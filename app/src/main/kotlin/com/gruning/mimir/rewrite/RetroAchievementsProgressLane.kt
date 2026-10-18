package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaMatchOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaNoAnswerReason
import com.gruning.mimir.rewrite.core.metadata.online.RETRO_ACHIEVEMENTS_PROVIDER_INDEX_FRESH_MS
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerVerdict
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerVerdictKind
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsMatch
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirAchievementRow
import com.gruning.mimir.rewrite.core.ui.MimirMedalState
import com.gruning.mimir.rewrite.core.ui.mimirAchievementProgressLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Largest cartridge admitted on the focus path; disc-sized reads require a separate explicit lane. */
internal const val RETRO_ACHIEVEMENTS_HASH_CEILING_BYTES: Long = 64L * 1024L * 1024L

internal fun retroAchievementsHashable(sizeBytes: Long): Boolean =
    sizeBytes > 0L && sizeBytes <= RETRO_ACHIEVEMENTS_HASH_CEILING_BYTES

/** Seek readers hash selected boot/filesystem data and do not read a disc image end-to-end. */
internal fun retroAchievementsHashable(sizeBytes: Long, seekReader: Boolean): Boolean =
    sizeBytes > 0L && (seekReader || sizeBytes <= RETRO_ACHIEVEMENTS_HASH_CEILING_BYTES)

/**
 * The key a file's cached verdict is kept under: where it is, how big it is and when it was last modified, so a ROM replaced under the same name reads as a different key and
 * is resolved (hashed) afresh. A file whose modified time is not known (a provider that reports none, a failed query: 0 or less) has NO key, never one made of its size alone:
 * a replacement of the same size would keep that key and be served the old file's verdict. With no key the cache is not consulted and nothing is saved for the file, so it is
 * hashed each time it is opened; that costs a read of the file, which is better than naming the wrong game.
 */
internal fun retroAchievementsFileKey(
    sourceDocumentUri: String?,
    managedPath: String?,
    sizeBytes: Long,
    modifiedMillis: Long,
): String? {
    val identity = sourceDocumentUri?.takeIf { it.isNotBlank() }
        ?: managedPath?.takeIf { it.isNotBlank() }
        ?: return null
    if (modifiedMillis <= 0L) return null
    return "$identity|$sizeBytes|$modifiedMillis"
}

/**
 * CHD results depend on a freshly verified parent chain, not only the focused file. Until the cache owns
 * an exact full-chain witness, bypass it for every CHD-engine route rather than reusing a stale match/miss.
 */
internal fun retroAchievementsCacheKeyForRoute(
    fileKey: String?,
    usesChdChainEngine: Boolean,
): String? = fileKey.takeUnless { usesChdChainEngine }

/** Only completed claims about the file are cacheable. */
internal sealed interface RetroAchievementsVerdict {
    data class Matched(val gameId: Int) : RetroAchievementsVerdict
    data object RecognizedNoSet : RetroAchievementsVerdict
    data object NotMatched : RetroAchievementsVerdict
}

/** Rejection and no-answer remain uncacheable claims about the key or request, never about the file. */
internal fun retroAchievementsVerdictOf(outcome: RaMatchOutcome): RetroAchievementsVerdict? = when (outcome) {
    is RaMatchOutcome.Answered -> when (val match = outcome.match) {
        is RetroAchievementsMatch.Matched -> RetroAchievementsVerdict.Matched(match.gameId)
        RetroAchievementsMatch.RecognizedNoSet -> RetroAchievementsVerdict.RecognizedNoSet
        RetroAchievementsMatch.NotMatched -> RetroAchievementsVerdict.NotMatched
    }
    RaMatchOutcome.CredentialsRejected, is RaMatchOutcome.NoAnswer -> null
}

/**
 * A "RetroAchievements has no set for this file" verdict can stop being true (a set is added later), so one is trusted for this long and then asked again,
 * wherever it came from (learned in this process or restored from the ledger) and checked EVERY time it is consulted, so a launcher left running past the limit
 * stops using it too. A match does not expire: the file key carries the file's size and modified time, so a changed file is a different key.
 *
 * It is the provider indexes' own freshness window ([RETRO_ACHIEVEMENTS_PROVIDER_INDEX_FRESH_MS]) on purpose: the retry after an expiry then reads the game-list
 * index and the keyless library afresh, so a set added meanwhile is found instead of the stale index renewing the miss.
 */
internal const val RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS: Long = RETRO_ACHIEVEMENTS_PROVIDER_INDEX_FRESH_MS

/**
 * What the lane has learned about each library file, by the app's file key. In memory for the lane's life, and SAVED (see [toLedger] / [restoreFrom]) so a
 * restart does not hash a file again to learn what it already knew: the resolution step only does work for a game that is new or changed.
 */
internal class RetroAchievementsVerdictCache(
    private val maxEntries: Int = 4096,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Stamped(val verdict: RetroAchievementsVerdict, val atMillis: Long)

    private val entries = object : LinkedHashMap<String, Stamped>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stamped>): Boolean =
            size > maxEntries
    }

    /** The verdict held for [key], or null: none held, or a "no set" one past [RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS] (it is dropped, so the game is resolved afresh). */
    @Synchronized fun get(key: String): RetroAchievementsVerdict? {
        val held = entries[key] ?: return null
        if (negativeVerdictExpired(held.verdict, held.atMillis, clock())) {
            entries.remove(key)
            return null
        }
        return held.verdict
    }

    @Synchronized fun put(key: String, verdict: RetroAchievementsVerdict) {
        entries[key] = Stamped(verdict, clock())
    }

    @Synchronized fun size(): Int = entries.size

    /** Forget everything held (the account the rows belong to went away; its saved rows are untouched). */
    @Synchronized fun clear() {
        entries.clear()
    }

    /** Every verdict held with the time it was learned, oldest use first: what the ledger saves. */
    @Synchronized fun toLedger(): List<RetroAchievementsLedgerVerdict> = entries.map { (key, held) ->
        when (val verdict = held.verdict) {
            is RetroAchievementsVerdict.Matched ->
                RetroAchievementsLedgerVerdict(key, RetroAchievementsLedgerVerdictKind.MATCHED, verdict.gameId, held.atMillis)
            RetroAchievementsVerdict.RecognizedNoSet ->
                RetroAchievementsLedgerVerdict(key, RetroAchievementsLedgerVerdictKind.RECOGNIZED_NO_SET, null, held.atMillis)
            RetroAchievementsVerdict.NotMatched ->
                RetroAchievementsLedgerVerdict(key, RetroAchievementsLedgerVerdictKind.NOT_MATCHED, null, held.atMillis)
        }
    }

    /**
     * Load saved verdicts. A verdict learned in this process is newer than a saved one and is kept; a saved "no set" older than
     * [RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS] is left out so the file is asked about again (and one that ages past it while held is dropped by [get]).
     * Returns how many were taken.
     */
    @Synchronized fun restoreFrom(saved: List<RetroAchievementsLedgerVerdict>): Int {
        val now = clock()
        var taken = 0
        for (row in saved) {
            if (row.fileKey in entries) continue
            val verdict = when (row.kind) {
                RetroAchievementsLedgerVerdictKind.MATCHED -> row.raGameId?.let { RetroAchievementsVerdict.Matched(it) }
                RetroAchievementsLedgerVerdictKind.RECOGNIZED_NO_SET ->
                    RetroAchievementsVerdict.RecognizedNoSet.takeIf { now - row.atMillis < RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS }
                RetroAchievementsLedgerVerdictKind.NOT_MATCHED ->
                    RetroAchievementsVerdict.NotMatched.takeIf { now - row.atMillis < RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS }
            } ?: continue
            entries[row.fileKey] = Stamped(verdict, row.atMillis)
            taken += 1
        }
        return taken
    }
}

/** A "no set" verdict (not matched, recognized without a set) held since [atMillis] that has outlived [RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS] at [now]. */
internal fun negativeVerdictExpired(verdict: RetroAchievementsVerdict, atMillis: Long, now: Long): Boolean =
    verdict !is RetroAchievementsVerdict.Matched && now - atMillis >= RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS

internal enum class MimirAchievementUnresolvedReason {
    CHECKING,
    NO_ANSWER_YET,
    NO_CREDENTIALS,
    CREDENTIALS_REJECTED,
    OPTED_OUT,
    UNREACHABLE,
    PROGRESS_UNAVAILABLE,
    SYSTEM_UNSUPPORTED,
    IDENTIFICATION_UNAVAILABLE,
    SYSTEM_UNMAPPED,
    PROVIDER_DATA_INCOMPLETE,
    TOO_LARGE,
    FILE_UNAVAILABLE,
}

internal enum class MimirAchievementNoSetReason {
    NOT_MATCHED,
    RECOGNIZED_NO_SET,
}

/** Typed until the final copy projection, so no string can accidentally acquire file-verdict semantics. */
internal sealed interface MimirAchievementLaneState {
    data class Unresolved(val reason: MimirAchievementUnresolvedReason) : MimirAchievementLaneState
    data class NoSet(val reason: MimirAchievementNoSetReason) : MimirAchievementLaneState
    data class Loaded(val progress: RetroAchievementsProgress) : MimirAchievementLaneState
}

/**
 * One logcat line for a published lane state. Platform id and typed state only: never a URL, key, username, hash,
 * file name, path or game title.
 */
internal fun mimirAchievementLaneDiagnostic(platformId: String, state: MimirAchievementLaneState): String =
    "lane platform=$platformId " + when (state) {
        is MimirAchievementLaneState.Unresolved -> "unresolved=${state.reason.name}"
        is MimirAchievementLaneState.NoSet -> "noset=${state.reason.name}"
        is MimirAchievementLaneState.Loaded ->
            "loaded=${state.progress.unlockedCount}/${state.progress.totalCount}"
    }

internal fun mimirAchievementLaneLabel(state: MimirAchievementLaneState?): String? = when (state) {
    is MimirAchievementLaneState.Loaded ->
        mimirAchievementProgressLabel(state.progress.unlockedCount, state.progress.totalCount)
    is MimirAchievementLaneState.NoSet, is MimirAchievementLaneState.Unresolved, null -> null
}

/** The card for a game nothing has answered for: one shared instance, so reading it costs nothing. */
private val NoAnswerYetCardState: MimirAchievementCardState =
    MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet)

internal fun mimirAchievementCardState(state: MimirAchievementLaneState?): MimirAchievementCardState = when (state) {
    null -> NoAnswerYetCardState
    is MimirAchievementLaneState.Unresolved -> MimirAchievementCardState.Unknown(state.reason.copy)
    is MimirAchievementLaneState.NoSet -> MimirAchievementCardState.NotMatched(state.reason.copy)
    is MimirAchievementLaneState.Loaded -> state.progress.toCardState()
}

/**
 * A lane answer with everything the screens read from it already derived: the card (a sort and a map over every
 * achievement when the set is loaded) and the hero's count label. It is built once, off the main thread, when the
 * answer is published, so a recomposition of the card overlay or the hero only reads fields. The lane state is the
 * truth; [card] and [label] are pure functions of it.
 */
internal class MimirPublishedLane(
    val gameIdValue: String,
    val state: MimirAchievementLaneState,
    val card: MimirAchievementCardState,
    val label: String?,
    /** The account this answer was asked under, so a later account change can tell an answer about the CURRENT account from an old one. */
    val account: String? = null,
)

internal fun mimirPublishedLane(gameIdValue: String, state: MimirAchievementLaneState, account: String? = null): MimirPublishedLane =
    MimirPublishedLane(
        gameIdValue = gameIdValue,
        state = state,
        card = mimirAchievementCardState(state),
        label = mimirAchievementLaneLabel(state),
        account = account,
    )

private val MimirAchievementUnresolvedReason.copy: String
    get() = when (this) {
        MimirAchievementUnresolvedReason.CHECKING -> MimirAchievementCopy.Checking
        MimirAchievementUnresolvedReason.NO_ANSWER_YET -> MimirAchievementCopy.NoAnswerYet
        MimirAchievementUnresolvedReason.NO_CREDENTIALS -> MimirAchievementCopy.NoCredentials
        MimirAchievementUnresolvedReason.CREDENTIALS_REJECTED -> MimirAchievementCopy.CredentialsRejected
        MimirAchievementUnresolvedReason.OPTED_OUT -> MimirAchievementCopy.OptedOut
        MimirAchievementUnresolvedReason.UNREACHABLE -> MimirAchievementCopy.Unreachable
        MimirAchievementUnresolvedReason.PROGRESS_UNAVAILABLE -> MimirAchievementCopy.ProgressUnavailable
        MimirAchievementUnresolvedReason.SYSTEM_UNSUPPORTED -> MimirAchievementCopy.SystemUnsupported
        MimirAchievementUnresolvedReason.IDENTIFICATION_UNAVAILABLE ->
            MimirAchievementCopy.IdentificationUnavailable
        MimirAchievementUnresolvedReason.SYSTEM_UNMAPPED -> MimirAchievementCopy.ConsoleUnmapped
        MimirAchievementUnresolvedReason.PROVIDER_DATA_INCOMPLETE -> MimirAchievementCopy.ProviderDataIncomplete
        MimirAchievementUnresolvedReason.TOO_LARGE -> MimirAchievementCopy.TooLarge
        MimirAchievementUnresolvedReason.FILE_UNAVAILABLE -> MimirAchievementCopy.FileUnavailable
    }

private val MimirAchievementNoSetReason.copy: String
    get() = when (this) {
        MimirAchievementNoSetReason.NOT_MATCHED -> MimirAchievementCopy.NoSetForGame
        MimirAchievementNoSetReason.RECOGNIZED_NO_SET -> MimirAchievementCopy.RecognizedNoSet
    }

internal fun retroAchievementsUnsupportedState() = unresolved(MimirAchievementUnresolvedReason.SYSTEM_UNSUPPORTED)
internal fun retroAchievementsIdentificationUnavailableState() =
    unresolved(MimirAchievementUnresolvedReason.IDENTIFICATION_UNAVAILABLE)
internal fun retroAchievementsNoCredentialsState() = unresolved(MimirAchievementUnresolvedReason.NO_CREDENTIALS)
internal fun retroAchievementsRejectedState() = unresolved(MimirAchievementUnresolvedReason.CREDENTIALS_REJECTED)
internal fun retroAchievementsOptedOutState() = unresolved(MimirAchievementUnresolvedReason.OPTED_OUT)
internal fun retroAchievementsCheckingState() = unresolved(MimirAchievementUnresolvedReason.CHECKING)
internal fun retroAchievementsUnreachableState() = unresolved(MimirAchievementUnresolvedReason.UNREACHABLE)
internal fun retroAchievementsOversizeState() = unresolved(MimirAchievementUnresolvedReason.TOO_LARGE)
internal fun retroAchievementsFileUnavailableState() = unresolved(MimirAchievementUnresolvedReason.FILE_UNAVAILABLE)

private fun unresolved(reason: MimirAchievementUnresolvedReason) = MimirAchievementLaneState.Unresolved(reason)

/**
 * Whether an achievement card must run its own lookup: nothing has answered for this game, or only the Checking
 * placeholder is published (a probe that may have been cancelled). A real answer is shown as it is.
 */
internal fun achievementCardNeedsOwnProbe(state: MimirAchievementLaneState?): Boolean =
    state == null || state == retroAchievementsCheckingState()

/**
 * Total public-facade crossing. Neither a nullable verdict nor prose can erase whether RA rejected the key,
 * failed to answer, completed a file search, or found a set whose progress request then failed.
 */
internal fun retroAchievementsLaneState(
    outcome: RaMatchOutcome,
    progress: RaProgressOutcome,
): MimirAchievementLaneState {
    return when (outcome) {
        RaMatchOutcome.CredentialsRejected -> retroAchievementsRejectedState()
        is RaMatchOutcome.NoAnswer -> if (progress == RaProgressOutcome.CredentialsRejected) {
            retroAchievementsRejectedState()
        } else {
            when (outcome.reason) {
                RaNoAnswerReason.UNCONFIGURED -> unresolved(MimirAchievementUnresolvedReason.NO_ANSWER_YET)
                RaNoAnswerReason.UNSUPPORTED_SYSTEM -> retroAchievementsUnsupportedState()
                RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE ->
                    retroAchievementsIdentificationUnavailableState()
                RaNoAnswerReason.CONSOLE_UNMAPPED -> unresolved(MimirAchievementUnresolvedReason.SYSTEM_UNMAPPED)
                RaNoAnswerReason.FILE_UNAVAILABLE -> retroAchievementsFileUnavailableState()
                RaNoAnswerReason.TRANSPORT -> retroAchievementsUnreachableState()
                RaNoAnswerReason.PROVIDER_DATA ->
                    unresolved(MimirAchievementUnresolvedReason.PROVIDER_DATA_INCOMPLETE)
            }
        }
        is RaMatchOutcome.Answered -> when (progress) {
            RaProgressOutcome.CredentialsRejected -> retroAchievementsRejectedState()
            else -> when (val match = outcome.match) {
                RetroAchievementsMatch.NotMatched ->
                    MimirAchievementLaneState.NoSet(MimirAchievementNoSetReason.NOT_MATCHED)

                RetroAchievementsMatch.RecognizedNoSet ->
                    MimirAchievementLaneState.NoSet(MimirAchievementNoSetReason.RECOGNIZED_NO_SET)

                is RetroAchievementsMatch.Matched -> when (progress) {
                    is RaProgressOutcome.Read -> if (
                        progress.progress.gameId == match.gameId && progress.progress.achievements.isNotEmpty()
                    ) {
                        MimirAchievementLaneState.Loaded(progress.progress)
                    } else {
                        unresolved(MimirAchievementUnresolvedReason.PROGRESS_UNAVAILABLE)
                    }
                    RaProgressOutcome.CredentialsRejected -> retroAchievementsRejectedState()
                    RaProgressOutcome.Unavailable ->
                        unresolved(MimirAchievementUnresolvedReason.PROGRESS_UNAVAILABLE)
                }
            }
        }
    }
}

private fun RetroAchievementsProgress.toCardState(): MimirAchievementCardState.Loaded =
    MimirAchievementCardState.Loaded(
        gameTitle = title,
        unlocked = unlockedCount,
        total = totalCount,
        earnedPoints = earnedPoints,
        totalPoints = totalPoints,
        rows = achievements
            .sortedWith(
                compareByDescending<RetroAchievementsAchievement> { it.unlocked }
                    .thenByDescending { it.points }
                    .thenBy { it.id },
            )
            .map { achievement ->
                MimirAchievementRow(
                    id = achievement.id,
                    title = achievement.title,
                    description = achievement.description,
                    points = achievement.points,
                    medal = when {
                        achievement.unlockedHardcore -> MimirMedalState.EARNED_HARDCORE
                        achievement.unlocked -> MimirMedalState.EARNED
                        else -> MimirMedalState.LOCKED
                    },
                )
            },
    )

/**
 * One shared, per-game, monotonic request token for every achievement probe (the ambient Home / gamelist probe and the card's own).
 *
 * A probe claims a token with [begin] before it suspends, and may publish only while [isCurrent]. A newer request for the same game
 * supersedes the older: its job is cancelled where it honours cancellation, and a late answer from one that does not is dropped at publish. Asking
 * about another game also makes that game the current one, so a late answer for the game the user has left can never overwrite the one they are on.
 * Per-game snapshots stay keyed by game; the fence only decides who may write. It starts no request and caches nothing.
 */
internal class RetroAchievementsProbeFence {
    private var counter = 0L
    private var currentGame: String? = null
    private val newest = HashMap<String, Long>()
    private val jobs = HashMap<String, Job>()

    /** Claim the next token for [gameId], supersede its older request, and make it the game the lane is about. */
    fun begin(gameId: String, job: Job?): Long {
        val superseded: Job?
        val token: Long
        synchronized(this) {
            counter += 1L
            token = counter
            newest[gameId] = token
            currentGame = gameId
            superseded = if (job != null) jobs.put(gameId, job) else jobs.remove(gameId)
        }
        if (superseded != null && superseded !== job) superseded.cancel()
        return token
    }

    /** True only for the newest request for [gameId], and only while [gameId] is the game the lane was last asked about. */
    @Synchronized
    fun isCurrent(gameId: String, token: Long): Boolean = newest[gameId] == token && currentGame == gameId

    /** True while [token] is the newest request held for [gameId], whatever game the lane was last asked about. */
    @Synchronized
    fun isNewest(gameId: String, token: Long): Boolean = newest[gameId] == token

    /** The request is over: release what it held, unless a newer request already replaced it. */
    @Synchronized
    fun finish(gameId: String, token: Long) {
        if (newest[gameId] == token) {
            newest.remove(gameId)
            jobs.remove(gameId)
        }
    }
}

/** How many games' last settled answers the lane keeps for restoring a card's game after Back. */
internal const val RETRO_ACHIEVEMENTS_SETTLED_MAX_GAMES: Int = 64

/** Run [onChange] each time [source] changes after this call (not for the value it holds now). */
internal fun launchOnChange(scope: CoroutineScope, source: Flow<*>, onChange: () -> Unit): Job =
    scope.launch { source.drop(1).collect { onChange() } }

/** What to do for a game whose card went away while the game still said "Checking". */
internal sealed interface CheckingRecovery {
    data object None : CheckingRecovery
    data class Restore(val lane: DerivedLane) : CheckingRecovery
    data object Reprobe : CheckingRecovery
}

/**
 * Changes whenever a game's session starts, moves to another phase (foreground, returning, ended...) or goes away, so a return invalidates the
 * progress cache even though the operation id stays the same.
 */
internal fun retroAchievementsSessionKey(operationValue: String?, phase: MimirSessionPhase?): String? =
    if (operationValue == null || phase == null) null else "$operationValue/${phase.name}"

/**
 * Opening a card ALWAYS asks afresh. An answer already held for the game may stand as the card's immediate placeholder (anything settled, i.e. not
 * nothing and not the Checking placeholder); the fresh answer replaces it.
 */
internal fun retroAchievementsCardShowsPlaceholder(state: MimirAchievementLaneState?): Boolean =
    state != null && state != retroAchievementsCheckingState()

/**
 * The lane's bookkeeping beside the single published slot: the shared request [fence], the last SETTLED answer per game (an LRU), which probes are
 * ambient, and the ACCOUNT EPOCH.
 *
 * The epoch is bumped by every sign-in, sign-out, account, key-state or opt-in change ([accountChanged]). A probe captures it before it suspends
 * and may publish, write the cache or record a settled answer only while it is unchanged, so a probe started under account A can never put A's
 * progress back after B signed in. It also exists so "Checking" can never outlive every probe for its game: opening a card supersedes the game's
 * ambient probe, and if the card goes away before its own answer, [recoveryAfterCardGone] says whether to restore what was last settled for the
 * game or to ask again.
 */
internal class RetroAchievementsLaneBook(
    private val maxSettled: Int = RETRO_ACHIEVEMENTS_SETTLED_MAX_GAMES,
) {
    val fence = RetroAchievementsProbeFence()
    private class Settled(val epoch: Long, val lane: DerivedLane)
    private val settled = object : LinkedHashMap<String, Settled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Settled>): Boolean = size > maxSettled
    }
    private val ambientTokens = HashMap<String, Long>()
    private var accountEpoch = 0L

    @Synchronized
    fun accountEpoch(): Long = accountEpoch

    /** True while no sign-in, sign-out, account, key-state or opt-in change has happened since [epoch] was captured. */
    @Synchronized
    fun accountUnchangedSince(epoch: Long): Boolean = accountEpoch == epoch

    /** The account side changed: what was settled, held or in flight for the old one may no longer be claimed. */
    fun accountChanged() {
        synchronized(this) {
            accountEpoch += 1L
            settled.clear()
        }
    }

    /**
     * Wire the lane to its sources in [scope]. Cancelling [scope] detaches every collector, so a coordinator that is disposed leaves nothing behind
     * on the process-wide flows. The callbacks and lambdas here capture only this book and the callbacks passed in, never a context of their own.
     *
     * @param onSessionReturned a game's session just returned or ended ([retroAchievementsSessionReturned]).
     * @param onAccountChanged a sign-in, sign-out, account, key-state or opt-in change, AFTER the epoch was bumped and the settled answers dropped.
     */
    fun attach(
        scope: CoroutineScope,
        sessionKeys: Flow<String?>,
        accountSources: List<Flow<*>>,
        onSessionReturned: () -> Unit = {},
        onAccountChanged: () -> Unit = {},
    ) {
        scope.launch {
            var previous: String? = null
            var first = true
            sessionKeys.collect { key ->
                if (!first && retroAchievementsSessionReturned(previous, key)) onSessionReturned()
                previous = key
                first = false
            }
        }
        for (source in accountSources) {
            launchOnChange(scope, source) {
                accountChanged()
                onAccountChanged()
            }
        }
    }

    private val knownLocalByRaId = object : LinkedHashMap<Int, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>): Boolean = size > 512
    }

    /** A launch-card read identified [localGameId] from its own file as RetroAchievements game [raGameId]: the exact link the bulk matcher prefers. */
    @Synchronized
    fun noteMatchedRaId(raGameId: Int, localGameId: String): Boolean {
        val changed = knownLocalByRaId[raGameId] != localGameId
        knownLocalByRaId[raGameId] = localGameId
        return changed
    }

    @Synchronized
    fun knownLocalByRaId(): Map<Int, String> = HashMap(knownLocalByRaId)

    /** The saved links of the account now signed in: a link identified in this process is newer and stays. */
    @Synchronized
    fun restoreKnownLocal(saved: Map<Int, String>) {
        for ((raGameId, localGameId) in saved) if (raGameId !in knownLocalByRaId) knownLocalByRaId[raGameId] = localGameId
    }

    /** The account the links belonged to went away (its saved links are untouched). */
    @Synchronized
    fun clearKnownLocal() {
        knownLocalByRaId.clear()
    }

    /**
     * Remember a published answer as the game's last settled one, if it was asked under the CURRENT account epoch ([epoch]). The Checking
     * placeholder never counts. Returns whether it was kept.
     */
    @Synchronized
    fun noteSettled(derived: DerivedLane, epoch: Long): Boolean {
        if (epoch != accountEpoch || derived.published.state == retroAchievementsCheckingState()) return false
        settled[derived.published.gameIdValue] = Settled(epoch, derived)
        return true
    }

    @Synchronized
    fun settledFor(gameId: String): DerivedLane? = settled[gameId]?.takeIf { it.epoch == accountEpoch }?.lane

    /**
     * True when a per-game read of the CURRENT account (this epoch) has already seen an unlock. A bulk read that then says "no games" contradicts it, so it is
     * not believed (see [ActivityBulkLoader.load]).
     */
    @Synchronized
    fun settledKnowsUnlock(): Boolean = settled.values.any { entry ->
        entry.epoch == accountEpoch && (entry.lane.published.state as? MimirAchievementLaneState.Loaded)?.progress?.unlockedCount?.let { it > 0 } == true
    }

    @Synchronized
    fun ambientBegan(gameId: String, token: Long) {
        ambientTokens[gameId] = token
    }

    @Synchronized
    fun ambientEnded(gameId: String, token: Long) {
        if (ambientTokens[gameId] == token) ambientTokens.remove(gameId)
    }

    /** An ambient probe is the newest request for [gameId], so it will publish an answer by itself. */
    @Synchronized
    fun ambientHoldsNewest(gameId: String): Boolean {
        val token = ambientTokens[gameId] ?: return false
        return fence.isNewest(gameId, token)
    }

    /**
     * The card for [gameId] is gone. If the game's slot still says Checking ([slotIsChecking]) and no ambient probe will answer it, restore the
     * last settled answer (current account only), or with none ask again.
     */
    @Synchronized
    fun recoveryAfterCardGone(gameId: String, slotIsChecking: Boolean): CheckingRecovery = when {
        !slotIsChecking || ambientHoldsNewest(gameId) -> CheckingRecovery.None
        else -> settledFor(gameId)?.let { CheckingRecovery.Restore(it) } ?: CheckingRecovery.Reprobe
    }
}

/**
 * May a probe publish [state]? The probe must still be about the same account ([accountUnchanged]), and either no account-side change has happened
 * since it began ([epochUnchanged]) or the answer is the REJECTION of the current key.
 *
 * A 401/403 on the current key marks the key rejected, and that very change bumps the account epoch, so the probe that DETECTED the rejection
 * always finds its own epoch stale. A rejection describes the current account (the store only marks the key rejected when the request carried the
 * current key's revision, [keyState]), so it is published under the new epoch rather than dropped: otherwise the open card would stay on "no answer
 * yet" until reopened. Any other answer from an older epoch (a stale success, a stale rejection of a key that is no longer current) never publishes.
 */
internal fun retroAchievementsPublishAllowed(
    epochUnchanged: Boolean,
    accountUnchanged: Boolean,
    state: MimirAchievementLaneState,
    keyState: RaKeyState,
): Boolean = accountUnchanged && (
    epochUnchanged || (state == retroAchievementsRejectedState() && keyState == RaKeyState.REJECTED_THIS_SESSION)
    )

/**
 * What the published slot keeps when the account side changes. A different account, a sign-out or an opt-out clears it; so does anything but the
 * rejection of the key that is rejected NOW for the account that is signed in now. That one is the answer the change itself produced, and it
 * must survive whichever of the change's notification and the probe's publication runs first.
 */
internal fun retroAchievementsSlotAfterAccountChange(
    slot: MimirPublishedLane?,
    currentAccount: String?,
    keyState: RaKeyState,
): MimirPublishedLane? = slot?.takeIf {
    it.state == retroAchievementsRejectedState() &&
        keyState == RaKeyState.REJECTED_THIS_SESSION &&
        !currentAccount.isNullOrBlank() &&
        it.account == currentAccount
}

/** A game's session just returned or ended: it moved to its returning or ended phase, or went away after having been running. */
internal fun retroAchievementsSessionReturned(previousKey: String?, newKey: String?): Boolean =
    previousKey != null && newKey != previousKey &&
        (newKey == null || newKey.endsWith("/${MimirSessionPhase.RETURNING.name}") || newKey.endsWith("/${MimirSessionPhase.ENDED.name}"))
