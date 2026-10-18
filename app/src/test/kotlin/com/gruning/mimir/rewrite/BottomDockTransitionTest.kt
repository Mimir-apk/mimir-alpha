package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.os.Looper
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.core.ui.mimirOverlayArriveSpec
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDockProbe
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchLayer
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A4 (T5): the Bottom's dock gets a transition, on the REAL [CompanionQuickLaunchLayer] under Robolectric. The receipts name
 * `dock.enter` and `dock.exit` and each is backed by a real begin and end (a transition that is only named would write
 * `transition.missing`), and the dock never holds the process-wide pad gate: the Bottom is touch-only, and a held gate would
 * deafen Main's controller for the lead. Lengths in ms are measured on the device; this log runs on the real clock
 * while Compose runs on a virtual one, so only the sequence is pinned here, and the spec lengths in
 * `MimirSwapPageGateTest` / `MenuReturnGlideTest`.
 *
 * Dock interaction constraints: the expanded handle is inert while the dock arrives or leaves; the collapsed handle takes a
 * re-tap during the departure and is never visible with the departing dock; a cut edge (reduced motion, a dock mounted open)
 * says so instead of naming a fade that never ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BottomDockTransitionTest {

    @get:Rule
    val compose = createComposeRule()

    private val beta = CompanionAppEntry(packageName = "com.example.beta", label = "Beta")
    private var expanded by mutableStateOf(false)

    /** What the layer reported through `onDockExpandedChanged`: a request FROM the surface, never the test's own writes. */
    private val requested = mutableListOf<Boolean>()

    @Before
    fun clean() {
        MimirTransitionInputGate.reset()
        ShadowLog.clear()
        requested.clear()
    }

    @After
    fun resetGate() {
        MimirTransitionInputGate.reset()
        setAnimatorScale(1f)
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun mount(display: Int = DISPLAY, probe: CompanionQuickLaunchDockProbe? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBottomUiDisplay provides display) {
                MimirTheme {
                    CompanionQuickLaunchLayer(
                        slots = listOf(beta.packageName, null),
                        apps = listOf(beta),
                        launchLocked = false,
                        launchMessage = null,
                        onLaunch = {},
                        onAssign = { _, _ -> },
                        hideableDock = true,
                        dockExpanded = expanded,
                        onDockExpandedChanged = {
                            requested += it
                            expanded = it
                        },
                        dockProbe = probe,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun driveExpanded(value: Boolean) {
        expanded = value
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy((MimirPageMotion.LeadMs + MimirPageMotion.ArriveMs + 200).toLong())
        compose.waitForIdle()
    }

    private fun lines(display: Int = DISPLAY): List<String> =
        ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }.filter { "display=$display" in it }

    /** Centres of every handle node currently in the semantics tree, top-most first (an inert handle clears its semantics). */
    private fun handleCentres(): List<Offset> =
        compose.onAllNodesWithTag(HANDLE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot.center }
            .sortedBy { it.y }

    private fun tapAt(point: Offset) {
        compose.onRoot().performTouchInput { click(point) }
        compose.mainClock.advanceTimeByFrame()
    }

    /** The expanded handle's and the collapsed handle's centres, measured while each is live; ends collapsed and at rest. */
    private fun measureHandles(): Pair<Offset, Offset> {
        mount()
        val collapsed = handleCentres().single()
        driveExpanded(true)
        settle()
        // Settled open: the collapsed handle is gone, the expanded one is the only handle.
        val expandedCentre = handleCentres().single()
        driveExpanded(false)
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        requested.clear()
        return expandedCentre to collapsed
    }

    @Test
    fun `opening the dock names dock enter, runs it, and never holds the pad`() {
        mount()
        driveExpanded(true)
        // Inside the lead and the early fade: a Bottom dock that held the gate would read true here.
        repeat(6) {
            compose.mainClock.advanceTimeBy(40L)
            assertFalse("the Bottom dock must not hold the process-wide pad gate", MimirTransitionInputGate.holding)
        }
        settle()

        val written = lines()
        assertTrue(
            "the open edge names dock.enter: $written",
            written.any { it.startsWith("ev=surface.enter surface=dock") && it.endsWith("transition=dock.enter") },
        )
        assertTrue("a begin for it: $written", written.any { it.startsWith("ev=transition.begin") && "name=dock.enter" in it })
        assertTrue(
            "and an end that ran to rest: $written",
            written.any { it.startsWith("ev=transition.end") && "name=dock.enter" in it && "result=done" in it },
        )
        assertFalse("nothing is named and not wired: $written", written.any { "transition.missing" in it || "transition.unfinished" in it })
        assertFalse("the dock opens with a transition, never none: $written", written.any { it.startsWith("ev=surface.enter surface=dock") && "transition=none" in it })
    }

    @Test
    fun `closing the dock names dock exit and ends it`() {
        mount()
        driveExpanded(true)
        compose.mainClock.advanceTimeBy((MimirPageMotion.LeadMs + MimirPageMotion.ArriveMs + 100).toLong())
        ShadowLog.clear()
        driveExpanded(false)
        compose.mainClock.advanceTimeBy(400L)
        compose.waitForIdle()

        val written = lines()
        assertTrue(
            "the close edge names dock.exit: $written",
            written.any { it.startsWith("ev=surface.exit surface=dock") && it.endsWith("transition=dock.exit") },
        )
        assertTrue(
            "and its end ran: $written",
            written.any { it.startsWith("ev=transition.end") && "name=dock.exit" in it },
        )
        assertFalse(written.any { "transition.missing" in it || "transition.unfinished" in it })
    }

    // ---- A4 fix round -------------------------------------------------------------------------------------------------

    @Test
    fun `the expanded handle takes no gesture while the dock arrives`() {
        val (expandedCentre, _) = measureHandles()
        driveExpanded(true)
        compose.mainClock.advanceTimeBy(40L) // inside the lead: the layer is at alpha 0 and shielded
        tapAt(expandedCentre)
        assertTrue("an arriving dock's handle must not collapse it: $requested", requested.isEmpty())
    }

    @Test
    fun `the expanded handle takes no gesture while the dock leaves`() {
        val (expandedCentre, _) = measureHandles()
        driveExpanded(true)
        settle()
        driveExpanded(false) // the dock is leaving, still painted at nearly full alpha
        compose.mainClock.advanceTimeBy(40L)
        tapAt(expandedCentre)
        assertTrue("a leaving dock's handle must not toggle it back open: $requested", requested.isEmpty())
    }

    @Test
    fun `a settled expanded handle still collapses the dock`() {
        val (expandedCentre, _) = measureHandles()
        driveExpanded(true)
        settle()
        tapAt(expandedCentre)
        assertEquals("settled behaviour is unchanged: one collapse request", listOf(false), requested)
    }

    @Test
    fun `a tap on the collapsed handle during the departure is received`() {
        val (_, collapsedCentre) = measureHandles()
        driveExpanded(true)
        settle()
        driveExpanded(false)
        compose.mainClock.advanceTimeBy(40L) // the departing layer is still above the threshold
        tapAt(collapsedCentre)
        assertEquals("the re-tap reaches the collapsed handle and asks to expand: $requested", listOf(true), requested)
    }

    @Test
    fun `the collapsed handle and the dock are never both above the shown threshold through a close`() {
        val probe = CompanionQuickLaunchDockProbe()
        mount(probe = probe)
        driveExpanded(true)
        settle()
        assertEquals("settled open: the dock is fully drawn", 1f, probe.dockAlpha(), 0.001f)
        assertEquals("and the collapsed handle is not composed", 0f, probe.handleAlpha(), 0.001f)

        driveExpanded(false)
        var frames = 0
        var handleAtFirstFrame = -1f
        var dockAtLastFrame = 1f
        var handleAtLastFrame = 0f
        repeat(40) {
            compose.mainClock.advanceTimeBy(16L)
            val dock = probe.dockAlpha()
            val handle = probe.handleAlpha()
            if (frames == 0) handleAtFirstFrame = handle
            assertFalse(
                "frame $frames: dock=$dock handle=$handle: both above ${MimirPageMotion.ShownAlpha}",
                dock > MimirPageMotion.ShownAlpha && handle > MimirPageMotion.ShownAlpha,
            )
            dockAtLastFrame = dock
            handleAtLastFrame = handle
            frames += 1
        }
        assertTrue(
            "the handle does not snap to full on the first frame of the close: $handleAtFirstFrame",
            handleAtFirstFrame <= MimirPageMotion.ShownAlpha,
        )
        assertEquals("the dock is gone", 0f, dockAtLastFrame, 0.001f)
        assertEquals("and the handle has returned to rest", 1f, handleAtLastFrame, 0.001f)
    }

    @Test
    fun `on the specs the handle has left before the arriving dock passes the shown threshold`() {
        val arrive = mimirOverlayArriveSpec(reducedMotion = false).vectorize(Float.VectorConverter)
        val leave = mimirOverlayLeaveSpec().vectorize(Float.VectorConverter)
        fun at(spec: VectorizedFiniteAnimationSpec<AnimationVector1D>, from: Float, to: Float, ms: Int): Float =
            spec.getValueFromNanos(ms * 1_000_000L, AnimationVector1D(from), AnimationVector1D(to), AnimationVector1D(0f)).value
        for (ms in 0..800 step 4) {
            val dock = at(arrive, 0f, 1f, ms)
            val handle = at(leave, 1f, 0f, ms)
            assertFalse(
                "open at ${ms}ms: dock=$dock handle=$handle both above the threshold",
                dock > MimirPageMotion.ShownAlpha && handle > MimirPageMotion.ShownAlpha,
            )
        }
    }

    @Test
    fun `under reduced motion the dock writes a cut on both edges and no enter or exit pair`() {
        setAnimatorScale(0f)
        mount(display = REDUCED_DISPLAY)
        driveExpanded(true)
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        driveExpanded(false)
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000L))

        val written = lines(REDUCED_DISPLAY)
        assertTrue(
            "the enter is a named cut: $written",
            written.any { it.startsWith("ev=surface.enter surface=dock") && it.endsWith("transition=none reason=reduced_motion") },
        )
        assertTrue(
            "so is the exit: $written",
            written.any { it.startsWith("ev=surface.exit surface=dock") && it.endsWith("transition=none reason=reduced_motion") },
        )
        assertFalse(
            "no dock.enter or dock.exit begin/end pair under a cut: $written",
            written.any { "name=dock.enter" in it || "name=dock.exit" in it },
        )
        assertFalse("and no missing or unfinished watch fires: $written", written.any { "transition.missing" in it || "transition.unfinished" in it })
    }

    @Test
    fun `a dock mounted already open writes an initial cut and arms no missing watch`() {
        expanded = true
        mount(display = INITIAL_DISPLAY)
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000L))

        val written = lines(INITIAL_DISPLAY)
        assertTrue(
            "the first edge is an initial cut: $written",
            written.any { it.startsWith("ev=surface.enter surface=dock") && it.endsWith("transition=none reason=initial") },
        )
        assertFalse("nothing waits for a transition that cannot come: $written", written.any { "transition.missing" in it })
        assertFalse(written.any { "name=dock.enter" in it })
    }

    // ---- A5 fold-in 2: the animator scale is read at each edge, not remembered for the mount ---------------------------------

    @Test
    fun `a dock mounted under reduced motion and used after animations return names and runs both fades`() {
        setAnimatorScale(0f)
        mount(display = RESTORED_DISPLAY)
        setAnimatorScale(1f) // "remove animations" turned off, no remount
        driveExpanded(true)
        settle()
        driveExpanded(false)
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000L))

        val written = lines(RESTORED_DISPLAY)
        assertTrue(
            "the open names the fade that runs: $written",
            written.any { it.startsWith("ev=surface.enter surface=dock") && it.endsWith("transition=dock.enter") },
        )
        assertTrue(
            "and the close names the one that runs, not a cut the mount remembered: $written",
            written.any { it.startsWith("ev=surface.exit surface=dock") && it.endsWith("transition=dock.exit") },
        )
        for (name in listOf("dock.enter", "dock.exit")) {
            assertTrue("a begin for $name: $written", written.any { it.startsWith("ev=transition.begin") && "name=$name" in it })
            assertTrue("and its end: $written", written.any { it.startsWith("ev=transition.end") && "name=$name" in it && "result=done" in it })
        }
        assertFalse("no edge is written as a cut while the fades run: $written", written.any { "reason=reduced_motion" in it })
        assertFalse("nothing is missing or unfinished: $written", written.any { "transition.missing" in it || "transition.unfinished" in it })
    }

    @Test
    fun `a dock mounted with animations on and used after they are turned off writes the cut at that edge`() {
        mount(display = DISABLED_DISPLAY)
        driveExpanded(true)
        settle()
        setAnimatorScale(0f) // "remove animations" turned on after the dock opened, no remount
        driveExpanded(false)
        compose.mainClock.advanceTimeBy(600L)
        compose.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000L))

        val written = lines(DISABLED_DISPLAY)
        assertTrue(
            "the open ran its fade: $written",
            written.any { it.startsWith("ev=surface.enter surface=dock") && it.endsWith("transition=dock.enter") },
        )
        assertTrue(
            "the close is the cut the player now has, named as one: $written",
            written.any { it.startsWith("ev=surface.exit surface=dock") && it.endsWith("transition=none reason=reduced_motion") },
        )
        assertFalse("and it runs no dock.exit pair: $written", written.any { "name=dock.exit" in it })
        assertFalse("nor waits for one: $written", written.any { "transition.missing" in it || "transition.unfinished" in it })
    }

    @Test
    fun `under reduced motion a close takes the dock off at once instead of leaving it drawn for the leave fade`() {
        setAnimatorScale(0f)
        val probe = CompanionQuickLaunchDockProbe()
        mount(display = CUT_CLOSE_DISPLAY, probe = probe)
        driveExpanded(true)
        compose.mainClock.advanceTimeBy(100L)
        assertEquals("a cut open: the dock is fully drawn at once", 1f, probe.dockAlpha(), 0.001f)
        driveExpanded(false)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("a cut close: no 180 ms leave lingers behind it", 0f, probe.dockAlpha(), 0.001f)
    }

    private companion object {
        const val DISPLAY = 7
        const val REDUCED_DISPLAY = 8
        const val INITIAL_DISPLAY = 9
        const val RESTORED_DISPLAY = 10
        const val DISABLED_DISPLAY = 11
        const val CUT_CLOSE_DISPLAY = 12
        const val HANDLE_TAG = "companion-quick-launch-handle"
    }
}
