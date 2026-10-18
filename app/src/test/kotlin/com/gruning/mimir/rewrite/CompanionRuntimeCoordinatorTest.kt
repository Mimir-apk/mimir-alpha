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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRuntimeCoordinatorTest {
    @Test
    fun `production lifecycle deadlines separate host start from slow target coverage`() {
        assertEquals(3_000L, CompanionRuntimeTimeouts().launchMillis)
        assertEquals(8_000L, CompanionRuntimeTimeouts().targetCoverageMillis)
        assertEquals(3_000L, CompanionRuntimeTimeouts().reclaimMillis)
        assertEquals(3_000L, CompanionRuntimeTimeouts().renderMillis)
    }

    @Test
    fun `only exact child parent and renderer acknowledgment establishes mirror`() {
        val fixture = presentingFixture()
        val attempt = fixture.attempt()

        assertFalse(fixture.runtime.snapshot.value.mirrorActive)
        assertFalse(
            fixture.runtime.presentationShown(
                OperationId.create(),
                attempt.parentDispositionId,
                attempt.rendererOwnerId,
            ),
        )
        assertFalse(
            fixture.runtime.presentationShown(
                attempt.operationId,
                OperationId.create(),
                attempt.rendererOwnerId,
            ),
        )
        assertFalse(
            fixture.runtime.presentationShown(
                attempt.operationId,
                attempt.parentDispositionId,
                RendererOwnerId.create(),
            ),
        )
        assertTrue(fixture.runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)
    }

    @Test
    fun `host started and target dispatched advance phase without yielding or mirroring`() {
        val fixture = mirroredFixture()
        val host = fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("library", "game" to "a"))!!

        assertTrue(fixture.runtime.hostStarted(host.operationId))
        assertEquals(
            LaunchPhase.HOST_STARTED,
            (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.LaunchPending).phase,
        )
        assertFalse(fixture.runtime.snapshot.value.mirrorActive)

        val hostStartDeadline = fixture.scheduler.tasks.last()
        assertTrue(fixture.runtime.targetDispatched(host.operationId))
        assertTrue(hostStartDeadline.cancelled)
        assertEquals(8_000L, fixture.scheduler.tasks.last().delayMillis)
        assertEquals(host.operationId, fixture.scheduler.tasks.last().operationId)
        assertEquals(
            LaunchPhase.TARGET_DISPATCHED,
            (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.LaunchPending).phase,
        )
        assertFalse(fixture.runtime.snapshot.value.mirrorActive)
    }

    @Test
    fun `explicit existing game return yields once and edge reclaim restores the mirror`() {
        val fixture = mirroredFixture()
        val mirror = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.MirrorPresented

        assertTrue(
            fixture.runtime.yieldForExistingSecondaryGame(
                parentOperationId = mirror.attempt.parentDispositionId,
                packageName = "com.example.melonds",
                primaryOrigin = origin("library"),
            ),
        )
        val yielded = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Yielded
        assertEquals(YieldEvidence.USER_REQUESTED_GAME_RETURN, yielded.evidence)
        assertNotEquals(mirror.attempt.parentDispositionId, yielded.lease?.operationId)
        assertEquals(0L, fixture.port.dismissals.single().delayMillis)
        assertFalse(
            fixture.runtime.yieldForExistingSecondaryGame(
                parentOperationId = mirror.attempt.parentDispositionId,
                packageName = "com.example.melonds",
                primaryOrigin = origin("library"),
            ),
        )

        val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
        assertEquals(yielded.lease?.operationId, reclaim.parentHostOperationId)
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        val render = fixture.attempt()
        assertTrue(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)
    }

    // ---- D41: a task Mimir did not launch took display 4 while Mimir was presenting there -------------------------------------

    @Test
    fun `a foreign task on the presented display yields the Bottom exactly as a covered host does`() {
        val fixture = mirroredFixture()

        assertTrue(fixture.runtime.yieldToForeignTask(4, "com.android.chrome", origin("library")))

        val yielded = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Yielded
        assertEquals(YieldEvidence.HOST_COVERED, yielded.evidence)
        val lease = yielded.lease!!
        assertEquals("com.android.chrome", lease.packageName)
        assertEquals(4, lease.displayId)
        assertEquals(LaunchModeRecentsPick, lease.launchMode)
        assertFalse(fixture.runtime.snapshot.value.mirrorActive)
        assertTrue(fixture.runtime.isYieldedFor(lease.operationId))
        assertTrue("the return row is offered", companionSecondaryReturnAvailable(fixture.runtime.snapshot.value))
        assertEquals("host:com.android.chrome", companionDisplayOwnerLabel(yielded))
        assertEquals("parked at once: the task is already on screen under the Presentation", listOf(FakePort.Dismissal(lease.operationId, 0L)), fixture.port.dismissals)
        assertNull("no launch happened", fixture.runtime.snapshot.value.launchLockOperationId)
        assertNull(fixture.runtime.snapshot.value.launchSettlement)
        val event = fixture.loggedEvents.last()
        assertEquals("ForeignTaskCovered", event.event)
        assertEquals("MirrorPresented", event.from)
        assertEquals("Yielded", event.to)
        assertEquals("com.android.chrome", event.pkg)
        assertEquals(LaunchModeRecentsPick, event.mode)
        assertEquals("recents_pick", event.reason)
    }

    @Test
    fun `edge back after a foreign task yield reclaims over that lease, as it does after a Quick Launch`() {
        val fixture = mirroredFixture()
        assertTrue(fixture.runtime.yieldToForeignTask(4, "com.android.chrome", origin("library")))
        val lease = fixture.hostLease()

        val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!

        assertEquals(lease.operationId, reclaim.parentHostOperationId)
        assertEquals("the covered host is kept for the D2 retreat", lease, reclaim.coveredHost)
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        val render = fixture.attempt()
        assertTrue(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)
    }

    @Test
    fun `a reclaim that fails over a foreign task yield hands the display back to that task`() {
        val fixture = mirroredFixture()
        assertTrue(fixture.runtime.yieldToForeignTask(4, "com.android.chrome", origin("library")))
        val host = fixture.hostLease()
        fixture.port.guardLaunchSucceeds = false

        val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!

        fixture.assertRetreatedTo(host, reclaim)
    }

    @Test
    fun `the return host path still returns over a foreign task lease`() {
        val fixture = mirroredFixture()
        assertTrue(fixture.runtime.yieldToForeignTask(4, "com.android.chrome", origin("library")))
        val lease = fixture.hostLease()

        val reclaim = fixture.runtime.hostReturned(lease.operationId)!!

        assertEquals(lease.operationId, reclaim.parentHostOperationId)
        assertEquals("secondary_app_return", reclaim.reason)
    }

    @Test
    fun `a recents-pick yield moved no Main app, so it ends no Main session, while every other lease does`() {
        fun lease(mode: String) = HostLease(OperationId.create(), "com.android.chrome", 4, mode, origin("library"))

        assertFalse(yieldedLeaseMovedMainApp(lease(LaunchModeRecentsPick)))
        listOf("secondary_app", "screen_transfer", "resume", "secondary_game").forEach { mode ->
            assertTrue("$mode moved an app to the Bottom", yieldedLeaseMovedMainApp(lease(mode)))
        }
        val fixture = mirroredFixture()
        assertTrue(fixture.runtime.yieldToForeignTask(4, "com.android.chrome", origin("library")))
        assertFalse("the lease the runtime mints for a foreign task is the one the gate leaves alone", yieldedLeaseMovedMainApp(fixture.hostLease()))
    }

    @Test
    fun `the Yielded collector asks the gate before it ends a Main session`() {
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionProcessRuntime.kt").readText()
        val line = source.lines().single { it.contains("MimirSessionAuthority.endPrimaryAppForSecondaryMove(lease.packageName)") }
        assertTrue(
            "the gate is on the same condition, ahead of the call",
            line.contains("yieldedLeaseMovedMainApp(lease)") &&
                line.indexOf("yieldedLeaseMovedMainApp(lease)") < line.indexOf("MimirSessionAuthority.endPrimaryAppForSecondaryMove"),
        )
    }

    @Test
    fun `the floor resuming after the foreign task left reclaims over its lease, for that package only`() {
        val fixture = mirroredFixture()
        assertTrue(fixture.runtime.yieldToForeignTask(4, "com.android.chrome", origin("library")))
        val lease = fixture.hostLease()
        val resumed = fixture.runtime.snapshot.value.disposition

        assertNull("a different package did not leave", fixture.runtime.explicitReclaim("secondary_home_resumed", origin("library"), returningFromPackage = "com.example.other"))
        assertEquals(resumed, fixture.runtime.snapshot.value.disposition)
        val reclaim = fixture.runtime.explicitReclaim(
            reason = "secondary_home_resumed",
            primaryOrigin = origin("library"),
            returningFromPackage = secondaryHomeResumeReturningPackage(resumed),
        )!!

        assertEquals(lease.operationId, reclaim.parentHostOperationId)
        assertNull("the caller watched the task leave, so a failed reclaim cannot retreat to it", reclaim.coveredHost)
        assertFalse("the floor is already the top, so it is not asked to come forward", reclaimRequestsFloorFront(reclaim.reason))
    }

    @Test
    fun `a presenting display yields too, and the render in flight is dead`() {
        val fixture = presentingFixture()
        val attempt = fixture.attempt()

        assertTrue(fixture.runtime.yieldToForeignTask(4, "com.android.chrome", origin("library")))

        assertTrue(fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.Yielded)
        assertFalse(fixture.runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
        assertTrue("no deadline stays armed over a yielded display", fixture.scheduler.tasks.none { !it.cancelled })
    }

    @Test
    fun `a foreign task is refused unless Mimir presents on that display with no lease`() {
        fun assertRefused(fixture: Fixture, displayId: Int = 4, packageName: String = "com.android.chrome") {
            val before = fixture.runtime.snapshot.value
            assertFalse(fixture.runtime.yieldToForeignTask(displayId, packageName, origin("library")))
            assertEquals("nothing moved", before, fixture.runtime.snapshot.value)
        }

        assertRefused(mirroredFixture(), displayId = 5)
        assertRefused(mirroredFixture(), packageName = "  ")
        assertRefused(Fixture())
        assertRefused(mirroredFixture().also { it.runtime.launchRequested("example.a", 4, "secondary_app", origin("home")) })
        assertRefused(mirroredFixture().also { it.runtime.explicitReclaim("overlay_edge_back", origin("library")) })
        assertRefused(yieldedFixture())
    }

    @Test
    fun `a running Main dual-screen game keeps its own hand-off, so a foreign task never yields over it`() {
        val fixture = mirroredFixture()
        fixture.activeGame = game
        val before = fixture.runtime.snapshot.value

        assertFalse(fixture.runtime.yieldToForeignTask(4, "com.example.melonds", origin("library")))

        assertEquals(before, fixture.runtime.snapshot.value)
        assertTrue(fixture.port.dismissals.isEmpty())
    }

    // ---- ret1/ret2: Return to game with no parked lease and no reclaim of ours leaves the live mirror alone ---------------------

    private fun lease(): MainGameParkingLease = MainGameParkingLease(
        operation = MimirGameSessionOperation("ret1-op"),
        gameId = GameId("ret1-game"),
        targetPackageName = "com.example.melonds",
        taskId = 4170,
        parkedAtElapsedMillis = 0L,
    )

    private val game = "game:ret2"

    /** Settles a reclaim the way the existing edge-reclaim test does, so the mirror is presented again. */
    private fun reclaimAndSettle(fixture: Fixture) {
        val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        val render = fixture.attempt()
        assertTrue(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)
    }

    private fun yieldTo(fixture: Fixture) {
        val mirror = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.MirrorPresented
        assertTrue(
            fixture.runtime.yieldForExistingSecondaryGame(
                parentOperationId = mirror.attempt.parentDispositionId,
                packageName = "com.example.melonds",
                primaryOrigin = origin("library"),
            ),
        )
    }

    @Test
    fun `the yield gate truth table`() {
        assertFalse("no lease, no reclaim of ours: the game dropped its own window", companionExistingGameReturnNeedsYield(null, reclaimedOverGame = false))
        assertTrue("a parked game", companionExistingGameReturnNeedsYield(lease(), reclaimedOverGame = false))
        assertTrue("a reclaim of ours over the game", companionExistingGameReturnNeedsYield(null, reclaimedOverGame = true))
        assertTrue("both", companionExistingGameReturnNeedsYield(lease(), reclaimedOverGame = true))
    }

    @Test
    fun `the game drops its own window with no reclaim, then Return completes at once, yields nothing and the mirror stays presented and live`() {
        val fixture = mirroredFixture()
        fixture.activeGame = game
        val before = fixture.runtime.snapshot.value
        assertFalse("Mimir reclaimed nothing over this game", fixture.runtime.reclaimedOverGame(game))
        var logged = 0
        val handled = completeExistingGameReturnAlreadyFront(null, fixture.runtime.reclaimedOverGame(game)) { logged += 1 }
        assertEquals(true, handled)
        assertEquals("one line, ids only", 1, logged)
        val after = fixture.runtime.snapshot.value
        assertTrue("still MirrorPresented, not Yielded", after.disposition is SecondaryDisplayDisposition.MirrorPresented)
        assertEquals(before.disposition, after.disposition)
        assertTrue("and live", after.mirrorActive)
        assertTrue("nothing was dismissed after cover and nothing parked", fixture.port.dismissals.isEmpty())
    }

    @Test
    fun `a parked return clears its lease on Main stop, a later reclaim puts Mimir over the game, and Return then yields and parks`() {
        val fixture = mirroredFixture()
        fixture.activeGame = game
        // The parked return's lease is gone (Main stopped); Mimir reclaims display 4 over the game's still-existing lower window.
        reclaimAndSettle(fixture)
        assertTrue("the reclaim is remembered for this game session", fixture.runtime.reclaimedOverGame(game))
        assertFalse("and not for another session", fixture.runtime.reclaimedOverGame("game:other"))
        assertNull(
            "the lease-less already-front path declines: Return must yield",
            completeExistingGameReturnAlreadyFront(null, fixture.runtime.reclaimedOverGame(game)) { error("must not run") },
        )
        yieldTo(fixture)
        val yielded = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Yielded
        assertEquals(YieldEvidence.USER_REQUESTED_GAME_RETURN, yielded.evidence)
        assertEquals("and the cover-dismiss that parks it is queued", 0L, fixture.port.dismissals.last().delayMillis)
    }

    @Test
    fun `a reclaim, a yield, then the game drops its window, and Return does not yield again`() {
        val fixture = mirroredFixture()
        fixture.activeGame = game
        reclaimAndSettle(fixture)
        yieldTo(fixture)
        assertFalse("the yield cleared the reclaim", fixture.runtime.reclaimedOverGame(game))
        // The edge reclaim brings the mirror back after the game's window is gone, with no game running at that moment.
        fixture.activeGame = null
        reclaimAndSettle(fixture)
        fixture.activeGame = game
        assertFalse("a reclaim made while no game ran is not over it", fixture.runtime.reclaimedOverGame(game))
        assertEquals(true, completeExistingGameReturnAlreadyFront(null, fixture.runtime.reclaimedOverGame(game)) { })
    }

    @Test
    fun `with a parked lease the already-front path declines and the parked path still reaches Yielded for the user return`() {
        val fixture = mirroredFixture()
        fixture.activeGame = game
        assertNull(completeExistingGameReturnAlreadyFront(lease(), fixture.runtime.reclaimedOverGame(game)) { error("must not run") })
        yieldTo(fixture)
        val yielded = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Yielded
        assertEquals(YieldEvidence.USER_REQUESTED_GAME_RETURN, yielded.evidence)
    }

    @Test
    fun `the controller asks the gate, yields on a reclaim of ours and never parks on the already-front path`() {
        val src = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionDisplayController.kt").readText()
        val body = src.substring(src.indexOf("private fun returnToExistingMainGame("), src.indexOf("val expectedGameOperation = session.operation"))
        assertTrue("it asks the gate with the reclaim flag", body.contains("completeExistingGameReturnAlreadyFront(parkedLease, reclaimedOver)"))
        assertTrue("the flag is the runtime's, per game session", body.contains("runtime.reclaimedOverGame(session.operation.value)"))
        assertTrue("and logs the one line", body.contains("existing_game_return_already_front"))
        val alreadyFront = body.substring(body.indexOf("completeExistingGameReturnAlreadyFront("), body.indexOf("if (alreadyFront != null)"))
        assertFalse(
            "the already-front path yields, parks and dismisses nothing",
            alreadyFront.contains("yieldForExistingSecondaryGame") || alreadyFront.contains("parkBehindSecondaryHost") || alreadyFront.contains("dismissPresentationAfterCover"),
        )
        assertTrue("a reclaim of ours still yields before the parked path", body.contains("runtime.yieldForExistingSecondaryGame("))
        assertFalse("and no timeout un-park", body.contains("postDelayed") || body.contains("delay("))
        val coord = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnCoordinator.kt").readText()
        assertTrue("the process runtime reads the running Main game's operation", coord.contains("activeGameOperation = {") && coord.contains("companionMainDualScreenGameActive(it)"))
    }

    @Test
    fun `edge back may explicitly refresh a settled mirror while ordinary reclaim deduplicates`() {
        val fixture = mirroredFixture()
        val first = fixture.runtime.explicitReclaim("ordinary_reclaim", origin("library"))!!
        assertTrue(fixture.runtime.restoreGuardForeground(first.operationId))
        val render = fixture.attempt()
        assertTrue(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)

        assertEquals(first, fixture.runtime.explicitReclaim("ordinary_reclaim", origin("library")))
        val refreshed = fixture.runtime.explicitReclaim(
            reason = "overlay_edge_back",
            primaryOrigin = origin("library"),
            refreshPresentedMirror = true,
        )!!
        assertNotEquals(first.operationId, refreshed.operationId)
        assertEquals(ReclaimPhase.GUARD_DISPATCHED, (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending).phase)
    }

    // ---- a dual-screen game that HOME parked, against the real runtime with a fake Bottom --------------------------------

    @Test
    fun `a parked dual-screen game takes the Bottom back over its window, Resume hands it over, and a refused focus takes it back`() {
        val fixture = mirroredFixture()

        // The park: the settled mirror is refreshed (a fresh Presentation above the emulator's window), as the right-edge swipe does.
        assertTrue(reclaimBottomOverLiveGame(fixture.runtime, "com.example.melonds", origin("library")))
        val pending = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending
        assertEquals(HomeParkReclaimReason, pending.lease.reason)
        assertTrue("it asks for the floor, like the edge-back it copies", reclaimRequestsFloorFront(pending.lease.reason))
        assertTrue(fixture.runtime.restoreGuardForeground(pending.lease.operationId))
        val render = fixture.attempt()
        assertTrue(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)

        // Resume: Mimir's mirror yields at once, which exposes the emulator's own window on display 4.
        assertTrue(yieldBottomIfMirrored(fixture.runtime, "com.example.melonds", origin("library")))
        val yielded = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Yielded
        assertEquals(YieldEvidence.USER_REQUESTED_GAME_RETURN, yielded.evidence)
        assertEquals(0L, fixture.port.dismissals.last().delayMillis)

        // The Main focus was refused: Mimir takes the Bottom back, from the hand-over it made.
        assertTrue(restoreBottomIfYieldedToGame(fixture.runtime, "com.example.melonds", origin("library")))
        val back = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending
        assertEquals("main_game_focus_failed", back.lease.reason)
    }

    @Test
    fun `a Bottom with no mirror shown has nothing to hand over, and a host that is not the game's is never reclaimed from`() {
        val presenting = presentingFixture()
        val before = presenting.runtime.snapshot.value
        assertFalse(yieldBottomIfMirrored(presenting.runtime, "com.example.melonds", origin("library")))
        assertEquals(before, presenting.runtime.snapshot.value)

        val fixture = mirroredFixture()
        assertTrue(fixture.runtime.yieldToForeignTask(4, "com.android.chrome", origin("library")))
        val covered = fixture.runtime.snapshot.value
        assertFalse("Chrome's cover is not the game's hand-over", restoreBottomIfYieldedToGame(fixture.runtime, "com.example.melonds", origin("library")))
        assertFalse("and a host that covers the Bottom cannot be reclaimed by a park", reclaimBottomOverLiveGame(fixture.runtime, "com.example.melonds", origin("library")))
        assertEquals(covered, fixture.runtime.snapshot.value)
    }

    @Test
    fun `pre-dispatch deadline rolls back through presenting and post-dispatch deadline needs recovery`() {
        val beforeDispatch = mirroredFixture()
        val firstOrigin = origin("home", "cursor" to "game-4")
        val first = beforeDispatch.runtime.launchRequested("example.a", 4, "secondary_app", firstOrigin)!!
        assertTrue(beforeDispatch.runtime.deadline(first.operationId))
        val rollback = beforeDispatch.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Presenting
        assertEquals(
            LaunchSettlement(first.operationId, LaunchSettlementKind.FAILED),
            beforeDispatch.runtime.snapshot.value.launchSettlement,
        )
        assertTrue(
            beforeDispatch.runtime.snapshot.value.launchResolutionFor(UUID.fromString(first.operationId.toString()))
                is CompanionLaunchResolution.Fail,
        )
        assertFalse(beforeDispatch.runtime.snapshot.value.mirrorActive)
        assertTrue(
            beforeDispatch.runtime.presentationFailed(
                rollback.attempt.operationId,
                rollback.attempt.parentDispositionId,
                rollback.attempt.rendererOwnerId,
                "rollback render failed",
            ),
        )
        assertEquals(
            firstOrigin,
            (beforeDispatch.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.RecoveryNeeded).fallback,
        )

        val afterDispatch = mirroredFixture()
        val second = afterDispatch.runtime.launchRequested("example.b", 4, "secondary_app", origin("book"))!!
        assertTrue(afterDispatch.runtime.hostStarted(second.operationId))
        assertTrue(afterDispatch.runtime.targetDispatched(second.operationId))
        assertTrue(afterDispatch.runtime.deadline(second.operationId))
        val recovery = afterDispatch.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.RecoveryNeeded
        assertEquals(FailureCode.LAUNCH_DEADLINE_AFTER_DISPATCH, recovery.failure.code)
        assertFalse(afterDispatch.runtime.snapshot.value.mirrorActive)
    }

    @Test
    fun `launch A reclaim R then launch B makes every late A and R completion inert`() {
        val fixture = mirroredFixture()
        val launchA = fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("library"))!!
        val reclaimR = fixture.runtime.explicitReclaim("edge_back", origin("library"))!!
        assertTrue(fixture.runtime.restoreGuardForeground(reclaimR.operationId))
        val renderR = (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending).expectedRender!!
        val launchB = fixture.runtime.launchRequested("example.b", 4, "secondary_app", origin("settings"))!!
        val expected = fixture.runtime.snapshot.value
        assertNull(expected.launchSettlement)

        assertFalse(fixture.runtime.hostStarted(launchA.operationId))
        assertFalse(fixture.runtime.targetDispatched(launchA.operationId))
        assertFalse(fixture.runtime.hostCovered(launchA.operationId))
        assertNull(fixture.runtime.hostReturned(launchA.operationId))
        assertFalse(fixture.runtime.deadline(launchA.operationId))
        assertFalse(fixture.runtime.restoreGuardForeground(reclaimR.operationId))
        assertFalse(fixture.runtime.deadline(reclaimR.operationId))
        assertFalse(
            fixture.runtime.presentationShown(
                renderR.operationId,
                renderR.parentDispositionId,
                renderR.rendererOwnerId,
            ),
        )
        assertEquals(expected, fixture.runtime.snapshot.value)
        assertEquals(launchB.operationId, fixture.runtime.snapshot.value.launchLockOperationId)
    }

    @Test
    fun `root superseded during reclaim preparation never dispatches stale guard`() {
        val fixture = mirroredFixture()
        lateinit var launchB: HostLease
        fixture.port.onPrepareReclaim = {
            launchB = fixture.runtime.launchRequested("example.b", 4, "secondary_app", origin("settings"))!!
        }

        fixture.runtime.explicitReclaim("edge", origin("library"))

        assertTrue(fixture.port.guardLaunches.isEmpty())
        val pending = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.LaunchPending
        assertEquals(launchB.operationId, pending.lease.operationId)
    }

    @Test
    fun `duplicate explicit reclaim joins the live lease without resetting work`() {
        val fixture = mirroredFixture()
        val first = fixture.runtime.explicitReclaim("edge_back", origin("library"))!!
        val snapshot = fixture.runtime.snapshot.value
        val scheduled = fixture.scheduler.tasks.toList()
        val preparations = fixture.port.reclaimPreparations.toList()
        val guards = fixture.port.guardLaunches.toList()

        val duplicate = fixture.runtime.explicitReclaim("another_edge", origin("settings"))!!

        assertEquals(first, duplicate)
        assertEquals(snapshot, fixture.runtime.snapshot.value)
        assertEquals(scheduled, fixture.scheduler.tasks)
        assertEquals(preparations, fixture.port.reclaimPreparations)
        assertEquals(guards, fixture.port.guardLaunches)
    }

    @Test
    fun `explicit reclaim joins a host return reclaim already rendering`() {
        val fixture = yieldedFixture()
        val host = (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Yielded).lease!!
        val returned = fixture.runtime.hostReturned(host.operationId)!!
        val snapshot = fixture.runtime.snapshot.value
        val renders = fixture.port.renders.toList()

        val explicit = fixture.runtime.explicitReclaim("edge_back", origin("settings"))!!

        assertEquals(returned, explicit)
        assertEquals(snapshot, fixture.runtime.snapshot.value)
        assertEquals(renders, fixture.port.renders)
    }

    @Test
    fun `visible tab removal waits for the exact reclaim render acknowledgment`() {
        val fixture = yieldedFixture()
        val host = fixture.hostLease()

        val reclaim = fixture.runtime.explicitReclaim(
            reason = "tab_close_reclaim",
            primaryOrigin = origin("library"),
            returningFromPackage = host.packageName,
        )!!
        assertEquals(host.operationId, reclaim.parentHostOperationId)
        assertFalse(fixture.runtime.snapshot.value.mirrorActive)

        // Dispatch and foreground preparation are not removal evidence. The selected tab remains
        // protected until the exact new renderer owner acknowledges this reclaim root.
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        assertFalse(fixture.runtime.snapshot.value.mirrorActive)
        val pending = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending
        assertEquals(reclaim.operationId, pending.expectedRender?.parentDispositionId)
        assertTrue(fixture.runtime.presentationShown(
            pending.expectedRender!!.operationId,
            reclaim.operationId,
            pending.expectedRender!!.rendererOwnerId,
        ))
        assertTrue(fixture.runtime.isMirrorPresentedFor(reclaim.operationId))
    }

    @Test
    fun `delayed explicit reclaim joins the just settled lease without new work`() {
        val fixture = mirroredFixture()
        val first = fixture.runtime.explicitReclaim("edge_back", origin("library"))!!
        assertTrue(fixture.runtime.restoreGuardForeground(first.operationId))
        fixture.acknowledgeCurrentRender()
        val settled = fixture.runtime.snapshot.value
        val issuedIds = fixture.ids.issuedCount
        val scheduled = fixture.scheduler.tasks.toList()
        val preparations = fixture.port.reclaimPreparations.toList()
        val guards = fixture.port.guardLaunches.toList()
        val renders = fixture.port.renders.toList()

        val duplicate = fixture.runtime.explicitReclaim("foreground_return", origin("settings"))!!

        assertEquals(first, duplicate)
        assertTrue(fixture.runtime.recognizes(first.operationId))
        assertEquals(settled, fixture.runtime.snapshot.value)
        assertEquals(issuedIds, fixture.ids.issuedCount)
        assertEquals(scheduled, fixture.scheduler.tasks)
        assertEquals(preparations, fixture.port.reclaimPreparations)
        assertEquals(guards, fixture.port.guardLaunches)
        assertEquals(renders, fixture.port.renders)
    }

    @Test
    fun `explicit reclaim after recovery mints a fresh operation`() {
        val fixture = mirroredFixture()
        val failed = fixture.runtime.explicitReclaim("edge_back", origin("library"))!!
        assertTrue(fixture.runtime.deadline(failed.operationId))
        assertTrue(fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.RecoveryNeeded)
        val issuedIds = fixture.ids.issuedCount
        val scheduledDeadlines = fixture.scheduler.tasks.size
        val preparations = fixture.port.reclaimPreparations.size
        val guards = fixture.port.guardLaunches.size

        val retry = fixture.runtime.explicitReclaim("recovery_retry", origin("library"))!!

        assertNotEquals(failed.operationId, retry.operationId)
        assertEquals(issuedIds + 1, fixture.ids.issuedCount)
        assertEquals(scheduledDeadlines + 1, fixture.scheduler.tasks.size)
        assertEquals(preparations + 1, fixture.port.reclaimPreparations.size)
        assertEquals(guards + 1, fixture.port.guardLaunches.size)
        assertEquals(
            retry,
            (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending).lease,
        )
    }

    @Test
    fun `missing and empty event ids are rejected in every disposition`() {
        val fixture = Fixture()
        assertInvalidEventsDoNotMutate(fixture.runtime)

        fixture.runtime.bindRenderer(fixture.port)
        fixture.runtime.displayAvailable(4)
        assertInvalidEventsDoNotMutate(fixture.runtime)

        fixture.acknowledgeCurrentRender()
        assertInvalidEventsDoNotMutate(fixture.runtime)

        val host = fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("home"))!!
        assertInvalidEventsDoNotMutate(fixture.runtime)

        fixture.runtime.hostStarted(host.operationId)
        fixture.runtime.targetDispatched(host.operationId)
        fixture.runtime.hostCovered(host.operationId)
        assertInvalidEventsDoNotMutate(fixture.runtime)

        val reclaim = fixture.runtime.hostReturned(host.operationId)!!
        assertInvalidEventsDoNotMutate(fixture.runtime)

        fixture.runtime.deadline(reclaim.operationId)
        assertTrue(fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.RecoveryNeeded)
        assertInvalidEventsDoNotMutate(fixture.runtime)
    }

    @Test
    fun `replacement renderer gets exactly one child and old callbacks are inert`() {
        val fixture = mirroredFixture()
        val oldAttempt = (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.MirrorPresented).attempt
        val oldPort = fixture.port
        val replacement = FakePort()

        fixture.runtime.bindRenderer(replacement)
        val replacementState = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Presenting
        val newAttempt = replacementState.attempt

        assertEquals(1, replacement.renders.size)
        assertEquals(oldAttempt.parentDispositionId, newAttempt.parentDispositionId)
        assertNotEquals(oldAttempt.operationId, newAttempt.operationId)
        assertFalse(fixture.runtime.unbindRenderer(oldPort.rendererOwnerId))
        assertFalse(
            fixture.runtime.presentationShown(
                oldAttempt.operationId,
                oldAttempt.parentDispositionId,
                oldAttempt.rendererOwnerId,
            ),
        )
        assertFalse(
            fixture.runtime.presentationFailed(
                oldAttempt.operationId,
                oldAttempt.parentDispositionId,
                oldAttempt.rendererOwnerId,
                "late old owner",
            ),
        )
        assertEquals(replacementState, fixture.runtime.snapshot.value.disposition)
        assertTrue(fixture.runtime.presentationShown(newAttempt.operationId, newAttempt.parentDispositionId, newAttempt.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)
    }

    @Test
    fun `replacement during reclaim invalidates old child without replacing parent`() {
        val fixture = mirroredFixture()
        val reclaim = fixture.runtime.explicitReclaim("edge", origin("library"))!!
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        val oldAttempt = (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending)
            .expectedRender!!
        val replacement = FakePort()

        fixture.runtime.bindRenderer(replacement)
        val pending = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending
        val newAttempt = pending.expectedRender!!

        assertEquals(reclaim.operationId, newAttempt.parentDispositionId)
        assertNotEquals(oldAttempt.operationId, newAttempt.operationId)
        assertEquals(1, replacement.renders.size)
        assertFalse(
            fixture.runtime.presentationShown(
                oldAttempt.operationId,
                oldAttempt.parentDispositionId,
                oldAttempt.rendererOwnerId,
            ),
        )
        assertFalse(
            fixture.runtime.presentationFailed(
                oldAttempt.operationId,
                oldAttempt.parentDispositionId,
                oldAttempt.rendererOwnerId,
                "stale reclaim renderer",
            ),
        )
        assertTrue(fixture.runtime.presentationShown(newAttempt.operationId, newAttempt.parentDispositionId, newAttempt.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)
    }

    @Test
    fun `activity unbind and rebind preserve every root disposition`() {
        val presenting = presentingFixture()
        val presentingParent = presenting.attempt().parentDispositionId
        rebind(presenting)
        assertEquals(
            presentingParent,
            (presenting.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Presenting).attempt.parentDispositionId,
        )

        val mirrored = mirroredFixture()
        val mirrorParent = (mirrored.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.MirrorPresented)
            .attempt.parentDispositionId
        rebind(mirrored)
        assertEquals(
            mirrorParent,
            (mirrored.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Presenting).attempt.parentDispositionId,
        )

        val launchPending = mirroredFixture()
        launchPending.runtime.launchRequested("example.a", 4, "secondary_app", origin("home"))
        val launchState = launchPending.runtime.snapshot.value.disposition
        rebind(launchPending)
        assertEquals(launchState, launchPending.runtime.snapshot.value.disposition)

        val yielded = yieldedFixture()
        val yieldedState = yielded.runtime.snapshot.value.disposition
        rebind(yielded)
        assertEquals(yieldedState, yielded.runtime.snapshot.value.disposition)

        val reclaim = mirroredFixture()
        reclaim.runtime.explicitReclaim("edge", origin("library"))
        val reclaimState = reclaim.runtime.snapshot.value.disposition
        rebind(reclaim)
        assertEquals(reclaimState, reclaim.runtime.snapshot.value.disposition)

        val recovery = mirroredFixture()
        val lease = recovery.runtime.explicitReclaim("edge", origin("library"))!!
        recovery.runtime.deadline(lease.operationId)
        val recoveryState = recovery.runtime.snapshot.value.disposition
        rebind(recovery)
        assertEquals(recoveryState, recovery.runtime.snapshot.value.disposition)
    }

    @Test
    fun `launch pending survives activity recreation and persisted process restart needs recovery`() {
        val fixture = mirroredFixture()
        val origin = origin("library", "platform" to "ps2", "cursor" to "game-7")
        val host = fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin)!!
        val pending = fixture.runtime.snapshot.value.disposition

        rebind(fixture)
        assertEquals(pending, fixture.runtime.snapshot.value.disposition)

        val restarted = Fixture()
        assertTrue(
            restarted.runtime.recoverPersistedNonMirror(
                PersistedNonMirrorLease(host.operationId, host.displayId, host.primaryOrigin, "saved LaunchPending"),
            ),
        )
        val recovery = restarted.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.RecoveryNeeded
        assertEquals(host.operationId, recovery.operationId)
        assertEquals(origin, recovery.fallback)
        assertFalse(restarted.runtime.snapshot.value.mirrorActive)

        val activeNewerProcess = mirroredFixture()
        val activeSnapshot = activeNewerProcess.runtime.snapshot.value
        assertFalse(
            activeNewerProcess.runtime.recoverPersistedNonMirror(
                PersistedNonMirrorLease(host.operationId, host.displayId, host.primaryOrigin, "stale saved host"),
            ),
        )
        assertEquals(activeSnapshot, activeNewerProcess.runtime.snapshot.value)
    }

    @Test
    fun `restore guard foreground waits for matching shown and return keeps exact origin`() {
        val fixture = mirroredFixture()
        val exactOrigin = origin("library/book", "folder" to "rpg", "child" to "chrono")
        val host = fixture.runtime.launchRequested("example.a", 4, "secondary_app", exactOrigin)!!
        fixture.runtime.hostStarted(host.operationId)
        fixture.runtime.targetDispatched(host.operationId)
        fixture.runtime.hostCovered(host.operationId)

        val reclaim = fixture.runtime.hostReturned(host.operationId)!!
        val pending = fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending
        val attempt = pending.expectedRender!!

        assertEquals(exactOrigin, reclaim.primaryOrigin)
        assertEquals(host.operationId, reclaim.parentHostOperationId)
        assertFalse(fixture.runtime.snapshot.value.mirrorActive)
        assertTrue(fixture.runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
        assertTrue(fixture.runtime.snapshot.value.mirrorActive)
        assertEquals(
            LaunchSettlement(host.operationId, LaunchSettlementKind.HOST_COVERED),
            fixture.runtime.snapshot.value.launchSettlement,
        )
        assertEquals(
            CompanionLaunchResolution.Unlock(CompanionLaunchUnlockReason.HOST_COVERED),
            fixture.runtime.snapshot.value.launchResolutionFor(UUID.fromString(host.operationId.toString())),
        )
        assertNull(fixture.runtime.snapshot.value.launchResolutionFor(UUID.randomUUID()))
    }

    @Test
    fun `host cover clears only matching launch lock and fast return mints reclaim root`() {
        val fixture = mirroredFixture()
        val launchA = fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("a"))!!
        val launchB = fixture.runtime.launchRequested("example.b", 4, "secondary_app", origin("b"))!!

        assertFalse(fixture.runtime.hostCovered(launchA.operationId))
        assertEquals(launchB.operationId, fixture.runtime.snapshot.value.launchLockOperationId)
        fixture.runtime.hostStarted(launchB.operationId)
        fixture.runtime.targetDispatched(launchB.operationId)
        assertTrue(fixture.runtime.hostCovered(launchB.operationId))
        assertNull(fixture.runtime.snapshot.value.launchLockOperationId)

        val reclaim = fixture.runtime.hostReturned(launchB.operationId)!!
        assertNotEquals(launchB.operationId, reclaim.operationId)
        assertTrue(fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.ReclaimPending)
    }

    @Test
    fun `host destruction never infers reclaim`() {
        val beforeDispatch = mirroredFixture()
        val first = beforeDispatch.runtime.launchRequested("example.a", 4, "secondary_app", origin("home"))!!
        assertTrue(beforeDispatch.runtime.hostDestroyed(first.operationId))
        assertTrue(beforeDispatch.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.Presenting)

        val afterDispatch = mirroredFixture()
        val second = afterDispatch.runtime.launchRequested("example.b", 4, "secondary_app", origin("home"))!!
        afterDispatch.runtime.hostStarted(second.operationId)
        afterDispatch.runtime.targetDispatched(second.operationId)
        assertTrue(afterDispatch.runtime.hostDestroyed(second.operationId))
        val recovery = afterDispatch.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.RecoveryNeeded
        assertEquals(FailureCode.HOST_DESTROYED_AFTER_DISPATCH, recovery.failure.code)
        assertFalse(afterDispatch.runtime.snapshot.value.mirrorActive)
    }

    @Test
    fun `close swap usage and process advisories cannot settle disposition`() {
        val fixture = yieldedFixture()
        val before = fixture.runtime.snapshot.value
        val advisoryId = OperationId.create().toString()

        assertFalse(fixture.runtime.closeDispatchObserved(advisoryId))
        assertFalse(fixture.runtime.swapDispatchObserved(advisoryId))
        assertFalse(fixture.runtime.foregroundReturnObserved("example.a"))
        assertFalse(fixture.runtime.processAbsenceObserved("example.a"))
        assertEquals(before, fixture.runtime.snapshot.value)
    }

    @Test
    fun `display removal invalidates roots renders locks and late callbacks`() {
        val fixture = mirroredFixture()
        val host = fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("home"))!!
        assertTrue(fixture.runtime.displayRemoved())
        assertTrue(fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.NoDisplay)
        assertNull(fixture.runtime.snapshot.value.displayId)
        assertNull(fixture.runtime.snapshot.value.launchLockOperationId)
        assertFalse(fixture.runtime.hostStarted(host.operationId))
        assertFalse(fixture.runtime.deadline(host.operationId))
    }

    @Test
    fun `stale tap after detach cannot restore a display or create recovery`() {
        val fixture = mirroredFixture()
        assertTrue(fixture.runtime.displayRemoved())
        val detached = fixture.runtime.snapshot.value
        val scheduledBeforeTap = fixture.scheduler.tasks.size

        assertNull(
            fixture.runtime.launchRequested(
                "example.a",
                4,
                "secondary_app",
                origin("home"),
            ),
        )

        assertEquals(detached, fixture.runtime.snapshot.value)
        assertEquals(scheduledBeforeTap, fixture.scheduler.tasks.size)
        assertTrue(fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.NoDisplay)
    }

    @Test
    fun `stale tap for replaced display is rejected and exact display is accepted`() {
        val fixture = mirroredFixture()
        assertTrue(fixture.runtime.displayAvailable(7))
        val replaced = fixture.runtime.snapshot.value

        assertNull(fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("home")))
        assertEquals(replaced, fixture.runtime.snapshot.value)

        val exact = fixture.runtime.launchRequested("example.a", 7, "secondary_app", origin("home"))
        assertEquals(7, exact?.displayId)
        assertEquals(7, fixture.runtime.snapshot.value.displayId)
    }

    @Test
    fun `persisted display identity cannot authorize launch before attach observation`() {
        val fixture = Fixture()
        assertTrue(
            fixture.runtime.recoverPersistedNonMirror(
                PersistedNonMirrorLease(
                    operationId = OperationId.create(),
                    displayId = 4,
                    primaryOrigin = origin("home"),
                    description = "saved launch",
                ),
            ),
        )
        val recovered = fixture.runtime.snapshot.value

        assertNull(fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("home")))
        assertEquals(recovered, fixture.runtime.snapshot.value)

        assertTrue(fixture.runtime.displayAvailable(4))
        assertEquals(
            4,
            fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("home"))?.displayId,
        )
    }

    @Test
    fun `persisted cold recovery needs fresh display observation before explicit reclaim`() {
        val fixture = Fixture()
        val record = PersistedNonMirrorLease(
            operationId = OperationId.create(),
            displayId = 4,
            primaryOrigin = origin("home"),
            description = "saved host",
        )
        assertTrue(fixture.runtime.recoverPersistedNonMirror(record))
        assertTrue(fixture.runtime.bindRenderer(fixture.port))
        assertEquals(null, fixture.runtime.snapshot.value.availableDisplayId)
        assertNull(fixture.runtime.explicitReclaim("stale_return", origin("home")))
        assertTrue(fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.RecoveryNeeded)
        assertTrue(fixture.runtime.displayAvailable(4))
        assertEquals(4, fixture.runtime.snapshot.value.availableDisplayId)
        assertTrue(fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.RecoveryNeeded)
        assertEquals(0, fixture.port.renders.size)
        assertEquals(0, fixture.port.reclaimPreparations.size)
        assertEquals(0, fixture.port.guardLaunches.size)
        val reclaim = fixture.runtime.explicitReclaim("fresh_return", origin("home"))
        assertTrue(reclaim != null)
        assertEquals(null, reclaim?.parentHostOperationId)
        assertEquals(null, reclaim?.coveredHost)
        assertEquals(null, reclaim?.coveredHostRoot)
        assertEquals(1, fixture.port.reclaimPreparations.size)
        assertEquals(1, fixture.port.guardLaunches.size)
    }

    // D2 (policy 2026-09-06, from the second-screen ledger): a reclaim minted OVER a covered host that
    // fails before the mirror settles hands the root back to that host. The app is still in front on the
    // secondary display, so `Yielded(host)` is the truth; both return channels (a host return and a fresh
    // explicit reclaim) must keep working, and the failed reclaim root must stop being recognised so a
    // standing restore guard fails closed instead of waiting on a dead lease.
    @Test
    fun `reclaim deadline over a covered host retreats to yielded and keeps both return channels`() {
        val fixture = yieldedFixture()
        val host = fixture.hostLease()
        val reclaim = fixture.runtime.explicitReclaim("edge_back", origin("library"))!!
        assertEquals(host.operationId, reclaim.parentHostOperationId)

        assertTrue(fixture.runtime.deadline(reclaim.operationId))

        fixture.assertRetreatedTo(host, reclaim)
        // The standing guard fails closed and, if the system destroys it first, reports the dead reclaim id
        // (its intent operation and its settlement id are both the reclaim); a yielded host ignores that.
        val retreated = fixture.runtime.snapshot.value
        assertFalse(fixture.runtime.hostDestroyed(reclaim.operationId))
        assertEquals(retreated, fixture.runtime.snapshot.value)
        val returned = fixture.runtime.hostReturned(host.operationId)!!
        assertEquals(host.operationId, returned.parentHostOperationId)
        assertNotEquals(reclaim.operationId, returned.operationId)
    }

    @Test
    fun `render deadline during a reclaim over a covered host retreats to yielded`() {
        val fixture = yieldedFixture()
        val host = fixture.hostLease()
        val reclaim = fixture.runtime.explicitReclaim("edge_back", origin("library"))!!
        assertTrue(fixture.runtime.restoreGuardDispatched(reclaim.operationId))
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        val render = fixture.attempt()

        assertTrue(fixture.runtime.deadline(render.operationId))

        fixture.assertRetreatedTo(host, reclaim)
        // A Presentation that shows late for the abandoned render is refused, never adopted.
        assertFalse(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId))
        val retry = fixture.runtime.explicitReclaim("edge_back_again", origin("library"))!!
        assertNotEquals(reclaim.operationId, retry.operationId)
        assertEquals(host.operationId, retry.parentHostOperationId)
    }

    @Test
    fun `guard dispatch failure and guard destruction over a covered host retreat to yielded`() {
        run {
            val fixture = yieldedFixture()
            val host = fixture.hostLease()
            fixture.port.guardLaunchSucceeds = false
            val reclaim = fixture.runtime.explicitReclaim("edge_back", origin("library"))!!
            fixture.assertRetreatedTo(host, reclaim)
        }
        run {
            val fixture = yieldedFixture()
            val host = fixture.hostLease()
            val reclaim = fixture.runtime.explicitReclaim("edge_back", origin("library"))!!
            assertTrue(fixture.runtime.restoreGuardDispatched(reclaim.operationId))
            assertTrue(fixture.runtime.hostDestroyed(reclaim.operationId))
            fixture.assertRetreatedTo(host, reclaim)
        }
    }

    @Test
    fun `reclaim failure without a covered host still needs recovery`() {
        // A host return reclaim: the app already left, so there is no cover to retreat to.
        val returned = yieldedFixture()
        val viaReturn = returned.runtime.hostReturned(returned.hostLease().operationId)!!
        assertTrue(returned.runtime.deadline(viaReturn.operationId))
        assertTrue(returned.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.RecoveryNeeded)

        // An explicit reclaim naming the returning package: the caller watched the app leave.
        val revealed = yieldedFixture()
        val viaReveal = revealed.runtime.explicitReclaim(
            "presentation_window_revealed",
            origin("library"),
            returningFromPackage = "example.a",
        )!!
        assertTrue(revealed.runtime.deadline(viaReveal.operationId))
        assertTrue(revealed.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.RecoveryNeeded)
    }

    // Handle failed reclaim renders like a timeout: a render that FAILS during a reclaim minted over a
    // covered app is the same failure as a render that times out. The app is still in front on the secondary display, so
    // the root goes back to that app first; RecoveryNeeded(RENDER_FAILED) is only for a reclaim with no cover to return to.
    @Test
    fun `render failure during a reclaim over a covered host retreats to yielded`() {
        val fixture = yieldedFixture()
        val host = fixture.hostLease()
        val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
        assertTrue(fixture.runtime.restoreGuardDispatched(reclaim.operationId))
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        val render = fixture.attempt()

        assertTrue(
            fixture.runtime.presentationFailed(
                render.operationId,
                render.parentDispositionId,
                render.rendererOwnerId,
                "display 4 is unavailable",
            ),
        )

        fixture.assertRetreatedTo(host, reclaim)
        // A Presentation that shows late for the failed render is refused, never adopted.
        assertFalse(fixture.runtime.presentationShown(render.operationId, render.parentDispositionId, render.rendererOwnerId))
        // The covered app keeps both return channels: a fresh explicit reclaim is minted over the same host.
        val retry = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
        assertNotEquals(reclaim.operationId, retry.operationId)
        assertEquals(host.operationId, retry.parentHostOperationId)
    }

    @Test
    fun `a render failure retreat names the failure it absorbed in the fail field`() {
        val fixture = yieldedFixture()
        val host = fixture.hostLease()
        val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        val render = fixture.attempt()

        fixture.runtime.presentationFailed(
            render.operationId,
            render.parentDispositionId,
            render.rendererOwnerId,
            "display 4 is unavailable",
        )

        val event = fixture.loggedEvents.last()
        assertEquals("RenderFailedRetreat", event.event)
        assertEquals("Yielded", event.to)
        assertEquals(host.packageName, event.pkg)
        assertEquals(host.launchMode, event.mode)
        assertEquals("RENDER_FAILED:display 4 is unavailable", event.fail)
    }

    @Test
    fun `every covered-host retreat names the failure it absorbed`() {
        run {
            val fixture = yieldedFixture()
            val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
            assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
            assertTrue(fixture.runtime.deadline(fixture.attempt().operationId))
            val event = fixture.loggedEvents.last()
            assertEquals("RenderDeadlineRetreat", event.event)
            assertEquals("RENDER_DEADLINE:renderer did not acknowledge the expected attempt", event.fail)
        }
        run {
            val fixture = yieldedFixture()
            val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
            assertTrue(fixture.runtime.deadline(reclaim.operationId))
            val event = fixture.loggedEvents.last()
            assertEquals("ReclaimDeadlineRetreat", event.event)
            assertEquals("RECLAIM_DEADLINE:reclaim did not receive a matching render acknowledgment", event.fail)
        }
        run {
            val fixture = yieldedFixture()
            val reclaim = fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
            assertTrue(fixture.runtime.hostDestroyed(reclaim.operationId))
            val event = fixture.loggedEvents.last()
            assertEquals("HostDestroyedRetreat", event.event)
            assertEquals("RECLAIM_HOST_DESTROYED:restore guard destroyed before render acknowledgment", event.fail)
        }
        run {
            val fixture = yieldedFixture()
            fixture.port.guardLaunchSucceeds = false
            fixture.runtime.explicitReclaim("overlay_edge_back", origin("library"))!!
            val event = fixture.loggedEvents.last()
            assertEquals("RestoreGuardDispatchFailedRetreat", event.event)
            assertEquals("RECLAIM_GUARD_DISPATCH_FAILED:restore guard dispatch failed", event.fail)
        }
    }

    @Test
    fun `render failure without a covered host still needs recovery and names the failure`() {
        // A host return reclaim: the app already left, so there is no cover to retreat to.
        val returned = yieldedFixture()
        val viaReturn = returned.runtime.hostReturned(returned.hostLease().operationId)!!
        assertTrue(returned.runtime.restoreGuardForeground(viaReturn.operationId))
        val returnedRender = returned.attempt()
        assertTrue(
            returned.runtime.presentationFailed(
                returnedRender.operationId,
                returnedRender.parentDispositionId,
                returnedRender.rendererOwnerId,
                "display 4 is no longer selected",
            ),
        )
        val recovery = returned.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.RecoveryNeeded
        assertEquals(FailureReason(FailureCode.RENDER_FAILED, "display 4 is no longer selected"), recovery.failure)
        assertEquals(viaReturn.operationId, recovery.operationId)
        assertEquals("PresentationFailed", returned.loggedEvents.last().event)
        assertEquals("RENDER_FAILED:display 4 is no longer selected", returned.loggedEvents.last().fail)

        // An explicit reclaim naming the returning package: the caller watched the app leave.
        val revealed = yieldedFixture()
        val viaReveal = revealed.runtime.explicitReclaim(
            "presentation_window_revealed",
            origin("library"),
            returningFromPackage = "example.a",
        )!!
        assertTrue(revealed.runtime.restoreGuardForeground(viaReveal.operationId))
        val revealedRender = revealed.attempt()
        assertTrue(
            revealed.runtime.presentationFailed(
                revealedRender.operationId,
                revealedRender.parentDispositionId,
                revealedRender.rendererOwnerId,
                "show failed",
            ),
        )
        assertEquals(
            FailureCode.RENDER_FAILED,
            (revealed.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.RecoveryNeeded).failure.code,
        )
    }

    // --- P1: the runtime log event carries pkg/mode/reason/fail for its destination disposition. ---

    @Test
    fun `P1 launch requested logs the destination package and launch mode`() {
        val fixture = mirroredFixture()

        fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("home"))

        val event = fixture.loggedEvents.last()
        assertEquals("LaunchRequested", event.event)
        assertEquals("example.a", event.pkg)
        assertEquals("secondary_app", event.mode)
        assertNull(event.reason)
        assertNull(event.fail)
    }

    @Test
    fun `P1 host covered logs the yielded lease package and launch mode`() {
        val fixture = yieldedFixture()

        val event = fixture.loggedEvents.last()
        assertEquals("HostCovered", event.event)
        assertEquals("example.a", event.pkg)
        assertEquals("secondary_app", event.mode)
        assertNull(event.reason)
        assertNull(event.fail)
    }

    @Test
    fun `P1 explicit reclaim logs the reclaim reason`() {
        val fixture = mirroredFixture()

        fixture.runtime.explicitReclaim("tab_close_reclaim", origin("library"))

        // Not `.last()`: a bound renderer's guard launches synchronously inside `execute(effects)` and
        // publishes its own `RestoreGuardDispatched` event right after this one.
        val event = fixture.loggedEvents.single { it.event == "ExplicitReclaim" }
        assertEquals("tab_close_reclaim", event.reason)
        assertNull(event.pkg)
        assertNull(event.mode)
        assertNull(event.fail)
    }

    @Test
    fun `P1 a post-dispatch launch deadline logs the failure code and detail`() {
        val fixture = mirroredFixture()
        val host = fixture.runtime.launchRequested("example.b", 4, "secondary_app", origin("book"))!!
        fixture.runtime.hostStarted(host.operationId)
        fixture.runtime.targetDispatched(host.operationId)

        fixture.runtime.deadline(host.operationId)

        val event = fixture.loggedEvents.last()
        assertEquals("LaunchDeadline", event.event)
        assertEquals("LAUNCH_DEADLINE_AFTER_DISPATCH:target dispatch was not covered", event.fail)
        assertNull(event.pkg)
        assertNull(event.mode)
        assertNull(event.reason)
    }

    private fun assertInvalidEventsDoNotMutate(runtime: CompanionRuntimeCoordinator) {
        val before = runtime.snapshot.value
        assertFalse(runtime.hostStarted(""))
        assertFalse(runtime.targetDispatched(null))
        assertFalse(runtime.hostCovered("00000000-0000-0000-0000-000000000000"))
        assertNull(runtime.hostReturned("not-a-uuid"))
        assertFalse(runtime.restoreGuardForeground(" "))
        assertFalse(runtime.launchFailed("", "missing"))
        assertFalse(runtime.hostDestroyed(null))
        assertFalse(runtime.deadline(""))
        assertFalse(runtime.presentationShown("", "", ""))
        assertFalse(runtime.presentationFailed("", "", "", "missing"))
        assertEquals(before, runtime.snapshot.value)
    }

    private fun rebind(fixture: Fixture) {
        val oldPort = fixture.port
        assertTrue(fixture.runtime.unbindRenderer(oldPort.rendererOwnerId))
        fixture.port = FakePort()
        assertTrue(fixture.runtime.bindRenderer(fixture.port))
    }

    // ---- review P2: a Resume inside the park's in-flight Bottom reclaim waits for it ----------------------------------------------

    @Test
    fun `a Resume waits, up to its bound, for the park's own Bottom reclaim to land`() {
        val fixture = mirroredFixture()
        assertFalse(homeParkReclaimPending(fixture.runtime.snapshot.value))
        assertTrue("nothing in flight: no wait", runBlocking { awaitHomeParkReclaimSettled(fixture.runtime, 50L) })

        assertTrue(reclaimBottomOverLiveGame(fixture.runtime, "com.example.melonds", origin("library")))
        assertTrue(homeParkReclaimPending(fixture.runtime.snapshot.value))
        assertFalse("a reclaim that never lands is not waited on past its bound", runBlocking { awaitHomeParkReclaimSettled(fixture.runtime, 50L) })

        val result = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val waiter = Thread { result.set(runBlocking { awaitHomeParkReclaimSettled(fixture.runtime, 10_000L) }) }.also(Thread::start)
        Thread.sleep(150L)
        assertEquals("still waiting while the reclaim is in flight", null, result.get())
        val reclaim = (fixture.runtime.snapshot.value.disposition as SecondaryDisplayDisposition.ReclaimPending).lease
        assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
        fixture.acknowledgeCurrentRender()
        waiter.join(10_000L)

        assertEquals("it goes on the moment the mirror lands", true, result.get())
        assertFalse(homeParkReclaimPending(fixture.runtime.snapshot.value))
    }

    @Test
    fun `the Bottom park's own reclaim is waited on too, and no other reclaim is`() {
        val bottom = mirroredFixture()
        assertTrue(bottom.runtime.explicitReclaim(BottomHomeParkReclaimReason, origin("library")) != null)
        assertTrue(homeParkReclaimPending(bottom.runtime.snapshot.value))

        val other = mirroredFixture()
        assertTrue(other.runtime.explicitReclaim("overlay_edge_back", origin("library")) != null)
        assertFalse("an edge-back reclaim is nobody's park", homeParkReclaimPending(other.runtime.snapshot.value))
    }

    // ---- The session bridge keeps a Bottom game's session through the floor's park reclaim -----------------------

    private fun bottomGameAdmitted(): MimirGameSessionOperation {
        val game = GameRow(gameId = GameId("chrono"), title = "Chrono Trigger", platformId = PlatformId.SNES, memberCount = 1, duplicateKind = null)
        val operation = requireNotNull(
            MimirSessionAuthority.prepareHomeGame(
                game = game,
                returnTarget = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L),
                placement = MimirGamePlacement.BOTTOM,
            ),
        )
        val dispatched = LaunchResult(
            readiness = LaunchReadiness.Ready(
                LaunchTarget(
                    gameId = game.gameId,
                    platformId = game.platformId,
                    emulator = EmulatorProfile(
                        id = EmulatorId("retroarch-snes"),
                        packageName = "com.retroarch",
                        displayName = "RetroArch",
                        installed = true,
                        capability = EmulatorCapability.DIRECT_PAYLOAD,
                        locatorForm = LaunchLocatorForm.CONTENT_URI,
                        supportedPlatforms = setOf(game.platformId),
                    ),
                    locator = "content://mimir/${game.gameId.value}",
                    locatorForm = LaunchLocatorForm.CONTENT_URI,
                    title = game.title,
                ),
            ),
            outcome = LaunchOutcome.IntentSent,
        )
        assertTrue(MimirSessionAuthority.settleHomeGame(operation, game, dispatched))
        return operation
    }

    /** A real runtime whose observer is the process's session bridge, a Bottom game launched and covered on display 4, the mirror gone under it. */
    private fun bridgedBottomGame(): Fixture {
        val fixture = Fixture(MimirCompanionRuntimeSessionBridge)
        fixture.runtime.bindRenderer(fixture.port)
        fixture.runtime.displayAvailable(4)
        fixture.acknowledgeCurrentRender()
        val host = fixture.runtime.launchRequested("com.retroarch", 4, "secondary_game", origin("home"))!!
        assertTrue(fixture.runtime.hostStarted(host.operationId))
        assertTrue(fixture.runtime.targetDispatched(host.operationId))
        assertTrue(fixture.runtime.hostCovered(host.operationId))
        return fixture
    }

    private fun gamePhase(): MimirSessionPhase? = MimirSessionAuthority.state.value.gameSession?.phase

    @Test
    fun `HOME on the Bottom parks a Bottom game - the floor's park reclaim keeps the session through its start and its landing`() {
        val operation = bottomGameAdmitted()
        try {
            val fixture = bridgedBottomGame()
            assertEquals(MimirSessionPhase.FOREGROUND_PROVEN, gamePhase())

            val reclaim = fixture.runtime.explicitReclaim(
                reason = BottomHomeParkReclaimReason,
                primaryOrigin = origin("home"),
                returningFromPackage = "com.retroarch",
            )!!
            assertEquals("the reclaim's start does not return the game", MimirSessionPhase.FOREGROUND_PROVEN, gamePhase())
            assertTrue(fixture.runtime.restoreGuardForeground(reclaim.operationId))
            fixture.acknowledgeCurrentRender()

            assertTrue("Mimir's companion is back on the Bottom", fixture.runtime.snapshot.value.disposition is SecondaryDisplayDisposition.MirrorPresented)
            assertEquals("and its landing does not end the game", MimirSessionPhase.FOREGROUND_PROVEN, gamePhase())
            assertEquals(operation, MimirSessionAuthority.state.value.gameSession?.operation)
        } finally {
            MimirSessionAuthority.endGameForTabClose("com.retroarch")
        }
    }

    @Test
    fun `the floor's ordinary reclaim over the same game still returns it, as edge-back does`() {
        bottomGameAdmitted()
        try {
            val fixture = bridgedBottomGame()
            assertEquals(MimirSessionPhase.FOREGROUND_PROVEN, gamePhase())

            fixture.runtime.explicitReclaim(
                reason = "secondary_home_resumed",
                primaryOrigin = origin("home"),
                returningFromPackage = "com.retroarch",
            )!!

            assertEquals("the reason is what keeps the session, so any other reason ends it", MimirSessionPhase.RETURNING, gamePhase())
        } finally {
            MimirSessionAuthority.endGameForTabClose("com.retroarch")
        }
    }

    private fun presentingFixture(): Fixture = Fixture().also {
        it.runtime.bindRenderer(it.port)
        it.runtime.displayAvailable(4)
    }

    private fun mirroredFixture(): Fixture = presentingFixture().also { it.acknowledgeCurrentRender() }

    private fun yieldedFixture(): Fixture = mirroredFixture().also { fixture ->
        val host = fixture.runtime.launchRequested("example.a", 4, "secondary_app", origin("home"))!!
        fixture.runtime.hostStarted(host.operationId)
        fixture.runtime.targetDispatched(host.operationId)
        fixture.runtime.hostCovered(host.operationId)
    }

    private fun origin(destination: String, vararg arguments: Pair<String, String>) =
        PrimaryReturnOrigin(destination, mapOf(*arguments))

    private class Fixture(observer: CompanionRuntimeSessionObserver = CompanionRuntimeSessionObserver { _, _ -> }) {
        val scheduler = FakeScheduler()
        val ids = SequenceIds()
        val loggedEvents = mutableListOf<CompanionRuntimeLogEvent>()
        /** ret2: the Main dual-screen game session running at this moment, as the runtime reads it. */
        var activeGame: String? = null
        val runtime = CompanionRuntimeCoordinator(scheduler, ids, logger = CompanionRuntimeLogger { loggedEvents += it }, sessionObserver = observer, activeGameOperation = { activeGame })
        var port = FakePort()

        fun attempt(): RenderAttempt = when (val state = runtime.snapshot.value.disposition) {
            is SecondaryDisplayDisposition.Presenting -> state.attempt
            is SecondaryDisplayDisposition.ReclaimPending -> state.expectedRender!!
            else -> error("No expected render in $state")
        }

        fun acknowledgeCurrentRender() {
            val attempt = attempt()
            assertTrue(runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
        }

        fun hostLease(): HostLease =
            (runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Yielded).lease!!

        /** The D2 retreat contract: the host owns the root again and the failed reclaim is dead truth. */
        fun assertRetreatedTo(host: HostLease, failed: ReclaimLease) {
            assertEquals(
                SecondaryDisplayDisposition.Yielded(host, YieldEvidence.HOST_COVERED),
                runtime.snapshot.value.disposition,
            )
            assertFalse(runtime.recognizes(failed.operationId))
            assertTrue(runtime.recognizes(host.operationId))
            assertTrue(runtime.isYieldedFor(host.operationId))
            assertEquals(host.primaryOrigin, runtime.operationOrigin(host.operationId))
            assertTrue("a deadline is still armed after the retreat", scheduler.tasks.none { !it.cancelled })
        }
    }

    private class SequenceIds : OperationIdSource {
        private var next = 1L
        var issuedCount = 0
            private set

        override fun next(): OperationId {
            issuedCount += 1
            return OperationId.parseOrNull(UUID(0L, next++).toString())!!
        }
    }

    private class FakePort : CompanionHostPort {
        override val rendererOwnerId: RendererOwnerId = RendererOwnerId.create()
        val renders = mutableListOf<RenderAttempt>()
        val reclaimPreparations = mutableListOf<ReclaimLease>()
        val guardLaunches = mutableListOf<ReclaimLease>()
        data class Dismissal(val operationId: OperationId, val delayMillis: Long)
        val dismissals = mutableListOf<Dismissal>()
        var dismissCount = 0
        var guardLaunchSucceeds = true
        var onPrepareReclaim: (() -> Unit)? = null

        override fun render(attempt: RenderAttempt) {
            renders += attempt
        }

        override fun dismissPresentation() {
            dismissCount += 1
        }

        override fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long) {
            dismissals += Dismissal(hostOperationId, delayMillis)
        }

        override fun prepareForReclaim(lease: ReclaimLease) {
            reclaimPreparations += lease
            onPrepareReclaim?.invoke()
        }

        override fun launchRestoreGuard(lease: ReclaimLease): Boolean {
            guardLaunches += lease
            return guardLaunchSucceeds
        }
    }

    private class FakeScheduler : CompanionRuntimeScheduler {
        data class Task(
            val delayMillis: Long,
            val operationId: OperationId,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        val tasks = mutableListOf<Task>()

        override fun schedule(
            delayMillis: Long,
            operationId: OperationId,
            action: () -> Unit,
        ): RuntimeCancellation {
            val task = Task(delayMillis, operationId, action)
            tasks += task
            return RuntimeCancellation { task.cancelled = true }
        }
    }
}
