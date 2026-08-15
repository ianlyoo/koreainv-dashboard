from __future__ import annotations

import logging
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor

from app import api_client, toss_api_client
from app.session_store import AccountCredential

logger = logging.getLogger(__name__)

_RATE_SUMMARY_KEYS = {"usd_exrt", "jpy_exrt"}
_MAX_WORKERS = 4


def _as_mapping(value: object) -> Mapping[str, object]:
    return value if isinstance(value, Mapping) else {}


def _as_item_list(value: object) -> list[Mapping[str, object]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, Mapping)]


def _as_float(value: object, default: float = 0.0) -> float:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return default
    return default


def _merge_summaries(
    summaries: list[Mapping[str, object]],
) -> dict[str, object]:
    """Sum numeric fields across accounts; keep the first positive rate."""
    merged: dict[str, object] = {}
    rate_values: dict[str, float] = {}
    for summary in summaries:
        for key, value in summary.items():
            if isinstance(value, (int, float)):
                if key in _RATE_SUMMARY_KEYS:
                    if value > 0 and key not in rate_values:
                        rate_values[key] = float(value)
                    continue
                merged[key] = _as_float(merged.get(key, 0.0)) + float(value)
            else:
                merged.setdefault(key, value)
    for key, value in rate_values.items():
        merged[key] = value
    return merged


def _annotate_items(
    items: list[Mapping[str, object]], account: AccountCredential
) -> list[dict[str, object]]:
    annotated: list[dict[str, object]] = []
    for item in items:
        entry = dict(item)
        entry["account_id"] = account.account_id
        entry["account_label"] = account.label
        entry["broker"] = account.broker
        annotated.append(entry)
    return annotated


def merge_domestic_balances(
    account_results: list[tuple[AccountCredential, object]],
) -> dict[str, object]:
    items: list[dict[str, object]] = []
    summaries: list[Mapping[str, object]] = []
    for account, payload in account_results:
        payload_mapping = _as_mapping(payload)
        summary = payload_mapping.get("summary")
        if isinstance(summary, Mapping):
            summaries.append(summary)
        items.extend(_annotate_items(_as_item_list(payload_mapping.get("items")), account))
    return {"summary": _merge_summaries(summaries), "items": items}


def merge_overseas_balances(
    account_results: list[tuple[AccountCredential, object]],
) -> dict[str, object]:
    us_items: list[dict[str, object]] = []
    jp_items: list[dict[str, object]] = []
    us_summaries: list[Mapping[str, object]] = []
    jp_summaries: list[Mapping[str, object]] = []
    for account, payload in account_results:
        payload_mapping = _as_mapping(payload)
        us_summary = payload_mapping.get("us_summary")
        if isinstance(us_summary, Mapping):
            us_summaries.append(us_summary)
        jp_summary = payload_mapping.get("jp_summary")
        if isinstance(jp_summary, Mapping):
            jp_summaries.append(jp_summary)
        us_items.extend(
            _annotate_items(_as_item_list(payload_mapping.get("us_items")), account)
        )
        jp_items.extend(
            _annotate_items(_as_item_list(payload_mapping.get("jp_items")), account)
        )
    return {
        "us_summary": _merge_summaries(us_summaries),
        "jp_summary": _merge_summaries(jp_summaries),
        "us_items": us_items,
        "jp_items": jp_items,
    }


def _empty_balance_payload() -> dict[str, object]:
    return {
        "domestic": {"summary": {}, "items": []},
        "overseas": {
            "us_summary": {},
            "jp_summary": {},
            "us_items": [],
            "jp_items": [],
        },
    }


def fetch_aggregated_balances(
    accounts: list[AccountCredential],
) -> dict[str, object]:
    """Fetch and merge balances for every session account."""
    if not accounts:
        return _empty_balance_payload()

    def fetch_one(account: AccountCredential):
        try:
            if account.broker == "toss":
                domestic, overseas = toss_api_client.get_balances(
                    account.app_key,
                    account.app_secret,
                    account.cano,
                )
                return account, domestic, overseas, None
            token = api_client.get_access_token(account.app_key, account.app_secret)
            if not token:
                raise RuntimeError("Failed to get access token")
            domestic = api_client.get_domestic_balance(
                token,
                account.app_key,
                account.app_secret,
                account.cano,
                account.acnt_prdt_cd,
            )
            overseas = api_client.get_overseas_balance(
                token,
                account.app_key,
                account.app_secret,
                account.cano,
                account.acnt_prdt_cd,
            )
            return account, domestic, overseas, None
        except Exception as exc:
            logger.exception(
                "Balance fetch failed for account %s (%s)",
                account.account_id,
                account.label,
            )
            return account, {"summary": {}, "items": []}, {
                "us_summary": {},
                "jp_summary": {},
                "us_items": [],
                "jp_items": [],
            }, str(exc) or exc.__class__.__name__

    with ThreadPoolExecutor(max_workers=min(len(accounts), _MAX_WORKERS)) as executor:
        results = list(executor.map(fetch_one, accounts))

    domestic_results = [
        (account, domestic) for account, domestic, _overseas, _error in results
    ]
    overseas_results = [
        (account, overseas) for account, _domestic, overseas, _error in results
    ]
    account_errors = [
        {
            "account_id": account.account_id,
            "account_label": account.label,
            "broker": account.broker,
            "error": error,
        }
        for account, _domestic, _overseas, error in results
        if error
    ]
    return {
        "domestic": merge_domestic_balances(domestic_results),
        "overseas": merge_overseas_balances(overseas_results),
        "account_errors": account_errors,
    }
