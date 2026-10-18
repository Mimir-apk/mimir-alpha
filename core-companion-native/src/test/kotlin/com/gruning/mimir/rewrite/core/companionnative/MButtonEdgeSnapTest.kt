package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OR-2: edge snap, the release classifier, the badge-bounds value, and the pins that keep the controller wired to them; the single-display coin's glide (policy
 * 2026-10-08); and the Bottom coin's free placement (policy 2026-10-09, which retired the corner slot of 2026-10-08): it stays exactly where it is dropped, on every
 * page and over every app, in ONE persisted position, with no snap-back and no glide.
 */
class MButtonEdgeSnapTest {
    // 1000 x 600 display, 56 px badge: the window can sit at x in 0..944 and y in 0..544.
    private val bounds = 1000 to 600
    private val size = 56
    private val inset = 14

    private fun snap(x: Int, y: Int, on: Pair<Int, Int> = bounds) =
        snapMButtonToNearestEdge(x, y, on, size, size, inset)

    @Test
    fun `a drop nearest the right edge settles on it at the safe inset and keeps its height`() {
        val result = snap(900, 300)
        assertEquals(MButtonEdge.RIGHT, result.edge)
        assertEquals(930 to 300, result.position)
    }

    @Test
    fun `a drop nearest the left edge settles on it`() {
        val result = snap(30, 200)
        assertEquals(MButtonEdge.LEFT, result.edge)
        assertEquals(14 to 200, result.position)
    }

    @Test
    fun `a drop nearest the top edge settles on it and keeps its column`() {
        val result = snap(500, 20)
        assertEquals(MButtonEdge.TOP, result.edge)
        assertEquals(500 to 14, result.position)
    }

    @Test
    fun `a drop nearest the bottom edge settles on it and keeps its column`() {
        val result = snap(500, 530)
        assertEquals(MButtonEdge.BOTTOM, result.edge)
        assertEquals(500 to 530, result.position)
    }

    @Test
    fun `a drop in a corner is held off both edges by the inset`() {
        val result = snap(5, 3)
        assertEquals(MButtonEdge.TOP, result.edge)
        assertEquals(14 to 14, result.position)
    }

    @Test
    fun `an exact tie prefers the vertical sides over top and bottom`() {
        // 1056 x 1056 with a 56 px badge: the centre is 500 from every edge.
        val result = snapMButtonToNearestEdge(500, 500, 1056 to 1056, size, size, inset)
        assertEquals(MButtonEdge.LEFT, result.edge)
        assertEquals(14 to 500, result.position)
    }

    @Test
    fun `a position past the display is clamped before it is measured`() {
        val result = snap(-50, 700)
        assertEquals(MButtonEdge.LEFT, result.edge)
        assertEquals(14 to 530, result.position)
    }

    @Test
    fun `the default top right position is already an edge position and snapping it changes nothing`() {
        val default = defaultMButtonPosition(bounds, size, size, inset)
        assertEquals(930 to 14, default)
        val result = snap(default.first, default.second)
        assertEquals(default, result.position)
        assertEquals(MButtonEdge.RIGHT, result.edge)
    }

    @Test
    fun `snapping is idempotent across the whole display`() {
        for (x in 0..944 step 37) {
            for (y in 0..544 step 41) {
                val once = snap(x, y).position
                val twice = snap(once.first, once.second).position
                assertEquals("($x, $y) moved again after settling", once, twice)
            }
        }
    }

    @Test
    fun `every settled position keeps the whole badge on the display`() {
        for (x in -100..1100 step 53) {
            for (y in -100..700 step 47) {
                val (sx, sy) = snap(x, y).position
                assertTrue("($x, $y) -> x=$sx", sx in 0..944)
                assertTrue("($x, $y) -> y=$sy", sy in 0..544)
            }
        }
    }

    @Test
    fun `a display too small to keep the inset on both sides falls back to flush edges`() {
        val tiny = 80 to 80
        val left = snapMButtonToNearestEdge(10, 10, tiny, size, size, inset)
        assertEquals(MButtonEdge.LEFT, left.edge)
        assertEquals(0 to 10, left.position)
        val right = snapMButtonToNearestEdge(20, 10, tiny, size, size, inset)
        assertEquals(MButtonEdge.RIGHT, right.edge)
        assertEquals(24 to 10, right.position)
    }

    @Test
    fun `a badge larger than the display cannot produce a negative position`() {
        val result = snapMButtonToNearestEdge(40, 40, 30 to 30, size, size, inset)
        assertEquals(0 to 0, result.position)
    }

    @Test
    fun `only the single display badge snaps and taps open the menu`() {
        assertTrue(mButtonEdgeSnapApplies(CompanionOverlayRole.PRIMARY_SINGLE))
        assertTrue(mButtonTapOpensMenu(CompanionOverlayRole.PRIMARY_SINGLE))
        assertFalse("the Bottom coin stays where it is dropped", mButtonEdgeSnapApplies(CompanionOverlayRole.SECONDARY_COMPANION))
        assertFalse(mButtonTapOpensMenu(CompanionOverlayRole.SECONDARY_COMPANION))
    }

    @Test
    fun `a release is a hold, a drag or a tap and a hold wins`() {
        val primary = CompanionOverlayRole.PRIMARY_SINGLE
        assertEquals(MButtonReleaseOutcome.HOLD_HANDLED, classifyMButtonRelease(primary, longPressFired = true, wasDragging = false))
        assertEquals(MButtonReleaseOutcome.HOLD_HANDLED, classifyMButtonRelease(primary, longPressFired = true, wasDragging = true))
        assertEquals(MButtonReleaseOutcome.DRAG_SNAP, classifyMButtonRelease(primary, longPressFired = false, wasDragging = true))
        assertEquals(MButtonReleaseOutcome.TAP_OPENS_MENU, classifyMButtonRelease(primary, longPressFired = false, wasDragging = false))
    }

    @Test
    fun `a tap opens the music panel only on the single display with music to control`() {
        val primary = CompanionOverlayRole.PRIMARY_SINGLE
        assertEquals(
            MButtonReleaseOutcome.TAP_OPENS_MUSIC,
            classifyMButtonRelease(primary, longPressFired = false, wasDragging = false, musicPanelAvailable = true),
        )
        assertEquals(
            "no music: the menu, as before",
            MButtonReleaseOutcome.TAP_OPENS_MENU,
            classifyMButtonRelease(primary, longPressFired = false, wasDragging = false, musicPanelAvailable = false),
        )
        assertEquals(
            "the default is the menu",
            MButtonReleaseOutcome.TAP_OPENS_MENU,
            classifyMButtonRelease(primary, longPressFired = false, wasDragging = false),
        )
        assertTrue(mButtonTapOpensMusic(primary, true))
        assertFalse(mButtonTapOpensMusic(primary, false))
        assertFalse(mButtonTapOpensMusic(CompanionOverlayRole.SECONDARY_COMPANION, true))
    }

    @Test
    fun `a hold always opens the menu and a drag still moves the badge whatever the music says`() {
        val primary = CompanionOverlayRole.PRIMARY_SINGLE
        assertEquals(
            MButtonReleaseOutcome.HOLD_HANDLED,
            classifyMButtonRelease(primary, longPressFired = true, wasDragging = false, musicPanelAvailable = true),
        )
        assertEquals(
            MButtonReleaseOutcome.HOLD_HANDLED,
            classifyMButtonRelease(primary, longPressFired = true, wasDragging = true, musicPanelAvailable = true),
        )
        assertEquals(
            MButtonReleaseOutcome.DRAG_SNAP,
            classifyMButtonRelease(primary, longPressFired = false, wasDragging = true, musicPanelAvailable = true),
        )
    }

    @Test
    fun `the Bottom badge ignores the music panel and its tap is Recents`() {
        val secondary = CompanionOverlayRole.SECONDARY_COMPANION
        assertEquals(
            MButtonReleaseOutcome.TAP_OPENS_RECENTS,
            classifyMButtonRelease(secondary, longPressFired = false, wasDragging = false, musicPanelAvailable = true),
        )
        assertEquals(
            MButtonReleaseOutcome.DRAG_KEEP,
            classifyMButtonRelease(secondary, longPressFired = false, wasDragging = true, musicPanelAvailable = true),
        )
        assertEquals(
            MButtonReleaseOutcome.HOLD_HANDLED,
            classifyMButtonRelease(secondary, longPressFired = true, wasDragging = false, musicPanelAvailable = true),
        )
    }

    // 1000 x 600 display, badge 56 px, clearance 14 px; the card is 320 x 200.
    private fun place(button: OverlayButtonBounds?, cardW: Int = 320, cardH: Int = 200) =
        musicPanelPlacement(button, 1000, 600, cardW, cardH, 14)

    private fun badge(left: Int, top: Int) = overlayButtonBoundsOf(left to top, 56, bounds)

    @Test
    fun `on the left edge the card's outer side meets the badge's and its top meets the badge's top`() {
        assertEquals(MusicPanelPlacement(14, 200), place(badge(14, 200)))
    }

    @Test
    fun `on the right edge the card's outer side meets the badge's and its top meets the badge's top`() {
        // The badge rests at x = 1000 - 56 - 14 = 930, so its outer (right) side is at 986; the card ends there.
        assertEquals(MusicPanelPlacement(986 - 320, 200), place(badge(930, 200)))
    }

    @Test
    fun `on the top edge the card hangs from the badge's top and is centred on it`() {
        // Badge centre x = 500 + 28 = 528.
        assertEquals(MusicPanelPlacement(528 - 160, 14), place(badge(500, 14)))
    }

    @Test
    fun `on the bottom edge the card's bottom meets the badge's bottom and it is centred on the badge`() {
        // Badge at y = 600 - 56 - 14 = 530, bottom 586; the card is 200 tall.
        assertEquals(MusicPanelPlacement(528 - 160, 586 - 200), place(badge(500, 530)))
    }

    @Test
    fun `a tie goes left, right, top, bottom as the snap's does`() {
        // Equal distance to left and top (a corner): the left edge wins, so the card stands on the badge's left and top.
        assertEquals(MusicPanelPlacement(14, 14), place(badge(14, 14)))
        // Equal distance to right and top: right wins over top.
        assertEquals(MusicPanelPlacement(986 - 320, 14), place(badge(930, 14)))
    }

    @Test
    fun `the card stays one clearance inside every edge`() {
        // Left badge low on the screen: the card would run off the bottom, so it is lifted to end 14 px above it.
        assertEquals(MusicPanelPlacement(14, 600 - 14 - 200), place(badge(14, 500)))
        // Top badge near the right: the card is pulled back inside the right edge.
        assertEquals(MusicPanelPlacement(1000 - 14 - 320, 14), place(badge(900, 14)))
        // Top badge near the left: pulled inside the left edge.
        assertEquals(MusicPanelPlacement(14, 14), place(badge(30, 14)))
    }

    @Test
    fun `a card taller than the display pins to the top clearance and scrolls`() {
        assertEquals(MusicPanelPlacement(14, 14), place(badge(14, 300), cardH = 580))
    }

    @Test
    fun `with no badge mounted the card is centred`() {
        assertEquals(MusicPanelPlacement(340, 200), place(null))
    }

    @Test
    fun `the clearance is the badge's own safe inset`() {
        assertEquals(14, MusicPanelEdgeClearanceDp)
    }

    @Test
    fun `the Bottom badge opens Recents on a tap and keeps its free drag`() {
        val secondary = CompanionOverlayRole.SECONDARY_COMPANION
        assertEquals(MButtonReleaseOutcome.HOLD_HANDLED, classifyMButtonRelease(secondary, longPressFired = true, wasDragging = false))
        assertEquals(MButtonReleaseOutcome.DRAG_KEEP, classifyMButtonRelease(secondary, longPressFired = false, wasDragging = true))
        assertEquals(MButtonReleaseOutcome.TAP_OPENS_RECENTS, classifyMButtonRelease(secondary, longPressFired = false, wasDragging = false))
        assertTrue(mButtonTapOpensRecents(secondary))
        assertFalse("the Bottom's tap is Recents, not the menu", mButtonTapOpensMenu(secondary))
    }

    @Test
    fun `the single display badge never opens Recents and every role's tap is classified`() {
        val primary = CompanionOverlayRole.PRIMARY_SINGLE
        assertFalse(mButtonTapOpensRecents(primary))
        assertEquals(MButtonReleaseOutcome.TAP_OPENS_MENU, classifyMButtonRelease(primary, longPressFired = false, wasDragging = false))
        assertEquals(
            MButtonReleaseOutcome.TAP_OPENS_MUSIC,
            classifyMButtonRelease(primary, longPressFired = false, wasDragging = false, musicPanelAvailable = true),
        )
        // The classifier's last branch throws for a role nobody mapped: a new role fails here, not on a device.
        for (role in CompanionOverlayRole.entries) classifyMButtonRelease(role, longPressFired = false, wasDragging = false)
    }

    @Test
    fun `a hold on the Bottom badge opens the menu and never Recents even with music`() {
        val secondary = CompanionOverlayRole.SECONDARY_COMPANION
        for (music in listOf(false, true)) {
            assertEquals(
                MButtonReleaseOutcome.HOLD_HANDLED,
                classifyMButtonRelease(secondary, longPressFired = true, wasDragging = false, musicPanelAvailable = music),
            )
            assertEquals(
                "a hold that then drags is still a hold",
                MButtonReleaseOutcome.HOLD_HANDLED,
                classifyMButtonRelease(secondary, longPressFired = true, wasDragging = true, musicPanelAvailable = music),
            )
        }
    }

    @Test
    fun `a nudge inside the touch slop is still a tap and a move past it is a drag that never opens Recents`() {
        val slop = 24f // 8 dp at 3x
        assertFalse("no movement", mButtonMoveIsDrag(0f, 0f, slop))
        assertFalse("a sub-slop nudge", mButtonMoveIsDrag(10f, 10f, slop))
        assertFalse("exactly on the slop is still a tap", mButtonMoveIsDrag(24f, 0f, slop))
        assertTrue("just past the slop", mButtonMoveIsDrag(24.5f, 0f, slop))
        assertTrue("diagonal past the slop", mButtonMoveIsDrag(20f, 20f, slop))
        assertEquals(8, MButtonDragSlopDp)
        val secondary = CompanionOverlayRole.SECONDARY_COMPANION
        assertEquals(
            "the nudge never became a drag, so the release is a tap",
            MButtonReleaseOutcome.TAP_OPENS_RECENTS,
            classifyMButtonRelease(secondary, longPressFired = false, wasDragging = mButtonMoveIsDrag(10f, 10f, slop)),
        )
        assertEquals(
            "the drag past the slop only repositions",
            MButtonReleaseOutcome.DRAG_KEEP,
            classifyMButtonRelease(secondary, longPressFired = false, wasDragging = mButtonMoveIsDrag(40f, 0f, slop)),
        )
    }
    @Test
    fun `the published bounds are the resting square in display pixels`() {
        val rect = overlayButtonBoundsOf(930 to 14, 56, bounds)
        assertEquals(OverlayButtonBounds(930, 14, 986, 70, 1000, 600), rect)
        assertEquals(56, rect.width)
        assertEquals(56, rect.height)
    }

    @Test
    fun `an overlay without a primary badge reports no bounds`() {
        assertNull(NoOverlayButtonBounds.value)
    }

    // ---- source pins: the controller must keep using the pure functions above ----

    private val controller: String by lazy { readMain("AndroidCompanionOverlayController.kt") }

    private fun readMain(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/$name")
        assertTrue("$name must be readable from the module dir", file.isFile)
        return file.readText()
    }

    private fun functionBody(signature: String): String {
        val start = controller.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val open = controller.indexOf('{', start)
        var depth = 0
        for (index in open until controller.length) {
            when (controller[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return controller.substring(open, index + 1)
            }
        }
        error("unbalanced body for $signature")
    }

    @Test
    fun `the release branch asks the classifier and a tap shows the menu`() {
        val touch = functionBody("private fun handleButtonTouch(")
        assertTrue("the release must be classified", touch.contains("classifyMButtonRelease("))
        assertTrue("a tap must show the menu", touch.contains("MButtonReleaseOutcome.TAP_OPENS_MENU -> showMenu()"))
        assertTrue("a snapping drag must settle", touch.contains("MButtonReleaseOutcome.DRAG_SNAP -> settleButtonAtRest(lp, context)"))
    }

    /**
     * Wiring only: that the release and the press ask the same state function. What the gestures DO (one SYSTEM_TABS per tap, none
     * for a hold, a retap in the hand-over, a drag) is driven through the real controller in `CompanionCoinTapRecentsTest`.
     */
    @Test
    fun `the press and the release both ask the controller's menu state, and the slop is the shared rule`() {
        val touch = functionBody("private fun handleButtonTouch(")
        assertTrue("the press latches the state", touch.contains("recentsBlockedAtDown = !coinTapMayOpenRecents()"))
        assertTrue("the release asks it again", touch.contains("recentsMayOpen = !recentsBlockedAtDown && coinTapMayOpenRecents()"))
        assertTrue("the slop is the shared rule", touch.contains("mButtonMoveIsDrag(dx, dy, dp(context, MButtonDragSlopDp).toFloat())"))
        val stateStart = controller.indexOf("private fun coinTapMayOpenRecents(")
        assertTrue("the state function exists", stateStart >= 0)
        val state = controller.substring(stateStart, controller.indexOf("\n\n", stateStart))
        for (read in listOf("menuOpen = menuOpen", "_quickMenuSwapInFlight.value", "overlayTransitionOutgoing", "secondaryMenuCollapseInFlight", "pendingMenuRequestId != null", "isEnabled")) {
            assertTrue("the state function reads $read", state.contains(read))
        }
    }

    @Test
    fun `a tap on the second display coin opens Recents only when no menu is up and the coin takes touches`() {
        val secondary = CompanionOverlayRole.SECONDARY_COMPANION
        assertTrue(mButtonTapMayOpenRecents(false, false, false, false, false, true))
        // One flag at a time, each is enough to refuse it.
        assertFalse("menu open", mButtonTapMayOpenRecents(true, false, false, false, false, true))
        assertFalse("opening or closing", mButtonTapMayOpenRecents(false, true, false, false, false, true))
        assertFalse("outgoing root still attached", mButtonTapMayOpenRecents(false, false, true, false, false, true))
        assertFalse("collapse in flight", mButtonTapMayOpenRecents(false, false, false, true, false, true))
        assertFalse("a menu request pending", mButtonTapMayOpenRecents(false, false, false, false, true, true))
        assertFalse("the coin's touch is disabled", mButtonTapMayOpenRecents(false, false, false, false, false, false))
        assertEquals(
            MButtonReleaseOutcome.TAP_IGNORED,
            classifyMButtonRelease(secondary, longPressFired = false, wasDragging = false, recentsMayOpen = false),
        )
        // The refusal never changes a hold, a drag, or the single-display badge.
        assertEquals(MButtonReleaseOutcome.HOLD_HANDLED, classifyMButtonRelease(secondary, longPressFired = true, wasDragging = false, recentsMayOpen = false))
        assertEquals(MButtonReleaseOutcome.DRAG_KEEP, classifyMButtonRelease(secondary, longPressFired = false, wasDragging = true, recentsMayOpen = false))
        val primary = CompanionOverlayRole.PRIMARY_SINGLE
        assertEquals(MButtonReleaseOutcome.TAP_OPENS_MENU, classifyMButtonRelease(primary, longPressFired = false, wasDragging = false, recentsMayOpen = false))
        assertEquals(
            MButtonReleaseOutcome.TAP_OPENS_MUSIC,
            classifyMButtonRelease(primary, longPressFired = false, wasDragging = false, musicPanelAvailable = true, recentsMayOpen = false),
        )
    }

    @Test
    fun `the settled edge is what gets saved, announced and glided to`() {
        val settle = functionBody("private fun settleButtonAtRest(")
        val snapped = settle.indexOf("snapMButtonToNearestEdge(")
        val saved = settle.indexOf("savePosition(\"button\", snap.position, bounds)")
        val announced = settle.indexOf("announceButtonPosition(snap.position, bounds)")
        val moved = settle.indexOf("moveButtonTo(lp, snap.position.first, snap.position.second, glide = MButtonRestGlides)")
        val published = settle.indexOf("publishPrimaryButtonBounds()")
        assertTrue("settle must snap", snapped >= 0)
        assertTrue("the edge is saved at the TARGET, never at lp (which still holds the drop point mid-glide)", saved >= 0)
        assertTrue("and announced at the target", announced >= 0)
        assertTrue("the window glides to the target on the one named constant", moved >= 0)
        assertTrue("the bounds are republished", published >= 0)
        assertTrue("the edge is decided before anything is saved, announced or moved", snapped < saved && saved < announced && announced < moved)
        assertFalse("the drop point is never what is saved", settle.contains("saveButtonPosition("))
    }

    @Test
    fun `a touch lands a gliding coin and an unmount cancels it`() {
        val touch = functionBody("private fun handleButtonTouch(")
        val landed = touch.indexOf("finishButtonRestGlide()")
        val read = touch.indexOf("startX = lp?.x")
        assertTrue("a touch lands the glide", landed >= 0)
        assertTrue("before the drag reads the window's position", read < 0 || landed < read)
        assertTrue(functionBody("private fun unmountOverlayWindowsOnMain(").contains("cancelButtonRestGlide()"))
        val move = functionBody("private fun moveButtonTo(")
        assertTrue("the glide runs on the pinned clock", move.contains("animator.duration = MButtonRestGlideMs"))
        assertTrue("on the house easing", move.contains("PathInterpolator(easing[0], easing[1], easing[2], easing[3])"))
        assertTrue("and is skipped when the constant says so", move.contains("if (!glide || "))
    }

    @Test
    fun `a saved position is settled on the nearest edge when it is read, for the single display coin only`() {
        val load = functionBody("private fun loadButtonPosition(")
        assertTrue(load.contains("mButtonEdgeSnapApplies(displayRole)"))
        assertTrue(load.contains("snapMButtonToNearestEdge("))
        assertEquals("ONE remembered position: the one 'button' kind, read once", 1, Regex("""loadPosition\(""").findAll(load).count())
        assertTrue(load.contains("loadPosition(\"button\""))
        assertTrue("the Bottom coin starts just below the header row when nothing is saved", load.contains("defaultBottomMButtonPosition("))
    }

    @Test
    fun `the bounds are published on mount and cleared on unmount`() {
        assertTrue(functionBody("private fun mountOverlayWindowsOnMain(").contains("publishPrimaryButtonBounds()"))
        assertTrue(functionBody("private fun unmountOverlayWindowsOnMain(").contains("_primaryButtonBounds.value = null"))
        val publish = functionBody("private fun publishPrimaryButtonBounds(")
        assertTrue("only the single-display coin publishes bounds: Main's corner reserve reads them", publish.contains("displayRole != CompanionOverlayRole.PRIMARY_SINGLE"))
    }

    @Test
    fun `an idle hide or a view swap lands a gliding coin before it touches the window`() {
        val idle = functionBody("private fun applyPrimaryIdleHidden(")
        assertTrue("the idle hide lands the glide first", idle.indexOf("finishButtonRestGlide()") in 0 until idle.indexOf("if (displayRole"))
        val replace = functionBody("private fun replaceView(")
        assertTrue("a view swap lands it before it replaces anything", replace.indexOf("finishButtonRestGlide()") in 0 until replace.indexOf("val wm = windowManager"))
    }

    @Test
    fun `the glide's clock is the app's overlay clock`() {
        assertEquals("a pinned copy of core-ui's MimirMotion.OverlayMs (the app-side source pin holds them equal)", 180L, MButtonRestGlideMs)
        assertEquals(listOf(0.25f, 0.1f, 0.25f, 1f), MButtonRestGlideEasing.toList())
        assertTrue("a drag that ends on Main's coin glides", MButtonRestGlides)
    }

    @Test
    fun `the contract exposes the bounds with a default`() {
        val contract = readMain("CompanionNativeContracts.kt")
        assertTrue(
            "the property needs a default getter, or every CompanionOverlayController fake stops compiling",
            Regex("""val primaryButtonBounds: StateFlow<OverlayButtonBounds\?>\s+get\(\) = NoOverlayButtonBounds""")
                .containsMatchIn(contract),
        )
        assertFalse("the Bottom coin publishes nothing: no page lays out around it", contract.contains("secondaryButtonBounds"))
    }

    // ---- the Bottom coin goes where it is dropped  ----

    // The Thor's Bottom: 1240 x 1080 px, the coin 56 dp = 129 px at 2.3063 px/dp, the safe inset 14 dp = 32 px, the default seat 72 dp = 166 px down.
    private val bottom = 1240 to 1080
    private val coin = 129
    private val coinInset = 32
    private val seatTop = 166

    @Test
    fun `with nothing saved the Bottom coin starts at the right edge, just below the header row`() {
        assertEquals(
            (1240 - 129 - 32) to 166,
            defaultBottomMButtonPosition(bottom, coin, coin, coinInset, top = seatTop),
        )
    }

    @Test
    fun `the default seat is clamped on a display too short to hold it`() {
        assertEquals((1240 - 129 - 32) to (200 - 129), defaultBottomMButtonPosition(1240 to 200, coin, coin, coinInset, top = seatTop))
    }

    @Test
    fun `the default seat is 72 dp down and the single-display coin's default is unchanged`() {
        assertEquals(72, MButtonBottomDefaultTopDp)
        assertEquals("Main's default stays the top-right corner", 930 to 14, defaultMButtonPosition(bounds, size, size, inset))
    }

    @Test
    fun `a Bottom drag keeps its drop point, whatever the Bottom is showing`() {
        val secondary = CompanionOverlayRole.SECONDARY_COMPANION
        assertFalse("no snap-back", mButtonEdgeSnapApplies(secondary))
        assertEquals(MButtonReleaseOutcome.DRAG_KEEP, classifyMButtonRelease(secondary, longPressFired = false, wasDragging = true))
        val touch = functionBody("private fun handleButtonTouch(")
        assertTrue("a kept drop is saved where it was dropped", touch.contains("MButtonReleaseOutcome.DRAG_KEEP -> saveButtonPosition(lp, context)"))
        assertTrue("the snapping outcome belongs to Main's coin", touch.contains("MButtonReleaseOutcome.DRAG_SNAP -> settleButtonAtRest(lp, context)"))
        val save = functionBody("private fun saveButtonPosition(")
        assertTrue("the drop is persisted as the one 'button' position", save.contains("savePosition(\"button\", lp, bounds)"))
        assertTrue("and announced as dropped", save.contains("announceButtonPosition(lp.x to lp.y, bounds)"))
    }

    @Test
    fun `the glide belongs to Main's coin alone`() {
        assertEquals("moveButtonTo is defined once and called once: from the edge settle", 2, Regex("""moveButtonTo\(""").findAll(controller).count())
        assertTrue(functionBody("private fun settleButtonAtRest(").contains("moveButtonTo("))
    }

    @Test
    fun `the retired slot leaves nothing behind`() {
        val sources = File(mainDir()).walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (retired in listOf(
            "restContextKnown",
            "overForeignApp",
            "reconcileButtonRest",
            "COMPANION_FOREIGN_BUTTON_POSITION_KIND",
            "MButtonForeignAppRestsAtNearestEdge",
            "secondaryButtonBounds",
            "_secondaryButtonBounds",
            "mButtonSlotPosition",
            "defaultForeignAppMButtonPosition",
            "settleMButtonAtRest",
            "MButtonRestPlace",
        )) {
            assertFalse("$retired was retired with the corner slot", sources.contains(retired))
        }
    }

    @Test
    fun `there is one position kind for the coin and the foreign-app one is gone`() {
        assertEquals("button_secondary_companion_x", companionOverlayPositionKey("button", CompanionOverlayRole.SECONDARY_COMPANION, "x"))
        val rejected = runCatching { companionOverlayPositionKey("button_foreign", CompanionOverlayRole.SECONDARY_COMPANION, "x") }.exceptionOrNull()
        assertTrue("an unknown kind is refused: $rejected", rejected is IllegalArgumentException)
    }

    private fun mainDir(): String =
        listOf("src/main/kotlin", "core-companion-native/src/main/kotlin").first { File(it).isDirectory }
}
