package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release UX/UI gate, BRT-2 (charter §3.4, 2026-08-12): [BrightnessController]'s declined-write outcomes
 * (`WriteFailed`/`Unsupported`) used to be discarded at every one of [AppBrightnessCoordinator]'s four write
 * call sites -- only `.state` survived, so a declined write and a successful one were structurally
 * indistinguishable to the player. [brightnessWriteDeclineMessage] is the pure decision (pinned directly
 * below); [reportBrightnessOutcomeWiringIsPresentAtEveryWriteCallSite] is the source-characterization half --
 * a JVM test cannot drive `AppBrightnessCoordinator.setFraction`/`step` through a real, privileged
 * [BrightnessController] without a vendor-gateway/Robolectric harness this module does not have (matches the
 * project's own `GameTitleEditOverlayResetWiringTest`/`homeLaunchCardSource()` precedent for exactly this
 * gap), so the wiring is pinned by asserting the four call sites route through [reportBrightnessOutcome]
 * rather than reverting to a bare `.state` extraction -- reverting only that wiring turns this test red.
 */
class AppBrightnessCoordinatorDeclineFeedbackTest {

    @Test
    fun writeFailedReportsChangeDidNotApply() {
        assertEquals(
            "CHANGE DID NOT APPLY",
            brightnessWriteDeclineMessage(BrightnessUpdateOutcome.WriteFailed(fakeBrightnessState())),
        )
    }

    @Test
    fun unsupportedReportsNotSupportedOnThisDevice() {
        assertEquals(
            "NOT SUPPORTED ON THIS DEVICE",
            brightnessWriteDeclineMessage(BrightnessUpdateOutcome.Unsupported(fakeBrightnessState())),
        )
    }

    @Test
    fun appliedReportsNothing() {
        assertNull(
            brightnessWriteDeclineMessage(
                BrightnessUpdateOutcome.Applied(fakeBrightnessState(), windowApplied = true, systemApplied = true),
            ),
        )
    }

    /**
     * PRIMARY's access-denied case already has a standing explanation (`shouldOfferMainWriteAccess`'s
     * message row plus the Allow button) -- this finding deliberately does not duplicate that with a toast,
     * and the right feedback for the other targets is an unresolved design decision, not guessed here.
     */
    @Test
    fun writeAccessRequiredReportsNothingHereByDesign() {
        assertNull(
            brightnessWriteDeclineMessage(
                BrightnessUpdateOutcome.WriteAccessRequired(
                    fakeBrightnessState(),
                    windowApplied = false,
                    request = BrightnessPermissionRequest(
                        action = "android.settings.action.MANAGE_WRITE_SETTINGS",
                        dataUri = "package:com.gruning.mimir.rewrite",
                    ),
                ),
            ),
        )
    }

    /**
     * The differential: revert `setFraction()`/`step()`/`enqueuePrivilegedFraction()`/
     * `enqueuePrivilegedStep()` back to discarding the outcome via a bare `.state` extraction and this
     * fails -- `reportBrightnessOutcome(` stops appearing at each of the four call sites.
     */
    @Test
    fun reportBrightnessOutcomeWiringIsPresentAtEveryWriteCallSite() {
        val source = appBrightnessCoordinatorSource()

        assertTrue(
            "setFraction() must route its non-privileged write through reportBrightnessOutcome",
            source.contains("reportBrightnessOutcome(activeController.setFraction(target, fraction))"),
        )
        assertTrue(
            "step() must route its non-privileged write through reportBrightnessOutcome",
            source.contains("reportBrightnessOutcome(activeController.step(target, direction))"),
        )
        assertTrue(
            "enqueuePrivilegedFraction() must route its settled outcome through reportBrightnessOutcome",
            source.contains(
                "if (privilegedFractionGenerations[target] == generation) reportBrightnessOutcome(outcome)",
            ),
        )
        assertTrue(
            "enqueuePrivilegedStep() must route its settled outcome through reportBrightnessOutcome",
            source.contains("reportBrightnessOutcome(outcome)\n                }"),
        )
        assertFalse(
            "no write call site may go back to discarding the outcome via a bare .state extraction",
            source.contains("activeController.setFraction(target, fraction).state") ||
                source.contains("activeController.step(target, direction).state"),
        )
    }

    private fun fakeBrightnessState(): BrightnessState = BrightnessState(
        capabilities = BrightnessCapabilities(
            canWriteSystemSettings = true,
            mainSystemBrightnessActive = true,
            secondaryWindowAvailable = false,
            secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
            joystickLightSupported = false,
            joystickLightColorSupported = false,
            mimirHapticsSupported = false,
            mimirHapticAmplitudeControl = false,
            mediaVolumeSupported = false,
            mediaVolumeFixed = false,
        ),
        primaryFraction = 0.5f,
        primaryWindowFallback = null,
        secondaryFraction = null,
        secondaryWindowFallback = null,
        joystickLightFraction = null,
        joystickColorFraction = null,
        joystickColorArgb = null,
        themeAwareJoystickLight = false,
        mimirHapticFraction = null,
        mediaVolumeFraction = null,
    )

    private fun appBrightnessCoordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("AppBrightnessCoordinator.kt not found from ${File(".").absolutePath}")
    }
}
