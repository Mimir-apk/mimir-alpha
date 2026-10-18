package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A saved SteamGridDB key opens as dots, so the first edit replaces it rather than extending it.
 * Controller B presses Done and a blank Done clears the stored key unvalidated, so neither a stray Space nor a single
 * Backspace may blank it.
 */
class SteamGridDbFirstEditTest {

    private val key = "saved-0000"
    private val atEnd = TextFieldValue(key, TextRange(key.length))
    private val allSelected = TextFieldValue(key, TextRange(0, key.length))

    private fun edit(previous: TextFieldValue, text: String) =
        steamGridDbFirstEditOfSavedKey(key, previous, TextFieldValue(text, TextRange(text.length)))

    @Test
    fun `typing or pasting replaces the saved key and no change keeps it`() {
        assertEquals(SteamGridDbSavedKeyEdit(TextFieldValue("abc", TextRange(3)), false), edit(atEnd, key + "abc"))
        assertEquals(key, edit(atEnd, key).value.text)
        assertEquals(true, edit(atEnd, key).savedKeyUntouched)
    }

    @Test
    fun `the rule runs on the live draft, which is the saved key while it is untouched`() {
        // The overlay's writer passes the LIVE draft text as the saved key, so it needs no copy of the key anywhere.
        val live = TextFieldValue("k-1234", TextRange(6))
        assertEquals(
            SteamGridDbSavedKeyEdit(TextFieldValue("z", TextRange(1)), false),
            steamGridDbFirstEditOfSavedKey(live.text, live, TextFieldValue("k-1234z", TextRange(7))),
        )
        assertEquals(
            SteamGridDbSavedKeyEdit(live, true),
            steamGridDbFirstEditOfSavedKey(live.text, live, TextFieldValue("k-1234 ", TextRange(7))),
        )
    }

    @Test
    fun `a space alone is no edit`() {
        assertEquals(SteamGridDbSavedKeyEdit(atEnd, true), edit(atEnd, "$key "))
        assertEquals(SteamGridDbSavedKeyEdit(allSelected, true), edit(allSelected, " "))
    }

    @Test
    fun `the first delete selects the key, the second clears it, and typing replaces the selection`() {
        assertEquals(SteamGridDbSavedKeyEdit(allSelected, true), edit(atEnd, key.dropLast(1)))
        assertEquals(SteamGridDbSavedKeyEdit(TextFieldValue("", TextRange(0)), false), edit(allSelected, ""))
        assertEquals(SteamGridDbSavedKeyEdit(TextFieldValue("x", TextRange(1)), false), edit(allSelected, "x"))
    }

    @Test
    fun `a paste at a touch-placed caret keeps every pasted character even where it matches the saved key`() {
        val saved = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
        // Starts with the saved key's first character, so a common-prefix guess would slide one character over.
        val pasted = "0a1b2c3d4e5f60718293a4b5c6d7e8f0"
        val atStart = TextFieldValue(saved, TextRange(0))
        val pastedAtStart = TextFieldValue(pasted + saved, TextRange(pasted.length))
        assertEquals(
            SteamGridDbSavedKeyEdit(TextFieldValue(pasted, TextRange(pasted.length)), false),
            steamGridDbFirstEditOfSavedKey(saved, atStart, pastedAtStart),
        )
        // Caret between the 2nd and 3rd dot of "ABC123", then a paste that starts with the dot after the caret.
        val midCaret = TextFieldValue("ABC123", TextRange(2))
        assertEquals(
            SteamGridDbSavedKeyEdit(TextFieldValue("CX", TextRange(2)), false),
            steamGridDbFirstEditOfSavedKey("ABC123", midCaret, TextFieldValue("ABCXC123", TextRange(4))),
        )
        assertEquals(
            SteamGridDbSavedKeyEdit(TextFieldValue("X", TextRange(1)), false),
            steamGridDbFirstEditOfSavedKey("ABC123", midCaret, TextFieldValue("ABXC123", TextRange(3))),
        )
    }

    @Test
    fun `a key typed over a touch selection of part of the saved key replaces it`() {
        val partSelected = TextFieldValue(key, TextRange(2, 5))
        assertEquals(
            SteamGridDbSavedKeyEdit(TextFieldValue("x", TextRange(1)), false),
            steamGridDbFirstEditOfSavedKey(key, partSelected, TextFieldValue("sax-0000", TextRange(3))),
        )
    }

    @Test
    fun `a delete at a touch-placed caret or over part of the key only selects the key`() {
        val midCaret = TextFieldValue(key, TextRange(3))
        assertEquals(
            SteamGridDbSavedKeyEdit(allSelected, true),
            steamGridDbFirstEditOfSavedKey(key, midCaret, TextFieldValue("saed-0000", TextRange(2))),
        )
        val partSelected = TextFieldValue(key, TextRange(2, 5))
        assertEquals(
            SteamGridDbSavedKeyEdit(allSelected, true),
            steamGridDbFirstEditOfSavedKey(key, partSelected, TextFieldValue("sa-0000", TextRange(2))),
        )
    }

    @Test
    fun `a space at a touch-placed caret is no edit and leaves the caret where it was`() {
        val midCaret = TextFieldValue(key, TextRange(3))
        assertEquals(
            SteamGridDbSavedKeyEdit(midCaret, true),
            steamGridDbFirstEditOfSavedKey(key, midCaret, TextFieldValue("sav ed-0000", TextRange(4))),
        )
    }
}
