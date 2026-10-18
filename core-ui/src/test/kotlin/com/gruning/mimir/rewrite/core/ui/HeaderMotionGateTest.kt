package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderMotionGateTest {
    @Test
    fun idleIsTheOnlyPhaseThatStopsHeaderAmbientLoops() {
        assertTrue(headerAmbientMotionRuns(AmbientMotionPhase.INTERACTING))
        assertTrue(headerAmbientMotionRuns(AmbientMotionPhase.RECENT))
        assertFalse(headerAmbientMotionRuns(AmbientMotionPhase.IDLE))
    }

    @Test
    fun flowResumeDurationStartsFromTheFrozenPhase() {
        assertEquals(13_000, headerPhaseRemainingDurationMillis(phase = 0f, periodMillis = 13_000))
        assertEquals(7_540, headerPhaseRemainingDurationMillis(phase = 0.42f, periodMillis = 13_000))
        assertEquals(4_060, headerPhaseRemainingDurationMillis(phase = 0.42f, periodMillis = 7_000))
    }

    @Test
    fun breatheOpacityIsAStableFunctionOfTheRetainedPhase() {
        assertEquals(1f, headerBreatheAlpha(phase = 0f), 0.0001f)
        assertEquals(HeaderBreatheLowAlpha, headerBreatheAlpha(phase = 0.5f), 0.0001f)
        assertEquals(1f, headerBreatheAlpha(phase = 1f), 0.0001f)
        assertEquals(headerBreatheAlpha(phase = 0.25f), headerBreatheAlpha(phase = 0.75f), 0.0001f)

        val frozenPhase = 0.37f
        val frozenAlpha = headerBreatheAlpha(frozenPhase)
        assertEquals(frozenAlpha, headerBreatheAlpha(frozenPhase), 0f)
    }

    @Test
    fun onlyAuthoritativeShippingThemesUseMoltenMotion() {
        // FUX-D: molten ART is dock material and exists for every theme; the animated HEADER rule is a
        // narrower shipped contract — Surtr and Niflheim only, everything else keeps the plain hairline.
        val motionThemes = setOf(MimirThemeId.SURTR, MimirThemeId.NIFLHEIM)
        MimirThemeId.entries.forEach { themeId ->
            assertTrue("$themeId dock material", MimirShelfArt.moltenSeparatorFor(themeId).crestStops.isNotEmpty())
            val motion = headerMoltenMotionSpec(themeId)
            assertEquals("$themeId molten eligibility", themeId in motionThemes, motion != null)
            if (motion != null) {
                assertEquals("$themeId primary flow", 13_000, motion.primaryFlowPeriodMillis)
                assertEquals("$themeId core flow", 19_000, motion.coreFlowPeriodMillis)
                assertEquals("$themeId crest alpha", 7_000, motion.crestAlphaPeriodMillis)
                assertEquals("$themeId core pulse", 4_500, motion.corePulsePeriodMillis)
                assertEquals("$themeId crest motion", HeaderCrestAlphaMotion.BREATHE, motion.crestAlphaMotion)
            }
        }
    }

    @Test
    fun surtrFlickerMatchesTheShippingKeyframes() {
        assertEquals(1f, headerSurtrFlickerAlpha(0.92f), 0.0001f)
        assertEquals(0.4f, headerSurtrFlickerAlpha(0.93f), 0.0001f)
        assertEquals(0.9f, headerSurtrFlickerAlpha(0.94f), 0.0001f)
        assertEquals(0.55f, headerSurtrFlickerAlpha(0.96f), 0.0001f)
        assertEquals(1f, headerSurtrFlickerAlpha(0.97f), 0.0001f)
        assertEquals(0.7f, headerSurtrFlickerAlpha(0.925f), 0.0001f)
    }

    @Test
    fun corePulsePeaksAtMidCycleAndResumesDeterministically() {
        assertEquals(HeaderCorePulseLowAlpha, headerCorePulseAlpha(0f), 0.0001f)
        assertEquals(1f, headerCorePulseAlpha(0.5f), 0.0001f)
        assertEquals(HeaderCorePulseLowAlpha, headerCorePulseAlpha(1f), 0.0001f)
        assertEquals(headerCorePulseAlpha(0.25f), headerCorePulseAlpha(0.75f), 0.0001f)
    }

    @Test
    fun ordinaryRuleHairlineStaysCenteredInsideTheFixedBand() {
        val geometry = centeredHeaderHairlineGeometry(
            containerHeightPx = 3f,
            requestedHeightPx = 1f,
        )
        assertEquals(1f, geometry.topPx, 0f)
        assertEquals(1f, geometry.heightPx, 0f)

        val clamped = centeredHeaderHairlineGeometry(
            containerHeightPx = 3f,
            requestedHeightPx = 5f,
        )
        assertEquals(0f, clamped.topPx, 0f)
        assertEquals(3f, clamped.heightPx, 0f)
    }
}
