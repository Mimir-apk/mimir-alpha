package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CompanionPermissionPromptVisualTest {
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

    // The buttons' focus is the one selection ring now (RemainderRingTest pins it); the border helpers retired.

    @Test
    fun controllerHintLabelsMoveSelectBack() {
        assertEquals(
            "Left/Right Move · A Select · B Back",
            onboardingTestResources().getString(R.string.app_overlay_prompt_hint),
        )
    }

    @Test
    fun theSpanishHintKeepsTheControllerTokensAndTranslatesTheVerbs() {
        assertEquals(
            "Izq/Der Mover · A Elegir · B Atrás",
            onboardingTestResourcesFor("es-419").getString(R.string.app_overlay_prompt_hint),
        )
    }
}
