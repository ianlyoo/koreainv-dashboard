package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.ui.theme.Surface
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary

@Composable
fun PinUnlockScreen(
    errorMessage: String?,
    isLoading: Boolean,
    onUnlock: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submissionError by remember { mutableStateOf<String?>(null) }
    val submissionGuard = remember { FormSubmissionGuard() }
    val isBusy = isLoading || submitting
    val latestExternalBusy by rememberUpdatedState(isLoading)
    LaunchedEffect(submitting, isLoading, errorMessage) {
        if (!isLoading) {
            // Allow the parent callback to publish busy, including operations completing in one frame.
            if (submitting) withFrameNanos { }
            if (latestExternalBusy) return@LaunchedEffect
            submissionGuard.finish()
            submitting = false
        }
    }

    CredentialShell(
        title = stringResource(R.string.welcome_back),
        subtitle = stringResource(R.string.enter_pin_prompt),
        isLoading = isBusy,
        errorMessage = submissionError ?: errorMessage?.let { "잠금을 해제하지 못했습니다. PIN을 확인한 뒤 다시 시도하세요." },
    ) {
        Text(
            text = stringResource(R.string.enter_pin),
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            letterSpacing = 2.sp,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.semantics { stateDescription = "잠금번호 ${pin.length}자리 입력됨, 전체 4자리" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { index ->
                val isFilled = index < pin.length
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isFilled) TextGold else Color.White.copy(alpha = 0.2f)),
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("DEL", "0", "OK"),
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { key ->
                    NumpadButton(
                        text = key,
                        onClick = {
                            if (!isBusy && !submissionGuard.isPending) {
                                submissionError = null
                                when (key) {
                                    "DEL" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                    "OK" -> if (isAccountPinValid(pin) && submissionGuard.begin(isLoading)) {
                                        submitting = true
                                        try {
                                            onUnlock(pin)
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                            submissionGuard.finish()
                                            submitting = false
                                            throw cancelled
                                        } catch (error: Exception) {
                                            submissionGuard.finish()
                                            submitting = false
                                            submissionError = accountFormErrorMessage(error)
                                        }
                                    }
                                    else -> if (pin.length < ACCOUNT_PIN_LENGTH) pin += key
                                }
                            }
                        },
                        enabled = !isBusy && when (key) {
                            "OK" -> isAccountPinValid(pin)
                            "DEL" -> pin.isNotEmpty()
                            else -> pin.length < ACCOUNT_PIN_LENGTH
                        },
                        isAction = key == "DEL" || key == "OK",
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun CredentialShell(
    title: String,
    subtitle: String,
    isLoading: Boolean,
    errorMessage: String?,
    loadingMessage: String = "잠금을 해제하고 있습니다.",
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(stringResource(R.string.korea_inv_dashboard), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                }
                Spacer(Modifier.height(20.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
                if (errorMessage != null) {
                    Spacer(Modifier.height(12.dp))
                    AccountFormErrorText(errorMessage)
                }
            }
        }
        if (isLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Surface(shape = RoundedCornerShape(20.dp), color = Surface,
                    modifier = Modifier.padding(24.dp).widthIn(max = 400.dp)) {
                    DashboardLoadingState(loadingMessage)
                }
            }
        }
    }
}

@Composable
internal fun AccountFormErrorText(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun AccountRemovalUndo(isEnabled: Boolean, onUndo: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("계좌를 목록에서 제거했습니다. 저장하기 전까지 되돌릴 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        TextButton(onClick = onUndo, enabled = isEnabled) { Text("계좌 제거 되돌리기") }
    }
}

@Composable
internal fun AccountDiscardDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("변경사항을 버릴까요?") },
        text = { Text("저장하지 않은 계좌 정보와 입력 내용이 사라집니다.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("버리고 나가기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("계속 수정") } },
    )
}

@Composable
fun NumpadButton(text: String, onClick: () -> Unit, isAction: Boolean = false, enabled: Boolean = true) {
    val buttonText = when (text) {
        "DEL" -> stringResource(R.string.delete)
        "OK" -> stringResource(R.string.ok)
        else -> text
    }

    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (isAction) TextGold else Color.White,
        ),
    ) {
        Text(
            text = buttonText,
            fontSize = if (isAction) 16.sp else 24.sp,
            fontWeight = if (isAction) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
