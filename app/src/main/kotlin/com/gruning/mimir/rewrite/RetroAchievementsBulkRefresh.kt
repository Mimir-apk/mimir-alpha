package com.gruning.mimir.rewrite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long after a read made on a return the one follow-up read waits. RetroAchievements rewrites its denormalised counts with a queued job, so a read made on the very
 * moment a game returns can still show the numbers from before the play; this one, a minute and a half later, catches them up.
 */
internal const val RETRO_ACHIEVEMENTS_FOLLOW_UP_READ_MS: Long = 90_000L

/** After a bulk read that brought nothing (offline, a page failed) the next return may try again this soon, instead of waiting out the whole five-minute window. */
internal const val RETRO_ACHIEVEMENTS_BULK_FAILURE_BACKOFF_MS: Long = 60_000L

/** Mimir must have been out of front this long, with no Mimir-launched game running, for coming back to count as a game played elsewhere. */
internal const val RETRO_ACHIEVEMENTS_OUTSIDE_PLAY_MIN_ABSENCE_MS: Long = 2L * 60L * 1000L

/**
 * What happens AFTER a game's play ended, so the Activity page learns of an unlock without the player opening anything.
 *
 * A return (a Mimir-launched game's session returning, or Mimir coming back to the front after a game played outside it) asks the loader for a refresh at once. The
 * loader's five-minute window can say no, and a read made at the very moment of return can still be ahead of RetroAchievements' own counts, so this owns the reads that
 * come LATER, as at most ONE pending read:
 *  - a read that went through ([ActivityBulkResult.LOADED]) is followed by one more [followUpMillis] later, which ignores the window (it is the read the window would
 *    otherwise swallow);
 *  - a return the window turned away is followed by ONE read at the window's end (the trailing read), so a short session is never lost;
 *  - a read that brought nothing is retried when its short backoff ends.
 * Every return counts: RetroAchievements' counts catch up about [followUpMillis] after a play, so a read is never due sooner than that after the LATEST return. A
 * trailing read is therefore due at the later of the window's end and that moment, and a second return while a read is pending PUSHES it later (it never drops the new
 * return): the one pending read serves both, and is a window-ignoring read when either of them asked for one. A read that has already begun is left running; a return
 * after that arms a pending read of its own. The pending read is single-shot (it never re-arms itself), is dropped on an account change ([cancel]) and when the
 * connection to RetroAchievements is no longer allowed, and never runs beside another bulk read: the loader's own lock turns a second one away or, for the follow-up,
 * queues it behind the one in flight.
 */
internal class ActivityBulkRefresher(
    private val scope: CoroutineScope,
    private val loader: ActivityBulkLoader,
    /** The same clock the loader's window is measured on. */
    private val clock: () -> Long,
    /** Connected, opted in and the key not refused: the only state in which anything may be asked. */
    private val gateOpen: () -> Boolean,
    private val followUpMillis: Long = RETRO_ACHIEVEMENTS_FOLLOW_UP_READ_MS,
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
) {
    /**
     * The pending read. [lagAtMillis] is when RetroAchievements' counts will have caught up with the latest return; [windowAtMillis] is when the loader's window next
     * allows a read (0 for a read that ignores the window, so it does not wait for it). A [forced] read ignores the window and is due at [lagAtMillis]; any other is due at
     * the later of the two.
     */
    private class Due(val lagAtMillis: Long, val windowAtMillis: Long, val forced: Boolean) {
        val atMillis: Long get() = if (forced) lagAtMillis else maxOf(lagAtMillis, windowAtMillis)

        fun mergedWith(other: Due): Due = Due(maxOf(lagAtMillis, other.lagAtMillis), maxOf(windowAtMillis, other.windowAtMillis), forced || other.forced)

        fun sameAs(other: Due): Boolean = lagAtMillis == other.lagAtMillis && windowAtMillis == other.windowAtMillis && forced == other.forced
    }

    private val lock = Any()
    private var due: Due? = null

    /** The job waiting for the pending read's time; null once it has begun reading (a return after that arms a new one beside it). */
    private var job: Job? = null

    /** The job whose read is in flight. Never cancelled by a later return; only by an account change. */
    private var reading: Job? = null

    /**
     * A game's play just ended: refresh now if the window allows, and arrange the read that follows. Returns at once. [afterRead] runs once that first read has been made
     * (whatever it brought): the unlock watch's return read, which follows the page's own.
     */
    fun onReturned(afterRead: () -> Unit = {}) {
        scope.launch {
            afterReturn()
            afterRead()
        }
    }

    private suspend fun afterReturn() {
        when (loader.refreshAfterReturn()) {
            // The read went through (or one is running that began before this return): the counts it saw may lag, so one more follows, past the window.
            ActivityBulkResult.LOADED, ActivityBulkResult.SUSPECT_EMPTY, ActivityBulkResult.BUSY ->
                arm(Due(lagAtMillis = clock() + followUpMillis, windowAtMillis = 0L, forced = true))
            // Turned away by the window: ONE read when the loader next allows one, and never before the counts have had [followUpMillis] to catch up with this return.
            ActivityBulkResult.THROTTLED ->
                loader.eligibleAtMillis()?.let { arm(Due(lagAtMillis = clock() + followUpMillis, windowAtMillis = it, forced = false)) }
            // The read this return made brought nothing: ONE read when its short backoff ends.
            ActivityBulkResult.UNAVAILABLE, ActivityBulkResult.PARTIAL ->
                loader.eligibleAtMillis()?.let { arm(Due(lagAtMillis = 0L, windowAtMillis = it, forced = false)) }
            ActivityBulkResult.NOT_SIGNED_IN, ActivityBulkResult.REJECTED, ActivityBulkResult.DROPPED -> Unit
        }
    }

    /** Folds [candidate] into the pending read (pushing it later when this return's read cannot come sooner), or makes it the pending read when none waits. */
    private fun arm(candidate: Due) {
        val starting: Job? = synchronized(lock) {
            val held = due?.takeIf { job != null }
            val next = held?.mergedWith(candidate) ?: candidate
            if (held != null && next.sameAs(held)) {
                null
            } else {
                due = next
                job?.cancel()
                scope.launch(start = CoroutineStart.LAZY) { runPending(next) }.also { job = it }
            }
        }
        starting?.start()
    }

    private suspend fun runPending(target: Due) {
        val me = currentCoroutineContext()[Job]
        try {
            val wait = target.atMillis - clock()
            if (wait > 0L) pause(wait)
            // From here the read is in flight: a return that arrives now arms a pending read of its own and cancels nothing.
            synchronized(lock) {
                if (job === me) {
                    job = null
                    due = null
                }
                reading = me
            }
            if (!gateOpen()) return
            // The follow-up ignores the window (and queues behind a read in flight); the trailing read goes through the window like any return, so one that someone
            // else's read has since closed again is simply dropped.
            if (target.forced) loader.load(waitForRunning = true) else loader.refreshAfterReturn()
        } finally {
            synchronized(lock) {
                if (job === me) {
                    job = null
                    due = null
                }
                if (reading === me) reading = null
            }
        }
    }

    /** An account change: the pending read, and the one in flight, were for the old account. */
    fun cancel() {
        val old = synchronized(lock) {
            val pending = listOfNotNull(job, reading)
            job = null
            reading = null
            due = null
            pending
        }
        old.forEach { it.cancel() }
    }
}

/**
 * Whether Mimir coming back to the front follows a game played OUTSIDE it (launched from the emulator's own icon, or from Recents): it was out of front at least
 * [minAbsenceMillis]. A quick pause (a permission dialog, the notification shade) is shorter than that. The caller adds the other half of the test (no Mimir-launched
 * game session is running, because that one's own return already refreshed).
 */
internal class OutsidePlayWatch(
    private val clock: () -> Long,
    private val minAbsenceMillis: Long = RETRO_ACHIEVEMENTS_OUTSIDE_PLAY_MIN_ABSENCE_MS,
) {
    private var leftAtMillis: Long? = null

    /** Mimir left the front. Only the first of a run of pauses counts. */
    @Synchronized
    fun onLeft() {
        if (leftAtMillis == null) leftAtMillis = clock()
    }

    /** Mimir is back in front: true when it had been away long enough. Consumes the departure either way. */
    @Synchronized
    fun onBack(): Boolean {
        val left = leftAtMillis ?: return false
        leftAtMillis = null
        return clock() - left >= minAbsenceMillis
    }
}
