package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.koreainv.dashboard.ui.theme.Info as InfoColor
import com.koreainv.dashboard.ui.theme.InfoSurface
import com.koreainv.dashboard.ui.theme.SurfacePrimary
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import java.io.IOException
import java.io.InterruptedIOException

/** Show actionable copy, never broker payloads, account identifiers or credentials. */
internal fun dashboardErrorMessage(error: Throwable): String {
    val detail = error.message.orEmpty()
    return when {
        error is InterruptedIOException -> "응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."
        error is IOException -> "인터넷 연결을 확인한 뒤 다시 시도해 주세요."
        detail.contains("TOKEN", ignoreCase = true) || detail.contains("EGW0012") ->
            "계좌 인증을 확인하지 못했습니다. 다시 시도한 뒤 계속되면 계좌 설정을 확인해 주세요."
        detail.contains("EGW00201") || detail.contains("code=429") ->
            "조회 요청이 잠시 많아졌습니다. 잠시 후 다시 시도해 주세요."
        else -> "정보를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."
    }
}

@Composable
fun DashboardErrorNotice(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    usingCachedData: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(18.dp),
        color = InfoSurface,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = InfoColor, modifier = Modifier.size(20.dp))
                Text(
                    if (usingCachedData) "새로고침하지 못했습니다" else "정보를 불러오지 못했습니다",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            if (usingCachedData) {
                Text("마지막으로 불러온 정보를 표시하고 있습니다.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            OutlinedButton(onClick = onRetry) { Text("다시 시도") }
        }
    }
}

@Composable
fun DashboardEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = SurfacePrimary) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() })
            Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun DashboardLoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = InfoColor, strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
fun ResponsiveDetailRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale > 1.2f) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(value, color = valueColor, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Text(label, Modifier.weight(1f), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(value, Modifier.weight(1.4f), color = valueColor, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
            }
        }
    }
}
