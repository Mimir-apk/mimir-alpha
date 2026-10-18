package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The admission dwell is sized for what reads it, and that is now a coherence check.**
 *
 *  A Quiet apply
 * measured 9.3 s on the Thor, and two full `observeCpuWindows` dwells — one at capture, one at the
 * post-cooling admission — were 4 s of it, each eleven reads 200 ms apart.
 *
 * Eleven-at-200ms was not arbitrary. It was sized to span more than one phase of
 * a vendor oscillation running about a second, so the aggregate band would describe the oscillation
 * instead of whichever phase happened to be showing. That was load-bearing while the band **steered
 * the target**: a ceiling was refused against the live ceiling, and a target below the live floor was
 * clamped up to it.
 *
 * Both were retired on 2026-09-21. What is left reading the dwell asks only whether the instrument is
 * coherent — every planned policy present, floor and ceiling positive, floor not above ceiling — plus
 * the capture boundary's surviving ceiling comparison. None of that improves with more samples, and
 * the admitted value comes from the operating-point table, which does not change between sample one
 * and sample eleven.
 *
 * This file exists for the one way that reasoning could silently stop being true.
 */
class CpuDwellSizingTest {

    /**
     * **The guard.** [TuningPlanner.CPU_SETTLED_FLOOR_STEERS_DERIVATION] is the switch that would put
     * the live floor back in charge of deriving operating points. Re-arming it while the dwell is
     * three samples long would steer derivation from a band that samples one phase of a square wave —
     * which is precisely the coin toss that retired the clamp: on the Thor, two runs from a
     * byte-identical APK journalled `{0=2016000, 3=2707200}` and `{0=1113600, 3=1651200}`.
     *
     * The two constants are in different files and neither mentions the other in code, so nothing but
     * this test connects them. Flipping the flag fails here rather than shipping a silent regression.
     */
    @Test
    fun `steering derivation from the floor requires a dwell that can characterise it`() {
        if (TuningPlanner.CPU_SETTLED_FLOOR_STEERS_DERIVATION) {
            assertTrue(
                "CPU_SETTLED_FLOOR_STEERS_DERIVATION is armed, so the admission dwell must be long " +
                    "enough to describe the oscillation it would be steering from: raise " +
                    "SAMPLE_COUNT to at least CHARACTERISING_SAMPLE_COUNT " +
                    "(${CpuWindowAdmissionPolicy.CHARACTERISING_SAMPLE_COUNT}) and restore the 200 ms " +
                    "interval before re-arming it",
                CpuWindowAdmissionPolicy.SAMPLE_COUNT >=
                    CpuWindowAdmissionPolicy.CHARACTERISING_SAMPLE_COUNT,
            )
        }
    }

    /**
     * The dwell still has to be a *bounded observation* rather than a single read: [afterDwell]
     * refuses on `samples.size != SAMPLE_COUNT`, and the shape the fixtures use — mostly resting with
     * a burst at the end — needs at least three slots to express. Two would make
     * `List(SAMPLE_COUNT - 2)` empty and quietly change what those tests exercise.
     */
    @Test
    fun `the dwell stays a bounded observation, not a single read`() {
        assertTrue(
            "a dwell below three samples cannot express resting-plus-burst, which is the shape the " +
                "Thor presents and the fixtures encode",
            CpuWindowAdmissionPolicy.SAMPLE_COUNT >= 3,
        )
    }

    /**
     * What the cut actually buys, stated as a fact rather than a comment: the sample count the
     * admission dwell runs at. The interval is private to `HardwareProbe`, so this pins the half that
     * is observable — together they are 150 ms per dwell, down from 2 s.
     */
    @Test
    fun `the dwell is three samples, not eleven`() {
        assertEquals(3, CpuWindowAdmissionPolicy.SAMPLE_COUNT)
        assertEquals(11, CpuWindowAdmissionPolicy.CHARACTERISING_SAMPLE_COUNT)
        assertTrue(
            "the whole point of the cut",
            CpuWindowAdmissionPolicy.SAMPLE_COUNT <
                CpuWindowAdmissionPolicy.CHARACTERISING_SAMPLE_COUNT,
        )
    }
}
