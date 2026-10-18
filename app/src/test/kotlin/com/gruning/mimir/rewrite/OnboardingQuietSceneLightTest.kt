package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact review 2026-09-27 (ONB-1): the earlier update dimmed the Default sun on compact Home and Library, and onboarding kept
 * it at full light under its bottom-right action seats. Onboarding now bakes the same quiet light, on compact only.
 */
class OnboardingQuietSceneLightTest {

    @Test
    fun `the RG square bakes onboarding with the quiet light, bar shown or hidden`() {
        assertTrue(onboardingQuietSceneLight(mimirViewportProfile(564.7f, 564.7f)))
        assertTrue(onboardingQuietSceneLight(mimirViewportProfile(564.7f, 540.7f)))
    }

    @Test
    fun `every standard device keeps full light`() {
        listOf(
            832.5f to 468.3f, // Odin
            640f to 480f, // Nova
            700f to 350f, // RP5
            914.3f to 411.4f, // QA emulator
        ).forEach { (width, height) ->
            assertFalse("${width}x$height", onboardingQuietSceneLight(mimirViewportProfile(width, height)))
        }
    }

    @Test
    fun `no provider means full light`() {
        assertFalse(onboardingQuietSceneLight(MimirViewportProfile(MimirViewportPosture.STANDARD, 0f, 0f)))
        // Even a compact posture with no measured size is not trusted.
        assertFalse(onboardingQuietSceneLight(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 0f, 0f)))
    }
}
