package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import java.util.Locale

/**
 * Actual deposit fields for overseas cash. These take priority over orderable
 * amounts so the dashboard shows what is truly held, not just what can be traded.
 */
internal val FOREIGN_DEPOSIT_KEYS = listOf(
    "frcr_dncl_amt_2",
    "frcr_dncl_amt_1",
    "ccld_qty_smtl1",
)

/** Domestic cash comes from TTTC8434R output2 summary row dnca_tot_amt. */
internal fun pickDomesticDeposit(summary: JsonObject?): Double =
    summary?.let { numberFromJson(it, "dnca_tot_amt") } ?: 0.0

/**
 * Picks the actual deposit amount for [currencyCode] from CTRP6504R output2 rows.
 * Only rows whose currency matches are considered; there is deliberately no
 * cross-currency fallback, so JPY rows never satisfy a USD request.
 */
internal fun pickForeignDepositFromRows(rows: List<JsonObject>, currencyCode: String): Double {
    val normalized = currencyCode.uppercase(Locale.US)
    val matched = rows.filter { stringFromJson(it, "crcy_cd").uppercase(Locale.US) == normalized }
    if (matched.isEmpty()) return 0.0
    FOREIGN_DEPOSIT_KEYS.forEach { key ->
        val value = matched.maxOfOrNull { numberFromJson(it, key) } ?: 0.0
        if (value > 0.0) return value
    }
    return 0.0
}

internal fun pickForeignDepositFromCurrencyHoldingRow(
    rows: List<JsonObject>,
    currencyCode: String,
): Double {
    val normalized = currencyCode.uppercase(Locale.US)
    val row = rows.firstOrNull {
        stringFromJson(it, "pdno").uppercase(Locale.US) == normalized
    } ?: return 0.0
    return firstPositiveNumber(row, FOREIGN_DEPOSIT_KEYS)
}

internal fun pickForeignDepositFromOutput3(output3: JsonObject?): Double =
    output3?.let { firstPositiveNumber(it, FOREIGN_DEPOSIT_KEYS) } ?: 0.0

/** Actual deposit sources only. Orderable buying power is a separate concept. */
internal fun resolveOverseasCash(
    depositFromHoldingRow: Double,
    depositFromRows: Double,
    depositFromOutput3: Double,
): Double = listOf(
    depositFromHoldingRow,
    depositFromRows,
    depositFromOutput3,
).firstOrNull { it > 0.0 } ?: 0.0

/** Japan uses TR_MKET_CD=01 for CTRP6504R; other markets keep 00. */
internal fun overseasTrMarketCd(nationCode: String): String =
    if (nationCode == "392") "01" else "00"

internal fun stringFromJson(json: JsonObject, key: String): String =
    runCatching { json.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")
