from __future__ import annotations

import unittest
from types import SimpleNamespace
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
        payload_dict = payload if isinstance(payload, dict) else {}
        summary_obj = payload_dict.get("summary")
        summary = summary_obj if isinstance(summary_obj, dict) else {}

        self.assertEqual(summary.get("total_realized_profit_krw"), 119829.0)
        self.assertAlmostEqual(
            float(summary.get("total_realized_return_rate") or 0.0),
            0.06350697242516584,
            places=6,
        )

    def test_normalize_overseas_realized_rows_adds_summary_aliases(self):
        rows = [
            {
                "trad_day": "20260311",
                "pdno": "7203",
                "ovrs_rlzt_pfls_amt": "111261",
                "stck_buy_amt_smtl": "18868739",
                "stck_sll_tlex": "1200",
                "ovrs_excg_cd": "TKSE",
                "crcy_cd": "JPY",
            }
        ]

        normalized = api_client._normalize_overseas_realized_rows(rows)

        self.assertEqual(len(normalized), 1)
        self.assertEqual(normalized[0]["symbol"], "7203")
        self.assertEqual(normalized[0]["realized_profit_krw"], 111261.0)
        self.assertEqual(normalized[0]["buy_amount_krw"], 18868739.0)

    def test_attach_realized_profit_to_overseas_sell_trade_uses_pdno_fallback(self):
        trades = [
            {
                "date": "20260311",
                "market": "TSE",
                "symbol": "7203",
                "ticker": "7203",
                "side": "매도",
                "quantity": 10.0,
                "amount": 18980000.0,
            }
        ]
        overseas_pnl_rows = [
            {
                "date": "20260311",
                "symbol": "7203",
                "quantity": 10.0,
                "amount": 18980000.0,
                "realized_profit_krw": 111261.0,
                "buy_amount_krw": 18868739.0,
                "realized_return_rate": None,
            }
        ]

        enriched = api_client._attach_realized_profit_to_sell_trades(
            trades,
            [],
            overseas_pnl_rows,
        )

        self.assertEqual(enriched[0]["realized_profit_krw"], 111261.0)
        self.assertAlmostEqual(
            enriched[0]["realized_return_rate"],
            (111261.0 / 18868739.0) * 100,
            places=6,
        )

    @patch("app.api_client._request_with_pagination")
    def test_overseas_realized_trade_profit_filters_out_of_range_and_dedupes(
        self, mock_request
    ):
        in_range_row = {
            "trad_day": "20260311",
            "ovrs_pdno": "ORCL",
            "slcl_qty": "10",
            "frcr_sll_amt_smtl1": "1600",
            "ovrs_rlzt_pfls_amt": "120",
            "stck_sll_tlex": "5",
            "stck_buy_amt_smtl": "1480",
        }
        out_of_range_row = {
            "trad_day": "20260228",
            "ovrs_pdno": "ORCL",
            "slcl_qty": "10",
            "frcr_sll_amt_smtl1": "1500",
            "ovrs_rlzt_pfls_amt": "80",
            "stck_sll_tlex": "5",
            "stck_buy_amt_smtl": "1415",
        }

        def fake_request(url, headers, params, fk_field, nk_field, **kwargs):
            exchange = params.get("OVRS_EXCG_CD")
            if exchange in {"NASD", "NYSE"}:
                return [(SimpleNamespace(status_code=200), {"output1": [in_range_row, out_of_range_row]})]
            return [(SimpleNamespace(status_code=200), {"output1": []})]

        mock_request.side_effect = fake_request

        rows = api_client.get_overseas_realized_trade_profit(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
        )

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["date"], "20260311")
        self.assertEqual(rows[0]["symbol"], "ORCL")
        self.assertEqual(rows[0]["realized_profit_krw"], 120.0)

    @patch("app.api_client._request_with_pagination")
    def test_overseas_realized_profit_filters_out_of_range_and_dedupes(
        self, mock_request
    ):
        in_range_row = {
            "trad_day": "20260311",
            "ovrs_pdno": "ORCL",
            "ovrs_rlzt_pfls_amt": "120",
            "stck_buy_amt_smtl": "1480",
            "stck_sll_tlex": "5",
        }
        out_of_range_row = {
            "trad_day": "20260228",
            "ovrs_pdno": "ORCL",
            "ovrs_rlzt_pfls_amt": "80",
            "stck_buy_amt_smtl": "1415",
            "stck_sll_tlex": "5",
        }

        def fake_request(url, headers, params, fk_field, nk_field, **kwargs):
            exchange = params.get("OVRS_EXCG_CD")
            if exchange in {"NASD", "NYSE"}:
                return [(SimpleNamespace(status_code=200), {"output1": [in_range_row, out_of_range_row]})]
            return [(SimpleNamespace(status_code=200), {"output1": []})]

        mock_request.side_effect = fake_request

        rows = api_client.get_overseas_realized_profit(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
        )

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["date"], "20260311")
        self.assertEqual(rows[0]["symbol"], "ORCL")
        self.assertEqual(rows[0]["realized_profit_krw"], 120.0)

    def test_pick_foreign_cash_balance_from_output2_reads_jpy_only(self):
        rows = [
            {"crcy_cd": "USD", "frcr_use_psbl_amt": "250.50", "bass_exrt": "1450"},
            {"crcy_cd": "JPY", "frcr_use_psbl_amt": "30000", "bass_exrt": "915"},
        ]

        amount, exrt, source = api_client._pick_foreign_cash_balance_from_output2(rows, "JPY")

        self.assertEqual(amount, 30000.0)
        self.assertEqual(exrt, 915.0)
        self.assertEqual(source, "output2[1].frcr_use_psbl_amt")

    def test_pick_foreign_cash_balance_from_output2_falls_back_when_currency_code_missing(self):
        rows = [
            {"crcy_cd": "", "frcr_use_psbl_amt": "33000", "bass_exrt": "910"},
        ]

        amount, exrt, source = api_client._pick_foreign_cash_balance_from_output2(rows, "JPY")

        self.assertEqual(amount, 33000.0)
        self.assertEqual(exrt, 910.0)
        self.assertEqual(source, "output2[0].frcr_use_psbl_amt")

    def test_pick_foreign_cash_balance_from_output1_cash_row_reads_jpy_pdno(self):
        rows = [
            {"pdno": "JPY", "ccld_qty_smtl1": "48000", "bass_exrt": "905"},
        ]

        amount, exrt, source = api_client._pick_foreign_cash_balance_from_output1_cash_row(rows, "JPY")

        self.assertEqual(amount, 48000.0)
        self.assertEqual(exrt, 905.0)
        self.assertEqual(source, "output1[0].ccld_qty_smtl1")

    def test_pick_foreign_cash_balance_from_output3_reads_orderable_fields(self):
        amount, source = api_client._pick_foreign_cash_balance_from_output3(
            {"ord_psbl_frcr_amt": "12000"}
        )

        self.assertEqual(amount, 12000.0)
        self.assertEqual(source, "output3.ord_psbl_frcr_amt")

    @patch("app.api_client.requests.get")
    def test_get_overseas_balance_exposes_optional_jpy_summary(self, mock_get):
        us_payload = {
            "rt_cd": "0",
            "output1": [],
            "output2": [{"crcy_cd": "USD", "frcr_use_psbl_amt": "20.5", "bass_exrt": "1400"}],
            "output3": {"pchs_amt_smtl_amt": "0", "evlu_amt_smtl_amt": "0"},
        }
        jp_payload = {
            "rt_cd": "0",
            "output1": [],
            "output2": [{"crcy_cd": "JPY", "frcr_use_psbl_amt": "33000", "bass_exrt": "910"}],
        }

        mock_get.side_effect = [
            SimpleNamespace(status_code=200, json=lambda: us_payload, headers={}),
            SimpleNamespace(status_code=500, json=lambda: {}, headers={}),
            SimpleNamespace(status_code=500, json=lambda: {}, headers={}),
            SimpleNamespace(status_code=200, json=lambda: jp_payload, headers={}),
        ]

        result = api_client.get_overseas_balance(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
        )
        result_dict = result if isinstance(result, dict) else {}
        us_summary_obj = result_dict.get("us_summary")
        jp_summary_obj = result_dict.get("jp_summary")
        us_summary = us_summary_obj if isinstance(us_summary_obj, dict) else {}
        jp_summary = jp_summary_obj if isinstance(jp_summary_obj, dict) else {}

        self.assertEqual(us_summary.get("usd_cash_balance"), 20.5)
        self.assertEqual(jp_summary.get("jpy_cash_balance"), 33000.0)
        self.assertEqual(jp_summary.get("jpy_exrt"), 910.0)

    @patch("app.api_client.requests.get")
    def test_get_overseas_balance_prefers_jpy_cash_row_from_output1(self, mock_get):
        us_payload = {
            "rt_cd": "0",
            "output1": [],
            "output2": [{"crcy_cd": "USD", "frcr_use_psbl_amt": "20.5", "bass_exrt": "1400"}],
            "output3": {"pchs_amt_smtl_amt": "0", "evlu_amt_smtl_amt": "0"},
        }
        jp_payload = {
            "rt_cd": "0",
            "output1": [
                {"pdno": "JPY", "ccld_qty_smtl1": "48000", "bass_exrt": "905"},
                {"pdno": "7203", "prdt_name": "Toyota", "avg_unpr3": "2000", "ovrs_now_pric1": "2100", "bass_exrt": "905", "ccld_qty_smtl1": "10", "ovrs_excg_cd": "TKSE"},
            ],
            "output2": {"frcr_use_psbl_amt": "33000", "bass_exrt": "910"},
            "output3": {"ord_psbl_frcr_amt": "12000"},
        }

        mock_get.side_effect = [
            SimpleNamespace(status_code=200, json=lambda: us_payload, headers={}),
            SimpleNamespace(status_code=500, json=lambda: {}, headers={}),
            SimpleNamespace(status_code=500, json=lambda: {}, headers={}),
            SimpleNamespace(status_code=200, json=lambda: jp_payload, headers={}),
        ]

        result = api_client.get_overseas_balance(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
        )
        result_dict = result if isinstance(result, dict) else {}
        jp_summary_obj = result_dict.get("jp_summary")
        jp_items_obj = result_dict.get("jp_items")
        jp_summary = jp_summary_obj if isinstance(jp_summary_obj, dict) else {}
        jp_items = jp_items_obj if isinstance(jp_items_obj, list) else []

        self.assertEqual(jp_summary.get("jpy_cash_balance"), 48000.0)
        self.assertEqual(jp_summary.get("jpy_exrt"), 905.0)
        self.assertEqual(len(jp_items), 1)
        self.assertEqual(jp_items[0].get("ticker"), "7203")

if __name__ == "__main__":
    unittest.main()
