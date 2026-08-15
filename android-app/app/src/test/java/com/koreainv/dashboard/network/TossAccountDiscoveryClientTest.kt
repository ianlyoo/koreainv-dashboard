package com.koreainv.dashboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TossAccountDiscoveryClientTest {
    @Test
    fun parseAccountsMasksAccountNumberAndKeepsSequence() {
        val options = parseTossAccountOptions(
            """
            {
              "result": [
                {"accountNo":"12345678901","accountSeq":7,"accountType":"BROKERAGE"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, options.size)
        assertEquals("7", options.single().accountSeq)
        assertEquals("••••8901", options.single().maskedAccountNo)
        assertEquals("토스증권 계좌 ••••8901", options.single().displayName)
    }

    @Test
    fun parseAccountsDropsInvalidSequencesAndHandlesMalformedPayload() {
        val invalid = parseTossAccountOptions(
            """{"result":[{"accountNo":"1234","accountSeq":0,"accountType":"BROKERAGE"}]}""",
        )

        assertTrue(invalid.isEmpty())
        assertTrue(parseTossAccountOptions("not-json").isEmpty())
    }
}
