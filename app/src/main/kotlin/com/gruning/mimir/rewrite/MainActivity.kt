package com.gruning.mimir.rewrite

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.MimirAudioFocus
import com.gruning.mimir.rewrite.core.audio.MimirFrontendMusic
import com.gruning.mimir.rewrite.core.audio.MimirMusicLibrary
import com.gruning.mimir.rewrite.core.audio.MimirSoundBank
import com.gruning.mimir.rewrite.core.audio.MimirSoundEvent
import com.gruning.mimir.rewrite.core.audio.MimirUiSounds
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.AndroidExternalForegroundMonitor
import com.gruning.mimir.rewrite.core.companionnative.AndroidSecondaryTaskRegistry
import com.gruning.mimir.rewrite.core.companionnative.BottomPadInput
import com.gruning.mimir.rewrite.core.companionnative.ResumedHostActivity
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.OverlayScreenActivity
import com.gruning.mimir.rewrite.core.companionnative.PadIdleGate
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplayState
import com.gruning.mimir.rewrite.core.companionnative.idleWakeSamples
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import com.gruning.mimir.rewrite.core.model.jobs.JobProgressState
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailure
import com.gruning.mimir.rewrite.core.resolver.library.LibraryStore
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import com.gruning.mimir.rewrite.core.ui.LocalMimirKeyboardHapticFeedback
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.UUID

/** Headroom over the splash's own budget: enough that scheduling jitter cannot invert the ordering. */
internal const val WelcomeHoldBackstopMarginMs = 500L

/**
 * Welcome-hold input-gate backstop: must exceed the welcome's MAX visual hold so the gate never
 * opens under the splash. The composable is the real disarm; this is only the last-resort safety if
 * it never runs.
 *
 * O11b (2026-09-12): the budget alone could not keep that promise, because the two are measured from
 * different moments. The backstop is posted on the decor view before attach, and `View.postDelayed`
 * starts this delay at attach; the splash measures its hold from its own LaunchedEffect, after the first
 * frame (about 1 s on the Thor, O11). The backstop now also stands down while a splash surface is
 * composed ([WelcomeHold.releaseFromBackstop]), so this value matters only when no splash has composed.
 *
 * PERFE-01 (2026-07-19): this was a hand-typed `9_000L` beside a comment claiming the hold was
 * "the 3.2s minimum beat plus the ~5s library-ready linger". The linger cap was 8s, not ~5s, so the
 * real worst case was 11.2s and this backstop released the input gate up to 2.2s BEFORE the splash
 * could come down — input landing on a live shell the user could not see. It is now DERIVED from
 * [WelcomeHoldMaxMs] rather than duplicating it, so the two cannot drift apart again, and
 * `WelcomeHoldContractTest` asserts the production value directly.
 */
internal const val WelcomeHoldBackstopMs = WelcomeHoldMaxMs + WelcomeHoldBackstopMarginMs

/**
 * Mimir Alpha — the Compose app entry point.
 *
 * Hosts the Compose shell ([MimirApp]): provides the single
 * [com.gruning.mimir.rewrite.core.input.FocusAuthority] for the window and opens on the Library shelf
 * (Onboarding on a first run); release configuration lives in the six-category Settings surface.
 *
 * ### Controller input
 * The Activity is the raw-input source. It owns one [InputRouter], normalizes every window key/motion
 * event through the single [com.gruning.mimir.rewrite.core.input.InputAuthority] (dual-source KEY + AXIS
 * coalescing), and hands the resulting logical action to whichever Compose screen owns navigation. This
 * is where D-pad input becomes real focus movement — directional input reaches the FocusAuthority ONLY
 * through this path, and the platform's own focus traversal is never invoked (the "one focus authority"
 * ruling made structural).
 *
 * Runtime posture: offline-first; the only network use is metadata-scoped INTERNET — DAT/ID catalog
 * refresh, never content/BIOS/firmware (see AndroidManifest — the Diagnostics screen reads the actual
 * declared permissions and proves it). App id `com.gruning.mimir.rewrite` installs side-by-side with the
 * shipping `com.gruning.mimir` and never touches its data.
 */
class MainActivity : MimirLocaleActivity() {

    private val debugIntentHookGate = DebugIntentHookGate(BuildConfig.DEBUG)
    private val interactionMotionGate = CompanionProcessRuntime.motionGate
    private val controllerNavigationActive = MutableStateFlow(false)
    private var controllerNavigationClearJob: Job? = null
    private val utilityHaptics by lazy { AndroidMimirHaptics(this) }
    private val keyboardHapticFeedback: () -> Unit = { utilityHaptics.preview() }
    private val inputRouter = InputRouter(
        interactionRoot = InteractionRoot.PRIMARY,
        onControllerNavigation = ::markControllerNavigationActive,
        interactionMotionGate = interactionMotionGate,
        // Keep the lazy Activity-backed adapter deferred until after ComponentActivity.attach/onCreate.
        onNavTick = {
            utilityHaptics.navigationTick()
            MimirUiSounds.play(MimirSoundEvent.NavFocusMove)
        },
        // BM01: main-thread ticker + uptime clock for held-HAT repeat synthesis (see InputRouter).
        hatRepeatTicker = { delayMs, tick ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val runnable = java.lang.Runnable(tick)
            handler.postDelayed(runnable, delayMs)
            ({ handler.removeCallbacks(runnable) })
        },
        hatRepeatClock = { android.os.SystemClock.uptimeMillis() },
        // Walk-32b: a keyboard's A waits until the frame has read the hat (InputRouter.confirmWaitsForPadFrame). A frame
        // callback runs after the frame's input stage, which is where Android hands over batched joystick motion.
        padFrameScheduler = { block -> android.view.Choreographer.getInstance().postFrameCallback { block() } },
    )
    private var primaryMotionRoot: RootLease? = null
    private var primaryTouchLease: InteractionLease? = null
    private var screenIdleController: MimirScreenIdleController? = null
    /** Round-5 triage B2: this instance's overlay screen-activity observer; teardown removes its own, never a successor's. */
    private var overlayScreenActivityObserver: ((OverlayScreenActivity) -> Unit)? = null
    private var idleWakeTouchLatched = false

    /**
     * The exact lambdas this instance hands to [MimirMainInputSink] while resumed, so `onPause` can take
     * back its own and not a successor's — a successor's `onResume` can run before this instance's
     * `onPause`, and an unconditional clear there would leave display 4 absorbing everything into a
     * silence nothing reports. Same guard as the [ControllerMappingStore] release in `onDestroy`.
     * Held as `val`s because a fresh `::method` reference is a new object every time it is taken, which
     * would make the identity comparison in `release` never match.
     */
    private val secondaryDisplayKeySink: (KeyEvent) -> Boolean = { routeKeyEventThroughAuthority(it) }
    private val secondaryDisplayMotionSink: (MotionEvent) -> Boolean =
        { routeGenericMotionEventThroughAuthority(it) }

    /** The idle controller's user-interaction answer, for the Bottom pad routes that run ahead of the sink above; registered and released with it (same identity guard). */
    private val secondaryDisplayInteraction: () -> Boolean = { screenIdleController?.onUserInteraction() == true }

    /**
     * Main's pad wake rule for display 0 and for the keys and motion the display-4 floor forwards here: the one [PadIdleGate] the Bottom's Activity page and ᛗ menu pads also run
     * through. A pad press re-arms the idle controller; the press that wakes a dimmed screen is eaten for its own events (its key up, or the hat back at rest in any sample of a
     * batch) and the router is reset once, at the wake; every other event goes on to the router, and a motion batch only with the samples the gate did not eat. Pad ownership is
     * the Bottom's ([BottomPadInput.ownerEpoch]): when the ᛗ menu takes the pad or gives it back while the wake press is held (it takes the release, and Main keeps focus), the
     * latch is dropped, so an unrelated press that follows is Main's. The Activity page's route taking or giving back the pad moves only [BottomPadInput.holdEpoch], which starts the
     * idle hold cap's credit fresh and never touches the latch.
     */
    private val padIdleGate = PadIdleGate(
        interaction = secondaryDisplayInteraction,
        onWake = { inputRouter.reset() },
        ownership = { BottomPadInput.ownerEpoch },
        holdBoundary = { BottomPadInput.holdEpoch },
    )

    /** The welcome-hold input-gate backstop — instance-scoped so onDestroy can cancel it (a stale one
     *  firing into a recreated instance's replayed beat would un-gate a still-masked shelf). It opens the gate
     *  only when no splash surface is composed ([WelcomeHold.releaseFromBackstop]); a composed one opens it itself. */
    private val welcomeHoldBackstop = Runnable { WelcomeHold.releaseFromBackstop() }

    private val staleMainTaskSweep = Runnable { removeStaleMainTasks(this) }

    /** OUR dual-screen yield hook (HP-2), identity-guarded across stacked Activity recreation. */
    private var dualScreenLaunchListener: ((String) -> Unit)? = null

    /**
     * Mounts the companion surface on the secondary display (M7). Created unconditionally in onCreate —
     * on a single-screen device it simply never mounts a Presentation. Null only outside onCreate–onDestroy.
     */
    private var companionController: CompanionDisplayController? = null
    private var companionOverlayController: AndroidCompanionOverlayController? = null
    private var mButtonTopologyController: MButtonTopologyController? = null
    private var mButtonActionRouter: MButtonProcessActionRouter? = null
    private var secondaryOverlayActionLease: UUID? = null
    // CE02: hosts the tab drawer in the overlay layer over a foreground foreign app (no reclaim = no app exit).
    private var recentsOverlayHost: CompanionRecentsOverlayHost? = null
    private var secondaryTaskRegistry: AndroidSecondaryTaskRegistry? = null
    private var foregroundMonitor: AndroidExternalForegroundMonitor? = null
    private var recentsCloseHandler: ((String, UUID) -> Unit)? = null
    private var recentsCloseOperationId: UUID? = null
    /**
     * The exact callback this Activity handed to [ControllerMappingStore], kept so `onDestroy` can take
     * back its own and not a successor's. See the release in `onDestroy`.
     */
    private var controllerMappingAppliedListener: (() -> Unit)? = null
    private var runningAppTransferJob: Job? = null
    private var gameSessionReturnJob: Job? = null
    private var gameDispatchWatchdogJob: Job? = null
    private var gameDispatchWatchdogOperation: MimirGameSessionOperation? = null
    private var primaryAppSessionReturnJob: Job? = null
    // CR-11: package → decoded icon file for the tab manager, from the shelf snapshot. Built off-thread when
    // the drawer opens so the live state-collector can map tabs without touching disk on the main thread.
    @Volatile private var secondaryTabIconPaths: Map<String, String> = emptyMap()

    /**
     * DEV proof harness for the REAL SAF library pick. Registered here (unconditionally, before STARTED)
     * so `mimir.pickFolder` can fire the SAME `ACTION_OPEN_DOCUMENT_TREE` picker + `takePersistableUriPermission`
     * + `repository.rescan(tree)` path the product UI uses (see [MimirApp] `onRescanFolder`), certifying it
     * on the real SD-card ROM tree without driving the Compose "Add folder" affordance blind. On grant it
     * logs the honest [com.gruning.mimir.rewrite.core.model.library.ScanResult] + per-platform counts under
     * `MimirLibraryProbe`. Local-only; index-in-place; no move/copy.
     */
    private val pickFolderProbe =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri == null) {
                Log.w("MimirLibraryProbe", "folder pick cancelled")
                return@registerForActivityResult
            }
            val access = persistOnboardingTreeAccess(this, treeUri)
            if (!access.write) {
                Log.w(
                    "MimirLibraryProbe",
                    "grant persisted WITHOUT write (read=${access.read}) — media_art enrichment will be blocked",
                )
            }
            lifecycleScope.launch {
                runCatching {
                    val repo = LibraryStore.repository(this@MainActivity)
                    val result = repo.rescan(treeUri.toString())
                    val platforms = repo.observePlatforms().first()
                    Log.i(
                        "MimirLibraryProbe",
                        "REAL SCAN $treeUri -> $result | platforms=${platforms.size} [" +
                            platforms.joinToString { "${it.platformId.value}:${it.gameCount}" } + "]",
                    )
                }.onFailure { Log.e("MimirLibraryProbe", "real scan failed", it) }
            }
        }

    /**
     * The REAL repair path for a SAF tree grant the app no longer holds (see `StorageGrantAudit.kt` for
     * the Nova 2026-09-11 evidence). Registered unconditionally before STARTED, exactly like
     * [pickFolderProbe] beside it, because an `ActivityResultLauncher` may only be created before the
     * Activity is started — it is NOT a debug hook, it is the only control `RECONNECT` has.
     *
     * The launcher's input is the LOST tree URI, which `ActivityResultContracts.OpenDocumentTree` puts in
     * `DocumentsContract.EXTRA_INITIAL_URI`: the system picker opens ON the folder that was lost, so the
     * player re-picks it rather than hunting for it. On a result the grant is persisted with the same
     * read|write-then-read-only fallback onboarding already uses ([persistOnboardingTreeAccess]), and the
     * audit is re-run against live truth — a pick that does not cover the lost root leaves the root
     * uncovered, so the prompt stays up on its own.
     */
    private val pickStorageRegrant =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            val recovery = storageGrantRecovery ?: return@registerForActivityResult
            if (recovery.pendingRootUri == null) return@registerForActivityResult
            if (treeUri == null) {
                recovery.onPickerResult(null)
                return@registerForActivityResult
            }
            val access = persistOnboardingTreeAccess(this, treeUri)
            if (!access.read) {
                Log.w(
                    StorageGrantLogTag,
                    "regrant persisted WITHOUT read (write=${access.write}) — the root stays unreadable",
                )
            }
            recovery.onPickerResult(treeUri.toString())
        }

    /**
     * Background-feature notification consent: Android's notification-permission dialog, asked at most once, when a background
     * feature is first turned on. A field for the reason [pickStorageRegrant] is one: a launcher cannot be registered
     * after STARTED. Every decision lives in [MimirNotificationPermissionAsk]; the answer goes back there and changes
     * nothing but the log, so a decline leaves the performance overlay and the M button running.
     */
    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            MimirNotificationPermissionAsk.onPermissionResult(this, granted)
        }

    /**
     * The only way [MimirNotificationPermissionAsk] reaches the screen. In front on Main is RESUMED on display 0: a
     * paused or stopped MainActivity is one a game, an app or a Settings screen is covering, and an instance on
     * another display is not Home on Main.
     */
    private val notificationAskHost = object : NotificationAskHost {
        override fun inFrontOnMain(): Boolean =
            !isFinishing &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                window?.decorView?.display?.displayId == Display.DEFAULT_DISPLAY

        override fun launchNotificationPermissionRequest(): Boolean = runCatching {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.isSuccess
    }

    private var storageGrantRecovery: StorageGrantRecovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // O11b cold-start timeline (behaviour-neutral, MimirColdStartTrace): this Activity's start on the one clock.
        MimirColdStartTrace.mark("activity create", "restored=${savedInstanceState != null}")
        // Lane mem (2026-10-14): the player is back; a Tweaker bridge start this process deferred after a lowmemorykiller
        // recovery runs now rather than at its deadline.
        MimirLowMemoryRecovery.onMainActivityCreated()
        // **The HUD does not listen to Mimir's UI, and there is no FrameMetrics listener here.**
        //
        // UI-frame sampling was removed; the overlay reads the foreground game's telemetry instead.
        //
        // What used to be here attached `Window.OnFrameMetricsAvailableListener` in `onCreate` and
        // released it in `onDestroy`, so the framework captured and marshalled a `FrameMetrics` for
        // every frame Mimir rendered, for the whole activity lifetime, including with the overlay
        // switched OFF. That much is measured, not inferred: device QA read the listener's
        // `MimirFrameMetrics` HandlerThread directly out of `/proc` and found it present on all three
        // handhelds before the change and absent after.
        //
        // **The frame-time COST of it is retracted — see the KDoc on `MimirFrameCountingGateTest`.**
        // The removal stands on the ruling above, not on a number.
        //
        // It is removed rather than scoped because the cell could never show a game's frame rate
        // anyway: Mimir is stopped while a game is foreground, and the kernel's per-CRTC
        // `measured_fps` cannot answer either — it is the panel's composited present rate and reads 60
        // with Mimir force-stopped. What the removal is worth is an always-on thread and whatever
        // battery it draws, which is UNQUANTIFIED.
        //
        // The FPS cell reads "—" whenever the overlay has no fresh sample of the foreground game's own
        // frame rate from the privileged utility (PerformanceOverlayService.readForegroundGameFrameRate),
        // the per-game source once OWED here. A dash is true; a number about the wrong subject was not.
        // **Before the reconcile, and that ordering is the entire point.** On a device where the
        // deterministic adoption proof has been armed, this points the discovered tuner at a synthetic
        // axis made of ordinary files; the cold-start reconcile two statements below must then run
        // against that axis rather than against the real one it would otherwise have discovered. A seam
        // installed after the reconcile is a seam the reconcile never saw. On every unarmed device --
        // which is every device nobody has explicitly armed with an adb extra behind the debug gate --
        // this returns immediately, and in a release build it is an empty method with no body to run.
        LabTunerAxisInstaller.installIfArmed(this)
        // Policy R2 (2026-09-01): the daemon can now be holding the record of what this device
        // was tuned FROM, so the cold-start withdraw asks it before it widens anything. On a device
        // with no Tweaker to ask, this is the same synchronous `releaseAnyOrphanedCap()` call it has
        // always been -- see TweakerBridge.reconcileDiscoveredTunerColdStart for the full ordering.
        TweakerBridge.reconcileDiscoveredTunerColdStart()
        ControllerMappingStore.initialize(this)
        // Held in a field because `onApplied` lives on a process-lifetime `object` and this callback
        // reaches back into the Activity: `inputRouter::reset` -> `inputRouter` -> closures assigned
        // just below that capture `this@MainActivity`. The reference is what lets `onDestroy` release
        // exactly this one.
        val controllerMappingApplied: () -> Unit = inputRouter::reset
        controllerMappingAppliedListener = controllerMappingApplied
        ControllerMappingStore.onApplied = controllerMappingApplied
        inputRouter.controllerMappingOverlayActive = {
            ControllerMappingStore.snapshot.value.open
        }
        inputRouter.controllerMappingCaptureActive = {
            ControllerMappingStore.snapshot.value.pendingAction != null
        }
        inputRouter.captureControllerMapping = ControllerMappingStore::capture
        inputRouter.captureControllerMappingKeys = ControllerMappingStore::capture
        inputRouter.cancelControllerMappingCapture = ControllerMappingStore::cancelCapture
        ControllerShortcutStore.initialize(this)
        inputRouter.controllerShortcutCaptureActive = {
            ControllerShortcutStore.snapshot.value.pendingAction != null
        }
        inputRouter.captureControllerShortcut = { key ->
            val result = ControllerShortcutStore.capture(this, key)
            result.message?.let { message ->
                showMimirMessage(this, message, MimirMessageClass.Guidance)
            }
            result.consumed
        }
        inputRouter.resolveControllerShortcut = { key ->
            ControllerShortcutStore.resolve(this, key)
        }
        registerTweakerPackageMonitor()
        lifecycleScope.launch { TweakerBridge.refreshCapabilities() }
        // Anbernic Stage A (debug builds only). One fixed, read-only identity check through the
        // stock Anbernic shell gate (see AnbernicShellProbe's KDoc), run from Mimir's own process for the first time; a
        // no-op on any device without /system/xbin/anbernicsu. Its one MimirAnbernicProbe line answers whether the app's
        // domain gets the shell access the Tweaker needs, before any write path is designed.
        if (BuildConfig.DEBUG) lifecycleScope.launch { AnbernicShellProbe.runIfAvailable() }
        val activityStateRestored = savedInstanceState != null
        val sameProcessRecreation = savedInstanceState
            ?.getString(SavedProcessEpochKey)
            ?.let { it == CompanionProcessRuntime.processEpoch }
            ?: false
        primaryMotionRoot = interactionMotionGate.registerRoot(
            kind = InteractionRoot.PRIMARY,
            initiallyVisible = false,
        )
        enterFullscreenMode()
        // Load the persisted appearance BEFORE first composition so the app opens already wearing the
        // saved Norse theme (no default→saved flash).
        ThemeStore.load(this)
        // Load the persisted gamelist backdrop ("Bifröst") mode before first composition (same prefs file).
        BackdropStore.load(this)
        // Load the persisted profile display name before first composition (Profile row shows it live).
        ProfileNameStore.load(this)
        // The companion 3DS-shelf layout (order / cursor / page) — read before the Presentation can compose.
        CompanionOrderStore.load(this)
        // The per-platform game order screen 2 files with its own hands . Loaded
        // beside its sibling and for the same reason: the Presentation is rebuilt on every yield/reclaim,
        // so nothing inside that composition can hold it.
        CompanionShelfGameOrderStore.load(this)
        // CR-1: if a reclaim hold ever hits its deadline without a fresh push, say so out loud.
        CompanionState.onHoldExpired = {
            Log.w("MimirCompanionNative", "reclaim hold expired without a fresh push — last stable restored")
        }
        // Load the per-platform default emulators + wire the launch engine to read them (below a per-game
        // Open-With override, above Auto) — so Settings › Emulators › Change actually changes what launches.
        PlatformEmulatorDefaultStore.load(this)
        QuickLaunchStore.load(this)
        // the user's own SteamGridDB key (entered in Settings/onboarding) drives art fetch.
        SteamGridDbKeyStore.load(this)
        // Load the persisted UI-sound preference (Phase 1b audio wiring) before first composition.
        AudioPreferences.load(this)
        settleLaunchSoundStep(this) // the launch-sound level the Music row shows is the level the intro plays
        // Settings > Music: the Custom music folder choice and its last index (read off the main thread), after the preferences.
        MimirMusicLibrary.load(this, AppMusicPlatformFolders)
        // AP01: the persisted appearance overrides (Default accent stop + per-theme focus-ring stops)
        // must be in force before the first composition resolves the palette.
        AppearanceOverrideStore.load(this)
        DatabaseStartupGateState.failure?.let { failure ->
            showDatabaseRecoveryGate(failure)
            return
        }
        // Attach only after the database gate; Android restores the registered picker result on start.
        val appContext = applicationContext
        storageGrantRecovery = StorageGrantRecovery(
            scope = lifecycleScope,
            readSnapshot = { readStorageGrantSnapshot(appContext) },
            openPicker = { lostRootUri -> pickStorageRegrant.launch(android.net.Uri.parse(lostRootUri)) },
            pendingRootUri = savedInstanceState?.getString(StorageGrantPendingRootKey),
        ).also {
            StorageGrantAuditCoordinator.attach(it)
            it.audit()
        }
        // O13 : the notification ask reaches the screen only through this Activity, and only
        // after the database gate above. While RESUMED, every session revision re-reads the ledger: a game that just
        // quit is still RETURNING when Home resumes and ENDS a beat later, which is when a deferred ask may show.
        MimirNotificationPermissionAsk.attachHost(notificationAskHost)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(
                    MimirSessionAuthority.state,
                    MimirNotificationPermissionAsk.pending(this@MainActivity),
                ) { _, _ -> Unit }.collect {
                    runCatching { MimirNotificationPermissionAsk.onHomeInFront(this@MainActivity) }
                        .onFailure { failure -> Log.w(NotificationAskLogTag, "deferred ask check failed", failure) }
                }
            }
        }
        // AU02: the media-bed environment holder (courtesy gate for the Bifröst clip audio) — cache the
        // AudioManager once; the backdrop refreshes the live reading at each clip bind.
        com.gruning.mimir.rewrite.core.audio.MimirMediaBed.attach(this)
        // Install the one process-wide UI-sound engine (preloaded once) so coordinators outside this
        // Activity's scope can play cues too. Idempotent across Activity recreation.
        MimirUiSounds.install(this, MimirSoundBank.resources)
        // Clean-room frontend music. Decode/playback lives on its own worker and remains gated to
        // visible Home, foreground Mimir, courtesy-safe audio, and no competing Hero Motion. The bed
        // is chosen from the theme ThemeStore.load() above already resolved, so a Hel install opens
        // on "Fallen Hero" directly rather than starting the ambient piece and swapping a beat later.
        MimirFrontendMusic.install(this, mimirFrontendBedFor(ThemeStore.themeId.value))
        com.gruning.mimir.rewrite.core.launch.LaunchStore.platformDefaultProvider =
            { platformId -> PlatformEmulatorDefaultStore.defaultFor(platformId) }
        // CR-2 : a DS/3DS launch no longer YIELDS screen 2. The old latch
        // suppressed the companion speculatively on platform id, and Android exposes no per-display
        // foreground signal for a dispatcher-launched app, so the guess was the only input we had and it was
        // wrong as a latch. Screen 2 now holds the static NOW_PLAYING the payload push already sent (Mimir is
        // still foreground at dispatch); action routing remains available and consults live runtime/session
        // authority when an operation needs ownership.
        // Corrected 2026-10-09 : the original reason said melonDS/Azahar draw BOTH DS screens on Main.
        // They do not. Each adds its own window on the Bottom, the same second-screen window type Mimir's
        // Presentation uses (MimirCompanionPresentation), created later and so above Mimir's. A foreign
        // window does land on screen 2; it never yields it, and Mimir's window stays beneath it. Anything
        // Mimir must show on the Bottom meanwhile is an overlay window lent on that display (the Bottom ᛗ's
        // Utility: BottomUtilityPanelHost), which sits above those windows.
        dualScreenLaunchListener = { pkg: String ->
            Log.i("MimirCompanionNative", "dual-screen game session tracked pkg=$pkg")
            CompanionState.dualScreenGameActive = true
        }.also { com.gruning.mimir.rewrite.core.launch.LaunchStore.dualScreenLaunchListener = it }
        // Reconcile provider-neutral consent, the ScreenScraper provider record, and its encrypted credential
        // in one close-first restore before any explicit client can run. ConnectedArtStore is the only
        // authority that opens MimirConnectedArtGate; the renderer remains local-only for all providers.
        ConnectedArtStore.restore(this)
        // RA connection truth depends on that provider-neutral reconciliation. Publish the username only
        // after both durable authorities agree; the key still decrypts lazily at the explicit client call.
        RetroAchievementsCredentialStore.restore(this)
        // Install the LOCAL-ONLY Coil image loader as the process singleton. Provider consent can authorize
        // an explicit bounded fetch into local staging; it never authorizes render/navigation-time egress.
        coil.Coil.setImageLoader(buildLocalOnlyImageLoader(this))
        // Production identity: repair legacy disc-tagged multi-disc stamps, then resolve canonical titles in
        // the background whenever games are unresolved (after any scan / on launch); the shelf updates live.
        IdentityEnrichmentCoordinator.start(this)
        // SD02: watch storage volumes — eject flips affected games to STORAGE_UNAVAILABLE, same-card
        // return restores them; startup pass covers a swap that happened while Mimir was dead.
        StorageVolumeMonitor.start(this)
        runDebugIntentHooks()
        // Mount the companion on the secondary display when one is present (M7); it renders the live
        // CompanionState read-model. Single-screen devices simply never show it.
        // Process SINGLETON (adversarial review 2026-07-10): one-per-activity controllers raced across
        // recreation and leaked stacked M windows (two dead M-buttons shadowing the live one on-device).
        val processServices = CompanionProcessRuntime.services(applicationContext)
        LaunchInjection.installTargetReaper(processServices.privilegedUtility)
        // W2f: Mimir Keyboard as the system default -- once per process, off the main thread, ON by default
        // (a stored OFF, a switch-away or the safety revert keeps it off).
        MimirKeyboardDefaultRuntime.startOnce(applicationContext, processServices.privilegedUtility)
        PerformanceOverlaySettingsStore.initialize(this)
        applyPerformanceOverlayLiveDecision()
        lifecycleScope.launch {
            MimirSessionAuthority.state.collect { snapshot ->
                val session = snapshot.gameSession
                if (session?.phase == MimirSessionPhase.DISPATCHED && session.placement == MimirGamePlacement.MAIN) {
                    armGameDispatchWatchdog(session)
                } else {
                    cancelGameDispatchWatchdog()
                }
                updateOwnMirrorDrawerEdgeBack(CompanionReturnCoordinator.runtime().snapshot.value)
            }
        }
        // R6-P2-08: opening or closing the Bottom shelf's own folder ("Tome") changes nothing else this
        // file already collects, so it needs its own trigger to promptly (re)arm the CP03 edge-back strip.
        lifecycleScope.launch {
            CompanionBottomFolderState.open.collect {
                updateOwnMirrorDrawerEdgeBack(CompanionReturnCoordinator.runtime().snapshot.value)
            }
        }
        consumeMButtonCommandIntent(intent)
        companionOverlayController = processServices.overlay
        companionOverlayController?.setSecondaryReturnAvailable(
            companionSecondaryReturnAvailable(CompanionReturnCoordinator.runtime().snapshot.value),
        )
        mButtonTopologyController = processServices.mButtonTopology
        applyPrimaryOrientation(processServices.mButtonTopology.primarySurfaceAvailable.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                processServices.mButtonTopology.primarySurfaceAvailable.collect(::applyPrimaryOrientation)
            }
        }
        mButtonActionRouter = processServices.mButtonActionRouter
        secondaryOverlayActionLease = processServices.mButtonActionRouter
            .bindSecondaryHandler(::handleCompanionOverlayAction)
        secondaryTaskRegistry = processServices.taskRegistry
        // The Bottom game's tab is created by the registry at dispatch, from the Intent alone, so it knows
        // the emulator and nothing else. This is the seam the session ledger names it through once the
        // library row is resolved; without it the tab keeps its emulator label, which is the fallback.
        MimirBottomGameTabs.noteGameTab = { pkg, title, gameId, artRef ->
            runCatching { processServices.taskRegistry.noteGameTab(pkg, title, gameId, artRef) }
        }
        MimirBottomGameTabs.forgetGameTab = { pkg ->
            runCatching { processServices.taskRegistry.forget(pkg) }
        }
        foregroundMonitor = processServices.foregroundMonitor
        // Your music plays everywhere: the game-in-front probe, the other-player bridge and the one notification ask (core-audio's
        // MimirMusicControl cannot see the session ledger or the foreground monitor). Idempotent across recreation.
        YourMusicEverywhere.install(this, processServices.foregroundMonitor)
        // CE02: the tab drawer host, hosted in the overlay layer so Recents over a foreign app doesn't reclaim.
        recentsOverlayHost = companionOverlayController?.let {
            CompanionRecentsOverlayHost(
                applicationContext,
                it,
                this,
                interactionMotionGate,
                isOverlayEligible = {
                    val snapshot = CompanionReturnCoordinator.runtime().snapshot.value
                    companionDrawerHostSelection(
                        snapshot = snapshot,
                        primaryActivityStopped = PrimaryActivityObservationState.stopped.value,
                        dualScreenGameLive = companionMainDualScreenGameActive(MimirSessionAuthority.state.value.gameSession),
                    ) == CompanionDrawerHostSelection.OVERLAY
                },
            )
        }
        // Every companion launch is process-owned by CompanionSecondaryLaunchCoordinator. Activity
        // recreation can no longer detach quicklaunch or replace its exact-token transaction handler.
        //  the ✕ is an honest "remove tab + best-effort reap". A third-party launcher can
        // neither force-stop a foreign package nor remove a foreign task, so the ✕ promises only what the
        // platform permits: the tab leaves the drawer NOW (forget), and the process reap is best-effort.
        // For the visible screen-2 host the display is reclaimed FIRST, so the target drops out of
        // visibility — background importance is the only state killBackgroundProcesses can touch. The old
        // route (visible ✕ → move-to-primary; background ✕ → kill that parks the tab in "Close unconfirmed"
        // forever with a dead ✕) closed nothing and removed nothing.
        recentsCloseHandler = handler@{ pkg: String, closeOperationId: UUID ->
            recentsCloseOperationId = closeOperationId
            val closeFailed = {
                CompanionRecentsState.failClose(
                    operationId = closeOperationId,
                    packageName = pkg,
                    message = "The tab stayed open. Try again.",
                )
            }
            val closeCleared = {
                CompanionRecentsState.clearClose(closeOperationId)
            }
            val runtimeSnapshot = CompanionReturnCoordinator.runtime().snapshot.value
            // L-6 (policy, review finding D8): refuse in RecoveryNeeded. The ✕ is HIDDEN there,
            // so this should be unreachable from the drawer -- it is here because the handler is also
            // the process-wide close entry point, and a control that cannot honour its promise must
            // not honour half of it either (remove the tab, change nothing else).
            if (!companionTabCloseIsOffered(runtimeSnapshot)) {
                closeFailed()
                return@handler
            }
            val yielded = runtimeSnapshot.disposition
                as? SecondaryDisplayDisposition.Yielded
            // FUX-03 (reported YouTube close/reclaim failure): the runtime's reclaim gate
            // compares packages ignoreCase; this gate compared with == and could refuse the restore
            // for the exact host being closed. Match the runtime's comparison, then retain the exact
            // host root until the display-0 reclaim is visibly confirmed.
            val hostOperationId = yielded?.lease
                ?.takeIf { it.packageName.equals(pkg, ignoreCase = true) }
                ?.operationId
            val reclaimRequested = hostOperationId != null && runCatching {
                companionController?.restore("tab_close_reclaim", returningFromPackage = pkg) == true
            }.getOrDefault(false)
            if (hostOperationId != null && !reclaimRequested) {
                closeFailed()
                return@handler
            }
            lifecycleScope.launch {
                try {
                    val runtime = CompanionReturnCoordinator.runtime()
                    val reclaimOperationId = when (val disposition = runtime.snapshot.value.disposition) {
                        is SecondaryDisplayDisposition.ReclaimPending -> {
                            if (hostOperationId != null &&
                                disposition.lease.parentHostOperationId != hostOperationId
                            ) {
                                // A replacement host owns the package now; the old close cannot report
                                // into that newer operation.
                                closeCleared()
                                return@launch
                            }
                            disposition.lease.operationId
                        }
                        is SecondaryDisplayDisposition.MirrorPresented -> disposition.attempt.parentDispositionId
                        else -> null
                    }
                    // A visible host must have produced a correlated reclaim root. A dispatch that
                    // has not entered ReclaimPending is not enough to forget its selected tab. Report
                    // failure only while the original yielded host is still current; otherwise this
                    // close operation was superseded and its status must not reach the replacement.
                    if (hostOperationId != null && reclaimOperationId == null) {
                        val currentHost = (runtime.snapshot.value.disposition as? SecondaryDisplayDisposition.Yielded)
                            ?.lease
                        if (currentHost?.operationId == hostOperationId) closeFailed() else closeCleared()
                        return@launch
                    }
                    val reclaimConfirmed = if (reclaimOperationId != null) {
                        withTimeoutOrNull(5_000L) {
                            runtime.snapshot.first { snapshot ->
                                val mirror = snapshot.disposition as? SecondaryDisplayDisposition.MirrorPresented
                                mirror != null &&
                                    mirror.attempt.parentDispositionId == reclaimOperationId &&
                                    snapshot.mirrorActive
                            }
                        } != null
                    } else {
                        true
                    }
                    // A timeout, failed reclaim, or newer same-package launch leaves the tab intact. The
                    // explicit close/reap occurs only after the exact reclaim root has settled.
                    val current = runtime.snapshot.value
                    if (hostOperationId == null) {
                        val currentPackage = when (val disposition = current.disposition) {
                            is SecondaryDisplayDisposition.Yielded -> disposition.lease?.packageName
                            is SecondaryDisplayDisposition.LaunchPending -> disposition.lease.packageName
                            else -> null
                        }
                        if (currentPackage?.equals(pkg, ignoreCase = true) == true) {
                            closeCleared()
                            return@launch
                        }
                    }
                    val sameConfirmedReclaim = reclaimOperationId == null || (
                        reclaimConfirmed &&
                            (current.disposition as? SecondaryDisplayDisposition.MirrorPresented)?.attempt
                                ?.parentDispositionId == reclaimOperationId &&
                            current.mirrorActive
                    )
                    if (!sameConfirmedReclaim) {
                        val currentReclaim = (current.disposition as? SecondaryDisplayDisposition.ReclaimPending)
                        val currentRecovery = (current.disposition as? SecondaryDisplayDisposition.RecoveryNeeded)
                        when {
                            currentReclaim?.lease?.operationId == reclaimOperationId -> closeFailed()
                            currentRecovery?.operationId == reclaimOperationId -> closeFailed()
                            current.disposition is SecondaryDisplayDisposition.NoDisplay &&
                                reclaimOperationId != null -> closeFailed()
                            else -> closeCleared()
                        }
                        return@launch
                    }
                    val closeResult = runCatching { secondaryTaskRegistry?.closeOne(pkg) }.getOrNull()
                    if (closeResult?.closeRequested == true) {
                        val forgotten = runCatching { secondaryTaskRegistry?.forget(pkg) }.getOrDefault(false)
                        if (forgotten == true) {
                            // The ✕ on a GAME tab ends the game, not just the tab: a Bottom game session
                            // holds Tweaker/audio posture and the Home return arm, and the native close
                            // settles none of it. No-op for an app tab, or for a game whose session already
                            // settled at the reclaim.
                            runCatching { MimirCompanionRuntimeSessionBridge.endBottomGameForTabClose(pkg) }
                            CompanionRecentsState.completeClose(closeOperationId, pkg)
                        } else {
                            closeFailed()
                        }
                    } else {
                        closeFailed()
                    }
                } finally {
                    // Cancellation during Activity teardown may only clear this operation. A terminal
                    // failure or a replacement close token survives the cleanup.
                    CompanionRecentsState.clearClose(closeOperationId)
                }
            }
            Unit
        }
        CompanionRecentsState.setCloseHandler(requireNotNull(recentsCloseHandler))
        companionController = CompanionDisplayController(
            activity = this,
            overlayController = companionOverlayController,
            secondaryDisplaySelector = processServices.secondaryDisplaySelector,
            onSecondaryTouchInteraction = { screenIdleController?.onUserInteraction() == true },
            onSecondaryMButtonInteraction = { processServices.mButtonTopology.noteSecondaryInteraction() },
        ).also {
            CompanionReturnCoordinator.bind(it)
            it.start()
        }
        // Cold-start loading cover: on a true cold start, screen 2 holds its themed loading cover
        // until the first seeded payload publishes over it (self-expiring, per the companion-hold posture) —
        // instead of visibly hydrating in stages while the primary sits behind the welcome mask. A
        // same-process re-create keeps its live payload untouched.
        if (!sameProcessRecreation) {
            // F3 (cold-launch settle audit, 2026-09-02): DERIVE the hold from the welcome budget instead of
            // taking holdForColdStart's 5 000 ms default. The primary's own warm cap is
            // WelcomeHoldMaxMs (currently ~11.2 s), so a fixed 5 s let screen 2 decay to idle while the
            // primary was still legitimately warming -- two clocks on one boot, which PERFE-01
            // (WelcomeBackLane.kt) already ruled must be expressed once and derived. Same
            // WelcomeHoldBackstopMarginMs the primary's own backstop uses.
            CompanionState.holdForColdStart(timeoutMs = WelcomeHoldMaxMs + WelcomeHoldBackstopMarginMs)
        }
        lifecycleScope.launch {
            ThemeStore.themeId.collect { themeId ->
                // Round 5d: the native ᛗ overlay follows ThemeStore itself, from process start (startCompanionOverlay),
                // so it wears the player's theme before any MainActivity exists. No second push here.
                // Hel keeps "Fallen Hero"; every other identity wears the ambient bed. Safe to churn
                // here: the Appearance picker commits and closes, so this fires on a real choice
                // rather than on every tile the gallery focuses.
                MimirFrontendMusic.setBed(mimirFrontendBedFor(themeId))
            }
        }
        val brightnessIdentity = currentBrightnessDeviceIdentity(this)
        val privilegedUtility = processServices.privilegedUtility
        val utilityPreferences = AndroidUtilityPreferenceStore(this)
        lateinit var idleController: MimirScreenIdleController
        val brightnessController = BrightnessController(
            settings = AndroidBrightnessSettingsGateway(this),
            preferences = AndroidWindowBrightnessPreferenceStore(this),
            identity = brightnessIdentity,
            secondaryWindowAvailable = {
                if (brightnessIdentity.hasAynSettingsContract) {
                    processServices.secondaryDisplaySelector.snapshot.value.soleEligibleId != null
                } else {
                    companionController?.secondaryWindow() != null
                }
            },
            windowSink = AndroidWindowBrightnessSink(
                primaryWindow = { window },
                secondaryWindow = { companionController?.secondaryWindow() },
            ),
            utilityPreferences = utilityPreferences,
            haptics = utilityHaptics,
            navSound = AndroidNavSound(this),
            ambientSound = AndroidAmbientSound(this),
            mediaVolume = AndroidMediaVolume(this),
            //  the joycon LED theme-follow feature is DISCARDED — Mimir
            // no longer drives the stick lights from theme/accent/ring. No themeColorArgb provider, no
            // theme/override collectors; the one-time migration below restores any followed LED to the
            // user's own saved color and turns the (now-removed) toggle off for good.
            privilegedUtility = privilegedUtility,
            secondaryDisplayId = {
                processServices.secondaryDisplaySelector.snapshot.value.soleEligibleId
            },
            onScreenTimeoutChanged = { idleController.onTimeoutChanged() },
            deferInitialPrivilegedProbe = true,
        ).also { it.applyWindowFallbacks() }
        idleController = MimirScreenIdleController(
            readTimeoutSeconds = utilityPreferences::readScreenTimeoutSeconds,
            applyDimmed = { dimmed ->
                // Window dim is immediate. Privileged joystick I/O stays serialized off-main and
                // carries a latest-generation token so a wake can never lose to a stale dim job.
                brightnessController.setIdleDimmed(dimmed)
                val generation = brightnessController.prepareIdleJoystickTransition(dimmed)
                lifecycleScope.launch(Dispatchers.IO) {
                    brightnessController.applyIdleJoystickTransition(generation)
                    // Neither panel follows a window override on this hardware. The main panel follows the
                    // system brightness Mimir itself writes; the second screen follows the vendor panel
                    // write. Second screen LAST on dim and FIRST on wake. This ordering is designed around
                    // a WORKING READING, not a proven fact: that the vendor assistant re-asserts the second
                    // screen from its own level when the system brightness changes (inferred from its
                    // string table and one wake recovery; settled by writing display 4 through Mimir's
                    // lever, then screen_brightness, and reading panel1-backlight). Either way the second
                    // screen's final write is Mimir's. Same token, so a stale dim cannot
                    // outlive a wake. See BrightnessController.applyIdlePrimarySystemBrightness and
                    // applyIdleSecondarySystemBrightness.
                    if (dimmed) {
                        brightnessController.applyIdlePrimarySystemBrightness(generation)
                        brightnessController.applyIdleSecondarySystemBrightness(generation)
                    } else {
                        brightnessController.applyIdleSecondarySystemBrightness(generation)
                        brightnessController.applyIdlePrimarySystemBrightness(generation)
                    }
                }
            },
            applyKeepScreenOn = { keepOn ->
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            },
            scheduler = ScreenIdleSchedule { delayMs, onDeadline ->
                val job = lifecycleScope.launch {
                    delay(delayMs)
                    onDeadline()
                }
                ({ job.cancel() })
            },
            // Stage two of the idle: the dim is a warning, this ends the session (policy
            // 2026-09-21). Vendor-privileged and chassis-gated inside the gateway; where the
            // privilege is not live this answers false and the controller has already handed the
            // screen back to the platform's own timeout.
            enforceDeviceSleep = privilegedUtility::enforceDeviceSleep,
        )
        screenIdleController = idleController
        // Round-5 triage B2: the Bottom's ᛗ, its menu and the overlay-hosted Tabs drawer are native windows none of this
        // Activity's dispatchers see, so the overlay reports their touches here: DIRECT counts as interaction and OUTSIDE
        // only re-arms (MimirScreenIdleController.onOverlayScreenActivity). The ᛗ's own reveal bridge is unchanged.
        val screenActivityObserver: (OverlayScreenActivity) -> Unit = { activity ->
            screenIdleController?.onOverlayScreenActivity(activity)
        }
        overlayScreenActivityObserver = screenActivityObserver
        companionOverlayController?.setScreenActivityObserver(screenActivityObserver)
        val idleRestoreGeneration = brightnessController.prepareIdleJoystickTransition(false)
        lifecycleScope.launch(Dispatchers.IO) {
            brightnessController.applyIdleJoystickTransition(idleRestoreGeneration)
            // Heals a screen left dim by a process that died while idle: this runs on every start.
            // Second screen first, for the same reason as the wake above.
            brightnessController.applyIdleSecondarySystemBrightness(idleRestoreGeneration)
            brightnessController.applyIdlePrimarySystemBrightness(idleRestoreGeneration)
        }
        lifecycleScope.launch {
            // CF01 discard migration: a device that had theme-follow ON (or a restore pending) gets its
            // ORIGINAL joycon color written back through the existing restore engine, exactly once. The
            // Vendor transaction stays off the main thread. With the flag off, every remaining
            // reconcile path in the controller is a no-op.
            val utilityPrefs = AndroidUtilityPreferenceStore(this@MainActivity)
            if (utilityPrefs.isThemeAwareJoystickLight() ||
                utilityPrefs.isThemeAwareJoystickLightRestorePending()
            ) {
                withContext(Dispatchers.IO) {
                    runCatching { brightnessController.setThemeAwareJoystickLight(false) }
                }
            }
        }
        lifecycleScope.launch {
            CompanionReturnCoordinator.runtime().snapshot.collect { snapshot ->
                companionOverlayController?.setSecondaryReturnAvailable(
                    companionSecondaryReturnAvailable(snapshot),
                )
            }
        }
        lifecycleScope.launch {
            companionController?.active?.collect { active ->
                if (active) brightnessController.applyWindowFallbacks()
                updateOwnMirrorDrawerEdgeBack(
                    CompanionReturnCoordinator.runtime().snapshot.value,
                    active,
                )
            }
        }
        // Runtime and drawer intent jointly drive host migration. A drawer opened after the mirror settled
        // must reconcile immediately; waiting for another runtime snapshot can leave it hostless indefinitely.
        lifecycleScope.launch {
            companionDrawerHostReconciliation(
                CompanionReturnCoordinator.runtime().snapshot,
                CompanionRecentsState.ui,
                CompanionRecentsState.livePresentationHosts,
            ).collect { snapshot ->
                reconcileCompanionDrawerHost(snapshot)
                updateOwnMirrorDrawerEdgeBack(snapshot)
            }
        }
        // CR-11: keep the tab manager live — as apps open, close, or move between screens, the registry
        // republishes and the open drawer refreshes. update() no-ops while the drawer is hidden.
        lifecycleScope.launch {
            secondaryTaskRegistry?.state?.collect { state ->
                CompanionRecentsState.update(runningTabsForDrawer(state))
            }
        }
        // M-3: an `install -r` silently resets SYSTEM_ALERT_WINDOW and the M-button just fails to mount
        // . Surface the one-line re-grant notice; the durable
        // fix stays at Settings › Controls › Draw over other apps.
        lifecycleScope.launch {
            companionOverlayController?.overlayPermissionMissing?.collect { missing ->
                if (missing) {
                    showMimirMessage(
                        this@MainActivity,
                        "The Mimir button needs \"Draw over other apps\" — Settings › Controls › Draw over other apps.",
                        long = true,
                    )
                }
            }
        }
        runDebugRecentsIntentHook()
        // Cold-start Welcome-back lane (shipping showWelcomeBack): arm the input hold BEFORE the first
        // frame so a buffered press can't act on the still-masked shelf; MimirColdStartWelcome disarms
        // it at land. The postDelayed reset is a backstop only — the gate must never outlive the beat.
        val returningUser = !OnboardingStore.isFirstRun(this) && !sameProcessRecreation
        //  a first run's cold start draws one empty frame before the app composes (the splash
        // path's handshake, without a splash). Claimed once per process, so a later MainActivity keeps today's mount.
        val firstRunColdStart = !returningUser && !sameProcessRecreation &&
            FirstRunColdStartClaim.compareAndSet(false, true)
        WelcomeHold.active = returningUser
        if (returningUser) {
            window.decorView.postDelayed(welcomeHoldBackstop, WelcomeHoldBackstopMs)
        }
        MimirColdStartTrace.mark(
            "set content",
            "returningUser=$returningUser sameProcessRecreation=$sameProcessRecreation",
        )
        setContent {
            // F10: the M badge's corner, reserved by the chrome that lives there.
            val noBadgeBounds = androidx.compose.runtime.remember {
                kotlinx.coroutines.flow.MutableStateFlow<com.gruning.mimir.rewrite.core.companionnative.OverlayButtonBounds?>(null)
            }
            val badgeBounds by (companionOverlayController?.primaryButtonBounds ?: noBadgeBounds)
                .collectAsState()
            val topEndReserve = mimirTopEndReserveDp(
                badgeBounds,
                androidx.compose.ui.platform.LocalDensity.current.density,
            ).dp
            CompositionLocalProvider(
                LocalMimirKeyboardHapticFeedback provides keyboardHapticFeedback,
                com.gruning.mimir.rewrite.core.ui.LocalMimirTopEndReserve provides topEndReserve,
            ) {
                MimirColdStartWelcome(returningUser = returningUser, firstRunColdStart = firstRunColdStart) {
                    MimirApp(
                        inputRouter = inputRouter,
                        activityStateRestored = activityStateRestored,
                        controllerNavigationActive = controllerNavigationActive,
                        interactionMotionGate = interactionMotionGate,
                        companionOverlayController = companionOverlayController,
                        companionDisplayActive = companionController?.active ?: kotlinx.coroutines.flow.MutableStateFlow(false),
                        launchForegroundObserver = foregroundMonitor,
                        brightnessController = brightnessController,
                    )
                }
            }
        }
    }

    private fun applyPrimaryOrientation(primarySurfaceAvailable: Boolean) {
        val orientation = if (primarySurfaceAvailable) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (requestedOrientation != orientation) requestedOrientation = orientation
    }

    private fun showDatabaseRecoveryGate(failure: DatabaseOpenFailure) {
        WelcomeHold.active = false
        val authority = DefaultFocusAuthority()
        inputRouter.handler = { action ->
            if (databaseRecoveryCommand(action) == DatabaseRecoveryCommand.CLOSE) {
                finishAndRemoveTask()
            }
            true
        }
        inputRouter.navMoveProbe = { authority.state.value }
        inputRouter.homeViewModeSelectEnabled = { false }
        inputRouter.onHomeViewModeSelect = { false }
        val themeId = ThemeStore.themeId.value
        setContent {
            DatabaseRecoveryGate(
                failure = failure,
                themeId = themeId,
                authority = authority,
                onClose = ::finishAndRemoveTask,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(StorageGrantPendingRootKey, storageGrantRecovery?.pendingRootUri)
        outState.putString(SavedProcessEpochKey, CompanionProcessRuntime.processEpoch)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        MimirMainStartedCopies.started(this)
        // A return to Mimir never shows the launch intro (walk 32g). The intro ends itself when the game has the
        // screen, without an exit; this covers the way that cannot: a game that came and went inside the 0.2 s hold,
        // or the coroutine that was to remove the intro ending with its scope. It runs before the first frame.
        //
        // The launch cover (Dolphin, ARMSX2) is an overlay window that outlives Mimir's stop, so it is ended first, and
        // synchronously (removeViewImmediate, no fade): a fade begun now would tail over Mimir's own return.
        GameLaunchCover.hostStarted()
        GameOpeningIntroState.hideDispatchedForReturn()
        // Before the music control re-reads whether a game is in front: Mimir in front means no foreign app is.
        YourMusicEverywhere.onMimirForegrounded()
        MimirFrontendMusic.onAppForegrounded()
        mButtonTopologyController?.setPrimaryHostForeground(true)
        primaryMotionRoot?.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        // The overlay / Usage Access Settings screens start from this Activity so they join Mimir's task
        // and do not linger as their own Recents cards.
        ResumedHostActivity.onResumed(this)
        YourMusicEverywhere.onMainResumed()
        window.decorView.removeCallbacks(staleMainTaskSweep)
        window.decorView.postDelayed(staleMainTaskSweep, StaleMainTaskSweepDelayMs)
        // Display 4's floor has no authority of its own; while this Activity is resumed, keys and HAT
        // motion that land there are routed to the body below. Registered BEFORE the database-recovery
        // return so the two displays never disagree about who owns input: display 0's dispatchKeyEvent
        // is not gated on that failure either.
        MimirMainInputSink.register(keys = secondaryDisplayKeySink, motion = secondaryDisplayMotionSink)
        MimirMainInputSink.registerInteraction(secondaryDisplayInteraction)
        if (!databaseRecoveryAllowsRuntimeIntents(DatabaseStartupGateState.failure)) {
            enterFullscreenMode()
            return
        }
        // O-5: the permission ("Display over other apps") can be regained or revoked while this Activity
        // sits backgrounded -- the player leaves for Android Settings and returns -- and onCreate's own
        // shouldRun decision, made once at construction time, never re-runs for that. Re-evaluate the same
        // live decision here, on every resume -- see applyPerformanceOverlayLiveDecision() for why this is
        // unconditional rather than diffed against a remembered belief.
        applyPerformanceOverlayLiveDecision()
        // Same live-decision discipline as the line above, for the same class of reason: a persisted SAF
        // grant can be taken away while this Activity sits backgrounded — the player revokes it in Android
        // Settings, a sibling debug build re-takes the same tree, or the card is re-mounted under a new
        // volume id — and `onCreate`'s verdict, made once, never re-runs for that. Unconditional rather
        // than diffed against a remembered belief; the DEDUP lives in the coordinator's log, not here, so
        // the StateFlow always reflects the last live read.
        storageGrantRecovery?.audit()
        if (window.decorView.hasWindowFocus()) screenIdleController?.onResume()
        // The Tweaker session can end from outside this app entirely -- device-proven 2026-08-13,
        // where the daemon's own STARTUP_RECOVERY_RESTORE dropped the profile to Stock during the
        // Add-games picker flow and it stayed there until a manual restart, because nothing was
        // refreshing the projection and so nothing ever noticed. Resuming is when the player is
        // present and a loss actually matters.
        TweakerBridge.onPrimaryForegroundResumed()
        val resumeObservation = PrimaryActivityObservationState.onPrimaryResumed()
        val stopTicket = resumeObservation.returnTicket
        val resumedAfterStop = resumeObservation.resumedAfterStop
        val parkedMainGame = MainGameParkingState.currentForCurrentSession()?.takeUnless { it.homeParked } != null
        val homeParked = MainGameHomePark.parkedByHome()
        if (!parkedMainGame && !homeParked && !resumedAfterStop) {
            MimirSessionAuthority.state.value.gameSession
                ?.takeIf { it.phase == MimirSessionPhase.DISPATCHED && it.placement == MimirGamePlacement.MAIN }
                ?.let(::armGameDispatchWatchdog)
        }
        if (!parkedMainGame) {
            if (!homeParked) CompanionSceneState.observeMimirForeground()
            noteCompanionRunningAppMainResumed()
        }
        val runtimeSnapshot = CompanionReturnCoordinator.runtime().snapshot.value
        // L-3: any resume of the primary is enough for ordinary runtime recovery. Persisted non-mirror
        // process recovery is intentionally excluded here; its surviving foreign task requires explicit
        // Return after the current process has observed the display. See the predicate KDoc.
        val reclaimOnResume = !parkedMainGame &&
            shouldReclaimCompanionAfterPrimaryResume(snapshot = runtimeSnapshot)
        if (stopTicket != null) {
            stopTicket.gameOperation?.let { operation ->
                settleReturningGameSession(operation, stopTicket.gameWasPreparing)
            }
            stopTicket.primaryAppOperationId?.let { operationId ->
                settleReturningPrimaryAppSession(operationId, stopTicket.primaryAppWasPreparing)
            }
        }
        // P1 (combined43 sweep): budgeted with the floor's reclaim, or a guard that keeps missing its deadline resumes Main and this
        // reclaims again, for good. The budget hears every resume; it issues only when the policy above wants one and a port exists.
        val requested = CompanionReturnCoordinator.reclaimOnPrimaryResume(
            disposition = runtimeSnapshot.disposition,
            wanted = reclaimOnResume && companionController != null,
            resumedAfterStop = resumedAfterStop,
        )
        if (reclaimOnResume) {
            Log.i(
                "MimirCompanionRuntime",
                "primary resume reclaim requested=$requested from=${runtimeSnapshot.disposition.javaClass.simpleName}",
            )
        }
        // Settlement belongs to a session that actually returned, so it is gated exactly like the two
        // blocks above: `stopTicket` is non-null only when the stop carried a real return operation
        // (PrimaryStopResumeLedger keeps the ticket only `takeIf(::hasReturnOperation)`). A freshly
        // started process never launched anything, so it has nothing of its own to settle -- and this
        // call used to run there unconditionally, tearing down a system-wide profile the player set
        // deliberately. Device-proven on Odin 2026-08-10 with Efficiency active: force-stop killed the
        // app at 02:43:58 and the profile SURVIVED that death (clocks unchanged with no pid), then the
        // replacement process started and exact Stock arrived ~4s later. Guardian stayed alive and
        // never fired, and no `SUPERVISOR restore reason=` was logged -- a requested settlement, not a
        // safety trip, which is why the rollback was unattributable from logcat.
        //
        // The other half of this call is capability refresh, which every resume still needs; the cold
        // path does that alone. Activity cancellation only stops this caller from awaiting either.
        lifecycleScope.launch {
            if (shouldSettleTweakerAfterPrimaryResume(stopTicket)) {
                TweakerBridge.settleAfterReturn()
            } else {
                TweakerBridge.refreshCapabilities()
            }
        }
        companionOverlayController?.reconcile()
        mButtonTopologyController?.reconcile()
        mButtonTopologyController?.notePrimaryInteraction()
        // R-8: resume the audio-focus gate here too — this is the live "Mimir is foreground again"
        // signal (the notifyExplicitReturn bus is dead code per the risk-6 trace); idempotent by
        // construction (MimirAudioFocus.onReturnedToMimir() is an unconditional StateFlow set).
        // It runs for a game parked by the switch button (a non-home lease) as well: Mimir is on Main and the game is not on
        // screen, which is what a HOME park already was for audio. This is the return edge that budgets the game's stream as
        // kept (MimirKeptGameStreams). It always runs before the Bottom app is dispatched, and dispatch guarantees it: the park
        // coroutine runs on Dispatchers.IO (CompanionProcessRuntime, launchBottomAppPlan) and its hop into Dispatchers.Main.immediate
        // ahead of launchOnSecondary is posted to the main thread, so it cannot run until this onResume has returned.
        MimirAudioFocus.onReturnedToMimir()
        if (parkedMainGame) Log.i("MimirCompanionRuntime", "ᛗ park: Mimir is in front, the parked game is kept")
        if (!parkedMainGame) {
            // Mimir is foreground again: a game that held the primary is gone (CR-2 backstop), unless HOME parked it (F3): its NOW PLAYING carries Quit.
            if (!homeParked) CompanionState.dualScreenGameActive = false
            // G-2: same no-Usage-Access backstop for the away-game re-derive key and its retained hero card.
            if (!homeParked) CompanionSceneState.disarmGameOwner()
            if (!homeParked) CompanionState.clearRetainedNowPlaying()
        }
        // R6-P1-01 §5.4: an Android-Recents kill of the parked task stops Main, then removes the task, then
        // resumes Main — the one edge the resolve-stage probe (CompanionDisplayController) never sees
        // because no Return was tapped. Proven gone (R6-P0-02 follow-up: no task on display 0, full stop —
        // the cached process is logged, not required) releases the lease and ends the session the same way
        // the resolve stage does; a live task retains it.
        if ((parkedMainGame || homeParked) && resumedAfterStop) {
            MainGameParkingState.currentForCurrentSession()?.let { lease ->
                CompanionProcessRuntime.processScope.launch(Dispatchers.IO) {
                    releaseParkedLease(applicationContext, lease, stage = "resume")
                }
            }
        }
        enterFullscreenMode()
        window.decorView.postDelayed({ enterFullscreenMode() }, FullscreenReapplyDelayMs)
    }

    private fun settleReturningGameSession(
        expectedOperation: MimirGameSessionOperation,
        wasPreparingAtStop: Boolean,
    ) {
        if (gameSessionReturnJob?.isActive == true) return
        gameSessionReturnJob = lifecycleScope.launch(Dispatchers.IO) {
            if (!awaitCurrentGameReturnReady(expectedOperation)) {
                if (gameReturnOperationResolved(expectedOperation)) {
                    PrimaryActivityObservationState.acknowledgeGameReturn(expectedOperation)
                }
                return@launch
            }
            val ticket = awaitGameReturnTicket(expectedOperation, wasPreparingAtStop)
            if (ticket == null) {
                if (gameReturnOperationResolved(expectedOperation)) {
                    PrimaryActivityObservationState.acknowledgeGameReturn(expectedOperation)
                }
                return@launch
            }
            val returnedAtMs = System.currentTimeMillis()
            val observer = foregroundMonitor
            val observation = ticket.targetPackageName?.let { targetPackage ->
                observer?.observeLaunchForeground(
                    targetPackage = targetPackage,
                    launchedAtMs = ticket.launchedAtEpochMs,
                    returnedAtMs = returnedAtMs,
                    singleScreenLaunchShape = !ticket.dualScreen,
                )
            }
            val completed = completeGameReturnWhenPrimaryHostStable(
                ticket = ticket,
                foregroundProven = observation?.targetForegroundObserved == true,
                failureReason = observation?.takeIf { it.silentDeathProven }?.silentDeathReason,
            )
            if (completed || gameReturnOperationResolved(expectedOperation)) {
                PrimaryActivityObservationState.acknowledgeGameReturn(expectedOperation)
            }
            // Rank 7c: a Bottom-card launch with no row on Main's Home arms no Home return record, and Home's resume
            // only finishes EXTERNAL ELAPSED from a record it can peek. Finish the returned session's own token here,
            // after the return has settled; the finish is token-matched, so it is a no-op if Home finished it first.
            primaryGameReturnElapsedToken(MimirSessionAuthority.state.value.gameSession, expectedOperation)
                ?.let { token ->
                    HomeExternalSessionElapsedStore.finalize(
                        context = this@MainActivity,
                        returnToken = token,
                        nowElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                    )
                }
        }
    }

    private fun armGameDispatchWatchdog(session: MimirGameSession) {
        val expectedPackage = session.targetPackageName ?: return
        if (gameDispatchWatchdogOperation == session.operation &&
            gameDispatchWatchdogJob?.isActive == true
        ) {
            return
        }
        cancelGameDispatchWatchdog()
        gameDispatchWatchdogOperation = session.operation
        val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
        val remaining = remainingGameDispatchWatchdogDelayMs(
            dispatchedAtElapsedMs = session.dispatchedAtElapsedMs,
            nowElapsedMs = nowElapsedMs,
            graceMs = GameDispatchPrimaryGraceMs,
        )
        gameDispatchWatchdogJob = lifecycleScope.launch {
            val result = runGameDispatchWatchdog(
                delayMs = remaining,
                expectedOperation = session.operation,
                expectedPackage = expectedPackage,
                currentSession = { MimirSessionAuthority.state.value.gameSession },
                primaryHostOwnsWindow = ::primaryHostOwnsWindow,
                recoverTarget = {
                    // RECOVERING is claimed before this bounded exact-package request, so another launch
                    // of the same emulator cannot race the force-stop side effect. Dispatch truth remains
                    // IntentSent; recovery is lifecycle cleanup, never a rewritten LaunchOutcome.
                    withContext(Dispatchers.IO) {
                        LaunchInjection.recoverLaunchTarget(this@MainActivity, expectedPackage)
                    }
                },
                expireSession = {
                    MimirSessionAuthority.gameDispatchWatchdogExpired(
                        operation = session.operation,
                        expectedPackageName = expectedPackage,
                    )
                },
                applyCleanup = { cleanup ->
                    // Unwind process-global away-game posture synchronously before another input can
                    // start a newer launch. The companion key is exact-package guarded.
                    MimirSessionAuthority.applyLibraryDispatchWatchdogCleanup(cleanup)
                    MimirAudioFocus.onReturnedToMimir()
                    TweakerBridge.settleAfterReturn()
                    val ownerCleared = CompanionSceneState.disarmGameOwner(cleanup.expectedPackageName)
                    if (ownerCleared) CompanionState.clearRetainedNowPlaying()
                    if (cleanup.dualScreen) CompanionState.dualScreenGameActive = false
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        // a failed dispatch cancels its play-time measurement, whoever opened it (a Home handle's token, else the session's own).
                        HomeExternalSessionElapsedStore.cancel(this@MainActivity, cleanup.elapsedToken())
                        cleanup.homeReturnHandle?.let { handle -> acknowledgeHomeLaunchReturnDurably(this@MainActivity, handle) }
                    }
                },
                completeCleanup = { cleanup ->
                    MimirSessionAuthority.gameDispatchWatchdogCleanupComplete(cleanup)
                },
            )
            result.reap?.let { reap ->
                Log.i(
                    "MimirLaunchRecovery",
                    "resumed watchdog target=$expectedPackage reap=$reap cleanup=${result.cleanupApplied}",
                )
            }
            if (gameDispatchWatchdogOperation == session.operation) {
                gameDispatchWatchdogOperation = null
                gameDispatchWatchdogJob = null
            }
        }
    }

    private fun cancelGameDispatchWatchdog() {
        gameDispatchWatchdogJob?.cancel()
        gameDispatchWatchdogJob = null
        gameDispatchWatchdogOperation = null
    }

    private suspend fun awaitCurrentGameReturnReady(
        expectedOperation: MimirGameSessionOperation,
    ): Boolean {
        val pending = MimirSessionAuthority.state.value.gameSession ?: return false
        if (pending.operation != expectedOperation) return false
        if (pending.phase != MimirSessionPhase.PREPARING) {
            return pending.phase.isPrimaryReturnTrackable()
        }
        val settled = MimirSessionAuthority.state.first { snapshot ->
            val game = snapshot.gameSession
            game == null || game.operation != pending.operation || game.phase != MimirSessionPhase.PREPARING
        }.gameSession
        return settled
            ?.takeIf { it.operation == expectedOperation }
            ?.phase
            ?.isPrimaryReturnTrackable() == true
    }

    private fun settleReturningPrimaryAppSession(
        expectedOperationId: String,
        wasPreparingAtStop: Boolean,
    ) {
        if (primaryAppSessionReturnJob?.isActive == true) return
        primaryAppSessionReturnJob = lifecycleScope.launch(Dispatchers.IO) {
            if (!awaitCurrentPrimaryAppReturnReady(expectedOperationId)) {
                if (primaryAppReturnOperationResolved(expectedOperationId)) {
                    PrimaryActivityObservationState.acknowledgePrimaryAppReturn(expectedOperationId)
                }
                return@launch
            }
            val ticket = awaitPrimaryAppReturnTicket(expectedOperationId, wasPreparingAtStop)
            if (ticket == null) {
                if (primaryAppReturnOperationResolved(expectedOperationId)) {
                    PrimaryActivityObservationState.acknowledgePrimaryAppReturn(expectedOperationId)
                }
                return@launch
            }
            val returnedAtMs = System.currentTimeMillis()
            val observer = foregroundMonitor
            val observation = observer?.observeLaunchForeground(
                targetPackage = ticket.targetPackageName,
                launchedAtMs = ticket.launchedAtEpochMs,
                returnedAtMs = returnedAtMs,
                singleScreenLaunchShape = true,
            )
            val completed = completePrimaryAppReturnWhenPrimaryHostStable(
                ticket = ticket,
                foregroundProven = observation?.targetForegroundObserved == true,
                failureReason = observation?.takeIf { it.silentDeathProven }?.silentDeathReason,
            )
            if (completed || primaryAppReturnOperationResolved(expectedOperationId)) {
                PrimaryActivityObservationState.acknowledgePrimaryAppReturn(expectedOperationId)
            }
        }
    }

    private suspend fun awaitCurrentPrimaryAppReturnReady(
        expectedOperationId: String,
    ): Boolean {
        val pending = MimirSessionAuthority.state.value.primaryAppSession ?: return false
        if (pending.operationId != expectedOperationId) return false
        if (pending.phase != MimirSessionPhase.PREPARING) {
            return pending.phase.isPrimaryReturnTrackable()
        }
        val settled = MimirSessionAuthority.state.first { snapshot ->
            val app = snapshot.primaryAppSession
            app == null || app.operationId != pending.operationId || app.phase != MimirSessionPhase.PREPARING
        }.primaryAppSession
        return settled
            ?.takeIf { it.operationId == expectedOperationId }
            ?.phase
            ?.isPrimaryReturnTrackable() == true
    }

    private fun gameReturnOperationResolved(expectedOperation: MimirGameSessionOperation): Boolean {
        val session = MimirSessionAuthority.state.value.gameSession
        return session == null || session.operation != expectedOperation || !session.phase.isPrimaryReturnTrackable()
    }

    private fun primaryAppReturnOperationResolved(expectedOperationId: String): Boolean {
        val session = MimirSessionAuthority.state.value.primaryAppSession
        return session == null || session.operationId != expectedOperationId || !session.phase.isPrimaryReturnTrackable()
    }

    private suspend fun awaitGameReturnTicket(
        expectedOperation: MimirGameSessionOperation,
        wasPreparingAtStop: Boolean,
    ): MimirGameReturnTicket? {
        if (wasPreparingAtStop) delay(PrimaryDeferredReturnGuardMs)
        while (!gameReturnOperationResolved(expectedOperation)) {
            val ticket = withContext(Dispatchers.Main.immediate) {
                if (!primaryHostOwnsWindow()) return@withContext null
                MimirSessionAuthority.beginGameReturn(expectedOperation)
            }
            if (ticket != null) return ticket
            delay(PrimaryDeferredReturnFocusPollMs)
        }
        return null
    }

    private suspend fun awaitPrimaryAppReturnTicket(
        expectedOperationId: String,
        wasPreparingAtStop: Boolean,
    ): MimirAppReturnTicket? {
        if (wasPreparingAtStop) delay(PrimaryDeferredReturnGuardMs)
        while (!primaryAppReturnOperationResolved(expectedOperationId)) {
            val ticket = withContext(Dispatchers.Main.immediate) {
                if (!primaryHostOwnsWindow()) return@withContext null
                MimirSessionAuthority.beginPrimaryAppReturn(expectedOperationId)
            }
            if (ticket != null) return ticket
            delay(PrimaryDeferredReturnFocusPollMs)
        }
        return null
    }

    private suspend fun completeGameReturnWhenPrimaryHostStable(
        ticket: MimirGameReturnTicket,
        foregroundProven: Boolean,
        failureReason: String?,
    ): Boolean {
        while (!gameReturnOperationResolved(ticket.operation)) {
            val completed = withContext(Dispatchers.Main.immediate) {
                if (!primaryHostOwnsWindow()) {
                    false
                } else {
                    val settled = MimirSessionAuthority.completeGameReturn(
                        operation = ticket.operation,
                        foregroundProven = foregroundProven,
                        failureReason = failureReason,
                    )
                    if (settled && failureReason == null && ticket.dualScreen) {
                        val reclaimed = companionController?.restoreAfterMainGameExit(
                            operation = ticket.operation,
                            packageName = ticket.targetPackageName,
                        ) == true
                        Log.i(
                            "MimirCompanionRuntime",
                            "main game exit settled operation=${ticket.operation} package=${ticket.targetPackageName} " +
                                "bottomReclaim=$reclaimed",
                        )
                        BottomUiReceipts.log.session(
                            "exit",
                            ticket.operation.value,
                            companionBottomDisplayId(),
                            if (reclaimed) "main_game_exit_reclaimed" else "main_game_exit_no_reclaim",
                            ticket.targetPackageName,
                        )
                    }
                    settled
                }
            }
            if (completed) return true
            delay(PrimaryDeferredReturnFocusPollMs)
        }
        return false
    }

    private suspend fun completePrimaryAppReturnWhenPrimaryHostStable(
        ticket: MimirAppReturnTicket,
        foregroundProven: Boolean,
        failureReason: String?,
    ): Boolean {
        while (!primaryAppReturnOperationResolved(ticket.operationId)) {
            val completed = withContext(Dispatchers.Main.immediate) {
                primaryHostOwnsWindow() && MimirSessionAuthority.completePrimaryAppReturn(
                    operationId = ticket.operationId,
                    foregroundProven = foregroundProven,
                    failureReason = failureReason,
                )
            }
            if (completed) return true
            delay(PrimaryDeferredReturnFocusPollMs)
        }
        return false
    }

    // Not the global window focus alone: on the Thor Mimir's own Bottom may hold it (see PrimaryHostDisplayGate.kt).
    private fun primaryHostOwnsWindow(): Boolean = primaryHostOwnsDisplay(
        resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        windowFocused = window.decorView.hasWindowFocus(),
        bottomOwnedByMimir = companionBottomOwnedByMimir(CompanionReturnCoordinator.runtime().snapshot.value.disposition),
    )

    override fun onPause() {
        ResumedHostActivity.onPaused(this)
        YourMusicEverywhere.onMainPaused()
        window.decorView.removeCallbacks(staleMainTaskSweep)
        // Identity-guarded: if a successor instance already registered its own, this releases nothing.
        MimirMainInputSink.release(keys = secondaryDisplayKeySink, motion = secondaryDisplayMotionSink)
        MimirMainInputSink.releaseInteraction(secondaryDisplayInteraction)
        screenIdleController?.onPause()
        CompanionRunningAppState.noteMainPaused()
        // Stop confirming while backgrounded: a heartbeat costs a bind, and a player who is not
        // looking at Mimir is not being misled by a stale profile.
        TweakerBridge.onPrimaryForegroundPaused()
        super.onPause()
    }

    override fun onStop() {
        // A copy another one covers stops only after that one has started (walk-32m: the launcher-opened copy beside the
        // HOME one), and Mimir is then still on screen. The app-wide edges stay the started copy's; this copy's own
        // cleanup runs either way. Left ungated, this stop silenced the music and marked Mimir stopped under a live copy.
        val leavingScreen = MimirMainStartedCopies.stoppedLeavingScreen(this)
        if (leavingScreen) {
            MimirFrontendMusic.onAppBackgrounded()
            mButtonTopologyController?.setPrimaryHostForeground(false)
            ControllerMappingStore.cancelCapture()
            ControllerShortcutStore.cancelCapture("Controller shortcut capture cancelled when Mimir left focus")
        }
        inputRouter.reset()
        cancelGameDispatchWatchdog()
        gameSessionReturnJob?.cancel()
        gameSessionReturnJob = null
        primaryAppSessionReturnJob?.cancel()
        primaryAppSessionReturnJob = null
        // onStop is the authoritative edge that Mimir ceded the primary window. Once this exact dispatch
        // leaves, return observation owns it; the failed-handoff watchdog must never re-arm on focus return
        // and force-stop a later same-package launch.
        if (leavingScreen && !isChangingConfigurations) {
            MimirSessionAuthority.state.value.gameSession
                ?.takeIf { it.phase == MimirSessionPhase.DISPATCHED && it.placement == MimirGamePlacement.MAIN }
                ?.let { MimirSessionAuthority.gameYielded(it.operation) }
            // The launch cover's ceiling runs from this edge. It is told here, and not only by the intro's finish step,
            // because that coroutine can end with its scope and this cannot.
            GameLaunchCover.hostStopped()
        }
        if (leavingScreen) {
            val sessions = MimirSessionAuthority.state.value
            val gameSession = sessions.gameSession?.takeIf {
                it.phase.isPrimaryReturnTrackable() && it.placement == MimirGamePlacement.MAIN
            }
            val primaryAppSession = sessions.primaryAppSession?.takeIf { it.phase.isPrimaryReturnTrackable() }
            PrimaryActivityObservationState.onPrimaryStopped(
                changingConfigurations = isChangingConfigurations,
                ticket = PrimaryStopResumeTicket(
                    gameOperation = gameSession?.operation,
                    gameWasPreparing = gameSession?.phase == MimirSessionPhase.PREPARING,
                    primaryAppOperationId = primaryAppSession?.operationId,
                    primaryAppWasPreparing = primaryAppSession?.phase == MimirSessionPhase.PREPARING,
                ),
            )
            if (!isChangingConfigurations) {
                // The reclaim budget's episode: was this stop the guard's own cycle, or the player leaving (FloorReclaimBudget.hostStopped)?
                CompanionReturnCoordinator.notePrimaryStopped()
                // Main stopped under a parked game: fronted by a Return or a HOME Resume (the restoring mark), or by Android's Recents, which marks
                // nothing (with a parked game and Main covered, the game is in front). The audio gate is armed as at a
                // launch: Mimir lets go at once, UI sounds go quiet, and the kept game's stream stops being discounted until Mimir is back
                // (MimirKeptGameStreams.onLaunchArmed subtracts the discount already standing, so a repeated cover does not grow it).
                val frontedParkedGame = gameSession?.operation?.let(MainGameParkingState::clearRestoringOnPrimaryStop) == true
                if (mainStopArmsAudioGate(frontedParkedGame, MainGameParkingState.currentForCurrentSession())) {
                    Log.i("MimirCompanionRuntime", if (frontedParkedGame) "game re-armed on return" else "parked game covered: re-armed")
                    MimirAudioFocus.onGameLaunchArmed()
                }
            }
        }
        primaryTouchLease?.close()
        primaryTouchLease = null
        primaryMotionRoot?.setVisible(false)
        super.onStop()
    }

    /**
     * singleTop re-delivery (review find): an `am start` against the running top instance lands here, not
     * onCreate, so the extras-gated dev hooks would silently no-op (getIntent() stays stale). Adopt the new
     * intent and re-run them; [runDebugIntentHooks] is a compile-time release quarantine (MainActivity stays
     * exported for LAUNCHER/HOME). A user HOME press with Mimir in front returns to Home: [routeMainHomeKeyIntent].
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (!databaseRecoveryAllowsRuntimeIntents(DatabaseStartupGateState.failure)) return
        setIntent(intent)
        consumeMButtonCommandIntent(intent)
        runDebugIntentHooks()
        runDebugRecentsIntentHook()
        routeMainHomeKeyIntent(intent, ActivityMainHomeKeyPlatform(this))
    }

    private fun consumeMButtonCommandIntent(intent: Intent?) {
        val tokenPresent = intent?.hasExtra(MButtonIntentAuthority.ExtraCommandToken) == true
        val command = MButtonIntentAuthority.consume(intent)
        intent?.removeExtra(MButtonIntentAuthority.ExtraCommandToken)
        // P5: release builds included (was BuildConfig.DEBUG-gated); tokenPresent still gates it to an
        // actual M-button-originated intent rather than every intent MainActivity receives.
        if (tokenPresent) {
            Log.i("MimirMButton", "activity consume command=$command tokenPresent=true")
        }
        command?.let(MButtonNavigationCommands::publish)
    }

    /**
     * Runs adb/device-proof extras only in a debuggable build.
     *
     * This is a security boundary, not cleanup: [MainActivity] is exported as both the launcher and HOME
     * activity. Without one shared fail-closed gate, any installed app could explicitly start it with a
     * proof extra and mutate identity, seed demo rows, rescan an arbitrary path, launch a target, or change
     * persisted presentation state in a release build.
     */
    private fun runDebugIntentHooks() = debugIntentHookGate.run {
        maybeObserveHomeRail()
        maybeSetProfileName()
        maybeSeedDemoLibrary()
        maybeLaunchFirstReadyGame()
        maybeMigrateFirst()
        maybePickFolder()
        maybeScanPath()
        maybeLaunchTargetedGame()
        maybeRescanGranted()
        maybeRemoveSource()
        maybeHashFirst()
        maybeIdentifyRom()
        maybeIdentifyAll()
        maybeEnrich()
        maybeGetArt()
        maybeHoldJob()
        maybeSetOverlayVisibility()
        maybeProbeTuner()
        maybeRequestCeiling()
        maybeArmLabTunerAxis()
        maybeRegroup()
        maybeRefreshDats()
        maybeClearIdentity()
        maybeAuditMameDependency()
        maybeSetTheme()
        maybeOpenGamelist()
        maybeOpenFiles()
        maybeShowIntroLogo()
        maybeOpenOverlay()
        maybeDismissTopOverlay()
        maybeShelfQuickAction()
    }

    /** Arms the bounded coordinate/input observation only through the shared debug intent gate. */
    private fun maybeObserveHomeRail() {
        if (intent?.getBooleanExtra(DebugObserveHomeRailExtra, false) == true) {
            DebugHomeRailObservation.arm()
        }
    }

    /** Kept at the original post-companion-mount call site; the proof drawer needs that host to exist. */
    private fun runDebugRecentsIntentHook() = debugIntentHookGate.run {
        maybeShowRecents()
    }

    /**
     * DEV proof hook: `adb ... am start ... --ez mimir.showRecents true` opens the companion app drawer
     * with a few sample entries, after the secondary presentation has mounted. Debug builds may also pass
     * `--es mimir.qaLaunchSecondaryPackage <package>` to dispatch one entry through the real prepared-launch
     * coordinator; this keeps yielded/overlay certification off fragile coordinate guesses.
     */
    private fun maybeShowRecents() {
        val qaLaunchPackage = intent
            ?.getStringExtra("mimir.qaLaunchSecondaryPackage")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (intent?.getBooleanExtra("mimir.showRecents", false) != true && qaLaunchPackage == null) return
        lifecycleScope.launch {
            delay(CompanionDrawerProofDelayMs)
            CompanionRecentsState.show(
                listOf(
                    CompanionAppEntry("com.limelight.noir", "Artemis", favorite = true, kindLabel = "Streaming"),
                    CompanionAppEntry("com.retroarch", "RetroArch", favorite = true, kindLabel = "Emulator"),
                    CompanionAppEntry("org.ppsspp.ppsspp", "PPSSPP", favorite = true, kindLabel = "Emulator"),
                ),
            )
            qaLaunchPackage?.let { packageName ->
                val operationId = CompanionRecentsState.onLaunch(packageName)
                Log.i(
                    "MimirCompanionRuntime",
                    "QA secondary launch requested package=$packageName operation=$operationId",
                )
            }
        }
    }

    override fun onDestroy() {
        // Mimir is going away for good (a finish, not a recreate or a rotation): no onStart is coming to end a launch
        // cover, so it is ended here and not left to wait out its ceiling over whatever the player sees next. A copy that
        // finishes while another is started (the stale-task sweep) is not Mimir going away.
        if (isFinishing && !isChangingConfigurations && !MimirMainStartedCopies.anyStarted()) GameLaunchCover.teardown()
        tweakerPackageMonitor?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        tweakerPackageMonitor = null
        // A recreate replays the welcome beat in the NEW instance — this instance's pending backstop must
        // not fire into it and drop the input gate while the new splash still masks the shelf (review find).
        window.decorView.removeCallbacks(welcomeHoldBackstop)
        recentsCloseOperationId?.let(CompanionRecentsState::clearClose)
        recentsCloseOperationId = null
        recentsCloseHandler?.let(CompanionRecentsState::clearCloseHandler)
        recentsCloseHandler = null
        // Guarded on identity for the same reason as the LaunchStore listener below: `onCreate` of a
        // replacement Activity can run BEFORE this `onDestroy`, and an unconditional clear would
        // unregister the live instance's callback, leaving controller remaps silently not reaching the
        // router. Only take back the one this instance handed over.
        if (ControllerMappingStore.onApplied === controllerMappingAppliedListener) {
            ControllerMappingStore.onApplied = {}
        }
        controllerMappingAppliedListener = null
        if (com.gruning.mimir.rewrite.core.launch.LaunchStore.dualScreenLaunchListener === dualScreenLaunchListener) {
            com.gruning.mimir.rewrite.core.launch.LaunchStore.dualScreenLaunchListener = null
        }
        dualScreenLaunchListener = null
        storageGrantRecovery?.let(StorageGrantAuditCoordinator::detach)
        storageGrantRecovery = null
        MimirNotificationPermissionAsk.detachHost(notificationAskHost)
        companionController?.stop()
        companionController?.let { CompanionReturnCoordinator.unbind(it) }
        companionController = null
        secondaryOverlayActionLease?.let { lease ->
            mButtonActionRouter?.unbindSecondaryHandler(lease)
        }
        secondaryOverlayActionLease = null
        mButtonActionRouter = null
        mButtonTopologyController = null
        recentsOverlayHost?.hide()
        recentsOverlayHost = null
        foregroundMonitor = null
        primaryTouchLease?.close()
        primaryTouchLease = null
        primaryMotionRoot?.close()
        primaryMotionRoot = null
        // Round-5 triage B2: identity-guarded, since a replacement Activity can install its observer before this runs.
        overlayScreenActivityObserver?.let { companionOverlayController?.clearScreenActivityObserver(it) }
        overlayScreenActivityObserver = null
        screenIdleController?.onPause()
        screenIdleController = null
        super.onDestroy()
    }

    private fun handleCompanionOverlayAction(action: CompanionOverlayAction) {
        // In-session menu access: the M must WORK while something is playing — its
        // menu + return + tab manager are exactly what you reach for mid-session. The old blanket guard that
        // swallowed every action during `dualScreenGameActive` left the M globally dead (and that flag can
        // strand — it clears only on a return event or Mimir onResume). Every action here is USER-initiated
        // (a deliberate long-press → menu, or an edge swipe), so none of them is the "auto reclaim mid-game"
        // the old guard was intended to prevent; they all run.
        when (action) {
            // CP03: a right-edge inward swipe of screen 2 = "return to previous state" (Android back-from-app).
            CompanionOverlayAction.EdgeBack -> {
                // Drawer-back flicker: if the tab drawer is the top surface over Mimir's OWN
                // mirror, a back-swipe should just DISMISS the drawer — not run the full reclaim FSM (teardown +
                // re-fade + "Reconnecting…" hold + _active true→false→true) that only made sense when a foreign
                // app owned screen 2. Reclaim only when the mirror isn't already up.
                val drawerVisible = CompanionRecentsState.ui.value.requestedVisible
                // R6-P2-08 review finding 3: `companionController?.active?.value` is a copy of
                // `snapshot.mirrorActive` (CompanionDisplayController.reconcileSnapshot) taken through an
                // async flow collector — a second, laggable read of the same fact `disposition` below
                // already reads fresh off this same runtime. Reading `mirrorActive` off the SAME snapshot
                // as `disposition` means the two can never disagree at this read (the coordinator's own
                // state machine never has both `mirrorActive` and a `Yielded` disposition at once), so the
                // old `!foreignAppOwnsSecondary` term below was dead weight guarding a staleness gap this
                // single shared read removes outright.
                val snapshot = CompanionReturnCoordinator.runtime().snapshot.value
                val controllerActive = snapshot.mirrorActive
                val dismissOwnMirror = drawerVisible && controllerActive
                // With no drawer up, an open Tome/folder (or any other closable Bottom layer) on
                // the idle shelf is the next innermost layer.
                //
                // R6-P1-10: this native path is no longer how that close normally happens. The strip
                // (companionOwnMirrorFolderEdgeBackEnabled) never arms for `folderOpen` alone any more — a
                // Compose edge-back gesture on the Presentation itself closes the layer instead
                // (detectCompanionMirrorEdgeBackSwipe -> CompanionBottomFolderState.requestClose(), same
                // call as below). `closeOwnMirrorFolder` below is reached only through the pre-existing
                // race the drawer branch already lives with — the drawer visibly closing between this
                // swipe's DOWN admission (still armed by `ownMirrorDrawerEdgeBackEnabled`) and its 40dp
                // MOVE fire — not as the live folder-close route.
                val folderOpen = CompanionBottomFolderState.open.value
                val closeOwnMirrorFolder = !dismissOwnMirror && folderOpen && controllerActive
                // P4: the gesture itself, and which branch it took, had no line of its own (§1.10, I7, I8).
                Log.i(
                    "MimirCompanionRuntime",
                    "edge back drawerVisible=$drawerVisible controllerActive=$controllerActive " +
                        "folderOpen=$folderOpen action=${
                            when {
                                dismissOwnMirror -> "dismiss"
                                closeOwnMirrorFolder -> "close_folder"
                                !controllerActive -> "reclaim"
                                else -> "ignored"
                            }
                        }",
                )
                when {
                    dismissOwnMirror -> {
                        CompanionRecentsState.clearLaunchLock()
                        CompanionRecentsState.hide()
                    }
                    closeOwnMirrorFolder -> CompanionBottomFolderState.requestClose()
                    !controllerActive -> {
                        // a reclaim lands back on whatever
                        // CompanionWorkspacePage the Bottom was showing when the app took it over — the
                        // lattice if it was on the lattice, the shelf page if a shelf was open. That page
                        // is process state (CompanionWorkspaceState) that a foreign app's Presentation
                        // teardown never touches, so it already holds the hand-off value here; the old
                        // `show(ACTIVITY)` forced an unintended landing and
                        // is simply gone, not replaced with a new remembered-page mechanism.
                        restoreCompanionFromOverlay("overlay_edge_back")
                    }
                    else -> {
                        // R6-P2-08 review finding 4: `controllerActive` is still true here, but neither the
                        // drawer nor a closable layer is open — one of them closed mid-gesture, between this
                        // swipe's DOWN admission and its 40dp MOVE fire (AndroidCompanionOverlayController's
                        // `handleEdgeSwipe` admits once at DOWN and never rechecks). Constraint 3 forbids a
                        // reclaim out of MirrorPresented, so this swipe is a no-op rather than falling
                        // through to `restoreCompanionFromOverlay` and minting a reclaim guard FSM (the I2
                        // flicker) the mirror never actually left.
                    }
                }
            }
            // The single-display badge's music tap is hosted by the process panel host (MButtonProcessActionRouter), never here.
            is CompanionOverlayAction.MusicPanel -> Unit
            is CompanionOverlayAction.DragMoved -> Log.i("MimirCompanionNative", "overlay drag: $action")
            is CompanionOverlayAction.QuickMenu -> {
                if (action.role != CompanionOverlayRole.SECONDARY_COMPANION) return
                when (action.item) {
                    QuickMenuItem.SECONDARY_TABS -> {
                        companionOverlayController?.completeQuickMenu(action.requestId, accepted = true)
                        //  opening the tab manager must NOT exit the running app. When a foreign
                        // app owns screen 2, the drawer is hosted in the OVERLAY layer (recentsOverlayHost) — it
                        // z-orders over the app with NO reclaim, so the app keeps running behind it. When Mimir's own
                        // mirror is up and the primary Activity is resumed, the drawer renders inside the
                        // Presentation as a blur overlay (CP06). The host policy never reclaims a running app when
                        // an overlay is unavailable; the pending drawer remains eligible for permission recovery.
                        lifecycleScope.launch {
                            secondaryTabIconPaths = withContext(Dispatchers.IO) {
                                // Round-5 B1: retire tabs whose app has no process before the entries below are
                                // read, so the drawer never offers an app that is no longer running.
                                secondaryTaskRegistry?.reconcileLiveness()
                                loadSecondaryTabIconPaths()
                            }
                            val entries = secondaryTaskRegistry?.state?.value?.let { runningTabsForDrawer(it) }
                                ?: emptyList()
                            CompanionRecentsState.clearLaunchLock()
                            CompanionRecentsState.show(entries)
                            // Icon loading crosses a suspension point; decide from current runtime truth, not the
                            // pre-I/O mirror state captured when the user first tapped Tabs.
                            val snapshot = CompanionReturnCoordinator.runtime().snapshot.value
                            val hostSelection = companionDrawerHostSelection(
                                snapshot = snapshot,
                                primaryActivityStopped = PrimaryActivityObservationState.stopped.value,
                                dualScreenGameLive = companionMainDualScreenGameActive(MimirSessionAuthority.state.value.gameSession),
                            )
                            // P4: §1.12 "Menu: Tabs" was SILENT on the Presentation-hosted path.
                            Log.i(
                                "MimirCompanionRuntime",
                                "tabs requested host=$hostSelection entries=${entries.size} " +
                                    "disposition=${snapshot.disposition::class.simpleName}",
                            )
                            if (hostSelection == CompanionDrawerHostSelection.NONE) return@launch
                            reconcileCompanionDrawerHost(snapshot)
                        }
                    }
                    QuickMenuItem.SECONDARY_RETURN -> {
                        val snapshot = CompanionReturnCoordinator.runtime().snapshot.value
                        val yielded = snapshot.disposition as? SecondaryDisplayDisposition.Yielded
                        val host = yielded?.lease
                        val coldRecovery = isColdProcessRecoveryAvailable(snapshot)
                        // P4: the toasts at §1.11 had no log line naming the host or why return failed.
                        val logReturn = { restored: Boolean ->
                            Log.i(
                                "MimirCompanionRuntime",
                                "return requested host=${host?.packageName} coldRecovery=$coldRecovery restored=$restored",
                            )
                        }
                        if (host != null || coldRecovery) {
                            // Round-5 triage 4b: the menu collapses BEFORE the reclaim starts. It used to complete
                            // after restore(), so the card was still drawn over the return guard and the revealed
                            // shelf still wore the menu's blur (219_RETURN_TO_SHELF f6-14, f30-40). completeQuickMenu
                            // collapses from a main-looper post while restore() launches the guard synchronously, so
                            // swapping the two calls would still launch the guard first; the reclaim is posted behind
                            // the collapse instead. accepted = true is the only value that collapses (false clears the
                            // pending request and leaves the card up), so a failed reclaim now reports over a
                            // collapsed menu.
                            companionOverlayController?.completeQuickMenu(action.requestId, accepted = true)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                // Return keeps the tab entry. The Tabs ✕ remains the distinct removal
                                // action and performs reclaim before forgetting its selected package.
                                val restored = companionController?.restore(
                                    reason = "secondary_menu_return_to_shelf",
                                    returningFromPackage = host?.packageName,
                                ) == true
                                if (!restored) {
                                    showCompanionFeedbackToast("Couldn’t restore the bottom screen")
                                }
                                logReturn(restored)
                            }
                        } else {
                            companionOverlayController?.completeQuickMenu(action.requestId, accepted = false)
                            showCompanionFeedbackToast("Return to shelf is no longer available.")
                            logReturn(false)
                        }
                    }
                    QuickMenuItem.SECONDARY_SWAP -> {
                        val yielded = CompanionReturnCoordinator.runtime().snapshot.value.disposition
                            as? SecondaryDisplayDisposition.Yielded
                        val host = yielded?.lease
                        val bottomGameBlocked = host?.let { companionGameHostSwapToPrimaryBlocked(it.launchMode) } == true
                        // The session ledger is authoritative: who occupies Main is read
                        // from the session ledger and nowhere else. The Main card is a read-only view of the same
                        // ledger, so there is no second record for this plan to disagree with. The ledger also holds
                        // an app the player launched from the Bottom dock, which never crosses the display-0 transfer
                        // host: reading a separate card first planned BOTTOM_TO_MAIN over Chrome on Main (device log
                        // 2026-09-11, `swap plan=BOTTOM_TO_MAIN … mainRunning=null`).
                        val sessionLedger = MimirSessionAuthority.state.value
                        val mainOccupant: CompanionRunningApp? = sessionLedger.primaryOccupant()
                        // Moving an emulator launcher must not discard a library game's ROM payload.
                        val mainGameActive = sessionLedger.gameSession?.let { game ->
                            companionMainGameSwapBlocked(game.placement, game.phase)
                        } == true
                        val launchBlocked = runningAppTransferJob?.isActive == true ||
                            CompanionRecentsState.ui.value.launchLocked
                        val plan = companionSwapPlan(
                            bottomHost = host != null,
                            bottomGameBlocked = bottomGameBlocked,
                            mainRunning = mainOccupant != null,
                            mainGameActive = mainGameActive,
                            launchBlocked = launchBlocked,
                        )
                        Log.i(
                            "MimirCompanionRuntime",
                            "swap plan=$plan bottomHost=${host?.packageName} bottomGameBlocked=$bottomGameBlocked " +
                                "mainRunning=${mainOccupant?.packageName} mainPhase=${sessionLedger.primaryAppSession?.phase} " +
                                "ledgerRev=${sessionLedger.revision} mainGameActive=$mainGameActive " +
                                "launchBlocked=$launchBlocked transferJobActive=${runningAppTransferJob?.isActive == true} " +
                                "launchLocked=${CompanionRecentsState.ui.value.launchLocked}",
                        )
                        // QA 2026-09-11 D6 (S3M): a plan that moves nothing fell into the reverse arm below, which
                        // toasted "Could not move the app to the bottom screen" over a game on Main and left the menu
                        // open on the Bottom. The refusal is named, logged, shown where Swap was pressed, and completes
                        // the menu so it collapses, unless pressing Swap again could succeed.
                        val refusal = companionSwapRefusal(
                            bottomHost = host != null,
                            bottomGameBlocked = bottomGameBlocked,
                            mainRunning = mainOccupant != null,
                            mainGameActive = mainGameActive,
                            launchBlocked = launchBlocked,
                        )
                        if (refusal != null) {
                            Log.i("MimirCompanionRuntime", "swap refused reason=$refusal plan=$plan")
                            companionOverlayController?.completeQuickMenu(action.requestId, accepted = !refusal.retryable)
                            showCompanionFeedbackToast(refusal.message)
                            return
                        }
                        when (plan) {
                            CompanionSwapPlan.EXCHANGE -> exchangeCompanionApps(
                                requestId = action.requestId,
                                bottomHost = requireNotNull(host),
                                mainApp = requireNotNull(mainOccupant),
                            )
                            CompanionSwapPlan.BOTTOM_TO_MAIN -> {
                                val bottomHost = requireNotNull(host)
                                companionOverlayController?.completeQuickMenu(action.requestId, accepted = true)
                                moveSecondaryAppToPrimary(
                                    packageName = bottomHost.packageName,
                                    expectedHostOperationId = bottomHost.operationId,
                                    reason = "overlay_swap_to_primary",
                                )
                            }
                            CompanionSwapPlan.MAIN_TO_BOTTOM -> {
                                // The reverse direction is valid only for the exact app session confirmed
                                // on Main. Registry.swapToSecondary mints the live secondary host lease and
                                // the process runtime clears this session only after that host reaches Yielded.
                                // A dispatch alone must never erase the Main card or its session authority.
                                val swap = if (mainOccupant != null && !launchBlocked && !mainGameActive) {
                                    secondaryTaskRegistry?.swapToSecondary(mainOccupant.packageName)
                                } else {
                                    null
                                }
                                val accepted = swap?.dispatchAccepted == true
                                companionOverlayController?.completeQuickMenu(action.requestId, accepted)
                                if (accepted) {
                                    swap?.let { acceptedSwap ->
                                        Log.i(
                                            "MimirCompanionRuntime",
                                            "reverse running app transfer dispatched package=${mainOccupant?.packageName} " +
                                                "operation=${acceptedSwap.operationId} targetDisplay=${acceptedSwap.targetDisplayId}",
                                        )
                                    }
                                } else {
                                    showCompanionFeedbackToast(
                                        "Couldn’t move ${mainOccupant?.label ?: "the app"} to the bottom screen",
                                    )
                                }
                            }
                            // Answered above: companionSwapRefusal names both of these (CompanionSwapRefusalTest).
                            CompanionSwapPlan.BLOCKED_BOTTOM_GAME,
                            CompanionSwapPlan.NONE,
                            -> Unit
                        }
                    }
                    QuickMenuItem.SYSTEM_TABS,
                    QuickMenuItem.QUICK_LAUNCH,
                    QuickMenuItem.UTILITY,
                    QuickMenuItem.TWEAKER,
                    -> companionOverlayController?.completeQuickMenu(
                        action.requestId,
                        accepted = secondaryQuickMenuFallbackAcceptance(action.item),
                    )
                    // The Bottom's Trackpad row is answered by the process router (opens the pad on the Bottom); completing it
                    // here as a refused primary command would race the router's accepted answer.
                    QuickMenuItem.TRACKPAD -> Unit
                    // CLOSE_ALL retired — apps close from the TAB manager (per-tab ✕).
                }
            }
        }
    }

    private fun reconcileCompanionDrawerHost(snapshot: CompanionRuntimeSnapshot): Boolean {
        val recents = CompanionRecentsState.ui.value
        if (!recents.requestedVisible) return false
        return when (
            companionDrawerHostSelection(
                snapshot,
                PrimaryActivityObservationState.stopped.value,
                dualScreenGameLive = companionMainDualScreenGameActive(MimirSessionAuthority.state.value.gameSession),
            )
        ) {
            CompanionDrawerHostSelection.OVERLAY -> {
                // A Presentation drawer is only a projection; retain the live Presentation token while
                // clearing that projection so the overlay can acquire the exact visible drawer lease.
                (recents.drawerHost as? CompanionDrawerHost.Presentation)?.let { presentation ->
                    CompanionRecentsState.clearPresentationDrawer(presentation.renderOperationId)
                }
                if (CompanionRecentsState.shouldAttachOverlay()) {
                    recentsOverlayHost?.show() == true
                } else {
                    CompanionRecentsState.ui.value.drawerHost is CompanionDrawerHost.Overlay
                }
            }
            CompanionDrawerHostSelection.PRESENTATION -> {
                // Detach the old overlay lease before selecting Presentation. The drawer intent and any
                // launch deadline remain in process state throughout this migration.
                recentsOverlayHost?.hide()
                CompanionRecentsState.selectPresentation(snapshot)
            }
            CompanionDrawerHostSelection.NONE -> false
        }
    }

    private fun updateOwnMirrorDrawerEdgeBack(
        snapshot: CompanionRuntimeSnapshot,
        active: Boolean = companionController?.active?.value == true,
    ) {
        // R6-P1-02 (): the catcher is no longer an explicit
        // return path while a live DS/3DS game owns Main — that admission is what let a swipe reclaim a
        // HOME-only Shelf over a still-active DS surface. The own-mirror exception now reads only the
        // visible Tabs drawer; the dual-screen block below is a separate, unconditional gate.
        companionOverlayController?.setOwnMirrorDrawerEdgeBackEnabled(
            companionOwnMirrorDrawerEdgeBackEnabled(
                mirrorActive = snapshot.mirrorActive,
                drawerVisible = CompanionRecentsState.ui.value.visible,
                controllerActive = active,
            ),
        )
        companionOverlayController?.setOwnMirrorFolderEdgeBackEnabled(
            companionOwnMirrorFolderEdgeBackEnabled(
                mirrorActive = snapshot.mirrorActive,
                folderOpen = CompanionBottomFolderState.open.value,
                controllerActive = active,
                mainDualScreenGameActive = companionMainDualScreenGameActive(
                    MimirSessionAuthority.state.value.gameSession,
                ),
            ),
        )
        companionOverlayController?.setDualScreenGameOwnsBottom(
            (snapshot.disposition as? SecondaryDisplayDisposition.Yielded)?.lease?.launchMode ==
                com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
                    .LAUNCH_MODE_EXISTING_MAIN_GAME_RETURN,
        )
    }

    /**
     * A toast for a command the player gave on the Bottom (the ᛗ menu's Swap and Return to shelf), shown on the Bottom.
     *
     * `Toast.makeText(this, …)` lands on this Activity's display: QA 2026-09-11 (D6, S24) recorded the Swap refusal
     * on display 0 over a running game while the player was looking at the menu on display 4. A text toast is placed
     * by the display of the context it was made with (`Toast.show()` hands `Context.getDisplayId()` to the
     * notification service), so this one is made from a display context of the Bottom when there is one, and from
     * this Activity otherwise. The wording is the caller's.
     */
    private fun showCompanionFeedbackToast(message: CharSequence) {
        val menuTarget = companionOverlayController?.activeTarget?.value
        val displayId = companionFeedbackDisplayId(
            menuDisplayId = menuTarget?.takeIf { it.role == CompanionOverlayRole.SECONDARY_COMPANION }?.displayId,
            selectedSecondaryDisplayId = CompanionProcessRuntime.services(applicationContext)
                .secondaryDisplaySelector.snapshot.value.selectedId,
            activityDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY,
        )
        val feedbackContext = displayId
            ?.let { id -> getSystemService(DisplayManager::class.java)?.getDisplay(id) }
            ?.let { target -> runCatching { createDisplayContext(target) }.getOrNull() }
            ?: this
        showMimirMessage(feedbackContext, message)
    }

    /** The installed label of [packageName] for a feedback sentence, or null when the package cannot be read. */
    private fun installedAppLabel(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun restoreCompanionFromOverlay(reason: String) {
        CompanionRecentsState.clearLaunchLock()
        CompanionRecentsState.hide()
        val refreshPresentedMirror = reason == "overlay_edge_back" && companionMainDualScreenGameActive(
            MimirSessionAuthority.state.value.gameSession,
        )
        companionController?.restore(reason, refreshPresentedMirror = refreshPresentedMirror)
    }

    /**
     * Both screens are occupied by plain apps: the Bottom app takes Main and the Main app takes the
     * Bottom.
     *
     * The legs are strictly sequential because each one unblocks the next. The registry can only mint a
     * fresh secondary host over a presented mirror, so the Bottom must be reclaimed before the Main app
     * can go down; and the dock's Main route refuses while a PRIMARY session is active
     * (`appPreparing(PRIMARY)`), so the Main app's own session must end before the former Bottom app can
     * come up. The previous attempt ran its second leg off [moveSecondaryAppToPrimary]'s display-0
     * transfer route, which `stageTransfer` refuses outright whenever a dock-launched Main session
     * exists — the exchange then toasted nothing and did half the move (device, 2026-09-11).
     *
     * Each leg reports its own failure in the player's terms and stops; a partial exchange is never
     * dressed up as a completed one.
     */
    private fun exchangeCompanionApps(
        requestId: UUID,
        bottomHost: HostLease,
        mainApp: CompanionRunningApp,
    ) {
        // The plan already excludes an active transfer via `launchBlocked`; this is the structural
        // guard that two exchanges cannot interleave over one pair of displays.
        if (runningAppTransferJob?.isActive == true) {
            Log.w(
                "MimirCompanionRuntime",
                "exchange declined reason=transfer_job_active bottom=${bottomHost.packageName}",
            )
            companionOverlayController?.completeQuickMenu(requestId, accepted = false)
            return
        }
        companionOverlayController?.completeQuickMenu(requestId, accepted = true)
        CompanionRecentsState.clearLaunchLock()
        CompanionRecentsState.hide()
        Log.i(
            "MimirCompanionRuntime",
            "exchange start bottom=${bottomHost.packageName} main=${mainApp.packageName}",
        )
        val runtime = CompanionReturnCoordinator.runtime()
        runningAppTransferJob = lifecycleScope.launch {
            try {
                // O12: while this is true the Main copy eviction brings nothing forward (CompanionMainCopyEviction.kt),
                // so no Mimir start can land over leg 3's app. Set inside the try so the finally always clears it.
                CompanionExchangeInFlight.active = true
                val bottomLabel = loadCompanionRunningApp(bottomHost.packageName).label

                // Leg 1 — the Bottom app hands the second display back to Mimir's mirror.
                val reclaimRequested = companionController?.restore(
                    reason = "overlay_swap_exchange",
                    returningFromPackage = bottomHost.packageName,
                ) == true
                if (!reclaimRequested) {
                    Log.w(
                        "MimirCompanionRuntime",
                        "exchange: bottom reclaim refused package=${bottomHost.packageName}",
                    )
                    showCompanionFeedbackToast("Couldn’t move $bottomLabel to the main screen")
                    return@launch
                }
                val mirror = withTimeoutOrNull(10_000L) {
                    runtime.snapshot.first { it.disposition is SecondaryDisplayDisposition.MirrorPresented }
                }
                if (mirror == null) {
                    Log.w(
                        "MimirCompanionRuntime",
                        "exchange: bottom reclaim never reached MirrorPresented package=${bottomHost.packageName}",
                    )
                    showCompanionFeedbackToast("Couldn’t move $bottomLabel to the main screen")
                    return@launch
                }

                // Leg 2 — the Main app takes the now-free Bottom. Dispatch is not ownership: wait for the
                // runtime to observe THIS package yielded, not merely for the intent to be accepted.
                val down = secondaryTaskRegistry?.swapToSecondary(mainApp.packageName)
                if (down?.dispatchAccepted != true) {
                    // P12: leg 2's refusal (§1.8 "Leg 2 refused") was silent apart from the ASTR line.
                    Log.w(
                        "MimirCompanionRuntime",
                        "exchange: main app dispatch refused package=${mainApp.packageName}",
                    )
                    showCompanionFeedbackToast("Couldn’t move ${mainApp.label} to the bottom screen")
                    return@launch
                }
                val landed = withTimeoutOrNull(10_000L) {
                    runtime.snapshot.first { snapshot ->
                        (snapshot.disposition as? SecondaryDisplayDisposition.Yielded)
                            ?.lease
                            ?.packageName
                            .equals(mainApp.packageName, ignoreCase = true)
                    }
                }
                if (landed == null) {
                    Log.w(
                        "MimirCompanionRuntime",
                        "exchange: main app never yielded onto the Bottom package=${mainApp.packageName}",
                    )
                    showCompanionFeedbackToast("Couldn’t move ${mainApp.label} to the bottom screen")
                    return@launch
                }

                // For Main occupancy, the session ledger is the only record
                // of what occupies Main, so there is no agreement to wait for. The landing observed above is itself the
                // evidence that ends the Main app's session. It is settled in the ledger here, on that evidence, with the
                // same idempotent settlement the process runtime's Yielded collector applies (whichever runs first ends
                // it), and leg 3's Main route is then admitted by the ledger's own Main mutex.
                val mainLanding = (landed.disposition as? SecondaryDisplayDisposition.Yielded)?.lease
                    ?.let { lease -> settleCompanionMainAppLandedOnBottom(lease) }
                val mainLedger = MimirSessionAuthority.state.value
                Log.i(
                    "MimirCompanionRuntime",
                    "exchange: main ledger read package=${mainApp.packageName} settled=${mainLanding?.settled} " +
                        "occupant=${mainLanding?.occupant?.packageName} mainPhase=${mainLedger.primaryAppSession?.phase} " +
                        "ledgerRev=${mainLedger.revision}",
                )

                // Leg 3 — the former Bottom app comes up on Main through the dock's own Main route, the
                // one path that starts a real PRIMARY session for it. That route animates for Quick launch; as part
                // of SWAP it cuts, the way the Bottom does .
                val mainRoute = CompanionQuickLaunchPrimaryRoute.dispatch(
                    packageName = bottomHost.packageName,
                    fromGeneral = false,
                    noAnimation = true,
                )
                val accepted = mainRoute == QuickLaunchPrimaryRouteResult.Accepted
                if (!accepted) {
                    // P12: named at the point of refusal, not only via the "Done" line's accepted=false. Round 5 (5b): the
                    // route makes no toast for this Bottom request. This leg's one toast names the move the player asked for,
                    // and the route's own answer goes to the log.
                    Log.w(
                        "MimirCompanionRuntime",
                        "exchange: former bottom app main route refused package=${bottomHost.packageName} route=$mainRoute",
                    )
                    showCompanionFeedbackToast("Couldn’t move $bottomLabel to the main screen")
                }
                Log.i(
                    "MimirCompanionRuntime",
                    "exchange done bottom→main=${bottomHost.packageName} " +
                        "main→bottom=${mainApp.packageName} accepted=$accepted " +
                        "mainStartFlags=0x${Integer.toHexString(nativeShelfLaunchFlags(noAnimation = true))}",
                )
            } finally {
                CompanionExchangeInFlight.active = false
                runningAppTransferJob = null
            }
        }
    }

    private fun moveSecondaryAppToPrimary(
        packageName: String,
        expectedHostOperationId: OperationId,
        reason: String,
    ) {
        // The Swap menu has already collapsed as accepted when this runs, so every early exit below was a press
        // that did nothing on screen (noted by the round-4 native lane, 2026-09-11). Each now tells the player,
        // on the Bottom where Swap was pressed.
        if (runningAppTransferJob?.isActive == true) {
            Log.w("MimirCompanionRuntime", "swap move declined reason=transfer_job_active package=$packageName")
            showCompanionFeedbackToast(companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.TRANSFER_IN_FLIGHT, null))
            return
        }
        if (CompanionRecentsState.ui.value.launchLocked) {
            Log.w("MimirCompanionRuntime", "swap move declined reason=launch_locked package=$packageName")
            showCompanionFeedbackToast(companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.LAUNCH_LOCKED, null))
            return
        }
        val registry = secondaryTaskRegistry ?: run {
            Log.w("MimirCompanionRuntime", "swap move declined reason=registry_missing package=$packageName")
            showCompanionFeedbackToast(
                companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.REGISTRY_MISSING, installedAppLabel(packageName)),
            )
            return
        }
        val runtime = CompanionReturnCoordinator.runtime()
        if (!runtime.snapshot.value.isYieldedHost(packageName, expectedHostOperationId)) {
            Log.w(
                "MimirCompanionRuntime",
                "swap move declined reason=host_not_yielded package=$packageName " +
                    "expected=$expectedHostOperationId disposition=${runtime.snapshot.value.disposition::class.simpleName}",
            )
            showCompanionFeedbackToast(
                companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.HOST_NOT_YIELDED, installedAppLabel(packageName)),
            )
            return
        }

        CompanionRecentsState.clearLaunchLock()
        CompanionRecentsState.hide()
        runningAppTransferJob = lifecycleScope.launch {
            try {
                val runningApp = loadCompanionRunningApp(packageName)
                if (!runtime.snapshot.value.isYieldedHost(packageName, expectedHostOperationId)) {
                    Log.w("MimirCompanionRuntime", "swap move declined reason=host_changed_before_transfer package=$packageName")
                    showCompanionFeedbackToast(
                        companionSwapMoveDeclineMessage(CompanionSwapMoveDecline.HOST_CHANGED, runningApp.label),
                    )
                    return@launch
                }

                val transferToken = UUID.randomUUID()
                if (!CompanionRunningAppState.stageTransfer(transferToken, runningApp)) {
                    // N5 (2026-08-12): total silence before this fix -- the player's press to swap
                    // ${runningApp.label} to the main screen vanished with zero feedback. stageTransfer
                    // refuses for two structurally distinct reasons (a staging-window collision,
                    // CompanionRunningAppState.kt:82-88, or the session mutex, :96-105) that this
                    // Boolean cannot tell apart; differentiating them needs a typed result on
                    // stageTransfer itself, which lives in CompanionRunningAppState.kt -- outside this
                    // file. That related failure remains unresolved in its separate launch path.
                    // Reuses, verbatim, the exact sentence the adjacent swap-dispatch-failure arm below
                    // already shows for the same "the move did not happen" outcome -- zero new copy.
                    // P12: this refusal was silent (N5 above); the reason is still coarse (see N5) but
                    // now at least reaches logcat instead of vanishing with the toast.
                    Log.w(
                        "MimirCompanionRuntime",
                        "swap move declined reason=stage_transfer_refused package=$packageName",
                    )
                    showCompanionFeedbackToast("Couldn’t move ${runningApp.label} to the main screen")
                    return@launch
                }
                val swap = registry.swapToPrimary(packageName, transferToken)
                if (swap?.dispatchAccepted != true) {
                    CompanionRunningAppState.cancelTransfer(transferToken)
                    // P12: the ASTR `swap dispatch … accepted=false` line was the only trace of this.
                    Log.w(
                        "MimirCompanionRuntime",
                        "swap move declined reason=swap_dispatch_refused package=$packageName",
                    )
                    showCompanionFeedbackToast("Couldn’t move ${runningApp.label} to the main screen")
                    return@launch
                }

                // The display-0 transfer host owns confirmation and exact return clearing. Reclaim can proceed
                // independently while that host dispatches the target and waits to be covered on Main.
                val reclaimRequested = companionController?.restore(
                    reason = reason,
                    returningFromPackage = packageName,
                ) == true
                runtime.swapDispatchObserved(swap.operationId.toString())
                Log.i(
                    "MimirCompanionRuntime",
                    "running app transfer dispatched package=$packageName operation=${swap.operationId} " +
                        "reclaimRequested=$reclaimRequested",
                )
            } finally {
                runningAppTransferJob = null
            }
        }
    }

    private suspend fun loadCompanionRunningApp(packageName: String): CompanionRunningApp {
        val tracked = secondaryTaskRegistry?.state?.value?.recents.orEmpty()
        val cachedIcons = secondaryTabIconPaths
        return withContext(Dispatchers.IO) {
            companionRunningAppMetadata(
                packageName = packageName,
                trackedApps = tracked,
                nativeSnapshot = runCatching { loadNativeShelfSnapshot(this@MainActivity) }
                    .getOrElse { NativeShelfSnapshot() },
                cachedIconPaths = cachedIcons,
            )
        }
    }

    /** CR-11: the icon files for every installed app the shelf knows, so the tab manager shows real icons. */
    private fun loadSecondaryTabIconPaths(): Map<String, String> {
        val snapshot = loadNativeShelfSnapshot(this)
        return (snapshot.games + snapshot.apps)
            .asSequence()
            .mapNotNull { row -> row.iconPath?.takeIf { it.isNotBlank() }?.let { row.packageName to it } }
            .toMap()
    }

    /**
     * Map the registry's secondary sessions to the tab manager's tiles, newest first, then append the
     * Main-screen running app if it is not already listed. Only sessions Mimir launched are represented —
     * apps AND the game running on the bottom screen.
     */
    private fun runningTabsForDrawer(state: SecondaryDisplayState): List<CompanionAppEntry> =
        companionDrawerEntries(
            state = state,
            // A Bottom GAME tab is named by the live session, not by PackageManager: the ledger row knows
            // the emulator that was dispatched, the session knows which game is inside it.
            session = MimirSessionAuthority.state.value,
            iconPaths = secondaryTabIconPaths,
            running = CompanionRunningAppState.running.value,
            selfPackage = packageName,
        )

    /**
     * DEV proof hook: `adb ... am start ... --ez mimir.hashFirst true` proves the ON-DEMAND SAF HASHING +
     * IDENTITY chain end-to-end on a REAL file — reads the smallest primary game's persisted
     * `sourceDocumentUri`, opens it via [AndroidContentOpener], fingerprints it via
     * [com.gruning.mimir.rewrite.core.metadata.ResidentHashProbe] / FileHasher, THEN resolves that
     * fingerprint against the bundled offline DATs via [com.gruning.mimir.rewrite.core.metadata.BundledDatSource]
     * ([AssetDatProvider]). Logs the real SHA-1/size and the DAT's canonical name + collapse key under
     * `MimirHashProbe`. Reads a file already on the device; fully offline — sends nothing.
     */
    private fun maybeHashFirst() {
        if (intent?.getBooleanExtra("mimir.hashFirst", false) != true) return
        lifecycleScope.launch {
            val game = runCatching {
                com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
                    .get(this@MainActivity).gameDao().smallestHashablePrimary()
            }.getOrNull()
            if (game == null) Log.w("MimirHashProbe", "no hashable game (sourceDocumentUri null)") else hashAndIdentify(game)
        }
    }

    /**
     * DEV proof hook: `--ez mimir.identifyRom true` — like [maybeHashFirst] but targets the smallest
     * CARTRIDGE ROM (nes/gb/gbc/gba/n64/megadrive), which positively matches a bundled No-Intro DAT far more
     * reliably than a disc PLAYLIST (`.m3u`, not a disc image) or a chdman-version-sensitive `.chd`. Proves a
     * real POSITIVE identity end-to-end, fully offline.
     */
    private fun maybeIdentifyRom() {
        if (intent?.getBooleanExtra("mimir.identifyRom", false) != true) return
        lifecycleScope.launch {
            val carts = listOf("nes", "gb", "gbc", "gba", "n64", "megadrive")
            val game = runCatching {
                com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
                    .get(this@MainActivity).gameDao().smallestHashablePrimaryIn(carts)
            }.getOrNull()
            if (game == null) Log.w("MimirHashProbe", "no hashable cart ROM") else hashAndIdentify(game)
        }
    }

    /**
     * DEV proof hook: `--ez mimir.identifyAll true` — the full coverage probe. For every platform with a
     * hashable member, fingerprints its smallest real file (>4 KB, so a `.chd` disc is reached behind an
     * `.m3u`) via the format-aware [com.gruning.mimir.rewrite.core.metadata.RomFingerprinter] and resolves
     * it against the bundled DATs. One HASH + IDENTIFY line per platform → the real match coverage.
     */
    private fun maybeIdentifyAll() {
        if (intent?.getBooleanExtra("mimir.identifyAll", false) != true) return
        lifecycleScope.launch {
            val dao = com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory.get(this@MainActivity).gameDao()
            val platforms = runCatching { dao.distinctHashablePlatforms() }.getOrNull()?.sorted() ?: return@launch
            Log.i("MimirHashProbe", "identifyAll over ${platforms.size} platforms: $platforms")
            for (p in platforms) {
                if (p == "dreamcast") { identifyDiscTracks(dao, p); continue } // multi-track: .chd OR .gdi/.cue
                val game = runCatching { dao.smallestHashableMember(p) }.getOrNull() ?: continue
                hashAndIdentify(game)
            }
        }
    }

    // Platforms the DAT hash path can't reach — identified by a NON-hash key (shortname / Title ID / disc
    // Game ID) via the bundled id-catalogs. Cheap: no full-file hash (avoids hashing a 1.5 GB .iso).
    private val idKeyedPlatforms = setOf("arcade", "switch", "gamecube", "wii", "ps3")

    /** Identify [game]: by extracted key for id-keyed platforms, else by content-hash against the DATs. */
    private suspend fun hashAndIdentify(game: com.gruning.mimir.rewrite.core.storage.entity.GameEntity) {
        if (game.platformId in idKeyedPlatforms) {
            val idMatched = identifyByKey(game)
            // PS3 keeps the Redump whole-image hash as a fallback when neither filename nor PARAM.SFO
            // supplies a title id. Other keyed platforms have no compatible hash fallback.
            if (idMatched || game.platformId != "ps3") return
        }
        runCatching {
            val probe = com.gruning.mimir.rewrite.core.metadata.ResidentHashProbe(AndroidContentOpener(this@MainActivity))
            val evidence = probe.fingerprintEvidenceFor(
                game.platformId,
                game.sourceDocumentUri,
                game.managedPath,
            )
            val hash = evidence?.datHash
            Log.i(
                "MimirHashProbe",
                "HASH '${game.title}' [${game.platformId}] size=${game.sizeBytes} -> " +
                    "crc32=${hash?.crc32} sha1=${hash?.sha1} bytes=${hash?.sizeBytes}",
            )
            if (
                evidence?.proof ==
                com.gruning.mimir.rewrite.core.metadata.RomFingerprintProof.CHD_HEADER_CLAIM
            ) {
                Log.i(
                    "MimirHashProbe",
                    "CHD_HEADER_CANDIDATE [${game.platformId}] -> authority deferred to libchdr payload replay",
                )
                return@runCatching
            }
            if (hash != null) {
                val platform = com.gruning.mimir.rewrite.core.model.PlatformId(game.platformId)
                val dat = com.gruning.mimir.rewrite.core.metadata.BundledDatSource(AssetDatProvider(this@MainActivity))
                val record = dat.lookup(com.gruning.mimir.rewrite.core.metadata.MetadataQuery(platform, contentHash = hash))
                val collapse = dat.collapseKeyFor(platform, hash)
                Log.i(
                    "MimirHashProbe",
                    "IDENTIFY [${game.platformId}, ${dat.entryCount(platform)} DAT entries] -> " +
                        (record?.let { "'${it.canonicalTitle}' [${it.origin}] collapse='$collapse'" } ?: "no DAT match"),
                )
            }
        }.onFailure { Log.e("MimirHashProbe", "hash probe failed", it) }
    }

    /**
     * Multi-track disc identify (dreamcast): a game is either ONE `.chd` (matches the MAMERedump `<disk>`
     * DAT only after libchdr verifies raw payload) OR a `.gdi`/`.cue` + track `.bin`s (matches the Redump
     * per-track DAT by track sha1).
     * Hashes loose members smallest-first, skipping tiny TOCs and huge (>256 MB) tracks. This developer
     * readout reports CHD headers only as provisional; production authority is owned by the enricher's
     * seekable libchdr payload replay. Shared filler tracks are dropped by the packed index's ambiguity
     * sentinel.
     */
    private suspend fun identifyDiscTracks(
        dao: com.gruning.mimir.rewrite.core.storage.dao.GameDao,
        platform: String,
    ) {
        runCatching {
            val members = dao.hashableMembers(platform)
            val probe = com.gruning.mimir.rewrite.core.metadata.ResidentHashProbe(AndroidContentOpener(this@MainActivity))
            val dat = com.gruning.mimir.rewrite.core.metadata.BundledDatSource(AssetDatProvider(this@MainActivity))
            val pid = com.gruning.mimir.rewrite.core.model.PlatformId(platform)
            for (m in members) {
                val fn = rawFileName(m.sourceDocumentUri, m.managedPath) ?: ""
                val cheap = fn.endsWith(".chd", true) || fn.endsWith(".gdi", true)
                val size = m.sizeBytes ?: 0L
                if (!cheap && (size < 4096 || size > 256L * 1024 * 1024)) continue // skip TOCs + huge tracks
                val evidence = probe.fingerprintEvidenceFor(
                    platform,
                    m.sourceDocumentUri,
                    m.managedPath,
                ) ?: continue
                if (
                    evidence.proof ==
                    com.gruning.mimir.rewrite.core.metadata.RomFingerprintProof.CHD_HEADER_CLAIM
                ) {
                    Log.i(
                        "MimirHashProbe",
                        "CHD_HEADER_CANDIDATE [$platform via '$fn'] -> authority deferred to libchdr payload replay",
                    )
                    continue
                }
                val hash = evidence.datHash
                val rec = dat.lookup(com.gruning.mimir.rewrite.core.metadata.MetadataQuery(pid, contentHash = hash))
                if (rec != null) {
                    Log.i(
                        "MimirHashProbe",
                        "IDENTIFY [$platform via '$fn', ${dat.entryCount(pid)} DAT] -> '${rec.canonicalTitle}' " +
                            "[${rec.origin}] collapse='${dat.collapseKeyFor(pid, hash)}'",
                    )
                    return@runCatching
                }
            }
            Log.i("MimirHashProbe", "IDENTIFY [$platform] -> no DAT match across ${members.size} members")
        }.onFailure { Log.e("MimirHashProbe", "disc probe failed", it) }
    }

    /** Diagnostic keyed-evidence probe; filename observations are reported as candidates, never identities. */
    private suspend fun identifyByKey(game: com.gruning.mimir.rewrite.core.storage.entity.GameEntity): Boolean =
        runCatching {
            val fileName = rawFileName(game.sourceDocumentUri, game.managedPath)
            val head = if (game.platformId == "gamecube" || game.platformId == "wii") readHead(game, 512) else null
            val idSrc = com.gruning.mimir.rewrite.core.metadata.BundledIdSource(AssetDatProvider(this@MainActivity))
            var resolution = idSrc.resolve(game.platformId, fileName, head?.first, head?.second ?: 0)
            if (game.platformId == "ps3") {
                val opener = AndroidContentOpener(this@MainActivity)
                for (candidate in listOf("PARAM.SFO", "../PARAM.SFO", "../../PARAM.SFO")) {
                    val stream = opener.openRelated(game.sourceDocumentUri, game.managedPath, candidate) ?: continue
                    val sfo = stream.use {
                        val bytes = ByteArray(64 * 1024)
                        var length = 0
                        while (length < bytes.size) {
                            val read = it.read(bytes, length, bytes.size - length)
                            if (read < 0) break
                            length += read
                        }
                        bytes to length
                    }
                    val candidateResolution = idSrc.resolve("ps3", fileName, sfo.first, sfo.second)
                    val embedded = when (candidateResolution) {
                        is com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Conflict -> true
                        is com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified ->
                            candidateResolution.match.binding ==
                                com.gruning.mimir.rewrite.core.metadata.BundledIdBinding.EMBEDDED_HEADER
                        com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.NoMatch -> false
                    }
                    if (embedded) {
                        resolution = candidateResolution
                        break
                    }
                }
                val hasEmbedded = when (resolution) {
                    is com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Conflict -> true
                    is com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified ->
                        resolution.match.binding ==
                            com.gruning.mimir.rewrite.core.metadata.BundledIdBinding.EMBEDDED_HEADER
                    com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.NoMatch -> false
                }
                if (!hasEmbedded) {
                    val isoHead = readHead(game, 16 * 2_048 + 40 + 32)
                    if (isoHead != null) {
                        resolution = idSrc.resolve("ps3", fileName, isoHead.first, isoHead.second)
                    }
                }
            }
            val description = when (resolution) {
                is com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified -> {
                    val match = resolution.match
                    "'${match.record.canonicalTitle}' [${match.record.origin}; ${match.binding}]"
                }
                is com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Conflict ->
                    "CONFLICT embedded='${resolution.embedded.record.canonicalTitle}' " +
                        "filename='${resolution.filename.record.canonicalTitle}'"
                com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.NoMatch -> "no ID match"
            }
            Log.i(
                "MimirHashProbe",
                "IDENTIFY [${game.platformId}, ${idSrc.entryCount(game.platformId)} cat] fn='$fileName' -> " +
                    description,
            )
            when (resolution) {
                is com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Conflict -> true
                is com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified ->
                    resolution.match.binding ==
                        com.gruning.mimir.rewrite.core.metadata.BundledIdBinding.EMBEDDED_HEADER
                com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.NoMatch -> false
            }
        }.getOrElse {
            Log.e("MimirHashProbe", "id probe failed", it)
            false
        }

    /** The raw on-disk basename (SAF-decoded) — keeps switch `[titleid]` / arcade shortname intact. */
    private fun rawFileName(documentUri: String?, managedPath: String?): String? {
        val s = documentUri ?: managedPath ?: return null
        return android.net.Uri.decode(s).substringAfterLast('/').ifBlank { null }
    }

    /** Read up to [n] head bytes of the file, off the main thread (for disc Game ID extraction). */
    private suspend fun readHead(
        game: com.gruning.mimir.rewrite.core.storage.entity.GameEntity,
        n: Int,
    ): Pair<ByteArray, Int>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val stream = AndroidContentOpener(this@MainActivity).open(game.sourceDocumentUri, game.managedPath)
            ?: return@withContext null
        stream.use {
            val buf = ByteArray(n)
            var len = 0
            while (len < n) {
                val r = it.read(buf, len, n - len)
                if (r < 0) break
                len += r
            }
            buf to len
        }
    }

    /**
     * PRODUCT hook: `--ez mimir.enrich true` runs the identity enricher over the indexed library — resolves
     * each game's canonical title + collapse key + provenance from the bundled DATs/id-catalogs and persists
     * them (COALESCE(canonicalTitle, title) then drives every tile). Runs after a scan in the real flow.
     * Fully offline; off the main thread.
     */
    /**
     * DEV proof hook: `adb ... am start ... --es mimir.getArt <gameId>` runs the Library's per-game Get Art
     * (`ArtworkFetchController.fetchOneArtwork`) for that row and logs the verdict, so a device check can
     * exercise the `pc_games` name-search path (reported 2026-09-11, Blasphemous 2) from a shell
     * without driving the Library UI. Logs under the same tag the route's own identification line uses.
     */
    private fun maybeGetArt() {
        val gameId = intent?.getStringExtra("mimir.getArt")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        lifecycleScope.launch {
            val result = ArtworkFetchController.fetchOneArtwork(this@MainActivity, gameId, explicitPress = true)
            Log.i("MimirArtFetch", "getArt hook game=$gameId result=$result")
        }
    }

    private fun maybeEnrich() {
        if (intent?.getBooleanExtra("mimir.enrich", false) != true) return
        lifecycleScope.launch { runEnrichment() }
    }

    /** Debug proof hook: `--ez mimir.refreshDats true` requests one bounded catalog refresh. */
    private fun maybeRefreshDats() {
        if (intent?.getBooleanExtra("mimir.refreshDats", false) != true) return
        DatRefreshWorker.runNow(this)
        Log.i("MimirEnrich", "dat refresh enqueued (manual)")
    }

    /**
     * DEV hook: `--es mimir.setTheme <storageKey>` applies + persists a Norse theme so all identities
     * can be device-verified without the Appearance picker. Keys: default/gold/crimson/midnight/nightmare/
     * void/terra/love/baldr (unknown → DEFAULT). The whole app re-skins live via [ThemeStore] → core-ui MimirTheme.
     */
    private fun maybeSetTheme() {
        val key = intent?.getStringExtra("mimir.setTheme") ?: return
        val id = com.gruning.mimir.rewrite.core.ui.MimirThemeId.fromStorageKey(key)
        ThemeStore.setTheme(this, id)
        Log.i("MimirTheme", "theme set -> ${id.storageKey} (${id.label})")
    }

    /**
     * DEV hook: `--es mimir.setProfileName "Example Player"` persists a profile display name so the Profile row +
     * name-edit round-trip can be device-verified without driving the touch keyboard. Stored via
     * [ProfileNameStore] (trimmed, local-only); the Profile row re-reads it live.
     */
    private fun maybeSetProfileName() {
        val name = intent?.getStringExtra("mimir.setProfileName") ?: return
        ProfileNameStore.setName(this, name)
        Log.i("MimirProfile", "profile name set -> '${ProfileNameStore.displayName.value}'")
    }

    /** DEV hook: `--ez mimir.regroup true` runs ONLY the collapse regrouper on the resolved library. */
    private fun maybeRegroup() {
        if (intent?.getBooleanExtra("mimir.regroup", false) != true) return
        lifecycleScope.launch {
            runCatching {
                val database = com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
                    .get(this@MainActivity)
                val dao = database.gameDao()
                val folded = com.gruning.mimir.rewrite.core.metadata.LibraryCollapseRegrouper(
                    dao,
                    database.gameIdentityDao(),
                ).regroup()
                Log.i("MimirEnrich", "regroup only: collapsed $folded dupes")
            }.onFailure { Log.e("MimirEnrich", "regroup failed", it) }
        }
    }

    /** DEV hook: `--ez mimir.clearIdentity true` wipes resolved identity so the coordinator re-enriches. */
    private fun maybeClearIdentity() {
        if (intent?.getBooleanExtra("mimir.clearIdentity", false) != true) return
        lifecycleScope.launch {
            runCatching {
                val n = com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations.coordinator
                    .commitIndexMutation {
                        com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
                            .get(this@MainActivity).gameDao().clearAllIdentity()
                    }
                Log.i("MimirEnrich", "cleared identity on $n games")
            }.onFailure { Log.e("MimirEnrich", "clearIdentity failed", it) }
        }
    }

    /**
     * Local evidence hook for EP18's counts-only MAME dependency audit. It never gates launch, persists a
     * verdict, or logs dependency hashes: `--es mimir.auditMameEntryKey <exact-entry-key>`.
     */
    private fun maybeAuditMameDependency() {
        val targetEntryKey = intent?.getStringExtra("mimir.auditMameEntryKey")
            ?.takeIf(String::isNotBlank)
            ?: return
        lifecycleScope.launch {
            runCatching {
                val database = com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
                    .get(this@MainActivity)
                val provider = AssetDatProvider(this@MainActivity)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.gruning.mimir.rewrite.core.metadata.MameRootDependencyAudit(
                        dao = database.gameDao(),
                        opener = AndroidContentOpener(this@MainActivity),
                        payloadProvider = provider,
                    ).audit(targetEntryKey)
                }
            }.onSuccess { result ->
                Log.i("MimirMameAudit", "counts-only result=$result")
            }.onFailure { error ->
                Log.e("MimirMameAudit", "audit failed without a verdict", error)
            }
        }
    }

    /** Build + run the [com.gruning.mimir.rewrite.core.metadata.LibraryIdentityEnricher]; logs the report. */
    private suspend fun runEnrichment() {
        runCatching {
            val database =
                com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory.get(this@MainActivity)
            val dao = database.gameDao()
            if (intent?.getBooleanExtra("mimir.enrichReset", false) == true) {
                val cleared = com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations.coordinator
                    .commitIndexMutation {
                        dao.clearUnmatchedIdentity()
                    }
                Log.i("MimirLibraryProbe", "ENRICH reset: cleared $cleared unmatched games to re-attempt")
            }
            val provider = AssetDatProvider(this@MainActivity)
            val enricher = com.gruning.mimir.rewrite.core.metadata.LibraryIdentityEnricher(
                dao = dao,
                datSource = com.gruning.mimir.rewrite.core.metadata.BundledDatSource(provider),
                idSource = com.gruning.mimir.rewrite.core.metadata.BundledIdSource(provider),
                opener = AndroidContentOpener(this@MainActivity),
                // The dev probe mirrors the product's first-run pass.
                chdIdentity = com.gruning.mimir.rewrite.core.metadata.ChdIdentitySource.HEADER_DECLARED,
                identityDao = database.gameIdentityDao(),
                payloadProvider = provider,
            )
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { enricher.enrich() }
            Log.i("MimirLibraryProbe", "ENRICH done: matched ${report.matched}/${report.attempted} games")
        }.onFailure { Log.e("MimirLibraryProbe", "enrich failed", it) }
    }

    /**
     * DEV/utility hook: `adb ... am start ... --ez mimir.rescanGranted true` re-runs the REAL scan over
     * the folder the user already granted (the first persisted SAF read permission) — no re-pick needed.
     * Used to re-index the installed library with the latest resolver after an app update. Logs the
     * ScanResult + per-platform counts under `MimirLibraryProbe`. Local-only; index-in-place.
     */
    /**
     * DEV/QA repair hook: `adb ... am start ... --es mimir.removeSource <exact rootUri>` removes ONE
     * additional Library source through the product's own [LibraryRepository.removeAdditionalSource] --
     * the same call the Settings removal row makes, with its transaction, its entry pruning and its
     * identity-winner reconciliation.
     *
     * **Written to repair damage a QA hook caused, and shaped by that.** `maybeRescanGranted` above
     * rescans with a SAF grant URI, `rescan` upserts whatever URI it is handed as a source root, and on
     * 2026-08-26 that registered a second root for a folder already added by path. Every ROM under it was
     * indexed twice and 25 games grew a phantom "2 versions" badge.
     *
     * So this one is deliberately the narrowest thing that can do the job:
     *  - it takes an EXACT `rootUri` and matches nothing by prefix, pattern, or "first one";
     *  - [LibraryRepository.removeAdditionalSource] refuses a primary source outright, so this hook
     *    structurally cannot remove the library itself, only an additional root;
     *  - it logs the resolved outcome, including [SourceRemovalResult.NotFound], because a repair hook
     *    that silently does nothing is how the original defect stayed invisible for two days.
     *
     * DEBUG only, like every `mimir.*` hook -- dead in a release build.
     */
    private fun maybeRemoveSource() {
        val rootUri = intent?.getStringExtra("mimir.removeSource")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        lifecycleScope.launch {
            runCatching {
                val repo = LibraryStore.repository(this@MainActivity)
                val before = repo.observeSources().first()
                Log.i(
                    "MimirLibraryProbe",
                    "removeSource requested rootUri=$rootUri sourcesBefore=${before.size} " +
                        "match=${before.count { it.rootUri == rootUri }}",
                )
                val result = repo.removeAdditionalSource(rootUri)
                val after = repo.observeSources().first()
                Log.i(
                    "MimirLibraryProbe",
                    "removeSource outcome=${result::class.simpleName} sourcesAfter=${after.size} " +
                        "remaining=${after.joinToString { it.rootUri }}",
                )
            }.onFailure { Log.e("MimirLibraryProbe", "removeSource probe failed", it) }
        }
    }

    private fun maybeRescanGranted() {
        if (intent?.getBooleanExtra("mimir.rescanGranted", false) != true) return
        if (!intent?.getStringExtra("mimir.launchPlatform").isNullOrBlank()) return
        // `firstOrNull` was wrong, and it failed SILENTLY in the one way that matters: the app can hold
        // grants for SD cards that are no longer in the device, and an arbitrary pick lands on a dead one.
        // Measured 2026-08-26 on the Odin -- the hook chose a grant for volume `691E-7BEA` while the only
        // mounted card, and the one every `source_roots` row names, is `6A7F-FCFE`. The scan came back
        // `filesSeen=0, outcome=Failed(SAF provider returned no cursor)` and nothing was re-indexed, so any
        // device verification built on this hook was quietly measuring a library that had not been rescanned.
        // Try every read grant and stop at the first that actually produces a cursor.
        val grants = contentResolver.persistedUriPermissions.filter { it.isReadPermission }
        if (grants.isEmpty()) {
            Log.w("MimirLibraryProbe", "no persisted SAF grant to rescan")
            return
        }
        lifecycleScope.launch {
            runCatching {
                val repo = LibraryStore.repository(this@MainActivity)
                // Log EVERY candidate and its outcome, not just the winner. A hook that reports only the
                // grant it happened to use is how the dead one stayed invisible.
                Log.i(
                    "MimirLibraryProbe",
                    "GRANTED RESCAN candidates=${grants.size} [" + grants.joinToString { it.uri.toString() } + "]",
                )
                var used: String? = null
                for (grant in grants) {
                    val result = repo.rescan(grant.uri.toString())
                    val usable = result.filesSeen > 0
                    Log.i("MimirLibraryProbe", "GRANTED RESCAN try ${grant.uri} -> $result")
                    if (usable) {
                        used = grant.uri.toString()
                        break
                    }
                }
                val platforms = repo.observePlatforms().first()
                Log.i(
                    "MimirLibraryProbe",
                    "GRANTED RESCAN used=${used ?: "NONE — every persisted grant is dead"} | " +
                        "platforms=${platforms.size} [" +
                        platforms.joinToString { "${it.platformId.value}:${it.gameCount}" } + "]",
                )
                runEnrichment() // re-index → resolve canonical identity for the freshly-scanned library
            }.onFailure { Log.e("MimirLibraryProbe", "granted rescan failed", it) }
        }
    }

    /**
     * DEV proof hook: `adb ... am start ... --es mimir.scanPathValue real-lib` runs the REAL
     * walk → classify → dedup → Room pipeline over a File-path root the app can read without any
     * storage permission (its own external files dir; a relative value resolves under
     * `getExternalFilesDir`). Used to certify library COMPREHENSION over a faithful replica of an existing ROM tree (exact filenames + folders) without driving the SAF picker — the SAF
     * DocumentsContract walk is the same downstream pipeline. Logs the honest ScanResult + per-platform
     * counts under `MimirLibraryProbe`. Local-only; index-in-place; no move/copy; no network.
     */
    private fun maybeScanPath() {
        val value = intent?.getStringExtra("mimir.scanPathValue") ?: return
        val path = if (value.startsWith("/")) value else java.io.File(getExternalFilesDir(null), value).absolutePath
        lifecycleScope.launch {
            runCatching {
                val repo = LibraryStore.repository(this@MainActivity)
                val result = repo.rescan(path)
                val platforms = repo.observePlatforms().first()
                Log.i(
                    "MimirLibraryProbe",
                    "PATH SCAN $path -> $result | platforms=${platforms.size} [" +
                        platforms.joinToString { "${it.platformId.value}:${it.gameCount}" } + "]",
                )
            }.onFailure { Log.e("MimirLibraryProbe", "path scan failed", it) }
        }
    }

    /**
     * DEV proof hook: `adb ... am start ... --es mimir.overlayVisibility ALWAYS` sets the performance
     * overlay's scope (`OFF` / `GAMES_ONLY` / `ALWAYS`).
     *
     * **This exists so the performance overlay can be proven on a device without a human finding a menu.**
     * [PerformanceOverlayService]'s reader only runs while the overlay is enabled — a scope that lives four
     * taps deep in the Utility panel and could previously only be changed by hand. A feature whose device
     * proof depends on a human finding a menu is a feature that stops being device-proven the moment
     * nobody feels like it. (It was first written for the Bottom gauges panel, retired on 2026-09-12; the log tag stays `MimirGauges` because device QA protocols filter on it.)
     *
     * **Routed through [setPerformanceOverlayVisibility], never straight at the store.** That function
     * is where Usage Access, draw-over permission and the service's own start are checked; writing the
     * preference directly would let adb persist a scope the device cannot actually honour and leave the
     * setting claiming something untrue.
     */
    private fun maybeSetOverlayVisibility() {
        val requested = intent?.getStringExtra("mimir.overlayVisibility")?.trim()?.uppercase()
            ?: return
        val visibility = PerformanceOverlayVisibility.entries.firstOrNull { it.name == requested }
        if (visibility == null) {
            Log.w("MimirGauges", "overlay visibility \"$requested\" is not a scope")
            return
        }
        // Capabilities are refreshed FIRST. resolveTweakerUsageAccessState reads a cache that the
        // Utility panel fills when it opens, and an adb caller has not opened it -- so without this the
        // hook would report UNAVAILABLE on a device whose Usage Access is granted, and the failure
        // would look like the feature rather than like the probe.
        lifecycleScope.launch {
            TweakerBridge.refreshCapabilities()
            val result = setPerformanceOverlayVisibility(
                context = this@MainActivity,
                visibility = visibility,
                usageAccess = resolveTweakerUsageAccessState(
                    TweakerBridge.isTweakerInstalled(),
                    TweakerBridge.capabilities,
                ),
            )
            Log.i("MimirGauges", "overlay visibility ${visibility.name} -> $result")
        }
    }

    /**
     * DEV proof hook: `adb ... am start ... --ez mimir.probeTuner true` reports what this device
     * actually lets Mimir tune — discovered live, with no manifest, no pairing and no daemon.
     *
     * **This is the instrument the Tweaker never had.** Its qualification model could answer only
     * `ELIGIBLE` or a refusal code, and on the Thor it answered a refusal code for a reason
     * (`prot=signature` across two different signing keys) that no surface could tell apart from
     * *"this hardware cannot do it"*. That is the worst property an availability check can have: it
     * fails identically whether the device is incapable or the pairing is merely wrong. This prints the
     * hardware's own answer instead, and the hardware cannot be mistaken about itself.
     */
    private fun maybeProbeTuner() {
        if (intent?.getBooleanExtra("mimir.probeTuner", false) != true) return
        val availability = readTunerAvailability()
        Log.i(
            "MimirTuner",
            // `route` joined this line with the daemon route (2026-09-11): `pserver=true` no longer
            // means Mimir is the writer, and a probe that cannot say who owns the node reports a
            // capability the device may not let this process use.
            "pserver=${availability.canWrite} readable=${availability.canRead} " +
                "policies=${availability.policies.size} " +
                "route=${mimirTunerRouteLabel(MimirTunerDaemonRoute.decision())}",
        )
        availability.policies.forEach { policy ->
            Log.i(
                "MimirTuner",
                "${policy.kind} ${policy.label} id=${policy.id} steps=${policy.availableKhz.size} " +
                    "floor=${policy.floorKhz} ceiling=${policy.ceilingKhz} " +
                    "current=${policy.currentMaxKhz} capped=${policy.capped}",
            )
        }
        if (intent?.getBooleanExtra("mimir.probePServerWrite", false) == true) probePServerWrite()
    }

    /**
     * DEV proof hook: `--ei mimir.capPolicy 7 --ei mimir.capKhz 1708800` requests a ceiling, and
     * `--ez mimir.capWithdraw true` gives the device back what it had.
     *
     * **Both inputs are INTEGERS, and both are validated against what the kernel published** — the
     * policy id must match a discovered policy, and the frequency is snapped by
     * [MimirTunerWrite.nearestSupportedKhz] onto a step from that policy's own table. So no string from
     * adb can reach [PServerHost], which is the rule the whole file is built to keep: an adb-supplied
     * string in a privileged command would be a root shell reachable by anything that can start this
     * exported activity.
     *
     * It logs what is IN FORCE afterwards rather than what was asked for, because those are different
     * questions and this device answers them differently.
     *
     * **Both hooks now name the ROUTE and the OUTCOME, and both run off the main thread.** The
     * writer may be the Tweaker daemon rather than this process, so a
     * line reading only `inForce=` cannot say who moved the clock — or whether anyone did. The shape is
     * fixed because a device script greps it: `cap … route=… outcome=… admitted=… inForce=…` and
     * `withdraw route=… outcome=…`.
     */
    private fun maybeRequestCeiling() {
        // Discovered here rather than handed in. Nesting this inside maybeProbeTuner made WITHDRAW
        // depend on the PROBE flag, so a withdraw sent without `--ez mimir.probeTuner true` silently
        // did nothing and left a cap on the device -- which is exactly the shape of failure a restore
        // path must never have. It is now reachable on its own terms.
        val availability = readTunerAvailability()
        if (intent?.getBooleanExtra("mimir.capWithdraw", false) == true) {
            // OFF the main thread since 2026-09-11: on a device with a v10 Tweaker this is a Binder
            // round trip to the daemon, and a hook that blocked the main thread to make it would be
            // measuring the daemon's latency as Mimir's. The read-backs ride the same dispatcher.
            lifecycleScope.launch(Dispatchers.IO) {
                // The same bounded wait as the request arm, and for the stronger reason: a withdraw
                // refused because the route had not resolved leaves a cap ON the device, and the
                // operator's next move is usually to walk away from a handheld they believe is clear.
                awaitMimirTunerRouteResolved()
                val outcome = MimirTunerWrite.withdraw()
                Log.i(
                    "MimirTuner",
                    "withdraw route=${mimirTunerRouteLabel(outcome.route)} " +
                        "outcome=${outcome.outcomeLabel()}",
                )
                availability.policies.forEach { policy ->
                    Log.i("MimirTuner", "after withdraw ${policy.label} inForce=${readInForce(policy)}")
                }
            }
            return
        }
        val policyId = intent?.getIntExtra("mimir.capPolicy", Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE } ?: return
        val requestedKhz = intent?.getIntExtra("mimir.capKhz", 0)?.takeIf { it > 0 } ?: return
        val policy = availability.policies.firstOrNull { it.id == policyId }
        if (policy == null) {
            Log.w("MimirTuner", "no discovered policy with id $policyId")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // **Wait for the route before asking, because `onCreate` is EARLIER than the bind.** Thor,
            // 2026-09-11: the hook fired at 08:30:00.069 and was refused with `route=REFUSE`, and the
            // daemon's own answer landed at 08:30:00.130 -- 61 ms later. The question was asked at the
            // one moment it could only have one answer. Bounded, and expiry proceeds anyway so a device
            // that genuinely refuses still prints the refusal with its reason; see
            // [awaitMimirTunerRouteResolved] for why a wait is legitimate here and on no other surface.
            awaitMimirTunerRouteResolved()
            val outcome = MimirTunerWrite.requestCeiling(policy, requestedKhz)
            // ADMITTED and IN FORCE are different questions, and this device answers them differently:
            // admitted is what the writer asked the kernel for after its own snap (and, on the daemon
            // route, its clamp to the live floor); in-force is what the node reads back afterwards.
            val applied = outcome as? MimirTunerWriteOutcome.InForce
            Log.i(
                "MimirTuner",
                "cap ${policy.label} requested=$requestedKhz " +
                    "route=${mimirTunerRouteLabel(outcome.route)} outcome=${outcome.outcomeLabel()} " +
                    "admitted=${applied?.admittedKhz} inForce=${applied?.khz}",
            )
        }
    }

    /**
     * DEV proof hook: `--ez mimir.labTunerAxis true` arms the deterministic adoption proof's synthetic
     * axis, `false` removes it.
     *
     * **Why a synthetic axis is worth a hook at all.** The real QoS node has a second writer, observed
     * on the Thor, so a live adoption run can fail because the staleness guard CORRECTLY threw
     * away a record whose cap somebody else had lifted. That is passing behaviour reported as failure,
     * and an instrument with that property cannot certify anything. The synthetic axis has exactly one
     * writer, so the same production code path — same discovery, same capture, same admission across the
     * Binder, same reconcile — produces the same answer every time, on any device.
     *
     * **It carries no input.** The extra is a boolean and nothing else; every id, frequency and row the
     * axis uses is a constant in the shared Tweaker contract. So this hook, unlike a hook that took a
     * path, cannot be used to make this app read or write anything the tree did not choose in advance.
     * The arming state is a directory inside this app's own `filesDir`, which is why it survives the
     * `am force-stop` the proof is built around.
     */
    private fun maybeArmLabTunerAxis() {
        val requested = intent?.takeIf { it.hasExtra("mimir.labTunerAxis") }
            ?.getBooleanExtra("mimir.labTunerAxis", false) ?: return
        if (requested) LabTunerAxisInstaller.arm(this) else LabTunerAxisInstaller.disarm(this)
    }

    // Deliberately left on RealMimirTunerNodes: the lab axis lives in this app's own filesDir, so the
    // real reader reads its `scaling_max_freq` correctly, and routing this too would be a production
    // edit the deterministic-proof design does not call for.
    private fun readInForce(policy: MimirTunerPolicy): Int? =
        MimirTunerWrite.readInForceKhz(policy, RealMimirTunerNodes())

    /**
     * Proof that the privileged path actually EXECUTES, not merely that the transaction was accepted.
     *
     * **The command is a fixed literal and there is no way to pass one in.** A debug hook that forwarded
     * an adb-supplied string to [PServerHost] would be a privileged shell reachable by any app that can
     * start this exported activity — the same class of hole `runDebugIntentHooks` is fail-closed to
     * prevent. So this writes one known marker to a path adb can read back, and nothing else.
     *
     * **It touches no clock.** Verifying the transport by capping a CPU would mean the first proof of
     * the write path was also an unrequested change to the player's device. The marker proves the same
     * thing — a privileged process ran our command — and proves it reversibly.
     */
    private fun probePServerWrite() {
        val accepted = PServerHost.run("echo mimir-pserver-ok > $PServerProbeMarkerPath")
        Log.i("MimirTuner", "pserver write transact accepted=$accepted marker=$PServerProbeMarkerPath")
    }

    /**
     * DEV proof hook: `adb ... am start ... --es mimir.holdJob SCAN --ei mimir.holdJobSeen 412
     * --ei mimir.holdJobTotal 2010 --ei mimir.holdJobSeconds 30` publishes ONE job into
     * [JobProgressState] and holds it there, so screen 2's rank (standing §5b) can be photographed in a
     * known state and then returns to absence on its own.
     *
     * **Why a held job rather than a real scan.** What §5b's rank decides is a RENDER: does the job take
     * the panel or a line beneath one. A real scan is a worse instrument for that question, not a better
     * one — it finishes faster than a screencap on any tree small enough to be safe, and it writes rows
     * into the installed library to answer a question about layout. The producer side (a real scan really
     * reporting into this channel) is a different claim with its own pins; this hook does not and cannot
     * stand in for it, and must not be cited as if it did.
     *
     * Omit `mimir.holdJobTotal` to hold a job with NO honest denominator — that is the null-fraction case
     * §5b turns on, and the only way to photograph "no bar" as distinct from "a bar at zero".
     */
    private fun maybeHoldJob() {
        val kindName = intent?.getStringExtra("mimir.holdJob")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val kind = JobKind.entries.firstOrNull { it.name.equals(kindName, ignoreCase = true) }
        if (kind == null) {
            Log.w("MimirJobProbe", "hold requested for unknown kind '$kindName'; known: ${JobKind.entries}")
            return
        }
        val seen = intent?.getIntExtra("mimir.holdJobSeen", 0) ?: 0
        // -1 rather than 0 as the absent marker: 0 is a real total the honesty contract already maps to
        // null, and conflating "not asked for" with "asked for zero" would hide which one was tested.
        val total = (intent?.getIntExtra("mimir.holdJobTotal", -1) ?: -1).takeIf { it >= 0 }
        val seconds = (intent?.getIntExtra("mimir.holdJobSeconds", 30) ?: 30).coerceIn(1, 300)
        lifecycleScope.launch {
            JobProgressState.report(kind, seen = seen, total = total)
            Log.i("MimirJobProbe", "HOLD $kind seen=$seen total=$total for ${seconds}s")
            try {
                delay(seconds * 1000L)
            } finally {
                // Cleared even if the scope is cancelled: a row that outlives its job is exactly the lie
                // JobProgressState's own contract forbids, and a probe may not leave one behind.
                JobProgressState.clear(kind)
                Log.i("MimirJobProbe", "RELEASED $kind")
            }
        }
    }

    /**
     * DEV proof hook: `adb ... am start ... --es mimir.launchPlatform arcade ...` launches a specific
     * indexed library target through the REAL [LaunchController]. Optional extras:
     *
     * - `--ez mimir.rescanGranted true` first rescans the persisted SAF grant inside this same coroutine,
     *   avoiding a race with launch proof.
     * - `--es mimir.launchTitleContains robotbwl` chooses the first visible game whose title contains it.
     * - `--es mimir.launchEmulatorId arcade.retroarch.aarch64.fbneo` strictly requests the per-call
     *   Open-With target without persisting an override; stale/unavailable ids abort the proof instead
     *   of falling through to Auto.
     *
     * Logs the selected game, readiness, and dispatch outcome under `MimirLaunchProbe`. Local-only; it
     * does not create games, mutate overrides, run identity enrichment, or bypass the launch engine.
     */
    private fun maybeLaunchTargetedGame() {
        val platformValue = intent?.getStringExtra("mimir.launchPlatform")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return
        val titleNeedle = intent?.getStringExtra("mimir.launchTitleContains")?.trim()
            ?.takeIf { it.isNotEmpty() }
        val emulatorOverride = intent?.getStringExtra("mimir.launchEmulatorId")?.trim()
            ?.takeIf { it.isNotEmpty() }
        val rescanGranted = intent?.getBooleanExtra("mimir.rescanGranted", false) == true
        lifecycleScope.launch {
            runCatching {
                val repo = LibraryStore.repository(this@MainActivity)
                if (rescanGranted) {
                    // Same grant-selection defect as [maybeRescanGranted], and it was here too -- a
                    // persisted grant can name an SD card that is no longer in the device, and picking
                    // one arbitrarily rescans nothing while reporting no error. Fixed at BOTH sites on
                    // 2026-08-26; fixing one and leaving its twin is how the next session rediscovers it.
                    val grants = contentResolver.persistedUriPermissions.filter { it.isReadPermission }
                    val grant = grants.firstOrNull { candidate ->
                        repo.rescan(candidate.uri.toString()).filesSeen > 0
                    }
                    if (grant == null) {
                        Log.w("MimirLaunchProbe", "target launch requested rescanGranted but no persisted SAF grant produced files (candidates=${grants.size} [" + grants.joinToString { it.uri.toString() } + "])")
                    } else {
                        val result = repo.rescan(grant.uri.toString())
                        val platforms = repo.observePlatforms().first()
                        Log.i(
                            "MimirLaunchProbe",
                            "TARGET RESCAN ${grant.uri} -> $result | platforms=${platforms.size} [" +
                                platforms.joinToString { "${it.platformId.value}:${it.gameCount}" } +
                                "] | enrichment=skipped_for_launch_probe",
                        )
                    }
                }
                val platformId = PlatformId(platformValue)
                val games = repo.observeGames(platformId).first()
                val game = when (titleNeedle) {
                    null -> games.firstOrNull()
                    else -> games.firstOrNull { it.title.contains(titleNeedle, ignoreCase = true) }
                } ?: run {
                    Log.w(
                        "MimirLaunchProbe",
                        "target launch found no game platform=$platformValue titleContains=${titleNeedle ?: "*"} visible=${games.size}",
                    )
                    return@runCatching
                }
                val overrideId = emulatorOverride?.let { EmulatorId.fromNativeLauncherKey(it) }
                val controller = LaunchInjection.controller(this@MainActivity)
                if (overrideId != null) {
                    val options = controller.openWithOptions(game.gameId, game.platformId)
                    val requested = options.candidates.firstOrNull { it.emulatorId == overrideId }
                    if (requested?.installed != true) {
                        Log.e(
                            "MimirLaunchProbe",
                            "TARGET LAUNCH ABORT '${game.title}' gameId=${game.gameId.value} " +
                                "platform=${game.platformId.value} emulatorOverride=${overrideId.value} " +
                                "candidatePresent=${requested != null} installed=${requested?.installed ?: false} " +
                                "reason=override_not_available",
                        )
                        return@runCatching
                    }
                }
                val result = controller.launch(
                    gameId = game.gameId,
                    platformId = game.platformId,
                    overrideEmulatorId = overrideId,
                )
                val target = (result.readiness as? LaunchReadiness.Ready)?.target
                Log.i(
                    "MimirLaunchProbe",
                    "TARGET LAUNCH '${game.title}' gameId=${game.gameId.value} platform=${game.platformId.value} " +
                        "emulatorOverride=${overrideId?.value ?: "auto"} launched=${result.launched} " +
                        "emulator=${target?.emulator?.id?.value ?: "-"} " +
                        "readiness=${result.readiness::class.simpleName} outcome=${result.outcome}",
                )
            }.onFailure { Log.e("MimirLaunchProbe", "target launch probe failed", it) }
        }
    }

    /**
     * DEV proof hook: `adb ... am start ... --es mimir.openGamelist arcade` opens the REAL Library
     * gamelist for the named platform — the state
     * source review found totally unreachable, because
     * every existing launch hook (`mimir.launchPlatform` above) dispatches straight through
     * [LaunchController] and never opens the gamelist screen on the way. `MainActivity` has no stable
     * reference to the Compose-scoped [MimirNavigationCoordinator] (it is `remember`ed inside
     * [MimirAppCoordinator]'s composition), so this hook only resolves + logs the request here, then
     * publishes it on [DebugNavigationBridge]; [MimirAppCoordinator]'s own collector performs the real
     * `openPlatformInLibrary` call, the Shelf Lab's production platform entry. It writes the real
     * model and destination; when Library's shelf is already mounted it also enters GamelistScope.
     * Other destinations leave scope creation to Library's mount effect. The collector logs the
     * outcome. Logs the resolved visible-game count under `MimirNavProbe` before publishing — a platform
     * with zero visible games "succeeds" mechanically but is useless as a scroll-capture fixture, and this
     * is the only place that can tell the difference. Local-only; does not scan or mutate the library.
     */
    private fun maybeOpenGamelist() {
        val platformId = parseOpenGamelistPlatformId(intent?.getStringExtra("mimir.openGamelist")) ?: return
        lifecycleScope.launch {
            runCatching {
                val repo = LibraryStore.repository(this@MainActivity)
                val games = repo.observeGames(platformId).first()
                Log.i(
                    "MimirNavProbe",
                    "openGamelist requested platform=${platformId.value} visibleGames=${games.size}",
                )
                DebugNavigationBridge.publish(DebugNavigationRequest.OpenGamelist(platformId))
            }.onFailure { Log.e("MimirNavProbe", "openGamelist probe failed", it) }
        }
    }

    /**
     * DEV/QA proof hook: `adb ... am start ... --es mimir.openFiles <pick|manage>` opens the REAL file
     * manager through `FileBrowserStore.open` -- the one primitive both production callers converge on
     * (`MimirSettingsCoordinator.launchSourcePicker`'s add-ROMs picker for PICK,
     * `AppBrightnessCoordinator.openFileBrowser`'s Utility "Files" row for MANAGE). This is the door
     * source review found unreachable by every existing `mimir.*`
     * extra: the only in-app door is `BrightnessControlKey.OPEN_FILES` on the Utility rail, which
     * `mimir.openOverlay` cannot name, and its `ControllerAction.GLOBAL_UTILITY` shortcut has no default
     * binding -- on top of that session finding every CONFIRM variant independently inert over adb
     * regardless of door.
     *
     * Stays fully synchronous, matching [maybeOpenOverlay] rather than [maybeOpenGamelist]:
     * `maybeOpenGamelist`'s `lifecycleScope.launch` exists only to await a repo read for its own
     * diagnostic log (`visibleGames=`), and there is no equivalent cheap fact to await before publishing
     * here.
     *
     * **Dispatches BELOW both production wrappers' own route gates, deliberately** -- the same way
     * [maybeOpenGamelist] calls the navigation primitive directly rather than replaying whatever caller
     * sits in front of it. A real Confirm-press on the Settings add-ROMs row can bounce to
     * `AddGamesRoute.BUSY` or `AddGamesRoute.SYSTEM_PICKER`; a real Confirm-press on the Utility Files row
     * can bounce to `FilesRouteDecision.REQUEST_ALL_FILES_ACCESS` instead of opening. This hook reaches
     * `FileBrowserStore.open` directly, so it opens regardless -- MANAGE opens even without all-files
     * access, PICK opens even mid-`sourceOperationRunning`. **A device without all-files access will show
     * a real but sparse/empty-looking manager, not a bug** -- `StorageVolumeListing.browserRoot` still
     * resolves a root, but MANAGE's own production caller checks that grant first for a reason.
     *
     * `pick`/`manage` map to `FileBrowserMode.PICK`/`FileBrowserMode.MANAGE` via [parseOpenFilesMode]; any
     * other value -- absent, blank, or unrecognised -- means "hook not fired," never a silent default,
     * because defaulting to MANAGE would hand a QA lane the destructive half (delete/move/the queue) by
     * accident. Publishes on [DebugNavigationBridge] for the same reason [maybeOpenGamelist] does -- no
     * stable reference to `FileBrowserStore`'s Compose-scoped `context`/`authority` exists here;
     * [MimirAppCoordinator]'s own collector performs the real `FileBrowserStore.open` call. Logs the
     * requested mode under `MimirNavProbe`. Local-only; does not itself open, delete, move, or extract
     * anything.
     */
    private fun maybeOpenFiles() {
        val mode = parseOpenFilesMode(intent?.getStringExtra("mimir.openFiles")) ?: return
        Log.i("MimirNavProbe", "openFiles requested mode=$mode")
        DebugNavigationBridge.publish(DebugNavigationRequest.OpenFiles(mode))
    }

    /**
     * R5d/R5e only: fills [GameOpeningIntroState] directly so [MimirLogoStage] can be photographed with a
     * known logo aspect. Goes nowhere near the launch path — see [parseIntroLogoPath]'s warning, and say so
     * on any shot taken with it. No bridge here because the intro state is process-wide, not Compose-scoped.
     */
    private fun maybeShowIntroLogo() {
        val logoPath = parseIntroLogoPath(intent?.getStringExtra("mimir.introLogo")) ?: return
        val uri = android.net.Uri.fromFile(java.io.File(logoPath)).toString()
        Log.i("MimirNavProbe", "introLogo SYNTHETIC plan (not a launch) logo=$logoPath")
        GameOpeningIntroState.show(
            GameOpeningIntroPlan(
                gameIdValue = "debug:intro-logo",
                title = intent?.getStringExtra("mimir.introTitle")?.trim().orEmpty()
                    .ifEmpty { "R5d/R5e logo stage" },
                heroUri = uri,
                logoUri = uri,
                audioUri = null,
            ),
        )
    }

    /**
     * DEV proof hook: `adb ... am start ... --es mimir.shelfAction library` presses a nav-rail seat
     * through the REAL dispatcher (`MimirNavigationCoordinator.onShelfQuickAction`) — the exact function
     * a Confirm-press on that dock icon calls.
     *
     * **This exists because a reported defect was unmeasurable without it.** The regression report described the Thor
     * slow traversing platforms on the Library shelf, and no adb input could get a device there:
     * `DPAD_DOWN` will not move focus off the shelf into the rail, `BUTTON_R1` will not change
     * destination (both verified by screenshot, focus unchanged), and [maybeOpenGamelist] lands one level
     * BELOW the shelf, inside a platform's gamelist. Measuring the Home shelf instead and reporting it as
     * the Library shelf would have been the third surface substitution in one investigation.
     *
     * Stays fully synchronous, matching [maybeOpenFiles] rather than [maybeOpenGamelist] —
     * `maybeOpenGamelist`'s `lifecycleScope.launch` exists only to await a repository read for its own
     * visible-game log line, and there is nothing to await here. Publishes on [DebugNavigationBridge] for
     * the same reason every sibling does: this Activity holds no stable reference to the
     * Compose-`remember`ed [MimirNavigationCoordinator].
     *
     * Local-only. Navigates; scans nothing, launches nothing, mutates no library state.
     */
    private fun maybeShelfQuickAction() {
        val action = parseShelfQuickAction(intent?.getStringExtra("mimir.shelfAction")) ?: return
        Log.i("MimirNavProbe", "shelfAction requested action=$action")
        DebugNavigationBridge.publish(DebugNavigationRequest.ShelfQuickAction(action))
    }

    /**
     * DEV proof hook: `adb ... am start ... --es mimir.openOverlay <canonicalRowId>` opens a Settings
     * overlay through the REAL row-activation dispatcher (`MimirSettingsCoordinator.onRowActivate`,
     * `MimirSettingsCoordinator.kt:472-545`) — the exact function a Confirm-press on that Settings row
     * calls. Any canonical row id it recognizes works, including the two that mount a REAL
     * on-screen keyboard host unconditionally the
     * moment they open (`profile/name`, `appearance/enrichment/steamgriddb`,
     * `MimirAppHost.kt:1850-1930`) — this is deliberately how the "keyboard on a named surface" scenario
     * is reached, through the same mechanism, rather than a bespoke third hook. Publishes on
     * [DebugNavigationBridge] for the same reason [maybeOpenGamelist] does — no stable reference to the
     * Compose-scoped `MimirSettingsCoordinator` exists here. Logs the requested row id under
     * `MimirNavProbe`; [MimirAppCoordinator]'s collector logs the coordinator-observed before/after top
     * overlay once dispatched.
     */
    private fun maybeOpenOverlay() {
        val rowId = parseOpenOverlayRowId(intent?.getStringExtra("mimir.openOverlay")) ?: return
        Log.i("MimirNavProbe", "openOverlay requested rowId=$rowId")
        DebugNavigationBridge.publish(DebugNavigationRequest.OpenSettingsRow(rowId))
    }

    /**
     * DEV proof hook: `adb ... am start ... --ez mimir.dismissTopOverlay true` sends one synthetic CANCEL
     * through the REAL overlay dispatch (`MimirOverlayCoordinator.dispatch`, reached by a physical
     * B/Back press at `MimirAppInputDispatch.kt:287`) — pairs with [maybeOpenOverlay] to close whatever it
     * opened. A no-op when no overlay is open (the real dispatch function already handles that: `top?.let
     * { ... }` returns null). Publishes on [DebugNavigationBridge] for the same reason [maybeOpenGamelist]
     * does.
     */
    private fun maybeDismissTopOverlay() {
        if (intent?.getBooleanExtra("mimir.dismissTopOverlay", false) != true) return
        Log.i("MimirNavProbe", "dismissTopOverlay requested")
        DebugNavigationBridge.publish(DebugNavigationRequest.DismissTopOverlay())
    }

    /** DEV proof hook: `adb ... am start ... --ez mimir.pickFolder true` opens the real SAF folder picker. */
    private fun maybePickFolder() {
        if (intent?.getBooleanExtra("mimir.pickFolder", false) != true) return
        pickFolderProbe.launch(null)
    }

    /**
     * DEV proof hook: `adb ... am start ... --ez mimir.seedDemo true` seeds a folder of representative
     * dummy ROM files into the app's OWN external files dir and runs the REAL scan → classify → dedup →
     * Room pipeline over it (no SAF pick, no user library needed). The Library UI observes the same Room
     * DB, so recognized + deduped games appear live. Local-only; reads its own files in place.
     */
    private fun maybeSeedDemoLibrary() {
        if (intent?.getBooleanExtra("mimir.seedDemo", false) != true) return
        // `--ez mimir.demoArt true` also drops LOCAL cover PNGs beside two ROMs so the offline box-art path
        // renders real art on those tiles (the rest keep the honest glyph) — a device-verifiable contrast.
        val withArt = intent?.getBooleanExtra("mimir.demoArt", false) == true
        val path = DemoSeeder.seed(this, withArt)
        lifecycleScope.launch {
            runCatching { LibraryStore.repository(this@MainActivity).rescan(path) }
        }
    }

    /**
     * DEV proof hook: `adb ... am start ... --ez mimir.launchFirst true` seeds the demo library, runs the
     * REAL scan, then drives the FULL launch stack — [LaunchController] resolves readiness (installed
     * emulator + Open-With + BIOS + locator) and, if [LaunchReadiness.Ready], dispatches a real launch
     * Intent to the resolved emulator on THIS device. Proves the "playable dispatch" contract on hardware
     * without blind UI nav; the outcome (IntentSent / a typed not-ready state) is logged under
     * `MimirLaunchProbe`. Local-only; the dummy ROM is expected to error inside the emulator — the
     * provable contract here is that Mimir fires the correct Intent to the correct installed package.
     */
    private fun maybeLaunchFirstReadyGame() {
        if (intent?.getBooleanExtra("mimir.launchFirst", false) != true) return
        val path = DemoSeeder.seed(this)
        lifecycleScope.launch {
            runCatching {
                val repo = LibraryStore.repository(this@MainActivity)
                repo.rescan(path)
                val platform = repo.observePlatforms().first().firstOrNull()
                    ?: return@runCatching Log.w("MimirLaunchProbe", "no platforms after scan")
                val game = repo.observeGames(platform.platformId).first().firstOrNull()
                    ?: return@runCatching Log.w("MimirLaunchProbe", "no games for ${platform.platformId.value}")
                // **E1: this used to call `LaunchInjection.controller(...).launch(...)` directly, and
                // that made the probe unusable as evidence about anything downstream of the session.**
                // The controller fires the right Intent at the right package -- which is all this hook
                // was ever written to prove -- but it emits no `LibraryGameSessionEvent.Preparing`, so
                // `MimirSessionAuthority` never learns a game session began, `armGameOwner` never fires,
                // and screen 2 describes the EMULATOR. The companion half of the original E1 report came
                // from a run of THIS hook, and that half was an artefact of the probe.
                //
                // It now publishes to `DebugNavigationBridge`, whose consumer calls
                // `LibraryViewModel.launch` -- the Confirm-press seam. Same reason every other request on
                // that bridge replays a real entry point rather than a synthetic one.
                Log.i(
                    "MimirLaunchProbe",
                    "LAUNCH '${game.title}' [${game.platformId.value}] requested " +
                        "gameId=${game.gameId.value} via=LibraryViewModel.launch",
                )
                DebugNavigationBridge.publish(
                    DebugNavigationRequest.LaunchGame(
                        gameId = game.gameId,
                        platformId = game.platformId,
                    ),
                )
            }.onFailure { Log.e("MimirLaunchProbe", "probe failed", it) }
        }
    }

    /**
     * DEV proof hook: `adb ... am start ... --ez mimir.migrateFirst true` runs the FULL M10 migration IMPORT
     * against the REAL seeded library — parse a bundle (from the app's own external files dir, pushed by the
     * test), `MigrationImporter.preview` (re-derive gameIds + probe `hasGame`), then `apply` via the REAL
     * write seams (favorites/hidden/emulator-override/theme). Proves the importer + seams on hardware without
     * the flaky SAF picker UI (the picker itself is verified separately). Logs the honest preview + result
     * under `MimirMigrateProbe`. Local-only, metadata-only — never touches shipping data or the network.
     */
    private fun maybeMigrateFirst() {
        if (intent?.getBooleanExtra("mimir.migrateFirst", false) != true) return
        val path = DemoSeeder.seed(this)
        // Internal filesDir (app-owned) — the test pushes the fixture here via run-as (an adb-pushed file in
        // the EXTERNAL files dir is unreadable by the app on the emulator's FUSE layer).
        val bundleFile = java.io.File(filesDir, "mimir-migration-fixture.json")
        lifecycleScope.launch {
            runCatching {
                val repo = LibraryStore.repository(this@MainActivity)
                repo.rescan(path)
                val bundle = com.gruning.mimir.rewrite.core.model.migration.MigrationBundleCodec
                    .parse(bundleFile.readText()).getOrThrow()
                val launch = LaunchInjection.controller(this@MainActivity)
                val importer = com.gruning.mimir.rewrite.core.resolver.migration.MigrationImporter(
                    com.gruning.mimir.rewrite.core.resolver.migration.MigrationMatcher(
                        resolveCurrentGameId = { gid ->
                            com.gruning.mimir.rewrite.core.model.GameId(
                                com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
                                    .get(this@MainActivity)
                                    .gameIdentityDao()
                                    .resolveCurrentGameId(gid.value),
                            )
                        },
                        gameExists = { gid -> repo.hasGame(gid) },
                    ),
                )
                val plan = importer.preview(
                    bundle = bundle,
                    isKnownTheme = com.gruning.mimir.rewrite.core.ui.MimirThemeId::isKnownStorageKey,
                    isEmulatorRecognized = { gid, rec, emu ->
                        launch.openWithOptions(gid, com.gruning.mimir.rewrite.core.model.PlatformId(rec.platformId))
                            .candidates.any { it.emulatorId.value == emu }
                    },
                )
                Log.i(
                    "MimirMigrateProbe",
                    "PREVIEW favMatched=${plan.favoritesMatched.size}/${plan.favorites.size} " +
                        "hidMatched=${plan.hiddenMatched.size}/${plan.hidden.size} " +
                        "ovrApplicable=${plan.overridesApplicable.size}/${plan.overrides.size} " +
                        "theme=${plan.theme?.themeId} notFound=${plan.notFoundTitles()}",
                )
                val seams = object : com.gruning.mimir.rewrite.core.resolver.migration.MigrationSeams {
                    override suspend fun setFavorite(gameId: com.gruning.mimir.rewrite.core.model.GameId) { repo.setFavorite(gameId, true) }
                    override suspend fun setHidden(gameId: com.gruning.mimir.rewrite.core.model.GameId) { repo.setHidden(gameId, true) }
                    override suspend fun setEmulatorOverride(gameId: com.gruning.mimir.rewrite.core.model.GameId, emulatorId: String) {
                        launch.setEmulatorOverride(gameId, com.gruning.mimir.rewrite.core.model.EmulatorId(emulatorId))
                    }
                    override fun applyTheme(themeStorageKey: String) {
                        ThemeStore.setTheme(this@MainActivity, com.gruning.mimir.rewrite.core.ui.MimirThemeId.fromStorageKey(themeStorageKey))
                    }
                }
                val result = importer.apply(plan, seams)
                Log.i(
                    "MimirMigrateProbe",
                    "RESULT favRestored=${result.favoritesRestored} favNotFound=${result.favoritesNotFound} " +
                        "hidRestored=${result.hiddenRestored} ovrRestored=${result.overridesRestored} " +
                        "ovrSkipped=${result.overridesSkipped} themeApplied=${result.themeApplied} " +
                        "regrant=${result.sourceRootsToRegrant}",
                )
            }.onFailure { Log.e("MimirMigrateProbe", "migrate probe failed", it) }
        }
    }

    /**
     * Mimir's whole key lane with both `super.dispatchKeyEvent` exits removed: `true` exactly when this
     * app's own authority consumed the event.
     *
     * [dispatchKeyEvent] puts those two `super` exits back, unchanged, for the window this Activity owns
     * on display 0. The display-4 floor ([CompanionSecondaryHomeActivity]) reaches this through
     * [MimirMainInputSink] and takes the answer as final — it has nothing under it for `super` to reach,
     * which is exactly why it must not fall through. One body, so the two displays cannot drift apart.
     */
    @SuppressLint("RestrictedApi")
    private fun routeKeyEventThroughAuthority(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) { UserLeaveSignal.noteKey(event.keyCode); mButtonTopologyController?.notePrimaryInteraction() }
        // The wake press first (PadIdleGate, see [padIdleGate]): a key down re-arms the idle controller, and the one that woke a dimmed screen, and its repeats and release, is eaten.
        return padIdleGate.key(
            down = event.action == KeyEvent.ACTION_DOWN,
            up = event.action == KeyEvent.ACTION_UP,
            keyCode = event.keyCode,
        ) {
            // Welcome beat: a timed mask, not an input surface — but system keys (volume/power) stay live;
            // shipping's lane never gated those (its splash simply had nothing focusable underneath).
            if (WelcomeHold.active && !isMimirSystemKey(event.keyCode)) {
                true
            } else if (isMimirSystemKey(event.keyCode)) {
                // Accessibility shortcut capture is app-modal, never Android-modal: volume/power remain owned
                // by the system even while an app shortcut listener is armed. Declining here is what routes it
                // to `super` — the receiving window's own, so the framework handles it on the display it
                // actually arrived on.
                false
            } else {
                // FUX-11: the nav tick moved into the router's normalized-directional point (InputRouter
                // onNavTick) — the KeyEvent-side tick never saw Thor's physical pad, whose D-pad arrives as
                // HAT AXIS MotionEvents, not key events.
                inputRouter.onKeyEvent(event)
            }
        }
    }

    /** Route every key event through the router first; consume it if Mimir's authority handled it. */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (routeKeyEventThroughAuthority(event)) true else super.dispatchKeyEvent(event)

    /**
     * The generic-motion half of [routeKeyEventThroughAuthority] — same contract, same reason:
     * everything but the `super` fallback, so display 4's floor can reuse the one body.
     */
    private fun routeGenericMotionEventThroughAuthority(event: MotionEvent): Boolean {
        // Every sample the event carries, oldest first (a batch can hold a press, a return to rest and a new press): any held one is activity.
        val samples = event.idleWakeSamples()
        if (samples.any { it }) {
            mButtonTopologyController?.notePrimaryInteraction()
        }
        return padIdleGate.motion(samples) { permitted ->
            if (WelcomeHold.active) {
                true
            } else {
                genericMotionInteractionSource(event.source, event.actionMasked)?.let(interactionMotionGate::pulse)
                // Only the samples the gate did not eat as the wake press: a batch of [rest, RIGHT] after a hat wake moves once.
                inputRouter.onMotionEvent(event, permitted)
            }
        }
    }

    /** Route analog stick / hat motion before Compose or Views can run a second focus path. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (routeGenericMotionEventThroughAuthority(event)) true else super.dispatchGenericMotionEvent(event)

    /** The welcome hold swallows touch too — nothing under the mask may receive a tap. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) mButtonTopologyController?.notePrimaryInteraction()
        if (idleWakeTouchLatched) {
            recordHomeRailRootTouch(ev, outcome = "idleWakeLatched", handled = true)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                idleWakeTouchLatched = false
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && screenIdleController?.onUserInteraction() == true) {
            recordHomeRailRootTouch(ev, outcome = "idleWake", handled = true)
            idleWakeTouchLatched = true
            return true
        }
        if (WelcomeHold.active) {
            recordHomeRailRootTouch(ev, outcome = "welcomeHold", handled = true)
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            primaryTouchLease?.close()
            primaryTouchLease = primaryMotionRoot?.let { root ->
                interactionMotionGate.hold(root, InteractionSource.TOUCH)
            }
        }
        var handled = false
        return try {
            handled = super.dispatchTouchEvent(ev)
            handled
        } finally {
            recordHomeRailRootTouch(ev, outcome = "super", handled = handled)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                primaryTouchLease?.close()
                primaryTouchLease = null
            }
        }
    }

    private fun recordHomeRailRootTouch(event: MotionEvent, outcome: String, handled: Boolean) {
        if (!BuildConfig.DEBUG || !DebugHomeRailObservation.isArmed()) return
        DebugHomeRailObservation.recordRootTouch(
            event = event,
            activity = javaClass.simpleName,
            displayId = window?.decorView?.display?.displayId ?: -1,
            outcome = outcome,
            handled = handled,
        )
    }

    /** Clear coalescing bookkeeping on focus change so a press that started off-window can't stick. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        inputRouter.reset()
        controllerNavigationActive.value = false
        controllerNavigationClearJob?.cancel()
        controllerNavigationClearJob = null
        if (!hasFocus) {
            ControllerMappingStore.cancelCapture()
            ControllerShortcutStore.cancelCapture("Controller shortcut capture cancelled when Mimir left focus")
            screenIdleController?.onPause()
            padIdleGate.reset()
            idleWakeTouchLatched = false
            primaryTouchLease?.close()
            primaryTouchLease = null
        }
        if (hasFocus) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                screenIdleController?.onResume()
                // onResume may precede window-focus restoration. Re-arm here so a translucent/failed
                // emulator handoff cannot consume the one watchdog check while Mimir is still unfocused.
                MimirSessionAuthority.state.value.gameSession
                    ?.takeIf { it.phase == MimirSessionPhase.DISPATCHED && it.placement == MimirGamePlacement.MAIN }
                    ?.let(::armGameDispatchWatchdog)
            }
            window.decorView.postDelayed({ enterFullscreenMode() }, FullscreenReapplyDelayMs)
        }
    }

    private fun markControllerNavigationActive() {
        controllerNavigationActive.value = true
        controllerNavigationClearJob?.cancel()
        controllerNavigationClearJob = lifecycleScope.launch {
            delay(ControllerNavigationMotionGateMs)
            controllerNavigationActive.value = false
        }
    }

    // idleWakeSamples() and PadIdleGate (core-companion-native, PadIdleGate.kt): shared with the Bottom's Activity page and ᛗ menu pads, so every pad lane counts the same inputs as activity.

    @Suppress("DEPRECATION")
    private fun enterFullscreenMode() {
        // PS-01: FLAG_KEEP_SCREEN_ON is owned exclusively by MimirScreenIdleController via
        // applyKeepScreenOn — fullscreen presentation must never set it.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /**
     * O-5, fixed a second time after
     * `tools/cert-overlay-permission-lifecycle.sh` falsified the first attempt on-device: that attempt
     * diffed the live decision against a remembered belief (`performanceOverlayServiceRunning`), tracking
     * only what THIS ACTIVITY last applied. But [PerformanceOverlayService] can and does stop **itself** --
     * `onStartCommand` (`PerformanceOverlayService.kt:329-337`) and its own telemetry loop
     * (`sampleSystemTelemetryWithObservation`, `:437-441`) both call `stopSelf()` the instant they observe
     * `!Settings.canDrawOverlays(this)`, entirely without `MainActivity` being told. The remembered belief
     * then silently disagreed with reality: it still said "running", the service was actually dead, and a
     * later resume with the permission restored saw "no change from what I remember" and started nothing.
     * That is the exact defect this method exists to not repeat.
     *
     * This method carries **no memory across calls**. Every invocation re-derives the decision purely from
     * the two live sources of truth -- [PerformanceOverlaySettingsStore.visibility] and
     * [Settings.canDrawOverlays] -- via [performanceOverlayResumeAction] (which itself only calls
     * [performanceOverlayShouldRun], never duplicates it), and applies [PerformanceOverlayResumeAction.START]
     * or [PerformanceOverlayResumeAction.STOP] unconditionally, whether or not that matches whatever the
     * service happened to be doing on its own. Calling `onCreate` and `onResume` through this same one
     * method (rather than each keeping its own copy) is what keeps them from drifting relative to each
     * other, not a shared cache of the outcome.
     *
     * Redundant delivery is cheap and was verified safe from source, not assumed: [PerformanceOverlayService]
     * only runs its one-time setup (`onCreate`: telemetry channel, observers, `serviceScope`) once for the
     * service's entire lifetime -- Android does not re-invoke a started `Service`'s `onCreate` while it is
     * still alive, only `onStartCommand` -- so a redundant `start()` while already running just re-delivers
     * one `onStartCommand`, which re-checks [performanceOverlayShouldRun] and rebuilds the (already
     * low-priority, silent, `setOnlyAlertOnce(true)`) notification; it starts no second sampling loop. A
     * redundant `stop()` while already stopped is `Context.stopService` on a non-running component, which is
     * a documented no-op. Neither call reissues the actual telemetry work this service exists to do, so
     * calling this on every resume is not the "thrash" the original brief warned a naive
     * always-start/always-stop design against -- that concern is about repeated *work*, and there is none.
     */
    private fun applyPerformanceOverlayLiveDecision() {
        when (
            performanceOverlayResumeAction(
                PerformanceOverlaySettingsStore.visibility.value,
                Settings.canDrawOverlays(this),
            )
        ) {
            PerformanceOverlayResumeAction.START -> PerformanceOverlayService.start(this)
            PerformanceOverlayResumeAction.STOP -> PerformanceOverlayService.stop(this)
        }
    }

    private var tweakerPackageMonitor: BroadcastReceiver? = null

    private fun registerTweakerPackageMonitor() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart
                if (!TweakerBridge.isTweakerPackageEvent(packageName)) return
                TweakerBridge.invalidateCachedPairIdentity()
                lifecycleScope.launch { TweakerBridge.refreshCapabilities() }
            }
        }
        tweakerPackageMonitor = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private companion object {
        private const val FullscreenReapplyDelayMs = 120L
        private const val ControllerNavigationMotionGateMs = 140L
        private const val CompanionDrawerProofDelayMs = 420L
        private const val PrimaryDeferredReturnGuardMs = 120L
        private const val PrimaryDeferredReturnFocusPollMs = 16L
        private const val SavedProcessEpochKey = "mimir.processEpoch"
    }
}

// Three seconds matches the
// existing quick-return honesty boundary and is now a frozen, testable launch-session bound.
internal const val GameDispatchPrimaryGraceMs = HomeExternalSessionElapsedThresholdMs

internal fun companionOwnMirrorDrawerEdgeBackEnabled(
    mirrorActive: Boolean,
    drawerVisible: Boolean,
    controllerActive: Boolean,
): Boolean = mirrorActive && controllerActive && drawerVisible

/**
 * The Bottom shelf mirrors Main's back behavior: the same
 * scoped own-mirror exception [companionOwnMirrorDrawerEdgeBackEnabled] gives the visible Tabs drawer,
 * given to the Bottom shelf's own open folder ("Tome") too.
 *
 * [mainDualScreenGameActive] may only ever narrow this — R6-P1-02's own ruling on the sibling admission —
 * so it is ANDed in negated rather than used anywhere that could flip a `false` to `true`.
 *
 * The native CP03 strip is a SEPARATE `TYPE_APPLICATION_OVERLAY` window. When armed
 * touchable, it consumes every DOWN/MOVE/UP over the right 32dp outright (there is no pass-through but
 * `FLAG_NOT_TOUCHABLE`), so a folder/layer open over Mimir's OWN mirror went on to make that whole strip
 * tap-dead, not just swipeable. This admission now stays `false` unconditionally — the folder/layer case is
 * handled by a Compose edge-back gesture on the Presentation itself (`MimirCompanionPresentation`'s root
 * Box), which can accept a real swipe and still let an ordinary tap reach the tile underneath. The
 * parameters stay so every call site (and its test) keeps reading the same admission shape; only the visible
 * Tabs drawer above may still arm the native strip.
 */
internal fun companionOwnMirrorFolderEdgeBackEnabled(
    mirrorActive: Boolean,
    folderOpen: Boolean,
    controllerActive: Boolean,
    mainDualScreenGameActive: Boolean,
): Boolean = false

internal enum class CompanionSwapPlan { EXCHANGE, BOTTOM_TO_MAIN, MAIN_TO_BOTTOM, BLOCKED_BOTTOM_GAME, NONE }

internal fun companionSwapPlan(
    bottomHost: Boolean,
    bottomGameBlocked: Boolean,
    mainRunning: Boolean,
    mainGameActive: Boolean,
    launchBlocked: Boolean,
): CompanionSwapPlan = when {
    bottomHost && bottomGameBlocked -> CompanionSwapPlan.BLOCKED_BOTTOM_GAME
    bottomHost && mainRunning && !mainGameActive && !launchBlocked -> CompanionSwapPlan.EXCHANGE
    bottomHost -> CompanionSwapPlan.BOTTOM_TO_MAIN
    mainRunning && !mainGameActive && !launchBlocked -> CompanionSwapPlan.MAIN_TO_BOTTOM
    else -> CompanionSwapPlan.NONE
}

/**
 * Why a Swap moves nothing, and what the player is told on the Bottom (QA 2026-09-11, D6).
 *
 * [retryable] is whether pressing Swap again could succeed. Only then is the menu completion refused, which keeps
 * the menu open for the retry; every other refusal completes it as handled, so the menu collapses.
 */
internal enum class CompanionSwapRefusal(val message: String, val retryable: Boolean) {
    /** Nothing on either screen. */
    NOTHING_TO_SWAP("Nothing to swap. Open an app on either screen first.", retryable = false),

    /** A library game owns Main ([companionMainGameSwapBlocked]) and nothing is on the Bottom to exchange with. */
    GAME_ON_MAIN("A game is running on the main screen. Nothing to swap yet.", retryable = false),

    /** A game session holds the Bottom. The wording is the toast this refusal always showed. */
    BOTTOM_GAME_BLOCKED("Game sessions stay on the bottom screen", retryable = false),

    /** A launch or an app transfer is still in flight; the same press can succeed once it settles. */
    LAUNCH_IN_FLIGHT("An app is still opening or moving. Try again in a moment.", retryable = true),
}

/** Names the refusal behind a [CompanionSwapPlan.NONE] or [CompanionSwapPlan.BLOCKED_BOTTOM_GAME]; null when Swap moves something. */
internal fun companionSwapRefusal(
    bottomHost: Boolean,
    bottomGameBlocked: Boolean,
    mainRunning: Boolean,
    mainGameActive: Boolean,
    launchBlocked: Boolean,
): CompanionSwapRefusal? = when (companionSwapPlan(bottomHost, bottomGameBlocked, mainRunning, mainGameActive, launchBlocked)) {
    CompanionSwapPlan.BLOCKED_BOTTOM_GAME -> CompanionSwapRefusal.BOTTOM_GAME_BLOCKED
    CompanionSwapPlan.NONE -> when {
        // A game on Main blocks the reverse move whatever the launch lock says, so the game is the answer.
        mainGameActive -> CompanionSwapRefusal.GAME_ON_MAIN
        launchBlocked -> CompanionSwapRefusal.LAUNCH_IN_FLIGHT
        else -> CompanionSwapRefusal.NOTHING_TO_SWAP
    }
    CompanionSwapPlan.EXCHANGE,
    CompanionSwapPlan.BOTTOM_TO_MAIN,
    CompanionSwapPlan.MAIN_TO_BOTTOM,
    -> null
}

/** Why an accepted Bottom-to-Main Swap stopped before the app moved (the menu has already collapsed by then). */
internal enum class CompanionSwapMoveDecline { TRANSFER_IN_FLIGHT, LAUNCH_LOCKED, REGISTRY_MISSING, HOST_NOT_YIELDED, HOST_CHANGED }

/**
 * What the player is told when [CompanionSwapMoveDecline] stops a Bottom-to-Main move. A move that is only waiting
 * says so in the same words as [CompanionSwapRefusal.LAUNCH_IN_FLIGHT]; one that cannot happen reuses the existing
 * "Couldn’t move … to the main screen" sentence, naming the app when its label is known.
 */
internal fun companionSwapMoveDeclineMessage(decline: CompanionSwapMoveDecline, appLabel: String?): String = when (decline) {
    CompanionSwapMoveDecline.TRANSFER_IN_FLIGHT,
    CompanionSwapMoveDecline.LAUNCH_LOCKED,
    -> CompanionSwapRefusal.LAUNCH_IN_FLIGHT.message
    CompanionSwapMoveDecline.REGISTRY_MISSING,
    CompanionSwapMoveDecline.HOST_NOT_YIELDED,
    CompanionSwapMoveDecline.HOST_CHANGED,
    -> "Couldn’t move ${appLabel?.takeIf(String::isNotBlank) ?: "that app"} to the main screen"
}

/**
 * The display a toast for a Bottom command goes to, or null for the Activity's own context.
 *
 * The display the ᛗ menu is mounted on is where the player pressed; failing that, the selected secondary display.
 * Null with no secondary display, or when that display is already the Activity's own.
 */
internal fun companionFeedbackDisplayId(
    menuDisplayId: Int?,
    selectedSecondaryDisplayId: Int?,
    activityDisplayId: Int,
): Int? = (menuDisplayId ?: selectedSecondaryDisplayId)?.takeIf { it != activityDisplayId }

/** Reverse app Swap must not relaunch an emulator while a library game owns the primary display. */
internal fun companionMainGameSwapBlocked(
    placement: MimirGamePlacement?,
    phase: MimirSessionPhase?,
): Boolean = placement == MimirGamePlacement.MAIN && phase in setOf(
    MimirSessionPhase.PREPARING,
    MimirSessionPhase.DISPATCHED,
    MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    MimirSessionPhase.RETURNING,
    MimirSessionPhase.RECOVERING,
)

/** A game host carries an emulator payload; moving its launcher package to Main would lose the ROM. */
internal fun companionGameHostSwapToPrimaryBlocked(launchMode: String?): Boolean =
    launchMode == com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME

private fun MimirSessionPhase.isPrimaryReturnTrackable(): Boolean = when (this) {
    MimirSessionPhase.PREPARING,
    MimirSessionPhase.DISPATCHED,
    MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    MimirSessionPhase.RETURNING,
    -> true
    MimirSessionPhase.ENDED,
    MimirSessionPhase.FAILED,
    MimirSessionPhase.RECOVERING,
    MimirSessionPhase.STALE,
    -> false
}

internal fun genericMotionInteractionSource(source: Int, actionMasked: Int): InteractionSource? {
    val pointerSource = source and InputDevice.SOURCE_CLASS_POINTER == InputDevice.SOURCE_CLASS_POINTER
    if (!pointerSource) return null
    return when (actionMasked) {
        MotionEvent.ACTION_SCROLL -> InteractionSource.WHEEL
        MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> InteractionSource.POINTER
        else -> null
    }
}

/**
 * O-5: [performanceOverlayShouldRun] was only ever
 * consulted in [MainActivity.onCreate], so regaining or losing "Display over other apps" while Mimir sits
 * backgrounded — the player left for Android Settings and came back — never reached the service. O-1 fixed
 * the *row* (it live-reads `canDrawOverlays` on resume via `AppBrightnessCoordinator.refreshOnResume()`);
 * this is the same class one step further down, for the service itself, consulted from both
 * [MainActivity.onCreate] and [MainActivity.onResume] via [MainActivity.applyPerformanceOverlayLiveDecision].
 *
 * Deliberately stateless — takes no "what did we last do" input. A first version of this fix diffed the live
 * verdict against a remembered belief and was falsified on-device: [PerformanceOverlayService] can and does
 * stop **itself** (its own `onStartCommand`/telemetry loop calls `stopSelf()` the moment `canDrawOverlays`
 * goes false), entirely without `MainActivity`'s involvement, so any locally remembered "is it running" bit
 * can silently go stale the instant that happens. See [MainActivity.applyPerformanceOverlayLiveDecision] for
 * the full mechanism and for why calling [PerformanceOverlayService.start]/[PerformanceOverlayService.stop]
 * unconditionally on every resume — rather than only on a detected change — is safe and cheap here.
 */
internal enum class PerformanceOverlayResumeAction { START, STOP }

internal fun performanceOverlayResumeAction(
    visibility: PerformanceOverlayVisibility,
    canDrawOverlays: Boolean,
): PerformanceOverlayResumeAction = if (performanceOverlayShouldRun(visibility, canDrawOverlays)) {
    PerformanceOverlayResumeAction.START
} else {
    PerformanceOverlayResumeAction.STOP
}
