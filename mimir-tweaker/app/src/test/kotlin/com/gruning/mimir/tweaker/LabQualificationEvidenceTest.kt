package com.gruning.mimir.tweaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabQualificationEvidenceTest {
    @Test
    fun `selection record retains requested targets and guardian identity`() {
        val record = LabQualificationEvidence.selection(
            requestedProfileId = "quiet",
            result = TransactionResult(
                success = true,
                applied = true,
                restored = false,
                code = "APPLIED",
                message = "Applied.",
                plan = TuningPlan(
                    profileId = "quiet",
                    packageName = "",
                    cpuMaxKhz = mapOf(6 to 2_246_400, 0 to 1_996_800),
                    gpuMaxPowerLevel = 0,
                    fanMode = null,
                    calibrated = true,
                ),
                transactionId = "txn-1",
                generation = 7,
                hardLeaseDeadlineElapsedRealtimeMs = 90_000,
            ),
            truth = PublicSessionTruth(
                state = PublicSessionState.ACTIVE,
                mutationMayBeActive = true,
                profileId = "quiet",
                generation = 7,
                transactionId = "txn-1",
                hardLeaseDeadlineElapsedRealtimeMs = 90_000,
            ),
        )

        assertTrue(record.contains("event=selection requested=quiet result=APPLIED"))
        assertTrue(record.contains("cpu_target_khz=0:1996800,6:2246400"))
        assertTrue(record.contains("gpu_target_pwrlevel=0 fan_target=na"))
        assertTrue(record.contains("generation=7 transaction=txn-1 hard_deadline_ms=90000"))
    }

    @Test
    fun `sample record uses package temperature and never invents absent readings`() {
        val record = LabQualificationEvidence.sample(
            telemetry = TelemetrySnapshot(
                cpuCurrentKhz = mapOf(0 to 1_500_000),
                cpuMinKhz = mapOf(0 to 384_000),
                cpuMaxKhz = mapOf(0 to 3_532_800),
                gpuClockHz = 680_000_000,
                gpuBusyPercent = 72,
                gpuMinPowerLevel = 7,
                gpuMaxPowerLevel = 0,
                fanDuty = null,
                fanPeriod = null,
                fanRpm = 2_400,
                cpuTempC = 68,
                gpuTempC = 55,
                batteryLevelPercent = 74,
                batteryCurrentUa = null,
                batteryVoltageMv = null,
                batteryPowerMw = -7_200,
                batteryTempDeciC = 361,
                batteryStatus = "Discharging",
                thermalStatus = 0,
                vendorFanMode = 2,
                cpuQosCeilingKhz = mapOf(0 to 1_996_800),
                cpuPackageTempC = 49,
            ),
            live = LivePerformanceTelemetry(
                sampledAtElapsedRealtimeMs = 12_345,
                cpuBusyPercent = 41,
                foregroundPackageName = "org.dolphinemu.dolphinemu",
                frame = FrameTelemetryReading(
                    status = FrameTelemetryStatus.AVAILABLE,
                    averageFpsMilli = 59_940,
                ),
            ),
            truth = PublicSessionTruth(
                state = PublicSessionState.ACTIVE,
                mutationMayBeActive = true,
                profileId = "quiet",
            ),
        )

        assertTrue(record.contains("cpu_package_c=49 cpu_safety_c=68"))
        assertTrue(record.contains("battery_pct=74 battery_mw=-7200"))
        assertTrue(record.contains("fps_status=AVAILABLE fps_milli=59940"))
        assertFalse(record.contains("null"))
    }

    @Test
    fun `guardian restore record keeps exact cause and transaction identity`() {
        val record = LabQualificationEvidence.guardianRestore(
            SupervisorDecision.Restore(
                generation = 119,
                transactionId = "txn-quiet",
                reason = "TARGET_CONTRACT_DRIFT_CPU",
            ),
        )

        assertTrue(record.contains("event=guardian_restore reason=TARGET_CONTRACT_DRIFT_CPU"))
        assertTrue(record.contains("generation=119 transaction=txn-quiet"))
    }

    @Test
    fun `target repair evidence preserves attempt and fallback settlement`() {
        val decision = SupervisorDecision.RepairCpuTarget(
            generation = 120,
            transactionId = "txn-quiet",
            reason = "TARGET_CONTRACT_DRIFT_CPU",
        )
        val attempt = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "TARGET_REPAIR_FAILED",
            message = "write failed",
        )
        val settlement = TransactionResult(
            success = true,
            applied = false,
            restored = true,
            code = "RESTORED",
            message = "Stock restored",
        )

        val record = LabQualificationEvidence.targetRepair(decision, attempt, settlement)

        assertTrue(record.contains("attempt=TARGET_REPAIR_FAILED attempt_success=false"))
        assertTrue(record.contains("settlement=RESTORED restored=true"))
        assertFalse(record.contains("null"))
    }
}
