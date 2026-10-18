package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thor Bottom revamp B4: the workspace strip's own logic, on a plain JVM (no Presentation, no composition). The strip is [Activity | lattice]; the
 * width here is 1000 px and the fling threshold 800 px/s, so the numbers read directly: half is 500, a 600 px drag is past it.
 *
 * What is pinned: what a drag takes of the finger and hands back, which page a release lands on (the nearer page, or exactly one page on a fling), that
 * only a settle that lands on the OTHER page names a control receipt and switches (once), that a settle back and a cancel write nothing, and the state
 * machine around a landing (the strip holds until the host shows the page, so the frame that swaps the shown scene is the frame the strip lets go).
 * The last two tests are source pins for the parts that live in Compose: the timeline scrubber's own drag, and the receipts' sites.
 */
class CompanionWorkspaceStripTest {

    private val width = 1000f
    private val fling = 800f

    private fun strip() = CompanionWorkspaceStrip().also { it.widthPx = width }

    private val lattice = CompanionWorkspacePage.SHELF
    private val activity = CompanionWorkspacePage.ACTIVITY

    /** A drag from [shown] by [px] in 10 steps, the finger's way; the strip is left held (not released). */
    private fun CompanionWorkspaceStrip.dragBy(shown: CompanionWorkspacePage, px: Float) {
        repeat(10) { drag(shown, px / 10f) }
    }

    @Test
    fun `a right drag from the lattice takes the finger one for one and hands nothing back`() {
        val s = strip()
        assertEquals(0f, s.x, 0f)
        assertFalse(s.active)
        assertEquals("the strip took the whole delta", 0f, s.drag(lattice, 120f), 0f)
        assertTrue(s.active)
        assertEquals(120f, s.x, 0f)
        s.dragBy(lattice, 200f)
        assertEquals(320f, s.x, 0.001f)
    }

    @Test
    fun `the strip stops at the Activity page and hands back what it could not use`() {
        val s = strip()
        s.dragBy(lattice, 900f)
        assertEquals(60f, s.drag(lattice, 160f), 0.001f)
        assertEquals(width, s.x, 0f)
        assertEquals("pinned at the edge, the next delta is all handed back", 40f, s.drag(lattice, 40f), 0f)
    }

    @Test
    fun `a leftward finger on the lattice at rest is not the strip's, and a drag back to rest hands the rest to the pager`() {
        val s = strip()
        assertEquals("nothing to take: the strip is at rest and the delta goes left", -50f, s.drag(lattice, -50f), 0f)
        assertFalse("and it did not start a drag", s.active)
        s.drag(lattice, 100f)
        // The finger comes back 130: the strip returns to rest (100) and the other 30 is the lattice pager's again.
        assertEquals(-30f, s.drag(lattice, -130f), 0.001f)
        assertEquals(0f, s.x, 0f)
    }

    @Test
    fun `a left drag from Activity moves the strip from the Activity side and a right drag there is not the strip's`() {
        val s = strip()
        assertEquals("Activity is at its rest edge: a rightward delta goes nowhere", 40f, s.drag(activity, 40f), 0f)
        assertFalse(s.active)
        assertEquals(0f, s.drag(activity, -300f), 0f)
        assertEquals(700f, s.x, 0.001f)
        assertEquals(700f, s.xFor(activity), 0f)
    }

    @Test
    fun `past half commits to Activity and names lattice swipe_activity once`() {
        val s = strip()
        s.dragBy(lattice, 600f)
        val settle = s.release(velocityPx = 0f, flingPx = fling)
        assertNotNull(settle)
        assertEquals(activity, settle!!.target)
        assertTrue(settle.commits)
        assertTrue("a release names workspace.settle", settle.announces)
        assertEquals("lattice.swipe_activity", settle.controlReceipt)
        assertEquals("it runs from where the finger left it to the Activity page's rest", 600f, settle.fromX, 0.001f)
        assertEquals(width, settle.toX, 0f)
        assertTrue(s.settling)
        s.moveTo(settle, 800f)
        assertEquals(800f, s.x, 0f)
        assertTrue("the landing switches the page, once", s.finish(settle))
        assertFalse("and a second finish for the same settle does not", s.finish(settle))
    }

    @Test
    fun `a short drag settles back with no control receipt and no switch`() {
        val s = strip()
        s.dragBy(lattice, 300f)
        val settle = s.release(velocityPx = 100f, flingPx = fling)!!
        assertEquals(lattice, settle.target)
        assertFalse(settle.commits)
        assertNull("no control receipt for a drag that settles back", settle.controlReceipt)
        assertTrue("but the settle itself is the finger's, and is named", settle.announces)
        assertEquals(0f, settle.toX, 0f)
        assertFalse("nothing to switch", s.finish(settle))
        assertFalse("and the strip is at rest again", s.active)
        assertFalse(s.carried)
    }

    @Test
    fun `a fling past the threshold commits under half, and a slower one does not`() {
        val fast = strip().also { it.dragBy(lattice, 200f) }
        val commit = fast.release(velocityPx = 900f, flingPx = fling)!!
        assertEquals("a rightward fling from the lattice turns to Activity", activity, commit.target)
        assertEquals("lattice.swipe_activity", commit.controlReceipt)

        val slow = strip().also { it.dragBy(lattice, 200f) }
        assertEquals("just under the threshold stays", lattice, slow.release(velocityPx = 799f, flingPx = fling)!!.target)

        // From Activity: a leftward fling returns to the lattice with the swipe-back receipt; a rightward one stays.
        val back = strip().also { it.drag(activity, -150f) }
        val toLattice = back.release(velocityPx = -900f, flingPx = fling)!!
        assertEquals(lattice, toLattice.target)
        assertEquals("activity.swipe_back", toLattice.controlReceipt)
        val stay = strip().also { it.drag(activity, -450f) }
        assertEquals("a rightward fling holds Activity even past half", activity, stay.release(velocityPx = 900f, flingPx = fling)!!.target)
    }

    @Test
    fun `a fling against the drag turns exactly one page and never past it`() {
        // Past half toward Activity but flung back to the left: the fling wins, one page, back to the lattice.
        val s = strip().also { it.dragBy(lattice, 700f) }
        assertEquals(lattice, s.release(velocityPx = -1_000f, flingPx = fling)!!.target)
        // A left fling on the lattice at rest has nowhere to go: still the lattice (the strip never moves left of it).
        val rest = strip().also { it.drag(lattice, 10f) }
        assertEquals(lattice, rest.release(velocityPx = -5_000f, flingPx = fling)!!.target)
    }

    @Test
    fun `half the width is the line, and the drag that stops exactly on it stays where it began`() {
        val under = strip().also { it.dragBy(lattice, 499f) }
        assertEquals(lattice, under.release(0f, fling)!!.target)
        val over = strip().also { it.dragBy(lattice, 501f) }
        assertEquals(activity, over.release(0f, fling)!!.target)
        val tie = strip().also { it.dragBy(lattice, 500f) }
        assertEquals("on the line the nearer-page rule rounds to the page the drag began on", lattice, tie.release(0f, fling)!!.target)
        val tieBack = strip().also { it.drag(activity, -500f) }
        assertEquals("from Activity, on the line, it is the lattice (rounding is toward the higher page)", lattice, tieBack.release(0f, fling)!!.target)
    }

    @Test
    fun `a cancel settles back to where the drag began, silently`() {
        val s = strip()
        s.dragBy(lattice, 700f)
        val settle = s.cancel()!!
        assertEquals("back to the lattice, though it was past half", lattice, settle.target)
        assertFalse("a cancel names no settle", settle.announces)
        assertNull(settle.controlReceipt)
        assertFalse(s.finish(settle))
        assertFalse(s.active)

        val fromActivity = strip().also { it.drag(activity, -700f) }
        val back = fromActivity.cancel()!!
        assertEquals("a cancel on the Activity side returns to Activity", activity, back.target)
        assertEquals(width, back.toX, 0f)
        assertNull(back.controlReceipt)
    }

    @Test
    fun `a release or cancel with no drag held is not the strip's`() {
        val s = strip()
        assertNull(s.release(5_000f, fling))
        assertNull(s.cancel())
        s.drag(lattice, -20f)
        assertNull("a delta the strip did not take started nothing", s.release(5_000f, fling))
    }

    @Test
    fun `the strip holds its landing until the host shows the page, then lets go and the carried flag drops with it`() {
        val s = strip()
        s.dragBy(lattice, 700f)
        val settle = s.release(0f, fling)!!
        s.moveTo(settle, width)
        assertTrue(s.finish(settle))
        assertTrue("it holds where it landed", s.active)
        assertTrue("and the change the host is about to show is the finger's", s.carried)
        assertEquals(width, s.xFor(lattice), 0f)
        s.reconcile(lattice)
        assertTrue("the host still shows the old page: it keeps holding", s.active)
        assertEquals("a touch in that gap is not a new drag", 25f, s.drag(activity, 25f), 0f)
        s.reconcile(activity)
        assertFalse("the host shows Activity: the strip lets go", s.active)
        assertFalse(s.carried)
        assertEquals("and at rest the page decides where the strip is", width, s.xFor(activity), 0f)
    }

    @Test
    fun `a settle that is replaced is ignored, and the strip does not move for it`() {
        val s = strip()
        s.dragBy(lattice, 700f)
        val first = s.release(0f, fling)!!
        s.clear()
        s.dragBy(lattice, 100f)
        val second = s.release(0f, fling)!!
        s.moveTo(first, 900f)
        assertEquals("the old settle's frames land on nothing", 100f, s.x, 0f)
        assertFalse("and its end switches nothing", s.finish(first))
        assertTrue(s.settling)
        assertEquals(second.id, s.settle!!.id)
    }

    @Test
    fun `a touch is ignored while the settle runs, and the strip stands down if the page changed under a drag`() {
        val s = strip()
        s.dragBy(lattice, 300f)
        s.release(0f, fling)
        assertEquals("settling: the delta is all handed back", 50f, s.drag(lattice, 50f), 0f)

        val moved = strip().also { it.dragBy(lattice, 300f) }
        moved.reconcile(activity)
        assertFalse("a launch moved the page to Activity under the finger: the strip lets go", moved.active)
        assertFalse(moved.settling)
    }

    @Test
    fun `a finger that comes back through rest and goes on hands the strip back, and nothing is left owned`() {
        val s = strip()
        s.drag(lattice, 100f)
        // Back 130: the strip takes its 100 home and hands the other 30 back, and stands down on the spot.
        assertEquals(-30f, s.drag(lattice, -130f), 0.001f)
        assertFalse("nothing is left composed and owned off-screen", s.active)
        assertNull("and a lift has nothing to decide", s.release(0f, fling))
        assertEquals("the finger keeps going left: all of it is the pager's", -20f, s.drag(lattice, -20f), 0f)
        assertFalse(s.active)
        // And the finger turning right again starts a fresh drag.
        assertEquals(0f, s.drag(lattice, 40f), 0f)
        assertTrue(s.active)
        assertEquals(40f, s.x, 0f)
    }

    @Test
    fun `landing exactly on rest and then going on hands the strip back on the next delta`() {
        val s = strip()
        s.drag(lattice, 100f)
        assertEquals(0f, s.drag(lattice, -100f), 0f)
        assertEquals(0f, s.x, 0f)
        assertEquals("the next leftward delta is not the strip's", -10f, s.drag(lattice, -10f), 0f)
        assertFalse(s.active)
        assertNull(s.release(0f, fling))
    }

    @Test
    fun `the same hand-back from the Activity side`() {
        val s = strip()
        s.drag(activity, -100f)
        assertEquals("back 150: 100 home, 50 handed back", 50f, s.drag(activity, 150f), 0.001f)
        assertFalse(s.active)
        assertEquals(width, s.xFor(activity), 0f)
        assertNull(s.release(0f, fling))
    }

    @Test
    fun `abandon takes a held drag back silently and drops a settle that was on its way to the other page`() {
        val held = strip().also { it.dragBy(lattice, 700f) }
        held.abandon()
        val back = held.settle!!
        assertEquals("settled back to where it began, though it was past half", lattice, back.target)
        assertNull(back.controlReceipt)
        assertFalse(back.announces)

        val running = strip().also { it.dragBy(lattice, 700f) }
        running.release(0f, fling)
        assertTrue(running.settle!!.commits)
        running.abandon()
        assertNull("the commit never lands", running.settle)
        assertFalse(running.active)

        val goingBack = strip().also { it.dragBy(lattice, 100f) }
        val settle = goingBack.release(0f, fling)!!
        goingBack.abandon()
        assertEquals("a settle that was only going home is left to finish", settle.id, goingBack.settle?.id)
    }

    @Test
    fun `a settle bound for Activity that lands with a cover up settles back with no switch and no receipt`() {
        val s = strip().also { it.dragBy(lattice, 700f) }
        val settle = s.release(0f, fling)!!
        assertEquals("fixture: bound for Activity, with the control receipt a commit would write", "lattice.swipe_activity", settle.controlReceipt)
        s.moveTo(settle, width)
        assertFalse("the shelf route was set before landing: nothing commits", s.finish(settle, coverUp = true))
        assertFalse("and the strip is at rest on the lattice", s.active)
        assertFalse(s.carried)
        assertEquals(0f, s.xFor(lattice), 0f)
        assertFalse("a second finish for it does nothing either", s.finish(settle, coverUp = false))

        // The same settle with no cover up still commits (the red case: the live read is what refuses it).
        val clear = strip().also { it.dragBy(lattice, 700f) }
        val ok = clear.release(0f, fling)!!
        assertTrue(clear.finish(ok, coverUp = false))
        // A settle that was only going home is unaffected by a cover.
        val home = strip().also { it.dragBy(lattice, 100f) }
        assertFalse(home.finish(home.release(0f, fling)!!, coverUp = true))
    }

    @Test
    fun `a landing back on the lattice commits even with the shelf route admitted, and only a landing on Activity is vetoed`() {
        // A retained platform route is admitted while Activity is shown (the shelf is only drawn on the lattice page): going back to the lattice must still commit.
        val back = strip().also { it.drag(activity, -700f) }
        val settle = back.release(0f, fling)!!
        assertEquals(lattice, settle.target)
        assertEquals("activity.swipe_back", settle.controlReceipt)
        back.moveTo(settle, 0f)
        assertTrue("Activity -> lattice commits with the route admitted", back.finish(settle, coverUp = true))
        assertTrue(back.carried)

        // And lattice -> Activity with the same route still settles back silently.
        val forward = strip().also { it.dragBy(lattice, 700f) }
        val up = forward.release(0f, fling)!!
        forward.moveTo(up, width)
        assertFalse(forward.finish(up, coverUp = true))
        assertFalse(forward.active)
    }

    @Test
    fun `an unmeasured strip takes nothing`() {
        val s = CompanionWorkspaceStrip()
        assertEquals(30f, s.drag(lattice, 30f), 0f)
        assertFalse(s.active)
    }

    @Test
    fun `the control receipts are the existing names, written at the landing and nowhere else`() {
        val presentation = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        val strip = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceStrip.kt").readText()
        assertTrue(strip.contains("\"lattice.swipe_activity\""))
        assertTrue(strip.contains("\"activity.swipe_back\""))
        assertEquals("the Presentation writes no control receipt by a literal of its own", 0, Regex("control\\(\"(lattice\\.swipe_activity|activity\\.swipe_back)\"").findAll(presentation).count())
        val commit = presentation.substringAfter("val commitWorkspacePage").substringBefore("CompanionWorkspaceSettleRunner(")
        assertTrue("the control line is in the commit", commit.contains("BottomUiReceipts.log.control("))
        assertTrue("the switch is in the commit", commit.contains("CompanionWorkspaceState.show(request.target)"))
        // D37: the settle runs in a scope of its own, so the strip's release and landing do not recompose the Presentation's root.
        val runner = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceSettleRunner.kt").readText()
        assertTrue(
            "a settle that landed on the other page with no cover up (read live at the landing) is the only caller",
            runner.contains("if (strip.finish(request, coverUp = latestCoverUp())) latestCommit(request)"),
        )
        assertTrue(
            "the Presentation reads the cover live, at the landing, and hands the runner its one commit",
            presentation.contains("coverUp = { companionLiveWorkspaceCoverUp(") && presentation.contains("commit = commitWorkspacePage,"),
        )
        assertEquals("one commit site", 1, Regex("latestCommit\\(").findAll(runner).count())
        assertEquals("and the Presentation never calls the commit itself", 0, Regex("commitWorkspacePage\\(").findAll(presentation).count())
        assertTrue(
            "the finger's settle is named, and a cancel's runs unnamed",
            runner.contains("if (request.announces) bottomUiTransition(\"workspace.settle\", receiptDisplayId) { run() } else run()"),
        )
    }

    @Test
    fun `the timeline scrubber claims its own drag before the strip, and a scrub that starts after it hands the strip back`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceSurface.kt").readText()
        // The scrubber claims the DOWN in the Initial pass, before any ancestor's detector (the strip's draggable) sees the stream, and it opens the
        // gate with a new serial the moment its own drag passes slop.
        val scrubber = source.substringAfter("val timelineModifier = ").substringBefore("Row(\n        modifier = modifier,")
        // F13: the lane reads the one slider grammar (detectSliderTouch) in the Initial pass with the DOWN claimed, and opens the gate when its gesture engages.
        val grammar = File("src/main/kotlin/com/gruning/mimir/rewrite/UtilitySliderTouch.kt").readText()
        assertTrue("claims the down first, in the Initial pass", scrubber.contains("detectSliderTouch(") && scrubber.contains("pass = PointerEventPass.Initial") && scrubber.contains("claimDown = true"))
        assertTrue("and the grammar consumes the down when asked to, and every change of its own drag", grammar.contains("if (claimDown) down.consume()") && grammar.contains("change.consume()"))
        assertTrue("opens the gate with a new serial the moment the gesture engages", scrubber.contains("seekGate.begin()") && scrubber.contains("onEngaged"))
        // The strip's half: the drag state reads the gate. A serial that moved since this drag began means a scrub took the stream, so the workspace
        // goes back to where it started instead of following or releasing.
        val drag = source.substringAfter("val workspaceDragState = ").substringBefore("Box(Modifier.fillMaxSize().then(activitySwipeModifier))")
        assertTrue("the drag is cancelled by a changed serial", drag.contains("seekGate.serial != workspaceDragSerial[0]") && drag.contains("latestWorkspaceDragCancel?.invoke()"))
        assertTrue("and the release is only the strip's on the same serial", drag.contains("!workspaceWatch.takenAway && latestEnabled && seekGate.serial == workspaceDragSerial[0]) latestWorkspaceDragEnd?.invoke(velocity) else latestWorkspaceDragCancel?.invoke()"))
        assertFalse("the old detector is gone", source.contains("detectHorizontalDragGestures") || source.contains("onSwipeLeft"))
    }

    @Test
    fun `the accessible return to the Shelf is the swipe's own settle and lands on the lattice with the swipe's receipt`() {
        val s = strip()
        val request = s.returnToShelf(fling)!!
        assertEquals(lattice, request.target)
        assertEquals(activity, request.from)
        assertTrue("it is announced, as a release is", request.announces)
        assertEquals("the same control receipt as the swipe", "activity.swipe_back", request.controlReceipt)
        assertSame("the host runs this settle", request, s.settle)
        assertEquals("it is a short flick off Activity's rest, so the settle animates the rest of the way", width * 0.9f, request.fromX, 0.001f)
        assertEquals(0f, request.toX, 0f)
        assertTrue("the landing commits once: the host switches the page and writes the receipt", s.finish(request))
        assertFalse(s.finish(request))
        assertEquals(lattice, request.target)
    }

    @Test
    fun `the accessible return is refused when the strip is unmeasured or already settling`() {
        assertNull(CompanionWorkspaceStrip().returnToShelf(fling))
        val busy = strip()
        assertNotNull(busy.returnToShelf(fling))
        assertNull("a second one while the first settles does nothing", busy.returnToShelf(fling))
    }
}
