package com.gruning.mimir.rewrite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
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
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Process-owned primary-display host for global M-button surfaces.
 *
 * It deliberately does not borrow MainActivity's lifecycle, window, or FocusAuthority. The exact
 * native panel lease keeps the current foreground app mounted beneath Quick Launch/Utility, while
 * the host owns one isolated input/focus graph only for the lifetime of that focusable panel.
 */
internal class PrimaryMButtonPanelHost(
    context: Context,
    private val overlay: CompanionOverlayController,
    private val topology: MButtonTopologyController,
    private val privilegedUtility: PrivilegedUtilityGateway,
    private val interactionMotionGate: InteractionMotionGate,
) : MButtonPrimaryPanelGateway {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val presentationEpoch = AtomicLong()
    private var mounted: MountedPanel? = null

    /** The panel whose window is fading out ([hide] began its exit and its window is not yet gone), if any. At most one. */
    private var leaving: MountedPanel? = null

    private class HostOwner(
        private val onBack: () -> Unit,
    ) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private var destroyed = false

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        override val onBackPressedDispatcher = OnBackPressedDispatcher(onBack)

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
        val owner: HostOwner,
        val view: ComposeView,
        val scope: CoroutineScope,
        val inputRouter: InputRouter,
        val utilityCoordinator: AppBrightnessCoordinator?,
        private val utilityController: BrightnessController?,
        private val utilityHostToken: Long?,
        private val disposeMotion: () -> Unit,
        /** The leave of this panel's window: fades the body, keeps input dead, then takes the window down. */
        val exit: NativePanelExit,
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

        fun revoke(leaseId: PanelLeaseId) {
            if (lease?.id != leaseId) return
            attached = false
        }

        fun markDetached() {
            attached = false
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            // A panel disposed by another road (revoked, replaced, finished) must not have its exit finish it a second time.
            exit.cancel()
            // A stale overlay must not close or detach a successor Activity/overlay that has already
            // taken ownership of the process-retained coordinator.
            utilityHostToken?.let { token ->
                // SECONDARY is a live handoff to the touch-only Presentation. The old primary window
                // must release its binding without closing that newly selected surface or cancelling
                // its pending settlement.
                if (utilityCoordinator?.presentationTarget == AppBrightnessPresentationTarget.PRIMARY) {
                    utilityCoordinator.onHostPausedIfCurrent(token)
                    utilityCoordinator.closeIfCurrent(token)
                }
                CompanionProcessRuntime.unregisterOverlayBrightnessHost(token)
            }
            inputRouter.reset()
            disposeMotion()
            scope.cancel()
            view.disposeComposition()
            owner.destroy()
        }
    }

    override fun show(command: MButtonPrimaryCommand): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (command != MButtonPrimaryCommand.QUICK_LAUNCH &&
            command != MButtonPrimaryCommand.UTILITY &&
            command != MButtonPrimaryCommand.TWEAKER &&
            command != MButtonPrimaryCommand.MUSIC
        ) {
            return false
        }
        // A panel already on its way out is finished at once: the next one is replacing it, and two full-screen windows never overlap.
        hideNow()
        if (command != MButtonPrimaryCommand.MUSIC) {
            quickLaunchColdRead(
                load = { QuickLaunchStore.load(appContext) },
                read = { Unit },
            )
        }
        ThemeStore.load(appContext)

        val focusAuthority = DefaultFocusAuthority()
        val panelJob = SupervisorJob()
        val panelScope = CoroutineScope(
            panelJob + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { context, throwable ->
                    Log.e("MimirMButton", "primary panel work failed in $context", throwable)
                },
        )
        var utilityController: BrightnessController? = null
        var utilityHostToken: Long? = null
        val utilityCoordinator = if (
            command == MButtonPrimaryCommand.UTILITY || command == MButtonPrimaryCommand.TWEAKER
        ) {
            val controller = BrightnessController(
                settings = AndroidBrightnessSettingsGateway(appContext),
                preferences = AndroidWindowBrightnessPreferenceStore(appContext),
                identity = currentBrightnessDeviceIdentity(appContext),
                secondaryWindowAvailable = { false },
                windowSink = object : WindowBrightnessSink {
                    override fun applyPrimary(fraction: Float?) {
                        overlay.setPrimaryPanelBrightness(fraction)
                    }

                    override fun applySecondary(fraction: Float?) = Unit
                },
                utilityPreferences = AndroidUtilityPreferenceStore(appContext),
                haptics = AndroidMimirHaptics(appContext),
                navSound = AndroidNavSound(appContext),
                ambientSound = AndroidAmbientSound(appContext),
                mediaVolume = AndroidMediaVolume(appContext),
                privilegedUtility = privilegedUtility,
                deferInitialPrivilegedProbe = true,
            )
            utilityController = controller
            CompanionProcessRuntime.brightnessCoordinator(
                context = appContext,
                controller = controller,
                authority = focusAuthority,
                beforeExternalActivity = ::hide,
                // This host is a native overlay window with its own FocusAuthority, over whatever app is
                // in the foreground -- MainActivity, and therefore FileBrowserSurface, is not composed
                // behind it. The Utility "Files" row is withheld here rather than offered and refused:
                // opening the store from this authority is the divorced-browser input lockup that
                // filesRouteDecision's KDoc describes in full. Every other Utility row still applies,
                // including the Tweaker route, whose destination IS reachable from this window.
                hostsInAppSurfaces = false,
                hostKind = CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY,
            ).also {
                utilityHostToken = CompanionProcessRuntime.currentBrightnessHost(
                    CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY,
                )
                it.open(
                    if (command == MButtonPrimaryCommand.TWEAKER) {
                        UtilitySurface.TWEAKER
                    } else {
                        UtilitySurface.UTILITY
                    },
                )
            }
        } else {
            null
        }
        // The single-display music panel : its own state and input over the same host plumbing. The badge it is
        // anchored to is read once, as the panel opens; it does not move while the panel is up.
        val musicController = if (command == MButtonPrimaryCommand.MUSIC) {
            MusicPanelController(
                engine = MimirMusicPanelEngine,
                authority = focusAuthority,
                scope = panelScope,
                mediaVolume = AndroidMediaVolume(appContext),
                anchor = overlay.primaryButtonBounds.value,
                // Posted, not run inside the key dispatch that is consuming the release: the window goes only after the
                // dismiss key's UP was consumed, so no half of the press reaches the covered app (as the M menu's does).
                onClose = { mainHandler.post { hide() } },
                // The art opens the other player's app; when it will not open the panel stays and says so.
                onOpenFailed = { showMimirMessage(appContext, "Mimir couldn’t open that app.") },
            )
        } else {
            null
        }
        val mode = HomeQuickLaunchPickerMode.General
        var quickItems = homeQuickLaunchPickerItems(
            mode,
            CompanionQuickLaunchCatalog.entries.value,
            QuickLaunchStore.slots.value,
            QuickLaunchStore.launchSequences.value,
        )
        val epoch = presentationEpoch.incrementAndGet()

        fun launchPackage(packageName: String): Boolean {
            QuickLaunchStore.setLastGeneralPackageName(appContext, packageName)
            // N5 (2026-08-12): was a bare Boolean collapsing two distinct failure reasons (no
            // launchable intent vs. an activity that failed to start) into one undifferentiated
            // toast from this one shared call site. The sentence stays the same — matching this
            // exact product's own precedent for the identical distinction on the native shelf's
            // other launch path (MimirNativeShelfCoordinator.kt's B1/B3 arms, N2 2026-08-12, both
            // reuse "App could not be launched" verbatim) — but the two reasons are no longer
            // collapsed onto one report: NO_LAUNCHABLE_INTENT is logged and toasted inside
            // launchNativeShelfPackageResult itself, so only ACTIVITY_NOT_STARTED still toasts here
            // (never both, for one real failure).
            val result = launchNativeShelfPackageResult(appContext, packageName)
            val launched = result == NativeShelfLaunchResult.LAUNCHED
            QuickLaunchStore.settleLaunch(
                context = appContext,
                origin = QuickLaunchLaunchOrigin.GENERAL_M_BUTTON,
                dispatchSucceeded = launched,
                packageName = packageName,
            )
            when {
                launched -> hide()
                result == NativeShelfLaunchResult.ACTIVITY_NOT_STARTED ->
                    showMimirMessage(appContext, "Mimir couldn’t open that app.")
                // NO_LAUNCHABLE_INTENT already reported inside launchNativeShelfPackageResult.
                else -> Unit
            }
            return launched
        }

        val inputRouter = InputRouter(
            interactionRoot = InteractionRoot.NATIVE_WINDOW,
            interactionMotionGate = interactionMotionGate,
            hatRepeatTicker = HatRepeatTicker { delayMs, tick ->
                val task = Runnable(tick)
                val cancel: () -> Unit = { mainHandler.removeCallbacks(task) }
                mainHandler.postDelayed(task, delayMs)
                cancel
            },
            hatRepeatClock = SystemClock::uptimeMillis,
        ).apply {
            handler = { action ->
                if (musicController != null) {
                    musicController.dispatch(action)
                } else if (command == MButtonPrimaryCommand.UTILITY || command == MButtonPrimaryCommand.TWEAKER) {
                    utilityCoordinator?.dispatch(action) ?: true
                } else {
                    dispatchHomeQuickLaunchPicker(
                        authority = focusAuthority,
                        mode = mode,
                        items = quickItems,
                        action = action,
                        onLaunch = { packageName -> launchPackage(packageName) },
                        onAssign = { _, _ -> },
                        onClear = {},
                        onDismiss = ::hide,
                    )
                }
            }
            navMoveProbe = { focusAuthority.state.value }
        }

        lateinit var candidate: MountedPanel
        // The screen going off closes the music panel (policy); nothing else closes it but the player.
        val screenOffReceiver = if (musicController != null) {
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) hide()
                }
            }.also { receiver ->
                runCatching {
                    val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        appContext.registerReceiver(receiver, filter)
                    }
                }.onFailure { Log.w("MimirMButton", "music panel could not watch for the screen going off", it) }
            }
        } else {
            null
        }
        val owner = HostOwner(::hide).also(HostOwner::create)
        // RC-38 (audit 2026-10-14): hide() starts this; the window goes when the fade ends, not on the frame hide() is called.
        val exit = NativePanelExit(mainHandler, finish = { finishExit(candidate) })
        val motionRoot = interactionMotionGate.registerRoot(
            InteractionRoot.NATIVE_WINDOW,
            initiallyVisible = true,
        )
        var touchLease: InteractionLease? = null
        val view = ComposeView(appContext).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeOnBackPressedDispatcherOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setOnKeyListener { _, _, event ->
                // Dead to input from the moment the exit begins: both edges of a key are swallowed here, so a half-press never
                // reaches the app beneath while the window is still up.
                if (exit.exiting) {
                    true
                } else if (musicController != null) {
                    // A and B act on release and swallow both edges; the volume keys go to Android (the music panel's key gate).
                    musicController.onKey(
                        keyCode = event.keyCode,
                        down = event.action == KeyEvent.ACTION_DOWN,
                        repeatCount = event.repeatCount,
                        route = { inputRouter.onKeyEvent(event) },
                    )
                } else {
                    inputRouter.onKeyEvent(event)
                }
            }
            setOnGenericMotionListener { _, event -> exit.exiting || inputRouter.onMotionEvent(event) }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchLease?.close()
                        touchLease = interactionMotionGate.hold(
                            motionRoot,
                            InteractionSource.NATIVE_OVERLAY_PANEL,
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touchLease?.close()
                        touchLease = null
                    }
                }
                false
            }
            setContent {
                val themeId by ThemeStore.themeId.collectAsState()
                val motionPhase by interactionMotionGate.phase.collectAsState()
                MimirTheme(themeId) {
                    CompositionLocalProvider(LocalMimirAmbientMotionPhase provides motionPhase) {
                        if (musicController != null) {
                            // The music panel leaves "dissolve, then clear" : its card fades with the body, its scrim and its
                            // window blur (Q14) hold for a beat and then clear, and the window goes when the glass is at 0.
                            // `overlay` inside this ComposeView would be its own ViewOverlay: the controller is named by its owner.
                            NativePanelGlassExitFade(
                                exiting = exit.exiting,
                                onFullyHidden = exit::onFullyHidden,
                                applyBlur = this@PrimaryMButtonPanelHost.overlay::setPrimaryPanelBlurBehind,
                            ) {
                                MusicPanelHost(controller = musicController, onDismiss = ::hide)
                            }
                        } else NativePanelExitFade(exiting = exit.exiting, onFullyHidden = exit::onFullyHidden) {
                            if (command == MButtonPrimaryCommand.UTILITY || command == MButtonPrimaryCommand.TWEAKER) {
                                val coordinator = requireNotNull(utilityCoordinator)
                                AppBrightnessCoordinatorHost(
                                    coordinator = coordinator,
                                    hostKind = CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY,
                                    // Kept drawing after the model closes: the window fades it out (the exit), then goes.
                                    retainedForExit = true,
                                )
                                LaunchedEffect(coordinator.isOpen, coordinator.presentationTarget) {
                                    if (!coordinator.isOpen ||
                                        coordinator.presentationTarget != AppBrightnessPresentationTarget.PRIMARY
                                    ) {
                                        hide()
                                    }
                                }
                            } else {
                                val apps by CompanionQuickLaunchCatalog.entries.collectAsState()
                                val slots by QuickLaunchStore.slots.collectAsState()
                                val launchSequences by QuickLaunchStore.launchSequences.collectAsState()
                                val items = remember(apps, slots, launchSequences) {
                                    homeQuickLaunchPickerItems(mode, apps, slots, launchSequences)
                                }
                                SideEffect { quickItems = items }
                                val activation = remember(epoch) { HomeQuickLaunchPickerTouchActivation() }
                                HomeQuickLaunchPickerOverlay(
                                    authority = focusAuthority,
                                    mode = mode,
                                    items = items,
                                    presentationEpoch = epoch,
                                    initialGeneralPackageName =
                                        QuickLaunchStore.lastGeneralPackageName(appContext),
                                    onGeneralFocusChanged = { packageName ->
                                        QuickLaunchStore.setLastGeneralPackageName(appContext, packageName)
                                    },
                                    onPresentationReadyChanged = {},
                                    onChoose = { item ->
                                        activation.dispatch(
                                            mode = mode,
                                            item = item,
                                            onLaunch = ::launchPackage,
                                            onAssign = { _, _ -> },
                                            onClear = {},
                                        )
                                    },
                                    onDismiss = ::hide,
                                )
                            }
                        }
                    }
                }
            }
        }
        candidate = MountedPanel(
            owner = owner,
            view = view,
            scope = panelScope,
            inputRouter = inputRouter,
            utilityCoordinator = utilityCoordinator,
            utilityController = utilityController,
            utilityHostToken = utilityHostToken,
            disposeMotion = {
                touchLease?.close()
                touchLease = null
                motionRoot.close()
                screenOffReceiver?.let { receiver -> runCatching { appContext.unregisterReceiver(receiver) } }
                musicController?.dispose()
            },
            exit = exit,
        )
        return when (val result = overlay.attachPrimaryPanel(view) { leaseId, _ ->
            // Release suppression FIRST and unconditionally, before anything that can throw.
            // PanelLeaseOwner.revokeCurrent (core-companion-native) invokes this whole closure
            // inside a runCatching, so a throwing candidate.dispose() would otherwise swallow
            // whatever runs after it -- if the release came last, a throwing dispose left
            // suppression latched on with nothing mounted afterward to ever undo it. Same fix,
            // same reasoning as hide() below: release first, unconditionally, before anything else.
            topology.setPrimaryUiSuppressed(false)
            candidate.revoke(leaseId)
            if (mounted === candidate) mounted = null
            if (leaving === candidate) leaving = null
            candidate.dispose()
        }) {
            is PanelAttachResult.Attached -> {
                candidate.bind(result.lease)
                mounted = candidate
                // Unconditional: `mounted` was just assigned on the line above, and nothing can run
                // between that assignment and this claim. PanelLeaseOwner.attach only registers a
                // candidate's onRevoked (the closure above) AFTER add(resource) succeeds, and returns
                // immediately after -- so no revocation of THIS candidate can fire before we get
                // here. The one revocation reachable during attachPrimaryPanel is of the PREVIOUS
                // lease (attach()'s own revokeCurrent(REPLACED), which runs before the new entry --
                // and this candidate -- even exists), and that closure closes over the previous
                // candidate, so it cannot touch this candidate's `mounted` assignment. Every path
                // here is main-confined (attachPanel rejects off-thread callers), so there is no
                // interleaving left to guard against.
                topology.setPrimaryUiSuppressed(true)
                mainHandler.post { if (candidate.attached) view.requestFocus() }
                true
            }
            is PanelAttachResult.Rejected -> {
                candidate.dispose()
                // Defensive: a rejected attach never suppressed, but releasing here costs nothing
                // and keeps "no mounted panel" and "not suppressed" the same state on every exit.
                topology.setPrimaryUiSuppressed(false)
                false
            }
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::hide)
            return
        }
        // Release suppression FIRST and unconditionally. This call used to sit at the END of hide(),
        // after the early-return guard below. Once suppression leaked -- e.g. from the revoke
        // closure above, before it was fixed to release first too -- `mounted` was already null, so
        // the guard returned and the one call that would have cleared suppression never ran --
        // leaving the M button permanently unreachable, because MButtonRuntime.notePrimaryInteraction
        // returns early while suppressed and no touch can reveal it. Releasing is idempotent and safe
        // with nothing mounted, so it belongs up here.
        topology.setPrimaryUiSuppressed(false)
        val current = mounted ?: return
        mounted = null
        // RC-38 (audit 2026-10-14): the window is not removed on this frame. Its body fades out on OverlayMs with input dead, and
        // finishExit takes it down when the fade has ended (or the leak guard fires). Hiding a panel already leaving is a no-op.
        leaving = current
        beginExit(current)
    }

    /** Starts [current]'s exit: the panel's own input is over from this frame, and its window fades out. */
    private fun beginExit(current: MountedPanel) {
        if (!current.exit.begin()) return
        // The window stays up to fade, but is neither focused nor touchable from this frame (W1C #1); the swallowing below it is the backstop.
        overlay.releasePrimaryPanelInput()
        current.inputRouter.reset()
    }

    /**
     * Takes the window down at once, with no fade: what [show] needs, because a panel that is still leaving must not stand beside
     * the one replacing it. Finishes the leaving panel and the mounted one, if any.
     */
    private fun hideNow() {
        topology.setPrimaryUiSuppressed(false)
        leaving?.let(::finishExit)
        val current = mounted ?: return
        mounted = null
        finishExit(current)
    }

    /** The end of a panel's life: its window detached (whatever lease it holds), its composition and input disposed. Idempotent. */
    private fun finishExit(current: MountedPanel) {
        if (leaving === current) leaving = null
        current.lease?.detach()
        current.markDetached()
        current.dispose()
    }
}
