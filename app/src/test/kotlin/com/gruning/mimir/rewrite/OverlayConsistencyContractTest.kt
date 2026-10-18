package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import com.gruning.mimir.rewrite.core.ui.mimirStaticChrome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The consistency contract for the performance overlay plate and the Utility/Tweaker rail.
 *
 * Every assertion here exists because the same defect was found in shipped code: Compact rendered
 * GPU before CPU while Classic and Extended rendered CPU before GPU; Extended fused RAM/BATT/LEFT
 * into one undelimited blob while delimiting the four cells before them; the spoken description
 * announced battery level before battery discharge, inverting the visual order a fourth way; and
 * `BATT` meant a level in one mode and a level plus a charge state in another.
 *
 * The point of the tests below is not to pin those four corrections. It is to pin the two properties
 * that make the whole class unrepresentable: **cell order comes from one canonical sequence**, and
 * **the spoken order is generated from the same list the plate renders**.
 */
class OverlayConsistencyContractTest {

    /**
     * The canonical sequence, spelled out so a reorder has to be a deliberate edit to this line
     * rather than a side effect of touching a composable. It reads left to right as one sentence:
     * how it feels, why, what it costs, how long that leaves you, then diagnostics.
     */
    private val canonical = listOf(
        PerformanceOverlayCell.FPS,
        PerformanceOverlayCell.CPU,
        PerformanceOverlayCell.GPU,
        PerformanceOverlayCell.DRAW,
        PerformanceOverlayCell.BATT,
        PerformanceOverlayCell.LEFT,
        PerformanceOverlayCell.RAM,
    )

    @Test
    fun `the canonical sequence is FPS CPU GPU DRAW BATT LEFT RAM`() {
        assertEquals(canonical, PerformanceOverlayCell.entries.toList())
    }

    @Test
    fun `every mode renders an order-preserving subsequence of the canonical sequence`() {
        PerformanceOverlayMode.entries.forEach { mode ->
            val rendered = performanceOverlayCells(mode)

            assertEquals(
                "$mode renders a cell twice",
                rendered.size,
                rendered.toSet().size,
            )
            // Subsequence, not prefix: Compact legitimately carries BATT without DRAW. Walk the
            // canonical sequence once and require the rendered cells to appear in that same walk.
            var cursor = 0
            rendered.forEach { cell ->
                val found = canonical.subList(cursor, canonical.size).indexOf(cell)
                assertTrue(
                    "$mode renders $cell out of canonical order: $rendered",
                    found >= 0,
                )
                cursor += found + 1
            }
        }
    }

    @Test
    fun `each mode carries the cells its plate is meant to show`() {
        assertEquals(
            listOf(
                PerformanceOverlayCell.FPS,
                PerformanceOverlayCell.CPU,
                PerformanceOverlayCell.GPU,
                PerformanceOverlayCell.BATT,
            ),
            performanceOverlayCells(PerformanceOverlayMode.MINIMAL),
        )
        // Classic was already canonical before this work and is asserted so it stays that way.
        assertEquals(
            listOf(
                PerformanceOverlayCell.FPS,
                PerformanceOverlayCell.CPU,
                PerformanceOverlayCell.GPU,
                PerformanceOverlayCell.DRAW,
            ),
            performanceOverlayCells(PerformanceOverlayMode.CLASSIC),
        )
        assertEquals(canonical, performanceOverlayCells(PerformanceOverlayMode.DETAILED))
    }

    @Test
    fun `the plate measures every cell it renders and no cell it does not`() {
        PerformanceOverlayMode.entries.forEach { mode ->
            performanceOverlayCells(mode).forEach { cell ->
                assertTrue(
                    "$mode has no maximum for $cell",
                    performanceOverlayMetricMaximum(mode, cell).isNotEmpty(),
                )
            }
            (PerformanceOverlayCell.entries - performanceOverlayCells(mode).toSet()).forEach { cell ->
                val absent = runCatching { performanceOverlayMetricMaximum(mode, cell) }.isFailure
                assertTrue("$mode claims a slot for the $cell it never renders", absent)
            }
        }
    }

    @Test
    fun `the spoken description follows the same order and the same cells as the plate`() {
        PerformanceOverlayMode.entries.forEach { mode ->
            val description = performanceOverlayDescription(telemetry(), environment(), mode)
            val rendered = performanceOverlayCells(mode)

            val spokenPositions = rendered.map { cell ->
                val at = description.indexOf(performanceOverlayCellSpokenName(cell))
                assertTrue("$mode never announces $cell: $description", at >= 0)
                at
            }
            assertEquals(
                "$mode announces its cells out of visual order: $description",
                spokenPositions.sorted(),
                spokenPositions,
            )
            // A mode must not announce readings it does not show — Compact used to describe CPU
            // busy percentage and clock speed that were nowhere on its plate.
            (PerformanceOverlayCell.entries - rendered.toSet()).forEach { cell ->
                assertEquals(
                    "$mode announces the $cell it never renders: $description",
                    -1,
                    description.indexOf(performanceOverlayCellSpokenName(cell)),
                )
            }
        }
    }

    @Test
    fun `the spoken description names CPU and GPU as the same hottest-zone aggregation`() {
        // retired the CPU-mean-vs-GPU-hottest asymmetry this test used to pin
        // (see performanceOverlayCpuTemperature's KDoc): both cells are now the hottest matching zone,
        // formatted identically on the plate AND named the same way in speech. The cell prefix (CPU/
        // GPU) is what keeps the two spoken names from being byte-identical, not an aggregation
        // difference anymore -- there isn't one left to tell apart.
        val description = performanceOverlayDescription(
            telemetry(cpuPackageTempC = 61, gpuTempC = 78),
            environment(),
            PerformanceOverlayMode.CLASSIC,
        )

        // cpuPackageTempC is passed and ignored here on purpose -- this cell no longer reads it.
        assertTrue(description, description.contains("CPU, hottest thermal zone"))
        assertTrue(description, description.contains("GPU, hottest thermal zone"))
        assertTrue(description, description.contains("82°C"))
        assertTrue(description, description.contains("78°C"))
        assertTrue(
            "CPU and GPU must keep distinct spoken names even though the aggregation is now identical",
            performanceOverlayCellSpokenName(PerformanceOverlayCell.CPU) !=
                performanceOverlayCellSpokenName(PerformanceOverlayCell.GPU),
        )
    }

    /** Speech expands the short plate labels with the measured subject and scope. */
    @Test
    fun `the spoken names state total-device CPU and GameNative renderer frames`() {
        val cpu = performanceOverlayCellSpokenName(PerformanceOverlayCell.CPU)
        val fps = performanceOverlayCellSpokenName(PerformanceOverlayCell.FPS)
        val gpu = performanceOverlayCellSpokenName(PerformanceOverlayCell.GPU)

        assertTrue(cpu, cpu.contains("whole device"))
        assertTrue(fps, fps.contains("GameNative"))
        assertTrue("unsupported game coverage must be explicit", fps.contains("other games unavailable"))
        assertTrue(gpu, gpu.contains("whole device"))
    }

    @Test
    fun `BATT is a battery level in every mode that carries it`() {
        val environment = environment(batteryPercent = 73, batteryStatus = "Discharging")
        val carriers = PerformanceOverlayMode.entries
            .filter { PerformanceOverlayCell.BATT in performanceOverlayCells(it) }

        // Both Compact and Extended carry it; the defect was that they meant different things by it.
        assertTrue(carriers.containsAll(listOf(PerformanceOverlayMode.MINIMAL, PerformanceOverlayMode.DETAILED)))
        carriers.forEach { mode ->
            assertEquals(
                "$mode renders BATT as something other than a level",
                "73%",
                performanceOverlayCellValue(
                    PerformanceOverlayCell.BATT,
                    mode,
                    telemetry(batteryStatus = "Discharging"),
                    environment,
                ),
            )
            assertEquals(
                "100%",
                performanceOverlayMetricMaximum(mode, PerformanceOverlayCell.BATT),
            )
        }
        // The charge state the Extended cell used to carry visually is not lost, only moved.
        assertTrue(
            performanceOverlayDescription(
                telemetry(batteryStatus = "Discharging"),
                environment,
                PerformanceOverlayMode.DETAILED,
            ).contains("Battery level 73%, discharging."),
        )
    }

    @Test
    fun `plate width is measured from the cells the mode actually renders`() {
        val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.5f }
        PerformanceOverlayMode.entries.forEach { mode ->
            val width = performanceOverlayContentWidthDp(mode, 1f, measure)
            assertTrue("$mode measured a non-positive plate", width > 0f)
        }
        // Extended carries every cell, so it can never measure narrower than the modes it contains.
        val compact = performanceOverlayContentWidthDp(PerformanceOverlayMode.MINIMAL, 1f, measure)
        val classic = performanceOverlayContentWidthDp(PerformanceOverlayMode.CLASSIC, 1f, measure)
        val extended = performanceOverlayContentWidthDp(PerformanceOverlayMode.DETAILED, 1f, measure)
        assertTrue(extended > classic)
        assertTrue(extended > compact)
    }

    @Test
    fun `the Tweaker rail emits an order-preserving subsequence of its control order`() {
        // Same principle as the plate: the rail's capability gates decide which rows exist, never
        // what order they come in, so no capability combination can invert two rows.
        val fanCapabilities = listOf(
            null,
            manualFan(TweakerFanMode.AUTO),
            manualFan(TweakerFanMode.MANUAL),
        )
        val accessStates = TweakerUsageAccessState.entries

        fanCapabilities.forEach { fan ->
            accessStates.forEach { access ->
                val emitted = brightnessControls(
                    state = brightnessState(),
                    tweakerUsageAccessState = access,
                    surface = UtilitySurface.TWEAKER,
                    tweakerManualFan = fan,
                )
                val declared = BrightnessControlKey.entries
                var cursor = 0
                emitted.forEach { control ->
                    val found = declared.subList(cursor, declared.size).indexOf(control)
                    assertTrue(
                        "Tweaker rail emits $control out of control order: $emitted",
                        found >= 0,
                    )
                    cursor += found + 1
                }
            }
        }
    }

    @Test
    fun `fan mode resolves through a display label rather than leaking its enum constant`() {
        assertEquals("AUTO", tweakerFanModeValueLabel(TweakerFanMode.AUTO))
        assertEquals("MANUAL", tweakerFanModeValueLabel(TweakerFanMode.MANUAL))
    }

    /**
     * Device frames 2026-10-13: the row said "THE DEVICE'S OWN FAN CONTROLLER KEEPS THE SPEED IN STEP WITH TEMPERATURE" beside AUTO while the Balanced profile
     * held `fan_mode=6`, Mimir's own duty loop. The sentence has to name whoever is actually driving the fan, and the row knows that:
     * [TweakerManualFanCapability.hardwareDutyOwned] is the daemon's attestation of live hardware (true = Mimir's loop, false = the vendor's SMART mode, null = an older
     * Tweaker that cannot say). Every state the row can show is pinned here, word for word.
     */
    @Test
    fun `fan mode says who is driving the fan in every state the row can show`() {
        fun auto(owned: Boolean?) = manualFan(TweakerFanMode.AUTO).copy(hardwareDutyOwned = owned)
        fun manual(owned: Boolean?) = manualFan(TweakerFanMode.MANUAL).copy(hardwareDutyOwned = owned)

        // AUTO, Mimir's loop holds the duty (the Thor on Efficiency, Balanced or Performance: fan_mode 5 or 6).
        assertEquals(
            "Mimir's fan curve for this profile keeps the speed in step with temperature.",
            tweakerFanModeSupportingText(auto(owned = true)),
        )
        // AUTO, the hardware sits on the vendor's SMART mode: the device's own controller really is in charge, so the old sentence is still the true one here.
        assertEquals(
            "The device's own fan controller keeps the speed in step with temperature.",
            tweakerFanModeSupportingText(auto(owned = false)),
        )
        // AUTO from a Tweaker that cannot attest: neither claim can be made, so only what is true of both.
        assertEquals("The fan speed follows temperature automatically.", tweakerFanModeSupportingText(auto(owned = null)))
        // MANUAL is an instruction and is true whoever drives; the parser never lets the vendor's mode be MANUAL, but the null and true cases both reach it.
        for (owned in listOf(true, null)) {
            assertEquals("Choose a cooler or quieter response, then apply.", tweakerFanModeSupportingText(manual(owned)))
        }

        // The truth rule, not just the strings: the vendor's controller is named only when the hardware is on the vendor's mode, Mimir's curve only when Mimir holds the duty.
        for (owned in listOf(true, false, null)) {
            val text = tweakerFanModeSupportingText(auto(owned))
            assertEquals("the device's controller is claimed only when owned=$owned is false", owned == false, text.contains("device's own fan controller"))
            assertEquals("Mimir's curve is claimed only when owned=$owned is true", owned == true, text.contains("Mimir's fan curve"))
            assertTrue("every AUTO sentence ends a sentence: $text", text.endsWith("."))
        }
    }

    @Test
    fun `fan mode's spoken state is its name and then the very same sentence`() {
        for (owned in listOf(true, false, null)) {
            val auto = manualFan(TweakerFanMode.AUTO).copy(hardwareDutyOwned = owned)
            assertEquals("Auto. " + tweakerFanModeSupportingText(auto), tweakerFanModeStateDescription(auto))
            val manual = manualFan(TweakerFanMode.MANUAL).copy(hardwareDutyOwned = owned)
            assertEquals("Manual. Choose a cooler or quieter response, then apply.", tweakerFanModeStateDescription(manual))
        }
        assertEquals(
            "Auto. Mimir's fan curve for this profile keeps the speed in step with temperature.",
            tweakerFanModeStateDescription(manualFan(TweakerFanMode.AUTO).copy(hardwareDutyOwned = true)),
        )
    }

    /**
     * A duty-owned-only contract (Sport/Manual owned, but manual PWM entry unqualified) can
     * advertise `fan.modes == {AUTO}`. The row must never offer a step into a mode the service
     * didn't advertise, even though `AppBrightnessCoordinator.stepTweakerFanMode` already no-ops
     * an out-of-`modes` step safely at the mutation layer -- showing a step that no-ops is the
     * fake mode change this row exists to avoid.
     */
    @Test
    fun `fan mode row never offers a step into a mode the service did not advertise`() {
        val autoOnly = manualFan(TweakerFanMode.AUTO, modes = setOf(TweakerFanMode.AUTO))
        assertFalse(
            "no MANUAL to step forward into when only AUTO was advertised",
            tweakerFanModeCanStepRight(autoOnly),
        )
        assertFalse(
            "already at AUTO, and AUTO is the only advertised mode",
            tweakerFanModeCanStepLeft(autoOnly),
        )

        val full = manualFan(TweakerFanMode.AUTO)
        assertTrue(
            "MANUAL is advertised, so stepping forward from AUTO must remain available",
            tweakerFanModeCanStepRight(full),
        )
        assertFalse(
            "already at AUTO -- nothing to step left into",
            tweakerFanModeCanStepLeft(full),
        )

        val fullAtManual = manualFan(TweakerFanMode.MANUAL)
        assertTrue(
            "AUTO is advertised, so stepping back from MANUAL must remain available",
            tweakerFanModeCanStepLeft(fullAtManual),
        )
        assertFalse(
            "already at MANUAL -- nothing to step right into",
            tweakerFanModeCanStepRight(fullAtManual),
        )
    }

    /**
     * The caps system renders chrome in full caps while speaking the authored string
     * ([mimirStaticChrome] is its visual half), so a literal typed in caps looks identical and only
     * changes what TalkBack says. This is therefore a source assertion rather than a behavioural
     * one: the rule it protects is about how the copy is *authored*, and there is no Compose test
     * harness in this module to read a rendered semantics tree with.
     *
     * Scope is the Utility/Tweaker rail only. The overlay plate's own labels (`FPS`, `CPU`) are
     * metric abbreviations rendered by plain Text and are deliberately not covered.
     */
    @Test
    fun `rail chrome is authored in natural case and never pre-uppercased`() {
        val source = railSource()
        assumeTrue("BrightnessOverlay.kt not reachable from the test working directory", source != null)

        val shouting = stripComments(source!!)
            .lineSequence()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.startsWith("label = \"") ||
                    trimmed.startsWith("text = \"") ||
                    trimmed.contains("UtilitySectionLabel(\"")
            }
            .flatMap { line -> QUOTED.findAll(line).map { it.groupValues[1] } }
            .filter { literal -> literal.any(Char::isLetter) && literal == literal.uppercase() }
            .toList()

        assertEquals(
            "pre-uppercased chrome renders identically but makes TalkBack shout: $shouting",
            emptyList<String>(),
            shouting,
        )
        // And the renderer really is the thing supplying the caps, so the fix costs no visual delta.
        assertEquals("FAN MODE", mimirStaticChrome("Fan mode"))
        assertEquals("FRAME CAP", mimirStaticChrome("Frame cap"))
    }

    private fun railSource(): String? = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
    ).firstOrNull { it.isFile }?.readText()

    /** Comments quote the old caps literals by design; reading them would measure documentation. */
    private fun stripComments(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }

    private fun manualFan(
        mode: TweakerFanMode,
        modes: Set<TweakerFanMode> = TweakerFanMode.entries.toSet(),
    ) = TweakerManualFanCapability(
        modes = modes,
        curveBiasRange = -100..100,
        curveBiasStep = 10,
        curveSensorType = "GPU",
        currentMode = mode,
        currentCurveBias = 0,
        ownerRequestId = "mimir.system.1",
        generation = 1,
        transactionId = "tx-1",
        profileId = "quiet",
    )

    /** The rail's shape here comes from the Tweaker gates, so the display half is left at defaults. */
    private fun brightnessState() = BrightnessState(
        capabilities = BrightnessCapabilities(
            canWriteSystemSettings = true,
            mainSystemBrightnessActive = true,
            secondaryWindowAvailable = false,
            secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
            joystickLightSupported = false,
            joystickLightColorSupported = false,
            mimirHapticsSupported = false,
            mimirHapticAmplitudeControl = false,
            mediaVolumeSupported = false,
            mediaVolumeFixed = false,
            mainWriteAccessUseful = true,
            privilegedUtilityPosture = PrivilegedUtilityPosture.Unsupported,
            ambientSoundSupported = false,
        ),
        primaryFraction = 0.5f,
        primaryWindowFallback = null,
        secondaryFraction = null,
        secondaryWindowFallback = null,
        joystickLightFraction = null,
        joystickColorFraction = null,
        joystickColorArgb = null,
        themeAwareJoystickLight = false,
        mimirHapticFraction = null,
        mediaVolumeFraction = null,
        ambientSoundFraction = null,
    )

    private fun environment(
        batteryPercent: Int? = 73,
        batteryStatus: String? = null,
    ) = PerformanceOverlayEnvironment(
        displayRefreshRateHz = 60f,
        ramUsedBytes = 4L * 1024L * 1024L * 1024L,
        ramTotalBytes = 8L * 1024L * 1024L * 1024L,
        batteryPercent = batteryPercent,
        batteryStatus = batteryStatus,
        batteryEstimatedRemainingMs = 125L * 60L * 1_000L,
    )

    private fun telemetry(
        cpuPackageTempC: Int? = 61,
        gpuTempC: Int? = 78,
        batteryStatus: String? = null,
    ) = TweakerTelemetry(
        sampledAtElapsedRealtimeMs = 1_000L,
        foregroundPackageName = null,
        frameStatus = "AVAILABLE",
        frameAverageFpsMilli = 59_940,
        cpuTempC = 82,
        gpuTempC = gpuTempC,
        cpuBusyPercent = 42,
        gpuBusyPercent = 30,
        batteryPowerMw = -2_100,
        batteryStatus = batteryStatus,
        cpuPackageTempC = cpuPackageTempC,
        cpuPolicyClocksHz = listOf(1_996_800_000L, 2_246_400_000L),
        gpuClockHz = 800_000_000L,
    )

    private companion object {
        private val QUOTED = Regex("\"([^\"]*)\"")
    }
}
