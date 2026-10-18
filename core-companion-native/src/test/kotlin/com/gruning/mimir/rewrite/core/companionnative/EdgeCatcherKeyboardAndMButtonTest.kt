package com.gruning.mimir.rewrite.core.companionnative

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * On Thor, the edge-back guard blocked dragging the M button over Bottom apps such as Chrome;
 * the guard and button also needed to stand down while the Bottom keyboard was open.
 *
 * Two defects, two causes, pinned apart.
 *
 * ## 1. The strip took the ᛗ's touches (z-order)
 *
 * The log of that session shows six NATIVE_EDGE_BACK INTERACTING/RECENT pairs and two reclaims (21:50:03, :24, :31) over
 * Chrome with NO `m_button_receipt BUTTON_DOWN` between them: those were attempts to grab the ᛗ, and the
 * edge-back strip had them. The ᛗ is 56 dp and may sit flush at the right edge; the strip is 32 dp wide and as tall as the
 * display, and it was added AFTER the ᛗ, so over a foreign app (touchable) it was on top of the ᛗ's right 32 dp. A
 * leftward drag from there is exactly the 40 dp gesture that fires EdgeBack. (The `DRAG_CANCEL` receipts that followed at
 * :25 and :27 were NOT failures: that line is the drag START, and a good drag logs DOWN, DRAG_CANCEL, UP dragging=true. They
 * were completed drags over Mimir's own mirror after the reclaim, where the strip is untouchable.)
 *
 * ## 2. The strip and the ᛗ stood over the keyboard
 *
 * Mimir's keyboard on the Bottom fills the display and the overlay windows draw above it, so the ᛗ floated over the keys and
 * the strip took the keys' right edge.
 *
 * The windows are Android window state a JVM cannot drive, so what can be pinned is pinned as source (the convention of
 * [OverlayScreenActivityTest] and [PrimaryMIdleThemeRebuildTest]); the state holder and the three decisions are tested directly.
 */
class EdgeCatcherKeyboardAndMButtonTest {

    @Before
    @After
    fun reset() = BottomImeVisibility.resetForTest()

    private val code: String by lazy {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt")
        assertTrue("AndroidCompanionOverlayController.kt must be readable", file.isFile)
        file.readText()
    }

    // ---- the state holder ---------------------------------------------------------------------------------------------

    @Test
    fun `the display follows the keyboard and a listener hears only a change`() {
        val ime = Any()
        var heard = 0
        BottomImeVisibility.addListener { heard += 1 }
        assertNull(BottomImeVisibility.displayId)

        BottomImeVisibility.report(ime, 4)
        BottomImeVisibility.report(ime, 4)
        assertEquals(4, BottomImeVisibility.displayId)
        assertEquals("the same value twice is one change", 1, heard)

        BottomImeVisibility.report(ime, null)
        assertNull(BottomImeVisibility.displayId)
        assertEquals(2, heard)
        BottomImeVisibility.report(ime, null)
        assertEquals("hiding what is hidden is not a change", 2, heard)
    }

    @Test
    fun `a hide from a service that is not the one showing never clears the live keyboard`() {
        val old = Any()
        val current = Any()
        BottomImeVisibility.report(old, 4)
        BottomImeVisibility.report(current, 4)

        BottomImeVisibility.report(old, null)
        assertEquals("the old instance being destroyed leaves the new one's keyboard up", 4, BottomImeVisibility.displayId)

        BottomImeVisibility.report(current, null)
        assertNull(BottomImeVisibility.displayId)
    }

    @Test
    fun `a listener that throws does not stop the others or the report`() {
        var heard = 0
        BottomImeVisibility.addListener { error("boom") }
        BottomImeVisibility.addListener { heard += 1 }
        BottomImeVisibility.report(Any(), 4)
        assertEquals(1, heard)
        assertEquals(4, BottomImeVisibility.displayId)
    }

    // ---- the three decisions ------------------------------------------------------------------------------------------

    @Test
    fun `only the Bottom's overlay on the keyboard's own display stands down`() {
        val bottom = CompanionOverlayRole.SECONDARY_COMPANION
        assertTrue(companionBottomImeShown(bottom, mountedDisplayId = 4, imeDisplayId = 4))
        assertFalse("no keyboard", companionBottomImeShown(bottom, 4, null))
        assertFalse("a keyboard on another display", companionBottomImeShown(bottom, 4, 0))
        assertFalse("nothing mounted", companionBottomImeShown(bottom, null, 4))
        assertFalse("the single-display Main badge must not respond to Bottom IME state", companionBottomImeShown(CompanionOverlayRole.PRIMARY_SINGLE, 0, 0))
    }

    @Test
    fun `the button is hidden while a keyboard is on the Bottom, comes back after it, and an open menu is left alone`() {
        assertTrue("no keyboard: shown", companionOverlayViewVisible(visible = true, menuOpen = false, bottomImeShown = false))
        assertFalse("keyboard up: hidden", companionOverlayViewVisible(visible = true, menuOpen = false, bottomImeShown = true))
        assertTrue("keyboard down: back", companionOverlayViewVisible(visible = true, menuOpen = false, bottomImeShown = false))
        assertTrue("an open menu is not the button", companionOverlayViewVisible(visible = true, menuOpen = true, bottomImeShown = true))
        assertFalse("the topology's hide still wins with no keyboard", companionOverlayViewVisible(visible = false, menuOpen = false, bottomImeShown = false))
        assertFalse("and with one", companionOverlayViewVisible(visible = false, menuOpen = true, bottomImeShown = true))
    }

    @Test
    fun `the strip is never touchable while a keyboard is on the Bottom, whatever else would admit it`() {
        for (dimmed in listOf(false, true)) {
            for (drawer in listOf(false, true)) {
                for (folder in listOf(false, true)) {
                    for (game in listOf(false, true)) {
                        assertFalse(
                            "dimmed=$dimmed drawer=$drawer folder=$folder game=$game",
                            companionEdgeCatcherIsTouchable(dimmed, drawer, game, folder, bottomImeShown = true),
                        )
                    }
                }
            }
        }
        // And the term only ever removes admission: with no keyboard every old answer is unchanged.
        assertTrue(companionEdgeCatcherIsTouchable(dimmed = false, ownMirrorDrawerEdgeBackEnabled = false, bottomImeShown = false))
        assertTrue(companionEdgeCatcherIsTouchable(dimmed = true, ownMirrorDrawerEdgeBackEnabled = true, bottomImeShown = false))
        assertFalse(companionEdgeCatcherIsTouchable(dimmed = true, ownMirrorDrawerEdgeBackEnabled = false, bottomImeShown = false))
    }

    // ---- the controller wiring, pinned as source ----------------------------------------------------------------------

    @Test
    fun `cause 1 - the strip is added before the button so the button window is above it`() {
        val mount = functionBody("private fun mountOverlayWindowsOnMain(")
        val strip = mount.indexOf("addEdgeCatcher(candidateWindowManager, candidateContext)")
        val button = mount.indexOf("candidateWindowManager.addView(candidateView, overlayParams(candidateContext))")
        assertTrue("the strip add was not found in the mount", strip >= 0)
        assertTrue("the button add was not found in the mount", button >= 0)
        assertTrue("the strip must be added first: later windows of one type stack on top", strip < button)
        assertTrue(
            "a button that fails to add takes the strip it was mounted over back down",
            mount.contains("candidateEdge?.let { runCatching { candidateWindowManager.removeViewImmediate(it) } }"),
        )
        assertEquals(
            "nothing else re-adds the strip later (a re-add would put it above the button again)",
            1,
            Regex("""(?<!fun )addEdgeCatcher\(""").findAll(code).count(),
        )
        // Every later button window (menu close, theme change, idle reveal that rebuilds) is added through here, after the strip.
        val add = functionBody("private fun addCurrentView(")
        assertTrue(add.contains("wm.addView(view, overlayParams("))
    }

    @Test
    fun `cause 2 - one function answers whether the strip is touchable and it reads the keyboard`() {
        assertEquals(
            "the predicate is called from one place in the controller (plus its declaration)",
            2,
            Regex("""companionEdgeCatcherIsTouchable\(""").findAll(code).count(),
        )
        val touchable = code.substringAfter("private fun edgeCatcherTouchable(): Boolean = companionEdgeCatcherIsTouchable(", "")
            .substringBefore(")\n", "")
        assertTrue(
            "its arguments, in order",
            touchable.replace(Regex("\\s+"), " ").trim() ==
                "dimmed, ownMirrorDrawerEdgeBackEnabled, dualScreenGameOwnsBottom, ownMirrorFolderEdgeBackEnabled, bottomImeShown(),",
        )
        assertTrue("the window flag follows it", functionBody("private fun updateEdgeCatcherTouchability(").contains("if (edgeCatcherTouchable()) {"))
        assertTrue("the DOWN gate follows it", functionBody("private fun handleEdgeSwipe(").contains("if (!edgeCatcherTouchable()) return false"))
        for (setter in listOf(
            "fun setOwnMirrorDrawerEdgeBackEnabled(",
            "fun setOwnMirrorFolderEdgeBackEnabled(",
            "fun setDualScreenGameOwnsBottom(",
        )) {
            assertTrue("$setter ends a gesture the strip no longer admits and updates its flag", functionBody(setter).contains("refreshEdgeCatcher()"))
        }
        val refresh = functionBody("private fun refreshEdgeCatcher(")
        assertTrue(refresh.contains("if (!edgeCatcherTouchable()) edgeInteraction.finish()"))
        assertTrue(refresh.contains("updateEdgeCatcherTouchability()"))
    }

    @Test
    fun `an edge-back gesture cannot fire while a keyboard is on the Bottom`() {
        val move = functionBody("private fun handleEdgeSwipe(")
            .substringAfter("MotionEvent.ACTION_MOVE ->", "")
            .substringBefore("MotionEvent.ACTION_UP", "")
        val gate = move.indexOf("!bottomImeShown()")
        val emit = move.indexOf("_actions.tryEmit(CompanionOverlayAction.EdgeBack)")
        assertTrue("the MOVE branch was not found", move.isNotEmpty())
        assertTrue("the fire reads the keyboard", gate >= 0)
        assertTrue("and does so before it emits EdgeBack, the only thing that reclaims", emit > gate)
        assertEquals("EdgeBack is emitted from this one place", 1, Regex("""CompanionOverlayAction\.EdgeBack\)""").findAll(code).count())
    }

    @Test
    fun `the button's root visibility has one source, and a keyboard change ends its gestures, hides it and stands the strip down`() {
        assertFalse("the raw structural-only visibility is gone", code.contains("if (visible) View.VISIBLE else View.GONE"))
        for (signature in listOf(
            "private fun mountOverlayWindowsOnMain(",
            "override fun setVisible(",
            "private fun addCurrentView(",
            "private fun applyBottomImeChange(",
        )) {
            assertTrue("$signature takes the root's visibility from overlayViewVisibility()", functionBody(signature).contains("visibility = overlayViewVisibility()"))
        }
        assertTrue(
            "visibility is the topology's visible, then the keyboard",
            code.contains("if (companionOverlayViewVisible(visible, menuOpen, bottomImeShown())) View.VISIBLE else View.GONE"),
        )
        assertTrue(
            "a mount made while the keyboard is already up knows it, so the keyboard going down re-arms the ᛗ",
            functionBody("private fun mountOverlayWindowsOnMain(").contains("bottomImeApplied = bottomImeShown()"),
        )
        val apply = functionBody("private fun applyBottomImeChange(")
        assertTrue("a gesture on the hidden button ends", apply.contains("finishOverlayViewInteractions()") && apply.contains("cancelLongPress()"))
        assertTrue("the strip stands down with it", apply.contains("refreshEdgeCatcher()"))
        assertTrue("the button returns revealed and re-armed, not idle-hidden", apply.contains("if (wasShown && !shown) notifyPrimaryIdleInteraction()"))
        assertTrue(
            "the controller hears the keyboard on the main looper, for the life of the process",
            code.contains("BottomImeVisibility.addListener { handler.post { applyBottomImeChange() } }"),
        )
        assertTrue(
            "and the keyboard is read for the display it is mounted on",
            code.contains("companionBottomImeShown(displayRole, mountedOverlayDisplayId, BottomImeVisibility.displayId)"),
        )
    }

    @Test
    fun `the DRAG_CANCEL receipt is the drag start and the drag it logs really moves the window`() {
        // `DRAG_CANCEL` is logged where a MOVE passes the slop and the pending long press is
        // cancelled; the same branch then moves the window and the release logs dragging=true.
        val move = functionBody("private fun handleButtonTouch(")
            .substringAfter("MotionEvent.ACTION_MOVE ->", "")
            .substringBefore("MotionEvent.ACTION_UP ->", "")
        val receipt = move.indexOf("event=DRAG_CANCEL")
        val start = move.indexOf("dragging = true")
        val moves = move.indexOf("windowManager?.updateViewLayout(overlayView, lp)")
        assertTrue("the drag branch was not found", move.isNotEmpty())
        assertTrue("the drag latches before the receipt", start in 0 until receipt)
        assertTrue("and the window moves after it, in the same MOVE", moves > receipt)
    }

    @Test
    fun `Mimir's key presses reach the controller as passive screen activity, and nothing is built when nothing listens`() {
        BottomImeVisibility.noteKeyActivity() // no listener: nothing happens
        var heard = 0
        BottomImeVisibility.addKeyActivityListener { error("boom") }
        BottomImeVisibility.addKeyActivityListener { heard += 1 }
        BottomImeVisibility.noteKeyActivity()
        BottomImeVisibility.noteKeyActivity()
        assertEquals("every press is heard and a throwing listener stops nobody", 2, heard)
        assertTrue(
            "the controller turns it into OUTSIDE activity (re-arms an awake session, never wakes a dimmed one)",
            code.contains("BottomImeVisibility.addKeyActivityListener { notifyScreenActivity(OverlayScreenActivity.OUTSIDE) }"),
        )
    }

    @Test
    fun `a press the keyboard interrupted is aborted whole, so its lift is not a tap`() {
        val touch = functionBody("private fun handleButtonTouch(")
        val down = touch.substringAfter("MotionEvent.ACTION_DOWN ->", "").substringBefore("MotionEvent.ACTION_MOVE ->", "")
        assertTrue(
            "a DOWN under a keyboard is ignored, and any other DOWN clears the flag",
            down.trimStart().lines().drop(1).take(3).joinToString("\n").let {
                it.contains("buttonGestureAborted = bottomImeShown() && !menuOpen") && it.contains("if (buttonGestureAborted) return true")
            },
        )
        val move = touch.substringAfter("MotionEvent.ACTION_MOVE ->", "").substringBefore("MotionEvent.ACTION_UP ->", "")
        assertTrue("MOVEs of an aborted press move nothing", move.contains("if (lp == null || buttonGestureAborted) return true"))
        val up = touch.substringAfter("MotionEvent.ACTION_UP ->", "").substringBefore("MotionEvent.ACTION_CANCEL ->", "")
        assertTrue("its lift does nothing, before any release is classified", up.indexOf("if (buttonGestureAborted) return true") in 0 until up.indexOf("classifyMButtonRelease("))
        val cancel = touch.substringAfter("MotionEvent.ACTION_CANCEL ->", "")
        assertTrue("and so does its CANCEL", cancel.trimStart().lines().drop(1).first().trim() == "if (buttonGestureAborted) return true")
        assertTrue(
            "the keyboard coming up sets it, with the press's timers, drag and press look all reset",
            functionBody("private fun applyBottomImeChange(").let {
                it.contains("buttonGestureAborted = true") && it.contains("cancelLongPress()") && it.contains("coin.animate().cancel()")
            },
        )
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
