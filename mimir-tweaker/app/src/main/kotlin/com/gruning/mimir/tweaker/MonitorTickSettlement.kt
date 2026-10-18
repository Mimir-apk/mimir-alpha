package com.gruning.mimir.tweaker

/**
 * What the supervisor monitor does with a tick's outcome once the tick's restore (or its "nothing is active" answer)
 * is real: drop the public owner, end the hold, settle the started-service lifetime, stop the monitor, and, after a
 * genuine restore, arm the post-control thermal watch.
 *
 * This used to run inline on the monitor thread after its bounded wait on the transaction worker. A tick whose sampling
 * and restore together outlived that wait (say 10 s plus 55 s against a 60 s bound) was dropped by the caller, so the
 * restore finished with nobody left to run this: the owner stayed public, the monitor kept ticking, and the thermal
 * watch that follows every Restore never started. It is now a unit so the service can hand it to the worker as the
 * tick's late settlement (see [BoundedTransactionWorker]): exactly one of the monitor thread (the answer arrived in
 * time) and the worker (the caller had left) runs it, with the block's real result.
 *
 * Every step is a cheap, non-blocking call; none waits on the transaction worker, which is what lets it run on that
 * worker.
 */
internal class MonitorTickSettlement(
    private val abortCalibration: (String) -> Unit,
    private val clearPublicOwnerForSession: (generation: Long, transactionId: String) -> Unit,
    private val clearCurrentPublicOwner: () -> Unit,
    private val clearLabApplyHold: () -> Unit,
    private val settleLifetime: () -> Unit,
    private val requestTerminalSettlement: () -> Unit,
    private val stopMonitor: () -> Unit,
    private val startPostControlWatch: () -> Unit,
) {
    /** A null [result] (the tick failed, or never ran) changes nothing, exactly as before. */
    fun settle(result: TransactionResult?) {
        if (result == null) return
        if (!result.restored && result.code != "NO_ACTIVE_SESSION") return
        abortCalibration("ACTIVE_PROFILE_RESTORED")
        val generation = result.generation
        val transactionId = result.transactionId
        if (generation != null && transactionId != null) {
            clearPublicOwnerForSession(generation, transactionId)
        } else {
            clearCurrentPublicOwner()
        }
        clearLabApplyHold()
        settleLifetime()
        requestTerminalSettlement()
        stopMonitor()
        // a genuine Restore just relinquished the fan to vendor Smart with nobody
        // left watching whether it actually cools the device -- see PostControlThermalWatch's own kdoc. Deliberately
        // NOT armed for the bare NO_ACTIVE_SESSION case: nothing was ever Mimir-controlled there, so there is nothing
        // to have abandoned.
        if (result.restored) startPostControlWatch()
    }
}
