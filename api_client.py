import requests
import json
import config
import time

_cached_token = None
_token_issue_time = 0

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
        if "output2" in data and len(data["output2"]) > 0:
            summary = data["output2"][0]
            result["summary"] = {
                "total_purchase_amt": int(summary.get('pchs_amt_smtl_amt', 0)),
                "total_eval_amt": int(summary.get('evlu_amt_smtl_amt', 0)),
                "total_profit_loss": int(summary.get('evlu_pfls_smtl_amt', 0)),
                "cash_balance": int(summary.get('dnca_tot_amt', 0))
            }
        if "output1" in data:
            for item in data["output1"]:
                result["items"].append({
                    "name": item.get("prdt_name", "알수없음"),
                    "ticker": item.get("pdno", ""),
                    "qty": int(item.get("hldg_qty", "0")),
                    "avg_price": int(float(item.get("pchs_avg_pric", "0"))),
                    "now_price": int(float(item.get("prpr", "0"))),
                    "profit_rt": (
                        ((float(item.get("prpr", "0")) - float(item.get("pchs_avg_pric", "0"))) / float(item.get("pchs_avg_pric", "0")) * 100)
                        if float(item.get("pchs_avg_pric", "0")) > 0 else 0.0
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
                        avg_unpr3 = float(item.get("avg_unpr3", "0"))
                        ovrs_now_pric1 = float(item.get("ovrs_now_pric1", "0"))
                        bass_exrt = float(item.get("bass_exrt", "1"))
                        if bass_exrt == 0: bass_exrt = 1

                        result["us_items"].append({
                            "name": item.get("prdt_name", "알수없음"),
                            "ticker": item.get("pdno", ""),
                            "excg_cd": item.get("ovrs_excg_cd", "NASD"),
                            "qty": float(item.get("ccld_qty_smtl1", "0")),
                            "profit_rt": ((ovrs_now_pric1 - avg_unpr3) / avg_unpr3 * 100) if avg_unpr3 > 0 else 0.0,
                            "avg_price": avg_unpr3,
                            "now_price": ovrs_now_pric1,
                            "bass_exrt": bass_exrt
                        })
                
                if "output3" in data and not result["us_summary"]:
                    out3 = data["output3"]
                    
                    usd_cash = 0.0
                    if "output2" in data:
                        for row in data["output2"]:
                            if row.get("crcy_cd") == "USD":
                                usd_cash = float(row.get("frcr_drwg_psbl_amt_1", 0.0))
                                break
                    
                    result["us_summary"] = {
                        "krw_purchase_amt": float(out3.get("pchs_amt_smtl_amt", 0)),
                        "krw_eval_amt": float(out3.get("evlu_amt_smtl_amt", 0)),
                        "usd_cash_balance": usd_cash
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
                avg_unpr3 = float(item.get("avg_unpr3", "0"))
                ovrs_now_pric1 = float(item.get("ovrs_now_pric1", "0"))
                bass_exrt = float(item.get("bass_exrt", "1"))
                if bass_exrt == 0: bass_exrt = 1

                result["jp_items"].append({
                    "name": item.get("prdt_name", "알수없음"),
                    "ticker": item.get("pdno", ""),
                    "excg_cd": item.get("ovrs_excg_cd", "TKSE"),
                    "qty": float(item.get("ccld_qty_smtl1", "0")),
                    "profit_rt": ((ovrs_now_pric1 - avg_unpr3) / avg_unpr3 * 100) if avg_unpr3 > 0 else 0.0,
                    "avg_price": avg_unpr3, 
                    "now_price": ovrs_now_pric1,
                    "bass_exrt": bass_exrt
                })

    # 3. (삭제됨) 원화 총합계는 1번 호출의 output3에서 가져옴
                    
    return result
