package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Differential regression coverage for the restore-path thermal-safety defects found by
 * adversarial review :
 *
 * 1. `restoreCapturedFanMode`'s `current == value` early-skip used to return CONFIRMED
 *    ("Captured fan mode already restored") while skipping the defensive full-PWM write its own
 *    contract requires -- even when the captured mode is one of Mimir's own duty-owned modes
 *    (SPORT/MANUAL), which is precisely the measured baseline (SPORT-5).
 * 2. The defensive full-PWM write and the mode `settings put` were joined with `;`, so a failed
 *    duty write was invisible and the compound result still reported CONFIRMED.
 * 3. A captured snapshot could restore directly into MANUAL (mode 6) via a plain `settings put`,
 *    bypassing `enterManualFanMode`'s handoff protocol (identity, freshness, duty reassert).
 *
 * Each of the first three tests below pins one of those three fixes and is written against
 * [PServerHardwareCommandPort.execute] / its private `restoreCapturedFanMode`, exercised only
 * through the public `HardwareCommandPort` surface (`SETTINGS_PUT` + `capturedSnapshotAuthority`).
 *
 * TR-08 adds a fourth concern, the
 * restore TARGET rather than the restore mechanism: a captured mode known to stop the vendor's own
 * regulation loop (Silent, Sport) must redirect to vendor Smart rather than ratchet a stale capture
 * forward -- see `AndroidTransactionPorts.restoreTargetFanMode`'s own KDoc. Pin 1's fixture value
 * also changed as a consequence (5 -> 4); see that test's own updated comment.
 */
class RestoreCapturedFanModeTest {

    // --- Pin 1: already-correct duty-owned mode still performs the defensive write -------------

    // TR-08 changed this fixture's expected write target from 5 to 4. Captured SPORT (5) is now
    // redirected to vendor Smart (4) by restoreTargetFanMode before this branch ever runs (SPORT
    // stops the vendor's own regulation loop -- see AndroidTransactionPorts' restoreTargetFanMode
    // KDoc) -- so a literal "restore target == 5" is no longer reachable through this codebase's
    // public surface at all, and this test's original equal-value edge case (current == target
    // while duty-owned) is now structurally unreachable too: the redirect guarantees `target` can
    // never itself be a duty-owned mode (SPORT always redirects away, MANUAL is refused before this
    // point). What remains real, and is what this test now pins, is the composition: a captured
    // Sport value over an already-Sport (duty-owned) live mode still forces the full defensive PWM
    // write, and still redirects to Smart rather than writing the stale 5 back.
    @Test
    fun `restoring a captured Sport value over an already-Sport live mode redirects to Smart and still forces full PWM`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_SPORT
        val pServer = RecordingPServer(dutyPath = HardwareProbe.FAN_DUTY) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {},
        )

        val result = port.execute(restoreMutation(TuningPlanner.FAN_SPORT))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(1, pServer.commands.size)
        assertTrue(
            "defensive full-PWM write must fire, and the redirected target must be Smart (4), " +
                "never the stale captured Sport (5) -- got: ${pServer.commands}",
            pServer.commands.single()
                .startsWith("echo 50000 > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4"),
        )
        assertFalse(
            "the old bug's detail string must not survive -- a real write happened",
            result.detail == "Captured fan mode already restored",
        )
        assertTrue(
            "the redirect must name itself in the confirmed detail (TR-08) -- got: ${result.detail}",
            result.detail.contains("restored vendor default 4"),
        )
    }

    @Test
    fun `restoring an already-correct non-duty-owned mode is still a genuine no-op`() {
        // Guards against over-correcting: the skip this method was protecting (avoiding a
        // redundant vendor mode write) remains valid when current was never duty-owned in the
        // first place -- e.g. restoring Smart(4) over live Smart(4).
        val state = fanState()
        var mode = TuningPlanner.FAN_SMART
        val pServer = RecordingPServer(dutyPath = HardwareProbe.FAN_DUTY) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {},
        )

        val result = port.execute(restoreMutation(TuningPlanner.FAN_SMART))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue(
            "detail must still be the already-restored message (plus settle suffix) -- got: " +
                result.detail,
            result.detail.startsWith("Captured fan mode already restored"),
        )
        assertTrue("a genuine no-op must send no PServer command", pServer.commands.isEmpty())
    }

    // --- Pin 2: a failed safety write does NOT report CONFIRMED ---------------------------------

    @Test
    fun `a failed defensive duty write on a duty-owned restore is reported as FAILED, not CONFIRMED`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_SPORT
        var modeWasSet = false
        val pServer = RecordingPServer(
            dutyPath = HardwareProbe.FAN_DUTY,
            dutyWriteFails = true,
        ) { value -> mode = value; modeWasSet = true }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {},
        )

        // Current (Sport) differs from the restore target -- so this exercises the duty-owned
        // branch's changing-value path too, not just the pin-1 equal-value path. TR-08: the
        // captured value here (Silent) is itself redirected to Smart (4) by restoreTargetFanMode
        // before this branch runs, since Silent also stops the vendor's regulation loop -- but that
        // is immaterial to what this test pins. The dutyWriteFails echo step fails before the
        // "settings put system fan_mode <target>" tail is ever attempted (short-circuited by `&&`),
        // so the specific target value (1 vs 4) is never observed; only the failure-propagation
        // mechanism is under test.
        val result = port.execute(restoreMutation(TuningPlanner.FAN_SILENT))

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertFalse(
            "the mode write must never have run -- && must short-circuit after the failed echo",
            modeWasSet,
        )
        assertEquals(TuningPlanner.FAN_SPORT, mode)
    }

    // --- Pin 3: the mode-6 restore path cannot bypass the handoff -------------------------------

    @Test
    fun `restoring captured Manual is refused before any command reaches PServer`() {
        listOf(TuningPlanner.FAN_SMART, TuningPlanner.FAN_SPORT, TuningPlanner.FAN_MANUAL).forEach { current ->
            val state = fanState()
            // A mutable, PServer-wired mode (not a fixed closure) matters here: if the guard were
            // absent, the plain/defensive restore branches genuinely flip live mode to MANUAL and
            // the post-restore settle check would then see it "stably" land on 6 and pass -- a
            // static fanModeRaw fake would incidentally fail the settle check for an unrelated
            // reason and mask whether the guard itself is what is doing the refusing.
            var mode = current
            val pServer = RecordingPServer(dutyPath = HardwareProbe.FAN_DUTY) { value -> mode = value }
            val port = PServerHardwareCommandPort(
                pServer = pServer,
                reader = reader(state),
                capabilities = ::odinCapabilities,
                fanModeRaw = { mode.toString() },
                sleeper = {},
            )

            val result = port.execute(restoreMutation(TuningPlanner.FAN_MANUAL))

            assertEquals(
                "current=$current must not let a Manual restore through",
                CheckedCommandStatus.FAILED,
                result.status,
            )
            assertTrue(
                "no hardware command may be attempted for a refused Manual restore",
                pServer.commands.isEmpty(),
            )
            assertTrue(
                "failure must name the handoff it refused to bypass -- got: ${result.detail}",
                result.detail.contains("handoff"),
            )
        }
    }

    // --- TR-08 restore target for vendor loop-stopping fan modes -------------------------------
    //
    // Restore now targets restoreTargetFanMode(value), not the raw captured value: a captured mode
    // known to stop the vendor's own regulation loop (Silent, Sport) redirects to vendor Smart
    // instead of ratcheting the stale capture forward. See AndroidTransactionPorts'
    // restoreTargetFanMode KDoc for the full evidence and scope.

    @Test
    fun `restoring a captured Sport value never writes the stale 5 back -- TR-08 ratchet`() {
        val state = fanState()
        // Silent, not Sport: chosen only so the live mode differs from both the raw captured value
        // (5) and its redirected target (4), so this exercises a real, observable write rather
        // than a no-op -- the ratchet risk is about what gets WRITTEN, not the live starting state.
        var mode = TuningPlanner.FAN_SILENT
        val pServer = RecordingPServer(dutyPath = HardwareProbe.FAN_DUTY) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {},
        )

        // Simulates a session that captured Sport (5) because an earlier, pre-TR-08 build left the
        // device there -- exactly the scenario this ticket closes: restoring the literal captured
        // value would park the device back in the state measured at 23% duty, speed=0, 89.9C.
        val result = port.execute(restoreMutation(TuningPlanner.FAN_SPORT))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(listOf("settings put system fan_mode 4"), pServer.commands)
        assertFalse(
            "the ratchet: a captured Sport value must never be written back literally -- got: " +
                pServer.commands,
            pServer.commands.any { it.contains("fan_mode 5") },
        )
        assertEquals(TuningPlanner.FAN_SMART, mode)
        assertTrue(
            "the redirect must name itself in the confirmed detail -- got: ${result.detail}",
            result.detail.contains("captured fan mode 5 stops the vendor regulation loop"),
        )
    }

    @Test
    fun `a safe captured value outside the redirect set is still honoured, not universally Smarted -- TR-08`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_SILENT
        val pServer = RecordingPServer(dutyPath = HardwareProbe.FAN_DUTY) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {},
        )

        // 0 is inside TuningPlanner.isCapturedVendorFanMode's admitted 0..7 range (captured-value
        // validation, unchanged by this ticket) but is not one of the two modes this codebase has
        // evidence stops the vendor's regulation loop (Silent, Sport) -- asserting otherwise would
        // be exactly the ungrounded claim restoreTargetFanMode's own KDoc forbids. A genuinely
        // different, safe captured value must still be restored as captured, not redirected.
        val result = port.execute(restoreMutation(0))

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(listOf("settings put system fan_mode 0"), pServer.commands)
        assertEquals(0, mode)
        assertFalse(
            "an unredirected restore must not claim a redirect happened -- got: ${result.detail}",
            result.detail.contains("stops the vendor regulation loop"),
        )
    }

    // --- Fixtures ---------------------------------------------------------------------------

    private fun restoreMutation(value: Int) = LogicalMutation(
        logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
        axis = TuningAxis.FAN,
        canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
        kind = LogicalMutationKind.SETTINGS_PUT,
        value = value,
        restoreMode = null,
        expectPresent = true,
        capturedSnapshotAuthority = true,
    )

    // see AndroidFanDutyPortTest's identical fix -- resolve() now gates
    // fan authority on live node presence for every device, and node presence is derived from
    // this same curated state map rather than a new fixture.
    private fun reader(state: Map<String, String>) = SystemReader(
        directRead = { path -> state[path] },
        directExists = { path -> path in state },
    )

    private fun fanState() = mutableMapOf(
        HardwareProbe.FAN_DUTY to "10000",
        HardwareProbe.FAN_PERIOD to "50000",
        HardwareProbe.FAN_SPEED to "2000",
    )

    private fun odinCapabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "AYN",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "odin/test",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0), listOf(1_000), 1_000, 1_000, 1_000),
        ),
        gpuFrequenciesKhz = listOf(500_000, 300_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
        cpuCeilingPath = HardwareProbe.CPU_QOS_MAX_PATH,
    )

    /**
     * Interprets the exact command string [PServerHardwareCommandPort] builds, distinguishing
     * `&&` (short-circuiting) from `;` (always-runs-next) the same way a real shell would, so this
     * fake can tell the pre-fix and post-fix production strings apart by observable behavior
     * rather than by string-matching the separator itself.
     */
    private class RecordingPServer(
        private val dutyPath: String,
        private val dutyWriteFails: Boolean = false,
        private val setMode: (Int) -> Unit,
    ) : PServerClient() {
        val commands = mutableListOf<String>()

        override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
            commands += command
            val separators = Regex("&&|;").findAll(command).map { it.value }.toList()
            val statements = command.split(Regex("&&|;")).map(String::trim)
            var exitCode = 0
            for (index in statements.indices) {
                exitCode = runStatement(statements[index])
                val separatorBeforeNext = separators.getOrNull(index)
                if (separatorBeforeNext == "&&" && exitCode != 0) break
            }
            return if (exitCode == 0) {
                CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "Exit 0 confirmed")
            } else {
                CheckedCommandResult(CheckedCommandStatus.FAILED, exitCode = exitCode, detail = "Nonzero exit $exitCode")
            }
        }

        private fun runStatement(step: String): Int {
            Regex("settings put system fan_mode ([0-9]+)").matchEntire(step)?.let { match ->
                setMode(match.groupValues[1].toInt())
                return 0
            }
            Regex("echo ([0-9]+) ?> ?(${Regex.escape(dutyPath)})").matchEntire(step)?.let {
                return if (dutyWriteFails) 1 else 0
            }
            return 0
        }
    }
}
