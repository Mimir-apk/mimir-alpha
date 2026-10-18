package com.gruning.mimir.rewrite

import android.os.Looper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySnapshot
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadHost
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The full-screen Bottom trackpad's window lifecycle: it opens, and it stays until Close, the
 * screen going off or the service being disabled. The surface and the activation are fakes, so every rule here is the
 * window object's own; the real `WindowManager.addView` on the Bottom is a Thor proof item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirTrackpadPadWindowTest {

    private class FakeSurface(var accepts: Boolean = true) : PadSurface {
        var mounted = false
        var mounts = 0
        var unmounts = 0
        override val isMounted: Boolean get() = mounted
        override fun mount(content: @Composable () -> Unit): Boolean {
            if (!accepts) return false
            mounted = true
            mounts++
            return true
        }

        override fun unmount() {
            if (mounted) unmounts++
            mounted = false
        }
    }

    /** One activation's handle: counts what reached it. */
    private class FakeActivation : MimirTrackpadHost {
        var ended = 0
        var clicks = 0
        var heldReleased = 0
        var holding = false
        override val serviceConnected = true
        override fun begin(): MimirTrackpadHost = this
        override fun pointerMove(dx: Float, dy: Float) = Unit
        override fun click() { clicks++ }
        override fun doubleClick() = Unit
        override fun scrollBy(dx: Float, dy: Float) = Unit
        override fun scrollEnd() = Unit
        override fun scrollCancel() = Unit
        override fun holdBegin() { holding = true }
        override fun holdMove(dx: Float, dy: Float) = Unit
        override fun holdEnd() { holding = false }
        override fun openAccessibilitySettings() = Unit
        override fun end() {
            ended++
            // Ending releases a press that is down: the owed finger-up.
            if (holding) { holding = false; heldReleased++ }
        }
    }

    private val surface = FakeSurface()
    private val activations = mutableListOf<FakeActivation>()
    private var connected = true
    private var locked = false
    private val pad = MimirTrackpadPadWindow(
        begin = { FakeActivation().also { activations += it } },
        surface = surface,
        serviceConnected = { connected },
        locked = { locked },
    )

    // Long enough for the leak guard: with no frame clock behind the fake surface the pad never reports its own leave, so the window
    // goes on the guard ([padLeft] is the production road and has its own tests in MimirTrackpadOneClockTest).
    private fun idleAnimation() = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NativePanelExitBackstopMs + 100L))

    @Test
    fun openingMountsOneWindowAndBeginsOneActivation() {
        assertTrue(pad.open())
        assertTrue(pad.isOpen)
        assertEquals(1, surface.mounts)
        assertEquals(1, activations.size)
        assertTrue("opening again is a no-op", pad.open())
        assertEquals(1, surface.mounts)
        assertEquals(1, activations.size)
    }

    @Test
    fun withTheServiceOffNothingOpens() {
        connected = false
        assertFalse(pad.open())
        assertEquals(0, surface.mounts)
        assertTrue("no activation was started for a pad that cannot show", activations.isEmpty())
    }

    @Test
    fun aWindowThatCannotBeAddedShowsNothingAndEndsItsActivation() {
        surface.accepts = false
        assertFalse(pad.open())
        assertFalse(pad.isOpen)
        assertEquals("there is no fallback window type: the activation ends", 1, activations.single().ended)
    }

    @Test
    fun aClickThroughTheActivationNeverEndsThePad() {
        pad.open()
        val activation = activations.single()
        activation.click()
        activation.click()
        assertEquals(2, activation.clicks)
        assertEquals(0, activation.ended)
        assertTrue(pad.isOpen)
        idleAnimation()
        assertTrue("still mounted after the animation clocks run", surface.mounted)
    }

    @Test
    fun theKeyboardBeingToldAFieldIsWaitingNeverEndsThePad() {
        pad.open()
        MimirTrackpadRuntime.setFieldWaiting(true)
        MimirTrackpadRuntime.setFieldWaiting(false)
        idleAnimation()
        assertTrue(pad.isOpen)
        assertEquals(0, activations.single().ended)
    }

    @Test
    fun closeEndsTheActivationReleasingAHeldPressThenRemovesTheWindowAfterItsExit() {
        pad.open()
        val activation = activations.single()
        activation.holdBegin()
        pad.close()
        assertEquals(1, activation.ended)
        assertEquals("the held press is released by the end", 1, activation.heldReleased)
        assertFalse("closing: no longer open", pad.isOpen)
        assertTrue("the exit plays before the window goes", surface.mounted)
        idleAnimation()
        assertFalse(surface.mounted)
        assertEquals(1, surface.unmounts)
    }

    @Test
    fun closingTwiceEndsOnce() {
        pad.open()
        pad.close()
        pad.close()
        idleAnimation()
        assertEquals(1, activations.single().ended)
        assertEquals(1, surface.unmounts)
    }

    @Test
    fun killNowForScreenOffOrServiceDisableRemovesTheWindowAtOnce() {
        pad.open()
        pad.killNow()
        assertFalse(surface.mounted)
        assertFalse(pad.isOpen)
        assertEquals("the activation was ended by the host that called killNow", 0, activations.single().ended)
    }

    @Test
    fun aReopenAfterCloseStartsACleanNewActivation() {
        pad.open()
        pad.close()
        idleAnimation()
        assertTrue(pad.open())
        assertEquals(2, activations.size)
        assertEquals("the old handle is not the new one", 1, activations[0].ended)
        assertEquals(0, activations[1].ended)
        assertTrue(surface.mounted)
    }

    @Test
    fun aStaleHandleEndingAfterReopenCannotTouchTheNewPad() {
        pad.open()
        val first = activations.single()
        pad.close()
        idleAnimation()
        pad.open()
        first.end() // a late callback from the first activation
        assertTrue(pad.isOpen)
        assertEquals(0, activations[1].ended)
    }

    // ---- a lock ends the pad even while the screen stays on (pad1 fix round) -------------------------------------

    @Test
    fun aPadIsNeverOpenedWhileTheDeviceIsLocked() {
        locked = true
        assertFalse(pad.open())
        assertEquals(0, surface.mounts)
        assertTrue("no activation for a pad that cannot show", activations.isEmpty())
    }

    @Test
    fun theKeyguardComingUpOverAnInteractiveScreenEndsThePadAndReleasesAHeldPress() {
        pad.open()
        val activation = activations.single()
        activation.holdBegin()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500))
        assertTrue("an unlocked device keeps its pad across the polls", surface.mounted)
        locked = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertFalse("the window is gone, with no exit animation left above the keyguard", surface.mounted)
        assertFalse(pad.isOpen)
        assertEquals(1, activation.ended)
        assertEquals("the owed release went", 1, activation.heldReleased)
    }

    @Test
    fun aKeyguardReadThatFailsCountsAsLockedAndTheGuardedPadIsRefusedAndEnded() {
        assertTrue("a failed read fails CLOSED", keyguardLockedOrClosed { error("no keyguard service") })
        assertFalse(keyguardLockedOrClosed { false })
        assertTrue(keyguardLockedOrClosed { true })
        val guardedPad = MimirTrackpadPadWindow(
            begin = { FakeActivation().also { activations += it } },
            surface = FakeSurface().also { it.mounted = false },
            serviceConnected = { true },
            locked = { keyguardLockedOrClosed { error("read failed") } },
        )
        assertFalse("refused to open when the read fails", guardedPad.open())
        assertTrue(activations.isEmpty())
    }

    @Test
    fun aKeyguardReadThatBeginsToFailEndsAnOpenPad() {
        var failing = false
        val localSurface = FakeSurface()
        val localPad = MimirTrackpadPadWindow(
            begin = { FakeActivation().also { activations += it } },
            surface = localSurface,
            serviceConnected = { true },
            locked = { keyguardLockedOrClosed { if (failing) error("read failed") else false } },
        )
        assertTrue(localPad.open())
        failing = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertFalse(localSurface.mounted)
        assertEquals(1, activations.single().ended)
    }

    @Test
    fun theHostGivesTheInjectorTheGestureZones() {
        val service = source("MimirTrackpadAccessibilityService.kt")
        assertTrue(service.contains("gestureMargins = { gestureZones }"))
        assertTrue("zones are read again at every activation", service.contains("gestureZones = readGestureZones()"))
        assertTrue(service.contains("WindowInsets.Type.systemGestures()"))
    }

    @Test
    fun afterALockTheNextOpenIsFreshOnceUnlocked() {
        pad.open()
        locked = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        locked = false
        assertTrue(pad.open())
        assertEquals(2, activations.size)
        assertEquals(0, activations[1].ended)
    }

    @Test
    fun theHostGivesTheInjectorTheKeyguardAndTheKeyguardIsReadFromKeyguardManager() {
        val service = source("MimirTrackpadAccessibilityService.kt")
        assertTrue(service.contains("keyguardLocked = { trackpadKeyguardLocked(appContext) }"))
        val windows = source("MimirTrackpadWindows.kt")
        assertTrue(windows.contains("isKeyguardLocked"))
        assertTrue("the pad window is built with the lock check", windows.contains("locked = { trackpadKeyguardLocked(appContext) }"))
    }

    // ---- window params ---------------------------------------------------------------------------------------------

    @Test
    fun theWindowIsFullSizeNotFocusableAndTheTypeIsWhatItWasGiven() {
        val params = trackpadPadWindowParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 1080, 936)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertEquals(1080, params.width)
        assertEquals(936, params.height)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertFalse("the pad takes touches", params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test
    fun theDisplayIsTheOneTheCompanionSelectedNeverAHardCodedId() {
        val selected = SecondaryDisplaySnapshot(eligibleIds = listOf(7, 9), selectedId = 9, soleEligibleId = null)
        assertEquals(9, trackpadPadDisplayId(selected))
        val none = SecondaryDisplaySnapshot(eligibleIds = emptyList(), selectedId = null, soleEligibleId = null)
        assertNull(trackpadPadDisplayId(none))
    }

    // ---- the Bottom menu's Trackpad row ----------------------------------------------------------------------------

    @Test
    fun theMenuRowOpensThePadWithTheServiceOnAndTheConsentCardWithItOff() {
        assertEquals(TrackpadMenuAction.OPEN_PAD, trackpadMenuAction(capable = true, serviceConnected = true))
        assertEquals(TrackpadMenuAction.SHOW_CONSENT, trackpadMenuAction(capable = true, serviceConnected = false))
        assertEquals(TrackpadMenuAction.UNSUPPORTED, trackpadMenuAction(capable = false, serviceConnected = true))
        assertEquals(TrackpadMenuAction.UNSUPPORTED, trackpadMenuAction(capable = false, serviceConnected = false))
    }

    @Test
    fun onlyTheBottomMenuAnswersTheTrackpadRow() {
        assertTrue(bottomTrackpadRoute(CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.TRACKPAD))
        assertFalse(bottomTrackpadRoute(CompanionOverlayRole.PRIMARY_SINGLE, QuickMenuItem.TRACKPAD))
        assertFalse(bottomTrackpadRoute(CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.QUICK_LAUNCH))
        assertEquals(PrimaryMButtonDispatch.REJECT, primaryMButtonDispatch(QuickMenuItem.TRACKPAD))
        assertNull("never a global command", globalMButtonCommand(QuickMenuItem.TRACKPAD))
        assertFalse(secondaryQuickMenuFallbackAcceptance(QuickMenuItem.TRACKPAD))
    }

    // ---- source pins: what is wired where --------------------------------------------------------------------------

    private fun source(name: String): String {
        val candidates = listOf(
            "src/main/kotlin/com/gruning/mimir/rewrite/$name",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/$name",
        )
        return candidates.map(::File).first { it.isFile }.readText()
    }

    @Test
    fun theHostEndsThePadWindowOnScreenOffAndServiceGoneButAnInterruptOnlyReleasesAPress() {
        val service = source("MimirTrackpadAccessibilityService.kt")
        val end = service.substringAfter("override fun end() {").substringBefore("private fun teardown()")
        assertTrue("host end() takes the window with it", end.contains("padWindow.killNow()"))
        val interrupted = service.substringAfter("fun onServiceInterrupted()").substringBefore("private val fieldWaitingState")
        assertTrue(interrupted.contains("releaseHeldPress()"))
        assertFalse("an interrupt is not a close", interrupted.contains("end()"))
        val gone = service.substringAfter("fun onServiceGone(").substringBefore("/**")
        assertTrue("service disable ends the activation", gone.contains("host?.end()"))
    }

    @Test
    fun thePadHasNoFallbackWindowTypeAndNeverReadsTheScreen() {
        val windows = source("MimirTrackpadWindows.kt")
        val pad = windows.substringAfter("internal class MimirTrackpadPadWindow(").substringBefore("internal class MimirTrackpadConsentWindow(")
        assertTrue(pad.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse("the pad has no application-overlay fallback", pad.contains("TYPE_APPLICATION_OVERLAY"))
        listOf("rootInActiveWindow", "AccessibilityNodeInfo", "getWindows", "takeScreenshot", "performGlobalAction").forEach {
            assertFalse("the pad window must not use $it", windows.contains(it))
        }
    }

    @Test
    fun theKeyboardNoLongerEndsThePadAnywhere() {
        val ime = source("MimirInputMethodService.kt")
        assertFalse(ime.contains("endTrackpad"))
        assertFalse("the IME never calls end() on the host", ime.contains("trackpadHost?.end()"))
        val keyboard = File(
            listOf(
                "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt",
                "../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt",
            ).first { File(it).isFile },
        ).readText()
        assertTrue("the PAD key opens the pad window", keyboard.contains("trackpad.openPad()"))
    }
}
