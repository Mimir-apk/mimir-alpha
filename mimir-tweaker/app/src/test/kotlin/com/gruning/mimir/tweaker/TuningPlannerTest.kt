package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningPlannerTest {
    private val planner = TuningPlanner()

    @Test
    fun `snap never selects an OPP above requested target`() {
        assertEquals(1_400_000, planner.snapAtOrBelow(listOf(800_000, 1_400_000, 1_800_000), 1_500_000, 1_800_000))
    }

    @Test
    fun `Adreno ascending OPP maps to fastest-first power level`() {
        val rp5Gpu = listOf(305_000, 400_000, 441_600, 490_000, 525_000, 587_000, 670_000, 905_000)

        assertEquals(0, planner.gpuPowerLevel(rp5Gpu, 1.0))
        assertEquals(4, planner.gpuPowerLevel(rp5Gpu, 0.55))
    }

    @Test
    fun `balanced plan is provisional and preserves RP5 policy identity`() {
        val plan = planner.plan("balanced", "example.game", capabilities())!!

        assertEquals(setOf(0, 4, 7), plan.cpuMaxKhz.keys)
        assertEquals(TuningPlanner.FAN_SMART, plan.fanMode)
        assertFalse(plan.calibrated)
    }

    @Test
    fun `balanced plan omits fan when axis unsupported`() {
        val noFan = capabilities().copy(supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU))
        val plan = planner.plan("balanced", "example.game", noFan)!!

        assertNull(plan.fanMode)
    }

    @Test
    fun `general live OPP fallback maps quiet and performance to large and small tiers`() {
        val odin = capabilities().copy(
            manufacturer = "ayn",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "qti/sun/test",
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            cpuPolicies = listOf(
                policyWithOpps(0, listOf(1_000_000, 2_200_000, 3_000_000, 4_000_000)),
                policyWithOpps(6, listOf(500_000, 1_100_000, 1_600_000, 2_000_000)),
            ),
            gpuFrequenciesKhz = listOf(200_000, 450_000, 700_000, 1_000_000),
            fanNodeAvailable = false,
        )

        val quiet = planner.plan("quiet", "example.game", odin)!!
        assertEquals(mapOf(0 to 2_200_000, 6 to 1_100_000), quiet.cpuMaxKhz)
        // Sequence 9: quiet's Odin GPU ceiling is now the explicit, hardcoded level 5 (spec S2),
        // independent of whatever this fixture's GPU table happens to contain -- it is no longer
        // computed from it, so the small synthetic 4-entry table above no longer determines this.
        assertEquals(5, quiet.gpuMaxPowerLevel)
        assertNull(quiet.fanMode)

        val performance = planner.plan("performance", "example.game", odin)!!
        assertEquals(mapOf(0 to 3_000_000, 6 to 1_600_000), performance.cpuMaxKhz)
        // Sequence 8: the Odin GPU ceiling returns to the full live table, so Performance reports
        // the fastest level 0. Sequence 7's one-OPP-down level 1 made the CPU hotter, not cooler.
        // Sequence 9 keeps Performance's level 0 explicit rather than factor-derived, same result.
        assertEquals(0, performance.gpuMaxPowerLevel)
        assertNull(performance.fanMode)
    }

    @Test
    fun `8 Elite aliases map every profile to exact live certified family tiers`() {
        listOf("CQ8725S", "SM8750").forEach { soc ->
            val elite = capabilities().copy(
                manufacturer = "ayn",
                model = "Odin3",
                socModel = soc,
                buildFingerprint = "qti/sun/test",
                deviceProfileId = "ayn-odin3-cq8725s-pserver",
                supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(998_400, 1_785_600, 1_996_800, 2_227_200, 2_745_600)),
                    policyWithOpps(6, listOf(1_075_200, 1_958_400, 2_246_400, 2_438_400, 3_072_000)),
                ),
                gpuFrequenciesKhz = listOf(200_000, 450_000, 700_000, 1_000_000),
                fanNodeAvailable = false,
            )

            val quiet = planner.plan("quiet", "example.game", elite)!!
            val balanced = planner.plan("balanced", "example.game", elite)!!
            val performance = planner.plan("performance", "example.game", elite)!!

            // Sequence 9 : quiet's
            // policy0 drops one further live rung, 1,996,800 -> 1,785,600. quiet's policy6 is
            // unchanged.
            assertEquals(soc, mapOf(0 to 1_785_600, 6 to 2_246_400), quiet.cpuMaxKhz)
            // The GPU ceiling is now an explicit level (spec S2), not a factor snapped against
            // this fixture's synthetic 4-entry GPU table -- see the dedicated GPU tests below.
            assertEquals(soc, 5, quiet.gpuMaxPowerLevel)
            // Sequence 9: balanced now replicates performance's six-core clamp exactly (a
            // REDUCTION from 2,227,200) and spends the freed envelope on its own prime cluster
            // instead (2,246,400 -> 2,438,400). Balanced's policy0 no longer sits above
            // Performance's -- they are now equal, by design (policy: "efficiency core
            // stays the same").
            assertEquals(soc, mapOf(0 to 1_996_800, 6 to 2_438_400), balanced.cpuMaxKhz)
            // The 2026-08-22 GPU policy, enforced on 8-Elite on 2026-10-13, leaves Balanced and Performance uncapped (level 0); only Quiet keeps a mild cap.
            assertEquals(soc, 0, balanced.gpuMaxPowerLevel)
            // Sequence 8 clamps the efficiency cluster to the 1,996,800 kHz live OPP and leaves
            // the GPU at the full table. Performance is unchanged by sequence 9.
            assertEquals(soc, mapOf(0 to 1_996_800, 6 to 3_072_000), performance.cpuMaxKhz)
            assertEquals(soc, 0, performance.gpuMaxPowerLevel)
            assertNull(soc, quiet.fanMode)
            assertNull(soc, balanced.fanMode)
            assertNull(soc, performance.fanMode)
        }
    }

    // Sequence 9 , task test 1: each profile's full planned vector (CPU +
    // GPU together) against real live OPP/GPU tables, pinned exactly to the spec S1 table. Reuses
    // exactly the CPU OPP set from "8 Elite aliases" above (plus 2,438,400, needed for balanced's
    // new policy6) and exactly the real 14-entry GPU table from "Odin 3 profiles use calibrated
    // GPU ceilings" below -- no new fixture data invented for this test.
    @Test
    fun `sequence 9 planned vector matches the spec S1 table exactly for every CQ8725S profile`() {
        val odin = capabilities().copy(
            manufacturer = "ayn",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "ayn/sun/odin3-seq9-candidate",
            deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            cpuPolicies = listOf(
                policyWithOpps(0, listOf(998_400, 1_785_600, 1_996_800, 2_227_200, 2_745_600)),
                policyWithOpps(6, listOf(1_075_200, 1_958_400, 2_246_400, 2_438_400, 3_072_000)),
            ),
            gpuFrequenciesKhz = listOf(
                160_000, 222_000, 342_000, 389_000, 443_000, 525_000, 607_000, 660_000,
                734_000, 832_000, 900_000, 967_000, 1_050_000, 1_100_000,
            ),
        )

        val quiet = planner.plan("quiet", "example.game", odin)!!
        assertEquals(mapOf(0 to 1_785_600, 6 to 2_246_400), quiet.cpuMaxKhz)
        assertEquals(5, quiet.gpuMaxPowerLevel)

        val balanced = planner.plan("balanced", "example.game", odin)!!
        // The CPU clamp is unchanged (Balanced and Performance KEEP their CPU
        // clamps for profile differentiation and power limits).
        assertEquals(mapOf(0 to 1_996_800, 6 to 2_438_400), balanced.cpuMaxKhz)
        // The 2026-08-22 GPU policy leaves Balanced and Performance uncapped, while Quiet keeps a mild cap; enforced on 8-Elite on 2026-10-13.
        assertEquals(0, balanced.gpuMaxPowerLevel)

        val performance = planner.plan("performance", "example.game", odin)!!
        assertEquals(mapOf(0 to 1_996_800, 6 to 3_072_000), performance.cpuMaxKhz)
        assertEquals(0, performance.gpuMaxPowerLevel)
    }

    @Test
    fun `Odin 3 profiles use calibrated GPU ceilings without changing generic devices`() {
        val odin = capabilities().copy(
            manufacturer = "ayn",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "ayn/sun/odin3-qualified-candidate",
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            gpuFrequenciesKhz = listOf(
                160_000,
                222_000,
                342_000,
                389_000,
                443_000,
                525_000,
                607_000,
                660_000,
                734_000,
                832_000,
                900_000,
                967_000,
                1_050_000,
                1_100_000,
            ),
        )

        // Sequence 9 (spec S2): quiet and balanced moved from factor-derived levels 8 (525 MHz)
        // and 6 (660 MHz) to the explicit levels 5 (734 MHz) and 2 (967 MHz).
        // Performance is unchanged: still the full live table at KGSL level 0 (1,100 MHz), the
        // same level a generic device gets. `max_pwrlevel` is inverted, so a larger level is a
        // slower clock.
        //
        // 2026-10-13: balanced's level 2 is retired. On the 8-Elite family, balanced now plans level 0
        // like performance, and only quiet carries a GPU ceiling on this family.
        //
        // Family-scoped dispatch: a DIFFERENT device
        // id on the SAME 8-Elite family (socModel unchanged) now gets the identical explicit
        // levels, replacing the earlier test that
        // asserted a non-Odin id fell back to the generic level 0.
        mapOf("quiet" to 5, "balanced" to 0, "performance" to 0).forEach { (profileId, level) ->
            assertEquals(profileId, level, planner.plan(profileId, "example.game", odin)!!.gpuMaxPowerLevel)
            assertEquals(
                "$profileId: a chassis nobody has qualified by name, same 8-Elite family, " +
                    "must get the identical calibrated level",
                level,
                planner.plan(
                    profileId,
                    "example.game",
                    odin.copy(deviceProfileId = "ayn-some-future-8-elite-handheld-pserver"),
                )!!.gpuMaxPowerLevel,
            )
        }

        // The actual boundary is chip family, not device id: a genuinely different socModel must
        // NOT reach [odin3ExplicitGpuMaxPowerLevel]'s raw indices. `performance` is asserted here
        // (not quiet/balanced) because it alone is guaranteed uncapped (level 0) on the transposed
        // path regardless of the target's own GPU table shape
        // (TuningPlanner.TRANSPOSED_UNCAPPED_GPU_PROFILE_IDS short-circuits before any position-
        // fraction arithmetic that would depend on this fixture's table size) -- so this assertion
        // does not accidentally depend on the transposition producing the same numbers by
        // coincidence, which a same-sized (14-entry) GPU table would risk for quiet/balanced.
        assertEquals(
            "a genuinely different chip family must not reach the 8-Elite explicit GPU indices",
            0,
            planner.plan(
                "performance",
                "example.game",
                odin.copy(deviceProfileId = "qualcomm-8-elite-pserver", socModel = "SM8250"),
            )!!.gpuMaxPowerLevel,
        )
    }

    // Sequence 9 spec S2 / task test 2: the Odin GPU ceiling is an explicit level, not a factor.
    // These three constants are the entire mechanism now -- pinning them here means a future edit
    // reintroducing factor arithmetic on this path would have to delete or bypass this test. The
    // old ODIN_3_QUIET_GPU_CEILING_FACTOR / ODIN_3_BALANCED_GPU_CEILING_FACTOR /
    // ODIN_3_PERFORMANCE_GPU_CEILING_FACTOR constants no longer exist -- this file would not
    // compile if the factor path were reintroduced under those names.
    @Test
    fun `sequence 9 Odin GPU levels are explicit pinned indices not ceiling factors`() {
        assertEquals(5, TuningPlanner.ODIN_3_QUIET_GPU_MAX_POWER_LEVEL)
        // Balanced's 2 survives only as the anchor-hardware value behind the transposed position
        // fractions; the 8-Elite dispatch no longer returns it (see the family-dispatch test further down).
        assertEquals(2, TuningPlanner.ODIN_3_BALANCED_GPU_MAX_POWER_LEVEL)
        assertEquals(0, TuningPlanner.ODIN_3_PERFORMANCE_GPU_MAX_POWER_LEVEL)
    }

    /**
     *  One value:
     * `min_pwrlevel` 6 -> 3 (900 MHz on the live Odin table pinned below). The profile-supplied
     * floor is non-null ONLY for the exact Odin 3 device profile's balanced plan, and only when
     * it can admit the requested ceiling within the live table -- every other combination
     * returns null and falls back to the derived (captured-bound-preserving, cooler) behaviour.
     */
    /**
     * **The floor is withdrawn — policy 2026-08-22, "stop writing the floor".**
     *
     * The 2026-08-18 intent was sound and the code was correct; the Odin's firmware simply will not
     * keep a `min_pwrlevel` above its own stock floor of 6. Measured on `reference device`: the write passes
     * apply-time readback, then the kernel resets it to 6 within ~5 s while leaving `max_pwrlevel`
     * alone, the supervisor reads that as contract drift and restores stock, the frontend re-arms
     * the standing balanced, and the device livelocks — observed past generation 1244.
     *
     * So `profileSuppliedGpuMinPowerLevel` must now return null for EVERY input, and the whole
     * point of this test is that there is no surviving combination that returns a floor.
     */
    @Test
    fun `no profile supplies a GPU floor -- the Odin firmware will not hold one`() {
        val odin = "ayn-odin3-cq8725s-pserver"
        // The constant and its guards are deliberately KEPT, re-armable behind one flag, for when a
        // transport exists that can hold a floor against firmware that reasserts it.
        assertEquals(3, TuningPlanner.ODIN_3_BALANCED_GPU_MIN_POWER_LEVEL)
        assertFalse(
            "the Odin balanced GPU floor must stay withdrawn until a holding transport exists",
            TuningPlanner.ODIN_3_BALANCED_GPU_FLOOR_ENFORCEABLE,
        )

        // The combination that used to return 3 -- the one that livelocked the reference device.
        assertNull(TuningPlanner.profileSuppliedGpuMinPowerLevel(odin, "balanced", 2, 13))

        // Everything that returned null before still does, so the withdrawal did not disturb them.
        assertNull(TuningPlanner.profileSuppliedGpuMinPowerLevel(odin, "quiet", 5, 13))
        assertNull(TuningPlanner.profileSuppliedGpuMinPowerLevel(odin, "performance", 0, 13))
        assertNull(TuningPlanner.profileSuppliedGpuMinPowerLevel(odin, "stock", 2, 13))
        assertNull(TuningPlanner.profileSuppliedGpuMinPowerLevel("rp5", "balanced", 2, 13))
        assertNull(TuningPlanner.profileSuppliedGpuMinPowerLevel(odin, "balanced", 4, 13))
        assertNull(TuningPlanner.profileSuppliedGpuMinPowerLevel(odin, "balanced", 2, 2))
    }

    /**
     * The duty the withdrawal drops, gated on its own rather than assumed
     * ([[absence-of-behaviour-needs-its-own-gate]]). With no profile-supplied floor, the two sites
     * that consult that one function -- snapshot capture and mutation planning -- must both decline
     * to own `gpu:min_pwrlevel` at all. If ownership and emission ever diverge here, the supervisor
     * gets a contract minimum with nothing maintaining it and the livelock returns in a new shape.
     */
    @Test
    fun `withdrawing the floor leaves no profile-supplied minimum for any Odin profile`() {
        val odin = "ayn-odin3-cq8725s-pserver"
        listOf("stock", "quiet", "balanced", "performance").forEach { profileId ->
            (0..13).forEach { ceiling ->
                assertNull(
                    "$profileId at ceiling $ceiling must supply no GPU floor",
                    TuningPlanner.profileSuppliedGpuMinPowerLevel(odin, profileId, ceiling, 13),
                )
            }
        }
    }

    /**
     * The dominance argument, pinned.
     *
     * KGSL `max_pwrlevel` is inverted: level 0 is the fastest live OPP and a LARGER level is a
     * SLOWER clock. Sequence 8 qualifies Performance at level 0 (1,100 MHz), which is exactly
     * Stock's own GPU ceiling — so every other level the device may be asked for is strictly
     * slower and is therefore dominated by the qualified vector without re-signing per step.
     *
     * If this test ever fails, the qualified sequence-8 vector no longer bounds what the device
     * may be asked to do, and any at-or-below-nominal scheme built on top of it is invalid.
     */
    @Test
    fun `KGSL power levels are inverted so a larger level is a strictly slower Odin OPP`() {
        val liveOdinGpuTableDescending = listOf(
            1_100_000, 1_050_000, 967_000, 900_000, 832_000, 734_000, 660_000,
            607_000, 525_000, 443_000, 389_000, 342_000, 222_000, 160_000,
        )
        val ascending = liveOdinGpuTableDescending.sorted()

        // Level index -> exact live frequency, read from Odin 3 reference device
        // (/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies).
        listOf(
            0 to 1_100_000,
            1 to 1_050_000,
            2 to 967_000,
            3 to 900_000,
            4 to 832_000,
        ).forEach { (level, expectedKhz) ->
            assertEquals(
                "level $level",
                level,
                planner.gpuPowerLevel(ascending, expectedKhz.toDouble() / 1_100_000.0),
            )
        }

        // Monotonic and strictly decreasing: every step up in level is a step down in clock.
        val frequencyByLevel = liveOdinGpuTableDescending
        frequencyByLevel.zipWithNext().forEach { (faster, slower) ->
            assertTrue("$faster must exceed $slower", faster > slower)
        }

        // The qualified sequence-8 ceiling: the full live table, which is Stock's own ceiling.
        // Sequence 9 keeps Performance's GPU at this same level 0, now expressed as the explicit
        // TuningPlanner.ODIN_3_PERFORMANCE_GPU_MAX_POWER_LEVEL constant rather than a factor
        // (spec S2) -- pinned directly below, alongside FULL_GPU_CEILING_FACTOR, which is what
        // every non-Odin device (and Odin's own `stock`) still resolves the full table through.
        assertEquals(
            "sequence-8 ceiling",
            0,
            planner.gpuPowerLevel(ascending, TuningPlanner.FULL_GPU_CEILING_FACTOR),
        )
        assertEquals(0, TuningPlanner.ODIN_3_PERFORMANCE_GPU_MAX_POWER_LEVEL)
        assertEquals(1_100_000, frequencyByLevel[0])
        assertEquals(1_050_000, frequencyByLevel[1])
        assertTrue(
            "every level below the qualified level 0 ceiling must be strictly slower",
            frequencyByLevel.drop(1).all { it < frequencyByLevel[0] },
        )
    }

    @Test
    fun `balanced falls back to 78 percent and snaps down when exact medium OPP is absent`() {
        val odinWithDifferentOpps = capabilities().copy(
            socModel = "CQ8725S",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            cpuPolicies = listOf(
                policyWithOpps(0, listOf(1_000_000, 2_000_000, 2_500_000, 3_000_000)),
                policyWithOpps(6, listOf(1_100_000, 1_800_000, 2_300_000, 3_100_000)),
            ),
            gpuFrequenciesKhz = listOf(200_000, 450_000, 700_000, 1_000_000),
        )

        val balanced = planner.plan("balanced", "example.game", odinWithDifferentOpps)!!

        assertEquals(mapOf(0 to 2_000_000, 6 to 2_300_000), balanced.cpuMaxKhz)
        // Family-scoped dispatch: this fixture carries socModel = "CQ8725S" (8-Elite family) but
        // never overrides deviceProfileId from capabilities()'s generic default. Before the
        // device-id restriction was retired, odin3ExplicitGpuMaxPowerLevel gated on
        // the literal Odin id, so a non-Odin id here fell through to the generic uncapped level 0
        // -- coincidentally correct-looking, for the wrong reason (device identity, not family).
        // Now it is family-scoped, so this IS an 8-Elite-family device and correctly gets the same
        // balanced GPU result as the Odin regardless of its device id -- which, since 2026-10-13
        // when the 8-Elite family switched to uncapped Balanced and Performance GPUs, is the uncapped level 0, no longer the retired level 2.
        assertEquals(TuningPlanner.UNCAPPED_GPU_MAX_POWER_LEVEL, balanced.gpuMaxPowerLevel)
    }

    @Test
    fun `certified family preset falls back atomically when one policy target is absent`() {
        val partialElite = capabilities().copy(
            socModel = "CQ8725S",
            supportedAxes = setOf(TuningAxis.CPU),
            cpuPolicies = listOf(
                policyWithOpps(0, listOf(998_400, 1_785_600, 2_745_600)),
                policyWithOpps(6, listOf(1_075_200, 1_900_000, 3_072_000)),
            ),
            gpuFrequenciesKhz = emptyList(),
            gpuNodeAvailable = false,
        )

        assertEquals(
            mapOf(0 to 998_400, 6 to 1_075_200),
            planner.plan("quiet", "example.game", partialElite)!!.cpuMaxKhz,
        )
    }

    @Test
    fun `certified family preset falls back atomically when topology has an extra policy`() {
        val expandedElite = capabilities().copy(
            socModel = "CQ8725S",
            supportedAxes = setOf(TuningAxis.CPU),
            cpuPolicies = listOf(
                policyWithOpps(0, listOf(998_400, 1_785_600, 2_745_600)),
                policyWithOpps(6, listOf(1_075_200, 1_958_400, 3_072_000)),
                policyWithOpps(8, listOf(500_000, 1_000_000, 1_500_000)),
            ),
            gpuFrequenciesKhz = emptyList(),
            gpuNodeAvailable = false,
        )

        assertEquals(
            mapOf(0 to 998_400, 6 to 1_075_200, 8 to 500_000),
            planner.plan("quiet", "example.game", expandedElite)!!.cpuMaxKhz,
        )
    }

    @Test
    fun `certified family preset and general fallback never select an OPP below immutable minimum`() {
        val raisedFloorSm8250 = capabilities().copy(
            socModel = "SM8250",
            supportedAxes = setOf(TuningAxis.CPU),
            cpuPolicies = listOf(
                policyWithOpps(
                    0,
                    listOf(1_171_200, 1_344_000, 1_708_800, 1_804_800),
                ).copy(minKhz = 1_344_000),
                policyWithOpps(
                    4,
                    listOf(710_400, 1_286_400, 1_862_400, 2_342_400, 2_419_200),
                ),
                policyWithOpps(
                    7,
                    listOf(844_800, 1_516_800, 2_169_600, 2_745_600, 2_841_600),
                ),
            ),
            gpuFrequenciesKhz = emptyList(),
            gpuNodeAvailable = false,
            fanNodeAvailable = false,
        )

        // Chipset-support wave stage 1: SM8250's row in MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ is
        // superseded by the Odin-anchored transposition (spec §3.5), so policy0's target is no
        // longer a literal lookup but IS still gated by this same raised minKhz floor -- 1,344,000
        // remains the answer for policy0 either way. policy4/policy7 are unaffected by the raised
        // floor (their own minKhz is untouched) but now come from the transposition's own quiet
        // little/mid/prime fractions rather than the flat 55% PULSE tier: on this sparse 5-entry
        // fixture the raw target (~1,240 kHz for policy4) falls between two widely-spaced live
        // rungs (710,400 and 1,286,400) and snaps down to the lower one -- never below the
        // policy's own minimum, which is exactly this test's point.
        assertEquals(
            mapOf(0 to 1_344_000, 4 to 710_400, 7 to 844_800),
            planner.plan("quiet", "example.game", raisedFloorSm8250)!!.cpuMaxKhz,
        )
    }

    @Test
    fun `stale GPU frequencies do not create a GPU plan without writable GPU authority`() {
        val cpuOnly = capabilities().copy(
            supportedAxes = setOf(TuningAxis.CPU),
            gpuFrequenciesKhz = listOf(305_000, 670_000, 905_000),
            gpuNodeAvailable = false,
        )

        assertNull(planner.plan("balanced", "example.game", cpuOnly)!!.gpuMaxPowerLevel)
    }

    // Chipset-support wave stage 1: this fixture's QCS8550/SM8550 row in
    // MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ is superseded by the Odin-anchored transposition
    // (spec §3.5) -- there is no QCS8550/8-Gen-2 device in hand to capture a real live OPP table
    // from , so this fixture's policy0 floor (1,459,200) stays
    // higher than any of quiet/balanced/performance's transposed raw targets (~1.09-1.22 MHz), and
    // snapAtOrBelow's own safety floor -- never invent an OPP below the lowest one live -- clamps
    // all three to that same 1,459,200 kHz rung. This is a fixture-range artifact (this synthetic
    // table's minimum sits well above where a real Snapdragon 8 Gen 2 little cluster idles), not a
    // formula defect: [snapAtOrBelow] is doing exactly what it is supposed to do when asked for
    // less than the device admits. A real QCS8550 device is required before this can be device-
    // verified.
    @Test
    fun `8 Gen 2 aliases map every profile through the Odin-anchored transposition, clamped at this fixtures live floor`() {
        listOf("QCS8550", "SM8550").forEach { soc ->
            val gen2 = capabilities().copy(
                socModel = soc,
                supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
                cpuPolicies = listOf(
                    policyWithOpps(0, listOf(1_459_200, 1_555_200, 1_785_600, 2_150_400)),
                    policyWithOpps(3, listOf(1_785_600, 2_054_400, 2_323_200, 2_841_600)),
                    policyWithOpps(7, listOf(1_843_200, 2_092_800, 2_476_800, 3_187_200)),
                ),
                gpuFrequenciesKhz = listOf(200_000, 450_000, 700_000, 1_000_000),
            )

            assertEquals(
                soc,
                mapOf(0 to 1_459_200, 3 to 1_785_600, 7 to 1_843_200),
                planner.plan("quiet", "example.game", gen2)!!.cpuMaxKhz,
            )
            assertEquals(
                soc,
                mapOf(0 to 1_459_200, 3 to 1_785_600, 7 to 1_843_200),
                planner.plan("balanced", "example.game", gen2)!!.cpuMaxKhz,
            )
            assertEquals(
                soc,
                mapOf(0 to 1_459_200, 3 to 1_785_600, 7 to 2_092_800),
                planner.plan("performance", "example.game", gen2)!!.cpuMaxKhz,
            )
        }
    }

    // SM8250 transposition: SM8250's row in MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ is superseded by the
    // Odin-anchored transposition. policy0's 17-entry OPP list below is the RP5's OWN real live
    // table (device evidence, `ro.soc.model=SM8250`, captured on device) -- not synthetic.
    // policy4/policy7 keep this repo's existing 5-entry SM8250 fixture (accurate endpoints --
    // 2,419,200 / 2,841,600 match the RP5's own real policy4/policy7 maxima -- but sparse interior,
    // since the full live tables for those two policies were not captured, only endpoints and the
    // three entries nearest the ceiling). Expect the real device's own
    // denser policy4/policy7 tables to land closer to the spec §3.3 illustrative estimates
    // (~1,240,600 / ~1,544,300 / ~1,604,100 / ~2,020,500) than this coarser fixture does --
    // device-verify on the real RP5 owns the final numbers, not this unit test.
    @Test
    fun `SM8250 (RP5) CPU targets are the Odin-anchored transposition snapped to the devices real live OPPs`() {
        val sm8250 = capabilities().copy(
            socModel = "SM8250",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            cpuPolicies = listOf(
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
            ),
        )

        // Real per-policy live maxima (1,804,800 / 2,419,200 / 2,841,600) sort ascending in the
        // same order as the policy ids themselves, so ordinal role assignment is little=policy0,
        // mid=policy4, prime=policy7 -- the same assignment the spec's own §3.3 illustration uses.
        assertEquals(
            mapOf(0 to 883_200, 4 to 710_400, 7 to 844_800),
            planner.plan("quiet", "example.game", sm8250)!!.cpuMaxKhz,
        )
        // balanced and performance share policy0's target (979,200) by design, not by coincidence
        // or fixture artifact: the Odin anchor's own little-cluster fraction is IDENTICAL for
        // balanced and performance (spec §3.1 -- reproducing Sequence 9's deliberate "performance's
        // little cluster clamps to the same value as balanced's" invariant on every transposed
        // device automatically). policy4/policy7 landing equal too IS a coarse-fixture artifact
        // (raw targets ~1.37/1.54 MHz and ~1.60/2.02 MHz both fall in the same wide gap of this
        // sparse 5-entry table) -- a denser real table would very likely separate them.
        assertEquals(
            mapOf(0 to 979_200, 4 to 1_286_400, 7 to 1_516_800),
            planner.plan("balanced", "example.game", sm8250)!!.cpuMaxKhz,
        )
        assertEquals(
            mapOf(0 to 979_200, 4 to 1_286_400, 7 to 1_516_800),
            planner.plan("performance", "example.game", sm8250)!!.cpuMaxKhz,
        )

        // GPU axis (spec §3.4), same fixture, RP5's own real 8-entry GPU table: quiet is slower
        // than balanced is slower than performance -- the ordering the profile names promise,
        // using the corrected Odin 14-level position fraction.
        //
        // on the transposed path balanced is now UNCAPPED (was level 1),
        // so only Efficiency carries a ceiling. The ordering invariant is unchanged and still
        // asserted; it is now satisfied with balanced equal to performance rather than above it.
        val quietGpu = planner.plan("quiet", "example.game", sm8250)!!.gpuMaxPowerLevel!!
        val balancedGpu = planner.plan("balanced", "example.game", sm8250)!!.gpuMaxPowerLevel!!
        val performanceGpu = planner.plan("performance", "example.game", sm8250)!!.gpuMaxPowerLevel!!
        assertEquals(3, quietGpu)
        assertEquals(0, balancedGpu)
        assertEquals(0, performanceGpu)
        assertTrue("quiet must be at or slower than balanced", quietGpu >= balancedGpu)
        assertTrue("balanced must be at or slower than performance", balancedGpu >= performanceGpu)
    }

    /**
     * The GPU transposition for Nova, pinned against the device's OWN live
     * table rather than a synthetic one -- read off test unit at
     * `/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies`, 9 levels, 680 MHz down to 124.8 MHz,
     * with `num_pwrlevels=9`.
     *
     * Balanced and Performance use uncapped GPU; Efficiency uses a mild cap proportional
     * to the 8-Elite reference.
     *
     * The proportionality claim is asserted here as arithmetic, not prose: Efficiency's transposed
     * level must land within a couple of points of the Odin's own 734/1100 = 66.7%.
     */
    @Test
    fun `Nova transposed GPU leaves balanced and performance uncapped and holds Efficiency near the Odin ratio`() {
        val novaGpuKhz = listOf(680_000, 615_000, 550_000, 475_000, 401_000, 348_000, 295_000, 220_000, 124_800)
        val nova = capabilities().copy(
            manufacturer = "Moorechip",
            model = "Retroid Pocket Nova",
            socModel = "QCS8550",
            buildFingerprint = "kalama/test",
            deviceProfileId = "retroid-qcs8550-pserver",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            gpuFrequenciesKhz = novaGpuKhz,
        )

        val quiet = planner.plan("quiet", "example.game", nova)!!.gpuMaxPowerLevel!!
        val balanced = planner.plan("balanced", "example.game", nova)!!.gpuMaxPowerLevel!!
        val performance = planner.plan("performance", "example.game", nova)!!.gpuMaxPowerLevel!!

        assertEquals("balanced must carry no GPU ceiling", 0, balanced)
        assertEquals("performance must carry no GPU ceiling", 0, performance)
        assertEquals("Efficiency keeps its mild ceiling", 3, quiet)

        // KGSL max_pwrlevel indexes the DESCENDING table, so level N is the (N+1)th fastest.
        val descending = novaGpuKhz.sortedDescending()
        val quietRatio = descending[quiet].toDouble() / descending.first()
        val odinRatio = 734_000.0 / 1_100_000.0
        assertTrue(
            "Efficiency at ${descending[quiet]} kHz is ${"%.1f".format(quietRatio * 100)}% of max, " +
                "which must track the Odin's ${"%.1f".format(odinRatio * 100)}%",
            kotlin.math.abs(quietRatio - odinRatio) < 0.05,
        )
    }

    /**
     * The uncapped Balanced and Performance GPU settings reached the Odin family on 2026-10-13. The
     * sequence-10 manifest that used to pin balanced at level 2 is retired as a behavioural gate,
     * so only quiet keeps an explicit cap here; balanced and performance are level 0.
     */
    @Test
    fun `the Odins explicit GPU levels are quiet capped and balanced and performance uncapped`() {
        val odin = capabilities().copy(
            manufacturer = "ayn",
            model = "Odin3",
            socModel = "CQ8725S",
            buildFingerprint = "qti/sun/test",
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
        )
        assertEquals(5, planner.plan("quiet", "example.game", odin)!!.gpuMaxPowerLevel)
        assertEquals(0, planner.plan("balanced", "example.game", odin)!!.gpuMaxPowerLevel)
        assertEquals(0, planner.plan("performance", "example.game", odin)!!.gpuMaxPowerLevel)
    }

    @Test
    fun `general purpose CPU tiers derive only from an unknown devices live OPPs`() {
        val general = capabilities().copy(
            socModel = "MT9999",
            supportedAxes = setOf(TuningAxis.CPU),
            cpuPolicies = listOf(
                policyWithOpps(0, listOf(300_000, 500_000, 700_000, 900_000, 1_100_000, 1_200_000)),
                policyWithOpps(4, listOf(500_000, 800_000, 1_100_000, 1_400_000, 1_700_000, 2_000_000)),
            ),
            gpuFrequenciesKhz = emptyList(),
            gpuNodeAvailable = false,
            fanNodeAvailable = false,
        )

        assertEquals(
            mapOf(0 to 500_000, 4 to 1_100_000),
            planner.plan("quiet", "example.game", general)!!.cpuMaxKhz,
        )
        assertEquals(
            mapOf(0 to 900_000, 4 to 1_400_000),
            planner.plan("balanced", "example.game", general)!!.cpuMaxKhz,
        )
        val performance = planner.plan("performance", "example.game", general)!!
        assertEquals(mapOf(0 to 1_100_000, 4 to 1_700_000), performance.cpuMaxKhz)
        assertNull(performance.gpuMaxPowerLevel)
    }

    // All three use SMART. Previously, quiet/balanced/performance carried
    // SILENT/SMART/SPORT respectively on a non-Odin fan-bearing device (this fixture is the RP5).
    // SILENT and SPORT both stop the vendor's own regulation loop -- the measured Odin defect this
    // whole release fixes -- so every profile is now SMART instead. Differential: reverting
    // TuningPlanner.profiles' quiet/performance fanMode fields back to FAN_SILENT/FAN_SPORT makes
    // this test fail.
    @Test
    fun `qualified fan axis maps every profile to vendor Smart on a non-Odin device`() {
        val withFan = capabilities()

        assertEquals(TuningPlanner.FAN_SMART, planner.plan("quiet", "example.game", withFan)!!.fanMode)
        assertEquals(TuningPlanner.FAN_SMART, planner.plan("balanced", "example.game", withFan)!!.fanMode)
        assertEquals(TuningPlanner.FAN_SMART, planner.plan("performance", "example.game", withFan)!!.fanMode)
        // stock is untouched: it carries no fanMode shape at all, on any device.
        assertNull(planner.plan("stock", "example.game", withFan)!!.fanMode)
    }

    // TR-08 removed the SPORT override. This test used to pin that
    // the exact Odin 3 device profile's selectedFanMode branch overrode every tunable profile's
    // vendorMode to FAN_SPORT regardless of the now-uniform SMART input -- Wave 1 wrote it
    // specifically to protect that branch because flipping it alone, before a manifest re-sign,
    // would have made the derived plan unequal to the sequence-10 signed plan (`fanMode=5`) and
    // taken the Odin stock-only via `ProductQualificationRuntime.planMatches`. TR-08 ships
    // together with the sequence-11 re-sign that carries `fanMode=4`, so that guard is gone and the
    // override branch is deleted. Renamed and rewritten to pin the new truth: the Odin now derives
    // SMART exactly like every other RP/AYN device, via the same `else -> vendorMode` arm.
    @Test
    fun `exact Odin fan axis now derives vendor Smart like every other device, TR-08`() {
        val odin = capabilities().copy(
            manufacturer = "AYN",
            model = "Odin3",
            socModel = "CQ8725S",
            deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        )

        listOf("quiet", "balanced", "performance").forEach { profileId ->
            assertEquals(
                profileId,
                TuningPlanner.FAN_SMART,
                planner.plan(profileId, "example.game", odin)!!.fanMode,
            )
        }
        assertNull(planner.plan("stock", "example.game", odin)!!.fanMode)
    }

    // TR-08: `auto.fanMode` used to be FAN_SPORT (the retired Odin override); it is now FAN_SMART,
    // same as every other device's ordinary entry. The MANUAL_OVERRIDE half of this test is
    // deliberately unmoved -- TR-08 keeps mode 6 reachable through the explicit override, only the
    // ordinary/automatic entry's target changed.
    @Test
    fun `explicit Odin manual intent changes only fan target and still requires qualification`() {
        val odin = capabilities().copy(
            manufacturer = "AYN",
            model = "Odin3",
            socModel = "CQ8725S",
            deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        )
        val auto = planner.plan("balanced", "example.game", odin)!!
        val manual = planner.plan(
            "balanced",
            "example.game",
            odin,
            TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        )!!

        // Ordinary entry now derives vendor Smart like every other device (TR-08) -- mode 6 remains
        // reachable only through the explicit override, not a one-way door, and still changes only
        // the fan axis.
        assertEquals(TuningPlanner.FAN_SMART, auto.fanMode)
        assertEquals(TuningPlanner.FAN_MANUAL, manual.fanMode)
        assertEquals(auto.cpuMaxKhz, manual.cpuMaxKhz)
        assertEquals(auto.gpuMaxPowerLevel, manual.gpuMaxPowerLevel)
        assertFalse(manual.calibrated)
    }

    // TR-08: the Odin's dedicated SPORT-override branch in selectedFanMode is retired, so its
    // ordinary derivation now reaches this calibrationLookup gate carrying FAN_SMART -- the same
    // now-uniform value the shape map already fed every other device. Renamed and
    // rewritten from the pre-TR-08 "Sport" version: the property under test (calibrationLookup sees
    // the plan's actual, exact fan target and never silently falls back to manual or no-fan) is
    // unchanged, only which mode is exact.
    @Test
    fun `Odin requires exact qualified Smart fan and never falls back to manual or no-fan`() {
        val odin = capabilities().copy(
            manufacturer = "AYN",
            model = "Odin3",
            socModel = "CQ8725S",
            deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        )
        val smart = TuningPlanner { plan, _ -> plan.fanMode == TuningPlanner.FAN_SMART }
            .plan("balanced", "example.game", odin)!!
        val noFanOnly = TuningPlanner { plan, _ -> plan.fanMode == null }
            .plan("balanced", "example.game", odin)!!

        assertEquals(TuningPlanner.FAN_SMART, smart.fanMode)
        assertTrue(smart.calibrated)
        assertEquals(TuningPlanner.FAN_SMART, noFanOnly.fanMode)
        assertFalse(noFanOnly.calibrated)
        assertEquals(smart.cpuMaxKhz, noFanOnly.cpuMaxKhz)
        assertEquals(smart.gpuMaxPowerLevel, noFanOnly.gpuMaxPowerLevel)
    }

    // ---- Vendor performance mode (2026-10-13 vendor-mode design, lane 1) ----

    /**
     *  The vendor's modes are 0 = Standard, 1 = Performance, 2 = High performance, so
     * quiet -> 0, balanced -> 1, performance -> 2, and stock is never written.
     */
    @Test
    fun `vendor modes are the vendor's three named values`() {
        assertEquals(0, TuningPlanner.VENDOR_MODE_STANDARD)
        assertEquals(1, TuningPlanner.VENDOR_MODE_PERFORMANCE)
        assertEquals(2, TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE)
        assertEquals(setOf(0, 1, 2), TuningPlanner.KNOWN_VENDOR_MODES)
    }

    @Test
    fun `vendor mode mapping is quiet 0 balanced 1 performance 2 and stock null on every family with the key`() {
        // Odin (8 Elite, both aliases), Thor/RP5-class (8 Gen 2, both aliases), Nova (SM8250), a
        // Qualcomm candidate and a non-Qualcomm SoC: the family picks the table, the capability
        // gates the axis, and no family is a reason to withhold the mode.
        listOf("CQ8725S", "SM8750", "QCS8550", "SM8550", "SM8250", "SM8650", "MT6893").forEach { soc ->
            val caps = capabilities().copy(socModel = soc).withVendorMode()

            assertNull(soc, planner.plan("stock", "example.game", caps)!!.vendorMode)
            assertEquals(soc, 0, planner.plan("quiet", "example.game", caps)!!.vendorMode)
            assertEquals(soc, 1, planner.plan("balanced", "example.game", caps)!!.vendorMode)
            assertEquals(soc, 2, planner.plan("performance", "example.game", caps)!!.vendorMode)
        }
    }

    @Test
    fun `without the vendor mode capability no plan carries a vendor mode and no hash changes`() {
        val withoutKey = capabilities()
        assertFalse(withoutKey.vendorPerformanceModePresent)
        val withKey = withoutKey.withVendorMode()

        listOf("stock", "quiet", "balanced", "performance").forEach { profileId ->
            val plain = planner.plan(profileId, "example.game", withoutKey)!!
            assertNull(profileId, plain.vendorMode)
            // The capability changes ONLY vendorMode; every other field is the same plan.
            assertEquals(profileId, plain, planner.plan(profileId, "example.game", withKey)!!.copy(vendorMode = null))
        }
    }

    @Test
    fun `the planner gates on the axis in supportedAxes, never on the flag alone or on a family`() {
        val flagOnly = capabilities().copy(vendorPerformanceModePresent = true)
        assertFalse(TuningAxis.VENDOR_MODE in flagOnly.supportedAxes)
        listOf("stock", "quiet", "balanced", "performance").forEach { profileId ->
            assertNull(profileId, planner.plan(profileId, "example.game", flagOnly)!!.vendorMode)
        }
        val axisOnly = capabilities().copy(supportedAxes = capabilities().supportedAxes + TuningAxis.VENDOR_MODE)
        assertEquals(1, planner.plan("balanced", "example.game", axisOnly)!!.vendorMode)
    }

    @Test
    fun `rungPlan carries the same vendor mode as the ordinary plan`() {
        val caps = odinCapabilities().withVendorMode()
        mapOf("quiet" to 0, "balanced" to 1, "performance" to 2).forEach { (profileId, mode) ->
            val rung = ThermalDerateLadder.forProfile(profileId).at(1)
            assertEquals(profileId, mode, planner.plan(profileId, "example.game", caps)!!.vendorMode)
            assertEquals(profileId, mode, planner.rungPlan(profileId, "example.game", caps, rung)!!.vendorMode)
        }
        assertNull(
            planner.rungPlan(
                "balanced",
                "example.game",
                odinCapabilities(),
                ThermalDerateLadder.forProfile("balanced").at(1),
            )!!.vendorMode,
        )
    }

    @Test
    fun `the vendor mode table resolves family overrides, never writes stock, and drops unknown modes`() {
        val family = TweakerHardwareFamilies.SNAPDRAGON_8_GEN_2_FAMILY_ID
        // No family overrides today: every family takes the profile default.
        assertTrue(VendorModeTable.FAMILY_OVERRIDES.isEmpty())
        assertEquals(1, VendorModeTable.modeFor(family, "balanced", profileDefault = 1))
        assertEquals(0, VendorModeTable.modeFor("any-other-family", "quiet", profileDefault = 0))

        // The override mechanism: a family row replaces the default for that family only.
        val overrides = mapOf(family to mapOf("balanced" to 2))
        assertEquals(2, VendorModeTable.modeFor(family, "balanced", profileDefault = 1, overrides = overrides))
        assertEquals(1, VendorModeTable.modeFor("qualcomm-8-elite", "balanced", profileDefault = 1, overrides = overrides))
        assertEquals(0, VendorModeTable.modeFor(family, "quiet", profileDefault = 0, overrides = overrides))

        // Stock is the player's own mode: not even an override may write it.
        assertNull(VendorModeTable.modeFor(family, "stock", profileDefault = null, overrides = mapOf(family to mapOf("stock" to 2))))
        assertNull(VendorModeTable.modeFor(family, "stock", profileDefault = 2))

        // A value the apply bound would refuse is dropped, not planned.
        assertNull(VendorModeTable.modeFor(family, "balanced", profileDefault = 3))
        assertNull(VendorModeTable.modeFor(family, "balanced", profileDefault = 1, overrides = mapOf(family to mapOf("balanced" to 7))))
    }

    /**
     * The identity of every plan that predates the axis must not move: a journal active across the
     * upgrade is re-verified against this hash. The expected value is computed here from the
     * historical canonical string, independently of [PlanHash]'s own code.
     */
    @Test
    fun `an existing plan's hash is byte identical and a vendor mode only appends vmode`() {
        val plan = TuningPlan(
            profileId = "balanced",
            packageName = "example.game",
            cpuMaxKhz = mapOf(6 to 2_438_400, 0 to 1_996_800),
            gpuMaxPowerLevel = 0,
            fanMode = 4,
            calibrated = false,
        )
        assertNull(plan.vendorMode)
        val historical = "balanced|example.game|cpu0=1996800|cpu6=2438400|gpu=0|fan=4|calibrated=false"

        assertEquals(sha256Hex(historical), PlanHash.of(plan))
        assertEquals(sha256Hex(historical), PlanHash.ofPersisted(PersistedTuningPlan.from(plan)))
        // Null gpu/fan keep their own historical spelling.
        val bare = plan.copy(gpuMaxPowerLevel = null, fanMode = null, calibrated = true)
        assertEquals(
            sha256Hex("balanced|example.game|cpu0=1996800|cpu6=2438400|gpu=null|fan=null|calibrated=true"),
            PlanHash.of(bare),
        )

        listOf(0, 1, 2).forEach { mode ->
            assertEquals(sha256Hex("$historical|vmode=$mode"), PlanHash.of(plan.copy(vendorMode = mode)))
        }
        assertTrue(PlanHash.of(plan) != PlanHash.of(plan.copy(vendorMode = 1)))
        assertTrue(PlanHash.of(plan.copy(vendorMode = 1)) != PlanHash.of(plan.copy(vendorMode = 2)))
    }

    @Test
    fun `the persisted plan carries the vendor mode both ways`() {
        val plan = TuningPlan(
            profileId = "performance",
            packageName = "example.game",
            cpuMaxKhz = mapOf(0 to 1_996_800),
            gpuMaxPowerLevel = 0,
            fanMode = 4,
            calibrated = false,
            vendorMode = 2,
        )

        val persisted = PersistedTuningPlan.from(plan)

        assertEquals(2, persisted.vendorMode)
        assertEquals(plan, persisted.toTuningPlan())
        assertEquals(PlanHash.of(plan), PlanHash.ofPersisted(persisted))
        // A plan journaled before the axis existed reads back with no vendor mode at all.
        assertNull(PersistedTuningPlan("quiet", "p", mapOf(0 to 1), null, null, false).vendorMode)
    }

    /**
     * The 2026-08-22 GPU policy leaves Balanced and Performance uncapped and Quiet mildly capped,
     * enforced on the 8-Elite family on 2026-10-13.
     * The 8-Elite family's balanced GPU ceiling is level 0, quiet keeps its cap, and no CPU ceiling
     * moves (Balanced and Performance KEEP their CPU clamps).
     */
    @Test
    fun `8 Elite balanced GPU is uncapped while quiet keeps its cap and every CPU clamp is unchanged`() {
        val elite = odinCapabilities()

        val quiet = planner.plan("quiet", "example.game", elite)!!
        val balanced = planner.plan("balanced", "example.game", elite)!!
        val performance = planner.plan("performance", "example.game", elite)!!

        assertEquals(TuningPlanner.ODIN_3_QUIET_GPU_MAX_POWER_LEVEL, quiet.gpuMaxPowerLevel)
        assertEquals(TuningPlanner.UNCAPPED_GPU_MAX_POWER_LEVEL, balanced.gpuMaxPowerLevel)
        assertEquals(TuningPlanner.UNCAPPED_GPU_MAX_POWER_LEVEL, performance.gpuMaxPowerLevel)
        assertEquals(mapOf(0 to 1_785_600, 6 to 2_246_400), quiet.cpuMaxKhz)
        assertEquals(mapOf(0 to 1_996_800, 6 to 2_438_400), balanced.cpuMaxKhz)
        assertEquals(mapOf(0 to 1_996_800, 6 to 3_072_000), performance.cpuMaxKhz)
    }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `unknown profile is rejected`() {
        assertNull(planner.plan("magic", "example.game", capabilities()))
    }

    // LAB rung verification: rungPlan must reproduce every
    // one of the 9 real ThermalDerateLadder values (3 profiles x 3 rungs) exactly, on the real
    // Odin 3 policy0/policy6 topology -- not a synthetic stand-in.
    @Test
    fun `rungPlan reproduces every ThermalDerateLadder rung exactly for every profile`() {
        listOf("performance", "balanced", "quiet").forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            (1..ladder.maxRung).forEach { rungIndex ->
                val rung = ladder.at(rungIndex)
                val plan = planner.rungPlan(profileId, "example.game", odinCapabilities(), rung)
                assertEquals(
                    "$profileId rung $rungIndex",
                    mapOf(0 to rung.policy0MaxKhz, 6 to rung.policy6MaxKhz),
                    plan?.cpuMaxKhz,
                )
                assertEquals("$profileId rung $rungIndex", rung.gpuMaxPwrLevel, plan?.gpuMaxPowerLevel)
                assertEquals(profileId, plan?.profileId)
            }
        }
    }

    // rungPlan() calls the exact same private selectedFanMode() as plan()
    // (see rungPlan's own KDoc, "Fan mode ... UNCHANGED from plan"), so this parity property is
    // structurally invariant to which vendorMode profiles carries -- it held before uniform SMART routing (when
    // balanced alone was already FAN_SMART) and holds now that all three are. Widened from
    // "balanced" alone to all three profile ids so the now-uniform SMART shape is actually
    // exercised by this parity check on the fixture that changed (quiet/performance), not only on
    // the one that didn't.
    //
    // TR-08: the Odin fixture's explicit assertion below changed from FAN_SPORT to FAN_SMART --
    // the device-profile override that used to pin it to Sport is retired, so the Odin now derives
    // the same vendorMode as the RP5 fixture above.
    @Test
    fun `rungPlan fan mode matches the ordinary profile plan for every profile`() {
        val fanCapable = odinCapabilities().copy(supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN))

        listOf("quiet", "balanced", "performance").forEach { profileId ->
            val rung = ThermalDerateLadder.forProfile(profileId).at(1)
            val ordinary = planner.plan(profileId, "example.game", fanCapable)!!
            val rungPlan = planner.rungPlan(profileId, "example.game", fanCapable, rung)!!

            assertEquals(profileId, ordinary.fanMode, rungPlan.fanMode)
            // On the Odin fixture both now derive Smart, same as every other device (TR-08) --
            // this is the one place a diverging FAN_SMART/FAN_SPORT split between rungPlan and plan
            // could hide, so it is asserted explicitly rather than only via equality above.
            assertEquals(profileId, TuningPlanner.FAN_SMART, ordinary.fanMode)
        }
    }

    @Test
    fun `rungPlan calibration follows the same calibrationLookup gate as an ordinary apply`() {
        val rung = ThermalDerateLadder.forProfile("performance").at(1)

        assertFalse(TuningPlanner().rungPlan("performance", "example.game", odinCapabilities(), rung)!!.calibrated)
        assertTrue(
            TuningPlanner { _, _ -> true }
                .rungPlan("performance", "example.game", odinCapabilities(), rung)!!.calibrated,
        )
    }

    @Test
    fun `rungPlan rejects an unknown profile`() {
        val rung = ThermalDerateLadder.forProfile("performance").at(1)
        assertNull(planner.rungPlan("magic", "example.game", odinCapabilities(), rung))
    }

    @Test
    fun `rungPlan refuses a device topology that is not exactly policy0 and policy6`() {
        val rung = ThermalDerateLadder.forProfile("performance").at(1)
        // The default RP5 fixture (policies 0, 4, 7) does not carry policy 6 -- rungPlan must
        // refuse cleanly here rather than silently apply policy0's clamp and leave the other
        // policies at whatever they already were.
        assertNull(planner.rungPlan("performance", "example.game", capabilities(), rung))
    }

    /** The probe admits the vendor-mode axis and sets the flag together; the planner gates on the axis. */
    private fun HardwareCapabilities.withVendorMode() = copy(
        supportedAxes = supportedAxes + TuningAxis.VENDOR_MODE,
        vendorPerformanceModePresent = true,
    )

    private fun odinCapabilities() = capabilities().copy(
        manufacturer = "ayn",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "ayn/sun/odin3-rung-test",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
        cpuPolicies = listOf(
            policyWithOpps(
                0,
                listOf(
                    384_000, 556_800, 748_800, 960_000, 1_152_000, 1_363_200, 1_555_200, 1_785_600,
                    1_996_800, 2_227_200, 2_400_000, 2_745_600, 2_918_400, 3_072_000, 3_321_600, 3_532_800,
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
        fanNodeAvailable = false,
    )

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint = "rp5/test",
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            policy(0, 1_804_800),
            policy(4, 2_419_200),
            policy(7, 2_841_600),
        ),
        gpuFrequenciesKhz = listOf(305_000, 400_000, 441_600, 490_000, 525_000, 587_000, 670_000, 905_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private fun policy(id: Int, max: Int) = CpuPolicy(
        id = id,
        path = "/policy$id",
        cpuIds = listOf(id),
        availableFrequenciesKhz = listOf(max / 2, max * 3 / 4, max),
        minKhz = max / 2,
        maxKhz = max,
        currentKhz = max,
    )

    private fun policyWithOpps(id: Int, available: List<Int>) = CpuPolicy(
        id = id,
        path = "/policy$id",
        cpuIds = listOf(id),
        availableFrequenciesKhz = available,
        minKhz = available.first(),
        maxKhz = available.last(),
        currentKhz = available.last(),
    )
}
