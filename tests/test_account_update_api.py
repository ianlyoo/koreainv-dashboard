from __future__ import annotations

import json
import unittest
from typing import cast
from unittest.mock import patch

from fastapi.testclient import TestClient

from app import auth
from app.main import app
from app.routes.auth_pages import decrypt_accounts_for_session
from app.session_store import AccountCredential, SessionData, active_sessions


def _fixture_accounts() -> list[AccountCredential]:
    return [
        AccountCredential.make("key-a", "secret-a", "11111111", "01", "main"),
        AccountCredential.make("key-b", "secret-b", "22222222", "01", "second"),
    ]


def _build_encrypted_settings(
    accounts: list[AccountCredential], pin: str = "123456"
) -> dict[str, object]:
    salt = auth.generate_kdf_salt()
    records = [
        {
            "account_id": account.account_id,
            "label": account.label,
            "app_key": account.app_key,
            "app_secret": account.app_secret,
            "cano": account.cano,
            "acnt_prdt_cd": account.acnt_prdt_cd,
        }
        for account in accounts
    ]
    primary = accounts[0]
    return {
        "setup_complete": True,
        "crypto_version": 2,
        "kdf_salt": salt,
        "pin_hash": "hashed",
        "accounts_enc": auth.encrypt_data_v2(json.dumps(records), pin, salt),
        "api_key_enc": auth.encrypt_data_v2(primary.app_key, pin, salt),
        "api_secret_enc": auth.encrypt_data_v2(primary.app_secret, pin, salt),
        "cano_enc": auth.encrypt_data_v2(primary.cano, pin, salt),
        "acnt_prdt_cd_enc": auth.encrypt_data_v2(primary.acnt_prdt_cd, pin, salt),
    }


class AccountUpdateApiTests(unittest.TestCase):
    client: TestClient = cast(TestClient, cast(object, None))

    def setUp(self):
        active_sessions.clear()
        self.client = TestClient(app)
        self.client.__enter__()
        self.accounts = _fixture_accounts()
        self.target_id = self.accounts[1].account_id
        active_sessions["test-session"] = SessionData(
            "key-a", "secret-a", "11111111", "01", accounts=list(self.accounts)
        )
        self.client.cookies.set("session", "test-session")

        self.load_settings_patcher = patch(
            "app.routes.auth_pages.auth.load_settings",
            return_value={
                "setup_complete": True,
                "crypto_version": 2,
                "kdf_salt": "test-salt",
                "pin_hash": "hashed",
                "accounts_enc": "ignored",
            },
        )
        self.decrypt_patcher = patch(
            "app.routes.auth_pages.decrypt_accounts_for_session",
            side_effect=lambda _settings, _pin: list(
                active_sessions["test-session"].accounts
            ),
        )
        self.verify_patcher = patch(
            "app.routes.auth_pages.auth.verify_pin", return_value=True
        )
        self.encrypt_patcher = patch(
            "app.routes.auth_pages.auth.encrypt_data_v2",
            side_effect=lambda data, _pin, _salt: f"enc:{data}",
        )
        self.save_patcher = patch(
            "app.routes.auth_pages.auth.save_settings", return_value=True
        )
        self.settings_mock = self.load_settings_patcher.start()
        self.decrypt_mock = self.decrypt_patcher.start()
        self.verify_mock = self.verify_patcher.start()
        self.encrypt_mock = self.encrypt_patcher.start()
        self.save_mock = self.save_patcher.start()

    def tearDown(self):
        self.save_patcher.stop()
        self.encrypt_patcher.stop()
        self.verify_patcher.stop()
        self.decrypt_patcher.stop()
        self.load_settings_patcher.stop()
        self.client.__exit__(None, None, None)
        active_sessions.clear()

    def _patch_payload(self, **overrides) -> dict[str, object]:
        payload: dict[str, object] = {"pin": "123456"}
        payload.update(overrides)
        return payload

    def test_patch_updates_fields_replaces_secrets_and_keeps_account_id(self):
        response = self.client.patch(
            f"/api/accounts/{self.target_id}",
            json=self._patch_payload(
                label="renamed",
                cano="33333333",
                acnt_prdt_cd="02",
                app_key="key-c",
                app_secret="secret-c",
            ),
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "success")
        self.assertTrue(payload["updated"])
        account = payload["account"]
        self.assertEqual(account["account_id"], self.target_id)
        self.assertEqual(account["label"], "renamed")
        self.assertEqual(account["cano_masked"], "****3333")
        self.assertEqual(account["acnt_prdt_cd"], "02")
        self.assertEqual(account["masked_app_key"], "ke****")
        self.assertNotIn("secret-c", response.text)
        self.assertNotIn("33333333", response.text)
        self.assertNotIn("key-c", response.text)

        session = active_sessions["test-session"]
        updated = next(a for a in session.accounts if a.account_id == self.target_id)
        self.assertEqual(updated.label, "renamed")
        self.assertEqual(updated.cano, "33333333")
        self.assertEqual(updated.acnt_prdt_cd, "02")
        self.assertEqual(updated.app_key, "key-c")
        self.assertEqual(updated.app_secret, "secret-c")
        self.assertEqual(updated.account_id, self.target_id)
        self.assertEqual(session.accounts[0].cano, "11111111")
        self.assertEqual(session.accounts[0].label, "main")

        stored = self.settings_mock.return_value
        self.assertIn("33333333", stored["accounts_enc"])
        self.assertIn("secret-c", stored["accounts_enc"])
        self.assertEqual(stored["crypto_version"], 2)
        self.assertEqual(stored["kdf_salt"], "test-salt")
        self.save_mock.assert_called_once_with(stored)

    def test_patch_empty_or_blank_secrets_keep_existing_values(self):
        response = self.client.patch(
            f"/api/accounts/{self.target_id}",
            json=self._patch_payload(label="renamed", app_key="", app_secret=""),
        )
        self.assertEqual(response.status_code, 200)
        session = active_sessions["test-session"]
        updated = next(a for a in session.accounts if a.account_id == self.target_id)
        self.assertEqual(updated.app_key, "key-b")
        self.assertEqual(updated.app_secret, "secret-b")
        self.assertEqual(updated.label, "renamed")

        response = self.client.patch(
            f"/api/accounts/{self.target_id}",
            json=self._patch_payload(label="again", app_key="   ", app_secret="   "),
        )
        self.assertEqual(response.status_code, 200)
        updated = next(a for a in session.accounts if a.account_id == self.target_id)
        self.assertEqual(updated.app_key, "key-b")
        self.assertEqual(updated.app_secret, "secret-b")
        self.assertEqual(updated.label, "again")

        noop = self.client.patch(
            f"/api/accounts/{self.target_id}",
            json=self._patch_payload(app_key="   ", app_secret="   "),
        )
        self.assertEqual(noop.status_code, 400)

    def test_patch_requires_session(self):
        self.client.cookies.set("session", "missing-session")
        response = self.client.patch(
            f"/api/accounts/{self.target_id}", json=self._patch_payload(label="x")
        )
        self.assertEqual(response.status_code, 401)
        self.save_mock.assert_not_called()

    def test_patch_invalid_pin_returns_401(self):
        self.verify_mock.return_value = False
        response = self.client.patch(
            f"/api/accounts/{self.target_id}", json=self._patch_payload(label="x")
        )
        self.assertEqual(response.status_code, 401)
        self.save_mock.assert_not_called()
        session = active_sessions["test-session"]
        self.assertEqual(
            next(a for a in session.accounts if a.account_id == self.target_id).label,
            "second",
        )

    def test_patch_unknown_account_returns_404(self):
        response = self.client.patch(
            "/api/accounts/acct_missing", json=self._patch_payload(label="x")
        )
        self.assertEqual(response.status_code, 404)
        self.save_mock.assert_not_called()

    def test_patch_duplicate_cano_product_returns_409(self):
        response = self.client.patch(
            f"/api/accounts/{self.target_id}",
            json=self._patch_payload(cano="11111111", acnt_prdt_cd="01"),
        )
        self.assertEqual(response.status_code, 409)
        self.save_mock.assert_not_called()
        session = active_sessions["test-session"]
        self.assertEqual(
            next(a for a in session.accounts if a.account_id == self.target_id).cano,
            "22222222",
        )

    def test_patch_with_no_editable_fields_returns_400(self):
        response = self.client.patch(
            f"/api/accounts/{self.target_id}", json=self._patch_payload()
        )
        self.assertEqual(response.status_code, 400)
        self.save_mock.assert_not_called()

    def test_patch_persist_failure_returns_500_and_keeps_session(self):
        self.save_mock.return_value = False
        response = self.client.patch(
            f"/api/accounts/{self.target_id}", json=self._patch_payload(label="x")
        )
        self.assertEqual(response.status_code, 500)
        session = active_sessions["test-session"]
        self.assertEqual(
            next(a for a in session.accounts if a.account_id == self.target_id).label,
            "second",
        )

    def test_post_add_and_delete_remain_compatible(self):
        add_response = self.client.post(
            "/api/accounts",
            json={
                "pin": "123456",
                "label": "third",
                "app_key": "key-c",
                "app_secret": "secret-c",
                "cano": "33333333",
                "acnt_prdt_cd": "01",
            },
        )
        self.assertEqual(add_response.status_code, 200)
        add_payload = add_response.json()
        self.assertEqual(len(add_payload["accounts"]), 3)
        new_id = add_payload["account"]["account_id"]

        delete_response = self.client.post(
            f"/api/accounts/{new_id}/delete", json={"pin": "123456"}
        )
        self.assertEqual(delete_response.status_code, 200)
        self.assertEqual(len(delete_response.json()["accounts"]), 2)


class AccountUpdateEncryptionRoundTripTests(unittest.TestCase):
    """Exercise the PATCH write path with real encryption, mocking only the
    settings file boundary and bcrypt PIN check."""

    client: TestClient = cast(TestClient, cast(object, None))
    PIN = "123456"

    def setUp(self):
        active_sessions.clear()
        self.client = TestClient(app)
        self.client.__enter__()
        self.accounts = _fixture_accounts()
        self.target_id = self.accounts[1].account_id
        self.settings = _build_encrypted_settings(self.accounts, self.PIN)
        active_sessions["test-session"] = SessionData(
            "key-a", "secret-a", "11111111", "01", accounts=list(self.accounts)
        )
        self.client.cookies.set("session", "test-session")

    def tearDown(self):
        self.client.__exit__(None, None, None)
        active_sessions.clear()

    def test_patch_persists_through_real_encryption(self):
        with (
            patch(
                "app.routes.auth_pages.auth.load_settings",
                return_value=self.settings,
            ),
            patch("app.routes.auth_pages.auth.verify_pin", return_value=True),
            patch(
                "app.routes.auth_pages.auth.save_settings", return_value=True
            ) as save_mock,
        ):
            response = self.client.patch(
                f"/api/accounts/{self.target_id}",
                json={
                    "pin": self.PIN,
                    "label": "renamed",
                    "cano": "33333333",
                    "acnt_prdt_cd": "02",
                    "app_key": "key-c",
                    "app_secret": "secret-c",
                },
            )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "success")
        self.assertEqual(payload["account"]["account_id"], self.target_id)
        self.assertEqual(payload["account"]["label"], "renamed")
        self.assertNotIn("secret-c", response.text)
        self.assertNotIn("key-c", response.text)
        self.assertNotIn("33333333", response.text)

        save_mock.assert_called_once_with(self.settings)
        decrypted = decrypt_accounts_for_session(self.settings, self.PIN)
        self.assertEqual(len(decrypted), 2)
        updated = next(
            account
            for account in decrypted
            if account.account_id == self.target_id
        )
        self.assertEqual(updated.label, "renamed")
        self.assertEqual(updated.cano, "33333333")
        self.assertEqual(updated.acnt_prdt_cd, "02")
        self.assertEqual(updated.app_key, "key-c")
        self.assertEqual(updated.app_secret, "secret-c")
        self.assertEqual(updated.account_id, self.target_id)

        other = next(
            account
            for account in decrypted
            if account.account_id != self.target_id
        )
        self.assertEqual(other.cano, "11111111")
        self.assertEqual(other.app_key, "key-a")
        self.assertEqual(other.app_secret, "secret-a")

        salt = str(self.settings["kdf_salt"])
        self.assertEqual(
            auth.decrypt_data_v2(
                str(self.settings["cano_enc"]), self.PIN, salt
            ),
            "11111111",
        )

    def test_patch_blank_secrets_keep_existing_values_after_round_trip(self):
        with (
            patch(
                "app.routes.auth_pages.auth.load_settings",
                return_value=self.settings,
            ),
            patch("app.routes.auth_pages.auth.verify_pin", return_value=True),
            patch(
                "app.routes.auth_pages.auth.save_settings", return_value=True
            ),
        ):
            response = self.client.patch(
                f"/api/accounts/{self.target_id}",
                json={
                    "pin": self.PIN,
                    "label": "renamed",
                    "app_key": "",
                    "app_secret": "   ",
                },
            )

        self.assertEqual(response.status_code, 200)
        decrypted = decrypt_accounts_for_session(self.settings, self.PIN)
        updated = next(
            account
            for account in decrypted
            if account.account_id == self.target_id
        )
        self.assertEqual(updated.label, "renamed")
        self.assertEqual(updated.app_key, "key-b")
        self.assertEqual(updated.app_secret, "secret-b")
        self.assertEqual(updated.cano, "22222222")
        self.assertEqual(updated.account_id, self.target_id)


if __name__ == "__main__":
    unittest.main()
