package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * One telemetry() snapshot must describe ONE thermal sample instant. The reader below returns a
 * temperature 10 C hotter on every successive read of the same zone, so any second sweep inside
 * telemetry() becomes visible as an incoherent triple (package mean from sweep 1, hottest
 * witnesses from sweep 2) and as a per-zone read count above one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class HardwareProbeThermalCoherenceTest {
    @Test
    fun `telemetry reads every thermal zone once and derives all three cpu-gpu figures from it`() {
        val tempReads = mutableMapOf<String, Int>()
        val zoneTypes = mapOf(
            "/sys/class/thermal/thermal_zone0/type" to "cpu-0-usr",
            "/sys/class/thermal/thermal_zone1/type" to "cpu-1-usr",
            "/sys/class/thermal/thermal_zone2/type" to "gpu-usr",
        )
        val zoneBaseTemps = mapOf(
            "/sys/class/thermal/thermal_zone0/temp" to 40_000L,
            "/sys/class/thermal/thermal_zone1/temp" to 60_000L,
            "/sys/class/thermal/thermal_zone2/temp" to 50_000L,
        )
        val reader = SystemReader(
            privilegedNodes = null,
            directRead = { path ->
                zoneTypes[path] ?: zoneBaseTemps[path]?.let { base ->
                    val readCount = (tempReads[path] ?: 0) + 1
                    tempReads[path] = readCount
                    (base + 10_000L * (readCount - 1)).toString()
                }
            },
            directDirectories = { _, prefix ->
                if (prefix == "thermal_zone") {
                    listOf(
                        File("/sys/class/thermal/thermal_zone0"),
                        File("/sys/class/thermal/thermal_zone1"),
                        File("/sys/class/thermal/thermal_zone2"),
                    )
                } else {
                    emptyList()
                }
            },
        )
        val probe = HardwareProbe(
            context = RuntimeEnvironment.getApplication(),
            reader = reader,
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ ->
                    PServerTransactResult(accepted = false, output = null)
                },
            ),
        )

        val telemetry = probe.telemetry(capabilities())

        // First-read values only: hottest CPU 60, mean (40+60)/2 = 50 over 2 zones, GPU 50.
        assertEquals(60, telemetry.cpuTempC)
        assertEquals(50, telemetry.cpuPackageTempC)
        assertEquals(2, telemetry.cpuPackageTempZoneCount)
        assertEquals(50, telemetry.gpuTempC)
        assertEquals(
            mapOf(
                "/sys/class/thermal/thermal_zone0/temp" to 1,
                "/sys/class/thermal/thermal_zone1/temp" to 1,
                "/sys/class/thermal/thermal_zone2/temp" to 1,
            ),
            tempReads.toMap(),
        )
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "TestCo",
        model = "Test Handheld",
        socModel = "TEST1",
        buildFingerprint = "test/fingerprint",
        deviceProfileId = "generic",
        supportedAxes = emptySet(),
        pServerAvailable = false,
        cpuPolicies = emptyList(),
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = false,
        fanNodeAvailable = false,
        refreshRatesHz = emptyList(),
        notes = emptyList(),
        certifiedProfileIds = emptySet(),
        posture = TweakerPostureTruth.SAFE,
        mutationAvailable = false,
        dryRun = true,
        usageAccessGranted = false,
        qualificationSource = TweakerPostureTruth.QUALIFICATION_LOCAL_REPORT,
    )
}
