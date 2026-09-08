package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.AccountCredential
import com.koreainv.dashboard.network.AccountProfile
import com.koreainv.dashboard.network.Broker
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    profile: AccountProfile,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (pin: String, accounts: List<AccountCredential>) -> Unit,
    onBack: () -> Unit,
) {
    val originalDrafts = remember(profile) { accountDraftsFrom(profile) }
    var drafts by remember(profile) { mutableStateOf(originalDrafts) }
    var pin by remember(profile) { mutableStateOf("") }
    var validationError by remember(profile) { mutableStateOf<String?>(null) }

    val submissionGuard = remember(profile) { FormSubmissionGuard() }
    var submitting by remember(profile) { mutableStateOf(false) }
    val isBusy = isSaving || submitting
    var validationRequested by remember(profile) { mutableStateOf(false) }
    var removedDrafts by remember(profile) { mutableStateOf(emptyList<RemovedAccountDraft>()) }
    var showDiscard by remember(profile) { mutableStateOf(false) }
    val focusRequesters = remember(profile) { mutableMapOf<Pair<String?, AccountFormField>, FocusRequester>() }
    fun focusFor(id: String?, field: AccountFormField): FocusRequester = focusRequesters.getOrPut(id to field) { FocusRequester() }
    val issues = if (validationRequested) accountFormIssues(drafts, pin) else emptyList()

    fun requestBack() {
        when (accountFormExit(isBusy || submissionGuard.isPending, accountFormIsDirty(originalDrafts, drafts, pin))) {
            AccountFormExit.WAIT -> Unit
            AccountFormExit.CONFIRM_DISCARD -> showDiscard = true
            AccountFormExit.EXIT -> onBack()
        }
    }
    val latestExternalBusy by rememberUpdatedState(isSaving)
    LaunchedEffect(submitting, isSaving, errorMessage) {
        if (!isSaving) {
            // Allow the parent callback to publish busy, including operations completing in one frame.
            if (submitting) withFrameNanos { }
            if (latestExternalBusy) return@LaunchedEffect
            submissionGuard.finish()
            submitting = false
        }
    }
    BackHandler { requestBack() }
    if (showDiscard && !isBusy) {
        AccountDiscardDialog(
            onDismiss = { showDiscard = false },
            onDiscard = {
                if (!isBusy && !submissionGuard.isPending) {
                    showDiscard = false
                    onBack()
                }
            },
        )
    }

    fun submit() {
        if (isBusy || submissionGuard.isPending) return
        validationRequested = true
        val problems = accountFormIssues(drafts, pin)
        if (problems.isNotEmpty()) {
            validationError = "표시된 입력 항목을 확인해 주세요."
            problems.first().let { focusRequesters[it.uiId to it.field]?.requestFocus() }
            return
        }
        if (!submissionGuard.begin(isSaving)) return
        validationError = null
        submitting = true
        try {
            onSave(pin, resolveAccountDrafts(profile.accounts, drafts))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            submissionGuard.finish()
            submitting = false
            throw cancelled
        } catch (error: Exception) {
            validationError = accountFormErrorMessage(error)
            submissionGuard.finish()
            submitting = false
        }
    }

    val displayError = validationError ?: errorMessage?.let { "저장하지 못했습니다. PIN과 연결 상태를 확인한 뒤 다시 시도하세요." }
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.account_management_title),
                lastSynced = null,
                navigationButton = {
                    HeaderIconButton(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        onClick = ::requestBack,
                        enabled = !isBusy,
                    )
                },
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        ScreenBackground(modifier = Modifier.padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.account_management_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                if (displayError != null) {
                    AccountFormErrorText(displayError)
                }
                drafts.forEachIndexed { index, draft ->
                    key(draft.uiId, draft.broker) {
                        AccountManagementCard(
                            index = index,
                            draft = draft,
                            storedAccount = profile.accounts.firstOrNull { it.id == draft.id },
                            isPrimary = draft.broker == Broker.KIS && drafts.take(index).none { it.broker == Broker.KIS },
                            isRemovable = drafts.size > 1,
                            isEnabled = !isBusy,
                            issues = issues,
                            focusFor = { focusFor(draft.uiId, it) },
                            onUpdate = { update ->
                                if (!isBusy && !submissionGuard.isPending) {
                                    validationError = null
                                    drafts = updateAccountDraft(drafts, draft.uiId, update)
                                }
                            },
                            onRemove = {
                                if (!isBusy && !submissionGuard.isPending && drafts.size > 1) {
                                    validationError = null
                                    val removedIndex = drafts.indexOfFirst { it.uiId == draft.uiId }
                                    if (removedIndex >= 0) {
                                        removedDrafts = removedDrafts + RemovedAccountDraft(drafts[removedIndex], removedIndex)
                                        drafts = drafts.filterNot { it.uiId == draft.uiId }
                                    }
                                }
                            },
                        )
                    }
                }
                if (removedDrafts.isNotEmpty()) {
                    AccountRemovalUndo(!isBusy) {
                        if (!isBusy && !submissionGuard.isPending) {
                            drafts = restoreAccountDraft(drafts, removedDrafts.last())
                            removedDrafts = removedDrafts.dropLast(1)
                            validationError = null
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        validationError = null
                        if (!isBusy && !submissionGuard.isPending) drafts = drafts + ManagedAccountDraft()
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.add_account), color = TextGold)
                }
                Divider(color = SurfaceBorder)
                ManagementField(
                    value = pin,
                    onValueChange = {
                        validationError = null
                        if (!isBusy && !submissionGuard.isPending) pin = asciiDigits(it, ACCOUNT_PIN_LENGTH)
                    },
                    label = "현재 앱 잠금번호",
                    supportingText = "변경사항을 저장하려면 현재 잠금번호 4자리를 입력하세요.",
                    errorMessage = issues.messageFor(null, AccountFormField.PIN),
                    modifier = Modifier.focusRequester(focusFor(null, AccountFormField.PIN)),
                    onDone = ::submit,
                    keyboardType = KeyboardType.NumberPassword,
                    isSecret = true,
                    isEnabled = !isBusy,
                )
                Button(
                    onClick = ::submit,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TextGold),
                ) {
                    Text(
                        text = stringResource(R.string.save_accounts),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        }

        if (isBusy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(24.dp)) {
                    DashboardLoadingState("계좌 변경사항을 안전하게 저장하고 있습니다.")
                }
            }
        }
    }
}

@Composable
private fun AccountManagementCard(
    index: Int,
    draft: ManagedAccountDraft,
    storedAccount: AccountCredential?,
    isPrimary: Boolean,
    isRemovable: Boolean,
    isEnabled: Boolean,
    issues: List<AccountFormIssue>,
    focusFor: (AccountFormField) -> FocusRequester,
    onUpdate: ((ManagedAccountDraft) -> ManagedAccountDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val lookupInput = managedTossLookupInput(draft, storedAccount)
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
            ManagementField(
                value = draft.label,
                onValueChange = { onUpdate { current -> current.copy(label = it) } },
                label = "계좌 이름 (선택)",
                isEnabled = isEnabled,
            )
            Spacer(modifier = Modifier.height(12.dp))
            BrokerSelector(
                broker = draft.broker,
                isEnabled = isEnabled,
                onChange = { broker -> onUpdate { it.changeBroker(broker) } },
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (draft.broker == Broker.TOSS) {
                ManagementTossConnectionSelector(
                    useProxy = draft.useTossProxy,
                    isEnabled = isEnabled,
                    onChange = { useProxy -> onUpdate { it.changeConnection(useProxy) } },
                )
                if (draft.useTossProxy) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ManagementField(
                        value = draft.proxyBaseUrlInput,
                        errorMessage = issues.messageFor(draft.uiId, AccountFormField.PROXY_URL),
                        modifier = Modifier.focusRequester(focusFor(AccountFormField.PROXY_URL)),
                        onValueChange = { onUpdate { current -> current.changeLookupField(AccountFormField.PROXY_URL, it, storedAccount) } },
                        label = "개인 서버 주소",
                        keyboardType = KeyboardType.Uri,
                        isEnabled = isEnabled,
                        supportingText = if (draft.hasStoredProxyConfig) "비워두면 기존 연결 유지 · 변경 시 주소와 토큰을 함께 입력" else "서버 주소와 인증 토큰을 함께 입력하세요.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ManagementField(
                        value = draft.proxyApiTokenInput,
                        errorMessage = issues.messageFor(draft.uiId, AccountFormField.PROXY_TOKEN),
                        modifier = Modifier.focusRequester(focusFor(AccountFormField.PROXY_TOKEN)),
                        onValueChange = { onUpdate { current -> current.changeLookupField(AccountFormField.PROXY_TOKEN, it, storedAccount) } },
                        label = "서버 인증 토큰",
                        isSecret = true,
                        isEnabled = isEnabled,
                        supportingText = if (draft.hasStoredProxyConfig) "비워두면 기존 연결 유지 · 변경 시 주소와 토큰을 함께 입력" else "서버 주소와 인증 토큰을 함께 입력하세요.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            ManagementField(
                value = draft.appKeyInput,
                errorMessage = issues.messageFor(draft.uiId, AccountFormField.KEY),
                modifier = Modifier.focusRequester(focusFor(AccountFormField.KEY)),
                onValueChange = {
                    onUpdate { current -> current.changeLookupField(AccountFormField.KEY, it, storedAccount) }
                },
                label = if (draft.broker == Broker.TOSS) "연결 ID" else "앱 키",
                isSecret = true,
                isEnabled = isEnabled,
                supportingText = if (draft.broker == Broker.TOSS) "기존 정보는 표시하지 않습니다. 변경 시 ID와 비밀키를 함께 입력하세요." else if (draft.hasStoredKey) stringResource(R.string.keep_existing_value) else null,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ManagementField(
                value = draft.appSecretInput,
                errorMessage = issues.messageFor(draft.uiId, AccountFormField.SECRET),
                modifier = Modifier.focusRequester(focusFor(AccountFormField.SECRET)),
                onValueChange = {
                    onUpdate { current -> current.changeLookupField(AccountFormField.SECRET, it, storedAccount) }
                },
                label = if (draft.broker == Broker.TOSS) "연결 비밀키" else "앱 시크릿",
                isSecret = true,
                isEnabled = isEnabled,
                supportingText = if (draft.broker == Broker.TOSS) "ID와 비밀키를 모두 비워두면 기존 정보를 유지합니다." else if (draft.hasStoredSecret) stringResource(R.string.keep_existing_value) else null,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (draft.broker == Broker.TOSS) {
                TossAccountPicker(
                    lookupInput = lookupInput,
                    useProxy = draft.useTossProxy,
                    selectedAccountSeq = draft.cano,
                    validationMessage = issues.messageFor(draft.uiId, AccountFormField.ACCOUNT),
                    modifier = Modifier.focusRequester(focusFor(AccountFormField.ACCOUNT)),
                    isEnabled = isEnabled,
                    onAccountSelected = { accountSeq ->
                        onUpdate { current -> if (managedTossLookupInput(current, storedAccount) == lookupInput) current.copy(cano = accountSeq) else current }
                    },
                )
            } else {
                ManagementField(
                    value = draft.cano,
                    errorMessage = issues.messageFor(draft.uiId, AccountFormField.ACCOUNT),
                    modifier = Modifier.focusRequester(focusFor(AccountFormField.ACCOUNT)),
                    onValueChange = {
                        onUpdate { current -> current.copy(cano = asciiDigits(it, ACCOUNT_NUMBER_LENGTH)) }
                    },
                    label = "계좌번호 앞 8자리",
                    keyboardType = KeyboardType.Number,
                    isEnabled = isEnabled,
                )
                Spacer(modifier = Modifier.height(12.dp))
                ManagementField(
                    value = draft.acntPrdtCd,
                    errorMessage = issues.messageFor(draft.uiId, AccountFormField.PRODUCT),
                    modifier = Modifier.focusRequester(focusFor(AccountFormField.PRODUCT)),
                    onValueChange = {
                        onUpdate { current -> current.copy(acntPrdtCd = asciiDigits(it, ACCOUNT_PRODUCT_CODE_LENGTH)) }
                    },
                    label = "계좌번호 뒤 2자리",
                    keyboardType = KeyboardType.Number,
                    isEnabled = isEnabled,
                )
            }
        }
    }
}

@Composable
private fun ManagementTossConnectionSelector(
    useProxy: Boolean,
    isEnabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
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
@OptIn(ExperimentalComposeUiApi::class)
private fun ManagementField(
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
            disabledTextColor = TextPrimary.copy(alpha = 0.6f),
            disabledLabelColor = TextSecondary.copy(alpha = 0.7f),
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
