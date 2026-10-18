package com.gruning.mimir.rewrite

import android.app.Application
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.companionnative.CompanionPanelLease
import com.gruning.mimir.rewrite.core.companionnative.NativeOverlayInteractionObserver
import com.gruning.mimir.rewrite.core.companionnative.PanelAttachResult
import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import com.gruning.mimir.rewrite.core.companionnative.PanelRejectReason
import com.gruning.mimir.rewrite.core.companionnative.PanelRevocationReason
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Through the real host: a touch in the Bottom's Quick launch window holds the ambient-motion gate's lease for as long as the finger is down,
 * and the window appearing is a wake and holds nothing. The views are the ones [BottomQuickLaunchPanelHost.open] builds.
 *
 * Not proven here: which device touches reach that lease. It is taken in the ComposeView's own touch listener, which
 * Android calls only for a touch the view's Compose content does not take (the Tabs drawer host says the same of its twin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class BottomQuickLaunchPanelMotionLeaseTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a touch in the Bottom Quick launch window holds the motion lease, and opening it does not`() {
        val gate = DefaultInteractionMotionGate(scope = scope, clock = { 0L })
        val overlay = LendingOverlayController()
        val host = BottomQuickLaunchPanelHost(RuntimeEnvironment.getApplication(), overlay, gate)
        gate.pulse(InteractionSource.CONTROLLER, InteractionRoot.PRIMARY)

        assertTrue("the window is lent", host.open().opened)
        val window = checkNotNull(overlay.lent) { "open() attached no panel" }
        assertEquals("the window appearing is a wake, not a touch", AmbientMotionPhase.RECENT, gate.phase.value)

        // R6-5E-04: routed through the root, not straight to the ComposeView -- the window is never attached, so its
        // content never composes (measuring it would ask for a window recomposer), and the root's own dispatchTouchEvent
        // takes the DOWN before any child could, as it does for a touch nothing consumed.
        val composeView = (window as ViewGroup).getChildAt(0)
        assertTrue("the host's window wraps its ComposeView", composeView is ComposeView)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        try {
            window.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }
        assertEquals("the window's touch lease is held", AmbientMotionPhase.INTERACTING, gate.phase.value)

        host.hide()
        assertEquals("hiding releases the window's gesture", AmbientMotionPhase.RECENT, gate.phase.value)
    }

    @Test
    fun `a touch a child of the Bottom Quick launch window consumes still holds the motion lease`() {
        val gate = DefaultInteractionMotionGate(scope = scope, clock = { 0L })
        val overlay = LendingOverlayController()
        val host = BottomQuickLaunchPanelHost(RuntimeEnvironment.getApplication(), overlay, gate)
        gate.pulse(InteractionSource.CONTROLLER, InteractionRoot.PRIMARY)
        assertTrue("the window is lent", host.open().opened)
        val window = checkNotNull(overlay.lent) as ViewGroup
        // Stands in for a Compose child that takes the DOWN: laid over the whole window, above the ComposeView, consuming
        // every touch, so the ComposeView's own listener is never called -- as on the device.
        val consumer = View(window.context).apply { setOnTouchListener { _, _ -> true } }
        window.addView(consumer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window.layout(0, 0, 800, 600)   // children keep their unmeasured zero size; the ComposeView never composes
        consumer.layout(0, 0, 800, 600)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        try {
            assertTrue("the stand-in child consumed the DOWN", window.dispatchTouchEvent(down))
        } finally {
            down.recycle()
        }
        assertEquals("the root held the lease before its child consumed", AmbientMotionPhase.INTERACTING, gate.phase.value)
    }

    /** Lends every Bottom panel it is asked for and keeps the view it lent. */
    private class LendingOverlayController : CompanionOverlayController {
        var lent: View? = null
            private set

        override fun targetOverlay(target: CompanionOverlayTarget) = Unit
        override fun dismiss() = Unit
        override fun setVisible(visible: Boolean) = Unit
        override fun setPrimaryIdleHidden(hidden: Boolean) = Unit
        override fun setPrimaryIdleInteractionObserver(observer: (() -> Unit)?) = Unit
        override val activeTarget: StateFlow<CompanionOverlayTarget?> = MutableStateFlow(null)
        override val quickMenuOpen: StateFlow<Boolean> = MutableStateFlow(false)
        override fun setOverlayTheme(theme: CompanionOverlayTheme) = Unit
        override fun setDimmed(dimmed: Boolean) = Unit
        override val overlayPermissionMissing: StateFlow<Boolean> = MutableStateFlow(false)
        override val actions: SharedFlow<CompanionOverlayAction> = MutableSharedFlow()
        override fun completeQuickMenu(requestId: UUID, accepted: Boolean) = Unit
        override fun hasOverlayPermission(): Boolean = true
        override fun requestOverlayPermission() = Unit
        override fun reconcile() = Unit
        override fun setNativeOverlayInteractionObserver(observer: NativeOverlayInteractionObserver?) = Unit

        override fun attachSecondaryPanel(
            view: View,
            onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
        ): PanelAttachResult {
            lent = view
            return PanelAttachResult.Attached(
                object : CompanionPanelLease {
                    override val id: PanelLeaseId = PanelLeaseId(UUID.randomUUID())

                    override fun detach(): Boolean = true
                },
            )
        }

        override fun attachPrimaryPanel(
            view: View,
            onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
        ): PanelAttachResult = PanelAttachResult.Rejected(PanelRejectReason.DISPLAY_UNAVAILABLE)

        override fun setPrimaryPanelBrightness(fraction: Float?) = Unit
    }
}
