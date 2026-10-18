package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Always-compiled seam for the lease a session is actually armed under.
 *
 * Sibling of [GuardianRestoreObservability], and it exists for the same reason that file does: a
 * safety property nobody can read from a shipping device is a safety property nobody can check. The
 * 2026-08-21 Nova pass concluded *"the 360s hard lease is not on this path"* only by tracing four
 * source files across two Gradle builds, because the daemon logged the profile it applied and never
 * the terms it applied it under. One line at adoption makes the lease a device-observable fact
 * instead of a source-reading exercise.
 *
 * retired the certified/uncertified distinction from session lifetime: a
 * SYSTEM session now holds the same hard deadline regardless of how it was admitted, so the
 * `uncertified=`/`attended=` tokens this line used to carry -- which existed only to positively
 * attest the now-deleted attended lease -- are gone. What survives is the duty that motivated this
 * file in the first place: the lease TERMS an adopted session is actually held under must be a
 * device-observable fact, not a source-reading exercise.
 *
 * Deliberately shares nothing with the restore family's `restore reason=` token: this is not a
 * restore, and a grep for one must not sweep it up. It carries the scope, both deadlines as
 * **relative** durations (an absolute `elapsedRealtime` value is unreadable against a log
 * timestamp), and the internal identifiers — never a package name, path, or any other private-library
 * content, same disclosure rule as its sibling. Adoption is rare; no throttling.
 *
 * [lastRecorded] is the test seam, set before the guarded [Log] call, for the same reason its
 * sibling's is: a host test asserts the value reached this point without depending on `Log`.
 */
internal object GuardianSessionLeaseObservability {
    private const val TAG = "MimirTweakerService"

    @Volatile
    var lastRecorded: String? = null
        private set

    fun onLeaseArmed(
        scope: String,
        generation: Long,
        transactionId: String,
        leaseDeadlineElapsedRealtimeMs: Long,
        hardLeaseDeadlineElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
    ) {
        lastRecorded = describe(
            scope,
            generation,
            transactionId,
            leaseDeadlineElapsedRealtimeMs,
            hardLeaseDeadlineElapsedRealtimeMs,
            nowElapsedRealtimeMs,
        )
        runCatching { Log.i(TAG, lastRecorded!!) }
    }

    /** Pure formatting, unit-testable without touching [Log]. */
    fun describe(
        scope: String,
        generation: Long,
        transactionId: String,
        leaseDeadlineElapsedRealtimeMs: Long,
        hardLeaseDeadlineElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
    ): String {
        return "SESSION lease scope=$scope " +
            "leaseMs=${leaseDeadlineElapsedRealtimeMs - nowElapsedRealtimeMs} " +
            "hardLeaseMs=${hardLeaseDeadlineElapsedRealtimeMs - nowElapsedRealtimeMs} " +
            "generation=$generation transactionId=$transactionId"
    }
}
