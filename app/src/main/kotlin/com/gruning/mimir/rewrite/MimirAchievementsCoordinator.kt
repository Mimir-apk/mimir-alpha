package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAccount
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsChdHashEngine
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsChdResident
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsClient
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerResolution
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerVerdict
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsMatch
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsM3uChdResolution
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsM3uChdTargetResolver
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsM3uResident
import com.gruning.mimir.rewrite.core.metadata.online.RaValidation
import com.gruning.mimir.rewrite.core.metadata.online.RaMatchOutcome
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.dao.GameHashSourceProjection
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The gamelist's RetroAchievements lane: it reads the focused game's file, asks RetroAchievements what it
 * carries, and holds the answer for the hero title's progress count and the achievement card.
 *
 * ## What keeps this off the scroll path
 *
 * [probe] is driven by a `LaunchedEffect` keyed on the gamelist's DESTINATION game id — a value that only
 * changes on a focus COMMIT, not on the frames between two cards. That gives the debounce for free and, more
 * importantly, gives CANCELLATION for free: scrolling past a game tears its effect down mid-read, so a user
 * sweeping through a platform never accumulates a queue of hashes for games they flew past. Nothing here
 * touches the scan, and nothing here runs for a card that is merely visible — only for the one they stopped on.
 *
     * The refusals below all happen BEFORE any file is opened, in increasing cost order: RA platform support,
     * the consent/credential gate, local identification capability, then the file-size ceiling. The expensive
     * path is only reached when Mimir can compute the platform's identifier for a connected account.
 *
 * ## Honesty
 *
 * Mimir does not emulate and can never unlock an achievement — the RA-enabled emulator does. This lane only
 * ever READS. Every state that is not a completed lookup renders as unknown with a sentence about what Mimir
 * did or did not do; none of them is ever allowed to read as "RetroAchievements does not carry this game".
 */
internal class MimirAchievementsState(
    private val context: Context,
    private val seams: MimirAchievementsSeams = MimirAchievementsSeams(),
) {
    private val contentOpener = AndroidContentOpener(context)
    private val m3uChdTargetResolver = RetroAchievementsM3uChdTargetResolver(contentOpener)

    /**
     * Both gate halves are read PER CALL rather than captured, so revoking the opt-in or clearing the
     * credentials takes effect on the very next lookup instead of at the next process start.
     */
    private val client = RetroAchievementsClient(
        gateOpen = { gateOpen() },
        account = { storedAccount() },
        onCredentialsRejected = RetroAchievementsCredentialStore::markRejectedIfCurrent,
        chdHashEngine = RetroAchievementsChdHashEngine(
            residentInventory = { limit ->
                MimirDatabaseFactory.get(context).gameDao()
                    .availableChdShapedResidents(limit)
                    .map { resident ->
                        RetroAchievementsChdResident(
                            entryKey = resident.entryKey,
                            sourceRootUri = resident.sourceRootUri,
                            relativePath = resident.relativePath,
                            documentUri = resident.sourceDocumentUri,
                            managedPath = resident.managedPath,
                            sizeBytes = resident.sizeBytes,
                        )
                    }
            },
            opener = contentOpener,
        ),
    )

    /** The stored RetroAchievements sign-in as the client takes it, or null when there is no usable one. Reads and decrypts the store: call it off the main thread. */
    private fun storedAccount(): RetroAchievementsAccount? =
        RetroAchievementsCredentialStore.load(context)
            ?.takeIf { it.isUsable }
            ?.let {
                RetroAchievementsAccount(
                    username = it.username,
                    apiKey = it.apiKey,
                    credentialRevision = it.credentialRevision,
                )
            }

    /** What a bulk read captures its sign-in from: a test's own source, else the stored one when the sources are the real client's, else nothing (a test's sources hold their own). */
    private fun bulkCredentials(): (() -> RetroAchievementsAccount?)? {
        seams.credentials?.let { return it }
        if (seams.bulkGames != null) return null
        return { storedAccount() }
    }

    /**
     * The durable ledger: this account's bulk answer, game-to-RA-id resolution and per-game detail, in app-private files under the files directory. A restart, an
     * install over the top and a switch of account all find what was learned; nothing here is ever sent anywhere.
     */
    private val ledger: RetroAchievementsLedger? = seams.ledger ?: RetroAchievementsLedgers.forContext(context)

    private val verdicts = RetroAchievementsVerdictCache(clock = seams.clock)

    /**
     * The shared request fence, the last settled answer per game, the ambient-probe record and the account epoch (see
     * [RetroAchievementsLaneBook]). the count lives on the launch card only, read fresh when the card opens.
     */
    private val book = RetroAchievementsLaneBook()
    private val probeFence = book.fence

    /**
     * Everything this lane collects or launches off the process-wide flows lives in this scope, and [close] cancels it: the state is remembered per
     * composition and holds an Activity context, so a collector that outlived it would retain that Activity.
     */
    private val lifetime = CoroutineScope(SupervisorJob() + seams.scopeContext)

    /**
     * The single live answer, tagged with the game it is about. One slot rather than a map: exactly one game
     * is the destination at a time, and tagging it means a label can never be painted next to the wrong
     * title — [laneStateFor] simply returns null when the tag does not match, which renders as nothing.
     */
    private var probed by mutableStateOf<MimirPublishedLane?>(null)

    /** The game whose achievement card is open, or null. Held by id so an evicted row cannot pin a stale one. */
    var openForGameId by mutableStateOf<String?>(null)
        private set

    /** The open game's platform, which seeds the medals' per-platform leather tint (the coverless-book rule). */
    var openForPlatformId by mutableStateOf<String?>(null)
        private set

    var homeProbeEpoch by mutableStateOf(0L)
        private set

    var homeProbeGame by mutableStateOf<GameRow?>(null)
        private set

    /** Monotonic controller request consumed by the card's bounded list viewport. */
    var cardScrollRequest by mutableStateOf<MimirAchievementScrollRequest?>(null)
        private set

    /**
     * The Activity page's cold-start bulk read (every game with progress, and the points), held in [CompanionActivityAchievements] where the hub view
     * and the strip read it. See [ActivityBulkLoader]; the sources default to the real client and library and are replaced only by tests.
     */
    private val bulk = ActivityBulkLoader(
        book = book,
        account = { seams.account.value },
        // Each source gets the sign-in the read captured when it began (`pinned`); a test's double ignores it.
        loadGames = { pinned -> seams.bulkGames?.invoke() ?: client.userGames(pinned) },
        loadPoints = { pinned -> seams.bulkPoints?.invoke() ?: client.userPoints(pinned) },
        // The recent-unlocks strip is not on the page , so the real read asks nothing for it; a test's own source is still honoured.
        loadRecent = { _ -> seams.bulkRecent?.invoke() ?: RaRecentOutcome.Unavailable },
        library = seams.bulkLibrary ?: {
            MimirDatabaseFactory.get(context).gameDao().allPrimaryGames()
                .map { LocalGameKey(gameId = it.gameId, platformId = it.platformId, title = it.title) }
        },
        platformIdFor = { consoleName -> client.platformIdForConsoleName(consoleName) },
        publish = { account, snapshots, points -> CompanionActivityAchievements.publishBulk(account, snapshots, points) },
        publishRecent = { account, unlocks -> CompanionActivityAchievements.publishRecent(account, unlocks) },
        publishPoints = { account, points -> CompanionActivityAchievements.publishPoints(account, points) },
        publishSoftcore = { account, softcore, hydrated -> CompanionActivityAchievements.publishSoftcore(account, softcore, hydrated) },
        clock = seams.clock,
        workContext = seams.bulkContext,
        publishContext = seams.publishContext,
        ledger = ledger,
        publishHydrated = { account, snapshots, points, recent ->
            CompanionActivityAchievements.publishHydrated(account, snapshots, points, recent)
        },
        markSettled = CompanionActivityAchievements::markSettled,
        log = seams.bulkLog,
        // The real sources are pinned to the stored sign-in captured once per read; a test that supplies its own sources (and no credentials) is not.
        credentials = bulkCredentials(),
    )

    /**
     * The reads that come AFTER a game's play ended: the trailing read a short session's return was owed, the one follow-up that catches RetroAchievements' lagging
     * counts up, and the same for a game played outside Mimir (see [onHostResumed]). One pending read at most; an account change drops it.
     */
    private val refresher = ActivityBulkRefresher(
        scope = lifetime,
        loader = bulk,
        clock = seams.clock,
        gateOpen = { bulkGateOpen() },
        pause = seams.bulkPause,
    )

    /**
     * The "Achievement earned" watch: while a Mimir-launched game runs it reads that game's progress every two minutes, and once more when the session returns (after the
     * page's own read above), and toasts what the play earned (see [AchievementSessionPoller]). It shares the client, so every read waits its turn at the process-wide pacer.
     */
    private val sessionPoller = AchievementSessionPoller(
        scope = lifetime,
        sessions = seams.playingSessions,
        gateOpen = { bulkGateOpen() },
        account = { seams.account.value },
        raGameIdFor = { gameId -> raGameIdForPlayedGame(gameId) },
        held = { seams.requestsHeld?.invoke() ?: client.requestsHeld() },
        read = { raGameId -> seams.sessionProgress?.invoke(raGameId) ?: client.progressFor(raGameId) },
        loadToasted = { name -> ledger?.readToasted(name).orEmpty() },
        saveToasted = { name, ids -> ledger?.saveToasted(name, ids) },
        post = seams.toastSink,
        epoch = { book.accountEpoch() },
        // The poll is the game's fresh read: the hub's card detail is seeded from it (the probe does the same), so the card shows the unlock at once and is not asked for again within seconds.
        onProgress = { progress, began ->
            cardDetails.seed(progress, began)
            if (bulk.noteLaneResult(seams.account.value, progress, began)) persistLaneResultsSoon()
        },
        pause = seams.bulkPause,
        log = seams.bulkLog,
        intervalMillis = seams.sessionPollMillis,
        workContext = seams.bulkContext,
    )

    /** The RetroAchievements id a played library game was resolved to: the Activity page's snapshot of it, else the link the lane learned; null while it has neither. */
    private fun raGameIdForPlayedGame(gameId: String): Int? =
        CompanionActivityAchievements.snapshots.value.firstOrNull { it.gameId == gameId }?.raGameId?.takeIf { it > 0 }
            ?: book.knownLocalByRaId().entries.firstOrNull { it.value == gameId }?.key

    /** When Mimir left the front, to tell a game played outside it from a pause. */
    private val outsidePlay = OutsidePlayWatch(seams.clock)

    /** The newest game-session key (null with no session), so coming back to Mimir can tell a Mimir-launched game still running from none. */
    @Volatile
    private var latestSessionKey: String? = null

    /**
     * The per-game detail behind an Activity card (its badges and title art): asked ONLY for a card the hub is showing, at most once per game per five
     * minutes, epoch-fenced. Nothing here runs at cold start.
     */
    private val cardDetails = ActivityCardDetailLoader(
        scope = lifetime,
        book = book,
        account = { seams.account.value },
        fetch = seams.cardDetailFetch ?: { raGameId -> client.progressFor(raGameId) },
        sink = CompanionActivityCardDetails.sink,
        clock = seams.clock,
        workContext = seams.bulkContext,
        publishContext = seams.publishContext,
        ledger = ledger,
        held = { seams.requestsHeld?.invoke() ?: client.requestsHeld() },
    )

    // The game-to-RA-id resolution (what RetroAchievements said about each library file, and which library game each RA game was identified as) is kept per account
    // in the ledger, so a restart resolves only games that are new or changed. In memory it belongs to ONE account at a time: a switch saves it, drops it and reads
    // the new account's, so nothing learned under one account is used under another.

    private val resolutionLock = Any()

    /** The account whose resolution is held in memory; guarded by [resolutionLock]. */
    private var resolutionAccount: String? = null

    /** Completes once [resolutionAccount]'s saved resolution has been read in (at once when there is none to read); guarded by [resolutionLock]. */
    private var resolutionReady = CompletableDeferred<Unit>().also { it.complete(Unit) }

    private var resolutionFlush: Job? = null

    /** [loaded]: the account's saved rows had been read into memory when this was taken (until then memory holds less than the disk, never more). */
    private class ResolutionSnapshot(val account: String?, val resolution: RetroAchievementsLedgerResolution, val loaded: Boolean)

    private fun resolutionSnapshotLocked() = ResolutionSnapshot(
        account = resolutionAccount,
        resolution = RetroAchievementsLedgerResolution(verdicts.toLedger(), book.knownLocalByRaId()),
        loaded = resolutionReady.isCompleted,
    )

    /**
     * Blocking file work: callers are on the IO context. A failed write is a missed save, never a failure of the lane. What memory learned is MERGED into what
     * the account already has saved (memory wins a key), and nothing is written before the saved rows were read in or when there is nothing learned, so a
     * save can add to the ledger but never erase from it.
     */
    private fun saveResolution(snapshot: ResolutionSnapshot) {
        val store = ledger ?: return
        val account = snapshot.account ?: return
        if (!snapshot.loaded || snapshot.resolution.isEmpty) return
        runCatching {
            val saved = store.readResolution(account)
            val verdicts = LinkedHashMap<String, RetroAchievementsLedgerVerdict>()
            saved.verdicts.forEach { verdicts[it.fileKey] = it }
            snapshot.resolution.verdicts.forEach { verdicts[it.fileKey] = it }
            store.saveResolution(
                account,
                RetroAchievementsLedgerResolution(verdicts.values.toList(), saved.localByRaGameId + snapshot.resolution.localByRaGameId),
            )
        }
    }

    /**
     * The account whose resolution is held becomes [account]: the old one's is saved and dropped from memory, and the new one's saved resolution is read in.
     * A change of key state or opt-in that leaves the account the same does nothing.
     */
    private fun switchResolutionTo(account: String?) {
        val store = ledger ?: return
        val mine = account?.takeIf(String::isNotBlank)
        val ready = CompletableDeferred<Unit>()
        val leaving = synchronized(resolutionLock) {
            if (mine == resolutionAccount) return
            val snapshot = resolutionSnapshotLocked()
            verdicts.clear()
            book.clearKnownLocal()
            resolutionAccount = mine
            resolutionReady = ready
            snapshot
        }
        lifetime.launch(seams.bulkContext) {
            try {
                saveResolution(leaving)
                val saved = mine?.let { name -> runCatching { store.readResolution(name) }.getOrNull() }
                if (saved != null) {
                    synchronized(resolutionLock) {
                        if (resolutionAccount == mine) {
                            verdicts.restoreFrom(saved.verdicts)
                            book.restoreKnownLocal(saved.localByRaGameId)
                        }
                    }
                }
            } finally {
                ready.complete(Unit)
            }
        }
    }

    /** What a probe or a hydration waits for before it reads the resolution: the saved rows of the signed-in account, which are a small file. Bounded, so it can never hold a probe. */
    private suspend fun awaitResolutionReady() {
        val ready = synchronized(resolutionLock) { resolutionReady }
        if (!ready.isCompleted) withTimeoutOrNull(RESOLUTION_READY_WAIT_MS) { ready.await() }
    }

    /** Something was learned: save the resolution shortly (one write for a burst of probes), off the main thread. */
    private fun persistResolutionSoon() {
        if (ledger == null) return
        synchronized(resolutionLock) {
            if (resolutionFlush?.isActive == true) return
            resolutionFlush = lifetime.launch(seams.bulkContext) {
                delay(seams.ledgerFlushDelayMillis)
                saveResolution(synchronized(resolutionLock) { resolutionSnapshotLocked() })
            }
        }
    }

    /** Per-game reads were queued for the ledger (see [ActivityBulkLoader.noteLaneResult]): write them shortly, one write for a burst of probes, off the main thread. */
    private fun persistLaneResultsSoon() {
        if (ledger == null) return
        lifetime.launch(seams.bulkContext) {
            delay(seams.ledgerFlushDelayMillis)
            bulk.flushLaneResults()
        }
    }

    /** RetroAchievements is connected, opted in and the key not refused: the only state in which the bulk read may ask anything. */
    private fun bulkGateOpen(): Boolean =
        seams.optedIn.value && !seams.account.value.isNullOrBlank() && seams.keyState.value != RaKeyState.REJECTED_THIS_SESSION

    /** The bulk load in flight, so an account change can cancel the old account's before the new account's starts. */
    private var bulkJob: Job? = null

    /**
     * Cold start: ONE bulk load, started by the app shell after the first frame (never before it, never on a scan or launch path). Does nothing when
     * RetroAchievements is not connected and opted in.
     */
    fun startActivityBulk() {
        if (bulkGateOpen()) bulkJob = lifetime.launch { bulk.load() }
    }

    init {
        // The Bottom paints RA game icons the lane stages to disk (keyless, opt-in gated, not an API call); it asks through this seam while the lane lives.
        CompanionActivityIcons.install(this) { path, target -> client.stageIcon(path, target) }
        CompanionActivityCardDetails.install(this, cardDetails)
        // A sign-in, sign-out, account, key-state or opt-in change drops what was settled and held for the old account; a game's session returning
        // refreshes the bulk read (throttled). The lambdas capture the book, the loader and this state, and [close] cancels the scope.
        book.attach(
            scope = lifetime,
            sessionKeys = seams.sessionKeys.onEach { latestSessionKey = it },
            accountSources = listOf(seams.account, seams.keyState, seams.optedIn),
            onSessionReturned = {
                if (bulkGateOpen()) {
                    // The session is taken NOW: the page's read can take a while, and another game may have started by the time it is done.
                    val finished = sessionPoller.sessionToRead()
                    refresher.onReturned(afterRead = { sessionPoller.onReturned(finished) })
                }
            },
            // The slot may hold the old account's answer: nothing of it may stand as a placeholder or a hero count. The bulk answer is dropped with it
            // (the epoch is already bumped, so a read still in flight cannot land), and the new account, if there is one, loads its own.
            onAccountChanged = {
                // A read that was waiting for the old account's play to settle is not the new account's.
                refresher.cancel()
                probed = retroAchievementsSlotAfterAccountChange(
                    slot = probed,
                    currentAccount = seams.account.value,
                    keyState = seams.keyState.value,
                )
                bulk.reset()
                cardDetails.reset()
                // The new account's saved resolution replaces the old one's in memory; the old one's rows stay on disk for when it signs back in.
                switchResolutionTo(seams.account.value)
                // A sign-out, an opt-out or a refused key leaves no RA icons on disk; they are public and are fetched again on the next sign-in.
                if (!bulkGateOpen()) {
                    lifetime.launch(seams.bulkContext) {
                        val clear = seams.iconClear
                        if (clear != null) clear() else CompanionActivityIcons.storeFor(context).clear()
                    }
                }
                CompanionActivityAchievements.dropAccountsExcept(seams.account.value)
                // The old account's load is cancelled and the new account's QUEUES behind it: BUSY never swallows an account-change refresh.
                bulkJob?.cancel()
                // The saved answer of the account that is signed in now goes on the page first (switching back is instant), then the live read refreshes it.
                bulkJob = if (bulkGateOpen()) {
                    lifetime.launch {
                        awaitResolutionReady()
                        bulk.hydrate()
                        bulk.load(waitForRunning = true)
                    }
                } else {
                    null
                }
            },
        )
        sessionPoller.start()
        // Cold start: what the ledger holds goes on the page NOW, from a small file read off the main thread and well before the first live read starts after
        // the first frames, so the Bottom shows the last known counts instead of zeros while RetroAchievements is asked again.
        switchResolutionTo(seams.account.value)
        if (bulkGateOpen()) {
            lifetime.launch {
                awaitResolutionReady()
                bulk.hydrate()
            }
        }
    }

    /** Mimir's window left the front (its Activity paused): the start of an absence that may be a game played elsewhere. */
    fun onHostPaused() = outsidePlay.onLeft()

    /**
     * Mimir's window is back in front. After at least two minutes away, with no Mimir-launched game running (that one's own return already refreshed), the Activity page
     * may be behind a game played outside Mimir (launched from the emulator's own icon or from Recents), which nothing else here can hear: ask for a refresh through
     * the same five-minute window a return uses.
     */
    fun onHostResumed() {
        if (outsidePlay.onBack() && bulkGateOpen() && !gameSessionRunning()) refresher.onReturned()
    }

    /** A Mimir-launched game's session is under way: any phase but the ones that are over (ended, failed, stale). */
    private fun gameSessionRunning(): Boolean {
        val key = latestSessionKey ?: return false
        return listOf(MimirSessionPhase.ENDED, MimirSessionPhase.FAILED, MimirSessionPhase.STALE).none { key.endsWith("/${it.name}") }
    }

    /** The lane's resolution memory (file verdicts, library links), for tests and diagnostics. */
    internal val verdictBook: RetroAchievementsVerdictCache get() = verdicts
    internal val laneBook: RetroAchievementsLaneBook get() = book

    /** Detach from the process-wide flows and cancel anything this lane launched. Called when the owning composition is disposed. */
    fun close() {
        CompanionActivityIcons.release(this)
        CompanionActivityCardDetails.release(this)
        lifetime.cancel()
    }

    val isCardOpen: Boolean get() = openForGameId != null

    /**
     * Opening a card ALWAYS issues its own fresh probe, whatever the launch card's earlier read holds (nothing is cached between reads): the answer already held for the game may
     * stand as an immediate placeholder (see [openCardFromHome]) and the fresh answer replaces it.
     */
    fun openCard(game: GameRow) = openCardFromHome(game)

    /**
     * SELECT on a game opens its card and the card owns a fresh probe (it always asks afresh, and never reads the ambient cache). A game with a
     * settled answer keeps it as the placeholder until the fresh one lands; a game with none shows Checking.
     */
    fun openCardFromHome(game: GameRow) {
        // The card asks afresh: it supersedes an ambient probe still running for this game, whose late answer must not overwrite the card's.
        probeFence.begin(game.gameId.value, null)
        if (!retroAchievementsCardShowsPlaceholder(laneStateFor(game.gameId.value))) {
            val account = seams.account.value
            publish(DerivedLane.of(game.gameId.value, account, retroAchievementsCheckingState()), account)
        }
        cardScrollRequest = null
        cardFence += 1L
        openForGameId = game.gameId.value
        openForPlatformId = game.platformId.value
        homeProbeGame = game
        homeProbeEpoch += 1L
    }

    fun dismissCard() {
        cardFence += 1L
        val goneGameId = openForGameId
        val goneGame = homeProbeGame
        openForGameId = null
        openForPlatformId = null
        homeProbeGame = null
        cardScrollRequest = null
        if (goneGameId != null && goneGame != null) recoverAfterCardGone(goneGameId, goneGame)
    }

    /**
     * Opening the card superseded the game's ambient probe, and the card's own answer is dropped once the card is gone. "Checking" must never
     * outlive every probe for its game, so when no ambient probe will answer it: put back what was last published for the game, or ask again.
     */
    private fun recoverAfterCardGone(gameIdValue: String, game: GameRow) {
        val checking = laneStateFor(gameIdValue) == retroAchievementsCheckingState()
        when (val recovery = book.recoveryAfterCardGone(gameIdValue, slotIsChecking = checking)) {
            CheckingRecovery.None -> Unit
            is CheckingRecovery.Restore -> publish(recovery.lane, seams.account.value)
            CheckingRecovery.Reprobe -> lifetime.launch { probe(game) }
        }
    }

    fun requestCardScroll(direction: MimirAchievementScrollDirection) {
        cardScrollRequest = MimirAchievementScrollRequest(
            epoch = (cardScrollRequest?.epoch ?: 0L) + 1L,
            direction = direction,
        )
    }

    /** Connect-time validation deliberately bypasses the runtime consent/credential gate inside the client. */
    suspend fun validateAccount(username: String, apiKey: String): RaValidation =
        client.validate(RetroAchievementsAccount(username.trim(), apiKey.trim()))

    /** The lane state for exactly this game, or null when the live answer is about a different one. */
    fun laneStateFor(gameIdValue: String?): MimirAchievementLaneState? = publishedFor(gameIdValue)?.state

    private fun publishedFor(gameIdValue: String?): MimirPublishedLane? =
        probed?.takeIf { it.gameIdValue == gameIdValue }

    /**
     * The count for the hero title, or null when there is nothing honest to show. A field read: the label was built
     * with the answer, so the hero's recompositions format nothing.
     */
    fun progressLabelFor(game: GameRow): String? = publishedFor(game.gameId.value)?.label

    /**
     * The card for the open game. Unknown when the lane never resolved — never an empty "0 of 0". A field read: the
     * ledger's sort and row mapping ran once, off the main thread, when the answer was published, not on every
     * recomposition of the overlay or of the overlay coordinator's hint reads.
     */
    fun cardState(): MimirAchievementCardState = publishedFor(openForGameId)?.card ?: mimirAchievementCardState(null)

    /** The RetroAchievements account signed in now, or null: the Activity page's rows are tagged with it, and Main's card reads only that account's. */
    fun signedInAccount(): String? = seams.account.value

    /**
     * The open game's whole answer (its badges, their dates and RA's art paths) when the lane has loaded it, else null (checking, unresolved, no set). Main's rich card
     * page is drawn from this; a card with none is the ledger frame's own sentence. A field read, like [cardState].
     */
    fun cardProgress(): RetroAchievementsProgress? =
        (publishedFor(openForGameId)?.state as? MimirAchievementLaneState.Loaded)?.progress

    /**
     * The open card's controller walk, set by the page while it is mounted ([installCardGrid]) and read by the overlay's dispatcher. Null with no page mounted (the ledger
     * frame scrolls with up and down instead). Main thread only.
     */
    var cardGrid: ((ControllerAction) -> Boolean)? = null
        private set

    /**
     * The life of the open card and its page: advances when a card opens, when it is dismissed and when its page lets go of the walk. An A that Main's router held for the page's
     * frame is dropped if this moved before the frame ([InputRouter.confirmFence]), so a Back between the press and the frame never lets that A land on the screen underneath.
     */
    var cardFence: Long = 0L
        private set

    fun installCardGrid(dispatch: (ControllerAction) -> Boolean) {
        cardGrid = dispatch
    }

    /** Releases only what [installCardGrid] was given by the same page, so a stale release never unplugs its successor. */
    fun releaseCardGrid(dispatch: (ControllerAction) -> Boolean) {
        if (cardGrid === dispatch) {
            cardGrid = null
            cardFence += 1L
        }
    }

    /**
     * Resolve [game]'s achievements. Suspends for the whole read so the caller's `LaunchedEffect` owns its
     * lifetime; every early return below publishes a state that says what happened.
     */
    suspend fun probe(game: GameRow) = probeInternal(game, expectedHomeEpoch = null)

    /** Home's overlay owns this probe; a close invalidates every later publish even if a dependency swallows cancellation. */
    suspend fun probeFromHome(game: GameRow, expectedHomeEpoch: Long) =
        probeInternal(game, expectedHomeEpoch)

    private suspend fun probeInternal(game: GameRow, expectedHomeEpoch: Long?) {
        // One shared, per-game, monotonic freshness token fences BOTH probe kinds (the ambient Home / gamelist probe and the card's own): this
        // request supersedes any older one for the same game (cancelled where it honours cancellation) and a late answer from a superseded request
        // is dropped at [report]. The token is claimed before any suspension, so request order is the order the probes were asked in.
        // The account epoch is captured at the same moment: a probe may publish, write the cache or record a settled answer only while no sign-in,
        // sign-out, account, key-state or opt-in change has happened since.
        val epoch = book.accountEpoch()
        val token = probeFence.begin(game.gameId.value, currentCoroutineContext()[Job])
        // The ambient probe (no card epoch) is the one that answers an unresolved game by itself; the card's probe is dropped once its card is gone.
        val ambient = expectedHomeEpoch == null
        if (ambient) book.ambientBegan(game.gameId.value, token)
        try {
            probeBody(game, expectedHomeEpoch, token, epoch)
        } finally {
            if (ambient) book.ambientEnded(game.gameId.value, token)
            probeFence.finish(game.gameId.value, token)
        }
    }

    private suspend fun probeBody(game: GameRow, expectedHomeEpoch: Long?, token: Long, epoch: Long) {
        val gameIdValue = game.gameId.value
        val placeholderShown = expectedHomeEpoch != null && retroAchievementsCardShowsPlaceholder(laneStateFor(gameIdValue))
        val activityAccount = seams.account.value
        // [derived] is the answer with its card, label and companion snapshot already built (off the main thread);
        // the quick early states build theirs here, which costs nothing (no rows).
        fun mayPublish(state: MimirAchievementLaneState): Boolean =
            probeFence.isCurrent(gameIdValue, token) &&
                retroAchievementsPublishAllowed(
                    epochUnchanged = book.accountUnchangedSince(epoch),
                    accountUnchanged = seams.account.value == activityAccount,
                    state = state,
                    keyState = seams.keyState.value,
                ) &&
                (expectedHomeEpoch == null || homeProbeIsCurrent(
                    expectedEpoch = expectedHomeEpoch,
                    currentEpoch = homeProbeEpoch,
                    expectedGameId = gameIdValue,
                    currentGameId = homeProbeGame?.gameId?.value,
                    cardOpen = isCardOpen,
                ))

        fun report(
            state: MimirAchievementLaneState,
            derived: DerivedLane = DerivedLane.of(gameIdValue, activityAccount, state),
        ) {
            // Structural only (platform id + typed state): the one trace a device report can be triaged from.
            runCatching {
                android.util.Log.i("MimirRA", mimirAchievementLaneDiagnostic(game.platformId.value, state))
            }
            if (mayPublish(state)) {
                publish(derived, activityAccount)
                book.noteSettled(derived, epoch)
                // A loaded answer is this game's one read for the shared detail window: the Bottom's hub reads the same cache and asks nothing more (the probe itself stays fresh).
                (derived.published.state as? MimirAchievementLaneState.Loaded)?.let {
                    cardDetails.seed(it.progress, epoch)
                    // The same read also goes into the durable ledger's list of games (batched, off the main thread), so what the Bottom has seen once shows at the next cold start.
                    if (bulk.noteLaneResult(activityAccount, it.progress, epoch)) persistLaneResultsSoon()
                }
            }
        }

        // The saved answer, shown while the fresh read is under way. It is a placeholder, not a check: not recorded as settled, no stamp on the detail window, and
        // nothing for the Bottom's checked games.
        fun reportSaved(state: MimirAchievementLaneState) {
            if (mayPublish(state)) probed = mimirPublishedLane(gameIdValue, state, activityAccount)
        }

        // A test's stand-in for the file and network work below: it reports through the very same gate a real probe does.
        seams.probeWork?.let { work ->
            work(game) { state, derived -> report(state, derived ?: DerivedLane.of(gameIdValue, activityAccount, state)) }
            return
        }

        // The credential read is a SharedPreferences read plus a Keystore decrypt, and the store's lock may be held
        // by another thread: never on the main thread this probe is launched on.
        val credentialsHealthy = withContext(Dispatchers.IO) {
            RetroAchievementsCredentialStore.hasHealthyCredentials(context)
        }
        retroAchievementsPreflightState(
            supported = client.supports(game.platformId.value),
            keyState = seams.keyState.value,
            hasUsableCredentialsForSession = credentialsHealthy,
            connectedServicesOptedIn = ConnectedServicesStore.optedIn.value,
        )?.let {
            report(it)
            return
        }
        if (!client.canIdentify(game.platformId.value)) {
            report(retroAchievementsIdentificationUnavailableState())
            return
        }

        // A card that opened over an answer it already holds keeps that answer on screen until the fresh one lands.
        if (!placeholderShown) report(retroAchievementsCheckingState())

        val source = withContext(Dispatchers.IO) {
            try {
                MimirDatabaseFactory.get(context).gameDao().hashSourceForGame(gameIdValue)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val fileName = source?.let { rawFileName(it.sourceDocumentUri, it.managedPath) }
        if (source == null || fileName == null) {
            report(retroAchievementsFileUnavailableState())
            return
        }
        if (!client.canIdentify(source.platformId, fileName)) {
            report(retroAchievementsIdentificationUnavailableState())
            return
        }
        val seekReader = client.requiresLocalPath(source.platformId, fileName)
        // The cartridge ceiling remains end-to-end read protection. Disc readers only seek selected canonical
        // regions, so applying that same 64 MiB ceiling would reject essentially every valid image.
        if (!retroAchievementsHashable(source.sizeBytes, seekReader)) {
            report(retroAchievementsOversizeState())
            return
        }
        val localPath = if (seekReader) localPathFor(source) else null
        val directChd = if (fileName.endsWith(".chd", ignoreCase = true)) {
            RetroAchievementsChdResident(
                entryKey = source.entryKey,
                sourceRootUri = source.sourceRootUri,
                relativePath = source.relativePath,
                documentUri = source.sourceDocumentUri,
                managedPath = source.managedPath,
                sizeBytes = source.sizeBytes,
            )
        } else {
            null
        }
        val playlistResolution = if (fileName.endsWith(".m3u", ignoreCase = true)) {
            val availableGroupChds = withContext(Dispatchers.IO) {
                try {
                    MimirDatabaseFactory.get(context).gameDao()
                        .membersOfGame(gameIdValue)
                        .asSequence()
                        .filter { it.availability == "AVAILABLE" }
                        .filter {
                            sequenceOf(it.relativePath, it.sourceDocumentUri, it.managedPath)
                                .filterNotNull()
                                .any { location -> location.endsWith(".chd", ignoreCase = true) }
                        }
                        .map {
                            RetroAchievementsChdResident(
                                entryKey = it.entryKey,
                                sourceRootUri = it.sourceRootUri,
                                relativePath = it.relativePath,
                                documentUri = it.sourceDocumentUri,
                                managedPath = it.managedPath,
                                sizeBytes = it.sizeBytes,
                            )
                        }
                        .toList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } ?: run {
                report(retroAchievementsFileUnavailableState())
                return
            }
            withContext(Dispatchers.IO) {
                m3uChdTargetResolver.resolve(
                    playlist = RetroAchievementsM3uResident(
                        entryKey = source.entryKey,
                        sourceRootUri = source.sourceRootUri,
                        relativePath = source.relativePath,
                        documentUri = source.sourceDocumentUri,
                        managedPath = source.managedPath,
                        sizeBytes = source.sizeBytes,
                    ),
                    availableGroupChds = availableGroupChds,
                )
            }
        } else {
            null
        }
        val playlistTargetChd = when (playlistResolution) {
            is RetroAchievementsM3uChdResolution.Selected -> playlistResolution.target
            RetroAchievementsM3uChdResolution.Unavailable -> {
                report(retroAchievementsFileUnavailableState())
                return
            }
            RetroAchievementsM3uChdResolution.NotChdTarget, null -> null
        }
        val targetChd = directChd ?: playlistTargetChd

        val cacheKey = withContext(Dispatchers.IO) {
            retroAchievementsCacheKeyForRoute(
                fileKey = cacheKeyFor(source),
                usesChdChainEngine = targetChd != null,
            )
        }
        // The saved resolution of the signed-in account is read in before the verdict cache is asked: a file it already knows is not hashed again.
        awaitResolutionReady()
        val cached = cacheKey?.let { verdicts.get(it) }
        // A game the ledger knows (its file's verdict is saved and so is its answer) shows that answer at once; the fresh read below replaces it.
        var savedPlaceholder = false
        if (!placeholderShown && cached is RetroAchievementsVerdict.Matched) {
            val store = ledger
            val name = activityAccount?.takeIf(String::isNotBlank)
            val saved = if (store != null && name != null) {
                withContext(Dispatchers.IO) { runCatching { store.readDetail(name, cached.gameId) }.getOrNull() }
            } else {
                null
            }
            if (saved != null) {
                savedPlaceholder = true
                reportSaved(MimirAchievementLaneState.Loaded(saved.progress))
            }
        }

        // Everything from here is the client's own work and runs OFF the main thread this probe is launched on: the
        // client reads and decrypts the credentials before each request, parses the response body and the hash index
        // on the CALLER's context (only the socket read hops to IO), and the answer's card and companion snapshot are
        // a sort and a map over every achievement. The main thread receives one finished immutable answer.
        val derived = withContext(Dispatchers.IO) {
            // A cached verdict skips the hash entirely — including, and especially, a cached MISS, which is the
            // common outcome and the one that already paid to read the whole ROM once.
            val outcome = cached?.toRaMatchOutcome() ?: client.match(
                platformId = source.platformId,
                fileName = fileName,
                sizeBytes = source.sizeBytes,
                localPath = localPath,
                targetChd = targetChd,
                openStream = { withContext(Dispatchers.IO) { openStream(source) } },
            ).also { fresh ->
                retroAchievementsVerdictOf(fresh)?.let { resolved ->
                    // Learned for the account this probe began under: a probe that outlived a switch teaches the new account nothing.
                    cacheKey?.let {
                        if (book.accountUnchangedSince(epoch)) {
                            verdicts.put(it, resolved)
                            persistResolutionSoon()
                        }
                    }
                }
            }
            val verdict = retroAchievementsVerdictOf(outcome)

            // the count is read fresh whenever a launch card opens; nothing is held between reads. The file's identification is
            // remembered (RA id for this library game) so the Activity page's bulk answer can link its row to the game exactly.
            (verdict as? RetroAchievementsVerdict.Matched)?.let {
                if (book.accountUnchangedSince(epoch) && book.noteMatchedRaId(it.gameId, gameIdValue)) persistResolutionSoon()
            }
            val progress = (verdict as? RetroAchievementsVerdict.Matched)
                ?.let { matched -> client.progressFor(matched.gameId) }
                ?: RaProgressOutcome.Unavailable
            DerivedLane.of(gameIdValue, activityAccount, retroAchievementsLaneState(outcome, progress))
        }
        // A fresh read that could not reach RetroAchievements does not take the saved answer away from a card that is showing it.
        if (savedPlaceholder && retroAchievementsKeepsSavedAnswer(derived.published.state)) return
        report(derived.published.state, derived)
    }

    private fun gateOpen(): Boolean =
        retroAchievementsGateShouldOpen(
            connectedServicesOptedIn = ConnectedServicesStore.optedIn.value,
            hasUsableCredentialsForSession = RetroAchievementsCredentialStore.hasHealthyCredentials(context),
        )

    private fun publish(
        derived: DerivedLane,
        account: String?,
    ) {
        probed = derived.published
        CompanionActivityAchievements.publish(derived.published.gameIdValue, account, derived.published.state, derived.snapshot)
    }

    private fun openStream(source: GameHashSourceProjection) =
        contentOpener.open(source.sourceDocumentUri, source.managedPath)

    /** A seek reader must use the original file; multi-gigabyte SAF documents are never copied to a cache. */
    private fun localPathFor(source: GameHashSourceProjection): String? {
        source.managedPath?.let { path ->
            if (runCatching { File(path).isFile }.getOrDefault(false)) return path
        }
        val documentUri = source.sourceDocumentUri ?: return null
        return ExternalStorageDocPath.resolve(documentUri)
            ?.takeIf { path -> runCatching { File(path).isFile }.getOrDefault(false) }
    }

    /**
     * The cache key, which includes the file's modified time. When the provider will not report one there is no key (null): see [retroAchievementsFileKey], which never keys a
     * file by its size alone, so the file is hashed afresh rather than served a replaced file's verdict.
     *
     * A SAF document normally costs a ContentResolver query to ask; when all-files access is granted the
     * document resolves to a real path and [File.lastModified] answers without the Binder round trip — the
     * same shortcut [AndroidContentOpener] takes to open it. Any failure yields 0.
     */
    private fun cacheKeyFor(source: GameHashSourceProjection): String? =
        retroAchievementsFileKey(
            sourceDocumentUri = source.sourceDocumentUri,
            managedPath = source.managedPath,
            sizeBytes = source.sizeBytes,
            modifiedMillis = modifiedMillis(source),
        )

    private fun modifiedMillis(source: GameHashSourceProjection): Long {
        source.managedPath?.let { path ->
            return runCatching { File(path).lastModified() }.getOrDefault(0L)
        }
        val documentUri = source.sourceDocumentUri ?: return 0L
        ExternalStorageDocPath.resolve(documentUri)?.let { path ->
            runCatching { File(path).lastModified() }.getOrDefault(0L).takeIf { it > 0L }?.let { return it }
        }
        return runCatching {
            context.contentResolver.query(
                Uri.parse(documentUri),
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    /** The raw on-disk basename, SAF-decoded — the `.zip`/`.z64` suffix the hash rules dispatch on. */
    private fun rawFileName(documentUri: String?, managedPath: String?): String? {
        val raw = documentUri ?: managedPath ?: return null
        return Uri.decode(raw).substringAfterLast('/').ifBlank { null }
    }
}

internal enum class MimirAchievementScrollDirection { UP, DOWN }

internal data class MimirAchievementScrollRequest(
    val epoch: Long,
    val direction: MimirAchievementScrollDirection,
)

internal fun homeProbeIsCurrent(
    expectedEpoch: Long,
    currentEpoch: Long,
    expectedGameId: String,
    currentGameId: String?,
    cardOpen: Boolean,
): Boolean = cardOpen && expectedEpoch == currentEpoch && expectedGameId == currentGameId

internal fun retroAchievementsPreflightState(
    supported: Boolean,
    keyState: RaKeyState,
    hasUsableCredentialsForSession: Boolean,
    connectedServicesOptedIn: Boolean,
): MimirAchievementLaneState? = when {
    !supported -> retroAchievementsUnsupportedState()
    keyState == RaKeyState.REJECTED_THIS_SESSION -> retroAchievementsRejectedState()
    !hasUsableCredentialsForSession -> retroAchievementsNoCredentialsState()
    !connectedServicesOptedIn -> retroAchievementsOptedOutState()
    else -> null
}

private fun RetroAchievementsVerdict.toRaMatchOutcome(): RaMatchOutcome.Answered =
    RaMatchOutcome.Answered(
        when (this) {
            is RetroAchievementsVerdict.Matched -> RetroAchievementsMatch.Matched(gameId)
            RetroAchievementsVerdict.RecognizedNoSet -> RetroAchievementsMatch.RecognizedNoSet
            RetroAchievementsVerdict.NotMatched -> RetroAchievementsMatch.NotMatched
        },
    )

/** Everything the app shell needs from the achievements lane, as one reference. */
internal class MimirAchievementsCoordinator(val state: MimirAchievementsState)

@Composable
internal fun rememberMimirAchievementsCoordinator(core: MimirAppCoreCoordinator): MimirAchievementsCoordinator {
    val state = remember(core.context) { MimirAchievementsState(core.context) }
    // The state holds the Activity context and collects process-wide flows: detach it when this composition goes away, or each recreated Activity
    // would leave a collector (and its context, caches and settled answers) behind.
    DisposableEffect(state) { onDispose { state.close() } }
    // Cold start: the Activity page's one bulk read begins AFTER the first frames are on screen, never before, and does nothing unless
    // RetroAchievements is connected and opted in. It runs off the main thread (see [ActivityBulkLoader]).
    LaunchedEffect(state) {
        withFrameNanos { }
        withFrameNanos { }
        state.startActivityBulk()
    }
    // A game played OUTSIDE Mimir has no session to hear: coming back to the front after a while away asks for a refresh (see [MimirAchievementsState.onHostResumed]).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(state, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> state.onHostPaused()
                Lifecycle.Event.ON_RESUME -> state.onHostResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(state) { MimirAchievementsCoordinator(state) }
}

/**
 * One probe's finished answer, built OFF the main thread: the lane state with its card and label, and the snapshot the
 * Bottom's Activity page reads (null when the answer carries none). Immutable, so publishing it is two reference
 * stores and the screens that read it do no work of their own.
 */
internal class DerivedLane(
    val published: MimirPublishedLane,
    val snapshot: CompanionActivityAchievementSnapshot?,
) {
    companion object {
        fun of(gameIdValue: String, account: String?, state: MimirAchievementLaneState): DerivedLane =
            DerivedLane(
                published = mimirPublishedLane(gameIdValue, state, account),
                snapshot = CompanionActivityAchievements.snapshotFor(gameIdValue, account, state),
            )
    }
}

/** A test's stand-in for a probe's file and network work: it gets the game and the same report gate the real probe publishes through. */
internal typealias MimirAchievementProbeWork =
    suspend (game: GameRow, report: (MimirAchievementLaneState, DerivedLane?) -> Unit) -> Unit

/**
 * Where the lane reads its account, key state, opt-in and game sessions, and where it runs. The defaults are the app's real stores and the main
 * dispatcher; a test replaces them with plain flows, a recording work function and its own bulk sources, so the REAL coordinator can be driven
 * (card open, dismiss, report, account change, the bulk read) without a Context's stores or a network.
 */
internal class MimirAchievementsSeams(
    val account: StateFlow<String?> = RetroAchievementsCredentialStore.connectedUser,
    val keyState: StateFlow<RaKeyState> = RetroAchievementsCredentialStore.validationState,
    val optedIn: StateFlow<Boolean> = ConnectedServicesStore.optedIn,
    val sessionKeys: Flow<String?> = MimirSessionAuthority.state
        .map { snapshot -> snapshot.gameSession?.let { retroAchievementsSessionKey(it.operation.value, it.phase) } }
        .distinctUntilChanged(),
    val scopeContext: CoroutineContext = Dispatchers.Main.immediate,
    val probeWork: MimirAchievementProbeWork? = null,
    val bulkGames: (suspend () -> RaUserGamesOutcome)? = null,
    val bulkPoints: (suspend () -> RaPointsOutcome)? = null,
    /** The one recent-unlocks read (the strip); null means the real client's. Run on [bulkContext]. */
    val bulkRecent: (suspend () -> RaRecentOutcome)? = null,
    val bulkLibrary: (suspend () -> List<LocalGameKey>)? = null,
    val bulkContext: CoroutineContext = Dispatchers.IO,
    /** Where the bulk read re-checks the epoch and writes (one step); the main dispatcher, where the account-change handler also runs. */
    val publishContext: CoroutineContext = Dispatchers.Main.immediate,
    /** Empties the RA icon folder (sign-out, opt-out); null means the process's real icon store. Run on [bulkContext]. */
    val iconClear: (suspend () -> Unit)? = null,
    /** The one per-game detail read (badges, title art); null means the real client's progress read. Run on [bulkContext]. */
    val cardDetailFetch: (suspend (raGameId: Int) -> RaProgressOutcome)? = null,
    val clock: () -> Long = System::currentTimeMillis,
    /** The durable ledger; null means the real one under the app's files directory. A test passes one over a temporary folder. */
    val ledger: RetroAchievementsLedger? = null,
    /** How long the resolution waits to be saved after something was learned, so a burst of probes is one write. */
    val ledgerFlushDelayMillis: Long = RETRO_ACHIEVEMENTS_LEDGER_FLUSH_DELAY_MS,
    /** Where the bulk read's outcome line goes; the real one is the MimirRA tag in logcat. */
    val bulkLog: (String) -> Unit = ::logRetroAchievementsLine,
    /** The sign-in a bulk read captures once when it begins; null means the stored one when the sources are the real client's, and none when they are a test's. Run on [bulkContext]. */
    val credentials: (() -> RetroAchievementsAccount?)? = null,
    /** How the scheduled reads after a play wait for their time; the real one is a coroutine delay, a test's moves its own clock. */
    val bulkPause: suspend (millis: Long) -> Unit = { delay(it) },
    /** The Mimir-launched game being played (null with none), which the unlock watch follows. */
    val playingSessions: Flow<RaPlayingSession?> = MimirSessionAuthority.state
        .map { snapshot -> snapshot.gameSession?.playingSessionOrNull() }
        .distinctUntilChanged(),
    /** The one game's progress read the unlock watch makes; null means the real client's. Run on [bulkContext]. */
    val sessionProgress: (suspend (raGameId: Int) -> RaProgressOutcome)? = null,
    /** Whether a Retry-After is in force; null means the real client's (the process pacer's). */
    val requestsHeld: (() -> Boolean)? = null,
    /** Where a toast goes to be shown, answering whether it was displayed; the real one is the Bottom's process-wide toast, which answers true only once its host ran it. */
    val toastSink: suspend (AchievementToast) -> Boolean = AchievementToasts::showAndAwaitDisplay,
    /** How often the unlock watch reads the running game. */
    val sessionPollMillis: Long = RETRO_ACHIEVEMENTS_SESSION_POLL_MS,
)

/** How long a probe or a hydration waits at most for the saved resolution to be read in (a small file; this only bounds a stuck disk). */
internal const val RESOLUTION_READY_WAIT_MS: Long = 2_000L

internal const val RETRO_ACHIEVEMENTS_LEDGER_FLUSH_DELAY_MS: Long = 750L

/**
 * Whether a fresh answer must NOT replace the saved one a card is showing: the read just could not reach RetroAchievements or got no usable body. Anything
 * that says something about the account or the game (a rejected key, no set, a real answer) always replaces it.
 */
internal fun retroAchievementsKeepsSavedAnswer(state: MimirAchievementLaneState): Boolean =
    state is MimirAchievementLaneState.Unresolved &&
        (state.reason == MimirAchievementUnresolvedReason.UNREACHABLE || state.reason == MimirAchievementUnresolvedReason.PROGRESS_UNAVAILABLE)
