package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cert 2026-10-08 F1 and F2, on the real session ledger: which launches end as "closed unexpectedly", and which never do.
 * The heuristic is documented on [LaunchEarlyClose].
 */
class LaunchEarlyCloseTest {
    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
    )

    private fun result(game: GameRow, displayName: String = "DuckStation") = LaunchResult(
        readiness = LaunchReadiness.Ready(
            LaunchTarget(
                gameId = game.gameId,
                platformId = game.platformId,
                emulator = EmulatorProfile(
                    id = EmulatorId("duckstation"),
                    packageName = "com.github.stenzek.duckstation",
                    displayName = displayName,
                    installed = true,
                    capability = EmulatorCapability.DIRECT_PAYLOAD,
                    locatorForm = LaunchLocatorForm.FILE_PATH,
                    supportedPlatforms = setOf(game.platformId),
                ),
                locator = "/storage/emulated/0/Roms/${game.gameId.value}.chd",
                locatorForm = LaunchLocatorForm.FILE_PATH,
                title = game.title,
            ),
        ),
        outcome = LaunchOutcome.IntentSent,
    )

    private class Launched(
        val coordinator: MimirSessionCoordinator,
        val operation: MimirGameSessionOperation,
        val closedEarly: MutableList<MimirGameSession>,
        val yielded: MutableList<MimirGameSession>,
    )

    private fun dispatched(displayName: String = "DuckStation"): Launched {
        val coordinator = MimirSessionCoordinator()
        val closedEarly = mutableListOf<MimirGameSession>()
        val yielded = mutableListOf<MimirGameSession>()
        coordinator.onEmulatorClosedEarly = closedEarly::add
        coordinator.onGameYielded = yielded::add
        val game = game("xenogears")
        val operation = requireNotNull(
            coordinator.prepareHomeGame(game, HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 1L), startedAtEpochMs = 1_000L),
        )
        assertTrue(coordinator.gameSettled(operation, game.gameId, result(game, displayName)))
        return Launched(coordinator, operation, closedEarly, yielded)
    }

    // ---- crash side: the emulator never took the window -----------------------------------------------------------------

    @Test
    fun anEmulatorThatNeverTookTheWindowIsAnUnexpectedCloseAndTheLaunchStaysDispatched() {
        val launched = dispatched()
        val cleanup = requireNotNull(
            launched.coordinator.gameDispatchWatchdogExpired(launched.operation, "com.github.stenzek.duckstation"),
        )
        assertTrue("nothing is said until the cleanup has finished", launched.closedEarly.isEmpty())

        assertTrue(launched.coordinator.gameDispatchWatchdogCleanupComplete(cleanup.operation, cleanup.expectedPackageName))

        val session = launched.closedEarly.single()
        assertTrue(LaunchEarlyClose.isUnexpectedClose(session))
        assertEquals(LaunchEarlyClose.NeverLeftPrimary, session.failureReason)
        assertEquals("the intent was sent, so the launch is dispatched history", true, session.dispatchedAtEpochMs != null)
        assertEquals("DuckStation", session.emulatorLabel)
        assertEquals(
            "DuckStation closed unexpectedly. The game may not be supported, or the emulator needs setup.",
            LaunchEarlyClose.message(session),
        )
    }

    @Test
    fun theWatchdogsDefaultReasonIsTheOneTheHeuristicReads() {
        val launched = dispatched()
        launched.coordinator.gameDispatchWatchdogExpired(launched.operation, "com.github.stenzek.duckstation")
        assertEquals(LaunchEarlyClose.NeverLeftPrimary, launched.coordinator.state.value.gameSession?.failureReason)
    }

    // ---- quit side: the player saw the emulator and left it --------------------------------------------------------------

    @Test
    fun aPlayerWhoQuitsAfterAboutASecondNeverSeesTheMessage() {
        val launched = dispatched()
        // Mimir was covered, so MainActivity.onStop yielded the session...
        assertTrue(launched.coordinator.gameYielded(launched.operation))
        // ...and the watchdog can no longer expire it, however long the player stayed.
        assertNull(launched.coordinator.gameDispatchWatchdogExpired(launched.operation, "com.github.stenzek.duckstation"))

        // The return the ledger files for a one-second stay is `launch_foreground_span_under_threshold`: that reason is NOT
        // the crash signal, because it fires on exactly this case.
        assertNotNull(launched.coordinator.beginGameReturn(launched.operation))
        assertTrue(
            launched.coordinator.completeGameReturn(
                launched.operation,
                foregroundProven = true,
                failureReason = "launch_foreground_span_under_threshold",
            ),
        )

        val session = requireNotNull(launched.coordinator.state.value.gameSession)
        assertEquals(MimirSessionPhase.FAILED, session.phase)
        assertFalse("a quick quit is not a crash", LaunchEarlyClose.isUnexpectedClose(session))
        assertTrue(launched.closedEarly.isEmpty())
    }

    @Test
    fun anOrdinaryReturnAndAnOrdinaryFailedReturnAreNotUnexpectedCloses() {
        val ended = dispatched()
        ended.coordinator.gameYielded(ended.operation)
        ended.coordinator.beginGameReturn(ended.operation)
        ended.coordinator.completeGameReturn(ended.operation, foregroundProven = true)
        assertFalse(LaunchEarlyClose.isUnexpectedClose(requireNotNull(ended.coordinator.state.value.gameSession)))

        // Every other terminal reason the ledger knows is not this one.
        val session = requireNotNull(ended.coordinator.state.value.gameSession)
        listOf("launch_never_foregrounded", "launch_foreground_span_under_threshold", "ROM_PATH_MISSING", null).forEach { reason ->
            assertFalse(
                "reason $reason",
                LaunchEarlyClose.isUnexpectedClose(session.copy(phase = MimirSessionPhase.FAILED, failureReason = reason)),
            )
        }
        // And the right reason on a launch that was never dispatched is not one either.
        assertFalse(
            LaunchEarlyClose.isUnexpectedClose(
                session.copy(
                    phase = MimirSessionPhase.FAILED,
                    failureReason = LaunchEarlyClose.NeverLeftPrimary,
                    dispatchedAtEpochMs = null,
                ),
            ),
        )
    }

    @Test
    fun aSlowStartThatYieldsLateNeverExpires() {
        // The cert's 8 s stub: its splash covers Mimir for nine seconds, then onStop yields. Slow is not closed.
        val launched = dispatched()
        assertTrue(launched.coordinator.gameYielded(launched.operation))
        assertEquals(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK, launched.coordinator.state.value.gameSession?.phase)
        assertNull(launched.coordinator.gameDispatchWatchdogExpired(launched.operation, "com.github.stenzek.duckstation"))
        assertTrue(launched.closedEarly.isEmpty())
    }

    // ---- F2: "Starting <Emulator>…" ends on the yield ---------------------------------------------------------------------

    @Test
    fun theYieldIsToldOnceAndOnlyOnTheDispatchedToYieldedEdge() {
        val launched = dispatched(displayName = "RetroArch 64 / mGBA")
        assertTrue(launched.coordinator.gameYielded(launched.operation))
        assertEquals("RetroArch 64", launched.yielded.single().emulatorLabel)

        assertFalse("a repeated yield has no edge", launched.coordinator.gameYielded(launched.operation))
        assertEquals(1, launched.yielded.size)
    }

    @Test
    fun aYieldThatLostTheRaceToTheWatchdogIsNotTold() {
        val launched = dispatched()
        launched.coordinator.gameDispatchWatchdogExpired(launched.operation, "com.github.stenzek.duckstation")
        assertFalse(launched.coordinator.gameYielded(launched.operation))
        assertTrue(launched.yielded.isEmpty())
    }
}
