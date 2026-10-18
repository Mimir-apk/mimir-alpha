package com.gruning.mimir.rewrite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionPanelLease
import com.gruning.mimir.rewrite.core.companionnative.PanelAttachResult
import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import com.gruning.mimir.rewrite.core.companionnative.overlayScreenActivityFor
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.LocalMimirKeyboardHapticFeedback
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchLayer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Bottom coin opens Quick launch on the Bottom screen, leaving Main's game and session intact.
 *
 * The Bottom menu's Quick launch used to be a Mimir-global command. The only panel that could open over an app without
 * MainActivity was the single-screen one ([PrimaryMButtonPanelHost]), so the Bottom started MainActivity on Main and
 * opened Quick launch in place there. This is the Bottom's counterpart: the Bottom's own picker, the All apps picker of
 * its rail ([CompanionQuickLaunchLayer], labelled from [CompanionQuickLaunchCatalog]), in a window the overlay controller
 * lends on the Bottom display. It sits over whatever the Bottom shows (the shelf, a platform's games, an app) the same
 * way the ᛗ menu that opens it does. Process-owned: no Activity is started, borrowed or needed.
 *
 * A pick routes exactly as a pick from the Bottom's other rails does ([routeSecondaryQuickLaunch]); where an app opens is
 * not decided here. Touch-only, like the Tabs drawer the same controller lends ([CompanionRecentsOverlayHost]).
 */
internal class BottomQuickLaunchPanelHost(
    context: Context,
    private val overlay: CompanionOverlayController,
    private val interactionMotionGate: InteractionMotionGate,
) : MButtonBottomQuickLaunchGateway {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requests = AtomicLong()
    private val generalPickerRequest = MutableStateFlow<Long?>(null)
    private val coveringMutable = MutableStateFlow(false)
    private var mounted: MountedPanel? = null

    /**
     * True while the picker, or a dialog it raised, is up in this window. The Presentation beneath cannot see this
     * composition: it softens its scene from this flow and leaves the pending display choice to this window.
     */
    val covering: StateFlow<Boolean> = coveringMutable.asStateFlow()

    /** RESUMED while the window shows, DESTROYED with it. Its own owner: an app may hold the Bottom. */
    private class HostOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private var destroyed = false

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun create() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    private class MountedPanel(
        private val owner: HostOwner,
        private val view: ComposeView,
        private val disposeMotion: () -> Unit,
    ) {
        var lease: CompanionPanelLease? = null
            private set
        var attached = false
            private set
        private var disposed = false

        fun bind(value: CompanionPanelLease) {
            lease = value
            attached = true
        }

        /** True when [leaseId] is this panel's own lease, which then no longer counts as attached. */
        fun revoke(leaseId: PanelLeaseId): Boolean {
            if (lease?.id != leaseId) return false
            attached = false
            return true
        }

        fun markDetached() {
            attached = false
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            disposeMotion()
            view.disposeComposition()
            owner.destroy()
        }
    }

    override fun open(): BottomQuickLaunchOpenResult {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return BottomQuickLaunchOpenResult(opened = false, refusal = "not_main_thread")
        }
        // The rail's All apps seat takes no tap while a launch holds the lock; this press is refused the same way.
        if (CompanionRecentsState.ui.value.launchLocked) {
            return BottomQuickLaunchOpenResult(opened = false, refusal = "launch_locked")
        }
        val request = requests.incrementAndGet()
        mounted?.takeIf { it.attached }?.let {
            // A second press while the window is up opens the picker again, in the same window.
            generalPickerRequest.value = request
            return BottomQuickLaunchOpenResult(opened = true)
        }
        mounted?.dispose()
        mounted = null
        // One Bottom panel at a time. Attaching replaces an overlay-hosted Tabs drawer's window; closing the drawer
        // first keeps its state from asking for that window back.
        CompanionRecentsState.hide()
        quickLaunchColdRead(
            load = { QuickLaunchStore.load(appContext) },
            read = { Unit },
        )
        generalPickerRequest.value = request
        val owner = HostOwner().also(HostOwner::create)
        val motionRoot = interactionMotionGate.registerRoot(
            InteractionRoot.NATIVE_WINDOW,
            initiallyVisible = true,
        )
        var touchLease: InteractionLease? = null
        val view = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            // Disposal is tied to the host lifecycle; hide() destroys it and then removes the window.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { BottomQuickLaunchContent(onNothingShown = ::requestHide) }
        }
        // Round-5 triage B2: this window's touches count for Main's screen idle, as the Tabs drawer's do. They are read on
        // a root around the ComposeView, which sees each touch before a Compose child consumes it. R6-5E-04: the motion
        // lease moved here too (mirrors MimirCompanionPresentation.dispatchTouchEvent) -- a listener on the ComposeView
        // itself is never called for a touch its Compose content consumes.
        val panelRoot = object : FrameLayout(appContext) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // Qualified: inside a View, a bare `overlay` is that View's own ViewOverlay.
                overlayScreenActivityFor(event.actionMasked)?.let(this@BottomQuickLaunchPanelHost.overlay::notifyScreenActivity)
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
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val candidate = MountedPanel(owner, view) {
            touchLease?.close()
            touchLease = null
            motionRoot.close()
        }
        return when (val result = overlay.attachSecondaryPanel(panelRoot) { leaseId, _ ->
            // Closes only its own candidate, never a replacement this host may already hold.
            if (candidate.revoke(leaseId)) {
                if (mounted === candidate) {
                    mounted = null
                    coveringMutable.value = false
                }
                candidate.dispose()
            }
        }) {
            is PanelAttachResult.Attached -> {
                candidate.bind(result.lease)
                mounted = candidate
                BottomQuickLaunchOpenResult(opened = true)
            }
            is PanelAttachResult.Rejected -> {
                candidate.dispose()
                BottomQuickLaunchOpenResult(opened = false, refusal = result.reason.name.lowercase(Locale.ROOT))
            }
        }
    }

    /** Takes the window down. Idempotent; from another thread it posts itself to main. */
    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::hide)
            return
        }
        val current = mounted ?: return
        mounted = null
        coveringMutable.value = false
        current.lease?.detach()
        current.markDetached()
        current.dispose()
    }

    // Posted, so the composition is never disposed from inside its own frame. Declines when the picker was asked for
    // again meanwhile: that newer request keeps this window.
    private fun requestHide(shownRequest: Long) {
        mainHandler.post {
            if (generalPickerRequest.value == shownRequest && !coveringMutable.value) hide()
        }
    }

    @Composable
    private fun BottomQuickLaunchContent(onNothingShown: (Long) -> Unit) {
        val request by generalPickerRequest.collectAsState()
        val recents by CompanionRecentsState.ui.collectAsState()
        val payload by CompanionState.payload.collectAsState()
        val quickSlots by QuickLaunchStore.slots.collectAsState()
        val quickLaunchApps by CompanionQuickLaunchCatalog.entries.collectAsState()
        val quickLaunchCatalogLoaded by CompanionQuickLaunchCatalog.hasLoaded.collectAsState()
        val quickLaunchChoice by CompanionQuickLaunchChoiceState.pending.collectAsState()
        val phase by interactionMotionGate.phase.collectAsState()
        val haptics = remember { AndroidMimirHaptics(appContext) }
        var pickerOpen by remember { mutableStateOf(false) }
        // The picker, or the display choice it raised (a first pick, or a hold to change one), keeps the window.
        val shown = pickerOpen || quickLaunchChoice != null
        SideEffect { coveringMutable.value = shown }
        LaunchedEffect(shown, request) {
            val current = request ?: return@LaunchedEffect
            if (shown) return@LaunchedEffect
            // Nothing is up: let the picker's own exit fade finish, then the window goes.
            delay(MimirMotion.OverlayMs.toLong())
            onNothingShown(current)
        }
        // Round 5 (5b): a tap's one toast is made from the display of this window, which is where the player tapped
        // (`attachSecondaryPanel` mounts it on the Bottom). A toast made from `appContext` alone lands on Main.
        val panelView = LocalView.current
        val onPrimaryQuickLaunch: (String, Boolean) -> QuickLaunchPrimaryRouteResult = { packageName, fromGeneral ->
            CompanionQuickLaunchPrimaryRoute.dispatch(packageName, fromGeneral)
        }
        val onSecondaryQuickLaunch: (String, String?) -> Boolean = { packageName, label ->
            // Quick Launch closes the app's copy on Main first, so the app reopens fresh on the Bottom and takes the keyboard.
            val operationId = CompanionRecentsState.onLaunch(packageName, label, clearMainCopy = true)
            companionQuickLaunchDispatchSucceeded(
                operationId,
                CompanionRecentsState.ui.value.launchState,
            )
        }
        MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
            CompositionLocalProvider(
                LocalMimirAmbientMotionPhase provides phase,
                LocalMimirKeyboardHapticFeedback provides haptics::navigationTick,
                // Receipts: this window is lent on the Bottom, and the picker inside it reports against that display.
                LocalBottomUiDisplay provides (panelView.display?.displayId ?: BottomUiReceiptFormat.NO_DISPLAY),
            ) {
                CompanionQuickLaunchLayer(
                    onPickerOpenChanged = { opened -> pickerOpen = opened },
                    slots = quickSlots,
                    apps = quickLaunchApps,
                    catalogLoaded = quickLaunchCatalogLoaded,
                    launchLocked = recents.launchLocked,
                    launchMessage = recents.launchMessage,
                    onLaunch = { packageName ->
                        val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                        routeSecondaryQuickLaunchTap(
                            packageName = packageName,
                            label = label,
                            fromGeneral = true,
                            onPrimary = onPrimaryQuickLaunch,
                            onSecondary = onSecondaryQuickLaunch,
                            feedbackContext = { companionFeedbackContext(appContext, panelView.display?.displayId) },
                            unavailableCopy = "Launch unavailable. Try again.",
                        )
                    },
                    onAssign = { index, packageName -> QuickLaunchStore.setSlot(appContext, index, packageName) },
                    onClear = { index -> QuickLaunchStore.setSlot(appContext, index, null) },
                    quickLaunchChoice = quickLaunchChoice,
                    onQuickLaunchChoice = { choice, destination ->
                        settleSecondaryQuickLaunchChoiceTap(
                            context = appContext,
                            choice = choice,
                            destination = destination,
                            onPrimary = onPrimaryQuickLaunch,
                            onSecondary = onSecondaryQuickLaunch,
                            feedbackContext = { companionFeedbackContext(appContext, panelView.display?.displayId) },
                            unavailableCopy = "Launch unavailable. Try again.",
                        )
                    },
                    onQuickLaunchChoiceDismiss = { choice -> CompanionQuickLaunchChoiceState.dismiss(choice) },
                    onQuickLaunchEdit = { packageName ->
                        val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                        CompanionQuickLaunchChoiceState.request(packageName, label, editing = true)
                    },
                    railVisible = false,
                    generalPickerRequest = request,
                    // This layer is composed in the quick menu's own overlay window: the stack snapshot sorts its picker, pin actions and launch
                    // prompt above `m-menu` (721-723), not below it as they sort in the Presentation's own surface group.
                    receiptHost = BottomSurface.M_MENU,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
