package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import com.gruning.mimir.tweaker.TweakerTelemetryContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Client half of the telemetry Bundle contract, the sibling of
 * `TweakerCapabilitiesBundleContractTest`. Telemetry is polled roughly once a second while a
 * session is live, so a key the daemon ships and nobody reads is the hottest dead payload in the
 * product, and a read that quietly stops matching is the most expensive kind of silent default.
 *
 * Same rule as the capabilities pair: a key is "consumed" if there EXISTS a bundle where dropping
 * it changes the parse.
 */
@RunWith(RobolectricTestRunner::class)
class TweakerTelemetryBundleContractTest {

    @Test
    fun `every contract key the client must consume actually changes the parse`() {
        val inert = TweakerTelemetryContract.CONSUMED_BY_CLIENT.filter { key -> !mattersAnywhere(key) }

        assertEquals(
            "contract says the client consumes these telemetry keys, but dropping them changed " +
                "nothing in any context -- either the client stopped reading them or the " +
                "contract is wrong",
            emptyList<String>(),
            inert,
        )
    }

    @Test
    fun `keys the contract marks unread really are unread`() {
        val nowConsumed = TweakerTelemetryContract.NOT_CONSUMED_BY_CLIENT.filter { key -> mattersAnywhere(key) }

        assertEquals(
            "these are declared NOT_CONSUMED_BY_CLIENT but now affect the parse; move them to " +
                "CONSUMED_BY_CLIENT",
            emptyList<String>(),
            nowConsumed,
        )
    }

    @Test
    fun `an empty telemetry bundle parses to nothing rather than throwing`() {
        // A dead binder returns an empty bundle. Every field is nullable for that reason, and the
        // fan readout must be absent rather than a zeroed-out object that would render as real.
        val parsed = parseTweakerTelemetryBundle(Bundle())

        assertNull(parsed.cpuTempC)
        assertNull(parsed.gpuTempC)
        assertNull(parsed.fanControl)
        assertEquals(emptyList<Long>(), parsed.cpuPolicyClocksHz)
    }

    private fun mattersAnywhere(key: String): Boolean = contexts().any { bundle ->
        parseTweakerTelemetryBundle(bundle.withoutKey(key)) != parseTweakerTelemetryBundle(bundle)
    }

    /**
     * Two contexts, because the fan readout is all-or-nothing: `parseFanControlReadout` returns
     * null unless `activeFanProfileId` is one of the three real profile ids, and the duty/period/
     * rpm readings are only accepted under a vendor fan mode Mimir actually owns duty in
     * (`FAN_READOUT_ACCEPTED_VENDOR_MODES`). Under vendor SMART those reads are correctly ignored,
     * so probing only that mode would report four live reads as dead.
     */
    private fun contexts(): List<Bundle> = listOf(
        completeTelemetryBundle(vendorFanMode = 5),
        completeTelemetryBundle(vendorFanMode = 4),
    )

    private fun Bundle.withoutKey(key: String): Bundle = Bundle(this).apply { remove(key) }

    /** Every declared key present, with values inside the parser's own accepted ranges. */
    private fun completeTelemetryBundle(vendorFanMode: Int): Bundle = Bundle().apply {
        putStringArrayList("cpuCurrentKhz", arrayListOf("policy0=1996800", "policy6=3072000"))
        putStringArrayList("cpuMinKhz", arrayListOf("policy0=300000"))
        putStringArrayList("cpuMaxKhz", arrayListOf("policy0=1996800"))
        putLong("gpuClockHz", 1_100_000_000L)
        putInt("gpuBusyPercent", 71)
        putInt("gpuMinPowerLevel", 6)
        putInt("gpuMaxPowerLevel", 2)
        putInt("fanDuty", 18_500)
        putInt("fanPeriod", 50_000)
        putInt("fanRpm", 3_400)
        putInt("vendorFanMode", vendorFanMode)
        putInt("cpuTempC", 62)
        putInt("cpuPackageTempC", 58)
        putInt("cpuPackageTempZoneCount", 4)
        putInt("gpuTempC", 74)
        putInt("batteryLevelPercent", 82)
        putInt("batteryCurrentUa", -1_200_000)
        putInt("batteryVoltageMv", 3_900)
        putInt("batteryPowerMw", 4_680)
        putInt("batteryTempDeciC", 290)
        putString("batteryStatus", "DISCHARGING")
        putString("externalPowerState", "NONE")
        putInt("thermalStatus", 2)

        putLong("sampledAtElapsedRealtimeMs", 1_234_567L)
        putInt("cpuBusyPercent", 43)
        putString("foregroundPackageName", "com.futuremark.dmandroid.application")
        putString("frameStatus", "OK")
        putString("frameLayerName", "SurfaceView[com.example]")
        putInt("frameAverageFpsMilli", 59_940)
        putInt("frameCount", 120)
        putInt("frameWindowDurationMs", 2_000)
        putInt("frameLatestPresentAgeMs", 16)

        putString("activeFanProfileId", "performance")
        putString("fanControlMode", "AUTO")
        putString("fanControlStrategy", "AUTO_CURVE")
        putInt("fanCurveBias", 0)
        putInt("fanPowerReferenceMw", 4_500)
        putInt("fanTargetTempC", 62)
    }
}
