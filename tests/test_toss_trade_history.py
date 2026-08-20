from __future__ import annotations

import unittest
from unittest.mock import patch

from app import toss_api_client


class TossTradeHistoryTests(unittest.TestCase):
    def test_normalize_orders_keeps_only_filled_rows_and_converts_usd(self):
        rows = toss_api_client._normalize_order_rows(
            [
                {
                    "orderId": "filled",
                    "symbol": "AAPL",
                    "side": "SELL",
                    "currency": "USD",
                    "orderedAt": "2026-08-02T09:00:00+09:00",
                    "execution": {
                        "filledQuantity": "2",
                        "averageFilledPrice": "200",
                        "filledAmount": "400",
                        "filledAt": "2026-08-02T09:01:00+09:00",
                    },
                },
                {
                    "orderId": "empty",
                    "symbol": "005930",
                    "side": "BUY",
                    "currency": "KRW",
                    "orderedAt": "2026-08-03T09:00:00+09:00",
                    "execution": {"filledQuantity": "0"},
                },
            ],
            usd_exchange_rate=1400,
            start_date="2026-08-01",
            end_date="2026-08-15",
        )

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["side"], "매도")
        self.assertEqual(rows[0]["amount_krw"], 560000)
        self.assertIsNone(rows[0]["realized_profit_krw"])

    @patch("app.toss_api_client._get_usd_exchange_rate", return_value=1400)
    @patch("app.toss_api_client._authorized_get")
    def test_trade_history_follows_closed_order_cursor(self, authorized_get, _rate):
        authorized_get.side_effect = [
            {
                "result": {
                    "orders": [
                        {
                            "orderId": "one",
                            "symbol": "005930",
                            "side": "BUY",
                            "currency": "KRW",
                            "orderedAt": "2026-08-01T09:00:00+09:00",
                            "execution": {
                                "filledQuantity": "1",
                                "averageFilledPrice": "70000",
                                "filledAmount": "70000",
                                "filledAt": "2026-08-01T09:00:01+09:00",
                            },
                        }
                    ],
                    "hasNext": True,
                    "nextCursor": "next",
                }
            },
            {"result": {"orders": [], "hasNext": False, "nextCursor": None}},
        ]

        payload = toss_api_client.get_trade_history(
            "client", "secret", "1", "20260801", "20260815"
        )

        self.assertEqual(len(payload["items"]), 1)
        self.assertEqual(authorized_get.call_count, 2)
        self.assertEqual(
            authorized_get.call_args_list[1].kwargs["params"]["cursor"], "next"
        )


if __name__ == "__main__":
    unittest.main()
