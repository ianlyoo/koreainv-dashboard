from fastapi import FastAPI, HTTPException, Request, Response, Depends, Form
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from app import api_client
import yfinance as yf
import datetime
import logging
import cloudscraper
from bs4 import BeautifulSoup
from app import auth
import os
import uuid
from dataclasses import dataclass
from app import runtime_paths

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s: %(message)s')

BASE_DIR = runtime_paths.get_app_base_dir()

app = FastAPI(title="Korea Investment Dashboard")

# In-memory session store: maps session_id to decrypted credentials (PIN is never stored)
@dataclass
class SessionData:
    app_key: str
    app_secret: str
    cano: str
    acnt_prdt_cd: str

active_sessions = {}
COOKIE_SECURE = os.getenv("COOKIE_SECURE", "false").lower() == "true"

# Serve static files
app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")

if os.path.isdir(os.path.join(BASE_DIR, "img")):
    app.mount("/img", StaticFiles(directory=os.path.join(BASE_DIR, "img")), name="img")

# Helper to check if logged in
def check_auth(request: Request):
    session_id = request.cookies.get("session")
    if not session_id or session_id not in active_sessions:
        raise HTTPException(status_code=401, detail="Unauthorized")

def _set_session_cookie(response: Response, session_id: str):
    response.set_cookie(
        key="session",
        value=session_id,
        httponly=True,
        samesite="lax",
        secure=COOKIE_SECURE,
        max_age=60 * 60 * 8,
    )

def _decrypt_credentials(settings: dict, pin: str):
    crypto_version = settings.get("crypto_version", 1)
    salt = settings.get("kdf_salt")

    if crypto_version >= 2 and salt:
        app_key = auth.decrypt_data_v2(settings.get("api_key_enc", ""), pin, salt)
        app_secret = auth.decrypt_data_v2(settings.get("api_secret_enc", ""), pin, salt)
        cano = auth.decrypt_data_v2(settings.get("cano_enc", ""), pin, salt)
        acnt_prdt_cd = auth.decrypt_data_v2(settings.get("acnt_prdt_cd_enc", ""), pin, salt)
    else:
        # Backward compatibility for existing encrypted settings
        app_key = auth.decrypt_data(settings.get("api_key_enc", ""), pin)
        app_secret = auth.decrypt_data(settings.get("api_secret_enc", ""), pin)
        cano = auth.decrypt_data(settings.get("cano_enc", ""), pin)
        acnt_prdt_cd = auth.decrypt_data(settings.get("acnt_prdt_cd_enc", ""), pin)

    return app_key, app_secret, cano, acnt_prdt_cd

@app.get("/", response_class=HTMLResponse)
async def read_root(request: Request):
    if not auth.is_setup_complete():
        return RedirectResponse(url="/login")
        
    session_id = request.cookies.get("session")
    if not session_id or session_id not in active_sessions:
        return RedirectResponse(url="/login")
        
    with open(os.path.join(BASE_DIR, "templates", "index.html"), "r", encoding="utf-8") as f:
        return f.read()

@app.get("/login", response_class=HTMLResponse)
async def read_login():
    try:
        with open(os.path.join(BASE_DIR, "templates", "login.html"), "r", encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return HTMLResponse(content="<h1>Login Page Needs to be created</h1>", status_code=404)

@app.get("/api/status")
async def get_status():
    return {"status": "success", "setup_complete": auth.is_setup_complete()}

@app.post("/api/setup")
async def setup_api(
    app_key: str = Form(...),
    app_secret: str = Form(...),
    cano: str = Form(...),
    acnt_prdt_cd: str = Form("01"),
    pin: str = Form(...)
):
    try:
        if auth.is_setup_complete():
            raise HTTPException(status_code=400, detail="Setup already complete")
            
        print(f"DEBUG: app_key len={len(app_key)}, app_secret len={len(app_secret)}, cano len={len(cano)}, pin len={len(pin)}")
            
        kdf_salt = auth.generate_kdf_salt()
        settings = {
            "setup_complete": True,
            "crypto_version": 2,
            "kdf_salt": kdf_salt,
            "api_key_enc": auth.encrypt_data_v2(app_key, pin, kdf_salt),
            "api_secret_enc": auth.encrypt_data_v2(app_secret, pin, kdf_salt),
            "cano_enc": auth.encrypt_data_v2(cano, pin, kdf_salt),
            "acnt_prdt_cd_enc": auth.encrypt_data_v2(acnt_prdt_cd, pin, kdf_salt),
            "pin_hash": auth.hash_pin(pin),
        }
        
        if auth.save_settings(settings):
            dec_app_key, dec_app_secret, dec_cano, dec_acnt_prdt_cd = _decrypt_credentials(settings, pin)
            if not dec_app_key or not dec_app_secret or not dec_cano:
                raise HTTPException(status_code=500, detail="Credential validation failed after setup")
            session_id = str(uuid.uuid4())
            active_sessions[session_id] = SessionData(
                app_key=dec_app_key,
                app_secret=dec_app_secret,
                cano=dec_cano,
                acnt_prdt_cd=dec_acnt_prdt_cd or "01",
            )
            response = JSONResponse({"status": "success", "message": "Setup successful"})
            _set_session_cookie(response, session_id)
            return response
        else:
            raise HTTPException(status_code=500, detail="Failed to save settings")
    except Exception as e:
        print(f"Setup error: {e}")
        return JSONResponse(status_code=500, content={"status": "error", "detail": f"Internal Server Error: {str(e)}"})

@app.post("/api/login")
async def login(pin: str = Form(...)):
    settings = auth.load_settings()
    if not settings.get("setup_complete"):
        raise HTTPException(status_code=400, detail="Setup not complete")
        
    pin_hash = settings.get("pin_hash")
    if not auth.verify_pin(pin, pin_hash):
        raise HTTPException(status_code=401, detail="Invalid PIN")

    app_key, app_secret, cano, acnt_prdt_cd = _decrypt_credentials(settings, pin)
    if not app_key or not app_secret or not cano:
        raise HTTPException(status_code=401, detail="Failed to decrypt credentials. Invalid PIN or corrupted settings.")
        
    session_id = str(uuid.uuid4())
    active_sessions[session_id] = SessionData(
        app_key=app_key,
        app_secret=app_secret,
        cano=cano,
        acnt_prdt_cd=acnt_prdt_cd or "01",
    )
    response = JSONResponse({"status": "success", "message": "Login successful"})
    _set_session_cookie(response, session_id)
    return response

@app.post("/api/logout")
async def logout(request: Request):
    session_id = request.cookies.get("session")
    if session_id in active_sessions:
        del active_sessions[session_id]
        
    response = JSONResponse({"status": "success", "message": "Logged out"})
    response.delete_cookie("session")
    return response

@app.post("/api/reset")
async def reset_settings(request: Request):
    # Require authentication to reset settings
    session_id = request.cookies.get("session")
    if not session_id or session_id not in active_sessions:
        raise HTTPException(status_code=401, detail="Unauthorized")

    if auth.delete_settings():
        # Clear all sessions after reset
        active_sessions.clear()
        
        response = JSONResponse({"status": "success", "message": "Settings reset"})
        response.delete_cookie("session")
        return response
    else:
        raise HTTPException(status_code=500, detail="Failed to reset settings")

@app.get("/api/sync")
async def sync_data(request: Request):
    session_id = request.cookies.get("session")
    session = active_sessions.get(session_id)
    if not session:
        raise HTTPException(status_code=401, detail="Unauthorized")
    try:
        token = api_client.get_access_token(session.app_key, session.app_secret)
        if not token:
            raise HTTPException(status_code=500, detail="Failed to get access token from API")

        domestic = api_client.get_domestic_balance(
            token, session.app_key, session.app_secret, session.cano, session.acnt_prdt_cd
        )
        overseas = api_client.get_overseas_balance(
            token, session.app_key, session.app_secret, session.cano, session.acnt_prdt_cd
        )

        return {
            "status": "success",
            "domestic": domestic,
            "overseas": overseas
        }
    except HTTPException:
        raise
    except Exception:
        logging.exception("sync_data failed")
        raise HTTPException(status_code=500, detail="sync_data_failed")



@app.get("/api/stock-search")
async def stock_search(request: Request, q: str = ""):
    """Search for stocks by name or ticker."""
    session_id = request.cookies.get("session")
    if not session_id or session_id not in active_sessions:
        raise HTTPException(status_code=401, detail="Unauthorized")
    
    if not q or len(q) < 1:
        return {"status": "success", "data": []}
    
    results = []
    raw_query = q.strip()
    query_upper = raw_query.upper()
    
    try:
        # Search using yfinance
        import yfinance as yf
        tickers_to_try = []
        
        # Direct ticker match
        tickers_to_try.append(query_upper)
        # Korean stock (6 digits)
        if raw_query.isdigit() and len(raw_query) <= 6:
            tickers_to_try.append(raw_query.zfill(6) + ".KS")
            tickers_to_try.append(raw_query.zfill(6) + ".KQ")
        
        for t in tickers_to_try:
            try:
                info = yf.Ticker(t).info
                if info and info.get("shortName"):
                    market = "USA"
                    ticker = t
                    if t.endswith(".KS") or t.endswith(".KQ"):
                        market = "KOR"
                        ticker = t
                    elif t.endswith(".T"):
                        market = "JPN"
                    results.append({
                        "ticker": ticker,
                        "name": info.get("shortName", t),
                        "market": market
                    })
            except:
                pass
        
        # Also try yfinance search (works for English/Korean name queries too)
        try:
            search_results = yf.Search(raw_query)
            if hasattr(search_results, 'quotes') and search_results.quotes:
                for item in search_results.quotes[:8]:
                    symbol = item.get("symbol", "")
                    name = item.get("shortname", "") or item.get("longname", symbol)
                    exchange = item.get("exchange", "")
                    quote_type = str(item.get("quoteType", "")).upper()

                    # Exclude options/derivatives results from autocomplete.
                    symbol_u = str(symbol).upper()
                    name_l = str(name).lower()
                    is_occ_option = bool(re.match(r"^[A-Z]{1,6}\d{6}[CP]\d{8}$", symbol_u))
                    has_option_word = (" call" in name_l) or (" put" in name_l)
                    if quote_type in {"OPTION", "FUTURE"} or is_occ_option or has_option_word:
                        continue
                    # Keep core asset types only.
                    if quote_type and quote_type not in {"EQUITY", "ETF"}:
                        continue
                    
                    market = "USA"
                    if exchange in ["KSC", "KOE"]:
                        market = "KOR"
                    elif exchange in ["JPX", "TYO"]:
                        market = "JPN"
                    
                    # Avoid duplicates
                    if not any(str(r.get("ticker", "")).upper() == symbol_u for r in results):
                        results.append({
                            "ticker": symbol,
                            "name": name,
                            "market": market
                        })
        except:
            pass

        # Relevance sort: ticker-exact -> ticker-prefix -> name matches.
        q_lower = raw_query.lower()
        market_rank = {"KOR": 0, "USA": 1, "JPN": 2}
        def relevance_key(item):
            name = str(item.get("name", "")).lower()
            ticker = str(item.get("ticker", "")).upper()
            ticker_exact = ticker == query_upper
            ticker_starts = ticker.startswith(query_upper)
            ticker_contains = query_upper in ticker
            name_exact = name == q_lower
            name_starts = name.startswith(q_lower)
            name_contains = q_lower in name
            return (
                0 if ticker_exact else 1,
                0 if ticker_starts else 1,
                0 if ticker_contains else 1,
                0 if name_exact else 1,
                0 if name_starts else 1,
                0 if name_contains else 1,
                market_rank.get(str(item.get("market", "")).upper(), 9),
                len(ticker),
            )

        results.sort(key=relevance_key)
        
    except Exception as e:
        logging.error(f"Stock search error: {e}")
    
    return {"status": "success", "data": results[:10]}


@app.get("/api/market-calendar")
async def get_market_calendar():
    try:
        import requests
        import datetime
        import dateutil.parser
        import pytz
        
        # We need from today to 7 days later
        now_kst = datetime.datetime.now(pytz.timezone('Asia/Seoul'))
        start_date = now_kst.strftime("%Y-%m-%dT00:00:00.000Z")
        end_date = (now_kst + datetime.timedelta(days=7)).strftime("%Y-%m-%dT23:59:59.000Z")
        
        url = f"https://economic-calendar.tradingview.com/events?from={start_date}&to={end_date}&countries=US,KR,CN,JP,EU,GB,DE"
        
        events = []
        headers = {
            'Origin': 'https://www.tradingview.com',
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'application/json, text/plain, */*'
        }
        
        try:
            res = requests.get(url, headers=headers, timeout=10)
            if res.status_code == 200:
                data = res.json()
                events = data.get('result', [])
        except Exception as e:
            print(f"Error fetching TradingView Calendar: {e}")
                
        kr_trans = {
            "Retail Sales Control Group": "소매판매 관리그룹",
            "Retail Sales Ex Autos": "자동차 제외 소매판매",
            "Retail Sales": "소매판매",
            "Core CPI": "근원 소비자물가지수(CPI)",
            "CPI s.a": "소비자물가지수(계절조정)",
            "CPI": "소비자물가지수(CPI)",
            "Unemployment Rate": "실업률",
            "Non-Farm Employment Change": "비농업 고용지수",
            "Non Farm Payrolls": "비농업 고용지수",
            "ADP Employment Change": "ADP 민간고용",
            "JOLTs Job Openings": "JOLTs 구인건수",
            "Initial Jobless Claims": "신규 실업수당 청구건수",
            "Participation Rate": "경제활동참가율",
            "Unit Labour Costs": "단위노동비용",
            "Average Hourly Earnings": "평균 시간당 임금",
            "GDP Growth Rate": "국내총생산(GDP) 성장률",
            "GDP Price Index": "GDP 물가지수",
            "GDP": "국내총생산(GDP)",
            "S&P Global Composite PMI": "S&P 글로벌 복합 PMI",
            "S&P Global Manufacturing PMI": "S&P 글로벌 제조업 PMI",
            "S&P Global Services PMI": "S&P 글로벌 서비스업 PMI",
            "ISM Manufacturing Employment": "ISM 제조업 고용지수",
            "ISM Manufacturing PMI": "ISM 제조업 PMI",
            "ISM Services PMI": "ISM 서비스업 PMI",
            "PMI": "구매관리자지수(PMI)",
            "Fed Interest Rate Decision": "미국 연준(Fed) 기준금리 결정",
            "Federal Funds Rate": "미국 기준금리 결정",
            "FOMC Economic Projections": "FOMC 경제전망",
            "FOMC": "연방공개시장위원회(FOMC)",
            "Fed Hammack Speech": "연준 Hammack 연설",
            "Fed Kashkari Speech": "연준 Kashkari 연설",
            "Fed Williams Speech": "연준 Williams 연설",
            "Fed Press Conference": "연준 기자회견",
            "Core PPI": "근원 생산자물가지수(PPI)",
            "PPI": "생산자물가지수(PPI)",
            "Core PCE Price Index": "근원 개인소비지출(PCE) 물가지수",
            "PCE Price Index": "개인소비지출(PCE) 물가지수",
            "Inflation Rate": "인플레이션 율",
            "Building Permits": "건축 허가건수",
            "Housing Starts": "주택 착공건수",
            "Existing Home Sales": "기존 주택 판매",
            "New Home Sales": "신규 주택 판매",
            "Pending Home Sales": "임시 주택 판매",
            "NAHB Housing Market Index": "NAHB 주택시장지수",
            "MBA 30-Year Mortgage Rate": "MBA 30년 모기지 금리",
            "Consumer Confidence": "소비자 신뢰지수",
            "Michigan Consumer Sentiment": "미시간대 소비자심리지수",
            "Personal Income": "개인 소득",
            "Personal Spending": "개인 지출",
            "Industrial Production": "산업생산",
            "Factory Orders": "공장재 수주",
            "Durable Goods Orders Ex Transp": "교통 제외 내구재 수주",
            "Durable Goods Orders": "내구재 수주",
            "Business Inventories": "기업 재고",
            "Wholesale Inventories": "도매 재고",
            "API Crude Oil Stock Change": "API 주간 원유재고",
            "EIA Crude Oil Stocks Change": "EIA 주간 원유재고",
            "EIA Gasoline Stocks Change": "EIA 주간 가솔린재고",
            "Balance of Trade": "무역수지",
            "Goods Trade Balance Adv": "상품 무역수지(사전)",
            "Export Prices": "수출물가지수",
            "Import Prices": "수입물가지수",
            "Exports": "수출",
            "Imports": "수입",
            "Current Account": "경상수지",
            "Net Long-term TIC Flows": "순 장기 TIC 흐름",
            "Monthly Budget Statement": "월간 재정수지",
            "Chicago Fed National Activity Index": "시카고 연은 국가활동지수",
            "NY Empire State Manufacturing Index": "뉴욕 엠파이어스테이트 제조업지수",
            "Philadelphia Fed Manufacturing Index": "필라델피아 연은 제조업지수",
            "Bank Holiday": "은행 휴일",
            "Weekly": "(주간)",
            "Prelim": "예비치",
            "Flash": "속보치",
            "Final": "확정치",
            "Adv": "사전",
            "2nd Est": "2차 추정치",
            "m/m": "(월간)",
            "q/q": "(분기)",
            "y/y": "(연간)",
            "YoY": "(연간)",
            "MoM": "(월간)",
            "QoQ": "(분기)"
        }
        
        def translate_title(title):
            for eng, kor in kr_trans.items():
                title = title.replace(eng, kor)
            return title.strip()
            
        processed_events = []
        
        for e in events:
            try:
                # Tradingview date typically '2026-03-01T00:00:00.000Z'
                dt = dateutil.parser.isoparse(e['date']) 
                dt_kst = dt.astimezone(pytz.timezone('Asia/Seoul'))
                
                if dt_kst < now_kst:
                    continue
                    
                country = e.get('country', '')
                impact_raw = e.get('importance', -1)
                
                # TradingView scale: 1 (High, 3 stars), 0 (Medium, 2 stars), -1 (Low, 1 star)
                if impact_raw == 1:
                    importance = 3
                elif impact_raw == 0:
                    importance = 2
                elif impact_raw == -1:
                    importance = 1
                else:
                    importance = 0
                    
                act = str(e.get('actual', ''))
                fore = str(e.get('forecast', ''))
                prev = str(e.get('previous', ''))
                
                # Only take US events, and importance must be at least 2 (out of 3)
                if country != 'US' or importance < 2:
                    continue
                    
                days = ['월', '화', '수', '목', '금', '토', '일']
                day_str = days[dt_kst.weekday()]
                time_str = dt_kst.strftime(f"%m/%d({day_str}) %H:%M")
                
                title = translate_title(e.get('title', 'Unknown Event'))
                
                processed_events.append({
                    "time": time_str,
                    "event": f"{country} - {title}",
                    "currency": country,
                    "actual": act,
                    "forecast": fore,
                    "previous": prev,
                    "importance": importance,
                    "_dt": dt_kst 
                })
            except Exception as ex:
                print(f"Event parse error: {ex}")
                
        # Sort by datetime
        processed_events.sort(key=lambda x: x['_dt'])
        
        final_events = []
        for e in processed_events[:15]:
            e.pop('_dt', None)
            final_events.append(e)

        return {
            "status": "success",
            "data": final_events
        }
    except Exception as e:
        print(f"Calendar error: {e}")
        return {"status": "error", "message": str(e)}

@app.get("/api/asset-insight")
async def get_asset_insight(ticker: str, market_type: str = "USA"):
    try:
        if not ticker:
            return {"status": "error", "message": "Ticker not provided."}
        
        # Resolve yfinance ticker based on market type
        yf_ticker = ticker
        if market_type == "KOR":
            # Korean stocks: try .KS (KOSPI) first, fallback to .KQ (KOSDAQ)
            if not ticker.endswith('.KS') and not ticker.endswith('.KQ'):
                yf_ticker = ticker + '.KS'
                test_tc = yf.Ticker(yf_ticker)
                test_info = test_tc.info
                if not test_info or test_info.get('regularMarketPrice') is None:
                    yf_ticker = ticker + '.KQ'
        elif market_type == "JPN":
            # Japanese stocks: append .T (Tokyo Stock Exchange)
            if not ticker.endswith('.T'):
                yf_ticker = ticker + '.T'
            
        tc = yf.Ticker(yf_ticker)
        
        # Financial Info
        info = tc.info
        
        logo_url = info.get("logo_url", "")
        if not logo_url:
            website = info.get("website", "")
            if website:
                # Extract domain
                import urllib.parse
                try:
                    parsed_uri = urllib.parse.urlparse(website)
                    domain = '{uri.netloc}'.format(uri=parsed_uri).replace('www.', '')
                    logo_url = f"https://img.logo.dev/{domain}?token=pk_Wf5NHZcSQmOdtQZWUZA9TA"
                except:
                    pass
                    
        current_price = info.get("currentPrice") or info.get("regularMarketPrice") or 0
        financials = {
            "forwardPE": info.get("forwardPE") or "N/A",
            "returnOnEquity": info.get("returnOnEquity") if info.get("returnOnEquity") is not None else "N/A",
            "debtToEquity": info.get("debtToEquity") or "N/A",
            "currentPrice": current_price if current_price else "N/A",
            "shortName": info.get("shortName") or ticker,
            "currency": info.get("currency") or "",
            "recommendation": info.get("recommendationKey") or "N/A",
            "logo_url": logo_url,
            "fiftyTwoWeekHigh": info.get("fiftyTwoWeekHigh") or "N/A",
            "fiftyTwoWeekLow": info.get("fiftyTwoWeekLow") or "N/A",
            "beta": info.get("beta") if info.get("beta") is not None else "N/A",
            "marketCap": info.get("marketCap") or "N/A",
            "shortPercentOfFloat": info.get("shortPercentOfFloat") if info.get("shortPercentOfFloat") is not None else "N/A",
            "targetMeanPrice": info.get("targetMeanPrice") or "N/A",
            "targetHighPrice": info.get("targetHighPrice") or "N/A",
            "targetLowPrice": info.get("targetLowPrice") or "N/A",
            "dividendYield": info.get("dividendYield") if info.get("dividendYield") is not None else "N/A",
        }
        
        # Options Expiry
        options_data = None
        try:
            opts = tc.options
            if opts:
                nearest_date = opts[0]
                chain = tc.option_chain(nearest_date)
                
                # We must cast numpy data types to standard Python ints for JSON serialization
                calls_vol = int(chain.calls['volume'].sum()) if 'volume' in chain.calls else 0
                calls_oi = int(chain.calls['openInterest'].sum()) if 'openInterest' in chain.calls else 0
                puts_vol = int(chain.puts['volume'].sum()) if 'volume' in chain.puts else 0
                puts_oi = int(chain.puts['openInterest'].sum()) if 'openInterest' in chain.puts else 0
                
                # Additional metrics
                import numpy as np
                max_call_oi_strike = float(chain.calls.loc[chain.calls['openInterest'].idxmax()]['strike']) if not chain.calls.empty and 'openInterest' in chain.calls and not chain.calls['openInterest'].isnull().all() else 0
                max_put_oi_strike = float(chain.puts.loc[chain.puts['openInterest'].idxmax()]['strike']) if not chain.puts.empty and 'openInterest' in chain.puts and not chain.puts['openInterest'].isnull().all() else 0
                
                # Max Pain Calculation
                max_pain = 0
                call_strikes = chain.calls['strike'].values if not chain.calls.empty else []
                put_strikes = chain.puts['strike'].values if not chain.puts.empty else []
                strikes = np.unique(np.concatenate((call_strikes, put_strikes)))
                
                if len(strikes) > 0:
                    min_loss = float('inf')
                    for strike in strikes:
                        loss = 0
                        if not chain.calls.empty:
                            loss += ((chain.calls['strike'] < strike) * (strike - chain.calls['strike']) * chain.calls['openInterest']).sum()
                        if not chain.puts.empty:
                            loss += ((chain.puts['strike'] > strike) * (chain.puts['strike'] - strike) * chain.puts['openInterest']).sum()
                        if loss < min_loss:
                            min_loss = loss
                            max_pain = float(strike)
                
                # ATM Implied Volatility
                atm_iv = None
                try:
                    if current_price and not chain.calls.empty and 'impliedVolatility' in chain.calls.columns:
                        atm_call = chain.calls.iloc[(chain.calls['strike'] - float(current_price)).abs().argsort()[:1]]
                        iv_val = atm_call['impliedVolatility'].values[0]
                        if iv_val and iv_val > 0:
                            atm_iv = round(float(iv_val) * 100, 2)
                except:
                    pass
                
                options_data = {
                    "date": nearest_date,
                    "calls_volume": calls_vol,
                    "calls_oi": calls_oi,
                    "puts_volume": puts_vol,
                    "puts_oi": puts_oi,
                    "max_pain": max_pain,
                    "max_call_oi_strike": max_call_oi_strike,
                    "max_put_oi_strike": max_put_oi_strike,
                    "atm_iv": atm_iv
                }
        except Exception as e:
            print(f"Options parsing error: {e}")
            pass
            
        # News
        news_data = []
        try:
            news = tc.news
            if news:
                for n in news[:5]: # Top 5
                    # yfinance news structure changed. Usually dict with 'content'
                    content = n.get("content", {})
                    title = content.get("title", n.get("title", ""))
                    provider = content.get("provider", {}).get("displayName", n.get("publisher", "Yahoo Finance"))
                    
                    # Sometimes link is inside 'clickThroughUrl'
                    click_data = content.get("clickThroughUrl", {})
                    link = click_data.get("url", n.get("link", ""))
                    
                    if title:
                        news_data.append({
                            "title": title,
                            "publisher": provider,
                            "link": link
                        })
        except Exception as e:
            print(f"News parsing error: {e}")
            pass
            
        # Historical price data (for Lightweight Charts fallback)
        history_data = []
        try:
            hist = tc.history(period="6mo", interval="1d")
            if hist is not None and not hist.empty:
                for idx, row in hist.iterrows():
                    history_data.append({
                        "time": idx.strftime("%Y-%m-%d"),
                        "open": round(float(row["Open"]), 2),
                        "high": round(float(row["High"]), 2),
                        "low": round(float(row["Low"]), 2),
                        "close": round(float(row["Close"]), 2),
                        "volume": int(row.get("Volume", 0))
                    })
        except Exception as e:
            print(f"History data error: {e}")
            
        return {
            "status": "success",
            "data": {
                "financials": financials,
                "options": options_data,
                "news": news_data,
                "history": history_data
            }
        }
    except Exception as e:
        print(f"Insight info error: {e}")
        return {"status": "error", "message": str(e)}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
