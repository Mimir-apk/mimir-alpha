package com.gruning.mimir.tweaker

import android.os.PowerManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several tests below carry a "WAVE1A test N" lineage marker. Those numbers refer to a
 * `WAVE1A_SPEC.md` that was **never committed to any ref** and does not exist anywhere in this
 * tree's history -- the same phantom class as the retired `FAN_OWNERSHIP_SPEC.md` citations. The
 * numbering is kept only as history: what each test actually pins is its own assertions, and the
 * WAVE2 behaviour that superseded WAVE1A is stated inline where it differs. Do not go looking for
 * the document, and do not treat the numbering as an authority.
 */
class HardwareSessionSupervisorTest {
    // ── SYSTEM lease semantics ─────────────────────────────────────────────────────────────────
    // Admitted SYSTEM sessions share the twelve-hour hard deadline regardless of admission provenance.
    // Admission still uses SupportedUncertifiedProfiles.admitsPlan and live-OPP revalidation.
    // The attended lease axis is removed: ActiveSessionIdentity.uncertified,
    // UNCERTIFIED_ATTENDED_LEASE_MS, uncertifiedAttendedLeaseWindowMs, DeviceAttendedPort and
    // extendAttendedLeaseIfPermitted no longer exist. No public renewal path extends SYSTEM;
    // it expires through HARD_LEASE_EXPIRED. SystemWideProfileSupervisorTest covers the minimal
    // fixture, and this block also checks the fuller Env fixture.

    @Test
    fun `a SYSTEM session takes the hard deadline unconditionally and never renews`() {
        val env = Env()
        env.adopt(scope = ActiveSessionScope.SYSTEM, hardDeadlineMs = 120_000L)
        assertEquals(
            "SYSTEM lease must BE the hard deadline from adoption",
            env.supervisor.activeSession()!!.hardLeaseDeadlineMs,
            env.supervisor.activeSession()!!.leaseDeadlineMs,
        )
        env.now = 119_000L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(
            "lease must be unmoved by ticking -- nothing renews a SYSTEM session",
            120_000L,
            env.supervisor.activeSession()!!.leaseDeadlineMs,
        )
        assertFalse("leaseRenewable must stay false for SYSTEM", env.supervisor.leaseRenewable())
        assertFalse(
            "renewLease must stay false for SYSTEM",
            env.supervisor.renewLease(
                ownerUid = 42,
                generation = 7L,
                transactionId = "tx-7",
                requestId = "req-7",
            ),
        )
        env.now = 120_000L
        assertEquals(
            "the retired attended-lease reason must never appear again",
            "HARD_LEASE_EXPIRED",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
        // The twelve-hour outer bound the lease policy leaves untouched.
        assertEquals(43_200_000L, ProductOwnerHardLeasePolicy.HARD_LEASE_MS)
    }

    @Test
    fun `activation grace requires exact target foreground evidence`() {
        val env = Env()
        env.workload = WorkloadTruth.INDETERMINATE
        env.adopt()
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now = 10_000L
        val decision = env.supervisor.tick()
        assertTrue(decision is SupervisorDecision.Restore)
        assertEquals("ACTIVATION_TIMEOUT", (decision as SupervisorDecision.Restore).reason)
    }

    @Test
    fun `activation grace passes after exact target foreground`() {
        val env = Env()
        env.adopt()
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertTrue(env.supervisor.activeSession()!!.targetEverForeground)
        env.now = 5_000L
        assertTrue(
            env.supervisor.renewLease(
                ownerUid = 42,
                generation = 7L,
                transactionId = "tx-7",
                requestId = "req-7",
            ),
        )
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        env.now = 20_000L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
    }

    @Test
    fun `historical pause stop waits for exact launch activation then times out`() {
        val env = Env()
        env.adopt(everForeground = false)
        env.workload = WorkloadTruth.TARGET_PAUSED_OR_STOPPED
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now = 10_000L
        assertEquals("ACTIVATION_TIMEOUT", (env.supervisor.tick() as SupervisorDecision.Restore).reason)
    }

    @Test
    fun `pause after this launch foreground latch restores immediately`() {
        val env = Env()
        env.adopt(everForeground = false)
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now = 1_000L
        env.workload = WorkloadTruth.TARGET_PAUSED_OR_STOPPED
        assertEquals("TARGET_PAUSED_OR_STOPPED", (env.supervisor.tick() as SupervisorDecision.Restore).reason)
    }

    @Test
    fun `pause stop and other foreground restore after target was observed`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.TARGET_PAUSED_OR_STOPPED
        assertEquals("TARGET_PAUSED_OR_STOPPED", (env.supervisor.tick() as SupervisorDecision.Restore).reason)

        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.OTHER_FOREGROUND
        assertEquals("TARGET_LOSS", (env.supervisor.tick() as SupervisorDecision.Restore).reason)

        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.ACCESS_DENIED
        assertEquals("WORKLOAD_ACCESS_DENIED", (env.supervisor.tick() as SupervisorDecision.Restore).reason)

        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.QUERY_FAILED
        assertEquals("WORKLOAD_QUERY_FAILED", (env.supervisor.tick() as SupervisorDecision.Restore).reason)
    }

    @Test
    fun `lease renewal rejects wrong identity expired or retrying and expiry uses exact deadline`() {
        val env = Env()
        env.adopt()
        assertFalse(env.supervisor.renewLease(99, 7L, "tx-7", "req-7"))
        assertFalse(env.supervisor.renewLease(42, 8L, "tx-7", "req-7"))
        assertFalse(env.supervisor.renewLease(42, 7L, "tx-other", "req-7"))
        assertFalse(env.supervisor.renewLease(42, 7L, "tx-7", "req-other"))
        env.now = 1_000L
        assertTrue(env.supervisor.renewLease(42, 7L, "tx-7", "req-7"))
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        env.supervisor.tick()
        env.now = env.supervisor.activeSession()!!.leaseDeadlineMs
        assertFalse(env.supervisor.renewLease(42, 7L, "tx-7", "req-7"))
        assertEquals("LEASE_EXPIRED", (env.supervisor.tick() as SupervisorDecision.Restore).reason)

        env.adopt(everForeground = true)
        env.restorer.result = TransactionResult(false, false, false, "RESTORE_FAILED", "kept", transactionId = "tx-7")
        env.supervisor.execute(SupervisorDecision.Restore(7L, "tx-7", "THERMAL_CPU"))
        assertFalse(env.supervisor.renewLease(42, 7L, "tx-7", "req-7"))
    }

    @Test
    fun `renewals are finite and cannot move the immutable hard deadline`() {
        val env = Env()
        env.adopt(everForeground = true)
        val hardDeadline = env.supervisor.activeSession()!!.hardLeaseDeadlineMs

        for (now in listOf(1_000L, 30_000L, 59_000L, 88_000L, 117_000L)) {
            env.now = now
            assertTrue(env.supervisor.renewLease(42, 7L, "tx-7", "req-7"))
            val active = env.supervisor.activeSession()!!
            assertEquals(hardDeadline, active.hardLeaseDeadlineMs)
            assertTrue(active.leaseDeadlineMs <= hardDeadline)
        }
        assertEquals(hardDeadline, env.supervisor.activeSession()!!.leaseDeadlineMs)

        env.now = hardDeadline - 1L
        assertFalse(env.supervisor.renewLease(42, 7L, "tx-7", "req-7"))
        env.now = hardDeadline
        assertEquals(
            "HARD_LEASE_EXPIRED",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
    }

    @Test
    fun `exact lab renewals sustain five minute observation but never cross six minute hard cap`() {
        val env = Env()
        env.adopt(
            everForeground = true,
            hardDeadlineMs = DiagnosticsCalibrationHoldLeasePolicy.HARD_LEASE_MS,
            ownerTokenId = LAB_OWNER_TOKEN_ID,
        )
        for (second in 1L..359L) {
            env.now = second * 1_000L
            val renewed = env.supervisor.renewLease(
                ownerUid = 42,
                generation = 7L,
                transactionId = "tx-7",
                requestId = "req-7",
                ownerTokenId = LAB_OWNER_TOKEN_ID,
            )
            assertEquals(second <= 330L, renewed)
            assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
            assertEquals(
                DiagnosticsCalibrationHoldLeasePolicy.HARD_LEASE_MS,
                env.supervisor.activeSession()!!.hardLeaseDeadlineMs,
            )
        }
        env.now = DiagnosticsCalibrationHoldLeasePolicy.HARD_LEASE_MS
        assertEquals(
            "HARD_LEASE_EXPIRED",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
    }

    @Test
    fun `elapsed clock regression restores`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now = -1L
        assertEquals("ELAPSED_CLOCK_REGRESSION", (env.supervisor.tick() as SupervisorDecision.Restore).reason)
    }

    @Test
    fun `a hot chassis with cool silicon no longer derates`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.TARGET_FOREGROUND

        // Device truth, Odin 3, 2026-08-26 mid-game: Android SEVERE sustained, hottest SoC 80 C.
        // Before the corroboration gate this disabled the prime cluster despite stock behaviour
        // reaching 100 C on this chassis.
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_SEVERE, 80, 80, 300)
        repeat(SupervisorPolicy().androidThermalTripDebounceSamples + 10) {
            assertEquals(
                "skin is a comfort signal; it must not derate silicon that is not hot",
                SupervisorDecision.Continue,
                env.supervisor.tick(),
            )
        }

        // Raise the silicon to the incident this channel exists for and it fires immediately --
        // the streak is already long past the debounce.
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_SEVERE, 98, 80, 300)
        val derate = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_ANDROID", derate.reason)
    }

    @Test
    fun `thermal boundaries derate above thresholds and not at exact limits`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.TARGET_FOREGROUND

        // WAVE2: CPU/GPU exact limit is now 105 (policy, 10 C below the vendor's 115 C hot
        // trip); battery's 450 and android's SEVERE threshold are unchanged ("change its
        // response, not its sensitivity"). All four now derate, never restore (spec S3.1).
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_MODERATE, 105, 105, 450)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())

        // ANDROID/BATTERY (WAVE1A D1, mechanism unchanged by WAVE2): TRIP-band values still need a
        // consecutive streak -- the exact-limit value above never trips, no matter how many times
        // it repeats, since it never leaves CLEAR.
        //
        // **2026-08-26: the ANDROID streak is no longer three.** After reported
        // clock loss mid-game, the channel stays armed with a longer debounce. On this chassis SEVERE is a SKIN
        // threshold with LIGHT/MODERATE/SEVERE packed into two degrees (48.0/49.0/50.0), and the
        // measured peak skin temperature was 49.0 C -- one degree under it. Three seconds of that is a wobble,
        // not a thermal event. Every other channel keeps its fast path deliberately.
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_SEVERE, 105, 105, 450)
        repeat(SupervisorPolicy().androidThermalTripDebounceSamples - 1) {
            assertEquals(
                "a SEVERE streak shorter than the debounce must never derate",
                SupervisorDecision.Continue,
                env.supervisor.tick(),
            )
        }
        val android = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_ANDROID", android.reason)
        assertEquals(1, android.rungs)
        assertEquals(1, android.rung)

        // CPU (WAVE2 S3.4, rolling mean replaces the streak): exactly-at-limit does not trip even
        // once diluted into a window; one sample above it pulls the mean over.
        env.adopt(everForeground = true)
        env.now = 0L
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 105, 40, 300)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now = 400L
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 106, 40, 300)
        val cpu = env.supervisor.tick() as SupervisorDecision.Derate // mean(105,106)=105.5 > 105
        assertEquals("THERMAL_CPU", cpu.reason)
        assertEquals(1, cpu.rungs)
        assertEquals(1, cpu.rung)

        // GPU: symmetric.
        env.adopt(everForeground = true)
        env.now = 0L
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 105, 300)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now = 400L
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 106, 300)
        val gpu = env.supervisor.tick() as SupervisorDecision.Derate // mean(105,106)=105.5 > 105
        assertEquals("THERMAL_GPU", gpu.reason)
        assertEquals(1, gpu.rungs)
        assertEquals(1, gpu.rung)

        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 40, 451)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        val battery = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_BATTERY", battery.reason)
        assertEquals(1, battery.rungs)
        assertEquals(1, battery.rung)
    }

    // WAVE2: pins the recalibrated CPU/GPU trip constants to their vendor evidence so a future
    // reader sees WHY 105 and not a magic/regressible number. Source: Odin 3 (`reference device`) sysfs,
    // 2026-08-06 -- all eight `gpuss-0..7` zones and `cpu-0-0-0` agree `hot=115C` on both
    // domains; policy (WAVE2_THERMAL_DERATE_SPEC.md S3.3, 2026-08-06) puts the trip 10 C
    // below that, superseding WAVE1C's 95 C (the platform's first *passive* mitigation point).
    // This test breaks loudly if either constant ever drifts from that evidence without a
    // deliberate, re-grounded change to this comment and the one in HardwareSessionSupervisor.kt's
    // SupervisorPolicy.
    @Test
    fun `cpu and gpu trip constants are pinned 10 C below the vendor's hot threshold`() {
        val policy = SupervisorPolicy()
        assertEquals(105, policy.cpuTripCExclusive)
        assertEquals(105, policy.gpuTripCExclusive)
    }

    // Supersedes WAVE1A test 1, which pinned the consecutive-streak mechanism WAVE2
    // replaces for CPU/GPU (spec S3.4). WAVE2_THERMAL_DERATE_SPEC.md S5 test 1: a session's very
    // first-ever telemetry sample has no history to average against, so
    // "mean(samples in last 2000 ms)" degenerates to that one sample -- if it is already above
    // trip, the derate fires on tick one (exactly one rung, not a release) rather than waiting to
    // accumulate history it does not have. This is the safe direction to err. See the next test
    // for the contrasting case: a spike diluted by real preceding history does not derate.
    @Test
    fun `WAVE2 test 1 a sustained trace above trip derates exactly one rung not a release`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 106, 40, 300)

        val decision = env.supervisor.tick()
        assertTrue(decision is SupervisorDecision.Derate)
        decision as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", decision.reason)
        assertEquals(1, decision.rungs)
        assertEquals(1, decision.rung)
    }

    // Supersedes WAVE1A test 2, whose consecutive-streak "reset" semantics no longer
    // apply to CPU/GPU. WAVE2_THERMAL_DERATE_SPEC.md S5 test 2: a single spike above trip,
    // surrounded by real preceding cool samples inside the same 2 s window, does not derate --
    // the rolling MEAN stays diluted even though the instantaneous spike (106 C) is itself above
    // the 105 C trip ("rolling mean, not instantaneous").
    @Test
    fun `WAVE2 test 2 a single spike diluted by real history does not derate`() {
        val env = Env()
        env.adopt(everForeground = true)

        val cpuTraceC = listOf(80, 80, 80, 106, 80)
        cpuTraceC.forEachIndexed { index, cpuC ->
            env.now = index * 350L
            env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, cpuC, 40, 300)
            assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        }
    }

    // WAVE1A test 3, WAVE2-updated (spec S5 test 4): v > thermalCriticalC (112, which
    // subsumes the old "v > MAX" sane-maximum check -- 121 exceeds both) trips immediately, on
    // the first sample, no dwell -- and now derates two rungs rather than restoring (S3.1/S3.3).
    @Test
    fun `reading above the critical band derates two rungs immediately on the first sample`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 121, 40, 300)
        val cpu = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", cpu.reason)
        assertEquals(2, cpu.rungs)
        assertEquals(2, cpu.rung)

        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 121, 300)
        val gpu = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_GPU", gpu.reason)
        assertEquals(2, gpu.rungs)
        assertEquals(2, gpu.rung)
    }

    // WAVE1A test 4, WAVE2-updated: battery above 800 deci-C still trips immediately
    // (mapped, by the same analogy as CPU/GPU's critical band, to a 2-rung derate); battery in
    // (450, 800] still needs three consecutive samples (mechanism unchanged) and derates one
    // rung. Neither releases to Stock any more.
    @Test
    fun `battery above the sane maximum derates two rungs immediately battery trip band still needs three`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 40, 801)
        val critical = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_BATTERY", critical.reason)
        assertEquals(2, critical.rungs)

        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 40, 451)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        val trip = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_BATTERY", trip.reason)
        assertEquals(1, trip.rungs)
    }

    // WAVE1A test 5, WAVE2-updated: first-match order preserved -- simultaneous CPU and
    // GPU critical trips report THERMAL_CPU, now as a 2-rung derate.
    @Test
    fun `first match order preserved simultaneous CPU and GPU trips report THERMAL_CPU`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 121, 121, 300)
        val decision = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", decision.reason)
        assertEquals(2, decision.rungs)
    }

    // Beyond the 7 mandatory WAVE1A tests, still true under WAVE2's rolling mean: first-match
    // order is preserved sensor-by-sensor (CPU fully resolved -- CRITICAL then TRIP -- before
    // GPU is examined), not band-by-band across sensors. A GPU CRITICAL reading must not preempt
    // a CPU TRIP-band derate the rolling mean has already established.
    @Test
    fun `first match order holds across bands cpu trip band beats simultaneous gpu critical`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 106, 40, 300)
        env.now = 0L
        val first = env.supervisor.tick() as SupervisorDecision.Derate // cold start: mean(106)=106>105
        assertEquals("THERMAL_CPU", first.reason)
        assertEquals(1, first.rungs)
        assertEquals(1, first.rung)
        env.now = 400L
        val second = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", second.reason)
        assertEquals(2, second.rung)

        // A simultaneous GPU CRITICAL reading must not preempt CPU's already-established
        // TRIP-band derate -- CPU is fully resolved first, GPU is never reached this tick.
        env.now = 800L
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 106, 121, 300)
        val third = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", third.reason)
        assertEquals(1, third.rungs)
        assertEquals(3, third.rung)
    }

    // WAVE1A test 6 (double-count guard), WAVE2-updated: the risk shifts from the old
    // per-call streak counter (a duplicate append of an IDENTICAL sample cannot change a rolling
    // MEAN, so that specific risk no longer applies to CPU/GPU ) to the
    // ThermalDerateLadder rung mutation itself, which is stateful and must still commit exactly
    // once per tick even though evaluateTelemetry() defensively re-reads the same cached outcome.
    // One qualifying tick must derate by exactly one rung, not two.
    @Test
    fun `double count guard a single qualifying tick advances the ladder rung exactly once`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 106, 40, 300)

        val decision = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals(1, decision.rungs)
        assertEquals(1, decision.rung)
    }

    // WAVE1A test 7, WAVE2-updated: this WAVE1A fixture's own peak is exactly 105 C --
    // grounded against the OLD 95 C trip (it comfortably exceeded that) but never exceeds the
    // NEW 105 C trip even once, consistent with spec S3.3's own reasoning for choosing 105
    // ("Performance peaked at 98.4 C, so 105 does not fire on a healthy run" -- this fixture is
    // the same era/character of session). This replay is now a negative confirmation: WAVE2 does
    // not regress this historical run into spurious derates. It is not a substitute for the
    // WAVE2 regression proof (the next test), which replays a real trace that DOES exceed 105.
    // Fixture values are the real cpu_hot_mC column from .re5-session-evidence.tsv, truncated to
    // whole degrees exactly as the production telemetry path truncates (see the next test's
    // comment for the exact conversion). Fed as one continuous replay at the real ~350 ms device
    // cadence so the 2-second rolling window behaves as designed rather than degenerating to an
    // unbounded accumulation.
    @Test
    fun `replay of the captured WAVE1A RE5 session never exceeds the new 105 C trip`() {
        val env = Env()
        // SYSTEM scope + a hard deadline comfortably beyond the 106-sample * 350 ms = ~37 s
        // replay: this test is about thermal classification only, and the default 30 s LAUNCH
        // lease would otherwise expire mid-replay and mask the property under test behind an
        // unrelated LEASE_EXPIRED restore.
        env.adopt(everForeground = true, hardDeadlineMs = 120_000L, scope = ActiveSessionScope.SYSTEM)

        (NOISE_BAND_CPU_C + SUSTAINED_RUN_CPU_C).forEachIndexed { index, cpuC ->
            env.now = index * 350L
            env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, cpuC, 40, 300)
            assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        }
    }

    // Rolling-mean debounce regression: THE regression proof for the debounce defect,
    // and the most important test in this suite. Real CPU samples, truncated to whole degrees
    // exactly as the production telemetry path truncates
    // (LivePerformanceTelemetry.kt's `normalizedThermalCelsius(raw).toInt()`, which truncates
    // toward zero, e.g. 105.8 C -> 105), from Odin 3 capture `RE-RUN-1315.tsv` (tweaker build
    // `0b2fbaf8...`, 2026-08-06), sample indices 928-937 at their real device-clock spacing:
    //   928=105.0 929=103.1 930=105.4 931=105.0 932=105.8 933=105.4 934=106.2 935=105.4 936=105.0
    //   937=105.4   (truncated: 105 103 105 105 105 105 106 105 105 105)
    //
    // This is deliberately NOT the illustrative "83.4/89.6/84.5/98.4/102.3" trace quoted in spec
    // S3.4/S5 test 3's prose. That exact snippet was independently verified against this same
    // capture (raw high-frequency rows around 08-06 17:18:42.782-45.648) and its own honest
    // 2-second window never exceeds roughly 103 C mean even before truncation, so it cannot
    // regression-test the new 105 C trip -- consistent with spec S2's own admission that
    // "Mimir's CPU trip never fired" the night this was captured (that release was
    // THERMAL_ANDROID at SEVERE, not a CPU trip). This window is a later, genuinely-105+-crossing
    // stretch from the SAME session and the same oscillating character -- hugging the trip line,
    // straddling it sample to sample -- that actually exercises the fix; 105C only barely and rarely clears in this capture even in its hottest post-rollback regime.
    //
    // Every individual truncated sample here is AT (not above) 105 except one (934 = 106); since
    // `cpuTripCExclusive` is exclusive, a consecutive-streak debounce needs three *consecutive*
    // samples strictly above 105 and never gets even two here (only sample 934 ever clears it).
    // The 2-second rolling mean, by contrast, correctly reads this as sustained: it first crosses
    // 105 at sample 937 (mean of samples 930-937 = 841/8 = 105.125).
    @Test
    fun `WAVE2 test 3 real oscillating trace that hugs the trip line derates the debounce regression proof`() {
        val env = Env()
        env.adopt(everForeground = true)

        val realCaptureCpuC = listOf(105, 103, 105, 105, 105, 105, 106, 105, 105, 105)
        val realCaptureAtMs = listOf(0L, 244L, 506L, 762L, 1022L, 1277L, 1539L, 1810L, 2073L, 2318L)

        var lastDecision: SupervisorDecision = SupervisorDecision.Continue
        realCaptureCpuC.forEachIndexed { index, cpuC ->
            env.now = realCaptureAtMs[index]
            env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, cpuC, 40, 300)
            lastDecision = env.supervisor.tick()
            if (index < realCaptureCpuC.lastIndex) {
                assertEquals(SupervisorDecision.Continue, lastDecision)
            }
        }

        assertTrue(lastDecision is SupervisorDecision.Derate)
        val decision = lastDecision as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", decision.reason)
        assertEquals(1, decision.rungs)
    }

    @Test
    fun `present thermal trip evaluates even when another channel is missing`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        // CRITICAL so this stays a decisive single-sample assertion; the property under test is
        // precedence over a missing channel, not the rolling-mean window.
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 121, null, 300)
        val decision = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", decision.reason)
        assertEquals(2, decision.rungs)
    }

    @Test
    fun `per channel missing telemetry restores on third consecutive miss and resets only that channel`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, null, 40, 300)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals("MISSING_TELEMETRY_CPU", (env.supervisor.tick() as SupervisorDecision.Restore).reason)

        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, null, 40, 300)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 40, 40, 300)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, null, 40, 300)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals("MISSING_TELEMETRY_CPU", (env.supervisor.tick() as SupervisorDecision.Restore).reason)
    }

    @Test
    fun `three missing all-channel samples restore`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.workload = WorkloadTruth.TARGET_FOREGROUND
        env.sample = null
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals("MISSING_TELEMETRY_ANDROID", (env.supervisor.tick() as SupervisorDecision.Restore).reason)
    }

    @Test
    fun `matching authenticated target continues and concrete cpu drift restores without reapply`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(everForeground = true, targetContract = contract)

        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(0, env.restorer.calls)

        env.sample = env.sample!!.copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        val decision = env.supervisor.tick() as SupervisorDecision.Restore
        assertEquals("TARGET_CONTRACT_DRIFT_CPU", decision.reason)
        assertEquals(0, env.restorer.calls)

        env.supervisor.execute(decision)
        assertEquals(1, env.restorer.calls)
    }

    @Test
    fun `exact Odin system CPU drift repairs under the same active identity`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 120_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )

        val decision = env.supervisor.tick() as SupervisorDecision.RepairCpuTarget
        assertEquals("TARGET_CONTRACT_DRIFT_CPU", decision.reason)
        assertEquals(TransactionCoordinator.TARGET_REPAIRED, env.supervisor.execute(decision)?.code)
        assertEquals(1, env.repairer.calls)
        assertEquals(0, env.restorer.calls)
        assertEquals(7L, env.supervisor.activeSession()?.generation)
    }

    @Test
    fun `sparse recurring Odin vendor resets remain repairable until hard lease`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 125_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )

        for (second in 10..120 step 10) {
            env.now = second * 1_000L
            env.sample = targetSample(contract).copy(
                cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
            )
            val repair = env.supervisor.tick() as SupervisorDecision.RepairCpuTarget
            assertEquals(TransactionCoordinator.TARGET_REPAIRED, env.supervisor.execute(repair)?.code)
            env.sample = targetSample(contract)
            env.now += 1_000L
            assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        }
        assertEquals(12, env.repairer.calls)
        assertEquals(0, env.restorer.calls)
        env.now = 125_000L
        assertEquals(
            "HARD_LEASE_EXPIRED",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
    }

    @Test
    fun `rapid Odin writer fight trips rolling repair budget and degrades to Continue, never Restore`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 120_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )

        for (second in 1L..2L) {
            env.now = second * 1_000L
            val repair = env.supervisor.tick() as SupervisorDecision.RepairCpuTarget
            env.supervisor.execute(repair)
        }
        env.now = 3_000L
        // Policy 2026-08-13/14: exhaustion degrades honestly instead of escalating -- a
        // rapid writer fight (often heat-caused) must never cost the fan by reverting to Stock.
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(2, env.repairer.calls)
        assertEquals(0, env.restorer.calls)
        assertNotNull(env.supervisor.activeSession())
    }

    // Sticky apply (2026-10-01). The Nova's 09-30 session set: 19 of 20 sessions restored 1-9 s after
    // the lease on this exact vector -- the vendor perf server lifting scaling_max_freq back to stock.
    private fun policyFallbackContract() = targetContract().copy(
        cpuQosCeilingKhz = mapOf(0 to 1_017_600, 3 to 1_286_400, 7 to 1_478_400),
        cpuQosTransport = CpuQosTransport.POLICY_SCALING_MAX,
    )

    @Test
    fun `POLICY_SCALING_MAX fallback drift toward stock repairs and never restores`() {
        val env = Env()
        val contract = policyFallbackContract()
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 120_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = mapOf(0 to 2_016_000, 3 to 1_286_400, 7 to 2_956_800),
        )

        val repair = env.supervisor.tick() as SupervisorDecision.RepairCpuTarget
        assertEquals("TARGET_CONTRACT_DRIFT_CPU", repair.reason)
        env.supervisor.execute(repair)
        assertEquals(1, env.repairer.calls)
        assertEquals(0, env.restorer.calls)
    }

    @Test
    fun `POLICY_SCALING_MAX fallback exhausts the repair budget into Continue with the session kept`() {
        val env = Env()
        val contract = policyFallbackContract()
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 120_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = mapOf(0 to 2_016_000, 3 to 2_803_200, 7 to 2_956_800),
        )
        for (second in 1L..2L) {
            env.now = second * 1_000L
            env.supervisor.execute(env.supervisor.tick() as SupervisorDecision.RepairCpuTarget)
        }
        // Burst budget (2 per 5 s) is spent until the first attempt ages out at 6 s: contested, kept,
        // never a Restore -- tick after tick.
        for (second in 3L..5L) {
            env.now = second * 1_000L
            assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        }
        assertEquals(2, env.repairer.calls)
        assertEquals(0, env.restorer.calls)
        assertNotNull(env.supervisor.activeSession())
    }

    @Test
    fun `POLICY_SCALING_MAX fallback drift BELOW the contract or a transport change still restores`() {
        val contract = policyFallbackContract()

        val below = Env()
        below.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        below.sample = targetSample(contract).copy(
            // policy 7 sits under its contract while 0 sits above: not the vendor lifting the cap.
            cpuQosCeilingKhz = mapOf(0 to 2_016_000, 3 to 1_286_400, 7 to 1_000_000),
        )
        assertEquals(
            "TARGET_CONTRACT_DRIFT_CPU",
            (below.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
        assertEquals(0, below.repairer.calls)

        val moved = Env()
        moved.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        moved.sample = targetSample(contract).copy(
            cpuQosTransport = CpuQosTransport.SHARED_QOS,
            cpuQosCeilingKhz = mapOf(0 to 2_016_000, 3 to 1_286_400, 7 to 2_956_800),
        )
        assertEquals(
            "TARGET_CONTRACT_DRIFT_CPU_TRANSPORT",
            (moved.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
    }

    @Test
    fun `toward-stock predicate needs every policy at or above its contract and a full observation`() {
        val contract = mapOf(0 to 100, 3 to 200)
        assertTrue(cpuDriftOnlyTowardStock(mapOf(0 to 100, 3 to 250), contract))
        assertFalse(cpuDriftOnlyTowardStock(mapOf(0 to 100, 3 to 199), contract))
        assertFalse(cpuDriftOnlyTowardStock(mapOf(0 to 500), contract))
        assertFalse(cpuDriftOnlyTowardStock(null, contract))
        assertFalse(cpuDriftOnlyTowardStock(contract, emptyMap()))
    }

    @Test
    fun `thermal or non CPU drift never enters exact repair`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(
            everForeground = true,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            // CRITICAL: keeps this a decisive single-sample assertion of precedence over the
            // repair path, independent of the rolling-mean window.
            cpuTempC = 121,
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        val thermal = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", thermal.reason)
        assertEquals(2, thermal.rungs)

        env.adopt(
            everForeground = true,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
            gpuMaxPowerLevel = 5,
        )
        assertEquals(
            "TARGET_CONTRACT_DRIFT_CPU",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
        assertEquals(0, env.repairer.calls)
    }

    @Test
    fun `partial CPU target telemetry never authorizes repair`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(
            everForeground = true,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = mapOf(0 to Int.MAX_VALUE),
        )

        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(
            "MISSING_TARGET_CONTRACT_TELEMETRY",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
        assertEquals(0, env.repairer.calls)
    }

    @Test
    fun `failed CPU target repair never falls back to restore`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(
            everForeground = true,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        env.repairer.result = env.repairer.result.copy(
            success = false,
            applied = false,
            code = "TARGET_REPAIR_FAILED",
            message = "injected",
        )

        var attempted: TransactionResult? = null
        val result = env.supervisor.execute(
            env.supervisor.tick(),
            onRepairAttempt = { attempted = it },
        )

        // The failed repair's own result is returned as-is -- no restore fallback, matching
        // RepairFanTarget's existing failure handling. The session survives untouched so a
        // later tick's bounded budget can retry.
        assertEquals("TARGET_REPAIR_FAILED", attempted?.code)
        assertEquals("TARGET_REPAIR_FAILED", result?.code)
        assertEquals(1, env.repairer.calls)
        assertEquals(0, env.restorer.calls)
        assertNotNull(env.supervisor.activeSession())

        val throwing = Env()
        throwing.adopt(
            everForeground = true,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        throwing.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        throwing.repairer.throwOnCall = true
        var thrownAttempt: TransactionResult? = null
        val thrownSettlement = throwing.supervisor.execute(
            throwing.supervisor.tick(),
            onRepairAttempt = { thrownAttempt = it },
        )
        assertEquals("TARGET_REPAIR_EXCEPTION", thrownAttempt?.code)
        assertEquals("TARGET_REPAIR_EXCEPTION", thrownSettlement?.code)
        assertEquals(0, throwing.restorer.calls)
        assertNotNull(throwing.supervisor.activeSession())
    }

    @Test
    fun `hard lease still restores unconditionally, burst breaker now withholds repair without restoring`() {
        val contract = targetContract()
        val leaseEnv = Env()
        leaseEnv.adopt(
            everForeground = true,
            hardDeadlineMs = 10_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        leaseEnv.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        leaseEnv.now = 10_000L
        // Hard lease expiry remains unchanged -- it is not a repair-failure
        // path at all, and still restores unconditionally.
        val expired = leaseEnv.supervisor.tick() as SupervisorDecision.Restore
        assertEquals("HARD_LEASE_EXPIRED", expired.reason)
        assertEquals(0, leaseEnv.repairer.calls)

        val burstEnv = Env()
        burstEnv.adopt(
            everForeground = true,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        burstEnv.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        listOf(1_000L, 2_000L).forEach { now ->
            burstEnv.now = now
            burstEnv.supervisor.execute(burstEnv.supervisor.tick())
        }
        burstEnv.now = 3_000L
        // Policy 2026-08-13/14: the burst breaker no longer restores -- it withholds this
        // tick's repair attempt and lets the window roll forward.
        assertEquals(SupervisorDecision.Continue, burstEnv.supervisor.tick())
        assertNotNull(burstEnv.supervisor.activeSession())
    }

    @Test
    fun `rolling window blocks a fifth repair attempt and degrades to Continue, independently of burst policy`() {
        val env = Env(
            policy = SupervisorPolicy(
                sampleIntervalMs = 1_000L,
                activationGraceMs = 10_000L,
                leaseWindowMs = 30_000L,
                missingTelemetryLimit = 3,
                maxTargetRepairAttemptsPerBurst = 100,
            ),
        )
        val contract = targetContract()
        env.adopt(
            everForeground = true,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )

        listOf(0L, 8_000L, 16_000L, 24_000L).forEach { now ->
            env.now = now
            env.supervisor.execute(env.supervisor.tick())
        }
        env.now = 29_000L
        // Policy 2026-08-13/14: the window breaker no longer restores either.
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertNotNull(env.supervisor.activeSession())
    }

    @Test
    fun `authenticated APPLIED projection freezes the exact target contract`() {
        val record = appliedRecord()
        val contract = AppliedTargetContract.fromAuthenticatedAppliedRecord(record)!!

        assertEquals("boot-1", contract.bootId)
        assertEquals(mapOf(0 to 1_996_800, 6 to 2_246_400), contract.cpuQosCeilingKhz)
        assertEquals(CpuQosTransport.SHARED_QOS, contract.cpuQosTransport)
        assertEquals(6, contract.gpuMinPowerLevel)
        assertEquals(6, contract.gpuMaxPowerLevel)
        assertEquals(
            null,
            AppliedTargetContract.fromAuthenticatedAppliedRecord(
                record.copy(lastCompletedMutationBoundary = record.lastCompletedMutationBoundary - 1),
            ),
        )
    }

    @Test
    fun `target contract telemetry gets three misses and a complete sample resets only its streak`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(everForeground = true, targetContract = contract)
        env.sample = env.sample!!.copy(cpuQosCeilingKhz = null)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.sample = targetSample(contract)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.sample = env.sample!!.copy(cpuQosCeilingKhz = null)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(
            "MISSING_TARGET_CONTRACT_TELEMETRY",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
    }

    @Test
    fun `target loss and thermal safety precede target drift classification`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(
            // CRITICAL: keeps this a decisive single-sample assertion; the property under test
            // is precedence over drift classification, not the rolling-mean window.
            cpuTempC = 121,
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        val thermalOverDrift = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", thermalOverDrift.reason)
        assertEquals(2, thermalOverDrift.rungs)

        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        env.workload = WorkloadTruth.OTHER_FOREGROUND
        assertEquals("TARGET_LOSS", (env.supervisor.tick() as SupervisorDecision.Restore).reason)

        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(cpuTempC = 121)
        env.workload = WorkloadTruth.OTHER_FOREGROUND
        val thermalOverTargetLoss = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals("THERMAL_CPU", thermalOverTargetLoss.reason)
        assertEquals(2, thermalOverTargetLoss.rungs)
    }

    @Test
    fun `boot transport and gpu drift remain axis exact`() {
        // Fan is deliberately excluded from this table from this drift model: a fan-only drift no longer
        // restores at all -- see `a fan-only drift re-asserts the
        // fan target and keeps the compute profile` below for its own (very different) proof.
        val env = Env()
        val contract = targetContract().copy(fanMode = 4)
        // **The GPU rows are GONE from this table — policy 2026-08-31**, and they are gone for a
        // measured reason rather than a preference. On the AYN Thor a GPU ceiling lands, governs for
        // about two seconds, and is then released by the vendor governor; `devfreq/max_freq`, the
        // kernel's own bound, is read-only even to root. So the axis cannot be held, and restoring the
        // player's whole profile because an unholdable axis was not held cost them the entire feature:
        // apply succeeded, the row said active, and three seconds later everything reverted.
        //
        // What remains here is every axis that CAN be held, and each is still exact.
        val cases = listOf(
            targetSample(contract).copy(bootId = "another-boot") to "TARGET_CONTRACT_DRIFT_BOOT",
            targetSample(contract).copy(cpuQosTransport = CpuQosTransport.POLICY_SCALING_MAX) to
                "TARGET_CONTRACT_DRIFT_CPU_TRANSPORT",
        )

        cases.forEach { (sample, reason) ->
            env.adopt(everForeground = true, targetContract = contract)
            env.sample = sample
            assertEquals(reason, (env.supervisor.tick() as SupervisorDecision.Restore).reason)
        }
    }

    @Test
    fun `a released GPU axis is admitted and the session survives on the axes that hold`() {
        // The positive form of the removal above. A GPU-only drift must NOT cost the profile.
        val env = Env()
        val contract = targetContract().copy(fanMode = 4)

        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(gpuMaxPowerLevel = 5)

        assertTrue(
            "a released GPU ceiling must not restore the session",
            env.supervisor.tick() is SupervisorDecision.Continue,
        )

        // And it stays dropped: the verdict is reached once, not re-litigated every tick.
        env.sample = targetSample(contract).copy(gpuMinPowerLevel = 5, gpuMaxPowerLevel = 5)
        assertTrue(env.supervisor.tick() is SupervisorDecision.Continue)
    }

    @Test
    fun `a CPU drift alongside a released GPU still restores, and still names the CPU`() {
        // The GPU being unholdable must not become a way for a REAL drift to escape. A CPU drift is a
        // CPU drift whatever the GPU is doing beside it.
        val env = Env()
        val contract = targetContract().copy(fanMode = 4)

        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
            gpuMaxPowerLevel = 5,
        )

        assertEquals(
            "TARGET_CONTRACT_DRIFT_CPU",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
    }

    // TR-02: TARGET_CONTRACT_DRIFT_* used to name only the axis, not the two values that
    // differed -- naming them on-device required a hand-run sysfs poll. Additive-only: reason
    // strings and branch conditions are unchanged from the table above, this only pins the new
    // expected/observed pair carried alongside each of the five reasons.
    @Test
    fun `target contract drift records carry expected and observed values`() {
        val env = Env()
        val contract = targetContract().copy(fanMode = 4)

        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(bootId = "another-boot")
        val boot = env.supervisor.tick() as SupervisorDecision.Restore
        assertEquals("TARGET_CONTRACT_DRIFT_BOOT", boot.reason)
        assertEquals("boot-1", boot.expected)
        assertEquals("another-boot", boot.observed)

        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(cpuQosTransport = CpuQosTransport.POLICY_SCALING_MAX)
        val transport = env.supervisor.tick() as SupervisorDecision.Restore
        assertEquals("TARGET_CONTRACT_DRIFT_CPU_TRANSPORT", transport.reason)
        assertEquals("SHARED_QOS", transport.expected)
        assertEquals("POLICY_SCALING_MAX", transport.observed)

        env.adopt(everForeground = true, targetContract = contract)
        val driftedCpu = contract.cpuQosCeilingKhz.mapValues { (_, khz) -> khz + 1 }
        env.sample = targetSample(contract).copy(cpuQosCeilingKhz = driftedCpu)
        val cpu = env.supervisor.tick() as SupervisorDecision.Restore
        assertEquals("TARGET_CONTRACT_DRIFT_CPU", cpu.reason)
        assertEquals(contract.cpuQosCeilingKhz.toString(), cpu.expected)
        assertEquals(driftedCpu.toString(), cpu.observed)

        // The two GPU legs are gone from this table with the axis itself : a
        // released GPU no longer produces a Restore to carry expected/observed on, and the axis's own
        // proof now lives in `a released GPU axis is admitted and the session survives on the axes that
        // hold`. What this test exists for -- that a restore names the two values that diverged, not
        // just the axis -- is unchanged and still covered by the three legs above.
    }

    // TR-02: reasons that are not a TARGET_CONTRACT_DRIFT_* axis mismatch never had a single
    // expected/observed pair to name, and still do not -- this pins that the additive fields stay
    // null there rather than silently defaulting to something misleading.
    @Test
    fun `restore reasons outside target contract drift carry no expected or observed values`() {
        val env = Env()
        val decision = env.supervisor.onOwnerDeath(
            ownerUid = 0,
            ownerTokenId = NO_OWNER_TOKEN_ID,
            requestId = "",
            generation = 0L,
            transactionId = "",
        )
        assertEquals(SupervisorDecision.Continue, decision)
        env.adopt(everForeground = true)
        val died = env.supervisor.onOwnerDeath(
            ownerUid = 42,
            ownerTokenId = NO_OWNER_TOKEN_ID,
            requestId = "req-7",
            generation = 7L,
            transactionId = "tx-7",
        ) as SupervisorDecision.Restore
        assertEquals("OWNER_TOKEN_DIED", died.reason)
        assertNull(died.expected)
        assertNull(died.observed)
    }

    // --- S4.3 -- fan-only drift stops being fatal ----------------------
    //
    // A charger plug/unplug rewrites vendor fan_mode at an unpredictable time; that is normal
    // operation on this device, not tampering (device evidence: `com.odin.settings`' FanTrigger,
    // a BATTERY_CHANGED receiver, on by default). Differential: reverting this section's
    // soleRepairableFanDrift/admitFanReassert/RepairFanTarget wiring back to the old unconditional
    // `fanDrift -> "TARGET_CONTRACT_DRIFT_FAN"` Restore makes the very first assertion below fail
    // (SupervisorDecision.Restore instead of RepairFanTarget).

    @Test
    fun `a fan-only drift re-asserts the fan target and keeps the compute profile`() {
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SPORT)
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 120_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        // A charger plug/unplug rewrote vendor fan_mode; nothing else drifted.
        env.sample = targetSample(contract).copy(vendorFanMode = TuningPlanner.FAN_SMART)

        val decision = env.supervisor.tick() as SupervisorDecision.RepairFanTarget
        assertEquals("TARGET_CONTRACT_DRIFT_FAN", decision.reason)
        assertEquals("FAN_REASSERTED", env.supervisor.execute(decision)?.code)
        assertEquals(1, env.fanReasserter.calls)
        assertEquals(7L, env.fanReasserter.lastGeneration)
        assertEquals("tx-7", env.fanReasserter.lastTransactionId)
        assertEquals(0, env.restorer.calls)
        assertEquals(0, env.repairer.calls)
        // The compute profile is untouched: session and CPU+GPU contract persist exactly.
        val settled = env.supervisor.activeSession()
        assertEquals(7L, settled?.generation)
        assertEquals(contract.cpuQosCeilingKhz, settled?.appliedTargetContract?.cpuQosCeilingKhz)
        assertEquals(contract.gpuMaxPowerLevel, settled?.appliedTargetContract?.gpuMaxPowerLevel)
    }

    /**
     * TR-06 : `admitFanReassert` reasserts whatever the CONTRACT carries
     * -- it is a rate limiter, not a mode constant -- so it needs no edit for the Odin's
     * SPORT-owned session above to keep working, and it needs none here either: `fanDrift` and
     * `soleRepairableFanDrift` are already generic over any `contract.fanMode` value, including the
     * new default [TuningPlanner.FAN_SMART]. This proves it directly rather than assuming it from
     * the SPORT case above: a SMART-contracted, fan-bearing session (every RP/AYN device but the
     * Odin in this routing configuration) observed off-SMART mid-session is still classified as a repairable
     * fan-only drift and reasserted via [SupervisorDecision.RepairFanTarget] -- never silently
     * ignored, and never escalated to a full [SupervisorDecision.Restore] that would cost the whole
     * compute profile for a fan_mode flip alone.
     */
    @Test
    fun `a Smart contract observed off-Smart mid-session is still a repairable fan-only drift`() {
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SMART)
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 120_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        // Something knocked the vendor's own SMART mode off -- a charger event, a foreign write --
        // and nothing else drifted.
        env.sample = targetSample(contract).copy(vendorFanMode = TuningPlanner.FAN_SILENT)

        val decision = env.supervisor.tick() as SupervisorDecision.RepairFanTarget
        assertEquals("TARGET_CONTRACT_DRIFT_FAN", decision.reason)
        assertEquals("FAN_REASSERTED", env.supervisor.execute(decision)?.code)
        assertEquals(1, env.fanReasserter.calls)
        assertEquals(0, env.restorer.calls)
        assertEquals(0, env.repairer.calls)
        // The compute profile is untouched, same as the SPORT case above.
        val settled = env.supervisor.activeSession()
        assertEquals(contract.cpuQosCeilingKhz, settled?.appliedTargetContract?.cpuQosCeilingKhz)
        assertEquals(contract.gpuMaxPowerLevel, settled?.appliedTargetContract?.gpuMaxPowerLevel)
    }

    @Test
    fun `sparse recurring fan drift such as a charger plug unplug remains repairable and never costs the profile`() {
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SPORT)
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 125_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )

        for (second in 10..120 step 10) {
            env.now = second * 1_000L
            env.sample = targetSample(contract).copy(vendorFanMode = TuningPlanner.FAN_SMART)
            val repair = env.supervisor.tick() as SupervisorDecision.RepairFanTarget
            assertEquals("FAN_REASSERTED", env.supervisor.execute(repair)?.code)
            env.sample = targetSample(contract)
            env.now += 1_000L
            assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        }
        assertEquals(12, env.fanReasserter.calls)
        assertEquals(0, env.restorer.calls)
        // The profile survived every charger-like event across the whole window; only the hard
        // lease -- an unrelated, unchanged integrity boundary -- eventually ends the session.
        env.now = 125_000L
        assertEquals(
            "HARD_LEASE_EXPIRED",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
    }

    @Test
    fun `rapid fan drift exhausts the bounded window and degrades honestly, never restoring`() {
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SPORT)
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 120_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        env.sample = targetSample(contract).copy(vendorFanMode = TuningPlanner.FAN_SMART)

        for (second in 1L..2L) {
            env.now = second * 1_000L
            val repair = env.supervisor.tick() as SupervisorDecision.RepairFanTarget
            env.supervisor.execute(repair)
        }
        env.now = 3_000L
        // Burst-exhausted (2 attempts / 5 s, the same default shape as CPU repair's circuit
        // breaker): degrades to Continue -- not a third reassert attempt, and never Restore.
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(2, env.fanReasserter.calls)
        assertEquals(0, env.restorer.calls)
        assertTrue(env.supervisor.activeSession() != null)
        assertEquals(7L, env.supervisor.activeSession()?.generation)
    }

    @Test
    fun `fan contract amendment preserves identity leases and spent repair budget`() {
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_MANUAL)
        env.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        val before = checkNotNull(env.supervisor.activeSession())
        env.sample = targetSample(contract).copy(vendorFanMode = TuningPlanner.FAN_SMART)
        for (second in 1L..2L) {
            env.now = second * 1_000
            env.supervisor.execute(env.supervisor.tick() as SupervisorDecision.RepairFanTarget)
        }
        val smart = contract.copy(fanMode = TuningPlanner.FAN_SMART)
        assertFalse(env.supervisor.amendFanContract(8, "tx-7", smart))
        assertFalse(env.supervisor.amendFanContract(7, "tx-7", smart.copy(cpuQosCeilingKhz = emptyMap())))
        assertTrue(env.supervisor.amendFanContract(7, "tx-7", smart))
        assertEquals(before.copy(appliedTargetContract = smart), env.supervisor.activeSession())
        assertTrue(env.supervisor.amendFanContract(7, "tx-7", contract))
        env.now = 3_000
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(2, env.fanReasserter.calls)
        assertEquals(0, env.restorer.calls)
    }

    @Test
    fun `durable contract reconciliation repairs non-fan desync without renewing identity or repair budget`() {
        val base = appliedRecord()
        val fan = PersistedAppliedMutation(HardwareSnapshot.FAN_LOGICAL_ID, TuningAxis.FAN,
            HardwareSnapshot.FAN_SETTINGS_PATH, LogicalMutationKind.SETTINGS_PUT, 4, true)
        val plan = base.appliedPlan!!.copy(fanMode = 4)
        val record = base.copy(schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
            deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
            appliedPlan = plan, planHash = PlanHash.ofPersisted(plan),
            snapshot = base.snapshot.copy(nodes = base.snapshot.nodes + TopologyNodeState(
                fan.logicalId, fan.axis, fan.canonicalPath, 4, null, null, null, true)),
            appliedMutationProjection = base.appliedMutationProjection + fan,
            applyOrderLogicalIds = base.applyOrderLogicalIds + fan.logicalId,
            lastCompletedMutationBoundary = base.lastCompletedMutationBoundary + 1,
            guardianBinding = GuardianJournalBinding("a".repeat(64), "b".repeat(32), "helper",
                "c".repeat(64), 120_000, readySessionSha256 = "d".repeat(64)))
        val authoritative = checkNotNull(AppliedTargetContract.fromAuthenticatedAppliedRecord(record))
        val stale = authoritative.copy(fanMode = 6, gpuMaxPowerLevel = 3)
        val env = Env()
        env.adopt(everForeground = true, targetContract = stale, scope = ActiveSessionScope.SYSTEM)
        val before = checkNotNull(env.supervisor.activeSession())
        env.sample = targetSample(stale).copy(vendorFanMode = 4)
        for (second in 1L..2L) {
            env.now = second * 1_000
            env.supervisor.execute(env.supervisor.tick() as SupervisorDecision.RepairFanTarget)
        }
        assertFalse(env.supervisor.amendFanContract(7, "tx-7", authoritative))
        assertFalse(env.supervisor.reconcileAppliedContract(record.copy(generation = 8)))
        assertFalse(env.supervisor.reconcileAppliedContract(record.copy(phase = TransactionPhase.APPLYING)))
        assertTrue(env.supervisor.reconcileAppliedContract(record))
        assertEquals(before.copy(appliedTargetContract = authoritative), env.supervisor.activeSession())
        val manual = authoritative.copy(fanMode = 6)
        assertTrue(env.supervisor.amendFanContract(7, "tx-7", manual))
        env.sample = targetSample(manual).copy(vendorFanMode = 4)
        env.now = 3_000
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(2, env.fanReasserter.calls)
        assertEquals(0, env.restorer.calls)
    }

    @Test
    fun `real supervisor budgets permit eight-second failures but deadman stops refreshing after two dispatches`() {
        val env = Env()
        val contract = targetContract().copy(fanMode = 6)
        env.adopt(everForeground = true, hardDeadlineMs = 125_000, targetContract = contract,
            scope = ActiveSessionScope.SYSTEM)
        env.sample = targetSample(contract).copy(vendorFanMode = 1)
        env.fanReasserter.result = TransactionResult(false, false, false, "FAN_REASSERT_FAILED", "injected",
            transactionId = "tx-7", generation = 7)
        val envelope = RestoreGuardianEnvelope(
            helperBuildIdentity = "helper", tweakerCertificateSha256 = "a".repeat(64),
            hardwareFingerprint = "hardware", deviceProfile = "odin3", bootIdentity = "boot-1",
            generation = 7, requestId = "req-7", transactionId = "tx-7", taskNonce = "b".repeat(32),
            topologySha256 = "c".repeat(64), snapshotSha256 = "d".repeat(64), planSha256 = "e".repeat(64),
            hardDeadlineElapsedRealtimeMs = 125_000, restoreEntries = listOf(RestoreGuardianFanPutEntry(0,
                expectedStock = RestoreGuardianStockState.PresentFanMode(4), rendererAppliedIntermediates = listOf(6))))
        val liveness = ManualFanMonitorLiveness()
        for (now in 0L..120_000L step 8_000L) {
            env.now = now
            val decision = env.supervisor.tick() as SupervisorDecision.RepairFanTarget
            liveness.markRepairing(checkNotNull(env.supervisor.activeSession()), now)
            assertEquals("FAN_REASSERT_FAILED", env.supervisor.execute(decision)?.code)
            assertEquals(if (now <= 18_000) null else "REPAIR_STALE age=${now - 8_000}",
                liveness.denialReason(envelope, now, ManualFanMonitorLiveness.JournalTruth(7, "tx-7", TransactionPhase.APPLIED), 1))
        }
        assertEquals(16, env.fanReasserter.calls)
        assertEquals(0, env.restorer.calls)
    }

    @Test
    fun `fan drift concurrent with a REAL drift still restores with that axis reason`() {
        // A fan drift beside a CPU drift that is NOT repairable (here: a non-SYSTEM session, which the
        // repair envelope has never covered) must NOT consume that drift's restore, and must not
        // re-assert the fan while the session is being torn down.
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SPORT)
        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(
            vendorFanMode = TuningPlanner.FAN_SMART,
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )

        assertEquals(
            "TARGET_CONTRACT_DRIFT_CPU",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
        assertEquals(0, env.fanReasserter.calls)
    }

    @Test
    fun `sticky apply -- a fan drift beside a repairable CPU drift no longer vetoes the CPU repair`() {
        // Sticky apply 2026-10-01: fanDrift used to be the one non-CPU conjunct in the repair
        // expression, and a charger plug alongside a vendor reset cost the whole compute profile.
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SPORT)
        env.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        env.sample = targetSample(contract).copy(
            vendorFanMode = TuningPlanner.FAN_SMART,
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )

        val repair = env.supervisor.tick() as SupervisorDecision.RepairCpuTarget
        assertEquals("TARGET_CONTRACT_DRIFT_CPU", repair.reason)
        assertEquals(0, env.restorer.calls)
        assertEquals(0, env.fanReasserter.calls)
    }

    @Test
    fun `a fan drift beside a released GPU takes the fan's own repair route`() {
        // The consequence of dropping the GPU axis, stated as a property rather than left implicit:
        // with the GPU no longer able to claim the decision, a concurrent fan drift is once again the
        // SOLE repairable drift and reaches admitFanReassert. The first version of this fix returned
        // early on a GPU branch and swallowed exactly this case; the supervisor's own tests caught it.
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SPORT)
        env.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        env.sample = targetSample(contract).copy(
            vendorFanMode = TuningPlanner.FAN_SMART,
            gpuMaxPowerLevel = 5,
        )

        val decision = env.supervisor.tick()

        assertTrue(
            "the fan must still be repairable beside a released GPU, not restored over",
            decision is SupervisorDecision.RepairFanTarget,
        )
    }

    // ── Vendor performance mode: drift is a YIELD (design 2026-10-13, D3) ───────────────────────
    //
    // The Tweaker drives the vendor's `performance_mode`, but the mode is the player's: a different
    // live value means they chose it. The supervisor says so once, stops watching the axis, and keeps
    // the session -- no re-assert, no Restore, and no effect on any other axis's decision.

    @Test
    fun `a player's change of the vendor mode yields once and keeps the session without restore or reassert`() {
        val env = Env()
        val contract = targetContract().copy(vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE)
        env.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        assertFalse(env.supervisor.vendorModeYieldedNoLock())

        // While the live mode matches what was applied the axis is watched and quiet.
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertFalse(env.supervisor.vendorModeYieldedNoLock())

        // The player switches the mode in the vendor UI.
        env.sample = targetSample(contract).copy(vendorPerformanceMode = TuningPlanner.VENDOR_MODE_STANDARD)
        env.now = 1_000L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertTrue(env.supervisor.vendorModeYieldedNoLock())

        // And it stays yielded: another value, then back to the applied one, are not re-litigated.
        env.sample = targetSample(contract).copy(vendorPerformanceMode = TuningPlanner.VENDOR_MODE_PERFORMANCE)
        env.now = 2_000L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.sample = targetSample(contract)
        env.now = 3_000L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertTrue(env.supervisor.vendorModeYieldedNoLock())

        // The session and its contract are untouched, and nothing was written.
        val settled = env.supervisor.activeSession()
        assertEquals(7L, settled?.generation)
        assertEquals(contract, settled?.appliedTargetContract)
        assertEquals(0, env.restorer.calls)
        assertEquals(0, env.fanReasserter.calls)
        assertEquals(0, env.repairer.calls)
        assertEquals(0, env.derater.calls)
    }

    @Test
    fun `a vendor mode matching the applied one never yields however long it is watched`() {
        val env = Env()
        val contract = targetContract().copy(vendorMode = TuningPlanner.VENDOR_MODE_PERFORMANCE)
        env.adopt(everForeground = true, targetContract = contract)
        repeat(5) { tick ->
            env.now = tick * 1_000L
            assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        }
        assertFalse(env.supervisor.vendorModeYieldedNoLock())
    }

    @Test
    fun `a yield is logged exactly once and cannot reach any decision`() {
        // The unit class is plain JUnit, so there is no log shadow to count lines in; the claim is
        // pinned against the source instead (same technique as the fan decision tests below).
        val whole = sliceMainSource(
            "HardwareSessionSupervisor.kt",
            "internal class HardwareSessionSupervisor(",
            "/** A single valid channel reading, timestamped for",
        )
        assertEquals(1, Regex("VENDOR_MODE_YIELDED").findAll(whole).count())
        val verdict = sliceMainSource(
            "HardwareSessionSupervisor.kt",
            "val appliedVendorMode = contract.vendorMode",
            "// The OBSERVED ceiling is folded onto the writable table",
        )
        assertTrue(
            "the verdict is reached once, behind the not-yet-yielded guard",
            verdict.contains("!vendorModeYielded") && verdict.contains("vendorModeYielded = true"),
        )
        assertFalse("the yield branch must not construct a decision", verdict.contains("SupervisorDecision"))
        assertFalse("the yield branch must not return", verdict.contains("return"))
        // The observed value feeds no drift predicate: only the unavailable leg and the verdict read it.
        val readers = Regex("vendorPerformanceMode").findAll(
            sliceMainSource(
                "HardwareSessionSupervisor.kt",
                "private fun evaluateTelemetry(",
                "/** TR-02: pure carrier for one TARGET_CONTRACT_DRIFT_* reason",
            ),
        ).count()
        assertEquals("unavailable leg + verdict read, nothing else", 2, readers)
    }

    @Test
    fun `a fan drift concurrent with a vendor mode yield still takes the fan's own repair route`() {
        val env = Env()
        val contract = targetContract().copy(
            fanMode = TuningPlanner.FAN_SPORT,
            vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE,
        )
        env.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        env.sample = targetSample(contract).copy(
            vendorFanMode = TuningPlanner.FAN_SMART,
            vendorPerformanceMode = TuningPlanner.VENDOR_MODE_STANDARD,
        )

        val decision = env.supervisor.tick()

        assertTrue(
            "the yield must not swallow, veto or restore over a fan drift: $decision",
            decision is SupervisorDecision.RepairFanTarget,
        )
        assertEquals("TARGET_CONTRACT_DRIFT_FAN", (decision as SupervisorDecision.RepairFanTarget).reason)
        assertTrue(env.supervisor.vendorModeYieldedNoLock())
        assertEquals("FAN_REASSERTED", env.supervisor.execute(decision)?.code)
        assertEquals(1, env.fanReasserter.calls)
        assertEquals(0, env.restorer.calls)
    }

    @Test
    fun `a CPU drift concurrent with a vendor mode yield still restores and still names the CPU`() {
        val env = Env()
        val contract = targetContract().copy(vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE)
        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
            vendorPerformanceMode = TuningPlanner.VENDOR_MODE_STANDARD,
        )

        assertEquals(
            "TARGET_CONTRACT_DRIFT_CPU",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
    }

    @Test
    fun `an unread vendor mode is an unavailable target channel and never a yield`() {
        val env = Env()
        val contract = targetContract().copy(vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE)
        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(vendorPerformanceMode = null)

        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertFalse("a null read is not drift", env.supervisor.vendorModeYieldedNoLock())
        // A complete sample resets only this streak, exactly like the other target channels...
        env.sample = targetSample(contract)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.sample = targetSample(contract).copy(vendorPerformanceMode = null)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        // ...and the third consecutive miss is the unavailable-telemetry restore, not a drift one.
        assertEquals(
            "MISSING_TARGET_CONTRACT_TELEMETRY",
            (env.supervisor.tick() as SupervisorDecision.Restore).reason,
        )
        assertFalse(env.supervisor.vendorModeYieldedNoLock())
    }

    @Test
    fun `once yielded a null vendor mode read no longer counts toward any restore`() {
        val env = Env()
        val contract = targetContract().copy(vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE)
        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(vendorPerformanceMode = TuningPlanner.VENDOR_MODE_STANDARD)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertTrue(env.supervisor.vendorModeYieldedNoLock())

        env.sample = targetSample(contract).copy(vendorPerformanceMode = null)
        repeat(6) { assertEquals(SupervisorDecision.Continue, env.supervisor.tick()) }
        assertEquals(0, env.restorer.calls)
    }

    @Test
    fun `a contract without a vendor mode never yields whatever the sample reads`() {
        val env = Env()
        val contract = targetContract()
        assertEquals(null, contract.vendorMode)
        env.adopt(everForeground = true, targetContract = contract)

        listOf(
            null,
            TuningPlanner.VENDOR_MODE_STANDARD,
            TuningPlanner.VENDOR_MODE_PERFORMANCE,
            TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE,
        ).forEachIndexed { index, live ->
            env.now = index * 1_000L
            env.sample = targetSample(contract).copy(vendorPerformanceMode = live)
            assertEquals("live=$live", SupervisorDecision.Continue, env.supervisor.tick())
        }
        assertFalse(env.supervisor.vendorModeYieldedNoLock())
    }

    @Test
    fun `the yield belongs to its session and a new or cleared session watches the mode afresh`() {
        val env = Env()
        val contract = targetContract().copy(vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE)
        env.adopt(everForeground = true, targetContract = contract)
        env.sample = targetSample(contract).copy(vendorPerformanceMode = TuningPlanner.VENDOR_MODE_STANDARD)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertTrue(env.supervisor.vendorModeYieldedNoLock())

        env.adopt(everForeground = true, targetContract = contract)
        assertFalse("a re-adopted session starts unyielded", env.supervisor.vendorModeYieldedNoLock())

        env.sample = targetSample(contract).copy(vendorPerformanceMode = TuningPlanner.VENDOR_MODE_STANDARD)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertTrue(env.supervisor.vendorModeYieldedNoLock())
        env.supervisor.clearSession()
        assertFalse("a cleared session leaves nothing behind", env.supervisor.vendorModeYieldedNoLock())
    }

    @Test
    fun `the authenticated applied record carries the plan's vendor mode and an older record carries none`() {
        // An older journal predates the field: it reconstructs a contract with no mode, and a session
        // adopted from it is supervised byte-for-byte as before, whatever a stray live read says.
        val older = appliedRecord()
        assertEquals(null, older.appliedPlan?.vendorMode)
        val olderContract = AppliedTargetContract.fromAuthenticatedAppliedRecord(older)!!
        assertEquals(null, olderContract.vendorMode)
        val env = Env()
        env.adopt(everForeground = true, targetContract = olderContract)
        env.sample = targetSample(olderContract).copy(vendorPerformanceMode = TuningPlanner.VENDOR_MODE_STANDARD)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertFalse(env.supervisor.vendorModeYieldedNoLock())

        // A plan that wrote a mode: the contract reads it from the PLAN, covered by the plan hash.
        val plan = checkNotNull(older.appliedPlan).copy(vendorMode = TuningPlanner.VENDOR_MODE_PERFORMANCE)
        // The journal validator holds a plan that drives the mode to a projection entry and a snapshot
        // node for it (apply order: fan, vendor mode, compute), so the fixture carries both.
        val vendorEntry = PersistedAppliedMutation(
            logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
            axis = TuningAxis.VENDOR_MODE,
            canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
            kind = LogicalMutationKind.SETTINGS_PUT,
            value = TuningPlanner.VENDOR_MODE_PERFORMANCE,
            expectPresent = true,
        )
        val projection = listOf(vendorEntry) + older.appliedMutationProjection
        val record = older.copy(
            appliedPlan = plan,
            planHash = PlanHash.ofPersisted(plan),
            snapshot = older.snapshot.copy(
                nodes = older.snapshot.nodes + TopologyNodeState(
                    logicalId = vendorEntry.logicalId,
                    axis = vendorEntry.axis,
                    canonicalPath = vendorEntry.canonicalPath,
                    originalValue = TuningPlanner.VENDOR_MODE_STANDARD,
                    originalMode = null,
                    originalUid = null,
                    originalGid = null,
                    present = true,
                ),
            ),
            applyOrderLogicalIds = projection.map { it.logicalId },
            appliedMutationProjection = projection,
            lastCompletedMutationBoundary = projection.size,
        )
        assertEquals(
            TuningPlanner.VENDOR_MODE_PERFORMANCE,
            AppliedTargetContract.fromAuthenticatedAppliedRecord(record)?.vendorMode,
        )
        // The mode is part of the plan identity: a record whose hash was struck without it is refused.
        assertEquals(
            null,
            AppliedTargetContract.fromAuthenticatedAppliedRecord(older.copy(appliedPlan = plan)),
        )
    }

    @Test
    fun `a derate refresh keeps the watched vendor mode and tracks a mode the derated plan writes`() {
        val derated = TuningPlan(
            profileId = "performance",
            packageName = "example.game",
            cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 1_958_400),
            gpuMaxPowerLevel = 7,
            fanMode = null,
            calibrated = true,
        )
        fun derate(env: Env, plan: TuningPlan) {
            env.derater.result = env.derater.result.copy(plan = plan)
            env.supervisor.execute(SupervisorDecision.Derate(7L, "tx-7", "THERMAL_CPU", 1, 1))
        }

        // A derated plan that writes no mode leaves the earlier mode (and its watch) as it was.
        val keep = Env()
        val contract = targetContract().copy(vendorMode = TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE)
        keep.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        derate(keep, derated)
        val kept = checkNotNull(keep.supervisor.activeSession()?.appliedTargetContract)
        assertEquals(TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE, kept.vendorMode)
        keep.now = 1_000L
        keep.sample = targetSample(kept)
        assertEquals(SupervisorDecision.Continue, keep.supervisor.tick())
        assertFalse(keep.supervisor.vendorModeYieldedNoLock())

        // One that writes a different mode moves the watch with it, so the write is not the
        // player's choice on the next tick.
        val move = Env()
        move.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        derate(move, derated.copy(vendorMode = TuningPlanner.VENDOR_MODE_PERFORMANCE))
        val moved = checkNotNull(move.supervisor.activeSession()?.appliedTargetContract)
        assertEquals(TuningPlanner.VENDOR_MODE_PERFORMANCE, moved.vendorMode)
        move.now = 1_000L
        move.sample = targetSample(moved)
        assertEquals(SupervisorDecision.Continue, move.supervisor.tick())
        assertFalse(move.supervisor.vendorModeYieldedNoLock())
    }

    @Test
    fun `ownership and integrity failures still restore to Stock even when the contract owns the fan`() {
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SPORT)

        // Dead owner.
        val ownerEnv = Env()
        ownerEnv.adopt(everForeground = true, targetContract = contract)
        ownerEnv.living = false
        val ownerDied = ownerEnv.supervisor.tick()
        assertTrue(ownerDied is SupervisorDecision.Restore)
        assertEquals("OWNER_TOKEN_DIED", (ownerDied as SupervisorDecision.Restore).reason)
        assertEquals(0, ownerEnv.fanReasserter.calls)

        // Hard-lease expiry.
        val leaseEnv = Env()
        leaseEnv.adopt(
            everForeground = true,
            hardDeadlineMs = 5_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        leaseEnv.now = 5_000L
        val leaseExpired = leaseEnv.supervisor.tick()
        assertTrue(leaseExpired is SupervisorDecision.Restore)
        assertEquals("HARD_LEASE_EXPIRED", (leaseExpired as SupervisorDecision.Restore).reason)

        // Boot-id mismatch (journal/target-contract integrity) -- concurrent with a fan drift
        // too, proving the integrity failure still wins outright.
        val bootEnv = Env()
        bootEnv.adopt(everForeground = true, targetContract = contract)
        bootEnv.sample = targetSample(contract).copy(
            bootId = "another-boot",
            vendorFanMode = TuningPlanner.FAN_SMART,
        )
        val bootMismatch = bootEnv.supervisor.tick()
        assertTrue(bootMismatch is SupervisorDecision.Restore)
        assertEquals("TARGET_CONTRACT_DRIFT_BOOT", (bootMismatch as SupervisorDecision.Restore).reason)
        assertEquals(0, bootEnv.fanReasserter.calls)

        // Unrepairable CPU drift (outside the narrow SHARED_QOS/SYSTEM repair envelope) still
        // restores, unaffected by the contract also owning the fan.
        val cpuEnv = Env()
        cpuEnv.adopt(everForeground = true, targetContract = contract)
        cpuEnv.sample = targetSample(contract).copy(
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz.mapValues { Int.MAX_VALUE },
        )
        val cpuDrift = cpuEnv.supervisor.tick()
        assertTrue(cpuDrift is SupervisorDecision.Restore)
        assertEquals("TARGET_CONTRACT_DRIFT_CPU", (cpuDrift as SupervisorDecision.Restore).reason)
    }

    // --- Composed-path correction: this supervisor's own bounded degrade-honestly decision is
    // not the whole story. MimirTweakerService.startMonitor composes TWO decisions on one tick:
    //
    //     var observedDecision = supervisor.tick()
    //     if (observedDecision is Continue) observedDecision = fanControlDecision(activeSession)
    //
    // Before this correction, fanControlDecision's own FailSafe/telemetry/duty-write-failure
    // branches independently constructed SupervisorDecision.Restore for the SAME live vendor
    // fan_mode disagreement soleRepairableFanDrift/admitFanReassert above already detects and
    // bounds-retries. Once the reassert burst budget (2 attempts / 5 s) exhausts WITHOUT the
    // drift actually clearing -- exactly what the test directly above this block proves --
    // supervisor.tick() itself degrades to Continue, and fanControlDecision runs THAT SAME tick,
    // observes the identical still-stale sample, and (pre-fix) silently overrode the degrade with
    // a full Restore. No existing test caught this: every test above uses this file's own Env,
    // which never composes the fan-control tick at all -- only HardwareSessionSupervisor.tick().
    //
    // fanControlDecision is a private method on a live Android Service with no test harness
    // anywhere in this codebase (confirmed: no MimirTweakerService test file exists). Proof here
    // is therefore split in two, matching the precedent AdvancedFanControlTest already uses for
    // this exact constraint (mainSourceText/sliceBetween against the checked-in PRODUCTION source,
    // not a description of it):
    //   1. `fanControlDecision source no longer restores...` -- the fanControlDecision half:
    //      proves, against the real source text, that the three reasons this fix addresses now
    //      route through fanControlDegraded (Continue), never active.fanRestore (Restore). This
    //      is the differential-bearing test: reverting the MimirTweakerService.kt fix restores
    //      the `fanRestore(...)` calls this test asserts are absent, and it fails.
    //   2. `composed charger-event scenario...` -- the supervisor half: drives the REAL
    //      HardwareSessionSupervisor through a sustained charger-event drift, well past burst
    //      exhaustion, at production tick cadence, proving ITS OWN half of the same composed tick
    //      keeps degrading rather than restoring for as long as fanControlDecision (proven by 1)
    //      no longer overrides it.
    // Together they cover the composed claim without requiring a live Service.

    @Test
    fun `fanControlDecision source no longer restores for a fan-axis-scoped cause`() {
        val fn = sliceMainSource(
            "MimirTweakerService.kt",
            "private fun fanControlDecision(active: ActiveSessionIdentity?): SupervisorDecision {",
            "private fun ActiveSessionIdentity.fanRestore(reason: String)",
        )
        assertTrue(
            "FAN_TELEMETRY_UNAVAILABLE must degrade, not restore",
            fn.contains("""?: return fanControlDegraded("FAN_TELEMETRY_UNAVAILABLE")"""),
        )
        assertFalse(
            "FAN_TELEMETRY_UNAVAILABLE must not construct a Restore",
            fn.contains("""fanRestore("FAN_TELEMETRY_UNAVAILABLE")"""),
        )
        assertTrue(
            "FAN_DUTY_WRITE_FAILED must degrade, not restore",
            fn.contains("""fanControlDegraded("FAN_DUTY_WRITE_FAILED")"""),
        )
        assertFalse(
            "FAN_DUTY_WRITE_FAILED must not construct a Restore",
            fn.contains("""fanRestore("FAN_DUTY_WRITE_FAILED")"""),
        )
        assertTrue(
            "every FanControlDecision.FailSafe reason (including FAN_OWNERSHIP_LOST) must " +
                "degrade, not restore",
            fn.contains("is FanControlDecision.FailSafe -> fanControlDegraded(decision.reason)"),
        )
        assertFalse(
            "FanControlDecision.FailSafe must not construct a Restore",
            fn.contains("is FanControlDecision.FailSafe -> active.fanRestore"),
        )
        // A deliberate, considered exception, pinned so a future change to this boundary is a
        // reviewed decision, not silent drift: FAN_PROFILE_UNAVAILABLE and FAN_CAPABILITY_DRIFT
        // are static session/contract mismatches a fan_mode reassert cannot fix -- not a
        // transient telemetry/ownership condition -- and still Restore, unchanged.
        assertTrue(fn.contains("""fanRestore("FAN_PROFILE_UNAVAILABLE")"""))
        assertTrue(fn.contains("""fanRestore("FAN_CAPABILITY_DRIFT")"""))
    }

    @Test
    fun `fanControlDegraded never constructs a Restore for any reason`() {
        val fn = sliceMainSource(
            "MimirTweakerService.kt",
            "private fun fanControlDegraded(reason: String): SupervisorDecision.Continue {",
            "private fun stopFanControl() {",
        )
        assertTrue(fn.contains("return SupervisorDecision.Continue"))
        assertFalse(fn.contains("Restore"))
    }

    /**
     * Manual tick diagnostics: the MANUAL branch's
     * non-healthy outcomes were silent before this -- see [ManualFanTickObservabilityTest] for the
     * pure dedup/classification contract this wires up. `fanControlDecision` has no live-Service
     * test harness (same constraint the two tests above already document), so the wiring itself is
     * proven against the checked-in source text.
     */
    @Test
    fun `fanControlDecision wires ManualFanTickObservability at every MANUAL-branch outcome`() {
        val decisionFn = sliceMainSource(
            "MimirTweakerService.kt",
            "private fun fanControlDecision(active: ActiveSessionIdentity?): SupervisorDecision {",
            "private fun ActiveSessionIdentity.fanRestore(reason: String)",
        )
        assertTrue(
            "the Hold branch must report a healthy tick",
            decisionFn.contains("ManualFanTickObservability.onHealthy(\"Hold\")"),
        )
        assertTrue(
            "a CONFIRMED WriteDuty must report a healthy tick",
            decisionFn.contains("ManualFanTickObservability.onHealthy(\"WriteDuty\")"),
        )
        assertTrue(
            "a failed duty write must report the real CheckedCommandStatus, not just a reason " +
                "string",
            decisionFn.contains("ManualFanTickObservability.onWriteFailed("),
        )

        val degradedFn = sliceMainSource(
            "MimirTweakerService.kt",
            "private fun fanControlDegraded(reason: String): SupervisorDecision.Continue {",
            "private fun stopFanControl() {",
        )
        assertTrue(
            "fanControlDegraded must route every reason it receives through the classifier -- " +
                "FAN_TELEMETRY_UNAVAILABLE and every FanControlDecision.FailSafe reason reach " +
                "logcat only through this call",
            degradedFn.contains("ManualFanTickObservability.onDegradedReason(reason)"),
        )
    }

    @Test
    fun `composed charger-event scenario -- burst-exhausted reassert keeps degrading, compute survives`() {
        val env = Env()
        val contract = targetContract().copy(fanMode = TuningPlanner.FAN_SPORT)
        env.adopt(
            everForeground = true,
            hardDeadlineMs = 120_000L,
            targetContract = contract,
            scope = ActiveSessionScope.SYSTEM,
        )
        // Live vendor fan_mode stuck at SMART for the whole window: the exact charger-event
        // trace the coordinator's own verification produced (reassert attempts consumed, but the
        // drift never actually clears -- either the reasserter is unavailable or the write keeps
        // losing a race with a flapping charger). This is deliberately the WORST case, not the
        // single-event case the earlier "re-asserts the fan target and keeps the compute profile"
        // test already covers.
        env.sample = targetSample(contract).copy(vendorFanMode = TuningPlanner.FAN_SMART)

        for (second in 1..30) {
            env.now = second * 1_000L
            val decision = env.supervisor.tick()
            assertTrue(
                "tick $second must never Restore for a sole fan-axis drift: was $decision",
                decision !is SupervisorDecision.Restore,
            )
            env.supervisor.execute(decision)
        }

        assertEquals(0, env.restorer.calls)
        val settled = env.supervisor.activeSession()
        assertTrue("the session must still be active after 30 s of sustained drift", settled != null)
        assertEquals(contract.cpuQosCeilingKhz, settled?.appliedTargetContract?.cpuQosCeilingKhz)
        assertEquals(contract.gpuMaxPowerLevel, settled?.appliedTargetContract?.gpuMaxPowerLevel)
        // Not simply inert: genuine RepairFanTarget attempts occurred before each burst window
        // exhausted.
        assertTrue(env.fanReasserter.calls > 0)
    }

    private fun sliceMainSource(fileName: String, startMarker: String, endMarker: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, fileName)
        if (!file.isFile) throw AssertionError("$fileName not found under $root")
        val text = file.readText()
        val start = text.indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = text.indexOf(endMarker, start)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }

    @Test
    fun `a throwing fan reasserter is converted into a failure result and never restores`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.fanReasserter.throwOnCall = true

        val result = env.supervisor.execute(
            SupervisorDecision.RepairFanTarget(7L, "tx-7", "TARGET_CONTRACT_DRIFT_FAN"),
        )

        assertEquals("FAN_REASSERT_EXCEPTION", result?.code)
        assertEquals(0, env.restorer.calls)
        assertTrue(env.supervisor.activeSession() != null)
    }

    @Test
    fun `fan reassert STALE_GENERATION clears the matching session without restoring`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.fanReasserter.result = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "STALE_GENERATION",
            message = "stale",
            transactionId = "tx-7",
            generation = 7L,
        )

        env.supervisor.execute(SupervisorDecision.RepairFanTarget(7L, "tx-7", "TARGET_CONTRACT_DRIFT_FAN"))

        assertNull(env.supervisor.activeSession())
        assertEquals(0, env.restorer.calls)
    }

    @Test
    fun `old generation restore is stale with zero commands`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.restorer.result = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "STALE_GENERATION",
            message = "mismatch",
            transactionId = "tx-new",
        )
        val decision = SupervisorDecision.Restore(generation = 1L, transactionId = "tx-old", reason = "OLD")
        val result = env.supervisor.execute(decision)
        assertEquals("STALE_GENERATION", result!!.code)
        assertEquals(1, env.restorer.calls)
        assertEquals(0, env.restorer.commands)
    }

    @Test
    fun `failed restore retains session and schedules bounded retry without tight loop`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.restorer.result = TransactionResult(
            false,
            false,
            false,
            "RESTORE_FAILED",
            "kept",
            transactionId = "tx-7",
        )
        val first = env.supervisor.execute(
            SupervisorDecision.Restore(7L, "tx-7", "THERMAL_CPU"),
        )
        assertEquals("RESTORE_FAILED", first!!.code)
        assertTrue(env.supervisor.activeSession() != null)
        assertEquals(1_000L, env.supervisor.pendingRetryDelayMs())
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now += 1_000L
        val retry = env.supervisor.tick() as SupervisorDecision.Restore
        assertEquals("RESTORE_RETRY", retry.reason)
        env.restorer.result = TransactionResult(false, false, false, "JOURNAL_CORRUPT", "bad")
        env.supervisor.execute(retry)
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now += 60_000L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
    }

    @Test
    fun `Guardian cross store recovery holds without arming a retry loop`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.restorer.result = TransactionResult(
            false,
            false,
            false,
            "CROSS_STORE_RECONCILIATION_REQUIRED",
            "Guardian owns terminal settlement",
            transactionId = "tx-7",
        )

        val result = env.supervisor.execute(
            SupervisorDecision.Restore(7L, "tx-7", "OWNER_TOKEN_DIED"),
        )

        assertEquals("CROSS_STORE_RECONCILIATION_REQUIRED", result!!.code)
        assertTrue(env.supervisor.activeSession() != null)
        assertTrue(env.supervisor.isCrossStoreHold())
        assertEquals(null, env.supervisor.pendingRetryDelayMs())
        env.now += 60_000L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        assertEquals(1, env.restorer.calls)
    }

    @Test
    fun `exact owner death requests generation bound restore`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.living = false
        assertEquals("OWNER_TOKEN_DIED", (env.supervisor.tick() as SupervisorDecision.Restore).reason)
        env.living = true
        env.adopt(everForeground = true)
        assertEquals(
            "OWNER_TOKEN_DIED",
            (
                env.supervisor.onOwnerDeath(
                    ownerUid = 42,
                    ownerTokenId = NO_OWNER_TOKEN_ID,
                    requestId = "req-7",
                    generation = 7L,
                    transactionId = "tx-7",
                ) as SupervisorDecision.Restore
                ).reason,
        )
        assertEquals(
            SupervisorDecision.Continue,
            env.supervisor.onOwnerDeath(42, 99L, "req-7", 7L, "tx-7"),
        )
    }

    @Test
    fun `recovery policy selects boot package and service recreate without main-thread restore`() {
        val loaded = JournalLoadResult.Loaded(
            TransactionJournalRecord(
                schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V1,
                generation = 1,
                requestId = "r",
                transactionId = "t",
                hardwareFingerprint = "fp",
                deviceProfile = "rp5",
                planHash = "ph",
                profileId = "balanced",
                targetPackage = "example.game",
                phase = TransactionPhase.APPLIED,
                creationTime = 1,
                requestDeadline = 2,
                leaseDeadline = 3,
                bootIdentity = "boot",
                snapshot = HardwareSnapshot(
                    schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                    hardwareFingerprint = "fp",
                    bootIdentity = "boot",
                    nodes = listOf(
                        TopologyNodeState(
                            logicalId = "cpu:0:scaling_max_freq",
                            axis = TuningAxis.CPU,
                            canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                            originalValue = 1_804_800,
                            originalMode = "644",
                            originalUid = 0,
                            originalGid = 0,
                            present = true,
                        ),
                    ),
                ),
                expectedTopologySha256 = "e",
                snapshotSha256 = "s",
                lastCompletedMutationBoundary = 1,
            ),
        )
        for (trigger in RecoveryTrigger.entries) {
            assertEquals(
                RecoverySelection.START_FOREGROUND_RECOVERY,
                TweakerRecoveryPolicy.selectRecovery(trigger, hardwareWritesCompiled = true, load = loaded),
            )
            assertEquals(
                RecoverySelection.START_FOREGROUND_RECOVERY,
                TweakerRecoveryPolicy.selectRecovery(trigger, hardwareWritesCompiled = false, load = loaded),
            )
        }
        assertEquals(
            RecoverySelection.HOLD_CORRUPT_JOURNAL,
            TweakerRecoveryPolicy.selectRecovery(
                RecoveryTrigger.LOCKED_BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Corrupt("truncated"),
            ),
        )
        assertEquals(
            RecoverySelection.HOLD_CORRUPT_JOURNAL,
            TweakerRecoveryPolicy.selectRecovery(
                RecoveryTrigger.LOCKED_BOOT_COMPLETED,
                hardwareWritesCompiled = false,
                load = JournalLoadResult.Corrupt("truncated"),
            ),
        )
        assertEquals(
            RecoverySelection.NONE,
            TweakerRecoveryPolicy.selectRecovery(
                RecoveryTrigger.BOOT_COMPLETED,
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
            ),
        )
        assertEquals(RecoveryTrigger.LOCKED_BOOT_COMPLETED, TweakerRecoveryPolicy.triggerForAction(android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(RecoveryTrigger.USER_UNLOCKED, TweakerRecoveryPolicy.triggerForAction(android.content.Intent.ACTION_USER_UNLOCKED))
        assertEquals(RecoveryTrigger.MY_PACKAGE_REPLACED, TweakerRecoveryPolicy.triggerForAction(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `SAFE write gate keeps supervisor restore from claiming hardware success`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.restorer.result = TransactionResult(
            false,
            false,
            false,
            "WRITE_GATE_DISABLED",
            "safe",
            transactionId = "tx-7",
        )
        val result = env.supervisor.execute(
            SupervisorDecision.Restore(7L, "tx-7", "LEASE_EXPIRED"),
        )
        assertEquals("WRITE_GATE_DISABLED", result!!.code)
        assertFalse(result.restored)
        assertEquals(0, env.restorer.commands)
    }

    // WAVE2_THERMAL_DERATE_SPEC.md S5 test 5 / S3.1: at the floor and still over trip, the
    // decision is remain (Continue), never Restore. Two CRITICAL (2-rung) hits are enough to
    // reach ThermalDerateLadder's 3-rung floor from nominal; a third, still-critical sample must
    // not release to Stock.
    @Test
    fun `WAVE2 test 5 at the floor and still over trip the decision is remain never Restore`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 121, 40, 300)

        env.now = 0L
        val first = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals(2, first.rung)
        env.now = 400L
        val second = env.supervisor.tick() as SupervisorDecision.Derate
        assertEquals(ThermalDerateLadder.maxRung, second.rung) // clamped: 2 + 2 -> floor (3)

        // Still critical, but already at the floor: remain, never Restore.
        env.now = 800L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
        env.now = 1200L
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
    }

    // Ownership/integrity failures are NOT
    // thermal signals and must still produce a full Restore to Stock, completely unchanged.
    // Existing incidental coverage: hard-lease expiry ("renewals are finite..."), owner death
    // ("exact owner death requests generation bound restore"), boot-id mismatch ("boot transport
    // and gpu drift remain axis exact"), unrepairable target-contract drift ("matching
    // authenticated target continues..."); fan-ownership-specific coverage of this same invariant
    // lives in "ownership and integrity failures still restore to
    // Stock even when the contract owns the fan" above. This test asserts the invariant
    // explicitly in one place, rather than leaving it only incidentally covered -- including that a simultaneous CRITICAL thermal reading
    // does not turn an integrity failure into a derate.
    @Test
    fun `WAVE2 test 9 ownership and integrity failures still produce a full Restore unchanged`() {
        // Dead owner.
        val ownerEnv = Env()
        ownerEnv.adopt(everForeground = true)
        ownerEnv.living = false
        val ownerDied = ownerEnv.supervisor.tick()
        assertTrue(ownerDied is SupervisorDecision.Restore)
        assertEquals("OWNER_TOKEN_DIED", (ownerDied as SupervisorDecision.Restore).reason)

        // Hard-lease expiry.
        val leaseEnv = Env()
        leaseEnv.adopt(everForeground = true, hardDeadlineMs = 5_000L, scope = ActiveSessionScope.SYSTEM)
        leaseEnv.now = 5_000L
        val leaseExpired = leaseEnv.supervisor.tick()
        assertTrue(leaseExpired is SupervisorDecision.Restore)
        assertEquals("HARD_LEASE_EXPIRED", (leaseExpired as SupervisorDecision.Restore).reason)

        // Boot-id mismatch (journal/target-contract integrity).
        val bootEnv = Env()
        val contract = targetContract()
        bootEnv.adopt(everForeground = true, targetContract = contract)
        bootEnv.sample = targetSample(contract).copy(bootId = "another-boot")
        val bootMismatch = bootEnv.supervisor.tick()
        assertTrue(bootMismatch is SupervisorDecision.Restore)
        assertEquals("TARGET_CONTRACT_DRIFT_BOOT", (bootMismatch as SupervisorDecision.Restore).reason)

        // Even a simultaneous CRITICAL thermal reading does not turn an integrity failure into a
        // derate -- ownership loss is checked before telemetry is even read (spec S3.2's "we no
        // longer know we own the device" outranks a confirmed-hot reading).
        val diedHotEnv = Env()
        diedHotEnv.adopt(everForeground = true)
        diedHotEnv.living = false
        diedHotEnv.sample = ThermalTelemetrySample(PowerManager.THERMAL_STATUS_NONE, 121, 40, 300)
        val diedHot = diedHotEnv.supervisor.tick()
        assertTrue(diedHot is SupervisorDecision.Restore)
        assertEquals("OWNER_TOKEN_DIED", (diedHot as SupervisorDecision.Restore).reason)
    }

    @Test
    fun `WAVE2 Stage B Derate calls the derater with exact identity and rung never restorer or repairer`() {
        val env = Env()
        env.adopt(everForeground = true)
        val decision = SupervisorDecision.Derate(
            generation = 7L,
            transactionId = "tx-7",
            reason = "THERMAL_CPU",
            rungs = 1,
            rung = 1,
        )

        val result = env.supervisor.execute(decision)

        assertEquals(1, env.derater.calls)
        assertEquals(7L, env.derater.lastGeneration)
        assertEquals("tx-7", env.derater.lastTransactionId)
        assertEquals(1, env.derater.lastRung)
        assertEquals(0, env.restorer.calls)
        assertEquals(0, env.repairer.calls)
        assertEquals(env.derater.result, result)
        // The session persists -- a derate never clears or restores on success.
        assertTrue(env.supervisor.activeSession() != null)
    }

    @Test
    fun `successful derate refreshes the in memory applied target contract so the next tick sees no false drift`() {
        val env = Env()
        val contract = targetContract()
        env.adopt(everForeground = true, targetContract = contract, scope = ActiveSessionScope.SYSTEM)
        val deratedPlan = TuningPlan(
            profileId = "performance",
            packageName = "example.game",
            cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 1_958_400),
            gpuMaxPowerLevel = 7,
            fanMode = null,
            calibrated = true,
        )
        env.derater.result = TransactionResult(
            success = true,
            applied = true,
            restored = false,
            code = TransactionCoordinator.THERMAL_DERATE_APPLIED,
            message = "applied",
            plan = deratedPlan,
            transactionId = "tx-7",
            generation = 7L,
        )

        env.supervisor.execute(SupervisorDecision.Derate(7L, "tx-7", "THERMAL_CPU", 1, 1))

        val refreshed = env.supervisor.activeSession()?.appliedTargetContract
        assertEquals(deratedPlan.cpuMaxKhz, refreshed?.cpuQosCeilingKhz)
        assertEquals(deratedPlan.gpuMaxPowerLevel, refreshed?.gpuMaxPowerLevel)
        // Every other field of the contract is carried forward exactly, untouched.
        assertEquals(contract.bootId, refreshed?.bootId)
        assertEquals(contract.deviceProfile, refreshed?.deviceProfile)
        assertEquals(contract.cpuQosTransport, refreshed?.cpuQosTransport)
        assertEquals(contract.gpuMinPowerLevel, refreshed?.gpuMinPowerLevel)
        assertEquals(contract.fanMode, refreshed?.fanMode)

        // The critical proof: a live sample matching the NEW (derated) ceiling ticks Continue, not
        // a false Restore. If the contract were left stale at the OLD ceiling, this exact sample
        // would read as TARGET_CONTRACT_DRIFT_CPU/GPU_MAX and force a restore next tick --
        // reproducing the exact defect WAVE2 fixed (spec S1: 76.9 C -> 104.5 C mean).
        env.now = 1_000L
        env.sample = targetSample(checkNotNull(refreshed))
        assertEquals(SupervisorDecision.Continue, env.supervisor.tick())
    }

    @Test
    fun `an unsigned derate rung degrades to remain and never falls back to restore`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.derater.result = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "THERMAL_DERATE_UNSIGNED_RUNG",
            message = "Thermal derate rung 1 is not signed for profile performance.",
            transactionId = "tx-7",
            generation = 7L,
        )

        val result = env.supervisor.execute(SupervisorDecision.Derate(7L, "tx-7", "THERMAL_CPU", 1, 1))

        assertEquals("THERMAL_DERATE_UNSIGNED_RUNG", result?.code)
        assertEquals(0, env.restorer.calls)
        assertTrue(env.supervisor.activeSession() != null)
    }

    @Test
    fun `any derate actuation failure never falls back to restore regardless of reason`() {
        listOf(
            "THERMAL_DERATE_UNSIGNED_RUNG",
            "THERMAL_DERATE_CONTEXT_REJECTED",
            "THERMAL_DERATE_FAILED",
            "THERMAL_DERATE_JOURNAL_PERSIST_FAILED",
            "THERMAL_DERATE_FENCE_LOST",
            "PLAN_NOT_ADMITTED",
            "CALIBRATION_REQUIRED",
            "WRITE_GATE_DISABLED",
            "NO_ACTIVE_SESSION",
        ).forEach { code ->
            val env = Env()
            env.adopt(everForeground = true)
            env.derater.result = TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = code,
                message = "failed",
                transactionId = "tx-7",
                generation = 7L,
            )
            env.supervisor.execute(SupervisorDecision.Derate(7L, "tx-7", "THERMAL_CPU", 1, 1))
            assertEquals("code=$code", 0, env.restorer.calls)
        }
    }

    @Test
    fun `a throwing derater is converted into a failure result and never restores`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.derater.throwOnCall = true

        val result = env.supervisor.execute(SupervisorDecision.Derate(7L, "tx-7", "THERMAL_CPU", 1, 1))

        assertEquals("THERMAL_DERATE_EXCEPTION", result?.code)
        assertEquals(0, env.restorer.calls)
        assertTrue(env.supervisor.activeSession() != null)
    }

    @Test
    fun `derate STALE_GENERATION clears the matching session without restoring`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.derater.result = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "STALE_GENERATION",
            message = "stale",
            transactionId = "tx-7",
            generation = 7L,
        )

        env.supervisor.execute(SupervisorDecision.Derate(7L, "tx-7", "THERMAL_CPU", 1, 1))

        assertNull(env.supervisor.activeSession())
        assertEquals(0, env.restorer.calls)
    }

    @Test
    fun `ownership and integrity restore decisions are unaffected by the derater and never call it`() {
        val env = Env()
        env.adopt(everForeground = true)
        env.living = false // OWNER_TOKEN_DIED, unrelated to any thermal signal.

        val decision = env.supervisor.tick()
        assertTrue(decision is SupervisorDecision.Restore)
        val result = env.supervisor.execute(decision)

        assertEquals(1, env.restorer.calls)
        assertEquals(0, env.derater.calls)
        assertEquals("RESTORED", result?.code)
    }

    private class Env(
        val policy: SupervisorPolicy = SupervisorPolicy(
            sampleIntervalMs = 1_000L,
            activationGraceMs = 10_000L,
            leaseWindowMs = 30_000L,
            missingTelemetryLimit = 3,
        ),
    ) {
        var now = 0L
        var workload: WorkloadTruth = WorkloadTruth.INDETERMINATE
        var sample: ThermalTelemetrySample? = ThermalTelemetrySample(
            androidThermalStatus = PowerManager.THERMAL_STATUS_NONE,
            cpuTempC = 40,
            gpuTempC = 40,
            batteryTempDeciC = 300,
        )
        var living = true

        val restorer = CountingRestorer()
        val repairer = CountingRepairer()
        val derater = CountingDerater()
        val fanReasserter = CountingFanReasserter()
        val supervisor = HardwareSessionSupervisor(
            policy = policy,
            clock = SupervisorClock { now },
            workload = WorkloadObservationPort { workload },
            telemetry = ThermalTelemetryPort { sample },
            ownerLiveness = OwnerLivenessPort { _ -> living },
            restorer = restorer,
            targetRepairer = repairer,
            derater = derater,
            fanReasserter = fanReasserter,
        )

        fun adopt(
            everForeground: Boolean = false,
            hardDeadlineMs: Long = 120_000L,
            ownerTokenId: Long = NO_OWNER_TOKEN_ID,
            targetContract: AppliedTargetContract? = null,
            scope: ActiveSessionScope = ActiveSessionScope.LAUNCH,
        ) {
            now = 0L
            living = true
            workload = if (everForeground) WorkloadTruth.TARGET_FOREGROUND else WorkloadTruth.INDETERMINATE
            sample = targetContract?.let(::targetSample) ?: ThermalTelemetrySample(
                androidThermalStatus = PowerManager.THERMAL_STATUS_NONE,
                cpuTempC = 40,
                gpuTempC = 40,
                batteryTempDeciC = 300,
            )
            supervisor.adoptAppliedSession(
                ActiveSessionIdentity(
                    generation = 7L,
                    transactionId = "tx-7",
                    requestId = "req-7",
                    ownerUid = 42,
                    targetPackage = "example.game",
                    activationDeadlineMs = 10_000L,
                    // Derived through the production function rather than hand-set, so these
                    // tests exercise the real deadline choice instead of a parallel one.
                    leaseDeadlineMs = ActiveSessionLeaseDeadline.initial(
                        scope = scope,
                        nowElapsedRealtimeMs = 0L,
                        renewableWindowMs = 30_000L,
                        hardDeadlineElapsedRealtimeMs = hardDeadlineMs,
                    ),
                    targetEverForeground = everForeground,
                    ownerTokenId = ownerTokenId,
                    hardLeaseDeadlineMs = hardDeadlineMs,
                    appliedTargetContract = targetContract,
                    scope = scope,
                ),
            )
        }
    }

    private class CountingRestorer : GenerationBoundRestorer {
        var calls = 0
        var commands = 0
        var result = TransactionResult(true, false, true, "RESTORED", "ok", transactionId = "tx-7")
        override fun restoreBound(expectedGeneration: Long, expectedTransactionId: String): TransactionResult {
            calls++
            if (result.code !in setOf("STALE_GENERATION", "WRITE_GATE_DISABLED", "JOURNAL_CORRUPT", "NO_ACTIVE_SESSION")) {
                commands += 1
            }
            return result
        }
    }

    private class CountingRepairer : GenerationBoundTargetRepairer {
        var calls = 0
        var throwOnCall = false
        var result = TransactionResult(
            success = true,
            applied = false,
            restored = false,
            code = TransactionCoordinator.TARGET_REPAIRED,
            message = "repaired",
            transactionId = "tx-7",
            generation = 7L,
        )

        override fun repairCpuTargetBound(
            expectedGeneration: Long,
            expectedTransactionId: String,
        ): TransactionResult {
            calls++
            if (throwOnCall) error("injected repair exception")
            return result
        }
    }

    private class CountingFanReasserter : GenerationBoundFanReasserter {
        var calls = 0
        var throwOnCall = false
        var lastGeneration: Long? = null
        var lastTransactionId: String? = null
        var result = TransactionResult(
            success = true,
            applied = true,
            restored = false,
            code = "FAN_REASSERTED",
            message = "reasserted",
            transactionId = "tx-7",
            generation = 7L,
        )

        override fun reassertFanBound(
            expectedGeneration: Long,
            expectedTransactionId: String,
        ): TransactionResult {
            calls++
            lastGeneration = expectedGeneration
            lastTransactionId = expectedTransactionId
            if (throwOnCall) error("injected fan reassert exception")
            return result
        }
    }

    private class CountingDerater : GenerationBoundDerater {
        var calls = 0
        var throwOnCall = false
        var lastGeneration: Long? = null
        var lastTransactionId: String? = null
        var lastRung: Int? = null
        var result = TransactionResult(
            success = true,
            applied = true,
            restored = false,
            code = TransactionCoordinator.THERMAL_DERATE_APPLIED,
            message = "applied",
            plan = TuningPlan(
                profileId = "performance",
                packageName = "example.game",
                cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 2_841_600),
                gpuMaxPowerLevel = 2,
                fanMode = null,
                calibrated = true,
            ),
            transactionId = "tx-7",
            generation = 7L,
        )

        override fun derateBound(
            expectedGeneration: Long,
            expectedTransactionId: String,
            rung: Int,
        ): TransactionResult {
            calls++
            lastGeneration = expectedGeneration
            lastTransactionId = expectedTransactionId
            lastRung = rung
            if (throwOnCall) error("injected derate exception")
            return result
        }
    }

    private companion object {
        /**
         * Real Odin 3 RE5-session `cpu_hot_mC` readings, sample indices 40-59 -- the exact 20
         * samples that wrongly rolled back on-device (WAVE1A test 7's "noise band"; the phrase
         * came from the uncommitted WAVE1A spec, but the DATA below does not depend on it),
         * truncated to whole degrees C exactly as the production telemetry path truncates
         * before the value reaches ThermalTelemetrySample.cpuTempC. Extracted once from
         * `.re5-session-evidence.tsv` (untracked device evidence, not read at test runtime).
         */
        val NOISE_BAND_CPU_C = listOf(
            59, 63, 63, 62, 63, 67, 66, 67, 66, 65,
            64, 64, 64, 72, 79, 84, 81, 84, 77, 86,
        )

        /**
         * Same source and truncation, sample indices 61-146 (test 7's "sustained run" -- the
         * genuine emergency that followed the noise band and must still restore).
         */
        val SUSTAINED_RUN_CPU_C = listOf(
            103, 102, 102, 103, 103, 103, 105, 104, 105, 103,
            103, 103, 104, 105, 103, 105, 104, 105, 105, 103,
            104, 104, 102, 102, 103, 103, 101, 104, 103, 103,
            101, 103, 103, 104, 103, 103, 103, 103, 105, 105,
            105, 101, 102, 104, 100, 105, 103, 104, 103, 103,
            104, 105, 104, 105, 103, 103, 102, 103, 105, 105,
            105, 105, 105, 105, 104, 105, 103, 105, 104, 105,
            105, 102, 103, 105, 105, 104, 104, 104, 104, 104,
            104, 104, 104, 104, 99, 73,
        )

        fun appliedRecord(): TransactionJournalRecord {
            val plan = PersistedTuningPlan(
                profileId = "quiet",
                packageName = "example.game",
                cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 2_246_400),
                gpuMaxPowerLevel = 6,
                fanMode = null,
                calibrated = false,
            )
            val projection = listOf(
                PersistedAppliedMutation(
                    logicalId = "cpu:0:scaling_max_freq",
                    axis = TuningAxis.CPU,
                    canonicalPath = HardwareProbe.CPU_QOS_MAX_PATH,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = 1_996_800,
                    expectPresent = true,
                ),
                PersistedAppliedMutation(
                    logicalId = "cpu:6:scaling_max_freq",
                    axis = TuningAxis.CPU,
                    canonicalPath = HardwareProbe.CPU_QOS_MAX_PATH,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = 2_246_400,
                    expectPresent = true,
                ),
                PersistedAppliedMutation(
                    logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                    axis = TuningAxis.GPU,
                    canonicalPath = HardwareProbe.GPU_MIN_LEVEL,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = 6,
                    expectPresent = true,
                ),
                PersistedAppliedMutation(
                    logicalId = HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                    axis = TuningAxis.GPU,
                    canonicalPath = HardwareProbe.GPU_MAX_LEVEL,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = 6,
                    expectPresent = true,
                ),
            )
            val snapshot = HardwareSnapshot(
                schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                hardwareFingerprint = "hardware-1",
                bootIdentity = "boot-1",
                nodes = projection.map { mutation ->
                    TopologyNodeState(
                        logicalId = mutation.logicalId,
                        axis = mutation.axis,
                        canonicalPath = mutation.canonicalPath,
                        originalValue = if (mutation.axis == TuningAxis.GPU) 0 else Int.MAX_VALUE,
                        originalMode = "644",
                        originalUid = 0,
                        originalGid = 0,
                        present = true,
                    )
                },
            )
            return TransactionJournalRecord(
                schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V4,
                generation = 7L,
                requestId = "req-7",
                transactionId = "tx-7",
                hardwareFingerprint = "hardware-1",
                deviceProfile = "odin3",
                planHash = PlanHash.ofPersisted(plan),
                profileId = plan.profileId,
                targetPackage = plan.packageName,
                phase = TransactionPhase.APPLIED,
                creationTime = 1L,
                requestDeadline = 120_000L,
                leaseDeadline = 120_000L,
                bootIdentity = "boot-1",
                snapshot = snapshot,
                expectedTopologySha256 = "topology",
                snapshotSha256 = "snapshot",
                lastCompletedMutationBoundary = projection.size,
                lastCompletedRestoreBoundary = 0,
                appliedPlan = plan,
                applyOrderLogicalIds = projection.map { it.logicalId },
                appliedMutationProjection = projection,
            )
        }

        fun targetContract() = AppliedTargetContract(
            deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
            bootId = "boot-1",
            cpuQosCeilingKhz = mapOf(0 to 1_996_800, 6 to 2_246_400),
            cpuQosTransport = CpuQosTransport.SHARED_QOS,
            gpuMinPowerLevel = 6,
            gpuMaxPowerLevel = 6,
            fanMode = null,
        )

        fun targetSample(contract: AppliedTargetContract) = ThermalTelemetrySample(
            androidThermalStatus = PowerManager.THERMAL_STATUS_NONE,
            cpuTempC = 40,
            gpuTempC = 40,
            batteryTempDeciC = 300,
            cpuQosCeilingKhz = contract.cpuQosCeilingKhz,
            cpuQosTransport = contract.cpuQosTransport,
            gpuMinPowerLevel = contract.gpuMinPowerLevel,
            gpuMaxPowerLevel = contract.gpuMaxPowerLevel,
            vendorFanMode = contract.fanMode,
            vendorPerformanceMode = contract.vendorMode,
            bootId = contract.bootId,
        )
    }
}
