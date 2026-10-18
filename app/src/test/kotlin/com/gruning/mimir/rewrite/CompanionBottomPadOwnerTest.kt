package com.gruning.mimir.rewrite

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.BottomPadInput
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.companionnative.PadIdleGate
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

/**
 * R9a , slice 1: the Bottom's ᛗ menu is walked by the pad, and only while it is open.
 *
 * Each test runs the real controller against a second display, as `CompanionOverlayProcessThemeTest` does, and reads the
 * mounted window's `LayoutParams` and the controller's one `secondaryPadOwner` field. What a host test CAN say: the window is
 * focusable for exactly the span the owner is set; the owner clears on B after its release, tap-outside, an accepted pick,
 * the display going away, the overlay hidden and the mirror leaving; and after every clear no overlay window of Mimir's can
 * hold key focus. What it CANNOT say: that a focusable window REMAINS on display 4 afterwards. That is the floor's
 * (`CompanionSecondaryHomeActivity`), whose not-NOT_FOCUSABLE flag `CompanionSecondaryHomeFocusTest` pins as source, and
 * whose real focus hand-off, and a foreign app closing under the open menu, are the emulator cycle's and the Thor's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionBottomPadOwnerTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private lateinit var overlay: AndroidCompanionOverlayController
    private val actions = ArrayList<CompanionOverlayAction>()
    private var bottomDisplayId = -1

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetOverlaySingleton()
        ShadowSettings.setCanDrawOverlays(true)
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        overlay = AndroidCompanionOverlayController.instance(app)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { overlay.actions.collect { actions += it } }
        idleMain()
        mountOnBottom()
    }

    @After
    fun tearDown() {
        scope.cancel()
        resetOverlaySingleton()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `the window is focusable exactly while the owner is set`() {
        val button = overlay.root()
        assertTrue("the button window is not focusable", button.flags() and NOT_FOCUSABLE != 0)
        assertNull("nobody owns the Bottom's pad before the menu opens", overlay.padOwner())

        openMenu()
        val menu = overlay.root()
        assertEquals("the owner is the menu", "MENU", overlay.padOwner())
        assertEquals("the menu window takes key focus", 0, menu.flags() and NOT_FOCUSABLE)
        assertTrue("and its root's focusability follows", menu.isFocusable && menu.isFocusableInTouchMode)
        assertTrue("it still watches outside touches and is not touch-modal",
            menu.flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0 &&
                menu.flags() and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0)
    }

    @Test
    fun `B closes the menu after its release and the release is consumed by the menu`() {
        openMenu()
        val menu = overlay.root()
        assertTrue("B's press is consumed", menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)))
        idleMain()
        assertTrue("a press alone closes nothing", overlay.quickMenuOpen.value)
        assertEquals("MENU", overlay.padOwner())

        assertTrue("B's release is consumed by the menu, so it cannot leak to the next window",
            menu.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)))
        idleMain()
        assertNull("the owner cleared the moment the collapse began", overlay.padOwner())
        assertNoFocusableOverlay("the outgoing menu left key focus at once")
        assertFalse("and the floor route is handed back", BottomPadInput.routeKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))

        settle()
        assertFalse(overlay.quickMenuOpen.value)
        assertTrue("the button that returns is not focusable", overlay.root().flags() and NOT_FOCUSABLE != 0)
    }

    @Test
    fun `Back dismisses too and is never the only path`() {
        openMenu()
        val menu = overlay.root()
        menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        menu.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        idleMain()
        assertNull(overlay.padOwner())
    }

    @Test
    fun `tap outside the card closes the menu and clears the owner`() {
        openMenu()
        val menu = overlay.root()
        val (x, y) = outsideTheCard(menu)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
        try {
            menu.dispatchTouchEvent(down)
            menu.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        idleMain()
        assertNull(overlay.padOwner())
        assertNoFocusableOverlay("tap-outside took the menu out of key focus")
    }

    @Test
    fun `an accepted pick clears the owner and the pad lands where it did before R9a`() {
        openMenu()
        val menu = overlay.root()
        // A is held for its frame, then runs the lit coin exactly as a tap on it would.
        assertTrue(menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
        assertTrue(menu.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)))
        assertTrue("nothing ran inside the dispatch: the A waits for the frame", actions.none { it is CompanionOverlayAction.QuickMenu })
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        val pick = actions.filterIsInstance<CompanionOverlayAction.QuickMenu>().single()
        assertEquals(CompanionOverlayRole.SECONDARY_COMPANION, pick.role)
        assertEquals("the first coin is the Bottom's Tabs", QuickMenuItem.SECONDARY_TABS, pick.item)
        assertEquals("the menu waits for the app's answer and still owns the pad", "MENU", overlay.padOwner())

        overlay.completeQuickMenu(pick.requestId, accepted = true)
        idleMain()
        assertNull("accepted: the menu collapses and the owner clears (slice 2 adds the panel owners)", overlay.padOwner())
        assertNoFocusableOverlay("the pad is no longer the menu's")
    }

    @Test
    fun `a refused pick leaves the menu and its owner in place`() {
        openMenu()
        val menu = overlay.root()
        menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        menu.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        val pick = actions.filterIsInstance<CompanionOverlayAction.QuickMenu>().single()
        overlay.completeQuickMenu(pick.requestId, accepted = false)
        idleMain()
        assertEquals("MENU", overlay.padOwner())
        assertEquals(0, overlay.root().flags() and NOT_FOCUSABLE)
    }

    @Test
    fun `a pad event that falls to the floor still reaches the menu`() {
        openMenu()
        assertEquals(0, overlay.litIndex())
        assertTrue("the floor's route consumed it", BottomPadInput.routeKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals("and the ring moved", 1, overlay.litIndex())
    }

    @Test
    fun `a rebuild of the open menu keeps the owner and a focusable window`() {
        openMenu()
        val before = overlay.root()
        // replaceViewImmediately: the theme change swaps the open menu's window without a fade.
        overlay.setOverlayTheme(CompanionOverlayTheme.Default.copy(inkArgb = 0xff112233.toInt()))
        idleMain()
        val after = overlay.root()
        assertTrue("fixture: a new window replaced the old one", before !== after)
        assertEquals("the owner survived the rebuild", "MENU", overlay.padOwner())
        assertEquals("and the new window takes key focus", 0, after.flags() and NOT_FOCUSABLE)
        assertTrue(after.isFocusable)
    }

    @Test
    fun `the display going away clears the owner and the floor route`() {
        openMenu()
        assertEquals("MENU", overlay.padOwner())
        ShadowDisplayManager.removeDisplay(bottomDisplayId)
        idleMain()
        assertNull("the owner cleared with the window", overlay.padOwner())
        assertNull("nothing is mounted any more", overlay.activeTarget.value)
        assertFalse(BottomPadInput.routeKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertFalse(BottomPadInput.routeMotion(joystick(hatX = 0f, timeMs = SystemClock.uptimeMillis())))
    }

    @Test
    fun `hiding the overlay clears the owner`() {
        openMenu()
        overlay.setVisible(false)
        idleMain()
        assertNull(overlay.padOwner())
    }

    @Test
    fun `the mirror leaving is an edge that clears the owner and not-dimmed alone clears nothing`() {
        // Over a foreign app yielded on the Bottom the mirror was never up: ruling R9a-1 keeps the menu walkable there.
        overlay.setDimmed(false)
        idleMain()
        openMenu()
        overlay.setDimmed(false)
        idleMain()
        assertEquals("not dimmed, never dimmed: no edge, the owner stays", "MENU", overlay.padOwner())

        // Over Mimir's own mirror (dimmed), then the mirror leaves under the open menu.
        overlay.setDimmed(true)
        idleMain()
        assertEquals("MENU", overlay.padOwner())
        overlay.setDimmed(false)
        idleMain()
        assertNull("the Presentation left MirrorPresented: the owner clears", overlay.padOwner())
        assertNoFocusableOverlay("and the menu, still on screen, no longer holds key focus")
        assertTrue("the menu itself stays open, touch-only", overlay.quickMenuOpen.value)
    }

    @Test
    fun `a rebuild after the mirror left does not grant the pad again`() {
        overlay.setDimmed(true)
        idleMain()
        openMenu()
        assertEquals("MENU", overlay.padOwner())
        overlay.setDimmed(false)
        idleMain()
        assertNull("fixture: the mirror leaving revoked the owner", overlay.padOwner())
        assertTrue("fixture: the menu stays open, touch-only", overlay.quickMenuOpen.value)

        // replaceViewImmediately: a theme rebuild of the open menu.
        val before = overlay.root()
        overlay.setOverlayTheme(CompanionOverlayTheme.Default.copy(inkArgb = 0xff445566.toInt()))
        idleMain()
        assertTrue("fixture: a new window replaced the old one", before !== overlay.root())
        assertNull("the rebuild did not grant MENU again", overlay.padOwner())
        assertNoFocusableOverlay("and the rebuilt window holds no key focus")
        assertFalse("and the floor route stays handed back", BottomPadInput.routeKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))

        // A second rebuild changes nothing either.
        overlay.setOverlayTheme(CompanionOverlayTheme.Default.copy(inkArgb = 0xff778899.toInt()))
        idleMain()
        assertNull(overlay.padOwner())
        assertNoFocusableOverlay("still no key focus")
    }

    @Test
    fun `a pad press on the Bottom goes to the screen's idle controller and every pad event the menu takes resets the ᛗ hide timer`() {
        val seen = ArrayList<Any>()
        overlay.setScreenActivityObserver { seen += it }
        var hideTimerResets = 0
        overlay.setPrimaryIdleInteractionObserver { hideTimerResets++ }
        var presses = 0
        overlay.setPadIdleInteraction {
            presses += 1
            false
        }
        openMenu()
        seen.clear()
        hideTimerResets = 0
        presses = 0

        overlay.root().dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        overlay.root().dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("each key edge the menu takes resets the hide timer", 2, hideTimerResets)
        assertEquals("a key DOWN is one press for the idle controller, its up is not", 1, presses)
        assertEquals("the pad reports no touch-style screen activity of its own: the gate is its one road to the idle controller", 0, seen.size)

        val later = SystemClock.uptimeMillis() + 500
        overlay.root().dispatchGenericMotionEvent(joystick(hatX = 0f, timeMs = later))
        overlay.root().dispatchGenericMotionEvent(joystick(hatX = 0f, timeMs = later + 20))
        assertEquals("a joystick stream resets the hide timer at most once per throttle window", 3, hideTimerResets)
        overlay.root().dispatchGenericMotionEvent(joystick(hatX = 0f, timeMs = later + 400))
        assertEquals("and again once the window has passed", 4, hideTimerResets)
        assertEquals("a hat at rest is no press for the idle controller", 1, presses)
        overlay.root().dispatchGenericMotionEvent(joystick(hatX = 1f, timeMs = later + 800))
        assertEquals("a hat past the dead zone is", 2, presses)
    }

    // ---- the menu's pad and the screen idle : the same PadIdleGate as Main's lane, over the REAL idle controller ----

    private class FakeIdleScheduler : ScreenIdleSchedule {
        class Task(val dueAtMs: Long, val action: () -> Unit, var cancelled: Boolean = false)

        var nowMs = 0L
            private set
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, onDeadline: () -> Unit): () -> Unit {
            val task = Task(nowMs + delayMs, onDeadline)
            tasks += task
            return { task.cancelled = true }
        }

        /** Moves the virtual clock, firing each deadline that falls due on the way, in due order. */
        fun advanceBy(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val next = tasks.filterNot { it.cancelled }.filter { it.dueAtMs <= target }.minByOrNull(Task::dueAtMs) ?: break
                nowMs = next.dueAtMs
                next.cancelled = true
                next.action()
            }
            nowMs = target
        }
    }

    private val dimCalls = ArrayList<Boolean>()
    private val idleClock = FakeIdleScheduler()

    /** Main's idle controller (a minute's timeout), handed to the menu the way the app hands it: its own `onUserInteraction`. */
    private fun idleControllerOnTheMenu(): MimirScreenIdleController =
        MimirScreenIdleController(
            readTimeoutSeconds = { 60 },
            applyDimmed = dimCalls::add,
            applyKeepScreenOn = {},
            scheduler = idleClock,
        ).also {
            it.onResume()
            overlay.setPadIdleInteraction(it::onUserInteraction)
        }

    @Test
    fun `pad-only presses on the open menu every thirty seconds for three minutes never dim the screen`() {
        val idle = idleControllerOnTheMenu()
        openMenu()
        val menu = overlay.root()
        repeat(6) { press ->
            idleClock.advanceBy(30_000L)
            assertTrue("press ${press + 1} is the menu's", menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
            menu.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        assertFalse("six presses over three minutes on a minute's timeout: never dimmed", idle.isDimmed)
        assertEquals(emptyList<Boolean>(), dimCalls)
        // The same silence without a press does dim at the minute: the presses are what held it.
        idleClock.advanceBy(60_000L)
        assertTrue(idle.isDimmed)
    }

    @Test
    fun `a pad press while the screen is dimmed wakes it and is eaten with its key up, and the next press acts`() {
        val idle = idleControllerOnTheMenu()
        openMenu()
        val menu = overlay.root()
        val lit = overlay.litIndex()
        idleClock.advanceBy(60_000L)
        assertTrue("fixture: the screen dimmed with the pad idle", idle.isDimmed)

        assertTrue("the wake press is consumed", menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertFalse("and woke the screen", idle.isDimmed)
        assertEquals("the ring did not move for it", lit, overlay.litIndex())
        assertTrue("its key up is eaten", menu.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals("still not moved", lit, overlay.litIndex())

        assertTrue(menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals("the next press acts: the ring moved one coin", (lit ?: 0) + 1, overlay.litIndex())
        assertFalse(idle.isDimmed)
    }

    @Test
    fun `a hat press while the screen is dimmed wakes it and is eaten until it is let go, and the next hat press moves the ring`() {
        val idle = idleControllerOnTheMenu()
        openMenu()
        val menu = overlay.root()
        val lit = overlay.litIndex()
        idleClock.advanceBy(60_000L)
        assertTrue(idle.isDimmed)
        val base = SystemClock.uptimeMillis() + 500

        assertTrue(menu.dispatchGenericMotionEvent(joystick(hatX = 1f, timeMs = base)))
        assertFalse("the hat woke the screen", idle.isDimmed)
        assertEquals("and moved nothing", lit, overlay.litIndex())
        assertTrue(menu.dispatchGenericMotionEvent(joystick(hatX = 0f, timeMs = base + 50)))
        assertEquals("its release moved nothing either", lit, overlay.litIndex())

        assertTrue(menu.dispatchGenericMotionEvent(joystick(hatX = 1f, timeMs = base + 400)))
        assertEquals("the next press moves the ring one coin", (lit ?: 0) + 1, overlay.litIndex())
    }

    @Test
    fun `Main's wake latch is dropped when the menu takes the pad and gives it back, and Main's next press acts`() {
        var asleep = true
        var mainPresses = 0
        // Main's gate as MainActivity builds it: the Bottom's pad ownership is its ownership source. A frozen clock, so only ownership can end the latch here.
        val main = PadIdleGate(interaction = { asleep.also { asleep = false } }, onWake = {}, ownership = { BottomPadInput.ownerEpoch }, clock = { 0L })
        fun press(keyCode: Int) = main.key(down = true, up = false, keyCode = keyCode) {
            mainPresses += 1
            true
        }
        assertTrue("Main's wake press is eaten", press(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue("and while it is held an unrelated press is eaten with it (Main's rule)", press(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(0, mainPresses)

        // The wake key's release goes to the ᛗ menu, which takes the pad and gives it back (B): Main keeps focus and never sees the up.
        openMenu()
        val menu = overlay.root()
        menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
        menu.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
        idleMain()
        assertNull("fixture: the menu gave the pad back", overlay.padOwner())

        assertTrue(press(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals("the next Main press acts: the ownership change cleared Main's latch", 1, mainPresses)
    }

    @Test
    fun `with no idle controller handed over the menu's pad acts on every press, as before`() {
        openMenu()
        val menu = overlay.root()
        val lit = overlay.litIndex()
        assertTrue(menu.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals((lit ?: 0) + 1, overlay.litIndex())
    }

    @Test
    fun `a held confirm dies with the owner and never runs on a menu that lost the pad`() {
        val frames = captureFrames()
        openMenu()
        val menu = overlay.root()
        menu.dispatchKeyEvent(keyAt(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 1_000))
        menu.dispatchKeyEvent(keyAt(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 1_020))
        assertEquals("the A waits for its frame", 1, frames.pending)
        // The mirror leaves before the frame: the held A must not run on a menu that no longer owns the pad.
        overlay.setDimmed(true)
        overlay.setDimmed(false)
        idleMain()
        assertNull(overlay.padOwner())
        assertTrue("the pending frame callback was cancelled with the owner", frames.cancelled)
        frames.run()
        assertTrue("and even a late frame runs nothing", actions.none { it is CompanionOverlayAction.QuickMenu })
    }

    @Test
    fun `a hat press stamped before the A moves the ring first and the A acts on the new coin`() {
        val frames = captureFrames()
        openMenu()
        val menu = overlay.root()
        // RIGHT pressed at 995, A at 1000..1020. The keys arrive at once; the batched hat arrives after them.
        menu.dispatchKeyEvent(keyAt(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 1_000))
        menu.dispatchKeyEvent(keyAt(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 1_020))
        assertTrue("the old coin has not run ahead of the move", actions.none { it is CompanionOverlayAction.QuickMenu })
        menu.dispatchGenericMotionEvent(joystick(hatX = 1f, timeMs = 995))
        assertEquals("the ring moved onto the second coin", 1, overlay.litIndex())
        frames.run()
        val pick = actions.filterIsInstance<CompanionOverlayAction.QuickMenu>().single()
        assertEquals("the A acted on the coin the hat lit", QuickMenuItem.QUICK_LAUNCH, pick.item)
    }

    @Test
    fun `a hat press stamped after the A leaves the A on the old coin`() {
        val frames = captureFrames()
        openMenu()
        val menu = overlay.root()
        menu.dispatchKeyEvent(keyAt(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 1_000))
        menu.dispatchKeyEvent(keyAt(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 1_020))
        menu.dispatchGenericMotionEvent(joystick(hatX = 1f, timeMs = 1_040))
        frames.run()
        val pick = actions.filterIsInstance<CompanionOverlayAction.QuickMenu>().single()
        assertEquals("the A went down first, on the first coin", QuickMenuItem.SECONDARY_TABS, pick.item)
        assertEquals("and the ring moved after it", 1, overlay.litIndex())
    }

    // ---- fixtures ----

    private fun mountOnBottom() {
        val target = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId)
        overlay.targetOverlay(target)
        idleMain()
        assertEquals("fixture: the ᛗ mounted on the Bottom", target, overlay.activeTarget.value)
    }

    /** A held press on the ᛗ past the long-press timeout: the existing touch route that opens the menu. */
    private fun openMenu() {
        val button = (overlay.root() as ViewGroup).getChildAt(0)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            assertTrue("fixture: the ᛗ took the press", button.dispatchTouchEvent(down))
        } finally {
            down.recycle()
        }
        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1_500L))
        assertTrue("fixture: the long press opened the menu", overlay.quickMenuOpen.value)
    }

    /** Past any fade the collapse started. */
    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500L))
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun key(action: Int, keyCode: Int): KeyEvent = keyAt(action, keyCode, SystemClock.uptimeMillis())

    private fun keyAt(action: Int, keyCode: Int, timeMs: Long): KeyEvent = KeyEvent(timeMs, timeMs, action, keyCode, 0)

    /** A joystick sample stamped [timeMs] whose D-pad reads [hatX], the way the Thor's pad reports its D-pad. */
    private fun joystick(hatX: Float, timeMs: Long): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_HAT_X, hatX) })
        return MotionEvent.obtain(
            timeMs, timeMs, MotionEvent.ACTION_MOVE, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_JOYSTICK, 0,
        )
    }

    /** The frame the controller's held A waits for, under the test's control instead of the Choreographer's. */
    private class Frames {
        var pending = 0
        var cancelled = false
        private var block: (() -> Unit)? = null

        fun post(block: () -> Unit): () -> Unit {
            pending += 1
            this.block = block
            return { cancelled = true; pending -= 1; this.block = null }
        }

        fun run() {
            val due = block ?: return
            block = null
            pending -= 1
            due()
        }
    }

    /** Swaps the controller's internal `MenuPadFrameScheduler` for one that hands the frame to [Frames]. */
    private fun captureFrames(): Frames {
        val frames = Frames()
        val type = Class.forName("com.gruning.mimir.rewrite.core.companionnative.MenuPadFrameScheduler")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, args ->
            @Suppress("UNCHECKED_CAST")
            frames.post(args[0] as () -> Unit)
        }
        ReflectionHelpers.setField(overlay, "padFrameScheduler", proxy)
        return frames
    }

    private fun outsideTheCard(menu: View): Pair<Float, Float> {
        val card = (menu as ViewGroup).getChildAt(0)
        // Unlaid (all zeros) every point is outside; laid out, take the first corner of the full-display window the card misses.
        val corners = listOf(1f to 1f, (menu.width - 1).toFloat() to (menu.height - 1).toFloat())
        return corners.firstOrNull { (x, y) -> x < card.left || x >= card.right || y < card.top || y >= card.bottom } ?: corners.first()
    }

    private fun AndroidCompanionOverlayController.root(): View =
        checkNotNull(ReflectionHelpers.getField<View?>(this, "overlayView")) { "fixture: a view is mounted" }

    private fun AndroidCompanionOverlayController.padOwner(): String? =
        ReflectionHelpers.getField<Any?>(this, "secondaryPadOwner")?.toString()

    private fun AndroidCompanionOverlayController.litIndex(): Int? {
        val focus = ReflectionHelpers.getField<Any>(this, "menuFocus")
        return ReflectionHelpers.callInstanceMethod<Int?>(focus, "getLitIndex")
    }

    private fun View.flags(): Int = (layoutParams as WindowManager.LayoutParams).flags

    /** Host-side half of the ANR guard: once the owner is clear, no overlay window of Mimir's can hold key focus. */
    private fun assertNoFocusableOverlay(message: String) {
        val view = ReflectionHelpers.getField<View?>(overlay, "overlayView") ?: return
        assertTrue(message, view.flags() and NOT_FOCUSABLE != 0)
    }

    private fun resetOverlaySingleton() {
        ReflectionHelpers.setStaticField(AndroidCompanionOverlayController::class.java, "shared", null)
    }

    private companion object {
        const val NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }
}
