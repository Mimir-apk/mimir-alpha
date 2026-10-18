package com.gruning.mimir.tweaker

/**
 * What the post-control thermal watch does with a tick's evaluation once it is real: log the decision, and disarm the
 * watch when the evaluation says the device is confirmed cool.
 *
 * This used to run inline on the watch's own thread after its bounded wait on the transaction worker. A tick whose
 * sampling and cooling write outlived that wait (the worker's block kept running) was dropped by the caller, so its
 * decision was never logged and its `shouldDisarm` was never acted on: repeated late ticks left the watch, and the
 * foreground service behind it, armed longer than the device needed. It is now a unit so the service can hand it to the
 * worker as the tick's late settlement (see [BoundedTransactionWorker] and [MonitorTickSettlement], the same pattern):
 * exactly one of the watch thread (the answer arrived in time) and the worker (the caller had left) runs it, with the
 * block's real result.
 *
 * Every step is a cheap, non-blocking call; none waits on the transaction worker, which is what lets it run on it.
 *
 * [stopWatch] is expected to refuse a watch that is no longer the current one: a late result from a watch that was
 * stopped and replaced describes a device state older than the replacement's own ticks and must not disarm it.
 */
internal class PostControlTickSettlement(
    private val onDecision: (PostControlThermalDecision) -> Unit,
    private val stopWatch: () -> Unit,
) {
    /**
     * A null [evaluation] (sampling threw, a fresh session beat the tick to the worker, or the tick never ran) changes
     * nothing: ignorance keeps the watch armed, only an explicit confirmed-safe signal disarms it.
     */
    fun settle(evaluation: PostControlThermalEvaluation?) {
        if (evaluation == null) return
        onDecision(evaluation.decision)
        if (evaluation.shouldDisarm) stopWatch()
    }
}
