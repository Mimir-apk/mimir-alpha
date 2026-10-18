package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingFlowPolicyTest {

    private val expectedSequence = listOf(
        "WELCOME",
        "PROFILE",
        "ACCESS",
        "LIBRARY",
        "METADATA",
        "SETTLING",
        "THEME",
        // QA-16: the favourites step sits between THEME and DONE -- after the palette is chosen so the
        // rows render in it, before DONE so "You're all set" lands on a shelf the player just filled.
        "FAVORITES",
        "DONE",
    )

    @Test
    fun onboardingStepsAreDeclaredInTheExpandedOrder() {
        assertEquals(expectedSequence, OnboardingStep.entries.map { it.name })
    }

    @Test
    fun advancePolicyTraversesTheExpandedSequenceAndThenTerminates() {
        val visited = mutableListOf<String>()
        var current: OnboardingStep? = OnboardingStep.WELCOME

        for (ignored in expectedSequence.indices) {
            val step = current ?: break
            visited += step.name
            current = nextOnboardingStep(step)
        }

        assertEquals(expectedSequence, visited)
        assertNull(current)
    }

    /**
     * QA-16: FAVORITES is the flow's first CONDITIONAL step, so the chain is no longer a pure function of the
     * step alone. With no discovered games there is nothing to choose, and a screen with nothing to choose is
     * a dead end rather than a step -- THEME must then go straight to DONE.
     */
    @Test
    fun `an empty library skips the favourites step entirely`() {
        assertEquals(
            OnboardingStep.DONE,
            nextOnboardingStep(OnboardingStep.THEME, favoritesStepApplies = false),
        )
        assertEquals(
            OnboardingStep.FAVORITES,
            nextOnboardingStep(OnboardingStep.THEME, favoritesStepApplies = true),
        )
        // The flag only ever governs THEME's own exit -- it must not be able to strand the player on
        // FAVORITES or change where FAVORITES itself leads.
        assertEquals(
            OnboardingStep.DONE,
            nextOnboardingStep(OnboardingStep.FAVORITES, favoritesStepApplies = false),
        )
        assertNull(nextOnboardingStep(OnboardingStep.DONE, favoritesStepApplies = true))
    }
}
