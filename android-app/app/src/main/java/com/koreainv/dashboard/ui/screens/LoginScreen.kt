package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.ui.theme.LocalDashboardColors
import com.koreainv.dashboard.ui.theme.Surface
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary

/** Static artwork is painted by the root host, including beneath system bars. */
@Composable
fun LoginBackdrop() {
    val colors = LocalDashboardColors.current
    Image(
        painter = painterResource(if (colors.isDark) R.drawable.login_horizon_dark else R.drawable.login_horizon_light),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
    )
}

@Composable
fun PinUnlockScreen(
    errorMessage: String?,
    isLoading: Boolean,
    onUnlock: (String) -> Unit,
) {
    var entry by remember { mutableStateOf(PinEntryState()) }
    var visibleError by remember { mutableStateOf(errorMessage) }
    val latestLoading by rememberUpdatedState(isLoading)
    val latestError by rememberUpdatedState(errorMessage)
    val isBusy = entry.isPending || isLoading
    val colors = LocalDashboardColors.current

    LaunchedEffect(entry.isPending, isLoading, errorMessage) {
        if (entry.isPending && !isLoading) {
            // Give the parent a frame to publish busy, including attempts that
            // finish before the next composition. Submission itself is event-driven.
            withFrameNanos { }
            if (!latestLoading) {
                visibleError = latestError
                entry = entry.finishAttempt()
            }
        } else if (!entry.isPending && entry.value.isEmpty() && errorMessage != null) {
            visibleError = errorMessage
        }
    }

    fun enterDigit(digit: Char) {
        val change = entry.enterDigit(digit, externalBusy = latestLoading)
        if (change.state == entry) return
        entry = change.state // Lock before invoking the parent, so rapid taps cannot resubmit.
        visibleError = null
        change.pinToSubmit?.let { pin ->
            try {
                onUnlock(pin)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                entry = entry.finishAttempt()
                throw cancelled
            } catch (_: Exception) {
                entry = entry.finishAttempt()
                visibleError = "잠금을 해제하지 못했습니다. 다시 시도해 주세요."
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()) {
        val heroHeight = (maxHeight * 0.47f).coerceIn(224.dp, 408.dp)
        val brandTop = (maxHeight * 0.115f).coerceIn(48.dp, 120.dp)
        val keyHeight = (maxHeight * 0.09f).coerceIn(64.dp, 84.dp)
        val pinRowInset = 36.dp + ((maxWidth - 360.dp) / 2f).coerceIn(0.dp, 20.dp)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                Text(
                    text = "KoreaInv",
                    modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = brandTop),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 36.sp, lineHeight = 44.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = pinRowInset).heightIn(min = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.welcome_back),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Row(
                    modifier = Modifier.semantics {
                        contentDescription = "잠금번호 입력"
                        stateDescription = if (isBusy) "잠금 해제 중" else "${entry.value.length}자리 입력됨, 전체 4자리"
                    },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) { index ->
                        val filled = index < entry.value.length
                        Box(Modifier.size(13.dp).clip(CircleShape)
                            .then(if (filled) Modifier.background(colors.primary)
                                else Modifier.border(1.5.dp, colors.textHint.copy(alpha = 0.75f), CircleShape)))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 36.dp).heightIn(min = 36.dp)) {
                val message = if (isBusy) "잠금 해제 중…" else visibleError
                if (!message.isNullOrBlank()) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isBusy) colors.textSecondary else colors.error,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf(null, "0", "backspace"),
                )
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { key ->
                            if (key == null) {
                                Spacer(Modifier.width(88.dp).height(keyHeight))
                            } else {
                                TextButton(
                                    onClick = {
                                        if (key == "backspace") {
                                            entry = entry.backspace(externalBusy = latestLoading)
                                            visibleError = null
                                        } else enterDigit(key.single())
                                    },
                                    enabled = !isBusy && (key != "backspace" || entry.value.isNotEmpty()),
                                    modifier = Modifier.width(88.dp).height(keyHeight),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = colors.textPrimary,
                                        disabledContentColor = colors.textSecondary.copy(alpha = 0.4f),
                                    ),
                                ) {
                                    if (key == "backspace") {
                                        Icon(painterResource(R.drawable.ic_backspace),
                                            contentDescription = "한 자리 지우기", modifier = Modifier.size(26.dp))
                                    } else {
                                        Text(key, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
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
    centered: Boolean = false,
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
            verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (centered) 440.dp else 560.dp)
                    .fillMaxWidth()
                    .then(
                        if (centered) Modifier
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                        else Modifier,
                    ),
                horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Text(
                    text = "KoreaInv",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                )
                Spacer(Modifier.height(20.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start)
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
