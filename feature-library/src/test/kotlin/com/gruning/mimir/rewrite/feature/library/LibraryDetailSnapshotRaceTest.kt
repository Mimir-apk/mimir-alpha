package com.gruning.mimir.rewrite.feature.library

import androidx.lifecycle.viewModelScope
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A race found by source inspection; it has not been observed on a device.
 *
 * The two detail snapshots, [LibraryViewModel.detailGame] and [LibraryViewModel.openGameDetail], are written from two
 * threads. The main thread writes them when he moves focus, opens or closes a card, or launches. The games reconcile
 * and the Open-With override writes run on Dispatchers.Default. They used to read a snapshot, derive a new one and
 * write it back as three separate steps. A main-thread write that landed between that read and that write was
 * replaced by a value derived from the snapshot it had replaced.
 *
 * Each test lands that write inside the window on purpose. [InterleavedSnapshot] holds the background reader right
 * after its read until the test thread has written. It also counts background commits, so the outcome is read only
 * after the held step has committed. No sleeps and no clock retries; every wait is bounded and names what it waited for.
 */
class LibraryDetailSnapshotRaceTest {

    @Test
    fun `a focus move that lands while the games reconcile holds the old game is not overwritten`() = runBlocking {
        val repository = FakeLibraryRepository()
        val (left, movedTo) = repository.observeGames(PlatformId.PS1).first()
        val rig = Rig(repository)
        rig.openPs1AndSettle()
        rig.viewModel.focusMetadata(left)

        rig.detail.holdNextBackgroundRead { it?.gameId == left.gameId }
        val commits = rig.detail.backgroundCommits()
        // A games emission for the reconcile to process: the row he is about to move to gets a new title.
        repository.setTitleOverride(movedTo.gameId, RENAMED)
        awaitWithin("the reconcile reading ${left.title}") { rig.detail.held.await() }

        rig.viewModel.focusMetadata(movedTo)
        rig.detail.release()
        rig.detail.awaitBackgroundCommits(commits + 1, "the held reconcile committing")

        val snapshot = rig.viewModel.detailGame.value
        assertEquals(
            "he moved focus while the reconcile held ${left.title}: the metadata must stay on the game he moved to, " +
                "not snap back to the one he left",
            movedTo.gameId,
            snapshot?.gameId,
        )
        assertEquals("and it is still reconciled against that emission", RENAMED, snapshot?.title)
    }

    @Test
    fun `a card closed while the games reconcile holds it open stays closed`() = runBlocking {
        val repository = FakeLibraryRepository()
        val card = repository.observeGames(PlatformId.PS1).first().first()
        val rig = Rig(repository)
        rig.openPs1AndSettle()
        rig.viewModel.openGameDetail(card)

        rig.openDetail.holdNextBackgroundRead { it?.gameId == card.gameId }
        val commits = rig.openDetail.backgroundCommits()
        // A games emission for the reconcile to process: the open card's own row gets a new title.
        repository.setTitleOverride(card.gameId, RENAMED)
        awaitWithin("the reconcile reading the open card") { rig.openDetail.held.await() }

        rig.viewModel.closeGameDetail()
        rig.openDetail.release()
        rig.openDetail.awaitBackgroundCommits(commits + 1, "the held reconcile committing")

        assertNull(
            "he closed the card while the reconcile held it open: it must stay closed, not come back renamed",
            rig.viewModel.openGameDetail.value,
        )
    }

    @Test
    fun `an override chosen for one game is not stamped on the game he moved to`() = runBlocking {
        val repository = FakeLibraryRepository()
        val (chosen, movedTo) = repository.observeGames(PlatformId.PS1).first()
        assertOverrideWriteLeavesTheMovedFocusAlone(
            repository = repository,
            focused = chosen,
            movedTo = movedTo,
            write = { viewModel -> viewModel.chooseEmulator(chosen, SELECTABLE) },
        )
    }

    @Test
    fun `an override reset for one game is not stamped on the game he moved to`() = runBlocking {
        val repository = FakeLibraryRepository()
        val (reset, movedTo) = repository.observeGames(PlatformId.PS1).first()
        assertOverrideWriteLeavesTheMovedFocusAlone(
            repository = repository,
            focused = reset.copy(hasEmulatorOverride = true),
            // The game he moves to carries its own override, so a reset stamped on it would show.
            movedTo = movedTo.copy(hasEmulatorOverride = true),
            write = { viewModel -> viewModel.resetEmulator(reset) },
        )
    }

    private suspend fun assertOverrideWriteLeavesTheMovedFocusAlone(
        repository: FakeLibraryRepository,
        focused: GameRow,
        movedTo: GameRow,
        write: (LibraryViewModel) -> Unit,
    ) {
        val rig = Rig(repository, launchPort = OverridePort())
        rig.openPs1AndSettle()
        rig.viewModel.focusMetadata(focused)

        rig.detail.holdNextBackgroundRead { it?.gameId == focused.gameId }
        val commits = rig.detail.backgroundCommits()
        write(rig.viewModel)
        awaitWithin("the override write reading ${focused.title}") { rig.detail.held.await() }

        rig.viewModel.focusMetadata(movedTo)
        rig.detail.release()
        rig.detail.awaitBackgroundCommits(commits + 1, "the held override write committing")

        assertEquals(
            "he moved focus while the override write held ${focused.title}: the game he moved to keeps its own row",
            movedTo,
            rig.viewModel.detailGame.value,
        )
    }

    /**
     * R6-5E-03, race 1. The reconcile pass captured [LibraryViewModel.games]'s rows and `loadedTruth` before
     * entering its `update {}` calls (round 5e). A pass overtaken by a platform switch re-runs its CAS retry
     * against the NEWER card, but with those STALE captured inputs, and nulls it.
     */
    @Test
    fun `a card opened after a platform switch is not closed by a pass the switch overtook`() = runBlocking {
        val repository = FakeLibraryRepository()
        // The repository's own rows: the view model's `platforms` never emits under plain JUnit.
        val platforms = repository.observePlatforms().first()
        val card = repository.observeGames(PlatformId.PS1).first().first()
        val rig = Rig(repository)
        rig.openPs1AndSettle()
        rig.viewModel.openGameDetail(card)

        // Pass P captures PS1's rows and loadedTruth = true, then is held inside its open-card update, as a Default
        // thread preempted for longer than a frame would be.
        rig.openDetail.holdNextBackgroundRead { it?.gameId == card.gameId }
        val commits = rig.openDetail.backgroundCommits()
        repository.setTitleOverride(card.gameId, RENAMED)
        awaitWithin("the reconcile reading the open card") { rig.openDetail.held.await() }

        // Meanwhile, on the main thread: he closes the card, switches platform, the list arrives, he opens a card
        // there.
        rig.viewModel.closeGameDetail()
        assertTrue("fixture: the switch is accepted", rig.viewModel.switchOpenPlatform(1, platforms))
        val destination = requireNotNull(rig.viewModel.openPlatform.value)
        val opened = awaitWithin("the ${destination.value} gamelist loading") {
            rig.viewModel.games.first { rows -> rows.isNotEmpty() && rows.all { it.platformId == destination } }
        }.first()
        rig.viewModel.openGameDetail(opened)

        // P resumes: its first CAS loses to the new card and the lambda re-runs on it. Then the pass over the
        // destination's rows. Each pass writes the open-card snapshot last, so two more commits mark both done.
        rig.openDetail.release()
        rig.openDetail.awaitBackgroundCommits(
            commits + 2,
            "the overtaken pass and the pass over the ${destination.value} rows committing",
        )

        assertEquals(
            "he opened ${opened.title} after switching to ${destination.value}: a pass over the platform he left " +
                "must not close it (it is nulled when the pass derives from the rows it captured before the switch)",
            opened.gameId,
            rig.viewModel.openGameDetail.value?.gameId,
        )
    }

    /**
     * R6-5E-03, race 2. `loadOpenWith`'s two guards (the request token, the focused game) were read as two separate
     * steps, then the write. Neither guard was part of the write, so a dismissal landing between the two reads
     * bumps the token too late to stop it: the first guard had already passed.
     */
    @Test
    fun `a picker he dismissed while its options were loading does not open afterwards`() = runBlocking {
        val repository = FakeLibraryRepository()
        val game = repository.observeGames(PlatformId.PS1).first().first()
        val rig = Rig(repository, launchPort = OverridePort())
        rig.openPs1AndSettle()
        rig.viewModel.focusMetadata(game)

        // The load's guard reads the metadata snapshot on Dispatchers.Default, after the port has answered: hold it
        // there.
        rig.detail.holdNextBackgroundRead { it?.gameId == game.gameId }
        val scopeJob = rig.viewModel.viewModelScope.coroutineContext.job
        val before = scopeJob.children.toSet()
        assertTrue("fixture: a port is wired, so the load starts", rig.viewModel.loadOpenWith(game))
        val load = (scopeJob.children.toSet() - before).single()
        awaitWithin("the load reading ${game.title}") { rig.detail.held.await() }

        // B while the picker is still loading: the request token moves and the picker is closed.
        rig.viewModel.closeOpenWith()
        rig.detail.release()
        awaitWithin("the load finishing") { load.join() }

        assertNull(
            "he dismissed the picker while its options were loading: the load must not open it after the dismissal",
            rig.viewModel.openWith.value,
        )
    }

    /** A view model over two [InterleavedSnapshot]s, so a test can hold a background step inside its window. */
    private class Rig(repository: FakeLibraryRepository, launchPort: LibraryLaunchPort? = null) {
        private val testThread = Thread.currentThread()
        val detail = InterleavedSnapshot(testThread)
        val openDetail = InterleavedSnapshot(testThread)
        val viewModel = LibraryViewModel(repository, detail, openDetail, launchPort)

        /**
         * Opens PS1 and returns once the reconcile is idle. Each reconcile pass writes the open-card snapshot last, so
         * each background commit there marks a finished pass: first the pass over the initial empty list, then the
         * pass over the PS1 rows. Nothing else is in flight until the test makes the next emission.
         */
        suspend fun openPs1AndSettle() {
            openDetail.awaitBackgroundCommits(1, "the reconcile's first pass")
            viewModel.openPlatform(PlatformId.PS1)
            openDetail.awaitBackgroundCommits(2, "the reconcile's pass over the PS1 rows")
        }
    }

    /**
     * A detail snapshot that can hold a background reader right after its read.
     *
     * [holdNextBackgroundRead] arms a one-shot hold. The next read on a thread other than the test's that sees a
     * matching value completes [held], then blocks until [release]; meanwhile the test thread writes. A set, or a
     * compare-and-set that took, from a background thread counts as a commit. Everything else is the real flow.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class InterleavedSnapshot(
        private val testThread: Thread,
        private val real: MutableStateFlow<GameRow?> = MutableStateFlow(null),
    ) : MutableStateFlow<GameRow?> by real {
        private val hold = AtomicReference<((GameRow?) -> Boolean)?>(null)
        private val released = CountDownLatch(1)
        private val commits = MutableStateFlow(0)
        val held = CompletableDeferred<GameRow?>()

        fun holdNextBackgroundRead(matches: (GameRow?) -> Boolean) {
            check(hold.compareAndSet(null, matches)) { "a hold is already armed" }
        }

        fun release() = released.countDown()

        fun backgroundCommits(): Int = commits.value

        suspend fun awaitBackgroundCommits(count: Int, what: String) {
            awaitWithin(what) { commits.first { it >= count } }
        }

        override var value: GameRow?
            get() {
                val seen = real.value
                val matches = hold.get()
                if (matches != null &&
                    Thread.currentThread() !== testThread &&
                    matches(seen) &&
                    hold.compareAndSet(matches, null)
                ) {
                    held.complete(seen)
                    check(released.await(LibraryTestAwaitMs, TimeUnit.MILLISECONDS)) {
                        "the test never released the held background reader"
                    }
                }
                return seen
            }
            set(next) {
                real.value = next
                countIfBackground()
            }

        override fun compareAndSet(expect: GameRow?, update: GameRow?): Boolean =
            real.compareAndSet(expect, update).also { took -> if (took) countIfBackground() }

        private fun countIfBackground() {
            if (Thread.currentThread() !== testThread) commits.update { it + 1 }
        }
    }

    private class OverridePort : LibraryLaunchPort {
        override suspend fun openWithOptions(game: GameRow): OpenWithOptions =
            OpenWithOptions(hasOverride = false, candidates = emptyList())

        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) = Unit
        override suspend fun clearEmulatorOverride(game: GameRow) = Unit

        override suspend fun launch(
            game: GameRow,
            preferredDisc: Int,
            launchMemberEntryKey: EntryKey?,
        ): LaunchResult = LaunchResult(LaunchReadiness.Unsupported(game.platformId))
    }

    private companion object {
        const val RENAMED = "Renamed while he looked"

        val SELECTABLE = OpenWithCandidate(
            emulatorId = EmulatorId("duckstation"),
            label = "DuckStation",
            packageName = "com.github.stenzek.duckstation",
            installed = true,
            isCurrentOverride = false,
            isAutoPick = true,
        )
    }
}
