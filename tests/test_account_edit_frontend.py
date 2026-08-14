from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class AccountEditFrontendTests(unittest.TestCase):
    """Frontend hooks for the account edit flow (PATCH /api/accounts/{id})."""

    @classmethod
    def setUpClass(cls):
        cls.index_html = (ROOT / "app/templates/index.html").read_text(
            encoding="utf-8"
        )
        cls.dashboard_js = (ROOT / "app/static/js/dashboard.js").read_text(
            encoding="utf-8"
        )

    def test_edit_modal_hooks_present_in_template(self):
        self.assertIn('id="accountEditModal"', self.index_html)
        self.assertIn('id="accountEditForm"', self.index_html)
        self.assertIn('onsubmit="submitEditAccount(event)"', self.index_html)
        self.assertIn('id="edit_account_label"', self.index_html)
        self.assertIn('id="edit_account_cano"', self.index_html)
        self.assertIn('id="edit_account_app_key"', self.index_html)
        self.assertIn('id="edit_account_app_secret"', self.index_html)
        self.assertIn('id="edit_account_pin"', self.index_html)
        self.assertIn('id="accountEditSaveBtn"', self.index_html)
        self.assertIn('onclick="closeAccountEditModal()"', self.index_html)
        self.assertIn('onclick="handleAccountEditOverlayClick(event)"', self.index_html)

    def test_keep_existing_keys_notice_is_shown(self):
        self.assertIn("빈 값이면 기존 키 유지", self.index_html)

    def test_edit_js_flow_functions_exist(self):
        self.assertIn("let accountEditTarget = null;", self.dashboard_js)
        self.assertIn("function requestEditAccount(btn)", self.dashboard_js)
        self.assertIn("function submitEditAccount(event)", self.dashboard_js)
        self.assertIn("function closeAccountEditModal()", self.dashboard_js)
        self.assertIn("function handleAccountEditOverlayClick(event)", self.dashboard_js)

    def test_edit_card_has_edit_button_and_delete_is_preserved(self):
        self.assertIn('class="btn-edit-account"', self.dashboard_js)
        self.assertIn('onclick="requestEditAccount(this)"', self.dashboard_js)
        self.assertIn('class="btn-delete-account"', self.dashboard_js)
        self.assertIn('onclick="requestDeleteAccount(this)"', self.dashboard_js)

    def test_edit_submits_patch_with_optional_keys(self):
        self.assertIn("method: 'PATCH'", self.dashboard_js)
        self.assertIn(
            "`/api/accounts/${encodeURIComponent(accountEditTarget.accountId)}`",
            self.dashboard_js,
        )
        self.assertIn("if (appKey) body.app_key = appKey;", self.dashboard_js)
        self.assertIn("if (appSecret) body.app_secret = appSecret;", self.dashboard_js)
        self.assertIn("if (parsed) {", self.dashboard_js)
        self.assertIn("body.cano = parsed.cano", self.dashboard_js)
        self.assertIn("body.acnt_prdt_cd = parsed.acnt_prdt_cd", self.dashboard_js)
        self.assertIn("pin", self.dashboard_js)

    def test_account_number_can_be_left_blank_to_keep_existing_value(self):
        self.assertIn(
            "const parsed = accountNumber ? parseAccountNumberInput(accountNumber) : null;",
            self.dashboard_js,
        )
        self.assertIn("if (accountNumber && !parsed)", self.dashboard_js)
        self.assertNotIn(
            'id="edit_account_cano" name="cano" required', self.index_html
        )

    def test_edit_secrets_are_never_prefilled(self):
        self.assertIn(
            "document.getElementById('edit_account_app_key').value = ''",
            self.dashboard_js,
        )
        self.assertIn(
            "document.getElementById('edit_account_app_secret').value = ''",
            self.dashboard_js,
        )
        self.assertNotIn("edit_account_app_key\".value = ", self.index_html)
        self.assertNotIn("value=\"${", self.index_html)

    def test_edit_prefills_label_and_cano(self):
        self.assertIn("document.getElementById('edit_account_label').value = label;", self.dashboard_js)
        self.assertIn("canoInput.value = fullCano + product;", self.dashboard_js)

    def test_add_and_delete_flows_are_preserved(self):
        self.assertIn('id="accountAddForm"', self.index_html)
        self.assertIn("function submitAddAccount(event)", self.dashboard_js)
        self.assertIn('id="accountDeleteModal"', self.index_html)
        self.assertIn("function confirmDeleteAccount()", self.dashboard_js)
        self.assertIn('onclick="requestDeleteAccount(this)"', self.dashboard_js)


if __name__ == "__main__":
    unittest.main()
