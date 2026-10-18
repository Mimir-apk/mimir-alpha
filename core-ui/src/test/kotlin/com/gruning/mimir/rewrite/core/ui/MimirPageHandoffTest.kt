package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's L1/R1 switch as a page change (motion audit batch D): the band's last frame stays on
 * screen as the departing page and leaves on the quick exit, gliding the other way, while the caller brings the new
 * content in on its own clock. A restore departs nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirPageHandoffTest {

    @get:Rule
    val compose = createComposeRule()

    private val epoch = mutableIntStateOf(0)
    private val departs = mutableStateOf(true)
    private val reduced = mutableStateOf(false)
    private val probe = MimirPageHandoffProbe()
    private val shielding = mutableStateOf(false)
    private var taps = 0

    private fun host(
        exitMs: Int = MimirPageMotion.ExitMs,
        vertical: Boolean = false,
        shieldTouches: (() -> Boolean)? = null,
        exitReleased: (() -> Boolean)? = null,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MimirPageHandoff(
                    epoch = epoch.intValue,
                    departs = departs.value,
                    exitTravelPx = -22f,
                    incomingAlpha = { 1f },
                    incomingTravelPx = { 0f },
                    reducedMotion = reduced.value,
                    exitMs = exitMs,
                    vertical = vertical,
                    modifier = Modifier.size(100.dp),
                    probe = probe,
                    shieldTouches = shieldTouches ?: { false },
                    exitReleased = exitReleased ?: { true },
                ) { Box(Modifier.size(100.dp).testTag("live").clickable { taps += 1 }) }
            }
        }
        frame()
    }

    private fun frame() = compose.mainClock.advanceTimeByFrame()

    private fun flip() {
        epoch.intValue += 1
        Snapshot.sendApplyNotifications()
        frame()
    }

    private fun tap() {
        compose.onNodeWithTag("live").performTouchInput { click() }
        frame()
    }

    private fun run(ms: Long, each: (Long) -> Unit = {}) {
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start < ms) {
            frame()
            each(compose.mainClock.currentTime - start)
        }
    }

    @Test
    fun `nothing departs at rest`() {
        host()
        assertFalse(probe.departing())
        assertEquals(0f, probe.outgoingAlpha(), 0f)
    }

    @Test
    fun `on the press the old band leaves on the quick exit, gliding the other way`() {
        host()
        flip()
        assertTrue(probe.departing())
        assertTrue("it starts fully on screen: ${probe.outgoingAlpha()}", probe.outgoingAlpha() > 0.9f)
        var goneAt = -1L
        run(400) { t -> if (goneAt < 0 && !probe.departing()) goneAt = t }
        assertTrue("gone in about 180 ms: $goneAt", goneAt in MimirPageMotion.ExitMs.toLong()..MimirPageMotion.ExitMs + 50L)
        assertEquals(-22f, probe.outgoingTravel(), 0.01f)
    }

    @Test
    fun `a caller can hold the departing page at rest until it lets it go`() {
        val released = mutableStateOf(false)
        host(exitReleased = { released.value })
        flip()
        run(300) { t ->
            assertEquals("at rest at $t", 1f, probe.outgoingAlpha(), 0f)
            assertEquals("in its seat at $t", 0f, probe.outgoingTravel(), 0f)
        }
        released.value = true
        Snapshot.sendApplyNotifications()
        var goneAt = -1L
        run(400) { t -> if (goneAt < 0 && !probe.departing()) goneAt = t }
        assertTrue("then gone on the quick exit: $goneAt", goneAt in MimirPageMotion.ExitMs.toLong()..MimirPageMotion.ExitMs + 66L)
        assertEquals(-22f, probe.outgoingTravel(), 0.01f)
    }

    @Test
    fun `a restore departs nothing`() {
        departs.value = false
        host()
        flip()
        assertFalse(probe.departing())
    }

    @Test
    fun `under reduced motion the old band is gone at once`() {
        reduced.value = true
        host()
        flip()
        run(48)
        assertFalse(probe.departing())
    }

    @Test
    fun `a second press mid-exit hands over again rather than stacking`() {
        host()
        flip()
        run(80)
        flip()
        assertTrue(probe.departing())
        run(400)
        assertFalse(probe.departing())
    }

    @Test
    fun `a return leaves on today's quicker clock`() {
        // Batch F: the file browser's ascend hands the child over as a page return.
        host(exitMs = MimirPageMotion.ReturnMs)
        flip()
        var goneAt = -1L
        run(400) { t -> if (goneAt < 0 && !probe.departing()) goneAt = t }
        assertTrue(
            "gone in about 160 ms: $goneAt",
            goneAt in MimirPageMotion.ReturnMs.toLong()..MimirPageMotion.ReturnMs + 50L,
        )
        assertEquals(-22f, probe.outgoingTravel(), 0.01f)
    }

    @Test
    fun `a vertical handoff travels on Y`() {
        // Batch F: the gamelist's Carousel/List swap arrives from below.
        host(vertical = true)
        flip()
        assertTrue(probe.travelAxisVertical())
        run(300)
        assertEquals(-22f, probe.outgoingTravel(), 0.01f)
    }

    @Test
    fun `while the caller shields, a tap on the surface reaches nothing, and reaches the page once released`() {
        // Review of 3cf42c7fd: the live page hit-tests at rest while it is hidden, so a tap on the departing
        // pixels landed on a tile nobody could see.
        shielding.value = true
        host(shieldTouches = { shielding.value })
        flip()
        tap()
        assertEquals("no tap reaches the hidden page", 0, taps)
        run(400)
        assertFalse("the departing layer is gone", probe.departing())
        tap()
        assertEquals("the shield outlives the departing layer", 0, taps)
        shielding.value = false
        Snapshot.sendApplyNotifications()
        frame()
        tap()
        assertEquals(1, taps)
    }

    @Test
    fun `without a shield the page takes touches as before`() {
        host()
        flip()
        tap()
        assertEquals(1, taps)
    }

    // --- The departing page is pixels: the file browser's ascend relies on it. Its listing is
    // keyed on the folder, so the old folder's tree is disposed on the flip while its last frame departs.

    /** The epochs whose content is composed right now, and what a tap reached. */
    private val alive = mutableListOf<Int>()
    private val tapped = mutableListOf<Int>()

    private fun keyedHost() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val e = epoch.intValue
                MimirPageHandoff(
                    epoch = e,
                    departs = true,
                    exitTravelPx = -22f,
                    incomingAlpha = { 1f },
                    incomingTravelPx = { 0f },
                    reducedMotion = false,
                    exitMs = MimirPageMotion.ReturnMs,
                    modifier = Modifier.size(100.dp),
                    probe = probe,
                ) {
                    key(e) {
                        DisposableEffect(Unit) {
                            alive += e
                            onDispose { alive -= e }
                        }
                        Box(Modifier.size(100.dp).testTag("live").clickable { tapped += e })
                    }
                }
            }
        }
        frame()
    }

    @Test
    fun `the departing page is pixels only, so there is one live tree, one node and one page to press through the whole exit`() {
        keyedHost()
        assertEquals("at rest: the first page is the one live tree", listOf(0), alive.toList())

        flip()

        assertTrue("the old page's last frame is on screen, departing", probe.departing())
        assertTrue("and it is not a tree: only the new page is composed", alive.toList() == listOf(1))
        assertEquals(
            "one node in the semantics tree, the new page's: the departing page clears its semantics",
            1,
            compose.onAllNodesWithTag("live").fetchSemanticsNodes().size,
        )
        tap()
        assertEquals("a tap reaches the new page, never the one that is leaving", listOf(1), tapped.toList())

        var seenAlive = alive.toList()
        run(MimirPageMotion.ReturnMs.toLong() + 100L) { seenAlive = alive.toList(); assertTrue("one tree at every frame: $seenAlive", seenAlive == listOf(1)) }
        assertFalse("and the departing pixels are gone", probe.departing())
        assertEquals("nothing was ever taken by the old page", listOf(1), tapped.toList())
    }

    // --- A reversal: R1 then L1 inside the exit (the walk's shoulder spam). The host mirrors Home: its own fade,
    // seeded from the page still leaving when the press turns back to it, and no lead for that page.

    private val mode = mutableStateOf("EC")
    private var bandFade: () -> Float = { 1f }

    private fun reversalHost() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val state = remember { MimirPageHandoffState() }
                val e = epoch.intValue
                val target = mode.value
                val returning = remember(e) { if (e == 0) null else state.returningAlpha(target) }
                val fade = remember(e) { Animatable(if (e == 0) 1f else returning ?: 0f) }
                SideEffect { bandFade = { fade.value } }
                LaunchedEffect(e) {
                    if (e == 0) return@LaunchedEffect
                    val start = withFrameNanos { it }
                    if (returning == null) {
                        while (withFrameNanos { it } - start < MimirPageMotion.LeadMs * 1_000_000L) Unit
                    }
                    fade.animateTo(1f, tween(MimirPageMotion.ArriveMs, easing = MimirMotion.Easing))
                }
                MimirPageHandoff(
                    epoch = e,
                    departs = true,
                    exitTravelPx = -22f,
                    incomingAlpha = { fade.value },
                    incomingTravelPx = { 0f },
                    reducedMotion = false,
                    modifier = Modifier.size(100.dp),
                    target = target,
                    state = state,
                    probe = probe,
                ) { Box(Modifier.size(100.dp)) }
            }
        }
        frame()
    }

    private fun press(target: String) {
        mode.value = target
        flip()
    }

    @Test
    fun `a reversal at 60 ms carries the returning page on from where it was painted`() {
        reversalHost()
        press("PT")
        run(60)
        val painted = probe.outgoingAlpha()
        assertTrue("the first page is mid-exit: $painted", painted > 0.05f && painted < 0.98f)
        press("EC")
        val carried = bandFade()
        assertTrue("no blink: it was at $painted and now shows $carried", carried > painted * 0.5f)
        assertEquals("within one frame of the exit", painted, carried, 0.2f)
        var last = carried
        run(700) { t ->
            val now = bandFade()
            assertTrue("it never drops back at $t ms: $last -> $now", now >= last - 1e-4f)
            last = now
        }
        assertEquals(1f, bandFade(), 0.001f)
    }

    @Test
    fun `a reversal at 300 ms finds the first page gone and brings it in fresh`() {
        reversalHost()
        press("PT")
        run(300)
        assertFalse(probe.departing())
        assertEquals("nothing is on screen to jump from", 0f, probe.outgoingAlpha(), 0f)
        press("EC")
        assertEquals(0f, bandFade(), 0f)
        run(100)
        assertEquals("it waits for the lead like any arrival", 0f, bandFade(), 0f)
        run(700)
        assertEquals(1f, bandFade(), 0.001f)
    }

    @Test
    fun `only the page still leaving can be returned to, and only with a target`() {
        val state = MimirPageHandoffState()
        state.commit(0, null, "EC")
        state.commit(1, 0, "PT")
        state.displayedAlpha[0] = 0.6f
        state.displayedTravel[0] = -8.8f
        assertEquals(0.6f, state.returningAlpha("EC")!!, 0f)
        assertEquals(-8.8f, state.returningTravel("EC")!!, 0f)
        assertNull("the arriving page is not a reversal", state.returningAlpha("PT"))
        assertNull("no target, no reversal", state.returningAlpha(null))
        state.commit(1, null, "PT")
        assertNull("a restore drops the page that was leaving", state.returningAlpha("EC"))

        val untargeted = MimirPageHandoffState()
        untargeted.commit(0, null, null)
        untargeted.commit(1, 0, null)
        untargeted.displayedAlpha[0] = 0.6f
        assertNull(untargeted.returningAlpha(null))
    }
}
