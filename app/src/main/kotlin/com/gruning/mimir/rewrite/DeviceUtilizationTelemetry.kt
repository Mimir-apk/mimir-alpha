package com.gruning.mimir.rewrite

import kotlin.math.roundToInt

/** One validated whole-device utilization sample, stamped with the instant its counters were read. */
data class DeviceUtilizationSample(
    val sampledAtElapsedRealtimeMs: Long,
    val cpuBusyPercent: Int?,
    val gpuBusyPercent: Int?,
)

private data class CpuCounters(
    val raw: LongArray,
    val total: Long,
    val idle: Long,
)

/**
 * Reads whole-device CPU and GPU utilization without starting a producer of its own.
 *
 * CPU is a delta over the first eight aggregate `/proc/stat` counters. The optional `guest` and
 * `guest_nice` fields are validated but not subtracted: Linux already includes them in `user` and
 * `nice`, and the first eight counters therefore already account for them exactly once.
 * GPU sources are tried in fixed order and the first valid source wins.
 */
internal class DeviceUtilizationSampler(
    private val readProcStat: () -> String?,
    private val readGpu: (String) -> String?,
    private val elapsedRealtimeMs: () -> Long,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
) {
    private var previousCpu: CpuCounters? = null
    private var previousAtMs: Long? = null

    fun sample(): DeviceUtilizationSample {
        val now = elapsedRealtimeMs()
        val cpu = sampleCpu(now)
        val gpu = readGpuBusyPercent(readGpu)
        return DeviceUtilizationSample(
            sampledAtElapsedRealtimeMs = now,
            cpuBusyPercent = cpu,
            gpuBusyPercent = gpu,
        )
    }

    private fun sampleCpu(nowMs: Long): Int? {
        val raw = runCatching { readProcStat() }.getOrNull()
        val current = parseCpuCounters(raw) ?: run {
            previousCpu = null
            previousAtMs = null
            return null
        }
        val prior = previousCpu
        val priorAtMs = previousAtMs
        previousCpu = current
        previousAtMs = nowMs
        if (prior == null || priorAtMs == null) return null

        // A single counter going backwards is a reset or a malformed source. Reseed from the
        // current complete sample so a later healthy interval can recover without inheriting a
        // negative delta from the old epoch.
        if (current.raw.indices.any { current.raw[it] < prior.raw[it] }) return null

        val intervalMs = runCatching { Math.subtractExact(nowMs, priorAtMs) }.getOrNull()
        if (intervalMs == null || intervalMs <= 0L || intervalMs > maxIntervalMs) return null
        val totalDelta = runCatching { Math.subtractExact(current.total, prior.total) }.getOrNull()
        val idleDelta = runCatching { Math.subtractExact(current.idle, prior.idle) }.getOrNull()
        if (totalDelta == null || idleDelta == null || totalDelta <= 0L || idleDelta !in 0L..totalDelta) {
            return null
        }
        val busyDelta = totalDelta - idleDelta
        return ((busyDelta.toDouble() * 100.0) / totalDelta.toDouble())
            .roundToInt()
            .coerceIn(0, 100)
    }

    companion object {
        internal const val MAX_INTERVAL_MS = 5_000L
    }
}

/** Parse the aggregate CPU line and retain only the counters needed for a delta. */
internal fun parseDeviceCpuCounters(raw: String?): Pair<Long, Long>? =
    parseCpuCounters(raw)?.let { it.total to it.idle }

private fun parseCpuCounters(raw: String?): CpuCounters? {
    val columns = raw?.trim()?.split(Regex("\\s+")) ?: return null
    if (columns.firstOrNull() != "cpu" || columns.size < 9) return null
    val counters = columns.drop(1).take(8).map { it.toLongOrNull() ?: return null }
    if (counters.any { it < 0L }) return null
    val guest = columns.getOrNull(9)?.let { it.toLongOrNull() ?: return null }
    val guestNice = columns.getOrNull(10)?.let { it.toLongOrNull() ?: return null }
    if ((guest != null && guest < 0L) || (guestNice != null && guestNice < 0L)) return null
    val rawTotal = counters.foldChecked() ?: return null
    val idle = runCatching { Math.addExact(counters[3], counters[4]) }.getOrNull() ?: return null
    val total = rawTotal
    if (total <= 0L || idle !in 0L..total) return null
    return CpuCounters(raw = counters.toLongArray(), total = total, idle = idle)
}

private fun List<Long>.foldChecked(): Long? = fold(0L) { sum, value ->
    runCatching { Math.addExact(sum, value) }.getOrNull() ?: return null
}

/** Return a bounded GPU percentage from the first valid fixed node. */
internal fun readGpuBusyPercent(
    read: (String) -> String?,
    onSelectedSource: (String) -> Unit = {},
): Int? {
    parseGpuPercent(readSafely(read, DeviceGpuPaths.BUSY_PERCENTAGE))?.let {
        onSelectedSource(DeviceGpuPaths.BUSY_PERCENTAGE)
        return it
    }
    parseGpuPercent(readSafely(read, DeviceGpuPaths.LOAD))?.let {
        onSelectedSource(DeviceGpuPaths.LOAD)
        return it
    }
    parseGpuBusyInterval(readSafely(read, DeviceGpuPaths.BUSY_INTERVAL))?.let {
        onSelectedSource(DeviceGpuPaths.BUSY_INTERVAL)
        return it
    }
    return null
}

internal fun parseGpuPercent(raw: String?): Int? {
    val token = raw?.trim()?.removeSuffix("%")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return token.toIntOrNull()?.takeIf { it in 0..100 }
}

internal fun parseGpuBusyInterval(raw: String?): Int? {
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

private fun readSafely(read: (String) -> String?, path: String): String? =
    runCatching { read(path) }.getOrNull()

internal object DeviceGpuPaths {
    const val ROOT = "/sys/class/kgsl/kgsl-3d0"
    const val BUSY_PERCENTAGE = "$ROOT/gpu_busy_percentage"
    const val LOAD = "$ROOT/devfreq/gpu_load"
    const val BUSY_INTERVAL = "$ROOT/gpubusy"
}

/** One GPU busy sample from the app's own kernel read, with the source that produced it. */
internal data class DirectGpuBusy(val percent: Int?, val source: String? = null)

/**
 * Mimir's own unprivileged read of GPU busy, which the KGSL nodes' SELinux label denies on an enforcing build and audits on
 * every call even where a permissive domain lets it through (Nova, before: 124 `avc: denied ... gpu_busy_percentage` in one
 * walk, 33 from Mimir itself).
 *
 * **It does not read at all while the Tweaker can be reached.** When the pair can bind, the Tweaker's telemetry carries
 * GPU busy through its privileged route, and this read only runs in the few seconds the overlay's Tweaker observation is
 * closed to it (a launch is preparing); a blank GPU cell for those seconds is honest, a denied read is not free.
 * **Where no Tweaker can bind** (the Thor) it is the only source, so it stays, with two limits: a value is reused for
 * [REUSE_MS], and when no source answers it is not asked again for [QUIET_MS]. Null throughout, never a zero.
 */
internal class DirectGpuBusyReader(
    private val tweakerReachable: () -> Boolean,
    private val readNode: (String) -> String?,
    private val nowMs: () -> Long,
) {
    private var last: DirectGpuBusy? = null
    private var lastAtMs = Long.MIN_VALUE
    private var quietUntilMs = Long.MIN_VALUE

    @Synchronized
    fun read(): DirectGpuBusy {
        if (tweakerReachable()) return DirectGpuBusy(null)
        val now = nowMs()
        last?.takeIf { now - lastAtMs in 0 until REUSE_MS }?.let { return it }
        if (now < quietUntilMs) return DirectGpuBusy(null)
        var source: String? = null
        val percent = readGpuBusyPercent(readNode) { source = it }
        if (percent == null) {
            quietUntilMs = now + QUIET_MS
            last = null
            return DirectGpuBusy(null)
        }
        return DirectGpuBusy(percent, source).also {
            last = it
            lastAtMs = now
        }
    }

    companion object {
        const val REUSE_MS = 5_000L
        const val QUIET_MS = 30_000L
    }
}
