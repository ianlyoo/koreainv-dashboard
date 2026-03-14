package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.BackgroundRaised
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.Info
import com.koreainv.dashboard.ui.theme.InfoSurface
import com.koreainv.dashboard.ui.theme.NegativeSurface
import com.koreainv.dashboard.ui.theme.PositiveSurface
import com.koreainv.dashboard.ui.theme.Surface
import com.koreainv.dashboard.ui.theme.SurfaceAccent
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.SurfaceBorderPrimary
import com.koreainv.dashboard.ui.theme.SurfaceElevated
import com.koreainv.dashboard.ui.theme.SurfaceGlass
import com.koreainv.dashboard.ui.theme.SurfaceGlassLight
import com.koreainv.dashboard.ui.theme.SurfacePrimary
import com.koreainv.dashboard.ui.theme.Success
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

enum class AccentTone {
    Neutral,
    Accent,
    Positive,
    Negative,
    Info,
}

data class DashboardTabItem(
    val route: String,
    val label: String,
)

@Composable
fun ScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, BackgroundRaised),
                ),
            ),
        content = content,
    )
}

@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SurfaceElevated.copy(alpha = 0.94f),
                        Surface.copy(alpha = 0.9f),
                    ),
                ),
            )
            .border(1.dp, SurfaceBorderPrimary, RoundedCornerShape(30.dp))
            .padding(24.dp),
    ) {
        GlassCornerSheen(
            topRightAlpha = 0.12f,
            bottomLeftAlpha = 0.05f,
        )
        content()
    }
}

@Composable
fun PremiumGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SurfaceGlassLight.copy(alpha = 0.9f),
                        SurfaceGlass.copy(alpha = 0.82f),
                    ),
                ),
            )
            .border(1.dp, SurfaceBorder, RoundedCornerShape(28.dp))
            .padding(20.dp),
    ) {
        GlassCornerSheen(
            topRightAlpha = 0.1f,
            bottomLeftAlpha = 0.045f,
        )
        content()
    }
}

@Composable
fun HeroTopSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(34.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(SurfacePrimary, SurfaceElevated),
                ),
            )
            .border(1.dp, SurfaceBorderPrimary, RoundedCornerShape(34.dp))
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        GlassCornerSheen(
            topRightAlpha = 0.13f,
            bottomLeftAlpha = 0.06f,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

@Composable
private fun GlassCornerSheen(
    topRightAlpha: Float,
    bottomLeftAlpha: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = topRightAlpha),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.92f, size.height * 0.1f),
                radius = size.minDimension * 0.55f,
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(size.width * 0.92f, size.height * 0.1f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = bottomLeftAlpha),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.08f, size.height * 0.92f),
                radius = size.minDimension * 0.48f,
            ),
            radius = size.minDimension * 0.48f,
            center = Offset(size.width * 0.08f, size.height * 0.92f),
        )
    }
}

@Composable
fun HeroMetricGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SurfaceBorder.copy(alpha = 0.85f)),
        )
        content()
    }
}

@Composable
fun HeroHeadlineValue(
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val metricSpec = heroHeadlineTextSpec(value, maxWidth)
        Text(
            text = value,
            style = metricSpec.style,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = metricSpec.maxLines,
            softWrap = metricSpec.softWrap,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
fun HeroMetricRow(
    primaryLabel: String,
    primaryValue: String,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    secondaryValue: String? = null,
    primaryValueColor: Color = TextPrimary,
    secondaryValueColor: Color = TextPrimary,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        HeroMetricCell(
            label = primaryLabel,
            value = primaryValue,
            valueColor = primaryValueColor,
            modifier = Modifier.weight(1f),
        )
        if (secondaryLabel != null && secondaryValue != null) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(42.dp)
                    .background(SurfaceBorder.copy(alpha = 0.85f)),
            )
            HeroMetricCell(
                label = secondaryLabel,
                value = secondaryValue,
                valueColor = secondaryValueColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun DashboardTopBar(
    title: String,
    lastSynced: String?,
    modifier: Modifier = Modifier,
    navigationButton: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        navigationButton?.invoke()
        InlineTitleWithSync(
            title = title,
            lastSynced = lastSynced,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
fun HeaderIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tone: AccentTone = AccentTone.Neutral,
) {
    val palette = tonePalette(tone)
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        palette.container.copy(alpha = 0.94f),
                        SurfaceGlass.copy(alpha = 0.74f),
                    ),
                ),
            )
            .border(1.dp, palette.border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = palette.content,
        )
    }
}

@Composable
fun HeaderLoadingIndicator() {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(SurfaceElevated, SurfacePrimary),
                ),
            )
            .border(1.dp, SurfaceBorder, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = TextGold,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = TextPrimary,
        modifier = modifier,
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    titleContent: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        titleContent?.invoke() ?: SectionTitle(title = title)
        action?.invoke()
    }
}

@Composable
fun PremiumListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var rowModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(26.dp))
        .background(SurfaceGlassLight)
        .border(1.dp, SurfaceBorder, RoundedCornerShape(26.dp))

    if (onClick != null) {
        rowModifier = rowModifier.clickable(onClick = onClick)
    }

    Box(modifier = rowModifier) {
        GlassCornerSheen(
            topRightAlpha = 0.09f,
            bottomLeftAlpha = 0.04f,
        )
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            content = content,
        )
    }
}

@Composable
fun InlineTitleWithSync(
    title: String,
    lastSynced: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        lastSynced?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "동기화 ${formatRelativeSyncText(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        SurfaceGlassLight.copy(alpha = 0.92f),
                        SurfaceGlass.copy(alpha = 0.82f),
                    ),
                ),
            )
            .border(1.dp, SurfaceBorder, RoundedCornerShape(22.dp))
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
fun DashboardPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: AccentTone = AccentTone.Neutral,
    trailingIcon: ImageVector? = null,
) {
    val palette = tonePalette(tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        palette.container.copy(alpha = 0.92f),
                        SurfaceGlass.copy(alpha = 0.76f),
                    ),
                ),
            )
            .border(1.dp, palette.border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = palette.content,
        )
        trailingIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = palette.content,
            )
        }
    }
}

@Composable
fun DashboardUtilityMenu(
    onCheckUpdates: () -> Unit,
    onLogout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        HeaderIconButton(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.menu),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceGlassLight)
                .border(1.dp, SurfaceBorder, RoundedCornerShape(24.dp)),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.check_for_updates), color = TextPrimary) },
                colors = MenuDefaults.itemColors(textColor = TextPrimary),
                onClick = {
                    expanded = false
                    onCheckUpdates()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.logout), color = TextPrimary) },
                colors = MenuDefaults.itemColors(textColor = TextPrimary),
                onClick = {
                    expanded = false
                    onLogout()
                },
            )
        }
    }
}

@Composable
fun DashboardBottomTabBar(
    items: List<DashboardTabItem>,
    currentRoute: String?,
    onTabSelected: (DashboardTabItem) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(SurfaceGlassLight.copy(alpha = 0.74f))
                .border(1.dp, SurfaceBorder.copy(alpha = 0.74f), RoundedCornerShape(30.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (selected) SurfaceAccent else SurfaceGlassLight.copy(alpha = 0.56f))
                        .border(
                            width = 1.dp,
                            color = if (selected) SurfaceBorderPrimary else SurfaceBorder.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(22.dp),
                        )
                        .clickable { onTabSelected(item) }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) TextGold else TextPrimary.copy(alpha = 0.86f),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun SurfaceBadge(
    label: String,
    modifier: Modifier = Modifier,
    tone: AccentTone = AccentTone.Neutral,
) {
    val palette = tonePalette(tone)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.container)
            .border(1.dp, palette.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.content,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
    }
}

@Composable
fun MetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceAccent)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HeroMetricCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val metricSpec = heroMetricValueTextSpec(value, maxWidth)
            Text(
                text = value,
                style = metricSpec.style,
                color = valueColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = metricSpec.maxLines,
                softWrap = metricSpec.softWrap,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun heroMetricValueTextSpec(value: String, maxWidth: androidx.compose.ui.unit.Dp): MetricTextSpec {
    val density = LocalDensity.current
    val styles = listOf(
        MaterialTheme.typography.titleLarge,
        MaterialTheme.typography.titleMedium,
        MaterialTheme.typography.titleSmall,
        MaterialTheme.typography.bodyLarge,
        MaterialTheme.typography.bodyMedium,
        MaterialTheme.typography.bodySmall,
    )
    val availablePx = with(density) { maxWidth.toPx() }
    val chosen = styles.firstOrNull { style ->
        val fontPx = with(density) { style.fontSize.toPx() }
        (value.length * fontPx * 0.62f) <= availablePx
    }
    return if (chosen != null) {
        MetricTextSpec(style = chosen, maxLines = 1, softWrap = false)
    } else {
        MetricTextSpec(
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            softWrap = true,
        )
    }
}

private data class MetricTextSpec(
    val style: TextStyle,
    val maxLines: Int,
    val softWrap: Boolean,
)

@Composable
private fun heroHeadlineTextSpec(value: String, maxWidth: androidx.compose.ui.unit.Dp): MetricTextSpec {
    val density = LocalDensity.current
    val styles = listOf(
        MaterialTheme.typography.displayLarge,
        MaterialTheme.typography.displayMedium,
        MaterialTheme.typography.displaySmall,
        MaterialTheme.typography.titleLarge,
        MaterialTheme.typography.titleMedium,
    )
    val availablePx = with(density) { maxWidth.toPx() }
    val chosen = styles.firstOrNull { style ->
        val fontPx = with(density) { style.fontSize.toPx() }
        (value.length * fontPx * 0.6f) <= availablePx
    }
    return if (chosen != null) {
        MetricTextSpec(style = chosen, maxLines = 1, softWrap = false)
    } else {
        MetricTextSpec(style = MaterialTheme.typography.titleSmall, maxLines = 2, softWrap = true)
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
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) SurfaceAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) TextPrimary else TextSecondary,
        )
    }
}

private data class TonePalette(
    val container: Color,
    val content: Color,
    val border: Color,
)

private fun tonePalette(tone: AccentTone): TonePalette = when (tone) {
    AccentTone.Neutral -> TonePalette(SurfacePrimary, TextPrimary, SurfaceBorder)
    AccentTone.Accent -> TonePalette(SurfaceAccent, TextGold, SurfaceBorderPrimary)
    AccentTone.Positive -> TonePalette(PositiveSurface.copy(alpha = 0.96f), Success, Success.copy(alpha = 0.42f))
    AccentTone.Negative -> TonePalette(NegativeSurface.copy(alpha = 0.96f), Error, Error.copy(alpha = 0.42f))
    AccentTone.Info -> TonePalette(InfoSurface, Info, SurfaceBorder)
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
            minutes < 60 -> "${minutes}분 전"
            minutes < 1440 -> "${minutes / 60}시간 전"
            else -> "${minutes / 1440}일 전"
        }
    } catch (_: Exception) {
        lastSynced
    }
}
