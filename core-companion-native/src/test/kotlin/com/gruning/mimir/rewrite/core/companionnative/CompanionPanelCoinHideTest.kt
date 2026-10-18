package com.gruning.mimir.rewrite.core.companionnative

import android.view.WindowManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * while one of Mimir's own Bottom panels (Utility, Tweaker, Tabs) is open, the Bottom's floating ᛗ coin FADES out and passes touches through, and fades
 * back in at the same position when the panel closes. The window is never removed. Over a foreign app, on the lattice and on Main the coin behaves exactly as it did.
 *
 * The decisions are pure functions, pinned here; the window work is the controller's (an Android window has no JVM-reachable observable), so its shape is pinned as source.
 */
class CompanionPanelCoinHideTest {
    private val source: String by lazy {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt")
        assertTrue("AndroidCompanionOverlayController.kt must be readable", file.isFile)
        file.readText()
    }

    private val code: String by lazy {
        source.lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    @Test
    fun onlyTheBottomsCoinAnswersAPanelsRequest() {
        assertTrue(companionCoinHiddenForPanel(requested = true, role = CompanionOverlayRole.SECONDARY_COMPANION))
        assertFalse("Main's single-display coin is never hidden by a Bottom panel", companionCoinHiddenForPanel(true, CompanionOverlayRole.PRIMARY_SINGLE))
        assertFalse("nothing mounted, nothing hidden", companionCoinHiddenForPanel(true, null))
        assertFalse("no panel asked: the coin behaves as it always did (a foreign app, the lattice)", companionCoinHiddenForPanel(false, CompanionOverlayRole.SECONDARY_COMPANION))
    }

    @Test
    fun aHiddenCoinIsAtAlphaZeroWhateverTheDimStateAndAShownOneKeepsItsLook() {
        assertEquals(0f, companionCoinRestingAlpha(hiddenForPanel = true, dimmed = false), 0f)
        assertEquals(0f, companionCoinRestingAlpha(hiddenForPanel = true, dimmed = true), 0f)
        val resting = companionCoinRestingAlpha(hiddenForPanel = false, dimmed = false)
        val dimmed = companionCoinRestingAlpha(hiddenForPanel = false, dimmed = true)
        assertEquals("the resting look is untouched", 0.90f, resting, 0f)
        assertEquals("and so is the dim look", 0.80f, dimmed, 0f)
    }

    @Test
    fun aHiddenCoinsWindowPassesEveryTouchThrough() {
        assertEquals(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, companionCoinTouchFlag(hiddenForPanel = true))
        assertEquals("a shown coin adds no flag: it is touchable exactly as before", 0, companionCoinTouchFlag(hiddenForPanel = false))
    }

    @Test
    fun theFadeIsTheOverlayTiersOneClock() {
        assertEquals(180L, CompanionPanelCoinFadeMs)
    }

    @Test
    fun theControllerFadesAndFlagsTheSameWindowAndNeverRemovesOrMovesIt() {
        val apply = code.substringAfter("private fun applyCoinHiddenForPanel(", "").substringBefore("\n    private fun ", "")
        assertTrue("the function is found", apply.isNotEmpty())
        assertTrue("it fades", apply.contains("animate().alpha(") && apply.contains("CompanionPanelCoinFadeMs"))
        assertTrue("it makes the window touch-through by flag, in place", apply.contains("companionCoinTouchFlag(") && apply.contains("updateViewLayout(view, lp)"))
        assertFalse("the window is not removed", apply.contains("removeView") || apply.contains("removeViewImmediate"))
        assertFalse("nor is it moved: position is not written", Regex("""lp\.(x|y)\s*=""").containsMatchIn(apply))
        assertTrue("the request is remembered before anything is touched, so a later mount wears it", apply.indexOf("panelCoinHiddenRequested = hidden") < apply.indexOf("overlayView"))
    }

    @Test
    fun aRebuiltOrRemountedCoinWearsTheRequest() {
        assertTrue("every alpha path reads the resting alpha, which reads the request", code.contains("private fun restingAlpha(): Float = companionCoinRestingAlpha(coinHiddenForPanel(), dimmed)"))
        val params = code.substringAfter("private fun overlayParams(", "").substringBefore("\n    private fun ", "")
        assertTrue("the window flags carry the coin's touch-through while hidden", params.contains("companionCoinTouchFlag(coinHiddenForPanel())"))
        assertTrue("and only for the coin, never the menu", params.contains("if (button) companionCoinTouchFlag("))
    }

    @Test
    fun theContractIsOptionalSoOtherImplementationsNeedNothing() {
        val contract = File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/CompanionNativeContracts.kt").readText()
        assertTrue(contract.contains("fun setCoinHiddenForPanel(hidden: Boolean) {}"))
    }
}
