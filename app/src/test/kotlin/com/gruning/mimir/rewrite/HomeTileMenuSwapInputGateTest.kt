package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
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
 * The tile menu's swap tier and the page-change input contract: presses during a page change are ignored until the
 * new page shows; Back and the shoulders always work.
 *
 * The swap keeps the arriving page at alpha 0 through its 130 ms lead while the reducer is already live. Before the fix
 * a second A, or a second tap on the Remove row still fading out, reached ConfirmRemove and removed the favourite before
 * "Remove favorite?" had painted. The pad's half is [MimirTransitionInputGate], which the input router honours (pinned
 * by PageChangeInputGateTest); the router is not stood up here, so "CONFIRM at 100 ms does nothing" is proved as "the
 * gate is held at 100 ms". Touch bypasses the router, so its half is proved by real hit-tested taps through the root.
 *
 * Composes the real [TileArtMenuOverlay] under Robolectric with native graphics on a paused clock, the harness of
 * UtilitySurfaceTransitionTouchTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTileMenuSwapInputGateTest {

    @get:Rule
    val compose = createComposeRule()

    private var phase by mutableStateOf<HomeTileMenuPhase>(HomeTileMenuPhase.Menu)

    /** What each tap reached, in order. */
    private val reached = mutableListOf<String>()

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    @Test
    fun `the page the menu opens on holds no gate and its Remove row answers`() {
        composeMenu()
        assertFalse("an open menu is not a page change", MimirTransitionInputGate.holding)
        assertEquals(listOf("menu:1"), tapAt(clickableCentre("Remove")))
    }

    @Test
    fun `at 100 ms into Menu to ConfirmRemove the pad is gated and a tap on the fading Remove row reaches nothing`() {
        composeMenu()
        val remove = clickableCentre("Remove")
        show(HomeTileMenuPhase.ConfirmRemove)
        advance(100L - FrameMs)
        assertTrue("CONFIRM at 100 ms must not reach ConfirmRemove", MimirTransitionInputGate.holding)
        assertEquals(
            "a second tap on the Remove row that is fading out must reach nothing",
            emptyList<String>(),
            tapAt(remove),
        )
    }

    @Test
    fun `the confirm row takes no tap until it paints, and answers once it has`() {
        composeMenu()
        show(HomeTileMenuPhase.ConfirmRemove)
        val start = compose.mainClock.currentTime
        advance(100L - FrameMs)
        assertEquals(
            "the arriving confirm row is at alpha 0 and must take no tap",
            emptyList<String>(),
            tapAt(clickableCentre("Remove favorite")),
        )
        advance(maxOf(0L, start + SwapLeadMs + 60L - compose.mainClock.currentTime))
        assertFalse("the pad is free once the new page shows", MimirTransitionInputGate.holding)
        settle()
        assertEquals(listOf("pick:0"), tapAt(clickableCentre("Remove favorite")))
    }

    @Test
    fun `B inside the lead frees the pad at once and the menu answers`() {
        composeMenu()
        show(HomeTileMenuPhase.ConfirmRemove)
        advance(50L)
        assertTrue(MimirTransitionInputGate.holding)
        show(HomeTileMenuPhase.Menu)
        compose.mainClock.advanceTimeByFrame()
        assertFalse("a reversed swap must not leave the pad deaf", MimirTransitionInputGate.holding)
        settle()
        assertEquals(listOf("menu:1"), tapAt(clickableCentre("Remove")))
    }

    @Test
    fun `B back to the menu from a settled page shows it at once and frees the pad within a few frames`() {
        composeMenu()
        show(HomeTileMenuPhase.ConfirmRemove)
        settle()
        show(HomeTileMenuPhase.Menu)
        advance(3 * FrameMs)
        assertFalse(
            "a return paints its page at once (review of 89d626d38), so the pad must not wait out the lead",
            MimirTransitionInputGate.holding,
        )
        settle()
        assertEquals(listOf("menu:1"), tapAt(clickableCentre("Remove")))
    }

    private fun composeMenu() {
        phase = HomeTileMenuPhase.Menu
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                TileArtMenuOverlay(
                    title = "Test Game",
                    options = listOf("Change icon", "Remove"),
                    menuFocus = 1,
                    phase = phase,
                    onMenuFocus = { reached += "menuFocus:$it" },
                    onActivateMenu = { reached += "menu:$it" },
                    onPickFocus = { reached += "pickFocus:$it" },
                    onActivatePick = { reached += "pick:$it" },
                    destructiveLabel = "Remove",
                )
            }
        }
        settle()
    }

    private fun show(target: HomeTileMenuPhase) {
        // A plain write from the test thread is not seen by a paused-clock recomposer until something idles.
        Snapshot.withMutableSnapshot { phase = target }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    private fun settle() = advance(MimirMotion.OverlayMs * 8L)

    /** The centre of the one clickable row labelled [label]. A page that is not live clears its semantics. */
    private fun clickableCentre(label: String): Offset {
        val matches = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            val words = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            words.any { it.trim().equals(label, ignoreCase = true) }
        }
        assertEquals("exactly one clickable row labelled $label", 1, matches.size)
        return matches.single().boundsInRoot.center
    }

    private fun tapAt(point: Offset): List<String> {
        val before = reached.size
        compose.onRoot().performTouchInput {
            down(point)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        return reached.drop(before)
    }

    private companion object {
        const val FrameMs = 16L

        /** When the arriving page starts to show: the swap tier's lead. */
        val SwapLeadMs = MimirMotion.OverlayDissolveCoverMs.toLong()
    }
}
