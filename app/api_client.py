from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import requests
import json
from app import config
import time
import logging
import datetime

_cached_token = None
_token_issue_time = 0
_token_expires_at = 0
_realized_cache = {}
_MAX_PARALLEL_WORKERS = 4
logger = logging.getLogger(__name__)


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

    if _is_kor_regular_session():
        return regular_price or after_price or fallback_price
    return after_price or regular_price or fallback_price


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
        res = requests.get(url, headers=headers, params=params, timeout=5)
        if res.status_code != 200:
            return None
        data = res.json()
        out = data.get("output") or {}
        if not out:
            return None
        return out
    except Exception:
        return None


def get_domestic_quote_price(token, app_key, app_secret, ticker: str) -> int | None:
    # Market code: J=KRX, NX=NXT, UN=통합.
    # For after-hours, prefer NXT/통합 first.
    market_order = ["J", "UN", "NX"] if _is_kor_regular_session() else ["NX", "UN", "J"]
    for code in market_order:
        out = _get_domestic_quote_output(token, app_key, app_secret, ticker, code)
        if not out:
            continue
        price = _pick_domestic_display_price(out, 0)
        if price > 0:
            return price
    return None

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
        res = requests.get(url, headers=headers, params=params, timeout=8)
        if res.status_code != 200:
            return 0, "psbl_api_http_error"
        data = res.json()
        out = data.get("output") or {}
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

def _invalidate_access_token():
    global _cached_token, _token_issue_time, _token_expires_at
    _cached_token = None
    _token_issue_time = 0
    _token_expires_at = 0


def get_access_token(app_key, app_secret):
    global _cached_token, _token_issue_time, _token_expires_at
    
    # 1遺??쒗븳 ?곴뎄 釉붾씫 諛⑹?: ?좏겙 諛쒓툒 ?쒕룄 ??60珥??대궡硫??ъ떆??湲덉? (?? 罹먯떆???좏겙???덉쑝硫?諛섑솚)
    now = time.time()
    if _cached_token and now < max(_token_expires_at - 60, _token_issue_time):
        return _cached_token
        
    # 理쒓렐???ㅽ뙣?덈떎硫?理쒖냼 65珥??湲?
    if not _cached_token and _token_issue_time > 0 and (now - _token_issue_time) < 65:
        return None

    headers = {"content-type": "application/json"}
    body = {
        "grant_type": "client_credentials",
        "appkey": app_key,
        "appsecret": app_secret
    }
    url = f"{config.URL_BASE}/oauth2/tokenP"
    res = requests.post(url, headers=headers, data=json.dumps(body))
    
    _token_issue_time = now # ?ㅽ뙣???깃났?대뱺 留덉?留??쒕룄 ?쒓컙 媛깆떊
    
    if res.status_code == 200:
        body = res.json()
        _cached_token = body.get("access_token")
        expires_in = _to_int(body.get("expires_in") or 0)
        _token_expires_at = now + expires_in if expires_in > 0 else now + 43200
        return _cached_token

    return None


def _is_token_error_response(res, data: dict) -> bool:
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
            _invalidate_access_token()
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
    res = requests.get(url, headers=headers, params=params)
    
    result = {"summary": {}, "items": []}
    if res.status_code == 200:
        data = res.json()
        output2_rows = data.get("output2") or []
        if len(output2_rows) > 0:
            summary = output2_rows[0]
            # Preferred: dedicated KRW orderable endpoint.
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
        for item in (data.get("output1") or []):
                ticker = item.get("pdno", "")
                # inquire-balance already includes current price fields, so avoid
                # issuing one extra quote request per holding on every sync.
                fallback_now = int(_to_float(item.get("prpr", "0")))
                now_price = fallback_now
                if now_price <= 0:
                    quoted_now = get_domestic_quote_price(
                        token, app_key, app_secret, ticker
                    )
                    now_price = quoted_now if (quoted_now and quoted_now > 0) else fallback_now
                result["items"].append({
                    "name": item.get("prdt_name", "알수없음"),
                    "ticker": ticker,
                    "qty": _to_int(item.get("hldg_qty", "0")),
                    "avg_price": int(_to_float(item.get("pchs_avg_pric", "0"))),
                    "now_price": now_price,
                    "profit_rt": (
                        ((now_price - _to_float(item.get("pchs_avg_pric", "0"))) / _to_float(item.get("pchs_avg_pric", "0")) * 100)
                        if _to_float(item.get("pchs_avg_pric", "0")) > 0 else 0.0
                    )
                })
    return result

def get_overseas_balance(token, app_key, app_secret, cano, acnt_prdt_cd):
    headers = {
        "content-type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "appkey": app_key,
        "appsecret": app_secret,
        "tr_id": "CTRP6504R", 
    }
    url = f"{config.URL_BASE}/uapi/overseas-stock/v1/trading/inquire-present-balance"
    
    result = {"us_summary": {}, "us_items": [], "jp_items": []}

    def _get_overseas_orderable_usd():
        headers_ps = {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {token}",
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": "TTTS3007R",
        }
        url_ps = f"{config.URL_BASE}/uapi/overseas-stock/v1/trading/inquire-psamount"
        # OVRS_ORD_UNPR accepts integer-like string (e.g. "1") and still returns account-level buying power.
        tries = [
            ("NASD", "QQQ", "1"),
            ("NYSE", "KO", "1"),
        ]
        for exch, item, unpr in tries:
            params_ps = {
                "CANO": cano,
                "ACNT_PRDT_CD": acnt_prdt_cd,
                "OVRS_EXCG_CD": exch,
                "OVRS_ORD_UNPR": unpr,
                "ITEM_CD": item,
            }
            try:
                rr = requests.get(url_ps, headers=headers_ps, params=params_ps, timeout=8)
                if rr.status_code != 200:
                    continue
                body = rr.json()
                if str(body.get("rt_cd")) != "0":
                    continue
                out = body.get("output") or {}
                ovrs_amt = _to_float(out.get("ovrs_ord_psbl_amt"))
                if ovrs_amt > 0:
                    return ovrs_amt, "inquire-psamount.ovrs_ord_psbl_amt"
                ord_psbl = _to_float(out.get("ord_psbl_frcr_amt"))
                if ord_psbl > 0:
                    return ord_psbl, "inquire-psamount.ord_psbl_frcr_amt"
            except Exception:
                continue
        return 0.0, "none"
    
    # 1. ?명솕 湲곗? (02) 濡?醫낅ぉ ?뺣낫 媛?몄삤湲?
    params_us_foreign = {
        "CANO": cano,
        "ACNT_PRDT_CD": acnt_prdt_cd,
        "WCRC_FRCR_DVSN_CD": "02", # ?명솕
        "NATN_CD": "840",
        "TR_MKET_CD": "00",
        "INQR_DVSN_CD": "00",
        "CTX_AREA_FK200": "",
        "CTX_AREA_NK200": ""
    }
    
    while True:
        res = requests.get(url, headers=headers, params=params_us_foreign)
        if res.status_code == 200:
            data = res.json()
            if data.get("rt_cd") == "0":
                if "output1" in data:
                    for item in data["output1"]:
                        avg_unpr3 = _to_float(item.get("avg_unpr3", "0"))
                        ovrs_now_pric1 = _to_float(item.get("ovrs_now_pric1", "0"))
                        bass_exrt = _to_float(item.get("bass_exrt", "1"))
                        if bass_exrt == 0: bass_exrt = 1

                        result["us_items"].append({
                            "name": item.get("prdt_name", "?뚯닔?놁쓬"),
                            "ticker": item.get("pdno", ""),
                            "excg_cd": item.get("ovrs_excg_cd", "NASD"),
                            "qty": _to_float(item.get("ccld_qty_smtl1", "0")),
                            "profit_rt": ((ovrs_now_pric1 - avg_unpr3) / avg_unpr3 * 100) if avg_unpr3 > 0 else 0.0,
                            "avg_price": avg_unpr3,
                            "now_price": ovrs_now_pric1,
                            "bass_exrt": bass_exrt
                        })
                
                if "output3" in data and not result["us_summary"]:
                    out3 = data["output3"]
                    
                    usd_cash = 0.0
                    usd_exrt = 0.0
                    usd_cash_key = "none"
                    if "output2" in data:
                        usd_cash, usd_exrt, usd_cash_key = _pick_usd_orderable_from_output2(data["output2"])
                    if usd_cash <= 0:
                        fallback_cash, fallback_key = _pick_usd_orderable_from_output3(out3)
                        if fallback_cash > 0:
                            usd_cash = fallback_cash
                            usd_cash_key = fallback_key

                    ps_cash, ps_key = _get_overseas_orderable_usd()
                    if ps_cash > 0:
                        usd_cash = ps_cash
                        usd_cash_key = ps_key

                    logger.info(
                        "Overseas USD cash selected: key=%s, value=%s",
                        usd_cash_key,
                        usd_cash,
                    )
                    
                    result["us_summary"] = {
                        "krw_purchase_amt": _to_float(out3.get("pchs_amt_smtl_amt", 0)),
                        "krw_eval_amt": _to_float(out3.get("evlu_amt_smtl_amt", 0)),
                        "usd_cash_balance": usd_cash,
                        "usd_exrt": usd_exrt,
                    }
                
                tr_cont = res.headers.get("tr_cont", "")
                if tr_cont in ["F", "M"]:
                    params_us_foreign["CTX_AREA_FK200"] = data.get("ctx_area_fk200", "")
                    params_us_foreign["CTX_AREA_NK200"] = data.get("ctx_area_nk200", "")
                else:
                    break
            else:
                break
        else:
            break

    # 2. ?쇰낯 二쇱떇 ?명솕 湲곗? (392)
    params_jp_foreign = params_us_foreign.copy()
    params_jp_foreign["NATN_CD"] = "392"
    params_jp_foreign["CTX_AREA_FK200"] = ""
    params_jp_foreign["CTX_AREA_NK200"] = ""
    
    res = requests.get(url, headers=headers, params=params_jp_foreign)
    if res.status_code == 200:
        data = res.json()
        if data.get("rt_cd") == "0" and "output1" in data:
            for item in data["output1"]:
                avg_unpr3 = _to_float(item.get("avg_unpr3", "0"))
                ovrs_now_pric1 = _to_float(item.get("ovrs_now_pric1", "0"))
                bass_exrt = _to_float(item.get("bass_exrt", "1"))
                if bass_exrt == 0: bass_exrt = 1

                result["jp_items"].append({
                    "name": item.get("prdt_name", "?뚯닔?놁쓬"),
                    "ticker": item.get("pdno", ""),
                    "excg_cd": item.get("ovrs_excg_cd", "TKSE"),
                    "qty": _to_float(item.get("ccld_qty_smtl1", "0")),
                    "profit_rt": ((ovrs_now_pric1 - avg_unpr3) / avg_unpr3 * 100) if avg_unpr3 > 0 else 0.0,
                    "avg_price": avg_unpr3, 
                    "now_price": ovrs_now_pric1,
                    "bass_exrt": bass_exrt
                })

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


def _get_cached_payload(cache_key: tuple, ttl_seconds: int = 20):
    cached = _realized_cache.get(cache_key)
    if cached and (time.time() - cached["ts"]) < ttl_seconds:
        return cached["data"]
    return None


def _set_cached_payload(cache_key: tuple, data):
    _realized_cache[cache_key] = {"ts": time.time(), "data": data}


def _fetch_trade_profit_rows(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    cache_key = ("trade_profit_rows", cano, acnt_prdt_cd, start_date, end_date)
    cached = _get_cached_payload(cache_key)
    if cached is not None:
        return cached

    results = _run_parallel_tasks({
        "domestic": (get_domestic_realized_trade_profit, (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)),
        "overseas": (get_overseas_realized_trade_profit, (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)),
    })
    payload = {
        "domestic": results.get("domestic", []),
        "overseas": results.get("overseas", []),
    }
    _set_cached_payload(cache_key, payload)
    return payload


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


def _normalize_side(code: str = "", label: str = "") -> str:
    label = str(label or "").strip()
    code = str(code or "").strip()
    if "매도" in label or code == "01":
        return "매도"
    if "매수" in label or code == "02":
        return "매수"
    return label or code or "-"


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
            "overseas_realized_profit_krw": _to_float(row.get("ovrs_rlzt_pfls_amt", 0)),
            "overseas_fee_krw": _to_float(row.get("stck_sll_tlex", 0)),
            "overseas_realized_profit_native": _to_float(row.get("ovrs_rlzt_pfls_amt", 0)),
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
        symbol = str(row.get("ovrs_pdno", "")).strip()
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

    for exchange_code, currency_code in exchange_queries:
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
            "NATN_CD": "",
            "CRCY_CD": currency_code,
            "PDNO": "",
            "INQR_STRT_DT": start_date,
            "INQR_END_DT": end_date,
            "WCRC_FRCR_DVSN_CD": "02",
            "CTX_AREA_FK200": "",
            "CTX_AREA_NK200": "",
        }
        pages = _request_with_pagination(url, headers, params, "ctx_area_fk200", "ctx_area_nk200", app_key=app_key, app_secret=app_secret)
        for _, data in pages:
            page_rows = data.get("output1") or []
            if isinstance(page_rows, dict):
                page_rows = [page_rows]
            rows.extend(_normalize_overseas_realized_rows(page_rows))

    return rows


def get_realized_profit_summary(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    cache_key = ("realized_summary", cano, acnt_prdt_cd, start_date, end_date)
    cached = _get_cached_payload(cache_key)
    if cached is not None:
        return cached

    trade_profit_rows = _fetch_trade_profit_rows(token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)
    domestic_rows = trade_profit_rows["domestic"]
    overseas_rows = trade_profit_rows["overseas"]

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

    result = {
        "summary": {
            "domestic_realized_profit_krw": domestic_total,
            "overseas_realized_profit_krw": overseas_total,
            "total_realized_profit_krw": domestic_total + overseas_total,
            "total_realized_return_rate": total_realized_return_rate,
            "trade_days": len(daily_rows),
        },
        "daily": daily_rows,
    }
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
            "NATN_CD": "",
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
            exchange_rows.extend(_normalize_overseas_realized_trade_rows(page_rows))
        return exchange_rows

    with ThreadPoolExecutor(max_workers=min(len(exchange_queries), _MAX_PARALLEL_WORKERS)) as executor:
        for exchange_rows in executor.map(lambda item: fetch_exchange(*item), exchange_queries):
            rows.extend(exchange_rows)
    return rows


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
            "side": _normalize_side(row.get("sll_buy_dvsn_cd"), row.get("sll_buy_dvsn_cd_name")),
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
            "side": _normalize_side(row.get("sll_buy_dvsn_cd"), row.get("sll_buy_dvsn_name")),
            "quantity": quantity,
            "unit_price": unit_price,
            "amount": amount,
            "currency": currency,
            "time": "",
            "realized_profit_krw": None,
        })
    return normalized


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


def get_domestic_trade_history(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
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
        "SLL_BUY_DVSN_CD": "00",
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


def get_overseas_trade_history(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    exchange_queries = ["NAS", "NYS", "AMS", "TSE", "HKS", "SHS", "SZS", "HSX", "HNX"]
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
            "SLL_BUY_DVSN_CD": "00",
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


def get_trade_history(token, app_key, app_secret, cano, acnt_prdt_cd, start_date: str, end_date: str):
    cache_key = ("trade_history", cano, acnt_prdt_cd, start_date, end_date)
    cached = _get_cached_payload(cache_key)
    if cached is not None:
        return cached

    results = _run_parallel_tasks({
        "domestic_trades": (get_domestic_trade_history, (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)),
        "overseas_trades": (get_overseas_trade_history, (token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)),
    })
    pnl_rows = _fetch_trade_profit_rows(token, app_key, app_secret, cano, acnt_prdt_cd, start_date, end_date)
    domestic_rows = results.get("domestic_trades", [])
    overseas_rows = results.get("overseas_trades", [])
    domestic_pnl_rows = pnl_rows["domestic"]
    overseas_pnl_rows = pnl_rows["overseas"]
    all_rows = _dedupe_trade_rows(domestic_rows + overseas_rows)
    all_rows = _attach_realized_profit_to_sell_trades(all_rows, domestic_pnl_rows, overseas_pnl_rows)
    all_rows.sort(key=lambda row: f"{row.get('date', '')}{row.get('time', '')}", reverse=True)
    result = {"items": all_rows}
    _set_cached_payload(cache_key, result)
    return result
