package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.jobs.JobProgressState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the artwork offer waits for identification before asking anyway.
 *
 * Fifteen minutes is not a performance target, it is a giving-up point: a measured identification run
 * took 24 minutes to identify 66 games, and a player on a slower library must still be offered
 * artwork rather than silently never asked. Nothing is blocked while this runs — the settling screen
 * is what the player is looking at — so the cost of waiting is invisible and the cost of not waiting
 * is fetching art for games that do not know their own names yet.
 */
/**
 * Absolute ceiling on the wait for identification, and a BACKSTOP only — the real escape is the stall
 * detector in [IdentityEnrichmentWorker.awaitIdentified], which gives up when the count stops moving.
 *
 * 45 minutes. This was 15, which was shorter than the 24 minutes a measured library took on
 * 2026-09-17 and so would have fired on the next run of the very walk it was written to fix. It is sized
 * as roughly double a measured worst case, and it should never be what returns: if this is the thing that
 * ends the wait, identification is both slow AND still making progress after 45 minutes, which is a bug
 * somewhere else and worth the log line at the call site.
 */
private const val IdentityWaitBudgetMillis = 45L * 60L * 1000L

internal class MimirArtworkState(
    private val context: Context,
    private val viewModel: LibraryViewModel,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /**
     * Whether the download may reach ScreenScraper: an account is connected and the build can reach the service. A
     * parameter so the state machine can be driven on the JVM without a real sign-in.
     */
    private val gameMusicConnected: () -> Boolean = {
        ConnectedArtStore.connectedAccount.value != null && MimirConnectedArtGate.isOpen
    },
) {
    var connectUi by mutableStateOf<ArtworkConnectUi?>(null)
    val fetchState = MutableStateFlow<ArtworkFetchUi?>(null)
    var fetchKind by mutableStateOf(ArtworkFetchKind.ARTWORK)
    var fetchEmbedded by mutableStateOf(false)
    var getArtUi by mutableStateOf<GetArtUi?>(null)

    /**
     * Which provider the CURRENT [getArtUi] card is about. Set synchronously alongside [GetArtUi.Working]
     * at the top of [fetchOne] (never behind [getArtEpoch] — both are written together by the one call
     * that is starting NOW, so the same latest-call-wins property applies without a separate guard), and
     * alongside [GetArtUi.NeedsAccount] on [fetchOne]'s early synchronous-gate return for the same reason
     * — without that second write site this stayed at its SCREENSCRAPER default (or an unrelated earlier
     * fetch's leftover value) for a row that never reached the Working write at all.
     *
     * CLOSED 2026-08-19 (was a KNOWN GAP): the early return now claims [getArtEpoch] too, so BOTH write
     * sites are governed by the one latest-call-wins property rather than only the Working/terminal pair.
     * The defect that closes: a game A already in flight, then a gate-closed game B's early return, then
     * A's own completion — A's terminal state used to paint with B's provider label, because A's epoch was
     * still current (B never claimed one) while A's provider had already been overwritten by B. The claim
     * is what makes "whoever wrote getArtProvider last also owns the epoch" true without exception.
     * R-24: a `pc_games` row is SteamGridDB's platform outright; every other platform keeps ScreenScraper.
     */
    var getArtProvider by mutableStateOf(GetArtProvider.SCREENSCRAPER)
        private set

    private var fetchJob: Job? = null

    // ── Settings > Music: the game-music download ────────────────────────────────────────────────────────
    //
    // The launch sound is fetched on purpose, from Settings (the whole library) or from a game's menu (one game), never
    // as a side effect of an enrich. It runs under the process gate ([ArtworkOperationGate]) so it never overlaps an
    // enrich, in either direction, and reports through [gameMusicState].

    /** The reason the download cannot start, as of the last time it was looked at; carried by every non-running state. */
    @Volatile
    private var heldGameMusicBlock: GameMusicBlock? = currentGameMusicBlock()

    private val _gameMusicState = MutableStateFlow<GameMusicDownloadState>(
        GameMusicDownloadState.Idle(have = 0, total = 0, block = heldGameMusicBlock),
    )

    /**
     * What Settings > Music > Download game music shows. Idle(have, total) until [refreshGameMusicCounts] has counted
     * (so `total == 0` means "not counted yet", not "no games"); Running(done, total, written) while a batch or a single
     * game runs; Finished(written, skipped, failed) when it ends, cancelled included. Every state carries
     * [GameMusicDownloadState.block] while not running, so the row can say why it is disabled, kept live by the
     * observer that [refreshGameMusicCounts] starts.
     */
    val gameMusicState: StateFlow<GameMusicDownloadState> = _gameMusicState.asStateFlow()

    private var gameMusicJob: Job? = null
    private var gameMusicRefreshJob: Job? = null
    private var gameMusicObserverJob: Job? = null

    /**
     * Counts what the download would do (the card's "N of M games have music") and starts keeping the block live. Call it
     * when the Music card is opened. It reads the library and probes `media_art`, so it runs off the main thread and is
     * skipped while a download runs (its own progress is the truth then). Replaces a Finished state with the counts.
     */
    fun refreshGameMusicCounts(plan: suspend (Context) -> GameMusicPlan<*> = GameMusicBatch::plan) {
        observeGameMusicBlock()
        if (gameMusicJob?.isActive == true) return
        gameMusicRefreshJob?.cancel()
        gameMusicRefreshJob = scope.launch {
            val counted = try {
                plan(context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return@launch
            }
            if (gameMusicJob?.isActive != true) {
                publishGameMusic(GameMusicDownloadState.Idle(have = counted.have, total = counted.total))
            }
        }
    }

    /**
     * Fetch the missing launch sounds for every eligible game. Pressing it again after a stop, a failure or a
     * skip-heavy run recomputes the eligible games, so it resumes with exactly the ones still missing. Does nothing while
     * [GameMusicDownloadState.block] is set (the row is disabled then) or a download is already running.
     */
    fun startGameMusicDownload(
        runAll: suspend (Context, (GameMusicTally) -> Unit) -> GameMusicTally = GameMusicBatch::runAll,
    ) {
        if (gameMusicJob?.isActive == true) return
        claimAndLaunchGameMusic(runGameId = null, report = null) { onProgress -> runAll(context, onProgress) }
    }

    /**
     * Stop the batch: no new game starts, the games in flight are cancelled where they stand and their staging files
     * with them, and the sounds already written stay. The state ends as Finished(stopped = true) with what was done.
     *
     * The press is answered AT ONCE: the card turns to Stopping before the work has finished stopping, because a lookup or a
     * clip parked on a slow network takes a moment to come back even when its connection is closed under it. A second press
     * while it stops is absorbed.
     */
    fun cancelGameMusicDownload() {
        val job = gameMusicJob?.takeIf { it.isActive } ?: return
        if (!gameMusicStopping) {
            gameMusicStopping = true
            _gameMusicState.update { if (it is GameMusicDownloadState.Running) it.copy(stopping = true) else it }
        }
        job.cancel()
    }

    /** The player pressed Cancel on the run that holds [gameMusicJob]: every progress report is Stopping from then on, and so is the end. */
    @Volatile
    private var gameMusicStopping = false

    /**
     * The per-game "Download music for this game": a batch of one in the same state flow. It bypasses that game's
     * cooldown and fetches no other art. Does nothing while a download is running or [GameMusicDownloadState.block] is set.
     *
     * the press reports where it was asked. [report] hears every way it can end, in order: [GameMusicPress.Started] and
     * then [GameMusicPress.Ended] when it ran, or a single [GameMusicPress.Refused] / [GameMusicPress.Busy] when it did not.
     * The shared state flow and the Settings card are fed exactly as before; a report that throws is dropped.
     */
    fun downloadMusicForGame(
        gameId: String,
        report: (GameMusicPress) -> Unit = {},
        runForGame: suspend (Context, String, (GameMusicTally) -> Unit) -> GameMusicTally = GameMusicBatch::runForGame,
    ) {
        if (gameMusicJob?.isActive == true) {
            reportGameMusic(report, GameMusicPress.Busy(sameGame = gameMusicRunGameId == gameId))
            return
        }
        claimAndLaunchGameMusic(runGameId = gameId, report = report) { onProgress -> runForGame(context, gameId, onProgress) }
    }

    /** The game whose per-game press owns [gameMusicJob], or null while the job is the Settings batch (or nothing runs). */
    @Volatile
    private var gameMusicRunGameId: String? = null

    private fun reportGameMusic(report: ((GameMusicPress) -> Unit)?, press: GameMusicPress) {
        if (report == null) return
        try {
            report(press)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A message that cannot be shown is not a reason to disturb the download's own state.
        }
    }

    /** True when a download started. The gate is claimed here, synchronously and before any work, then released on every end. */
    private fun claimAndLaunchGameMusic(
        runGameId: String?,
        report: ((GameMusicPress) -> Unit)?,
        run: suspend ((GameMusicTally) -> Unit) -> GameMusicTally,
    ): Boolean {
        val block = currentGameMusicBlock()
        if (block != null) {
            heldGameMusicBlock = block
            republishGameMusicBlock()
            reportGameMusic(report, GameMusicPress.Refused(block))
            return false
        }
        val claim = ArtworkOperationGate.process.tryClaimGameMusic()
        if (claim == null) {
            // Lost a race at the gate: an enrich took it after the block was read, or the previous download has finished
            // but not yet handed its claim back. Either way the press is refused, and says which.
            reportGameMusic(
                report,
                if (ArtworkOperationGate.process.active.value == ArtworkOperation.ENRICH) {
                    GameMusicPress.Refused(GameMusicBlock.EnrichRunning)
                } else {
                    GameMusicPress.Busy(sameGame = false)
                },
            )
            return false
        }
        var latest = GameMusicTally()
        gameMusicRunGameId = runGameId
        gameMusicStopping = false
        publishGameMusic(GameMusicDownloadState.Running(done = 0, total = 0, written = 0))
        reportGameMusic(report, GameMusicPress.Started)
        val job = scope.launch {
            // The report is made AFTER the state is published and OUTSIDE the try: a message that fails must not be read as the
            // download failing, and must not publish a second Finished.
            val ended: GameMusicPress.Ended = try {
                val finished = run { tally ->
                    latest = tally
                    publishGameMusicProgress(tally)
                }
                // Work that does not look at its cancellation can come back whole after a stop was asked for: it was still stopped.
                val stopped = gameMusicStopping || !isActive
                publishGameMusic(
                    GameMusicDownloadState.Finished(
                        finished.written,
                        finished.skipped,
                        finished.failed,
                        stopped = stopped,
                        failure = finished.failure,
                    ),
                )
                GameMusicPress.Ended(finished, stopped = stopped)
            } catch (cancellation: CancellationException) {
                // A stop is not a failure: the games it did not reach are simply still missing. The card says Stopped, not Done.
                publishGameMusic(
                    GameMusicDownloadState.Finished(
                        latest.written,
                        latest.skipped,
                        latest.failed,
                        stopped = true,
                        failure = latest.failure,
                    ),
                )
                reportGameMusic(report, GameMusicPress.Ended(latest, stopped = true))
                throw cancellation
            } catch (_: Throwable) {
                // Something unexpected: what was not reached counts as failed, so the card does not claim a clean run.
                val failed = latest.failed + (latest.total - latest.done).coerceAtLeast(0)
                publishGameMusic(
                    GameMusicDownloadState.Finished(
                        written = latest.written,
                        skipped = latest.skipped,
                        failed = failed,
                        failure = latest.failure,
                    ),
                )
                // The report is stricter than the card: before the first progress nothing is counted, but a throw is a failure.
                GameMusicPress.Ended(latest.copy(failed = failed.coerceAtLeast(1)), stopped = false)
            }
            reportGameMusic(report, ended)
        }
        // A completion handler rather than a `finally`: it also runs for a job cancelled before its first line.
        // The run's owner is forgotten first, so no press can meet a freed gate that still names the finished game.
        job.invokeOnCompletion { gameMusicRunGameId = null }
        job.invokeOnCompletion { claim.release() }
        gameMusicJob = job
        return true
    }

    private fun currentGameMusicBlock(): GameMusicBlock? = gameMusicBlock(
        connected = gameMusicConnected(),
        activity = ArtworkOperationGate.process.active.value,
    )

    private fun publishGameMusic(state: GameMusicDownloadState) {
        _gameMusicState.value = state.withBlock(if (state is GameMusicDownloadState.Running) null else heldGameMusicBlock)
    }

    /**
     * A running report. Written with [update], and the stop flag read INSIDE it, so a report that races the Cancel press is retried
     * against the Stopping state rather than overwriting it with a card that looks as if nothing was pressed.
     */
    private fun publishGameMusicProgress(tally: GameMusicTally) {
        _gameMusicState.update {
            GameMusicDownloadState.Running(
                done = tally.done,
                total = tally.total,
                written = tally.written,
                currentIndex = tally.currentIndex,
                current = tally.current,
                stopping = gameMusicStopping,
            )
        }
    }

    /** The block, brought up to date on whatever state the card is in now (read and written together, so a report cannot be lost). */
    private fun republishGameMusicBlock() {
        _gameMusicState.update { it.withBlock(if (it is GameMusicDownloadState.Running) null else heldGameMusicBlock) }
    }

    /** Keeps the block in step with the gate and the connection, for as long as the composition lives. Started once. */
    private fun observeGameMusicBlock() {
        if (gameMusicObserverJob != null) return
        gameMusicObserverJob = scope.launch {
            combine(ArtworkOperationGate.process.active, ConnectedArtStore.connectedAccount) { activity, _ ->
                gameMusicBlock(connected = gameMusicConnected(), activity = activity)
            }.collect { block ->
                heldGameMusicBlock = block
                republishGameMusicBlock()
            }
        }
    }

    private var connectJob: Job? = null
    private val artFetchInFlight = mutableSetOf<String>()

    // fetchOne's own auto-dismiss timer used to identify "my result is still showing" by comparing
    // getArtUi's VALUE against the terminal singletons (Found/NotFound/Failed). Two different games
    // reaching the same terminal state is not a same-operation coincidence — nothing here was ever keyed
    // per-game or per-call, only per-value. An older call's timer firing after a newer call already
    // painted its own (value-identical) result nulled that newer, still-current result early. This
    // monotonic counter identifies the CALL, not the value, so only the operation that actually set the
    // currently-displayed getArtUi may clear it.
    //
    // Claimed by BOTH of fetchOne's display-writing paths (2026-08-19): the async Working path, and the
    // synchronous gate-closed early return. "Call" here means any operation that puts state on screen, not
    // only one that starts a fetch — the early return writes getArtProvider and getArtUi and then finishes,
    // which is precisely the thing a later completion must not paint over. See [getArtProvider].
    private var getArtEpoch = 0L

    /** Profile owns account state only; it never starts a library-wide download as a side effect. */
    fun openArtworkProfile() {
        if (ConnectedArtStore.connectedAccount.value == null) {
            connectUi = ArtworkConnectUi.EnterId
        } else {
            disconnect()
        }
    }

    /** Explicit Settings action: opt-in network work begins only from the Download row. */
    fun downloadArtworkSettings() {
        if (ConnectedArtStore.connectedAccount.value != null) {
            startBulkFetch(ArtworkFetchKind.ARTWORK)
        }
    }

    /** Onboarding owns the later fetch phase, so this entry point never starts a Settings-style batch. */
    fun openConnectForOnboarding() {
        if (ConnectedArtStore.connectedAccount.value == null) {
            connectUi = ArtworkConnectUi.EnterId
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        val disconnected = ArtworkConnectController.disconnect(context)
        val copy = if (disconnected) {
            "Disconnected. Your artwork stays on this device."
        } else {
            "Network access is off for this session, but Mimir couldn’t finish disconnecting. Retry before restart."
        }
        showMimirMessage(context, copy, if (disconnected) MimirMessageClass.Confirmation else MimirMessageClass.Warning)
    }

    /**
     * Library Enrich (single-game): refresh metadata plus static cover/logo/Hero/physical art only.
     * Hero Motion is deliberately excluded; choosing Hero Art versus Hero Motion remains an explicit
     * Customize Media decision. Keep member canonical/stored/raw/hash/provider-id truth inside
     * [ArtworkFetchController], and only append the visible logical [GameRow.title] as bounded additional
     * provider-name evidence. Explicit [candidateNames] callers keep their own list.
     */
    fun fetchOne(row: GameRow) = fetchOne(row, libraryEnrichAdditionalCandidateNames(row.title))

    /**
     * [fetchOneArtwork] is injectable ONLY so this method's own wiring — including the auto-dismiss call
     * site below — is directly unit-testable without a real network/ScreenScraper round trip. Every real
     * caller uses the default, which is exactly [ArtworkFetchController.fetchOneArtwork]; production
     * behavior is unchanged.
     *
     * [onArtInvalidated] is injectable for the same reason and nothing else: its default IS
     * `viewModel::invalidateCachedArt`. Without it the eviction is unobservable from a unit test —
     * `LibraryViewModel` is final with a private constructor and `platformListCache` is private — so the
     * one property that matters about it (that it happens even when this call has LOST the epoch race, and
     * is therefore NOT reachable through the terminal-state assertions) could only have been characterized
     * as source text. It is declared ahead of [fetchOneArtwork] so existing trailing-lambda call sites
     * still bind to that one.
     *
     * this synchronous gate check used to consult [MimirConnectedArtGate] —
     * ScreenScraper's gate — unconditionally, before [row]'s platform was even looked at. R-24: a
     * `pc_games` row is SteamGridDB's platform outright (ScreenScraper has no `pc_games` system id and
     * structurally cannot identify a GameNative shortcut), so requiring a ScreenScraper account to reach
     * it was asking for a credential that could never help — the exact device defect this closes. [row]'s
     * platform is already in memory (the caller resolved it), so this reorders rather than combines: no
     * context/DB touch is added ahead of the check, unlike [ArtworkFetchController.fetchOneArtwork]'s own
     * gate (which resolves the row from the database and so keeps its combined OR-guard for a genuinely
     * unresolved platform). Every platform other than `pc_games` is completely unchanged, still gated on
     * [MimirConnectedArtGate] alone.
     */
    fun fetchOne(
        row: GameRow,
        candidateNames: List<String>,
        onArtInvalidated: (GameRow) -> Unit = viewModel::invalidateCachedArt,
        fetchOneArtwork: suspend (
            context: Context,
            gameId: String,
            candidateNames: List<String>,
        ) -> ArtworkFetchController.OneResult = { ctx, id, names ->
            // The per-game Enrich press is the one caller that is an explicit press: the only place the ScreenScraper PC-logo
            // fallback and IGDB's name search may be reached (fix round 2026-10-06; the default is false everywhere else).
            ArtworkFetchController.fetchOneArtwork(ctx, id, names, explicitPress = true)
        },
    ) {
        val isPc = row.platformId == PlatformId.PC_GAMES
        // A pc_games row names SteamGridDB, except when ScreenScraper is the ONLY provider this press can ask (its key or gate is
        // closed and the connection is open): then the card must not say "SteamGridDB" about a lookup SteamGridDB never ran.
        val provider = if (isPc && (MimirSteamGridDbGate.isOpen || !MimirConnectedArtGate.isOpen)) {
            GetArtProvider.STEAMGRIDDB
        } else {
            GetArtProvider.SCREENSCRAPER
        }
        // a pc_games press that only ScreenScraper can answer (no saved SteamGridDB key, so the logo is all it can
        // serve) ends on a card that says PC art needs the key and where to add it. This is exactly the label case above.
        val keylessPc = isPc && provider == GetArtProvider.SCREENSCRAPER
        // A pc_games row is reachable by EITHER provider : SteamGridDB for its art, ScreenScraper for the logo alone.
        // The label is chosen above: SteamGridDB, unless ScreenScraper is the only provider this press can ask.
        val gateOpen = if (isPc) MimirSteamGridDbGate.isOpen || MimirConnectedArtGate.isOpen else MimirConnectedArtGate.isOpen
        if (!gateOpen) {
            // Same selection as the Working write below — this row never reaches that line, so without
            // this write NeedsAccount would render off getArtProvider's SCREENSCRAPER default (or an
            // unrelated earlier fetch's leftover value) instead of the provider THIS row actually needs.
            // Claim the epoch exactly as the Working path below does. This return writes the two pieces
            // of display state (provider + card) that the epoch governs, so it is a "call" by the only
            // definition that matters here — an operation whose result is on screen. Without the claim an
            // OLDER in-flight fetch still matched the live epoch and painted its terminal card over this
            // NeedsAccount one, carrying this row's provider label with it.
            ++getArtEpoch
            getArtProvider = provider
            getArtUi = getArtUiForGateClosed(provider, ConnectedArtStore.connectedAccount.value)
            return
        }
        val gameId = row.gameId.value
        if (!artFetchInFlight.add(gameId)) return
        val myEpoch = ++getArtEpoch
        getArtProvider = provider
        getArtUi = GetArtUi.Working
        scope.launch {
            val result = try {
                fetchOneArtwork(context, gameId, candidateNames)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (getArtEpoch == myEpoch) getArtUi = GetArtUi.Failed
                return@launch
            } finally {
                artFetchInFlight.remove(gameId)
            }
            // A later fetchOne (this game re-triggered, or a different game) already claimed the epoch —
            // its own Working/terminal state is live now, and painting over it here would be exactly the
            // "written then overwritten" shape this fix exists to rule out, just from the opposite side.
            // DATA effect, deliberately ABOVE the epoch guard. `invalidateCachedArt` is
            // `platformListCache.remove(row.platformId)` (LibraryViewModel) — a cache eviction about the
            // bytes THIS fetch just wrote to disk, not about whose card is on screen. Leaving it below the
            // guard meant a matched fetch that lost the epoch race silently kept the Library painting the
            // pre-fetch art: the enrich worked, the tile did not change, and nothing said so. That was
            // already reachable before today (any second fetchOne claims the epoch) and closing the early
            // return's gap would only have widened it. Idempotent and row-scoped, so an eviction whose card
            // never shows costs one cache miss and nothing else. DISPLAY writes stay guarded, below.
            if (result == ArtworkFetchController.OneResult.Matched) onArtInvalidated(row)
            if (getArtEpoch != myEpoch) return@launch
            when (result) {
                ArtworkFetchController.OneResult.Matched ->
                    getArtUi = if (keylessPc) GetArtUi.PcKeyNote(logoSaved = true) else GetArtUi.Found
                ArtworkFetchController.OneResult.NoMatch ->
                    getArtUi = if (keylessPc) GetArtUi.PcKeyNote(logoSaved = false) else GetArtUi.NotFound
                // no provider was asked (a pc_games row with no `.steam` shortcut
                // and no searchable title), so this must not borrow NotFound's "the provider had no art".
                ArtworkFetchController.OneResult.NoTitle -> getArtUi = GetArtUi.NotSearchable
                ArtworkFetchController.OneResult.Failed -> getArtUi = GetArtUi.Failed
                // The game-music download is running and no Enrich may run beside it: nothing was asked of any provider, and
                // the honest card is the generic "couldn't, try again" one. It stays until dismissed, like Failed.
                ArtworkFetchController.OneResult.Busy -> getArtUi = GetArtUi.Failed
                ArtworkFetchController.OneResult.GateClosed -> {
                    getArtUi = getArtUiForGateClosed(provider, ConnectedArtStore.connectedAccount.value)
                }
            }
            // MEASURED bug this excludes Failed from: GetArtOverlay's own contract for GetArtUi.Failed is
            // Keep the card visible until its Back button dismisses it, but
            // this timer used to auto-null Failed exactly like the success/no-match outcomes — a failed
            // fetch flashed an error and silently vanished on its own 1.4s later, indistinguishable from
            // "nothing happened" to anyone not looking at that exact moment. The exception path above
            // (line 112-114) never auto-dismissed Failed; only this result-based path did, inconsistently.
            if (getArtResultAutoDismisses(result)) {
                delay(1_400)
                // MEASURED bug this replaces: comparing getArtUi's VALUE against the terminal singletons
                // let an OLDER call's timer clear a NEWER, still-current result whenever both happened to
                // land on the same outcome (e.g. two NotFound in a row) — the timer never knew which call
                // it belonged to. Comparing the epoch instead identifies the CALL, so only the operation
                // that is still the live one may clear the display, regardless of what value it holds.
                // The keyless-PC note is the one result that stays: its sentence carries a Settings path, and it has its own Not now.
                if (getArtEpoch == myEpoch && getArtUi !is GetArtUi.PcKeyNote) getArtUi = null
            }
        }
    }

    fun submitAccountId(id: String) {
        if (id.isBlank()) {
            // An empty first step is Back/Cancel, never an anonymous ScreenScraper sign-in.
            closeConnect()
        } else {
            connectUi = ArtworkConnectUi.EnterPassword(id)
        }
    }

    fun submitPassword(state: ArtworkConnectUi, password: String) {
        val id = (state as? ArtworkConnectUi.EnterPassword)?.id ?: ""
        connectJob?.cancel()
        if (connectUi is ArtworkConnectUi.Connecting) {
            ArtworkConnectController.cancelPendingConnect(context)
        }
        connectUi = ArtworkConnectUi.Connecting
        connectJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                ArtworkConnectController.connect(context, id, password)
            }
            connectUi = result.toMimirArtworkConnectUi()
        }
    }

    /**
     * QA-24: drop a TERMINAL connect card that has been displaced off screen.
     *
     * `connectUi` is the overlay's stack membership -- `MimirOverlayCoordinator.kt:346` is literally
     * `artworkConnect = artwork.state.connectUi != null`. So when a higher-priority overlay takes the
     * stack, this one stops rendering but its state is untouched; when that other overlay closes,
     * ARTWORK_CONNECT becomes the active kind again and re-presents whatever was left in it. For a
     * `Done` card that is a success message for a sign-in the player completed minutes ago, arriving
     * unprompted and unexplained -- the defect reported as "stale Connection Successful".
     *
     * Scoped to TERMINAL states on purpose. `EnterId`/`EnterPassword`/`Connecting` are a flow the player
     * is part-way through, and a transient displacement must not destroy a half-entered credential; those
     * survive and are restored. `Done` and `Failed` are outcomes that have already been delivered, and an
     * outcome has nothing left to resume.
     */
    fun dismissDisplacedConnectOutcome() {
        if (connectUi?.let(::artworkConnectOutcomeIsTerminal) == true) {
            connectUi = null
        }
    }

    fun closeConnect() {
        connectJob?.cancel()
        if (connectUi is ArtworkConnectUi.Connecting) {
            ArtworkConnectController.cancelPendingConnect(context)
        }
        connectJob = null
        connectUi = null
    }

    fun cancelOrCloseFetch() {
        if (fetchState.value == null && NewGameEnrichPromptState.current.value != null) {
            dismissNewGameEnrichPrompt()
            return
        }
        when (val state = fetchState.value) {
            is ArtworkFetchUi.Running -> {
                fetchState.value = ArtworkFetchUi.Stopping(state.matched)
                fetchJob?.cancel()
            }
            is ArtworkFetchUi.Stopping -> Unit
            else -> fetchState.value = null
        }
    }

    fun cancelFetch() {
        if (fetchState.value == null && NewGameEnrichPromptState.current.value != null) {
            dismissNewGameEnrichPrompt()
            return
        }
        val matched = (fetchState.value as? ArtworkFetchUi.Running)?.matched ?: 0
        fetchState.value = ArtworkFetchUi.Stopping(matched)
        fetchJob?.cancel()
    }

    fun closeFetch() {
        if (fetchState.value == null && NewGameEnrichPromptState.current.value != null) {
            dismissNewGameEnrichPrompt()
            return
        }
        fetchState.value = null
        fetchEmbedded = false
    }

    fun dismissNewGameEnrichPrompt() {
        NewGameEnrichPromptState.dismiss(context)
    }

    /**
     * The no-account branch of the new-game card: take the player to the sign-in instead of starting a
     * fetch that cannot work.
     *
     * Without this, pressing the primary button with no credentials runs every game through
     * [ArtworkFetchController.fetchOneArtwork], which returns `GateClosed` for each one and reports
     * "Done, 0 matched" -- a progress bar that completes successfully having done nothing. A prompt that
     * arrives on time and then lies is worse than one that arrives late.
     *
     * The pending set is deferred rather than dismissed, so the question comes back once the sign-in closes.
     */
    /**
     * The new-game card's primary button, wherever it is pressed from.
     *
     * One decision point on purpose: the controller arm and the touch handler both land here, so the card's
     * two renderings cannot drift into two different behaviours. What the button SAYS is decided from the
     * same fact, in [ArtworkFetchUi.ConfirmNewGames.hasAccount].
     */
    fun answerNewGameEnrich() {
        if (ConnectedArtStore.connectedAccount.value == null) {
            connectForNewGameEnrich()
        } else {
            confirmNewGameEnrich()
        }
    }

    fun connectForNewGameEnrich() {
        NewGameEnrichPromptState.deferForConnect()
        connectUi = ArtworkConnectUi.EnterId
    }

    fun confirmNewGameEnrich() {
        val pending = NewGameEnrichPromptState.current.value ?: return
        NewGameEnrichPromptState.dismiss(context)
        startNewGamesFetch(pending.gameIds)
    }

    fun dismissGetArt() {
        if (getArtUi != GetArtUi.Working) getArtUi = null
    }

    /**
     * [GetArtUi.NeedsAccount]'s ScreenScraper route — the only route this reaches now that
     * [GetArtProvider.STEAMGRIDDB] cards use [addSteamGridDbKeyFromGetArt] instead (policy
     * 2026-08-19: the Connect pill must go where its own sentence points; `pc_games` never shows this
     * sentence in the first place, so this stays a plain ScreenScraper open, unconditional on provider).
     */
    fun connectFromGetArt() {
        getArtUi = null
        connectUi = ArtworkConnectUi.EnterId
    }

    /**
     * `pc_games`-only counterpart to [connectFromGetArt]: [GetArtUi.NeedsAccount]'s Connect pill for a
     * `pc_games` row goes to the SteamGridDB key entry the product already has (Settings › Appearance ›
     * Enrichment & artwork), not the ScreenScraper login flow. This class doesn't own the settings
     * coordinator, so it clears the same [getArtUi] state [connectFromGetArt] does and hands off to
     * [openKeyEditor] (the caller's existing `settings.setSteamGridDbKeyEditing(true)` — the identical
     * call the Settings row itself makes) rather than opening [ArtworkConnectUi.EnterId] itself.
     * Reachable ONLY from [GetArtUi.NeedsAccount]'s STEAMGRIDDB arm, so the unconditional clear is safe
     * for the same reason [connectFromGetArt]'s is: neither pill exists on any other page.
     */
    fun addSteamGridDbKeyFromGetArt(openKeyEditor: () -> Unit) {
        getArtUi = null
        openKeyEditor()
    }

    private fun startBulkFetch(kind: ArtworkFetchKind, embedded: Boolean = false) {
        if (fetchState.value != null) return
        // The game-music download holds the process gate: no Enrich may start beside it (the row reading the gate is
        // disabled, so this is the guard, not the message). fetchAllArtwork claims the gate itself and answers busy on a race.
        if (ArtworkOperationGate.process.active.value == ArtworkOperation.GAME_MUSIC) return
        fetchKind = kind
        fetchEmbedded = embedded
        fetchState.value = ArtworkFetchUi.Running(done = 0, total = 0, matched = 0)
        // D1-R11 step 4. `total` is genuinely unknown at this instant -- the controller counts the
        // games only once it starts -- so this opens with no denominator rather than the 0 the UI
        // state carries. Zero here would render as a full or empty bar, and neither is true.
        JobProgressState.report(JobKind.SCRAPE, seen = 0, total = null)
        fetchJob = scope.launch {
            try {
                // One bulk artwork batch, since the Hero Motion batch was retired 2026-09-17.
                val result = ArtworkFetchController.fetchAllArtwork(context, ::publishProgress)
                // Busy means the download took the gate in the instant between the check above and the claim: nothing ran, so
                // there is no result to show, and the card simply closes.
                fetchState.value = if (result.busy) null else ArtworkFetchUi.Done(result.total, result.matched, result.writeBlocked)
            } catch (cancellation: CancellationException) {
                val matched = when (val state = fetchState.value) {
                    is ArtworkFetchUi.Running -> state.matched
                    is ArtworkFetchUi.Stopping -> state.matched
                    else -> 0
                }
                fetchState.value = ArtworkFetchUi.Cancelled(matched)
                throw cancellation
            } catch (_: Throwable) {
                val matched = when (val state = fetchState.value) {
                    is ArtworkFetchUi.Running -> state.matched
                    is ArtworkFetchUi.Stopping -> state.matched
                    else -> 0
                }
                fetchState.value = ArtworkFetchUi.Cancelled(matched)
            } finally {
                // Every exit: done, cancelled, thrown, or the composition scope dying underneath a
                // fetch that was still running. `finally` is the only one of those that cannot be
                // forgotten, and the last of them is the reason it matters here -- see the scope note
                // on `publishProgress`.
                JobProgressState.clear(JobKind.SCRAPE)
            }
        }
    }

    /**
     * The new-game artwork batch — the run the first-run card starts.
     *
     * It used to be `gameIds.forEach { fetchOneArtwork(...) }`: strictly one game at a time, while
     * [ArtworkFetchController.fetchAllArtwork] — the Settings-side "enrich everything" — has run up to six
     * wide since lever E. Same provider, same per-game work, same account; the path a player actually
     * reaches after onboarding was simply the serial one. That made the onboarding batch unnecessarily serial.
     *
     * Width is the account's, not ours: [ArtworkFetchController.artworkBatchConcurrency] clamps to the
     * live `ssuser.maxthreads`, so a one-thread (free) account still runs serially by construction and
     * nothing here can exceed what ScreenScraper allows that account.
     *
     * The failure breaker comes with the parallelism rather than after it. Six threads against a provider
     * that is down is six times the wasted quota and six times the noise ScreenScraper sees; lever B's
     * three-consecutive-failure rule already existed for exactly this and the serial loop never had it.
     */
    private fun startNewGamesFetch(gameIds: Set<String>) {
        if (gameIds.isEmpty() || fetchState.value != null) return
        // Claimed here, synchronously and before the quota refresh or any work, and released on every end (the completion
        // handler below also runs for a job cancelled before it starts). The per-game steps take their own shared claims.
        // While the game-music download runs the batch does not start; the card stays as it was.
        val claim = ArtworkOperationGate.process.tryClaimEnrich() ?: return
        fetchKind = ArtworkFetchKind.ARTWORK
        fetchEmbedded = false
        fetchState.value = ArtworkFetchUi.Running(done = 0, total = gameIds.size, matched = 0)
        JobProgressState.report(JobKind.SCRAPE, seen = 0, total = gameIds.size)
        val job = scope.launch(Dispatchers.IO) {
            val ids = gameIds.toList()
            var done = 0
            var matched = 0
            try {
                val accountMaxThreads = ArtworkConnectController.refreshAccountMaxThreads(context)
                val concurrency = ArtworkFetchController.artworkBatchConcurrency(accountMaxThreads)
                // The settings batch has logged its width since lever E; this path never did, which is why
                // a first run at one lane went unnoticed for so long. No credential is named -- the account
                // allowance is a small integer, and ScreenScraper URLs (which DO carry credentials) are
                // never logged anywhere in this file.
                Log.i("MimirArtworkFetch", "first-run artwork batch: games=${ids.size} concurrency=$concurrency")
                // Initial-enrich measurement, 2026-09-30: zeroes the batch meter AFTER the live quota read
                // above, so its requests-today "before" is this batch's. It fetches no game sound (Settings > Music does).
                ArtworkFetchController.beginEnrichBatch(ArtworkFetchController.EnrichBatchFirstRun)
                val breaker = ArtworkFetchController.WriteFailureBreaker(
                    limit = ArtworkFetchController.MaxConsecutiveProviderFailures,
                )
                ArtworkFetchController.runBoundedArtworkBatch(
                    items = ids,
                    concurrency = concurrency,
                    // A tripped breaker drains the remainder without touching the network, matching
                    // fetchAllArtwork rather than cancelling: the run still ends with an honest count.
                    step = { gameId ->
                        if (breaker.tripped) {
                            ArtworkFetchController.OneResult.Failed
                        } else {
                            // The new-games batch is not a press: it never spends ScreenScraper quota on a PC logo or IGDB on a name.
                            ArtworkFetchController.fetchOneArtwork(context, gameId, explicitPress = false)
                        }
                    },
                    // Serialized by the runner's lock, so these counters need no synchronisation of
                    // their own and the progress the player sees never goes backwards.
                    onCompleted = { _, result ->
                        when (result) {
                            ArtworkFetchController.OneResult.Matched -> {
                                matched++
                                breaker.recordSuccess()
                            }
                            // The provider answered, and "no art for this game" is an answer.
                            ArtworkFetchController.OneResult.NoMatch -> breaker.recordSuccess()
                            ArtworkFetchController.OneResult.Failed -> breaker.recordFailure()
                            // No lookup was made at all: neither a provider success nor a failure.
                            ArtworkFetchController.OneResult.NoTitle,
                            ArtworkFetchController.OneResult.GateClosed,
                            ArtworkFetchController.OneResult.Busy,
                            -> Unit
                        }
                        done++
                        fetchState.value = ArtworkFetchUi.Running(done, ids.size, matched)
                        JobProgressState.report(JobKind.SCRAPE, seen = done, total = ids.size)
                    },
                    publish = {},
                    recover = { ArtworkFetchController.OneResult.Failed },
                )
                // One `MimirEnrichCost` line for the whole batch (a cancelled batch logs none: it has no whole to report).
                ArtworkFetchController.finishEnrichBatch(context, games = ids.size, matched = matched)
                fetchState.value = ArtworkFetchUi.Done(ids.size, matched)
            } catch (cancellation: CancellationException) {
                fetchState.value = ArtworkFetchUi.Cancelled(matched)
                throw cancellation
            } catch (_: Throwable) {
                fetchState.value = ArtworkFetchUi.Cancelled(matched)
            } finally {
                JobProgressState.clear(JobKind.SCRAPE)
            }
        }
        job.invokeOnCompletion { claim.release() }
        fetchJob = job
    }

    /**
     * **The scrape is still composition-scoped, and this does not change that.** `MimirArtworkState`
     * holds the [scope] this fetch runs on, so when the primary window goes the fetch is cancelled and
     * the row clears. That is honest — the job really did stop — but it is not the same as the job
     * surviving, and the conformance spec keeps the scope move as its own step. On the second screen
     * it means a scrape vanishes if Android reclaims the Activity, which is exactly the
     * STOPPED-versus-DESTROYED boundary D1-R18 is about.
     */
    private fun publishProgress(progress: ArtworkFetchController.Progress) {
        if (fetchState.value is ArtworkFetchUi.Running) {
            fetchState.value = ArtworkFetchUi.Running(progress.done, progress.total, progress.matched)
        }
        JobProgressState.report(JobKind.SCRAPE, seen = progress.done, total = progress.total)
    }
}

internal class MimirArtworkCoordinator(
    val state: MimirArtworkState,
    val account: String?,
    val previewVideoOptedIn: Boolean,
    val fetchUi: ArtworkFetchUi?,
    val fetchEmbedded: Boolean,
    // Same route as `account`/`canFetchArt` just below: a StateFlow the app layer already maintains,
    // reduced to the plain Boolean feature-library needs. True only for a durably-saved, provider-verified
    // user SteamGridDB key (SteamGridDbKeyStore.providerValidated / MimirSteamGridDbGate.isOpen) -- never
    // resolvedKey's build-time debug fallback.  gates whether a
    // keyless pc_games row's "no description" copy may point at Enrich. Policy 2026-08-19 (this
    // gating fix): ALSO gates the gamelist media pill's own Enrich affordance for a pc_games row
    // (LibraryScreen.kt's gamelistEnrichAllowed), mirroring MimirArtworkState.fetchOne's synchronous gate
    // exactly -- the same value this coordinator already keeps in sync via MimirSteamGridDbGate.
    val steamGridDbKeyPresent: Boolean = false,
) {
    var connectKeyboardHost: MimirKeyboardHost? = null

    val canFetchArt: Boolean
        get() = account != null && MimirConnectedArtGate.isOpen
}

@Composable
internal fun rememberMimirArtworkCoordinator(core: MimirAppCoreCoordinator): MimirArtworkCoordinator {
    val scope = rememberCoroutineScope()
    // Restore the durable new-game prompt once, in the first composition, before `pending` is first read below; the
    // flow restore hands back is the one collected there, so that read cannot start from an unrestored null.
    val restoredPendingNewGames = remember(core.context) { NewGameEnrichPromptState.restore(core.context) }
    val state = remember(core.context, core.libraryViewModel, scope) {
        MimirArtworkState(core.context, core.libraryViewModel, scope)
    }
    val account by ConnectedArtStore.connectedAccount.collectAsState()
    val previewVideoOptedIn by ConnectedArtStore.previewVideoOptedIn.collectAsState()
    val fetchUiState by state.fetchState.collectAsState()
    val newGamePrompt by NewGameEnrichPromptState.current.collectAsState()
    val pendingNewGames by restoredPendingNewGames.collectAsState()
    val artOptedIn by ConnectedArtStore.optedIn.collectAsState()
    // The artwork question, asked when the HASH finishes -- not when an account happens to appear.
    //
    // This used to `return` unless `artOptedIn`, which made having a ScreenScraper account a precondition
    // for being ASKED whether you want one. On a first run that meant nothing at all happened when the hash
    // settled, and the card then surfaced at whatever later moment the account was connected. Reported as
    // The Enrich menu appeared at unrelated times because it was keyed to the wrong event.
    //
    // `connectOpen` replaces it, for a different reason: ARTWORK_FETCH sits ABOVE ARTWORK_CONNECT in the
    // overlay stack, so re-raising the card while the sign-in is up would cover the keyboard. Standing
    // aside is all it does -- `pending` is untouched, so closing the sign-in re-runs this and asks again,
    // by then with an account.
    val connectOpen = state.connectUi != null
    LaunchedEffect(pendingNewGames, artOptedIn, connectOpen) {
        val pending = pendingNewGames ?: return@LaunchedEffect
        if (connectOpen) return@LaunchedEffect
        // Wait for THESE games, not for the table to look quiet. See [awaitIdentified]: the old
        // `awaitSettled` call here returned before the scan had inserted a single row, which is how a
        // first-run artwork batch came to start twenty-two minutes before identification finished.
        //
        // Bounded twice, because an offer that never arrives is worse than one that arrives late, and
        // because the two ways this can fail to end are different: identification can STALL (the count
        // stops moving — `awaitIdentified` handles that itself) or it can run impossibly long while
        // still progressing (the ceiling). Waiting is otherwise free: the player is watching the settling
        // screen for this whole period anyway, so it costs them nothing they can see.
        val identified = withTimeoutOrNull(IdentityWaitBudgetMillis) {
            IdentityEnrichmentWorker.awaitIdentified(core.context, pending.gameIds)
        }
        // Both escapes are meant to be unreachable on a healthy run; say which one fired, because the
        // symptom on the device is identical either way and is exactly the complaint this wait fixes.
        if (identified != true) {
            Log.w(
                "MimirArtworkFetch",
                "offering artwork before identification finished: " +
                    if (identified == null) {
                        "hit the " + (IdentityWaitBudgetMillis / 60_000L) + "m ceiling"
                    } else {
                        "identification stalled"
                    },
            )
        }
        NewGameEnrichPromptState.offerAfterHash(core.context, pending)
    }
    val steamGridDbKeyPresent by SteamGridDbKeyStore.providerValidated.collectAsState()
    return MimirArtworkCoordinator(
        state,
        account,
        previewVideoOptedIn,
        fetchUiState ?: newGamePrompt?.let {
            ArtworkFetchUi.ConfirmNewGames(it.gameIds.size, hasAccount = account != null)
        },
        state.fetchEmbedded,
        steamGridDbKeyPresent,
    )
}

internal const val ScreenScraperUnavailableCopy =
    "ScreenScraper isn’t available in this build. Your account wasn’t changed."

/**
 * Map a closed provider gate to the truthful card for the provider that this row uses. A closed
 * ScreenScraper gate with a saved account means the account is still connected but this build cannot
 * reach the service; a SteamGridDB gate remains an ordinary missing-key prompt even when ScreenScraper
 * is connected.
 */
internal fun getArtUiForGateClosed(provider: GetArtProvider, connectedAccount: String?): GetArtUi =
    if (provider == GetArtProvider.SCREENSCRAPER && connectedAccount != null) {
        GetArtUi.ProviderUnavailable
    } else {
        GetArtUi.NeedsAccount
    }

/** Trim / blank-filter / dedupe the visible Library Enrich title before it becomes additional name evidence. */
internal fun libraryEnrichAdditionalCandidateNames(visibleTitle: String): List<String> =
    listOf(visibleTitle).map(String::trim).filter(String::isNotBlank).distinct()

/**
 * Whether [fetchOne]'s single-game result card should auto-dismiss on its own after a short delay, or wait
 * for the player to dismiss it explicitly. [GetArtOverlay]'s own contract for [GetArtUi.Failed] is remaining visible until explicitly dismissed — a failed fetch is not a "nothing to see" outcome the way a
 * successful or no-match one is, so it must not vanish on a timer the same way they do. [GateClosed] never
 * reaches this decision in practice ([fetchOne] returns before starting a fetch when the gate is closed);
 * excluded here too so the predicate stays correct even if a future caller reaches it directly.
 * [ArtworkFetchController.OneResult.NoTitle] is a "nothing to see" outcome like no-match and auto-dismisses
 * with it.
 */
internal fun getArtResultAutoDismisses(result: ArtworkFetchController.OneResult): Boolean =
    result != ArtworkFetchController.OneResult.GateClosed &&
        result != ArtworkFetchController.OneResult.Failed &&
        result != ArtworkFetchController.OneResult.Busy

/**
 * QA-24: has this connect state already delivered its outcome?
 *
 * Pure and exhaustive over [ArtworkConnectUi] so a new state must decide, at the compiler, whether it
 * survives being displaced off screen. The wrong default here is "true for everything", which is what the
 * absence of this function amounted to.
 */
internal fun artworkConnectOutcomeIsTerminal(state: ArtworkConnectUi): Boolean = when (state) {
    ArtworkConnectUi.EnterId,
    is ArtworkConnectUi.EnterPassword,
    ArtworkConnectUi.Connecting,
    -> false
    is ArtworkConnectUi.Done,
    is ArtworkConnectUi.Failed,
    -> true
}

private fun ArtworkConnectController.ConnectResult.toMimirArtworkConnectUi(): ArtworkConnectUi = when (this) {
    is ArtworkConnectController.ConnectResult.Success ->
        ArtworkConnectUi.Done(account, dailyLimit, usedToday)
    is ArtworkConnectController.ConnectResult.Failed -> ArtworkConnectUi.Failed(
        when (reason) {
            ArtworkConnectController.ConnectResult.Reason.MEMBER_REJECTED ->
                "ScreenScraper didn’t accept that login. Double-check your username and password, then try again."
            ArtworkConnectController.ConnectResult.Reason.UNAVAILABLE ->
                ScreenScraperUnavailableCopy
            ArtworkConnectController.ConnectResult.Reason.BUSY ->
                "ScreenScraper is busy right now and is limiting lookups. Give it a few minutes and try again."
            ArtworkConnectController.ConnectResult.Reason.UNREACHABLE ->
                "Couldn’t reach ScreenScraper. Check your connection and try again."
            ArtworkConnectController.ConnectResult.Reason.LOCAL_STORAGE ->
                "ScreenScraper accepted the login, but Mimir couldn’t activate it safely. No new connection was kept."
            ArtworkConnectController.ConnectResult.Reason.LOCAL_RECOVERY ->
                "Network access is off for this session, but local account recovery is incomplete. Retry disconnect before restart."
        },
    )
}
