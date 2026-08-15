from __future__ import annotations

import secrets

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from app import config, toss_api_client


router = APIRouter(prefix="/api/toss-proxy", tags=["toss-proxy"])


class TossProxyCredentialRequest(BaseModel):
    client_id: str = Field(min_length=1, max_length=256)
    client_secret: str = Field(min_length=1, max_length=512)


class TossProxyDashboardRequest(TossProxyCredentialRequest):
    account_seq: str = Field(min_length=1, max_length=19)


def _require_proxy_access(request: Request) -> None:
    if not config.TOSS_PROXY_SERVER_ENABLED:
        raise HTTPException(status_code=404, detail="Not found")
    configured = config.TOSS_PROXY_SERVER_TOKEN
    supplied = request.headers.get("Authorization", "")
    expected = f"Bearer {configured}" if configured else ""
    if not expected or not secrets.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail="Invalid proxy token")


@router.get("/status")
async def proxy_status(request: Request):
    _require_proxy_access(request)
    return {"status": "success", "enabled": True, "orders_supported": False}


@router.post("/accounts")
async def discover_accounts(request: Request, payload: TossProxyCredentialRequest):
    _require_proxy_access(request)
    try:
        accounts = await run_in_threadpool(
            toss_api_client.get_accounts,
            payload.client_id.strip(),
            payload.client_secret.strip(),
        )
    except Exception as exc:
        raise HTTPException(
            status_code=502, detail=f"Toss account discovery failed: {exc}"
        ) from exc
    return {"status": "success", "result": accounts}


@router.post("/dashboard")
async def load_dashboard(request: Request, payload: TossProxyDashboardRequest):
    _require_proxy_access(request)
    try:
        holdings, exchange_rate = await run_in_threadpool(
            toss_api_client.get_dashboard_source,
            payload.client_id.strip(),
            payload.client_secret.strip(),
            payload.account_seq.strip(),
        )
    except Exception as exc:
        raise HTTPException(
            status_code=502, detail=f"Toss dashboard lookup failed: {exc}"
        ) from exc
    return {
        "status": "success",
        "holdings": holdings,
        "exchange_rate": exchange_rate,
    }


@router.post("/balances")
async def load_balances(request: Request, payload: TossProxyDashboardRequest):
    _require_proxy_access(request)
    try:
        domestic, overseas = await run_in_threadpool(
            toss_api_client.get_balances,
            payload.client_id.strip(),
            payload.client_secret.strip(),
            payload.account_seq.strip(),
        )
    except Exception as exc:
        raise HTTPException(
            status_code=502, detail=f"Toss balance lookup failed: {exc}"
        ) from exc
    return {"status": "success", "domestic": domestic, "overseas": overseas}
