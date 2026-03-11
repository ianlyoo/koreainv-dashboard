from __future__ import annotations

import unittest
from unittest.mock import patch

from app import api_client


class RealizedProfitApiClientTests(unittest.TestCase):
    def setUp(self):
        api_client._realized_cache.clear()

    def tearDown(self):
        api_client._realized_cache.clear()

    def test_normalize_domestic_realized_trade_rows_derives_buy_amount_when_missing(
        self,
    ):
        rows = [
            {
                "trad_dt": "20260311",
                "pdno": "006800",
                "sll_qty": "229",
                "sll_amt": "16877300",
                "rlzt_pfls": "1738925",
                "fee": "1200",
                "tl_tax": "3500",
                "buy_amt": "0",
            }
        ]

        normalized = api_client._normalize_domestic_realized_trade_rows(rows)

        self.assertEqual(len(normalized), 1)
        self.assertEqual(normalized[0]["buy_amount_krw"], 15133675.0)

    def test_attach_realized_profit_to_sell_trade_uses_recovered_buy_amount(self):
        trades = [
            {
                "date": "20260311",
                "market": "KOR",
                "symbol": "006800",
                "ticker": "006800",
                "side": "매도",
                "quantity": 229.0,
                "amount": 16877300.0,
            }
        ]
        domestic_pnl_rows = [
            {
                "date": "20260311",
                "symbol": "006800",
                "quantity": 229.0,
                "amount": 16877300.0,
                "realized_profit_krw": 1738925.0,
                "buy_amount_krw": 15133675.0,
                "realized_return_rate": None,
            }
        ]

        enriched = api_client._attach_realized_profit_to_sell_trades(
            trades,
            domestic_pnl_rows,
            [],
        )

        self.assertAlmostEqual(
            enriched[0]["realized_return_rate"], 11.490434411998407, places=6
        )

    @patch("app.api_client._fetch_trade_profit_rows")
    def test_summary_rate_ignores_profit_without_cost_basis(
        self, mock_fetch_trade_profit_rows
    ):
        mock_fetch_trade_profit_rows.return_value = {
            "domestic": [
                {
                    "date": "20260311",
                    "symbol": "006800",
                    "quantity": 200.0,
                    "amount": 13500000.0,
                    "realized_profit_krw": 8568.0,
                    "buy_amount_krw": 13491432.0,
                    "realized_return_rate": None,
                },
                {
                    "date": "20260311",
                    "symbol": "000660",
                    "quantity": 20.0,
                    "amount": 18980000.0,
                    "realized_profit_krw": 111261.0,
                    "buy_amount_krw": 0.0,
                    "realized_return_rate": None,
                },
            ],
            "overseas": [],
        }

        payload = api_client.get_realized_profit_summary(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
        )

        self.assertEqual(payload["summary"]["total_realized_profit_krw"], 119829.0)
        self.assertAlmostEqual(
            payload["summary"]["total_realized_return_rate"],
            0.06350697242516584,
            places=6,
        )


if __name__ == "__main__":
    unittest.main()
