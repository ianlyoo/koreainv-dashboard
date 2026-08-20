from __future__ import annotations

import unittest
from unittest.mock import patch

from app.services.trade_history_aggregation import fetch_aggregated_trade_history
from app.session_store import AccountCredential


class MultiAccountTradeHistoryTests(unittest.TestCase):
    def setUp(self):
        self.accounts = [
            AccountCredential.make("key-main", "secret", "11111111", "01", "KIS 주계좌"),
            AccountCredential.make("key-sub", "secret", "22222222", "01", "KIS 부계좌"),
            AccountCredential.make(
                "toss-client", "toss-secret", "1", "", "토스계좌", broker="toss"
            ),
        ]

    @patch("app.services.trade_history_aggregation.toss_proxy_client.is_configured", return_value=False)
    @patch("app.services.trade_history_aggregation.toss_api_client.get_trade_history")
    @patch("app.services.trade_history_aggregation.api_client.get_trade_history")
    @patch("app.services.trade_history_aggregation.api_client.get_access_token")
    def test_integrated_history_merges_three_accounts_and_keeps_account_labels(
        self, get_token, get_kis_history, get_toss_history, _proxy_configured
    ):
        get_token.side_effect = lambda key, _secret: f"token-{key}"

        def kis_payload(_token, _key, _secret, cano, _product, *_args, **_kwargs):
            profit = 1000.0 if cano == "11111111" else 2000.0
            return {
                "summary": {
                    "domestic_realized_profit_krw": profit,
                    "overseas_realized_profit_krw": 0.0,
                    "total_realized_profit_krw": profit,
                    "total_realized_return_rate": 10.0,
                    "total_buy_amount_krw": profit * 10,
                },
                "daily": [],
                "items": [
                    {
                        "date": "20260801",
                        "time": "090000",
                        "side": "매도",
                        "symbol": cano[-4:],
                        "name": cano[-4:],
                        "market": "KOR",
                    }
                ],
                "pagination": {"total_pages": 1},
            }

        get_kis_history.side_effect = kis_payload
        get_toss_history.return_value = {
            "summary": {
                "domestic_realized_profit_krw": 500.0,
                "overseas_realized_profit_krw": 0.0,
                "total_buy_amount_krw": 1000.0,
            },
            "daily": [
                {
                    "date": "20260802",
                    "domestic_realized_profit_krw": 500.0,
                    "overseas_realized_profit_krw": 0.0,
                    "total_realized_profit_krw": 500.0,
                }
            ],
            "items": [
                {
                    "date": "20260802",
                    "time": "100000",
                    "side": "매수",
                    "symbol": "AAPL",
                    "name": "AAPL",
                    "market": "NASD",
                }
            ],
            "profit_available": True,
            "profit_complete": True,
            "profit_estimated": True,
        }

        payload = fetch_aggregated_trade_history(
            self.accounts,
            "20260801",
            "20260815",
            page_size=100,
        )

        self.assertEqual(payload["summary"]["total_realized_profit_krw"], 3500.0)
        self.assertEqual(payload["summary"]["total_realized_return_rate"], 3500 / 31000 * 100)
        self.assertEqual(len(payload["items"]), 3)
        self.assertEqual(
            {row["account_label"] for row in payload["items"]},
            {"KIS 주계좌", "KIS 부계좌", "토스계좌"},
        )
        self.assertTrue(payload["profit_available"])
        self.assertTrue(payload["profit_complete"])
        self.assertTrue(payload["profit_estimated"])

    @patch("app.services.trade_history_aggregation.toss_proxy_client.is_configured", return_value=False)
    @patch("app.services.trade_history_aggregation.toss_api_client.get_trade_history")
    def test_toss_account_filter_returns_trades_but_marks_profit_unavailable(
        self, get_toss_history, _proxy_configured
    ):
        get_toss_history.return_value = {
            "summary": {
                "domestic_realized_profit_krw": 300.0,
                "overseas_realized_profit_krw": 0.0,
                "total_buy_amount_krw": 1000.0,
            },
            "daily": [],
            "items": [{"date": "20260802", "side": "매수", "market": "KOR"}],
            "profit_available": True,
            "profit_complete": True,
            "profit_estimated": True,
        }
        toss_account = self.accounts[2]

        payload = fetch_aggregated_trade_history(
            self.accounts,
            "20260801",
            "20260815",
            account_id=toss_account.account_id,
        )

        self.assertEqual(payload["selected_account_ids"], [toss_account.account_id])
        self.assertTrue(payload["profit_available"])
        self.assertTrue(payload["profit_complete"])
        self.assertTrue(payload["profit_estimated"])
        self.assertEqual(payload["summary"]["total_realized_profit_krw"], 300.0)
        self.assertEqual(payload["items"][0]["account_label"], "토스계좌")

    def test_unknown_account_filter_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Unknown account_id"):
            fetch_aggregated_trade_history(
                self.accounts,
                "20260801",
                "20260815",
                account_id="missing",
            )


if __name__ == "__main__":
    unittest.main()
