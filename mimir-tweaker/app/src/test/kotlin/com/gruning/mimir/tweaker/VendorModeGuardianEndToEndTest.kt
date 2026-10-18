package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.mgde.MgdeExpectedStockProjection
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The vendor `performance_mode` end to end (D3 of the 2026-10-13 vendor-mode design): the REAL
 * [TransactionCoordinator] applies a vendor-mode plan through the real [PServerHardwareCommandPort],
 * [AndroidHardwareStatePort] and [HardwareProbe]; the REAL [AndroidGuardianPreparedEnvelopePort] signs
 * the restore envelope; the daemon then "dies" and the Guardian restores from nothing but that
 * envelope, the shared origin record and the key -- through the real [GuardianProcessHardware] and
 * the real [RestoreGuardianRunnerEngine].
 *
 * Only the edges are fakes: the pserver (a `settings put` moves the key the way pservice would), the
 * journal (in memory), the Guardian arm store (captures the signed envelope), and the Guardian
 * service's own `restoreActive` loop, which is mirrored step for step in [guardianRestore] because it
 * lives in an Android `Service`. Every fixture is settings-only, so none of it depends on the host
 * filesystem and it means the same thing on Windows as on Linux.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class VendorModeGuardianEndToEndTest {

    // ---- fixtures -----------------------------------------------------------------------------------

    private val resolver = SysfsCanonicalResolver { it }
    private val certificate = "a".repeat(64)
    private val authority = GuardianTestKeyAuthority.create(certificate).available
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public,
    )

    private class Key(var value: String?)

    private class RecordingPServer(private val key: Key) : PServerClient() {
        val commands = mutableListOf<String>()

        override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
            commands += command
            Regex("^settings put system performance_mode (\\d+)$").matchEntire(command)?.let {
                key.value = it.groupValues[1]
            }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
        }
    }

    private class MemoryJournal : TransactionJournal {
        var record: TransactionJournalRecord? = null
        private var generation = 0L

        override fun load(): JournalLoadResult =
            record?.let { JournalLoadResult.Loaded(it) } ?: JournalLoadResult.Empty

        override fun nextGeneration(): GenerationResult {
            generation += 1
            return GenerationResult.Ok(generation)
        }

        override fun persist(record: TransactionJournalRecord): Boolean {
            this.record = record
            return true
        }

        override fun clear(expectedTransactionId: String): Boolean {
            if (record?.transactionId == expectedTransactionId) record = null
            return record == null
        }
    }

    /** Captures the signed envelope the coordinator arms, and answers READY. */
    private class CapturingArm : GuardianArmAuthorityPort {
        var signed: SignedRestoreGuardianEnvelope? = null

        override fun armToReady(
            signed: SignedRestoreGuardianEnvelope,
            expected: RestoreGuardianExpectedIdentity,
        ): GuardianArmAdmission {
            this.signed = signed
            return GuardianArmAdmission.Ready(GuardianReadyHandle(expected, signed, "d".repeat(64)))
        }

        override fun presence() = GuardianArmStorePresence.PRESENT
        override fun verifyTerminal(handle: GuardianReadyHandle) =
            GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)
        override fun disarm(handle: GuardianReadyHandle) = false
    }

    private fun caps() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "anyvendor",
        model = "anyhandheld",
        socModel = "SM8550",
        buildFingerprint = "test/1",
        deviceProfileId = "generic-test-profile",
        supportedAxes = setOf(TuningAxis.VENDOR_MODE),
        pServerAvailable = true,
        cpuPolicies = emptyList(),
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = false,
        fanNodeAvailable = false,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
        vendorPerformanceModePresent = true,
    )

    /** One handheld: the key, the shared origin record and the pserver survive a daemon's death. */
    private inner class Device(initial: String?) {
        val key = Key(initial)
        val pServer = RecordingPServer(key)
        val store = VendorModeOriginStore(File(Files.createTempDirectory("vmode-e2e").toFile(), "store"))
        val reader = SystemReader(
            privilegedNodes = null,
            directRead = { path -> if (path == HardwareProbe.BOOT_ID_PATH) "boot-1" else null },
            directDirectories = { _, _ -> emptyList() },
        )
        val probe = HardwareProbe(
            context = RuntimeEnvironment.getApplication(),
            reader = reader,
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ -> PServerTransactResult(accepted = false, output = null) },
            ),
            sleeper = {},
            pServerAvailable = { true },
            vendorModeOriginStore = store,
            vendorModeTransportEnabled = true,
            vendorModeSettingReader = { key.value },
        )
        var clock = 1_000L
        val journal = MemoryJournal()
        val arm = CapturingArm()

        /** A daemon: coordinator over the real ports. Calling this again is a re-armed daemon. */
        fun daemon(journal: MemoryJournal = this.journal, arm: CapturingArm = this.arm): TransactionCoordinator {
            val commands = PServerHardwareCommandPort(
                pServer = pServer,
                reader = reader,
                capabilities = ::caps,
                fanModeRaw = { null },
                sleeper = {},
                elapsedRealtimeMs = { clock },
                vendorModeRaw = probe::vendorModeRaw,
                vendorModeOrigin = probe.vendorModeOriginStore,
                sysfsResolver = resolver,
            )
            return TransactionCoordinator(
                planner = TuningPlanner { _, _ -> true },
                state = AndroidHardwareStatePort(
                    probe = probe,
                    capabilities = ::caps,
                    sleeper = {},
                    sysfsResolver = resolver,
                ),
                commands = commands,
                journal = journal,
                hardwareWritesCompiled = true,
                idFactory = { "tx-${++ids}" },
                clock = { clock },
                requiresGuardianAuthority = true,
                guardianPreparedEnvelope = AndroidGuardianPreparedEnvelopePort(
                    context = RuntimeEnvironment.getApplication(),
                    authority = authority,
                    certificateDigest = certificate,
                    helperBuildIdentity = "helper.1",
                ),
                guardianArmAuthority = arm,
            )
        }

        private var ids = 0

        fun record(): VendorModeOriginLoad = store.load()
    }

    private fun Device.apply(profile: String, daemon: TransactionCoordinator = daemon()): TransactionResult =
        daemon.apply(profile, "example.game", caps(), dryRun = false, requestId = "req-$profile-${clock++}")

    private fun VendorModeOriginLoad.origin(): Int? = (this as? VendorModeOriginLoad.Held)?.record?.origin
    private fun VendorModeOriginLoad.applied(): Int? = (this as? VendorModeOriginLoad.Held)?.record?.applied

    private data class GuardianOutcome(
        val state: GuardianRunnerLifecycleState,
        val executed: Boolean,
        val intents: List<String>,
        val described: String,
    )

    /**
     * What `RestoreGuardianProcessService.restoreActive` does, step for step, with the process, fence
     * and receipt stores taken out: observe, plan, execute, release the origin record, two readbacks,
     * confirm.
     */
    private fun Device.guardianRestore(
        signed: SignedRestoreGuardianEnvelope,
        crossBoot: Boolean = false,
    ): GuardianOutcome {
        val envelope = signed.envelope
        var tick = 5_000L
        val hardware = GuardianProcessHardware(probe, reader, pServer) { tick += 100; tick }
        val engine = RestoreGuardianRunnerEngine(
            GuardianReceiptAuthenticationStrategy { _, _ -> byteArrayOf(1) },
            GuardianReceiptOutputPort { GuardianReceiptOutputDisposition.CONFIRMED },
            sessionKey,
        )
        engine.stage(GuardianEnvelopeVerificationOutcome.Verified(envelope), 1, envelope.bootIdentity)
        engine.admitReady(2)
        engine.beginWatching(3)
        if (crossBoot) engine.ownerDecision(GuardianOwnerDecision.BOOT_MISMATCH) else engine.ownerCancelled()

        val liveBoot = if (crossBoot) "boot-2" else envelope.bootIdentity
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(envelope)
        val evidence = if (crossBoot) {
            GuardianRestoreFenceEvidence.DifferentBootPersistent(liveBoot, GuardianPersistentRestoreFenceProof(true, true, true))
        } else {
            GuardianRestoreFenceEvidence.SameBoot(FenceDiskState(true, identity, identity, true))
        }
        val observed = checkNotNull(hardware.observe(envelope.restoreEntries))
        val planned = engine.planRestore(liveBoot, evidence, observed)
        check(planned.state == GuardianRunnerLifecycleState.RESTORING) { "restore not admitted: ${planned.state}" }
        val executed = hardware.execute(planned.restoreIntents)
        hardware.settleVendorOrigin(envelope.restoreEntries)
        val first = checkNotNull(hardware.readback(envelope.restoreEntries, persistentOnly = false))
        val second = checkNotNull(hardware.readback(envelope.restoreEntries, persistentOnly = false))
        val confirmed = engine.confirmStock(first, second)
        return GuardianOutcome(
            confirmed.state,
            executed,
            planned.restoreIntents.map { it.entry.logicalId },
            GuardianDoubleStockReadbackPolicy.describe(envelope.restoreEntries, first, second),
        )
    }

    private fun Device.vendorPuts(): List<String> = pServer.commands.filter { "performance_mode" in it }

    // ---- the envelope the coordinator arms ------------------------------------------------------------

    @Test
    fun `applying a vendor mode plan arms a signed envelope carrying the honest origin and the applied mode`() {
        val device = Device("0")

        val result = device.apply("performance")

        assertEquals(result.message, "APPLIED", result.code)
        assertEquals(2, result.plan?.vendorMode)
        assertEquals("2", device.key.value)
        val signed = checkNotNull(device.arm.signed)
        assertTrue(RestoreGuardianCrypto.verifyEnvelope(signed, authority.envelopeVerifier()))
        val entry = signed.envelope.restoreEntries.single() as RestoreGuardianVendorModePutEntry
        assertEquals("the player's own mode, never Mimir's", 0, entry.origin)
        assertEquals(2, entry.applied)
        assertEquals(0, entry.applyOrdinal)
        // Mimir remembered whose mode this was BEFORE it wrote: the record is the Guardian's source of truth.
        assertEquals(0, device.record().origin())
        assertEquals(2, device.record().applied())
    }

    // ---- 1. kill the daemon with Performance applied: the Guardian puts the player's mode back ---------

    @Test
    fun `the daemon dies with Performance applied and the Guardian restores the origin and clears the record`() {
        val device = Device("0")
        assertEquals("APPLIED", device.apply("performance").code)
        val signed = checkNotNull(device.arm.signed)
        assertEquals("2", device.key.value)
        // -- the daemon is gone: nothing below touches its coordinator, journal or ports --

        val outcome = device.guardianRestore(signed)

        assertTrue("the restore command must land", outcome.executed)
        assertEquals(listOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID), outcome.intents)
        assertEquals("0", device.key.value)
        assertEquals(
            listOf("settings put system performance_mode 2", "settings put system performance_mode 0"),
            device.vendorPuts(),
        )
        assertEquals(VendorModeOriginLoad.Absent, device.record())
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
        assertEquals("a clean restore says nothing more", GuardianDoubleStockDecision.STOCK_CONFIRMED.name, outcome.described)
    }

    // ---- 2. the player moved the mode: their choice stands -------------------------------------------

    @Test
    fun `a mode the player moved before the Guardian ran is left alone and the confirmation says so`() {
        val device = Device("0")
        assertEquals("APPLIED", device.apply("performance").code)
        val signed = checkNotNull(device.arm.signed)
        device.key.value = "1" // the player used the vendor settings screen

        val outcome = device.guardianRestore(signed)

        assertEquals("1", device.key.value)
        assertEquals("the Guardian must write nothing over the player's choice", 1, device.vendorPuts().size)
        assertTrue(outcome.intents.isEmpty())
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
        assertTrue(outcome.described, outcome.described.contains("unheld=${HardwareSnapshot.VENDOR_MODE_LOGICAL_ID}=vmode1!=vmode0"))
        // The player's choice stands and the record goes with it (V-P1): a record kept here would let a
        // later session whose live read equals Mimir's old value take the old origin for the player's.
        assertEquals(VendorModeOriginLoad.Absent, device.record())
    }

    // ---- 2b. V-P1: a stale record must not make a later session's restore dishonest --------------------------------

    /**
     * The six-step scenario of the 2026-10-13 review. Quiet over a player on Performance records
     * {origin 2, applied 0}; the player then picks mode 1 mid-session, the restore leaves it and (the fix)
     * releases the record; later the player picks 0 by hand and a Performance game starts. With the old
     * keep-on-skip record the capture read live 0 == applied 0, took the stale origin 2 as the player's
     * mode, wrote 2 and ended "already at origin" -- the device stayed on Mimir's 2 although the player was
     * on 0. Now the second session takes the true origin 0 and its restore leaves the player's 0.
     */
    @Test
    fun `a mode the player chose after a skipped restore is the next session's origin, never a stale record`() {
        val device = Device("2") // 1. the player is on mode 2
        assertEquals("APPLIED", device.apply("quiet").code)
        assertEquals("0", device.key.value)
        assertEquals(2, device.record().origin())
        assertEquals(0, device.record().applied())
        val first = checkNotNull(device.arm.signed)

        device.key.value = "1" // 2. mid-session the player picks 1
        val skipped = device.guardianRestore(first) // 3. exit: the restore leaves the player's choice
        assertEquals("1", device.key.value)
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, skipped.state)
        assertEquals("no record survives the skip", VendorModeOriginLoad.Absent, device.record())

        device.key.value = "0" // 4. no session: the player picks 0 by hand
        val fresh = CapturingArm() // 5. a Performance game starts (a new session on a clean journal)
        val second = device.daemon(journal = MemoryJournal(), arm = fresh)
        assertEquals("APPLIED", device.apply("performance", second).code)
        assertEquals("2", device.key.value)
        val entry = checkNotNull(fresh.signed).envelope.restoreEntries.single() as RestoreGuardianVendorModePutEntry
        assertEquals("the true pre-session mode, not the stale 2", 0, entry.origin)
        assertEquals(2, entry.applied)
        assertEquals(0, device.record().origin())
        assertEquals(2, device.record().applied())

        val outcome = device.guardianRestore(checkNotNull(fresh.signed)) // 6. exit
        assertEquals("the player's 0 is back", "0", device.key.value)
        assertEquals(listOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID), outcome.intents)
        assertEquals(VendorModeOriginLoad.Absent, device.record())
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
    }

    // ---- 3. the plan targets the mode the player is already on ------------------------------------------

    @Test
    fun `a plan on the player's own mode writes nothing and the Guardian restores it cleanly`() {
        val device = Device("0")
        val applied = device.apply("quiet")
        assertEquals(applied.message, "APPLIED", applied.code)
        assertEquals(0, applied.plan?.vendorMode)
        assertTrue("nothing to write when the plan targets the live mode", device.vendorPuts().isEmpty())
        assertEquals("a plan that deviates from nothing leaves no record", VendorModeOriginLoad.Absent, device.record())
        val signed = checkNotNull(device.arm.signed)
        val entry = signed.envelope.restoreEntries.single() as RestoreGuardianVendorModePutEntry
        assertEquals("the node is in the snapshot so the entry must exist", 0, entry.origin)
        assertEquals(0, entry.applied)

        val outcome = device.guardianRestore(signed)

        assertTrue(outcome.executed)
        assertTrue("an entry on the origin can never ask for a write", outcome.intents.isEmpty())
        assertTrue(device.vendorPuts().isEmpty())
        assertEquals("0", device.key.value)
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
        assertEquals(GuardianDoubleStockDecision.STOCK_CONFIRMED.name, outcome.described)
    }

    // ---- 4. a reboot with Performance applied -------------------------------------------------------------------

    @Test
    fun `a reboot with Performance applied leaves Mimir's mode and the cross boot replay puts the origin back`() {
        val device = Device("0")
        assertEquals("APPLIED", device.apply("performance").code)
        val signed = checkNotNull(device.arm.signed)
        // The key is a persistent settings value and the record sits on device-protected storage, so
        // both survive the reboot untouched; only the boot identity changes.
        assertEquals("2", device.key.value)

        val outcome = device.guardianRestore(signed, crossBoot = true)

        assertEquals("0", device.key.value)
        assertEquals(listOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID), outcome.intents)
        assertEquals(VendorModeOriginLoad.Absent, device.record())
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
    }

    // ---- 5. a daemon frozen with Performance applied, then re-armed -------------------------------------------------

    @Test
    fun `a re armed daemon keeps the pre Mimir origin, never Mimir's own mode`() {
        val device = Device("0")
        assertEquals("APPLIED", device.apply("performance").code)
        assertEquals("2", device.key.value)
        // The first daemon froze; the second starts with the key at Mimir's 2 and no journal of its own.
        val fresh = CapturingArm()
        val second = device.daemon(journal = MemoryJournal(), arm = fresh)

        val result = device.apply("balanced", second)

        assertEquals(result.message, "APPLIED", result.code)
        assertEquals("1", device.key.value)
        val entry = checkNotNull(fresh.signed).envelope.restoreEntries.single() as RestoreGuardianVendorModePutEntry
        assertEquals("the restore point is the player's mode", 0, entry.origin)
        assertEquals(1, entry.applied)
        assertEquals(0, device.record().origin())
        assertEquals(1, device.record().applied())

        val outcome = device.guardianRestore(checkNotNull(fresh.signed))

        assertEquals("0", device.key.value)
        assertTrue("Mimir's 2 was never written back", device.vendorPuts().last() == "settings put system performance_mode 0")
        assertEquals(VendorModeOriginLoad.Absent, device.record())
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
    }

    // ---- the Guardian's own housekeeping -----------------------------------------------------------------------------

    @Test
    fun `a daemon that restored the mode and died before clearing leaves a record the Guardian releases`() {
        val device = Device("0")
        assertEquals("APPLIED", device.apply("performance").code)
        val signed = checkNotNull(device.arm.signed)
        device.key.value = "0" // the daemon wrote the origin back, then died before it cleared the record
        assertEquals(2, device.record().applied())

        val outcome = device.guardianRestore(signed)

        assertTrue(outcome.intents.isEmpty())
        assertEquals("nothing left to write", 1, device.vendorPuts().size)
        assertEquals(VendorModeOriginLoad.Absent, device.record())
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
    }

    @Test
    fun `the Guardian does not claim stock while it cannot read the key`() {
        val device = Device("0")
        assertEquals("APPLIED", device.apply("performance").code)
        val signed = checkNotNull(device.arm.signed)
        device.key.value = null // unreadable

        val hardware = GuardianProcessHardware(device.probe, device.reader, device.pServer)
        val observed = checkNotNull(hardware.observe(signed.envelope.restoreEntries))
        assertEquals(GuardianObservedState.VendorMode(null), observed.getValue(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID))

        val outcome = device.guardianRestore(signed)
        assertFalse("an unreadable key is not a restored one", outcome.state == GuardianRunnerLifecycleState.STOCK_CONFIRMED)
        assertTrue(device.vendorPuts().size == 1)
    }

    @Test
    fun `the persistent readback and observation cover the vendor entry`() {
        val device = Device("0")
        assertEquals("APPLIED", device.apply("performance").code)
        val entries = checkNotNull(device.arm.signed).envelope.restoreEntries
        val hardware = GuardianProcessHardware(device.probe, device.reader, device.pServer)

        val readback = checkNotNull(hardware.readback(entries, persistentOnly = true))

        assertEquals(setOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID), readback.entries.keys)
        assertEquals(GuardianObservedState.VendorMode(2), readback.entries.getValue(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID))
    }

    // ---- the envelope port refuses anything inconsistent ------------------------------------------------------------------

    private fun vendorNode(origin: Int?) = TopologyNodeState(
        logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
        axis = TuningAxis.VENDOR_MODE,
        canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
        originalValue = origin,
        originalMode = null,
        originalUid = null,
        originalGid = null,
        present = origin != null,
    )

    private fun vendorMutation(value: Int, origin: Int, applied: Int) = LogicalMutation(
        logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
        axis = TuningAxis.VENDOR_MODE,
        canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
        kind = LogicalMutationKind.SETTINGS_PUT,
        value = value,
        restoreMode = null,
        expectPresent = true,
        vendorModeContext = VendorModeMutationContext(origin = origin, applied = applied, transactionId = "plan:x"),
    )

    private fun preparedEnvelope(node: TopologyNodeState, mutation: LogicalMutation): SignedRestoreGuardianEnvelope? {
        val snapshot = HardwareSnapshot(HARDWARE_SNAPSHOT_SCHEMA, "fp", "boot-1", listOf(node))
        val identity = GuardianJournalIdentity(
            generation = 3, requestId = "req", transactionId = "tx", hardwareFingerprint = "fp",
            deviceProfile = "generic-test-profile", bootIdentity = "boot-1",
            expectedTopologySha256 = "b".repeat(64), snapshotSha256 = "c".repeat(64), planSha256 = "d".repeat(64),
        )
        return AndroidGuardianPreparedEnvelopePort(
            context = RuntimeEnvironment.getApplication(),
            authority = authority,
            certificateDigest = certificate,
            helperBuildIdentity = "helper.1",
        ).preparedEnvelope(GuardianPreparedEnvelopeRequest(identity, 10_000, snapshot, listOf(mutation)))
    }

    @Test
    fun `the envelope port signs a consistent vendor mutation and refuses every inconsistent one`() {
        val signed = checkNotNull(preparedEnvelope(vendorNode(0), vendorMutation(value = 2, origin = 0, applied = 2)))
        assertEquals(RestoreGuardianVendorModePutEntry(0, origin = 0, applied = 2), signed.envelope.restoreEntries.single())

        // A plan on the origin still renders an entry.
        assertNotNull(preparedEnvelope(vendorNode(1), vendorMutation(value = 1, origin = 1, applied = 1)))

        assertNull("context origin disagrees with the captured node", preparedEnvelope(vendorNode(0), vendorMutation(2, origin = 1, applied = 2)))
        assertNull("context applied disagrees with the written value", preparedEnvelope(vendorNode(0), vendorMutation(2, origin = 0, applied = 1)))
        assertNull("no origin was captured", preparedEnvelope(vendorNode(null), vendorMutation(2, origin = 0, applied = 2)))
        assertNull("a mode outside the known set", preparedEnvelope(vendorNode(0), vendorMutation(3, origin = 0, applied = 3)))
        assertNull(
            "a mutation with no origin context",
            preparedEnvelope(vendorNode(0), vendorMutation(2, 0, 2).copy(vendorModeContext = null)),
        )
        assertNull(
            "a restore-authority mutation is not an apply",
            preparedEnvelope(vendorNode(0), vendorMutation(2, 0, 2).copy(capturedSnapshotAuthority = true)),
        )
    }

    // ---- MGDE accepts the envelope the port really signs ---------------------------------------------------------------------

    /**
     * The projection MGDE builds from an authenticated journal. Its factory first runs
     * [RestoreContextValidator], whose live-allowlist check canonicalises `/sys/...` through the host
     * filesystem and so refuses on Windows regardless of the plan -- which is why MGDE's own fixture is
     * red on this machine. Neither rule under test here (`matchesExactStockAndPlan`,
     * `compiledPolicyAllows`) depends on that validator, so the (private) constructor is used directly.
     * The record is the REAL one the coordinator journalled, as a schema-v4 record the way MGDE reads it.
     */
    private fun mgdeProjection(record: TransactionJournalRecord): MgdeExpectedStockProjection =
        MgdeExpectedStockProjection::class.java
            .getDeclaredConstructor(TransactionJournalRecord::class.java, HardwareCapabilities::class.java)
            .also { it.isAccessible = true }
            .newInstance(record.copy(schemaVersion = TRANSACTION_JOURNAL_SCHEMA, guardianBinding = null), caps())

    @Test
    fun `MGDE accepts a well formed vendor entry and rejects one that disagrees with the journal`() {
        val device = Device("0")
        assertEquals("APPLIED", device.apply("performance").code)
        val record = checkNotNull(device.journal.record)
        val envelope = checkNotNull(device.arm.signed).envelope

        val projection = mgdeProjection(record)
        assertTrue(projection.matchesExactStockAndPlan(envelope))
        assertTrue(projection.compiledPolicyAllows(envelope))

        val entry = envelope.restoreEntries.single() as RestoreGuardianVendorModePutEntry
        listOf(
            "another origin" to entry.copy(origin = 1),
            "another applied mode" to entry.copy(applied = 1),
            "another ordinal" to entry.copy(applyOrdinal = 3),
        ).forEach { (label, altered) ->
            val forged = envelope.copy(restoreEntries = listOf(altered))
            assertFalse(label, projection.matchesExactStockAndPlan(forged) && projection.compiledPolicyAllows(forged))
        }
        // The vendor node with NO entry for it is the count mismatch MGDE used to answer alone.
        val fan = RestoreGuardianFanPutEntry(
            applyOrdinal = 0,
            expectedStock = RestoreGuardianStockState.PresentFanMode(4),
            rendererAppliedIntermediates = listOf(1),
        )
        assertFalse(projection.matchesExactStockAndPlan(envelope.copy(restoreEntries = listOf(fan))))
    }
}
