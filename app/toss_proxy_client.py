from __future__ import annotations

from collections.abc import Mapping

import requests

from app import config


def is_configured() -> bool:
    has_url = bool(config.TOSS_PROXY_REMOTE_URL)
    has_token = bool(config.TOSS_PROXY_REMOTE_TOKEN)
    if has_url != has_token:
        raise RuntimeError("Toss proxy remote URL and token must be configured together")
    return has_url


def _post(path: str, payload: Mapping[str, str], *, timeout: float = 30) -> dict[str, object]:
    if not is_configured():
        raise RuntimeError("Toss proxy remote URL and token are required")
    response = requests.post(
        f"{config.TOSS_PROXY_REMOTE_URL}{path}",
        json=dict(payload),
        headers={
            "Authorization": f"Bearer {config.TOSS_PROXY_REMOTE_TOKEN}",
            "Accept": "application/json",
        },
        timeout=timeout,
    )
    try:
        body = response.json()
    except ValueError:
        body = {}
    if response.status_code != 200:
        detail = body.get("detail") if isinstance(body, Mapping) else None
        raise RuntimeError(str(detail or response.text[:300] or response.status_code))
    return dict(body) if isinstance(body, Mapping) else {}


def get_accounts(client_id: str, client_secret: str) -> list[dict[str, object]]:
    body = _post(
        "/api/toss-proxy/accounts",
        {"client_id": client_id, "client_secret": client_secret},
    )
    result = body.get("result")
    if not isinstance(result, list):
        return []
    return [dict(row) for row in result if isinstance(row, Mapping)]


def get_balances(
    client_id: str, client_secret: str, account_seq: str
) -> tuple[dict[str, object], dict[str, object]]:
    body = _post(
        "/api/toss-proxy/balances",
        {
            "client_id": client_id,
            "client_secret": client_secret,
            "account_seq": account_seq,
        },
    )
    domestic = body.get("domestic")
    overseas = body.get("overseas")
    return (
        dict(domestic) if isinstance(domestic, Mapping) else {},
        dict(overseas) if isinstance(overseas, Mapping) else {},
    )


def get_trade_history(
    client_id: str,
    client_secret: str,
    account_seq: str,
    start_date: str,
    end_date: str,
) -> dict[str, object]:
    body = _post(
        "/api/toss-proxy/trade-history",
        {
            "client_id": client_id,
            "client_secret": client_secret,
            "account_seq": account_seq,
            "start_date": start_date,
            "end_date": end_date,
        },
        # Annual ranges can require hundreds of throttled historical FX reads.
        timeout=300,
    )
    result = body.get("result")
    result = dict(result) if isinstance(result, Mapping) else {}
    raw_rows = result.get("items")
    rows = raw_rows if isinstance(raw_rows, list) else []
    summary = result.get("summary", {})
    overseas_sales = any(
        isinstance(row, Mapping) and row.get("side") in {"SELL", "매도"}
        and row.get("currency") != "KRW"
        for row in rows
    )
    overseas_profit = isinstance(summary, Mapping) and bool(summary.get("overseas_realized_profit_krw"))
    if result.get("profit_fx_basis") != "sale_historical_mid_rate" and (overseas_sales or overseas_profit):
        reason = "개인 서버 업데이트 필요: 매도 시점 참고환율 미지원"
        for row in rows:
            if isinstance(row, dict) and row.get("side") in {"SELL", "매도"}:
                row.update(realized_profit_krw=None, realized_return_rate=None,
                           realized_profit_estimated=True, profit_estimate_reason=reason)
        result.update(
            summary={}, daily=[], profit_available=False, profit_complete=False,
            profit_history_complete=False, profit_history_note=reason,
            unpriced_sell_count=sum(isinstance(row, Mapping) and row.get("side") in {"SELL", "매도"} for row in rows),
        )
    return result
