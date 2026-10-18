package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the transport-precedence check FAILS CLOSED. It used to answer "apply continues" whenever
 * it could not run (the probe threw, the reclaim threw, the worker never answered), so an apply went on to select its
 * CPU transport from a node nobody had read. If the privileged route cannot be confirmed the apply is refused, nothing
 * is written, the device stays as it was, and the refusal and its reason are logged.
 */
class CpuTransportPrecedenceTest {
    private val log = mutableListOf<String>()

    private fun refusal(evaluate: () -> QosReclaimOutcome) =
        CpuTransportPrecedence.refusal(evaluate = evaluate, log = { log += it })

    @Test
    fun `a check that throws refuses the apply and logs why`() {
        val refused = refusal { throw IllegalStateException("probe failed") }

        assertNotNull(refused)
        assertEquals(CpuTransportPrecedence.UNCONFIRMED_CODE, refused!!.code)
        assertTrue(refused.message.contains("nothing was written"))
        assertEquals(1, log.size)
        assertTrue(log.single(), log.single().contains("refused"))
        assertTrue(log.single(), log.single().contains("IllegalStateException: probe failed"))
    }

    @Test
    fun `a check the busy worker could not run refuses with the busy code`() {
        val refused = refusal {
            throw TransactionWorkerBusyException("SERVICE_BUSY: worker stuck", started = false)
        }

        assertEquals(SERVICE_BUSY_CODE, refused!!.code)
        assertTrue(log.single().contains("refused"))
    }

    @Test
    fun `a held unowned node that could not be reclaimed still refuses`() {
        val refused = refusal { QosReclaimOutcome.Unreclaimable("readback mode=666") }

        assertEquals(CpuTransportPrecedence.UNRECLAIMABLE_CODE, refused!!.code)
        assertTrue(log.single().contains("readback mode=666"))
    }

    @Test
    fun `only a completed check that found nothing wrong lets the apply proceed`() {
        listOf(
            QosReclaimOutcome.NodeAbsent,
            QosReclaimOutcome.NotHeld,
            QosReclaimOutcome.ForeignPinned("mode=644"),
            QosReclaimOutcome.Owned("session"),
            QosReclaimOutcome.Reclaimed("mode=666"),
        ).forEach { outcome ->
            assertNull(outcome.wire, refusal { outcome })
        }
        assertTrue("proceeding needs no refusal log", log.isEmpty())
    }

    // ---- Absent is a positive signal; an unreadable protected node refuses -----------------------------

    // The Nova's topology: three policies over eight CPUs.
    private val policies = listOf(
        CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2), listOf(1_017_600, 2_016_000), 307_200, 2_016_000, 0),
        CpuPolicy(3, "/sys/devices/system/cpu/cpufreq/policy3", listOf(3, 4, 5, 6), listOf(1_286_400, 2_803_200), 307_200, 2_803_200, 0),
        CpuPolicy(7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7), listOf(1_478_400, 3_187_200), 307_200, 3_187_200, 0),
    )
    private val unpinnedRow = (0..7).joinToString(" ") { "$it:$UNPINNED_CPU_CEILING" }
    private val heldRow = (0..7).joinToString(" ") { "$it:1017600" }

    private fun reclaimer(
        read: () -> String?,
        presence: NodePresence,
        mode: String? = UNHELD_CPU_QOS_MODE,
        owner: String? = null,
        written: MutableList<String> = mutableListOf(),
    ) = SharedQosNodeReclaimer(
        nodes = { read() },
        modeOf = { mode },
        presenceOf = { presence },
        commands = { command ->
            written += command
            CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
        },
        ownerOrNull = { owner },
    )

    @Test
    fun `a chassis without the node resolves Absent and the apply proceeds`() {
        val written = mutableListOf<String>()
        val outcome = reclaimer({ null }, NodePresence.ABSENT, written = written).reclaimIfOrphaned(policies)

        assertEquals(QosReclaimOutcome.NodeAbsent, outcome)
        assertNull(refusal { outcome })
        assertNull(
            "the transport decision agrees: NODE_MISSING is not an unreadable node",
            refusal { CpuTransportPrecedence.confirm(outcome, CpuCeilingTransport.decide(policies, null, nodePresent = false)) },
        )
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a protected-node read that times out refuses with CPU_TRANSPORT_UNCONFIRMED`() {
        // The real chain: a PServer whose service never answers inside the read deadline, the protected-node helper,
        // and the reclaimer. The helper turns the timeout into a null -- which used to read as NodeAbsent.
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val pServer = PServerClient(
                transport = PServerBinderTransport { _, _ ->
                    release.await()
                    PServerTransactResult(accepted = true, output = unpinnedRow)
                },
                readDeadlineMs = 50,
            )
            val access = PServerProtectedNodeAccess(pServer)
            assertNull("the timed-out read is just a null at this seam", access.read(SHARED_CPU_QOS_CEILING_PATH))

            val written = mutableListOf<String>()
            val refused = refusal {
                reclaimer({ access.read(SHARED_CPU_QOS_CEILING_PATH) }, NodePresence.PRESENT, written = written)
                    .reclaimIfOrphaned(policies)
            }

            assertNotNull(refused)
            assertEquals(CpuTransportPrecedence.UNCONFIRMED_CODE, refused!!.code)
            assertTrue(refused.message.contains("nothing was written"))
            assertTrue(log.single(), log.single().contains("could not be read"))
            assertTrue(written.isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a protected-node read that fails refuses with CPU_TRANSPORT_UNCONFIRMED`() {
        val pServer = PServerClient(
            transport = PServerBinderTransport { _, _ -> PServerTransactResult(accepted = false) },
        )
        val access = PServerProtectedNodeAccess(pServer)
        assertNull(access.read(SHARED_CPU_QOS_CEILING_PATH))

        val refused = refusal {
            reclaimer({ access.read(SHARED_CPU_QOS_CEILING_PATH) }, NodePresence.PRESENT).reclaimIfOrphaned(policies)
        }

        assertEquals(CpuTransportPrecedence.UNCONFIRMED_CODE, refused!!.code)
    }

    @Test
    fun `a node whose existence cannot be established is unconfirmed, not absent`() {
        val refused = refusal { reclaimer({ null }, NodePresence.UNKNOWN).reclaimIfOrphaned(policies) }

        assertEquals(CpuTransportPrecedence.UNCONFIRMED_CODE, refused!!.code)
    }

    @Test
    fun `a transport decision of NODE_UNREADABLE refuses, it is not just logged`() {
        // The reclaim read succeeded; the second read the transport decision makes did not.
        val outcome = QosReclaimOutcome.NotHeld
        listOf(
            CpuCeilingTransport.decide(policies, "", nodePresent = true),
            CpuCeilingTransport.decide(policies, null, nodePresent = true),
            CpuCeilingTransport.decide(policies, null, nodePresent = null),
        ).forEach { decision ->
            assertTrue(decision.reason, decision.nodeUnreadable)
            val refused = refusal { CpuTransportPrecedence.confirm(outcome, decision) }
            assertEquals(decision.reason, CpuTransportPrecedence.UNCONFIRMED_CODE, refused!!.code)
        }
        log.clear()

        // None of the others is an unreadable node: they keep the outcome and so keep applying as they always did.
        listOf(
            CpuCeilingTransport.decide(policies, null, nodePresent = false),
            CpuCeilingTransport.decide(policies, unpinnedRow, nodePresent = true),
            CpuCeilingTransport.decide(policies, "0-7:$UNPINNED_CPU_CEILING", nodePresent = true),
            CpuCeilingTransport.decide(emptyList(), null, nodePresent = true),
        ).forEach { decision ->
            assertFalse(decision.reason, decision.nodeUnreadable)
            assertEquals(outcome, CpuTransportPrecedence.confirm(outcome, decision))
        }
    }

    @Test
    fun `the Nova's 644 node is NotHeld and its 444-held node is Owned, and both still apply`() {
        val written = mutableListOf<String>()
        val notHeld = reclaimer({ unpinnedRow }, NodePresence.PRESENT, mode = UNHELD_CPU_QOS_MODE, written = written)
            .reclaimIfOrphaned(policies)
        val owned = reclaimer({ heldRow }, NodePresence.PRESENT, mode = HELD_CPU_QOS_MODE, owner = "ACTIVE", written = written)
            .reclaimIfOrphaned(policies)

        assertEquals(QosReclaimOutcome.NotHeld, notHeld)
        assertEquals(QosReclaimOutcome.Owned("ACTIVE"), owned)
        assertNull(refusal { notHeld })
        assertNull(refusal { owned })
        // The readable-node decision is OK too, so confirm() changes nothing on the Nova.
        val okDecision = CpuCeilingTransport.decide(policies, unpinnedRow, nodePresent = true)
        assertEquals(notHeld, CpuTransportPrecedence.confirm(notHeld, okDecision))
        assertEquals(owned, CpuTransportPrecedence.confirm(owned, okDecision))
        assertTrue(written.isEmpty())
        assertTrue(log.isEmpty())
    }

    @Test
    fun `the service routes every apply through the fail closed check and no longer proceeds on a throw`() {
        val service = sourceText("MimirTweakerService.kt")

        assertTrue(service.contains("CpuTransportPrecedence.refusal("))
        assertFalse("the fail-open branch is gone", service.contains("apply continues"))
        assertTrue("the transport decision is folded into the verdict, not just logged", service.contains("CpuTransportPrecedence.confirm(outcome, decision)"))
        assertTrue("absence comes from the existence probe, never from a null read", service.contains("OsNodePresenceProbe.probe(HardwareProbe.CPU_QOS_MAX_PATH)"))
        assertTrue(service.contains("presenceOf = OsNodePresenceProbe"))
        assertFalse("File.exists() cannot tell ENOENT from EACCES", service.contains("systemReader.exists(HardwareProbe.CPU_QOS_MAX_PATH)"))
        assertEquals(
            "legacy apply, V4 apply and system-profile select all consult it",
            3,
            Regex("cpuTransportPrecedenceRefusal\\(\\)\\?\\.let").findAll(service).count(),
        )
    }

    private fun sourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        return File(root, name).readText()
    }
}
