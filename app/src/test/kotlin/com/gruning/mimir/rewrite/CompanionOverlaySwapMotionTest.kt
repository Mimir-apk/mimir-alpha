package com.gruning.mimir.rewrite

import android.app.Application
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
 * Thor Bottom revamp B2/N8, on the REAL [AndroidCompanionOverlayController] under Robolectric: the ᛗ menu's swap on the swap tier.
 *
 * A long press swaps the button's root for the menu's, in one native window. The outgoing root fades with no scale and, at the
 * hand-over (130 ms, alpha about 0.044), is removed and the replacement is added: at alpha 0, 8 dp below its seat when it is the
 * Bottom's menu (the button, which fills its window, and Main's menu, whose window is the size of its card, only fade, as a rise would
 * clip them), with no scale. It then fades in and rises into place.
 *
 * What is read here is what Robolectric can say: the replacement's state at the moment it is ADDED (a recording WindowManager sees
 * it before the first frame), the outgoing root's alpha at the moment it was handed over (still just a trace, never the mid-fade 0.5
 * the old 90 ms beat had), that exactly one root is added, and that every view ends seated. The paused looper cannot hold an
 * animator at a given millisecond (the first frame lands the animation about where it lands on a device, then it finishes), so the
 * 180 / 130 / 8 dp values are pinned in `CompanionOverlaySwapBeatTest` and `CompanionOverlaySwapSourcePinTest`, and the receipt's
 * 310 ms is the device's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionOverlaySwapMotionTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private var bottomDisplayId = -1

    /** What the window manager was handed: the view, and what it looked like at that instant. */
    private class Added(val view: View, val alpha: Float, val translationY: Float, val scaleX: Float, val scaleY: Float)

    private val added = mutableListOf<Added>()
    private val removed = mutableListOf<View>()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        CompanionState.set(CompanionPayload.idle())
        resetOverlaySingleton()
        ShadowSettings.setCanDrawOverlays(true)
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        added.clear()
        removed.clear()
        idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        resetOverlaySingleton()
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `opening the menu adds it at alpha 0, 8 dp low with no scale, removes the button nearly gone, and seats it`() {
        val overlay = startCompanionOverlay(app, scope)
        mountOnBottom(overlay)
        val button = overlay.root()
        record(overlay)

        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1_000)

        val menu = overlay.root()
        assertNotSame("the swap replaced the root", button, menu)
        assertTrue("the menu is open", overlay.quickMenuOpen.value)
        val addedMenu = added.single { it.view === menu }
        val dp8 = MimirMotion.SwapTravelDp * app.resources.displayMetrics.density
        assertEquals("added invisible", 0f, addedMenu.alpha, 0f)
        assertEquals("8 dp below its seat", dp8, addedMenu.translationY, 0.01f)
        assertEquals("no scale on the way in", 1f, addedMenu.scaleX, 0f)
        assertEquals(1f, addedMenu.scaleY, 0f)

        assertTrue("the outgoing root was removed from the window", removed.any { it === button })
        assertFalse("and is gone", button.isAttachedToWindow)
        assertTrue(
            "handed over when it was a trace (0.044 on the house curve), not mid-fade: ${button.alpha}",
            button.alpha in 0f..0.12f,
        )
        assertEquals("the outgoing root never scaled", 1f, button.scaleX, 0f)
        assertEquals(1f, button.scaleY, 0f)

        assertEquals("seated and opaque", 1f, menu.alpha, 0f)
        assertEquals(0f, menu.translationY, 0f)
        assertEquals(1f, menu.scaleX, 0f)
        assertEquals(1f, menu.scaleY, 0f)
        assertFalse("the swap is over", overlay.quickMenuSwapInFlight.value)
        assertEquals("one root at a time: the menu is the only one added", 1, added.size)
    }

    @Test
    fun `closing the menu adds the button at alpha 0 in its seat, because it fills its window, with no scale`() {
        val overlay = startCompanionOverlay(app, scope)
        mountOnBottom(overlay)
        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1_000)
        val menu = overlay.root()
        assertTrue("fixture: the menu is open", overlay.quickMenuOpen.value)
        record(overlay)

        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "collapseMenu")
        advance(1_000)

        val button = overlay.root()
        assertNotSame(menu, button)
        assertFalse("closed", overlay.quickMenuOpen.value)
        val addedButton = added.single { it.view === button }
        assertEquals("added invisible", 0f, addedButton.alpha, 0f)
        assertEquals("a window-filling button never rises, which would clip it", 0f, addedButton.translationY, 0f)
        assertEquals(1f, addedButton.scaleX, 0f)
        assertEquals(1f, addedButton.scaleY, 0f)
        assertTrue("the menu was removed nearly gone: ${menu.alpha}", removed.any { it === menu } && menu.alpha in 0f..0.12f)
        assertEquals(0f, button.translationY, 0f)
        assertFalse(overlay.quickMenuSwapInFlight.value)
    }

    @Test
    fun `Main's single-display menu fades in place, because its window is the size of the card and a rise would clip its rim`() {
        val overlay = startCompanionOverlay(app, scope)
        overlay.targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY))
        idle()
        assertEquals("fixture: the ᛗ mounted on Main", CompanionOverlayRole.PRIMARY_SINGLE, overlay.activeTarget.value?.role)
        val button = overlay.root()
        record(overlay)

        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1_000)

        val menu = overlay.root()
        assertNotSame("the swap replaced the root", button, menu)
        assertTrue("the menu is open", overlay.quickMenuOpen.value)
        val addedMenu = added.single { it.view === menu }
        assertEquals("added invisible", 0f, addedMenu.alpha, 0f)
        assertEquals("no rise: the card-sized window would clip it", 0f, addedMenu.translationY, 0f)
        assertEquals("no scale", 1f, addedMenu.scaleX, 0f)
        assertEquals(1f, addedMenu.scaleY, 0f)
        assertTrue("the button was handed over as a trace: ${button.alpha}", removed.any { it === button } && button.alpha in 0f..0.12f)
        assertEquals("seated and opaque", 1f, menu.alpha, 0f)
        assertEquals(0f, menu.translationY, 0f)
        assertFalse(overlay.quickMenuSwapInFlight.value)
    }

    @Test
    fun `an outgoing root the window manager will not remove has its fade stopped and is put back opaque and live`() {
        listOf("open", "close").forEach { direction ->
            resetOverlaySingleton()
            added.clear()
            removed.clear()
            val overlay = startCompanionOverlay(app, scope)
            mountOnBottom(overlay)
            if (direction == "close") {
                ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
                advance(1_000)
            }
            val stuck = overlay.root()
            record(overlay, failRemoval = true)

            ReflectionHelpers.callInstanceMethod<Unit>(overlay, if (direction == "open") "showMenu" else "collapseMenu")
            // Past the 130 ms hand-over and the end of the 180 ms fade, then a long way beyond it.
            advance(400)
            val settled = stuck.alpha
            val where = "$direction: the root the swap could not remove"
            assertTrue("$where is still the mounted root", overlay.root() === stuck)
            assertTrue("$where is back at a visible alpha, not left fading: $settled", settled >= 0.5f)
            assertTrue("$where takes touches again", stuck.isEnabled && stuck.isClickable)
            assertEquals("$where never scaled", 1f, stuck.scaleX, 0f)
            // A fade left running would keep driving it to 0 on the frames that follow.
            repeat(10) {
                advance(100)
                assertEquals("$where holds its alpha, no animator is still driving it", settled, stuck.alpha, 0f)
            }
            assertFalse("$where: the swap is over", overlay.quickMenuSwapInFlight.value)
            assertEquals("$where: nothing was added behind it", 0, added.size)
        }
    }

    @Test
    fun `a swap cancelled right after it starts leaves the view seated, not offset or scaled`() {
        val overlay = startCompanionOverlay(app, scope)
        mountOnBottom(overlay)
        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1)
        val root = overlay.root()
        // A newer request cancels whatever the swap still has in flight.
        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "cancelOverlayTransition")
        assertEquals(0f, root.translationY, 0f)
        assertEquals(1f, root.scaleX, 0f)
        assertEquals(1f, root.scaleY, 0f)
        assertFalse(overlay.quickMenuSwapInFlight.value)
    }

    /** Puts a recording window manager in front of the controller's real one, so each add and remove is seen as it happens. */
    private fun record(overlay: AndroidCompanionOverlayController, failRemoval: Boolean = false) {
        val real = ReflectionHelpers.getField<WindowManager>(overlay, "windowManager")
        val proxy = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
            val view = args?.firstOrNull() as? View
            when (method.name) {
                "addView" -> if (view != null) added += Added(view, view.alpha, view.translationY, view.scaleX, view.scaleY)
                "removeView", "removeViewImmediate" -> {
                    // A window manager that cannot take the outgoing root away: the root stays attached.
                    if (failRemoval) throw IllegalStateException("fake: the window manager would not remove the view")
                    if (view != null) removed += view
                }
            }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as WindowManager
        ReflectionHelpers.setField(overlay, "windowManager", proxy)
    }

    private fun mountOnBottom(overlay: AndroidCompanionOverlayController) {
        val target = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId)
        overlay.targetOverlay(target)
        idle()
        assertEquals("fixture: the ᛗ mounted on the Bottom", target, overlay.activeTarget.value)
        assertTrue("fixture: the mounted root is a view group", overlay.root() is ViewGroup)
    }

    private fun AndroidCompanionOverlayController.root(): View =
        checkNotNull(ReflectionHelpers.getField<View?>(this, "overlayView")) { "fixture: a view is mounted" }

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
