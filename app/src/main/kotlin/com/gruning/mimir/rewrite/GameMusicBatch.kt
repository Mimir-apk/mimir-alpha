package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.metadata.online.OnlineProviderPlatformProjection
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperLookupFailure
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.ChangedMedia
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.resolver.library.LocalArtSource
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The game-music download: the launch sound, cut from each game's ScreenScraper
 * clip, fetched on purpose from Settings rather than as a side effect of an enrich.
 *
 * The remux, the transcode, the two-at-once gate, the `audio:` negatives and the create-only writer are the ones the
 * enrich used before the sound left it ([ArtworkFetchController.fetchLaunchAudioWith]); what is new here is who asks.
 * Each game gets a FRESH `jeuInfos` lookup (nothing is reused from an artwork enrich), the batch has its own meter
 * ([cost], `MimirEnrichCost batch=game-music`) and its own breaker ([breaker]), and it runs under the process gate
 * ([ArtworkOperationGate]) so it never overlaps an enrich. Logs counts, durations and bytes only: no URL, credential,
 * file name or game title, ever.
 */

/** How one game's download step ended, in the three ways the card counts it. */
internal enum class GameMusicStepOutcome {
    /** A sound was written. */
    WRITTEN,

    /** Nothing to write and nothing wrong: no match, no clip, a clip with no usable audio, or a sound already there. */
    SKIPPED,

    /** An error stood in the way (provider, download, transcode, storage), or the batch stopped before the game. */
    FAILED,
}

/** One game's step: its [outcome], whether the provider could not answer ([lookupFailed]), and the media it wrote. */
internal data class GameMusicStepResult(
    val outcome: GameMusicStepOutcome,
    /** The lookup failed (transport, quota, authentication): counts toward the provider breaker, never as a no-match. */
    val lookupFailed: Boolean = false,
    val changed: ChangedMedia? = null,
    /** Why a FAILED game failed, when the step knows ([GameMusicFailure]); null for every other outcome. */
    val failure: GameMusicFailure? = null,
)

/**
 * Why games failed, the one fact the Settings card can say after its counts. Each is a true statement about the cause, never a guess:
 * [NO_ANSWER] ScreenScraper did not answer (a timeout or a drop, or a refusal that names no cause), [DAILY_LIMIT] the account's quota
 * is spent, [BUSY] the shared allowance is closed, [SIGN_IN_REFUSED] the member sign-in was refused, [BUILD_REFUSED] this build's
 * developer key was, and [COULDNT_SAVE] the clip arrived but the sound could not be made or written.
 */
internal enum class GameMusicFailure { NO_ANSWER, DAILY_LIMIT, BUSY, SIGN_IN_REFUSED, BUILD_REFUSED, COULDNT_SAVE }

/** A failed lookup's reason: the source's own classification ([ScreenScraperLookupFailure]), one for one. */
internal fun gameMusicFailureOfLookup(reason: ScreenScraperLookupFailure): GameMusicFailure = when (reason) {
    ScreenScraperLookupFailure.NO_ANSWER -> GameMusicFailure.NO_ANSWER
    ScreenScraperLookupFailure.DAILY_LIMIT -> GameMusicFailure.DAILY_LIMIT
    ScreenScraperLookupFailure.BUSY -> GameMusicFailure.BUSY
    ScreenScraperLookupFailure.SIGN_IN_REFUSED -> GameMusicFailure.SIGN_IN_REFUSED
    ScreenScraperLookupFailure.BUILD_REFUSED -> GameMusicFailure.BUILD_REFUSED
}

/**
 * Why a sound step that got past the lookup ended as a failure. A clip that would not download, or that the dead-download breaker
 * skipped, is the provider not answering; a transcode that hit trouble, a write failure or a throw is the sound not being saved.
 * Null for an outcome that is not a failure (and for none at all).
 */
internal fun gameMusicFailureOfStep(outcome: LaunchAudioStepOutcome?): GameMusicFailure? = when (outcome) {
    LaunchAudioStepOutcome.DownloadUnavailable,
    LaunchAudioStepOutcome.SkippedByBreaker,
    -> GameMusicFailure.NO_ANSWER
    LaunchAudioStepOutcome.WriteFailed,
    LaunchAudioStepOutcome.StepFailed,
    LaunchAudioStepOutcome.TranscodeFailed,
    -> GameMusicFailure.COULDNT_SAVE
    else -> null
}

/**
 * Where a batch stands: [done] of [total] games stepped, and how each ended.
 *
 * [alreadyHad] is a part of [skipped], set only by the per-game press ([GameMusicBatch.runForGame] sees the verdict "this game
 * already has its sound" before any lookup): the batch never fills it, because it only steps games that lack one. It lets the
 * press tell "already has music" from "none found", which every other skip (no match, no clip, no usable audio, an uncovered
 * platform) shares. The Settings card reads none of it.
 *
 * [currentIndex] and [current] are for the Settings card while a batch runs: the 1-based place in the batch of the game being looked up
 * now (0 when none is) and its name, cut to one line. They belong to a progress report and are clear in a batch's final tally. [failure]
 * is the dominant reason among the failed games ([runGameMusicBatch]), the card's one hint.
 */
internal data class GameMusicTally(
    val done: Int = 0,
    val total: Int = 0,
    val written: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val alreadyHad: Int = 0,
    val currentIndex: Int = 0,
    val current: String? = null,
    val failure: GameMusicFailure? = null,
)

/**
 * How a step's end maps onto the three counts. A clip that cannot become a sound (no audio track, no decoder, a bad file,
 * too big) is a SKIP, because it records its negative and will be the same next time; a refused download, a transcode that
 * hit trouble, a write failure, a breaker skip or anything that threw is a FAILURE the next press tries again.
 * [LaunchAudioStepOutcome.NotWritten] is a skip: the install wrote nothing because the game already had a sound.
 */
internal fun gameMusicOutcomeFor(outcome: LaunchAudioStepOutcome?): GameMusicStepOutcome = when {
    outcome == null -> GameMusicStepOutcome.FAILED
    outcome == LaunchAudioStepOutcome.Written -> GameMusicStepOutcome.WRITTEN
    outcome == LaunchAudioStepOutcome.NotWritten -> GameMusicStepOutcome.SKIPPED
    audioStepRecordsNegative(outcome) -> GameMusicStepOutcome.SKIPPED
    else -> GameMusicStepOutcome.FAILED
}

/** What a fresh `jeuInfos` lookup for one game came to, as the download's step reads it. */
internal sealed interface GameMusicLookup {
    /** ScreenScraper matched the game; [clipUrl] is its preview clip, or null when the record carries none. */
    data class Loaded(val clipUrl: String?) : GameMusicLookup

    /** The provider answered: it has no record for this game. */
    data object Empty : GameMusicLookup

    /** The platform has no ScreenScraper system id. No request was made. */
    data object PlatformUnsupported : GameMusicLookup

    /** Transport, quota or authentication trouble. Not a no-match. [reason] is what the answer says, null when none was asked (the gate closed). */
    data class Failed(val reason: GameMusicFailure? = null) : GameMusicLookup
}

/** How cutting the sound from one clip ended: the media written (if any) and the step's own [outcome]. */
internal data class GameMusicCut(val changed: ChangedMedia?, val outcome: LaunchAudioStepOutcome?)

/**
 * The download's step for ONE game, every effect injected so the rules are tested without a network, a database or a
 * codec. What it may and may not touch is the whole point:
 *
 *  - A sound that is already there is never looked up and never replaced ([alreadyHasSound]).
 *  - A game whose ROM document no longer resolves ([romPresent] false) is skipped before any lookup: no quota, no download,
 *    no transcode, because there is no folder left to write the sound beside.
 *  - One fresh lookup, then ONLY the sound: no art, no metadata refresh, no IGDB. Whatever the lookup caches as a side
 *    effect is the lookup's own.
 *  - [forced] is the per-game press. It bypassed the cooldowns before this step was called (they are the caller's
 *    selection rule), and a miss it meets records no game-level negative: an explicit press must never cool a game down.
 *  - Negatives: the game-level "no ScreenScraper match" one is cleared when a verified lookup proves it wrong and recorded
 *    on an empty answer from a batch; the sound's own (`audio:`) is recorded when the record has no clip. No other game's
 *    negative and no other slot's is read or written here, which is why none of these seams exists for them.
 */
internal suspend fun gameMusicStepWith(
    forced: Boolean,
    alreadyHasSound: () -> Boolean,
    romPresent: () -> Boolean = { true },
    lookup: suspend () -> GameMusicLookup,
    clearGameNegative: () -> Unit,
    recordGameNegative: () -> Unit,
    recordNoClipNegative: () -> Unit,
    cutSound: suspend (clipUrl: String) -> GameMusicCut,
): GameMusicStepResult {
    if (alreadyHasSound()) return GameMusicStepResult(GameMusicStepOutcome.SKIPPED)
    if (!romPresent()) return GameMusicStepResult(GameMusicStepOutcome.SKIPPED)
    val clipUrl = when (val found = lookup()) {
        is GameMusicLookup.Loaded -> {
            clearGameNegative()
            found.clipUrl
        }
        GameMusicLookup.Empty -> {
            if (!forced) recordGameNegative()
            return GameMusicStepResult(GameMusicStepOutcome.SKIPPED)
        }
        GameMusicLookup.PlatformUnsupported -> return GameMusicStepResult(GameMusicStepOutcome.SKIPPED)
        is GameMusicLookup.Failed ->
            return GameMusicStepResult(GameMusicStepOutcome.FAILED, lookupFailed = true, failure = found.reason)
    }
    if (clipUrl == null) {
        if (audioNegativeAfterLookup(asked = true, clipUrlPresent = false)) recordNoClipNegative()
        return GameMusicStepResult(GameMusicStepOutcome.SKIPPED)
    }
    val cut = cutSound(clipUrl)
    val outcome = gameMusicOutcomeFor(cut.outcome)
    return GameMusicStepResult(
        outcome = outcome,
        changed = cut.changed,
        failure = if (outcome == GameMusicStepOutcome.FAILED) gameMusicFailureOfStep(cut.outcome) else null,
    )
}

/** Whether a game can be given a sound, and if not, which rule says so. The first rule that applies wins. */
internal enum class GameMusicEligibility {
    ELIGIBLE,

    /** PC games are identified by SteamGridDB, which has no clips: ScreenScraper never sees them. */
    PC,

    /** The platform has no ScreenScraper system id, so no lookup could answer. */
    NOT_COVERED,

    /** The game already has its `-audio.m4a`. A sound is never replaced. */
    HAS_SOUND,

    /** The game has no writable destination for `media_art`, so a download would be thrown away. */
    WRITE_BLOCKED,

    /**
     * The game's ROM document, or the folder it sat in, no longer resolves (a stale library row; the tree grant outlives the folder).
     * Nothing could be written beside it, so a download would be thrown away. Not a failure: no retry can bring the file back.
     */
    ROM_MISSING,

    /** The game's sound, or the game itself, is inside the fourteen-day provider-absent cooldown. */
    COOLING_DOWN,
}

/**
 * The one rule for which games the download asks about: available primary games on ScreenScraper-covered platforms, PC
 * excluded, whose sound is missing, whose `media_art` can be written and whose negatives are not cooling down. The
 * inputs are lambdas so the cheap rules decide before any file or store is touched (a PC game never costs a SAF probe).
 */
internal fun gameMusicEligibility(
    platformId: String,
    hasSound: () -> Boolean,
    writeAllowed: () -> Boolean,
    audioCoolingDown: () -> Boolean,
    gameCoolingDown: () -> Boolean,
    romPresent: () -> Boolean = { true },
): GameMusicEligibility = when {
    PlatformId(platformId) == PlatformId.PC_GAMES -> GameMusicEligibility.PC
    OnlineProviderPlatformProjection.screenScraperSystemId(platformId) == null -> GameMusicEligibility.NOT_COVERED
    hasSound() -> GameMusicEligibility.HAS_SOUND
    !writeAllowed() -> GameMusicEligibility.WRITE_BLOCKED
    !romPresent() -> GameMusicEligibility.ROM_MISSING
    audioCoolingDown() || gameCoolingDown() -> GameMusicEligibility.COOLING_DOWN
    else -> GameMusicEligibility.ELIGIBLE
}

/**
 * What the download would do now: the games it would ask about ([eligible], handed to the runner in the library's enrichment order, so the
 * games the player looks at get their sound first) and the card's "[have] of [total] games have music".
 * [total] is every game a sound could exist for (PC and uncovered platforms excluded); [have] those that already do.
 */
internal data class GameMusicPlan<T>(val eligible: List<T>, val have: Int, val total: Int)

internal fun <T> planGameMusic(games: List<T>, classify: (T) -> GameMusicEligibility): GameMusicPlan<T> {
    val eligible = ArrayList<T>()
    var have = 0
    var total = 0
    for (game in games) {
        when (classify(game)) {
            GameMusicEligibility.PC, GameMusicEligibility.NOT_COVERED -> Unit
            GameMusicEligibility.HAS_SOUND -> {
                have++
                total++
            }
            GameMusicEligibility.WRITE_BLOCKED, GameMusicEligibility.ROM_MISSING, GameMusicEligibility.COOLING_DOWN -> total++
            GameMusicEligibility.ELIGIBLE -> {
                eligible += game
                total++
            }
        }
    }
    return GameMusicPlan(eligible = eligible, have = have, total = total)
}

/**
 * Why the card's download row cannot start right now, or null. Not connected comes first: it is the cause the player can
 * act on (the row sends them to the connect row), and an enrich cannot be running without a connection anyway.
 */
internal fun gameMusicBlock(connected: Boolean, activity: ArtworkOperation): GameMusicBlock? = when {
    !connected -> GameMusicBlock.NotConnected
    activity == ArtworkOperation.ENRICH -> GameMusicBlock.EnrichRunning
    else -> null
}

/**
 * Runs [step] over [items] [concurrency] wide and keeps the tally. Pure of Android: every effect arrives as a parameter,
 * so the cancel, resume and breaker rules are tested on the JVM.
 *
 * **Cancel** (the coroutine is cancelled): no new game starts, the games in flight are cancelled where they stand (their
 * staging files go with them, see [ArtworkFetchController.fetchLaunchAudioWith]), and [publish] still runs, so the sounds
 * already written become visible. A game cancelled mid-step is not counted. Pressing the download again recomputes the
 * eligible games, so it resumes with exactly the ones still missing.
 *
 * **The provider breaker** is the enrich's rule: [ArtworkFetchController.MaxConsecutiveProviderFailures] consecutive
 * failed lookups stop the lookups, and the remaining games are counted as failed without touching the network. They carry the
 * reason of the failures that tripped the breaker, so the card's hint names the cause and not a guess about the games it skipped.
 *
 * **Progress** is reported when the batch opens, when a game's lookup starts ([GameMusicTally.currentIndex] and
 * [GameMusicTally.current], named by [titleOf]) and when one ends, so the card never sits unchanged while a slow lookup runs.
 * Games start in parallel, so the report names the most recently started game that is still in flight. The returned tally
 * carries [GameMusicTally.failure], the reason most failed games share (the first reached on a tie).
 */
internal suspend fun <T> runGameMusicBatch(
    items: List<T>,
    concurrency: Int,
    step: suspend (T) -> GameMusicStepResult,
    onProgress: (GameMusicTally) -> Unit,
    publish: (List<ChangedMedia>) -> Unit,
    providerBreaker: ArtworkFetchController.WriteFailureBreaker =
        ArtworkFetchController.WriteFailureBreaker(limit = ArtworkFetchController.MaxConsecutiveProviderFailures),
    titleOf: (T) -> String? = { null },
): GameMusicTally {
    var tally = GameMusicTally(total = items.size)
    val changed = mutableListOf<ChangedMedia>()
    // Games start in parallel and end one at a time: everything below that reads or writes the state holds [lock].
    val lock = Any()
    val inFlight = LinkedHashMap<Int, String?>()
    val failures = LinkedHashMap<GameMusicFailure, Int>()
    var lastLookupFailure: GameMusicFailure? = null
    var breakerFailure: GameMusicFailure? = null

    fun dominantFailure(): GameMusicFailure? = failures.maxByOrNull { it.value }?.key
    fun report() {
        val latest = inFlight.entries.lastOrNull()
        onProgress(tally.copy(currentIndex = latest?.key ?: 0, current = latest?.value, failure = dominantFailure()))
    }

    onProgress(tally)
    ArtworkFetchController.runBoundedArtworkBatch(
        items = items.withIndex().toList(),
        concurrency = concurrency,
        step = { (index, item) ->
            val place = index + 1
            if (providerBreaker.tripped) {
                GameMusicStepResult(GameMusicStepOutcome.FAILED, failure = synchronized(lock) { breakerFailure })
            } else {
                synchronized(lock) {
                    inFlight[place] = titleOf(item)?.let(::gameMusicDisplayName)
                    report()
                }
                try {
                    step(item)
                } finally {
                    synchronized(lock) { inFlight.remove(place) }
                }
            }
        },
        onCompleted = { _, result ->
            synchronized(lock) {
                val wasTripped = providerBreaker.tripped
                when {
                    result.lookupFailed -> {
                        providerBreaker.recordFailure()
                        result.failure?.let { lastLookupFailure = it }
                    }
                    // The provider answered (a sound, a skip, a download that failed after the lookup): not a provider failure.
                    result.outcome != GameMusicStepOutcome.FAILED -> providerBreaker.recordSuccess()
                }
                if (!wasTripped && providerBreaker.tripped) breakerFailure = result.failure ?: lastLookupFailure
                if (result.outcome == GameMusicStepOutcome.FAILED) {
                    result.failure?.let { failures[it] = (failures[it] ?: 0) + 1 }
                }
                result.changed?.let { changed += it }
                tally = tally.copy(
                    done = tally.done + 1,
                    written = tally.written + if (result.outcome == GameMusicStepOutcome.WRITTEN) 1 else 0,
                    skipped = tally.skipped + if (result.outcome == GameMusicStepOutcome.SKIPPED) 1 else 0,
                    failed = tally.failed + if (result.outcome == GameMusicStepOutcome.FAILED) 1 else 0,
                )
                report()
            }
        },
        publish = { publish(changed.toList()) },
        recover = { GameMusicStepResult(GameMusicStepOutcome.FAILED) },
    )
    return synchronized(lock) { tally.copy(failure = dominantFailure()) }
}

/** The production side of the download: which games, under which meter and breaker, and the cleanup after. */
internal object GameMusicBatch {
    /** The download's own meter (label `game-music`). The enrich's [ArtworkFetchController.enrichCost] is process-global, so it is not shared. */
    internal val cost = EnrichCostAccumulator()

    /** Five consecutive dead downloads stop the batch paying for more; armed per run, never outliving it. */
    internal val breaker = LaunchAudioBreaker()

    /** What a download would do now, for the card's "N of M" and for a run. Reads the library and probes `media_art`. */
    suspend fun plan(context: Context): GameMusicPlan<GameEntity> = withContext(Dispatchers.IO) {
        ArtNoMatchStore.attach(context)
        val localArt = LocalArtSource(context)
        val preflight = ArtworkFetchController.SafWritePreflight(context)
        val games = MimirDatabaseFactory.get(context).gameDao().primaryGamesForEnrichment()
        planGameMusic(games) { game -> classify(game, localArt, preflight) }
    }

    /** The whole library's missing sounds: [GameMusicPlan.eligible] games, the player's own games first. */
    suspend fun runAll(context: Context, onProgress: (GameMusicTally) -> Unit): GameMusicTally {
        val plan = plan(context)
        return run(context, plan.eligible, forced = false, onProgress = onProgress)
    }

    /**
     * One game's download, the per-game action: the same step as a batch of one. It bypasses THAT game's cooldowns
     * (a press is explicit) and nothing else: it fetches no art, refreshes no metadata beyond the lookup's own, and never
     * clears another game's negative or another slot's.
     */
    suspend fun runForGame(context: Context, gameId: String, onProgress: (GameMusicTally) -> Unit): GameMusicTally {
        val game = withContext(Dispatchers.IO) {
            val database = MimirDatabaseFactory.get(context)
            val currentGameId = database.gameIdentityDao().resolveCurrentGameId(gameId)
            database.gameDao().membersOfGame(currentGameId).firstOrNull()
        } ?: return GameMusicTally(done = 1, total = 1, skipped = 1).also(onProgress)
        ArtNoMatchStore.attach(context)
        val localArt = LocalArtSource(context)
        val preflight = ArtworkFetchController.SafWritePreflight(context)
        // The same rule as a batch, with the cooldowns left out: a press bypasses them.
        val verdict = withContext(Dispatchers.IO) {
            gameMusicEligibility(
                platformId = game.platformId,
                hasSound = { localArt.audioUriFor(game.sourceDocumentUri, game.managedPath) != null },
                writeAllowed = { preflight.allowsWrite(game.sourceDocumentUri, game.managedPath) },
                audioCoolingDown = { false },
                gameCoolingDown = { false },
                romPresent = { !preflight.romMissing(game.sourceDocumentUri, game.managedPath) },
            )
        }
        return when (verdict) {
            GameMusicEligibility.ELIGIBLE -> run(context, listOf(game), forced = true, onProgress = onProgress)
            GameMusicEligibility.WRITE_BLOCKED -> GameMusicTally(done = 1, total = 1, failed = 1).also(onProgress)
            // The press names this one apart from the other skips: a sound that is already there is not a miss.
            GameMusicEligibility.HAS_SOUND -> GameMusicTally(done = 1, total = 1, skipped = 1, alreadyHad = 1).also(onProgress)
            GameMusicEligibility.PC,
            GameMusicEligibility.NOT_COVERED,
            GameMusicEligibility.ROM_MISSING,
            GameMusicEligibility.COOLING_DOWN,
            -> GameMusicTally(done = 1, total = 1, skipped = 1).also(onProgress)
        }
    }

    private fun classify(
        game: GameEntity,
        localArt: LocalArtSource,
        preflight: ArtworkFetchController.SafWritePreflight,
    ): GameMusicEligibility = gameMusicEligibility(
        platformId = game.platformId,
        hasSound = { localArt.audioUriFor(game.sourceDocumentUri, game.managedPath) != null },
        writeAllowed = { preflight.allowsWrite(game.sourceDocumentUri, game.managedPath) },
        audioCoolingDown = {
            ArtNoMatchStore.isCoolingDown(
                ArtworkFetchController.artNoMatchSlotKey(ArtworkFetchController.ManagedMediaSlot.AUDIO, game.gameId),
            )
        },
        gameCoolingDown = { ArtNoMatchStore.isCoolingDown(game.gameId) },
        romPresent = { !preflight.romMissing(game.sourceDocumentUri, game.managedPath) },
    )

    private suspend fun run(
        context: Context,
        games: List<GameEntity>,
        forced: Boolean,
        onProgress: (GameMusicTally) -> Unit,
    ): GameMusicTally = withContext(Dispatchers.IO) {
        if (!MimirConnectedArtGate.isOpen || games.isEmpty()) {
            return@withContext GameMusicTally(total = games.size).also(onProgress)
        }
        ArtworkConnectController.attach(context)
        ArtResolutionStore.attach(context)
        ArtNoMatchStore.attach(context)
        // The live quota read first (it also keeps the account's requests-today count), so the meter's "before" is this
        // batch's and the concurrency is the account's own.
        val accountMaxThreads = ArtworkConnectController.refreshAccountMaxThreads(context)
        ArtworkFetchController.sweepLaunchAudioStage(context)
        cost.begin(ArtworkFetchController.EnrichBatchGameMusic, requestsBefore = ArtworkConnectController.lastRequestsToday)
        breaker.begin()
        try {
            val localArt = LocalArtSource(context)
            val tally = runGameMusicBatch(
                items = games,
                concurrency = ArtworkFetchController.artworkBatchConcurrency(accountMaxThreads),
                step = { game -> ArtworkFetchController.fetchGameMusicForGame(context, game, localArt, forced, cost, breaker) },
                titleOf = { game -> game.title.ifBlank { game.displayName } },
                onProgress = onProgress,
                publish = { changed -> ArtworkFetchController.publishWrittenMedia(context, changed) },
            )
            // A cancelled run never gets here and logs no line, like an enrich: it has no whole to report.
            ArtworkFetchController.emitBatchCost(
                meter = cost,
                games = games.size,
                matched = tally.written,
                audioBreakerTripped = breaker.tripped,
                readRequestsToday = { ArtworkConnectController.readRequestsToday(context) },
            )
            tally
        } finally {
            breaker.end()
            // This batch is the stage folder's only user while it holds the gate, so everything in it is its own leftover.
            ArtworkFetchController.clearLaunchAudioStage(context)
        }
    }
}
