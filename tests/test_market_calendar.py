from __future__ import annotations

import datetime as dt
import unittest
from typing import Any, cast
from unittest.mock import AsyncMock, patch

from app.routes import market


class MarketCalendarTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        market._calendar_cache["ts"] = 0.0
        market._calendar_cache["data"] = None

    def tearDown(self):
        market._calendar_cache["ts"] = 0.0
        market._calendar_cache["data"] = None

    def test_build_calendar_window_converts_kst_day_bounds_to_utc(self):
        now_kst = market.KST.localize(dt.datetime(2026, 3, 12, 10, 15, 0))

        returned_now, start_date, end_date = market._build_calendar_window(now_kst)

        self.assertEqual(returned_now, now_kst)
        self.assertEqual(start_date, "2026-03-11T15:00:00.000Z")
        self.assertEqual(end_date, "2026-03-19T14:59:59.000Z")

    def test_format_calendar_time_does_not_depend_on_strftime(self):
        class StrftimeBrokenDateTime(dt.datetime):
            def strftime(self, _format: str) -> str:
                raise AssertionError("strftime should not be used for calendar time formatting")

        broken_dt = StrftimeBrokenDateTime(2026, 3, 12, 12, 0, 0, tzinfo=market.KST)

        self.assertEqual(market._format_calendar_time(broken_dt), "03/12(목) 12:00")

    @patch("app.routes.market.asyncio.to_thread", new_callable=AsyncMock)
    async def test_empty_results_are_not_cached(self, mock_to_thread):
        now_kst = market.KST.localize(dt.datetime(2026, 3, 12, 10, 15, 0))
        mock_to_thread.return_value = []

        with patch(
            "app.routes.market._build_calendar_window",
            return_value=(
                now_kst,
                "2026-03-11T15:00:00.000Z",
                "2026-03-19T14:59:59.000Z",
            ),
        ):
            payload = cast(dict[str, Any], await market.get_market_calendar())

        self.assertEqual(payload, {"status": "success", "data": []})
        self.assertIsNone(market._calendar_cache["data"])
        called_url = cast(str, mock_to_thread.call_args.args[1])
        self.assertIn("from=2026-03-11T15:00:00.000Z", called_url)
        self.assertIn("to=2026-03-19T14:59:59.000Z", called_url)

    @patch("app.routes.market.asyncio.to_thread", new_callable=AsyncMock)
    async def test_non_empty_results_are_cached(self, mock_to_thread):
        now_kst = market.KST.localize(dt.datetime(2026, 3, 12, 10, 15, 0))
        mock_to_thread.return_value = [
            {
                "date": "2026-03-12T03:00:00.000Z",
                "country": "US",
                "importance": 0,
                "title": "Initial Jobless Claims",
                "actual": "210K",
                "forecast": "215K",
                "previous": "220K",
            }
        ]

        with patch(
            "app.routes.market._build_calendar_window",
            return_value=(
                now_kst,
                "2026-03-11T15:00:00.000Z",
                "2026-03-19T14:59:59.000Z",
            ),
        ):
            payload = cast(dict[str, Any], await market.get_market_calendar())

        self.assertEqual(payload["status"], "success")
        self.assertEqual(len(payload["data"]), 1)
        self.assertEqual(payload["data"][0]["time"], "03/12(목) 12:00")
        self.assertEqual(payload["data"][0]["event"], "US - 신규 실업수당 청구건수")
        self.assertEqual(market._calendar_cache["data"], payload)


if __name__ == "__main__":
    unittest.main()
