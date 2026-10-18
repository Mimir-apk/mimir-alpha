package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual fan control uses family and capability admission.
 *
 * **What this file exists to stop happening again.** Manual fan control had never worked on any
 * device, ever -- not a regression, never provisioned. Eight independent gates stood between an
 * explicit [TuningPlanner.FanIntent.MANUAL_OVERRIDE] request and the PWM duty write, six of them
 * `deviceProfileId == CpuCeilingTransport.ODIN_3_PROFILE` comparisons and two of them structural
 * (an admission that refused every non-SMART fan mode, and an availability predicate that resolved
 * through a signed manifest which has never carried a manual plan on any sequence). Because the
 * gates were in series, widening any subset left the feature exactly as dead as before while
 * looking, in review and in a green suite, as though it had been fixed. Each test below pins one
 * of those gates from the OUTSIDE -- on a Retroid Pocket Nova fixture, a supported device
 * -- so re-narrowing any single one reddens here rather than silently restoring the old behaviour.
 *
 * Every test also asserts the fail-closed twin. Widening a device-id test to a capability test is
 * only safe while the capability is real, so each case that admits manual fan on a fan-bearing
 * chassis also refuses it on one whose PWM nodes are not live. "Ambiguous branches fail toward
 * cooling" is the invariant being preserved, not relaxed.
 *
 * **Fixture provenance.** The Nova's identity strings and profile id are device truth: `adb shell`
 * on the measured unit reports `ro.product.name=kalama`, family
 * `qualcomm-8gen2`, profile `retroid-qcs8550-pserver`. Its `/sys/class/gpio5_pwm2/` topology was
 * read live on 2026-08-23 -- `duty=12000`, `period=50000`, `speed=5100`, with `duty` and `speed`
 * world-writable -- which is what makes this widening reach real hardware rather than a fixture.
 */
class ManualFanFamilyWideningTest {

    // ---------------------------------------------------------------- planning

    @Test
    fun `MANUAL_OVERRIDE derives mode 6 on a fan-bearing Nova, and PROFILE_AUTO still derives vendor SMART`() {
        val planner = TuningPlanner()
        val nova = novaCapabilities()

        val manual = planner.plan(
            profileId = "balanced",
            packageName = "example.game",
            capabilities = nova,
            fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        )
        assertNotNull("MANUAL_OVERRIDE must derive a plan for a fan-bearing Nova", manual)
        assertEquals(TuningPlanner.FAN_MANUAL, manual!!.fanMode)

        // The R2/R3 profile-apply default is untouched by the widening: an ordinary apply still
        // derives vendor SMART, on this device and every other. If this ever flips, the release
        // design ("Mimir never stops the vendor regulation loop") has been broken by the opt-in.
        val auto = planner.plan("balanced", "example.game", nova)
        assertEquals(TuningPlanner.FAN_SMART, auto!!.fanMode)

        // ...and manual differs from the automatic plan in the fan axis ONLY. Manual fan must never
        // become a route to a clock envelope no profile derives.
        assertEquals(auto.cpuMaxKhz, manual.cpuMaxKhz)
        assertEquals(auto.gpuMaxPowerLevel, manual.gpuMaxPowerLevel)
    }

    @Test
    fun `MANUAL_OVERRIDE derives no manual mode without a live fan node`() {
        val planner = TuningPlanner()
        val noNodes = novaCapabilities().copy(fanNodeAvailable = false)
        assertEquals(
            TuningPlanner.FAN_SMART,
            planner.plan("balanced", "example.game", noNodes, TuningPlanner.FanIntent.MANUAL_OVERRIDE)!!.fanMode,
        )
        val noAxis = novaCapabilities().copy(
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            fanNodeAvailable = false,
        )
        assertNull(
            planner.plan("balanced", "example.game", noAxis, TuningPlanner.FanIntent.MANUAL_OVERRIDE)!!.fanMode,
        )
    }

    // ---------------------------------------------------------------- validation

    @Test
    fun `PlanTargetValidator admits a manual fan target on the Nova and refuses it with no fan axis`() {
        val nova = novaCapabilities()
        val manual = TuningPlanner().plan(
            "balanced",
            "example.game",
            nova,
            TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        )!!
        assertEquals(TuningPlanner.FAN_MANUAL, manual.fanMode)
        assertEquals(
            "PlanTargetValidator must no longer restrict manual fan to the exact Odin 3 profile",
            emptyList<String>(),
            PlanTargetValidator.validate(manual, nova),
        )
        assertTrue(
            "a manual target must still fail closed with no admitted live fan axis",
            PlanTargetValidator.validate(
                manual,
                nova.copy(
                    supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
                    fanNodeAvailable = false,
                ),
            ).any { "admitted live fan axis" in it },
        )
    }

    // ---------------------------------------------------------------- actuation

    @Test
    fun `the PWM duty write is accepted on the Nova, not only on the exact Odin`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_MANUAL
        val pServer = RecordingPServer(state) { mode = it }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::novaCapabilities,
            fanModeRaw = { mode.toString() },
        )

        // Before 2026-08-23 this returned FAILED("Manual fan duty is unavailable for this exact
        // hardware profile") -- the deepest of the eight gates, and the one that made every gate
        // above it moot. This is the assertion that proves the fan can actually be driven.
        assertEquals(CheckedCommandStatus.CONFIRMED, port.write(20_000, 50_000, 0).status)
        assertEquals("20000", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(listOf("echo 20000 > ${HardwareProbe.FAN_DUTY}"), pServer.commands)
    }

    @Test
    fun `the PWM duty write still fails closed when the fan axis or nodes are not live`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_MANUAL
        val pServer = RecordingPServer(state) { mode = it }

        val noAxis = novaCapabilities().copy(supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU))
        assertEquals(
            CheckedCommandStatus.FAILED,
            PServerFanDutyMutationPort(pServer, reader(state), { noAxis }, { mode.toString() })
                .write(20_000, 50_000, 0).status,
        )

        val noNodes = novaCapabilities().copy(fanNodeAvailable = false)
        assertEquals(
            CheckedCommandStatus.FAILED,
            PServerFanDutyMutationPort(pServer, reader(state), { noNodes }, { mode.toString() })
                .write(20_000, 50_000, 0).status,
        )

        assertTrue("no duty may reach hardware from a refused write", pServer.commands.isEmpty())
    }

    @Test
    fun `duty ownership is still required before any duty write, on the Nova as on the Odin`() {
        val state = fanState()
        // Vendor SMART is NOT a Mimir-duty-owned mode: the vendor's own loop is regulating, and a
        // duty write would be fighting it. Widening the device gate must not have widened this one.
        var mode = TuningPlanner.FAN_SMART
        val pServer = RecordingPServer(state) { mode = it }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::novaCapabilities,
            fanModeRaw = { mode.toString() },
        )
        assertEquals(CheckedCommandStatus.FAILED, port.write(20_000, 50_000, 0).status)
        assertTrue(pServer.commands.isEmpty())
    }

    // ---------------------------------------------------------------- topology resolution

    /**
     * The gate at `AndroidTransactionPorts.manualFanNodes` (renamed from `exactManualFanNodes`).
     * It is NOT reachable through [PServerFanDutyMutationPort], which does its own
     * `HandheldDeviceProfiles.resolve` -- so the duty-write pins above do not cover it, and without
     * this test its device-id conjunct could be reinstated with the suite still green. That is the
     * precise failure mode this file's header describes, so it gets its own pin.
     * [forceCoolingSmartReassert] is the shortest path to it: it resolves the topology first and
     * refuses outright when it comes back null.
     */
    @Test
    fun `force-cooling reassert resolves the fan topology on the Nova, not only on the exact Odin`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_SMART
        val pServer = RecordingPServer(state) { mode = it }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::novaCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {},
        )

        // Pre-2026-08-23 this returned FAILED("Force-cooling reassert topology is unavailable") on
        // every device but the Odin -- the force-cooling path was exact-Odin-only.
        val result = port.forceCoolingSmartReassert()
        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals("full PWM must be forced in the same transaction", "50000", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(TuningPlanner.FAN_SMART, mode)
    }

    @Test
    fun `force-cooling reassert still refuses when the fan nodes are not live`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_SMART
        val pServer = RecordingPServer(state) { mode = it }
        val noNodes = novaCapabilities().copy(fanNodeAvailable = false)
        val result = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = { noNodes },
            fanModeRaw = { mode.toString() },
            sleeper = {},
        ).forceCoolingSmartReassert()
        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue("no command may reach hardware", pServer.commands.isEmpty())
    }

    // ---------------------------------------------------------------- capture admission
    //
    // NOT PINNED HERE, and the gap is deliberate rather than an oversight.
    // `AndroidHardware.captureFanNode` carries a seventh gate that this commit widened the same way
    // ("manual fan mode requires exact Odin 3 profile", retired), but it is a private method of
    // `HardwareProbe`, which reads `android.os.Build` directly and has no host-constructible seam.
    // Nothing in this suite covered it before this commit either. It is therefore DEVICE-VERIFY
    // ONLY: a manual entry that reaches the duty write on the Nova proves it passed; a host suite
    // cannot. This remains unverified by this host suite.

    // ---------------------------------------------------------------- handoff admission

    @Test
    fun `the manual handoff admits the derived plan and refuses a vector that is not the applied one`() {
        val nova = novaCapabilities()
        val planner = TuningPlanner()
        val auto = planner.plan("balanced", "example.game", nova)!!
        val manual = planner.plan(
            "balanced",
            "example.game",
            nova,
            TuningPlanner.FanIntent.MANUAL_OVERRIDE,
        )!!
        // The contract is built FROM the automatic derivation, which is what an applied profile
        // actually carries -- not a hand-picked fixture that happens to agree.
        val contract = requireNotNull(novaSession(nova.deviceProfileId).appliedTargetContract).copy(
            cpuQosCeilingKhz = auto.cpuMaxKhz,
            gpuMaxPowerLevel = auto.gpuMaxPowerLevel,
            fanMode = TuningPlanner.FAN_SMART,
        )

        assertTrue(
            "the MANUAL_OVERRIDE derivation for the applied profile must be admitted",
            manualFanHandoffPlanAdmitted(manual, contract, nova),
        )
        assertFalse("a null derivation must refuse", manualFanHandoffPlanAdmitted(null, contract, nova))
        assertFalse(
            "a vendor-SMART plan is not a manual handoff",
            manualFanHandoffPlanAdmitted(auto, contract, nova),
        )
        assertFalse(
            "a CPU vector that is not the applied one must refuse -- manual fan changes the fan only",
            manualFanHandoffPlanAdmitted(manual.copy(cpuMaxKhz = mapOf(0 to 123)), contract, nova),
        )
        assertFalse(
            "a GPU vector that is not the applied one must refuse",
            manualFanHandoffPlanAdmitted(manual.copy(gpuMaxPowerLevel = 99), contract, nova),
        )
    }

    // ---------------------------------------------------------------- session admission

    @Test
    fun `manualFanControlAdmitted admits a Nova contract and still refuses a hardware identity drift`() {
        val nova = novaCapabilities()
        val active = novaSession(nova.deviceProfileId)
        assertTrue(
            manualFanControlAdmitted(
                active,
                nova,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
        // The conjunct that was KEPT when the device-id one was retired: the live hardware must
        // still be the hardware the contract was applied against. Different question, still true
        // on every device.
        assertFalse(
            "a contract applied against other hardware must still be refused",
            manualFanControlAdmitted(
                novaSession(CpuCeilingTransport.ODIN_3_PROFILE),
                nova,
                hardwareWritesCompiled = true,
                manualPlanMatchesContract = true,
                cpuSafetySensorAvailable = true,
                gpuSensorAvailable = true,
                mutationPortAvailable = true,
                guardianJournalBound = true,
            ),
        )
    }

    // ---------------------------------------------------------------- fixtures

    private fun novaSession(deviceProfile: String) = ActiveSessionIdentity(
        generation = 7,
        transactionId = "tx-nova-7",
        requestId = "mimir.system.test",
        ownerUid = 1,
        targetPackage = "com.gruning.mimir.rewrite",
        activationDeadlineMs = Long.MAX_VALUE,
        leaseDeadlineMs = Long.MAX_VALUE,
        targetEverForeground = true,
        appliedTargetContract = AppliedTargetContract(
            deviceProfile = deviceProfile,
            bootId = "boot",
            cpuQosCeilingKhz = mapOf(0 to 1_804_800),
            cpuQosTransport = CpuQosTransport.SHARED_QOS,
            gpuMinPowerLevel = null,
            gpuMaxPowerLevel = 1,
            fanMode = TuningPlanner.FAN_SMART,
            profileId = "balanced",
        ),
        scope = ActiveSessionScope.SYSTEM,
    )

    private fun reader(state: Map<String, String>) = SystemReader(
        directRead = { path -> state[path] },
        directExists = { path -> path in state },
    )

    private fun fanState(duty: Int = 12_000) = mutableMapOf(
        HardwareProbe.FAN_DUTY to duty.toString(),
        HardwareProbe.FAN_PERIOD to "50000",
        HardwareProbe.FAN_SPEED to "5100",
    )

    /**
     * Device truth, read off the Retroid Pocket Nova on 2026-08-23. `Moorechip` is the ODM
     * manufacturer string Retroid actually ships (shared with the RP5; only the model separates
     * them), and `retroid-qcs8550-pserver` is the id [HandheldDeviceProfiles.profileId] mints for
     * it -- deliberately NOT the exact Odin id, which is the entire point of every assertion above.
     */
    private fun novaCapabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket Nova",
        socModel = "QCS8550",
        buildFingerprint = "kalama/test",
        deviceProfileId = "retroid-qcs8550-pserver",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2, 3), listOf(300_000, 1_516_800, 2_016_000), 300_000, 2_016_000, 2_016_000),
            CpuPolicy(4, "/sys/devices/system/cpu/cpufreq/policy4", listOf(4, 5, 6), listOf(633_600, 2_150_400, 2_803_200), 633_600, 2_803_200, 2_803_200),
            CpuPolicy(7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7), listOf(825_600, 2_476_800, 3_187_200), 825_600, 3_187_200, 3_187_200),
        ),
        gpuFrequenciesKhz = listOf(305_000, 525_000, 719_000, 818_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private class RecordingPServer(
        private val state: MutableMap<String, String>,
        private val setMode: (Int) -> Unit,
    ) : PServerClient() {
        val commands = mutableListOf<String>()

        override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
            commands += command
            command.split(Regex("&&|;")).map(String::trim).forEach { step ->
                Regex("settings put system fan_mode ([0-9]+)")
                    .matchEntire(step)?.groupValues?.get(1)?.toIntOrNull()?.let(setMode)
                Regex("echo ([0-9]+) ?> ?(${Regex.escape(HardwareProbe.FAN_DUTY)})")
                    .matchEntire(step)?.let { m -> state[m.groupValues[2]] = m.groupValues[1] }
            }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
        }
    }
}
