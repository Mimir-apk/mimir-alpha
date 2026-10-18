package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFanDutyPortTest {
    @Test
    fun `runtime duty writer is fixed path mode bound and readback checked`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_MANUAL
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
        )

        assertEquals(CheckedCommandStatus.CONFIRMED, port.write(20_000, 50_000, 0).status)
        assertEquals("20000", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(listOf("echo 20000 > ${HardwareProbe.FAN_DUTY}"), pServer.commands)

        mode = TuningPlanner.FAN_SMART
        assertEquals(CheckedCommandStatus.FAILED, port.write(25_000, 50_000, 0).status)
        assertEquals(1, pServer.commands.size)
        mode = TuningPlanner.FAN_MANUAL
        assertEquals(CheckedCommandStatus.FAILED, port.write(25_000, 49_999, 0).status)
        assertEquals(1, pServer.commands.size)
    }

    // Defect: with the 20% floor retired, every remaining port.write(...) call in this file now
    // passes floor 0, so the "caller's floor tag doesn't match the required floor" rejection in
    // PServerFanDutyMutationPort.write was left untested. This pins it: a stale/legacy 20%
    // floor tag must still be rejected even though 0 is now the only qualified floor.
    @Test
    fun `runtime duty writer rejects a stale twenty percent floor tag`() {
        val state = fanState()
        val pServer = MutableFanPServer(state) {}
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_MANUAL.toString() },
        )

        val result = port.write(20_000, 50_000, 20)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertEquals("Manual fan duty floor is not qualified", result.detail)
        assertEquals(0, pServer.commands.size)
    }

    // Defect: with the 20% floor retired, every remaining port.write(...) call in this file now
    // passes an in-range duty, so the "requested duty outside 0..period" rejection in
    // PServerFanDutyMutationPort.write was left untested on its high side. This pins it: a duty
    // above the live period must still be rejected.
    @Test
    fun `runtime duty writer rejects a duty above the period`() {
        val state = fanState()
        val pServer = MutableFanPServer(state) {}
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_MANUAL.toString() },
        )

        val result = port.write(50_001, 50_000, 0)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertEquals("Manual fan duty is below the qualified profile floor", result.detail)
        assertEquals(0, pServer.commands.size)
    }

    // Defect: with the 20% floor retired, every remaining port.write(...) call in this file now
    // passes a non-negative duty, so the "requested duty outside 0..period" rejection in
    // PServerFanDutyMutationPort.write was left untested on its low side. This pins it: a
    // negative duty must still be rejected, not just an over-period one.
    @Test
    fun `runtime duty writer rejects a negative duty`() {
        val state = fanState()
        val pServer = MutableFanPServer(state) {}
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_MANUAL.toString() },
        )

        val result = port.write(-1, 50_000, 0)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertEquals("Manual fan duty is below the qualified profile floor", result.detail)
        assertEquals(0, pServer.commands.size)
    }

    // S4.2/S6 test 1 (see TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES): the actual PWM writer --
    // not just SmartFanThermalController's own decision layer -- must accept SPORT ownership
    // exactly like MANUAL, and continue refusing SMART, the one mode whose vendor loop fights a
    // direct write.
    @Test
    fun `runtime duty writer accepts Sport ownership exactly like Manual`() {
        val state = fanState()
        var mode = TuningPlanner.FAN_SPORT
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
        )

        assertEquals(CheckedCommandStatus.CONFIRMED, port.write(20_000, 50_000, 0).status)
        assertEquals("20000", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(listOf("echo 20000 > ${HardwareProbe.FAN_DUTY}"), pServer.commands)

        mode = TuningPlanner.FAN_SMART
        assertEquals(CheckedCommandStatus.FAILED, port.write(25_000, 50_000, 0).status)
        assertEquals(1, pServer.commands.size)
    }

    @Test
    fun `confirmed runtime duty write accepts bounded delayed exact readback without replay`() {
        val state = fanState(duty = 10_000)
        var reads = 0
        val sleeps = mutableListOf<Long>()
        val pServer = object : PServerClient() {
            val commands = mutableListOf<String>()

            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commands += command
                return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
            }
        }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = SystemReader(
                directRead = { path ->
                    when (path) {
                        HardwareProbe.FAN_DUTY -> if (reads++ == 0) "10000" else "20000"
                        else -> state[path]
                    }
                },
                directExists = { path -> path in state },
            ),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_MANUAL.toString() },
            sleeper = { sleeps += it },
        )

        val result = port.write(20_000, 50_000, 0)

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(1, pServer.commands.size)
        assertEquals(listOf(50L), sleeps)
    }

    @Test
    fun `confirmed runtime duty write fails after bounded permanent readback mismatch`() {
        val state = fanState(duty = 10_000)
        val sleeps = mutableListOf<Long>()
        val pServer = object : PServerClient() {
            var commandCount = 0

            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commandCount += 1
                return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
            }
        }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_MANUAL.toString() },
            sleeper = { sleeps += it },
        )

        val result = port.write(20_000, 50_000, 0)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail.contains("requestedDuty=20000"))
        assertTrue(result.detail.contains("observedDuty=10000"))
        assertEquals(1, pServer.commandCount)
        assertEquals(listOf(50L, 50L), sleeps)
    }

    @Test
    fun `ambiguous runtime duty receipt remains ambiguous despite matching readback`() {
        val state = fanState(duty = 20_000)
        val pServer = object : PServerClient() {
            var commandCount = 0

            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commandCount += 1
                return CheckedCommandResult(
                    CheckedCommandStatus.AMBIGUOUS,
                    detail = "PServer transact returned false",
                )
            }
        }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_MANUAL.toString() },
        )

        val result = port.write(20_000, 50_000, 0)

        assertEquals(CheckedCommandStatus.AMBIGUOUS, result.status)
        assertTrue(result.detail.contains("observedDuty=20000"))
        assertEquals(1, pServer.commandCount)
    }

    @Test
    fun `failed runtime duty receipt remains failed despite matching readback`() {
        val pServer = FixedReceiptPServer(CheckedCommandStatus.FAILED)
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(fanState(duty = 20_000)),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_MANUAL.toString() },
        )

        val result = port.write(20_000, 50_000, 0)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail.contains("observedDuty=20000"))
        assertEquals(1, pServer.commandCount)
    }

    @Test
    fun `confirmed runtime duty receipt fails closed when manual ownership drifts`() {
        var mode = TuningPlanner.FAN_MANUAL
        val pServer = object : PServerClient() {
            var commandCount = 0

            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commandCount += 1
                mode = TuningPlanner.FAN_SMART
                return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
            }
        }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(fanState(duty = 20_000)),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
        )

        val result = port.write(20_000, 50_000, 0)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail.contains("ownership changed"))
        assertEquals(1, pServer.commandCount)
    }

    @Test
    fun `confirmed runtime duty receipt fails closed when period drifts`() {
        val state = fanState(duty = 20_000)
        val pServer = object : PServerClient() {
            var commandCount = 0

            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commandCount += 1
                state[HardwareProbe.FAN_PERIOD] = "49999"
                return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
            }
        }
        val port = PServerFanDutyMutationPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_MANUAL.toString() },
        )

        val result = port.write(20_000, 50_000, 0)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail.contains("period changed"))
        assertEquals(1, pServer.commandCount)
    }

    @Test
    fun `balanced manual entry preserves exact Smart duty against the platform's own entry write`() {
        // **The fake that could not see the thirteenth blocker, corrected 2026-08-25.** It already
        // modelled the platform's own duty write on entry -- that part was right, and known. What
        // it got wrong was WHEN: it applied the write synchronously inside the `fan_mode` command,
        // so a chained `fan_mode=6 && echo duty` always landed last and the suite stayed green
        // while the device refused every single attempt. Measured on the Nova, the platform's write
        // arrives 86-107 ms AFTER the `settings put` begins -- asynchronously, well after that
        // command returns. Modelled here on the first sleeper tick, which is the vendor-reaction
        // settle the port now waits out before asserting its duty.
        // [[verify-the-instrument-can-see-the-defect]]: a fixture that cannot reproduce the field
        // failure is not covering the behaviour, however green it reads.
        val state = fanState(duty = 12_500)
        var mode = TuningPlanner.FAN_SMART
        var platformEntryWritePending = false
        val pServer = MutableFanPServer(state) { value ->
            mode = value
            if (value == TuningPlanner.FAN_MANUAL) platformEntryWritePending = true
        }
        val prepared = preparedManualEntry(
            profileId = "balanced",
            state = state,
            pServer = pServer,
            fanModeRaw = { mode.toString() },
            sleeper = {
                if (platformEntryWritePending) {
                    platformEntryWritePending = false
                    state[HardwareProbe.FAN_DUTY] = "25000"
                }
            },
        )

        val result = prepared.port.execute(prepared.mutation)

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(TuningPlanner.FAN_MANUAL, mode)
        assertEquals("12500", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(
            listOf(
                "settings put system fan_mode 6",
                "echo 12500>${HardwareProbe.FAN_DUTY}",
            ),
            pServer.commands,
        )
    }

    @Test
    fun `delayed vendor reset after immediate handoff fails closed without PWM replay`() {
        val state = fanState(duty = 12_500)
        var mode = TuningPlanner.FAN_SMART
        var settleCount = 0
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val prepared = preparedManualEntry(
            profileId = "balanced",
            state = state,
            pServer = pServer,
            fanModeRaw = { mode.toString() },
            // Injects on the SECOND sleeper call, not the first: the first is now the
            // vendor-reaction settle the port waits out before writing duty, and a reset there
            // would be the ordinary entry case this suite covers one test up. This test is about a
            // reset that arrives LATER, after entry, and it must still fail closed with no replay.
            sleeper = {
                if (settleCount++ == 1) {
                    state[HardwareProbe.FAN_DUTY] = "25000"
                }
            },
        )

        val result = prepared.port.execute(prepared.mutation)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail.contains("duty drift"))
        assertEquals(
            listOf(
                "settings put system fan_mode 6",
                "echo 12500>${HardwareProbe.FAN_DUTY}",
            ),
            pServer.commands,
        )
    }

    @Test
    fun `Smart duty drift above the profile floor is handed off exactly once`() {
        val state = fanState(duty = 12_500)
        var mode = TuningPlanner.FAN_SMART
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val prepared = preparedManualEntry(
            profileId = "balanced",
            state = state,
            pServer = pServer,
            fanModeRaw = { mode.toString() },
        )
        state[HardwareProbe.FAN_DUTY] = "15000"

        assertEquals(CheckedCommandStatus.CONFIRMED, prepared.port.execute(prepared.mutation).status)
        assertEquals(
            listOf(
                "settings put system fan_mode 6",
                "echo 15000>${HardwareProbe.FAN_DUTY}",
            ),
            pServer.commands,
        )
        assertEquals("15000", state.getValue(HardwareProbe.FAN_DUTY))
        // Exactly one DUTY write is the property this test is named for; the mode write beside it
        // is a separate command since 2026-08-25 (the platform's own entry write has to land in
        // between). Counted rather than asserted as a total, so the count cannot silently absorb a
        // replay the way `commands.size == 1` would have.
        assertEquals(1, pServer.commands.count { it.startsWith("echo ") })

        val issuedBefore = pServer.commands.size
        mode = TuningPlanner.FAN_SMART
        assertEquals(CheckedCommandStatus.FAILED, prepared.port.execute(prepared.mutation).status)
        assertEquals("a refused second entry must reach hardware zero times", issuedBefore, pServer.commands.size)
    }

    // the 20% floor is retired for every profile, so a Smart duty that
    // drifted below the old 20% threshold (e.g. 9999/50000) is no longer rejected -- it is
    // exactly the kind of low idle duty the floor used to block. Renamed from "...below twenty
    // percent executes zero mode6" to reflect the new admitted behavior.
    @Test
    fun `Balanced and Max Smart duty admits a drift that would have been below the retired floor`() {
        listOf("balanced", "performance").forEach { profileId ->
            val state = fanState(duty = 12_500)
            var mode = TuningPlanner.FAN_SMART
            val pServer = MutableFanPServer(state) { value -> mode = value }
            val prepared = preparedManualEntry(
                profileId = profileId,
                state = state,
                pServer = pServer,
                fanModeRaw = { mode.toString() },
            )
            state[HardwareProbe.FAN_DUTY] = "9999"

            assertEquals(profileId, CheckedCommandStatus.CONFIRMED, prepared.port.execute(prepared.mutation).status)
            assertEquals(
                profileId,
                listOf(
                "settings put system fan_mode 6",
                "echo 9999>${HardwareProbe.FAN_DUTY}",
            ),
                pServer.commands,
            )
            assertEquals(profileId, TuningPlanner.FAN_MANUAL, mode)
            assertEquals(profileId, "9999", state.getValue(HardwareProbe.FAN_DUTY))
        }
    }

    // Fan handoff regression: the case this
    // pins -- a live duty-node reading that happens to equal the period got chained straight
    // through this handoff and commanded a full-speed fan. This must be refused BY CONSTRUCTION,
    // not merely by coincidence of the fixtures never hitting it.
    @Test
    fun `Smart duty reading exactly the period is refused, never chained into the handoff`() {
        val state = fanState(duty = 12_500)
        var mode = TuningPlanner.FAN_SMART
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val prepared = preparedManualEntry(
            profileId = "balanced",
            state = state,
            pServer = pServer,
            fanModeRaw = { mode.toString() },
        )
        state[HardwareProbe.FAN_DUTY] = "50000" // == period: the untrusted, full-duty reading

        val result = prepared.port.execute(prepared.mutation)

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail.contains("implies the full period"))
        // The refusal happens before any command is issued -- no fan_mode write, no duty write,
        // exactly the "never chain a redundant/untrusted write" posture Part 1 also relies on.
        assertEquals(emptyList<String>(), pServer.commands)
        assertEquals(TuningPlanner.FAN_SMART, mode)
    }

    // Regression guard for the fix above: the very next duty value below the period must still
    // pass through unchanged and exact, exactly like the existing non-round-number drift cases
    // (9999, 15000) already pinned above -- the new ceiling must not round or clamp anything
    // legitimate, only refuse the single value that equals the period.
    @Test
    fun `Smart duty reading one below the period is handed off exactly, unrounded`() {
        val state = fanState(duty = 12_500)
        var mode = TuningPlanner.FAN_SMART
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val prepared = preparedManualEntry(
            profileId = "balanced",
            state = state,
            pServer = pServer,
            fanModeRaw = { mode.toString() },
        )
        state[HardwareProbe.FAN_DUTY] = "49999" // period - 1: the last admissible reading

        assertEquals(CheckedCommandStatus.CONFIRMED, prepared.port.execute(prepared.mutation).status)
        assertEquals(
            listOf(
                "settings put system fan_mode 6",
                "echo 49999>${HardwareProbe.FAN_DUTY}",
            ),
            pServer.commands,
        )
        assertEquals("49999", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(TuningPlanner.FAN_MANUAL, mode)
    }

    @Test
    fun `Smart period drift executes zero mode6`() {
        val state = fanState(duty = 12_500)
        var mode = TuningPlanner.FAN_SMART
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val prepared = preparedManualEntry(
            profileId = "balanced",
            state = state,
            pServer = pServer,
            fanModeRaw = { mode.toString() },
        )
        state[HardwareProbe.FAN_PERIOD] = "49999"

        assertEquals(CheckedCommandStatus.FAILED, prepared.port.execute(prepared.mutation).status)
        assertEquals(emptyList<String>(), pServer.commands)
        assertEquals(TuningPlanner.FAN_SMART, mode)
    }

    @Test
    fun `Smart mode drift at the first mutation boundary executes zero mode6`() {
        val state = fanState(duty = 12_500)
        var modeReads = 0
        val pServer = MutableFanPServer(state) {}
        val prepared = preparedManualEntry(
            profileId = "balanced",
            state = state,
            pServer = pServer,
            fanModeRaw = {
                modeReads += 1
                if (modeReads == 1) {
                    TuningPlanner.FAN_SMART.toString()
                } else {
                    TuningPlanner.FAN_SPORT.toString()
                }
            },
        )

        assertEquals(CheckedCommandStatus.FAILED, prepared.port.execute(prepared.mutation).status)
        assertEquals(emptyList<String>(), pServer.commands)
        assertEquals(2, modeReads)
    }

    @Test
    fun `quiet manual entry accepts exact Smart zero duty without clamping`() {
        val state = fanState(duty = 0)
        var mode = TuningPlanner.FAN_SMART
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val prepared = preparedManualEntry(
            profileId = "quiet",
            state = state,
            pServer = pServer,
            fanModeRaw = { mode.toString() },
        )

        val result = prepared.port.execute(prepared.mutation)

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals("0", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(
            listOf(
                "settings put system fan_mode 6",
                "echo 0>${HardwareProbe.FAN_DUTY}",
            ),
            pServer.commands,
        )
    }

    // the 20% floor is retired for every profile -- 0 is now the only
    // qualified floor for "balanced" too. This still rejects, but for a different reason than
    // when this test was named "below twenty percent": the caller's stale minimumDutyPercent
    // (20) no longer matches the required floor (0), regardless of the duty value. Renamed from
    // "balanced handoff below twenty percent is rejected before mode6".
    @Test
    fun `balanced handoff with a stale nonzero floor is rejected before mode6`() {
        val sample = FanControlSample(
            cpuSafetyTempC = 45,
            hottestCpuTempC = 45,
            gpuTempC = 43,
            vendorFanMode = TuningPlanner.FAN_SMART,
            duty = 9_999,
            period = 50_000,
            rpm = 800,
        )

        assertEquals(
            null,
            ManualFanHandoffCapture.capture(
                profileId = "balanced",
                ownerRequestId = "owner",
                source = ManualFanHandoffSource.Session(1, "tx-1"),
                minimumDutyPercent = 20,
                sample = sample,
                nowElapsedRealtimeMs = 1_000,
            ),
        )
    }

    @Test
    fun `stale identity and age handoffs execute zero mode6 commands`() {
        data class Case(
            val label: String,
            val baselineDuty: Int = 12_500,
            val identityTransactionId: String = "source-tx",
            val capturedAt: Long = 900,
            val now: Long = 1_000,
        )
        listOf(
            Case("identity", identityTransactionId = "other-tx"),
            Case("age", capturedAt = 0, now = 20_000),
        ).forEach { case ->
            val state = fanState(duty = 12_500)
            var mode = TuningPlanner.FAN_SMART
            val pServer = MutableFanPServer(state) { value -> mode = value }
            val prepared = preparedManualEntry(
                profileId = "balanced",
                state = state,
                pServer = pServer,
                fanModeRaw = { mode.toString() },
                baselineDuty = case.baselineDuty,
                identityTransactionId = case.identityTransactionId,
                capturedElapsedRealtimeMs = case.capturedAt,
                nowElapsedRealtimeMs = case.now,
            )

            assertEquals(case.label, CheckedCommandStatus.FAILED, prepared.port.execute(prepared.mutation).status)
            assertEquals(case.label, emptyList<String>(), pServer.commands)
            assertEquals(case.label, TuningPlanner.FAN_SMART, mode)
        }
    }

    @Test
    fun `manual restore pins full duty before returning captured vendor mode`() {
        val state = fanState(duty = 15_000)
        var mode = TuningPlanner.FAN_MANUAL
        val sleeps = mutableListOf<Long>()
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = { sleeps += it },
        )

        val result = port.execute(
            fanMutation(TuningPlanner.FAN_SMART).copy(capturedSnapshotAuthority = true),
        )

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(TuningPlanner.FAN_SMART, mode)
        assertEquals("50000", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(
            listOf("echo 50000 > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4"),
            pServer.commands,
        )
        assertEquals(List(6) { 500L }, sleeps)
    }

    // device evidence (see TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES) shows the vendor's
    // duty loop stops contesting duty during a live Sport session, same as Manual. The
    // SPORT-to-vendor handback transition itself is UNVERIFIED / UNKNOWN -- no device measurement
    // covers what happens to duty at that specific boundary -- so release forces full duty before
    // handback from Sport too, not only from Manual, as a defensive precaution.
    @Test
    fun `manual restore pins full duty before returning captured vendor mode from Sport too`() {
        val state = fanState(duty = 15_000)
        var mode = TuningPlanner.FAN_SPORT
        val sleeps = mutableListOf<Long>()
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = { sleeps += it },
        )

        val result = port.execute(
            fanMutation(TuningPlanner.FAN_SMART).copy(capturedSnapshotAuthority = true),
        )

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(TuningPlanner.FAN_SMART, mode)
        assertEquals("50000", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(
            listOf("echo 50000 > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4"),
            pServer.commands,
        )
        assertEquals(List(6) { 500L }, sleeps)
    }

    // PServerHardwareCommandPort.forceCoolingSmartReassert
    // is the unconditional sibling of restoreCapturedFanMode used only by PostControlThermalWatch
    // -- see both classes' own kdoc. These tests exercise it directly (it is not reachable
    // through execute(LogicalMutation) at all -- it is a separate public entry point), reusing
    // this file's existing fan-fixture harness.

    @Test
    fun `force-cooling reassert forces full duty and re-writes Smart even though the live mode is already Smart`() {
        val state = fanState(duty = 3_500)
        var mode = TuningPlanner.FAN_SMART
        val sleeps = mutableListOf<Long>()
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = { sleeps += it },
        )

        val result = port.forceCoolingSmartReassert()

        // The whole point: restoreCapturedFanMode's own current==value skip would have elided
        // this entirely (mode was already Smart, and Smart is not duty-owned) -- this method
        // exists precisely because that elision is wrong for this caller.
        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(TuningPlanner.FAN_SMART, mode)
        assertEquals("50000", state.getValue(HardwareProbe.FAN_DUTY))
        assertEquals(
            listOf(
                "echo 50000 > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode " +
                    "${TuningPlanner.FAN_SMART}",
            ),
            pServer.commands,
        )
        assertEquals(List(6) { 500L }, sleeps)
    }

    @Test
    fun `force-cooling reassert refuses without any command when live mode is no longer Smart`() {
        val state = fanState(duty = 3_500)
        // Something else -- most plausibly a fresh Mimir session -- already reclaimed the fan.
        var mode = TuningPlanner.FAN_SPORT
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
        )

        val result = port.forceCoolingSmartReassert()

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(pServer.commands.isEmpty())
        assertEquals(TuningPlanner.FAN_SPORT, mode) // untouched
        assertEquals("3500", state.getValue(HardwareProbe.FAN_DUTY)) // untouched
    }

    @Test
    fun `force-cooling reassert fails closed when vendor mode drifts during bounded settlement`() {
        val state = fanState(duty = 3_500)
        var mode = TuningPlanner.FAN_SMART
        var settle = 0
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {
                settle += 1
                if (settle == 3) mode = TuningPlanner.FAN_SPORT
            },
        )

        val result = port.forceCoolingSmartReassert()

        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertEquals(3, settle)
    }

    /**
     * **Rewritten 2026-08-23, deliberately, and the old name is kept in this KDoc so the change is
     * findable: `manual restore fails closed when vendor mode drifts during bounded settlement`.**
     *
     * That test pinned a real duty and I am overriding it, so the duty is stated before it goes.
     * Failing closed here meant: if the vendor reclaimed `fan_mode` inside the settle window, the
     * restore reported FAILED. `attemptFullRestore` returns on the first non-CONFIRMED restore
     * mutation and `RestoreMutationOrder` puts the fan FIRST for a duty-owned session — so that
     * verdict aborted the restore before the CPU and GPU mutations ran at all.
     *
     * It is the wrong duty for two reasons, and note the tempting one is NOT among them: Mimir
     * ceilings are underclocks, so aborting leaves compute *cooler* than Stock, not hotter.
     *  - **Category.** After the write, fan authority belongs to the vendor. A vendor rewrite in
     *    that window is the new owner exercising ownership, not a failed restore.
     *  - **Convergence.** FAILED means RESTORE_FAILED, which the supervisor answers with a bounded
     *    retry — a loop against a device that persistently rewrites, which is the churn class that
     *    made the Nova unusable earlier the same day.
     *
     * The hazard non-fatal opens is closed in the same change and is what this test now pins: a
     * vendor that lands on a mode which STOPS its own regulation loop would leave Stock clocks with
     * nothing regulating the fan, and `forceCoolingSmartReassert` cannot rescue that state because
     * it refuses unless the mode is already Smart. So a loop-stopper gets one corrective write.
     * [[narrowing-by-name-deletes-the-second-duty]] — the second duty here was thermal, and it is
     * carried forward rather than dropped.
     */
    @Test
    fun `a vendor reclaim during restore settlement is reported, corrected, and does not abort the restore`() {
        val state = fanState(duty = 15_000)
        var mode = TuningPlanner.FAN_MANUAL
        var settle = 0
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {
                settle += 1
                // SPORT stops the vendor's own regulation loop -- the case that must be corrected.
                if (settle == 3) mode = TuningPlanner.FAN_SPORT
            },
        )

        val result = port.execute(
            fanMutation(TuningPlanner.FAN_SMART).copy(capturedSnapshotAuthority = true),
        )

        assertEquals(
            "the restore must CONTINUE so the compute axes still return to Stock",
            CheckedCommandStatus.CONFIRMED,
            result.status,
        )
        assertEquals("the full bounded window must still be observed", 6, settle)
        assertTrue(
            "the reclaim must be named in the detail, not swallowed: ${result.detail}",
            result.detail.contains("reclaimed fan mode ${TuningPlanner.FAN_SPORT}"),
        )
        assertTrue(
            "a loop-stopping mode must get one corrective write back to vendor Smart: ${result.detail}",
            result.detail.contains("corrected to vendor Smart"),
        )
        assertEquals(
            "and the device must actually be left regulating",
            TuningPlanner.FAN_SMART,
            mode,
        )
    }

    @Test
    fun `a vendor reclaim to a still-regulating mode is reported but not corrected`() {
        // The corrective is scoped to modes that stop the loop. If the vendor lands somewhere that
        // still regulates, Mimir has no business writing over the owner it just handed back to.
        val state = fanState(duty = 15_000)
        var mode = TuningPlanner.FAN_MANUAL
        var settle = 0
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = {
                settle += 1
                if (settle == 3) mode = 7
            },
        )

        val result = port.execute(
            fanMutation(TuningPlanner.FAN_SMART).copy(capturedSnapshotAuthority = true),
        )

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue(result.detail.contains("reclaimed fan mode 7"))
        assertTrue(
            "no corrective write for a mode that still regulates: ${result.detail}",
            !result.detail.contains("corrected to vendor Smart"),
        )
        assertEquals("and Mimir must not have written over it", 7, mode)
    }

    @Test
    fun `already restored Odin fan mode still holds the compute settlement barrier`() {
        val state = fanState(duty = 15_000)
        var mode = TuningPlanner.FAN_SMART
        val sleeps = mutableListOf<Long>()
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = { sleeps += it },
        )

        val result = port.execute(
            fanMutation(TuningPlanner.FAN_SMART).copy(capturedSnapshotAuthority = true),
        )

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue(pServer.commands.isEmpty())
        assertEquals(List(6) { 500L }, sleeps)
    }

    @Test
    fun `manual entry readback ambiguity fails closed`() {
        val state = fanState(duty = 12_500)
        var mode = TuningPlanner.FAN_SMART
        val pServer = object : PServerClient() {
            var commandCount = 0

            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commandCount += 1
                mode = TuningPlanner.FAN_MANUAL
                state[HardwareProbe.FAN_DUTY] = "1"
                return CheckedCommandResult(CheckedCommandStatus.AMBIGUOUS)
            }
        }
        val prepared = preparedManualEntry(
            profileId = "balanced",
            state = state,
            pServer = pServer,
            fanModeRaw = { mode.toString() },
        )

        assertEquals(
            CheckedCommandStatus.AMBIGUOUS,
            prepared.port.execute(prepared.mutation).status,
        )
        assertEquals(1, pServer.commandCount)
    }

    private class MutableFanPServer(
        private val state: MutableMap<String, String>,
        private val setMode: (Int) -> Unit,
    ) : PServerClient() {
        val commands = mutableListOf<String>()

        override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
            commands += command
            command.split(Regex("&&|;"))
                .map(String::trim)
                .forEach { step ->
                    Regex("settings put system fan_mode ([0-9]+)")
                        .matchEntire(step)
                        ?.groupValues?.get(1)?.toIntOrNull()?.let(setMode)
                    Regex("echo ([0-9]+) ?(?:>|\\>) ?(${Regex.escape(HardwareProbe.FAN_DUTY)})")
                        .matchEntire(step)
                        ?.let { match -> state[match.groupValues[2]] = match.groupValues[1] }
                }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
        }
    }

    private class FixedReceiptPServer(
        private val status: CheckedCommandStatus,
    ) : PServerClient() {
        var commandCount = 0

        override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
            commandCount += 1
            return CheckedCommandResult(status, detail = "fixed receipt")
        }
    }

    // HandheldDeviceProfiles.resolve now gates fan authority on live node
    // presence for every device (see AndroidHardware.SystemReader's directExists seam), so a fixed
    // fan-node path must actually report present or PServerFanDutyMutationPort.write's own
    // `HandheldDeviceProfiles.resolve(...).fanNodes ?: return failed(...)` line fails every test in
    // this file. `state` already curates exactly the three fan paths (see fanState below) plus
    // whatever a test adds to it, so node presence is derived from the same map, not a new fixture.
    private fun reader(state: Map<String, String>) = SystemReader(
        directRead = { path -> state[path] },
        directExists = { path -> path in state },
    )

    private fun fanState(duty: Int = 10_000) = mutableMapOf(
        HardwareProbe.FAN_DUTY to duty.toString(),
        HardwareProbe.FAN_PERIOD to "50000",
        HardwareProbe.FAN_SPEED to "2000",
    )

    private fun fanMutation(value: Int) = LogicalMutation(
        logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
        axis = TuningAxis.FAN,
        canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
        kind = LogicalMutationKind.SETTINGS_PUT,
        value = value,
        restoreMode = null,
        expectPresent = true,
    )

    private data class PreparedManualEntry(
        val port: PServerHardwareCommandPort,
        val mutation: LogicalMutation,
    )

    private fun preparedManualEntry(
        profileId: String,
        state: MutableMap<String, String>,
        pServer: PServerClient,
        fanModeRaw: () -> String?,
        baselineDuty: Int = requireNotNull(state[HardwareProbe.FAN_DUTY]).toInt(),
        baselinePeriod: Int = 50_000,
        identityTransactionId: String = "source-tx",
        capturedElapsedRealtimeMs: Long = 900,
        nowElapsedRealtimeMs: Long = 1_000,
        sleeper: (Long) -> Unit = {},
    ): PreparedManualEntry {
        val baselineIdentity = ManualFanHandoffIdentity(
            profileId,
            "owner",
            ManualFanHandoffSource.Session(1, "source-tx"),
        )
        val portIdentity = baselineIdentity.copy(
            source = ManualFanHandoffSource.Session(1, identityTransactionId),
        )
        // the 20% floor is retired for every profile, so this
        // manual-handoff floor fixture is unconditionally 0 now. This specific value was
        // already 0 for "quiet" (unchanged for quiet here); balanced/performance move from 20
        // to 0. Quiet's separate CONTROLLER floor (FanThermalProfile.minSpinPercent) was still
        // 20 before this change and changes too -- see FanThermalControlTest.kt.
        val floor = 0
        val store = ManualFanHandoffStore().also { pending ->
            assertTrue(
                pending.prepare(
                    ManualFanHandoffBaseline(
                        profileId = profileId,
                        ownerRequestId = baselineIdentity.ownerRequestId,
                        source = baselineIdentity.source,
                        observedDuty = baselineDuty,
                        entryDuty = baselineDuty,
                        period = baselinePeriod,
                        cpuSafetyTempC = 45,
                        minimumDutyPercent = floor,
                        capturedElapsedRealtimeMs = capturedElapsedRealtimeMs,
                    ),
                ),
            )
        }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = fanModeRaw,
            sleeper = sleeper,
            manualFanHandoffStore = store,
            manualFanHandoffIdentity = { portIdentity },
            elapsedRealtimeMs = { nowElapsedRealtimeMs },
        )
        val plan = TuningPlan(
            profileId = profileId,
            packageName = "com.gruning.mimir.rewrite",
            cpuMaxKhz = mapOf(0 to 1_000),
            gpuMaxPowerLevel = 0,
            fanMode = TuningPlanner.FAN_MANUAL,
            calibrated = true,
        )
        val mutation = port.plannedMutations(plan, odinSnapshot()).first {
            it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID
        }
        return PreparedManualEntry(port, mutation)
    }

    private fun odinSnapshot() = HardwareSnapshot(
        schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
        hardwareFingerprint = HardwareFingerprint.create(odinCapabilities()),
        bootIdentity = "boot",
        nodes = listOf(
            TopologyNodeState(
                logicalId = "cpu:0:scaling_max_freq",
                axis = TuningAxis.CPU,
                canonicalPath = HardwareProbe.CPU_QOS_MAX_PATH,
                originalValue = 1_000,
                originalMode = "444",
                originalUid = 0,
                originalGid = 0,
                present = true,
            ),
            TopologyNodeState(
                logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                axis = TuningAxis.GPU,
                canonicalPath = HardwareProbe.GPU_MIN_LEVEL,
                originalValue = 0,
                originalMode = "644",
                originalUid = 0,
                originalGid = 0,
                present = true,
            ),
            TopologyNodeState(
                logicalId = HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                axis = TuningAxis.GPU,
                canonicalPath = HardwareProbe.GPU_MAX_LEVEL,
                originalValue = 0,
                originalMode = "644",
                originalUid = 0,
                originalGid = 0,
                present = true,
            ),
            TopologyNodeState(
                logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                axis = TuningAxis.FAN,
                canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                originalValue = TuningPlanner.FAN_SMART,
                originalMode = null,
                originalUid = null,
                originalGid = null,
                present = true,
            ),
        ),
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

    // Mimir write-clock coverage: an ordinary apply's plain
    // `settings put system fan_mode` is a Mimir-initiated write too. The vendor loop restarts from
    // whatever duty Mimir last drove and observeVendorSmartDuty keeps a per-bucket MAX, so the port
    // must stamp its write clock on CONFIRMED -- and only when it actually wrote.
    @Test
    fun `an ordinary fan_mode write stamps the vendor write clock on CONFIRMED, a skipped rewrite does not`() {
        val state = fanState(duty = 12_500)
        var mode = TuningPlanner.FAN_SPORT
        val pServer = MutableFanPServer(state) { value -> mode = value }
        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { mode.toString() },
            sleeper = { },
            elapsedRealtimeMs = { 4_242L },
        )
        assertEquals(null, port.lastVendorFanModeWriteElapsedRealtimeMs())

        val written = port.execute(fanMutation(TuningPlanner.FAN_SMART))
        assertEquals(CheckedCommandStatus.CONFIRMED, written.status)
        assertEquals(TuningPlanner.FAN_SMART, mode)
        assertEquals(listOf("settings put system fan_mode 4"), pServer.commands)
        assertEquals(4_242L, port.lastVendorFanModeWriteElapsedRealtimeMs())

        val skipping = PServerHardwareCommandPort(
            pServer = MutableFanPServer(state) { value -> mode = value },
            reader = reader(state),
            capabilities = ::odinCapabilities,
            fanModeRaw = { TuningPlanner.FAN_SMART.toString() },
            sleeper = { },
            elapsedRealtimeMs = { 9_999L },
        )
        assertEquals(CheckedCommandStatus.CONFIRMED, skipping.execute(fanMutation(TuningPlanner.FAN_SMART)).status)
        assertEquals("a skipped rewrite is not a write", null, skipping.lastVendorFanModeWriteElapsedRealtimeMs())
    }
}
