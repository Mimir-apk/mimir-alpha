package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE2_THERMAL_DERATE_SPEC.md S3.5 (as amended 2026-08-06: CPU+GPU derate together, not
 * CPU-first/GPU-last) and S4's safety argument -- "every rung is strictly dominated by the
 * signed nominal" -- must hold BY CONSTRUCTION over the data table, not by convention. Sequence 9
 *  extends this to three independent per-profile ladders, so
 * every assertion below is repeated PER PROFILE rather than once globally -- a shared assertion
 * would miss e.g. a Balanced rung dominated by Performance's nominal instead of its own. These
 * tests are the proof, independent of [HardwareSessionSupervisor] (spec S5 test 8: "no rung ever
 * exceeds the qualified nominal, in any state").
 */
class ThermalDerateLadderTest {

    private val profileIds = listOf("performance", "balanced", "quiet")

    @Test
    fun `every profile ladder rung is strictly dominated by that profiles own nominal`() {
        profileIds.forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            ladder.rungs.forEachIndexed { index, rung ->
                assertTrue(
                    "[$profileId] rung ${index + 1} policy6MaxKhz=${rung.policy6MaxKhz} must be " +
                        "strictly below its OWN nominal ${ladder.nominal.policy6MaxKhz}",
                    rung.policy6MaxKhz < ladder.nominal.policy6MaxKhz,
                )
                assertTrue(
                    "[$profileId] rung ${index + 1} gpuMaxPwrLevel=${rung.gpuMaxPwrLevel} must be " +
                        "no faster than its OWN nominal ${ladder.nominal.gpuMaxPwrLevel} (larger " +
                        "number = slower)",
                    rung.gpuMaxPwrLevel >= ladder.nominal.gpuMaxPwrLevel,
                )
            }
        }
    }

    // policy0 stays at its profile's own nominal at every rung -- policy (WAVE2 spec S3.5,
    // reaffirmed SEQ9 spec S3: "policy0 stays at each profile's own nominal on every rung").
    // Structural, not conventional: [ThermalDerateLadder]'s private `ladder` builder pins every
    // rung it constructs to the policy0 value passed in once, and no per-rung entry can override
    // it.
    @Test
    fun `policy0 never moves from its own profiles nominal on any rung`() {
        profileIds.forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            ladder.rungs.forEach { rung ->
                assertEquals(ladder.nominal.policy0MaxKhz, rung.policy0MaxKhz)
            }
        }
    }

    // Every successive rung must be monotonically slower on both axes: getting the GPU's inverted scale backwards here would
    // overclock the GPU partway down the ladder during a real thermal event. Kept distinct from
    // the GPU-only inversion guard below so the two failure modes (CPU regresses vs. GPU
    // regresses) are independently attributable.
    @Test
    fun `every successive rung is monotonically slower on both axes never faster than the one before`() {
        profileIds.forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            val all = listOf(ladder.nominal) + ladder.rungs
            for (i in 1 until all.size) {
                val prev = all[i - 1]
                val curr = all[i]
                assertTrue(
                    "[$profileId] policy6MaxKhz must never increase stepping down the ladder: " +
                        "step ${i - 1} (${prev.policy6MaxKhz}) -> step $i (${curr.policy6MaxKhz})",
                    curr.policy6MaxKhz <= prev.policy6MaxKhz,
                )
                assertTrue(
                    "[$profileId] gpuMaxPwrLevel must never decrease stepping down the ladder -- " +
                        "it is inverted, so a decrease would overclock the GPU: step ${i - 1} " +
                        "(${prev.gpuMaxPwrLevel}) -> step $i (${curr.gpuMaxPwrLevel})",
                    curr.gpuMaxPwrLevel >= prev.gpuMaxPwrLevel,
                )
            }
        }
    }

    // Dedicated guard, isolated from the combined monotonicity test above: a derate must
    // increase max_pwrlevel, and any rung decreasing it must fail. Kept as its
    // own GPU-only test so inverting one rung's GPU direction fails exactly this test (and the
    // combined test above), without needing the CPU-axis assertions to attribute the break.
    @Test
    fun `GPU inversion guard a derate never decreases max_pwrlevel for any profile`() {
        profileIds.forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            val all = listOf(ladder.nominal) + ladder.rungs
            all.zipWithNext().forEach { (prev, curr) ->
                assertTrue(
                    "[$profileId] gpuMaxPwrLevel decreased -- this would OVERCLOCK the GPU " +
                        "during a thermal event: ${prev.gpuMaxPwrLevel} -> ${curr.gpuMaxPwrLevel}",
                    curr.gpuMaxPwrLevel >= prev.gpuMaxPwrLevel,
                )
            }
        }
    }

    // Performance rung 1 has both axes verified on-device 2026-08-06. Pins the grounded values so a future edit cannot silently drift them.
    // Unchanged by sequence 9.
    @Test
    fun `performance rung 1 matches the verified device readings exactly`() {
        val performance = ThermalDerateLadder.forProfile("performance")
        val rung1 = performance.at(1)
        assertEquals(2_841_600, rung1.policy6MaxKhz)
        assertEquals(2, rung1.gpuMaxPwrLevel)
        assertEquals(performance.nominal.policy0MaxKhz, rung1.policy0MaxKhz)
    }

    // Performance rungs 2-3 are conservative extrapolation, not device-verified. These pins catch
    // drift in the configured values; device validation remains outstanding. Unchanged by sequence 9.
    @Test
    fun `performance rungs 2 and 3 match the TD extrapolated table and rung 3 doubles as the floor`() {
        val performance = ThermalDerateLadder.forProfile("performance")
        val rung2 = performance.at(2)
        assertEquals(2_649_600, rung2.policy6MaxKhz)
        assertEquals(2, rung2.gpuMaxPwrLevel)

        val rung3 = performance.at(3)
        assertEquals(2_438_400, rung3.policy6MaxKhz)
        assertEquals(3, rung3.gpuMaxPwrLevel)

        assertEquals(3, performance.maxRung)
        assertEquals(rung3, performance.rungs.last())
    }

    // Balanced is new in sequence 9. Its configured nominal is policy0 1,996,800, policy6
    // 2,438,400, GPU L2. Every rung below it, including rung 1, is conservative extrapolation,
    // never device-verified, and still needs validation. These pins catch silent drift.
    @Test
    fun `balanced ladder matches the SEQ9 extrapolated table with unverified derate rungs`() {
        val balanced = ThermalDerateLadder.forProfile("balanced")
        assertEquals(1_996_800, balanced.nominal.policy0MaxKhz)
        assertEquals(2_438_400, balanced.nominal.policy6MaxKhz)
        assertEquals(2, balanced.nominal.gpuMaxPwrLevel)

        val rung1 = balanced.at(1)
        assertEquals(2_246_400, rung1.policy6MaxKhz)
        assertEquals(3, rung1.gpuMaxPwrLevel)

        val rung2 = balanced.at(2)
        assertEquals(1_958_400, rung2.policy6MaxKhz)
        assertEquals(3, rung2.gpuMaxPwrLevel)

        val floor = balanced.at(3)
        assertEquals(1_958_400, floor.policy6MaxKhz)
        assertEquals(4, floor.gpuMaxPwrLevel)

        assertEquals(3, balanced.maxRung)
        assertEquals(floor, balanced.rungs.last())
    }

    // Efficiency (`quiet`) is new in sequence 9. Its configured nominal is policy0 1,785,600,
    // policy6 2,246,400, GPU L5. Every rung below it is conservative extrapolation awaiting validation.
    @Test
    fun `quiet ladder matches the SEQ9 extrapolated table with unverified derate rungs`() {
        val quiet = ThermalDerateLadder.forProfile("quiet")
        assertEquals(1_785_600, quiet.nominal.policy0MaxKhz)
        assertEquals(2_246_400, quiet.nominal.policy6MaxKhz)
        assertEquals(5, quiet.nominal.gpuMaxPwrLevel)

        val rung1 = quiet.at(1)
        assertEquals(1_958_400, rung1.policy6MaxKhz)
        assertEquals(5, rung1.gpuMaxPwrLevel)

        val rung2 = quiet.at(2)
        assertEquals(1_958_400, rung2.policy6MaxKhz)
        assertEquals(6, rung2.gpuMaxPwrLevel)

        val floor = quiet.at(3)
        assertEquals(1_689_600, floor.policy6MaxKhz)
        assertEquals(6, floor.gpuMaxPwrLevel)

        assertEquals(3, quiet.maxRung)
        assertEquals(floor, quiet.rungs.last())
    }

    @Test
    fun `at clamps below nominal and above the floor instead of throwing for every profile`() {
        profileIds.forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            assertEquals(ladder.nominal, ladder.at(0))
            assertEquals(ladder.nominal, ladder.at(-5))
            assertEquals(ladder.rungs.last(), ladder.at(ladder.maxRung))
            assertEquals(ladder.rungs.last(), ladder.at(99))
        }
    }

    // HardwareSessionSupervisor Stage A (see its own doc + TODO) tracks one shared abstract rung
    // POSITION, not a per-profile hardware write yet, and reads ThermalDerateLadder.maxRung
    // directly rather than a specific profile's ladder. That is only safe while every profile
    // agrees on the rung count; this test is the guard that catches the day it stops being true.
    @Test
    fun `every profile ladder has the same rung count as the shared top level maxRung`() {
        profileIds.forEach { profileId ->
            assertEquals(
                profileId,
                ThermalDerateLadder.maxRung,
                ThermalDerateLadder.forProfile(profileId).maxRung,
            )
        }
    }

    // LAB rung verification: ThermalDerateRungRequest is the
    // fail-clean entry point a diagnostic command uses -- unlike [ThermalDerateProfileLadder.at],
    // it must REJECT an out-of-range index, never clamp it to nominal/floor. Exercises all 9 real
    // ladder values (3 profiles x 3 rungs) through the resolver, not just the raw table.
    @Test
    fun `ThermalDerateRungRequest resolves every real rung of every profile exactly`() {
        profileIds.forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            (1..ladder.maxRung).forEach { rungIndex ->
                val resolution = ThermalDerateRungRequest.resolve(profileId, rungIndex)
                assertEquals(
                    "$profileId rung $rungIndex",
                    ThermalDerateRungResolution.Resolved(ladder.at(rungIndex)),
                    resolution,
                )
            }
        }
    }

    @Test
    fun `ThermalDerateRungRequest rejects an out-of-range rung index instead of clamping`() {
        profileIds.forEach { profileId ->
            val ladder = ThermalDerateLadder.forProfile(profileId)
            listOf(0, -1, -5, ladder.maxRung + 1, 99).forEach { rungIndex ->
                assertEquals(
                    "$profileId rung $rungIndex",
                    ThermalDerateRungResolution.OutOfRange(ladder.maxRung),
                    ThermalDerateRungRequest.resolve(profileId, rungIndex),
                )
            }
        }
    }

    @Test
    fun `ThermalDerateRungRequest rejects an unknown profile before ever consulting a rung index`() {
        assertEquals(
            ThermalDerateRungResolution.UnknownProfile,
            ThermalDerateRungRequest.resolve("magic", 1),
        )
        assertEquals(
            ThermalDerateRungResolution.UnknownProfile,
            ThermalDerateRungRequest.resolve("", 1),
        )
        // An unknown profile is rejected even when the rung index would also be out of range for
        // every real ladder -- profile identity is checked first, per resolve()'s own order.
        assertEquals(
            ThermalDerateRungResolution.UnknownProfile,
            ThermalDerateRungRequest.resolve("magic", 99),
        )
    }
}
