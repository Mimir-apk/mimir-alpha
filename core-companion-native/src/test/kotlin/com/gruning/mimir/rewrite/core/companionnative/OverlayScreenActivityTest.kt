package com.gruning.mimir.rewrite.core.companionnative

import android.view.MotionEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-5 triage B2 (Thor build 5fa4c795, 2026-09-11): Main dimmed while the player held the Bottom's ᛗ and used the
 * menu it opened. Native overlay touches only re-armed the ᛗ's own hide timer; nothing reached MainActivity's
 * screen-idle authority. The overlay now reports screen activity: DIRECT for a touch one of its windows received,
 * OUTSIDE for the copy of a touch another window received.
 *
 * The routing and the observer slot are tested directly. The emission sites live in window dispatch code a JVM test
 * cannot drive, so they are pinned as source, the convention [SecondaryMenuCardDragTest] follows.
 */
class OverlayScreenActivityTest {

    private val code: String by lazy {
        val file = File(
            "src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt",
        )
        assertTrue("AndroidCompanionOverlayController.kt must be readable", file.isFile)
        file.readText()
    }

    @Test
    fun `a DOWN is direct activity and an outside copy is outside activity`() {
        assertEquals(OverlayScreenActivity.DIRECT, overlayScreenActivityFor(MotionEvent.ACTION_DOWN))
        assertEquals(OverlayScreenActivity.OUTSIDE, overlayScreenActivityFor(MotionEvent.ACTION_OUTSIDE))
    }

    @Test
    fun `the rest of a gesture is not new activity`() {
        assertNull(overlayScreenActivityFor(MotionEvent.ACTION_MOVE))
        assertNull(overlayScreenActivityFor(MotionEvent.ACTION_UP))
        assertNull(overlayScreenActivityFor(MotionEvent.ACTION_CANCEL))
        assertNull(overlayScreenActivityFor(MotionEvent.ACTION_POINTER_DOWN))
    }

    @Test
    fun `the slot delivers to the installed observer and a null install removes it`() {
        val slot = OverlayScreenActivityObserverSlot()
        val seen = mutableListOf<OverlayScreenActivity>()
        slot.set { seen += it }
        slot.emit(OverlayScreenActivity.DIRECT)
        slot.emit(null)
        slot.emit(OverlayScreenActivity.OUTSIDE)
        assertEquals(listOf(OverlayScreenActivity.DIRECT, OverlayScreenActivity.OUTSIDE), seen)

        slot.set(null)
        slot.emit(OverlayScreenActivity.DIRECT)
        assertEquals(2, seen.size)
    }

    @Test
    fun `a stale host's clear leaves its successor installed`() {
        val slot = OverlayScreenActivityObserverSlot()
        val old = mutableListOf<OverlayScreenActivity>()
        val new = mutableListOf<OverlayScreenActivity>()
        val oldObserver: (OverlayScreenActivity) -> Unit = { old += it }
        val newObserver: (OverlayScreenActivity) -> Unit = { new += it }
        slot.set(oldObserver)
        // MainActivity recreation: the replacement's onCreate can run before the old instance's onDestroy.
        slot.set(newObserver)
        assertFalse("a clear for an observer that is no longer installed must refuse", slot.clear(oldObserver))
        slot.emit(OverlayScreenActivity.DIRECT)
        assertEquals(listOf(OverlayScreenActivity.DIRECT), new)
        assertTrue(old.isEmpty())

        assertTrue(slot.clear(newObserver))
        slot.emit(OverlayScreenActivity.DIRECT)
        assertEquals(1, new.size)
    }

    @Test
    fun `the M DOWN is routed before the button handles it`() {
        val handler = functionBody("private fun handleButtonTouch(")
        val routed = handler.indexOf("emitScreenActivity(overlayScreenActivityFor(event.actionMasked))")
        assertTrue("handleButtonTouch must route each event it receives", routed >= 0)
        assertTrue(
            "the route must come before the DOWN branch, so the M hold that opens the menu counts",
            routed < handler.indexOf("MotionEvent.ACTION_DOWN ->"),
        )
        assertEquals("exactly one route in the button handler", 1, screenActivityEmissions(handler))
    }

    @Test
    fun `the outside copy is read before the post and reported inside it`() {
        val root = code.substringAfter("private fun buildButton(context: Context): View {", "")
            .substringBefore("return super.dispatchTouchEvent(event)", "")
        val read = root.indexOf("val screenActivity = overlayScreenActivityFor(event.actionMasked)")
        val post = root.indexOf("handler.postDelayed(")
        val reveal = root.indexOf("notifyPrimaryIdleInteraction()")
        val emit = root.indexOf("emitScreenActivity(screenActivity)")
        assertTrue("the ACTION_OUTSIDE branch must read the activity while the event is still live", read >= 0)
        assertTrue("and before posting: the event is recycled once this dispatch returns", read < post)
        assertTrue("the existing reveal stays in the posted runnable", reveal > post)
        assertTrue("the outside activity is reported from that runnable, after the reveal", emit > reveal)
    }

    @Test
    fun `the menu root routes once, before either touch route`() {
        val menu = code.substringAfter("val outsideCapture = if (fullScreenSecondary)", "")
            .substringBefore("override fun dispatchKeyEvent(", "")
        val outgoing = menu.indexOf("if (overlayTransitionOutgoing && overlayTransitionView === this) return true")
        val routed = menu.indexOf("emitScreenActivity(overlayScreenActivityFor(event.actionMasked))")
        val capture = menu.indexOf("if (outsideCapture != null) {")
        assertTrue("the menu root must route its events", routed >= 0)
        assertTrue("after the outgoing-swap swallow", outgoing in 0 until routed)
        assertTrue("and before the capture route, so a DOWN on either route counts once", routed < capture)
        assertEquals("exactly one route in the menu root", 1, screenActivityEmissions(menu))
    }

    @Test
    fun `an admitted edge-catcher DOWN reports screen activity`() {
        // R6-P1-05: the edge-back strip is the one native overlay window that never wakes a dimmed
        // screen. It must route exactly like handleButtonTouch and the menu root, but only once the
        // ACTION_DOWN clears companionEdgeCatcherIsTouchable -- an inadmissible DOWN is not a real touch.
        val handler = functionBody("private fun handleEdgeSwipe(")
        val down = handler.substringAfter("MotionEvent.ACTION_DOWN ->", "")
            .substringBefore("MotionEvent.ACTION_MOVE ->", "")
        assertTrue("ACTION_DOWN branch not found in handleEdgeSwipe", down.isNotEmpty())
        val admitted = down.substringAfter(
            // R6-P2-08: this pin legitimately moves — the admission call gained a 4th argument
            // (ownMirrorFolderEdgeBackEnabled), the shelf's own open-folder exception to the same rule
            // the drawer already had. The gate itself, and everything this test checks about it (found
            // at all, routes once, before the emission), is otherwise unchanged.
            // 10-07: it moved again. The six copies of that call (and its 4 arguments) are now one private
            // edgeCatcherTouchable() that also reads the keyboard-on-the-Bottom term; the gate is unchanged
            // otherwise, and EdgeCatcherKeyboardAndMButtonTest pins that it is the same function.
            "if (!edgeCatcherTouchable()) return false",
            "",
        )
        assertTrue("the admission gate was not found in the DOWN branch", admitted.isNotEmpty())
        assertTrue(
            "an admitted DOWN must report screen activity, the same call every other overlay window uses",
            admitted.contains("emitScreenActivity(overlayScreenActivityFor(event.actionMasked))"),
        )
        assertEquals("exactly one route in the edge-catcher handler", 1, screenActivityEmissions(handler))
    }

    @Test
    fun `the emission detector sees every emission form and nothing else`() {
        assertEquals(0, screenActivityEmissions("notifyPrimaryIdleInteraction()\nreplaceView(buildMenu(context))"))
        assertEquals(1, screenActivityEmissions("emitScreenActivity(OverlayScreenActivity.DIRECT)"))
        assertEquals(1, screenActivityEmissions("screenActivitySlot.emit(activity)"))
        assertEquals(1, screenActivityEmissions("overlay.notifyScreenActivity(OverlayScreenActivity.DIRECT)"))
        assertEquals(2, screenActivityEmissions("emitScreenActivity(a)\nemitScreenActivity(b)"))
    }

    @Test
    fun `showing or collapsing the menu and mounting the button report no activity`() {
        for (signature in listOf(
            "private fun showMenu()",
            "private fun collapseMenu()",
            "private fun mountOverlayWindowsOnMain(",
        )) {
            assertEquals(
                "$signature is not a touch; an emission there keeps Main awake with nobody at the Bottom",
                0,
                screenActivityEmissions(functionBody(signature)),
            )
        }
    }

    @Test
    fun `completing a quick menu posts its collapse`() {
        // Premise pin for MainActivity's RETURN TO SHELF, which posts the reclaim behind this collapse (round-5 triage
        // 4b). That order holds only while completion is a plain FIFO post to the main looper.
        val complete = functionBody("override fun completeQuickMenu(")
        assertTrue("completion must post", complete.contains("handler.post {"))
        assertTrue(
            "the collapse must run inside that post",
            complete.indexOf("collapseMenu()") > complete.indexOf("handler.post {"),
        )
        assertFalse("a delayed or front-of-queue post would reorder against the reclaim", complete.contains("post" + "Delayed") || complete.contains("postAtFrontOfQueue"))
    }

    private fun functionBody(signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val open = code.indexOf('{', start)
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open, index + 1)
            }
        }
        error("unbalanced body for $signature")
    }
}

/** Counts screen-activity emissions in a slice of source: the controller's helper, the slot, and the contract hook. */
internal fun screenActivityEmissions(source: String): Int =
    Regex("""\b(emitScreenActivity|screenActivitySlot\.emit|notifyScreenActivity)\(""").findAll(source).count()
