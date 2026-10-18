package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA D11 (2026-09-11): with a game on Main the drawer is overlay-hosted over Mimir's own mirror
 * (`companionDrawerHostSelection`), and the surface under it kept a sharp scene. The overlay drawer over the own
 * mirror now covers the surface exactly like the Presentation-hosted drawer; nothing else counts.
 */
class CompanionOverlayDrawerCoverTest {
    private val render = UUID.randomUUID()
    private val ownMirror = SecondaryDisplayDisposition.MirrorPresented(attemptFor(render))
    private val overlay = CompanionDrawerHost.Overlay(PanelLeaseId(UUID(0L, 11L)))

    @Test
    fun `the Presentation-hosted drawer covers its own window`() {
        val drawer = CompanionRecentsState.Ui(
            requestedVisible = true,
            drawerHost = CompanionDrawerHost.Presentation(render),
        )
        assertTrue(companionDrawerCoversPresentation(drawer, render, ownMirror))
    }

    @Test
    fun `an overlay-hosted drawer over the own mirror covers the surface under it`() {
        val drawer = CompanionRecentsState.Ui(requestedVisible = true, drawerHost = overlay)
        assertTrue(companionDrawerCoversPresentation(drawer, render, ownMirror))
    }

    @Test
    fun `an overlay-hosted drawer covers nothing once dismissed, over a foreign app, or over another window`() {
        // hide() keeps the Overlay host while the window fades out (CompanionRecentsCoordinator.hide).
        assertFalse(
            companionDrawerCoversPresentation(
                CompanionRecentsState.Ui(requestedVisible = false, drawerHost = overlay),
                render,
                ownMirror,
            ),
        )
        // Over a yielded foreign app this window is parked; there is no surface to cover.
        assertFalse(
            companionDrawerCoversPresentation(
                CompanionRecentsState.Ui(requestedVisible = true, drawerHost = overlay),
                render,
                SecondaryDisplayDisposition.Yielded(lease = null, evidence = YieldEvidence.HOST_COVERED),
            ),
        )
        // A stale window whose render operation is not the mirror's.
        assertFalse(
            companionDrawerCoversPresentation(
                CompanionRecentsState.Ui(requestedVisible = true, drawerHost = overlay),
                render,
                SecondaryDisplayDisposition.MirrorPresented(attemptFor(UUID.randomUUID())),
            ),
        )
        // Requested but hostless: nothing is drawn yet.
        assertFalse(
            companionDrawerCoversPresentation(
                CompanionRecentsState.Ui(requestedVisible = true, drawerHost = CompanionDrawerHost.Hidden),
                render,
                ownMirror,
            ),
        )
    }

    private fun attemptFor(renderOperationId: UUID) = RenderAttempt(
        operationId = requireNotNull(OperationId.parseOrNull(renderOperationId.toString())),
        parentDispositionId = OperationId.create(),
        rendererOwnerId = RendererOwnerId.create(),
        displayId = 4,
    )
}
