from __future__ import annotations

import base64
import datetime as dt
import os
import shutil
import tempfile
import unittest
from typing import cast
from unittest.mock import patch

from fastapi.testclient import TestClient

from app import config, runtime_paths
from app.main import app
from app.services.scheduled_order_store import ScheduledOrderStore, build_account_ref
from app.services.scheduled_order_worker import ScheduledOrderWorker
from app.session_store import SessionData, active_sessions


class ScheduledOrdersApiTests(unittest.TestCase):
    client: TestClient = cast(TestClient, cast(object, None))

    def setUp(self):
        active_sessions.clear()
        self._tmpdir = tempfile.mkdtemp(prefix="scheduled-orders-test-")
        self._orig_get_user_data_dir = runtime_paths.get_user_data_dir
        runtime_paths.get_user_data_dir = lambda: self._tmpdir  # type: ignore[assignment]
        self._orig_server_mode = config.CENTRAL_ORDER_SERVER_MODE
        self._orig_server_token = config.CENTRAL_ORDER_SERVER_TOKEN
        self._orig_master_key = config.CENTRAL_ORDER_MASTER_KEY
        self._orig_execution_enabled = config.CENTRAL_ORDER_EXECUTION_ENABLED
        self._orig_remote_url = config.CENTRAL_ORDER_REMOTE_URL
        self._orig_remote_token = config.CENTRAL_ORDER_REMOTE_TOKEN
        config.CENTRAL_ORDER_SERVER_MODE = True
        config.CENTRAL_ORDER_SERVER_TOKEN = "server-token"
        config.CENTRAL_ORDER_MASTER_KEY = base64.urlsafe_b64encode(os.urandom(32)).decode("utf-8")
        config.CENTRAL_ORDER_EXECUTION_ENABLED = False
        config.CENTRAL_ORDER_REMOTE_URL = ""
        config.CENTRAL_ORDER_REMOTE_TOKEN = ""
        app.state.scheduled_order_store = ScheduledOrderStore(config.CENTRAL_ORDER_MASTER_KEY)
        app.state.scheduled_order_worker = ScheduledOrderWorker(app.state.scheduled_order_store)
        self.client = TestClient(app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        active_sessions.clear()
        runtime_paths.get_user_data_dir = self._orig_get_user_data_dir  # type: ignore[assignment]
        config.CENTRAL_ORDER_SERVER_MODE = self._orig_server_mode
        config.CENTRAL_ORDER_SERVER_TOKEN = self._orig_server_token
        config.CENTRAL_ORDER_MASTER_KEY = self._orig_master_key
        config.CENTRAL_ORDER_EXECUTION_ENABLED = self._orig_execution_enabled
        config.CENTRAL_ORDER_REMOTE_URL = self._orig_remote_url
        config.CENTRAL_ORDER_REMOTE_TOKEN = self._orig_remote_token
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def test_remote_create_requires_valid_token(self):
        response = self.client.post(
            "/api/central-server/scheduled-orders",
            json={
                "execute_at": "2099-01-01T08:00:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "70000",
                "execution_credentials": {
                    "app_key": "key",
                    "app_secret": "secret",
                    "cano": "12345678",
                    "acnt_prdt_cd": "01",
                },
            },
        )
        self.assertEqual(response.status_code, 401)

    def test_remote_create_and_list_order(self):
        create_response = self.client.post(
            "/api/central-server/scheduled-orders",
            headers={"Authorization": "Bearer server-token"},
            json={
                "execute_at": "2099-01-01T08:00:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 2,
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
                "execution_credentials": {
                    "app_key": "key",
                    "app_secret": "secret",
                    "cano": "12345678",
                    "acnt_prdt_cd": "01",
                },
                "source_app": "android",
            },
        )
        self.assertEqual(create_response.status_code, 200)
        payload = create_response.json()
        self.assertEqual(payload["status"], "success")
        self.assertEqual(payload["order"]["status"], "scheduled")

        account_ref = build_account_ref("key", "12345678", "01")
        list_response = self.client.get(
            f"/api/central-server/scheduled-orders?account_ref={account_ref}",
            headers={"Authorization": "Bearer server-token"},
        )
        self.assertEqual(list_response.status_code, 200)
        listed = list_response.json()
        self.assertEqual(len(listed["orders"]), 1)
        self.assertEqual(listed["orders"][0]["source_app"], "android")
        self.assertEqual(listed["orders"][0]["order"]["pdno"], "005930")

    @patch("app.api_client.inquire_domestic_psbl_sell", return_value=[{"pdno": "005930", "ord_psbl_qty": 5}])
    @patch("app.api_client.get_access_token", return_value="token")
    def test_local_session_route_creates_order_without_exposing_remote_token(self, _get_access_token, _mock_inquire_psbl_sell):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.post(
            "/api/scheduled-orders",
            json={
                "execute_at": "2099-01-01T08:00:00+09:00",
                "side": "sell",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "71000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
                "note": "take profit",
            },
        )
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["order"]["source_app"], "desktop-web")
        self.assertEqual(payload["order"]["note"], "take profit")

    @patch("app.api_client.inquire_domestic_psbl_sell", return_value=[{"pdno": "005930", "ord_psbl_qty": 5}])
    @patch("app.api_client.get_access_token", return_value="token")
    def test_local_session_route_updates_scheduled_order(self, _get_access_token, _mock_inquire_psbl_sell):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        create_response = self.client.post(
            "/api/scheduled-orders",
            json={
                "execute_at": "2099-01-01T08:00:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "70000",
            },
        )
        order_id = create_response.json()["order"]["id"]

        update_response = self.client.put(
            f"/api/scheduled-orders/{order_id}",
            json={
                "execute_at": "2099-01-01T08:10:00+09:00",
                "side": "sell",
                "pdno": "005930",
                "ord_qty": 2,
                "ord_unpr": "71000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
                "note": "edited order",
            },
        )

        self.assertEqual(update_response.status_code, 200)
        payload = update_response.json()["order"]
        self.assertEqual(payload["order"]["side"], "sell")
        self.assertEqual(payload["order"]["ord_qty"], "2")
        self.assertEqual(payload["note"], "edited order")
        self.assertIn("08:10:00", payload["end_at"])

    def test_local_session_route_updates_submitted_order_end_time_only(self):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            end_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        store.mark_submitted(created["id"], {"broker_order": {"odno": "12345"}, "output": {"ODNO": "12345"}})

        response = self.client.put(
            f"/api/scheduled-orders/{created['id']}",
            json={
                "execute_at": "2099-01-01T08:30:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()["order"]
        self.assertEqual(payload["status"], "submitted")
        self.assertIn("08:30:00", payload["end_at"])

    def test_local_session_route_rejects_price_change_for_submitted_order(self):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            end_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        store.mark_submitted(created["id"], {"broker_order": {"odno": "12345"}, "output": {"ODNO": "12345"}})

        response = self.client.put(
            f"/api/scheduled-orders/{created['id']}",
            json={
                "execute_at": "2099-01-01T08:30:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "71000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
        )

        self.assertEqual(response.status_code, 409)
        self.assertIn("주문 단가", response.json()["detail"])

    def test_local_session_route_rejects_non_domestic_symbol_code(self):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.post(
            "/api/scheduled-orders",
            json={
                "execute_at": "2099-01-01T08:00:00+09:00",
                "side": "buy",
                "pdno": "AAPL",
                "ord_qty": 1,
                "ord_unpr": "70000",
            },
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("Domestic scheduled orders", response.json()["detail"])

    def test_local_session_route_allows_create_without_market_hour_block(self):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.post(
            "/api/scheduled-orders",
            json={
                "execute_at": "2099-01-01T08:00:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "70000",
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["order"]["status"], "scheduled")

    @patch("app.api_client.inquire_domestic_psbl_sell", return_value=[{"pdno": "005930", "ord_psbl_qty": 5}])
    @patch("app.api_client.get_access_token", return_value="token")
    def test_local_session_route_allows_update_without_market_hour_block(self, _get_access_token, _mock_inquire_psbl_sell):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        create_response = self.client.post(
            "/api/scheduled-orders",
            json={
                "execute_at": "2099-01-01T08:00:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "70000",
            },
        )
        order_id = create_response.json()["order"]["id"]

        response = self.client.put(
            f"/api/scheduled-orders/{order_id}",
            json={
                "execute_at": "2099-01-01T08:10:00+09:00",
                "side": "sell",
                "pdno": "005930",
                "ord_qty": 2,
                "ord_unpr": "71000",
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["order"]["order"]["side"], "sell")

    @patch("app.api_client.inquire_domestic_psbl_sell", return_value=[{"pdno": "005930", "ord_psbl_qty": 0}])
    @patch("app.api_client.get_access_token", return_value="token")
    def test_local_session_route_rejects_sell_order_exceeding_orderable_qty(self, _get_access_token, _mock_inquire_psbl_sell):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.post(
            "/api/scheduled-orders",
            json={
                "execute_at": "2099-01-01T08:00:00+09:00",
                "side": "sell",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "71000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("매도 가능 수량", response.json()["detail"])

    def test_scheduled_order_availability_reports_allowed(self):
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.get("/api/scheduled-orders/availability")

        self.assertEqual(response.status_code, 200)
        payload = response.json()["availability"]
        self.assertTrue(payload["allowed"])
        self.assertFalse(payload["blocked"])
        self.assertEqual(payload["policy"]["timezone"], "Asia/Seoul")
        self.assertEqual(payload["policy"]["rule"], "persistent_condition_order")

    def test_remote_update_allows_submitted_order_end_time_only(self):
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        store.mark_submitted(created["id"], {"output": {"ODNO": "12345"}})

        response = self.client.put(
            f"/api/central-server/scheduled-orders/{created['id']}",
            headers={"Authorization": "Bearer server-token"},
            json={
                "execute_at": "2099-01-01T08:20:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 1,
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
                "execution_credentials": {
                    "app_key": "key",
                    "app_secret": "secret",
                    "cano": "12345678",
                    "acnt_prdt_cd": "01",
                },
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertIn("08:20:00", response.json()["order"]["end_at"])

    def test_remote_update_rejects_qty_change_for_submitted_order(self):
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        store.mark_submitted(created["id"], {"output": {"ODNO": "12345"}})

        response = self.client.put(
            f"/api/central-server/scheduled-orders/{created['id']}",
            headers={"Authorization": "Bearer server-token"},
            json={
                "execute_at": "2099-01-01T08:20:00+09:00",
                "side": "buy",
                "pdno": "005930",
                "ord_qty": 2,
                "ord_unpr": "70000",
                "execution_credentials": {
                    "app_key": "key",
                    "app_secret": "secret",
                    "cano": "12345678",
                    "acnt_prdt_cd": "01",
                },
            },
        )

        self.assertEqual(response.status_code, 409)
        self.assertIn("주문 수량", response.json()["detail"])

    @patch("app.api_client.inquire_domestic_psbl_rvsecncl")
    @patch("app.api_client.inquire_domestic_daily_ccld")
    @patch("app.api_client.get_access_token", return_value="token")
    def test_sync_updates_submitted_order_to_open(self, _get_access_token, mock_inquire_daily, mock_inquire_psbl):
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "2",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        store.mark_submitted(
            created["id"],
            {
                "broker_order": {"odno": "12345", "krx_fwdg_ord_orgno": "00001"},
                "output": {"ODNO": "12345"},
            },
        )
        mock_inquire_daily.return_value = [{
            "odno": "12345",
            "ord_qty": 2,
            "tot_ccld_qty": 0,
            "rmn_qty": 2,
            "cncl_yn": "N",
        }]
        mock_inquire_psbl.return_value = [{
            "odno": "12345",
            "psbl_qty": 2,
            "ord_qty": 2,
            "tot_ccld_qty": 0,
            "ord_dvsn_cd": "00",
            "excg_id_dvsn_cd": "SOR",
        }]
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.post("/api/scheduled-orders/sync")

        self.assertEqual(response.status_code, 200)
        payload = response.json()["orders"][0]
        self.assertEqual(payload["status"], "open")
        self.assertEqual(payload["broker_order"]["remaining_qty"], 2)

    @patch("app.api_client.inquire_domestic_psbl_rvsecncl", return_value=[])
    @patch("app.api_client.inquire_domestic_daily_ccld")
    @patch("app.api_client.get_access_token", return_value="token")
    def test_sync_reschedules_remaining_qty_for_next_session(self, _get_access_token, mock_inquire_daily, _mock_inquire_psbl):
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "3",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "SOR",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        store.mark_submitted(
            created["id"],
            {
                "broker_order": {"odno": "12345", "krx_fwdg_ord_orgno": "00001"},
                "output": {"ODNO": "12345"},
            },
        )
        mock_inquire_daily.return_value = [{
            "odno": "12345",
            "ord_qty": 3,
            "tot_ccld_qty": 1,
            "rmn_qty": 2,
            "cncl_yn": "N",
        }]
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.post("/api/scheduled-orders/sync")

        self.assertEqual(response.status_code, 200)
        payload = response.json()["orders"][0]
        self.assertEqual(payload["status"], "scheduled")
        self.assertEqual(payload["order"]["ord_qty"], "2")
        self.assertEqual(payload["condition_state"]["filled_qty"], 1)
        self.assertEqual(payload["condition_state"]["remaining_qty"], 2)

    @patch("app.api_client.cancel_domestic_order")
    @patch("app.api_client.inquire_domestic_psbl_rvsecncl")
    @patch("app.api_client.get_access_token", return_value="token")
    def test_cancel_submitted_order_uses_broker_cancel(self, _get_access_token, mock_inquire_psbl, mock_cancel):
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "2",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        store.mark_submitted(
            created["id"],
            {
                "broker_order": {"odno": "12345", "krx_fwdg_ord_orgno": "00001"},
                "output": {"ODNO": "12345"},
            },
        )
        mock_inquire_psbl.return_value = [{
            "odno": "12345",
            "psbl_qty": 2,
            "ord_qty": 2,
            "tot_ccld_qty": 0,
            "ord_dvsn_cd": "00",
            "excg_id_dvsn_cd": "NXT",
        }]
        active_sessions["test-session"] = SessionData("key", "secret", "12345678", "01")
        self.client.cookies.set("session", "test-session")

        response = self.client.delete(f"/api/scheduled-orders/{created['id']}")

        self.assertEqual(response.status_code, 200)
        payload = response.json()["order"]
        self.assertEqual(payload["status"], "cancel_requested")
        self.assertEqual(payload["broker_order"]["odno"], "12345")
        mock_cancel.assert_called_once()

    @patch("app.services.scheduled_order_worker._is_resubmit_window", return_value=True)
    @patch("app.api_client.place_domestic_order_cash")
    @patch("app.api_client.get_access_token", return_value="token")
    def test_worker_marks_due_order_submitted(self, _get_access_token, mock_place_order, _mock_window):
        mock_place_order.return_value = {"tr_id": "TTTC0012U", "output": {"ODNO": "12345"}, "raw": {"rt_cd": "0"}}
        config.CENTRAL_ORDER_EXECUTION_ENABLED = True
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2000-01-01T08:00:00+09:00",
            end_at="2099-01-03T20:00:00+09:00",
            source_app="android",
        )

        worker = cast(ScheduledOrderWorker, app.state.scheduled_order_worker)
        worker.process_due_orders_once()

        listed = store.list_orders(account_ref=created["account_ref"])
        self.assertEqual(listed[0]["status"], "submitted")
        self.assertEqual(listed[0]["execution_result"]["output"]["ODNO"], "12345")

    @patch("app.api_client.inquire_domestic_psbl_sell")
    @patch("app.api_client.place_domestic_order_cash")
    @patch("app.api_client.get_access_token", return_value="token")
    @patch("app.services.scheduled_order_worker._is_resubmit_window", return_value=True)
    def test_worker_prevents_sell_order_exceeding_orderable_qty(
        self,
        _mock_window,
        _get_access_token,
        mock_place_order,
        mock_inquire_psbl_sell,
    ):
        mock_inquire_psbl_sell.return_value = [
            {
                "pdno": "005930",
                "ord_psbl_qty": 0,
            }
        ]
        mock_place_order.return_value = {"tr_id": "TTTC0011U", "output": {"ODNO": "12345"}, "raw": {"rt_cd": "0"}}
        config.CENTRAL_ORDER_EXECUTION_ENABLED = True
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "sell",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2000-01-01T08:00:00+09:00",
            end_at="2099-01-03T20:00:00+09:00",
            source_app="android",
        )

        worker = cast(ScheduledOrderWorker, app.state.scheduled_order_worker)
        worker.process_due_orders_once()

        listed = store.list_orders(account_ref=created["account_ref"])
        self.assertEqual(listed[0]["status"], "failed")
        self.assertIn("매도 가능 수량", listed[0]["last_error"])
        mock_place_order.assert_not_called()

    @patch("app.api_client.inquire_domestic_psbl_sell", return_value=[{"pdno": "005930", "ord_psbl_qty": 10}])
    @patch("app.api_client.place_domestic_order_cash")
    @patch("app.api_client.get_access_token", return_value="token")
    @patch("app.services.scheduled_order_worker._is_resubmit_window", return_value=True)
    def test_worker_allows_sell_order_within_orderable_qty(
        self,
        _mock_window,
        _get_access_token,
        mock_place_order,
        mock_inquire_psbl_sell,
    ):
        mock_place_order.return_value = {"tr_id": "TTTC0011U", "output": {"ODNO": "12345"}, "raw": {"rt_cd": "0"}}
        config.CENTRAL_ORDER_EXECUTION_ENABLED = True
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "sell",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "NXT",
            },
            execute_at="2000-01-01T08:00:00+09:00",
            end_at="2099-01-03T20:00:00+09:00",
            source_app="android",
        )

        worker = cast(ScheduledOrderWorker, app.state.scheduled_order_worker)
        worker.process_due_orders_once()

        listed = store.list_orders(account_ref=created["account_ref"])
        self.assertEqual(listed[0]["status"], "submitted")
        self.assertEqual(listed[0]["execution_result"]["output"]["ODNO"], "12345")
        mock_inquire_psbl_sell.assert_called_once()
        mock_place_order.assert_called_once()

    def test_list_orders_hides_terminal_orders_after_24_hours(self):
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        orders = store._load_locked()
        record = orders[created["id"]]
        old_updated_at = (dt.datetime.now(dt.timezone(dt.timedelta(hours=9))) - dt.timedelta(hours=25)).isoformat(timespec="seconds")
        record["status"] = "filled"
        record["updated_at"] = old_updated_at
        store._save_locked()

        listed = store.list_orders(account_ref=created["account_ref"])

        self.assertEqual(listed, [])

    def test_reconcile_broker_order_clears_stale_broker_message(self):
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
            },
            execute_at="2099-01-01T08:00:00+09:00",
            source_app="desktop-web",
        )
        store.mark_submitted(created["id"], {"broker_order": {"odno": "12345"}, "output": {"ODNO": "12345"}})
        store.mark_expired(created["id"], broker_snapshot={"odno": "12345"}, broker_message="종료 시각 전에 다음 세션이 없어 자동 종료")

        reconciled = store.reconcile_broker_order(
            created["id"],
            status="broker_cancelled",
            filled_qty=0,
            remaining_qty=0,
            broker_snapshot={"odno": "12345"},
            broker_message="",
        )

        self.assertIsNotNone(reconciled)
        self.assertEqual(reconciled["broker_order"]["last_broker_message"], "")

    @patch("app.services.scheduled_order_worker._next_session_start")
    @patch("app.services.scheduled_order_worker._is_resubmit_window", return_value=False)
    def test_worker_reschedules_due_order_when_session_closed(self, _mock_window, mock_next_session_start):
        mock_next_session_start.return_value = __import__("datetime").datetime(2099, 1, 2, 8, 0, 0, tzinfo=__import__("datetime").timezone(__import__("datetime").timedelta(hours=9)))
        store = cast(ScheduledOrderStore, app.state.scheduled_order_store)
        created = store.create_order(
            execution_credentials={
                "app_key": "key",
                "app_secret": "secret",
                "cano": "12345678",
                "acnt_prdt_cd": "01",
            },
            order_payload={
                "side": "buy",
                "pdno": "005930",
                "ord_qty": "1",
                "ord_unpr": "70000",
                "ord_dvsn": "00",
                "excg_id_dvsn_cd": "SOR",
            },
            execute_at="2000-01-01T08:00:00+09:00",
            end_at="2099-01-03T20:00:00+09:00",
            source_app="desktop-web",
        )
        config.CENTRAL_ORDER_EXECUTION_ENABLED = True
        worker = cast(ScheduledOrderWorker, app.state.scheduled_order_worker)

        worker.process_due_orders_once()

        listed = store.list_orders(account_ref=created["account_ref"])
        self.assertEqual(listed[0]["status"], "scheduled")
        self.assertEqual(listed[0]["order"]["ord_qty"], "1")
        self.assertIn("2099-01-02T08:00:00+09:00", listed[0]["execute_at"])
