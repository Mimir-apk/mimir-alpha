package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy L-6, 2026-09-01, from review finding D8: the recents ✕ is HIDDEN in `RecoveryNeeded`.
 *
 * The close path reclaims the display first so the target drops to background importance — the only
 * importance `killBackgroundProcesses` can touch — and that reclaim is driven off a live `Yielded`
 * lease. `RecoveryNeeded` has no lease, so nothing is reclaimed, the reap no-ops against a
 * still-visible app, and the press removes a tab while changing nothing else.
 */
class CompanionTabClosePolicyTest {

    @Test
    fun `the close control is withdrawn while recovery is needed`() {
        val recovery = SecondaryDisplayDisposition.RecoveryNeeded(
            operationId = OperationId.create(),
            displayId = 4,
            fallback = PrimaryReturnOrigin("LIBRARY"),
            failure = FailureReason(FailureCode.PROCESS_RECOVERED_NON_MIRROR, "saved host"),
        )

        assertFalse(companionTabCloseIsOffered(snapshot(recovery)))
    }

    /**
     * **The deliberate narrowing, pinned.** A broad condition covering all states outside `Yielded`
     * would hide the ✕ while presenting or mirroring too. The drawer lists every recent task
     * on the secondary display, not only the one hosting it, so closing a tab for a non-host app is
     * ordinary and works — hiding it there would break a control that does exactly what it promises.
     */
    @Test
    fun `every other disposition still offers the close control`() {
        val owner = RendererOwnerId.create()
        val render = RenderAttempt(OperationId.create(), OperationId.create(), owner, displayId = 4)
        val lease = HostLease(
            operationId = OperationId.create(),
            packageName = "org.example.game",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
        )

        assertTrue(companionTabCloseIsOffered(snapshot(SecondaryDisplayDisposition.NoDisplay)))
        assertTrue(
            companionTabCloseIsOffered(
                snapshot(SecondaryDisplayDisposition.Presenting(render, PresentCause.RENDERER_REBOUND)),
            ),
        )
        assertTrue(companionTabCloseIsOffered(snapshot(SecondaryDisplayDisposition.MirrorPresented(render))))
        assertTrue(
            companionTabCloseIsOffered(
                snapshot(
                    SecondaryDisplayDisposition.Yielded(lease = lease, evidence = YieldEvidence.HOST_COVERED),
                ),
            ),
        )
        assertTrue(
            companionTabCloseIsOffered(
                snapshot(
                    SecondaryDisplayDisposition.LaunchPending(
                        lease = lease,
                        phase = LaunchPhase.TARGET_DISPATCHED,
                    ),
                ),
            ),
        )
    }

    private fun snapshot(disposition: SecondaryDisplayDisposition) = CompanionRuntimeSnapshot(
        stateVersion = 1L,
        displayId = 4,
        disposition = disposition,
        rendererOwnerId = null,
        mirrorActive = false,
        launchLockOperationId = null,
        launchSettlement = null,
    )
}
