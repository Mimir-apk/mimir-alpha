package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.MimirEqualizer
import com.gruning.mimir.rewrite.core.audio.MimirFrontendMusic
import com.gruning.mimir.rewrite.core.audio.MimirMusicControl
import com.gruning.mimir.rewrite.core.audio.MimirMusicLibrary
import com.gruning.mimir.rewrite.core.audio.mimirMusicAudible
import com.gruning.mimir.rewrite.core.launch.ExtensionLaunchCoverage
import com.gruning.mimir.rewrite.core.launch.LauncherCertificationProjector
import com.gruning.mimir.rewrite.core.launch.PackageManagerEmulatorProvider
import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.launch.PlatformEmulatorStatus
import com.gruning.mimir.rewrite.core.launch.resolvePlatformEmulatorStatus
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.settings.BiosRequirementRegistry
import com.gruning.mimir.rewrite.feature.settings.BiosRequirementRow
import com.gruning.mimir.rewrite.feature.settings.EmulatorAssignment
import com.gruning.mimir.rewrite.feature.settings.EmulatorChoice
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutRow
import com.gruning.mimir.rewrite.feature.settings.MimirKeyboardPickerEntry
import com.gruning.mimir.rewrite.feature.settings.TrackpadSettingsState
import com.gruning.mimir.rewrite.feature.settings.MusicReadModel
import com.gruning.mimir.rewrite.feature.settings.SettingsSample
import com.gruning.mimir.rewrite.feature.settings.SpecialAccessRow
import com.gruning.mimir.rewrite.feature.settings.deriveEmulatorRouteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal const val TabsSupportingCopy = "Uses this device’s built-in bridge to open Android’s recent apps screen."

private val EmulatorPackageChangeActions = setOf(
    Intent.ACTION_PACKAGE_ADDED,
    Intent.ACTION_PACKAGE_CHANGED,
    Intent.ACTION_PACKAGE_REMOVED,
    Intent.ACTION_PACKAGE_REPLACED,
)

internal fun shouldRefreshEmulatorPackages(
    action: String?,
    packageName: String?,
    trackedPackages: Set<String>,
): Boolean = action in EmulatorPackageChangeActions && packageName in trackedPackages

/**
 * PackageManager has no observable cache contract. Refresh on exact emulator-package broadcasts and
 * every foreground return so an Obtanium/Play Store install cannot leave Settings or shelf labels stale
 * when the process missed a broadcast while stopped.
 */
@Composable
private fun rememberEmulatorPackageRevision(context: Context): Int {
    var revision by remember(context) { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val trackedPackages = remember { EmulatorSeedRegistry.allSeedPackages().toSet() }

    DisposableEffect(context, lifecycleOwner, trackedPackages) {
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (
                    shouldRefreshEmulatorPackages(
                        action = intent?.action,
                        packageName = intent?.data?.schemeSpecificPart,
                        trackedPackages = trackedPackages,
                    )
                ) {
                    revision += 1
                }
            }
        }
        val filter = IntentFilter().apply {
            EmulatorPackageChangeActions.forEach(::addAction)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision += 1
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }
    return revision
}

/**
 * Settings › Emulators' three states, kept apart (cert 2026-10-08 B1): `null` only while the library itself has not
 * loaded (the pane says "Checking installed emulators…"), an empty list once it has loaded with zero platforms (the
 * pane says "No emulator choices yet"), the built rows otherwise. [build] runs only when there is something to build.
 */
internal fun <T> emulatorRowsOrLoading(
    platformsLoaded: Boolean,
    platformCount: Int,
    build: () -> List<T>,
): List<T>? = when {
    !platformsLoaded -> null
    platformCount == 0 -> emptyList()
    else -> build()
}

/**
 * Whether the emulator verdicts cover the library (RC-31): null is the resolve that has not ended, and a platform the resolve
 * did not see (the library grew after it started) has no verdict yet either. Until then an absent entry means "not known", never
 * "no emulator", and nothing may print NEEDS_EMULATOR, "Recommended" or "Automatic" for it.
 */
internal fun emulatorVerdictsLoaded(
    resolved: Map<PlatformId, PlatformEmulatorStatus>?,
    platformIds: List<PlatformId>,
): Boolean = resolved != null && platformIds.all { it in resolved }

/**
 * A platform read (AppOps, the IME list, display and accessibility state, installed-package binder calls) that must not run
 * on the composing thread (RC-30, audit 2026-10-14).
 *
 * The read model is rebuilt on every foreground return, including the return from a game while the cover is up, and these
 * reads sat in `remember` blocks keyed on that tick: binder calls on Main in the resume frame. Here [read] runs on
 * [Dispatchers.IO] and the result lands as state. [produceState] keeps the last value while a key change re-reads, so a
 * resume never blinks; only the very first composition shows [initialValue], so pick one that renders neutral. A value the
 * player's own tap changes must stay a key of the caller's `remember`, not of this read, or the tap would wait on the hop.
 */
// Runtime 1.7.3 lint misses this inherited setter; ResumeFrameReadsOffMainTest checks publication and re-reads.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
internal fun <T> rememberOffMainRead(
    initialValue: T,
    vararg keys: Any?,
    read: () -> T,
): State<T> = produceState(initialValue, *keys) {
    value = withContext(Dispatchers.IO) { read() }
}

@Composable
internal fun rememberTrackpadSettingsLoaded(context: Context): State<Boolean> =
    rememberOffMainRead(false, context) {
        MimirTrackpadSettings.load(context)
        true
    }

internal fun androidAppSwitchingSupportingCopy(
    status: PrivilegedUtilityStatus,
    authority: PrivilegedUtilityAuthority = PrivilegedUtilityAuthority.NONE,
): String = when (status) {
    PrivilegedUtilityStatus.MANAGER_MISSING ->
        "Android app switching is not available through this device service."
    PrivilegedUtilityStatus.SERVICE_STOPPED ->
        "Android app switching is not available through this device service."
    PrivilegedUtilityStatus.PERMISSION_REQUIRED -> if (
        authority == PrivilegedUtilityAuthority.CERTIFIED_VENDOR
    ) {
        "Enable this handheld’s built-in Android app-switching bridge."
    } else {
        "Allow Mimir’s fixed Android app-switching bridge."
    }
    PrivilegedUtilityStatus.PERMISSION_DENIED ->
        "Android app switching is not available through this device service."
    PrivilegedUtilityStatus.CONNECTION_FAILED ->
        "Android app switching is not available through this device service."
    PrivilegedUtilityStatus.BINDING ->
        "Checking Android app switching…"
    PrivilegedUtilityStatus.READY -> if (
        authority == PrivilegedUtilityAuthority.CERTIFIED_VENDOR
    ) {
        "Uses this handheld’s built-in verified Android app-switching bridge."
    } else {
        TabsSupportingCopy
    }
    PrivilegedUtilityStatus.API_UNSUPPORTED ->
        "Android app switching is not available on this Android version."
    PrivilegedUtilityStatus.UNSUPPORTED_DEVICE,
    PrivilegedUtilityStatus.REMOTE_PERMISSION_MISSING,
    PrivilegedUtilityStatus.READBACK_UNSATISFIABLE,
    -> "Android app switching is not available through this device service."
}

internal fun androidAppSwitchingStateLabel(status: PrivilegedUtilityStatus): String = when (status) {
    PrivilegedUtilityStatus.BINDING -> "Checking"
    PrivilegedUtilityStatus.READY -> "Ready"
    else -> "Unavailable"
}

/** Settings projections that are independent of modal state and command routing. */
internal class MimirSettingsReadCoordinator(
    val emulatorProvider: PackageManagerEmulatorProvider,
    val platformDefaults: Map<PlatformId, EmulatorId>,
    val shelfLaunchStatusLines: Map<PlatformId, String>,
    val shelfEmulatorLabels: Map<PlatformId, String>,
    /**
     * False until every current platform has an emulator verdict: [shelfLaunchStatusLines] and [shelfEmulatorLabels] are empty
     * then, which is "not known yet", not "no emulator". A consumer that turns an absent entry into words ("Automatic",
     * "Needs emulator", "Recommended") must wait for this (RC-31, the second source of the loading-label defect).
     */
    val platformStatusesLoaded: Boolean,
    val emulatorAssignments: List<EmulatorAssignment>?,
    val biosRequirements: List<BiosRequirementRow>?,
    /** The "All files access" grant for the Library pane's storage row; re-read on every foreground return. */
    val allFilesAccess: Boolean,
    val specialAccessRows: List<SpecialAccessRow>?,
    val controllerShortcutRows: List<ControllerShortcutRow>,
    val androidAppSwitchingStatus: PrivilegedUtilityStatus,
    val refreshSpecialAccess: () -> Unit,
    /** R6-P3-02: what the Settings "Mimir Keyboard" row currently offers. See `MimirKeyboardPickerNative.kt`. */
    val keyboardPickerEntry: MimirKeyboardPickerEntry,
    /** D34b: the keyboard trackpad's Settings state; null where this device cannot host it. */
    val trackpadSettings: TrackpadSettingsState?,
    /** Settings › Music (2026-09-30): the pane's read model, projected from the engine's own flows. */
    val music: MusicReadModel,
    /** What holds the shared artwork gate right now; the rows gated in either direction read it. */
    val artworkOperation: ArtworkOperation,
) {
    fun choicesFor(platformId: PlatformId): List<EmulatorChoice> =
        emulatorProvider.candidatesFor(platformId)
            .map { EmulatorChoice(it.id.value, it.displayName, it.installed) }
}

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
internal fun rememberMimirSettingsReadCoordinator(
    core: MimirAppCoreCoordinator,
    library: MimirLibraryCoordinator,
    /** For the Music pane's game-music batch state, which lives on [MimirArtworkState]. */
    artwork: MimirArtworkCoordinator,
): MimirSettingsReadCoordinator {
    val emulatorProvider = remember { PackageManagerEmulatorProvider(core.context) }
    val emulatorPackageRevision = rememberEmulatorPackageRevision(core.context)
    val platformDefaults by PlatformEmulatorDefaultStore.defaults.collectAsState()
    // Null until the first IO resolve ends: an empty map would read as "no emulator on any platform" (NEEDS_EMULATOR, "Recommended",
    // "Automatic") for as long as the resolve takes (RC-31).
    val resolvedPlatformStatuses by produceState<Map<PlatformId, PlatformEmulatorStatus>?>(
        initialValue = null,
        library.platforms,
        platformDefaults,
        emulatorProvider,
        emulatorPackageRevision,
    ) {
        val platformSnapshot = library.platforms.map { it.platformId }
        val defaultsSnapshot = platformDefaults.toMap()
        value = withContext(Dispatchers.IO) {
            platformSnapshot.associateWith { platformId ->
                emulatorProvider.resolvePlatformEmulatorStatus(
                    platformId = platformId,
                    defaultEmulatorId = defaultsSnapshot[platformId],
                )
            }
        }
    }
    val platformStatuses = resolvedPlatformStatuses ?: emptyMap()
    // Loaded means every platform the library lists has a verdict. The library's list can grow after the first resolve (a platform
    // added later), and until the resolve for the new list ends that platform has none.
    val platformStatusesLoaded = emulatorVerdictsLoaded(resolvedPlatformStatuses, library.platforms.map { it.platformId })
    val shelfLaunchStatusLines = remember(platformStatuses) {
        platformStatuses.mapValues { (_, status) ->
            val resolved = status.resolved
            when {
                resolved != null -> "Launch · ${resolved.displayName}"
                status.unsupported -> "Launch · Unsupported"
                else -> "Launch · Needs emulator"
            }
        }
    }
    val shelfEmulatorLabels = remember(platformStatuses) {
        platformStatuses.mapNotNull { (platformId, status) ->
            status.resolved?.displayName?.let { platformId to it }
        }.toMap()
    }
    val emulatorAssignments = remember(library.platforms, library.platformsLoaded, platformStatuses, platformStatusesLoaded) {
        // "Checking installed emulators..." until the library AND its emulator verdicts have loaded (RC-31): NEEDS_EMULATOR and
        // "Recommended" are verdicts, and an unresolved platform has none.
        emulatorRowsOrLoading(library.platformsLoaded && platformStatusesLoaded, library.platforms.size) {
            //  the pane reads the proof-owned certification projection
            // (`mimirLauncherCertificationByPlatform.v1`) — a badge NEVER derives from installed/
            // configured state. No persisted evidence store exists yet, so the projection runs with
            // empty evidence: every platform reports its honest NOT_CERTIFIED/OPEN_EMULATOR_ONLY tier.
            val certification = LauncherCertificationProjector.project(
                platformIds = library.platforms.map { it.platformId },
                // Never resolved here (RC-30): the loaded gate above guarantees an entry, and an absent one is unknown, not a binder
                // sweep of every candidate on the composing thread.
                statusFor = { platformId ->
                    platformStatuses[platformId]
                        ?: PlatformEmulatorStatus(platformId, emptyList(), null, null)
                },
                updatedAtMillis = System.currentTimeMillis(),
            )
            library.platforms.map { platform ->
                val status = platformStatuses[platform.platformId]
                val shown = status?.resolved
                val cert = certification.platforms[platform.platformId.value]
                EmulatorAssignment(
                    platform = platform.label,
                    emulator = shown?.displayName,
                    status = if (shown != null) {
                        SettingsSample.SampleStatus.READY
                    } else {
                        SettingsSample.SampleStatus.NEEDS_EMULATOR
                    },
                    platformId = platform.platformId.value,
                    recommendedEmulator = if (shown == null) {
                        EmulatorSeedRegistry.recommendedDisplayNameFor(platform.platformId)
                    } else {
                        null
                    },
                    routeState = deriveEmulatorRouteState(
                        hasCandidates = status?.candidates?.isNotEmpty() == true,
                        hasResolvedTarget = shown != null,
                        anyCandidateInstalled = status?.candidates?.any { it.installed } == true,
                        certified = cert?.certified == true,
                        openOnly = cert?.openOnly == true,
                        requiresCoreProof = cert?.requiresManualCoreProof == true,
                    ),
                    unsupportedReason =
                        ExtensionLaunchCoverage.unsupportedCanonicalPlatforms[platform.platformId.value],
                )
            }
        }
    }
    val biosRequirements = remember(library.platforms, platformStatuses) {
        if (library.platforms.isEmpty()) {
            null
        } else {
            library.platforms.mapNotNull { platform ->
                val resolved = platformStatuses[platform.platformId]?.resolved
                val emulatorRequiresBios = resolved?.packageName
                    ?.let(EmulatorSeedRegistry::requiresBios) == true
                BiosRequirementRegistry.rowFor(
                    platform.platformId.value,
                    platform.label,
                    emulatorRequiresBios,
                )
            }
        }
    }
    var specialAccessRefreshTick by remember { mutableStateOf(0) }
    // The Library pane's storage row follows the All-files grant. `isExternalStorageManager()` is a framework call, so it is
    // sampled off the main thread and only when the tick moves (a foreground return, i.e. back from the grant screen) --
    // never from composition (the AppBrightnessCoordinator.allFilesAccessGranted posture).
    val allFilesAccess by produceState(initialValue = false, specialAccessRefreshTick) {
        value = withContext(Dispatchers.IO) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        }
    }
    val mButtonVisibilityPolicy by MButtonSettingsStore.visibilityPolicy.collectAsState()
    val controllerMappingSnapshot by ControllerMappingStore.snapshot.collectAsState()
    val legacyControllerShortcutSnapshot by ControllerShortcutStore.snapshot.collectAsState()
    val controllerShortcutRows = remember(controllerMappingSnapshot, legacyControllerShortcutSnapshot) {
        controllerMappingSnapshot.toShortcutSettingsRows(
            legacyRows = legacyControllerShortcutSnapshot.toSettingsRows(),
        )
    }
    val tabsGateway = remember(core.context) {
        CompanionProcessRuntime.services(core.context).privilegedUtility
    }
    var tabsStatus by remember(tabsGateway) {
        // Passive and non-actionable until the first authoritative off-main sample completes.
        mutableStateOf(PrivilegedUtilityStatus.BINDING)
    }
    var tabsAuthority by remember(tabsGateway) {
        mutableStateOf(PrivilegedUtilityAuthority.NONE)
    }
    // A UserService bind completes asynchronously. Converge only that transient state so Settings
    // cannot remain stuck on CONNECTING after the bridge is ready; all consent states stay click-owned.
    LaunchedEffect(specialAccessRefreshTick, tabsGateway) {
        do {
            val sample = withContext(Dispatchers.IO) {
                tabsGateway.systemRecentsAccessStatus() to tabsGateway.posture().authority
            }
            tabsStatus = sample.first
            tabsAuthority = sample.second
            if (tabsStatus == PrivilegedUtilityStatus.BINDING) delay(250L)
        } while (tabsStatus == PrivilegedUtilityStatus.BINDING)
    }
    // RC-30: display, overlay, usage-access and app-switching checks, off the composing thread. Null until the first read, as the
    // Controls pane already treats "no rows".
    val specialAccessRows by rememberOffMainRead<List<SpecialAccessRow>?>(
        null,
        specialAccessRefreshTick,
        mButtonVisibilityPolicy,
        core.companionOverlayController,
        core.launchForegroundObserver,
        tabsStatus,
        tabsAuthority,
    ) {
        buildList<SpecialAccessRow> {
            val hasSecondaryDisplay = core.context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.isNotEmpty() == true
            if (hasSecondaryDisplay) {
                add(
                    SpecialAccessRow(
                        id = "m_button_dual",
                        title = "Secondary-display Mimir button",
                        supporting = "Wired to the secondary display. Long-press for Tabs and Swap.",
                        granted = true,
                        grantedLabel = "Wired",
                    ),
                )
            } else {
                add(
                    SpecialAccessRow(
                        id = "m_button",
                        title = "Mimir button visibility",
                        supporting = "Long-press for Android Tabs, Quick Launch, and Utility. Adjust timeout in Utility.",
                        granted = mButtonVisibilityPolicy.buttonEnabled,
                        actionLabel = "Utility",
                        grantedLabel = mButtonVisibilityPolicy.displayLabel,
                        toggleable = false,
                    ),
                )
            }
            core.companionOverlayController?.let { overlay ->
                val granted = runCatching { overlay.hasOverlayPermission() }.getOrDefault(false)
                add(
                    SpecialAccessRow(
                        id = "overlay",
                        title = "Draw over other apps",
                        supporting = "Required for the Mimir button to stay available while you use other apps.",
                        granted = granted,
                    ),
                )
            }
            core.launchForegroundObserver?.let { observer ->
                val state = observer.usageAccessState()
                add(
                    SpecialAccessRow(
                        id = "usage",
                        title = "Usage access",
                        supporting = if (hasSecondaryDisplay) {
                            "Optional local launch check; the secondary-display Mimir button remains topology-wired."
                        } else {
                            "Required for the Mimir button to stay available while you use other apps."
                        },
                        granted = state.granted,
                    ),
                )
            }
            add(
                SpecialAccessRow(
                    id = "tabs",
                    title = "Android app switching",
                    supporting = androidAppSwitchingSupportingCopy(tabsStatus, tabsAuthority),
                    granted = tabsStatus == PrivilegedUtilityStatus.READY,
                    actionLabel = if (
                        tabsAuthority == PrivilegedUtilityAuthority.CERTIFIED_VENDOR &&
                            tabsStatus == PrivilegedUtilityStatus.PERMISSION_REQUIRED
                    ) "Allow" else androidAppSwitchingStateLabel(tabsStatus),
                    grantedLabel = "Ready",
                    actionable = tabsAuthority == PrivilegedUtilityAuthority.CERTIFIED_VENDOR &&
                        tabsStatus == PrivilegedUtilityStatus.PERMISSION_REQUIRED,
                ),
            )
        }.ifEmpty { null }
    }
    // R6-P3-02 review fix F1 (onboarding follow-up): the IME picker (`showInputMethodPicker()`) and
    // Android's keyboard-settings screen are BOTH reached by leaving Mimir's Activity, but neither is an
    // Activity itself, so `onResume` cannot be trusted to fire reliably on the way back. `core.keyboardStateTick`
    // is the ONE `ContentObserver` on the two `Settings.Secure` keys this entry actually reads, registered
    // once in `rememberMimirAppCoreCoordinator` (`MimirAppCoreCoordinator.kt`) -- shared with
    // `MimirOnboardingCoordinator`'s identical re-read, per the review's explicit "not a second observer".
    // `specialAccessRefreshTick` stays a secondary trigger (a plain foreground return still re-reads for
    // free); neither bumps the other, so this never forces the tabs refetch above.
    // RC-30: the IME list is a binder call; off the composing thread. The first composition shows "open Android's keyboard settings",
    // which is also what a press before the read lands would do.
    val keyboardPickerEntry by rememberOffMainRead(
        MimirKeyboardPickerEntry.OPEN_KEYBOARD_SETTINGS,
        specialAccessRefreshTick,
        core.keyboardStateTick,
    ) {
        currentMimirKeyboardPickerEntry(core.context)
    }
    // D34b: the trackpad's status row. Re-read on a plain foreground return (the player comes back from Android's
    // Accessibility settings), and live for the scroll-direction switch.
    val trackpadLoaded by rememberTrackpadSettingsLoaded(core.context)
    val naturalScroll by MimirTrackpadSettings.naturalScroll.collectAsState()
    val trackpadSpeed by MimirTrackpadSettings.speedStep.collectAsState()
    val trackpadPointer by MimirTrackpadSettings.pointerStyle.collectAsState()
    // RC-30: whether this device can host the trackpad and whether its accessibility service is on are platform reads, off the
    // composing thread. The player's own settings (scroll, speed, pointer) stay keys of the remember below, so a tap on one shows
    // at once instead of waiting on the read.
    val trackpadPlatform by rememberOffMainRead<Pair<Boolean, Boolean>?>(null, specialAccessRefreshTick) {
        val capable = MimirTrackpadRuntime.capability(core.context)
        capable to (capable && MimirTrackpadRuntime.accessibilityServiceEnabled(core.context))
    }
    val trackpadSettings = remember(trackpadLoaded, trackpadPlatform, naturalScroll, trackpadSpeed, trackpadPointer) {
        trackpadPlatform?.let { (capable, serviceEnabled) ->
            if (capable && trackpadLoaded) {
                TrackpadSettingsState(
                    serviceEnabled = serviceEnabled,
                    naturalScroll = naturalScroll,
                    speed = trackpadSpeed,
                    pointer = trackpadPointer,
                )
            } else {
                null
            }
        }
    }

    // Settings › Music: one read model from the engine's flows, rebuilt only when one
    // of them moves. `lastRequestsTodayReading` is a plain field read when the model is rebuilt, shown with the age
    // of the read it came from -- the pane never makes the authenticated call that would refresh it.
    val artworkOperation by ArtworkOperationGate.process.active.collectAsState()
    val musicEnabled by AudioPreferences.musicEnabled.collectAsState()
    val musicNowPlaying by MimirFrontendMusic.nowPlaying.collectAsState()
    val musicCustomFallback by MimirFrontendMusic.customFallback.collectAsState()
    val musicSource by AudioPreferences.musicSource.collectAsState()
    val musicOrder by AudioPreferences.musicOrder.collectAsState()
    val musicVolume by AudioPreferences.ambientVolume.collectAsState()
    val songTitlesEnabled by AudioPreferences.songTitlesEnabled.collectAsState()
    val musicLibrary by MimirMusicLibrary.state.collectAsState()
    val launchSoundEnabled by AudioPreferences.launchSoundEnabled.collectAsState()
    val launchSoundVolume by AudioPreferences.launchSoundVolume.collectAsState()
    val gameMusic by artwork.state.gameMusicState.collectAsState()
    val playOnHighlight by AudioPreferences.gameMusicOnHighlight.collectAsState()
    val keepMusicInGames by AudioPreferences.keepMusicInGames.collectAsState()
    val musicHours by AudioPreferences.musicHours.collectAsState()
    val fadeBetweenTracks by AudioPreferences.fadeBetweenTracks.collectAsState()
    val playOutsideMimir by AudioPreferences.playOutsideMimir.collectAsState()
    val pauseOnScreenOff by AudioPreferences.pauseOnScreenOff.collectAsState()
    val pauseOnUnplug by AudioPreferences.pauseOnUnplug.collectAsState()
    val equalizer by MimirEqualizer.settings.collectAsState()
    val systemEq by MimirEqualizer.systemEqualizer.collectAsState()
    // The track on air's format: a flow of its own, so a format arriving or changing rebuilds the model and nothing else does.
    val trackFormat by remember { MimirMusicControl.state.map { it.yours?.format }.distinctUntilChanged() }.collectAsState(initial = null)
    // Whether Mimir's music is AUDIBLE (the engine's word, not the switch, and not into a silent stream), for the chip: held by another app, a call, the cable, the hours
    // or the player's pause, at level zero, or with Game & media at zero, it reads Paused. A flow of its own for the same reason as the format: a hold arriving
    // rebuilds the model and nothing else does.
    val initialMusicSounding = remember {
        mimirMusicAudible(MimirMusicControl.state.value.mimirMusicSounding, MimirMusicControl.mediaVolume.value)
    }
    val musicSounding by remember {
        combine(MimirMusicControl.state.map { it.mimirMusicSounding }, MimirMusicControl.mediaVolume, ::mimirMusicAudible).distinctUntilChanged()
    }.collectAsState(initial = initialMusicSounding)
    // The grant is made on another screen, so it is re-read when the app comes back to the front (the same tick Special access uses).
    // RC-30: a notification-listener lookup (a settings read), off the composing thread; false until the first read.
    val otherPlayersAccess by rememberOffMainRead(false, specialAccessRefreshTick) { musicOtherPlayersAccessGranted(core.context) }
    val music = remember(
        musicEnabled, musicNowPlaying, musicCustomFallback, musicSource, musicOrder, musicVolume, songTitlesEnabled,
        musicLibrary, launchSoundEnabled, launchSoundVolume, gameMusic, playOnHighlight, keepMusicInGames,
        artworkOperation, otherPlayersAccess, equalizer, systemEq, trackFormat, musicSounding,
        musicHours, fadeBetweenTracks, playOutsideMimir, pauseOnScreenOff, pauseOnUnplug,
    ) {
        musicReadModel(
            musicEnabled = musicEnabled,
            nowPlaying = musicNowPlaying,
            source = musicSource,
            order = musicOrder,
            ambientVolume = musicVolume,
            songTitlesEnabled = songTitlesEnabled,
            library = musicLibrary,
            launchSoundEnabled = launchSoundEnabled,
            launchSoundVolume = launchSoundVolume,
            gameMusic = gameMusic,
            requestsToday = ArtworkConnectController.lastRequestsTodayReading,
            playOnHighlight = playOnHighlight,
            keepMusicInGames = keepMusicInGames,
            nowMillis = System.currentTimeMillis(),
            customFallback = musicCustomFallback,
            otherPlayersAccessGranted = otherPlayersAccess,
            equalizer = equalizer,
            systemEq = systemEq,
            trackFormat = trackFormat,
            hours = musicHours,
            fadeBetweenTracks = fadeBetweenTracks,
            playOutsideMimir = playOutsideMimir,
            pauseOnScreenOff = pauseOnScreenOff,
            pauseOnUnplug = pauseOnUnplug,
            playing = musicSounding,
        )
    }

    return MimirSettingsReadCoordinator(
        emulatorProvider = emulatorProvider,
        platformDefaults = platformDefaults,
        shelfLaunchStatusLines = shelfLaunchStatusLines,
        shelfEmulatorLabels = shelfEmulatorLabels,
        platformStatusesLoaded = platformStatusesLoaded,
        emulatorAssignments = emulatorAssignments,
        biosRequirements = biosRequirements,
        allFilesAccess = allFilesAccess,
        specialAccessRows = specialAccessRows,
        controllerShortcutRows = controllerShortcutRows,
        androidAppSwitchingStatus = tabsStatus,
        refreshSpecialAccess = { specialAccessRefreshTick += 1 },
        keyboardPickerEntry = keyboardPickerEntry,
        trackpadSettings = trackpadSettings,
        music = music,
        artworkOperation = artworkOperation,
    )
}
