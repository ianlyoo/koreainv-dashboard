from __future__ import annotations

import unittest
from typing import cast
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app
from app.session_store import SessionData, active_sessions


class PortfolioTradeHistoryDetailTests(unittest.TestCase):
    client: TestClient = cast(TestClient, cast(object, None))

    def setUp(self):
        active_sessions.clear()
        self.client = TestClient(app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        active_sessions.clear()

    @patch("app.routes.portfolio.fetch_aggregated_trade_history")
    def test_realized_profit_detail_reuses_trade_history_payload(
        self,
        mock_get_trade_history,
    ):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        mock_get_trade_history.return_value = {
            "summary": {
                "total_realized_profit_krw": 120000,
                "domestic_realized_profit_krw": 120000,
                "overseas_realized_profit_krw": 0,
                "total_realized_return_rate": 5.4,
                "trade_days": 1,
            },
            "daily": [
                {
                    "date": "20260305",
                    "domestic_realized_profit_krw": 120000,
                    "overseas_realized_profit_krw": 0,
                    "total_realized_profit_krw": 120000,
                }
            ],
            "items": [
                {
                    "date": "20260305",
                    "side": "매도",
                    "ticker": "005930",
                    "symbol": "005930",
                    "name": "Samsung Electronics",
                    "market": "KOR",
                    "quantity": 3,
                    "unit_price": 71000,
                    "amount": 213000,
                    "currency": "KRW",
                    "realized_profit_krw": 120000,
                    "realized_return_rate": 5.4,
                }
            ],
        }

        response = self.client.get(
            "/api/realized-profit/detail?start=2026-03-01&end=2026-03-31"
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "success")
        self.assertEqual(payload["summary"]["total_realized_profit_krw"], 120000)
        self.assertEqual(payload["daily"][0]["total_realized_profit_krw"], 120000)
        self.assertEqual(payload["trades"][0]["ticker"], "005930")

    @patch("app.routes.portfolio.fetch_aggregated_trade_history")
    def test_realized_profit_detail_passes_filters_and_pagination(
        self,
        mock_get_trade_history,
    ):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")
        mock_get_trade_history.return_value = {
            "summary": {
                "total_realized_profit_krw": 120000,
                "domestic_realized_profit_krw": 120000,
                "overseas_realized_profit_krw": 0,
                "total_realized_return_rate": 5.4,
            },
            "daily": [],
            "items": [],
            "pagination": {"page": 2, "page_size": 5, "total_items": 7, "total_pages": 2},
            "filters": {"side": "sell", "market": "domestic"},
        }

        response = self.client.get(
            "/api/realized-profit/detail?start=2026-03-01&end=2026-03-31&side=sell&market=domestic&page=2&page_size=5"
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["pagination"]["page"], 2)
        self.assertEqual(payload["filters"]["side"], "sell")
        call = mock_get_trade_history.call_args
        self.assertEqual(call.args[1:3], ("20260301", "20260331"))
        self.assertEqual(call.kwargs["side"], "sell")
        self.assertEqual(call.kwargs["market"], "domestic")
        self.assertEqual(call.kwargs["page"], 2)
        self.assertEqual(call.kwargs["page_size"], 5)

    @patch("app.routes.portfolio.fetch_aggregated_trade_history")
    def test_realized_profit_detail_can_skip_trade_rows_for_summary_only(
        self,
        mock_get_trade_history,
    ):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")
        mock_get_trade_history.return_value = {
            "summary": {
                "total_realized_profit_krw": 55555,
                "domestic_realized_profit_krw": 55555,
                "overseas_realized_profit_krw": 0,
                "total_realized_return_rate": 3.21,
            },
            "daily": [],
            "items": [],
        }

        response = self.client.get(
            "/api/realized-profit/detail?start=2026-03-01&end=2026-03-31&include_trades=0"
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["summary"]["total_realized_profit_krw"], 55555)
        self.assertEqual(payload["trades"], [])
        self.assertFalse(mock_get_trade_history.call_args.kwargs["include_trades"])


if __name__ == "__main__":
    unittest.main()
