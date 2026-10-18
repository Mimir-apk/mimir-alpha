package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.feature.companion.CompanionSceneTransition
import com.gruning.mimir.rewrite.feature.companion.CompanionSceneTransitionProbe
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
import org.robolectric.shadows.ShadowLog

/**
 * Thor Bottom revamp B2 (T6 part 2): a Bottom scene change is Main's page handoff, on the REAL [CompanionSceneTransition] under
 * Robolectric on a paused clock (autoAdvance off, `Snapshot.sendApplyNotifications()` after each state write).
 *
 * The departing scene leaves on the 180 ms exit; the arriving one is hidden for the 130 ms lead and then fades in over 540 ms, so
 * the two are never on screen together (the old 0.5 start alpha, a second exposure of the departing scene over the new one, is
 * gone). A return arrives at rest and the departing scene dissolves over it on the 160 ms return clock. A touch on the surface
 * reaches nothing until the arriving scene has painted, and the Bottom never holds the process-wide pad gate. Reduced motion
 * snaps. The receipts keep their names, each backed by a begin and an end that ran to rest. Their lengths in ms are measured on
 * the device (this log runs on the real clock while Compose runs on a virtual one).
 *
 * Sites: the workspace scene (lattice <-> Activity, a glide, and a mode change that crosses in alpha only) is
 * `CompanionSurface`'s call; the Now playing tile's track change is `ActivityMediaTile`'s. Both pass the same four facts this test
 * varies (the target, the identity, the signed glide and `returning`). The Activity, takeover and hub scenes inside the Activity
 * page are the swap tier's own `AnimatedContent` (L3) and are pinned by `CompanionNowPlayingTakeoverRenderTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CompanionSceneHandoffTest {

    @get:Rule
    val compose = createComposeRule()

    private var target by mutableStateOf("lattice")
    private var identity by mutableStateOf<Any?>("lattice")
    private var glideDp by mutableStateOf(0f)
    private var returning by mutableStateOf(false)
    private var snap by mutableStateOf(false)
    private var taps = 0
    private val probe = CompanionSceneTransitionProbe()

    @Before
    fun clean() {
        MimirTransitionInputGate.reset()
        ShadowLog.clear()
    }

    @After
    fun restore() {
        MimirTransitionInputGate.reset()
        setAnimatorScale(1f)
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalBottomUiDisplay provides DISPLAY) {
                CompanionSceneTransition(
                    targetState = target,
                    transitionIdentity = identity,
                    incomingGlideOffsetDp = glideDp,
                    returning = returning,
                    snap = snap,
                    probe = probe,
                    modifier = Modifier.fillMaxSize(),
                ) { scene ->
                    Box(Modifier.fillMaxSize().testTag("scene-$scene")) {
                        Box(Modifier.size(100.dp).testTag("live").clickable { taps += 1 })
                    }
                }
            }
        }
        frame()
    }

    private fun frame() = compose.mainClock.advanceTimeByFrame()

    /** The change itself: a state write, applied, then the frame that composes it. */
    private fun change(to: String, identity: Any? = this.identity, glideDp: Float = 0f, returning: Boolean = false, snap: Boolean = false) {
        this.glideDp = glideDp
        this.snap = snap
        this.returning = returning
        this.identity = identity
        this.target = to
        Snapshot.sendApplyNotifications()
        frame()
    }

    private fun run(ms: Long, each: (Long) -> Unit = {}) {
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start < ms) {
            frame()
            each(compose.mainClock.currentTime - start)
        }
    }

    private fun tap() {
        compose.onNodeWithTag("live", useUnmergedTree = true).performTouchInput { click() }
        frame()
    }

    private fun lines(): List<String> =
        ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }.filter { "display=$DISPLAY" in it }

    private fun assertRanToRest(name: String) {
        val written = lines()
        assertTrue("$name began: $written", written.any { it.startsWith("ev=transition.begin") && "name=$name " in it + " " })
        assertTrue(
            "$name ended at rest: $written",
            written.any { it.startsWith("ev=transition.end") && "name=$name " in it && it.endsWith("result=done") },
        )
    }

    @Test
    fun `the first scene shows at once, with nothing departing and nothing shielded`() {
        host()
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertFalse(probe.handoff.departing())
        assertFalse(probe.shielding())
        assertTrue("and no receipt for a first scene: ${lines()}", lines().none { "scene." in it })
    }

    @Test
    fun `forward, the departing scene leaves on the exit and the arriving one waits the lead and fades in over 540 ms, never together`() {
        host()
        change(to = "activity", identity = "activity", glideDp = -MimirMotion.GlideTravelDp.toFloat())
        assertTrue("the departing scene starts on screen: ${probe.handoff.outgoingAlpha()}", probe.handoff.outgoingAlpha() > 0.9f)
        assertEquals("the arriving scene starts hidden", 0f, probe.incomingAlpha(), 0f)

        var atLead = -1f
        var goneAt = -1L
        var firstPaintAt = -1L
        var restAt = -1L
        var worstOverlap = 0f
        run(900) { t ->
            val out = probe.handoff.outgoingAlpha()
            val inn = probe.incomingAlpha()
            worstOverlap = maxOf(worstOverlap, minOf(out, inn))
            if (atLead < 0f && t >= MimirPageMotion.LeadMs) atLead = out
            if (goneAt < 0 && !probe.handoff.departing()) goneAt = t
            if (firstPaintAt < 0 && inn > 0f) firstPaintAt = t
            if (restAt < 0 && inn >= 0.999f) restAt = t
            assertFalse("the Bottom never holds the pad gate (t=$t)", MimirTransitionInputGate.holding)
        }
        assertTrue("the departing scene is at its measured 0.044 where the lead ends: $atLead", atLead in 0f..0.1f)
        assertTrue("gone in about 180 ms: $goneAt", goneAt in MimirPageMotion.ExitMs.toLong()..MimirPageMotion.ExitMs + 50L)
        assertTrue(
            "the arrival's first frame is after the lead: $firstPaintAt",
            firstPaintAt in MimirPageMotion.LeadMs.toLong()..MimirPageMotion.LeadMs + 50L,
        )
        assertTrue(
            "at rest about 670 ms from the press: $restAt",
            restAt in (MimirPageMotion.LeadMs + MimirPageMotion.ArriveMs).toLong()..(MimirPageMotion.LeadMs + MimirPageMotion.ArriveMs + 60).toLong(),
        )
        assertTrue("no double exposure: at no frame were both above 0.06 (worst $worstOverlap)", worstOverlap < 0.06f)
        // The departing scene glides the other way, a page's distance (22 dp, not the old 32).
        assertEquals(MimirMotion.GlideTravelDp.toFloat(), probe.handoff.outgoingTravel(), 0.01f)
        assertRanToRest("scene.crossfade")
        assertRanToRest("scene.glide")
    }

    @Test
    fun `a mode change with no glide crosses in alpha only`() {
        host()
        change(to = "now-playing")
        run(900)
        assertEquals("a full-screen mode change takes no lift", 0f, probe.handoff.outgoingTravel(), 0f)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertRanToRest("scene.crossfade")
        assertTrue("and names no glide: ${lines()}", lines().none { "name=scene.glide" in it })
    }

    @Test
    fun `a return arrives at rest and the departing scene dissolves over it on the 160 ms return clock`() {
        host()
        change(to = "activity", identity = "activity", glideDp = -MimirMotion.GlideTravelDp.toFloat())
        run(900)
        ShadowLog.clear()

        change(to = "lattice", identity = "lattice", glideDp = MimirMotion.GlideTravelDp.toFloat(), returning = true)
        assertEquals("the returned-to scene is at rest from the first frame", 1f, probe.incomingAlpha(), 0f)
        assertFalse("and nothing shields it", probe.shielding())
        assertTrue(probe.handoff.departing())
        var goneAt = -1L
        run(400) { t ->
            assertEquals("the destination never fades (t=$t)", 1f, probe.incomingAlpha(), 0f)
            if (goneAt < 0 && !probe.handoff.departing()) goneAt = t
        }
        assertTrue(
            "the departing scene is gone on the return clock, not the 180 ms exit: $goneAt",
            goneAt in MimirPageMotion.ReturnMs.toLong()..MimirPageMotion.ReturnMs + 50L,
        )
        assertEquals("it left toward the side it came from", -MimirMotion.GlideTravelDp.toFloat(), probe.handoff.outgoingTravel(), 0.01f)
        assertRanToRest("scene.crossfade")
        assertRanToRest("scene.glide")
    }

    @Test
    fun `a touch reaches nothing until the arriving scene has painted, and then lands`() {
        host()
        change(to = "activity", identity = "activity", glideDp = -MimirMotion.GlideTravelDp.toFloat())
        assertTrue(probe.shielding())
        run(40)
        tap()
        assertEquals("inside the lead, the touch is shielded", 0, taps)
        assertFalse("and still no pad gate", MimirTransitionInputGate.holding)
        run(300)
        assertFalse("shield lifted at the first painted frame", probe.shielding())
        tap()
        assertEquals("once it shows, the touch lands", 1, taps)
    }

    @Test
    fun `reduced motion snaps the scene with no departure, no lead and no travel`() {
        setAnimatorScale(0f)
        host()
        change(to = "activity", identity = "activity", glideDp = -MimirMotion.GlideTravelDp.toFloat())
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertFalse("nothing departs", probe.handoff.departing())
        assertFalse("nothing shields", probe.shielding())
        assertEquals(0f, probe.handoff.outgoingTravel(), 0f)
        tap()
        assertEquals("the first touch lands", 1, taps)
        assertTrue("a snap writes no transition receipt: ${lines()}", lines().none { it.startsWith("ev=transition.") })
    }

    @Test
    fun `a change the finger carried switches with no handoff, whichever way it goes`() {
        host()
        // The strip (B4) already moved the scenes under the finger and settled; the change of identity that follows is on screen already. It is
        // asked for with a glide and no reduced motion, and still takes none of it: nothing departs, nothing waits a lead, nothing is shielded.
        change(to = "lattice", identity = "activity", glideDp = -MimirMotion.GlideTravelDp.toFloat(), snap = true)
        assertEquals("the carried scene is at rest from the first frame", 1f, probe.incomingAlpha(), 0f)
        assertFalse("nothing departs", probe.handoff.departing())
        assertFalse("nothing shields", probe.shielding())
        assertEquals("and nothing travels", 0f, probe.handoff.outgoingTravel(), 0f)
        tap()
        assertEquals("the first touch lands", 1, taps)
        run(300)
        assertTrue("a carried change writes no scene transition: ${lines()}", lines().none { it.startsWith("ev=transition.") })

        // And back: the same, with the return flag the Presentation sets on the way to the lattice.
        change(to = "lattice", identity = "lattice", glideDp = MimirMotion.GlideTravelDp.toFloat(), returning = true, snap = true)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertFalse(probe.handoff.departing())
        assertTrue("still no scene transition: ${lines()}", lines().none { it.startsWith("ev=transition.") })

        // A change that is not the finger's still hands over exactly as before (the flag is per change, not sticky).
        change(to = "activity", identity = "activity", glideDp = -MimirMotion.GlideTravelDp.toFloat())
        assertTrue("a programmatic change departs and hides its arrival: ${probe.handoff.outgoingAlpha()}", probe.handoff.outgoingAlpha() > 0.9f)
        assertEquals(0f, probe.incomingAlpha(), 0f)
    }

    @Test
    fun `a second change inside the lead takes over cleanly and rests on the last scene`() {
        host()
        change(to = "activity", identity = "activity", glideDp = -MimirMotion.GlideTravelDp.toFloat())
        run(60)
        change(to = "lattice", identity = "lattice", glideDp = MimirMotion.GlideTravelDp.toFloat(), returning = true)
        run(600)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertFalse(probe.handoff.departing())
        assertFalse(probe.shielding())
        tap()
        assertEquals(1, taps)
    }

    /**
     * The least of what is visible on any frame: the departing scene's alpha or the arriving one's, whichever is more. A handoff dips
     * (the departing scene is 0.044 where the arrival starts) but is never blank.
     */
    private fun leastVisible(ms: Long, until: (Long) -> Unit = {}): Float {
        var least = 1f
        run(ms) { t ->
            until(t)
            least = minOf(least, maxOf(probe.handoff.outgoingAlpha(), probe.incomingAlpha()))
        }
        return least
    }

    @Test
    fun `one forward change dips to a trace and never blank (the baseline for the double change below)`() {
        host()
        change(to = "B", identity = "B", glideDp = MimirMotion.GlideTravelDp.toFloat())
        val least = leastVisible(900)
        println("HANDOFF-BASELINE least visible=$least")
        assertTrue("a single handoff is never blank: $least", least > 0.01f)
    }

    @Test
    fun `two track changes inside the lead never blank the row, keep the visible one leaving and end on the last`() {
        host()
        // A is on screen. B is asked for at t0 and C 60 ms later, inside B's 130 ms lead, while B is still invisible.
        change(to = "B", identity = "B", glideDp = MimirMotion.GlideTravelDp.toFloat())
        var blankAt = -1L
        run(60) { t -> if (blankAt < 0 && maxOf(probe.handoff.outgoingAlpha(), probe.incomingAlpha()) <= 0.01f) blankAt = t }
        assertEquals("B is still invisible at 60 ms", 0f, probe.incomingAlpha(), 0f)
        assertTrue("A is still showing, leaving: ${probe.handoff.outgoingAlpha()}", probe.handoff.outgoingAlpha() > 0.05f)
        change(to = "C", identity = "C", glideDp = MimirMotion.GlideTravelDp.toFloat())
        val least = leastVisible(900) { t -> if (blankAt < 0 && maxOf(probe.handoff.outgoingAlpha(), probe.incomingAlpha()) <= 0.01f) blankAt = 60 + t }
        println("HANDOFF-DOUBLE least visible=$least blankAt=$blankAt")
        assertEquals("no frame was blank (first blank at $blankAt ms)", -1L, blankAt)
        assertTrue("never blank: $least", least > 0.01f)
        assertEquals("it ends on C", 1f, probe.incomingAlpha(), 0f)
        assertEquals("and C is the scene composed", 1, compose.onAllNodesWithTag("scene-C", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("B is not", 0, compose.onAllNodesWithTag("scene-B", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(probe.handoff.departing())
        assertFalse(probe.shielding())
        tap()
        assertEquals("and it takes touches", 1, taps)
    }

    @Test
    fun `the same scene recomposing before the shared arrival completes keeps the joined presentation`() {
        host()
        // A on screen; B at 0; C at 60 (joins B's hidden presentation). Then C RECOMPOSES at about 100 ms, still inside the lead, with nothing changed but a
        // parameter the transition re-reads (a playback or artwork update does exactly that): the join must hold.
        change(to = "B", identity = "B", glideDp = MimirMotion.GlideTravelDp.toFloat())
        run(60)
        change(to = "C", identity = "C", glideDp = MimirMotion.GlideTravelDp.toFloat())
        run(40)
        assertEquals("C is still hidden inside the lead", 0f, probe.incomingAlpha(), 0f)
        val outgoingBefore = probe.handoff.outgoingAlpha()
        assertTrue("A is still leaving: $outgoingBefore", outgoingBefore in 0.001f..0.999f)
        glideDp += 1f
        Snapshot.sendApplyNotifications()
        frame()
        assertEquals("the recomposition did not snap C to 1: the arrival is still hidden", 0f, probe.incomingAlpha(), 0f)
        assertTrue("and A was not dropped by it: ${probe.handoff.outgoingAlpha()} after $outgoingBefore", probe.handoff.departing() && probe.handoff.outgoingAlpha() <= outgoingBefore + 0.001f)
        assertTrue("C is still shielded", probe.shielding())

        var blankAt = -1L
        var goneAt = -1L
        var firstPaintAt = -1L
        var previous = probe.incomingAlpha()
        var biggestStep = 0f
        var lastOutgoing = probe.handoff.outgoingAlpha()
        var outgoingNeverRose = true
        run(900) { t ->
            val out = probe.handoff.outgoingAlpha()
            val inn = probe.incomingAlpha()
            if (blankAt < 0 && maxOf(out, inn) <= 0.01f) blankAt = t
            if (goneAt < 0 && !probe.handoff.departing()) goneAt = t
            if (firstPaintAt < 0 && inn > 0f) firstPaintAt = t
            biggestStep = maxOf(biggestStep, inn - previous)
            previous = inn
            if (out > lastOutgoing + 0.001f) outgoingNeverRose = false
            lastOutgoing = out
        }
        assertEquals("no frame was blank (first blank $blankAt ms after the recomposition)", -1L, blankAt)
        assertTrue("A kept leaving on its own timeline: gone ${goneAt}ms after the recomposition (it began 100 ms earlier on a 180 ms exit)", goneAt in 1L..(MimirPageMotion.ExitMs - 100L + 50L))
        assertTrue("A never came back up", outgoingNeverRose)
        assertTrue("C waited out the lead, then glided in: first painted $firstPaintAt ms after the recomposition", firstPaintAt in 1L..(MimirPageMotion.LeadMs - 100L + 50L))
        assertTrue("C rises over frames, not in one jump: biggest step $biggestStep", biggestStep < 0.3f)
        assertEquals("it ends on C", 1f, probe.incomingAlpha(), 0f)
        assertEquals(1, compose.onAllNodesWithTag("scene-C", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(probe.shielding())
        tap()
        assertEquals(1, taps)
    }

   @Test
    fun `once the shared arrival has completed the join is released, so asking for the scene it joined is a fresh handoff and never a cut`() {
        host()
        // A -> B at 0 -> C at 60 (C joins B's hidden presentation) -> C recomposes at 100 -> C settles.
        change(to = "B", identity = "B", glideDp = MimirMotion.GlideTravelDp.toFloat())
        run(60)
        change(to = "C", identity = "C", glideDp = MimirMotion.GlideTravelDp.toFloat())
        run(40)
        glideDp += 1f
        Snapshot.sendApplyNotifications()
        frame()
        run(1_000)
        assertEquals("fixture: C has settled", 1f, probe.incomingAlpha(), 0f)
        assertFalse(probe.handoff.departing())
        ShadowLog.clear()

        // Now B is asked for again. C still carries the join's key under the old rule, so the key did not change and C cut to B at 1 with nothing departing.
        change(to = "B", identity = "B", glideDp = MimirMotion.GlideTravelDp.toFloat())
        assertEquals("B arrives hidden, through the handoff", 0f, probe.incomingAlpha(), 0f)
        // (The departing layer's own alpha is not asserted: it is read from what the layer last PAINTED, which this test's non-native graphics never record for the
        // second handoff. What separates a handoff from the cut is that C is departing, B is hidden and shielded, and B then rises over frames.)
        assertTrue("C is departing", probe.handoff.departing())
        assertTrue("B is shielded until it paints", probe.shielding())
        var previous = probe.incomingAlpha()
        var biggestStep = 0f
        var firstPaintAt = -1L
        run(900) { t ->
            val inn = probe.incomingAlpha()
            if (firstPaintAt < 0 && inn > 0f) firstPaintAt = t
            biggestStep = maxOf(biggestStep, inn - previous)
            previous = inn
        }
        assertTrue("B waited out the lead: first painted at $firstPaintAt ms", firstPaintAt >= MimirPageMotion.LeadMs - 20L)
        assertTrue("B rises over frames, never a one-frame cut: biggest step $biggestStep", biggestStep < 0.3f)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals("B is the scene composed", 1, compose.onAllNodesWithTag("scene-B", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("scene-C", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertRanToRest("scene.crossfade")
        assertRanToRest("scene.glide")
        assertFalse(probe.shielding())
    }

    private companion object {
        const val DISPLAY = 4
    }
}
