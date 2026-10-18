package com.gruning.mimir.rewrite.core.companionnative

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** OR-11: the key map, the grid rule, the focus reducer, the HAT edge detector, the ring colours, and the wiring pins. */
class QuickMenuFocusTest {
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    private fun move(count: Int, from: Int, key: QuickMenuNavKey) = quickMenuFocusNeighbour(from, count, key)

    // ---- keys ----

    @Test
    fun `the four directions and the confirm group map and everything else does not`() {
        assertEquals(QuickMenuNavKey.UP, quickMenuNavKeyFor(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(QuickMenuNavKey.DOWN, quickMenuNavKeyFor(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(QuickMenuNavKey.LEFT, quickMenuNavKeyFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(QuickMenuNavKey.RIGHT, quickMenuNavKeyFor(KeyEvent.KEYCODE_DPAD_RIGHT))
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
        ).forEach { assertEquals(QuickMenuNavKey.CONFIRM, quickMenuNavKeyFor(it)) }
        listOf(
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_POWER,
        ).forEach { assertNull("keycode $it must not be a focus key", quickMenuNavKeyFor(it)) }
    }

    @Test
    fun `back and B keep the dismiss route and are never focus keys`() {
        listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK).forEach { keyCode ->
            assertNull(quickMenuNavKeyFor(keyCode))
            assertEquals(
                PrimaryQuickMenuKeyResult.DISMISS_AFTER_RELEASE,
                primaryQuickMenuKeyResult(keyCode, KeyEvent.ACTION_UP),
            )
        }
    }

    // ---- the grid rule ----

    @Test
    fun `four coins are a two by two grid and every direction moves inside it`() {
        // 0 1
        // 2 3
        assertEquals(1, move(4, 0, QuickMenuNavKey.RIGHT))
        assertEquals(2, move(4, 0, QuickMenuNavKey.DOWN))
        assertEquals(0, move(4, 1, QuickMenuNavKey.LEFT))
        assertEquals(3, move(4, 1, QuickMenuNavKey.DOWN))
        assertEquals(0, move(4, 2, QuickMenuNavKey.UP))
        assertEquals(3, move(4, 2, QuickMenuNavKey.RIGHT))
        assertEquals(1, move(4, 3, QuickMenuNavKey.UP))
        assertEquals(2, move(4, 3, QuickMenuNavKey.LEFT))
    }

    @Test
    fun `the ends clamp and do not wrap`() {
        assertEquals(0, move(4, 0, QuickMenuNavKey.LEFT))
        assertEquals(0, move(4, 0, QuickMenuNavKey.UP))
        assertEquals(1, move(4, 1, QuickMenuNavKey.RIGHT))
        assertEquals(3, move(4, 3, QuickMenuNavKey.RIGHT))
        assertEquals(3, move(4, 3, QuickMenuNavKey.DOWN))
        // Right at the end of the first line does not spill onto the second.
        assertEquals(1, move(4, 1, QuickMenuNavKey.RIGHT))
    }

    @Test
    fun `five coins lay out three over two and the short line is centred`() {
        // 0 1 2
        //  3 4      (3 sits between 0 and 1, 4 between 1 and 2)
        assertEquals(3, move(5, 0, QuickMenuNavKey.DOWN))
        assertEquals(3, move(5, 1, QuickMenuNavKey.DOWN))
        assertEquals(4, move(5, 2, QuickMenuNavKey.DOWN))
        assertEquals(0, move(5, 3, QuickMenuNavKey.UP))
        assertEquals(1, move(5, 4, QuickMenuNavKey.UP))
        assertEquals(4, move(5, 3, QuickMenuNavKey.RIGHT))
        assertEquals(3, move(5, 4, QuickMenuNavKey.LEFT))
        assertEquals(3, move(5, 3, QuickMenuNavKey.LEFT))
        assertEquals(4, move(5, 4, QuickMenuNavKey.RIGHT))
        assertEquals(2, move(5, 2, QuickMenuNavKey.RIGHT))
    }

    @Test
    fun `a single line and a lone coin have nowhere to go vertically`() {
        assertEquals(1, move(3, 1, QuickMenuNavKey.DOWN))
        assertEquals(1, move(3, 1, QuickMenuNavKey.UP))
        assertEquals(2, move(3, 1, QuickMenuNavKey.RIGHT))
        assertEquals(0, move(1, 0, QuickMenuNavKey.DOWN))
        assertEquals(0, move(1, 0, QuickMenuNavKey.RIGHT))
    }

    @Test
    fun `a stale or empty index is returned unchanged`() {
        assertEquals(7, move(4, 7, QuickMenuNavKey.DOWN))
        assertEquals(-1, move(4, -1, QuickMenuNavKey.RIGHT))
        assertEquals(0, move(0, 0, QuickMenuNavKey.DOWN))
    }

    @Test
    fun `the grid rule follows the menu's own shape for every count the menus can have`() {
        (1..9).forEach { count ->
            val columns = companionOverlayMenuGridColumns(count)
            for (index in 0 until count) {
                QuickMenuNavKey.entries.forEach { key ->
                    val target = move(count, index, key)
                    assertTrue("$count coins: $key from $index went to $target", target in 0 until count)
                }
                // Sideways never leaves the line, so it can never wrap onto the next one.
                val right = move(count, index, QuickMenuNavKey.RIGHT)
                assertTrue(right / columns == index / columns)
            }
        }
    }

    // ---- the reducer ----

    private fun press(state: QuickMenuFocus, key: QuickMenuNavKey, repeat: Int = 0) =
        reduceQuickMenuFocus(state, key, down, repeat)

    private fun release(state: QuickMenuFocus, key: QuickMenuNavKey) = reduceQuickMenuFocus(state, key, up)

    @Test
    fun `the ring is lit on the first coin when the menu opens`() {
        val opened = QuickMenuFocus.opened(4)
        assertEquals(0, opened.litIndex)
        assertNull(opened.confirmDownOn)
    }

    @Test
    fun `a direction moves the ring and reports nothing to activate`() {
        val step = press(QuickMenuFocus.opened(4), QuickMenuNavKey.DOWN)
        assertEquals(2, step.state.litIndex)
        assertNull(step.activate)
    }

    @Test
    fun `confirm arms on press and activates the lit coin on release`() {
        val armed = press(QuickMenuFocus.opened(4), QuickMenuNavKey.CONFIRM)
        assertNull("nothing runs while the key is down", armed.activate)
        assertEquals(0, armed.state.confirmDownOn)
        val released = release(armed.state, QuickMenuNavKey.CONFIRM)
        assertEquals(0, released.activate)
        assertNull(released.state.confirmDownOn)
    }

    @Test
    fun `confirm activates the coin it went down on after the ring moved onto it`() {
        val moved = press(QuickMenuFocus.opened(4), QuickMenuNavKey.RIGHT).state
        val armed = press(moved, QuickMenuNavKey.CONFIRM).state
        assertEquals(1, release(armed, QuickMenuNavKey.CONFIRM).activate)
    }

    @Test
    fun `a release whose press never reached the menu activates nothing`() {
        assertNull(release(QuickMenuFocus.opened(4), QuickMenuNavKey.CONFIRM).activate)
    }

    @Test
    fun `a move between the press and the release cancels the confirm`() {
        val armed = press(QuickMenuFocus.opened(4), QuickMenuNavKey.CONFIRM).state
        val moved = press(armed, QuickMenuNavKey.DOWN).state
        assertNull(moved.confirmDownOn)
        assertNull(release(moved, QuickMenuNavKey.CONFIRM).activate)
    }

    @Test
    fun `a key repeat does not re-arm or move a held confirm`() {
        val armed = press(QuickMenuFocus.opened(4), QuickMenuNavKey.CONFIRM).state
        val repeated = press(armed, QuickMenuNavKey.CONFIRM, repeat = 3)
        assertEquals(armed, repeated.state)
        assertNull(repeated.activate)
    }

    @Test
    fun `releasing a direction changes nothing`() {
        val state = QuickMenuFocus.opened(4)
        val step = release(state, QuickMenuNavKey.DOWN)
        assertEquals(state, step.state)
        assertNull(step.activate)
    }

    @Test
    fun `an action that is neither press nor release is ignored`() {
        val state = QuickMenuFocus.opened(4)
        assertEquals(state, reduceQuickMenuFocus(state, QuickMenuNavKey.DOWN, KeyEvent.ACTION_MULTIPLE).state)
    }

    @Test
    fun `a menu with no coins ignores every key`() {
        val empty = QuickMenuFocus.opened(0)
        QuickMenuNavKey.entries.forEach { key ->
            assertEquals(empty, press(empty, key).state)
            assertNull(release(empty, key).activate)
        }
    }

    @Test
    fun `with the ring dark the first key lights the first coin and does nothing else`() {
        val dark = QuickMenuFocus.opened(4, litOnOpen = false)
        assertNull(dark.litIndex)
        val lit = press(dark, QuickMenuNavKey.DOWN)
        assertEquals(0, lit.state.litIndex)
        assertNull(lit.activate)
        // A confirm on a dark ring lights it and does not arm, so its release activates nothing.
        val confirmed = press(dark, QuickMenuNavKey.CONFIRM)
        assertEquals(0, confirmed.state.litIndex)
        assertNull(confirmed.state.confirmDownOn)
        assertNull(release(confirmed.state, QuickMenuNavKey.CONFIRM).activate)
    }

    @Test
    fun `the shipped default lights the ring on open`() {
        assertTrue(QuickMenuRingLitOnOpen)
    }

    // ---- HAT and stick ----

    @Test
    fun `an axis reads as a direction only past half travel and the hat wins`() {
        assertEquals(0, quickMenuAxisDirection(0f, 0f))
        assertEquals(0, quickMenuAxisDirection(0.49f, -0.49f))
        assertEquals(1, quickMenuAxisDirection(1f, 0f))
        assertEquals(-1, quickMenuAxisDirection(-1f, 0f))
        assertEquals(1, quickMenuAxisDirection(0f, 0.5f))
        assertEquals(-1, quickMenuAxisDirection(0f, -0.9f))
        assertEquals("the hat outranks a stick pushed the other way", 1, quickMenuAxisDirection(1f, -1f))
    }

    @Test
    fun `a held direction reports once and again only after it was released`() {
        val edges = QuickMenuAxisEdges()
        assertEquals(listOf(QuickMenuNavKey.RIGHT), edges.step(1, 0))
        assertEquals(emptyList<QuickMenuNavKey>(), edges.step(1, 0))
        assertEquals(emptyList<QuickMenuNavKey>(), edges.step(0, 0))
        assertEquals(listOf(QuickMenuNavKey.RIGHT), edges.step(1, 0))
    }

    @Test
    fun `reversing a held direction reports the new one`() {
        val edges = QuickMenuAxisEdges()
        edges.step(0, 1)
        assertEquals(listOf(QuickMenuNavKey.UP), edges.step(0, -1))
    }

    @Test
    fun `a diagonal is two presses with X first`() {
        val edges = QuickMenuAxisEdges()
        assertEquals(listOf(QuickMenuNavKey.LEFT, QuickMenuNavKey.DOWN), edges.step(-1, 1))
    }

    // ---- the ring ----

    @Test
    fun `the ring is lit from the top and shaded from the bottom and stays opaque`() {
        val stops = quickMenuRingStops(0xffc4af82.toInt())
        listOf(stops.top, stops.base, stops.bottom).forEach { assertEquals(0xff, it ushr 24) }
        assertTrue("top must be lighter than base", luma(stops.top) > luma(stops.base))
        assertTrue("bottom must be darker than base", luma(stops.bottom) < luma(stops.base))
    }

    @Test
    fun `a translucent ring colour is treated as opaque`() {
        assertEquals(0xff112233.toInt(), quickMenuRingStops(0x40112233).base)
    }

    @Test
    fun `mixing at the ends returns the ends`() {
        assertEquals(0xff102030.toInt(), mixArgb(0xff102030.toInt(), 0xffffffff.toInt(), 0f))
        assertEquals(0xffffffff.toInt(), mixArgb(0xff102030.toInt(), 0xffffffff.toInt(), 1f))
    }

    @Test
    fun `the ring reaches no farther than the gap to the label under the coin`() {
        val reach = QuickMenuRingOutsetDp + QuickMenuRingStrokeDp / 2f
        assertTrue("the ring reaches $reach dp", reach <= CompanionMenuCoinLabelGapDp)
        assertTrue(reach <= CompanionMenuCoinRowGapDp)
        // Wide enough for a coin in the narrowest cell the menus draw (three across a 244 dp card).
        val cell = (CompanionMenuWidthDp - 2 * CompanionMenuContentStartDp) / 3f
        assertTrue(CompanionMenuCoinSizeDp + 2 * reach <= cell)
    }

    @Test
    fun `the ring colour defaults to the card rim and carries an explicit value`() {
        val base = CompanionOverlayTheme.Default
        assertEquals(base.pageEdgeArgb, base.focusRingArgb)
        assertEquals(0xff112233.toInt(), base.copy(focusRingArgb = 0xff112233.toInt()).focusRingArgb)
    }

    private fun luma(argb: Int): Int = ((argb ushr 16) and 0xff) + ((argb ushr 8) and 0xff) + (argb and 0xff)

    // ---- the press-order buffer: RIGHT then A must act on the NEW coin even when A arrives first ----

    /** A reducer driven only through the buffer, recording every activation in the order it ran. */
    private class PadWalk {
        val order = QuickMenuPadOrder()
        var focus = QuickMenuFocus.opened(4)
        val activated = ArrayList<Int>()

        fun apply(ops: List<QuickMenuPadKey>) {
            ops.forEach { op ->
                val step = reduceQuickMenuFocus(focus, op.key, op.action, op.repeatCount)
                focus = step.state
                step.activate?.let { activated += it }
            }
        }

        fun confirmDown(t: Long, canHold: Boolean = true) = apply(order.confirm(KeyEvent.ACTION_DOWN, 0, t, canHold))
        fun confirmUp(t: Long) = apply(order.confirm(KeyEvent.ACTION_UP, 0, t, true))
        fun right(t: Long) = apply(order.move(QuickMenuNavKey.RIGHT, 0, t))
        fun frame() = apply(order.frame())
    }

    @Test
    fun `without the buffer a hat press that really came first loses to the A that arrived first`() {
        // The bite, in arrival order: A-down, A-up, then the batched RIGHT. The old coin runs, then the ring moves.
        var focus = QuickMenuFocus.opened(4)
        val activated = ArrayList<Int>()
        listOf(
            QuickMenuPadKey(QuickMenuNavKey.CONFIRM, down),
            QuickMenuPadKey(QuickMenuNavKey.CONFIRM, up),
            QuickMenuPadKey(QuickMenuNavKey.RIGHT, down),
        ).forEach { op ->
            val step = reduceQuickMenuFocus(focus, op.key, op.action)
            focus = step.state
            step.activate?.let { activated += it }
        }
        assertEquals("fixture: arrival order activates the coin that was lit BEFORE the move", listOf(0), activated)
    }

    @Test
    fun `RIGHT stamped before the A moves the ring first and the A acts on the new coin`() {
        val walk = PadWalk()
        // Pressed RIGHT at 5, then A at 10..20. Delivered: A-down, A-up (keys are immediate), then the batched hat.
        walk.confirmDown(10)
        walk.confirmUp(20)
        assertTrue("both key edges are held for the frame", walk.order.holding)
        assertTrue("the old coin has NOT run ahead of the move", walk.activated.isEmpty())
        walk.right(5)
        assertEquals("the ring moved while the A was still held", 1, walk.focus.litIndex)
        assertTrue("and still nothing has run", walk.activated.isEmpty())
        walk.frame()
        assertEquals("the A acted on the coin the RIGHT lit, once", listOf(1), walk.activated)
        assertFalse(walk.order.holding)
    }

    @Test
    fun `RIGHT stamped after the A releases leaves the A on the old coin and then moves`() {
        val walk = PadWalk()
        walk.confirmDown(10)
        walk.confirmUp(20)
        walk.right(30)
        assertEquals("the A went down first, so it acts on the coin it went down on", listOf(0), walk.activated)
        assertEquals("and the ring moves after it", 1, walk.focus.litIndex)
        walk.frame()
        assertEquals("the frame has nothing left to run", listOf(0), walk.activated)
    }

    @Test
    fun `A-down then RIGHT then A-up fails safe and activates nothing`() {
        val walk = PadWalk()
        // True order A-down 10, RIGHT 15, A-up 20; delivered A-down, A-up, then the batched RIGHT.
        walk.confirmDown(10)
        walk.confirmUp(20)
        walk.right(15)
        assertEquals("the ring moved", 1, walk.focus.litIndex)
        walk.frame()
        assertTrue("the move interrupted the press, so its release runs nothing", walk.activated.isEmpty())
        assertNull(walk.focus.confirmDownOn)
    }

    @Test
    fun `a press stamped at the same instant as the A goes after it`() {
        val walk = PadWalk()
        walk.confirmDown(10)
        walk.confirmUp(12)
        walk.right(10)
        walk.frame()
        // The A went down (arming coin 0), the RIGHT then interrupted it: nothing activates, as in the router.
        assertTrue(walk.activated.isEmpty())
        assertEquals(1, walk.focus.litIndex)
    }

    @Test
    fun `an A with no frame to wait for, or no stamp, is never held`() {
        val noFrame = PadWalk()
        noFrame.confirmDown(10, canHold = false)
        assertFalse(noFrame.order.holding)
        assertEquals(0, noFrame.focus.confirmDownOn)
        val unstamped = PadWalk()
        unstamped.confirmDown(0)
        assertFalse(unstamped.order.holding)
        unstamped.confirmUp(0)
        assertEquals(listOf(0), unstamped.activated)
    }

    @Test
    fun `a long press releases its DOWN at the frame and the later UP activates straight away`() {
        val walk = PadWalk()
        walk.confirmDown(10)
        walk.frame()
        assertEquals("armed, not yet run", 0, walk.focus.confirmDownOn)
        assertTrue(walk.activated.isEmpty())
        walk.confirmUp(900)
        assertEquals(listOf(0), walk.activated)
    }

    @Test
    fun `an auto repeat of a held A is dropped and a second A queues behind the first`() {
        val walk = PadWalk()
        walk.confirmDown(10)
        walk.apply(walk.order.confirm(KeyEvent.ACTION_DOWN, 2, 400, true))
        assertTrue(walk.activated.isEmpty())
        walk.confirmUp(40)
        walk.confirmDown(50)
        assertTrue("neither has run: both wait for the frame", walk.activated.isEmpty())
        assertTrue(walk.order.holding)
        walk.frame()
        assertEquals("the first press ran once, in order; the second is armed, not run", listOf(0), walk.activated)
        assertEquals(0, walk.focus.confirmDownOn)
        walk.confirmUp(60)
        assertEquals("the second press follows the ordinary release rule", listOf(0, 0), walk.activated)
    }

    @Test
    fun `a batched hat then two A presses before the frame never run the stale coin`() {
        val walk = PadWalk()
        // True order: RIGHT at 5, A 10..20, A 30..40. Delivered: both A presses (keys are immediate), then the batched hat.
        walk.confirmDown(10)
        walk.confirmUp(20)
        walk.confirmDown(30)
        walk.confirmUp(40)
        assertTrue("the second A DOWN did not drain the first", walk.activated.isEmpty())
        walk.right(5)
        assertEquals("the ring moved before any A ran", 1, walk.focus.litIndex)
        assertTrue(walk.activated.isEmpty())
        walk.frame()
        assertEquals("both A presses acted on the coin the RIGHT lit, once each, in order", listOf(1, 1), walk.activated)
        assertFalse(walk.order.holding)
    }

    @Test
    fun `a reset drops a held A without running it`() {
        val walk = PadWalk()
        walk.confirmDown(10)
        walk.confirmUp(20)
        walk.order.reset()
        assertFalse(walk.order.holding)
        walk.frame()
        assertTrue(walk.activated.isEmpty())
    }

    @Test
    fun `with nothing held a direction goes straight through`() {
        val walk = PadWalk()
        walk.right(5)
        assertEquals(1, walk.focus.litIndex)
    }

    // ---- wiring pins: the controller must keep using the pure rules above ----

    private fun read(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/$name")
        assertTrue("$name must be readable from the module dir", file.isFile)
        return file.readText()
    }

    private val controller: String by lazy { read("AndroidCompanionOverlayController.kt") }

    @Test
    fun `the primary menu feeds its keys to the reducer and no longer swallows joystick motion`() {
        assertTrue(controller.contains("quickMenuNavKeyFor(event.keyCode)"))
        assertTrue("keys go through the press-order buffer", controller.contains("quickMenuNavKeyFor(event.keyCode)?.let { key -> applyPadNavKey(key, event) }"))
        assertTrue(
            "hat samples go through the same buffer: on the Bottom only the samples the idle gate permits, on the primary menu all of them",
            controller.contains("applyPadMotion(event, permitted)") && controller.contains("applyPadMotion(event, null)"),
        )
        assertTrue("and its output reaches the reducer", controller.contains("ops.forEach { applyQuickMenuFocusStep(it.key, it.action, it.repeatCount) }"))
        assertTrue(controller.contains("reduceQuickMenuFocus(menuFocus, key, action, repeatCount)"))
        assertTrue(controller.contains("quickMenuAxisDirection("))
        assertFalse(
            "the one-line swallow of every generic motion event must be gone",
            controller.contains("if (role == CompanionOverlayRole.PRIMARY_SINGLE) true else super.dispatchGenericMotionEvent(event)"),
        )
    }

    @Test
    fun `the ring is built for both menus and the coins stay non focusable`() {
        assertTrue(controller.contains("val focusRing: CompanionMenuFocusRingView = CompanionMenuFocusRingView(context, theme.focusRingArgb, coinMarks, book)"))
        assertFalse(
            "R9a lifted the role gate: the ring is no longer built for PRIMARY only",
            Regex("""if \(role == CompanionOverlayRole\.PRIMARY_SINGLE\) \{\s+CompanionMenuFocusRingView\(""")
                .containsMatchIn(controller),
        )
        val ring = read("CompanionMenuFocusRingView.kt")
        assertTrue(ring.contains("isFocusable = false"))
        assertTrue(ring.contains("isClickable = false"))
        val coin = controller.substringAfter("private fun menuCoin(", "").substringBefore("private fun handleButtonTouch(", "")
        assertTrue(coin.contains("isFocusable = false"))
        assertFalse(coin.contains("isFocusable = true"))
    }

    @Test
    fun `an activation runs the same emitter a tap on the coin runs`() {
        val apply = controller.substringAfter("private fun applyQuickMenuFocusStep(", "").substringBefore("private fun ", "")
        assertTrue("the role is the menu's own, so a Bottom pick is a Bottom command", apply.contains("emitMenuAction(row, displayRole)"))
        assertTrue(
            "focus keys act only while the menu is open and its role owns keys (PRIMARY always, SECONDARY while the pad owner is set)",
            apply.contains("if (!menuOpen || !quickMenuRoleOwnsKeys(displayRole)) return"),
        )
    }

    @Test
    fun `the Bottom pad owner is cleared at every exit and each clear drops a held confirm`() {
        val collapse = controller.substringAfter("private fun collapseMenu(animate: Boolean) {", "").substringBefore("replaceView(", "")
        assertTrue("collapse (B, tap-outside, a command, an accepted pick) clears before the swap", collapse.contains("clearSecondaryPadOwner(\"collapse\")"))
        val unmount = controller.substringAfter("private fun unmountOverlayWindowsOnMain(", "").substringBefore("panelOwner.revokeCurrent(reason)", "")
        assertTrue("display removed / permission lost / target cleared", unmount.contains("clearSecondaryPadOwner(\"unmount_"))
        val mount = controller.substringAfter("private fun mountOverlayWindowsOnMain(", "").substringBefore("val candidateView = buildButton(", "")
        assertTrue(mount.contains("clearSecondaryPadOwner(\"mount\""))
        val dimmed = controller.substringAfter("override fun setDimmed(", "").substringBefore("updateEdgeCatcherTouchability()", "")
        assertTrue("the Presentation's mirror leaving is an edge, not a level", dimmed.contains("val mirrorLeft = this.dimmed &&"))
        assertTrue(dimmed.contains("if (mirrorLeft) clearSecondaryPadOwner(\"mirror_left\")"))
        assertTrue(controller.contains("clearSecondaryPadOwner(\"hidden\")"))
        assertEquals("both swap landings that fail to add a window clear it", 2, Regex("clearSecondaryPadOwner\\(\"add_failed\"").findAll(controller).count())
        val clear = controller.substringAfter("private fun clearSecondaryPadOwner(", "").substringBefore("\n    }\n", "")
        assertTrue("every clear drops a held confirm, the frame callback and the hat state", clear.startsWith("reason: String, updateWindow: Boolean = true) {\n        resetPadInput()"))
        assertTrue("and hands the floor's route back", clear.contains("BottomPadInput.release(floorPadKeys, floorPadMotion)"))
        val reset = controller.substringAfter("private fun resetPadInput() {", "").substringBefore("\n    }\n", "")
        assertTrue(reset.contains("padOrder.reset()") && reset.contains("padFrameCancel?.invoke()") && reset.contains("padAxisEdges = QuickMenuAxisEdges()"))
    }

    @Test
    fun `the window, the root and the key route all follow the one owner rule`() {
        assertTrue(controller.contains("val flags = (if (primaryQuickMenuOwnsWindowFocus(displayRole, menuOpen, secondaryPadOwner)) {"))
        val add = controller.substringAfter("private fun addCurrentView(", "").substringBefore("private fun animateOverlayReplacement(", "")
        assertTrue(add.contains("val ownsFocus = primaryQuickMenuOwnsWindowFocus(displayRole, menuOpen, secondaryPadOwner)"))
        assertTrue(add.contains("view.isFocusable = ownsFocus"))
        assertTrue("requestFocus is inside this one window", add.contains("if (ownsFocus) {\n                view.requestFocus()"))
        assertTrue("the owner is set before the incoming window is added", Regex("syncSecondaryPadOwnerToMenu\\(targetMenuOpen, wasMenuOpen\\)\\s+overlayView =").findAll(controller).count() == 2)
        assertTrue("B and Back dismiss after release on either role", controller.contains("if (menuOpen && displayRole == role) collapseMenu()"))
        assertTrue("Back is never the only close: B is a dismiss key too", primaryQuickMenuKeyResult(KeyEvent.KEYCODE_BUTTON_B, up) == PrimaryQuickMenuKeyResult.DISMISS_AFTER_RELEASE)
        assertFalse("nothing asks for focus across displays", controller.contains("requestFocusAcross") || controller.contains("moveDisplayToTop"))
    }

    @Test
    fun `a Bottom pad event resets the hide timer, and the screen's idle rule is the one PadIdleGate Main's lane runs`() {
        val keyPath = controller.substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean {", "").substringBefore("override fun dispatchGenericMotionEvent", "")
        assertTrue(keyPath.contains("notePadActivity(event.eventTime, throttled = false)"))
        assertTrue(controller.contains("notePadActivity(event.eventTime, throttled = true)"))
        val note = controller.substringAfter("private fun notePadActivity(", "").substringBefore("\n    }\n", "")
        assertTrue("the ᛗ's own hide timer", note.contains("notifyPrimaryIdleInteraction()"))
        assertFalse("the screen's idle deadline is the gate's, not a second note here", note.contains("emitScreenActivity"))
        // The menu's pad runs through the one gate on the Bottom, volume and power staying the system's: keys the menu takes, and every joystick sample.
        assertTrue(keyPath.contains("val menuPad = result == PrimaryQuickMenuKeyResult.CONSUME || result == PrimaryQuickMenuKeyResult.DISMISS_AFTER_RELEASE"))
        assertTrue(keyPath.contains("padIdleGate.key("))
        assertTrue(controller.contains("padIdleGate.motion(event.idleWakeSamples()) { permitted ->"))
        assertTrue("a sample the gate ate is never replayed to the ring", controller.contains("applyPadMotion(event, permitted)"))
        // One gate for the controller's life, under the pad's ownership, reset through the menu's own pad reset, and handed Main's idle answer by the app.
        assertTrue(controller.contains("private val padIdleGate = PadIdleGate("))
        assertTrue(controller.contains("ownership = { BottomPadInput.ownerEpoch }"))
        assertTrue(controller.contains("interaction = { padIdleInteraction?.invoke() ?: false }"))
        assertTrue(controller.contains("override fun setPadIdleInteraction(interaction: (() -> Boolean)?) {"))
        val wake = controller.substringAfter("private val padIdleGate = PadIdleGate(", "").substringBefore("\n    )\n", "")
        assertTrue(wake.contains("resetPadInput()"))
        listOf("brightness", "Brightness", "applyDimmed").forEach { assertFalse("the controller never dims or wakes anything itself ($it)", wake.contains(it)) }
    }

    @Test
    fun `an open menu keeps its lit coin across a rebuild and a fresh menu starts from the default`() {
        assertTrue(controller.contains("if (menuOpen && menuFocus.itemCount == rows.size) menuFocus else QuickMenuFocus.opened(rows.size)"))
    }
}
