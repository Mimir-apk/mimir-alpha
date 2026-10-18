package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirFocusRingSettleMs
import com.gruning.mimir.rewrite.core.ui.MimirFocusTraversal
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reduced motion goes to 0 ms on every onboarding transition . The favourites cell's ring and
 * its Mannaz pin were still easing on their own clocks (2026-09-28). The pills' rings moved
 * onto the product's one selection ring with the ring migration (2026-09-28), whose clocks are pinned in core-ui.
 */
class OnboardingFocusRingMotionTest {

    /**
     * These pin the clock a ring takes when focus LANDS, so no traverse may be under way. The detector is a process-wide
     * object, and a Compose test earlier in the same JVM that pressed the pad through the input router can leave two
     * moves inside its window; at 3x that window is 420 ms, wide enough to catch such a test's paced presses.
     */
    @Before
    fun noTraverseUnderWay() {
        MimirFocusTraversal.reset()
    }

    @Test
    fun `the rings run on the product ring clock, and snap under reduced motion`() {
        // The favourites cell follows the product-wide slower ring clock.
        val active = onboardingFocusRingSpec(reducedMotion = false)
        assertEquals(MimirFocusRingSettleMs, active.durationMillis)
        assertEquals(MimirMotion.Easing, active.easing)
        val reduced = onboardingFocusRingSpec(reducedMotion = true)
        assertEquals(0, reduced.durationMillis)
        assertEquals(MimirMotion.Easing, reduced.easing)
    }

    @Test
    fun `the favourites pin keeps 110 ms, and snaps under reduced motion`() {
        assertEquals(HomeTomeMutationAckMs, onboardingFavoritePinDurationMs(reducedMotion = false))
        assertEquals(110, onboardingFavoritePinDurationMs(reducedMotion = false))
        assertEquals(0, onboardingFavoritePinDurationMs(reducedMotion = true))
    }

    @Test
    fun `the cell ring and the pin read the reduced-motion clocks, not a fixed tween`() {
        val source = onboardingFlowSource()
        val site = source.substringBefore("label = \"onboardingFavoriteRing\"").takeLast(400)
        assertTrue(site, site.contains("animationSpec = onboardingFocusRingSpec(ringReducedMotion)"))
        assertFalse(site, site.contains("tween(MimirMotion.FocusMs"))
        assertTrue(source.contains("durationMs = onboardingFavoritePinDurationMs(ringReducedMotion)"))
        assertFalse(source.contains("durationMs = HomeTomeMutationAckMs,"))
    }

    /**
     * Ring migration (2026-09-28): the pill's ring, wash, gleam and flare are the product's one modifier, on the
     * product's clocks ([MimirFocusRingSettleMs] for the ring, the gleam clock for the flare and the sweep). The pill
     * keeps no clock, key or wash law of its own.
     */
    @Test
    fun `the pill wears the one selection ring and keeps no clock of its own`() {
        val source = onboardingFlowSource()
        val pill = source.substringAfter("private fun OnboardingPill(").substringBefore("// (The ᛗ Mannaz badge")
        assertTrue(pill.contains(".mimirSelectionRing("))
        assertFalse(pill.contains("mimirFocusTileRing("))
        assertFalse(pill.contains("mimirFocusRing("))
        assertFalse(pill.contains("animateFloatAsState"))
        assertFalse(source.contains("OnboardingPillRingMs"))
        assertFalse(source.contains("OnboardingPillFlareMs"))
        assertFalse(source.contains("onboardingPillRingSpec("))
        // A gold pill gleams once and a ghost pill never (SPEC slice 5); both flare when focus lands, once the step
        // has arrived. Update: neither pill is a filled capsule -- the gold one is the PRIMARY etched chip (the same dark plate, its rim and word in
        // the accent), the ghost the unselected one -- so the metal has its own rim and the ring paints no fill, no resting hairline and no rest dim (the dim would
        // dim the plate). The ring is drawn BEFORE the surface: on the plate itself, with its wash under the opaque metal.
        assertTrue(pill.contains("gleam = if (gold) MimirRingGleam.Sweep else MimirRingGleam.None,"))
        assertTrue(pill.contains("flare = true,"))
        assertTrue(pill.contains("armed = { arrival.value >= 1f },"))
        assertFalse(pill.contains("restingOutline"))
        assertFalse(pill.contains("fill = "))
        assertFalse(pill.contains("filled = "))
        assertFalse("the rest dim would dim the plate", pill.contains("restingAlpha"))
        assertTrue(pill.contains(".mimirEtchedChipSurface(palette, focused = focusAffordanceActive, primary = gold, edgeFlush = true)"))
        assertFalse("a primary is never warmed: warming is the selected look", pill.contains("warmed = "))
        assertTrue("the ring is drawn before the surface", pill.indexOf(".mimirSelectionRing(") in 0 until pill.indexOf(".mimirEtchedChipSurface("))
    }

    @Test
    fun `the favourites cell is a game, so it carries the game gleam on its tile ring`() {
        val source = onboardingFlowSource()
        assertTrue(source.contains("val ringGlint = rememberMimirFocusGameGleam(if (focused) row.gameId.value else null)"))
        val rings = Regex("""\.mimirFocusTileRing\(([^)]*\))*[^)]*\)""").findAll(source).map { it.value }.toList()
        val cellRing = rings.single { it.contains("progress = { ringProgress }") && it.contains("tileFocusRing") }
        assertTrue(cellRing, cellRing.contains("glint = ringGlint"))
    }

    private fun onboardingFlowSource(): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/OnboardingFlow.kt"),
    ).first(File::isFile).readText()
}
