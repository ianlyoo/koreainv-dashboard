package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.koreainv.dashboard.network.AccountProfile
import com.koreainv.dashboard.network.Broker
import com.koreainv.dashboard.network.SettingsManager
import com.koreainv.dashboard.network.SetupInput
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private data class AccountDraft(
    val broker: String = Broker.KIS,
    val label: String = "",
    val appKey: String = "",
    val appSecret: String = "",
    val cano: String = "",
    val acntPrdtCd: String = "01",
)

private const val SETUP_ACCOUNT_NUMBER_LENGTH = 8
private const val SETUP_PRODUCT_CODE_LENGTH = 2

@Composable
fun SetupScreen(
    settingsManager: SettingsManager,
    onSetupSuccess: (AccountProfile) -> Unit,
) {
    val pinLengthError = stringResource(R.string.pin_six_digits)
    val accountsRequiredError = stringResource(R.string.setup_accounts_required)
    val pinMismatchError = stringResource(R.string.pin_mismatch)
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(listOf(AccountDraft())) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (accounts.any {
                it.appKey.isBlank() || it.appSecret.isBlank() || it.cano.isBlank() ||
                    (it.broker == Broker.KIS && it.acntPrdtCd.isBlank())
            }
        ) {
            errorMessage = accountsRequiredError
            return
        }
        if (pin.length != 4 || confirmPin.length != 4) {
            errorMessage = pinLengthError
            return
        }
        if (pin != confirmPin) {
            errorMessage = pinMismatchError
            return
        }
        scope.launch {
            isLoading = true
            val profile = settingsManager.saveProfile(
                inputs = accounts.map { account ->
                    SetupInput(
                        appKey = account.appKey,
                        appSecret = account.appSecret,
                        cano = account.cano,
                        acntPrdtCd = account.acntPrdtCd,
                        pin = pin,
                        label = account.label,
                        broker = account.broker,
                    )
                },
                pin = pin,
            )
            isLoading = false
            onSetupSuccess(profile)
        }
    }

    CredentialShell(
        title = stringResource(R.string.setup_title),
        subtitle = stringResource(R.string.setup_subtitle),
        isLoading = isLoading,
        errorMessage = errorMessage,
    ) {
        accounts.forEachIndexed { index, account ->
            AccountSection(
                index = index,
                account = account,
                isPrimary = account.broker == Broker.KIS && accounts.take(index).none { it.broker == Broker.KIS },
                isRemovable = accounts.size > 1,
                onUpdate = { updated ->
                    accounts = accounts.mapIndexed { i, current -> if (i == index) updated else current }
                },
                onRemove = {
                    accounts = accounts.filterIndexed { i, _ -> i != index }
                },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        OutlinedButton(
            onClick = { accounts = accounts + AccountDraft() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.add_account), color = TextGold)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Divider(color = SurfaceBorder)
        Spacer(modifier = Modifier.height(12.dp))
        SetupField(
            value = pin,
            onValueChange = { if (it.length <= 4) pin = it.filter(Char::isDigit) },
            label = stringResource(R.string.setup_pin),
            keyboardType = KeyboardType.NumberPassword,
            isSecret = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SetupField(
            value = confirmPin,
            onValueChange = { if (it.length <= 4) confirmPin = it.filter(Char::isDigit) },
            label = stringResource(R.string.confirm_pin),
            keyboardType = KeyboardType.NumberPassword,
            isSecret = true,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = ::submit,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TextGold),
        ) {
            Text(text = stringResource(R.string.complete_setup), color = Color.Black, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AccountSection(
    index: Int,
    account: AccountDraft,
    isPrimary: Boolean,
    isRemovable: Boolean,
    onUpdate: (AccountDraft) -> Unit,
    onRemove: () -> Unit,
) {
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
                    TextButton(onClick = onRemove) {
                        Text(text = stringResource(R.string.remove_account), color = TextSecondary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = SurfaceBorder)
            Spacer(modifier = Modifier.height(14.dp))
            SetupField(
                value = account.label,
                onValueChange = { onUpdate(account.copy(label = it)) },
                label = stringResource(R.string.account_label),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BrokerSelector(
                broker = account.broker,
                onChange = { broker ->
                    onUpdate(
                        account.copy(
                            broker = broker,
                            cano = "",
                            acntPrdtCd = if (broker == Broker.KIS) "01" else "",
                        ),
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            SetupField(
                value = account.appKey,
                onValueChange = {
                    onUpdate(
                        account.copy(
                            appKey = it,
                            cano = if (account.broker == Broker.TOSS) "" else account.cano,
                        ),
                    )
                },
                label = stringResource(if (account.broker == Broker.TOSS) R.string.client_id else R.string.app_key),
            )
            Spacer(modifier = Modifier.height(12.dp))
            SetupField(
                value = account.appSecret,
                onValueChange = {
                    onUpdate(
                        account.copy(
                            appSecret = it,
                            cano = if (account.broker == Broker.TOSS) "" else account.cano,
                        ),
                    )
                },
                label = stringResource(if (account.broker == Broker.TOSS) R.string.client_secret else R.string.app_secret),
                isSecret = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (account.broker == Broker.TOSS) {
                TossAccountPicker(
                    clientId = account.appKey,
                    clientSecret = account.appSecret,
                    selectedAccountSeq = account.cano,
                    onAccountSelected = { accountSeq ->
                        onUpdate(account.copy(cano = accountSeq))
                    },
                )
            } else {
                SetupField(
                    value = account.cano,
                    onValueChange = {
                        onUpdate(account.copy(cano = it.filter(Char::isDigit).take(SETUP_ACCOUNT_NUMBER_LENGTH)))
                    },
                    label = stringResource(R.string.account_number),
                    keyboardType = KeyboardType.Number,
                )
                Spacer(modifier = Modifier.height(12.dp))
                SetupField(
                    value = account.acntPrdtCd,
                    onValueChange = {
                        onUpdate(account.copy(acntPrdtCd = it.filter(Char::isDigit).take(SETUP_PRODUCT_CODE_LENGTH)))
                    },
                    label = stringResource(R.string.account_product_code),
                    keyboardType = KeyboardType.Number,
                )
            }
        }
    }
}

@Composable
private fun BrokerSelector(broker: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.broker), color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Broker.KIS to R.string.broker_kis, Broker.TOSS to R.string.broker_toss).forEach { (value, label) ->
                OutlinedButton(
                    onClick = { onChange(value) },
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
private fun SetupField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isSecret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
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
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}
