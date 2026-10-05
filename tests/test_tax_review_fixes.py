from __future__ import annotations

import asyncio
import copy
import json
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import requests
from fastapi import HTTPException
from app import toss_api_client as toss
from app.routes.toss_proxy import _run_tax_history


class TaxReviewFixesTests(unittest.TestCase):
    def setUp(self):
        toss.clear_token_cache()
        self.orders = json.loads((Path(__file__).parent / "fixtures/toss/orders-and-fx.json").read_text())["orders"]["result"]["orders"]

    def annual(self, orders=None):
        with patch.object(toss, "_fetch_closed_orders", return_value=(orders or self.orders, True)), patch.object(toss, "_get_usd_exchange_rate", return_value=1300):
            return toss.get_trade_history("fake-client", "fake-secret", "9", "2026-01-01", "2026-12-31", tax_estimate=True)

    def test_malformed_empty_response_retains_incomplete_history(self):
        with patch.object(toss, "_authorized_get", return_value={"result": {}}), patch.object(toss, "_get_usd_exchange_rate", return_value=1300):
            result = toss.get_trade_history("c", "s", "9", "2026-01-01", "2026-12-31", tax_estimate=True)
        self.assertEqual(result["items"], [])
        self.assertFalse(result["profit_history_complete"])
        self.assertFalse(result["profit_complete"])

    def test_empty_broken_cursor_retains_incomplete_history(self):
        with patch.object(toss, "_authorized_get", return_value={"result": {"orders": [], "hasNext": True, "nextCursor": ""}}):
            rows, complete = toss._fetch_closed_orders("c", "s", "9", end_date="2026-12-31")
        self.assertFalse(complete)
        self.assertEqual(rows, [])

    def test_repeated_cursor_keeps_nonempty_partial_history(self):
        page = {"result": {"orders": [self.orders[0]], "hasNext": True, "nextCursor": "same"}}
        with patch.object(toss, "_authorized_get", return_value=page) as get:
            rows, complete = toss._fetch_closed_orders("c", "s", "9", end_date="2026-12-31")
        self.assertFalse(complete)
        self.assertTrue(rows)
        self.assertEqual(get.call_count, 2)

    def test_hundred_page_exhaustion_is_not_eof(self):
        def page(*args, **kwargs):
            cursor = int(kwargs["params"].get("cursor", "0")) + 1
            return {"result": {"orders": [self.orders[0]], "hasNext": True, "nextCursor": str(cursor)}}
        with patch.object(toss, "_authorized_get", side_effect=page) as get:
            rows, complete = toss._fetch_closed_orders("c", "s", "9", end_date="2026-12-31")
        self.assertFalse(complete)
        self.assertEqual(len(rows), 100)
        self.assertEqual(get.call_count, 100)

    def test_all_timeouts_stop_after_two_requests_within_budget(self):
        # 500 needed windows, clock advances by each actual transport timeout.
        clock = [0.0]
        def timeout(*args, **kwargs):
            clock[0] += kwargs["timeout"]
            raise requests.Timeout("synthetic timeout")
        orders = []
        for n in range(250):
            for template in (self.orders[0], self.orders[2]):
                row = copy.deepcopy(template)
                row["symbol"] = f"FAKE{n}"
                row["orderId"] = f"{n}-{row['side']}"
                orders.append(row)
        with patch.object(toss, "get_access_token", return_value="fake-token"), patch.object(toss.requests, "get", side_effect=timeout) as get, patch.object(toss.time, "monotonic", side_effect=lambda: clock[0]), patch.object(toss.FxBudget, "pause"):
            result = self.annual(orders)
        self.assertEqual(get.call_count, 2)
        self.assertLessEqual(clock[0], toss.TAX_FX_SECONDS)
        self.assertEqual(result["tax_fx_incomplete_reason"], "repeated_failures")
        self.assertTrue(all(row.get("buy_amount_native", 0) > 0 for row in result["items"] if row["side"] == "매도"))
        self.assertEqual(len(result["tax_executions"]), 500)

    def test_successful_quotes_obey_request_budget_and_deadline(self):
        orders = []
        for n in range(250):
            for template in (self.orders[0], self.orders[2]):
                row = copy.deepcopy(template)
                row["symbol"] = f"FAKE{n}"
                row["orderId"] = f"{n}-{row['side']}"
                timestamp = row["execution"]["filledAt"]
                row["execution"]["filledAt"] = timestamp[:11] + f"10:{n // 60:02}:{n % 60:02}" + timestamp[19:]
                orders.append(row)
        def quote(*args, **kwargs):
            timestamp = kwargs["params"]["dateTime"]
            response = Mock(status_code=200)
            response.json.return_value = {"result": {"baseCurrency": "USD", "quoteCurrency": "KRW", "midRate": 1300, "validFrom": timestamp, "validUntil": "2099-01-01T00:00:00Z"}}
            return response
        with patch.object(toss, "get_access_token", return_value="fake-token"), patch.object(toss.requests, "get", side_effect=quote) as get, patch.object(toss.FxBudget, "pause"):
            result = self.annual(orders)
            self.assertEqual(get.call_count, toss.TAX_FX_REQUESTS)
            self.assertEqual(result["tax_fx_incomplete_reason"], "request_budget")
        clock = [0.0]
        with patch.object(toss.time, "monotonic", side_effect=lambda: clock[0]):
            work = toss.FxBudget()
            clock[0] = toss.TAX_FX_SECONDS - 1
            self.assertEqual(work.begin_request(), 0.5)
            clock[0] += 1
            self.assertFalse(work.allowed())
            self.assertEqual(work.reason, "time_budget")

    def test_cancellation_stops_inflight_result_before_next_quote(self):
        event = threading.Event()
        def cancel(*args, **kwargs):
            event.set()
            raise requests.Timeout("cancelled fake request")
        with patch.object(toss, "get_access_token", return_value="fake-token"), patch.object(toss.requests, "get", side_effect=cancel) as get, patch.object(toss.FxBudget, "pause"):
            work = toss.FxBudget(event)
            with self.assertRaises(toss.TaxWorkCancelled):
                toss._get_historical_usd_mid_rate("c", "s", "2026-06-01T10:00:00Z", work=work)
        self.assertEqual(get.call_count, 1)

    def test_trade_request_does_not_wait_for_tax_quote_lock(self):
        entered = threading.Event()
        release = threading.Event()
        errors = []
        def lookup(*args, **kwargs):
            timestamp = kwargs["params"]["dateTime"]
            if timestamp.startswith("2026-06"):
                entered.set()
                if not release.wait(2):
                    raise AssertionError("tax request was not released")
            return {"result": {"baseCurrency": "USD", "quoteCurrency": "KRW", "midRate": 1300, "validFrom": timestamp, "validUntil": "2027-01-01T00:00:00Z"}}
        def tax():
            try:
                toss._get_historical_usd_mid_rate("c", "s", "2026-06-01T10:00:00Z", work=toss.FxBudget())
            except Exception as exc:
                errors.append(exc)
        with patch.object(toss, "_authorized_get", side_effect=lookup), patch.object(toss.FxBudget, "pause"), patch.object(toss.time, "sleep"):
            thread = threading.Thread(target=tax)
            thread.start()
            try:
                self.assertTrue(entered.wait(1))
                start = time.monotonic()
                with patch.object(toss, "_fetch_closed_orders", return_value=(self.orders, True)), patch.object(toss, "_get_usd_exchange_rate", return_value=1300):
                    trade = toss.get_trade_history("c", "s", "9", "2026-09-01", "2026-09-30")
                self.assertTrue(trade["profit_available"])
                self.assertLess(time.monotonic() - start, 0.5)
            finally:
                release.set()
                thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual(errors, [])

    def test_consecutive_sales_keep_total_native_cost_after_failed_quote(self):
        rows = [dict(symbol="FAKE", currency="USD", side=side, quantity=1, amount_native=amount,
                     date=f"2026060{i}", time="090000", commission_native=0, tax_native=0, profit_exchange_rate=rate)
                for i, (side, amount, rate) in enumerate([("매수", 100, 0), ("매수", 200, 0), ("매도", 220, 1300), ("매도", 220, 0)], 1)]
        toss._estimate_realized_profit(rows, usd_exchange_rate=1300, start_date="2026-01-01", end_date="2026-12-31")
        self.assertEqual(sum(row.get("buy_amount_native", 0) for row in rows), 300)
        self.assertEqual(rows[-1]["buy_amount_native"], 150)
        self.assertIsNone(rows[-1]["realized_profit_krw"])


class TaxRouteCancellationTests(unittest.IsolatedAsyncioTestCase):
    async def run_cancellation(self, disconnected):
        entered = threading.Event()
        finished = threading.Event()
        def load(*, cancel_event):
            entered.set()
            try:
                if not cancel_event.wait(2):
                    raise AssertionError("server worker did not receive cancellation")
                raise toss.TaxWorkCancelled()
            finally:
                finished.set()
        request = Mock()
        async def is_disconnected():
            return disconnected and entered.is_set()
        request.is_disconnected = is_disconnected
        task = asyncio.create_task(_run_tax_history(request, load))
        while not entered.is_set():
            await asyncio.sleep(0.001)
        if disconnected:
            with self.assertRaises(HTTPException) as exc:
                await task
            self.assertEqual(exc.exception.status_code, 499)
        else:
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        self.assertTrue(await asyncio.to_thread(finished.wait, 1))

    async def test_disconnect_signals_server_worker(self):
        await self.run_cancellation(True)

    async def test_cancelled_route_signals_server_worker(self):
        await self.run_cancellation(False)
