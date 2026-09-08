package com.koreainv.dashboard.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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
    val icon: ImageVector? = null,
)

internal val LocalDashboardBottomBarHeight = compositionLocalOf<MutableState<Dp>?> { null }

@Composable
fun dashboardBottomContentPadding(): Dp = (LocalDashboardBottomBarHeight.current?.value ?: 116.dp) + 16.dp

@Composable
fun ScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
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
            .liquidGlass(radius = 30.dp)
            .padding(24.dp),
    ) {
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
            .liquidGlass()
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
fun HeroTopSection(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = bottomPadding),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

@Composable
fun HeroMetricGroup(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 14.dp,
    showBottomDivider: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalPadding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SurfaceBorder.copy(alpha = 0.5f)),
        )
        content()
        if (showBottomDivider) {
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(SurfaceBorder.copy(alpha = 0.5f)),
            )
        }
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
    syncValueSizing: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stackMetrics = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.2f
        val sharedSpec = if (!stackMetrics && syncValueSizing && secondaryLabel != null && secondaryValue != null) {
            heroMetricSharedTextSpec(primaryValue, secondaryValue, maxWidth)
        } else {
            null
        }

        if (stackMetrics) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroMetricCell(primaryLabel, primaryValue, primaryValueColor)
                if (secondaryLabel != null && secondaryValue != null) {
                    HeroMetricCell(secondaryLabel, secondaryValue, secondaryValueColor)
                }
            }
        } else Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            HeroMetricCell(
                label = primaryLabel,
                value = primaryValue,
                valueColor = primaryValueColor,
                modifier = Modifier.weight(1f),
                forcedSpec = sharedSpec,
            )
            if (secondaryLabel != null && secondaryValue != null) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(42.dp)
                        .background(SurfaceBorder.copy(alpha = 0.5f)),
                )
                HeroMetricCell(
                    label = secondaryLabel,
                    value = secondaryValue,
                    valueColor = secondaryValueColor,
                    modifier = Modifier.weight(1f),
                    forcedSpec = sharedSpec,
                )
            }
        }
    }
}

@Composable
fun DashboardTopBar(
    title: String,
    lastSynced: String?,
    modifier: Modifier = Modifier,
    navigationButton: @Composable (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (actions != null && (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.2f)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    navigationButton?.invoke()
                    InlineTitleWithSync(title, lastSynced, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                navigationButton?.invoke()
                InlineTitleWithSync(title, lastSynced, Modifier.weight(1f))
                if (actions != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
    }
}

@Composable
fun HeaderIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tone: AccentTone = AccentTone.Neutral,
    enabled: Boolean = true,
) {
    val palette = tonePalette(tone)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .glassPressFeedback(interactionSource)
            .liquidGlass(radius = 24.dp, role = GlassRole.Control)
            .clip(RoundedCornerShape(24.dp))
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current,
                enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) palette.content else TextSecondary,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
fun HeaderLoadingIndicator() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .liquidGlass(radius = 24.dp, role = GlassRole.Control),
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
        modifier = modifier.semantics { heading() },
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
        Box(Modifier.weight(1f).padding(end = 12.dp)) {
            titleContent?.invoke() ?: SectionTitle(title = title)
        }
        action?.invoke()
    }
}

@Composable
fun PremiumListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dividerColor = SurfaceBorder.copy(alpha = 0.5f)
    var rowModifier = modifier
        .fillMaxWidth()
        .drawBehind {
            drawLine(dividerColor, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx())
        }

    if (onClick != null) {
        rowModifier = rowModifier.clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
    }

    Box(modifier = rowModifier) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
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
            modifier = Modifier.semantics { heading() },
        )
        lastSynced?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "동기화 ${formatRelativeSyncText(it)}",
                style = MaterialTheme.typography.labelSmall,
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
            .selectableGroup()
            .liquidGlass(radius = 28.dp, role = GlassRole.Control)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CurrencyPill(
            label = "$",
            description = "미국 달러로 표시",
            selected = mode == CurrencyDisplayMode.USD,
            onClick = { onModeChange(CurrencyDisplayMode.USD) },
        )
        CurrencyPill(
            label = "원",
            description = "원화로 표시",
            selected = mode == CurrencyDisplayMode.KRW,
            onClick = { onModeChange(CurrencyDisplayMode.KRW) },
        )
    }
}

@Composable
fun DashboardInlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: AccentTone = AccentTone.Neutral,
    trailingIcon: ImageVector? = null,
    compact: Boolean = false,
) {
    val palette = tonePalette(tone)
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.SpaceBetween else Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            modifier = if (compact) Modifier.weight(1f) else Modifier,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = palette.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailingIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = if (compact) Modifier.size(20.dp) else Modifier,
                tint = palette.content,
            )
        }
    }
}

@Composable
fun DashboardSettingsButton(onClick: () -> Unit) {
    HeaderIconButton(Icons.Outlined.Settings, "설정", onClick)
}

@Composable
internal fun ScreenFilterMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        // An opaque menu remains legible without another live backdrop recording.
        modifier = Modifier.liquidGlass(radius = 24.dp, role = GlassRole.Control,
            tint = SurfacePrimary.copy(alpha = 1f)),
        content = content,
    )
}

@Composable
fun DashboardBottomTabBar(
    items: List<DashboardTabItem>,
    currentRoute: String?,
    onTabSelected: (DashboardTabItem) -> Unit,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val colors = com.koreainv.dashboard.ui.theme.LocalDashboardColors.current
    val selectionFill = Brush.verticalGradient(listOf(
        Color.White.copy(alpha = if (colors.isDark) 0.16f else 0.8f),
        Color.White.copy(alpha = if (colors.isDark) 0.05f else 0.25f),
    ))
    val measuredHeight = LocalDashboardBottomBarHeight.current
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { size -> measuredHeight?.value = with(density) { size.height.toDp() } }
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .liquidGlass(radius = 34.dp, role = GlassRole.Navigation)
                .padding(5.dp),
        ) {
            val tabWidth = maxWidth / items.size
            val selectedIndex = items.indexOfFirst { it.route == currentRoute }
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selectedIndex.coerceAtLeast(0),
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                label = "selected tab position",
            )
            if (selectedIndex >= 0) {
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.offset { IntOffset(with(density) { indicatorOffset.roundToPx() }, 0) }.width(tabWidth).fillMaxHeight()
                        .clip(RoundedCornerShape(28.dp))
                        .background(selectionFill)
                        .border(0.5.dp, Color.White.copy(alpha = if (colors.isDark) 0.14f else 0.6f), RoundedCornerShape(28.dp)))
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                items.forEach { item ->
                    val selected = currentRoute == item.route
                    val interactionSource = remember { MutableInteractionSource() }
                    val foreground by animateColorAsState(
                        if (selected) colors.primary else TextSecondary,
                        label = "tab foreground",
                    )
                    Column(
                        Modifier.weight(1f)
                            .glassPressFeedback(interactionSource)
                            .clip(RoundedCornerShape(28.dp))
                            .selectable(selected = selected, role = Role.Tab,
                                interactionSource = interactionSource, indication = LocalIndication.current,
                                onClick = { onTabSelected(item) })
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 2.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    ) {
                        item.icon?.let { Icon(it, null, Modifier.size(22.dp), tint = foreground) }
                        Text(item.label, style = MaterialTheme.typography.labelSmall,
                            color = foreground, textAlign = TextAlign.Center,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                    }
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    forcedSpec: MetricTextSpec? = null,
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
            val metricSpec = forcedSpec ?: heroMetricValueTextSpec(value, maxWidth)
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
    val measurer = rememberTextMeasurer()
    val styles = listOf(
        MaterialTheme.typography.titleLarge,
        MaterialTheme.typography.titleMedium,
        MaterialTheme.typography.titleSmall,
        MaterialTheme.typography.bodyLarge,
        MaterialTheme.typography.bodyMedium,
    )
    val availablePx = with(density) { maxWidth.toPx() }
    val chosen = styles.firstOrNull { style ->
        measurer.measure(value, style.copy(fontWeight = FontWeight.SemiBold), softWrap = false).size.width <= availablePx
    }
    return if (chosen != null) {
        MetricTextSpec(style = chosen, maxLines = 1, softWrap = false)
    } else {
        MetricTextSpec(
            style = MaterialTheme.typography.bodyMedium,
            maxLines = Int.MAX_VALUE,
            softWrap = true,
        )
    }
}

@Composable
private fun heroMetricSharedTextSpec(
    primaryValue: String,
    secondaryValue: String,
    rowWidth: androidx.compose.ui.unit.Dp,
): MetricTextSpec {
    val cellWidth = (rowWidth - 24.dp) / 2
    val primarySpec = heroMetricValueTextSpec(primaryValue, cellWidth)
    val secondarySpec = heroMetricValueTextSpec(secondaryValue, cellWidth)
    val orderedStyles = listOf(
        MaterialTheme.typography.titleLarge,
        MaterialTheme.typography.titleMedium,
        MaterialTheme.typography.titleSmall,
        MaterialTheme.typography.bodyLarge,
        MaterialTheme.typography.bodyMedium,
        MaterialTheme.typography.bodySmall,
        MaterialTheme.typography.labelLarge,
    )
    fun indexOf(style: TextStyle): Int = orderedStyles.indexOfFirst {
        it.fontSize == style.fontSize && it.lineHeight == style.lineHeight
    }.let { if (it == -1) orderedStyles.lastIndex else it }
    return if (indexOf(primarySpec.style) >= indexOf(secondarySpec.style)) primarySpec else secondarySpec
}

private data class MetricTextSpec(
    val style: TextStyle,
    val maxLines: Int,
    val softWrap: Boolean,
)

@Composable
private fun heroHeadlineTextSpec(value: String, maxWidth: androidx.compose.ui.unit.Dp): MetricTextSpec {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val styles = listOf(
        MaterialTheme.typography.displayLarge,
        MaterialTheme.typography.displayMedium,
        MaterialTheme.typography.displaySmall,
        MaterialTheme.typography.titleLarge,
        MaterialTheme.typography.titleMedium,
    )
    val availablePx = with(density) { maxWidth.toPx() }
    val chosen = styles.firstOrNull { style ->
        measurer.measure(value, style.copy(fontWeight = FontWeight.Bold), softWrap = false).size.width <= availablePx
    }
    return if (chosen != null) {
        MetricTextSpec(style = chosen, maxLines = 1, softWrap = false)
    } else {
        MetricTextSpec(style = MaterialTheme.typography.titleMedium, maxLines = Int.MAX_VALUE, softWrap = true)
    }
}

@Composable
private fun CurrencyPill(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) TextPrimary.copy(alpha = 0.10f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 8.dp),
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

@Composable
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
        val prefix = if (usdValue < 0) "-" else if (signed && usdValue > 0) "+" else ""
        val absValue = abs(usdValue)
        "$prefix$${formatter.format(absValue)}"
    } else {
        val formatter = NumberFormat.getNumberInstance(Locale.KOREA).apply {
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
        val prefix = if (amountKrw < 0) "-" else if (signed && amountKrw > 0) "+" else ""
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
