package com.gruning.mimir.tweaker

import kotlin.math.roundToInt

/** Validated KGSL utilization readers, ordered from the preferred direct percentage to legacy data. */
internal object GpuUtilizationTelemetry {
    const val ROOT = "/sys/class/kgsl/kgsl-3d0"
    const val BUSY_PERCENTAGE = "$ROOT/gpu_busy_percentage"
    const val LOAD = "$ROOT/devfreq/gpu_load"
    const val BUSY_INTERVAL = "$ROOT/gpubusy"

    /** Every source, preferred first. The privileged allowlist admits exactly these three paths. */
    val SOURCES: List<String> = listOf(BUSY_PERCENTAGE, LOAD, BUSY_INTERVAL)

    /** One raw reply from [path] as a percentage, or null when it is absent or not that source's shape. */
    fun parseSource(path: String, raw: String?): Int? = when (path) {
        BUSY_PERCENTAGE, LOAD -> parsePercent(raw)
        BUSY_INTERVAL -> parseBusyInterval(raw)
        else -> null
    }

    /** Read at most one valid source for a beat. The lower-priority reads are fallbacks only. */
    fun read(
        readNode: (String) -> String?,
        onSelectedSource: (String) -> Unit = {},
    ): Int? {
        parsePercent(readSafely(readNode, BUSY_PERCENTAGE))?.let {
            onSelectedSource(BUSY_PERCENTAGE)
            return it
        }
        parsePercent(readSafely(readNode, LOAD))?.let {
            onSelectedSource(LOAD)
            return it
        }
        parseBusyInterval(readSafely(readNode, BUSY_INTERVAL))?.let {
            onSelectedSource(BUSY_INTERVAL)
            return it
        }
        return null
    }

    fun parsePercent(raw: String?): Int? {
        val token = raw?.trim()?.removeSuffix("%")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return token.toIntOrNull()?.takeIf { it in 0..100 }
    }

    fun parseBusyInterval(raw: String?): Int? {
        val values = raw?.trim()?.split(Regex("\\s+"))?.takeIf { it.size == 2 }
            ?.map { it.toLongOrNull() ?: return null }
            ?: return null
        val busy = values[0]
        val total = values[1]
        if (busy < 0L || total <= 0L || busy > total) return null
        return ((busy.toDouble() * 100.0) / total.toDouble())
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun readSafely(readNode: (String) -> String?, path: String): String? =
        runCatching { readNode(path) }.getOrNull()
}


/** Which door a GPU utilization number came through. */
internal enum class GpuUtilizationRoute { PRIVILEGED, DIRECT }

/**
 * One GPU utilization sample: [percent] is null when nothing could be read, never a zero standing in for it.
 * [source] and [route] name where a number came from, and are null with it.
 */
internal data class GpuUtilizationReading(
    val percent: Int?,
    val source: String? = null,
    val route: GpuUtilizationRoute? = null,
)

/**
 * Reads GPU busy through the helper this service already has, and does not keep knocking on a door that is shut.
 *
 * **Why it exists.** The KGSL utilization nodes are `vendor_sysfs_kgsl`, which this app's domain is denied. The
 * old reader tried the direct read first on EVERY tick, so each beat wrote an `avc: denied { read }` for every
 * source it tried (Nova, 2026-10-01 09:41: `name="gpu_busy_percentage"`). On the Nova the domain happens to be
 * permissive, so the read still succeeded and the number was real; on an enforcing build it fails, falls to the
 * helper's allowlist (which did not name these nodes) and the cell goes blank.
 *
 * **The route is decided by whether a helper exists, not by whether it just answered.** With a helper present
 * ([helperPresent]) only the privileged route is ever used: a miss is a miss, and the direct read is NOT the
 * fallback, because on a permissive domain it would succeed, stick, and bring the per-tick denial back. With no
 * helper at all the direct read is the only route there is.
 *
 * **Cost, and the bounds on it.** A privileged read is a root-helper `cat`. The last privileged value is reused for
 * [PRIVILEGED_REUSE_MS], so a 750 ms telemetry refresh does not fork a process each time; the first source that
 * answers is remembered, and when nothing answers [read] reports null and does not touch a node again for
 * [UNAVAILABLE_RETRY_MS]. The read's own deadline lives in [BoundedRead], which the caller wraps around the helper.
 *
 * **The readback.** Every value is range-checked by [GpuUtilizationTelemetry.parseSource] (0..100, or a coherent
 * busy/total pair) before it is a number; a malformed or out-of-range reply is a failure, not a zero.
 */
internal class GpuUtilizationReader(
    private val readPrivileged: (String) -> String?,
    private val readDirect: (String) -> String?,
    private val helperPresent: () -> Boolean,
    private val nowMs: () -> Long,
    private val retryUnavailableMs: Long = UNAVAILABLE_RETRY_MS,
    private val reuseMs: Long = PRIVILEGED_REUSE_MS,
) {
    private var selectedSource: String? = null
    private var selectedRoute: GpuUtilizationRoute? = null
    private var unavailableUntilMs = Long.MIN_VALUE
    private var lastPrivileged: GpuUtilizationReading? = null
    private var lastPrivilegedAtMs = Long.MIN_VALUE

    @Synchronized
    fun read(): GpuUtilizationReading {
        val now = nowMs()
        val source = selectedSource
        val route = selectedRoute
        if (source != null && route != null) {
            if (route == GpuUtilizationRoute.PRIVILEGED) {
                lastPrivileged?.takeIf { now - lastPrivilegedAtMs in 0 until reuseMs }?.let { return it }
            }
            sample(source, route)?.let { return remember(it, source, route, now) }
            // It answered before and does not now: rediscover once rather than report a stale source.
            selectedSource = null
            selectedRoute = null
            lastPrivileged = null
        }
        if (now < unavailableUntilMs) return GpuUtilizationReading(null)
        val candidateRoute = if (helperPresent()) GpuUtilizationRoute.PRIVILEGED else GpuUtilizationRoute.DIRECT
        for (candidate in GpuUtilizationTelemetry.SOURCES) {
            val percent = sample(candidate, candidateRoute) ?: continue
            selectedSource = candidate
            selectedRoute = candidateRoute
            return remember(percent, candidate, candidateRoute, now)
        }
        unavailableUntilMs = now + retryUnavailableMs
        return GpuUtilizationReading(null)
    }

    private fun remember(
        percent: Int,
        source: String,
        route: GpuUtilizationRoute,
        now: Long,
    ): GpuUtilizationReading = GpuUtilizationReading(percent, source, route).also { reading ->
        if (route == GpuUtilizationRoute.PRIVILEGED) {
            lastPrivileged = reading
            lastPrivilegedAtMs = now
        }
    }

    private fun sample(path: String, route: GpuUtilizationRoute): Int? {
        val raw = runCatching {
            when (route) {
                GpuUtilizationRoute.PRIVILEGED -> readPrivileged(path)
                GpuUtilizationRoute.DIRECT -> readDirect(path)
            }
        }.getOrNull()
        return GpuUtilizationTelemetry.parseSource(path, raw)
    }

    companion object {
        /** How long a read that found no source at all stays quiet before the next discovery burst. */
        const val UNAVAILABLE_RETRY_MS = 30_000L

        /** How long one privileged value answers for the next reads: the telemetry cache refreshes every 750 ms. */
        const val PRIVILEGED_REUSE_MS = 5_000L
    }
}

/**
 * A read with a deadline, for a transaction that has none of its own ([PServerClient.execute] blocks on Binder).
 *
 * One worker, one call in flight: a call that outlives [timeoutMs] returns null at the deadline and keeps the worker
 * busy until it really returns, and every read in that window is refused at once with null rather than queued, so a
 * wedged helper costs one thread and never a pile of them. Fail closed: a timeout, a refusal and a throw are all null,
 * never a value.
 *
 * [worker] is the one thread a call runs on. Production uses the default (a single daemon thread); it is a parameter so
 * a test can hand in an executor it controls and prove the wedge deterministically, without racing a wall clock.
 */
internal class BoundedRead(
    private val delegate: (String) -> String?,
    private val timeoutMs: Long = DEADLINE_MS,
    private val worker: java.util.concurrent.ExecutorService = newReadWorker(),
) {
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun read(path: String): String? {
        if (!busy.compareAndSet(false, true)) return null
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val future = try {
            worker.submit<String?> {
                started.set(true)
                try {
                    delegate(path)
                } finally {
                    busy.set(false)
                }
            }
        } catch (_: Throwable) {
            busy.set(false)
            return null
        }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            releaseIfNeverStarted(future, started)
            null
        } catch (_: Throwable) {
            releaseIfNeverStarted(future, started)
            null
        }
    }

    /** Past the deadline: stop waiting, and free the worker if the call never began (it would never release it). */
    private fun releaseIfNeverStarted(
        future: java.util.concurrent.Future<String?>,
        started: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        future.cancel(true)
        if (!started.get()) busy.set(false)
    }

    companion object {
        /** A `cat` through the helper takes milliseconds; this is the line past which the number is not worth waiting for. */
        const val DEADLINE_MS = 400L

        /** The production worker: one daemon thread, so a wedged helper costs exactly one thread. */
        fun newReadWorker(): java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "mimir-privileged-read").apply { isDaemon = true }
            }
    }
}
