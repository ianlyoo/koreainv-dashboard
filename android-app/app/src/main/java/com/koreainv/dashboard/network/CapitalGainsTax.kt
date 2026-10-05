package com.koreainv.dashboard.network

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Legal tax uses this payment's own settlement-date reference FX.
 * Estimated adapters may supply a labelled trade-date/reference-rate approximation.
 * JPY rates must be normalized to one yen, not 100 yen. Null means unavailable.
 */
data class TaxPayment(
    val amountNative: BigDecimal,
    val currency: String,
    val settlementDate: LocalDate,
    val referenceFx: BigDecimal? = null,
) {
    internal fun krw(): BigDecimal? {
        if (amountNative.signum() < 0) return null
        val rate = if (currency == "KRW") BigDecimal.ONE else referenceFx?.takeIf { it.signum() > 0 }
        return rate?.let(amountNative::multiply)
    }
}

/** Costs are allocated to the sold quantity; acquisitions include each lot's own date/FX.
 * Expenses are separate payments (buy/sell fees) and must not be duplicated in acquisitions.
 * No adapter may infer this contract from realizedProfitKrw, returnRate or amountKrw.
 */
data class CapitalGainsBasis(
    val proceeds: TaxPayment,
    val acquisitions: List<TaxPayment>,
    val expenses: List<TaxPayment>,
    val costsComplete: Boolean,
    val estimated: Boolean = false,
    val estimateReason: String = "",
)

data class CapitalGainsTradeResult(
    val trade: Trade,
    val gainKrw: BigDecimal?,
    val estimated: Boolean,
    val exempt: Boolean,
    val reason: String,
)

data class CapitalGainsEstimate(
    val year: Int,
    val rows: List<CapitalGainsTradeResult>,
    val netGainKrw: BigDecimal?,
    val eligibleCount: Int,
    val estimatedCount: Int,
    val missingCount: Int,
    val domesticCount: Int,
    val accountErrors: List<String>,
) {
    val basicDeductionKrw: BigDecimal = BigDecimal("2500000")
    val taxableBaseKrw: BigDecimal? = netGainKrw?.subtract(basicDeductionKrw)?.max(BigDecimal.ZERO)
    // Reference estimate, whole KRW. Actual filing/payment rounding is handled by the broker/HomeTax.
    val estimatedTaxKrw: BigDecimal? = taxableBaseKrw?.multiply(BigDecimal("0.22"))?.setScale(0, RoundingMode.DOWN)
    val totalOverseasCount: Int = rows.count { !it.exempt }
    val incomplete: Boolean = estimatedCount > 0 || accountErrors.isNotEmpty()
    val filingPeriod: String = "${year + 1}년 5월 1일~31일 (휴일 시 다음 영업일)"
}

/** Widen fill-date queries around New Year; membership is determined by settlement, not fill.
 * Missing settlement dates stay visible as estimates, including boundary candidates.
 */
internal fun capitalGainsQueryPeriod(year: Int, today: LocalDate): Triple<LocalDate, LocalDate, String> {
    require(year in 2000..today.year) { "INVALID_TAX_YEAR" }
    return Triple(LocalDate.of(year - 1, 12, 1), LocalDate.of(year + 1, 1, 31).coerceAtMost(today), "${year}년 결제 기준")
}

fun estimateCapitalGainsTax(year: Int, history: TradeHistoryResponse): CapitalGainsEstimate {
    val rows = history.trades.filter { it.side == "매도" || it.side.uppercase() == "SELL" }.mapNotNull { trade ->
        val basis = trade.capitalGainsBasis
        val settlement = basis?.proceeds?.settlementDate ?: runCatching {
            estimatedSettlementDate(LocalDate.parse(trade.date.replace("-", ""), DateTimeFormatter.BASIC_ISO_DATE), trade.market)
        }.getOrNull()
        if (settlement != null && settlement.year != year) return@mapNotNull null
        if (trade.market.uppercase() in setOf("KOR", "KRX", "KOSPI", "KOSDAQ", "KONEX")) {
            return@mapNotNull CapitalGainsTradeResult(trade, null, basis == null, true,
                "비과세 · 국내 상장주식 장내거래 소액주주 가정 · 증권거래세 매도 시 원천징수")
        }
        val reason = when {
            basis == null -> "추정 · 취득 원가·결제일·기준환율 자료 부족 (표시 손익 사용 안 함)"
            basis.acquisitions.isEmpty() -> "추정 · 취득 원가 자료 부족"
            !basis.costsComplete -> "추정 · 수수료 등 필요경비 자료 부족"
            basis.acquisitions.any { it.settlementDate > basis.proceeds.settlementDate } -> "추정 · 취득 결제일 확인 필요"
            basis.proceeds.krw() == null || (basis.acquisitions + basis.expenses).any { it.krw() == null } ->
                "추정 · 결제일 기준환율 또는 금액 자료 부족"
            else -> null
        }
        if (reason != null) CapitalGainsTradeResult(trade, null, true, false, reason)
        else {
            requireNotNull(basis)
            val costs = (basis.acquisitions + basis.expenses).fold(BigDecimal.ZERO) { sum, payment ->
                sum + requireNotNull(payment.krw())
            }
            val estimated = basis.estimated || trade.realizedProfitEstimated
            CapitalGainsTradeResult(trade, requireNotNull(basis.proceeds.krw()) - costs, estimated, false,
                if (estimated) "추정 · ${basis.estimateReason.ifBlank { "원가·환율 포함 참고 계산" }}" else "취득·양도 각각의 결제일 기준환율 · 필요경비 포함")
        }
    }
    val overseas = rows.filter { !it.exempt }
    val priced = overseas.mapNotNull { it.gainKrw }
    val net = when {
        priced.isNotEmpty() -> priced.fold(BigDecimal.ZERO, BigDecimal::add)
        overseas.isEmpty() && history.accountErrors.isEmpty() -> BigDecimal.ZERO
        else -> null
    }
    return CapitalGainsEstimate(year, rows, net,
        eligibleCount = overseas.count { !it.estimated },
        estimatedCount = overseas.count { it.estimated },
        missingCount = overseas.count { it.gainKrw == null },
        domesticCount = rows.count { it.exempt },
        accountErrors = history.accountErrors,
    )
}
