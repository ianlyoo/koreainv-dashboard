package com.koreainv.dashboard.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ScreenRequestOwnerTest {
    @Test fun onlyTheLatestRequestCanPublishOrFinish() = runBlocking {
        val owner = ScreenRequestOwner()
        val oldResult = CompletableDeferred<String>()
        val values = mutableListOf<String>()
        var finished = 0
        owner.launch(this, load = { withContext(NonCancellable) { oldResult.await() } },
            onSuccess = values::add, onFailure = { error("Unexpected failure") }, onFinished = { finished++ })
        yield()
        owner.launch(this, load = { "new selection" }, onSuccess = values::add,
            onFailure = { error("Unexpected failure") }, onFinished = { finished++ })
        yield()
        oldResult.complete("old selection")
        yield()
        assertEquals(listOf("new selection"), values)
        assertEquals(1, finished)
    }

    @Test fun cancellationInvalidatesTheVersionBeforeLateCompletion() = runBlocking {
        val owner = ScreenRequestOwner()
        val result = CompletableDeferred<Unit>()
        var published = false
        var finished = false
        owner.launch(this, load = { result.await() }, onSuccess = { published = true },
            onFailure = { published = true }, onFinished = { finished = true })
        yield()
        val version = owner.version
        owner.cancel()
        result.complete(Unit)
        yield()
        assertFalse(owner.accepts(version))
        assertFalse(published)
        assertFalse(finished)
    }

    @Test fun currentFailureFinishesOnceAndAllowsRetry() = runBlocking {
        val owner = ScreenRequestOwner()
        var failures = 0
        var finished = 0
        owner.launch<Unit>(this, load = { throw IllegalStateException("synthetic failure") },
            onSuccess = { fail("Should fail") }, onFailure = { failures++ }, onFinished = { finished++ })
        yield()
        var recovered = false
        owner.launch(this, load = { true }, onSuccess = { recovered = it },
            onFailure = { fail("Should recover") }, onFinished = { finished++ })
        yield()
        assertEquals(1, failures)
        assertEquals(2, finished)
        assertTrue(recovered)
    }

    @Test fun filteredScopeDoesNotClaimTheSummaryWasFiltered() {
        assertTrue(portfolioScopeDescription(true).contains("요약은 전체 계좌"))
        assertTrue(portfolioScopeDescription(true).contains("목록은 선택한 계좌"))
        assertFalse(portfolioScopeDescription(false).contains("선택한"))
    }

    @Test fun initialFailureAndStaleSnapshotHaveDifferentHeadings() {
        assertNotEquals(feedbackTitle(false), feedbackTitle(true))
        assertTrue(feedbackTitle(true).contains("새로고침"))
    }
}
