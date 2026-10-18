package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class CalibrationEvaluatorTest {
    private val evaluator = CalibrationEvaluator()
    private val plan = TuningPlan(
        profileId = "balanced",
        packageName = "example.game",
        cpuMaxKhz = mapOf(0 to 1_420_800, 4 to 1_958_400, 7 to 2_361_600),
        gpuMaxPowerLevel = 1,
        fanMode = 4,
        calibrated = false,
    )

    @Test
    fun `complete cool discharging run never certifies from local evidence`() {
        val report = evaluator.evaluate(
            run = run(samples = samples()),
            candidate = plan,
            evidence = CalibrationSafetyEvidence(
                workloadAttested = true,
                frameTelemetryAttested = true,
                writesExercised = true,
                rollbackAttempts = 3,
                rollbackSuccesses = 3,
                rebootRestoreVerified = true,
            ),
        )

        assertEquals(CalibrationDisposition.OBSERVED, report.disposition)
        assertNotEquals(CalibrationDisposition.CERTIFIED, report.disposition)
        assertEquals(301, report.sampleCount)
    }

    @Test
    fun `report retains admission terminal and periodic provenance vectors`() {
        val admission = provenance(1, 1_420_800, 40_100, 2_000L)
        val terminal = provenance(2, 2_841_600, 74_300, 300_000L)
        val raw = samples().toMutableList().apply {
            this[2] = this[2].copy(hardware = admission)
            this[150] = this[150].copy(hardware = provenance(1, 1_958_400, 61_500, 150_000L))
            this[lastIndex] = this[lastIndex].copy(hardware = terminal)
        }

        val report = evaluator.evaluate(run(raw), plan)

        assertEquals(admission, report.admissionHardware)
        assertEquals(terminal, report.terminalHardware)
        assertEquals(raw.first().hardware, report.baselineHardware)
        assertEquals(1_958_400, report.samples[150].hardware?.cpuCurrentKhz?.get(0))
        assertEquals(1, report.admissionHardware?.vendorPerformanceMode)
        assertEquals("cpu-usr", report.terminalHardware?.cpuThermalZone?.type)
        assertEquals(74_300L, report.terminalHardware?.cpuThermalZone?.rawTemperature)
    }

    @Test
    fun `observe-only run remains observed and never certified`() {
        val report = evaluator.evaluate(run(samples = samples()), plan)

        assertEquals(CalibrationDisposition.OBSERVED, report.disposition)
        assertTrue(report.blockers.any { "write path" in it })
        assertTrue(report.blockers.any { "frame telemetry" in it })
        assertTrue(report.blockers.any { "rollback drills" in it })
        assertTrue(report.blockers.any { "reboot" in it })
    }

    @Test
    fun `Stock and Quiet provenance require stock vendor mode fan and candidate QoS GPU`() {
        val stock = provenance(0, 1_420_800, 72_000, 2_000L)
        val candidate = plan.copy(profileId = "stock")

        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "stock",
                candidate = candidate,
                admission = stock,
                terminal = stock.copy(capturedAtElapsedMs = 3_000L),
                periodic = listOf(stock, stock.copy(capturedAtElapsedMs = 3_000L)),
            ).isEmpty(),
        )
        // Quiet drives the vendor mode, so it requires the plan's OWN vendorMode (Standard, 0).
        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "quiet",
                candidate = candidate.copy(profileId = "quiet", vendorMode = TuningPlanner.VENDOR_MODE_STANDARD),
                admission = stock.copy(vendorPerformanceMode = 1),
                terminal = stock.copy(vendorPerformanceMode = 1, capturedAtElapsedMs = 3_000L),
                periodic = emptyList(),
            ).any { "performance_mode=0" in it },
        )
    }

    @Test
    fun `Stock no longer requires any vendor mode while Quiet Balanced and Performance require the plan's own`() {
        fun blockers(profileId: String, vendorMode: Int?, live: Int): List<String> {
            val vector = provenance(live, 1_420_800, 72_000L, 2_000L)
            return CalibrationProvenanceValidator.validate(
                profileId = profileId,
                candidate = plan.copy(profileId = profileId, vendorMode = vendorMode),
                admission = vector,
                terminal = vector.copy(capturedAtElapsedMs = 3_000L),
                periodic = emptyList(),
            )
        }

        // Stock never writes the key: whatever the player's mode is, it is not a blocker.
        listOf(0, 1, 2).forEach { live ->
            assertTrue("stock at mode $live", blockers("stock", null, live).none { "performance_mode" in it })
        }
        // Even a stock candidate that somehow carried a mode is not held to it.
        assertTrue(blockers("stock", 2, 0).none { "performance_mode" in it })

        // mapping: quiet 0 (Standard), balanced 1 (Performance), performance 2.
        mapOf("quiet" to 0, "balanced" to 1, "performance" to 2).forEach { (profileId, mode) ->
            assertTrue("$profileId at its own mode", blockers(profileId, mode, mode).none { "performance_mode" in it })
            (0..2).filter { it != mode }.forEach { wrong ->
                assertTrue(
                    "$profileId must reject live mode $wrong",
                    blockers(profileId, mode, wrong).any { "$profileId requires vendor performance_mode=$mode." in it },
                )
            }
        }

        // A plan without the axis (vendorMode == null) requires nothing, like fan with no fan axis.
        listOf("quiet", "balanced", "performance").forEach { profileId ->
            assertTrue(blockers(profileId, null, 1).none { "performance_mode" in it })
        }
    }

    @Test
    fun `Stock and Quiet reject the vendor fan invariant and Quiet the vendor mode while exact vectors pass`() {
        listOf("stock", "quiet").forEach { profileId ->
            val candidate = plan.copy(
                profileId = profileId,
                vendorMode = if (profileId == "quiet") TuningPlanner.VENDOR_MODE_STANDARD else null,
            )
            val valid = provenance(0, 1_420_800, 72_000L, 2_000L)
            fun blockers(vector: CalibrationHardwareVector) = CalibrationProvenanceValidator.validate(
                profileId = profileId,
                candidate = candidate,
                admission = vector,
                terminal = vector.copy(capturedAtElapsedMs = 3_000L),
                periodic = emptyList(),
            )

            assertTrue("$profileId exact stock vector must pass", blockers(valid).isEmpty())
            if (profileId == "quiet") {
                assertTrue(
                    "$profileId must reject vendor performance mode",
                    blockers(valid.copy(vendorPerformanceMode = 1)).any { "performance_mode=0" in it },
                )
            } else {
                assertTrue(
                    "$profileId no longer requires a vendor performance mode",
                    blockers(valid.copy(vendorPerformanceMode = 1)).none { "performance_mode" in it },
                )
            }
            assertTrue(
                "$profileId must reject vendor fan mode",
                blockers(valid.copy(vendorFanMode = 3)).any { "fan_mode=4" in it },
            )
        }
    }

    @Test
    fun `Odin Quiet provenance admits authenticated manual fan ownership`() {
        val candidate = plan.copy(
            profileId = "quiet",
            fanMode = TuningPlanner.FAN_MANUAL,
        )
        val vector = provenance(0, 1_420_800, 72_000L, 2_000L).copy(
            vendorFanMode = TuningPlanner.FAN_MANUAL,
        )

        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "quiet",
                candidate = candidate,
                admission = vector,
                terminal = vector.copy(capturedAtElapsedMs = 3_000L),
                periodic = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun `Odin Stock accepts only explicit unbounded shared QoS not a generic ceiling mismatch`() {
        val stock = provenance(0, 1_420_800, 72_000L, 2_000L).copy(
            cpuQosCeilingKhz = mapOf(0 to Int.MAX_VALUE, 4 to Int.MAX_VALUE, 7 to Int.MAX_VALUE),
            cpuQosTransport = CpuQosTransport.SHARED_QOS,
        )
        val stockPlan = plan.copy(profileId = "stock")

        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "stock",
                candidate = stockPlan,
                admission = stock,
                terminal = stock.copy(capturedAtElapsedMs = 3_000L),
                periodic = emptyList(),
            ).isEmpty(),
        )
        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "balanced",
                candidate = plan,
                admission = stock,
                terminal = stock.copy(capturedAtElapsedMs = 3_000L),
                periodic = emptyList(),
            ).any { "QoS ceiling" in it },
        )
    }

    @Test
    fun `pre profile baseline is structurally validated with its immutable thermal inventory`() {
        val baseline = provenance(0, 1_420_800, 72_000L, 0L)

        assertTrue(CalibrationProvenanceValidator.validateBaseline(baseline).isEmpty())
        assertEquals("/sys/class/thermal/thermal_zone0", baseline.thermalInventory?.cpu?.single()?.path)
        assertEquals("/sys/class/thermal/thermal_zone1", baseline.thermalInventory?.gpu?.single()?.path)
        assertTrue(CalibrationProvenanceValidator.validateBaseline(null).isNotEmpty())
    }

    @Test
    fun `pre profile baseline admits live vendor Smart duty movement`() {
        val stockPlan = plan.copy(profileId = "stock")
        val vectors = listOf(
            provenance(0, 1_420_800, 72_000L, 1_000L),
            provenance(0, 1_420_800, 72_000L, 1_250L).copy(fanDuty = 96),
            provenance(0, 1_420_800, 72_000L, 1_500L),
        )

        assertTrue(CalibrationPreProfileBaselinePolicy.violations(vectors, stockPlan).isEmpty())
        assertEquals(vectors.last(), CalibrationPreProfileBaselinePolicy.select(vectors, stockPlan))
    }

    @Test
    fun `pre profile baseline violations preserve exact valid selection behavior`() {
        val stockPlan = plan.copy(profileId = "stock")
        val vectors = listOf(
            provenance(0, 1_420_800, 72_000L, 1_000L),
            provenance(0, 1_420_800, 72_000L, 1_250L),
            provenance(0, 1_420_800, 72_000L, 1_500L),
        )

        assertTrue(CalibrationPreProfileBaselinePolicy.violations(vectors, stockPlan).isEmpty())
        assertEquals(vectors.last(), CalibrationPreProfileBaselinePolicy.select(vectors, stockPlan))
    }

    @Test
    fun `hardware vector JSON round trip preserves complete fail closed provenance`() {
        val expected = provenance(1, 1_420_800, 72_123L, 15_000L)

        val decoded = CalibrationHardwareVectorJsonCodec.decode(
            CalibrationHardwareVectorJsonCodec.encode(expected),
        )

        assertEquals(expected, decoded)
        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "balanced",
                candidate = plan,
                admission = decoded,
                terminal = decoded.copy(capturedAtElapsedMs = 30_000L),
                periodic = listOf(decoded),
            ).isEmpty(),
        )
    }

    @Test
    fun `LAB pre mutation binding rejects a report identity that was not issued to this observation`() {
        val vectors = listOf(
            provenance(0, 1_420_800, 72_000L, 1_000L),
            provenance(0, 1_420_800, 72_000L, 1_250L),
            provenance(0, 1_420_800, 72_000L, 1_500L),
        )
        val binding = LabPreMutationBaselineBinding(
            profileId = "balanced",
            packageName = "example.game",
            hardwareFingerprint = "rp5-hash",
            buildFingerprint = "fingerprint",
            bootId = "boot-id",
            generation = 7L,
            transactionId = "transaction",
            requestId = "request",
            planHash = "plan",
            leaseDeadlineElapsedMs = 99_000L,
        )
        val valid = run(emptyList()).copy(
            buildFingerprint = "fingerprint",
            preProfileHardware = vectors.last(),
            preMutationBaselineVectors = vectors,
            preMutationBaselineBinding = binding,
            preMutationBaselinePlanHash = "plan",
            observationId = "fresh-observation",
            preMutationBaselineObservationId = "fresh-observation",
        )

        assertTrue(LabPreMutationBaselineDiagnosticValidator.validate(valid).isEmpty())
        assertTrue(
            LabPreMutationBaselineDiagnosticValidator.validate(
                valid.copy(preMutationBaselineObservationId = "stale-observation"),
            ).isNotEmpty(),
        )
    }

    @Test
    fun `external LAB baseline with a different applied plan never starts an observation`() {
        val store = InMemoryReportStore()
        val runner = runner(
            store = store,
            externalBaseline = { _, _, _ ->
                LabPreMutationBaselineOffer(
                    vectors = emptyList(),
                    binding = LabPreMutationBaselineBinding(
                        profileId = "quiet",
                        packageName = "example.game",
                        hardwareFingerprint = "rp5-hash",
                        buildFingerprint = "rp5-test",
                        bootId = "boot-id",
                        generation = 7L,
                        transactionId = "transaction",
                        requestId = "request",
                        planHash = "a-different-plan",
                        leaseDeadlineElapsedMs = 99_000L,
                    ),
                    observationId = "handoff-observation",
                )
            },
            requiresExternalBaseline = { it == "quiet" },
        )
        try {
            val result = runner.start("quiet", "example.game", 30)
            assertFalse(result.success)
            assertEquals("PRE_MUTATION_BASELINE_PLAN_MISMATCH", result.code)
            assertEquals(CalibrationDisposition.NOT_STARTED, store.load()?.disposition ?: CalibrationDisposition.NOT_STARTED)
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `legacy missing JSON vector fails closed rather than becoming calibration evidence`() {
        val legacy = CalibrationHardwareVectorJsonCodec.decode(org.json.JSONObject())

        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "balanced",
                candidate = plan,
                admission = legacy,
                terminal = legacy,
                periodic = emptyList(),
            ).any { "schema" in it || "identity" in it || "CPU" in it },
        )
    }

    @Test
    fun `thermal winner may move within frozen inventory but no new sensor is accepted`() {
        val admission = provenance(1, 1_420_800, 72_000L, 2_000L)
        val sibling = ThermalZoneIdentity("kgsl-usr", "kgsl-usr-2", "/sys/class/thermal/thermal_zone2")
        val frozenInventory = admission.thermalInventory!!.copy(gpu = admission.thermalInventory.gpu + sibling)
        val validMovedWinner = admission.copy(
            capturedAtElapsedMs = 3_000L,
            gpuThermalZone = ThermalZoneWinner("kgsl-usr-2", 40_100L, 40, sibling.path),
            thermalInventory = frozenInventory,
        )
        val invalidNewWinner = validMovedWinner.copy(
            gpuThermalZone = ThermalZoneWinner("kgsl-usr-9", 40_100L, 40, "/sys/class/thermal/thermal_zone9"),
        )

        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "balanced",
                candidate = plan,
                admission = admission.copy(thermalInventory = frozenInventory),
                terminal = validMovedWinner,
                periodic = emptyList(),
                expectedThermalInventory = frozenInventory,
            ).isEmpty(),
        )
        assertTrue(
            CalibrationProvenanceValidator.validate(
                profileId = "balanced",
                candidate = plan,
                admission = admission.copy(thermalInventory = frozenInventory),
                terminal = invalidNewWinner,
                periodic = emptyList(),
                expectedThermalInventory = frozenInventory,
            ).any { "thermal" in it.lowercase() },
        )
    }

    /**
     * **This test required `fanRpm` until 2026-08-26, and the hardware overturned it.**
     *
     * "Baseline comparability" assumed rpm was a witness that could be captured. It is not: neither
     * shipping handheld has a tachometer. `/sys/class/gpio5_pwm2/speed` reads 0 on a fan spinning
     * at constant duty, its non-zero values never repeat, and the vendor's own controller logs
     * `smartSpeed = 12000` against a `duty` node reading exactly 12000 -- its "speed" is commanded
     * PWM duty, not revolutions. Requiring rpm therefore marked EVERY real vector incoherent, which
     * is "absent" being read as "broken".
     *
     * Duty and period stay required: those are genuinely written and read back.
     */
    @Test
    fun `duty and period are required but a missing tachometer is not an incoherent vector`() {
        val admission = provenance(1, 1_420_800, 72_000L, 2_000L)

        assertTrue(
            "no handheld Mimir ships on has a tachometer -- absence must not invalidate the vector",
            CalibrationProvenanceValidator.validate(
                profileId = "balanced",
                candidate = plan,
                admission = admission.copy(fanRpm = null),
                terminal = admission.copy(capturedAtElapsedMs = 3_000L, fanRpm = null),
                periodic = emptyList(),
            ).none { "fan live vector is incoherent" in it },
        )
        assertTrue(
            "but duty is a real reading and is still required",
            CalibrationProvenanceValidator.validate(
                profileId = "balanced",
                candidate = plan,
                admission = admission.copy(fanDuty = null),
                terminal = admission.copy(capturedAtElapsedMs = 3_000L, fanDuty = null),
                periodic = emptyList(),
            ).any { "fan live vector is incoherent" in it },
        )
    }

    /**
     * A tachometer that IS present still has to talk sense -- and it gets its own message, so an
     * evidence file can never confuse "this device has no tachometer" with "this tachometer is
     * lying".
     */
    @Test
    fun `a negative tachometer reading is reported separately from an absent one`() {
        val admission = provenance(1, 1_420_800, 72_000L, 2_000L)

        val findings = CalibrationProvenanceValidator.validate(
            profileId = "balanced",
            candidate = plan,
            admission = admission.copy(fanRpm = -1),
            terminal = admission.copy(capturedAtElapsedMs = 3_000L, fanRpm = -1),
            periodic = emptyList(),
        )

        assertTrue(findings.any { "fan tachometer reading is negative" in it })
        assertTrue(findings.none { "fan live vector is incoherent" in it })
    }

    @Test
    fun `SharedPreferences reload preserves full provenance after a fresh store instance`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tweaker_calibration", 0).edit().clear().commit()
        try {
            val vectors = listOf(
                provenance(0, 1_420_800, 72_000L, 1_000L),
                provenance(0, 1_420_800, 72_000L, 1_250L),
                provenance(0, 1_420_800, 72_000L, 1_500L),
            )
            val binding = LabPreMutationBaselineBinding(
                profileId = "balanced",
                packageName = "example.game",
                hardwareFingerprint = "rp5-hash",
                buildFingerprint = "fingerprint",
                bootId = "boot-id",
                generation = 7L,
                transactionId = "transaction",
                requestId = "request",
                planHash = "plan",
                leaseDeadlineElapsedMs = 99_000L,
            )
            val expected = evaluator.evaluate(
                run(samples()).copy(
                    buildFingerprint = "fingerprint",
                    preProfileHardware = vectors.last(),
                    preMutationBaselineVectors = vectors,
                    preMutationBaselineBinding = binding,
                    preMutationBaselinePlanHash = "plan",
                    observationId = "durable-observation",
                    preMutationBaselineObservationId = "durable-observation",
                ),
                plan,
            )
            assertTrue(SharedPreferencesCalibrationReportStore(context).save(expected))

            val reloaded = SharedPreferencesCalibrationReportStore(context).load()
            assertEquals(expected.observationId, reloaded?.observationId)
            assertEquals(expected.baselineHardware, reloaded?.baselineHardware)
            assertEquals(expected.admissionHardware, reloaded?.admissionHardware)
            assertEquals(expected.terminalHardware, reloaded?.terminalHardware)
            assertEquals(expected.samples.map { it.hardware }, reloaded?.samples?.map { it.hardware })
            assertEquals(vectors, reloaded?.preMutationBaselineVectors)
            assertEquals(binding, reloaded?.preMutationBaselineBinding)
            assertEquals("durable-observation", reloaded?.preMutationBaselineObservationId)
        } finally {
            context.getSharedPreferences("tweaker_calibration", 0).edit().clear().commit()
        }
    }

    @Test
    fun `complete legacy report JSON reloads but remains non certifying without provenance`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tweaker_calibration", 0).edit().clear().commit()
        try {
            val legacy = org.json.JSONObject()
                .put("disposition", CalibrationDisposition.OBSERVED.name)
                .put("profileId", "balanced")
                .put("packageName", "example.game")
                .put("hardwareFingerprint", "legacy-hardware")
                .put("startedAtEpochMs", 1_000L)
                .put("requestedDurationSeconds", 300)
                .put("observedDurationSeconds", 300)
                .put("sampleCount", 1)
                .put("maxCpuTempC", 72)
                .put("maxGpuTempC", 70)
                .put("maxBatteryTempDeciC", 390)
                .put("averageBatteryPowerMw", 5_200)
                .put("maxThermalStatus", 1)
                .put("chargingSampleCount", 0)
                .put("attestedSampleCount", 1)
                .put("frameSampleCount", 1)
                .put("frameAttestedSampleCount", 1)
                .put("frameLayerNames", org.json.JSONArray())
                .put("candidateCpuMaxKhz", org.json.JSONObject().put("0", 1_420_800))
                .put("candidateGpuMaxPowerLevel", 1)
                .put("candidateFanMode", 4)
                .put("evidence", org.json.JSONObject())
                .put("blockers", org.json.JSONArray())
                .put("samples", org.json.JSONArray().put(org.json.JSONObject().put("elapsedMs", 0L)))
            context.getSharedPreferences("tweaker_calibration", 0)
                .edit().putString("latest_report", legacy.toString()).commit()

            val store = SharedPreferencesCalibrationReportStore(context)
            val reloaded = store.load()
            assertEquals(CalibrationDisposition.OBSERVED, reloaded?.disposition)
            assertEquals(null, reloaded?.admissionHardware)
            assertEquals(null, reloaded?.samples?.singleOrNull()?.hardware)
            assertFalse(reloaded?.batteryEfficiencyEligible ?: true)
            assertEquals(null, reloaded?.averageBatteryPowerMw)
            assertEquals(null, store.loadCertified("legacy-hardware", "balanced"))
        } finally {
            context.getSharedPreferences("tweaker_calibration", 0).edit().clear().commit()
        }
    }

    @Test
    fun `full vector cadence has a baseline then fifteen second checkpoints and aborts only on third miss`() {
        assertTrue(
            CalibrationVectorCheckpointPolicy.requiresFullVector(
                elapsedMs = 0L,
                lastFullCheckpointAttemptElapsedMs = null,
                admissionCheckpoint = false,
                terminalCheckpoint = false,
                thermalCheckpoint = false,
            ),
        )
        assertFalse(
            CalibrationVectorCheckpointPolicy.requiresFullVector(
                elapsedMs = 14_999L,
                lastFullCheckpointAttemptElapsedMs = 0L,
                admissionCheckpoint = false,
                terminalCheckpoint = false,
                thermalCheckpoint = false,
            ),
        )
        assertTrue(
            CalibrationVectorCheckpointPolicy.requiresFullVector(
                elapsedMs = 15_000L,
                lastFullCheckpointAttemptElapsedMs = 0L,
                admissionCheckpoint = false,
                terminalCheckpoint = false,
                thermalCheckpoint = false,
            ),
        )
        assertFalse(
            CalibrationVectorCheckpointPolicy.requiresFullVector(
                elapsedMs = 15_650L,
                lastFullCheckpointAttemptElapsedMs = 15_000L,
                admissionCheckpoint = false,
                terminalCheckpoint = false,
                thermalCheckpoint = false,
            ),
        )
        assertTrue(
            CalibrationVectorCheckpointPolicy.requiresFullVector(
                elapsedMs = 30_000L,
                lastFullCheckpointAttemptElapsedMs = 15_000L,
                admissionCheckpoint = false,
                terminalCheckpoint = false,
                thermalCheckpoint = false,
            ),
        )
        assertFalse(CalibrationVectorCheckpointPolicy.mustAbortAfterMiss(false, 1))
        assertFalse(CalibrationVectorCheckpointPolicy.mustAbortAfterMiss(false, 2))
        assertTrue(CalibrationVectorCheckpointPolicy.mustAbortAfterMiss(false, 3))
        assertTrue(CalibrationVectorCheckpointPolicy.mustAbortAfterMiss(true, 1))
    }

    @Test
    fun `target contract latch is fresh only until the next full vector boundary`() {
        val latch = CalibrationTargetContractLatch()

        assertFalse(latch.isFresh(1_000L))
        latch.record(elapsedMs = 1_000L, valid = true)
        assertTrue(latch.isFresh(15_999L))
        assertFalse(latch.isFresh(16_000L))
    }

    @Test
    fun `failed full vector renewal invalidates target contract until a later valid proof`() {
        val latch = CalibrationTargetContractLatch()

        latch.record(elapsedMs = 1_000L, valid = true)
        latch.record(elapsedMs = 5_000L, valid = false)
        assertFalse(latch.isFresh(5_001L))
        latch.record(elapsedMs = 20_000L, valid = true)
        assertTrue(latch.isFresh(20_001L))
        latch.invalidate()
        assertFalse(latch.isFresh(20_002L))
    }

    @Test
    fun `invalid full vector becomes terminal after initial target proof`() {
        assertTrue(CalibrationTargetContractDriftPolicy.mustAbort(fullVectorValid = false))
        assertFalse(CalibrationTargetContractDriftPolicy.mustAbort(fullVectorValid = true))
    }

    @Test
    fun `inactive target contract never persists an observing report`() {
        val store = InMemoryReportStore()
        val runner = runner(store = store, fullTelemetry = ::stockTelemetry)

        try {
            val result = runner.start("balanced", "example.game", 30)
            assertFalse(result.success)
            assertEquals("TARGET_CONTRACT_NOT_ACTIVE", result.code)
            assertEquals(null, store.load())
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `unavailable initial target vector never begins observation`() {
        val store = InMemoryReportStore()
        val runner = runner(store = store, fullTelemetry = { error("target probe unavailable") })

        try {
            val result = runner.start("balanced", "example.game", 30)
            assertFalse(result.success)
            assertEquals("TARGET_CONTRACT_NOT_ACTIVE", result.code)
            assertEquals(null, store.load())
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `production observation requires authenticated applied target authority`() {
        val store = InMemoryReportStore()
        val runner = runner(
            store = store,
            requiresAppliedTarget = { true },
        )

        try {
            val result = runner.start("balanced", "example.game", 30)
            assertFalse(result.success)
            assertEquals("APPLIED_TARGET_AUTHORITY_REQUIRED", result.code)
            assertEquals(null, store.load())
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `exact authenticated transport drift blocks observation before persistence`() {
        val store = InMemoryReportStore()
        val runner = runner(
            store = store,
            appliedTarget = { profileId, packageName, live ->
                appliedTargetOffer(profileId, packageName, live).copy(
                    contract = appliedTargetOffer(profileId, packageName, live).contract.copy(
                        cpuQosTransport = CpuQosTransport.SHARED_QOS,
                    ),
                )
            },
            requiresAppliedTarget = { true },
        )

        try {
            val result = runner.start("balanced", "example.game", 30)
            assertFalse(result.success)
            assertEquals("TARGET_CONTRACT_NOT_ACTIVE", result.code)
            assertTrue("diagnostic omitted exact transport drift", "transport" in result.message.lowercase())
            assertEquals(null, store.load())
        } finally {
            runner.shutdown()
        }
    }

    //  now derives FAN_SMART, not FAN_SILENT -- the
    // live telemetry fixture below must report the vendor mode the calibration evaluator will
    // actually require to start observing "quiet", or it refuses with TARGET_CONTRACT_NOT_ACTIVE
    // before this test ever reaches its own assertions.
    @Test
    fun `external LAB baseline validates its first live read as the target`() {
        val store = InMemoryReportStore()
        val baseline = List(CalibrationPreProfileBaselinePolicy.REQUIRED_READS) { index ->
            stockTelemetry().toCalibrationHardwareVector(
                capturedAtElapsedMs = index.toLong(),
                buildFingerprint = "rp5-test",
            )
        }
        val runner = runner(
            store = store,
            telemetry = telemetry().copy(
                vendorPerformanceMode = 0,
                vendorFanMode = TuningPlanner.FAN_SMART,
            ),
            fullTelemetry = {
                telemetry().copy(
                    vendorPerformanceMode = 0,
                    vendorFanMode = TuningPlanner.FAN_SMART,
                )
            },
            externalBaseline = { profileId, packageName, live ->
                val plan = TuningPlanner().plan(profileId, packageName, live)!!
                LabPreMutationBaselineOffer(
                    vectors = baseline,
                    binding = LabPreMutationBaselineBinding(
                        profileId = profileId,
                        packageName = packageName,
                        hardwareFingerprint = HardwareFingerprint.create(live),
                        buildFingerprint = live.buildFingerprint,
                        bootId = "boot-id",
                        generation = 7L,
                        transactionId = "transaction",
                        requestId = "request",
                        planHash = PlanHash.of(plan),
                        leaseDeadlineElapsedMs = 99_000L,
                    ),
                    observationId = "handoff-observation",
                )
            },
            requiresExternalBaseline = { it == "quiet" },
        )

        try {
            val result = runner.start("quiet", "example.game", 30)
            assertTrue(result.message, result.success)
            assertEquals(CalibrationDisposition.OBSERVING, result.report.disposition)
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `target drift before workload admission terminalizes promptly`() {
        val finished = CountDownLatch(1)
        var targetReads = 0
        val store = InMemoryReportStore()
        val runner = runner(
            store = store,
            fullTelemetry = {
                if (targetReads++ == 0) telemetry() else stockTelemetry()
            },
            onFinished = { finished.countDown() },
        )

        try {
            assertTrue(runner.start("balanced", "example.game", 30).success)
            assertTrue("target drift did not finish promptly", finished.await(2, TimeUnit.SECONDS))
            val terminal = store.load()!!
            assertEquals(CalibrationDisposition.FAILED, terminal.disposition)
            assertEquals(0, terminal.qualificationSampleCount)
            assertTrue(terminal.blockers.any { "TARGET_CONTRACT_DRIFT" in it })
            assertFalse(terminal.blockers.any { "QUALIFICATION_ADMISSION_TIMEOUT" in it })
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `restore abort is idempotent and preserves the first terminal report`() {
        val callbacks = AtomicInteger(0)
        val store = InMemoryReportStore()
        val runner = runner(store = store, onFinished = { callbacks.incrementAndGet() })

        try {
            assertTrue(runner.start("balanced", "example.game", 30).success)
            val first = runner.abort("PROFILE_RESTORE_REQUESTED")
            assertTrue(first.success)
            assertTrue(first.report.blockers.any { "PROFILE_RESTORE_REQUESTED" in it })
            val firstId = first.report.observationId
            val second = runner.abort("ACTIVE_PROFILE_RESTORED")
            assertEquals("NO_ACTIVE_OBSERVATION", second.code)
            assertEquals(firstId, store.load()?.observationId)
            assertTrue(store.load()!!.blockers.any { "PROFILE_RESTORE_REQUESTED" in it })
            assertEquals(1, callbacks.get())
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `terminal persistence failure remains blocked until an exact retry succeeds`() {
        val store = InMemoryReportStore()
        val runner = runner(store = store)

        try {
            assertTrue(runner.start("balanced", "example.game", 30).success)
            store.failTerminalWrites = true
            val failed = runner.abort("PROFILE_RESTORE_REQUESTED")
            assertFalse(failed.success)
            assertEquals("PERSIST_FAILED", failed.code)
            assertEquals(CalibrationDisposition.OBSERVING, runner.status().disposition)

            store.failTerminalWrites = false
            val retried = runner.abort("PROFILE_RESTORE_REQUESTED")
            assertTrue(retried.success)
            assertEquals(CalibrationDisposition.FAILED, store.load()?.disposition)
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `cold process terminalization remains retryable when its first durable write fails`() {
        val store = InMemoryReportStore()
        val stale = evaluator.evaluate(run(emptyList()), plan, observing = true)
        assertTrue(store.save(stale))
        store.failTerminalWrites = true
        val runner = runner(store = store)

        try {
            assertEquals(CalibrationDisposition.FAILED, runner.status().disposition)
            assertEquals(CalibrationDisposition.OBSERVING, store.load()?.disposition)
            assertEquals("PERSIST_FAILED", runner.start("balanced", "example.game", 30).code)

            store.failTerminalWrites = false
            assertEquals("NO_ACTIVE_OBSERVATION", runner.abort("ACTIVE_PROFILE_RESTORED").code)
            assertEquals(CalibrationDisposition.FAILED, store.load()?.disposition)
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `charging samples invalidate battery power calibration`() {
        val charging = samples().map { it.copy(batteryStatus = "Charging") }
        val report = evaluator.evaluate(run(samples = charging), plan)

        assertEquals(301, report.chargingSampleCount)
        assertFalse(report.batteryEfficiencyEligible)
        assertTrue(report.batteryEfficiencyBlockers.any { "discharging" in it })
        assertNotEquals(CalibrationDisposition.CERTIFIED, report.disposition)
    }

    @Test
    fun `charge separation remains valid thermal evidence but never battery efficiency evidence`() {
        val chargeSeparated = samples().map {
            it.copy(
                batteryStatus = "Discharging",
                externalPowerState = ExternalPowerState.USB,
            )
        }

        val report = evaluator.evaluate(run(samples = chargeSeparated), plan)

        assertEquals(CalibrationDisposition.OBSERVED, report.disposition)
        assertEquals(301, report.externalPowerSampleCount)
        assertFalse(report.batteryEfficiencyEligible)
        assertEquals(null, report.averageBatteryPowerMw)
        assertTrue(report.batteryEfficiencyBlockers.any { "Charge Separation" in it })
    }

    @Test
    fun `unplugged discharging samples admit battery efficiency evidence`() {
        val report = evaluator.evaluate(run(samples = samples()), plan)

        assertEquals(0, report.externalPowerSampleCount)
        assertTrue(report.batteryEfficiencyEligible)
        assertTrue(report.batteryEfficiencyBlockers.isEmpty())
        assertEquals(5_200, report.averageBatteryPowerMw)
    }

    @Test
    fun `unknown external power fails closed only for battery efficiency`() {
        val unknown = samples().map { it.copy(externalPowerState = ExternalPowerState.UNKNOWN) }
        val report = evaluator.evaluate(run(samples = unknown), plan)

        assertEquals(CalibrationDisposition.OBSERVED, report.disposition)
        assertFalse(report.batteryEfficiencyEligible)
        assertEquals(null, report.averageBatteryPowerMw)
    }

    @Test
    fun `thermal threshold breach is a failed calibration`() {
        val hot = samples().toMutableList().apply { this[200] = this[200].copy(cpuTempC = 91) }
        val report = evaluator.evaluate(run(samples = hot), plan)

        assertEquals(CalibrationDisposition.FAILED, report.disposition)
        assertTrue(report.blockers.any { "CPU temperature exceeded" in it })
    }

    @Test
    fun `pre-workload startup evidence stays raw but cannot poison qualification aggregates`() {
        val raw = samples().toMutableList().apply {
            this[0] = this[0].copy(
                cpuTempC = 75,
                batteryPowerMw = -9_999,
                foregroundPackageName = "com.gruning.mimir.tweaker",
                frameStatus = FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND.name,
                frameAverageFpsMilli = null,
                frameP95TimeUs = null,
            )
            add(last().copy(elapsedMs = 301_000L))
        }
        val report = evaluator.evaluate(
            run = run(samples = raw),
            candidate = plan,
            evidence = CalibrationQualificationWindow.from(run(raw)).toEvidence(),
        )

        assertEquals(302, report.sampleCount)
        assertEquals(301, report.qualificationSampleCount)
        assertEquals(301, report.qualificationWindowSampleCount)
        assertEquals(75, report.maxCpuTempC)
        assertEquals(5_200, report.averageBatteryPowerMw)
        assertEquals(CalibrationDisposition.OBSERVED, report.disposition)
        assertTrue(report.samples.first().cpuTempC == 75)
    }

    @Test
    fun `hot preflight sample invalidates the attempt instead of being excluded`() {
        val raw = samples().toMutableList().apply {
            this[0] = this[0].copy(
                cpuTempC = 96,
                foregroundPackageName = "com.gruning.mimir.tweaker",
                frameStatus = FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND.name,
            )
            add(last().copy(elapsedMs = 301_000L))
        }

        val report = evaluator.evaluate(
            run = run(samples = raw),
            candidate = plan,
            evidence = CalibrationQualificationWindow.from(run(raw)).toEvidence(),
        )

        assertEquals(96, report.maxCpuTempC)
        assertEquals(CalibrationDisposition.FAILED, report.disposition)
        assertTrue(report.blockers.any { "CPU temperature exceeded" in it })
        assertEquals(96, report.samples.first().cpuTempC)
    }

    @Test
    fun `hot raw sample during a post-admission attestation gap fails safely`() {
        val raw = samples().toMutableList().apply {
            this[200] = this[200].copy(
                cpuTempC = 96,
                foregroundPackageName = "com.gruning.mimir.tweaker",
                frameStatus = FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND.name,
            )
        }

        val report = evaluator.evaluate(
            run = run(samples = raw),
            candidate = plan,
            evidence = CalibrationQualificationWindow.from(run(raw)).toEvidence(),
        )

        assertEquals(96, report.maxCpuTempC)
        assertEquals(CalibrationDisposition.FAILED, report.disposition)
        assertTrue(report.blockers.any { "CPU temperature exceeded" in it })
    }

    @Test
    fun `qualification must span the requested workload duration after admission`() {
        val raw = samples().toMutableList().apply {
            this[0] = this[0].copy(
                foregroundPackageName = "com.gruning.mimir.tweaker",
                frameStatus = FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND.name,
            )
        }

        val report = evaluator.evaluate(
            run = run(samples = raw),
            candidate = plan,
            evidence = CalibrationQualificationWindow.from(run(raw)).toEvidence(),
        )

        assertEquals(299, report.observedDurationSeconds)
        assertEquals(CalibrationDisposition.FAILED, report.disposition)
        assertTrue(report.blockers.any { "at least 300 seconds" in it })
    }

    @Test
    fun `completion requires 300 seconds after admission within the 360 second cap`() {
        val required = 300_000L

        assertEquals(
            CalibrationObservationCompletion.CONTINUE,
            CalibrationObservationCompletionPolicy.decide(
                elapsedMs = 300_000L,
                qualifiedDurationMs = 299_000L,
                requiredDurationMs = required,
            ),
        )
        assertEquals(
            CalibrationObservationCompletion.COMPLETE,
            CalibrationObservationCompletionPolicy.decide(
                elapsedMs = 359_000L,
                qualifiedDurationMs = required,
                requiredDurationMs = required,
            ),
        )
        assertEquals(
            CalibrationObservationCompletion.ADMISSION_TIMEOUT,
            CalibrationObservationCompletionPolicy.decide(
                elapsedMs = 360_000L,
                qualifiedDurationMs = 299_000L,
                requiredDurationMs = required,
            ),
        )
    }

    @Test
    fun `qualification begins only after three consecutive fresh target samples`() {
        val unstable = samples().toMutableList().apply {
            this[0] = this[0].copy(
                foregroundPackageName = "com.gruning.mimir.tweaker",
                frameStatus = FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND.name,
            )
            this[2] = this[2].copy(
                foregroundPackageName = "com.gruning.mimir.tweaker",
                frameStatus = FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND.name,
            )
        }

        val window = CalibrationQualificationWindow.from(run(unstable))

        assertEquals(298, window.samples.size)
        assertEquals(298, window.windowSampleCount)
    }

    @Test
    fun `admission requires three fresh frames each backed by a target contract vector`() {
        val raw = samples().take(3).map { it.copy(targetContractValid = false) }
        val oneValid = raw.mapIndexed { index, sample ->
            sample.copy(targetContractValid = index == 2)
        }
        val allValid = raw.map { it.copy(targetContractValid = true) }

        assertEquals(null, CalibrationQualificationWindow.from(run(raw)).admissionSample)
        assertEquals(null, CalibrationQualificationWindow.from(run(oneValid)).admissionSample)
        assertEquals(2_000L, CalibrationQualificationWindow.from(run(allValid)).admissionSample?.elapsedMs)
    }

    @Test
    fun `late terminal report cannot replace a newer observation`() {
        val store = InMemoryReportStore()
        val stale = evaluator.evaluate(
            run = run(samples = emptyList()).copy(observationId = "stale"),
            candidate = plan,
            observing = true,
        )
        val fresh = evaluator.evaluate(
            run = run(samples = emptyList()).copy(observationId = "fresh"),
            candidate = plan,
            observing = true,
        )
        assertTrue(store.beginObservation(stale))
        assertTrue(store.beginObservation(fresh))

        assertFalse(store.saveIfCurrent("stale", stale.copy(disposition = CalibrationDisposition.FAILED)))
        assertEquals("fresh", store.load()?.observationId)
        assertEquals(CalibrationDisposition.OBSERVING, store.load()?.disposition)
    }

    @Test
    fun `service death terminalizes its observation and a relaunch starts a distinct fresh report`() {
        val store = InMemoryReportStore()
        val first = runner(store)

        val firstStart = first.start("balanced", "example.game", 30)
        assertTrue(firstStart.success)
        first.shutdown()

        val terminal = store.load()!!
        assertEquals(CalibrationDisposition.FAILED, terminal.disposition)
        assertTrue(terminal.blockers.any { "SERVICE_DESTROYED" in it })

        val second = runner(store)
        val secondStart = second.start("balanced", "example.game", 30)
        try {
            assertTrue(secondStart.success)
            assertEquals(CalibrationDisposition.OBSERVING, secondStart.report.disposition)
            assertNotEquals(terminal.observationId, secondStart.report.observationId)
            assertEquals(secondStart.report.observationId, store.load()?.observationId)
            assertEquals(CalibrationDisposition.OBSERVING, store.load()?.disposition)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun `cold process recovery replaces the observing blocker with a terminal reason`() {
        val store = InMemoryReportStore()
        val observing = evaluator.evaluate(
            run = run(samples = emptyList()).copy(observationId = "orphaned"),
            candidate = plan,
            observing = true,
        )
        assertTrue(store.beginObservation(observing))

        val recovered = runner(store)
        try {
            val terminal = store.load()!!
            assertEquals(CalibrationDisposition.FAILED, terminal.disposition)
            assertTrue(terminal.blockers.any { "SERVICE_PROCESS_ENDED" in it })
            assertFalse(terminal.blockers.any { "still running" in it })
        } finally {
            recovered.shutdown()
        }
    }

    @Test
    fun `raw thermal trip stops the runner immediately even before workload admission`() {
        val finished = CountDownLatch(1)
        val store = InMemoryReportStore()
        val runner = runner(
            store = store,
            telemetry = telemetry().copy(cpuTempC = 96),
            onFinished = { finished.countDown() },
        )

        try {
            assertTrue(runner.start("balanced", "example.game", 30).success)
            assertTrue("thermal trip did not finish promptly", finished.await(2, TimeUnit.SECONDS))
            val terminal = store.load()!!
            assertEquals(CalibrationDisposition.FAILED, terminal.disposition)
            assertEquals(96, terminal.maxCpuTempC)
            assertTrue(terminal.blockers.any { "THERMAL_CPU_LIMIT_EXCEEDED" in it })
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `raw thermal cause wins when its required full provenance vector cannot be read`() {
        val finished = CountDownLatch(1)
        var targetReads = 0
        val store = InMemoryReportStore()
        val runner = runner(
            store = store,
            telemetry = telemetry().copy(cpuTempC = 96),
            fullTelemetry = {
                if (targetReads++ == 0) telemetry() else error("privileged vector unavailable")
            },
            onFinished = { finished.countDown() },
        )

        try {
            assertTrue(runner.start("balanced", "example.game", 30).success)
            assertTrue("thermal trip did not finish promptly", finished.await(2, TimeUnit.SECONDS))
            val terminal = store.load()!!
            assertEquals(CalibrationDisposition.FAILED, terminal.disposition)
            assertTrue(terminal.blockers.any { "THERMAL_CPU_LIMIT_EXCEEDED" in it })
            assertFalse(terminal.blockers.any { "PROVENANCE_VECTOR_REQUIRED_UNAVAILABLE" in it })
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `hardware fingerprint changes across firmware fingerprints`() {
        val first = HardwareFingerprint.create(capabilities("retroid/rp5/build-a"))
        val second = HardwareFingerprint.create(capabilities("retroid/rp5/build-b"))

        assertNotEquals(first, second)
    }

    private fun run(samples: List<CalibrationSample>) = CalibrationRun(
        profileId = "balanced",
        packageName = "example.game",
        hardwareFingerprint = "rp5-hash",
        startedAtEpochMs = 1_000L,
        requestedDurationSeconds = 300,
        samples = samples,
        preProfileHardware = samples.firstOrNull { it.hardware != null }?.hardware,
        completed = true,
    )

    private fun samples(): List<CalibrationSample> = (0..300).map { second ->
        CalibrationSample(
            elapsedMs = second * 1_000L,
            cpuTempC = 72,
            gpuTempC = 70,
            batteryTempDeciC = 390,
            batteryPowerMw = -5_200,
            batteryStatus = "Discharging",
            externalPowerState = ExternalPowerState.OFFLINE,
            thermalStatus = 1,
            gpuBusyPercent = 75,
            fanRpm = 4_800,
            foregroundPackageName = "example.game",
            frameStatus = FrameTelemetryStatus.AVAILABLE.name,
            frameLayerName = "SurfaceView[example.game/.GameActivity](BLAST)#42",
            frameAverageFpsMilli = 30_000,
            frameP50TimeUs = 33_333,
            frameP95TimeUs = 33_333,
            frameSlow20FpsPercent = 0,
            frameSlow30FpsPercent = 0,
            targetContractValid = true,
            hardware = provenance(
                performanceMode = 1,
                cpuCurrentKhz = 1_420_800,
                rawCpuTemp = 72_000L,
                capturedAtElapsedMs = second * 1_000L,
            ),
        )
    }

    private fun capabilities(buildFingerprint: String) = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint = buildFingerprint,
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/policy0", listOf(0, 1, 2, 3), listOf(300_000, 1_804_800), 300_000, 1_804_800, 1_804_800),
            CpuPolicy(4, "/policy4", listOf(4, 5, 6), listOf(710_400, 2_419_200), 710_400, 2_419_200, 2_419_200),
            CpuPolicy(7, "/policy7", listOf(7), listOf(844_800, 2_841_600), 844_800, 2_841_600, 2_841_600),
        ),
        gpuFrequenciesKhz = listOf(305_000, 905_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private fun runner(
        store: CalibrationReportStore,
        telemetry: TelemetrySnapshot = telemetry(),
        baselineTelemetry: () -> TelemetrySnapshot = ::stockTelemetry,
        fullTelemetry: () -> TelemetrySnapshot = { telemetry },
        onFinished: (CalibrationReport) -> Unit = {},
        externalBaseline: ((String, String, HardwareCapabilities) -> LabPreMutationBaselineOffer?)? = null,
        requiresExternalBaseline: (String) -> Boolean = { false },
        appliedTarget: ((String, String, HardwareCapabilities) -> CalibrationAppliedTargetOffer?)? = null,
        requiresAppliedTarget: (String) -> Boolean = { false },
    ): CalibrationSessionRunner {
        var baselineReads = if (externalBaseline != null) {
            CalibrationPreProfileBaselinePolicy.REQUIRED_READS
        } else {
            0
        }
        return CalibrationSessionRunner(
        telemetryProbe = object : CalibrationTelemetryProbe {
            override fun lightTelemetry(capabilities: HardwareCapabilities): TelemetrySnapshot = telemetry
            override fun fullTelemetry(capabilities: HardwareCapabilities): TelemetrySnapshot = if (
                baselineReads++ < CalibrationPreProfileBaselinePolicy.REQUIRED_READS
            ) {
                baselineTelemetry()
            } else {
                fullTelemetry()
            }
        },
        capabilities = { capabilities("rp5-test") },
        planner = TuningPlanner(),
        evaluator = evaluator,
        store = store,
        nowEpochMs = { 1_000L },
        nowElapsedMs = { 1_000L },
        foregroundPackage = { "example.game" },
        frameTelemetry = object : FrameTelemetryPort {
            override fun read(packageName: String): FrameTelemetryReading = FrameTelemetryReading(
                status = FrameTelemetryStatus.AVAILABLE,
                layerName = "SurfaceView[example.game/.GameActivity](BLAST)#42",
                averageFpsMilli = 30_000,
                p50FrameTimeUs = 33_333,
                p95FrameTimeUs = 33_333,
            )
        },
        onFinished = onFinished,
        preProfileBaselineSleeper = {},
        externalBaseline = externalBaseline,
        requiresExternalBaseline = requiresExternalBaseline,
        appliedTarget = appliedTarget,
        requiresAppliedTarget = requiresAppliedTarget,
        )
    }

    private fun appliedTargetOffer(
        profileId: String,
        packageName: String,
        live: HardwareCapabilities,
    ): CalibrationAppliedTargetOffer {
        val target = telemetry()
        val plan = TuningPlanner().plan(profileId, packageName, live)!!
        return CalibrationAppliedTargetOffer(
            contract = AppliedTargetContract(
                deviceProfile = live.deviceProfileId,
                bootId = target.bootId!!,
                cpuQosCeilingKhz = target.cpuQosCeilingKhz,
                cpuQosTransport = target.cpuQosTransport,
                gpuMinPowerLevel = target.gpuMinPowerLevel,
                gpuMaxPowerLevel = target.gpuMaxPowerLevel,
                fanMode = plan.fanMode,
            ),
            profileId = profileId,
            packageName = packageName,
            hardwareFingerprint = HardwareFingerprint.create(live),
            generation = 7L,
            transactionId = "transaction",
            requestId = "request",
            planHash = PlanHash.of(plan),
            leaseDeadlineElapsedMs = 99_000L,
        )
    }

    private fun telemetry() = TelemetrySnapshot(
        cpuCurrentKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
        cpuMinKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
        cpuMaxKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
        gpuClockHz = 905_000_000L,
        gpuBusyPercent = 75,
        gpuMinPowerLevel = 6,
        // Runner fixtures follow the current planner: the full KGSL ceiling.
        gpuMaxPowerLevel = 0,
        fanDuty = 128,
        fanPeriod = 255,
        fanRpm = 4_800,
        cpuTempC = 72,
        gpuTempC = 70,
        batteryLevelPercent = 50,
        batteryCurrentUa = -1_000,
        batteryVoltageMv = 5_200,
        batteryPowerMw = -5_200,
        batteryTempDeciC = 390,
        batteryStatus = "Discharging",
        externalPowerState = ExternalPowerState.OFFLINE,
        thermalStatus = 1,
        vendorPerformanceMode = 1,
        vendorFanMode = 4,
        cpuThermalZone = ThermalZoneWinner("cpu-usr", 72_000L, 72, "/sys/class/thermal/thermal_zone0"),
        gpuThermalZone = ThermalZoneWinner("kgsl-usr", 39_800L, 39, "/sys/class/thermal/thermal_zone1"),
        cpuQosCeilingKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
        cpuQosTransport = CpuQosTransport.POLICY_SCALING_MAX,
        bootId = "boot-id",
        thermalInventory = CalibrationThermalInventory(
            cpu = listOf(ThermalZoneIdentity("cpu-usr", "cpu-usr", "/sys/class/thermal/thermal_zone0")),
            gpu = listOf(ThermalZoneIdentity("kgsl-usr", "kgsl-usr", "/sys/class/thermal/thermal_zone1")),
        ),
    )

    private fun stockTelemetry() = telemetry().copy(
        cpuCurrentKhz = mapOf(0 to 1_804_800, 4 to 2_419_200, 7 to 2_841_600),
        cpuMinKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
        cpuMaxKhz = mapOf(0 to 1_804_800, 4 to 2_419_200, 7 to 2_841_600),
        gpuMaxPowerLevel = 0,
        vendorPerformanceMode = 0,
        vendorFanMode = 4,
        cpuQosCeilingKhz = mapOf(0 to 1_804_800, 4 to 2_419_200, 7 to 2_841_600),
    )

    private fun provenance(
        performanceMode: Int,
        cpuCurrentKhz: Int,
        rawCpuTemp: Long,
        capturedAtElapsedMs: Long = 0L,
    ) = CalibrationHardwareVector(
        schemaVersion = CALIBRATION_HARDWARE_VECTOR_SCHEMA,
        capturedAtElapsedMs = capturedAtElapsedMs,
        bootId = "boot-id",
        buildFingerprint = "fingerprint",
        vendorPerformanceMode = performanceMode,
        vendorFanMode = 4,
        cpuCurrentKhz = mapOf(0 to cpuCurrentKhz, 4 to 1_958_400, 7 to 2_361_600),
        cpuMinKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
        cpuMaxKhz = mapOf(0 to 1_420_800, 4 to 1_958_400, 7 to 2_361_600),
        cpuQosCeilingKhz = mapOf(0 to 1_420_800, 4 to 1_958_400, 7 to 2_361_600),
        cpuQosTransport = CpuQosTransport.POLICY_SCALING_MAX,
        gpuClockHz = 905_000_000L,
        gpuMinPowerLevel = 6,
        gpuMaxPowerLevel = 1,
        fanDuty = 128,
        fanPeriod = 255,
        fanRpm = 4_800,
        batteryLevelPercent = 50,
        batteryCurrentUa = -1_000,
        batteryVoltageMv = 5_200,
        batteryTempDeciC = 390,
        batteryStatus = "Discharging",
        androidThermalStatus = 1,
        cpuThermalZone = ThermalZoneWinner("cpu-usr", rawCpuTemp, (rawCpuTemp / 1_000L).toInt(), "/sys/class/thermal/thermal_zone0"),
        gpuThermalZone = ThermalZoneWinner("kgsl-usr", 39_800L, 39, "/sys/class/thermal/thermal_zone1"),
        thermalInventory = CalibrationThermalInventory(
            cpu = listOf(ThermalZoneIdentity("cpu-usr", "cpu-usr", "/sys/class/thermal/thermal_zone0")),
            gpu = listOf(ThermalZoneIdentity("kgsl-usr", "kgsl-usr", "/sys/class/thermal/thermal_zone1")),
        ),
    )

    private class InMemoryReportStore : CalibrationReportStore {
        private var report: CalibrationReport? = null
        var failTerminalWrites: Boolean = false

        override fun load(): CalibrationReport? = report

        override fun save(report: CalibrationReport): Boolean {
            this.report = report
            return true
        }

        override fun beginObservation(report: CalibrationReport): Boolean = save(report)

        override fun saveIfCurrent(observationId: String, report: CalibrationReport): Boolean {
            if (this.report?.observationId != observationId) return false
            if (failTerminalWrites) return false
            return save(report)
        }

        override fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport? = null

        override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> = emptySet()
    }
}
