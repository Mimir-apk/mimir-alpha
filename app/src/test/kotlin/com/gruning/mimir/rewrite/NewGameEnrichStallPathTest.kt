package com.gruning.mimir.rewrite

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F7 (Thor re-check on combined16, 2026-10-08): the identification-stall path raised "Your library is identified. 8 games are
 * ready for artwork. Fetch it now?" over Library Search while a query was being typed. This drives the real
 * [NewGameEnrichPromptState] through that path: a scan records eight new games, Search is open, the wait for identification
 * gives up (`MimirArtworkFetch: offering artwork before identification finished: identification stalled`) and calls
 * [NewGameEnrichPromptState.offerAfterHash]. The card must not appear until Search has closed and the surface has been quiet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NewGameEnrichStallPathTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the stall path's offer stays queued while Search is open and lands only after the surface has been quiet`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        NewGameEnrichPromptState.restore(context)
        NewGameEnrichPromptState.recordScan(
            context,
            SourceReconcileResult(
                sourcesChecked = 1,
                healthySources = 1,
                removedSources = 0,
                filesSeen = 8,
                recognized = 8,
                groups = 8,
                skipped = 0,
                addedGameIds = (1..8).map { "stall-game-$it" }.toSet(),
            ),
        )
        val pending = requireNotNull(NewGameEnrichPromptState.pending.value) { "the scan recorded eight new games" }
        val samples = MutableSharedFlow<NewGameEnrichCoverSample>()
        val watcher = launch { watchNewGameEnrichCover(samples, idleMillis = 1_000L) }
        runCurrent()
        try {
            // The app opens on a resting shelf; nothing has been quiet long enough yet, so a fresh offer is already held.
            NewGameEnrichPromptState.offerAfterHash(context, pending)
            assertNull("held before the first look", NewGameEnrichPromptState.current.value)

            // The player opens Library Search and types. The wait gives up and the stall path asks.
            samples.emit(NewGameEnrichCoverSample(covered = true, focused = FocusKey("library/search/query")))
            runCurrent()
            NewGameEnrichPromptState.offerAfterHash(context, pending)
            assertNull("no card over a query being typed", NewGameEnrichPromptState.current.value)
            advanceTimeBy(60_000L)
            runCurrent()
            assertNull("and no card however long they type", NewGameEnrichPromptState.current.value)

            // They back out to the shelf. They are still moving, so the card is not for them yet.
            samples.emit(NewGameEnrichCoverSample(covered = false, focused = FocusKey("library/shelf/x")))
            advanceTimeBy(900L)
            runCurrent()
            assertNull("still within the quiet beat", NewGameEnrichPromptState.current.value)

            advanceTimeBy(200L)
            runCurrent()
            assertEquals("the same unanswered question comes back, once", pending, NewGameEnrichPromptState.current.value)
        } finally {
            watcher.cancel()
            NewGameEnrichPromptState.dismiss(context)
            NewGameEnrichPromptState.holdWhile(covered = true, quiet = false)
        }
    }

    /**
     * Quiet-beat offer gating: the quiet beat is a second fact, separate from the cover. An offer that arrives while the player is scrolling the
     * shelf is uncovered but not quiet, and used to show at once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an offer that arrives while the player is scrolling the shelf waits until a quiet beat after the last move`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        NewGameEnrichPromptState.restore(context)
        NewGameEnrichPromptState.recordScan(
            context,
            SourceReconcileResult(
                sourcesChecked = 1,
                healthySources = 1,
                removedSources = 0,
                filesSeen = 3,
                recognized = 3,
                groups = 3,
                skipped = 0,
                addedGameIds = (1..3).map { "scroll-game-$it" }.toSet(),
            ),
        )
        val pending = requireNotNull(NewGameEnrichPromptState.pending.value) { "the scan recorded three new games" }
        val samples = MutableSharedFlow<NewGameEnrichCoverSample>()
        val watcher = launch { watchNewGameEnrichCover(samples) }
        runCurrent()
        try {
            // A resting, quiet shelf: the hold is open.
            samples.emit(NewGameEnrichCoverSample(covered = false, focused = FocusKey("library/shelf/a")))
            advanceTimeBy(NewGameEnrichIdleMillis + 1L)
            runCurrent()

            // The player scrolls: each move is uncovered but not quiet. The identification wait gives up in the middle of it.
            samples.emit(NewGameEnrichCoverSample(covered = false, focused = FocusKey("library/shelf/b")))
            advanceTimeBy(1_000L)
            NewGameEnrichPromptState.offerAfterHash(context, pending)
            assertNull("nothing covers the shelf, but the player is still moving", NewGameEnrichPromptState.current.value)

            samples.emit(NewGameEnrichCoverSample(covered = false, focused = FocusKey("library/shelf/c")))
            advanceTimeBy(NewGameEnrichIdleMillis - 1L)
            runCurrent()
            assertNull("1.5 s after the FIRST move is not 1.5 s after the last", NewGameEnrichPromptState.current.value)

            advanceTimeBy(2L)
            runCurrent()
            assertEquals("1.5 s after the last move the question lands", pending, NewGameEnrichPromptState.current.value)

            // P3-2: and for 300 ms after it lands, a press of A or B does not answer it.
            val landed = SystemClock.elapsedRealtime()
            assertEquals(false, NewGameEnrichPromptState.cardInputArmed(landed + NewGameEnrichInputGraceMillis - 50L))
            assertEquals(true, NewGameEnrichPromptState.cardInputArmed(landed + NewGameEnrichInputGraceMillis))
        } finally {
            watcher.cancel()
            NewGameEnrichPromptState.dismiss(context)
            NewGameEnrichPromptState.holdWhile(covered = true, quiet = false)
        }
    }
}
