package com.gruning.mimir.tweaker

import android.provider.Settings
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The AYN vendor's fan-and-mode coupling (policy 2026-10-13, "mode first, then our fan"),
 * exercised through the REAL [PServerHardwareCommandPort], [AndroidHardwareStatePort], [HardwareProbe],
 * [RestoreGuardianRunnerEngine] and [GuardianProcessHardware] against one fake handheld.
 *
 * The fake emulates what the Thor's logcat showed on 2026-10-13: `Settings.System` holds
 * `performance_mode`, `fan_mode` and `is_quick_set_performance_and_fan_enable`; when the flag reads "1",
 * a CHANGE of `performance_mode` makes "SystemUI" rewrite `fan_mode` from its own per-mode memory after a
 * configurable delay (0 ms, a few hundred ms, or never). The vendor's Smart loop regulates duty only while
 * `fan_mode` is 4. Settings are Robolectric's own provider, so the probe's production reads are the ones
 * under test; the clock is fake and every wait goes through the ports' `sleeper` seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class VendorFanCouplingTest {

    // ---- the fake handheld ----------------------------------------------------------------------

    private val identity = SysfsCanonicalResolver { it }
    private val cpuPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
    private val cpuMinPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"

    private inner class Device(
        mode: Int,
        fan: Int,
        flag: String?,
        /** What SystemUI writes to `fan_mode` after a change TO this mode; absent = it does not react. */
        private val systemUiMemory: Map<Int, Int>,
        private val reactionDelayMs: Long = 0L,
    ) {
        val app = RuntimeEnvironment.getApplication()
        private val resolver = app.contentResolver
        var clock = 5_000L
        val sleeps = mutableListOf<Long>()
        val commands = mutableListOf<String>()
        private var pendingRewrite: Pair<Long, Int>? = null
        val smartDuty = 18_000
        val state = mutableMapOf(
            HardwareProbe.FAN_DUTY to smartDuty.toString(),
            HardwareProbe.FAN_PERIOD to "50000",
            HardwareProbe.FAN_SPEED to "2000",
            cpuPath to "2000000",
            cpuMinPath to "300000",
        )
        private val modes = mutableMapOf<String, String>()
        val store = VendorModeOriginStore(File(Files.createTempDirectory("vfan").toFile(), "store"))
        val handoffs = ManualFanHandoffStore()
        private var handoffIdentity: ManualFanHandoffIdentity? = null

        init {
            Settings.System.putString(resolver, "performance_mode", mode.toString())
            Settings.System.putString(resolver, "fan_mode", fan.toString())
            if (flag != null) Settings.System.putString(resolver, "is_quick_set_performance_and_fan_enable", flag)
        }

        fun mode(): Int? = Settings.System.getString(resolver, "performance_mode")?.toIntOrNull()
        fun fan(): Int? = Settings.System.getString(resolver, "fan_mode")?.toIntOrNull()
        fun flag(): String? = Settings.System.getString(resolver, "is_quick_set_performance_and_fan_enable")

        /** The player's finger in the vendor UI: the tile writes the mode and SystemUI couples as usual. */
        fun playerSetsMode(value: Int) = putMode(value)

        private fun putMode(value: Int) {
            val before = mode()
            Settings.System.putString(resolver, "performance_mode", value.toString())
            if (before == value || flag() != "1") return
            val coupled = systemUiMemory[value] ?: return
            if (reactionDelayMs == 0L) {
                Settings.System.putString(resolver, "fan_mode", coupled.toString())
            } else {
                pendingRewrite = (clock + reactionDelayMs) to coupled
            }
        }

        fun sleep(ms: Long) {
            sleeps += ms
            clock += ms
            pendingRewrite?.let { (dueAt, value) ->
                if (clock >= dueAt) {
                    Settings.System.putString(resolver, "fan_mode", value.toString())
                    pendingRewrite = null
                }
            }
            // The vendor's Smart loop (`FanBase: mSmartAction`, 11 ms after the tile line on the Thor):
            // it drives duty by its curve only while the fan is Smart, exactly as the module documents.
            if (fan() == TuningPlanner.FAN_SMART) state[HardwareProbe.FAN_DUTY] = smartDuty.toString()
        }

        val pServer = object : PServerClient() {
            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commands += command
                command.split("&&").map(String::trim).forEach { step ->
                    Regex("^settings put system performance_mode (\\d+)$").matchEntire(step)
                        ?.let { putMode(it.groupValues[1].toInt()) }
                    Regex("^settings put system fan_mode (\\d+)$").matchEntire(step)
                        ?.let { Settings.System.putString(resolver, "fan_mode", it.groupValues[1]) }
                    Regex("^echo (\\d+) ?> ?(\\S+)$").matchEntire(step)
                        ?.let { state[it.groupValues[2]] = it.groupValues[1] }
                    Regex("^chmod (\\d+) (\\S+)$").matchEntire(step)
                        ?.let { modes[it.groupValues[2]] = it.groupValues[1] }
                }
                return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
            }
        }

        val reader = SystemReader(
            privilegedNodes = null,
            directRead = { path -> if (path == HardwareProbe.BOOT_ID_PATH) "boot-1" else state[path] },
            directExists = { path -> path in state },
            directDirectories = { _, _ -> emptyList() },
        )

        val probe = HardwareProbe(
            context = app,
            reader = reader,
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ -> PServerTransactResult(accepted = false, output = null) },
            ),
            sleeper = {},
            pServerAvailable = { true },
            vendorModeOriginStore = store,
            vendorModeTransportEnabled = true,
        )

        val caps = HardwareCapabilities(
            status = CapabilityStatus.SUPPORTED,
            manufacturer = "AYN",
            model = "Thor",
            socModel = "SM8550",
            buildFingerprint = "thor/test",
            deviceProfileId = HandheldDeviceProfiles.THOR_PROFILE_ID,
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.FAN, TuningAxis.VENDOR_MODE),
            pServerAvailable = true,
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
            vendorPerformanceModePresent = true,
        )

        val commandsPort = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader,
            capabilities = { caps },
            fanModeRaw = probe::fanModeRaw,
            sleeper = ::sleep,
            manualFanHandoffStore = handoffs,
            manualFanHandoffIdentity = { handoffIdentity },
            elapsedRealtimeMs = { clock },
            vendorModeRaw = probe::vendorModeRaw,
            vendorModeOrigin = store,
            sysfsResolver = identity,
            vendorFanCouplingRaw = probe::vendorFanCouplingRaw,
        )

        val statePort = AndroidHardwareStatePort(
            probe = probe,
            capabilities = { caps },
            posix = PosixNodeMetadataReader { path -> PosixNodeMetadata(modes[path] ?: "644", 0, 0) },
            sleeper = ::sleep,
            sysfsResolver = identity,
        )

        /** What the service does before a MANUAL apply: a fresh handoff baseline for the profile. */
        fun armManualHandoff(profileId: String) {
            val source = ManualFanHandoffSource.Session(1, "source-tx")
            val baseline = ManualFanHandoffBaseline(
                profileId = profileId,
                ownerRequestId = "owner",
                source = source,
                observedDuty = smartDuty,
                entryDuty = smartDuty,
                period = 50_000,
                cpuSafetyTempC = 45,
                minimumDutyPercent = 0,
                capturedElapsedRealtimeMs = clock - 100,
            )
            handoffs.clear()
            assertTrue(handoffs.prepare(baseline))
            handoffIdentity = ManualFanHandoffIdentity(profileId, "owner", source)
        }

        fun snapshot(cpu: Boolean = true) = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "fp",
            bootIdentity = "boot-1",
            nodes = listOfNotNull(
                TopologyNodeState(
                    logicalId = "cpu:0:scaling_max_freq",
                    axis = TuningAxis.CPU,
                    canonicalPath = cpuPath,
                    originalValue = state[cpuPath]!!.toInt(),
                    originalMode = "644",
                    originalUid = 0,
                    originalGid = 0,
                    present = true,
                ).takeIf { cpu },
                TopologyNodeState(
                    logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                    axis = TuningAxis.FAN,
                    canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                    originalValue = fan(),
                    originalMode = null,
                    originalUid = null,
                    originalGid = null,
                    present = fan() != null,
                ),
                TopologyNodeState(
                    logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
                    axis = TuningAxis.VENDOR_MODE,
                    canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
                    originalValue = mode(),
                    originalMode = null,
                    originalUid = null,
                    originalGid = null,
                    present = mode() != null,
                ),
            ),
        )

        fun vendorPuts(): List<String> = commands.filter { "performance_mode" in it }
        fun fanPuts(): List<String> = commands.filter { "fan_mode" in it }
        fun settlePolls(): Int = sleeps.count { it == VendorFanCouplingSettle.POLL_MS }
        fun couplingLines(): List<String> =
            ShadowLog.getLogsForTag(VendorFanCouplingSettle.TAG).map { it.msg }.filter { it.startsWith("VENDOR_FAN_COUPLING") }
    }

    private fun plan(profileId: String, vendorMode: Int?, fanMode: Int?, cpu: Boolean = true) = TuningPlan(
        profileId = profileId,
        packageName = "example.game",
        cpuMaxKhz = if (cpu) mapOf(0 to 1_000_000) else emptyMap(),
        gpuMaxPowerLevel = null,
        fanMode = fanMode,
        calibrated = true,
        vendorMode = vendorMode,
    )

    private data class Applied(
        val ok: Boolean,
        val detail: String,
        val planned: List<LogicalMutation>,
        val snapshot: HardwareSnapshot,
        val plan: TuningPlan,
    )

    /** The coordinator's apply loop, mutation by mutation, then the windowed verify -- at the port level. */
    private fun Device.applyPlan(plan: TuningPlan, cpu: Boolean = true): Applied {
        val snapshot = snapshot(cpu)
        val planned = commandsPort.plannedMutations(plan, snapshot)
        for (mutation in planned) {
            val executed = commandsPort.execute(mutation)
            if (executed.status != CheckedCommandStatus.CONFIRMED) {
                return Applied(false, "Mutation ${mutation.logicalId}: ${executed.status} ${executed.detail}", planned, snapshot, plan)
            }
            if (!statePort.readback(mutation)) {
                return Applied(false, "Readback mismatch for ${mutation.logicalId}", planned, snapshot, plan)
            }
        }
        val verification = statePort.verifyAppliedPlan(plan, planned)
        return Applied(verification.ok, verification.failures.joinToString("; "), planned, snapshot, plan)
    }

    private fun Device.record(applied: Applied, applyOrder: List<LogicalMutation> = applied.planned) = TransactionJournalRecord(
        generation = 1L,
        requestId = "r",
        transactionId = "t",
        hardwareFingerprint = "fp",
        deviceProfile = caps.deviceProfileId,
        planHash = PlanHash.of(applied.plan),
        profileId = applied.plan.profileId,
        targetPackage = applied.plan.packageName,
        phase = TransactionPhase.APPLIED,
        creationTime = 1L,
        requestDeadline = 2L,
        leaseDeadline = 3L,
        bootIdentity = "boot-1",
        snapshot = applied.snapshot,
        expectedTopologySha256 = "x",
        snapshotSha256 = "y",
        lastCompletedMutationBoundary = applyOrder.size,
        appliedPlan = PersistedTuningPlan.from(applied.plan),
        applyOrderLogicalIds = applyOrder.map { it.logicalId },
        appliedMutationProjection = applyOrder.map { PersistedAppliedMutation.from(it) },
    )

    private data class Restored(val ok: Boolean, val detail: String, val order: List<String>)

    /** The coordinator's `attemptFullRestore` loop at the port level: every mutation, its readback, the windowed verify. */
    private fun Device.restore(record: TransactionJournalRecord): Restored {
        val mutations = commandsPort.restoreMutations(record)
        for (mutation in mutations) {
            val executed = commandsPort.execute(mutation)
            if (executed.status != CheckedCommandStatus.CONFIRMED) {
                return Restored(false, "Restore mutation ${mutation.logicalId}: ${executed.status} ${executed.detail}", mutations.map { it.logicalId })
            }
            if (!statePort.readback(mutation)) {
                return Restored(false, "Restore readback mismatch for ${mutation.logicalId}", mutations.map { it.logicalId })
            }
        }
        val verification = statePort.verifySnapshotPlan(record.snapshot)
        return Restored(verification.ok, verification.failures.joinToString("; "), mutations.map { it.logicalId })
    }

    private val sha = { value: String -> GuardianCanonicalCodec.sha256(value.toByteArray()) }

    /** The signed envelope's entries, rendered the way the daemon's envelope port renders them (fan and vendor only). */
    private fun Device.envelope(applied: Applied): RestoreGuardianEnvelope {
        val nodes = applied.snapshot.nodes.associateBy { it.logicalId }
        val entries = RestoreMutationOrder.indexed(applied.planned).map { indexed ->
            val mutation = indexed.value
            val stock = nodes.getValue(mutation.logicalId)
            when (mutation.axis) {
                TuningAxis.FAN -> RestoreGuardianFanPutEntry(
                    applyOrdinal = indexed.index,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(requireNotNull(stock.originalValue)),
                    rendererAppliedIntermediates = listOf(requireNotNull(mutation.value)),
                )
                TuningAxis.VENDOR_MODE -> RestoreGuardianVendorModePutEntry(
                    applyOrdinal = indexed.index,
                    origin = requireNotNull(stock.originalValue),
                    applied = requireNotNull(mutation.value),
                )
                else -> error("the Guardian rig is fan and vendor only: ${mutation.logicalId}")
            }
        }
        return RestoreGuardianEnvelope(
            helperBuildIdentity = "helper.1",
            tweakerCertificateSha256 = sha("certificate"),
            hardwareFingerprint = "hardware.1",
            deviceProfile = caps.deviceProfileId,
            bootIdentity = "boot-1",
            generation = 7,
            requestId = "request.1",
            transactionId = "transaction.1",
            taskNonce = "a".repeat(32),
            topologySha256 = sha("topology"),
            snapshotSha256 = sha("snapshot"),
            planSha256 = sha("plan"),
            hardDeadlineElapsedRealtimeMs = 10_000_000,
            restoreEntries = entries,
        )
    }

    private data class GuardianOutcome(
        val state: GuardianRunnerLifecycleState,
        val executed: Boolean,
        val intents: List<String>,
        val finalFanIntents: Int,
        val described: String,
    )

    /** What `RestoreGuardianProcessService.restoreActive` does, step for step, in the :guardian process. */
    private fun Device.guardianRestore(envelope: RestoreGuardianEnvelope): GuardianOutcome {
        val sessionKey = GuardianSessionPublicKey.fromPublicKey(
            KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public,
        )
        val hardware = GuardianProcessHardware(
            probe = probe,
            reader = reader,
            pServer = pServer,
            now = { clock },
            fanModeRaw = probe::fanModeRaw,
            vendorFanCouplingRaw = probe::vendorFanCouplingRaw,
            sleeper = ::sleep,
            capabilities = { caps },
            sysfsResolver = identity,
        )
        val engine = RestoreGuardianRunnerEngine(
            GuardianReceiptAuthenticationStrategy { _, _ -> byteArrayOf(1) },
            GuardianReceiptOutputPort { GuardianReceiptOutputDisposition.CONFIRMED },
            sessionKey,
        )
        engine.stage(GuardianEnvelopeVerificationOutcome.Verified(envelope), 1, envelope.bootIdentity)
        engine.admitReady(2)
        engine.beginWatching(3)
        engine.ownerCancelled()
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(envelope)
        val evidence = GuardianRestoreFenceEvidence.SameBoot(FenceDiskState(true, identity, identity, true))
        val observed = checkNotNull(hardware.observe(envelope.restoreEntries))
        val planned = engine.planRestore(envelope.bootIdentity, evidence, observed)
        check(planned.state == GuardianRunnerLifecycleState.RESTORING) { "restore not admitted: ${planned.state}" }
        val executed = hardware.execute(planned.restoreIntents)
        hardware.settleVendorOrigin(envelope.restoreEntries)
        val first = checkNotNull(hardware.readback(envelope.restoreEntries, persistentOnly = false))
        sleep(GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS)
        val second = checkNotNull(hardware.readback(envelope.restoreEntries, persistentOnly = false))
        val confirmed = engine.confirmStock(first, second)
        return GuardianOutcome(
            state = confirmed.state,
            executed = executed,
            intents = planned.restoreIntents.map { it.entry.logicalId },
            finalFanIntents = planned.restoreIntents.count { it.afterVendorMode },
            described = GuardianDoubleStockReadbackPolicy.describe(envelope.restoreEntries, first, second),
        )
    }

    private val fan = HardwareSnapshot.FAN_LOGICAL_ID
    private val vendor = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID
    private val cpu = "cpu:0:scaling_max_freq"

    // ---- apply: the mode first, then our fan ----------------------------------------------------------

    @Test
    fun `Balanced with a MANUAL fan holds on the Thor's own coupling -- SystemUI writes Smart over Smart and the entry still runs`() {
        // Check 1 of the drive: stock mode 0, the fan already at Smart from the service's hand-to-4,
        // SystemUI's memory for mode 1 is 4. Before the fix the fan was written first and the mode write
        // had SystemUI overwrite it: `MimirVerifyWindow failed=fan_mode expected 6 got 4`.
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(1 to 4, 0 to 1))
        device.armManualHandoff("balanced")

        val applied = device.applyPlan(plan("balanced", vendorMode = 1, fanMode = TuningPlanner.FAN_MANUAL))

        assertTrue(applied.detail, applied.ok)
        assertEquals(listOf(vendor, fan, cpu), applied.planned.map { it.logicalId })
        assertEquals(1, device.mode())
        assertEquals(TuningPlanner.FAN_MANUAL, device.fan())
        // The mode write came first, and the fan's manual entry only after SystemUI's reaction window.
        assertTrue(device.commands.indexOfFirst { "performance_mode 1" in it } < device.commands.indexOfFirst { "fan_mode 6" in it })
        // Smart over Smart is unobservable: the settle ran to its deadline and said so, once.
        assertEquals(VendorFanCouplingSettle.DEADLINE_POLLS, device.settlePolls())
        assertEquals(
            listOf("VENDOR_FAN_COUPLING flag=1 mode 0->1 fan pre=4 coupled=unchanged settledMs=timeout"),
            device.couplingLines(),
        )
    }

    @Test
    fun `Performance with a Smart fan target holds when SystemUI rewrites the fan a few hundred ms later, and the redundant fan write is still elided`() {
        // Stock fan 1 (measured stock). SystemUI answers mode 2 with fan 4 after 250 ms;
        // the fan target IS 4, so once the settle has seen it land the port's compare-before-write skips.
        val device = Device(mode = 0, fan = 1, flag = "1", systemUiMemory = mapOf(2 to 4, 0 to 1), reactionDelayMs = 250)

        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART))

        assertTrue(applied.detail, applied.ok)
        assertEquals(2, device.mode())
        assertEquals(TuningPlanner.FAN_SMART, device.fan())
        assertTrue("no fan_mode put at all: ${device.fanPuts()}", device.fanPuts().isEmpty())
        // 5 polls to see the change at 250 ms, then 6 unchanged: settled at 550 ms.
        assertEquals(VendorFanCouplingSettle.STABLE_POLLS + 5, device.settlePolls())
        assertEquals(
            listOf("VENDOR_FAN_COUPLING flag=1 mode 0->2 fan pre=1 coupled=4 settledMs=550"),
            device.couplingLines(),
        )
    }

    @Test
    fun `a coupled value that is not Smart re-enters Smart inside the transaction before the manual entry`() {
        // 18:51:36 on the Thor: a Tweaker write of mode 0 was answered with fan 1. The service's
        // pre-transaction hand-to-Smart is behind the mode write now, so the entry repeats it.
        val device = Device(mode = 1, fan = 4, flag = "1", systemUiMemory = mapOf(0 to 1, 1 to 4), reactionDelayMs = 100)
        device.armManualHandoff("quiet")

        val applied = device.applyPlan(plan("quiet", vendorMode = 0, fanMode = TuningPlanner.FAN_MANUAL))

        assertTrue(applied.detail, applied.ok)
        assertEquals(0, device.mode())
        assertEquals(TuningPlanner.FAN_MANUAL, device.fan())
        val puts = device.fanPuts()
        assertEquals("hand to Smart, then manual: $puts", 2, puts.size)
        assertTrue(puts[0], puts[0].endsWith("settings put system fan_mode ${TuningPlanner.FAN_SMART}") && "echo 50000" in puts[0])
        assertEquals("settings put system fan_mode ${TuningPlanner.FAN_MANUAL}", puts[1])
        assertEquals(
            listOf("VENDOR_FAN_COUPLING flag=1 mode 1->0 fan pre=4 coupled=1 settledMs=400"),
            device.couplingLines(),
        )
    }

    @Test
    fun `a SystemUI that never reacts costs the deadline once and the apply proceeds`() {
        val device = Device(mode = 0, fan = 1, flag = "1", systemUiMemory = emptyMap())

        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART))

        assertTrue(applied.detail, applied.ok)
        assertEquals(TuningPlanner.FAN_SMART, device.fan())
        assertEquals(listOf("settings put system fan_mode 4"), device.fanPuts())
        assertEquals(VendorFanCouplingSettle.DEADLINE_POLLS, device.settlePolls())
        assertEquals(
            listOf("VENDOR_FAN_COUPLING flag=1 mode 0->2 fan pre=1 coupled=unchanged settledMs=timeout"),
            device.couplingLines(),
        )
    }

    @Test
    fun `without the flag nothing waits -- only the order changed`() {
        listOf<String?>(null, "0", "true").forEach { flag ->
            ShadowLog.clear()
            val device = Device(mode = 0, fan = 1, flag = flag, systemUiMemory = mapOf(2 to 4))

            val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART))

            assertTrue("flag=$flag: ${applied.detail}", applied.ok)
            assertEquals("flag=$flag", listOf(vendor, fan, cpu), applied.planned.map { it.logicalId })
            assertEquals("flag=$flag", 0, device.settlePolls())
            assertTrue("flag=$flag: ${device.couplingLines()}", device.couplingLines().isEmpty())
        }
    }

    @Test
    fun `a mode the key already holds is not written and nothing waits`() {
        val device = Device(mode = 0, fan = 1, flag = "1", systemUiMemory = mapOf(0 to 4))

        val applied = device.applyPlan(plan("quiet", vendorMode = 0, fanMode = TuningPlanner.FAN_SMART))

        assertTrue(applied.detail, applied.ok)
        assertTrue(device.vendorPuts().isEmpty())
        assertEquals(0, device.settlePolls())
        assertTrue(device.couplingLines().isEmpty())
    }

    // ---- restore: the daemon ----------------------------------------------------------------------

    @Test
    fun `the daemon restores a MANUAL session to the captured fan behind SystemUI's rewrite -- release first, mode, then the fan once more`() {
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(1 to 4, 0 to 1), reactionDelayMs = 150)
        device.armManualHandoff("balanced")
        val applied = device.applyPlan(plan("balanced", vendorMode = 1, fanMode = TuningPlanner.FAN_MANUAL))
        assertTrue(applied.detail, applied.ok)
        device.commands.clear()
        ShadowLog.clear()

        val restored = device.restore(device.record(applied))

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf(fan, cpu, vendor, fan), restored.order)
        assertEquals(0, device.mode())
        assertEquals("the captured fan, not SystemUI's coupled 1", 4, device.fan())
        // The release handed the duty back before compute; the mode's rewrite to 1 settled; the final put went back to 4.
        assertEquals(
            listOf(
                "echo 50000 > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4",
                "settings put system fan_mode 4",
            ),
            device.fanPuts(),
        )
        assertEquals(
            listOf("VENDOR_FAN_COUPLING flag=1 mode 1->0 fan pre=4 coupled=1 settledMs=450"),
            device.couplingLines(),
        )
        assertTrue(device.store.load().recordOrNull == null)
    }

    @Test
    fun `a MANUAL session's journal decodes its N+1 restore boundary and refuses N+2 -- restore steps, not snapshot nodes`() {
        // The restore of a duty-owned session with a vendor mode has one step more than the snapshot has
        // nodes: the fan is released first and written once more after the mode. A journal that reached
        // that final step must still load (the old decode refused anything past the node count); one
        // step past it is corrupt. No CPU node here: decode canonicalises sysfs paths through the host
        // filesystem, which refuses them on Windows; the fan and the mode are settings paths.
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(1 to 4, 0 to 1))
        device.armManualHandoff("balanced")
        val applied = device.applyPlan(plan("balanced", vendorMode = 1, fanMode = TuningPlanner.FAN_MANUAL, cpu = false), cpu = false)
        assertTrue(applied.detail, applied.ok)
        val record = device.record(applied).copy(
            expectedTopologySha256 = SnapshotIntegrity.topologySha256(SnapshotIntegrity.identitiesOf(applied.snapshot)),
            snapshotSha256 = SnapshotIntegrity.snapshotSha256(applied.snapshot),
        )
        val steps = RestoreMutationOrder.logicalIds(record)
        assertEquals(listOf(fan, vendor, fan), steps)
        assertEquals(record.snapshot.nodes.size + 1, steps.size)

        val complete = AtomicFileTransactionJournal.decodeJournal(
            AtomicFileTransactionJournal.encodeJournal(record.copy(lastCompletedRestoreBoundary = steps.size)),
        )
        assertTrue((complete as? JournalLoadResult.Corrupt)?.reason ?: "loaded", complete is JournalLoadResult.Loaded)
        assertEquals(steps.size, (complete as JournalLoadResult.Loaded).record.lastCompletedRestoreBoundary)

        val overflow = AtomicFileTransactionJournal.decodeJournal(
            AtomicFileTransactionJournal.encodeJournal(record.copy(lastCompletedRestoreBoundary = steps.size + 1)),
        )
        assertTrue(overflow.javaClass.simpleName, overflow is JournalLoadResult.Corrupt)
        assertEquals(steps.size + 1, record.snapshot.nodes.size + 2)
        assertEquals(
            "Restore boundary overflow: ${steps.size + 1} > ${steps.size}",
            (overflow as JournalLoadResult.Corrupt).reason,
        )
    }

    @Test
    fun `the daemon restores a Smart session with the fan last and KEEPS the barrier when the mode's rewrite was not observed`() {
        // Stock Smart (check 0 of the drive: mode 0, fan 4); SystemUI's memory for mode 0 is 4 too, so
        // the mode's restore rewrites 4 over 4 -- unobservable, the settle reports `unchanged` -- and
        // the final fan step has nothing to write. A rewrite could still be in flight, so the 3 s
        // barrier stays: it is what would catch and correct a late SILENT/SPORT with compute at Stock.
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(2 to 4, 0 to 4))
        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART))
        assertTrue(applied.detail, applied.ok)
        device.commands.clear()
        device.sleeps.clear()

        val restored = device.restore(device.record(applied))

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf(cpu, vendor, fan), restored.order)
        assertEquals(0, device.mode())
        assertEquals(4, device.fan())
        assertTrue("the fan already reads the captured 4; nothing to write: ${device.fanPuts()}", device.fanPuts().isEmpty())
        assertEquals("the barrier stays after an unobserved rewrite", 6, device.sleeps.count { it == 500L })
        assertEquals(
            listOf("VENDOR_FAN_COUPLING flag=1 mode 2->0 fan pre=4 coupled=unchanged settledMs=timeout"),
            device.couplingLines().filter { "mode 2->0" in it },
        )
    }

    @Test
    fun `a settled rewrite that lands on the captured value lets the final fan step skip the barrier`() {
        // Captured Smart; the player moved the fan to Sport mid-session; SystemUI's memory for mode 0
        // is 4, so the mode's restore is OBSERVED to rewrite 5 -> 4 and hold: nothing is in flight.
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(2 to 4, 0 to 4), reactionDelayMs = 100)
        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART))
        assertTrue(applied.detail, applied.ok)
        device.pServer.executeChecked("settings put system fan_mode ${TuningPlanner.FAN_SPORT}", "n")
        device.commands.clear()
        device.sleeps.clear()

        val restored = device.restore(device.record(applied))

        assertTrue(restored.detail, restored.ok)
        assertEquals(4, device.fan())
        assertTrue(device.fanPuts().isEmpty())
        assertEquals("no barrier after an observed, settled rewrite", 0, device.sleeps.count { it == 500L })
        assertEquals(
            listOf("VENDOR_FAN_COUPLING flag=1 mode 2->0 fan pre=5 coupled=4 settledMs=400"),
            device.couplingLines().filter { "mode 2->0" in it },
        )
    }

    @Test
    fun `captured SILENT stock from Thor Standard restores to Smart and counts as restored`() {
        // Manually selected Standard reads mode 0, fan 1, and 1 is SILENT, which
        // TR-08 never restores literally (it stops the vendor's duty loop on the Odin). The mode lands, its
        // rewrite to 1 settles, the fan is written LAST as Smart, and the readbacks demand exactly that.
        val device = Device(mode = 0, fan = TuningPlanner.FAN_SILENT, flag = "1", systemUiMemory = mapOf(2 to 4, 0 to 1))
        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART))
        assertTrue(applied.detail, applied.ok)
        device.commands.clear()

        val restored = device.restore(device.record(applied))

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf(cpu, vendor, fan), restored.order)
        assertEquals(0, device.mode())
        assertEquals("TR-08 wrote Smart over SystemUI's 1", TuningPlanner.FAN_SMART, device.fan())
        assertEquals(listOf("settings put system fan_mode ${TuningPlanner.FAN_SMART}"), device.fanPuts())

        // The readback is STRICT: the captured 1 itself still live would not count as restored.
        val fanRestore = device.commandsPort.restoreMutations(device.record(applied)).last { it.logicalId == fan }
        assertEquals(TuningPlanner.FAN_SILENT, fanRestore.value)
        assertTrue(device.statePort.readback(fanRestore))
        device.pServer.executeChecked("settings put system fan_mode ${TuningPlanner.FAN_SILENT}", "n")
        assertFalse("a loop-stopper still live is not stock", device.statePort.readback(fanRestore))
        assertFalse(device.statePort.verifySnapshotPlan(applied.snapshot).ok)
    }

    @Test
    fun `a tw06 journal -- fan, vendor mode, compute -- restores under the new order with the fan put back after the mode`() {
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(1 to 4, 0 to 1))
        device.armManualHandoff("balanced")
        val applied = device.applyPlan(plan("balanced", vendorMode = 1, fanMode = TuningPlanner.FAN_MANUAL))
        assertTrue(applied.detail, applied.ok)
        // What tw06 wrote: its own apply order, fan first, in the journal's projection.
        val tw06Order = listOf(
            applied.planned.single { it.logicalId == fan },
            applied.planned.single { it.logicalId == vendor },
            applied.planned.single { it.logicalId == cpu },
        )
        device.commands.clear()

        val restored = device.restore(device.record(applied, applyOrder = tw06Order))

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf(fan, cpu, vendor, fan), restored.order)
        assertEquals(0, device.mode())
        assertEquals(4, device.fan())
    }

    @Test
    fun `a tw06 Smart journal keeps the exact order it always had`() {
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(2 to 4, 0 to 1))
        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART))
        assertTrue(applied.detail, applied.ok)
        val tw06Order = listOf(
            applied.planned.single { it.logicalId == fan },
            applied.planned.single { it.logicalId == vendor },
            applied.planned.single { it.logicalId == cpu },
        )

        val restored = device.restore(device.record(applied, applyOrder = tw06Order))

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf(cpu, vendor, fan), restored.order)
        assertEquals("SystemUI's 1 for mode 0 was put back to the captured 4", 4, device.fan())
    }

    // ---- restore: the Guardian process -------------------------------------------------------------

    @Test
    fun `the Guardian confirms stock for a MANUAL session the daemon left behind -- the fan intent is planned once more after the mode`() {
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(1 to 4, 0 to 1), reactionDelayMs = 200)
        device.armManualHandoff("balanced")
        val applied = device.applyPlan(plan("balanced", vendorMode = 1, fanMode = TuningPlanner.FAN_MANUAL, cpu = false), cpu = false)
        assertTrue(applied.detail, applied.ok)
        val envelope = device.envelope(applied)
        assertEquals(listOf(fan, vendor), envelope.restoreEntries.map { it.logicalId })
        device.commands.clear()

        val outcome = device.guardianRestore(envelope)

        assertTrue(outcome.executed)
        assertEquals(outcome.described, GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
        assertEquals("STOCK_CONFIRMED", outcome.described)
        assertEquals(listOf(fan, vendor, fan), outcome.intents)
        assertEquals(1, outcome.finalFanIntents)
        assertEquals(0, device.mode())
        assertEquals(4, device.fan())
        assertNull(device.store.load().recordOrNull)
    }

    @Test
    fun `the Guardian confirms stock for a Smart session whose fan the player had moved -- the envelope's own fan entry behind the mode is the final write`() {
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(2 to 4, 0 to 1))
        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART, cpu = false), cpu = false)
        assertTrue(applied.detail, applied.ok)
        val envelope = device.envelope(applied)
        assertEquals(listOf(vendor, fan), envelope.restoreEntries.map { it.logicalId })
        // The player's finger on the fan tile mid-session, then the daemon dies.
        device.pServer.executeChecked("settings put system fan_mode ${TuningPlanner.FAN_SPORT}", "n")

        val outcome = device.guardianRestore(envelope)

        assertTrue(outcome.executed)
        assertEquals(outcome.described, GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
        assertEquals(listOf(vendor, fan), outcome.intents)
        assertEquals("the envelope's fan entry IS the final write", 1, outcome.finalFanIntents)
        assertEquals(0, device.mode())
        assertEquals("SystemUI's 1 for mode 0 was put back to the captured 4", 4, device.fan())
    }

    @Test
    fun `the Guardian restores captured SILENT stock to Smart and confirms it`() {
        val device = Device(mode = 0, fan = TuningPlanner.FAN_SILENT, flag = "1", systemUiMemory = mapOf(2 to 4, 0 to 1))
        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART, cpu = false), cpu = false)
        assertTrue(applied.detail, applied.ok)
        val envelope = device.envelope(applied)
        assertEquals(TuningPlanner.FAN_SILENT, (envelope.restoreEntries.single { it.logicalId == fan } as RestoreGuardianFanPutEntry).expectedStock.value)
        device.commands.clear()

        val outcome = device.guardianRestore(envelope)

        assertTrue(outcome.executed)
        assertEquals(outcome.described, GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
        assertEquals("STOCK_CONFIRMED", outcome.described)
        assertEquals(listOf(vendor, fan), outcome.intents)
        assertEquals(0, device.mode())
        assertEquals("SystemUI's 1 for mode 0 became Smart, which counts as restored", TuningPlanner.FAN_SMART, device.fan())
        assertEquals(listOf("settings put system fan_mode ${TuningPlanner.FAN_SMART}"), device.fanPuts())
    }

    @Test
    fun `the Guardian plans the fan once more after the mode even when the fan read stock at planning`() {
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(2 to 4, 0 to 1))
        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART, cpu = false), cpu = false)
        assertTrue(applied.detail, applied.ok)
        val envelope = device.envelope(applied)
        assertEquals("the fan is at stock before the Guardian plans", 4, device.fan())

        val outcome = device.guardianRestore(envelope)

        assertEquals(outcome.described, GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
        assertEquals(listOf(vendor, fan), outcome.intents)
        assertEquals(1, outcome.finalFanIntents)
        assertEquals(4, device.fan())
    }

    @Test
    fun `the Guardian still puts the fan back when the daemon had already restored it and died before the mode`() {
        // The daemon's release step ran (fan back at the captured 4), then it died with the mode at 1.
        val device = Device(mode = 0, fan = 4, flag = "1", systemUiMemory = mapOf(1 to 4, 0 to 1))
        device.armManualHandoff("balanced")
        val applied = device.applyPlan(plan("balanced", vendorMode = 1, fanMode = TuningPlanner.FAN_MANUAL, cpu = false), cpu = false)
        assertTrue(applied.detail, applied.ok)
        val envelope = device.envelope(applied)
        device.pServer.executeChecked("echo 50000 > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4", "n")
        assertEquals(4, device.fan())

        val outcome = device.guardianRestore(envelope)

        assertEquals(outcome.described, GuardianRunnerLifecycleState.STOCK_CONFIRMED, outcome.state)
        assertEquals("the fan read stock at planning and is still re-put after the mode", listOf(vendor, fan), outcome.intents)
        assertEquals(4, device.fan())
    }

    // ---- in session: the player's own change ----------------------------------------------------------

    @Test
    fun `a player's mid-session mode change moves the fan once and a fan reassert puts it back without touching the mode`() {
        val device = Device(mode = 0, fan = 1, flag = "1", systemUiMemory = mapOf(2 to 4, 1 to 1, 0 to 1))
        val applied = device.applyPlan(plan("performance", vendorMode = 2, fanMode = TuningPlanner.FAN_SMART))
        assertTrue(applied.detail, applied.ok)
        device.commands.clear()
        device.sleeps.clear()

        device.playerSetsMode(1)
        assertEquals("SystemUI's rewrite is the drift the supervisor sees", 1, device.fan())
        // S4.3: the supervisor's RepairFanTarget replays the journalled fan mutation through execute.
        val reasserted = device.commandsPort.execute(applied.planned.single { it.logicalId == fan })

        assertEquals(CheckedCommandStatus.CONFIRMED, reasserted.status)
        assertEquals(4, device.fan())
        assertEquals("the player's mode stands", 1, device.mode())
        assertEquals(listOf("settings put system fan_mode 4"), device.commands)
        assertEquals("a fan write never makes SystemUI react", 0, device.settlePolls())
    }

    // ---- the settle on its own ------------------------------------------------------------------------

    @Test
    fun `a value that keeps moving is capped and reported unstable, never waited on forever`() {
        var fanValue = 1
        var polls = 0
        val lines = mutableListOf<String>()
        val settle = VendorFanCouplingSettle(
            couplingRaw = { "1" },
            fanModeRaw = { fanValue.toString() },
            sleeper = { polls += 1; fanValue = if (fanValue == 1) 4 else 1 },
            elapsedRealtimeMs = { polls * VendorFanCouplingSettle.POLL_MS },
            log = { lines += it },
        )

        val result = settle.settle(fromMode = 0, toMode = 2, preFan = 1)

        assertEquals(VendorFanCouplingSettle.Outcome.UNSTABLE, result.outcome)
        assertEquals(VendorFanCouplingSettle.MAX_POLLS, result.polls)
        assertEquals(listOf("VENDOR_FAN_COUPLING flag=1 mode 0->2 fan pre=1 coupled=${result.coupledFan} settledMs=timeout outcome=unstable"), lines)
    }

    @Test
    fun `no fan to settle on a fanless chassis, and an unarmed flag never polls`() {
        var polls = 0
        val fanless = VendorFanCouplingSettle({ "1" }, { null }, { polls += 1 }, { 0L }, log = {})
        assertEquals(VendorFanCouplingSettle.Outcome.NO_FAN, fanless.settle(0, 1, null).outcome)
        val unarmed = VendorFanCouplingSettle({ "0" }, { "4" }, { polls += 1 }, { 0L }, log = {})
        assertFalse(unarmed.armed())
        assertEquals(VendorFanCouplingSettle.Outcome.NOT_COUPLED, unarmed.settle(0, 1, 4).outcome)
        assertEquals(0, polls)
    }
}
