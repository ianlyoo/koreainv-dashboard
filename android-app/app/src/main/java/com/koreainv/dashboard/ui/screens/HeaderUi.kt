package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koreainv.dashboard.ui.theme.Surface
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.SurfaceGlass
import com.koreainv.dashboard.ui.theme.SurfaceGlassLight
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import java.text.NumberFormat
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs

enum class CurrencyDisplayMode {
    KRW,
    USD,
}

@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Surface)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
        content = content
    )
}

@Composable
fun PremiumGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceGlassLight)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(20.dp))
            .padding(20.dp),
        content = content
    )
}

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = TextPrimary,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 16.dp)
    )
}

@Composable
fun PremiumListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    var rowModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(SurfaceGlassLight)
        .border(1.dp, SurfaceBorder, RoundedCornerShape(16.dp))
        
    if (onClick != null) {
        rowModifier = rowModifier.clickable(onClick = onClick)
    }
    
    Row(
        modifier = rowModifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content
    )
}

@Composable
fun InlineTitleWithSync(
    title: String,
    lastSynced: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )
        lastSynced?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "동기화: ${formatRelativeSyncText(it)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
fun CompactCurrencyToggle(
    mode: CurrencyDisplayMode,
    onModeChange: (CurrencyDisplayMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(SurfaceGlass, RoundedCornerShape(20.dp))
            .border(1.dp, SurfaceBorder, RoundedCornerShape(20.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CurrencyPill(
            label = "$",
            selected = mode == CurrencyDisplayMode.USD,
            onClick = { onModeChange(CurrencyDisplayMode.USD) },
        )
        CurrencyPill(
            label = "원",
            selected = mode == CurrencyDisplayMode.KRW,
            onClick = { onModeChange(CurrencyDisplayMode.KRW) },
        )
    }
}

@Composable
private fun CurrencyPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) TextGold.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) TextGold else TextSecondary,
        )
    }
}

fun formatCurrencyAmount(
    amountKrw: Double,
    mode: CurrencyDisplayMode,
    usdRate: Double,
    signed: Boolean = false,
): String {
    val safeRate = usdRate.takeIf { it > 0.0 } ?: 1350.0
    return if (mode == CurrencyDisplayMode.USD) {
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 2
        }
        val usdValue = amountKrw / safeRate
        val prefix = if (signed && usdValue > 0) "+" else if (signed && usdValue < 0) "-" else ""
        val absValue = abs(usdValue)
        "$prefix$${formatter.format(absValue)}"
    } else {
        val formatter = NumberFormat.getNumberInstance(Locale.KOREA).apply {
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
        val prefix = if (signed && amountKrw > 0) "+" else if (signed && amountKrw < 0) "-" else ""
        val absValue = abs(amountKrw)
        "$prefix₩${formatter.format(absValue)}"
    }
}

fun formatSignedPercent(value: Double): String {
    val prefix = if (value > 0) "+" else if (value < 0) "-" else ""
    val formatter = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }
    return "$prefix${formatter.format(abs(value))}%"
}

fun formatWholeNumber(value: Double): String =
    NumberFormat.getNumberInstance(Locale.KOREA).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }.format(abs(value))

fun formatRelativeSyncText(lastSynced: String): String {
    return try {
        val syncedAt = OffsetDateTime.parse(lastSynced)
        val now = OffsetDateTime.now(syncedAt.offset)
        val minutes = Duration.between(syncedAt, now).toMinutes().coerceAtLeast(0)
        when {
            minutes <= 0 -> "방금"
            minutes < 60 -> "${minutes}분전"
            minutes < 1440 -> "${minutes / 60}시간전"
            else -> "${minutes / 1440}일전"
        }
    } catch (_: Exception) {
        lastSynced
    }
}
