from __future__ import annotations

"""
KIS WebSocket Client Module
- Approval key caching (24hr validity)
- WebSocket connection management (domestic + overseas)
- Auto-reconnect with exponential backoff
- PINGPONG handling
"""

import asyncio
import json
import os
import time
import hashlib
import logging
from datetime import datetime, timedelta
from typing import Optional, Dict, Set

import requests
import websockets

import config

logger = logging.getLogger(__name__)

# --- Approval Key Management ---

WS_KEY_FILE = os.path.join(os.path.expanduser("~"), ".kis_ws_key.json")
WS_URL_REAL = "ws://ops.koreainvestment.com:21000"

_last_approval_attempt = 0  # Rate limit: prevent rapid re-requests


def get_ws_approval_key(app_key: str, app_secret: str) -> Optional[str]:
    """
    Get WebSocket approval key with 24hr caching.
    - Checks cached key file first
    - Only issues new key if cache is missing/expired/app_key changed
    - Rate-limits to prevent 1-minute block from KIS
    """
    global _last_approval_attempt
    now = time.time()

    key_hash = hashlib.sha256(app_key.encode()).hexdigest()[:16]

    try:
        if os.path.exists(WS_KEY_FILE):
            with open(WS_KEY_FILE, "r", encoding="utf-8") as f:
                cached = json.load(f)

            cached_time = datetime.fromisoformat(cached["issued_at"])
            cached_hash = cached.get("app_key_hash", "")

            if cached_hash == key_hash and datetime.now() - cached_time < timedelta(hours=23):
                logger.info("Using cached WebSocket approval key (issued: %s)", cached_time)
                return cached["approval_key"]
            logger.info("Cached WebSocket key expired or app_key changed, re-issuing")
    except Exception as e:
        logger.warning("Failed to read cached WS key: %s", e)

    if _last_approval_attempt > 0 and (now - _last_approval_attempt) < 65:
        remaining = 65 - (now - _last_approval_attempt)
        logger.warning("Rate limited: wait %.0f seconds before next approval key request", remaining)
        return None

    _last_approval_attempt = now
    try:
        url = f"{config.URL_BASE}/oauth2/Approval"
        body = {
            "grant_type": "client_credentials",
            "appkey": app_key,
            "secretkey": app_secret,
        }
        res = requests.post(url, json=body, timeout=10)
        if res.status_code == 200:
            data = res.json()
            approval_key = data.get("approval_key")
            if approval_key:
                cache_data = {
                    "approval_key": approval_key,
                    "issued_at": datetime.now().isoformat(),
                    "app_key_hash": key_hash,
                }
                os.makedirs(os.path.dirname(WS_KEY_FILE) or ".", exist_ok=True)
                with open(WS_KEY_FILE, "w", encoding="utf-8") as f:
                    json.dump(cache_data, f, ensure_ascii=False)
                os.chmod(WS_KEY_FILE, 0o600)
                logger.info("New WebSocket approval key issued and cached")
                return approval_key
        logger.error("Failed to get approval key: %s %s", res.status_code, res.text[:200])
    except Exception as e:
        logger.error("Approval key request error: %s", e)

    return None


class KISWebSocketManager:
    """Manage KIS real-time websocket connection and SSE fanout."""

    def __init__(self):
        self._ws: Optional[websockets.WebSocketClientProtocol] = None
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self._subscribed_domestic: Set[str] = set()
        self._subscribed_overseas: Set[str] = set()
        self._approval_key: Optional[str] = None
        self._app_key: Optional[str] = None
        self._app_secret: Optional[str] = None
        self._retry_count = 0
        self._max_retries = 10
        self._latest_prices: Dict[str, dict] = {}
        self._listeners: list[asyncio.Queue] = []
        self._queue_drop_count = 0
        self._parse_error_counts = {"domestic": 0, "overseas": 0}

    @property
    def is_connected(self) -> bool:
        if self._ws is None:
            return False
        if hasattr(self._ws, "open"):
            return self._ws.open
        state = getattr(self._ws, "state", None)
        if state is not None:
            return getattr(state, "name", "") == "OPEN" or getattr(state, "value", 0) == 1
        return False

    @property
    def latest_prices(self) -> Dict[str, dict]:
        return self._latest_prices

    def add_listener(self) -> asyncio.Queue:
        q = asyncio.Queue(maxsize=100)
        self._listeners.append(q)
        return q

    def remove_listener(self, q: asyncio.Queue):
        if q in self._listeners:
            self._listeners.remove(q)

    def _broadcast(self, data: dict):
        for q in self._listeners:
            try:
                q.put_nowait(data)
            except asyncio.QueueFull:
                try:
                    q.get_nowait()
                    q.put_nowait(data)
                    self._queue_drop_count += 1
                    if self._queue_drop_count % 50 == 0:
                        logger.warning("SSE queue drops accumulated: %d", self._queue_drop_count)
                except Exception:
                    pass

    @staticmethod
    def _normalize_ticker(raw_ticker: str) -> str:
        normalized = (raw_ticker or "").strip().upper()
        # KRX streams may include prefixes (e.g., A005930); normalize to 6-digit code.
        if len(normalized) >= 6 and normalized[-6:].isdigit():
            return normalized[-6:]
        return normalized

    @staticmethod
    def _strip_overseas_prefix(symbol: str) -> str:
        normalized = (symbol or "").strip().upper()
        for prefix in ("DNAS", "DNYS", "DAMS", "BAYI", "BAQS"):
            if normalized.startswith(prefix):
                return normalized[len(prefix):]
        return normalized

    async def start(
        self,
        app_key: str,
        app_secret: str,
        domestic_codes: list[str] = None,
        overseas_codes: list[str] = None,
    ):
        self._app_key = app_key
        self._app_secret = app_secret

        if self._running:
            await self.update_subscriptions(domestic_codes, overseas_codes)
            return

        self._running = True
        self._retry_count = 0

        if domestic_codes:
            self._subscribed_domestic = set(domestic_codes)
        if overseas_codes:
            self._subscribed_overseas = set(overseas_codes)

        self._task = asyncio.create_task(self._run_loop())

    async def stop(self):
        self._running = False
        if self._ws:
            await self._ws.close()
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def update_subscriptions(
        self,
        domestic_codes: list[str] = None,
        overseas_codes: list[str] = None,
    ):
        if domestic_codes is not None:
            new_domestic = set(domestic_codes)
            sub_domestic = new_domestic - self._subscribed_domestic
            unsub_domestic = self._subscribed_domestic - new_domestic
            self._subscribed_domestic = new_domestic

            if self.is_connected:
                for code in sub_domestic:
                    await self._send_subscribe(self._ws, "H0STCNT0", code, True)
                for code in unsub_domestic:
                    await self._send_subscribe(self._ws, "H0STCNT0", code, False)

        if overseas_codes is not None:
            new_overseas = set(overseas_codes)
            sub_overseas = new_overseas - self._subscribed_overseas
            unsub_overseas = self._subscribed_overseas - new_overseas
            self._subscribed_overseas = new_overseas

            if self.is_connected:
                for code in sub_overseas:
                    await self._send_subscribe(self._ws, "HDFSCNT0", code, True)
                for code in unsub_overseas:
                    await self._send_subscribe(self._ws, "HDFSCNT0", code, False)

    async def _run_loop(self):
        while self._running:
            try:
                self._approval_key = get_ws_approval_key(self._app_key, self._app_secret)
                if not self._approval_key:
                    logger.error("Cannot get WebSocket approval key, retrying in 30s...")
                    await asyncio.sleep(30)
                    continue

                logger.info("Connecting to KIS WebSocket...")
                async with websockets.connect(
                    WS_URL_REAL,
                    ping_interval=None,
                    close_timeout=10,
                ) as ws:
                    self._ws = ws
                    self._retry_count = 0
                    logger.info("WebSocket connected!")

                    self._broadcast({"type": "status", "status": "connected"})
                    await self._subscribe_all(ws)

                    async for raw in ws:
                        await self._handle_message(raw, ws)

            except websockets.exceptions.ConnectionClosed as e:
                logger.warning("WebSocket connection closed: %s", e)
            except Exception as e:
                logger.error("WebSocket error: %s", e)

            if self._running:
                self._retry_count += 1
                if self._retry_count > self._max_retries:
                    logger.error("Max retries (%d) reached, stopping", self._max_retries)
                    self._broadcast({"type": "status", "status": "disconnected"})
                    break

                wait = min(2 ** self._retry_count, 60)
                logger.info(
                    "Reconnecting in %ds (attempt %d/%d)...",
                    wait,
                    self._retry_count,
                    self._max_retries,
                )
                self._broadcast({"type": "status", "status": "reconnecting"})
                await asyncio.sleep(wait)

        self._ws = None

    async def _subscribe_all(self, ws):
        logger.info(
            "Subscribing stocks: domestic=%d, overseas=%d",
            len(self._subscribed_domestic),
            len(self._subscribed_overseas),
        )

        for code in self._subscribed_domestic:
            await self._send_subscribe(ws, "H0STCNT0", code)
            await asyncio.sleep(0.1)

        for code in self._subscribed_overseas:
            await self._send_subscribe(ws, "HDFSCNT0", code)
            await asyncio.sleep(0.1)

    async def _send_subscribe(self, ws, tr_id: str, tr_key: str, subscribe: bool = True):
        msg = {
            "header": {
                "approval_key": self._approval_key,
                "custtype": "P",
                "tr_type": "1" if subscribe else "2",
                "content-type": "utf-8",
            },
            "body": {
                "input": {
                    "tr_id": tr_id,
                    "tr_key": tr_key,
                }
            },
        }
        await ws.send(json.dumps(msg))
        action = "Subscribed" if subscribe else "Unsubscribed"
        logger.info("%s: %s / %s", action, tr_id, tr_key)

    async def _handle_message(self, raw: str, ws):
        if not raw:
            logger.debug("Received empty WebSocket message, skipping")
            return

        if raw[0] in ["0", "1"]:
            parts = raw.split("|")
            if len(parts) < 4:
                logger.debug("Malformed WS data frame, parts=%d", len(parts))
                return

            tr_id = parts[1]
            data_str = parts[3]
            fields_len = len(data_str.split("^")) if data_str else 0
            logger.debug("WS data frame tr_id=%s fields=%d", tr_id, fields_len)

            if tr_id == "H0STCNT0":
                self._parse_domestic_price(data_str)
            elif tr_id == "HDFSCNT0":
                self._parse_overseas_price(data_str)
            else:
                logger.debug("Unhandled WS data tr_id=%s", tr_id)
            return

        try:
            msg = json.loads(raw)
            tr_id = msg.get("header", {}).get("tr_id", "")

            if tr_id == "PINGPONG":
                await ws.send(raw)
                logger.debug("PINGPONG responded")
                return

            body = msg.get("body", {})
            rt_cd = body.get("rt_cd", "")
            msg_text = body.get("msg1", "")
            if rt_cd == "0":
                logger.info("WS system: %s", msg_text)
            else:
                logger.warning("WS error: %s", msg_text)
        except json.JSONDecodeError:
            logger.warning("Unknown WS message format: %s", raw[:100])

    def _parse_domestic_price(self, data_str: str):
        fields = data_str.split("^")
        if len(fields) < 14:
            logger.debug("Domestic frame too short: fields=%d", len(fields))
            return

        try:
            ticker = self._normalize_ticker(fields[0])
            price = int(fields[2])
            change_sign = fields[3]
            change = int(fields[4])
            change_rate = float(fields[5])
            volume = int(fields[13])

            if change_sign in ["4", "5"]:
                change = -abs(change)
                change_rate = -abs(change_rate)

            price_data = {
                "type": "price",
                "market": "KOR",
                "ticker": ticker,
                "price": price,
                "change": change,
                "change_rate": change_rate,
                "volume": volume,
                "timestamp": datetime.now().isoformat(),
            }
            self._latest_prices[ticker] = price_data
            self._broadcast(price_data)
        except (ValueError, IndexError) as e:
            self._parse_error_counts["domestic"] += 1
            logger.warning("Domestic parse error: %s", e)

    def _parse_overseas_price(self, data_str: str):
        fields = data_str.split("^")
        if len(fields) < 20:
            logger.debug("Overseas frame too short: fields=%d", len(fields))
            return

        try:
            symbol = (fields[0] or "").strip()
            price = float(fields[10])
            change_sign = fields[11]
            change = float(fields[12])
            change_rate = float(fields[13])
            volume = int(fields[19])

            if change_sign in ["4", "5"]:
                change = -abs(change)
                change_rate = -abs(change_rate)

            clean_ticker = self._strip_overseas_prefix(symbol)
            price_data = {
                "type": "price",
                "market": "USA",
                "ticker": clean_ticker,
                "raw_symbol": symbol,
                "price": price,
                "change": change,
                "change_rate": change_rate,
                "volume": volume,
                "timestamp": datetime.now().isoformat(),
            }
            self._latest_prices[clean_ticker] = price_data
            self._broadcast(price_data)
        except (ValueError, IndexError) as e:
            self._parse_error_counts["overseas"] += 1
            logger.warning("Overseas parse error: %s", e)


ws_manager = KISWebSocketManager()
