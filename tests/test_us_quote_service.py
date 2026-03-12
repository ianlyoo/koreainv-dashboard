from __future__ import annotations

import datetime as dt
import json
import unittest
from unittest import mock
from zoneinfo import ZoneInfo

from app.services.kis_us_quote_service import KISUSQuoteService, QuoteSnapshot
from app.services.us_market_session import build_us_tr_key, get_us_market_session


KST = ZoneInfo("Asia/Seoul")


class USMarketSessionTests(unittest.TestCase):
    def test_day_market_uses_rbaq_prefix(self):
        now = dt.datetime(2026, 3, 10, 10, 30, tzinfo=KST)
        session = get_us_market_session(now)
        self.assertEqual(session.session, "day_market")
        self.assertTrue(session.uses_day_prefix)
        self.assertEqual(build_us_tr_key("AAPL", "NASD", session), "RBAQAAPL")

    def test_day_market_starts_at_9am_kst_during_dst(self):
        now = dt.datetime(2026, 3, 10, 9, 30, tzinfo=KST)
        session = get_us_market_session(now)
        self.assertEqual(session.session, "day_market")
        self.assertTrue(session.uses_day_prefix)

    def test_day_market_still_closed_before_10am_kst_outside_dst(self):
        now = dt.datetime(2026, 2, 10, 9, 30, tzinfo=KST)
        session = get_us_market_session(now)
        self.assertEqual(session.session, "closed")
        self.assertFalse(session.uses_day_prefix)

    def test_regular_market_uses_dnas_prefix(self):
        now = dt.datetime(2026, 3, 10, 23, 0, tzinfo=KST)
        session = get_us_market_session(now)
        self.assertEqual(session.session, "regular")
        self.assertFalse(session.uses_day_prefix)
        self.assertEqual(build_us_tr_key("AAPL", "NASD", session), "DNASAAPL")


class USQuoteServiceTests(unittest.TestCase):
    def test_appkey_conflict_message_starts_retry_cooldown(self):
        service = KISUSQuoteService()
        ws_app = mock.Mock()
        message = json.dumps(
            {
                "header": {"tr_key": "RBAQAAPL", "tr_id": "HDFSASP0"},
                "body": {"rt_cd": "1", "msg1": "ALREADY IN USE appkey"},
            }
        )

        with mock.patch("app.services.kis_us_quote_service.time.time", return_value=100.0):
            service._handle_message(ws_app, message)

        self.assertEqual(service._appkey_conflict_retry_at, 280.0)
        ws_app.close.assert_called_once()

    def test_sync_session_holdings_preserves_conflict_cooldown_without_force_retry(self):
        service = KISUSQuoteService()
        service._appkey_conflict_retry_at = 280.0

        with mock.patch.object(service, "_ensure_thread"):
            service.sync_session_holdings("session-1", "key", "secret", [], force_retry=False)

        self.assertEqual(service._appkey_conflict_retry_at, 280.0)

    def test_sync_session_holdings_force_retry_clears_conflict_cooldown(self):
        service = KISUSQuoteService()
        service._appkey_conflict_retry_at = 280.0

        with mock.patch.object(service, "_ensure_thread"):
            service.sync_session_holdings("session-1", "key", "secret", [], force_retry=True)

        self.assertEqual(service._appkey_conflict_retry_at, 0.0)

    def test_enrich_us_items_prefers_fresh_websocket_quote(self):
        service = KISUSQuoteService()
        service._quote_cache["AAPL"] = QuoteSnapshot(
            ticker="AAPL",
            tr_key="RBAQAAPL",
            price=201.25,
            source="websocket_contract",
            quote_session="day_market",
            quoted_at=dt.datetime.now(KST),
            updated_at=dt.datetime.now(KST),
        )

        items = [
            {
                "ticker": "AAPL",
                "excg_cd": "NASD",
                "avg_price": 150.0,
                "now_price": 190.0,
                "profit_rt": 0.0,
            }
        ]

        enriched = service.enrich_us_items(items)

        self.assertEqual(len(enriched), 1)
        self.assertEqual(enriched[0]["now_price"], 201.25)
        self.assertEqual(enriched[0]["quote_source"], "websocket_contract")
        self.assertFalse(enriched[0]["quote_stale"])
        self.assertEqual(enriched[0]["quote_tr_key"], "RBAQAAPL")

    def test_enrich_us_items_keeps_balance_price_when_cache_missing(self):
        service = KISUSQuoteService()
        items = [
            {
                "ticker": "MSFT",
                "excg_cd": "NASD",
                "avg_price": 300.0,
                "now_price": 305.0,
            }
        ]

        enriched = service.enrich_us_items(items)

        self.assertEqual(enriched[0]["now_price"], 305.0)
        self.assertEqual(enriched[0]["quote_source"], "balance")
        self.assertTrue(enriched[0]["quote_stale"])


if __name__ == "__main__":
    unittest.main()
