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

    @patch("app.routes.portfolio.api_client.get_realized_profit_summary")
    @patch("app.routes.portfolio.api_client.get_trade_history")
    @patch("app.routes.portfolio.api_client.get_access_token", return_value="token")
    def test_realized_profit_detail_reuses_trade_history_payload(
        self,
        _get_access_token,
        mock_get_trade_history,
        mock_get_realized_profit_summary,
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
        mock_get_realized_profit_summary.assert_not_called()

    @patch("app.routes.portfolio.api_client.get_trade_history")
    @patch("app.routes.portfolio.api_client.get_access_token", return_value="token")
    def test_realized_profit_detail_passes_filters_and_pagination(
        self,
        _get_access_token,
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
        mock_get_trade_history.assert_called_once_with(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
            side_filter="sell",
            market_filter="domestic",
            page=2,
            page_size=5,
            force_refresh=False,
        )

    @patch("app.routes.portfolio.api_client.get_realized_profit_summary")
    @patch("app.routes.portfolio.api_client.get_trade_history")
    @patch("app.routes.portfolio.api_client.get_access_token", return_value="token")
    def test_realized_profit_detail_can_skip_trade_rows_for_summary_only(
        self,
        _get_access_token,
        mock_get_trade_history,
        mock_get_realized_profit_summary,
    ):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")
        mock_get_realized_profit_summary.return_value = {
            "summary": {
                "total_realized_profit_krw": 55555,
                "domestic_realized_profit_krw": 55555,
                "overseas_realized_profit_krw": 0,
                "total_realized_return_rate": 3.21,
            },
            "daily": [],
        }

        response = self.client.get(
            "/api/realized-profit/detail?start=2026-03-01&end=2026-03-31&include_trades=0"
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["summary"]["total_realized_profit_krw"], 55555)
        self.assertEqual(payload["trades"], [])
        mock_get_trade_history.assert_not_called()
        mock_get_realized_profit_summary.assert_called_once()


if __name__ == "__main__":
    unittest.main()
