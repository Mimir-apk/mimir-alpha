package com.gruning.mimir.rewrite

import android.app.Application
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptTimer
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * on the REAL [AndroidCompanionOverlayController] under Robolectric: while one of Mimir's own Bottom panels (Utility, Tweaker, Tabs) is open, the Bottom's ᛗ
 * coin FADES out and its window passes touches through; when the panel closes it fades back in at the SAME position. The window is never removed. Over a foreign app and on the
 * lattice (no panel up) the coin is exactly what it was, and Main's own coin is never hidden by a Bottom panel.
 *
 * The coin is told by the Bottom's surface stack (the receipts' `surface.state`), here through a real [BottomUiReceiptLog] and [observeBottomPanelCoin].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomCoinPanelHideTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private lateinit var overlay: AndroidCompanionOverlayController
    private var bottomDisplayId = -1

    private val log = BottomUiReceiptLog(
        sink = { _, _ -> },
        nowMs = { 0L },
        timer = BottomUiReceiptTimer { _, _ -> ({}) },
    )

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        CompanionState.set(CompanionPayload.idle())
        resetOverlaySingleton()
        ShadowSettings.setCanDrawOverlays(true)
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        overlay = startCompanionOverlay(app, scope)
        idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        resetOverlaySingleton()
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        idle()
    }

    private val fade = 180L

    @Test
    fun `opening a panel fades the coin out and makes its window touch-through, and the window stays`() {
        mountOnBottom()
        val window = root()
        val before = position()
        assertEquals("the coin shows at its resting look", 0.90f, window.alpha, 0.001f)
        assertFalse("and its window takes touches", flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)

        overlay.setCoinHiddenForPanel(true)
        advance(fade + 60)

        assertEquals("the coin is gone from view", 0f, window.alpha, 0.001f)
        assertTrue("its window passes every touch through while hidden", flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue("the same window is still mounted: it is hidden, not removed", root() === window)
        assertEquals("and it has not moved", before, position())
    }

    @Test
    fun `closing the panel brings the coin back at the same position and touchable`() {
        mountOnBottom()
        val window = root()
        val before = position()
        overlay.setCoinHiddenForPanel(true)
        advance(fade + 60)

        overlay.setCoinHiddenForPanel(false)
        advance(fade + 60)

        assertEquals("the coin is back at its resting look", 0.90f, window.alpha, 0.001f)
        assertFalse("and touchable again", flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue("in the same window", root() === window)
        assertEquals("at the same position", before, position())
    }

    @Test
    fun `the coin is told to fade, it is not snapped to zero`() {
        mountOnBottom()
        val window = root()
        overlay.setCoinHiddenForPanel(true)
        // Nothing ran yet: the request itself moves nothing, it starts an animator. (The paused looper cannot hold an animator at a given millisecond, so the 180 ms is pinned
        // as source in CompanionPanelCoinHideTest; what is read here is that the alpha is the animator's to move, and that it lands on zero.)
        assertEquals("the request alone does not snap the coin out", 0.90f, window.alpha, 0.001f)
        advance(fade + 60)
        assertEquals(0f, window.alpha, 0.001f)
    }

    @Test
    fun `the coin never hides over a foreign app or the lattice`() {
        mountOnBottom()
        val window = root()
        // A foreign app on the Bottom: the presentation says the mirror is not up (not dimmed). No panel asked, so nothing hides.
        overlay.setDimmed(false)
        advance(fade + 60)
        assertEquals(0.90f, window.alpha, 0.001f)
        assertFalse(flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        // Mimir's own lattice: the dim look, still present and touchable, never hidden.
        overlay.setDimmed(true)
        advance(fade + 60)
        assertEquals("the dim look is the one the lattice always had", 0.80f, window.alpha, 0.001f)
        assertFalse(flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test
    fun `a panel closing while the lattice is dimmed returns the coin to the dim look`() {
        mountOnBottom()
        val window = root()
        overlay.setDimmed(true)
        advance(fade + 60)
        overlay.setCoinHiddenForPanel(true)
        advance(fade + 60)
        assertEquals(0f, window.alpha, 0.001f)
        overlay.setCoinHiddenForPanel(false)
        advance(fade + 60)
        assertEquals("the dim look, not full strength: the panel changed nothing else", 0.80f, window.alpha, 0.001f)
    }

    @Test
    fun `Main's own coin is never hidden by a Bottom panel`() {
        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY))
        idle()
        val window = root()
        overlay.setCoinHiddenForPanel(true)
        advance(fade + 60)
        assertEquals(0.90f, window.alpha, 0.001f)
        assertFalse(flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test
    fun `a request made before the coin is mounted is worn the moment it mounts`() {
        overlay.setCoinHiddenForPanel(true)
        idle()
        mountOnBottom()
        assertEquals("a Bottom coin mounted under an open panel is born hidden", 0f, root().alpha, 0.001f)
        assertTrue(flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test
    fun `the Bottom's surface stack drives it end to end - Utility opens, the coin hides, it closes, the coin returns`() {
        mountOnBottom()
        val window = root()
        val before = position()
        val job = observeBottomPanelCoin(scope, overlay.activeTarget, overlay::setCoinHiddenForPanel, log)
        try {
            log.surface(BottomSurface.LATTICE, bottomDisplayId, visible = true)
            advance(fade + 60)
            assertEquals("the lattice alone: the coin shows", 0.90f, window.alpha, 0.001f)

            log.surface(BottomSurface.UTILITY, bottomDisplayId, visible = true)
            advance(fade + 60)
            assertEquals("Utility is open: the coin is hidden", 0f, window.alpha, 0.001f)
            assertTrue(flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)

            log.surface(BottomSurface.UTILITY, bottomDisplayId, visible = false)
            advance(fade + 60)
            assertEquals("Utility closed: the coin is back", 0.90f, window.alpha, 0.001f)
            assertFalse(flags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals("at the same position", before, position())

            log.surface(BottomSurface.TABS, bottomDisplayId, visible = true)
            advance(fade + 60)
            assertEquals("Tabs hides it too", 0f, window.alpha, 0.001f)
            log.surface(BottomSurface.TABS, bottomDisplayId, visible = false)
            advance(fade + 60)
            assertEquals(0.90f, window.alpha, 0.001f)
        } finally {
            job.cancel()
        }
    }

    // ---- fixtures ----

    private fun mountOnBottom() {
        val target = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId)
        overlay.targetOverlay(target)
        idle()
        assertEquals("fixture: the ᛗ mounted on the Bottom", target, overlay.activeTarget.value)
        assertNotNull(root())
        // The Bottom's presentation tells the coin its dim state after every mount (here: no mirror up, a foreign app owns the screen), which is what lays its resting look down;
        // every test starts from the coin at rest.
        overlay.setDimmed(false)
        advance(1_000)
    }

    private fun root(): View =
        checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView")) { "fixture: a view is mounted" }

    private fun layoutParams(): WindowManager.LayoutParams =
        checkNotNull(root().layoutParams as? WindowManager.LayoutParams) { "fixture: the mounted root carries window layout params" }

    private fun flags(): Int = layoutParams().flags

    private fun position(): Pair<Int, Int> = layoutParams().let { it.x to it.y }

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
