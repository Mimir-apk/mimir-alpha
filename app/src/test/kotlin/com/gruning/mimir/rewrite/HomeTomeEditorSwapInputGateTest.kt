package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Alpha audit F1c (2026-09-30), input gating for the Tome library's editor: the swap keeps the arriving editor at alpha 0
 * through its 130 ms lead while its keyboard is already live and focused on the landing key, so an A inside the lead typed
 * "q" into an empty name. The pad's half is [MimirTransitionInputGate], which the input router honours (pinned by
 * PageChangeInputGateTest); the router is not stood up here, so "A at 100 ms types nothing" is proved as "the gate is held
 * at 100 ms". Touch bypasses the router, so its half is a real hit-tested tap on a key.
 *
 * Composes the real [HomeTomeLibraryOverlay] (an empty library is enough: a browser page and an editor page) under
 * Robolectric with native graphics on a paused clock, the harness of HomeTileMenuSwapInputGateTest. The keyboard's
 * ordinary keys read a consumed pointer as not theirs; its Backspace and Space keys do not (`requireUnconsumed = false`),
 * so this pins the letter keys only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTomeEditorSwapInputGateTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(HomeTomeLibraryState(entries = emptyList(), invokingEntry = null))

    /** What the editor was told to write, in order. */
    private val drafts = mutableListOf<String>()

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    @Test
    fun `the browser the library opens on holds no gate`() {
        composeLibrary()
        assertFalse("an open library is not a page change", MimirTransitionInputGate.holding)
    }

    @Test
    fun `at 100 ms into browser to editor the pad is gated and a tap on a key types nothing`() {
        composeLibrary()
        openEditor()
        advance(100L - FrameMs)
        assertTrue("an A at 100 ms must not type the landing key", MimirTransitionInputGate.holding)
        assertEquals(
            "a tap on the editor's key while it is at alpha 0 must reach nothing",
            emptyList<String>(),
            tapKey("Q"),
        )
    }

    @Test
    fun `the editor takes a tap once it paints, and the pad is free`() {
        composeLibrary()
        openEditor()
        advance(SwapLeadMs + 60L)
        assertFalse("the pad is free once the editor shows", MimirTransitionInputGate.holding)
        settle()
        assertEquals("and a key types", listOf("Q"), tapKey("Q"))
    }

    @Test
    fun `B inside the lead frees the pad at once`() {
        composeLibrary()
        openEditor()
        advance(50L)
        assertTrue(MimirTransitionInputGate.holding)
        closeEditor()
        compose.mainClock.advanceTimeByFrame()
        assertFalse("a reversed swap must not leave the pad deaf", MimirTransitionInputGate.holding)
    }

    @Test
    fun `B back to the browser from a settled editor frees the pad within a few frames`() {
        composeLibrary()
        openEditor()
        settle()
        closeEditor()
        advance(3 * FrameMs)
        assertFalse(
            "a return paints its page at once (review of 89d626d38), so the pad must not wait out the lead",
            MimirTransitionInputGate.holding,
        )
    }

    private fun composeLibrary() {
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                HomeTomeLibraryOverlay(
                    state = state,
                    authority = authority,
                    active = true,
                    onFirstVisibleIndex = {},
                    onFocusSettled = {},
                    onActivate = {},
                    onDraftChange = { drafts += it },
                    onDraftDone = {},
                    onDeleteAction = {},
                )
            }
        }
        settle()
    }

    private fun openEditor() {
        // A plain write from the test thread is not seen by a paused-clock recomposer until something idles.
        Snapshot.withMutableSnapshot { state = state.copy(draft = "") }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun closeEditor() {
        Snapshot.withMutableSnapshot { state = state.copy(draft = null) }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    private fun settle() = advance(MimirMotion.OverlayMs * 8L)

    private fun tapKey(label: String): List<String> {
        val before = drafts.size
        compose.onNodeWithContentDescription(label).performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        return drafts.drop(before)
    }

    private companion object {
        const val FrameMs = 16L

        /** When the arriving page starts to show: the swap tier's lead. */
        val SwapLeadMs = MimirMotion.OverlayDissolveCoverMs.toLong()
    }
}
