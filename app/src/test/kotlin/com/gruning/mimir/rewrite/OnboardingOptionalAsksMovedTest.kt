package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * OR-12 (product review 2026-09-28, the table's pick "Defer the optional asks"): Android's "Modify
 * system settings" (Utility's brightness) and the Tweaker's usage access left onboarding. This reverses run 10
 * (2026-09-18), which had moved the brightness grant into setup. Each is asked where it is used, and each site now
 * carries the one instruction its onboarding page carried, so the rulings behind those lines still reach the player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingOptionalAsksMovedTest {
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

    private fun capabilities(
        canWriteSystemSettings: Boolean,
        manualBrightnessMode: Boolean = true,
    ) = BrightnessCapabilities(
        canWriteSystemSettings = canWriteSystemSettings,
        mainSystemBrightnessActive = true,
        secondaryWindowAvailable = false,
        secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
        joystickLightSupported = false,
        joystickLightColorSupported = false,
        mimirHapticsSupported = false,
        mimirHapticAmplitudeControl = false,
        mediaVolumeSupported = false,
        mediaVolumeFixed = false,
        // BrightnessController builds this from the brightness mode (`mainWriteAccessUseful = manualBrightnessMode`).
        mainWriteAccessUseful = manualBrightnessMode,
    )

    @Test
    fun `setup is four asks, and neither moved page is among them`() {
        val access = OnboardingAccessUi(
            usageAvailable = true,
            usageGranted = false,
            homeRoleAvailable = true,
            homeRoleHeld = false,
        )
        assertEquals(
            listOf(
                OnboardingAccessScreenId.ORIENTATION,
                OnboardingAccessScreenId.FILES,
                OnboardingAccessScreenId.OVERLAY,
                OnboardingAccessScreenId.USAGE,
                OnboardingAccessScreenId.HOME_ROLE,
            ),
            onboardingAccessScreens(access),
        )
        assertEquals(
            "4 of 4 · Home app",
            onboardingAccessProgressLabel(res, onboardingAccessScreens(access), OnboardingAccessScreenId.HOME_ROLE),
        )
        val names = OnboardingAccessScreenId.entries.map { it.name }
        assertFalse(names.contains("WRITE_SETTINGS"))
        assertFalse(names.contains("TWEAKER_USAGE"))
    }

    @Test
    fun `Utility offers Allow in manual mode only, and says it needs manual under auto`() {
        assertTrue(capabilities(canWriteSystemSettings = false).shouldOfferMainWriteAccess)
        assertFalse(capabilities(canWriteSystemSettings = true).shouldOfferMainWriteAccess)
        assertFalse(
            "under auto-brightness the grant does nothing yet, so Allow is not offered",
            capabilities(canWriteSystemSettings = false, manualBrightnessMode = false).shouldOfferMainWriteAccess,
        )
        // Manual-brightness requirement: the footer's own branch for auto, grant or no grant.
        val overlay = mainSource("BrightnessOverlay.kt")
        val autoBranch = overlay
            .substringAfter("presentedSurface == UtilitySurface.UTILITY && !state.capabilities.mainWriteAccessUseful")
            .substringBefore("} else if (presentedSurface == UtilitySurface.TWEAKER)")
        assertTrue(autoBranch, autoBranch.contains("text = UtilityAutoBrightnessMessage"))
        assertFalse("the auto branch must not offer Allow", autoBranch.contains("UtilityAction("))
        assertEquals("Turn off auto-brightness so Mimir's slider works in games.", UtilityAutoBrightnessMessage)
    }

    @Test
    fun `the Tweaker's usage row carries the instruction its onboarding page carried`() {
        assertEquals("In Android's list, pick Mimir Tweaker, not Mimir.", TweakerUsageAccessInstruction)
        val row = mainSource("BrightnessOverlay.kt")
            .substringAfter("label = \"Tweaker usage access\",")
            .substringBefore("valueLabel =")
        assertTrue(row, row.contains("TweakerUsageAccessInstruction"))
    }

    @Test
    fun `setup no longer waits on the Tweaker's usage access`() {
        // Before OR-12 a Tweaker that read SET_UP held completion open until its page was granted or deferred.
        val access = OnboardingAccessUi(
            usageAvailable = true,
            usageGranted = true,
            homeRoleAvailable = true,
            homeRoleHeld = true,
            filesGranted = true,
        )
        assertTrue(onboardingAccessComplete(access, OnboardingAccessReviewState()))
    }

    private fun mainSource(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.first(File::isFile).readText()
    }
}
