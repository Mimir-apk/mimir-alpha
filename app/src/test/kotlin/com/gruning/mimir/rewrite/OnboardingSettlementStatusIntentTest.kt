package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * Pins [onboardingSettlementStatusIntent] -- the settling screen's status line copy. Policy
 * 2026-08-20 ruled the shape "N of M checked" and required the false "Working · exact total unavailable"
 * tail gone; this class pins both without needing Compose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingSettlementStatusIntentTest {
    /**
     * This class runs in the shared sdk 34 Robolectric sandbox, and the copy it reads can write Compose snapshot state outside any
     * composition. Apply those writes and run what they posted, or a later Compose test in the sandbox fails "Compose did not get
     * idle" (CompanionThemeSeedTest, MimirTrackpadPointerStyleTest).
     */
    @After
    fun applyPendingSnapshotWrites() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    private val res = onboardingTestResources()

    @Test
    fun `a real total renders the N of M checked shape`() {
        val intent = onboardingSettlementStatusIntent(res, done = 3, total = 10)
        assertEquals(
            MimirTextIntent.Mixed(
                listOf(
                    MimirTextFragment.Authored("3"),
                    MimirTextFragment.Chrome(" of "),
                    MimirTextFragment.Authored("10"),
                    MimirTextFragment.Chrome(" checked"),
                ),
            ),
            intent,
        )
    }

    @Test
    fun `null total falls back to a plain Working with no caveat`() {
        val intent = onboardingSettlementStatusIntent(res, done = 0, total = null)
        assertEquals(MimirTextIntent.Mixed(listOf(MimirTextFragment.Chrome("Working"))), intent)
    }

    @Test
    fun `zero total is treated the same as no total -- never a 0 of 0 fraction`() {
        val intent = onboardingSettlementStatusIntent(res, done = 0, total = 0)
        assertEquals(MimirTextIntent.Mixed(listOf(MimirTextFragment.Chrome("Working"))), intent)
    }

    @Test
    fun `the false exact-total-unavailable tail is gone in every branch`() {
        val allTexts = listOf(
            onboardingSettlementStatusIntent(res, done = 0, total = null),
            onboardingSettlementStatusIntent(res, done = 0, total = 0),
            onboardingSettlementStatusIntent(res, done = 3, total = 10),
        ).flatMap { intent -> (intent as MimirTextIntent.Mixed).fragments }
            .joinToString("") { fragment ->
                when (fragment) {
                    is MimirTextFragment.Chrome -> fragment.text
                    is MimirTextFragment.Authored -> fragment.text
                }
            }
        assertFalse(allTexts.contains("exact total unavailable"))
        assertFalse(allTexts.contains("processed"))
    }
}
