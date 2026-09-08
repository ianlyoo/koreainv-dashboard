package com.koreainv.dashboard.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PinEntryStateTest {
    @Test fun fourthDigitSubmitsExactlyOnceAndBlocksRepeatedTaps() {
        var state = PinEntryState()
        for (digit in "123") {
            val change = state.enterDigit(digit)
            assertNull(change.pinToSubmit)
            assertFalse(change.state.isPending)
            state = change.state
        }
        assertEquals("123", state.value)

        val submitted = state.enterDigit('4')
        assertEquals("1234", submitted.pinToSubmit)
        assertEquals("1234", submitted.state.value)
        assertTrue(submitted.state.isPending)
        repeat(10) {
            val repeated = submitted.state.enterDigit('4')
            assertNull(repeated.pinToSubmit)
            assertSame(submitted.state, repeated.state)
        }
    }

    @Test fun leadingZerosReachSubmissionUnchanged() {
        var state = PinEntryState()
        for (digit in "000") state = state.enterDigit(digit).state
        val change = state.enterDigit('7')
        assertEquals("0007", change.pinToSubmit)
        assertEquals("0007", change.state.value)
    }

    @Test fun nonAsciiDigitsAndOtherCharactersAreIgnored() {
        val state = PinEntryState("123")
        for (digit in listOf('a', ' ', '-', '\n', '\u0664', '\uFF14')) {
            val change = state.enterDigit(digit)
            assertSame(state, change.state)
            assertNull(change.pinToSubmit)
        }
        assertEquals("1234", state.enterDigit('4').pinToSubmit)
    }

    @Test fun externalBusyBlocksEntryAndDeletionWithoutLosingPartialPin() {
        val state = PinEntryState("123")
        val blocked = state.enterDigit('4', externalBusy = true)
        assertSame(state, blocked.state)
        assertNull(blocked.pinToSubmit)
        assertSame(state, state.backspace(externalBusy = true))
        assertEquals("1234", state.enterDigit('4', externalBusy = false).pinToSubmit)
        assertEquals(PinEntryState(), PinEntryState().enterDigit('0', externalBusy = true).state)
    }

    @Test fun pendingAttemptBlocksBackspaceEvenAfterExternalBusyEnds() {
        val pending = PinEntryState("123").enterDigit('4').state
        assertSame(pending, pending.backspace(externalBusy = true))
        assertSame(pending, pending.backspace(externalBusy = false))
        assertNull(pending.enterDigit('5', externalBusy = false).pinToSubmit)
    }

    @Test fun backspaceRemovesOnlyLastDigitAndStopsAtEmpty() {
        var state = PinEntryState("012")
        state = state.backspace()
        assertEquals("01", state.value)
        state = state.backspace()
        assertEquals("0", state.value)
        state = state.backspace()
        assertEquals(PinEntryState(), state)
        assertSame(state, state.backspace())
        assertEquals("9", state.enterDigit('9').state.value)
    }

    @Test fun completionClearsAttemptAndAllowsSamePinRetry() {
        val first = PinEntryState("000").enterDigit('7')
        var state = first.state.finishAttempt()
        assertEquals(PinEntryState(), state)
        assertEquals(PinEntryState(), state.finishAttempt())
        for (digit in "000") state = state.enterDigit(digit).state
        val retry = state.enterDigit('7')
        assertEquals(first.pinToSubmit, retry.pinToSubmit)
        assertTrue(retry.state.isPending)
    }
}
