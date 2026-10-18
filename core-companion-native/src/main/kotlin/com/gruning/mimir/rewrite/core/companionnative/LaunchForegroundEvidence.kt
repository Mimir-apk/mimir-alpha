package com.gruning.mimir.rewrite.core.companionnative

import kotlin.math.max

/**
 * Pure proof rule for UsageStats foreground-span evidence.
 *
 * This mirrors the shipping Java posture: a silent post-intent death can only be proven when the target
 * was observable through UsageStats on a single-screen launch shape, then either never foregrounded or
 * foregrounded for a bounded sub-threshold span. Unbounded spans are evidence that the app stayed open,
 * so they never produce a silent-death proof.
 */
object LaunchForegroundEvidence {
    const val SILENT_FOREGROUND_SPAN_THRESHOLD_MS: Long = 3_000L

    fun silentDeathProven(observation: LaunchForegroundObservation): Boolean {
        if (!observation.singleScreenLaunchShape) return false
        if (!observation.foregroundObservable) return false
        if (!observation.targetForegroundObserved) return true
        return observation.targetForegroundSpanBounded &&
            observation.targetForegroundSpanMs in 0 until SILENT_FOREGROUND_SPAN_THRESHOLD_MS
    }

    fun silentDeathReason(observation: LaunchForegroundObservation): String =
        when {
            !silentDeathProven(observation) -> ""
            !observation.targetForegroundObserved -> "launch_never_foregrounded"
            else -> "launch_foreground_span_under_threshold"
        }
}

internal data class LaunchForegroundTimeline(
    val firstForegroundAtMs: Long = 0L,
    val lastForegroundAtMs: Long = 0L,
    val lastBackgroundAtMs: Long = 0L,
    val lastTargetEventType: String = "",
    private val lastTargetEventAtMs: Long = 0L,
) {
    val targetForegroundObserved: Boolean
        get() = firstForegroundAtMs > 0L

    val targetForegroundSpanBounded: Boolean
        get() = targetForegroundObserved && lastBackgroundAtMs > lastForegroundAtMs

    fun recordForeground(timestampMs: Long): LaunchForegroundTimeline =
        copy(
            firstForegroundAtMs = if (firstForegroundAtMs == 0L || timestampMs < firstForegroundAtMs) {
                timestampMs
            } else {
                firstForegroundAtMs
            },
            lastForegroundAtMs = max(lastForegroundAtMs, timestampMs),
            lastTargetEventType = latestEventType(timestampMs, "FOREGROUND"),
            lastTargetEventAtMs = max(lastTargetEventAtMs, timestampMs),
        )

    fun recordBackground(timestampMs: Long): LaunchForegroundTimeline =
        copy(
            lastBackgroundAtMs = max(lastBackgroundAtMs, timestampMs),
            lastTargetEventType = latestEventType(timestampMs, "BACKGROUND"),
            lastTargetEventAtMs = max(lastTargetEventAtMs, timestampMs),
        )

    fun targetForegroundSpanMs(returnedAtMs: Long): Long =
        when {
            !targetForegroundObserved -> 0L
            targetForegroundSpanBounded -> lastBackgroundAtMs - firstForegroundAtMs
            else -> max(0L, max(returnedAtMs, lastForegroundAtMs) - firstForegroundAtMs)
        }

    private fun latestEventType(timestampMs: Long, eventType: String): String =
        if (timestampMs >= lastTargetEventAtMs) eventType else lastTargetEventType
}
