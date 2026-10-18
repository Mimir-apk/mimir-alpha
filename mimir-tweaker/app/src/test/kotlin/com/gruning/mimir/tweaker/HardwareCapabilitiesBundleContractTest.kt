package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Daemon half of the capabilities Bundle contract.
 *
 * The client lives in a different Gradle build and cannot be linked into this JVM, so the two sides
 * are held to [TweakerCapabilityContract] instead of to each other -- it is declared in the
 * `:contract` module both builds already share. This test pins what the REAL marshaller emits;
 * `TweakerCapabilitiesBundleContractTest` on the client side pins what the REAL parser consumes.
 * Together they close the gap that let `supportedUncertifiedProfileIds` be computed, marshalled and
 * silently dropped while every suite stayed green.
 *
 * A key added to `toBundle` without being declared in the contract fails here.
 */
@RunWith(RobolectricTestRunner::class)
class HardwareCapabilitiesBundleContractTest {

    @Test
    fun `capabilities marshaller emits exactly the declared contract`() {
        val emitted = fullyPopulatedCapabilities().toBundle().keySet()
        val declared = TweakerCapabilityContract.ALL.toSet()

        assertEquals(
            "capabilities keys not declared in TweakerCapabilityContract",
            emptySet<String>(),
            emitted - declared,
        )
        assertEquals(
            "contract declares keys the marshaller never writes",
            emptySet<String>(),
            declared - emitted,
        )
    }

    @Test
    fun `only the conditional keys may be absent`() {
        // usageAccessGranted unknown, and no family/treatment: the three CONDITIONAL keys are the
        // only ones allowed to drop out. hasUsageAccessGranted must still be written, because it
        // is what tells the client "unknown" apart from "false".
        val sparse = fullyPopulatedCapabilities().copy(
            usageAccessGranted = null,
            hardwareFamilyId = null,
            profileTreatment = null,
        )

        val missing = TweakerCapabilityContract.ALL.toSet() - sparse.toBundle().keySet()

        assertEquals(TweakerCapabilityContract.CONDITIONAL.toSet(), missing)
        assertTrue(
            "hasUsageAccessGranted must survive an unknown usage-access state",
            sparse.toBundle().containsKey(TweakerCapabilityContract.HAS_USAGE_ACCESS_GRANTED),
        )
    }

    @Test
    fun `every declared key is classified for the client exactly once`() {
        val consumed = TweakerCapabilityContract.CONSUMED_BY_CLIENT.toSet()
        val notConsumed = TweakerCapabilityContract.NOT_CONSUMED_BY_CLIENT.toSet()

        assertEquals(
            "a key is both consumed and not consumed by the client",
            emptySet<String>(),
            consumed intersect notConsumed,
        )
        assertEquals(
            "every declared key must be classified as consumed or explicitly not consumed",
            TweakerCapabilityContract.ALL.toSet(),
            consumed + notConsumed,
        )
    }

    private fun fullyPopulatedCapabilities(): HardwareCapabilities = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "AYN",
        model = "Odin3",
        socModel = "test-soc",
        buildFingerprint = "test/fingerprint",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(
                id = 0,
                cpuIds = listOf(0, 1, 2),
                path = "/sys/devices/system/cpu/cpufreq/policy0",
                minKhz = 300_000,
                maxKhz = 1_996_800,
                currentKhz = 1_996_800,
                availableFrequenciesKhz = listOf(300_000, 1_996_800),
            ),
        ),
        gpuFrequenciesKhz = listOf(1_100_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60, 120),
        notes = listOf("contract fixture"),
        certifiedProfileIds = setOf("performance"),
        supportedUncertifiedProfileIds = setOf("balanced"),
        mutationAvailable = true,
        dryRun = false,
        usageAccessGranted = true,
        hardwareFamilyId = "family",
        profileTreatment = "treatment",
    )
}
