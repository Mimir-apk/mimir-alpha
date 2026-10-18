package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/** OR-15 (product review 2026-09-28, ): RetroAchievements on the accounts step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingRetroAchievementsRowTest {
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
    fun `the row sits after the two art sources and before Continue`() {
        assertEquals(
            listOf(
                OnboardingArtworkSetupKey,
                OnboardingSteamGridDbKey,
                OnboardingRetroAchievementsKey,
                OnboardingMetadataContinueKey,
            ),
            onboardingMetadataFocusKeys(artworkAccountConnected = false),
        )
        assertTrue(OnboardingRetroAchievementsKey in onboardingMetadataFocusKeys(artworkAccountConnected = true))
    }

    @Test
    fun `A on the row opens the RetroAchievements connect overlay`() {
        // With ScreenScraper connected the step opens on SteamGridDB; one move down reaches the new row.
        var opened = 0
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        val focusIntent = OnboardingFocusIntentState()
        fun press(action: ControllerAction) = dispatchOnboarding(
            authority = authority,
            step = OnboardingStep.METADATA,
            profile = OnboardingProfileState(),
            accessReview = OnboardingAccessReviewState(),
            focusIntent = focusIntent,
            library = OnboardingLibraryState(),
            access = OnboardingAccessUi(
                usageAvailable = true,
                usageGranted = true,
                homeRoleAvailable = true,
                homeRoleHeld = true,
            ),
            artworkAccount = "someone",
            settlement = OnboardingSettlementUi(
                phase = OnboardingSettlementPhase.READY,
                title = "Ready",
                detail = "Ready",
            ),
            action = action,
            onAdvance = {},
            onCommitName = {},
            onRequestFilesAccess = {},
            onRequestUsageAccess = {},
            onRequestHomeRole = {},
            onChooseFolder = {},
            onOpenArtworkSetup = {},
            onOpenSteamGridDbKey = {},
            onRetrySettlement = {},
            onContinueLocally = {},
            onApplyTheme = {},
            onOpenRetroAchievements = { opened++ },
            accessOrientationSeen = true,
        )
        press(ControllerAction.NAV_DOWN)
        assertTrue(press(ControllerAction.CONFIRM))
        assertEquals(1, opened)
    }

    @Test
    fun `the row's words follow the account`() {
        val none = onboardingRetroAchievementsCopy(res, user = null, keyRejected = false)
        assertEquals("Connect", none.action)
        assertNull(none.status)
        assertTrue(none.detail, none.detail.contains("RetroAchievements key"))
        val connected = onboardingRetroAchievementsCopy(res, user = "Player", keyRejected = false)
        assertEquals("Connected", connected.status)
        assertEquals("Progress will be read for Player.", connected.detail)
        val rejected = onboardingRetroAchievementsCopy(res, user = "Player", keyRejected = true)
        assertNull(rejected.status)
        assertEquals("Update", rejected.action)
    }
}
