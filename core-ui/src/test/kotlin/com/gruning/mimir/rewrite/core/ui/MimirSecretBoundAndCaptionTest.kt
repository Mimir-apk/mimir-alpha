package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D33 (a), (b), (e): the last password gaps in the keyboard's own code.
 *
 * (b) A hostile field can report any Int as its selection; nothing it reports may size an allocation.
 * (a) A secret caption has no text-selection or diff path at all.
 * (e) Main's in-app secret entry keeps no plaintext in the keyboard's remembered state.
 */
class MimirSecretBoundAndCaptionTest {

    private val bound = 1_000

    @Test
    fun aHostileSelectionNeverSizesTheDisplayPastTheBound() {
        val huge = MimirSecretDisplay.seed(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue("count ${huge.count} must be bounded", huge.count <= bound)
        assertTrue(huge.caret in 0..bound)
        assertTrue(huge.selected in 0..bound)

        val selection = MimirSecretDisplay.seed(0, Int.MAX_VALUE)
        assertTrue(selection.count <= bound)
        assertTrue(selection.caret + selection.selected <= bound)
    }

    @Test
    fun aNegativeOrInvertedReportBecomesAnEmptyDisplay() {
        assertEquals(MimirSecretDisplay(), MimirSecretDisplay.seed(-1, 5))
        assertEquals(MimirSecretDisplay(), MimirSecretDisplay.seed(5, -1))
        assertEquals(MimirSecretDisplay(), MimirSecretDisplay.seed(Int.MIN_VALUE, Int.MIN_VALUE))
        assertEquals("an inverted report is not trusted", MimirSecretDisplay(), MimirSecretDisplay.seed(5, 2))
    }

    @Test
    fun aHugeInsertOrAHugeSelectionUpdateStaysBoundedAndNeverOverflows() {
        val afterPaste = MimirSecretDisplay.seed(3, 3).after(MimirSecretEdit.Insert("x".repeat(200_000)))
        assertTrue("count ${afterPaste.count}", afterPaste.count in 0..bound)
        assertTrue(afterPaste.caret in 0..afterPaste.count)

        val updated = MimirSecretDisplay.seed(3, 3).withSelection(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(updated.count in 0..bound)
        assertTrue(updated.caret in 0..updated.count)
        val negative = MimirSecretDisplay.seed(3, 3).withSelection(-9, 4)
        assertEquals("a negative update changes nothing", MimirSecretDisplay.seed(3, 3), negative)
    }

    private fun keyboardSource(): String {
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"),
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"),
        ).first(File::isFile)
        // The prose explains the old behaviour; only code may be asserted on.
        return file.readText()
            .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")
    }

    @Test
    fun theSecretCaptionRunsNoSelectionOrDiffPath() {
        val display = keyboardSource().substringAfter("private fun KeyboardEditingDisplay(")
            .substringBefore("readOnly = true")
        val select = display.indexOf("selectMimirKeyboardText(")
        assertTrue("the caption still has its selection path for ordinary fields", select > 0)
        val guard = display.indexOf("if (!inert)")
        assertTrue("an inert (secret) caption must guard the selection path first", guard in 0 until select)
    }

    @Test
    fun theInAppSecretEntryKeepsNoPlaintextInRememberedKeyboardState() {
        val source = keyboardSource()
        val stringOverload = source.substringAfter("fun MimirKeyboard(\n    value: String,")
            .substringBefore("fun MimirKeyboard(\n    value: TextFieldValue,")
        // The ordinary keyboard keeps its editingValue; it simply has no secret mode any more.
        assertTrue(stringOverload.contains("var editingValue"))
        assertFalse("the ordinary overload takes no secret flag", stringOverload.contains("secret: Boolean"))
        assertTrue("and passes the surface a plain false", stringOverload.contains("secret = false,"))
    }
}
