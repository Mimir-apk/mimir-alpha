package com.gruning.mimir.tweaker

import java.util.concurrent.atomic.AtomicReference

/**
 * Fan-fence fix F2 (reference Odin, 2026-10-13/14, `build/test-captures/fanfence/ROOT_CAUSE.md`): the guardian's
 * hand-back must reach the supervisor.
 *
 * Three times on the reference device the Guardian restored Stock -- twice because the host's own heartbeat
 * gate asked it to (F1's false `HEALTHY_STALE`), once because the watchdog expired across a suspend
 * (F3) -- and every time the host told nobody. `publishHeartbeat` fired `requestRestore()` and
 * stopped its heartbeat; `HardwareSessionSupervisor` kept the session; the next 1 Hz tick read the
 * Guardian's own Stock fan write as `TARGET_CONTRACT_DRIFT_FAN` and dispatched a `RepairFanTarget`
 * INTO a generation the Guardian had already terminalized. The reassert then blocked 23 s behind
 * the Guardian's restore on the fence lock, came back `Guardian terminal fence closed`, and the
 * session only died when the recovery pass stumbled on the receipt.
 *
 * Two small, pure pieces, both Android-free so the JVM tests can drive them:
 *
 *  - [GuardianHeartbeatRetirePolicy] decides, from the Guardian's binder answer, whether the host
 *    session is over. A protocol rejection (the Guardian answered `success=false`, which is
 *    `restoreActive` having run) retires; a received terminal receipt names the cause. A
 *    **transport** failure (no answer at all) never reaches the policy: the authority keeps its
 *    heartbeat running and tries again next slot (P2-4), and if the Guardian is truly gone its
 *    watchdog's receipt retires the session through [GuardianHandbackGate] on the next tick.
 *  - [GuardianDenialSequence] fixes the order of a host-side denial (P1-1): the session is retired
 *    BEFORE the synchronous `requestRestore()` binder call, never after it returns.
 *  - [GuardianHandbackGate] carries that verdict, and the host's own denial, from the heartbeat
 *    thread to the monitor tick, where session state is owned. The tick consumes it BEFORE any
 *    decision runs: a pending hand-back turns whatever the tick decided into a
 *    [SupervisorDecision.Restore] for the active session, so `supervisor.execute` runs the ordinary
 *    restore settlement (the same `restoreBound` that reconciled gen 51 at 23:19:14.750) and never a
 *    repair. A terminal receipt already on disk does the same, so a Guardian that restored on its
 *    own (gen 37's watchdog) is also settled on the very next tick rather than after a drift.
 */
internal sealed interface GuardianHeartbeatAnswer {
    /** The Guardian accepted the heartbeat: `success=true`. */
    data object Accepted : GuardianHeartbeatAnswer

    /** The Guardian answered, and refused: `success=false` with its own [code]. */
    data class Rejected(val code: String?) : GuardianHeartbeatAnswer
}

/**
 * P1-1 (fence review, 2026-10-14): the order of a host-side heartbeat denial. `requestRestore()` is
 * synchronous on the Guardian's one worker and returns only after the restore has run -- 24 s on
 * gen 51, up to 60 s -- so a session retired AFTER the call left the next 1 Hz tick free to dispatch
 * `RepairFanTarget` into the dead generation. [run] retires first ([retireUnderMonitor]: heartbeat
 * stopped, hand-back gate marked, under the authority monitor) and asks the Guardian second. A
 * retirement that finds the session already gone requests nothing: the Guardian's restore is
 * arm-scoped and must never restore a successor session.
 */
internal object GuardianDenialSequence {
    fun run(retireUnderMonitor: () -> Boolean, requestRestore: () -> Unit) {
        if (!retireUnderMonitor()) return
        requestRestore()
    }
}

internal object GuardianHeartbeatRetirePolicy {
    /** The host's own gate denied the heartbeat and asked the Guardian to restore. */
    const val RESTORE_REQUESTED = "GUARDIAN_RESTORE_REQUESTED"

    /** The Guardian's signed terminal receipt is on disk: Stock is restored and verified. */
    const val TERMINAL_RECEIVED = "GUARDIAN_TERMINAL_RECEIVED"

    /** The Guardian answered a heartbeat with a refusal: it is restoring, or already has. */
    const val HEARTBEAT_REJECTED = "GUARDIAN_HEARTBEAT_REJECTED"

    /** A repair/derate/reassert found the Guardian's terminal fence closed for this generation. */
    const val TERMINAL_FENCE = "GUARDIAN_TERMINAL_FENCE"

    /** Every reason this file mints shares the prefix; the companion bundle classifies on it. */
    private const val REASON_PREFIX = "GUARDIAN_"

    /**
     * The restore reason recorded for a host-side denial: the family token plus the gate's own
     * reason token (`HEALTHY_STALE`, `TRUTH_MISMATCH`, ...), never the ` age=...` tail -- the
     * reason reaches the companion bundle, which wants a bounded vocabulary, and the age is already
     * in the `heartbeat=denied` log line beside it.
     */
    fun denialCause(denialReason: String): String = "$RESTORE_REQUESTED/${denialReason.substringBefore(' ')}"

    /**
     * Null means the session stays. [terminalReceived] is consulted lazily and only when the
     * answer was not an acceptance, so an accepted heartbeat costs no receipt read.
     */
    fun retireCause(answer: GuardianHeartbeatAnswer, terminalReceived: () -> Boolean): String? = when (answer) {
        GuardianHeartbeatAnswer.Accepted -> null
        is GuardianHeartbeatAnswer.Rejected -> if (terminalReceived()) {
            TERMINAL_RECEIVED
        } else {
            "$HEARTBEAT_REJECTED/${answer.code ?: "null"}"
        }
    }

    /** True for every reason this policy mints: the Guardian handed the hardware back to Stock. */
    fun isGuardianHandbackReason(reason: String): Boolean = reason.startsWith(REASON_PREFIX)
}

internal class GuardianHandbackGate {
    internal data class PendingRestore(
        val generation: Long,
        val transactionId: String,
        val cause: String,
    )

    private val pending = AtomicReference<PendingRestore?>(null)

    /** Heartbeat-thread side: the session named here is over; the next tick must settle it. */
    fun markRestoreRequested(generation: Long, transactionId: String, cause: String) {
        pending.set(PendingRestore(generation, transactionId, cause))
    }

    fun pending(): PendingRestore? = pending.get()

    /** A new authenticated Guardian envelope is a new epoch; nothing pending may outlive the old one. */
    fun clear() {
        pending.set(null)
    }

    /**
     * Tick side, called with the supervisor's own decision for this tick BEFORE anything executes.
     *
     * Returns [decision] unchanged when there is nothing to hand back. Otherwise a
     * [SupervisorDecision.Restore] for the ACTIVE session: the pending hand-back, when it names this
     * session (a pending entry for a session that is already gone is dropped, never applied to its
     * successor); or [GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED] when [terminalReceived] says
     * the Guardian's receipt is on disk -- a receipt can only belong to the active session, because
     * the Guardian refuses to arm while one is retained (`CODE_TERMINAL_TRUTH_RETAINED`). A decision
     * that is already a Restore is kept as it is; the pending entry for that session is spent.
     *
     * [holdOrBackoff] (P2-3, fence review 2026-10-14): while the supervisor sits in a cross-store or
     * corrupt hold, or in its bounded retry backoff, its own `tick()` answers `Continue` with the
     * session still set -- and a receipt is the NORMAL state of a cross-store hold. Minting a
     * Restore from the receipt there ran `restoreBound` at 1 Hz through the hold and relabelled a
     * benign settlement as a hand-back. The receipt branch is skipped in those states; the hold is
     * settled by `ACTION_GUARDIAN_SETTLEMENT` as designed. A pending hand-back is still consumed.
     */
    fun override(
        active: ActiveSessionIdentity?,
        decision: SupervisorDecision,
        holdOrBackoff: Boolean = false,
        terminalReceived: () -> Boolean,
    ): SupervisorDecision {
        val requested = pending.get()
        if (active == null) {
            if (requested != null) pending.compareAndSet(requested, null)
            return decision
        }
        val requestedForActive = requested != null &&
            requested.generation == active.generation &&
            requested.transactionId == active.transactionId
        if (requested != null) {
            // Spent whether it matched (consumed below) or not (stale, belongs to a dead session).
            pending.compareAndSet(requested, null)
        }
        if (decision is SupervisorDecision.Restore) return decision
        if (requestedForActive) {
            return SupervisorDecision.Restore(
                generation = active.generation,
                transactionId = active.transactionId,
                reason = requireNotNull(requested).cause,
            )
        }
        if (holdOrBackoff) return decision
        return if (runCatching(terminalReceived).getOrDefault(false)) {
            SupervisorDecision.Restore(
                generation = active.generation,
                transactionId = active.transactionId,
                reason = GuardianHeartbeatRetirePolicy.TERMINAL_RECEIVED,
            )
        } else {
            decision
        }
    }
}
