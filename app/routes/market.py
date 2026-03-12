from __future__ import annotations

import asyncio
import datetime
import logging
import threading
import time

import dateutil.parser
import pytz
import requests
from fastapi import APIRouter


router = APIRouter()
logger = logging.getLogger(__name__)
_CALENDAR_CACHE_TTL_SECONDS = 900
_calendar_cache: dict[str, object] = {"ts": 0.0, "data": None}
_calendar_cache_lock = threading.RLock()
KST = pytz.timezone("Asia/Seoul")
UTC = pytz.UTC


def _coerce_cache_ts(value: object) -> float:
    if isinstance(value, int):
        return float(value)
    if isinstance(value, float):
        return value
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return 0.0
    return 0.0


def _get_cached_calendar():
    with _calendar_cache_lock:
        cached = _calendar_cache.get("data")
        if cached is not None and (time.time() - _coerce_cache_ts(_calendar_cache.get("ts"))) < _CALENDAR_CACHE_TTL_SECONDS:
            return cached
    return None


def _set_cached_calendar(data: object):
    with _calendar_cache_lock:
        _calendar_cache["ts"] = time.time()
        _calendar_cache["data"] = data


def _build_calendar_window(now_kst: datetime.datetime | None = None) -> tuple[datetime.datetime, str, str]:
    current_kst = now_kst or datetime.datetime.now(KST)
    start_kst = KST.localize(
        datetime.datetime(
            current_kst.year,
            current_kst.month,
            current_kst.day,
            0,
            0,
            0,
        )
    )
    end_base = current_kst + datetime.timedelta(days=7)
    end_kst = KST.localize(
        datetime.datetime(
            end_base.year,
            end_base.month,
            end_base.day,
            23,
            59,
            59,
        )
    )
    start_utc = start_kst.astimezone(UTC)
    end_utc = end_kst.astimezone(UTC)
    return (
        current_kst,
        start_utc.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
        end_utc.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
    )


def _format_calendar_time(dt_kst: datetime.datetime) -> str:
    days = ["월", "화", "수", "목", "금", "토", "일"]
    day_str = days[dt_kst.weekday()]
    return (
        f"{dt_kst.month:02d}/{dt_kst.day:02d}"
        f"({day_str}) {dt_kst.hour:02d}:{dt_kst.minute:02d}"
    )


def _fetch_calendar_events(url: str, headers: dict[str, str]) -> list[dict[str, object]]:
    response = requests.get(url, headers=headers, timeout=10)
    if response.status_code == 200:
        data = response.json()
        result = data.get("result", [])
        return [item for item in result if isinstance(item, dict)]
    return []


def _event_str(event: dict[str, object], key: str, default: str = "") -> str:
    value = event.get(key, default)
    return value if isinstance(value, str) else str(value)


def _event_int(event: dict[str, object], key: str, default: int = 0) -> int:
    value = event.get(key, default)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return default
    return default


@router.get("/api/market-calendar")
async def get_market_calendar():
    try:
        cached = _get_cached_calendar()
        if cached is not None:
            return cached

        now_kst, start_date, end_date = _build_calendar_window()

        url = f"https://economic-calendar.tradingview.com/events?from={start_date}&to={end_date}&countries=US"

        events: list[dict[str, object]] = []
        headers = {
            "Origin": "https://www.tradingview.com",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept": "application/json, text/plain, */*",
        }

        try:
            events = await asyncio.to_thread(_fetch_calendar_events, url, headers)
        except Exception as exc:
            logger.warning("Error fetching TradingView Calendar: %s", exc)

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
            "QoQ": "(분기)",
        }

        def translate_title(title: str) -> str:
            for eng, kor in kr_trans.items():
                title = title.replace(eng, kor)
            return title.strip()

        processed_events = []
        for event in events:
            try:
                raw_date_value = event.get("date")
                if not isinstance(raw_date_value, str):
                    continue
                raw_date = raw_date_value
                dt = dateutil.parser.isoparse(raw_date)
                dt_kst = dt.astimezone(KST)
                if dt_kst < now_kst:
                    continue

                country = _event_str(event, "country", "")
                impact_raw = _event_int(event, "importance", -1)
                if impact_raw == 1:
                    importance = 3
                elif impact_raw == 0:
                    importance = 2
                elif impact_raw == -1:
                    importance = 1
                else:
                    importance = 0

                if country != "US" or importance < 2:
                    continue

                act = _event_str(event, "actual", "")
                fore = _event_str(event, "forecast", "")
                prev = _event_str(event, "previous", "")
                time_str = _format_calendar_time(dt_kst)
                title = translate_title(_event_str(event, "title", "Unknown Event"))

                processed_events.append(
                    {
                        "time": time_str,
                        "event": f"{country} - {title}",
                        "currency": country,
                        "actual": act,
                        "forecast": fore,
                        "previous": prev,
                        "importance": importance,
                        "_dt": dt_kst,
                    }
                )
            except Exception as exc:
                logger.warning("Event parse error: %s", exc)

        processed_events.sort(key=lambda item: item["_dt"])
        final_events = []
        for event in processed_events[:15]:
            event.pop("_dt", None)
            final_events.append(event)

        payload = {"status": "success", "data": final_events}
        if final_events:
            _set_cached_calendar(payload)
        return payload
    except Exception as exc:
        logger.exception("Calendar error: %s", exc)
        return {"status": "error", "message": str(exc)}
