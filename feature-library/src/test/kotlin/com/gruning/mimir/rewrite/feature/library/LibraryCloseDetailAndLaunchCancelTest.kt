package com.gruning.mimir.rewrite.feature.library

import androidx.lifecycle.viewModelScope
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 6, R6-F-06 and R6-F-07 — two defects the review of R6-5E-03 found, each its own mechanism, not the
 * reconcile races in [LibraryDetailSnapshotRaceTest]. One red test per defect (policy: avoid
 * testing/overengineering).
 */
class LibraryCloseDetailAndLaunchCancelTest {

    /**
     * R6-F-06. [LibraryViewModel.openGameDetail] always issues an Open-with load ([LibraryViewModel.loadOpenWith]),
     * and [LibraryViewModel.closeGameDetail] used to only null the detail snapshot — never bumping the Open-with
     * request token or touching [LibraryViewModel.detailGame]. Neither guard `loadOpenWith`'s completion checks
     * changes on close, so a load landing afterward wrote stale options for a card that is no longer open.
     *
     * Fully deterministic, no thread interleaving needed: the load is parked behind a suspend call the test
     * controls, so "close, then let the load land" is a plain sequential order on one thread.
     */
    @Test
    fun `closing the card leaves no stale open-with options for a load still in flight`() = runBlocking {
        val repository = FakeLibraryRepository()
        val game = repository.observeGames(PlatformId.PS1).first().first()
        val port = GatedOpenWithPort(OpenWithOptions(hasOverride = false, candidates = emptyList()))
        val viewModel = LibraryViewModel(repository, port)

        val scopeJob = viewModel.viewModelScope.coroutineContext.job
        val before = scopeJob.children.toSet()
        viewModel.openGameDetail(game)
        val load = (scopeJob.children.toSet() - before).single()

        viewModel.closeGameDetail()
        port.release()
        awaitWithin("the open-with load finishing") { load.join() }

        assertNull(
            "he closed the card before its open-with load returned: the load must not leave stale options " +
                "behind for a card that is no longer open",
            viewModel.openWith.value,
        )
    }

    /**
     * R6-F-07. `launch()`'s completion arms and [LibraryViewModel.cancelPendingLaunch] shared the same
     * check-then-write shape as R6-5E-03's race 2 (`spec-R6-5E-03.md` §6): the arm's guard
     * (`latestLaunchOperation == operation`) and its write were two separate steps, so a watchdog cancel landing
     * between them was undone by the arm's late, stale write.
     *
     * [LaunchCompletionGate] holds the arm at the production test seam
     * ([LibraryViewModel.launchCompletionGuardHook]) right after its guard passes. The watchdog's cancel runs on
     * its own real thread — pre-fix it is lock-free and completes immediately; after the fix it contends for the
     * same monitor the held arm holds (from before its guard, straight through the hook), so it can genuinely
     * block there without deadlocking this test. `aboutToCancel` is signalled on that thread the instruction
     * before the call itself, so the wait for it names the same certainty [InterleavedSnapshot] relies on
     * elsewhere in this suite: a real synchronization point, not a sleep.
     */
    @Test
    fun `a launch completion that outraces a watchdog cancel does not re-arm the return target`() = runBlocking {
        val repository = FakeLibraryRepository()
        val game = repository.observeGames(PlatformId.PS1).first().first()
        var capturedOperation: LibraryGameLaunchOperation? = null
        val observer = LibraryGameSessionObserver { event ->
            if (event is LibraryGameSessionEvent.Preparing) capturedOperation = event.operation
            true
        }
        val port = ImmediateLaunchPort(intentSent(game))
        val viewModel = LibraryViewModel(repository, port, observer)
        val gate = LaunchCompletionGate()
        viewModel.launchCompletionGuardHook = gate.hook()

        val scopeJob = viewModel.viewModelScope.coroutineContext.job
        val before = scopeJob.children.toSet()
        viewModel.launch(game)
        val operation = requireNotNull(capturedOperation) { "fixture: launch() always publishes Preparing first" }
        val completion = (scopeJob.children.toSet() - before).single()
        awaitWithin("the completion arm's guard passing") { gate.entered.await() }

        val cancelled = CompletableDeferred<Boolean>()
        val aboutToCancel = CountDownLatch(1)
        Thread {
            aboutToCancel.countDown()
            cancelled.complete(viewModel.cancelPendingLaunch(operation))
        }.start()
        check(aboutToCancel.await(LibraryTestAwaitMs, TimeUnit.MILLISECONDS)) {
            "the watchdog thread never started"
        }

        gate.release()
        awaitWithin("the completion arm finishing") { completion.join() }
        assertTrue(
            "fixture: the watchdog's cancel matched the live operation",
            awaitWithin("the watchdog's cancel finishing") { cancelled.await() },
        )

        assertNull(
            "the watchdog cancelled this launch before the stale completion arm wrote its result: the return " +
                "target must not come back",
            viewModel.pendingLaunchReturnTarget.value,
        )
    }

    /**
     * Review 2026-10-14 (finding 6): the completion arm used to write `if (launched) returnTarget else null`, which RE-SET the anchor
     * when `port.launch` returned after the cover's lift had already cleared it. The re-set target then read as a restore in flight
     * (the settled motion strips every Library change's motion) and was re-armed on the next pause. The arm now only clears.
     */
    @Test
    fun `a launch completion that outraces the cover's lift does not write the return target back`() = runBlocking {
        val repository = FakeLibraryRepository()
        val game = repository.observeGames(PlatformId.PS1).first().first()
        val port = ImmediateLaunchPort(intentSent(game))
        val viewModel = LibraryViewModel(repository, port)
        val gate = LaunchCompletionGate()
        viewModel.launchCompletionGuardHook = gate.hook()

        val scopeJob = viewModel.viewModelScope.coroutineContext.job
        val before = scopeJob.children.toSet()
        viewModel.launch(game)
        val completion = (scopeJob.children.toSet() - before).single()
        awaitWithin("the completion arm's guard passing") { gate.entered.await() }

        val target = requireNotNull(viewModel.pendingLaunchReturnTarget.value) { "fixture: the anchor is published before dispatch" }
        viewModel.restoreLaunchReturnTargetState(target)
        assertTrue("fixture: the restore is under way", viewModel.restoringLaunchReturnTarget() === target)
        viewModel.clearPendingLaunchReturnTarget() // the cover's lift, before the launch call returned

        gate.release()
        awaitWithin("the completion arm finishing") { completion.join() }

        assertNull("the late completion must not re-arm the anchor", viewModel.pendingLaunchReturnTarget.value)
        assertNull("and nothing reads as a restore in flight", viewModel.restoringLaunchReturnTarget())
    }

    private fun intentSent(game: GameRow) = LaunchResult(
        readiness = LaunchReadiness.Ready(
            LaunchTarget(
                gameId = game.gameId,
                platformId = game.platformId,
                emulator = EmulatorProfile(
                    id = EmulatorId("ps1-emulator"),
                    packageName = "com.example.emulator",
                    displayName = "Emulator",
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

    /** Suspends on [release] before returning [options] — lets a test land an action while the load is parked. */
    private class GatedOpenWithPort(private val options: OpenWithOptions) : LibraryLaunchPort {
        private val gate = CompletableDeferred<Unit>()
        fun release() = gate.complete(Unit)

        override suspend fun openWithOptions(game: GameRow): OpenWithOptions {
            gate.await()
            return options
        }

        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) = Unit
        override suspend fun clearEmulatorOverride(game: GameRow) = Unit
        override suspend fun launch(
            game: GameRow,
            preferredDisc: Int,
            launchMemberEntryKey: EntryKey?,
        ): LaunchResult = LaunchResult(LaunchReadiness.Unsupported(game.platformId))
    }

    /** Returns [result] immediately — the race under test is inside the VM's completion arm, not the port. */
    private class ImmediateLaunchPort(private val result: LaunchResult) : LibraryLaunchPort {
        override suspend fun openWithOptions(game: GameRow): OpenWithOptions =
            OpenWithOptions(hasOverride = false, candidates = emptyList())

        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) = Unit
        override suspend fun clearEmulatorOverride(game: GameRow) = Unit
        override suspend fun launch(
            game: GameRow,
            preferredDisc: Int,
            launchMemberEntryKey: EntryKey?,
        ): LaunchResult = result
    }

    /**
     * Holds [LibraryViewModel.launchCompletionGuardHook]'s call: [entered] completes the moment a completion
     * arm's guard passes, then the calling thread blocks on an internal latch until [release] — held under the
     * arm's own monitor once R6-F-07's fix lands, so another thread's [LibraryViewModel.cancelPendingLaunch]
     * genuinely contends for it instead of racing it.
     */
    private class LaunchCompletionGate {
        private val releaseLatch = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()

        fun hook(): () -> Unit = {
            entered.complete(Unit)
            check(releaseLatch.await(LibraryTestAwaitMs, TimeUnit.MILLISECONDS)) {
                "the test never released the held launch-completion arm"
            }
        }

        fun release() = releaseLatch.countDown()
    }
}
