package com.gruning.mimir.tweaker

import android.os.PowerManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A strictly cooler candidate may re-arm while hot: no axis hotter and at least one lower.
 * Each axis that is not cooler, or is unreadable, refuses with its own token. [ApplyAdmission]
 * waives only the Android status trip for an admitting candidate; silicon trips stay absolute.
 * A vendor-owned fan plan is unchanged only over a vendor-run fan. The Manual curve is judged
 * with the live bias, a running hold refuses outright, and a fan-mode change inside a session
 * may only raise cooling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CoolerRearmAdmissionTest {
    // -- The matrix -------------------------------------------------------------------------------

    @Test
    fun `a profile cooler on one axis and no hotter on any is admitted`() {
        assertNull(CoolerRearmAdmission.refusal(balanced(), hotStock(), odin()))
    }

    @Test
    fun `a plan equal to the live state on every axis is refused -- it would yield a hold for nothing`() {
        val sameAsLive = balanced().copy(
            cpuMaxKhz = mapOf(0 to 2_246_400, 6 to 3_187_200),
            gpuMaxPowerLevel = 0,
            vendorMode = 2,
            fanMode = TuningPlanner.FAN_SMART,
        )
        assertEquals(CoolerRearmAdmission.NOT_STRICTLY_COOLER, CoolerRearmAdmission.refusal(sameAsLive, hotStock(), odin()))
        val stock = sameAsLive.copy(profileId = "stock", cpuMaxKhz = emptyMap(), gpuMaxPowerLevel = null, vendorMode = null, fanMode = null)
        assertEquals(CoolerRearmAdmission.NOT_STRICTLY_COOLER, CoolerRearmAdmission.refusal(stock, hotStock(), odin()))
    }

    @Test
    fun `a plan or a live state that cannot be read refuses`() {
        assertEquals(CoolerRearmAdmission.PLAN_UNAVAILABLE, CoolerRearmAdmission.refusal(null, hotStock(), odin()))
        assertEquals(CoolerRearmAdmission.LIVE_STATE_UNREADABLE, CoolerRearmAdmission.refusal(balanced(), null, odin()))
    }

    @Test
    fun `a CPU ceiling above the live ceiling on any policy refuses, a missing live policy refuses`() {
        val raisesPrime = balanced().copy(cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 3_532_800))
        assertEquals(CoolerRearmAdmission.CPU_CEILING_ABOVE_LIVE, CoolerRearmAdmission.refusal(raisesPrime, hotStock(), odin()))
        assertNull(
            "equal CPU ceilings are not hotter; the vendor mode still makes this plan cooler",
            CoolerRearmAdmission.refusal(balanced().copy(cpuMaxKhz = mapOf(0 to 2_246_400, 6 to 3_187_200)), hotStock(), odin()),
        )
        assertEquals(
            CoolerRearmAdmission.CPU_CEILING_UNREADABLE,
            CoolerRearmAdmission.refusal(balanced(), hotStock().copy(cpuCeilingKhz = mapOf(0 to 2_246_400)), odin()),
        )
    }

    @Test
    fun `the GPU ceiling is compared on the inverted power-level index`() {
        // Live max_pwrlevel 2; a plan at level 0 is FASTER (a higher ceiling), level 2 equal, level 5 slower.
        assertEquals(
            CoolerRearmAdmission.GPU_CEILING_ABOVE_LIVE,
            CoolerRearmAdmission.refusal(balanced().copy(gpuMaxPowerLevel = 0), hotStock().copy(gpuMaxPowerLevel = 2), odin()),
        )
        assertNull(CoolerRearmAdmission.refusal(balanced().copy(gpuMaxPowerLevel = 2), hotStock().copy(gpuMaxPowerLevel = 2), odin()))
        assertNull(
            "a slower GPU alone makes an otherwise-equal plan strictly cooler",
            CoolerRearmAdmission.refusal(equalToLive().copy(gpuMaxPowerLevel = 5), hotStock().copy(gpuMaxPowerLevel = 2), odin()),
        )
        assertEquals(
            CoolerRearmAdmission.GPU_CEILING_UNREADABLE,
            CoolerRearmAdmission.refusal(balanced().copy(gpuMaxPowerLevel = 5), hotStock().copy(gpuMaxPowerLevel = null), odin()),
        )
        assertNull(
            "a plan that writes no GPU ceiling passes the axis",
            CoolerRearmAdmission.refusal(balanced().copy(gpuMaxPowerLevel = null), hotStock().copy(gpuMaxPowerLevel = null, gpuMinPowerLevel = null), odin()),
        )
    }

    @Test
    fun `under the devfreq transport the GPU ceiling is compared in hertz`() {
        val devfreq = odin().copy(gpuCeilingPath = HardwareProbe.GPU_DEVFREQ_MAX_FREQ)
        // Level 2 on the 7-row table is row 4 = 443 MHz; live policy at 389 MHz is lower -> refuse.
        assertEquals(
            CoolerRearmAdmission.GPU_CEILING_ABOVE_LIVE,
            CoolerRearmAdmission.refusal(balanced().copy(gpuMaxPowerLevel = 2), hotStock().copy(gpuDevfreqMaxHz = 389_000_000), devfreq),
        )
        assertNull(CoolerRearmAdmission.refusal(balanced().copy(gpuMaxPowerLevel = 2), hotStock().copy(gpuDevfreqMaxHz = 443_000_000, gpuMinPowerLevel = null), devfreq))
        assertNull(
            "a lower policy in hertz is the cooler axis",
            CoolerRearmAdmission.refusal(equalToLive().copy(gpuMaxPowerLevel = 2), hotStock().copy(gpuDevfreqMaxHz = 525_000_000), devfreq),
        )
        assertEquals(
            CoolerRearmAdmission.GPU_CEILING_UNREADABLE,
            CoolerRearmAdmission.refusal(balanced().copy(gpuMaxPowerLevel = 2), hotStock().copy(gpuDevfreqMaxHz = null), devfreq),
        )
    }

    @Test
    fun `the GPU floor refuses only when a profile-supplied floor would sit above the live one`() {
        // No profile floor is enforceable today (TuningPlanner.ODIN_3_BALANCED_GPU_FLOOR_ENFORCEABLE),
        // so the renderer only ever keeps or lowers the captured floor -- but the live floor must be
        // readable before that can be relied on.
        assertNull(CoolerRearmAdmission.refusal(balanced(), hotStock().copy(gpuMinPowerLevel = 6), odin()))
        assertEquals(
            CoolerRearmAdmission.GPU_FLOOR_UNREADABLE,
            CoolerRearmAdmission.refusal(balanced(), hotStock().copy(gpuMinPowerLevel = null), odin()),
        )
    }

    @Test
    fun `the vendor mode must not sit above the live mode -- the floors follow it`() {
        // The Odin at 23:31: live mode 2 (High performance, 4.2 GHz floors); Balanced plans mode 1.
        assertNull(CoolerRearmAdmission.refusal(equalToLive().copy(vendorMode = 1), hotStock().copy(vendorPerformanceMode = 2), odin()))
        assertNull(CoolerRearmAdmission.refusal(balanced().copy(vendorMode = 2), hotStock().copy(vendorPerformanceMode = 2), odin()))
        assertEquals(
            CoolerRearmAdmission.VENDOR_MODE_RAISES_FLOORS,
            CoolerRearmAdmission.refusal(balanced().copy(vendorMode = 2), hotStock().copy(vendorPerformanceMode = 1), odin()),
        )
        assertEquals(
            CoolerRearmAdmission.VENDOR_MODE_UNREADABLE,
            CoolerRearmAdmission.refusal(balanced().copy(vendorMode = 1), hotStock().copy(vendorPerformanceMode = null), odin()),
        )
        assertNull(
            "a plan that leaves the mode alone passes the axis",
            CoolerRearmAdmission.refusal(balanced().copy(vendorMode = null), hotStock().copy(vendorPerformanceMode = null), odin()),
        )
    }

    @Test
    fun `a vendor-owned fan plan is no change only over a vendor-run fan`() {
        // SMART, or no fan write at all, with the vendor already running the fan: equal.
        assertNull(CoolerRearmAdmission.refusal(balanced().copy(fanMode = TuningPlanner.FAN_SMART), hotStock(), odin()))
        assertNull(CoolerRearmAdmission.refusal(balanced().copy(fanMode = null), hotStock(), odin()))
        // P1-2: over a Mimir-owned duty (a post-control hold at full) it is a hand-over to a loop
        // that ran 37-47 % at 103 C on the Odin -- never cooler.
        val heldFull = hotStock().copy(vendorFanMode = TuningPlanner.FAN_MANUAL, fanDuty = 25_000)
        assertEquals(CoolerRearmAdmission.FAN_MODE_NOT_COOLER, CoolerRearmAdmission.refusal(balanced().copy(fanMode = TuningPlanner.FAN_SMART), heldFull, odin()))
        assertEquals(CoolerRearmAdmission.FAN_MODE_NOT_COOLER, CoolerRearmAdmission.refusal(balanced().copy(fanMode = null), heldFull, odin()))
        assertEquals(
            CoolerRearmAdmission.FAN_MODE_UNREADABLE,
            CoolerRearmAdmission.refusal(balanced(), hotStock().copy(vendorFanMode = null), odin()),
        )
    }

    @Test
    fun `a Mimir-owned fan plan must command at least the live duty, judged with the live bias`() {
        // MANUAL on balanced at 103 C: the curve is at 100% -- above the vendor's 47%.
        val manual = balanced().copy(fanMode = TuningPlanner.FAN_MANUAL)
        assertNull(CoolerRearmAdmission.refusal(manual, hotStock(), odin()))
        // MANUAL at 40 C: the curve commands 26% (23% x 1.15) -- below a live 47% duty.
        assertEquals(
            CoolerRearmAdmission.FAN_DUTY_BELOW_LIVE,
            CoolerRearmAdmission.refusal(manual, hotStock().copy(cpuTempC = 40), odin()),
        )
        // P3-7: the bias the loop will run moves the verdict. Live 30%: neutral commands 26%
        // (below); the full Cooler bias evaluates eight degrees hotter, 37% (above).
        val live30 = hotStock().copy(cpuTempC = 40, fanDuty = 7_500)
        assertEquals(CoolerRearmAdmission.FAN_DUTY_BELOW_LIVE, CoolerRearmAdmission.refusal(manual, live30, odin(), bias = FanCurveBias(0)))
        assertNull(CoolerRearmAdmission.refusal(manual, live30, odin(), bias = FanCurveBias(-100)))
        // Equal duty is not hotter; the CPU ceilings still make the plan cooler.
        assertNull(CoolerRearmAdmission.refusal(manual, hotStock().copy(cpuTempC = 40, fanDuty = 6_500), odin()))
        // SILENT stops the vendor loop and drives nothing: never cooler.
        assertEquals(
            CoolerRearmAdmission.FAN_MODE_NOT_COOLER,
            CoolerRearmAdmission.refusal(balanced().copy(fanMode = TuningPlanner.FAN_SILENT), hotStock(), odin()),
        )
        // Unreadable duty, period or temperature: refuse.
        assertEquals(CoolerRearmAdmission.FAN_DUTY_UNREADABLE, CoolerRearmAdmission.refusal(manual, hotStock().copy(fanDuty = null), odin()))
        assertEquals(CoolerRearmAdmission.FAN_DUTY_UNREADABLE, CoolerRearmAdmission.refusal(manual, hotStock().copy(fanPeriod = 0), odin()))
        assertEquals(CoolerRearmAdmission.FAN_DUTY_UNREADABLE, CoolerRearmAdmission.refusal(manual, hotStock().copy(cpuTempC = null), odin()))
    }

    @Test
    fun `the first axis that is not cooler names itself, in a fixed order`() {
        val everythingWrong = balanced().copy(
            cpuMaxKhz = mapOf(0 to 9_000_000, 6 to 9_000_000),
            gpuMaxPowerLevel = 0,
            vendorMode = 2,
            fanMode = TuningPlanner.FAN_SILENT,
        )
        assertEquals(
            CoolerRearmAdmission.CPU_CEILING_ABOVE_LIVE,
            CoolerRearmAdmission.refusal(everythingWrong, hotStock().copy(vendorPerformanceMode = 1), odin()),
        )
    }

    // -- A fan-mode change inside a session -----------------------------------------------

    @Test
    fun `at SEVERE a fan change that only raises cooling is admitted, one that lowers it is refused`() {
        // Smart -> Manual full at 103 C: 100% over the vendor's 47%.
        assertNull(CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_MANUAL, "balanced", hotStock(), FanCurveBias(0)))
        // Manual -> Smart over a Mimir-owned duty hands the fan to a loop that runs lower.
        val mimirOwned = hotStock().copy(vendorFanMode = TuningPlanner.FAN_MANUAL, fanDuty = 25_000)
        assertEquals(
            CoolerRearmAdmission.FAN_MODE_NOT_COOLER,
            CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_SMART, "balanced", mimirOwned, FanCurveBias(0)),
        )
        // Smart while the vendor already runs the fan changes nothing: admitted.
        assertNull(CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_SMART, "balanced", hotStock(), FanCurveBias(0)))
        // Manual whose curve sits below the live duty lowers cooling: refused; at the same duty: admitted.
        assertEquals(
            CoolerRearmAdmission.FAN_DUTY_BELOW_LIVE,
            CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_MANUAL, "balanced", hotStock().copy(cpuTempC = 40), FanCurveBias(0)),
        )
        assertNull(CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_MANUAL, "balanced", hotStock().copy(cpuTempC = 40, fanDuty = 6_500), FanCurveBias(0)))
        // The request's own bias is what the loop will run.
        assertNull(CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_MANUAL, "balanced", hotStock().copy(cpuTempC = 40, fanDuty = 7_500), FanCurveBias(-100)))
        // Fail closed.
        assertEquals(CoolerRearmAdmission.LIVE_STATE_UNREADABLE, CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_MANUAL, "balanced", null, FanCurveBias(0)))
        assertEquals(
            CoolerRearmAdmission.FAN_MODE_UNREADABLE,
            CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_MANUAL, "balanced", hotStock().copy(vendorFanMode = null), FanCurveBias(0)),
        )
        assertEquals(
            CoolerRearmAdmission.FAN_DUTY_UNREADABLE,
            CoolerRearmAdmission.fanTransitionRefusal(TuningPlanner.FAN_MANUAL, "balanced", hotStock().copy(fanDuty = null), FanCurveBias(0)),
        )
    }

    // -- The hook in ApplyAdmission ----------------------------------------------------------------

    @Test
    fun `at SEVERE a structurally cooler candidate is admitted and said out loud`() {
        ShadowLog.clear()
        val result = ApplyAdmission.evaluate(usageAccessGranted = true, telemetry = severe(cpuTempC = 103), coolerRearm = { null })
        assertEquals(ApplyAdmissionResult.Admitted, result)
        assertTrue(ShadowLog.getLogsForTag("MimirApplyAdmission").any { it.msg.startsWith("THERMAL_ANDROID_COOLER_REARM_ADMITTED") })
    }

    @Test
    fun `at SEVERE a candidate that is not cooler stays refused, with the axis in the log`() {
        ShadowLog.clear()
        val result = ApplyAdmission.evaluate(
            usageAccessGranted = true,
            telemetry = severe(cpuTempC = 103),
            coolerRearm = { CoolerRearmAdmission.VENDOR_MODE_RAISES_FLOORS },
        ) as ApplyAdmissionResult.Rejected
        assertEquals("TELEMETRY_ADMISSION_FAILED", result.code)
        assertTrue(ShadowLog.getLogsForTag("MimirApplyAdmission").any { it.msg.contains("reason=THERMAL_ANDROID/VENDOR_MODE_RAISES_FLOORS") })
    }

    @Test
    fun `without a comparison the status trip refuses exactly as before`() {
        val result = ApplyAdmission.evaluate(usageAccessGranted = true, telemetry = severe(cpuTempC = 60)) as ApplyAdmissionResult.Rejected
        assertEquals("TELEMETRY_ADMISSION_FAILED", result.code)
        assertTrue(result.message.endsWith("THERMAL_ANDROID"))
        val systemWide = ApplyAdmission.evaluateSystemWide(telemetry = severe(cpuTempC = 60)) as ApplyAdmissionResult.Rejected
        assertEquals("TELEMETRY_ADMISSION_FAILED", systemWide.code)
    }

    @Test
    fun `status-trip waiver preserves absolute CPU and GPU cooler-candidate rejection`() {
        val cpu = ApplyAdmission.evaluate(usageAccessGranted = true, telemetry = severe(cpuTempC = 106), coolerRearm = { null }) as ApplyAdmissionResult.Rejected
        assertTrue(cpu.message.endsWith("THERMAL_CPU"))
        val gpu = ApplyAdmission.evaluateSystemWide(telemetry = severe(cpuTempC = 103).copy(gpuTempC = 106), coolerRearm = { null }) as ApplyAdmissionResult.Rejected
        assertTrue(gpu.message.endsWith("THERMAL_GPU"))
        assertEquals(ApplyAdmissionResult.Admitted, ApplyAdmission.evaluateSystemWide(telemetry = severe(cpuTempC = 103), coolerRearm = { null }))
    }

    @Test
    fun `below the trip the comparison is never consulted`() {
        var consulted = 0
        val mild = severe(cpuTempC = 60).copy(androidThermalStatus = PowerManager.THERMAL_STATUS_MODERATE)
        assertEquals(ApplyAdmissionResult.Admitted, ApplyAdmission.evaluate(usageAccessGranted = true, telemetry = mild, coolerRearm = { consulted++; "NEVER" }))
        assertEquals(0, consulted)
    }

    // -- Wiring pins -------------------------------------------------------------------------------

    @Test
    fun `the apply admission refuses under a running hold, then judges the planner's candidate with the live bias`() {
        val source = stripLineComments(mainSourceText("MimirTweakerService.kt"))
        val holdGate = "if (!dryRun() && postControlHoldController().isHeld()) {"
        val admit = sliceBetween(source, holdGate, "val admission = if (sessionScope == ActiveSessionScope.SYSTEM) {")
        assertTrue(
            "the hold is the first thing the admit lambda judges",
            source.substring(0, source.indexOf(holdGate)).trimEnd().endsWith("admit = {"),
        )
        val telemetry = admit.indexOf("val telemetry = thermalTelemetryForAdmission")
        assertTrue("the hold is judged before any telemetry", telemetry > 0)
        assertTrue(admit.contains("return@run ApplyAdmissionResult.Rejected(") && admit.contains("code = POST_CONTROL_HOLD_ACTIVE_CODE,"))
        assertTrue(admit.contains("TuningPlanner().plan("))
        assertTrue(admit.contains("fanIntent = admissionFanIntent(live)"))
        assertTrue(admit.contains("LiveRearmState.from(probe.telemetry(live))"))
        assertTrue("P3-7: the Manual curve is judged with the bias the loop will run", admit.contains("bias = FanCurveBias(fanControlSelections.preferenceFor(profileId).curveBias),"))
        assertTrue("an unreadable live state must refuse", admit.contains("getOrElse { CoolerRearmAdmission.LIVE_STATE_UNREADABLE }"))
        assertTrue("P1-2: the body learns the waiver was used", admit.contains(".also { refusal -> if (refusal == null) onCoolerRearmWaived() }"))
        assertTrue(source.contains("ApplyAdmission.evaluateSystemWide(telemetry, supervisorPolicy, coolerRearm)"))
        assertTrue(source.contains("coolerRearm = coolerRearm,"))
        assertTrue(
            "the select path must judge the fan intent its body will choose",
            source.contains("if (manualFanPlanEligible(profileId, live) && !dryRun()) {"),
        )
    }

    @Test
    fun `a waived select-path apply refuses every fallback to the auto plan instead of running it`() {
        val source = stripLineComments(mainSourceText("MimirTweakerService.kt"))
        val body = sliceBetween(source, "onCoolerRearmWaived = { coolerRearmWaived = true },", "}.also(::observePostTransactionDisposition)")
        val refusal = sliceBetween(body, "fun fallbackOrRefuse(code: String): TransactionResult {", "if (!manualFanPlanEligible(profileId, operationCapabilities)) {")
        assertTrue(refusal.contains("if (!coolerRearmWaived) return autoApply()"))
        assertTrue(refusal.contains("code = COOLER_REARM_FALLBACK_REFUSED_CODE,"))
        assertTrue("the fallback code is still recorded for the disposition", refusal.contains("selectPathFanEntryFallbackCode = code"))
        val afterDecision = body.substringAfter("val liveFanModeBeforeEntry = probe.fanModeRaw()?.toIntOrNull()")
        assertEquals(
            "every fallback after the Manual decision goes through the refusal",
            5,
            afterDecision.split("fallbackOrRefuse(").size - 1,
        )
        assertEquals("no fallback after the Manual decision runs autoApply() directly", 0, afterDecision.split("autoApply()").size - 1)
        assertTrue("the MANUAL_FAILED fallback still clears the abandoned liveness", afterDecision.contains("clearAbandonedApplyLiveness()"))
        assertEquals(
            "the two gates mirrored by the admission intent still fall back to the plan the admission judged",
            2,
            body.substringBefore("val liveFanModeBeforeEntry").split("return@runApplyWithSessionCommit autoApply()").size - 1,
        )
    }

    @Test
    fun `the fan transition admission judges the requested mode with the request's own bias`() {
        val source = stripLineComments(mainSourceText("MimirTweakerService.kt"))
        val transition = sliceBetween(source, "private fun transitionFanControlModeOnWorker(", "proceed = {")
        assertTrue(transition.contains("CoolerRearmAdmission.fanTransitionRefusal("))
        assertTrue(transition.contains("targetFanMode = targetFanMode,"))
        assertTrue(transition.contains("live = LiveRearmState.from(probe.telemetry(operationCapabilities)),"))
        assertTrue(transition.contains("bias = FanCurveBias(curveBias),"))
        assertTrue(transition.contains("getOrElse { CoolerRearmAdmission.LIVE_STATE_UNREADABLE }"))
    }

    // -- Fixtures ----------------------------------------------------------------------------------

    /** The Odin at 23:31: Balanced -- CPU clamps below the live ceilings, GPU level 0, mode 1, SMART fan. */
    private fun balanced() = TuningPlan(
        profileId = "balanced",
        packageName = "example.game",
        cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 3_072_000),
        gpuMaxPowerLevel = 0,
        fanMode = TuningPlanner.FAN_SMART,
        calibrated = false,
        vendorMode = 1,
    )

    /** A plan equal to [hotStock] on every axis; tests copy one axis cooler. */
    private fun equalToLive() = balanced().copy(
        cpuMaxKhz = mapOf(0 to 2_246_400, 6 to 3_187_200),
        gpuMaxPowerLevel = 0,
        vendorMode = 2,
        fanMode = TuningPlanner.FAN_SMART,
    )

    /** The Odin at 23:31: stock, unpinned ceilings at the boost maxima, vendor mode 2, SMART at 47%, 103 C. */
    private fun hotStock() = LiveRearmState(
        cpuCeilingKhz = mapOf(0 to 2_246_400, 6 to 3_187_200),
        gpuMaxPowerLevel = 0,
        gpuDevfreqMaxHz = null,
        gpuMinPowerLevel = 6,
        vendorPerformanceMode = 2,
        vendorFanMode = TuningPlanner.FAN_SMART,
        fanDuty = 11_750,
        fanPeriod = 25_000,
        cpuTempC = 103,
    )

    private fun odin() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "AYN",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "ayn/odin3/sun:15",
        deviceProfileId = "ayn-odin3-cq8725s-pserver",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN, TuningAxis.VENDOR_MODE),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2, 3, 4, 5), listOf(1_785_600, 1_996_800, 2_246_400), 1_785_600, 2_246_400, 1_996_800),
            CpuPolicy(6, "/sys/devices/system/cpu/cpufreq/policy6", listOf(6, 7), listOf(2_246_400, 3_072_000, 3_187_200), 2_246_400, 3_187_200, 3_072_000),
        ),
        gpuFrequenciesKhz = listOf(160_000, 222_000, 342_000, 389_000, 443_000, 525_000, 607_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
        vendorPerformanceModePresent = true,
    )

    private fun severe(cpuTempC: Int) = ThermalTelemetrySample(
        androidThermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
        cpuTempC = cpuTempC,
        gpuTempC = 70,
        batteryTempDeciC = 350,
    )

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }

    private fun sliceBetween(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = text.indexOf(endMarker, start)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }

    private fun stripLineComments(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
}
