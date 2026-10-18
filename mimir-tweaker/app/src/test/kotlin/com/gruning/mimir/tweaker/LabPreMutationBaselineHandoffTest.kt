package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabPreMutationBaselineHandoffTest {
    @Test
    fun `failed LAB baseline gate executes zero transaction closure while SAFE and public profiles stay unchanged`() {
        var captureCalls = 0
        var transactionWrites = 0
        val rejected = if (LabPreMutationBaselineApplyGate.permits(true, "quiet") {
                captureCalls += 1
                false
            }
        ) {
            transactionWrites += 1
            true
        } else {
            false
        }
        assertFalse(rejected)
        assertEquals(1, captureCalls)
        assertEquals(0, transactionWrites)

        assertTrue(LabPreMutationBaselineApplyGate.permits(false, "quiet") {
            captureCalls += 1
            false
        })
        assertTrue(LabPreMutationBaselineApplyGate.permits(true, "balanced") {
            captureCalls += 1
            false
        })
        assertEquals(1, captureCalls)

        assertTrue(
            LabPreMutationBaselineApplyGate.permits(
                diagnosticsCompiled = true,
                profileId = "quiet",
                requiresCalibrationBaseline = false,
            ) {
                captureCalls += 1
                false
            },
        )
        assertEquals(1, captureCalls)
    }

    @Test
    fun `Quiet capture requires three coherent exact Stock reads and binds only APPLIED identity`() {
        val delays = mutableListOf<Long>()
        val handoff = LabPreMutationBaselineHandoff(nowElapsedMs = { 1_000L }, sleep = delays::add)
        val caps = capabilities()

        assertTrue(handoff.captureBeforeApply("quiet", "example.game", caps) { stockTelemetry() })
        assertEquals(listOf(2_000L, 250L, 250L), delays)
        assertFalse(handoff.onAppliedAdopt(record(caps).copy(phase = TransactionPhase.APPLYING)))

        assertTrue(handoff.captureBeforeApply("quiet", "example.game", caps) { stockTelemetry() })
        assertTrue(handoff.onAppliedAdopt(record(caps)))
        val offer = handoff.consume("quiet", "example.game", caps, record(caps))
        assertEquals(CalibrationPreProfileBaselinePolicy.REQUIRED_READS, offer?.vectors?.size)
        assertEquals("quiet", offer?.binding?.profileId)
        assertTrue(offer?.observationId?.isNotBlank() == true)
        assertNull(handoff.consume("quiet", "example.game", caps, record(caps)))
    }

    @Test
    fun `non Quiet invalid or mismatched consumption destroys the one shot offer`() {
        val caps = capabilities()
        val nonQuiet = LabPreMutationBaselineHandoff({ 1_000L }, sleep = {})
        assertFalse(nonQuiet.captureBeforeApply("balanced", "example.game", caps) { stockTelemetry() })

        // The Stock baseline no longer requires vendor performance_mode=0 (2026-10-13 vendor-mode
        // design: Stock never writes the key, so the player's own mode IS the baseline). The
        // invariant that still makes a baseline invalid here is the vendor fan mode.
        val playersOwnMode = LabPreMutationBaselineHandoff({ 1_000L }, sleep = {})
        assertTrue(playersOwnMode.captureBeforeApply("quiet", "example.game", caps) {
            stockTelemetry().copy(vendorPerformanceMode = 1)
        })

        val invalid = LabPreMutationBaselineHandoff({ 1_000L }, sleep = {})
        assertFalse(invalid.captureBeforeApply("quiet", "example.game", caps) {
            stockTelemetry().copy(vendorFanMode = 3)
        })

        val mismatched = LabPreMutationBaselineHandoff({ 1_000L }, sleep = {})
        assertTrue(mismatched.captureBeforeApply("quiet", "example.game", caps) { stockTelemetry() })
        val record = record(caps)
        assertTrue(mismatched.onAppliedAdopt(record))
        assertNull(mismatched.consume("quiet", "example.game", caps, record.copy(profileId = "balanced")))
        assertNull(mismatched.consume("quiet", "example.game", caps, record))
    }

    @Test
    fun `plan hash expiry and lifecycle clear invalidate an attached offer`() {
        val caps = capabilities()
        val record = record(caps)
        val planMismatch = LabPreMutationBaselineHandoff({ 1_000L }, sleep = {})
        assertTrue(planMismatch.captureBeforeApply("quiet", "example.game", caps) { stockTelemetry() })
        assertTrue(planMismatch.onAppliedAdopt(record))
        assertNull(planMismatch.consume("quiet", "example.game", caps, record.copy(planHash = "other-plan")))

        val expired = LabPreMutationBaselineHandoff({ 10_000L }, sleep = {})
        assertTrue(expired.captureBeforeApply("quiet", "example.game", caps) { stockTelemetry() })
        assertTrue(expired.onAppliedAdopt(record))
        assertNull(expired.consume("quiet", "example.game", caps, record))

        val restored = LabPreMutationBaselineHandoff({ 1_000L }, sleep = {})
        assertTrue(restored.captureBeforeApply("quiet", "example.game", caps) { stockTelemetry() })
        assertTrue(restored.onAppliedAdopt(record))
        restored.clear() // Restore/onDestroy/process recreation must leave no offer behind.
        assertNull(restored.consume("quiet", "example.game", caps, record))

        val rebootedCapabilities = caps.copy(buildFingerprint = "retroid/rp5/rebooted")
        val reboot = LabPreMutationBaselineHandoff({ 1_000L }, sleep = {})
        assertTrue(reboot.captureBeforeApply("quiet", "example.game", caps) { stockTelemetry() })
        assertTrue(reboot.onAppliedAdopt(record))
        assertNull(reboot.consume("quiet", "example.game", rebootedCapabilities, record))
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "Retroid",
        model = "Pocket 5",
        socModel = "SM8250",
        buildFingerprint = "retroid/rp5/test",
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/policy0", listOf(0), listOf(300_000, 1_804_800), 300_000, 1_804_800, 1_804_800),
            CpuPolicy(4, "/policy4", listOf(4), listOf(710_400, 2_419_200), 710_400, 2_419_200, 2_419_200),
            CpuPolicy(7, "/policy7", listOf(7), listOf(844_800, 2_841_600), 844_800, 2_841_600, 2_841_600),
        ),
        gpuFrequenciesKhz = listOf(305_000, 905_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private fun stockTelemetry() = TelemetrySnapshot(
        cpuCurrentKhz = mapOf(0 to 1_804_800, 4 to 2_419_200, 7 to 2_841_600),
        cpuMinKhz = mapOf(0 to 300_000, 4 to 710_400, 7 to 844_800),
        cpuMaxKhz = mapOf(0 to 1_804_800, 4 to 2_419_200, 7 to 2_841_600),
        gpuClockHz = 905_000_000L,
        gpuBusyPercent = 50,
        gpuMinPowerLevel = 6,
        gpuMaxPowerLevel = 0,
        fanDuty = 128,
        fanPeriod = 255,
        fanRpm = 4_800,
        cpuTempC = 60,
        gpuTempC = 55,
        batteryLevelPercent = 50,
        batteryCurrentUa = -1_000,
        batteryVoltageMv = 5_000,
        batteryPowerMw = -5_000,
        batteryTempDeciC = 360,
        batteryStatus = "Discharging",
        thermalStatus = 1,
        vendorPerformanceMode = 0,
        vendorFanMode = 4,
        cpuQosCeilingKhz = mapOf(0 to 1_804_800, 4 to 2_419_200, 7 to 2_841_600),
        cpuQosTransport = CpuQosTransport.POLICY_SCALING_MAX,
        bootId = "boot-id",
        cpuThermalZone = ThermalZoneWinner("cpu-usr", 60_000L, 60, "/sys/class/thermal/thermal_zone0"),
        gpuThermalZone = ThermalZoneWinner("kgsl-usr", 55_000L, 55, "/sys/class/thermal/thermal_zone1"),
        thermalInventory = CalibrationThermalInventory(
            cpu = listOf(ThermalZoneIdentity("cpu-usr", "cpu-usr", "/sys/class/thermal/thermal_zone0")),
            gpu = listOf(ThermalZoneIdentity("kgsl-usr", "kgsl-usr", "/sys/class/thermal/thermal_zone1")),
        ),
    )

    private fun record(caps: HardwareCapabilities) = TransactionJournalRecord(
        generation = 7L,
        requestId = "request",
        transactionId = "transaction",
        hardwareFingerprint = HardwareFingerprint.create(caps),
        deviceProfile = "rp5",
        planHash = "plan",
        profileId = "quiet",
        targetPackage = "example.game",
        phase = TransactionPhase.APPLIED,
        creationTime = 1L,
        requestDeadline = 5_000L,
        leaseDeadline = 10_000L,
        bootIdentity = "boot-id",
        snapshot = HardwareSnapshot(HARDWARE_SNAPSHOT_SCHEMA, HardwareFingerprint.create(caps), "boot-id", emptyList()),
        expectedTopologySha256 = "topology",
        snapshotSha256 = "snapshot",
        lastCompletedMutationBoundary = 0,
    )
}
