from __future__ import annotations

import hashlib
import threading
import time
from collections.abc import Mapping

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
