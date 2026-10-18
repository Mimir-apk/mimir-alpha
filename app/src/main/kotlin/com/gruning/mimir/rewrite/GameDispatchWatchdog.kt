package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchTargetReapResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Retains the former 2,000 ms remote budget plus 500 ms caller drain for watchdog recovery. */
internal const val LaunchTargetStopClientDeadlineMs = 2_500L

/** Must outlive the gateway's remote budget + drain so watchdog cleanup cannot outrun a force-stop. */
internal const val GameDispatchRecoveryDeadlineMs = LaunchTargetStopClientDeadlineMs + 500L

internal data class GameDispatchWatchdogResult(
    val reap: LaunchTargetReapResult?,
    val cleanupApplied: Boolean,
)

/** Both values must come from Android elapsedRealtime; never mix this deadline with nanoTime. */
internal fun remainingGameDispatchWatchdogDelayMs(
    dispatchedAtElapsedMs: Long?,
    nowElapsedMs: Long,
    graceMs: Long,
): Long {
    val boundedGraceMs = graceMs.coerceAtLeast(0L)
    val elapsedAgeMs = (nowElapsedMs - (dispatchedAtElapsedMs ?: nowElapsedMs)).coerceAtLeast(0L)
    return (boundedGraceMs - elapsedAgeMs.coerceAtMost(boundedGraceMs)).coerceAtLeast(0L)
}

/**
 * Integrated resumed-host watchdog transaction. Exact operation/package/focus are checked before the
 * semantic session atomically claims RECOVERING. That claim precedes every exact-package side effect, so
 * a returning old operation cannot race a newer launch of the same emulator. Once claimed, recovery and
 * cleanup are non-cancellable; RECOVERING must never strand and hold launch admission indefinitely.
 */
internal suspend fun runGameDispatchWatchdog(
    delayMs: Long,
    recoveryDeadlineMs: Long = GameDispatchRecoveryDeadlineMs,
    expectedOperation: MimirGameSessionOperation,
    expectedPackage: String,
    currentSession: () -> MimirGameSession?,
    primaryHostOwnsWindow: () -> Boolean,
    recoverTarget: suspend () -> LaunchTargetReapResult,
    expireSession: () -> MimirGameDispatchCleanup?,
    applyCleanup: suspend (MimirGameDispatchCleanup) -> Unit,
    completeCleanup: (MimirGameDispatchCleanup) -> Unit,
): GameDispatchWatchdogResult {
    delay(delayMs.coerceAtLeast(0L))
    val beforeRecovery = currentSession()
    if (beforeRecovery?.operation != expectedOperation ||
        beforeRecovery.targetPackageName != expectedPackage ||
        beforeRecovery.phase != MimirSessionPhase.DISPATCHED ||
        !primaryHostOwnsWindow()
    ) {
        return GameDispatchWatchdogResult(reap = null, cleanupApplied = false)
    }

    val cleanup = expireSession()
        ?: return GameDispatchWatchdogResult(reap = null, cleanupApplied = false)
    return withContext(NonCancellable) {
        val reap = withTimeoutOrNull(recoveryDeadlineMs.coerceAtLeast(1L)) {
            try {
                recoverTarget()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LaunchTargetReapResult.FAILED
            }
        } ?: LaunchTargetReapResult.FAILED
        var cleanupApplied = false
        try {
            applyCleanup(cleanup)
            cleanupApplied = true
        } catch (_: Exception) {
            cleanupApplied = false
        } finally {
            completeCleanup(cleanup)
        }
        GameDispatchWatchdogResult(reap = reap, cleanupApplied = cleanupApplied)
    }
}
