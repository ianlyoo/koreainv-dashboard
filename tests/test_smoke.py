from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app
from app.session_store import SessionData, active_sessions


class DashboardSmokeTests(unittest.TestCase):
    def setUp(self):
        active_sessions.clear()
        self.client = TestClient(app)

    def tearDown(self):
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
        self.assertIn("/static/css/dashboard.css", response.text)
        self.assertIn("/static/js/dashboard.js", response.text)

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


if __name__ == "__main__":
    unittest.main()
