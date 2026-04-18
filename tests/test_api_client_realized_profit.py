from __future__ import annotations

import unittest
import threading
import time
from types import SimpleNamespace
from unittest.mock import patch

from app import api_client


class RealizedProfitApiClientTests(unittest.TestCase):
    def setUp(self):
        api_client._realized_cache.clear()
        api_client._inflight_trade_profit_rows.clear()

    def tearDown(self):
        api_client._realized_cache.clear()
        api_client._inflight_trade_profit_rows.clear()

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

    def test_normalize_domestic_trade_rows_derives_amount_from_average_price_when_amount_zero(self):
        rows = [
            {
                "ord_dt": "20260312",
                "pdno": "005930",
                "prdt_name": "삼성전자",
                "sll_buy_dvsn_cd": "01",
                "sll_buy_dvsn_cd_name": "매도",
                "tot_ccld_qty": "0",
                "ord_qty": "3",
                "tot_ccld_amt": "0",
                "avg_prvs": "71000",
            }
        ]

        normalized = api_client._normalize_domestic_trade_rows(rows)

        self.assertEqual(len(normalized), 1)
        self.assertEqual(normalized[0]["side"], "매도")
        self.assertEqual(normalized[0]["quantity"], 3.0)
        self.assertEqual(normalized[0]["unit_price"], 71000.0)
        self.assertEqual(normalized[0]["amount"], 213000.0)

    def test_normalize_overseas_trade_rows_uses_positive_fallback_amount_after_zero_string(self):
        rows = [
            {
                "trad_dt": "20260312",
                "pdno": "AAPL",
                "ovrs_item_name": "Apple",
                "sll_buy_dvsn_cd": "01",
                "sll_buy_dvsn_name": "매도",
                "ccld_qty": "2",
                "tr_frcr_amt2": "0",
                "frcr_sll_amt_smtl": "350.50",
                "crcy_cd": "USD",
            }
        ]

        normalized = api_client._normalize_overseas_trade_rows(rows, "NAS")

        self.assertEqual(len(normalized), 1)
        self.assertEqual(normalized[0]["side"], "매도")
        self.assertEqual(normalized[0]["amount"], 350.50)
        self.assertEqual(normalized[0]["unit_price"], 175.25)
        self.assertEqual(normalized[0]["sell_amount_native"], 350.50)

    def test_normalize_overseas_realized_trade_rows_uses_fallback_sell_amount_after_zero_string(self):
        rows = [
            {
                "trad_day": "20260312",
                "ovrs_pdno": "AAPL",
                "slcl_qty": "2",
                "frcr_sll_amt_smtl1": "0",
                "stck_sll_amt_smtl": "350.50",
                "ovrs_rlzt_pfls_amt": "10",
                "stck_buy_amt_smtl": "340.50",
            }
        ]

        normalized = api_client._normalize_overseas_realized_trade_rows(rows)

        self.assertEqual(len(normalized), 1)
        self.assertEqual(normalized[0]["amount"], 350.50)
        self.assertEqual(normalized[0]["buy_amount_krw"], 340.50)

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

    def test_pick_foreign_cash_balance_from_output2_reads_balance_only(self):
        rows = [
            {"crcy_cd": "USD", "frcr_use_psbl_amt": "250.50", "bass_exrt": "1450"},
            {"crcy_cd": "JPY", "frcr_dncl_amt_2": "30000", "sll_ruse_psbl_amt": "12000", "bass_exrt": "915"},
        ]

        amount, exrt, source = api_client._pick_foreign_cash_balance_from_output2(rows, "JPY")

        self.assertEqual(amount, 30000.0)
        self.assertEqual(exrt, 915.0)
        self.assertEqual(source, "output2[1].frcr_dncl_amt_2")

    def test_pick_foreign_cash_balance_from_output2_falls_back_without_currency_match(self):
        rows = [
            {"crcy_cd": "", "frcr_dncl_amt_2": "33000", "sl_ruse_frcr_amt": "2000", "bass_exrt": "910"},
        ]

        amount, exrt, source = api_client._pick_foreign_cash_balance_from_output2(rows, "JPY")

        self.assertEqual(amount, 33000.0)
        self.assertEqual(exrt, 910.0)
        self.assertEqual(source, "output2[0].frcr_dncl_amt_2")

    def test_pick_foreign_cash_balance_from_output1_cash_row_reads_jpy_pdno(self):
        rows = [
            {"pdno": "JPY", "ccld_qty_smtl1": "48000", "bass_exrt": "905"},
        ]

        amount, exrt, source = api_client._pick_foreign_cash_balance_from_output1_cash_row(rows, "JPY")

        self.assertEqual(amount, 48000.0)
        self.assertEqual(exrt, 905.0)
        self.assertEqual(source, "output1[0].ccld_qty_smtl1")

    def test_pick_foreign_sell_reuse_from_output2_reads_usd_reuse(self):
        rows = [
            {"crcy_cd": "USD", "sll_ruse_psbl_amt": "25.5", "bass_exrt": "1400"},
        ]

        amount, exrt, source = api_client._pick_foreign_sell_reuse_from_output2(rows, "USD")

        self.assertEqual(amount, 25.5)
        self.assertEqual(exrt, 1400.0)
        self.assertEqual(source, "output2[0].sll_ruse_psbl_amt")

    def test_pick_foreign_cash_balance_from_output3_reads_orderable_fields(self):
        amount, source = api_client._pick_foreign_cash_balance_from_output3(
            {"ord_psbl_frcr_amt": "12000"}
        )

        self.assertEqual(amount, 12000.0)
        self.assertEqual(source, "output3.ord_psbl_frcr_amt")

    @patch("app.api_client._run_parallel_tasks")
    def test_fetch_trade_profit_rows_dedupes_overlapping_calls(self, mock_run_parallel_tasks):
        call_count = 0
        call_count_lock = threading.Lock()
        results = []

        def fake_run_parallel_tasks(tasks):
            nonlocal call_count
            with call_count_lock:
                call_count += 1
            time.sleep(0.05)
            return {
                "domestic": [{"date": "20260311", "symbol": "005930"}],
                "overseas": [{"date": "20260311", "symbol": "AAPL"}],
            }

        mock_run_parallel_tasks.side_effect = fake_run_parallel_tasks

        def fetch_rows():
            results.append(
                api_client._fetch_trade_profit_rows(
                    "token",
                    "key",
                    "secret",
                    "12345678",
                    "01",
                    "20260301",
                    "20260331",
                )
            )

        first = threading.Thread(target=fetch_rows)
        second = threading.Thread(target=fetch_rows)
        first.start()
        second.start()
        first.join()
        second.join()

        self.assertEqual(call_count, 1)
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0], results[1])

    @patch("app.api_client._run_parallel_tasks")
    def test_fetch_trade_profit_rows_clears_inflight_state_after_failure(self, mock_run_parallel_tasks):
        call_count = 0

        def fake_run_parallel_tasks(tasks):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise RuntimeError("boom")
            return {
                "domestic": [{"date": "20260311", "symbol": "005930"}],
                "overseas": [],
            }

        mock_run_parallel_tasks.side_effect = fake_run_parallel_tasks

        with self.assertRaises(RuntimeError):
            api_client._fetch_trade_profit_rows(
                "token",
                "key",
                "secret",
                "12345678",
                "01",
                "20260301",
                "20260331",
            )

        self.assertEqual(api_client._inflight_trade_profit_rows, {})

        payload = api_client._fetch_trade_profit_rows(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
        )

        self.assertEqual(call_count, 2)
        self.assertEqual(len(payload.get("domestic") or []), 1)

    def test_has_japan_trade_rows_accepts_market_aliases(self):
        self.assertTrue(api_client._has_japan_trade_rows([{"market": "TKSE"}]))
        self.assertTrue(api_client._has_japan_trade_rows([{"market": "TSE"}]))
        self.assertTrue(api_client._has_japan_trade_rows([{"market": "TYO"}]))
        self.assertTrue(api_client._has_japan_trade_rows([{"market": "JPX"}]))
        self.assertFalse(api_client._has_japan_trade_rows([{"market": "NASD"}]))

    @patch("app.api_client.get_overseas_trade_history_ccnl")
    @patch("app.api_client._fetch_trade_profit_rows")
    @patch("app.api_client.get_overseas_trade_history")
    @patch("app.api_client.get_domestic_trade_history")
    def test_get_trade_history_includes_summary_and_daily_payload(
        self,
        mock_domestic_trade_history,
        mock_overseas_trade_history,
        mock_fetch_trade_profit_rows,
        mock_get_overseas_trade_history_ccnl,
    ):
        mock_domestic_trade_history.return_value = [
            {
                "date": "20260311",
                "time": "091500",
                "market": "KOR",
                "symbol": "005930",
                "ticker": "005930",
                "side": "매도",
                "quantity": 2.0,
                "unit_price": 70000.0,
                "amount": 140000.0,
            }
        ]
        mock_overseas_trade_history.return_value = []
        mock_fetch_trade_profit_rows.return_value = {
            "domestic": [
                {
                    "date": "20260311",
                    "symbol": "005930",
                    "quantity": 2.0,
                    "amount": 140000.0,
                    "realized_profit_krw": 10000.0,
                    "buy_amount_krw": 130000.0,
                    "realized_return_rate": None,
                }
            ],
            "overseas": [],
        }
        mock_get_overseas_trade_history_ccnl.return_value = []

        payload = api_client.get_trade_history(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
        )

        summary = payload.get("summary") or {}
        daily = payload.get("daily") or []
        items = payload.get("items") or []

        self.assertEqual(summary.get("total_realized_profit_krw"), 10000.0)
        self.assertAlmostEqual(
            float(summary.get("total_realized_return_rate") or 0.0),
            (10000.0 / 130000.0) * 100.0,
            places=6,
        )
        self.assertEqual(len(daily), 1)
        self.assertEqual(daily[0]["date"], "20260311")
        self.assertEqual(items[0]["realized_profit_krw"], 10000.0)

    @patch("app.api_client.get_overseas_trade_history_ccnl")
    @patch("app.api_client._fetch_trade_profit_rows")
    @patch("app.api_client.get_overseas_trade_history")
    @patch("app.api_client.get_domestic_trade_history")
    def test_get_trade_history_merges_overseas_ccnl_when_rows_include_japan(
        self,
        mock_domestic_trade_history,
        mock_overseas_trade_history,
        mock_fetch_trade_profit_rows,
        mock_get_overseas_trade_history_ccnl,
    ):
        mock_domestic_trade_history.return_value = []
        mock_overseas_trade_history.return_value = [
            {
                "date": "20260311",
                "market": "TKSE",
                "symbol": "7203",
                "ticker": "7203",
                "side": "매도",
                "quantity": 10.0,
                "amount": 1000.0,
            }
        ]
        mock_fetch_trade_profit_rows.return_value = {"domestic": [], "overseas": []}

        payload = api_client.get_trade_history(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
        )

        self.assertEqual(len(payload.get("items") or []), 1)
        mock_get_overseas_trade_history_ccnl.assert_called_once()

    @patch("app.api_client.get_overseas_trade_history_ccnl")
    @patch("app.api_client._fetch_trade_profit_rows")
    @patch("app.api_client.get_overseas_trade_history")
    @patch("app.api_client.get_domestic_trade_history")
    def test_get_trade_history_merges_overseas_ccnl_for_jpx_alias_market(
        self,
        mock_domestic_trade_history,
        mock_overseas_trade_history,
        mock_fetch_trade_profit_rows,
        mock_get_overseas_trade_history_ccnl,
    ):
        mock_domestic_trade_history.return_value = []
        mock_overseas_trade_history.return_value = [
            {
                "date": "20260311",
                "market": "JPX",
                "symbol": "7203",
                "ticker": "7203",
                "side": "매도",
                "quantity": 10.0,
                "amount": 1000.0,
            }
        ]
        mock_fetch_trade_profit_rows.return_value = {"domestic": [], "overseas": []}

        payload = api_client.get_trade_history(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
        )

        self.assertEqual(len(payload.get("items") or []), 1)
        mock_get_overseas_trade_history_ccnl.assert_called_once()

    @patch("app.api_client.get_overseas_trade_history_ccnl")
    @patch("app.api_client._fetch_trade_profit_rows")
    @patch("app.api_client.get_overseas_trade_history")
    @patch("app.api_client.get_domestic_trade_history")
    def test_get_trade_history_calls_overseas_ccnl_when_overseas_requested(
        self,
        mock_domestic_trade_history,
        mock_overseas_trade_history,
        mock_fetch_trade_profit_rows,
        mock_get_overseas_trade_history_ccnl,
    ):
        mock_domestic_trade_history.return_value = []
        mock_overseas_trade_history.return_value = [
            {
                "date": "20260311",
                "market": "NASD",
                "symbol": "AAPL",
                "ticker": "AAPL",
                "side": "매수",
                "quantity": 1.0,
                "amount": 100.0,
            }
        ]
        mock_fetch_trade_profit_rows.return_value = {"domestic": [], "overseas": []}
        mock_get_overseas_trade_history_ccnl.return_value = []

        payload = api_client.get_trade_history(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
        )

        self.assertEqual(len(payload.get("items") or []), 1)
        mock_get_overseas_trade_history_ccnl.assert_called_once()

    @patch("app.api_client.get_overseas_trade_history_ccnl")
    @patch("app.api_client._fetch_trade_profit_rows")
    @patch("app.api_client.get_overseas_trade_history")
    @patch("app.api_client.get_domestic_trade_history")
    def test_get_trade_history_filters_market_side_and_paginates(
        self,
        mock_domestic_trade_history,
        mock_overseas_trade_history,
        mock_fetch_trade_profit_rows,
        mock_get_overseas_trade_history_ccnl,
    ):
        mock_domestic_trade_history.return_value = [
            {
                "date": "20260312",
                "time": "110000",
                "market": "KOR",
                "symbol": "005930",
                "ticker": "005930",
                "side": "매수",
                "quantity": 1.0,
                "amount": 70000.0,
            },
            {
                "date": "20260311",
                "time": "100000",
                "market": "KOR",
                "symbol": "000660",
                "ticker": "000660",
                "side": "매수",
                "quantity": 2.0,
                "amount": 140000.0,
            },
            {
                "date": "20260310",
                "time": "090000",
                "market": "KOR",
                "symbol": "035420",
                "ticker": "035420",
                "side": "매도",
                "quantity": 3.0,
                "amount": 210000.0,
            },
        ]
        mock_fetch_trade_profit_rows.return_value = {"domestic": [], "overseas": []}
        mock_overseas_trade_history.return_value = []
        mock_get_overseas_trade_history_ccnl.return_value = []

        payload = api_client.get_trade_history(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
            side_filter="buy",
            market_filter="domestic",
            page=2,
            page_size=1,
        )

        self.assertEqual([item["ticker"] for item in payload.get("items") or []], ["000660"])
        self.assertEqual(payload.get("filters"), {"side": "buy", "market": "domestic"})
        self.assertEqual(
            payload.get("pagination"),
            {"page": 2, "page_size": 1, "total_items": 2, "total_pages": 2},
        )
        mock_domestic_trade_history.assert_called_once_with(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
            "buy",
        )
        mock_overseas_trade_history.assert_not_called()
        mock_get_overseas_trade_history_ccnl.assert_not_called()

    @patch("app.api_client.get_overseas_trade_history_ccnl")
    @patch("app.api_client._fetch_trade_profit_rows")
    @patch("app.api_client.get_overseas_trade_history")
    @patch("app.api_client.get_domestic_trade_history")
    def test_get_trade_history_reuses_base_rows_across_pages(
        self,
        mock_domestic_trade_history,
        mock_overseas_trade_history,
        mock_fetch_trade_profit_rows,
        mock_get_overseas_trade_history_ccnl,
    ):
        mock_domestic_trade_history.return_value = [
            {
                "date": "20260312",
                "time": "110000",
                "market": "KOR",
                "symbol": "005930",
                "ticker": "005930",
                "side": "매수",
                "quantity": 1.0,
                "amount": 70000.0,
            },
            {
                "date": "20260311",
                "time": "100000",
                "market": "KOR",
                "symbol": "000660",
                "ticker": "000660",
                "side": "매수",
                "quantity": 2.0,
                "amount": 140000.0,
            },
        ]
        mock_fetch_trade_profit_rows.return_value = {"domestic": [], "overseas": []}
        mock_overseas_trade_history.return_value = []
        mock_get_overseas_trade_history_ccnl.return_value = []

        first_page = api_client.get_trade_history(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
            side_filter="buy",
            market_filter="domestic",
            page=1,
            page_size=1,
        )
        second_page = api_client.get_trade_history(
            "token",
            "key",
            "secret",
            "12345678",
            "01",
            "20260301",
            "20260331",
            side_filter="buy",
            market_filter="domestic",
            page=2,
            page_size=1,
        )

        self.assertEqual([item["ticker"] for item in first_page.get("items") or []], ["005930"])
        self.assertEqual([item["ticker"] for item in second_page.get("items") or []], ["000660"])
        mock_domestic_trade_history.assert_called_once()
        mock_fetch_trade_profit_rows.assert_called_once()
        mock_get_overseas_trade_history_ccnl.assert_not_called()

    def test_dedupe_trade_rows_keeps_same_trade_terms_with_different_times(self):
        rows = [
            {"date": "20260311", "symbol": "005930", "side": "매도", "quantity": 1, "unit_price": 70000, "amount": 70000, "time": "090001"},
            {"date": "20260311", "symbol": "005930", "side": "매도", "quantity": 1, "unit_price": 70000, "amount": 70000, "time": "090002"},
        ]

        deduped = api_client._dedupe_trade_rows(rows)

        self.assertEqual(len(deduped), 2)

    def test_attach_realized_profit_allocates_aggregate_profit_across_split_sells(self):
        trades = [
            {"date": "20260311", "market": "KOR", "symbol": "005930", "side": "매도", "quantity": 1.0, "amount": 70000.0},
            {"date": "20260311", "market": "KOR", "symbol": "005930", "side": "매도", "quantity": 1.0, "amount": 70000.0},
        ]
        domestic_pnl_rows = [
            {
                "date": "20260311",
                "symbol": "005930",
                "quantity": 2.0,
                "amount": 140000.0,
                "realized_profit_krw": 10000.0,
                "buy_amount_krw": 130000.0,
                "realized_return_rate": None,
            }
        ]

        enriched = api_client._attach_realized_profit_to_sell_trades(trades, domestic_pnl_rows, [])

        self.assertEqual([row["realized_profit_krw"] for row in enriched], [5000.0, 5000.0])
        self.assertTrue(all(row["realized_return_rate"] is not None for row in enriched))

    @patch("app.api_client._run_parallel_tasks")
    def test_fetch_trade_profit_rows_force_refresh_bypasses_cached_rows(self, mock_run_parallel_tasks):
        mock_run_parallel_tasks.side_effect = [
            {"domestic": [{"date": "20260310"}], "overseas": []},
            {"domestic": [{"date": "20260311"}], "overseas": []},
        ]

        first = api_client._fetch_trade_profit_rows("token", "key", "secret", "12345678", "01", "20260301", "20260331")
        second = api_client._fetch_trade_profit_rows("token", "key", "secret", "12345678", "01", "20260301", "20260331", force_refresh=True)

        self.assertEqual(first["domestic"][0]["date"], "20260310")
        self.assertEqual(second["domestic"][0]["date"], "20260311")
        self.assertEqual(mock_run_parallel_tasks.call_count, 2)

if __name__ == "__main__":
    unittest.main()
