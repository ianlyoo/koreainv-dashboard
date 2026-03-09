from __future__ import annotations

import os
import uuid
from dataclasses import dataclass

from fastapi import HTTPException, Request, Response


@dataclass
class SessionData:
    app_key: str
    app_secret: str
    cano: str
    acnt_prdt_cd: str


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
