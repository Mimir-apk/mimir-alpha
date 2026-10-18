package com.gruning.mimir.tweaker

import android.os.SystemClock

/** One composed getTelemetry() unit: the full hardware sweep plus the live performance sample. */
internal data class ComposedLiveTelemetry(
    val telemetry: TelemetrySnapshot,
    val live: LivePerformanceTelemetry,
)

/**
 * Binder-facing telemetry cache. getTelemetry() used to run the capabilities probe, the sysfs
 * sweep and a `dumpsys SurfaceFlinger | awk` shell pipeline synchronously inside every Binder
 * transaction; this cache makes that cost per-refresh instead of per-call.
 *
 * Freshness contract:
 * - A composed sample is served for at most [freshForMs] (750 ms) measured from its own
 *   [LivePerformanceTelemetry.sampledAtElapsedRealtimeMs]; past that, the next caller composes a
 *   fresh one synchronously.
 * - A served sample always keeps its ORIGINAL stamp. The cache never re-stamps, so every
 *   consumer can compute the true age of exactly what it received; cache hits are visible as an
 *   unchanged stamp, and every existing age gate (overlay 2500 ms, fan readout and FPS-cap
 *   attestation 3500 ms) keeps working on true ages.
 * - Refresh is single-flight: callers arriving during a refresh wait for it and receive its
 *   result rather than launching their own sweep. The CPU% delta sampler is therefore read by
 *   exactly one caller per refresh, so its measurement window is the refresh cadence — two
 *   surfaces polling concurrently can no longer shorten each other's delta windows.
 * - [freshForMs] is deliberately below the fastest sanctioned poll cadence (the overlay's
 *   1000 ms), so a steady 1 Hz poller always receives a fresh sweep and never a duplicate stamp,
 *   while a second surface's interleaved poll (the rail's 2000 ms) is usually absorbed.
 */
internal class LiveTelemetrySnapshotCache(
    private val compose: () -> ComposedLiveTelemetry,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val freshForMs: Long = FRESH_FOR_MS,
) {
    private val lock = Any()
    private var cached: ComposedLiveTelemetry? = null

    fun sample(): ComposedLiveTelemetry = synchronized(lock) {
        val prior = cached
        val age = prior?.let { elapsedRealtimeMs() - it.live.sampledAtElapsedRealtimeMs }
        if (prior != null && age != null && age in 0 until freshForMs) {
            prior
        } else {
            compose().also { cached = it }
        }
    }

    companion object {
        internal const val FRESH_FOR_MS = 750L
    }
}
