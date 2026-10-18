package com.gruning.mimir.tweaker

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-thread deadman between the serialized thermal loop and Guardian heartbeat.
 * Missing, stale, or wrong-session control truth suppresses the next heartbeat so the
 * independent Guardian process restores the authenticated Stock envelope.
 *
 * **Backstop, not primary, since the 2026-08-22 fan-architecture ruling (R2/R3).** [ownsManualFan]
 * already keys off [TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES] (SPORT/MANUAL), which this ruling
 * does not touch, so this deadman continues to guard exactly the sessions where Mimir has actually
 * stopped the vendor loop and taken duty -- this wave, that remains the Odin's SPORT contract
 * only. A vendor-SMART contract was already, correctly, a no-op here before this ruling
 * ([ownsManualFan] returns false and [allowsHeartbeat] admits unconditionally), and stays one now
 * that SMART is the default everywhere else: there is no stopped loop to guard on a device Mimir
 * never took ownership from. Kept wired, unchanged.
 *
 * **Fan-fence fix F1 (reference Odin, 2026-10-13/14, `build/test-captures/fanfence/ROOT_CAUSE.md`).** The
 * heartbeat used to capture its clock BEFORE a Settings read and a journal load, then compare that
 * stale sample against a tick the controller stamped DURING those reads. Any stall over
 * [MAX_CONCURRENT_TICK_SKEW_MS] in the reads (the journal lock is shared with the tick's writes)
 * produced `HEALTHY_STALE age=-779` -- a negative age, i.e. a controller that was provably alive --
 * and the guardian was asked to restore a healthy session. Twice on the reference device, plus one more time
 * for a cousin defect. So the production gate ([denialReason] without a `now`) owns its own clock
 * ([uptimeClock]) and samples it at the comparison itself, right after the state read: a tick that
 * lands while the caller's reads run is then older than the sample, never younger, and the only
 * negative age left is the sub-millisecond CAS race the skew tolerance was written for.
 */
internal class ManualFanMonitorLiveness(
    /**
     * CPU-awake clock, the same domain every `markHealthy`/`markRepairing` stamp uses
     * (`SystemClock.uptimeMillis()` at each call site in `MimirTweakerService`). Injected for the
     * JVM tests; the default is the real clock. Sampled only by the no-`now` [denialReason].
     */
    private val uptimeClock: () -> Long = SystemClock::uptimeMillis,
) {
    internal data class JournalTruth(
        val generation: Long,
        val transactionId: String,
        val phase: TransactionPhase,
    )

    private sealed interface State {
        val generation: Long
        val transactionId: String
        val elapsedRealtimeMs: Long
    }

    private data class ApplyingWindow(
        override val generation: Long,
        override val transactionId: String,
        override val elapsedRealtimeMs: Long,
    ) : State

    private data class AppliedHandoff(
        override val generation: Long,
        override val transactionId: String,
        override val elapsedRealtimeMs: Long,
    ) : State

    private data class HealthyTick(
        override val generation: Long,
        override val transactionId: String,
        override val elapsedRealtimeMs: Long,
    ) : State

    /**
     * A fan repair the
     * daemon is actively running (tick decision `RepairFanTarget` dispatched, through the
     * reassert's completion) counts as controller liveness for a bounded window
     * ([MAX_REPAIR_AGE_MS]) -- never longer, so a stalled repair still lets L1's ordinary
     * `HEALTHY_STALE` path request the Guardian restore once the window itself goes stale. A
     * `HealthyTick` for the same generation/transaction (the repair actually landing) replaces
     * this via [markHealthy]'s own unconditional matching-generation CAS, unchanged below.
     */
    private data class RepairingWindow(
        override val generation: Long,
        override val transactionId: String,
        override val elapsedRealtimeMs: Long,
        val dispatches: Int = 1,
    ) : State

    private val state = AtomicReference<State?>(null)

    fun markHealthy(identity: ActiveSessionIdentity, nowElapsedRealtimeMs: Long) {
        while (true) {
            val current = state.get()
            if (current != null && !current.matches(identity.generation, identity.transactionId)) return
            val healthy = HealthyTick(identity.generation, identity.transactionId, nowElapsedRealtimeMs)
            if (state.compareAndSet(current, healthy)) return
        }
    }

    /**
     * Phase B (§3 B1): same generation-match discipline as [markHealthy] above -- never overwrites
     * a DIFFERENT generation/transaction's state. Each of at most two dispatches gets a full
     * ten-second window. A retry cannot revive an expired window; only healthy progress starts
     * another episode. Thus a legitimate retry can finish, but repeated failures cannot renew
     * their own authority indefinitely (the entire episode is at most twenty seconds).
     */
    fun markRepairing(identity: ActiveSessionIdentity, nowElapsedRealtimeMs: Long) {
        while (true) {
            val current = state.get()
            if (current != null && !current.matches(identity.generation, identity.transactionId)) return
            if (current is RepairingWindow && (current.dispatches >= MAX_REPAIR_DISPATCHES ||
                    nowElapsedRealtimeMs - current.elapsedRealtimeMs !in 0..MAX_REPAIR_AGE_MS)
            ) return
            val repairing = RepairingWindow(identity.generation, identity.transactionId, nowElapsedRealtimeMs,
                dispatches = (current as? RepairingWindow)?.dispatches?.plus(1) ?: 1)
            if (state.compareAndSet(current, repairing)) return
        }
    }

    fun disarm() {
        state.set(null)
    }

    /** LAB-only caller aid; exposes no transaction token or mutable state. */
    fun diagnosticSummary(
        envelope: RestoreGuardianEnvelope,
        nowElapsedRealtimeMs: Long,
        truth: JournalTruth?,
    ): String {
        val current = state.get()
        return "truth=${truth?.phase} " +
            "truthMatches=${truth?.let { it.generation == envelope.generation && it.transactionId == envelope.transactionId } == true} " +
            "state=${current?.javaClass?.simpleName ?: "NONE"} " +
            "stateMatches=${current?.matches(envelope.generation, envelope.transactionId) == true} " +
            "stateAgeMs=${current?.let { nowElapsedRealtimeMs - it.elapsedRealtimeMs }}"
    }

    fun allowsHeartbeat(
        envelope: RestoreGuardianEnvelope,
        nowElapsedRealtimeMs: Long,
        truth: JournalTruth?,
        liveFanMode: Int?,
    ): Boolean = denialReason(envelope, nowElapsedRealtimeMs, truth, liveFanMode) == null

    /**
     * The primary
     * implementation now -- [allowsHeartbeat] is a thin wrapper (`denialReason(...) == null`) over
     * the SAME truth table and the SAME CAS state transitions it always performed. Returns null
     * when the heartbeat is allowed, otherwise one of: `NO_TRUTH`, `TRUTH_MISMATCH`,
     * `PHASE_<phase>` (an unhandled journal phase), `HEALTHY_STALE age=<ms>`,
     * `HANDOFF_STALE age=<ms>`, `REPAIR_STALE age=<ms>`, `STATE_MISMATCH` (the deadman's own state
     * doesn't match this envelope's generation/transaction, or -- APPLYING only -- matches but is
     * neither an in-window [ApplyingWindow] nor a fresh CAS: no separate token is spent on that
     * narrower case since the closed reason vocabulary in the spec has no `APPLYING_STALE` entry).
     *
     * Phase B (§3 B3, L3 -- CORRECTED 17:07Z, review F1/F3): [liveFanMode] is the live vendor
     * `fan_mode` sampled at THIS heartbeat -- deliberately last, deliberately no default (a missed
     * call site must not silently compile into "always null"). The vendor's own duty-regulation
     * loop is a 4000 ms runnable that only keeps re-posting while `fan_mode == SMART`
     * (`TweakerVendorFanModes.java`'s device-measured note); SILENT and SPORT both **stop** that
     * loop (`AndroidTransactionPorts.FAN_MODES_STOPPING_VENDOR_REGULATION_LOOP = {SILENT, SPORT}`),
     * and MANUAL stops it too. So only `liveFanMode == TuningPlanner.FAN_SMART` bypasses the
     * tick-liveness checks -- and only as the FIRST line of [appliedDenialReason], i.e. AFTER the
     * truth-null / truth-mismatch / phase-dispatch checks above and the 45 s APPLYING deadman,
     * never before them, so a wedged apply or a stale/mismatched truth is never silently cancelled
     * by a vendor-owned live mode. Fan modes 0/2/3/7 are unevidenced for loop behaviour and are NOT
     * bypassed here -- like SILENT, SPORT, MANUAL, and a null read (failed Settings sample), they
     * fall through to the existing age-based rules; L1 wins on uncertainty, exactly as before this
     * phase. The withdrawn first draft keyed this off [TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES]
     * instead and sat above the truth/phase checks at the top of [denialReason] -- false for
     * SILENT (F1, the exact mode Test 2's device proof used) and unsafe for a wedged apply (F3);
     * both are fixed by this correction.
     */
    fun denialReason(
        envelope: RestoreGuardianEnvelope,
        nowElapsedRealtimeMs: Long,
        truth: JournalTruth?,
        liveFanMode: Int?,
    ): String? = denialReason(envelope, { nowElapsedRealtimeMs }, truth, liveFanMode)

    /**
     * F1: the production gate. No timestamp comes in; [uptimeClock] is sampled inside the comparison,
     * after the caller has finished every read it does before asking (the live `fan_mode` Settings
     * read in `RemoteGuardianProcessAuthority.publishHeartbeat`, the `journal.load()` in the
     * service's gate lambda). The explicit-`now` overload above stays for the pure timeline tests and
     * keeps the historical parameter name.
     */
    fun denialReason(
        envelope: RestoreGuardianEnvelope,
        truth: JournalTruth?,
        liveFanMode: Int?,
    ): String? = denialReason(envelope, uptimeClock, truth, liveFanMode)

    private fun denialReason(
        envelope: RestoreGuardianEnvelope,
        clock: () -> Long,
        truth: JournalTruth?,
        liveFanMode: Int?,
    ): String? {
        if (!envelope.ownsManualFan()) return null
        if (truth == null) return "NO_TRUTH"
        if (truth.generation != envelope.generation || truth.transactionId != envelope.transactionId) {
            return "TRUTH_MISMATCH"
        }
        return when (truth.phase) {
            TransactionPhase.APPLYING -> applyingDenialReason(envelope, clock)
            TransactionPhase.APPLIED -> appliedDenialReason(envelope, clock, liveFanMode)
            else -> "PHASE_${truth.phase}"
        }
    }

    private fun applyingDenialReason(envelope: RestoreGuardianEnvelope, clock: () -> Long): String? {
        while (true) {
            val current = state.get()
            // F1: the clock is read AFTER the state, at the comparison -- see the class kdoc.
            val now = clock()
            if (current == null) {
                if (state.compareAndSet(
                        null,
                        ApplyingWindow(envelope.generation, envelope.transactionId, now),
                    )
                ) return null
                continue
            }
            if (!current.matches(envelope.generation, envelope.transactionId)) return "STATE_MISMATCH"
            return if (current is ApplyingWindow && now - current.elapsedRealtimeMs in 0..MAX_APPLYING_AGE_MS) {
                null
            } else {
                "STATE_MISMATCH"
            }
        }
    }

    private fun appliedDenialReason(
        envelope: RestoreGuardianEnvelope,
        clock: () -> Long,
        liveFanMode: Int?,
    ): String? {
        if (liveFanMode == TuningPlanner.FAN_SMART) return null
        while (true) {
            val current = state.get()
            // F1: the clock is read AFTER the state, at the comparison -- a HealthyTick stamped
            // before this read is at most microseconds younger than `now`, never 779 ms older.
            val now = clock()
            if (current is HealthyTick) {
                if (!current.matches(envelope.generation, envelope.transactionId)) return "STATE_MISMATCH"
                val ageMs = now - current.elapsedRealtimeMs
                return if (ageMs in -MAX_CONCURRENT_TICK_SKEW_MS..MAX_HEALTHY_AGE_MS) {
                    null
                } else {
                    "HEALTHY_STALE age=$ageMs"
                }
            }
            if (current is RepairingWindow) {
                if (!current.matches(envelope.generation, envelope.transactionId)) return "STATE_MISMATCH"
                val ageMs = now - current.elapsedRealtimeMs
                // B1 amendment (review F2): the SAME negative-skew allowance as HealthyTick above.
                // The window is stamped on the tx thread and read on the heartbeat thread, whose
                // `now` was captured before a Settings read and a journal load -- a negative age
                // is a repair that started milliseconds ago, not a state to deny.
                return if (ageMs in -MAX_CONCURRENT_TICK_SKEW_MS..MAX_REPAIR_AGE_MS) {
                    null
                } else {
                    "REPAIR_STALE age=$ageMs"
                }
            }
            if (current != null && !current.matches(envelope.generation, envelope.transactionId)) {
                return "STATE_MISMATCH"
            }
            if (current is AppliedHandoff) {
                val ageMs = now - current.elapsedRealtimeMs
                return if (ageMs in 0..MAX_APPLIED_HANDOFF_AGE_MS) null else "HANDOFF_STALE age=$ageMs"
            }
            val handoff = AppliedHandoff(envelope.generation, envelope.transactionId, now)
            if (state.compareAndSet(current, handoff)) return null
        }
    }

    private fun State.matches(generation: Long, transactionId: String): Boolean =
        this.generation == generation && this.transactionId == transactionId

    // Same TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES set as RestoreMutationOrder and
    // RestoreGuardianEnvelope.requireRestoreOrder (S4.3 / OWED-1, device-measured): SPORT and
    // MANUAL both stop the vendor duty loop and are equally deadman-guarded here. Under the old
    // MANUAL-only check a SPORT-owned session was never recognised as fan-owned, so this deadman
    // silently allowed every heartbeat regardless of controller health -- defanging the guard.
    private fun RestoreGuardianEnvelope.ownsManualFan(): Boolean = restoreEntries.any { entry ->
        entry.mayOwnManualFan()
    }

    private companion object {
        const val MAX_APPLYING_AGE_MS = 45_000L
        // Includes the measured initial thermal sample plus one checked Odin PWM write.
        const val MAX_APPLIED_HANDOFF_AGE_MS = 6_000L
        // One second beyond the controller's own 6 s fail-closed cadence boundary.
        const val MAX_HEALTHY_AGE_MS = 7_000L
        // Negative-age tolerance for the explicit-`now` callers (tests, diagnostics). The
        // production gate samples its clock after the state read (F1), so it cannot compute a
        // negative age at all; the budget stays for the one overload that still can.
        const val MAX_CONCURRENT_TICK_SKEW_MS = 250L
        // Phase B (§3 B1): hand-to-Smart dwell (3.0 s) + vendor reaction (0.3 s) + entry settle
        // (0.3 s) + telemetry + one 1 s tick, with margin -- device-measured against §0's own
        // proof timeline. Never longer than the Guardian's own 30 s stale threshold minus one
        // heartbeat interval (HEARTBEAT_INTERVAL_MS = 5_000 in RemoteGuardianProcessAuthority).
        const val MAX_REPAIR_AGE_MS = 10_000L
        const val MAX_REPAIR_DISPATCHES = 2
    }
}
