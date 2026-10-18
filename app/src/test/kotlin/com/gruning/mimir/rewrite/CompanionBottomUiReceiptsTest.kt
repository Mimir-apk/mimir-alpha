package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuOrigin
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Bottom receipts say about the runtime's own state: pinned as text so a driver can grep it. */
class CompanionBottomUiReceiptsTest {
    private val origin = PrimaryReturnOrigin("LIBRARY")
    private val operation = OperationId.create()
    private val renderer = RendererOwnerId.create()
    private val attempt = RenderAttempt(operation, OperationId.create(), renderer, 4)

    @Test
    fun aReclaimNamesItsReasonAndAPlainRenderNamesItsDisposition() {
        val lease = ReclaimLease(operation, null, 4, origin, "tab_close_reclaim")
        assertEquals(
            "reclaim_tab_close_reclaim",
            companionPresentationReceiptReason(SecondaryDisplayDisposition.ReclaimPending(lease, ReclaimPhase.GUARD_REQUESTED)),
        )
        assertEquals("mirror", companionPresentationReceiptReason(SecondaryDisplayDisposition.MirrorPresented(attempt)))
        assertEquals(
            "present_display_available",
            companionPresentationReceiptReason(SecondaryDisplayDisposition.Presenting(attempt, PresentCause.DISPLAY_AVAILABLE)),
        )
        assertEquals("no_display", companionPresentationReceiptReason(SecondaryDisplayDisposition.NoDisplay))
    }

    @Test
    fun theDisplayOwnerIsMimirUntilAForeignHostCoversIt() {
        val host = HostLease(operation, "org.example.player", 4, "launch", origin)
        assertEquals("none", companionDisplayOwnerLabel(SecondaryDisplayDisposition.NoDisplay))
        assertEquals("mimir", companionDisplayOwnerLabel(SecondaryDisplayDisposition.MirrorPresented(attempt)))
        assertEquals(
            "launching:org.example.player",
            companionDisplayOwnerLabel(SecondaryDisplayDisposition.LaunchPending(host, LaunchPhase.HOST_REQUESTED)),
        )
        assertEquals(
            "host:org.example.player",
            companionDisplayOwnerLabel(SecondaryDisplayDisposition.Yielded(host, YieldEvidence.HOST_COVERED)),
        )
        assertEquals(
            "host:unknown",
            companionDisplayOwnerLabel(SecondaryDisplayDisposition.Yielded(null, YieldEvidence.HOST_COVERED)),
        )
        val lease = ReclaimLease(operation, null, 4, origin, "overlay_edge_back")
        assertEquals(
            "reclaiming",
            companionDisplayOwnerLabel(SecondaryDisplayDisposition.ReclaimPending(lease, ReclaimPhase.GUARD_REQUESTED)),
        )
    }

    @Test
    fun everyMenuRowHasAStableControlId() {
        val ids = QuickMenuItem.values().map(::companionMenuControlId)
        assertEquals(QuickMenuItem.values().size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("m-menu.") })
        assertEquals("m-menu.return_to_shelf", companionMenuControlId(QuickMenuItem.SECONDARY_RETURN))
        assertEquals("m-menu.tabs", companionMenuControlId(QuickMenuItem.SECONDARY_TABS))
        assertEquals("m-menu.swap", companionMenuControlId(QuickMenuItem.SECONDARY_SWAP))
        assertEquals("m-menu.trackpad", companionMenuControlId(QuickMenuItem.TRACKPAD))
        assertEquals("m-menu.utility", companionMenuControlId(QuickMenuItem.UTILITY))
        assertEquals("m-menu.quick_launch", companionMenuControlId(QuickMenuItem.QUICK_LAUNCH))
    }

    @Test
    fun aCoinTapHasItsOwnControlIdAndNoMenuSurface() {
        val tap = CompanionOverlayAction.QuickMenu(
            UUID(0L, 1L), CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.SYSTEM_TABS, QuickMenuOrigin.COIN_TAP,
        )
        val receipt = companionQuickMenuReceipt(tap)
        assertEquals("m-button.recents", receipt.id)
        assertEquals(null, receipt.surface)
    }

    @Test
    fun theCoinTapReceiptNamesWhatItDid() {
        assertEquals(CompanionQuickMenuReceipt("m-button.recents", null, "open"), companionCoinRecentsReceipt(RecentsTapAction.OPEN))
        assertEquals(CompanionQuickMenuReceipt("m-button.recents", null, "close"), companionCoinRecentsReceipt(RecentsTapAction.CLOSE))
        assertEquals(CompanionQuickMenuReceipt("m-button.recents", null, "close_home"), companionCoinRecentsReceipt(RecentsTapAction.CLOSE_HOME))
        // The grammar is the existing control.activate line, with the action in its target slot.
        assertEquals(
            "ev=control.activate id=m-button.recents display=4 surface=none target=close",
            com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat.control("m-button.recents", 4, null, 0, "close"),
        )
    }

    @Test
    fun theMenuTabsRowKeepsItsMenuIdAndSurface() {
        val row = CompanionOverlayAction.QuickMenu(UUID(0L, 2L), CompanionOverlayRole.SECONDARY_COMPANION, QuickMenuItem.SYSTEM_TABS)
        assertEquals(QuickMenuOrigin.MENU_ROW, row.origin)
        val receipt = companionQuickMenuReceipt(row)
        assertEquals("m-menu.system_tabs", receipt.id)
        assertEquals(BottomSurface.M_MENU, receipt.surface)
    }
}
