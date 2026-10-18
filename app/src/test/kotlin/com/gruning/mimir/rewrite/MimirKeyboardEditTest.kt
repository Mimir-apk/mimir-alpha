package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MimirKeyboard` reports a WHOLE value on every key; an `InputConnection` takes EDITS. These pin the
 * reconciliation, which decides whether an ordinary keystroke costs one `commitText` of one character
 * or a retype of the whole line.
 *
 * Carried over from the reverted injection surface, which needed the identical shape for a different
 * pair of verbs — `deleteSurroundingText` + `commitText` here, backspace keyevents and `input text`
 * there. The reconciliation was the one part of that work worth keeping.
 */
class MimirKeyboardEditTest {

    @Test
    fun anOrdinaryKeystrokeCommitsOneCharacterAndDeletesNothing() {
        val edit = mimirKeyboardEdit("mimi", "mimir")
        assertEquals(0, edit.deleteBefore)
        assertEquals("r", edit.commit)
    }

    @Test
    fun theFirstKeystrokeOnAnEmptyFieldCommitsOnlyItself() {
        val edit = mimirKeyboardEdit("", "m")
        assertEquals(0, edit.deleteBefore)
        assertEquals("m", edit.commit)
    }

    @Test
    fun aDeletionDeletesAndCommitsNothing() {
        assertEquals(MimirKeyboardEdit(1, ""), mimirKeyboardEdit("mimir", "mimi"))
        assertEquals(MimirKeyboardEdit(5, ""), mimirKeyboardEdit("mimir", ""))
    }

    @Test
    fun aMidWordCorrectionDoesBothAndTheDeleteMustComeFirst() {
        // "mimir" -> "mimic": shared prefix "mimi", so delete one then commit "c". The caret is at the
        // end and has nothing to seek with, so committing before deleting would land the text in the
        // wrong place.
        assertEquals(MimirKeyboardEdit(1, "c"), mimirKeyboardEdit("mimir", "mimic"))
    }

    @Test
    fun anUnchangedValueTouchesTheFieldAtAll() {
        assertTrue(mimirKeyboardEdit("mimir", "mimir").isEmpty)
        assertTrue(mimirKeyboardEdit("", "").isEmpty)
    }

    @Test
    fun aWholesaleReplacementRetypesOnlyWhatDiverged() {
        assertEquals(MimirKeyboardEdit(3, "xyz"), mimirKeyboardEdit("abc", "xyz"))
    }

    @Test
    fun itNeverDeletesMoreThanTheFieldWasBelievedToHold() {
        // The property that keeps this from eating text the player did not type in this session: the
        // service seeds `typed` from a BOUNDED read of the field, and a delete can never exceed it.
        listOf("" to "abc", "a" to "", "abc" to "", "abcdef" to "abc", "x" to "yz")
            .forEach { (previous, next) ->
                val edit = mimirKeyboardEdit(previous, next)
                assertTrue(
                    "<$previous> -> <$next> deleted ${edit.deleteBefore} of ${previous.length}",
                    edit.deleteBefore <= previous.length,
                )
            }
    }

    @Test
    fun theEditAlwaysReconstructsTheTargetValue() {
        val cases = listOf(
            "" to "hello", "hello" to "", "hello" to "hello world", "hello world" to "hello",
            "abc" to "abd", "aaa" to "aa", "aa" to "aaa", "search" to "searching", "x" to "y",
            "mimir" to "MIMIR", "  " to " ", "a" to "a",
        )
        cases.forEach { (previous, next) ->
            val edit = mimirKeyboardEdit(previous, next)
            val applied = previous.dropLast(edit.deleteBefore) + edit.commit
            assertEquals("<$previous> -> <$next> must reconstruct", next, applied)
        }
    }
}
