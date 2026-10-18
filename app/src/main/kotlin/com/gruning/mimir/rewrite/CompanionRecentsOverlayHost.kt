package com.gruning.mimir.rewrite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gruning.mimir.rewrite.core.companionnative.CompanionPanelLease
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.PanelAttachResult
import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import com.gruning.mimir.rewrite.core.companionnative.PanelRejectReason
import com.gruning.mimir.rewrite.core.companionnative.overlayScreenActivityFor
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiTransitionProbe
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirOverlayArriveSpec
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFadeAlpha
import com.gruning.mimir.rewrite.feature.companion.CompanionRecentsDrawer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 *  hosts the tab-manager drawer ([CompanionRecentsDrawer]) in a native
 * `TYPE_APPLICATION_OVERLAY` window on the SECONDARY display, lent by [CompanionOverlayController]. That window
 * z-orders OVER a foreground foreign app (exactly like the M button), so opening the tab manager while an app
 * runs NO LONGER requires the app-evicting reclaim a [android.app.Presentation] would (the render-gate) — the
 * app keeps rendering behind the drawer.
 *
 * The ComposeView gets its OWN always-RESUMED lifecycle/savedstate owners (mirroring MimirCompanionPresentation's
 * PresentationHost) — a foreign app owns the display, so it must not borrow the Activity's lifecycle. The drawer
 * observes [CompanionRecentsState]; when it dismisses (visible → false) the exit fade plays and then the window
 * detaches. Touch-only, no controller focus (the window is FLAG_NOT_FOCUSABLE).
 */
class CompanionRecentsOverlayHost(
    private val appContext: Context,
    private val overlay: CompanionOverlayController,
    private val viewModelStoreOwner: ViewModelStoreOwner,
    private val interactionMotionGate: InteractionMotionGate,
    private val isOverlayEligible: () -> Boolean,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** RESUMED while the drawer window shows, DESTROYED on hide — its own owner, not the Activity's. */
    private class Host : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private var destroyed = false
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        fun create() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() {
            if (destroyed) return
            destroyed = true
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private class MountedPanel(
        private val host: Host,
        private val view: ComposeView,
        val leaseIdState: MutableStateFlow<PanelLeaseId?>,
        private val disposeMotion: () -> Unit,
    ) {
        var lease: CompanionPanelLease? = null
            private set
        var attached: Boolean = false
            private set
        private var disposed = false

        fun bind(lease: CompanionPanelLease) {
            this.lease = lease
            attached = true
            leaseIdState.value = lease.id
        }

        fun revoke(leaseId: PanelLeaseId) {
            if (lease?.id != leaseId) return
            attached = false
            dispose()
        }

        fun markDetached() {
            attached = false
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            disposeMotion()
            view.disposeComposition()
            host.destroy()
        }
    }

    private var mounted: MountedPanel? = null

    init {
        val owner = viewModelStoreOwner as? LifecycleOwner
        owner?.lifecycle?.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    overlay.reconcile()
                    mainHandler.post {
                        if (!overlay.overlayPermissionMissing.value &&
                            isOverlayEligible() &&
                            CompanionRecentsState.shouldAttachOverlay()
                        ) {
                            show()
                        }
                    }
                }
            },
        )
        owner?.lifecycleScope?.launch {
            overlay.overlayPermissionMissing.collect { missing ->
                if (!missing && isOverlayEligible() && CompanionRecentsState.shouldAttachOverlay()) show()
            }
        }
    }

    fun isShowing(): Boolean = mounted?.attached == true

    /** Attach the drawer window over the foreground app. Main thread. */
    fun show(): Boolean {
        // Permission is checked before the active-host fast path. A missing appop is recoverable and does not
        // route Tabs through a reclaim fallback; the AppOps/onResume hooks remount this eligible drawer.
        if (!overlay.hasOverlayPermission()) {
            CompanionRecentsState.noteOverlayUnavailable(PanelRejectReason.OVERLAY_PERMISSION_MISSING)
            overlay.reconcile()
            return true
        }
        mounted?.takeIf { it.attached }?.let { current ->
            current.lease?.let { lease -> CompanionRecentsState.attachOverlayHost(lease.id) }
            return true
        }
        mounted?.dispose()
        mounted = null
        quickLaunchColdRead(
            load = { QuickLaunchStore.load(appContext) },
            read = { Unit },
        )

        val h = Host().also { it.create() }
        val motionRoot = interactionMotionGate.registerRoot(
            InteractionRoot.NATIVE_WINDOW,
            initiallyVisible = true,
        )
        var touchLease: InteractionLease? = null
        val leaseIdState = MutableStateFlow<PanelLeaseId?>(null)
        val cv = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(h)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(h)
            // Disposal is tied to our host lifecycle; hide() destroys it, then removes the view.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val leaseId by leaseIdState.collectAsState()
                val phase by interactionMotionGate.phase.collectAsState()
                CompositionLocalProvider(LocalMimirAmbientMotionPhase provides phase) {
                    OverlayRecentsContent(leaseId = leaseId, onFullyHidden = ::requestHide)
                }
            }
        }
        // Round-5 triage B2: the drawer's touches count for Main's screen idle. They are observed on a root around the
        // ComposeView: a touch listener on the ComposeView itself is not called for a touch its Compose child takes, and
        // the drawer's backdrop and panel are both clickable. R6-5E-04: the motion lease moved here too (mirrors
        // MimirCompanionPresentation.dispatchTouchEvent), for the same reason.
        val panelRoot = object : FrameLayout(appContext) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // Qualified: inside a View, a bare `overlay` is that View's own ViewOverlay.
                overlayScreenActivityFor(event.actionMasked)?.let(this@CompanionRecentsOverlayHost.overlay::notifyScreenActivity)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchLease?.close()
                    touchLease = interactionMotionGate.hold(motionRoot, InteractionSource.NATIVE_OVERLAY_PANEL)
                }
                try {
                    return super.dispatchTouchEvent(event)
                } finally {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        touchLease?.close()
                        touchLease = null
                    }
                }
            }
        }.apply {
            // Compose builds the window recomposer from the window's root view and throws without these owners there.
            setViewTreeLifecycleOwner(h)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(h)
            addView(cv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val candidate = MountedPanel(h, cv, leaseIdState) {
            touchLease?.close()
            touchLease = null
            motionRoot.close()
        }
        return when (val result = overlay.attachSecondaryPanel(panelRoot) { leaseId, reason ->
            // This callback closes only its captured candidate. It never reads host fields that may already
            // refer to a replacement panel.
            candidate.revoke(leaseId)
            CompanionRecentsState.releaseOverlayHost(leaseId, reason)
        }) {
            is PanelAttachResult.Attached -> {
                candidate.bind(result.lease)
                mounted = candidate
                if (!CompanionRecentsState.attachOverlayHost(result.lease.id)) {
                    result.lease.detach()
                    candidate.markDetached()
                    candidate.dispose()
                    mounted = null
                    false
                } else {
                    true
                }
            }
            is PanelAttachResult.Rejected -> {
                candidate.dispose()
                CompanionRecentsState.noteOverlayUnavailable(result.reason)
                // Missing permission is a handled, pending recovery state. Other failures may use the existing
                // render/reclaim fallback until their owning coordinator integration is replaced.
                result.reason == PanelRejectReason.OVERLAY_PERMISSION_MISSING
            }
        }
    }

    /** Tear the drawer window down. Idempotent. MAIN THREAD ONLY (drives the LifecycleRegistry + Compose dispose). */
    fun hide() {
        val current = mounted ?: return
        mounted = null
        val lease = current.lease
        if (lease != null) {
            lease.detach()
            current.markDetached()
            // A matching acknowledgement enables the Presentation projection only after the panel window is
            // detached. A stale acknowledgement cannot alter a newer overlay host.
            CompanionRecentsState.releaseOverlayHost(lease.id)
        }
        current.dispose()
    }

    // Called from inside the drawer composition once its exit fade has fully settled — post so we never dispose
    // the composition from within its own frame, and re-check the shared state: if the drawer was re-opened
    // during the fade, DON'T tear it down (show() reuses the window, so a blind hide would strand visible=true).
    private fun requestHide() {
        mainHandler.post { if (!CompanionRecentsState.ui.value.requestedVisible) hide() }
    }

    @Composable
    private fun OverlayRecentsContent(leaseId: PanelLeaseId?, onFullyHidden: () -> Unit) {
        val recents by CompanionRecentsState.ui.collectAsState()
        // L-6: the runtime decides whether the per-tab ✕ is offered at all.
        val companionRuntime by CompanionReturnCoordinator.runtime().snapshot.collectAsState()
        val payload by CompanionState.payload.collectAsState()
        // The Presentation-hosted rails pass this (638245cb); this host used to leave the drawer's default of
        // true, so in the cold window an unswept pin read as an EMPTY seat whose tap opened the picker
        // instead of launching (surface map finding F4, 2026-09-11).
        val quickLaunchCatalogLoaded by CompanionQuickLaunchCatalog.hasLoaded.collectAsState()
        val quickSlots by QuickLaunchStore.slots.collectAsState()
        val quickLaunchApps by CompanionQuickLaunchCatalog.entries.collectAsState()
        val quickLaunchChoice by CompanionQuickLaunchChoiceState.pending.collectAsState()
        // QA D17: the launch this drawer's lock is holding for, named in the ignored-tap line.
        val pendingLaunchOperationId =
            (recents.launchState as? CompanionDrawerLaunchState.LaunchPending)?.operationId?.toString()
        // Round 5 (5b): the drawer's failure toasts are made from the display of the drawer's own window, which is where the
        // player tapped (`attachSecondaryPanel` mounts it on the Bottom). A toast made from `appContext` alone lands on the
        // default display.
        val drawerView = LocalView.current
        val onPrimaryQuickLaunch: (String, Boolean) -> QuickLaunchPrimaryRouteResult = { packageName, fromGeneral ->
            CompanionQuickLaunchPrimaryRoute.dispatch(packageName, fromGeneral)
        }
        val onSecondaryQuickLaunch: (String, String?) -> Boolean = { packageName, label ->
            val operationId = CompanionRecentsState.onLaunch(packageName, label)
            companionQuickLaunchDispatchSucceeded(
                operationId,
                CompanionRecentsState.ui.value.launchState,
            )
        }
        // R1 : over a dual-screen game this window sits above the emulator's own. The emulator's screen is then not
        // shown through the drawer's frost, and the drawer offers the Tweaker seat that opens the one Bottom instruments window.
        val gameSession by MimirSessionAuthority.state.collectAsState()
        val dualScreenGameLive = companionMainDualScreenGameActive(gameSession.gameSession)
        val bottomUtilityPanel = remember(appContext) { CompanionProcessRuntime.services(appContext).bottomUtilityPanel }
        val selected = leaseId?.let(recents::isVisibleOnOverlay) == true
        // Start hidden so the FIRST composition plays the enter fade (CP-FADE), then drive from the state.
        val visibleState = remember { MutableTransitionState(false) }
        visibleState.targetState = selected
        LaunchedEffect(visibleState.targetState, visibleState.isIdle) {
            if (!visibleState.targetState && visibleState.isIdle) onFullyHidden()
        }
        // Receipts: this window is the Bottom's Tabs drawer. Its display is the window's own (the panel is lent on the Bottom).
        val receiptDisplay = drawerView.display?.displayId ?: BottomUiReceiptFormat.NO_DISPLAY
        BottomUiSurfaceReceipt(
            surface = BottomSurface.TABS,
            visible = selected,
            transition = if (selected) "tabs.enter" else "tabs.exit",
            display = receiptDisplay,
        )
        CompositionLocalProvider(LocalBottomUiDisplay provides receiptDisplay) {
        // B1: Tabs arrive on the page tier's overlay clock, as Main's Quick Launch dock does (the 130 ms lead at alpha 0, then 540 ms: about 670 ms
        // from the press) and leave on OverlayMs (about 180 ms). One pair of specs feeds the painting and the touch gate's probe, so they cannot drift.
        // The Bottom is touch-only, so the gate passes holdPad = false: it keeps its touch half (a page that is arriving takes no touch until it paints,
        // one that is leaving takes none) and never holds the process-wide pad gate, which would deafen Main's controller for the lead.
        val tabsReducedMotion = remember(selected) { bottomUiReducedMotion() }
        val tabsEnterSpec = mimirOverlayArriveSpec(tabsReducedMotion)
        val tabsExitSpec = mimirOverlayLeaveSpec()
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tabsEnterSpec),
            exit = fadeOut(tabsExitSpec),
        ) {
            BottomUiTransitionProbe(if (selected) "tabs.enter" else "tabs.exit", transition, receiptDisplay)
            val tabsInput = MimirSwapPageGate(mimirRingSafeFadeAlpha(tabsEnterSpec, tabsExitSpec), holdPad = false)
            CompanionRecentsDrawer(
                modifier = tabsInput,
                opaqueBackdrop = dualScreenGameLive,
                instrumentsShown = if (dualScreenGameLive) false else null,
                onToggleInstruments = {
                    // The drawer stands down first: the window replaces this one, and a drawer left asking for its window back would fight it.
                    CompanionRecentsState.hide()
                    if (!bottomUtilityPanel.open(UtilitySurface.TWEAKER).opened) {
                        showMimirMessage(
                            companionFeedbackContext(appContext, drawerView.display?.displayId),
                            "Tweaker is not ready on the bottom screen",
                        )
                    }
                },
                entries = recents.entries,
                closeOffered = companionTabCloseIsOffered(companionRuntime),
                launchLocked = recents.launchLocked,
                launchMessage = recents.launchMessage,
                closeMessage = recents.closeMessage,
                closePending = recents.closePending,
                themeId = payload.themeId,
                quickSlots = quickSlots,
                quickLaunchApps = quickLaunchApps,
                quickLaunchCatalogLoaded = quickLaunchCatalogLoaded,
                onQuickLaunch = { packageName ->
                    val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                    routeSecondaryQuickLaunchTap(
                        packageName = packageName,
                        label = label,
                        fromGeneral = true,
                        onPrimary = onPrimaryQuickLaunch,
                        onSecondary = onSecondaryQuickLaunch,
                        feedbackContext = { companionFeedbackContext(appContext, drawerView.display?.displayId) },
                        unavailableCopy = "Launch unavailable. Try again.",
                    )
                },
                onPinnedQuickLaunch = { packageName ->
                    val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                    routeSecondaryQuickLaunchTap(
                        packageName = packageName,
                        label = label,
                        fromGeneral = false,
                        onPrimary = onPrimaryQuickLaunch,
                        onSecondary = onSecondaryQuickLaunch,
                        feedbackContext = { companionFeedbackContext(appContext, drawerView.display?.displayId) },
                        unavailableCopy = "Launch unavailable. Try again.",
                    )
                },
                onQuickAssign = { index, packageName ->
                    QuickLaunchStore.setSlot(appContext, index, packageName)
                },
                onQuickClear = { index -> QuickLaunchStore.setSlot(appContext, index, null) },
                quickLaunchChoice = quickLaunchChoice,
                onQuickLaunchChoice = { choice, destination ->
                    settleSecondaryQuickLaunchChoiceTap(
                        context = appContext,
                        choice = choice,
                        destination = destination,
                        onPrimary = onPrimaryQuickLaunch,
                        onSecondary = onSecondaryQuickLaunch,
                        feedbackContext = { companionFeedbackContext(appContext, drawerView.display?.displayId) },
                        unavailableCopy = "Launch unavailable. Try again.",
                    )
                },
                onQuickLaunchChoiceDismiss = { choice ->
                    CompanionQuickLaunchChoiceState.dismiss(choice)
                },
                onQuickLaunchEdit = { packageName ->
                    val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                    CompanionQuickLaunchChoiceState.request(packageName, label, editing = true)
                },
                onLaunch = { CompanionRecentsState.onLaunch(it) },
                onClose = { CompanionRecentsState.onClose(it) },
                onDismiss = { CompanionRecentsState.hide() },
                onQuickLaunchIgnored = { target ->
                    Log.i("MimirCompanionRuntime", secondaryLaunchIgnoredLogMessage(target, pendingLaunchOperationId))
                },
            )
        }
        }
    }
}
