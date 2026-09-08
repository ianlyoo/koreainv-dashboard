package com.koreainv.dashboard.ui.screens

private const val PIN_ENTRY_LENGTH = 4

/** Transient keypad state; the caller owns submission and asynchronous completion. */
internal data class PinEntryState(
    val value: String = "",
    val isPending: Boolean = false,
) {
    fun enterDigit(digit: Char, externalBusy: Boolean = false): PinEntryChange {
        if (isPending || externalBusy || digit !in '0'..'9' || value.length >= PIN_ENTRY_LENGTH) {
            return PinEntryChange(this)
        }
        val entered = value + digit
        return if (entered.length == PIN_ENTRY_LENGTH) {
            PinEntryChange(PinEntryState(entered, isPending = true), pinToSubmit = entered)
        } else {
            PinEntryChange(copy(value = entered))
        }
    }

    fun backspace(externalBusy: Boolean = false): PinEntryState =
        if (isPending || externalBusy || value.isEmpty()) this else copy(value = value.dropLast(1))

    fun finishAttempt(): PinEntryState = PinEntryState()
}

internal data class PinEntryChange(
    val state: PinEntryState,
    val pinToSubmit: String? = null,
)
