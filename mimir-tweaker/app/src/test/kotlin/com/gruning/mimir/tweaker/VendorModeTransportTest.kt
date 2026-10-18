package com.gruning.mimir.tweaker

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The vendor performance-mode transport (D1 and D3 of the 2026-10-13 vendor-mode design), exercised
 * through the REAL [PServerHardwareCommandPort], [AndroidHardwareStatePort] and [HardwareProbe]
 * rather than the in-memory rigs the coordinator suites use.
 *
 * Every fixture here is settings-only (`settings system performance_mode` and `fan_mode`) plus one
 * CPU ceiling node resolved through an identity [SysfsCanonicalResolver], so the suite means the same
 * thing on a Windows host as on Linux CI. The transport switch ([VENDOR_MODE_TRANSPORT_ENABLED]) is
 * injected as a constructor argument everywhere; nothing here leans on its shipped value except the
 * one test that pins that value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class VendorModeTransportTest {

    // ---- fixtures -----------------------------------------------------------------------------

    private val identity = SysfsCanonicalResolver { it }

    /** The `performance_mode` key. Null = the key does not exist. */
    private class Key(var value: String?)

    /** Records every command; a `settings put system performance_mode N` moves the key like pservice would. */
    private class RecordingPServer(private val key: Key) : PServerClient() {
        val commands = mutableListOf<String>()
        var failWrites = false
        var onPut: (Int) -> Unit = {}

        override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
            commands += command
            val put = Regex("^settings put system performance_mode (\\d+)$").matchEntire(command)
            if (put != null) {
                val value = put.groupValues[1].toInt()
                onPut(value)
                if (failWrites) return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "injected")
                key.value = value.toString()
            }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
        }
    }

    private fun newStore(): VendorModeOriginStore =
        VendorModeOriginStore(File(Files.createTempDirectory("vmode-origin").toFile(), "store"))

    private fun caps(
        axes: Set<TuningAxis> = setOf(TuningAxis.CPU, TuningAxis.FAN, TuningAxis.VENDOR_MODE),
        pServerAvailable: Boolean = true,
    ) = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "anyvendor",
        model = "anyhandheld",
        socModel = "SM8550",
        buildFingerprint = "test/1",
        deviceProfileId = "generic-test-profile",
        supportedAxes = axes,
        pServerAvailable = pServerAvailable,
        cpuPolicies = listOf(
            CpuPolicy(
                id = 0,
                path = "/sys/devices/system/cpu/cpufreq/policy0",
                cpuIds = listOf(0),
                availableFrequenciesKhz = listOf(1_000_000, 2_000_000),
                minKhz = 1_000_000,
                maxKhz = 2_000_000,
                currentKhz = 2_000_000,
            ),
        ),
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = false,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
        vendorPerformanceModePresent = TuningAxis.VENDOR_MODE in axes,
    )

    private fun plan(
        vendorMode: Int? = 1,
        fanMode: Int? = TuningPlanner.FAN_SMART,
        cpu: Map<Int, Int> = mapOf(0 to 1_000_000),
    ) = TuningPlan(
        profileId = "balanced",
        packageName = "example.game",
        cpuMaxKhz = cpu,
        gpuMaxPowerLevel = null,
        fanMode = fanMode,
        calibrated = true,
        vendorMode = vendorMode,
    )

    private val cpuNode = TopologyNodeState(
        logicalId = "cpu:0:scaling_max_freq",
        axis = TuningAxis.CPU,
        canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        originalValue = 2_000_000,
        originalMode = "644",
        originalUid = 0,
        originalGid = 0,
        present = true,
    )
    private val fanNode = TopologyNodeState(
        logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
        axis = TuningAxis.FAN,
        canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
        originalValue = TuningPlanner.FAN_SMART,
        originalMode = null,
        originalUid = null,
        originalGid = null,
        present = true,
    )

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

    private fun snapshot(vararg nodes: TopologyNodeState) = HardwareSnapshot(
        schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
        hardwareFingerprint = "fp",
        bootIdentity = "boot-1",
        nodes = nodes.toList(),
    )

    private fun port(
        key: Key,
        pServer: PServerClient,
        store: VendorModeOriginStore?,
        capabilities: HardwareCapabilities = caps(),
        nowMs: Long = 5_000L,
    ) = PServerHardwareCommandPort(
        pServer = pServer,
        reader = SystemReader(directRead = { null }, directExists = { false }),
        capabilities = { capabilities },
        fanModeRaw = { TuningPlanner.FAN_SMART.toString() },
        sleeper = {},
        elapsedRealtimeMs = { nowMs },
        vendorModeRaw = { key.value },
        vendorModeOrigin = store,
        sysfsResolver = identity,
    )

    private fun applyMutation(value: Int, origin: Int) = LogicalMutation(
        logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
        axis = TuningAxis.VENDOR_MODE,
        canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
        kind = LogicalMutationKind.SETTINGS_PUT,
        value = value,
        restoreMode = null,
        expectPresent = true,
        vendorModeContext = VendorModeMutationContext(origin = origin, applied = value, transactionId = "plan:abc"),
    )

    private fun restoreMutation(origin: Int, applied: Int) = LogicalMutation(
        logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
        axis = TuningAxis.VENDOR_MODE,
        canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
        kind = LogicalMutationKind.SETTINGS_PUT,
        value = origin,
        restoreMode = null,
        expectPresent = true,
        capturedSnapshotAuthority = true,
        vendorModeContext = VendorModeMutationContext(origin = origin, applied = applied, transactionId = "tx-1"),
    )

    private fun probe(
        key: Key,
        store: VendorModeOriginStore = newStore(),
        enabled: Boolean = true,
        pServerAvailable: Boolean = true,
        bootId: String? = "boot-1",
    ) = HardwareProbe(
        context = RuntimeEnvironment.getApplication(),
        reader = SystemReader(
            privilegedNodes = null,
            directRead = { path -> if (path == HardwareProbe.BOOT_ID_PATH) bootId else null },
            directDirectories = { _, _ -> emptyList() },
        ),
        pServer = PServerClient(
            transport = PServerBinderTransport { _, _ -> PServerTransactResult(accepted = false, output = null) },
        ),
        sleeper = {},
        pServerAvailable = { pServerAvailable },
        vendorModeOriginStore = store,
        vendorModeTransportEnabled = enabled,
        vendorModeSettingReader = { key.value },
    )

    private fun statePort(probe: HardwareProbe, capabilities: HardwareCapabilities = caps()) =
        AndroidHardwareStatePort(
            probe = probe,
            capabilities = { capabilities },
            sleeper = {},
            sysfsResolver = identity,
        )

    // ---- apply order, the planned mutation ------------------------------------------------------

    @Test
    fun `apply order is vendor mode then fan then compute -- mode first, then our fan`() {
        // SystemUI rewrites fan_mode on every performance_mode change on the
        // AYN devices, so the mode (and its coupling settle) must land before the fan is written.
        val port = port(Key("0"), RecordingPServer(Key("0")), newStore())
        val planned = port.plannedMutations(plan(), snapshot(cpuNode, fanNode, vendorNode(0)))

        assertEquals(
            listOf(
                HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
                HardwareSnapshot.FAN_LOGICAL_ID,
                "cpu:0:scaling_max_freq",
            ),
            planned.map { it.logicalId },
        )
    }

    @Test
    fun `the planned vendor mutation is a settings put of the planned mode that carries the captured origin`() {
        val port = port(Key("0"), RecordingPServer(Key("0")), newStore())
        val vendor = port.plannedMutations(plan(vendorMode = 2), snapshot(cpuNode, fanNode, vendorNode(0)))
            .single { it.axis == TuningAxis.VENDOR_MODE }

        assertEquals(LogicalMutationKind.SETTINGS_PUT, vendor.kind)
        assertEquals(2, vendor.value)
        assertEquals(HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH, vendor.canonicalPath)
        assertTrue(vendor.expectPresent)
        assertFalse("an apply is never the captured-snapshot restore authority", vendor.capturedSnapshotAuthority)
        // The origin is the SNAPSHOT's value, the honest one -- never the live read.
        assertEquals(0, vendor.vendorModeContext?.origin)
        assertEquals(2, vendor.vendorModeContext?.applied)
        assertTrue(vendor.vendorModeContext?.transactionId.orEmpty().isNotBlank())
        assertNull("no restore posture on an apply", vendor.restoreMode)
    }

    @Test
    fun `a plan without a vendor mode plans no vendor mutation and the order is unchanged`() {
        val port = port(Key("0"), RecordingPServer(Key("0")), newStore())
        val planned = port.plannedMutations(plan(vendorMode = null), snapshot(cpuNode, fanNode))

        assertEquals(
            listOf(HardwareSnapshot.FAN_LOGICAL_ID, "cpu:0:scaling_max_freq"),
            planned.map { it.logicalId },
        )
    }

    @Test
    fun `a plan that names a mode is refused when no origin store is wired or no origin was captured`() {
        val unwired = port(Key("0"), RecordingPServer(Key("0")), store = null)
        try {
            unwired.plannedMutations(plan(), snapshot(cpuNode, fanNode, vendorNode(0)))
            fail("a port with no origin store must not plan a vendor-mode write")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("not wired"))
        }
        val noOrigin = port(Key("0"), RecordingPServer(Key("0")), newStore())
        try {
            noOrigin.plannedMutations(plan(), snapshot(cpuNode, fanNode))
            fail("no captured origin means no vendor-mode write")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("no origin"))
        }
    }

    // ---- apply: the bounded fixed command, the stamp, origin-before-write ------------------------

    @Test
    fun `apply sends the one fixed string, stamps the vendor fan clock, and never touches fan_mode`() {
        val key = Key("0")
        val pServer = RecordingPServer(key)
        val port = port(key, pServer, newStore(), nowMs = 7_777L)
        assertNull(port.lastVendorFanModeWriteElapsedRealtimeMs())

        val result = port.execute(applyMutation(value = 2, origin = 0))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(listOf("settings put system performance_mode 2"), pServer.commands)
        assertTrue(pServer.commands.none { "fan_mode" in it })
        // D4: E4's 15 s floor-learning suppression has to cover the vendor's own fan transition.
        assertEquals(7_777L, port.lastVendorFanModeWriteElapsedRealtimeMs())
        assertEquals("2", key.value)
    }

    @Test
    fun `apply is bounded to the known modes at execute`() {
        listOf(3, 7, 255).forEach { illegal ->
            val key = Key("0")
            val pServer = RecordingPServer(key)
            val result = port(key, pServer, newStore()).execute(applyMutation(value = illegal, origin = 0))

            assertEquals("mode $illegal", CheckedCommandStatus.FAILED, result.status)
            assertTrue("mode $illegal must send nothing", pServer.commands.isEmpty())
            assertEquals("0", key.value)
        }
        // A value that disagrees with its own context is refused too.
        val key = Key("0")
        val pServer = RecordingPServer(key)
        val skewed = applyMutation(value = 1, origin = 0).copy(
            vendorModeContext = VendorModeMutationContext(origin = 0, applied = 2, transactionId = "x"),
        )
        assertEquals(CheckedCommandStatus.FAILED, port(key, pServer, newStore()).execute(skewed).status)
        assertTrue(pServer.commands.isEmpty())
    }

    @Test
    fun `apply saves the origin record BEFORE it writes the key`() {
        val key = Key("0")
        val store = newStore()
        val pServer = RecordingPServer(key)
        var recordAtPut: VendorModeOriginLoad? = null
        pServer.onPut = { recordAtPut = store.load() }

        port(key, pServer, store).execute(applyMutation(value = 1, origin = 0))

        val held = recordAtPut as? VendorModeOriginLoad.Held
        assertNotNull("the record must already be durable when the put runs", held)
        assertEquals(0, held!!.record.origin)
        assertEquals(1, held.record.applied)
        assertEquals("plan:abc", held.record.transactionId)
    }

    @Test
    fun `no write happens when the origin record cannot be made durable`() {
        val key = Key("0")
        val pServer = RecordingPServer(key)
        // A store whose directory sits under a regular file can never mkdirs, so save() is false.
        val blocker = File(Files.createTempDirectory("vmode-blocker").toFile(), "blocker").apply { writeText("x") }
        val unwritable = VendorModeOriginStore(File(blocker, "sub"))

        val result = port(key, pServer, unwritable).execute(applyMutation(value = 1, origin = 0))

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail.contains("not durable"))
        assertTrue("not one command may be sent", pServer.commands.isEmpty())
        assertEquals("0", key.value)
    }

    @Test
    fun `an apply that targets the origin itself holds no record and clears a stale one`() {
        val key = Key("0")
        val store = newStore()
        assertTrue(store.save(VendorModeOriginRecord(origin = 0, applied = 2, transactionId = "stale")))
        val pServer = RecordingPServer(key)

        // origin 0, target 0: Mimir has no deviation to guard, and live is already 0.
        val result = port(key, pServer, store).execute(applyMutation(value = 0, origin = 0))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue(pServer.commands.isEmpty())
        assertEquals(VendorModeOriginLoad.Absent, store.load())
    }

    @Test
    fun `a redundant write is skipped but a held record is still refreshed`() {
        val key = Key("1")
        val store = newStore()
        assertTrue(store.save(VendorModeOriginRecord(origin = 0, applied = 1, transactionId = "older")))
        val pServer = RecordingPServer(key)
        val port = port(key, pServer, store, nowMs = 9_000L)

        // live already 1 (an earlier session's value, still held); re-applying 1 over origin 0.
        val result = port.execute(applyMutation(value = 1, origin = 0))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue(result.detail.contains("already 1"))
        assertTrue("an equal value is not re-put", pServer.commands.isEmpty())
        assertNull("a skipped write is not a vendor fan transition", port.lastVendorFanModeWriteElapsedRealtimeMs())
        val held = (store.load() as VendorModeOriginLoad.Held).record
        assertEquals(0, held.origin)
        assertEquals("plan:abc", held.transactionId)
    }

    @Test
    fun `an unreadable key refuses the write`() {
        val key = Key(null)
        val pServer = RecordingPServer(key)
        val store = newStore()

        val result = port(key, pServer, store).execute(applyMutation(value = 1, origin = 0))

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(pServer.commands.isEmpty())
        assertEquals(VendorModeOriginLoad.Absent, store.load())
    }

    @Test
    fun `a mode the player moved after capture refuses the write rather than recording a stale origin`() {
        // Captured origin 0; by apply time the player is on 2, which is neither the origin, nor the
        // target, nor a held record's value. Restoring "0" later would overwrite their newer choice.
        val key = Key("2")
        val pServer = RecordingPServer(key)
        val store = newStore()

        val result = port(key, pServer, store).execute(applyMutation(value = 1, origin = 0))

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail.contains("changed it after capture"))
        assertTrue(pServer.commands.isEmpty())
        assertEquals(VendorModeOriginLoad.Absent, store.load())
        assertEquals("2", key.value)
    }

    @Test
    fun `a failed write leaves the record, and the restore finds the origin already in place`() {
        val key = Key("0")
        val store = newStore()
        val pServer = RecordingPServer(key).apply { failWrites = true }
        val port = port(key, pServer, store)

        val applied = port.execute(applyMutation(value = 1, origin = 0))
        assertEquals(CheckedCommandStatus.FAILED, applied.status)
        assertNull("a failed write is not a vendor fan transition", port.lastVendorFanModeWriteElapsedRealtimeMs())

        // The coordinator restores the whole snapshot after a failed apply.
        val restored = port.execute(restoreMutation(origin = 0, applied = 1))
        assertEquals(CheckedCommandStatus.CONFIRMED, restored.status)
        assertEquals(1, pServer.commands.size)
        assertEquals(VendorModeOriginLoad.Absent, store.load())
    }

    // ---- restore: the three-way rule --------------------------------------------------------------

    @Test
    fun `restore writes the origin only while the live mode is still the one Mimir applied`() {
        val key = Key("1")
        val store = newStore()
        assertTrue(store.save(VendorModeOriginRecord(origin = 0, applied = 1, transactionId = "tx-1")))
        val pServer = RecordingPServer(key)
        val port = port(key, pServer, store, nowMs = 4_321L)

        val result = port.execute(restoreMutation(origin = 0, applied = 1))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(listOf("settings put system performance_mode 0"), pServer.commands)
        assertEquals("0", key.value)
        assertEquals("a confirmed origin readback clears the record", VendorModeOriginLoad.Absent, store.load())
        assertEquals(4_321L, port.lastVendorFanModeWriteElapsedRealtimeMs())
    }

    @Test
    fun `restore leaves a mode the player changed alone and says so`() {
        val key = Key("2") // neither Mimir's 1 nor the origin 0
        val store = newStore()
        assertTrue(store.save(VendorModeOriginRecord(origin = 0, applied = 1, transactionId = "tx-1")))
        val pServer = RecordingPServer(key)
        val port = port(key, pServer, store)

        val result = port.execute(restoreMutation(origin = 0, applied = 1))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue(pServer.commands.isEmpty())
        assertEquals("2", key.value)
        assertTrue(result.detail.contains("player changed it"))
        assertTrue(result.detail.contains("2") && result.detail.contains("1") && result.detail.contains("0"))
        assertEquals("a skip for the player's choice releases the record (V-P1)", VendorModeOriginLoad.Absent, store.load())
        assertNull(port.lastVendorFanModeWriteElapsedRealtimeMs())
    }

    @Test
    fun `restore finding the origin already live writes nothing and clears the record`() {
        val key = Key("0")
        val store = newStore()
        assertTrue(store.save(VendorModeOriginRecord(origin = 0, applied = 1, transactionId = "tx-1")))
        val pServer = RecordingPServer(key)

        val result = port(key, pServer, store).execute(restoreMutation(origin = 0, applied = 1))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue(pServer.commands.isEmpty())
        assertEquals(VendorModeOriginLoad.Absent, store.load())
    }

    @Test
    fun `restore of an unreadable key neither writes nor claims success`() {
        val key = Key(null)
        val pServer = RecordingPServer(key)
        val result = port(key, pServer, newStore()).execute(restoreMutation(origin = 0, applied = 1))

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(pServer.commands.isEmpty())
    }

    @Test
    fun `a failed restore write keeps the record and reports the failure`() {
        val key = Key("1")
        val store = newStore()
        val record = VendorModeOriginRecord(origin = 0, applied = 1, transactionId = "tx-1")
        assertTrue(store.save(record))
        val pServer = RecordingPServer(key).apply { failWrites = true }

        val result = port(key, pServer, store).execute(restoreMutation(origin = 0, applied = 1))

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertEquals(VendorModeOriginLoad.Held(record), store.load())
        assertEquals("1", key.value)
    }

    @Test
    fun `a vendor mutation is never deleted and never mistaken for fan_mode`() {
        val key = Key("1")
        val pServer = RecordingPServer(key)
        val delete = applyMutation(value = 1, origin = 0).copy(
            kind = LogicalMutationKind.SETTINGS_DELETE,
            value = null,
            expectPresent = false,
            capturedSnapshotAuthority = true,
        )
        val result = port(key, pServer, newStore()).execute(delete)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(pServer.commands.isEmpty())
        // And a restore without its context cannot proceed on a bare value.
        val bare = restoreMutation(origin = 0, applied = 1).copy(vendorModeContext = null)
        assertEquals(CheckedCommandStatus.FAILED, port(key, pServer, newStore()).execute(bare).status)
        assertTrue(pServer.commands.isEmpty())
    }

    @Test
    fun `the restore mutation is built from the snapshot origin and the journalled plan`() {
        val plan = plan(vendorMode = 2, fanMode = null, cpu = mapOf(0 to 1_000_000))
        val snapshot = snapshot(cpuNode, vendorNode(0))
        val record = TransactionJournalRecord(
            generation = 3L,
            requestId = "req",
            transactionId = "tx-9",
            hardwareFingerprint = "fp",
            deviceProfile = "generic-test-profile",
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1L,
            requestDeadline = 2L,
            leaseDeadline = 3L,
            bootIdentity = "boot-1",
            snapshot = snapshot,
            expectedTopologySha256 = "a".repeat(64),
            snapshotSha256 = "b".repeat(64),
            lastCompletedMutationBoundary = 2,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = listOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID, "cpu:0:scaling_max_freq"),
            appliedMutationProjection = emptyList(),
        )
        val port = port(Key("2"), RecordingPServer(Key("2")), newStore())

        val restore = port.restoreMutations(record)

        // Literal reverse of the apply order: compute first, the vendor mode last.
        assertEquals(
            listOf("cpu:0:scaling_max_freq", HardwareSnapshot.VENDOR_MODE_LOGICAL_ID),
            restore.map { it.logicalId },
        )
        val vendor = restore.single { it.axis == TuningAxis.VENDOR_MODE }
        assertEquals(LogicalMutationKind.SETTINGS_PUT, vendor.kind)
        assertEquals(0, vendor.value)
        assertTrue(vendor.capturedSnapshotAuthority)
        assertEquals(0, vendor.vendorModeContext?.origin)
        assertEquals(2, vendor.vendorModeContext?.applied)
        assertEquals("tx-9", vendor.vendorModeContext?.transactionId)
    }

    // ---- readback: the direct Settings read ---------------------------------------------------------

    @Test
    fun `apply readback is the direct settings read equalling the written mode`() {
        val key = Key("1")
        val state = statePort(probe(key))
        val mutation = applyMutation(value = 1, origin = 0)

        assertTrue(state.readback(mutation))
        key.value = "2"
        assertFalse(state.readback(mutation))
        key.value = null
        assertFalse(state.readback(mutation))
        key.value = " 1\n"
        assertTrue("the read is trimmed like the probe's", state.readback(mutation))
    }

    @Test
    fun `restore readback passes for the origin and for a player's choice but not for Mimir's own value`() {
        val key = Key("0")
        val state = statePort(probe(key))
        val restore = restoreMutation(origin = 0, applied = 1)

        assertTrue("origin is live", state.readback(restore))
        key.value = "2"
        assertTrue("the player's mode stands and is not a failed restore", state.readback(restore))
        key.value = "1"
        assertFalse("Mimir's own value still live means the write did not land", state.readback(restore))
        key.value = null
        assertFalse(state.readback(restore))
    }

    @Test
    fun `a restore whose applied mode is the origin reads back as restored while the key sits on it`() {
        // Quiet at the player's stock mode 0: the apply wrote nothing, the restore is "already at
        // origin", and the key legitimately still reads the value Mimir "applied". Asking only "is it
        // not Mimir's value" would call that restore failed forever.
        val key = Key("0")
        val state = statePort(probe(key))
        val restore = restoreMutation(origin = 0, applied = 0)

        assertTrue("origin == applied == live is a landed restore", state.readback(restore))
        key.value = "2"
        assertTrue("the player's own mode stands", state.readback(restore))
        key.value = null
        assertFalse(state.readback(restore))
    }

    @Test
    fun `a windowed verification never fails on the player's mode, it only reports it`() {
        val key = Key("2") // the player has since moved off Mimir's 1
        val state = statePort(probe(key))
        val vendorOnly = plan(vendorMode = 1, fanMode = null, cpu = emptyMap())

        val verification = state.verifyAppliedPlan(vendorOnly, listOf(applyMutation(value = 1, origin = 0)))

        assertTrue(verification.failures.joinToString(), verification.ok)
        // And the snapshot verifier skips the player-owned node entirely.
        assertTrue(state.verifySnapshotPlan(snapshot(vendorNode(0))).ok)
    }

    @Test
    fun `the vendor mode is player-owned for verification and the GPU unholdable set is untouched`() {
        assertFalse(vetoesWindowedVerification(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID))
        assertEquals(setOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID), PLAYER_OWNED_AXIS_LOGICAL_IDS)
        listOf("cpu:0:scaling_max_freq", HardwareSnapshot.FAN_LOGICAL_ID).forEach {
            assertTrue(it, vetoesWindowedVerification(it))
        }
        assertFalse(
            "the sibling set must not have absorbed the player-owned axis",
            HardwareSnapshot.VENDOR_MODE_LOGICAL_ID in UNHOLDABLE_AXIS_LOGICAL_IDS,
        )
    }

    // ---- RestoreMutationOrder ----------------------------------------------------------------------

    private fun orderRecord(fanValue: Int): TransactionJournalRecord {
        val fan = PersistedAppliedMutation(
            logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
            axis = TuningAxis.FAN,
            canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
            kind = LogicalMutationKind.SETTINGS_PUT,
            value = fanValue,
            expectPresent = true,
        )
        val vendor = PersistedAppliedMutation(
            logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
            axis = TuningAxis.VENDOR_MODE,
            canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
            kind = LogicalMutationKind.SETTINGS_PUT,
            value = 1,
            expectPresent = true,
        )
        val cpu = PersistedAppliedMutation(
            logicalId = "cpu:0:scaling_max_freq",
            axis = TuningAxis.CPU,
            canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            kind = LogicalMutationKind.SYSFS_WRITE,
            value = 1_000_000,
            expectPresent = true,
        )
        val projection = listOf(fan, vendor, cpu)
        return TransactionJournalRecord(
            generation = 1L,
            requestId = "r",
            transactionId = "t",
            hardwareFingerprint = "fp",
            deviceProfile = "p",
            planHash = "h",
            profileId = "balanced",
            targetPackage = "pkg",
            phase = TransactionPhase.APPLIED,
            creationTime = 1L,
            requestDeadline = 2L,
            leaseDeadline = 3L,
            bootIdentity = "b",
            snapshot = snapshot(cpuNode, fanNode, vendorNode(0)),
            expectedTopologySha256 = "x",
            snapshotSha256 = "y",
            lastCompletedMutationBoundary = 3,
            appliedPlan = PersistedTuningPlan.from(plan(vendorMode = 1)),
            applyOrderLogicalIds = projection.map { it.logicalId },
            appliedMutationProjection = projection,
        )
    }

    @Test
    fun `a tw06 journal (fan vendor compute) still restores compute vendor fan`() {
        assertEquals(
            listOf("cpu:0:scaling_max_freq", HardwareSnapshot.VENDOR_MODE_LOGICAL_ID, HardwareSnapshot.FAN_LOGICAL_ID),
            RestoreMutationOrder.logicalIds(orderRecord(fanValue = TuningPlanner.FAN_SMART)),
        )
    }

    @Test
    fun `a tw06 manual fan journal releases the fan first and puts it back once more after the vendor mode`() {
        assertEquals(
            listOf(
                HardwareSnapshot.FAN_LOGICAL_ID,
                "cpu:0:scaling_max_freq",
                HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
                HardwareSnapshot.FAN_LOGICAL_ID,
            ),
            RestoreMutationOrder.logicalIds(orderRecord(fanValue = TuningPlanner.FAN_MANUAL)),
        )
    }

    // ---- the probe: presence, supportedAxes, the safety switch, capture ---------------------------------

    @Test
    fun `the axis is present when the key reads a known mode on a pserver device -- no model or family involved`() {
        listOf("0", "1", "2").forEach { raw ->
            val capabilities = probe(Key(raw)).probe()

            assertTrue(raw, TuningAxis.VENDOR_MODE in capabilities.supportedAxes)
            assertTrue(raw, capabilities.vendorPerformanceModePresent)
        }
        // The fixture's manufacturer under Robolectric is not a handheld vendor's; presence still holds.
        assertFalse(probe(Key("1")).probe().manufacturer.equals("ayn", ignoreCase = true))
    }

    @Test
    fun `the axis is absent for a missing, non-integer or out of range key, or without pserver`() {
        listOf<String?>(null, "", "abc", "3", "-1", "9", "1.5").forEach { raw ->
            val capabilities = probe(Key(raw)).probe()
            assertFalse("raw=$raw", TuningAxis.VENDOR_MODE in capabilities.supportedAxes)
            assertFalse("raw=$raw", capabilities.vendorPerformanceModePresent)
        }
        val noPserver = probe(Key("1"), pServerAvailable = false).probe()
        assertFalse(TuningAxis.VENDOR_MODE in noPserver.supportedAxes)
        assertFalse(noPserver.vendorPerformanceModePresent)
    }

    @Test
    fun `the transport switch ships on now the Guardian can restore the key, and off still means no axis anywhere`() {
        assertTrue(
            "the Guardian restore entry exists (vmode-guardian), so the switch ships on",
            VENDOR_MODE_TRANSPORT_ENABLED,
        )

        // The switch is still honoured: off is the dark-shipping posture, absent on every device.
        val off = probe(Key("1"), enabled = false).probe()
        assertFalse(TuningAxis.VENDOR_MODE in off.supportedAxes)
        assertFalse(off.vendorPerformanceModePresent)
        // ...so the planner then plans no vendor mode, and the validator refuses a hand-built one.
        assertNull(TuningPlanner { _, _ -> true }.plan("balanced", "example.game", off)?.vendorMode)
        assertTrue(PlanTargetValidator.validate(plan(), off).any { "vendor-mode axis" in it })

        // A probe built WITHOUT naming the switch takes the shipped constant: the key reads a known mode
        // on a pserver device, so the axis is admitted -- capability, no model or family involved.
        val shipped = HardwareProbe(
            context = RuntimeEnvironment.getApplication(),
            reader = SystemReader(
                privilegedNodes = null,
                directRead = { null },
                directDirectories = { _, _ -> emptyList() },
            ),
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ -> PServerTransactResult(accepted = false, output = null) },
            ),
            sleeper = {},
            pServerAvailable = { true },
            vendorModeSettingReader = { "1" },
        ).probe()
        assertTrue(TuningAxis.VENDOR_MODE in shipped.supportedAxes)
        assertTrue(shipped.vendorPerformanceModePresent)
        assertEquals(
            TuningPlanner.VENDOR_MODE_PERFORMANCE,
            TuningPlanner { _, _ -> true }.plan("balanced", "example.game", shipped)?.vendorMode,
        )
        assertTrue(PlanTargetValidator.validate(plan(), shipped).none { "vendor-mode axis" in it })

        // And a device whose key is missing is unchanged by the switch being on.
        val keyless = HardwareProbe(
            context = RuntimeEnvironment.getApplication(),
            reader = SystemReader(
                privilegedNodes = null,
                directRead = { null },
                directDirectories = { _, _ -> emptyList() },
            ),
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ -> PServerTransactResult(accepted = false, output = null) },
            ),
            sleeper = {},
            pServerAvailable = { true },
            vendorModeSettingReader = { null },
        ).probe()
        assertFalse(TuningAxis.VENDOR_MODE in keyless.supportedAxes)
        assertNull(TuningPlanner { _, _ -> true }.plan("balanced", "example.game", keyless)?.vendorMode)
    }

    private fun vendorOnlyPlan(vendorMode: Int = 1) =
        plan(vendorMode = vendorMode, fanMode = null, cpu = emptyMap())

    @Test
    fun `capture takes the live mode as the origin when no record is held`() {
        val key = Key("0")
        val probe = probe(key)
        val outcome = probe.captureForPlan(vendorOnlyPlan(), probe.probe())

        assertTrue(outcome.failures.joinToString(), outcome.ok)
        val node = outcome.snapshot!!.nodes.single()
        assertEquals(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID, node.logicalId)
        assertEquals(TuningAxis.VENDOR_MODE, node.axis)
        assertEquals(HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH, node.canonicalPath)
        assertEquals(0, node.originalValue)
        assertTrue(node.present)
        assertNull(node.originalMode)
        assertEquals(0, outcome.snapshot!!.vendorMode)
        assertTrue(outcome.snapshot!!.vendorModePresent)
    }

    @Test
    fun `capture after a frozen daemon carries the stored origin, never Mimir's own applied value`() {
        // The 2026-09-19 trap, for a settings key: Mimir wrote 2 over the player's 0 and the daemon
        // froze. A re-arm reads 2 live; stock is 0.
        val store = newStore()
        assertTrue(store.save(VendorModeOriginRecord(origin = 0, applied = 2, transactionId = "tx-1")))
        val key = Key("2")
        val probe = probe(key, store = store)

        val node = probe.captureForPlan(vendorOnlyPlan(), probe.probe()).snapshot!!.nodes.single()

        assertEquals("the restore point is the player's mode", 0, node.originalValue)
        // Capture is observation only: it saved nothing and cleared nothing.
        assertEquals(
            VendorModeOriginLoad.Held(VendorModeOriginRecord(origin = 0, applied = 2, transactionId = "tx-1")),
            store.load(),
        )
    }

    @Test
    fun `capture retires a record the player has moved past and takes the live value as the origin`() {
        val store = newStore()
        assertTrue(store.save(VendorModeOriginRecord(origin = 0, applied = 2, transactionId = "tx-1")))
        val probe = probe(Key("1"), store = store) // the player moved 2 -> 1 in the vendor UI

        val node = probe.captureForPlan(vendorOnlyPlan(), probe.probe()).snapshot!!.nodes.single()

        assertEquals(1, node.originalValue)
    }

    @Test
    fun `capture treats a corrupt record as none and refuses an unreadable or unknown live mode`() {
        val store = newStore()
        val dir = File(Files.createTempDirectory("vmode-corrupt").toFile(), "store").apply { mkdirs() }
        File(dir, "origin.json").writeText("not json")
        val corruptStore = VendorModeOriginStore(dir)
        assertEquals(VendorModeOriginLoad.Corrupt, corruptStore.load())
        val corrupt = probe(Key("1"), store = corruptStore)
        assertEquals(1, corrupt.captureForPlan(vendorOnlyPlan(), corrupt.probe()).snapshot!!.nodes.single().originalValue)

        listOf<String?>(null, "abc", "3").forEach { raw ->
            val key = Key("1")
            val probe = probe(key, store = store)
            val capabilities = probe.probe()
            key.value = raw // the key went bad between the probe and the capture
            val outcome = probe.captureForPlan(vendorOnlyPlan(), capabilities)
            assertFalse("raw=$raw", outcome.ok)
            assertNull("raw=$raw", outcome.snapshot)
        }
    }

    @Test
    fun `capture refuses a vendor-mode plan the live capabilities do not admit`() {
        val probe = probe(Key("1"))
        val unadmitted = probe.probe().copy(supportedAxes = emptySet(), vendorPerformanceModePresent = false)

        val outcome = probe.captureForPlan(vendorOnlyPlan(), unadmitted)

        assertFalse(outcome.ok)
        assertTrue(outcome.failures.any { "vendor-mode axis" in it })
    }

    @Test
    fun `the supervisor sample reads the mode only where the axis is admitted`() {
        val key = Key("2")
        val probe = probe(key)
        val admitted = probe.probe()

        assertEquals(2, probe.supervisorTelemetry(admitted).vendorPerformanceMode)
        key.value = null
        assertNull("an unread mode is null, never a guessed value", probe.supervisorTelemetry(admitted).vendorPerformanceMode)

        key.value = "2"
        val withoutAxis = admitted.copy(supportedAxes = admitted.supportedAxes - TuningAxis.VENDOR_MODE)
        assertNull(probe.supervisorTelemetry(withoutAxis).vendorPerformanceMode)
    }

    // ---- validators, the journal and the plan identity ------------------------------------------------

    @Test
    fun `plan target validation bounds the mode and requires an admitted axis on a pserver device`() {
        assertTrue(PlanTargetValidator.validate(plan(), caps()).isEmpty())

        assertTrue(PlanTargetValidator.validate(plan(vendorMode = 3), caps()).any { "not a known Mimir mode" in it })
        assertTrue(
            PlanTargetValidator.validate(plan(), caps(axes = setOf(TuningAxis.CPU, TuningAxis.FAN)))
                .any { "vendor-mode axis" in it },
        )
        assertTrue(
            PlanTargetValidator.validate(plan(), caps(pServerAvailable = false)).any { "vendor-mode axis" in it },
        )
        // Stock names no mode and is never held to the axis.
        assertTrue(PlanTargetValidator.validate(plan(vendorMode = null), caps(axes = setOf(TuningAxis.CPU, TuningAxis.FAN))).isEmpty())
    }

    @Test
    fun `the snapshot validators accept a vendor node and refuse a malformed one`() {
        fun failures(node: TopologyNodeState) = SnapshotCompleteness.validate(snapshot(node))

        assertTrue(failures(vendorNode(0)).joinToString(), failures(vendorNode(0)).isEmpty())
        assertTrue(failures(vendorNode(3)).isNotEmpty())
        assertTrue(failures(vendorNode(0).copy(canonicalPath = "settings:system:fan_mode")).isNotEmpty())
        assertTrue(failures(vendorNode(0).copy(originalMode = "644")).isNotEmpty())
        assertTrue(failures(vendorNode(null)).isNotEmpty())
    }

    @Test
    fun `the projection validator holds the vendor entry to the plan`() {
        val base = journalRecord(vendorMode = 1)
        assertTrue(AppliedMutationProjectionValidator.validate(base).joinToString(), AppliedMutationProjectionValidator.validate(base).isEmpty())

        val entry = base.appliedMutationProjection.single()
        // value differs from the plan
        assertTrue(
            AppliedMutationProjectionValidator.validate(
                base.copy(appliedMutationProjection = listOf(entry.copy(value = 2))),
            ).any { "vendor mode value mismatch" in it },
        )
        // wrong kind
        assertTrue(
            AppliedMutationProjectionValidator.validate(
                base.copy(appliedMutationProjection = listOf(entry.copy(kind = LogicalMutationKind.SYSFS_WRITE))),
            ).any { "kind invalid" in it },
        )
        // a plan that drives the mode with no projection entry is refused (the snapshot node is gone too)
        val withoutEntry = journalRecord(vendorMode = 1).let { record ->
            record.copy(
                snapshot = record.snapshot.copy(nodes = listOf(fanNode)),
                applyOrderLogicalIds = listOf(fanNode.logicalId),
                appliedMutationProjection = listOf(
                    PersistedAppliedMutation(
                        logicalId = fanNode.logicalId,
                        axis = TuningAxis.FAN,
                        canonicalPath = fanNode.canonicalPath,
                        kind = LogicalMutationKind.SETTINGS_PUT,
                        value = TuningPlanner.FAN_SMART,
                        expectPresent = true,
                    ),
                ),
                appliedPlan = record.appliedPlan!!.copy(fanMode = TuningPlanner.FAN_SMART),
            )
        }
        assertTrue(
            AppliedMutationProjectionValidator.validate(withoutEntry).any { "vendor mode set mismatch" in it },
        )
        // an entry with no plan target
        val unplanned = base.copy(appliedPlan = base.appliedPlan!!.copy(vendorMode = null))
        assertTrue(
            AppliedMutationProjectionValidator.validate(unplanned).any { "without appliedPlan vendor target" in it },
        )
    }

    private fun journalRecord(vendorMode: Int?): TransactionJournalRecord {
        val journalPlan = TuningPlan(
            profileId = "balanced",
            packageName = "example.game",
            cpuMaxKhz = emptyMap(),
            gpuMaxPowerLevel = null,
            fanMode = if (vendorMode == null) TuningPlanner.FAN_SMART else null,
            calibrated = true,
            vendorMode = vendorMode,
        )
        val node = if (vendorMode != null) vendorNode(0) else fanNode
        val snapshot = snapshot(node)
        val hashes = checkNotNull(SnapshotIntegrity.bindHashes(journalPlan, caps(), snapshot, identity))
        val projection = listOf(
            if (vendorMode != null) {
                PersistedAppliedMutation(
                    logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
                    axis = TuningAxis.VENDOR_MODE,
                    canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
                    kind = LogicalMutationKind.SETTINGS_PUT,
                    value = vendorMode,
                    expectPresent = true,
                )
            } else {
                PersistedAppliedMutation(
                    logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                    axis = TuningAxis.FAN,
                    canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                    kind = LogicalMutationKind.SETTINGS_PUT,
                    value = TuningPlanner.FAN_SMART,
                    expectPresent = true,
                )
            },
        )
        return TransactionJournalRecord(
            generation = 1L,
            requestId = "req-1",
            transactionId = "tx-1",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
            planHash = PlanHash.of(journalPlan),
            profileId = journalPlan.profileId,
            targetPackage = journalPlan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1_000L,
            requestDeadline = 31_000L,
            leaseDeadline = 90_000L,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = 1,
            appliedPlan = PersistedTuningPlan.from(journalPlan),
            applyOrderLogicalIds = projection.map { it.logicalId },
            appliedMutationProjection = projection,
        )
    }

    @Test
    fun `the journal round-trips a plan that drives the vendor mode and its plan hash still checks`() {
        val record = journalRecord(vendorMode = 2)
        val encoded = AtomicFileTransactionJournal.encodeJournal(record)

        val appliedPlan = JSONObject(encoded).getJSONObject("appliedPlan")
        assertEquals(2, appliedPlan.getInt("vendorMode"))

        val decoded = (AtomicFileTransactionJournal.decodeJournal(encoded) as JournalLoadResult.Loaded).record
        assertEquals(record, decoded)
        assertEquals(2, decoded.appliedPlan?.vendorMode)
        // The hash was struck WITH the mode; if the journal dropped it, this identity would not reconstruct.
        assertEquals(record.planHash, PlanHash.ofPersisted(checkNotNull(decoded.appliedPlan)))
        assertTrue(record.planHash != PlanHash.ofPersisted(decoded.appliedPlan!!.copy(vendorMode = null)))
        assertEquals(HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH, decoded.snapshot.nodes.single().canonicalPath)
    }

    @Test
    fun `a journal for a plan without the axis is byte-identical to what an older build wrote and loads unchanged`() {
        val record = journalRecord(vendorMode = null)
        val encoded = AtomicFileTransactionJournal.encodeJournal(record)

        assertFalse("no vendorMode key may appear for a plan without the axis", JSONObject(encoded).getJSONObject("appliedPlan").has("vendorMode"))
        val decoded = (AtomicFileTransactionJournal.decodeJournal(encoded) as JournalLoadResult.Loaded).record
        assertEquals(record, decoded)
        assertNull(decoded.appliedPlan?.vendorMode)
        // The pre-axis identity: a plan with no mode hashes exactly as it did before the field existed.
        assertEquals(record.planHash, PlanHash.ofPersisted(checkNotNull(decoded.appliedPlan)))
    }

    @Test
    fun `a journal whose plan names the mode but whose key was stripped does not load as a valid record`() {
        val encoded = AtomicFileTransactionJournal.encodeJournal(journalRecord(vendorMode = 1))
        val stripped = JSONObject(encoded).apply { getJSONObject("appliedPlan").remove("vendorMode") }

        val loaded = AtomicFileTransactionJournal.decodeJournal(stripped.toString())

        // Either the codec refuses it (the projection no longer matches the plan) or, if it loads, the
        // mode is gone and the plan hash no longer reconstructs -- never a silently valid record.
        if (loaded is JournalLoadResult.Loaded) {
            assertNull(loaded.record.appliedPlan?.vendorMode)
            assertTrue(PlanHash.ofPersisted(loaded.record.appliedPlan!!) != loaded.record.planHash)
        } else {
            assertTrue(loaded is JournalLoadResult.Corrupt)
        }
    }
}
