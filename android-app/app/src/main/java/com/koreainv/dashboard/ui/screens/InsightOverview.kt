package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.insight.*
import com.koreainv.dashboard.ui.theme.LocalDashboardColors

internal data class InsightCoreMetric(
    val label: String,
    val metric: InsightMetric?,
    val unit: String,
    val comparisonLabel: String? = null,
    val comparisonUnit: String = "",
    val context: String? = null,
) {
    val exactValue: String get() = if (metric?.value != null) insightNumber(metric.value, unit)
        else metric?.replaceText?.takeIf { it.isNotBlank() } ?: "제공 안 됨"
    val comparison: String? get() = metric?.compare?.let { "$comparisonLabel ${insightNumber(it, comparisonUnit)}" }
    val detail: String get() = listOfNotNull(comparison,
        metric?.replaceText?.takeIf { metric.value != null && it.isNotBlank() },
        metric?.sectorPercentile?.let { "업종 $it" }, context,
        insightDirection(metric?.direction)?.takeIf { metric?.compare != null }).joinToString(" · ")
}

internal fun insightCoreMetrics(s: InsightSnapshot): List<InsightCoreMetric> {
    val k = s.keyMetrics
    return listOf(
        InsightCoreMetric("시가총액", InsightMetric(value = s.header?.marketCap), " USD"),
        InsightCoreMetric("PER", k?.per, "배", "업종 평균", "배"),
        InsightCoreMetric("EPS", k?.eps, " USD", "전년 대비", "%"),
        InsightCoreMetric(if (k?.periodLabel?.contains("TTM", true) == true) "최근 12개월 매출" else "분기 매출", k?.revenueTtm, " USD", "전년 대비", "%", k?.periodLabel),
        InsightCoreMetric("배당수익률", k?.dividendYield, "%", "분기 배당", " USD"),
        InsightCoreMetric("ROE", k?.roe, "%", "제공자 비교", "%"),
        InsightCoreMetric("공매도 비중", k?.shortInterestPct, "%", "2주 변화", "%p"),
        InsightCoreMetric("상환 소요일", k?.daysToCover, "일", "2주 변화", "일"),
    )
}

@Composable
internal fun InsightCoreGrid(metrics: List<InsightCoreMetric>) {
    val colors = LocalDashboardColors.current
    val columns = if (LocalDensity.current.fontScale > 1.2f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        metrics.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { metric ->
                    val direction = metric.metric?.direction
                    val color = when (direction?.lowercase()) {
                        "up", "increase", "positive" -> colors.success
                        "down", "decrease", "negative" -> colors.error
                        else -> colors.textSecondary
                    }
                    Column(Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = "${metric.label}, ${metric.exactValue}, ${metric.detail}"
                    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(metric.label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                        val compact = if (metric.metric?.value == null) metric.exactValue
                            else if (metric.unit == " USD") insightCompactMoney(metric.metric.value) else metric.exactValue
                        Text(compact, style = MaterialTheme.typography.titleLarge)
                        if (metric.unit == " USD" && metric.metric?.value != null) {
                            Text("USD", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        }
                        if (metric.detail.isNotBlank()) Text(metric.detail, style = MaterialTheme.typography.bodyMedium, color = color)
                    }
                }
            }
        }
    }
}

internal fun insightHeaderDetails(h: InsightHeader?): List<Pair<String, String>> = buildList {
    h ?: return@buildList
    h.marketStatus?.let { add("시장" to insightProductLabel(it)) }
    h.changeBasis?.let { add("등락 기준" to insightProductLabel(it)) }
    h.turnover?.let { add("거래대금" to insightNumber(it, " USD")) }
    h.marketCapRank?.let { add("시가총액 순위" to insightNumber(it, "위")) }
    h.turnoverRank?.let { add("거래대금 순위" to insightNumber(it, "위")) }
    h.extendedHours?.let { e ->
        e.price?.let { add("시간외 가격" to insightNumber(it, " USD")) }
        e.changePercent?.let { add("시간외 등락" to insightSigned(it, "%")) }
        e.session?.let { add("시간외 거래" to insightProductLabel(it)) }
    }
}

@Composable
internal fun InsightHeaderRanges(h: InsightHeader?) {
    h?.dayRange?.let { InsightRangeMarker("당일 범위", it, h.price) }
    h?.week52Range?.let { InsightRangeMarker("52주 범위", it, h.price) }
}

@Composable
private fun InsightRangeMarker(label: String, range: InsightRange, price: Double?) {
    val colors = LocalDashboardColors.current
    val current = range.current ?: price
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ResponsiveDetailRow(label, "${insightNumber(range.low)} ~ ${insightNumber(range.high)} USD")
        val low = range.low
        val high = range.high
        if (low != null && high != null && current != null && high > low) {
            Canvas(Modifier.fillMaxWidth().height(18.dp).semantics {
                contentDescription = "$label 내 현재가 ${insightNumber(current)} USD"
            }) {
                drawLine(colors.surfaceBorderPrimary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx())
                val x = ((current - low) / (high - low)).coerceIn(0.0, 1.0).toFloat() * size.width
                drawCircle(colors.primary, 5.dp.toPx(), Offset(x, size.height / 2))
            }
        }
    }
}
