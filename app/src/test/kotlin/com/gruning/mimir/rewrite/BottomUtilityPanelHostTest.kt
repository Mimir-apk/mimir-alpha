package com.gruning.mimir.rewrite

import android.app.Application
import android.view.View
import androidx.compose.runtime.Composable
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * the Bottom Utility window's life, through the real [BottomUtilityPanelHost] on a lending overlay and a scripted Utility model.
 * The window is never attached to a display here, so its content does not compose (that is `BottomUtilityWindowRenderTest`'s); what is
 * proven is the contract around it: one window at a time, the model opened and closed with it, and nothing left open on the Bottom for
 * the Presentation to draw when the window goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class BottomUtilityPanelHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class ScriptedModel(var accepts: Boolean = true) : BottomUtilityModel {
        var open: UtilitySurface? = null
        var shows = 0
        var dismisses = 0
        val requested = mutableListOf<UtilitySurface>()

        override val shownSurface: UtilitySurface? get() = open

        override fun showOnBottom(surface: UtilitySurface): Boolean {
            shows++
            requested += surface
            open = if (accepts) surface else null
            return accepts
        }

        override fun dismissFromBottom() {
            dismisses++
            open = null
        }

        @Composable
        override fun Panel(presentationToken: UUID) = Unit
    }

    private fun host(overlay: LendingOverlay, model: ScriptedModel?, gameRunning: Boolean = false): BottomUtilityPanelHost =
        BottomUtilityPanelHost(
            context = RuntimeEnvironment.getApplication(),
            overlay = overlay,
            interactionMotionGate = DefaultInteractionMotionGate(scope = scope, clock = { 0L }),
            model = { model },
            gameSessionActive = { gameRunning },
        )

    @Test
    fun `with no Utility model bound it refuses by name and lends nothing`() {
        val overlay = LendingOverlay()

        val result = host(overlay, model = null).open()

        assertFalse(result.opened)
        assertEquals(BottomUtilityHostUnbound, result.refusal)
        assertEquals(0, overlay.attached)
    }

    @Test
    fun `opening lends one window, opens the model on the Bottom and covers the Presentation's instruments layer`() {
        val overlay = LendingOverlay()
        val model = ScriptedModel()
        val host = host(overlay, model)
        assertFalse(host.covering.value)

        val result = host.open()

        assertTrue(result.opened)
        assertNull(result.refusal)
        assertEquals("one window lent", 1, overlay.attached)
        assertEquals("the model was opened on the Bottom", UtilitySurface.UTILITY, model.shownSurface)
        assertTrue("the window carries the panel", host.covering.value)
    }

    @Test
    fun `a second press while the window is up reselects Utility in the same window`() {
        val overlay = LendingOverlay()
        val model = ScriptedModel()
        val host = host(overlay, model)
        assertTrue(host.open().opened)

        assertTrue(host.open().opened)

        assertEquals("no second window", 1, overlay.attached)
        assertEquals("the model was asked again", 2, model.shows)
        assertTrue(host.covering.value)
    }

    @Test
    fun `hiding removes the window, uncovers the Presentation and is idempotent`() {
        val overlay = LendingOverlay()
        val host = host(overlay, ScriptedModel())
        assertTrue(host.open().opened)

        host.hide()
        host.hide()

        assertEquals("the lease was detached once", 1, overlay.detached)
        assertFalse(host.covering.value)
    }

    @Test
    fun `a window another panel replaces takes the open model down with it`() {
        val overlay = LendingOverlay()
        val model = ScriptedModel()
        val host = host(overlay, model)
        assertTrue(host.open().opened)

        overlay.revoke(PanelRevocationReason.REPLACED)

        assertFalse("the window no longer covers", host.covering.value)
        assertEquals("the model is closed with the window, not left open for the Presentation to draw", 1, model.dismisses)
        assertNull(model.shownSurface)
        assertEquals("a revoked lease is not detached again", 0, overlay.detached)
    }

    @Test
    fun `losing the display or the permission does the same`() {
        for (reason in listOf(PanelRevocationReason.DISPLAY_LOST, PanelRevocationReason.PERMISSION_LOST, PanelRevocationReason.RETARGETED)) {
            val overlay = LendingOverlay()
            val model = ScriptedModel()
            val host = host(overlay, model)
            assertTrue(host.open().opened)

            overlay.revoke(reason)

            assertFalse(reason.name, host.covering.value)
            assertEquals(reason.name, 1, model.dismisses)
        }
    }

    @Test
    fun `a refused attach over a game says why and leaves nothing open on the Bottom`() {
        val overlay = LendingOverlay(reject = PanelRejectReason.OVERLAY_PERMISSION_MISSING)
        val model = ScriptedModel()
        val host = host(overlay, model, gameRunning = true)

        val result = host.open()

        assertFalse(result.opened)
        assertEquals("overlay_permission_missing", result.refusal)
        assertEquals("the model that was opened for it is closed again", 1, model.dismisses)
        assertNull(model.shownSurface)
        assertFalse(host.covering.value)
    }

    @Test
    fun `a refused attach with no game running is carried by the Presentation, never by Main`() {
        val overlay = LendingOverlay(reject = PanelRejectReason.OVERLAY_PERMISSION_MISSING)
        val model = ScriptedModel()
        val host = host(overlay, model, gameRunning = false)

        val result = host.open()

        assertTrue("the press is answered: the Presentation draws the model open on the Bottom, as it did before the window", result.opened)
        assertEquals(PresentationFallbackPrefix + "overlay_permission_missing", result.refusal)
        assertEquals("the model stays open on the Bottom for the Presentation to draw", UtilitySurface.UTILITY, model.shownSurface)
        assertEquals("and was not closed behind it", 0, model.dismisses)
        assertFalse("no window carries it, so the Presentation's instruments layer does", host.covering.value)
        assertEquals(0, overlay.attached)
    }

    @Test
    fun `the Tweaker opens in the same window as Utility, and a second window never exists`() {
        val overlay = LendingOverlay()
        val model = ScriptedModel()
        val host = host(overlay, model)

        assertTrue(host.open(UtilitySurface.TWEAKER).opened)
        assertEquals("the Tweaker was asked for directly", listOf(UtilitySurface.TWEAKER), model.requested)
        assertEquals(UtilitySurface.TWEAKER, model.shownSurface)
        assertEquals(1, overlay.attached)

        assertTrue("the Bottom menu's Utility while it is up", host.open().opened)
        assertTrue("the shelf's seat again", host.open(UtilitySurface.TWEAKER).opened)

        assertEquals("one window for Utility, the Tweaker and every press between", 1, overlay.attached)
        assertEquals(listOf(UtilitySurface.TWEAKER, UtilitySurface.UTILITY, UtilitySurface.TWEAKER), model.requested)
        assertTrue(host.covering.value)
    }

    @Test
    fun `a panel lent to anything else takes the Tweaker window down with its model`() {
        val overlay = LendingOverlay()
        val model = ScriptedModel()
        val host = host(overlay, model)
        assertTrue(host.open(UtilitySurface.TWEAKER).opened)

        // The Tabs drawer's window or Quick Launch's is lent by the same overlay controller: an attach revokes the panel before it.
        overlay.revoke(PanelRevocationReason.REPLACED)

        assertFalse(host.covering.value)
        assertNull("two overlays never stand at once: the model closed with its window", model.shownSurface)
    }

    @Test
    fun `a model that declines opens no window`() {
        val overlay = LendingOverlay()
        val host = host(overlay, ScriptedModel(accepts = false))

        val result = host.open()

        assertFalse(result.opened)
        assertEquals("model_declined", result.refusal)
        assertEquals(0, overlay.attached)
        assertFalse(host.covering.value)
    }

    /** Lends every Bottom panel it is asked for, or refuses them all, and lets a test revoke the one it lent. */
    private class LendingOverlay(private val reject: PanelRejectReason? = null) : CompanionOverlayController {
        var attached = 0
            private set
        var detached = 0
            private set
        private var lentId: PanelLeaseId? = null
        private var onRevoked: ((PanelLeaseId, PanelRevocationReason) -> Unit)? = null

        fun revoke(reason: PanelRevocationReason) {
            val id = checkNotNull(lentId) { "no panel is lent" }
            val callback = checkNotNull(onRevoked) { "no revocation callback" }
            lentId = null
            callback(id, reason)
        }

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
            reject?.let { return PanelAttachResult.Rejected(it) }
            attached++
            val issued = PanelLeaseId(UUID.randomUUID())
            lentId = issued
            this.onRevoked = onRevoked
            return PanelAttachResult.Attached(
                object : CompanionPanelLease {
                    override val id: PanelLeaseId = issued

                    override fun detach(): Boolean {
                        detached++
                        return true
                    }
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
