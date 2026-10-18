package com.gruning.mimir.rewrite

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Window
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Mimir measuring **Mimir**, for the performance-card CPU cell.
 *
 * **Policy 2026-09-01.** The card's CPU and FPS cells were honest measurements of the wrong
 * subject: CPU was the whole SoC's busy fraction out of `/proc/stat`, and FPS was the panel's
 * composited present rate out of the kernel's
 * per-CRTC `measured_fps` node under `/sys/class/drm` — every layer on the display, which
 * that port's own KDoc states in as many words
 * (`mimir-tweaker/.../DisplayFrameTelemetry.kt:17-20`). The card instead measures this app process.
 *
 * **What each reading here is, precisely:**
 *
 * | reading | subject | denominator | absent when |
 * |---|---|---|---|
 * | [MimirProcessCpuSampler] | THIS process (`com.gruning.mimir.rewrite`) | the **whole SoC** | first sample of a series |
 *
 * The FPS row that stood beside it is gone — `MimirPrimaryFrameCounter` was REMOVED 2026-09-03; see
 * the block further down. The cell renders "—" until a real per-game source exists.
 *
 * **The CPU denominator is the whole chip, not one core, and that is a decision.** A per-core figure
 * would let one process read `380%`, which is a true number in `top`'s dialect and a lie in a cell
 * whose neighbour (`GPU`) is a 0–100 busy fraction one separator away. Whole-SoC keeps both
 * percentages on one scale and keeps the slot's width bounded. The core count comes from
 * `_SC_NPROCESSORS_CONF` — every core the kernel knows
 * about — rather than `availableProcessors()`, which tracks hotplug and would silently move the
 * denominator between two beats.
 *
 * **The Tweaker's own process is NOT included, and that is a stated exclusion, not an oversight.**
 * `com.gruning.mimir.tweaker` is Mimir's too, and on the Thor it is the larger half (0.58% of SoC
 * against this process's 0.31%). It is excluded because it cannot be read: `/proc` is mounted
 * `hidepid` on Android, so this app can open its own `stat` and no other package's, and the daemon
 * transport that could have self-reported is signature-paired to a key this build does not hold on
 * exactly the device in question. The only remaining route is a privileged `PServer` exec per beat,
 * which is a cost this file deliberately does not pay for a glance readout. **Open design question** —
 * whether the cell should say "Mimir" and mean both processes.
 *
 * **Nothing here is a perpetual producer (§6).** The CPU sampler is a two-file read on a beat that
 * already existed, and it is now the only producer in this file.
 *
 * The frame counter used to be argued for on the same ground — `FrameMetrics` is *delivered* when the
 * window renders a frame rather than *requesting* a vsync like a self-reposting `Choreographer`
 * callback. That argument was correct about the SHAPE and still lost: delivered-per-frame is a
 * per-frame cost while anything is drawing, regardless of callback shape.
 */

/**
 * **A baseline older than this is thrown away rather than divided.**
 *
 * The reader's beat is 1 s while something is drawn and one read per 30 s while nothing is
 * (`PerformanceTelemetryHiddenPollMs`), and neither of those is the real bound: the sampling loop
 * skips reading entirely while the screen is off, so the gap between two environment reads can be
 * hours. A percentage computed across such a gap is arithmetically correct and describes nothing a
 * player could act on — it is the same since-boot-average-dressed-as-now this whole file refuses.
 * Twice the idle cadence is the line: anything inside it is a longer average of the same quantity,
 * anything beyond it is a different claim, and the surface says "—" for one beat and then resumes.
 */
internal const val MimirTelemetryStaleBaselineMs = 60_000L

/**
 * `utime + stime` from one `/proc/self/stat` read, in clock ticks, or null if the line is not the
 * shape the kernel documents.
 *
 * **Split on the LAST `)`, never on whitespace from the front.** Field 2 is `comm`, which is
 * arbitrary bytes inside parentheses and may contain both spaces and parentheses; a front-to-back
 * whitespace split mis-indexes every field after it for any process whose name has a space in it.
 *
 * Child times (`cutime`/`cstime`) are excluded: they are only credited on `wait()`, so including
 * them would make a reaped helper process arrive as a spike in Mimir's own footprint long after the
 * work happened.
 */
internal fun mimirProcessCpuTicks(stat: String?): Long? {
    val text = stat?.trim()?.takeIf(String::isNotEmpty) ?: return null
    // No closing paren means this is not a stat line at all -- substringAfterLast would otherwise
    // hand back the WHOLE string and every index below would be off by two.
    if (!text.contains(')')) return null
    val afterComm = text.substringAfterLast(')').trim().takeIf(String::isNotEmpty) ?: return null
    val fields = afterComm.split(Regex("\\s+"))
    // fields[0] is `state`, the third field of the line, so `utime` (the 14th) is index 11 here.
    val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
    val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
    if (utime < 0L || stime < 0L) return null
    return utime + stime
}

/**
 * Busy ticks over an interval, as a percentage of everything the SoC could have run in it.
 *
 * Clamped to 0..100: a process cannot use more of the whole chip than exists, and a negative delta
 * (a counter that went backwards, which only a bad read can produce) is refused rather than shown.
 */
internal fun mimirProcessCpuPercent(
    deltaTicks: Long,
    deltaMs: Long,
    ticksPerSecond: Long,
    coreCount: Int,
): Float? {
    if (deltaTicks < 0L || deltaMs <= 0L || ticksPerSecond <= 0L || coreCount <= 0) return null
    val busySeconds = deltaTicks.toDouble() / ticksPerSecond.toDouble()
    val availableSeconds = (deltaMs.toDouble() / 1_000.0) * coreCount.toDouble()
    if (availableSeconds <= 0.0) return null
    return ((busySeconds / availableSeconds) * 100.0).toFloat().coerceIn(0f, 100f)
}

/**
 * A rate, so ONE sample can never produce it — the same law
 * [MimirDiscoveredTelemetry]'s `/proc/stat` reader was written under. The first call seeds the
 * baseline and returns null, the cell renders `—` once, and every call after that describes the
 * interval since the previous one. A since-start average dressed as "now" is refused.
 *
 * **One instance per reader.** Two readers sharing a sampler would each consume the other's
 * baseline and each measure a fragment of its own interval.
 */
internal class MimirProcessCpuSampler(
    private val ticksPerSecond: Long,
    private val coreCount: Int,
    private val readSelfStat: () -> String?,
    private val elapsedRealtimeMs: () -> Long,
    private val maxIntervalMs: Long = MimirTelemetryStaleBaselineMs,
) {
    private var lastTicks: Long? = null
    private var lastAtMs: Long = 0L

    fun sample(): Float? {
        val ticks = mimirProcessCpuTicks(readSelfStat()) ?: return null
        val nowMs = elapsedRealtimeMs()
        val previousTicks = lastTicks
        val previousAtMs = lastAtMs
        lastTicks = ticks
        lastAtMs = nowMs
        if (previousTicks == null) return null
        if (nowMs - previousAtMs > maxIntervalMs) return null
        return mimirProcessCpuPercent(
            deltaTicks = ticks - previousTicks,
            deltaMs = nowMs - previousAtMs,
            ticksPerSecond = ticksPerSecond,
            coreCount = coreCount,
        )
    }

    companion object {
        /**
         * The production sampler. `sysconf` is asked once per instance — the tick rate and the
         * configured core count are boot-time constants, and re-asking every beat would pay two
         * syscalls for an answer that cannot have changed.
         */
        fun onDevice(): MimirProcessCpuSampler = MimirProcessCpuSampler(
            ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(0L),
            coreCount = runCatching { Os.sysconf(OsConstants._SC_NPROCESSORS_CONF).toInt() }
                .getOrDefault(0),
            readSelfStat = { runCatching { File(SelfStatPath).readText() }.getOrNull() },
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
        )

        private const val SelfStatPath = "/proc/self/stat"
    }
}

/**
 * Frames Mimir's own primary window has rendered, counted by the framework as they land.
 *
 * **`FrameMetrics`, not `Choreographer`, and the difference is §6.** A `Choreographer` callback that
 * re-posts itself is a *request* for a vsync on every frame, so it keeps the pipeline awake on a
 * screen where nothing is moving — a perpetual producer, which this overlay is forbidden to become.
 * `Window.addOnFrameMetricsAvailableListener` is the inverse: the framework delivers a callback for
 * a frame that was *already* produced and delivers nothing at all when the window is idle. Attached
 * for the life of the Activity and costing exactly one increment per frame Mimir was drawing anyway.
 *
 * **The worker thread is not the UI thread on purpose.** Counting on the main looper would put a
 * message dispatch per frame onto the very thread whose frame rate is being measured, so the
 * instrument would move the reading. The `HandlerThread` is created on attach and quit on detach,
 * and it blocks on an empty queue whenever Mimir is not drawing.
 *
 * **The count is Mimir's PRIMARY window and nothing else.** Not the overlay card (its own
 * `WindowManager` window, which redraws about once a second by design), and not the companion
 * `Presentation` on the second display, which is a different window on a different panel — see
 * [PerformanceOverlayEnvironment.mimirFrameRate] for what the second screen's panel shows instead.
 */
/**
 * **REMOVED 2026-09-03: `MimirPrimaryFrameCounter`, and nothing replaces it here.**
 *
 * The HUD must not sample Mimir's UI frame rate. The earlier implementation changed the measured
 * subject to Mimir's own frames instead of removing UI-frame collection.
 *
 * It attached a `Window.OnFrameMetricsAvailableListener` in `MainActivity.onCreate` and released it in
 * `onDestroy`, so the framework captured and marshalled a `FrameMetrics` for every frame Mimir rendered
 * for the whole activity lifetime — including with the overlay switched OFF. **That is certified**:
 * device QA read the listener's `MimirFrameMetrics` HandlerThread straight out of `/proc` and found
 * it present on all three handhelds beforehand, absent afterwards.
 *
 * **The frame-time cost once claimed here ("~12 ms at p95") is RETRACTED and must not come back.** It
 * came from an A/B/A whose arms were run in blocks; interleaving them inverts the sign. The full
 * accounting is in the KDoc on `MimirFrameCountingGateTest`. What the removal is actually worth is an
 * always-on thread and its battery draw, which nobody has measured.
 *
 * **It could never have shown a game's frame rate either.** Mimir is stopped while a game is foreground,
 * so its own window renders nothing; and the kernel's per-CRTC `measured_fps` is the panel's composited
 * present rate, which reads 60 with Mimir force-stopped. Both candidate sources answer a question the
 * cell was not asking.
 *
 * The per-game source this note once listed as OWED has since landed (c94f4e4b, aad984a7), and it is not
 * SurfaceFlinger: the overlay asks the privileged utility for the foreground game's own frame rate
 * (`PerformanceOverlayService.readForegroundGameFrameRate`; the producers are listed on
 * `performanceOverlayFpsValue`), and the FPS cell reads "—" whenever that route has no fresh sample.
 * [MimirFrameRateSampler] below was kept for that source, but the source does not use it and nothing in
 * production calls it: pure arithmetic over a monotonic frame count, no listener, no cost.
 *
 * Pinned by `MimirFrameCountingGateTest`, which fails if any FrameMetrics listener returns to this app.
 */

/** Frames per second from a frame-count delta. Whole frames; the plate has no room for a decimal. */
internal fun mimirFrameRate(deltaFrames: Long, deltaMs: Long): Int? {
    if (deltaFrames < 0L || deltaMs <= 0L) return null
    return ((deltaFrames * 1_000.0) / deltaMs).roundToInt().coerceAtLeast(0)
}

/**
 * The frame-rate half of the same two-sample law [MimirProcessCpuSampler] follows: one count is not
 * a rate, so the first call seeds and returns null.
 *
 * [reset] exists because the counter is monotonic across a backgrounding. Without it, a player who
 * leaves Mimir for twenty minutes and comes back would be shown the frames of the last visible
 * second divided by twenty minutes — a number that is arithmetically correct and describes nothing.
 */
internal class MimirFrameRateSampler(
    private val renderedFrames: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
    private val maxIntervalMs: Long = MimirTelemetryStaleBaselineMs,
) {
    private var lastFrames: Long? = null
    private var lastAtMs: Long = 0L

    fun sample(): Int? {
        val frames = renderedFrames()
        val nowMs = elapsedRealtimeMs()
        val previousFrames = lastFrames
        val previousAtMs = lastAtMs
        lastFrames = frames
        lastAtMs = nowMs
        if (previousFrames == null) return null
        if (nowMs - previousAtMs > maxIntervalMs) return null
        return mimirFrameRate(deltaFrames = frames - previousFrames, deltaMs = nowMs - previousAtMs)
    }

    fun reset() {
        lastFrames = null
        lastAtMs = 0L
    }
}
