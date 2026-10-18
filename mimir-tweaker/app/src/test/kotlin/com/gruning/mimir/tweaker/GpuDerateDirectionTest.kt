package com.gruning.mimir.tweaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE law, over two units that count in opposite directions: **a derate may never move toward faster.**
 *
 *   `gpu:max_pwrlevel`      an index — LARGER is slower
 *   `gpu:devfreq_max_freq`  a frequency in HERTZ — SMALLER is slower
 *
 * The comparator in [AppliedMutationProjection] is the only place that sense is written down, and a
 * sign living inside a comparison is exactly the class of defect this tree spent 2026-08-25 removing
 * from the frontend. So it is pinned as a TABLE over both ids, with real Nova values, and each
 * direction is differential-checked by flipping its comparator.
 */
class GpuDerateDirectionTest {

    private fun mutation(id: String, value: Int) = PersistedAppliedMutation(
        logicalId = id,
        axis = TuningAxis.GPU,
        canonicalPath = "/sys/class/kgsl/kgsl-3d0/x",
        kind = LogicalMutationKind.SYSFS_WRITE,
        value = value,
        expectPresent = true,
    )

    private fun amend(id: String, from: Int, to: Int) =
        AppliedMutationProjection.isMonotonicallySlowerAmendment(
            listOf(mutation(id, from)),
            listOf(mutation(id, to)),
        )

    private val pwrlevel = HardwareSnapshot.GPU_MAX_LOGICAL_ID
    private val devfreq = HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID

    @Test
    fun `max_pwrlevel derates upward because a larger index is slower`() {
        assertTrue("3 -> 4 is slower", amend(pwrlevel, 3, 4))
        assertTrue("unchanged is allowed", amend(pwrlevel, 3, 3))
        assertFalse("3 -> 2 is FASTER and must be refused", amend(pwrlevel, 3, 2))
    }

    @Test
    fun `devfreq derates downward because a smaller frequency is slower`() {
        // The measured Nova table, in the node's own unit -- HERTZ. 475 MHz is power level 3.
        assertTrue("475 MHz -> 401 MHz is slower", amend(devfreq, 475_000_000, 401_000_000))
        assertTrue("unchanged is allowed", amend(devfreq, 475_000_000, 475_000_000))
        assertFalse("475 MHz -> 550 MHz is FASTER and must be refused", amend(devfreq, 475_000_000, 550_000_000))
    }

    /**
     * The two ids must not be able to borrow each other's sense. Written as the cross-product so that
     * copying one arm of the `when` onto the other id fails here rather than on the reference device, where the
     * symptom would be a "derate" that raised the ceiling.
     */
    @Test
    fun `neither id accepts the other's direction`() {
        assertFalse("a pwrlevel that fell is faster", amend(pwrlevel, 4, 3))
        assertFalse("a devfreq that rose is faster", amend(devfreq, 401_000_000, 475_000_000))
    }

    /** Any other axis or id may not move at all -- the fan above all. */
    @Test
    fun `an unrelated node may not move in either direction`() {
        val fan = PersistedAppliedMutation(
            logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
            axis = TuningAxis.FAN,
            canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
            kind = LogicalMutationKind.SETTINGS_PUT,
            value = 2,
            expectPresent = true,
        )
        assertTrue(AppliedMutationProjection.isMonotonicallySlowerAmendment(listOf(fan), listOf(fan)))
        assertFalse(
            AppliedMutationProjection.isMonotonicallySlowerAmendment(
                listOf(fan),
                listOf(fan.copy(value = 3)),
            ),
        )
    }
}
