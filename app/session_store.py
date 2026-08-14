from __future__ import annotations

import os
import hashlib
import uuid
from dataclasses import dataclass, field

from fastapi import HTTPException, Request, Response


@dataclass
class AccountCredential:
    account_id: str
    label: str
    app_key: str
    app_secret: str
    cano: str
    acnt_prdt_cd: str

    @classmethod
    def make(
        cls,
        app_key: str,
        app_secret: str,
        cano: str,
        acnt_prdt_cd: str = "01",
        label: str = "계좌",
        account_id: str | None = None,
    ) -> "AccountCredential":
        safe_cano = str(cano or "").strip()
        safe_product = str(acnt_prdt_cd or "").strip() or "01"
        derived_id = build_account_id(safe_cano, safe_product)
        final_id = str(account_id or "").strip() or derived_id
        return cls(
            account_id=final_id,
            label=str(label or "").strip() or "계좌",
            app_key=str(app_key or "").strip(),
            app_secret=str(app_secret or "").strip(),
            cano=safe_cano,
            acnt_prdt_cd=safe_product,
        )

    def masked_metadata(self) -> dict[str, object]:
        key = self.app_key
        if len(key) > 8:
            masked_key = f"{key[:4]}****{key[-4:]}"
        elif key:
            masked_key = f"{key[:2]}****"
        else:
            masked_key = ""
        return {
            "account_id": self.account_id,
            "label": self.label,
            "masked_app_key": masked_key,
            "cano_masked": (
                f"****{self.cano[-4:]}" if len(self.cano) >= 4 else "****"
            ),
            "acnt_prdt_cd": self.acnt_prdt_cd,
        }


@dataclass
class SessionData:
    app_key: str
    app_secret: str
    cano: str
    acnt_prdt_cd: str
    accounts: list[AccountCredential] = field(default_factory=list)

    def __post_init__(self) -> None:
        # Backward compatibility: existing callers construct SessionData with
        # singular fields only; derive a single account from them.
        if not self.accounts:
            self.accounts = [
                AccountCredential.make(
                    self.app_key,
                    self.app_secret,
                    self.cano,
                    self.acnt_prdt_cd or "01",
                )
            ]

    @property
    def primary_account(self) -> AccountCredential:
        if self.accounts:
            return self.accounts[0]
        return AccountCredential.make(
            self.app_key,
            self.app_secret,
            self.cano,
            self.acnt_prdt_cd or "01",
        )


def build_account_id(cano: str, acnt_prdt_cd: str) -> str:
    """Stable account id derived from cano + product, never from secrets."""
    safe_cano = str(cano or "").strip()
    safe_product = str(acnt_prdt_cd or "").strip() or "01"
    digest = hashlib.sha256(
        f"{safe_cano}:{safe_product}".encode("utf-8")
    ).hexdigest()[:16]
    return f"acct_{digest}"


def ensure_unique_account_id(
    account_id: str, accounts: list[AccountCredential], exclude_id: str | None = None
) -> str:
    """Disambiguate an account id that collides with another stored account."""
    used = {
        existing.account_id
        for existing in accounts
        if existing.account_id and existing.account_id != exclude_id
    }
    if account_id not in used:
        return account_id
    suffix = 2
    while f"{account_id}-{suffix}" in used:
        suffix += 1
    return f"{account_id}-{suffix}"


def upsert_account(
    accounts: list[AccountCredential], candidate: AccountCredential
) -> tuple[list[AccountCredential], bool]:
    """Add or update an account keyed by cano + product. Returns (list, updated)."""
    result = list(accounts)
    for index, existing in enumerate(result):
        if (
            existing.cano == candidate.cano
            and existing.acnt_prdt_cd == candidate.acnt_prdt_cd
        ):
            result[index] = candidate
            return result, True
    result.append(candidate)
    return result, False


def accounts_metadata(
    accounts: list[AccountCredential],
) -> list[dict[str, object]]:
    """Masked, order-stable metadata for every account in a session."""
    metadata = [account.masked_metadata() for account in accounts]
    for index, entry in enumerate(metadata):
        entry["is_primary"] = index == 0
    return metadata


active_sessions: dict[str, SessionData] = {}
COOKIE_SECURE = os.getenv("COOKIE_SECURE", "false").lower() == "true"


def has_active_session(request: Request) -> bool:
    session_id = request.cookies.get("session")
    return bool(session_id and session_id in active_sessions)


def require_session(request: Request) -> SessionData:
    session_id = request.cookies.get("session")
    session = active_sessions.get(session_id)
    if not session:
        raise HTTPException(status_code=401, detail="Unauthorized")
    return session


def create_session(session_data: SessionData) -> str:
    session_id = str(uuid.uuid4())
    active_sessions[session_id] = session_data
    return session_id


def destroy_session(session_id: str | None) -> None:
    if session_id and session_id in active_sessions:
        del active_sessions[session_id]


def update_session_accounts(
    session_id: str | None, accounts: list[AccountCredential]
) -> None:
    """Replace the accounts (and derived primary fields) on an active session."""
    session = active_sessions.get(session_id or "")
    if session is None:
        return
    if not accounts:
        return
    primary = accounts[0]
    session.app_key = primary.app_key
    session.app_secret = primary.app_secret
    session.cano = primary.cano
    session.acnt_prdt_cd = primary.acnt_prdt_cd
    session.accounts = list(accounts)


def clear_all_sessions() -> None:
    active_sessions.clear()


def set_session_cookie(response: Response, session_id: str) -> None:
    response.set_cookie(
        key="session",
        value=session_id,
        httponly=True,
        samesite="lax",
        secure=COOKIE_SECURE,
        max_age=60 * 60 * 8,
    )


def clear_session_cookie(response: Response) -> None:
    response.delete_cookie("session")
