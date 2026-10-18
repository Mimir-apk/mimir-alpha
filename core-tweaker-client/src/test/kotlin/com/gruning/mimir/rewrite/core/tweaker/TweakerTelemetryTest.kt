package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerTelemetryTest {
    @Test
    fun `parser preserves valid optional live telemetry`() {
        val parsed = parseTweakerTelemetryBundle(Bundle().apply {
            putLong("sampledAtElapsedRealtimeMs", 123L)
            putString("foregroundPackageName", "org.ppsspp.ppsspp")
            putString("frameStatus", "AVAILABLE")
            putInt("frameAverageFpsMilli", 59_940)
            putInt("cpuTempC", 43)
            putInt("cpuPackageTempC", 39)
            putInt("gpuTempC", 39)
            putInt("cpuBusyPercent", 36)
            putInt("gpuBusyPercent", 72)
            putInt("batteryPowerMw", 2_100)
            putString("batteryStatus", "Discharging")
            putStringArrayList(
                "cpuCurrentKhz",
                arrayListOf("policy6=3072000", "policy0=998400"),
            )
            putLong("gpuClockHz", 900_000_000L)
            putString("activeFanProfileId", "balanced")
            putString("fanControlStrategy", "HOLD_TARGET")
            putInt("fanTargetTempC", 65)
            putInt("fanPowerReferenceMw", 11_250)
            putInt("vendorFanMode", 6)
            putInt("fanDuty", 12_500)
            putInt("fanPeriod", 50_000)
            putInt("fanRpm", 0)
        })

        assertEquals(59_940, parsed.frameAverageFpsMilli)
        assertEquals(39, parsed.cpuPackageTempC)
        assertEquals(36, parsed.cpuBusyPercent)
        assertEquals(72, parsed.gpuBusyPercent)
        assertEquals(listOf(998_400_000L, 3_072_000_000L), parsed.cpuPolicyClocksHz)
        assertEquals(900_000_000L, parsed.gpuClockHz)
        assertEquals("org.ppsspp.ppsspp", parsed.foregroundPackageName)
        assertEquals(
            TweakerFanControlReadout(
                profileId = "balanced",
                strategy = TweakerFanControlStrategy.HOLD_TARGET,
                targetTempC = 65,
                calibrationPowerReferenceMw = 11_250,
                dutyPercent = 25,
                rpm = 0,
            ),
            parsed.fanControl,
        )
    }

    @Test
    fun `parser preserves the frame averaging window its data age and the package zone count`() {
        // A 6-frame 100 ms average and a 200-frame 3340 ms average must be distinguishable
        // downstream; before these fields the daemon computed and then dropped both figures.
        val lowSample = parseTweakerTelemetryBundle(Bundle().apply {
            putInt("frameAverageFpsMilli", 59_940)
            putInt("frameCount", 6)
            putInt("frameWindowDurationMs", 100)
            putInt("frameLatestPresentAgeMs", 1_500)
            putInt("cpuPackageTempC", 39)
            putInt("cpuPackageTempZoneCount", 8)
        })
        val richSample = parseTweakerTelemetryBundle(Bundle().apply {
            putInt("frameAverageFpsMilli", 59_940)
            putInt("frameCount", 200)
            putInt("frameWindowDurationMs", 3_340)
            putInt("frameLatestPresentAgeMs", 0)
        })

        assertEquals(6, lowSample.frameCount)
        assertEquals(100, lowSample.frameWindowDurationMs)
        assertEquals(1_500, lowSample.frameLatestPresentAgeMs)
        assertEquals(8, lowSample.cpuPackageTempZoneCount)
        assertEquals(200, richSample.frameCount)
        assertEquals(3_340, richSample.frameWindowDurationMs)
        assertEquals(0, richSample.frameLatestPresentAgeMs)
        assertNull(richSample.cpuPackageTempZoneCount)
    }

    @Test
    fun `parser rejects invalid frame window and zone count values without fabricating them`() {
        val parsed = parseTweakerTelemetryBundle(Bundle().apply {
            putInt("frameCount", 0)
            putInt("frameWindowDurationMs", -5)
            putInt("frameLatestPresentAgeMs", -1)
            putInt("cpuPackageTempZoneCount", 0)
        })

        assertNull(parsed.frameCount)
        assertNull(parsed.frameWindowDurationMs)
        assertNull(parsed.frameLatestPresentAgeMs)
        assertNull(parsed.cpuPackageTempZoneCount)
    }

    @Test
    fun `parser rejects invalid ranges without fabricating values`() {
        val parsed = parseTweakerTelemetryBundle(Bundle().apply {
            putInt("frameAverageFpsMilli", 0)
            putInt("cpuTempC", 999)
            putInt("cpuPackageTempC", 999)
            putInt("cpuBusyPercent", 101)
            putInt("gpuBusyPercent", -1)
            putStringArrayList(
                "cpuCurrentKhz",
                arrayListOf("policy0=-1", "policyX=998400", "not-a-policy"),
            )
            putLong("gpuClockHz", 0L)
        })

        assertNull(parsed.frameAverageFpsMilli)
        assertNull(parsed.cpuTempC)
        assertNull(parsed.cpuPackageTempC)
        assertNull(parsed.cpuBusyPercent)
        assertNull(parsed.gpuBusyPercent)
        assertEquals(emptyList<Long>(), parsed.cpuPolicyClocksHz)
        assertNull(parsed.gpuClockHz)
        assertNull(parsed.fanControl)
    }

    @Test
    fun `fan readout rejects partial mismatched or unknown strategy truth as one unit`() {
        fun base() = Bundle().apply {
            putString("activeFanProfileId", "quiet")
            putString("fanControlStrategy", "HOLD_TARGET")
            putInt("fanTargetTempC", 55)
            putInt("fanPowerReferenceMw", 8_000)
            putInt("vendorFanMode", 6)
            putInt("fanDuty", 10_000)
            putInt("fanPeriod", 50_000)
        }

        assertNull(parseTweakerTelemetryBundle(base().apply { remove("fanPeriod") }).fanControl)
        assertNull(parseTweakerTelemetryBundle(base().apply { putInt("fanDuty", 50_001) }).fanControl)
        assertNull(parseTweakerTelemetryBundle(base().apply { putInt("vendorFanMode", 4) }).fanControl)
        assertNull(parseTweakerTelemetryBundle(base().apply {
            putString("fanControlStrategy", "UNKNOWN")
        }).fanControl)
        assertEquals(
            TweakerFanControlStrategy.AUTO_CURVE,
            parseTweakerTelemetryBundle(base().apply {
                putString("fanControlStrategy", "AUTO_CURVE")
            }).fanControl?.strategy,
        )
    }

    @Test
    fun `fan readout accepts SPORT the mode Odin sessions actually run, same as MANUAL`() {
        // Odin sessions run vendor fan mode 5 (SPORT) since 95f6fa7b, not 6 (MANUAL). Both are
        // Mimir-duty-owned per TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES; a stale mode-6-only
        // check left the already-built fan readout permanently null on real sessions.
        fun base(vendorFanMode: Int) = Bundle().apply {
            putString("activeFanProfileId", "balanced")
            putString("fanControlStrategy", "AUTO_CURVE")
            putInt("fanTargetTempC", 65)
            putInt("fanPowerReferenceMw", 11_250)
            putInt("vendorFanMode", vendorFanMode)
            putInt("fanDuty", 12_500)
            putInt("fanPeriod", 50_000)
            putInt("fanRpm", 3_200)
        }

        val sportReadout = parseTweakerTelemetryBundle(base(5)).fanControl
        val manualReadout = parseTweakerTelemetryBundle(base(6)).fanControl

        assertEquals(
            TweakerFanControlReadout(
                profileId = "balanced",
                strategy = TweakerFanControlStrategy.AUTO_CURVE,
                targetTempC = 65,
                calibrationPowerReferenceMw = 11_250,
                dutyPercent = 25,
                rpm = 3_200,
            ),
            sportReadout,
        )
        assertEquals(sportReadout?.copy(), manualReadout)
        assertNull(parseTweakerTelemetryBundle(base(4)).fanControl)
    }

    @Test
    fun `CPU clock vector rejects duplicate policy rows as one unit`() {
        val parsed = parseTweakerTelemetryBundle(Bundle().apply {
            putStringArrayList(
                "cpuCurrentKhz",
                arrayListOf("policy0=998400", "policy0=2227200"),
            )
        })

        assertEquals(emptyList<Long>(), parsed.cpuPolicyClocksHz)
    }

    @Test
    fun `CPU clock vector rejects a valid row mixed with malformed input`() {
        val parsed = parseTweakerTelemetryBundle(Bundle().apply {
            putStringArrayList(
                "cpuCurrentKhz",
                arrayListOf("policy0=998400", "policyX=2227200"),
            )
        })

        assertEquals(emptyList<Long>(), parsed.cpuPolicyClocksHz)
    }
}
