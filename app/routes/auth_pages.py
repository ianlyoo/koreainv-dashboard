from __future__ import annotations

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from app import auth
from app.session_store import (
    SessionData,
    clear_all_sessions,
    clear_session_cookie,
    create_session,
    destroy_session,
    has_active_session,
    set_session_cookie,
)
from app.template_utils import render_template_html


router = APIRouter()


def _remove_quote_session(request: Request) -> None:
    service = getattr(request.app.state, "us_quote_service", None)
    if service is not None:
        service.remove_session(request.cookies.get("session"))


def _decrypt_credentials(settings: dict, pin: str):
    crypto_version = settings.get("crypto_version", 1)
    salt = settings.get("kdf_salt")

    if crypto_version >= 2 and salt:
        app_key = auth.decrypt_data_v2(settings.get("api_key_enc", ""), pin, salt)
        app_secret = auth.decrypt_data_v2(settings.get("api_secret_enc", ""), pin, salt)
        cano = auth.decrypt_data_v2(settings.get("cano_enc", ""), pin, salt)
        acnt_prdt_cd = auth.decrypt_data_v2(
            settings.get("acnt_prdt_cd_enc", ""), pin, salt
        )
    else:
        app_key = auth.decrypt_data(settings.get("api_key_enc", ""), pin)
        app_secret = auth.decrypt_data(settings.get("api_secret_enc", ""), pin)
        cano = auth.decrypt_data(settings.get("cano_enc", ""), pin)
        acnt_prdt_cd = auth.decrypt_data(settings.get("acnt_prdt_cd_enc", ""), pin)

    return app_key, app_secret, cano, acnt_prdt_cd


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
    app_key: str = Form(...),
    app_secret: str = Form(...),
    cano: str = Form(...),
    acnt_prdt_cd: str = Form("01"),
    pin: str = Form(...),
):
    try:
        if auth.is_setup_complete():
            raise HTTPException(status_code=400, detail="Setup already complete")

        kdf_salt = auth.generate_kdf_salt()
        settings = {
            "setup_complete": True,
            "crypto_version": 2,
            "kdf_salt": kdf_salt,
            "api_key_enc": auth.encrypt_data_v2(app_key, pin, kdf_salt),
            "api_secret_enc": auth.encrypt_data_v2(app_secret, pin, kdf_salt),
            "cano_enc": auth.encrypt_data_v2(cano, pin, kdf_salt),
            "acnt_prdt_cd_enc": auth.encrypt_data_v2(acnt_prdt_cd, pin, kdf_salt),
            "pin_hash": auth.hash_pin(pin),
        }

        if auth.save_settings(settings):
            dec_app_key, dec_app_secret, dec_cano, dec_acnt_prdt_cd = (
                _decrypt_credentials(settings, pin)
            )
            if not dec_app_key or not dec_app_secret or not dec_cano:
                raise HTTPException(
                    status_code=500, detail="Credential validation failed after setup"
                )
            session_id = create_session(
                SessionData(
                    app_key=dec_app_key,
                    app_secret=dec_app_secret,
                    cano=dec_cano,
                    acnt_prdt_cd=dec_acnt_prdt_cd or "01",
                )
            )
            response = JSONResponse(
                {"status": "success", "message": "Setup successful"}
            )
            set_session_cookie(response, session_id)
            return response

        raise HTTPException(status_code=500, detail="Failed to save settings")
    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={"status": "error", "detail": f"Internal Server Error: {str(exc)}"},
        )


@router.post("/api/login")
async def login(pin: str = Form(...)):
    settings = auth.load_settings()
    if not settings.get("setup_complete"):
        raise HTTPException(status_code=400, detail="Setup not complete")

    pin_hash = settings.get("pin_hash")
    if not auth.verify_pin(pin, pin_hash):
        raise HTTPException(status_code=401, detail="Invalid PIN")

    app_key, app_secret, cano, acnt_prdt_cd = _decrypt_credentials(settings, pin)
    if not app_key or not app_secret or not cano:
        raise HTTPException(
            status_code=401,
            detail="Failed to decrypt credentials. Invalid PIN or corrupted settings.",
        )

    session_id = create_session(
        SessionData(
            app_key=app_key,
            app_secret=app_secret,
            cano=cano,
            acnt_prdt_cd=acnt_prdt_cd or "01",
        )
    )
    response = JSONResponse({"status": "success", "message": "Login successful"})
    set_session_cookie(response, session_id)
    return response


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
        clear_all_sessions()
        response = JSONResponse({"status": "success", "message": "Settings reset"})
        clear_session_cookie(response)
        return response

    raise HTTPException(status_code=500, detail="Failed to reset settings")
