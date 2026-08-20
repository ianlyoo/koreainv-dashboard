from __future__ import annotations

import hashlib
import threading
import time
from collections.abc import Mapping
import datetime

import requests


BASE_URL = "https://openapi.tossinvest.com"
_TOKEN_BUFFER_SECONDS = 60
_token_cache: dict[str, tuple[str, float]] = {}
_token_lock = threading.RLock()


def _scope_key(client_id: str, client_secret: str) -> str:
    raw = f"{client_id.strip()}::{client_secret.strip()}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _as_mapping(value: object) -> Mapping[str, object]:
    return value if isinstance(value, Mapping) else {}


def _as_rows(value: object) -> list[Mapping[str, object]]:
    if not isinstance(value, list):
        return []
    return [row for row in value if isinstance(row, Mapping)]


def _as_float(value: object) -> float:
    try:
        return float(str(value or "0"))
    except (TypeError, ValueError):
        return 0.0


def _error_detail(response: requests.Response, payload: object) -> str:
    body = _as_mapping(payload)
    error = _as_mapping(body.get("error"))
    return str(
        error.get("message")
        or error.get("code")
        or body.get("error_description")
        or response.text[:300]
        or f"HTTP {response.status_code}"
    )


def clear_token_cache() -> None:
    with _token_lock:
        _token_cache.clear()


def get_access_token(client_id: str, client_secret: str, force: bool = False) -> str:
    safe_id = str(client_id or "").strip()
    safe_secret = str(client_secret or "").strip()
    if not safe_id or not safe_secret:
        raise ValueError("Toss client_id and client_secret are required")
    scope = _scope_key(safe_id, safe_secret)
    with _token_lock:
        cached = _token_cache.get(scope)
        if not force and cached and time.time() < cached[1] - _TOKEN_BUFFER_SECONDS:
            return cached[0]

        response = requests.post(
            f"{BASE_URL}/oauth2/token",
            data={
                "grant_type": "client_credentials",
                "client_id": safe_id,
                "client_secret": safe_secret,
            },
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=10,
        )
        try:
            payload = response.json()
        except ValueError:
            payload = {}
        if response.status_code != 200:
            raise RuntimeError(f"Toss token request failed: {_error_detail(response, payload)}")
        body = _as_mapping(payload)
        token = str(body.get("access_token") or "").strip()
        if not token:
            raise RuntimeError("Toss token response did not contain access_token")
        expires_in = max(int(_as_float(body.get("expires_in"))) or 86400, 120)
        _token_cache[scope] = (token, time.time() + expires_in)
        return token


def _authorized_get(
    path: str,
    client_id: str,
    client_secret: str,
    *,
    account_seq: str | None = None,
    params: Mapping[str, str] | None = None,
    retry: bool = True,
) -> Mapping[str, object]:
    token = get_access_token(client_id, client_secret)
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    if account_seq is not None:
        headers["X-Tossinvest-Account"] = str(account_seq).strip()
    response = requests.get(
        f"{BASE_URL}{path}", headers=headers, params=dict(params or {}), timeout=15
    )
    try:
        payload = response.json()
    except ValueError:
        payload = {}
    if response.status_code == 401 and retry:
        get_access_token(client_id, client_secret, force=True)
        return _authorized_get(
            path,
            client_id,
            client_secret,
            account_seq=account_seq,
            params=params,
            retry=False,
        )
    if response.status_code != 200:
        raise RuntimeError(
            f"Toss API request failed ({path}): {_error_detail(response, payload)}"
        )
    return _as_mapping(payload)


def get_accounts(client_id: str, client_secret: str) -> list[dict[str, object]]:
    payload = _authorized_get("/api/v1/accounts", client_id, client_secret)
    return [dict(row) for row in _as_rows(payload.get("result"))]


def get_dashboard_source(
    client_id: str, client_secret: str, account_seq: str
) -> tuple[dict[str, object], dict[str, object]]:
    safe_seq = str(account_seq or "").strip()
    if not safe_seq.isdigit() or not (0 < int(safe_seq) <= 9_223_372_036_854_775_807):
        raise ValueError("Toss account_seq must be a positive integer")
    holdings = dict(
        _authorized_get(
            "/api/v1/holdings",
            client_id,
            client_secret,
            account_seq=safe_seq,
        )
    )
    try:
        exchange_rate = dict(
            _authorized_get(
                "/api/v1/exchange-rate",
                client_id,
                client_secret,
                params={"baseCurrency": "USD", "quoteCurrency": "KRW"},
            )
        )
    except Exception:
        exchange_rate = {}
    return holdings, exchange_rate


def _get_usd_exchange_rate(client_id: str, client_secret: str) -> float:
    try:
        payload = _authorized_get(
            "/api/v1/exchange-rate",
            client_id,
            client_secret,
            params={"baseCurrency": "USD", "quoteCurrency": "KRW"},
        )
        result = _as_mapping(payload.get("result"))
        return _as_float(result.get("rate")) or _as_float(result.get("midRate"))
    except Exception:
        return 0.0


def get_balances(
    client_id: str, client_secret: str, account_seq: str
) -> tuple[dict[str, object], dict[str, object]]:
    safe_seq = str(account_seq or "").strip()
    if not safe_seq.isdigit() or not (0 < int(safe_seq) <= 9_223_372_036_854_775_807):
        raise ValueError("Toss account_seq must be a positive integer")
    payload = _authorized_get(
        "/api/v1/holdings",
        client_id,
        client_secret,
        account_seq=safe_seq,
    )
    result = _as_mapping(payload.get("result"))
    total_purchase = _as_mapping(result.get("totalPurchaseAmount"))
    market_value = _as_mapping(result.get("marketValue"))
    market_amount = _as_mapping(market_value.get("amount"))
    profit_loss = _as_mapping(result.get("profitLoss"))
    profit_amount = _as_mapping(profit_loss.get("amount"))
    usd_rate = _get_usd_exchange_rate(client_id, client_secret)

    domestic_items: list[dict[str, object]] = []
    us_items: list[dict[str, object]] = []
    for row in _as_rows(result.get("items")):
        quantity = _as_float(row.get("quantity"))
        if quantity <= 0:
            continue
        average_price = _as_float(row.get("averagePurchasePrice"))
        current_price = _as_float(row.get("lastPrice"))
        item_profit = _as_mapping(row.get("profitLoss"))
        normalized = {
            "name": str(row.get("name") or row.get("symbol") or ""),
            "ticker": str(row.get("symbol") or ""),
            "qty": quantity,
            "avg_price": average_price,
            "now_price": current_price,
            "profit_rt": _as_float(item_profit.get("rate")) * 100.0,
            "broker": "toss",
        }
        country = str(row.get("marketCountry") or "").upper()
        currency = str(row.get("currency") or "").upper()
        if country == "KR" or currency == "KRW":
            domestic_items.append(normalized)
        elif country == "US" or currency == "USD":
            normalized["excg_cd"] = "NASD"
            normalized["bass_exrt"] = usd_rate or 1350.0
            us_items.append(normalized)

    domestic = {
        "summary": {
            "total_purchase_amt": _as_float(total_purchase.get("krw")),
            "total_eval_amt": _as_float(market_amount.get("krw")),
            "total_profit_loss": _as_float(profit_amount.get("krw")),
            "cash_balance": 0,
            "orderable_cash": 0,
        },
        "items": domestic_items,
    }
    overseas = {
        "us_summary": {
            "krw_purchase_amt": _as_float(total_purchase.get("usd")) * (usd_rate or 0),
            "krw_eval_amt": _as_float(market_amount.get("usd")) * (usd_rate or 0),
            "usd_cash_balance": 0,
            "usd_orderable_cash": 0,
            "usd_exrt": usd_rate,
        },
        "jp_summary": {},
        "us_items": us_items,
        "jp_items": [],
    }
    return domestic, overseas


def _iso_date(value: object) -> str:
    text = str(value or "").strip()
    if len(text) == 8 and text.isdigit():
        return f"{text[:4]}-{text[4:6]}-{text[6:]}"
    try:
        return datetime.date.fromisoformat(text).isoformat()
    except ValueError as exc:
        raise ValueError("Toss trade-history dates must use YYYY-MM-DD") from exc


def _normalize_order_rows(
    orders: object,
    *,
    usd_exchange_rate: float,
    start_date: str,
    end_date: str,
) -> list[dict[str, object]]:
    normalized: list[dict[str, object]] = []
    seen: set[str] = set()
    for row in _as_rows(orders):
        execution = _as_mapping(row.get("execution"))
        quantity = _as_float(execution.get("filledQuantity"))
        if quantity <= 0:
            continue
        order_id = str(row.get("orderId") or "").strip()
        if order_id and order_id in seen:
            continue
        if order_id:
            seen.add(order_id)
        filled_at = str(execution.get("filledAt") or row.get("orderedAt") or "").strip()
        trade_date = filled_at[:10]
        if not trade_date or trade_date < start_date or trade_date > end_date:
            continue
        currency = str(row.get("currency") or "KRW").strip().upper()
        amount_native = _as_float(execution.get("filledAmount"))
        unit_price = _as_float(execution.get("averageFilledPrice"))
        if amount_native <= 0 and quantity > 0 and unit_price > 0:
            amount_native = quantity * unit_price
        amount_krw = (
            amount_native
            if currency == "KRW"
            else amount_native * max(usd_exchange_rate, 0.0)
        )
        commission_native = _as_float(execution.get("commission"))
        tax_native = _as_float(execution.get("tax"))
        symbol = str(row.get("symbol") or "").strip()
        side = str(row.get("side") or "").strip().upper()
        normalized.append(
            {
                "date": trade_date.replace("-", ""),
                "time": filled_at[11:19].replace(":", "") if len(filled_at) >= 19 else "",
                "side": "매수" if side == "BUY" else "매도" if side == "SELL" else side,
                "ticker": symbol,
                "symbol": symbol,
                "name": symbol or "-",
                "market": "KOR" if currency == "KRW" else "NASD",
                "currency": currency,
                "quantity": quantity,
                "unit_price": unit_price,
                "amount": amount_native,
                "amount_native": amount_native,
                "amount_krw": amount_krw,
                "commission_native": commission_native,
                "tax_native": tax_native,
                "realized_profit_krw": None,
                "realized_return_rate": None,
                "realized_profit_estimated": False,
                "order_no": order_id,
            }
        )
    return normalized


def _estimate_realized_profit(
    rows: list[dict[str, object]],
    *,
    usd_exchange_rate: float,
    start_date: str,
    end_date: str,
    history_complete: bool = True,
) -> dict[str, object]:
    """Estimate Toss realized P/L with a moving-average native-currency basis.

    The Toss order-history API exposes execution proceeds and costs but not the
    broker's realized P/L.  We reconstruct cost basis from all available prior
    BUY executions.  A SELL with insufficient basis is deliberately left
    unpriced because transfers and corporate actions are not represented by
    order history.
    """

    positions: dict[tuple[str, str], dict[str, float]] = {}
    unknown_basis: set[tuple[str, str]] = set()
    selected_sell_count = 0
    estimated_sell_count = 0
    domestic_profit = 0.0
    overseas_profit = 0.0
    total_buy_amount_krw = 0.0
    daily: dict[str, dict[str, object]] = {}

    ordered = sorted(
        rows,
        key=lambda row: (
            str(row.get("date") or ""),
            str(row.get("time") or ""),
            str(row.get("order_no") or ""),
        ),
    )
    for row in ordered:
        symbol = str(row.get("symbol") or row.get("ticker") or "").strip()
        currency = str(row.get("currency") or "KRW").strip().upper()
        if not symbol:
            continue
        key = (symbol, currency)
        side = str(row.get("side") or "").strip().upper()
        quantity = _as_float(row.get("quantity"))
        amount_native = _as_float(row.get("amount_native") or row.get("amount"))
        commission = _as_float(row.get("commission_native"))
        tax = _as_float(row.get("tax_native"))
        trade_date = str(row.get("date") or "")
        selected = start_date.replace("-", "") <= trade_date <= end_date.replace("-", "")

        if side in {"BUY", "매수"}:
            if key in unknown_basis:
                continue
            position = positions.setdefault(key, {"quantity": 0.0, "cost": 0.0})
            position["quantity"] += quantity
            position["cost"] += amount_native + commission + tax
            continue

        if side not in {"SELL", "매도"}:
            continue
        if selected:
            selected_sell_count += 1
        position = positions.get(key)
        if (
            key in unknown_basis
            or position is None
            or quantity <= 0
            or position["quantity"] + 1e-9 < quantity
        ):
            unknown_basis.add(key)
            positions.pop(key, None)
            if selected:
                row["profit_estimate_reason"] = "매수 원가 이력 부족"
            continue

        allocated_cost = position["cost"] * (quantity / position["quantity"])
        proceeds = amount_native - commission - tax
        profit_native = proceeds - allocated_cost
        rate = usd_exchange_rate if currency == "USD" else 1.0
        position["quantity"] -= quantity
        position["cost"] -= allocated_cost
        if position["quantity"] <= 1e-9:
            positions.pop(key, None)

        if not selected:
            continue
        if currency != "KRW" and rate <= 0:
            row["profit_estimate_reason"] = "원화 환산 환율 부족"
            continue
        profit_krw = profit_native * rate
        buy_amount_krw = allocated_cost * rate
        row["realized_profit_krw"] = round(profit_krw, 2)
        row["realized_return_rate"] = (
            round(profit_native / allocated_cost * 100.0, 4)
            if allocated_cost > 0
            else None
        )
        row["realized_profit_estimated"] = True
        estimated_sell_count += 1
        total_buy_amount_krw += buy_amount_krw
        is_domestic = currency == "KRW"
        if is_domestic:
            domestic_profit += profit_krw
        else:
            overseas_profit += profit_krw
        bucket = daily.setdefault(
            trade_date,
            {
                "date": trade_date,
                "domestic_realized_profit_krw": 0.0,
                "overseas_realized_profit_krw": 0.0,
                "total_realized_profit_krw": 0.0,
            },
        )
        bucket_key = (
            "domestic_realized_profit_krw"
            if is_domestic
            else "overseas_realized_profit_krw"
        )
        bucket[bucket_key] = _as_float(bucket[bucket_key]) + profit_krw
        bucket["total_realized_profit_krw"] = (
            _as_float(bucket["total_realized_profit_krw"]) + profit_krw
        )

    estimate_complete = history_complete and estimated_sell_count == selected_sell_count
    profit_available = selected_sell_count == 0 or estimated_sell_count > 0
    total_profit = domestic_profit + overseas_profit
    return {
        "summary": {
            "domestic_realized_profit_krw": domestic_profit,
            "overseas_realized_profit_krw": overseas_profit,
            "total_realized_profit_krw": total_profit,
            "total_realized_return_rate": (
                total_profit / total_buy_amount_krw * 100.0
                if total_buy_amount_krw > 0
                else 0.0
            ),
            "total_buy_amount_krw": total_buy_amount_krw,
        },
        "daily": [daily[key] for key in sorted(daily)],
        "profit_available": profit_available,
        "profit_complete": estimate_complete,
        "profit_estimated": True,
        "estimated_sell_count": estimated_sell_count,
        "unpriced_sell_count": selected_sell_count - estimated_sell_count,
    }


def _fetch_closed_orders(
    client_id: str,
    client_secret: str,
    account_seq: str,
    *,
    end_date: str,
) -> tuple[list[Mapping[str, object]], bool]:
    orders: list[Mapping[str, object]] = []
    cursor = ""
    seen_cursors: set[str] = set()
    history_complete = True
    for _ in range(100):
        params = {
            "status": "CLOSED",
            "to": end_date,
            "limit": "100",
        }
        if cursor:
            params["cursor"] = cursor
        payload = _authorized_get(
            "/api/v1/orders",
            client_id,
            client_secret,
            account_seq=account_seq,
            params=params,
        )
        result = _as_mapping(payload.get("result"))
        orders.extend(_as_rows(result.get("orders")))
        has_next = bool(result.get("hasNext"))
        next_cursor = str(result.get("nextCursor") or "").strip()
        if not has_next:
            break
        if not next_cursor or next_cursor in seen_cursors:
            history_complete = False
            break
        seen_cursors.add(next_cursor)
        cursor = next_cursor
    else:
        history_complete = False
    return orders, history_complete


def get_trade_history(
    client_id: str,
    client_secret: str,
    account_seq: str,
    start_date: str,
    end_date: str,
) -> dict[str, object]:
    safe_seq = str(account_seq or "").strip()
    if not safe_seq.isdigit() or not (0 < int(safe_seq) <= 9_223_372_036_854_775_807):
        raise ValueError("Toss account_seq must be a positive integer")
    safe_start = _iso_date(start_date)
    safe_end = _iso_date(end_date)
    if safe_start > safe_end:
        raise ValueError("Toss trade-history start date must not exceed end date")

    orders, history_complete = _fetch_closed_orders(
        client_id,
        client_secret,
        safe_seq,
        end_date=safe_end,
    )
    usd_rate = _get_usd_exchange_rate(client_id, client_secret)
    all_items = _normalize_order_rows(
        orders,
        usd_exchange_rate=usd_rate,
        start_date="0001-01-01",
        end_date=safe_end,
    )
    estimate = _estimate_realized_profit(
        all_items,
        usd_exchange_rate=usd_rate,
        start_date=safe_start,
        end_date=safe_end,
        history_complete=history_complete,
    )
    items = [
        row
        for row in all_items
        if safe_start.replace("-", "") <= str(row.get("date") or "") <= safe_end.replace("-", "")
    ]
    items.sort(key=lambda row: (str(row.get("date", "")), str(row.get("time", ""))), reverse=True)
    summary = dict(_as_mapping(estimate.get("summary")))
    summary["trade_days"] = len({str(row.get("date", "")) for row in items})
    return {
        "items": items,
        "summary": summary,
        "daily": estimate["daily"],
        "usd_exchange_rate": usd_rate,
        "profit_available": estimate["profit_available"],
        "profit_complete": estimate["profit_complete"],
        "profit_estimated": True,
        "estimated_sell_count": estimate["estimated_sell_count"],
        "unpriced_sell_count": estimate["unpriced_sell_count"],
    }
