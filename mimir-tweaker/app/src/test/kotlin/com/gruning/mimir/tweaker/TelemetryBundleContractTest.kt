package com.gruning.mimir.tweaker

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Daemon half of the telemetry Bundle contract. Sibling of
 * [HardwareCapabilitiesBundleContractTest]; `TweakerTelemetryBundleContractTest` in the main build
 * holds the client to the same [TweakerTelemetryContract].
 *
 * Telemetry is assembled by three producers chained in `MimirTweakerService.getTelemetry`, and the
 * contract declares them separately so a key drifting between producers cannot hide. Two of the
 * three are pure functions and are pinned here. The third,
 * `MimirTweakerService.putActiveFanControlReadout`, is a private member that reads live supervisor
 * session state, so it has no callable seam -- [TweakerTelemetryContract.FAN_READOUT] is declared
 * and enforced client-side only, which is where its consumption drift would bite.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryBundleContractTest {

    @Test
    fun `snapshot producer emits exactly its declared keys`() {
        val emitted = fullSnapshot().toBundle().keySet()
        val declared = TweakerTelemetryContract.SNAPSHOT.toSet()

        assertEquals("snapshot keys not declared in the contract", emptySet<String>(), emitted - declared)
        assertEquals("contract declares snapshot keys never written", emptySet<String>(), declared - emitted)
    }

    @Test
    fun `snapshot producer drops every optional reading when the hardware reports nothing`() {
        // A device with no fan node, no GPU node and no battery channel still has to produce a
        // parseable bundle: the CPU rows and the power state are the floor.
        val emitted = minimalSnapshot().toBundle().keySet()

        assertEquals(TweakerTelemetryContract.SNAPSHOT_ALWAYS.toSet(), emitted)
    }

    @Test
    fun `live producer emits exactly its declared keys`() {
        val emitted = Bundle().putLivePerformanceTelemetry(fullLive()).keySet()
        val declared = TweakerTelemetryContract.LIVE.toSet()

        assertEquals("live keys not declared in the contract", emptySet<String>(), emitted - declared)
        assertEquals("contract declares live keys never written", emptySet<String>(), declared - emitted)
    }

    @Test
    fun `live producer keeps its stamp and status when there is no foreground frame data`() {
        val emitted = Bundle().putLivePerformanceTelemetry(
            LivePerformanceTelemetry(
                sampledAtElapsedRealtimeMs = 1_000L,
                cpuBusyPercent = null,
                foregroundPackageName = null,
                frame = FrameTelemetryReading(FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND),
            ),
        ).keySet()

        assertEquals(TweakerTelemetryContract.LIVE_ALWAYS.toSet(), emitted)
    }

    @Test
    fun `every declared telemetry key is classified for the client exactly once`() {
        val consumed = TweakerTelemetryContract.CONSUMED_BY_CLIENT.toSet()
        val notConsumed = TweakerTelemetryContract.NOT_CONSUMED_BY_CLIENT.toSet()

        assertEquals(
            "a key is both consumed and not consumed by the client",
            emptySet<String>(),
            consumed intersect notConsumed,
        )
        assertEquals(
            "every declared key must be classified as consumed or explicitly not consumed",
            TweakerTelemetryContract.ALL.toSet(),
            consumed + notConsumed,
        )
    }

    private fun fullSnapshot() = TelemetrySnapshot(
        cpuCurrentKhz = mapOf(0 to 1_996_800),
        cpuMinKhz = mapOf(0 to 300_000),
        cpuMaxKhz = mapOf(0 to 1_996_800),
        gpuClockHz = 1_100_000_000L,
        gpuBusyPercent = 71,
        gpuMinPowerLevel = 6,
        gpuMaxPowerLevel = 2,
        fanDuty = 18_500,
        fanPeriod = 50_000,
        fanRpm = 3_400,
        cpuTempC = 62,
        gpuTempC = 74,
        batteryLevelPercent = 82,
        batteryCurrentUa = -1_200_000,
        batteryVoltageMv = 3_900,
        batteryPowerMw = 4_680,
        batteryTempDeciC = 290,
        batteryStatus = "DISCHARGING",
        thermalStatus = 2,
        vendorFanMode = 5,
        cpuPackageTempC = 58,
        cpuPackageTempZoneCount = 4,
    )

    private fun minimalSnapshot() = TelemetrySnapshot(
        cpuCurrentKhz = emptyMap(),
        cpuMinKhz = emptyMap(),
        cpuMaxKhz = emptyMap(),
        gpuClockHz = null,
        gpuBusyPercent = null,
        gpuMinPowerLevel = null,
        gpuMaxPowerLevel = null,
        fanDuty = null,
        fanPeriod = null,
        fanRpm = null,
        cpuTempC = null,
        gpuTempC = null,
        batteryLevelPercent = null,
        batteryCurrentUa = null,
        batteryVoltageMv = null,
        batteryPowerMw = null,
        batteryTempDeciC = null,
        batteryStatus = null,
        thermalStatus = null,
    )

    private fun fullLive() = LivePerformanceTelemetry(
        sampledAtElapsedRealtimeMs = 1_234_567L,
        cpuBusyPercent = 43,
        foregroundPackageName = "com.futuremark.dmandroid.application",
        frame = FrameTelemetryReading(
            status = FrameTelemetryStatus.AVAILABLE,
            layerName = "SurfaceView[com.example]",
            frameCount = 120,
            windowDurationMs = 2_000,
            averageFpsMilli = 59_940,
            latestPresentAgeMs = 16,
        ),
    )
}
