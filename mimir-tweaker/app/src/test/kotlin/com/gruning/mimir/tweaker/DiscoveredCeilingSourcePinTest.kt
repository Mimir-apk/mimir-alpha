package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v10 ceiling's write is UN-FENCED, and that is a decision this pin keeps decided.
 *
 * A discovered ceiling is player-owned and boot-bound: it holds no lease, arms no Guardian envelope
 * and journals nothing, because its restore memory is the record. The failure this guards against is
 * the plausible-looking "improvement" of routing it through the machinery every OTHER mutation in
 * this daemon uses — which would not merely add ceremony, it would make the write impossible:
 * `GuardianFencedHardwareCommandPort` refuses unless a Guardian fence identity exists, and that
 * identity exists only inside an ARMED transaction.
 *
 * Comments are stripped before the check, so the actuator's own KDoc may (and must) go on explaining
 * why each of these is absent. A doc link is not a call.
 */
class DiscoveredCeilingSourcePinTest {

    @Test
    fun `the actuator never reaches the Guardian, the journal, a lease or the supervisor`() {
        val code = strippedActuatorSource()
        assertForbiddenMachineryAbsent(code)
    }

    /**
     * The keeper inherits the pin, because it lives in the pinned file.
     *
     * A watcher that re-asserts a cap every second is exactly the component somebody would later
     * "harden" by routing it through the supervisor, the journal or a Guardian fence — and that
     * would not harden it, it would stop it: a ceiling arms no transaction, so there is no fence
     * identity for a fenced port to find, and nothing in its lifecycle ever reads a journal. Its
     * restore memory is the record; its budget is its own copy of the supervisor's numbers, not the
     * supervisor. This test fails loudly if the keeper ever moves out of the pinned file.
     */
    @Test
    fun `the keeper lives in the pinned file and reaches none of that machinery either`() {
        val code = strippedActuatorSource()
        assertTrue(
            "the keeper's port must stay in DiscoveredCeilingActuator.kt, inside this pin",
            code.contains("internal interface CeilingKeeperScheduler"),
        )
        assertTrue("the tick itself must stay in the pinned file", code.contains("fun keeperTick()"))
        assertTrue("the budget must stay in the pinned file", code.contains("fun admitRepair("))
        assertForbiddenMachineryAbsent(code)
    }

    /**
     * The keeper's tick is its OWN scheduler, never the session supervisor's monitor — the monitor
     * exists only while a named-profile session does, and a ceiling is refused while one exists, so
     * sharing it would mean the watcher could only run at the exact times nothing is there to watch.
     */
    @Test
    fun `the service gives the keeper its own scheduler and its own thread`() {
        val service = source("MimirTweakerService.kt")
        assertTrue(service.contains("keeper = ceilingKeeper"))
        assertTrue(
            "the keeper must not be scheduled on monitorExecutor",
            // The thread name is a constant now (BoundedTransactionWorker keys tick skipping on it); the
            // keeper still owns a thread of its own with that name.
            service.contains("Thread(runnable, TX_TICK_CEILING_THREAD)") &&
                service.contains("const val TX_TICK_CEILING_THREAD = \"mimir-tweaker-ceiling\""),
        )
    }

    private fun assertForbiddenMachineryAbsent(code: String) {
        listOf(
            "GuardianFencedHardwareCommandPort",
            "RemoteGuardianProcessAuthority",
            "GuardianProcessMutationFence",
            "RestoreGuardian",
            "TransactionJournal",
            "TransactionCoordinator",
            "HardwareSessionSupervisor",
            "journal",
            "Journal",
            "lease",
            "Lease",
            "LogicalMutation",
            "QualificationManifest",
        ).forEach { forbidden ->
            assertTrue(
                "DiscoveredCeilingActuator.kt must not reference $forbidden outside its comments",
                !code.contains(forbidden),
            )
        }
    }

    /**
     * The one privileged transport it DOES name is the un-fenced one, and it is named through a
     * port rather than a concrete class, so the service is the only place the binding is chosen.
     */
    @Test
    fun `the actuator writes through the un-fenced command port`() {
        val code = strippedActuatorSource()
        assertTrue(code.contains("internal fun interface CeilingCommandPort"))
        assertTrue(code.contains("commands.execute(command)"))
    }

    /** The service binds that port straight to `PServerClient.executeChecked`, nothing else. */
    @Test
    fun `the service binds the ceiling to PServerClient directly`() {
        val service = source("MimirTweakerService.kt")
        assertTrue(
            "the ceiling's command port must be pServer.executeChecked, not a fenced delegate",
            service.contains("commands = { command -> pServer.executeChecked(command) }"),
        )
    }

    /**
     * The ceiling check ahead of a profile apply fails CLOSED. It used to be `runCatching { ... }.getOrNull()`, where a
     * null (an exception) meant "proceed", so a ceiling that could not be looked at was treated as no ceiling.
     */
    @Test
    fun `the service refuses a profile apply when the ceiling check throws`() {
        val service = source("MimirTweakerService.kt")
        val function = service.substringAfter("private fun ceilingPrecedenceFailureCode()").substringBefore("\n    )\n")
        assertTrue(function.contains("CeilingPrecedenceGate.refusalCode("))
        assertTrue(function.contains("discoveredCeiling.withdrawBeforeProfileApply()"))
        assertTrue(
            "no runCatching may turn the ceiling check's exception into permission to proceed",
            !service.contains("runCatching { discoveredCeiling.withdrawBeforeProfileApply() }"),
        )
    }

    /** No sysfs path may cross the Binder boundary; every path is a compile-time constant here. */
    @Test
    fun `every node the actuator touches is a discovered constant`() {
        val code = strippedActuatorSource()
        val quotedPaths = Regex("\"/[^\"]*\"").findAll(code).map { it.value }.toList()
        assertEquals("no literal sysfs path belongs in this file: $quotedPaths", emptyList<String>(), quotedPaths)
    }

    /** The v10 record is the v9 memo's SIBLING, never its file. */
    @Test
    fun `the ceiling record has its own file`() {
        val store = source("DiscoveredAxisRecordStore.kt")
        assertTrue(store.contains("mimir_discovered_axis_session.v1.json"))
        assertTrue(store.contains("mimir_discovered_ceiling.v1.json"))
        assertEquals(
            DiscoveredAxisRecordStore.RECORD_FILE != DiscoveredAxisRecordStore.CEILING_RECORD_FILE,
            true,
        )
    }

    private fun strippedActuatorSource(): String = source("DiscoveredCeilingActuator.kt")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)//.*$"), "")

    private fun source(fileName: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $fileName not found")
}
