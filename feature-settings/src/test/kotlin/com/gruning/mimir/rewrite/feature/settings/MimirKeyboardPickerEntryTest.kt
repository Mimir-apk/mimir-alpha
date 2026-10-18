package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R6-P3-02 ("make Mimir keyboard default"), policy 2026-09-15 ("Picker shortcut"). Pins the one
 * pure decision the ticket asks for: what the Settings/onboarding entry offers, from two platform facts
 * — the enabled-IME id set and the current default IME id — never a write to
 * `Settings.Secure.DEFAULT_INPUT_METHOD`.
 */
class MimirKeyboardPickerEntryTest {

    private val mimirImeId = "com.gruning.mimir.rewrite/.MimirInputMethodService"
    private val otherImeId = "com.google.android.inputmethod.latin/.LatinIME"

    @Test
    fun `not enabled -- open Android's keyboard settings`() {
        assertEquals(
            MimirKeyboardPickerEntry.OPEN_KEYBOARD_SETTINGS,
            mimirKeyboardPickerEntryState(
                mimirImeId = mimirImeId,
                enabledInputMethodIds = setOf(otherImeId),
                defaultInputMethodId = otherImeId,
            ),
        )
    }

    @Test
    fun `enabled but another IME is default -- show the input method picker`() {
        assertEquals(
            MimirKeyboardPickerEntry.SHOW_INPUT_METHOD_PICKER,
            mimirKeyboardPickerEntryState(
                mimirImeId = mimirImeId,
                enabledInputMethodIds = setOf(mimirImeId, otherImeId),
                defaultInputMethodId = otherImeId,
            ),
        )
    }

    @Test
    fun `enabled and no default recorded yet -- show the input method picker`() {
        assertEquals(
            MimirKeyboardPickerEntry.SHOW_INPUT_METHOD_PICKER,
            mimirKeyboardPickerEntryState(
                mimirImeId = mimirImeId,
                enabledInputMethodIds = setOf(mimirImeId),
                defaultInputMethodId = null,
            ),
        )
    }

    @Test
    fun `already the default -- settled, nothing left to offer`() {
        assertEquals(
            MimirKeyboardPickerEntry.ALREADY_DEFAULT,
            mimirKeyboardPickerEntryState(
                mimirImeId = mimirImeId,
                enabledInputMethodIds = setOf(mimirImeId, otherImeId),
                defaultInputMethodId = mimirImeId,
            ),
        )
    }
}
