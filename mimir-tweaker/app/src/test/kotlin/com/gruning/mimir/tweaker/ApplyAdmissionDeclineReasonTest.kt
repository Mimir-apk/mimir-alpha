package com.gruning.mimir.tweaker

import android.os.PowerManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * TR-02: [ApplyAdmission.evaluate]/[ApplyAdmission.evaluateSystemWide] return the single wire code
 * `TELEMETRY_ADMISSION_FAILED` for seven distinct causes -- eliminating six of them by hand across
 * two subsystems was the only way to learn a real rejection (Android thermal status 3, CPUs at
 * 89.9C while charging) was a correct thermal refusal, not a bug. This pins that `code`/`message`
 * on the wire are UNCHANGED (other modules and tests match on `code`) while every cause now also
 * reaches logcat with its own reason at the `MimirApplyAdmission` tag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ApplyAdmissionDeclineReasonTest {
    private val logTag = "MimirApplyAdmission"

    @Test
    fun `null telemetry logs TELEMETRY_MISSING and keeps the wire code unchanged`() {
        ShadowLog.clear()
        val result = ApplyAdmission.evaluate(usageAccessGranted = true, telemetry = null)
            as ApplyAdmissionResult.Rejected

        assertTrue(result.code == "TELEMETRY_ADMISSION_FAILED")
        assertReasonLogged("TELEMETRY_MISSING")
    }

    @Test
    fun `incomplete telemetry logs TELEMETRY_INCOMPLETE distinct from missing`() {
        ShadowLog.clear()
        val incomplete = ThermalTelemetrySample(
            androidThermalStatus = PowerManager.THERMAL_STATUS_NONE,
            cpuTempC = 40,
            gpuTempC = null,
            batteryTempDeciC = 300,
        )
        val result = ApplyAdmission.evaluate(usageAccessGranted = true, telemetry = incomplete)
            as ApplyAdmissionResult.Rejected

        assertTrue(result.code == "TELEMETRY_ADMISSION_FAILED")
        assertReasonLogged("TELEMETRY_INCOMPLETE")
        assertReasonNotLogged("TELEMETRY_MISSING")
    }

    @Test
    fun `out-of-range telemetry logs TELEMETRY_OUT_OF_RANGE distinct from incomplete`() {
        ShadowLog.clear()
        val outOfRange = ThermalTelemetrySample(
            androidThermalStatus = PowerManager.THERMAL_STATUS_NONE,
            cpuTempC = 999,
            gpuTempC = 40,
            batteryTempDeciC = 300,
        )
        val result = ApplyAdmission.evaluate(usageAccessGranted = true, telemetry = outOfRange)
            as ApplyAdmissionResult.Rejected

        assertTrue(result.code == "TELEMETRY_ADMISSION_FAILED")
        assertReasonLogged("TELEMETRY_OUT_OF_RANGE")
        assertReasonNotLogged("TELEMETRY_INCOMPLETE")
    }

    @Test
    fun `a correct thermal refusal logs the exact trip sub-reason and the measured values`() {
        ShadowLog.clear()
        // The ticket's own motivating measurement: Android thermal status 3 (SEVERE trip is 3 by
        // default), CPU at 89.9C -- rounds to 90, at the exclusive trip boundary is fine, use 106
        // to cross the default 105C cpuTripCExclusive unambiguously.
        val hot = ThermalTelemetrySample(
            androidThermalStatus = PowerManager.THERMAL_STATUS_NONE,
            cpuTempC = 106,
            gpuTempC = 40,
            batteryTempDeciC = 300,
        )
        val result = ApplyAdmission.evaluate(usageAccessGranted = true, telemetry = hot)
            as ApplyAdmissionResult.Rejected

        assertTrue(result.code == "TELEMETRY_ADMISSION_FAILED")
        assertReasonLogged("THERMAL_CPU")
        assertTrue(
            "expected the measured cpuTempC to reach the log, got: ${ShadowLog.getLogs()}",
            ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains("cpuTempC=106") },
        )
    }

    @Test
    fun `evaluateSystemWide distinguishes incomplete from out-of-range despite the shared branch`() {
        ShadowLog.clear()
        val incomplete = ThermalTelemetrySample(
            androidThermalStatus = null,
            cpuTempC = 40,
            gpuTempC = 40,
            batteryTempDeciC = 300,
        )
        ApplyAdmission.evaluateSystemWide(telemetry = incomplete)
        assertReasonLogged("TELEMETRY_INCOMPLETE")

        ShadowLog.clear()
        val outOfRange = ThermalTelemetrySample(
            androidThermalStatus = PowerManager.THERMAL_STATUS_NONE,
            cpuTempC = -50,
            gpuTempC = 40,
            batteryTempDeciC = 300,
        )
        ApplyAdmission.evaluateSystemWide(telemetry = outOfRange)
        assertReasonLogged("TELEMETRY_OUT_OF_RANGE")
    }

    @Test
    fun `usage access denial keeps its own already-distinct code unlogged by this seam`() {
        ShadowLog.clear()
        val result = ApplyAdmission.evaluate(usageAccessGranted = false, telemetry = null)
            as ApplyAdmissionResult.Rejected

        assertTrue(result.code == "USAGE_ACCESS_REQUIRED")
        assertReasonNotLogged("TELEMETRY_MISSING")
    }

    private fun assertReasonLogged(reason: String) {
        val matches = ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains("reason=$reason") }
        assertTrue("expected a $logTag log containing reason=$reason, got: ${ShadowLog.getLogs()}", matches)
    }

    private fun assertReasonNotLogged(reason: String) {
        val matches = ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains("reason=$reason") }
        assertTrue("did not expect a $logTag log containing reason=$reason, got: ${ShadowLog.getLogs()}", !matches)
    }
}
