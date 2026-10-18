package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirKeyboardEditingTest {
    @Test
    fun keyHeightFitsTheAvailablePanelAndClampsToKeyboardBounds() {
        assertEquals(
            MimirSpacing.minTarget,
            mimirKeyboardKeyHeight(400.dp, rows = 5, fixedOverhead = 80.dp, rowGap = 8.dp),
        )
        assertEquals(
            36.dp,
            mimirKeyboardKeyHeight(300.dp, rows = 5, fixedOverhead = 80.dp, rowGap = 8.dp),
        )
        assertEquals(
            34.dp,
            mimirKeyboardKeyHeight(200.dp, rows = 5, fixedOverhead = 80.dp, rowGap = 8.dp),
        )
    }

    @Test
    fun keyActivationEmitsExactlyOneHapticBeforeItsAction() {
        val events = mutableListOf<String>()

        activateMimirKeyboardKey(
            emitHaptic = { events += "haptic" },
            action = { events += "action" },
        )

        assertEquals(listOf("haptic", "action"), events)
    }

    @Test
    fun caretBlinkRunsOnlyWhenCollapsedAnimatableResumedAndAmbientActive() {
        fun runs(
            collapsed: Boolean = true,
            reducedMotion: Boolean = false,
            phase: AmbientMotionPhase = AmbientMotionPhase.INTERACTING,
            resumed: Boolean = true,
            paneLive: Boolean = true,
        ): Boolean = mimirKeyboardCaretBlinkRuns(
            selectionCollapsed = collapsed,
            reducedMotion = reducedMotion,
            ambientPhase = phase,
            lifecycleAtLeastResumed = resumed,
            paneLive = paneLive,
        )

        assertTrue(runs(phase = AmbientMotionPhase.INTERACTING))
        assertTrue(runs(phase = AmbientMotionPhase.RECENT))
        assertFalse(runs(collapsed = false))
        assertFalse(runs(reducedMotion = true))
        assertFalse(runs(phase = AmbientMotionPhase.IDLE))
        assertFalse(runs(resumed = false))
        assertFalse("D24: a keyboard in a parked pane runs no endless clock", runs(paneLive = false))
        assertFalse(
            runs(
                collapsed = false,
                reducedMotion = true,
                phase = AmbientMotionPhase.IDLE,
                resumed = false,
            ),
        )
        assertFalse(runs(collapsed = false, phase = AmbientMotionPhase.RECENT, resumed = true))
        assertFalse(runs(reducedMotion = true, phase = AmbientMotionPhase.INTERACTING, resumed = true))
        assertFalse(runs(phase = AmbientMotionPhase.IDLE, resumed = true))
        assertFalse(runs(phase = AmbientMotionPhase.RECENT, resumed = false))
    }

    @Test
    fun insert_placesTextAtCaret() {
        val result = insertAtMimirKeyboardSelection(value("Reto Classics", 3), "r")

        assertEdit(result, text = "Retro Classics", selection = TextRange(4))
    }

    @Test
    fun insert_supportsBeginningAndEnd() {
        val atBeginning = insertAtMimirKeyboardSelection(value("etro", 0), "R")
        val atEnd = insertAtMimirKeyboardSelection(value("Retro", 5), "!")

        assertEdit(atBeginning, text = "Retro", selection = TextRange(1))
        assertEdit(atEnd, text = "Retro!", selection = TextRange(6))
    }

    @Test
    fun insert_replacesForwardSelection() {
        val result = insertAtMimirKeyboardSelection(value("abcXYZdef", 3, 6), "-")

        assertEdit(result, text = "abc-def", selection = TextRange(4))
    }

    @Test
    fun insert_replacesReversedSelection() {
        val result = insertAtMimirKeyboardSelection(value("abcXYZdef", 6, 3), "-")

        assertEdit(result, text = "abc-def", selection = TextRange(4))
    }

    @Test
    fun insert_emptyTextDeletesSelection() {
        val result = insertAtMimirKeyboardSelection(value("Retro Classics", 5, 14), "")

        assertEdit(result, text = "Retro", selection = TextRange(5))
    }

    @Test
    fun insert_advancesCaretByUtf16Length() {
        val result = insertAtMimirKeyboardSelection(value("AB", 1), "\uD83D\uDE00")

        assertEdit(result, text = "A\uD83D\uDE00B", selection = TextRange(3))
    }

    @Test
    fun insert_atInvalidSurrogateOffsetMovesBeforeCodePoint() {
        val result = insertAtMimirKeyboardSelection(value("A\uD83D\uDE00B", 2), "X")

        assertEdit(result, text = "AX\uD83D\uDE00B", selection = TextRange(2))
    }

    @Test
    fun insert_clearsComposition() {
        val editing = TextFieldValue(
            text = "Retro",
            selection = TextRange(5),
            composition = TextRange(0, 5),
        )

        val result = insertAtMimirKeyboardSelection(editing, "!")

        assertEdit(result, text = "Retro!", selection = TextRange(6))
        assertNull(result.composition)
    }

    @Test
    fun backspace_deletesCodeUnitBeforeMiddleCaret() {
        val result = backspaceAtMimirKeyboardSelection(value("Retro", 3))

        assertEdit(result, text = "Rero", selection = TextRange(2))
    }

    @Test
    fun backspace_deletesForwardAndReversedSelections() {
        val forward = backspaceAtMimirKeyboardSelection(value("Retro Classics", 5, 14))
        val reversed = backspaceAtMimirKeyboardSelection(value("Retro Classics", 14, 5))

        assertEdit(forward, text = "Retro", selection = TextRange(5))
        assertEdit(reversed, text = "Retro", selection = TextRange(5))
    }

    @Test
    fun backspace_atBeginningIsNoOp() {
        val editing = value("Retro", 0)

        assertEquals(editing, backspaceAtMimirKeyboardSelection(editing))
    }

    @Test
    fun backspace_deletesWholeSupplementaryCodePoint() {
        val result = backspaceAtMimirKeyboardSelection(value("A\uD83D\uDE00B", 3))

        assertEdit(result, text = "AB", selection = TextRange(1))
    }

    @Test
    fun cursorPlacementClampsAndNeverSplitsSurrogatePair() {
        val editing = value("A\uD83D\uDE00B", 0)

        assertEquals(TextRange(0), placeMimirKeyboardCursor(editing, -20).selection)
        assertEquals(TextRange(1), placeMimirKeyboardCursor(editing, 2).selection)
        assertEquals(TextRange(4), placeMimirKeyboardCursor(editing, 99).selection)
    }

    @Test
    fun selectionExpandsAcrossSurrogatePairAndKeepsDirection() {
        val editing = value("A\uD83D\uDE00B", 0)

        assertEquals(TextRange(1, 3), selectMimirKeyboardText(editing, start = 2, end = 3).selection)
        assertEquals(TextRange(3, 1), selectMimirKeyboardText(editing, start = 3, end = 2).selection)
    }

    @Test
    fun synchronize_preservesSelectionWhenTextMatches() {
        val editing = value("Retro Classics", 2, 7)

        assertEquals(editing, synchronizeMimirKeyboardText(editing, "Retro Classics"))
    }

    @Test
    fun synchronize_movesCaretToEndWhenExternalTextChanges() {
        val editing = value("Retro", 2)

        val result = synchronizeMimirKeyboardText(editing, "Classics")

        assertEdit(result, text = "Classics", selection = TextRange(8))
    }

    @Test
    fun deleteRepeatCadenceAcceleratesOnlyAfterTheLockedBoundary() {
        assertEquals(400L, MimirKeyboardDeleteFirstRepeatDelayMs)
        assertEquals(110L, mimirKeyboardDeleteRepeatIntervalMillis(400L))
        assertEquals(110L, mimirKeyboardDeleteRepeatIntervalMillis(1_200L))
        assertEquals(55L, mimirKeyboardDeleteRepeatIntervalMillis(1_201L))
    }

    @Test
    fun repeatedDeleteConsumesTheLatestSelectionAndKeepsSupplementaryCodePointsWhole() {
        var latest = value("A\uD83D\uDE00BCD", 4, 6)

        latest = backspaceAtMimirKeyboardSelection(latest)
        assertEdit(latest, text = "A\uD83D\uDE00B", selection = TextRange(4))
        latest = backspaceAtMimirKeyboardSelection(latest)
        assertEdit(latest, text = "A\uD83D\uDE00", selection = TextRange(3))
        latest = backspaceAtMimirKeyboardSelection(latest)
        assertEdit(latest, text = "A", selection = TextRange(1))
        latest = backspaceAtMimirKeyboardSelection(latest)
        assertEdit(latest, text = "", selection = TextRange(0))
        assertEquals(latest, backspaceAtMimirKeyboardSelection(latest))
    }

    @Test
    fun pasteSupportsBeginningMiddleAndEnd() {
        val atBeginning = pasteAtMimirKeyboardSelection(value("BC", 0), "A")
        val inMiddle = pasteAtMimirKeyboardSelection(value("AC", 1), "B")
        val atEnd = pasteAtMimirKeyboardSelection(value("AB", 2), "C")

        assertEdit(atBeginning, text = "ABC", selection = TextRange(1))
        assertEdit(inMiddle, text = "ABC", selection = TextRange(2))
        assertEdit(atEnd, text = "ABC", selection = TextRange(3))
    }

    @Test
    fun pasteReplacesEitherSelectionDirectionVerbatimAndClearsComposition() {
        val editing = TextFieldValue(
            text = "AxxB",
            selection = TextRange(3, 1),
            composition = TextRange(0, 4),
        )
        val pasted = pasteAtMimirKeyboardSelection(editing, " \uD83D\uDE00\n ")

        assertEdit(
            pasted,
            text = "A \uD83D\uDE00\n B",
            selection = TextRange(6),
        )
        assertEdit(
            pasteAtMimirKeyboardSelection(value("AxxB", 1, 3), "\uD83D\uDE00"),
            text = "A\uD83D\uDE00B",
            selection = TextRange(3),
        )
    }

    @Test
    fun nullOrEmptyClipboardIsANoOp() {
        val editing = TextFieldValue(
            text = "Retro",
            selection = TextRange(2, 4),
            composition = TextRange(0, 5),
        )

        assertEquals(editing, pasteAtMimirKeyboardSelection(editing, null))
        assertEquals(editing, pasteAtMimirKeyboardSelection(editing, ""))
    }

    @Test
    fun spaceDragUsesSignedSixteenDpStepsWithRemainderAndReversal() {
        val editing = value("ABCDE", 2)

        assertEquals(editing, moveMimirKeyboardCursorBySpaceDrag(editing, 15.99f))
        assertEquals(TextRange(3), moveMimirKeyboardCursorBySpaceDrag(editing, 16f).selection)
        assertEquals(TextRange(3), moveMimirKeyboardCursorBySpaceDrag(editing, 31.99f).selection)
        assertEquals(TextRange(4), moveMimirKeyboardCursorBySpaceDrag(editing, 32f).selection)
        assertEquals(TextRange(1), moveMimirKeyboardCursorBySpaceDrag(editing, -16f).selection)
        assertEquals(TextRange(0), moveMimirKeyboardCursorBySpaceDrag(editing, -32f).selection)
    }

    @Test
    fun spaceDragClampsAndNeverSplitsSupplementaryCodePoints() {
        val editing = value("A\uD83D\uDE00B", 1)

        assertEquals(TextRange(3), moveMimirKeyboardCursorBySpaceDrag(editing, 16f).selection)
        assertEquals(TextRange(4), moveMimirKeyboardCursorBySpaceDrag(editing, 32f).selection)
        assertEquals(TextRange(4), moveMimirKeyboardCursorBySpaceDrag(editing, 160f).selection)
        assertEquals(TextRange(0), moveMimirKeyboardCursorBySpaceDrag(editing, -160f).selection)
    }

    @Test
    fun firstSpaceDragStepCollapsesSelectionTowardTravelWithoutMovingPastIt() {
        val forward = value("ABCDE", 1, 4)
        val reversed = value("ABCDE", 4, 1)

        assertEquals(TextRange(4), moveMimirKeyboardCursorBySpaceDrag(forward, 16f).selection)
        assertEquals(TextRange(1), moveMimirKeyboardCursorBySpaceDrag(forward, -16f).selection)
        assertEquals(TextRange(4), moveMimirKeyboardCursorBySpaceDrag(reversed, 16f).selection)
        assertEquals(TextRange(1), moveMimirKeyboardCursorBySpaceDrag(reversed, -16f).selection)
        assertEquals(TextRange(5), moveMimirKeyboardCursorBySpaceDrag(forward, 32f).selection)
        assertEquals(TextRange(0), moveMimirKeyboardCursorBySpaceDrag(forward, -32f).selection)
    }

    @Test
    fun emittedSpaceDragChangesOnlySelectionAndClearsComposition() {
        val editing = TextFieldValue(
            text = "Retro",
            selection = TextRange(2),
            composition = TextRange(0, 5),
        )
        val moved = moveMimirKeyboardCursorBySpaceDrag(editing, 16f)

        assertEdit(moved, text = "Retro", selection = TextRange(3))
        assertEquals(editing, moveMimirKeyboardCursorBySpaceDrag(editing, 0f))
    }

    @Test
    fun spaceDragSessionEmitsReversalToOriginAndSuppressesDuplicateBuckets() {
        var session = MimirKeyboardSpaceDragSession(value("ABCDE", 2))

        fun advance(distanceDp: Float): TextFieldValue? {
            val update = advanceMimirKeyboardSpaceDrag(session, distanceDp)
            session = update.session
            return update.value
        }

        assertEquals(TextRange(3), advance(16f)?.selection)
        assertNull(advance(31f))
        assertEquals(TextRange(2), advance(8f)?.selection)
        assertEquals(TextRange(1), advance(-16f)?.selection)
        assertEquals(TextRange(2), advance(0f)?.selection)
    }

    @Test
    fun spaceDragSessionNeverRestoresASelectionAfterItsFirstCollapse() {
        var session = MimirKeyboardSpaceDragSession(value("ABCDE", 1, 4))

        fun advance(distanceDp: Float): TextFieldValue? {
            val update = advanceMimirKeyboardSpaceDrag(session, distanceDp)
            session = update.session
            return update.value
        }

        assertEquals(TextRange(4), advance(16f)?.selection)
        assertNull(advance(31f))
        assertNull(advance(8f))
        assertEquals(TextRange(1), advance(-16f)?.selection)
        assertNull(advance(0f))
    }

    @Test
    fun twoDimensionalTouchSlopSuppressesVerticalSwipeTapClassification() {
        assertTrue(!mimirKeyboardGestureCrossedTouchSlop(totalX = 3f, totalY = 4f, touchSlop = 5f))
        assertTrue(mimirKeyboardGestureCrossedTouchSlop(totalX = 0f, totalY = 5.1f, touchSlop = 5f))
        assertTrue(mimirKeyboardGestureCrossedTouchSlop(totalX = 5.1f, totalY = 0f, touchSlop = 5f))
    }

    private fun value(text: String, start: Int, end: Int = start): TextFieldValue =
        TextFieldValue(text = text, selection = TextRange(start, end))

    private fun assertEdit(actual: TextFieldValue, text: String, selection: TextRange) {
        assertEquals(text, actual.text)
        assertEquals(selection, actual.selection)
        assertNull(actual.composition)
    }
}
