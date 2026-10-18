package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchTargetReapResult
import com.gruning.mimir.rewrite.feature.library.LibraryGameLaunchOperation
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDispatchWatchdogTest {
    @Test
    fun `elapsed realtime deadline preserves grace after a slept-device relaunch`() {
        assertEquals(
            2_973L,
            remainingGameDispatchWatchdogDelayMs(
                dispatchedAtElapsedMs = 100_000L,
                nowElapsedMs = 100_027L,
                graceMs = 3_000L,
            ),
        )
        assertEquals(
            0L,
            remainingGameDispatchWatchdogDelayMs(
                dispatchedAtElapsedMs = 100_000L,
                nowElapsedMs = 103_001L,
                graceMs = 3_000L,
            ),
        )
        assertEquals(
            3_000L,
            remainingGameDispatchWatchdogDelayMs(
                dispatchedAtElapsedMs = null,
                nowElapsedMs = 400_000L,
                graceMs = 3_000L,
            ),
        )
        assertEquals(
            3_000L,
            remainingGameDispatchWatchdogDelayMs(
                dispatchedAtElapsedMs = 400_100L,
                nowElapsedMs = 400_000L,
                graceMs = 3_000L,
            ),
        )
    }

    @Test
    fun `timer recovery focus recheck and cleanup execute in order`() = runBlocking {
        val operation = MimirGameSessionOperation("home:integrated-watchdog")
        var live = dispatchedSession(operation, "com.retroarch")
        val events = mutableListOf<String>()

        val result = runGameDispatchWatchdog(
            delayMs = 1L,
            expectedOperation = operation,
            expectedPackage = "com.retroarch",
            currentSession = {
                events += "session"
                live
            },
            primaryHostOwnsWindow = {
                events += "focus"
                true
            },
            recoverTarget = {
                events += "recover"
                LaunchTargetReapResult.REQUEST_ACCEPTED
            },
            expireSession = {
                events += "expire"
                live = live.copy(phase = MimirSessionPhase.RECOVERING)
                cleanup(operation, "com.retroarch")
            },
            applyCleanup = {
                events += "cleanup"
            },
            completeCleanup = {
                events += "complete"
                live = live.copy(phase = MimirSessionPhase.FAILED)
            },
        )

        assertEquals(LaunchTargetReapResult.REQUEST_ACCEPTED, result.reap)
        assertTrue(result.cleanupApplied)
        assertEquals(
            listOf("session", "focus", "expire", "recover", "cleanup", "complete"),
            events,
        )
    }

    @Test
    fun `bounded recovery still unlocks exact failed launch`() = runBlocking {
        val operation = MimirGameSessionOperation("library:bounded-watchdog")
        var live = dispatchedSession(operation, "com.retroarch")
        var cleaned = false

        val result = runGameDispatchWatchdog(
            delayMs = 0L,
            recoveryDeadlineMs = 25L,
            expectedOperation = operation,
            expectedPackage = "com.retroarch",
            currentSession = { live },
            primaryHostOwnsWindow = { true },
            recoverTarget = { awaitCancellation() },
            expireSession = {
                live = live.copy(phase = MimirSessionPhase.RECOVERING)
                cleanup(operation, "com.retroarch")
            },
            applyCleanup = { cleaned = true },
            completeCleanup = { live = live.copy(phase = MimirSessionPhase.FAILED) },
        )

        assertEquals(LaunchTargetReapResult.FAILED, result.reap)
        assertTrue(result.cleanupApplied)
        assertTrue(cleaned)
        assertEquals(MimirSessionPhase.FAILED, live.phase)
    }

    @Test
    fun `normal return before recovery claim cannot reap or clean the target`() = runBlocking {
        val operation = MimirGameSessionOperation("home:return-wins")
        var live = dispatchedSession(operation, "com.retroarch")
        var expired = false
        var recovered = false
        var cleaned = false

        val result = runGameDispatchWatchdog(
            delayMs = 0L,
            expectedOperation = operation,
            expectedPackage = "com.retroarch",
            currentSession = { live },
            primaryHostOwnsWindow = {
                live = live.copy(phase = MimirSessionPhase.RETURNING)
                false
            },
            recoverTarget = {
                recovered = true
                LaunchTargetReapResult.REQUEST_ACCEPTED
            },
            expireSession = {
                expired = true
                cleanup(operation, "com.retroarch")
            },
            applyCleanup = { cleaned = true },
            completeCleanup = { error("normal return must not complete watchdog cleanup") },
        )

        assertEquals(null, result.reap)
        assertFalse(result.cleanupApplied)
        assertFalse(expired)
        assertFalse(recovered)
        assertFalse(cleaned)
        assertEquals(MimirSessionPhase.RETURNING, live.phase)
    }

    @Test
    fun `throwing library projection cleanup is contained`() {
        assertFalse(
            applyLibraryDispatchCleanupSafely(LibraryGameLaunchOperation(9L)) {
                error("projection failed")
            },
        )
    }

    @Test
    fun `throwing process cleanup still completes semantic recovery`() = runBlocking {
        val operation = MimirGameSessionOperation("library:cleanup-throws")
        var live = dispatchedSession(operation, "com.retroarch")
        var completed = false

        val result = runGameDispatchWatchdog(
            delayMs = 0L,
            expectedOperation = operation,
            expectedPackage = "com.retroarch",
            currentSession = { live },
            primaryHostOwnsWindow = { true },
            recoverTarget = { LaunchTargetReapResult.REQUEST_ACCEPTED },
            expireSession = {
                live = live.copy(phase = MimirSessionPhase.RECOVERING)
                cleanup(operation, "com.retroarch")
            },
            applyCleanup = { error("projection failed") },
            completeCleanup = {
                completed = true
                live = live.copy(phase = MimirSessionPhase.FAILED)
            },
        )

        assertEquals(LaunchTargetReapResult.REQUEST_ACCEPTED, result.reap)
        assertFalse(result.cleanupApplied)
        assertTrue(completed)
        assertEquals(MimirSessionPhase.FAILED, live.phase)
    }

    private fun dispatchedSession(
        operation: MimirGameSessionOperation,
        packageName: String,
    ): MimirGameSession = MimirGameSession(
        operation = operation,
        sequence = 1L,
        gameId = com.gruning.mimir.rewrite.core.model.GameId("watchdog-game"),
        platformId = com.gruning.mimir.rewrite.core.model.PlatformId("snes"),
        returnAnchor = MimirGameReturnAnchor.Home(
            com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget(
                itemId = com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId.Emulated(
                    com.gruning.mimir.rewrite.core.model.GameId("watchdog-game"),
                ),
                rank = 1L,
            ),
        ),
        startedAtEpochMs = 1L,
        dispatchedAtEpochMs = 2L,
        dispatchedAtElapsedMs = 3L,
        targetPackageName = packageName,
        dualScreen = false,
        phase = MimirSessionPhase.DISPATCHED,
    )

    private fun cleanup(
        operation: MimirGameSessionOperation,
        packageName: String,
    ) = MimirGameDispatchCleanup(
        operation = operation,
        expectedPackageName = packageName,
        homeReturnHandle = null,
        libraryOperation = null,
        dualScreen = false,
    )
}
