package com.gruning.mimir.rewrite

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuOrigin
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

/**
 * Policy 2026-10-03, on the REAL [AndroidCompanionOverlayController] under Robolectric: on the second display a tap on the
 * ᛗ coin opens Recents on Main (the menu's Tabs request), a hold opens the menu, a drag only moves the coin, and a retap inside
 * the 130 ms menu hand-over does nothing. The single-screen badge keeps its menu / music-panel tap.
 *
 * Touches are dispatched to the coin view itself (the view the controller's touch listener is on), the actions are collected
 * from the controller's own flow, and the clock is the looper's, so a hold is the real long-press timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionCoinTapRecentsTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private lateinit var overlay: AndroidCompanionOverlayController
    private val actions = mutableListOf<CompanionOverlayAction>()
    private var bottomDisplayId = -1

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        CompanionState.set(CompanionPayload.idle())
        resetOverlaySingleton()
        ShadowSettings.setCanDrawOverlays(true)
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        actions.clear()
        overlay = startCompanionOverlay(app, scope)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { overlay.actions.collect { actions += it } }
        idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        resetOverlaySingleton()
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a Bottom tap dispatches one SYSTEM_TABS request from the coin and opens no menu`() {
        mountOnBottom()
        val coin = coin()

        tap(coin)
        advance(1_000)

        val requests = quickMenuActions()
        assertEquals("one request", 1, requests.size)
        val request = requests.single()
        assertEquals(QuickMenuItem.SYSTEM_TABS, request.item)
        assertEquals(CompanionOverlayRole.SECONDARY_COMPANION, request.role)
        assertEquals("tagged as the coin's own tap, for its receipt", QuickMenuOrigin.COIN_TAP, request.origin)
        assertFalse("a tap opens no menu", overlay.quickMenuOpen.value)
        assertFalse("and starts no swap", overlay.quickMenuSwapInFlight.value)
        assertTrue("the coin is still the mounted root's child", root().let { it is ViewGroup && it.getChildAt(0) === coin })
    }

    @Test
    fun `two separate Bottom taps are two requests`() {
        mountOnBottom()
        val coin = coin()
        tap(coin)
        advance(300)
        tap(coin)
        advance(300)
        assertEquals(2, quickMenuActions().size)
    }

    @Test
    fun `a Bottom hold opens the menu and dispatches no Recents request`() {
        mountOnBottom()
        val coin = coin()

        press(coin)
        advance(longPress() + 50)
        release(coin)
        advance(1_000)

        assertTrue("the hold opened the menu", overlay.quickMenuOpen.value)
        assertEquals("and asked for no Recents", emptyList<CompanionOverlayAction>(), quickMenuActions())
    }

    @Test
    fun `a hold, release and retap inside the menu hand-over dispatches no Recents request`() {
        mountOnBottom()
        val coin = coin()
        var sawHandover = false
        // Robolectric's animators finish on their first frame, so the 130 ms hand-over cannot be held open by the clock. The
        // window manager hook runs at the hand-over itself, where the real controller's state is exactly the race's: the
        // outgoing root is about to be removed, `menuOpen` has not landed, and the swap is in flight.
        atHandover {
            sawHandover = true
            assertFalse("the menu has not landed", overlay.quickMenuOpen.value)
            assertTrue("the swap is in flight", overlay.quickMenuSwapInFlight.value)
            assertFalse("the outgoing root is disabled, though its child coin still takes touches", root().isEnabled)
            release(coin) // the hold's own release
            tap(coin)
            tap(coin)
        }

        press(coin)
        advance(longPress() + 50)
        advance(1_000)

        assertTrue("fixture: the hand-over ran", sawHandover)
        assertTrue("the menu landed", overlay.quickMenuOpen.value)
        assertEquals("the retaps asked for nothing", emptyList<CompanionOverlayAction>(), quickMenuActions())
    }

    @Test
    fun `a touch that went down in the hand-over and comes up after it still dispatches nothing`() {
        mountOnBottom()
        val coin = coin()
        var sawHandover = false
        atHandover {
            sawHandover = true
            release(coin) // the hold's own release
            press(coin) // the retap's DOWN, in the window
        }

        press(coin)
        advance(longPress() + 50)
        advance(1_000)
        release(coin) // ... and its UP, after the menu has landed
        advance(1_000)

        assertTrue("fixture: the hand-over ran", sawHandover)
        assertEquals(emptyList<CompanionOverlayAction>(), quickMenuActions())
    }

    @Test
    fun `with the menu up a tap on the coin dispatches nothing`() {
        mountOnBottom()
        val coin = coin()
        press(coin)
        advance(longPress() + 50)
        release(coin)
        advance(1_000)
        assertTrue("fixture: the menu is up", overlay.quickMenuOpen.value)
        assertFalse("fixture: no swap running", overlay.quickMenuSwapInFlight.value)

        // The menu replaced the coin's root; a touch that still reaches the old coin view is refused by the menu state.
        tap(coin)
        advance(500)

        assertEquals(emptyList<CompanionOverlayAction>(), quickMenuActions())
        assertTrue("and the menu is still up", overlay.quickMenuOpen.value)
    }

    @Test
    fun `a tap while the menu is closing dispatches nothing, and one after it has closed dispatches one`() {
        mountOnBottom()
        press(coin())
        advance(longPress() + 50)
        release(coin())
        advance(1_000)
        assertTrue("fixture: the menu is open", overlay.quickMenuOpen.value)

        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "collapseMenu")
        val buttonBack = coin()
        assertTrue("fixture: the collapse is in flight", overlay.quickMenuSwapInFlight.value)
        tap(buttonBack)
        assertEquals("nothing while it closes", emptyList<CompanionOverlayAction>(), quickMenuActions())

        advance(1_000)
        assertFalse("fixture: the menu has closed", overlay.quickMenuOpen.value)
        tap(coin())
        advance(300)
        assertEquals("a tap on the settled coin opens Recents", 1, quickMenuActions().size)
    }

    @Test
    fun `a nudge inside the slop is a tap and a drag past it dispatches nothing`() {
        mountOnBottom()
        val coin = coin()
        val slop = 8 * app.resources.displayMetrics.density

        press(coin, 100f, 100f)
        move(coin, 100f + slop / 2f, 100f)
        release(coin, 100f + slop / 2f, 100f)
        advance(300)
        assertEquals("a sub-slop nudge is a tap", 1, quickMenuActions().size)

        actions.clear()
        press(coin, 100f, 100f)
        move(coin, 100f + slop + 20f, 100f)
        release(coin, 100f + slop + 20f, 100f)
        advance(1_000)
        assertEquals("a drag past the slop never opens Recents", emptyList<CompanionOverlayAction>(), quickMenuActions())
        assertFalse("and opens no menu", overlay.quickMenuOpen.value)
    }

    @Test
    fun `a single-screen tap still opens the menu, and the music panel when there is music, never Recents`() {
        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY))
        idle()
        assertEquals("fixture: the ᛗ mounted on Main", CompanionOverlayRole.PRIMARY_SINGLE, overlay.activeTarget.value?.role)

        tap(coin())
        advance(1_000)
        assertTrue("no music: the tap opens the menu", overlay.quickMenuOpen.value)
        assertEquals(emptyList<CompanionOverlayAction>(), quickMenuActions())

        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "collapseMenu")
        advance(1_000)
        assertFalse("fixture: closed again", overlay.quickMenuOpen.value)
        overlay.setMusicPanelAvailable(true)
        idle()
        tap(coin())
        advance(1_000)
        assertEquals(
            "with music: the music panel is asked for",
            listOf<CompanionOverlayAction>(CompanionOverlayAction.MusicPanel(CompanionOverlayRole.PRIMARY_SINGLE)),
            actions.filterNot { it is CompanionOverlayAction.DragMoved },
        )
        assertEquals("and no Recents", emptyList<CompanionOverlayAction>(), quickMenuActions())
    }

    // ---- fixtures ----

    private fun quickMenuActions(): List<CompanionOverlayAction.QuickMenu> {
        idle()
        return actions.filterIsInstance<CompanionOverlayAction.QuickMenu>()
    }

    private fun longPress(): Long = ViewConfiguration.getLongPressTimeout().toLong()

    /** Runs [block] once, when the controller hands the outgoing root to the window manager for removal: the hand-over. */
    private fun atHandover(block: () -> Unit) {
        val real = ReflectionHelpers.getField<WindowManager>(overlay, "windowManager")
        var done = false
        val proxy = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
            if (!done && (method.name == "removeView" || method.name == "removeViewImmediate")) {
                done = true
                block()
            }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as WindowManager
        ReflectionHelpers.setField(overlay, "windowManager", proxy)
    }

    private fun mountOnBottom() {
        val target = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId)
        overlay.targetOverlay(target)
        idle()
        assertEquals("fixture: the ᛗ mounted on the Bottom", target, overlay.activeTarget.value)
    }

    private fun root(): View =
        checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView")) { "fixture: a view is mounted" }

    /** The coin: the child of the mounted root that carries the controller's touch listener. */
    private fun coin(): View {
        val group = root() as ViewGroup
        return group.getChildAt(0)
    }

    private fun tap(coin: View, x: Float = 100f, y: Float = 100f) {
        press(coin, x, y)
        release(coin, x, y)
    }

    private fun press(coin: View, x: Float = 100f, y: Float = 100f) = send(coin, MotionEvent.ACTION_DOWN, x, y)
    private fun move(coin: View, x: Float, y: Float) = send(coin, MotionEvent.ACTION_MOVE, x, y)
    private fun release(coin: View, x: Float = 100f, y: Float = 100f) = send(coin, MotionEvent.ACTION_UP, x, y)

    private fun send(coin: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            coin.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun advance(ms: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resetOverlaySingleton() {
        ReflectionHelpers.setStaticField(AndroidCompanionOverlayController::class.java, "shared", null)
    }
}
