package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.TossAccountDiscoveryClient
import com.koreainv.dashboard.network.TossAccountOption
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
internal fun TossAccountPicker(
    clientId: String,
    clientSecret: String,
    selectedAccountSeq: String,
    isEnabled: Boolean = true,
    onAccountSelected: (String) -> Unit,
) {
    var accounts by remember(clientId, clientSecret) { mutableStateOf<List<TossAccountOption>>(emptyList()) }
    var isLoading by remember(clientId, clientSecret) { mutableStateOf(false) }
    var message by remember(clientId, clientSecret) { mutableStateOf("") }
    var expanded by remember(clientId, clientSecret) { mutableStateOf(false) }
    var refreshNonce by remember(clientId, clientSecret) { mutableIntStateOf(0) }
    val safeClientId = clientId.trim()
    val safeClientSecret = clientSecret.trim()

    LaunchedEffect(safeClientId, safeClientSecret, refreshNonce) {
        if (safeClientId.isBlank() || safeClientSecret.isBlank()) {
            accounts = emptyList()
            message = "CLIENT ID와 CLIENT SECRET을 입력하면 계좌를 자동으로 조회합니다."
            return@LaunchedEffect
        }
        if (refreshNonce == 0) delay(700)
        isLoading = true
        message = "토스증권에서 계좌 목록을 조회하고 있습니다."
        runCatching {
            TossAccountDiscoveryClient.fetchAccounts(
                clientId = safeClientId,
                clientSecret = safeClientSecret,
                forceRefresh = refreshNonce > 0,
            )
        }.onSuccess { loaded ->
            accounts = loaded
            val retained = selectedAccountSeq.takeIf { selected ->
                loaded.any { it.accountSeq == selected }
            }.orEmpty()
            val selected = if (loaded.size == 1) loaded.single().accountSeq else retained
            if (selected != selectedAccountSeq) onAccountSelected(selected)
            message = when {
                loaded.isEmpty() -> "사용 가능한 토스증권 계좌가 없습니다."
                loaded.size == 1 -> "${loaded.single().displayName} 계좌가 자동 선택됐습니다."
                selected.isNotBlank() -> "${loaded.first { it.accountSeq == selected }.displayName} 계좌를 사용합니다."
                else -> "사용할 토스 계좌를 선택하세요."
            }
        }.onFailure { error ->
            accounts = emptyList()
            message = error.message ?: "토스 계좌 조회에 실패했습니다."
        }
        isLoading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        ?: if (accounts.size > 1) "계좌를 선택하세요" else "계좌 조회 대기 중",
                    color = if (selected != null) TextGold else TextSecondary,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.displayName) },
                        onClick = {
                            onAccountSelected(account.accountSeq)
                            message = "${account.displayName} 계좌를 사용합니다."
                            expanded = false
                        },
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { refreshNonce += 1 },
            enabled = isEnabled && !isLoading && safeClientId.isNotBlank() && safeClientSecret.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = TextGold, strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (accounts.isEmpty()) "토스 계좌 불러오기" else "토스 계좌 다시 조회",
                    color = TextGold,
                )
            }
        }
        if (message.isNotBlank()) {
            Text(
                text = message,
                color = if (accounts.isEmpty() && !isLoading && safeClientId.isNotBlank()) {
                    Color(0xFFEF4444)
                } else {
                    TextSecondary
                },
            )
        }
    }
}
