package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the recovery window to what the hardware actually did, so nobody can shrink it back to
 * something that expires while the fan is still at 50%.
 *
 * The numbers here are not taste. They are five consecutive Odin trials, 2026-08-13, every trial
 * validated as a Mimir-duty-owned vendor mode before and after.
 */
class PowerTransitionFanRecoveryTest {

    @Test
    fun `window outlasts the worst excursion measured on hardware`() {
        assertTrue(
            "recovery window must outlast the measured worst-case excursion end " +
                "(${PowerTransitionFanRecovery.MEASURED_MAX_EXCURSION_END_MS}ms); " +
                "a shorter window stops looking while the fan is still at 50%",
            PowerTransitionFanRecovery.WINDOW_MS >=
                PowerTransitionFanRecovery.MEASURED_MAX_EXCURSION_END_MS,
        )
    }

    /**
     * The property that matters is NOT "a cycle runs before the first possible onset" -- a cycle
     * that runs before the excursion exists sees nothing and is wasted. It is that whenever the
     * excursion starts, something looks at it within one spacing.
     */
    @Test
    fun `every onset measured on hardware is noticed within one spacing`() {
        val offsets = PowerTransitionFanRecovery.recoveryOffsetsMs()
        // The five measured onsets, 2026-08-13, charging-status stimulus.
        listOf(142L, 430L, 585L, 798L, 1069L).forEach { onset ->
            val next = offsets.firstOrNull { it >= onset }
            assertTrue("no recovery cycle runs at or after an onset of ${onset}ms: $offsets", next != null)
            assertTrue(
                "an excursion starting at ${onset}ms would go unnoticed for ${next!! - onset}ms, " +
                    "worse than the declared ${PowerTransitionFanRecovery.SPACING_MS}ms spacing",
                next - onset <= PowerTransitionFanRecovery.SPACING_MS,
            )
        }
    }

    @Test
    fun `worst-case time to notice is one spacing, not one control tick`() {
        val offsets = PowerTransitionFanRecovery.recoveryOffsetsMs()
        val gaps = offsets.zipWithNext { a, b -> b - a }
        assertTrue(
            "every gap must be the declared spacing; ragged gaps mean the real time-to-notice " +
                "is not the advertised one: $offsets",
            gaps.all { it == PowerTransitionFanRecovery.SPACING_MS },
        )
        assertTrue(
            "the whole point is beating the ${SmartFanThermalController.DEFAULT_TICK_MS}ms control tick",
            PowerTransitionFanRecovery.SPACING_MS < SmartFanThermalController.DEFAULT_TICK_MS,
        )
    }

    /**
     * this class is a backstop for a duty-owned session (the
     * Odin's SPORT, unaffected by R2/R3), not a primary. It carries no fan-mode parameter at all --
     * its schedule is a pure function of elapsed time since the power broadcast -- so there is no
     * "off-SMART" input to gate a differential test on directly. What is pinned here instead is the
     * property a careless read of R2 ("the loop is never stopped, so nothing needs to reassert")
     * could tempt someone into breaking: the schedule must remain unconditional and non-empty
     * regardless of any fan-mode concept, because the one device this wave still leaves duty-owned
     * (the Odin, SPORT) still needs it. [[right-numbers-wrong-mechanism-label]]: this test would
     * not catch a caller-side regression that stopped INVOKING this schedule for a duty-owned
     * session -- that wiring lives in `MimirTweakerService`, outside this class and outside this
     * lane's declared write scope for that file.
     */
    @Test
    fun `the recovery schedule stays unconditional -- it has no fan-mode input to gate on`() {
        assertTrue(
            "the schedule must still fire even though R2/R3 means it finds nothing to repair on " +
                "a SMART-driven device -- it is the Odin's SPORT session this backstop protects",
            PowerTransitionFanRecovery.recoveryOffsetsMs().isNotEmpty(),
        )
    }

    @Test
    fun `schedule is bounded and cannot flood the single monitor thread`() {
        val offsets = PowerTransitionFanRecovery.recoveryOffsetsMs()
        assertEquals(
            "offsets must be strictly increasing and unique",
            offsets.distinct().sorted(),
            offsets,
        )
        assertTrue("no offset may outlive the window: $offsets", offsets.all { it <= PowerTransitionFanRecovery.WINDOW_MS })
        assertTrue("window must actually schedule work", offsets.isNotEmpty())
        // One cycle per spacing across the window, and nothing beyond it. The bound is what makes
        // this safe to hang off a broadcast that can arrive repeatedly.
        assertEquals(
            (PowerTransitionFanRecovery.WINDOW_MS / PowerTransitionFanRecovery.SPACING_MS).toInt(),
            offsets.size,
        )
    }
}
