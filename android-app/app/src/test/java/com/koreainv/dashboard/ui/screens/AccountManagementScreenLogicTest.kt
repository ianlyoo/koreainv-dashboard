package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.AccountCredential
import com.koreainv.dashboard.network.AccountProfile
import com.koreainv.dashboard.network.Broker
import com.koreainv.dashboard.network.defaultAccountLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountManagementScreenLogicTest {
    private val existing = listOf(
        AccountCredential(
            id = "acct_primary",
            label = "나의 계좌",
            appKey = "stored-key",
            appSecret = "stored-secret",
            cano = "11112222",
            acntPrdtCd = "01",
            centralServerBaseUrl = "https://central.example",
            centralServerApiToken = "tok",
        ),
        AccountCredential(
            id = "acct_second",
            label = "두 번째 계좌",
            appKey = "key-2",
            appSecret = "secret-2",
            cano = "33334444",
            acntPrdtCd = "02",
        ),
    )

    @Test
    fun accountDraftsFromNeverExposesStoredSecrets() {
        val drafts = accountDraftsFrom(AccountProfile(accounts = existing))

        assertEquals(2, drafts.size)
        drafts.forEach { draft ->
            assertTrue(draft.appKeyInput.isBlank())
            assertTrue(draft.appSecretInput.isBlank())
            assertTrue(draft.hasStoredKey)
            assertTrue(draft.hasStoredSecret)
        }
        assertEquals("acct_primary", drafts[0].id)
        assertEquals("나의 계좌", drafts[0].label)
        assertEquals("11112222", drafts[0].cano)
        assertEquals("01", drafts[0].acntPrdtCd)
    }

    @Test
    fun resolveAccountDraftsKeepsStoredSecretsWhenInputsBlank() {
        val drafts = accountDraftsFrom(AccountProfile(accounts = existing))

        val resolved = resolveAccountDrafts(existing, drafts)

        assertEquals("stored-key", resolved[0].appKey)
        assertEquals("stored-secret", resolved[0].appSecret)
        assertEquals("key-2", resolved[1].appKey)
        assertEquals("secret-2", resolved[1].appSecret)
        assertEquals("https://central.example", resolved[0].centralServerBaseUrl)
        assertEquals("tok", resolved[0].centralServerApiToken)
    }

    @Test
    fun resolveAccountDraftsReplacesOnlyEditedSecrets() {
        val drafts = accountDraftsFrom(AccountProfile(accounts = existing)).mapIndexed { index, draft ->
            if (index == 0) draft.copy(appKeyInput = "new-key") else draft
        }

        val resolved = resolveAccountDrafts(existing, drafts)

        assertEquals("new-key", resolved[0].appKey)
        assertEquals("stored-secret", resolved[0].appSecret)
        assertEquals("key-2", resolved[1].appKey)
        assertEquals("secret-2", resolved[1].appSecret)
    }

    @Test
    fun resolveAccountDraftsKeepsIdAndCarriesCentralFieldsWhenAccountEdited() {
        val drafts = accountDraftsFrom(AccountProfile(accounts = existing)).mapIndexed { index, draft ->
            if (index == 0) draft.copy(cano = "99998888", acntPrdtCd = "03") else draft
        }

        val resolved = resolveAccountDrafts(existing, drafts)

        assertEquals("acct_primary", resolved[0].id)
        assertEquals("99998888", resolved[0].cano)
        assertEquals("03", resolved[0].acntPrdtCd)
        assertEquals("https://central.example", resolved[0].centralServerBaseUrl)
    }

    @Test
    fun resolveAccountDraftsLeavesNewAccountIdBlankAndDefaultsLabel() {
        val drafts = listOf(
            ManagedAccountDraft(
                label = "",
                cano = "55556666",
                acntPrdtCd = "01",
                appKeyInput = "new-key",
                appSecretInput = "new-secret",
            ),
        )

        val resolved = resolveAccountDrafts(existing, drafts)

        assertEquals("", resolved[0].id)
        assertEquals(defaultAccountLabel("55556666"), resolved[0].label)
        assertEquals("new-key", resolved[0].appKey)
        assertEquals("new-secret", resolved[0].appSecret)
        assertEquals("", resolved[0].centralServerBaseUrl)
    }

    @Test
    fun isAccountDraftCompleteAcceptsExistingSecretsAndRejectsNewEmptyOnes() {
        val existingDraft = accountDraftsFrom(AccountProfile(accounts = existing))[0]
        assertTrue(isAccountDraftComplete(existingDraft))

        val newDraftWithoutKey = ManagedAccountDraft(
            cano = "55556666",
            acntPrdtCd = "01",
            appSecretInput = "new-secret",
        )
        assertFalse(isAccountDraftComplete(newDraftWithoutKey))

        val newDraftWithoutSecret = ManagedAccountDraft(
            cano = "55556666",
            acntPrdtCd = "01",
            appKeyInput = "new-key",
        )
        assertFalse(isAccountDraftComplete(newDraftWithoutSecret))
    }

    @Test
    fun isAccountDraftCompleteValidatesAccountNumberAndProductCode() {
        val complete = ManagedAccountDraft(
            cano = "55556666",
            acntPrdtCd = "01",
            appKeyInput = "new-key",
            appSecretInput = "new-secret",
        )
        assertTrue(isAccountDraftComplete(complete))

        assertFalse(isAccountDraftComplete(complete.copy(cano = "5555666")))
        assertFalse(isAccountDraftComplete(complete.copy(cano = "555566666")))
        assertFalse(isAccountDraftComplete(complete.copy(cano = "5555abcd")))
        assertFalse(isAccountDraftComplete(complete.copy(acntPrdtCd = "1")))
        assertFalse(isAccountDraftComplete(complete.copy(acntPrdtCd = "011")))
    }

    @Test
    fun tossDraftUsesAccountSequenceAndDoesNotRequireProductCode() {
        val toss = ManagedAccountDraft(
            broker = Broker.TOSS,
            cano = "1",
            acntPrdtCd = "",
            appKeyInput = "client",
            appSecretInput = "secret",
        )

        assertTrue(isAccountDraftComplete(toss))
        assertFalse(isAccountDraftComplete(toss.copy(cano = "0")))
        assertFalse(isAccountDraftComplete(toss.copy(appSecretInput = "")))
    }

    @Test
    fun changingBrokerNeverReusesStoredCredentials() {
        val draft = accountDraftsFrom(AccountProfile(accounts = existing))[0].copy(
            broker = Broker.TOSS,
            cano = "1",
            acntPrdtCd = "",
            hasStoredKey = false,
            hasStoredSecret = false,
        )

        val resolved = resolveAccountDrafts(existing, listOf(draft)).single()

        assertEquals(Broker.TOSS, resolved.broker)
        assertEquals("", resolved.appKey)
        assertEquals("", resolved.appSecret)
    }

    @Test
    fun tossProxyConfigIsKeptEncryptedAndRequiresCompleteReplacementPair() {
        val tossAccount = AccountCredential(
            id = "acct_toss",
            label = "토스",
            appKey = "client",
            appSecret = "secret",
            cano = "1",
            acntPrdtCd = "",
            broker = Broker.TOSS,
            centralServerBaseUrl = "https://proxy.example",
            centralServerApiToken = "proxy-token",
        )
        val drafts = accountDraftsFrom(AccountProfile(listOf(tossAccount)))
        val draft = drafts.single()

        assertTrue(draft.useTossProxy)
        assertTrue(draft.proxyBaseUrlInput.isBlank())
        assertTrue(draft.proxyApiTokenInput.isBlank())
        assertTrue(draft.hasStoredProxyConfig)
        assertTrue(isAccountDraftComplete(draft))
        assertFalse(isAccountDraftComplete(draft.copy(proxyBaseUrlInput = "https://new.example")))

        val resolved = resolveAccountDrafts(listOf(tossAccount), drafts).single()
        assertEquals("https://proxy.example", resolved.centralServerBaseUrl)
        assertEquals("proxy-token", resolved.centralServerApiToken)

        val direct = resolveAccountDrafts(
            listOf(tossAccount),
            listOf(draft.copy(useTossProxy = false)),
        ).single()
        val normalized = com.koreainv.dashboard.network.normalizeUpdatedAccounts(
            listOf(tossAccount),
            listOf(direct),
        ).single()
        assertEquals("", normalized.centralServerBaseUrl)
        assertEquals("", normalized.centralServerApiToken)
    }

    @Test
    fun accountManagementValidationErrorReportsPinThenAccounts() {
        val drafts = accountDraftsFrom(AccountProfile(accounts = existing))

        assertEquals("pin-error", accountManagementValidationError(drafts, "12", "accounts-error", "pin-error"))
        assertEquals(
            "accounts-error",
            accountManagementValidationError(drafts + ManagedAccountDraft(), "1234", "accounts-error", "pin-error"),
        )
        assertNull(accountManagementValidationError(drafts, "1234", "accounts-error", "pin-error"))
    }

    @Test
    fun tossIpAllowlistErrorRecommendsPrivateServer() {
        val message = tossAccountLookupErrorMessage(IllegalStateException("IP address not allowed"))
        assertTrue(message.contains("개인 서버"))
    }
}
