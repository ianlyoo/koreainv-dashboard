package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.insight.*
import java.math.BigDecimal
import java.time.LocalDate
import java.net.URI

internal fun insightNumber(value: Double?, unit: String = ""): String = value?.takeIf { it.isFinite() }
    ?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() + unit } ?: "제공 안 됨"
internal fun insightText(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "제공 안 됨"
internal fun insightSigned(value: Double?, unit: String = ""): String = if (value == null) "제공 안 됨" else (if (value > 0) "+" else "") + insightNumber(value, unit)
internal fun insightSafeLink(link: String): Boolean = runCatching { URI(link).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
internal fun insightBars(bars: List<PriceBar>, months: Int): List<PriceBar> {
    val end = bars.lastOrNull() ?: return emptyList()
    val cutoff = runCatching { LocalDate.parse(end.date).minusMonths(months.toLong()).toString() }.getOrNull() ?: return bars
    return bars.filter { it.date >= cutoff }
}
/** Compact quote presentation; exact values remain in chart/quote semantics and disclosures. */
internal fun insightPrice(value: Double?): String {
    if (value == null || !value.isFinite()) return "제공 안 됨"
    val digits = if (kotlin.math.abs(value) > 0 && kotlin.math.abs(value) < 1) 4 else 2
    return java.text.NumberFormat.getNumberInstance(java.util.Locale.US).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
        roundingMode = java.math.RoundingMode.HALF_UP
    }.format(value)
}
internal fun insightVolume(value: Double?): String {
    if (value == null || !value.isFinite()) return "제공 안 됨"
    return java.text.NumberFormat.getNumberInstance(java.util.Locale.US).apply {
        maximumFractionDigits = 0
        roundingMode = java.math.RoundingMode.HALF_UP
    }.format(value)
}

internal fun insightCompactMoney(value: Double?): String {
    if (value == null || !value.isFinite()) return "제공 안 됨"
    val divisor = when {
        kotlin.math.abs(value) >= 1e12 -> 1e12 to "T"
        kotlin.math.abs(value) >= 1e9 -> 1e9 to "B"
        kotlin.math.abs(value) >= 1e6 -> 1e6 to "M"
        else -> return insightNumber(value)
    }
    return BigDecimal.valueOf(value / divisor.first).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + divisor.second
}

internal fun insightDirection(value: String?): String? = when(value?.lowercase()) {
    null, "", "flat", "neutral" -> null
    "up", "increase", "positive" -> "증가"
    "down", "decrease", "negative" -> "감소"
    else -> value
}
internal fun insightProductLabel(value: String): String = when(value.lowercase()) {
    "open" -> "정규장"
    "closed" -> "장 마감"
    "pre", "premarket" -> "프리마켓"
    "post", "afterhours" -> "애프터마켓"
    "prevclose" -> "전일 종가 대비"
    "maintained" -> "의견 유지"
    "reiterated" -> "의견 재확인"
    "upgraded", "upgrade" -> "상향 조정"
    "downgraded", "downgrade" -> "하향 조정"
    "initiated", "initiate" -> "신규 분석"
    "resumed" -> "분석 재개"
    "buy" -> "매수"
    "hold" -> "보유"
    "sell" -> "매도"
    "strong buy" -> "적극 매수"
    "strong sell" -> "적극 매도"
    "outperform" -> "시장수익률 상회"
    "underperform" -> "시장수익률 하회"
    "neutral" -> "중립"
    "vendor" -> "제공사"
    "float" -> "유통주식 기준"
    else -> value
}
internal fun insightQuarterChange(q: InsightQuarter): String = q.replaceText?.takeIf { it.isNotBlank() }
    ?: listOfNotNull(insightSigned(q.yoy, "%"), insightDirection(q.direction)?.takeIf { q.yoy != null }).joinToString(" · ")

internal fun insightLedger(s: InsightSnapshot): List<Pair<String,String>> = buildList {
    if (s.fetchedAtMillis > 0) add("조회" to java.time.Instant.ofEpochMilli(s.fetchedAtMillis).toString())
    val names = mapOf("header" to "현재가", "key_metrics" to "핵심 지표", "revenue" to "재무", "analyst" to "애널리스트", "options" to "옵션", "insider" to "내부자", "news" to "뉴스", "bars" to "일봉")
    s.sections.forEach { (key, info) ->
        val status = when(info.status) {
            InsightSectionStatus.AVAILABLE -> "제공됨"
            InsightSectionStatus.EMPTY -> "데이터 없음"
            InsightSectionStatus.ERROR -> "조회 실패"
            InsightSectionStatus.UNSUPPORTED -> "미지원"
            InsightSectionStatus.RATE_LIMITED -> "요청 대기"
        }
        add((names[key] ?: key) to listOfNotNull(info.source, info.asOf, status, "잠정".takeIf { info.provisional == true }).joinToString(" · "))
    }
    s.header?.let { h ->
        h.asOf?.let { add("시세 기준" to it) }
        h.extendedHours?.asOf?.let { add("시간외 기준" to it) }
    }
    s.keyMetrics?.let { k ->
        k.asOf?.let { add("지표 기준" to it) }
        val short = listOfNotNull(k.shortAsOf, k.shortBasis?.let(::insightProductLabel))
        if (short.isNotEmpty()) add("공매도 기준" to short.joinToString(" · "))
    }
    s.revenue?.source?.let { add("매출 공급원" to it) }
    s.analyst?.let { a ->
        val source = listOfNotNull(a.provider, a.asOf)
        if (source.isNotEmpty()) add("의견 공급원 / 기준" to source.joinToString(" · "))
        a.recent.forEachIndexed { i, r -> r.prevSource?.let { add("평가 ${i + 1} 이전 출처" to insightProductLabel(it)) } }
    }
    s.insider?.asOf?.let { add("내부자 기준" to it) }
    s.options?.let { o ->
        o.asOf?.let { add("옵션 현재" to it) }
        val snapshot = listOfNotNull(o.snapshotDate, o.snapshotUpdatedAt?.let { "갱신 $it" }, o.snapshotIsPriorDay?.let { if(it) "전일" else "당일" })
        if (snapshot.isNotEmpty()) add("옵션 스냅샷" to snapshot.joinToString(" · "))
        val batch = listOfNotNull(o.batchDate, o.batchUpdatedAt?.let { "갱신 $it" }, o.batchIsPriorDay?.let { if(it) "전일" else "당일" }, o.batchIsProvisional?.let { if(it) "잠정 집계" else "확정 집계" })
        if (batch.isNotEmpty()) add("옵션 배치" to batch.joinToString(" · "))
        o.optionVolumeVsAvg?.let { a ->
            val comparison = listOfNotNull(a.asOfMinute?.let { "분 ${insightNumber(it)}" }, a.roundSeq?.let { "회차 ${insightNumber(it)}" })
            if (comparison.isNotEmpty()) add("동일 시각 비교" to comparison.joinToString(" · "))
        }
    }
    if (s.bars.isNotEmpty()) add("일봉 해석" to "공급자 UTC 날짜 · 수정주가·거래소 날짜·진행 중 봉 여부 미확인")
}
