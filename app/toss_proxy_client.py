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


def _post(path: str, payload: Mapping[str, str]) -> dict[str, object]:
    if not is_configured():
        raise RuntimeError("Toss proxy remote URL and token are required")
    response = requests.post(
        f"{config.TOSS_PROXY_REMOTE_URL}{path}",
        json=dict(payload),
        headers={
            "Authorization": f"Bearer {config.TOSS_PROXY_REMOTE_TOKEN}",
            "Accept": "application/json",
        },
        timeout=30,
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
    )
    result = body.get("result")
    return dict(result) if isinstance(result, Mapping) else {}
