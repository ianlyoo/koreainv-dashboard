"""Read-only SaveTicker connector; credentials and session cookies stay server-side."""
from __future__ import annotations

import copy
import datetime as dt
import math
import logging
import re
import threading
import time
from collections import OrderedDict
from urllib.parse import quote

import requests

from app import config
from app.version import APP_VERSION

SECTIONS = {
    "header": "header", "key_metrics": "key-metrics", "revenue": "revenue-trend",
    "analyst": "analyst", "insider": "sec-insider", "options": "options",
    "news": "news",
}
logger = logging.getLogger(__name__)
BASE_URL = "https://saveticker.com"
HEADERS = {
    "User-Agent": f"KoreaInvDashboard/{APP_VERSION} (personal portfolio dashboard)",
    "Accept": "application/json", "Content-Type": "application/json",
    "Origin": BASE_URL, "Referer": BASE_URL + "/login",
}


def empty_snapshot(status: str) -> dict:
    messages = {
        "disabled": "SaveTicker 연결이 설정되지 않았습니다.",
        "unsupported": "SaveTicker는 미국 주식 종목을 지원합니다.",
        "unavailable": "SaveTicker 데이터를 불러오지 못했습니다.",
        "available": "SaveTicker 데이터입니다.",
        "partial": "SaveTicker 일부 데이터만 제공됩니다.",
    }
    return {"status": status, "sections": dict.fromkeys(SECTIONS),
            "section_status": dict.fromkeys(SECTIONS, "unavailable"),
            "fetched_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "message": messages[status]}


def _json_safe(value: object) -> bool:
    if isinstance(value, dict):
        return all(isinstance(k, str) and _json_safe(v) for k, v in value.items())
    if isinstance(value, list):
        return all(_json_safe(v) for v in value)
    return value is None or isinstance(value, (str, bool, int)) or (isinstance(value, float) and math.isfinite(value))


def _valid_section(section: str, data: object) -> bool:
    if not isinstance(data, dict) or not _json_safe(data):
        return False
    # Require a recognized field and verify container types before exposing raw data.
    schemas = {
        "header": {"price": (int, float), "week52Range": dict, "marketCap": (int, float)},
        "key_metrics": {k: dict for k in ("per", "eps", "revenueTtm", "dividendYield", "roe", "shortInterestPct")},
        "revenue": {"quarters": list},
        "analyst": {"analystCount": (int, float), "dist": dict, "target": dict, "recent": list},
        "insider": {"buyCount": (int, float), "sellCount": (int, float), "netValue": (int, float), "recent": list},
        "news": {"items": list},
        "options": {"optionable": bool, "volume": (int, float), "volumeShare": dict, "openInterestShare": dict},
    }
    schema = schemas[section]
    present = [key for key in schema if key in data]
    if not present:
        return False
    if not all(data[key] is None or isinstance(data[key], schema[key]) for key in present):
        return False
    for key in ("recent", "quarters"):
        if isinstance(data.get(key), list) and not all(isinstance(row, dict) for row in data[key]):
            return False
    return True


def _normalize_news(data: object) -> dict | None:
    """Drop all article bodies and account metadata before caching or returning."""
    if not isinstance(data, dict) or not isinstance(data.get("news_list"), list):
        return None
    items = []
    for row in data["news_list"][:3]:
        if not isinstance(row, dict) or not isinstance(row.get("title"), str):
            return None
        article_id = row.get("id")
        if isinstance(article_id, bool) or not isinstance(article_id, (str, int)) or not str(article_id):
            return None
        items.append({
            "title": row["title"],
            "publisher": row.get("source") if isinstance(row.get("source"), str) else "SaveTicker",
            "link": BASE_URL + "/news/" + quote(str(article_id), safe=""),
            "published_at": row.get("created_at") if isinstance(row.get("created_at"), str) else None,
        })
    return {"items": items}


class SaveTickerService:
    CACHE_TTL = 300
    MAX_CACHE = 128
    BACKOFF = 60
    TIMEOUT = (3.05, 8)

    def __init__(self, email: str = "", password: str = "", enabled: bool = True, session=None):
        self._email = email
        self._password = password
        self.enabled = enabled and bool(email and password)
        self._session = session if session is not None else requests.Session()
        self._session.headers.update(HEADERS)
        # One lock owns cookie mutation, refresh and cache population (singleflight).
        self._lock = threading.RLock()
        self._authenticated = False
        self._retry_at = 0.0
        self._cache = OrderedDict()

    def _backoff(self):
        self._retry_at = time.monotonic() + self.BACKOFF
        self._authenticated = False
        self._session.cookies.clear()

    def _login(self) -> bool:
        self._session.cookies.clear()
        try:
            response = self._session.post(BASE_URL + "/api/auth/login",
                json={"email": self._email, "password": self._password},
                timeout=self.TIMEOUT, allow_redirects=False)
            data = response.json() if response.status_code == 200 else None
            self._authenticated = (isinstance(data, dict) and isinstance(data.get("user_info"), dict)
                                   and any(c.name == "access_token" for c in self._session.cookies))
        except (requests.RequestException, ValueError):
            self._authenticated = False
        if not self._authenticated:
            logger.warning("SaveTicker authentication unavailable; retry cooldown started")
            self._backoff()
        return self._authenticated

    def fetch(self, ticker: str, market_type: str = "USA") -> dict:
        ticker = str(ticker or "").strip().upper()
        if str(market_type).upper() != "USA" or not re.fullmatch(r"[A-Z][A-Z0-9.-]{0,19}", ticker):
            return empty_snapshot("unsupported")
        if not self.enabled:
            return empty_snapshot("disabled")
        with self._lock:
            now = time.monotonic()
            cached = self._cache.get(ticker)
            cache_ttl = self.BACKOFF if cached and "error" in cached[1]["section_status"].values() else self.CACHE_TTL
            if cached and now - cached[0] < cache_ttl:
                self._cache.move_to_end(ticker)
                return copy.deepcopy(cached[1])
            if now < self._retry_at:
                return empty_snapshot("unavailable")
            if not self._authenticated and not self._login():
                return empty_snapshot("unavailable")
            result = empty_snapshot("unavailable")
            refreshed = False
            for key, endpoint in SECTIONS.items():
                try:
                    url = f"{BASE_URL}/api/stocks/api/v1/tickers/{quote(ticker, safe='')}/{endpoint}"
                    if key == "news":
                        url = f"{BASE_URL}/api/news/company?page=1&page_size=3&ticker={quote(ticker, safe='')}&sort=created_at_desc"
                    response = self._session.get(url, timeout=self.TIMEOUT, allow_redirects=False)
                    if response.status_code == 401 and not refreshed:
                        refreshed = True
                        if not self._login():
                            result["section_status"][key] = "error"
                            break
                        response = self._session.get(url, timeout=self.TIMEOUT, allow_redirects=False)
                    if response.status_code in (401, 403, 429):
                        result["section_status"][key] = "error"
                        logger.warning("SaveTicker section %s unavailable (HTTP %s)", key, response.status_code)
                        self._backoff()
                        break
                    if response.status_code in (204, 404):
                        continue
                    if response.status_code != 200:
                        logger.warning("SaveTicker section %s unavailable (HTTP %s)", key, response.status_code)
                        result["section_status"][key] = "error"
                        continue
                    data = response.json()
                    if data is None or data == {}:
                        continue
                    if key == "news":
                        data = _normalize_news(data)
                    if data is None or not _valid_section(key, data):
                        result["section_status"][key] = "error"
                        continue
                    result["sections"][key] = data
                    result["section_status"][key] = "available"
                except requests.RequestException:
                    logger.warning("SaveTicker section %s request failed", key)
                    result["section_status"][key] = "error"
                    continue
                except ValueError:
                    result["section_status"][key] = "error"
            count = sum(v == "available" for v in result["section_status"].values())
            status = "available" if count == len(SECTIONS) else "partial" if count else "unavailable"
            result.update(status=status, message=empty_snapshot(status)["message"])
            self._cache[ticker] = (time.monotonic(), copy.deepcopy(result))
            self._cache.move_to_end(ticker)
            while len(self._cache) > self.MAX_CACHE:
                self._cache.popitem(last=False)
            return result


saveticker_service = SaveTickerService(config.SAVETICKER_EMAIL, config.SAVETICKER_PASSWORD, config.SAVETICKER_ENABLED)
