package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.insight.*
import com.koreainv.dashboard.ui.theme.LocalDashboardColors
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class InsightLedgerValue(val key: String, val label: String, val value: String, val original: String)
internal data class InsightLedgerGroup(val key: String, val title: String, val rows: List<InsightLedgerValue>)

/** Preserve the supplied timezone and precision; a date-only value stays date-only. */
internal fun insightTimestamp(value: String): String {
    if (value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
        return runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) }.getOrDefault(value)
    }
    val date = runCatching {
        if (value.matches(Regex("\\d{13}"))) Instant.ofEpochMilli(value.toLong()).atOffset(ZoneOffset.UTC)
        else OffsetDateTime.parse(value)
    }.getOrNull() ?: return value
    val fraction = if (date.nano == 0) "" else "." + date.nano.toString().padStart(9, '0').trimEnd('0')
    val zone = if (date.offset == ZoneOffset.UTC) "UTC" else "UTC${date.offset}"
    return date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd\nHH:mm:ss")) + fraction + " $zone"
}

private class LedgerRows(private val info: InsightSectionInfo? = null) {
    val values = mutableListOf<InsightLedgerValue>()
    fun add(key: String, label: String, value: String?, timestamp: Boolean = false) {
        if (!value.isNullOrBlank()) values += InsightLedgerValue(key, label, if (timestamp) insightTimestamp(value) else value, value)
    }
    fun source() {
        info ?: return
        add("source", "공급원", info.source)
        add("asof", "응답 기준", info.asOf, timestamp = true)
        add("status", "상태", when (info.status) {
            InsightSectionStatus.AVAILABLE -> "제공됨"
            InsightSectionStatus.EMPTY -> "데이터 없음"
            InsightSectionStatus.ERROR -> "조회 실패"
            InsightSectionStatus.UNSUPPORTED -> "미지원"
            InsightSectionStatus.RATE_LIMITED -> "요청 대기"
        })
        info.provisional?.let { add("provisional", "집계", if (it) "잠정" else "확정") }
    }
    fun asOf(key: String, label: String, value: String?) {
        if (value != info?.asOf) add(key, label, value, timestamp = true)
    }
}

internal fun insightLedgerGroups(s: InsightSnapshot): List<InsightLedgerGroup> = buildList {
    fun group(key: String, title: String, body: LedgerRows.() -> Unit = {}) {
        val rows = LedgerRows(s.sections[key]).apply { source(); body() }.values
        if (rows.isNotEmpty()) add(InsightLedgerGroup(key, title, rows))
    }
    if (s.fetchedAtMillis > 0) group("retrieval", "조회") {
        add("fetched", "조회 시각", Instant.ofEpochMilli(s.fetchedAtMillis).toString(), timestamp = true)
    }
    group("header", "현재가") {
        asOf("quote", "시세 기준", s.header?.asOf)
        add("extended", "시간외 기준", s.header?.extendedHours?.asOf, timestamp = true)
    }
    group("key_metrics", "핵심 지표") {
        asOf("metrics", "지표 기준", s.keyMetrics?.asOf)
        add("short_date", "공매도 기준일", s.keyMetrics?.shortAsOf, timestamp = true)
        add("short_basis", "공매도 산정", s.keyMetrics?.shortBasis?.let(::insightProductLabel))
    }
    group("revenue", "재무") { s.revenue?.source?.takeIf { it != s.sections["revenue"]?.source }?.let { add("revenue_source", "매출 공급원", it) } }
    group("analyst", "애널리스트") {
        s.analyst?.let { a ->
            a.provider?.takeIf { it != s.sections["analyst"]?.source }?.let { add("provider", "의견 공급원", it) }
            asOf("analyst_date", "평가 기준", a.asOf)
            a.recent.forEachIndexed { i, r -> add("previous_$i", "평가 ${i + 1} 이전 출처", r.prevSource?.let(::insightProductLabel)) }
        }
    }
    group("options", "옵션") {
        s.options?.let { o ->
            asOf("current", "현재 집계", o.asOf)
            add("snapshot_date", "스냅샷 기준일", o.snapshotDate, timestamp = true)
            add("snapshot_updated", "스냅샷 갱신", o.snapshotUpdatedAt, timestamp = true)
            o.snapshotIsPriorDay?.let { add("snapshot_prior", "스냅샷 구분", if (it) "전일" else "당일") }
            add("batch_date", "배치 기준일", o.batchDate, timestamp = true)
            add("batch_updated", "배치 갱신", o.batchUpdatedAt, timestamp = true)
            o.batchIsPriorDay?.let { add("batch_prior", "배치 구분", if (it) "전일" else "당일") }
            o.batchIsProvisional?.let { add("batch_provisional", "배치 집계", if (it) "잠정 집계" else "확정 집계") }
            o.optionVolumeVsAvg?.let { a ->
                a.asOfMinute?.let { add("comparison_minute", "동일 시각 비교", "${insightNumber(it)}분") }
                a.roundSeq?.let { add("comparison_round", "비교 회차", insightNumber(it)) }
            }
        }
    }
    group("insider", "내부자") { asOf("insider_date", "집계 기준", s.insider?.asOf) }
    group("news", "뉴스")
    group("bars", "일봉") {
        if (s.bars.isNotEmpty()) add("interpretation", "날짜 해석", "공급자 UTC 날짜 · 수정주가·거래소 날짜·진행 중 봉 여부 미확인")
    }
    val known = setOf("retrieval", "header", "key_metrics", "revenue", "analyst", "options", "insider", "news", "bars")
    s.sections.keys.filterNot { it in known }.sorted().forEach { group(it, it) }
}

@Composable
internal fun InsightLedgerField(row: InsightLedgerValue) {
    val colors = LocalDashboardColors.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp).clearAndSetSemantics {
        contentDescription = "${row.label}, ${row.original}"
    }) {
        if (maxWidth < 300.dp || fontScale > 1.2f) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.label, color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(row.value, color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(row.label, Modifier.weight(1f), color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(row.value, Modifier.weight(1.8f), color = colors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
        }
    }
}
