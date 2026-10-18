package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sticky apply (2026-10-01): a shared QoS node left held by a session that never restored it must be
 * reclaimed (unpinned + 644, read back) before the transport is chosen, and a failed reclaim must be a
 * refusal with a reason -- never a silent demotion to the vendor-reasserted `scaling_max_freq`.
 */
class SharedQosNodeReclaimerTest {
    private val path = SHARED_CPU_QOS_CEILING_PATH
    private val unpinned = UNPINNED_CPU_CEILING

    // The Nova's topology: three policies over eight CPUs.
    private val policies = listOf(
        CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2), listOf(1_017_600, 2_016_000), 307_200, 2_016_000, 0),
        CpuPolicy(3, "/sys/devices/system/cpu/cpufreq/policy3", listOf(3, 4, 5, 6), listOf(1_286_400, 2_803_200), 307_200, 2_803_200, 0),
        CpuPolicy(7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7), listOf(1_478_400, 3_187_200), 307_200, 3_187_200, 0),
    )

    /** A sysfs node that obeys `chmod` and `echo cpu:value > path` -- and can be told to ignore them. */
    private class FakeNode(
        var values: MutableMap<Int, Int>,
        var mode: String,
    ) {
        val commands = mutableListOf<String>()
        var failCommandContaining: String? = null
        var ignoreWrites = false
        var raw: String? = null

        fun read(): String? = raw ?: values.toSortedMap().entries.joinToString(" ") { "${it.key}:${it.value}" }

        fun execute(command: String): CheckedCommandResult {
            commands += command
            failCommandContaining?.let { if (command.contains(it)) return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "injected") }
            Regex("chmod ([0-7]{3}) .*").matchEntire(command)?.let { mode = it.groupValues[1] }
            Regex("echo ([0-9]+):([0-9]+) > .*").matchEntire(command)?.let {
                if (!ignoreWrites) values[it.groupValues[1].toInt()] = it.groupValues[2].toInt()
            }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
        }
    }

    private fun pinnedNode(mode: String) = FakeNode(
        mutableMapOf(
            0 to 1_017_600, 1 to 1_017_600, 2 to 1_017_600,
            3 to 1_286_400, 4 to 1_286_400, 5 to 1_286_400, 6 to 1_286_400,
            7 to 1_478_400,
        ),
        mode,
    )

    private fun reclaimer(
        node: FakeNode,
        owner: String? = null,
        log: MutableList<String> = mutableListOf(),
        presence: NodePresence = NodePresence.PRESENT,
    ) = SharedQosNodeReclaimer(
        nodes = { node.read() },
        modeOf = { node.mode },
        presenceOf = { presence },
        commands = { node.execute(it) },
        ownerOrNull = { owner },
        log = { log += it },
    )

    @Test
    fun `a held 444 pinned node nobody owns is unpinned and 644 again, so SHARED_QOS can be selected`() {
        val node = pinnedNode(HELD_CPU_QOS_MODE)
        val log = mutableListOf<String>()
        val outcome = reclaimer(node, log = log).reclaimIfOrphaned(policies)

        assertTrue(outcome.toString(), outcome is QosReclaimOutcome.Reclaimed)
        assertEquals(UNHELD_CPU_QOS_MODE, node.mode)
        assertTrue(node.values.values.all { it == unpinned })
        // The reclaimed node is exactly what the transport selector requires.
        assertEquals(path, CpuCeilingTransport.selectedPath(policies, node.read()))
        assertTrue(log.single().startsWith("QOS_RECLAIM reclaimed"))
    }

    @Test
    fun `the reclaim surface is fixed -- one path, one value, chmod 666 first and 644 last`() {
        val node = pinnedNode(HELD_CPU_QOS_MODE)
        reclaimer(node).reclaimIfOrphaned(policies)

        assertEquals("chmod 666 $path", node.commands.first())
        assertEquals("chmod $UNHELD_CPU_QOS_MODE $path", node.commands.last())
        val writes = node.commands.drop(1).dropLast(1)
        assertEquals((0..7).map { "echo $it:$unpinned > $path" }, writes)
    }

    @Test
    fun `a pinned 644 node is the vendor's own cap -- foreign-pinned, nothing touched, transport proceeds as before`() {
        val node = pinnedNode(UNHELD_CPU_QOS_MODE)
        val log = mutableListOf<String>()
        val outcome = reclaimer(node, log = log).reclaimIfOrphaned(policies)

        assertTrue(outcome.toString(), outcome is QosReclaimOutcome.ForeignPinned)
        assertTrue(node.commands.isEmpty())
        assertEquals(UNHELD_CPU_QOS_MODE, node.mode)
        assertEquals(1_017_600, node.values[0])
        assertTrue(log.single().startsWith("QOS_RECLAIM skipped foreign-pinned"))
    }

    @Test
    fun `a split cluster -- the incoherent map that silently demoted the transport -- is reclaimed`() {
        val node = pinnedNode(HELD_CPU_QOS_MODE)
        node.values[4] = unpinned
        assertNull(CpuCeilingTransport.selectedPath(policies, node.read()))

        assertTrue(reclaimer(node).reclaimIfOrphaned(policies) is QosReclaimOutcome.Reclaimed)
        assertEquals(path, CpuCeilingTransport.selectedPath(policies, node.read()))
    }

    @Test
    fun `a node a live session or journal owns is never touched`() {
        val node = pinnedNode(HELD_CPU_QOS_MODE)
        val outcome = reclaimer(node, owner = "ACTIVE").reclaimIfOrphaned(policies)

        assertEquals(QosReclaimOutcome.Owned("ACTIVE"), outcome)
        assertTrue(node.commands.isEmpty())
        assertEquals(HELD_CPU_QOS_MODE, node.mode)
    }

    @Test
    fun `a failed mode readback never reads as held, so nothing is overwritten`() {
        val node = pinnedNode(HELD_CPU_QOS_MODE)
        val unreadableMode = SharedQosNodeReclaimer(
            nodes = { node.read() },
            modeOf = { null },
            presenceOf = { NodePresence.PRESENT },
            commands = { node.execute(it) },
            ownerOrNull = { null },
        )
        assertTrue(unreadableMode.reclaimIfOrphaned(policies) is QosReclaimOutcome.ForeignPinned)
        assertTrue(node.commands.isEmpty())
    }

    @Test
    fun `a clean unpinned 644 node and a missing node write nothing`() {
        val clean = FakeNode((0..7).associateWith { unpinned }.toMutableMap(), UNHELD_CPU_QOS_MODE)
        assertEquals(QosReclaimOutcome.NotHeld, reclaimer(clean).reclaimIfOrphaned(policies))
        assertTrue(clean.commands.isEmpty())

        // Absent is a POSITIVE answer: the existence probe says there is no such node.
        val absent = FakeNode(mutableMapOf(), UNHELD_CPU_QOS_MODE).also { it.raw = "" }
        assertEquals(QosReclaimOutcome.NodeAbsent, reclaimer(absent, presence = NodePresence.ABSENT).reclaimIfOrphaned(policies))
        assertTrue(absent.commands.isEmpty())
    }

    @Test
    fun `a read that returned nothing is never taken for an absent node unless the probe says no such file`() {
        // A protected-node read that timed out or failed reaches the reclaimer as a null; so does a blank row.
        listOf<String?>(null, "", "  ").forEach { unreadable ->
            listOf(NodePresence.PRESENT, NodePresence.UNKNOWN).forEach { presence ->
                val node = pinnedNode(HELD_CPU_QOS_MODE).also { it.raw = unreadable ?: "" }
                val log = mutableListOf<String>()
                val outcome = SharedQosNodeReclaimer(
                    nodes = { unreadable },
                    modeOf = { node.mode },
                    presenceOf = { presence },
                    commands = { node.execute(it) },
                    ownerOrNull = { null },
                    log = { log += it },
                ).reclaimIfOrphaned(policies)

                assertTrue("$presence/$unreadable -> $outcome", outcome is QosReclaimOutcome.NodeUnreadable)
                assertTrue("an unreadable node is never written", node.commands.isEmpty())
                assertTrue(log.single().startsWith("QOS_RECLAIM unreadable"))
            }
        }
        val absent = SharedQosNodeReclaimer(
            nodes = { null },
            modeOf = { null },
            presenceOf = { NodePresence.ABSENT },
            commands = { throw AssertionError("an absent node is never written") },
            ownerOrNull = { null },
        )
        assertEquals(QosReclaimOutcome.NodeAbsent, absent.reclaimIfOrphaned(policies))
    }

    @Test
    fun `a node that reads normally never consults the existence probe`() {
        val node = FakeNode((0..7).associateWith { unpinned }.toMutableMap(), UNHELD_CPU_QOS_MODE)
        val reclaimer = SharedQosNodeReclaimer(
            nodes = { node.read() },
            modeOf = { node.mode },
            presenceOf = { throw AssertionError("a readable node needs no existence probe") },
            commands = { node.execute(it) },
            ownerOrNull = { null },
        )
        assertEquals(QosReclaimOutcome.NotHeld, reclaimer.reclaimIfOrphaned(policies))
    }

    @Test
    fun `fail closed -- a node that ignores the write is Unreclaimable with the readback named`() {
        val node = pinnedNode(HELD_CPU_QOS_MODE).also { it.ignoreWrites = true }
        val log = mutableListOf<String>()
        val outcome = reclaimer(node, log = log).reclaimIfOrphaned(policies)

        assertTrue(outcome.toString(), outcome is QosReclaimOutcome.Unreclaimable)
        assertTrue((outcome as QosReclaimOutcome.Unreclaimable).reason.startsWith("readback cpu 0=1017600"))
        assertTrue(log.single().startsWith("QOS_RECLAIM failed"))
    }

    @Test
    fun `fail closed -- a failed write is Unreclaimable and the closing chmod is still attempted`() {
        val node = pinnedNode(HELD_CPU_QOS_MODE).also { it.failCommandContaining = "echo 3:" }
        val outcome = reclaimer(node).reclaimIfOrphaned(policies)

        assertTrue(outcome.toString(), outcome is QosReclaimOutcome.Unreclaimable)
        assertTrue((outcome as QosReclaimOutcome.Unreclaimable).reason.startsWith("write-cpu-3 FAILED"))
        assertEquals("chmod $UNHELD_CPU_QOS_MODE $path", node.commands.last())
    }

    @Test
    fun `fail closed -- a mode that does not read back 644 is Unreclaimable`() {
        val node = pinnedNode(HELD_CPU_QOS_MODE).also { it.failCommandContaining = "chmod 644" }
        val outcome = reclaimer(node).reclaimIfOrphaned(policies)

        assertTrue(outcome.toString(), outcome is QosReclaimOutcome.Unreclaimable)
        assertTrue((outcome as QosReclaimOutcome.Unreclaimable).reason.contains("restore-mode FAILED"))
    }

    @Test
    fun `transport decision names the exact reason for every demotion`() {
        val coherent = (0..7).joinToString(" ") { "$it:$unpinned" }
        assertEquals(CpuTransportDecision(path, "OK"), CpuCeilingTransport.decide(policies, coherent))

        assertEquals("NODE_MISSING", CpuCeilingTransport.decide(policies, null, nodePresent = false).reason)
        assertEquals("NODE_UNREADABLE", CpuCeilingTransport.decide(policies, "", nodePresent = true).reason)
        assertEquals("NODE_MISSING_OR_UNREADABLE", CpuCeilingTransport.decide(policies, null).reason)
        assertEquals("NODE_UNPARSEABLE", CpuCeilingTransport.decide(policies, "0-7:$unpinned").reason)
        assertEquals("NO_CPU_POLICIES", CpuCeilingTransport.decide(emptyList(), coherent).reason)
        assertTrue(
            CpuCeilingTransport.decide(policies, (0..6).joinToString(" ") { "$it:$unpinned" })
                .reason.startsWith("INCOHERENT_MAP_CPU_SET"),
        )
        val split = (0..7).joinToString(" ") { "$it:${if (it == 5) 1_286_400 else unpinned}" }
        val decision = CpuCeilingTransport.decide(policies, split)
        assertNull(decision.path)
        assertTrue(decision.reason, decision.reason.startsWith("INCOHERENT_MAP_POLICY_SPLIT policy=3"))
    }

    @Test
    fun `the per-session transport line names the choice, the reason, and the reclaim`() {
        val coherent = (0..7).joinToString(" ") { "$it:$unpinned" }
        assertEquals(
            "CPU_TRANSPORT chosen=SHARED_QOS reason=OK reclaim=RECLAIMED x",
            CpuCeilingTransport.describeChoice(CpuCeilingTransport.decide(policies, coherent), "RECLAIMED x"),
        )
        assertEquals(
            "CPU_TRANSPORT chosen=POLICY_SCALING_MAX reason=NODE_MISSING",
            CpuCeilingTransport.describeChoice(CpuCeilingTransport.decide(policies, null, nodePresent = false)),
        )
    }

    @Test
    fun `selectedPath is still exactly decide's path`() {
        val coherent = (0..7).joinToString(" ") { "$it:$unpinned" }
        listOf(coherent, null, "", "garbage", "0:1 1:1").forEach { raw ->
            assertEquals(CpuCeilingTransport.decide(policies, raw).path, CpuCeilingTransport.selectedPath(policies, raw))
        }
    }
}
