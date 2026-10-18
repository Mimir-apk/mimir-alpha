package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that replaced `setRequiresDeviceIdle` on the background re-verification worker.
 *
 * Measured on the Thor (2026-09-17) with the job queued and the device plugged in, the OS constraint read
 * `Unsatisfied constraints: IDLE` and `Ready: false`, and had done for seventeen minutes — because Mimir
 * runs as the Home app, so it is permanently the top app and the device never reaches JobScheduler's idle
 * state. The pass would never have executed on the one device it was written for, while every unit test
 * passed and the log cheerfully reported 63 games waiting.
 *
 * These tests exist so the replacement cannot rot into the same thing. A gate that never opens is
 * indistinguishable from a working feature unless something asserts on when it opens.
 */
class IdentityReverificationGateTest {

    private fun gameSession(phase: MimirSessionPhase): MimirGameSession {
        val gameId = GameId("chrono")
        return MimirGameSession(
            operation = MimirGameSessionOperation("game:chrono"),
            sequence = 1L,
            gameId = gameId,
            platformId = PlatformId("ps1"),
            returnAnchor = MimirGameReturnAnchor.Home(
                HomeLaunchReturnTarget(HomeShelfItemId.Emulated(gameId), rank = 1L),
            ),
            startedAtEpochMs = 1L,
            placement = MimirGamePlacement.MAIN,
            dualScreen = false,
            phase = phase,
        )
    }

    private fun appSession(phase: MimirSessionPhase): MimirAppSession = MimirAppSession(
        operationId = "app:1",
        sequence = 1L,
        packageName = "com.example.app",
        label = "Example",
        placement = MimirAppPlacement.PRIMARY,
        returnAnchor = PrimaryReturnOrigin(destination = MimirDestination.LIBRARY.name),
        startedAtEpochMs = 1L,
        phase = phase,
    )

    @Test
    fun `an idle Mimir with nothing presenting may read`() {
        assertTrue(reverificationMayRun(MimirSessionSnapshot()))
    }

    /**
     * The whole point: while a game is on screen the SD card belongs to that game. This is the condition
     * `IDLE` was reaching for, stated in terms Mimir can actually observe about itself.
     */
    @Test
    fun `no phase in which a game is presenting allows a read`() {
        ReverificationBlockingPhases.forEach { phase ->
            assertFalse(
                "a game in $phase must stop background hashing",
                reverificationMayRun(MimirSessionSnapshot(gameSession = gameSession(phase))),
            )
        }
    }

    /**
     * A session that has YIELDED has handed the screen back, and must not hold this gate.
     *
     * Caught on the Thor 2026-09-18. The gate tested [ActivePresentationPhases] -- the LAUNCH MUTEX set,
     * per that set's own KDoc -- which contains YIELDED_TO_SECONDARY_TASK. The system file picker that
     * onboarding opens for the folder grant yields a third of a second after it opens and is not walked to
     * ENDED until Mimir returns to the foreground; it held this gate for 100 seconds and cost two
     * scheduled runs, for a picker the player had already dismissed.
     *
     * The bigger case is the one this class's own doc promised from the start: a PARKED game is also
     * YIELDED_TO_SECONDARY_TASK, and on this device it can outlive a charge cycle. Under the old set,
     * parking a game closed this gate for as long as it stayed parked -- `setRequiresDeviceIdle`'s exact
     * failure, one layer up and in different words.
     */
    @Test
    fun `a yielded session does not block, because nothing is on screen`() {
        assertTrue(
            "a parked game is the case this gate was documented to allow",
            reverificationMayRun(
                MimirSessionSnapshot(
                    gameSession = gameSession(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK),
                ),
            ),
        )
        assertTrue(
            "and a dismissed file picker is the same shape",
            reverificationMayRun(
                MimirSessionSnapshot(
                    secondaryAppSession = appSession(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK),
                ),
            ),
        )
        assertFalse(
            "YIELDED is the ONLY phase this removes -- the rest of the launch-mutex set still blocks",
            ReverificationBlockingPhases.contains(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK),
        )
        assertEquals(
            "and it removes exactly one",
            ActivePresentationPhases.size - 1,
            ReverificationBlockingPhases.size,
        )
    }

    /**
     * The other half, and the half that would quietly rebuild the never-opening gate if it were wrong: a
     * session that has ENDED, FAILED or gone STALE is not a session. If those blocked too, one crashed
     * emulator would retire the guard until the next reboot.
     */
    @Test
    fun `a finished or failed session does not block`() {
        listOf(MimirSessionPhase.ENDED, MimirSessionPhase.FAILED, MimirSessionPhase.STALE)
            .forEach { phase ->
                assertTrue(
                    "$phase is not a live session",
                    reverificationMayRun(MimirSessionSnapshot(gameSession = gameSession(phase))),
                )
            }
    }

    /** An Android app the player launched is just as much "in use" as a game. */
    @Test
    fun `a presenting app session also blocks`() {
        assertFalse(
            reverificationMayRun(
                MimirSessionSnapshot(
                    primaryAppSession = appSession(MimirSessionPhase.FOREGROUND_PROVEN),
                ),
            ),
        )
        assertFalse(
            reverificationMayRun(
                MimirSessionSnapshot(
                    secondaryAppSession = appSession(MimirSessionPhase.FOREGROUND_PROVEN),
                ),
            ),
        )
    }
}
