package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.companionnative.NativeOverlayInteractionObserver
import com.gruning.mimir.rewrite.core.companionnative.PanelAttachResult
import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import com.gruning.mimir.rewrite.core.companionnative.PanelRejectReason
import com.gruning.mimir.rewrite.core.companionnative.PanelRevocationReason
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySelector
import java.time.Duration
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog

/**
 * "Stay visible over apps": the Bottom ᛗ never idle-hides while an app covers the
 * Bottom, so tapping it cannot touch the app. It can still hide over Mimir's own shelf.
 *
 * Behaviour tests. The real [MButtonTopologyController] runs under Robolectric against a real [SecondaryDisplaySelector]
 * on a Robolectric presentation display, and a real [CompanionRuntimeCoordinator], installed as the process runtime, is
 * walked through the transitions the Thor logs for a Bottom launch (LaunchRequested, HostStarted, TargetDispatched,
 * HostCovered) and for the return to the shelf (HostReturned, PresentationShown). The runtime's deadlines are held, so
 * a 30 s idle wait cannot walk it into RecoveryNeeded behind the test's back.
 *
 * Only the overlay window is a recording fake, so these tests see the hide and reveal the controller REQUESTS. Whether
 * the window then shrinks to one pixel or keeps its 129 x 129 frame is device evidence, and nothing here claims it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class MButtonBottomForeignAppCoverTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var policyBefore: MButtonVisibilityPolicy
    private val teardown = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        ShadowDisplayManager.reset()
        ShadowLog.clear()
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        MButtonSettingsStore.initialize(context)
        policyBefore = MButtonSettingsStore.visibilityPolicy.value
        // The Thor's stored policy in the QA passes (`mimir_m_button.xml`).
        MButtonSettingsStore.setVisibilityPolicy(context, MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS)
    }

    @After
    fun tearDown() {
        scope.cancel()
        teardown.asReversed().forEach { it() }
        teardown.clear()
        CompanionReturnCoordinator.resetTestOverrides()
        MButtonSettingsStore.setVisibilityPolicy(context, policyBefore)
        ShadowDisplayManager.reset()
    }

    @Test
    fun `the fixture waits are sized for the 30 second policy`() {
        // Every wait below is written as a literal (29 s, 31 s) rather than derived from the policy, so a changed
        // constant cannot quietly move the fixture with it.
        assertEquals(30_000L, MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS.timeoutMillis)
    }

    @Test
    fun `an app covering the Bottom keeps the Bottom M visible past the idle timeout`() {
        val bottom = bottomShowingShelf()
        bottom.appCoversBottom()
        val requestsBefore = bottom.overlay.idleHiddenRequests.size

        advanceSeconds(31)

        assertFalse(
            "the Bottom M was asked to hide while an app covered the Bottom: ${bottom.overlay.idleHiddenRequests}",
            bottom.overlay.idleHiddenRequests.drop(requestsBefore).any { hidden -> hidden },
        )

        // A touch on the app reaches the M only as the ACTION_OUTSIDE copy, which re-arms the policy. It still never hides.
        requireNotNull(bottom.overlay.idleInteractionObserver).invoke()
        idleMain()
        advanceSeconds(61)

        assertFalse(
            "a re-armed deadline must not hide it over the app either: ${bottom.overlay.idleHiddenRequests}",
            bottom.overlay.idleHiddenRequests.drop(requestsBefore).any { hidden -> hidden },
        )
        assertFalse(bottom.overlay.hidden)
    }

    @Test
    fun `a Bottom M already hidden over the shelf comes back when an app arrives with no touch`() {
        val bottom = bottomShowingShelf()
        advanceSeconds(31)
        assertTrue("precondition: over the shelf the Bottom M idle-hides", bottom.overlay.hidden)

        bottom.appCoversBottom()

        assertFalse(
            "the app arriving on the Bottom must bring the hidden M back: ${bottom.overlay.idleHiddenRequests}",
            bottom.overlay.hidden,
        )
        advanceSeconds(31)
        assertFalse("and it stays up over the app", bottom.overlay.hidden)
    }

    @Test
    fun `over Mimir's own shelf the Bottom M idle-hides on the user's timeout as before`() {
        val bottom = bottomShowingShelf()

        advanceSeconds(29)
        assertFalse("not before the 30 s policy", bottom.overlay.hidden)
        advanceSeconds(2)
        assertTrue("the shelf keeps its idle hide", bottom.overlay.hidden)
    }

    @Test
    fun `when the app leaves the Bottom the shelf idle hide resumes from a fresh timeout`() {
        val bottom = bottomShowingShelf()
        val host = bottom.appCoversBottom()
        advanceSeconds(31)
        assertFalse("visible over the app", bottom.overlay.hidden)

        bottom.appLeavesBottom(host)

        advanceSeconds(29)
        assertFalse("the policy restarts when the shelf returns", bottom.overlay.hidden)
        advanceSeconds(2)
        assertTrue("and then it hides over the shelf as before", bottom.overlay.hidden)
    }

    @Test
    fun `the single-display M on Main is unaffected by Bottom cover evidence`() {
        // No presentation display: the topology selects PRIMARY_SINGLE on the default display (a single-screen device).
        // A dual-screen device never mounts a Main M at all (selectMButtonOverlayTarget), so this is the only Main M.
        val selector = SecondaryDisplaySelector(context).also { selector -> teardown += { selector.close() } }
        assertNull(selector.snapshot.value.selectedId)
        val runtimeDisplay = 7
        val (runtime, port) = runtimeShowingShelf(runtimeDisplay)
        val overlay = RecordingOverlay()
        startTopology(overlay, selector)
        assertEquals(CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY), overlay.target)
        val requestsBefore = overlay.idleHiddenRequests.size

        Harness(runtime, port, overlay, runtimeDisplay).appCoversBottom()

        assertEquals(
            "cover evidence must not reach the Main M",
            emptyList<Boolean>(),
            overlay.idleHiddenRequests.drop(requestsBefore),
        )
        advanceSeconds(31)
        assertTrue("the Main M still idle-hides on its timeout", overlay.hidden)
        assertTrue(
            "and Main's idle path logs nothing new: ${mButtonLogs()}",
            mButtonLogs().none { line -> line.startsWith("idle ") },
        )
    }

    @Test
    fun `Always visible keeps the Bottom M up over the shelf and over an app`() {
        MButtonSettingsStore.setVisibilityPolicy(context, MButtonVisibilityPolicy.ALWAYS_VISIBLE)
        val bottom = bottomShowingShelf()

        advanceSeconds(121)
        bottom.appCoversBottom()
        advanceSeconds(121)

        assertFalse(
            "Always visible never asks for a hide: ${bottom.overlay.idleHiddenRequests}",
            bottom.overlay.idleHiddenRequests.any { hidden -> hidden },
        )
    }

    @Test
    fun `the idle log line names the decision and its reason`() {
        val bottom = bottomShowingShelf()
        val deadline = "idle deadline role=SECONDARY_COMPANION display=${bottom.displayId} policy=HIDE_AFTER_30_SECONDS"

        advanceSeconds(31)
        // "requested", not "applied": the overlay can still decline a hide (menu open, a drag, no window).
        assertTrue(mButtonLogs().toString(), "$deadline hide=requested reason=none" in mButtonLogs())

        bottom.appCoversBottom()
        assertTrue(
            mButtonLogs().toString(),
            mButtonLogs().any { line ->
                line.startsWith(
                    "idle cover=foreign_app_on_bottom role=SECONDARY_COMPANION display=${bottom.displayId} ",
                )
            },
        )

        advanceSeconds(31)
        assertTrue(mButtonLogs().toString(), "$deadline hide=suppressed reason=foreign_app_on_bottom" in mButtonLogs())
    }

    @Test
    fun `only the runtime's Yielded disposition counts as an app covering the Bottom`() {
        val display = 4
        val origin = PrimaryReturnOrigin("home")
        val render = RenderAttempt(OperationId.create(), OperationId.create(), RendererOwnerId.create(), display)
        val host = HostLease(OperationId.create(), "com.google.android.youtube", display, "secondary_app", origin)
        val reclaim = ReclaimLease(
            operationId = OperationId.create(),
            parentHostOperationId = host.operationId,
            displayId = display,
            primaryOrigin = origin,
            reason = "overlay_edge_back",
            coveredHost = host,
        )

        assertTrue(mButtonBottomCoveredByForeignApp(SecondaryDisplayDisposition.Yielded(host, YieldEvidence.HOST_COVERED)))

        // Where HOST_COVERED evidence stops. None of these carries HOST_COVERED evidence, so each keeps today's idle hide, even
        // though an app can be in front in some of them (RecoveryNeeded after a dispatch or a destroyed host). Widening
        // this list changes the visibility policy beyond HOST_COVERED.
        listOf(
            SecondaryDisplayDisposition.NoDisplay,
            SecondaryDisplayDisposition.Presenting(render, PresentCause.DISPLAY_AVAILABLE),
            SecondaryDisplayDisposition.MirrorPresented(render),
            SecondaryDisplayDisposition.LaunchPending(host, LaunchPhase.TARGET_DISPATCHED),
            SecondaryDisplayDisposition.ReclaimPending(reclaim, ReclaimPhase.RENDER_REQUESTED, expectedRender = render),
            SecondaryDisplayDisposition.RecoveryNeeded(
                operationId = OperationId.create(),
                displayId = display,
                fallback = origin,
                failure = FailureReason(FailureCode.HOST_DESTROYED_WHILE_YIELDED, "return host destroyed"),
            ),
        ).forEach { disposition ->
            assertFalse("$disposition", mButtonBottomCoveredByForeignApp(disposition))
        }
    }

    private class Harness(
        val runtime: CompanionRuntimeCoordinator,
        val port: RecordingPort,
        val overlay: RecordingOverlay,
        val displayId: Int,
    )

    /** Dual screen: Mimir's shelf presented on the Bottom, the Bottom M mounted and its idle policy armed. */
    private fun bottomShowingShelf(): Harness {
        val displayId = addPresentationDisplay()
        val selector = SecondaryDisplaySelector(context).also { selector -> teardown += { selector.close() } }
        assertEquals("the presentation display must be the selected Bottom", displayId, selector.snapshot.value.selectedId)
        val (runtime, port) = runtimeShowingShelf(displayId)
        val overlay = RecordingOverlay()
        startTopology(overlay, selector)
        assertEquals(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, displayId), overlay.target)
        assertFalse("precondition: the Bottom M starts visible", overlay.hidden)
        return Harness(runtime, port, overlay, displayId)
    }

    /** DisplayAvailable then PresentationShown, installed as the process runtime the topology reads. */
    private fun runtimeShowingShelf(displayId: Int): Pair<CompanionRuntimeCoordinator, RecordingPort> {
        val runtime = CompanionRuntimeCoordinator(CompanionRuntimeScheduler { _, _, _ -> RuntimeCancellation {} })
        CompanionReturnCoordinator.installRuntime(runtime)
        val port = RecordingPort()
        assertTrue(runtime.bindRenderer(port))
        assertTrue(runtime.displayAvailable(displayId))
        port.acknowledgeLatestRender(runtime)
        assertTrue(runtime.snapshot.value.disposition is SecondaryDisplayDisposition.MirrorPresented)
        return runtime to port
    }

    private fun startTopology(overlay: RecordingOverlay, selector: SecondaryDisplaySelector) {
        MButtonTopologyController(
            context = context,
            overlay = overlay,
            secondaryDisplaySelector = selector,
            usageAccessGranted = { true },
            onboardingComplete = { true },
            scope = scope,
        )
        idleMain()
    }

    /** LaunchRequested, HostStarted, TargetDispatched, HostCovered: the order the Thor logs for a Bottom launch. */
    private fun Harness.appCoversBottom(packageName: String = "com.google.android.youtube"): HostLease {
        val host = requireNotNull(
            runtime.launchRequested(packageName, displayId, "secondary_app", PrimaryReturnOrigin("home")),
        )
        assertTrue(runtime.hostStarted(host.operationId))
        assertTrue(runtime.targetDispatched(host.operationId))
        assertTrue(runtime.hostCovered(host.operationId))
        assertTrue(runtime.snapshot.value.disposition is SecondaryDisplayDisposition.Yielded)
        idleMain()
        return host
    }

    /** HostReturned then PresentationShown: the app left and Mimir's shelf is back on the Bottom. */
    private fun Harness.appLeavesBottom(host: HostLease) {
        requireNotNull(runtime.hostReturned(host.operationId))
        port.acknowledgeLatestRender(runtime)
        assertTrue(runtime.snapshot.value.disposition is SecondaryDisplayDisposition.MirrorPresented)
        idleMain()
    }

    private fun addPresentationDisplay(): Int {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        // Same Robolectric 4.14.1 recipe as SecondaryDisplaySelectorRobolectricTest.
        val displayId = ShadowDisplayManager.addDisplay("+land", 2)
        Shadows.shadowOf(requireNotNull(displayManager.getDisplay(displayId))).setFlags(Display.FLAG_PRESENTATION)
        idleMain()
        return displayId
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advanceSeconds(seconds: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    private fun mButtonLogs(): List<String> = ShadowLog.getLogsForTag("MimirMButton").map { item -> item.msg }

    private class RecordingPort : CompanionHostPort {
        override val rendererOwnerId: RendererOwnerId = RendererOwnerId.create()
        val renders = mutableListOf<RenderAttempt>()

        fun acknowledgeLatestRender(runtime: CompanionRuntimeCoordinator) {
            val attempt = renders.last()
            assertTrue(runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
        }

        override fun render(attempt: RenderAttempt) {
            renders += attempt
        }

        override fun dismissPresentation() = Unit

        override fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long) = Unit

        override fun prepareForReclaim(lease: ReclaimLease) = Unit

        override fun launchRestoreGuard(lease: ReclaimLease): Boolean = true
    }

    /** Records what the topology asks of the overlay window; the last idle request is the state it asked for. */
    private class RecordingOverlay : CompanionOverlayController {
        val idleHiddenRequests = mutableListOf<Boolean>()
        val hidden: Boolean
            get() = idleHiddenRequests.lastOrNull() ?: false
        var target: CompanionOverlayTarget? = null
            private set
        var idleInteractionObserver: (() -> Unit)? = null
            private set
        private val targetFlow = MutableStateFlow<CompanionOverlayTarget?>(null)

        override fun targetOverlay(target: CompanionOverlayTarget) {
            this.target = target
            targetFlow.value = target
        }

        override fun dismiss() {
            target = null
            targetFlow.value = null
        }

        override fun setVisible(visible: Boolean) = Unit

        override fun setPrimaryIdleHidden(hidden: Boolean) {
            idleHiddenRequests += hidden
        }

        override fun setPrimaryIdleInteractionObserver(observer: (() -> Unit)?) {
            idleInteractionObserver = observer
        }

        override val activeTarget: StateFlow<CompanionOverlayTarget?> = targetFlow
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
            view: android.view.View,
            onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
        ): PanelAttachResult = PanelAttachResult.Rejected(PanelRejectReason.DISPLAY_UNAVAILABLE)

        override fun attachPrimaryPanel(
            view: android.view.View,
            onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
        ): PanelAttachResult = PanelAttachResult.Rejected(PanelRejectReason.DISPLAY_UNAVAILABLE)

        override fun setPrimaryPanelBrightness(fraction: Float?) = Unit
    }
}
