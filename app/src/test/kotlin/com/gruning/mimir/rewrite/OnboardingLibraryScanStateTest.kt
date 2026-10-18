package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLibraryScanStateTest {

    @Test
    fun healthyScanUnlocksContinue() {
        val state = OnboardingLibraryState().apply {
            granted = true
            status = OnboardingLibraryStatus.SCANNING
            failureReason = "stale"
        }

        applyOnboardingScanOutcome(state, ScanOutcome.Healthy)

        assertEquals(OnboardingLibraryStatus.DONE, state.status)
        assertTrue(state.granted)
        assertNull(state.failureReason)
    }

    @Test
    fun healthyScanWithoutTheCurrentFlowGrantRemainsFailed() {
        val state = OnboardingLibraryState().apply {
            granted = false
            status = OnboardingLibraryStatus.SCANNING
        }

        applyOnboardingScanOutcome(state, ScanOutcome.Healthy)

        assertEquals(OnboardingLibraryStatus.FAILED, state.status)
        assertFalse(state.granted)
    }

    @Test
    fun failedScanRevokesCompletionAndStaysRetryable() {
        val state = OnboardingLibraryState().apply {
            granted = true
            status = OnboardingLibraryStatus.SCANNING
        }

        applyOnboardingScanOutcome(state, ScanOutcome.Failed("tree is unreadable"))

        assertEquals(OnboardingLibraryStatus.FAILED, state.status)
        assertFalse(state.granted)
        assertEquals("tree is unreadable", state.failureReason)
    }
}
