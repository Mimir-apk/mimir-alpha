package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A finished setup stays finished. Every checkpoint write used to store onboardingComplete = false, so a settlement
 * or scan result landing after FINISH would have sent the player back through first run on the next launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingStoreCompletionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a write after FINISH is refused and first run never comes back`() {
        assertTrue(OnboardingStore.isFirstRun(context))
        assertTrue(OnboardingStore.moveTo(context, OnboardingStep.DONE))
        assertTrue(OnboardingStore.markComplete(context))
        assertFalse(OnboardingStore.isFirstRun(context))

        assertFalse(OnboardingStore.markSettlement(context, OnboardingSettlementStatus.READY, null))
        assertFalse(OnboardingStore.markAccessOrientationSeen(context))
        assertFalse(OnboardingStore.moveTo(context, OnboardingStep.LIBRARY))
        assertFalse("still finished", OnboardingStore.isFirstRun(context))
        assertEquals(OnboardingStep.DONE, OnboardingStore.checkpoint(context).step)
    }
}
