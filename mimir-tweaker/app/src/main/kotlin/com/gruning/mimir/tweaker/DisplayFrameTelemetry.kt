package com.gruning.mimir.tweaker

/**
 * Parsing for the display driver's `measured_fps` node. **Nothing in the daemon reads the node any more**: the
 * live telemetry sampler carries no display-wide frame producer (the HUD's FPS is GameNative's own stream,
 * `LiveGameFrameTelemetryUnavailable`), so the port and factory that once read it were removed and only the
 * parsing and CRTC-selection rules below remain, with their recorded-device tests.
 *
 * ## What the node measures
 *
 * `measured_fps` is the **whole CRTC's composited present rate** -- every layer the compositor put on the
 * panel, including Mimir's own overlay -- not one app layer's present timeline as SurfaceFlinger's
 * `--latency` is. Present rate was selected despite that distinction (RC-47, 2026-08-22).
 *
 * ## What the node's three fields actually mean
 *
 * Grounded in the driver, not inferred: CodeLinaro
 * `clo/la/platform/vendor/opensource/display-drivers`, `msm/sde/sde_crtc.c`, functions
 * `measured_fps_show()` / `sde_crtc_calc_fps()` and `struct sde_crtc_fps_info` in `sde_crtc.h`.
 * The three format-string variants across branches are indistinguishable on 64-bit LE, so the text
 * below is stable from the 4.19-era trees through the current 6.x ones.
 *
 * `sde_crtc_calc_fps()` runs at commit kickoff and appends `ktime_get()` to a **ring buffer** of
 * [KERNEL_MAX_FRAME_COUNT] commit timestamps. `measured_fps_show()` then, **at read time**, walks
 * that ring backwards from the newest entry until it finds a commit at least
 * `fps_periodic_duration` old, and prints:
 *
 * ```
 * fps: 120.7 duration:1000000 frame_count:121
 *      ^^^^^          ^^^^^^^             ^^^
 *      |              |                   how many ring entries the walk covered
 *      |              the CONFIGURED periodicity in MICROSECONDS (fps_periodicity_ms x 1000,
 *      |              default 1000000) -- NOT the window that produced the number
 *      frame_count / (now - oldest_walked_commit), truncated to tenths
 * ```
 *
 * Three consequences the arithmetic does not survive without:
 *
 * 1. **`fps` is not `frame_count / duration`.** The denominator is the live age of the boundary
 *    commit, so the same `frame_count` reads lower the longer you wait. Measured on the Odin:
 *    `fps: 16.8 frame_count:25` is 25 commits over 1.488 s, and a fixed `frame_count:2` was
 *    observed decaying 0.6 -> 0.5 -> 0.4 across three reads 70 ms apart.
 * 2. **The real window is `frame_count / fps`, and it is only ever >= `duration`.** After an idle
 *    gap the boundary commit is the last one *before* the gap, so the window swallows the whole
 *    gap: measured on the Nova, the first read after ~9.4 s of static screen reported
 *    `fps: 4.4 frame_count:43` — 43 real commits diluted across a 9.77 s window — where the panel
 *    was in fact running at 120 Hz. [admissibleWindowExcessUs] is what rejects that.
 * 3. **`fps: 0.0 frame_count:0` is an unambiguous idle signal**, not a failure: it means the walk
 *    broke on its very first entry, i.e. nothing was committed in the last `duration`.
 *
 * Reading the node is not free of side effects — `measured_fps_show()` writes
 * `fps_info.measured_fps` — but it takes no lock this code can contend and mutates nothing any
 * other reader depends on.
 */
internal object DisplayFrameTelemetry {

    /** Kernel `MAX_FRAME_COUNT`: the commit ring holds this many timestamps. */
    const val KERNEL_MAX_FRAME_COUNT = 1_000

    /**
     * Both certified devices name their CRTC directories differently — the Odin (kernel 6.6,
     * `card%d-sde-crtc-%d`) has `card0-sde-crtc-0`, the Nova (kernel 5.15, `sde-crtc-%d`) has
     * `sde-crtc-0` — because the naming changed in `device_create_with_groups`. Never hardcode
     * either.
     */
    private val CRTC_DIRECTORY = Regex("^(?:card\\d+-)?sde-crtc-(\\d+)$")

    private val MEASURED_FPS_LINE =
        Regex("""fps:\s*(\d+)\.(\d+)\s+duration:\s*(\d+)\s+frame_count:\s*(\d+)""")

    /**
     * Pick the panel CRTC by **lowest CRTC index**, not by which one is currently non-zero.
     *
     * SDE publishes a node per CRTC (eight on the Odin, six on the Nova) and only the one driving
     * the panel is ever non-zero — but on a static screen *every* node reads `0.0`, verified on
     * both devices, so "pick the busy one" has no deterministic answer at resolution time. Index
     * order is stable across boots and matches where the primary DSI encoder lands on both
     * devices.
     *
     * Pure so the rule is testable without a device.
     */
    fun selectCrtcDirectory(entryNames: List<String>): String? = entryNames
        .mapNotNull { name ->
            CRTC_DIRECTORY.matchEntire(name)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.let { index -> index to name }
        }
        // Lowest index wins; the name breaks a tie only so the result cannot depend on readdir
        // order if a tree ever published both naming schemes at once.
        .sortedWith(compareBy({ (index, _) -> index }, { (_, name) -> name }))
        .firstOrNull()
        ?.second

    /** Parse one raw `measured_fps` read. Null means the text was not the expected shape. */
    fun parse(raw: String?): DrmMeasuredFpsSample? {
        val match = MEASURED_FPS_LINE.find(raw.orEmpty().trim()) ?: return null
        val whole = match.groupValues[1].toIntOrNull() ?: return null
        val tenth = match.groupValues[2].takeIf { it.length == 1 }?.toIntOrNull() ?: return null
        val durationUs = match.groupValues[3].toLongOrNull() ?: return null
        val frameCount = match.groupValues[4].toIntOrNull() ?: return null
        if (whole < 0 || tenth !in 0..9 || durationUs < 0L || frameCount < 0) return null
        return DrmMeasuredFpsSample(
            fpsTenths = whole * 10 + tenth,
            configuredDurationUs = durationUs,
            frameCount = frameCount,
        )
    }
}

/** One raw `measured_fps` read, parsed but not yet judged. */
internal data class DrmMeasuredFpsSample(
    /** `measured_fps` is fps x 10; 120.7 arrives as 1207. */
    val fpsTenths: Int,
    /** The CONFIGURED periodicity in microseconds, not the window that produced [fpsTenths]. */
    val configuredDurationUs: Long,
    /** Commits the kernel's ring walk covered; capped at [DisplayFrameTelemetry.KERNEL_MAX_FRAME_COUNT]. */
    val frameCount: Int,
) {
    /**
     * The window the reported rate actually describes, derived the only way the node allows:
     * `fps = frame_count * 1e6 * 10 / diff_us`, so `diff_us = frame_count * 1e7 / fps_tenths`.
     * Always >= [configuredDurationUs] by construction of the kernel's walk.
     */
    val measuredWindowUs: Long?
        get() = if (fpsTenths > 0 && frameCount > 0) {
            frameCount.toLong() * 10_000_000L / fpsTenths.toLong()
        } else {
            null
        }

    /**
     * How much longer than the configured periodicity the window may run before the number stops
     * describing *now*.
     *
     * Two clauses, both derived from the walk rather than picked to taste. The kernel stops at the
     * newest commit that is already `duration` old, so on a steady stream the overshoot is bounded
     * by roughly one inter-commit gap — `2 x` that absorbs ordinary pacing jitter and is what keeps
     * a genuinely slow-but-steady renderer admissible. The flat quarter-window floor is what keeps
     * a *fast* renderer admissible when jitter briefly exceeds two of its very short intervals.
     * Whichever is larger wins, so neither case rejects the other's normal behaviour.
     *
     * Checked against recorded device samples in `DisplayFrameTelemetryTest`: it admits the Nova's
     * steady 119-commit 1.004 s window and the Odin's 47-commit 1.047 s swipe, and rejects the
     * Nova's post-idle 43-commit 9.77 s window and the Odin's 74-commit 3.38 s one.
     */
    val admissibleWindowExcessUs: Long?
        get() {
            val window = measuredWindowUs ?: return null
            val meanIntervalUs = window / frameCount.toLong()
            return maxOf(configuredDurationUs / 4L, meanIntervalUs * 2L)
        }
}
