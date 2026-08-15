from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.routes.toss_proxy import router
from app import toss_proxy_client


class TossProxyRouteTests(unittest.TestCase):
    def setUp(self):
        app = FastAPI()
        app.include_router(router)
        self.client = TestClient(app)

    def test_proxy_is_hidden_when_disabled(self):
        with patch("app.routes.toss_proxy.config.TOSS_PROXY_SERVER_ENABLED", False):
            response = self.client.post(
                "/api/toss-proxy/accounts",
                json={"client_id": "client", "client_secret": "secret"},
            )
        self.assertEqual(response.status_code, 404)

    def test_proxy_requires_bearer_token(self):
        with (
            patch("app.routes.toss_proxy.config.TOSS_PROXY_SERVER_ENABLED", True),
            patch("app.routes.toss_proxy.config.TOSS_PROXY_SERVER_TOKEN", "private-token"),
        ):
            response = self.client.post(
                "/api/toss-proxy/accounts",
                json={"client_id": "client", "client_secret": "secret"},
            )
        self.assertEqual(response.status_code, 401)

    @patch("app.routes.toss_proxy.toss_api_client.get_accounts")
    def test_accounts_are_relayed_without_persisting_credentials(self, get_accounts):
        get_accounts.return_value = [
            {"accountNo": "12345678901", "accountSeq": 1, "accountType": "BROKERAGE"}
        ]
        with (
            patch("app.routes.toss_proxy.config.TOSS_PROXY_SERVER_ENABLED", True),
            patch("app.routes.toss_proxy.config.TOSS_PROXY_SERVER_TOKEN", "private-token"),
        ):
            response = self.client.post(
                "/api/toss-proxy/accounts",
                headers={"Authorization": "Bearer private-token"},
                json={"client_id": "client", "client_secret": "secret"},
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["result"][0]["accountSeq"], 1)
        get_accounts.assert_called_once_with("client", "secret")

    @patch("app.routes.toss_proxy.toss_api_client.get_dashboard_source")
    def test_dashboard_relays_only_read_only_toss_payloads(self, get_dashboard_source):
        get_dashboard_source.return_value = (
            {"result": {"items": []}},
            {"result": {"rate": "1400"}},
        )
        with (
            patch("app.routes.toss_proxy.config.TOSS_PROXY_SERVER_ENABLED", True),
            patch("app.routes.toss_proxy.config.TOSS_PROXY_SERVER_TOKEN", "private-token"),
        ):
            response = self.client.post(
                "/api/toss-proxy/dashboard",
                headers={"Authorization": "Bearer private-token"},
                json={
                    "client_id": "client",
                    "client_secret": "secret",
                    "account_seq": "9",
                },
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["exchange_rate"]["result"]["rate"], "1400")
        get_dashboard_source.assert_called_once_with("client", "secret", "9")


class TossProxyClientTests(unittest.TestCase):
    def test_partial_remote_configuration_is_rejected(self):
        with (
            patch.object(toss_proxy_client.config, "TOSS_PROXY_REMOTE_URL", "https://proxy.example"),
            patch.object(toss_proxy_client.config, "TOSS_PROXY_REMOTE_TOKEN", ""),
        ):
            with self.assertRaisesRegex(RuntimeError, "configured together"):
                toss_proxy_client.is_configured()

    @patch("app.toss_proxy_client.requests.post")
    def test_remote_balance_request_uses_private_bearer_token(self, post):
        response = Mock(status_code=200)
        response.json.return_value = {
            "status": "success",
            "domestic": {"summary": {}, "items": []},
            "overseas": {"us_summary": {}, "us_items": []},
        }
        post.return_value = response
        with (
            patch.object(toss_proxy_client.config, "TOSS_PROXY_REMOTE_URL", "https://proxy.example"),
            patch.object(toss_proxy_client.config, "TOSS_PROXY_REMOTE_TOKEN", "remote-token"),
        ):
            domestic, overseas = toss_proxy_client.get_balances("client", "secret", "1")

        self.assertEqual(domestic["items"], [])
        self.assertEqual(overseas["us_items"], [])
        request = post.call_args
        self.assertEqual(request.args[0], "https://proxy.example/api/toss-proxy/balances")
        self.assertEqual(request.kwargs["headers"]["Authorization"], "Bearer remote-token")
        self.assertEqual(request.kwargs["json"]["account_seq"], "1")


if __name__ == "__main__":
    unittest.main()
