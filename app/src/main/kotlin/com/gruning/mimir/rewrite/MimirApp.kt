package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.LaunchForegroundObserver
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.LocalFocusAuthority
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionShelfRow
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleCodec
import com.gruning.mimir.rewrite.core.resolver.migration.MigrationImporter
import com.gruning.mimir.rewrite.core.resolver.migration.MigrationMatcher
import com.gruning.mimir.rewrite.core.resolver.migration.MigrationSeams
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerNavigationActive
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import com.gruning.mimir.rewrite.feature.browse.BrowseScreen
import com.gruning.mimir.rewrite.feature.browse.dispatchToBrowse
import com.gruning.mimir.rewrite.feature.library.NativeShelfAddCandidate
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.GamelistViewMode
import com.gruning.mimir.rewrite.feature.library.HomeShelfScreen
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryDetailSession
import com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryOriginReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibrarySearchReturnFocus
import com.gruning.mimir.rewrite.feature.library.LibrarySearchReturnSnapshot
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryScreen
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.LocalLibraryRepository
import com.gruning.mimir.rewrite.feature.library.dispatchToLibrary
import com.gruning.mimir.rewrite.feature.library.dispatchToHomeShelf
import com.gruning.mimir.rewrite.feature.library.homeShelfFocusKey
import com.gruning.mimir.rewrite.feature.library.nativeShelfKindOrNull
import com.gruning.mimir.rewrite.core.launch.PackageManagerEmulatorProvider
import com.gruning.mimir.rewrite.core.launch.PlatformEmulatorStatus
import com.gruning.mimir.rewrite.core.launch.resolvePlatformEmulatorStatus
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.feature.settings.BiosRequirementRegistry
import com.gruning.mimir.rewrite.feature.settings.BiosRequirementRow
import com.gruning.mimir.rewrite.feature.settings.EmulatorAssignment
import com.gruning.mimir.rewrite.feature.settings.EmulatorChoice
import com.gruning.mimir.rewrite.feature.settings.LibraryReadModel
import com.gruning.mimir.rewrite.feature.settings.PlatformEmulatorPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.PlatformEmulatorPickerScope
import com.gruning.mimir.rewrite.feature.settings.ProfileNameEditOverlay
import com.gruning.mimir.rewrite.feature.settings.dispatchPlatformEmulatorPicker
import com.gruning.mimir.rewrite.feature.settings.BackdropPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.BackdropPickerScope
import com.gruning.mimir.rewrite.feature.settings.BackdropValueBackdrop
import com.gruning.mimir.rewrite.feature.settings.BackdropValueMedia
import com.gruning.mimir.rewrite.feature.settings.BackdropValueOff
import com.gruning.mimir.rewrite.feature.settings.dispatchBackdropPicker
import com.gruning.mimir.rewrite.feature.library.GamelistBackdropMode
import com.gruning.mimir.rewrite.feature.settings.SettingsSample
import com.gruning.mimir.rewrite.feature.settings.SettingsHome
import com.gruning.mimir.rewrite.feature.settings.SpecialAccessRow
import com.gruning.mimir.rewrite.feature.settings.ThemePickerScope
import com.gruning.mimir.rewrite.feature.settings.ThemePreviewGallery
import com.gruning.mimir.rewrite.feature.settings.dispatchThemeGallery
import com.gruning.mimir.rewrite.feature.settings.dispatchToSettings
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.MimirCopy

/**
 * The app root — the Compose navigation shell.
 *
 * Provides the ONE [FocusAuthority] for the whole window ([LocalFocusAuthority]) AND the single
 * [LibraryRepository] ([LocalLibraryRepository]) so there is provably one source of focus truth and one
 * library truth across every surface. Wraps everything in the active-theme [MimirTheme] (the persisted
 * Norse identity from [ThemeStore]) so Diagnostics, Settings, and Library share one design language and
 * re-skin together when the Appearance picker changes the theme.
 *
 * ### Destinations (single back scope)
 * - **Diagnostics** (M1 root): SYSTEM_START opens Settings ;
 *   the readout also offers a touch action to open the Library.
 * - **Settings**: every action goes to [dispatchToSettings]; CANCEL/Back at the rail exits to Diagnostics.
 * - **Library**: every action goes to [dispatchToLibrary]; CANCEL/Back at the platforms shelf exits to
 *   Diagnostics. The "Add / rescan folder" affordance fires the SAF `ACTION_OPEN_DOCUMENT_TREE` picker,
 *   persists the granted tree URI, and calls `repository.rescan(uri)`.
 *
 * Hardware Back is also wired via [BackHandler] as a belt-and-suspenders path for the OS back gesture.
 */
@Composable
fun MimirApp(
    inputRouter: InputRouter,
    activityStateRestored: Boolean = false,
    controllerNavigationActive: StateFlow<Boolean> = NoControllerNavigationActive,
    interactionMotionGate: InteractionMotionGate,
    companionOverlayController: CompanionOverlayController? = null,
    // TRUE only while the companion Presentation is showing (CompanionDisplayController.active) — gates the
    // payload push so single-screen devices never build payloads nobody consumes (B2, companion WP-0B).
    companionDisplayActive: StateFlow<Boolean> = NoCompanionDisplayActive,
    launchForegroundObserver: LaunchForegroundObserver? = null,
    brightnessController: BrightnessController? = null,
) {
    MimirAppCoordinator(
        inputRouter = inputRouter,
        activityStateRestored = activityStateRestored,
        controllerNavigationActive = controllerNavigationActive,
        interactionMotionGate = interactionMotionGate,
        companionOverlayController = companionOverlayController,
        companionDisplayActive = companionDisplayActive,
        launchForegroundObserver = launchForegroundObserver,
        brightnessController = brightnessController,
    )
}

@Composable
private fun MimirAmbientMotionProvider(
    interactionMotionGate: InteractionMotionGate,
    content: @Composable () -> Unit,
) {
    val phase by interactionMotionGate.phase.collectAsState()
    CompositionLocalProvider(LocalMimirAmbientMotionPhase provides phase, content = content)
}

internal fun resolveStartupDestination(
    origin: PrimaryReturnOrigin,
    firstRun: Boolean,
    homeLaunchReturnPending: Boolean = false,
    finalShelfEnabled: Boolean = BuildConfig.FINAL_SHELF_ENABLED,
): MimirDestination {
    val requested = when {
        firstRun -> MimirDestination.ONBOARDING
        homeLaunchReturnPending -> MimirDestination.HOME
        else -> runCatching { MimirDestination.valueOf(origin.destination) }
            .getOrDefault(MimirDestination.HOME)
    }
    return normalizeMimirDestination(requested, finalShelfEnabled)
}

internal fun selectPrimaryStartupOrigin(
    pendingRecovery: PrimaryReturnOrigin?,
    activityStateRestored: Boolean,
    recreationOrigin: PrimaryReturnOrigin?,
): PrimaryReturnOrigin = pendingRecovery
    ?: recreationOrigin.takeIf { activityStateRestored }
    ?: PrimaryReturnOrigin(MimirDestination.HOME.name)

/**
 * Map a [ArtworkConnectController.ConnectResult] to the connect overlay's next state, choosing HONEST,
 * reason-specific copy so the UI never blames the user's password for a build-level (developer key) or
 * server-side (busy) failure. This prevents connection failures from appearing to reject valid account details.
 */
private fun artworkConnectResultUi(r: ArtworkConnectController.ConnectResult): ArtworkConnectUi = when (r) {
    is ArtworkConnectController.ConnectResult.Success ->
        ArtworkConnectUi.Done(r.account, r.dailyLimit, r.usedToday)
    is ArtworkConnectController.ConnectResult.Failed -> ArtworkConnectUi.Failed(
        when (r.reason) {
            ArtworkConnectController.ConnectResult.Reason.MEMBER_REJECTED ->
                "ScreenScraper didn’t accept that login. Double-check your username and password, then try again."
            ArtworkConnectController.ConnectResult.Reason.UNAVAILABLE ->
                "Cover downloads aren’t available in this version yet — the problem is on our end, not your " +
                    "account. Nothing was saved."
            ArtworkConnectController.ConnectResult.Reason.BUSY ->
                "ScreenScraper is busy right now and is limiting lookups. Give it a few minutes and try again."
            ArtworkConnectController.ConnectResult.Reason.UNREACHABLE ->
                "Couldn’t reach ScreenScraper. Check your connection and try again."
            ArtworkConnectController.ConnectResult.Reason.LOCAL_STORAGE ->
                "ScreenScraper accepted the login, but Mimir couldn’t activate it safely. No new connection was kept."
            ArtworkConnectController.ConnectResult.Reason.LOCAL_RECOVERY ->
                "Network access is off for this session, but local account recovery is incomplete. " +
                    "Retry disconnect before restart."
        },
    )
}

private val NoControllerNavigationActive = MutableStateFlow(false)
private val NoCompanionDisplayActive = MutableStateFlow(false)

private data class NativeAddReturnFocusRequest(
    val mode: LibraryShelfMode,
    val packageName: String?,
)

private data class NativeLaunchReturnFocusRequest(
    val mode: LibraryShelfMode,
    val packageName: String,
)

internal fun libraryLaunchReturnFocusKey(target: LibraryLaunchReturnTarget): FocusKey? =
    when (target.surface) {
        LibraryLaunchReturnTarget.Surface.SHELF -> target.shelfKey?.let { key ->
            when (target.shelfMode) {
                LibraryShelfMode.EMULATION -> FocusKey("library/shelf/$key")
                LibraryShelfMode.ANDROID_GAMES -> nativeShelfAppFocusKey(key)
            }
        }
        LibraryLaunchReturnTarget.Surface.GAMELIST -> {
            target.gameId?.let { FocusKey("library/gamelist/${it.value}") }
                ?: target.nativePackageName?.let(::nativeShelfAppFocusKey)
        }
    }

internal fun restoreStartupGamelistScopeIfReady(authority: FocusAuthority): Boolean =
    when (authority.state.value.scope) {
        GamelistScope -> true
        LibraryShelfScope -> {
            authority.pushScope(GamelistScope)
            true
        }
        else -> false
    }

private fun FocusAuthority.restoreLibraryLaunchReturnScope(target: LibraryLaunchReturnTarget) {
    val targetScope = when (target.surface) {
        LibraryLaunchReturnTarget.Surface.SHELF -> LibraryShelfScope
        LibraryLaunchReturnTarget.Surface.GAMELIST -> GamelistScope
    }
    repeat(LibraryLaunchReturnScopeAttempts) {
        val currentScope = state.value.scope
        if (currentScope == targetScope) return
        when {
            currentScope == FocusScope.Root -> pushScope(LibraryShelfScope)
            targetScope == GamelistScope && currentScope == LibraryShelfScope -> pushScope(GamelistScope)
            else -> popScope()
        }
    }
}

private fun FocusAuthority.restoreLibraryOriginReturnScope(target: LibraryOriginReturnTarget) {
    val targetScope = when (target.surface) {
        LibraryOriginReturnTarget.Surface.SHELF -> LibraryShelfScope
        LibraryOriginReturnTarget.Surface.GAMELIST -> GamelistScope
    }
    repeat(LibraryLaunchReturnScopeAttempts) {
        val currentScope = state.value.scope
        if (currentScope == targetScope) return
        when {
            currentScope == FocusScope.Root -> pushScope(LibraryShelfScope)
            targetScope == GamelistScope && currentScope == LibraryShelfScope -> pushScope(GamelistScope)
            else -> popScope()
        }
    }
}

private fun LibraryOriginReturnTarget.needsNativeRows(): Boolean =
    when {
        surface == LibraryOriginReturnTarget.Surface.SHELF -> shelfMode != LibraryShelfMode.EMULATION
        openPlatformId == LibraryViewModel.FAVORITES_PLATFORM_ID -> favoriteSegment != FavoriteSegment.GAMES
        else -> false
    }


private data class NativeShelfSnapshotState(
    val snapshot: NativeShelfSnapshot = NativeShelfSnapshot(),
    val loaded: Boolean = false,
)

private const val NativeLaunchReturnPrefs = "mimir_native_launch_return"
private const val NativeLaunchReturnModeKey = "mode"
private const val NativeLaunchReturnPackageKey = "packageName"
private const val LibraryLaunchReturnFocusAttempts = 8
private const val LibraryOriginReturnFocusAttempts = 8
private const val LibraryLaunchReturnScopeAttempts = 6

private fun persistNativeLaunchReturn(context: Context, request: NativeLaunchReturnFocusRequest) {
    context.applicationContext
        .getSharedPreferences(NativeLaunchReturnPrefs, Context.MODE_PRIVATE)
        .edit()
        .putString(NativeLaunchReturnModeKey, request.mode.name)
        .putString(NativeLaunchReturnPackageKey, request.packageName)
        .apply()
}

private fun consumeNativeLaunchReturn(context: Context): NativeLaunchReturnFocusRequest? {
    val prefs = context.applicationContext.getSharedPreferences(NativeLaunchReturnPrefs, Context.MODE_PRIVATE)
    val modeName = prefs.getString(NativeLaunchReturnModeKey, null)
    val packageName = prefs.getString(NativeLaunchReturnPackageKey, null)?.takeIf { it.isNotBlank() }
    prefs.edit().clear().apply()
    val mode = LibraryShelfMode.values().firstOrNull { it.name == modeName }
    if (mode == null || mode == LibraryShelfMode.EMULATION || packageName == null) return null
    return NativeLaunchReturnFocusRequest(mode, packageName)
}

private fun clearNativeLaunchReturn(context: Context) {
    context.applicationContext
        .getSharedPreferences(NativeLaunchReturnPrefs, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}

private class NativeShelfRefreshHandle {
    var job: Job? = null
}

private const val NativeAddFocusPrefix = "library/nativeAdd/"
private const val NativeShelfRefreshDebounceMs = 180L
private const val NativeLaunchReturnFocusAttempts = 8
private const val StartupOriginScopeAttempts = 12
private const val StartupOriginFocusAttempts = 60
private const val PrimaryOriginFocusKeyArg = "focusKey"
private const val PrimaryOriginFocusScopeArg = "focusScope"
private const val PrimaryOriginDetailGameArg = "detailGameId"
private const val PrimaryOriginLibrarySurfaceArg = "library.surface"
private const val PrimaryOriginLibraryShelfModeArg = "library.shelfMode"
private const val PrimaryOriginLibraryFocusKeyArg = "library.focusKey"
private const val PrimaryOriginLibraryOpenPlatformArg = "library.openPlatformId"
private const val PrimaryOriginLibraryFavoriteSegmentArg = "library.favoriteSegment"
private const val PrimaryOriginLibrarySearchQueryArg = "library.searchQuery"
private const val PrimaryOriginLibraryFavoritesOnlyArg = "library.favoritesOnly"
private const val LibraryOriginSearchGenreKeySuffix = "searchGenreKey"
private const val LibraryOriginSearchPlatformIdSuffix = "searchPlatformId"
private const val LibraryOriginSearchLastFocusedSuffix = "searchLastFocused"
private const val LibraryOriginSearchLastFocusedGameIdSuffix = "searchLastFocusedGameId"
private const val LibraryOriginSearchLastTopFocusedSuffix = "searchLastTopFocused"
private const val LibraryOriginSearchResultAnchorGameIdSuffix = "searchResultAnchorGameId"
private const val LibraryOriginGamelistViewModeSuffix = "gamelistViewMode"
private const val SettingsOriginPrefix = "settingsOrigin."
private val NativeAddSearchFocusKey = FocusKey("${NativeAddFocusPrefix}search")
private val NativeAddCloseFocusKey = FocusKey("${NativeAddFocusPrefix}close")

@Composable
private fun TrackNativeShelfFocus(
    authority: FocusAuthority,
    shelfMode: LibraryShelfMode,
    onFocusedPackage: (LibraryShelfMode, String) -> Unit,
) {
    val focusState by authority.state.collectAsState()
    LaunchedEffect(focusState.scope, focusState.focused, shelfMode) {
        val packageName = nativePackageFromAppFocusKey(focusState.focused)
        if (focusState.scope == LibraryShelfScope &&
            shelfMode != LibraryShelfMode.EMULATION &&
            packageName != null
        ) {
            onFocusedPackage(shelfMode, packageName)
        }
    }
}

@Composable
private fun PersistPrimaryOriginEffect(
    authority: FocusAuthority,
    destination: MimirDestination,
    openPlatform: PlatformId?,
    detailGame: GameRow?,
    shelfMode: LibraryShelfMode,
    favoriteSegment: FavoriteSegment,
    searchQuery: String,
    favoritesOnly: Boolean,
    settingsLibraryOrigin: LibraryOriginReturnTarget?,
    enabled: Boolean,
) {
    val focusState by authority.state.collectAsState()
    LaunchedEffect(
        enabled,
        destination,
        openPlatform,
        detailGame,
        shelfMode,
        favoriteSegment,
        searchQuery,
        favoritesOnly,
        settingsLibraryOrigin,
        focusState,
    ) {
        if (!enabled) return@LaunchedEffect
        val currentLibraryFocus = when (destination) {
            MimirDestination.LIBRARY -> focusState.focused
            MimirDestination.SETTINGS -> settingsLibraryOrigin?.focusKey
            else -> null
        }
        CompanionPrimaryOriginStore.update(
            PrimaryReturnOrigin(
                destination = destination.name,
                arguments = buildMap {
                    put(
                        PrimaryOriginLibrarySurfaceArg,
                        if (openPlatform == null) {
                            LibraryOriginReturnTarget.Surface.SHELF.name
                        } else {
                            LibraryOriginReturnTarget.Surface.GAMELIST.name
                        },
                    )
                    put(PrimaryOriginLibraryShelfModeArg, shelfMode.name)
                    openPlatform?.value?.let { put(PrimaryOriginLibraryOpenPlatformArg, it) }
                    currentLibraryFocus?.value?.let { put(PrimaryOriginLibraryFocusKeyArg, it) }
                    put(PrimaryOriginLibraryFavoriteSegmentArg, favoriteSegment.name)
                    put(PrimaryOriginLibrarySearchQueryArg, searchQuery)
                    put(PrimaryOriginLibraryFavoritesOnlyArg, favoritesOnly.toString())
                    detailGame?.gameId?.value?.let { put(PrimaryOriginDetailGameArg, it) }
                    focusState.focused?.value?.let { put(PrimaryOriginFocusKeyArg, it) }
                    put(PrimaryOriginFocusScopeArg, focusState.scope.value)
                    settingsLibraryOrigin?.writeTo(this, SettingsOriginPrefix)
                },
            ),
        )
    }
}

internal fun decodePrimaryLibraryOrigin(origin: PrimaryReturnOrigin): LibraryOriginReturnTarget {
    val args = origin.arguments
    val openPlatform = args[PrimaryOriginLibraryOpenPlatformArg]
        ?.takeIf(String::isNotBlank)
        ?.let(::PlatformId)
        // Compatibility with schema 1 origins written before the canonical Library prefix.
        ?: args["openPlatformId"]?.takeIf(String::isNotBlank)?.let(::PlatformId)
    val surface = args[PrimaryOriginLibrarySurfaceArg]
        ?.let { raw -> runCatching { LibraryOriginReturnTarget.Surface.valueOf(raw) }.getOrNull() }
        ?: if (openPlatform == null) {
            LibraryOriginReturnTarget.Surface.SHELF
        } else {
            LibraryOriginReturnTarget.Surface.GAMELIST
        }
    val mode = (args[PrimaryOriginLibraryShelfModeArg] ?: args["shelfMode"])
        ?.let { raw -> runCatching { LibraryShelfMode.valueOf(raw) }.getOrNull() }
        ?: LibraryShelfMode.EMULATION
    val focusKey = (args[PrimaryOriginLibraryFocusKeyArg] ?: args[PrimaryOriginFocusKeyArg])
        ?.takeIf(String::isNotBlank)
        ?.let(::FocusKey)
    val favoriteSegment = args[PrimaryOriginLibraryFavoriteSegmentArg]
        ?.let { raw -> runCatching { FavoriteSegment.valueOf(raw) }.getOrNull() }
        ?: FavoriteSegment.GAMES
    val normalizedSurface = if (surface == LibraryOriginReturnTarget.Surface.GAMELIST && openPlatform == null) {
            LibraryOriginReturnTarget.Surface.SHELF
        } else {
            surface
        }
    return LibraryOriginReturnTarget(
        surface = normalizedSurface,
        shelfMode = mode,
        focusKey = focusKey,
        openPlatformId = openPlatform.takeIf { normalizedSurface == LibraryOriginReturnTarget.Surface.GAMELIST },
        favoriteSegment = favoriteSegment,
        searchQuery = args[PrimaryOriginLibrarySearchQueryArg].orEmpty(),
        favoritesOnly = args[PrimaryOriginLibraryFavoritesOnlyArg]?.toBooleanStrictOrNull() ?: false,
        searchSnapshot = readLibrarySearchReturnSnapshot(
            values = args,
            prefix = "library.",
            query = args[PrimaryOriginLibrarySearchQueryArg].orEmpty(),
            allowed = libraryDedicatedSearchReturnAllowed(
                surface = normalizedSurface,
                openPlatformId = openPlatform,
                favoriteSegment = favoriteSegment,
            ),
        ),
        gamelistViewMode = readLibraryGamelistViewMode(args, "library."),
    )
}

internal fun decodeSettingsLibraryOrigin(origin: PrimaryReturnOrigin): LibraryOriginReturnTarget? =
    readLibraryOrigin(origin.arguments, SettingsOriginPrefix)

internal fun availableStartupLibraryTarget(
    target: LibraryOriginReturnTarget,
    platforms: List<PlatformRow>,
): LibraryOriginReturnTarget {
    if (target.surface != LibraryOriginReturnTarget.Surface.GAMELIST ||
        target.shelfMode != LibraryShelfMode.EMULATION
    ) {
        return target
    }
    val platformId = target.openPlatformId
    val remainsAvailable = platformId == LibraryViewModel.FAVORITES_PLATFORM_ID ||
        platforms.any { it.platformId == platformId }
    if (remainsAvailable) return target
    return target.copy(
        surface = LibraryOriginReturnTarget.Surface.SHELF,
        focusKey = null,
        openPlatformId = null,
        favoriteSegment = FavoriteSegment.GAMES,
        searchQuery = "",
        favoritesOnly = false,
        searchSnapshot = null,
    )
}

internal fun LibraryOriginReturnTarget.writeTo(
    destination: MutableMap<String, String>,
    prefix: String,
) {
    destination[prefix + "surface"] = surface.name
    destination[prefix + "shelfMode"] = shelfMode.name
    focusKey?.value?.let { destination[prefix + "focusKey"] = it }
    openPlatformId?.value?.let { destination[prefix + "openPlatformId"] = it }
    destination[prefix + "favoriteSegment"] = favoriteSegment.name
    destination[prefix + "searchQuery"] = searchQuery
    destination[prefix + "favoritesOnly"] = favoritesOnly.toString()
    destination[prefix + LibraryOriginGamelistViewModeSuffix] = gamelistViewMode.name
    searchSnapshot?.let { snapshot ->
        snapshot.genreKey?.let { destination[prefix + LibraryOriginSearchGenreKeySuffix] = it }
        snapshot.platformId?.value?.let {
            destination[prefix + LibraryOriginSearchPlatformIdSuffix] = it
        }
        destination[prefix + LibraryOriginSearchLastFocusedSuffix] = snapshot.lastFocused.name
        snapshot.lastFocusedResultGameId?.value?.let {
            destination[prefix + LibraryOriginSearchLastFocusedGameIdSuffix] = it
        }
        destination[prefix + LibraryOriginSearchLastTopFocusedSuffix] = snapshot.lastTopFocused.name
        snapshot.resultAnchorGameId?.value?.let {
            destination[prefix + LibraryOriginSearchResultAnchorGameIdSuffix] = it
        }
    }
}

private fun readLibraryOrigin(
    values: Map<String, String>,
    prefix: String,
): LibraryOriginReturnTarget? {
    val surface = values[prefix + "surface"]
        ?.let { raw -> runCatching { LibraryOriginReturnTarget.Surface.valueOf(raw) }.getOrNull() }
        ?: return null
    val mode = values[prefix + "shelfMode"]
        ?.let { raw -> runCatching { LibraryShelfMode.valueOf(raw) }.getOrNull() }
        ?: return null
    val openPlatform = values[prefix + "openPlatformId"]?.takeIf(String::isNotBlank)?.let(::PlatformId)
    if (surface == LibraryOriginReturnTarget.Surface.GAMELIST && openPlatform == null) return null
    return LibraryOriginReturnTarget(
        surface = surface,
        shelfMode = mode,
        focusKey = values[prefix + "focusKey"]?.takeIf(String::isNotBlank)?.let(::FocusKey),
        openPlatformId = openPlatform,
        favoriteSegment = values[prefix + "favoriteSegment"]
            ?.let { raw -> runCatching { FavoriteSegment.valueOf(raw) }.getOrNull() }
            ?: FavoriteSegment.GAMES,
        searchQuery = values[prefix + "searchQuery"].orEmpty(),
        favoritesOnly = values[prefix + "favoritesOnly"]?.toBooleanStrictOrNull() ?: false,
        searchSnapshot = readLibrarySearchReturnSnapshot(
            values = values,
            prefix = prefix,
            query = values[prefix + "searchQuery"].orEmpty(),
            allowed = libraryDedicatedSearchReturnAllowed(
                surface = surface,
                openPlatformId = openPlatform,
                favoriteSegment = values[prefix + "favoriteSegment"]
                    ?.let { raw -> runCatching { FavoriteSegment.valueOf(raw) }.getOrNull() }
                    ?: FavoriteSegment.GAMES,
            ),
        ),
        gamelistViewMode = readLibraryGamelistViewMode(values, prefix),
    )
}

private fun libraryDedicatedSearchReturnAllowed(
    surface: LibraryOriginReturnTarget.Surface,
    openPlatformId: PlatformId?,
    favoriteSegment: FavoriteSegment,
): Boolean = surface == LibraryOriginReturnTarget.Surface.GAMELIST &&
    openPlatformId != null &&
    (
        openPlatformId != LibraryViewModel.FAVORITES_PLATFORM_ID ||
            favoriteSegment == FavoriteSegment.GAMES
        )

private fun readLibrarySearchReturnSnapshot(
    values: Map<String, String>,
    prefix: String,
    query: String,
    allowed: Boolean,
): LibrarySearchReturnSnapshot? {
    if (!allowed) return null
    val keys = listOf(
        LibraryOriginSearchGenreKeySuffix,
        LibraryOriginSearchPlatformIdSuffix,
        LibraryOriginSearchLastFocusedSuffix,
        LibraryOriginSearchLastFocusedGameIdSuffix,
        LibraryOriginSearchLastTopFocusedSuffix,
        LibraryOriginSearchResultAnchorGameIdSuffix,
    )
    if (keys.none { suffix -> values.containsKey(prefix + suffix) }) return null
    return LibrarySearchReturnSnapshot(
        query = query,
        genreKey = values[prefix + LibraryOriginSearchGenreKeySuffix]?.takeIf(String::isNotBlank),
        platformId = values[prefix + LibraryOriginSearchPlatformIdSuffix]
            ?.takeIf(String::isNotBlank)
            ?.let(::PlatformId),
        lastFocused = values[prefix + LibraryOriginSearchLastFocusedSuffix]
            ?.let { raw -> runCatching { LibrarySearchReturnFocus.valueOf(raw) }.getOrNull() }
            ?: LibrarySearchReturnFocus.QUERY,
        lastFocusedResultGameId = values[prefix + LibraryOriginSearchLastFocusedGameIdSuffix]
            ?.takeIf(String::isNotBlank)
            ?.let(::GameId),
        lastTopFocused = values[prefix + LibraryOriginSearchLastTopFocusedSuffix]
            ?.let { raw -> runCatching { LibrarySearchReturnFocus.valueOf(raw) }.getOrNull() }
            ?: LibrarySearchReturnFocus.QUERY,
        resultAnchorGameId = values[prefix + LibraryOriginSearchResultAnchorGameIdSuffix]
            ?.takeIf(String::isNotBlank)
            ?.let(::GameId),
    )
}

private fun readLibraryGamelistViewMode(
    values: Map<String, String>,
    prefix: String,
): GamelistViewMode = values[prefix + LibraryOriginGamelistViewModeSuffix]
    ?.let { raw -> runCatching { GamelistViewMode.valueOf(raw) }.getOrNull() }
    ?: GamelistViewMode.CAROUSEL

internal fun selectStartupLibraryFocusTarget(
    target: LibraryOriginReturnTarget,
    platforms: List<PlatformRow>,
    games: List<GameRow>,
    nativeRows: List<NativeShelfRow>,
): FocusKey? {
    val requested = target.focusKey
    return when (target.surface) {
        LibraryOriginReturnTarget.Surface.SHELF -> when (target.shelfMode) {
            LibraryShelfMode.EMULATION -> {
                val valid = platforms.map { FocusKey("library/shelf/${it.platformId.value}") }
                requested?.takeIf { valid.isNotEmpty() && (it in valid || it.value == "library/shelf/add") }
                    ?: valid.firstOrNull()
            }
            LibraryShelfMode.ANDROID_GAMES -> {
                val valid = nativeRows.map { nativeShelfAppFocusKey(it.packageName) }
                requested?.takeIf { valid.isNotEmpty() && (it in valid || it.value == "library/shelf/add") }
                    ?: valid.firstOrNull()
            }
        }
        LibraryOriginReturnTarget.Surface.GAMELIST -> {
            val gameKeys = games.map { FocusKey("library/gamelist/${it.gameId.value}") }
            val requestedIsAction = requested?.value == "library/gamelist/openwith" ||
                requested?.value == "library/gamelist/getart"
            val nativeKeys = nativeRows.map { nativeShelfAppFocusKey(it.packageName) }
            requested?.takeIf { it in gameKeys || it in nativeKeys || (requestedIsAction && games.isNotEmpty()) }
                ?: gameKeys.firstOrNull()
                ?: nativeKeys.firstOrNull()
        }
    }
}

private fun nativePackageFromAppFocusKey(key: FocusKey?): String? {
    val value = key?.value ?: return null
    if (!value.startsWith(NativeShelfFocusPrefix)) return null
    return value.removePrefix(NativeShelfFocusPrefix).takeIf { it.isNotBlank() }
}

private fun nativeShelfAppFocusKey(packageName: String): FocusKey =
    FocusKey("$NativeShelfFocusPrefix$packageName")

// ── Bifröst ↔ picker-value mapping (feature-settings can't see the library enum) ──
// Two states only . The retired BIFROST_BACKDROP and the internal AURORA both
// display as — and commit to — the single on-state, so the picker can never write them back.
private fun backdropModeToValue(mode: GamelistBackdropMode): String = when (mode) {
    GamelistBackdropMode.OFF -> BackdropValueOff
    else -> BackdropValueMedia
}

private fun backdropValueToMode(value: String): GamelistBackdropMode = when (value) {
    BackdropValueOff -> GamelistBackdropMode.OFF
    else -> GamelistBackdropMode.BIFROST
}

private fun nativeAddAppFocusKey(packageName: String): FocusKey =
    FocusKey("$NativeAddFocusPrefix$packageName")

internal fun nativeAddFocusRing(candidates: List<NativeShelfAddCandidate>): List<FocusKey> =
    buildList {
        add(NativeAddSearchFocusKey)
        candidates.forEach { add(nativeAddAppFocusKey(it.packageName)) }
        add(NativeAddCloseFocusKey)
    }

internal fun selectNativeReturnPackage(packageName: String?, rows: List<NativeShelfRow>): String? =
    packageName
        ?.takeIf { candidate -> rows.any { it.packageName == candidate } }
        ?: rows.firstOrNull()?.packageName

internal fun selectNativeAddReturnPackage(
    currentPackage: String?,
    rememberedPackage: String?,
    rows: List<NativeShelfRow>,
): String? =
    listOfNotNull(currentPackage, rememberedPackage)
        .firstOrNull { candidate -> rows.any { it.packageName == candidate } }
        ?: rows.firstOrNull()?.packageName

internal fun nextNativeAddFocus(
    ring: List<FocusKey>,
    focused: FocusKey?,
    action: ControllerAction,
): FocusKey? {
    if (ring.isEmpty()) return null
    val index = ring.indexOf(focused).takeIf { it >= 0 } ?: 0
    val nextIndex = when (action) {
        ControllerAction.NAV_UP, ControllerAction.NAV_LEFT -> (index - 1).coerceAtLeast(0)
        ControllerAction.NAV_DOWN, ControllerAction.NAV_RIGHT -> (index + 1).coerceAtMost(ring.lastIndex)
        else -> index
    }
    return ring[nextIndex]
}

@Suppress("UNUSED_PARAMETER")
internal fun nativeLaunchReturnShouldSwallowInput(
    restoring: Boolean,
    action: ControllerAction,
): Boolean = restoring

/**
 * Whether the full-screen rune return-cover should be up.
 *
 * *** The `settings` arm is therefore GONE — not shortened, removed. It used to be
 * `native || library || settings || home`, and a Library → START → Settings → back round trip raised
 * the cover every time.
 *
 * Shortening was never going to satisfy it: the cover is `EnterTransition.None` +
 * `fadeOut(MimirMotion.OverlayMs)`, so even a frame-perfect return showed **≥180 ms of rune**. The
 * minimum visible duration comes from that existing exit fade.
 *
 * The three surviving arms are LAUNCH returns — the app was actually backgrounded for a game or a
 * native app and genuinely has state to restore. A settings visit never left the process, so there was
 * nothing to cover in the first place. `settingsLibraryReturnRequest` still drives the real restore
 * work in `MimirNavigationCoordinator`; only its claim on the cover is withdrawn.
 */
internal fun primaryReturnRestoring(
    native: Boolean,
    library: Boolean,
    home: Boolean = false,
): Boolean = native || library || home

private fun NativeShelfAddCandidate.matchesNativeAddQuery(query: String): Boolean =
    listOf(
        label,
        packageName,
        rowKindLabel ?: sourceKind.rowKindLabel,
        targetKind.shelfLabel,
        when (reason) {
            com.gruning.mimir.rewrite.feature.library.NativeShelfAddReason.HIDDEN_HERE -> "Hidden restore"
            com.gruning.mimir.rewrite.feature.library.NativeShelfAddReason.MOVE_FROM_OTHER_SHELF -> {
                if (isHidden) "Hidden add ${targetKind.shelfLabel}" else "Add ${targetKind.shelfLabel}"
            }
        },
    ).any { it.contains(query, ignoreCase = true) }

private fun NativeShelfSnapshot.rowsForMode(mode: LibraryShelfMode) = when (mode) {
    LibraryShelfMode.EMULATION -> emptyList()
    LibraryShelfMode.ANDROID_GAMES -> games
}
