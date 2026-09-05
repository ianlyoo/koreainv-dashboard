package com.koreainv.dashboard.network

internal enum class KisRetryAction { NONE, REFRESH_TOKEN, RATE_LIMIT }

/** Authentication failures take precedence even when KIS returns HTTP 500 or 429. */
internal fun kisRetryAction(
    httpCode: Int,
    messageCode: String,
    message: String,
    retryOnTokenError: Boolean,
    rateLimitRetriesRemaining: Int,
): KisRetryAction {
    val tokenError = httpCode == 401 || messageCode in setOf("EGW00123", "EGW00121") ||
        message.contains("token", ignoreCase = true)
    if (tokenError) {
        return if (retryOnTokenError) KisRetryAction.REFRESH_TOKEN else KisRetryAction.NONE
    }
    val rateLimitError = httpCode == 429 || messageCode == "EGW00201" ||
        message.contains("초당 거래건수를 초과", ignoreCase = true)
    return if (rateLimitRetriesRemaining > 0 && rateLimitError) {
        KisRetryAction.RATE_LIMIT
    } else {
        KisRetryAction.NONE
    }
}
