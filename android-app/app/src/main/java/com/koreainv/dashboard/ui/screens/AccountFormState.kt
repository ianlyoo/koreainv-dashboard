package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.AccountCredential
import com.koreainv.dashboard.network.AccountProfile
import com.koreainv.dashboard.network.Broker
import com.koreainv.dashboard.network.defaultAccountLabel
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val ACCOUNT_NUMBER_LENGTH = 8
internal const val ACCOUNT_PRODUCT_CODE_LENGTH = 2
internal const val ACCOUNT_PIN_LENGTH = 4

internal fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

internal fun asciiDigits(value: String, length: Int): String = value.filter { it in '0'..'9' }.take(length)

internal fun isAccountPinValid(pin: String): Boolean = pin.length == ACCOUNT_PIN_LENGTH && pin.isAsciiDigits()

internal fun isTossAccountSequence(value: String): Boolean =
    value.isAsciiDigits() && value.toLongOrNull()?.let { it > 0 } == true

internal fun isValidProxyUrl(value: String): Boolean = try {
    val uri = URI(value.trim())
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535)
} catch (_: Exception) {
    false
}

internal enum class AccountFormField { ACCOUNTS, KEY, SECRET, ACCOUNT, PRODUCT, PROXY_URL, PROXY_TOKEN, PIN, CONFIRM_PIN }

internal enum class AccountFormProblem { REQUIRED, ACCOUNT_LENGTH, PRODUCT_LENGTH, ACCOUNT_SELECTION, CREDENTIAL_PAIR, PROXY_PAIR, PROXY_URL, PIN_LENGTH, PIN_MISMATCH }

internal data class AccountFormIssue(val uiId: String?, val field: AccountFormField, val problem: AccountFormProblem)

internal fun accountFormIssueMessage(issue: AccountFormIssue): String = when (issue.problem) {
    AccountFormProblem.REQUIRED -> "필수 항목을 입력해 주세요."
    AccountFormProblem.ACCOUNT_LENGTH -> "계좌번호 앞 8자리를 숫자로 입력해 주세요."
    AccountFormProblem.PRODUCT_LENGTH -> "계좌번호 뒤 2자리를 숫자로 입력해 주세요."
    AccountFormProblem.ACCOUNT_SELECTION -> "연결 정보를 확인한 뒤 사용할 토스 계좌를 선택해 주세요."
    AccountFormProblem.CREDENTIAL_PAIR -> "토스 연결 정보를 바꾸려면 연결 ID와 비밀키를 함께 입력해 주세요."
    AccountFormProblem.PROXY_PAIR -> "서버 주소와 인증 토큰을 함께 입력해 주세요."
    AccountFormProblem.PROXY_URL -> "http:// 또는 https://로 시작하는 서버 주소를 입력해 주세요. 예: https://server.example"
    AccountFormProblem.PIN_LENGTH -> "잠금번호는 0~9 숫자 4자리로 입력해 주세요."
    AccountFormProblem.PIN_MISMATCH -> "두 잠금번호가 일치하지 않습니다."
}

internal fun List<AccountFormIssue>.messageFor(uiId: String?, field: AccountFormField): String? =
    firstOrNull { it.uiId == uiId && it.field == field }?.let(::accountFormIssueMessage)

/**
 * Editable representation of an account. App key/secret are never prefilled:
 * a blank input means "keep the stored value", which lets users edit an
 * account without exposing its credentials.
 */
internal data class ManagedAccountDraft(
    // UI identity is independent of the persisted broker/account identity. Memory only.
    val uiId: String = UUID.randomUUID().toString(),
    val broker: String = Broker.KIS,
    val id: String = "",
    val label: String = "",
    val cano: String = "",
    val acntPrdtCd: String = "01",
    val appKeyInput: String = "",
    val appSecretInput: String = "",
    val hasStoredKey: Boolean = false,
    val hasStoredSecret: Boolean = false,
    val useTossProxy: Boolean = false,
    val proxyBaseUrlInput: String = "",
    val proxyApiTokenInput: String = "",
    val hasStoredProxyConfig: Boolean = false,
)

internal fun accountDraftsFrom(profile: AccountProfile): List<ManagedAccountDraft> =
    profile.accounts.map { account ->
        ManagedAccountDraft(
            id = account.id,
            broker = Broker.normalize(account.broker),
            label = account.label,
            cano = account.cano,
            acntPrdtCd = account.acntPrdtCd,
            hasStoredKey = account.appKey.isNotBlank(),
            hasStoredSecret = account.appSecret.isNotBlank(),
            useTossProxy = Broker.normalize(account.broker) == Broker.TOSS &&
                account.centralServerBaseUrl.isNotBlank() && account.centralServerApiToken.isNotBlank(),
            hasStoredProxyConfig = account.centralServerBaseUrl.isNotBlank() &&
                account.centralServerApiToken.isNotBlank(),
        )
    }

internal fun isAccountDraftComplete(draft: ManagedAccountDraft): Boolean = accountDraftIssues(draft).isEmpty()

internal fun accountDraftIssues(draft: ManagedAccountDraft): List<AccountFormIssue> = buildList {
    fun issue(field: AccountFormField, problem: AccountFormProblem) {
        add(AccountFormIssue(draft.uiId, field, problem))
    }
    if (draft.appKeyInput.isBlank() && !draft.hasStoredKey) issue(AccountFormField.KEY, AccountFormProblem.REQUIRED)
    if (draft.appSecretInput.isBlank() && !draft.hasStoredSecret) issue(AccountFormField.SECRET, AccountFormProblem.REQUIRED)
    if (draft.broker == Broker.TOSS) {
        if (draft.appKeyInput.isBlank() != draft.appSecretInput.isBlank()) {
            issue(AccountFormField.KEY, AccountFormProblem.CREDENTIAL_PAIR)
            issue(AccountFormField.SECRET, AccountFormProblem.CREDENTIAL_PAIR)
        }
        if (!isTossAccountSequence(draft.cano.trim())) issue(AccountFormField.ACCOUNT, AccountFormProblem.ACCOUNT_SELECTION)
        if (draft.useTossProxy) {
            val urlBlank = draft.proxyBaseUrlInput.isBlank()
            val tokenBlank = draft.proxyApiTokenInput.isBlank()
            if (urlBlank != tokenBlank || (urlBlank && !draft.hasStoredProxyConfig)) {
                issue(AccountFormField.PROXY_URL, AccountFormProblem.PROXY_PAIR)
                issue(AccountFormField.PROXY_TOKEN, AccountFormProblem.PROXY_PAIR)
            }
            if (!urlBlank && !isValidProxyUrl(draft.proxyBaseUrlInput)) issue(AccountFormField.PROXY_URL, AccountFormProblem.PROXY_URL)
        }
    } else {
        if (draft.cano.trim().let { it.length != ACCOUNT_NUMBER_LENGTH || !it.isAsciiDigits() }) {
            issue(AccountFormField.ACCOUNT, AccountFormProblem.ACCOUNT_LENGTH)
        }
        if (draft.acntPrdtCd.trim().let { it.length != ACCOUNT_PRODUCT_CODE_LENGTH || !it.isAsciiDigits() }) {
            issue(AccountFormField.PRODUCT, AccountFormProblem.PRODUCT_LENGTH)
        }
    }
}

internal fun accountFormIssues(
    drafts: List<ManagedAccountDraft>,
    pin: String,
    confirmPin: String? = null,
): List<AccountFormIssue> = buildList {
    if (drafts.isEmpty()) add(AccountFormIssue(null, AccountFormField.ACCOUNTS, AccountFormProblem.REQUIRED))
    drafts.forEach { addAll(accountDraftIssues(it)) }
    if (!isAccountPinValid(pin)) add(AccountFormIssue(null, AccountFormField.PIN, AccountFormProblem.PIN_LENGTH))
    if (confirmPin != null) {
        if (!isAccountPinValid(confirmPin)) add(AccountFormIssue(null, AccountFormField.CONFIRM_PIN, AccountFormProblem.PIN_LENGTH))
        else if (pin != confirmPin) add(AccountFormIssue(null, AccountFormField.CONFIRM_PIN, AccountFormProblem.PIN_MISMATCH))
    }
}

internal fun accountManagementValidationError(
    drafts: List<ManagedAccountDraft>,
    pin: String,
    accountsRequiredError: String,
    pinLengthError: String,
): String? = when {
    !isAccountPinValid(pin) -> pinLengthError
    drafts.isEmpty() || drafts.any { !isAccountDraftComplete(it) } -> accountsRequiredError
    else -> null
}

/**
 * Resolves drafts into credentials. Stored app key/secret are kept whenever
 * the corresponding input is left blank, and central-server fields are carried
 * over from the original account so editing never drops them. Existing account
 * ids are preserved; new accounts keep a blank id for the saver to assign.
 */
internal fun resolveAccountDrafts(
    original: List<AccountCredential>,
    drafts: List<ManagedAccountDraft>,
): List<AccountCredential> = drafts.map { draft ->
    val existing = original.firstOrNull { it.id == draft.id }
    val cano = draft.cano.trim()
    val broker = Broker.normalize(draft.broker)
    val credentialSource = existing?.takeIf { Broker.normalize(it.broker) == broker }
    val acntPrdtCd = if (broker == Broker.KIS) draft.acntPrdtCd.trim() else ""
    AccountCredential(
        id = draft.id,
        label = draft.label.trim().ifBlank { defaultAccountLabel(cano, broker) },
        appKey = draft.appKeyInput.trim().ifBlank { credentialSource?.appKey.orEmpty().takeIf { draft.hasStoredKey }.orEmpty() },
        appSecret = draft.appSecretInput.trim().ifBlank { credentialSource?.appSecret.orEmpty().takeIf { draft.hasStoredSecret }.orEmpty() },
        cano = cano,
        acntPrdtCd = acntPrdtCd,
        broker = broker,
        centralServerBaseUrl = if (broker == Broker.TOSS && draft.useTossProxy) {
            draft.proxyBaseUrlInput.trim().ifBlank { credentialSource?.centralServerBaseUrl.orEmpty().takeIf { draft.hasStoredProxyConfig }.orEmpty() }
        } else {
            credentialSource?.centralServerBaseUrl.orEmpty().takeIf { broker == Broker.KIS }.orEmpty()
        },
        centralServerApiToken = if (broker == Broker.TOSS && draft.useTossProxy) {
            draft.proxyApiTokenInput.trim().ifBlank { credentialSource?.centralServerApiToken.orEmpty().takeIf { draft.hasStoredProxyConfig }.orEmpty() }
        } else {
            credentialSource?.centralServerApiToken.orEmpty().takeIf { broker == Broker.KIS }.orEmpty()
        },
    )
}

internal fun ManagedAccountDraft.changeBroker(value: String): ManagedAccountDraft {
    val next = Broker.normalize(value)
    if (broker == next) return this
    return copy(
        broker = next, id = "", cano = "", acntPrdtCd = if (next == Broker.KIS) "01" else "",
        appKeyInput = "", appSecretInput = "", hasStoredKey = false, hasStoredSecret = false,
        useTossProxy = false, proxyBaseUrlInput = "", proxyApiTokenInput = "", hasStoredProxyConfig = false,
    )
}

internal fun ManagedAccountDraft.changeConnection(useProxy: Boolean): ManagedAccountDraft =
    if (broker != Broker.TOSS || useTossProxy == useProxy) this
    else copy(useTossProxy = useProxy, cano = "")

internal fun ManagedAccountDraft.changeLookupField(
    field: AccountFormField,
    value: String,
    stored: AccountCredential? = null,
): ManagedAccountDraft {
    val updated = when (field) {
        AccountFormField.KEY -> copy(appKeyInput = value)
        AccountFormField.SECRET -> copy(appSecretInput = value)
        AccountFormField.PROXY_URL -> copy(proxyBaseUrlInput = value)
        AccountFormField.PROXY_TOKEN -> copy(proxyApiTokenInput = value)
        else -> return this
    }
    return if (broker == Broker.TOSS && managedTossLookupInput(this, stored) != managedTossLookupInput(updated, stored)) {
        updated.copy(cano = "")
    } else updated
}

/** The updater always receives current memory state, never a draft captured by a request. */
internal fun updateAccountDraft(
    drafts: List<ManagedAccountDraft>,
    uiId: String,
    update: (ManagedAccountDraft) -> ManagedAccountDraft,
): List<ManagedAccountDraft> = drafts.map { if (it.uiId == uiId) update(it) else it }

internal data class RemovedAccountDraft(val draft: ManagedAccountDraft, val index: Int)

internal fun restoreAccountDraft(drafts: List<ManagedAccountDraft>, removed: RemovedAccountDraft): List<ManagedAccountDraft> =
    if (drafts.any { it.uiId == removed.draft.uiId }) drafts
    else drafts.toMutableList().apply { add(removed.index.coerceIn(0, size), removed.draft) }

internal fun accountFormIsDirty(original: List<ManagedAccountDraft>, drafts: List<ManagedAccountDraft>, pin: String): Boolean =
    original != drafts || pin.isNotEmpty()

internal enum class AccountFormExit { WAIT, CONFIRM_DISCARD, EXIT }

internal fun accountFormExit(isBusy: Boolean, isDirty: Boolean): AccountFormExit = when {
    isBusy -> AccountFormExit.WAIT
    isDirty -> AccountFormExit.CONFIRM_DISCARD
    else -> AccountFormExit.EXIT
}

internal data class TossLookupInput(
    val clientId: String,
    val clientSecret: String,
    val useProxy: Boolean,
    val proxyBaseUrl: String,
    val proxyApiToken: String,
) {
    override fun toString(): String = "TossLookupInput([redacted])"
}

internal fun tossLookupInput(
    clientId: String,
    clientSecret: String,
    useProxy: Boolean,
    proxyBaseUrl: String,
    proxyApiToken: String,
    isEnabled: Boolean = true,
): TossLookupInput? {
    if (!isEnabled || clientId.isBlank() || clientSecret.isBlank()) return null
    if (useProxy && (!isValidProxyUrl(proxyBaseUrl) || proxyApiToken.isBlank())) return null
    return TossLookupInput(
        clientId.trim(), clientSecret.trim(), useProxy,
        if (useProxy) proxyBaseUrl.trim() else "",
        if (useProxy) proxyApiToken.trim() else "",
    )
}

/** Stored values may be used only while the corresponding preservation flags remain set. */
internal fun managedTossLookupInput(draft: ManagedAccountDraft, stored: AccountCredential?): TossLookupInput? {
    if (draft.broker != Broker.TOSS) return null
    val source = stored?.takeIf { Broker.normalize(it.broker) == draft.broker }
    val keyEdits = draft.appKeyInput.isNotBlank() || draft.appSecretInput.isNotBlank()
    val proxyEdits = draft.proxyBaseUrlInput.isNotBlank() || draft.proxyApiTokenInput.isNotBlank()
    return tossLookupInput(
        clientId = if (keyEdits) draft.appKeyInput else source?.appKey.orEmpty().takeIf { draft.hasStoredKey }.orEmpty(),
        clientSecret = if (keyEdits) draft.appSecretInput else source?.appSecret.orEmpty().takeIf { draft.hasStoredSecret }.orEmpty(),
        useProxy = draft.useTossProxy,
        proxyBaseUrl = if (proxyEdits) draft.proxyBaseUrlInput else source?.centralServerBaseUrl.orEmpty().takeIf { draft.hasStoredProxyConfig }.orEmpty(),
        proxyApiToken = if (proxyEdits) draft.proxyApiTokenInput else source?.centralServerApiToken.orEmpty().takeIf { draft.hasStoredProxyConfig }.orEmpty(),
    )
}

internal fun retainedTossSelection(sequences: List<String>, current: String): String =
    sequences.singleOrNull() ?: current.takeIf { it in sequences }.orEmpty()

/** Main-thread request generation also protects against dependencies that finish after cancellation. */
internal class FormRequestGate {
    private var generation = 0L
    fun invalidate(): Long = ++generation
    fun isCurrent(ticket: Long): Boolean = generation == ticket
}

internal suspend fun <T> runLatestFormRequest(
    gate: FormRequestGate,
    ticket: Long,
    load: suspend () -> T,
    onSuccess: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        val result = load()
        currentCoroutineContext().ensureActive()
        if (gate.isCurrent(ticket)) onSuccess(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (gate.isCurrent(ticket)) onFailure(error)
    }
}

/** Acquired synchronously before dispatch, so two taps cannot race a parent recomposition. */
internal class FormSubmissionGuard {
    var isPending: Boolean = false
        private set
    fun begin(externallyBusy: Boolean = false): Boolean {
        if (externallyBusy || isPending) return false
        isPending = true
        return true
    }
    fun finish() { isPending = false }
}

internal suspend fun <T> runFormSubmission(
    guard: FormSubmissionGuard,
    save: suspend () -> T,
    onSuccess: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
    onFinished: () -> Unit,
) {
    try {
        val result = save()
        currentCoroutineContext().ensureActive()
        onSuccess(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    } finally {
        guard.finish()
        onFinished()
    }
}

internal fun accountFormErrorMessage(error: Throwable): String = when (error) {
    is java.net.SocketTimeoutException -> "응답이 지연되고 있습니다. 잠시 후 다시 시도하세요."
    is java.io.IOException -> "연결을 확인한 뒤 다시 시도하세요."
    else -> "요청을 완료하지 못했습니다. 입력 내용을 확인하고 다시 시도하세요."
}


internal fun tossAccountLookupErrorMessage(error: Throwable): String {
    val raw = error.message.orEmpty()
    return if (raw.contains("IP address not allowed", ignoreCase = true)
    ) {
        "현재 네트워크 IP가 토스증권에서 허용되지 않았습니다. 모바일 데이터에서는 개인 서버 연결을 사용하세요."
    } else {
        accountFormErrorMessage(error)
    }
}
