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
import com.gruning.mimir.rewrite.core.companionnative.BottomImeVisibility
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.model.CompanionPayload
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
 * The REAL [AndroidCompanionOverlayController] under Robolectric, with Mimir's keyboard going up and down on the Bottom (the
 * process-wide [BottomImeVisibility] a real keyboard writes). The Bottom ᛗ must be unavailable while its keyboard is open;
 * the back guard previously fired over a Bottom app with the keyboard up.
 *
 * The 10-07 review found the keyboard-up path reset a press's `dragging` and `longPressFired` and made the root GONE with no
 * CANCEL after it. A GONE root still gets the rest of the touch it already had, so a lift with no further MOVE reached the tap
 * branch and opened Recents. Those tests dispatch to the coin view itself, as [CompanionCoinTapRecentsTest] does: the
 * controller's touch listener is the thing under test, and the window system's delivery is device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomImeOverlayBehaviourTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private lateinit var overlay: AndroidCompanionOverlayController
    private val actions = mutableListOf<CompanionOverlayAction>()
    private val keyboardOwner = Any()
    private var bottomDisplayId = -1

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // First: a controller from an earlier test left its listeners on the process-wide holder.
        BottomImeVisibility.resetForTest()
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
        BottomImeVisibility.resetForTest()
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        idle()
    }

    // ---- an interrupted press ends as nothing ---------------------------------------------------------------------------

    @Test
    fun `a press the keyboard interrupts and a lift with no move after it opens no Recents and no menu`() {
        mountOnBottom()
        val coin = coin()

        press(coin)
        keyboard(shown = true)
        assertEquals("fixture: the keyboard took the ᛗ away", View.GONE, root().visibility)
        release(coin)
        advance(1_000)

        assertEquals("no Recents request", emptyList<CompanionOverlayAction>(), quickMenuActions())
        assertFalse("and no menu", overlay.quickMenuOpen.value)
    }

    @Test
    fun `a hold the keyboard interrupts never opens the menu`() {
        mountOnBottom()
        val coin = coin()

        press(coin)
        keyboard(shown = true)
        advance(longPress() + 50)
        release(coin)
        advance(1_000)

        assertFalse("the long press was cancelled with the press", overlay.quickMenuOpen.value)
        assertEquals(emptyList<CompanionOverlayAction>(), quickMenuActions())
    }

    @Test
    fun `a drag the keyboard interrupts stops where it was, and its lift does nothing`() {
        mountOnBottom()
        val coin = coin()
        val slop = 8 * app.resources.displayMetrics.density

        press(coin, 100f, 100f)
        move(coin, 100f + slop + 20f, 100f)
        val lp = root().layoutParams as WindowManager.LayoutParams
        val movedTo = lp.x
        assertTrue("fixture: the drag started and moved the window", movedTo != 0)

        keyboard(shown = true)
        move(coin, 20f, 100f)
        assertEquals("a move after the interruption moves nothing", movedTo, lp.x)
        release(coin, 20f, 100f)
        advance(1_000)

        assertEquals("and the lift is no drag end, no tap", emptyList<CompanionOverlayAction>(), actions.filterNot { it is CompanionOverlayAction.DragMoved })
        assertFalse(overlay.quickMenuOpen.value)
    }

    @Test
    fun `a drag that stopped before the lift, when the keyboard comes up, is not a tap either`() {
        mountOnBottom()
        val coin = coin()
        val slop = 8 * app.resources.displayMetrics.density

        press(coin, 100f, 100f)
        move(coin, 100f + slop + 20f, 100f)
        keyboard(shown = true)
        release(coin, 100f + slop + 20f, 100f) // no MOVE after the interruption: the old reset made this a tap
        advance(1_000)

        assertEquals(emptyList<CompanionOverlayAction>(), quickMenuActions())
    }

    @Test
    fun `a CANCEL after the interruption does nothing, and the interrupted press look is put back`() {
        mountOnBottom()
        val coin = coin()

        press(coin)
        advance(300)
        keyboard(shown = true)
        assertEquals("the coin is not left pressed in", 1f, coin.scaleX, 0f)
        send(coin, MotionEvent.ACTION_CANCEL, 100f, 100f)
        advance(1_000)

        assertEquals(emptyList<CompanionOverlayAction>(), quickMenuActions())
        assertFalse(overlay.quickMenuOpen.value)
    }

    @Test
    fun `a touch that still reaches the ᛗ while the keyboard is up is ignored whole`() {
        mountOnBottom()
        val coin = coin()
        keyboard(shown = true)

        tap(coin)
        advance(1_000)

        assertEquals(emptyList<CompanionOverlayAction>(), quickMenuActions())
        assertFalse(overlay.quickMenuOpen.value)
    }

    @Test
    fun `after the keyboard hides the ᛗ is back and the next tap opens Recents once`() {
        mountOnBottom()
        val coin = coin()
        press(coin)
        keyboard(shown = true)
        release(coin)
        advance(500)
        assertEquals("fixture: the interrupted press asked for nothing", emptyList<CompanionOverlayAction>(), quickMenuActions())

        keyboard(shown = false)
        assertEquals("the ᛗ is back", View.VISIBLE, root().visibility)
        tap(coin)
        advance(500)

        assertEquals("the next DOWN cleared the abort: a tap is a tap again", 1, quickMenuActions().size)
    }

    // ---- the ᛗ and the strip follow the keyboard ------------------------------------------------------------------------

    @Test
    fun `the ᛗ hides and the strip stands down while the keyboard is up on the Bottom, and both return after`() {
        mountOnBottom()
        assertEquals(View.VISIBLE, root().visibility)
        assertTrue("fixture: over a foreign app the strip takes touches", stripTouchable())

        keyboard(shown = true)
        assertEquals("the ᛗ is hidden, so it takes no touch", View.GONE, root().visibility)
        assertFalse("the strip takes none", stripTouchable())

        keyboard(shown = false)
        assertEquals(View.VISIBLE, root().visibility)
        assertTrue(stripTouchable())
    }

    @Test
    fun `a keyboard on another display leaves the single-display badge unchanged`() {
        mountOnBottom()
        keyboard(shown = true, display = Display.DEFAULT_DISPLAY)
        assertEquals(View.VISIBLE, root().visibility)
        assertTrue(stripTouchable())
        keyboard(shown = false)

        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY))
        idle()
        assertEquals("fixture: the badge mounted on Main", CompanionOverlayRole.PRIMARY_SINGLE, overlay.activeTarget.value?.role)
        keyboard(shown = true, display = Display.DEFAULT_DISPLAY)
        assertEquals("a keyboard on Main does not hide Main's badge", View.VISIBLE, root().visibility)
    }

    @Test
    fun `an open menu is left alone by the keyboard`() {
        mountOnBottom()
        val coin = coin()
        press(coin)
        advance(longPress() + 50)
        release(coin)
        advance(1_000)
        assertTrue("fixture: the menu is up", overlay.quickMenuOpen.value)

        keyboard(shown = true)

        assertTrue(overlay.quickMenuOpen.value)
        assertEquals("the menu is not the button", View.VISIBLE, root().visibility)
    }

    @Test
    fun `an edge swipe fires EdgeBack with no keyboard, and not once the keyboard is up, even from before it`() {
        mountOnBottom()
        val strip = strip()
        val trigger = 40 * app.resources.displayMetrics.density

        swipe(strip, 500f, 500f - trigger - 20f)
        assertEquals("control: a swipe over a foreign app with no keyboard reclaims", 1, edgeBackCount())

        actions.clear()
        send(strip, MotionEvent.ACTION_DOWN, 500f, 100f)
        keyboard(shown = true)
        send(strip, MotionEvent.ACTION_MOVE, 500f - trigger - 20f, 100f)
        send(strip, MotionEvent.ACTION_UP, 500f - trigger - 20f, 100f)
        assertEquals("a swipe begun before the keyboard cannot reclaim under it", 0, edgeBackCount())

        swipe(strip, 500f, 500f - trigger - 20f)
        assertEquals("nor can one made with the keyboard up", 0, edgeBackCount())

        keyboard(shown = false)
        swipe(strip, 500f, 500f - trigger - 20f)
        assertEquals("with the keyboard down the strip works again", 1, edgeBackCount())
    }

    @Test
    fun `a mount made while the keyboard is already up starts hidden, and the keyboard going down reveals and re-arms it`() {
        keyboard(shown = true)
        mountOnBottom()
        assertEquals("the ᛗ mounts hidden under the keyboard", View.GONE, root().visibility)
        assertFalse("and so does the strip's touch", stripTouchable())

        var rearmed = 0
        overlay.setPrimaryIdleInteractionObserver { rearmed += 1 }
        keyboard(shown = false)

        assertEquals(View.VISIBLE, root().visibility)
        assertTrue(stripTouchable())
        assertEquals("revealed and re-armed once, as any touch on the Bottom would", 1, rearmed)
    }

    // ---- fixtures ----

    private fun quickMenuActions(): List<CompanionOverlayAction.QuickMenu> {
        idle()
        return actions.filterIsInstance<CompanionOverlayAction.QuickMenu>()
    }

    private fun edgeBackCount(): Int {
        idle()
        return actions.count { it === CompanionOverlayAction.EdgeBack }
    }

    /** Mimir's keyboard going up on [display] (the Bottom unless said otherwise), or down. */
    private fun keyboard(shown: Boolean, display: Int = bottomDisplayId) {
        BottomImeVisibility.report(keyboardOwner, if (shown) display else null)
        idle()
    }

    private fun longPress(): Long = ViewConfiguration.getLongPressTimeout().toLong()

    private fun mountOnBottom() {
        val target = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId)
        overlay.targetOverlay(target)
        idle()
        assertEquals("fixture: the ᛗ mounted on the Bottom", target, overlay.activeTarget.value)
    }

    private fun root(): View =
        checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView")) { "fixture: a view is mounted" }

    private fun coin(): View = (root() as ViewGroup).getChildAt(0)

    private fun strip(): View =
        checkNotNull(ReflectionHelpers.getField<View?>(overlay, "edgeSwipeView")) { "fixture: the edge strip is mounted" }

    private fun stripTouchable(): Boolean =
        ((strip().layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0

    private fun swipe(view: View, fromX: Float, toX: Float) {
        send(view, MotionEvent.ACTION_DOWN, fromX, 100f)
        send(view, MotionEvent.ACTION_MOVE, toX, 100f)
        send(view, MotionEvent.ACTION_UP, toX, 100f)
    }

    private fun tap(coin: View, x: Float = 100f, y: Float = 100f) {
        press(coin, x, y)
        release(coin, x, y)
    }

    private fun press(coin: View, x: Float = 100f, y: Float = 100f) = send(coin, MotionEvent.ACTION_DOWN, x, y)
    private fun move(coin: View, x: Float, y: Float) = send(coin, MotionEvent.ACTION_MOVE, x, y)
    private fun release(coin: View, x: Float = 100f, y: Float = 100f) = send(coin, MotionEvent.ACTION_UP, x, y)

    private fun send(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
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
