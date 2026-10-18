package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bottom overlay coverage: the overlay transforms the Bottom's pickers, dialogs and instruments host run, on a paused clock
 * (autoAdvance off, a state write then [Snapshot.sendApplyNotifications], frames of 16 ms), so the lengths the receipts name are measured
 * rather than assumed. The measured transition is the one a `BottomUiTransitionProbe` reads in production: the CURRENT TARGET's own
 * `transition` (`currentState != targetState`), which on a close is the empty page that arrives in the overlay's place.
 *
 * - Swap tier, arrival: the 130 ms lead then 180 ms, 310 ms. Close: 180 ms.
 * - Overlay tier, arrival: the 130 ms lead then 540 ms, 670 ms. Close: 180 ms.
 * - A close never holds the dying page past its own clock (no 130 ms lead on nothing, no delayed resize).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirOverlayMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private val target = mutableStateOf<String?>(null)
    private val children = mutableMapOf<String?, Transition<EnterExitState>>()

    private fun host(spec: AnimatedContentTransitionScope<String?>.() -> ContentTransform) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AnimatedContent(targetState = target.value, transitionSpec = spec, label = "overlayMotionTest") { shown ->
                children[shown] = transition
                if (shown != null) Box(Modifier.size(40.dp).testTag("page-$shown"))
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun flip(to: String?) {
        target.value = to
        Snapshot.sendApplyNotifications()
    }

    /** Frames until [shown]'s own transition settles, from the frame the state write lands in. Null if it never settles. */
    private fun msToSettle(shown: String?): Long? {
        var elapsed = 0L
        while (elapsed <= 2_000L) {
            compose.mainClock.advanceTimeByFrame()
            elapsed += FrameMs
            val t = children[shown] ?: continue
            if (t.currentState == t.targetState) return elapsed
        }
        return null
    }

    private fun pagesOnScreen(): Int = compose.onAllNodesWithTag("page-a").fetchSemanticsNodes().size

    /** A measured settle runs a few frames past the spec: the state write lands a frame before the clock starts and the transition reports rest a frame after its last tick (40 to 44 ms on a 16 ms frame). */
    private fun inRange(label: String, expected: Long, actual: Long?, below: Long = 12L, above: Long = 64L) {
        println("B1-MOTION $label expected=$expected measured=$actual")
        assertTrue("$label: expected about $expected ms, measured $actual", actual != null && actual >= expected - below && actual <= expected + above)
    }

    @Test
    fun `the swap tier opens in 310 ms and closes in 180 ms`() {
        host { mimirSwapOverlayTransform(reducedMotion = false, arriving = targetState != null) }
        flip("a")
        inRange("swap open", (MimirMotion.OverlayDissolveCoverMs + MimirMotion.OverlayMs).toLong(), msToSettle("a"))
        assertEquals("the page is on screen once open", 1, pagesOnScreen())
        flip(null)
        inRange("swap close", MimirMotion.OverlayMs.toLong(), msToSettle(null))
        compose.mainClock.advanceTimeBy(FrameMs * 2)
        assertEquals("the page is gone once the close has settled", 0, pagesOnScreen())
    }

    @Test
    fun `a swap close keeps the dying page for its own clock and not for the lead`() {
        host { mimirSwapOverlayTransform(reducedMotion = false, arriving = targetState != null) }
        flip("a")
        msToSettle("a")
        flip(null)
        compose.mainClock.advanceTimeBy(96L)
        assertEquals("the page is still composed 96 ms into its 180 ms close", 1, pagesOnScreen())
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + FrameMs * 2 - 96L)
        assertEquals("and gone shortly after 180 ms, well before a 310 ms lead-and-fade would have let it go", 0, pagesOnScreen())
    }

    @Test
    fun `the overlay tier opens in 670 ms and closes in 180 ms`() {
        host { mimirOverlayContentTransform(reducedMotion = false, arriving = targetState != null) }
        flip("a")
        inRange("overlay open", (MimirPageMotion.LeadMs + MimirPageMotion.ArriveMs).toLong(), msToSettle("a"))
        flip(null)
        inRange("overlay close", MimirMotion.OverlayMs.toLong(), msToSettle(null))
        compose.mainClock.advanceTimeBy(FrameMs * 2)
        assertEquals("the page is gone once the close has settled", 0, pagesOnScreen())
    }

    @Test
    fun `reduced motion opens at once on both tiers`() {
        host { mimirSwapOverlayTransform(reducedMotion = true, arriving = targetState != null) }
        flip("a")
        val swapOpen = msToSettle("a")
        println("B1-MOTION swap open under reduced motion measured=$swapOpen")
        assertTrue("a swap overlay opens within a frame or two under reduced motion: $swapOpen", swapOpen != null && swapOpen <= FrameMs * 2)
    }

    private companion object {
        const val FrameMs = 16L
    }
}
