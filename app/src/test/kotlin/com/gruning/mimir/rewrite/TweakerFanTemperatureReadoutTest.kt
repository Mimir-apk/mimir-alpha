package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for the fan-control temperature readout: its copy, banned-vocabulary invariant, bounded retention,
 * and both surfaces read CPU from the same hottest-core source rather than the recommended mean,
 * so their temperature readouts cannot disagree.
 *
 * This module has no Compose test harness (see `PerformanceOverlayRowPermissionWiringTest`'s
 * doc comment for the same limitation), so the source-order/focus-API pin at the bottom is source
 * characterization, not a behavioural test -- it reads the actual file text, the same pattern that
 * test already established for this module.
 */
class TweakerFanTemperatureReadoutTest {

    // ---- §3.4 copy table: visual ----------------------------------------------------------

    @Test
    fun `visual readout renders all four GPU CPU presence combinations`() {
        assertEquals("GPU 71°C · CPU 58°C", tweakerFanTemperatureReadoutText(71, 58))
        assertEquals("GPU 71°C · CPU —", tweakerFanTemperatureReadoutText(71, null))
        assertEquals("GPU — · CPU 58°C", tweakerFanTemperatureReadoutText(null, 58))
        assertEquals("GPU — · CPU —", tweakerFanTemperatureReadoutText(null, null))
    }

    @Test
    fun `visual readout absence uses the em dash, never a hyphen`() {
        val rendered = tweakerFanTemperatureReadoutText(null, null)
        assertTrue("expected U+2014 em dash in: $rendered", rendered.contains('—'))
        assertFalse("must not fall back to a hyphen: $rendered", rendered.contains('-'))
    }

    // ---- §3.4 copy table: spoken (TalkBack) ------------------------------------------------

    @Test
    fun `spoken readout renders all four GPU CPU presence combinations`() {
        assertEquals(
            "GPU 71 degrees. CPU 58 degrees.",
            tweakerFanTemperatureSpokenText(71, 58),
        )
        assertEquals(
            "GPU 71 degrees. CPU temperature unavailable.",
            tweakerFanTemperatureSpokenText(71, null),
        )
        assertEquals(
            "GPU temperature unavailable. CPU 58 degrees.",
            tweakerFanTemperatureSpokenText(null, 58),
        )
        assertEquals(
            "Temperature unavailable.",
            tweakerFanTemperatureSpokenText(null, null),
        )
    }

    @Test
    fun `spoken form never contains a literal em dash the reader would spell out`() {
        listOf(
            tweakerFanTemperatureSpokenText(71, 58),
            tweakerFanTemperatureSpokenText(71, null),
            tweakerFanTemperatureSpokenText(null, 58),
            tweakerFanTemperatureSpokenText(null, null),
        ).forEach { spoken ->
            assertFalse("spoken form must not carry the visual glyph: $spoken", spoken.contains('—'))
        }
    }

    // ---- §8.4 banned vocabulary, full 1..150 x {value, null} domain ------------------------

    @Test
    fun `neither formatter ever emits banned vocabulary across the full domain`() {
        val bannedFragments = listOf(
            "certified", "qualified", "qualification", "safe", "danger", "critical",
            "limit", "warning", "throttl", "overheat",
        )
        val values: List<Int?> = (1..150).map { it } + listOf(null)
        for (gpu in values) {
            for (cpu in values) {
                val visual = tweakerFanTemperatureReadoutText(gpu, cpu).lowercase()
                val spoken = tweakerFanTemperatureSpokenText(gpu, cpu).lowercase()
                bannedFragments.forEach { banned ->
                    assertFalse(
                        "visual leaked '$banned' for gpu=$gpu cpu=$cpu: $visual",
                        visual.contains(banned),
                    )
                    assertFalse(
                        "spoken leaked '$banned' for gpu=$gpu cpu=$cpu: $spoken",
                        spoken.contains(banned),
                    )
                }
            }
        }
    }

    // ---- §4.3 bounded retention -------------------------------------------------------------

    @Test
    fun `a held reading survives up to the 4000ms bound and renders absent past it`() {
        assertEquals(
            71,
            boundedRetainedFanTempC(
                retained = 71,
                retainedAtElapsedRealtimeMs = 10_000L,
                nowElapsedRealtimeMs = 14_000L,
            ),
        )
        assertNull(
            "one millisecond past the 4000ms bound must render absent",
            boundedRetainedFanTempC(
                retained = 71,
                retainedAtElapsedRealtimeMs = 10_000L,
                nowElapsedRealtimeMs = 14_001L,
            ),
        )
    }

    @Test
    fun `a null retained value or an unset acceptance stamp renders absent immediately`() {
        assertNull(
            boundedRetainedFanTempC(
                retained = null,
                retainedAtElapsedRealtimeMs = 10_000L,
                nowElapsedRealtimeMs = 10_000L,
            ),
        )
        assertNull(
            "nothing has been accepted yet -- must not fall back to treating it as fresh",
            boundedRetainedFanTempC(
                retained = 71,
                retainedAtElapsedRealtimeMs = null,
                nowElapsedRealtimeMs = 10_000L,
            ),
        )
    }

    // ---- CPU source symmetry  ---------------------------------------

    @Test
    fun `fan readout and performance overlay draw CPU from the identical hottest-core field`() {
        val telemetry = TweakerTelemetry(
            sampledAtElapsedRealtimeMs = 10_000L,
            foregroundPackageName = null,
            frameStatus = null,
            frameAverageFpsMilli = null,
            cpuTempC = 89,
            gpuTempC = 55,
            cpuBusyPercent = null,
            gpuBusyPercent = null,
            batteryPowerMw = null,
            batteryStatus = null,
            // Deliberately different from cpuTempC -- proves neither surface reads the mean anymore.
            cpuPackageTempC = 61,
        )

        val fanReadoutCpu = freshUtilityFanCpuTempC(telemetry, nowElapsedRealtimeMs = 10_000L)
        val overlayCpu = performanceOverlayCpuTemperature(telemetry)

        assertEquals(89, fanReadoutCpu)
        assertEquals(89, overlayCpu)
        assertEquals(
            "the fan readout and the performance overlay must never disagree on CPU",
            fanReadoutCpu,
            overlayCpu,
        )
        assertTrue(
            "both must read the hottest-core field, not the package mean",
            fanReadoutCpu != telemetry.cpuPackageTempC,
        )
    }

    // ---- Source-order and focus-authority pins (Compose harness-free module) -----------------

    @Test
    fun `TweakerFanCurveRow declares no new focus API and seats the readout between subtext and curve`() {
        val body = tweakerFanCurveRowSource()

        assertFalse("must never use Compose's focusable()", body.contains("focusable("))
        assertFalse("must never use Compose's focusTarget()", body.contains("focusTarget("))
        // Word-boundary regex over CODE ONLY, not a substring check: Kotlin's trailing-lambda call
        // syntax (`clickable { }`) carries no literal "clickable(" -- a plain .contains("clickable(")
        // would miss exactly that form and wave through a real regression. Comment lines are stripped
        // first because this row's own KDoc legitimately discusses TweakerProfileRow's *different*
        // clickable-based pattern as a contrast (see the comment above the readout's MimirChromeText);
        // that prose must not itself trip the pin.
        assertFalse(
            "the readout must not add a new clickable region",
            Regex("\\bclickable\\b").containsMatchIn(withoutLineComments(body)),
        )

        val supportingTextAt = body.indexOf("tweakerFanCurveSupportingText(previewBias)")
        val readoutAt = body.indexOf("tweakerFanTemperatureReadoutText(gpuTempC, cpuTempC)")
        val curveBoxAt = body.indexOf("BoxWithConstraints(")
        assertTrue("supporting text must be found", supportingTextAt >= 0)
        assertTrue("the readout call must be found", readoutAt >= 0)
        assertTrue("the curve box must be found", curveBoxAt >= 0)
        assertTrue(
            "the readout must be declared after the supporting text and before the curve box",
            supportingTextAt < readoutAt && readoutAt < curveBoxAt,
        )
    }

    @Test
    fun `TweakerFanCurveRow's floor is raised to 170dp to seat the readout without eating the row's gaps`() {
        val body = tweakerFanCurveRowSource()
        assertTrue(
            "the row floor must be raised to 170.dp (design spec §3.2)",
            body.contains("heightIn(min = 170.dp)"),
        )
        assertFalse(
            "the row must not still carry the pre-readout 156.dp floor",
            body.contains("heightIn(min = 156.dp)"),
        )
    }

    @Test
    fun `TweakerFanCurveRow suppresses the readout as an independent TalkBack stop`() {
        val body = tweakerFanCurveRowSource()
        val readoutAt = body.indexOf("tweakerFanTemperatureReadoutText(gpuTempC, cpuTempC)")
        val nextClearAndSetSemantics = body.indexOf("clearAndSetSemantics", readoutAt)
        val nextComposableCallAfterReadout = body.indexOf("BoxWithConstraints(", readoutAt)
        assertTrue(
            "the readout's own MimirChromeText must carry clearAndSetSemantics before the curve box",
            readoutAt in 0 until nextClearAndSetSemantics &&
                nextClearAndSetSemantics < nextComposableCallAfterReadout,
        )
    }

    @Test
    fun `the curve stateDescription carries the spoken readout between the current-setting clause and the axis sentence`() {
        val body = tweakerFanCurveRowSource()
        val currentSettingAt = body.indexOf("\" Current setting.\"")
        val spokenAt = body.indexOf("tweakerFanTemperatureSpokenText(gpuTempC, cpuTempC)")
        val axisAt = body.indexOf("Fan speed increases upward")
        assertTrue("the draft/current clause must be found", currentSettingAt >= 0)
        assertTrue("the spoken readout append must be found", spokenAt >= 0)
        assertTrue("the axis sentence must be found", axisAt >= 0)
        assertTrue(
            "the spoken readout must sit between the current-setting clause and the axis sentence",
            currentSettingAt < spokenAt && spokenAt < axisAt,
        )
    }

    private fun tweakerFanCurveRowSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        )
        val source = candidates.firstOrNull(File::isFile)?.readText()
            ?: error("BrightnessOverlay.kt not found from ${File(".").absolutePath}")
        val start = source.indexOf("private fun TweakerFanCurveRow(")
        val end = source.indexOf("private fun TweakerFanCurvePreview(", start)
        require(start >= 0 && end > start) { "could not slice TweakerFanCurveRow's body" }
        return source.substring(start, end)
    }

    /** Drops `//`-prefixed comment lines so a structural pin scans code, not the prose above it. */
    private fun withoutLineComments(source: String): String =
        source.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    // ---- Retention-bound wiring pin -----------------------------------------------------------

    /**
     * [boundedRetainedFanTempC] is pinned above as a pure function, but under Robolectric
     * `TweakerBridge.utilityFanGpuTempC()`/`utilityFanCpuTempC()` are always null (no bound
     * telemetry session in this harness -- the same gap `AppBrightnessCoordinatorFanReadoutRetentionTest`
     * documents for `tweakerFanGpuTempC`), so no behavioural test can drive the bound through
     * `projectTweakerUtilityState()`. This is source characterization pinning that the published
     * `tweakerFanGpuTempC`/`tweakerFanCpuTempC` fields actually route through the bound -- not just
     * that a bounding function exists unused (an unused bounding function would not protect the published fields) -- the same pattern `PerformanceOverlayRowPermissionWiringTest` already uses in this
     * module.
     */
    @Test
    fun `projectTweakerUtilityState publishes both temperatures through the retention bound`() {
        val source = appBrightnessCoordinatorSource()

        assertTrue(
            "tweakerFanGpuTempC must be published through boundedRetainedFanTempC, not the raw " +
                "retained field",
            Regex("tweakerFanGpuTempC = boundedRetainedFanTempC\\(").containsMatchIn(source),
        )
        assertTrue(
            "tweakerFanCpuTempC must be published through boundedRetainedFanTempC, not the raw " +
                "retained field",
            Regex("tweakerFanCpuTempC = boundedRetainedFanTempC\\(").containsMatchIn(source),
        )
        assertFalse(
            "must not regress to the pre-bound direct assignment",
            source.contains("tweakerFanGpuTempC = retainedFanGpuTempC\n"),
        )
    }

    private fun appBrightnessCoordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("AppBrightnessCoordinator.kt not found from ${File(".").absolutePath}")
    }

    // ---- Three-hop parameter threading pin -----------------------------------------------------

    /**
     * `coordinator.tweakerFanCpuTempC` has to cross three composables to reach the row that renders
     * it: `AppBrightnessCoordinatorHost` -> `AppBrightnessHost` -> `MimirBrightnessOverlay` ->
     * `TweakerFanCurveRow`. A forgotten hop leaves the parameter declared but never actually fed
     * (a declared but unused parameter is another dead-code case) -- the CPU-source-symmetry test above cannot see this because it calls the
     * pure functions directly, bypassing the wiring entirely.
     */
    @Test
    fun `tweakerFanCpuTempC is threaded through every hop from the coordinator to the row`() {
        val coordinatorSource = appBrightnessCoordinatorSource()
        val hostSource = fileSource("AppBrightnessHost.kt")
        val overlaySource = fileSource("BrightnessOverlay.kt")

        assertTrue(
            "AppBrightnessCoordinatorHost must pass coordinator.tweakerFanCpuTempC to AppBrightnessHost",
            coordinatorSource.contains("tweakerFanCpuTempC = coordinator.tweakerFanCpuTempC"),
        )
        assertTrue(
            "AppBrightnessHost must declare the tweakerFanCpuTempC parameter",
            hostSource.contains("tweakerFanCpuTempC: Int? = null"),
        )
        assertTrue(
            "AppBrightnessHost must forward tweakerFanCpuTempC to MimirBrightnessOverlay unchanged",
            hostSource.contains("tweakerFanCpuTempC = tweakerFanCpuTempC"),
        )
        assertTrue(
            "MimirBrightnessOverlay must declare the tweakerFanCpuTempC parameter",
            overlaySource.contains("tweakerFanCpuTempC: Int? = null"),
        )
        assertTrue(
            "the TweakerFanCurveRow call site must pass both gpuTempC and cpuTempC from the " +
                "coordinator-published fields",
            overlaySource.contains("gpuTempC = tweakerFanGpuTempC") &&
                overlaySource.contains("cpuTempC = tweakerFanCpuTempC"),
        )
    }

    private fun fileSource(fileName: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$fileName not found from ${File(".").absolutePath}")
    }
}
