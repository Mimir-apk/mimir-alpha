package com.gruning.mimir.rewrite

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlin.math.max

internal data class GameNativeUsageEvent(
    val packageName: String,
    val className: String,
    val eventType: Int,
)

/**
 * Queries current foreground activity truth on the HUD beat. The shared monitor publishes resume edges for
 * reclaim evidence, so its replayed edge is not sufficient for a live FPS read; this reader advances its
 * own UsageStats cursor and clears its ledger on missing access, query failure or time reversal.
 */
internal class GameNativeForegroundReader private constructor(
    private val accessGranted: () -> Boolean,
    private val queryEvents: (Long, Long, (GameNativeUsageEvent) -> Unit) -> Boolean,
    private val nowEpochMs: () -> Long,
) {
    constructor(
        context: Context,
        accessGranted: () -> Boolean,
    ) : this(
        accessGranted,
        usageStatsQuery(context.applicationContext),
        System::currentTimeMillis,
    )

    internal constructor(
        nowEpochMs: () -> Long,
        accessGranted: () -> Boolean,
        queryEvents: (Long, Long, (GameNativeUsageEvent) -> Unit) -> Boolean,
    ) : this(accessGranted, queryEvents, nowEpochMs)

    private var cursorEpochMs = max(0L, nowEpochMs() - INITIAL_LOOKBACK_MS)
    private val foreground = GameNativeActivityLedger()

    @Synchronized
    fun currentPackage(atEpochMs: Long = nowEpochMs()): String? {
        if (!accessGranted()) {
            rebootstrap(atEpochMs)
            return null
        }
        if (atEpochMs < cursorEpochMs) {
            rebootstrap(atEpochMs)
            return null
        }
        if (atEpochMs - cursorEpochMs > INITIAL_LOOKBACK_MS) {
            rebootstrap(atEpochMs)
        }
        val success = runCatching {
            queryEvents(cursorEpochMs, atEpochMs) { event ->
                foreground.observe(event.packageName, event.className, event.eventType)
            }
        }.getOrDefault(false)
        if (!success) {
            rebootstrap(atEpochMs)
            return null
        }
        cursorEpochMs = atEpochMs
        return foreground.currentPackage()
    }

    private fun rebootstrap(atEpochMs: Long) {
        foreground.clear()
        cursorEpochMs = max(0L, atEpochMs - INITIAL_LOOKBACK_MS)
    }

    private companion object {
        private const val INITIAL_LOOKBACK_MS = 24L * 60L * 60L * 1_000L

        private fun usageStatsQuery(context: Context):
            (Long, Long, (GameNativeUsageEvent) -> Unit) -> Boolean = { from, to, emit ->
            val manager = context.getSystemService(UsageStatsManager::class.java)
            if (manager == null) {
                false
            } else {
                val events = runCatching { manager.queryEvents(from, to) }.getOrNull()
                if (events == null) {
                    false
                } else {
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        emit(
                            GameNativeUsageEvent(
                                packageName = event.packageName.orEmpty(),
                                className = event.className.orEmpty(),
                                eventType = event.eventType,
                            ),
                        )
                    }
                    true
                }
            }
        }
    }
}

/** UsageStats emits activity transitions independently; retain sibling activities until each one ends. */
internal class GameNativeActivityLedger {
    private data class ActivityKey(val packageName: String, val className: String)
    private val resumed = linkedMapOf<ActivityKey, Long>()
    private var sequence = 0L

    fun observe(packageName: String, className: String, eventType: Int) {
        if (packageName.isBlank()) return
        val key = ActivityKey(packageName, className)
        when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.MOVE_TO_FOREGROUND,
            -> {
                resumed.remove(key)
                resumed[key] = ++sequence
            }
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED,
            ACTIVITY_DESTROYED,
            -> {
                if (className.isBlank()) {
                    resumed.keys.removeAll { it.packageName == packageName }
                } else {
                    resumed.remove(key)
                }
            }
            UsageEvents.Event.MOVE_TO_BACKGROUND ->
                resumed.keys.removeAll { it.packageName == packageName }
        }
    }

    fun currentPackage(): String? = resumed.maxByOrNull { it.value }?.key?.packageName

    fun clear() {
        resumed.clear()
    }

    private companion object {
        private const val ACTIVITY_DESTROYED = 24
    }
}
