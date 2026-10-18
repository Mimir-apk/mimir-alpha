package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native ᛗ menu's receipts, driven by the three flows the controller publishes. The controller's own order is mirrored:
 * in flight first, then the visual value; at the end the swap stops, then the visual value settles.
 */
class CompanionBottomMenuReceiptsTest {
    private class FakeTimer(val dueMs: Long, val block: () -> Unit) { var cancelled = false }

    private var now = 0L
    private val lines = ArrayList<String>()
    private val timers = ArrayList<FakeTimer>()
    private val log = BottomUiReceiptLog(
        sink = { line, _ -> lines += line },
        nowMs = { now },
        timer = BottomUiReceiptTimer { delayMs, block ->
            val timer = FakeTimer(now + delayMs, block)
            timers += timer
            ({ timer.cancelled = true })
        },
    )
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val visual = MutableStateFlow(false)
    private val open = MutableStateFlow(false)
    private val swapping = MutableStateFlow(false)
    private var displayId: Int? = 4

    @After
    fun tearDown() = scope.cancel()

    private fun observe(): Job = observeBottomMenuReceipts(scope, log, visual, open, swapping) { displayId }

    private fun advance(ms: Long) {
        now += ms
        timers.filter { !it.cancelled && it.dueMs <= now }.forEach { it.cancelled = true; it.block() }
    }

    private fun beginOpening() {
        swapping.value = true
        visual.value = true
    }

    @Test
    fun theSwapEndsWhenTheIncomingWindowHasFinishedNotWhenTheMenuLandsHalfwayThrough() {
        observe()
        beginOpening()
        assertEquals(
            listOf(
                "ev=transition.begin name=m-menu.swap display=4",
                "ev=surface.enter surface=m-menu display=4 from=none transition=m-menu.swap",
            ),
            lines,
        )
        advance(90)
        open.value = true // the outgoing window is replaced: the OLD end signal
        assertEquals("landing halfway is not the end", 2, lines.size)
        advance(90)
        swapping.value = false // the incoming window's animation ended
        assertEquals("ev=transition.end name=m-menu.swap display=4 ms=180 result=done", lines.last())
        assertEquals(3, lines.size)
    }

    @Test
    fun anOpenMenuTornDownAfterTheTargetClearsStillWritesItsExit() {
        observe()
        beginOpening()
        open.value = true
        swapping.value = false
        // Unmount: the controller clears its active target BEFORE it publishes the close.
        displayId = null
        open.value = false
        visual.value = false
        assertEquals("ev=surface.exit surface=m-menu display=4 to=none transition=none", lines.last())
    }

    @Test
    fun aSwapCancelledByTheControllerEndsCancelledAndTheMenuExits() {
        observe()
        beginOpening()
        displayId = null
        swapping.value = false // cancelOverlayTransition: in flight clears first,
        visual.value = false // then the visual value settles back, and the menu never mounted
        assertTrue(lines.toString(), lines.contains("ev=transition.end name=m-menu.swap display=4 ms=0 result=cancelled"))
        assertEquals("ev=surface.exit surface=m-menu display=4 to=none transition=none", lines.last())
    }

    @Test
    fun cancellingTheObserverMidSwapEndsTheTransitionCancelledAndLeavesNothingToTimeOut() {
        val job = observe()
        beginOpening()
        advance(40)
        job.cancel()
        assertEquals("ev=transition.end name=m-menu.swap display=4 ms=40 result=cancelled", lines.last())
        val before = lines.size
        advance(10_000)
        assertEquals("no unfinished or missing watch fires after the observer is gone: $lines", before, lines.size)
        assertFalse(lines.any { it.startsWith("ev=transition.unfinished") || it.startsWith("ev=transition.missing") })
    }

    @Test
    fun aMenuThatIsNotOnTheBottomWritesNothing() {
        displayId = null
        observe()
        beginOpening()
        open.value = true
        swapping.value = false
        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun aMenuThatOpensWithNoSwapAnimationIsASurfaceEdgeWithNoTransition() {
        observe()
        visual.value = true
        open.value = true
        assertEquals(listOf("ev=surface.enter surface=m-menu display=4 from=none transition=none"), lines)
    }

    @Test
    fun aMainMenuThatOpensAfterTheBottomLostEligibilityIsNotReportedAsTheBottomsDisplay() {
        observe()
        visual.value = true
        open.value = true
        visual.value = false
        open.value = false
        assertEquals(
            listOf(
                "ev=surface.enter surface=m-menu display=4 from=none transition=none",
                "ev=surface.exit surface=m-menu display=4 to=none transition=none",
            ),
            lines,
        )
        // The Bottom stops being a target (single-display again); the next menu is Main's.
        displayId = null
        visual.value = true
        open.value = true
        visual.value = false
        open.value = false
        assertEquals("a Main menu writes nothing, and never names the old Bottom display: $lines", 2, lines.size)
    }

    @Test
    fun theNextOpenResolvesItsDisplayFreshInsteadOfReusingTheLastOne() {
        observe()
        visual.value = true
        visual.value = false
        displayId = 5
        visual.value = true
        assertEquals("ev=surface.enter surface=m-menu display=5 from=none transition=none", lines.last())
    }

    @Test
    fun cancellingAnObserverThatAttachedMidCloseStillEndsTheSwapOnTheDisplayItBeganOn() {
        // The observer first sees the swap already closing: visual false, swapping true. No visual=true was ever observed.
        swapping.value = true
        val job = observe()
        assertEquals(listOf("ev=transition.begin name=m-menu.swap display=4"), lines)
        advance(30)
        displayId = null // the controller's target is gone by the time the observer is cancelled
        job.cancel()
        assertEquals("ev=transition.end name=m-menu.swap display=4 ms=30 result=cancelled", lines.last())
        val before = lines.size
        advance(10_000)
        assertEquals("nothing is left to time out: $lines", before, lines.size)
        assertFalse(lines.any { it.startsWith("ev=transition.unfinished") })
    }
}
