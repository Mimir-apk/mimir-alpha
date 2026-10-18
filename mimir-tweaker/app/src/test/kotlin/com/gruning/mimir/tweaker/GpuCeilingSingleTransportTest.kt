package com.gruning.mimir.tweaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE ceiling node per apply — the property that keeps every seam downstream answering one question.
 *
 * The GPU can be capped two ways on this chassis family and they are ALTERNATIVES, never a pair. A
 * projection or envelope naming both would mean capture and emission disagreed about which transport
 * the device uses, and neither could then be trusted as the thing to watch.
 */
class GpuCeilingSingleTransportTest {

    private val nova = listOf(124_800, 220_000, 295_000, 348_000, 401_000, 475_000, 550_000, 615_000, 680_000)

    @Test
    fun `the two ceiling ids are alternatives and both are known as ceilings`() {
        assertTrue(HardwareSnapshot.GPU_MAX_LOGICAL_ID in HardwareSnapshot.GPU_CEILING_LOGICAL_IDS)
        assertTrue(HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID in HardwareSnapshot.GPU_CEILING_LOGICAL_IDS)
        assertFalse(
            "the FLOOR is not a ceiling; widening it must never satisfy the ceiling requirement",
            HardwareSnapshot.GPU_MIN_LOGICAL_ID in HardwareSnapshot.GPU_CEILING_LOGICAL_IDS,
        )
        assertTrue(HardwareSnapshot.GPU_CEILING_LOGICAL_IDS.size == 2)
    }

    /**
     * The selection is a PURE function of the same four inputs at both call sites (capture in
     * HardwareProbe, emission in PServerHardwareCommandPort). If it were not, the two could own
     * different nodes and the stock value restored on teardown would be for a node nobody wrote —
     * the single failure this design exists to make unrepresentable.
     */
    @Test
    fun `the same inputs always select the same node`() {
        val a = GpuCeilingTransport.selectedPath(nova, 3, devfreqMaxExists = true)
        val b = GpuCeilingTransport.selectedPath(nova, 3, devfreqMaxExists = true)
        assertTrue(a == b && a == HardwareProbe.GPU_DEVFREQ_MAX_FREQ)

        val c = GpuCeilingTransport.selectedPath(nova, 3, devfreqMaxExists = false)
        val d = GpuCeilingTransport.selectedPath(nova, 3, devfreqMaxExists = false)
        assertTrue("a chassis without the node falls back, identically, every time", c == d && c == null)
    }

    /**
     * The Odin keeps `max_pwrlevel`. Its capture, emission, journal projection and watch are
     * byte-identical to before this transport existed — the fix must be invisible on the device it
     * was not written for.
     */
    @Test
    fun `a chassis with no devfreq node is unchanged`() {
        assertNull(GpuCeilingTransport.selectedPath(nova, 3, devfreqMaxExists = false))
        assertNull(GpuCeilingTransport.selectedPath(emptyList(), 3, devfreqMaxExists = true))
    }

    /** A contract built without the devfreq transport must still watch the pwrlevel it wrote. */
    @Test
    fun `an absent devfreq ceiling leaves the pwrlevel contract in charge`() {
        val contract = AppliedTargetContract(
            deviceProfile = "ayn-odin3-cq8725s-pserver",
            bootId = "boot",
            cpuQosCeilingKhz = sortedMapOf(),
            cpuQosTransport = CpuQosTransport.SHARED_QOS,
            gpuMinPowerLevel = null,
            gpuMaxPowerLevel = 3,
            fanMode = null,
        )
        assertNull("defaulting to null is what keeps every existing contract a pwrlevel contract", contract.gpuDevfreqMaxHz)
    }

    /**
     * **What is NOT pinned here, deliberately recorded.**
     *
     * The journal's "exactly one ceiling" rule and MGDE's matching envelope rule are defence in
     * depth against a state the normal path cannot produce: capture and emission disagreeing about
     * the transport. I tried twice to pin them at record level and both attempts were VACUOUS — the
     * constructed record failed validation earlier, on apply-order consistency, so the assertion
     * passed with the rule deleted. A pin that cannot fail is worse than no pin, so they are left
     * unpinned and named instead.
     *
     * What IS pinned is the layer beneath them, which is where a real divergence would start: the
     * selection is pure and deterministic (above), the two ids are known alternatives (above), and
     * each carries its own derate direction ([GpuDerateDirectionTest]).
     */
    @Test
    fun `the ceiling set is the single source of truth for what counts as a ceiling`() {
        assertTrue(
            "both journal and MGDE count ceilings through this set; a third transport added to one " +
                "and not the other is the divergence those rules exist to catch",
            HardwareSnapshot.GPU_CEILING_LOGICAL_IDS ==
                setOf(HardwareSnapshot.GPU_MAX_LOGICAL_ID, HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID),
        )
    }
}
