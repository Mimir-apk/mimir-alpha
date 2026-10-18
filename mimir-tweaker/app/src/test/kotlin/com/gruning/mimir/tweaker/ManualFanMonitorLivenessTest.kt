package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins
 * [ManualFanMonitorLiveness.denialReason]'s reason vocabulary and its exact agreement with
 * [ManualFanMonitorLiveness.allowsHeartbeat] (now a thin `denialReason(...) == null` wrapper over
 * the SAME truth table and the SAME CAS state transitions [ManualFanSafetyTest] already pins via
 * [ManualFanMonitorLiveness.allowsHeartbeat] alone). Every scenario below walks a matched pair of
 * fresh [ManualFanMonitorLiveness] instances through an IDENTICAL call sequence -- one instance
 * driven through `denialReason`, the other through `allowsHeartbeat` -- and asserts the two agree
 * at every step, which only holds if a call sequence never observes a state CAS on one instance
 * that the other didn't equally perform for the same step.
 *
 * The 14-step sequence in `denialReason and allowsHeartbeat agree across the full apply/handoff/
 * healthy timeline` reproduces, step for step, the exact numbers already pinned as green in
 * [ManualFanSafetyTest]'s `manual monitor heartbeat admits bounded apply handoff then requires
 * fresh controller progress` -- so this file adds reason-string granularity to an already-proven
 * boolean timeline rather than asserting new thresholds.
 */
class ManualFanMonitorLivenessTest {
    @Test
    fun `Smart contract tick renews liveness only after observing the live vendor loop`() {
        val source = mainSourceText("MimirTweakerService.kt")
        val decision = source.substringAfter("private fun fanControlDecision(")
        val smart = decision.substringAfter("if (contract.fanMode == TuningPlanner.FAN_SMART)")
            .substringBefore("if (renewalScopeAdmitted(active.scope))")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }
        val liveCheck = smart.indexOf("if (probe.fanModeRaw()?.toIntOrNull() == TuningPlanner.FAN_SMART)")
        val healthy = smart.indexOf("manualFanMonitorLiveness.markHealthy(active, SystemClock.uptimeMillis())")
        assertTrue(liveCheck in 0 until healthy)
    }

    @Test
    fun `all manual fan marks use uptime clock`() {
        val source = stripLineComments(mainSourceText("MimirTweakerService.kt"))
        val markLines = source.lineSequence()
            .filter { line ->
                line.contains("manualFanMonitorLiveness.markHealthy(") ||
                    line.contains("manualFanMonitorLiveness.markRepairing(")
            }
            .toList()

        assertEquals("the P8 sleep fix has exactly seven service liveness marks", 7, markLines.size)
        markLines.forEach { line ->
            assertTrue(
                "every manual fan liveness mark must read uptimeMillis(): $line",
                line.contains("SystemClock.uptimeMillis()"),
            )
        }
    }

    @Test
    fun `fan controller tick receives the uptime domain`() {
        val source = stripLineComments(mainSourceText("MimirTweakerService.kt"))
        val tickSite = sliceBetween(
            source,
            "val telemetry = latestMonitorFanSample",
            "return when (decision)",
        )

        assertTrue(
            "the controller tick must capture CPU-awake uptime after telemetry resolution",
            tickSite.contains("val nowUptimeMs = SystemClock.uptimeMillis()"),
        )
        assertTrue(
            "fanController.tick must receive that same uptime reading",
            tickSite.contains("nowElapsedRealtimeMs = nowUptimeMs"),
        )
    }

    /**
     * F1 (fan fence, `build/test-captures/fanfence/ROOT_CAUSE.md`): the authority samples NO gate timestamp.
     * It used to capture `uptimeMillis()` before the live fan-mode Settings read and the gate's
     * journal load, and the gate then compared that stale sample against a tick stamped during
     * those reads -- `HEALTHY_STALE age=-779` on the Odin, a healthy session restored.
     * Now the reads come first and `ManualFanMonitorLiveness` clocks the comparison itself.
     */
    @Test
    fun `authority reads the live fan mode first and passes the gate no timestamp`() {
        val source = stripLineComments(mainSourceText("RemoteGuardianProcessAuthority.kt"))
        val constructor = sliceBetween(
            source,
            "internal class RemoteGuardianProcessAuthority(",
            ") : GuardianArmAuthorityPort",
        )
        val publish = sliceBetween(
            source,
            "private fun publishHeartbeat()",
            "val heartbeat = RestoreGuardianHeartbeat(",
        )
        val heartbeatPayload = sliceBetween(
            source,
            "val heartbeat = RestoreGuardianHeartbeat(",
            "val signed = available.signHeartbeat(heartbeat)",
        )

        assertTrue(
            "the gate lambda takes the envelope and the live fan mode only -- no clock sample",
            constructor.contains("private val heartbeatDenialReason: (RestoreGuardianEnvelope, Int?) -> String?"),
        )
        assertFalse(
            "the authority must own no uptime clock for the gate any more",
            constructor.contains("uptimeMillis"),
        )
        assertTrue(
            "the heartbeat deadline clock must remain elapsed realtime",
            publish.contains("val now = elapsedRealtimeMs()") &&
                publish.contains("if (now < 0 || now >= envelope.hardDeadlineElapsedRealtimeMs) return"),
        )
        assertFalse(
            "heartbeat publication must not capture an uptime sample ahead of the reads",
            publish.contains("uptimeMillis()"),
        )
        assertTrue(
            "the live fan-mode Settings read must precede the gate, which clocks itself",
            publish.indexOf("val liveFanMode = liveFanMode()") in
                0 until publish.indexOf("heartbeatDenialReason(envelope, liveFanMode)"),
        )
        assertTrue(
            "the signed Guardian heartbeat must retain elapsed realtime",
            heartbeatPayload.contains("elapsedRealtimeMs = now") &&
                heartbeatPayload.contains("hardDeadlineElapsedRealtimeMs = envelope.hardDeadlineElapsedRealtimeMs"),
        )
    }

    /**
     * F1: the test the old :221-224 pin should have been. Gen 51 / gen 38 on the Odin: the
     * heartbeat captured its clock, spent ~0.8 s in a Settings read and a journal load (the journal
     * lock is shared with the tick's writes), and the controller ticked DURING those reads. The old
     * gate computed `now - tick = -779` and called the live controller stale; this gate samples its
     * clock at the comparison, after the reads, and sees a 50 ms old tick.
     */
    @Test
    fun `F1 -- a tick stamped 800 ms into the heartbeat's reads is allowed`() {
        var clock = 9_000L
        val liveness = ManualFanMonitorLiveness(uptimeClock = { clock })
        val identity = activeIdentity()
        val envelope = manualEnvelope()
        liveness.markHealthy(identity, 3_000)

        // The heartbeat's reads begin at 9_000 and take 850 ms; the tick lands at 9_800.
        liveness.markHealthy(identity, 9_800)
        clock = 9_850
        assertNull(
            "a tick stamped during the heartbeat's reads is 50 ms old at the comparison",
            liveness.denialReason(envelope, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_MANUAL),
        )
        // The shape that fired on the device: a clock captured before the reads, compared after.
        assertEquals(
            "HEALTHY_STALE age=-800",
            liveness.denialReason(envelope, 9_000, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_MANUAL),
        )
        // The positive side of the gate is unchanged: 7 s without a tick is still stale.
        clock = 16_801
        assertEquals(
            "HEALTHY_STALE age=7001",
            liveness.denialReason(envelope, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_MANUAL),
        )
    }

    @Test
    fun `F1 -- the service gate lambda loads the journal before asking the self-clocking gate`() {
        val source = stripLineComments(mainSourceText("MimirTweakerService.kt"))
        val gate = sliceBetween(
            source,
            "heartbeatDenialReason = { envelope, liveFanMode ->",
            "liveFanMode = { runCatching { probe.fanModeRaw()?.toIntOrNull() }.getOrNull() },",
        )
        val load = gate.indexOf("val load = journal.load()")
        val ask = gate.indexOf("val reason = manualFanMonitorLiveness.denialReason(")
        assertTrue("the gate lambda must load the journal", load >= 0)
        assertTrue("the journal load must precede the gate call", load < ask)
        assertFalse(
            "no timestamp may be threaded into the production gate",
            gate.substring(ask).substringBefore(")").contains("nowElapsedRealtimeMs"),
        )
    }

    @Test
    fun `deadman semantics distinguish elapsed suspend gap from CPU-awake progress`() {
        val liveness = ManualFanMonitorLiveness()
        val envelope = manualEnvelope()
        val identity = activeIdentity()
        liveness.markHealthy(identity, 1_000)

        // BOOTTIME advances during a 15 s suspend, but uptime advances only while the daemon runs.
        assertEquals(
            "HEALTHY_STALE age=15000",
            liveness.denialReason(envelope, 16_000, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_MANUAL),
        )
        assertNull(
            liveness.denialReason(envelope, 1_500, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_MANUAL),
        )
    }

    @Test
    fun `dual ownership envelope keeps deadman gating after Manual entry and allows live Smart`() {
        val envelope = manualEnvelope().copy(restoreEntries = listOf(
            (manualEnvelope().restoreEntries.single() as RestoreGuardianFanPutEntry)
                .copy(rendererAppliedIntermediates = listOf(4, 6))))
        val liveness = ManualFanMonitorLiveness()
        liveness.markHealthy(activeIdentity(), 1_000)
        assertNull(liveness.denialReason(envelope, 8_001, truth(TransactionPhase.APPLIED), 4))
        assertEquals("HEALTHY_STALE age=7001",
            liveness.denialReason(envelope, 8_001, truth(TransactionPhase.APPLIED), 6))
        assertEquals("HEALTHY_STALE age=7001",
            liveness.denialReason(envelope, 8_001, truth(TransactionPhase.APPLIED), null))
    }

    @Test
    fun `a plain vendor SMART envelope is never gated -- not owned, both agree allowed`() {
        val smartEnvelope = manualEnvelope().copy(
            restoreEntries = listOf(
                RestoreGuardianFanPutEntry(
                    applyOrdinal = 0,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                    rendererAppliedIntermediates = listOf(TuningPlanner.FAN_SMART),
                ),
            ),
        )
        assertStep(smartEnvelope, 1_000, null, expectedReason = null)
    }

    @Test
    fun `denialReason names an unhandled journal phase as PHASE_phase, allowsHeartbeat still just denies`() {
        val envelope = manualEnvelope()
        assertStep(envelope, 1_000, truth(TransactionPhase.PREPARED), expectedReason = "PHASE_PREPARED")
        assertStep(envelope, 1_000, truth(TransactionPhase.ARMING), expectedReason = "PHASE_ARMING")
        assertStep(envelope, 1_000, truth(TransactionPhase.RESTORING), expectedReason = "PHASE_RESTORING")
    }

    @Test
    fun `denialReason and allowsHeartbeat agree across the full apply-handoff-healthy timeline`() {
        val envelope = manualEnvelope()
        val identity = activeIdentity()
        val applying = truth(TransactionPhase.APPLYING)
        val applied = truth(TransactionPhase.APPLIED)
        val reasonLiveness = ManualFanMonitorLiveness()
        val allowedLiveness = ManualFanMonitorLiveness()

        // NO_TRUTH: no journal record yet.
        assertStep(reasonLiveness, allowedLiveness, envelope, 1_000, null, "NO_TRUTH")
        // APPLYING: fresh CAS installs the window -- allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 1_000, applying, null)
        // Still inside MAX_APPLYING_AGE_MS (45_000) at the boundary -- allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 46_000, applying, null)
        // One ms past the boundary: the window matches but is out of range. The closed reason
        // vocabulary has no APPLYING-specific stale token, so this maps to STATE_MISMATCH (see the
        // kdoc on ManualFanMonitorLiveness.denialReason).
        assertStep(reasonLiveness, allowedLiveness, envelope, 46_001, applying, "STATE_MISMATCH")

        reasonLiveness.disarm()
        allowedLiveness.disarm()

        // APPLIED, state null: CAS installs an AppliedHandoff -- allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 1_000, applied, null)
        // Still inside MAX_APPLIED_HANDOFF_AGE_MS (6_000) at the boundary -- allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 7_000, applied, null)
        // One ms past: HANDOFF_STALE, with the age it computed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 7_001, applied, "HANDOFF_STALE age=6001")

        reasonLiveness.markHealthy(identity, 1_000)
        allowedLiveness.markHealthy(identity, 1_000)

        // HealthyTick at t=1_000: at t=8_000 age=7_000, the MAX_HEALTHY_AGE_MS boundary -- allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 8_000, applied, null)
        // One ms past: HEALTHY_STALE, with the age it computed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 8_001, applied, "HEALTHY_STALE age=7001")

        reasonLiveness.markHealthy(identity, 9_000)
        allowedLiveness.markHealthy(identity, 9_000)

        // The explicit-`now` overload (this test's instrument) keeps MAX_CONCURRENT_TICK_SKEW_MS
        // (250) on the negative side for the sub-millisecond CAS race it was written for.
        assertStep(reasonLiveness, allowedLiveness, envelope, 8_983, applied, null)
        // F1 (fan fence, 2026-10-14): the pin that used to sit here -- `8_749 -> "HEALTHY_STALE
        // age=-251"`, "negative age included verbatim" -- enshrined the device defect: a
        // controller tick stamped AFTER the heartbeat captured its clock is a live controller, not
        // a stale one. The production gate now samples its clock at the comparison (see
        // `F1 -- a tick stamped 800 ms into the heartbeat's reads is allowed`), so no negative age
        // of any size can reach the comparison there. A later check against the same tick is what
        // the gate actually sees: age 50 at 9_050 -- allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 9_050, applied, null)

        // markHealthy for a DIFFERENT transactionId (same generation) is a documented no-op when
        // the current state already belongs to a different transaction -- the HealthyTick from
        // t=9_000 above is untouched.
        reasonLiveness.markHealthy(identity.copy(transactionId = "other"), 17_000)
        allowedLiveness.markHealthy(identity.copy(transactionId = "other"), 17_000)
        assertStep(reasonLiveness, allowedLiveness, envelope, 17_000, applied, "HEALTHY_STALE age=8000")

        reasonLiveness.disarm()
        allowedLiveness.disarm()

        // APPLIED, state null again: CAS installs a fresh AppliedHandoff -- allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 17_000, applied, null)
        // The journal truth's own transactionId no longer matches the envelope's: TRUTH_MISMATCH,
        // decided before the deadman's internal state is even consulted.
        assertStep(
            reasonLiveness,
            allowedLiveness,
            envelope,
            17_001,
            applied.copy(transactionId = "other"),
            "TRUTH_MISMATCH",
        )
    }

    @Test
    fun `a stale HealthyTick left by an earlier generation denies the next one as STATE_MISMATCH`() {
        // A new authenticated envelope (a new generation) is a new liveness epoch -- ordinarily
        // onEnvelopeReady disarms the deadman for exactly this reason. Tested here without that
        // disarm to pin what the gate itself does when a PRIOR generation's HealthyTick is still
        // sitting in state: the internal state's own generation/transactionId no longer matches
        // this envelope/truth pair, which is STATE_MISMATCH, not an age question.
        val priorGeneration = manualEnvelope()
        val nextGeneration = priorGeneration.copy(generation = 8, transactionId = "transaction-2")
        val priorIdentity = activeIdentity()
        val nextTruth = ManualFanMonitorLiveness.JournalTruth(
            generation = 8,
            transactionId = "transaction-2",
            phase = TransactionPhase.APPLIED,
        )

        val reasonLiveness = ManualFanMonitorLiveness()
        val allowedLiveness = ManualFanMonitorLiveness()
        reasonLiveness.markHealthy(priorIdentity, 500)
        allowedLiveness.markHealthy(priorIdentity, 500)

        assertStep(reasonLiveness, allowedLiveness, nextGeneration, 1_000, nextTruth, "STATE_MISMATCH")
    }

    @Test
    fun `an APPLYING check against a matching but wrong-shaped state is STATE_MISMATCH`() {
        // Same generation/transaction, but the state already advanced to HealthyTick while the
        // journal truth still reads APPLYING -- current.matches(...) is true, but the state is not
        // an ApplyingWindow, which the closed vocabulary also names STATE_MISMATCH.
        val envelope = manualEnvelope()
        val identity = activeIdentity()
        val reasonLiveness = ManualFanMonitorLiveness()
        val allowedLiveness = ManualFanMonitorLiveness()
        reasonLiveness.markHealthy(identity, 500)
        allowedLiveness.markHealthy(identity, 500)

        assertStep(reasonLiveness, allowedLiveness, envelope, 1_000, truth(TransactionPhase.APPLYING), "STATE_MISMATCH")
    }

    // -- Phase B (§3 B1, L2): a repairing window counts as controller liveness, bounded -------

    @Test
    fun `repeated failed repairs cannot refresh beyond two dispatches in one episode`() {
        val liveness = ManualFanMonitorLiveness()
        val identity = activeIdentity()
        val envelope = manualEnvelope()
        liveness.markHealthy(identity, 0)
        // An eight-second cadence stays inside the real four-per-thirty-second budget.
        for (now in 0L..120_000L step 8_000L) {
            liveness.markRepairing(identity, now)
            assertEquals(
                "repair at $now must retain the final admitted dispatch deadline",
                if (now <= 18_000) null else "REPAIR_STALE age=${now - 8_000}",
                liveness.denialReason(envelope, now, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_SPORT),
            )
        }
    }

    @Test
    fun `failed repair at 4600 permits retry at 5000 through heartbeat at 10200`() {
        val liveness = ManualFanMonitorLiveness()
        val identity = activeIdentity()
        val envelope = manualEnvelope()
        liveness.markHealthy(identity, 0)
        liveness.markRepairing(identity, 0)
        assertNull(liveness.denialReason(envelope, 4_600, truth(TransactionPhase.APPLIED), 6))
        liveness.markRepairing(identity, 5_000)
        assertNull(liveness.denialReason(envelope, 10_200, truth(TransactionPhase.APPLIED), 6))
        liveness.markHealthy(identity, 10_600)
        assertNull(liveness.denialReason(envelope, 17_600, truth(TransactionPhase.APPLIED), 6))
    }

    @Test
    fun `a later dispatch cannot revive an already expired repair window`() {
        val liveness = ManualFanMonitorLiveness()
        liveness.markRepairing(activeIdentity(), 0)
        liveness.markRepairing(activeIdentity(), 10_001)
        assertEquals("REPAIR_STALE age=10001",
            liveness.denialReason(manualEnvelope(), 10_001, truth(TransactionPhase.APPLIED), 6))
    }

    @Test
    fun `abandoned first apply needs disarm before a dual-mode fallback generation can heartbeat`() {
        val liveness = ManualFanMonitorLiveness()
        val first = manualEnvelope()
        assertNull(liveness.denialReason(first, 5_000, truth(TransactionPhase.APPLYING), 6))
        val second = first.copy(generation = 8, transactionId = "fallback", restoreEntries = listOf(
            (first.restoreEntries.single() as RestoreGuardianFanPutEntry).copy(rendererAppliedIntermediates = listOf(4, 6))))
        val nextTruth = ManualFanMonitorLiveness.JournalTruth(8, "fallback", TransactionPhase.APPLYING)
        assertEquals("STATE_MISMATCH", liveness.denialReason(second, 12_000, nextTruth, 4))
        // The service may do this only after the first journal and active session are both gone.
        liveness.disarm()
        assertNull(liveness.denialReason(second, 12_000, nextTruth, 4))
        assertNull(liveness.denialReason(second, 57_000, nextTruth, 4))
        assertEquals("STATE_MISMATCH", liveness.denialReason(second, 57_001, nextTruth, 4))
    }

    @Test
    fun `successful repair permits a fresh bounded repair episode`() {
        val liveness = ManualFanMonitorLiveness()
        val identity = activeIdentity()
        val envelope = manualEnvelope()
        liveness.markRepairing(identity, 0)
        liveness.markHealthy(identity, 4_600)
        liveness.markRepairing(identity, 8_000)
        assertEquals(null, liveness.denialReason(envelope, 18_000, truth(TransactionPhase.APPLIED), 6))
        assertEquals("REPAIR_STALE age=10001", liveness.denialReason(envelope, 18_001, truth(TransactionPhase.APPLIED), 6))
    }

    @Test
    fun `L2 -- a repairing window admits the heartbeat until the exact MAX_REPAIR_AGE_MS boundary, then REPAIR_STALE`() {
        val envelope = manualEnvelope()
        val identity = activeIdentity()
        val reasonLiveness = ManualFanMonitorLiveness()
        val allowedLiveness = ManualFanMonitorLiveness()
        reasonLiveness.markRepairing(identity, 0)
        allowedLiveness.markRepairing(identity, 0)

        // Still inside MAX_REPAIR_AGE_MS (10_000) at the 9_900ms mark -- allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 9_900, truth(TransactionPhase.APPLIED), null)
        // Exact boundary: age == MAX_REPAIR_AGE_MS (10_000) -- still allowed.
        assertStep(reasonLiveness, allowedLiveness, envelope, 10_000, truth(TransactionPhase.APPLIED), null)
        // One ms past the boundary: REPAIR_STALE, with the age it computed.
        assertStep(
            reasonLiveness,
            allowedLiveness,
            envelope,
            10_001,
            truth(TransactionPhase.APPLIED),
            "REPAIR_STALE age=10001",
        )
    }

    @Test
    fun `L2 -- F2, a repairing window admits a negative cross-thread skew`() {
        // The
        // window is stamped on the tx thread and read on the heartbeat thread, whose `now` may
        // have been captured a moment BEFORE the mark landed -- MAX_CONCURRENT_TICK_SKEW_MS (250)
        // must admit that ordering race on the negative side, same allowance as HealthyTick.
        val envelope = manualEnvelope()
        val identity = activeIdentity()
        val reasonLiveness = ManualFanMonitorLiveness()
        val allowedLiveness = ManualFanMonitorLiveness()
        reasonLiveness.markRepairing(identity, 1_000)
        allowedLiveness.markRepairing(identity, 1_000)

        // markRepairing at t=1_000, checked at now=900 (ageMs=-100, inside -250..10_000): allowed.
        // liveFanMode=SPORT (Mimir-duty-owned) so the L3 bypass never interferes with this pin.
        assertStep(
            reasonLiveness,
            allowedLiveness,
            envelope,
            900,
            truth(TransactionPhase.APPLIED),
            null,
            liveFanMode = TuningPlanner.FAN_SPORT,
        )
    }

    @Test
    fun `L2 -- a healthy tick replaces a repairing window for the same generation`() {
        val envelope = manualEnvelope()
        val identity = activeIdentity()
        val reasonLiveness = ManualFanMonitorLiveness()
        val allowedLiveness = ManualFanMonitorLiveness()
        reasonLiveness.markRepairing(identity, 0)
        allowedLiveness.markRepairing(identity, 0)

        // Both null (allowed) regardless of whether markRepairing did anything, since nothing has
        // queried the deadman before this point -- kept because the spec text asserts it.
        assertStep(reasonLiveness, allowedLiveness, envelope, 1_000, truth(TransactionPhase.APPLIED), null)
        // Discriminates a no-op markRepairing: a stub leaves state null, so the call above would
        // have CAS-installed an AppliedHandoff at now=1_000 instead of reading a real
        // RepairingWindow's own age from t=0 -- that handoff goes HANDOFF_STALE (age 6_100) by
        // 7_100ms, while a genuine RepairingWindow minted at t=0 is only 7_100ms old, still well
        // inside MAX_REPAIR_AGE_MS (10_000).
        assertStep(reasonLiveness, allowedLiveness, envelope, 7_100, truth(TransactionPhase.APPLIED), null)

        reasonLiveness.markHealthy(identity, 3_000)
        allowedLiveness.markHealthy(identity, 3_000)

        // A HealthyTick for the same generation/transaction replaces the RepairingWindow --
        // allowed at 9_000 (age 6_000, inside MAX_HEALTHY_AGE_MS).
        assertStep(reasonLiveness, allowedLiveness, envelope, 9_000, truth(TransactionPhase.APPLIED), null)
        // Stale past 7s from the healthy mark.
        assertStep(
            reasonLiveness,
            allowedLiveness,
            envelope,
            10_500,
            truth(TransactionPhase.APPLIED),
            "HEALTHY_STALE age=7500",
        )
    }

    @Test
    fun `L2 -- a repairing window for one generation denies the next generation's envelope`() {
        val priorGeneration = manualEnvelope()
        val nextGeneration = priorGeneration.copy(generation = 8, transactionId = "transaction-2")
        val priorIdentity = activeIdentity()
        val nextTruth = ManualFanMonitorLiveness.JournalTruth(
            generation = 8,
            transactionId = "transaction-2",
            phase = TransactionPhase.APPLIED,
        )
        val reasonLiveness = ManualFanMonitorLiveness()
        val allowedLiveness = ManualFanMonitorLiveness()
        reasonLiveness.markRepairing(priorIdentity, 0)
        allowedLiveness.markRepairing(priorIdentity, 0)

        assertStep(reasonLiveness, allowedLiveness, nextGeneration, 1_000, nextTruth, "STATE_MISMATCH")
    }

    // -- Phase B (§3 B3, L3 -- CORRECTED, review F1/F3): only SMART bypasses ------------------

    @Test
    fun `L3 -- SMART is the only live fan mode that bypasses tick liveness`() {
        val envelope = manualEnvelope()
        val identity = activeIdentity()
        val liveness = ManualFanMonitorLiveness()
        liveness.markHealthy(identity, 0)

        // live=4 (TuningPlanner.FAN_SMART): allowed even though this tick is 30s old -- the
        // vendor's own duty-regulation loop only keeps running in SMART (device-measured), so the
        // age check never runs.
        assertEquals(
            null,
            liveness.denialReason(envelope, 30_000, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_SMART),
        )
    }

    @Test
    fun `L3 -- CORRECTED (review F1), SILENT and SPORT stop the vendor loop and fall through to HEALTHY_STALE`() {
        val envelope = manualEnvelope()
        val identity = activeIdentity()

        // live=1 (SILENT): the withdrawn first draft wrongly bypassed this -- device-measured,
        // SILENT STOPS the vendor's duty-regulation loop (duty frozen, speed=0, CPUs at 89.9C),
        // which is exactly the mode Test 2's device proof used. Inverted from "allowed" to the
        // existing age rule.
        val silent = ManualFanMonitorLiveness()
        silent.markHealthy(identity, 0)
        assertEquals(
            "HEALTHY_STALE age=7100",
            silent.denialReason(envelope, 7_100, truth(TransactionPhase.APPLIED), 1),
        )

        // live=5 (TuningPlanner.FAN_SPORT): also stops the vendor loop -- the existing rule
        // applies, a 7_100ms old tick is stale.
        val sport = ManualFanMonitorLiveness()
        sport.markHealthy(identity, 0)
        assertEquals(
            "HEALTHY_STALE age=7100",
            sport.denialReason(envelope, 7_100, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_SPORT),
        )

        // live=6 (TuningPlanner.FAN_MANUAL, Mimir-duty-owned): same.
        val manual = ManualFanMonitorLiveness()
        manual.markHealthy(identity, 0)
        assertEquals(
            "HEALTHY_STALE age=7100",
            manual.denialReason(envelope, 7_100, truth(TransactionPhase.APPLIED), TuningPlanner.FAN_MANUAL),
        )

        // live=null (a failed Settings read): L1 wins on uncertainty -- never bypassed.
        val unknown = ManualFanMonitorLiveness()
        unknown.markHealthy(identity, 0)
        assertEquals(
            "HEALTHY_STALE age=7100",
            unknown.denialReason(envelope, 7_100, truth(TransactionPhase.APPLIED), null),
        )
    }

    @Test
    fun `L3 -- F3, the SMART bypass sits below truth, mismatch, and the APPLYING deadman`() {
        // Review F3: the withdrawn first draft's bypass sat at the TOP of denialReason, above the
        // truth/phase checks -- cancelling NO_TRUTH/TRUTH_MISMATCH/PHASE_x and the 45s APPLYING
        // deadman whenever the live mode was vendor-owned. Fixed by placement: the bypass is now
        // the FIRST line of appliedDenialReason, reached only AFTER those checks.
        val envelope = manualEnvelope()

        // live=4 (SMART) with no journal truth at all: NO_TRUTH still wins.
        assertEquals(
            "NO_TRUTH",
            ManualFanMonitorLiveness().denialReason(envelope, 1_000, null, TuningPlanner.FAN_SMART),
        )

        // live=4 (SMART) with a journal truth for a different transaction: TRUTH_MISMATCH still
        // wins.
        assertEquals(
            "TRUTH_MISMATCH",
            ManualFanMonitorLiveness().denialReason(
                envelope,
                1_000,
                truth(TransactionPhase.APPLIED).copy(transactionId = "other"),
                TuningPlanner.FAN_SMART,
            ),
        )

        // live=4 (SMART) with an ApplyingWindow already 46_001ms old (one ms past the 45s
        // deadman): STATE_MISMATCH still wins -- the bypass never reaches the APPLYING branch.
        val applying = ManualFanMonitorLiveness()
        applying.denialReason(envelope, 1_000, truth(TransactionPhase.APPLYING), TuningPlanner.FAN_SMART)
        assertEquals(
            "STATE_MISMATCH",
            applying.denialReason(envelope, 46_001, truth(TransactionPhase.APPLYING), TuningPlanner.FAN_SMART),
        )
    }

    // -- Source-text pins ---------------------------------------------------------------------

    @Test
    fun `restoreActive's restore-requested log has no DIAGNOSTICS_COMPILED guard`() {
        val fn = sliceBetween(
            mainSourceText("RestoreGuardianProcessService.kt"),
            "private fun restoreActive(",
            "cancelWatch()",
        )
        assertTrue(
            "restoreActive must log the restore cause unconditionally at warn",
            fn.contains("Log.w(TAG, \"Guardian restore requested cause=\$cause\")"),
        )
        assertTrue(
            "the restore-requested log must not be wrapped in a DIAGNOSTICS_COMPILED guard -- " +
                "L5 requires every restore decision to speak unconditionally",
            !stripLineComments(fn).contains("DIAGNOSTICS_COMPILED"),
        )
    }

    @Test
    fun `publishHeartbeat logs heartbeat=denied before calling requestRestore`() {
        // F3 (fan fence, 2026-10-14): the decision is prepared under the authority monitor and
        // the binder call runs with it released, so the two now live in two functions. The log
        // still precedes the side effect it explains: prepareHeartbeat logs and returns the
        // Denied step; publishHeartbeat makes the call from that step, after the prepare returns.
        val source = mainSourceText("RemoteGuardianProcessAuthority.kt")
        val prepare = sliceBetween(
            source,
            "private fun prepareHeartbeat()",
            "val heartbeat = RestoreGuardianHeartbeat(",
        )
        val publish = sliceBetween(
            source,
            "private fun publishHeartbeat()",
            "private fun prepareHeartbeat()",
        )
        val deniedAt = prepare.indexOf("heartbeat=denied action=requestRestore reason=")
        assertTrue("prepareHeartbeat must log heartbeat=denied", deniedAt >= 0)
        assertTrue(
            "the denial must return the Denied step AFTER the log, never the call itself",
            prepare.indexOf("return HeartbeatStep.Denied(") > deniedAt &&
                !prepare.contains("requestRestore()"),
        )
        val prepareAt = publish.indexOf("synchronized(this) { prepareHeartbeat() }")
        val restoreAt = publish.indexOf("step.guardian.requestRestore()")
        assertTrue("publishHeartbeat must call guardian.requestRestore() from the Denied step", restoreAt >= 0)
        assertTrue("the prepare (and its log) must run before the restore request", prepareAt in 0 until restoreAt)
        // P1-1 (fence review): the retirement runs under the monitor FIRST (GuardianDenialSequence,
        // pinned in GuardianHandbackTest); the binder call is the second step, outside it.
        val requestStep = publish.indexOf("requestRestore = {", prepareAt)
        assertTrue("the restore request is the sequence's second step", requestStep in 0 until restoreAt)
        assertTrue(
            "the restore request must run with the authority monitor released",
            !publish.substring(requestStep, restoreAt).contains("synchronized(this)"),
        )
        assertTrue(
            "the denied log must not be gated behind DIAGNOSTICS_COMPILED",
            !stripLineComments(prepare.substring(0, deniedAt)).contains("DIAGNOSTICS_COMPILED"),
        )
    }

    @Test
    fun `the three reassert lines exist at their named sites in MimirTweakerService`() {
        val source = mainSourceText("MimirTweakerService.kt")

        val dispatchSite = sliceBetween(
            source,
            "(observedDecision as? SupervisorDecision.RepairFanTarget)?.let { dispatching ->",
            "val outcome = supervisor.execute(",
        )
        assertTrue(
            "the RepairFanTarget dispatch must log before supervisor.execute runs the decision",
            dispatchSite.contains("\"reassert dispatch generation=") &&
                dispatchSite.contains("liveFanMode="),
        )

        val prepareSite = sliceBetween(
            source,
            "private fun prepareManualFanReentryForReassert(",
            "if (contract.fanMode != TuningPlanner.FAN_MANUAL) return null",
        )
        assertTrue(
            "prepareManualFanReentryForReassert must log at its own start, before the FAN_MANUAL " +
                "short-circuit",
            prepareSite.contains("Log.i(FAN_CONTROL_TAG, \"reassert stage=prepare generation=\${active.generation}\")"),
        )

        val executeSite = sliceBetween(
            source,
            "prepareManualFanReentryForReassert(active, contract)?.let {",
            "transaction.reassertAppliedFanBound(generation, transactionId) {",
        )
        assertTrue(
            "the fanReasserter must log immediately before calling reassertAppliedFanBound",
            executeSite.contains("Log.i(FAN_CONTROL_TAG, \"reassert stage=execute generation=\$generation\")"),
        )
    }

    @Test
    fun `Phase B -- markRepairing, markHealthy on FAN_REASSERTED, and liveFanMode are wired at their named sites`() {
        val source = mainSourceText("MimirTweakerService.kt")

        val dispatchSite = sliceBetween(
            source,
            "(observedDecision as? SupervisorDecision.RepairFanTarget)?.let { dispatching ->",
            "val outcome = supervisor.execute(",
        )
        val dispatchLogAt = dispatchSite.indexOf("\"reassert dispatch generation=")
        val markRepairingAt = dispatchSite.indexOf("markRepairing(")
        assertTrue("the dispatch site must call markRepairing", markRepairingAt >= 0)
        assertTrue(
            "markRepairing must run AFTER the reassert dispatch log and BEFORE supervisor.execute",
            dispatchLogAt in 0 until markRepairingAt,
        )

        val attemptSite = sliceBetween(
            source,
            "(observedDecision as? SupervisorDecision.RepairFanTarget)?.let { decision ->",
            "monitorTickSettlement.settle(result)",
        )
        assertTrue(
            "the reassert result callback must call markHealthy on FAN_REASSERTED",
            attemptSite.contains("attempt.code == \"FAN_REASSERTED\"") &&
                attemptSite.contains("manualFanMonitorLiveness.markHealthy("),
        )

        val publishHeartbeatFn = sliceBetween(
            mainSourceText("RemoteGuardianProcessAuthority.kt"),
            "private fun publishHeartbeat()",
            "val heartbeat = RestoreGuardianHeartbeat(",
        )
        val liveFanModeSampleCalls = Regex(Regex.escape("liveFanMode()")).findAll(publishHeartbeatFn).count()
        assertEquals(
            "publishHeartbeat must sample liveFanMode() exactly once per heartbeat",
            1,
            liveFanModeSampleCalls,
        )
        assertTrue(
            "the single sampled liveFanMode must be threaded into heartbeatDenialReason",
            publishHeartbeatFn.contains("heartbeatDenialReason(envelope, liveFanMode)"),
        )
    }

    // A fan re-entry (drift
    // ticks never reach tick(); the repair takes ~4.6 s) left a >6 s gap for the first tick()
    // after FAN_REASSERTED, so SmartFanThermalControl's sticky FAN_CONTROLLER_STALLED FailSafe
    // latched forever and the controller never marked healthy again (device evidence: Silent
    // write acceptance run, 809e7ff0). A re-entry is a fresh start for the duty controller, so
    // the FAN_REASSERTED branch must call fanController.reset() before markHealthy(...).
    @Test
    fun `B6 -- FAN_REASSERTED resets the duty controller before marking the session healthy`() {
        val source = stripLineComments(mainSourceText("MimirTweakerService.kt"))

        val attemptSite = sliceBetween(
            source,
            "(observedDecision as? SupervisorDecision.RepairFanTarget)?.let { decision ->",
            "monitorTickSettlement.settle(result)",
        )
        val reassertedAt = attemptSite.indexOf("attempt.code == \"FAN_REASSERTED\"")
        assertTrue("the FAN_REASSERTED branch must exist", reassertedAt >= 0)
        val branchSite = attemptSite.substring(reassertedAt)

        val resetAt = branchSite.indexOf("fanController.reset()")
        val markHealthyAt = branchSite.indexOf("manualFanMonitorLiveness.markHealthy(")
        assertTrue(
            "the FAN_REASSERTED branch must call fanController.reset() -- a re-entry is a fresh " +
                "start for the duty controller; without it the stale tick clock stalls the " +
                "controller for good",
            resetAt >= 0,
        )
        assertTrue("the FAN_REASSERTED branch must call markHealthy", markHealthyAt >= 0)
        assertTrue(
            "fanController.reset() must run BEFORE markHealthy(...), so the controller is never " +
                "marked healthy while its own tick clock is still stale",
            resetAt < markHealthyAt,
        )
    }

    // -- Helpers --------------------------------------------------------------------------------

    private fun assertStep(
        envelope: RestoreGuardianEnvelope,
        now: Long,
        truth: ManualFanMonitorLiveness.JournalTruth?,
        expectedReason: String?,
        liveFanMode: Int? = null,
    ) = assertStep(
        ManualFanMonitorLiveness(),
        ManualFanMonitorLiveness(),
        envelope,
        now,
        truth,
        expectedReason,
        liveFanMode,
    )

    // Phase B (§3 B3): every existing call above supplies no `liveFanMode` and defaults to null,
    // which falls through to the SAME truth table this file already pins (L1 wins on
    // uncertainty); supplying liveFanMode = 6 or null must preserve every existing row.
    private fun assertStep(
        reasonLiveness: ManualFanMonitorLiveness,
        allowedLiveness: ManualFanMonitorLiveness,
        envelope: RestoreGuardianEnvelope,
        now: Long,
        truth: ManualFanMonitorLiveness.JournalTruth?,
        expectedReason: String?,
        liveFanMode: Int? = null,
    ) {
        val reason = reasonLiveness.denialReason(envelope, now, truth, liveFanMode)
        val allowed = allowedLiveness.allowsHeartbeat(envelope, now, truth, liveFanMode)
        assertEquals("denialReason at now=$now", expectedReason, reason)
        assertEquals(
            "allowsHeartbeat(...) must equal (denialReason(...) == null) at now=$now",
            expectedReason == null,
            allowed,
        )
    }

    private fun truth(phase: TransactionPhase) = ManualFanMonitorLiveness.JournalTruth(
        generation = 7,
        transactionId = "transaction",
        phase = phase,
    )

    private fun activeIdentity() = ActiveSessionIdentity(
        generation = 7,
        transactionId = "transaction",
        requestId = "request",
        ownerUid = 1,
        targetPackage = "example.game",
        activationDeadlineMs = 10_000,
        leaseDeadlineMs = 20_000,
        targetEverForeground = false,
    )

    private fun manualEnvelope() = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper",
        tweakerCertificateSha256 = "a".repeat(64),
        hardwareFingerprint = "hardware",
        deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
        bootIdentity = "boot",
        generation = 7,
        requestId = "request",
        transactionId = "transaction",
        taskNonce = "b".repeat(32),
        topologySha256 = "c".repeat(64),
        snapshotSha256 = "d".repeat(64),
        planSha256 = "e".repeat(64),
        hardDeadlineElapsedRealtimeMs = 30_000,
        restoreEntries = listOf(
            RestoreGuardianFanPutEntry(
                applyOrdinal = 0,
                expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                rendererAppliedIntermediates = listOf(TuningPlanner.FAN_MANUAL),
            ),
        ),
    )

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }

    private fun sliceBetween(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = text.indexOf(endMarker, start)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }

    private fun stripLineComments(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
}
