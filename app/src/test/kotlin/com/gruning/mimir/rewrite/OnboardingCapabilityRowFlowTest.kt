package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The large-font layout task (2026-10-06): when an onboarding capability row sets its pill on the title's line. */
class OnboardingCapabilityRowFlowTest {

    @Test
    fun `a row flows only at a raised scale, with a trailing part, and only when that is strictly shorter`() {
        assertTrue(onboardingCapabilityRowFlows(mayFlow = true, hasTrailing = true, sideBySideHeightPx = 138, flowedHeightPx = 110))
        assertFalse("at the default scale the row stays as certified", onboardingCapabilityRowFlows(false, true, 138, 110))
        assertFalse("no pill or status, nothing to move", onboardingCapabilityRowFlows(true, false, 138, 110))
        assertFalse("a tie keeps the column beside the pill", onboardingCapabilityRowFlows(true, true, 104, 104))
        assertFalse("never the taller of the two", onboardingCapabilityRowFlows(true, true, 104, 110))
    }
}
