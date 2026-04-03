from __future__ import annotations

import asyncio
import datetime as dt
from typing import Any

import requests
from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

from app import api_client, config
from app.services.scheduled_order_store import LIVE_EDITABLE_STATUSES, ScheduledOrderStore, build_account_ref
from app.session_store import SessionData, require_session

router = APIRouter()
KST = dt.timezone(dt.timedelta(hours=9))
SESSION_START_HOUR = 8
SESSION_END_HOUR = 20


class ScheduledDomesticOrderRequest(BaseModel):
    end_at: str | None = None
    execute_at: str | None = None
    side: str
    pdno: str
    ord_qty: int = Field(..., ge=1)
    ord_unpr: str
    ord_dvsn: str = "00"
    excg_id_dvsn_cd: str = "SOR"
    sll_type: str = ""
    cndt_pric: str = ""
    note: str = ""


class ExecutionCredentialsModel(BaseModel):
    app_key: str
    app_secret: str
    cano: str
    acnt_prdt_cd: str = "01"


class RemoteScheduledOrderCreateRequest(ScheduledDomesticOrderRequest):
    execution_credentials: ExecutionCredentialsModel
    source_app: str = "remote"


def _now_kst() -> dt.datetime:
    return dt.datetime.now(KST)


def _is_submit_window(now: dt.datetime) -> bool:
    return now.weekday() < 5 and (now.hour, now.minute) >= (SESSION_START_HOUR, 0) and (now.hour, now.minute) < (SESSION_END_HOUR, 0)


def _next_session_start(now: dt.datetime) -> dt.datetime:
    cursor = now
    if cursor.weekday() < 5 and (cursor.hour, cursor.minute) < (SESSION_START_HOUR, 0):
        return cursor.replace(hour=SESSION_START_HOUR, minute=0, second=0, microsecond=0)
    cursor = (cursor + dt.timedelta(days=1)).replace(hour=SESSION_START_HOUR, minute=0, second=0, microsecond=0)
    while cursor.weekday() >= 5:
        cursor += dt.timedelta(days=1)
    return cursor


def _extract_end_at_value(payload: ScheduledDomesticOrderRequest) -> str:
    value = str(payload.end_at or payload.execute_at or "").strip()
    if not value:
        raise HTTPException(status_code=400, detail="end_at is required")
    return value


def _get_scheduled_order_write_availability() -> dict[str, Any]:
    now_kst = dt.datetime.now(KST)
    return {
        "allowed": True,
        "blocked": False,
        "reason": "",
        "current_kst": now_kst.isoformat(timespec="seconds"),
        "policy": {
            "timezone": "Asia/Seoul",
            "rule": "persistent_condition_order",
        },
    }


def _parse_kst_datetime(value: str, field_name: str) -> dt.datetime:
    try:
        parsed = dt.datetime.fromisoformat(str(value or "").strip())
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Invalid {field_name}. Use ISO 8601.") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=KST)
    return parsed.astimezone(KST)


def _validate_end_at(value: str) -> str:
    parsed = _parse_kst_datetime(value, "end_at")
    if parsed <= _now_kst():
        raise HTTPException(status_code=400, detail="end_at must be in the future")
    return parsed.isoformat(timespec="seconds")


def _compute_initial_execute_at(end_at: str) -> str:
    now = _now_kst()
    end_dt = _parse_kst_datetime(end_at, "end_at")
    if end_dt <= now:
        raise HTTPException(status_code=400, detail="end_at must be in the future")
    if _is_submit_window(now):
        return now.isoformat(timespec="seconds")
    next_start = _next_session_start(now)
    if next_start >= end_dt:
        raise HTTPException(status_code=400, detail="end_at must be after the next eligible submit session")
    return next_start.isoformat(timespec="seconds")


def _normalize_order_payload(payload: ScheduledDomesticOrderRequest) -> dict[str, str]:
    side = payload.side.strip().lower()
    if side not in {"buy", "sell"}:
        raise HTTPException(status_code=400, detail="side must be 'buy' or 'sell'")
    normalized = {
        "side": side,
        "pdno": payload.pdno.strip(),
        "ord_qty": str(payload.ord_qty),
        "ord_unpr": str(payload.ord_unpr).strip(),
        "ord_dvsn": payload.ord_dvsn.strip() or "00",
        "excg_id_dvsn_cd": payload.excg_id_dvsn_cd.strip() or "SOR",
        "sll_type": payload.sll_type.strip(),
        "cndt_pric": payload.cndt_pric.strip(),
    }
    _validate_domestic_order_payload(normalized)
    return normalized


def _validate_domestic_order_payload(order: dict[str, str]) -> None:
    pdno = str(order.get("pdno") or "").strip()
    if not pdno.isdigit() or len(pdno) not in {6, 7}:
        raise HTTPException(status_code=400, detail="Domestic scheduled orders require a 6-7 digit Korean symbol code")
    exchange = str(order.get("excg_id_dvsn_cd") or "KRX").strip().upper()
    if exchange not in {"KRX", "NXT", "SOR"}:
        raise HTTPException(status_code=400, detail="Domestic scheduled orders only support KRX, NXT, or SOR routing")


def _assert_live_order_update_payload(current_order: dict[str, Any], next_order: dict[str, str]) -> None:
    locked_fields = {
        "side": "매매 구분",
        "pdno": "종목 코드",
        "ord_qty": "주문 수량",
        "ord_unpr": "주문 단가",
        "ord_dvsn": "주문 유형",
        "excg_id_dvsn_cd": "거래소",
        "sll_type": "매도 유형",
        "cndt_pric": "조건 가격",
    }
    for field, label in locked_fields.items():
        current_value = str(current_order.get(field) or "").strip()
        next_value = str(next_order.get(field) or "").strip()
        if current_value != next_value:
            raise HTTPException(status_code=409, detail=f"브로커에 제출된 주문은 {label}을 수정할 수 없습니다")


def _assert_sell_orderable(order: dict[str, str], credentials: dict[str, str]) -> None:
    if str(order.get("side") or "").strip().lower() != "sell":
        return

    token = api_client.get_access_token(credentials["app_key"], credentials["app_secret"])
    if not token:
        raise HTTPException(status_code=503, detail="Failed to acquire KIS access token")

    pdno = str(order.get("pdno") or "").strip()
    ord_qty = api_client._to_int(order.get("ord_qty") or 0)
    if not pdno:
        raise HTTPException(status_code=400, detail="매도 종목 정보를 확인해 주세요")
    if ord_qty <= 0:
        raise HTTPException(status_code=400, detail="매도 주문 수량이 유효하지 않습니다")

    psbl_rows = api_client.inquire_domestic_psbl_sell(
        token,
        credentials["app_key"],
        credentials["app_secret"],
        credentials["cano"],
        credentials["acnt_prdt_cd"],
        pdno=pdno,
        ord_dvsn=str(order.get("ord_dvsn") or "00"),
        excg_id_dvsn_cd=str(order.get("excg_id_dvsn_cd") or "KRX"),
    )
    psbl_row = next((row for row in psbl_rows if str(row.get("pdno") or "") == pdno), None)
    if psbl_row is None:
        raise HTTPException(status_code=400, detail="매도 가능 수량 조회 결과를 찾을 수 없습니다")

    ord_psbl_qty = api_client._to_int(psbl_row.get("ord_psbl_qty") or psbl_row.get("psbl_qty") or 0)
    if ord_psbl_qty <= 0:
        raise HTTPException(status_code=400, detail="매도 가능 수량이 0주입니다")
    if ord_qty > ord_psbl_qty:
        raise HTTPException(
            status_code=400,
            detail=f"매도 가능 수량을 초과했습니다. 주문 수량 {ord_qty}주, 가능 수량 {ord_psbl_qty}주",
        )


def _can_broker_cancel(item: dict[str, Any]) -> bool:
    status = str(item.get("status") or "")
    return status in {"submitted", "open", "cancel_requested"}


def _build_broker_cancel_snapshot(psbl_row: dict[str, Any]) -> dict[str, Any]:
    return {
        "odno": str(psbl_row.get("odno") or "").strip(),
        "orgn_odno": str(psbl_row.get("orgn_odno") or "").strip(),
        "psbl_qty": int(psbl_row.get("psbl_qty") or 0),
        "ord_qty": int(psbl_row.get("ord_qty") or 0),
        "tot_ccld_qty": int(psbl_row.get("tot_ccld_qty") or 0),
        "ord_dvsn_cd": str(psbl_row.get("ord_dvsn_cd") or "").strip(),
        "excg_id_dvsn_cd": str(psbl_row.get("excg_id_dvsn_cd") or "").strip(),
    }


def _cancel_broker_order(store: ScheduledOrderStore, item: dict[str, Any]) -> dict[str, Any]:
    credentials = store.decrypt_execution_credentials(item)
    raw_broker_order = item.get("broker_order")
    raw_order = item.get("order")
    broker_order: dict[str, Any] = raw_broker_order if isinstance(raw_broker_order, dict) else {}
    order: dict[str, Any] = raw_order if isinstance(raw_order, dict) else {}
    odno = str(broker_order.get("odno") or "")
    if not odno:
        raise HTTPException(status_code=409, detail="Broker order number is missing")
    token = api_client.get_access_token(credentials["app_key"], credentials["app_secret"])
    if not token:
        raise HTTPException(status_code=503, detail="Failed to acquire KIS access token")
    psbl_rows = api_client.inquire_domestic_psbl_rvsecncl(
        token,
        credentials["app_key"],
        credentials["app_secret"],
        credentials["cano"],
        credentials["acnt_prdt_cd"],
    )
    candidate = next((row for row in psbl_rows if str(row.get("odno") or "") == odno), None)
    candidate_dict: dict[str, Any] | None = candidate if isinstance(candidate, dict) else None
    if candidate_dict is None or api_client._to_int(candidate_dict.get("psbl_qty") or 0) <= 0:
        raise HTTPException(status_code=409, detail="Broker cancel is no longer available for this order")
    api_client.cancel_domestic_order(
        token,
        credentials["app_key"],
        credentials["app_secret"],
        credentials["cano"],
        credentials["acnt_prdt_cd"],
        krx_fwdg_ord_orgno=str(broker_order.get("krx_fwdg_ord_orgno") or ""),
        orgn_odno=odno,
        ord_qty=str(candidate_dict.get("psbl_qty") or order.get("ord_qty") or ""),
        ord_unpr=str(order.get("ord_unpr") or ""),
        ord_dvsn=str(order.get("ord_dvsn") or "00"),
        excg_id_dvsn_cd=str(order.get("excg_id_dvsn_cd") or "SOR"),
        qty_all_ord_yn="Y",
    )
    updated = store.mark_broker_cancel_requested(item["id"], broker_snapshot=_build_broker_cancel_snapshot(candidate_dict))
    if updated is None:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    return updated


def _store_or_503(request: Request) -> ScheduledOrderStore:
    store = getattr(request.app.state, "scheduled_order_store", None)
    if not isinstance(store, ScheduledOrderStore):
        raise HTTPException(status_code=503, detail="Scheduled order server is not enabled")
    return store


def _extract_bearer_token(authorization: str | None) -> str:
    value = str(authorization or "").strip()
    if value.lower().startswith("bearer "):
        return value[7:].strip()
    return value


def _require_server_token(authorization: str | None) -> None:
    token = _extract_bearer_token(authorization)
    if not config.CENTRAL_ORDER_SERVER_TOKEN or token != config.CENTRAL_ORDER_SERVER_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid central order token")


def _session_credentials(session: SessionData) -> dict[str, str]:
    return {
        "app_key": session.app_key,
        "app_secret": session.app_secret,
        "cano": session.cano,
        "acnt_prdt_cd": session.acnt_prdt_cd,
    }


async def _forward_to_remote(
    *,
    payload: RemoteScheduledOrderCreateRequest | None,
    method: str,
    path: str,
) -> Any:
    if not config.CENTRAL_ORDER_REMOTE_URL or not config.CENTRAL_ORDER_REMOTE_TOKEN:
        raise HTTPException(status_code=503, detail="Central order remote is not configured")
    url = f"{config.CENTRAL_ORDER_REMOTE_URL}{path}"
    headers = {
        "Authorization": f"Bearer {config.CENTRAL_ORDER_REMOTE_TOKEN}",
        "Content-Type": "application/json",
    }
    if method == "GET":
        response = await asyncio.to_thread(requests.get, url, headers=headers, timeout=10)
    elif method == "PUT":
        if payload is None:
            raise HTTPException(status_code=500, detail="central_order_remote_payload_missing")
        response = await asyncio.to_thread(
            requests.put,
            url,
            headers=headers,
            timeout=10,
            json=payload.model_dump(),
        )
    else:
        if payload is None:
            raise HTTPException(status_code=500, detail="central_order_remote_payload_missing")
        response = await asyncio.to_thread(
            requests.post,
            url,
            headers=headers,
            timeout=10,
            json=payload.model_dump(),
        )
    try:
        data = response.json()
    except Exception:
        data = {"detail": response.text[:300]}
    if response.status_code >= 400:
        raise HTTPException(status_code=response.status_code, detail=data.get("detail") or "central_order_remote_failed")
    return data


@router.post("/api/scheduled-orders")
async def create_scheduled_order(request: Request, payload: ScheduledDomesticOrderRequest):
    session = require_session(request)
    credentials = _session_credentials(session)
    remote_payload = RemoteScheduledOrderCreateRequest(
        **payload.model_dump(),
        execution_credentials=ExecutionCredentialsModel(**credentials),
        source_app="desktop-web",
    )

    if config.CENTRAL_ORDER_REMOTE_URL and not config.CENTRAL_ORDER_SERVER_MODE:
        return await _forward_to_remote(
            payload=remote_payload,
            method="POST",
            path="/api/central-server/scheduled-orders",
        )

    store = _store_or_503(request)
    end_at = _validate_end_at(_extract_end_at_value(payload))
    normalized_order = _normalize_order_payload(payload)
    await asyncio.to_thread(_assert_sell_orderable, normalized_order, credentials)
    created = store.create_order(
        execution_credentials=remote_payload.execution_credentials.model_dump(),
        order_payload=normalized_order,
        execute_at=_compute_initial_execute_at(end_at),
        end_at=end_at,
        source_app="desktop-web",
        note=payload.note,
    )
    return {"status": "success", "order": created}


@router.get("/api/scheduled-orders")
async def list_scheduled_orders(request: Request):
    session = require_session(request)
    account_ref = build_account_ref(session.app_key, session.cano, session.acnt_prdt_cd)
    if config.CENTRAL_ORDER_REMOTE_URL and not config.CENTRAL_ORDER_SERVER_MODE:
        url = f"{config.CENTRAL_ORDER_REMOTE_URL}/api/central-server/scheduled-orders?account_ref={account_ref}"
        headers = {"Authorization": f"Bearer {config.CENTRAL_ORDER_REMOTE_TOKEN}"}
        response = await asyncio.to_thread(requests.get, url, headers=headers, timeout=10)
        try:
            data = response.json()
        except Exception:
            data = {"detail": response.text[:300]}
        if response.status_code >= 400:
            raise HTTPException(status_code=response.status_code, detail=data.get("detail") or "central_order_remote_failed")
        return data
    store = _store_or_503(request)
    return {"status": "success", "orders": store.list_orders(account_ref=account_ref)}


@router.post("/api/scheduled-orders/sync")
async def sync_scheduled_orders(request: Request):
    session = require_session(request)
    account_ref = build_account_ref(session.app_key, session.cano, session.acnt_prdt_cd)
    worker = getattr(request.app.state, "scheduled_order_worker", None)
    if worker is not None:
        worker.reconcile_submitted_orders_once()
    store = _store_or_503(request)
    return {"status": "success", "orders": store.list_orders(account_ref=account_ref)}


@router.get("/api/scheduled-orders/availability")
async def get_scheduled_order_availability(request: Request):
    require_session(request)
    return {"status": "success", "availability": _get_scheduled_order_write_availability()}


@router.delete("/api/scheduled-orders/{order_id}")
async def cancel_scheduled_order(request: Request, order_id: str):
    session = require_session(request)
    account_ref = build_account_ref(session.app_key, session.cano, session.acnt_prdt_cd)
    if config.CENTRAL_ORDER_REMOTE_URL and not config.CENTRAL_ORDER_SERVER_MODE:
        url = f"{config.CENTRAL_ORDER_REMOTE_URL}/api/central-server/scheduled-orders/{order_id}"
        headers = {"Authorization": f"Bearer {config.CENTRAL_ORDER_REMOTE_TOKEN}"}
        response = await asyncio.to_thread(requests.delete, url, headers=headers, timeout=10)
        try:
            data = response.json()
        except Exception:
            data = {"detail": response.text[:300]}
        if response.status_code >= 400:
            raise HTTPException(status_code=response.status_code, detail=data.get("detail") or "central_order_remote_failed")
        returned_order = data.get("order") if isinstance(data, dict) else None
        if isinstance(returned_order, dict) and str(returned_order.get("account_ref") or "") != account_ref:
            raise HTTPException(status_code=404, detail="Scheduled order not found")
        return data
    store = _store_or_503(request)
    existing = [item for item in store.list_orders(account_ref=account_ref) if item.get("id") == order_id]
    if not existing:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    item = existing[0]
    internal_item = store.get_order_record(order_id)
    if internal_item is None:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    cancelled = _cancel_broker_order(store, internal_item) if _can_broker_cancel(item) else store.cancel_order(order_id)
    if cancelled is None:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    return {"status": "success", "order": cancelled}


@router.put("/api/scheduled-orders/{order_id}")
async def update_scheduled_order(
    request: Request,
    order_id: str,
    payload: ScheduledDomesticOrderRequest,
):
    session = require_session(request)
    credentials = _session_credentials(session)
    account_ref = build_account_ref(session.app_key, session.cano, session.acnt_prdt_cd)
    remote_payload = RemoteScheduledOrderCreateRequest(
        **payload.model_dump(),
        execution_credentials=ExecutionCredentialsModel(**credentials),
        source_app="desktop-web",
    )

    if config.CENTRAL_ORDER_REMOTE_URL and not config.CENTRAL_ORDER_SERVER_MODE:
        url = f"/api/central-server/scheduled-orders/{order_id}"
        data = await _forward_to_remote(payload=remote_payload, method="PUT", path=url)
        returned_order = data.get("order") if isinstance(data, dict) else None
        if isinstance(returned_order, dict) and str(returned_order.get("account_ref") or "") != account_ref:
            raise HTTPException(status_code=404, detail="Scheduled order not found")
        return data

    store = _store_or_503(request)
    existing = [item for item in store.list_orders(account_ref=account_ref) if item.get("id") == order_id]
    if not existing:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    internal_item = store.get_order_record(order_id)
    if internal_item is None or str(internal_item.get("account_ref") or "") != account_ref:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    end_at = _validate_end_at(_extract_end_at_value(payload))
    normalized_order = _normalize_order_payload(payload)
    await asyncio.to_thread(_assert_sell_orderable, normalized_order, credentials)
    status = str(internal_item.get("status") or "")
    if status == "scheduled":
        updated = store.update_order(
            order_id,
            execute_at=_compute_initial_execute_at(end_at),
            end_at=end_at,
            order_payload=normalized_order,
            note=payload.note,
        )
        if updated is None:
            raise HTTPException(status_code=404, detail="Scheduled order not found")
        updated_status = str(updated.get("status") or "")
        if updated_status == "scheduled":
            return {"status": "success", "order": updated}
        if updated_status in LIVE_EDITABLE_STATUSES:
            current_order = dict(updated.get("order") or {})
            _assert_live_order_update_payload(current_order, normalized_order)
            retried = store.update_live_order(order_id, end_at=end_at, note=payload.note)
            if retried is None:
                raise HTTPException(status_code=404, detail="Scheduled order not found")
            return {"status": "success", "order": retried}
        raise HTTPException(status_code=409, detail="Only active scheduled orders can be modified")
    if status in LIVE_EDITABLE_STATUSES:
        current_order = dict(internal_item.get("order") or {})
        _assert_live_order_update_payload(current_order, normalized_order)
        updated = store.update_live_order(order_id, end_at=end_at, note=payload.note)
        if updated is None:
            raise HTTPException(status_code=404, detail="Scheduled order not found")
        return {"status": "success", "order": updated}
    raise HTTPException(status_code=409, detail="Only active scheduled orders can be modified")


@router.post("/api/central-server/scheduled-orders")
async def create_remote_scheduled_order(
    request: Request,
    payload: RemoteScheduledOrderCreateRequest,
    authorization: str | None = Header(default=None),
):
    _require_server_token(authorization)
    store = _store_or_503(request)
    end_at = _validate_end_at(_extract_end_at_value(payload))
    normalized_order = _normalize_order_payload(payload)
    await asyncio.to_thread(_assert_sell_orderable, normalized_order, payload.execution_credentials.model_dump())
    created = store.create_order(
        execution_credentials=payload.execution_credentials.model_dump(),
        order_payload=normalized_order,
        execute_at=_compute_initial_execute_at(end_at),
        end_at=end_at,
        source_app=payload.source_app,
        note=payload.note,
    )
    return {"status": "success", "order": created}


@router.get("/api/central-server/scheduled-orders")
async def list_remote_scheduled_orders(
    request: Request,
    account_ref: str | None = None,
    authorization: str | None = Header(default=None),
):
    _require_server_token(authorization)
    store = _store_or_503(request)
    return {"status": "success", "orders": store.list_orders(account_ref=account_ref)}


@router.post("/api/central-server/scheduled-orders/sync")
async def sync_remote_scheduled_orders(
    request: Request,
    account_ref: str | None = None,
    authorization: str | None = Header(default=None),
):
    _require_server_token(authorization)
    worker = getattr(request.app.state, "scheduled_order_worker", None)
    if worker is not None:
        worker.reconcile_submitted_orders_once()
    store = _store_or_503(request)
    return {"status": "success", "orders": store.list_orders(account_ref=account_ref)}


@router.delete("/api/central-server/scheduled-orders/{order_id}")
async def cancel_remote_scheduled_order(
    request: Request,
    order_id: str,
    authorization: str | None = Header(default=None),
):
    _require_server_token(authorization)
    store = _store_or_503(request)
    public_item = next((item for item in store.list_orders() if item.get("id") == order_id), None)
    if not public_item:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    internal_item = store.get_order_record(order_id)
    if internal_item is None:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    cancelled = _cancel_broker_order(store, internal_item) if _can_broker_cancel(public_item) else store.cancel_order(order_id)
    if cancelled is None:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    return {"status": "success", "order": cancelled}


@router.put("/api/central-server/scheduled-orders/{order_id}")
async def update_remote_scheduled_order(
    request: Request,
    order_id: str,
    payload: RemoteScheduledOrderCreateRequest,
    authorization: str | None = Header(default=None),
):
    _require_server_token(authorization)
    store = _store_or_503(request)
    existing = next((item for item in store.list_orders() if item.get("id") == order_id), None)
    if existing is None:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    internal_item = store.get_order_record(order_id)
    if internal_item is None:
        raise HTTPException(status_code=404, detail="Scheduled order not found")
    end_at = _validate_end_at(_extract_end_at_value(payload))
    normalized_order = _normalize_order_payload(payload)
    await asyncio.to_thread(_assert_sell_orderable, normalized_order, payload.execution_credentials.model_dump())
    status = str(internal_item.get("status") or "")
    if status == "scheduled":
        updated = store.update_order(
            order_id,
            execute_at=_compute_initial_execute_at(end_at),
            end_at=end_at,
            order_payload=normalized_order,
            note=payload.note,
        )
        if updated is None:
            raise HTTPException(status_code=404, detail="Scheduled order not found")
        updated_status = str(updated.get("status") or "")
        if updated_status == "scheduled":
            return {"status": "success", "order": updated}
        if updated_status in LIVE_EDITABLE_STATUSES:
            current_order = dict(updated.get("order") or {})
            _assert_live_order_update_payload(current_order, normalized_order)
            retried = store.update_live_order(order_id, end_at=end_at, note=payload.note)
            if retried is None:
                raise HTTPException(status_code=404, detail="Scheduled order not found")
            return {"status": "success", "order": retried}
        raise HTTPException(status_code=409, detail="Only active scheduled orders can be modified")
    if status in LIVE_EDITABLE_STATUSES:
        current_order = dict(internal_item.get("order") or {})
        _assert_live_order_update_payload(current_order, normalized_order)
        updated = store.update_live_order(order_id, end_at=end_at, note=payload.note)
        if updated is None:
            raise HTTPException(status_code=404, detail="Scheduled order not found")
        return {"status": "success", "order": updated}
    raise HTTPException(status_code=409, detail="Only active scheduled orders can be modified")
