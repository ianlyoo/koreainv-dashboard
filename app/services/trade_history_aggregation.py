from __future__ import annotations

import logging
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor

from app import api_client, toss_api_client, toss_proxy_client
from app.session_store import AccountCredential, accounts_metadata


logger = logging.getLogger(__name__)
_MAX_WORKERS = 4


def _as_float(value: object) -> float:
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def _selected_accounts(
    accounts: list[AccountCredential], account_id: str | None
) -> list[AccountCredential]:
    selected_id = str(account_id or "").strip()
    if not selected_id or selected_id == "all":
        return list(accounts)
    return [account for account in accounts if account.account_id == selected_id]


def _load_kis_history(
    account: AccountCredential,
    start_date: str,
    end_date: str,
    *,
    include_trades: bool,
    force_refresh: bool,
) -> dict[str, object]:
    token = api_client.get_access_token(account.app_key, account.app_secret)
    if not token:
        raise RuntimeError("Failed to get access token")
    if not include_trades:
        return api_client.get_realized_profit_summary(
            token,
            account.app_key,
            account.app_secret,
            account.cano,
            account.acnt_prdt_cd,
            start_date,
            end_date,
            force_refresh=force_refresh,
        )

    first = api_client.get_trade_history(
        token,
        account.app_key,
        account.app_secret,
        account.cano,
        account.acnt_prdt_cd,
        start_date,
        end_date,
        side_filter="all",
        market_filter="all",
        page=1,
        page_size=100,
        force_refresh=force_refresh,
    )
    items = list(first.get("items", [])) if isinstance(first, Mapping) else []
    pagination = first.get("pagination", {}) if isinstance(first, Mapping) else {}
    total_pages = int(_as_float(pagination.get("total_pages"))) if isinstance(pagination, Mapping) else 1
    for page in range(2, max(total_pages, 1) + 1):
        payload = api_client.get_trade_history(
            token,
            account.app_key,
            account.app_secret,
            account.cano,
            account.acnt_prdt_cd,
            start_date,
            end_date,
            side_filter="all",
            market_filter="all",
            page=page,
            page_size=100,
            force_refresh=False,
        )
        if isinstance(payload, Mapping) and isinstance(payload.get("items"), list):
            items.extend(payload["items"])
    result = dict(first) if isinstance(first, Mapping) else {}
    result["items"] = items
    result["profit_available"] = True
    return result


def _load_toss_history(
    account: AccountCredential,
    start_date: str,
    end_date: str,
    *,
    include_trades: bool,
) -> dict[str, object]:
    loader = (
        toss_proxy_client.get_trade_history
        if toss_proxy_client.is_configured()
        else toss_api_client.get_trade_history
    )
    result = loader(
        account.app_key,
        account.app_secret,
        account.cano,
        start_date,
        end_date,
    )
    if not include_trades:
        result = dict(result)
        result["items"] = []
    return result


def _annotate_trade(
    value: object, account: AccountCredential
) -> dict[str, object] | None:
    if not isinstance(value, Mapping):
        return None
    row = dict(value)
    row["account_id"] = account.account_id
    row["account_label"] = account.label
    row["broker"] = account.broker
    return row


def _side_matches(row: Mapping[str, object], side: str) -> bool:
    if side == "all":
        return True
    value = str(row.get("side") or "").strip()
    return (side == "buy" and value in {"매수", "BUY", "buy"}) or (
        side == "sell" and value in {"매도", "SELL", "sell"}
    )


def _market_matches(row: Mapping[str, object], market: str) -> bool:
    if market == "all":
        return True
    value = str(row.get("market") or "").strip().upper()
    is_domestic = value in {"KOR", "KR", "KRX", "NXT"}
    return is_domestic if market == "domestic" else not is_domestic


def fetch_aggregated_trade_history(
    accounts: list[AccountCredential],
    start_date: str,
    end_date: str,
    *,
    account_id: str | None = None,
    side: str = "all",
    market: str = "all",
    page: int = 1,
    page_size: int = 10,
    include_trades: bool = True,
    force_refresh: bool = False,
) -> dict[str, object]:
    selected = _selected_accounts(accounts, account_id)
    if not selected:
        raise ValueError("Unknown account_id")

    def load_one(account: AccountCredential):
        try:
            if account.broker == "toss":
                payload = _load_toss_history(
                    account, start_date, end_date, include_trades=include_trades
                )
            else:
                payload = _load_kis_history(
                    account,
                    start_date,
                    end_date,
                    include_trades=include_trades,
                    force_refresh=force_refresh,
                )
            return account, payload, None
        except Exception as exc:
            logger.exception(
                "Trade-history fetch failed for account %s (%s)",
                account.account_id,
                account.label,
            )
            return account, {}, str(exc) or exc.__class__.__name__

    with ThreadPoolExecutor(max_workers=min(len(selected), _MAX_WORKERS)) as executor:
        results = list(executor.map(load_one, selected))

    domestic_profit = 0.0
    overseas_profit = 0.0
    total_buy_amount = 0.0
    daily_by_date: dict[str, dict[str, object]] = {}
    trades: list[dict[str, object]] = []
    errors: list[dict[str, object]] = []
    successful_accounts = 0
    successful_profit_accounts = 0
    all_profit_complete = True
    profit_estimated = False
    unpriced_sell_count = 0

    for account, payload_value, error in results:
        if error:
            errors.append(
                {
                    "account_id": account.account_id,
                    "account_label": account.label,
                    "broker": account.broker,
                    "error": error,
                }
            )
            continue
        payload = payload_value if isinstance(payload_value, Mapping) else {}
        successful_accounts += 1
        summary = payload.get("summary", {})
        account_profit_available = bool(
            payload.get("profit_available", account.broker == "kis")
        )
        account_profit_complete = bool(
            payload.get("profit_complete", account_profit_available)
        )
        all_profit_complete = (
            all_profit_complete
            and account_profit_available
            and account_profit_complete
        )
        profit_estimated = profit_estimated or bool(payload.get("profit_estimated"))
        unpriced_sell_count += int(_as_float(payload.get("unpriced_sell_count")))
        if account_profit_available and isinstance(summary, Mapping):
            successful_profit_accounts += 1
            domestic_profit += _as_float(summary.get("domestic_realized_profit_krw"))
            overseas_profit += _as_float(summary.get("overseas_realized_profit_krw"))
            total_buy_amount += _as_float(summary.get("total_buy_amount_krw"))
        daily = payload.get("daily", [])
        if account_profit_available and isinstance(daily, list):
            for value in daily:
                if not isinstance(value, Mapping):
                    continue
                date = str(value.get("date") or "")
                bucket = daily_by_date.setdefault(
                    date,
                    {
                        "date": date,
                        "domestic_realized_profit_krw": 0.0,
                        "overseas_realized_profit_krw": 0.0,
                        "total_realized_profit_krw": 0.0,
                    },
                )
                for key in (
                    "domestic_realized_profit_krw",
                    "overseas_realized_profit_krw",
                    "total_realized_profit_krw",
                ):
                    bucket[key] = _as_float(bucket.get(key)) + _as_float(value.get(key))
        if include_trades and isinstance(payload.get("items"), list):
            for value in payload["items"]:
                annotated = _annotate_trade(value, account)
                if annotated is not None:
                    trades.append(annotated)

    filtered = [
        row
        for row in trades
        if _side_matches(row, side) and _market_matches(row, market)
    ]
    filtered.sort(
        key=lambda row: (str(row.get("date") or ""), str(row.get("time") or "")),
        reverse=True,
    )
    total_items = len(filtered)
    total_pages = max(1, (total_items + page_size - 1) // page_size)
    safe_page = min(max(page, 1), total_pages)
    start_index = (safe_page - 1) * page_size
    page_items = filtered[start_index : start_index + page_size]
    total_profit = domestic_profit + overseas_profit
    profit_available = successful_profit_accounts > 0
    profit_complete = (
        profit_available
        and successful_accounts == len(selected)
        and all_profit_complete
        and not errors
    )

    return {
        "summary": {
            "domestic_realized_profit_krw": domestic_profit,
            "overseas_realized_profit_krw": overseas_profit,
            "total_realized_profit_krw": total_profit,
            "total_realized_return_rate": (
                total_profit / total_buy_amount * 100.0
                if total_buy_amount > 0
                else 0.0
            ),
            "total_buy_amount_krw": total_buy_amount,
            "trade_days": len(daily_by_date),
        },
        "daily": [daily_by_date[key] for key in sorted(daily_by_date)],
        "items": page_items,
        "pagination": {
            "page": safe_page,
            "page_size": page_size,
            "total_items": total_items,
            "total_pages": total_pages,
        },
        "filters": {
            "side": side,
            "market": market,
            "account_id": str(account_id or "all"),
        },
        "accounts": accounts_metadata(accounts),
        "selected_account_ids": [account.account_id for account in selected],
        "profit_available": profit_available,
        "profit_complete": profit_complete,
        "profit_estimated": profit_estimated,
        "unpriced_sell_count": unpriced_sell_count,
        "account_errors": errors,
    }
