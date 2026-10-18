package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fan-fence fix F2 (`build/test-captures/fanfence/ROOT_CAUSE.md`): the Guardian's hand-back reaches the
 * supervisor before the next tick can dispatch a repair into a dead generation. Pure pieces first
 * ([GuardianHeartbeatRetirePolicy], [GuardianHandbackGate]), then the supervisor's own settlement
 * of a repair that met the closed terminal fence, then the source pins for the wiring.
 */
class GuardianHandbackTest {
    // -- GuardianHeartbeatRetirePolicy -----------------------------------------------------------

    @Test
    fun `a host denial is recorded as GUARDIAN_RESTORE_REQUESTED with the gate's own token`() {
        assertEquals(
            "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE",
            GuardianHeartbeatRetirePolicy.denialCause("HEALTHY_STALE age=-779"),
        )
        assertEquals(
            "GUARDIAN_RESTORE_REQUESTED/TRUTH_MISMATCH",
            GuardianHeartbeatRetirePolicy.denialCause("TRUTH_MISMATCH"),
        )
    }

    @Test
    fun `an accepted heartbeat never retires and never reads the receipt`() {
        var receiptReads = 0
        assertNull(
            GuardianHeartbeatRetirePolicy.retireCause(GuardianHeartbeatAnswer.Accepted) {
                receiptReads++
                true
            },
        )
        assertEquals(0, receiptReads)
    }

    @Test
    fun `a received terminal receipt names the cause of a rejection`() {
        assertEquals(
            GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED,
            GuardianHeartbeatRetirePolicy.retireCause(GuardianHeartbeatAnswer.Rejected("HEARTBEAT_REJECTED")) { true },
        )
    }

    // -- GuardianDenialSequence (P1-1) -----------------------------------------------------------

    @Test
    fun `a denial retires the session before the Guardian is asked -- no repair dispatches during the restore`() {
        val gate = GuardianHandbackGate()
        val active = identity(51, "tx-51")
        val duringRestore = mutableListOf<SupervisorDecision>()
        var heartbeatStopped = false

        GuardianDenialSequence.run(
            retireUnderMonitor = {
                heartbeatStopped = true
                gate.markRestoreRequested(51, "tx-51", GuardianHeartbeatRetirePolicy.denialCause("HEALTHY_STALE age=12001"))
                true
            },
            requestRestore = {
                // Inside the synchronous binder call (24 s on gen 51): a tick landing here must
                // already see the hand-back, so its RepairFanTarget becomes the session's Restore.
                assertTrue("the heartbeat is stopped before the Guardian is asked", heartbeatStopped)
                duringRestore += gate.override(active, repair(51, "tx-51")) { false }
            },
        )

        assertEquals(
            listOf<SupervisorDecision>(SupervisorDecision.Restore(51, "tx-51", "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE")),
            duringRestore,
        )
    }

    @Test
    fun `a denial for a session that already moved on asks the Guardian for nothing`() {
        var requested = 0
        GuardianDenialSequence.run(retireUnderMonitor = { false }, requestRestore = { requested++ })
        assertEquals(0, requested)
    }

    @Test
    fun `a protocol rejection retires with the Guardian's code`() {
        assertEquals(
            "GUARDIAN_HEARTBEAT_REJECTED/HEARTBEAT_REJECTED",
            GuardianHeartbeatRetirePolicy.retireCause(GuardianHeartbeatAnswer.Rejected("HEARTBEAT_REJECTED")) { false },
        )
        assertEquals(
            "GUARDIAN_HEARTBEAT_REJECTED/null",
            GuardianHeartbeatRetirePolicy.retireCause(GuardianHeartbeatAnswer.Rejected(null)) { false },
        )
    }

    @Test
    fun `every reason the policy mints is a guardian hand-back, ordinary reasons are not`() {
        listOf(
            GuardianHeartbeatRetirePolicy.denialCause("HEALTHY_STALE age=-779"),
            GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED,
            GuardianHeartbeatRetirePolicy.TERMINAL_FENCE,
            "GUARDIAN_HEARTBEAT_REJECTED/HEARTBEAT_REJECTED",
        ).forEach { assertTrue(it, GuardianHeartbeatRetirePolicy.isGuardianHandbackReason(it)) }
        listOf("LEASE_EXPIRED", "MISSING_TELEMETRY_CPU", "TARGET_CONTRACT_DRIFT_FAN", "STARTUP_RECOVERY_RESTORE")
            .forEach { assertFalse(it, GuardianHeartbeatRetirePolicy.isGuardianHandbackReason(it)) }
    }

    // -- GuardianHandbackGate --------------------------------------------------------------------

    @Test
    fun `a pending hand-back turns the tick's RepairFanTarget into a Restore and is spent`() {
        val gate = GuardianHandbackGate()
        gate.markRestoreRequested(51, "tx-51", "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE")

        val decision = gate.override(identity(51, "tx-51"), repair(51, "tx-51")) { false }

        assertEquals(
            SupervisorDecision.Restore(51, "tx-51", "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE"),
            decision,
        )
        assertNull("the entry is consumed by the tick that acted on it", gate.pending())
        val next = repair(51, "tx-51")
        assertSame("once spent, the next tick runs its own decision", next, gate.override(identity(51, "tx-51"), next) { false })
    }

    @Test
    fun `a pending hand-back also overrides Continue, Derate and RepairCpuTarget`() {
        listOf(
            SupervisorDecision.Continue,
            SupervisorDecision.Derate(51, "tx-51", "THERMAL_CPU", rungs = 1, rung = 1),
            SupervisorDecision.RepairCpuTarget(51, "tx-51", "TARGET_CONTRACT_DRIFT_CPU"),
        ).forEach { tick ->
            val gate = GuardianHandbackGate()
            gate.markRestoreRequested(51, "tx-51", GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED)
            val decision = gate.override(identity(51, "tx-51"), tick) { false }
            assertEquals(
                "$tick must become a Restore while a hand-back is pending",
                SupervisorDecision.Restore(51, "tx-51", GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED),
                decision,
            )
        }
    }

    @Test
    fun `a hand-back for a session that is already gone is dropped, never applied to its successor`() {
        val gate = GuardianHandbackGate()
        gate.markRestoreRequested(50, "tx-50", "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE")

        val successorRepair = repair(51, "tx-51")
        assertSame(successorRepair, gate.override(identity(51, "tx-51"), successorRepair) { false })
        assertNull("the stale entry is discarded", gate.pending())
    }

    @Test
    fun `no active session clears the pending entry and changes nothing`() {
        val gate = GuardianHandbackGate()
        gate.markRestoreRequested(51, "tx-51", GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED)
        assertSame(SupervisorDecision.Continue, gate.override(null, SupervisorDecision.Continue) { true })
        assertNull(gate.pending())
    }

    @Test
    fun `a terminal receipt on disk retires the active session with nothing pending`() {
        val gate = GuardianHandbackGate()
        assertEquals(
            SupervisorDecision.Restore(37, "tx-37", GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED),
            gate.override(identity(37, "tx-37"), repair(37, "tx-37")) { true },
        )
        assertEquals(
            "a Continue tick is retired too -- the session is dead whatever the tick saw",
            SupervisorDecision.Restore(37, "tx-37", GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED),
            gate.override(identity(37, "tx-37"), SupervisorDecision.Continue) { true },
        )
    }

    @Test
    fun `with nothing pending and no receipt the tick's own decision stands`() {
        val gate = GuardianHandbackGate()
        val tick = repair(51, "tx-51")
        assertSame(tick, gate.override(identity(51, "tx-51"), tick) { false })
        assertSame("an unreadable receipt store is not a receipt", tick, gate.override(identity(51, "tx-51"), tick) { error("io") })
    }

    @Test
    fun `during a hold or a retry backoff the receipt mints nothing, a pending hand-back still settles`() {
        val gate = GuardianHandbackGate()
        val tick = SupervisorDecision.Continue
        assertSame(
            "a cross-store hold normally HAS a receipt; it is settled by ACTION_GUARDIAN_SETTLEMENT, not at 1 Hz here",
            tick,
            gate.override(identity(37, "tx-37"), tick, holdOrBackoff = true) { true },
        )
        gate.markRestoreRequested(37, "tx-37", GuardianHeartbeatRetirePolicy.denialCause("HEALTHY_STALE"))
        assertEquals(
            SupervisorDecision.Restore(37, "tx-37", "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE"),
            gate.override(identity(37, "tx-37"), tick, holdOrBackoff = true) { true },
        )
    }

    @Test
    fun `a tick that already decided Restore keeps its own reason and spends the pending entry`() {
        val gate = GuardianHandbackGate()
        gate.markRestoreRequested(51, "tx-51", GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED)
        val own = SupervisorDecision.Restore(51, "tx-51", "LEASE_EXPIRED")
        assertSame(own, gate.override(identity(51, "tx-51"), own) { true })
        assertNull(gate.pending())
    }

    // -- HardwareSessionSupervisor: a closed terminal fence settles the session ------------------

    @Test
    fun `a fan reassert that met the closed terminal fence is settled through the restorer`() {
        val env = SupervisorEnv(
            fanReassert = fenceClosed(51, "tx-51", "Fan reassert"),
        )
        env.adopt(51, "tx-51")

        val outcome = env.supervisor.execute(SupervisorDecision.RepairFanTarget(51, "tx-51", "TARGET_CONTRACT_DRIFT_FAN"))

        assertEquals(listOf(51L to "tx-51"), env.restores)
        assertEquals("RESTORED", outcome?.code)
        assertNull("the session is settled, not kept for one more repair", env.supervisor.activeSession())
    }

    @Test
    fun `a CPU repair and a derate that met the closed terminal fence settle the same way`() {
        val repairEnv = SupervisorEnv(cpuRepair = fenceClosed(51, "tx-51", "Target repair"))
        repairEnv.adopt(51, "tx-51")
        repairEnv.supervisor.execute(SupervisorDecision.RepairCpuTarget(51, "tx-51", "TARGET_CONTRACT_DRIFT_CPU"))
        assertEquals(listOf(51L to "tx-51"), repairEnv.restores)
        assertNull(repairEnv.supervisor.activeSession())

        val derateEnv = SupervisorEnv(derate = fenceClosed(51, "tx-51", "Thermal derate"))
        derateEnv.adopt(51, "tx-51")
        derateEnv.supervisor.execute(SupervisorDecision.Derate(51, "tx-51", "THERMAL_CPU", rungs = 1, rung = 1))
        assertEquals(listOf(51L to "tx-51"), derateEnv.restores)
        assertNull(derateEnv.supervisor.activeSession())
    }

    @Test
    fun `ordinary failed reassert never restores the session`() {
        val env = SupervisorEnv(
            fanReassert = TransactionResult(false, false, false, "FAN_REASSERT_FAILED", "write failed", transactionId = "tx-51", generation = 51),
        )
        env.adopt(51, "tx-51")
        env.supervisor.execute(SupervisorDecision.RepairFanTarget(51, "tx-51", "TARGET_CONTRACT_DRIFT_FAN"))
        assertTrue(env.restores.isEmpty())
        assertEquals(51L, env.supervisor.activeSession()?.generation)
    }

    @Test
    fun `a terminal fence for a generation that is no longer the session touches nothing`() {
        val env = SupervisorEnv(fanReassert = fenceClosed(50, "tx-50", "Fan reassert"))
        env.adopt(51, "tx-51")
        env.supervisor.execute(SupervisorDecision.RepairFanTarget(50, "tx-50", "TARGET_CONTRACT_DRIFT_FAN"))
        assertTrue(env.restores.isEmpty())
        assertEquals(51L, env.supervisor.activeSession()?.generation)
    }

    // -- Source pins for the wiring -------------------------------------------------------------

    @Test
    fun `the fenced command port types a Terminal fence as guardianTerminal`() {
        val port = sliceBetween(
            mainSourceText("RemoteGuardianProcessAuthority.kt"),
            "GuardianFencedResult.Terminal -> CheckedCommandResult(",
            "GuardianFencedResult.Rejected ->",
        )
        assertTrue(port.contains("guardianTerminal = true"))
    }

    @Test
    fun `the monitor tick consults the hand-back gate before any decision executes`() {
        val source = stripLineComments(mainSourceText("MimirTweakerService.kt"))
        val cycle = sliceBetween(
            source,
            "var observedDecision: SupervisorDecision = supervisor.tick()",
            "val outcome = supervisor.execute(",
        )
        val override = cycle.indexOf("guardianHandback.override(")
        assertTrue(
            "the receipt override must be skipped during a hold or backoff (P2-3)",
            cycle.contains("holdOrBackoff = supervisor.isHoldOrBackoff(),"),
        )
        val fanDecision = cycle.indexOf("observedDecision = fanControlDecision(supervisor.activeSession())")
        val dispatchLog = cycle.indexOf("\"reassert dispatch generation=")
        assertTrue("the override must run", override >= 0)
        assertTrue("the override must precede the fan-control decision", override < fanDecision)
        assertTrue("the override must precede the RepairFanTarget dispatch", override < dispatchLog)
        assertTrue(
            "the override reads the Guardian's terminal receipt store",
            cycle.contains("guardianTerminalReceipts?.loadUntil(0) is GuardianTerminalLoad.Received"),
        )
    }

    @Test
    fun `the authority retires under its monitor before the binder call, and never on transport alone`() {
        val source = stripLineComments(mainSourceText("RemoteGuardianProcessAuthority.kt"))
        val publish = sliceBetween(source, "private fun publishHeartbeat()", "private fun stopHeartbeat()")
        val deniedBranch = sliceBetween(publish, "is HeartbeatStep.Denied -> {", "is HeartbeatStep.Beat -> {")
        assertTrue("the denial runs through GuardianDenialSequence", deniedBranch.contains("GuardianDenialSequence.run("))
        val retire = sliceBetween(deniedBranch, "retireUnderMonitor = {", "requestRestore = {")
        assertTrue("the retirement runs under the authority monitor", retire.contains("synchronized(this) {"))
        assertTrue(
            "the retirement must check the session is still this one",
            retire.contains("current.generation != step.envelope.generation"),
        )
        assertTrue(
            "stop the heartbeat, then mark the retirement",
            retire.indexOf("stopHeartbeat()") in 0 until retire.indexOf("onSessionRetired("),
        )
        assertTrue(
            "the Guardian is asked only as the second step",
            deniedBranch.substringAfter("requestRestore = {").contains("step.guardian.requestRestore()"),
        )
        val settle = publish.substring(publish.indexOf("private fun settleHeartbeatAnswer(").also { assertTrue(it >= 0) })
        val nullAnswer = settle.indexOf("if (response == null) {")
        assertTrue(
            "a null binder answer must return before anything is stopped (P2-4)",
            nullAnswer in 0 until settle.indexOf("stopHeartbeat()") &&
                sliceBetween(settle, "if (response == null) {", "val current = activeEnvelope").contains("return"),
        )
        assertTrue(
            "the rejected branch must consult the retire policy with the receipt store",
            settle.contains("GuardianHeartbeatRetirePolicy.retireCause(answer)") &&
                settle.contains("terminalStore.loadUntil(0) is GuardianTerminalLoad.Received"),
        )
        assertTrue(
            "the Guardian's binder calls must run with the authority monitor released",
            publish.contains("val response = runCatching { step.guardian.heartbeat(step.wire) }.getOrNull()") &&
                publish.contains("synchronized(this) { settleHeartbeatAnswer(step, response) }"),
        )
    }

    // -- Helpers --------------------------------------------------------------------------------

    private class SupervisorEnv(
        fanReassert: TransactionResult? = null,
        cpuRepair: TransactionResult? = null,
        derate: TransactionResult? = null,
    ) {
        val restores = mutableListOf<Pair<Long, String>>()
        val supervisor = HardwareSessionSupervisor(
            clock = SupervisorClock { 1_000L },
            workload = WorkloadObservationPort { WorkloadTruth.TARGET_FOREGROUND },
            telemetry = ThermalTelemetryPort { null },
            ownerLiveness = OwnerLivenessPort { true },
            restorer = GenerationBoundRestorer { generation, transactionId ->
                restores += generation to transactionId
                TransactionResult(true, false, true, "RESTORED", "ok", transactionId = transactionId, generation = generation)
            },
            targetRepairer = GenerationBoundTargetRepairer { _, _ -> requireNotNull(cpuRepair) },
            derater = GenerationBoundDerater { _, _, _ -> requireNotNull(derate) },
            fanReasserter = GenerationBoundFanReasserter { _, _ -> requireNotNull(fanReassert) },
        )

        fun adopt(generation: Long, transactionId: String) {
            supervisor.adoptAppliedSession(identity(generation, transactionId))
        }
    }

    private companion object {
        fun identity(generation: Long, transactionId: String) = ActiveSessionIdentity(
            generation = generation,
            transactionId = transactionId,
            requestId = "req-$generation",
            ownerUid = 42,
            targetPackage = "example.game",
            activationDeadlineMs = 10_000L,
            leaseDeadlineMs = 60_000L,
            targetEverForeground = true,
        )

        fun repair(generation: Long, transactionId: String) =
            SupervisorDecision.RepairFanTarget(generation, transactionId, "TARGET_CONTRACT_DRIFT_FAN")

        fun fenceClosed(generation: Long, transactionId: String, what: String) = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = TransactionCoordinator.GUARDIAN_TERMINAL_FENCE,
            message = "$what refused: the Guardian has already terminalized this generation; the session is settled.",
            transactionId = transactionId,
            generation = generation,
        )

        fun mainSourceText(name: String): String {
            val root = sequenceOf(
                File("src/main/kotlin/com/gruning/mimir/tweaker"),
                File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
            ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
            val file = File(root, name)
            if (!file.isFile) throw AssertionError("$name not found under $root")
            return file.readText()
        }

        fun sliceBetween(text: String, startMarker: String, endMarker: String): String {
            val start = text.indexOf(startMarker)
            require(start >= 0) { "start marker not found: $startMarker" }
            val end = text.indexOf(endMarker, start)
            require(end > start) { "end marker not found after start: $endMarker" }
            return text.substring(start, end)
        }

        fun stripLineComments(text: String): String =
            text.lineSequence().joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
    }
}
