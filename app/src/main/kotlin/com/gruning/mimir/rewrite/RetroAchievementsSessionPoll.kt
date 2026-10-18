package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How often a running game's own achievement list is read again for new unlocks (): every two minutes. */
internal const val RETRO_ACHIEVEMENTS_SESSION_POLL_MS: Long = 120_000L

/** After a session returns, the read that finds what it earned is tried again this many more times if the first got no answer (a Retry-After in force, or offline). */
internal const val RETRO_ACHIEVEMENTS_RETURN_READ_RETRIES: Int = 2

/** The wait between those tries. */
internal const val RETRO_ACHIEVEMENTS_RETURN_READ_RETRY_MS: Long = 60_000L

/** A Mimir-launched game that is being played: its session, its library game and the wall-clock moment it began (an unlock is this session's only if it came after it). */
internal data class RaPlayingSession(val operation: String, val gameId: String, val startedAtEpochMillis: Long)

/**
 * This session as a game being played, or null once it is over or on its way back (returning, ended, failed, stale): the unlock watch runs for the first and stops at the
 * second. The return read is the post-session bulk read's follower (see [AchievementSessionPoller.onReturned]).
 */
internal fun MimirGameSession.playingSessionOrNull(): RaPlayingSession? = when (phase) {
    MimirSessionPhase.RETURNING, MimirSessionPhase.ENDED, MimirSessionPhase.FAILED, MimirSessionPhase.STALE -> null
    MimirSessionPhase.PREPARING, MimirSessionPhase.DISPATCHED, MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK, MimirSessionPhase.RECOVERING,
    -> RaPlayingSession(operation = operation.value, gameId = gameId.value, startedAtEpochMillis = startedAtEpochMs)
}

/**
 * The "Achievement earned" watch. While a Mimir-launched game is running and RetroAchievements has been resolved for it, this reads that
 * ONE game's progress every [intervalMillis] and toasts each unlock that was earned after the session began and has not been toasted yet. When the session returns, after the
 * Activity page's own post-session read, it reads once more and toasts whatever the session earned that no earlier read caught.
 *
 *  - Every read goes through the client, so it waits its turn at the process-wide pacer; [held] says a Retry-After is in force and then nothing is asked at all.
 *  - Nothing is read without a connected, opted-in, unrefused key ([gateOpen]); the loop ends the moment the session leaves its running phases.
 *  - The toasted ids are kept per account ([loadToasted] / [saveToasted], the ledger's), so a restart in the middle of a session, or a second read of the same list, never
 *    toasts the same unlock twice. Several unlocks found by ONE read are ONE toast ("3 achievements earned"). An id is kept only once the toast was DISPLAYED ([post] answers
 *    true): with no toast host (a single-screen device) the unlock stays unshown and is offered again, and is still in Mimir's achievements view on return.
 *  - A line goes to the log for each read (`session-poll game=<RA id> outcome=...`) and for each toast (`toast new=<n> game=<RA id>`); never a key, a name or a URL.
 */
internal class AchievementSessionPoller(
    private val scope: CoroutineScope,
    /** The running game session, or null with none (see [playingSessionOrNull]). */
    private val sessions: Flow<RaPlayingSession?>,
    /** Connected, opted in and the key not refused: the only state in which anything may be asked. */
    private val gateOpen: () -> Boolean,
    /** The signed-in account's name (the toasted ids are kept under it), or null with none. */
    private val account: () -> String?,
    /** The RetroAchievements id the library game was resolved to, or null while it has not been. */
    private val raGameIdFor: (gameId: String) -> Int?,
    /** A Retry-After is in force: nothing may be asked until it has passed. */
    private val held: () -> Boolean,
    /** One game's progress, through the client's pacer. */
    private val read: suspend (raGameId: Int) -> RaProgressOutcome,
    /** Blocking file reads and writes of the toasted ids; they run on [workContext]. */
    private val loadToasted: (account: String) -> List<Int>,
    private val saveToasted: (account: String, ids: List<Int>) -> Unit,
    /**
     * Where a toast goes to be shown, and whether it WAS: true once a consumer displayed it, false when none did (no host is mounted,
     * as on a single-screen device, or the host never finished the showing). An unlock is marked toasted only on true (toast-delivery contract), so one nobody saw stays unshown and is offered again at the next read, and on return it is still in
     * Mimir's achievements view.
     */
    private val post: suspend (AchievementToast) -> Boolean,
    /** The account epoch a read begins under, and what is done with a read's answer (it is the game's one fresh read: the hub's card detail is seeded from it). */
    private val epoch: () -> Long = { 0L },
    private val onProgress: (RetroAchievementsProgress, epoch: Long) -> Unit = { _, _ -> },
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = ::logRetroAchievementsLine,
    private val intervalMillis: Long = RETRO_ACHIEVEMENTS_SESSION_POLL_MS,
    private val workContext: CoroutineContext = Dispatchers.IO,
) {
    private val toastLock = Mutex()
    private val seen = HashMap<String, LinkedHashSet<Int>>()
    private val lock = Any()

    /** The session being watched; null with none. Guarded by [lock]. */
    private var current: RaPlayingSession? = null

    /** The session that just stopped running, kept until its return read has been made. Guarded by [lock]. */
    private var ended: RaPlayingSession? = null

    /** The operation whose return read has been started, so the three signals of one return make one read. Guarded by [lock]. */
    private var returnedOperation: String? = null

    private var loop: Job? = null

    /** Start watching [sessions]. Called once, when the lane is built; the scope's end ends it. */
    fun start(): Job = scope.launch { sessions.collect { onSession(it) } }

    private fun onSession(session: RaPlayingSession?) {
        synchronized(lock) {
            if (session == current) return
            loop?.cancel()
            loop = null
            val previous = current
            if (previous != null && previous.operation != session?.operation) ended = previous
            if (session != null) ended = null
            current = session
            if (session != null) loop = scope.launch { watch(session) }
        }
    }

    private suspend fun watch(session: RaPlayingSession) {
        while (true) {
            pause(intervalMillis)
            poll(session)
        }
    }

    /**
     * One read of the session's game, and the toast it earns. True when the read was answered (or nothing could be asked, which no retry would change); false when it got no
     * answer for a reason that may pass (a Retry-After in force, offline, the game not resolved yet).
     */
    private suspend fun poll(session: RaPlayingSession): Boolean {
        val who = account()?.trim()?.takeIf { it.isNotEmpty() }
        if (who == null || !gateOpen()) return true
        val raGameId = raGameIdFor(session.gameId)
        if (raGameId == null) {
            log("session-poll game=unresolved outcome=unresolved")
            return false
        }
        if (held()) {
            log("session-poll game=$raGameId outcome=held")
            return false
        }
        // Nothing a read, a file or a toast throws may end the watch: a failed poll is a missed poll, tried again at the next mark. A cancellation is never swallowed.
        return try {
            val began = epoch()
            when (val outcome = withContext(workContext) { read(raGameId) }) {
                is RaProgressOutcome.Read -> {
                    log("session-poll game=$raGameId outcome=read")
                    onProgress(outcome.progress, began)
                    announce(who, session, outcome.progress)
                    true
                }
                RaProgressOutcome.CredentialsRejected -> {
                    log("session-poll game=$raGameId outcome=rejected")
                    true
                }
                RaProgressOutcome.Unavailable -> {
                    log("session-poll game=$raGameId outcome=unavailable")
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("session-poll game=$raGameId outcome=failed")
            false
        }
    }

    /**
     * Toast what [progress] shows earned after [session] began and not toasted before: one toast for the lot, newest first.
     *
     * The ids are marked toasted only AFTER [post] says a consumer displayed the toast (RC-56, ). They used to be marked first, so on a single-screen device, where nothing draws the toast, every unlock was spent
     * on a toast no one saw. The lock is held across the post so a second read cannot find the same unlocks and post them twice.
     */
    private suspend fun announce(who: String, session: RaPlayingSession, progress: RetroAchievementsProgress) {
        toastLock.withLock {
            val known = seen.getOrPut(who) {
                LinkedHashSet(withContext(workContext) { runCatching { loadToasted(who) }.getOrDefault(emptyList()) })
            }
            val found = progress.achievements
                .filter { it.unlocked && it.id !in known }
                .mapNotNull { achievement ->
                    activityEarnedAtMillis(achievement)?.takeIf { it > session.startedAtEpochMillis }?.let { achievement to it }
                }
                .sortedByDescending { it.second }
                .map { it.first }
            if (found.isEmpty()) return
            val newest = found.first()
            // NonCancellable from the post to the save: the post now waits for the host (up to the toast's time and a grace), and the watch can be
            // cancelled in that window (the game ended, the session went to null). Marking BEFORE posting could not be cut short; marking after must
            // not be either, or the host still runs the toast and the return read finds the same unlocks unmarked and toasts them a second time.
            // Bounded: the real sink gives up after ACHIEVEMENT_TOAST_MS + ACHIEVEMENT_TOAST_DISPLAY_GRACE_MS.
            withContext(NonCancellable) {
                val displayed = post(
                    AchievementToast(
                        key = 0L,
                        raGameId = progress.gameId,
                        gameTitle = progress.title,
                        count = found.size,
                        title = newest.title,
                        points = found.sumOf { it.points },
                        badgePath = activityBadgePath(newest),
                    ),
                )
                if (!displayed) {
                    log("toast unshown new=${found.size} game=${progress.gameId}")
                    return@withContext
                }
                found.forEach { known += it.id }
                val saved = known.toList()
                withContext(workContext) { runCatching { saveToasted(who, saved) } }
                log("toast new=${found.size} game=${progress.gameId}")
            }
        }
    }

    /**
     * The session a return that is signalled NOW belongs to: the one that just stopped running, else the one still counted as running. Taken at the moment of the signal and
     * handed to [onReturned] later, so a read that waits (the page's own, which can take a while) is never pointed at whatever game was started in the meantime.
     */
    fun sessionToRead(): RaPlayingSession? = synchronized(lock) { ended ?: current }

    /**
     * The [session] (see [sessionToRead]) came back: read its game once more, after the Activity page's own post-session read, and toast whatever the session earned that the
     * polls missed. The three signals one return gives (returning, ended, gone) make ONE read, and one that got no answer is tried again a minute later, twice at most.
     * Returns at once.
     */
    fun onReturned(session: RaPlayingSession?) {
        if (session == null) return
        synchronized(lock) {
            if (ended?.operation == session.operation) ended = null
            if (returnedOperation == session.operation) return
            returnedOperation = session.operation
        }
        scope.launch {
            var attempt = 0
            while (!poll(session) && attempt < RETRO_ACHIEVEMENTS_RETURN_READ_RETRIES) {
                attempt += 1
                pause(RETRO_ACHIEVEMENTS_RETURN_READ_RETRY_MS)
            }
        }
    }
}
