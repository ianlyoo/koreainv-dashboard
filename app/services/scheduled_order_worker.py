from __future__ import annotations

import threading
import datetime as dt
from collections.abc import Mapping

from app import api_client, config
from app.services.scheduled_order_store import ScheduledOrderStore

SESSION_START_HOUR = 8
SESSION_END_HOUR = 20


def _now_kst() -> dt.datetime:
    return dt.datetime.now(dt.timezone(dt.timedelta(hours=9)))


def _is_resubmit_window(now: dt.datetime) -> bool:
    return now.weekday() < 5 and (now.hour, now.minute) >= (SESSION_START_HOUR, 0) and (now.hour, now.minute) < (SESSION_END_HOUR, 0)


def _next_session_start(now: dt.datetime) -> dt.datetime:
    cursor = now
    if cursor.weekday() < 5 and (cursor.hour, cursor.minute) < (SESSION_START_HOUR, 0):
        return cursor.replace(hour=SESSION_START_HOUR, minute=0, second=0, microsecond=0)
    cursor = (cursor + dt.timedelta(days=1)).replace(hour=SESSION_START_HOUR, minute=0, second=0, microsecond=0)
    while cursor.weekday() >= 5:
        cursor += dt.timedelta(days=1)
    return cursor


def _assert_domestic_sell_orderable(order: Mapping[str, object], credentials: Mapping[str, str], token: str) -> None:
    side = str(order.get("side") or "").strip().lower()
    if side != "sell":
        return

    pdno = str(order.get("pdno") or "").strip()
    if not pdno:
        raise RuntimeError("Domestic scheduled sell order is missing symbol")

    ord_qty = api_client._to_int(order.get("ord_qty") or 0)
    if ord_qty <= 0:
        raise RuntimeError("매도 주문 수량이 유효하지 않습니다")

    psbl_rows = api_client.inquire_domestic_psbl_sell(
        token,
        credentials["app_key"],
        credentials["app_secret"],
        credentials["cano"],
        credentials["acnt_prdt_cd"],
        pdno=pdno,
        ord_dvsn=str(order.get("ord_dvsn") or "00"),
        excg_id_dvsn_cd=str(order.get("excg_id_dvsn_cd") or "KRX"),
    )
    psbl_row = next((row for row in psbl_rows if not pdno or str(row.get("pdno") or "") == pdno), None)
    if psbl_row is None:
        raise RuntimeError("주문 가능 수량 조회 결과를 찾을 수 없습니다")

    ord_psbl_qty = api_client._to_int(psbl_row.get("ord_psbl_qty") or psbl_row.get("psbl_qty") or 0)
    if ord_psbl_qty <= 0:
        raise RuntimeError("매도 가능 수량이 0입니다")

    if ord_qty > ord_psbl_qty:
        raise RuntimeError(f"주문 가능 수량 초과: 주문 수량 {ord_qty}주, 가능 수량 {ord_psbl_qty}주")


class ScheduledOrderWorker:
    def __init__(self, store: ScheduledOrderStore, poll_interval_seconds: int = 5) -> None:
        self._store = store
        self._poll_interval_seconds = max(1, int(poll_interval_seconds))
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run_forever,
            name="scheduled-order-worker",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=3)

    def process_due_orders_once(self) -> None:
        if not config.CENTRAL_ORDER_EXECUTION_ENABLED:
            return
        for record in self._store.claim_due_orders():
            order_id = str(record.get("id") or "")
            try:
                now = _now_kst()
                end_at_raw = str(record.get("end_at") or "")
                end_at = dt.datetime.fromisoformat(end_at_raw) if end_at_raw else now
                if end_at <= now:
                    self._store.mark_expired(order_id, broker_message="종료 시각 도달로 자동 종료")
                    continue
                if not _is_resubmit_window(now):
                    next_start = _next_session_start(now)
                    if next_start >= end_at:
                        self._store.mark_expired(order_id, broker_message="종료 시각 전에 제출 가능한 세션이 없어 자동 종료")
                        continue
                    self._store.schedule_resubmit(
                        order_id,
                        execute_at=next_start.isoformat(timespec="seconds"),
                        remaining_qty=api_client._to_int(record.get("order", {}).get("ord_qty") if isinstance(record.get("order"), Mapping) else 0),
                        filled_qty=api_client._to_int(record.get("condition_state", {}).get("filled_qty") if isinstance(record.get("condition_state"), Mapping) else 0),
                        broker_snapshot={"status": "awaiting_resubmit"},
                        broker_message="세션 시작 대기 중",
                    )
                    continue
                credentials = self._store.decrypt_execution_credentials(record)
                raw_order = record.get("order")
                if not isinstance(raw_order, Mapping):
                    raise RuntimeError("central_order_payload_missing")
                order = raw_order
                token = api_client.get_access_token(
                    credentials["app_key"], credentials["app_secret"]
                )
                if not token:
                    raise RuntimeError("central_order_token_failure")

                _assert_domestic_sell_orderable(order, credentials, token)

                result = api_client.place_domestic_order_cash(
                    token,
                    credentials["app_key"],
                    credentials["app_secret"],
                    credentials["cano"],
                    credentials["acnt_prdt_cd"],
                    side=str(order.get("side") or ""),
                    pdno=str(order.get("pdno") or ""),
                    ord_dvsn=str(order.get("ord_dvsn") or "00"),
                    ord_qty=str(order.get("ord_qty") or ""),
                    ord_unpr=str(order.get("ord_unpr") or ""),
                    excg_id_dvsn_cd=str(order.get("excg_id_dvsn_cd") or "KRX"),
                    sll_type=str(order.get("sll_type") or ""),
                    cndt_pric=str(order.get("cndt_pric") or ""),
                )
                self._store.mark_submitted(order_id, result)
            except Exception as exc:
                self._store.mark_failed(order_id, str(exc))

    def reconcile_submitted_orders_once(self) -> None:
        for record in self._store.list_reconcilable_orders():
            try:
                self._reconcile_record(record)
            except Exception:
                continue

    def _reconcile_record(self, record: Mapping[str, object]) -> None:
        order_id = str(record.get("id") or "")
        credentials = self._store.decrypt_execution_credentials(record)
        raw_broker_order = record.get("broker_order")
        broker_order: Mapping[str, object] = raw_broker_order if isinstance(raw_broker_order, Mapping) else {}
        odno = str(broker_order.get("odno") or "")
        if not odno:
            return
        now = _now_kst()
        end_at_raw = str(record.get("end_at") or "")
        end_at = dt.datetime.fromisoformat(end_at_raw) if end_at_raw else now
        token = api_client.get_access_token(credentials["app_key"], credentials["app_secret"])
        if not token:
            return
        created_at = str(record.get("created_at") or "")[:10]
        start_date = created_at or "1970-01-01"
        end_date = str(dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).date())
        rows = api_client.inquire_domestic_daily_ccld(
            token,
            credentials["app_key"],
            credentials["app_secret"],
            credentials["cano"],
            credentials["acnt_prdt_cd"],
            order_no=odno,
            start_date=start_date,
            end_date=end_date,
        )
        match = next((row for row in rows if str(row.get("odno") or "") == odno), None)
        psbl_rows = api_client.inquire_domestic_psbl_rvsecncl(
            token,
            credentials["app_key"],
            credentials["app_secret"],
            credentials["cano"],
            credentials["acnt_prdt_cd"],
        )
        cancelable = next((row for row in psbl_rows if str(row.get("odno") or "") == odno and api_client._to_int(row.get("psbl_qty") or 0) > 0), None)

        base_filled = api_client._to_int(broker_order.get("base_filled_qty") or 0)
        cycle_submitted = api_client._to_int(broker_order.get("submitted_qty") or 0)
        cycle_filled = api_client._to_int(match.get("tot_ccld_qty") or 0) if isinstance(match, Mapping) else 0
        total_filled = base_filled + cycle_filled
        remaining_qty = max(cycle_submitted - cycle_filled, 0)
        current_status = str(record.get("status") or "")

        if cancelable is not None:
            if now >= end_at:
                if current_status != "cancel_requested":
                    raw_order = record.get("order")
                    order: Mapping[str, object] = raw_order if isinstance(raw_order, Mapping) else {}
                    cancelable_order = cancelable if isinstance(cancelable, Mapping) else {}
                    api_client.cancel_domestic_order(
                        token,
                        credentials["app_key"],
                        credentials["app_secret"],
                        credentials["cano"],
                        credentials["acnt_prdt_cd"],
                        krx_fwdg_ord_orgno=str(broker_order.get("krx_fwdg_ord_orgno") or ""),
                        orgn_odno=odno,
                        ord_qty=str(cancelable_order.get("psbl_qty") or order.get("ord_qty") or ""),
                        ord_unpr=str(order.get("ord_unpr") or ""),
                        ord_dvsn=str(order.get("ord_dvsn") or "00"),
                        excg_id_dvsn_cd=str(order.get("excg_id_dvsn_cd") or "SOR"),
                        qty_all_ord_yn="Y",
                    )
                    self._store.mark_broker_cancel_requested(
                        order_id,
                        broker_snapshot=dict(cancelable_order),
                    )
                return
            live_status = "cancel_requested" if current_status == "cancel_requested" else "open"
            snapshot = dict(match) if isinstance(match, Mapping) else {}
            snapshot.update(dict(cancelable))
            self._store.reconcile_broker_order(
                order_id,
                status=live_status,
                filled_qty=total_filled,
                remaining_qty=remaining_qty,
                broker_snapshot=snapshot,
            )
            return

        if remaining_qty <= 0:
            terminal_status = "broker_cancelled" if current_status == "cancel_requested" else "filled"
            self._store.reconcile_broker_order(
                order_id,
                status=terminal_status,
                filled_qty=total_filled,
                remaining_qty=0,
                broker_snapshot=dict(match) if isinstance(match, Mapping) else {},
            )
            return

        if current_status == "cancel_requested":
            if now >= end_at:
                self._store.mark_expired(
                    order_id,
                    broker_snapshot=dict(match) if isinstance(match, Mapping) else {},
                    broker_message="종료 시각 도달 후 자동 취소 완료",
                )
            else:
                self._store.reconcile_broker_order(
                    order_id,
                    status="broker_cancelled",
                    filled_qty=total_filled,
                    remaining_qty=remaining_qty,
                    broker_snapshot=dict(match) if isinstance(match, Mapping) else {},
                    broker_message="브로커 취소 완료",
                )
            return

        if now >= end_at:
            self._store.mark_expired(
                order_id,
                broker_snapshot=dict(match) if isinstance(match, Mapping) else {},
                broker_message="종료 시각 도달로 자동 종료",
            )
            return

        next_start = _next_session_start(now)
        if next_start >= end_at:
            self._store.mark_expired(
                order_id,
                broker_snapshot=dict(match) if isinstance(match, Mapping) else {},
                broker_message="종료 시각 전에 다음 세션이 없어 자동 종료",
            )
            return
        self._store.schedule_resubmit(
            order_id,
            execute_at=next_start.isoformat(timespec="seconds"),
            remaining_qty=remaining_qty,
            filled_qty=total_filled,
            broker_snapshot=dict(match) if isinstance(match, Mapping) else {},
            broker_message="미체결 잔량 재주문 대기",
        )

    def _run_forever(self) -> None:
        while not self._stop_event.wait(self._poll_interval_seconds):
            self.process_due_orders_once()
            self.reconcile_submitted_orders_once()
