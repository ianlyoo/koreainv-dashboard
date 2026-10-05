package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import java.math.BigDecimal
import java.math.MathContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.ArrayDeque

/** Account-local executions. Native cost is independently supplied, never derived from P&L. */
internal data class TaxExecution(
    val key: String,
    val date: LocalDate,
    val time: String,
    val symbol: String,
    val name: String,
    val market: String,
    val currency: String,
    val buy: Boolean,
    val quantity: BigDecimal,
    val amount: BigDecimal,
    val fee: BigDecimal,
    val fx: BigDecimal?,
    val nativeCost: BigDecimal? = null,
    val costsKnown: Boolean = true,
    val costIncludesBuyFees: Boolean = true,
    val source: String = "참고환율",
)

private fun JsonObject.taxText(key: String): String =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")
private fun JsonObject.taxNumber(key: String): BigDecimal? = taxText(key).toBigDecimalOrNull()
private fun JsonObject.taxPositive(vararg keys: String): BigDecimal? =
    keys.firstNotNullOfOrNull { taxNumber(it)?.takeIf { value -> value.signum() > 0 } }
private fun taxDate(value: String): LocalDate? = runCatching {
    LocalDate.parse(value.take(10).replace("-", ""), DateTimeFormatter.BASIC_ISO_DATE)
}.getOrNull()
private fun referenceRate(row: JsonObject, currency: String): BigDecimal? {
    val rate = row.taxPositive("frst_bltn_exrt", "bass_exrt", "exrt") ?: return null
    return if (currency == "JPY") rate.divide(BigDecimal("100")) else rate
}

internal fun parseKisTaxSale(row: JsonObject, market: String, currency: String): TaxExecution? {
    val date = taxDate(row.taxText("trad_day")) ?: return null
    val symbol = row.taxText("ovrs_pdno").ifBlank { row.taxText("pdno") }
    val qty = row.taxPositive("slcl_qty", "ccld_qty") ?: return null
    val amount = row.taxPositive("frcr_sll_amt_smtl1", "frcr_sll_amt_smtl", "stck_sll_amt_smtl") ?: return null
    if (symbol.isBlank()) return null
    val cost = row.taxPositive("pchs_avg_pric")?.multiply(qty) ?: row.taxPositive("frcr_pchs_amt1")
    val fee = row.taxNumber("stck_sll_tlex") ?: row.taxNumber("smtl_fee1")
    return TaxExecution(listOf(date, symbol, qty, amount, market, cost, fee).joinToString("|"), date, "235959", symbol,
        row.taxText("ovrs_item_name").ifBlank { symbol }, row.taxText("ovrs_excg_cd").ifBlank { market }, currency,
        false, qty, amount, fee?.max(BigDecimal.ZERO) ?: BigDecimal.ZERO, referenceRate(row, currency), cost,
        costsKnown = fee != null, costIncludesBuyFees = false, source = "평균단가 · 참고환율")
}

internal fun parseKisTaxFill(row: JsonObject, market: String): TaxExecution? {
    val date = taxDate(row.taxText("trad_dt").ifBlank { row.taxText("ord_dt") }.ifBlank { row.taxText("ccld_dt") }) ?: return null
    val symbol = row.taxText("pdno").ifBlank { row.taxText("ovrs_pdno") }
    val qty = row.taxPositive("ccld_qty", "tot_ccld_qty", "ft_ccld_qty", "ccld_qty_smtl1") ?: return null
    val price = row.taxPositive("ovrs_stck_ccld_unpr", "ft_ccld_unpr2", "ft_ccld_unpr3", "ccld_unpr")
    val amount = row.taxPositive("ft_ccld_amt3", "tr_frcr_amt2", "ovrs_ccld_amt", "tr_amt") ?: price?.multiply(qty) ?: return null
    val code = row.taxText("sll_buy_dvsn_cd").ifBlank { row.taxText("sll_buy_dvsn") }
    val side = row.taxText("sll_buy_dvsn_name").ifBlank { row.taxText("sll_buy_dvsn_cd_name") }
    val buy = code == "02" || side.contains("매수")
    if (!buy && code != "01" && !side.contains("매도")) return null
    val currency = row.taxText("crcy_cd").ifBlank { if (market in setOf("TKSE", "TSE", "JPX")) "JPY" else "USD" }
    val time = row.taxText("ord_tmd").ifBlank { row.taxText("ccld_tmd") }
    val key = listOf(date, time, symbol, buy, qty, amount, row.taxText("odno")).joinToString("|")
    return TaxExecution(key, date, time, symbol, row.taxText("ovrs_item_name").ifBlank { symbol }, market,
        currency, buy, qty, amount, BigDecimal.ZERO, referenceRate(row, currency), costsKnown = false,
        source = "참고환율 · 매수 수수료 미확인")
}

/** Accepts either official OrderExecution or the existing proxy's normalized execution shape. */
internal fun parseTossTaxExecution(row: JsonObject): TaxExecution? {
    val direct = row.get("execution")?.takeIf { it.isJsonObject }?.asJsonObject
    val timestamp = direct?.taxText("filledAt")?.ifBlank { row.taxText("orderedAt") } ?: row.taxText("filled_at")
    val currency = row.taxText("currency").uppercase()
    val localTimestamp = runCatching { OffsetDateTime.parse(timestamp).atZoneSameInstant(
        ZoneId.of(if (currency == "USD") "America/New_York" else if (currency == "JPY") "Asia/Tokyo" else "Asia/Seoul")) }.getOrNull()
    val date = localTimestamp?.toLocalDate() ?: taxDate(timestamp.ifBlank { row.taxText("date") }) ?: return null
    val qty = direct?.taxPositive("filledQuantity") ?: row.taxPositive("quantity") ?: return null
    val price = direct?.taxPositive("averageFilledPrice") ?: row.taxPositive("unit_price")
    val amount = direct?.taxPositive("filledAmount") ?: row.taxPositive("amount_native") ?: price?.multiply(qty) ?: return null
    val symbol = row.taxText("symbol").ifBlank { row.taxText("ticker") }
    if (symbol.isBlank()) return null
    val side = row.taxText("side").uppercase()
    if (side !in setOf("BUY", "SELL", "매수", "매도")) return null
    val commission = if (direct != null) direct.taxNumber("commission") else row.taxNumber("commission_native")
    val tax = if (direct != null) direct.taxNumber("tax") else row.taxNumber("tax_native")
    val time = localTimestamp?.format(DateTimeFormatter.ofPattern("HHmmss"))
        ?: timestamp.drop(11).take(8).replace(":", "").ifBlank { row.taxText("time") }
    val key = row.taxText("orderId").ifBlank { row.taxText("order_no") }.ifBlank {
        listOf(taxDate(timestamp.ifBlank { row.taxText("date") })?.format(DateTimeFormatter.BASIC_ISO_DATE),
            timestamp.drop(11).take(8).replace(":", "").ifBlank { row.taxText("time") }, symbol,
            if (side in setOf("BUY", "매수")) "매수" else "매도", qty.toDouble(), amount.toDouble()).joinToString("|")
    }
    return TaxExecution(key, date, time, symbol, row.taxText("name").ifBlank { symbol },
        row.taxText("market").ifBlank { if (currency == "KRW") "KOR" else "NASD" }, currency,
        side in setOf("BUY", "매수"), qty, amount,
        (commission ?: BigDecimal.ZERO) + (tax ?: BigDecimal.ZERO), row.taxPositive("tax_reference_fx", "profit_exchange_rate"),
        nativeCost = row.taxPositive("buy_amount_native"),
        costsKnown = commission != null && tax != null && row.taxText("execution_costs_complete") != "false")
}

private data class TaxLot(var quantity: BigDecimal, val execution: TaxExecution)

/** Only hydrate buy FX for FIFO lots actually used by selected sales, plus those sales' FX. */
internal fun taxFxExecutionKeys(executions: List<TaxExecution>, start: LocalDate, end: LocalDate): Set<String> {
    val positions = mutableMapOf<Pair<String, String>, ArrayDeque<TaxLot>>()
    val keys = mutableSetOf<String>()
    executions.distinctBy { it.key }.sortedWith(compareBy<TaxExecution> { it.date }.thenBy { it.time }.thenBy { it.key }).forEach { execution ->
        val lots = positions.getOrPut(execution.symbol to execution.currency) { ArrayDeque() }
        if (execution.buy) lots.add(TaxLot(execution.quantity, execution))
        else {
            val selected = execution.currency != "KRW" && execution.date in start..end
            if (selected) keys.add(execution.key)
            var remaining = execution.quantity
            while (remaining.signum() > 0 && lots.isNotEmpty()) {
                val lot = lots.first
                val covered = remaining.min(lot.quantity)
                if (selected) keys.add(lot.execution.key)
                remaining -= covered
                lot.quantity -= covered
                if (lot.quantity.signum() == 0) lots.removeFirst()
            }
        }
    }
    return keys
}

/** FIFO chooses acquisition FX dates; the broker's moving-average native cost remains the cost total.
 * Uncovered quantities and missing acquisition FX use the sell-date rate, explicitly estimated.
 */
internal fun buildCapitalGainsBases(executions: List<TaxExecution>, fallbackUsdRate: Double): Map<String, CapitalGainsBasis> {
    val positions = mutableMapOf<Pair<String, String>, ArrayDeque<TaxLot>>()
    val bases = mutableMapOf<String, CapitalGainsBasis>()
    val math = MathContext.DECIMAL128
    executions.distinctBy { it.key }.sortedWith(compareBy<TaxExecution> { it.date }.thenBy { it.time }.thenBy { it.key }).forEach { sell ->
        val lots = positions.getOrPut(sell.symbol to sell.currency) { ArrayDeque() }
        if (sell.buy) {
            lots.add(TaxLot(sell.quantity, sell))
            return@forEach
        }
        val quantity = lots.fold(BigDecimal.ZERO) { sum, lot -> sum + lot.quantity }
        val availableCost = lots.fold(BigDecimal.ZERO) { sum, lot ->
            sum + (lot.execution.amount + lot.execution.fee).divide(lot.execution.quantity, math).multiply(lot.quantity)
        }
        val nativeCost = sell.nativeCost ?: if (quantity.signum() > 0)
            availableCost.divide(quantity, math).multiply(sell.quantity) else null
        val date = estimatedSettlementDate(sell.date, sell.market)
        val reasons = linkedSetOf(sell.source, "결제일 추정")
        val saleFx = if (sell.currency == "KRW") BigDecimal.ONE else sell.fx ?: run {
            reasons.add("매도환율 미확인(현재참고)")
            BigDecimal.valueOf(if (sell.currency == "JPY") 9.05 else fallbackUsdRate)
        }
        val acquisitions = mutableListOf<TaxPayment>()
        var remaining = sell.quantity
        val unitCost = nativeCost?.divide(sell.quantity, math)
        var allocatedNative = BigDecimal.ZERO
        while (remaining.signum() > 0 && lots.isNotEmpty()) {
            val lot = lots.first
            val covered = remaining.min(lot.quantity)
            if (unitCost != null) {
                if (lot.execution.source.contains(TAX_FX_SUBSTITUTED)) reasons.add("취득 $TAX_FX_SUBSTITUTED")
                val fx = lot.execution.fx ?: saleFx.also { reasons.add("취득환율 미확인(환차 미반영)") }
                // With no acquisition FX, use the sale date for the explicit fallback payment.
                val acquired = if (lot.execution.fx == null) date else estimatedSettlementDate(lot.execution.date, lot.execution.market)
                val principal = if (covered == remaining) requireNotNull(nativeCost) - allocatedNative else unitCost.multiply(covered)
                acquisitions.add(TaxPayment(principal, sell.currency, acquired, fx))
                allocatedNative += principal
                if (!sell.costIncludesBuyFees && lot.execution.fee.signum() > 0) {
                    acquisitions.add(TaxPayment(lot.execution.fee.multiply(covered).divide(lot.execution.quantity, math), sell.currency, acquired, fx))
                }
                if (!lot.execution.costsKnown) reasons.add("매수 수수료 미확인")
            }
            remaining -= covered
            lot.quantity -= covered
            if (lot.quantity.signum() == 0) lots.removeFirst()
        }
        if (remaining.signum() > 0 && unitCost != null) {
            acquisitions.add(TaxPayment(requireNotNull(nativeCost) - allocatedNative, sell.currency, date, saleFx))
            reasons.add("취득환율 미확인(환차 미반영)")
        }
        if (sell.nativeCost == null && nativeCost != null) reasons.add("평균단가")
        if (!sell.costsKnown) reasons.add("수수료 미확인")
        bases[sell.key] = CapitalGainsBasis(TaxPayment(sell.amount, sell.currency, date, saleFx), acquisitions,
            listOf(TaxPayment(sell.fee, sell.currency, date, saleFx)), costsComplete = true, estimated = true,
            estimateReason = reasons.joinToString(" · "))
    }
    return bases
}

internal fun estimatedSettlementDate(tradeDate: LocalDate, market: String, additionalHolidays: Set<LocalDate> = emptySet()): LocalDate {
    val japan = market.uppercase() in setOf("TKSE", "TSE", "JPX", "TYO", "JPN")
    val domestic = market.uppercase() in setOf("KOR", "KRX", "KOSPI", "KOSDAQ", "KONEX")
    var left = if (japan || domestic || tradeDate < LocalDate.of(2024, 5, 28)) 2 else 1
    var date = tradeDate
    while (left > 0) {
        date = date.plusDays(1)
        if (date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
            date !in additionalHolidays && (domestic || !knownSettlementHoliday(date, japan))) left--
    }
    return date
}

private fun knownSettlementHoliday(date: LocalDate, japan: Boolean): Boolean {
    val y = date.year
    if (japan) {
        if ((date.monthValue == 1 && date.dayOfMonth <= 3) || (date.monthValue == 12 && date.dayOfMonth == 31)) return true
        val dates = when (y) {
            2025 -> "01-13 02-11 02-24 03-20 04-29 05-03 05-04 05-05 05-06 07-21 08-11 09-15 09-23 10-13 11-03 11-24"
            2026 -> "01-12 02-11 02-23 03-20 04-29 05-03 05-04 05-05 05-06 07-20 08-11 09-21 09-22 09-23 10-12 11-03 11-23"
            2027 -> "01-11 02-11 02-23 03-21 03-22 04-29 05-03 05-04 05-05 07-19 08-11 09-20 09-23 10-11 11-03 11-23"
            else -> ""
        }
        return date.toString().substring(5) in dates.split(' ')
    }
    fun monday(month: Int, nth: Int) = LocalDate.of(y, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(nth, DayOfWeek.MONDAY))
    // US cash settlement follows banking holidays, not early exchange closes or Good Friday.
    val fixed = listOf(LocalDate.of(y, 1, 1), LocalDate.of(y, 6, 19), LocalDate.of(y, 7, 4), LocalDate.of(y, 11, 11), LocalDate.of(y, 12, 25))
        .map { if (it.dayOfWeek == DayOfWeek.SUNDAY) it.plusDays(1) else it }
    return date in fixed || date == monday(1, 3) || date == monday(2, 3) ||
        date == LocalDate.of(y, 5, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) ||
        date == monday(9, 1) || date == monday(10, 2) ||
        date == LocalDate.of(y, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
}

/** Query earlier years only when the fetched FIFO quantities cannot cover a selected sale. */
internal fun hasUncoveredTaxSales(executions: List<TaxExecution>, selectedKeys: Set<String> = executions.filter { !it.buy }.map { it.key }.toSet()): Boolean {
    val quantities = mutableMapOf<Pair<String, String>, BigDecimal>()
    var uncovered = false
    executions.distinctBy { it.key }.sortedWith(compareBy<TaxExecution> { it.date }.thenBy { it.time }.thenBy { it.key }).forEach { row ->
        val key = row.symbol to row.currency
        val held = quantities[key] ?: BigDecimal.ZERO
        if (row.buy) quantities[key] = held + row.quantity
        else {
            if (row.key in selectedKeys && held < row.quantity) uncovered = true
            quantities[key] = (held - row.quantity).max(BigDecimal.ZERO)
        }
    }
    return uncovered
}
