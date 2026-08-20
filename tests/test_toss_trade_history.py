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

    def test_estimate_uses_prior_buys_moving_average_and_execution_costs(self):
        rows = toss_api_client._normalize_order_rows(
            [
                {
                    "orderId": "buy-old-1",
                    "symbol": "005930",
                    "side": "BUY",
                    "currency": "KRW",
                    "execution": {
                        "filledQuantity": "10",
                        "averageFilledPrice": "100",
                        "filledAmount": "1000",
                        "commission": "10",
                        "tax": "0",
                        "filledAt": "2026-06-01T09:00:00+09:00",
                    },
                },
                {
                    "orderId": "buy-old-2",
                    "symbol": "005930",
                    "side": "BUY",
                    "currency": "KRW",
                    "execution": {
                        "filledQuantity": "10",
                        "averageFilledPrice": "200",
                        "filledAmount": "2000",
                        "commission": "20",
                        "tax": "0",
                        "filledAt": "2026-07-01T09:00:00+09:00",
                    },
                },
                {
                    "orderId": "sell-selected",
                    "symbol": "005930",
                    "side": "SELL",
                    "currency": "KRW",
                    "execution": {
                        "filledQuantity": "5",
                        "averageFilledPrice": "250",
                        "filledAmount": "1250",
                        "commission": "5",
                        "tax": "10",
                        "filledAt": "2026-08-02T09:00:00+09:00",
                    },
                },
            ],
            usd_exchange_rate=1400,
            start_date="0001-01-01",
            end_date="2026-08-31",
        )

        result = toss_api_client._estimate_realized_profit(
            rows,
            usd_exchange_rate=1400,
            start_date="2026-08-01",
            end_date="2026-08-31",
        )

        sell = next(row for row in rows if row["order_no"] == "sell-selected")
        self.assertEqual(sell["realized_profit_krw"], 477.5)
        self.assertTrue(sell["realized_profit_estimated"])
        self.assertAlmostEqual(result["summary"]["total_buy_amount_krw"], 757.5)
        self.assertAlmostEqual(result["summary"]["total_realized_profit_krw"], 477.5)
        self.assertTrue(result["profit_available"])
        self.assertTrue(result["profit_complete"])

    def test_estimate_leaves_sell_unpriced_when_purchase_basis_is_missing(self):
        rows = [
            {
                "date": "20260802",
                "time": "090000",
                "side": "매도",
                "symbol": "AAPL",
                "currency": "USD",
                "quantity": 1.0,
                "amount_native": 200.0,
                "commission_native": 1.0,
                "tax_native": 0.0,
                "order_no": "transferred-sell",
            }
        ]

        result = toss_api_client._estimate_realized_profit(
            rows,
            usd_exchange_rate=1400,
            start_date="2026-08-01",
            end_date="2026-08-31",
        )

        self.assertIsNone(rows[0].get("realized_profit_krw"))
        self.assertEqual(rows[0]["profit_estimate_reason"], "매수 원가 이력 부족")
        self.assertFalse(result["profit_available"])
        self.assertFalse(result["profit_complete"])
        self.assertEqual(result["unpriced_sell_count"], 1)

    def test_estimate_leaves_us_sell_unpriced_when_exchange_rate_is_missing(self):
        rows = [
            {
                "date": "20260701",
                "time": "090000",
                "side": "매수",
                "symbol": "AAPL",
                "currency": "USD",
                "quantity": 1.0,
                "amount_native": 100.0,
                "order_no": "buy",
            },
            {
                "date": "20260802",
                "time": "090000",
                "side": "매도",
                "symbol": "AAPL",
                "currency": "USD",
                "quantity": 1.0,
                "amount_native": 120.0,
                "order_no": "sell",
            },
        ]

        result = toss_api_client._estimate_realized_profit(
            rows,
            usd_exchange_rate=0.0,
            start_date="2026-08-01",
            end_date="2026-08-31",
        )

        self.assertIsNone(rows[1].get("realized_profit_krw"))
        self.assertEqual(rows[1]["profit_estimate_reason"], "원화 환산 환율 부족")
        self.assertFalse(result["profit_available"])
        self.assertEqual(result["unpriced_sell_count"], 1)


if __name__ == "__main__":
    unittest.main()
