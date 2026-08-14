package com.koreainv.dashboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountProfileCodecTest {
    @Test
    fun legacyAppCredentialsJsonMigratesToSingleAccountProfile() {
        val legacy = """{"appKey":"key-a","appSecret":"secret-a","cano":"12345678","acntPrdtCd":"01"}"""

        val accounts = AccountProfileCodec.parse(legacy)

        assertNotNull(accounts)
        assertEquals(1, accounts!!.size)
        assertEquals("key-a", accounts[0].appKey)
        assertEquals("12345678", accounts[0].cano)
        assertEquals("01", accounts[0].acntPrdtCd)
        assertEquals(stableAccountId("12345678", "01"), accounts[0].id)
        assertEquals("계좌 5678", accounts[0].label)
    }

    @Test
    fun legacyJsonWithCentralFieldsKeepsThemAfterMigration() {
        val legacy = """
            {"appKey":"key-a","appSecret":"secret-a","cano":"12345678","acntPrdtCd":"01",
             "centralServerBaseUrl":"https://central.example","centralServerApiToken":"tok"}
        """.trimIndent()

        val accounts = AccountProfileCodec.parse(legacy)

        assertEquals("https://central.example", accounts!![0].centralServerBaseUrl)
        assertEquals("tok", accounts[0].centralServerApiToken)
    }

    @Test
    fun multiAccountProfileRoundTripsThroughSerializer() {
        val profile = AccountProfile(
            accounts = listOf(
                AccountCredential("acc-1", "메인", "k1", "s1", "11111111", "01"),
                AccountCredential("acc-2", "세컨드", "k2", "s2", "22222222", "01"),
            ),
        )

        val parsed = AccountProfileCodec.parse(AccountProfileCodec.serialize(profile.accounts))

        assertEquals(profile.accounts, parsed)
    }

    @Test
    fun malformedPayloadReturnsNull() {
        assertNull(AccountProfileCodec.parse("not json"))
        assertNull(AccountProfileCodec.parse("""{"version":2,"accounts":[]}"""))
    }

    @Test
    fun tokenScopeIsStablePerAccountAndDistinctAcrossAccounts() {
        val first = AppCredentials("key-a", "secret-a", "11111111", "01")
        val second = AppCredentials("key-a", "secret-a", "22222222", "01")
        val sameAsFirst = AppCredentials("key-a", "secret-a", "11111111", "01")

        assertEquals(tokenScope(first), tokenScope(sameAsFirst))
        assertNotEquals(tokenScope(first), tokenScope(second))
    }
}
