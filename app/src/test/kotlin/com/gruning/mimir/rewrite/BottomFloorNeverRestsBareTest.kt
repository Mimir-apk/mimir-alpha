package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observed on Thor, 2026-10-08: the Bottom rested on the bare floor, the M cover and
 * the floating mark on black, for good, whenever an app leased to it lost the display to a HOME press on the Bottom itself.
 *
 * Both repros ran the same two presses. HOME on the Bottom (`-d 4`, then the pad's HOME after a touch on it) started the secondary-home floor over Chrome
 * and Chrome's return host together: `home key decision=skip reason=not_in_front source=floor`, then `secondary home resumed disposition=Yielded
 * package=com.android.chrome autoReclaim=false`. Neither resumed again, so the lease nothing else resolves stayed, and the Presentation stayed parked
 * (frame clock stopped: `scene.crossfade`/`scene.glide` began at the game's launch and ended `transition.unfinished runningMs=4001`) behind a floor that
 * draws only the Mannaz cover. HOME on Main afterwards ended the game (`game_ended`, then `result=cancelled ms=39355`) and changed nothing on the Bottom, because a
 * game's end has no hook there while another app holds the display. Only killing Mimir brought the Bottom back.
 *
 * These tests drive the real runtime through the exact sequences, against a fake renderer, on both game paths: (a) HOME on Main parks the game
 * (`MainGameHomePark`), (b) the game really ends (the park watch proves the task gone). The rule they pin: with an app leased to the Bottom the game's
 * end or park leaves the Bottom on that app while it is in front, and HOME on the Bottom lands on Mimir's companion, whatever order the presses come in.
 *
 * code review of that fix: HOME on the Bottom DURING a launch (floor resumed while `LaunchPending`, then the host's cover arrives with the floor on top) is
 * reclaimed when the host covers, and a reclaim the floor keeps failing is retried twice and then left to the paths that are not the floor's ([FloorReclaimBudget]).
 */
class BottomFloorNeverRestsBareTest {

    @After
    fun resetCoordinator() {
        CompanionReturnCoordinator.resetTestOverrides()
    }

    // ---- the bench ------------------------------------------------------------------------------------------------------------

    private class Port : CompanionHostPort {
        override val rendererOwnerId: RendererOwnerId = RendererOwnerId.create()
        val renders = mutableListOf<RenderAttempt>()
        val guardLaunches = mutableListOf<ReclaimLease>()
        val dismissals = mutableListOf<OperationId>()
        var guardLaunchSucceeds = true

        override fun render(attempt: RenderAttempt) {
            renders += attempt
        }

        override fun dismissPresentation() = Unit

        override fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long) {
            dismissals += hostOperationId
        }

        override fun prepareForReclaim(lease: ReclaimLease) = Unit

        override fun launchRestoreGuard(lease: ReclaimLease): Boolean {
            guardLaunches += lease
            return guardLaunchSucceeds
        }

        /** Everything the runtime asked the Bottom's window to do, as one number. */
        fun work(): Int = renders.size + guardLaunches.size + dismissals.size
    }

    private class Scheduler : CompanionRuntimeScheduler {
        override fun schedule(delayMillis: Long, operationId: OperationId, action: () -> Unit): RuntimeCancellation =
            RuntimeCancellation { }
    }

    /** The Bottom as the Thor had it before the first press: Mimir's companion mirrored on display 4. Installed as the process runtime. */
    private class Bottom {
        val port = Port()
        val runtime = CompanionRuntimeCoordinator(Scheduler())
        val origin = PrimaryReturnOrigin("LIBRARY")

        init {
            runtime.bindRenderer(port)
            runtime.displayAvailable(4)
            val attempt = (runtime.snapshot.value.disposition as SecondaryDisplayDisposition.Presenting).attempt
            assertTrue(runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
            CompanionReturnCoordinator.installRuntime(runtime)
        }

        val disposition: SecondaryDisplayDisposition get() = runtime.snapshot.value.disposition

        /** Chrome tapped from the Activity page's last-app tile, as far as the dispatch: `LaunchPending`, the host not yet stopped under it. */
        fun dispatchChrome(): HostLease {
            val lease = requireNotNull(runtime.launchRequested(Chrome, 4, "secondary_app", origin))
            assertTrue(runtime.hostStarted(lease.operationId))
            assertTrue(runtime.targetDispatched(lease.operationId))
            return lease
        }

        /** The Quick Launch path, `secondary_app`, covered by its return host. */
        fun leaseChrome(): HostLease {
            val lease = dispatchChrome()
            assertTrue(runtime.hostCovered(lease.operationId))
            assertEquals(SecondaryDisplayDisposition.Yielded(lease, YieldEvidence.HOST_COVERED), disposition)
            return lease
        }

        /** `input -d 4 keyevent KEYCODE_HOME`: the floor is started over the app and resumes. Only this callback is the floor's. */
        fun homeOnBottom() = CompanionReturnCoordinator.noteSecondaryHomeResumed(4)

        /** The restore guard comes up and the Presentation commits its first frame: the two callbacks that settle a reclaim. */
        fun settleReclaim() {
            val pending = disposition as SecondaryDisplayDisposition.ReclaimPending
            assertTrue(runtime.restoreGuardForeground(pending.lease.operationId))
            val attempt = (disposition as SecondaryDisplayDisposition.ReclaimPending).expectedRender!!
            assertTrue(runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
        }

        fun assertCompanionIsBack(chrome: HostLease) {
            assertTrue("Mimir's companion is presented again, not a lease over a floor", disposition is SecondaryDisplayDisposition.MirrorPresented)
            assertTrue(runtime.snapshot.value.mirrorActive)
            assertFalse("the Chrome lease is settled", runtime.isYieldedFor(chrome.operationId))
        }
    }

    // ---- the game sides ---------------------------------------------------------------------------------------------------------

    private fun dolphinSession(): MimirGameSession = MimirGameSession(
        operation = MimirGameSessionOperation("game:gc"),
        sequence = 1L,
        gameId = GameId("resident-evil"),
        title = "Resident Evil",
        platformId = PlatformId.PS1,
        returnAnchor = MimirGameReturnAnchor.Home(
            HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("resident-evil")), rank = 0L),
        ),
        startedAtEpochMs = 1L,
        targetPackageName = Dolphin,
        placement = MimirGamePlacement.MAIN,
        dualScreen = false,
        phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
        dispatchedAtElapsedMs = null,
    )

    private fun lease(taskId: Int = 4170) = MainGameParkingLease(
        operation = MimirGameSessionOperation("game:gc"),
        gameId = GameId("resident-evil"),
        targetPackageName = Dolphin,
        taskId = taskId,
        parkedAtElapsedMillis = 1L,
        homeParked = true,
    )

    /** HOME on Main as MainActivity's router delivers it to the parker: `not_in_front`, the game holds the screen. */
    private class Parked(val armed: MutableList<MainGameParkingLease>, val ended: MutableList<MimirGameSession>, val parker: MainGameHomeParker) {
        val scheduled = mutableListOf<suspend () -> Unit>()
    }

    private fun parkBench(game: MimirGameSession): Parked {
        val armed = mutableListOf<MainGameParkingLease>()
        val ended = mutableListOf<MimirGameSession>()
        lateinit var parked: Parked
        val parker = MainGameHomeParker(
            HomeParkPorts(
                currentGame = { game },
                isParked = { false },
                privilegeReady = { true },
                withholdReturn = { },
                resolveTaskOutcome = { VendorTaskOutcome.Found(4170) },
                arm = { g, taskId ->
                    MainGameParkingLease(
                        operation = g.operation,
                        gameId = g.gameId,
                        targetPackageName = g.targetPackageName.orEmpty(),
                        taskId = taskId,
                        parkedAtElapsedMillis = 1L,
                        homeParked = true,
                    ).also { armed += it }
                },
                endSession = { ended += it },
                startWatch = { },
                finalizeElapsed = { _, _ -> },
                elapsedNow = { 7_000L },
                schedule = { block -> parked.scheduled += block },
                log = { },
            ),
        )
        parked = Parked(armed, ended, parker)
        return parked
    }

    /** The 5 s park watch's proof that the emulator really exited behind Home: the game's end, release included. Returns what the release was told. */
    private fun parkWatchProvesTheTaskGone(): List<String> {
        val releasedBy = mutableListOf<String>()
        val released = runBlocking {
            releaseHomeParkedOnProof(
                lease = lease(),
                resolve = { VendorTaskOutcome.NoTask },
                gatewayStatus = { PrivilegedUtilityStatus.READY },
                release = { _, endedBy ->
                    releasedBy += endedBy
                    true
                },
                log = { },
            )
        }
        assertTrue(released)
        return releasedBy
    }

    // ---- the exact sequence, then both game paths -------------------------------------------------------------------------------

    @Test
    fun `HOME on the Bottom over a leased Chrome lands on Mimir's companion, and it is the floor's resume that does it`() {
        val bottom = Bottom()
        val chrome = bottom.leaseChrome()
        assertEquals("the Presentation was parked behind Chrome, and the lease alone has no way back", listOf(chrome.operationId), bottom.port.dismissals)

        bottom.homeOnBottom()

        val reclaim = (bottom.disposition as SecondaryDisplayDisposition.ReclaimPending).lease
        assertEquals("secondary_home_resumed", reclaim.reason)
        assertEquals(chrome.operationId, reclaim.parentHostOperationId)
        assertNull("the floor is over the app, so a failed reclaim must not retreat to a host that covers nothing", reclaim.coveredHost)
        assertEquals("one restore guard, from the Presentation's own port", 1, bottom.port.guardLaunches.size)
        bottom.settleReclaim()
        bottom.assertCompanionIsBack(chrome)
    }

    @Test
    fun `a second HOME press while the reclaim is in flight, or after it, drives nothing again`() {
        val bottom = Bottom()
        val chrome = bottom.leaseChrome()
        bottom.homeOnBottom()
        val pending = bottom.runtime.snapshot.value
        val work = bottom.port.work()

        bottom.homeOnBottom()

        assertSame("ReclaimPending has its own owner", pending, bottom.runtime.snapshot.value)
        assertEquals(work, bottom.port.work())
        bottom.settleReclaim()
        val settled = bottom.runtime.snapshot.value

        bottom.homeOnBottom()

        assertSame(settled, bottom.runtime.snapshot.value)
        bottom.assertCompanionIsBack(chrome)
    }

    @Test
    fun `a reclaim whose guard cannot start leaves the display recoverable, not leased again over the floor`() {
        val bottom = Bottom()
        val chrome = bottom.leaseChrome()
        bottom.port.guardLaunchSucceeds = false

        bottom.homeOnBottom()

        val failed = bottom.disposition as SecondaryDisplayDisposition.RecoveryNeeded
        assertEquals(FailureCode.RECLAIM_GUARD_DISPATCH_FAILED, failed.failure.code)
        assertTrue("RecoveryNeeded is the state every later floor resume and Main resume reclaims from", secondaryHomeResumeShouldReclaim(failed))
        bottom.port.guardLaunchSucceeds = true

        bottom.homeOnBottom()

        bottom.settleReclaim()
        bottom.assertCompanionIsBack(chrome)
    }

    // ---- HOME on the Bottom DURING a launch (code review, P2) ------------------------------------------------------------------

    @Test
    fun `HOME on the Bottom between dispatch and cover - the host's cover that follows lands on the companion`() {
        val bottom = Bottom()
        val chrome = bottom.dispatchChrome()

        bottom.homeOnBottom()

        assertTrue("a floor resume during a launch is ignored by design", bottom.disposition is SecondaryDisplayDisposition.LaunchPending)
        assertTrue(bottom.port.guardLaunches.isEmpty())

        // The host stops under Chrome: through the same wrapper CompanionReturnActivity.settleHostCoverageIfCorrelated calls.
        assertTrue(CompanionReturnCoordinator.hostCovered(chrome.operationId))

        val reclaim = (bottom.disposition as SecondaryDisplayDisposition.ReclaimPending).lease
        assertEquals("secondary_home_resumed", reclaim.reason)
        assertEquals(chrome.operationId, reclaim.parentHostOperationId)
        assertNull(reclaim.coveredHost)
        bottom.settleReclaim()
        bottom.assertCompanionIsBack(chrome)
    }

    @Test
    fun `a launch whose host came up over the floor is a cover, and an ordinary launch is one too`() {
        val bottom = Bottom()
        val chrome = bottom.dispatchChrome()
        bottom.homeOnBottom()
        CompanionReturnCoordinator.noteFloorPaused(4) // the host and then Chrome came up over the floor: it is not the top any more

        assertTrue(CompanionReturnCoordinator.hostCovered(chrome.operationId))

        assertEquals(SecondaryDisplayDisposition.Yielded(chrome, YieldEvidence.HOST_COVERED), bottom.disposition)
        assertTrue(bottom.port.guardLaunches.isEmpty())

        val ordinary = Bottom()
        val tapped = ordinary.dispatchChrome()
        assertTrue(CompanionReturnCoordinator.hostCovered(tapped.operationId))
        assertEquals(SecondaryDisplayDisposition.Yielded(tapped, YieldEvidence.HOST_COVERED), ordinary.disposition)
        assertTrue(ordinary.port.guardLaunches.isEmpty())
    }

    @Test
    fun `the floor's pause and the host's cover reach the coordinator through the calls these tests make`() {
        val coordinator = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnCoordinator.kt").readText()
        assertTrue(
            "the floor's pause clears where it was resumed before it opens the cover watch",
            coordinator.substringAfter("fun noteSecondaryHomePaused(").substringBefore("watch.floorPaused").contains("noteFloorPaused(displayId)"),
        )
        val host = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnActivity.kt").readText()
        assertTrue("the host reports its cover through the wrapper", "CompanionReturnCoordinator.hostCovered(operation)" in host)
    }

    // ---- a reclaim that keeps failing --------------------------------------------------------------------------

    private fun recovery(operationId: OperationId) = SecondaryDisplayDisposition.RecoveryNeeded(
        operationId = operationId,
        displayId = 4,
        fallback = PrimaryReturnOrigin("LIBRARY"),
        failure = FailureReason(FailureCode.RECLAIM_DEADLINE, "reclaim did not receive a matching render acknowledgment"),
    )

    @Test
    fun `two failed floor-issued reclaims in a row end the retries, and the giving up is said once`() {
        val budget = FloorReclaimBudget()
        val first = OperationId.create()
        val second = OperationId.create()

        budget.issued(first)
        assertEquals(FloorReclaimBudget.Verdict.ALLOW, budget.onFloorResume(recovery(first)))
        assertEquals(1, budget.failures())
        assertEquals("the same failure seen again is not a second one", FloorReclaimBudget.Verdict.ALLOW, budget.onFloorResume(recovery(first)))
        assertEquals(1, budget.failures())

        budget.issued(second)
        assertEquals(FloorReclaimBudget.Verdict.GIVE_UP, budget.onFloorResume(recovery(second)))
        assertEquals(2, budget.failures())
        assertEquals(FloorReclaimBudget.Verdict.STILL_GIVEN_UP, budget.onFloorResume(recovery(second)))
        assertEquals(
            "secondary home resume reclaim gave up display=4 failures=2 op=$second",
            FloorReclaimBudget.giveUpLogLine(4, 2, second),
        )
    }

    @Test
    fun `a recovery the floor did not cause, a presented mirror or a new lease starts the count again`() {
        val budget = FloorReclaimBudget()
        val ours = OperationId.create()
        val render = RenderAttempt(OperationId.create(), OperationId.create(), RendererOwnerId.create(), displayId = 4)
        val lease = HostLease(OperationId.create(), Chrome, 4, "secondary_app", PrimaryReturnOrigin("LIBRARY"))

        // Given up, then a non-floor trigger (Main resuming, an edge swipe) lost a reclaim of its own: that recovery is not ours.
        val retry = OperationId.create()
        budget.issued(ours)
        budget.onFloorResume(recovery(ours))
        budget.issued(retry)
        assertEquals(FloorReclaimBudget.Verdict.GIVE_UP, budget.onFloorResume(recovery(retry)))
        assertEquals(FloorReclaimBudget.Verdict.ALLOW, budget.onFloorResume(recovery(OperationId.create())))
        assertEquals(0, budget.failures())

        budget.issued(ours)
        budget.onFloorResume(recovery(ours))
        assertEquals(1, budget.failures())
        assertEquals(FloorReclaimBudget.Verdict.ALLOW, budget.onFloorResume(SecondaryDisplayDisposition.MirrorPresented(render)))
        assertEquals("MirrorPresented", 0, budget.failures())

        budget.issued(ours)
        budget.onFloorResume(recovery(ours))
        assertEquals(1, budget.failures())
        assertEquals(FloorReclaimBudget.Verdict.ALLOW, budget.onFloorResume(SecondaryDisplayDisposition.Yielded(lease, YieldEvidence.HOST_COVERED)))
        assertEquals("Yielded", 0, budget.failures())

        budget.issued(ours)
        budget.onFloorResume(recovery(ours))
        assertEquals("a display with nothing to reclaim is not the floor's to count", FloorReclaimBudget.Verdict.ALLOW, budget.onFloorResume(SecondaryDisplayDisposition.NoDisplay))
        assertEquals(1, budget.failures())
    }

    @Test
    fun `HOME on the Bottom over a reclaim that keeps failing tries twice, stops, and a non-floor trigger lets it try again`() {
        val bottom = Bottom()
        val chrome = bottom.leaseChrome()
        bottom.port.guardLaunchSucceeds = false

        bottom.homeOnBottom()
        assertTrue(bottom.disposition is SecondaryDisplayDisposition.RecoveryNeeded)
        bottom.homeOnBottom()
        assertEquals("the second failure is the retry", 2, bottom.port.guardLaunches.size)

        val gaveUp = bottom.runtime.snapshot.value
        bottom.homeOnBottom()
        bottom.homeOnBottom()
        assertEquals("no third reclaim from the floor", 2, bottom.port.guardLaunches.size)
        assertSame(gaveUp, bottom.runtime.snapshot.value)

        // An edge swipe is the player's, not the budget's (Main's resume shares the budget since the combined43 P1, see
        // PrimaryResumeReclaimBudgetTest): its reclaim fails on its own, and the floor may try once more after it.
        val other = requireNotNull(bottom.runtime.explicitReclaim("overlay_edge_back", bottom.origin))
        assertEquals(3, bottom.port.guardLaunches.size)
        assertEquals(other.operationId, (bottom.disposition as SecondaryDisplayDisposition.RecoveryNeeded).operationId)
        bottom.port.guardLaunchSucceeds = true

        bottom.homeOnBottom()

        assertEquals(4, bottom.port.guardLaunches.size)
        bottom.settleReclaim()
        bottom.assertCompanionIsBack(chrome)
    }

    @Test
    fun `the coordinator says so once when the floor gives up`() {
        val coordinator = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnCoordinator.kt").readText()
        assertTrue(
            "the give-up verdict logs the budget's one line",
            coordinator.substringAfter("FloorReclaimBudget.Verdict.GIVE_UP").contains("FloorReclaimBudget.giveUpLogLine("),
        )
    }

    @Test
    fun `path a - HOME on Main parks the game and the Bottom stays on Chrome, then HOME on the Bottom lands on the companion`() {
        val bottom = Bottom()
        val chrome = bottom.leaseChrome()
        val leased = bottom.runtime.snapshot.value
        val work = bottom.port.work()
        val park = parkBench(dolphinSession())

        // HOME -d 0 while Dolphin holds Main: the router's NOT_IN_FRONT from MainActivity.
        assertEquals(HomeParkVerdict.PARK, park.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN))
        runBlocking { park.scheduled.single()() }

        assertEquals("the game is parked behind Mimir, its session alive", listOf(Dolphin), park.armed.map { it.targetPackageName })
        assertTrue("parking is not an end", park.ended.isEmpty())
        assertSame("the park did not touch the Bottom: Chrome is still in front of it", leased, bottom.runtime.snapshot.value)
        assertEquals(work, bottom.port.work())

        bottom.homeOnBottom()
        bottom.settleReclaim()

        bottom.assertCompanionIsBack(chrome)
        assertEquals("and the parked game is still parked", 1, park.armed.size)
        assertTrue(park.ended.isEmpty())
    }

    @Test
    fun `path b - the game really ends behind Home while Chrome is in front, the Bottom keeps Chrome until HOME on the Bottom`() {
        val bottom = Bottom()
        val chrome = bottom.leaseChrome()
        val leased = bottom.runtime.snapshot.value
        val work = bottom.port.work()

        val releasedBy = parkWatchProvesTheTaskGone()

        assertEquals(listOf("parked task gone"), releasedBy)
        assertFalse(
            "a Main game's exit may reclaim a settled mirror or its own Return-to-game handoff, never another Bottom host",
            companionMainGameExitReclaimAllowed(bottom.disposition, Dolphin),
        )
        assertSame("the end of the game left the Bottom on Chrome", leased, bottom.runtime.snapshot.value)
        assertEquals(work, bottom.port.work())

        bottom.homeOnBottom()
        bottom.settleReclaim()

        bottom.assertCompanionIsBack(chrome)
    }

    @Test
    fun `HOME on the Bottom first, then the game ends - the companion stays up, nothing is left to resolve`() {
        val bottom = Bottom()
        val chrome = bottom.leaseChrome()
        bottom.homeOnBottom()
        bottom.settleReclaim()
        bottom.assertCompanionIsBack(chrome)
        val settled = bottom.runtime.snapshot.value
        val work = bottom.port.work()

        parkWatchProvesTheTaskGone()
        bottom.homeOnBottom()

        assertSame(settled, bottom.runtime.snapshot.value)
        assertEquals(work, bottom.port.work())
    }

    @Test
    fun `a Main game ending never reaches the Bottom's runtime, which is why the floor's resume is the only way back`() {
        val src = File("src/main/kotlin/com/gruning/mimir/rewrite/MainGameHomePark.kt").readText()
        listOf("explicitReclaim", "CompanionReturnCoordinator", "restoreAfterMainGameExit", "yieldToForeignTask", "companionController").forEach { name ->
            assertFalse("MainGameHomePark.kt must not drive the Bottom ($name): parking and releasing a game leave the Bottom to whoever holds it", name in src)
        }
    }

    // ---- what the floor's resume must still leave alone -------------------------------------------------------------------------

    @Test
    fun `a DS or 3DS lower screen is the emulator's own window above a floor that stays resumed, so the floor resuming reclaims nothing`() {
        val bottom = Bottom()
        val mirror = bottom.disposition as SecondaryDisplayDisposition.MirrorPresented
        assertTrue(bottom.runtime.yieldForExistingSecondaryGame(mirror.attempt.parentDispositionId, "me.magnum.melonds", bottom.origin))
        val yielded = bottom.runtime.snapshot.value
        val work = bottom.port.work()

        assertFalse(secondaryHomeResumeShouldReclaim(yielded.disposition))
        bottom.homeOnBottom()

        assertSame(yielded, bottom.runtime.snapshot.value)
        assertEquals(work, bottom.port.work())
        assertTrue(bottom.port.guardLaunches.isEmpty())
    }

    @Test
    fun `a floor resume reclaims over every host-covered lease but the lower screen and a lease-less yield`() {
        fun yielded(mode: String) = SecondaryDisplayDisposition.Yielded(
            HostLease(OperationId.create(), Chrome, 4, mode, PrimaryReturnOrigin("LIBRARY")),
            YieldEvidence.HOST_COVERED,
        )
        listOf("secondary_app", "secondary_game", "screen_transfer", "resume", LaunchModeRecentsPick).forEach { mode ->
            assertTrue(mode, secondaryHomeResumeShouldReclaim(yielded(mode)))
            assertEquals(mode, Chrome, secondaryHomeResumeReturningPackage(yielded(mode)))
        }
        assertFalse(secondaryHomeResumeShouldReclaim(yielded("existing_main_game_return")))
        assertNull(secondaryHomeResumeReturningPackage(yielded("existing_main_game_return")))
        assertFalse(secondaryHomeResumeShouldReclaim(SecondaryDisplayDisposition.Yielded(null, YieldEvidence.HOST_COVERED)))
        val handoff = SecondaryDisplayDisposition.Yielded(
            HostLease(OperationId.create(), Dolphin, 4, "secondary_app", PrimaryReturnOrigin("LIBRARY")),
            YieldEvidence.USER_REQUESTED_GAME_RETURN,
        )
        assertFalse("an explicit Return-to-game is not a host cover", secondaryHomeResumeShouldReclaim(handoff))
    }

    // ---- the unfinished transition ----------------------------------------------------------------------------------------------

    @Test
    fun `a scene swap that lands on a parked or dark window snaps instead of starting a fade that cannot finish`() {
        assertFalse(companionSwapSnaps(reducedMotion = false, paneMotionLive = true, windowLive = true))
        assertTrue("reduced motion", companionSwapSnaps(reducedMotion = true, paneMotionLive = true, windowLive = true))
        assertTrue("D24: the pane is parked", companionSwapSnaps(reducedMotion = false, paneMotionLive = false, windowLive = true))
        assertTrue("#228: the window is parked behind an app or dark", companionSwapSnaps(reducedMotion = false, paneMotionLive = true, windowLive = false))
    }

    @Test
    fun `the window liveness the swap reads follows the park the leased app causes and the reclaim's reveal`() {
        val parkedBehindHost = MutableStateFlow(false)
        val screenOn = MutableStateFlow(true)
        val window = CompanionWindowLive(parkedBehindHost, screenOn)
        fun snaps() = companionSwapSnaps(reducedMotion = false, paneMotionLive = true, windowLive = window.isLive)

        assertFalse("a live window animates", snaps())
        parkedBehindHost.value = true // parkBehindSecondaryHost, at HostCovered + the cover delay
        assertTrue("the game launching behind Chrome swaps Now Playing in on a parked window: snapped, settled", snaps())
        parkedBehindHost.value = false // rebindRenderOperation, at the reclaim HOME on the Bottom now causes
        assertFalse(snaps())
        screenOn.value = false
        assertTrue(snaps())
    }

    @Test
    fun `the Activity page asks the window before it animates a swap`() {
        val src = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceSurface.kt").readText()
        assertTrue(
            "the swap decision must pass windowLive.isLive, read at the swap edge",
            "companionSwapSnaps(bottomUiReducedMotion(), Snapshot.withoutReadObservation { paneMotionLive.value }, windowLive.isLive)" in src,
        )
    }

    private companion object {
        const val Chrome = "com.android.chrome"
        const val Dolphin = "org.dolphinemu.dolphinemu"
    }
}
