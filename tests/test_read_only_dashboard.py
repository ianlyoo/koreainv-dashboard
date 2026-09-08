from __future__ import annotations

import importlib
import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app import api_client, config
from app.main import app
from app.session_store import SessionData, active_sessions


class ReadOnlyDashboardTests(unittest.TestCase):
    def test_former_order_endpoints_are_unavailable_even_with_a_session(self):
        session_id = "read-only-regression"
        active_sessions[session_id] = SessionData("key", "secret", "12345678", "01")
        try:
            client = TestClient(app)
            client.cookies.set("session", session_id)
            for prefix in ("/api/scheduled-orders", "/api/central-server/scheduled-orders"):
                cases = (
                    ("GET", prefix),
                    ("POST", prefix),
                    ("POST", f"{prefix}/sync"),
                    ("GET", f"{prefix}/availability"),
                    ("PUT", f"{prefix}/old-order"),
                    ("DELETE", f"{prefix}/old-order"),
                )
                for method, path in cases:
                    with self.subTest(method=method, path=path):
                        response = client.request(method, path, json={})
                        self.assertEqual(response.status_code, 404)
            paths = app.openapi()["paths"]
            self.assertFalse(any("scheduled-orders" in path for path in paths))
            self.assertIn("/api/realized-profit/detail", paths)
            self.assertIn("/api/sync", paths)
        finally:
            active_sessions.pop(session_id, None)

    def test_legacy_order_environment_cannot_enable_a_worker_or_broker_request(self):
        legacy_environment = {
            "CENTRAL_ORDER_SERVER_MODE": "true",
            "CENTRAL_ORDER_EXECUTION_ENABLED": "true",
            "CENTRAL_ORDER_MASTER_KEY": "obsolete-key",
            "CENTRAL_ORDER_SERVER_TOKEN": "obsolete-token",
            "CENTRAL_ORDER_REMOTE_URL": "https://central.invalid",
            "CENTRAL_ORDER_REMOTE_TOKEN": "obsolete-token",
            "CENTRAL_ORDER_POLL_INTERVAL_SECONDS": "1",
        }
        try:
            with patch.dict(os.environ, legacy_environment), patch(
                "app.main.KISUSQuoteService"
            ) as quote_service, patch(
                "requests.sessions.Session.request",
                side_effect=AssertionError("Startup must not contact a broker"),
            ) as network_request:
                importlib.reload(config)
                with TestClient(app):
                    self.assertFalse(hasattr(app.state, "scheduled_order_worker"))
                    self.assertFalse(hasattr(app.state, "scheduled_order_store"))
                    quote_service.return_value.start.assert_called_once_with()
                quote_service.return_value.stop.assert_called_once_with()
                network_request.assert_not_called()
        finally:
            importlib.reload(config)

    def test_broker_mutation_helpers_are_removed_but_read_helpers_remain(self):
        for name in ("place_domestic_order_cash", "cancel_domestic_order"):
            with self.subTest(name=name):
                self.assertFalse(hasattr(api_client, name))
        for name in (
            "get_domestic_orderable_cash",
            "get_domestic_balance",
            "get_overseas_balance",
            "get_domestic_trade_history",
            "get_overseas_trade_history",
        ):
            with self.subTest(name=name):
                self.assertTrue(callable(getattr(api_client, name)))


if __name__ == "__main__":
    unittest.main()
