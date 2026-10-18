package com.gruning.mimir.rewrite.feature.library

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout

/** How long any wait built on [awaitWithin] may take before it fails with its own message. */
internal const val LibraryTestAwaitMs: Long = 5_000L

/**
 * Runs [block] for at most [LibraryTestAwaitMs] and fails naming [what] when the time runs out.
 *
 * Every wait built on this suspends on the thing it waits for: a flow, a deferred, a channel. None polls a clock, so
 * a pass never depends on how fast the machine is, and a hang fails at its own line.
 */
internal suspend fun <T> awaitWithin(what: String, block: suspend () -> T): T =
    try {
        withTimeout(LibraryTestAwaitMs) { block() }
    } catch (_: TimeoutCancellationException) {
        throw AssertionError("$what did not happen within $LibraryTestAwaitMs ms")
    }

/**
 * The session events a launch publishes, recorded for a host test that reads them while the launch is still running.
 *
 * [LibraryViewModel.launch] publishes [LibraryGameSessionEvent.Preparing] on the caller's thread before its dispatcher
 * reaches the port, and [LibraryGameSessionEvent.Settled] or [LibraryGameSessionEvent.Failed] from that dispatcher
 * once the port returns. The log is therefore written from two threads, so every read is an immutable snapshot that is
 * safe to iterate while the dispatcher records.
 *
 * Round 5e: in the 2026-09-13T00:23:11Z integration gates, `LibraryGameLaunchOperationTest` threw
 * ConcurrentModificationException. It iterated a `Collections.synchronizedList` without the list's lock, which the JDK
 * requires for iteration, while the launch dispatcher added Settled.
 */
internal class LibrarySessionEventLog {
    private val recorded = MutableStateFlow<List<LibraryGameSessionEvent>>(emptyList())

    /** Records every event and admits it, as `LibraryGameSessionObserver(events::add)` did. */
    val observer = LibraryGameSessionObserver { event ->
        recorded.update { events -> events + event }
        true
    }

    /** An immutable snapshot of every event recorded so far, in order. */
    fun events(): List<LibraryGameSessionEvent> = recorded.value

    /** Suspends until the recorded events satisfy [condition], bounded like [awaitWithin]. */
    suspend fun awaitEvents(
        what: String,
        condition: (List<LibraryGameSessionEvent>) -> Boolean,
    ): List<LibraryGameSessionEvent> =
        try {
            withTimeout(LibraryTestAwaitMs) { recorded.first { events -> condition(events) } }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError("$what did not happen within $LibraryTestAwaitMs ms; recorded: ${recorded.value}")
        }

    /**
     * Suspends until every launch this log admitted has come back from the port. That is the point where a launch
     * has been fully processed.
     *
     * The point is deterministic, from source. [LibraryViewModel.launch] is the only caller of the port's `launch`. It
     * publishes Preparing before its dispatcher calls the port, and Settled or Failed after the port returns. So once
     * every Preparing here has its Settled or Failed, the port has seen every launch the recorded presses caused.
     */
    suspend fun awaitAdmittedLaunchesSettled(): List<LibraryGameSessionEvent> =
        awaitEvents("every admitted launch settling") { events ->
            val finished = events
                .filter { it is LibraryGameSessionEvent.Settled || it is LibraryGameSessionEvent.Failed }
                .mapTo(HashSet()) { it.operation }
            events.filterIsInstance<LibraryGameSessionEvent.Preparing>().all { it.operation in finished }
        }
}
