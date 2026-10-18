package com.gruning.mimir.tweaker

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Reads Usage Access foreground truth fail-closed.
 * RESUMED establishes the current package; PAUSED/STOPPED/DESTROYED/MOVE_TO_BACKGROUND of that
 * package clears it. Missing access, query failure, time reversal, or indeterminate truth never
 * invents a foreground package.
 */
internal class ForegroundWorkloadAttestor(context: Context) {
    private val appContext = context.applicationContext
    private val usageStats = appContext.getSystemService(UsageStatsManager::class.java)
    private var cursorEpochMs = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
    private val foreground = ForegroundActivityLedger()

    @Synchronized
    fun currentPackage(nowEpochMs: Long = System.currentTimeMillis()): String? {
        return when (observeInternal(targetPackage = null, nowEpochMs = nowEpochMs)) {
            is ObserveOutcome.Foreground -> foreground.currentPackage()
            else -> null
        }
    }

    @Synchronized
    fun observe(targetPackage: String, nowEpochMs: Long = System.currentTimeMillis()): WorkloadTruth {
        return when (val outcome = observeInternal(targetPackage, nowEpochMs)) {
            ObserveOutcome.AccessDenied -> WorkloadTruth.ACCESS_DENIED
            ObserveOutcome.QueryFailed -> WorkloadTruth.QUERY_FAILED
            is ObserveOutcome.Resolved -> outcome.truth
            is ObserveOutcome.Foreground -> {
                WorkloadTruth.INDETERMINATE
            }
        }
    }

    private fun observeInternal(targetPackage: String?, nowEpochMs: Long): ObserveOutcome {
        if (UsageAccessProbe.granted(appContext) != true) {
            foreground.clear()
            return ObserveOutcome.AccessDenied
        }
        if (nowEpochMs < cursorEpochMs) {
            foreground.clear()
            return ObserveOutcome.QueryFailed
        }
        val manager = usageStats ?: run {
            foreground.clear()
            return ObserveOutcome.AccessDenied
        }
        val events = runCatching { manager.queryEvents(cursorEpochMs, nowEpochMs) }
            .getOrElse {
                foreground.clear()
                return ObserveOutcome.QueryFailed
            } ?: run {
            foreground.clear()
            return ObserveOutcome.QueryFailed
        }

        var clearedTargetByPauseStop = false
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName.orEmpty()
            val className = event.className.orEmpty()
            // UsageEvents does not expose an activity-instance id in the public SDK. Class identity
            // is still sufficient to preserve measured emulator MainActivity -> EmulationActivity.
            val instanceId = 0
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                -> {
                    foreground.resume(packageName, className, instanceId)
                    if (targetPackage != null && packageName == targetPackage) {
                        clearedTargetByPauseStop = false
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                ACTIVITY_DESTROYED_EVENT,
                -> {
                    foreground.end(packageName, className, instanceId)
                    if (targetPackage != null && packageName == targetPackage) {
                        clearedTargetByPauseStop = !foreground.containsPackage(targetPackage)
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    foreground.background(packageName)
                    if (targetPackage != null && packageName == targetPackage) {
                        clearedTargetByPauseStop = true
                    }
                }
            }
        }
        cursorEpochMs = nowEpochMs

        if (targetPackage == null) {
            return ObserveOutcome.Foreground(foreground.currentPackage())
        }
        val resumedPackage = foreground.currentPackage()
        val truth = when {
            resumedPackage == targetPackage -> WorkloadTruth.TARGET_FOREGROUND
            clearedTargetByPauseStop -> WorkloadTruth.TARGET_PAUSED_OR_STOPPED
            resumedPackage != null -> WorkloadTruth.OTHER_FOREGROUND
            else -> WorkloadTruth.INDETERMINATE
        }
        return ObserveOutcome.Resolved(truth)
    }

    private sealed class ObserveOutcome {
        data object AccessDenied : ObserveOutcome()
        data object QueryFailed : ObserveOutcome()
        data class Foreground(val packageName: String?) : ObserveOutcome()
        data class Resolved(val truth: WorkloadTruth) : ObserveOutcome()
    }

    companion object {
        // The overlay can be enabled over a game that has already been foreground for minutes or hours.
        // Replay one bounded UsageEvents day on the first read, then retain the existing incremental cursor.
        private const val INITIAL_LOOKBACK_MS = 24L * 60L * 60L * 1_000L
        /** UsageEvents.Event.ACTIVITY_DESTROYED — int literal for host stub compatibility. */
        private const val ACTIVITY_DESTROYED_EVENT = 24
    }
}

/**
 * Package foreground truth must survive one Activity stopping after a sibling Activity resumed.
 * UsageEvents reports those transitions independently, so package-only state loses the live game.
 */
internal class ForegroundActivityLedger {
    private data class ActivityKey(
        val packageName: String,
        val className: String,
        val instanceId: Int,
    )

    private val resumed = linkedMapOf<ActivityKey, Long>()
    private var sequence = 0L

    fun resume(packageName: String, className: String, instanceId: Int) {
        if (packageName.isBlank()) return
        val key = ActivityKey(packageName, className, instanceId)
        if (className.isNotBlank() || instanceId != 0) {
            resumed.keys.removeAll { it.packageName == packageName && it.className.isBlank() && it.instanceId == 0 }
        }
        resumed.remove(key)
        resumed[key] = ++sequence
    }

    fun end(packageName: String, className: String, instanceId: Int) {
        if (packageName.isBlank()) return
        if (className.isBlank() && instanceId == 0) {
            background(packageName)
        } else {
            resumed.remove(ActivityKey(packageName, className, instanceId))
        }
    }

    fun background(packageName: String) {
        resumed.keys.removeAll { it.packageName == packageName }
    }

    fun containsPackage(packageName: String): Boolean = resumed.keys.any { it.packageName == packageName }

    fun currentPackage(): String? = resumed.maxByOrNull { it.value }?.key?.packageName

    fun clear() {
        resumed.clear()
    }
}
