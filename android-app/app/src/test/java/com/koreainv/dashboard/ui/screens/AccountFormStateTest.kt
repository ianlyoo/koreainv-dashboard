package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.AccountCredential
import com.koreainv.dashboard.network.AccountProfile
import com.koreainv.dashboard.network.Broker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountFormStateTest {
    private fun kis() = ManagedAccountDraft(
        cano = "12345678", acntPrdtCd = "01", appKeyInput = "test-key", appSecretInput = "test-secret",
    )

    private fun toss() = ManagedAccountDraft(
        broker = Broker.TOSS, cano = "7", acntPrdtCd = "", appKeyInput = "test-client", appSecretInput = "test-secret",
    )

    private fun storedToss() = AccountCredential(
        id = "toss", broker = Broker.TOSS, label = "test", cano = "7", acntPrdtCd = "",
        appKey = "test-client", appSecret = "test-secret",
        centralServerBaseUrl = "https://proxy.example", centralServerApiToken = "test-token",
    )

    @Test fun repeatedOptionsPreserveEnteredAndStoredCredentials() {
        val stored = storedToss()
        val draft = accountDraftsFrom(AccountProfile(listOf(stored))).single()
        assertSame(draft, draft.changeBroker(Broker.TOSS))
        assertSame(draft, draft.changeConnection(true))
        assertEquals(stored, resolveAccountDrafts(listOf(stored), listOf(draft)).single())
        val entered = kis()
        assertSame(entered, entered.changeBroker(Broker.KIS))
    }

    @Test fun switchingBrokerClearsEnteredCredentialsAndCannotStartDiscovery() {
        val draft = kis()
        val changed = draft.changeBroker(Broker.TOSS)
        assertEquals(draft.uiId, changed.uiId)
        assertTrue(changed.appKeyInput.isEmpty())
        assertTrue(changed.appSecretInput.isEmpty())
        assertTrue(changed.cano.isEmpty())
        assertEquals("", changed.acntPrdtCd)
        assertNull(managedTossLookupInput(changed, null))
        assertFalse(isAccountDraftComplete(changed))
    }

    @Test fun switchingAwayAndBackNeverResurrectsStoredCredentials() {
        val stored = storedToss()
        val changed = accountDraftsFrom(AccountProfile(listOf(stored))).single()
            .changeBroker(Broker.KIS).changeBroker(Broker.TOSS)
        assertNull(managedTossLookupInput(changed, stored))
        val resolved = resolveAccountDrafts(listOf(stored), listOf(changed)).single()
        assertTrue(resolved.appKey.isEmpty())
        assertTrue(resolved.appSecret.isEmpty())
        assertTrue(resolved.centralServerApiToken.isEmpty())
    }

    @Test fun switchingFromTossToKisNeverCarriesProxyCredentials() {
        val stored = storedToss()
        val changed = accountDraftsFrom(AccountProfile(listOf(stored))).single()
            .changeBroker(Broker.KIS).copy(cano = "12345678", appKeyInput = "kis-key", appSecretInput = "kis-secret")
        assertTrue(isAccountDraftComplete(changed))
        val resolved = resolveAccountDrafts(listOf(stored), listOf(changed)).single()
        assertEquals(Broker.KIS, resolved.broker)
        assertEquals("01", resolved.acntPrdtCd)
        assertTrue(resolved.centralServerBaseUrl.isEmpty())
        assertTrue(resolved.centralServerApiToken.isEmpty())
        assertTrue(resolved.id.isEmpty())
        val normalized = com.koreainv.dashboard.network.normalizeUpdatedAccounts(listOf(stored), listOf(resolved)).single()
        assertTrue(normalized.centralServerBaseUrl.isEmpty())
        assertTrue(normalized.centralServerApiToken.isEmpty())
    }

    @Test fun proxyModeRequiresValidUrlAndTokenEvenWhenBothAreBlank() {
        fun input(url: String, token: String, enabled: Boolean = true) =
            tossLookupInput("client", "secret", true, url, token, enabled)
        assertNull(input("", ""))
        assertNull(input("https://proxy.example", ""))
        assertNull(input("", "token"))
        assertNull(input("https://", "token"))
        assertNotNull(input("http://proxy.example", "token"))
        assertNull(input("https://user:password@proxy.example", "token"))
        assertNull(input("https://proxy.example", "token", false))
        val complete = input(" HTTPS://proxy.example/path ", " token ")!!
        assertEquals("HTTPS://proxy.example/path", complete.proxyBaseUrl)
        assertEquals("token", complete.proxyApiToken)
        assertTrue(complete.useProxy)
    }

    @Test fun existingAndNewLocalHttpProxyConfigurationsRemainSupported() {
        listOf("http://localhost:8080", "http://192.168.0.2:8080", "http://[::1]:8080/proxy").forEach { url ->
            val stored = storedToss().copy(centralServerBaseUrl = url)
            val draft = accountDraftsFrom(AccountProfile(listOf(stored))).single()
            assertTrue(isAccountDraftComplete(draft))
            assertEquals(url, managedTossLookupInput(draft, stored)!!.proxyBaseUrl)
            assertEquals(url, resolveAccountDrafts(listOf(stored), listOf(draft)).single().centralServerBaseUrl)
            val newDraft = toss().copy(useTossProxy = true, proxyBaseUrlInput = url, proxyApiTokenInput = "token")
            assertTrue(isAccountDraftComplete(newDraft))
            assertEquals(url, managedTossLookupInput(newDraft, null)!!.proxyBaseUrl)
        }
        listOf("ftp://localhost", "http://", "http://user:secret@localhost", "http://localhost:99999", "http://localhost?token=secret").forEach {
            assertFalse(isValidProxyUrl(it))
        }
    }

    @Test fun directModeDoesNotForwardRememberedProxyConfiguration() {
        val stored = storedToss()
        val original = accountDraftsFrom(AccountProfile(listOf(stored))).single()
        val direct = original.changeConnection(false)
        val input = managedTossLookupInput(direct, stored)!!
        assertFalse(input.useProxy)
        assertTrue(input.proxyBaseUrl.isEmpty())
        assertTrue(input.proxyApiToken.isEmpty())
        val saved = resolveAccountDrafts(listOf(stored), listOf(direct)).single()
        assertTrue(saved.centralServerBaseUrl.isEmpty())
        assertTrue(saved.centralServerApiToken.isEmpty())
        assertEquals("https://proxy.example", managedTossLookupInput(direct.changeConnection(true), stored)!!.proxyBaseUrl)
    }

    @Test fun partialReplacementCannotMixNewAndStoredTossCredentials() {
        val stored = storedToss()
        val draft = accountDraftsFrom(AccountProfile(listOf(stored))).single()
        val partial = draft.changeLookupField(AccountFormField.KEY, "replacement", stored)
        assertNull(managedTossLookupInput(partial, stored))
        assertTrue(partial.cano.isEmpty())
        assertTrue(accountDraftIssues(partial).any { it.problem == AccountFormProblem.CREDENTIAL_PAIR })
        assertNull(managedTossLookupInput(draft.changeLookupField(AccountFormField.PROXY_URL, "https://new.example", stored), stored))
        val complete = partial.changeLookupField(AccountFormField.SECRET, "replacement-secret", stored)
            .changeLookupField(AccountFormField.PROXY_URL, "https://new.example", stored)
            .changeLookupField(AccountFormField.PROXY_TOKEN, "replacement-token", stored)
            .copy(cano = "8")
        assertTrue(isAccountDraftComplete(complete))
        val lookup = managedTossLookupInput(complete, stored)!!
        val saved = resolveAccountDrafts(listOf(stored), listOf(complete)).single()
        assertEquals(saved.appKey, lookup.clientId)
        assertEquals(saved.appSecret, lookup.clientSecret)
        assertEquals(saved.centralServerBaseUrl, lookup.proxyBaseUrl)
        assertEquals(saved.centralServerApiToken, lookup.proxyApiToken)
    }

    @Test fun whitespaceOnlyCredentialEditRetainsSelectionButChangedCredentialInvalidatesIt() {
        val draft = toss()
        assertEquals("7", draft.changeLookupField(AccountFormField.KEY, " test-client ").cano)
        assertEquals("", draft.changeLookupField(AccountFormField.KEY, "different-client").cano)
    }

    @Test fun validationIdentifiesAccountAndFieldWithoutExposingInput() {
        val valid = kis()
        val invalid = kis().copy(cano = "123", acntPrdtCd = "1")
        val issues = accountFormIssues(listOf(valid, invalid), "1234", "1234")
        assertEquals(setOf(AccountFormField.ACCOUNT, AccountFormField.PRODUCT), issues.map { it.field }.toSet())
        assertTrue(issues.all { it.uiId == invalid.uiId })
        assertFalse(accountFormIssueMessage(issues.first()).contains("test-secret"))
        assertTrue(accountFormIssues(listOf(valid), "1234", "1234").isEmpty())
        assertEquals(AccountFormProblem.PIN_MISMATCH, accountFormIssues(listOf(valid), "1234", "4321").single().problem)
    }

    @Test fun asciiPinAndAccountValidationPreservesLeadingZeros() {
        assertEquals("0012", asciiDigits("00-12", ACCOUNT_PIN_LENGTH))
        assertEquals("1234", asciiDigits("12345", ACCOUNT_PIN_LENGTH))
        assertEquals("", asciiDigits("１２３４١٢٣٤", ACCOUNT_PIN_LENGTH))
        assertTrue(isAccountPinValid("0012"))
        assertFalse(isAccountPinValid("１２３４"))
        assertFalse(isAccountPinValid("12ab"))
        assertFalse(isAccountDraftComplete(kis().copy(cano = "１２３４５６７８")))
        assertTrue(isAccountDraftComplete(kis().copy(cano = "00001234")))
        assertTrue(isAccountDraftComplete(toss()))
        assertFalse(isAccountDraftComplete(toss().copy(cano = "0")))
    }

    @Test fun lateLookupUpdatesLatestDraftWithoutRevertingEdits() = runBlocking {
        val original = toss().copy(cano = "")
        var drafts = listOf(original)
        val response = CompletableDeferred<List<String>>()
        val gate = FormRequestGate()
        val ticket = gate.invalidate()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runLatestFormRequest(gate, ticket, { response.await() }, { sequences ->
                drafts = updateAccountDraft(drafts, original.uiId) { current ->
                    current.copy(cano = retainedTossSelection(sequences, current.cano))
                }
            }, { fail("Unexpected failure") })
        }
        drafts = updateAccountDraft(drafts, original.uiId) { it.copy(label = "edited during request") }
        response.complete(listOf("7"))
        job.join()
        assertEquals("edited during request", drafts.single().label)
        assertEquals("7", drafts.single().cano)
    }

    @Test fun obsoleteLookupCannotOverwriteNewerSelectionOrReportAnError() = runBlocking {
        val gate = FormRequestGate()
        val oldResponse = CompletableDeferred<String>()
        var selection = ""
        var failures = 0
        val oldTicket = gate.invalidate()
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
            runLatestFormRequest(gate, oldTicket, { oldResponse.await() }, { selection = it }, { failures++ })
        }
        val newTicket = gate.invalidate()
        runLatestFormRequest(gate, newTicket, { "new" }, { selection = it }, { failures++ })
        oldResponse.completeExceptionally(IllegalStateException("old failure"))
        oldJob.join()
        assertEquals("new", selection)
        assertEquals(0, failures)
        runLatestFormRequest(gate, oldTicket, { "late old success" }, { selection = it }, { failures++ })
        assertEquals("new", selection)
    }

    @Test fun cancellationDoesNotBecomeLookupFailureEvenIfDependencySwallowsIt() = runBlocking {
        val gate = FormRequestGate()
        val ticket = gate.invalidate()
        var callbacks = 0
        val pending = CompletableDeferred<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runLatestFormRequest(gate, ticket,
                load = {
                    try { pending.await() } catch (_: CancellationException) { "late result" }
                },
                onSuccess = { callbacks++ },
                onFailure = { callbacks++ },
            )
        }
        job.cancelAndJoin()
        assertEquals(0, callbacks)
        assertTrue(job.isCancelled)
    }

    @Test fun removedAccountIgnoresLateResultsAndUndoRestoresIdentityAndOrder() {
        val first = kis()
        val second = toss()
        val removed = RemovedAccountDraft(first, 0)
        var drafts = listOf(second.copy(label = "retained edit"))
        drafts = updateAccountDraft(drafts, first.uiId) { it.copy(cano = "wrong") }
        assertEquals(1, drafts.size)
        drafts = restoreAccountDraft(drafts, removed)
        assertEquals(first, drafts[0])
        assertEquals("retained edit", drafts[1].label)
        assertEquals(drafts, restoreAccountDraft(drafts, removed))
    }

    @Test fun selectionRetainsCurrentChoiceAndDoesNotGuessAmongMultipleAccounts() {
        assertEquals("7", retainedTossSelection(listOf("7"), ""))
        assertEquals("8", retainedTossSelection(listOf("7", "8"), "8"))
        assertEquals("", retainedTossSelection(listOf("7", "8"), "9"))
        assertEquals("", retainedTossSelection(emptyList(), "7"))
    }

    @Test fun duplicateSubmissionIsBlockedUntilFailureCleanupThenRetryWorks() = runBlocking {
        val guard = FormSubmissionGuard()
        val pending = CompletableDeferred<String>()
        var saveCalls = 0
        var failures = 0
        var finished = 0
        assertTrue(guard.begin())
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runFormSubmission(guard, { saveCalls++; pending.await() }, { fail("Unexpected success") }, { failures++ }, { finished++ })
        }
        assertFalse(guard.begin())
        pending.completeExceptionally(IllegalStateException("test failure"))
        job.join()
        assertEquals(1, saveCalls)
        assertEquals(1, failures)
        assertEquals(1, finished)
        assertFalse(guard.isPending)
        assertTrue(guard.begin())
        var saved = ""
        runFormSubmission(guard, { "saved" }, { saved = it }, { fail("Unexpected failure") }, {})
        assertEquals("saved", saved)
        assertFalse(guard.isPending)
        assertFalse(guard.begin(externallyBusy = true))
    }

    @Test fun cancelledSubmissionAlwaysReleasesGuardWithoutReportingFailure() = runBlocking {
        val guard = FormSubmissionGuard()
        val pending = CompletableDeferred<String>()
        var failures = 0
        var finished = 0
        guard.begin()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runFormSubmission(guard, { pending.await() }, { fail("Unexpected success") }, { failures++ }, { finished++ })
        }
        job.cancelAndJoin()
        assertFalse(guard.isPending)
        assertEquals(0, failures)
        assertEquals(1, finished)
    }

    @Test fun backRequiresDiscardOnlyForDirtyIdleFormsAndNeverExitsDuringSave() {
        val original = listOf(kis())
        assertFalse(accountFormIsDirty(original, original, ""))
        assertTrue(accountFormIsDirty(original, original, "1"))
        assertTrue(accountFormIsDirty(original, listOf(original.single().copy(label = "new")), ""))
        assertEquals(AccountFormExit.EXIT, accountFormExit(false, false))
        assertEquals(AccountFormExit.CONFIRM_DISCARD, accountFormExit(false, true))
        assertEquals(AccountFormExit.WAIT, accountFormExit(true, true))
        assertEquals(AccountFormExit.WAIT, accountFormExit(true, false))
    }

    @Test fun exceptionMessagesNeverExposeCredentialTextOrMisclassifyGenericDenial() {
        val secret = "TEST_SECRET_DO_NOT_DISPLAY"
        assertFalse(accountFormErrorMessage(IllegalStateException(secret)).contains(secret))
        assertFalse(tossAccountLookupErrorMessage(IllegalStateException("access_denied $secret")).contains("IP"))
        assertFalse(tossAccountLookupErrorMessage(IllegalStateException(secret)).contains(secret))
        assertTrue(tossAccountLookupErrorMessage(IllegalStateException("IP address not allowed")).contains("개인 서버"))
        assertFalse(tossLookupInput("id", secret, false, "", "")!!.toString().contains(secret))
    }
}
