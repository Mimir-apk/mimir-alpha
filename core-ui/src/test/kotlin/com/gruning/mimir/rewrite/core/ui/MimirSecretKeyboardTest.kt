package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2f round 3: a secret field's keyboard reports discrete ops and shows three Ints. These pin the op each key
 * stands for, the caption arithmetic (including a bullet that is a real character), and that no character of the
 * password is representable in the display or readable from an op's string form.
 */
class MimirSecretKeyboardTest {

    private val keys = MimirKeyboardCharacterSet.values().flatMap { keyboardRows(shiftOn = false, characterSet = it).flatten() }

    private fun key(action: KeyboardAction?, value: String? = null): KeyboardKey =
        keys.first { it.action == action && (value == null || it.value == value) }

    @Test
    fun everyKeyMapsToExactlyOneOp() {
        assertEquals(MimirSecretEdit.Insert("a"), mimirSecretEditFor(key(null, "a")) { null })
        assertEquals(MimirSecretEdit.DeleteBackward, mimirSecretEditFor(key(KeyboardAction.Back)) { null })
        assertEquals(MimirSecretEdit.Insert(" "), mimirSecretEditFor(key(KeyboardAction.Space)) { null })
        assertEquals(MimirSecretEdit.Insert("•a"), mimirSecretEditFor(key(KeyboardAction.Paste)) { "•a" })
        assertNull("an empty clipboard is no edit", mimirSecretEditFor(key(KeyboardAction.Paste)) { "" })
        assertNull(mimirSecretEditFor(key(KeyboardAction.Paste)) { null })
        assertNull(mimirSecretEditFor(key(KeyboardAction.Shift)) { "x" })
        assertNull(mimirSecretEditFor(key(KeyboardAction.Symbols)) { "x" })
    }

    @Test
    fun aSpaceDragOwesTheCaretKeysForItsSteps() {
        assertEquals(List(3) { MimirSecretEdit.CaretRight }, mimirSecretCaretEdits(0, 3))
        assertEquals(List(2) { MimirSecretEdit.CaretLeft }, mimirSecretCaretEdits(5, 3))
        assertTrue(mimirSecretCaretEdits(4, 4).isEmpty())
    }

    @Test
    fun theDisplayFollowsEachOpAndStaysInRange() {
        var d = MimirSecretDisplay.seed(3, 3)
        assertEquals(MimirSecretDisplay(count = 3, caret = 3, selected = 0), d)
        d = d.after(MimirSecretEdit.Insert("ab"))
        assertEquals(MimirSecretDisplay(count = 5, caret = 5), d)
        d = d.after(MimirSecretEdit.CaretLeft).after(MimirSecretEdit.CaretLeft)
        assertEquals(3, d.caret)
        d = d.after(MimirSecretEdit.DeleteBackward)
        assertEquals(MimirSecretDisplay(count = 4, caret = 2), d)
        // The caret never leaves the dots.
        repeat(10) { d = d.after(MimirSecretEdit.CaretRight) }
        assertEquals(4, d.caret)
        repeat(10) { d = d.after(MimirSecretEdit.CaretLeft) }
        assertEquals(0, d.caret)
        assertEquals("a backspace at the start deletes nothing", 4, d.after(MimirSecretEdit.DeleteBackward).count)
    }

    @Test
    fun aSelectionIsReplacedByAnInsertAndRemovedByABackspace() {
        val selected = MimirSecretDisplay.seed(1, 4)
        assertEquals(MimirSecretDisplay(count = 4, caret = 1, selected = 3), selected)
        assertEquals(MimirSecretDisplay(count = 1, caret = 1), selected.after(MimirSecretEdit.DeleteBackward))
        assertEquals(MimirSecretDisplay(count = 2, caret = 2), selected.after(MimirSecretEdit.Insert("z")))
    }

    @Test
    fun aBulletIsJustAnInsertOfOneCharacter() {
        val d = MimirSecretDisplay.seed(2, 2).after(MimirSecretEdit.Insert("•"))
        assertEquals(MimirSecretDisplay(count = 3, caret = 3), d)
    }

    @Test
    fun theFieldsOwnSelectionReportMovesTheCaptionWithoutAnyText() {
        val d = MimirSecretDisplay.seed(5, 5).withSelection(2, 4)
        assertEquals(MimirSecretDisplay(count = 5, caret = 2, selected = 2), d)
        assertEquals("an unknown selection changes nothing", d, d.withSelection(-1, -1))
        assertEquals(MimirSecretDisplay(), MimirSecretDisplay.seed(-1, -1))
    }

    @Test
    fun noCharacterIsReadableFromAnOpOrRepresentableInTheDisplay() {
        assertEquals("Insert(length=7)", MimirSecretEdit.Insert("hunter2").toString())
        assertTrue(!MimirSecretEdit.Insert("hunter2").toString().contains("hunter"))
        // Three Ints and one flag are the whole display state; no String or CharSequence can hold a character.
        val fields = MimirSecretDisplay::class.java.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(listOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), fields.map { it.type })
        assertNotNull(MimirSecretDisplay().toString())
    }

    @Test
    fun anOverflowingDisplayDrawsTheBoundPlusAnOverflowMark() {
        val huge = MimirSecretDisplay.seed(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(MimirSecretDisplayBound, huge.count)
        assertTrue(huge.overflow)
        val dots = huge.dotsText()
        assertEquals(MimirSecretDisplayBound + 1, dots.length)
        assertEquals('…', dots.last())
        assertEquals("a small display has no mark", "•••", MimirSecretDisplay.seed(3, 3).dotsText())
    }

    private fun value(text: String, at: Int, end: Int = at) =
        androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(at, end))

    @Test
    fun anOwnedSecretIsEditedByOpsAndTheKeyboardKeepsNoText() {
        var v = value("", 0)
        "hunter2".forEach { v = mimirApplySecretEdit(v, MimirSecretEdit.Insert(it.toString())) }
        assertEquals(value("hunter2", 7), v)
        v = mimirApplySecretEdit(mimirApplySecretEdit(v, MimirSecretEdit.CaretLeft), MimirSecretEdit.CaretLeft)
        v = mimirApplySecretEdit(v, MimirSecretEdit.Insert("•"))
        assertEquals("a bullet is just a character", value("hunte•r2", 6), v)
        v = mimirApplySecretEdit(v, MimirSecretEdit.DeleteBackward)
        assertEquals(value("hunter2", 5), v)
        assertEquals(
            "a backspace at the start deletes nothing",
            value("hunter2", 0),
            mimirApplySecretEdit(value("hunter2", 0), MimirSecretEdit.DeleteBackward),
        )
    }

    @Test
    fun aSelectionIsReplacedOrDeletedAndACaretKeyCollapsesItToItsEdge() {
        val deleted = mimirApplySecretEdit(value("abcd", 1, 3), MimirSecretEdit.DeleteBackward)
        assertEquals("ad", deleted.text)
        assertEquals(1, deleted.selection.start)
        assertEquals("axd", mimirApplySecretEdit(value("abcd", 1, 3), MimirSecretEdit.Insert("x")).text)
        assertEquals(1, mimirApplySecretEdit(value("abcd", 1, 3), MimirSecretEdit.CaretLeft).selection.start)
        assertEquals(3, mimirApplySecretEdit(value("abcd", 1, 3), MimirSecretEdit.CaretRight).selection.start)
        // The owner's own selection is never clamped: only the drawn dots are.
        val far = mimirApplySecretEdit(value("x".repeat(3000), 1001, 1005), MimirSecretEdit.DeleteBackward)
        assertEquals(3000 - 4, far.text.length)
    }

    @Test
    fun aDeleteOrCaretMoveStepsOverASurrogatePairAsOneCharacter() {
        val text = "a\uD83D\uDE00b"
        assertEquals("ab", mimirApplySecretEdit(value(text, 3), MimirSecretEdit.DeleteBackward).text)
        assertEquals(1, mimirApplySecretEdit(value(text, 3), MimirSecretEdit.CaretLeft).selection.start)
        assertEquals(3, mimirApplySecretEdit(value(text, 1), MimirSecretEdit.CaretRight).selection.start)
    }
}
