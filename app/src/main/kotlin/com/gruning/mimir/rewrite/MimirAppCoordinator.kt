package com.gruning.mimir.rewrite

import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.first
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import android.view.Display
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.launch.LaunchDispatchDestination
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import com.gruning.mimir.rewrite.feature.library.LaunchChoice
import com.gruning.mimir.rewrite.feature.library.PlatformTileArtStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.gruning.mimir.rewrite.core.audio.MimirSoundEvent
import com.gruning.mimir.rewrite.core.audio.MimirUiSounds
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.LaunchForegroundObserver
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.files.PermissionHelper
import com.gruning.mimir.rewrite.feature.browse.dispatchToBrowse
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.gameLaunchSessionHeldMessage
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.GameDetailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.homeShelfEntryForKey
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioActionRequest
import com.gruning.mimir.rewrite.feature.library.LibraryDetailActionState
import com.gruning.mimir.rewrite.feature.library.LibraryDetailPlayKey
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.dispatchToHomeShelf
import com.gruning.mimir.rewrite.feature.library.dispatchToLibrary
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import com.gruning.mimir.rewrite.feature.library.providerEnrichmentState
import com.gruning.mimir.rewrite.feature.settings.dispatchToSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job

internal fun companionQuickLaunchDispatchSucceeded(
    operationId: UUID?,
    launchState: CompanionDrawerLaunchState,
): Boolean = operationId != null &&
    (launchState as? CompanionDrawerLaunchState.LaunchPending)?.operationId == operationId

internal fun homeCustomEnrichStageFailureMessage(outcome: CustomHeroStageOutcome?): String = when (outcome) {
    CustomHeroStageOutcome.DOWNLOAD_FAILED ->
        "Download failed. Check the connection and try again."
    CustomHeroStageOutcome.UNSUPPORTED_MEDIA ->
        "Unsupported media format. Your current choice is unchanged."
    CustomHeroStageOutcome.BELOW_QUALITY_FLOOR ->
        "This media is below Mimir’s release quality floor. Your current choice is unchanged."
    CustomHeroStageOutcome.READY -> ""
    null -> "Couldn’t download or validate this media. Your current choice is unchanged."
}

internal data class HomeCustomEnrichHeroProviderRoute(
    val steamGridDb: Boolean,
    val screenScraper: Boolean,
)

/**
 * Android package rows have a truthful title but no emulated [GameRow]. They can still search SteamGridDB;
 * only ScreenScraper's platform-verified route requires an emulated game identity.
 *
 * pc_games is the same shape of exclusion for a different reason (PC provider routing): the game IS emulated, but ScreenScraper matches ROM files
 * by hash/name and structurally cannot identify a GameNative shortcut — SteamGridDB owns this platform
 * outright, so ScreenScraper is never eligible for it regardless of the source filter.
 */
internal fun homeCustomEnrichHeroProviderRoute(
    source: String,
    hasEmulatedGame: Boolean,
    platformId: PlatformId? = null,
): HomeCustomEnrichHeroProviderRoute {
    val screenScraperEligible = hasEmulatedGame && platformId != PlatformId.PC_GAMES
    return HomeCustomEnrichHeroProviderRoute(
        steamGridDb = source != "ScreenScraper",
        screenScraper = source != "SteamGridDB" && screenScraperEligible,
    )
}

/**
 * CLIP's counterpart to [homeCustomEnrichHeroProviderRoute]. HERO expresses ScreenScraper's eligibility as
 * a route and so never confused "not connected" with "cannot ever answer". CLIP decided the same thing
 * inline and did confuse them — the defect this function exists to keep from recurring.
 *
 * pc_games is **structurally** outside ScreenScraper (R-24 — it
 * matches ROM files by hash/name and a GameNative shortcut is not one), so it yields neither an attempt
 * nor [needsAccess]. `needsAccess` outranks the honest empty state in
 * [homeCustomEnrichProviderSearchStatus], so raising it here told an owner with BOTH providers connected
 * to go connect them.
 */
internal data class HomeCustomEnrichClipScreenScraperUse(
    /** Issue the ScreenScraper request. */
    val attempt: Boolean,
    /** ScreenScraper could have answered this platform, but is not connected or has no game identity. */
    val needsAccess: Boolean,
)

internal fun homeCustomEnrichClipScreenScraperUse(
    source: String,
    format: String,
    page: Int,
    hasScreenScraper: Boolean,
    hasEmulatedGame: Boolean,
    platformId: PlatformId?,
): HomeCustomEnrichClipScreenScraperUse {
    val eligible = homeCustomEnrichClipUsesScreenScraper(source, format, page) &&
        platformId != PlatformId.PC_GAMES
    if (!eligible) {
        return HomeCustomEnrichClipScreenScraperUse(attempt = false, needsAccess = false)
    }
    val reachable = hasScreenScraper && hasEmulatedGame
    return HomeCustomEnrichClipScreenScraperUse(attempt = reachable, needsAccess = !reachable)
}

/**
 * CLIP's settlement, including the one status HERO also reports by hand: a PC game whose Source the player
 * has deliberately pointed at ScreenScraper is told why that provider cannot answer, rather than being
 * shown a needs-access or "no matches" sentence that misdescribes it.
 */
internal fun homeCustomEnrichClipSearchStatus(
    resultCount: Int,
    attempted: Boolean,
    needsAccess: Boolean,
    unreachable: Boolean,
    providerFailed: Boolean,
    source: String,
    platformId: PlatformId?,
): HomeCustomEnrichSearchStatus =
    if (source == "ScreenScraper" && platformId == PlatformId.PC_GAMES) {
        HomeCustomEnrichSearchStatus.Failed(
            "ScreenScraper can’t identify PC games. Use SteamGridDB instead.",
        )
    } else {
        homeCustomEnrichProviderSearchStatus(
            resultCount = resultCount,
            attempted = attempted,
            needsAccess = needsAccess,
            unreachable = unreachable,
            providerFailed = providerFailed,
            needsAccessMessage = "Connect ScreenScraper or add a SteamGridDB key in Settings.",
            notAttemptedMessage = "Connect ScreenScraper or add a SteamGridDB key in Settings.",
            unreachableMessage = "Couldn’t reach media providers. Saved motion is still available.",
            providerFailedMessage =
                "Media providers couldn’t complete this request. Saved motion is still available.",
        )
    }

internal data class HomeCustomEnrichExpandedPreviewRequest(
    val token: String,
    val resultId: String,
    val target: File,
)

internal sealed interface HomeCustomEnrichExpandedPreviewSettlement {
    data object Stale : HomeCustomEnrichExpandedPreviewSettlement
    data class Current(
        val outcome: CustomHeroStageOutcome,
        val localPath: String?,
    ) : HomeCustomEnrichExpandedPreviewSettlement
}

internal fun homeCustomEnrichExpandedPreviewDirectory(
    cacheDir: File,
    sessionId: String,
): File = File(
    cacheDir,
    "custom-enrich/expanded/${sessionId.hashCode().toUInt().toString(16)}",
)

internal fun homeCustomEnrichExpandedPreviewRequest(
    cacheDir: File,
    sessionId: String,
    generation: Int,
    resultId: String,
    nonce: ULong,
): HomeCustomEnrichExpandedPreviewRequest {
    val token = "$generation-${resultId.hashCode().toUInt().toString(16)}-${nonce.toString(16)}"
    return HomeCustomEnrichExpandedPreviewRequest(
        token = token,
        resultId = resultId,
        target = File(homeCustomEnrichExpandedPreviewDirectory(cacheDir, sessionId), "$token.media"),
    )
}

internal fun homeCustomEnrichPriorExpandedPreviewTarget(
    cacheDir: File,
    sessionId: String,
    localPath: String?,
): File? = localPath
    ?.takeIf(String::isNotBlank)
    ?.let(::File)
    ?.let { candidate ->
        runCatching {
            val directory = homeCustomEnrichExpandedPreviewDirectory(cacheDir, sessionId).canonicalFile
            candidate.canonicalFile.takeIf { it.parentFile == directory }
        }.getOrNull()
    }

internal suspend fun deleteHomeCustomEnrichPriorExpandedPreview(
    cacheDir: File,
    sessionId: String,
    localPath: String?,
): Boolean {
    if (localPath.isNullOrBlank()) return true
    val target = homeCustomEnrichPriorExpandedPreviewTarget(cacheDir, sessionId, localPath)
        ?: return false
    return withContext(Dispatchers.IO) { !target.exists() || target.delete() }
}

/**
 * Stages one immutable inspection asset. A superseded or cancelled request owns and removes only its
 * own file, so a late provider response cannot overwrite or resurrect the currently selected preview.
 */
internal suspend fun stageHomeCustomEnrichExpandedPreview(
    request: HomeCustomEnrichExpandedPreviewRequest,
    isCurrent: (String) -> Boolean,
    stage: suspend (File) -> CustomHeroStageOutcome,
): HomeCustomEnrichExpandedPreviewSettlement {
    var retainTarget = false
    return try {
        val outcome = stage(request.target)
        if (!isCurrent(request.token)) {
            HomeCustomEnrichExpandedPreviewSettlement.Stale
        } else {
            retainTarget = outcome == CustomHeroStageOutcome.READY
            HomeCustomEnrichExpandedPreviewSettlement.Current(
                outcome = outcome,
                localPath = request.target.absolutePath.takeIf { retainTarget },
            )
        }
    } finally {
        if (!retainTarget) {
            withContext(NonCancellable + Dispatchers.IO) { request.target.delete() }
        }
    }
}

/**
 * Can a collection-mode switch SUCCEED at all, whatever screen is asking?
 *
 * This is the CONTENT half of the rule, read through [homeViewModeSelectOffered] by both the Home
 * dispatch and the on-screen badge.
 * It restates `switchHomeViewMode`'s own refusals (`HomeShelfCoordinator.kt:1846` — an empty
 * `PINNED_TOMES` target is refused; the second leg is how an already-emptied pinned mode stays
 * escapable until the fallback at `:1933-1937` fires).
 *
 * **It is not a device state**, which is the whole point when reading the one-screen test: the state
 * where this is false is a state where the switch is INERT on both screens, not a state where a working
 * control has gone missing.
 */
internal fun homeViewModeSwitchPossible(
    viewModeToggleAvailable: Boolean,
    viewMode: HomeViewMode,
): Boolean = viewModeToggleAvailable || viewMode == HomeViewMode.PINNED_TOMES

/**
 * The Home root's L1/R1 collection-mode SELECT is offered — one rule, read from two different truths.
 *
 * **PR173 S2-01: the main Home shoulders remain the logical collection switch.** The secondary rail no
 * longer owns a collection seat, so the same discovered companion capability must not remove L1/R1 from
 * Home. A single-screen handheld and a dual-screen host therefore share the same content predicate.
 *
 * Pure and parameterised rather than reading `bindings`, because the dispatch reads INPUT truth
 * (`inputViewMode`, published before the tree invalidates) and the on-screen badge reads RENDER truth
 * (`viewMode`). Two truths, one rule: collapsing them into a single call would move the badge a frame
 * off the control it describes. The rule is shared; the truths stay separate on purpose.
 */
internal fun homeViewModeSelectOffered(
    @Suppress("UNUSED_PARAMETER") companionDisplayActive: Boolean,
    viewModeToggleAvailable: Boolean,
    viewMode: HomeViewMode,
): Boolean = homeViewModeSwitchPossible(viewModeToggleAvailable, viewMode)

/**
 * F9 (product review 2026-09-28): the one state where R1 is refused for want of content -- Editor's Choice with no
 * pinned Tome. [homeViewModeSwitchPossible] is false exactly there, and only R1 asks for Pinned Tomes.
 */
internal fun homePinnedTomesRefusalApplies(
    viewModeToggleAvailable: Boolean,
    viewMode: HomeViewMode,
): Boolean = !homeViewModeSwitchPossible(viewModeToggleAvailable, viewMode)

/**
 * F9: the Home root is ready for a shoulder press (the same gates as [homeViewModeSelectAvailable]) but the switch
 * is refused because nothing is pinned. The router keeps its shoulder arm engaged here so R1 can say why.
 */
private fun homePinnedTomesRefusalOffered(bindings: MimirAppBindings): Boolean {
    if (bindings.navigation.destination != MimirDestination.HOME || bindings.overlays.top != null) return false
    if (bindings.homeShelf.tomeSession != null) return false
    if (!bindings.homeShelf.rootProjectionSettled || !bindings.homeShelf.contentSettled) return false
    if (!bindings.homeShelf.canActivateHomeContent()) return false
    if (PrimaryActivityObservationState.stopped.value) return false
    return homePinnedTomesRefusalApplies(
        viewModeToggleAvailable = bindings.homeShelf.viewModeToggleAvailable,
        viewMode = bindings.homeShelf.inputViewMode,
    )
}

private fun homeViewModeSelectAvailable(bindings: MimirAppBindings): Boolean {
    if (bindings.navigation.destination != MimirDestination.HOME || bindings.overlays.top != null) return false
    if (bindings.homeShelf.tomeSession != null) return false
    if (!bindings.homeShelf.rootProjectionSettled || !bindings.homeShelf.contentSettled) return false
    if (!bindings.homeShelf.canActivateHomeContent()) return false
    if (PrimaryActivityObservationState.stopped.value) return false
    return homeViewModeSelectOffered(
        companionDisplayActive = bindings.core.companionDisplayActive,
        viewModeToggleAvailable = bindings.homeShelf.viewModeToggleAvailable,
        viewMode = bindings.homeShelf.inputViewMode,
    )
}

/**
 * Directional selection — L1 selects Editor's Choice, R1 selects Pinned Tomes, at the Home root only.
 * Selecting the active edge remains a no-op.
 */
private fun dispatchHomeViewModeSelect(bindings: MimirAppBindings, right: Boolean): Boolean {
    if (!homeViewModeSelectAvailable(bindings)) return false
    val target = if (right) HomeViewMode.PINNED_TOMES else HomeViewMode.EDITORS_CHOICE
    // An active-edge press is a true consumed no-op: do not even leave the Quick Rail scope.
    if (bindings.homeShelf.inputViewMode == target) return false
    if (bindings.core.focusAuthority.state.value.scope == HomeShelfQuickRailScope) {
        bindings.core.focusAuthority.popScope()
    }
    if (bindings.core.focusAuthority.state.value.scope != HomeShelfScope) return false
    return bindings.homeShelf.toggleViewMode()
}

private sealed interface HomeImageImportTarget {
    /** [keepsAspect]: the Customize media Cover art upload, staged whole at its own shape (stageCover) rather than square-cropped like an icon. */
    data class Icon(val cacheKey: String, val keepsAspect: Boolean = false) : HomeImageImportTarget
    data class Hero(val gameId: GameId) : HomeImageImportTarget
}

internal sealed interface CustomEnrichPickerOwner {
    data class Home(val itemId: HomeShelfItemId) : CustomEnrichPickerOwner
    data class Platform(val platformId: PlatformId) : CustomEnrichPickerOwner
}

/**
 * What one media apply has to invalidate. Pure, so the wiring below is pinned rather than only compiled --
 * `refreshCustomEnrichOwner` lives inside a composable and its effects are unobservable from a unit test
 * (the same reason `MimirArtworkCoordinator` takes `onArtInvalidated` as a parameter).
 *
 * @property libraryGameId the game whose library projection must be re-read, or null when this owner has no
 *   row in the library at all. An Android package is the null case: it is not a game id and never was.
 */
internal data class CustomEnrichRefreshPlan(
    val refreshHomeTiles: Boolean,
    val libraryGameId: GameId?,
    val platformId: PlatformId?,
)

/**
 *
 *
 * [CustomEnrichRefreshPlan.libraryGameId] is the half that was missing. Home tiles were refreshed and
 * platform art was republished, but nothing ever asked the library repository to re-project -- so
 * `GameRow.wiredFace`, which `toGameRow` reads from the filesystem, kept its stale value until leaving the
 * gamelist re-subscribed `observeGames` and re-queried Room cold.
 *
 * The LAUNCH_CARD half is not in this plan because it is not addressable by game id: those files live under
 * `launch-card#<cacheKey>` and no repository field carries them. That state is invalidated by the epoch the
 * caller bumps unconditionally.
 */
internal fun customEnrichRefreshPlan(owner: CustomEnrichPickerOwner): CustomEnrichRefreshPlan =
    when (owner) {
        is CustomEnrichPickerOwner.Home -> CustomEnrichRefreshPlan(
            refreshHomeTiles = true,
            libraryGameId = (owner.itemId as? HomeShelfItemId.Emulated)?.gameId,
            platformId = null,
        )
        is CustomEnrichPickerOwner.Platform -> CustomEnrichRefreshPlan(
            refreshHomeTiles = false,
            libraryGameId = null,
            platformId = owner.platformId,
        )
    }

private data class HomeImageImportRequest(
    val owner: CustomEnrichPickerOwner,
    val target: HomeImageImportTarget,
    val returnPhase: HomeTileMenuPhase,
)

@Composable
internal fun MimirAppCoordinator(
    inputRouter: InputRouter,
    activityStateRestored: Boolean,
    controllerNavigationActive: StateFlow<Boolean>,
    interactionMotionGate: InteractionMotionGate,
    companionOverlayController: CompanionOverlayController?,
    companionDisplayActive: StateFlow<Boolean>,
    launchForegroundObserver: LaunchForegroundObserver?,
    brightnessController: BrightnessController?,
) {
    val core = rememberMimirAppCoreCoordinator(
        controllerNavigationActive = controllerNavigationActive,
        interactionMotionGate = interactionMotionGate,
        companionOverlayController = companionOverlayController,
        companionDisplayActive = companionDisplayActive,
        launchForegroundObserver = launchForegroundObserver,
        brightnessController = brightnessController,
    )
    MimirColdStartTrace.markOnce("span core coordinator done")
    val library = rememberMimirLibraryCoordinator(core)
    MimirColdStartTrace.markOnce("span library coordinator done")
    val navigation = rememberMimirNavigationCoordinator(core, activityStateRestored)
    MimirColdStartTrace.markOnce("span navigation coordinator done")
    val mButtonNavigationRequest by MButtonNavigationCommands.pending.collectAsState()
    val nativeShelf = rememberMimirNativeShelfCoordinator(core, library, navigation)
    MimirColdStartTrace.markOnce("span native shelf coordinator done")
    // Artwork before the Settings read model (2026-09-30): the Music pane's "Download game music" row reads the
    // batch state that lives on MimirArtworkState, and that state is remembered in this coordinator. Both depend
    // on `core` alone, so the swap moves nothing but the order the two are remembered in.
    val artwork = rememberMimirArtworkCoordinator(core)
    MimirColdStartTrace.markOnce("span artwork coordinator done")
    val settingsRead = rememberMimirSettingsReadCoordinator(core, library, artwork)
    MimirColdStartTrace.markOnce("span settings read coordinator done")
    val achievements = rememberMimirAchievementsCoordinator(core)
    MimirColdStartTrace.markOnce("span achievements coordinator done")
    val onboarding = rememberMimirOnboardingCoordinator(core, navigation, artwork)
    MimirColdStartTrace.markOnce("span onboarding coordinator done")
    val migration = rememberMimirMigrationCoordinator(core)
    MimirColdStartTrace.markOnce("span migration coordinator done")
    val settings = rememberMimirSettingsCoordinator(
        core = core,
        library = library,
        navigation = navigation,
        read = settingsRead,
        artwork = artwork,
        achievements = achievements,
        migration = migration,
    )
    MimirColdStartTrace.markOnce("span settings coordinator done")
    val homeShelf = rememberHomeShelfCoordinator(
        context = core.context,
        persistedLaunchReturn = core.persistedHomeLaunchReturn,
        persistedLaunchReturnReady = core.persistedHomeLaunchReturnReady,
        focusAuthority = core.focusAuthority,
        nativeGameRows = nativeShelf.snapshot.games,
        launchController = core.launchController,
        destination = navigation.destination,
        onNavigate = { navigation.destination = it },
        nativeRowsLoaded = nativeShelf.loadState.loaded,
    )
    MimirColdStartTrace.markOnce("span home coordinator done")
    // CP02 : the Folio-return
    // teardown's wait lives here, deliberately OUTSIDE rememberHomeShelfCoordinator -- that composable is
    // ~2300 lines of inlined remember/derivedStateOf bodies and gaining a navigation-coordinator
    // PARAMETER there VerifyError'd the app on every cold start
    // . One call line here,
    // same idiom as MimirAppLifecycleEffect/MimirCompanionEffects below -- see the function's own kdoc.
    HomeTomeLibraryReturnReleaseEffect(homeShelf, navigation)
    // Motion batch D's deferred prewarm, beside the coordinator and never inside it (the verifier size edge above).
    HomeSurfacePrewarmEffect(homeShelf, core.focusAuthority)
    val homeLaunchCard = rememberHomeLaunchCardCoordinator(
        authority = core.focusAuthority,
        launchController = core.launchController,
        repository = core.repository,
    )
    // A card is tied to one exact visible Home identity. If a remove/edit mutation or context truth
    // makes that row disappear, dismiss the transient surface instead of leaving stale launch actions.
    // The card asks; the app layer launches — this is where the return-target arming and the Tweaker
    // pre/post hooks live, and a disc launch must not skip them.
    LaunchedEffect(homeLaunchCard, homeShelf) {
        homeLaunchCard.onLaunchChoiceCommitted = { choice ->
            val active = homeLaunchCard.session
            val game = active?.game
            if (active != null && game != null) {
                homeShelf.launchFromLaunchCard(
                    active.row,
                    game,
                    { state -> homeLaunchCard.setLaunchState(state, game.gameId) },
                    homeLaunchCard::close,
                    choice,
                )
            }
        }
    }
    // A card held for Settings (START from it) comes back when B leaves Settings for Home, and is dropped when the player leaves some other way. Not keyed on the held flag:
    // resuming flips it, and the effect must not cancel itself on that.
    LaunchedEffect(navigation.destination, navigation.homeLaunchCardReturnRequest) {
        when (homeLaunchCard.settleForDestination(navigation.destination, navigation.homeLaunchCardReturnRequest)) {
            HomeCardNavigationOutcome.RESUMED, HomeCardNavigationOutcome.DROPPED, HomeCardNavigationOutcome.CLOSED -> {
                navigation.settingsFromHomeLaunchCard = false
                navigation.homeLaunchCardReturnRequest = false
            }
            HomeCardNavigationOutcome.STILL_HELD, HomeCardNavigationOutcome.UNCHANGED -> Unit
        }
    }
    LaunchedEffect(homeLaunchCard.session?.row?.itemId, homeShelf.rows) {
        val active = homeLaunchCard.session ?: return@LaunchedEffect
        val itemId = active.row.itemId
        if (homeShelf.rows.none { it.itemId == itemId }) homeLaunchCard.close()
    }
    MimirColdStartTrace.markOnce("span launch card coordinator done")
    // The Library card's SESSION TIME is the elapsed store's total for its game , the same one Home's card reads, handed down on the card's detail state.
    val detailActionState = homeShelf.detailActionState(library.activeDetailTarget)?.let { state ->
        state.copy(externalSessionElapsedMillis = library.externalSessionElapsedTotals[state.itemId])
    }
    val companionRecents by CompanionRecentsState.ui.collectAsState()
    val returnCoverVisible = primaryReturnRestoring(
        native = nativeShelf.launchReturnRestoring,
        library = navigation.libraryLaunchReturnRestoring,
        home = homeShelf.launchReturnRestoring,
    )
    // FUX-01 §4.4 single-source rule: the dock's picker reads the SAME QuickLaunchStore slots and
    // catalog the companion footer uses; launch is topology-keyed ( Thor → screen 2 via the
    // companion transaction; single-screen → primary through the same store, never hidden).
    remember(core.context) {
        quickLaunchColdRead(
            load = { QuickLaunchStore.load(core.context) },
            read = { Unit },
        )
        true
    }
    val quickLaunchSlots by QuickLaunchStore.slots.collectAsState()
    val quickLaunchSequences by QuickLaunchStore.launchSequences.collectAsState()
    val quickLaunchApps by CompanionQuickLaunchCatalog.entries.collectAsState()
    val homeQuickLaunchSlotsUi = remember(quickLaunchSlots, quickLaunchApps) {
        val catalogByPackage = quickLaunchApps.associateBy { it.packageName }
        homeQuickLaunchSlots(
            slots = quickLaunchSlots,
            labelFor = { pkg -> catalogByPackage[pkg]?.label },
            iconFor = { pkg -> catalogByPackage[pkg]?.iconPath },
        )
    }
    val homeQuickLaunchScope = rememberCoroutineScope()
    // Bumped once per media apply, by [refreshCustomEnrichOwner] and nothing else. It exists because the
    // LAUNCH_CARD state is invisible to every other invalidation this app has: its files live under
    // `launch-card#<cacheKey>`, and `GameRow.wiredFace` is projected from the SHELF key alone, so a
    // launch-card pick can leave the repository row byte-identical. `games` is a StateFlow, which
    // CONFLATES an equal emission entirely -- so on a game with no automatic art the row never changes,
    // the flow never re-emits, and no `remember` downstream re-keys. The epoch is the one signal that
    // cannot be conflated away. Read by MimirAppHost's two launch-card face producers.
    var mediaApplyEpoch by remember { mutableStateOf(0L) }
    var homeCustomEnrichSearchJob by remember { mutableStateOf<Job?>(null) }
    // When a picker search last came back with NOTHING, or failed. Arms the retry floor. Long.MIN_VALUE
    // means it has not happened yet. Deliberately not a State: nothing renders from it, and making it one
    // would recompose this whole coordinator on every barren search.
    var homeCustomEnrichLastBarrenSearchAt by remember { mutableStateOf(Long.MIN_VALUE) }
    var homeCustomEnrichApplyJob by remember { mutableStateOf<Job?>(null) }
    var homeCustomEnrichExpandedPreviewJob by remember { mutableStateOf<Job?>(null) }
    var homeCustomEnrichExpandedPreviewToken by remember { mutableStateOf<String?>(null) }
    val homeCustomEnrichMigrations = remember {
        mutableMapOf<String, Deferred<EnrichLegacyMigrationOutcome>>()
    }

    fun refreshOnePlatformArt(platformId: PlatformId, contentChanged: Boolean = false) {
        val file = SteamGridDbTileFetcher.tileIconFile(
            core.context,
            SteamGridDbTileFetcher.platformKey(platformId),
        )
        PlatformTileArtStore.publish(
            platformIdValue = platformId.value,
            persistedUri = file.takeIf(File::isFile)
                ?.let { it.toURI().toString() + "?v=" + it.lastModified() },
            contentChanged = contentChanged,
        )
    }

    fun writeCustomEnrichPicker(owner: CustomEnrichPickerOwner, picker: HomeCustomEnrichState) {
        when (owner) {
            is CustomEnrichPickerOwner.Home -> navigation.homeTileMenu
                ?.takeIf { it.row.itemId == owner.itemId }
                ?.let { live ->
                    navigation.homeTileMenu = live.copy(
                        phase = HomeTileMenuPhase.CustomEnrichPicker(picker),
                    )
                }
            is CustomEnrichPickerOwner.Platform -> navigation.platformTileMenu
                ?.takeIf { it.platformId == owner.platformId }
                ?.let { live ->
                    navigation.platformTileMenu = live.copy(
                        phase = HomeTileMenuPhase.CustomEnrichPicker(picker),
                    )
                }
        }
    }

    /** The picker currently stored for [owner], read fresh (not the snapshot a reducer call started with). */
    fun currentLiveCustomEnrichPicker(owner: CustomEnrichPickerOwner): HomeCustomEnrichState? = when (owner) {
        is CustomEnrichPickerOwner.Home -> (navigation.homeTileMenu
            ?.takeIf { it.row.itemId == owner.itemId }
            ?.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker
        is CustomEnrichPickerOwner.Platform -> (navigation.platformTileMenu
            ?.takeIf { it.platformId == owner.platformId }
            ?.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker
    }

    /**
     * The one choke point every media apply funnels through -- ActivateSaved, Automatic, ApplyResult and
     * both image-import legs all land here after the file move has already committed.
     *
     * This function used to notify only two surfaces:
     *
     *  - the Home arm bumped [HomeShelfCoordinator]'s own `sgdbEpoch`, a `remember` inside that composable;
     *  - the Platform arm republished platform tile art.
     *
     * Neither touches [LibraryRepositoryImpl]'s `artVersion`. The Library gamelist paints
     * `GameRow.wiredFace`, which `toGameRow` reads from the filesystem through `CoverOverrideSource` -- and
     * that projection only re-runs when the Room flow or `artVersion` emits. Leaving the gamelist and coming
     * back worked because `observeGames` is COLD: re-subscribing re-queries Room and re-reads the store.
     *
     * Introduced by `1ab6b96a` (2026-08-25), which gave the pick its own tier and its own projected field.
     * It shipped stale on day one rather than breaking later -- the picker was born without a live seam to
     * the Library, and only the Home shelf ever had one.
     *
     * Two signals, because one cannot cover both states:
     *  - [refreshArt] for the SHELF face, which is what the gamelist tile and list preview paint. It also
     *    evicts that game's memoized ROM-sibling probes, which is right: he just changed this game's media.
     *  - [mediaApplyEpoch] for the LAUNCH_CARD face, which no repository field carries at all.
     */
    fun refreshCustomEnrichOwner(owner: CustomEnrichPickerOwner) {
        // Unconditional, and above the plan: the LAUNCH_CARD state has no game id and no repository field,
        // so this is the only signal that can reach it.
        mediaApplyEpoch += 1L
        val plan = customEnrichRefreshPlan(owner)
        if (plan.refreshHomeTiles) homeShelf.refreshTiles()
        plan.libraryGameId?.let { gameId ->
            // Fire-and-forget: the file move has already committed by the time any caller reaches here, so
            // there is nothing to order against. Matches every other async leg in this coordinator.
            homeQuickLaunchScope.launch { core.repository.refreshArt(gameId) }
        }
        plan.platformId?.let { platformId ->
            refreshOnePlatformArt(platformId = platformId, contentChanged = true)
        }
    }

    fun updateHomeCustomEnrich(
        sessionId: String,
        transform: (HomeCustomEnrichState) -> HomeCustomEnrichState,
    ) {
        navigation.homeTileMenu?.let { live ->
            val phase = live.phase as? HomeTileMenuPhase.CustomEnrichPicker
            if (phase?.picker?.sessionId == sessionId) {
                navigation.homeTileMenu = live.copy(phase = phase.copy(picker = transform(phase.picker)))
                return
            }
        }
        navigation.platformTileMenu?.let { live ->
            val phase = live.phase as? HomeTileMenuPhase.CustomEnrichPicker
            if (phase?.picker?.sessionId == sessionId) {
                navigation.platformTileMenu = live.copy(phase = phase.copy(picker = transform(phase.picker)))
            }
        }
    }

    val homeCustomEnrichSessionId =
        (navigation.homeTileMenu?.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker?.sessionId
            ?: (navigation.platformTileMenu?.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker?.sessionId
    LaunchedEffect(homeCustomEnrichSessionId) {
        homeCustomEnrichSearchJob?.cancel()
        homeCustomEnrichSearchJob = null
        homeCustomEnrichExpandedPreviewToken = null
        homeCustomEnrichExpandedPreviewJob?.cancel()
        homeCustomEnrichExpandedPreviewJob = null
        withContext(Dispatchers.IO) {
            File(core.context.cacheDir, "custom-enrich/expanded").deleteRecursively()
        }
    }
    DisposableEffect(core.context, homeCustomEnrichSessionId) {
        val owner = core.context as? LifecycleOwner
        val observer = homeCustomEnrichSessionId?.let { sessionId ->
            object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    val picker = (
                        (navigation.homeTileMenu?.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker
                            ?: (navigation.platformTileMenu?.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker
                        )?.takeIf { it.sessionId == sessionId }
                        ?: return
                    if (picker.searchStatus is HomeCustomEnrichSearchStatus.Searching) {
                        homeCustomEnrichSearchJob?.cancel()
                        updateHomeCustomEnrich(sessionId) { live ->
                            live.copy(
                                generation = live.generation + 1,
                                searchStatus = HomeCustomEnrichSearchStatus.Cancelled(live.results.size),
                            )
                        }
                    }
                }
            }
        }
        if (owner != null && observer != null) owner.lifecycle.addObserver(observer)
        onDispose { if (owner != null && observer != null) owner.lifecycle.removeObserver(observer) }
    }

    val platformCustomPicker =
        (navigation.platformTileMenu?.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker
    val platformCustomId = navigation.platformTileMenu?.platformId
    LaunchedEffect(platformCustomPicker?.sessionId, platformCustomPicker?.catalogReady, platformCustomId) {
        val picker = platformCustomPicker?.takeUnless { it.catalogReady } ?: return@LaunchedEffect
        val platformId = platformCustomId ?: return@LaunchedEffect
        val cacheKey = SteamGridDbTileFetcher.platformKey(platformId)
        val legacy = SteamGridDbTileFetcher.tileIconFile(core.context, cacheKey)
            .takeIf { it.isFile && it.length() > 0L }
        val retainedUpload = CustomImageImportStore.retainedIconFile(core.context, cacheKey)
            .takeIf { it.isFile && it.length() > 0L }
        val activeWasUpload = SteamGridDbTileFetcher.uploadedPickActive(core.context, cacheKey)
        val migrationKey = "${EnrichMediaKind.ICON.wireName}/$cacheKey"
        val migration = homeCustomEnrichMigrations[migrationKey]
            ?: homeQuickLaunchScope.async {
                withContext(NonCancellable) {
                    EnrichSavedStore.migrateLegacy(
                        context = core.context,
                        kind = EnrichMediaKind.ICON,
                        cacheKey = cacheKey,
                        legacyActive = legacy,
                        retainedUpload = retainedUpload,
                        activeWasUpload = activeWasUpload,
                    )
                }
            }.also { homeCustomEnrichMigrations[migrationKey] = it }
        val outcome = try {
            migration.await()
        } catch (_: Exception) {
            EnrichLegacyMigrationOutcome.Failed(
                EnrichSavedStore.load(core.context, EnrichMediaKind.ICON, cacheKey),
            )
        } finally {
            if (homeCustomEnrichMigrations[migrationKey] === migration) {
                homeCustomEnrichMigrations.remove(migrationKey)
            }
        }
        updateHomeCustomEnrich(picker.sessionId) { live ->
            when (outcome) {
                is EnrichLegacyMigrationOutcome.Ready -> live.copy(
                    saved = outcome.catalog.orderedForOpen(),
                    activeAssetId = outcome.catalog.activeAssetId,
                    catalogReady = true,
                )
                is EnrichLegacyMigrationOutcome.Failed -> live.copy(
                    catalogReady = false,
                    note = "Saved choice couldn’t be prepared. Go back and try again.",
                )
            }
        }
    }

    val launchHomeCustomEnrichSearch: (
        HomeCustomEnrichState,
        com.gruning.mimir.rewrite.core.model.library.GameRow?,
        Int,
    ) -> Unit = enrichSearch@{ picker, game, requestedPage ->
        homeCustomEnrichSearchJob?.cancel()
        // THE RETRY FLOOR.
        //
        // There was no rate limit of any kind here -- grepped for debounce, backoff, retryAfter, any
        // minimum interval, and found none. Every submit fired a live provider transaction immediately.
        // On a platform the provider could not answer for that meant each press started a round-trip
        // guaranteed to come back empty, so holding the button spawned unbounded concurrent probes that
        // flickered between searching and no-results as they landed out of order. That is the probing bug.
        //
        // The floor applies only after a search that FOUND NOTHING or FAILED. A search that returned
        // results is not a retry, and paging through them must never be throttled.
        val nowMillis = System.nanoTime() / 1_000_000L
        if (!homeCustomEnrichRetryAllowed(
                nowMillis = nowMillis,
                lastEmptyOrFailedAt = homeCustomEnrichLastBarrenSearchAt,
                floorMillis = HomeCustomEnrichRetryFloorMs,
            )
        ) {
            return@enrichSearch
        }
        val sessionId = picker.sessionId
        val generation = picker.generation
        val query = picker.submittedQuery ?: picker.queryDraft.trim()
        // CP-02b: the query actually issued. Starts as [query] and only advances through this platform's
        // OWN seeded angle chain when a search comes back empty — never for a hand-typed query.
        var effectiveQuery = query
        // Saved media this session already owns, by provider reference. The provider query still runs;
        // this only lets a match the user has on disk paint without a second transfer.
        val savedPreviews = homeCustomEnrichSavedPreviewIndex(picker.saved)
        fun cachedPreviewFor(
            candidates: List<CustomHeroCandidate>,
        ): (CustomHeroCandidate) -> String? {
            val uniqueHashes = homeCustomEnrichUniqueProviderRefHashes(picker.kind, candidates)
            return { candidate ->
                homeCustomEnrichCachedPreviewPath(
                    savedPreviews = savedPreviews,
                    providerReference = homeCustomEnrichProviderReference(picker.kind, candidate),
                    uniqueProviderRefHashes = uniqueHashes,
                )
            }
        }
        homeCustomEnrichSearchJob = homeQuickLaunchScope.launch {
            /** Arms the retry floor when a search produced nothing to show. */
            fun armRetryFloorIfBarren(resultCount: Int, failed: Boolean) {
                if (resultCount == 0 || failed) {
                    homeCustomEnrichLastBarrenSearchAt = System.nanoTime() / 1_000_000L
                }
            }
            suspend fun append(
                result: HomeCustomEnrichResult,
                provider: String,
                uniqueProviderRefHashes: Set<String>? = null,
            ) {
                withContext(Dispatchers.Main.immediate) {
                    updateHomeCustomEnrich(sessionId) {
                        it.appendSearchResult(
                            expectedGeneration = generation,
                            result = result,
                            provider = provider,
                            uniqueProviderRefHashes = uniqueProviderRefHashes,
                        )
                    }
                }
            }

            suspend fun settle(
                hasMore: Boolean = false,
                status: (Int) -> HomeCustomEnrichSearchStatus,
            ) {
                withContext(Dispatchers.Main.immediate) {
                    updateHomeCustomEnrich(sessionId) { live ->
                        if (live.generation == generation &&
                            live.searchStatus is HomeCustomEnrichSearchStatus.Searching
                        ) {
                            val settled = status(live.results.size)
                            armRetryFloorIfBarren(
                                resultCount = live.results.size,
                                failed = settled !is HomeCustomEnrichSearchStatus.Complete,
                            )
                            live.copy(
                                page = requestedPage,
                                hasMore = hasMore && live.results.size < homeCustomEnrichResultCap(live.kind),
                                searchStatus = status(live.results.size),
                                // CP-02b: if the angle walk moved on, show the term that ACTUALLY ran.
                                // Leaving the seeded primary in the box while results came from a later
                                // spelling would be a lie on the glass.
                                queryDraft = effectiveQuery,
                                submittedQuery = effectiveQuery,
                            )
                        } else {
                            live
                        }
                    }
                }
            }

            when (picker.kind) {
                EnrichMediaKind.ICON -> if (picker.shelfIconArt) {
                    // The merged Icon art search : the Type filter says which providers are asked and
                    // what is kept. SteamGridDB answers squares and icons (and a PC game's portrait grids), ScreenScraper the
                    // box family and the article of an emulated game, first page only. Nothing is fetched on scan or launch;
                    // this runs only from Search or Retry, behind the user's key and connection as before.
                    val type = picker.filterValue(IconArtTypeFilterKey) ?: IconArtType.All
                    val pcGame = game?.platformId == PlatformId.PC_GAMES
                    val usesSteamGridDb = iconArtTypeUsesSteamGridDb(type, pcGame)
                    val usesScreenScraper = game != null && !pcGame && requestedPage == 0 &&
                        iconArtTypeUsesScreenScraper(type)
                    var attempted = false
                    var needsAccess = false
                    var unreachable = false
                    var failed = false
                    var providerHasMore = false
                    if (usesSteamGridDb) {
                        val steamGridDbKey = SteamGridDbKeyStore.resolvedKey(core.context)
                        when (
                            val lookup = when {
                                steamGridDbKey.isBlank() -> SteamGridDbTileFetcher.CandidateLookup.NeedsKey
                                !MimirSteamGridDbGate.isOpen -> SteamGridDbTileFetcher.CandidateLookup.NeedsAccess
                                else -> {
                                    attempted = true
                                    SteamGridDbTileFetcher.manualCandidatesFor(
                                        context = core.context,
                                        apiKey = steamGridDbKey,
                                        query = effectiveQuery,
                                        // Front on a PC game is its portrait grid, which the icon endpoint does not carry.
                                        kind = if (type == IconArtType.Front) "Grids" else "All",
                                        style = picker.effectiveFilterValue("style", hiddenDefault = "All"),
                                        size = picker.effectiveFilterValue("size", hiddenDefault = "Any"),
                                        generation = generation,
                                        page = requestedPage,
                                        pageSize = HomeCustomEnrichPageSize,
                                        includePortraitGrids = pcGame &&
                                            (type == IconArtType.All || type == IconArtType.Front),
                                        onCandidate = {
                                            if (iconArtTypeMatches(type, it.providerMediaType)) {
                                                append(HomeCustomEnrichResult.Icon(it), "SteamGridDB")
                                            }
                                        },
                                    )
                                }
                            }
                        ) {
                            SteamGridDbTileFetcher.CandidateLookup.NeedsKey,
                            SteamGridDbTileFetcher.CandidateLookup.NeedsAccess,
                            -> needsAccess = true
                            SteamGridDbTileFetcher.CandidateLookup.Busy -> unreachable = true
                            SteamGridDbTileFetcher.CandidateLookup.Failed -> failed = true
                            is SteamGridDbTileFetcher.CandidateLookup.Loaded -> providerHasMore = lookup.hasMore
                        }
                    }
                    if (usesScreenScraper && game != null) {
                        val connected = ConnectedArtStore.optedIn.value &&
                            ArtworkCredentialStore.hasHealthyCredentials(core.context)
                        if (!connected) {
                            needsAccess = true
                        } else {
                            attempted = true
                            val (useVerifiedIdentity, persistResolution) =
                                homeCustomEnrichScreenScraperIdentityUse(picker.gameTitle, query)
                            val covers = CustomHeroFetcher.screenScraperCoverArt(
                                game = game,
                                candidateNames = homeCustomEnrichScreenScraperCandidateNames(
                                    picker.gameTitle,
                                    query,
                                ),
                                useVerifiedIdentity = useVerifiedIdentity,
                                persistResolution = persistResolution,
                            )
                            // The Type is applied BEFORE the previews are fetched, so a 3D box does not cost the front's download.
                            val candidates = (covers as? CustomHeroProviderResult.Loaded)?.candidates.orEmpty()
                                .filter { iconArtTypeMatches(type, it.mediaSubtype) }
                            val uniqueHashes = homeCustomEnrichUniqueProviderRefHashes(
                                EnrichMediaKind.COVER,
                                candidates,
                            )
                            CustomHeroFetcher.withLocalPreviews(
                                context = core.context,
                                candidates = candidates,
                                previewNamespace = "manual-$generation-cover",
                                cachedPreviewFor = cachedPreviewFor(candidates),
                                onCandidateReady = { ready ->
                                    if (ready.pickerArtPath != null) {
                                        append(
                                            // asCoverCandidate, NOT asPhysicalIconCandidate: that one labels the support
                                            // vocabulary and captions every box type "Physical media".
                                            HomeCustomEnrichResult.Icon(ready.asCoverCandidate()),
                                            "ScreenScraper",
                                            uniqueHashes,
                                        )
                                    }
                                },
                            )
                            when (covers) {
                                CustomHeroProviderResult.NeedsAccess -> needsAccess = true
                                CustomHeroProviderResult.Unreachable -> unreachable = true
                                CustomHeroProviderResult.ProviderFailed -> failed = true
                                else -> Unit
                            }
                        }
                    }
                    val accessMessage = when {
                        usesSteamGridDb && usesScreenScraper ->
                            "Add a SteamGridDB key or connect ScreenScraper: Settings › Appearance › Enrichment & artwork."
                        usesSteamGridDb ->
                            "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork."
                        else ->
                            "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork."
                    }
                    settle(hasMore = providerHasMore) {
                        homeCustomEnrichProviderSearchStatus(
                            resultCount = it,
                            attempted = attempted,
                            // A provider that could not be asked only matters when the other one was not asked either.
                            needsAccess = needsAccess && !attempted,
                            unreachable = unreachable,
                            providerFailed = failed,
                            needsAccessMessage = accessMessage,
                            notAttemptedMessage = accessMessage,
                            unreachableMessage = "Couldn’t reach artwork services. Saved choices are still available.",
                            providerFailedMessage =
                                "Artwork services couldn’t complete this request. Saved choices are still available.",
                        )
                    }
                } else {
                    val kind = picker.filterValue("kind") ?: "All"
                    val steamGridDbKey = SteamGridDbKeyStore.resolvedKey(core.context)
                    var attempted = false
                    var needsAccess = false
                    var failed = false
                    var busy = false
                    var providerHasMore = false
                    var landed = 0

                    // CP-02b: walk this platform's seeded angle chain, but ONLY while a search returns no
                    // USABLE result. A working primary still costs exactly one request — extra calls happen
                    // only where the user would otherwise be staring at an empty picker.
                    //
                    // Termination does NOT rely on the angle table being well-formed. nextPlatformArtSearchAngle
                    // re-derives position by case-insensitive string lookup, and indexOfFirst returns the FIRST
                    // match, so a duplicate in a list would cycle forever — issuing a live provider request per
                    // cycle. MaxPlatformArtSearchAngles is the backstop that makes the bound a property of this
                    // loop rather than of a hand-maintained table (platform-name query gate).
                    //
                    // Refused for non-platform pickers, for queries not in the platform's own angle table, and
                    // while paging so "load more" stays on the angle that produced the page.
                    var angleAttempts = 0
                    while (true) {
                        val landedBefore = landed
                        when (
                            val lookup = when {
                                steamGridDbKey.isBlank() -> SteamGridDbTileFetcher.CandidateLookup.NeedsKey
                                !MimirSteamGridDbGate.isOpen -> SteamGridDbTileFetcher.CandidateLookup.NeedsAccess
                                else -> {
                                    attempted = true
                                    SteamGridDbTileFetcher.manualCandidatesFor(
                                        context = core.context,
                                        apiKey = steamGridDbKey,
                                        query = effectiveQuery,
                                        kind = kind,
                                        style = picker.effectiveFilterValue("style", hiddenDefault = "All"),
                                        size = picker.effectiveFilterValue("size", hiddenDefault = "Any"),
                                        generation = generation,
                                        page = requestedPage,
                                        pageSize = HomeCustomEnrichPageSize,
                                        onCandidate = {
                                            landed++
                                            append(HomeCustomEnrichResult.Icon(it), "SteamGridDB")
                                        },
                                    )
                                }
                            }
                        ) {
                            SteamGridDbTileFetcher.CandidateLookup.NeedsKey -> Unit
                            SteamGridDbTileFetcher.CandidateLookup.NeedsAccess -> needsAccess = true
                            SteamGridDbTileFetcher.CandidateLookup.Busy -> busy = true
                            SteamGridDbTileFetcher.CandidateLookup.Failed -> failed = true
                            is SteamGridDbTileFetcher.CandidateLookup.Loaded -> providerHasMore = lookup.hasMore
                        }

                        // Only an empty USABLE result walks on. `landed` counts candidates that survived
                        // download+decode (SteamGridDbTileFetcher.kt:177-182), so "provider had assets but every
                        // preview failed" also walks — bounded, but it is not the same as "this spelling found
                        // nothing", and the ceiling below is what keeps that honest. A key/access/busy/failure
                        // outcome breaks instead: retrying those burns the chain against a condition no spelling
                        // can fix, and would mask the real status from the user.
                        if (landed > landedBefore) break
                        if (needsAccess || busy || failed || !attempted) break
                        if (requestedPage != 0) break
                        angleAttempts++
                        if (angleAttempts >= SteamGridDbTileFetcher.MaxPlatformArtSearchAngles) break
                        val nextAngle = SteamGridDbTileFetcher
                            .nextPlatformArtSearchAngle(picker.cacheKey, effectiveQuery) ?: break
                        effectiveQuery = nextAngle
                    }

                    when {
                        needsAccess || !attempted -> settle {
                            HomeCustomEnrichSearchStatus.NeedsAccess(
                                "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork.",
                            )
                        }
                        busy -> settle(hasMore = providerHasMore) { count ->
                            if (count > 0) HomeCustomEnrichSearchStatus.Complete(count)
                            else HomeCustomEnrichSearchStatus.Busy(
                                "SteamGridDB is busy right now. Try again shortly.",
                            )
                        }
                        failed -> settle(hasMore = providerHasMore) { count ->
                            if (count > 0) HomeCustomEnrichSearchStatus.Complete(count)
                            else HomeCustomEnrichSearchStatus.Failed(
                                "Couldn’t reach artwork services. Saved choices are still available.",
                            )
                        }
                        else -> settle(hasMore = providerHasMore) { HomeCustomEnrichSearchStatus.Complete(it) }
                    }
                }
                EnrichMediaKind.TOME_PHYSICAL -> {
                    val connected = ConnectedArtStore.optedIn.value &&
                        ArtworkCredentialStore.hasHealthyCredentials(core.context)
                    if (game == null) {
                        settle {
                            HomeCustomEnrichSearchStatus.Failed(
                                "This game isn’t available right now. Saved cartridge art is still available.",
                            )
                        }
                    } else if (!connected) {
                        settle {
                            HomeCustomEnrichSearchStatus.NeedsAccess(
                                "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork.",
                            )
                        }
                    } else {
                        val (useVerifiedIdentity, persistResolution) =
                            homeCustomEnrichScreenScraperIdentityUse(picker.gameTitle, query)
                        val physical = CustomHeroFetcher.screenScraperPhysicalMedia(
                            game = game,
                            candidateNames = homeCustomEnrichScreenScraperCandidateNames(
                                picker.gameTitle,
                                query,
                            ),
                            useVerifiedIdentity = useVerifiedIdentity,
                            persistResolution = persistResolution,
                        )
                        val candidates = (physical as? CustomHeroProviderResult.Loaded)?.candidates.orEmpty()
                        val uniqueHashes = homeCustomEnrichUniqueProviderRefHashes(
                            EnrichMediaKind.TOME_PHYSICAL,
                            candidates,
                        )
                        CustomHeroFetcher.withLocalPreviews(
                            context = core.context,
                            candidates = candidates,
                            previewNamespace = "manual-$generation-tome-physical",
                            cachedPreviewFor = cachedPreviewFor(candidates),
                            onCandidateReady = { ready ->
                                if (ready.pickerArtPath != null) {
                                    append(
                                        HomeCustomEnrichResult.Icon(ready.asPhysicalIconCandidate()),
                                        "ScreenScraper",
                                        uniqueHashes,
                                    )
                                }
                            },
                        )
                        settle {
                            homeCustomEnrichProviderSearchStatus(
                                resultCount = it,
                                attempted = true,
                                needsAccess = physical is CustomHeroProviderResult.NeedsAccess,
                                unreachable = physical is CustomHeroProviderResult.Unreachable,
                                providerFailed = physical is CustomHeroProviderResult.ProviderFailed,
                                needsAccessMessage =
                                    "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork.",
                                notAttemptedMessage =
                                    "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork.",
                                unreachableMessage =
                                    "Couldn’t reach ScreenScraper. Saved cartridge art is still available.",
                                providerFailedMessage =
                                    "ScreenScraper couldn’t complete this request. Saved cartridge art is still available.",
                            )
                        }
                    }
                }
                EnrichMediaKind.COVER -> {
                    // Deliberately the same shape as the TOME_PHYSICAL branch above: same connection
                    // gate, same identity handling, same status mapping. The only differences are the
                    // accepted media types (the box family, R5-3) and the copy.
                    val connected = ConnectedArtStore.optedIn.value &&
                        ArtworkCredentialStore.hasHealthyCredentials(core.context)
                    // Policy 2026-08-26, corrected target 2026-08-27 and reported from the device:
                    // PC Customize Media must expose search for both shelf and launch-card states.
                    //
                    // Both face rows -- Shelves (now Icon art, for a PC game too) and Launch card -- open THIS branch, and it demanded
                    // ScreenScraper credentials and then searched ScreenScraper. ScreenScraper is a console
                    // database: a `.steam` shortcut has no entry there and never will, so the shelf was
                    // empty BY CONSTRUCTION rather than by data, for every PC title, forever. The gamelist's
                    // own Enrich chip has known this for a while -- `gamelistEnrichAllowed` routes pc_games
                    // to SteamGridDB -- so the product already disagreed with itself one surface apart.
                    //
                    // Both grid lanes, because the face picker is merged: portrait grids stand in for the
                    // box family, square for the physical article. Each candidate records which it IS from
                    // its own dimensions, so a square asset cannot be saved as a cover and painted portrait.
                    val pcFace = game != null && game.platformId == PlatformId.PC_GAMES
                    if (pcFace) {
                        val steamGridDbKey = SteamGridDbKeyStore.resolvedKey(core.context)
                        var attempted = false
                        var needsAccess = false
                        var failed = false
                        var busy = false
                        var providerHasMore = false
                        when (
                            val lookup = when {
                                steamGridDbKey.isBlank() -> SteamGridDbTileFetcher.CandidateLookup.NeedsKey
                                !MimirSteamGridDbGate.isOpen -> SteamGridDbTileFetcher.CandidateLookup.NeedsAccess
                                else -> {
                                    attempted = true
                                    SteamGridDbTileFetcher.manualCandidatesFor(
                                        context = core.context,
                                        apiKey = steamGridDbKey,
                                        query = effectiveQuery,
                                        kind = "Grids",
                                        style = picker.effectiveFilterValue("style", hiddenDefault = "All"),
                                        size = picker.effectiveFilterValue("size", hiddenDefault = "Any"),
                                        generation = generation,
                                        page = requestedPage,
                                        pageSize = HomeCustomEnrichPageSize,
                                        includePortraitGrids = true,
                                        onCandidate = {
                                            append(HomeCustomEnrichResult.Icon(it), "SteamGridDB")
                                        },
                                    )
                                }
                            }
                        ) {
                            SteamGridDbTileFetcher.CandidateLookup.NeedsKey -> Unit
                            SteamGridDbTileFetcher.CandidateLookup.NeedsAccess -> needsAccess = true
                            SteamGridDbTileFetcher.CandidateLookup.Busy -> busy = true
                            SteamGridDbTileFetcher.CandidateLookup.Failed -> failed = true
                            is SteamGridDbTileFetcher.CandidateLookup.Loaded ->
                                providerHasMore = lookup.hasMore
                        }
                        settle(hasMore = providerHasMore) {
                            homeCustomEnrichProviderSearchStatus(
                                resultCount = it,
                                attempted = attempted,
                                needsAccess = needsAccess,
                                unreachable = busy,
                                providerFailed = failed,
                                needsAccessMessage =
                                    "Connect SteamGridDB: Settings › Appearance › Enrichment & artwork.",
                                notAttemptedMessage =
                                    "Connect SteamGridDB: Settings › Appearance › Enrichment & artwork.",
                                unreachableMessage =
                                    "Couldn’t reach SteamGridDB. Saved covers are still available.",
                                providerFailedMessage =
                                    "SteamGridDB couldn’t complete this request. Saved covers are still available.",
                            )
                        }
                    } else if (game == null) {
                        settle {
                            HomeCustomEnrichSearchStatus.Failed(
                                "This game isn’t available right now. Saved covers are still available.",
                            )
                        }
                    } else if (!connected) {
                        settle {
                            HomeCustomEnrichSearchStatus.NeedsAccess(
                                "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork.",
                            )
                        }
                    } else {
                        val (useVerifiedIdentity, persistResolution) =
                            homeCustomEnrichScreenScraperIdentityUse(picker.gameTitle, query)
                        val covers = CustomHeroFetcher.screenScraperCoverArt(
                            game = game,
                            candidateNames = homeCustomEnrichScreenScraperCandidateNames(
                                picker.gameTitle,
                                query,
                            ),
                            useVerifiedIdentity = useVerifiedIdentity,
                            persistResolution = persistResolution,
                        )
                        val candidates = (covers as? CustomHeroProviderResult.Loaded)?.candidates.orEmpty()
                        val uniqueHashes = homeCustomEnrichUniqueProviderRefHashes(
                            EnrichMediaKind.COVER,
                            candidates,
                        )
                        CustomHeroFetcher.withLocalPreviews(
                            context = core.context,
                            candidates = candidates,
                            previewNamespace = "manual-$generation-cover",
                            cachedPreviewFor = cachedPreviewFor(candidates),
                            onCandidateReady = { ready ->
                                if (ready.pickerArtPath != null) {
                                    append(
                                        // asCoverCandidate, NOT asPhysicalIconCandidate: that one labels
                                        // the support vocabulary and captions every box type "Physical
                                        // media". Seen on device before this was fixed.
                                        HomeCustomEnrichResult.Icon(ready.asCoverCandidate()),
                                        "ScreenScraper",
                                        uniqueHashes,
                                    )
                                }
                            },
                        )
                        settle {
                            homeCustomEnrichProviderSearchStatus(
                                resultCount = it,
                                attempted = true,
                                needsAccess = covers is CustomHeroProviderResult.NeedsAccess,
                                unreachable = covers is CustomHeroProviderResult.Unreachable,
                                providerFailed = covers is CustomHeroProviderResult.ProviderFailed,
                                needsAccessMessage =
                                    "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork.",
                                notAttemptedMessage =
                                    "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork.",
                                unreachableMessage =
                                    "Couldn’t reach ScreenScraper. Saved covers are still available.",
                                providerFailedMessage =
                                    "ScreenScraper couldn’t complete this request. Saved covers are still available.",
                            )
                        }
                    }
                }
                EnrichMediaKind.HERO -> {
                    val source = picker.filterValue("source") ?: "Both"
                    val size = picker.filterValue("size") ?: "Any"
                    val fit = picker.filterValue("fit") ?: "Mimir"
                    val route = homeCustomEnrichHeroProviderRoute(
                        source = source,
                        hasEmulatedGame = game != null,
                        platformId = game?.platformId,
                    )
                    val steamGridDbKey = SteamGridDbKeyStore.resolvedKey(core.context)
                    val hasScreenScraper = ConnectedArtStore.optedIn.value &&
                        ArtworkCredentialStore.hasHealthyCredentials(core.context)
                    var attempted = false
                    var needsAccess = false
                    var steamGridDbNeedsAccess = false
                    var screenScraperNeedsAccess = false
                    var unreachable = false
                    var providerFailed = false
                    var providerHasMore = false
                    val perProviderBudget = homeCustomEnrichHeroProviderBudget(source)
                    if (route.steamGridDb) {
                        if (steamGridDbKey.isBlank() || !MimirSteamGridDbGate.isOpen) {
                            needsAccess = true
                            steamGridDbNeedsAccess = true
                        } else {
                            attempted = true
                            // a PC game looks itself up by its Steam app id while the
                            // query box is untouched; the moment he types, the typed text wins. R-23
                            // declined title matching for `.steam` because sequels and editions are
                            // exactly where it goes wrong, and the file already carries the exact key.
                            // An unresolvable id falls back to the title search silently — no new copy.
                            val exactAppId = if (
                                homeCustomEnrichPcGameUsesExactAppId(
                                    game?.platformId,
                                    picker.gameTitle,
                                    query,
                                    requestedPage,
                                )
                            ) {
                                pcGameSteamAppId(core.context, requireNotNull(game))
                            } else {
                                null
                            }
                            val result = if (exactAppId != null) {
                                CustomHeroFetcher.steamGridDbHeroesForSteamAppId(
                                    steamGridDbKey,
                                    exactAppId,
                                    size = size,
                                    fit = fit,
                                )
                            } else {
                                CustomHeroFetcher.manualSteamGridDbHeroes(
                                    steamGridDbKey,
                                    query,
                                    size,
                                    fit,
                                    page = requestedPage,
                                    pageSize = perProviderBudget,
                                )
                            }
                            if (result is CustomHeroProviderResult.NeedsAccess) {
                                needsAccess = true
                                steamGridDbNeedsAccess = true
                            }
                            val loaded = result as? CustomHeroProviderResult.Loaded
                            providerHasMore = loaded?.hasMore == true
                            val candidates = loaded?.candidates.orEmpty().take(perProviderBudget)
                            val uniqueHashes = homeCustomEnrichUniqueProviderRefHashes(
                                EnrichMediaKind.HERO,
                                candidates,
                            )
                            CustomHeroFetcher.withLocalPreviews(
                                core.context,
                                candidates,
                                previewNamespace = homeCustomEnrichHeroPreviewNamespace(
                                    generation,
                                    EnrichMediaKind.HERO,
                                    "SteamGridDB",
                                    requestedPage,
                                ),
                                cachedPreviewFor = cachedPreviewFor(candidates),
                                onCandidateReady = { ready ->
                                    if (customEnrichHeroMatchesFilters(ready, size, fit)) {
                                        append(
                                            HomeCustomEnrichResult.Hero(ready),
                                            "SteamGridDB",
                                            uniqueHashes,
                                        )
                                    }
                                },
                            )
                            when (result) {
                                CustomHeroProviderResult.Unreachable -> unreachable = true
                                CustomHeroProviderResult.ProviderFailed -> providerFailed = true
                                else -> Unit
                            }
                        }
                    }
                    if (requestedPage == 0 && route.screenScraper) {
                        if (!hasScreenScraper) {
                            needsAccess = true
                            screenScraperNeedsAccess = true
                        } else {
                            attempted = true
                            val (useVerifiedIdentity, persistResolution) =
                                homeCustomEnrichScreenScraperIdentityUse(picker.gameTitle, query)
                            val result = CustomHeroFetcher.screenScraperHeroes(
                                game = requireNotNull(game),
                                videoAllowed = false,
                                candidateNames = homeCustomEnrichScreenScraperCandidateNames(
                                    picker.gameTitle,
                                    query,
                                ),
                                useVerifiedIdentity = useVerifiedIdentity,
                                persistResolution = persistResolution,
                            )
                            val candidates = (result as? CustomHeroProviderResult.Loaded)?.candidates.orEmpty()
                                .take(perProviderBudget * 2)
                            val uniqueHashes = homeCustomEnrichUniqueProviderRefHashes(
                                EnrichMediaKind.HERO,
                                candidates,
                            )
                            var screenScraperAppended = 0
                            CustomHeroFetcher.withLocalPreviews(
                                core.context,
                                candidates,
                                previewNamespace = homeCustomEnrichHeroPreviewNamespace(
                                    generation,
                                    EnrichMediaKind.HERO,
                                    "ScreenScraper",
                                    requestedPage,
                                ),
                                cachedPreviewFor = cachedPreviewFor(candidates),
                                onCandidateReady = { ready ->
                                    if (screenScraperAppended < perProviderBudget &&
                                        customEnrichHeroMatchesFilters(ready, size, fit)
                                    ) {
                                        append(
                                            HomeCustomEnrichResult.Hero(ready),
                                            "ScreenScraper",
                                            uniqueHashes,
                                        )
                                        screenScraperAppended++
                                    }
                                },
                            )
                            when (result) {
                                CustomHeroProviderResult.NeedsAccess -> {
                                    needsAccess = true
                                    screenScraperNeedsAccess = true
                                }
                                CustomHeroProviderResult.Unreachable -> unreachable = true
                                CustomHeroProviderResult.ProviderFailed -> providerFailed = true
                                else -> Unit
                            }
                        }
                    }
                    settle(hasMore = providerHasMore) { count ->
                        if (source == "ScreenScraper" && game == null) {
                            HomeCustomEnrichSearchStatus.Failed(
                                "ScreenScraper needs a console game identity. Use SteamGridDB for Android Hero Art.",
                            )
                        } else if (source == "ScreenScraper" && game?.platformId == PlatformId.PC_GAMES) {
                            HomeCustomEnrichSearchStatus.Failed(
                                "ScreenScraper can’t identify PC games. Use SteamGridDB instead.",
                            )
                        } else homeCustomEnrichHeroSearchStatus(
                            resultCount = count,
                            attempted = attempted,
                            needsAccess = needsAccess,
                            unreachable = unreachable,
                            providerFailed = providerFailed,
                            needsAccessMessage = when {
                                steamGridDbNeedsAccess && screenScraperNeedsAccess ->
                                    "Connect artwork: Settings › Appearance › Enrichment & artwork."
                                screenScraperNeedsAccess ->
                                    "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork."
                                steamGridDbNeedsAccess ->
                                    "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork."
                                else ->
                                    "Connect artwork: Settings › Appearance › Enrichment & artwork."
                            },
                        )
                    }
                }
                EnrichMediaKind.CLIP -> {
                    val source = picker.filterValue("source") ?: "Both"
                    val format = picker.effectiveFilterValue("encode", hiddenDefault = "All")
                    val providerBudgets = homeCustomEnrichClipProviderBudgets(source, format)
                    val hasScreenScraper = ConnectedArtStore.optedIn.value &&
                        ArtworkCredentialStore.hasHealthyCredentials(core.context)
                    val steamGridDbKey = SteamGridDbKeyStore.resolvedKey(core.context)
                    var attempted = false
                    var needsAccess = false
                    var unreachable = false
                    var providerFailed = false
                    var providerHasMore = false

                    if (homeCustomEnrichClipUsesSteamGridDb(source, format)) {
                        if (steamGridDbKey.isBlank() || !MimirSteamGridDbGate.isOpen) {
                            needsAccess = true
                        } else {
                            attempted = true
                            val result = CustomHeroFetcher.manualSteamGridDbMotionArt(
                                apiKey = steamGridDbKey,
                                query = query,
                                fallbackQuery = picker.gameTitle.takeUnless {
                                    it.equals(query, ignoreCase = true)
                                },
                                size = "Any",
                                page = requestedPage,
                                pageSize = providerBudgets.steamGridDb,
                            )
                            val loaded = result as? CustomHeroProviderResult.Loaded
                            providerHasMore = loaded?.hasMore == true
                            val candidates = loaded?.candidates.orEmpty()
                            val uniqueHashes = homeCustomEnrichUniqueProviderRefHashes(
                                EnrichMediaKind.CLIP,
                                candidates,
                            )
                            CustomHeroFetcher.withLocalPreviews(
                                context = core.context,
                                candidates = candidates,
                                previewNamespace = homeCustomEnrichHeroPreviewNamespace(
                                    generation,
                                    EnrichMediaKind.CLIP,
                                    "SteamGridDB",
                                    requestedPage,
                                ),
                                cachedPreviewFor = cachedPreviewFor(candidates),
                                onCandidateReady = { ready ->
                                    append(
                                        HomeCustomEnrichResult.Clip(ready),
                                        "SteamGridDB",
                                        uniqueHashes,
                                    )
                                },
                            )
                            when (result) {
                                CustomHeroProviderResult.NeedsAccess -> needsAccess = true
                                CustomHeroProviderResult.Unreachable -> unreachable = true
                                CustomHeroProviderResult.ProviderFailed -> providerFailed = true
                                else -> Unit
                            }
                        }
                    }

                    val screenScraperUse = homeCustomEnrichClipScreenScraperUse(
                        source = source,
                        format = format,
                        page = requestedPage,
                        hasScreenScraper = hasScreenScraper,
                        hasEmulatedGame = game != null,
                        platformId = game?.platformId,
                    )
                    if (screenScraperUse.needsAccess) {
                        needsAccess = true
                    }
                    if (screenScraperUse.attempt) {
                        attempted = true
                        val (useVerifiedIdentity, persistResolution) =
                            homeCustomEnrichScreenScraperIdentityUse(picker.gameTitle, query)
                        val result = CustomHeroFetcher.screenScraperHeroes(
                            game = requireNotNull(game),
                            videoAllowed = true,
                            candidateNames = homeCustomEnrichScreenScraperCandidateNames(
                                picker.gameTitle,
                                query,
                            ),
                            useVerifiedIdentity = useVerifiedIdentity,
                            persistResolution = persistResolution,
                        )
                        val candidates = (result as? CustomHeroProviderResult.Loaded)
                            ?.candidates
                            .orEmpty()
                            .filter { candidate ->
                                screenScraperHeroMotionMatchesFormat(candidate, format)
                            }
                            .take(providerBudgets.screenScraper)
                        val uniqueHashes = homeCustomEnrichUniqueProviderRefHashes(
                            EnrichMediaKind.CLIP,
                            candidates,
                        )
                        CustomHeroFetcher.withLocalClipPosters(
                            context = core.context,
                            candidates = candidates,
                            previewNamespace = homeCustomEnrichHeroPreviewNamespace(
                                generation,
                                EnrichMediaKind.CLIP,
                                "ScreenScraper",
                                requestedPage,
                            ),
                            cachedPreviewFor = cachedPreviewFor(candidates),
                            onCandidateReady = {
                                append(
                                    HomeCustomEnrichResult.Clip(it),
                                    "ScreenScraper",
                                    uniqueHashes,
                                )
                            },
                        )
                        when (result) {
                            CustomHeroProviderResult.NeedsAccess -> needsAccess = true
                            CustomHeroProviderResult.Unreachable -> unreachable = true
                            CustomHeroProviderResult.ProviderFailed -> providerFailed = true
                            else -> Unit
                        }
                    }
                    settle(hasMore = providerHasMore) { count ->
                        homeCustomEnrichClipSearchStatus(
                            resultCount = count,
                            attempted = attempted,
                            needsAccess = needsAccess,
                            unreachable = unreachable,
                            providerFailed = providerFailed,
                            source = source,
                            platformId = game?.platformId,
                        )
                    }
                }
            }
        }
    }
    /**
     * The ONE import body both pickers land on -- reported picker mismatch, 2026-09-11. The system `OpenDocument` result and the in-app `FileBrowserMode.PICK_IMAGE`
     * pick both arrive here as a [Uri] (`content://` from the former, `file://` from the latter --
     * `CustomImageImportStore` opens either through `contentResolver.openInputStream`, which serves a
     * `file` scheme directly, and the bounded staging decoder never inspects the scheme). Factored out of
     * the launcher callback rather than duplicated so the staging, the Saved catalog write, the activation
     * and every note the picker shows are one code path, whichever door the bytes came through.
     */
    suspend fun importPickedImage(request: HomeImageImportRequest, uri: Uri) {
        val customReturn = request.returnPhase as? HomeTileMenuPhase.CustomEnrichPicker
        if (customReturn != null) {
            writeCustomEnrichPicker(
                request.owner,
                customReturn.picker.copy(
                    operation = HomeCustomEnrichOperation.APPLY_COMMITTING,
                    keyboardOpen = false,
                ),
            )
        } else {
            val homeOwner = request.owner as? CustomEnrichPickerOwner.Home
            navigation.homeTileMenu
                ?.takeIf { homeOwner != null && it.row.itemId == homeOwner.itemId }
                ?.let { live ->
                    navigation.homeTileMenu = live.copy(phase = HomeTileMenuPhase.Applying)
                }
        }

        if (customReturn != null) {
            val staging = File(core.context.cacheDir, "enrich-stage").apply { mkdirs() }
            val token = customReturn.picker.sessionId.hashCode().toUInt().toString(16)
            val staged = File(staging, "upload-$token.img")
            staged.delete()
            try {
                val prepared = when (val target = request.target) {
                    is HomeImageImportTarget.Icon ->
                        // Pass the cache key: it is what selects the platform-icon 512 cap, so a
                        // staged PREVIEW is normalized to the same size the applied art will be.
                        // Without it the preview would be a 1024 icon and the applied one 512.
                        if (target.keepsAspect) {
                            CustomImageImportStore.stageCover(core.context, uri, staged)
                        } else {
                            CustomImageImportStore.stageIcon(core.context, uri, staged, target.cacheKey)
                        }
                    is HomeImageImportTarget.Hero ->
                        CustomImageImportStore.stageHero(core.context, uri, staged)
                }
                if (!prepared) {
                    updateHomeCustomEnrich(customReturn.picker.sessionId) { live ->
                        live.copy(
                            operation = HomeCustomEnrichOperation.IDLE,
                            note = "That image couldn’t be used. Choose a PNG, JPEG, WebP, or HEIF.",
                        )
                    }
                    return
                }
                withContext(NonCancellable) {
                    // The merged Icon art picker lands an upload by its own shape: within a few percent of 1:1 it is a square tile,
                    // anything else is a box that keeps its shape. Every other picker saves into its own kind.
                    val uploadKind = if (customReturn.picker.shelfIconArt) {
                        iconArtLaneOfAspect(customMediaLocalImageAspect(staged.absolutePath)).kind
                    } else {
                        customReturn.picker.kind
                    }
                    val saved = EnrichSavedStore.save(
                        context = core.context,
                        kind = uploadKind,
                        cacheKey = customReturn.picker.cacheKey,
                        source = EnrichSavedSource.UPLOAD,
                        label = "Uploaded image",
                        fullAsset = staged,
                        activate = false,
                    )
                    val savedRecord = (saved as? EnrichSaveOutcome.Saved)?.asset
                    val activated = savedRecord != null && EnrichSavedStore.activate(
                        core.context,
                        uploadKind,
                        customReturn.picker.cacheKey,
                        savedRecord.assetId,
                    )
                    if (activated) refreshCustomEnrichOwner(request.owner)
                    val catalog = EnrichSavedStore.load(
                        core.context,
                        customReturn.picker.kind,
                        customReturn.picker.cacheKey,
                    )
                    val iconArtCatalog = if (customReturn.picker.shelfIconArt) {
                        IconArtStore.loadOffMain(core.context, customReturn.picker.cacheKey)
                    } else {
                        null
                    }
                    updateHomeCustomEnrich(customReturn.picker.sessionId) { live ->
                        live.copy(
                            saved = iconArtCatalog?.saved ?: catalog.orderedForOpen(),
                            savedLanes = iconArtCatalog?.lanes ?: live.savedLanes,
                            activeAssetId = if (iconArtCatalog != null) iconArtCatalog.activeAssetId else catalog.activeAssetId,
                            operation = HomeCustomEnrichOperation.IDLE,
                            note = when {
                                saved is EnrichSaveOutcome.Full ->
                                    "Saved is full. Remove an unused choice first. Current art is unchanged."
                                savedRecord == null ->
                                    "Couldn’t save this image. Your current art is unchanged."
                                activated -> "Uploaded · Saved · In use"
                                else -> "Saved, but the current art couldn’t be changed."
                            },
                        )
                    }
                }
            } finally {
                staged.delete()
            }
            return
        }

        val imported = when (val target = request.target) {
            is HomeImageImportTarget.Icon ->
                CustomImageImportStore.importIcon(core.context, uri, target.cacheKey)
            is HomeImageImportTarget.Hero ->
                CustomImageImportStore.importHero(core.context, uri, target.gameId)
        }
        val homeOwner = request.owner as? CustomEnrichPickerOwner.Home ?: return
        val live = navigation.homeTileMenu?.takeIf { it.row.itemId == homeOwner.itemId }
        if (imported) {
            refreshCustomEnrichOwner(homeOwner)
            val settledPhase = when (val target = request.target) {
                is HomeImageImportTarget.Icon -> {
                    val phase = request.returnPhase as? HomeTileMenuPhase.ArtPick
                    val path = CustomImageImportStore.retainedIconPath(core.context, target.cacheKey)
                    if (phase != null && path != null) homeArtPickAfterImport(phase, path) else null
                }
                is HomeImageImportTarget.Hero -> {
                    val phase = request.returnPhase as? HomeTileMenuPhase.HeroPick
                    val path = CustomImageImportStore.retainedHeroPath(core.context, target.gameId)
                    if (phase != null && path != null) homeHeroPickAfterImport(phase, path) else null
                }
            }
            if (live != null && settledPhase != null) {
                navigation.homeTileMenu = live.copy(phase = settledPhase)
            } else {
                navigation.closeHomeTileMenu()
            }
        } else if (live != null) {
            val failed = when (val phase = request.returnPhase) {
                is HomeTileMenuPhase.ArtPick -> phase.copy(
                    note = "That image couldn’t be used. Choose a PNG, JPEG, WebP, HEIF, or another image Android can decode.",
                )
                is HomeTileMenuPhase.HeroPick -> phase.copy(
                    note = "That image couldn’t be used. Choose a PNG, JPEG, WebP, HEIF, or another image Android can decode.",
                )
                else -> request.returnPhase
            }
            navigation.homeTileMenu = live.copy(phase = failed)
        }
    }
    var pendingImageImport by remember { mutableStateOf<HomeImageImportRequest?>(null) }
    val imageImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pendingImageImport
        pendingImageImport = null
        if (uri != null && request != null) {
            homeQuickLaunchScope.launch { importPickedImage(request, uri) }
        }
    }

    /**
     * Every Upload / import button routes here -- reported 2026-09-11. [imageImportPickerRoute]
     * decides: with all-files access Mimir's own browser opens in `FileBrowserMode.PICK_IMAGE`
     * (`resolveMimirOverlayStack` ranks `FOLDER_BROWSER` above `HOME_TILE_MENU`, so it covers the
     * Customize-media picker and the tile menu re-shows, phase intact, the moment the browser closes --
     * on a pick AND on a cancel, exactly as the system picker left it); without it the system picker is
     * launched unchanged, since the in-app browser lists through `java.io.File` and would show nothing.
     *
     * **The browser closes itself before this lambda runs.** `FileBrowserCommand.CommitSourceFile`'s
     * dispatch arm is `close()` then `onChoose(path)`, and `FileBrowserStore.mount` wraps `onChoose` in
     * `clearSession()` first -- so there is no second close to call here, and no window in which the
     * import could race a browser that is still on screen. A cancel takes `onDismiss` -> `clearSession()`
     * on the same path with nothing to import.
     *
     * `Uri.fromFile` rather than a `content://` grant: with all-files access the store reads the path
     * directly, and the decoder's bounded copy is what protects the process from an oversized file,
     * not the scheme.
     */
    fun startImageImport(request: HomeImageImportRequest) {
        val allFilesAccess = runCatching { PermissionHelper(core.context).hasFullStorageAccess() }
            .getOrDefault(false)
        when (imageImportPickerRoute(allFilesAccess)) {
            ImageImportPickerRoute.IN_APP -> FileBrowserStore.open(
                context = core.context,
                authority = core.focusAuthority,
                mode = FileBrowserMode.PICK_IMAGE,
                onChoose = { path ->
                    homeQuickLaunchScope.launch { importPickedImage(request, Uri.fromFile(File(path))) }
                },
            )
            ImageImportPickerRoute.SYSTEM -> {
                pendingImageImport = request
                imageImportLauncher.launch(arrayOf("image/*"))
            }
        }
    }
    /**
     * The appearance import landing step (concept A): both appearance-image pickers' + Add image
     * land here with a [Uri] from either door -- Mimir's image browser or the system picker -- and the store does
     * the bounded copy and the processing on IO. The saved lists are re-read, then the open picker gains the tile,
     * its note ("Uploaded · Saved", "Saved is full…", "That image couldn't be used…") and focus. The mark's
     * processing is the companion lane's seam (`AppearanceMarkProcessing`); this step is the same for both kinds.
     */
    suspend fun importAppearanceImage(kind: AppearanceImageKind, uri: Uri) {
        val outcome = when (kind) {
            AppearanceImageKind.BACKGROUND ->
                AppearanceImageStore.importBackground(core.context, uri, appearanceMainDisplayPx(core.context))
            AppearanceImageKind.MARK -> AppearanceImageStore.importMark(core.context, uri)
        }
        AppearanceOverrideStore.refreshSavedLists(core.context)
        settings.appearanceImportLanded(kind, outcome)
    }
    var pendingAppearanceImport by remember { mutableStateOf<AppearanceImageKind?>(null) }
    val appearanceImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = pendingAppearanceImport
        pendingAppearanceImport = null
        if (uri != null && kind != null) { homeQuickLaunchScope.launch { importAppearanceImage(kind, uri) } } else if (kind != null) settings.appearanceImportCancelled(kind)
    }

    /**
     * The same route choice as [startImageImport], for the appearance pickers: Mimir's image browser with all-files
     * access (it ranks above both pickers, which come back, state intact, when it closes), the system picker
     * otherwise. `Uri.fromFile` for the browser's path, as the Customize-media path does.
     */
    fun startAppearanceImageImport(kind: AppearanceImageKind) {
        val allFilesAccess = runCatching { PermissionHelper(core.context).hasFullStorageAccess() }
            .getOrDefault(false)
        when (imageImportPickerRoute(allFilesAccess)) {
            ImageImportPickerRoute.IN_APP -> FileBrowserStore.open(
                context = core.context,
                authority = core.focusAuthority,
                mode = FileBrowserMode.PICK_IMAGE,
                onChoose = { path ->
                    homeQuickLaunchScope.launch { importAppearanceImage(kind, Uri.fromFile(File(path))) }
                },
            )
            ImageImportPickerRoute.SYSTEM -> {
                pendingAppearanceImport = kind
                appearanceImportLauncher.launch(arrayOf("image/*"))
            }
        }
    }
    // A picker's + Add image raises the request; it is consumed here, once, and the route opens.
    LaunchedEffect(settings.uiState.appearanceImportRequest) {
        val kind = settings.uiState.appearanceImportRequest ?: return@LaunchedEffect
        settings.uiState.appearanceImportRequest = null
        startAppearanceImageImport(kind)
    }
    // QL01: single-screen processes cannot depend on a Presentation ever having existed. Refresh the
    // shared, display-agnostic catalog from the PRIMARY whenever its dock opens; coalescing keeps the
    // PackageManager sweep shared with the shelf/companion process path.
    LaunchedEffect(navigation.homeQuickLaunchOpen) {
        if (navigation.homeQuickLaunchOpen) {
            runCatching { CompanionQuickLaunchCatalog.refresh(core.context.applicationContext) }
        }
    }
    val homeQuickLaunchPickerMode = navigation.homeQuickLaunchPickerMode
    val homeQuickLaunchPickerItemsUi = remember(
        homeQuickLaunchPickerMode,
        quickLaunchApps,
        quickLaunchSlots,
        quickLaunchSequences,
    ) {
        homeQuickLaunchPickerMode?.let { mode ->
            homeQuickLaunchPickerItems(mode, quickLaunchApps, quickLaunchSlots, quickLaunchSequences)
        }.orEmpty()
    }
    val assignHomeQuickLaunchSlot: (Int, String) -> Unit = { index, packageName ->
        QuickLaunchStore.setSlot(core.context, index, packageName)
        navigation.closeHomeQuickLaunchPicker()
    }
    val clearHomeQuickLaunchSlot: (Int) -> Unit = { index ->
        QuickLaunchStore.setSlot(core.context, index, null)
        navigation.closeHomeQuickLaunchPicker()
    }
    /**
     * Exact primary-display app transaction used when a secondary quick-launch package is assigned MAIN.
     *
     * Round 5 (5b): `sourceRole` is the screen the request came from. Main's own Home quick launch (no Bottom display)
     * passes PRIMARY_SINGLE, and the route reports its refusals and failures on Main. Everything that reaches the route
     * through [CompanionQuickLaunchPrimaryRoute] is on the Bottom and passes SECONDARY_COMPANION: the Tabs drawer, the
     * Presentation's quick-launch rails and choice sheets, the Bottom Quick launch window, and SWAP's last leg. For those the
     * route makes no refusal toast and answers with its reason ([QuickLaunchPrimaryRouteResult.Refused]); the caller makes
     * the tap's one toast from that answer, on the display the player tapped (one tap used to show a toast on each screen,
     * and then only a generic one on the Bottom). Start failures come after the route accepted, when the caller has nothing
     * to report, and are made on the Bottom. `noAnimation` is SWAP's request to start the app on Main with no transition
     * (O12); every other caller passes false.
     */
    val launchHomeQuickPackageOnPrimary: (String, Boolean, CompanionOverlayRole, Boolean) -> QuickLaunchPrimaryRouteResult = launch@{ packageName, fromGeneral, sourceRole, noAnimation ->
        val reportsRefusal = sourceRole == CompanionOverlayRole.PRIMARY_SINGLE
        fun refused(reason: String): QuickLaunchPrimaryRouteResult {
            if (reportsRefusal) showMimirMessage(core.context, reason)
            return QuickLaunchPrimaryRouteResult.Refused(reason)
        }
        // O15 follow-up: the Library's launch state stays IntentSent for the whole run of a game it launched, and past that
        // run when a return skips the Library's restore (the M button's Home and Quick launch clear the return target and
        // leave the state). The ledger says what the state means now: a game still starting keeps these words, a game
        // already running gets the ruled sentence, and no game session leaves the answer to admission below.
        val libraryLaunchHold = libraryLaunchGuardHold(library.launchState, MimirSessionAuthority.state.value)
        if (libraryLaunchHold == GameLaunchSessionHold.STARTING) {
            return@launch refused("Still opening the previous app")
        }
        if (libraryLaunchHold == GameLaunchSessionHold.RUNNING) {
            return@launch refused(gameLaunchSessionHeldMessage(libraryLaunchHold))
        }
        val cleanPackageName = packageName.trim().takeIf { it.isNotBlank() } ?: run {
            return@launch refused("App couldn’t be launched")
        }
        val label = CompanionQuickLaunchCatalog.entry(cleanPackageName)?.label
        // Admit BEFORE resolver I/O. The synchronous session transition is the one owner of contention:
        // two taps cannot both queue resolver work, and a refusal remains visible to the caller.
        // O15: a refusal names what held it, read from the session that refused this admission.
        var heldBy = GameLaunchSessionHold.STARTING
        val operation = MimirSessionAuthority.preparePrimaryApp(
            CompanionRunningApp(
                packageName = cleanPackageName,
                label = label ?: cleanPackageName,
                iconPath = CompanionQuickLaunchCatalog.entry(cleanPackageName)?.iconPath,
            ),
            onHeld = { heldBy = it },
        ) ?: run {
            return@launch refused(gameLaunchSessionHeldMessage(heldBy))
        }
        navigation.closeHomeQuickLaunch()
        val appContext = core.context.applicationContext
        // A Bottom request's start failures go to the selected secondary display: the Presentation and the Bottom ᛗ, with
        // its Tabs drawer, are mounted on it.
        val failureDisplayId = if (sourceRole == CompanionOverlayRole.SECONDARY_COMPANION) {
            CompanionProcessRuntime.services(appContext).secondaryDisplaySelector.snapshot.value.selectedId
        } else {
            null
        }
        // This work intentionally uses process lifetime: Activity recreation must not cancel an accepted
        // operation between admission, resolver lookup and the native start. Every terminal arm settles the
        // exact session and records MRU only when the activity start was accepted.
        CompanionProcessRuntime.launchPrimaryQuickLaunch {
            withContext(NonCancellable) {
                val intent = runCatching {
                    withContext(Dispatchers.IO) {
                        resolveNativeShelfLaunchIntent(appContext, cleanPackageName)
                    }
                }.getOrNull()
                if (intent == null) {
                    MimirSessionAuthority.appFailed(operation, "no_launchable_intent")
                    showMimirMessage(
                        companionFeedbackContext(appContext, failureDisplayId),
                        "App couldn’t be launched",
                    )
                    QuickLaunchStore.settleLaunch(
                        context = appContext,
                        origin = if (fromGeneral) {
                            QuickLaunchLaunchOrigin.GENERAL_PRIMARY
                        } else {
                            QuickLaunchLaunchOrigin.PINNED_SLOT
                        },
                        dispatchSucceeded = false,
                        packageName = cleanPackageName,
                    )
                    return@withContext
                }
                val started = runCatching {
                    startNativeShelfLaunchIntent(
                        context = appContext,
                        packageName = cleanPackageName,
                        target = intent,
                        launchDisplayId = Display.DEFAULT_DISPLAY,
                        // Only SWAP's exchange asks for this; Quick launch keeps the animation.
                        noAnimation = noAnimation,
                    )
                }.getOrDefault(false)
                if (started) {
                    MimirSessionAuthority.appDispatched(operation)
                } else {
                    MimirSessionAuthority.appFailed(operation, "activity_not_started")
                    showMimirMessage(
                        companionFeedbackContext(appContext, failureDisplayId),
                        "App couldn’t be launched",
                    )
                }
                QuickLaunchStore.settleLaunch(
                    context = appContext,
                    origin = if (fromGeneral) {
                        QuickLaunchLaunchOrigin.GENERAL_PRIMARY
                    } else {
                        QuickLaunchLaunchOrigin.PINNED_SLOT
                    },
                    dispatchSucceeded = started,
                    packageName = cleanPackageName,
                )
            }
        }
        QuickLaunchPrimaryRouteResult.Accepted
    }
    val launchHomeQuickPackage: (String, Boolean) -> Boolean = launch@{ packageName, fromGeneral ->
        val label = CompanionQuickLaunchCatalog.entry(packageName)?.label
        val secondaryAvailable = CompanionReturnCoordinator.runtime().snapshot.value.disposition !is
            SecondaryDisplayDisposition.NoDisplay
        if (secondaryAvailable) {
            // Footer parity: requestPreparedLaunch returns null WITHOUT publishing while a launch is
            // pending (6s lock) or when prepare fails — the footer disables its slots for the first
            // case and renders launchMessage for the second, both on screen 2. The user who confirmed
            // here is looking at the PRIMARY, so the picker stays open on a lock and failures toast.
            if (CompanionRecentsState.ui.value.launchLocked) {
                showMimirMessage(core.context, "Still opening the previous app")
                return@launch false
            } else {
                navigation.closeHomeQuickLaunch()
                // Quick Launch closes the app's copy on Main first (BottomMainCopyClearance.kt).
                val operationId = CompanionRecentsState.onLaunch(packageName, label, clearMainCopy = true)
                val dispatched = companionQuickLaunchDispatchSucceeded(
                    operationId,
                    CompanionRecentsState.ui.value.launchState,
                )
                if (!dispatched) {
                    showMimirMessage(core.context, "Launch unavailable. Try again.")
                }
                QuickLaunchStore.settleLaunch(
                    context = core.context,
                    origin = if (fromGeneral) {
                        QuickLaunchLaunchOrigin.GENERAL_COMPANION
                    } else {
                        QuickLaunchLaunchOrigin.PINNED_SLOT
                    },
                    dispatchSucceeded = dispatched,
                    packageName = packageName,
                )
            }
        } else {
            launchHomeQuickPackageOnPrimary(packageName, fromGeneral, CompanionOverlayRole.PRIMARY_SINGLE, false)
        }
        true
    }
    val handleCustomEnrichTransition: (
        CustomEnrichPickerOwner,
        com.gruning.mimir.rewrite.core.model.library.GameRow?,
        () -> Unit,
        HomeCustomEnrichState,
        HomeCustomEnrichTransition,
    ) -> Unit = { owner, game, onBack, preDispatchPicker, transition ->
        val picker = reconcileCustomEnrichPickerForWrite(
            preDispatchPicker = preDispatchPicker,
            transition = transition,
            live = currentLiveCustomEnrichPicker(owner),
        )
        writeCustomEnrichPicker(owner, picker)
        when (val command = transition.command) {
            HomeCustomEnrichCommand.None -> Unit
            HomeCustomEnrichCommand.CancelSearch -> {
                homeCustomEnrichSearchJob?.cancel()
                homeCustomEnrichSearchJob = null
            }
            HomeCustomEnrichCommand.Back -> {
                homeCustomEnrichSearchJob?.cancel()
                onBack()
            }
            HomeCustomEnrichCommand.OpenKeyboard -> updateHomeCustomEnrich(picker.sessionId) {
                it.copy(keyboardOpen = true)
            }
            // L1/R1 or the Media panel. The new type's picker is built here (nothing is fetched), keeps the typed
            // name, and replaces this one on the same page. Nothing here cancels a search or a preview: customMediaSwitchAllowed
            // refuses the switch while either is running (and while an apply is staged or the keyboard is open), and an apply or a
            // remove (X) still running refuses it here: its write-back would land the old type's picker over the new one.
            is HomeCustomEnrichCommand.SwitchMedia -> navigation.homeTileMenu?.let { live ->
                val old = (live.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker
                if (old != null && owner is CustomEnrichPickerOwner.Home &&
                    live.row.itemId == owner.itemId && customMediaSwitchAllowed(old) &&
                    homeCustomEnrichApplyJob?.isActive != true
                ) {
                    val next = buildHomeCustomEnrichPicker(
                        context = core.context,
                        current = live,
                        kind = command.selection.kind,
                        mediaState = command.selection.mediaState,
                        migrations = homeCustomEnrichMigrations,
                        scope = homeQuickLaunchScope,
                        update = { sessionId, transform -> updateHomeCustomEnrich(sessionId, transform) },
                    )
                    navigation.homeTileMenu = customMediaSwitched(live, old, next, command.selection)
                }
            }
            HomeCustomEnrichCommand.Upload -> {
                val target = when (picker.kind) {
                    // The merged Icon art picker keeps an upload's own shape (stageCover), and the lane it lands in is decided by
                    // that shape once it is staged ([importPickedImage]); the platform picker's square crop is unchanged.
                    EnrichMediaKind.ICON ->
                        HomeImageImportTarget.Icon(picker.cacheKey, keepsAspect = picker.shelfIconArt)
                    // Reuse only the bounded square staging decoder. The picker kind below still
                    // saves/promotes into TomeCartridgeArtStore, never the global icon renderer.
                    EnrichMediaKind.TOME_PHYSICAL -> HomeImageImportTarget.Icon(picker.cacheKey)
                    // A cover is bounded and validated the same way but KEEPS ITS SHAPE (stageCover): the square crop is for icons, and it
                    // cut a 2:1 upload at its sides before any tile drew it. The pick is promoted into CustomCoverStore,
                    // not into the global icon renderer.
                    EnrichMediaKind.COVER -> HomeImageImportTarget.Icon(picker.cacheKey, keepsAspect = true)
                    EnrichMediaKind.HERO -> HomeImageImportTarget.Hero(GameId(picker.cacheKey))
                    EnrichMediaKind.CLIP -> null
                }
                if (target != null) {
                    startImageImport(
                        HomeImageImportRequest(
                            owner = owner,
                            target = target,
                            returnPhase = HomeTileMenuPhase.CustomEnrichPicker(picker),
                        ),
                    )
                }
            }
            is HomeCustomEnrichCommand.PrepareExpandedPreview -> {
                homeCustomEnrichExpandedPreviewToken = null
                homeCustomEnrichExpandedPreviewJob?.cancel()
                val result = picker.resultById(command.resultId)
                val candidate = when (result) {
                    is HomeCustomEnrichResult.Hero -> result.candidate
                    is HomeCustomEnrichResult.Clip -> result.candidate
                    else -> null
                }
                if (result == null || candidate == null || result.saved != null) {
                    updateHomeCustomEnrich(picker.sessionId) { live ->
                        live.copy(
                            expandedPreview = HomeCustomEnrichExpandedPreview.Failed(command.resultId),
                        )
                    }
                } else {
                    val request = homeCustomEnrichExpandedPreviewRequest(
                        cacheDir = core.context.cacheDir,
                        sessionId = picker.sessionId,
                        generation = picker.generation,
                        resultId = command.resultId,
                        nonce = System.nanoTime().toULong(),
                    )
                    homeCustomEnrichExpandedPreviewToken = request.token
                    homeCustomEnrichExpandedPreviewJob = homeQuickLaunchScope.launch {
                        try {
                            if (!deleteHomeCustomEnrichPriorExpandedPreview(
                                    cacheDir = core.context.cacheDir,
                                    sessionId = picker.sessionId,
                                    localPath = command.priorLocalPath,
                                )
                            ) {
                                updateHomeCustomEnrich(picker.sessionId) { live ->
                                    live.copy(
                                        expandedPreview = HomeCustomEnrichExpandedPreview.Failed(
                                            command.resultId,
                                        ),
                                    )
                                }
                                return@launch
                            }
                            val settlement = stageHomeCustomEnrichExpandedPreview(
                                request = request,
                                isCurrent = { token -> homeCustomEnrichExpandedPreviewToken == token },
                                stage = { target ->
                                    CustomHeroFetcher.stageCandidateOutcome(
                                        context = core.context,
                                        candidate = candidate,
                                        expectedVideo = result is HomeCustomEnrichResult.Clip,
                                        target = target,
                                    )
                                },
                            )
                            if (settlement is HomeCustomEnrichExpandedPreviewSettlement.Current) {
                                var accepted = false
                                updateHomeCustomEnrich(picker.sessionId) { live ->
                                    val loading = live.expandedPreview as? HomeCustomEnrichExpandedPreview.Loading
                                    if (!live.previewExpanded || loading?.resultId != command.resultId) {
                                        live
                                    } else if (settlement.outcome == CustomHeroStageOutcome.READY &&
                                        settlement.localPath != null
                                    ) {
                                        accepted = true
                                        live.copy(
                                            expandedPreview = HomeCustomEnrichExpandedPreview.Ready(
                                                resultId = command.resultId,
                                                localPath = settlement.localPath,
                                            ),
                                        )
                                    } else {
                                        live.copy(
                                            expandedPreview = HomeCustomEnrichExpandedPreview.Failed(
                                                command.resultId,
                                            ),
                                        )
                                    }
                                }
                                if (!accepted && settlement.localPath != null) {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        File(settlement.localPath).delete()
                                    }
                                }
                            }
                        } finally {
                            if (homeCustomEnrichExpandedPreviewToken == request.token) {
                                homeCustomEnrichExpandedPreviewToken = null
                                homeCustomEnrichExpandedPreviewJob = null
                            }
                        }
                    }
                }
            }
            HomeCustomEnrichCommand.CancelExpandedPreview -> {
                homeCustomEnrichExpandedPreviewToken = null
                homeCustomEnrichExpandedPreviewJob?.cancel()
                homeCustomEnrichExpandedPreviewJob = null
            }
            is HomeCustomEnrichCommand.Search -> launchHomeCustomEnrichSearch(
                picker,
                game,
                command.page,
            )
            HomeCustomEnrichCommand.Automatic -> {
                if (homeCustomEnrichApplyJob?.isActive == true) {
                    updateHomeCustomEnrich(picker.sessionId) {
                        it.copy(operation = HomeCustomEnrichOperation.IDLE, note = "Another selection is still finishing.")
                    }
                } else {
                    homeCustomEnrichApplyJob = homeQuickLaunchScope.launch {
                        withContext(NonCancellable) {
                            // "Default" on the merged Icon art clears his shelf pick in every lane, so automatic art shows.
                            val marked = if (picker.shelfIconArt) {
                                IconArtStore.activateAutomatic(core.context, picker.cacheKey)
                            } else {
                                EnrichSavedStore.activateAutomatic(
                                    core.context,
                                    picker.kind,
                                    picker.cacheKey,
                                )
                            }
                            if (marked) refreshCustomEnrichOwner(owner)
                            updateHomeCustomEnrich(picker.sessionId) { live ->
                                live.copy(
                                    activeAssetId = if (marked) null else live.activeAssetId,
                                    operation = HomeCustomEnrichOperation.IDLE,
                                    note = if (marked) "Automatic art restored" else "Couldn’t restore Automatic.",
                                )
                            }
                        }
                    }
                }
            }
            is HomeCustomEnrichCommand.ActivateSaved -> {
                if (homeCustomEnrichApplyJob?.isActive == true) {
                    updateHomeCustomEnrich(picker.sessionId) {
                        it.copy(operation = HomeCustomEnrichOperation.IDLE, note = "Another selection is still finishing.")
                    }
                } else {
                    homeCustomEnrichApplyJob = homeQuickLaunchScope.launch {
                        withContext(NonCancellable) {
                            // The merged picker activates an asset in the lane that holds it, which records that lane's face kind.
                            val activated = if (picker.shelfIconArt) {
                                IconArtStore.activate(core.context, picker.cacheKey, command.assetId, picker.savedLanes[command.assetId])
                            } else {
                                EnrichSavedStore.activate(
                                    core.context,
                                    picker.kind,
                                    picker.cacheKey,
                                    command.assetId,
                                )
                            }
                            if (activated) refreshCustomEnrichOwner(owner)
                            // "In use" is asked of the shelf, not assumed from the command: a choice saved in the box lane before the lanes merged is
                            // activated in its real lane (a different catalog), and the shelf may paint another pick over it. The mark and the lane
                            // map are the merged gallery's, read after the write.
                            val shown = if (picker.shelfIconArt && activated) IconArtStore.loadOffMain(core.context, picker.cacheKey) else null
                            updateHomeCustomEnrich(picker.sessionId) { live ->
                                live.copy(
                                    saved = shown?.saved ?: live.saved,
                                    savedLanes = shown?.lanes ?: live.savedLanes,
                                    activeAssetId = if (shown != null) shown.activeAssetId else command.assetId.takeIf { activated } ?: live.activeAssetId,
                                    operation = HomeCustomEnrichOperation.IDLE,
                                    note = if (activated) "Current art updated" else "Couldn’t use this saved media.",
                                )
                            }
                        }
                    }
                }
            }
            is HomeCustomEnrichCommand.RemoveSaved -> {
                if (homeCustomEnrichApplyJob?.isActive == true) {
                    updateHomeCustomEnrich(picker.sessionId) {
                        it.copy(operation = HomeCustomEnrichOperation.IDLE, note = "Another selection is still finishing.")
                    }
                } else {
                    homeCustomEnrichApplyJob = homeQuickLaunchScope.launch {
                        withContext(NonCancellable) {
                            val removed = if (picker.shelfIconArt) {
                                IconArtStore.remove(core.context, picker.cacheKey, command.assetId)
                            } else {
                                EnrichSavedStore.remove(
                                    core.context,
                                    picker.kind,
                                    picker.cacheKey,
                                    command.assetId,
                                )
                            }
                            if (removed) {
                                val removedIndex = picker.saved.indexOfFirst { it.assetId == command.assetId }
                                val nextSaved = picker.saved.filterNot { it.assetId == command.assetId }
                                val next = picker.copy(
                                    saved = nextSaved,
                                    savedLanes = picker.savedLanes - command.assetId,
                                    results = picker.results.map { result ->
                                        if (result.saved?.assetId == command.assetId) {
                                            result.withSavedAsset(null)
                                        } else {
                                            result
                                        }
                                    },
                                    operation = HomeCustomEnrichOperation.IDLE,
                                    note = "Saved choice removed",
                                )
                                writeCustomEnrichPicker(owner, next)
                                val nearest = nextSaved.getOrNull(
                                    removedIndex.coerceAtMost(nextSaved.lastIndex),
                                ) ?: nextSaved.lastOrNull()
                                val target = nearest?.let { HomeCustomEnrichAsset.Saved(it) }
                                    ?: HomeCustomEnrichAsset.Automatic
                                core.focusAuthority.requestFocus(homeCustomEnrichAssetKey(next, target))
                            } else {
                                updateHomeCustomEnrich(picker.sessionId) {
                                    it.copy(
                                        operation = HomeCustomEnrichOperation.IDLE,
                                        note = "Couldn’t remove this saved choice.",
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is HomeCustomEnrichCommand.ApplyResult -> {
                homeCustomEnrichSearchJob?.cancel()
                homeCustomEnrichSearchJob = null
                if (picker.searchStatus is HomeCustomEnrichSearchStatus.Searching) {
                    updateHomeCustomEnrich(picker.sessionId) { live ->
                        live.copy(
                            generation = live.generation + 1,
                            hasMore = false,
                            searchStatus = HomeCustomEnrichSearchStatus.Complete(live.results.size),
                        )
                    }
                }
                val result = picker.resultById(command.resultId)
                if (result == null) {
                    updateHomeCustomEnrich(picker.sessionId) {
                        it.copy(operation = HomeCustomEnrichOperation.IDLE, note = "That result is no longer available.")
                    }
                } else if (homeCustomEnrichApplyJob?.isActive == true) {
                    // A hand-picked transaction is single-writer and commit-to-completion. A picker
                    // reopened through an external surface change cannot replace the active writer.
                    updateHomeCustomEnrich(picker.sessionId) {
                        it.copy(operation = HomeCustomEnrichOperation.IDLE, note = "Another selection is still finishing.")
                    }
                } else {
                    homeCustomEnrichApplyJob = homeQuickLaunchScope.launch {
                        // Once the gallery choice is confirmed, remote staging and durable activation
                        // are one transaction. Back, Home, app pause, and picker-session changes may
                        // hide its progress UI, but cannot leave a half-staged or silently abandoned pick.
                        runHomeCustomEnrichCommittedPick {
                            val staging = File(core.context.cacheDir, "enrich-stage").apply { mkdirs() }
                            val token = picker.sessionId.hashCode().toUInt().toString(16) + "-" +
                                result.stableId.hashCode().toUInt().toString(16) + "-" +
                                System.nanoTime().toULong().toString(16)
                            val staged = File(
                                staging,
                                "$token.${when (result.mediaType) {
                                    CustomHeroMediaType.ANIMATED_IMAGE -> "webp"
                                    CustomHeroMediaType.VIDEO -> "mp4"
                                    else -> "img"
                                }}",
                            )
                            val poster = File(staging, "$token.poster.jpg")
                            staged.delete()
                            poster.delete()
                            try {
                                var stageOutcome: CustomHeroStageOutcome? = null
                                var preparedPreview: File? = null
                                // The merged Icon art picker sends a found result to the lane its SHAPE belongs in (a square, a box
                                // or the article), read from the result's provider type and never from the staged file, which
                                // is canvas-normalised before anyone could ask. Every other picker saves into its own kind.
                                val iconArtLane = if (picker.shelfIconArt) {
                                    (result as? HomeCustomEnrichResult.Icon)?.let { icon ->
                                        iconArtLaneOf(
                                            icon.providerMediaType,
                                            icon.candidate.savedSource == EnrichSavedSource.STEAMGRIDDB,
                                        )
                                    }
                                } else {
                                    null
                                }
                                val saveKind = iconArtLane?.kind ?: picker.kind
                                val ready = when (result) {
                                    is HomeCustomEnrichResult.Icon -> SteamGridDbTileFetcher.stagePick(
                                        core.context,
                                        result.candidate,
                                        staged,
                                        // A box and the article keep the shape the provider drew them in.
                                        keepsShape = iconArtLane != null && iconArtLane != IconArtLane.SQUARE,
                                    )
                                    is HomeCustomEnrichResult.Hero -> {
                                        stageOutcome = CustomHeroFetcher.stageCandidateOutcome(
                                            core.context,
                                            result.candidate,
                                            expectedVideo = false,
                                            target = staged,
                                        )
                                        stageOutcome == CustomHeroStageOutcome.READY
                                    }
                                    is HomeCustomEnrichResult.Clip -> {
                                        stageOutcome = CustomHeroFetcher.stageCandidateOutcome(
                                            core.context,
                                            result.candidate,
                                            expectedVideo = true,
                                            target = staged,
                                        )
                                        if (stageOutcome == CustomHeroStageOutcome.READY) {
                                            preparedPreview = when (result.candidate.mediaType) {
                                                CustomHeroMediaType.ANIMATED_IMAGE ->
                                                    poster.takeIf {
                                                        CustomHeroFetcher.createMotionArtPoster(staged, it)
                                                    } ?: CustomHeroFetcher.customHeroReusableCandidatePoster(
                                                        result.candidate,
                                                    )
                                                CustomHeroMediaType.VIDEO ->
                                                    poster.takeIf {
                                                        CustomHeroFetcher.createClipPoster(staged, it)
                                                    } ?: CustomHeroFetcher.customHeroReusableCandidatePoster(
                                                        result.candidate,
                                                    )
                                                CustomHeroMediaType.STATIC -> null
                                            }
                                        }
                                        stageOutcome == CustomHeroStageOutcome.READY
                                    }
                                }
                                if (!ready) {
                                    updateHomeCustomEnrich(picker.sessionId) { live ->
                                        live.copy(
                                            operation = HomeCustomEnrichOperation.IDLE,
                                            note = homeCustomEnrichStageFailureMessage(stageOutcome),
                                        )
                                    }
                                    return@runHomeCustomEnrichCommittedPick
                                }
                                updateHomeCustomEnrich(picker.sessionId) {
                                    it.copy(operation = HomeCustomEnrichOperation.APPLY_COMMITTING, note = null)
                                }
                                val source = homeCustomEnrichSavedSource(result)
                                val saved = EnrichSavedStore.save(
                                    context = core.context,
                                    kind = saveKind,
                                    cacheKey = picker.cacheKey,
                                    source = source,
                                    label = result.label,
                                    fullAsset = staged,
                                    previewAsset = if (picker.kind == EnrichMediaKind.CLIP) {
                                        preparedPreview
                                    } else {
                                        staged
                                    },
                                    allowMissingPreview = picker.kind == EnrichMediaKind.CLIP &&
                                        preparedPreview == null,
                                    width = result.width,
                                    height = result.height,
                                    durationMillis = result.durationMillis,
                                    mediaType = result.mediaType,
                                    providerReference = result.providerReference,
                                    // WHICH kind of face he chose, carried so apply can record it.
                                    providerMediaType = result.providerMediaType,
                                    activate = false,
                                )
                                val savedRecord = (saved as? EnrichSaveOutcome.Saved)?.asset
                                val activated = if (savedRecord != null && command.activate) {
                                    EnrichSavedStore.activate(
                                        core.context,
                                        saveKind,
                                        picker.cacheKey,
                                        savedRecord.assetId,
                                    )
                                } else {
                                    savedRecord != null
                                }
                                if (activated && command.activate) refreshCustomEnrichOwner(owner)
                                // The merged picker's gallery is the three catalogs read as one: reread it after the write.
                                val iconArtCatalog = if (picker.shelfIconArt) IconArtStore.loadOffMain(core.context, picker.cacheKey) else null
                                updateHomeCustomEnrich(picker.sessionId) { live ->
                                    val markedResults = if (savedRecord != null) {
                                        live.results.map { candidate ->
                                            if (candidate.stableId == result.stableId) {
                                                candidate.withSavedAsset(savedRecord)
                                            } else {
                                                candidate
                                            }
                                        }
                                    } else live.results
                                    // For the merged picker "persisted" is the three catalogs together, not the one lane just written.
                                    val persistedIds = if (iconArtCatalog != null) {
                                        iconArtCatalog.saved.mapTo(mutableSetOf()) { it.assetId }
                                            .takeIf { saved is EnrichSaveOutcome.Saved }
                                    } else {
                                        (saved as? EnrichSaveOutcome.Saved)
                                            ?.catalog
                                            ?.entries
                                            ?.mapTo(mutableSetOf()) { it.assetId }
                                    }
                                    val reconciledResults = if (persistedIds != null) {
                                        markedResults.map { candidate ->
                                            candidate.withSavedAsset(
                                                candidate.saved?.takeIf { it.assetId in persistedIds },
                                            )
                                        }
                                    } else {
                                        markedResults
                                    }
                                    live.copy(
                                        saved = if (persistedIds != null) {
                                            live.saved.filter { it.assetId in persistedIds }
                                        } else live.saved,
                                        savedLanes = iconArtCatalog?.lanes ?: live.savedLanes,
                                        results = reconciledResults,
                                        activeAssetId = savedRecord?.assetId
                                            .takeIf { activated && command.activate }
                                            ?: live.activeAssetId,
                                        operation = HomeCustomEnrichOperation.IDLE,
                                        note = when {
                                            saved is EnrichSaveOutcome.Full ->
                                                "Saved is full. Remove an unused choice first."
                                            savedRecord == null ->
                                                "Couldn’t save this media. Your current choice is unchanged."
                                            !activated -> "Saved, but the current art couldn’t be changed."
                                            command.activate -> "Saved · In use"
                                            else -> "Saved for offline use"
                                        },
                                    )
                                }
                            } finally {
                                // Scratch is transaction-local even when the picker is no longer visible.
                                staged.delete()
                                poster.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    // Cocoon-style Y-menu actions. The reducer is pure; the async legs (candidate
    // fetch, pick download) land back into navigation.homeTileMenu ONLY if the same tile's menu is
    // still open. Remove + apply both reconcile through the shelf's reactive state (membership flow /
    // tile epoch) — the asset resync Cocoon skips.
    val onHomeTileMenuAction: (ControllerAction) -> Unit = { action ->
        navigation.homeTileMenu?.let { current ->
            val customPicker = (current.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker
            if (customPicker != null) {
                handleCustomEnrichTransition(
                    CustomEnrichPickerOwner.Home(current.row.itemId),
                    homeTileMenuGame(current, homeShelf.gameForAchievements),
                    {
                        // B with nothing left to close returns to the Edit menu; the doors with no menu of their
                        // own (the Library hero, compact) close everything.
                        navigation.homeTileMenu = customMediaBack(current)
                    },
                    customPicker,
                    reduceHomeCustomEnrich(core.focusAuthority, customPicker, action),
                )
            } else {
                navigation.homeTileMenu = reduceHomeTileMenu(
                state = current,
                action = action,
                onRemove = { itemId ->
                    if (homeLaunchCard.session?.row?.itemId == itemId) homeLaunchCard.close()
                    homeShelf.removeFavorite(itemId)
                    homeShelf.refreshTiles()
                },
                // The Library menu's Add favorite : the one membership write the Library's Favorite button used, by the row's own identity, so an Android game
                // adds exactly as an emulated one does. Only the Library entry points offer the row.
                onAddFavorite = { itemId -> homeShelf.addFavorite(itemId) },
                onLoadArt = {
                    homeQuickLaunchScope.launch {
                        val searchTitles = (current.row.itemId as? HomeShelfItemId.Emulated)?.let { emulated ->
                            ArtworkFetchController.providerSearchTitles(
                                core.context,
                                emulated.gameId.value,
                                current.row.title,
                            )
                        }.orEmpty().ifEmpty { listOf(current.row.title) }
                        val lookup = SteamGridDbTileFetcher.candidatesFor(
                            context = core.context,
                            apiKey = SteamGridDbKeyStore.resolvedKey(core.context),
                            titles = searchTitles,
                        )
                        val cacheKey = homeTileMenuCacheKey(current.row.itemId)
                        val localChoices = buildList {
                            add(HomeTileArtChoice.UseDefault)
                            CustomImageImportStore.retainedIconPath(core.context, cacheKey)?.let {
                                add(HomeTileArtChoice.UploadedImage(it))
                            }
                            add(HomeTileArtChoice.ImportImage)
                        }
                        val next = when (lookup) {
                            SteamGridDbTileFetcher.CandidateLookup.NeedsKey,
                            SteamGridDbTileFetcher.CandidateLookup.NeedsAccess,
                            ->
                                HomeTileMenuPhase.ArtPick(
                                    choices = localChoices,
                                    focusedIndex = homeArtPickerFocusedIndex(core.context, cacheKey, localChoices),
                                    note = "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork.",
                                )
                            SteamGridDbTileFetcher.CandidateLookup.Failed ->
                                HomeTileMenuPhase.ArtPick(
                                    choices = localChoices,
                                    focusedIndex = homeArtPickerFocusedIndex(core.context, cacheKey, localChoices),
                                    note = "Couldn’t reach SteamGridDB. You can still upload an image.",
                                )
                            SteamGridDbTileFetcher.CandidateLookup.Busy ->
                                HomeTileMenuPhase.ArtPick(
                                    choices = localChoices,
                                    focusedIndex = homeArtPickerFocusedIndex(core.context, cacheKey, localChoices),
                                    note = "SteamGridDB is busy right now. Try again shortly.",
                                )
                            is SteamGridDbTileFetcher.CandidateLookup.Loaded -> {
                                val choices = buildList {
                                    addAll(localChoices)
                                    lookup.candidates.forEach { add(HomeTileArtChoice.Art(it)) }
                                }
                                HomeTileMenuPhase.ArtPick(
                                    choices = choices,
                                    focusedIndex = homeArtPickerFocusedIndex(core.context, cacheKey, choices),
                                    note = "No icon art found for this game."
                                        .takeIf { lookup.candidates.isEmpty() },
                                )
                            }
                        }
                        navigation.homeTileMenu
                            ?.takeIf { it.row.itemId == current.row.itemId && it.phase is HomeTileMenuPhase.LoadingArt }
                            ?.let { navigation.homeTileMenu = it.copy(phase = next) }
                    }
                },
                onApplyPick = { candidate, index ->
                    homeQuickLaunchScope.launch {
                        val applied = runCatching {
                            SteamGridDbTileFetcher.applyPick(
                                context = core.context,
                                cacheKey = homeTileMenuCacheKey(current.row.itemId),
                                candidate = candidate,
                                index = index,
                            )
                        }.getOrDefault(false)
                        if (applied) {
                            publishTileMenuIconChange(homeShelf.refreshTiles) { core.repository.refreshShelfFaces() }
                            navigation.closeHomeTileMenu()
                        } else {
                            val picker = current.phase as? HomeTileMenuPhase.ArtPick
                            navigation.homeTileMenu
                                ?.takeIf {
                                    it.row.itemId == current.row.itemId && it.phase is HomeTileMenuPhase.Applying
                                }
                                ?.let {
                                    navigation.homeTileMenu = current.copy(
                                        phase = picker?.copy(
                                            note = "Couldn’t save this icon art. Try again.",
                                        ) ?: HomeTileMenuPhase.ProviderUnavailable(
                                            "Couldn’t save this icon art. Try again.",
                                        ),
                                    )
                                }
                        }
                    }
                },
                onImportArt = {
                    val phase = current.phase as? HomeTileMenuPhase.ArtPick
                    if (phase != null) {
                        startImageImport(
                            HomeImageImportRequest(
                                owner = CustomEnrichPickerOwner.Home(current.row.itemId),
                                target = HomeImageImportTarget.Icon(homeTileMenuCacheKey(current.row.itemId)),
                                returnPhase = phase,
                            ),
                        )
                    }
                },
                onApplyUploadedArt = {
                    homeQuickLaunchScope.launch {
                        val phase = current.phase as? HomeTileMenuPhase.ArtPick
                        val applied = CustomImageImportStore.activateIcon(
                            core.context,
                            homeTileMenuCacheKey(current.row.itemId),
                        )
                        if (applied) {
                            publishTileMenuIconChange(homeShelf.refreshTiles) { core.repository.refreshShelfFaces() }
                            navigation.closeHomeTileMenu()
                        } else {
                            navigation.homeTileMenu
                                ?.takeIf { it.row.itemId == current.row.itemId }
                                ?.let { live ->
                                    navigation.homeTileMenu = live.copy(
                                        phase = phase?.copy(note = "The uploaded image is no longer available.")
                                            ?: HomeTileMenuPhase.ProviderUnavailable(
                                                "The uploaded image is no longer available.",
                                            ),
                                    )
                                }
                        }
                    }
                },
                onResetArt = {
                    homeQuickLaunchScope.launch {
                        val cleared = SteamGridDbTileFetcher.clearPickAsAutomatic(
                            core.context,
                            homeTileMenuCacheKey(current.row.itemId),
                        )
                        if (cleared) {
                            publishTileMenuIconChange(homeShelf.refreshTiles) { core.repository.refreshShelfFaces() }
                            navigation.closeHomeTileMenu()
                        } else {
                            // a refused reset HOLDS THE MENU OPEN WITH A NOTE,
                            // same reused copy as onApplyPick's honest twin (:1791-1821).
                            val picker = current.phase as? HomeTileMenuPhase.ArtPick
                            navigation.homeTileMenu
                                ?.takeIf {
                                    it.row.itemId == current.row.itemId && it.phase is HomeTileMenuPhase.Applying
                                }
                                ?.let {
                                    navigation.homeTileMenu = current.copy(
                                        phase = picker?.copy(
                                            note = "Couldn’t save this icon art. Try again.",
                                        ) ?: HomeTileMenuPhase.ProviderUnavailable(
                                            "Couldn’t save this icon art. Try again.",
                                        ),
                                    )
                                }
                        }
                    }
                },
                // The SAME read-only RA card the Select shortcut opens — Edit adds a second doorway to
                // it, not a second implementation. Select's binding is untouched.
                onOpenAchievements = {
                    // The Library hero menu carries its own game (a Library game need not be a Home favourite), Home Edit asks the shelf.
                    homeTileMenuGame(current, homeShelf.gameForAchievements)
                        ?.let(achievements.state::openCardFromHome)
                },
                onLoadHero = {
                    homeQuickLaunchScope.launch {
                        // The EXACT emulated row, not just a title: the ScreenScraper query needs the
                        // real platformId for cross-system verification and the verified provider id.
                        val game = homeTileMenuGame(current, homeShelf.gameForAchievements)
                        if (game == null) {
                            // The row can disappear between opening Edit and resolving the exact GameRow.
                            // Never strand the modal on an infinite "Finding heroes…" phase.
                            navigation.homeTileMenu
                                ?.takeIf {
                                    it.row.itemId == current.row.itemId &&
                                        it.phase is HomeTileMenuPhase.LoadingHero
                                }
                                ?.let {
                                    navigation.homeTileMenu = it.copy(
                                        phase = HomeTileMenuPhase.ProviderUnavailable(
                                            "This game is no longer available in Favorites.",
                                        ),
                                    )
                                }
                            return@launch
                        }
                        val next = loadCustomHeroPhase(
                            context = core.context,
                            game = game,
                            steamGridDbKey = SteamGridDbKeyStore.resolvedKey(core.context),
                            screenScraperConnected = ConnectedArtStore.optedIn.value &&
                                ArtworkCredentialStore.hasHealthyCredentials(core.context),
                            candidateNames = ArtworkFetchController.providerSearchTitles(
                                core.context,
                                game.gameId.value,
                                game.title,
                            ),
                        )
                        navigation.homeTileMenu
                            ?.takeIf {
                                it.row.itemId == current.row.itemId &&
                                    it.phase is HomeTileMenuPhase.LoadingHero
                            }
                            ?.let { navigation.homeTileMenu = it.copy(phase = next) }
                    }
                },
                onApplyHero = { candidate ->
                    homeQuickLaunchScope.launch {
                        val emulated = current.row.itemId as? HomeShelfItemId.Emulated
                        // Atomic from the UI's side: the override flips only if the local asset
                        // landed, so a failed download leaves the previous hero untouched.
                        val applied = emulated != null && runCatching {
                            CustomHeroFetcher.applyHero(core.context, emulated.gameId, candidate)
                        }.getOrDefault(false)
                        if (applied) {
                            homeShelf.refreshTiles()
                            navigation.closeHomeTileMenu()
                        } else {
                            val picker = current.phase as? HomeTileMenuPhase.HeroPick
                            navigation.homeTileMenu
                                ?.takeIf {
                                    it.row.itemId == current.row.itemId && it.phase is HomeTileMenuPhase.Applying
                                }
                                ?.let {
                                    navigation.homeTileMenu = current.copy(
                                        phase = picker?.copy(
                                            note = "Couldn’t save this hero art. Try again.",
                                        ) ?: HomeTileMenuPhase.ProviderUnavailable(
                                            "Couldn’t save this hero art. Try again.",
                                        ),
                                    )
                                }
                        }
                    }
                },
                onImportHero = {
                    val phase = current.phase as? HomeTileMenuPhase.HeroPick
                    val emulated = current.row.itemId as? HomeShelfItemId.Emulated
                    if (phase != null && emulated != null) {
                        startImageImport(
                            HomeImageImportRequest(
                                owner = CustomEnrichPickerOwner.Home(current.row.itemId),
                                target = HomeImageImportTarget.Hero(emulated.gameId),
                                returnPhase = phase,
                            ),
                        )
                    }
                },
                onApplyUploadedHero = {
                    homeQuickLaunchScope.launch {
                        val phase = current.phase as? HomeTileMenuPhase.HeroPick
                        val emulated = current.row.itemId as? HomeShelfItemId.Emulated
                        val applied = emulated != null &&
                            CustomImageImportStore.activateHero(core.context, emulated.gameId)
                        if (applied) {
                            homeShelf.refreshTiles()
                            navigation.closeHomeTileMenu()
                        } else {
                            navigation.homeTileMenu
                                ?.takeIf { it.row.itemId == current.row.itemId }
                                ?.let { live ->
                                    navigation.homeTileMenu = live.copy(
                                        phase = phase?.copy(note = "The uploaded image is no longer available.")
                                            ?: HomeTileMenuPhase.ProviderUnavailable(
                                                "The uploaded image is no longer available.",
                                            ),
                                    )
                                }
                        }
                    }
                },
                onLoadClip = {
                    homeQuickLaunchScope.launch {
                        val game = homeTileMenuGame(current, homeShelf.gameForAchievements)
                        if (game == null) {
                            navigation.homeTileMenu
                                ?.takeIf {
                                    it.row.itemId == current.row.itemId &&
                                        it.phase is HomeTileMenuPhase.LoadingClip
                                }
                                ?.let {
                                    navigation.homeTileMenu = it.copy(
                                        phase = HomeTileMenuPhase.ProviderUnavailable(
                                            "This game is no longer available in Favorites.",
                                        ),
                                    )
                                }
                            return@launch
                        }
                        val next = loadCustomPreviewClipPhase(
                            context = core.context,
                            game = game,
                            screenScraperConnected = ConnectedArtStore.optedIn.value &&
                                ArtworkCredentialStore.hasHealthyCredentials(core.context),
                            candidateNames = ArtworkFetchController.providerSearchTitles(
                                core.context,
                                game.gameId.value,
                                game.title,
                            ),
                        )
                        navigation.homeTileMenu
                            ?.takeIf {
                                it.row.itemId == current.row.itemId &&
                                    it.phase is HomeTileMenuPhase.LoadingClip
                            }
                            ?.let { navigation.homeTileMenu = it.copy(phase = next) }
                    }
                },
                onApplyClip = { candidate ->
                    homeQuickLaunchScope.launch {
                        val emulated = current.row.itemId as? HomeShelfItemId.Emulated
                        val applied = emulated != null && runCatching {
                            CustomHeroFetcher.applyPreviewClip(core.context, emulated.gameId, candidate)
                        }.getOrDefault(false)
                        if (applied) {
                            homeShelf.refreshTiles()
                            navigation.closeHomeTileMenu()
                        } else {
                            val picker = current.phase as? HomeTileMenuPhase.ClipPick
                            navigation.homeTileMenu
                                ?.takeIf {
                                    it.row.itemId == current.row.itemId && it.phase is HomeTileMenuPhase.Applying
                                }
                                ?.let {
                                    navigation.homeTileMenu = current.copy(
                                        phase = picker?.copy(
                                            note = "Couldn’t save this Hero Motion. Try again.",
                                        ) ?: HomeTileMenuPhase.ProviderUnavailable(
                                            "Couldn’t save this Hero Motion. Try again.",
                                        ),
                                    )
                                }
                        }
                    }
                },
                onResetHero = {
                    homeQuickLaunchScope.launch {
                        val emulated = current.row.itemId as? HomeShelfItemId.Emulated
                        val cleared = emulated != null &&
                            CustomHeroStore.clearAsAutomatic(core.context, emulated.gameId)
                        if (cleared) {
                            homeShelf.refreshTiles()
                            navigation.closeHomeTileMenu()
                        } else {
                            // a refused reset HOLDS THE MENU OPEN WITH A NOTE,
                            // same reused copy as onApplyHero's honest twin (:1915-1943).
                            val picker = current.phase as? HomeTileMenuPhase.HeroPick
                            navigation.homeTileMenu
                                ?.takeIf {
                                    it.row.itemId == current.row.itemId && it.phase is HomeTileMenuPhase.Applying
                                }
                                ?.let {
                                    navigation.homeTileMenu = current.copy(
                                        phase = picker?.copy(
                                            note = "Couldn’t save this hero art. Try again.",
                                        ) ?: HomeTileMenuPhase.ProviderUnavailable(
                                            "Couldn’t save this hero art. Try again.",
                                        ),
                                    )
                                }
                        }
                    }
                },
                onResetClip = {
                    homeQuickLaunchScope.launch {
                        val emulated = current.row.itemId as? HomeShelfItemId.Emulated
                        val cleared = emulated != null && CustomHeroStore.clearPreviewClipAsAutomatic(
                            core.context,
                            homeTileHeroStoreId(current.row.itemId),
                        )
                        if (cleared) {
                            homeShelf.refreshTiles()
                            navigation.closeHomeTileMenu()
                        } else {
                            // a refused reset HOLDS THE MENU OPEN WITH A NOTE,
                            // same reused copy as onApplyClip's honest twin (:2016-2042).
                            val picker = current.phase as? HomeTileMenuPhase.ClipPick
                            navigation.homeTileMenu
                                ?.takeIf {
                                    it.row.itemId == current.row.itemId && it.phase is HomeTileMenuPhase.Applying
                                }
                                ?.let {
                                    navigation.homeTileMenu = current.copy(
                                        phase = picker?.copy(
                                            note = "Couldn’t save this Hero Motion. Try again.",
                                        ) ?: HomeTileMenuPhase.ProviderUnavailable(
                                            "Couldn’t save this Hero Motion. Try again.",
                                        ),
                                    )
                                }
                        }
                    }
                },
                collectionsFor = {
                    // Typed membership (v2): the row's own identity is the key, so Android rows get a
                    // truthful ✓ instead of the permanent "not a member" v1's emulated-only id forced.
                    homeShelf.tomeDocument.customTomes.map { collection ->
                        val member = current.row.itemId.toFavoriteMemberIdOrNull()
                        HomeTileCollectionEntry(
                            id = collection.id,
                            name = collection.label,
                            isMember = member != null && member in collection.members,
                        )
                    }
                },
                onToggleCollection = { tomeId ->
                    // O-7: the reducer already flipped this entry's ✓ optimistically (instant response
                    // for the common case — persist() is a local SharedPreferences commit, not network).
                    // homeTileMenuAfterToggleCollectionResult is the pure, independently-tested reversal:
                    // it reverts the flip and sets the note only if the save was refused AND this exact
                    // tile's menu (and CollectionPick phase) is still open — the same stale-write guard
                    // every other async leg in this block applies by hand.
                    homeShelf.toggleTomeMembership(tomeId, current.row.itemId) { saved ->
                        navigation.homeTileMenu?.let { latest ->
                            homeTileMenuAfterToggleCollectionResult(latest, current.row.itemId, tomeId, saved)
                                ?.let { navigation.homeTileMenu = it }
                        }
                    }
                },
                onRemoveFromTome = { tomeId ->
                    // Distinct action (closes the menu unconditionally); O-7 scoped this fix to the
                    // CollectionPick checkbox only. The same optimistic-without-revert shape here is a
                    // related, unresolved defect.
                    homeShelf.toggleTomeMembership(tomeId, current.row.itemId) {}
                },
                onOpenCustomEnrich = { kind, mediaState ->
                    buildHomeCustomEnrichPicker(
                        context = core.context,
                        current = current,
                        kind = kind,
                        mediaState = mediaState,
                        migrations = homeCustomEnrichMigrations,
                        scope = homeQuickLaunchScope,
                        update = { sessionId, transform -> updateHomeCustomEnrich(sessionId, transform) },
                    )
                },
                // the
                // same idempotent single-game fetch the seat's own direct Enrich tap uses
                // (onFetchArtForGame = bindings.artwork.state::fetchOne). sourceGame is already the exact
                // GameRow for LIBRARY_HERO (the only entry point that ever sets offerEnrich); the
                // gameForAchievements fallback matches every other async leg's row->GameRow resolution
                // above in case the row outlives the menu into a stale close.
                onEnrich = {
                    //  while the game-music download holds the shared artwork gate the
                    // fetch would return Busy and surface as the generic could-not card; say why instead. The
                    // menu's rows carry a label only, so the line arrives as a toast rather than under the row.
                    if (artworkEntryMayStart(ArtworkOperationGate.process.active.value)) {
                        homeTileMenuGame(current, homeShelf.gameForAchievements)
                            ?.let(artwork.state::fetchOne)
                    } else {
                        showMimirMessage(core.context, GameMusicDownloadingCopy, MimirMessageClass.Guidance)
                    }
                },
                // Settings › Music (2026-09-30), the per-game "Download music for this game": the engine's own entry point, by game
                // id, bypassing that game's cooldown and fetching no other art. it answers on Main's one message rail, where it
                // was asked (start, result, or why not). The Library hero menu carries the game itself, Home Edit its row's id.
                onDownloadGameMusic = {
                    (current.sourceGame?.gameId ?: (current.row.itemId as? HomeShelfItemId.Emulated)?.gameId)?.let { id ->
                        artwork.state.downloadGameMusicFromMenu(
                            gameId = id.value,
                            gameName = current.sourceGame?.title ?: current.row.title,
                            show = { notice -> showMimirMessage(core.context, notice.text, notice.messageClass, notice.supportingText, holdMs = notice.holdMs) },
                        )
                    }
                },
            )
            }
        }
    }
    // S-2 : opens on libraryHeroPickerMenuState's
    // top-level menu (Customize media / Add to Tome / Achievements) and STOPS there -- showing that menu
    // and letting the player choose from it is the entire feature, so nothing here may auto-confirm past
    // it, ever.
    //
    // This used to auto-confirm straight past the (then-landing) media sub-menu whenever it had only one
    // real option -- no intermediate Edit/Customize page for an entry with one option, the same principle the
    // extract chooser uses. That block is gone, not merely disarmed: libraryHeroPickerRow always builds
    // HomeShelfItemId.Emulated(game.gameId) (GameRow -- core-model's libraryRepository.GameRow -- has no
    // package-name/native variant, only ever emulated titles), so homeTileCustomEnrichActions' LIBRARY_HERO
    // branch always adds CUSTOM_PREVIEW_CLIP alongside CUSTOM_HERO here; the guarded block was already
    // unreachable (pinned by openLibraryHeroPickerCustomEnrichActionsAreAlwaysTwoWide in
    // LibraryHeroPickerTest.kt). Keeping a dead auto-confirm around risked a silent S-2 regression the day
    // that invariant stopped holding: reintroducing it would skip the top-level menu on first open, before
    // the player ever saw it. If a genuinely one-option row needs that shortcut again, it belongs one level
    // deeper -- inside the CustomEnrich phase's own CONFIRM handling in HomeTileMenuOverlay.kt -- which can
    // never skip this top-level menu.
    //
    //  With no Enrich
    // pill on a compact screen, Y opens this menu for EVERY game, and a game the provider has never answered finds
    // Enrich leading it (libraryHeroPickerMenuState). This used to open nothing for such a game, because every
    // caller sent it to the pill's Enrich first; standard screens still do (LibraryDispatch's Y and the seat),
    // so only compact reaches here with one.
    val openLibraryHeroPicker: (GameRow) -> Unit = { game ->
        navigation.openHomeTileMenu(libraryHeroPickerMenuState(game))
    }
    val onHomeTomeLibraryAction: (ControllerAction) -> Unit = { action ->
        navigation.homeTomeLibrary?.let { current ->
            val reduced = reduceHomeTomeLibrary(
                state = current,
                action = action,
                onOpen = { tomeId ->
                    // THE COVER IS STAMPED BEFORE THE FOLIO MOUNTS, and the order is the whole point.
                    //
                    // The reducer marks `handingOff` on the state it RETURNS, which the caller assigns
                    // after this lambda has already mounted the Folio. So at the one composition where
                    // the Folio's identity changes -- the composition whose `remember` decides whether
                    // it may skip its entrance -- the browser still read as not holding, and the Folio
                    // ramped anyway. The hold worked; the snap silently did not.
                    //
                    // Stamping here makes the cover true before the thing it covers exists. Same class
                    // as the post-composition stamp that lost input bursts: write it at the hop that
                    // consumes it, not on the way back out.
                    navigation.homeTomeLibrary = current.copy(handingOff = true)
                    if (tomeId == TomeId.HomeOverview) {
                        homeShelf.openHomeOverview(
                            HomeTomeOrigin.TOME_LIBRARY,
                            current.focusedIndex,
                            current.firstVisibleIndex,
                        )
                    } else {
                        homeShelf.openTome(
                            tomeId,
                            HomeTomeOrigin.TOME_LIBRARY,
                            current.focusedIndex,
                            current.firstVisibleIndex,
                        )
                        true
                    }
                },
                onTogglePinned = { tomeId, pinned ->
                    val expectedSession = current.sessionToken
                    homeShelf.setTomePinned(tomeId, pinned) settled@{ saved ->
                        // Pinning is an in-browser mutation, never a hidden surface switch. Rebuild the
                        // ordered browser from durable truth. Never resurrect a closed/reopened browser;
                        // the acted Tome remains the focused identity while its section/seat changes.
                        if (core.focusAuthority.state.value.scope != HomeTomeLibraryScope) return@settled
                        val live = navigation.homeTomeLibrary
                            ?.takeIf { it.sessionToken === expectedSession }
                            ?: return@settled
                        val eventId = (live.pinFeedback?.eventId ?: 0L) + 1L
                        if (!saved) {
                            navigation.homeTomeLibrary = live.copy(
                                pinMutation = null,
                                pinFeedback = HomeTomePinFeedback(eventId, tomeId, pinned, saved = false),
                            )
                            return@settled
                        }
                        val entries = homeTomeLibraryEntries(
                            HomeTomeStore.document.value,
                            homeShelf.availableTomes,
                            homeShelf.homeOverviewTome,
                        )
                        val reconciled = reconcilePinnedHomeTomeLibrary(
                            live = navigation.homeTomeLibrary,
                            expectedSession = expectedSession,
                            entries = entries,
                            actedTomeId = tomeId,
                        ) ?: return@settled
                        navigation.homeTomeLibrary = reconciled.copy(
                            pinMutation = null,
                            pinFeedback = HomeTomePinFeedback(eventId, tomeId, pinned, saved = true),
                        )
                        reconciled.entries.getOrNull(reconciled.focusedIndex)
                            ?.let(::homeTomeLibraryFocusKey)
                            ?.let { key ->
                            core.focusAuthority.rememberFocusForScope(HomeTomeLibraryScope, key)
                            core.focusAuthority.requestFocus(key)
                        }
                    }
                },
                onDismiss = homeShelf.restoreRootEntry,
                onDelete = { tomeId ->
                    homeShelf.deleteTome(tomeId) { deleted ->
                        navigation.homeTomeLibrary?.let { live ->
                            if (!deleted) {
                                navigation.homeTomeLibrary = live.copy(
                                    deletePending = false,
                                    confirmDelete = false,
                                    draftError = "Couldn’t delete Tome",
                                )
                            } else {
                                val deadIndex = live.entries.indexOfFirst { entry ->
                                    entry is HomeTomeLibraryEntry.Tome && entry.model.tomeId == tomeId
                                }
                                val remaining = live.entries.filterNot { entry ->
                                    entry is HomeTomeLibraryEntry.Tome && entry.model.tomeId == tomeId
                                }
                                val nearest = deadIndex.coerceIn(0, remaining.lastIndex.coerceAtLeast(0))
                                navigation.homeTomeLibrary = live.copy(
                                    entries = remaining,
                                    focusedIndex = nearest,
                                    settledFocusedIndex = null,
                                    firstVisibleIndex = live.firstVisibleIndex.coerceAtMost(nearest),
                                    draft = null,
                                    editingTomeId = null,
                                    draftError = null,
                                    confirmDelete = false,
                                    deletePending = false,
                                )
                            }
                        }
                    }
                },
            )
            navigation.homeTomeLibrary = reduced
            reduced?.entries?.getOrNull(reduced.focusedIndex)?.let { entry ->
                val focusKey = homeTomeLibraryFocusKey(entry)
                core.focusAuthority.rememberFocusForScope(HomeTomeLibraryScope, focusKey)
                core.focusAuthority.requestFocus(focusKey)
            }
        }
    }
    // Y → change platform icon art (SteamGridDB, then ScreenScraper system media) + revert.
    // Mirrors the Home game-tile flow; the
    // picked art is written to the shared sgdb2 store under a platformKey and surfaced to the Library shelf via
    // PlatformTileArtStore. SteamGridDB stays opt-in / key-gated / local (posture).
    // Hydrate from the repository flow itself, whose first emission is authoritative. The UI-facing
    // StateFlow deliberately starts with emptyList(), so keying this pass to library.platforms could
    // release the default glyph for one frame before a real non-empty library emission arrived.
    LaunchedEffect(core.repository) {
        hydratePersistedPlatformArt(
            platforms = core.repository.observePlatforms(),
            refresh = { refreshOnePlatformArt(it) },
            markHydrated = { PlatformTileArtStore.hydrated.value = true },
        )
    }
    val onPlatformTileMenuAction: (ControllerAction) -> Unit = { action ->
        navigation.platformTileMenu?.let { current ->
            val customPicker = (current.phase as? HomeTileMenuPhase.CustomEnrichPicker)?.picker
            if (customPicker != null) {
                handleCustomEnrichTransition(
                    CustomEnrichPickerOwner.Platform(current.platformId),
                    null,
                    navigation::closePlatformTileMenu,
                    customPicker,
                    reduceHomeCustomEnrich(core.focusAuthority, customPicker, action),
                )
            } else navigation.platformTileMenu = reducePlatformArtMenu(
                state = current,
                action = action,
                onLoadArt = {
                    homeQuickLaunchScope.launch {
                        var candidates = SteamGridDbTileFetcher.platformCandidatesFor(
                            context = core.context,
                            apiKey = SteamGridDbKeyStore.resolvedKey(core.context),
                            // gather icons across canonical name angles ("Genesis"/"Mega Drive", …).
                            platformIdValue = current.platformId.value,
                            fallbackLabel = current.label,
                        )
                        if (candidates.isEmpty()) {
                            ArtworkConnectController.attach(core.context)
                            candidates = SteamGridDbTileFetcher.screenScraperPlatformCandidatesFor(
                                context = core.context,
                                credentialedInputs = ArtworkConnectController.source
                                    .systemImageInputs(current.platformId),
                            )
                        }
                        // The platform menu has its own "Revert to default" ROW, so it builds no
                        // UseDefault slot — every choice here is a real candidate.
                        val choices = candidates.map { HomeTileArtChoice.Art(it) }
                        navigation.platformTileMenu
                            ?.takeIf {
                                it.platformId == current.platformId && it.phase is HomeTileMenuPhase.LoadingArt
                            }
                            ?.let { navigation.platformTileMenu = it.copy(phase = HomeTileMenuPhase.ArtPick(choices)) }
                    }
                },
                onApplyPick = { candidate, index ->
                    homeQuickLaunchScope.launch {
                        val applied = SteamGridDbTileFetcher.applyPick(
                            context = core.context,
                            cacheKey = SteamGridDbTileFetcher.platformKey(current.platformId),
                            candidate = candidate,
                            index = index,
                        )
                        if (applied) {
                            refreshOnePlatformArt(
                                platformId = current.platformId,
                                contentChanged = true,
                            )
                            navigation.closePlatformTileMenu()
                        } else {
                            // a refused apply HOLDS THE MENU OPEN WITH A NOTE
                            // instead of closing with the success grammar — the honest twin of the Home
                            // tile's onApplyPick (:1791-1821), same reused copy.
                            val picker = current.phase as? HomeTileMenuPhase.ArtPick
                            navigation.platformTileMenu
                                ?.takeIf {
                                    it.platformId == current.platformId && it.phase is HomeTileMenuPhase.Applying
                                }
                                ?.let {
                                    navigation.platformTileMenu = current.copy(
                                        phase = picker?.copy(
                                            note = "Couldn’t save this icon art. Try again.",
                                        ) ?: HomeTileMenuPhase.ProviderUnavailable(
                                            "Couldn’t save this icon art. Try again.",
                                        ),
                                    )
                                }
                        }
                    }
                },
                onRevert = {
                    homeQuickLaunchScope.launch {
                        val cleared = SteamGridDbTileFetcher.clearPickAsAutomatic(
                            core.context,
                            SteamGridDbTileFetcher.platformKey(current.platformId),
                        )
                        if (cleared) {
                            refreshOnePlatformArt(
                                platformId = current.platformId,
                                contentChanged = true,
                            )
                            navigation.closePlatformTileMenu()
                        } else {
                            // same as onApplyPick above — the reducer now keeps
                            // this open (Applying) instead of closing before this result is known, so a
                            // refusal here has a surface to write the note to at all.
                            navigation.platformTileMenu
                                ?.takeIf {
                                    it.platformId == current.platformId && it.phase is HomeTileMenuPhase.Applying
                                }
                                ?.let {
                                    navigation.platformTileMenu = current.copy(
                                        phase = HomeTileMenuPhase.ProviderUnavailable(
                                            "Couldn’t save this icon art. Try again.",
                                        ),
                                    )
                                }
                        }
                    }
                },
            )
        }
    }
    // `createMimirOverlayCoordinator` is a live dispatch projection and is rebuilt by recomposition.
    // Picker readiness tokens are bound to their owner, so retain that owner for the composable lifetime
    // rather than mounting a token on an instance that the next projection discards.
    val homeQuickLaunchPickerOwnership = remember { HomeQuickLaunchPickerOwnership() }
    val folderBrowser by FileBrowserStore.controller.collectAsState()
    val overlays = createMimirOverlayCoordinator(
        folderBrowser = { folderBrowser },
        // Product review 2026-09-28 (F3): NEEDS SETUP opens Settings on the RetroAchievements row. B from there
        // returns to the game, as any other Settings visit from here does.
        onOpenRetroAchievementsSettings = {
            achievements.state.dismissCard()
            // Over Home's launch card the visit holds the card, as START does, so B returns to it; left on the stack it would answer (and fail) every press in Settings.
            homeLaunchCard.holdForSettingsVisit(navigation.destination) { navigation.settingsFromHomeLaunchCard = true }
            library.prepareDetailDescriptionReturn()
            navigation.openRetroAchievementsSettings(library.detailSession)
        },
        core = core,
        settings = settings,
        migration = migration,
        artwork = artwork,
        achievements = achievements,
        returnCoverVisible = {
            primaryReturnRestoring(
                native = nativeShelf.launchReturnRestoring,
                library = navigation.libraryLaunchReturnRestoring,
                    home = homeShelf.launchReturnRestoring,
            )
        },
        companionPermissionVisible = { CompanionRecentsState.ui.value.awaitingOverlayPermission },
        homeQuickLaunchOpen = { navigation.homeQuickLaunchOpen },
        homeQuickLaunchSlots = { homeQuickLaunchSlotsUi },
        onHomeQuickLaunch = { packageName -> launchHomeQuickPackage(packageName, false) },
        onHomeQuickLaunchGeneral = navigation::openHomeQuickLaunchGeneral,
        onHomeQuickLaunchEdit = navigation::editHomeQuickLaunchSlot,
        onHomeQuickLaunchDismiss = navigation::closeHomeQuickLaunch,
        homeQuickLaunchPickerMode = { navigation.homeQuickLaunchPickerMode },
        homeQuickLaunchPickerItems = { homeQuickLaunchPickerItemsUi },
        onHomeQuickLaunchPickerLaunch = { packageName -> launchHomeQuickPackage(packageName, true) },
        onHomeQuickLaunchPickerAssign = assignHomeQuickLaunchSlot,
        onHomeQuickLaunchPickerClear = clearHomeQuickLaunchSlot,
        onHomeQuickLaunchPickerDismiss = navigation::closeHomeQuickLaunchPicker,
        homeTileMenuState = { navigation.homeTileMenu },
        onHomeTileMenuAction = onHomeTileMenuAction,
        gameTitleEditOpen = { navigation.gameTitleEdit != null },
        onCloseGameTitleEdit = navigation::closeGameTitleEdit,
        homeTomeLibraryState = { navigation.homeTomeLibrary },
        onHomeTomeLibraryAction = onHomeTomeLibraryAction,
        platformTileMenuState = { navigation.platformTileMenu },
        onPlatformTileMenuAction = onPlatformTileMenuAction,
        // During close the card is pixel-only. Removing it from the logical overlay stack immediately lets
        // carousel input route while AnimatedVisibility retains the outgoing frame.
        homeLaunchCardOpen = {
            homeLaunchCardOverlayOpen(
                hasSession = homeLaunchCard.session != null,
                closing = homeLaunchCard.closing,
                held = homeLaunchCard.suspendedForSettings,
                destination = navigation.destination,
            )
        },
        homeLaunchCardOpenWithOpen = { homeLaunchCard.openWithOpen },
        homeLaunchChoiceOpen = { homeLaunchCard.launchChoiceOpen },
        onHomeLaunchCardAction = { action ->
            homeLaunchCard.dispatch(
                action = action,
                onPlay = { row -> homeShelf.launch(row) },
                onPlayEmulated = { row, game ->
                    // Home's card asks first when the group offers a choice, exactly
                    // as the Library's does. `openLaunchChoice()` returns false when there is nothing to
                    // choose between, and then this is the launch it has always been.
                    if (!homeLaunchCard.openLaunchChoice()) {
                        homeShelf.launchFromLaunchCard(
                            row,
                            game,
                            { state -> homeLaunchCard.setLaunchState(state, game.gameId) },
                            homeLaunchCard::close,
                            LaunchChoice.GroupDefault,
                        )
                    }
                },
                onEdit = { row ->
                    navigation.openHomeTileMenu(
                        homeTileMenuState(
                            row = row,
                            // The card's own game, else the shelf's.
                            game = homeLaunchCard.session?.game ?: homeShelf.gameForAchievements(row),
                            removable = homeShelf.isMember(row.itemId),
                            activeCustomTomeId = homeShelf.tomeSession?.tomeId as? TomeId.Custom,
                        ),
                    )
                },
                onEditTitle = { game ->
                    navigation.openGameTitleEdit(
                        gameId = game.gameId,
                        currentTitle = game.title,
                        hasOverride = game.hasTitleOverride,
                    )
                },
                onAchievements = achievements.state::openCardFromHome,
            )
        },
        onHomeLaunchCardOpenWithAction = homeLaunchCard::dispatchOpenWith,
        onHomeLaunchChoiceAction = { action ->
            when (action) {
                ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                    core.focusAuthority.moveFocus(action)
                    true
                }
                ControllerAction.CONFIRM -> {
                    homeLaunchCard.commitFocusedLaunchChoice()
                    true
                }
                ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK, ControllerAction.NAV_LEFT -> {
                    homeLaunchCard.dismissLaunchChoice()
                    true
                }
                // Hard modal, exactly as OpenWithScope is: swallow the rest so nothing reaches the card.
                else -> true
            }
        },
        homeQuickLaunchPickerOwnership = homeQuickLaunchPickerOwnership,
    )
    // F6/F7: the new-game artwork question waits for a resting, quiet Home or Library (newGameEnrichOfferCovered, watchNewGameEnrichCover). Read through
    // snapshotFlow so a change of cover does not recompose this host; overlay flags the snapshot takes from a StateFlow's value are not snapshot reads.
    val currentOverlaysForEnrichOffer by rememberUpdatedState(overlays)
    val focusForEnrichOffer = core.focusAuthority.state.collectAsState()
    val enrichKeyboardOpen by core.libraryViewModel.keyboardOpen.collectAsState()
    val enrichSearchOpen by core.libraryViewModel.librarySearchOpen.collectAsState()
    LaunchedEffect(core.focusAuthority) {
        watchNewGameEnrichCover {
            val focus = focusForEnrichOffer.value
            NewGameEnrichCoverSample(
                newGameEnrichOfferCovered(currentOverlaysForEnrichOffer.stack.entries, focus.scope, navigation.destination, enrichKeyboardOpen || enrichSearchOpen),
                focus.focused,
            )
        }
    }
    // Debug-only nav-hooks lane (perf gate): MainActivity has no stable reference to `navigation`/
    // `settings`/`overlays` — they are `remember`ed here, not on the Activity — so its gated `mimir.*`
    // hooks publish on DebugNavigationBridge instead of calling these coordinators directly. This
    // consumer always exists, in every build variant, exactly like the MButtonNavigationCommands consumer
    // below it: gating the consumer instead of (or in addition to) the producer would be a second, weaker
    // security boundary. In a release build DebugIntentHookGate keeps MainActivity from ever calling
    // `publish`, so `pending` never leaves null and this effect never has anything to dispatch — see
    // DebugNavigationBridge's doc comment for the full reasoning.
    val debugNavigationRequest by DebugNavigationBridge.pending.collectAsState()
    LaunchedEffect(debugNavigationRequest?.id) {
        val request = debugNavigationRequest ?: return@LaunchedEffect
        when (request) {
            is DebugNavigationRequest.LaunchGame -> {
                // The row is re-read here rather than carried in the request, so the probe launches
                // exactly what the library holds. `LibraryViewModel.launch` is the Confirm-press seam:
                // it emits `LibraryGameSessionEvent.Preparing` into `MimirSessionAuthority` BEFORE
                // dispatching, which is the event the whole companion attribution chain hangs off.
                val row = runCatching {
                    core.repository.observeGames(request.platformId).first()
                        .firstOrNull { it.gameId == request.gameId }
                }.getOrNull()
                if (row == null) {
                    Log.w(
                        "MimirLaunchProbe",
                        "launchGame no row gameId=${request.gameId.value} " +
                            "platform=${request.platformId.value}",
                    )
                } else {
                    core.libraryViewModel.launch(row)
                    Log.i(
                        "MimirLaunchProbe",
                        "launchGame dispatched '${row.title}' gameId=${row.gameId.value} " +
                            "platform=${row.platformId.value} via=LibraryViewModel.launch",
                    )
                }
            }
            is DebugNavigationRequest.OpenGamelist -> {
                navigation.openPlatformInLibrary(request.platformId)
                Log.i(
                    "MimirNavProbe",
                    "openGamelist dispatched platform=${request.platformId.value} " +
                        "destination=${navigation.destination}",
                )
            }
            is DebugNavigationRequest.ShelfQuickAction -> {
                val before = navigation.destination
                navigation.onShelfQuickAction(request.action)
                Log.i(
                    "MimirNavProbe",
                    "shelfAction dispatched action=${request.action} " +
                        "destination=$before -> ${navigation.destination}",
                )
            }
            is DebugNavigationRequest.OpenSettingsRow -> {
                val beforeTop = overlays.top
                settings.onRowActivate(request.rowId)
                Log.i(
                    "MimirNavProbe",
                    "openOverlay dispatched rowId=${request.rowId} beforeTop=$beforeTop afterTop=${overlays.top}",
                )
            }
            is DebugNavigationRequest.DismissTopOverlay -> {
                val beforeTop = overlays.top
                val consumed = overlays.dispatch(ControllerAction.CANCEL)
                Log.i(
                    "MimirNavProbe",
                    "dismissTopOverlay dispatched beforeTop=$beforeTop consumed=$consumed afterTop=${overlays.top}",
                )
            }
            is DebugNavigationRequest.OpenFiles -> {
                // Diagnostic only -- misses the pre-mount window FileBrowserStore.open's own no-op guard
                // also checks (`session != null`, set synchronously; `controller` mounts later, after an
                // async ROM-folder refresh -- FileBrowserStore.kt:226). A second fire landing inside that
                // narrow window still no-ops even though this reads false.
                val controllerMounted = FileBrowserStore.controller.value != null
                // Mirrors MimirSettingsCoordinator.launchSourcePicker's own check (MimirSettingsCoordinator.kt:194)
                // -- purely diagnostic here too: this hook opens regardless, see maybeOpenFiles's KDoc.
                val allFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
                FileBrowserStore.open(
                    context = core.context,
                    authority = core.focusAuthority,
                    mode = request.mode,
                    // Reachability proof only, like every DebugNavigationRequest dispatch above -- opens
                    // the REAL screen through the REAL FileBrowserStore.open, but does not replicate
                    // MimirSettingsCoordinator.launchSourcePicker's onChoose (library.addSource(...)): a QA
                    // lane driving this over adb must not be able to blind-mutate ROM sources. In PICK the
                    // commit affordance still renders (FileBrowserController's own mode-driven UI, not this
                    // hook's doing); FileBrowserCommand.Commit closes the browser BEFORE onChoose runs
                    // regardless of what onChoose does (MimirSettingsCoordinator.kt:221), so pressing it
                    // here closes the browser, then no-ops.
                    onChoose = {},
                    // Extract/Verify ARE live in both modes. Both production callers give these a
                    // player-facing Toast specifically because a silent result was a real, caught
                    // regression (MimirSettingsCoordinator.kt / AppBrightnessCoordinator.kt's own comments
                    // on this point) -- logging instead of Toasting keeps that same "never silent" property
                    // observable to a QA lane reading logcat, without popping UI a proof hook has no
                    // business showing.
                    onExtracted = { archivePath, destination, count ->
                        Log.i(
                            "MimirNavProbe",
                            "openFiles extracted archive=${File(archivePath).name} " +
                                "destination=${File(destination).name} count=$count",
                        )
                    },
                    // §6.2 : reachability proof for the two
                    // sinks Move/Copy just gained, same "log, don't Toast" posture as onExtracted/
                    // onChecksum above -- this hook can open MANAGE itself (`mimir.openFiles manage`,
                    // see parseOpenFilesMode), which is exactly how the spec's own M2/M3 device
                    // measurements are meant to be driven. `label` is already a display string (a
                    // filename or "N files"), not a path, so it is logged as-is -- unlike `archivePath`/
                    // `destination` above, it needs no `File(...).name` truncation.
                    // `sources` is logged as a COUNT, not as paths: this probe's whole value is being
                    // readable in a device log, and a multi-path move would otherwise dump absolute
                    // paths into logcat for a line nobody reads that far across.
                    //
                    // R1 IS NOT WIRED ON THIS ROUTE, and the log says so on purpose. The targeted
                    // rescan lives in AppBrightnessCoordinator.kickTransferRescan, on the Utility
                    // "Files" path -- this hook is a reachability probe, not a second product entry
                    // point, and giving it its own debounce state would be a second source of truth for
                    // when a scan fires. The cost is that a move driven through `mimir.openFiles manage`
                    // leaves the library untouched, which looks EXACTLY like R1 being broken. M5 (spec
                    // §11) is the measurement that certifies the stale-tile chain, so it must be driven
                    // through Utility through the product interaction; run it here and it will manufacture
                    // a refutation of a feature that works. An instrument that cannot see the thing has
                    // to say so, rather than report a clean trial.
                    onMoved = { label, sources, destination, count ->
                        Log.i(
                            "MimirNavProbe",
                            "openFiles moved label=$label sources=${sources.size} " +
                                "destination=${File(destination).name} count=$count " +
                                "rescan=NOT_WIRED_ON_PROBE_ROUTE",
                        )
                    },
                    onCopied = { label, sources, destination, count ->
                        Log.i(
                            "MimirNavProbe",
                            "openFiles copied label=$label sources=${sources.size} " +
                                "destination=${File(destination).name} count=$count",
                        )
                    },
                    onExtractFailed = { archivePath, error ->
                        Log.e("MimirNavProbe", "openFiles extract failed archive=${File(archivePath).name}", error)
                    },
                    onChecksum = { path, digest ->
                        Log.i(
                            "MimirNavProbe",
                            "openFiles checksum path=${File(path).name} digest=${digest.take(12)}",
                        )
                    },
                    onChecksumFailed = { path, error ->
                        Log.e("MimirNavProbe", "openFiles checksum failed path=${File(path).name}", error)
                    },
                    // classifyPlatform stays defaulted (null) -- it only feeds tome/file glyphs and the
                    // inspector's "Launches" row, not a silent-failure class the Toast precedent above was
                    // written for.
                )
                Log.i(
                    "MimirNavProbe",
                    "openFiles dispatched mode=${request.mode} controllerMounted=$controllerMounted " +
                        "allFilesAccess=$allFilesAccess",
                )
            }
        }
        DebugNavigationBridge.acknowledge(request.id)
    }
    LaunchedEffect(mButtonNavigationRequest?.id) {
        val request = mButtonNavigationRequest ?: return@LaunchedEffect
        if (
            request.command == MButtonPrimaryCommand.SETTINGS &&
            navigation.destination == MimirDestination.SETTINGS
        ) {
            MButtonNavigationCommands.acknowledge(request.id)
            return@LaunchedEffect
        }
        if (request.command == MButtonPrimaryCommand.QUICK_LAUNCH) {
            // Quick Launch opens IN PLACE — the panel drops over
            // whatever surface is live and closing it returns there (the shelf quick rail's
            // long-standing behaviour). The settle sequence is the old navigate path's clearing
            // minus only the navigation: the 2026-08-11 review confirmed that clearing was
            // load-bearing for opening the panel safely, not just for going Home. Sequence,
            // ordering, and refusal semantics live in executeQuickLaunchInPlace (JVM-tested).
            val quickLaunchOutcome = executeQuickLaunchInPlace(
                panelOpen = navigation.homeQuickLaunchOpen,
                startupOriginApplied = navigation.startupOriginApplied,
                closePicker = navigation::closeHomeQuickLaunchPicker,
                // The sweep never sends CANCEL to a kept surface (the folder browser reads it as "up one
                // directory"): walk-32k, the press at depth in Files moved the browser and then refused.
                sweepConflictingOverlays = {
                    overlays.dismissConflictingModals(
                        stopAt = MimirOverlayKind.HOME_QUICK_LAUNCH,
                        keep = ::mimirOverlayStaysUnderQuickLaunch,
                    )
                },
                remainingObstruction = {
                    overlays.top?.takeIf(::mimirOverlayOutranksQuickLaunch)
                },
                keptSurfaceLive = {
                    overlays.stack.entries.any(::mimirOverlayStaysUnderQuickLaunch)
                },
                hardCloseTransientMenus = {
                    navigation.homeTileMenu = null
                    navigation.homeTomeLibrary = null
                    navigation.platformTileMenu = null
                },
                cancelTomeReturn = homeShelf.cancelTomeReturn,
                disarmLaunchReturns = {
                    navigation.disarmLibraryLaunchReturn()
                    core.libraryViewModel.clearPendingLaunchReturnTarget()
                    nativeShelf.supersedeLaunchReturn()
                },
                closeUtilityPanel = core.brightness::close,
                openPanel = navigation::openHomeQuickLaunch,
            )
            // N5 (2026-08-12): a refused press used to vanish in total silence — the physical M
            // button visibly did nothing. It toasted one shared "try again in a moment" for both
            // REFUSED_* arms, which walk-32k showed to be false for one of them: an obstruction
            // the player has to finish or close is not cured by retrying. So the two arms no longer
            // share a sentence. A refusal leaves its obstruction on top, so it is read here.
            when (quickLaunchOutcome) {
                QuickLaunchInPlaceOutcome.REFUSED_STARTUP_RESTORE ->
                    showMimirMessage(core.context, QuickLaunchNotReadyCopy)
                QuickLaunchInPlaceOutcome.REFUSED_OBSTRUCTED ->
                    showMimirMessage(
                        core.context,
                        quickLaunchObstructionCopy(overlays.top),
                    )
                QuickLaunchInPlaceOutcome.OPENED, QuickLaunchInPlaceOutcome.RECOVERED_EXISTING -> Unit
            }
            MButtonNavigationCommands.acknowledge(request.id)
            return@LaunchedEffect
        }
        if (request.command == MButtonPrimaryCommand.UTILITY ||
            request.command == MButtonPrimaryCommand.TWEAKER
        ) {
            // Utility and Tweaker are overlays, not navigation: dismiss only conflicting modals and keep destination,
            // Tome session, launch-return anchors, and focus intact.
            overlays.dismissConflictingModals()
            if (request.command == MButtonPrimaryCommand.TWEAKER && BuildConfig.DEBUG) {
                Log.i(
                    "MimirMButton",
                    "coordinator open request=${request.id} command=TWEAKER " +
                        "target=${core.brightness.presentationTarget} surface=${core.brightness.surface} " +
                        "isOpen=${core.brightness.isOpen}",
                )
            }
            core.brightness.open(
                if (request.command == MButtonPrimaryCommand.TWEAKER) {
                    UtilitySurface.TWEAKER
                } else {
                    UtilitySurface.UTILITY
                },
            )
            if (request.command == MButtonPrimaryCommand.TWEAKER && BuildConfig.DEBUG) {
                Log.i(
                    "MimirMButton",
                    "coordinator opened request=${request.id} command=TWEAKER " +
                        "target=${core.brightness.presentationTarget} surface=${core.brightness.surface} " +
                        "isOpen=${core.brightness.isOpen}",
                )
            }
            MButtonNavigationCommands.acknowledge(request.id)
            if (request.command == MButtonPrimaryCommand.TWEAKER && BuildConfig.DEBUG) {
                Log.i("MimirMButton", "coordinator ack request=${request.id} command=TWEAKER")
            }
        } else {
            runPrimaryMRequestAfterTomeCancel(homeShelf.cancelTomeReturn) {
                // Use each overlay's own ruled Back path, from the top down, so Home never arrives beneath a stale
                // picker/card. A restoration cover is structural and self-expiring; routing may settle beneath it.
                overlays.dismissConflictingModals()
                core.brightness.close()
                nativeShelf.supersedeLaunchReturn()
                // A Folio is a Home child destination, not a generic overlay. Close its typed session before
                // the global M command clears navigation state so no hidden Folio scope survives under Home.
                homeShelf.closeTome()
                if (request.command == MButtonPrimaryCommand.SETTINGS) {
                    library.prepareDetailDescriptionReturn()
                    navigation.openSettingsFromCurrent(library.detailSession)
                } else {
                    navigation.applyMButtonHomeNavigation()
                }
                MButtonNavigationCommands.acknowledge(request.id)
            }
        }
    }
    MimirColdStartTrace.markOnce("span coordinator body done")
    val bindings = MimirAppBindings(
        core = core,
        library = library,
        nativeShelf = nativeShelf,
        navigation = navigation,
        settingsRead = settingsRead,
        artwork = artwork,
        achievements = achievements,
        onboarding = onboarding,
        migration = migration,
        settings = settings,
        homeShelf = homeShelf,
        homeLaunchCard = homeLaunchCard,
        overlays = overlays,
        detailActionState = detailActionState,
        homeQuickLaunch = HomeQuickLaunchBinding(
            slots = homeQuickLaunchSlotsUi,
            openEpoch = navigation.homeQuickLaunchEpoch,
            pickerMode = homeQuickLaunchPickerMode,
            pickerItems = homeQuickLaunchPickerItemsUi,
            onLaunch = { packageName -> launchHomeQuickPackage(packageName, false) },
            onPickerTouchLaunch = { packageName -> launchHomeQuickPackage(packageName, true) },
            onOpenGeneral = navigation::openHomeQuickLaunchGeneral,
            onEdit = navigation::editHomeQuickLaunchSlot,
            onAssign = assignHomeQuickLaunchSlot,
            onClear = clearHomeQuickLaunchSlot,
            onPickerDismiss = navigation::closeHomeQuickLaunchPicker,
            onDismiss = navigation::closeHomeQuickLaunch,
        ),
        onOpenLibraryHeroPicker = openLibraryHeroPicker,
        mediaApplyEpoch = mediaApplyEpoch,
    )

    LaunchedEffect(
        homeShelf.tomeReplay?.generation,
        homeShelf.rootProjectionSettled,
        homeShelf.entries,
    ) {
        val replay = homeShelf.tomeReplay ?: return@LaunchedEffect
        if (navigation.destination != MimirDestination.HOME) {
            homeShelf.cancelTomeReturn()
            return@LaunchedEffect
        }
        if (!homeShelf.rootProjectionSettled) return@LaunchedEffect
        val actions = homeShelf.consumeTomeReplay(replay.generation)
        actions.forEach { deferred ->
            if (navigation.destination != MimirDestination.HOME) return@forEach
            dispatchMimirInput(bindings, deferred)
        }
    }

    MimirNavigationEffects(core, library, nativeShelf, navigation)
    MimirAppLifecycleEffect(bindings)
    MimirCompanionEffects(
        bindings = bindings,
        companionDisplayActive = companionDisplayActive,
        launchQuickPackageOnMain = { packageName, fromGeneral, noAnimation ->
            launchHomeQuickPackageOnPrimary(packageName, fromGeneral, CompanionOverlayRole.SECONDARY_COMPANION, noAnimation)
        },
    )
    InstallMimirInputHandler(inputRouter, bindings)
    MimirColdStartTrace.markOnce("span app effects done")
    MimirAppHost(bindings, interactionMotionGate)
}

@Composable
private fun InstallMimirInputHandler(
    inputRouter: InputRouter,
    bindings: MimirAppBindings,
) {
    val currentBindings = rememberUpdatedState(bindings)
    remember(inputRouter, bindings.core.focusAuthority) {
        inputRouter.handler = { action -> dispatchMimirInput(currentBindings.value, action) }
        // Walk-32b: while the on-screen keyboard owns focus, its A waits for the frame's hat input and keeps press order.
        inputRouter.confirmWaitsForPadFrame = {
            mainPadAWaitsForItsFrame(currentBindings.value.core.focusAuthority.state.value.scope)
        }
        // An A held for the achievement page's frame is dropped if the card closed (an OS Back, a dismiss) or its page left before the frame came.
        inputRouter.confirmFence = { currentBindings.value.achievements.state.cardFence }
        inputRouter.homeViewModeSelectEnabled = {
            homeViewModeSelectAvailable(currentBindings.value) ||
                homePinnedTomesRefusalOffered(currentBindings.value)
        }
        inputRouter.onHomeViewModeSelect = { right ->
            val current = currentBindings.value
            if (!homeViewModeSelectAvailable(current) && homePinnedTomesRefusalOffered(current)) {
                // F9: R1 asks for Pinned Tomes and there are none; L1 is the active-edge no-op.
                if (right) current.homeShelf.refusePinnedTomesSwitch()
                false
            } else {
                dispatchHomeViewModeSelect(current, right)
            }
        }
        inputRouter.onControllerShortcut = { shortcut ->
            // **Policy 2026-08-18, reported twice.** Quick Launch, Utility and Tweaker are the three
            // things a macro key can be bound to, and all three tear the player out of a browse or a pick
            // -- mid-transfer, mid-destination-choice, mid-delete-with-Undo-still-offered.
            //
            // Swallowed here rather than in the browser, because a shortcut never reaches the browser: it
            // is resolved in InputRouter and published straight to the M-button bus, so the file browser's
            // hard-modal dispatch table -- which swallows every unbound action precisely so nothing falls
            // through -- was never consulted. The modal was airtight and the macro walked around it.
            //
            // Returns true, not false: consumed, exactly as the browser's own table consumes a press it
            // has no meaning for. Returning false would hand the key to whatever is behind the browser,
            // which is the fall-through this is here to stop.
            if (!FileBrowserStore.screenOwned) {
                MButtonNavigationCommands.publish(
                    when (shortcut) {
                        ControllerShortcutAction.QUICK_LAUNCH -> MButtonPrimaryCommand.QUICK_LAUNCH
                        ControllerShortcutAction.UTILITY -> MButtonPrimaryCommand.UTILITY
                        ControllerShortcutAction.TWEAKER -> MButtonPrimaryCommand.TWEAKER
                    },
                )
            }
            true
        }
        // BM03: the router ticks (sound + haptic) only when a directional press visibly moved
        // something — FocusState is a data class, so this snapshot compares structurally.
        inputRouter.navMoveProbe = { currentBindings.value.core.focusAuthority.state.value }
        true
    }
}

internal inline fun runPrimaryMRequestAfterTomeCancel(
    cancelTomeReturn: () -> Unit,
    transaction: () -> Unit,
) {
    cancelTomeReturn()
    transaction()
}

/**
 * CP02 : the wait half of the
 * Folio-return teardown. `HomeShelfCoordinator.kt`'s `closeTomeToLibrary` arms
 * [HomeShelfCoordinator.tomeLibraryReturnPendingFence] and returns immediately -- the actual wait for the
 * Library's own arrival to settle lives HERE, deliberately outside that ~2300-line composable.
 *
 * **Why it moved.** The first CP02 attempt captured `navigation: MimirNavigationCoordinator` as a new
 * parameter on `rememberHomeShelfCoordinator` and ran the wait as a `scope.launch` inside
 * `closeTomeToLibrary` itself. It compiled, `assembleDebug` passed, and 2775 JVM unit tests passed --
 * because none of them touch ART's dex verifier. On the device it was fatal on every cold start:
 * `VerifyError: ...copy1 v0<-v268...`. `rememberHomeShelfCoordinator`'s body is dozens of INLINED `remember`/
 * `derivedStateOf` calls; Compose's compiler expands every @Composable parameter into `$changed`
 * dirty-bits plus a full-argument recomposition-retry lambda, and one more reference parameter pushed the
 * method past what the verifier accepts. `MimirAppCoordinator` (this function) is itself ~3000 lines for
 * the same reason, which is why this effect is a genuinely separate top-level function call -- one line
 * in the caller -- rather than an inlined `LaunchedEffect` block added to either giant composable.
 *
 * **The fence, not a live read.** [HomeShelfCoordinator.tomeLibraryReturnPendingFence] is the
 * `navigation.homeTomeLibraryOpenArrivalEpoch` value `closeTomeSurface` (`MimirAppInputDispatch.kt`)
 * captured SYNCHRONOUSLY, before recomposition could process its own `homeTomeLibrary = returnState`
 * write -- so it is guaranteed to predate the fresh arrival's epoch bump
 * (`MimirAppHost.kt`'s `onIncomingSettleChanged`). Because the fence is a stored value rather than an
 * edge this effect has to catch live, it does not matter when this LaunchedEffect actually starts
 * collecting relative to that bump.
 *
 * **The reversal escape.** If the incoming Library arrival is killed mid-materialise --
 * `applyMButtonHomeNavigation` sets `homeTomeLibrary = null` (`MimirNavigationCoordinator.kt`), reachable
 * inside the ~310ms window -- `homeTomeLibraryOpenSettled` never fires true for that arrival and a wait on
 * settlement alone would hang, stranding `tomeSession` non-null. Mirrors the open hop's own
 * `LaunchedEffect(folioCovers, folio == null)` (`MimirAppHost.kt`, *"the `folio == null` arm is the
 * reversal path and it is not optional"*): when the browser is gone there is nothing left to cover for.
 */
@Composable
private fun HomeTomeLibraryReturnReleaseEffect(
    homeShelf: HomeShelfCoordinator,
    navigation: MimirNavigationCoordinator,
) {
    val pendingFence = homeShelf.tomeLibraryReturnPendingFence
    val completeTomeLibraryReturn = homeShelf.completeTomeLibraryReturn
    LaunchedEffect(pendingFence) {
        val fence = pendingFence ?: return@LaunchedEffect
        snapshotFlow {
            Triple(
                navigation.homeTomeLibraryOpenArrivalEpoch,
                navigation.homeTomeLibraryOpenSettled,
                navigation.homeTomeLibrary == null,
            )
        }.first { (epoch, settled, browserGone) -> (epoch > fence && settled) || browserGone }
        completeTomeLibraryReturn(fence)
    }
}

@Composable
private fun MimirAppLifecycleEffect(bindings: MimirAppBindings) {
    val currentBindings = rememberUpdatedState(bindings)
    DisposableEffect(bindings.core.context) {
        val owner = bindings.core.context as? LifecycleOwner
        val observer = if (owner != null) {
            object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) {
                    val current = currentBindings.value
                    // A recreated Activity may receive a late pause after a native primary overlay
                    // has taken the shared coordinator. Only the exact current Activity host may stop
                    // its foreground refresh work.
                    current.core.brightness.onActivityHostPaused(current.core.focusAuthority)
                    current.navigation.updateLibraryHostResumed(false)
                    if (current.core.libraryViewModel.pendingLaunchReturnTarget.value != null) {
                        current.navigation.armLibraryLaunchReturn()
                    }
                }

                override fun onResume(owner: LifecycleOwner) {
                    val current = currentBindings.value
                    current.navigation.updateLibraryHostResumed(true)
                    current.settingsRead.refreshSpecialAccess()
                    current.onboarding.refreshAccess()
                    current.nativeShelf.scheduleRefreshOnResume()
                    current.core.brightness.refreshActivityHostOnResume(current.core.focusAuthority)
                    current.nativeShelf.consumeLaunchReturnOnResume()
                    if (current.core.libraryViewModel.pendingLaunchReturnTarget.value != null) {
                        current.navigation.armLibraryLaunchReturn()
                    } else {
                        current.navigation.disarmLibraryLaunchReturn()
                    }
                }
            }
        } else {
            null
        }
        if (owner != null && observer != null) {
            bindings.navigation.updateLibraryHostResumed(
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
            owner.lifecycle.addObserver(observer)
        }
        onDispose {
            if (owner != null && observer != null) owner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
private fun MimirCompanionEffects(
    bindings: MimirAppBindings,
    companionDisplayActive: StateFlow<Boolean>,
    launchQuickPackageOnMain: (String, Boolean, Boolean) -> QuickLaunchPrimaryRouteResult,
) {
    val core = bindings.core
    val library = bindings.library
    val nativeShelf = bindings.nativeShelf
    val companionLaunchContext = androidx.compose.ui.platform.LocalContext.current
    val companionLaunchScope = androidx.compose.runtime.rememberCoroutineScope()
    CompanionPayloadPush(
        companionDisplayActive = companionDisplayActive,
        detailGame = library.detailGame,
        openGameDetail = library.openGameDetail,
        openPlatform = library.openPlatform,
        games = library.games,
        platforms = library.platforms,
        nativeShelfSnapshot = nativeShelf.snapshot,
        shelfMode = library.shelfMode,
        favoriteSegment = library.favoriteSegment,
        focusState = core.focusAuthority.state,
        launchState = library.launchState,
        themeId = core.themeId,
        shelfLaunchStatusLines = bindings.settingsRead.shelfLaunchStatusLines,
        launchController = core.launchController,
        favoritesCount = core.libraryViewModel.favoritesCount,
        searchQuery = core.libraryViewModel.searchQuery,
        favoritesOnly = core.libraryViewModel.favoritesOnly,
        destination = bindings.navigation.destination,
        homeShelfRows = bindings.homeShelf.rows,
        launchingRow = bindings.homeShelf.launchingRow,
        // Round 5b: the launching row's game, so the Home seed can name its launcher (null for an Android row).
        launchingGame = bindings.homeShelf.launchingRow?.let(bindings.homeShelf.gameForAchievements),
        repository = core.repository,
    )
    // R01 "Land Warm": register the cold-start warm tasks the welcome lane awaits. Runs behind the mask.
    MimirColdStartWarmup(
        homeShelfRows = bindings.homeShelf.rows,
        repository = core.repository,
        // F1: the landing surface's own truth now gates the mask alongside the Library's.
        homeTruthReady = bindings.homeShelf.contentSettled,
    )
    DisposableEffect(launchQuickPackageOnMain) {
        CompanionQuickLaunchPrimaryRoute.install(launchQuickPackageOnMain)
        onDispose { CompanionQuickLaunchPrimaryRoute.clear(launchQuickPackageOnMain) }
    }
    CompanionCommandBridge(
        libraryViewModel = core.libraryViewModel,
        focusAuthority = core.focusAuthority,
        launchInFlight = library.launchState == GameLaunchUiState.Launching ||
            library.launchState == GameLaunchUiState.IntentSent,
        libraryForeground = bindings.navigation.destination == MimirDestination.LIBRARY,
        // screen 2 browses the library and launches from it.
        launchGameFromCompanion = { gameIdValue, destination ->
            companionLaunchScope.launch {
                launchCompanionPickedGame(
                    context = companionLaunchContext,
                    gameIdValue = gameIdValue,
                    repository = core.repository,
                    launchController = core.launchController,
                    homeRows = bindings.homeShelf.rows,
                    homeViewMode = bindings.homeShelf.viewMode,
                    themeId = core.themeId.storageKey,
                    platforms = library.platforms,
                    shelfLaunchStatusLines = bindings.settingsRead.shelfLaunchStatusLines,
                    destination = destination,
                    finishScope = companionLaunchScope,
                )
            }
        },
    )
}

/**
 * Launch a game the COMPANION display picked .
 *
 * Goes through [settleEmulatedHomeLaunch] — the one path that arms the return target and runs both
 * Tweaker dispatch hooks — rather than calling the launch controller directly, which that function's own
 * doc says silently loses all three.
 *
 * **The return target is a typed IDENTITY, not a row, and that is the whole design.** A game tapped on
 * screen 2 need not be on the Home shelf at all, so there may be no row to come back to. `HomeLaunchReturnTarget`
 * has always been `(itemId, rank, focusKey)` with the focus key derived from the identity, so the target is the
 * real Home row when the game IS pinned there — the player returns exactly where they were — and the bare
 * identity when it is not.
 *
 * **Only the Home-row case arms Main's Home return (Rank 7c, Thor 2026-09-11).** An armed identity with no row
 * resolves as `RowAbsent`, which holds Main's branded return cover for the whole bounded grace before Home
 * lands on its first row — about 4 s after a game that never changed Main's Home. So the bare-identity handle
 * no longer arms: `origin` below carries that decision (`HomeLaunchReturnOrigin.kt`). The handle is still
 * created: the game session keeps it, and it carries the game's EXTERNAL ELAPSED token.
 *
 * The game is re-read from the repository here rather than carried across the companion bus. That bus is
 * process-scoped and an id can outlive the shelf that minted it; a launch must resolve against live
 * library truth at the moment it launches, not at the moment of the tap.
 */
private suspend fun launchCompanionPickedGame(
    context: android.content.Context,
    gameIdValue: String,
    repository: com.gruning.mimir.rewrite.core.model.library.LibraryRepository,
    launchController: com.gruning.mimir.rewrite.core.launch.LaunchController,
    homeRows: List<com.gruning.mimir.rewrite.feature.library.HomeShelfRow>,
    homeViewMode: HomeViewMode,
    themeId: String,
    platforms: List<com.gruning.mimir.rewrite.core.model.library.PlatformRow>,
    shelfLaunchStatusLines: Map<PlatformId, String>,
    destination: CompanionQuickLaunchDestination = CompanionQuickLaunchDestination.MAIN,
    finishScope: CoroutineScope,
) {
    val gameId = com.gruning.mimir.rewrite.core.model.GameId(gameIdValue)
    val game = repository.observeGame(gameId).first() ?: run {
        GameOpeningIntroState.clearLaunching(gameIdValue)
        dismissGameOpeningPresentation(finishScope, "rejected")
        return
    }
    val itemId = com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId.Emulated(gameId)
    val target = homeRows.firstOrNull { it.itemId == itemId }
        ?.let { com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget.from(it) }
        ?: com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget(itemId = itemId, rank = 0L)
    val returnHandle = HomeLaunchReturnHandle.create(
        target,
        tomeSession = null,
        homeViewMode = homeViewMode,
        // Rank 7c: only a game with a row on Main's Home arms Main's Home return; same lookup as `target` above.
        origin = bottomCardHomeLaunchOrigin(homeRows.firstOrNull { it.itemId == itemId }),
    )
    val placement = if (destination == CompanionQuickLaunchDestination.BOTTOM) {
        MimirGamePlacement.BOTTOM
    } else {
        MimirGamePlacement.MAIN
    }
    // O15: a refusal names what held it, read from the session that refused this admission.
    var heldBy = GameLaunchSessionHold.STARTING
    val operation = MimirSessionAuthority.prepareHomeGame(
        game = game,
        returnTarget = target,
        returnHandle = returnHandle,
        placement = placement,
        onHeld = { heldBy = it },
    ) ?: run {
        // O15 follow-up: the player tapped PLAY on the Bottom, so the refusal is made there, on the selected secondary
        // display the Presentation is on. This context is Main's Activity, and a toast made from it appeared on Main.
        showCompanionPickedGameRefusal(
            base = context.applicationContext,
            bottomDisplayId = CompanionProcessRuntime.services(context).secondaryDisplaySelector.snapshot.value.selectedId,
            gameIdValue = gameIdValue,
            hold = heldBy,
        )
        GameOpeningIntroState.clearLaunching(gameIdValue)
        dismissGameOpeningPresentation(finishScope, "rejected")
        return
    }
    // Seed only after admission. A rejected tap must leave the currently active game's retained card
    // and owner untouched; accepted launches still retain the payload for the foreground re-derive.
    // Round 5b: with the launcher line the Library names; an override's label is read after this, never before.
    publishCompanionPickedGameSeed(game, themeId, platforms, shelfLaunchStatusLines, finishScope, launchController)
    // Open this interaction with the launch card, bridging Mimir UI to the emulator on a single display.
    if (placement == MimirGamePlacement.BOTTOM) {
        GameOpeningIntroState.clearLaunching(gameIdValue)
        dismissGameOpeningPresentation(finishScope, "bottom-launch")
    }
    val dispatchDestination = if (placement == MimirGamePlacement.BOTTOM) {
        LaunchDispatchDestination.Secondary { intent ->
            CompanionProcessRuntime.services(context).secondaryLaunchCoordinator
                .prepareGame(intent)
                ?.dispatchAwaitingHost() == true
        }
    } else {
        LaunchDispatchDestination.Main
    }
    val result = settleEmulatedHomeLaunch(
        context = context,
        itemId = itemId,
        game = game,
        returnHandle = returnHandle,
        launchController = launchController,
        destination = dispatchDestination,
    )
    if (result != null) {
        MimirSessionAuthority.settleHomeGame(operation, game, result)
        if (placement == MimirGamePlacement.BOTTOM) {
            if (result.launched) {
                MimirCompanionRuntimeSessionBridge.reconcileGameDispatch()
            } else {
                // The return arm may have succeeded before the bottom dispatch failed. Settle the
                // exact PREPARING operation so its global Tweaker/audio posture cannot leak into the
                // next accepted game.
                MimirSessionAuthority.state.value.gameSession
                    ?.takeIf { it.operation == operation }
                    ?.let(CompanionProcessRuntime::settleBottomGameCleanup)
            }
        }
    } else {
        MimirSessionAuthority.failHomeGame(operation, game, "home_return_arm_failed")
        if (placement == MimirGamePlacement.BOTTOM) {
            MimirSessionAuthority.state.value.gameSession
                ?.takeIf { it.operation == operation }
                ?.let(CompanionProcessRuntime::settleBottomGameCleanup)
        }
    }
    if (placement == MimirGamePlacement.MAIN) {
        finishGameOpeningIntro(finishScope, launched = result?.launched == true)
    }
}
