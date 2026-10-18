package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Point
import android.hardware.display.DisplayManager
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
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.time.Duration
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Policy 2026-10-09 (which retired the 2026-10-08 corner slot), on the REAL [AndroidCompanionOverlayController] under Robolectric: the Bottom's ᛗ coin stays exactly where
 * the player drops it, on every Mimir page and over other apps; it has ONE remembered position, persisted; there is no snap-back and no glide on the Bottom; and with nothing
 * saved it starts at the right edge, just below the header row (72 dp down), so it covers no header control.
 *
 * Touches go to the coin view itself; the clock is the looper's. "Over Mimir's own pages" and "over another app" are what the Bottom's presentation reports through
 * `setDimmed(true)` and `setDimmed(false)`: the coin must not care.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomCoinFreePlacementTest {

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
        startOverlay()
    }

    @After
    fun tearDown() {
        scope.cancel()
        resetOverlaySingleton()
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /** A fresh controller, as after a restart: it knows only what was persisted. */
    private fun startOverlay() {
        actions.clear()
        overlay = startCompanionOverlay(app, scope)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { overlay.actions.collect { actions += it } }
        idle()
    }

    private fun restart() {
        resetOverlaySingleton()
        startOverlay()
    }

    @Test
    fun `with nothing saved the Bottom coin starts at the right edge, just below the header row`() {
        mountOnBottom()
        val lp = layoutParams()
        val density = lp.width / 56f
        val display = displaySize()
        println("FREE-PLACEMENT default lp=${lp.x},${lp.y} size=${lp.width} density=$density display=${display.x}x${display.y}")
        assertEquals("the right edge, 14 dp in", display.x - lp.width - 14f * density, lp.x.toFloat(), 2f)
        assertEquals("72 dp down: just below the header row", 72f * density, lp.y.toFloat(), 2f)
    }

    @Test
    fun `a Bottom drag over Mimir's own pages stays exactly where it was dropped and is persisted`() {
        mountOnBottom()
        overlay.setDimmed(true)
        idle()
        assertKeptAndPersisted(dx = -150f, dy = 90f)
    }

    @Test
    fun `a Bottom drag over another app stays exactly where it was dropped and is persisted`() {
        mountOnBottom()
        overlay.setDimmed(false)
        idle()
        assertKeptAndPersisted(dx = -200f, dy = 120f)
    }

    @Test
    fun `one remembered position serves both, so a drop over a page is where the coin is over an app, and the other way round`() {
        mountOnBottom()
        overlay.setDimmed(true)
        idle()
        val first = drag(dx = -120f, dy = 80f)
        overlay.setDimmed(false)
        idle()
        advance(1_000)
        val overApp = layoutParams()
        assertEquals("over an app the coin has not moved", first, overApp.x to overApp.y)
        val second = drag(dx = 40f, dy = 30f)
        overlay.setDimmed(true)
        idle()
        advance(1_000)
        val backOnPage = layoutParams()
        assertEquals("back over a page it is where the app drop left it", second, backOnPage.x to backOnPage.y)
        restart()
        mountOnBottom()
        val restarted = layoutParams()
        assertEquals("and a restart finds the same one position", second.first.toFloat(), restarted.x.toFloat(), 2f)
        assertEquals(second.second.toFloat(), restarted.y.toFloat(), 2f)
    }

    @Test
    fun `a drag the system cancels is committed as a release commits it, on the Bottom and on Main`() {
        // The Bottom coin: cancelled mid-move, it is where the finger left it and that is saved, so a restart finds it there.
        mountOnBottom()
        val bottomStart = layoutParams().let { it.x to it.y }
        val bottomCoin = coin()
        press(bottomCoin, 100f, 100f)
        move(bottomCoin, 100f - 75f, 100f + 45f)
        move(bottomCoin, 100f - 150f, 100f + 90f)
        cancel(bottomCoin, 100f - 150f, 100f + 90f)
        idle()
        val bottomDropped = layoutParams().let { it.x to it.y }
        assertEquals("the cancelled drag left the coin where the finger took it", (bottomStart.first - 150) to (bottomStart.second + 90), bottomDropped)
        advance(1_000)
        assertEquals("with no snap-back and no glide on the Bottom", bottomDropped, layoutParams().let { it.x to it.y })
        val bottomDisplay = displaySize(bottomDisplayId)
        val bottomMoved = actions.filterIsInstance<CompanionOverlayAction.DragMoved>().last()
        assertEquals("the cancel commits it, announced as a release announces it", bottomDropped.first.toFloat() / bottomDisplay.x, bottomMoved.xRatio, 0.01f)
        restart()
        mountOnBottom()
        assertEquals("a restart finds the Bottom coin where the cancelled drag left it", bottomDropped.first.toFloat(), layoutParams().x.toFloat(), 2f)
        assertEquals(bottomDropped.second.toFloat(), layoutParams().y.toFloat(), 2f)

        // Main's coin: cancelled mid-move, it takes the edge snap a release takes, glides there, and the settled edge is what is saved.
        restart()
        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY))
        idle()
        assertEquals("fixture: the ᛗ mounted on Main", CompanionOverlayRole.PRIMARY_SINGLE, overlay.activeTarget.value?.role)
        val mainStart = layoutParams().let { it.x to it.y }
        val density = layoutParams().width / 56f
        val mainDisplay = displaySize(Display.DEFAULT_DISPLAY)
        val mainCoin = coin()
        press(mainCoin, 100f, 100f)
        move(mainCoin, 100f - 50f, 100f + 60f)
        move(mainCoin, 100f - 100f, 100f + 120f)
        cancel(mainCoin, 100f - 100f, 100f + 120f)
        idle()
        advance(1_000)
        val settled = layoutParams().let { it.x to it.y }
        println("FREE-PLACEMENT cancel main start=$mainStart settled=$settled display=${mainDisplay.x}x${mainDisplay.y}")
        val rawX = mainStart.first - 100
        assertTrue("the cancel took the edge snap: the drop at x=$rawX settled at $settled", settled.first != rawX)
        assertEquals("on the nearest (right) edge, 14 dp in", mainDisplay.x - layoutParams().width - 14f * density, settled.first.toFloat(), 2f)
        val mainMoved = actions.filterIsInstance<CompanionOverlayAction.DragMoved>().last()
        assertEquals("the settled spot is the one announced", settled.first.toFloat() / mainDisplay.x, mainMoved.xRatio, 0.01f)
        restart()
        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY))
        idle()
        assertEquals("and a restart finds Main's coin on the settled edge", settled.first.toFloat(), layoutParams().x.toFloat(), 2f)
        assertEquals(settled.second.toFloat(), layoutParams().y.toFloat(), 2f)
    }

    private fun assertKeptAndPersisted(dx: Float, dy: Float) {
        val start = layoutParams().let { it.x to it.y }
        val dropped = drag(dx, dy)
        println("FREE-PLACEMENT start=$start dropped=$dropped")
        assertNotEquals2("the coin moved", start, dropped)
        assertEquals("exactly the finger's travel", (start.first + dx).toInt() to (start.second + dy).toInt(), dropped)
        // No snap-back and no glide: the release moves nothing, now or later.
        val atRelease = layoutParams().let { it.x to it.y }
        assertEquals("at the release it is where it was dropped", dropped, atRelease)
        advance(1_000)
        assertEquals("a second later it is still there: no glide and no snap-back", dropped, layoutParams().let { it.x to it.y })
        val moved = actions.filterIsInstance<CompanionOverlayAction.DragMoved>().last()
        val display = displaySize()
        assertEquals("the drop is announced as dropped", dropped.first.toFloat() / display.x, moved.xRatio, 0.01f)
        assertEquals(dropped.second.toFloat() / display.y, moved.yRatio, 0.01f)
        // Persisted: a restarted controller mounts the coin at the drop.
        restart()
        mountOnBottom()
        val restarted = layoutParams()
        assertEquals("a restart finds it where it was dropped", dropped.first.toFloat(), restarted.x.toFloat(), 2f)
        assertEquals(dropped.second.toFloat(), restarted.y.toFloat(), 2f)
    }

    private fun assertNotEquals2(message: String, a: Pair<Int, Int>, b: Pair<Int, Int>) = assertTrue("$message: $a vs $b", a != b)

    /** One deliberate drag of the coin by ([dx], [dy]); returns where the window sits after the release. */
    private fun drag(dx: Float, dy: Float): Pair<Int, Int> {
        val coin = coin()
        press(coin, 100f, 100f)
        move(coin, 100f + dx / 2f, 100f + dy / 2f)
        move(coin, 100f + dx, 100f + dy)
        release(coin, 100f + dx, 100f + dy)
        idle()
        val lp = layoutParams()
        return lp.x to lp.y
    }

    // ---- fixtures ----

    private fun displaySize(displayId: Int = bottomDisplayId): Point {
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        return Point().also { display.getRealSize(it) }
    }

    private fun mountOnBottom() {
        val target = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId)
        overlay.targetOverlay(target)
        idle()
        assertEquals("fixture: the ᛗ mounted on the Bottom", target, overlay.activeTarget.value)
    }

    private fun root(): View =
        checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView")) { "fixture: a view is mounted" }

    private fun layoutParams(): WindowManager.LayoutParams =
        checkNotNull(root().layoutParams as? WindowManager.LayoutParams) { "fixture: the mounted root carries window layout params" }

    private fun coin(): View = (root() as ViewGroup).getChildAt(0)

    private fun press(coin: View, x: Float, y: Float) = send(coin, MotionEvent.ACTION_DOWN, x, y)
    private fun move(coin: View, x: Float, y: Float) = send(coin, MotionEvent.ACTION_MOVE, x, y)
    private fun release(coin: View, x: Float, y: Float) = send(coin, MotionEvent.ACTION_UP, x, y)
    private fun cancel(coin: View, x: Float, y: Float) = send(coin, MotionEvent.ACTION_CANCEL, x, y)

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
