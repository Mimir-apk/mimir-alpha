package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Perf §4.5 (2026-09-11, P3-1 and S01-1): the Bottom launch glass unwound at `HostCovered` -- the task-start
 * acknowledgement -- and the lattice drew sharp for 380-410 ms until the runtime parked the window 600 ms later.
 * This is the decision that now holds the glass, and the cases that still take it off.
 */
class CompanionSecondaryLaunchGlassTest {
    private val launch = OperationId.create()
    private val otherLaunch = OperationId.create()

    @Test
    fun `a pending launch shows the glass`() {
        val pending = snapshot(
            SecondaryDisplayDisposition.LaunchPending(hostLease(launch), LaunchPhase.TARGET_DISPATCHED),
            launchLock = launch,
        )
        assertEquals(CompanionSecondaryLaunchGlass.SHOW, decide(pending, pending = true))
    }

    @Test
    fun `a covered host holds the glass while this window still shows, for at most the safety bound`() {
        val covered = hostCovered(launch)
        assertEquals(CompanionSecondaryLaunchGlass.SHOW, decide(covered, msSinceHostCovered = 0L))
        assertEquals(
            CompanionSecondaryLaunchGlass.SHOW,
            decide(covered, msSinceHostCovered = CompanionSecondaryLaunchGlassHoldMs - 1L),
        )
        assertEquals(
            CompanionSecondaryLaunchGlass.RELEASE,
            decide(covered, msSinceHostCovered = CompanionSecondaryLaunchGlassHoldMs),
        )
    }

    @Test
    fun `a window parked behind the host or stopped drops the glass`() {
        assertEquals(
            CompanionSecondaryLaunchGlass.CLEAR,
            decide(hostCovered(launch), presentationCovered = true, msSinceHostCovered = 600L),
        )
    }

    @Test
    fun `a failed launch clears the glass with no unwind`() {
        val recovery = snapshot(
            SecondaryDisplayDisposition.RecoveryNeeded(
                operationId = launch,
                displayId = 4,
                fallback = PrimaryReturnOrigin.LegacyCurrent,
                failure = FailureReason(FailureCode.LAUNCH_DEADLINE_AFTER_DISPATCH, "test"),
            ),
            settlement = LaunchSettlement(launch, LaunchSettlementKind.FAILED),
        )
        val rolledBack = snapshot(
            SecondaryDisplayDisposition.Presenting(renderAttempt(), PresentCause.LAUNCH_ROLLED_BACK),
            settlement = LaunchSettlement(launch, LaunchSettlementKind.FAILED),
        )
        assertEquals(CompanionSecondaryLaunchGlass.CLEAR, decide(recovery))
        assertEquals(CompanionSecondaryLaunchGlass.CLEAR, decide(rolledBack))
        // Even inside the hold window: a failure recorded against this launch wins over a covered host.
        val failedWhileYielded = hostCovered(launch).copy(
            launchSettlement = LaunchSettlement(launch, LaunchSettlementKind.FAILED),
        )
        assertEquals(CompanionSecondaryLaunchGlass.CLEAR, decide(failedWhileYielded, msSinceHostCovered = 100L))
    }

    @Test
    fun `a reclaim clears the glass with no unwind`() {
        val reclaiming = snapshot(
            SecondaryDisplayDisposition.ReclaimPending(
                lease = ReclaimLease(
                    operationId = OperationId.create(),
                    parentHostOperationId = launch,
                    displayId = 4,
                    primaryOrigin = PrimaryReturnOrigin.LegacyCurrent,
                    reason = "edge_swipe",
                ),
                phase = ReclaimPhase.RENDER_REQUESTED,
            ),
            settlement = LaunchSettlement(launch, LaunchSettlementKind.HOST_COVERED),
        )
        val mirrored = snapshot(
            SecondaryDisplayDisposition.MirrorPresented(renderAttempt()),
            settlement = LaunchSettlement(launch, LaunchSettlementKind.HOST_COVERED),
        )
        assertEquals(CompanionSecondaryLaunchGlass.CLEAR, decide(reclaiming, msSinceHostCovered = 100L))
        assertEquals(CompanionSecondaryLaunchGlass.CLEAR, decide(mirrored, msSinceHostCovered = 100L))
    }

    @Test
    fun `another launch's covered host is not this launch's hold`() {
        assertEquals(CompanionSecondaryLaunchGlass.RELEASE, decide(hostCovered(otherLaunch)))
    }

    private fun decide(
        runtime: CompanionRuntimeSnapshot,
        pending: Boolean = false,
        presentationCovered: Boolean = false,
        msSinceHostCovered: Long = 0L,
    ): CompanionSecondaryLaunchGlass = companionSecondaryLaunchGlass(
        transitionOperationId = launch.toString(),
        launchPending = pending,
        runtime = runtime,
        presentationCovered = presentationCovered,
        msSinceHostCovered = msSinceHostCovered,
    )

    private fun hostCovered(operationId: OperationId) = snapshot(
        SecondaryDisplayDisposition.Yielded(hostLease(operationId), YieldEvidence.HOST_COVERED),
        settlement = LaunchSettlement(operationId, LaunchSettlementKind.HOST_COVERED),
    )

    private fun snapshot(
        disposition: SecondaryDisplayDisposition,
        settlement: LaunchSettlement? = null,
        launchLock: OperationId? = null,
    ) = CompanionRuntimeSnapshot(
        stateVersion = 1L,
        displayId = 4,
        disposition = disposition,
        rendererOwnerId = null,
        mirrorActive = disposition is SecondaryDisplayDisposition.MirrorPresented,
        launchLockOperationId = launchLock,
        launchSettlement = settlement,
    )

    private fun hostLease(operationId: OperationId) = HostLease(
        operationId = operationId,
        packageName = "com.google.android.youtube",
        displayId = 4,
        launchMode = "secondary_app",
        primaryOrigin = PrimaryReturnOrigin.LegacyCurrent,
    )

    private fun renderAttempt() =
        RenderAttempt(OperationId.create(), OperationId.create(), RendererOwnerId.create(), displayId = 4)
}
