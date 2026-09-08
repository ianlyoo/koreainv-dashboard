package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.AccountProfile
import com.koreainv.dashboard.network.Broker
import com.koreainv.dashboard.network.SettingsManager
import com.koreainv.dashboard.network.SetupInput
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    settingsManager: SettingsManager,
    onSetupSuccess: (AccountProfile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(listOf(ManagedAccountDraft())) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val submissionGuard = remember { FormSubmissionGuard() }
    var validationRequested by remember { mutableStateOf(false) }
    var removedDrafts by remember { mutableStateOf(emptyList<RemovedAccountDraft>()) }
    val focusRequesters = remember { mutableMapOf<Pair<String?, AccountFormField>, FocusRequester>() }
    fun focusFor(id: String?, field: AccountFormField): FocusRequester = focusRequesters.getOrPut(id to field) { FocusRequester() }
    val issues = if (validationRequested) accountFormIssues(accounts, pin, confirmPin) else emptyList()


    fun submit() {
        if (isLoading || submissionGuard.isPending) return
        validationRequested = true
        val problems = accountFormIssues(accounts, pin, confirmPin)
        if (problems.isNotEmpty()) {
            errorMessage = "표시된 입력 항목을 확인해 주세요."
            problems.first().let { focusRequesters[it.uiId to it.field]?.requestFocus() }
            return
        }
        if (!submissionGuard.begin()) return
        val submittedAccounts = accounts
        val submittedPin = pin
        errorMessage = null
        isLoading = true
        scope.launch {
            runFormSubmission(
                guard = submissionGuard,
                save = {
                    settingsManager.saveProfile(
                        inputs = submittedAccounts.map { account ->
                            SetupInput(
                                appKey = account.appKeyInput,
                                appSecret = account.appSecretInput,
                                cano = account.cano,
                                acntPrdtCd = if (account.broker == Broker.KIS) account.acntPrdtCd else "",
                                pin = submittedPin,
                                label = account.label,
                                broker = account.broker,
                                centralServerBaseUrl = if (account.useTossProxy) account.proxyBaseUrlInput else "",
                                centralServerApiToken = if (account.useTossProxy) account.proxyApiTokenInput else "",
                            )
                        },
                        pin = submittedPin,
                    )
                },
                onSuccess = onSetupSuccess,
                onFailure = { errorMessage = accountFormErrorMessage(it) },
                onFinished = { isLoading = false },
            )
        }
    }

    CredentialShell(
        title = stringResource(R.string.setup_title),
        subtitle = "증권사를 선택하고 계좌를 연결하세요. 연결 정보는 이 기기에 암호화해 저장합니다.",
        loadingMessage = "계좌 설정을 안전하게 저장하고 있습니다.",
        isLoading = isLoading,
        errorMessage = errorMessage,
    ) {
        accounts.forEachIndexed { index, account ->
            key(account.uiId, account.broker) {
                AccountSection(
                    index = index,
                    account = account,
                    isPrimary = account.broker == Broker.KIS && accounts.take(index).none { it.broker == Broker.KIS },
                    isRemovable = accounts.size > 1,
                    isEnabled = !isLoading,
                    issues = issues,
                    focusFor = { focusFor(account.uiId, it) },
                    onUpdate = { update ->
                        if (!isLoading) {
                            errorMessage = null
                            accounts = updateAccountDraft(accounts, account.uiId, update)
                        }
                    },
                    onRemove = {
                        if (!isLoading && accounts.size > 1) {
                            errorMessage = null
                            val removedIndex = accounts.indexOfFirst { it.uiId == account.uiId }
                            if (removedIndex >= 0) {
                                removedDrafts = removedDrafts + RemovedAccountDraft(accounts[removedIndex], removedIndex)
                                accounts = accounts.filterNot { it.uiId == account.uiId }
                            }
                        }
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        if (removedDrafts.isNotEmpty()) {
            AccountRemovalUndo(!isLoading) {
                if (!isLoading) {
                    accounts = restoreAccountDraft(accounts, removedDrafts.last())
                    removedDrafts = removedDrafts.dropLast(1)
                    errorMessage = null
                }
            }
        }
        OutlinedButton(
            onClick = { if (!isLoading) { errorMessage = null; accounts = accounts + ManagedAccountDraft() } },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.add_account), color = TextGold)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Divider(color = SurfaceBorder)
        Spacer(modifier = Modifier.height(12.dp))
        SetupField(
            value = pin,
            onValueChange = { if (!isLoading) { errorMessage = null; pin = asciiDigits(it, ACCOUNT_PIN_LENGTH) } },
            label = "앱 잠금번호 4자리",
            supportingText = "앱을 열 때 사용할 숫자 4자리입니다.",
            errorMessage = issues.messageFor(null, AccountFormField.PIN),
            modifier = Modifier.focusRequester(focusFor(null, AccountFormField.PIN)),
            keyboardType = KeyboardType.NumberPassword,
            isSecret = true,
            isEnabled = !isLoading,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SetupField(
            value = confirmPin,
            onValueChange = { if (!isLoading) { errorMessage = null; confirmPin = asciiDigits(it, ACCOUNT_PIN_LENGTH) } },
            label = "잠금번호 다시 입력",
            errorMessage = issues.messageFor(null, AccountFormField.CONFIRM_PIN),
            modifier = Modifier.focusRequester(focusFor(null, AccountFormField.CONFIRM_PIN)),
            onDone = ::submit,
            keyboardType = KeyboardType.NumberPassword,
            isSecret = true,
            isEnabled = !isLoading,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = ::submit,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TextGold),
        ) {
            Text(text = stringResource(R.string.complete_setup), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AccountSection(
    index: Int,
    account: ManagedAccountDraft,
    isPrimary: Boolean,
    isRemovable: Boolean,
    isEnabled: Boolean,
    issues: List<AccountFormIssue>,
    focusFor: (AccountFormField) -> FocusRequester,
    onUpdate: ((ManagedAccountDraft) -> ManagedAccountDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val lookupInput = managedTossLookupInput(account, null)
    PremiumGlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.account_section_title, index + 1),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                    if (isPrimary) {
                        Text(
                            text = stringResource(R.string.primary_account),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextGold,
                        )
                    }
                }
                if (isRemovable) {
                    TextButton(onClick = onRemove, enabled = isEnabled) {
                        Text(text = stringResource(R.string.remove_account), color = TextSecondary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = SurfaceBorder)
            Spacer(modifier = Modifier.height(14.dp))
            SetupField(
                isEnabled = isEnabled,
                value = account.label,
                onValueChange = { onUpdate { current -> current.copy(label = it) } },
                label = "계좌 이름 (선택)",
            )
            Spacer(modifier = Modifier.height(12.dp))
            BrokerSelector(
                broker = account.broker,
                isEnabled = isEnabled,
                onChange = { broker -> onUpdate { it.changeBroker(broker) } },
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (account.broker == Broker.TOSS) {
                TossConnectionSelector(
                    useProxy = account.useTossProxy,
                    isEnabled = isEnabled,
                    onChange = { useProxy -> onUpdate { it.changeConnection(useProxy) } },
                )
                if (account.useTossProxy) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SetupField(
                        isEnabled = isEnabled,
                        value = account.proxyBaseUrlInput,
                        errorMessage = issues.messageFor(account.uiId, AccountFormField.PROXY_URL),
                        modifier = Modifier.focusRequester(focusFor(AccountFormField.PROXY_URL)),
                        onValueChange = { onUpdate { current -> current.changeLookupField(AccountFormField.PROXY_URL, it, null) } },
                        label = "개인 서버 주소",
                        keyboardType = KeyboardType.Uri,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SetupField(
                        isEnabled = isEnabled,
                        value = account.proxyApiTokenInput,
                        errorMessage = issues.messageFor(account.uiId, AccountFormField.PROXY_TOKEN),
                        modifier = Modifier.focusRequester(focusFor(AccountFormField.PROXY_TOKEN)),
                        onValueChange = { onUpdate { current -> current.changeLookupField(AccountFormField.PROXY_TOKEN, it, null) } },
                        label = "서버 인증 토큰",
                        isSecret = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            SetupField(
                isEnabled = isEnabled,
                value = account.appKeyInput,
                errorMessage = issues.messageFor(account.uiId, AccountFormField.KEY),
                modifier = Modifier.focusRequester(focusFor(AccountFormField.KEY)),
                onValueChange = {
                    onUpdate { current -> current.changeLookupField(AccountFormField.KEY, it, null) }
                },
                label = if (account.broker == Broker.TOSS) "연결 ID" else "앱 키",
                isSecret = true,
                supportingText = "선택한 증권사에서 발급한 연결 정보를 입력하세요.",
            )
            Spacer(modifier = Modifier.height(12.dp))
            SetupField(
                isEnabled = isEnabled,
                value = account.appSecretInput,
                errorMessage = issues.messageFor(account.uiId, AccountFormField.SECRET),
                modifier = Modifier.focusRequester(focusFor(AccountFormField.SECRET)),
                onValueChange = {
                    onUpdate { current -> current.changeLookupField(AccountFormField.SECRET, it, null) }
                },
                label = if (account.broker == Broker.TOSS) "연결 비밀키" else "앱 시크릿",
                isSecret = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (account.broker == Broker.TOSS) {
                TossAccountPicker(
                    lookupInput = lookupInput,
                    useProxy = account.useTossProxy,
                    selectedAccountSeq = account.cano,
                    validationMessage = issues.messageFor(account.uiId, AccountFormField.ACCOUNT),
                    modifier = Modifier.focusRequester(focusFor(AccountFormField.ACCOUNT)),
                    isEnabled = isEnabled,
                    onAccountSelected = { accountSeq ->
                        onUpdate { current -> if (managedTossLookupInput(current, null) == lookupInput) current.copy(cano = accountSeq) else current }
                    },
                )
            } else {
                SetupField(
                    isEnabled = isEnabled,
                    value = account.cano,
                    errorMessage = issues.messageFor(account.uiId, AccountFormField.ACCOUNT),
                    modifier = Modifier.focusRequester(focusFor(AccountFormField.ACCOUNT)),
                    onValueChange = {
                        onUpdate { current -> current.copy(cano = asciiDigits(it, ACCOUNT_NUMBER_LENGTH)) }
                    },
                    label = "계좌번호 앞 8자리",
                    supportingText = "숫자 8자리 · 하이픈 없이 입력",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(modifier = Modifier.height(12.dp))
                SetupField(
                    isEnabled = isEnabled,
                    value = account.acntPrdtCd,
                    errorMessage = issues.messageFor(account.uiId, AccountFormField.PRODUCT),
                    modifier = Modifier.focusRequester(focusFor(AccountFormField.PRODUCT)),
                    onValueChange = {
                        onUpdate { current -> current.copy(acntPrdtCd = asciiDigits(it, ACCOUNT_PRODUCT_CODE_LENGTH)) }
                    },
                    label = "계좌번호 뒤 2자리",
                    supportingText = "계좌의 상품 코드입니다. 예: 01",
                    keyboardType = KeyboardType.Number,
                )
            }
        }
    }
}

@Composable
private fun TossConnectionSelector(useProxy: Boolean, isEnabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.toss_connection_method), color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to R.string.toss_connection_direct, true to R.string.toss_connection_proxy).forEach { (value, label) ->
                OutlinedButton(
                    onClick = { if (isEnabled) onChange(value) },
                    enabled = isEnabled,
                    modifier = Modifier.weight(1f).semantics { selected = useProxy == value },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (useProxy == value) TextGold.copy(alpha = 0.15f) else Color.Transparent,
                    ),
                ) {
                    Text(text = stringResource(label), color = if (useProxy == value) TextGold else TextSecondary)
                }
            }
        }
    }
}

@Composable
internal fun BrokerSelector(broker: String, isEnabled: Boolean, onChange: (String) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val options = listOf(Broker.KIS to R.string.broker_kis, Broker.TOSS to R.string.broker_toss)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.broker), color = TextSecondary)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 280.dp || fontScale > 1.15f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (value, label) ->
                        BrokerOption(broker, value, label, isEnabled, onChange, Modifier.fillMaxWidth())
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (value, label) ->
                        BrokerOption(broker, value, label, isEnabled, onChange, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BrokerOption(
    broker: String,
    value: String,
    label: Int,
    isEnabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(
        onClick = { if (isEnabled) onChange(value) },
        enabled = isEnabled,
        modifier = modifier.heightIn(min = 48.dp).semantics { selected = broker == value },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (broker == value) TextGold.copy(alpha = 0.15f) else Color.Transparent,
        ),
    ) {
        Text(text = stringResource(label), color = if (broker == value) TextGold else TextSecondary)
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SetupField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isSecret: Boolean = false,
    isEnabled: Boolean = true,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    errorMessage: String? = null,
) {
    var revealInput by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        value = value,
        onValueChange = onValueChange,
        enabled = isEnabled,
        label = { Text(label) },
        isError = errorMessage != null,
        trailingIcon = if (isSecret && keyboardType != KeyboardType.NumberPassword) {
            { TextButton(onClick = { revealInput = !revealInput }, enabled = isEnabled) { Text(if (revealInput) "숨기기" else "보기") } }
        } else null,
        supportingText = (errorMessage ?: supportingText)?.let { message -> { Text(message) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TextGold,
            unfocusedBorderColor = SurfaceBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = TextGold,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedLabelColor = TextGold,
            unfocusedLabelColor = TextSecondary,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isSecret && keyboardType == KeyboardType.Text) KeyboardType.Password else keyboardType,
            autoCorrect = false,
            imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Next) },
            onDone = {
                if (isEnabled) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onDone?.invoke()
                }
            },
        ),
        visualTransformation = if (isSecret && !revealInput) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}
