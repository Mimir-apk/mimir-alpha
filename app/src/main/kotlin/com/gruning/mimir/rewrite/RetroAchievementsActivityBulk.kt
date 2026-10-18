package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAccount
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsIcons
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerWrite
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsRecentUnlock
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.metadata.online.toUserGame
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** The part of a library game the matcher needs. Never a path, a hash or a file name. */
internal data class LocalGameKey(val gameId: String, val platformId: String, val title: String)

/** How often a return from a game may refresh the bulk answer. at most once per five minutes. */
internal const val RETRO_ACHIEVEMENTS_BULK_REFRESH_THROTTLE_MS: Long = 5L * 60L * 1000L

/**
 * A title reduced to what two catalogues agree on: lower case, bracketed notes and punctuation removed, "&" read as "and", a leading "the" dropped.
 * It is only ever compared within one platform, and a title that two library games share is left unmatched rather than guessed.
 */
internal fun retroAchievementsMatchTitle(title: String): String {
    var text = title.lowercase(Locale.ROOT)
    text = text.replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
    text = text.replace("&", " and ")
    text = text.replace(Regex("[^a-z0-9 ]"), " ").trim()
    text = text.replace(Regex("\\s+"), " ")
    if (text.startsWith("the ")) text = text.removePrefix("the ")
    return text.replace(" ", "")
}

/**
 * One snapshot per game the user has progress on, matched to the library where it can be.
 *
 * A game is matched to a library entry (so the Bottom can show its art and launch it) when, in order of trust: its RA id was already identified from
 * the game's own file by a launch-card read this session ([knownLocalByRaId]); or the library has exactly ONE game on the platform RA's console maps to
 * whose title matches. A library with two candidates, or none, leaves the game unmatched: it still shows, under a neutral `ra:<id>` identity, with the
 * numbers RA gave. Nothing here hashes a file or touches the network, so the cold start pays for no scan.
 *
 * A game with nothing earned that matches no CURRENT library game is left out (see the comment at the filter): the page is the library's opened games.
 */
internal fun matchActivityBulk(
    games: List<RetroAchievementsUserGame>,
    local: List<LocalGameKey>,
    platformIdFor: (consoleName: String) -> String?,
    knownLocalByRaId: Map<Int, String>,
    account: String,
    nowMillis: Long,
): List<CompanionActivityAchievementSnapshot> {
    val valid = games.filter { it.isShowable }
    val localById = local.associateBy { it.gameId }
    val byPlatformAndTitle = HashMap<Pair<String, String>, MutableList<LocalGameKey>>()
    for (game in local) {
        byPlatformAndTitle.getOrPut(game.platformId to retroAchievementsMatchTitle(game.title)) { mutableListOf() } += game
    }
    // A title is only an identity when it is unique on BOTH sides: two RA games of one console that normalize alike (a remake and its original, a
    // regional pair) would otherwise both claim the one library game and share its key.
    fun titleKey(game: RetroAchievementsUserGame): Pair<String, String> =
        (platformIdFor(game.consoleName) ?: "console:${retroAchievementsMatchTitle(game.consoleName)}") to retroAchievementsMatchTitle(game.title)
    val raTitleCounts = valid.groupingBy(::titleKey).eachCount()

    // A library game is claimed at most once, so no two rows can ever carry the same key. The file-identified link goes first.
    val claimed = HashSet<String>()
    val matchedByRaId = HashMap<Int, LocalGameKey>()
    for (game in valid) {
        val known = knownLocalByRaId[game.gameId]?.let(localById::get) ?: continue
        if (claimed.add(known.gameId)) matchedByRaId[game.gameId] = known
    }
    for (game in valid) {
        if (game.gameId in matchedByRaId) continue
        val key = titleKey(game)
        if (raTitleCounts[key] != 1) continue
        val candidate = platformIdFor(game.consoleName)?.let { byPlatformAndTitle[key]?.singleOrNull() } ?: continue
        if (claimed.add(candidate.gameId)) matchedByRaId[game.gameId] = candidate
    }
    return valid.mapNotNull { game ->
        val matched = matchedByRaId[game.gameId]
        // Display scope : the page lists the library's games that have been opened. A game with NOTHING earned is only known because its card was
        // opened once, so it shows only while it is still a game of the library; one with earned counts shows either way, under RA's own key if need be. The row
        // itself stays in the ledger: this only decides what is published.
        if (matched == null && game.earned == 0) return@mapNotNull null
        CompanionActivityAchievementSnapshot(
            gameId = matched?.gameId ?: "ra:${game.gameId}",
            account = account,
            unlocked = game.earned,
            total = game.total,
            gameTitle = matched?.title?.takeIf(String::isNotBlank) ?: game.title,
            earned = emptyList(),
            checkedAtEpochMillis = nowMillis,
            rowsKnown = false,
            iconPath = game.imageIconPath,
            raGameId = game.gameId,
            consoleName = game.consoleName.trim().takeIf(String::isNotEmpty),
            lastAwardedAtMillis = game.lastAwardedAtMillis,
            earnedHardcore = game.earnedHardcore,
            awardKind = game.awardKind,
        )
    }
}

/** The most per-game rows waiting for a ledger write at once (see [ActivityBulkLoader.noteLaneResult]); the oldest go first. */
internal const val ACTIVITY_LANE_PENDING_MAX: Int = 512

/** The most unlocks the lane holds from one read; the strip shows [ACTIVITY_RECENT_STRIP_MAX] of them. */
internal const val ACTIVITY_RECENT_KEEP: Int = 60

/** An API unlock as the strip holds it: its badge only as the validated colour path, never a provider URL. */
internal fun RetroAchievementsRecentUnlock.toActivityRecentUnlock(): ActivityRecentUnlock = ActivityRecentUnlock(
    achievementId = achievementId,
    title = title,
    description = description,
    points = points,
    badgePath = RetroAchievementsIcons.badgePath(badgeName, locked = false),
    raGameId = gameId,
    gameTitle = gameTitle,
    awardedAtMillis = awardedAtMillis,
    hardcore = hardcore,
)

/**
 * What one bulk load did. Only [LOADED] changed what the Activity page holds; [PARTIAL] read some pages and stopped short, and is neither published nor saved;
 * [SUSPECT_EMPTY] read "no games" for an account that other evidence says has progress, and is neither published nor saved either.
 */
internal enum class ActivityBulkResult { LOADED, NOT_SIGNED_IN, REJECTED, UNAVAILABLE, PARTIAL, DROPPED, THROTTLED, BUSY, SUSPECT_EMPTY }

/** One line under the MimirRA tag. Logging can never fail a load (a plain JVM test has no logcat). */
internal fun logRetroAchievementsLine(line: String) {
    runCatching { android.util.Log.i("MimirRA", line) }
}

/** How one bulk read ended, for the one outcome line a load writes. */
internal enum class RetroAchievementsBulkOutcome(val logName: String) {
    /** Every page of the games was read: published, and saved to the ledger. */
    COMPLETE("complete"),

    /** Some pages were read and the read stopped short: nothing is published or saved, so a complete snapshot already on disk stays whole. */
    PARTIAL("partial"),

    /** Nothing was read (offline, refused, rate limited): nothing is published or saved. */
    UNAVAILABLE("unavailable"),

    /**
     * Every page was read and held no game, but the account has progress by other evidence (profile points, an unlock a per-game read saw, earned counts the ledger
     * already holds): the empty answer is not believed. The games are neither published nor saved, so the ledger and the page keep the games they have; the
     * points and the recent unlocks the same read brought are published and saved (they are the freshest there are).
     *
     * Known limit, left as it is: after a genuine full reset of the account (everything lost on RetroAchievements' side) this can stick for as long as the
     * profile still shows points or the ledger still holds earned counts for it; it clears once each game with stale counts has been read again on its own
     * (a per-game read replaces the game's row), or a read finds games.
     */
    SUSPECT_EMPTY("suspect-empty"),
}

/**
 * The one line a bulk read writes under the MimirRA tag. [games] is how many games this read returned, [persisted] how many games the account's ledger holds
 * AFTER the read (the same as [games] after a complete read with nothing else saved, and what survived untouched after any other). [why], for a suspect empty
 * answer only, is which evidence contradicted it: `points`, `unlock` or `ledger`. Counts and fixed words only: never a username, a key or a URL.
 */
internal fun retroAchievementsBulkOutcomeLine(outcome: RetroAchievementsBulkOutcome, games: Int, persisted: Int, why: String? = null): String =
    "bulk outcome=${outcome.logName} games=$games persisted=$persisted" + (why?.let { " why=$it" } ?: "")

/**
 * The Activity page's cold-start bulk read, held as ONE snapshot (in [CompanionActivityAchievements], which the hub view and the strip read).
 *
 * One [load] is one pass of the completion read (paged inside the client), one points read and one recent-unlocks read (the strip), off the main thread; nothing on a swipe, a focus move
 * or a scan path ever reaches it. It is fenced by the account epoch exactly like a probe: a read that began under one account and finished after a
 * switch publishes nothing. [refreshAfterReturn] is the only automatic refresh after the first load, throttled to once per
 * [RETRO_ACHIEVEMENTS_BULK_REFRESH_THROTTLE_MS]; an account change [reset]s the throttle and the caller loads again.
 *
 * **One read, one user.** A read captures (account, epoch, sign-in) ONCE when it begins, after it has the lock: every page and every sub-request is asked with
 * that captured sign-in ([credentials]; the sources get it as their argument) and never re-reads the stored one, so a switch mid-read cannot turn one read into
 * a mix of two users' pages. The ledger write is then fenced by the epoch inside the ledger's own lock, so a read that a switch overtook writes nothing, under
 * either account.
 */
internal class ActivityBulkLoader(
    private val book: RetroAchievementsLaneBook,
    private val account: () -> String?,
    private val loadGames: suspend (pinned: RetroAchievementsAccount?) -> RaUserGamesOutcome,
    private val loadPoints: suspend (pinned: RetroAchievementsAccount?) -> RaPointsOutcome,
    private val library: suspend () -> List<LocalGameKey>,
    private val platformIdFor: (String) -> String?,
    private val publish: (account: String, snapshots: List<CompanionActivityAchievementSnapshot>, totalPoints: Int?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val workContext: CoroutineContext = Dispatchers.IO,
    /** Where the epoch is re-checked and the snapshot written, one step: the same serialized context the account-change handler runs on. */
    private val publishContext: CoroutineContext = Dispatchers.Main.immediate,
    private val throttleMillis: Long = RETRO_ACHIEVEMENTS_BULK_REFRESH_THROTTLE_MS,
    /** The user's recent unlocks across every game: ONE request per load, with the bulk read (the default reads nothing, so a loader that wants none is unchanged). */
    private val loadRecent: suspend (pinned: RetroAchievementsAccount?) -> RaRecentOutcome = { RaRecentOutcome.Unavailable },
    private val publishRecent: (account: String, unlocks: List<ActivityRecentUnlock>) -> Unit = { _, _ -> },
    /** Publishes the profile's points alone, leaving the games as they are: what a read whose empty games answer was not believed still brings. */
    private val publishPoints: (account: String, totalPoints: Int) -> Unit = { _, _ -> },
    /** The durable ledger: a COMPLETE read is saved to it (an upsert, so the same answer twice changes nothing), the per-game reads the lane makes are folded into it ([noteLaneResult]), and [hydrate] publishes it. Null keeps nothing. */
    private val ledger: RetroAchievementsLedger? = null,
    /** Publishes what the ledger held (see [hydrate]) for each field no live answer has landed for; true when it published anything. */
    private val publishHydrated: (account: String, snapshots: List<CompanionActivityAchievementSnapshot>, totalPoints: Int?, recent: List<ActivityRecentUnlock>?) -> Boolean =
        { _, _, _, _ -> false },
    /** A read for [account] ended without publishing: the page may stop saying "checking". */
    private val markSettled: (account: String) -> Unit = {},
    /** Where the outcome line goes; the real one writes it under the MimirRA tag in logcat. */
    private val log: (String) -> Unit = ::logRetroAchievementsLine,
    /**
     * The stored sign-in, read ONCE per [load] (on [workContext]) and handed to every source as `pinned`. It must be the account the read is for (compared without
     * regard to case): anything else, or nothing, and the read asks nothing and ends as [ActivityBulkResult.UNAVAILABLE]. Null means the sources hold their own
     * credentials (a test double), and they get null.
     */
    private val credentials: (() -> RetroAchievementsAccount?)? = null,
    /** How soon after a read that brought NOTHING (offline, partial) the next return may try again: much sooner than the full [throttleMillis] window. */
    private val failureBackoffMillis: Long = RETRO_ACHIEVEMENTS_BULK_FAILURE_BACKOFF_MS,
    /**
     * Publishes the profile's SOFTCORE score beside the hardcore one the other callbacks carry (their points are the hardcore score: RA's main one). The two are kept
     * apart and never added. [hydrated] is true when it came from the ledger, which must not go over a live answer.
     */
    private val publishSoftcore: (account: String, softcorePoints: Int, hydrated: Boolean) -> Unit = { _, _, _ -> },
) {
    private val running = Mutex()

    /** The moment [refreshAfterReturn] may load again: a window after the last load began, or a short backoff after one that brought nothing. */
    private var nextEligibleAtMillis: Long? = null

    /** An account change: the old account's throttle is not the new one's. */
    @Synchronized
    fun reset() {
        nextEligibleAtMillis = null
    }

    @Synchronized
    private fun markAttempt(now: Long) {
        nextEligibleAtMillis = now + throttleMillis
    }

    /** A read that brought nothing must not shut the next return out for the whole window: it may be retried after the short backoff (never later than the window itself). */
    @Synchronized
    private fun markFailed(now: Long) {
        nextEligibleAtMillis = minOf(nextEligibleAtMillis ?: Long.MAX_VALUE, now + failureBackoffMillis)
    }

    @Synchronized
    private fun throttled(now: Long): Boolean = nextEligibleAtMillis?.let { now < it } == true

    /** When [refreshAfterReturn] may next load, or null when it may now (nothing was loaded yet, or the account just changed). */
    @Synchronized
    fun eligibleAtMillis(): Long? = nextEligibleAtMillis

    /**
     * Publish what the durable ledger holds for the signed-in account, so the page shows the last known counts at once and the live read below only refreshes
     * them. It reads the disk and the library OFF the main thread, matches the saved games to the library exactly as a live read does, and publishes on
     * [publishContext] only while no account-side change happened since it began (the same epoch fence). Each field is published only if no live answer has
     * landed for it (the publish decides per field: live games, points and recent unlocks each keep their live value). Never asks the network. True when it
     * published anything.
     */
    suspend fun hydrate(): Boolean {
        val store = ledger ?: return false
        val signedIn = account()?.takeIf(String::isNotBlank) ?: return false
        val epoch = book.accountEpoch()
        val prepared = withContext(workContext) {
            val saved = runCatching { store.readBulk(signedIn) }.getOrNull() ?: return@withContext null
            val local: List<LocalGameKey> = if (saved.games.isEmpty()) emptyList() else runCatching { library() }.getOrDefault(emptyList())
            Hydrated(
                snapshots = matchActivityBulk(
                    games = saved.games,
                    local = local,
                    platformIdFor = platformIdFor,
                    knownLocalByRaId = book.knownLocalByRaId(),
                    account = signedIn,
                    nowMillis = saved.savedAtMillis,
                ),
                points = saved.points,
                softcore = saved.softcorePoints,
                recent = saved.recent?.map { it.toActivityRecentUnlock() },
            )
        } ?: return false
        return withContext(publishContext) {
            if (!book.accountUnchangedSince(epoch) || account() != signedIn) {
                false
            } else {
                val published = publishHydrated(signedIn, prepared.snapshots, prepared.points, prepared.recent)
                prepared.softcore?.let { publishSoftcore(signedIn, it, true) }
                published
            }
        }
    }

    /** After a game session returned: a refresh only when the last attempt is older than the throttle. */
    suspend fun refreshAfterReturn(): ActivityBulkResult =
        if (throttled(clock())) ActivityBulkResult.THROTTLED else load()

    /**
     * The bulk read now (the first one at cold start, and one per sign-in or account change).
     *
     * With [waitForRunning] false (cold start, a return refresh) a load already running turns this one away as [ActivityBulkResult.BUSY]. An
     * account change passes true: the caller has already cancelled the old account's load, and this one QUEUES behind it and then loads the new
     * account, so a refresh for a new account is never swallowed by the old one's.
     */
    suspend fun load(waitForRunning: Boolean = false): ActivityBulkResult {
        if (account().isNullOrBlank()) return ActivityBulkResult.NOT_SIGNED_IN
        if (waitForRunning) running.lock() else if (!running.tryLock()) return ActivityBulkResult.BUSY
        try {
            // Read AFTER the lock: a load that queued behind another asks for whoever is signed in now, under the epoch of now.
            val signedIn = account()?.takeIf(String::isNotBlank) ?: return ActivityBulkResult.NOT_SIGNED_IN
            val epoch = book.accountEpoch()
            markAttempt(clock())
            val read = withContext(workContext) {
                // The sign-in this read is made under is captured HERE, once: the pages below never read the stored one again.
                val capture = credentials
                val pinned: RetroAchievementsAccount? = if (capture == null) {
                    null
                } else {
                    val held = capture()
                    if (held == null || !held.username.trim().equals(signedIn.trim(), ignoreCase = true)) {
                        logOutcome(RetroAchievementsBulkOutcome.UNAVAILABLE, 0, signedIn)
                        return@withContext ActivityBulkResult.UNAVAILABLE
                    }
                    held
                }
                // Only a COMPLETE read of the games goes past this point, so only a complete read is ever saved: a partial, refused or unavailable one
                // publishes nothing and leaves the ledger exactly as it was.
                val games = when (val outcome = loadGames(pinned)) {
                    is RaUserGamesOutcome.Read -> outcome.games
                    RaUserGamesOutcome.CredentialsRejected -> {
                        logOutcome(RetroAchievementsBulkOutcome.UNAVAILABLE, 0, signedIn)
                        return@withContext ActivityBulkResult.REJECTED
                    }
                    is RaUserGamesOutcome.Partial -> {
                        logOutcome(RetroAchievementsBulkOutcome.PARTIAL, outcome.games.size, signedIn)
                        return@withContext ActivityBulkResult.PARTIAL
                    }
                    RaUserGamesOutcome.Unavailable -> {
                        logOutcome(RetroAchievementsBulkOutcome.UNAVAILABLE, 0, signedIn)
                        return@withContext ActivityBulkResult.UNAVAILABLE
                    }
                }
                // The profile's two scores: points is the hardcore one (RA's main score), softcore the other. They are kept apart and never added.
                val pointsRead = loadPoints(pinned) as? RaPointsOutcome.Read
                val points = pointsRead?.points
                val softcore = pointsRead?.softcorePoints
                // "No games at all" is a claim, and a wrong one is costly: saved, it would wipe what the ledger holds and the Bottom would load game by game at
                // every start. So it is believed only when nothing says the account has progress. Otherwise the GAMES are neither published nor saved, and
                // the points and recent unlocks this same read brought still are (below).
                val heldGames = runCatching { ledger?.readBulk(signedIn)?.games }.getOrNull().orEmpty()
                // (Whether the profile shows ANY points is only evidence that the account has progress; it is never shown, so adding the two scores here is harmless.)
                val suspectWhy = if (games.isEmpty()) zeroGamesWhy(pointsRead?.let { it.points + it.softcorePoints }, heldGames) else null
                // One request for the whole recent window. A refused key is a refused key; any other failure leaves what the strip already holds.
                val recentRaw = when (val outcome = loadRecent(pinned)) {
                    is RaRecentOutcome.Read -> outcome.unlocks.take(ACTIVITY_RECENT_KEEP)
                    RaRecentOutcome.CredentialsRejected -> {
                        logOutcome(RetroAchievementsBulkOutcome.UNAVAILABLE, 0, signedIn)
                        return@withContext ActivityBulkResult.REJECTED
                    }
                    RaRecentOutcome.Unavailable -> null
                }
                val recent = recentRaw?.map { it.toActivityRecentUnlock() }
                // The fence for every ledger write below: this read is still the current one, asked again INSIDE the ledger's lock at the last moment before the
                // file would change, so a sign-in change that lands while the write waits stops it. A failed or refused write is a missed save, never a failed load.
                val mayWrite = { book.accountUnchangedSince(epoch) && account() == signedIn }
                if (suspectWhy != null) {
                    // A read whose games are not believed is not believed about a zero total either: only a positive total is saved or shown.
                    val suspectRead = pointsRead?.takeIf { it.points + it.softcorePoints > 0 }
                    val suspectPoints = suspectRead?.points
                    val suspectSoftcore = suspectRead?.softcorePoints
                    runCatching { ledger?.saveBulkExtras(signedIn, suspectPoints, recentRaw, nowMillis = clock(), mayWrite = mayWrite, softcorePoints = suspectSoftcore) }
                    logOutcome(RetroAchievementsBulkOutcome.SUSPECT_EMPTY, 0, signedIn, suspectWhy)
                    return@withContext BulkRead(snapshots = null, points = suspectPoints, softcore = suspectSoftcore, recent = recent)
                }
                val local = library()
                // Saved as the account's list; the games only a per-game read has seen with nothing earned stay (decided inside the ledger's lock, from the file as
                // it is then, so a per-game write that landed while this read was under way is not erased). An upsert: the same answer twice writes nothing.
                val write = runCatching {
                    ledger?.saveBulk(signedIn, games, points, recentRaw, nowMillis = clock(), mayWrite = mayWrite, keepObserved = true, softcorePoints = softcore)
                }.getOrNull()
                val saved = runCatching { ledger?.readBulk(signedIn)?.games }.getOrNull()
                val persisted = saved?.size ?: 0
                log(retroAchievementsBulkOutcomeLine(RetroAchievementsBulkOutcome.COMPLETE, games.size, persisted))
                // What the page shows is the list as saved (the kept rows included); with no saved list (no ledger, or a write that failed) it is the read plus what
                // the ledger held before it.
                val listed = games.mapTo(HashSet()) { it.gameId }
                val everything = if ((write == RetroAchievementsLedgerWrite.WRITTEN || write == RetroAchievementsLedgerWrite.UNCHANGED) && saved != null) {
                    saved
                } else {
                    games + heldGames.filter { it.earned == 0 && it.gameId !in listed }
                }
                BulkRead(
                    snapshots = matchActivityBulk(
                        games = everything,
                        local = local,
                        platformIdFor = platformIdFor,
                        knownLocalByRaId = book.knownLocalByRaId(),
                        account = signedIn,
                        nowMillis = clock(),
                    ),
                    points = points,
                    softcore = softcore,
                    recent = recent,
                )
            }
            if (read !is BulkRead) {
                // The read ended without an answer: the page stops saying "checking" and shows what it holds (the ledger's rows, or the plain empty state).
                markSettled(signedIn)
                // Nothing was brought (offline, a page failed): a return a minute later may try again instead of waiting out the whole window. A read an account
                // switch overtook leaves the new account's throttle alone.
                if ((read == ActivityBulkResult.UNAVAILABLE || read == ActivityBulkResult.PARTIAL) && book.accountUnchangedSince(epoch)) markFailed(clock())
                return read as ActivityBulkResult
            }
            // The fence and the write are ONE step on the serialized [publishContext], the same context the account-change handler runs on: a
            // switch cannot land between the check and the write, and one that landed before it fails the check. (Checked on the IO thread, a
            // switch could slip in between the two and the old account's data would republish.)
            return withContext(publishContext) {
                if (!book.accountUnchangedSince(epoch) || account() != signedIn) {
                    ActivityBulkResult.DROPPED
                } else if (read.snapshots == null) {
                    // The empty games answer was not believed: the games the page holds stay, and only the points and recent unlocks this read brought go on it.
                    read.points?.let { publishPoints(signedIn, it) }
                    read.softcore?.let { publishSoftcore(signedIn, it, false) }
                    read.recent?.let { publishRecent(signedIn, it) }
                    markSettled(signedIn)
                    ActivityBulkResult.SUSPECT_EMPTY
                } else {
                    publish(signedIn, read.snapshots, read.points)
                    read.softcore?.let { publishSoftcore(signedIn, it, false) }
                    read.recent?.let { publishRecent(signedIn, it) }
                    ActivityBulkResult.LOADED
                }
            }
        } finally {
            running.unlock()
        }
    }

    /**
     * Which evidence contradicts a complete read of ZERO games, or null when none does: the profile has points (`points`, [points]), a per-game read of this account
     * already saw an unlock (`unlock`), or the ledger already holds earned counts for it (`ledger`, [held]). Progress does not vanish between two starts, so any of
     * these means the empty answer is the odd one out; the first that holds is named. A ledger row of 0 earned (a game the lane merely looked at) proves nothing
     * and does not count. (After a genuine full reset this can stick; see [RetroAchievementsBulkOutcome.SUSPECT_EMPTY].)
     */
    private fun zeroGamesWhy(points: Int?, held: List<RetroAchievementsUserGame>): String? = when {
        (points ?: 0) > 0 -> "points"
        book.settledKnowsUnlock() -> "unlock"
        held.any { it.earned > 0 } -> "ledger"
        else -> null
    }

    /** One game's progress, held until the next [flushLaneResults] writes it. [epoch] is the account epoch the per-game read began under. */
    private class PendingLane(val account: String, val epoch: Long, val progress: RetroAchievementsProgress)

    /** The per-game reads waiting to be written, newest per RA game. A burst of reads is one ledger write. */
    private val pendingLane = LinkedHashMap<Int, PendingLane>()
    private val laneFlushQueued = AtomicBoolean(false)

    /**
     * A per-game read ([progress], for [account]) just landed on the lane: queue its row for the durable ledger, so what the Bottom has seen once is there at the
     * next cold start even when no bulk read ever succeeds. [epoch] is the account epoch the read began under; the write is fenced by it inside the ledger's own
     * lock, so a read a sign-in change overtook writes nothing. Does no file work: it returns at once.
     *
     * Returns true when the caller must now schedule one [flushLaneResults] (the first note since the last flush); false when one is already scheduled or the
     * read leaves nothing to save.
     */
    fun noteLaneResult(account: String?, progress: RetroAchievementsProgress, epoch: Long): Boolean {
        if (ledger == null) return false
        val name = account?.takeIf(String::isNotBlank) ?: return false
        if (progress.gameId <= 0 || progress.totalCount <= 0) return false
        synchronized(pendingLane) {
            pendingLane.remove(progress.gameId)
            pendingLane[progress.gameId] = PendingLane(name, epoch, progress)
            // A burst is flushed every few hundred milliseconds; this only bounds a flush that never comes.
            while (pendingLane.size > ACTIVITY_LANE_PENDING_MAX) pendingLane.remove(pendingLane.keys.first())
        }
        return laneFlushQueued.compareAndSet(false, true)
    }

    /**
     * Write every queued per-game row to the ledger, grouped by (account, epoch) so each group is ONE upsert fenced by its own epoch and account. Run it off the main
     * thread; it asks no network. A failed or refused write is a missed save and nothing more. Returns how many groups changed the file.
     */
    suspend fun flushLaneResults(): Int {
        val store = ledger ?: return 0
        // Cleared BEFORE the queue is taken: a note that lands after this schedules the next flush, one that landed before is in the batch below.
        laneFlushQueued.set(false)
        val batch = synchronized(pendingLane) { pendingLane.values.toList().also { pendingLane.clear() } }
        if (batch.isEmpty()) return 0
        return withContext(workContext) {
            var changed = 0
            for ((key, items) in batch.groupBy { it.account to it.epoch }) {
                val (name, epoch) = key
                val write = runCatching {
                    store.upsertBulkGames(
                        name,
                        // Built here, off the main thread: it parses the unlock dates of every achievement the read held.
                        items.mapNotNull { it.progress.toUserGame() },
                        nowMillis = clock(),
                        mayWrite = { book.accountUnchangedSince(epoch) && account() == name },
                    )
                }.getOrNull()
                if (write == RetroAchievementsLedgerWrite.WRITTEN) changed += 1
            }
            changed
        }
    }

    /**
     * One outcome line for a read that did not complete or was not believed: what the ledger holds for [account] is the persisted figure (the games it holds are
     * never changed by such a read). [why] names the evidence behind a suspect empty answer.
     */
    private fun logOutcome(outcome: RetroAchievementsBulkOutcome, games: Int, account: String, why: String? = null) {
        val held = runCatching { ledger?.bulkGameCount(account) ?: 0 }.getOrDefault(0)
        log(retroAchievementsBulkOutcomeLine(outcome, games, held, why))
    }

    /** What a read brought. [snapshots] is null for a read whose empty games answer was not believed: only [points] and [recent] go on the page then. */
    private class BulkRead(val snapshots: List<CompanionActivityAchievementSnapshot>?, val points: Int?, val softcore: Int?, val recent: List<ActivityRecentUnlock>?)

    private class Hydrated(val snapshots: List<CompanionActivityAchievementSnapshot>, val points: Int?, val softcore: Int?, val recent: List<ActivityRecentUnlock>?)
}
