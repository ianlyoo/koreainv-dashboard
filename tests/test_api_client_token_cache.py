from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi.testclient import TestClient

from app import api_client
from app.main import app
from app.session_store import SessionData, active_sessions


class _ImmediateFuture:
    def __init__(self, value):
        self._value = value

    def result(self):
        return self._value


class _ImmediateExecutor:
    def __init__(self, *args, **kwargs):
        pass

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def submit(self, func, *args):
        return _ImmediateFuture(func(*args))


class AccessTokenCacheTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.user_data_dir = Path(self.temp_dir.name)
        self.path_patch = patch(
            "app.api_client.runtime_paths.get_user_data_dir",
            return_value=str(self.user_data_dir),
        )
        self.path_patch.start()
        api_client._cached_tokens.clear()
        api_client._token_issue_times.clear()
        api_client._token_expiry_times.clear()
        api_client._persisted_token_cache = None

    def tearDown(self):
        api_client._cached_tokens.clear()
        api_client._token_issue_times.clear()
        api_client._token_expiry_times.clear()
        api_client._persisted_token_cache = None
        self.path_patch.stop()
        self.temp_dir.cleanup()

    def _cache_path(self) -> Path:
        return self.user_data_dir / "token_cache.json"

    def _write_cache_entry(
        self,
        app_key: str,
        app_secret: str,
        token: str,
        issued_at: float,
        expires_at: float,
    ) -> None:
        scope_key = api_client._token_scope_key(app_key, app_secret)
        self.user_data_dir.mkdir(parents=True, exist_ok=True)
        self._cache_path().write_text(
            json.dumps(
                {
                    "entries": {
                        scope_key: {
                            "access_token": token,
                            "issued_at": issued_at,
                            "expires_at": expires_at,
                        }
                    }
                }
            ),
            encoding="utf-8",
        )

    def test_reuses_valid_persisted_token_without_post(self):
        self._write_cache_entry("key", "secret", "persisted-token", 1_000.0, 5_000.0)

        with patch("app.api_client.time.time", return_value=2_000.0), patch(
            "app.api_client.requests.post"
        ) as mock_post:
            token = api_client.get_access_token("key", "secret")

        self.assertEqual(token, "persisted-token")
        mock_post.assert_not_called()

    def test_expired_persisted_token_requests_new_token_and_overwrites_cache(self):
        self._write_cache_entry("key", "secret", "expired-token", 1_000.0, 1_100.0)
        response = SimpleNamespace(
            status_code=200,
            json=lambda: {"access_token": "fresh-token", "expires_in": 86400},
        )

        with patch("app.api_client.time.time", return_value=2_000.0), patch(
            "app.api_client.requests.post", return_value=response
        ) as mock_post:
            token = api_client.get_access_token("key", "secret")

        self.assertEqual(token, "fresh-token")
        mock_post.assert_called_once()
        payload = json.loads(self._cache_path().read_text(encoding="utf-8"))
        scope_key = api_client._token_scope_key("key", "secret")
        self.assertEqual(payload["entries"][scope_key]["access_token"], "fresh-token")

    def test_invalidate_access_token_removes_persisted_scope_entry(self):
        self._write_cache_entry("key", "secret", "persisted-token", 1_000.0, 5_000.0)

        with patch("app.api_client.time.time", return_value=2_000.0):
            self.assertEqual(api_client.get_access_token("key", "secret"), "persisted-token")

        api_client._invalidate_access_token("key", "secret")

        payload = json.loads(self._cache_path().read_text(encoding="utf-8"))
        self.assertEqual(payload["entries"], {})

    def test_corrupt_cache_file_is_ignored(self):
        self.user_data_dir.mkdir(parents=True, exist_ok=True)
        self._cache_path().write_text("{not-json", encoding="utf-8")
        response = SimpleNamespace(
            status_code=200,
            json=lambda: {"access_token": "fresh-token", "expires_in": 86400},
        )

        with patch("app.api_client.time.time", return_value=2_000.0), patch(
            "app.api_client.requests.post", return_value=response
        ) as mock_post:
            token = api_client.get_access_token("key", "secret")

        self.assertEqual(token, "fresh-token")
        mock_post.assert_called_once()

    @patch("app.api_client.get_access_token", return_value="fresh-token")
    @patch("app.api_client._invalidate_access_token")
    @patch("app.api_client.requests.get")
    def test_domestic_orderable_cash_retries_once_on_token_error(
        self,
        mock_get,
        mock_invalidate,
        mock_get_access_token,
    ):
        mock_get.side_effect = [
            SimpleNamespace(status_code=401, json=lambda: {"msg_cd": "EGW00123", "msg1": "expired token"}),
            SimpleNamespace(status_code=200, json=lambda: {"output": {"ord_psbl_cash": "12345"}}),
        ]

        amount, source = api_client.get_domestic_orderable_cash(
            "stale-token", "key", "secret", "12345678", "01"
        )

        self.assertEqual(amount, 12345)
        self.assertEqual(source, "inquire-psbl-order.ord_psbl_cash")
        mock_invalidate.assert_called_once_with("key", "secret")
        mock_get_access_token.assert_called_once_with("key", "secret")
        self.assertEqual(mock_get.call_count, 2)
        self.assertEqual(mock_get.call_args_list[1].kwargs["headers"]["authorization"], "Bearer fresh-token")

    @patch("app.api_client.get_access_token", return_value="fresh-token")
    @patch("app.api_client._invalidate_access_token")
    @patch("app.api_client.requests.get")
    def test_domestic_orderable_cash_only_retries_once_on_repeated_token_error(
        self,
        mock_get,
        mock_invalidate,
        mock_get_access_token,
    ):
        mock_get.side_effect = [
            SimpleNamespace(status_code=401, json=lambda: {"msg_cd": "EGW00123", "msg1": "expired token"}),
            SimpleNamespace(status_code=401, json=lambda: {"msg_cd": "EGW00123", "msg1": "expired token"}),
        ]

        amount, source = api_client.get_domestic_orderable_cash(
            "stale-token", "key", "secret", "12345678", "01"
        )

        self.assertEqual(amount, 0)
        self.assertEqual(source, "psbl_api_http_error")
        mock_invalidate.assert_called_once_with("key", "secret")
        mock_get_access_token.assert_called_once_with("key", "secret")
        self.assertEqual(mock_get.call_count, 2)

    @patch("app.api_client.get_access_token", return_value="fresh-token")
    @patch("app.api_client._invalidate_access_token")
    @patch("app.api_client.get_domestic_orderable_cash", return_value=(0, "none"))
    @patch("app.api_client.requests.get")
    def test_domestic_balance_retries_once_on_token_error(
        self,
        mock_get,
        _mock_orderable,
        mock_invalidate,
        mock_get_access_token,
    ):
        mock_get.side_effect = [
            SimpleNamespace(status_code=401, json=lambda: {"msg_cd": "EGW00123", "msg1": "expired token"}),
            SimpleNamespace(
                status_code=200,
                json=lambda: {
                    "output1": [
                        {
                            "pdno": "005930",
                            "prdt_name": "Samsung Electronics",
                            "hldg_qty": "1",
                            "pchs_avg_pric": "70000",
                            "prpr": "71000",
                        }
                    ],
                    "output2": [
                        {
                            "pchs_amt_smtl_amt": "70000",
                            "evlu_amt_smtl_amt": "71000",
                            "evlu_pfls_smtl_amt": "1000",
                        }
                    ],
                },
            ),
        ]

        result = api_client.get_domestic_balance(
            "stale-token", "key", "secret", "12345678", "01"
        )
        items_raw = result.get("items", []) if isinstance(result, dict) else []
        items = [item for item in items_raw if isinstance(item, dict)] if isinstance(items_raw, list) else []

        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["ticker"], "005930")
        mock_invalidate.assert_called_once_with("key", "secret")
        mock_get_access_token.assert_called_once_with("key", "secret")
        self.assertEqual(mock_get.call_count, 2)

    @patch("app.api_client.ThreadPoolExecutor", _ImmediateExecutor)
    @patch("app.api_client.get_access_token", return_value="fresh-token")
    @patch("app.api_client._invalidate_access_token")
    @patch("app.api_client.requests.get")
    def test_overseas_balance_nested_requests_use_refreshed_token(
        self,
        mock_get,
        mock_invalidate,
        mock_get_access_token,
    ):
        mock_get.side_effect = [
            SimpleNamespace(status_code=401, json=lambda: {"msg_cd": "EGW00123", "msg1": "expired token"}),
            SimpleNamespace(
                status_code=200,
                headers={"tr_cont": ""},
                json=lambda: {"rt_cd": "0", "output1": [], "output2": [], "output3": {}},
            ),
            SimpleNamespace(status_code=200, json=lambda: {"rt_cd": "0", "output": {"ovrs_ord_psbl_amt": "321.5"}}),
            SimpleNamespace(
                status_code=200,
                headers={"tr_cont": ""},
                json=lambda: {"rt_cd": "0", "output1": [], "output2": [], "output3": {}},
            ),
            SimpleNamespace(status_code=200, json=lambda: {"rt_cd": "0", "output": {"ovrs_ord_psbl_amt": "0"}}),
        ]

        result = api_client.get_overseas_balance(
            "stale-token", "key", "secret", "12345678", "01"
        )

        self.assertIn("us_summary", result)
        self.assertEqual(mock_invalidate.call_count, 1)
        mock_get_access_token.assert_called_once_with("key", "secret")
        self.assertEqual(mock_get.call_args_list[2].kwargs["headers"]["authorization"], "Bearer fresh-token")


class ResetClearsTokenCacheTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.user_data_dir = Path(self.temp_dir.name)
        self.client = TestClient(app)
        self.client.__enter__()
        self.path_patches = [
            patch(
                "app.api_client.runtime_paths.get_user_data_dir",
                return_value=str(self.user_data_dir),
            ),
            patch(
                "app.auth.runtime_paths.get_user_data_dir",
                return_value=str(self.user_data_dir),
            ),
        ]
        for patcher in self.path_patches:
            patcher.start()
        active_sessions.clear()
        api_client._cached_tokens.clear()
        api_client._token_issue_times.clear()
        api_client._token_expiry_times.clear()
        api_client._persisted_token_cache = None

    def tearDown(self):
        self.client.__exit__(None, None, None)
        active_sessions.clear()
        api_client._cached_tokens.clear()
        api_client._token_issue_times.clear()
        api_client._token_expiry_times.clear()
        api_client._persisted_token_cache = None
        for patcher in reversed(self.path_patches):
            patcher.stop()
        self.temp_dir.cleanup()

    def test_reset_endpoint_removes_persisted_token_cache(self):
        self.user_data_dir.mkdir(parents=True, exist_ok=True)
        token_cache = self.user_data_dir / "token_cache.json"
        token_cache.write_text(json.dumps({"entries": {"scope": {"access_token": "token"}}}), encoding="utf-8")
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        with patch("app.routes.auth_pages.auth.delete_settings", return_value=True):
            response = self.client.post("/api/reset")

        self.assertEqual(response.status_code, 200)
        self.assertFalse(token_cache.exists())


if __name__ == "__main__":
    unittest.main()
