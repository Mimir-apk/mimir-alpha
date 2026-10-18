package com.gruning.mimir.rewrite

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lane mem (2026-10-14): the posture of a process start that follows a lowmemorykiller exit.
 *
 * Measured on the Thor (11.5 GB, PSI lmkd, `ro.lmk.kill_heaviest_task=true`): an Eden Switch load maps the 4 GB game
 * and drops free memory from 5.3 GB to 2 GB in four seconds; lmkd then works every adj bucket down to 100, heaviest
 * first, and Mimir is the heaviest visible process (its Bottom floor keeps it visible at adj 100 while the game is
 * TOP). The platform relaunches the display-4 secondary home within 50 ms of each kill, so the day's two Eden loads
 * (8 s and 5 s under pressure) killed Mimir ten times (6 + 4), and every relaunch bound the Tweaker daemon
 * (`TweakerBridge.initialize`, BIND_AUTO_CREATE), which started its guardian: two more processes spawned into the
 * crunch and killed within the second (daemon 5 + 1, guardian 4 + 2). The daemon's and the guardian's own sticky
 * restarts were by then backed off by the platform to minutes ("Scheduling restart of crashed service … in 1146142ms").
 *
 * The cure within this process: a start whose previous exit was [ApplicationExitInfo.REASON_LOW_MEMORY] within
 * [RecoveryWindowMs] is a *low-memory recovery start*. It attaches the bridge (every reader of its context is served)
 * but defers the bind by [TweakerBindDeferralMs], or until MainActivity is created, whichever comes first. The
 * crunches measured lasted 5-8 s, so the surviving process binds once, after the spike, and the daemon's
 * STARTUP_RECOVERY_RESTORE and the standing re-arm run exactly as they do today, minus the churn. If the deferral
 * lands inside a longer crunch the bind is the same one as before this change.
 *
 * Nothing here needs certification or evidence to run, and the privileged bridge's command surface is untouched.
 */
internal object MimirLowMemoryRecovery {
    private const val TAG = "MimirProcessRecovery"

    /** A low-memory exit older than this is history, not a crunch this start is part of. */
    internal const val RecoveryWindowMs = 60_000L

    /** The bind waits this long after a low-memory recovery start, unless MainActivity comes first. */
    internal const val TweakerBindDeferralMs = 12_000L

    /** What the previous process of this package died of, as the platform recorded it. */
    internal data class LastExit(val reason: Int, val timestampMs: Long, val rssKb: Long)

    /** [lowMemoryExitAgeMs] is set only when this start recovers from a recent lowmemorykiller exit. */
    internal data class Posture(val lowMemoryExitAgeMs: Long?) {
        val lean: Boolean get() = lowMemoryExitAgeMs != null
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { context, throwable ->
            Log.e(TAG, "deferred tweaker bridge start failed in $context", throwable)
        },
    )

    private val deferredStart = AtomicReference<Runnable?>(null)

    /** Reads the platform's exit record for this package; never throws, never blocks on anything but one binder call. */
    fun detect(context: Context, nowMs: Long = System.currentTimeMillis()): Posture {
        val lastExit = runCatching { readLastExit(context) }
            .onFailure { Log.w(TAG, "exit reasons unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()
        val posture = postureFor(lastExit, nowMs)
        if (posture.lean) {
            Log.i(
                TAG,
                "low-memory recovery start exitAgeMs=${posture.lowMemoryExitAgeMs} lastRssKb=${lastExit?.rssKb} " +
                    "tweakerBindDeferralMs=$TweakerBindDeferralMs",
            )
        }
        return posture
    }

    /** The pure decision: a low-memory exit no older than [RecoveryWindowMs] (and not from the future) is a crunch. */
    internal fun postureFor(lastExit: LastExit?, nowMs: Long): Posture {
        if (lastExit == null || lastExit.reason != ApplicationExitInfo.REASON_LOW_MEMORY) return Posture(null)
        val ageMs = nowMs - lastExit.timestampMs
        return if (ageMs in 0L..RecoveryWindowMs) Posture(ageMs) else Posture(null)
    }

    private fun readLastExit(context: Context): LastExit? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        return LastExit(reason = info.reason, timestampMs = info.timestamp, rssKb = info.rss)
    }

    /**
     * Starts the Tweaker bridge now, or, on a lean start, after [deferralMs] or at the first MainActivity. [start] is
     * the bridge's own `initialize` and [deferralMs] is [TweakerBindDeferralMs]; tests replace both.
     */
    fun startTweakerBridge(
        context: Context,
        posture: Posture,
        start: (Context) -> Unit = TweakerBridge::initialize,
        deferralMs: Long = TweakerBindDeferralMs,
    ) {
        val app = context.applicationContext
        if (!posture.lean) {
            start(app)
            return
        }
        deferredStart.set(Runnable { start(app) })
        scope.launch {
            delay(deferralMs)
            runDeferred(trigger = "deferral")
        }
    }

    /**
     * True while a lean start holds the bridge's `initialize` back. The bridge refuses to open telemetry observation
     * meanwhile (`TweakerBridge.openTelemetryObservation`, `telemetryObservationMayOpen`): the performance HUD's
     * service is START_STICKY and binds with BIND_AUTO_CREATE, so an observation opened inside the deferral would
     * respawn the daemon into the crunch and hold the reservation the deferred start then fails to take.
     */
    fun tweakerBridgeStartDeferred(): Boolean = deferredStart.get() != null

    /** The player is back: a deferred bridge start runs now. A no-op when nothing was deferred. */
    fun onMainActivityCreated() {
        runDeferred(trigger = "main-activity")
    }

    /** Tests share this process-wide object; each clears the deferral it armed. */
    internal fun resetForTest() {
        deferredStart.set(null)
    }

    /**
     * The bridge's `initialize` only ever ran on the main thread (Application.onCreate), and its lazily built session
     * and lifecycle are not proven for any other; the deferral hands it back to the main looper. Already on it (the
     * MainActivity trigger), it runs inline, exactly where it ran before this change.
     */
    private fun runDeferred(trigger: String) {
        val start = deferredStart.getAndSet(null) ?: return
        Log.i(TAG, "tweaker bridge start trigger=$trigger")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            start.run()
        } else {
            Handler(Looper.getMainLooper()).post(start)
        }
    }
}
