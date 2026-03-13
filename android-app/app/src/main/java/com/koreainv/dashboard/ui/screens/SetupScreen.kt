package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.AppCredentials
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
    onSetupSuccess: (AppCredentials) -> Unit,
) {
    val pinLengthError = stringResource(R.string.pin_six_digits)
    val scope = rememberCoroutineScope()
    var appKey by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var cano by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (appKey.isBlank() || appSecret.isBlank() || cano.isBlank()) {
            errorMessage = "필수 정보를 모두 입력하세요."
            return
        }
        if (pin.length != 4 || confirmPin.length != 4) {
            errorMessage = pinLengthError
            return
        }
        if (pin != confirmPin) {
            errorMessage = "PIN 확인값이 일치하지 않습니다."
            return
        }
        scope.launch {
            isLoading = true
            val credentials = settingsManager.saveCredentials(
                SetupInput(
                    appKey = appKey,
                    appSecret = appSecret,
                    cano = cano,
                    acntPrdtCd = "01",
                    pin = pin,
                ),
            )
            isLoading = false
            onSetupSuccess(credentials)
        }
    }

    CredentialShell(
        title = stringResource(R.string.setup_title),
        subtitle = stringResource(R.string.setup_subtitle),
        isLoading = isLoading,
        errorMessage = errorMessage,
    ) {
        SetupField(value = appKey, onValueChange = { appKey = it }, label = stringResource(R.string.app_key))
        Spacer(modifier = Modifier.height(12.dp))
        SetupField(
            value = appSecret,
            onValueChange = { appSecret = it },
            label = stringResource(R.string.app_secret),
            isSecret = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SetupField(
            value = cano,
            onValueChange = { cano = it },
            label = stringResource(R.string.account_number),
            keyboardType = KeyboardType.Number,
        )
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
private fun SetupField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isSecret: Boolean = false,
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
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
