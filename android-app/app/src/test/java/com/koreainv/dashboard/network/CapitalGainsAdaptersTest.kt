package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CapitalGainsAdaptersTest {
    private val kis = JsonParser.parseString(javaClass.classLoader!!.getResource("tax/kis-field-shapes.json")!!.readText()).asJsonObject
    private fun sale() = requireNotNull(parseKisTaxSale(kis.getAsJsonObject("usdSale"), "NASD", "USD"))
    private fun buy(which: String = "buyOne") = requireNotNull(parseKisTaxFill(kis.getAsJsonObject(which), "NASD"))
    private fun bases(vararg executions: TaxExecution) = buildCapitalGainsBases(executions.toList(), 1700.0)
    private fun gains(sale: TaxExecution, basis: CapitalGainsBasis): CapitalGainsEstimate {
        val trade = Trade(sale.date.toString(), "매도", sale.symbol, sale.name, sale.market, sale.currency,
            sale.quantity.toDouble(), 0.0, sale.amount.toDouble(), 0.0, 999999999.0, null, capitalGainsBasis = basis)
        return estimateCapitalGainsTax(basis.proceeds.settlementDate.year, TradeHistoryResponse(
            TradePeriod("2025-12-01", "2027-01-31", "합성"), TradeSummary(0.0,0.0,0.0,0.0), listOf(trade)))
    }
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))

    @Test fun kisUsdRecordedShapeUsesIndependentNativeCostAndRowRate() {
        val sale = sale(); val basis = bases(sale).getValue(sale.key)
        eq("1400", basis.proceeds.referenceFx)
        eq("1000", basis.acquisitions.single().amountNative)
        eq("417200", gains(sale,basis).netGainKrw)
        assertTrue(basis.estimateReason.contains("취득환율 미확인"))
    }
    @Test fun kisTwoBuysUseFifoDatesWithBrokerAverageNativeCost() {
        val sale = sale(); val basis = bases(buy(), buy("buyTwo"), sale).getValue(sale.key)
        assertEquals(2,basis.acquisitions.size)
        eq("400",basis.acquisitions[0].amountNative); eq("600",basis.acquisitions[1].amountNative)
        eq("557200",gains(sale,basis).netGainKrw)
        assertTrue(basis.estimated)
    }
    @Test fun partialBuyCoverageFallsBackForOnlyUncoveredCost() {
        val sale = sale(); val basis = bases(buy(),sale).getValue(sale.key)
        eq("1200",basis.acquisitions[0].referenceFx); eq("1400",basis.acquisitions[1].referenceFx)
        eq("497200",gains(sale,basis).netGainKrw)
        assertEquals(0,gains(sale,basis).missingCount)
        assertTrue(basis.estimateReason.contains("환차 미반영"))
    }
    @Test fun missingBuyFxStillPricesCostAtSaleRate() {
        val sale = sale(); val basis = bases(buy().copy(fx=null),sale).getValue(sale.key)
        eq("417200",gains(sale,basis).netGainKrw)
        assertTrue(basis.estimateReason.contains("취득환율 미확인"))
    }
    @Test fun kisJPYPerHundredIsNormalizedForBothLegs() {
        val sale = requireNotNull(parseKisTaxSale(kis.getAsJsonObject("jpySale"),"TKSE","JPY"))
        val row = kis.getAsJsonObject("buyOne").deepCopy().apply {
            addProperty("pdno","FAKE-JP"); addProperty("crcy_cd","JPY"); addProperty("ccld_qty","10")
            addProperty("ft_ccld_amt3","80000"); addProperty("frst_bltn_exrt","900")
        }
        val buy = requireNotNull(parseKisTaxFill(row,"TKSE"))
        val basis = bases(buy,sale).getValue(sale.key)
        eq("9.5",basis.proceeds.referenceFx); eq("9",basis.acquisitions.single().referenceFx)
        eq("229050",gains(sale,basis).netGainKrw)
    }
    @Test fun zeroBrokerCostWithNoBuyHistoryRemainsUnpriced() {
        val row = kis.getAsJsonObject("usdSale").deepCopy().apply {
            addProperty("pchs_avg_pric","0"); addProperty("frcr_pchs_amt1","0")
            addProperty("ovrs_rlzt_pfls_amt","999999")
        }
        val sale = requireNotNull(parseKisTaxSale(row,"NASD","USD"))
        val result = gains(sale,bases(sale).getValue(sale.key))
        assertNull(result.netGainKrw); assertEquals(1,result.missingCount)
    }
    @Test fun priorSellConsumesFifoSoItsBuyIsNotReused() {
        val first = sale().copy(key="prior",date=LocalDate.of(2026,6,10),quantity=BigDecimal("4"),nativeCost=BigDecimal("400"))
        val final = sale().copy(quantity=BigDecimal("6"),nativeCost=BigDecimal("600"))
        val basis = bases(buy(),first,buy("buyTwo"),final).getValue(final.key)
        eq("1300",basis.acquisitions.single().referenceFx)
    }
    @Test fun tossRecordedExecutionsUseAcquisitionFxAndNativeCostNotExFxProfit() {
        val fixture = JsonParser.parseString(javaClass.classLoader!!.getResource("toss/orders-and-fx.json")!!.readText()).asJsonObject
        val rows = fixture.getAsJsonObject("orders").getAsJsonObject("result").getAsJsonArray("orders").map { it.asJsonObject }
        val rates = listOf(1200.0,1300.0,1400.0,1300.0)
        val cost = estimateTossRealizedProfit(rows.mapIndexed { i,row -> parseTossExecutionForEstimate(row)!!.copy(saleMidRate=rates[i]) },
            "20260901","20260930",1700.0)
        val inputs = rows.mapIndexed { i,row -> parseTossTaxExecution(row)!!.let { input -> input.copy(
            fx=BigDecimal.valueOf(rates[i]),nativeCost=cost.profitsByExecutionKey[input.key]?.buyAmountNative?.let(BigDecimal::valueOf)) } }
        val basis = buildCapitalGainsBases(inputs,1700.0).getValue("sell-one")
        eq("101000",gains(inputs[2],basis).netGainKrw)
        assertNotEquals(cost.profitsByExecutionKey.getValue("sell-one").realizedProfitKrw,gains(inputs[2],basis).netGainKrw!!.toDouble(),.01)
        assertTrue(basis.estimateReason.contains("참고환율"))
    }
    @Test fun tossProxyShapePreservesNativeCostAndBothFxDates() {
        fun proxy(id:String,side:String,date:String,amount:String,fx:String) = JsonObject().apply {
            addProperty("order_no",id); addProperty("symbol","FAKE"); addProperty("side",side)
            addProperty("date",date); addProperty("currency","USD"); addProperty("quantity","1")
            addProperty("amount_native",amount); addProperty("commission_native","0"); addProperty("tax_native","0")
            addProperty("tax_reference_fx",fx); addProperty("execution_costs_complete",true)
        }
        val buy = parseTossTaxExecution(proxy("b","매수","20260601","100","1200"))!!
        val sale = parseTossTaxExecution(proxy("s","매도","20260902","90","1400").apply {
            addProperty("buy_amount_native","100"); addProperty("realized_profit_krw","-14000")
        })!!
        eq("6000",gains(sale,bases(buy,sale).getValue("s")).netGainKrw)
    }
    @Test fun fxHydrationOnlyIncludesLotsActuallyConsumedBySelectedSales() {
        val oldSale = sale().copy(key="old-sell",date=LocalDate.of(2026,6,5),quantity=BigDecimal("4"))
        val keys = taxFxExecutionKeys(listOf(buy(),oldSale,buy("buyTwo"),sale()),LocalDate.of(2026,9,1),LocalDate.of(2026,9,30))
        assertEquals(setOf(buy("buyTwo").key,sale().key),keys)
    }
    @Test fun settlementCrossesNewYearAndKnownJapaneseHolidays() {
        assertEquals(LocalDate.of(2027,1,4),estimatedSettlementDate(LocalDate.of(2026,12,31),"NASD"))
        assertEquals(LocalDate.of(2027,1,5),estimatedSettlementDate(LocalDate.of(2026,12,30),"TKSE"))
        val sale = sale().copy(date=LocalDate.of(2026,12,31))
        val basis = bases(sale).getValue(sale.key)
        val trade = Trade("20261231","매도","FAKE","합성","NASD","USD",10.0,0.0,1300.0,0.0,null,null,capitalGainsBasis=basis)
        val history = TradeHistoryResponse(TradePeriod("20261201","20270131","합성"),TradeSummary(0.0,0.0,0.0,0.0),listOf(trade))
        assertEquals(0,estimateCapitalGainsTax(2026,history).totalOverseasCount)
        assertEquals(1,estimateCapitalGainsTax(2027,history).totalOverseasCount)
    }
    @Test fun usSettlementSkipsBankHolidayButGoodFridayIsOpen() {
        assertEquals(LocalDate.of(2026,10,13),estimatedSettlementDate(LocalDate.of(2026,10,9),"NASD"))
        assertEquals(LocalDate.of(2026,4,3),estimatedSettlementDate(LocalDate.of(2026,4,2),"NASD"))
    }
    @Test fun kisOrderNumbersReusedOnDifferentDaysDoNotCollapseBuyLots() {
        val first = kis.getAsJsonObject("buyOne").deepCopy()
        val second = kis.getAsJsonObject("buyTwo").deepCopy().apply { addProperty("odno",first.get("odno").asString) }
        val buys = listOf(parseKisTaxFill(first,"NASD")!!,parseKisTaxFill(second,"NASD")!!)
        assertNotEquals(buys[0].key,buys[1].key)
        assertEquals(2,buildCapitalGainsBases(buys+sale(),1700.0).getValue(sale().key).acquisitions.size)
    }
    @Test fun fractionalAllocationRetainsTheEntireBrokerNativeCost() {
        val sale = sale().copy(quantity=BigDecimal("3"),nativeCost=BigDecimal("100"))
        val first = buy().copy(quantity=BigDecimal.ONE)
        val second = buy("buyTwo").copy(quantity=BigDecimal("2"))
        val basis = bases(first,second,sale).getValue(sale.key)
        eq("100",basis.acquisitions.fold(BigDecimal.ZERO) { sum,payment -> sum+payment.amountNative })
    }
    @Test fun tossTradeDateUsesMarketTimezoneAtNewYear() {
        val row = JsonParser.parseString("""{"order_no":"fake-year-boundary","symbol":"FAKE","side":"매도","currency":"USD","quantity":"1","amount_native":"110","buy_amount_native":"100","commission_native":"0","tax_native":"0","tax_reference_fx":"1400","filled_at":"2027-01-01T05:00:00+09:00"}""").asJsonObject
        val sale = parseTossTaxExecution(row)!!
        assertEquals(LocalDate.of(2026,12,31),sale.date)
        assertEquals(LocalDate.of(2027,1,4),bases(sale).getValue(sale.key).proceeds.settlementDate)
    }
    @Test fun olderTossProxyStillProducesAUsableLabelledSaleRateEstimate() {
        val row = JsonParser.parseString("""{"order_no":"fake-legacy-proxy","symbol":"FAKE","side":"매도","currency":"USD","quantity":"1","amount_native":"90","buy_amount_native":"100","commission_native":"0","tax_native":"0","profit_exchange_rate":"1400","realized_profit_krw":"999999","date":"20260902"}""").asJsonObject
        val sale = parseTossTaxExecution(row)!!
        val basis = bases(sale).getValue(sale.key)
        eq("-14000",gains(sale,basis).netGainKrw)
        assertEquals(0,gains(sale,basis).missingCount)
        assertTrue(basis.estimateReason.contains("취득환율 미확인"))
    }
}
