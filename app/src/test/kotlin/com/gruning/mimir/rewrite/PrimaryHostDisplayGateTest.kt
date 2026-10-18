package com.gruning.mimir.rewrite

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F2 (combined43 sweep, 2026-10-15): `awaitGameReturnTicket` and its settle twin polled `primaryHostOwnsWindow()`, which
 * demanded the GLOBAL window focus. On the Thor the focus is one per device, not one per display: after a Bottom guard, a Bottom Quit or the
 * floor's own resume the Bottom's window (the floor Activity, or the companion Presentation, a Dialog no lifecycle callback sees) keeps it
 * ("Focused display #3"), Main never gets it back, and the poll ran ~5 min with nothing left to wait for (emulator logcat 001905: the
 * reclaim streaks had no game session; after the give-up `primaryHostOwnsWindow()` stayed false while the floor held the focus).
 *
 * The gate ([primaryHostOwnsDisplay]) is Main RESUMED and, failing the window focus, the Bottom in Mimir's own hands by the runtime's
 * disposition ([companionBottomOwnedByMimir]): then whichever display holds the focus, Mimir's task is the one on top of Main's. The
 * protection stays where it matters: a foreign task on the Bottom (`Yielded`, a launch in flight), or no Bottom at all, leaves the window
 * focus the only truth, as before. These fail on the base, whose gate was `resumed && windowFocused`.
 */
class PrimaryHostDisplayGateTest {

    @Before
    fun clearTheLedger() {
        MainGameLedgerFixture.endAnyLive()
    }

    @After
    fun reset() {
        CompanionReturnCoordinator.resetTestOverrides()
        MainGameLedgerFixture.endAnyLive()
    }

    @Test
    fun `resumed with the window focus is enough on any device, and not resumed is never enough`() {
        assertTrue(primaryHostOwnsDisplay(resumed = true, windowFocused = true, bottomOwnedByMimir = false))
        assertTrue(primaryHostOwnsDisplay(resumed = true, windowFocused = true, bottomOwnedByMimir = true))
        assertFalse(primaryHostOwnsDisplay(resumed = false, windowFocused = true, bottomOwnedByMimir = true))
        assertFalse(primaryHostOwnsDisplay(resumed = false, windowFocused = false, bottomOwnedByMimir = true))
    }

    @Test
    fun `without the window focus Main owns its display only while the Bottom is Mimir's`() {
        assertTrue("the Thor: the Bottom holds the one focus, and it is Mimir's", primaryHostOwnsDisplay(resumed = true, windowFocused = false, bottomOwnedByMimir = true))
        assertFalse("a foreign Bottom, or a single screen: the window focus is the only truth", primaryHostOwnsDisplay(resumed = true, windowFocused = false, bottomOwnedByMimir = false))
    }

    @Test
    fun `the runtime's disposition says whose the Bottom is`() {
        val bench = Bench()
        assertFalse("no Bottom at all", companionBottomOwnedByMimir(SecondaryDisplayDisposition.NoDisplay))

        bench.runtime.displayAvailable(4)
        assertTrue(bench.disposition is SecondaryDisplayDisposition.Presenting)
        assertTrue("Mimir is presenting", companionBottomOwnedByMimir(bench.disposition))
        bench.presentationShown()
        assertTrue("Mimir's mirror is up", companionBottomOwnedByMimir(bench.disposition))

        assertNotNull(bench.runtime.explicitReclaim("overlay_edge_back", bench.origin))
        assertTrue(bench.disposition is SecondaryDisplayDisposition.ReclaimPending)
        assertTrue("a guard of Mimir's own is taking the Bottom back", companionBottomOwnedByMimir(bench.disposition))
        bench.guardMissesDeadline()
        assertTrue("a reclaim that missed its deadline left no foreign task there: the Bottom is Mimir's, in recovery", companionBottomOwnedByMimir(bench.disposition))

        val recoveredNonMirror = SecondaryDisplayDisposition.RecoveryNeeded(
            operationId = OperationId.create(),
            displayId = 4,
            fallback = bench.origin,
            failure = FailureReason(FailureCode.PROCESS_RECOVERED_NON_MIRROR, "process recovered with a non-mirror host"),
        )
        assertFalse("a process recovery found something else on the Bottom", companionBottomOwnedByMimir(recoveredNonMirror))
    }

    @Test
    fun `a foreign task on the Bottom, or a launch in flight, is not Mimir's`() {
        val bench = Bench()
        bench.runtime.displayAvailable(4)
        bench.presentationShown()
        assertTrue(bench.runtime.yieldToForeignTask(displayId = 4, packageName = "com.android.chrome", primaryOrigin = bench.origin))
        assertTrue(bench.disposition is SecondaryDisplayDisposition.Yielded)
        assertFalse("Chrome holds the Bottom: Main waits for the window focus as it always did", companionBottomOwnedByMimir(bench.disposition))

        val launching = Bench()
        launching.runtime.displayAvailable(4)
        launching.presentationShown()
        assertNotNull(launching.runtime.launchRequested("com.example.game", displayId = 4, launchMode = "quick_launch", primaryOrigin = launching.origin))
        assertTrue(launching.disposition is SecondaryDisplayDisposition.LaunchPending)
        assertFalse("a launch is on its way to the Bottom", companionBottomOwnedByMimir(launching.disposition))
    }

    /**
     * MainActivity's `awaitGameReturnTicket`, on the real ledger and the real coordinator: Main resumes over a recovery, the budget issues
     * its reclaim (a guard on the Bottom), the Bottom keeps the window focus throughout, and the ticket is taken at once because the Bottom is
     * Mimir's: `game_returning`, the ledger leaves YIELDED. On the base the same poll never completes (`windowFocused` is false every time).
     */
    @Test
    fun `the game-return ticket completes while the Bottom holds the window focus`() {
        val bench = Bench()
        bench.runtime.displayAvailable(4)
        bench.presentationShown()
        CompanionReturnCoordinator.installRuntime(bench.runtime)
        val operation = MainGameLedgerFixture.start()
        assertTrue(MimirSessionAuthority.gameYielded(operation))
        assertNotNull(bench.runtime.explicitReclaim("overlay_edge_back", bench.origin))
        bench.guardMissesDeadline()

        assertTrue("Main's resume reclaims", CompanionReturnCoordinator.reclaimOnPrimaryResume(bench.disposition, wanted = shouldReclaimCompanionAfterPrimaryResume(bench.runtime.snapshot.value)))
        assertTrue(bench.disposition is SecondaryDisplayDisposition.ReclaimPending)

        val owns = primaryHostOwnsDisplay(
            resumed = true,
            windowFocused = false,
            bottomOwnedByMimir = companionBottomOwnedByMimir(bench.disposition),
        )
        assertTrue("the Bottom has the focus and it is Mimir's guard: Main is on top of its own display", owns)
        val ticket = MimirSessionAuthority.beginGameReturn(operation)
        assertNotNull("the ticket is taken on this very resume", ticket)
        assertEquals(MimirSessionPhase.RETURNING, MimirSessionAuthority.state.value.gameSession?.phase)
    }

    @Test
    fun `a Bottom that is not Mimir's keeps the ticket waiting, as before`() {
        val bench = Bench()
        bench.runtime.displayAvailable(4)
        bench.presentationShown()
        CompanionReturnCoordinator.installRuntime(bench.runtime)
        val operation = MainGameLedgerFixture.start()
        assertTrue(MimirSessionAuthority.gameYielded(operation))
        assertTrue(bench.runtime.yieldToForeignTask(displayId = 4, packageName = "com.android.chrome", primaryOrigin = bench.origin))

        val owns = primaryHostOwnsDisplay(resumed = true, windowFocused = false, bottomOwnedByMimir = companionBottomOwnedByMimir(bench.disposition))
        assertFalse(owns)
        assertNull("the poll takes nothing while Chrome holds the focus on the Bottom", if (owns) MimirSessionAuthority.beginGameReturn(operation) else null)
        assertEquals(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK, MimirSessionAuthority.state.value.gameSession?.phase)
    }

    @Test
    fun `MainActivity's one gate is this one, fed the runtime's disposition`() {
        val main = File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()
        val gate = main.substringAfter("private fun primaryHostOwnsWindow(): Boolean").substringBefore("override fun onPause()")
        assertTrue(gate.contains("primaryHostOwnsDisplay("))
        assertTrue(gate.contains("resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),"))
        assertTrue(gate.contains("windowFocused = window.decorView.hasWindowFocus(),"))
        assertTrue(gate.contains("bottomOwnedByMimir = companionBottomOwnedByMimir(CompanionReturnCoordinator.runtime().snapshot.value.disposition),"))
        assertEquals("every return poll goes through the one gate", 5, Regex(Regex.escape("primaryHostOwnsWindow()")).findAll(main).count())
    }

    // ---- the bench: a runtime with a fake Bottom port, as PrimaryResumeReclaimBudgetTest drives it ----------------------------

    private class Port : CompanionHostPort {
        override val rendererOwnerId: RendererOwnerId = RendererOwnerId.create()
        override fun render(attempt: RenderAttempt) = Unit
        override fun dismissPresentation() = Unit
        override fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long) = Unit
        override fun prepareForReclaim(lease: ReclaimLease) = Unit
        override fun launchRestoreGuard(lease: ReclaimLease): Boolean = true
    }

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

    private class Bench {
        val scheduler = Scheduler()
        val runtime = CompanionRuntimeCoordinator(scheduler).also { it.bindRenderer(Port()) }
        val origin = PrimaryReturnOrigin("LIBRARY")
        val disposition: SecondaryDisplayDisposition get() = runtime.snapshot.value.disposition

        fun presentationShown() {
            val attempt = (disposition as SecondaryDisplayDisposition.Presenting).attempt
            assertTrue(runtime.presentationShown(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId))
            assertTrue(disposition is SecondaryDisplayDisposition.MirrorPresented)
        }

        fun guardMissesDeadline() {
            val pending = disposition as SecondaryDisplayDisposition.ReclaimPending
            assertTrue(runtime.restoreGuardForeground(pending.lease.operationId))
            scheduler.fire(pending.lease.operationId)
            val recovery = disposition as SecondaryDisplayDisposition.RecoveryNeeded
            assertEquals(FailureCode.RECLAIM_DEADLINE, recovery.failure.code)
        }
    }
}
