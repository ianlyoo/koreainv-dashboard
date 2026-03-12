from __future__ import annotations

import datetime as dt
import json
import logging
import threading
import time
from dataclasses import dataclass
from typing import Any, cast
from zoneinfo import ZoneInfo

import requests

from app import config
from app.services.us_market_session import build_us_tr_key, get_us_market_session

try:
    import websocket
except ImportError:
    websocket = None


logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")
WS_REAL_URL = "ws://ops.koreainvestment.com:21000/tryitout"
WS_PAPER_URL = "ws://ops.koreainvestment.com:31000/tryitout"
TR_ASKING_PRICE = "HDFSASP0"
TR_CONTRACT = "HDFSCNT0"
QUOTE_STALE_SECONDS = 180
APPKEY_CONFLICT_COOLDOWN_SECONDS = 180
CONTRACT_FIELD_COUNT = 26
ASK_FIELD_COUNT = 17


@dataclass
class QuoteSnapshot:
    ticker: str
    tr_key: str
    price: float
    bid: float | None = None
    ask: float | None = None
    market_type: str | None = None
    source: str = "websocket"
    quote_session: str | None = None
    quoted_at: dt.datetime | None = None
    updated_at: dt.datetime | None = None

    def is_stale(self, now: dt.datetime | None = None) -> bool:
        if self.updated_at is None:
            return True
        if now is None:
            now = dt.datetime.now(KST)
        return (now - self.updated_at).total_seconds() > QUOTE_STALE_SECONDS


class KISUSQuoteService:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._wake_event = threading.Event()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._ws = None
        self._approval_key: str | None = None
        self._quote_cache: dict[str, QuoteSnapshot] = {}
        self._session_targets: dict[str, list[dict[str, str]]] = {}
        self._session_us_items: dict[str, list[dict[str, Any]]] = {}
        self._desired_keys: dict[str, tuple[str, str]] = {}
        self._subscribed_keys: set[str] = set()
        self._subscription_diag: dict[str, dict[str, Any]] = {}
        self._app_key: str | None = None
        self._app_secret: str | None = None
        self._session_name: str = "closed"
        self._last_session_refresh_at = 0.0
        self._appkey_conflict_retry_at = 0.0

    def start(self) -> None:
        self._stop_event.clear()

    def stop(self) -> None:
        self._stop_event.set()
        self._wake_event.set()
        ws = None
        thread = None
        with self._lock:
            ws = self._ws
            thread = self._thread
        if ws is not None:
            try:
                ws.close()
            except Exception:
                logger.debug("Failed to close KIS websocket", exc_info=True)
        if thread is not None and thread.is_alive():
            thread.join(timeout=3)

    def remove_session(self, session_id: str | None) -> None:
        if not session_id:
            return
        ws = None
        with self._lock:
            self._session_targets.pop(session_id, None)
            self._session_us_items.pop(session_id, None)
            self._recompute_targets_locked()
            ws = self._ws
        if ws is not None:
            self._send_subscriptions(ws)
            with self._lock:
                if not self._desired_keys and self._ws is not None:
                    try:
                        self._ws.close()
                    except Exception:
                        logger.debug(
                            "Failed to close websocket after session removal",
                            exc_info=True,
                        )
        self._wake_event.set()

    def sync_session_holdings(
        self,
        session_id: str | None,
        app_key: str,
        app_secret: str,
        us_items: list[dict[str, Any]],
        force_retry: bool = False,
    ) -> None:
        if not session_id:
            return

        holdings: list[dict[str, str]] = []
        us_items_copy = [dict(item) for item in (us_items or [])]
        for item in us_items or []:
            ticker = str(item.get("ticker", "")).strip().upper()
            excg_cd = str(item.get("excg_cd", "")).strip().upper()
            if not ticker or not excg_cd:
                continue
            holdings.append({"ticker": ticker, "excg_cd": excg_cd})

        ws = None
        with self._lock:
            credentials_changed = self._app_key not in {
                None,
                app_key,
            } or self._app_secret not in {None, app_secret}
            self._app_key = app_key
            self._app_secret = app_secret
            self._session_targets[session_id] = holdings
            self._session_us_items[session_id] = us_items_copy
            self._recompute_targets_locked(force_refresh_session=True)
            if credentials_changed:
                self._approval_key = None
                self._subscribed_keys.clear()
            if force_retry:
                self._clear_appkey_conflict_cooldown_locked()
            ws = self._ws
        if ws is not None and not credentials_changed:
            self._send_subscriptions(ws)
        elif ws is not None and credentials_changed:
            try:
                ws.close()
            except Exception:
                logger.debug(
                    "Failed to restart websocket after credential change",
                    exc_info=True,
                )
        self._wake_event.set()
        self._ensure_thread()

    def get_session_quote_payload(self, session_id: str | None) -> dict[str, Any]:
        if not session_id:
            return {
                "us_items": [],
                "us_market_status": self.get_market_status([]),
            }

        with self._lock:
            us_items = [
                dict(item) for item in self._session_us_items.get(session_id, [])
            ]

        enriched_us_items = self.enrich_us_items(us_items)
        return {
            "us_items": enriched_us_items,
            "us_market_status": self.get_market_status(enriched_us_items),
        }

    def enrich_us_items(self, us_items: list[dict[str, Any]]) -> list[dict[str, Any]]:
        now = dt.datetime.now(KST)
        session_info = get_us_market_session(now)
        enriched: list[dict[str, Any]] = []

        with self._lock:
            for item in us_items or []:
                ticker = str(item.get("ticker", "")).strip().upper()
                snapshot = self._quote_cache.get(ticker)
                enriched_item = dict(item)
                enriched_item["quote_session"] = session_info.session
                enriched_item["quote_source"] = "balance"
                enriched_item["quote_stale"] = True
                enriched_item["quote_ts"] = None
                enriched_item["quote_tr_key"] = build_us_tr_key(
                    ticker,
                    str(item.get("excg_cd", "")),
                    session_info,
                )
                if snapshot and not snapshot.is_stale(now):
                    enriched_item["now_price"] = snapshot.price
                    enriched_item["quote_source"] = snapshot.source
                    enriched_item["quote_stale"] = False
                    quote_dt = snapshot.quoted_at or snapshot.updated_at
                    if quote_dt is not None:
                        enriched_item["quote_ts"] = quote_dt.isoformat()
                    enriched_item["quote_tr_key"] = snapshot.tr_key
                    avg_price = float(item.get("avg_price") or 0)
                    if avg_price > 0:
                        enriched_item["profit_rt"] = (
                            (snapshot.price - avg_price) / avg_price
                        ) * 100
                enriched.append(enriched_item)
        return enriched

    def get_market_status(
        self, us_items: list[dict[str, Any]] | None = None
    ) -> dict[str, Any]:
        now = dt.datetime.now(KST)
        session_info = get_us_market_session(now)
        items = list(us_items or [])
        fresh_count = 0

        with self._lock:
            for item in items:
                ticker = str(item.get("ticker", "")).strip().upper()
                snapshot = self._quote_cache.get(ticker)
                if snapshot and not snapshot.is_stale(now):
                    fresh_count += 1

        total_count = len(items)
        fallback_count = max(total_count - fresh_count, 0)
        if total_count == 0:
            source_state = "idle"
        elif fresh_count == total_count:
            source_state = "live"
        elif fresh_count > 0:
            source_state = "mixed"
        else:
            source_state = "fallback"

        return {
            "session": session_info.session,
            "is_open": session_info.is_open,
            "uses_day_prefix": session_info.uses_day_prefix,
            "source_state": source_state,
            "tracked_count": total_count,
            "fresh_count": fresh_count,
            "fallback_count": fallback_count,
        }

    def get_ticker_diagnostics(
        self, ticker: str, tr_key: str | None = None
    ) -> dict[str, Any]:
        ticker = str(ticker or "").strip().upper()
        now = dt.datetime.now(KST)
        with self._lock:
            snapshot = self._quote_cache.get(ticker)
            diag = dict(
                self._subscription_diag.get(str(tr_key or "").strip().upper(), {})
            )

        if snapshot:
            diag.setdefault("ticker", ticker)
            diag["last_quote_source"] = snapshot.source
            quote_dt = snapshot.quoted_at or snapshot.updated_at
            if quote_dt is not None:
                diag["last_quote_ts"] = quote_dt.isoformat()
            diag["quote_is_stale"] = snapshot.is_stale(now)
        return diag

    def _ensure_thread(self) -> None:
        if websocket is None:
            logger.warning(
                "websocket-client is not installed; U.S. quote streaming disabled"
            )
            return
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            if not self._desired_keys:
                return
            self._thread = threading.Thread(
                target=self._run_forever,
                name="kis-us-quote-service",
                daemon=True,
            )
            self._thread.start()

    def _run_forever(self) -> None:
        backoff = 1.0
        while not self._stop_event.is_set():
            self._wake_event.wait(timeout=1.0)
            self._wake_event.clear()
            if self._stop_event.is_set():
                break
            with self._lock:
                if not self._desired_keys or not self._app_key or not self._app_secret:
                    continue
                retry_at = self._appkey_conflict_retry_at
            now_ts = time.time()
            if retry_at > now_ts:
                self._wake_event.wait(timeout=min(retry_at - now_ts, 1.0))
                continue
            try:
                self._connect_once()
                backoff = 1.0
            except Exception:
                logger.exception("KIS U.S. quote websocket loop failed")
                time.sleep(backoff)
                backoff = min(backoff * 2, 30.0)

    def _connect_once(self) -> None:
        ws_module = cast(Any, websocket)
        if ws_module is None:
            raise RuntimeError("websocket-client is not installed")
        approval_key = self._get_approval_key()
        if not approval_key:
            raise RuntimeError("Failed to obtain websocket approval key")

        def on_open(ws_app):
            logger.info("KIS U.S. quote websocket connected")
            self._send_subscriptions(ws_app)

        def on_message(ws_app, message: str):
            self._handle_message(ws_app, message)

        def on_error(_ws_app, error):
            logger.warning("KIS U.S. quote websocket error: %s", error)

        def on_close(_ws_app, status_code, close_msg):
            logger.info(
                "KIS U.S. quote websocket closed status=%s msg=%s",
                status_code,
                close_msg,
            )
            with self._lock:
                self._subscribed_keys.clear()
                self._ws = None

        ws_app = ws_module.WebSocketApp(
            self._get_ws_url(),
            on_open=on_open,
            on_message=on_message,
            on_error=on_error,
            on_close=on_close,
        )
        with self._lock:
            self._ws = ws_app
        ws_app.run_forever()

    def _get_approval_key(self) -> str | None:
        with self._lock:
            if self._approval_key:
                return self._approval_key
            app_key = self._app_key
            app_secret = self._app_secret
        if not app_key or not app_secret:
            return None

        headers = {"content-type": "application/json"}
        body = {
            "grant_type": "client_credentials",
            "appkey": app_key,
            "secretkey": app_secret,
        }
        url = f"{config.URL_BASE}/oauth2/Approval"
        response = requests.post(
            url, headers=headers, data=json.dumps(body), timeout=10
        )
        response.raise_for_status()
        approval_key = response.json().get("approval_key")
        with self._lock:
            self._approval_key = approval_key
        return approval_key

    def _get_ws_url(self) -> str:
        url_base = (config.URL_BASE or "").lower()
        if "openapivts" in url_base or config.TRADE_MODE == "paper":
            return WS_PAPER_URL
        return WS_REAL_URL

    def _build_subscribe_message(
        self, approval_key: str, tr_id: str, tr_key: str, tr_type: str = "1"
    ) -> str:
        return json.dumps(
            {
                "header": {
                    "approval_key": approval_key,
                    "custtype": "P",
                    "tr_type": tr_type,
                    "content-type": "utf-8",
                },
                "body": {
                    "input": {
                        "tr_id": tr_id,
                        "tr_key": tr_key,
                    }
                },
            }
        )

    def _send_subscriptions(self, ws_app) -> None:
        with self._lock:
            approval_key = self._approval_key
            desired = dict(self._desired_keys)
            subscribed = set(self._subscribed_keys)
        if not approval_key:
            return

        removed = subscribed - set(desired)
        added = set(desired) - subscribed

        for tr_key in sorted(removed):
            try:
                ws_app.send(
                    self._build_subscribe_message(
                        approval_key, TR_ASKING_PRICE, tr_key, tr_type="2"
                    )
                )
                ws_app.send(
                    self._build_subscribe_message(
                        approval_key, TR_CONTRACT, tr_key, tr_type="2"
                    )
                )
                with self._lock:
                    diag = self._subscription_diag.setdefault(tr_key, {})
                    diag["removed_at"] = dt.datetime.now(KST).isoformat()
            except Exception:
                logger.debug("Failed to unsubscribe %s", tr_key, exc_info=True)

        for tr_key in sorted(added):
            try:
                with self._lock:
                    ticker, excg_cd = desired.get(tr_key, ("", ""))
                    self._subscription_diag[tr_key] = {
                        "ticker": ticker,
                        "excg_cd": excg_cd,
                        "asking_sent_at": dt.datetime.now(KST).isoformat(),
                    }
                ws_app.send(
                    self._build_subscribe_message(approval_key, TR_ASKING_PRICE, tr_key)
                )
                time.sleep(0.05)
                with self._lock:
                    self._subscription_diag.setdefault(tr_key, {})[
                        "contract_sent_at"
                    ] = dt.datetime.now(KST).isoformat()
                ws_app.send(
                    self._build_subscribe_message(approval_key, TR_CONTRACT, tr_key)
                )
                time.sleep(0.05)
            except Exception:
                logger.debug("Failed to subscribe %s", tr_key, exc_info=True)

        with self._lock:
            self._subscribed_keys = set(desired)

    def _handle_message(self, ws_app, data: str) -> None:
        if not data:
            return
        if data[0] in {"0", "1"}:
            recv = data.split("|")
            if len(recv) < 4:
                return
            tr_id = recv[1]
            if tr_id == TR_CONTRACT:
                self._parse_contract_payload(recv[3])
            elif tr_id == TR_ASKING_PRICE:
                self._parse_asking_payload(recv[3])
            return

        try:
            obj = json.loads(data)
        except json.JSONDecodeError:
            logger.debug("Failed to decode websocket message: %s", data)
            return

        header = obj.get("header") or {}
        tr_id = header.get("tr_id")
        if tr_id == "PINGPONG":
            ws_module = cast(Any, websocket)
            if ws_module is None:
                return
            try:
                ws_app.send(data, opcode=ws_module.ABNF.OPCODE_PING)
            except Exception:
                logger.debug("Failed to answer PINGPONG", exc_info=True)
            return

        body = obj.get("body") or {}
        if str(body.get("rt_cd", "0")) != "0":
            msg = body.get("msg1")
            if self._is_appkey_conflict_message(msg):
                self._mark_appkey_conflict()
                try:
                    ws_app.close()
                except Exception:
                    logger.debug("Failed to close websocket after appkey conflict", exc_info=True)
            tr_key = str(header.get("tr_key") or "").strip().upper()
            if tr_key:
                with self._lock:
                    diag = self._subscription_diag.setdefault(tr_key, {})
                    diag["last_response_at"] = dt.datetime.now(KST).isoformat()
                    diag["last_response_ok"] = False
                    diag["last_response_msg"] = msg
            if msg != "ALREADY IN SUBSCRIBE":
                logger.warning("KIS websocket subscription response error: %s", msg)
            return

        tr_key = str(header.get("tr_key") or "").strip().upper()
        if tr_key:
            with self._lock:
                diag = self._subscription_diag.setdefault(tr_key, {})
                diag["last_response_at"] = dt.datetime.now(KST).isoformat()
                diag["last_response_ok"] = True
                diag["last_response_msg"] = body.get("msg1")
                tr_id_text = str(header.get("tr_id") or "").strip().upper()
                if tr_id_text == TR_ASKING_PRICE:
                    diag["asking_ack_at"] = diag["last_response_at"]
                elif tr_id_text == TR_CONTRACT:
                    diag["contract_ack_at"] = diag["last_response_at"]

        self._refresh_subscriptions_if_needed(ws_app)

    def _parse_contract_payload(self, payload: str) -> None:
        fields = payload.split("^")
        rows = len(fields) // CONTRACT_FIELD_COUNT
        if rows <= 0:
            return
        now = dt.datetime.now(KST)
        for idx in range(rows):
            row = fields[idx * CONTRACT_FIELD_COUNT : (idx + 1) * CONTRACT_FIELD_COUNT]
            if len(row) < CONTRACT_FIELD_COUNT:
                continue
            ticker = str(row[1]).strip().upper()
            tr_key = str(row[0]).strip().upper()
            last = self._to_float(row[11])
            if not ticker or last <= 0:
                continue
            quoted_at = self._parse_kst_timestamp(row[6], row[7])
            snapshot = QuoteSnapshot(
                ticker=ticker,
                tr_key=tr_key,
                price=last,
                bid=self._to_float(row[15]) or None,
                ask=self._to_float(row[16]) or None,
                market_type=str(row[25]).strip() or None,
                source="websocket_contract",
                quote_session=self._session_name,
                quoted_at=quoted_at,
                updated_at=now,
            )
            with self._lock:
                self._quote_cache[ticker] = snapshot
                diag = self._subscription_diag.setdefault(tr_key, {})
                diag.setdefault("first_data_at", now.isoformat())
                diag["last_data_at"] = now.isoformat()
                diag["last_data_kind"] = TR_CONTRACT
                diag["ticker"] = ticker

    def _parse_asking_payload(self, payload: str) -> None:
        fields = payload.split("^")
        if len(fields) < ASK_FIELD_COUNT:
            return
        ticker = str(fields[1]).strip().upper()
        tr_key = str(fields[0]).strip().upper()
        bid = self._to_float(fields[11])
        ask = self._to_float(fields[12])
        if not ticker or bid <= 0 or ask <= 0:
            return
        midpoint = (bid + ask) / 2.0
        now = dt.datetime.now(KST)
        quoted_at = self._parse_kst_timestamp(fields[5], fields[6])
        with self._lock:
            existing = self._quote_cache.get(ticker)
            if (
                existing
                and existing.updated_at
                and (now - existing.updated_at).total_seconds() < 5
            ):
                return
            self._quote_cache[ticker] = QuoteSnapshot(
                ticker=ticker,
                tr_key=tr_key,
                price=midpoint,
                bid=bid,
                ask=ask,
                source="websocket_bid_ask_mid",
                quote_session=self._session_name,
                quoted_at=quoted_at,
                updated_at=now,
            )
            diag = self._subscription_diag.setdefault(tr_key, {})
            diag.setdefault("first_data_at", now.isoformat())
            diag["last_data_at"] = now.isoformat()
            diag["last_data_kind"] = TR_ASKING_PRICE
            diag["ticker"] = ticker

    def _parse_kst_timestamp(
        self, ymd: str | None, hms: str | None
    ) -> dt.datetime | None:
        ymd = str(ymd or "").strip()
        hms = str(hms or "").strip()
        if len(ymd) != 8 or len(hms) != 6:
            return None
        try:
            return dt.datetime.strptime(f"{ymd}{hms}", "%Y%m%d%H%M%S").replace(
                tzinfo=KST
            )
        except ValueError:
            return None

    def _refresh_subscriptions_if_needed(self, ws_app) -> None:
        with self._lock:
            before = dict(self._desired_keys)
            self._recompute_targets_locked()
            changed = before != self._desired_keys
        if changed:
            self._send_subscriptions(ws_app)

    def _recompute_targets_locked(self, force_refresh_session: bool = False) -> None:
        now = time.time()
        if force_refresh_session or (now - self._last_session_refresh_at) >= 30:
            session_info = get_us_market_session()
            self._session_name = session_info.session
            self._last_session_refresh_at = now
        else:
            session_info = get_us_market_session()
            self._session_name = session_info.session

        desired: dict[str, tuple[str, str]] = {}
        for holdings in self._session_targets.values():
            for item in holdings:
                ticker = item.get("ticker", "")
                excg_cd = item.get("excg_cd", "")
                tr_key = build_us_tr_key(ticker, excg_cd, session_info)
                if not tr_key:
                    continue
                desired[tr_key] = (ticker, excg_cd)
        self._desired_keys = desired

    @staticmethod
    def _is_appkey_conflict_message(message: object) -> bool:
        return "ALREADY IN USE appkey" in str(message or "")

    def _mark_appkey_conflict(self) -> None:
        with self._lock:
            self._appkey_conflict_retry_at = time.time() + APPKEY_CONFLICT_COOLDOWN_SECONDS
            self._subscribed_keys.clear()

    def _clear_appkey_conflict_cooldown_locked(self) -> None:
        self._appkey_conflict_retry_at = 0.0

    @staticmethod
    def _to_float(value: Any) -> float:
        try:
            if value is None:
                return 0.0
            if isinstance(value, (int, float)):
                return float(value)
            text = str(value).strip().replace(",", "")
            if not text:
                return 0.0
            return float(text)
        except Exception:
            return 0.0
