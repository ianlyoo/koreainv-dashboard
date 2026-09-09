package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.insight.*

internal val insightSectionLabels = linkedMapOf("overview" to "개요", "financial" to "재무", "analyst" to "의견", "options" to "옵션", "insider" to "내부자", "news" to "뉴스")
@Composable internal fun InsightRows(rows:List<Pair<String,String>>) { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { rows.forEach { (label,value) -> ResponsiveDetailRow(label,value) } } }
@Composable internal fun InsightHeading(title:String) { Column(Modifier.padding(top=24.dp,bottom=12.dp)) { HorizontalDivider(); Spacer(Modifier.height(24.dp)); Text(title,style=MaterialTheme.typography.titleLarge) } }
@Composable internal fun InsightSectionNotice(s:InsightSnapshot,key:String,onRetry:()->Unit) {
    val info=s.sections[key]
    if(info!=null && info.status!=InsightSectionStatus.AVAILABLE) {
        Text(info.message ?: when(info.status) { InsightSectionStatus.UNSUPPORTED->"지원하지 않는 종목/시장입니다."; InsightSectionStatus.RATE_LIMITED->"요청이 많아 잠시 기다려 주세요."; InsightSectionStatus.ERROR->"이 항목을 불러오지 못했습니다."; else->"아직 제공된 데이터가 없습니다." })
        if(info.status==InsightSectionStatus.ERROR) TextButton(onClick=onRetry,modifier=Modifier.heightIn(min=48.dp)) { Text("다시 시도") }
    }
}
internal data class InsightListItem(val key: String, val section: String, val content: @Composable () -> Unit)

/** Keys and section ownership are created once alongside the actual flattened rows. */
private class InsightItemBuilder {
    val items = mutableListOf<InsightListItem>()
    private var section = "overview"
    fun item(key: String, content: @Composable () -> Unit) {
        if (key in insightSectionLabels) section = key
        items += InsightListItem(key, section, content)
    }
}

internal fun insightSectionItems(s:InsightSnapshot,expanded:Set<String>,toggle:(String)->Unit,onRetry:()->Unit): List<InsightListItem> = InsightItemBuilder().apply {
    item("overview") {
        InsightHeading("개요")
        InsightSectionNotice(s, "key_metrics", onRetry)
        val metrics = remember(s) { insightCoreMetrics(s) }
        InsightCoreGrid(metrics)
        InsightDisclosureRow("핵심 지표 정확한 값", "metrics" in expanded, { toggle("metrics") })
        InsightDisclosureRow("가격 범위·거래 현황", "header" in expanded, { toggle("header") })
    }
    if ("metrics" in expanded) item("metrics_exact") {
        val metrics = remember(s) { insightCoreMetrics(s) }
        InsightRows(metrics.map { it.label to listOf(it.exactValue, it.detail).filter(String::isNotBlank).joinToString(" · ") })
    }
    if ("header" in expanded) item("header_detail") {
        InsightSectionNotice(s, "header", onRetry)
        val rows = remember(s.header) { insightHeaderDetails(s.header) }
        InsightHeaderRanges(s.header)
        InsightRows(rows)
    }
    item("financial") {
        InsightHeading("재무")
        InsightSectionNotice(s,"revenue",onRetry)
        s.revenue?.let { r ->
            Text("분기 매출 · USD",style=MaterialTheme.typography.titleMedium)
            InsightRevenueChart(r)
            InsightDisclosureRow("전체 ${r.quarters.size}개 분기 정확한 값", "financial" in expanded, { toggle("financial") })
        }
    }
    if ("financial" in expanded) s.revenue?.quarters?.forEachIndexed { i, q ->
        item("quarter_$i") {
            InsightRows(listOf("회계 분기" to insightText(q.label), "매출" to insightNumber(q.revenue, " USD"), "전년 대비" to insightQuarterChange(q)))
            Spacer(Modifier.height(20.dp))
        }
    }
    item("analyst") {
        InsightHeading("애널리스트")
        InsightSectionNotice(s,"analyst",onRetry)
        s.analyst?.let { a ->
            InsightRows(listOf("종합 의견" to insightText(a.label),"참여" to insightNumber(a.analystCount,"명")))
            InsightBarsChart(remember(a) { listOf("매수" to a.dist?.buy,"보유" to a.dist?.hold,"매도" to a.dist?.sell) })
            InsightTargetChart(a.target,s.header?.price)
            InsightRows(listOf("목표가 최저" to insightNumber(a.target?.low," USD"),"평균 목표가" to insightNumber(a.target?.mean," USD"),"목표가 최고" to insightNumber(a.target?.high," USD"),"현재가 (세로 표시)" to insightNumber(s.header?.price," USD"),"상승 여력" to insightSigned(a.upsidePct,"%")))
            InsightDisclosureRow("전체 ${a.recent.size}건 평가 보기", "analyst" in expanded, { toggle("analyst") })
        }
    }
    if ("analyst" in expanded) s.analyst?.recent?.forEachIndexed { i, a ->
        item("analyst_$i") {
            val rows = remember(a) { buildList {
                add("기관" to insightText(a.firmKo ?: a.firm))
                a.firm?.takeIf { it != a.firmKo && a.firmKo != null }?.let { add("기관명 (원문)" to it) }
                a.at?.let { add("평가일" to it) }
                val currentRating = a.rating?.let(::insightProductLabel)
                val previousRating = a.prevRating?.let(::insightProductLabel)
                if (currentRating != null || previousRating != null) add((if (currentRating == null) "이전 의견" else "의견") to listOfNotNull(previousRating, currentRating).joinToString(" → "))
                if (a.target != null || a.prevTarget != null) add((if (a.target == null) "이전 목표가" else "목표가") to listOfNotNull(a.prevTarget?.let { insightNumber(it) }, a.target?.let { insightNumber(it) }).joinToString(" → ").plus(" USD"))
                a.action?.let { add("평가 변경" to insightProductLabel(it)) }
                a.upsidePct?.let { add("상승 여력" to insightSigned(it, "%")) }
                a.isNew?.let { add("평가 구분" to if (it) "신규" else "기존") }
            } }
            InsightRows(rows)
            Spacer(Modifier.height(24.dp))
        }
    }
    item("options") {
        InsightHeading("옵션")
        InsightSectionNotice(s,"options",onRetry)
        s.options?.let { o ->
            if(o.optionable==false) Text("옵션을 지원하지 않는 종목입니다.")
            InsightRows(listOf("거래량 · 전체 만기" to insightNumber(o.volume,"계약"),"거래량 PCR (풋/콜)" to insightNumber(o.putCallRatioVolume),"미결제약정 PCR (풋/콜)" to insightNumber(o.putCallRatioOpenInterest)))
            Spacer(Modifier.height(16.dp))
            InsightShareChart("거래량 구성",o.volumeShare); Spacer(Modifier.height(12.dp)); InsightShareChart("미결제약정 구성",o.openInterestShare); Spacer(Modifier.height(12.dp)); InsightShareChart("프리미엄 구성",o.premiumShare)
            Spacer(Modifier.height(20.dp)); Text("동일 시각 평균 대비 · 기준선 100%",style=MaterialTheme.typography.titleSmall)
            val values=remember(o) { listOf("d3" to "3일","d7" to "7일","d30" to "30일").map { (key,label) -> label to o.optionVolumeVsAvg?.windows?.get(key)?.let { if(it.available) it.ratioPct else null } } }
            InsightBarsChart(values,100.0)
            InsightRows(listOf("현재 누적 거래량" to insightNumber(o.optionVolumeVsAvg?.currentCumVolume,"계약")))
            listOf("d3" to "3일","d7" to "7일","d30" to "30일").forEach { (key,label) -> ResponsiveDetailRow("$label 비교 누적 거래량",insightNumber(o.optionVolumeVsAvg?.windows?.get(key)?.takeIf{it.available}?.baselineCumVolume,"계약")) }
            Spacer(Modifier.height(20.dp))
            InsightRows(listOf("가장 가까운 만기" to insightText(o.nearestExpiry),"만기까지" to insightNumber(o.daysToExpiry,"일"),"Max Pain" to insightNumber(o.maxPain," USD"),"기준 주가" to insightNumber(o.referencePrice," USD"),"Call Wall" to insightNumber(o.callWall," USD"),"Put Wall" to insightNumber(o.putWall," USD"),"Gamma Flip" to insightNumber(o.gammaFlip," USD"),"Net GEX" to insightNumber(o.netGammaExposure," USD"),"기초자산 1% 변화당 감마" to insightNumber(o.gammaPer1Pct," USD / 1%")))
        }
    }
    item("insider") {
        InsightHeading("내부자")
        InsightSectionNotice(s,"insider",onRetry)
        s.insider?.let { i ->
            InsightRows(listOf("집계 기간" to insightNumber(i.window,"일"),"매수" to insightNumber(i.buyCount,"건"),"매도" to insightNumber(i.sellCount,"건"),"순금액 · 공급자 집계" to insightSigned(i.netValue," USD"),"설명" to insightText(i.label)))
            InsightDisclosureRow("전체 ${i.recent.size}건 최근 거래 보기", "insider" in expanded, { toggle("insider") })
        }
    }
    if("insider" in expanded) s.insider?.recent?.forEachIndexed { i,r -> item("insider_$i") { InsightRows(listOf("이름" to insightText(r.name),"직책" to insightText(r.title),"거래일" to insightText(r.transactionDate),"거래 코드" to insightText(r.transactionCode),"금액" to insightSigned(r.value," USD"))); Spacer(Modifier.height(24.dp)) } }
    item("news") { InsightHeading("뉴스"); InsightSectionNotice(s,"news",onRetry); if(s.news?.items.isNullOrEmpty()) Text("제공된 뉴스가 없습니다.") }
    s.news?.items?.forEachIndexed { i,n -> item("news_$i") {
        val uri=LocalUriHandler.current
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(n.title,style=MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(n.publisher, n.publishedAt).joinToString(" · "),style=MaterialTheme.typography.bodyMedium)
            var failed by remember(n.link) { mutableStateOf(false) }
            if(insightSafeLink(n.link)) TextButton(onClick={failed=runCatching { uri.openUri(n.link) }.isFailure},modifier=Modifier.heightIn(min=48.dp)) { Text("원문 열기 ↗") }
            if(failed) Text("원문을 열 수 없습니다.")
            Spacer(Modifier.height(16.dp))
        }
    } }
    item("ledger") {
        Column(Modifier.padding(top = 24.dp)) {
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            InsightDisclosureRow("데이터 기준 시점", "ledger" in expanded, { toggle("ledger") }, prominent = true)
        }
    }
    if ("ledger" in expanded) insightLedgerGroups(s).forEach { group ->
        item("ledger_group_${group.key}") {
            Text(group.title, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
        }
        group.rows.forEach { row ->
            item("ledger_${group.key}_${row.key}") { InsightLedgerField(row) }
        }
    }
}.items
