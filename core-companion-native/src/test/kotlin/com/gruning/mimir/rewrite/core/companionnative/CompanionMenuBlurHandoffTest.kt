package com.gruning.mimir.rewrite.core.companionnative

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nova observation (2026-09-29): the M menu -> Utility/Tweaker/Quick Launch path showed two fading layers
 * colliding. On the main screen a pick that opens a Mimir surface hands the menu to Mimir: the card hides at once,
 * the window gives up focus and touch, and its blur clears on Mimir's glass clock, then the window goes without
 * animation. Every other completion collapses as before. The blur used to be HELD 700 ms, and since the menu's
 * window is above Mimir, its blur-behind blurred the arriving panel, which then snapped sharp.
 */
class CompanionMenuBlurHandoffTest {

    @Test
    fun `the blur clears on Mimir's glass clock instead of holding over Mimir's panel`() {
        // Mimir's scene glass ramps over MimirMotion.OverlayMs (180 ms); its panel is held at alpha 0 for a 130 ms lead.
        assertEquals(180L, CompanionMenuBlurHandoffMs)
        assertEquals(24, companionMenuHandoffBlurRadius(24, 1f))
        assertEquals(0, companionMenuHandoffBlurRadius(24, 0f))
        assertEquals(12, companionMenuHandoffBlurRadius(24, 0.5f))
        val ramp = (0..20).map { companionMenuHandoffBlurRadius(60, 1f - it / 20f) }
        assertTrue("it only ever clears: $ramp", ramp.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0, companionMenuHandoffBlurRadius(0, 1f))
    }

    @Test
    fun `a pick that opens a Mimir surface on the main screen hands off instead of fading the menu`() {
        val source = listOf(
            File("core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
        ).first(File::isFile).readText()
        val end = "\n    }\n"
        val into = source.substringAfter("override fun completeQuickMenuIntoMimir(").substringBefore(end)
        assertTrue(into, into.contains("if (displayRole == CompanionOverlayRole.PRIMARY_SINGLE) handOffMenuToMimir() else collapseMenu()"))
        val handoff = source.substringAfter("private fun handOffMenuToMimir() {").substringBefore(end)
        assertTrue(handoff, handoff.contains("FLAG_NOT_FOCUSABLE") && handoff.contains("FLAG_NOT_TOUCHABLE"))
        assertTrue(handoff, handoff.contains("view.alpha = 0f"))
        assertTrue(handoff, handoff.contains("collapseMenuWithoutFade()"))
        assertTrue("the blur is ramped to clear, never held", handoff.contains("lp.blurBehindRadius = companionMenuHandoffBlurRadius(fromPx, ramp.animatedValue as Float)"))
        assertTrue("the window goes when the ramp ends", handoff.contains("override fun onAnimationEnd(animation: Animator)"))
        assertTrue("no wall-clock hold", !handoff.contains("postDelayed"))
        // The plain completion keeps its plain collapse, which RETURN TO SHELF's reclaim is ordered behind.
        val plain = source.substringAfter("override fun completeQuickMenu(").substringBefore(end)
        assertTrue(plain, plain.contains("collapseMenu()") && !plain.contains("handOffMenuToMimir"))
    }
}
