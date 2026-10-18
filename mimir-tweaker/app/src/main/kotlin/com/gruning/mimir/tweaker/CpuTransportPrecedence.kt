package com.gruning.mimir.tweaker

/** An apply refused before it wrote anything, with the code and the sentence a client is told. */
internal data class CpuTransportRefusal(val code: String, val message: String)

/**
 * The verdict on the privileged CPU route that precedes every apply: has an orphaned hold on the shared QoS node been
 * given back, or can it not be?
 *
 * **Fails closed.** This check used to answer "proceed" whenever it could not run at all (the probe threw, the
 * reclaim threw, the transaction worker did not answer): the apply then went on to select its CPU transport from a
 * node whose state nobody had read. The privileged route the apply would write through is exactly the thing that was
 * not confirmed, so now a check that cannot complete refuses the apply, nothing is written and the device stays as it
 * was, and the reason is logged. The only way through is an [evaluate] that returns an outcome other than
 * [QosReclaimOutcome.Unreclaimable] and [QosReclaimOutcome.NodeUnreadable].
 *
 * **"Absent" is a positive answer.** A protected-node read that timed out or failed used to reach this check as a null,
 * be taken for [QosReclaimOutcome.NodeAbsent], and let the apply carry on toward `scaling_max_freq` with the shared QoS
 * hold unconfirmed. Absent now means the existence probe said "no such file"; an unreadable, timed-out or failed read
 * of a node that is (or may be) there is [QosReclaimOutcome.NodeUnreadable] and is refused with [UNCONFIRMED_CODE]. A
 * chassis that genuinely has no such node is unchanged and applies as before.
 */
internal object CpuTransportPrecedence {
    const val UNRECLAIMABLE_CODE = "CPU_QOS_NODE_UNRECLAIMABLE"
    const val UNCONFIRMED_CODE = "CPU_TRANSPORT_UNCONFIRMED"

    /**
     * Folds the transport decision into the reclaim outcome: a decision that says the node is unreadable
     * (`NODE_UNREADABLE`, or `NODE_MISSING_OR_UNREADABLE` when presence could not be established) is the same unconfirmed
     * route as an unreadable reclaim read, so it becomes [QosReclaimOutcome.NodeUnreadable] and the apply is refused.
     * `NODE_MISSING` (a positive absence) and every other reason leave [outcome] as it was.
     */
    fun confirm(outcome: QosReclaimOutcome, decision: CpuTransportDecision): QosReclaimOutcome = when {
        outcome is QosReclaimOutcome.NodeUnreadable -> outcome
        decision.nodeUnreadable -> QosReclaimOutcome.NodeUnreadable("transport decision ${decision.reason}")
        else -> outcome
    }

    fun refusal(evaluate: () -> QosReclaimOutcome, log: (String) -> Unit): CpuTransportRefusal? {
        val outcome = try {
            evaluate()
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val code = transactionWorkerFailureCode(error, fallback = UNCONFIRMED_CODE)
            log(
                "CPU_TRANSPORT apply refused: the privileged route could not be confirmed " +
                    "(${error.javaClass.simpleName}: ${error.message}); nothing was written",
            )
            return CpuTransportRefusal(
                code = code,
                message = "The privileged CPU route could not be confirmed (${error.javaClass.simpleName}); nothing was written.",
            )
        }
        if (outcome is QosReclaimOutcome.NodeUnreadable) {
            log("CPU_TRANSPORT apply refused: the shared QoS node could not be read (${outcome.reason}); nothing was written")
            return CpuTransportRefusal(
                code = UNCONFIRMED_CODE,
                message = "The shared CPU QoS node could not be read, so the privileged CPU route is unconfirmed; nothing was written.",
            )
        }
        if (outcome is QosReclaimOutcome.Unreclaimable) {
            log("CPU_TRANSPORT apply refused: the shared QoS node is held by no session and could not be reclaimed (${outcome.reason})")
            return CpuTransportRefusal(
                code = UNRECLAIMABLE_CODE,
                message = "The shared CPU QoS node is held by no session and could not be reclaimed.",
            )
        }
        return null
    }
}
