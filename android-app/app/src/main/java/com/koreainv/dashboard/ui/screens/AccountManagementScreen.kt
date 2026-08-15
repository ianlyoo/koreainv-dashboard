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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.AccountCredential
import com.koreainv.dashboard.network.AccountProfile
import com.koreainv.dashboard.network.Broker
import com.koreainv.dashboard.network.defaultAccountLabel
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextHint
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary

internal const val ACCOUNT_NUMBER_LENGTH = 8
internal const val ACCOUNT_PRODUCT_CODE_LENGTH = 2
internal const val ACCOUNT_PIN_LENGTH = 4

/**
 * Editable representation of an account. App key/secret are never prefilled:
 * a blank input means "keep the stored value", which lets users edit an
 * account without exposing its credentials.
 */
internal data class ManagedAccountDraft(
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

internal fun isAccountDraftComplete(draft: ManagedAccountDraft): Boolean {
    val cano = draft.cano.trim()
    val productCode = draft.acntPrdtCd.trim()
    val keyOk = draft.appKeyInput.isNotBlank() || draft.hasStoredKey
    val secretOk = draft.appSecretInput.isNotBlank() || draft.hasStoredSecret
    val tossCredentialPairOk = draft.broker != Broker.TOSS ||
        draft.appKeyInput.isBlank() == draft.appSecretInput.isBlank()
    val proxyValuesOk = if (draft.broker == Broker.TOSS && draft.useTossProxy) {
        val pairOk = draft.proxyBaseUrlInput.isBlank() == draft.proxyApiTokenInput.isBlank()
        val inputUrlOk = draft.proxyBaseUrlInput.isBlank() ||
            draft.proxyBaseUrlInput.trim().startsWith("https://", ignoreCase = true)
        pairOk && inputUrlOk && (draft.hasStoredProxyConfig ||
            (draft.proxyBaseUrlInput.isNotBlank() && draft.proxyApiTokenInput.isNotBlank()))
    } else {
        true
    }
    val canoOk = if (draft.broker == Broker.TOSS) {
        cano.isNotBlank() && cano.all(Char::isDigit) && cano.toLongOrNull()?.let { it > 0 } == true
    } else {
        cano.length == ACCOUNT_NUMBER_LENGTH && cano.all(Char::isDigit)
    }
    val productOk = draft.broker == Broker.TOSS ||
        (productCode.length == ACCOUNT_PRODUCT_CODE_LENGTH && productCode.all(Char::isDigit))
    return keyOk && secretOk && tossCredentialPairOk && proxyValuesOk && canoOk && productOk
}

internal fun accountManagementValidationError(
    drafts: List<ManagedAccountDraft>,
    pin: String,
    accountsRequiredError: String,
    pinLengthError: String,
): String? = when {
    pin.length != ACCOUNT_PIN_LENGTH -> pinLengthError
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
        appKey = draft.appKeyInput.trim().ifBlank { credentialSource?.appKey.orEmpty() },
        appSecret = draft.appSecretInput.trim().ifBlank { credentialSource?.appSecret.orEmpty() },
        cano = cano,
        acntPrdtCd = acntPrdtCd,
        broker = broker,
        centralServerBaseUrl = if (broker == Broker.TOSS && draft.useTossProxy) {
            draft.proxyBaseUrlInput.trim().ifBlank { credentialSource?.centralServerBaseUrl.orEmpty() }
        } else {
            existing?.centralServerBaseUrl.orEmpty().takeIf { broker == Broker.KIS }.orEmpty()
        },
        centralServerApiToken = if (broker == Broker.TOSS && draft.useTossProxy) {
            draft.proxyApiTokenInput.trim().ifBlank { credentialSource?.centralServerApiToken.orEmpty() }
        } else {
            existing?.centralServerApiToken.orEmpty().takeIf { broker == Broker.KIS }.orEmpty()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    profile: AccountProfile,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (pin: String, accounts: List<AccountCredential>) -> Unit,
    onBack: () -> Unit,
) {
    val accountsRequiredError = stringResource(R.string.setup_accounts_required)
    val pinLengthError = stringResource(R.string.pin_six_digits)
    var drafts by remember(profile) { mutableStateOf(accountDraftsFrom(profile)) }
    var pin by remember(profile) { mutableStateOf("") }
    var validationError by remember(profile) { mutableStateOf<String?>(null) }

    fun submit() {
        val error = accountManagementValidationError(drafts, pin, accountsRequiredError, pinLengthError)
        if (error != null) {
            validationError = error
            return
        }
        validationError = null
        onSave(pin, resolveAccountDrafts(profile.accounts, drafts))
    }

    val displayError = errorMessage ?: validationError
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
                        onClick = onBack,
                    )
                },
            )
        },
        containerColor = Background,
    ) { paddingValues ->
        ScreenBackground(modifier = Modifier.padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                    Text(
                        text = displayError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                drafts.forEachIndexed { index, draft ->
                    AccountManagementCard(
                        index = index,
                        draft = draft,
                        storedAccount = profile.accounts.firstOrNull { it.id == draft.id },
                        isPrimary = draft.broker == Broker.KIS && drafts.take(index).none { it.broker == Broker.KIS },
                        isRemovable = drafts.size > 1,
                        isEnabled = !isSaving,
                        onUpdate = { updated ->
                            validationError = null
                            drafts = drafts.mapIndexed { i, current -> if (i == index) updated else current }
                        },
                        onRemove = {
                            validationError = null
                            drafts = drafts.filterIndexed { i, _ -> i != index }
                        },
                    )
                }
                OutlinedButton(
                    onClick = {
                        validationError = null
                        drafts = drafts + ManagedAccountDraft()
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.add_account), color = TextGold)
                }
                Divider(color = SurfaceBorder)
                ManagementField(
                    value = pin,
                    onValueChange = {
                        validationError = null
                        if (it.length <= ACCOUNT_PIN_LENGTH) pin = it.filter(Char::isDigit)
                    },
                    label = stringResource(R.string.setup_pin),
                    keyboardType = KeyboardType.NumberPassword,
                    isSecret = true,
                    isEnabled = !isSaving,
                )
                Button(
                    onClick = ::submit,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TextGold),
                ) {
                    Text(
                        text = stringResource(R.string.save_accounts),
                        color = Color.Black,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        }

        if (isSaving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = TextGold)
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
    onUpdate: (ManagedAccountDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val hasCredentialEdits = draft.appKeyInput.isNotBlank() || draft.appSecretInput.isNotBlank()
    val storedTossAccount = storedAccount?.takeIf { Broker.normalize(it.broker) == Broker.TOSS }
    val tossClientId = if (hasCredentialEdits) draft.appKeyInput else storedTossAccount?.appKey.orEmpty()
    val tossClientSecret = if (hasCredentialEdits) draft.appSecretInput else storedTossAccount?.appSecret.orEmpty()
    val hasProxyEdits = draft.proxyBaseUrlInput.isNotBlank() || draft.proxyApiTokenInput.isNotBlank()
    val tossProxyUrl = if (hasProxyEdits) draft.proxyBaseUrlInput else storedTossAccount?.centralServerBaseUrl.orEmpty()
    val tossProxyToken = if (hasProxyEdits) draft.proxyApiTokenInput else storedTossAccount?.centralServerApiToken.orEmpty()
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
                onValueChange = { onUpdate(draft.copy(label = it)) },
                label = stringResource(R.string.account_label),
                isEnabled = isEnabled,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ManagementBrokerSelector(
                broker = draft.broker,
                isEnabled = isEnabled,
                onChange = { broker ->
                    onUpdate(
                        draft.copy(
                            broker = broker,
                            cano = "",
                            acntPrdtCd = if (broker == Broker.KIS) "01" else "",
                            appKeyInput = "",
                            appSecretInput = "",
                            hasStoredKey = false,
                            hasStoredSecret = false,
                            useTossProxy = false,
                            proxyBaseUrlInput = "",
                            proxyApiTokenInput = "",
                            hasStoredProxyConfig = false,
                        ),
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (draft.broker == Broker.TOSS) {
                ManagementTossConnectionSelector(
                    useProxy = draft.useTossProxy,
                    isEnabled = isEnabled,
                    onChange = { useProxy ->
                        onUpdate(
                            draft.copy(
                                useTossProxy = useProxy,
                                cano = "",
                                proxyBaseUrlInput = "",
                                proxyApiTokenInput = "",
                                hasStoredProxyConfig = useProxy && draft.hasStoredProxyConfig,
                            ),
                        )
                    },
                )
                if (draft.useTossProxy) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ManagementField(
                        value = draft.proxyBaseUrlInput,
                        onValueChange = { onUpdate(draft.copy(proxyBaseUrlInput = it, cano = "")) },
                        label = stringResource(R.string.toss_proxy_url),
                        isEnabled = isEnabled,
                        supportingText = if (draft.hasStoredProxyConfig) stringResource(R.string.keep_existing_value) else null,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ManagementField(
                        value = draft.proxyApiTokenInput,
                        onValueChange = { onUpdate(draft.copy(proxyApiTokenInput = it, cano = "")) },
                        label = stringResource(R.string.toss_proxy_token),
                        isSecret = true,
                        isEnabled = isEnabled,
                        supportingText = if (draft.hasStoredProxyConfig) stringResource(R.string.keep_existing_value) else null,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            ManagementField(
                value = draft.appKeyInput,
                onValueChange = {
                    onUpdate(
                        draft.copy(
                            appKeyInput = it,
                            cano = if (draft.broker == Broker.TOSS) "" else draft.cano,
                        ),
                    )
                },
                label = stringResource(if (draft.broker == Broker.TOSS) R.string.client_id else R.string.app_key),
                isSecret = true,
                isEnabled = isEnabled,
                supportingText = if (draft.hasStoredKey) stringResource(R.string.keep_existing_value) else null,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ManagementField(
                value = draft.appSecretInput,
                onValueChange = {
                    onUpdate(
                        draft.copy(
                            appSecretInput = it,
                            cano = if (draft.broker == Broker.TOSS) "" else draft.cano,
                        ),
                    )
                },
                label = stringResource(if (draft.broker == Broker.TOSS) R.string.client_secret else R.string.app_secret),
                isSecret = true,
                isEnabled = isEnabled,
                supportingText = if (draft.hasStoredSecret) stringResource(R.string.keep_existing_value) else null,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (draft.broker == Broker.TOSS) {
                TossAccountPicker(
                    clientId = tossClientId,
                    clientSecret = tossClientSecret,
                    selectedAccountSeq = draft.cano,
                    proxyBaseUrl = if (draft.useTossProxy) tossProxyUrl else "",
                    proxyApiToken = if (draft.useTossProxy) tossProxyToken else "",
                    isEnabled = isEnabled,
                    onAccountSelected = { accountSeq ->
                        onUpdate(draft.copy(cano = accountSeq))
                    },
                )
            } else {
                ManagementField(
                    value = draft.cano,
                    onValueChange = {
                        onUpdate(draft.copy(cano = it.filter(Char::isDigit).take(ACCOUNT_NUMBER_LENGTH)))
                    },
                    label = stringResource(R.string.account_number),
                    keyboardType = KeyboardType.Number,
                    isEnabled = isEnabled,
                )
                Spacer(modifier = Modifier.height(12.dp))
                ManagementField(
                    value = draft.acntPrdtCd,
                    onValueChange = {
                        onUpdate(draft.copy(acntPrdtCd = it.filter(Char::isDigit).take(ACCOUNT_PRODUCT_CODE_LENGTH)))
                    },
                    label = stringResource(R.string.account_product_code),
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
                    onClick = { onChange(value) },
                    enabled = isEnabled,
                    modifier = Modifier.weight(1f),
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
private fun ManagementBrokerSelector(
    broker: String,
    isEnabled: Boolean,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.broker), color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Broker.KIS to R.string.broker_kis, Broker.TOSS to R.string.broker_toss).forEach { (value, label) ->
                OutlinedButton(
                    onClick = { onChange(value) },
                    enabled = isEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (broker == value) TextGold.copy(alpha = 0.15f) else Color.Transparent,
                    ),
                ) {
                    Text(text = stringResource(label), color = if (broker == value) TextGold else TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun ManagementField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isSecret: Boolean = false,
    isEnabled: Boolean = true,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = isEnabled,
        label = { Text(label, color = TextSecondary) },
        supportingText = supportingText?.let { { Text(it, color = TextHint) } },
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
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}
