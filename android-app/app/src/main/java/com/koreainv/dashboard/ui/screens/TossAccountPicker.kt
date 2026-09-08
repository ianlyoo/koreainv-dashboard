package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.TossAccountDiscoveryClient
import com.koreainv.dashboard.network.TossAccountOption
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
internal fun TossAccountPicker(
    lookupInput: TossLookupInput?,
    useProxy: Boolean,
    selectedAccountSeq: String,
    isEnabled: Boolean = true,
    validationMessage: String? = null,
    modifier: Modifier = Modifier,
    onAccountSelected: (String) -> Unit,
) {
    val requestInput = lookupInput.takeIf { isEnabled }
    val latestInput by rememberUpdatedState(requestInput)
    val latestSelection by rememberUpdatedState(selectedAccountSeq)
    val latestOnSelected by rememberUpdatedState(onAccountSelected)
    val gate = remember { FormRequestGate() }
    var accounts by remember(requestInput) { mutableStateOf<List<TossAccountOption>>(emptyList()) }
    var isLoading by remember(requestInput) { mutableStateOf(false) }
    var message by remember(requestInput) { mutableStateOf("") }
    var hasError by remember(requestInput) { mutableStateOf(false) }
    var expanded by remember(requestInput) { mutableStateOf(false) }
    var refreshNonce by remember(requestInput) { mutableIntStateOf(0) }

    DisposableEffect(requestInput) {
        onDispose { gate.invalidate() }
    }
    LaunchedEffect(requestInput, refreshNonce, useProxy) {
        val ticket = gate.invalidate()
        val input = requestInput
        if (input == null) {
            message = if (useProxy) "연결 ID와 비밀키, 개인 서버 주소와 인증 토큰을 입력해 주세요."
                else "연결 ID와 비밀키를 입력하면 계좌 목록을 불러옵니다."
            return@LaunchedEffect
        }
        isLoading = true
        hasError = false
        expanded = false
        message = "토스증권에서 계좌 목록을 조회하고 있습니다."
        try {
            runLatestFormRequest(
                gate = gate,
                ticket = ticket,
                load = {
                    if (refreshNonce == 0) delay(700)
                    TossAccountDiscoveryClient.fetchAccounts(
                        clientId = input.clientId,
                        clientSecret = input.clientSecret,
                        forceRefresh = refreshNonce > 0,
                        proxyBaseUrl = input.proxyBaseUrl,
                        proxyApiToken = input.proxyApiToken,
                    )
                },
                onSuccess = { loaded ->
                    if (latestInput == input) {
                        accounts = loaded
                        val selected = retainedTossSelection(loaded.map { it.accountSeq }, latestSelection)
                        if (selected != latestSelection) latestOnSelected(selected)
                        message = when {
                            loaded.isEmpty() -> "사용 가능한 토스증권 계좌가 없습니다."
                            loaded.size == 1 -> "${loaded.single().displayName} 계좌가 자동 선택됐습니다."
                            selected.isNotBlank() -> "${loaded.first { it.accountSeq == selected }.displayName} 계좌를 사용합니다."
                            else -> "사용할 토스 계좌를 선택하세요."
                        }
                    }
                },
                onFailure = { error ->
                    if (latestInput == input) {
                        accounts = emptyList()
                        hasError = true
                        message = tossAccountLookupErrorMessage(error)
                    }
                },
            )
        } finally {
            if (gate.isCurrent(ticket)) isLoading = false
        }
    }

    Column(modifier.focusable(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "토스 계좌", color = TextSecondary)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = isEnabled && !isLoading && accounts.size > 1,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val selected = accounts.firstOrNull { it.accountSeq == selectedAccountSeq }
                Text(
                    text = selected?.displayName
                        ?: when {
                            isLoading -> "계좌 목록 불러오는 중"
                            hasError -> "계좌 목록을 불러오지 못했습니다"
                            accounts.size > 1 -> "계좌를 선택하세요"
                            requestInput == null -> "연결 정보 입력 후 선택"
                            else -> "선택할 계좌가 없습니다"
                        },
                    color = if (selected != null) TextGold else TextSecondary,
                )
            }
            ScreenFilterMenu(
                expanded = expanded && isEnabled && !isLoading,
                onDismissRequest = { expanded = false },
            ) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.displayName) },
                        onClick = {
                            if (isEnabled && !isLoading) latestOnSelected(account.accountSeq)
                            message = "${account.displayName} 계좌를 사용합니다."
                            expanded = false
                        },
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { refreshNonce += 1 },
            enabled = requestInput != null && !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TextGold, strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (hasError) "계좌 조회 다시 시도" else if (accounts.isEmpty()) "토스 계좌 불러오기" else "토스 계좌 다시 조회",
                    color = TextGold,
                )
            }
        }
        if (message.isNotBlank()) {
            Text(
                text = message,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (hasError) {
                    MaterialTheme.colorScheme.error
                } else {
                    TextSecondary
                },
            )
        }
        if (validationMessage != null) AccountFormErrorText(validationMessage)
    }
}
