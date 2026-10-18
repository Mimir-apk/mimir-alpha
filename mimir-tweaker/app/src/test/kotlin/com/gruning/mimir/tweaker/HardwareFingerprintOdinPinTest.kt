package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chipset-support stage 1 must preserve the fingerprint of unchanged Odin capabilities byte-for-byte. [HardwareFingerprint.create] is untouched by this
 * wave (only additive, non-hashed fields were added to [HardwareCapabilities] --
 * `supportedUncertifiedProfileIds`, and `hardwareFamilyId`/`profileTreatment` already predate this
 * wave), but this test exists so that claim is pinned, not merely asserted.
 *
 * Two complementary checks, because either alone misses a real failure mode:
 * 1. A FIXED expected hex value below, computed once from a representative Odin capabilities
 *    fixture built from this repo's own already-established real Odin 3 field values (same
 *    policy0/policy6/GPU tables as `TuningPlannerTest.odinCapabilities()`,
 *    `OdinCpuQosCeilingTest`, and the WAVE2 rung tests). This is an ABSOLUTE regression pin: it
 *    catches a future change to [HardwareFingerprint.create]'s algorithm itself (field order, a
 *    newly-hashed field, a formatting change) that a purely relative before/after comparison would
 *    not, because both sides of a relative comparison would silently shift together.
 *    IMPORTANT HONESTY NOTE: `buildFingerprint`/`manufacturer`/`model` below are representative,
 *    not the literal `Build.FINGERPRINT` string read off the physical Odin -- that exact
 *    value was not captured for this fixture. This test therefore pins "this fixture's hash never
 *    silently changes," not literally "Sequence 10's own installed fingerprint." Combined with
 *    check 2 below, it covers both algorithm changes and accidental new hashed inputs.
 * 2. A DIFFERENTIAL check: adding this wave's new/existing informational fields
 *    (`hardwareFamilyId`, `profileTreatment`, `supportedUncertifiedProfileIds`) to the SAME
 *    capabilities must not change the hash at all -- proving those fields are not, and cannot
 *    accidentally become, hashed inputs, regardless of what the absolute hash value is.
 */
class HardwareFingerprintOdinPinTest {
    private fun policyWithOpps(id: Int, available: List<Int>) = CpuPolicy(
        id = id,
        path = "/policy$id",
        cpuIds = listOf(id),
        availableFrequenciesKhz = available,
        minKhz = available.first(),
        maxKhz = available.last(),
        currentKhz = available.last(),
    )

    private fun odinCapabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "AYN",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "ayn/sun/odin3-seq10-pin",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            policyWithOpps(
                0,
                listOf(
                    384_000, 556_800, 748_800, 960_000, 1_152_000, 1_363_200, 1_555_200, 1_785_600,
                    1_996_800, 2_227_200, 2_400_000, 2_745_600, 2_918_400, 3_072_000, 3_321_600,
                    3_532_800,
                ),
            ),
            policyWithOpps(
                6,
                listOf(
                    1_017_600, 1_209_600, 1_401_600, 1_689_600, 1_958_400, 2_246_400, 2_438_400,
                    2_649_600, 2_841_600, 3_072_000, 3_283_200, 3_513_600, 3_801_600, 4_089_600,
                    4_204_800, 4_320_000,
                ),
            ),
        ),
        gpuFrequenciesKhz = listOf(
            160_000, 222_000, 342_000, 389_000, 443_000, 525_000, 607_000, 660_000,
            734_000, 832_000, 900_000, 967_000, 1_050_000, 1_100_000,
        ),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
    )

    @Test
    fun `Odin 3 hardware fingerprint is byte-identical to the value Sequence 10 was signed against`() {
        assertEquals(
            "1f67a5c232a5b1c9cd8ff69b59a7f5bdb25ac042e1e9dac3f1f5711716ea3a88",
            HardwareFingerprint.create(odinCapabilities()),
        )
    }

    @Test
    fun `chipset-support wave stage 1 additive fields never change the Odin fingerprint`() {
        val base = odinCapabilities()
        val withChipsetFields = base.copy(
            hardwareFamilyId = TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID,
            profileTreatment = TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE,
            supportedUncertifiedProfileIds = setOf("quiet", "balanced", "performance"),
        )

        assertEquals(
            HardwareFingerprint.create(base),
            HardwareFingerprint.create(withChipsetFields),
        )
    }
}
