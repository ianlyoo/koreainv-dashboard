from __future__ import annotations

import unittest
from pathlib import Path
from typing import cast
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient

from app.main import app
from app.session_store import SessionData, active_sessions
from app.version import APP_VERSION


class DashboardSmokeTests(unittest.TestCase):
    client: TestClient = cast(TestClient, cast(object, None))

    def setUp(self):
        active_sessions.clear()
        self.client = TestClient(app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        active_sessions.clear()

    def test_login_page_uses_static_assets_and_no_store_headers(self):
        response = self.client.get("/login")
        self.assertEqual(response.status_code, 200)
        self.assertIn("/static/css/login.css", response.text)
        self.assertIn("/static/js/login.js", response.text)
        self.assertEqual(
            response.headers.get("Cache-Control"),
            "no-store, no-cache, must-revalidate, max-age=0",
        )

    @patch("app.routes.auth_pages.auth.is_setup_complete", return_value=True)
    def test_root_redirects_to_login_without_session(self, _is_setup_complete):
        response = self.client.get("/", follow_redirects=False)
        self.assertEqual(response.status_code, 307)
        self.assertEqual(response.headers.get("location"), "/login")

    @patch("app.routes.auth_pages.auth.is_setup_complete", return_value=True)
    def test_root_renders_with_valid_session(self, _is_setup_complete):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        self.assertIn("Dashboard", response.text)
        self.assertIn(f"/static/css/dashboard.css?v={APP_VERSION}", response.text)
        self.assertIn(f"/static/js/dashboard.js?v={APP_VERSION}", response.text)
        self.assertNotIn("__ASSET_VERSION__", response.text)
        self.assertNotIn("20260312-v1-4-9", response.text)
        self.assertNotIn("20260312-v1-4-7", response.text)

    @patch(
        "app.routes.auth_pages._decrypt_credentials",
        return_value=("key", "secret", "12345678", "01"),
    )
    @patch("app.routes.auth_pages.auth.verify_pin", return_value=True)
    @patch(
        "app.routes.auth_pages.auth.load_settings",
        return_value={"setup_complete": True, "pin_hash": "hashed"},
    )
    def test_login_sets_session_cookie(
        self, _load_settings, _verify_pin, _decrypt_credentials
    ):
        response = self.client.post("/api/login", data={"pin": "123456"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "success")
        self.assertIn("session", response.cookies)
        self.assertEqual(len(active_sessions), 1)

    def test_reset_requires_authentication(self):
        response = self.client.post("/api/reset")
        self.assertEqual(response.status_code, 401)

    def test_templates_no_longer_depend_on_dashboard_auth_storage(self):
        login_html = Path("app/templates/login.html").read_text(encoding="utf-8")
        index_html = Path("app/templates/index.html").read_text(encoding="utf-8")
        self.assertNotIn('sessionStorage.setItem("dashboard_auth"', login_html)
        self.assertNotIn('sessionStorage.getItem("dashboard_auth"', index_html)
        self.assertNotIn("http://localhost:8000/img/", index_html)

    def test_dashboard_template_contains_cash_flip_card_hooks(self):
        index_html = Path("app/templates/index.html").read_text(encoding="utf-8")
        dashboard_js = Path("app/static/js/dashboard.js").read_text(encoding="utf-8")

        self.assertIn('id="cashSummaryCard"', index_html)
        self.assertIn('id="val_jpy_cash"', index_html)
        self.assertIn('onclick="toggleCashCard()"', index_html)
        self.assertIn('aria-pressed="false"', index_html)
        self.assertIn("function toggleCashCard()", dashboard_js)
        self.assertIn("function handleCashCardKeydown(event)", dashboard_js)

    @patch("app.routes.portfolio.api_client.get_domestic_balance", return_value={"items": [], "summary": {}})
    @patch("app.routes.portfolio.api_client.get_overseas_balance", return_value={"us_items": [], "jp_items": []})
    @patch("app.routes.portfolio.api_client.get_access_token", return_value="token")
    def test_sync_manual_refresh_bypasses_quote_cooldown(
        self,
        _get_access_token,
        _get_overseas_balance,
        _get_domestic_balance,
    ):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")
        original_service = app.state.us_quote_service
        quote_service = Mock()
        quote_service.enrich_us_items.return_value = []
        quote_service.get_market_status.return_value = {
            "session": "closed",
            "is_open": False,
            "uses_day_prefix": False,
            "source_state": "idle",
            "tracked_count": 0,
            "fresh_count": 0,
            "fallback_count": 0,
        }
        app.state.us_quote_service = quote_service

        try:
            response = self.client.get("/api/sync?manual_refresh=1")
        finally:
            app.state.us_quote_service = original_service

        self.assertEqual(response.status_code, 200)
        quote_service.sync_session_holdings.assert_called_once_with(
            "test-session",
            "key",
            "secret",
            [],
            force_retry=True,
        )

    def test_dashboard_sync_uses_manual_refresh_flag_only_for_button_trigger(self):
        dashboard_js = Path("app/static/js/dashboard.js").read_text(encoding="utf-8")

        self.assertIn(
            "const syncUrl = manualTrigger ? '/api/sync?manual_refresh=1' : '/api/sync';",
            dashboard_js,
        )

    def test_dashboard_js_escapes_holding_markup_and_resumes_day_market_polling(self):
        dashboard_js = Path("app/static/js/dashboard.js").read_text(encoding="utf-8")

        self.assertIn("function escapeHtml(value)", dashboard_js)
        self.assertIn("onclick='fetchAssetInsight(${onClickArgs})'", dashboard_js)
        self.assertIn(
            "if (hasUsHoldings && lastUsMarketStatus?.session === 'day_market') {",
            dashboard_js,
        )
        self.assertIn("startUsQuotePollingWindow(lastUsMarketStatus);", dashboard_js)


if __name__ == "__main__":
    unittest.main()
