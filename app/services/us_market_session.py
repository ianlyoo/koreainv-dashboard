from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from zoneinfo import ZoneInfo


KST = ZoneInfo("Asia/Seoul")
NEW_YORK = ZoneInfo("America/New_York")


@dataclass(frozen=True)
class MarketSessionInfo:
    session: str
    is_open: bool
    uses_day_prefix: bool


def _is_us_dst(now_kst: dt.datetime) -> bool:
    return bool(now_kst.astimezone(NEW_YORK).dst())


def get_us_market_session(now_kst: dt.datetime | None = None) -> MarketSessionInfo:
    if now_kst is None:
        now_kst = dt.datetime.now(KST)
    elif now_kst.tzinfo is None:
        now_kst = now_kst.replace(tzinfo=KST)
    else:
        now_kst = now_kst.astimezone(KST)

    hm = now_kst.hour * 60 + now_kst.minute
    weekday = now_kst.weekday()
    is_dst = _is_us_dst(now_kst)

    day_market_start = 9 * 60 if is_dst else 10 * 60
    day_market_end = 17 * 60 if is_dst else 18 * 60
    premarket_start = 17 * 60 if is_dst else 18 * 60
    regular_start = 22 * 60 + 30 if is_dst else 23 * 60 + 30
    regular_end = 5 * 60 if is_dst else 6 * 60
    aftermarket_end = 8 * 60 if is_dst else 9 * 60

    if weekday < 5 and day_market_start <= hm < day_market_end:
        return MarketSessionInfo(
            session="day_market",
            is_open=True,
            uses_day_prefix=True,
        )

    if weekday < 5 and premarket_start <= hm < regular_start:
        return MarketSessionInfo(
            session="premarket",
            is_open=True,
            uses_day_prefix=False,
        )

    if (weekday < 5 and hm >= regular_start) or (0 < weekday <= 5 and hm < regular_end):
        return MarketSessionInfo(
            session="regular",
            is_open=True,
            uses_day_prefix=False,
        )

    if 0 < weekday <= 5 and regular_end <= hm < aftermarket_end:
        return MarketSessionInfo(
            session="aftermarket",
            is_open=True,
            uses_day_prefix=False,
        )

    return MarketSessionInfo(session="closed", is_open=False, uses_day_prefix=False)


def build_us_tr_key(
    ticker: str, excg_cd: str, session_info: MarketSessionInfo | None = None
) -> str | None:
    if not ticker:
        return None

    if session_info is None:
        session_info = get_us_market_session()

    exchange = str(excg_cd or "").strip().upper()
    ticker = str(ticker).strip().upper()
    if not ticker:
        return None

    regular_prefix = {
        "NASD": "DNAS",
        "NAS": "DNAS",
        "NYSE": "DNYS",
        "NYS": "DNYS",
        "AMEX": "DAMS",
        "AMS": "DAMS",
    }
    day_prefix = {
        "NASD": "RBAQ",
        "NAS": "RBAQ",
        "NYSE": "RBAY",
        "NYS": "RBAY",
        "AMEX": "RBAA",
        "AMS": "RBAA",
    }

    prefix = day_prefix if session_info.uses_day_prefix else regular_prefix
    mapped = prefix.get(exchange)
    if not mapped:
        return None
    return f"{mapped}{ticker}"
