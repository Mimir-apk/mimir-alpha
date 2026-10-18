package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The HUD does not listen to Mimir's UI, and nothing may quietly re-attach a per-frame listener.**
 *
 * The HUD excludes measurement of Mimir's own UI frames.
 *
 * `MimirPrimaryFrameCounter` attached a `Window.OnFrameMetricsAvailableListener` in
 * `MainActivity.onCreate` and released it in `onDestroy`, so the framework captured and marshalled a
 * `FrameMetrics` for every frame Mimir rendered, for the whole activity lifetime, **including with the
 * overlay switched OFF**. device QA certified exactly that on hardware by a direct instrument rather
 * than a timing inference — the listener's `MimirFrameMetrics` `HandlerThread` is visible in `/proc`,
 * so its presence *is* the listener's state. Present on all three handhelds before, absent after, and
 * it reappeared live on a scope flip with no restart.
 *
 * **RETRACTED: the frame-time cost.** The commit that first scoped this listener (`b4adf14a`) is
 * titled *"measured at 12ms p95 on the Thor"* and claims a 48% reduction. **That attribution does not
 * survive.** Same device, same build, same workload, ~15 minutes apart:
 *
 * | design | ALWAYS (listener ON) | GAMES_ONLY (listener OFF) | delta |
 * |---|---|---|---|
 * | block, 3 + 3 | 22.67 | 12.67 | **+10.00 ms** |
 * | interleaved, 4 + 4 | 17.25 | 23.00 | **−5.75 ms** |
 * | pooled, n = 7 each | 19.57 (sd 5.75) | 18.57 (sd 5.85) | **+1.00 ms** |
 *
 * **The sign inverts when the arms are interleaved instead of blocked.** Pooled across all 16 runs ever
 * taken in the listener-ON condition the metric spans **11–28 ms**, and the "removed" arm's value of 13
 * occurs in 3 of those 16 — it was never outside the distribution it was being contrasted against. The
 * original A/B/A was clean in every other respect (device QA reconciled the raw captures, and confirmed
 * from the arm-B screenshot that the card was drawing with only the FPS cell dashed, so it was not
 * card-confounded); it was simply n = 2 per arm against a ~6 ms standard deviation. Blocked arms
 * correlate consecutive runs, which is what made it look tight and reproducible.
 *
 * Stated as **unsupported, not refuted** — the one experiment that would settle it (card ON, listener
 * OFF, n >= 5, interleaved) was never run, and is now moot because the listener is gone by ruling.
 *
 * **So this pin is grounded in the ruling, not in a benchmark.** What the removal is worth is an
 * always-on thread and whatever battery it draws, which nobody has quantified. This summary covers 26 recorded runs.
 *
 * **This is an ABSENCE pin, which is the shape that suits the ruling.** A gate saying "attach only
 * when useful" would still leave a listener in the app for someone to widen; a pin saying "there is no
 * listener" cannot be satisfied by widening a condition. The standing lesson that a removed path needs
 * its own gate is why this exists at all rather than trusting the deletion to stay deleted.
 */
class MimirFrameCountingGateTest {

    /**
     * Production sources of **every module**, not just `:app`.
     *
     * The first version of this pin scanned `app/src/main` alone. Checking the built APK caught the
     * gap: a listener added in `core-ui` or `feature-library` would have sailed past a green gate, and
     * a gate that reads as covering more than it does is worse than no gate — it converts an unchecked
     * area into an apparently-checked one.
     *
     * (The APK does contain one `addOnFrameMetricsAvailableListener` string, in androidx's
     * `FrameMetricsAggregator`. That is a library class present in the dex and never instantiated by
     * anything here; this pin covers our sources, which is what we control.)
     */
    private fun productionKotlin(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        assertTrue(
            "could not find the repo root (no settings.gradle.kts above ${File("").absolutePath}) — " +
                "if the layout changed, move this pin with it rather than deleting it.",
            dir != null,
        )
        val modules = dir!!.listFiles().orEmpty()
            .map { File(it, "src/main/kotlin") }
            .filter { it.isDirectory }
        assertTrue(
            "found no module sources under ${dir.absolutePath} — the scan must not silently cover nothing",
            modules.size >= 10,
        )
        return modules.flatMap { m -> m.walkTopDown().filter { it.isFile && it.extension == "kt" } }
    }

    @Test
    fun `no FrameMetrics listener exists anywhere in the app`() {
        val offenders = productionKotlin()
            .filter { file -> frameMetricsListenerPresent(file.readLines()) }
            .map { it.path.substringAfter("Mimir-rewrite-register/").ifEmpty { it.name } }
            .sorted()

        assertEquals(
            "A FrameMetrics listener is back in production. The HUD does not " +
                "listen to Mimir's UI state. Do NOT re-argue this from frame times: the 12 ms p95 " +
                "figure once claimed for it is RETRACTED (the sign inverts when the arms are " +
                "interleaved rather than blocked -- see this class's KDoc). What is certified is that " +
                "it ran for the whole activity lifetime, including with the overlay switched OFF. " +
                "If a per-game frame rate is " +
                "wanted, the source is SurfaceFlinger per-layer present timing read by the daemon — an " +
                "app cannot see another app's surface, and Mimir is stopped while a game is foreground.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The detector proves itself on samples, and no real file is ever mutated.
     *
     * The detector uses isolated samples to prove the source pin without temporarily disabling
     * the real listener guard. An interrupted mutate-and-restore check could leave that guard disabled.
     */
    @Test
    fun `the detector can tell a listener from prose about one`() {
        val attaches = listOf(
            "        val callback = Window.OnFrameMetricsAvailableListener { _, _, dropped ->",
            "        window.addOnFrameMetricsAvailableListener(callback, Handler(worker.looper))",
        )
        val prose = listOf(
            "         * `Window.addOnFrameMetricsAvailableListener` is the inverse: the framework",
            "        // What used to be here attached `Window.OnFrameMetricsAvailableListener` in onCreate",
        )
        val removalOnly = listOf(
            "            runCatching { current.removeOnFrameMetricsAvailableListener(callback) }",
        )

        assertTrue("a real registration must be caught", frameMetricsListenerPresent(attaches))
        assertFalse(
            "KDoc and comments explaining the removal must NOT trip the pin — otherwise the only way to " +
                "keep it green is to stop documenting why the listener went",
            frameMetricsListenerPresent(prose),
        )
        assertFalse(
            "a remove call is not a registration",
            frameMetricsListenerPresent(removalOnly),
        )
    }

    /**
     * True when a line REGISTERS a FrameMetrics listener. Comments and KDoc are stripped first, so this
     * file's own explanation of the defect cannot satisfy the check it is describing, and `remove…` is
     * excluded because unhooking is not hooking.
     */
    private fun frameMetricsListenerPresent(lines: List<String>): Boolean =
        lines
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .any { line ->
                line.contains("addOnFrameMetricsAvailableListener") ||
                    line.contains("Window.OnFrameMetricsAvailableListener")
            }
}
