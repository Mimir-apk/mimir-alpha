package com.gruning.mimir.rewrite.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hints must not snap when focus changes. Departing words are pixels only; arriving words are the
 * only composition from the first frame of the swap. Reproduced on RG Rotate, 2026-09-28.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirHintSwapTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * With the clock paused, a state write reaches the recomposer only once the snapshot's apply notifications
     * go out -- a plain write, even on the UI thread, is not seen at all (a Text-only control showed the same).
     */
    private fun setOnUiThread(write: () -> Unit) = compose.runOnUiThread {
        write()
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `a change of words leaves only the new words in the tree, through the whole swap`() {
        var words by mutableStateOf("A mark · B back")
        compose.setContent { MimirHintSwap(key = words) { Text(words) } }
        compose.onNodeWithText("A mark · B back").assertExists()

        compose.mainClock.autoAdvance = false
        setOnUiThread { words = "A unmark · B back" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("A unmark · B back").assertExists()
        compose.onNodeWithText("A mark · B back").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs / 2L)
        compose.onNodeWithText("A unmark · B back").assertExists()
        compose.onNodeWithText("A mark · B back").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong())
        compose.onNodeWithText("A unmark · B back").assertExists()
    }

    @Test
    fun `a change mid-swap and a change back both land on the latest words`() {
        var words by mutableStateOf("A open")
        compose.setContent { MimirHintSwap(key = words) { Text(words) } }
        compose.mainClock.autoAdvance = false
        setOnUiThread { words = "Y edit" }
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs / 3L)
        setOnUiThread { words = "A open" }
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs / 3L)
        setOnUiThread { words = "X pin" }
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 2L)
        compose.onNodeWithText("X pin").assertExists()
        compose.onNodeWithText("A open").assertDoesNotExist()
        compose.onNodeWithText("Y edit").assertDoesNotExist()
    }

    @Test
    fun `the departing frame keeps the edge its anchor names`() {
        val space = IntSize(100, 20)
        // A wider departing hint pinned right overhangs to the left only.
        assertEquals(
            IntOffset(-40, 0),
            mimirHintSwapOutgoingOffset(IntSize(140, 20), space, Alignment.CenterEnd, LayoutDirection.Ltr),
        )
        // A narrower one pinned right keeps the right edge.
        assertEquals(
            IntOffset(40, 0),
            mimirHintSwapOutgoingOffset(IntSize(60, 20), space, Alignment.CenterEnd, LayoutDirection.Ltr),
        )
        // Centred: the overhang splits evenly.
        assertEquals(
            IntOffset(-20, 0),
            mimirHintSwapOutgoingOffset(IntSize(140, 20), space, Alignment.Center, LayoutDirection.Ltr),
        )
        // Start-anchored keeps the left edge.
        assertEquals(
            IntOffset(0, 0),
            mimirHintSwapOutgoingOffset(IntSize(140, 20), space, Alignment.CenterStart, LayoutDirection.Ltr),
        )
    }

    /** The first surface's hint glides out and in rather than snapping. */
    @Test
    fun `a gliding hint leaves toward the start and arrives from the end, in either direction`() {
        val ltr = LayoutDirection.Ltr
        assertEquals(0f, mimirHintGlideShiftPx(22f, gone = 0f, incoming = false, ltr), 1e-4f)
        assertEquals(-11f, mimirHintGlideShiftPx(22f, gone = 0.5f, incoming = false, ltr), 1e-4f)
        assertEquals(-22f, mimirHintGlideShiftPx(22f, gone = 1f, incoming = false, ltr), 1e-4f)
        assertEquals(22f, mimirHintGlideShiftPx(22f, gone = 1f, incoming = true, ltr), 1e-4f)
        assertEquals(0f, mimirHintGlideShiftPx(22f, gone = 0f, incoming = true, ltr), 1e-4f)
        assertEquals(22f, mimirHintGlideShiftPx(22f, gone = 1f, incoming = false, LayoutDirection.Rtl), 1e-4f)
        assertEquals(-22f, mimirHintGlideShiftPx(22f, gone = 1f, incoming = true, LayoutDirection.Rtl), 1e-4f)
        // No travel, no shift: the plain dissolve is untouched.
        assertEquals(0f, mimirHintGlideShiftPx(0f, gone = 0.5f, incoming = true, ltr), 1e-4f)
    }

    @Test
    fun `a gliding hint leaves only the new words in the tree, and settles after out plus in`() {
        var words by mutableStateOf("A Type")
        compose.setContent { MimirHintSwap(key = words, glide = 22.dp) { Text(words) } }
        compose.onNodeWithText("A Type").assertExists()
        compose.mainClock.autoAdvance = false
        setOnUiThread { words = "A Select" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("A Select").assertExists()
        compose.onNodeWithText("A Type").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 2L + 64L)
        compose.onNodeWithText("A Select").assertExists()
        compose.onNodeWithText("A Type").assertDoesNotExist()
    }
}
