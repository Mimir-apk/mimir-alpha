package com.gruning.mimir.rewrite

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1 (combined43 sweep, emulator 2026-10-15, `build/test-captures/combined43/sweep/SWEEP.md` F2 and the RECLAIM_DEADLINE observation; logcat 001905 lines
 * 1969-4233): MainActivity reclaimed the Bottom on EVERY resume while the runtime said `RecoveryNeeded`. Each reclaim started a restore guard,
 * the guard took top focus from Main, missed its deadline (`reclaim did not receive a matching render acknowledgment`), and the deadline
 * resumed Main, which reclaimed again: streaks of 7, 25 and 21 `primary_activity_resumed` reclaims with no give-up, where the floor's own
 * path (`secondary_home_resumed`, budgeted by [FloorReclaimBudget]) ran exactly twice and stopped. Nothing bounded it, so `awaitGameReturnTicket`,
 * which waits for Main to own its window, never got its ticket: no `game_returning`, the ledger stuck in `YIELDED_TO_SECONDARY_TASK`, PLAY
 * refused (`launch_refused hold=RUNNING`), the launch card never restored, the "Welcome back" cover up for 13-21 s. The Thor control
 * (`build/test-captures/combined43/thor/THOR.md` check 1) returned in 0.3 s because its Bottom acknowledged; the loop has no terminal state wherever it does not.
 *
 * The fix: Main's resume draws on the SAME budget as the floor's ([CompanionReturnCoordinator.reclaimOnPrimaryResume]), per resume EPISODE:
 * Main's resumed interval, reset on a real stop and start (Lead, fix round 2026-10-15), so a later return (Recents, a primary-app return,
 * HOME) reclaims again and is bounded again. These tests drive the real runtime and the real coordinator against a fake Bottom port whose
 * guard comes up and never acknowledges, from the exact sequence the log shows. The return ticket is NOT the budget's: it is the display
 * gate's (PrimaryHostDisplayGateTest), and the one ticket case here shows it completing with the budget's guard still in flight. They fail
 * on the base with the budget consult, the episode reset or the gate removed.
 */
class PrimaryResumeReclaimBudgetTest {

    @Before
    fun clearTheLedger() {
        MainGameLedgerFixture.endAnyLive()
    }

    @After
    fun reset() {
        CompanionReturnCoordinator.resetTestOverrides()
        MainGameLedgerFixture.endAnyLive()
    }

    // ---- the bench ------------------------------------------------------------------------------------------------------------

    private class Port : CompanionHostPort {
        override val rendererOwnerId: RendererOwnerId = RendererOwnerId.create()
        val renders = mutableListOf<RenderAttempt>()
        val guardLaunches = mutableListOf<ReclaimLease>()

        override fun render(attempt: RenderAttempt) {
            renders += attempt
        }

        override fun dismissPresentation() = Unit

        override fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long) = Unit

        override fun prepareForReclaim(lease: ReclaimLease) = Unit

        override fun launchRestoreGuard(lease: ReclaimLease): Boolean {
            guardLaunches += lease
            return true
        }
    }

    /** Every deadline the runtime schedules, by operation; the test fires the one it means, as the main-thread scheduler would on time. */
    private class Scheduler : CompanionRuntimeScheduler {
        private val pending = mutableMapOf<OperationId, () -> Unit>()

        override fun schedule(delayMillis: Long, operationId: OperationId, action: () -> Unit): RuntimeCancellation {
            pending[operationId] = action
            return RuntimeCancellation { pending.remove(operationId) }
        }

        fun fire(operationId: OperationId) {
            requireNotNull(pending.remove(operationId)) { "no deadline is scheduled for $operationId" }()
        }
    }

    /** The Bottom as the sweep had it before the loop: Mimir's companion mirrored on display 4, this runtime installed as the process runtime. */
    private class Bench {
        val port = Port()
        val scheduler = Scheduler()
        val runtime = CompanionRuntimeCoordinator(scheduler)
        val origin = PrimaryReturnOrigin("LIBRARY")

        init {
            runtime.bindRenderer(port)
            runtime.displayAvailable(4)
            val attempt = (disposition as SecondaryDisplayDisposition.Presenting).attempt
            assertTrue(runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
            CompanionReturnCoordinator.installRuntime(runtime)
        }

        val disposition: SecondaryDisplayDisposition get() = runtime.snapshot.value.disposition

        /** A restore guard is up on the Bottom: it holds the one window focus, and Main is resumed on display 0 under it. */
        val guardInFlight: Boolean get() = disposition is SecondaryDisplayDisposition.ReclaimPending

        /** MainActivity.onResume's reclaim block: the resume policy's answer, then the budgeted entry. */
        fun mainResumes(resumedAfterStop: Boolean = false): Boolean = CompanionReturnCoordinator.reclaimOnPrimaryResume(
            disposition = disposition,
            wanted = shouldReclaimCompanionAfterPrimaryResume(runtime.snapshot.value),
            resumedAfterStop = resumedAfterStop,
        )

        /** MainActivity.onStop, a real stop: the budget hears it with the Bottom as the runtime has it. */
        fun mainStops() = CompanionReturnCoordinator.notePrimaryStopped()

        /** MainActivity's `primaryHostOwnsWindow()` on the Thor: Main resumed, the window focus on the Bottom, the Bottom as the runtime has it. */
        fun mainOwnsItsDisplay(): Boolean = primaryHostOwnsDisplay(
            resumed = true,
            windowFocused = false,
            bottomOwnedByMimir = companionBottomOwnedByMimir(disposition),
        )

        /** The guard comes to the foreground and the Presentation never acknowledges: the root deadline fires (`ReclaimDeadline`), and Main resumes. */
        fun guardMissesDeadline() {
            val pending = disposition as SecondaryDisplayDisposition.ReclaimPending
            assertTrue(runtime.restoreGuardForeground(pending.lease.operationId))
            scheduler.fire(pending.lease.operationId)
            val recovery = disposition as SecondaryDisplayDisposition.RecoveryNeeded
            assertEquals(FailureCode.RECLAIM_DEADLINE, recovery.failure.code)
            assertEquals(pending.lease.operationId, recovery.operationId)
        }

        /** The guard comes up and the Presentation commits its frame: the reclaim settles. */
        fun guardSettles() {
            val pending = disposition as SecondaryDisplayDisposition.ReclaimPending
            assertTrue(runtime.restoreGuardForeground(pending.lease.operationId))
            val attempt = (disposition as SecondaryDisplayDisposition.ReclaimPending).expectedRender!!
            assertTrue(runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
            assertTrue(disposition is SecondaryDisplayDisposition.MirrorPresented)
        }

        /** The player's own reclaim (the right-edge swipe) that lost: a recovery no automatic path caused, the way every episode begins. */
        fun edgeSwipeFails() {
            assertNotNull(runtime.explicitReclaim("overlay_edge_back", origin))
            guardMissesDeadline()
        }
    }

    // ---- the loop, bounded ----------------------------------------------------------------------------------------------------

    @Test
    fun `a primary-issued reclaim that keeps missing its deadline is retried once, then given up, and every resume after that is silent`() {
        val bench = Bench()
        bench.edgeSwipeFails()
        assertEquals("the edge swipe's own guard", 1, bench.port.guardLaunches.size)

        assertTrue("the first resume reclaims", bench.mainResumes())
        assertEquals("primary_activity_resumed", bench.port.guardLaunches.last().reason)
        bench.guardMissesDeadline()
        assertTrue("a failure of our own is the retry", bench.mainResumes())
        bench.guardMissesDeadline()
        assertEquals("two primary reclaims", 3, bench.port.guardLaunches.size)

        assertFalse("the second failure in a row is the give-up", bench.mainResumes())
        val terminal = bench.runtime.snapshot.value
        repeat(5) { assertFalse(bench.mainResumes()) }

        assertEquals("no third reclaim from Main, however often it resumes", 3, bench.port.guardLaunches.size)
        assertSame("the terminal state is a recovery with no guard in flight, left to the player's own paths", terminal, bench.runtime.snapshot.value)
        assertTrue(bench.disposition is SecondaryDisplayDisposition.RecoveryNeeded)
    }

    /**
     * MainActivity's `awaitGameReturnTicket`, on the real ledger: it polls the display gate (`primaryHostOwnsWindow()`, see
     * PrimaryHostDisplayGateTest) and takes `beginGameReturn` the first time Main is resumed over a Bottom that is Mimir's. The window focus is
     * on the Bottom for the whole episode (the guard, then the recovery, then the floor), so the gate, not the focus, completes it: on the FIRST
     * resume, with the budget's own guard still in flight, `game_returning` is published and the ledger leaves YIELDED. The budget bounds the
     * reclaims; the ticket never waited for them. (Before the fix round this test waited for the budget to give up, which the Lead proved was
     * the budget, not the ticket: the real poll demanded the window focus and never got it.)
     */
    @Test
    fun `the game-return ticket completes on the first resume, with the budget's guard still in flight`() {
        val bench = Bench()
        val operation = MainGameLedgerFixture.start()
        assertTrue(MimirSessionAuthority.gameYielded(operation))
        bench.edgeSwipeFails()

        var ticket: MimirGameReturnTicket? = null
        var resumes = 0
        while (ticket == null && resumes < 8) {
            resumes += 1
            bench.mainResumes()
            if (bench.mainOwnsItsDisplay()) ticket = MimirSessionAuthority.beginGameReturn(operation)
            if (ticket == null && bench.guardInFlight) bench.guardMissesDeadline()
        }

        assertNotNull("the ticket is taken while the Bottom holds the focus", ticket)
        assertEquals("on the first resume", 1, resumes)
        assertTrue("the guard that resume issued is still up", bench.guardInFlight)
        assertEquals("the edge swipe's guard and the budget's first", 2, bench.port.guardLaunches.size)
        assertEquals(MimirSessionPhase.RETURNING, MimirSessionAuthority.state.value.gameSession?.phase)
    }

    /**
     * The loop as the emulator ran it (repeat check of the second run, logcat 001905 01:10:21-01:10:44): reclaim, guard, the deadline
     * (`ReclaimDeadline` at :36.104), Main's stop (`Activity reported stop` at :44.016), Main's resume and the next reclaim (:44.541). Every
     * cycle STOPS Main, so a stop alone must not begin a new episode: the budget stays at two reclaims, then the give-up, and the loop ends
     * because no guard is up to stop Main again. Both orders of deadline and stop are the cycle's own. Fails on round 2, which reset on every stop.
     */
    @Test
    fun `the loop's own stops do not begin a new episode, in either order of deadline and stop`() {
        for (deadlineFirst in listOf(true, false)) {
            val bench = Bench()
            bench.edgeSwipeFails()
            assertTrue("the first resume reclaims", bench.mainResumes())
            var cycles = 0
            while (bench.guardInFlight && cycles < 10) {
                cycles += 1
                if (deadlineFirst) {
                    bench.guardMissesDeadline()
                    bench.mainStops()
                } else {
                    bench.mainStops()
                    bench.guardMissesDeadline()
                }
                bench.mainResumes(resumedAfterStop = true)
            }
            assertEquals("deadlineFirst=$deadlineFirst: the retry, then the give-up, then no guard to cycle on", 2, cycles)
            assertEquals("deadlineFirst=$deadlineFirst: the edge swipe's guard and the budget's two", 3, bench.port.guardLaunches.size)
            assertTrue(bench.disposition is SecondaryDisplayDisposition.RecoveryNeeded)
            repeat(3) { assertFalse(bench.mainResumes()) }

            // The player leaves (Recents, another app) in the settled recovery and comes back: that stop is theirs, and the return reclaims.
            bench.mainStops()
            assertTrue("deadlineFirst=$deadlineFirst: the player's own return re-arms", bench.mainResumes(resumedAfterStop = true))
            assertEquals(4, bench.port.guardLaunches.size)
            CompanionReturnCoordinator.resetTestOverrides()
        }
    }

    @Test
    fun `a stop and start of Main is a new episode, so the budget is spent again and bounded again`() {
        val bench = Bench()
        bench.edgeSwipeFails()
        assertTrue(bench.mainResumes())
        bench.guardMissesDeadline()
        assertTrue(bench.mainResumes())
        bench.guardMissesDeadline()
        assertFalse("given up", bench.mainResumes())
        assertEquals(3, bench.port.guardLaunches.size)

        // Main stops under the game (or Recents, or HOME) and starts again: the player came back on purpose.
        bench.mainStops()
        assertTrue("the return reclaims", bench.mainResumes(resumedAfterStop = true))
        assertEquals(4, bench.port.guardLaunches.size)
        bench.guardMissesDeadline()
        assertTrue("and may retry once", bench.mainResumes())
        assertEquals(5, bench.port.guardLaunches.size)
        bench.guardMissesDeadline()
        assertFalse("and gives up again: two per episode, the pause-only cycles of the loop between them never reset it", bench.mainResumes())
        repeat(3) { assertFalse(bench.mainResumes()) }
        assertEquals(5, bench.port.guardLaunches.size)
    }

    @Test
    fun `a resume the policy does not want still counts, so the give-up lands on it and the next wanted resume is silent`() {
        val bench = Bench()
        bench.edgeSwipeFails()
        assertTrue(bench.mainResumes())
        bench.guardMissesDeadline()
        assertTrue(bench.mainResumes())
        bench.guardMissesDeadline()

        assertFalse(CompanionReturnCoordinator.reclaimOnPrimaryResume(bench.disposition, wanted = false))
        assertFalse("the give-up was taken on the unwanted resume: nothing issues now", bench.mainResumes())
        assertEquals(3, bench.port.guardLaunches.size)
    }

    @Test
    fun `the floor and Main draw on one budget, so neither resets the other`() {
        val bench = Bench()
        bench.edgeSwipeFails()

        CompanionReturnCoordinator.noteSecondaryHomeResumed(4)
        assertEquals("the floor's reclaim", 2, bench.port.guardLaunches.size)
        assertEquals("secondary_home_resumed", bench.port.guardLaunches.last().reason)
        bench.guardMissesDeadline()

        assertTrue("Main counts the floor's failure as its own and may still retry once", bench.mainResumes())
        assertEquals(3, bench.port.guardLaunches.size)
        bench.guardMissesDeadline()

        CompanionReturnCoordinator.noteSecondaryHomeResumed(4)
        assertEquals("the floor counts Main's failure: the second in a row, so it gives up", 3, bench.port.guardLaunches.size)
        assertFalse("and Main after it is silent", bench.mainResumes())
        assertEquals(3, bench.port.guardLaunches.size)
    }

    @Test
    fun `a new game launch after the give-up is a new episode, and a reclaim that settles ends it`() {
        val bench = Bench()
        val first = MainGameLedgerFixture.start()
        bench.edgeSwipeFails()
        assertTrue(bench.mainResumes())
        bench.guardMissesDeadline()
        assertTrue(bench.mainResumes())
        bench.guardMissesDeadline()
        assertFalse("given up", bench.mainResumes())
        assertEquals(3, bench.port.guardLaunches.size)

        MainGameLedgerFixture.end(first)
        val second = MainGameLedgerFixture.start()
        assertTrue("the ledger moved on: a new launch", second != first)

        assertTrue("the automatic paths may try again for the new game", bench.mainResumes())
        assertEquals(4, bench.port.guardLaunches.size)
        bench.guardSettles()

        assertFalse("a presented Bottom wants no reclaim", bench.mainResumes())
        assertTrue(bench.runtime.snapshot.value.mirrorActive)
        assertEquals(4, bench.port.guardLaunches.size)
    }

    // ---- the budget itself, pure -----------------------------------------------------------------------------------------------

    private fun recovery(operationId: OperationId) = SecondaryDisplayDisposition.RecoveryNeeded(
        operationId = operationId,
        displayId = 4,
        fallback = PrimaryReturnOrigin("LIBRARY"),
        failure = FailureReason(FailureCode.RECLAIM_DEADLINE, "reclaim did not receive a matching render acknowledgment"),
    )

    @Test
    fun `a game launched since the episode began starts the count again`() {
        var sequence: Long? = 1L
        val budget = FloorReclaimBudget(gameSequence = { sequence })
        val first = OperationId.create()
        val second = OperationId.create()
        val third = OperationId.create()

        budget.issued(first)
        assertEquals(FloorReclaimBudget.Verdict.ALLOW, budget.onResume(recovery(first)))
        assertEquals(1, budget.failures())

        budget.issued(second)
        sequence = 2L
        assertEquals("the launch between the two failures is a new episode", FloorReclaimBudget.Verdict.ALLOW, budget.onResume(recovery(second)))
        assertEquals(0, budget.failures())

        budget.issued(third)
        assertEquals(FloorReclaimBudget.Verdict.ALLOW, budget.onResume(recovery(third)))
        assertEquals("counted against the new episode", 1, budget.failures())
        assertEquals("primary resume reclaim gave up display=4 failures=2 op=$third", FloorReclaimBudget.primaryGiveUpLogLine(4, 2, third))
    }

    @Test
    fun `a stop is the loop's own only with its reclaim in flight or its recovery not yet counted`() {
        val budget = FloorReclaimBudget()
        val op = OperationId.create()

        budget.hostStopped(SecondaryDisplayDisposition.NoDisplay)
        assertTrue("no reclaim of its own: the player's stop", budget.hostResumedAfterStop())

        budget.issued(op)
        budget.hostStopped(recovery(op))
        assertFalse("the deadline fired while the guard still covered Main: the loop's stop, nothing reset", budget.hostResumedAfterStop())
        assertEquals("and the issued reclaim is still what the next resume is judged on", FloorReclaimBudget.Verdict.ALLOW, budget.onResume(recovery(op)))
        assertEquals(1, budget.failures())

        budget.hostStopped(recovery(op))
        assertTrue("a resume counted this recovery already: a stop now is the player's", budget.hostResumedAfterStop())
        assertEquals(0, budget.failures())

        budget.issued(op)
        budget.hostStopped(recovery(OperationId.create()))
        assertTrue("a recovery that is not ours: the player's stop", budget.hostResumedAfterStop())
        assertFalse("a stop the budget never heard of is the player's too", run { budget.issued(op); !budget.hostResumedAfterStop() })
    }

    @Test
    fun `the coordinator says so once when Main gives up, and MainActivity reclaims through nothing else`() {
        val coordinator = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnCoordinator.kt").readText()
        // Placed after the floor's reclaim on purpose: BottomGameHomeParkTest orders the file's first `explicitReclaim(` after `onBottomFloorHome`.
        val entry = coordinator.substringAfter("fun reclaimOnPrimaryResume(").substringBefore("fun noteSecondaryHomePaused(")
        assertTrue("the give-up verdict logs the budget's one line", entry.substringAfter("FloorReclaimBudget.Verdict.GIVE_UP").contains("FloorReclaimBudget.primaryGiveUpLogLine("))
        val policyConsulted = entry.indexOf("if (!wanted || verdict != FloorReclaimBudget.Verdict.ALLOW) return false")
        assertTrue("the policy's answer is consulted once, after the budget", policyConsulted > 0)
        assertTrue("the budget hears the resume before the policy's answer is consulted", entry.indexOf("autoReclaimBudget.onResume(") < policyConsulted)
        assertTrue("the give-up is said before the policy's answer is consulted, so a resume the policy does not want still says it", entry.indexOf("FloorReclaimBudget.primaryGiveUpLogLine(") < policyConsulted)
        assertTrue("a resume after a stop asks the budget whose stop it was, before the budget hears the resume", entry.indexOf("if (resumedAfterStop) autoReclaimBudget.hostResumedAfterStop()") in 0 until entry.indexOf("autoReclaimBudget.onResume("))
        assertFalse("never a bare reset on a stop: the guard's own cycle stops Main", entry.contains("autoReclaimBudget.reset()"))
        assertTrue("the issued lease is what the next resume is judged on", entry.contains("autoReclaimBudget.issued(it.operationId)"))
        val stopNote = coordinator.substringAfter("fun notePrimaryStopped()").substringBefore("\n    }")
        assertTrue("the stop is judged on the Bottom's state", stopNote.contains("autoReclaimBudget.hostStopped(runtime().snapshot.value.disposition)"))

        val main = File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()
        val resume = main.substringAfter("override fun onResume() {").substringBefore("private fun settleReturningGameSession(")
        assertTrue(resume.contains("CompanionReturnCoordinator.reclaimOnPrimaryResume("))
        assertTrue("the port guard stays", resume.contains("wanted = reclaimOnResume && companionController != null,"))
        assertTrue("the ledger's own stop-to-start signal marks the episode", resume.contains("resumedAfterStop = resumedAfterStop,"))
        assertFalse("no unbudgeted reclaim is left on the resume", resume.contains("restore(\"primary_activity_resumed\")"))
        val stop = main.substringAfter("override fun onStop() {").substringBefore("override fun onNewIntent(")
        assertTrue("every real stop tells the budget, inside the same configuration-change guard as the ledger's own stop note", stop.substringAfter("if (!isChangingConfigurations) {").contains("CompanionReturnCoordinator.notePrimaryStopped()"))
    }
}
