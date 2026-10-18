package com.gruning.mimir.tweaker

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The averaging window and data age must SURVIVE the wire boundary: a consumer cannot judge an
 * FPS figure without knowing whether it averaged 6 frames or 200, nor how old the newest frame
 * behind it already was at the read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class LiveTelemetryBundleTest {
    @Test
    fun `live bundle carries frame window and data age alongside the fps average`() {
        val bundle = Bundle().putLivePerformanceTelemetry(
            LivePerformanceTelemetry(
                sampledAtElapsedRealtimeMs = 12_345L,
                cpuBusyPercent = 42,
                foregroundPackageName = "org.ppsspp.ppsspp",
                frame = FrameTelemetryReading(
                    status = FrameTelemetryStatus.AVAILABLE,
                    layerName = "SurfaceView[org.ppsspp.ppsspp/.PpssppActivity](BLAST)#7",
                    frameCount = 6,
                    windowDurationMs = 100,
                    latestPresentAgeMs = 1_500,
                    averageFpsMilli = 59_940,
                ),
            ),
        )

        assertEquals(12_345L, bundle.getLong("sampledAtElapsedRealtimeMs"))
        assertEquals(59_940, bundle.getInt("frameAverageFpsMilli"))
        assertEquals(6, bundle.getInt("frameCount"))
        assertEquals(100, bundle.getInt("frameWindowDurationMs"))
        assertEquals(1_500, bundle.getInt("frameLatestPresentAgeMs"))
    }

    @Test
    fun `live bundle omits frame window keys when no frame data exists`() {
        val bundle = Bundle().putLivePerformanceTelemetry(
            LivePerformanceTelemetry(
                sampledAtElapsedRealtimeMs = 12_345L,
                cpuBusyPercent = null,
                foregroundPackageName = null,
                frame = FrameTelemetryReading(FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND),
            ),
        )

        assertEquals("WORKLOAD_NOT_FOREGROUND", bundle.getString("frameStatus"))
        assertFalse(bundle.containsKey("frameCount"))
        assertFalse(bundle.containsKey("frameWindowDurationMs"))
        assertFalse(bundle.containsKey("frameLatestPresentAgeMs"))
        assertTrue(bundle.containsKey("sampledAtElapsedRealtimeMs"))
    }
}
