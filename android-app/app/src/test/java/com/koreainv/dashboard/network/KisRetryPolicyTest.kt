package com.koreainv.dashboard.network

import org.junit.Assert.assertEquals
import org.junit.Test

class KisRetryPolicyTest {
    @Test
    fun http500TokenErrorsRefreshImmediatelyInsteadOfBackingOff() {
        for (code in listOf("EGW00121", "EGW00123")) {
            assertEquals(KisRetryAction.REFRESH_TOKEN, kisRetryAction(500, code, "유효하지 않은 토큰", true, 2))
        }
    }

    @Test
    fun authenticationTakesPrecedenceOverRateLimiting() {
        assertEquals(KisRetryAction.REFRESH_TOKEN, kisRetryAction(429, "EGW00123", "", true, 2))
        assertEquals(KisRetryAction.REFRESH_TOKEN, kisRetryAction(401, "EGW00201", "", true, 2))
        assertEquals(KisRetryAction.REFRESH_TOKEN, kisRetryAction(500, "", "Invalid TOKEN", true, 0))
    }

    @Test
    fun rejectedRetryCannotRefreshAgainOrFallThroughToRateLimiting() {
        for (status in listOf(200, 401, 429, 500)) {
            assertEquals(KisRetryAction.NONE, kisRetryAction(status, "EGW00123", "", false, 2))
        }
    }

    @Test
    fun genuineRateLimitsRespectRetryBudget() {
        assertEquals(KisRetryAction.RATE_LIMIT, kisRetryAction(429, "", "", true, 2))
        assertEquals(KisRetryAction.RATE_LIMIT, kisRetryAction(500, "EGW00201", "", true, 2))
        assertEquals(KisRetryAction.RATE_LIMIT, kisRetryAction(200, "EGW00201", "", false, 1))
        assertEquals(KisRetryAction.NONE, kisRetryAction(429, "", "", true, 0))
        assertEquals(KisRetryAction.NONE, kisRetryAction(500, "EGW00201", "", true, 0))
        assertEquals(KisRetryAction.RATE_LIMIT, kisRetryAction(500, "", "초당 거래건수를 초과", true, 2))
    }

    @Test
    fun unrelatedServerErrorsDoNotRetry() {
        for (status in listOf(200, 400, 403, 500, 502, 503)) {
            assertEquals(KisRetryAction.NONE, kisRetryAction(status, "OTHER", "", true, 2))
        }
    }
}
