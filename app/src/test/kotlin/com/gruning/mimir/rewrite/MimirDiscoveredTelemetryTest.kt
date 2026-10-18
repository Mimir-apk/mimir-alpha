package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source pins for the one-shot discovery route; parser behavior is covered by DeviceUtilizationTelemetryTest. */
class MimirDiscoveredTelemetryTest {
    @Test
    fun `discovered telemetry uses shared GPU validation and includes GPU-only readings`() {
        val candidates = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirDiscoveredTelemetry.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirDiscoveredTelemetry.kt"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/MimirDiscoveredTelemetry.kt"),
        )
        val source = candidates.firstOrNull(File::exists)?.readText()
            ?: error("MimirDiscoveredTelemetry.kt source not found")

        assertTrue(source.contains("DirectGpuBusyReader("))
        assertTrue(source.contains("readNode = ::read"))
        assertTrue(source.contains("gpuBusy == null"))
        assertTrue(source.contains("logGpuSourceEdge(gpuReading.source)"))
        assertTrue(source.contains("Process.myUid()"))
    }
}
