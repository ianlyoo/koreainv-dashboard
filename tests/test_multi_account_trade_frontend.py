from __future__ import annotations

import unittest
from pathlib import Path


class MultiAccountTradeFrontendTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.template = Path("app/templates/index.html").read_text(encoding="utf-8")
        cls.javascript = Path("app/static/js/dashboard.js").read_text(encoding="utf-8")
        cls.android_trade_screen = Path(
            "android-app/app/src/main/java/com/koreainv/dashboard/ui/screens/TradeHistoryScreen.kt"
        ).read_text(encoding="utf-8")

    def test_web_portfolio_and_trade_history_have_account_filters(self):
        self.assertIn('id="portfolioAccountFilter"', self.template)
        self.assertIn('id="realizedProfitAccountFilter"', self.template)
        self.assertIn("function setPortfolioAccountFilter(accountId)", self.javascript)
        self.assertIn("function setRealizedProfitAccountFilter(accountId", self.javascript)

    def test_web_trade_history_includes_one_year_preset(self):
        self.assertIn('data-profit-preset="oneYear"', self.template)
        self.assertIn("getRecentMonthsRange(12)", self.javascript)

    def test_web_trade_rows_render_side_and_account_badges(self):
        self.assertIn("function tradeAccountBadgeHtml(trade, fallbackSide)", self.javascript)
        self.assertIn("tradeAccountBadgeHtml(trade, '매수')", self.javascript)
        self.assertIn("tradeAccountBadgeHtml(trade, '매도')", self.javascript)
        self.assertIn("trade?.account_label", self.javascript)

    def test_web_trade_requests_include_selected_account(self):
        self.assertGreaterEqual(
            self.javascript.count("params.set('account_id', activeProfitAccountId)"),
            2,
        )
        self.assertIn("activeProfitAccountId}:${start}:${end}", self.javascript)

    def test_estimated_toss_profit_is_clearly_labeled(self):
        self.assertIn("토스 추정 손익 포함", self.javascript)
        self.assertIn('class="profit-estimate-chip">추정', self.javascript)
        self.assertIn("토스 추정 손익 포함", self.android_trade_screen)
        self.assertIn("trade.realizedProfitEstimated", self.android_trade_screen)

    def test_android_trade_filters_share_one_row_without_trade_list_title(self):
        self.assertNotIn(
            "SectionTitle(title = stringResource(R.string.trade_list))",
            self.android_trade_screen,
        )
        self.assertGreaterEqual(self.android_trade_screen.count("compact = true"), 3)
        self.assertIn("Modifier.weight(1.08f)", self.android_trade_screen)
        self.assertIn("Modifier.weight(1.12f)", self.android_trade_screen)
        self.assertIn("Modifier.weight(0.8f)", self.android_trade_screen)


if __name__ == "__main__":
    unittest.main()
