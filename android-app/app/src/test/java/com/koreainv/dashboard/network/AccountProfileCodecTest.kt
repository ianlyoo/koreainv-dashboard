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
    fun versionTwoProfileWithoutBrokerMigratesAccountsToKis() {
        val legacy = """{"version":2,"accounts":[{"id":"a","label":"기존","appKey":"k","appSecret":"s","cano":"12345678","acntPrdtCd":"01"}]}"""

        val parsed = AccountProfileCodec.parse(legacy)

        assertEquals(Broker.KIS, parsed!![0].broker)
        assertEquals("01", parsed[0].acntPrdtCd)
    }

    @Test
    fun tossAccountRoundTripsAndUsesBrokerScopedIdentity() {
        val toss = AccountCredential(
            id = stableAccountId("1", "", Broker.TOSS),
            label = "토스",
            appKey = "client",
            appSecret = "secret",
            cano = "1",
            acntPrdtCd = "",
            broker = Broker.TOSS,
        )

        val parsed = AccountProfileCodec.parse(AccountProfileCodec.serialize(listOf(toss)))!!

        assertEquals(toss, parsed.single())
        assertNotEquals(stableAccountId("1", "01", Broker.KIS), toss.id)
    }

    @Test
    fun normalizeUpdatedAccountsAcceptsTossSequenceWithoutProductCode() {
        val toss = AccountCredential("", "토스", "client", "secret", "1", "", broker = Broker.TOSS)

        val normalized = normalizeUpdatedAccounts(emptyList(), listOf(toss)).single()

        assertEquals(Broker.TOSS, normalized.broker)
        assertEquals("", normalized.acntPrdtCd)
        assertEquals(stableAccountId("1", "", Broker.TOSS), normalized.id)
    }

    @Test
    fun accountProfileChoosesFirstKisForKisOnlyFeatures() {
        val toss = AccountCredential("t", "토스", "c", "s", "1", "", broker = Broker.TOSS)
        val kis = AccountCredential("k", "KIS", "k", "s", "12345678", "01")

        assertEquals(kis, AccountProfile(listOf(toss, kis)).primary)
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

    @Test
    fun normalizeUpdatedAccountsKeepsIdAndStoredSecrets() {
        val existing = listOf(
            AccountCredential("acc-1", "기존", "key", "secret", "11111111", "01"),
        )
        val update = existing.single().copy(
            label = "수정",
            appKey = "",
            appSecret = "",
            cano = "22222222",
        )

        val normalized = normalizeUpdatedAccounts(existing, listOf(update)).single()

        assertEquals("acc-1", normalized.id)
        assertEquals("수정", normalized.label)
        assertEquals("key", normalized.appKey)
        assertEquals("secret", normalized.appSecret)
        assertEquals("22222222", normalized.cano)
    }

    @Test
    fun normalizeUpdatedAccountsAssignsNewId() {
        val account = AccountCredential("", "신규", "key", "secret", "33333333", "22")

        val normalized = normalizeUpdatedAccounts(emptyList(), listOf(account)).single()

        assertEquals(stableAccountId("33333333", "22"), normalized.id)
    }

    @Test
    fun normalizeUpdatedAccountsRejectsDuplicateAccountPair() {
        val updates = listOf(
            AccountCredential("a", "A", "key-a", "secret-a", "11111111", "01"),
            AccountCredential("b", "B", "key-b", "secret-b", "11111111", "01"),
        )

        val error = runCatching { normalizeUpdatedAccounts(emptyList(), updates) }.exceptionOrNull()

        assertEquals("ACCOUNT_PROFILE_DUPLICATE", error?.message)
    }

    @Test
    fun normalizeUpdatedAccountsDisambiguatesNewIdAfterExistingAccountEdit() {
        val originalCano = "11111111"
        val originalId = stableAccountId(originalCano, "01")
        val existing = listOf(
            AccountCredential(originalId, "기존", "key", "secret", originalCano, "01"),
        )
        val updates = listOf(
            existing.single().copy(cano = "22222222"),
            AccountCredential("", "신규", "key-2", "secret-2", originalCano, "01"),
        )

        val normalized = normalizeUpdatedAccounts(existing, updates)

        assertEquals(originalId, normalized[0].id)
        assertEquals("$originalId-2", normalized[1].id)
    }

    @Test
    fun normalizeUpdatedAccountsRejectsBlankProductCode() {
        val update = AccountCredential("", "신규", "key", "secret", "11111111", "")

        val error = runCatching {
            normalizeUpdatedAccounts(emptyList(), listOf(update))
        }.exceptionOrNull()

        assertEquals("ACCOUNT_PRODUCT_CODE_INVALID", error?.message)
    }
}
