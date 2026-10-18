package com.gruning.mimir.rewrite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiTransitionProbe
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.LocalMimirKeyboardHapticFeedback
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirOverlayArriveSpec
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFadeAlpha
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Bottom's Utility window needs of the Utility model, and nothing more. The model is the process's one
 * [AppBrightnessCoordinator]; a window never creates a second writer. Kept apart so the window's life (opened, covering, closed,
 * revoked) is testable without a vendor gateway behind a real coordinator.
 */
internal interface BottomUtilityModel {
    /** The face showing on the Bottom (Utility or the Tweaker inside it), or null when closed or open on another screen. */
    val shownSurface: UtilitySurface?

    /** Opens [surface] (Utility, or the Tweaker directly) on the Bottom; true when it is now open there. */
    fun showOnBottom(surface: UtilitySurface): Boolean

    /** The panel's own Close: drafts discarded, a profile change asked to cancel first. */
    fun dismissFromBottom()

    /** The panel itself, for the window's composition. [presentationToken] is the window's own owner token. */
    @Composable
    fun Panel(presentationToken: UUID)
}

internal class CoordinatorBottomUtilityModel(
    private val coordinator: AppBrightnessCoordinator,
) : BottomUtilityModel {
    override val shownSurface: UtilitySurface?
        get() = coordinator.surface.takeIf {
            coordinator.isOpen && coordinator.presentationTarget == AppBrightnessPresentationTarget.SECONDARY
        }

    override fun showOnBottom(surface: UtilitySurface): Boolean {
        coordinator.open(
            requestedSurface = surface,
            target = AppBrightnessPresentationTarget.SECONDARY,
        )
        return shownSurface != null
    }

    override fun dismissFromBottom() {
        if (shownSurface != null) coordinator.dismissFromSecondaryPresentation()
    }

    @Composable
    override fun Panel(presentationToken: UUID) {
        // Retained for the exit: the window keeps drawing the panel through its fade after the model has closed.
        AppBrightnessCoordinatorHost(
            coordinator = coordinator,
            target = AppBrightnessPresentationTarget.SECONDARY,
            secondaryPresentationToken = presentationToken,
            retainedForExit = true,
        )
    }
}

/**
 * Whether the Presentation draws the instruments layer itself. While the Utility window carries the panel the Presentation must not
 * draw a second copy beneath it: the model's state ("open on the Bottom") is the same either way.
 */
internal fun bottomInstrumentsPanelShownInPresentation(
    openOnBottom: Boolean,
    utilityWindowCarriesPanel: Boolean,
): Boolean = openOnBottom && !utilityWindowCarriesPanel

/**
 *
 *
 * This is the Bottom's counterpart of [BottomQuickLaunchPanelHost], for the Utility panel. Utility used to be drawn inside Mimir's
 * Bottom Presentation. melonDS, Lime3DS and Azahar add their own window on the Bottom, the same second-screen window type, made
 * later and so above Mimir's; Utility opened beneath them and showed nothing. This window is the overlay controller's own
 * application-overlay panel on the Bottom display, which sits above all of those windows, and it is the Bottom's Utility whichever
 * window is on top, so nothing asks which one that is. The Tweaker is the panel's other face, swapped inside the same window.
 *
 * Touch only: the panel is lent `FLAG_NOT_FOCUSABLE`, takes no key, and is never given a Back to answer. It goes when the panel's own
 * Close or Back dismisses the model (the window sees the model close, lets the exit fade finish, then removes itself), when another
 * panel replaces it, or when the display or the overlay permission goes (the lease is revoked, and the model is closed with it so
 * the Presentation does not draw a panel nobody asked it to). It never starts an Activity and never brings Main anywhere.
 *
 * Process-owned, like the Quick Launch window: no Activity is started, borrowed or needed.
 */
internal class BottomUtilityPanelHost(
    context: Context,
    private val overlay: CompanionOverlayController,
    private val interactionMotionGate: InteractionMotionGate,
    private val model: () -> BottomUtilityModel? = {
        CompanionProcessRuntime.sharedBrightness.value?.let(::CoordinatorBottomUtilityModel)
    },
    /** Whether a game session is live. Over a game the Presentation lies beneath the emulator's own window, so it is no fallback. */
    private val gameSessionActive: () -> Boolean = { MimirSessionAuthority.state.value.gameSession != null },
) : MButtonBottomUtilityGateway {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coveringMutable = MutableStateFlow(false)
    private var mounted: MountedPanel? = null

    /**
     * True while this window carries the Utility panel. The Presentation beneath reads it and leaves the instruments layer to this
     * window ([bottomInstrumentsPanelShownInPresentation]).
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

    /** The Bottom ᛗ's Utility row. */
    override fun open(): BottomUtilityOpenResult = open(UtilitySurface.UTILITY)

    /**
     * Opens the window on [surface]: Utility from the Bottom ᛗ, the Tweaker from the shelf's instruments seat and the Tabs drawer's
     * . This is the ONE host for the Bottom's instruments: nothing else lends them a window, so a second
     * Utility/Tweaker overlay cannot exist. Against the Tabs drawer's window and Quick Launch's the overlay controller's single panel
     * lease does the excluding: an attach revokes the panel before it, and a revoked Utility window closes its model.
     */
    fun open(surface: UtilitySurface): BottomUtilityOpenResult {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return BottomUtilityOpenResult(opened = false, refusal = "not_main_thread")
        }
        val utility = model() ?: return BottomUtilityOpenResult(opened = false, refusal = BottomUtilityHostUnbound)
        mounted?.takeIf { it.attached }?.let {
            // A second press while the window is up reselects the surface in the same window.
            return if (utility.showOnBottom(surface)) {
                BottomUtilityOpenResult(opened = true)
            } else {
                BottomUtilityOpenResult(opened = false, refusal = "model_declined")
            }
        }
        mounted?.dispose()
        mounted = null
        // One Bottom panel at a time. Attaching replaces an overlay-hosted Tabs drawer's window; closing the drawer first keeps
        // its state from asking for that window back.
        CompanionRecentsState.hide()
        if (!utility.showOnBottom(surface)) return BottomUtilityOpenResult(opened = false, refusal = "model_declined")
        val presentationToken = UUID.randomUUID()
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
            setContent { BottomUtilityContent(utility, presentationToken, onFullyHidden = ::requestHide) }
        }
        // Touches in this window count for Main's screen idle and hold the ambient-motion lease, as the Quick Launch window's do:
        // they are read on a root around the ComposeView, which sees each touch before a Compose child consumes it.
        val panelRoot = object : FrameLayout(appContext) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // Qualified: inside a View, a bare `overlay` is that View's own ViewOverlay.
                overlayScreenActivityFor(event.actionMasked)?.let(this@BottomUtilityPanelHost.overlay::notifyScreenActivity)
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
            // Closes only its own candidate, never a replacement this host may already hold. The window is gone, so the model is
            // closed with it: left open on the Bottom it would be drawn by the Presentation, which nobody asked for.
            if (candidate.revoke(leaseId)) {
                // The model first, then the cover: at no instant is the model open on the Bottom with nothing carrying it.
                utility.dismissFromBottom()
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
                coveringMutable.value = true
                BottomUtilityOpenResult(opened = true)
            }
            is PanelAttachResult.Rejected -> {
                candidate.dispose()
                val reason = result.reason.name.lowercase(Locale.ROOT)
                if (gameSessionActive()) {
                    // Over a game the Presentation is beneath the emulator's window: a model left open on the Bottom would be drawn
                    // there and never seen. Nothing is lent, so nothing stays open.
                    utility.dismissFromBottom()
                    BottomUtilityOpenResult(opened = false, refusal = reason)
                } else {
                    // No game: the Bottom is Mimir's own Presentation, which draws a model open on the Bottom as it did before this
                    // window existed. `covering` stays false, so its instruments layer carries the panel. Never Main.
                    BottomUtilityOpenResult(opened = true, refusal = PresentationFallbackPrefix + reason)
                }
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

    // Posted, so the composition is never disposed from inside its own frame. Declines when Utility was opened again meanwhile:
    // that newer press keeps this window.
    private fun requestHide() {
        mainHandler.post {
            if (model()?.shownSurface == null) hide()
        }
    }

    @Composable
    private fun BottomUtilityContent(utility: BottomUtilityModel, presentationToken: UUID, onFullyHidden: () -> Unit) {
        val payload by CompanionState.payload.collectAsState()
        val phase by interactionMotionGate.phase.collectAsState()
        val haptics = remember { AndroidMimirHaptics(appContext) }
        BottomUtilityWindowContent(
            utility = utility,
            presentationToken = presentationToken,
            themeId = MimirThemeId.fromStorageKey(payload.themeId),
            motionPhase = phase,
            keyboardHaptic = haptics::navigationTick,
            onFullyHidden = onFullyHidden,
        )
    }
}

/** The `refusal` text of a press the window could not carry but the Presentation did (`opened` is true): `presentation_<reason>`. */
internal const val PresentationFallbackPrefix = "presentation_"

/**
 * What the Utility window draws: the model's panel on the Bottom's theme, arriving and leaving on the page tier's overlay clocks (the
 * Presentation's own for this panel: the 130 ms lead at alpha 0, then 540 ms in, and OverlayMs out). Separate from the host so a
 * test can compose it, in the Bottom's size, around the model it is given.
 */
@Composable
internal fun BottomUtilityWindowContent(
    utility: BottomUtilityModel,
    presentationToken: UUID,
    themeId: MimirThemeId,
    motionPhase: AmbientMotionPhase,
    keyboardHaptic: (() -> Unit)?,
    onFullyHidden: () -> Unit,
) {
    // The receipts name this window's own display (the panel is lent on the Bottom), and the Presentation's receipt for the
    // instruments surface keeps reporting the model's open/close, so none is made here twice.
    val panelView = LocalView.current
    val receiptDisplay = panelView.display?.displayId ?: BottomUiReceiptFormat.NO_DISPLAY
    val shown = utility.shownSurface != null
    // Start hidden so the FIRST composition plays the enter fade, then drive from the model.
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = shown
    LaunchedEffect(visibleState.targetState, visibleState.isIdle) {
        if (!visibleState.targetState && visibleState.isIdle) onFullyHidden()
    }
    // The Bottom is touch-only, so the gate holds no pad (the pad gate is process-wide); it keeps its touch half.
    val reducedMotion = remember(shown) { bottomUiReducedMotion() }
    val enterSpec = mimirOverlayArriveSpec(reducedMotion)
    val exitSpec = mimirOverlayLeaveSpec()
    MimirTheme(themeId = themeId) {
        val palette = LocalMimirPalette.current
        CompositionLocalProvider(
            LocalMimirAmbientMotionPhase provides motionPhase,
            LocalMimirKeyboardHapticFeedback provides keyboardHaptic,
            LocalBottomUiDisplay provides receiptDisplay,
        ) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(enterSpec),
                exit = fadeOut(exitSpec),
            ) {
                BottomUiTransitionProbe("instruments.fade", transition, receiptDisplay)
                val pageInput = MimirSwapPageGate(mimirRingSafeFadeAlpha(enterSpec, exitSpec), holdPad = false)
                // OPAQUE (reviewed 2026-10-09): the emulator's own screen was showing through the plate. The field is the theme's
                // base, drawn inside the page gate so it fades with the panel and the emulator is uncovered only as the exit ends.
                Box(modifier = pageInput.fillMaxSize().background(palette.surfaceBase)) { utility.Panel(presentationToken) }
            }
        }
    }
}
