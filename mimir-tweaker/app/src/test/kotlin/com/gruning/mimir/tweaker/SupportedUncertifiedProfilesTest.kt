package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Initial chipset-support capability tests. This is the daemon-side gate only -- it is informational capability truth, never a
 * mutation authority; `TweakerPostureTruth.mutationAvailable` and everything downstream of it are
 * unchanged and untested here.
 */
class SupportedUncertifiedProfilesTest {
    private val allThree = setOf("quiet", "balanced", "performance")

    private fun policyWithOpps(id: Int, available: List<Int>) = CpuPolicy(
        id = id,
        path = "/policy$id",
        cpuIds = listOf(id),
        availableFrequenciesKhz = available,
        minKhz = available.first(),
        maxKhz = available.last(),
        currentKhz = available.last(),
    )

    /**
     * Vendor privilege admission is distinct from tuning admission. The frontend's `CertifiedVendorAdmissionPolicy` now admits uncharacterised silicon
     * for the sealed vendor command set. This test is the other half -- proof that the widening
     * did NOT reach tuning.
     *
     * It is deliberately a differential: the CPU topology is byte-for-byte the RP5's, the one the
     * test below proves DOES earn all three profiles. Only the `socModel` string changes. So a
     * pass cannot be explained by the topology being unusable -- the family table, and nothing
     * else, is what refuses. That is also why this cannot be inherited from the frontend change:
     * [TweakerHardwareFamilies] is a separate table in a separate Gradle build, resolved from
     * `socModel` independently of `CertifiedVendorAdmissionPolicy.family`.
     */
    @Test
    fun `uncharacterised silicon earns no tuning profiles even on a topology that would qualify`() {
        val rp5Topology = listOf(
            policyWithOpps(
                0,
                listOf(
                    300_000, 403_200, 518_400, 614_400, 691_200, 787_200, 883_200, 979_200,
                    1_075_200, 1_171_200, 1_248_000, 1_344_000, 1_420_800, 1_516_800, 1_612_800,
                    1_708_800, 1_804_800,
                ),
            ),
            policyWithOpps(4, listOf(710_400, 1_286_400, 1_862_400, 2_342_400, 2_419_200)),
            policyWithOpps(7, listOf(844_800, 1_516_800, 2_169_600, 2_745_600, 2_841_600)),
        )

        // Control: this exact topology on characterised silicon earns all three.
        assertEquals(
            allThree,
            SupportedUncertifiedProfiles.compute(
                status = CapabilityStatus.CALIBRATION_REQUIRED,
                socModel = "SM8250",
                cpuPolicies = rp5Topology,
            ),
        )

        // Same topology, silicon this build has not reasoned about: no tuning, by three
        // independent mechanisms -- the profileTreatment gate in SupportedUncertifiedProfiles,
        // TuningPlanner's family-keyed CPU target tables, and (for the certified path) the signed
        // manifest's exact hardwareFingerprint + deviceProfile match.
        listOf(
            "SM8450",      // a real Snapdragon, simply never characterised here
            "QCS9075",     // plausible future Qualcomm part
            "MT6983",      // not Qualcomm at all
            "Tensor G3",
            "",
        ).forEach { soc ->
            assertEquals(
                "Tuning must stay refused on uncharacterised silicon: '$soc'",
                emptySet<String>(),
                SupportedUncertifiedProfiles.compute(
                    status = CapabilityStatus.CALIBRATION_REQUIRED,
                    socModel = soc,
                    cpuPolicies = rp5Topology,
                ),
            )
            // The layer underneath agrees, so this is not one gate carrying the whole claim.
            assertEquals(
                "No CPU vector may be derived for uncharacterised silicon: '$soc'",
                null,
                TuningPlanner().certifiedCpuTargetsKhz("performance", soc, rp5Topology),
            )
            assertEquals(
                "Uncharacterised silicon must not be treated as a certified reference: '$soc'",
                TweakerHardwareFamilies.GENERAL_LIVE_OPP,
                TweakerHardwareFamilies.resolve(soc).profileTreatment,
            )
        }
    }

    @Test
    fun `RP5-shaped SM8250 topology is offered as chipset-supported-uncertified`() {
        val rp5Policies = listOf(
            policyWithOpps(
                0,
                listOf(
                    300_000, 403_200, 518_400, 614_400, 691_200, 787_200, 883_200, 979_200,
                    1_075_200, 1_171_200, 1_248_000, 1_344_000, 1_420_800, 1_516_800, 1_612_800,
                    1_708_800, 1_804_800,
                ),
            ),
            policyWithOpps(4, listOf(710_400, 1_286_400, 1_862_400, 2_342_400, 2_419_200)),
            policyWithOpps(7, listOf(844_800, 1_516_800, 2_169_600, 2_745_600, 2_841_600)),
        )

        assertEquals(
            allThree,
            SupportedUncertifiedProfiles.compute(
                status = CapabilityStatus.CALIBRATION_REQUIRED,
                socModel = "SM8250",
                cpuPolicies = rp5Policies,
            ),
        )
    }

    @Test
    fun `an exact-topology 8 Elite device is offered through the same literal certified row`() {
        val eliteTopology = listOf(
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
        )

        assertEquals(
            allThree,
            SupportedUncertifiedProfiles.compute(
                status = CapabilityStatus.CALIBRATION_REQUIRED,
                socModel = "CQ8725S",
                cpuPolicies = eliteTopology,
            ),
        )
    }

    @Test
    fun `an unsupported chipset gets nothing`() {
        assertEquals(
            emptySet<String>(),
            SupportedUncertifiedProfiles.compute(
                status = CapabilityStatus.CALIBRATION_REQUIRED,
                socModel = "MT9999",
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(300_000, 700_000, 1_200_000)),
                    policyWithOpps(4, listOf(500_000, 1_100_000, 2_000_000)),
                ),
            ),
        )
    }

    @Test
    fun `an UNSUPPORTED capability status gets nothing regardless of chipset family`() {
        assertEquals(
            emptySet<String>(),
            SupportedUncertifiedProfiles.compute(
                status = CapabilityStatus.UNSUPPORTED,
                socModel = "SM8250",
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(300_000, 979_200, 1_804_800)),
                    policyWithOpps(4, listOf(710_400, 1_286_400, 2_419_200)),
                    policyWithOpps(7, listOf(844_800, 1_516_800, 2_841_600)),
                ),
            ),
        )
    }

    @Test
    fun `a single-policy SM8250 topology gets nothing -- transposition needs at least two clusters`() {
        assertEquals(
            emptySet<String>(),
            SupportedUncertifiedProfiles.compute(
                status = CapabilityStatus.CALIBRATION_REQUIRED,
                socModel = "SM8250",
                cpuPolicies = listOf(policyWithOpps(0, listOf(300_000, 979_200, 1_804_800))),
            ),
        )
    }

    @Test
    fun `an 8 Elite device whose topology does not match Odins exact policy pair gets nothing`() {
        // The anchor family deliberately does NOT fall back to the transposition (spec §3.5) --
        // its own literal row requires an exact {0,6} topology match, same as certification does.
        assertEquals(
            emptySet<String>(),
            SupportedUncertifiedProfiles.compute(
                status = CapabilityStatus.CALIBRATION_REQUIRED,
                socModel = "CQ8725S",
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(384_000, 1_785_600, 3_532_800)),
                    policyWithOpps(4, listOf(710_400, 1_996_800, 2_419_200)),
                    policyWithOpps(7, listOf(844_800, 2_246_400, 2_841_600)),
                ),
            ),
        )
    }

    @Test
    fun `8 Gen 2 family aliases (QCS8550, SM8550) are both offered identically`() {
        val gen2Policies = listOf(
            policyWithOpps(0, listOf(1_459_200, 1_555_200, 1_785_600, 2_150_400)),
            policyWithOpps(3, listOf(1_785_600, 2_054_400, 2_323_200, 2_841_600)),
            policyWithOpps(7, listOf(1_843_200, 2_092_800, 2_476_800, 3_187_200)),
        )

        listOf("QCS8550", "SM8550").forEach { soc ->
            assertEquals(
                soc,
                allThree,
                SupportedUncertifiedProfiles.compute(
                    status = CapabilityStatus.CALIBRATION_REQUIRED,
                    socModel = soc,
                    cpuPolicies = gen2Policies,
                ),
            )
        }
    }
}
