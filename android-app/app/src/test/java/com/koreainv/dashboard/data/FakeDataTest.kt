package com.koreainv.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDataTest {
    @Test
    fun demoPinValidationMatchesExpectedPin() {
        assertTrue(PinAuth.isValid("1234"))
    }

    @Test
    fun nextSnapshotChangesVisiblePortfolioState() {
        val initial = FakePortfolioRepository.initialSnapshot()
        val next = FakePortfolioRepository.nextSnapshot(initial)

        assertNotEquals(initial.lastSyncedLabel, next.lastSyncedLabel)
        assertNotEquals(initial.totalPortfolioValue, next.totalPortfolioValue)
    }

    @Test
    fun portfolioSnapshotCalculatesTotalsFromHoldings() {
        val snapshot = PortfolioSnapshot(
            holdings = listOf(
                Holding("AAA", "Alpha", 2.0, 100.0, 80.0),
                Holding("BBB", "Beta", 1.0, 50.0, 60.0),
            ),
            cashBalanceKrw = 1000.0,
            lastSyncedLabel = "Today 10:00",
        )

        assertEquals(250.0, snapshot.totalPortfolioValue, 0.001)
        assertEquals(30.0, snapshot.totalProfitLoss, 0.001)
        assertEquals(13.6363, snapshot.totalProfitLossPercentage, 0.001)
    }
}
