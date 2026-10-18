package com.gruning.mimir.tweaker

import android.os.Bundle
import android.os.SystemClock
import java.io.File
import kotlin.math.roundToInt

internal data class CpuTimeSample(
    val counters: LongArray,
    val total: Long,
    val idle: Long,
)

internal fun parseCpuTimeSample(raw: String?): CpuTimeSample? {
    val columns = raw.orEmpty().trim().split(Regex("\\s+"))
    if (columns.firstOrNull() != "cpu" || columns.size < 9) return null
    val counters = columns.drop(1).take(8).map { it.toLongOrNull() ?: return null }
    if (counters.any { it < 0L }) return null
    val guest = columns.getOrNull(9)?.let { it.toLongOrNull() ?: return null }
    val guestNice = columns.getOrNull(10)?.let { it.toLongOrNull() ?: return null }
    if ((guest != null && guest < 0L) || (guestNice != null && guestNice < 0L)) return null
    val total = counters.foldChecked() ?: return null
    val idle = runCatching { Math.addExact(counters[3], counters[4]) }.getOrNull() ?: return null
    return CpuTimeSample(counters.toLongArray(), total = total, idle = idle)
        .takeIf { total > 0L && idle in 0L..total }
}

private fun List<Long>.foldChecked(): Long? = fold(0L) { sum, value ->
    runCatching { Math.addExact(sum, value) }.getOrNull() ?: return null
}

/** Stateful aggregate CPU-load sampler. The first read warms the delta and never invents zero usage. */
internal class CpuUsageSampler(
    private val readProcStat: () -> String? = {
        runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull()
    },
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
) {
    private var previous: CpuTimeSample? = null
    private var previousAtMs: Long? = null

    @Synchronized
    fun samplePercent(): Int? {
        val nowMs = elapsedRealtimeMs()
        val raw = runCatching { readProcStat() }.getOrNull()
        val current = parseCpuTimeSample(raw) ?: run {
            previous = null
            previousAtMs = null
            return null
        }
        val prior = previous
        val priorAtMs = previousAtMs
        previous = current
        previousAtMs = nowMs
        if (prior == null || priorAtMs == null) return null
        if (current.counters.indices.any { current.counters[it] < prior.counters[it] }) return null
        val intervalMs = runCatching { Math.subtractExact(nowMs, priorAtMs) }.getOrNull()
        if (intervalMs == null || intervalMs <= 0L || intervalMs > maxIntervalMs) return null
        val totalDelta = runCatching { Math.subtractExact(current.total, prior.total) }.getOrNull()
            ?: return null
        val idleDelta = runCatching { Math.subtractExact(current.idle, prior.idle) }.getOrNull()
            ?: return null
        if (totalDelta <= 0L || idleDelta !in 0L..totalDelta) return null
        return (((totalDelta - idleDelta) * 100.0) / totalDelta).roundToInt().coerceIn(0, 100)
    }

    private companion object {
        const val MAX_INTERVAL_MS = 5_000L
    }
}

internal data class ClassifiedThermalZone(
    val type: String,
    val rawTemperature: Long?,
    val path: String = "",
)

internal data class ThermalZoneWinner(
    val type: String,
    val rawTemperature: Long,
    val celsius: Int,
    val path: String = "",
)

private fun normalizedThermalCelsius(rawTemperature: Long): Double? {
    val celsius = if (rawTemperature > 1_000L) {
        rawTemperature / 1_000.0
    } else {
        rawTemperature.toDouble()
    }
    return celsius.takeIf { it in 1.0..150.0 }
}

/** [celsius] is a mean; [zoneCount] is how many thermal zones fed it, so no layer above has to guess. */
internal data class CpuPackageTemperatureWitness(
    val celsius: Int,
    val zoneCount: Int,
)

/**
 * User-facing CPU-package temperature: a MEAN of a variable number of zones, never a single
 * package sensor. A device profile may supply ordered sensor-family prefixes; the first family
 * present wins. Otherwise all valid CPU/CPU-subsystem zones are averaged. The witness carries the
 * zone count so the wire contract can disclose the aggregation instead of implying one sensor.
 * This remains separate from the hottest-zone safety witness used by Guardian and calibration.
 */
internal fun classifiedCpuPackageTemperatureWitness(
    zones: List<ClassifiedThermalZone>,
    preferredTypePrefixes: List<String> = emptyList(),
): CpuPackageTemperatureWitness? {
    val validCpuZones = zones.mapNotNull { zone ->
        val type = zone.type.trim().lowercase()
        if ("cpu" !in type) return@mapNotNull null
        val celsius = zone.rawTemperature?.let(::normalizedThermalCelsius) ?: return@mapNotNull null
        type to celsius
    }
    val preferredReadings = preferredTypePrefixes.asSequence()
        .map(String::trim)
        .map(String::lowercase)
        .filter(String::isNotEmpty)
        .map { prefix ->
            validCpuZones.filter { (type, _) -> type == prefix || type.startsWith("$prefix-") }
                .map(Pair<String, Double>::second)
        }
        .firstOrNull(List<Double>::isNotEmpty)
    val readings = preferredReadings ?: validCpuZones.map(Pair<String, Double>::second)
    if (readings.isEmpty()) return null
    return CpuPackageTemperatureWitness(
        celsius = readings.average().toInt(),
        zoneCount = readings.size,
    )
}

internal fun classifiedCpuPackageTemperature(
    zones: List<ClassifiedThermalZone>,
    preferredTypePrefixes: List<String> = emptyList(),
): Int? = classifiedCpuPackageTemperatureWitness(zones, preferredTypePrefixes)?.celsius

/**
 * Actuation-grade Odin package-temperature truth. Unlike the user-facing projection above,
 * this never falls through to a sensor family or aggregate: fan control is admitted only when
 * the cached kernel topology contains one valid, path-backed exact package sensor.
 */
internal fun strictOdinPackageThermalReading(
    zones: List<ClassifiedThermalZone>,
): ThermalZoneWinner? {
    val matches = zones.filter { it.type.trim().equals(ODIN_PACKAGE_THERMAL_TYPE, ignoreCase = true) }
    if (matches.size != 1) return null
    val zone = matches.single()
    if (zone.path.isBlank()) return null
    val raw = zone.rawTemperature ?: return null
    val celsius = normalizedThermalCelsius(raw)?.toInt() ?: return null
    return ThermalZoneWinner(
        type = zone.type,
        rawTemperature = raw,
        celsius = celsius,
        path = zone.path,
    )
}

internal const val ODIN_PACKAGE_THERMAL_TYPE = "cpuss-0-1"

/** Select the exact winning CPU/GPU zone alongside its unrounded kernel value. */
internal fun classifiedThermalZoneWinners(
    zones: List<ClassifiedThermalZone>,
): Pair<ThermalZoneWinner?, ThermalZoneWinner?> {
    var cpu: ThermalZoneWinner? = null
    var gpu: ThermalZoneWinner? = null
    zones.forEach { zone ->
        val raw = zone.rawTemperature ?: return@forEach
        val celsius = normalizedThermalCelsius(raw)?.toInt() ?: return@forEach
        val winner = ThermalZoneWinner(zone.type, raw, celsius, zone.path)
        val type = zone.type.lowercase()
        if ("cpu" in type && (cpu == null || winner.celsius > cpu!!.celsius)) cpu = winner
        if (("gpu" in type || "kgsl" in type || "gfx" in type) &&
            (gpu == null || winner.celsius > gpu!!.celsius)
        ) {
            gpu = winner
        }
    }
    return cpu to gpu
}

/** Select the hottest valid sensor within each classified CPU/GPU family, never an unrelated zone. */
internal fun classifiedThermalTemperatures(
    zones: List<ClassifiedThermalZone>,
): Pair<Int?, Int?> = classifiedThermalZoneWinners(zones).let { (cpu, gpu) ->
    cpu?.celsius to gpu?.celsius
}

internal data class LivePerformanceTelemetry(
    /**
     * Stamped after the foreground attestation and before the frame read. The foreground package
     * is therefore never fresher than this stamp claims (the FPS-cap mutation guard's window stays
     * conservative — it can over-reject, never under-reject), while whatever the frame read costs
     * counts against freshness gates instead of hiding behind a bundle-assembly stamp.
     *
     * **How much older the frame data itself can be depends on which port produced it.** The
     * SurfaceFlinger port serves retained frame history and states its own lag in
     * [FrameTelemetryReading.latestPresentAgeMs]. The DRM `measured_fps` port has no per-commit
     * timestamp to state one from, so it leaves that field null and instead bounds its own
     * staleness at the source: it refuses any reading whose measured window has drifted past the
     * driver's configured periodicity (see [DrmMeasuredFpsSample.admissibleWindowExcessUs]), and
     * publishes the real window in [FrameTelemetryReading.windowDurationMs] either way.
     */
    val sampledAtElapsedRealtimeMs: Long,
    val cpuBusyPercent: Int?,
    val foregroundPackageName: String?,
    val frame: FrameTelemetryReading,
)

internal class LivePerformanceTelemetrySampler(
    private val foregroundPackage: () -> String?,
    private val frameTelemetry: FrameTelemetryPort,
    private val cpuUsage: CpuUsageSampler = CpuUsageSampler(),
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    @Synchronized
    fun sample(): LivePerformanceTelemetry {
        val packageName = foregroundPackage()
        // Ordering is load-bearing; see the stamp's contract on LivePerformanceTelemetry. Moving
        // this stamp after the frame read would hide the shell pipeline's cost from every
        // freshness gate; moving it before the attestation would let the mutation guard admit a
        // staler foreground package than the stamp claims.
        val sampledAtElapsedRealtimeMs = elapsedRealtimeMs()
        val frame = packageName?.let(frameTelemetry::read)
            ?: FrameTelemetryReading(FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND)
        return LivePerformanceTelemetry(
            sampledAtElapsedRealtimeMs = sampledAtElapsedRealtimeMs,
            cpuBusyPercent = cpuUsage.samplePercent(),
            foregroundPackageName = packageName,
            frame = frame,
        )
    }
}

internal fun Bundle.putLivePerformanceTelemetry(sample: LivePerformanceTelemetry): Bundle = apply {
    putLong("sampledAtElapsedRealtimeMs", sample.sampledAtElapsedRealtimeMs)
    sample.cpuBusyPercent?.let { putInt("cpuBusyPercent", it) }
    sample.foregroundPackageName?.let { putString("foregroundPackageName", it) }
    putString("frameStatus", sample.frame.status.name)
    sample.frame.layerName?.let { putString("frameLayerName", it) }
    sample.frame.averageFpsMilli?.let { putInt("frameAverageFpsMilli", it) }
    // The averaging window makes the FPS figure interpretable: a 6-frame sample and a 200-frame
    // sample must be distinguishable downstream, and the data's own age must be visible.
    if (sample.frame.frameCount > 0) putInt("frameCount", sample.frame.frameCount)
    sample.frame.windowDurationMs?.let { putInt("frameWindowDurationMs", it) }
    sample.frame.latestPresentAgeMs?.let { putInt("frameLatestPresentAgeMs", it) }
}
