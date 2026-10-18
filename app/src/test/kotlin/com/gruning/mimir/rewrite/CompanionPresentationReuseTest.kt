package com.gruning.mimir.rewrite

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPresentationReuseTest {
    @Test
    fun `window reveal gate ignores first show and emits once after each real cover`() {
        val gate = CompanionWindowRevealGate()

        assertFalse(gate.onWindowVisibilityChanged(View.GONE))
        assertFalse(gate.onWindowVisibilityChanged(View.VISIBLE))
        assertFalse(gate.onWindowVisibilityChanged(View.VISIBLE))

        assertFalse(gate.onWindowVisibilityChanged(View.INVISIBLE))
        assertTrue(gate.onWindowVisibilityChanged(View.VISIBLE))
        assertFalse(gate.onWindowVisibilityChanged(View.VISIBLE))

        assertFalse(gate.onWindowVisibilityChanged(View.GONE))
        assertTrue(gate.onWindowVisibilityChanged(View.VISIBLE))
    }

    @Test
    fun `visible presentation is reused only on the same display`() {
        assertTrue(canReuseCompanionPresentation(currentDisplayId = 4, currentShowing = true, targetDisplayId = 4))
        assertFalse(canReuseCompanionPresentation(currentDisplayId = 4, currentShowing = false, targetDisplayId = 4))
        assertFalse(canReuseCompanionPresentation(currentDisplayId = 5, currentShowing = true, targetDisplayId = 4))
        assertFalse(canReuseCompanionPresentation(currentDisplayId = null, currentShowing = true, targetDisplayId = 4))
    }

    @Test
    fun `only an identity matched reclaim for an active main dual screen game forces a fresh presentation`() {
        val attempt = RenderAttempt(OperationId.create(), OperationId.create(), RendererOwnerId.create(), displayId = 4)
        val reclaim = SecondaryDisplayDisposition.ReclaimPending(
            lease = ReclaimLease(
                operationId = OperationId.create(),
                parentHostOperationId = OperationId.create(),
                displayId = 4,
                primaryOrigin = PrimaryReturnOrigin.LegacyCurrent,
                reason = "overlay_edge_back",
            ),
            phase = ReclaimPhase.RENDER_REQUESTED,
            expectedRender = attempt,
        )

        assertTrue(companionReclaimNeedsFreshPresentation(reclaim, attempt, activeMainDualScreenGame = true))
        assertFalse(companionReclaimNeedsFreshPresentation(reclaim, attempt, activeMainDualScreenGame = false))
        assertFalse(
            companionReclaimNeedsFreshPresentation(
                reclaim,
                attempt.copy(displayId = 5),
                activeMainDualScreenGame = true,
            ),
        )
        assertFalse(
            companionReclaimNeedsFreshPresentation(
                SecondaryDisplayDisposition.MirrorPresented(attempt),
                attempt,
                activeMainDualScreenGame = true,
            ),
        )
    }

    @Test
    fun `covered presentation is retained only while runtime owns that display identity`() {
        assertTrue(canRetainCoveredCompanionPresentation(4, currentShowing = true, runtimeDisplayId = 4))
        assertFalse(canRetainCoveredCompanionPresentation(4, currentShowing = false, runtimeDisplayId = 4))
        assertFalse(canRetainCoveredCompanionPresentation(4, currentShowing = true, runtimeDisplayId = 5))
        assertFalse(canRetainCoveredCompanionPresentation(4, currentShowing = true, runtimeDisplayId = null))
    }

    @Test
    fun `main game exit does not reclaim an active secondary quick launch`() {
        val yielded = SecondaryDisplayDisposition.Yielded(
            lease = HostLease(
                operationId = OperationId.create(),
                packageName = "com.example.quicklaunch",
                displayId = 4,
                launchMode = com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
                primaryOrigin = PrimaryReturnOrigin.LegacyCurrent,
            ),
            evidence = YieldEvidence.HOST_COVERED,
        )

        assertFalse(companionMainGameExitReclaimAllowed(yielded, "com.example.quicklaunch"))
    }

    @Test
    fun `retained presentation decor is hidden under the foreign host and revealed for reclaim`() {
        assertEquals(
            View.INVISIBLE,
            companionPresentationDecorVisibility(coveredBySecondaryHost = true),
        )
        assertEquals(
            View.VISIBLE,
            companionPresentationDecorVisibility(coveredBySecondaryHost = false),
        )
    }
}
