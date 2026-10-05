from __future__ import annotations

import copy
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

from app import toss_api_client as toss


class TossHistoricalProfitTests(unittest.TestCase):
    def setUp(self):
        toss.clear_token_cache()
        self.fixture = json.loads((Path(__file__).parent / "fixtures/toss/orders-and-fx.json").read_text())

    @patch("app.toss_api_client._fetch_closed_orders")
    @patch("app.toss_api_client._get_usd_exchange_rate", return_value=1700)
    @patch("app.toss_api_client._get_historical_usd_mid_rate")
    def test_tax_opt_in_relays_buy_quotes_and_independent_native_cost(self, fx, spot, fetch):
        orders = self.fixture["orders"]["result"]["orders"]
        fetch.return_value = (orders, True)
        quotes = {row["execution"]["filledAt"]: rate for row, rate in zip(orders, [1200, 1300, 1400, 1300])}
        fx.side_effect = lambda client, secret, timestamp, **kwargs: quotes[timestamp]
        result = toss.get_trade_history("fake-client", "fake-secret", "9", "2026-09-01", "2026-09-30", tax_estimate=True)
        self.assertEqual(len(result["tax_executions"]), 4)
        self.assertEqual([row["tax_reference_fx"] for row in result["tax_executions"]], [1200, 1300, 1400, 1300])
        self.assertAlmostEqual(result["tax_executions"][2]["buy_amount_native"], 125.25)
        self.assertEqual(fx.call_count, 4)
        self.assertEqual(result["tax_fx_basis"], "fifo_acquisition_and_sale_historical_mid_rate")
        fetch.assert_called_once_with("fake-client", "fake-secret", "9", end_date="2026-09-30")

    @patch("app.toss_api_client._fetch_closed_orders")
    @patch("app.toss_api_client._get_usd_exchange_rate", return_value=1700)
    @patch("app.toss_api_client._get_historical_usd_mid_rate", return_value=1400)
    def test_tax_opt_in_skips_unrelated_buys_and_keeps_missing_cost_sale(self, fx, spot, fetch):
        orders = copy.deepcopy(self.fixture["orders"]["result"]["orders"])
        unrelated = copy.deepcopy(orders[0])
        unrelated.update(orderId="unrelated-buy", symbol="OTHER")
        unrelated["execution"]["filledAt"] = "2026-06-05T09:00:30+09:00"
        orders = [unrelated, orders[2]]  # sell has no acquisition history
        fetch.return_value = (orders, True)
        result = toss.get_trade_history("fake-client", "fake-secret", "9", "2026-09-01", "2026-09-30", tax_estimate=True)
        self.assertEqual(len(result["tax_executions"]), 2)
        self.assertNotIn("tax_reference_fx", result["tax_executions"][0])
        self.assertNotIn("buy_amount_native", result["tax_executions"][1])
        self.assertEqual(fx.call_count, 1)

    def rows(self):
        return toss._normalize_order_rows(
            self.fixture["orders"]["result"]["orders"], usd_exchange_rate=1700,
            start_date="0001-01-01", end_date="2026-09-30",
        )

    def estimate(self, rows, **kwargs):
        return toss._estimate_realized_profit(
            rows, usd_exchange_rate=1700, start_date="2026-09-01", end_date="2026-09-30", **kwargs,
        )

    def test_fractional_basis_fees_taxes_and_sale_fx_ignore_today_rate(self):
        rows = self.rows()
        rows[2]["profit_exchange_rate"] = 1400
        rows[3]["profit_exchange_rate"] = 1300
        result = self.estimate(rows)
        self.assertAlmostEqual(rows[2]["realized_profit_krw"], (180 - .5 - 125.25) * 1400)
        self.assertAlmostEqual(rows[3]["realized_profit_krw"], (150 - .4 - 125.25) * 1300)
        self.assertAlmostEqual(result["summary"]["total_buy_amount_krw"], 125.25 * 2700)
        self.assertTrue(result["profit_complete"])
        self.assertTrue(rows[2]["realized_profit_estimated"])
        self.assertEqual(rows[2]["profit_rate_source"], toss.HISTORICAL_FX_SOURCE)
        self.assertEqual(rows[2]["profit_history_start_date"], "20260601")

    def test_mid_rate_validation_requires_direction_timezone_and_window(self):
        quote = self.fixture["exchange_rate"]["result"]
        timestamp = "2026-09-02T09:00:30+09:00"
        self.assertEqual(toss._validated_historical_mid_rate(quote, timestamp), 1400)
        self.assertEqual(toss._validated_historical_mid_rate(quote, "2026-09-02T00:00:30Z"), 1400)
        for change in [{"baseCurrency": "KRW"}, {"quoteCurrency": "USD"}, {"midRate": None}, {"midRate": "NaN"}, {"midRate": "0"}, {"validUntil": "2026-09-02T09:00:00+09:00"}, {"validFrom": "2026-09-03T09:00:00+09:00"}]:
            with self.subTest(change=change):
                self.assertEqual(toss._validated_historical_mid_rate({**quote, **change}, timestamp), 0)
        self.assertEqual(toss._validated_historical_mid_rate(quote, "2026-09-02T09:00:30"), 0)

    def test_sale_quote_window_skew_tolerance_includes_both_ten_minute_boundaries(self):
        quote = {**self.fixture["exchange_rate"]["result"],
                 "validFrom": "2026-09-02T09:00:45+09:00", "validUntil": "2026-09-02T09:05:45+09:00"}
        for timestamp, expected in [
            ("2026-09-02T09:00:30+09:00", 1400),  # window starts 15 seconds after sale
            ("2026-09-02T00:00:30Z", 1400),
            ("2026-09-02T08:50:45+09:00", 1400),
            ("2026-09-02T08:50:44.999+09:00", 0),
            ("2026-09-02T09:05:45+09:00", 1400),
            ("2026-09-02T09:15:45+09:00", 1400),
            ("2026-09-02T09:15:45.001+09:00", 0),
        ]:
            with self.subTest(timestamp=timestamp):
                self.assertEqual(toss._validated_historical_mid_rate(quote, timestamp), expected)

    @patch("app.toss_api_client.time.sleep")
    @patch("app.toss_api_client._authorized_get")
    def test_historical_lookup_cache_is_scoped_and_does_not_use_rate(self, get, sleep):
        get.return_value = self.fixture["exchange_rate"]
        timestamp = "2026-09-02T09:00:30+09:00"
        self.assertEqual(toss._get_historical_usd_mid_rate("c", "s", timestamp), 1400)
        self.assertEqual(toss._get_historical_usd_mid_rate("c", "s", timestamp), 1400)
        self.assertEqual(get.call_count, 1)
        self.assertEqual(get.call_args.kwargs["params"]["dateTime"], timestamp)
        toss._get_historical_usd_mid_rate("other", "s", timestamp)
        self.assertEqual(get.call_count, 2)

    @patch("app.toss_api_client.time.sleep")
    @patch("app.toss_api_client._authorized_get", side_effect=RuntimeError("404"))
    def test_missing_historical_window_is_briefly_cached_and_keeps_rows(self, get, sleep):
        timestamp = "2026-09-02T09:00:30+09:00"
        self.assertEqual(toss._get_historical_usd_mid_rate("c", "s", timestamp), 0)
        self.assertEqual(toss._get_historical_usd_mid_rate("c", "s", timestamp), 0)
        self.assertEqual(get.call_count, 1)
        rows = self.rows()
        result = self.estimate(rows)
        self.assertEqual(len(rows), 4)
        self.assertIsNone(rows[2]["realized_profit_krw"])
        self.assertEqual(rows[2]["profit_estimate_reason"], "매도 시점 참고환율 정보 부족")
        self.assertEqual(result["unpriced_sell_count"], 2)

    def test_unknown_basis_incomplete_history_and_unsupported_currency(self):
        for mode in ["basis", "history", "currency"]:
            rows = self.rows()
            for row in rows: row["profit_exchange_rate"] = 1400
            if mode == "basis": rows = rows[2:]
            if mode == "currency":
                for row in rows: row["currency"] = "JPY"
            result = self.estimate(rows, history_complete=mode != "history")
            sell = next(row for row in rows if row["side"] == "매도")
            self.assertIsNone(sell["realized_profit_krw"])
            self.assertEqual(sell["profit_estimate_reason"], {"basis": "매수 원가 이력 부족", "history": "거래 이력 조회 미완료", "currency": "지원하지 않는 통화"}[mode])
            self.assertFalse(result["profit_complete"])

    def test_nullable_or_malformed_buy_and_sell_charges_are_not_zero(self):
        for index in [0, 2]:
            for value in [None, "", "not-a-number", "NaN"]:
                with self.subTest(index=index, value=value):
                    fixture = copy.deepcopy(self.fixture)
                    fixture["orders"]["result"]["orders"][index]["execution"]["commission"] = value
                    rows = toss._normalize_order_rows(fixture["orders"]["result"]["orders"], usd_exchange_rate=1700, start_date="0001-01-01", end_date="2026-09-30")
                    for row in rows: row["profit_exchange_rate"] = 1400
                    self.estimate(rows)
                    self.assertIsNone(rows[2]["realized_profit_krw"])
                    self.assertIn("수수료", rows[2]["profit_estimate_reason"])

    @patch("app.toss_api_client._get_usd_exchange_rate", return_value=1700)
    @patch("app.toss_api_client._get_historical_usd_mid_rate", return_value=1400)
    @patch("app.toss_api_client._authorized_get")
    def test_real_response_pipeline_deduplicates_orders_and_propagates_metadata(self, get, fx, spot):
        payload = copy.deepcopy(self.fixture["orders"])
        payload["result"]["orders"].append(payload["result"]["orders"][2])
        get.return_value = payload
        result = toss.get_trade_history("c", "s", "1", "20260901", "20260930")
        self.assertEqual(len(result["items"]), 2)
        self.assertEqual(fx.call_count, 2)
        self.assertTrue(result["profit_history_complete"])
        self.assertEqual(result["profit_history_start_date"], "20260601")
        self.assertEqual(result["items"][1]["profit_exchange_rate"], 1400)

    @patch("app.toss_api_client._authorized_get")
    def test_broken_cursor_and_malformed_pages_mark_history_incomplete(self, get):
        get.return_value = {"result": {"orders": [], "hasNext": True, "nextCursor": None}}
        self.assertFalse(toss._fetch_closed_orders("c", "s", "1", end_date="2026-09-30")[1])
        get.return_value = {"result": {}}
        self.assertFalse(toss._fetch_closed_orders("c", "s", "1", end_date="2026-09-30")[1])

    @patch("app.toss_proxy_client._post")
    def test_old_proxy_profit_is_unavailable_but_sale_row_is_preserved(self, post):
        from app import toss_proxy_client
        post.return_value = {"result": {"items": [{"side": "매도", "currency": "USD", "realized_profit_krw": 123}], "summary": {"overseas_realized_profit_krw": 123}, "profit_available": True}}
        result = toss_proxy_client.get_trade_history("c", "s", "1", "20260901", "20260930")
        self.assertFalse(result["profit_available"])
        self.assertEqual(len(result["items"]), 1)
        self.assertIsNone(result["items"][0]["realized_profit_krw"])
        self.assertIn("서버 업데이트", result["items"][0]["profit_estimate_reason"])

    @patch("app.toss_api_client.get_access_token", return_value="fixture-token")
    @patch("app.toss_api_client.time.sleep")
    @patch("app.toss_api_client.requests.get")
    def test_429_has_at_most_two_retries(self, get, sleep, token):
        get.return_value = Mock(status_code=429, text="limited")
        get.return_value.json.return_value = {"error": {"code": "rate-limit"}}
        with self.assertRaises(RuntimeError): toss._authorized_get("/api/v1/exchange-rate", "c", "s")
        self.assertEqual(get.call_count, 3)
        self.assertEqual(sleep.call_count, 2)


if __name__ == "__main__":
    unittest.main()
