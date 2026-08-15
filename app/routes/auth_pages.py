from __future__ import annotations

import json
from collections.abc import Mapping

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from app import api_client, auth, toss_api_client, toss_proxy_client
from app.session_store import (
    AccountCredential,
    SessionData,
    accounts_metadata,
    clear_all_sessions,
    clear_session_cookie,
    create_session,
    destroy_session,
    ensure_unique_account_id,
    has_active_session,
    require_session,
    set_session_cookie,
    update_session_accounts,
    upsert_account,
)
from app.template_utils import render_template_html


router = APIRouter()


def _is_valid_toss_account_seq(value: str) -> bool:
    return value.isdigit() and 0 < int(value) <= 9_223_372_036_854_775_807


def _remove_quote_session(request: Request) -> None:
    service = getattr(request.app.state, "us_quote_service", None)
    if service is not None:
        service.remove_session(request.cookies.get("session"))


def _crypto_context(settings: Mapping[str, object]) -> tuple[int, str | None]:
    crypto_version_raw = settings.get("crypto_version", 1)
    crypto_version = (
        int(crypto_version_raw) if isinstance(crypto_version_raw, (int, float)) else 1
    )
    salt_raw = settings.get("kdf_salt")
    salt = salt_raw if isinstance(salt_raw, str) else None
    return crypto_version, salt


def _decrypt_credentials(settings: Mapping[str, object], pin: str):
    crypto_version, salt = _crypto_context(settings)

    def _encrypted_value(key: str) -> str:
        value = settings.get(key, "")
        return value if isinstance(value, str) else ""

    if crypto_version >= 2 and salt:
        app_key = auth.decrypt_data_v2(_encrypted_value("api_key_enc"), pin, salt)
        app_secret = auth.decrypt_data_v2(_encrypted_value("api_secret_enc"), pin, salt)
        cano = auth.decrypt_data_v2(_encrypted_value("cano_enc"), pin, salt)
        acnt_prdt_cd = auth.decrypt_data_v2(
            _encrypted_value("acnt_prdt_cd_enc"), pin, salt
        )
    else:
        app_key = auth.decrypt_data(_encrypted_value("api_key_enc"), pin)
        app_secret = auth.decrypt_data(_encrypted_value("api_secret_enc"), pin)
        cano = auth.decrypt_data(_encrypted_value("cano_enc"), pin)
        acnt_prdt_cd = auth.decrypt_data(_encrypted_value("acnt_prdt_cd_enc"), pin)

    return app_key, app_secret, cano, acnt_prdt_cd


def _try_decrypt_account_records(
    settings: Mapping[str, object], pin: str
) -> list[dict[str, object]] | None:
    """Decrypt the accounts_enc JSON array when present; None otherwise."""
    crypto_version, salt = _crypto_context(settings)
    accounts_enc = settings.get("accounts_enc")
    if not (isinstance(accounts_enc, str) and accounts_enc):
        return None
    if crypto_version < 2 or not salt:
        return None
    try:
        raw = auth.decrypt_data_v2(accounts_enc, pin, salt)
        if not raw:
            return None
        parsed = json.loads(raw)
        if not isinstance(parsed, list):
            return None
        records = [
            record
            for record in parsed
            if isinstance(record, dict)
            and str(record.get("app_key") or "").strip()
            and str(record.get("app_secret") or "").strip()
            and str(record.get("cano") or "").strip()
        ]
        return records or None
    except Exception:
        return None


def decrypt_accounts_for_session(
    settings: Mapping[str, object], pin: str
) -> list[AccountCredential]:
    """Decrypt all stored accounts, falling back to legacy singular settings."""
    records = _try_decrypt_account_records(settings, pin)
    if records is None:
        app_key, app_secret, cano, acnt_prdt_cd = _decrypt_credentials(settings, pin)
        records = [
            {
                "app_key": app_key,
                "app_secret": app_secret,
                "cano": cano,
                "acnt_prdt_cd": acnt_prdt_cd or "01",
                "label": "계좌",
            }
        ]

    accounts: list[AccountCredential] = []
    for record in records:
        app_key = str(record.get("app_key") or "").strip()
        app_secret = str(record.get("app_secret") or "").strip()
        cano = str(record.get("cano") or "").strip()
        if not (app_key and app_secret and cano):
            continue
        accounts.append(
            AccountCredential.make(
                app_key=app_key,
                app_secret=app_secret,
                cano=cano,
                acnt_prdt_cd=str(record.get("acnt_prdt_cd") or "01").strip() or "01",
                label=str(record.get("label") or "계좌").strip() or "계좌",
                account_id=str(record.get("account_id") or "").strip() or None,
                broker=str(record.get("broker") or "kis").strip().lower(),
            )
        )
    return accounts


def decrypt_credentials_for_session(settings: Mapping[str, object], pin: str):
    """Primary-account credentials, kept for backward compatibility."""
    accounts = decrypt_accounts_for_session(settings, pin)
    if not accounts:
        return None, None, None, None
    primary = accounts[0]
    return primary.app_key, primary.app_secret, primary.cano, primary.acnt_prdt_cd


def _parse_accounts_json(accounts_json: str) -> list[dict[str, object]] | None:
    raw = str(accounts_json or "").strip()
    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except (ValueError, TypeError) as exc:
        raise HTTPException(
            status_code=400, detail="accounts_json must be a valid JSON array"
        ) from exc
    if not isinstance(parsed, list):
        raise HTTPException(
            status_code=400,
            detail="accounts_json must be a JSON array of account objects",
        )
    records: list[dict[str, object]] = []
    for entry in parsed:
        if not isinstance(entry, dict):
            raise HTTPException(
                status_code=400, detail="Each account must be a JSON object"
            )
        app_key = str(entry.get("app_key") or "").strip()
        app_secret = str(entry.get("app_secret") or "").strip()
        cano = str(entry.get("cano") or "").strip()
        broker = str(entry.get("broker") or "kis").strip().lower()
        if broker not in {"kis", "toss"}:
            raise HTTPException(status_code=400, detail="Unsupported broker")
        if not (app_key and app_secret and cano):
            raise HTTPException(
                status_code=400,
                detail="Each account requires credentials and an account reference",
            )
        if broker == "toss" and not _is_valid_toss_account_seq(cano):
            raise HTTPException(
                status_code=400, detail="Toss account_seq must be a positive integer"
            )
        records.append(
            {
                "app_key": app_key,
                "app_secret": app_secret,
                "cano": cano,
                "acnt_prdt_cd": str(entry.get("acnt_prdt_cd") or "01").strip() or "01",
                "label": str(entry.get("label") or "계좌").strip() or "계좌",
                "account_id": str(entry.get("account_id") or "").strip() or None,
                "broker": broker,
            }
        )
    if not records:
        raise HTTPException(
            status_code=400, detail="accounts_json must contain at least one account"
        )
    return records


def _normalize_account_records(
    records: list[dict[str, object]],
) -> list[AccountCredential]:
    """Build accounts with stable ids, deduplicating cano+product collisions."""
    normalized: list[AccountCredential] = []
    for record in records:
        candidate = AccountCredential.make(
            app_key=str(record.get("app_key") or ""),
            app_secret=str(record.get("app_secret") or ""),
            cano=str(record.get("cano") or ""),
            acnt_prdt_cd=str(record.get("acnt_prdt_cd") or "01"),
            label=str(record.get("label") or "계좌"),
            account_id=str(record.get("account_id") or "").strip() or None,
            broker=str(record.get("broker") or "kis").strip().lower(),
        )
        if any(
            existing.broker == candidate.broker
            and existing.cano == candidate.cano
            and existing.acnt_prdt_cd == candidate.acnt_prdt_cd
            for existing in normalized
        ):
            continue
        candidate.account_id = ensure_unique_account_id(
            candidate.account_id, normalized
        )
        normalized.append(candidate)
    return normalized


def _serialize_accounts_for_storage(
    accounts: list[AccountCredential],
) -> list[dict[str, str]]:
    return [
        {
            "account_id": account.account_id,
            "label": account.label,
            "app_key": account.app_key,
            "app_secret": account.app_secret,
            "cano": account.cano,
            "acnt_prdt_cd": account.acnt_prdt_cd,
            "broker": account.broker,
        }
        for account in accounts
    ]


def _persist_accounts(
    settings: dict[str, object],
    accounts: list[AccountCredential],
    pin: str,
) -> None:
    if not accounts:
        raise HTTPException(status_code=400, detail="At least one account is required")
    crypto_version, kdf_salt = _crypto_context(settings)
    if crypto_version < 2 or not kdf_salt:
        kdf_salt = auth.generate_kdf_salt()
        settings["crypto_version"] = 2
        settings["kdf_salt"] = kdf_salt
    records = _serialize_accounts_for_storage(accounts)
    settings["accounts_enc"] = auth.encrypt_data_v2(
        json.dumps(records), pin, kdf_salt
    )
    primary = next(
        (account for account in accounts if account.broker == "kis"), accounts[0]
    )
    settings["api_key_enc"] = auth.encrypt_data_v2(primary.app_key, pin, kdf_salt)
    settings["api_secret_enc"] = auth.encrypt_data_v2(
        primary.app_secret, pin, kdf_salt
    )
    settings["cano_enc"] = auth.encrypt_data_v2(primary.cano, pin, kdf_salt)
    settings["acnt_prdt_cd_enc"] = auth.encrypt_data_v2(
        primary.acnt_prdt_cd, pin, kdf_salt
    )
    settings["setup_complete"] = True
    if not auth.save_settings(settings):
        raise HTTPException(status_code=500, detail="Failed to save settings")


def _accounts_metadata(
    accounts: list[AccountCredential],
) -> list[dict[str, object]]:
    return accounts_metadata(accounts)


def _verify_settings_pin(settings: Mapping[str, object], pin: str) -> bool:
    pin_hash = settings.get("pin_hash")
    if not isinstance(pin_hash, str):
        return False
    return auth.verify_pin(pin, pin_hash)


class AccountCreateRequest(BaseModel):
    pin: str
    label: str = "계좌"
    app_key: str
    app_secret: str
    cano: str
    acnt_prdt_cd: str = "01"
    broker: str = "kis"


class AccountDeleteRequest(BaseModel):
    pin: str


class AccountUpdateRequest(BaseModel):
    pin: str
    label: str = ""
    cano: str = ""
    acnt_prdt_cd: str = ""
    app_key: str = ""
    app_secret: str = ""
    broker: str = ""


class TossAccountDiscoveryRequest(BaseModel):
    client_id: str = ""
    client_secret: str = ""
    account_id: str = ""
    pin: str = ""


def _mask_toss_account_no(value: object) -> str:
    digits = "".join(character for character in str(value or "") if character.isdigit())
    if not digits:
        return ""
    return f"••••{digits[-4:]}" if len(digits) > 4 else digits


def _toss_account_options(rows: list[dict[str, object]]) -> list[dict[str, str]]:
    options: list[dict[str, str]] = []
    for row in rows:
        account_seq = str(row.get("accountSeq") or "").strip()
        if not _is_valid_toss_account_seq(account_seq):
            continue
        masked_number = _mask_toss_account_no(row.get("accountNo"))
        account_type = str(row.get("accountType") or "").strip()
        display_name = "토스증권 계좌"
        if masked_number:
            display_name += f" {masked_number}"
        options.append(
            {
                "account_seq": account_seq,
                "account_no_masked": masked_number,
                "account_type": account_type,
                "display_name": display_name,
            }
        )
    return options


@router.get("/", response_class=HTMLResponse)
async def read_root(request: Request):
    if not auth.is_setup_complete():
        return RedirectResponse(url="/login")

    if not has_active_session(request):
        return RedirectResponse(url="/login")

    return render_template_html("index.html")


@router.get("/login", response_class=HTMLResponse)
async def read_login():
    try:
        return render_template_html("login.html")
    except FileNotFoundError:
        return HTMLResponse(
            content="<h1>Login Page Needs to be created</h1>", status_code=404
        )


@router.get("/api/status")
async def get_status(request: Request):
    return {
        "status": "success",
        "setup_complete": auth.is_setup_complete(),
        "authenticated": has_active_session(request),
    }


@router.post("/api/setup")
async def setup_api(
    app_key: str = Form(""),
    app_secret: str = Form(""),
    cano: str = Form(""),
    acnt_prdt_cd: str = Form("01"),
    pin: str = Form(...),
    accounts_json: str = Form(""),
):
    try:
        if auth.is_setup_complete():
            raise HTTPException(status_code=400, detail="Setup already complete")

        kdf_salt = auth.generate_kdf_salt()
        records = _parse_accounts_json(accounts_json)
        if records is None:
            if not (app_key.strip() and app_secret.strip() and cano.strip()):
                raise HTTPException(
                    status_code=400,
                    detail="app_key, app_secret and cano are required",
                )
            records = [
                {
                    "app_key": app_key.strip(),
                    "app_secret": app_secret.strip(),
                    "cano": cano.strip(),
                    "acnt_prdt_cd": (acnt_prdt_cd or "01").strip() or "01",
                    "label": "계좌",
                }
            ]

        accounts = _normalize_account_records(records)
        if not accounts:
            raise HTTPException(
                status_code=400, detail="At least one valid account is required"
            )

        settings: dict[str, object] = {
            "setup_complete": True,
            "crypto_version": 2,
            "kdf_salt": kdf_salt,
            "pin_hash": auth.hash_pin(pin),
        }
        _persist_accounts(settings, accounts, pin)

        decrypted = decrypt_accounts_for_session(settings, pin)
        if not decrypted:
            raise HTTPException(
                status_code=500, detail="Credential validation failed after setup"
            )
        primary = next(
            (account for account in decrypted if account.broker == "kis"), decrypted[0]
        )
        session_id = create_session(
            SessionData(
                app_key=primary.app_key,
                app_secret=primary.app_secret,
                cano=primary.cano,
                acnt_prdt_cd=primary.acnt_prdt_cd,
                accounts=decrypted,
            )
        )
        response = JSONResponse(
            {"status": "success", "message": "Setup successful"}
        )
        set_session_cookie(response, session_id)
        return response
    except HTTPException:
        raise
    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={"status": "error", "detail": f"Internal Server Error: {str(exc)}"},
        )


@router.post("/api/login")
async def login(pin: str = Form(...)):
    settings: dict[str, object] = auth.load_settings()
    if not settings.get("setup_complete"):
        raise HTTPException(status_code=400, detail="Setup not complete")

    pin_hash = settings.get("pin_hash")
    if not isinstance(pin_hash, str):
        raise HTTPException(status_code=500, detail="Stored PIN is invalid")
    if not auth.verify_pin(pin, pin_hash):
        raise HTTPException(status_code=401, detail="Invalid PIN")

    accounts = decrypt_accounts_for_session(settings, pin)
    if not accounts:
        raise HTTPException(
            status_code=401,
            detail="Failed to decrypt credentials. Invalid PIN or corrupted settings.",
        )
    primary = next(
        (account for account in accounts if account.broker == "kis"), accounts[0]
    )

    session_id = create_session(
        SessionData(
            app_key=primary.app_key,
            app_secret=primary.app_secret,
            cano=primary.cano,
            acnt_prdt_cd=primary.acnt_prdt_cd,
            accounts=accounts,
        )
    )
    response = JSONResponse({"status": "success", "message": "Login successful"})
    set_session_cookie(response, session_id)
    return response


@router.get("/api/accounts")
async def list_accounts(request: Request):
    session = require_session(request)
    return {
        "status": "success",
        "accounts": _accounts_metadata(session.accounts),
        "primary_account_id": session.primary_account.account_id,
    }


@router.post("/api/toss/accounts/discover")
async def discover_toss_accounts(
    request: Request, payload: TossAccountDiscoveryRequest
):
    client_id = payload.client_id.strip()
    client_secret = payload.client_secret.strip()
    if bool(client_id) != bool(client_secret):
        raise HTTPException(
            status_code=400, detail="CLIENT ID and CLIENT SECRET are both required"
        )

    if not client_id:
        require_session(request)
        settings = auth.load_settings()
        if not payload.account_id.strip() or not _verify_settings_pin(
            settings, payload.pin
        ):
            raise HTTPException(
                status_code=401,
                detail="PIN is required to load accounts with stored credentials",
            )
        stored_accounts = decrypt_accounts_for_session(settings, payload.pin)
        target = next(
            (
                account
                for account in stored_accounts
                if account.account_id == payload.account_id.strip()
                and account.broker == "toss"
            ),
            None,
        )
        if target is None:
            raise HTTPException(status_code=404, detail="Toss account not found")
        client_id = target.app_key
        client_secret = target.app_secret

    try:
        account_loader = (
            toss_proxy_client.get_accounts
            if toss_proxy_client.is_configured()
            else toss_api_client.get_accounts
        )
        rows = await run_in_threadpool(account_loader, client_id, client_secret)
    except Exception as exc:
        raise HTTPException(
            status_code=502, detail=f"Failed to load Toss accounts: {exc}"
        ) from exc

    return {
        "status": "success",
        "accounts": _toss_account_options(rows),
    }


@router.post("/api/accounts")
async def add_or_update_account(request: Request, payload: AccountCreateRequest):
    session = require_session(request)
    settings = auth.load_settings()
    if not _verify_settings_pin(settings, payload.pin):
        raise HTTPException(status_code=401, detail="Invalid PIN")
    broker = payload.broker.strip().lower() or "kis"
    if broker not in {"kis", "toss"}:
        raise HTTPException(status_code=400, detail="Unsupported broker")
    if not (
        payload.app_key.strip()
        and payload.app_secret.strip()
        and payload.cano.strip()
    ):
        raise HTTPException(
            status_code=400, detail="credentials and account reference are required"
        )
    if broker == "toss" and not _is_valid_toss_account_seq(payload.cano.strip()):
        raise HTTPException(
            status_code=400, detail="Toss account_seq must be a positive integer"
        )

    current = decrypt_accounts_for_session(settings, payload.pin)
    if not current:
        raise HTTPException(
            status_code=500, detail="Failed to decrypt stored accounts"
        )

    candidate = AccountCredential.make(
        app_key=payload.app_key.strip(),
        app_secret=payload.app_secret.strip(),
        cano=payload.cano.strip(),
        acnt_prdt_cd=(payload.acnt_prdt_cd or "01").strip() or "01",
        label=payload.label.strip() or "계좌",
        broker=broker,
    )
    matched = next(
        (
            account
            for account in current
            if account.broker == candidate.broker
            and account.cano == candidate.cano
            and account.acnt_prdt_cd == candidate.acnt_prdt_cd
        ),
        None,
    )
    if matched is not None:
        candidate.account_id = matched.account_id
    updated, was_updated = upsert_account(current, candidate)
    if not was_updated:
        candidate.account_id = ensure_unique_account_id(
            candidate.account_id, current
        )
        updated = list(updated)
        updated[-1] = candidate

    _persist_accounts(settings, updated, payload.pin)
    update_session_accounts(request.cookies.get("session"), updated)
    return {
        "status": "success",
        "account": candidate.masked_metadata(),
        "updated": was_updated,
        "accounts": _accounts_metadata(updated),
    }


@router.patch("/api/accounts/{account_id}")
async def update_account(
    request: Request, account_id: str, payload: AccountUpdateRequest
):
    session = require_session(request)
    settings = auth.load_settings()
    if not _verify_settings_pin(settings, payload.pin):
        raise HTTPException(status_code=401, detail="Invalid PIN")

    current = decrypt_accounts_for_session(settings, payload.pin)
    if not current:
        raise HTTPException(
            status_code=500, detail="Failed to decrypt stored accounts"
        )

    target = next(
        (account for account in current if account.account_id == account_id), None
    )
    if target is None:
        raise HTTPException(status_code=404, detail="Account not found")

    label = payload.label.strip()
    cano = payload.cano.strip()
    acnt_prdt_cd = payload.acnt_prdt_cd.strip()
    app_key = payload.app_key.strip()
    app_secret = payload.app_secret.strip()
    broker = payload.broker.strip().lower()
    if broker and broker not in {"kis", "toss"}:
        raise HTTPException(status_code=400, detail="Unsupported broker")
    if not (label or cano or acnt_prdt_cd or app_key or app_secret or broker):
        raise HTTPException(
            status_code=400,
            detail=(
                "At least one of label, cano, acnt_prdt_cd, app_key or "
                "app_secret is required"
            ),
        )

    new_broker = broker or target.broker
    if broker and broker != target.broker and not (cano and app_key and app_secret):
        raise HTTPException(
            status_code=400,
            detail="Changing broker requires new credentials and account reference",
        )
    new_cano = cano or target.cano
    new_product = (acnt_prdt_cd or target.acnt_prdt_cd) if new_broker == "kis" else ""
    if new_broker == "toss" and not _is_valid_toss_account_seq(new_cano):
        raise HTTPException(
            status_code=400, detail="Toss account_seq must be a positive integer"
        )
    if any(
        account.account_id != account_id
        and account.broker == new_broker
        and account.cano == new_cano
        and account.acnt_prdt_cd == new_product
        for account in current
    ):
        raise HTTPException(
            status_code=409,
            detail="An account with the same cano and acnt_prdt_cd already exists",
        )

    updated_account = AccountCredential.make(
        app_key=app_key or target.app_key,
        app_secret=app_secret or target.app_secret,
        cano=new_cano,
        acnt_prdt_cd=new_product,
        label=label or target.label,
        account_id=account_id,
        broker=new_broker,
    )
    updated = [
        updated_account if account.account_id == account_id else account
        for account in current
    ]

    _persist_accounts(settings, updated, payload.pin)
    update_session_accounts(request.cookies.get("session"), updated)
    return {
        "status": "success",
        "account": updated_account.masked_metadata(),
        "updated": True,
        "accounts": _accounts_metadata(updated),
    }


@router.post("/api/accounts/{account_id}/delete")
async def delete_account(request: Request, account_id: str, payload: AccountDeleteRequest):
    session = require_session(request)
    settings = auth.load_settings()
    if not _verify_settings_pin(settings, payload.pin):
        raise HTTPException(status_code=401, detail="Invalid PIN")

    current = decrypt_accounts_for_session(settings, payload.pin)
    if not current:
        raise HTTPException(
            status_code=500, detail="Failed to decrypt stored accounts"
        )
    if len(current) <= 1:
        raise HTTPException(status_code=400, detail="Cannot delete the last account")

    remaining = [
        account for account in current if account.account_id != account_id
    ]
    if len(remaining) == len(current):
        raise HTTPException(status_code=404, detail="Account not found")

    _persist_accounts(settings, remaining, payload.pin)
    update_session_accounts(request.cookies.get("session"), remaining)
    return {
        "status": "success",
        "message": "Account deleted",
        "accounts": _accounts_metadata(remaining),
        "primary_account_id": next(
            (account.account_id for account in remaining if account.broker == "kis"),
            remaining[0].account_id,
        ),
    }


@router.post("/api/logout")
async def logout(request: Request):
    _remove_quote_session(request)
    destroy_session(request.cookies.get("session"))
    response = JSONResponse({"status": "success", "message": "Logged out"})
    clear_session_cookie(response)
    return response


@router.post("/api/reset")
async def reset_settings(request: Request):
    if not has_active_session(request):
        raise HTTPException(status_code=401, detail="Unauthorized")

    if auth.delete_settings():
        _remove_quote_session(request)
        api_client.clear_persisted_token_cache()
        clear_all_sessions()
        response = JSONResponse({"status": "success", "message": "Settings reset"})
        clear_session_cookie(response)
        return response

    raise HTTPException(status_code=500, detail="Failed to reset settings")
