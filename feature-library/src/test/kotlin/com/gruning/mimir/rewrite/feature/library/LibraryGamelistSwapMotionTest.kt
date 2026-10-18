package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPageArrived
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import kotlinx.coroutines.CompletableDeferred
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

/**
 * The gamelist's L2/R2 platform switch as a swap (motion audit 2026-09-28, batch F): the old list leaves as recorded
 * pixels on 180 ms, gliding the other way, and the new one fades and glides in from the shoulder's side on 180 ms from
 * the 130 ms lead. Presses wait until it shows. No frame shows both above alpha 0.15. the old
 * list holds at rest until the new rows and centre covers settle, at most SettleCapMs, before any of that starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibraryGamelistSwapMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private val platform = mutableStateOf(PlatformId.SNES)
    private val motion = mutableStateOf<GamelistPlatformSwitchMotion?>(null)
    private val loaded = mutableStateOf(true)
    private val reduced = mutableStateOf(false)
    private val probe = LibraryPageProbe()
    private val settled = mutableListOf<GamelistPlatformSwitchMotion>()
    private var sequence = 0L
    private var uptime = 0L
    private val travel = MimirMotion.GlideTravelDp.toFloat()
    private var settle: (suspend () -> Unit)? = null

    /** What the content sees of [LocalMimirPageArrived], as the centred card's gleam does; recorded after each composition. */
    private var pageArrived = true
    private val viewMode = mutableStateOf(GamelistViewMode.CAROUSEL)

    @Before
    fun clock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { uptime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                GamelistDestinationPageTransition(
                    platformId = platform.value,
                    gamesLoaded = loaded.value,
                    gamesEmpty = false,
                    withinSwitchGrace = false,
                    motion = motion.value,
                    onMotionSettled = { settled += it },
                    settle = settle,
                    modifier = Modifier.size(100.dp),
                    reducedMotion = reduced.value,
                    probe = probe,
                ) {
                    Box(Modifier.size(100.dp))
                    val arrived = LocalMimirPageArrived.current
                    SideEffect { pageArrived = arrived }
                }
            }
        }
        frame()
    }

    /** The Carousel/List change on the same harness. Its view has no probe, so its arrival is read off the local alone. */
    private fun viewHost(reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                GamelistViewModeLandingTransition(
                    mode = viewMode.value,
                    modifier = Modifier.size(100.dp),
                    reducedMotion = reducedMotion,
                ) {
                    Box(Modifier.size(100.dp))
                    val arrived = LocalMimirPageArrived.current
                    SideEffect { pageArrived = arrived }
                }
            }
        }
        frame()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        uptime += 16
    }

    private fun run(ms: Long, each: (Long) -> Unit = {}) {
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start < ms) {
            frame()
            each(compose.mainClock.currentTime - start)
        }
    }

    /** The view model's own order: the motion first, then the platform, in one synchronous call. */
    private fun press(direction: Int, to: PlatformId = PlatformId.GBA) {
        motion.value = GamelistPlatformSwitchMotion(platformId = to, direction = direction, sequence = ++sequence)
        platform.value = to
        Snapshot.sendApplyNotifications()
        frame()
    }

    @Test
    fun `R2 sends the old list left as pixels and brings the new one in from the right, never both`() {
        host()
        assertEquals(1f, probe.incomingAlpha(), 0f)
        press(direction = 1)
        assertTrue("the old list starts fully on screen: ${probe.outgoingAlpha()}", probe.outgoingAlpha() > 0.9f)
        assertEquals("the new list starts hidden", 0f, probe.incomingAlpha(), 0f)
        assertEquals("from the right", travel, probe.incomingTravel(), 0.5f)
        assertTrue("presses wait", MimirTransitionInputGate.holding)
        var bothVisible = 0
        var gateOpenedAt = -1L
        var outgoingGoneAt = -1L
        run(500) { t ->
            val out = probe.outgoingAlpha()
            val incoming = probe.incomingAlpha()
            if (out > 0.15f && incoming > 0.15f) bothVisible++
            if (t < MimirMotion.OverlayDissolveCoverMs) assertEquals("hidden before the lead at $t", 0f, incoming, 0.001f)
            if (outgoingGoneAt < 0 && out == 0f) outgoingGoneAt = t
            if (gateOpenedAt < 0 && !MimirTransitionInputGate.holding) gateOpenedAt = t
        }
        assertEquals("frames with both lists above 0.15", 0, bothVisible)
        assertTrue("the old list is gone in about 180 ms: $outgoingGoneAt", outgoingGoneAt in 170L..240L)
        assertTrue("the old list went left: ${probe.outgoingTravel()}", probe.outgoingTravel() < -travel * 0.8f)
        assertTrue("presses reach the list once it shows: $gateOpenedAt", gateOpenedAt in 130L..220L)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals("at rest", 0f, probe.incomingTravel(), 0.01f)
        assertEquals("the switch token was consumed", 1, settled.size)
    }

    @Test
    fun `L2 mirrors it`() {
        host()
        press(direction = -1)
        assertEquals("from the left", -travel, probe.incomingTravel(), 0.5f)
        run(300)
        assertTrue("the old list went right: ${probe.outgoingTravel()}", probe.outgoingTravel() > travel * 0.8f)
    }

    @Test
    fun `a cold platform keeps the new list hidden until its rows land, and the pad waits with it`() {
        host()
        loaded.value = false
        press(direction = 1)
        run(300)
        assertEquals("nothing to show yet", 0f, probe.incomingAlpha(), 0f)
        assertTrue(MimirTransitionInputGate.holding)
        loaded.value = true
        Snapshot.sendApplyNotifications()
        run(400)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertFalse(MimirTransitionInputGate.holding)
        assertEquals(1, settled.size)
    }

    @Test
    fun `the old list holds at rest until the new rows and covers settle, then the swap runs`() {
        val covers = CompletableDeferred<Unit>()
        settle = { covers.await() }
        host()
        press(direction = 1)
        run(250) { t ->
            assertEquals("the old list is at rest at $t", 1f, probe.outgoingAlpha(), 0f)
            assertEquals("and in its seat at $t", 0f, probe.outgoingTravel(), 0f)
            assertEquals("the new list is hidden at $t", 0f, probe.incomingAlpha(), 0f)
        }
        assertTrue("presses wait", MimirTransitionInputGate.holding)
        covers.complete(Unit)
        var bothVisible = 0
        run(500) { _ -> if (probe.outgoingAlpha() > 0.15f && probe.incomingAlpha() > 0.15f) bothVisible++ }
        assertEquals("frames with both lists above 0.15", 0, bothVisible)
        assertEquals(0f, probe.outgoingAlpha(), 0f)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertFalse(MimirTransitionInputGate.holding)
        assertEquals(1, settled.size)
    }

    @Test
    fun `a platform that never settles swaps anyway at the cap, and the pad opens when the list shows`() {
        settle = { CompletableDeferred<Unit>().await() }
        host()
        loaded.value = false
        press(direction = 1)
        var exitStarted = -1L
        var shownAt = -1L
        var gateOpenedAt = -1L
        run(900) { t ->
            if (exitStarted < 0 && probe.outgoingAlpha() < 1f) exitStarted = t
            if (shownAt < 0 && probe.incomingAlpha() > 0f) shownAt = t
            if (gateOpenedAt < 0 && !MimirTransitionInputGate.holding) gateOpenedAt = t
        }
        val cap = MimirPageMotion.SettleCapMs.toLong()
        val lead = MimirPageMotion.LeadMs.toLong()
        assertTrue("the old list starts to leave at the cap: $exitStarted", exitStarted in (cap - 16)..(cap + 96))
        assertTrue("the new list shows a lead later: $shownAt", shownAt in (cap + lead)..(cap + lead + 96))
        assertTrue(
            "the pad opens as the list shows, not at the gate's own expiry: $gateOpenedAt",
            gateOpenedAt in shownAt..(shownAt + 16) && gateOpenedAt < MimirPageMotion.InputGateMaxMs,
        )
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals("the switch token was consumed", 1, settled.size)
    }

    /**
     * RG-2 (alpha audit 2026-09-30). The hold lapses 710 ms after it was taken, which is the cap, the lead and the exit
     * added up. A wait that ends late (a blocked main thread delays the cap's timer) leaves less than the lead and the
     * exit behind it, so a first frame a little slow let the pad reach a list still at alpha 0. Here the gate's uptime
     * runs 200 ms ahead of the frames before the cap fires, which is that stall.
     */
    @Test
    fun `a capped settle that ends 200 ms late still holds the pad until the list shows`() {
        settle = { CompletableDeferred<Unit>().await() }
        host()
        loaded.value = false
        press(direction = 1)
        var stalled = false
        var shownAt = -1L
        var gateOpenedAt = -1L
        var unseenAndOpen = -1L
        run(1_000) { t ->
            if (!stalled && t >= 100) {
                uptime += 200
                stalled = true
            }
            if (shownAt < 0 && probe.incomingAlpha() > 0f) shownAt = t
            val open = !MimirTransitionInputGate.holding
            if (gateOpenedAt < 0 && open) gateOpenedAt = t
            if (shownAt < 0 && open && unseenAndOpen < 0) unseenAndOpen = t
        }
        val cap = MimirPageMotion.SettleCapMs.toLong()
        val lead = MimirPageMotion.LeadMs.toLong()
        assertTrue("the list shows a lead after the cap: $shownAt", shownAt in (cap + lead)..(cap + lead + 96))
        assertEquals("the pad was open at $unseenAndOpen ms while the list was still at alpha 0", -1L, unseenAndOpen)
        assertTrue("and it opens as the list shows: $gateOpenedAt", gateOpenedAt in shownAt..(shownAt + 16))
        assertEquals(1f, probe.incomingAlpha(), 0f)
    }

    @Test
    fun `a second press before the first list showed skips the wait and the lead`() {
        host()
        loaded.value = false
        press(direction = 1)
        run(100)
        assertEquals("the first list has not shown", 0f, probe.incomingAlpha(), 0f)
        press(direction = 1, to = PlatformId.NES)
        var shownAt = -1L
        run(300) { t -> if (shownAt < 0 && probe.incomingAlpha() > 0f) shownAt = t }
        assertTrue("the newest list shows at once rather than after a held blank: $shownAt", shownAt in 0L..80L)
        assertFalse(MimirTransitionInputGate.holding)
        assertEquals(1f, probe.incomingAlpha(), 0f)
    }

    @Test
    fun `under reduced motion the switch is a cut and the token is still consumed`() {
        reduced.value = true
        host()
        press(direction = 1)
        frame()
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals(0f, probe.outgoingAlpha(), 0f)
        assertFalse(MimirTransitionInputGate.holding)
        run(100)
        assertEquals(1, settled.size)
    }

    /**
     * N1 (alpha audit 2026-09-30). Only the Library's page entry told its content when it had arrived, so on L2/R2 the
     * centred card's gleam started behind the held list and ran up to about 530 ms unseen. The local is false from the
     * press until the arrival has run its course, and true after.
     */
    @Test
    fun `on L2 R2 the local says the page has not arrived until its arrival has run, and then that it has`() {
        host()
        assertTrue("at rest a list has arrived", pageArrived)
        press(direction = 1)
        var settledAt = -1L
        var arrivedAt = -1L
        var arrivedEarly = -1L
        run(700) { t ->
            val settledAlpha = probe.incomingAlpha() == 1f
            if (settledAt < 0 && settledAlpha) settledAt = t
            if (arrivedAt < 0 && pageArrived) arrivedAt = t
            if (!settledAlpha && pageArrived && arrivedEarly < 0) arrivedEarly = t
        }
        assertEquals("the gleam was released at $arrivedEarly ms, behind a list still arriving", -1L, arrivedEarly)
        assertTrue("the arrival ran: $settledAt", settledAt > MimirPageMotion.LeadMs)
        assertTrue("and the gleam is released with it: $arrivedAt after $settledAt", arrivedAt in settledAt..(settledAt + 48))
        assertTrue(pageArrived)
    }

    @Test
    fun `on L2 R2 a cold platform keeps the local false through the whole wait`() {
        settle = { CompletableDeferred<Unit>().await() }
        host()
        loaded.value = false
        press(direction = 1)
        run(MimirPageMotion.SettleCapMs.toLong() + MimirPageMotion.LeadMs) { t ->
            assertFalse("the list is held at $t ms, and its gleam waits", pageArrived)
        }
        run(600)
        assertTrue("the arrival ran after the cap", pageArrived)
    }

    @Test
    fun `under reduced motion the local is true at once on L2 R2`() {
        reduced.value = true
        host()
        press(direction = 1)
        frame()
        assertTrue(pageArrived)
    }

    @Test
    fun `the Carousel List change holds the local false through its arrival and then releases it`() {
        viewHost()
        assertTrue("at rest a view has arrived", pageArrived)
        viewMode.value = GamelistViewMode.LIST
        Snapshot.sendApplyNotifications()
        frame()
        // One OverlayMs clock with no lead (RC-10, 2026-10-15): the gleam waits for the cross-fade, not for a 130 ms lead and a second fade.
        run(MimirMotion.OverlayMs - 32L) { t -> assertFalse("the view is still arriving at $t ms", pageArrived) }
        run(400)
        assertTrue("the arrival ran and the gleam is released", pageArrived)
    }

    @Test
    fun `under reduced motion the Carousel List change is arrived at once`() {
        viewHost(reducedMotion = true)
        viewMode.value = GamelistViewMode.LIST
        Snapshot.sendApplyNotifications()
        frame()
        assertTrue(pageArrived)
    }
}
