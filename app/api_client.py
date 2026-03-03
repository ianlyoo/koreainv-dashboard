import requests
import json
from app import config
import time
import logging

_cached_token = None
_token_issue_time = 0
logger = logging.getLogger(__name__)


def _get_ci(row: dict, wanted_key: str):
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

def get_access_token(app_key, app_secret):
    global _cached_token, _token_issue_time
    
    # 1분 제한 영구 블락 방지: 토큰 발급 시도 후 60초 이내면 재시도 금지 (단, 캐시된 토큰이 있으면 반환)
    now = time.time()
    if _cached_token and (now - _token_issue_time) < 43200:
        return _cached_token
        
    # 최근에 실패했다면 최소 65초 대기
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
    
    _token_issue_time = now # 실패든 성공이든 마지막 시도 시간 갱신
    
    if res.status_code == 200:
        _cached_token = res.json().get("access_token")
        return _cached_token
        
    return None

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
                result["items"].append({
                    "name": item.get("prdt_name", "알수없음"),
                    "ticker": item.get("pdno", ""),
                    "qty": _to_int(item.get("hldg_qty", "0")),
                    "avg_price": int(_to_float(item.get("pchs_avg_pric", "0"))),
                    "now_price": int(_to_float(item.get("prpr", "0"))),
                    "profit_rt": (
                        ((_to_float(item.get("prpr", "0")) - _to_float(item.get("pchs_avg_pric", "0"))) / _to_float(item.get("pchs_avg_pric", "0")) * 100)
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
    
    # 1. 외화 기준 (02) 로 종목 정보 가져오기
    params_us_foreign = {
        "CANO": cano,
        "ACNT_PRDT_CD": acnt_prdt_cd,
        "WCRC_FRCR_DVSN_CD": "02", # 외화
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
                            "name": item.get("prdt_name", "알수없음"),
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
                    if "output2" in data:
                        for row in data["output2"]:
                            crcy = str(row.get("crcy_cd", "")).strip().upper()
                            if crcy == "USD":
                                # Strict: use orderable USD cash only.
                                usd_cash = _pick_orderable_value(row, prefer_usd=True)
                                usd_exrt = _to_float(
                                    row.get("bass_exrt")
                                    or row.get("frst_bltn_exrt")
                                    or 0.0
                                )
                                break
                    
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

    # 2. 일본 주식 외화 기준 (392)
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
                    "name": item.get("prdt_name", "알수없음"),
                    "ticker": item.get("pdno", ""),
                    "excg_cd": item.get("ovrs_excg_cd", "TKSE"),
                    "qty": _to_float(item.get("ccld_qty_smtl1", "0")),
                    "profit_rt": ((ovrs_now_pric1 - avg_unpr3) / avg_unpr3 * 100) if avg_unpr3 > 0 else 0.0,
                    "avg_price": avg_unpr3, 
                    "now_price": ovrs_now_pric1,
                    "bass_exrt": bass_exrt
                })

    # 3. (삭제됨) 원화 총합계는 1번 호출의 output3에서 가져옴
                    
    return result
