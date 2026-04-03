from __future__ import annotations

from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor
import hashlib
import datetime
import json
import logging
import math
import os
import tempfile
import threading
import time

import requests

from app import config
from app import runtime_paths

_cached_tokens: dict[str, str] = {}
_token_issue_times: dict[str, float] = {}
_token_expiry_times: dict[str, float] = {}
_persisted_token_cache: dict[str, dict[str, object]] | None = None
_realized_cache = {}
_inflight_trade_profit_rows = {}
_MAX_PARALLEL_WORKERS = 4
_REALIZED_CACHE_TTL_SECONDS = 120
_token_lock = threading.RLock()
_cache_lock = threading.RLock()
logger = logging.getLogger(__name__)


def _token_cache_file_path() -> str:
    return os.path.join(runtime_paths.get_user_data_dir(), "token_cache.json")


def _token_scope_key(app_key: str, app_secret: str) -> str:
    raw = f"{str(app_key or '').strip()}::{str(app_secret or '').strip()}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _load_persisted_token_cache_locked() -> dict[str, dict[str, object]]:
    global _persisted_token_cache
    if _persisted_token_cache is not None:
        return _persisted_token_cache

    path = _token_cache_file_path()
    if not os.path.exists(path):
        _persisted_token_cache = {}
        return _persisted_token_cache

    try:
        with open(path, "r", encoding="utf-8") as f:
            payload = json.load(f)
    except Exception:
        logger.warning("Failed to load token cache file", exc_info=True)
        _persisted_token_cache = {}
        return _persisted_token_cache

    if not isinstance(payload, dict):
        _persisted_token_cache = {}
        return _persisted_token_cache

    entries = payload.get("entries")
    _persisted_token_cache = entries if isinstance(entries, dict) else {}
    return _persisted_token_cache


def _save_persisted_token_cache_locked() -> None:
    cache = _load_persisted_token_cache_locked()
    path = _token_cache_file_path()
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix="token_cache_", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump({"entries": cache}, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


def _load_persisted_token_for_scope_locked(scope_key: str) -> str | None:
    cache = _load_persisted_token_cache_locked()
    entry = cache.get(scope_key)
    if not isinstance(entry, dict):
        return None

    token = str(entry.get("access_token") or "").strip()
    issued_at = _to_float(entry.get("issued_at") or 0)
    expires_at = _to_float(entry.get("expires_at") or 0)
    now = time.time()
    if not token or issued_at <= 0 or expires_at <= 0 or now >= max(expires_at - 60, issued_at):
        cache.pop(scope_key, None)
        _save_persisted_token_cache_locked()
        return None

    _cached_tokens[scope_key] = token
    _token_issue_times[scope_key] = issued_at
    _token_expiry_times[scope_key] = expires_at
    return token


def _persist_token_locked(scope_key: str, access_token: str, issued_at: float, expires_at: float) -> None:
    cache = _load_persisted_token_cache_locked()
    cache[scope_key] = {
        "access_token": access_token,
        "issued_at": issued_at,
        "expires_at": expires_at,
    }
    _save_persisted_token_cache_locked()


def clear_persisted_token_cache() -> None:
    global _persisted_token_cache
    with _token_lock:
        _cached_tokens.clear()
        _token_issue_times.clear()
        _token_expiry_times.clear()
        _persisted_token_cache = {}
        path = _token_cache_file_path()
        if os.path.exists(path):
            try:
                os.remove(path)
            except Exception:
                logger.warning("Failed to remove token cache file", exc_info=True)


def _get_ci(row: dict, wanted_key: str):
    if not isinstance(row, dict):
        return None, None
    wanted = str(wanted_key).lower()
    for k, v in row.items():
        if str(k).lower() == wanted:
            return v, str(k)
    return None, None

def _to_int(v):
    try:
        if v is None:
            return 0
        if isinstance(v, (int, float)):
            return int(v)
        s = str(v).strip().replace(",", "")
        if s == "":
            return 0
        return int(float(s))
    except Exception:
        return 0

def _to_float(v):
    try:
        if v is None:
            return 0.0
        if isinstance(v, (int, float)):
            return float(v)
        s = str(v).strip().replace(",", "")
        if s == "":
            return 0.0
        return float(s)
    except Exception:
        return 0.0


def _is_kor_regular_session(now_kst: datetime.datetime | None = None) -> bool:
    if now_kst is None:
        now_kst = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9)))
    if now_kst.weekday() >= 5:
        return False
    hm = now_kst.hour * 60 + now_kst.minute
    return (9 * 60) <= hm <= (15 * 60 + 30)


def _pick_domestic_display_price(quote_output: dict, fallback_price: int) -> int:
    # Regular-session and after-hours prices can both exist in quote output.
    regular_price, after_price = _extract_domestic_quote_prices(quote_output)

    if _is_kor_regular_session():
        return regular_price or after_price or fallback_price
    return after_price or regular_price or fallback_price


def _extract_domestic_quote_prices(quote_output: dict) -> tuple[int, int]:
    regular_keys = ["stck_prpr", "prpr"]
    after_keys = ["ovtm_untp_prpr", "ovtm_vi_cls_prc", "ovtm_prpr"]

    regular_price = 0
    after_price = 0

    for k in regular_keys:
        v = _to_int(quote_output.get(k, 0))
        if v > 0:
            regular_price = v
            break

    for k in after_keys:
        v = _to_int(quote_output.get(k, 0))
        if v > 0:
            after_price = v
            break
    return regular_price, after_price


def _get_domestic_quote_output(token, app_key, app_secret, ticker: str, market_div_code: str) -> dict | None:
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "FHKST01010100",
    }
    params = {
        "FID_COND_MRKT_DIV_CODE": market_div_code,
        "FID_INPUT_ISCD": str(ticker).zfill(6),
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/quotations/inquire-price"
    try:
        res, data, _headers = _authorized_request_once(
            requests.get,
            url,
            headers,
            params=params,
            timeout=5,
            app_key=app_key,
            app_secret=app_secret,
        )
        if res.status_code != 200:
            return None
        out = data.get("output") or {}
        if not isinstance(out, dict) or not out:
            return None
        out_dict: dict[object, object] = out
        return out_dict
    except Exception:
        return None


def get_domestic_quote_price(token, app_key, app_secret, ticker: str) -> int | None:
    # Market code: J=KRX, NX=NXT, UN=통합.
    # For after-hours, prefer NXT/통합 first.
    is_regular_session = _is_kor_regular_session()
    market_order = ["J", "UN", "NX"] if is_regular_session else ["NX", "UN", "J"]
    best_regular_price = 0
    for code in market_order:
        out = _get_domestic_quote_output(token, app_key, app_secret, ticker, code)
        if not out:
            continue
        regular_price, after_price = _extract_domestic_quote_prices(out)
        if is_regular_session:
            price = regular_price or after_price
            if price > 0:
                return price
            continue
        if after_price > 0:
            return after_price
        if best_regular_price <= 0 and regular_price > 0:
            best_regular_price = regular_price
    return best_regular_price or None


def _resolve_domestic_balance_now_price(item: dict, token, app_key, app_secret) -> int:
    fallback_now = int(_to_float(item.get("prpr", "0")))
    ticker = str(item.get("pdno", "")).strip()

    if fallback_now > 0 and _is_kor_regular_session():
        return fallback_now
    if not ticker:
        return fallback_now

    quoted_now = get_domestic_quote_price(token, app_key, app_secret, ticker)
    if quoted_now and quoted_now > 0:
        return quoted_now
    return fallback_now

def _pick_orderable_value(row: dict, prefer_usd: bool = False):
    # Explicit priority only (no fuzzy matching).
    if prefer_usd:
        candidates = [
            "frcr_ord_psbl_amt1",
            "frcr_ord_psbl_amt",
            "frcr_drwg_psbl_amt_1",
            "ord_psbl_amt",
        ]
    else:
        candidates = [
            "ord_psbl_cash",
            "ord_psbl_amt",
            "tot_ord_psbl_cash",
        ]
    for k in candidates:
        raw, actual = _get_ci(row, k)
        if actual is not None:
            val = _to_float(raw)
            if val > 0:
                return val
    return 0.0


def _pick_usd_orderable_from_output2(rows: list):
    # Follow official overseas balance field semantics:
    # prefer "frcr_use_psbl_amt" (외화사용가능금액) first, then known orderable fields.
    usd_candidates = [
        "frcr_use_psbl_amt",
        "frcr_ord_psbl_amt1",
        "frcr_ord_psbl_amt2",
        "frcr_ord_psbl_amt",
        "ord_psbl_frcr_amt",
        "ovrs_ord_psbl_amt",
        "frcr_drwg_psbl_amt_1",
        "frcr_drwg_psbl_amt1",
        "ord_psbl_amt",
    ]
    exrt = 0.0
    usd_rows = []

    for idx, row in enumerate(rows or []):
        if not isinstance(row, dict):
            continue
        crcy = str(row.get("crcy_cd", "")).strip().upper()
        if crcy != "USD":
            continue

        usd_rows.append((idx, row))

        if exrt <= 0:
            exrt = _to_float(
                row.get("bass_exrt")
                or row.get("frst_bltn_exrt")
                or 0.0
            )

    if not usd_rows:
        return 0.0, exrt, "none"

    for key in usd_candidates:
        matches = []
        for idx, row in usd_rows:
            raw, actual = _get_ci(row, key)
            if actual is None:
                continue
            value = _to_float(raw)
            if value > 0:
                matches.append((value, f"output2[{idx}].{actual}"))
        if matches:
            # Same key may appear in multiple USD rows; pick largest available amount.
            matches.sort(key=lambda x: x[0], reverse=True)
            return matches[0][0], exrt, matches[0][1]

    return 0.0, exrt, "none"


def _pick_foreign_sell_reuse_from_output2(rows: list, currency_code: str):
    candidates = [
        "sll_ruse_psbl_amt",
        "sl_ruse_frcr_amt",
        "frcr_sll_amt_smtl",
    ]
    exrt = 0.0
    matched_rows = []
    target_currency = str(currency_code or "").strip().upper()

    for idx, row in enumerate(rows or []):
        if not isinstance(row, dict):
            continue
        crcy = str(row.get("crcy_cd", "")).strip().upper()
        if crcy != target_currency:
            continue
        matched_rows.append((idx, row))
        if exrt <= 0:
            exrt = _to_float(row.get("bass_exrt") or row.get("frst_bltn_exrt") or 0.0)

    if not matched_rows and target_currency:
        for idx, row in enumerate(rows or []):
            if not isinstance(row, dict):
                continue
            matched_rows.append((idx, row))
            if exrt <= 0:
                exrt = _to_float(row.get("bass_exrt") or row.get("frst_bltn_exrt") or 0.0)

    if not matched_rows:
        return 0.0, exrt, "none"

    for key in candidates:
        matches = []
        for idx, row in matched_rows:
            raw, actual = _get_ci(row, key)
            if actual is None:
                continue
            value = _to_float(raw)
            if value > 0:
                matches.append((value, f"output2[{idx}].{actual}"))
        if matches:
            matches.sort(key=lambda x: x[0], reverse=True)
            return matches[0][0], exrt, matches[0][1]

    return 0.0, exrt, "none"


def _pick_foreign_balance_from_output2(rows: list, currency_code: str):
    candidates = [
        "frcr_dncl_amt_2",
        "tot_frcr_cblc_smtl",
        "frcr_use_psbl_amt",
        "frcr_drwg_psbl_amt_1",
        "frcr_drwg_psbl_amt1",
        "frcr_ord_psbl_amt1",
        "frcr_ord_psbl_amt2",
        "frcr_ord_psbl_amt",
        "ord_psbl_frcr_amt",
        "ovrs_ord_psbl_amt",
        "ord_psbl_amt",
    ]
    exrt = 0.0
    matched_rows = []
    target_currency = str(currency_code or "").strip().upper()

    for idx, row in enumerate(rows or []):
        if not isinstance(row, dict):
            continue
        crcy = str(row.get("crcy_cd", "")).strip().upper()
        if crcy != target_currency:
            continue
        matched_rows.append((idx, row))
        if exrt <= 0:
            exrt = _to_float(row.get("bass_exrt") or row.get("frst_bltn_exrt") or 0.0)

    if not matched_rows and target_currency:
        for idx, row in enumerate(rows or []):
            if not isinstance(row, dict):
                continue
            matched_rows.append((idx, row))
            if exrt <= 0:
                exrt = _to_float(row.get("bass_exrt") or row.get("frst_bltn_exrt") or 0.0)

    if not matched_rows:
        return 0.0, exrt, "none"

    for key in candidates:
        matches = []
        for idx, row in matched_rows:
            raw, actual = _get_ci(row, key)
            if actual is None:
                continue
            value = _to_float(raw)
            if value > 0:
                matches.append((value, f"output2[{idx}].{actual}"))
        if matches:
            matches.sort(key=lambda x: x[0], reverse=True)
            return matches[0][0], exrt, matches[0][1]

    return 0.0, exrt, "none"


def _pick_foreign_cash_balance_from_output2(rows: list, currency_code: str):
    return _pick_foreign_balance_from_output2(rows, currency_code)


def _normalize_balance_rows(rows):
    if isinstance(rows, list):
        return [row for row in rows if isinstance(row, dict)]
    if isinstance(rows, dict):
        return [rows]
    return []


def _pick_foreign_cash_balance_from_output1_cash_row(rows, currency_code: str):
    target_currency = str(currency_code or "").strip().upper()
    if not target_currency:
        return 0.0, 0.0, "none"

    candidates = [
        "ccld_qty_smtl1",
        "frcr_dncl_amt_2",
        "tot_frcr_cblc_smtl",
        "frcr_use_psbl_amt",
        "frcr_drwg_psbl_amt_1",
    ]

    for idx, row in enumerate(_normalize_balance_rows(rows)):
        pdno = str(row.get("pdno", "")).strip().upper()
        if pdno != target_currency:
            continue
        exrt = _to_float(row.get("bass_exrt") or row.get("frst_bltn_exrt") or 0.0)
        for key in candidates:
            raw, actual = _get_ci(row, key)
            if actual is None:
                continue
            value = _to_float(raw)
            if value > 0:
                return value, exrt, f"output1[{idx}].{actual}"
        return 0.0, exrt, f"output1[{idx}]"

    return 0.0, 0.0, "none"


def _pick_foreign_cash_balance_from_output3(summary_row: dict):
    if not isinstance(summary_row, dict):
        return 0.0, "none"

    candidates = [
        "frcr_dncl_amt_2",
        "tot_frcr_cblc_smtl",
        "frcr_use_psbl_amt",
        "ord_psbl_frcr_amt",
        "frcr_drwg_psbl_amt_1",
    ]
    for key in candidates:
        raw, actual = _get_ci(summary_row, key)
        if actual is None:
            continue
        value = _to_float(raw)
        if value > 0:
            return value, f"output3.{actual}"
    return 0.0, "none"


def _pick_usd_orderable_from_output3(summary_row: dict):
    if not isinstance(summary_row, dict):
        return 0.0, "none"

    candidates = [
        "frcr_use_psbl_amt",
        "frcr_ord_psbl_amt1",
        "ord_psbl_frcr_amt",
        "ovrs_ord_psbl_amt",
    ]
    for key in candidates:
        raw, actual = _get_ci(summary_row, key)
        if actual is None:
            continue
        value = _to_float(raw)
        if value > 0:
            return value, f"output3.{actual}"
    return 0.0, "none"


def _pick_foreign_sell_reuse_from_output3(summary_row: dict):
    if not isinstance(summary_row, dict):
        return 0.0, "none"

    candidates = [
        "sll_ruse_psbl_amt",
        "sl_ruse_frcr_amt",
        "frcr_sll_amt_smtl",
    ]
    for key in candidates:
        raw, actual = _get_ci(summary_row, key)
        if actual is None:
            continue
        value = _to_float(raw)
        if value > 0:
            return value, f"output3.{actual}"
    return 0.0, "none"

def _pick_domestic_orderable_cash(summary: dict):
    # Strict KRW orderable fields only.
    exact_keys = [
        "ord_psbl_cash",
        "ord_psbl_amt",
        "tot_ord_psbl_cash",
        "max_buy_amt",
        "nrcvb_buy_amt",
    ]
    for k in exact_keys:
        raw, actual = _get_ci(summary, k)
        if actual is not None:
            v = _to_float(raw)
            if v > 0:
                return v, actual

    # Fallback (response variant): choose the minimum positive among cash-like candidates.
    # This avoids over-reporting cash when a broader balance field is present.
    fallback_keys = [
        "nrcvb_buy_amt",
        "nxdy_excc_amt",
        "prvs_rcdl_excc_amt",
    ]
    fallback_vals = []
    for k in fallback_keys:
        raw, actual = _get_ci(summary, k)
        if actual is not None:
            v = _to_float(raw)
            if v > 0:
                fallback_vals.append((v, actual))
    if fallback_vals:
        fallback_vals.sort(key=lambda x: x[0])
        return fallback_vals[0][0], fallback_vals[0][1]

    return 0.0, "none"


def _scan_domestic_orderable_from_output2(rows: list):
    # Search all rows for explicit orderable keys first.
    exact_keys = [
        "ord_psbl_cash",
        "ord_psbl_amt",
        "tot_ord_psbl_cash",
        "max_buy_amt",
        "nrcvb_buy_amt",
    ]
    for idx, row in enumerate(rows):
        for k in exact_keys:
            raw, actual = _get_ci(row, k)
            if actual is None:
                continue
            v = _to_float(raw)
            if v > 0:
                return v, f"output2[{idx}].{actual}"
    return 0.0, "none"


def get_domestic_orderable_cash(token, app_key, app_secret, cano, acnt_prdt_cd):
    # Dedicated endpoint for KRW orderable amount.
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "TTTC8908R",
    }
    params = {
        "CANO": cano,
        "ACNT_PRDT_CD": acnt_prdt_cd,
        "PDNO": "005930",  # Dummy domestic ticker for capability query.
        "ORD_UNPR": "1",
        "ORD_DVSN": "01",
        "CMA_EVLU_AMT_ICLD_YN": "N",
        "OVRS_ICLD_YN": "N",
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/inquire-psbl-order"
    try:
        res, data, _headers = _authorized_request_once(
            requests.get,
            url,
            headers,
            params=params,
            timeout=8,
            app_key=app_key,
            app_secret=app_secret,
        )
        if res.status_code != 200:
            return 0, "psbl_api_http_error"
        out_raw = data.get("output") or {}
        out: dict[str, object] = out_raw if isinstance(out_raw, dict) else {}
        for k in ["nrcvb_buy_amt", "ord_psbl_cash", "ord_psbl_amt", "max_buy_amt"]:
            raw, actual = _get_ci(out, k)
            if actual is None:
                continue
            v = int(_to_float(raw))
            if v > 0:
                return v, f"inquire-psbl-order.{actual}"
        return 0, "psbl_api_no_cash_key"
    except Exception:
        return 0, "psbl_api_exception"

def _invalidate_access_token(app_key: str | None = None, app_secret: str | None = None):
    with _token_lock:
        if app_key is None or app_secret is None:
            _cached_tokens.clear()
            _token_issue_times.clear()
            _token_expiry_times.clear()
            clear_persisted_token_cache()
            return

        scope_key = _token_scope_key(app_key, app_secret)
        _cached_tokens.pop(scope_key, None)
        _token_issue_times.pop(scope_key, None)
        _token_expiry_times.pop(scope_key, None)
        cache = _load_persisted_token_cache_locked()
        if cache.pop(scope_key, None) is not None:
            _save_persisted_token_cache_locked()


def get_access_token(app_key, app_secret):
    scope_key = _token_scope_key(app_key, app_secret)
    with _token_lock:
        now = time.time()
        cached_token = _cached_tokens.get(scope_key)
        cached_issue_time = _token_issue_times.get(scope_key, 0)
        cached_expires_at = _token_expiry_times.get(scope_key, 0)
        if cached_token and now < max(cached_expires_at - 60, cached_issue_time):
            return cached_token

        persisted_token = _load_persisted_token_for_scope_locked(scope_key)
        if persisted_token:
            return persisted_token

        if not cached_token and cached_issue_time > 0 and (now - cached_issue_time) < 65:
            return None

        headers = {"content-type": "application/json"}
        body = {
            "grant_type": "client_credentials",
            "appkey": app_key,
            "appsecret": app_secret,
        }
        url = f"{config.URL_BASE}/oauth2/tokenP"
        _token_issue_times[scope_key] = now
        try:
            res = requests.post(url, headers=headers, data=json.dumps(body), timeout=10)
        except Exception:
            _token_issue_times.pop(scope_key, None)
            logger.exception("Failed to get access token")
            return None

        if res.status_code == 200:
            body = res.json()
            access_token = str(body.get("access_token") or "").strip()
            expires_in = _to_int(body.get("expires_in") or 0)
            expires_at = now + expires_in if expires_in > 0 else now + 43200
            if not access_token:
                _token_issue_times.pop(scope_key, None)
                return None
            _cached_tokens[scope_key] = access_token
            _token_issue_times[scope_key] = now
            _token_expiry_times[scope_key] = expires_at
            _persist_token_locked(scope_key, access_token, now, expires_at)
            return access_token

        _token_issue_times.pop(scope_key, None)
        logger.warning("Token request failed status=%s body=%s", res.status_code, res.text[:300])
        return None


def _authorized_request_once(
    request_fn,
    url: str,
    headers: dict[str, str],
    *,
    params: Mapping[str, object] | None = None,
    data: str | None = None,
    timeout: int = 10,
    app_key: str | None = None,
    app_secret: str | None = None,
):
    request_headers = dict(headers)
    refreshed = False

    while True:
        res = request_fn(url, headers=request_headers, params=params, data=data, timeout=timeout)
        payload: dict[str, object] = {}
        try:
            parsed = res.json()
            payload = parsed if isinstance(parsed, dict) else {}
        except Exception:
            payload = {}

        if _is_token_error_response(res, payload) and app_key and app_secret and not refreshed:
            refreshed = True
            _invalidate_access_token(app_key, app_secret)
            new_token = get_access_token(app_key, app_secret)
            if not new_token:
                return res, payload, request_headers
            request_headers = dict(request_headers)
            request_headers["authorization"] = f"Bearer {new_token}"
            continue

        return res, payload, request_headers


def _trade_env_mode() -> str:
    return "demo" if str(config.TRADE_MODE).strip().lower() == "paper" else "real"


def place_domestic_order_cash(
    token,
    app_key,
    app_secret,
    cano,
    acnt_prdt_cd,
    *,
    side: str,
    pdno: str,
    ord_dvsn: str,
    ord_qty: str,
    ord_unpr: str,
    excg_id_dvsn_cd: str = "SOR",
    sll_type: str = "",
    cndt_pric: str = "",
):
    normalized_side = str(side or "").strip().lower()
    env_dv = _trade_env_mode()
    if normalized_side == "sell":
        tr_id = "VTTC0011U" if env_dv == "demo" else "TTTC0011U"
    elif normalized_side == "buy":
        tr_id = "VTTC0012U" if env_dv == "demo" else "TTTC0012U"
    else:
        raise ValueError("side must be 'buy' or 'sell'")

    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": tr_id,
        "custtype": "P",
    }
    payload = {
        "CANO": str(cano or "").strip(),
        "ACNT_PRDT_CD": str(acnt_prdt_cd or "01").strip() or "01",
        "PDNO": str(pdno or "").strip(),
        "ORD_DVSN": str(ord_dvsn or "00").strip() or "00",
        "ORD_QTY": str(ord_qty or "").strip(),
        "ORD_UNPR": str(ord_unpr or "").strip(),
        "EXCG_ID_DVSN_CD": str(excg_id_dvsn_cd or "SOR").strip() or "SOR",
        "SLL_TYPE": str(sll_type or "").strip(),
        "CNDT_PRIC": str(cndt_pric or "").strip(),
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/order-cash"
    response, data, _headers = _authorized_request_once(
        requests.post,
        url,
        headers,
        data=json.dumps(payload),
        timeout=10,
        app_key=app_key,
        app_secret=app_secret,
    )
    if response.status_code != 200 or str(data.get("rt_cd", "")).strip() not in {"0", ""}:
        detail = str(data.get("msg1") or response.text[:300] or "domestic order failed")
        raise RuntimeError(detail)
    output = data.get("output") if isinstance(data.get("output"), dict) else {}
    return {
        "tr_id": tr_id,
        "raw": data,
        "output": output,
        "broker_order": {
            "odno": str(output.get("ODNO") or output.get("odno") or "").strip(),
            "krx_fwdg_ord_orgno": str(output.get("KRX_FWDG_ORD_ORGNO") or output.get("krx_fwdg_ord_orgno") or "").strip(),
            "ord_tmd": str(output.get("ORD_TMD") or output.get("ord_tmd") or "").strip(),
        },
    }


def inquire_domestic_daily_ccld(
    token,
    app_key,
    app_secret,
    cano,
    acnt_prdt_cd,
    *,
    order_no: str,
    start_date: str,
    end_date: str,
):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "VTTC0081R" if _trade_env_mode() == "demo" else "TTTC0081R",
        "custtype": "P",
    }
    params = {
        "CANO": str(cano or "").strip(),
        "ACNT_PRDT_CD": str(acnt_prdt_cd or "01").strip() or "01",
        "INQR_STRT_DT": str(start_date or "").replace("-", "").strip(),
        "INQR_END_DT": str(end_date or "").replace("-", "").strip(),
        "SLL_BUY_DVSN_CD": "00",
        "INQR_DVSN": "00",
        "PDNO": "",
        "CCLD_DVSN": "00",
        "ORD_GNO_BRNO": "",
        "ODNO": str(order_no or "").strip(),
        "INQR_DVSN_3": "00",
        "INQR_DVSN_1": "",
        "CTX_AREA_FK100": "",
        "CTX_AREA_NK100": "",
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
    response, data, _headers = _authorized_request_once(
        requests.get,
        url,
        headers,
        params=params,
        timeout=10,
        app_key=app_key,
        app_secret=app_secret,
    )
    if response.status_code != 200 or str(data.get("rt_cd", "")).strip() not in {"0", ""}:
        detail = str(data.get("msg1") or response.text[:300] or "daily ccld inquiry failed")
        raise RuntimeError(detail)
    rows = data.get("output1") if isinstance(data.get("output1"), list) else []
    normalized: list[dict[str, object]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        normalized.append(
            {
                "odno": str(row.get("odno") or row.get("ODNO") or "").strip(),
                "orgn_odno": str(row.get("orgn_odno") or row.get("ORGN_ODNO") or "").strip(),
                "ord_qty": _to_int(row.get("ord_qty") or row.get("ORD_QTY") or 0),
                "tot_ccld_qty": _to_int(row.get("tot_ccld_qty") or row.get("TOT_CCLD_QTY") or 0),
                "rmn_qty": _to_int(row.get("rmn_qty") or row.get("RMN_QTY") or 0),
                "cncl_yn": str(row.get("cncl_yn") or row.get("CNCL_YN") or "").strip().upper(),
                "cnc_cfrm_qty": _to_int(row.get("cnc_cfrm_qty") or row.get("CNC_CFRM_QTY") or 0),
                "rjct_qty": _to_int(row.get("rjct_qty") or row.get("RJCT_QTY") or 0),
                "ord_unpr": _to_int(row.get("ord_unpr") or row.get("ORD_UNPR") or 0),
                "ord_tmd": str(row.get("ord_tmd") or row.get("ORD_TMD") or "").strip(),
                "raw": row,
            }
        )
    return normalized


def inquire_domestic_psbl_rvsecncl(
    token,
    app_key,
    app_secret,
    cano,
    acnt_prdt_cd,
):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "VTTC0084R" if _trade_env_mode() == "demo" else "TTTC0084R",
        "custtype": "P",
    }
    params = {
        "CANO": str(cano or "").strip(),
        "ACNT_PRDT_CD": str(acnt_prdt_cd or "01").strip() or "01",
        "INQR_DVSN_1": "1",
        "INQR_DVSN_2": "0",
        "CTX_AREA_FK100": "",
        "CTX_AREA_NK100": "",
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/inquire-psbl-rvsecncl"
    response, data, _headers = _authorized_request_once(
        requests.get,
        url,
        headers,
        params=params,
        timeout=10,
        app_key=app_key,
        app_secret=app_secret,
    )
    if response.status_code != 200 or str(data.get("rt_cd", "")).strip() not in {"0", ""}:
        detail = str(data.get("msg1") or response.text[:300] or "psbl rvsecncl inquiry failed")
        raise RuntimeError(detail)
    rows = data.get("output") if isinstance(data.get("output"), list) else data.get("output1") if isinstance(data.get("output1"), list) else []
    normalized: list[dict[str, object]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        normalized.append(
            {
                "odno": str(row.get("odno") or row.get("ODNO") or "").strip(),
                "orgn_odno": str(row.get("orgn_odno") or row.get("ORGN_ODNO") or "").strip(),
                "ord_qty": _to_int(row.get("ord_qty") or row.get("ORD_QTY") or 0),
                "tot_ccld_qty": _to_int(row.get("tot_ccld_qty") or row.get("TOT_CCLD_QTY") or 0),
                "psbl_qty": _to_int(row.get("psbl_qty") or row.get("PSBL_QTY") or 0),
                "ord_dvsn_cd": str(row.get("ord_dvsn_cd") or row.get("ORD_DVSN_CD") or "").strip(),
                "excg_id_dvsn_cd": str(row.get("excg_id_dvsn_cd") or row.get("EXCG_ID_DVSN_CD") or "").strip(),
                "raw": row,
            }
        )
    return normalized


def inquire_domestic_psbl_sell(
    token,
    app_key,
    app_secret,
    cano,
    acnt_prdt_cd,
    *,
    pdno: str,
    ord_dvsn: str = "00",
    excg_id_dvsn_cd: str = "KRX",
):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "VTTC0082R" if _trade_env_mode() == "demo" else "TTTC0082R",
        "custtype": "P",
    }
    params = {
        "CANO": str(cano or "").strip(),
        "ACNT_PRDT_CD": str(acnt_prdt_cd or "01").strip() or "01",
        "PDNO": str(pdno or "").strip(),
        "ORD_DVSN": str(ord_dvsn or "00").strip() or "00",
        "EXCG_ID_DVSN_CD": str(excg_id_dvsn_cd or "KRX").strip() or "KRX",
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/inquire-psbl-sell"
    response, data, _headers = _authorized_request_once(
        requests.get,
        url,
        headers,
        params=params,
        timeout=10,
        app_key=app_key,
        app_secret=app_secret,
    )
    if response.status_code != 200 or str(data.get("rt_cd", "")).strip() not in {"0", ""}:
        detail = str(data.get("msg1") or response.text[:300] or "psbl sell inquiry failed")
        raise RuntimeError(detail)

    rows = data.get("output") if isinstance(data.get("output"), list) else data.get("output1") if isinstance(data.get("output1"), list) else []
    normalized: list[dict[str, object]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        ord_psbl_qty = _to_int(row.get("ord_psbl_qty") or row.get("ORD_PSBL_QTY") or row.get("ord_psbl_qyt") or row.get("ORD_PSBL_QTY") or row.get("psbl_qty") or row.get("PSBL_QTY") or 0)
        normalized.append(
            {
                "pdno": str(row.get("pdno") or row.get("PDNO") or "").strip(),
                "ord_psbl_qty": int(ord_psbl_qty),
                "psbl_qty": int(ord_psbl_qty),
                "ord_dvsn_cd": str(row.get("ord_dvsn_cd") or row.get("ORD_DVSN_CD") or "").strip(),
                "excg_id_dvsn_cd": str(row.get("excg_id_dvsn_cd") or row.get("EXCG_ID_DVSN_CD") or "").strip(),
                "raw": row,
            }
        )
    return normalized


def cancel_domestic_order(
    token,
    app_key,
    app_secret,
    cano,
    acnt_prdt_cd,
    *,
    krx_fwdg_ord_orgno: str,
    orgn_odno: str,
    ord_qty: str,
    ord_unpr: str,
    ord_dvsn: str = "00",
    excg_id_dvsn_cd: str = "SOR",
    qty_all_ord_yn: str = "Y",
):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "VTTC0013U" if _trade_env_mode() == "demo" else "TTTC0013U",
        "custtype": "P",
    }
    payload = {
        "CANO": str(cano or "").strip(),
        "ACNT_PRDT_CD": str(acnt_prdt_cd or "01").strip() or "01",
        "KRX_FWDG_ORD_ORGNO": str(krx_fwdg_ord_orgno or "").strip(),
        "ORGN_ODNO": str(orgn_odno or "").strip(),
        "ORD_DVSN": str(ord_dvsn or "00").strip() or "00",
        "RVSE_CNCL_DVSN_CD": "02",
        "ORD_QTY": str(ord_qty or "").strip(),
        "ORD_UNPR": str(ord_unpr or "").strip(),
        "QTY_ALL_ORD_YN": str(qty_all_ord_yn or "Y").strip() or "Y",
        "EXCG_ID_DVSN_CD": str(excg_id_dvsn_cd or "SOR").strip() or "SOR",
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/order-rvsecncl"
    response, data, _headers = _authorized_request_once(
        requests.post,
        url,
        headers,
        data=json.dumps(payload),
        timeout=10,
        app_key=app_key,
        app_secret=app_secret,
    )
    if response.status_code != 200 or str(data.get("rt_cd", "")).strip() not in {"0", ""}:
        detail = str(data.get("msg1") or response.text[:300] or "domestic order cancel failed")
        raise RuntimeError(detail)
    output = data.get("output") if isinstance(data.get("output"), dict) else {}
    return {"raw": data, "output": output}


def _bearer_token_from_headers(headers: Mapping[str, str], fallback_token: str) -> str:
    authorization = str(headers.get("authorization") or "").strip()
    if authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
        if token:
            return token
    return fallback_token


def _is_token_error_response(res, data: dict[str, object]) -> bool:
    if getattr(res, "status_code", None) == 401:
        return True
    msg_cd = str((data or {}).get("msg_cd", "")).strip()
    msg = str((data or {}).get("msg1", "")).lower()
    return msg_cd in {"EGW00123", "EGW00121"} or "token" in msg


def _authorized_paginated_request(url, headers, params, fk_field: str, nk_field: str, max_pages: int = 10, app_key: str | None = None, app_secret: str | None = None):
    pages = []
    next_params = dict(params)
    refreshed = False

    for _ in range(max_pages):
        try:
            res = requests.get(url, headers=headers, params=next_params, timeout=10)
        except Exception:
            logger.exception("Paginated request failed: %s", url)
            break

        data = {}
        try:
            data = res.json()
        except Exception:
            data = {}

        if _is_token_error_response(res, data) and app_key and app_secret and not refreshed:
            refreshed = True
            _invalidate_access_token(app_key, app_secret)
            new_token = get_access_token(app_key, app_secret)
            if not new_token:
                break
            headers = dict(headers)
            headers["authorization"] = f"Bearer {new_token}"
            continue

        yield res, data, headers, next_params

def get_domestic_balance(token, app_key, app_secret, cano, acnt_prdt_cd):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "TTTC8434R", 
    }
    params = {
        "CANO": cano,
        "ACNT_PRDT_CD": acnt_prdt_cd,
        "AFHR_FLPR_YN": "N",
        "OFL_YN": "",
        "INQR_DVSN": "01",
        "UNPR_DVSN": "01",
        "FUND_STTL_ICLD_YN": "N",
        "FNCG_AMT_AUTO_RDPT_YN": "N",
        "PRCS_DVSN": "00",
        "CTX_AREA_FK100": "",
        "CTX_AREA_NK100": ""
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/inquire-balance"
    res, data, headers = _authorized_request_once(
        requests.get,
        url,
        headers,
        params=params,
        timeout=10,
        app_key=app_key,
        app_secret=app_secret,
    )
    token = _bearer_token_from_headers(headers, token)
    
    items: list[dict[str, object]] = []
    result: dict[str, object] = {"summary": {}, "items": items}
    quote_lookup_count = 0
    if res.status_code == 200:
        output2_raw = data.get("output2") or []
        output2_rows: list[dict[str, object]] = (
            [row for row in output2_raw if isinstance(row, dict)]
            if isinstance(output2_raw, list)
            else []
        )
        if len(output2_rows) > 0:
            summary = output2_rows[0] if isinstance(output2_rows[0], dict) else {}
            psbl_cash, psbl_key = get_domestic_orderable_cash(
                token, app_key, app_secret, cano, acnt_prdt_cd
            )
            # 1) scan all rows for explicit orderable fields
            orderable_cash_raw, orderable_cash_key = _scan_domestic_orderable_from_output2(output2_rows)
            if psbl_cash > 0:
                orderable_cash_raw = psbl_cash
                orderable_cash_key = psbl_key
            # 2) fallback to summary-only heuristic
            if orderable_cash_raw <= 0:
                orderable_cash_raw, orderable_cash_key = _pick_domestic_orderable_cash(summary)
            orderable_cash = int(orderable_cash_raw)

            logger.debug(
                "Domestic cash selected: key=%s, value=%s",
                orderable_cash_key,
                orderable_cash,
            )
            result["summary"] = {
                "total_purchase_amt": _to_int(summary.get('pchs_amt_smtl_amt', 0)),
                "total_eval_amt": _to_int(summary.get('evlu_amt_smtl_amt', 0)),
                "total_profit_loss": _to_int(summary.get('evlu_pfls_smtl_amt', 0)),
                "cash_balance": orderable_cash
            }
        output1_raw = data.get("output1") or []
        output1_rows: list[dict[str, object]] = (
            [row for row in output1_raw if isinstance(row, dict)]
            if isinstance(output1_raw, list)
            else []
        )
        for item in output1_rows:
                ticker = item.get("pdno", "")
                qty = _to_int(item.get("hldg_qty", "0"))
                if qty <= 0:
                    continue
                fallback_now = int(_to_float(item.get("prpr", "0")))
                if ticker and not (fallback_now > 0 and _is_kor_regular_session()):
                    quote_lookup_count += 1
                now_price = _resolve_domestic_balance_now_price(
                    item,
                    token,
                    app_key,
                    app_secret,
                )
                items.append({
                    "name": item.get("prdt_name", "알수없음"),
                    "ticker": ticker,
                    "qty": qty,
                    "avg_price": int(_to_float(item.get("pchs_avg_pric", "0"))),
                    "now_price": now_price,
                    "profit_rt": (
                        ((now_price - _to_float(item.get("pchs_avg_pric", "0"))) / _to_float(item.get("pchs_avg_pric", "0")) * 100)
                        if _to_float(item.get("pchs_avg_pric", "0")) > 0 else 0.0
                    )
                })
    return result

def get_overseas_balance(token, app_key, app_secret, cano, acnt_prdt_cd):
    url = f"{config.URL_BASE}/uapi/overseas-stock/v1/trading/inquire-present-balance"
    result: dict[str, dict[str, object] | list[dict[str, object]]] = {
        "us_summary": {},
        "jp_summary": {},
        "us_items": [],
        "jp_items": [],
    }
    token_holder = {"value": token}

    def _balance_headers() -> dict[str, str]:
        return {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {token_holder['value']}",
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": "CTRP6504R",
        }

    def _get_overseas_orderable_cash(currency_code: str):
        headers_ps = {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {token_holder['value']}",
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": "TTTS3007R",
        }
        url_ps = f"{config.URL_BASE}/uapi/overseas-stock/v1/trading/inquire-psamount"
        tries_by_currency = {
            "USD": [
                ("NASD", "QQQ", "1"),
                ("NYSE", "KO", "1"),
            ],
            "JPY": [
                ("TKSE", "7203", "1"),
            ],
        }
        tries = tries_by_currency.get(str(currency_code or "").strip().upper(), [])
        for exch, item, unpr in tries:
            params_ps = {
                "CANO": cano,
                "ACNT_PRDT_CD": acnt_prdt_cd,
                "OVRS_EXCG_CD": exch,
                "OVRS_ORD_UNPR": unpr,
                "ITEM_CD": item,
            }
            try:
                rr, body, headers_ps = _authorized_request_once(
                    requests.get,
                    url_ps,
                    headers_ps,
                    params=params_ps,
                    timeout=8,
                    app_key=app_key,
                    app_secret=app_secret,
                )
                token_holder["value"] = _bearer_token_from_headers(
                    headers_ps, token_holder["value"]
                )
                if rr.status_code != 200:
                    continue
                if str(body.get("rt_cd")) != "0":
                    continue
                out = body.get("output") or {}
                out_dict: dict[str, object] = out if isinstance(out, dict) else {}
                ovrs_amt = _to_float(out_dict.get("ovrs_ord_psbl_amt"))
                if ovrs_amt > 0:
                    return ovrs_amt, "inquire-psamount.ovrs_ord_psbl_amt"
                ord_psbl = _to_float(out_dict.get("ord_psbl_frcr_amt"))
                if ord_psbl > 0:
                    return ord_psbl, "inquire-psamount.ord_psbl_frcr_amt"
            except Exception:
                continue
        return 0.0, "none"

    def _fetch_us_balance() -> dict[str, object]:
        us_items: list[dict[str, object]] = []
        us_result: dict[str, object] = {"us_summary": {}, "us_items": us_items}
        headers = _balance_headers()
        params_us_foreign = {
            "CANO": cano,
            "ACNT_PRDT_CD": acnt_prdt_cd,
            "WCRC_FRCR_DVSN_CD": "02",
            "NATN_CD": "840",
            "TR_MKET_CD": "00",
            "INQR_DVSN_CD": "00",
            "CTX_AREA_FK200": "",
            "CTX_AREA_NK200": "",
        }

        try:
            while True:
                res, data, headers = _authorized_request_once(
                    requests.get,
                    url,
                    headers,
                    params=params_us_foreign,
                    timeout=10,
                    app_key=app_key,
                    app_secret=app_secret,
                )
                token_holder["value"] = _bearer_token_from_headers(
                    headers, token_holder["value"]
                )
                if res.status_code != 200:
                    break
                if data.get("rt_cd") != "0":
                    break

                output1_page_raw = data.get("output1") or []
                if isinstance(output1_page_raw, list):
                    output1_page: list[dict[str, object]] = (
                        [row for row in output1_page_raw if isinstance(row, dict)]
                    )
                    for item in output1_page:
                        avg_unpr3 = _to_float(item.get("avg_unpr3", "0"))
                        ovrs_now_pric1 = _to_float(item.get("ovrs_now_pric1", "0"))
                        bass_exrt = _to_float(item.get("bass_exrt", "1"))
                        if bass_exrt == 0:
                            bass_exrt = 1

                        us_items.append({
                            "name": item.get("prdt_name", "?뚯닔?놁쓬"),
                            "ticker": item.get("pdno", ""),
                            "excg_cd": item.get("ovrs_excg_cd", "NASD"),
                            "qty": _to_float(item.get("ccld_qty_smtl1", "0")),
                            "profit_rt": ((ovrs_now_pric1 - avg_unpr3) / avg_unpr3 * 100) if avg_unpr3 > 0 else 0.0,
                            "avg_price": avg_unpr3,
                            "now_price": ovrs_now_pric1,
                            "bass_exrt": bass_exrt,
                        })

                out3_raw = data.get("output3") or {}
                if isinstance(out3_raw, dict) and not us_result["us_summary"]:
                    out3: dict[str, object] = out3_raw if isinstance(out3_raw, dict) else {}
                    usd_cash = 0.0
                    usd_exrt = 0.0
                    usd_cash_key = "none"
                    output1_rows = _normalize_balance_rows(data.get("output1"))
                    usd_cash, usd_cash_key = _get_overseas_orderable_cash("USD")
                    output2_page_raw = data.get("output2") or []
                    if isinstance(output2_page_raw, list):
                        output2_page = output2_page_raw
                        _, usd_exrt, _ = _pick_foreign_balance_from_output2(output2_page, "USD")
                    if usd_cash <= 0 and output1_rows:
                        usd_cash, usd_exrt, usd_cash_key = _pick_foreign_cash_balance_from_output1_cash_row(output1_rows, "USD")
                    if usd_cash <= 0 and isinstance(output2_page_raw, list):
                        output2_page = output2_page_raw
                        usd_cash, usd_exrt, usd_cash_key = _pick_foreign_cash_balance_from_output2(output2_page, "USD")
                    if usd_cash <= 0:
                        fallback_cash, fallback_key = _pick_foreign_cash_balance_from_output3(out3)
                        if fallback_cash > 0:
                            usd_cash = fallback_cash
                            usd_cash_key = fallback_key

                    logger.info(
                        "Overseas USD cash selected: cash_key=%s, value=%s",
                        usd_cash_key,
                        usd_cash,
                    )

                    us_result["us_summary"] = {
                        "krw_purchase_amt": _to_float(out3.get("pchs_amt_smtl_amt", 0)),
                        "krw_eval_amt": _to_float(out3.get("evlu_amt_smtl_amt", 0)),
                        "usd_cash_balance": usd_cash,
                        "usd_exrt": usd_exrt,
                    }

                tr_cont = res.headers.get("tr_cont", "")
                if tr_cont in ["F", "M"]:
                    params_us_foreign["CTX_AREA_FK200"] = data.get("ctx_area_fk200", "")
                    params_us_foreign["CTX_AREA_NK200"] = data.get("ctx_area_nk200", "")
                    continue
                break
        except Exception:
            logger.warning("Failed to fetch US overseas balance", exc_info=True)

        return us_result

    def _fetch_jp_balance() -> dict[str, object]:
        headers = _balance_headers()
        params_jp_foreign = {
            "CANO": cano,
            "ACNT_PRDT_CD": acnt_prdt_cd,
            "WCRC_FRCR_DVSN_CD": "02",
            "NATN_CD": "392",
            "TR_MKET_CD": "00",
            "INQR_DVSN_CD": "00",
            "CTX_AREA_FK200": "",
            "CTX_AREA_NK200": "",
        }
        jp_items: list[dict[str, object]] = []
        jp_result: dict[str, object] = {"jp_summary": {}, "jp_items": jp_items}

        try:
            res, data, headers = _authorized_request_once(
                requests.get,
                url,
                headers,
                params=params_jp_foreign,
                timeout=10,
                app_key=app_key,
                app_secret=app_secret,
            )
            token_holder["value"] = _bearer_token_from_headers(
                headers, token_holder["value"]
            )
            if res.status_code != 200:
                return jp_result
            if data.get("rt_cd") != "0":
                return jp_result

            jp_cash = 0.0
            jp_exrt = 0.0
            jp_cash_key = "none"

            output1_rows = _normalize_balance_rows(data.get("output1"))
            output2_rows = _normalize_balance_rows(data.get("output2"))
            output3_row_raw = data.get("output3") or {}
            output3_row: dict[str, object] = (
                output3_row_raw if isinstance(output3_row_raw, dict) else {}
            )

            jp_cash, jp_cash_key = _get_overseas_orderable_cash("JPY")

            if output1_rows and jp_cash <= 0:
                jp_cash, jp_exrt, jp_cash_key = _pick_foreign_cash_balance_from_output1_cash_row(
                    output1_rows, "JPY"
                )

            if output2_rows:
                if jp_exrt <= 0:
                    _, jp_exrt, _ = _pick_foreign_balance_from_output2(output2_rows, "JPY")
                if jp_cash <= 0:
                    jp_cash, jp_exrt, jp_cash_key = _pick_foreign_cash_balance_from_output2(output2_rows, "JPY")

            if jp_cash <= 0 and output3_row:
                fallback_cash, fallback_key = _pick_foreign_cash_balance_from_output3(output3_row)
                if fallback_cash > 0:
                    jp_cash = fallback_cash
                    jp_cash_key = fallback_key

            logger.info(
                "Overseas JPY cash selected: cash_key=%s, value=%s",
                jp_cash_key,
                jp_cash,
            )

            jp_result["jp_summary"] = {
                "jpy_cash_balance": jp_cash,
                "jpy_exrt": jp_exrt,
            }

            for item in output1_rows:
                if str(item.get("pdno", "")).strip().upper() == "JPY":
                    continue
                avg_unpr3 = _to_float(item.get("avg_unpr3", "0"))
                ovrs_now_pric1 = _to_float(item.get("ovrs_now_pric1", "0"))
                bass_exrt = _to_float(item.get("bass_exrt", "1"))
                if bass_exrt == 0:
                    bass_exrt = 1

                jp_items.append({
                    "name": item.get("prdt_name", "?뚯닔?놁쓬"),
                    "ticker": item.get("pdno", ""),
                    "excg_cd": item.get("ovrs_excg_cd", "TKSE"),
                    "qty": _to_float(item.get("ccld_qty_smtl1", "0")),
                    "profit_rt": ((ovrs_now_pric1 - avg_unpr3) / avg_unpr3 * 100) if avg_unpr3 > 0 else 0.0,
                    "avg_price": avg_unpr3,
                    "now_price": ovrs_now_pric1,
                    "bass_exrt": bass_exrt,
                })
        except Exception:
            logger.warning("Failed to fetch JP overseas balance", exc_info=True)

        return jp_result

    with ThreadPoolExecutor(max_workers=2) as executor:
        us_future = executor.submit(_fetch_us_balance)
        jp_future = executor.submit(_fetch_jp_balance)
        us_result = us_future.result()
        jp_result = jp_future.result()

    if isinstance(us_result, dict):
        us_summary = us_result.get("us_summary")
        us_items = us_result.get("us_items")
        result["us_summary"] = us_summary if isinstance(us_summary, dict) else {}
        result["us_items"] = us_items if isinstance(us_items, list) else []
    if isinstance(jp_result, dict):
        jp_summary = jp_result.get("jp_summary")
        jp_items = jp_result.get("jp_items")
        result["jp_summary"] = jp_summary if isinstance(jp_summary, dict) else {}
        result["jp_items"] = jp_items if isinstance(jp_items, list) else []

    # 3. (??젣?? ?먰솕 珥앺빀怨꾨뒗 1踰??몄텧??output3?먯꽌 媛?몄샂
                    
    return result


def _request_with_pagination(url, headers, params, fk_field: str, nk_field: str, max_pages: int = 10, app_key: str | None = None, app_secret: str | None = None):
    pages = []
    tr_cont = ""

    for res, data, headers, next_params in _authorized_paginated_request(
        url, headers, params, fk_field, nk_field, max_pages=max_pages, app_key=app_key, app_secret=app_secret
    ):
        if res.status_code != 200:
            logger.warning("Paginated request returned status=%s for %s", res.status_code, url)
            break

        if str(data.get("rt_cd", "0")) not in {"0", ""}:
            logger.warning("Paginated request returned rt_cd=%s msg=%s", data.get("rt_cd"), data.get("msg1"))
            break

        pages.append((res, data))

        tr_cont = res.headers.get("tr_cont", "")
        if tr_cont not in {"F", "M"}:
            break

        next_fk = data.get(fk_field, "")
        next_nk = data.get(nk_field, "")
        if not next_fk and not next_nk:
            break

        next_params[fk_field.upper()] = next_fk
        next_params[nk_field.upper()] = next_nk
        time.sleep(0.15)

    return pages


def _run_parallel_tasks(tasks: dict[str, tuple]) -> dict[str, object]:
    if not tasks:
        return {}
    results: dict[str, object] = {}
    with ThreadPoolExecutor(max_workers=min(len(tasks), _MAX_PARALLEL_WORKERS)) as executor:
        future_map = {
            name: executor.submit(func, *args)
            for name, (func, args) in tasks.items()
        }
        for name, future in future_map.items():
            results[name] = future.result()
    return results


def _get_cached_payload(cache_key: tuple, ttl_seconds: int = _REALIZED_CACHE_TTL_SECONDS):
    with _cache_lock:
        cached = _realized_cache.get(cache_key)
        if cached and (time.time() - cached["ts"]) < ttl_seconds:
            return cached["data"]
    return None


def _set_cached_payload(cache_key: tuple, data):
    with _cache_lock:
        _realized_cache[cache_key] = {"ts": time.time(), "data": data}


def _fetch_trade_profit_rows(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    cache_key = ("trade_profit_rows", cano, acnt_prdt_cd, start_date, end_date)
    inflight_event = None

    while True:
        cached = _get_cached_payload(cache_key)
        if cached is not None:
            return cached

        with _cache_lock:
            inflight_event = _inflight_trade_profit_rows.get(cache_key)
            if inflight_event is None:
                inflight_event = threading.Event()
                _inflight_trade_profit_rows[cache_key] = inflight_event
                break

        inflight_event.wait()

    try:
        results = _run_parallel_tasks({
            "domestic": (get_domestic_realized_trade_profit, (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)),
            "overseas": (get_overseas_realized_trade_profit, (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)),
        })
        domestic_result = results.get("domestic")
        overseas_result = results.get("overseas")
        payload: dict[str, list[dict]] = {"domestic": [], "overseas": []}
        if isinstance(domestic_result, list):
            payload["domestic"] = domestic_result
        if isinstance(overseas_result, list):
            payload["overseas"] = overseas_result
        _set_cached_payload(cache_key, payload)
        return payload
    finally:
        with _cache_lock:
            current_event = _inflight_trade_profit_rows.get(cache_key)
            if current_event is inflight_event:
                del _inflight_trade_profit_rows[cache_key]
                inflight_event.set()


def _build_realized_profit_summary_payload(trade_profit_rows: Mapping[str, object]) -> dict[str, object]:
    domestic_source = trade_profit_rows.get("domestic", [])
    overseas_source = trade_profit_rows.get("overseas", [])
    domestic_rows = domestic_source if isinstance(domestic_source, list) else []
    overseas_rows = overseas_source if isinstance(overseas_source, list) else []

    by_date: dict[str, dict] = {}
    for row in domestic_rows:
        bucket = by_date.setdefault(row["date"], {
            "date": row["date"],
            "domestic_realized_profit_krw": 0.0,
            "overseas_realized_profit_krw": 0.0,
            "domestic_buy_amount_krw": 0.0,
            "overseas_buy_amount_krw": 0.0,
        })
        bucket["domestic_realized_profit_krw"] += row.get("realized_profit_krw", 0.0)
        bucket["domestic_buy_amount_krw"] += row.get("buy_amount_krw", 0.0)

    for row in overseas_rows:
        bucket = by_date.setdefault(row["date"], {
            "date": row["date"],
            "domestic_realized_profit_krw": 0.0,
            "overseas_realized_profit_krw": 0.0,
            "domestic_buy_amount_krw": 0.0,
            "overseas_buy_amount_krw": 0.0,
        })
        bucket["overseas_realized_profit_krw"] += row.get("realized_profit_krw", 0.0)
        bucket["overseas_buy_amount_krw"] += row.get("buy_amount_krw", 0.0)

    daily_rows = []
    for date_key in sorted(by_date.keys()):
        row = by_date[date_key]
        row["total_realized_profit_krw"] = row["domestic_realized_profit_krw"] + row["overseas_realized_profit_krw"]
        daily_rows.append(row)

    domestic_total = sum(row["domestic_realized_profit_krw"] for row in daily_rows)
    overseas_total = sum(row["overseas_realized_profit_krw"] for row in daily_rows)
    domestic_buy_total = sum(
        row.get("buy_amount_krw", 0.0)
        for row in domestic_rows
        if row.get("buy_amount_krw", 0.0) > 0
    )
    overseas_buy_total = sum(
        row.get("buy_amount_krw", 0.0)
        for row in overseas_rows
        if row.get("buy_amount_krw", 0.0) > 0
    )
    domestic_rate_profit_total = sum(
        row.get("realized_profit_krw", 0.0)
        for row in domestic_rows
        if row.get("buy_amount_krw", 0.0) > 0
    )
    overseas_rate_profit_total = sum(
        row.get("realized_profit_krw", 0.0)
        for row in overseas_rows
        if row.get("buy_amount_krw", 0.0) > 0
    )
    total_buy_amount = domestic_buy_total + overseas_buy_total
    total_realized_return_rate = (
        ((domestic_rate_profit_total + overseas_rate_profit_total) / total_buy_amount * 100)
        if total_buy_amount > 0
        else 0.0
    )

    return {
        "summary": {
            "domestic_realized_profit_krw": domestic_total,
            "overseas_realized_profit_krw": overseas_total,
            "total_realized_profit_krw": domestic_total + overseas_total,
            "total_realized_return_rate": total_realized_return_rate,
            "trade_days": len(daily_rows),
        },
        "daily": daily_rows,
    }


def _normalize_domestic_realized_rows(rows: list[dict]) -> list[dict]:
    normalized = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        trade_date = str(row.get("trad_dt", "")).strip()
        if not trade_date:
            continue
        normalized.append({
            "date": trade_date,
            "domestic_realized_profit_krw": _to_float(row.get("rlzt_pfls", 0)),
            "domestic_fee_krw": _to_float(row.get("fee", 0)),
            "domestic_tax_krw": _to_float(row.get("tl_tax", 0)),
            "domestic_buy_amount_krw": _to_float(row.get("buy_amt", 0)),
        })
    return normalized


def _normalize_side(code: object = "", label: object = "") -> str:
    label = str(label or "").strip()
    code = str(code or "").strip()
    if "매도" in label or code == "01":
        return "매도"
    if "매수" in label or code == "02":
        return "매수"
    return label or code or "-"


def _get_overseas_profit_nation_code(exchange_code: str) -> str:
    exchange_code = str(exchange_code or "").strip().upper()
    if exchange_code in {"NASD", "NYSE", "AMEX", "NAS", "NYS", "AMS"}:
        return "840"
    if exchange_code in {"TKSE", "TSE", "JPX", "TYO"}:
        return "392"
    if exchange_code in {"SEHK", "HKS"}:
        return "344"
    if exchange_code in {"SHAA", "SHS", "SZS"}:
        return "156"
    if exchange_code in {"HASE", "HSX", "HNX"}:
        return "704"
    return ""


def _is_yyyymmdd_in_range(date_value: str, start_date: str, end_date: str) -> bool:
    raw = str(date_value or "").strip()
    if len(raw) != 8 or not raw.isdigit():
        return False
    return start_date <= raw <= end_date


def _dedupe_realized_profit_rows(rows: list[dict]) -> list[dict]:
    deduped = []
    seen = set()
    for row in rows or []:
        key = (
            str(row.get("date", "")),
            str(row.get("symbol", "")),
            round(float(row.get("quantity") or 0), 8),
            round(float(row.get("amount") or 0), 8),
            round(float(row.get("realized_profit_krw") or 0), 8),
            round(float(row.get("buy_amount_krw") or 0), 8),
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(row)
    return deduped


def _normalize_overseas_realized_rows(rows: list[dict]) -> list[dict]:
    normalized = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        trade_date = str(row.get("trad_day", "")).strip()
        if not trade_date:
            continue
        normalized.append({
            "date": trade_date,
            "symbol": str(row.get("ovrs_pdno", "")).strip() or str(row.get("pdno", "")).strip(),
            "realized_profit_krw": _to_float(row.get("ovrs_rlzt_pfls_amt", 0)),
            "overseas_realized_profit_krw": _to_float(row.get("ovrs_rlzt_pfls_amt", 0)),
            "overseas_fee_krw": _to_float(row.get("stck_sll_tlex", 0)),
            "overseas_realized_profit_native": _to_float(row.get("ovrs_rlzt_pfls_amt", 0)),
            "buy_amount_krw": _to_float(row.get("stck_buy_amt_smtl", 0)),
            "overseas_buy_amount_krw": _to_float(row.get("stck_buy_amt_smtl", 0)),
            "exchange_code": str(row.get("ovrs_excg_cd", "")).strip(),
            "currency_code": str(row.get("crcy_cd", "")).strip() or str(row.get("tr_crcy_cd", "")).strip(),
        })
    return normalized


def _normalize_domestic_realized_trade_rows(rows: list[dict]) -> list[dict]:
    normalized = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        trade_date = str(row.get("trad_dt", "")).strip()
        symbol = str(row.get("pdno", "")).strip()
        if not trade_date or not symbol:
            continue
        quantity = _to_float(row.get("sll_qty") or 0)
        amount = _to_float(row.get("sll_amt") or 0)
        if quantity <= 0 or amount <= 0:
            continue
        realized_profit = _to_float(row.get("rlzt_pfls") or 0)
        fee = _to_float(row.get("fee") or 0)
        tax = _to_float(row.get("tl_tax") or 0)
        buy_amount = _to_float(row.get("buy_amt") or 0)
        if buy_amount <= 0:
            buy_amount = max(amount - realized_profit - fee - tax, 0.0)
        normalized.append({
            "date": trade_date,
            "symbol": symbol,
            "quantity": quantity,
            "amount": amount,
            "realized_profit_krw": realized_profit,
            "buy_amount_krw": buy_amount,
            "realized_return_rate": None,
        })
    return normalized


def _normalize_overseas_realized_trade_rows(rows: list[dict]) -> list[dict]:
    normalized = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        trade_date = str(row.get("trad_day", "")).strip()
        symbol = str(row.get("ovrs_pdno", "")).strip() or str(row.get("pdno", "")).strip()
        if not trade_date or not symbol:
            continue
        quantity = _to_float(row.get("slcl_qty") or 0)
        amount = _to_float(row.get("frcr_sll_amt_smtl1") or row.get("stck_sll_amt_smtl") or 0)
        if quantity <= 0 or amount <= 0:
            continue
        realized_profit = _to_float(row.get("ovrs_rlzt_pfls_amt") or 0)
        fee = _to_float(row.get("stck_sll_tlex") or row.get("smtl_fee1") or 0)
        buy_amount = _to_float(row.get("stck_buy_amt_smtl") or 0)
        if buy_amount <= 0:
            buy_amount = max(amount - realized_profit - fee, 0.0)
        normalized.append({
            "date": trade_date,
            "symbol": symbol,
            "quantity": quantity,
            "amount": amount,
            "realized_profit_krw": realized_profit,
            "buy_amount_krw": buy_amount,
            "realized_return_rate": _to_float(row.get("pftrt")) if str(row.get("pftrt", "")).strip() != "" else None,
        })
    return normalized


def get_domestic_realized_profit(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "TTTC8708R",
    }
    params = {
        "CANO": cano,
        "ACNT_PRDT_CD": acnt_prdt_cd,
        "INQR_STRT_DT": start_date,
        "INQR_END_DT": end_date,
        "SORT_DVSN": "01",
        "INQR_DVSN": "00",
        "CBLC_DVSN": "00",
        "PDNO": "",
        "CTX_AREA_FK100": "",
        "CTX_AREA_NK100": "",
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/inquire-period-profit"
    pages = _request_with_pagination(url, headers, params, "ctx_area_fk100", "ctx_area_nk100", app_key=app_key, app_secret=app_secret)

    rows = []
    for _, data in pages:
        rows.extend(_normalize_domestic_realized_rows(data.get("output1") or []))

    return rows


def get_overseas_realized_profit(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    exchange_queries = [
        ("NASD", "USD"),
        ("NYSE", "USD"),
        ("AMEX", "USD"),
        ("TKSE", "JPY"),
        ("SEHK", "HKD"),
        ("SHAA", "CNY"),
        ("HASE", "VND"),
    ]
    url = f"{config.URL_BASE}/uapi/overseas-stock/v1/trading/inquire-period-profit"
    rows = []

    def fetch_exchange(exchange_query: tuple[str, str]) -> list[dict]:
        exchange_code, currency_code = exchange_query
        headers = {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {token}",
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": "TTTS3039R",
        }
        params = {
            "CANO": cano,
            "ACNT_PRDT_CD": acnt_prdt_cd,
            "OVRS_EXCG_CD": exchange_code,
            "NATN_CD": _get_overseas_profit_nation_code(exchange_code),
            "CRCY_CD": currency_code,
            "PDNO": "",
            "INQR_STRT_DT": start_date,
            "INQR_END_DT": end_date,
            "WCRC_FRCR_DVSN_CD": "02",
            "CTX_AREA_FK200": "",
            "CTX_AREA_NK200": "",
        }
        pages = _request_with_pagination(url, headers, params, "ctx_area_fk200", "ctx_area_nk200", app_key=app_key, app_secret=app_secret)
        exchange_rows = []
        for _, data in pages:
            page_rows = data.get("output1") or []
            if isinstance(page_rows, dict):
                page_rows = [page_rows]
            normalized_rows = _normalize_overseas_realized_rows(page_rows)
            exchange_rows.extend(
                row
                for row in normalized_rows
                if _is_yyyymmdd_in_range(str(row.get("date", "")), start_date, end_date)
            )
        return exchange_rows

    with ThreadPoolExecutor(max_workers=min(len(exchange_queries), _MAX_PARALLEL_WORKERS)) as executor:
        for exchange_rows in executor.map(fetch_exchange, exchange_queries):
            rows.extend(exchange_rows)

    return _dedupe_realized_profit_rows(rows)


def get_realized_profit_summary(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    cache_key = ("realized_summary", cano, acnt_prdt_cd, start_date, end_date)
    cached = _get_cached_payload(cache_key)
    if cached is not None:
        return cached

    trade_profit_rows = _fetch_trade_profit_rows(token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)
    result = _build_realized_profit_summary_payload(trade_profit_rows)
    _set_cached_payload(cache_key, result)
    return result


def get_domestic_realized_trade_profit(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "TTTC8715R",
    }
    params = {
        "CANO": cano,
        "ACNT_PRDT_CD": acnt_prdt_cd,
        "SORT_DVSN": "01",
        "INQR_STRT_DT": start_date,
        "INQR_END_DT": end_date,
        "CBLC_DVSN": "00",
        "PDNO": "",
        "CTX_AREA_FK100": "",
        "CTX_AREA_NK100": "",
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/inquire-period-trade-profit"
    pages = _request_with_pagination(url, headers, params, "ctx_area_fk100", "ctx_area_nk100", app_key=app_key, app_secret=app_secret)
    rows = []
    for _, data in pages:
        rows.extend(_normalize_domestic_realized_trade_rows(data.get("output1") or []))
    return rows


def get_overseas_realized_trade_profit(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    exchange_queries = [
        ("NASD", "USD"),
        ("NYSE", "USD"),
        ("AMEX", "USD"),
        ("TKSE", "JPY"),
        ("SEHK", "HKD"),
        ("SHAA", "CNY"),
        ("HASE", "VND"),
    ]
    url = f"{config.URL_BASE}/uapi/overseas-stock/v1/trading/inquire-period-profit"
    rows = []

    def fetch_exchange(exchange_code: str, currency_code: str):
        headers = {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {token}",
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": "TTTS3039R",
        }
        params = {
            "CANO": cano,
            "ACNT_PRDT_CD": acnt_prdt_cd,
            "OVRS_EXCG_CD": exchange_code,
            "NATN_CD": _get_overseas_profit_nation_code(exchange_code),
            "CRCY_CD": currency_code,
            "PDNO": "",
            "INQR_STRT_DT": start_date,
            "INQR_END_DT": end_date,
            "WCRC_FRCR_DVSN_CD": "02",
            "CTX_AREA_FK200": "",
            "CTX_AREA_NK200": "",
        }
        pages = _request_with_pagination(url, headers, params, "ctx_area_fk200", "ctx_area_nk200", app_key=app_key, app_secret=app_secret)
        exchange_rows = []
        for _, data in pages:
            page_rows = data.get("output1") or []
            if isinstance(page_rows, dict):
                page_rows = [page_rows]
            normalized_rows = _normalize_overseas_realized_trade_rows(page_rows)
            normalized_rows = [
                row
                for row in normalized_rows
                if _is_yyyymmdd_in_range(str(row.get("date", "")), start_date, end_date)
            ]
            exchange_rows.extend(normalized_rows)
        return exchange_rows

    with ThreadPoolExecutor(max_workers=min(len(exchange_queries), _MAX_PARALLEL_WORKERS)) as executor:
        for exchange_rows in executor.map(lambda item: fetch_exchange(*item), exchange_queries):
            rows.extend(exchange_rows)
    return _dedupe_realized_profit_rows(rows)


def _normalize_domestic_trade_rows(rows: list[dict]) -> list[dict]:
    normalized = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        trade_date = str(row.get("ord_dt", "")).strip()
        if not trade_date:
            continue
        qty = _to_float(row.get("tot_ccld_qty") or row.get("ord_qty") or 0)
        amount = _to_float(row.get("tot_ccld_amt") or 0)
        # Official KIS example exposes avg_prvs as 평균가. We still prefer
        # total amount / filled quantity when both exist because it matches the
        # actual weighted fill price the user expects to see.
        unit_price = (amount / qty) if qty > 0 and amount > 0 else _to_float(row.get("avg_prvs") or row.get("ord_unpr") or 0)
        symbol = str(row.get("pdno", "")).strip()
        normalized.append({
            "date": trade_date,
            "market": "KOR",
            "symbol": symbol,
            "ticker": symbol,
            "name": str(row.get("prdt_name", "")).strip() or symbol,
            "side": _normalize_side(str(row.get("sll_buy_dvsn_cd") or ""), str(row.get("sll_buy_dvsn_cd_name") or "")),
            "quantity": qty,
            "unit_price": unit_price,
            "amount": amount,
            "currency": "KRW",
            "time": str(row.get("ord_tmd", "")).strip(),
            "realized_profit_krw": None,
        })
    return normalized


def _normalize_overseas_trade_rows(rows: list[dict], fallback_market: str = "OVRS") -> list[dict]:
    normalized = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        trade_date = str(row.get("trad_dt", "")).strip()
        if not trade_date:
            continue
        currency = str(row.get("crcy_cd", "")).strip() or "USD"
        quantity = _to_float(row.get("ccld_qty") or 0)
        amount = _to_float(row.get("tr_frcr_amt2") or row.get("tr_amt") or 0)
        exchange_rate = _to_float(row.get("erlm_exrt") or 0)
        buy_amount_native = _to_float(row.get("frcr_buy_amt_smtl") or 0)
        sell_amount_native = _to_float(row.get("frcr_sll_amt_smtl") or row.get("tr_frcr_amt2") or row.get("tr_amt") or 0)
        settlement_amount_krw = _to_float(row.get("wcrc_excc_amt") or 0)
        domestic_fee_krw = _to_float(row.get("dmst_wcrc_fee") or 0)
        overseas_fee_krw = _to_float(row.get("ovrs_wcrc_fee") or 0)
        domestic_fee_native = _to_float(row.get("dmst_fee_smtl") or row.get("dmst_frcr_fee1") or 0)
        overseas_fee_native = _to_float(row.get("ovrs_fee_smtl") or row.get("frcr_fee1") or 0)
        # The official example labels tr_frcr_amt2 as foreign-currency trade
        # amount. For display, deriving price from amount / quantity is more
        # reliable than ovrs_stck_ccld_unpr, which can come back in a different
        # unit from the user-facing filled price.
        unit_price = (amount / quantity) if quantity > 0 and amount > 0 else _to_float(row.get("ovrs_stck_ccld_unpr") or row.get("ft_ccld_unpr2") or 0)
        symbol = str(row.get("pdno", "")).strip()
        normalized.append({
            "date": trade_date,
            "market": str(row.get("ovrs_excg_cd", "")).strip() or fallback_market,
            "symbol": symbol,
            "ticker": symbol,
            "name": str(row.get("ovrs_item_name", "")).strip() or symbol,
            "side": _normalize_side(str(row.get("sll_buy_dvsn_cd") or ""), str(row.get("sll_buy_dvsn_name") or "")),
            "quantity": quantity,
            "unit_price": unit_price,
            "amount": amount,
            "currency": currency,
            "time": "",
            "realized_profit_krw": None,
            "exchange_rate": exchange_rate,
            "buy_amount_native": buy_amount_native,
            "sell_amount_native": sell_amount_native,
            "settlement_amount_krw": settlement_amount_krw,
            "domestic_fee_krw": domestic_fee_krw,
            "overseas_fee_krw": overseas_fee_krw,
            "domestic_fee_native": domestic_fee_native,
            "overseas_fee_native": overseas_fee_native,
        })
    return normalized


def get_japan_trade_history_ccnl(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "TTTS3035R",
    }
    url = f"{config.URL_BASE}/uapi/overseas-stock/v1/trading/inquire-ccnl"
    rows = []

    for exchange_code in ("TKSE", "TSE"):
        params = {
            "CANO": cano,
            "ACNT_PRDT_CD": acnt_prdt_cd,
            "PDNO": "%",
            "ORD_STRT_DT": start_date,
            "ORD_END_DT": end_date,
            "SLL_BUY_DVSN": "00",
            "CCLD_NCCS_DVSN": "01",
            "OVRS_EXCG_CD": exchange_code,
            "SORT_SQN": "DS",
            "ORD_DT": "",
            "ORD_GNO_BRNO": "",
            "ODNO": "",
            "CTX_AREA_FK200": "",
            "CTX_AREA_NK200": "",
        }
        pages = _request_with_pagination(
            url,
            headers,
            params,
            "ctx_area_fk200",
            "ctx_area_nk200",
            app_key=app_key,
            app_secret=app_secret,
        )
        exchange_rows = []
        for _, data in pages:
            page_rows = data.get("output") or data.get("output1") or []
            if isinstance(page_rows, dict):
                page_rows = [page_rows]
            exchange_rows.extend(_normalize_overseas_trade_rows(page_rows, exchange_code))
        rows.extend(exchange_rows)

    return rows


def _dedupe_trade_rows(rows: list[dict]) -> list[dict]:
    deduped = []
    seen = set()
    for row in rows:
        key = (
            row.get("date", ""),
            row.get("symbol", ""),
            row.get("side", ""),
            round(float(row.get("quantity") or 0), 8),
            round(float(row.get("unit_price") or 0), 8),
            round(float(row.get("amount") or 0), 8),
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(row)
    return deduped


def _has_japan_trade_rows(rows: list[dict]) -> bool:
    for row in rows:
        market = str(row.get("market", "")).strip().upper()
        if market in {"TSE", "TKSE", "TYO", "JPX"}:
            return True
    return False


def _normalize_trade_side_filter(side_filter: str | None) -> str:
    normalized = str(side_filter or "all").strip().lower()
    if normalized in {"buy", "매수", "02"}:
        return "buy"
    if normalized in {"sell", "매도", "01"}:
        return "sell"
    return "all"


def _normalize_trade_market_filter(market_filter: str | None) -> str:
    normalized = str(market_filter or "all").strip().lower()
    if normalized in {"domestic", "kor", "korea", "국내"}:
        return "domestic"
    if normalized in {"overseas", "global", "foreign", "해외"}:
        return "overseas"
    return "all"


def _trade_side_code(side_filter: str | None) -> str:
    normalized = _normalize_trade_side_filter(side_filter)
    if normalized == "buy":
        return "02"
    if normalized == "sell":
        return "01"
    return "00"


def _paginate_trade_rows(rows: list[dict], page: int | None, page_size: int | None) -> tuple[list[dict], dict[str, int]]:
    safe_page_size = max(1, min(int(page_size or 10), 100))
    total_items = len(rows)
    total_pages = max(1, math.ceil(total_items / safe_page_size))
    safe_page = min(max(1, int(page or 1)), total_pages)
    start_index = (safe_page - 1) * safe_page_size
    return rows[start_index:start_index + safe_page_size], {
        "page": safe_page,
        "page_size": safe_page_size,
        "total_items": total_items,
        "total_pages": total_pages,
    }


def _build_realized_profit_matchers(rows: list[dict]) -> dict:
    matchers: dict[tuple[str, str], list[dict]] = {}
    for row in rows:
        key = (row.get("date", ""), row.get("symbol", ""))
        matchers.setdefault(key, []).append(dict(row))
    return matchers


def _attach_realized_profit_to_sell_trades(trades: list[dict], domestic_pnl_rows: list[dict], overseas_pnl_rows: list[dict]) -> list[dict]:
    domestic_matchers = _build_realized_profit_matchers(domestic_pnl_rows)
    overseas_matchers = _build_realized_profit_matchers(overseas_pnl_rows)

    for trade in trades:
        if trade.get("side") != "매도":
            continue
        key = (trade.get("date", ""), trade.get("symbol", ""))
        matcher_source = domestic_matchers if trade.get("market") == "KOR" else overseas_matchers
        candidates = matcher_source.get(key, [])
        chosen_index = None
        for index, candidate in enumerate(candidates):
            qty_diff = abs(float(candidate.get("quantity") or 0) - float(trade.get("quantity") or 0))
            amt_diff = abs(float(candidate.get("amount") or 0) - float(trade.get("amount") or 0))
            if qty_diff < 0.0001 and amt_diff < max(1.0, abs(float(trade.get("amount") or 0)) * 0.01):
                chosen_index = index
                break
        if chosen_index is None and candidates:
            chosen_index = 0
        if chosen_index is not None:
            matched = candidates.pop(chosen_index)
            trade["realized_profit_krw"] = matched.get("realized_profit_krw")
            matched_rate = matched.get("realized_return_rate")
            if matched_rate is not None:
                trade["realized_return_rate"] = float(matched_rate)
            else:
                buy_amount = float(matched.get("buy_amount_krw") or 0)
                trade["realized_return_rate"] = ((float(trade["realized_profit_krw"]) / buy_amount) * 100) if buy_amount > 0 else None
    return trades


def get_domestic_trade_history(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str, side_filter: str | None = None):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
    }
    url = f"{config.URL_BASE}/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
    start_dt = datetime.datetime.strptime(start_date, "%Y%m%d").date()
    today_kst = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date()
    pd_dv = "before" if (today_kst - start_dt).days > 92 else "inner"
    headers["tr_id"] = "CTSC9215R" if pd_dv == "before" else "TTTC0081R"

    params = {
        "CANO": cano,
        "ACNT_PRDT_CD": acnt_prdt_cd,
        "INQR_STRT_DT": start_date,
        "INQR_END_DT": end_date,
        "SLL_BUY_DVSN_CD": _trade_side_code(side_filter),
        "PDNO": "",
        "CCLD_DVSN": "01",
        "INQR_DVSN": "00",
        "INQR_DVSN_3": "00",
        "ORD_GNO_BRNO": "",
        "ODNO": "",
        "INQR_DVSN_1": "",
        "CTX_AREA_FK100": "",
        "CTX_AREA_NK100": "",
        "EXCG_ID_DVSN_CD": "ALL",
    }
    pages = _request_with_pagination(url, headers, params, "ctx_area_fk100", "ctx_area_nk100", app_key=app_key, app_secret=app_secret)
    rows = []
    for _, data in pages:
        rows.extend(_normalize_domestic_trade_rows(data.get("output1") or []))
    return rows


def get_overseas_trade_history(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str, side_filter: str | None = None):
    exchange_queries = ["NAS", "NYS", "AMS", "TSE", "TKSE", "HKS", "SHS", "SZS", "HSX", "HNX"]
    url = f"{config.URL_BASE}/uapi/overseas-stock/v1/trading/inquire-period-trans"
    rows = []

    def fetch_exchange(exchange_code: str):
        headers = {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {token}",
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": "CTOS4001R",
        }
        params = {
            "CANO": cano,
            "ACNT_PRDT_CD": acnt_prdt_cd,
            "ERLM_STRT_DT": start_date,
            "ERLM_END_DT": end_date,
            "OVRS_EXCG_CD": exchange_code,
            "PDNO": "",
            "SLL_BUY_DVSN_CD": _trade_side_code(side_filter),
            "LOAN_DVSN_CD": "",
            "CTX_AREA_FK100": "",
            "CTX_AREA_NK100": "",
        }
        pages = _request_with_pagination(url, headers, params, "ctx_area_fk100", "ctx_area_nk100", app_key=app_key, app_secret=app_secret)
        exchange_rows = []
        for _, data in pages:
            page_rows = data.get("output1") or []
            if isinstance(page_rows, dict):
                page_rows = [page_rows]
            exchange_rows.extend(_normalize_overseas_trade_rows(page_rows, exchange_code))
        return exchange_rows

    with ThreadPoolExecutor(max_workers=min(len(exchange_queries), _MAX_PARALLEL_WORKERS)) as executor:
        for exchange_rows in executor.map(fetch_exchange, exchange_queries):
            rows.extend(exchange_rows)
    return rows


def get_trade_history(
    token,
    app_key,
    app_secret,
    cano,
    acnt_prdt_cd,
    start_date: str,
    end_date: str,
    *,
    side_filter: str | None = None,
    market_filter: str | None = None,
    page: int | None = None,
    page_size: int | None = None,
):
    normalized_side = _normalize_trade_side_filter(side_filter)
    normalized_market = _normalize_trade_market_filter(market_filter)
    safe_page = max(1, int(page or 1))
    safe_page_size = max(1, min(int(page_size or 10), 100))
    cache_key = (
        "trade_history",
        cano,
        acnt_prdt_cd,
        start_date,
        end_date,
        normalized_side,
        normalized_market,
        safe_page,
        safe_page_size,
    )
    cached = _get_cached_payload(cache_key)
    if cached is not None:
        return cached

    task_map: dict[str, tuple[object, tuple[object, ...]]] = {
        "pnl_rows": (_fetch_trade_profit_rows, (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)),
    }
    if normalized_market in {"all", "domestic"}:
        task_map["domestic_trades"] = (
            get_domestic_trade_history,
            (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date, normalized_side),
        )
    if normalized_market in {"all", "overseas"}:
        task_map["overseas_trades"] = (
            get_overseas_trade_history,
            (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date, normalized_side),
        )

    results = _run_parallel_tasks(task_map)
    domestic_trade_result = results.get("domestic_trades")
    overseas_trade_result = results.get("overseas_trades")
    pnl_result = results.get("pnl_rows")
    domestic_rows: list[dict] = domestic_trade_result if isinstance(domestic_trade_result, list) else []
    overseas_rows: list[dict] = overseas_trade_result if isinstance(overseas_trade_result, list) else []
    pnl_rows: dict[str, list[dict]] = pnl_result if isinstance(pnl_result, dict) else {"domestic": [], "overseas": []}
    japan_ccnl_rows: list[dict] = []
    if normalized_market in {"all", "overseas"} and not _has_japan_trade_rows(overseas_rows):
        japan_ccnl_result = get_japan_trade_history_ccnl(
            token,
            app_key,
            app_secret,
            cano,
            acnt_prdt_cd,
            start_date,
            end_date,
        )
        japan_ccnl_rows = japan_ccnl_result if isinstance(japan_ccnl_result, list) else []
    if japan_ccnl_rows:
        overseas_rows = _dedupe_trade_rows(overseas_rows + japan_ccnl_rows)
    domestic_pnl_rows = pnl_rows["domestic"] if isinstance(pnl_rows.get("domestic"), list) else []
    overseas_pnl_rows = pnl_rows["overseas"] if isinstance(pnl_rows.get("overseas"), list) else []
    all_rows = _dedupe_trade_rows(domestic_rows + overseas_rows)
    all_rows = _attach_realized_profit_to_sell_trades(all_rows, domestic_pnl_rows, overseas_pnl_rows)
    if normalized_side == "buy":
        all_rows = [row for row in all_rows if str(row.get("side", "")).strip() == "매수"]
    elif normalized_side == "sell":
        all_rows = [row for row in all_rows if str(row.get("side", "")).strip() == "매도"]
    all_rows.sort(key=lambda row: f"{row.get('date', '')}{row.get('time', '')}", reverse=True)
    page_rows, pagination = _paginate_trade_rows(all_rows, safe_page, safe_page_size)
    summary_payload = _build_realized_profit_summary_payload(pnl_rows)
    result = {
        "items": page_rows,
        "summary": summary_payload.get("summary", {}),
        "daily": summary_payload.get("daily", []),
        "pagination": pagination,
        "filters": {
            "side": normalized_side,
            "market": normalized_market,
        },
    }
    _set_cached_payload(cache_key, result)
    return result
