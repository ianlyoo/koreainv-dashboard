from __future__ import annotations

import unittest
from unittest.mock import patch
from types import SimpleNamespace

from app import api_client


class DomesticBalancePriceTests(unittest.TestCase):
    def test_regular_session_prefers_balance_price_without_quote_call(self):
        item = {"pdno": "005930", "prpr": "71000"}

        with patch("app.api_client._is_kor_regular_session", return_value=True), patch(
            "app.api_client.get_domestic_quote_price"
        ) as quote_mock:
            price = api_client._resolve_domestic_balance_now_price(
                item,
                "token",
                "app_key",
                "app_secret",
            )

        self.assertEqual(price, 71000)
        quote_mock.assert_not_called()

    def test_after_hours_prefers_latest_quote_over_balance_price(self):
        item = {"pdno": "005930", "prpr": "71000"}

        with patch("app.api_client._is_kor_regular_session", return_value=False), patch(
            "app.api_client.get_domestic_quote_price", return_value=71500
        ) as quote_mock:
            price = api_client._resolve_domestic_balance_now_price(
                item,
                "token",
                "app_key",
                "app_secret",
            )

        self.assertEqual(price, 71500)
        quote_mock.assert_called_once_with("token", "app_key", "app_secret", "005930")

    def test_after_hours_falls_back_to_balance_price_when_quote_missing(self):
        item = {"pdno": "005930", "prpr": "71000"}

        with patch("app.api_client._is_kor_regular_session", return_value=False), patch(
            "app.api_client.get_domestic_quote_price", return_value=None
        ):
            price = api_client._resolve_domestic_balance_now_price(
                item,
                "token",
                "app_key",
                "app_secret",
            )

        self.assertEqual(price, 71000)


class DomesticQuoteLookupTests(unittest.TestCase):
    def test_after_hours_prefers_later_after_market_price_over_earlier_regular_price(self):
        responses = iter(
            [
                {"stck_prpr": "71000"},
                {"ovtm_untp_prpr": "71500"},
            ]
        )

        with patch("app.api_client._is_kor_regular_session", return_value=False), patch(
            "app.api_client._get_domestic_quote_output",
            side_effect=lambda *_args, **_kwargs: next(responses, None),
        ):
            price = api_client.get_domestic_quote_price(
                "token",
                "app_key",
                "app_secret",
                "005930",
            )

        self.assertEqual(price, 71500)

    def test_after_hours_falls_back_to_regular_price_when_no_after_market_price_exists(self):
        responses = iter(
            [
                {"stck_prpr": "71000"},
                None,
                None,
            ]
        )

        with patch("app.api_client._is_kor_regular_session", return_value=False), patch(
            "app.api_client._get_domestic_quote_output",
            side_effect=lambda *_args, **_kwargs: next(responses, None),
        ):
            price = api_client.get_domestic_quote_price(
                "token",
                "app_key",
                "app_secret",
                "005930",
            )

        self.assertEqual(price, 71000)


class DomesticBalanceFilteringTests(unittest.TestCase):
    @patch("app.api_client.get_domestic_orderable_cash", return_value=(0, "none"))
    @patch("app.api_client.requests.get")
    def test_get_domestic_balance_skips_zero_quantity_rows(self, mock_get, _mock_cash):
        payload = {
            "output1": [
                {
                    "pdno": "005930",
                    "prdt_name": "Samsung Electronics",
                    "hldg_qty": "0",
                    "pchs_avg_pric": "70000",
                    "prpr": "71000",
                },
                {
                    "pdno": "000660",
                    "prdt_name": "SK hynix",
                    "hldg_qty": "3",
                    "pchs_avg_pric": "200000",
                    "prpr": "210000",
                },
            ],
            "output2": [
                {
                    "pchs_amt_smtl_amt": "600000",
                    "evlu_amt_smtl_amt": "630000",
                    "evlu_pfls_smtl_amt": "30000",
                }
            ],
        }
        mock_get.return_value = SimpleNamespace(status_code=200, json=lambda: payload)

        with patch("app.api_client._resolve_domestic_balance_now_price", side_effect=[210000]):
            result = api_client.get_domestic_balance(
                "token",
                "app_key",
                "app_secret",
                "12345678",
                "01",
            )

        self.assertEqual(len(result["items"]), 1)
        self.assertEqual(result["items"][0]["ticker"], "000660")
        self.assertEqual(result["items"][0]["qty"], 3)


if __name__ == "__main__":
    unittest.main()
