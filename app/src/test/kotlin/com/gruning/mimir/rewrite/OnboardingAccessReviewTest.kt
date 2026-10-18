package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingAccessReviewTest {

    private val pending = OnboardingAccessUi(
        usageAvailable = true,
        usageGranted = false,
        homeRoleAvailable = true,
        homeRoleHeld = false,
        filesGranted = false,
    )

    @Test
    fun `Continue remains blocked until every presented capability is granted or deferred`() {
        val review = OnboardingAccessReviewState()

        assertFalse(onboardingAccessComplete(pending, review))
        onboardingAccessCapabilities(pending).forEach(review::defer)
        assertTrue(onboardingAccessComplete(pending, review))
    }

    @Test
    fun `a granted capability needs no deferral but every other row still does`() {
        val review = OnboardingAccessReviewState()
        val filesGranted = pending.copy(filesGranted = true)

        assertFalse(onboardingAccessNeedsReview(OnboardingAccessCapability.FILES, filesGranted, review))
        assertTrue(onboardingAccessNeedsReview(OnboardingAccessCapability.USAGE, filesGranted, review))
    }

    @Test
    fun `the sub-sequence is the four permission asks, the Mimir button's pair together`() {
        // FILES -> USAGE -> HOME_ROLE. QA-10's OVERLAY sits next to USAGE because the two
        // are the halves of one thing the player recognises, the floating M (selectMButtonOverlayTarget reads
        // exactly these two). OR-12 (2026-09-29) moved WRITE_SETTINGS to Utility and TWEAKER_USAGE to the Tweaker,
        // where each is used; the built-in controls screen went the same day and KEYBOARD on 2026-09-17.
        assertEquals(
            listOf(
                OnboardingAccessScreenId.ORIENTATION,
                OnboardingAccessScreenId.FILES,
                // OVERLAY names the button before the following screen uses its name.
                OnboardingAccessScreenId.OVERLAY,
                OnboardingAccessScreenId.USAGE,
                OnboardingAccessScreenId.HOME_ROLE,
            ),
            onboardingAccessScreens(pending),
        )
    }
}
