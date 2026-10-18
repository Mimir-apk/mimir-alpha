package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * while one of Mimir's own Bottom panels (Utility, Tweaker, Tabs) is open, the floating ᛗ coin on the Bottom hides (on the Thor it covered the Utility's
 * "Bottom screen 60%" value), and comes back when the panel closes. Over a foreign app and on the lattice it behaves as it always did. The coin is told by the Bottom's own
 * surface stack, the one `surface.state` writes.
 */
class CompanionBottomPanelCoinTest {
    private val log = BottomUiReceiptLog(
        sink = { _, _ -> },
        nowMs = { 0L },
        timer = BottomUiReceiptTimer { _, _ -> ({}) },
    )
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bottom = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, displayId = 4)
    private val target = MutableStateFlow<CompanionOverlayTarget?>(bottom)
    private val told = ArrayList<Boolean>()

    @After
    fun tearDown() = scope.cancel()

    private fun observe(): Job = observeBottomPanelCoin(scope, target, { told += it }, log)

    private fun enter(surface: String, display: Int = 4, host: String? = null) {
        log.surface(surface, display, visible = true, host = host)
    }

    private fun exit(surface: String, display: Int = 4, host: String? = null) {
        log.surface(surface, display, visible = false, host = host)
    }

    @Test
    fun openingAPanelHidesTheCoin() {
        observe()
        enter(BottomSurface.LATTICE)
        assertEquals("a lattice with nothing over it tells the coin nothing new: it stays shown", listOf(false), told)
        for (panel in listOf(BottomSurface.UTILITY, BottomSurface.TWEAKER, BottomSurface.TABS)) {
            told.clear()
            enter(panel)
            assertEquals("$panel opens: the coin hides", listOf(true), told)
            exit(panel)
            assertEquals("$panel closes: the coin returns", listOf(true, false), told)
        }
    }

    @Test
    fun closingThePanelRestoresTheCoin() {
        observe()
        enter(BottomSurface.LATTICE)
        enter(BottomSurface.UTILITY)
        assertEquals(listOf(false, true), told)
        exit(BottomSurface.UTILITY)
        assertEquals("the coin is shown again, in the same window it never left", listOf(false, true, false), told)
    }

    @Test
    fun aPanelAmongOthersStillHidesAndOnlyTheLastPanelClosingRestores() {
        observe()
        enter(BottomSurface.LATTICE)
        enter(BottomSurface.TABS)
        enter(BottomSurface.UTILITY, host = null)
        exit(BottomSurface.UTILITY)
        assertEquals("Tabs is still up: still hidden, and nothing new is told", listOf(false, true), told)
        exit(BottomSurface.TABS)
        assertEquals(listOf(false, true, false), told)
    }

    @Test
    fun theCoinNeverHidesOverAForeignAppOrTheLattice() {
        observe()
        // A foreign host owns the Bottom: no Mimir surface is up but the menu, the dock and the lattice's own bits.
        enter(BottomSurface.DOCK)
        enter(BottomSurface.M_MENU)
        enter(BottomSurface.LATTICE)
        exit(BottomSurface.M_MENU)
        assertTrue("never told to hide: $told", told.none { it })
        assertEquals("and told to show at most once", listOf(false), told)
    }

    @Test
    fun aPanelOnAnotherDisplayOrWithTheMainTargetNeverHidesTheBottomCoin() {
        observe()
        enter(BottomSurface.UTILITY, display = 9)
        assertTrue("a panel on a different display is not the Bottom's: $told", told.none { it })
        target.value = CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, displayId = 0)
        enter(BottomSurface.UTILITY, display = 0)
        assertTrue("Main's single-display coin is not the Bottom's: $told", told.none { it })
    }

    @Test
    fun aPanelAlreadyUpWhenTheTargetArrivesHidesTheCoinAtOnce() {
        enter(BottomSurface.UTILITY)
        target.value = null
        observe()
        assertEquals("no Bottom yet: shown", listOf(false), told)
        target.value = bottom
        assertEquals("the Bottom arrives with a panel up: hidden", listOf(false, true), told)
    }

    @Test
    fun theStackDecisionNamesUtilityTweakerTabsAndTheQuickLaunchPicker() {
        assertEquals(setOf("utility", "tweaker", "tabs", "picker"), CompanionCoinHidingSurfaces)
        assertTrue(companionCoinHiddenByStack(listOf("lattice", "tabs", "m-menu")))
        assertTrue(companionCoinHiddenByStack(listOf("lattice", "picker", "m-menu", "dock")))
        assertFalse(companionCoinHiddenByStack(listOf("lattice", "m-menu", "dock")))
        assertFalse(companionCoinHiddenByStack(emptyList()))
    }
}
