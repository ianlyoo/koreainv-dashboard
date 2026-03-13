from __future__ import annotations

import unittest
from typing import cast
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app
from app.session_store import SessionData, active_sessions


class MobileApiTests(unittest.TestCase):
    client: TestClient = cast(TestClient, cast(object, None))

    def setUp(self):
        active_sessions.clear()
        self.client = TestClient(app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        active_sessions.clear()

    @patch("app.routes.mobile.auth.is_setup_complete", return_value=True)
    def test_mobile_status_reports_setup_and_authentication(self, _is_setup_complete):
        response = self.client.get("/api/mobile/status")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json(),
            {
                "status": "success",
                "setup_complete": True,
                "authenticated": False,
            },
        )

    @patch(
        "app.routes.mobile.decrypt_credentials_for_session",
        return_value=("key", "secret", "12345678", "01"),
    )
    @patch("app.routes.mobile.auth.verify_pin", return_value=True)
    @patch(
        "app.routes.mobile.auth.load_settings",
        return_value={"setup_complete": True, "pin_hash": "hashed"},
    )
    def test_mobile_login_accepts_json_and_sets_session_cookie(
        self, _load_settings, _verify_pin, _decrypt_credentials
    ):
        response = self.client.post("/api/mobile/login", json={"pin": "123456"})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "success")
        self.assertIn("session", response.cookies)
        self.assertEqual(len(active_sessions), 1)

    def test_mobile_portfolio_summary_requires_authentication(self):
        response = self.client.get("/api/mobile/portfolio-summary")

        self.assertEqual(response.status_code, 401)

    def test_mobile_dashboard_requires_authentication(self):
        response = self.client.get("/api/mobile/dashboard")

        self.assertEqual(response.status_code, 401)

    @patch("app.routes.mobile.api_client.get_overseas_balance")
    @patch("app.routes.mobile.api_client.get_domestic_balance")
    @patch("app.routes.mobile.api_client.get_access_token", return_value="token")
    def test_mobile_portfolio_summary_returns_compact_snapshot(
        self,
        _get_access_token,
        mock_get_domestic_balance,
        mock_get_overseas_balance,
    ):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        mock_get_domestic_balance.return_value = {
            "summary": {
                "cash_balance": 100000,
            },
            "items": [
                {"ticker": "005930", "qty": 2, "avg_price": 70000, "now_price": 75000},
            ],
        }
        mock_get_overseas_balance.return_value = {
            "us_summary": {
                "usd_cash_balance": 50.5,
                "usd_exrt": 1350,
            },
            "jp_summary": {
                "jpy_cash_balance": 1000,
                "jpy_exrt": 900,
            },
            "us_items": [
                {"ticker": "AAPL", "qty": 1, "avg_price": 180.0, "now_price": 200.0, "bass_exrt": 1350},
            ],
            "jp_items": [
                {"ticker": "7203", "qty": 10, "avg_price": 2500.0, "now_price": 2600.0, "bass_exrt": 900},
            ],
        }

        response = self.client.get("/api/mobile/portfolio-summary")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json(),
            {
                "status": "success",
                "summary": {
                    "total_assets_krw": 654000,
                    "total_purchase_krw": 608000,
                    "total_profit_krw": 46000,
                    "total_profit_rate": 7.57,
                    "cash_krw": 100000,
                    "cash_usd": 50.5,
                    "cash_jpy": 1000.0,
                    "domestic_count": 1,
                    "overseas_count": 2,
                },
            },
        )

    @patch("app.routes.mobile.api_client.get_overseas_balance")
    @patch("app.routes.mobile.api_client.get_domestic_balance")
    @patch("app.routes.mobile.api_client.get_access_token", return_value="token")
    def test_mobile_dashboard_returns_summary_holdings_and_distribution(
        self,
        _get_access_token,
        mock_get_domestic_balance,
        mock_get_overseas_balance,
    ):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        mock_get_domestic_balance.return_value = {
            "summary": {"cash_balance": 50000},
            "items": [
                {
                    "ticker": "005930",
                    "name": "Samsung Electronics",
                    "qty": 2,
                    "avg_price": 70000,
                    "now_price": 75000,
                }
            ],
        }
        mock_get_overseas_balance.return_value = {
            "us_summary": {"usd_cash_balance": 10.0},
            "jp_summary": {"jpy_cash_balance": 0.0},
            "us_items": [
                {
                    "ticker": "AAPL",
                    "name": "Apple",
                    "qty": 1,
                    "avg_price": 180.0,
                    "now_price": 200.0,
                    "bass_exrt": 1350,
                }
            ],
            "jp_items": [],
        }

        response = self.client.get("/api/mobile/dashboard")

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "success")
        self.assertIn("summary", payload)
        self.assertIn("holdings", payload)
        self.assertIn("asset_distribution", payload)
        self.assertEqual(len(payload["holdings"]), 2)
        self.assertEqual(payload["holdings"][0]["symbol"], "AAPL")
        self.assertEqual(payload["holdings"][1]["symbol"], "005930")
        self.assertEqual(len(payload["asset_distribution"]), 2)
        self.assertIn("last_synced", payload["summary"])

    @patch("app.routes.mobile.api_client.get_realized_profit_summary")
    @patch("app.routes.mobile.api_client.get_trade_history")
    @patch("app.routes.mobile.api_client.get_access_token", return_value="token")
    def test_mobile_trade_history_returns_realized_summary_and_trades(
        self,
        _get_access_token,
        mock_get_trade_history,
        mock_get_realized_profit_summary,
    ):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        mock_get_trade_history.return_value = {
            "items": [
                {
                    "date": "2026-03-05",
                    "side": "매수",
                    "ticker": "005930",
                    "symbol": "005930",
                    "name": "Samsung Electronics",
                    "market": "KOR",
                    "quantity": 3,
                    "unit_price": 71000,
                    "amount": 213000,
                    "realized_profit_krw": None,
                    "realized_return_rate": None,
                }
            ]
        }
        mock_get_realized_profit_summary.return_value = {
            "summary": {
                "total_realized_profit_krw": 120000,
                "domestic_realized_profit_krw": 120000,
                "overseas_realized_profit_krw": 0,
                "total_realized_return_rate": 5.4,
            }
        }

        response = self.client.get("/api/mobile/trade-history")

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "success")
        self.assertEqual(payload["period"]["label"], "이번 달")
        self.assertEqual(payload["summary"]["total_realized_profit_krw"], 120000)
        self.assertEqual(len(payload["trades"]), 1)
        self.assertEqual(payload["trades"][0]["ticker"], "005930")
        self.assertEqual(payload["trades"][0]["amount_krw"], 213000)

    def test_mobile_logout_clears_session(self):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.post("/api/mobile/logout")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "success")
        self.assertEqual(len(active_sessions), 0)


if __name__ == "__main__":
    unittest.main()
