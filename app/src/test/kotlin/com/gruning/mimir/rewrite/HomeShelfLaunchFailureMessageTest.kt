package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outcome-dropping defect-class audit (2026-08-12), Home shelf Play.
 *
 * `settleEmulatedHomeLaunch` already computed a full [LaunchResult] — readiness plus a typed dispatch
 * outcome — and the Home shelf row's failure handler discarded all of it, reporting every refusal as the
 * single line "Game didn't start". This is the same shape as the BRT-2 brightness defect (`a37be38f`): the
 * honest plumbing existed, was tested, and was thrown away one layer up. Here the honest renderer was not
 * merely present but ALREADY CONSUMED by the launch-card call site in the same file, so the shelf row and
 * the launch card described the same refusal two different ways.
 *
 * These are behavioural tests on [homeShelfLaunchFailureMessage], the pure decision. The final test is the
 * source-characterization half — a JVM test cannot drive `rememberHomeShelfCoordinator`'s @Composable
 * launch action, matching this module's own `AppBrightnessCoordinatorDeclineFeedbackTest` /
 * `GameTitleEditOverlayResetWiringTest` precedent for exactly that gap.
 */
class HomeShelfLaunchFailureMessageTest {

    @Test
    fun needsEmulatorNamesThePlatformAndRoutesToSettings() {
        assertEquals(
            "No installed emulator for PlayStation. Open Settings › Emulators to install one.",
            homeShelfLaunchFailureMessage(
                result = LaunchResult(readiness = LaunchReadiness.NeedsEmulator(PlatformId("ps1"))),
                gameId = GameId("g1"),
                platformLabelFor = { "PlayStation" },
            ),
        )
    }

    @Test
    fun missingFileTellsThePlayerToRestoreOrReconnect() {
        assertEquals(
            "Mimir can’t reach this game file. Restore it or reconnect its library folder, then try again.",
            homeShelfLaunchFailureMessage(
                result = LaunchResult(readiness = LaunchReadiness.MissingFile(GameId("g1"))),
                gameId = GameId("g1"),
                platformLabelFor = { "PlayStation" },
            ),
        )
    }

    @Test
    fun unsupportedPlatformNamesThePlatform() {
        assertEquals(
            "Mimir can’t launch Sega Saturn yet.",
            homeShelfLaunchFailureMessage(
                result = LaunchResult(readiness = LaunchReadiness.Unsupported(PlatformId("saturn"))),
                gameId = GameId("g1"),
                platformLabelFor = { "Sega Saturn" },
            ),
        )
    }

    /** A revoked folder grant is the case the generic line hid most expensively — it is actionable. */
    @Test
    fun typedDispatchFailureSurvivesToThePlayer() {
        assertEquals(
            "Mimir lost access to this file. Re-grant the folder in Settings › Library.",
            homeShelfLaunchFailureMessage(
                result = LaunchResult(
                    readiness = LaunchReadiness.Ready(launchTarget()),
                    outcome = LaunchOutcome.Failed(LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE),
                ),
                gameId = GameId("g1"),
                platformLabelFor = { "PlayStation" },
            ),
        )
    }

    @Test
    fun eachDispatchFailureGetsItsOwnLine() {
        val lines = LaunchDispatchFailure.entries.map { reason ->
            homeShelfLaunchFailureMessage(
                result = LaunchResult(
                    readiness = LaunchReadiness.Ready(launchTarget()),
                    outcome = LaunchOutcome.Failed(reason),
                ),
                gameId = GameId("g1"),
                platformLabelFor = { "PlayStation" },
            )
        }
        assertEquals(
            "every typed dispatch failure must produce a distinct line, not one collapsed string",
            LaunchDispatchFailure.entries.size,
            lines.toSet().size,
        )
        assertFalse(
            "no typed dispatch failure may fall back to the generic line",
            lines.contains(HomeShelfLaunchFailureFallback),
        )
    }

    /**
     * The return arm can fail before any dispatch is attempted, so there is genuinely no typed reason to
     * speak from. The generic line is the honest answer there — and it is the ONLY case that may reach it.
     */
    @Test
    fun noResultFallsBackToTheGenericLine() {
        assertEquals(
            HomeShelfLaunchFailureFallback,
            homeShelfLaunchFailureMessage(
                result = null,
                gameId = GameId("g1"),
                platformLabelFor = { "PlayStation" },
            ),
        )
    }

    /**
     * The differential: revert the Home shelf row's `onFailureToast` to emitting the bare
     * [HomeShelfLaunchFailureFallback] and this fails — the typed result stops reaching the message.
     */
    @Test
    fun homeShelfRowRoutesItsLaunchFailureThroughTheTypedMessage() {
        val source = homeShelfCoordinatorSource()

        assertTrue(
            "settleEmulatedHomeLaunch must hand its LaunchResult to the failure reporter",
            source.contains("onFailureToast?.invoke(launchResult)"),
        )
        assertTrue(
            "the Home shelf row's launch failure must be built from the typed result",
            source.contains("homeShelfLaunchFailureMessage("),
        )
        assertFalse(
            "no launch call site may go back to reporting a bare literal for every refusal",
            source.contains("emitMessage(MimirMessageClass.Warning, \"Game didn’t start\")"),
        )
    }

    private fun launchTarget(): LaunchTarget = LaunchTarget(
        gameId = GameId("g1"),
        platformId = PlatformId("ps1"),
        emulator = EmulatorProfile(
            id = EmulatorId("duckstation"),
            packageName = "com.github.stenzek.duckstation",
            displayName = "DuckStation",
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.CONTENT_URI,
            supportedPlatforms = setOf(PlatformId("ps1")),
        ),
        locator = "content://mimir/g1",
        locatorForm = LaunchLocatorForm.CONTENT_URI,
        title = "Resident Evil 2",
    )

    private fun homeShelfCoordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("HomeShelfCoordinator.kt not found from ${File(".").absolutePath}")
    }
}
