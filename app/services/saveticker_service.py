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

from app.services.insight_schema import normalize_section
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


class InsightRevoked(Exception):
    """The request no longer has a valid credential lease."""


class SaveTickerService:
    CACHE_TTL = 300
    MAX_CACHE = 128
    BACKOFF = 60
    TIMEOUT = (3.05, 8)

    def __init__(self, email="", password="", enabled=True, session=None):
        self._credentials = (email, password) if email and password else None
        self.enabled = enabled and bool(self._credentials)
        self._session = session if session is not None else requests.Session()
        self._session.headers.update(HEADERS)
        self._lock = threading.RLock()  # Serializes network work, never acquired by revoke.
        self._state_lock = threading.RLock()
        self._generation = 0
        self._revoked = False
        self._authenticated = False
        self._retry_at = 0.0
        self._cache = OrderedDict()

    def revoke(self):
        with self._state_lock:
            self._revoked = True
            self._generation += 1
            self.enabled = False
            self._credentials = None
            self._authenticated = False
            self._cache.clear()
            transport, self._session = self._session, None
            if transport is not None:
                transport.cookies.clear()

    def clear_cache(self):
        with self._state_lock:
            self._generation += 1
            self._cache.clear()

    def _check(self, generation, transport):
        with self._state_lock:
            if self._revoked or generation != self._generation:
                transport.cookies.clear()
                raise InsightRevoked()

    def _backoff(self, generation, transport):
        with self._state_lock:
            self._check(generation, transport)
            self._retry_at = time.monotonic() + self.BACKOFF
            self._authenticated = False
            transport.cookies.clear()

    def _login(self, generation, transport):
        with self._state_lock:
            self._check(generation, transport)
            credentials = self._credentials
            transport.cookies.clear()
        try:
            response = transport.post(BASE_URL + "/api/auth/login",
                json={"email": credentials[0], "password": credentials[1]},
                timeout=self.TIMEOUT, allow_redirects=False)
            self._check(generation, transport)
            data = response.json() if response.status_code == 200 else None
            authenticated = (isinstance(data, dict) and isinstance(data.get("user_info"), dict)
                             and any(c.name == "access_token" for c in transport.cookies))
        except (requests.RequestException, ValueError):
            authenticated = False
        finally:
            credentials = None
            self._check(generation, transport)
        with self._state_lock:
            self._check(generation, transport)
            self._authenticated = authenticated
            if not authenticated:
                self._backoff(generation, transport)
            return authenticated

    def authenticate(self):
        with self._lock:
            with self._state_lock:
                if self._revoked:
                    raise InsightRevoked()
                generation, transport = self._generation, self._session
            return self.enabled and self._login(generation, transport)

    def fetch(self, ticker, market_type="USA"):
        ticker = str(ticker or "").strip().upper()
        with self._lock:
            with self._state_lock:
                if self._revoked:
                    raise InsightRevoked()
                generation, transport = self._generation, self._session
                if str(market_type).upper() != "USA" or not re.fullmatch(r"[A-Z][A-Z0-9.-]{0,19}", ticker):
                    return empty_snapshot("unsupported")
                if not self.enabled:
                    return empty_snapshot("disabled")
                now = time.monotonic()
                cached = self._cache.get(ticker)
                ttl = self.BACKOFF if cached and (cached[1]["status"] == "unavailable" or "error" in cached[1]["section_status"].values()) else self.CACHE_TTL
                if cached and now - cached[0] < ttl:
                    self._cache.move_to_end(ticker)
                    return copy.deepcopy(cached[1])
                if now < self._retry_at:
                    return empty_snapshot("unavailable")
            if not self._authenticated and not self._login(generation, transport):
                return empty_snapshot("unavailable")
            result = empty_snapshot("unavailable")
            refreshed = False
            for key, endpoint in SECTIONS.items():
                try:
                    self._check(generation, transport)
                    url = f"{BASE_URL}/api/stocks/api/v1/tickers/{quote(ticker, safe='')}/{endpoint}"
                    if key == "news":
                        url = f"{BASE_URL}/api/news/company?page=1&page_size=3&ticker={quote(ticker, safe='')}&sort=created_at_desc"
                    response = transport.get(url, timeout=self.TIMEOUT, allow_redirects=False)
                    self._check(generation, transport)
                    if response.status_code == 401 and not refreshed:
                        refreshed = True
                        if not self._login(generation, transport):
                            result["section_status"][key] = "error"
                            break
                        self._check(generation, transport)
                        response = transport.get(url, timeout=self.TIMEOUT, allow_redirects=False)
                        self._check(generation, transport)
                    if response.status_code in (401, 403, 429):
                        result["section_status"][key] = "error"
                        self._backoff(generation, transport)
                        break
                    if response.status_code in (204, 404):
                        continue
                    if response.status_code != 200:
                        result["section_status"][key] = "error"
                        continue
                    data = response.json()
                    if data is None or data == {}:
                        continue
                    if key == "news":
                        data = _normalize_news(data)
                    data = normalize_section(key, data)
                    if data is None or not _valid_section(key, data):
                        result["section_status"][key] = "error"
                        continue
                    result["sections"][key] = data
                    result["section_status"][key] = "available"
                except (requests.RequestException, ValueError):
                    self._check(generation, transport)
                    result["section_status"][key] = "error"
            count = sum(v == "available" for v in result["section_status"].values())
            status = "available" if count == len(SECTIONS) else "partial" if count else "unavailable"
            result.update(status=status, message=empty_snapshot(status)["message"])
            with self._state_lock:
                self._check(generation, transport)
                self._cache[ticker] = (time.monotonic(), copy.deepcopy(result))
                self._cache.move_to_end(ticker)
                while len(self._cache) > self.MAX_CACHE:
                    self._cache.popitem(last=False)
                return result
