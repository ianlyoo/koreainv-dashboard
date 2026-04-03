from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import tempfile
import threading
import uuid
from collections.abc import Mapping
from typing import Any

from cryptography.fernet import Fernet

from app import runtime_paths

KST = dt.timezone(dt.timedelta(hours=9))
STALE_EXECUTING_SECONDS = 15 * 60
TERMINAL_RETENTION_HOURS = 24
TERMINAL_ORDER_STATUSES = {"filled", "expired", "cancelled", "broker_cancelled", "failed"}
LIVE_EDITABLE_STATUSES = {"submitted", "open", "cancel_requested"}


def _now_kst() -> dt.datetime:
    return dt.datetime.now(KST)


def _parse_execute_at(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(str(value or "").strip())
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=KST)
    return parsed.astimezone(KST)


def build_account_ref(app_key: str, cano: str, acnt_prdt_cd: str) -> str:
    raw = "::".join(
        [
            str(app_key or "").strip(),
            str(cano or "").strip(),
            str(acnt_prdt_cd or "01").strip() or "01",
        ]
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class ScheduledOrderStore:
    def __init__(self, encryption_key: str) -> None:
        self._lock = threading.RLock()
        self._fernet = Fernet(encryption_key.encode("utf-8"))
        self._cache: dict[str, dict[str, Any]] | None = None

    def list_orders(self, account_ref: str | None = None) -> list[dict[str, Any]]:
        now = _now_kst()
        with self._lock:
            orders = self._load_locked()
            items = list(orders.values())
        if account_ref:
            items = [item for item in items if str(item.get("account_ref") or "") == account_ref]
        filtered: list[dict[str, Any]] = []
        retention_window = dt.timedelta(hours=TERMINAL_RETENTION_HOURS)
        for item in items:
            if not isinstance(item, dict):
                continue
            status = str(item.get("status") or "")
            if status in TERMINAL_ORDER_STATUSES:
                try:
                    updated_at = _parse_execute_at(str(item.get("updated_at") or item.get("created_at") or now.isoformat()))
                except Exception:
                    updated_at = now
                if now - updated_at > retention_window:
                    continue
            filtered.append(item)
        items = filtered
        items.sort(key=lambda item: str(item.get("created_at") or ""), reverse=True)
        return [self._public_order(item) for item in items]

    def get_order_record(self, order_id: str) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            return dict(record)

    def create_order(
        self,
        *,
        execution_credentials: Mapping[str, Any],
        order_payload: Mapping[str, Any],
        execute_at: str,
        end_at: str | None = None,
        source_app: str,
        note: str = "",
    ) -> dict[str, Any]:
        app_key = str(execution_credentials.get("app_key") or "").strip()
        cano = str(execution_credentials.get("cano") or "").strip()
        acnt_prdt_cd = str(execution_credentials.get("acnt_prdt_cd") or "01").strip() or "01"
        account_ref = build_account_ref(app_key, cano, acnt_prdt_cd)
        created_at = _now_kst().isoformat(timespec="seconds")
        normalized_end_at = _parse_execute_at(end_at or execute_at).isoformat(timespec="seconds")
        target_qty = int(str(order_payload.get("ord_qty") or "0") or 0)
        record = {
            "id": str(uuid.uuid4()),
            "status": "scheduled",
            "source_app": str(source_app or "unknown").strip() or "unknown",
            "account_ref": account_ref,
            "created_at": created_at,
            "updated_at": created_at,
            "execute_at": _parse_execute_at(execute_at).isoformat(timespec="seconds"),
            "end_at": normalized_end_at,
            "attempt_count": 0,
            "last_error": "",
            "note": str(note or "").strip(),
            "order": {
                "side": str(order_payload.get("side") or "").strip().lower(),
                "pdno": str(order_payload.get("pdno") or "").strip(),
                "ord_qty": str(order_payload.get("ord_qty") or "").strip(),
                "ord_unpr": str(order_payload.get("ord_unpr") or "").strip(),
                "ord_dvsn": str(order_payload.get("ord_dvsn") or "00").strip() or "00",
                "excg_id_dvsn_cd": str(order_payload.get("excg_id_dvsn_cd") or "SOR").strip() or "SOR",
                "sll_type": str(order_payload.get("sll_type") or "").strip(),
                "cndt_pric": str(order_payload.get("cndt_pric") or "").strip(),
            },
            "condition_state": {
                "target_qty": target_qty,
                "filled_qty": 0,
                "remaining_qty": target_qty,
                "submit_count": 0,
                "active": True,
                "last_resubmit_at": "",
            },
            "credentials": self._encrypt_credentials(execution_credentials),
            "execution_result": None,
            "broker_order": None,
        }
        with self._lock:
            orders = self._load_locked()
            order_id = str(record.get("id") or "")
            orders[order_id] = record
            self._save_locked()
        return self._public_order(record)

    def cancel_order(self, order_id: str) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            if str(record.get("status") or "") in {"submitted", "cancelled"}:
                return self._public_order(record)
            record["status"] = "cancelled"
            condition_state = dict(record.get("condition_state") or {})
            condition_state["active"] = False
            record["condition_state"] = condition_state
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            self._save_locked()
            return self._public_order(record)

    def list_reconcilable_orders(self, limit: int = 50) -> list[dict[str, Any]]:
        with self._lock:
            orders = self._load_locked()
            items = [
                dict(item)
                for item in orders.values()
                if isinstance(item, dict) and str(item.get("status") or "") in {"submitted", "open", "cancel_requested"}
            ]
        items.sort(key=lambda item: str(item.get("updated_at") or item.get("created_at") or ""))
        return items[:limit]

    def update_order(
        self,
        order_id: str,
        *,
        execute_at: str,
        end_at: str | None = None,
        order_payload: Mapping[str, Any],
        note: str = "",
    ) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            if str(record.get("status") or "") != "scheduled":
                return self._public_order(record)
            condition_state = dict(record.get("condition_state") or {})
            target_qty = int(str(order_payload.get("ord_qty") or "0") or 0)
            condition_state["target_qty"] = max(int(condition_state.get("target_qty") or 0), target_qty)
            condition_state["remaining_qty"] = target_qty
            record["condition_state"] = condition_state
            record["execute_at"] = _parse_execute_at(execute_at).isoformat(timespec="seconds")
            record["end_at"] = _parse_execute_at(end_at or execute_at).isoformat(timespec="seconds")
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            record["note"] = str(note or "").strip()
            record["order"] = {
                "side": str(order_payload.get("side") or "").strip().lower(),
                "pdno": str(order_payload.get("pdno") or "").strip(),
                "ord_qty": str(order_payload.get("ord_qty") or "").strip(),
                "ord_unpr": str(order_payload.get("ord_unpr") or "").strip(),
                "ord_dvsn": str(order_payload.get("ord_dvsn") or "00").strip() or "00",
                "excg_id_dvsn_cd": str(order_payload.get("excg_id_dvsn_cd") or "SOR").strip() or "SOR",
                "sll_type": str(order_payload.get("sll_type") or "").strip(),
                "cndt_pric": str(order_payload.get("cndt_pric") or "").strip(),
            }
            self._save_locked()
            return self._public_order(record)

    def update_live_order(
        self,
        order_id: str,
        *,
        end_at: str,
        note: str = "",
    ) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            if str(record.get("status") or "") not in LIVE_EDITABLE_STATUSES:
                return self._public_order(record)
            record["end_at"] = _parse_execute_at(end_at).isoformat(timespec="seconds")
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            record["note"] = str(note or "").strip()
            self._save_locked()
            return self._public_order(record)

    def claim_due_orders(self, limit: int = 10) -> list[dict[str, Any]]:
        now = _now_kst()
        claimed: list[dict[str, Any]] = []
        with self._lock:
            orders = self._load_locked()
            stale_cutoff = now - dt.timedelta(seconds=STALE_EXECUTING_SECONDS)
            for item in orders.values():
                if not isinstance(item, dict):
                    continue
                try:
                    end_at = _parse_execute_at(str(item.get("end_at") or now.isoformat()))
                except Exception:
                    end_at = now
                if str(item.get("status") or "") != "executing":
                    if str(item.get("status") or "") == "scheduled" and end_at <= now:
                        item["status"] = "expired"
                        item["updated_at"] = now.isoformat(timespec="seconds")
                        condition_state = dict(item.get("condition_state") or {})
                        condition_state["active"] = False
                        item["condition_state"] = condition_state
                    continue
                try:
                    updated_at = _parse_execute_at(str(item.get("updated_at") or item.get("execute_at") or now.isoformat()))
                except Exception:
                    updated_at = now
                if updated_at <= stale_cutoff:
                    item["status"] = "failed"
                    item["last_error"] = "stale_executing_order_requires_review"
                    item["updated_at"] = now.isoformat(timespec="seconds")
            due = [
                item
                for item in orders.values()
                if isinstance(item, dict)
                and str(item.get("status") or "") == "scheduled"
                and _parse_execute_at(str(item.get("end_at") or now.isoformat())) > now
                and _parse_execute_at(str(item.get("execute_at") or now.isoformat())) <= now
            ]
            due.sort(key=lambda item: str(item.get("execute_at") or ""))
            for item in due[:limit]:
                item["status"] = "executing"
                item["updated_at"] = now.isoformat(timespec="seconds")
                item["attempt_count"] = int(item.get("attempt_count") or 0) + 1
                claimed.append(dict(item))
            if claimed:
                self._save_locked()
        return claimed

    def mark_submitted(self, order_id: str, execution_result: Mapping[str, Any]) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            if str(record.get("status") or "") == "cancelled":
                return self._public_order(record)
            condition_state = dict(record.get("condition_state") or {})
            target_qty = int(condition_state.get("target_qty") or int(record.get("order", {}).get("ord_qty") or 0))
            cumulative_filled = int(condition_state.get("filled_qty") or 0)
            remaining_qty = int(condition_state.get("remaining_qty") or max(target_qty - cumulative_filled, 0))
            condition_state["target_qty"] = target_qty
            condition_state["remaining_qty"] = remaining_qty
            condition_state["submit_count"] = int(condition_state.get("submit_count") or 0) + 1
            condition_state["last_resubmit_at"] = _now_kst().isoformat(timespec="seconds")
            condition_state["active"] = True
            record["condition_state"] = condition_state
            record["status"] = "submitted"
            record["execution_result"] = dict(execution_result)
            record["broker_order"] = {
                **dict(record.get("broker_order") or {}),
                **dict(execution_result.get("broker_order") or {}),
                "status": "submitted",
                "submitted_at": _now_kst().isoformat(timespec="seconds"),
                "last_sync_at": "",
                "submitted_qty": remaining_qty,
                "base_filled_qty": cumulative_filled,
                "filled_qty": 0,
                "remaining_qty": remaining_qty,
                "cancelled_at": "",
                "last_broker_message": "",
            }
            record["last_error"] = ""
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            self._save_locked()
            return self._public_order(record)

    def mark_failed(self, order_id: str, error_message: str) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            if str(record.get("status") or "") == "cancelled":
                return self._public_order(record)
            record["status"] = "failed"
            record["last_error"] = str(error_message or "scheduled_order_failed").strip()
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            self._save_locked()
            return self._public_order(record)

    def mark_expired(
        self,
        order_id: str,
        *,
        broker_snapshot: Mapping[str, Any] | None = None,
        broker_message: str = "",
    ) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            record["status"] = "expired"
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            condition_state = dict(record.get("condition_state") or {})
            condition_state["active"] = False
            record["condition_state"] = condition_state
            if broker_snapshot is not None:
                broker_order = dict(record.get("broker_order") or {})
                broker_order.update(dict(broker_snapshot))
                broker_order["status"] = "expired"
                broker_order["last_sync_at"] = _now_kst().isoformat(timespec="seconds")
                if broker_message:
                    broker_order["last_broker_message"] = str(broker_message).strip()
                record["broker_order"] = broker_order
            self._save_locked()
            return self._public_order(record)

    def reconcile_broker_order(
        self,
        order_id: str,
        *,
        status: str,
        filled_qty: int,
        remaining_qty: int,
        broker_snapshot: Mapping[str, Any],
        broker_message: str = "",
    ) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            broker_order = dict(record.get("broker_order") or {})
            broker_order.update(dict(broker_snapshot))
            broker_order["status"] = status
            broker_order["filled_qty"] = int(filled_qty)
            broker_order["remaining_qty"] = int(remaining_qty)
            broker_order["last_sync_at"] = _now_kst().isoformat(timespec="seconds")
            broker_order["last_broker_message"] = str(broker_message).strip()
            if status == "broker_cancelled":
                broker_order["cancelled_at"] = _now_kst().isoformat(timespec="seconds")
            record["broker_order"] = broker_order
            condition_state = dict(record.get("condition_state") or {})
            condition_state["filled_qty"] = int(filled_qty)
            condition_state["remaining_qty"] = int(remaining_qty)
            condition_state["active"] = status not in {"filled", "broker_cancelled", "cancelled"}
            record["condition_state"] = condition_state
            record["status"] = status
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            record["last_error"] = "" if status in {"open", "filled", "broker_cancelled", "submitted"} else record.get("last_error", "")
            self._save_locked()
            return self._public_order(record)

    def schedule_resubmit(
        self,
        order_id: str,
        *,
        execute_at: str,
        remaining_qty: int,
        filled_qty: int,
        broker_snapshot: Mapping[str, Any],
        broker_message: str,
    ) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            if str(record.get("status") or "") == "cancel_requested":
                return self._public_order(record)
            record["status"] = "scheduled"
            record["execute_at"] = _parse_execute_at(execute_at).isoformat(timespec="seconds")
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            order = dict(record.get("order") or {})
            order["ord_qty"] = str(max(int(remaining_qty), 0))
            order.setdefault("ord_dvsn", "00")
            order.setdefault("excg_id_dvsn_cd", "SOR")
            record["order"] = order
            condition_state = dict(record.get("condition_state") or {})
            condition_state["filled_qty"] = max(int(filled_qty), 0)
            condition_state["remaining_qty"] = max(int(remaining_qty), 0)
            condition_state["active"] = max(int(remaining_qty), 0) > 0
            record["condition_state"] = condition_state
            broker_order = dict(record.get("broker_order") or {})
            broker_order.update(dict(broker_snapshot))
            broker_order["status"] = "awaiting_resubmit"
            broker_order["remaining_qty"] = max(int(remaining_qty), 0)
            broker_order["last_sync_at"] = _now_kst().isoformat(timespec="seconds")
            broker_order["last_broker_message"] = str(broker_message or "").strip()
            record["broker_order"] = broker_order
            record["last_error"] = ""
            self._save_locked()
            return self._public_order(record)

    def mark_broker_cancel_requested(
        self,
        order_id: str,
        *,
        broker_snapshot: Mapping[str, Any],
    ) -> dict[str, Any] | None:
        with self._lock:
            orders = self._load_locked()
            record = orders.get(order_id)
            if not isinstance(record, dict):
                return None
            broker_order = dict(record.get("broker_order") or {})
            broker_order.update(dict(broker_snapshot))
            broker_order["status"] = "cancel_requested"
            broker_order["last_sync_at"] = _now_kst().isoformat(timespec="seconds")
            broker_order["last_broker_message"] = ""
            record["broker_order"] = broker_order
            record["status"] = "cancel_requested"
            condition_state = dict(record.get("condition_state") or {})
            condition_state["active"] = True
            record["condition_state"] = condition_state
            record["updated_at"] = _now_kst().isoformat(timespec="seconds")
            self._save_locked()
            return self._public_order(record)

    def decrypt_execution_credentials(self, record: Mapping[str, Any]) -> dict[str, str]:
        encrypted = str(record.get("credentials") or "").encode("utf-8")
        decrypted = self._fernet.decrypt(encrypted)
        payload = json.loads(decrypted.decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("invalid_scheduled_order_credentials")
        return {
            "app_key": str(payload.get("app_key") or "").strip(),
            "app_secret": str(payload.get("app_secret") or "").strip(),
            "cano": str(payload.get("cano") or "").strip(),
            "acnt_prdt_cd": str(payload.get("acnt_prdt_cd") or "01").strip() or "01",
        }

    def _encrypt_credentials(self, execution_credentials: Mapping[str, Any]) -> str:
        payload = {
            "app_key": str(execution_credentials.get("app_key") or "").strip(),
            "app_secret": str(execution_credentials.get("app_secret") or "").strip(),
            "cano": str(execution_credentials.get("cano") or "").strip(),
            "acnt_prdt_cd": str(execution_credentials.get("acnt_prdt_cd") or "01").strip() or "01",
        }
        return self._fernet.encrypt(json.dumps(payload).encode("utf-8")).decode("utf-8")

    def _public_order(self, record: Mapping[str, Any]) -> dict[str, Any]:
        return {
            "id": str(record.get("id") or ""),
            "status": str(record.get("status") or ""),
            "source_app": str(record.get("source_app") or ""),
            "account_ref": str(record.get("account_ref") or ""),
            "created_at": str(record.get("created_at") or ""),
            "updated_at": str(record.get("updated_at") or ""),
            "execute_at": str(record.get("execute_at") or ""),
            "end_at": str(record.get("end_at") or ""),
            "attempt_count": int(record.get("attempt_count") or 0),
            "last_error": str(record.get("last_error") or ""),
            "note": str(record.get("note") or ""),
            "order": dict(record.get("order") or {}),
            "condition_state": dict(record.get("condition_state") or {}) if isinstance(record.get("condition_state"), Mapping) else record.get("condition_state"),
            "execution_result": record.get("execution_result"),
            "broker_order": dict(record.get("broker_order") or {}) if isinstance(record.get("broker_order"), Mapping) else record.get("broker_order"),
        }

    def _load_locked(self) -> dict[str, dict[str, Any]]:
        if self._cache is not None:
            return self._cache
        path = runtime_paths.get_scheduled_orders_path()
        if not os.path.exists(path):
            self._cache = {}
            return self._cache
        try:
            with open(path, "r", encoding="utf-8") as file:
                payload = json.load(file)
        except Exception:
            raise RuntimeError("scheduled_order_store_load_failed")
        orders = payload.get("orders") if isinstance(payload, dict) else {}
        self._cache = orders if isinstance(orders, dict) else {}
        return self._cache

    def _save_locked(self) -> None:
        path = runtime_paths.get_scheduled_orders_path()
        directory = os.path.dirname(path)
        os.makedirs(directory, exist_ok=True)
        fd, tmp_path = tempfile.mkstemp(prefix="scheduled_orders_", suffix=".tmp", dir=directory)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as file:
                json.dump({"orders": self._cache or {}}, file, indent=2, ensure_ascii=False)
                file.flush()
                os.fsync(file.fileno())
            os.replace(tmp_path, path)
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
