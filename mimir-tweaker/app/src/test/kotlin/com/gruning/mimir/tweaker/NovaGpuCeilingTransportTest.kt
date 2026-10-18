package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The GPU's answer to [CpuCeilingTransport], and the reason it exists.
 *
 * Device trace, Nova 2026-08-25, Tweaker `568944db…`, all three axes polled together at 5 Hz:
 * the CPU ceiling stayed PINNED while `max_pwrlevel` fell from 3 back to 0 on its own, and Mimir's
 * own restore arrived 1.5 s LATER as a consequence. Nothing of ours wrote it — a Mimir restore moves
 * every axis at once. `max_pwrlevel` is to the GPU what `scaling_max_freq` was to the CPU.
 */
class NovaGpuCeilingTransportTest {

    /** Nova capture, verbatim: kHz ascending, as the probe normalises them. 9 rows, 9 power levels. */
    private val nova = listOf(124_800, 220_000, 295_000, 348_000, 401_000, 475_000, 550_000, 615_000, 680_000)

    @Test
    fun `power levels are inverted and level zero is the fastest row`() {
        assertEquals(680_000_000L, GpuCeilingTransport.targetHzFor(nova, 0))
        assertEquals(124_800_000L, GpuCeilingTransport.targetHzFor(nova, 8))
        // The level the quiet profile actually asks for on this device.
        assertEquals(475_000_000L, GpuCeilingTransport.targetHzFor(nova, 3))
    }

    /**
     * The failure this inversion invites, stated so it cannot creep back: reading the table the
     * OTHER way round would cap a "quiet" GPU at 348 MHz instead of 475 — or, at level 0, pin a
     * profile that asked for full speed down to 124.8 MHz. Neither throws. Neither logs.
     */
    @Test
    fun `reading the table forwards would be a different and silent answer`() {
        assertEquals(475_000_000L, GpuCeilingTransport.targetHzFor(nova, 3))
        assertEquals(
            "the naive forward read -- kept here as the thing this must never equal",
            348_000_000L,
            nova[3].toLong() * 1_000L,
        )
    }

    /**
     * REMOVED, not weakened: a `num_pwrlevels` cross-check used to live here.
     *
     * The decision is needed at four sites — capture, emission, apply-time verify, and the
     * expected-topology the capture is checked against — and only two of them can read that node.
     * An input one site has and another does not is a divergence waiting to happen, and a
     * divergence here does not read as a wrong node: it reads as CAPTURE_INCOMPLETE, with the
     * capture blamed for a topology someone else expected. That is exactly how it behaved on the
     * measured device before the decision moved onto `HardwareCapabilities.gpuCeilingPath`, discovered once.
     *
     * What survives is the substantive check: the table must be non-trivial and the level must
     * address a real row.
     */
    @Test
    fun `the table alone decides, because it is the only input every site shares`() {
        assertEquals(475_000_000L, GpuCeilingTransport.targetHzFor(nova, 3))
        assertNull("a chassis with no usable table cannot be capped this way", GpuCeilingTransport.targetHzFor(listOf(400_000), 0))
    }

    @Test
    fun `an absent devfreq node falls back to max_pwrlevel rather than inventing one`() {
        assertNull(GpuCeilingTransport.selectedPath(nova, 3, devfreqMaxExists = false))
        assertEquals(
            HardwareProbe.GPU_DEVFREQ_MAX_FREQ,
            GpuCeilingTransport.selectedPath(nova, 3, devfreqMaxExists = true),
        )
    }

    @Test
    fun `a level outside the live table is refused, in both directions`() {
        assertNull(GpuCeilingTransport.targetHzFor(nova, 9))
        assertNull(GpuCeilingTransport.targetHzFor(nova, -1))
        assertNull("a chassis with no usable table cannot be capped this way", GpuCeilingTransport.targetHzFor(listOf(400_000), 0))
    }

    /** A null count is "no cross-check available", not a failure -- the table alone still answers. */
    @Test
    fun `an unreadable level count still permits the transport`() {
        assertEquals(475_000_000L, GpuCeilingTransport.targetHzFor(nova, 3))
        assertEquals(
            HardwareProbe.GPU_DEVFREQ_MAX_FREQ,
            GpuCeilingTransport.selectedPath(nova, 3, devfreqMaxExists = true),
        )
    }
}
