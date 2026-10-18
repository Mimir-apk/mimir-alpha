package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainGameParkingStateTest {
    @Before
    @After
    fun clearProcessLease() {
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
    }

    @Test
    fun `same exact lease is reused while a replacement cannot overwrite it`() {
        val first = session()
        val replacement = session(operation = "game:replacement", gameId = "replacement")

        assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.arm(first, 4052))
        val lease = requireNotNull(MainGameParkingState.currentFor(first))
        assertEquals(MainGameParkingArmResult.ALREADY_ARMED, MainGameParkingState.arm(first, 4052))
        assertEquals(MainGameParkingArmResult.REPLACEMENT_ACTIVE, MainGameParkingState.arm(replacement, 4053))
        assertNull(MainGameParkingState.currentFor(replacement))
        assertEquals(4052, lease.taskId)

        assertFalse(MainGameParkingState.clear(lease.copy(taskId = 4053)))
        assertTrue(MainGameParkingState.clear(lease))
        assertNull(MainGameParkingState.current())
    }

    @Test
    fun `parking rejects invalid tasks and non dual-screen or non main sessions`() {
        val game = session()

        assertEquals(MainGameParkingArmResult.INVALID_TASK, MainGameParkingState.arm(game, 0))
        assertEquals(MainGameParkingArmResult.INVALID_TASK, MainGameParkingState.arm(game, -1))
        assertEquals(
            MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME,
            MainGameParkingState.arm(game.copy(placement = MimirGamePlacement.BOTTOM), 4052),
        )
        assertEquals(
            MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME,
            MainGameParkingState.arm(game.copy(dualScreen = false), 4052),
        )
    }

    @Test
    fun `a DS game parks like a 3DS one`() {
        //  dualScreen is computed for DS and THREE_DS
        // alike (MimirSessionCoordinator.settleHomeGame); the parking gate reads that, not the platform.
        val ds = session().copy(platformId = PlatformId.DS)

        assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.arm(ds, 4052))
        assertEquals(4052, MainGameParkingState.currentFor(ds)?.taskId)
    }

    @Test
    fun `restoring is exact and a failed focus can cancel it without losing the lease`() {
        val game = session()
        assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.arm(game, 4052))
        val lease = requireNotNull(MainGameParkingState.currentFor(game))

        assertTrue(MainGameParkingState.beginRestoring(lease))
        assertTrue(MainGameParkingState.isRestoring(lease))
        assertTrue(MainGameParkingState.cancelRestoring(lease))
        assertFalse(MainGameParkingState.isRestoring(lease))
        assertEquals(lease, MainGameParkingState.currentFor(game))

        assertTrue(MainGameParkingState.beginRestoring(lease))
        assertTrue(MainGameParkingState.clearRestoringOnPrimaryStop(lease.operation))
        assertNull(MainGameParkingState.current())
        assertFalse(MainGameParkingState.clearRestoringOnPrimaryStop(lease.operation))
    }

    @Test
    fun `current getter requires every session identity field`() {
        val game = session()
        assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.arm(game, 4052))

        assertNotNull(MainGameParkingState.currentFor(game))
        assertNull(MainGameParkingState.currentFor(game.copy(gameId = GameId("other"))))
        assertNull(MainGameParkingState.currentFor(game.copy(targetPackageName = "com.other.game")))
        assertNull(MainGameParkingState.currentFor(game.copy(operation = MimirGameSessionOperation("game:other"))))
    }

    // Thor T1-T3: Lime3DS's own Close Game finishes the
    // activity without killing the process, so the old "task gone AND process ABSENT" bar kept a stale
    // lease -- and every later PLAY -- stuck after a proper close reached through Android Recents. A
    // positive NoTask answer alone is the proof now; liveness is logged, never a second gate.
    @Test
    fun `a positively taskless lease is proven gone regardless of the cached process state`() {
        assertTrue(CompanionProcessRuntime.parkedGameProvenGone(VendorTaskOutcome.NoTask, PrivilegedPackageLiveness.ALIVE))
        assertTrue(CompanionProcessRuntime.parkedGameProvenGone(VendorTaskOutcome.NoTask, PrivilegedPackageLiveness.ABSENT))
        assertTrue(CompanionProcessRuntime.parkedGameProvenGone(VendorTaskOutcome.NoTask, PrivilegedPackageLiveness.UNKNOWN))
    }

    @Test
    fun `a found task is never proven gone`() {
        assertFalse(CompanionProcessRuntime.parkedGameProvenGone(VendorTaskOutcome.Found(4170), PrivilegedPackageLiveness.ABSENT))
        assertFalse(CompanionProcessRuntime.parkedGameProvenGone(VendorTaskOutcome.Found(4170), PrivilegedPackageLiveness.ALIVE))
    }

    // R6-P0-02 review fix (BLOCKING): a probe that could not answer -- policy declined, posture not ready, a
    // timed-out or failed transaction, or an unparseable reply -- must retain exactly like a live task does.
    // Thor finding this closes: a PServer timeout landed mid-PLAY-press on a genuinely alive parked Lime3DS;
    // the old bar (`taskId == null`) read that timeout's null exactly like a positive "no task" answer and
    // ended the session, so Castlevania was admitted on top of the still-running, now-orphaned emulator.
    @Test
    fun `an unanswered probe is never proven gone, whatever the reason`() {
        assertFalse(
            CompanionProcessRuntime.parkedGameProvenGone(
                VendorTaskOutcome.Unavailable("unavailable"),
                PrivilegedPackageLiveness.ALIVE,
            ),
        )
        assertFalse(
            CompanionProcessRuntime.parkedGameProvenGone(
                VendorTaskOutcome.Unavailable("timeout"),
                PrivilegedPackageLiveness.ALIVE,
            ),
        )
        assertFalse(
            CompanionProcessRuntime.parkedGameProvenGone(
                VendorTaskOutcome.Unavailable("parse"),
                PrivilegedPackageLiveness.ALIVE,
            ),
        )
    }

    private fun session(
        operation: String = "game:3ds",
        gameId: String = "zelda-3ds",
        targetPackageName: String = "com.example.lime3ds",
    ): MimirGameSession = MimirGameSession(
        operation = MimirGameSessionOperation(operation),
        sequence = 1L,
        gameId = GameId(gameId),
        platformId = PlatformId.THREE_DS,
        returnAnchor = MimirGameReturnAnchor.Home(
            HomeLaunchReturnTarget(
                HomeShelfItemId.Emulated(GameId(gameId)),
                rank = 0L,
            ),
        ),
        startedAtEpochMs = 1L,
        targetPackageName = targetPackageName,
        placement = MimirGamePlacement.MAIN,
        dualScreen = true,
        phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    )
}
