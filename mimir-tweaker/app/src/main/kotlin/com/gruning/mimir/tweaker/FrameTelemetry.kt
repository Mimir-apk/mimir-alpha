package com.gruning.mimir.tweaker

import kotlin.math.roundToInt

/**
 * Two producers write these, and the name says which. The `*_LAYER`/`*_LATENCY_*` values belong to
 * the SurfaceFlinger path ([PServerFrameTelemetryPort]); the `DISPLAY_*` values were the DRM
 * `measured_fps` path (its port is removed; the values stay wire-stable). They are kept distinct rather than
 * folded together because `frameStatus` is the field a device debugger reads out of the telemetry
 * bundle, and it should name the mechanism that actually declined.
 *
 * `.name` goes on the wire. The app's `performanceOverlayFrameStateLabel` special-cases only
 * `AVAILABLE` and `WORKLOAD_NOT_FOREGROUND` and renders every other value as FPS UNAVAILABLE, so
 * adding one here degrades gracefully rather than breaking a client.
 */
internal enum class FrameTelemetryStatus {
    AVAILABLE,
    WORKLOAD_NOT_FOREGROUND,
    P_SERVER_UNAVAILABLE,
    LIST_COMMAND_FAILED,
    NO_PACKAGE_LAYER,
    AMBIGUOUS_PACKAGE_LAYER,
    LATENCY_COMMAND_FAILED,
    MALFORMED_LATENCY_DATA,
    STALE_LATENCY_DATA,

    /** The DRM node resolved at startup could not be read this tick. */
    DISPLAY_FPS_NODE_UNREADABLE,

    /** The node's text was not `fps: N.N duration:N frame_count:N`, or the ring walk hit its cap. */
    MALFORMED_DISPLAY_FPS_DATA,

    /** `fps: 0.0 frame_count:0` — nothing was committed to the panel in the configured window. */
    DISPLAY_IDLE_NO_FRAMES,

    /**
     * Real commits, but spread across a window far longer than the configured periodicity because
     * an idle gap sits inside it, so the rate describes the gap rather than now. See
     * [DrmMeasuredFpsSample.admissibleWindowExcessUs].
     */
    STALE_DISPLAY_FPS_WINDOW,
}

internal data class FrameTelemetryReading(
    val status: FrameTelemetryStatus,
    val layerName: String? = null,
    val diagnostic: String? = null,
    val frameCount: Int = 0,
    val windowDurationMs: Int? = null,
    /**
     * Age of the newest present-fence timestamp at the moment the latency data was parsed
     * (0..2000 ms by construction; older data is rejected as STALE_LATENCY_DATA). SurfaceFlinger
     * serves retained frame history, so [averageFpsMilli] can describe frames this much older
     * than the read itself; without this figure no freshness gate can see that staleness.
     */
    val latestPresentAgeMs: Int? = null,
    val averageFpsMilli: Int? = null,
    val p50FrameTimeUs: Int? = null,
    val p95FrameTimeUs: Int? = null,
    val slow20FpsPercent: Int? = null,
    val slow30FpsPercent: Int? = null,
)

internal interface FrameTelemetryPort {
    fun read(packageName: String): FrameTelemetryReading
}

internal fun interface FrameCommandExecutor {
    fun execute(command: String): Result<String?>
}

internal class PServerFrameTelemetryPort(
    private val executor: FrameCommandExecutor,
    private val pServerAvailable: () -> Boolean,
    private val nowNs: () -> Long = System::nanoTime,
) : FrameTelemetryPort {
    private var cachedPackageName: String? = null
    private var cachedLayerName: String? = null

    override fun read(packageName: String): FrameTelemetryReading {
        if (!pServerAvailable()) return FrameTelemetryReading(FrameTelemetryStatus.P_SERVER_UNAVAILABLE)
        val layerName = cachedLayerName.takeIf { cachedPackageName == packageName }
            ?: resolveLayer(packageName).also { result ->
                if (result.status != FrameTelemetryStatus.AVAILABLE) return result
                cachedPackageName = packageName
                cachedLayerName = result.layerName
            }.layerName
            ?: return FrameTelemetryReading(FrameTelemetryStatus.NO_PACKAGE_LAYER)

        return readLatency(layerName).also { reading ->
            if (reading.status != FrameTelemetryStatus.AVAILABLE) invalidateCache()
        }
    }

    private fun readLatency(layerName: String): FrameTelemetryReading {
        val joinLines = "NR == 1 { printf \"%s\", \$0; next } { printf \";%s\", \$0 } END { print \"\" }"
        val output = executor.execute(
            "dumpsys SurfaceFlinger --latency ${shellQuote(layerName)} | awk ${shellQuote(joinLines)}",
        )
            .getOrElse { error ->
                return FrameTelemetryReading(
                    FrameTelemetryStatus.LATENCY_COMMAND_FAILED,
                    layerName = layerName,
                    diagnostic = error.safeDiagnostic(),
                )
            }
            ?: run {
                return FrameTelemetryReading(FrameTelemetryStatus.LATENCY_COMMAND_FAILED, layerName = layerName)
            }
        val expandedOutput = output.replace(';', '\n')
        return SurfaceFlingerFrameParser.parseLatency(expandedOutput, layerName, nowNs())
    }

    private fun resolveLayer(packageName: String): FrameTelemetryReading {
        val prefix = "SurfaceView[$packageName/"
        val filter = "grep -F ${shellQuote(prefix)} | grep -F ${shellQuote("(BLAST)#")}"
        val joinLines = "NR == 1 { printf \"%s\", \$0; next } { printf \";%s\", \$0 } END { print \"\" }"
        val output = executor.execute(
            "dumpsys SurfaceFlinger --list | $filter | awk ${shellQuote(joinLines)}",
        )
            .getOrElse { error ->
                return FrameTelemetryReading(
                    FrameTelemetryStatus.LIST_COMMAND_FAILED,
                    diagnostic = error.safeDiagnostic(),
                )
            }
            ?: return FrameTelemetryReading(FrameTelemetryStatus.LIST_COMMAND_FAILED)
        if (output.isBlank()) {
            return resolveDirectPackageLayer(packageName)
        }
        val expandedOutput = output.replace(';', '\n')
        val resolved = SurfaceFlingerFrameParser.resolveLayer(expandedOutput, packageName)
        if (resolved.status != FrameTelemetryStatus.AMBIGUOUS_PACKAGE_LAYER) return resolved
        return resolveUniqueFreshLayer(expandedOutput, packageName)
    }

    /** Multiple retained SurfaceViews are common after an emulator Activity recreation. */
    private fun resolveUniqueFreshLayer(output: String, packageName: String): FrameTelemetryReading {
        val candidates = SurfaceFlingerFrameParser.topRankedLayers(output, packageName)
        val attempts = candidates.map { candidate -> candidate to readLatency(candidate) }
        val fresh = attempts.filter { (_, reading) -> reading.status == FrameTelemetryStatus.AVAILABLE }
        return if (fresh.size == 1) {
            FrameTelemetryReading(FrameTelemetryStatus.AVAILABLE, layerName = fresh.single().first)
        } else {
            FrameTelemetryReading(
                FrameTelemetryStatus.AMBIGUOUS_PACKAGE_LAYER,
                diagnostic = attempts.joinToString(" | ") { (layer, reading) ->
                    "${layer.take(160)}=${reading.status}"
                }.take(MAX_DIAGNOSTIC_CHARS),
            )
        }
    }

    /**
     * Some renderers publish their buffer directly under the package/activity layer rather than a
     * SurfaceView. Keep that compatibility path read-only and fail closed on equal-ranked candidates.
     */
    private fun resolveDirectPackageLayer(packageName: String): FrameTelemetryReading {
        val packageFilter = "grep -F ${shellQuote("$packageName/")}"
        val joinLines = "NR == 1 { printf \"%s\", \$0; next } { printf \";%s\", \$0 } END { print \"\" }"
        val output = executor.execute(
            "dumpsys SurfaceFlinger --list | $packageFilter | awk ${shellQuote(joinLines)}",
        ).getOrElse { error ->
            return FrameTelemetryReading(
                FrameTelemetryStatus.LIST_COMMAND_FAILED,
                diagnostic = error.safeDiagnostic(),
            )
        } ?: return FrameTelemetryReading(FrameTelemetryStatus.LIST_COMMAND_FAILED)
        if (output.isBlank()) {
            return FrameTelemetryReading(
                FrameTelemetryStatus.NO_PACKAGE_LAYER,
                diagnostic = "No package render layer found",
            )
        }
        return SurfaceFlingerFrameParser.resolveLayer(output.replace(';', '\n'), packageName)
    }

    private fun invalidateCache() {
        cachedPackageName = null
        cachedLayerName = null
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun Throwable.safeDiagnostic(): String =
        "${javaClass.simpleName}: ${message.orEmpty()}".take(MAX_DIAGNOSTIC_CHARS)

    companion object {
        private const val MAX_DIAGNOSTIC_CHARS = 512
    }
}

internal object SurfaceFlingerFrameParser {
    private const val MIN_INTERVAL_COUNT = 5
    private const val STALE_AFTER_NS = 2_000_000_000L
    private const val SLOW_20_FPS_NS = 50_000_000L
    private const val SLOW_30_FPS_NS = 34_000_000L
    private const val MAX_TIMESTAMP_NS = Long.MAX_VALUE - 1

    fun resolveLayer(output: String, packageName: String): FrameTelemetryReading {
        val candidates = topRankedLayers(output, packageName)
        if (candidates.isEmpty()) {
            val relevant = output.lineSequence().filter { packageName in it }.take(4).toList()
            val diagnosticLines = relevant.ifEmpty {
                output.lineSequence().map(String::trim).filter(String::isNotEmpty).take(4).toList()
            }
            return FrameTelemetryReading(
                FrameTelemetryStatus.NO_PACKAGE_LAYER,
                diagnostic = diagnosticLines.joinToString(" | ").take(512).ifBlank { "empty output" },
            )
        }
        if (candidates.size != 1) {
            return FrameTelemetryReading(
                FrameTelemetryStatus.AMBIGUOUS_PACKAGE_LAYER,
                diagnostic = candidates.joinToString(" | ").take(512),
            )
        }
        return FrameTelemetryReading(FrameTelemetryStatus.AVAILABLE, layerName = candidates.single())
    }

    internal fun topRankedLayers(output: String, packageName: String): List<String> {
        val ranked = output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { rawLayer ->
                normalizeLayerName(rawLayer, packageName)?.let { layer ->
                    layerPriority(layer, packageName)?.let { priority -> priority to layer }
                }
            }
            .toList()
        if (ranked.isEmpty()) return emptyList()
        val highestPriority = ranked.maxOf { it.first }
        return ranked.filter { it.first == highestPriority }.map { it.second }.distinct()
    }

    fun parseLatency(output: String, layerName: String, nowNs: Long): FrameTelemetryReading {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val refreshPeriodNs = lines.firstOrNull()?.toLongOrNull()
            ?: return malformed(layerName)
        if (refreshPeriodNs <= 0L) return malformed(layerName)
        val presentTimestampsNs = lines.drop(1).mapNotNull { line ->
            val columns = line.split(Regex("\\s+"))
            if (columns.size != 3) return@mapNotNull null
            columns[1].toLongOrNull()?.takeIf { it in 1L..MAX_TIMESTAMP_NS }
        }
        if (presentTimestampsNs.size < MIN_INTERVAL_COUNT + 1) return malformed(layerName)
        val latestNs = presentTimestampsNs.maxOrNull() ?: return malformed(layerName)
        if (nowNs < latestNs || nowNs - latestNs > STALE_AFTER_NS) {
            return FrameTelemetryReading(FrameTelemetryStatus.STALE_LATENCY_DATA, layerName = layerName)
        }
        val intervalsNs = presentTimestampsNs.zipWithNext { first, second -> second - first }
        if (intervalsNs.any { it <= 0L }) return malformed(layerName)
        if (intervalsNs.size < MIN_INTERVAL_COUNT) return malformed(layerName)
        val windowNs = presentTimestampsNs.last() - presentTimestampsNs.first()
        if (windowNs <= 0L) return malformed(layerName)
        val sorted = intervalsNs.sorted()
        val averageFpsMilli = (intervalsNs.size * 1_000_000_000_000.0 / windowNs).roundToInt()
        return FrameTelemetryReading(
            status = FrameTelemetryStatus.AVAILABLE,
            layerName = layerName,
            frameCount = intervalsNs.size,
            windowDurationMs = (windowNs / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            latestPresentAgeMs = ((nowNs - latestNs) / 1_000_000L).toInt(),
            averageFpsMilli = averageFpsMilli,
            p50FrameTimeUs = percentile(sorted, 50).nanosToMicros(),
            p95FrameTimeUs = percentile(sorted, 95).nanosToMicros(),
            slow20FpsPercent = percentAbove(intervalsNs, SLOW_20_FPS_NS),
            slow30FpsPercent = percentAbove(intervalsNs, SLOW_30_FPS_NS),
        )
    }

    /** Android 15's SurfaceFlinger new frontend wraps real names in RequestedLayerState records. */
    private fun normalizeLayerName(rawLayer: String, packageName: String): String? {
        if (
            "ActivityRecordInputSink" in rawLayer ||
            "ActivityRecord{" in rawLayer ||
            "Background for " in rawLayer ||
            "Bounds for " in rawLayer
        ) {
            return null
        }
        val surfaceStart = rawLayer.indexOf("SurfaceView[$packageName/")
        val packageStart = rawLayer.indexOf("$packageName/")
        val start = when {
            surfaceStart >= 0 -> surfaceStart
            packageStart >= 0 -> packageStart
            else -> return null
        }
        val end = listOf(" parentId=", " relativeParentId=", " z=", "}")
            .mapNotNull { marker ->
                rawLayer.indexOf(marker, startIndex = start).takeIf { it >= 0 }
            }
            .minOrNull()
            ?: rawLayer.length
        return rawLayer.substring(start, end).trim().takeIf(String::isNotEmpty)
    }

    private fun layerPriority(layer: String, packageName: String): Int? = when {
        layer.startsWith("SurfaceView[$packageName/") && "(BLAST)#" in layer -> 4
        layer.startsWith("SurfaceView[$packageName/") -> 3
        layer.startsWith("$packageName/") && "(BLAST)#" in layer -> 2
        layer.startsWith("$packageName/") -> 1
        else -> null
    }

    private fun percentile(sorted: List<Long>, percentile: Int): Long {
        val index = ((percentile / 100.0) * sorted.lastIndex).roundToInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun percentAbove(values: List<Long>, thresholdNs: Long): Int =
        (values.count { it > thresholdNs } * 100.0 / values.size).roundToInt()

    private fun Long.nanosToMicros(): Int = (this / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun malformed(layerName: String) = FrameTelemetryReading(
        FrameTelemetryStatus.MALFORMED_LATENCY_DATA,
        layerName = layerName,
    )
}
