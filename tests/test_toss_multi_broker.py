from __future__ import annotations

import unittest
from unittest.mock import patch

from app import toss_api_client
from app.services.balance_aggregation import fetch_aggregated_balances
from app.session_store import AccountCredential, SessionData, accounts_metadata


class TossClientNormalizationTests(unittest.TestCase):
    @patch("app.toss_api_client._get_usd_exchange_rate", return_value=1400.0)
    @patch("app.toss_api_client._authorized_get")
    def test_holdings_are_normalized_into_existing_dashboard_contract(
        self, authorized_get, _exchange_rate
    ):
        authorized_get.return_value = {
            "result": {
                "totalPurchaseAmount": {"krw": "70000", "usd": "180"},
                "marketValue": {"amount": {"krw": "75000", "usd": "200"}},
                "profitLoss": {"amount": {"krw": "5000", "usd": "20"}},
                "items": [
                    {
                        "symbol": "005930",
                        "name": "삼성전자",
                        "marketCountry": "KR",
                        "currency": "KRW",
                        "quantity": "1",
                        "lastPrice": "75000",
                        "averagePurchasePrice": "70000",
                        "profitLoss": {"rate": "0.071428"},
                    },
                    {
                        "symbol": "AAPL",
                        "name": "Apple",
                        "marketCountry": "US",
                        "currency": "USD",
                        "quantity": "1",
                        "lastPrice": "200",
                        "averagePurchasePrice": "180",
                        "profitLoss": {"rate": "0.111111"},
                    },
                ],
            }
        }

        domestic, overseas = toss_api_client.get_balances("client", "secret", "1")

        self.assertEqual(domestic["items"][0]["ticker"], "005930")
        self.assertAlmostEqual(domestic["items"][0]["profit_rt"], 7.1428)
        self.assertEqual(overseas["us_items"][0]["ticker"], "AAPL")
        self.assertEqual(overseas["us_items"][0]["bass_exrt"], 1400.0)
        self.assertEqual(domestic["summary"]["cash_balance"], 0)
        self.assertEqual(overseas["us_summary"]["usd_cash_balance"], 0)


class MultiBrokerAggregationTests(unittest.TestCase):
    def setUp(self):
        self.toss = AccountCredential.make(
            "toss-client", "toss-secret", "1", "", "토스", broker="toss"
        )
        self.kis = AccountCredential.make(
            "kis-key", "kis-secret", "12345678", "01", "KIS 주계좌"
        )

    def test_first_kis_account_remains_primary_for_kis_only_features(self):
        session = SessionData(
            self.kis.app_key,
            self.kis.app_secret,
            self.kis.cano,
            self.kis.acnt_prdt_cd,
            accounts=[self.toss, self.kis],
        )

        self.assertEqual(session.primary_account.account_id, self.kis.account_id)
        metadata = accounts_metadata(session.accounts)
        self.assertFalse(metadata[0]["is_primary"])
        self.assertTrue(metadata[1]["is_primary"])
        self.assertFalse(metadata[0]["supports_orders"])
        self.assertTrue(metadata[1]["supports_orders"])

    @patch("app.services.balance_aggregation.api_client.get_overseas_balance")
    @patch("app.services.balance_aggregation.api_client.get_domestic_balance")
    @patch("app.services.balance_aggregation.api_client.get_access_token", return_value="kis-token")
    @patch("app.services.balance_aggregation.toss_api_client.get_balances")
    def test_kis_and_toss_accounts_are_fetched_in_parallel_and_merged(
        self, toss_balances, _token, kis_domestic, kis_overseas
    ):
        toss_balances.return_value = (
            {"summary": {}, "items": [{"ticker": "005930", "qty": 1}]},
            {"us_summary": {}, "jp_summary": {}, "us_items": [], "jp_items": []},
        )
        kis_domestic.return_value = {
            "summary": {},
            "items": [{"ticker": "000660", "qty": 1}],
        }
        kis_overseas.return_value = {
            "us_summary": {},
            "jp_summary": {},
            "us_items": [],
            "jp_items": [],
        }

        result = fetch_aggregated_balances([self.toss, self.kis])

        self.assertEqual(len(result["domestic"]["items"]), 2)
        by_ticker = {item["ticker"]: item for item in result["domestic"]["items"]}
        self.assertEqual(by_ticker["005930"]["broker"], "toss")
        self.assertEqual(by_ticker["000660"]["broker"], "kis")
        toss_balances.assert_called_once_with("toss-client", "toss-secret", "1")


if __name__ == "__main__":
    unittest.main()
