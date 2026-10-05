package com.koreainv.dashboard.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.CapitalGainsEstimate
import com.koreainv.dashboard.network.DashboardDataSource
import com.koreainv.dashboard.network.estimateCapitalGainsTax
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@Composable
fun CapitalGainsTaxScreen(repository: DashboardDataSource, onBack: () -> Unit) {
    val currentYear = remember { LocalDate.now(ZoneOffset.ofHours(9)).year }
    var year by rememberSaveable { mutableIntStateOf(currentYear) }
    var expanded by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    // Key results by year/repository: a previous year's amount never appears under a new year.
    var result by remember(repository, year) { mutableStateOf<CapitalGainsEstimate?>(null) }
    var error by remember(repository, year) { mutableStateOf<String?>(null) }
    var loading by remember(repository, year) { mutableStateOf(true) }
    BackHandler(onBack = onBack)
    LaunchedEffect(repository, year, retry) {
        loading = true
        error = null
        try {
            result = estimateCapitalGainsTax(year, repository.fetchCapitalGainsHistory(year))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = dashboardErrorMessage(failure)
        } finally {
            loading = false
        }
    }
    DashboardScaffold(topBar = {
        DashboardTopBar(title = "양도소득세 계산", lastSynced = null, navigationButton = {
            HeaderIconButton(Icons.Default.ArrowBack, "뒤로", onClick = onBack)
        })
    }) { padding ->
        ScreenBackground {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp, bottom = dashboardBottomContentPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Box {
                        DashboardInlineButton(label = "${year}년", onClick = { expanded = true },
                            trailingIcon = Icons.Default.ArrowDropDown, compact = true)
                        ScreenFilterMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            (currentYear downTo 2000).forEach { option ->
                                DropdownMenuItem(text = { Text("${option}년", color = TextPrimary) }, onClick = {
                                    year = option
                                    expanded = false
                                })
                            }
                        }
                    }
                }
                item {
                    Text("한국투자 · 토스 전체 계좌 합산 · 결제일 기준", color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                    Text("외국법인 상장주식 · 국내 거주 5년 이상 가정", color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                    if (year == currentYear) Text("올해 누적 · 연말까지의 예상 거래는 포함하지 않음", color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (loading) item { DashboardLoadingState(message = "연간 거래를 불러오는 중입니다…") }
                else if (error != null) item { DashboardErrorNotice(message = error.orEmpty(), onRetry = { retry++ }) }
                else result?.let { estimate ->
                    item {
                        HeroTopSection {
                            Text("예상 세액${if (estimate.incomplete) " · 추정" else ""}", color = TextSecondary)
                            Text(taxWon(estimate.estimatedTaxKrw), style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("추정 ${estimate.estimatedCount - estimate.missingCount}건 포함 · 미산출 ${estimate.missingCount}건", color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall)
                            Text("소득세 20% + 지방소득세 2%", color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (estimate.incomplete) item {
                        Text(if (estimate.missingCount > 0 || estimate.accountErrors.isNotEmpty())
                            "추정 · 자료 부족 거래는 합계·세액에 미반영. 표시 금액은 확인 가능한 거래의 부분 합계이며 최종 세액이 아닙니다."
                        else "추정 자료가 포함된 참고 계산입니다.", color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (estimate.accountErrors.isNotEmpty()) item {
                        DashboardErrorNotice(message = "일부 계좌 조회 미완료 · 합산 결과 불완전", onRetry = { retry++ })
                    }
                    item { TaxMetricRow("양도차익 합계", taxWon(estimate.netGainKrw), "해외주식 손익통산 · 환차손익 포함") }
                    item { TaxMetricRow("기본공제", taxWon(estimate.basicDeductionKrw), "1인당 연 250만원 · 두 증권사 합산 1회") }
                    item { TaxMetricRow("과세표준", taxWon(estimate.taxableBaseKrw), "양도차익 합계 − 기본공제 · 최소 0원") }
                    item { TaxMetricRow("신고기한", "${year + 1}년 5월", estimate.filingPeriod) }
                    item { TaxMetricRow("해외 매도 거래", "${estimate.totalOverseasCount - estimate.missingCount}/${estimate.totalOverseasCount}건",
                        "산출 가능(추정 포함)/전체 · 추정 ${estimate.estimatedCount}건 · 미산출 ${estimate.missingCount}건") }
                    item { TaxMetricRow("국내 상장주식", "비과세", "소액주주 장내거래 가정 · ${estimate.domesticCount}건 · 대주주 계산 제외") }
                    item {
                        Text("참고용 추정이며, 실제 신고는 증권사 신고대행 또는 홈택스 기준.", color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(estimate.rows) { row ->
                        PremiumListItem {
                            LedgerRowContent(name = row.trade.name, identity = "${row.trade.ticker} · ${row.trade.accountLabel}",
                                detail = row.reason, amount = if (row.exempt) "비과세" else taxWon(row.gainKrw),
                                secondary = row.trade.capitalGainsBasis?.proceeds?.settlementDate?.let { "결제 $it" }
                                    ?: "추정 · 결제일 미확인",
                                secondaryColor = when (row.gainKrw?.signum()) { 1 -> Success; -1 -> Error; else -> TextSecondary })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaxMetricRow(label: String, value: String, explanation: String) {
    PremiumListItem {
        LedgerRowContent(label, "", explanation, value, null, TextSecondary)
    }
}

private fun taxWon(value: BigDecimal?): String = value?.let {
    "₩${NumberFormat.getIntegerInstance(Locale.KOREA).format(it)}"
} ?: "미산출"
