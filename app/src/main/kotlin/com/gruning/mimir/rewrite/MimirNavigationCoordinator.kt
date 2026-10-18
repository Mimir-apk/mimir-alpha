package com.gruning.mimir.rewrite

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.GameDetailScope
import com.gruning.mimir.rewrite.feature.library.LibraryDetailDescriptionKey
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.LibraryDetailAchievementsKey
import com.gruning.mimir.rewrite.feature.library.LibraryDetailOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume
import com.gruning.mimir.rewrite.feature.library.LibraryDetailPlayKey
import com.gruning.mimir.rewrite.feature.library.LibraryDetailRestartKey
import com.gruning.mimir.rewrite.feature.library.LibraryDetailSession
import com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryOriginReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.exitLibraryDetail
import com.gruning.mimir.rewrite.feature.library.restoreLibraryDetailSession
import com.gruning.mimir.rewrite.feature.library.isSettledFor
import com.gruning.mimir.rewrite.feature.settings.detailKey
import kotlinx.coroutines.delay

/** Navigation, startup restoration, and origin-aware return state kept outside the app root method. */
internal class MimirNavigationCoordinator internal constructor(
    private val destinationState: MutableState<MimirDestination>,
    val startupOrigin: PrimaryReturnOrigin,
    val firstRunAtStartup: Boolean,
    val startupSettingsFocusKey: FocusKey?,
    val startupSettingsFocusScope: FocusScope?,
    private val context: Context,
    private val viewModel: LibraryViewModel,
    private val focusAuthority: FocusAuthority,
    private val brightness: AppBrightnessCoordinator,
    finalShelfEnabled: Boolean,
) {
    private val governedDestination = FinalShelfDestinationState(destinationState, finalShelfEnabled)

    var destination: MimirDestination
        get() = governedDestination.value
        set(requested) {
            governedDestination.value = requested
        }
    var startupSettingsFocusPending by mutableStateOf(startupSettingsFocusKey != null)

    /**
     * A one-shot Settings row target (product review 2026-09-28, F3). SettingsHome reads it once when it mounts;
     * the Settings destination clears it when it leaves, so a later visit uses the normal entry.
     */
    var settingsEntryFocusKey by mutableStateOf<FocusKey?>(null)
    var startupOriginApplied by mutableStateOf(false)
    var settingsReturnDestination by mutableStateOf<MimirDestination?>(null)
    var settingsLibraryOrigin by mutableStateOf(decodeSettingsLibraryOrigin(startupOrigin))
    var settingsLibraryReturnRequest by mutableStateOf<LibraryOriginReturnTarget?>(null)
    var settingsLibraryDetailSession by mutableStateOf<LibraryDetailSession?>(null)
    var settingsLibraryDetailFocusKey by mutableStateOf<FocusKey?>(null)

    /** START from the Home launch card sent the player to Settings with the card held ([HomeLaunchCardCoordinator.suspendedForSettings]). */
    var settingsFromHomeLaunchCard by mutableStateOf(false)

    /** B left Settings for Home with the card held: the app resumes the card, once. Any other way out of Settings leaves this false, and the held card is dropped. */
    var homeLaunchCardReturnRequest by mutableStateOf(false)
    var libraryLaunchReturnArmed by mutableStateOf(false)
    var libraryLaunchReturnRestoring by mutableStateOf(false)
    var libraryHostResumed by mutableStateOf(false)

    /** The return cover's clock for the Library arm (LibraryLaunchReturnCoverPatience.kt): one absolute deadline per return. */
    val libraryLaunchReturnCoverClock = LibraryLaunchReturnCoverClock()
    var onboardingStep by mutableStateOf(OnboardingStore.checkpoint(context).step)

    val onboardingProfile = OnboardingProfileState()

    /** WELCOME's verse-first intro, shared by its screen and its dispatch. */
    val onboardingWelcomeIntro = OnboardingWelcomeIntroState()

    /** The step on screen while the last one glides out , shared by the flow and its dispatch. */
    val onboardingStepGlide = OnboardingStepGlideState(onboardingStep)
    val onboardingAccessReview = OnboardingAccessReviewState()

    /** The FAVORITES grid's filter + measured geometry, shared by its screen and its dispatch. */
    val onboardingFavoritesGrid = OnboardingFavoritesGridState()

    /** The loading hint carousel's turns, asked for by the dispatch and played by the screen. */
    val onboardingHintCarousel = OnboardingHintCarouselState()

    //  LIBRARY's action pages, shared by the flow and its dispatch like the carousel.
    val onboardingActionTour = OnboardingActionTourState()
    val onboardingLibrary = OnboardingLibraryState()
    val onboardingFocusIntent = OnboardingFocusIntentState()

    /** FUX-01 §4.4: the dock's Quick Launch opens the shared-truth picker (QuickLaunchStore slots). */
    var homeQuickLaunchOpen by mutableStateOf(false)
        private set

    /** QL01: null = dock only; General launches, Assign writes one of the three persisted slots. */
    var homeQuickLaunchPickerMode by mutableStateOf<HomeQuickLaunchPickerMode?>(null)
        private set

    /** FUX-10: Y-on-tile menu (remove / change icon art). Null = closed. */
    var homeTileMenu by mutableStateOf<HomeTileMenuState?>(null)

    /**
     * the manual game-rename overlay (cosmetic display override). Shared by
     * both launch cards — Home hero and Library detail — since both open it through the same rune glyph
     * on the same shared `HomeLaunchCard` composition. Null = closed.
     */
    var gameTitleEdit by mutableStateOf<GameTitleEditRequest?>(null)
        private set

    fun openGameTitleEdit(gameId: GameId, currentTitle: String, hasOverride: Boolean) {
        gameTitleEdit = GameTitleEditRequest(gameId, currentTitle, hasOverride)
    }

    fun closeGameTitleEdit() {
        gameTitleEdit = null
    }

    /** X opens the typed Tome Library; it never mutates Home by cycling a context label. */
    var homeTomeLibrary by mutableStateOf<HomeTomeLibraryState?>(null)

    /** Active controller keyboard binding while the Tome Library name editor is open. */
    var homeTomeLibraryKeyboardHost by mutableStateOf<MimirKeyboardHost?>(null)

    /**
     * The Tome Library's focused-tome backdrop art, on its way DOWN to the Home shelf's backdrop stage.
     *
     * Policy 2026-08-27, *"one backdrop under both surfaces"*. It lives here because the two
     * composables involved do not share a scope -- `HomeTomeLibraryOverlayHost` and `HomeShelfScreen` are
     * mounted from different places in `MimirAppHost` -- and the alternative was for the Library to keep
     * painting a second full-screen host over the shelf's, which is the mount/unmount the regression report described.
     */
    var homeTomeLibraryBackdropArt by mutableStateOf<String?>(null)

    /**
     * CP01 beat 3 : has
     * the Tome Library's OWN open arrival (`MimirDirectOverlayPixelFadeHost`'s incoming alpha, for the
     * `HOME_TOME_LIBRARY` kind) reached alpha 1 for the CURRENT open.
     *
     * A latch, not a live read of the arrival's progress — set `false` the instant a fresh open begins
     * and `true` once that open's arrival completes, and left untouched (NOT reset) while the Library is
     * merely closing. Resetting it on close would un-gate [homeTomeLibraryBackdropArt]'s stage priority
     * during the close-hold and reintroduce a flash on the RETURN direction — the exact regression
     * `onBackdropArt`'s own kdoc exists to prevent (*"can vanish onto an identical picture"*), and a
     * direction this ticket's spec explicitly does not touch.
     */
    var homeTomeLibraryOpenSettled by mutableStateOf(false)

    /**
     * CP02 beat 3 : a
     * monotonic counter bumped alongside every `false`-edge of [homeTomeLibraryOpenSettled] above — i.e.
     * once per FRESH arrival, never reset. [homeTomeLibraryOpenSettled] alone cannot tell "this arrival
     * settled" apart from "a PREVIOUS arrival settled and was never reset", which is exactly the hazard
     * named in the spec: the Folio's return republishes the Library non-null and the latch may still read
     * `true` from before the Folio was opened. A waiter fences on this epoch (read once, before the wait
     * starts) so it can require "the epoch advanced past what I captured, AND settled is true" rather than
     * trusting whatever `homeTomeLibraryOpenSettled` already says. Two readers now:
     * `HomeShelfCoordinator.kt`'s `closeTomeToLibrary`, and `MimirAppHost.kt`'s scene-glass release
     * (`mimirSceneGlassSuppressed`), which fences on it for exactly the hazard described above -- on a
     * folio return the latch still reads `true` from before the Folio was opened.
     *
     * Written from the SAME site as [homeTomeLibraryOpenSettled] (`MimirAppHost.kt`'s
     * `onIncomingSettleChanged` callback) so the two can never drift apart — never widen this to a second
     * writer.
     */
    var homeTomeLibraryOpenArrivalEpoch by mutableStateOf(0L)

    /** Y-on-platform-tile art menu (change icon art / revert to default). Null = closed. */
    var platformTileMenu by mutableStateOf<PlatformArtMenuState?>(null)

    fun openHomeTileMenu(state: HomeTileMenuState) {
        homeTileMenu = state
    }

    fun closeHomeTileMenu() {
        homeTileMenu = null
    }

    fun openPlatformTileMenu(state: PlatformArtMenuState) {
        platformTileMenu = state
    }

    fun closePlatformTileMenu() {
        platformTileMenu = null
    }

    /**
     * Monotonic open generation. The overlay host keys the panel's composition on it so a reopen
     * inside the dismiss fade tears down the outgoing composition and remounts — without this, the
     * retained AnimatedVisibility content skips the mount effect and the reopened panel has no
     * focus scope (2026-08-11 review: scope-less dead panel on fade-window reopen).
     */
    var homeQuickLaunchEpoch by mutableStateOf(0L)
        private set

    fun openHomeQuickLaunch() {
        homeQuickLaunchEpoch += 1
        homeQuickLaunchOpen = true
        homeQuickLaunchPickerMode = null
    }

    fun openHomeQuickLaunchGeneral() {
        if (homeQuickLaunchOpen) homeQuickLaunchPickerMode = HomeQuickLaunchPickerMode.General
    }

    fun editHomeQuickLaunchSlot(index: Int) {
        if (homeQuickLaunchOpen && index in 0 until QuickLaunchStore.SLOT_COUNT) {
            homeQuickLaunchPickerMode = HomeQuickLaunchPickerMode.Assign(index)
        }
    }

    fun closeHomeQuickLaunchPicker() {
        // Scope ownership must unwind in the command frame. Waiting for the picker's onDispose lets
        // Utility push above it, after which disposal can no longer pop the stale picker scope.
        unwindHomeQuickLaunchFocus(focusAuthority, includeQuickLaunch = false)
        homeQuickLaunchPickerMode = null
    }

    fun closeHomeQuickLaunch() {
        closeHomeQuickLaunchPicker()
        unwindHomeQuickLaunchFocus(focusAuthority, includeQuickLaunch = true)
        homeQuickLaunchOpen = false
    }

    /**
     * The M button's HOME navigation: clear transient navigation state and settle Home. Only HOME
     * routes here — the caller sends SETTINGS to [openSettingsFromCurrent] (gate F5), and
     * QUICK_LAUNCH deliberately never navigates: the
     * panel opens IN PLACE via `executeQuickLaunchInPlace`, the shelf quick rail's long-standing
     * behaviour. The former `command` parameter is gone with its last read (review: dead parameter).
     */
    fun applyMButtonHomeNavigation() {
        // An explicit user command outranks every automatic launch-return anchor. Disarm and consume the
        // ROM-side request synchronously so a resume effect cannot force Library back over Home/Quick Launch.
        disarmLibraryLaunchReturn()
        viewModel.clearPendingLaunchReturnTarget()
        homeTileMenu = null
        homeTomeLibrary = null
        platformTileMenu = null
        gameTitleEdit = null
        closeHomeQuickLaunch()
        settingsReturnDestination = null
        settingsLibraryOrigin = null
        settingsLibraryReturnRequest = null
        settingsLibraryDetailSession = null
        settingsLibraryDetailFocusKey = null
        destination = MimirDestination.HOME
        restoreHomeContentFocusAfterMButton(focusAuthority)
    }

    fun onShelfQuickAction(action: MimirQuickRailAction) {
        val observeNavigation = BuildConfig.DEBUG && DebugHomeRailObservation.isArmed()
        val before = if (observeNavigation) destination else null
        when (action) {
            MimirQuickRailAction.HOME -> destination = MimirDestination.HOME
            MimirQuickRailAction.LIBRARY -> destination = MimirDestination.LIBRARY
            MimirQuickRailAction.BRIGHTNESS -> brightness.open()
            MimirQuickRailAction.SETTINGS -> openSettingsFromCurrent()
            MimirQuickRailAction.QUICK_LAUNCH -> openHomeQuickLaunch()
            // Two doors, deliberately, while the dock route is being judged (product interaction path): the Utility "Files" row calls this
            // exact function (AppBrightnessCoordinator.kt ~:750/:2077), so both doors share one policy
            // call and one surface construction -- the dock adds a caller, not a code path. KNOWN GAP,
            // the design calls for an `entry` parameter so the ungranted-access branch
            // lands on Utility's Files row instead of raising the system grant screen with no
            // forewarning; that needs AppBrightnessCoordinator.kt/AppBrightnessHost.kt, and remains pending. OPEN and UNAVAILABLE already match the spec exactly (same function);
            // REQUEST_ALL_FILES_ACCESS matches the Utility row's own existing behaviour, just reached
            // here without that row's forewarning label.
            MimirQuickRailAction.FILES -> brightness.openFilesManager()
        }
        if (observeNavigation) {
            before?.let { DebugHomeRailObservation.recordNavigation(action, before = it, after = destination) }
        }
    }

    fun openSettingsFromCurrent(detailSession: LibraryDetailSession? = null) {
        settingsReturnDestination = destination
        settingsLibraryOrigin = if (destination == MimirDestination.LIBRARY) {
            // The typed session remains the detail truth while a child scope (Open With, achievements, etc.)
            // temporarily owns focus. Capture the detail scope's remembered key instead of mistaking the child
            // row key for a launch-card action.
            val liveSession = detailSession
            settingsLibraryDetailSession = liveSession
            settingsLibraryDetailFocusKey = liveSession?.let {
                if (focusAuthority.state.value.scope == GameDetailScope) {
                    focusAuthority.state.value.focused
                } else {
                    focusAuthority.rememberedFocusIn(GameDetailScope)
                }
            }
            val originFocus = when {
                liveSession != null -> liveSession.originFocusKey
                focusAuthority.state.value.scope == LibraryQuickRailScope -> {
                    val originScope = if (viewModel.openPlatform.value == null) {
                        LibraryShelfScope
                    } else {
                        GamelistScope
                    }
                    focusAuthority.rememberedFocusIn(originScope)
                }
                else -> focusAuthority.state.value.focused
            }
            val origin = viewModel.captureOriginReturnTarget(originFocus)
            viewModel.closeLibrarySearchForExternalNavigation()
            origin
        } else {
            settingsLibraryDetailSession = null
            settingsLibraryDetailFocusKey = null
            null
        }
        destination = MimirDestination.SETTINGS
    }

    /** The achievement card's NEEDS SETUP action (F3): Settings opens with the RetroAchievements row focused. */
    fun openRetroAchievementsSettings(detailSession: LibraryDetailSession? = null) {
        settingsEntryFocusKey = detailKey("profile/retroachievements")
        openSettingsFromCurrent(detailSession)
    }

    fun selectShellTab(tab: ShellTab) {
        if (tab == ShellTab.SETTINGS && destination != MimirDestination.SETTINGS) {
            settingsReturnDestination = destination
        }
        destination = when (tab) {
            ShellTab.HOME -> MimirDestination.HOME
            ShellTab.BROWSE -> MimirDestination.BROWSE
            ShellTab.SETTINGS -> MimirDestination.SETTINGS
        }
    }

    fun restoreSettingsOriginOrLibraryRoot() {
        val target = settingsLibraryOrigin
        val returnDestination = settingsReturnDestination
        settingsLibraryOrigin = null
        settingsReturnDestination = null
        if (settingsFromHomeLaunchCard) {
            homeLaunchCardReturnRequest = target == null && (returnDestination ?: MimirDestination.HOME) == MimirDestination.HOME
            settingsFromHomeLaunchCard = false
        }
        if (target != null) {
            settingsLibraryReturnRequest = target
        } else {
            settingsLibraryDetailSession = null
            settingsLibraryDetailFocusKey = null
            destination = returnDestination ?: MimirDestination.HOME
        }
    }

    fun openPlatformInLibrary(platformId: PlatformId) {
        viewModel.openPlatform(platformId)
        enterMountedLibraryGamelist(focusAuthority, destination)
        destination = MimirDestination.LIBRARY
    }

    fun armLibraryLaunchReturn() {
        libraryLaunchReturnCoverClock.reset()
        libraryLaunchReturnArmed = true
        libraryLaunchReturnRestoring = true
    }

    fun updateLibraryHostResumed(resumed: Boolean) {
        libraryHostResumed = resumed
    }

    /** The cover's bound ran out ([MimirLibraryLaunchReturnCoverBound]): the cover lifts; the restore itself goes on. */
    fun liftLibraryLaunchReturnCover() {
        if (!libraryLaunchReturnRestoring) return
        libraryLaunchReturnRestoring = false
        logLibraryLaunchReturnCoverLifted()
    }

    fun disarmLibraryLaunchReturn() {
        libraryLaunchReturnCoverClock.reset()
        libraryLaunchReturnArmed = false
        libraryLaunchReturnRestoring = false
    }
}

/** Command-frame scope settlement used before a sibling overlay (Utility) is allowed to push. */
internal fun unwindHomeQuickLaunchFocus(
    authority: FocusAuthority,
    includeQuickLaunch: Boolean,
) {
    // Both releases are total. The sequencing here was already correct -- picker first, then the
    // panel it covers -- but sequencing only helps when nothing ELSE is on top, and the quick-launch
    // panel has a documented list of overlay kinds that bury it.
    authority.discardScope(HomeQuickLaunchPickerScope)
    if (includeQuickLaunch) authority.discardScope(HomeQuickLaunchScope)
}

/** HOME is a recovery command even when Home is already active: leave its child dock scope. */
internal fun restoreHomeContentFocusAfterMButton(authority: FocusAuthority) {
    when (authority.state.value.scope) {
        HomeShelfQuickRailScope -> authority.popScope()
        HomeTomeLibraryScope -> leaveHomeTomeScope(authority, HomeTomeLibraryScope)
        com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope -> leaveHomeTomeScope(
            authority,
            com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope,
        )
        else -> Unit
    }
}

@Composable
internal fun rememberMimirNavigationCoordinator(
    core: MimirAppCoreCoordinator,
    activityStateRestored: Boolean,
): MimirNavigationCoordinator {
    val startupOrigin = remember(activityStateRestored) {
        selectPrimaryStartupOrigin(
            pendingRecovery = CompanionPrimaryOriginStore.consumeStartupRecovery(),
            activityStateRestored = activityStateRestored,
            recreationOrigin = CompanionPrimaryOriginStore.activityRecreationOrigin(),
        )
    }
    val firstRunAtStartup = remember(core.context) { OnboardingStore.isFirstRun(core.context) }
    val finalShelfEnabled = BuildConfig.FINAL_SHELF_ENABLED
    val startupDestination = remember(startupOrigin, firstRunAtStartup, core.persistedHomeLaunchReturn) {
        resolveStartupDestination(
            origin = startupOrigin,
            firstRun = firstRunAtStartup,
            homeLaunchReturnPending = core.persistedHomeLaunchReturn != null,
            finalShelfEnabled = finalShelfEnabled,
        )
    }
    val startupSettingsFocusKey = remember(startupOrigin, startupDestination) {
        if (startupDestination == MimirDestination.SETTINGS) {
            startupOrigin.arguments[MimirPrimaryOriginFocusKeyArg]
                ?.takeIf(String::isNotBlank)
                ?.let(::FocusKey)
        } else {
            null
        }
    }
    val startupSettingsFocusScope = remember(startupOrigin, startupDestination) {
        if (startupDestination == MimirDestination.SETTINGS) {
            startupOrigin.arguments[MimirPrimaryOriginFocusScopeArg]
                ?.takeIf(String::isNotBlank)
                ?.let { raw -> runCatching { FocusScope(raw) }.getOrNull() }
        } else {
            null
        }
    }
    val destinationState = rememberSaveable(finalShelfEnabled) { mutableStateOf(startupDestination) }

    LaunchedEffect(destinationState, finalShelfEnabled) {
        destinationState.value = normalizeMimirDestination(destinationState.value, finalShelfEnabled)
    }
    LaunchedEffect(core.context, finalShelfEnabled) {
        while (!HomeLaunchReturnStore.clearWhenFinalShelfDisabled(core.context, finalShelfEnabled)) {
            delay(HomeLaunchReturnDurabilityRetryMs)
        }
    }

    return remember(
        destinationState,
        startupOrigin,
        firstRunAtStartup,
        startupSettingsFocusKey,
        startupSettingsFocusScope,
        core.context,
        core.libraryViewModel,
        core.focusAuthority,
        core.brightness,
        finalShelfEnabled,
    ) {
        MimirNavigationCoordinator(
            destinationState = destinationState,
            startupOrigin = startupOrigin,
            firstRunAtStartup = firstRunAtStartup,
            startupSettingsFocusKey = startupSettingsFocusKey,
            startupSettingsFocusScope = startupSettingsFocusScope,
            context = core.context,
            viewModel = core.libraryViewModel,
            focusAuthority = core.focusAuthority,
            brightness = core.brightness,
            finalShelfEnabled = finalShelfEnabled,
        )
    }
}

@Composable
internal fun MimirNavigationEffects(
    core: MimirAppCoreCoordinator,
    library: MimirLibraryCoordinator,
    nativeShelf: MimirNativeShelfCoordinator,
    navigation: MimirNavigationCoordinator,
) {
    MimirStartupOriginEffect(core.focusAuthority, library, nativeShelf, navigation)
    MimirLibraryLaunchReturnSavedStateEffect(
        core.libraryViewModel,
        onRestoredAfterKill = navigation::armLibraryLaunchReturn,
        savedDestination = navigation.destination,
    )
    MimirLibraryLaunchReturnEffect(core.focusAuthority, core.libraryViewModel, library, nativeShelf, navigation)
    MimirLibraryLaunchReturnCoverBound(
        deadlineMs = navigation.libraryLaunchReturnCoverClock.deadlineMs,
        resumed = navigation.libraryHostResumed,
        onLift = navigation::liftLibraryLaunchReturnCover,
    )
    MimirGamelistCloseReturnEffect(core.focusAuthority, library)
    MimirSettingsLibraryReturnEffect(core.focusAuthority, core.libraryViewModel, library, nativeShelf, navigation)
    MimirPrimaryOriginEffect(core.focusAuthority, library, navigation)
    MimirBrowseDestinationEffect(navigation.destination, library)
}

@Composable
private fun MimirStartupOriginEffect(
    authority: FocusAuthority,
    library: MimirLibraryCoordinator,
    nativeShelf: MimirNativeShelfCoordinator,
    navigation: MimirNavigationCoordinator,
) {
    LaunchedEffect(
        navigation.startupOriginApplied,
        navigation.startupOrigin,
        navigation.destination,
        library.openPlatform,
        library.games,
        library.platforms,
        library.platformsLoaded,
        library.gamesLoadedForPlatform,
        nativeShelf.loadState,
    ) {
        if (navigation.startupOriginApplied) return@LaunchedEffect
        if (navigation.firstRunAtStartup) {
            navigation.startupSettingsFocusPending = false
            navigation.startupOriginApplied = true
            return@LaunchedEffect
        }

        if (navigation.destination == MimirDestination.LIBRARY) {
            val decodedTarget = decodePrimaryLibraryOrigin(navigation.startupOrigin)
            if (decodedTarget.requiresNativeRows() && !nativeShelf.loadState.loaded) return@LaunchedEffect
            if (decodedTarget.shelfMode == LibraryShelfMode.EMULATION && !library.platformsLoaded) {
                return@LaunchedEffect
            }
            val target = availableStartupLibraryTarget(decodedTarget, library.platforms)

            val viewModel = nativeShelf.libraryViewModel
            viewModel.restoreOriginReturnTargetState(target)
            val platformId = target.openPlatformId
            if (library.openPlatform != platformId) return@LaunchedEffect
            if (platformId != null) {
                if (library.gamesLoadedForPlatform != platformId) return@LaunchedEffect
                var scopeReady = false
                for (attempt in 0 until MimirStartupOriginScopeAttempts) {
                    withFrameNanos { }
                    if (restoreStartupGamelistScopeIfReady(authority)) {
                        scopeReady = true
                        break
                    }
                }
                if (!scopeReady) return@LaunchedEffect
            }

            val detailId = navigation.startupOrigin.arguments[MimirPrimaryOriginDetailGameArg]
            if (detailId != null && library.detailGame?.gameId?.value != detailId) {
                library.games.firstOrNull { it.gameId.value == detailId }?.let(viewModel::focusMetadata)
            }

            val focusTarget = selectStartupLibraryFocusTarget(
                target = target,
                platforms = library.platforms,
                games = library.games,
                nativeRows = nativeShelf.rowsForMode(target.shelfMode),
            )
            if (focusTarget != null) {
                for (attempt in 0 until MimirStartupOriginFocusAttempts) {
                    if (navigation.destination != MimirDestination.LIBRARY) break
                    withFrameNanos { }
                    if (target.surface == LibraryOriginReturnTarget.Surface.GAMELIST) {
                        restoreStartupGamelistScopeIfReady(authority)
                    }
                    authority.requestFocus(focusTarget)
                    if (authority.state.value.focused == focusTarget) break
                }
            }
        } else if (navigation.destination == MimirDestination.SETTINGS) {
            navigation.startupSettingsFocusKey
                ?.takeIf { navigation.startupSettingsFocusPending }
                ?.let { target ->
                    repeat(MimirStartupOriginScopeAttempts) {
                        if (navigation.destination != MimirDestination.SETTINGS ||
                            authority.state.value.focused == target
                        ) {
                            return@let
                        }
                        withFrameNanos { }
                    }
                }
            navigation.startupSettingsFocusPending = false
        } else {
            navigation.startupOrigin.arguments[MimirPrimaryOriginFocusKeyArg]
                ?.takeIf(String::isNotBlank)
                ?.let(::FocusKey)
                ?.let { target ->
                    repeat(MimirStartupOriginScopeAttempts) {
                        withFrameNanos { }
                        authority.requestFocus(target)
                        if (authority.state.value.focused == target) return@let
                    }
                }
        }
        navigation.startupOriginApplied = true
    }
}

/**
 * The pending launch-return target, beside the host's destination in the saved instance state, so a PROCESS killed while the game ran (a
 * low-memory kill during an emulator load: the Thor, 2026-10-14) still returns to the launch card. The destination is already saved
 * (`rememberMimirNavigationCoordinator`'s `rememberSaveable`) and says LIBRARY; without this the new process had no target and landed on the
 * shelf. Written whenever the pending target changes (and cleared when it is consumed), read once when the host is created: with no launch
 * pending (a new ViewModel), a saved target is put back and the return is armed, and the restore treats it as an ended session. A
 * configuration change keeps the ViewModel, which still has its own target, so nothing is read.
 */
@Composable
internal fun MimirLibraryLaunchReturnSavedStateEffect(
    viewModel: LibraryViewModel,
    onRestoredAfterKill: () -> Unit,
    /**
     * The destination the host was restored on, saved beside the target ([rememberMimirNavigationCoordinator]). The target is put back only
     * when it is [MimirDestination.LIBRARY] ([libraryLaunchReturnMayRestoreAfterKill]); the default keeps a caller that has no destination to
     * offer on the old behaviour.
     */
    savedDestination: MimirDestination = MimirDestination.LIBRARY,
) {
    val saved = rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    LaunchedEffect(viewModel) {
        if (viewModel.pendingLaunchReturnTarget.value == null && libraryLaunchReturnMayRestoreAfterKill(savedDestination)) {
            LibraryLaunchReturnTarget.fromSavedState(saved.value)?.let { target ->
                if (viewModel.restorePendingLaunchReturnTargetAfterKill(target)) onRestoredAfterKill()
            }
        }
        // Starts from the current value, so the restored target is what is written back, not a stale null.
        viewModel.pendingLaunchReturnTarget.collect { saved.value = it?.toSavedState() }
    }
}

/**
 * Whether a launch-return target saved before a process kill may be put back (ttx re-check, P3): only when the destination saved beside it is
 * the Library. A game launched from the Library parks its target there; a game launched from anywhere else (a running Bottom game, a
 * Home launch) leaves the saved destination on Home or Settings, and a stale Library target restored there pulled Home to the game's card.
 * The stale value is then dropped by the collect below, which writes the (empty) current target back.
 */
internal fun libraryLaunchReturnMayRestoreAfterKill(savedDestination: MimirDestination): Boolean =
    savedDestination == MimirDestination.LIBRARY

@Composable
private fun MimirLibraryLaunchReturnEffect(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    library: MimirLibraryCoordinator,
    nativeShelf: MimirNativeShelfCoordinator,
    navigation: MimirNavigationCoordinator,
) {
    val sessionSnapshot by MimirSessionAuthority.state.collectAsState()
    LaunchedEffect(
        navigation.libraryLaunchReturnArmed,
        navigation.libraryHostResumed,
        library.pendingLaunchReturnTarget,
        library.gamesLoadedForPlatform,
        library.games,
        nativeShelf.loadState,
        sessionSnapshot.revision,
    ) {
        if (!libraryLaunchReturnHasViableAnchor(
                armed = navigation.libraryLaunchReturnArmed,
                target = library.pendingLaunchReturnTarget,
            )
        ) {
            // The resumed-host watchdog can consume a failed launch target after this effect has
            // raised the branded return cover. Reconcile both flags here so a terminal cleanup can
            // never strand that cover over an otherwise healthy, resumed Mimir window.
            if (library.pendingLaunchReturnTarget == null) {
                navigation.disarmLibraryLaunchReturn()
            } else {
                navigation.libraryLaunchReturnRestoring = false
            }
            return@LaunchedEffect
        }
        if (!navigation.libraryHostResumed) return@LaunchedEffect
        val target = requireNotNull(library.pendingLaunchReturnTarget)
        // The park's pending mark is set in onNewIntent, before the resume that flips libraryHostResumed, so this read is fresh for the park.
        if (!libraryLaunchReturnMayRestore(
                target,
                sessionSnapshot.gameSession,
                restoredAfterKill = viewModel.isLaunchReturnRestoredAfterKill(target),
                homeParkPending = MainGameHomePark.pending(),
            )
        ) {
            return@LaunchedEffect
        }
        if (target.surface == LibraryLaunchReturnTarget.Surface.SHELF &&
            target.shelfMode != LibraryShelfMode.EMULATION &&
            !nativeShelf.loadState.loaded
        ) {
            return@LaunchedEffect
        }
        // A real return (past libraryLaunchReturnMayRestore): the cover holds only while its clock does (LibraryLaunchReturnCoverPatience.kt).
        // The first run mints the deadline; a restart past it leaves the cover down, and the restore below goes on either way.
        navigation.libraryLaunchReturnRestoring = navigation.libraryLaunchReturnCoverClock.holds(SystemClock.elapsedRealtime())
        navigation.destination = MimirDestination.LIBRARY
        viewModel.restoreLaunchReturnTargetState(target)
        if (target.gameId != null && library.gamesLoadedForPlatform != target.openPlatformId) {
            return@LaunchedEffect
        }
        if (target.restoreLaunchCard && target.nativeReturnPackageName() != null && !nativeShelf.loadState.loaded) {
            return@LaunchedEffect
        }
        val liveDetailTarget = liveLibraryLaunchReturnDetailTarget(
            target = target,
            games = library.games,
            nativeGames = nativeShelf.snapshot.games,
        )
        val detailSession = resolveLibraryLaunchReturnDetailSession(
            target = target,
            currentSession = library.detailSession,
            activeTarget = liveDetailTarget,
        )
        if (libraryLaunchReturnClosesOpenCard(target, detailSession, library.detailSession != null)) {
            // The card is closed when it cannot be restored (the game is gone from the list), and when the target does not ask for it
            // (a game launched from the shelf or the list): a card left open from an earlier session would keep the scene glass on and put
            // the return back under a blurred background (reported on Odin after Eden crashed on exit).
            viewModel.closeGameDetail()
            library.activeDetailTarget?.let(library.closeDetail)
        }
        installLibraryLaunchReturnDetailSession(
            session = detailSession,
            openDetail = library.openDetail,
            refreshOpenWith = { game -> viewModel.loadOpenWith(game) },
            requestDescriptionRestore = { library.requestDetailDescriptionRestore() },
        )
        authority.restoreMimirLaunchReturnScope(target, detailSession)
        val targetKey = if (detailSession != null) LibraryDetailPlayKey else libraryLaunchReturnFocusKey(target)
        if (targetKey != null) {
            repeat(MimirLibraryLaunchReturnFocusAttempts) {
                withFrameNanos { }
                authority.restoreMimirLaunchReturnScope(target, detailSession)
                authority.requestFocus(targetKey)
                if (authority.state.value.focused == targetKey) {
                    // Focus landing is not the surface being on screen: the focused card still has to compose and draw. The
                    // cover keeps its hold for those frames, and only then asks to lift.
                    awaitLibraryLaunchReturnSurfaceDrawn()
                    navigation.libraryLaunchReturnArmed = false
                    viewModel.clearPendingLaunchReturnTarget()
                    navigation.libraryLaunchReturnRestoring = false
                    navigation.libraryLaunchReturnCoverClock.reset()
                    return@LaunchedEffect
                }
            }
        } else {
            withFrameNanos { }
        }
        awaitLibraryLaunchReturnSurfaceDrawn()
        navigation.libraryLaunchReturnArmed = false
        viewModel.clearPendingLaunchReturnTarget()
        navigation.libraryLaunchReturnRestoring = false
        navigation.libraryLaunchReturnCoverClock.reset()
    }
}

/**
 * Frames the return cover keeps holding after focus has landed on the restored surface, so it lifts on a frame the surface has
 * DRAWN and not on the frame its state was written: on Thor the cover lifted when focus landed,
 * a frame before the card was on screen, and the restore showed through the gap.
 *
 * Compose resumes a coroutine from `withFrameNanos` at the start of a frame, before that frame composes. A write made in the
 * coroutine resumed at frame N is therefore composed and drawn in frame N+1, and the continuation after the SECOND wait
 * resumes at N+2 with N+1's pixels recorded. Two frames is the least that is true; the cover's own one-frame exit beat
 * (ReturnCoverSurface.kt) and fade come on top of it.
 */
internal const val LibraryLaunchReturnDrawnFrames = 2

internal suspend fun awaitLibraryLaunchReturnSurfaceDrawn() {
    repeat(LibraryLaunchReturnDrawnFrames) { withFrameNanos { } }
}

internal fun libraryLaunchReturnHasViableAnchor(
    armed: Boolean,
    target: LibraryLaunchReturnTarget?,
): Boolean = armed && target != null

/**
 * A Library ROM launch publishes its return anchor before external dispatch so a genuinely fast emulator
 * return cannot be lost. That also means Android can briefly pause/resume Mimir while the launch intent is
 * still leaving; raw lifecycle truth alone must not consume the anchor and reopen the card at that point.
 * The exact semantic session already distinguishes an outbound hand-off from a completed return/failure.
 */
internal fun libraryLaunchReturnMayRestore(
    target: LibraryLaunchReturnTarget,
    gameSession: MimirGameSession?,
    /** The target was put back from saved instance state after the process was killed: this process never had its session, and the game is over. */
    restoredAfterKill: Boolean = false,
    /**
     * A HOME press is parking the game right now (`MainGameHomePark.pending()`). The HOME lease lands OFF the main thread, after the
     * resume this effect runs on (Thor combined44 check 6: `verdict=park` at .150, `armed` at .489), and nothing re-decided when it did:
     * the cover waited 162 s for a phase a park never leaves (the ledger was YIELDED 40 s before the press, rev 18). A park in flight
     * ends in a lease (restore, below) or, if the task is gone, in ENDED (restore, below), so the card restores now, whichever way it
     * lands, and whatever phase the ledger shows: a pending park is a park.
     */
    homeParkPending: Boolean = false,
): Boolean {
    if (restoredAfterKill) return true
    val gameId = target.gameId ?: return true
    val matchingSession = gameSession?.takeIf { session ->
        session.gameId == gameId &&
            (session.returnAnchor as? MimirGameReturnAnchor.Library)?.target == target
    } ?: return false
    // R6-P0-03: a parked Main game (YIELDED_TO_SECONDARY_TASK) is a legitimate return too — the lease
    // is armed before the Main-covering startActivity, so the same resume that would otherwise strand
    // the Return cover for the whole parking duration can instead restore the Library detail card.
    if (MainGameParkingState.currentFor(matchingSession) != null) return true
    if (homeParkPending) return true
    return matchingSession.phase == MimirSessionPhase.ENDED ||
        matchingSession.phase == MimirSessionPhase.FAILED ||
        matchingSession.phase == MimirSessionPhase.STALE
}

/**
 * Whether a launch return closes the card the Library has open. A target that asks for the card and cannot have it (the game is gone
 * from the list: [restoredSession] is null) closes it, as before. A target that does NOT ask for the card (a game launched from the
 * shelf or from the list) closes any card an earlier session left open, so the return lands on the shelf or the list it names with
 * the played game focused and the scene glass off, and not on a blurred background.
 */
internal fun libraryLaunchReturnClosesOpenCard(
    target: LibraryLaunchReturnTarget,
    restoredSession: LibraryDetailSession?,
    cardOpen: Boolean,
): Boolean = if (target.restoreLaunchCard) restoredSession == null else cardOpen

internal fun installLibraryLaunchReturnDetailSession(
    session: LibraryDetailSession?,
    openDetail: (LibraryDetailSession) -> Unit,
    refreshOpenWith: (GameRow) -> Unit,
    requestDescriptionRestore: () -> Unit,
): Boolean {
    val validated = session ?: return false
    openDetail(validated)
    (validated.target as? LibraryDetailTarget.Emulated)?.game?.let(refreshOpenWith)
    requestDescriptionRestore()
    return true
}

@Composable
private fun MimirGamelistCloseReturnEffect(
    authority: FocusAuthority,
    library: MimirLibraryCoordinator,
) {
    val previousOpenPlatform = remember { mutableStateOf(library.openPlatform) }
    LaunchedEffect(library.openPlatform) {
        val previous = previousOpenPlatform.value
        previousOpenPlatform.value = library.openPlatform
        if (previous == null || library.openPlatform != null) return@LaunchedEffect
        if (library.pendingLaunchReturnTarget != null) return@LaunchedEffect
        if (previous.value == "__favorites__") return@LaunchedEffect
        val targetKey = FocusKey("library/shelf/${previous.value}")
        repeat(MimirLibraryLaunchReturnFocusAttempts) {
            withFrameNanos { }
            if (authority.state.value.scope == LibraryShelfScope) {
                authority.requestFocus(targetKey)
                if (authority.state.value.focused == targetKey) return@LaunchedEffect
            }
        }
    }
}

@Composable
private fun MimirSettingsLibraryReturnEffect(
    authority: FocusAuthority,
    viewModel: LibraryViewModel,
    library: MimirLibraryCoordinator,
    nativeShelf: MimirNativeShelfCoordinator,
    navigation: MimirNavigationCoordinator,
) {
    LaunchedEffect(
        navigation.settingsLibraryReturnRequest,
        nativeShelf.loadState,
        library.platformsLoaded,
        library.platforms,
        library.gamesLoadedForPlatform,
        library.games,
        library.detailDescriptionScrollState,
        library.detailDescriptionScrollCommand,
    ) {
        val requestedTarget = navigation.settingsLibraryReturnRequest ?: return@LaunchedEffect
        if (requestedTarget.requiresNativeRows() && !nativeShelf.loadState.loaded) return@LaunchedEffect
        if (requestedTarget.shelfMode == LibraryShelfMode.EMULATION && !library.platformsLoaded) {
            return@LaunchedEffect
        }
        val target = availableStartupLibraryTarget(requestedTarget, library.platforms)
        navigation.destination = MimirDestination.LIBRARY
        viewModel.restoreOriginReturnTargetState(target)
        val openPlatform = target.openPlatformId
        if (openPlatform != null && library.gamesLoadedForPlatform != openPlatform) return@LaunchedEffect

        val capturedDetail = navigation.settingsLibraryDetailSession
        val requestedDetail = capturedDetail?.takeIf { session ->
            session.originScope == when (target.surface) {
                LibraryOriginReturnTarget.Surface.SHELF -> LibraryShelfScope
                LibraryOriginReturnTarget.Surface.GAMELIST -> GamelistScope
            }
        }
        val retainedDetail = requestedDetail?.let { session ->
            when (val detailTarget = session.target) {
                is com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget.Emulated -> {
                    library.games.firstOrNull { it.gameId == detailTarget.game.gameId }
                        ?.let { live -> session.copy(target = detailTarget.copy(game = live)) }
                }
                is com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget.AndroidGame -> {
                    nativeShelf.snapshot.games
                        .firstOrNull { it.packageName == detailTarget.row.packageName }
                        ?.let { live ->
                            session.copy(
                                target = com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget.AndroidGame(live),
                            )
                        }
                }
            }
        }
        if (capturedDetail != null && retainedDetail == null) {
            viewModel.closeGameDetail()
            library.closeDetail(capturedDetail.target)
        }
        val retainedRestoreCommand = retainedDetail?.let { session ->
            (session.target as? com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget.Emulated)
                ?.let { emulated ->
                    library.games.firstOrNull { it.gameId == emulated.game.gameId }
                        ?.let(viewModel::openGameDetail)
                }
            // Restore scroll for every retained detail return, even when focus belongs to Play or another
            // action. Only Description focus itself waits for the command's measured settlement below.
            installSettingsLibraryDetailSession(
                session = session,
                openDetail = library.openDetail,
                requestDescriptionRestore = library.requestDetailDescriptionRestore,
            )
        }
        // Deliberately NOT restoring the FocusAuthority scope here, before any withFrameNanos yield.
        // `navigation.destination = MimirDestination.LIBRARY` above is a state write Compose has not
        // processed yet at this point in the coroutine -- SettingsHome/SettingsDetailPane are still
        // mounted and still own the scope stack (SettingsRailScope/DetailScope). Popping/pushing toward
        // Library's scope here would race that still-live Settings scope instead of the about-to-mount
        // LibraryScreen's own scope -- and it is redundant: LibraryScreen's own mount effects
        // (`enterLibraryBaseScope`, `reconcileLibraryEntryScope`, both in LibraryScreen.kt/LibraryDispatch.kt)
        // already derive the identical target scope from the SAME `viewModel.restoreOriginReturnTargetState`
        // state this effect just wrote, once LibraryScreen actually mounts. The retry loop below is already
        // frame-gated (one `withFrameNanos {}` per attempt, before every scope/focus touch) and performs
        // the equivalent scope-then-focus work safely, after Compose has had a chance to dispose Settings
        // and mount Library -- so removing this premature, un-gated duplicate is a pure race fix, not a
        // behavior change on the happy path.
        val requestedDetailFocus = navigation.settingsLibraryDetailFocusKey?.takeIf { key ->
            when (key) {
                LibraryDetailPlayKey, LibraryDetailDescriptionKey -> true
                LibraryDetailRestartKey, LibraryDetailOpenWithKey, LibraryDetailAchievementsKey ->
                    retainedDetail?.target is com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget.Emulated
                else -> false
            }
        }
        val landingKey = if (retainedDetail != null) {
            if (requestedDetailFocus == LibraryDetailDescriptionKey) {
                val command = retainedRestoreCommand
                val scroll = library.detailDescriptionScrollState
                if (command == null || scroll.ownerToken != command.ownerToken) {
                    LibraryDetailPlayKey
                } else {
                    if (!scroll.isSettledFor(command)) return@LaunchedEffect
                    if (scroll.scrollable) LibraryDetailDescriptionKey else LibraryDetailPlayKey
                }
            } else {
                requestedDetailFocus ?: LibraryDetailPlayKey
            }
        } else {
            selectStartupLibraryFocusTarget(
                target = target,
                platforms = library.platforms,
                games = library.games,
                nativeRows = nativeShelf.rowsForMode(target.shelfMode),
            )
        }
        if (landingKey == null) {
            withFrameNanos { }
            navigation.finishSettingsLibraryReturn()
            return@LaunchedEffect
        }
        // The key the loop asks for. Restart exists only while its game is parked behind Home: for a game that is not, the card lands on Play at once; for one that is, Restart
        // has the whole allowance below to register.
        val targetKey: FocusKey = if (
            landingKey == LibraryDetailRestartKey &&
            HomeLaunchCardResume.parkedGameId() !=
            (retainedDetail?.target as? com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget.Emulated)?.game?.gameId
        ) {
            LibraryDetailPlayKey
        } else {
            landingKey
        }
        // Registration can legitimately lag database/native hydration, but an item removed while Settings
        // owned the screen must not leave a per-frame restore coroutine alive forever. Use the same bounded
        // registration allowance as the other Library-origin returns, then unwind any retained detail whose
        // action target never became focusable.
        repeat(MimirLibraryOriginReturnFocusAttempts) {
            if (navigation.settingsLibraryReturnRequest != requestedTarget ||
                navigation.destination != MimirDestination.LIBRARY
            ) return@LaunchedEffect
            withFrameNanos { }
            authority.restoreMimirOriginReturnScope(target)
            retainedDetail?.let { restoreLibraryDetailSession(authority, it) }
            // Restart exists only while its game is parked behind Home. If it is not there after a few frames (the game came back while Settings had the screen), the card
            // lands on Play rather than spending the whole allowance on a key that is not coming.
            authority.requestFocus(targetKey)
            if (authority.state.value.focused == targetKey) {
                navigation.finishSettingsLibraryReturn()
                return@LaunchedEffect
            }
        }
        retainedDetail?.let { session ->
            if (authority.state.value.scope == GameDetailScope) {
                exitLibraryDetail(authority, session.target)
            }
            viewModel.closeGameDetail()
            library.closeDetail(session.target)
            authority.restoreMimirOriginReturnScope(target)
        }
        // BUG#01: a return can leave the active scope without a registered focus target. Every exit above except the `focused == targetKey` arm can reach here with the active
        // scope owning NOTHING: pushScope lands null focus when the ring has not registered yet
        // (DefaultFocusAuthority.kt:164-166), requestFocus is a SILENT no-op while its key is unregistered
        // (:113), and maybeSeedFocusOnRegister (:66-72) only ever seeds the scope's remembered key -- which
        // is targetKey, the very key that never registered. moveFocus then returns false on its first line
        // (:123), so the D-pad is inert on a screen that looks ready.
        //
        // Terminal, and placed deliberately: AFTER the retry loop and the detail unwind, so it can never
        // outrank a correct landing; and BEFORE the request clears, because clearing it is what drops the
        // rune cover (MimirAppBindings.kt:105 -> MimirAppHost.kt:2197) and hands the screen to the player.
        authority.landSettingsReturnFocusFallback()
        navigation.finishSettingsLibraryReturn()
    }
}

/**
 * Which key a Library-origin return falls back to when its exact target never became focusable.
 *
 * Pure, so the policy is unit-testable off-device, and deliberately conservative:
 *  - [focused] non-null means the return already landed -- returns null and changes nothing. A fallback
 *    able to override a correct landing would reintroduce the reported first-tile jump followed by cancellation, which is the same class of defect this lane just removed elsewhere.
 *  - the scope's [remembered] key wins when it is genuinely registered, so the player lands where they
 *    were rather than at the top.
 *  - otherwise the first registered key: an arbitrary REAL element beats a dead D-pad.
 *  - an empty scope yields null. There is nothing to focus, and pretending otherwise would be a lie.
 */
internal fun settingsReturnFallbackFocusKey(
    focused: FocusKey?,
    registeredInScope: List<FocusKey>,
    remembered: FocusKey?,
): FocusKey? {
    if (focused != null) return null
    if (remembered != null && remembered in registeredInScope) return remembered
    return registeredInScope.firstOrNull()
}

/**
 * Apply [settingsReturnFallbackFocusKey] to this authority's ACTIVE scope. No-op when focus already
 * landed. Adds no `core-input` primitive -- [FocusAuthority.registeredKeysInScope] and
 * [FocusAuthority.rememberedFocusIn] are existing interface members.
 */
internal fun FocusAuthority.landSettingsReturnFocusFallback() {
    val scope = state.value.scope
    val fallback = settingsReturnFallbackFocusKey(
        focused = state.value.focused,
        registeredInScope = registeredKeysInScope(scope),
        remembered = rememberedFocusIn(scope),
    ) ?: return
    requestFocus(fallback)
}

internal fun installSettingsLibraryDetailSession(
    session: LibraryDetailSession,
    openDetail: (LibraryDetailSession) -> Unit,
    requestDescriptionRestore: () -> com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand?,
): com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand? {
    openDetail(session)
    return requestDescriptionRestore()
}

private fun MimirNavigationCoordinator.finishSettingsLibraryReturn() {
    settingsLibraryReturnRequest = null
    settingsLibraryDetailSession = null
    settingsLibraryDetailFocusKey = null
}

@Composable
private fun MimirPrimaryOriginEffect(
    authority: FocusAuthority,
    library: MimirLibraryCoordinator,
    navigation: MimirNavigationCoordinator,
) {
    val focusState by authority.state.collectAsState()
    LaunchedEffect(
        navigation.startupOriginApplied,
        navigation.destination,
        library.openPlatform,
        library.detailGame,
        library.shelfMode,
        library.favoriteSegment,
        library.searchQuery,
        library.favoritesOnly,
        library.searchReturnSnapshot,
        library.gamelistViewMode,
        navigation.settingsLibraryOrigin,
        focusState,
    ) {
        if (!navigation.startupOriginApplied) return@LaunchedEffect
        val currentLibraryOrigin = when (navigation.destination) {
            MimirDestination.LIBRARY ->
                library.captureOriginReturnTarget(focusState.focused)
            MimirDestination.SETTINGS ->
                navigation.settingsLibraryOrigin ?: library.captureOriginReturnTarget(null)
            else ->
                library.captureOriginReturnTarget(null)
        }
        CompanionPrimaryOriginStore.update(
            PrimaryReturnOrigin(
                destination = navigation.destination.name,
                arguments = buildMap {
                    currentLibraryOrigin.writeTo(this, "library.")
                    library.detailGame?.gameId?.value?.let { put(MimirPrimaryOriginDetailGameArg, it) }
                    focusState.focused?.value?.let { put(MimirPrimaryOriginFocusKeyArg, it) }
                    put(MimirPrimaryOriginFocusScopeArg, focusState.scope.value)
                    navigation.settingsLibraryOrigin?.writeTo(this, MimirSettingsOriginPrefix)
                },
            ),
        )
    }
}

private fun FocusAuthority.restoreMimirLaunchReturnScope(
    target: LibraryLaunchReturnTarget,
    detailSession: LibraryDetailSession?,
) {
    if (detailSession != null && state.value.scope == GameDetailScope) {
        restoreLibraryDetailSession(this, detailSession)
        return
    }
    val targetScope = detailSession?.originScope ?: when (target.surface) {
        LibraryLaunchReturnTarget.Surface.SHELF -> LibraryShelfScope
        LibraryLaunchReturnTarget.Surface.GAMELIST -> GamelistScope
    }
    repeat(MimirLibraryLaunchReturnScopeAttempts) {
        val currentScope = state.value.scope
        if (currentScope != targetScope) {
            when {
                currentScope == FocusScope.Root -> pushScope(LibraryShelfScope)
                targetScope == GamelistScope && currentScope == LibraryShelfScope -> pushScope(GamelistScope)
                else -> popScope()
            }
        }
    }
    detailSession?.let { restoreLibraryDetailSession(this, it) }
}

private fun FocusAuthority.restoreMimirOriginReturnScope(target: LibraryOriginReturnTarget) {
    val targetScope = when (target.surface) {
        LibraryOriginReturnTarget.Surface.SHELF -> LibraryShelfScope
        LibraryOriginReturnTarget.Surface.GAMELIST -> GamelistScope
    }
    repeat(MimirLibraryLaunchReturnScopeAttempts) {
        val currentScope = state.value.scope
        if (currentScope == targetScope) return
        when {
            currentScope == FocusScope.Root -> pushScope(LibraryShelfScope)
            targetScope == GamelistScope && currentScope == LibraryShelfScope -> pushScope(GamelistScope)
            else -> popScope()
        }
    }
}

private fun LibraryOriginReturnTarget.requiresNativeRows(): Boolean = when {
    surface == LibraryOriginReturnTarget.Surface.SHELF -> shelfMode != LibraryShelfMode.EMULATION
    openPlatformId == LibraryViewModel.FAVORITES_PLATFORM_ID -> favoriteSegment != FavoriteSegment.GAMES
    else -> false
}

private const val MimirStartupOriginScopeAttempts = 12
private const val MimirStartupOriginFocusAttempts = 60
private const val MimirLibraryLaunchReturnFocusAttempts = 8
private const val MimirLibraryOriginReturnFocusAttempts = 8
private const val MimirLibraryLaunchReturnScopeAttempts = 6
private const val MimirPrimaryOriginFocusKeyArg = "focusKey"
private const val MimirPrimaryOriginFocusScopeArg = "focusScope"
private const val MimirPrimaryOriginDetailGameArg = "detailGameId"
private const val MimirSettingsOriginPrefix = "settingsOrigin."
