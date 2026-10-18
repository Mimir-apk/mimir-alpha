package com.gruning.mimir.tweaker

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * LAB-only evidence sampler.
 *
 * This USED to piggyback on the same `ThermalTelemetryPort` cadence that produces
 * `FAN_MONITOR_SAMPLE` (see git history: `LabQualificationEvidenceCadence.onFanCadenceTick()`,
 * called from inside [MimirTweakerService]'s fan-control closure). That put a synchronous,
 * cross-process telemetry compose -- [LiveTelemetrySnapshotCache.sample] calling
 * `probe.telemetry(capabilities)`, which itself can invoke `frameTelemetry.read()` -> a
 * synchronous, UNTIMED `IBinder.transact()` to the vendor PServerBinder running
 * `dumpsys SurfaceFlinger | awk` server-side -- directly on the single fan-monitor thread that
 * must tick roughly once per second to keep `FAN_MONITOR_SAMPLE`, fan control and the Guardian
 * manual-fan heartbeat alive. Under sustained WLE load that call can block for a long time (there
 * is no timeout on the underlying Binder transaction), which starved the fan cadence down to a
 * couple of ticks in 40 s and tripped `Guardian heartbeat suppressed: manual fan controller is
 * stale` / `CONTINUITY_LOST_WITHOUT_GUARDIAN_REASON`.
 *
 * This sampler now owns a dedicated single-thread [ScheduledExecutorService] with its own ~1 s
 * cadence, so the fan-control closure never does any of this work. [start] and [stop] are wired
 * to [MimirTweakerService]'s `startMonitor()`/`stopMonitor()` -- the same functions that start
 * and stop the fan-monitor thread itself -- so this sampler keeps the "runs for the whole hold,
 * stops when the hold ends" lifecycle the old hook got for free, without sharing that hold's
 * thread. [MimirTweakerService.onDestroy] tears down the fan monitor via `stopMonitor()` too, so
 * no extra destroy-time wiring is needed here.
 *
 * [enabled] gates BEFORE any thread is created in [start], and is re-checked on every [tick] --
 * this sampler must be impossible to reach outside LAB, not merely unlikely, and must stop itself
 * promptly if LAB eligibility is lost mid-hold (e.g. the player flips dry-run on). [sampleTelemetry]
 * failures are swallowed so this sampler's own thread can never disturb the caller.
 *
 * [sampleTelemetry] is expected to be the shared [LiveTelemetrySnapshotCache.sample], the same
 * entry point real telemetry consumers (the overlay, `getTelemetry()`) use. That cache is
 * synchronized and freshness-windowed, so this sampler's tick and a concurrent consumer compose
 * at most once per window; [MimirTweakerService.maybeLogLabQualificationSample]'s own interval
 * guard (keyed off the composed sample's timestamp) then dedupes the emitted evidence line on top
 * of that. This sampler CAN contend with a Binder `getTelemetry()` caller on that cache's lock --
 * that was already true before this change, since both are just callers of the same cache -- but
 * it can never contend with fan control: the fan-control closure reads
 * `probe.supervisorTelemetry()` directly and never touches [LiveTelemetrySnapshotCache].
 */
internal class LabQualificationEvidenceSampler(
    private val enabled: () -> Boolean,
    private val sampleTelemetry: () -> Unit,
    private val cadenceMs: Long = DEFAULT_CADENCE_MS,
    private val joinTimeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
) {
    private val lock = Any()
    private var executor: ScheduledExecutorService? = null
    private var future: ScheduledFuture<*>? = null

    @Volatile
    private var workerThread: Thread? = null

    /**
     * Idempotent. A no-op -- no thread is even created -- when [enabled] is false at the moment
     * of the call, and a no-op when already running.
     */
    fun start() {
        synchronized(lock) {
            if (executor != null) return
            if (!enabled()) return
            val service = try {
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, THREAD_NAME).apply {
                        isDaemon = true
                        workerThread = this
                    }
                }
            } catch (_: RuntimeException) {
                return
            }
            executor = service
            future = try {
                service.scheduleWithFixedDelay(::tick, cadenceMs, cadenceMs, TimeUnit.MILLISECONDS)
            } catch (_: RuntimeException) {
                service.shutdownNow()
                executor = null
                null
            }
        }
    }

    /**
     * Idempotent; safe to call even when never started. Cancels and shuts the executor down;
     * joins with a bounded timeout UNLESS called from the sampler's own worker thread (its
     * self-stop path in [tick], when [enabled] is lost mid-hold). That case skips the join
     * deliberately, not because it would hang forever: `shutdownNow()` interrupts every worker
     * thread including this one, and a JDK `Condition`-backed `awaitTermination` throws
     * `InterruptedException` immediately when the calling thread is already interrupted on
     * entry, so the join would return fast either way. Skipping it here just avoids depending on
     * that interrupt-on-entry behavior for correctness and avoids a pointless
     * interrupt/throw/swallow round trip on the sampler's own thread.
     */
    fun stop() {
        val service: ScheduledExecutorService?
        val worker: Thread?
        synchronized(lock) {
            service = executor
            worker = workerThread
            executor = null
            future?.cancel(false)
            future = null
        }
        if (service == null) return
        service.shutdownNow()
        if (Thread.currentThread() !== worker) {
            runCatching { service.awaitTermination(joinTimeoutMs, TimeUnit.MILLISECONDS) }
        }
    }

    private fun tick() {
        if (!enabled()) {
            stop()
            return
        }
        runCatching(sampleTelemetry)
    }

    private companion object {
        const val THREAD_NAME = "mimir-tweaker-lab-evidence"
        const val DEFAULT_CADENCE_MS = 1_000L
        const val DEFAULT_JOIN_TIMEOUT_MS = 500L
    }
}
