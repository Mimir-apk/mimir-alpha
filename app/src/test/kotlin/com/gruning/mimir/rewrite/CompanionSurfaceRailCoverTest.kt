package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA D11 (2026-09-11): the overlay-hosted Tabs drawer over this window's own mirror draws its rail over the surface's,
 * and the two rails' seats disagree (evidence S20: six seats over seven). The surface rail stands down for exactly that
 * host. The decision is fed here from the same functions the Presentation reads (`companionDrawerCoversPresentation`,
 * `isVisibleOnPresentation`), so it is tested on real drawer and disposition states rather than on bare booleans.
 */
class CompanionSurfaceRailCoverTest {
    private val render = UUID.randomUUID()
    private val ownMirror = SecondaryDisplayDisposition.MirrorPresented(attemptFor(render))
    private val overlay = CompanionDrawerHost.Overlay(PanelLeaseId(UUID(0L, 11L)))

    private fun railCovered(drawer: CompanionRecentsState.Ui, disposition: SecondaryDisplayDisposition): Boolean =
        companionSurfaceRailCovered(
            drawerCoversSurface = companionDrawerCoversPresentation(drawer, render, disposition),
            drawerSelected = drawer.isVisibleOnPresentation(render),
        )

    @Test
    fun `the overlay-hosted drawer over the own mirror stands the surface rail down`() {
        assertTrue(railCovered(CompanionRecentsState.Ui(requestedVisible = true, drawerHost = overlay), ownMirror))
    }

    @Test
    fun `the Presentation-hosted drawer softens the scene but leaves the surface rail as it was`() {
        val drawer = CompanionRecentsState.Ui(
            requestedVisible = true,
            drawerHost = CompanionDrawerHost.Presentation(render),
        )
        assertTrue("the scene still reads as covered", companionDrawerCoversPresentation(drawer, render, ownMirror))
        assertFalse(railCovered(drawer, ownMirror))
    }

    @Test
    fun `nothing else stands the surface rail down`() {
        // Dismissed: hide() keeps the Overlay host while that window fades, so the rail fades back in with it.
        assertFalse(railCovered(CompanionRecentsState.Ui(requestedVisible = false, drawerHost = overlay), ownMirror))
        // Over a yielded foreign app this window is parked.
        assertFalse(
            railCovered(
                CompanionRecentsState.Ui(requestedVisible = true, drawerHost = overlay),
                SecondaryDisplayDisposition.Yielded(lease = null, evidence = YieldEvidence.HOST_COVERED),
            ),
        )
        // Another render operation's mirror.
        assertFalse(
            railCovered(
                CompanionRecentsState.Ui(requestedVisible = true, drawerHost = overlay),
                SecondaryDisplayDisposition.MirrorPresented(attemptFor(UUID.randomUUID())),
            ),
        )
        // Requested but hostless, and no drawer at all.
        assertFalse(
            railCovered(
                CompanionRecentsState.Ui(requestedVisible = true, drawerHost = CompanionDrawerHost.Hidden),
                ownMirror,
            ),
        )
        assertFalse(railCovered(CompanionRecentsState.Ui(), ownMirror))
    }

    @Test
    fun `the Presentation hands the surface exactly this decision`() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt")
        assertTrue("MimirCompanionPresentation.kt must be readable (${file.absolutePath})", file.isFile)
        val source = file.readText()
        assertEquals(
            "the surface call must pass the overlay-only rail cover, once",
            1,
            source.split("railCovered = companionSurfaceRailCovered(drawerCoversSurface, drawerSelected),").size - 1,
        )
        assertTrue(
            "drawerSelected must stay the Presentation-hosted drawer, or the tests above decide on the wrong input",
            "val drawerSelected = recents.isVisibleOnPresentation(currentRenderOperationId)" in source,
        )
    }

    private fun attemptFor(renderOperationId: UUID) = RenderAttempt(
        operationId = requireNotNull(OperationId.parseOrNull(renderOperationId.toString())),
        parentDispositionId = OperationId.create(),
        rendererOwnerId = RendererOwnerId.create(),
        displayId = 4,
    )
}
