package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2f: the system input method opens the existing layouts by field kind. These pin that a number field opens
 * on the digit grid, that email/URL fields reach `@` `.` `/` on the first (letter) layer without the symbols
 * page, and that the in-app default is exactly the layout it was before.
 */
class MimirKeyboardFieldKindTest {

    private fun ids(kind: MimirKeyboardFieldKind, set: MimirKeyboardCharacterSet = MimirKeyboardCharacterSet.ALPHA) =
        mimirKeyboardLayoutRows(set, kind).flatten()

    @Test
    fun theDefaultKindIsTheShippingLetterLayout() {
        assertEquals(
            mimirKeyboardLayoutRows(MimirKeyboardCharacterSet.ALPHA),
            mimirKeyboardLayoutRows(MimirKeyboardCharacterSet.ALPHA, MimirKeyboardFieldKind.TEXT),
        )
        val letters = ids(MimirKeyboardFieldKind.TEXT)
        assertFalse(letters.contains("char/@"))
        assertFalse(letters.contains("char//"))
    }

    @Test
    fun aNumberFieldOpensOnTheDigitGridAndStartsUnshifted() {
        assertEquals(MimirKeyboardCharacterSet.NUMBERS, MimirKeyboardFieldKind.NUMBER.startingCharacterSet())
        assertEquals(MimirKeyboardCharacterSet.ALPHA, MimirKeyboardFieldKind.TEXT.startingCharacterSet())
        assertEquals(MimirKeyboardCharacterSet.ALPHA, MimirKeyboardFieldKind.EMAIL.startingCharacterSet())
        assertEquals(MimirKeyboardCharacterSet.ALPHA, MimirKeyboardFieldKind.URI.startingCharacterSet())
        val grid = ids(MimirKeyboardFieldKind.NUMBER, MimirKeyboardCharacterSet.NUMBERS)
        assertTrue(grid.contains("char/5"))
        assertTrue(grid.none { it.startsWith("char/") && it.removePrefix("char/").any(Char::isLetter) })
        assertFalse(mimirKeyboardStartsShifted("", secret = false, fieldKind = MimirKeyboardFieldKind.NUMBER))
    }

    @Test
    fun anEmailFieldReachesAtAndDotOnTheLetterLayer() {
        val letters = ids(MimirKeyboardFieldKind.EMAIL)
        assertTrue(letters.contains("char/@"))
        assertTrue(letters.contains("char/."))
        assertTrue(letters.contains("char/q"))
        assertTrue(letters.contains("action/paste"))
        assertTrue(letters.contains("action/done"))
        assertFalse(mimirKeyboardStartsShifted("", secret = false, fieldKind = MimirKeyboardFieldKind.EMAIL))
    }

    @Test
    fun aUrlFieldReachesSlashAndDotOnTheLetterLayer() {
        val letters = ids(MimirKeyboardFieldKind.URI)
        assertTrue(letters.contains("char//"))
        assertTrue(letters.contains("char/."))
        assertTrue(letters.contains("action/space"))
        assertFalse(mimirKeyboardStartsShifted("", secret = false, fieldKind = MimirKeyboardFieldKind.URI))
    }

    @Test
    fun onlyAnOrdinaryEmptyTextFieldStartsShifted() {
        assertTrue(mimirKeyboardStartsShifted("", secret = false))
        assertFalse(mimirKeyboardStartsShifted("", secret = true))
        assertFalse(mimirKeyboardStartsShifted("x", secret = false))
    }
}
