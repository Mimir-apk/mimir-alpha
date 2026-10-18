package com.gruning.mimir.tweaker

/**
 * Where a FAILED public V4 apply is settled.
 *
 * Settling a failed apply loads the global journal (a file read plus authentication) and may enter RECOVERY, which takes
 * the supervisor's monitor. That used to run on the Binder thread that had just been told the apply failed, so a stuck
 * supervisor write or a slow journal held a Binder thread, and an unrelated journal could be read as RECOVERY from the
 * wrong thread. It now runs on the transaction worker, where the supervisor and the journal are already mutated:
 *
 * - The Binder thread waits for it, bounded by the same wait every Binder call has, so the answer's truth still
 *   describes the settled state.
 * - If that wait lapses before the worker STARTED the settlement, the worker would have withdrawn it and nothing would
 *   ever settle (the owner and the started-service lifetime would stay held), so it is queued instead and runs, once,
 *   behind whatever the worker is doing.
 * - If it lapses after the settlement started, the worker is running it and will finish it: nothing more to do.
 *
 * **A settlement that throws is contained, wherever it runs.** It is logged once with its class, never rethrown, and the
 * caller's [settle] `onFailed` is run so the failure lands in the existing fail-closed recovery/hold state instead of
 * leaving the owner and the started-service lifetime half settled. The deferred copy is the dangerous one: it is queued
 * as a raw runnable behind the worker, where an escaping exception would end the worker thread (and, uncaught, the
 * daemon), so the containment wraps the settlement itself and not only the call that queues it.
 */
internal class FailedApplySettlementHandoff(
    /** Run on the transaction worker and wait, bounded. Throws [TransactionWorkerBusyException] when the wait lapses. */
    private val onWorker: (block: () -> Unit) -> Unit,
    /** Queue on the transaction worker without waiting. */
    private val enqueue: (block: () -> Unit) -> Unit,
    private val log: (String) -> Unit,
) {
    /**
     * @param onFailed what to do when [settlement] threw: put the service into its fail-closed recovery/hold state. It
     * runs on the thread that ran the settlement, is itself contained, and is never called when the settlement succeeded.
     */
    fun settle(onFailed: () -> Unit = {}, settlement: () -> Unit) {
        val hold: () -> Unit = {
            try {
                onFailed()
            } catch (fallbackError: Throwable) {
                log("FAILED_APPLY_SETTLEMENT hold failed class=${fallbackError.javaClass.name}")
            }
        }
        val contained: () -> Unit = {
            try {
                settlement()
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                log("FAILED_APPLY_SETTLEMENT failed class=${error.javaClass.name} action=HELD_FOR_RECOVERY")
                hold()
            }
        }
        try {
            onWorker(contained)
        } catch (busy: TransactionWorkerBusyException) {
            if (busy.started) return
            try {
                enqueue(contained)
            } catch (error: Throwable) {
                log("FAILED_APPLY_SETTLEMENT enqueue failed class=${error.javaClass.name} action=HELD_FOR_RECOVERY")
                hold()
            }
        } catch (error: Throwable) {
            // The worker call itself failed (the settlement is contained, so it did not): nothing says it ran.
            log("FAILED_APPLY_SETTLEMENT worker call failed class=${error.javaClass.name} action=HELD_FOR_RECOVERY")
            hold()
        }
    }
}
