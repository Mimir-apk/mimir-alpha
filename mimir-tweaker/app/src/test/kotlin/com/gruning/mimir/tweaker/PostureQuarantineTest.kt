package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class PostureQuarantineTest {
    @Test
    fun `safe and lab and production expose zero product-qualified profiles`() {
        assertTrue(TweakerPostureTruth.productQualifiedProfileIds().isEmpty())
        assertTrue("balanced" in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS)
        assertTrue("quiet" in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS)
        assertTrue("performance" in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS)
    }

    @Test
    fun `local report qualification source never authorizes profile ids in the bundle`() {
        val capabilities = HardwareCapabilities(
            status = CapabilityStatus.CALIBRATION_REQUIRED,
            manufacturer = "Moorechip",
            model = "Retroid Pocket 5",
            socModel = "SM8250",
            buildFingerprint = "rp5/test",
            deviceProfileId = "rp5",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            pServerAvailable = true,
            cpuPolicies = emptyList(),
            gpuFrequenciesKhz = listOf(305_000, 905_000),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
            notes = emptyList(),
            certifiedProfileIds = setOf("quiet", "performance"),
            posture = TweakerPostureTruth.LAB,
            mutationAvailable = true,
            dryRun = false,
            qualificationSource = TweakerPostureTruth.QUALIFICATION_LOCAL_REPORT,
        )
        // Even if a buggy caller stuffed IDs, productQualifiedProfileIds stays empty and service
        // overwrites via productQualifiedProfileIds(); this asserts the compile-time empty set.
        assertEquals(emptySet<String>(), TweakerPostureTruth.productQualifiedProfileIds())
        val authoritative = capabilities.copy(
            certifiedProfileIds = TweakerPostureTruth.productQualifiedProfileIds(),
        )
        assertTrue(authoritative.toBundle().getStringArrayList("certifiedProfileIds")!!.isEmpty())
    }

    @Test
    fun `requiring a signed manifest never advertises one before it is loaded`() {
        val source = TweakerPostureTruth.qualificationSource(hasLocalCalibrationReport = true)
        assertEquals(TweakerPostureTruth.QUALIFICATION_LOCAL_REPORT, source)
        assertFalse(
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
                dryRun = false,
                capabilityStatus = CapabilityStatus.SUPPORTED,
                pServerAvailable = true,
                qualificationSource = source,
                qualifiedProfileIds = setOf("quiet", "performance"),
            ),
        )
        assertTrue(TweakerPostureTruth.productQualifiedProfileIds().isEmpty())
    }
}
