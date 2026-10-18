package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A single before/after mutation test
 * already proves the read-before-write guard elides one redundant write -- it does NOT prove the
 * guard survives a realistic, repeated sequence, and this program has direct precedent for that
 * gap: a prior fix passed its own unit tests and produced no effect on device . This file drives one [PServerHardwareCommandPort] through a simulated
 * "add games -> Android picker -> back" foreground bounce -- several fan_mode SETTINGS_PUT calls
 * in a row, exactly the repeated shape the real apply/reassert callers produce across a
 * transition -- and counts BOTH the total commands actually sent to the transport AND the duty
 * values a device-accurate fake vendor would have produced, across the whole sequence, not one
 * isolated call.
 *
 * The fake vendor client mirrors the 2026-08-13 device measurement precisely:
 * every `settings put system fan_mode N` command that reaches the transport -- genuine transition
 * or redundant re-write alike -- resets the simulated duty node to period/2 as a mode-init
 * default, matching what was device-measured by writing fan_mode=5 while it already read 5. A
 * guard that only *looks* like it elides redundant writes, but still lets one slip through
 * somewhere in a multi-step sequence, shows up here as an extra duty excursion the single-call
 * tests in [AndroidFanDutyPortTest]/`TransactionCoordinatorTest` cannot see.
 */
class FanForegroundTransitionSequenceTest {
    @Test
    fun `one genuine SPORT entry, four bounced reassert attempts -- exactly one write, exactly one duty excursion`() {
        val state = mutableMapOf(
            "fan_mode" to TuningPlanner.FAN_SMART.toString(),
            "duty" to "12500",
        )
        val period = 50_000
        val pServer = object : PServerClient() {
            val commands = mutableListOf<String>()
            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commands += command
                Regex("settings put system fan_mode ([0-9]+)").find(command)?.let { match ->
                    state["fan_mode"] = match.groupValues[1]
                    // Device-measured 2026-08-13: ANY fan_mode write -- genuine transition or
                    // redundant re-write -- makes the vendor driver treat it as a fresh mode
                    // entry and slam duty to its entry value (period/2). The fake does not
                    // distinguish "genuine" from "redundant" here on purpose: distinguishing them
                    // is exactly what the read-before-write guard under test is responsible for,
                    // not something this fake should do on its behalf.
                    state["duty"] = (period / 2).toString()
                }
                return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
            }
        }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = SystemReader(),
            capabilities = ::capabilities,
            fanModeRaw = { state["fan_mode"] },
        )
        fun applySport() = port.execute(
            LogicalMutation(
                logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                axis = TuningAxis.FAN,
                canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                kind = LogicalMutationKind.SETTINGS_PUT,
                value = TuningPlanner.FAN_SPORT,
                restoreMode = null,
                expectPresent = true,
            ),
        )

        // Step 1: the ordinary profile apply -- a GENUINE transition, SMART -> SPORT. This one
        // write, and its one accepted duty excursion, is not what Part 1/2 claims to eliminate.
        val applyResult = applySport()
        assertEquals(CheckedCommandStatus.CONFIRMED, applyResult.status)
        assertEquals(1, pServer.commands.size)
        assertEquals((period / 2).toString(), state.getValue("duty"))

        // Mimir's own duty controller settles the fan back to a normal holding duty on its very
        // next tick (not modeled mechanically here -- SmartFanThermalControlTest owns that law);
        // only the fact that a real session moves on from the entry transient matters to this
        // sequence.
        state["duty"] = "11800"
        val dutyValuesAcrossBounce = mutableListOf<String>()

        // Steps 2-5: "add games -> Android picker -> back", modeled as four repeated reassert
        // attempts across the bounce (the ordinary apply path and the S4.3 fan-only reassert both
        // funnel through this exact same execute() call, per the read-before-write guard's own
        // commit message -- this sequence does not need to pick which caller fires to prove the
        // guard holds across all of them in a row).
        repeat(4) {
            val result = applySport()
            assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
            dutyValuesAcrossBounce += state.getValue("duty")
        }

        // The whole point of Part 1: a foreground bounce that keeps re-asserting a fan_mode Mimir
        // already holds must never re-reach the transport, no matter how many times it fires
        // across the bounce.
        assertEquals(
            "exactly one fan_mode write for the whole sequence -- the genuine SMART->SPORT " +
                "transition; every one of the four bounced reassert attempts must be elided",
            1,
            pServer.commands.size,
        )
        assertEquals(TuningPlanner.FAN_SPORT.toString(), state.getValue("fan_mode"))

        // The device pass criterion this simulates (stated in the brief): no duty excursion at or
        // above 20000 on external app launch, and never to the full period. Because every bounced
        // attempt was elided before reaching the fake transport, none of them could re-trigger the
        // fake's simulated mode-init duty reset -- so duty must have stayed pinned at its settled
        // holding value for the entire bounce, never once jumping back to period/2 or period.
        assertEquals(listOf("11800", "11800", "11800", "11800"), dutyValuesAcrossBounce)
        assertTrue(dutyValuesAcrossBounce.none { it.toInt() >= 20_000 })
        assertFalse(dutyValuesAcrossBounce.any { it.toInt() == period })
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "ayn",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "qti/sun/test",
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
}
