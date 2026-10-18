package com.gruning.mimir.rewrite.core.tweaker

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only Odin proof: visible package temperature follows the sensor used by Odin Game Assistant. */
@RunWith(AndroidJUnit4::class)
class OdinCpuPackageTelemetryDeviceTest {
    @Test
    fun packageTemperatureMatchesVendorSensorWhileSafetyRetainsHottestZone() = runBlocking {
        assertTrue("This proof is scoped to Odin3", Build.MODEL.equals("Odin3", ignoreCase = true))
        val vendorReading = requireNotNull(readVendorPackageTemperature()) {
            "Odin Game Assistant CPU thermal zone unavailable"
        }
        assertEquals(PackageSensorType, vendorReading.type)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = TweakerSession(context)
        assertTrue("Tweaker service did not bind", session.bind())
        try {
            val telemetry = session.getTelemetry()
            val packageTempC = telemetry?.cpuPackageTempC
            val hottestTempC = telemetry?.cpuTempC
            assertNotNull("Tweaker package temperature unavailable", packageTempC)
            assertNotNull("Tweaker hottest safety temperature unavailable", hottestTempC)
            assertTrue(
                "Package temperature diverged from the live Odin vendor sensor",
                abs(packageTempC!! - vendorReading.celsius) <= 3,
            )
            assertTrue(
                "User-facing package temperature must not exceed the hottest safety witness",
                packageTempC <= hottestTempC!!,
            )
            Log.i(
                ProofTag,
                "vendorPath=${vendorReading.path} vendorType=${vendorReading.type} " +
                    "vendorRaw=${vendorReading.raw} vendorPackageC=${vendorReading.celsius} " +
                    "mimirPackageC=$packageTempC " +
                    "mimirHottestSafetyC=$hottestTempC",
            )
        } finally {
            session.unbind()
        }
        Unit
    }

    private fun readVendorPackageTemperature(): VendorReading? {
        val zone = File(ThermalRoot, VendorZoneName)
        val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull()
            ?: return null
        val raw = runCatching { File(zone, "temp").readText().trim().toLong() }.getOrNull()
            ?: return null
        val celsius = (if (raw > 1_000L) raw / 1_000.0 else raw.toDouble())
            .takeIf { it in 1.0..150.0 }
            ?.toInt()
            ?: return null
        return VendorReading(zone.path, type, raw, celsius)
    }

    private data class VendorReading(
        val path: String,
        val type: String,
        val raw: Long,
        val celsius: Int,
    )

    private companion object {
        const val ThermalRoot = "/sys/devices/virtual/thermal"
        const val VendorZoneName = "thermal_zone23"
        const val PackageSensorType = "cpuss-0-1"
        const val ProofTag = "MimirCpuPackageProof"
    }
}
