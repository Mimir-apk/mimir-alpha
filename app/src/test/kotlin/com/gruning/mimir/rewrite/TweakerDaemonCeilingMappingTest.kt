package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_CEILING_AXIS_CPU
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_CEILING_AXIS_GPU
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_DISCOVERED_ABSENT_LEVEL
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeilingRead
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeilingRecord
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeilingWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daemon's vocabulary, translated into the tuner's representation.
 *
 * **Two dialects for one row is how a record and a write come apart.** The wire spells a QoS row
 * `"0:1804800 7:1843200"` and absence `-1`; `MimirTunerSnapshot` and `DaemonCeilingRecord` spell them
 * `Map<Int, Int>` and null. The translation is one file with no I/O in it, so every one of these
 * assertions is a fact about the code rather than about a device — which is the point: the reservation
 * half of the actuator needs a daemon to exercise, and this half decides what the surface will show.
 *
 * The fixtures are the AYN Thor's real numbers (captured 2026-08-31, the same set
 * `MimirTunerWriteTest` and `MimirTunerRecordTest` use), because this silicon has already falsified one
 * tidy premise about itself.
 */
class TweakerDaemonCeilingMappingTest {

    private val record = TweakerDiscoveredCeilingRecord(
        bootIdentity = "boot-a",
        cpuStockRow = "0:1804800 7:2956800",
        cpuRequestedRow = "0:1804800 7:1843200",
    )

    @Test
    fun `an axis is the kind, both ways`() {
        assertEquals(TWEAKER_CEILING_AXIS_CPU, mimirTunerCeilingAxis(MimirTunerPolicy.Kind.CPU))
        assertEquals(TWEAKER_CEILING_AXIS_GPU, mimirTunerCeilingAxis(MimirTunerPolicy.Kind.GPU))
        assertEquals(MimirTunerPolicy.Kind.CPU, mimirTunerCeilingKind(TWEAKER_CEILING_AXIS_CPU))
        assertEquals(MimirTunerPolicy.Kind.GPU, mimirTunerCeilingKind(TWEAKER_CEILING_AXIS_GPU))
    }

    /** An axis this build does not know is null, not a guess at the nearer of the two. */
    @Test
    fun `an unknown axis is not guessed at`() = assertNull(mimirTunerCeilingKind(7))

    /**
     * The row parser is shared with the write path on purpose. If this ever stops being
     * `MimirTunerWrite.parseCpuQosRow`, the record and the write can disagree about what `7:1843200`
     * means, and the disagreement is invisible until a withdrawal restores the wrong CPU.
     */
    @Test
    fun `a requested row becomes the same map the write path would build`() {
        val mapped = record.toDaemonCeilingRecord()

        assertEquals(MimirTunerWrite.parseCpuQosRow("0:1804800 7:1843200"), mapped.cpuRequested)
        assertEquals(mapOf(0 to 1_804_800, 7 to 1_843_200), mapped.cpuRequested)
        assertEquals("0:1804800 7:2956800", mapped.cpuStockRow)
    }

    /** `-1` and `""` are the wire's spellings of absence; the tuner's is null. Neither becomes a value. */
    @Test
    fun `absence crosses the boundary as absence`() {
        val empty = record.copy(
            cpuStockRow = "",
            gpuStockMinLevel = TWEAKER_DISCOVERED_ABSENT_LEVEL,
            gpuRequestedMaxLevel = TWEAKER_DISCOVERED_ABSENT_LEVEL,
        ).toDaemonCeilingRecord()

        assertNull(empty.cpuStockRow)
        assertNull(empty.gpuStockMinLevel)
        assertNull(empty.gpuRequestedMaxLevel)
    }

    /** A real GPU level of 0 is a level, and must survive a mapper that spells absence with a number. */
    @Test
    fun `power level zero is a level, not absence`() {
        val gpu = record.copy(gpuRequestedMinLevel = 0, gpuStockMinLevel = 0).toDaemonCeilingRecord()

        assertEquals(0, gpu.gpuRequestedMinLevel)
        assertEquals(0, gpu.gpuStockMinLevel)
    }

    /**
     * **The daemon's echo outranks our request.** The whole reason the write moved is that the daemon
     * re-admits every value against its own discovery — here it snapped a 1.80 GHz ask onto the
     * published 1.8432 GHz step. Reporting our own number back to the surface would hide exactly the
     * correction this route exists to apply.
     */
    @Test
    fun `an applied outcome reports what the daemon admitted`() {
        val applied = TweakerDiscoveredCeilingWrite.Applied(
            axis = TWEAKER_CEILING_AXIS_CPU,
            policyId = 7,
            requestedKhz = 1_800_000,
            admittedKhz = 1_843_200,
            inForceKhz = 1_843_200,
            record = record,
        ).toDaemonCeilingOutcome(askedKind = MimirTunerPolicy.Kind.CPU, askedPolicyId = 7, askedKhz = 1_800_000)
            as DaemonCeilingOutcome.Applied

        assertEquals(MimirTunerPolicy.Kind.CPU, applied.kind)
        assertEquals(7, applied.policyId)
        assertEquals(1_800_000, applied.requestedKhz)
        assertEquals(1_843_200, applied.admittedKhz)
        assertEquals(1_843_200, applied.inForceKhz)
        assertEquals(mapOf(0 to 1_804_800, 7 to 1_843_200), applied.record?.cpuRequested)
    }

    /**
     * **The contract promises only `admittedKhz` on an APPLIED**, so a reply that echoes nothing else is
     * the ordinary case rather than a fault, and every missing echo falls back to what this side asked
     * for. The alternative — a default — is a fabrication with a plausible shape: `policyId = -1` reads
     * as *"the GPU was capped"* on a press that asked for a CPU cluster.
     */
    @Test
    fun `an applied reply that echoes nothing reports what we asked for`() {
        val applied = TweakerDiscoveredCeilingWrite.Applied(
            axis = null,
            policyId = null,
            requestedKhz = null,
            admittedKhz = 1_843_200,
            inForceKhz = null,
            record = null,
        ).toDaemonCeilingOutcome(askedKind = MimirTunerPolicy.Kind.CPU, askedPolicyId = 7, askedKhz = 1_800_000)
            as DaemonCeilingOutcome.Applied

        assertEquals(MimirTunerPolicy.Kind.CPU, applied.kind)
        assertEquals(7, applied.policyId)
        assertEquals(1_800_000, applied.requestedKhz)
        assertEquals("the one number the daemon does promise", 1_843_200, applied.admittedKhz)
        assertNull(applied.record)
    }

    /** An echo this build cannot read falls back to what we asked for, rather than to a wrong axis. */
    @Test
    fun `an unrecognised axis echo falls back to the requested kind`() {
        val applied = TweakerDiscoveredCeilingWrite.Applied(
            axis = 42,
            policyId = GpuPolicyId,
            requestedKhz = 700_000,
            admittedKhz = 700_000,
            inForceKhz = null,
            record = null,
        ).toDaemonCeilingOutcome(askedKind = MimirTunerPolicy.Kind.GPU, askedPolicyId = GpuPolicyId, askedKhz = 700_000)
            as DaemonCeilingOutcome.Applied

        assertEquals(MimirTunerPolicy.Kind.GPU, applied.kind)
        assertNull(applied.record)
    }

    @Test
    fun `every write outcome maps to its own case, and none collapses into another`() {
        assertEquals(
            DaemonCeilingOutcome.Withdrawn(restoredCpus = 6, leftAloneCpus = 2, gpuRestored = true),
            TweakerDiscoveredCeilingWrite.Withdrawn(6, 2, true).toDaemonCeilingOutcome(),
        )
        assertEquals(
            DaemonCeilingOutcome.NothingHeld,
            TweakerDiscoveredCeilingWrite.NothingHeld.toDaemonCeilingOutcome(),
        )
        assertEquals(
            DaemonCeilingOutcome.Refused("PROFILE_ACTIVE", "balanced is active"),
            TweakerDiscoveredCeilingWrite.Refused("PROFILE_ACTIVE", "balanced is active").toDaemonCeilingOutcome(),
        )
        assertEquals(
            DaemonCeilingOutcome.Unavailable("not bound"),
            TweakerDiscoveredCeilingWrite.Unavailable("not bound").toDaemonCeilingOutcome(),
        )
    }

    /**
     * The three-way distinction, preserved to the last translation. `NotHeld` means the node is the
     * daemon's and there is nothing of ours on it; `Unavailable` means nobody asked anybody anything.
     * A cold start that confuses them widens a cap it could have adopted exactly.
     */
    @Test
    fun `a read keeps held, not-held and could-not-ask apart`() {
        assertTrue(
            TweakerDiscoveredCeilingRead.Held(record).toDaemonCeilingOutcome() is DaemonCeilingOutcome.Held,
        )
        assertEquals(
            DaemonCeilingOutcome.NotHeld,
            TweakerDiscoveredCeilingRead.NotHeld.toDaemonCeilingOutcome(),
        )
        assertEquals(
            DaemonCeilingOutcome.Unavailable("Tweaker service is not bound"),
            TweakerDiscoveredCeilingRead.Unavailable("Tweaker service is not bound").toDaemonCeilingOutcome(),
        )
    }

    /**
     * A record naming nothing is not adopted, and the emptiness test travels with it rather than being
     * re-derived by whoever receives it.
     */
    @Test
    fun `an empty record says so`() {
        val empty = TweakerDiscoveredCeilingRead.Held(
            record.copy(cpuStockRow = "", cpuRequestedRow = ""),
        ).toDaemonCeilingOutcome() as DaemonCeilingOutcome.Held
        val full = TweakerDiscoveredCeilingRead.Held(record)
            .toDaemonCeilingOutcome() as DaemonCeilingOutcome.Held

        assertTrue("a record naming no row and no level is empty", empty.record.isEmpty())
        assertTrue("a record with both CPU rows is not", !full.record.isEmpty())
    }

    /** Every outcome reaches the log with its own shape; none of them prints as another's. */
    @Test
    fun `each outcome logs a line that names it`() {
        val lines = listOf(
            DaemonCeilingOutcome.Applied(MimirTunerPolicy.Kind.CPU, 7, 1_800_000, 1_843_200, 1_843_200, null),
            DaemonCeilingOutcome.Withdrawn(6, 2, false),
            DaemonCeilingOutcome.NothingHeld,
            DaemonCeilingOutcome.Held(record.toDaemonCeilingRecord()),
            DaemonCeilingOutcome.NotHeld,
            DaemonCeilingOutcome.Refused("PROFILE_ACTIVE", ""),
            DaemonCeilingOutcome.Unavailable("not bound"),
        ).map { daemonCeilingOutcomeLogLine("apply", it) }

        assertEquals("every outcome logs a distinct line", lines.size, lines.toSet().size)
        lines.forEach { assertTrue("'$it' must name the call", it.startsWith("route apply outcome=")) }
    }
}
