package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blur behind the ᛗ menu is the backdrop pipeline that already exists, given a third input.
 *
 *  Nothing new draws for this. `CompanionSurface` already ramps a scene-level blur from
 * `panelCovered`, device-proven for the recents drawer and the Tweaker panel; the menu is a third thing
 * that covers, not a fourth kind of blur.
 *
 * Two facts are pinned, because losing either silently returns the sharp backdrop:
 *
 *  1. the presentation ORs the menu into `panelCovered` — the term is what makes the scene react, and
 *     it is one word in a boolean that reads fine without it;
 *  2. the host actually hands the controller's flow in — the parameter defaults to permanently-false so
 *     that hosts with no overlay are honest, which means a dropped argument compiles, runs, and blurs
 *     nothing.
 */
class CompanionMenuBackdropWiringTest {

    private fun sourceOf(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/$name")
        assertTrue("$name must be readable", file.isFile)
        return file.readText()
    }

    @Test
    fun `the menu joins the drawer and the panel as something that covers`() {
        val source = sourceOf("MimirCompanionPresentation.kt")
        assertTrue(
            "the open menu must reach panelCovered, or the scene behind it stays sharp",
            "panelCovered = drawerCoversSurface || secondaryTweakerVisible || overlayMenuOpen || bottomQuickLaunchCovering," in source,
        )
        assertTrue(
            "the drawer term must count an overlay-hosted drawer over the own mirror (QA D11), not only the Presentation one",
            "val drawerCoversSurface = companionDrawerCoversPresentation(" in source,
        )
        assertTrue(
            "and it must be collected, not read once",
            "val overlayMenuOpen by quickMenuVisualOpen.collectAsState()" in source,
        )
        assertFalse(
            "the blur reads the visual flow; the window flow lands only after the outgoing fade (perf 2026-09-11 §4.4b)",
            "quickMenuOpen.collectAsState()" in source,
        )
    }

    @Test
    fun `the host hands in the real flow rather than the honest default`() {
        val source = sourceOf("CompanionDisplayController.kt")
        assertTrue(
            "a dropped argument here compiles and blurs nothing",
            "quickMenuVisualOpen = overlayController?.quickMenuVisualOpen ?: MutableStateFlow(false)," in source,
        )
    }
}
