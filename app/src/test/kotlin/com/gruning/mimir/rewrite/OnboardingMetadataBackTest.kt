package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingMetadataBackTest {

    @Test
    fun metadataBackIsSwallowed() {
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        var advanced = false
        val handled = dispatchOnboarding(
            authority = authority,
            step = OnboardingStep.METADATA,
            profile = OnboardingProfileState(),
            accessReview = OnboardingAccessReviewState(),
            focusIntent = OnboardingFocusIntentState(),
            library = OnboardingLibraryState(),
            access = OnboardingAccessUi(
                usageAvailable = true,
                usageGranted = true,
                homeRoleAvailable = true,
                homeRoleHeld = true,
            ),
            artworkAccount = null,
            settlement = OnboardingSettlementUi(
                phase = OnboardingSettlementPhase.READY,
                title = "Ready",
                detail = "Ready",
            ),
            action = ControllerAction.SYSTEM_BACK,
            onAdvance = { advanced = true },
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
        )

        assertTrue(handled)
        assertFalse(advanced)
    }
}
