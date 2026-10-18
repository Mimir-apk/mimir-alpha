package com.gruning.mimir.rewrite.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.launch.LaunchAttemptId
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.launch.PreDispatchOutcome
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.ScanResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * a cold-start readiness signal the app-module welcome lane can hold on. The welcome plays its
 * 3.2s MINIMUM beat, then lingers until [ready] flips (the shelf can render) so cold launch never cuts
 * to a half-built shelf. Lives here (not app) so the [LibraryViewModel] can flip it without an upward dependency.
 */
object LibraryReadyGate {
    val ready = MutableStateFlow(false)
}

internal data class GamelistFocusLane(
    val platformId: PlatformId,
    val favoritesOnly: Boolean,
)

enum class GamelistViewMode {
    CAROUSEL,
    LIST;

    fun toggled(): GamelistViewMode = if (this == CAROUSEL) LIST else CAROUSEL
}

internal data class PendingGamelistFilterLanding(
    val platformId: PlatformId,
    val favoritesOnly: Boolean,
    val sourceGameId: GameId?,
    val targetGameId: GameId,
)

/** One atomic UI publication of launch feedback and the game it belongs to. */
data class LibraryLaunchUiSnapshot(
    val state: GameLaunchUiState = GameLaunchUiState.Idle,
    val ownerGameId: GameId? = null,
    /**
     * The emulator the intent went to, while Mimir is still the thing on screen and has not yielded to it (F2:
     * "Starting <Emulator>…"). Null before dispatch, once the emulator has taken the screen, and on every failure.
     */
    val emulatorLabel: String? = null,
)

/**
 * See [LibraryViewModel.platformSwitchFloorMillis]. The floor is one page glide, derived from
 * [MimirMotion.ModeTransitionMs], so held L2/R2 accepts the next switch as the previous one finishes.
 * The earlier flat 400ms floor gated at less than half that rate.
 */
internal val LibraryPlatformSwitchFloorMs: Long = MimirMotion.ModeTransitionMs.toLong()

/**
 * Whether a platform switch may be accepted now.
 *
 * Pure so the floor is checkable without a clock or a ViewModel. `lastAccepted == Long.MIN_VALUE` means
 * nothing has been accepted yet, and the subtraction would overflow — the first switch is always allowed.
 */
internal fun libraryPlatformSwitchAccepted(
    nowMillis: Long,
    lastAcceptedMillis: Long,
    floorMillis: Long,
): Boolean = lastAcceptedMillis == Long.MIN_VALUE || (nowMillis - lastAcceptedMillis) >= floorMillis

internal data class GamelistPlatformSwitchMotion(
    val platformId: PlatformId,
    val direction: Int,
    val sequence: Long,
) {
    init {
        require(direction == -1 || direction == 1)
    }
}

/** Choose one destination identity before a Normal/Favorites list mutation can unregister the source. */
internal fun resolveGamelistFilterTarget(
    orderedUniverse: List<GameRow>,
    destination: List<GameRow>,
    focusedGameId: GameId?,
    rememberedDestinationGameId: GameId?,
): GameId? {
    if (destination.isEmpty()) return null
    val destinationIds = destination.mapTo(hashSetOf(), GameRow::gameId)
    rememberedDestinationGameId?.takeIf(destinationIds::contains)?.let { return it }
    focusedGameId?.takeIf(destinationIds::contains)?.let { return it }

    val sourceIndex = focusedGameId?.let { id -> orderedUniverse.indexOfFirst { it.gameId == id } }
        ?.takeIf { it >= 0 }
        ?: return destination.first().gameId
    val universeIndex = orderedUniverse.mapIndexed { index, row -> row.gameId to index }.toMap()
    return destination.minWithOrNull(
        compareBy<GameRow> { row ->
            kotlin.math.abs((universeIndex[row.gameId] ?: Int.MAX_VALUE / 2) - sourceIndex)
        }.thenBy { row ->
            // At an equal distance, continue forward before falling back behind the source.
            if ((universeIndex[row.gameId] ?: -1) >= sourceIndex) 0 else 1
        }.thenBy { row -> universeIndex[row.gameId] ?: Int.MAX_VALUE },
    )?.gameId
}

internal interface LibraryLaunchPort {
    suspend fun openWithOptions(game: GameRow): OpenWithOptions
    suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate)
    suspend fun clearEmulatorOverride(game: GameRow)
    /**
     * [preferredDisc] pins which physical disc of a multi-disc group starts (0 = the group default).
     *
     * **An explicit disc launches that disc ALONE.** `defaultLaunchMember` returns the disc member ahead
     * of the `.m3u` when `preferredDisc > 0`, and the generated-playlist branch is gated on
     * `preferredDisc <= 0` (`RoomLaunchProviders.kt:77,100`), so the emulator receives one disc rather
     * than the set and shows no disc-swap list for it. That is the plain meaning of picking a disc, and
     * the default Play path is untouched -- it still hands over the whole set.
     */
    suspend fun launch(
        game: GameRow,
        preferredDisc: Int = 0,
        launchMemberEntryKey: EntryKey? = null,
    ): LaunchResult
}

/**
 * The app's game-opening intro, seen from the Library.
 * [LibraryViewModel.launch] calls [beforeDispatch] once a launch is admitted and its return target is published, and
 * before the port dispatches; the call suspends for the intro's hold. [afterDispatch] follows the Settled or Failed
 * session event with the dispatch verdict and the ViewModel's own scope, which outlives any one composition.
 */
interface LibraryLaunchOpening {
    suspend fun beforeDispatch(game: GameRow)
    fun afterDispatch(scope: CoroutineScope, game: GameRow, launched: Boolean)
}

/** No intro: previews, the Fake repository and host tests that do not ask for one. */
object NoLibraryLaunchOpening : LibraryLaunchOpening {
    override suspend fun beforeDispatch(game: GameRow) = Unit
    override fun afterDispatch(scope: CoroutineScope, game: GameRow, launched: Boolean) = Unit
}

private class LaunchControllerLibraryPort(
    private val controller: LaunchController,
    private val preDispatchHook: suspend (GameRow, LaunchReadiness.Ready, LaunchAttemptId) -> Unit,
    private val postDispatchHook: suspend (GameRow, LaunchReadiness.Ready, LaunchAttemptId) -> Unit,
    private val onPreDispatchDegraded: suspend (GameRow, LaunchAttemptId, PreDispatchOutcome) -> Unit,
    private val onDispatchFailed: suspend (GameRow, LaunchAttemptId) -> Unit,
) : LibraryLaunchPort {
    override suspend fun openWithOptions(game: GameRow): OpenWithOptions =
        controller.openWithOptions(game.gameId, game.platformId)

    override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) {
        controller.setEmulatorOverride(game.gameId, candidate.emulatorId)
    }

    override suspend fun clearEmulatorOverride(game: GameRow) {
        controller.clearEmulatorOverride(game.gameId)
    }

    override suspend fun launch(
        game: GameRow,
        preferredDisc: Int,
        launchMemberEntryKey: EntryKey?,
    ): LaunchResult =
        controller.launch(
            gameId = game.gameId,
            platformId = game.platformId,
            preferredDisc = preferredDisc,
            launchMemberEntryKey = launchMemberEntryKey,
            preDispatchHook = { ready, attemptId -> preDispatchHook(game, ready, attemptId) },
            postDispatchHook = { ready, attemptId -> postDispatchHook(game, ready, attemptId) },
            onPreDispatchDegraded = { attemptId, outcome ->
                onPreDispatchDegraded(game, attemptId, outcome)
            },
            onDispatchFailed = { _, _, attemptId -> onDispatchFailed(game, attemptId) },
        )
}

/**
 * The Library screen's state holder — collects the [LibraryRepository] Flows into hot [StateFlow]s the
 * Compose UI renders from. Deliberately thin: the repository already returns product-truth, DEDUP-
 * COLLAPSED rows , so the ViewModel only chooses which platform's
 * gamelist is currently observed and exposes the reactive results.
 *
 * There is NO business logic here (no dedup, no filtering beyond what the contract guarantees) — that
 * lives in `core-resolver`/`core-storage` behind the contract. Production uses the Room-backed repository;
 * previews and tests can supply a fake through the same contract.
 */
class LibraryViewModel private constructor(
    private val repository: LibraryRepository,
    detailGameSnapshot: MutableStateFlow<GameRow?>,
    openGameDetailSnapshot: MutableStateFlow<GameRow?>,
    // R6-P0-02 round-3 fix (BLOCKING): dispatcher-injection seam for [launch]'s Released-arm Main hop.
    // Defaults to the real Main dispatcher for production and for every existing test's constructor
    // call; a host test that needs the Released arm reachable without Robolectric/Dispatchers.setMain
    // can pass a test dispatcher through the public or internal(launchPort) constructor below instead of
    // configuring a real Main dispatcher -- never by removing the hop itself.
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private var launchPort: LibraryLaunchPort? = null
    private var gameSessionObserver: LibraryGameSessionObserver = NoOpLibraryGameSessionObserver
    private var launchOpening: LibraryLaunchOpening = NoLibraryLaunchOpening
    @Volatile
    private var latestLaunchOperation: LibraryGameLaunchOperation? = null

    /**
     * Test seam (R6-F-07): invoked on the calling (Dispatchers.Default) thread inside [launch]'s completion
     * monitor, right after a completion arm's guard (`latestLaunchOperation == operation`) reads true and
     * before it writes [_pendingLaunchReturnTarget] / [_launchState]. A host test can block here to prove
     * [cancelPendingLaunch] cannot land inside that window any more. No-op in production.
     */
    internal var launchCompletionGuardHook: () -> Unit = {}

    /**
     * The launch pipeline. Nullable so the Fake-repository previews/standalone tests build without a
     * device (a null controller keeps CONFIRM-on-a-game a no-op stub). The app always provides the real
     * [LaunchController] from `LaunchStore.controller(context)`.
     */
    constructor(
        repository: LibraryRepository,
        launchController: LaunchController? = null,
        gameSessionObserver: LibraryGameSessionObserver = NoOpLibraryGameSessionObserver,
        preDispatchHook: suspend (GameRow, LaunchReadiness.Ready, LaunchAttemptId) -> Unit = { _, _, _ -> },
        postDispatchHook: suspend (GameRow, LaunchReadiness.Ready, LaunchAttemptId) -> Unit = { _, _, _ -> },
        onPreDispatchDegraded: suspend (GameRow, LaunchAttemptId, PreDispatchOutcome) -> Unit = { _, _, _ -> },
        onDispatchFailed: suspend (GameRow, LaunchAttemptId) -> Unit = { _, _ -> },
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        launchOpening: LibraryLaunchOpening = NoLibraryLaunchOpening,
    ) : this(repository, MutableStateFlow(null), MutableStateFlow(null), mainDispatcher) {
        launchPort = launchController?.let {
            LaunchControllerLibraryPort(
                controller = it,
                preDispatchHook = preDispatchHook,
                postDispatchHook = postDispatchHook,
                onPreDispatchDegraded = onPreDispatchDegraded,
                onDispatchFailed = onDispatchFailed,
            )
        }
        this.gameSessionObserver = gameSessionObserver
        this.launchOpening = launchOpening
    }

    internal constructor(
        repository: LibraryRepository,
        launchPort: LibraryLaunchPort,
        gameSessionObserver: LibraryGameSessionObserver = NoOpLibraryGameSessionObserver,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        launchOpening: LibraryLaunchOpening = NoLibraryLaunchOpening,
    ) : this(repository, MutableStateFlow(null), MutableStateFlow(null), mainDispatcher) {
        this.launchPort = launchPort
        this.gameSessionObserver = gameSessionObserver
        this.launchOpening = launchOpening
    }

    /**
     * Host-test seam for the detail snapshots' two-thread contract (see [reconcileDetailSnapshots]). The test supplies
     * the flows behind [detailGame] and [openGameDetail], so it can hold a background read-modify-write inside its
     * window and land a main-thread write there on purpose. Production never calls this; every other constructor
     * builds plain [MutableStateFlow]s.
     */
    internal constructor(
        repository: LibraryRepository,
        detailGameSnapshot: MutableStateFlow<GameRow?>,
        openGameDetailSnapshot: MutableStateFlow<GameRow?>,
        launchPort: LibraryLaunchPort?,
    ) : this(repository, detailGameSnapshot, openGameDetailSnapshot) {
        this.launchPort = launchPort
    }

    private val _platformsLoaded = MutableStateFlow(false)
    val platformsLoaded: StateFlow<Boolean> = _platformsLoaded

    /**
     * The platforms that have games, for the shelf. Empty until the first emission.
     *
     * JVM-test caveat: this flow can NEVER emit under plain JUnit. `stateIn` inherits a dispatcher
     * only when the upstream carries `flowOn` (as [games] does with `Dispatchers.Default`); with no
     * `flowOn` here the sharing coroutine launches on the ViewModel scope's Main dispatcher, which is
     * absent on the JVM, so `platforms.first { … }` parks forever and `platforms.value` stays empty.
     * Host tests must read `repository.observePlatforms()` directly and use the
     * `switchOpenPlatform(offset, rows)` overload (see `GamelistOneGameHeroFocusCharacterizationTest`).
     */
    val platforms: StateFlow<List<PlatformRow>> =
        repository.observePlatforms()
            .onEach {
                _platformsLoaded.value = true
                LibraryReadyGate.ready.value = true // release the cold-start welcome once the shelf can render
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Games the identity enricher is still resolving; > 0 shows the subtle "Identifying…" chip. */
    val resolvingCount: StateFlow<Int> =
        repository.observeResolvingCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Which platform's gamelist is being observed. null = the platforms view (no gamelist open). */
    private val _openPlatform = MutableStateFlow<PlatformId?>(null)
    val openPlatform: StateFlow<PlatformId?> = _openPlatform

    /** The active shelf lane. Native lanes are package shelves, not repository/library rows. */
    private val _shelfMode = MutableStateFlow(LibraryShelfMode.EMULATION)
    val shelfMode: StateFlow<LibraryShelfMode> = _shelfMode

    /**
     * The coverflow's currently-centred tile key — the source of truth for returning focus to the content from
     * the Add pill. The authority's `focusMemory` can be clobbered by scope pushes/sentinels (which resets the
     * Add→DOWN hop to the first console); this is written by the shelf's `onCenter` and never clobbered.
     * Second writer (BUG-3, 2026-08-11): the dispatch's tile→Add hop stamps the tile focus is leaving,
     * because `onCenter` runs post-composition and a coalesced ring-move+UP burst reaches the pill before
     * any frame records the new tile. Both writers stamp the same truth; the hop just does it in time.
     */
    private val _centeredShelfKey = MutableStateFlow<FocusKey?>(null)
    val centeredShelfKey: StateFlow<FocusKey?> = _centeredShelfKey

    fun setCenteredShelfKey(key: FocusKey) {
        _centeredShelfKey.value = key
    }

    /** The game owning the metadata-side Open-With focus or the latest direct-launch status line. */
    private val _detailGame: MutableStateFlow<GameRow?> = detailGameSnapshot
    val detailGame: StateFlow<GameRow?> = _detailGame

    // Game-detail (rich metadata) sub-surface: which game's full-screen detail page is open (null = closed).
    // A sub-state of the open gamelist, exactly as [openPlatform] is a sub-state of the shelf.
    private val _openGameDetail: MutableStateFlow<GameRow?> = openGameDetailSnapshot
    val openGameDetail: StateFlow<GameRow?> = _openGameDetail

    /** The launch state for the latest gamelist direct-launch attempt. */
    private val _launchState = MutableStateFlow<GameLaunchUiState>(GameLaunchUiState.Idle)
    val launchState: StateFlow<GameLaunchUiState> = _launchState

    /**
     * Compatibility flow for consumers that need only the owner. UI that needs both values must collect
     * [launchUiSnapshot], whose state and owner are published together under this ViewModel's monitor.
     */
    private val _launchStateOwner = MutableStateFlow<GameId?>(null)
    val launchStateOwner: StateFlow<GameId?> = _launchStateOwner

    private val _launchUiSnapshot = MutableStateFlow(LibraryLaunchUiSnapshot())
    val launchUiSnapshot: StateFlow<LibraryLaunchUiSnapshot> = _launchUiSnapshot

    /**
     * Test seam for the interval between compatibility-flow writes and the atomic UI snapshot commit.
     * Production leaves it as a no-op; it lets a bounded host test prove the UI never observes half a pair.
     */
    internal var launchUiPublicationHook: (GameLaunchUiState) -> Unit = {}

    /** Publish launch state and owner as one UI snapshot, serialized with completion and cancellation. */
    private fun publishLaunchUi(state: GameLaunchUiState, ownerGameId: GameId?, emulatorLabel: String? = null) {
        synchronized(this@LibraryViewModel) {
            _launchState.value = state
            launchUiPublicationHook(state)
            _launchStateOwner.value = ownerGameId
            _launchUiSnapshot.value = LibraryLaunchUiSnapshot(state, ownerGameId, emulatorLabel)
        }
    }

    /**
     * R6-P0-02 review fix (item 2): the one-shot "say so" toast for a PLAY refused by a live parked lease
     * that release-at-the-press could not clear -- the same [ParkedLeaseReleaseOutcome.StillParked] message
     * the Home row/card show, on the same Main-level rail, reused rather than reinvented for this screen.
     * Never set for an ordinary O15 refusal ([ParkedLeaseReleaseOutcome.NotParked]) or after a successful
     * release. [LibraryScreen] mounts the rail and calls [consumeDeliveredParkedRefusalToast] once shown.
     *
     * R6-P0-02 round-3 fix (BLOCKING): the release probe this event id is minted from runs on
     * `Dispatchers.Default` (see [launch]'s refusal branch), so a plain `var` here was a non-atomic write
     * off Main -- the same [AtomicLong] pattern [processLaunchOperationSource] and
     * [gamelistPlatformSwitchSequence] already use for a counter touched off the main thread.
     */
    private val parkedRefusalToastEventId = AtomicLong(0L)
    private val _parkedRefusalToast = MutableStateFlow<MimirMessage?>(null)
    val parkedRefusalToast: StateFlow<MimirMessage?> = _parkedRefusalToast

    fun consumeDeliveredParkedRefusalToast(deliveredEventId: Long) {
        if (_parkedRefusalToast.value?.eventId == deliveredEventId) _parkedRefusalToast.value = null
    }

    /**
     * Walk-32h ("every hide shatters the card"): says where a hidden game went, as a confirmation on the Library's one toast
     * rail ([LibraryHideCopy.GameReceipt]). Posted by the hide slot (see [departureSlot]): at once when the card is not broken,
     * and as the last shard fades when it is. Writes nothing and touches no list.
     */
    internal fun showHideReceipt(text: String) {
        _parkedRefusalToast.value = MimirMessage(
            eventId = parkedRefusalToastEventId.incrementAndGet(),
            text = text,
            messageClass = MimirMessageClass.Confirmation,
        )
    }

    /** The stable surface identity to restore after the latest successfully dispatched external launch. */
    private val _pendingLaunchReturnTarget = MutableStateFlow<LibraryLaunchReturnTarget?>(null)
    val pendingLaunchReturnTarget: StateFlow<LibraryLaunchReturnTarget?> = _pendingLaunchReturnTarget

    /**
     * The Open-With options loaded for the metadata-focused game: what the picker lists and what the card's EMULATOR fact names.
     * Non-null from the first load until the card closes or another game's options are asked for. It does NOT say whether the
     * picker is open: that is the Open-with focus scope .
     */
    private val _openWith = MutableStateFlow<OpenWithOptions?>(null)
    val openWith: StateFlow<OpenWithOptions?> = _openWith

    /** The game [_openWith] belongs to, so a different game's card never shows these. Written under [openWithLock]. */
    private var openWithGameId: GameId? = null
    /** Invalidates older asynchronous option loads when the picker closes or a newer game/request wins. */
    private val openWithRequestSource = AtomicLong(0L)

    /** The live gamelist search query (blank = no filter). Per-gamelist — reset when a gamelist opens/closes. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /** Immutable title/Genre/conditional-Platform criteria for the scoped Search surface. */
    private val _searchCriteria = MutableStateFlow(LibrarySearchCriteria())
    internal val searchCriteria: StateFlow<LibrarySearchCriteria> = _searchCriteria

    /** Stable facets from the active lane before its current query/filter is applied. */
    private val _availableSearchFacets = MutableStateFlow(LibrarySearchFacets.Empty)
    internal val availableSearchFacets: StateFlow<LibrarySearchFacets> = _availableSearchFacets

    /** Bounded, process-memory-only state for the dedicated Tome Search surface. */
    private val _librarySearchSession = MutableStateFlow<LibrarySearchSession?>(null)
    internal val librarySearchSession: StateFlow<LibrarySearchSession?> = _librarySearchSession

    /**
     * Whether the Library Search surface is up, for hosts outside this module. The app's status HUD yields to
     * it on compact, where the two share the top-right corner (compact review 2026-09-27, SEARCH-1).
     */
    val librarySearchOpen: StateFlow<Boolean> =
        _librarySearchSession.map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The optional hard child picker above Search. Null means the Search page itself owns input. */
    private val _librarySearchPicker = MutableStateFlow<LibrarySearchPicker?>(null)
    internal val librarySearchPicker: StateFlow<LibrarySearchPicker?> = _librarySearchPicker

    /** Search remembers the last top control separately from its current/result focus identity. */
    private val _librarySearchLastTopFocus =
        MutableStateFlow<LibrarySearchFocusIdentity>(LibrarySearchFocusIdentity.Query)
    internal val librarySearchLastTopFocus: StateFlow<LibrarySearchFocusIdentity> =
        _librarySearchLastTopFocus

    /** Last closed/restored Search state. It remains dormant until the user explicitly opens Search again. */
    private val _librarySearchReturnSnapshot = MutableStateFlow<LibrarySearchReturnSnapshot?>(null)
    val librarySearchReturnSnapshot: StateFlow<LibrarySearchReturnSnapshot?> =
        _librarySearchReturnSnapshot

    /** One controller hop from a base result to the Search launcher; consumed on open or return. */
    private var preparedLibrarySearchOriginGameId: GameId? = null

    /**
     * X shortcut: show only favorited games in a regular gamelist. PER-GAMELIST by policy (HP-4,
     * 2026-07-10, deliberately diverging from shipping's session-global flag): opening, switching, or
     * closing a gamelist clears it — only a launch-return restore re-applies a captured filter.
     */
    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly

    /** Session-stable presentation choice for regular platform gamelists. */
    private val _gamelistViewMode = MutableStateFlow(GamelistViewMode.CAROUSEL)
    val gamelistViewMode: StateFlow<GamelistViewMode> = _gamelistViewMode

    internal fun toggleGamelistViewMode(): GamelistViewMode {
        val next = _gamelistViewMode.value.toggled()
        _gamelistViewMode.value = next
        return next
    }

    /** Independent focus identity for each regular platform's Normal and Favorites projections. */
    private val gamelistFocusMemory = ConcurrentHashMap<GamelistFocusLane, GameId>()

    /** One destination focus request spanning the reactive Normal/Favorites list replacement. */
    private val _pendingGamelistFilterLanding = MutableStateFlow<PendingGamelistFilterLanding?>(null)
    internal val pendingGamelistFilterLanding: StateFlow<PendingGamelistFilterLanding?> =
        _pendingGamelistFilterLanding

    /** Direction + identity for the destination-owned book-page glide after an in-list platform switch. */
    private val _gamelistPlatformSwitchMotion = MutableStateFlow<GamelistPlatformSwitchMotion?>(null)
    internal val gamelistPlatformSwitchMotion: StateFlow<GamelistPlatformSwitchMotion?> =
        _gamelistPlatformSwitchMotion
    private val gamelistPlatformSwitchSequence = AtomicLong(0L)

    /** Active category inside the hidden Favorites collection. */
    private val _favoriteSegment = MutableStateFlow(FavoriteSegment.GAMES)
    val favoriteSegment: StateFlow<FavoriteSegment> = _favoriteSegment

    /** Whether the in-app keyboard is showing over the gamelist (touch-only search entry). */
    /**
     * OR-8: each L3 press on the shelf or in the gamelist bumps this, and the Guidance button there toggles its legend
     * as a tap would. A counter, so two presses are two toggles.
     */
    private val _hintLegendRequests = MutableStateFlow(0)
    val hintLegendRequests: StateFlow<Int> = _hintLegendRequests

    fun requestHintLegend() {
        _hintLegendRequests.value = _hintLegendRequests.value + 1
    }

    private val _keyboardOpen = MutableStateFlow(false)
    val keyboardOpen: StateFlow<Boolean> = _keyboardOpen

    /** Active controller keyboard binding while the gamelist search keyboard is open. */
    @Volatile
    var libraryKeyboardHost: LibraryMimirKeyboardHost? = null

    /** Active controller keyboard binding while the native-add search keyboard is open. */
    @Volatile
    var nativeAddKeyboardHost: LibraryMimirKeyboardHost? = null

    /** The focused gamelist row waiting on the START-hide confirmation dialog. */
    private val _pendingHideGame = MutableStateFlow<GameRow?>(null)
    val pendingHideGame: StateFlow<GameRow?> = _pendingHideGame

    /**
     * Optimistic departures for hidden games or games un-starred in a favourites view. [games] and the screen omit
     * them immediately so cards can dissolve and neighbours close the gap. Entries clear when a live emission for
     * the departed list omits the game, a write fails, or it is restored. Declared before [games], which reads them.
     */
    private val _pendingRemovals = MutableStateFlow(PendingLibraryRemovals())
    internal val pendingRemovals: StateFlow<PendingLibraryRemovals> = _pendingRemovals

    /**
     * The dedup-collapsed games of the open platform, FILTERED by the live search query; empty when no
     * platform is open. When the synthetic [FAVORITES_PLATFORM_ID] is open, the base set is every favorited
     * group across ALL platforms — the favorites-only view reuses the exact same gamelist rendering. The
     * search filter is applied HERE (not in the UI) so `games.value` is the single visible truth both the
     * grid and controller dispatch ([dispatchToLibrary]'s focused-game lookup) resolve against.
     */
    private val _gamesLoadedForPlatform = MutableStateFlow<PlatformId?>(null)
    val gamesLoadedForPlatform: StateFlow<PlatformId?> = _gamesLoadedForPlatform

    /**
     * The rows [games] last published, with the platform they are truly loaded for (null while the list on hand was
     * queried for another platform). Written in the same combine pass as [games], so a loaded-but-empty list reads as
     * loaded and the pre-switch beat never does. [gamesLoadedForPlatform] flips in the upstream onEach, before that
     * pass runs, so paired with [games] it can take the previous page's list for this one's; this cannot. It is what
     * the gamelist's settle waits on ([libraryGamelistSettleRows]). Declared before [games], whose Default-dispatched
     * combine writes it.
     */
    private val _visibleGamesFor = MutableStateFlow(LibraryVisibleGames(null, emptyList()))
    internal val visibleGamesFor: StateFlow<LibraryVisibleGames> = _visibleGamesFor

    /** Raw, pre-search/pre-filter Favorite availability for the active regular platform. */
    private val _openPlatformHasFavorites = MutableStateFlow(false)
    val openPlatformHasFavorites: StateFlow<Boolean> = _openPlatformHasFavorites

    // Seed revisited platforms immediately while their live query refreshes; Favorites/null skip this cache.
    private val platformListCache = ConcurrentHashMap<PlatformId, List<GameRow>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val games: StateFlow<List<GameRow>> =
        combine(
            _openPlatform.flatMapLatest { id ->
                val cached = id?.takeIf { it != FAVORITES_PLATFORM_ID }?.let { platformListCache[it] }
                // Only drop to the loading state when there is nothing cached to paint immediately.
                if (cached == null) _gamesLoadedForPlatform.value = null
                val source = when (id) {
                    null -> flowOf(emptyList())
                    FAVORITES_PLATFORM_ID -> repository.observeFavorites()
                    else -> repository.observeGames(id)
                }.onEach { fresh ->
                    if (id != null && id != FAVORITES_PLATFORM_ID) platformListCache[id] = fresh
                    if (_openPlatform.value == id) {
                        _openPlatformHasFavorites.value = id != null && id != FAVORITES_PLATFORM_ID &&
                            fresh.any(GameRow::isFavorite)
                    }
                    _gamesLoadedForPlatform.value = id
                }.map { rows ->
                    LibrarySourceEmission(id, rows, acknowledgementPending = id != null)
                }
                if (cached != null) {
                    flow<LibrarySourceEmission> {
                        if (_openPlatform.value == id) {
                            _openPlatformHasFavorites.value = cached.any(GameRow::isFavorite)
                        }
                        _gamesLoadedForPlatform.value = id
                        emit(LibrarySourceEmission(id, cached, acknowledgementPending = false))
                        emitAll(source)
                    }
                } else {
                    source
                }
            },
            _openPlatform,
            _searchCriteria,
            _favoritesOnly,
            _pendingRemovals,
        ) { tagged, platformId, criteria, favoritesOnly, pending ->
            val list = tagged.rows
            if (tagged.acknowledgementPending) {
                // Consume before updating pending: search/filter replays cannot acknowledge newer intents.
                tagged.acknowledgementPending = false
                _pendingRemovals.update { it.reconciledWith(tagged.loadedFor, list) }
            }
            // A platform switch can combine the previous query's rows with the next platform id.
            val stale = if (platformId != null && platformId != FAVORITES_PLATFORM_ID &&
                list.isNotEmpty() && list.first().platformId != platformId
            ) {
                emptyList()
            } else {
                list
            }
            // Apply optimistic removals before facets and search so every visible count agrees.
            val fresh = pending.applyTo(stale, platformId, favoritesOnly)
            val origin = when (platformId) {
                null -> null
                FAVORITES_PLATFORM_ID -> LibrarySearchOrigin.FavoriteGames
                else -> LibrarySearchOrigin.PlatformGames(platformId)
            }
            val visible: List<GameRow> = if (origin == null) {
                _availableSearchFacets.value = LibrarySearchFacets.Empty
                emptyList()
            } else {
                _availableSearchFacets.value = deriveLibrarySearchFacets(
                    orderedRows = fresh,
                    origin = origin,
                    favoritesOnly = favoritesOnly,
                )
                reduceVisibleLibraryGames(
                    orderedRows = fresh,
                    origin = origin,
                    criteria = criteria,
                    favoritesOnly = favoritesOnly,
                )
            }
            _visibleGamesFor.value = LibraryVisibleGames(platformId?.takeIf { it == tagged.loadedFor }, visible)
            visible
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        warmPlatformGamelists()
        reconcileDetailSnapshots()
    }

    /**
     * Observed on device: [_detailGame] / [_openGameDetail] hold SNAPSHOT GameRows
     * captured when the detail was focused/opened. After a game session returns, the repository row moves on
     * (playCount, lastPlayed, favorite) but the snapshots — and the companion payload built FROM them —
     * stayed stale (screen 2 read TIMES PLAYED 8 while the primary read 10, simultaneously). Reconcile the
     * snapshots by identity against every fresh [games] emission so both displays read one truth.
     *
     * **Each snapshot is reconciled with one atomic `update {}`** (found by source review, never
     * observed on a device). This collector runs on Dispatchers.Default, while [focusMetadata], [openGameDetail],
     * [closeGameDetail], [launch] and the platform and return paths write the same two snapshots on the main thread.
     * Each StateFlow write is thread-safe on its own, but a read, then a derive, then a write is not. A main-thread
     * write that landed between them was replaced by a row derived from the snapshot it had replaced, so a card he
     * had just closed could come back, or the metadata could stay on the game he had moved away from. `update`
     * compares before it commits and re-runs against the newer snapshot when one has landed.
     *
     * **Invariant :** a snapshot write commits only against the state it was derived from — the CAS'd
     * value for the two detail snapshots, and the request token under [openWithLock] for the picker.
     */
    private fun reconcileDetailSnapshots() {
        // Dispatchers.Default explicitly (like warmPlatformGamelists): unit tests construct this VM without
        // a Main dispatcher. Each snapshot write stays atomic through `update`, as above.
        viewModelScope.launch(Dispatchers.Default) {
            games.collect {
                _detailGame.update(::reconciledDetailSnapshot)
                _openGameDetail.update(::reconciledDetailSnapshot)
            }
        }
    }

    /**
     * Derived INSIDE the CAS, from the truth at that instant ([games]'s value, the open platform, the loaded flag)
     * rather than from inputs captured before the `update {}` began (R6-5E-03, race 1). A pass preempted across a
     * platform switch re-runs its retry against the newer snapshot AND the newer rows, so it cannot null a card or
     * focus set in the destination. `loadedTruth` keeps its existing meaning.
     */
    private fun reconciledDetailSnapshot(snapshot: GameRow?): GameRow? {
        val d = snapshot ?: return null
        val rows = games.value
        val loadedTruth = _openPlatform.value != null && _gamesLoadedForPlatform.value == _openPlatform.value
        return rows.firstOrNull { it.gameId == d.gameId } ?: d.takeUnless { loadedTruth }
    }

    /**
     * idle-warm every platform's list into [platformListCache] once the shelf has settled, so the
     * FIRST visit to a platform paints instantly too — the earlier cache only helped revisits, and the first visit still incurred a cold rebuild. Sequential + yielding so it never contends with the initial shelf load, the identity
     * enricher, or the user's own gamelist open; skips anything already cached (a platform they opened first).
     */
    private fun warmPlatformGamelists() {
        viewModelScope.launch(Dispatchers.Default) {
            val rows = platforms.first { it.isNotEmpty() }
            // R01 "Land Warm" : the LANDING platform (the shelf centres index 0 on a cold
            // start) warms IMMEDIATELY — its rows are what the first gamelist open renders and what the
            // welcome-gate cover warm decodes — and the resolved rows are signalled to the WarmupLedger.
            // This first .first() also forces the per-ROM SAF art resolution (cover/logo/video) into its
            // per-process memo behind the mask, so the first open pays no ContentResolver storm (GL-02).
            rows.firstOrNull()?.let { first ->
                if (!platformListCache.containsKey(first.platformId)) {
                    runCatching { repository.observeGames(first.platformId).first() }
                        .getOrNull()
                        ?.let { platformListCache[first.platformId] = it }
                }
                LibraryWarmSignals.firstGamelistRows.value =
                    platformListCache[first.platformId] ?: emptyList()
            } ?: run { LibraryWarmSignals.firstGamelistRows.value = emptyList() }
            delay(1_500L) // let the first shelf + gamelist settle before the broad background reads
            for (row in rows) {
                if (platformListCache.containsKey(row.platformId)) continue
                runCatching { repository.observeGames(row.platformId).first() }
                    .getOrNull()
                    ?.let { platformListCache[row.platformId] = it }
                delay(60L) // stay idle-friendly between platforms
            }
        }
    }

    /** How many favorited ROM groups exist across all platforms. Native favorites are supplied by the app shell. */
    val favoritesCount: StateFlow<Int> =
        repository.observeFavorites()
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The live [GameRow] whose detail page is open (D7 single-game accessor); null when closed or the row is gone. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val gameDetail: StateFlow<GameRow?> =
        _openGameDetail.flatMapLatest { row -> if (row == null) flowOf(null) else repository.observeGame(row.gameId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The open detail game's physical members (D5): the disc list + real per-file sizes for the Size sum. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val gameDetailMembers: StateFlow<List<GameMemberRow>> =
        _openGameDetail.flatMapLatest { row -> if (row == null) flowOf(emptyList()) else repository.observeGameMembers(row.gameId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Open a platform's gamelist (called when a platform tile is activated). Search resets per-gamelist.
     *
     * Returning from Settings could flash the rune loading screen over a Library already in memory.
     *
     * This function used to write `_gamesLoadedForPlatform.value = null` here, immediately before
     * `_openPlatform.value = platformId`. Two things make that a defect rather than belt-and-braces:
     *
     *  1. **[_openPlatform] is a StateFlow, so the line below is a NO-OP when the id is unchanged** --
     *     and re-opening the platform you are already on is exactly what returning to the Library does
     *     (`MimirNavigationCoordinator`'s settings-return effect restores the origin target). The
     *     `flatMapLatest` in [games] therefore never re-ran, so neither of the two places that write the
     *     id BACK ever fired. The flag stayed null: a fully loaded, still-hot, still-cached gamelist
     *     reporting itself as loading, indefinitely, until the next Room emission happened along.
     *  2. **Nulling it could never have made the UI more correct anyway.** This flag is not a boolean
     *     "loading" -- it names WHICH platform's list is loaded, and every consumer derives truth as
     *     `gamesLoadedForPlatform == openPlatform` (`LibraryScreen.kt:1012`). So when the platform really
     *     is changing, that comparison already reads false on its own the moment [_openPlatform] moves;
     *     the null adds nothing. When the platform is NOT changing, the null is simply wrong.
     *
     * So the write is gone, and [games]'s `flatMapLatest` is left as the single owner of this flag. It is
     * also the better-informed owner: it drops to loading only when [platformListCache] has nothing to
     * paint immediately, which is a decision this function cannot make correctly and did not try to.
     */
    fun openPlatform(platformId: PlatformId) {
        _gamelistPlatformSwitchMotion.value = null
        resetPerGamelistFilters()
        _openPlatformHasFavorites.value = platformListCache[platformId]?.any(GameRow::isFavorite) == true
        _openPlatform.value = platformId
    }

    /** Open a game's full-screen detail page (rich metadata). Also resolves the "Opens with" emulator so the
     *  detail can name it, and clears any prior launch state. */
    fun openGameDetail(game: GameRow) {
        publishLaunchUi(GameLaunchUiState.Idle, ownerGameId = null)
        _openGameDetail.value = game
        loadOpenWith(game)
    }

    /**
     * Close the detail page, back to the open gamelist. Also invalidates any Open-with load still in
     * flight (R6-F-06): [openGameDetail] always issues one, and neither its request token nor
     * [_detailGame] changes on close, so a load that lands after this call used to write stale options
     * for a card that is no longer open. [clearOpenWith] is idempotent when nothing was pending.
     */
    fun closeGameDetail() {
        _openGameDetail.value = null
        clearOpenWith()
    }

    /**
     * Open the hidden Favorites collection. Search resets per-visit; the preferred segment is shelf-aware.
     *
     * Same BUG#1 removal as [openPlatform], and for the same reason -- re-opening Favorites while already
     * on Favorites deduped the assignment below and left this flag stuck at null. **Favorites remains the
     * one gamelist that legitimately shows a loading state**, because [games]'s cache deliberately
     * excludes [FAVORITES_PLATFORM_ID] (the favorited set spans every platform, so there is no per-platform
     * list to seed from) -- so entering Favorites really does wait on a query. That is honest loading, not
     * this bug, and it is left exactly as it was.
     */
    fun openFavorites(preferredSegment: FavoriteSegment = FavoriteSegment.GAMES) {
        _gamelistPlatformSwitchMotion.value = null
        resetPerGamelistFilters()
        _openPlatformHasFavorites.value = false
        _favoriteSegment.value = preferredSegment
        _openPlatform.value = FAVORITES_PLATFORM_ID
    }

    fun setFavoriteSegment(segment: FavoriteSegment) {
        if (_favoriteSegment.value == segment) return
        resetPerGamelistFilters()
        _favoriteSegment.value = segment
    }

    fun cycleFavoriteSegment(availableSegments: List<FavoriteSegment>, offset: Int): Boolean {
        if (_openPlatform.value != FAVORITES_PLATFORM_ID) return false
        val available = availableSegments.ifEmpty { listOf(FavoriteSegment.GAMES) }
        val currentIndex = available.indexOf(_favoriteSegment.value).takeIf { it >= 0 } ?: 0
        setFavoriteSegment(available[(currentIndex + offset).floorMod(available.size)])
        return true
    }

    /** Set the live search query — [games] re-filters immediately. */
    fun setSearchQuery(query: String) {
        updateSearchCriteria(_searchCriteria.value.copy(query = query))
    }

    internal fun setSearchGenre(filter: LibrarySearchGenreFilter) {
        updateSearchCriteria(_searchCriteria.value.copy(genre = filter))
    }

    internal fun setSearchPlatform(filter: LibrarySearchPlatformFilter) {
        val applicable = if (
            _openPlatform.value == FAVORITES_PLATFORM_ID &&
            _favoriteSegment.value == FavoriteSegment.GAMES
        ) {
            filter
        } else {
            LibrarySearchPlatformFilter.All
        }
        updateSearchCriteria(_searchCriteria.value.copy(platform = applicable))
    }

    internal fun clearSearchCriteria() {
        updateSearchCriteria(LibrarySearchCriteria())
    }

    /**
     * Open dedicated Search only for an emulated platform lane or Favorites/Games. Native favorite segments
     * retain their established package/title search path and never fabricate Genre or Platform metadata.
     */
    internal fun openLibrarySearch(originGameId: GameId? = null): Boolean {
        val platform = _openPlatform.value ?: return false
        val origin = currentLibrarySearchOrigin() ?: return false
        val restored = restoreLibrarySearchReturnSnapshot(
            snapshot = currentLibrarySearchReturnSnapshot(),
            legacyQuery = _searchQuery.value,
            origin = origin,
            facets = _availableSearchFacets.value,
            games = games.value,
        )
        updateSearchCriteria(restored.criteria)
        val originFocus = originGameId
            ?.takeIf { id -> games.value.any { it.gameId == id } }
            ?.let { id ->
                LibrarySearchOriginFocusIdentity.Result(
                    LibrarySearchResultIdentity.Game(id),
                )
            }
            ?: LibrarySearchOriginFocusIdentity.SearchLauncher
        _keyboardOpen.value = false
        _librarySearchPicker.value = null
        _librarySearchLastTopFocus.value = restored.lastTopFocused
        preparedLibrarySearchOriginGameId = null
        _librarySearchSession.value = LibrarySearchSession(
            origin = origin,
            originFocus = originFocus,
            criteria = restored.criteria,
            lastFocused = restored.lastFocused,
            resultScrollAnchor = restored.resultScrollAnchor,
        )
        _librarySearchReturnSnapshot.value = captureActiveLibrarySearchSnapshot()
        return true
    }

    internal fun prepareLibrarySearchOrigin(game: GameRow) {
        preparedLibrarySearchOriginGameId = game.gameId.takeIf { id ->
            games.value.any { it.gameId == id }
        }
    }

    internal fun preparedLibrarySearchOrigin(): GameId? =
        preparedLibrarySearchOriginGameId?.takeIf { id -> games.value.any { it.gameId == id } }

    internal fun openPreparedLibrarySearch(): Boolean {
        val prepared = preparedLibrarySearchOrigin()
        preparedLibrarySearchOriginGameId = null
        return openLibrarySearch(prepared)
    }

    /** Close the dedicated Search page while preserving its active criteria on the retained gamelist. */
    internal fun closeLibrarySearch(): LibrarySearchOriginFocusIdentity? {
        val session = _librarySearchSession.value
        val originFocus = session?.originFocus
        captureActiveLibrarySearchSnapshot()?.let { _librarySearchReturnSnapshot.value = it }
        _keyboardOpen.value = false
        _librarySearchPicker.value = null
        _librarySearchSession.value = null
        preparedLibrarySearchOriginGameId = null
        return originFocus
    }

    /**
     * Leave dedicated Search for an external root surface while retaining its dormant return snapshot.
     *
     * Callers must capture their typed origin before invoking this seam. Keeping capture separate and
     * non-mutating matters because the primary-origin persistence effect also observes it reactively.
     */
    fun closeLibrarySearchForExternalNavigation() {
        closeLibrarySearch()
    }

    internal fun currentLibrarySearchReturnSnapshot(): LibrarySearchReturnSnapshot? =
        currentLibrarySearchOrigin()?.let {
            captureActiveLibrarySearchSnapshot() ?: _librarySearchReturnSnapshot.value
        }

    internal fun openLibrarySearchPicker(picker: LibrarySearchPicker): Boolean {
        val session = _librarySearchSession.value ?: return false
        if (picker == LibrarySearchPicker.PLATFORM && session.origin != LibrarySearchOrigin.FavoriteGames) {
            return false
        }
        _keyboardOpen.value = false
        _librarySearchPicker.value = picker
        return true
    }

    internal fun closeLibrarySearchPicker() {
        _librarySearchPicker.value = null
    }

    internal fun rememberLibrarySearchFocus(focus: LibrarySearchFocusIdentity) {
        if (focus == LibrarySearchFocusIdentity.Query ||
            focus == LibrarySearchFocusIdentity.Genre ||
            focus == LibrarySearchFocusIdentity.Platform
        ) {
            _librarySearchLastTopFocus.value = focus
        }
        _librarySearchSession.value = _librarySearchSession.value?.copy(
            lastFocused = focus,
            resultScrollAnchor = (focus as? LibrarySearchFocusIdentity.Result)?.identity
                ?: _librarySearchSession.value?.resultScrollAnchor,
        )
        captureActiveLibrarySearchSnapshot()?.let { _librarySearchReturnSnapshot.value = it }
    }

    /** Show the in-app keyboard over the gamelist (touch tap on the search bar). */
    fun openSearch() {
        _keyboardOpen.value = true
    }

    /** Hide the keyboard (Done / dismiss) — the current filter stays applied until cleared or the list closes. */
    fun closeSearch() {
        _keyboardOpen.value = false
    }

    /**
     * Toggle the X "show favorites" filter without ever entering an empty lane. Availability is the raw
     * platform truth above, so a Favorite hidden by the current search still permits the transition.
     * Returning from an already-active filter is always allowed, including after its final Favorite is removed.
     */
    fun toggleFavoritesOnly(focusedGameId: GameId? = null): Boolean {
        val platform = _openPlatform.value ?: return false
        if (platform == FAVORITES_PLATFORM_ID) return false
        if (!_favoritesOnly.value && !_openPlatformHasFavorites.value) return false

        val sourceFavoritesOnly = _favoritesOnly.value
        val destinationFavoritesOnly = !sourceFavoritesOnly
        val cachedUniverse = platformListCache[platform].orEmpty()
        val rawUniverse = cachedUniverse.ifEmpty { games.value }
        val origin = LibrarySearchOrigin.PlatformGames(platform)
        val orderedUniverse = reduceVisibleLibraryGames(
            orderedRows = rawUniverse,
            origin = origin,
            criteria = _searchCriteria.value,
        )
        val sourceFocus = focusedGameId?.takeIf { id -> games.value.any { it.gameId == id } }
        if (sourceFocus != null) {
            gamelistFocusMemory[GamelistFocusLane(platform, sourceFavoritesOnly)] = sourceFocus
        }
        val destination = reduceVisibleLibraryGames(
            orderedRows = rawUniverse,
            origin = origin,
            criteria = _searchCriteria.value,
            favoritesOnly = destinationFavoritesOnly,
        )
        val destinationLane = GamelistFocusLane(platform, destinationFavoritesOnly)
        val target = resolveGamelistFilterTarget(
            orderedUniverse = orderedUniverse,
            destination = destination,
            focusedGameId = sourceFocus,
            rememberedDestinationGameId = gamelistFocusMemory[destinationLane],
        )
        if (target != null) {
            gamelistFocusMemory[destinationLane] = target
            _pendingGamelistFilterLanding.value = PendingGamelistFilterLanding(
                platformId = platform,
                favoritesOnly = destinationFavoritesOnly,
                sourceGameId = sourceFocus,
                targetGameId = target,
            )
        } else {
            _pendingGamelistFilterLanding.value = null
        }
        _favoritesOnly.value = destinationFavoritesOnly
        return true
    }

    fun rememberGamelistFocus(game: GameRow) {
        val platform = _openPlatform.value ?: return
        if (platform == FAVORITES_PLATFORM_ID || game.platformId != platform) return
        gamelistFocusMemory[GamelistFocusLane(platform, _favoritesOnly.value)] = game.gameId
    }

    internal fun rememberedGamelistFocus(favoritesOnly: Boolean): GameId? {
        val platform = _openPlatform.value ?: return null
        if (platform == FAVORITES_PLATFORM_ID) return null
        return gamelistFocusMemory[GamelistFocusLane(platform, favoritesOnly)]
    }

    /** Motion batch B prewarm (LibraryShelfPrewarm.kt): [platform]'s rows from the idle row cache, or null. No query. */
    internal fun cachedPlatformRows(platform: PlatformId): List<GameRow>? = platformListCache[platform]

    /** The landing [rememberedGamelistFocus] would give [platform]'s gamelist, read without opening it. */
    internal fun rememberedGamelistFocusFor(platform: PlatformId, favoritesOnly: Boolean): GameId? =
        gamelistFocusMemory[GamelistFocusLane(platform, favoritesOnly)]

    internal fun consumePendingGamelistFilterLanding(expected: PendingGamelistFilterLanding) {
        if (_pendingGamelistFilterLanding.value == expected) {
            _pendingGamelistFilterLanding.value = null
        }
    }

    internal fun consumeGamelistPlatformSwitchMotion(expected: GamelistPlatformSwitchMotion) {
        if (_gamelistPlatformSwitchMotion.value == expected) {
            _gamelistPlatformSwitchMotion.value = null
        }
    }

    /**
     * The step of the last shelf-mode change: +1 for R1 (the new mode glides in from the right), -1 for L1, the wrap
     * included (motion audit batch C). Written before the mode, so the page change reads it when it lands.
     */
    internal var lastShelfModeStep: Int = 1
        private set

    fun cycleShelfMode(offset: Int) {
        if (offset != 0) lastShelfModeStep = if (offset > 0) 1 else -1
        _shelfMode.value = _shelfMode.value.shifted(offset)
    }

    fun setShelfMode(mode: LibraryShelfMode) {
        if (_shelfMode.value == mode) return
        lastShelfModeStep = if (mode.ordinal > _shelfMode.value.ordinal) 1 else -1
        _shelfMode.value = mode
    }

    /** Shipping shoulder shortcut: hop between platform gamelists without returning to the shelf. */
    /**
     * The floor between two accepted platform switches.
     *
     *  Measured on the Odin: a destination's rows and art land ~460 ms after the press, and
     * the card cell is a different shape per platform (`pc_games` 0.72 against `ps1` 1.16), so a burst
     * stacks half-settled resizes on top of each other. **This is the alternative to animating layout** —
     * an animated resize moves a card under the player's thumb, and a switch
     * that cannot outrun its own settle has nothing to animate away.
     *
     * Deliberately NOT a reference to `LibraryGamelistEmptyGraceMs`. They answer different questions —
     * *"how long may a switch be unsettled"* and *"how soon may the next one be accepted"* — and one
     * number serving two duties is how a value silently acquires a second meaning here. Both were 400 ms
     * when this was written; the floor has since been reduced to one page glide and the grace has not
     * moved, which is exactly why they could not share a constant.
     */
    internal var platformSwitchFloorMillis: Long = LibraryPlatformSwitchFloorMs
    private var lastAcceptedPlatformSwitchAt: Long = Long.MIN_VALUE

    /**
     * Monotonic, and deliberately NOT `SystemClock.uptimeMillis`.
     *
     * That call throws `Method ... not mocked` under the JVM unit runner, so defaulting to it would have
     * left a landmine for every future test that reaches this path -- and it took one down immediately.
     * `System.nanoTime` is monotonic in exactly the way this needs (unaffected by wall-clock changes) and
     * costs no Android dependency. Overridable for a test that wants to drive time itself.
     */
    internal var switchClockMillis: () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * The controller-driven switch — L2/R2 from inside a gamelist. **This is where the floor lives**, and
     * deliberately not on the internal overload below.
     *
     * The floor exists to stop a human holding a trigger from outrunning the destination's own settle. A
     * programmatic call is not a burst, and putting the gate on the shared seam refused two focus
     * characterization tests that drive it directly — caught by the suite, and fixed here rather than by
     * editing those tests, because their duty is untouched and a gate that has to be worked around is a
     * gate in the wrong place.
     *
     * Refused, not queued: a queued burst would still play out as stacked resizes a moment later, which
     * is the thing being prevented rather than deferred.
     */
    fun switchOpenPlatform(offset: Int): Boolean {
        val now = switchClockMillis()
        if (!libraryPlatformSwitchAccepted(now, lastAcceptedPlatformSwitchAt, platformSwitchFloorMillis)) {
            return false
        }
        val switched = switchOpenPlatform(offset, platforms.value)
        // Only an ACCEPTED switch arms the floor. A refusal at the edge of the list (or a no-op onto the
        // same platform) must not make the next real press wait.
        if (switched) lastAcceptedPlatformSwitchAt = now
        return switched
    }

    /** Deterministic platform-row seam used by the controller reducer's focused JVM contract. */
    internal fun switchOpenPlatform(offset: Int, availablePlatforms: List<PlatformRow>): Boolean {
        val current = _openPlatform.value ?: return false
        if (current == FAVORITES_PLATFORM_ID) return false
        // Favourites-only no longer refuses the switch. Policy 2026-09-03, after the press was
        // measured DEAD on device: it moves to the next platform and lands unfiltered, which
        // `resetPerGamelistFilters()` below already does. Carrying the filter across was the other
        // option and it loses on the example library shape -- 16 of the Thor's 20 platforms hold no favourites
        // at all, so it would have walked him through mostly-empty lists.
        val rows = availablePlatforms
        val currentIndex = rows.indexOfFirst { it.platformId == current }
        if (currentIndex < 0 || rows.isEmpty()) return false
        val next = rows[(currentIndex + offset).floorMod(rows.size)].platformId
        if (next == current) return true
        resetPerGamelistFilters()
        _openPlatformHasFavorites.value = platformListCache[next]?.any(GameRow::isFavorite) == true
        _pendingHideGame.value = null
        _detailGame.value = null
        publishLaunchUi(GameLaunchUiState.Idle, ownerGameId = null)
        clearOpenWith()
        _gamelistPlatformSwitchMotion.value = GamelistPlatformSwitchMotion(
            platformId = next,
            direction = if (offset < 0) -1 else 1,
            sequence = gamelistPlatformSwitchSequence.incrementAndGet(),
        )
        _openPlatform.value = next
        return true
    }

    private fun resetPerGamelistFilters() {
        updateSearchCriteria(LibrarySearchCriteria())
        _availableSearchFacets.value = LibrarySearchFacets.Empty
        _keyboardOpen.value = false
        _librarySearchPicker.value = null
        _librarySearchSession.value = null
        _librarySearchLastTopFocus.value = LibrarySearchFocusIdentity.Query
        _librarySearchReturnSnapshot.value = null
        preparedLibrarySearchOriginGameId = null
        _favoritesOnly.value = false
        _pendingGamelistFilterLanding.value = null
    }

    private fun updateSearchCriteria(criteria: LibrarySearchCriteria) {
        _searchCriteria.value = criteria
        _searchQuery.value = criteria.query
        _librarySearchSession.value = _librarySearchSession.value?.copy(criteria = criteria)
        _librarySearchReturnSnapshot.value = _librarySearchReturnSnapshot.value?.copy(
            query = criteria.query,
            genreKey = (criteria.genre as? LibrarySearchGenreFilter.Exact)?.key?.value,
            platformId = (criteria.platform as? LibrarySearchPlatformFilter.Exact)?.platformId,
        )
        captureActiveLibrarySearchSnapshot()?.let { _librarySearchReturnSnapshot.value = it }
    }

    private fun currentLibrarySearchOrigin(): LibrarySearchOrigin? {
        val platform = _openPlatform.value ?: return null
        return when {
            platform != FAVORITES_PLATFORM_ID -> LibrarySearchOrigin.PlatformGames(platform)
            _favoriteSegment.value == FavoriteSegment.GAMES -> LibrarySearchOrigin.FavoriteGames
            else -> null
        }
    }

    private fun captureActiveLibrarySearchSnapshot(): LibrarySearchReturnSnapshot? {
        val session = _librarySearchSession.value ?: return null
        return captureLibrarySearchReturnSnapshot(
            criteria = _searchCriteria.value,
            lastFocused = session.lastFocused,
            lastTopFocused = _librarySearchLastTopFocus.value,
            resultScrollAnchor = session.resultScrollAnchor,
        )
    }

    /** Close the gamelist, returning to the platforms view. Clears any active search. */
    fun closeGamelist() {
        _gamelistPlatformSwitchMotion.value = null
        _openPlatform.value = null
        _openPlatformHasFavorites.value = false
        resetPerGamelistFilters()
        _detailGame.value = null
        publishLaunchUi(GameLaunchUiState.Idle, ownerGameId = null)
        _pendingHideGame.value = null
    }

    /** Remember which gamelist row owns the metadata-side affordance focus. */
    fun focusMetadata(game: GameRow) {
        _keyboardOpen.value = false
        _detailGame.value = game
        publishLaunchUi(GameLaunchUiState.Idle, ownerGameId = null)
    }

    /**
     * Toggle [game]'s favorite mark (pure frontend state — never touches the file/launch/placement,
     * the design constraint). The gamelist re-emits from Room without changing its
     * last-played order; the metadata-focused row is updated optimistically so preview state stays current
     * until Room re-emits.
     */
    fun toggleFavorite(game: GameRow): Boolean {
        val nowFavorite = !game.isFavorite
        if (_detailGame.value?.gameId == game.gameId) {
            _detailGame.value = _detailGame.value?.copy(isFavorite = nowFavorite)
        }
        // Walk 32g: un-starring a game inside a favourites view takes it out of that view, so the list lets it go now,
        // as it does for a hide, and not when the write has come back. A star put back withdraws the entry.
        val leavesTheView = !nowFavorite && (_favoritesOnly.value || _openPlatform.value == FAVORITES_PLATFORM_ID)
        if (nowFavorite) clearPendingRemoval(game.gameId)
        if (leavesTheView) markPendingRemoval(game, LibraryRemovalKind.UNFAVORITED)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                repository.setFavorite(game.gameId, nowFavorite)
            } catch (t: Throwable) {
                if (leavesTheView) clearPendingRemoval(game.gameId)
                throw t
            }
        }
        // Select favourited silently, and a player found it only by
        // pressing every button. It says so now, on the Library's one toast rail (the parked-refusal rail: one rail,
        // one message at a time, the newest wins).
        _parkedRefusalToast.value = MimirMessage(
            eventId = parkedRefusalToastEventId.incrementAndGet(),
            text = libraryFavoriteToastText(nowFavorite),
            messageClass = MimirMessageClass.Confirmation,
        )
        return nowFavorite
    }

    /**
     * Hide [game] from the shelf/gamelist without deleting anything  — it's recoverable from Settings › Library › Hidden. Reactive:
     * the gamelist drops it. [hidden] = false restores it (used by the restore surface).
     */
    fun setHidden(game: GameRow, hidden: Boolean) {
        if (hidden) {
            hideGame(game)
        } else {
            // A restore: whatever the list was told to hold back comes back with it.
            clearPendingRemoval(game.gameId)
            viewModelScope.launch(mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
                repository.setHidden(game.gameId, false)
            }
        }
    }

    /**
     * Hide [game], the way a player's confirmed hide does (walk 32g), in this order:
     *
     *  1. [departureSlot], if something has set one, is awaited. The game is still in the list, still focused, still
     *     drawn: this is where a future shatter plays.
     *  2. The game is marked pending and leaves the list. The list (and the screen, in its next frame) no longer carries
     *     it, so the card can dissolve and its neighbours close over it.
     *  3. [beforeWrite] runs on the main thread: the caller's survivor hand-off, so the focus already names a card that
     *     stays when the database's list drops the hidden one, and can never fall through to game one.
     *  4. ONE repository write hides the game ([LibraryRepository.setHidden]). Its star stays on, so nothing observing
     *     the library sees a star leave a game that is still there, and a restore brings the game back starred.
     *
     * If anything in that fails, or the scope is cancelled, the game is let back into the list before the failure goes on.
     * The coroutine starts undispatched, so with no slot set the whole of 2 and 3 has run when this returns.
     */
    internal fun hideGame(game: GameRow, beforeWrite: () -> Unit = {}) {
        viewModelScope.launch(mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                departureSlot?.invoke(game)
                markPendingRemoval(game, LibraryRemovalKind.HIDDEN)
                beforeWrite()
                repository.setHidden(game.gameId, true)
            } catch (t: Throwable) {
                clearPendingRemoval(game.gameId)
                throw t
            }
        }
    }

    /**
     * SHATTER, seat (a). The Library screen's gamelist sets this to [LibraryShatterStage.play] (walk-32h); with nothing set, the
     * default departure is a dissolve the screen draws on its own.
     *
     * A departure that has to play BEFORE the game leaves the list (a shatter breaks the card apart where it stands)
     * sets this to a function that does. [hideGame] awaits it first: the game is still in [games], still focused, still
     * drawn at its seat, and neither the pending mark nor the write has happened. The function should return when the
     * card may go, and it should leave the input gate ([com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate]) held
     * for as long as it plays, because a press that lands mid-departure moves focus off the card it is breaking.
     *
     * The card's last bounds, the other half of what a shatter needs: the centred card records where it is laid out in
     * [GamelistCentreBounds] (the gamelist's departure tracker owns it, so whoever sets this from the screen can read it
     * while the game is still listed), and the departure the gamelist seats afterwards carries the same rectangle as
     * [GamelistDeparture.lastBounds].
     *
     * It is called on the main thread and may suspend; an exception from it lets the game stay. Whoever sets it clears it.
     */
    @Volatile
    var departureSlot: (suspend (GameRow) -> Unit)? = null

    /**
     * Hide the Android game or app [packageName] from its shelf, in the shape [hideGame] gives an emulated game
     * (walk-32h, "every hide shatters the card"):
     *
     *  1. [nativeDepartureSlot], if something has set one, is awaited. The package is still on the shelf, still focused, and
     *     its card is still drawn, so the card can be broken where it stands.
     *  2. [beforeWrite] runs on the main thread: the survivor's focus hand-off, so the focus already names a card that
     *     stays when the shelf drops the hidden one and can never fall through to the first.
     *  3. [write] is the ONE write (the app's native shelf coordinator marks the package hidden, writes the exclusion and
     *     asks for the rescan). It runs in the same turn as 2, so no frame sits between them.
     *
     * With no slot set the hide still says where the package went, at once. If the slot throws, or the scope is
     * cancelled, nothing is written and the package stays. The coroutine starts undispatched, so with no slot set the
     * whole of 2 and 3 has run when this returns.
     */
    internal fun hideNativePackage(packageName: String, beforeWrite: () -> Unit = {}, write: () -> Unit) {
        viewModelScope.launch(mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
            val slot = nativeDepartureSlot
            if (slot != null) slot(packageName) else showHideReceipt(LibraryHideCopy.NativeReceipt)
            beforeWrite()
            write()
        }
    }

    /**
     * The native hide's seat (a), the counterpart of [departureSlot] for a package the shelf or the Favorites list draws.
     * The Library screen sets it to [LibraryShatterStage.play], which posts the hide's receipt itself (at once when the
     * card is not broken, as the last shard fades when it is). Called on the main thread; may suspend; whoever sets it
     * clears it.
     */
    @Volatile
    var nativeDepartureSlot: (suspend (String) -> Unit)? = null

    private fun markPendingRemoval(game: GameRow, kind: LibraryRemovalKind) {
        if (kind == LibraryRemovalKind.HIDDEN) {
            // The paint-immediately seed for a platform switch must not bring a hidden game back for the beat before the
            // live list corrects it. A hidden game is gone from every list, so its own platform's seed is scrubbed.
            platformListCache.computeIfPresent(game.platformId) { _, rows -> rows.filterNot { it.gameId == game.gameId } }
        }
        _pendingRemovals.update { it.with(game.gameId, PendingLibraryRemoval(_openPlatform.value, kind)) }
    }

    private fun clearPendingRemoval(gameId: GameId) {
        _pendingRemovals.update { it.without(gameId) }
    }

    /**
     * Manually correct the display title for [gameId] (a COSMETIC display
     * override, applied at the model boundary — never fed into recognition). Optimistically updates the
     * open detail snapshot(s) so an already-open launch card repaints immediately; Room's reactive
     * `observeGame` (backing [gameDetail]) settles every other surface with zero extra plumbing.
     */
    fun setTitleOverride(gameId: GameId, title: String) {
        if (_detailGame.value?.gameId == gameId) {
            _detailGame.value = _detailGame.value?.copy(title = title, hasTitleOverride = true)
        }
        if (_openGameDetail.value?.gameId == gameId) {
            _openGameDetail.value = _openGameDetail.value?.copy(title = title, hasTitleOverride = true)
        }
        viewModelScope.launch(Dispatchers.Default) { repository.setTitleOverride(gameId, title) }
    }

    /**
     * "Reset to detected name" — clears the manual override; display falls back to the detected/provider
     * title automatically (`libraryDisplayTitle` in `LibraryRepositoryImpl`). No optimistic snapshot
     * update here: unlike [setTitleOverride], the detected fallback string is not known client-side, so
     * the open detail waits one Room re-emission rather than guessing at a value to show meanwhile.
     */
    fun clearTitleOverride(gameId: GameId) {
        viewModelScope.launch(Dispatchers.Default) { repository.clearTitleOverride(gameId) }
    }

    /** Ask the hard modal to confirm a START-hide action for [game]. */
    fun requestHide(game: GameRow) {
        _pendingHideGame.value = game
    }

    /** Dismiss the START-hide confirmation without changing the library. */
    fun clearPendingHide() {
        _pendingHideGame.value = null
    }

    /**
     * Confirm the pending START-hide action. The game remains recoverable from Browse/Hidden. [beforeWrite] is the
     * caller's survivor hand-off; see [hideGame] for where it runs.
     */
    fun confirmPendingHide(beforeWrite: () -> Unit = {}) {
        val game = _pendingHideGame.value ?: return
        _pendingHideGame.value = null
        hideGame(game, beforeWrite)
    }

    /**
     * Couples the request token to the picker write (R6-5E-03): a dismissal and an in-flight load's commit
     * exclude each other — see the invariant on [reconcileDetailSnapshots].
     */
    private val openWithLock = Any()

    /**
     * Load the "Open with…" options for [game] (the platform's installable emulators + the current
     * override). No-op when no [LaunchController] is wired (Fake/preview path).
     *
     * **[openWith] is the loaded options, not "the picker is open"**. The picker is the Open-with focus
     * scope, which the caller pushes; this only keeps the options the card names its emulator from. They are replaced when a
     * load lands, dropped when the card closes, and never shown for another game: asking for a different game's options
     * clears the previous game's at once.
     */
    fun loadOpenWith(game: GameRow): Boolean {
        val port = launchPort ?: return false
        _detailGame.value = game
        refreshOpenWith(game, port)
        return true
    }

    private fun refreshOpenWith(game: GameRow, port: LibraryLaunchPort) {
        val request = synchronized(openWithLock) {
            if (openWithGameId != game.gameId) {
                _openWith.value = null
                openWithGameId = null
            }
            openWithRequestSource.incrementAndGet()
        }
        viewModelScope.launch(Dispatchers.Default) {
            val options = port.openWithOptions(game)
            // Read OUTSIDE the monitor, on purpose (R6-5E-03): the focus guard is not the race, and
            // LibraryDetailSnapshotRaceTest's rig holds this exact read to prove the token guard below closes it.
            val stillFocused = _detailGame.value?.gameId == game.gameId
            synchronized(openWithLock) {
                if (stillFocused && openWithRequestSource.get() == request) {
                    _openWith.value = options
                    openWithGameId = game.gameId
                }
            }
        }
    }

    /**
     * Apply an Open-With [candidate] as the game's override (persists). The picker closes by its scope; the options are not
     * cleared, and are loaded again once the write has landed (as Home's card does), so the EMULATOR fact names the new pick
     * and never falls back to "Automatic" in between.
     */
    fun chooseEmulator(game: GameRow, candidate: OpenWithCandidate) {
        if (!candidate.selectable) return
        val port = launchPort ?: return
        // The row names the pick at once (the picker closes on this very press); a load that began before it is invalidated, so it cannot put the
        // old emulator back, and the reload after the write replaces this with the truth.
        synchronized(openWithLock) {
            val current = _openWith.value
            if (current != null && openWithGameId == game.gameId) {
                openWithRequestSource.incrementAndGet()
                _openWith.value = openWithOptionsAfterPick(current, candidate)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            port.setEmulatorOverride(game, candidate)
            // Atomic, like reconcileDetailSnapshots: a focus move can land on the main thread between a read and
            // this write, and must neither be undone nor be given this game's override.
            _detailGame.update { snapshot ->
                if (snapshot?.gameId == game.gameId) snapshot?.copy(hasEmulatorOverride = true) else snapshot
            }
            refreshOpenWith(game, port)
        }
    }

    /** Reset the game to Auto (clear the override). Like [chooseEmulator]: the options are loaded again, not cleared. */
    fun resetEmulator(game: GameRow) {
        val port = launchPort ?: return
        viewModelScope.launch(Dispatchers.Default) {
            port.clearEmulatorOverride(game)
            // Atomic for the same reason as chooseEmulator.
            _detailGame.update { snapshot ->
                if (snapshot?.gameId == game.gameId) snapshot?.copy(hasEmulatorOverride = false) else snapshot
            }
            refreshOpenWith(game, port)
        }
    }

    /**
     * Dismiss the Open-With picker without changing the override. A load still in flight is dropped (a dismissed picker must
     * not be given options afterwards, R6-5E-03), and the options already loaded stay: the card keeps naming its emulator.
     */
    fun closeOpenWith() {
        synchronized(openWithLock) { openWithRequestSource.incrementAndGet() }
    }

    /** The card is closing or the options are no longer this game's: invalidate loads in flight and drop what was loaded. */
    private fun clearOpenWith() {
        synchronized(openWithLock) {
            openWithRequestSource.incrementAndGet()
            _openWith.value = null
            openWithGameId = null
        }
    }

    /** Capture the current Library surface before handing a native package launch to the app shell. */
    fun noteNativePackageLaunch(
        packageName: String,
        restoreLaunchCard: Boolean = false,
    ) {
        if (packageName.isBlank()) return
        _keyboardOpen.value = false
        _pendingLaunchReturnTarget.value = nativePackageReturnTarget(packageName, restoreLaunchCard)
    }

    fun clearPendingLaunchReturnTarget() {
        _pendingLaunchReturnTarget.value = null
        restoredLaunchReturnTarget = null
    }

    /**
     * The pending target put back from the host's saved instance state after the PROCESS was killed while the game ran (a low-memory kill during an
     * emulator load, the Thor, 2026-10-14). The new process has no session for it, so the return's session gate admits this instance as an
     * ended session ([isLaunchReturnRestoredAfterKill]); nothing else about the return changes. Only when no launch is pending.
     */
    @Volatile
    private var launchReturnRestoredAfterKill: LibraryLaunchReturnTarget? = null

    fun restorePendingLaunchReturnTargetAfterKill(target: LibraryLaunchReturnTarget): Boolean {
        if (_pendingLaunchReturnTarget.value != null) return false
        launchReturnRestoredAfterKill = target
        _pendingLaunchReturnTarget.value = target
        return true
    }

    fun isLaunchReturnRestoredAfterKill(target: LibraryLaunchReturnTarget): Boolean = target === launchReturnRestoredAfterKill

    /** The pending target whose restore has begun ([restoreLaunchReturnTargetState] ran for that very instance); see [restoringLaunchReturnTarget]. */
    @Volatile
    private var restoredLaunchReturnTarget: LibraryLaunchReturnTarget? = null

    /**
     * The pending launch-return target, but only while its RESTORE is under way: from [restoreLaunchReturnTargetState] until the target is cleared at
     * the cover's lift. The pending target itself is set at launch and stays set for the whole game session, so a launch that leaves Main
     * interactive (a game on the second screen) would otherwise read as "restoring" for every Library change made meanwhile and strip their motion.
     * Identity, not equality: a new launch of the same game is a new target.
     */
    fun restoringLaunchReturnTarget(): LibraryLaunchReturnTarget? =
        _pendingLaunchReturnTarget.value?.takeIf { it === restoredLaunchReturnTarget }

    /**
     * Resumed-host watchdog cleanup. Exact operation identity prevents a late timeout/result from
     * clearing a newer launch; IntentSent remains dispatch history while the live UI returns to Idle.
     *
     * `@Synchronized` locks on `this` — the same monitor [launch]'s completion arms now take (R6-F-07),
     * so a cancel and a late completion for the same operation can no longer interleave their check and
     * their write; whichever runs second sees the first one's result, not a snapshot from before it.
     */
    @Synchronized
    fun cancelPendingLaunch(expectedOperation: LibraryGameLaunchOperation): Boolean {
        if (latestLaunchOperation != expectedOperation) return false
        latestLaunchOperation = null
        _pendingLaunchReturnTarget.value = null
        if (_launchState.value == GameLaunchUiState.Launching ||
            _launchState.value == GameLaunchUiState.IntentSent
        ) {
            publishLaunchUi(GameLaunchUiState.Idle, ownerGameId = null)
        }
        return true
    }

    /**
     * The resumed-host watchdog's cleanup (F1, cert 2026-10-08): the intent was sent, and the emulator never took the
     * window from Mimir inside the grace period. It is [cancelPendingLaunch] with the answer left on the card: the
     * launch card of the game that was started says the emulator closed unexpectedly, instead of the card quietly
     * going back to Idle. The launch itself stays counted (its proof is already written and is not touched here).
     *
     * Only a launch still in flight is rewritten; a card the player has already moved on from is left alone, exactly
     * as [cancelPendingLaunch] leaves it.
     */
    @Synchronized
    fun closePendingLaunchUnexpectedly(expectedOperation: LibraryGameLaunchOperation): Boolean {
        if (latestLaunchOperation != expectedOperation) return false
        latestLaunchOperation = null
        _pendingLaunchReturnTarget.value = null
        val snapshot = _launchUiSnapshot.value
        if (snapshot.state == GameLaunchUiState.Launching || snapshot.state == GameLaunchUiState.IntentSent) {
            publishLaunchUi(
                GameLaunchUiState.ClosedUnexpectedly(snapshot.emulatorLabel),
                ownerGameId = snapshot.ownerGameId,
            )
        }
        return true
    }

    /**
     * The emulator has taken the screen from Mimir (the session ledger's `game_yielded`): "Starting <Emulator>…" is
     * over. The state stays [GameLaunchUiState.IntentSent] (the Bottom's NOW_PLAYING promotion and the launch guards
     * read it for the game's whole run); only the words that said it was still starting go.
     */
    @Synchronized
    fun noteEmulatorYielded(expectedOperation: LibraryGameLaunchOperation): Boolean {
        if (latestLaunchOperation != expectedOperation) return false
        val snapshot = _launchUiSnapshot.value
        if (snapshot.emulatorLabel == null) return false
        publishLaunchUi(snapshot.state, snapshot.ownerGameId, emulatorLabel = null)
        return true
    }

    fun restoreLaunchReturnTargetState(target: LibraryLaunchReturnTarget) {
        restoredLaunchReturnTarget = target
        _gamelistPlatformSwitchMotion.value = null
        _pendingGamelistFilterLanding.value = null
        _keyboardOpen.value = false
        _librarySearchPicker.value = null
        _librarySearchSession.value = null
        _librarySearchLastTopFocus.value = LibrarySearchFocusIdentity.Query
        _librarySearchReturnSnapshot.value = null
        preparedLibrarySearchOriginGameId = null
        // The loaded Open-with options are NOT cleared: a return that restores the card puts it back on screen, and for the frames
        // until its own reload lands (installLibraryLaunchReturnDetailSession asks for one) it would name "Automatic" for an
        // emulator the player picked . Another game's options never show for this one (loadOpenWith drops
        // them on the game change) and closing the card still clears them (closeGameDetail).
        _pendingHideGame.value = null
        publishLaunchUi(GameLaunchUiState.Idle, ownerGameId = null)
        _shelfMode.value = target.shelfMode
        _gamelistViewMode.value = target.gamelistViewMode
        when (target.surface) {
            LibraryLaunchReturnTarget.Surface.SHELF -> {
                _openPlatform.value = null
                _openPlatformHasFavorites.value = false
                _detailGame.value = null
                resetPerGamelistFilters()
            }
            LibraryLaunchReturnTarget.Surface.GAMELIST -> {
                _openPlatformHasFavorites.value = target.openPlatformId
                    .takeIf { it != FAVORITES_PLATFORM_ID }
                    ?.let { platformListCache[it]?.any(GameRow::isFavorite) == true }
                    ?: false
                _favoriteSegment.value = target.favoriteSegment
                _openPlatform.value = target.openPlatformId
                val origin = currentLibrarySearchOrigin()
                _librarySearchReturnSnapshot.value = origin?.let {
                    target.searchSnapshot ?: LibrarySearchReturnSnapshot(query = target.searchQuery)
                }
                updateSearchCriteria(
                    origin?.let { _librarySearchReturnSnapshot.value?.toLibrarySearchCriteria(it) }
                        ?: LibrarySearchCriteria(query = target.searchQuery),
                )
                _availableSearchFacets.value = LibrarySearchFacets.Empty
                _favoritesOnly.value = target.favoritesOnly && target.openPlatformId != FAVORITES_PLATFORM_ID
                _detailGame.value = null
            }
        }
    }

    fun captureOriginReturnTarget(focusedKey: FocusKey?): LibraryOriginReturnTarget {
        val openPlatform = _openPlatform.value
        val returnFocusKey = originReturnFocusKey(focusedKey)
        val searchSnapshot = currentLibrarySearchReturnSnapshot()
        return if (openPlatform == null) {
            LibraryOriginReturnTarget(
                surface = LibraryOriginReturnTarget.Surface.SHELF,
                shelfMode = _shelfMode.value,
                focusKey = returnFocusKey,
                gamelistViewMode = _gamelistViewMode.value,
            )
        } else {
            LibraryOriginReturnTarget(
                surface = LibraryOriginReturnTarget.Surface.GAMELIST,
                shelfMode = nativeReturnShelfMode(),
                focusKey = returnFocusKey,
                openPlatformId = openPlatform,
                favoriteSegment = _favoriteSegment.value,
                searchQuery = _searchQuery.value,
                favoritesOnly = _favoritesOnly.value && openPlatform != FAVORITES_PLATFORM_ID,
                searchSnapshot = searchSnapshot,
                gamelistViewMode = _gamelistViewMode.value,
            )
        }
    }

    fun restoreOriginReturnTargetState(target: LibraryOriginReturnTarget) {
        _gamelistPlatformSwitchMotion.value = null
        _pendingGamelistFilterLanding.value = null
        _keyboardOpen.value = false
        _librarySearchPicker.value = null
        _librarySearchSession.value = null
        _librarySearchLastTopFocus.value = LibrarySearchFocusIdentity.Query
        _librarySearchReturnSnapshot.value = null
        preparedLibrarySearchOriginGameId = null
        clearOpenWith()
        _pendingHideGame.value = null
        publishLaunchUi(GameLaunchUiState.Idle, ownerGameId = null)
        _shelfMode.value = target.shelfMode
        _gamelistViewMode.value = target.gamelistViewMode
        when (target.surface) {
            LibraryOriginReturnTarget.Surface.SHELF -> {
                _openPlatform.value = null
                _openPlatformHasFavorites.value = false
                _detailGame.value = null
                resetPerGamelistFilters()
            }
            LibraryOriginReturnTarget.Surface.GAMELIST -> {
                _openPlatformHasFavorites.value = target.openPlatformId
                    ?.takeIf { it != FAVORITES_PLATFORM_ID }
                    ?.let { platformListCache[it]?.any(GameRow::isFavorite) == true }
                    ?: false
                _favoriteSegment.value = target.favoriteSegment
                _openPlatform.value = target.openPlatformId
                val origin = currentLibrarySearchOrigin()
                _librarySearchReturnSnapshot.value = origin?.let {
                    target.searchSnapshot ?: LibrarySearchReturnSnapshot(query = target.searchQuery)
                }
                updateSearchCriteria(
                    origin?.let { _librarySearchReturnSnapshot.value?.toLibrarySearchCriteria(it) }
                        ?: LibrarySearchCriteria(query = target.searchQuery),
                )
                _availableSearchFacets.value = LibrarySearchFacets.Empty
                _favoritesOnly.value = target.favoritesOnly && target.openPlatformId != FAVORITES_PLATFORM_ID
                _detailGame.value = null
            }
        }
    }

    /**
     * Launch [game] (A/CONFIRM on its row). Resolves readiness via the
     * [LaunchController], dispatches the intent when Ready, and drives [launchState] so the UI shows the
     * honest state: a not-ready reason (NeedsEmulator / NeedsBios / MissingFile / Unsupported), a
     * "Launching…" on IntentSent, or the typed dispatch failure. Dispatch-only — no silent-death verdict
    * .
     *
     * No-op stub when no [LaunchController] is wired (Fake/preview path): shows a "not wired" state.
     */
    fun launch(
        game: GameRow,
        preferredDisc: Int = 0,
        launchMemberEntryKey: EntryKey? = null,
        retryAfterRelease: Boolean = true,
    ) {
        // Local re-press guard closes the sub-frame before the semantic authority is even consulted.
        // The authority remains the cross-surface mutex; this prevents a same-ViewModel burst from
        // allocating and publishing a second operation while the first dispatcher is still suspended.
        if (_launchUiSnapshot.value.state == GameLaunchUiState.Launching) return
        val returnTarget = romReturnTarget(game)
        val operation = LibraryGameLaunchOperation(processLaunchOperationSource.incrementAndGet())
        val preparing = LibraryGameSessionEvent.Preparing(
            operation = operation,
            game = game,
            returnTarget = returnTarget,
            startedAtEpochMs = System.currentTimeMillis(),
        )
        val admitted = gameSessionObserver.onEvent(preparing)
        if (!admitted) {
            // O-3 (2026-08-12): the mutex refusal used to return here with the UI untouched, leaving
            // launchState at whatever it was before (usually Idle) — the screen didn't change at all.
            // This synchronous refusal can overlap an earlier operation's Default-thread completion, so its
            // state and owner use the same monitor-protected publisher as that completion. O15: the observer
            // names what held it, as the session that refused this exact event read it.
            publishLaunchUi(
                GameLaunchUiState.SessionHeld(gameSessionObserver.holdAfterRefusal(preparing)),
                ownerGameId = game.gameId,
            )
            // R6-P0-02 follow-up ("release at the press", policy): the refusal may be held by a
            // stale parked lease. Ask the observer to release it, off this synchronous call path, and
            // retry the SAME admission exactly once -- `retryAfterRelease = false` on the recursive call
            // bounds this to one attempt, so a second refusal (still held, or held by something else)
            // falls through to the ordinary SessionHeld state above and stays there.
            //
            // R6-P0-02 review fix (item 2): a refusal that release could not clear (StillParked) publishes
            // the shared one-shot toast [LibraryScreen] paints -- never for NotParked (an ordinary
            // O15 refusal), never for Released (the retry below either admits or produces its own fresh
            // refusal), and never stacked (one `parkedRefusalToastEventId` per refused press).
            //
            // R6-P0-02 round-3 fix (BLOCKING): the PROBE (`releaseParkedLeaseAfterRefusal`) stays on
            // `Dispatchers.Default` -- it is necessary off Main and touches no ViewModel state on its own.
            // But the Released arm's retried [launch] call is not a probe: its body writes [_launchState],
            // [latestLaunchOperation], [_detailGame], and [_pendingLaunchReturnTarget] with no
            // synchronization of its own, while [cancelPendingLaunch] (`@Synchronized`) and the R6-F-07
            // completion arms below DO hold that monitor -- [launch] is documented as a main-thread writer,
            // and running its state-mutating retry from a background dispatcher breaks that invariant
            // (making the dormant R6-F-10 defect reachable). So only the retried [launch] call hops back
            // to [mainDispatcher] here; the probe itself is untouched.
            if (retryAfterRelease) {
                // Dispatchers.Default explicitly, like the dispatch coroutine below: viewModelScope's own
                // context is Dispatchers.Main.immediate, which a plain JVM unit test (no Robolectric, no
                // Dispatchers.setMain) cannot resolve -- a bare `viewModelScope.launch { }` here failed
                // sessionMutexRefusal* synchronously with "Main dispatcher had failed to initialize" even
                // though those tests never park anything.
                viewModelScope.launch(Dispatchers.Default) {
                    when (val outcome = gameSessionObserver.releaseParkedLeaseAfterRefusal(preparing)) {
                        ParkedLeaseReleaseOutcome.Released ->
                            withContext(mainDispatcher) {
                                launch(game, preferredDisc, launchMemberEntryKey, retryAfterRelease = false)
                            }
                        is ParkedLeaseReleaseOutcome.StillParked -> {
                            // AtomicLong, not a Main hop: the StateFlow's own `.value` setter is already a
                            // safe publish from any thread (R6-P0-02 round-3) -- only the counter needed to
                            // stop being a plain `var` written off Main.
                            _parkedRefusalToast.value = MimirMessage(
                                eventId = parkedRefusalToastEventId.incrementAndGet(),
                                text = outcome.message,
                                messageClass = MimirMessageClass.Warning,
                            )
                        }
                        ParkedLeaseReleaseOutcome.NotParked -> Unit
                    }
                }
            }
            return
        }
        latestLaunchOperation = operation
        _keyboardOpen.value = false
        _detailGame.value = game
        _pendingLaunchReturnTarget.value = null
        val port = launchPort
        if (port == null) {
            synchronized(this@LibraryViewModel) {
                if (latestLaunchOperation == operation) {
                    publishLaunchUi(GameLaunchUiState.Unwired, ownerGameId = game.gameId)
                }
            }
            gameSessionObserver.onEvent(
                LibraryGameSessionEvent.Failed(operation, game, returnTarget, "launch_port_unavailable"),
            )
            return
        }
        // Publish the return anchor before external dispatch can pause the host. The launch result either
        // confirms it or clears it, but a very fast emulator exit can no longer outrun lifecycle arming.
        _pendingLaunchReturnTarget.value = returnTarget
        publishLaunchUi(GameLaunchUiState.Launching, ownerGameId = game.gameId)
        viewModelScope.launch(Dispatchers.Default) {
            // After admission and the return-target
            // publish above, before dispatch, exactly where Home holds its own. A failure here never blocks the launch.
            runCatching { launchOpening.beforeDispatch(game) }
            runCatching { port.launch(game, preferredDisc, launchMemberEntryKey) }
                .onSuccess { result ->
                    // Monitor, like cancelPendingLaunch's own @Synchronized (R6-F-07, same shared `this`, no
                    // nested lock): the guard and the write must commit as one step, or a watchdog cancel
                    // landing between them is undone by this arm's late, now-stale write.
                    synchronized(this@LibraryViewModel) {
                        if (latestLaunchOperation == operation) {
                            launchCompletionGuardHook()
                            // The anchor was published before dispatch (above). A completion only CLEARS it, when the launch did not happen; it never
                            // writes it back: a return that lifted and cleared the anchor before this arm ran must not have it re-armed here (the next
                            // onPause would restore it again, and the restore's settled motion would read it as a restore in flight).
                            if (!result.launched) _pendingLaunchReturnTarget.value = null
                            publishLaunchUi(
                                GameLaunchUiState.from(result),
                                ownerGameId = game.gameId,
                                emulatorLabel = result.takeIf { it.launched }?.dispatchedEmulatorLabel(),
                            )
                        }
                    }
                    gameSessionObserver.onEvent(
                        LibraryGameSessionEvent.Settled(operation, game, returnTarget, result),
                    )
                    launchOpening.afterDispatch(viewModelScope, game, launched = result.launched)
                }
                .onFailure { error ->
                    // Same monitor as the success arm, for the same reason.
                    synchronized(this@LibraryViewModel) {
                        if (latestLaunchOperation == operation) {
                            _pendingLaunchReturnTarget.value = null
                            publishLaunchUi(GameLaunchUiState.Unwired, ownerGameId = game.gameId)
                        }
                    }
                    gameSessionObserver.onEvent(
                        LibraryGameSessionEvent.Failed(
                            operation = operation,
                            game = game,
                            returnTarget = returnTarget,
                            reason = error.javaClass.simpleName.ifBlank { "launch_failed" },
                        ),
                    )
                    launchOpening.afterDispatch(viewModelScope, game, launched = false)
                }
        }
    }

    private fun romReturnTarget(game: GameRow): LibraryLaunchReturnTarget =
        LibraryLaunchReturnTarget.gamelistRom(
            openPlatformId = _openPlatform.value ?: game.platformId,
            romPlatformId = game.platformId,
            gameId = game.gameId,
            shelfMode = _shelfMode.value,
            favoriteSegment = _favoriteSegment.value,
            searchQuery = _searchQuery.value,
            favoritesOnly = _favoritesOnly.value,
            restoreLaunchCard = _openGameDetail.value?.gameId == game.gameId,
            searchSnapshot = currentLibrarySearchReturnSnapshot(),
            gamelistViewMode = _gamelistViewMode.value,
        )

    private fun nativePackageReturnTarget(
        packageName: String,
        restoreLaunchCard: Boolean,
    ): LibraryLaunchReturnTarget {
        val openPlatform = _openPlatform.value
        val returnShelfMode = nativeReturnShelfMode()
        return if (openPlatform == null) {
            LibraryLaunchReturnTarget.shelf(
                shelfMode = returnShelfMode,
                shelfKey = packageName,
                restoreLaunchCard = restoreLaunchCard,
                gamelistViewMode = _gamelistViewMode.value,
            )
        } else {
            LibraryLaunchReturnTarget.gamelistNative(
                openPlatformId = openPlatform,
                nativePackageName = packageName,
                shelfMode = returnShelfMode,
                favoriteSegment = _favoriteSegment.value,
                searchQuery = _searchQuery.value,
                restoreLaunchCard = restoreLaunchCard,
                gamelistViewMode = _gamelistViewMode.value,
            )
        }
    }

    // ANDROID_APPS retired : the per-segment branches below existed only to
    // type-match the launched native package's own shelf lane on return. With no APP lane left there is
    // nothing left to match, so a Favorites/ANDROID_APPS launch now falls back to the live shelf mode —
    // provably EMULATION or ANDROID_GAMES at this point, never the retired mode.
    private fun nativeReturnShelfMode(): LibraryShelfMode =
        if (_openPlatform.value == FAVORITES_PLATFORM_ID) {
            when (_favoriteSegment.value) {
                FavoriteSegment.ANDROID_GAMES -> LibraryShelfMode.ANDROID_GAMES
                FavoriteSegment.ANDROID_APPS, FavoriteSegment.GAMES -> _shelfMode.value
            }
        } else {
            _shelfMode.value
        }

    private fun originReturnFocusKey(focusedKey: FocusKey?): FocusKey? {
        // The Enrich/Hero action is a child of one centred card. External return restores the owning game,
        // never the child action, so state changes cannot leave focus detached from its identity.
        if (focusedKey == GamelistGetArtKey) {
            return _detailGame.value?.let(::gameKey)
        }
        // The in-place Quick Launch panel can sit OVER a Library surface ,
        // so its slot keys can now arrive here. They belong to no Library scope: persisting one
        // poisons the process-restart origin and every bounded restore attempt fails (2026-08-11
        // review). No key beats a foreign key — the restore then lands on its sensible default.
        if (focusedKey?.value?.startsWith("home/quicklaunch/") == true) {
            return null
        }
        if (focusedKey?.value?.startsWith("library/search/") == true) {
            return librarySearchResultGameId(focusedKey)
                ?.takeIf { gameId -> games.value.any { it.gameId == gameId } }
                ?.let { gameId -> FocusKey("library/gamelist/${gameId.value}") }
                ?: LibrarySearchLauncherKey
        }
        return focusedKey
    }

    /**
     * After a per-game rescrape wrote a new cover (UXCI-01 Phase 4), repaint that tile in place: the repository
     * evicts its art memo and version-busts the URI so the gamelist Flow re-emits the fresh cover.
     */
    fun refreshArt(game: GameRow) {
        platformListCache.remove(game.platformId)
        viewModelScope.launch { repository.refreshArt(game.gameId) }
    }

    /** The fetch controller already published the exact repository media revision before returning. Remove
     *  only the warm seed so a later re-entry cannot paint the pre-Enrich row for one frame. */
    fun invalidateCachedArt(game: GameRow) {
        platformListCache.remove(game.platformId)
    }

    /**
     * Fire a rescan of a granted source root and report the honest pass outcome via [onResult]. The
     * SAF grant itself is owned by `:app` (it holds the ActivityResult contract); this just forwards
     * the uri to the repository. No-op cost against the Fake; a real scan against the repository implementation.
     */
    fun rescan(rootUri: String, onResult: (ScanResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.rescan(rootUri)
            onResult(result)
        }
    }

    /**
     * Factory so the app can construct the VM with its chosen [LibraryRepository] and [LaunchController].
     * The controller is optional (Fake/preview path passes null → launch is a "not wired" stub).
     */
    class Factory(
        private val repository: LibraryRepository,
        private val launchController: LaunchController? = null,
        private val gameSessionObserver: LibraryGameSessionObserver = NoOpLibraryGameSessionObserver,
        private val preDispatchHook: suspend (GameRow, LaunchReadiness.Ready, LaunchAttemptId) -> Unit =
            { _, _, _ -> },
        private val postDispatchHook: suspend (GameRow, LaunchReadiness.Ready, LaunchAttemptId) -> Unit =
            { _, _, _ -> },
        private val onPreDispatchDegraded:
            suspend (GameRow, LaunchAttemptId, PreDispatchOutcome) -> Unit = { _, _, _ -> },
        private val onDispatchFailed: suspend (GameRow, LaunchAttemptId) -> Unit = { _, _ -> },
        private val launchOpening: LibraryLaunchOpening = NoLibraryLaunchOpening,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                "LibraryViewModel.Factory can only create LibraryViewModel, got $modelClass"
            }
            return LibraryViewModel(
                repository = repository,
                launchController = launchController,
                gameSessionObserver = gameSessionObserver,
                preDispatchHook = preDispatchHook,
                postDispatchHook = postDispatchHook,
                onPreDispatchDegraded = onPreDispatchDegraded,
                onDispatchFailed = onDispatchFailed,
                launchOpening = launchOpening,
            ) as T
        }
    }

    companion object {
        /** Monotonic across every LibraryViewModel created in this app process. */
        private val processLaunchOperationSource = AtomicLong(0L)

        /**
         * The synthetic platform id that opens the favorites-only view. Deliberately un-typeable as a real
         * platform slug (double-underscored) so it can never collide with a scanned platform. When
         * [openPlatform] receives this, [games] serves the cross-platform favorites set instead.
         */
        val FAVORITES_PLATFORM_ID = PlatformId("__favorites__")
    }
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

private class LibrarySourceEmission(
    val loadedFor: PlatformId?,
    val rows: List<GameRow>,
    var acknowledgementPending: Boolean,
)

/** A visible gamelist and the platform it is loaded for; [loadedFor] is null while the rows are another's. */
internal data class LibraryVisibleGames(val loadedFor: PlatformId?, val rows: List<GameRow>)
