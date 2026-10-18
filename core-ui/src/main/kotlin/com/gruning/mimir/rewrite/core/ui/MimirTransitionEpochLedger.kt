package com.gruning.mimir.rewrite.core.ui

/** A destination-first presentation transaction backed by at most two reusable pixel layers. */
data class MimirTransitionFrame<T>(
    val epoch: Long,
    val target: T,
    val primaryLayerIndex: Int,
    val outgoingLayerIndex: Int?,
) {
    init {
        require(primaryLayerIndex == 0 || primaryLayerIndex == 1)
        require(outgoingLayerIndex == null || outgoingLayerIndex != primaryLayerIndex)
    }

    val surfaceCount: Int get() = if (outgoingLayerIndex == null) 1 else 2
}

/**
 * Issues monotonic presentation epochs without owning logical navigation. A prepared frame may be abandoned;
 * only [commit] changes the presented target, and only the current epoch may complete visual cleanup.
 */
class MimirTransitionEpochLedger<T>(initialTarget: T) {
    private var issuedEpoch = 0L
    private var currentEpoch = 0L
    private var presentedTarget = initialTarget
    private var primaryLayerIndex = 0

    @Synchronized
    fun prepare(
        target: T,
        retainOutgoing: (from: T, to: T) -> Boolean,
    ): MimirTransitionFrame<T> {
        if (target == presentedTarget) {
            return MimirTransitionFrame(
                epoch = currentEpoch,
                target = target,
                primaryLayerIndex = primaryLayerIndex,
                outgoingLayerIndex = null,
            )
        }
        check(issuedEpoch < Long.MAX_VALUE) { "Transition epoch exhausted" }
        issuedEpoch += 1L
        val shouldRetain = retainOutgoing(presentedTarget, target)
        val outgoing = primaryLayerIndex.takeIf { shouldRetain }
        val primary = if (shouldRetain) 1 - primaryLayerIndex else primaryLayerIndex
        return MimirTransitionFrame(
            epoch = issuedEpoch,
            target = target,
            primaryLayerIndex = primary,
            outgoingLayerIndex = outgoing,
        )
    }

    @Synchronized
    fun commit(frame: MimirTransitionFrame<T>): Boolean {
        if (frame.epoch < currentEpoch) return false
        currentEpoch = frame.epoch
        presentedTarget = frame.target
        primaryLayerIndex = frame.primaryLayerIndex
        return true
    }

    @Synchronized
    fun acceptsCompletion(epoch: Long): Boolean = epoch == currentEpoch
}
