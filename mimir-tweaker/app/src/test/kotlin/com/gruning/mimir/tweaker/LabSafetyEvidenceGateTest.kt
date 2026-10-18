package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TW04-A: `recordLabSafetyEvidence` is retired. Caller booleans/counters must never mutate or
 * certify a report, in any posture.
 */
class LabSafetyEvidenceGateTest {
    @Test
    fun `retired evidence path refuses and cannot certify in safe posture`() {
        val store = SingleReportStore().apply { save(observedReport()) }
        val runner = runner(store, hardwareWritesCompiled = false, diagnosticsCompiled = false)

        val result = runner.recordLabEvidence(
            writesExercised = true,
            rollbackAttempts = 3,
            rollbackSuccesses = 3,
            rebootRestoreVerified = true,
        )

        assertFalse(result.success)
        assertEquals("LAB_EVIDENCE_RETIRED", result.code)
        assertEquals(1, store.saves)
        assertEquals(CalibrationDisposition.OBSERVED, store.load()!!.disposition)
        assertFalse(store.load()!!.evidence.writesExercised)
        assertEquals(0, store.load()!!.evidence.rollbackAttempts)
        assertFalse(store.load()!!.evidence.rebootRestoreVerified)
    }

    @Test
    fun `retired evidence path refuses even with no report`() {
        val store = SingleReportStore()
        val runner = runner(store, hardwareWritesCompiled = false, diagnosticsCompiled = false)

        val result = runner.recordLabEvidence(true, 3, 3, true)

        assertFalse(result.success)
        assertEquals("LAB_EVIDENCE_RETIRED", result.code)
        assertEquals(0, store.saves)
    }

    @Test
    fun `lab posture also refuses retired evidence and never certifies`() {
        val store = SingleReportStore().apply { save(observedReport()) }
        val runner = runner(store, hardwareWritesCompiled = true, diagnosticsCompiled = true)

        val result = runner.recordLabEvidence(
            writesExercised = true,
            rollbackAttempts = 3,
            rollbackSuccesses = 3,
            rebootRestoreVerified = true,
        )

        assertFalse(result.success)
        assertEquals("LAB_EVIDENCE_RETIRED", result.code)
        val updated = store.load()!!
        assertEquals(CalibrationDisposition.OBSERVED, updated.disposition)
        assertFalse(updated.evidence.writesExercised)
        assertEquals(0, updated.evidence.rollbackAttempts)
        assertFalse(updated.evidence.rebootRestoreVerified)
        assertEquals(1, store.saves)
    }

    @Test
    fun `production posture refuses retired evidence`() {
        val store = SingleReportStore().apply { save(observedReport()) }
        val runner = runner(store, hardwareWritesCompiled = true, diagnosticsCompiled = false)

        val result = runner.recordLabEvidence(true, 3, 3, true)

        assertFalse(result.success)
        assertEquals("LAB_EVIDENCE_RETIRED", result.code)
        assertEquals(1, store.saves)
    }

    private fun runner(
        store: CalibrationReportStore,
        hardwareWritesCompiled: Boolean,
        diagnosticsCompiled: Boolean,
    ) = RollbackDrillRunner(
        transaction = TransactionCoordinator(
            planner = TuningPlanner { _, _ -> true },
            state = UnusedState,
            commands = UnusedCommands,
            journal = UnusedJournal,
            hardwareWritesCompiled = hardwareWritesCompiled,
        ),
        capabilities = ::capabilities,
        planner = TuningPlanner { _, _ -> true },
        calibrationStore = store,
        hardwareWritesCompiled = hardwareWritesCompiled,
        diagnosticsCompiled = diagnosticsCompiled,
    )

    private fun observedReport(): CalibrationReport {
        val plan = TuningPlanner().plan("balanced", WORKLOAD, capabilities())!!
        val samples = samples(plan)
        return CalibrationEvaluator().evaluate(
            run = CalibrationRun(
                profileId = "balanced",
                packageName = WORKLOAD,
                hardwareFingerprint = HardwareFingerprint.create(capabilities()),
                startedAtEpochMs = 1_000L,
                requestedDurationSeconds = 300,
                samples = samples,
                preProfileHardware = samples.first().hardware,
                completed = true,
            ),
            candidate = plan,
            evidence = CalibrationSafetyEvidence(
                workloadAttested = true,
                frameTelemetryAttested = true,
            ),
        )
    }

    private fun samples(plan: TuningPlan): List<CalibrationSample> = (0..300).map { second ->
        CalibrationSample(
            elapsedMs = second * 1_000L,
            cpuTempC = 72,
            gpuTempC = 70,
            batteryTempDeciC = 390,
            batteryPowerMw = -5_200,
            batteryStatus = "Discharging",
            thermalStatus = 1,
            gpuBusyPercent = 75,
            fanRpm = 4_800,
            foregroundPackageName = WORKLOAD,
            frameStatus = FrameTelemetryStatus.AVAILABLE.name,
            frameLayerName = "SurfaceView[$WORKLOAD/.GameActivity](BLAST)#42",
            frameAverageFpsMilli = 30_000,
            frameP50TimeUs = 33_333,
            frameP95TimeUs = 33_333,
            frameSlow20FpsPercent = 0,
            frameSlow30FpsPercent = 0,
            targetContractValid = true,
            hardware = CalibrationHardwareVector(
                schemaVersion = CALIBRATION_HARDWARE_VECTOR_SCHEMA,
                capturedAtElapsedMs = second * 1_000L,
                bootId = "rp5-test-boot",
                buildFingerprint = "rp5/test",
                vendorPerformanceMode = 1,
                vendorFanMode = 4,
                cpuCurrentKhz = plan.cpuMaxKhz,
                cpuMinKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
                cpuMaxKhz = plan.cpuMaxKhz,
                cpuQosCeilingKhz = plan.cpuMaxKhz,
                cpuQosTransport = CpuQosTransport.POLICY_SCALING_MAX,
                gpuClockHz = 905_000_000L,
                gpuMinPowerLevel = 6,
                gpuMaxPowerLevel = plan.gpuMaxPowerLevel,
                fanDuty = 128,
                fanPeriod = 255,
                fanRpm = 4_800,
                batteryLevelPercent = 50,
                batteryCurrentUa = -1_000,
                batteryVoltageMv = 5_200,
                batteryTempDeciC = 390,
                batteryStatus = "Discharging",
                androidThermalStatus = 1,
                cpuThermalZone = ThermalZoneWinner("cpu-usr", 72_000L, 72, "/sys/class/thermal/thermal_zone0"),
                gpuThermalZone = ThermalZoneWinner("kgsl-usr", 39_800L, 39, "/sys/class/thermal/thermal_zone1"),
                thermalInventory = CalibrationThermalInventory(
                    cpu = listOf(ThermalZoneIdentity("cpu-usr", "cpu-usr", "/sys/class/thermal/thermal_zone0")),
                    gpu = listOf(ThermalZoneIdentity("kgsl-usr", "kgsl-usr", "/sys/class/thermal/thermal_zone1")),
                ),
            ),
        )
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint = "rp5/test",
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

    private class SingleReportStore : CalibrationReportStore {
        private var latest: CalibrationReport? = null
        var saves = 0
            private set

        override fun load(): CalibrationReport? = latest

        override fun save(report: CalibrationReport): Boolean {
            saves++
            latest = report
            return true
        }

        override fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport? = null

        override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> = emptySet()
    }

    private object UnusedState : HardwareStatePort {
        override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome =
            error("no hardware access on the evidence path")
        override fun verify(plan: TuningPlan): Boolean = error("no hardware access on the evidence path")
        override fun verifyPlan(plan: TuningPlan): PlanVerification =
            error("no hardware access on the evidence path")
        override fun verifyAppliedPlan(
            plan: TuningPlan,
            mutations: List<LogicalMutation>,
        ): PlanVerification = error("no hardware access on the evidence path")
        override fun verifySnapshot(snapshot: HardwareSnapshot): Boolean =
            error("no hardware access on the evidence path")
        override fun verifySnapshotPlan(snapshot: HardwareSnapshot): PlanVerification =
            error("no hardware access on the evidence path")
        override fun admitCpuTargetsAfterCooling(plan: TuningPlan): PlanVerification =
            error("no hardware access on the evidence path")
        override fun readback(mutation: LogicalMutation): Boolean =
            error("no hardware access on the evidence path")
    }

    private object UnusedCommands : HardwareCommandPort {
        override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot): List<LogicalMutation> =
            error("no hardware access on the evidence path")
        override fun restoreMutations(record: TransactionJournalRecord): List<LogicalMutation> =
            error("no hardware access on the evidence path")
        override fun execute(mutation: LogicalMutation): CheckedCommandResult =
            error("no hardware access on the evidence path")
        override fun validateRestore(record: TransactionJournalRecord): PlanVerification =
            error("no hardware access on the evidence path")
    }

    private object UnusedJournal : TransactionJournal {
        override fun load(): JournalLoadResult = JournalLoadResult.Empty
        override fun nextGeneration(): GenerationResult = error("no journal access on the evidence path")
        override fun persist(record: TransactionJournalRecord): Boolean =
            error("no journal access on the evidence path")
        override fun clear(expectedTransactionId: String): Boolean =
            error("no journal access on the evidence path")
    }

    private companion object {
        const val WORKLOAD = "example.game"
    }
}
