package com.gruning.mimir.rewrite.core.metadata.online

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The most key-bearing requests that may START in any one [RA_REQUEST_WINDOW_MS]: RetroAchievements asks clients to be gentle, and a burst of card opens must not be one. */
internal const val RA_MAX_REQUESTS_PER_WINDOW: Int = 4

/** The rolling window [RA_MAX_REQUESTS_PER_WINDOW] counts over. */
internal const val RA_REQUEST_WINDOW_MS: Long = 1_000L

/**
 * A rolling window over request STARTS: at most [maxStarts] in any [windowMillis]. [admit] returns at once while there is room and otherwise waits (on [pause]) until
 * the oldest start leaves the window, so a burst is spread out rather than refused: every request is still made, only not all at once. Callers are served in order (one
 * waiter at a time holds the gate).
 *
 * It keeps its own monotonic clock ([nowMillis]) rather than the source's: the source's clock is the wall clock that Retry-After is measured on, and a test moves it freely.
 */
internal class RetroAchievementsRequestPacer(
    private val maxStarts: Int = RA_MAX_REQUESTS_PER_WINDOW,
    private val windowMillis: Long = RA_REQUEST_WINDOW_MS,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
) {
    private val gate = Mutex()
    private val starts = ArrayDeque<Long>()

    private val holdLock = Any()
    private var holdUntilMillis = Long.MIN_VALUE

    /**
     * True while a Retry-After the host asked for is in force: nothing key-bearing may be sent, from ANY client, because the limit is the host's. It is held here, on the
     * pacer every request already goes through, and not on a client, so a second client (or a poll) can never walk past a wait that another one was handed.
     */
    fun holding(): Boolean = synchronized(holdLock) { nowMillis() < holdUntilMillis }

    /** The host asked for a wait of [millis]: no request starts before it has passed. A second ask only ever extends it. */
    fun holdOff(millis: Long) {
        synchronized(holdLock) {
            val until = nowMillis() + millis
            if (until > holdUntilMillis) holdUntilMillis = until
        }
    }

    /** Returns when this request may start (and counts it as started). Cancellable while it waits. */
    suspend fun admit() {
        if (maxStarts == Int.MAX_VALUE) return
        gate.withLock {
            while (true) {
                val now = nowMillis()
                while (starts.isNotEmpty() && now - starts.first() >= windowMillis) starts.removeFirst()
                if (starts.size < maxStarts) {
                    starts.addLast(now)
                    return
                }
                pause((windowMillis - (now - starts.first())).coerceAtLeast(1L))
            }
        }
    }

    companion object {
        /** The process's one pacer: every client the app builds shares it, because the limit is the host's, not a client's. */
        val process = RetroAchievementsRequestPacer()

        /** A pacer that spaces nothing and only holds a Retry-After, on [nowMillis]: what a source built without the process's one (a test's) uses. */
        fun holdOnly(nowMillis: () -> Long): RetroAchievementsRequestPacer = RetroAchievementsRequestPacer(maxStarts = Int.MAX_VALUE, nowMillis = nowMillis)
    }
}
