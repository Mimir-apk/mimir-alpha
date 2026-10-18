package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * on 4:3 and square screens the Default theme's sun moves below
 * the text column; wide screens keep it. Onboarding decides from the root profile, as it does for the quiet light.
 */
class OnboardingSceneLightBelowTextTest {

    @Test
    fun `the Nova and the RG square move the sun below the text column`() {
        assertTrue("Nova", onboardingSceneLightBelowText(mimirViewportProfile(640f, 480f)))
        assertTrue("RG, bar hidden", onboardingSceneLightBelowText(mimirViewportProfile(564.7f, 564.7f)))
        assertTrue("RG, bar shown", onboardingSceneLightBelowText(mimirViewportProfile(564.7f, 540.7f)))
    }

    @Test
    fun `wide devices move the sun below the text too, since OR-3`() {
        listOf(
            832.5f to 468.3f, // Odin / Thor main display
            700f to 350f, // RP5-class shelf
            914.3f to 411.4f, // QA emulator
        ).forEach { (width, height) ->
            assertTrue("${width}x$height", onboardingSceneLightBelowText(mimirViewportProfile(width, height)))
        }
    }

    @Test
    fun `no provider means the default anchor`() {
        assertFalse(onboardingSceneLightBelowText(MimirViewportProfile(MimirViewportPosture.STANDARD, 0f, 0f)))
        assertFalse(onboardingSceneLightBelowText(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 0f, 0f)))
    }

    @Test
    fun `the move and the quiet light are separate requirements`() {
        // The Nova is STANDARD: moved, at full light. The RG is compact: moved AND dimmed.
        val nova = mimirViewportProfile(640f, 480f)
        assertTrue(onboardingSceneLightBelowText(nova))
        assertFalse(onboardingQuietSceneLight(nova))
        val rg = mimirViewportProfile(564.7f, 564.7f)
        assertTrue(onboardingSceneLightBelowText(rg))
        assertTrue(onboardingQuietSceneLight(rg))
    }
}
