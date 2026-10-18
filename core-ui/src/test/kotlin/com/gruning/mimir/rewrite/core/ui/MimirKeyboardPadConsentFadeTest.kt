package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.center
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-33 (audit 2026-10-14, X-65 / X-73): the Trackpad key, with Mimir's accessibility service off, replaced the key rows with the
 * consent card in one frame. It is a crossfade now. Over the real keyboard: after the key is tapped the card and the rows are both
 * drawn for the leave (the rows are not cut), the leaving rows register nothing with the focus controller and take no touch, and
 * once the clock has run only the card is left. Nothing else on the keyboard moves: the tool/preview area above is outside the fade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirKeyboardPadConsentFadeTest {

    @get:Rule
    val compose = createComposeRule()

    private class OffHost : MimirTrackpadHost {
        override val serviceConnected = false
        override fun begin(): MimirTrackpadHost = this
        override fun pointerMove(dx: Float, dy: Float) = Unit
        override fun click() = Unit
        override fun doubleClick() = Unit
        override fun scrollBy(dx: Float, dy: Float) = Unit
        override fun scrollEnd() = Unit
        override fun scrollCancel() = Unit
        override fun holdBegin() = Unit
        override fun holdMove(dx: Float, dy: Float) = Unit
        override fun holdEnd() = Unit
        override fun openAccessibilitySettings() = Unit
        override fun end() = Unit
    }

    private val typed = mutableListOf<String>()
    private val liveKeys = mutableSetOf<String>()

    /** A registering decorator, as the real one is: a key that is decorated is a key in the authority. */
    private val decorator: MimirKeyboardKeyDecorator = { id, _ ->
        DisposableEffect(id.value) {
            liveKeys += id.value
            onDispose { liveKeys -= id.value }
        }
        MimirKeyboardKeyDecoration(Modifier, false)
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize()) {
                    MimirKeyboard(
                        value = "",
                        onValueChange = { typed += it },
                        onDone = {},
                        trackpad = OffHost(),
                        controller = MimirKeyboardController(onEnter = {}, onExit = {}, onEnterAt = {}),
                        keyDecorator = decorator,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        step(0L)
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun rows() = compose.onAllNodesWithContentDescription("Q", useUnmergedTree = true)
    private fun card() = compose.onAllNodesWithContentDescription(MimirTrackpadCopy.Title, useUnmergedTree = true)

    /** The card is drawn [count] times; a miss says what the tree held instead. */
    private fun assertCard(count: Int) {
        val found = card().fetchSemanticsNodes().size
        val tree = if (found == count) "" else compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 14)
        assertEquals("consent card nodes; tree: $tree", count, found)
    }

    @Test
    fun `the consent card crossfades with the rows, which are inert and unregistered while they leave`() {
        mount()
        rows().assertCountEquals(1)
        card().assertCountEquals(0)
        assertTrue("the rows are focus targets before the tap: ${liveKeys.size}", liveKeys.size > 25)

        compose.onNodeWithContentDescription("Trackpad").performClick()
        // Two frames from the tap to the card's first drawn frame: the recomposition, then the transition's own.
        step(32L)
        assertCard(1)
        rows().assertCountEquals(1)
        assertEquals("a leaving row registers nothing with the focus controller", 0, liveKeys.size)

        step(58L)
        card().assertCountEquals(1)
        rows().assertCountEquals(1)
        val before = typed.size
        compose.onNodeWithContentDescription("Q", useUnmergedTree = true).performTouchInput { down(center); up() }
        step(0L)
        assertEquals("a key cannot be tapped through its own fade", before, typed.size)

        step(MimirMotion.OverlayMs.toLong() + 64L)
        rows().assertCountEquals(0)
        card().assertCountEquals(1)
    }

    @Test
    fun `leaving the card brings the rows back`() {
        mount()
        compose.onNodeWithContentDescription("Trackpad").performClick()
        step(MimirMotion.OverlayMs.toLong() + 64L)
        rows().assertCountEquals(0)
        compose.onNodeWithContentDescription(MimirTrackpadCopy.NotNow).performClick()
        step(32L)
        rows().assertCountEquals(1)
        step(MimirMotion.OverlayMs.toLong() + 64L)
        rows().assertCountEquals(1)
        card().assertCountEquals(0)
        assertTrue("the rows are live again: ${liveKeys.size}", liveKeys.size > 25)
    }
}
