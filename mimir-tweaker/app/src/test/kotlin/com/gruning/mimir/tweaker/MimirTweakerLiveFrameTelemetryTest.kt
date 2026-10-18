package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirTweakerLiveFrameTelemetryTest {
    @Test
    fun `periodic live telemetry has no SurfaceFlinger or DRM fallback`() {
        val source = mainSourceText("MimirTweakerService.kt")
        val live = source.substringAfter("livePerformanceTelemetry = LivePerformanceTelemetrySampler(")
            .substringBefore("liveTelemetryCache = LiveTelemetrySnapshotCache(")

        assertTrue(live.contains("frameTelemetry = LiveGameFrameTelemetryUnavailable"))
        assertFalse(live.contains("DisplayFrameTelemetry.createPort"))
        assertFalse(live.contains("fallback = surfaceFlingerFrameTelemetry"))
    }

    @Test
    fun `explicit qualification still receives the SurfaceFlinger port`() {
        val source = mainSourceText("MimirTweakerService.kt")
        assertTrue(source.contains("ensureCredentialStores(surfaceFlingerFrameTelemetry)"))
        assertTrue(source.contains("val surfaceFlingerFrameTelemetry = PServerFrameTelemetryPort"))
    }

    private fun mainSourceText(name: String): String {
        val candidates = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker/$name"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Tweaker source root unavailable")
    }
}
