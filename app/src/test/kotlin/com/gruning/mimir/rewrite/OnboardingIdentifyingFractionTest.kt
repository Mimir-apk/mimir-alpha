package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingIdentifyingFractionTest {
    @Test
    fun snapshotKeepsAttemptedAndTotalFromOneRunTogether() {
        val fraction = onboardingIdentifyingFraction(
            IdentityEnrichmentLiveSnapshot(runId = 4L, attempted = 58, total = 61),
        )
        assertEquals(61, fraction.total)
        assertEquals(58, fraction.done)
        assertTrue(fraction.done < requireNotNull(fraction.total))
    }

    @Test
    fun noSnapshotOrInvalidTotalHasNoFraction() {
        assertNull(onboardingIdentifyingFraction(null).total)
        assertNull(
            onboardingIdentifyingFraction(
                IdentityEnrichmentLiveSnapshot(runId = 4L, attempted = 0, total = 0),
            ).total,
        )
    }

    @Test
    fun allHashesCompleteReturnsToIndeterminateUntilCommitAndGroupingFinish() {
        val fraction = onboardingIdentifyingFraction(
            IdentityEnrichmentLiveSnapshot(runId = 4L, attempted = 61, total = 61),
        )
        assertNull(fraction.total)
        assertEquals(0, fraction.done)
    }
}
