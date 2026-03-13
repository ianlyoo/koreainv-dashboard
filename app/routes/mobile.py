from __future__ import annotations

import asyncio
import datetime
from collections.abc import Mapping

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from app import api_client, auth
from app.routes.auth_pages import decrypt_credentials_for_session
from app.session_store import (
    SessionData,
    create_session,
    destroy_session,
    has_active_session,
    require_session,
    clear_session_cookie,
    set_session_cookie,
)
from fastapi.responses import JSONResponse


router = APIRouter()


class MobileLoginRequest(BaseModel):
    pin: str


def _build_mobile_portfolio_summary(
    domestic: Mapping[str, object], overseas: Mapping[str, object]
) -> dict[str, object]:
    def _as_mapping(value: object) -> Mapping[str, object]:
        return value if isinstance(value, Mapping) else {}

    def _as_item_list(value: object) -> list[Mapping[str, object]]:
        if not isinstance(value, list):
            return []
        return [item for item in value if isinstance(item, Mapping)]

    domestic_items_list = _as_item_list(domestic.get("items", []))
    us_items_list = _as_item_list(overseas.get("us_items", []))
    jp_items_list = _as_item_list(overseas.get("jp_items", []))

    def _as_float(value: object, default: float = 0.0) -> float:
        if isinstance(value, (int, float)):
            return float(value)
        if isinstance(value, str):
            try:
                return float(value)
            except ValueError:
                return default
        return default

    def _holding_totals(items: list[Mapping[str, object]], market_type: str) -> tuple[float, float]:
        total_eval = 0.0
        total_purchase = 0.0
        for item in items:
            qty = _as_float(item.get("qty"))
            avg_price = _as_float(item.get("avg_price"))
            now_price = _as_float(item.get("now_price"))
            exrt = _as_float(item.get("bass_exrt"))

            if market_type == "USA":
                rate = exrt if exrt > 0 else 1350.0
            elif market_type == "JPN":
                rate = (exrt if exrt > 0 else 900.0) / 100.0
            else:
                rate = 1.0

            total_eval += qty * now_price * rate
            total_purchase += qty * avg_price * rate
        return total_eval, total_purchase

    domestic_eval, domestic_purchase = _holding_totals(domestic_items_list, "KOR")
    us_eval, us_purchase = _holding_totals(us_items_list, "USA")
    jp_eval, jp_purchase = _holding_totals(jp_items_list, "JPN")

    domestic_summary_dict = _as_mapping(domestic.get("summary", {}))
    us_summary_dict = _as_mapping(overseas.get("us_summary", {}))
    jp_summary_dict = _as_mapping(overseas.get("jp_summary", {}))

    total_eval = domestic_eval + us_eval + jp_eval
    total_purchase = domestic_purchase + us_purchase + jp_purchase
    total_profit = total_eval - total_purchase
    total_profit_rate = (total_profit / total_purchase * 100.0) if total_purchase > 0 else 0.0

    return {
        "status": "success",
        "summary": {
            "total_assets_krw": round(total_eval),
            "total_purchase_krw": round(total_purchase),
            "total_profit_krw": round(total_profit),
            "total_profit_rate": round(total_profit_rate, 2),
            "cash_krw": round(_as_float(domestic_summary_dict.get("cash_balance"))),
            "cash_usd": _as_float(us_summary_dict.get("usd_cash_balance")),
            "cash_jpy": _as_float(jp_summary_dict.get("jpy_cash_balance")),
            "domestic_count": len(domestic_items_list),
            "overseas_count": len(us_items_list) + len(jp_items_list),
        },
    }


def _as_float(value: object, default: float = 0.0) -> float:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return default
    return default


def _now_kst_iso() -> str:
    return datetime.datetime.now(
        datetime.timezone(datetime.timedelta(hours=9))
    ).isoformat(timespec="seconds")


def _holding_rate_to_krw(market_type: str, item: Mapping[str, object]) -> float:
    if market_type == "USA":
        return _as_float(item.get("bass_exrt"), 1350.0) or 1350.0
    if market_type == "JPN":
        base = _as_float(item.get("bass_exrt"), 900.0) or 900.0
        return base / 100.0
    return 1.0


def _build_mobile_holdings(
    domestic: Mapping[str, object], overseas: Mapping[str, object]
) -> list[dict[str, object]]:
    holdings: list[dict[str, object]] = []

    def _append_items(
        items: object,
        market_type: str,
        currency: str,
    ) -> None:
        if not isinstance(items, list):
            return
        for item in items:
            if not isinstance(item, Mapping):
                continue
            quantity = _as_float(item.get("qty"))
            avg_price = _as_float(item.get("avg_price"))
            current_price = _as_float(item.get("now_price"))
            if quantity <= 0:
                continue
            rate = _holding_rate_to_krw(market_type, item)
            total_cost_krw = quantity * avg_price * rate
            total_value_krw = quantity * current_price * rate
            profit_loss_krw = total_value_krw - total_cost_krw
            profit_loss_rate = (profit_loss_krw / total_cost_krw * 100.0) if total_cost_krw > 0 else 0.0
            holdings.append(
                {
                    "symbol": str(item.get("ticker") or "").strip(),
                    "name": str(item.get("name") or item.get("ticker") or "-").strip(),
                    "market": market_type,
                    "quantity": quantity,
                    "current_price": current_price,
                    "average_cost": avg_price,
                    "total_value_krw": round(total_value_krw),
                    "total_cost_krw": round(total_cost_krw),
                    "profit_loss_krw": round(profit_loss_krw),
                    "profit_loss_rate": round(profit_loss_rate, 2),
                    "currency": currency,
                }
            )

    _append_items(domestic.get("items", []), "KOR", "KRW")
    _append_items(overseas.get("us_items", []), "USA", "USD")
    _append_items(overseas.get("jp_items", []), "JPN", "JPY")
    return sorted(holdings, key=lambda item: _as_float(item.get("total_value_krw")), reverse=True)


def _build_asset_distribution(holdings: list[dict[str, object]]) -> list[dict[str, object]]:
    total_value = sum(_as_float(item.get("total_value_krw")) for item in holdings)
    if total_value <= 0:
        return []
    return [
        {
            "symbol": str(item.get("symbol") or ""),
            "name": str(item.get("name") or item.get("symbol") or "-"),
            "weight_percent": round(_as_float(item.get("total_value_krw")) / total_value * 100.0, 2),
            "value_krw": round(_as_float(item.get("total_value_krw"))),
        }
        for item in holdings
    ]


def _build_mobile_dashboard(
    domestic: Mapping[str, object], overseas: Mapping[str, object]
) -> dict[str, object]:
    summary_payload = _build_mobile_portfolio_summary(domestic, overseas)
    holdings = _build_mobile_holdings(domestic, overseas)
    summary_raw = summary_payload.get("summary", {})
    summary = dict(summary_raw) if isinstance(summary_raw, Mapping) else {}
    summary["last_synced"] = _now_kst_iso()
    return {
        "status": "success",
        "summary": summary,
        "holdings": holdings,
        "asset_distribution": _build_asset_distribution(holdings),
    }


def _resolve_trade_history_range(
    raw_range: str | None,
) -> tuple[datetime.date, datetime.date, str]:
    today = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date()
    normalized = (raw_range or "this_month").strip().lower()
    if normalized == "last_month":
        first_this_month = today.replace(day=1)
        end = first_this_month - datetime.timedelta(days=1)
        start = end.replace(day=1)
        return start, end, "지난 달"
    if normalized == "3m":
        start = (today.replace(day=1) - datetime.timedelta(days=62)).replace(day=1)
        return start, today, "최근 3개월"
    if normalized == "6m":
        start = (today.replace(day=1) - datetime.timedelta(days=155)).replace(day=1)
        return start, today, "지난 6개월"
    return today.replace(day=1), today, "이번 달"


def _as_trade_amount_krw(trade: Mapping[str, object]) -> float:
    amount = trade.get("amount")
    if isinstance(amount, (int, float)):
        return float(amount)
    if isinstance(amount, str):
        try:
            return float(amount)
        except ValueError:
            return 0.0
    return 0.0


def _normalize_mobile_trade(trade: Mapping[str, object]) -> dict[str, object]:
    quantity = _as_float(trade.get("quantity"))
    unit_price = _as_float(trade.get("unit_price"))
    amount = _as_trade_amount_krw(trade)
    realized_profit = trade.get("realized_profit_krw")
    return_rate = trade.get("realized_return_rate")
    market = str(trade.get("market") or "").strip()
    return {
        "date": str(trade.get("date") or ""),
        "side": str(trade.get("side") or ""),
        "ticker": str(trade.get("ticker") or trade.get("symbol") or ""),
        "name": str(trade.get("name") or trade.get("symbol") or "-"),
        "market": market,
        "quantity": quantity,
        "unit_price": round(unit_price, 2),
        "amount_krw": round(amount),
        "realized_profit_krw": round(_as_float(realized_profit), 2) if isinstance(realized_profit, (int, float, str)) else None,
        "return_rate": round(_as_float(return_rate), 2) if isinstance(return_rate, (int, float, str)) else None,
    }


def _build_mobile_trade_history(
    detail_payload: Mapping[str, object],
    label: str,
) -> dict[str, object]:
    period_raw = detail_payload.get("period", {})
    period = period_raw if isinstance(period_raw, Mapping) else {}
    summary_raw = detail_payload.get("summary", {})
    summary = summary_raw if isinstance(summary_raw, Mapping) else {}
    trades_source = detail_payload.get("trades", [])
    trades = []
    if isinstance(trades_source, list):
        for trade in trades_source:
            if isinstance(trade, Mapping):
                trades.append(_normalize_mobile_trade(trade))
    return {
        "status": "success",
        "period": {
            "start": str(period.get("start") or ""),
            "end": str(period.get("end") or ""),
            "label": label,
        },
        "summary": {
            "total_realized_profit_krw": round(_as_float(summary.get("total_realized_profit_krw"))),
            "domestic_realized_profit_krw": round(_as_float(summary.get("domestic_realized_profit_krw"))),
            "overseas_realized_profit_krw": round(_as_float(summary.get("overseas_realized_profit_krw"))),
            "total_realized_return_rate": round(_as_float(summary.get("total_realized_return_rate")), 2),
        },
        "trades": trades,
    }


@router.get("/api/mobile/status")
async def get_mobile_status(request: Request):
    return {
        "status": "success",
        "setup_complete": auth.is_setup_complete(),
        "authenticated": has_active_session(request),
    }


@router.post("/api/mobile/login")
async def mobile_login(payload: MobileLoginRequest):
    settings = auth.load_settings()
    if not settings.get("setup_complete"):
        raise HTTPException(status_code=400, detail="Setup not complete")

    pin_hash = settings.get("pin_hash")
    if not isinstance(pin_hash, str):
        raise HTTPException(status_code=500, detail="Stored PIN is invalid")
    if not auth.verify_pin(payload.pin, pin_hash):
        raise HTTPException(status_code=401, detail="Invalid PIN")

    app_key, app_secret, cano, acnt_prdt_cd = decrypt_credentials_for_session(
        settings, payload.pin
    )
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


@router.get("/api/mobile/portfolio-summary")
async def get_mobile_portfolio_summary(request: Request):
    session = require_session(request)

    token = await asyncio.to_thread(
        api_client.get_access_token, session.app_key, session.app_secret
    )
    if not token:
        raise HTTPException(status_code=500, detail="Failed to get access token from API")

    domestic_task = asyncio.to_thread(
        api_client.get_domestic_balance,
        token,
        session.app_key,
        session.app_secret,
        session.cano,
        session.acnt_prdt_cd,
    )
    overseas_task = asyncio.to_thread(
        api_client.get_overseas_balance,
        token,
        session.app_key,
        session.app_secret,
        session.cano,
        session.acnt_prdt_cd,
    )
    domestic, overseas = await asyncio.gather(domestic_task, overseas_task)
    return _build_mobile_portfolio_summary(domestic, overseas)


@router.get("/api/mobile/dashboard")
async def get_mobile_dashboard(request: Request):
    session = require_session(request)

    token = await asyncio.to_thread(
        api_client.get_access_token, session.app_key, session.app_secret
    )
    if not token:
        raise HTTPException(status_code=500, detail="Failed to get access token from API")

    domestic_task = asyncio.to_thread(
        api_client.get_domestic_balance,
        token,
        session.app_key,
        session.app_secret,
        session.cano,
        session.acnt_prdt_cd,
    )
    overseas_task = asyncio.to_thread(
        api_client.get_overseas_balance,
        token,
        session.app_key,
        session.app_secret,
        session.cano,
        session.acnt_prdt_cd,
    )
    domestic, overseas = await asyncio.gather(domestic_task, overseas_task)
    return _build_mobile_dashboard(domestic, overseas)


@router.get("/api/mobile/trade-history")
async def get_mobile_trade_history(request: Request, range: str | None = None):
    session = require_session(request)
    start_day, end_day, label = _resolve_trade_history_range(range)

    token = await asyncio.to_thread(
        api_client.get_access_token, session.app_key, session.app_secret
    )
    if not token:
        raise HTTPException(status_code=500, detail="Failed to get access token from API")

    detail_payload = await asyncio.to_thread(
        api_client.get_trade_history,
        token,
        session.app_key,
        session.app_secret,
        session.cano,
        session.acnt_prdt_cd,
        start_day.strftime("%Y%m%d"),
        end_day.strftime("%Y%m%d"),
    )
    summary_payload = await asyncio.to_thread(
        api_client.get_realized_profit_summary,
        token,
        session.app_key,
        session.app_secret,
        session.cano,
        session.acnt_prdt_cd,
        start_day.strftime("%Y%m%d"),
        end_day.strftime("%Y%m%d"),
    )
    serialized = {
        "period": {
            "start": start_day.isoformat(),
            "end": end_day.isoformat(),
        },
        "summary": (
            summary_payload.get("summary", {})
            if isinstance(summary_payload, Mapping)
            else {}
        ),
        "trades": detail_payload.get("items", []) if isinstance(detail_payload, Mapping) else [],
    }
    return _build_mobile_trade_history(serialized, label)


@router.post("/api/mobile/logout")
async def mobile_logout(request: Request):
    destroy_session(request.cookies.get("session"))
    response = JSONResponse({"status": "success", "message": "Logged out"})
    clear_session_cookie(response)
    return response
