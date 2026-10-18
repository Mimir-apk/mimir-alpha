package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibrarySourceRole
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult
import com.gruning.mimir.rewrite.core.model.library.SourceRemovalResult
import com.gruning.mimir.rewrite.feature.browse.BrowseMimirKeyboardHost
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.GamelistViewMode
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.LibraryDetailSession
import com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryOriginReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibrarySearchReturnSnapshot
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import com.gruning.mimir.rewrite.feature.settings.LibraryReadModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Live library projection and app-owned browse/detail state, collapsed to one root local. */
internal class MimirLibraryCoordinator(
    val readModel: LibraryReadModel?,
    val gameCount: Int,
    val platformCount: Int,
    val hiddenGames: List<GameRow>,
    val platforms: List<PlatformRow>,
    val platformsLoaded: Boolean,
    val openPlatform: PlatformId?,
    val games: List<GameRow>,
    val gamesLoadedForPlatform: PlatformId?,
    val detailGame: GameRow?,
    val openGameDetail: GameRow?,
    val launchState: GameLaunchUiState,
    val pendingLaunchReturnTarget: LibraryLaunchReturnTarget?,
    val shelfMode: LibraryShelfMode,
    val favoriteSegment: FavoriteSegment,
    val searchQuery: String,
    val favoritesOnly: Boolean,
    val searchReturnSnapshot: LibrarySearchReturnSnapshot?,
    val gamelistViewMode: GamelistViewMode,
    val detailSession: LibraryDetailSession?,
    val activeDetailTarget: LibraryDetailTarget?,
    val detailDescriptionOwnerToken: Long,
    val detailDescriptionScrollState: com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState,
    val detailDescriptionScrollCommand: com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand,
    /**
     * Every game's total session time from the elapsed store, the one Home's tiles read: the Library's launch card shows the same SESSION TIME  as Home's, handed to it
     * on its detail action state, because feature-library never reaches into an app store.
     */
    val externalSessionElapsedTotals: Map<HomeShelfItemId, Long>,
    val transitionCancellationEpoch: Long,
    val browseSearch: String,
    val browseKeyboardOpen: Boolean,
    val browseKeyboardHost: BrowseMimirKeyboardHost?,
    val setBrowseKeyboardHost: (BrowseMimirKeyboardHost?) -> Unit,
    val browseVisible: List<GameRow>,
    val restoreHidden: (GameId) -> Unit,
    val rescan: (String) -> Unit,
    val addSource: (String, LibrarySourceRole) -> Unit,
    val rescanAll: ((SourceReconcileResult) -> Unit) -> Unit,
    val removeAdditionalSource: (String, (SourceRemovalResult) -> Unit) -> Unit,
    val sourceOperationRunning: Boolean,
    /**
     * non-null
     * exactly when the most recently completed [rescan]/[addSource] read could not traverse the folder
     * — one plain sentence, never a per-[com.gruning.mimir.rewrite.core.model.library.SourceFailureKind]
     * message. This is what makes a folder that FAILED to scan distinguishable from one that scanned
     * successfully and was simply empty; before this field the two were indistinguishable in this class.
     */
    val sourceReadFailureMessage: String?,
    val openDetail: (LibraryDetailSession) -> Unit,
    val closeDetail: (LibraryDetailTarget) -> Unit,
    val updateDetailDescriptionScrollState: (com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState) -> Unit,
    val requestDetailDescriptionScroll: (Int) -> Unit,
    val prepareDetailDescriptionReturn: () -> Unit,
    val requestDetailDescriptionRestore: () -> com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand?,
    val noteTransitionInteraction: () -> Unit,
    val captureOriginReturnTarget: (com.gruning.mimir.rewrite.core.input.FocusKey?) -> LibraryOriginReturnTarget,
    val setBrowseSearch: (String) -> Unit,
    val setBrowseKeyboardOpen: (Boolean) -> Unit,
)

@Composable
internal fun rememberMimirLibraryCoordinator(core: MimirAppCoreCoordinator): MimirLibraryCoordinator {
    val repository = core.repository
    val viewModel = core.libraryViewModel
    val scope = rememberCoroutineScope()
    val summaryFlow = remember(repository) { repository.observeSummary() }
    val sourcesFlow = remember(repository) { repository.observeSources() }
    val hiddenFlow = remember(repository) { repository.observeHiddenGames() }
    val summary by summaryFlow.collectAsState(initial = null)
    val sources by sourcesFlow.collectAsState(initial = emptyList())
    val hiddenGames by hiddenFlow.collectAsState(initial = emptyList())

    val platforms by viewModel.platforms.collectAsState()
    val platformsLoaded by viewModel.platformsLoaded.collectAsState()
    val openPlatform by viewModel.openPlatform.collectAsState()
    val games by viewModel.games.collectAsState()
    val gamesLoadedForPlatform by viewModel.gamesLoadedForPlatform.collectAsState()
    val detailGame by viewModel.detailGame.collectAsState()
    val openGameDetail by viewModel.openGameDetail.collectAsState()
    val gameDetail by viewModel.gameDetail.collectAsState()
    val launchState by viewModel.launchState.collectAsState()
    val pendingLaunchReturnTarget by viewModel.pendingLaunchReturnTarget.collectAsState()
    val shelfMode by viewModel.shelfMode.collectAsState()
    val favoriteSegment by viewModel.favoriteSegment.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val searchReturnSnapshot by viewModel.librarySearchReturnSnapshot.collectAsState()
    val gamelistViewMode by viewModel.gamelistViewMode.collectAsState()
    val externalSessionElapsedTotals by remember(core.context) { HomeExternalSessionElapsedStore.observe(core.context) }.collectAsState()

    var detailSession by remember { mutableStateOf<LibraryDetailSession?>(null) }
    var detailDescriptionScrollState by remember {
        mutableStateOf(com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState())
    }
    var detailDescriptionScrollCommand by remember {
        mutableStateOf(com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand())
    }
    var detailDescriptionOwnerToken by remember { mutableLongStateOf(0L) }
    var detailDescriptionReturnSnapshot by remember {
        mutableStateOf<com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState?>(null)
    }
    var detailDescriptionCaptureGeneration by remember { mutableLongStateOf(0L) }
    var detailDescriptionIssuedGeneration by remember { mutableLongStateOf(-1L) }
    var transitionCancellationEpoch by remember { mutableStateOf(0L) }
    var browseSearch by rememberSaveable { mutableStateOf("") }
    var browseKeyboardOpen by remember { mutableStateOf(false) }
    var browseKeyboardHost by remember { mutableStateOf<com.gruning.mimir.rewrite.feature.browse.BrowseMimirKeyboardHost?>(null) }
    var queuedSourceOperations by remember { mutableIntStateOf(0) }
    val sourceOperationMutex = remember { Mutex() }
    val sourceOperationRunning = queuedSourceOperations > 0
    var sourceReadFailureMessage by remember { mutableStateOf<String?>(null) }
    val browseVisible = if (browseSearch.isBlank()) {
        hiddenGames
    } else {
        hiddenGames.filter { it.title.contains(browseSearch.trim(), ignoreCase = true) }
    }
    val activeDetailTarget = detailSession?.target?.let { target ->
        when (target) {
            is LibraryDetailTarget.Emulated -> {
                val live = gameDetail?.takeIf { it.gameId == target.game.gameId } ?: target.game
                target.copy(game = live)
            }
            is LibraryDetailTarget.AndroidGame -> target
        }
    } ?: (gameDetail ?: openGameDetail)?.let(LibraryDetailTarget::Emulated)

    // Cold existing-library load ONLY. Deliberately NOT keyed on `platforms`: that list re-emits on every
    // library change, and each re-fire bumps the revision and re-probes the filesystem for EVERY platform
    // with games. Later revisions arrive through the explicit post-scan seam (rescanAndRefreshBios below),
    // which is the spec's trigger — that keeps probe I/O off the reactive path (SD01 lesson: filesystem
    // work driven by library emissions is what killed 4k+ libraries).
    LaunchedEffect(platformsLoaded) {
        if (platformsLoaded) core.launchController.biosLibraryRevisionChanged()
    }

    // Widened from `suspend () -> Boolean` (policy 2026-08-12, spec §1.4/§3, C1/C2): the old shape
    // could only signal "did the library change" and had nowhere to carry a read failure, so `rescan`/
    // `addSource` collapsed `ScanOutcome.Failed` into the same `false` a genuinely empty folder produces.
    // `scanOutcome` defaults to null for `rescanAll`/`removeAdditionalSource` (:220-243 below), which are
    // not single-folder reads and already report through their own honest `onResult` callback — leaving
    // it null means they never touch [sourceReadFailureMessage], so their behavior is unchanged.
    fun runSourceOperation(
        queueIfBusy: Boolean = false,
        operation: suspend () -> SourceOperationOutcome,
    ) {
        if (queuedSourceOperations > 0 && !queueIfBusy) return
        queuedSourceOperations += 1
        scope.launch {
            try {
                sourceOperationMutex.withLock {
                    val outcome = operation()
                    if (outcome.libraryChanged) core.launchController.biosLibraryRevisionChanged()
                    outcome.scanOutcome?.let { scanOutcome ->
                        sourceReadFailureMessage = librarySourceReadFailureMessage(scanOutcome)
                    }
                }
            } finally {
                queuedSourceOperations = (queuedSourceOperations - 1).coerceAtLeast(0)
            }
        }
    }

    return MimirLibraryCoordinator(
        readModel = summary?.let { LibraryReadModel(it, sources, hiddenGames, platforms = hiddenGamePlatformRows(hiddenGames, platforms), appPackage = core.context.packageName) },
        gameCount = summary?.gameCount ?: 0,
        platformCount = summary?.platformCount ?: 0,
        hiddenGames = hiddenGames,
        platforms = platforms,
        platformsLoaded = platformsLoaded,
        openPlatform = openPlatform,
        games = games,
        gamesLoadedForPlatform = gamesLoadedForPlatform,
        detailGame = detailGame,
        openGameDetail = openGameDetail,
        launchState = launchState,
        pendingLaunchReturnTarget = pendingLaunchReturnTarget,
        shelfMode = shelfMode,
        favoriteSegment = favoriteSegment,
        searchQuery = searchQuery,
        favoritesOnly = favoritesOnly,
        searchReturnSnapshot = searchReturnSnapshot,
        gamelistViewMode = gamelistViewMode,
        detailSession = detailSession,
        activeDetailTarget = activeDetailTarget,
        detailDescriptionOwnerToken = detailDescriptionOwnerToken,
        detailDescriptionScrollState = detailDescriptionScrollState,
        detailDescriptionScrollCommand = detailDescriptionScrollCommand,
        externalSessionElapsedTotals = externalSessionElapsedTotals,
        transitionCancellationEpoch = transitionCancellationEpoch,
        browseSearch = browseSearch,
        browseKeyboardOpen = browseKeyboardOpen,
        browseKeyboardHost = browseKeyboardHost,
        setBrowseKeyboardHost = { browseKeyboardHost = it },
        browseVisible = browseVisible,
        restoreHidden = { gameId ->
            hiddenGames.firstOrNull { it.gameId == gameId }
                ?.let { viewModel.setHidden(it, hidden = false) }
        },
        rescan = { rootUri ->
            runSourceOperation {
                val result = repository.rescan(rootUri)
                LastScanSummaryState.record(result)
                NewGameEnrichPromptState.recordScan(core.context, result)
                val outcome = result.outcome
                SourceOperationOutcome(libraryChanged = outcome == ScanOutcome.Healthy, scanOutcome = outcome)
            }
        },
        addSource = { rootUri, role ->
            // A URI already accepted by SAF must never be dropped behind an in-flight re-scan.
            runSourceOperation(queueIfBusy = true) {
                val result = repository.addSource(rootUri, role)
                LastScanSummaryState.record(result)
                NewGameEnrichPromptState.recordScan(core.context, result)
                val outcome = result.outcome
                SourceOperationOutcome(libraryChanged = outcome == ScanOutcome.Healthy, scanOutcome = outcome)
            }
        },
        rescanAll = { onResult ->
            runSourceOperation {
                val result = repository.reconcileSources()
                LastScanSummaryState.record(result)
                NewGameEnrichPromptState.recordScan(core.context, result)
                onResult(result)
                SourceOperationOutcome(libraryChanged = sourceReconcileChangedLibrary(result))
            }
        },
        removeAdditionalSource = { rootUri, onResult ->
            // An explicitly confirmed removal must not vanish behind an in-flight scan. Queue it just
            // like an accepted SAF source; the callback owns settling the confirmation UI.
            runSourceOperation(queueIfBusy = true) {
                val result = runCatching {
                    repository.removeAdditionalSource(rootUri)
                }.getOrElse { error ->
                    SourceRemovalResult.Failed(error.message ?: "Source removal failed")
                }
                onResult(result)
                SourceOperationOutcome(libraryChanged = result is SourceRemovalResult.Removed)
            }
        },
        sourceOperationRunning = sourceOperationRunning,
        sourceReadFailureMessage = sourceReadFailureMessage,
        openDetail = { incoming ->
            if (BuildConfig.DEBUG && DebugHomeRailObservation.isArmed()) {
                DebugHomeRailObservation.recordLibraryDetail(
                    originScope = incoming.originScope.toString(),
                    originFocus = incoming.originFocusKey.toString(),
                )
            }
            val sameSession = detailSession?.let { current ->
                current.target.itemId == incoming.target.itemId &&
                    current.originScope == incoming.originScope &&
                    current.originFocusKey == incoming.originFocusKey
            } == true
            if (!sameSession) {
                detailDescriptionOwnerToken = nextLibraryDetailDescriptionToken(detailDescriptionOwnerToken)
                detailDescriptionScrollState =
                    com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState(
                        ownerToken = detailDescriptionOwnerToken,
                    )
                detailDescriptionScrollCommand =
                    com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand(
                        ownerToken = detailDescriptionOwnerToken,
                    )
                detailDescriptionReturnSnapshot = null
                detailDescriptionIssuedGeneration = -1L
            }
            detailSession = incoming
        },
        closeDetail = { target ->
            if (detailSession?.target?.itemId == target.itemId) {
                detailSession = null
                detailDescriptionScrollState = com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState()
                detailDescriptionScrollCommand = com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand()
                detailDescriptionReturnSnapshot = null
                detailDescriptionIssuedGeneration = -1L
            }
        },
        updateDetailDescriptionScrollState = { measured ->
            if (acceptLibraryDetailDescriptionState(
                    sessionActive = detailSession != null,
                    activeOwnerToken = detailDescriptionOwnerToken,
                    measured = measured,
                )
            ) {
                detailDescriptionScrollState = measured
            }
        },
        requestDetailDescriptionScroll = { direction ->
            detailDescriptionScrollCommand = com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand(
                sequence = nextLibraryDescriptionCommandSequence(detailDescriptionScrollCommand.sequence),
                direction = direction.coerceIn(-1, 1),
                ownerToken = detailDescriptionOwnerToken,
            )
        },
        prepareDetailDescriptionReturn = {
            detailDescriptionReturnSnapshot = detailDescriptionScrollState
                .takeIf { detailSession != null && it.ownerToken == detailDescriptionOwnerToken }
            detailDescriptionCaptureGeneration = nextLibraryDetailDescriptionToken(
                detailDescriptionCaptureGeneration,
            )
        },
        requestDetailDescriptionRestore = {
            val snapshot = detailDescriptionReturnSnapshot
            val issued = issueLibraryDetailDescriptionRestoreCommand(
                snapshot = snapshot,
                activeOwnerToken = detailDescriptionOwnerToken,
                previous = detailDescriptionScrollCommand,
                alreadyIssued = detailDescriptionIssuedGeneration == detailDescriptionCaptureGeneration,
            )
            if (detailSession != null && issued != null) {
                detailDescriptionScrollCommand = issued
                detailDescriptionIssuedGeneration = detailDescriptionCaptureGeneration
            }
            detailDescriptionScrollCommand.takeIf {
                detailSession != null &&
                    snapshot?.ownerToken == detailDescriptionOwnerToken &&
                    it.restoreOffset != null &&
                    it.ownerToken == detailDescriptionOwnerToken
            }
        },
        noteTransitionInteraction = {
            transitionCancellationEpoch = if (transitionCancellationEpoch == Long.MAX_VALUE) {
                0L
            } else {
                transitionCancellationEpoch + 1L
            }
        },
        captureOriginReturnTarget = viewModel::captureOriginReturnTarget,
        setBrowseSearch = { browseSearch = it },
        setBrowseKeyboardOpen = { browseKeyboardOpen = it },
    )
}

/**
 * What one completed [runSourceOperation] reports. [libraryChanged] keeps the pre-existing
 * `biosLibraryRevisionChanged()` trigger; [scanOutcome] is new (policy 2026-08-12, spec §1.4/§3,
 * C1/C2) and stays null for operations that are not a single-folder read ([rescanAll],
 * [removeAdditionalSource] — see their call sites above), so only [rescan]/[addSource] ever touch
 * [MimirLibraryCoordinator.sourceReadFailureMessage].
 */
internal data class SourceOperationOutcome(
    val libraryChanged: Boolean,
    val scanOutcome: ScanOutcome? = null,
)

/**
 * Use the shared failure sentence, without a reason code or jargon. One
 * plain sentence for every [ScanOutcome.Failed], regardless of
 * [com.gruning.mimir.rewrite.core.model.library.SourceFailureKind] or the diagnostic-only
 * [ScanOutcome.Failed.reason] (`core-model/.../LibraryRepository.kt:336`: "reason is diagnostic only").
 * Do not branch this per failure kind — that was the rejected variant-specific copy shape.
 */
internal const val LIBRARY_SOURCE_READ_FAILURE_MESSAGE = "Couldn’t read this folder."

/**
 * The defect in one line: pre-fix, [rescan]/[addSource] collapsed this to `outcome == ScanOutcome.Healthy`
 * — a `Boolean` that cannot tell "scanned fine, found nothing" apart from "could not be read". This
 * function is what makes the two [ScanOutcome] arms distinguishable again.
 */
internal fun librarySourceReadFailureMessage(outcome: ScanOutcome): String? = when (outcome) {
    ScanOutcome.Healthy -> null
    is ScanOutcome.Failed -> LIBRARY_SOURCE_READ_FAILURE_MESSAGE
}

internal fun nextLibraryDetailDescriptionToken(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L

internal fun nextLibraryDescriptionCommandSequence(current: Int): Int =
    if (current == Int.MAX_VALUE) 1 else current + 1

internal fun acceptLibraryDetailDescriptionState(
    sessionActive: Boolean,
    activeOwnerToken: Long,
    measured: com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState,
): Boolean = sessionActive &&
    measured.ownerToken == activeOwnerToken &&
    measured.measured

internal fun issueLibraryDetailDescriptionRestoreCommand(
    snapshot: com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState?,
    activeOwnerToken: Long,
    previous: com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand,
    alreadyIssued: Boolean,
): com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand? {
    if (snapshot?.ownerToken != activeOwnerToken) return null
    if (alreadyIssued) {
        return previous.takeIf { it.ownerToken == activeOwnerToken && it.restoreOffset != null }
    }
    return com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand(
        sequence = nextLibraryDescriptionCommandSequence(previous.sequence),
        restoreOffset = snapshot.offset,
        expectedRevision = snapshot.revision,
        ownerToken = activeOwnerToken,
    )
}

internal fun resolveLibraryLaunchReturnDetailSession(
    target: LibraryLaunchReturnTarget,
    currentSession: LibraryDetailSession?,
    activeTarget: LibraryDetailTarget?,
): LibraryDetailSession? {
    if (!target.restoreLaunchCard) return null
    val originScope = when (target.surface) {
        LibraryLaunchReturnTarget.Surface.SHELF -> LibraryShelfScope
        LibraryLaunchReturnTarget.Surface.GAMELIST -> GamelistScope
    }
    fun LibraryDetailTarget.matchesReturnIdentity(): Boolean = when (this) {
        is LibraryDetailTarget.Emulated -> game.gameId == target.gameId
        is LibraryDetailTarget.AndroidGame -> row.packageName == (target.nativePackageName ?: target.shelfKey)
    }
    val validatedTarget = activeTarget
        ?.takeIf { it.matchesReturnIdentity() }
        ?: return null
    currentSession?.takeIf { session ->
        session.originScope == originScope && session.target.matchesReturnIdentity()
    }?.let { return it.copy(target = validatedTarget) }
    return LibraryDetailSession(
        target = validatedTarget,
        originScope = originScope,
        originFocusKey = validatedTarget.sourceFocusKey,
    )
}

internal fun liveLibraryLaunchReturnDetailTarget(
    target: LibraryLaunchReturnTarget,
    games: List<GameRow>,
    nativeGames: List<com.gruning.mimir.rewrite.feature.library.NativeShelfRow>,
): LibraryDetailTarget? {
    if (!target.restoreLaunchCard) return null
    target.gameId?.let { gameId ->
        return games.firstOrNull { it.gameId == gameId }?.let(LibraryDetailTarget::Emulated)
    }
    val packageName = target.nativeReturnPackageName() ?: return null
    return nativeGames.firstOrNull { it.packageName == packageName }?.let(LibraryDetailTarget::AndroidGame)
}

internal fun LibraryLaunchReturnTarget.nativeReturnPackageName(): String? =
    nativePackageName ?: shelfKey?.takeIf { shelfMode == LibraryShelfMode.ANDROID_GAMES }

@Composable
internal fun MimirBrowseDestinationEffect(
    destination: MimirDestination,
    library: MimirLibraryCoordinator,
) {
    LaunchedEffect(destination) {
        if (destination != MimirDestination.BROWSE) {
            library.setBrowseSearch("")
            library.setBrowseKeyboardOpen(false)
        }
    }
}
