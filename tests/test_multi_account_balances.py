from __future__ import annotations

import unittest
from unittest.mock import patch

from app.services.balance_aggregation import fetch_aggregated_balances
from app.session_store import AccountCredential, accounts_metadata, build_account_id


class MultiAccountBalanceTests(unittest.TestCase):
    def setUp(self):
        self.accounts = [
            AccountCredential.make("key-a", "secret-a", "11111111", "01", "메인"),
            AccountCredential.make("key-b", "secret-b", "22222222", "01", "세컨드"),
        ]

    def test_account_metadata_masks_account_number_and_uses_opaque_id(self):
        metadata = accounts_metadata(self.accounts)

        self.assertEqual(metadata[0]["cano_masked"], "****1111")
        self.assertNotIn("11111111", str(metadata[0]))
        self.assertNotIn("11111111", build_account_id("11111111", "01"))
        self.assertTrue(metadata[0]["is_primary"])
        self.assertFalse(metadata[1]["is_primary"])

    @patch("app.services.balance_aggregation.api_client.get_overseas_balance")
    @patch("app.services.balance_aggregation.api_client.get_domestic_balance")
    @patch("app.services.balance_aggregation.api_client.get_access_token")
    def test_each_account_uses_its_own_token_and_balances_are_merged(
        self, get_token, get_domestic, get_overseas
    ):
        get_token.side_effect = lambda app_key, _secret: f"token-{app_key}"
        get_domestic.side_effect = lambda token, _key, _secret, cano, _product: {
            "summary": {
                "cash_balance": 100 if cano == "11111111" else 200,
                "orderable_cash": 80 if cano == "11111111" else 150,
            },
            "items": [{"ticker": cano[-4:], "qty": 1}],
        }
        get_overseas.side_effect = lambda token, _key, _secret, cano, _product: {
            "us_summary": {
                "usd_cash_balance": 1 if cano == "11111111" else 2,
                "usd_orderable_cash": 0.5 if cano == "11111111" else 1.5,
                "usd_exrt": 1400,
            },
            "jp_summary": {},
            "us_items": [],
            "jp_items": [],
        }

        payload = fetch_aggregated_balances(self.accounts)

        self.assertEqual(payload["domestic"]["summary"]["cash_balance"], 300)
        self.assertEqual(payload["domestic"]["summary"]["orderable_cash"], 230)
        self.assertEqual(payload["overseas"]["us_summary"]["usd_cash_balance"], 3)
        self.assertEqual(payload["overseas"]["us_summary"]["usd_exrt"], 1400)
        self.assertEqual(
            {item["account_label"] for item in payload["domestic"]["items"]},
            {"메인", "세컨드"},
        )
        domestic_tokens = {call.args[0] for call in get_domestic.call_args_list}
        overseas_tokens = {call.args[0] for call in get_overseas.call_args_list}
        self.assertEqual(domestic_tokens, {"token-key-a", "token-key-b"})
        self.assertEqual(overseas_tokens, {"token-key-a", "token-key-b"})
        self.assertEqual(payload["account_errors"], [])

    @patch("app.services.balance_aggregation.api_client.get_access_token")
    def test_failed_account_is_reported_instead_of_silently_omitted(self, get_token):
        get_token.side_effect = ["token-a", None]

        with patch(
            "app.services.balance_aggregation.api_client.get_domestic_balance",
            return_value={"summary": {"cash_balance": 100}, "items": []},
        ), patch(
            "app.services.balance_aggregation.api_client.get_overseas_balance",
            return_value={"us_summary": {}, "jp_summary": {}, "us_items": [], "jp_items": []},
        ):
            payload = fetch_aggregated_balances(self.accounts)

        self.assertEqual(len(payload["account_errors"]), 1)
        self.assertIn(
            payload["account_errors"][0]["account_label"], {"메인", "세컨드"}
        )


if __name__ == "__main__":
    unittest.main()
