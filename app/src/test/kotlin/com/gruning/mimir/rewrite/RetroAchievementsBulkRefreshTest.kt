package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The reads that come AFTER a game's play: a short session is never lost to the five-minute window (one trailing read at the window's end), a read that went through
 * is followed by ONE more that catches RetroAchievements' lagging counts up, a read that brought nothing is retried after a minute, and a game played outside Mimir is
 * heard when Mimir comes back. A fake clock that IS the test scheduler's virtual time drives the real loader and the real scheduler, and fake sources count the reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsBulkRefreshTest {

    private val minute = 60_000L
    private val start = 50_000_000L
    private val book = RetroAchievementsLaneBook()
    private var signedIn: String? = "refresh-player"
    private var gate = true
    private var gameCalls = 0
    private var inFlight = 0
    private var maxInFlight = 0
    private var holdReads: CompletableDeferred<Unit>? = null
    private val games = listOf(RetroAchievementsUserGame(1, "Super Metroid", "Super Nintendo Entertainment System", earned = 12, total = 40))
    private var gamesAnswer: () -> RaUserGamesOutcome = { RaUserGamesOutcome.Read(games, games.size) }

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    private fun TestScope.loader() = ActivityBulkLoader(
        book = book,
        account = { signedIn },
        loadGames = {
            gameCalls += 1
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                holdReads?.await()
                gamesAnswer()
            } finally {
                inFlight -= 1
            }
        },
        loadPoints = { RaPointsOutcome.Read(100) },
        library = { listOf(LocalGameKey("local-metroid", "snes", "Super Metroid (USA)")) },
        platformIdFor = { name -> if (name == "Super Nintendo Entertainment System") "snes" else null },
        publish = { account, snapshots, points -> CompanionActivityAchievements.publishBulk(account, snapshots, points) },
        clock = { start + currentTime },
        workContext = EmptyCoroutineContext,
        publishContext = EmptyCoroutineContext,
    )

    private fun TestScope.refresher(loader: ActivityBulkLoader) = ActivityBulkRefresher(
        scope = backgroundScope,
        loader = loader,
        clock = { start + currentTime },
        gateOpen = { gate },
        pause = { delay(it) },
    )

    // ── U2: a short session ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a return at three minutes gets one trailing read when the window ends at five`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        assertEquals(ActivityBulkResult.LOADED, loader.load()) // the cold start, at minute 0
        assertEquals(1, gameCalls)

        advanceTimeBy(3 * minute)
        refresher.onReturned()
        runCurrent()
        assertEquals("inside the window nothing is read at once", 1, gameCalls)

        advanceTimeBy(2 * minute - 1)
        runCurrent()
        assertEquals("and nothing a millisecond before the window ends", 1, gameCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("the trailing read lands exactly when the window ends", 2, gameCalls)

        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals("one trailing read, not a series", 2, gameCalls)
        assertEquals(1, maxInFlight)
    }

    @Test
    fun `the hook after a return runs once the page's own read has been made, and never before it`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(6 * minute) // past the five-minute window: the return's read goes through at once
        var callsWhenRun = -1
        var runs = 0
        refresher.onReturned(afterRead = {
            runs += 1
            callsWhenRun = gameCalls
        })
        runCurrent()
        assertEquals("the hook ran once", 1, runs)
        assertEquals("and after the return's read (the cold start was call 1)", 2, callsWhenRun)
    }

    @Test
    fun `the hook after a return also runs when the window turned the read away`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(3 * minute)
        var runs = 0
        refresher.onReturned(afterRead = { runs += 1 })
        runCurrent()
        assertEquals("a throttled return still gets its follower", 1, runs)
        assertEquals("with no read of its own", 1, gameCalls)
    }

    @Test
    fun `two returns inside one window share the one trailing read`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()

        advanceTimeBy(3 * minute)
        refresher.onReturned()
        runCurrent()
        advanceTimeBy(30_000L)
        refresher.onReturned()
        runCurrent()

        advanceTimeBy(2 * minute)
        runCurrent()
        assertEquals("cold start plus ONE trailing read for both returns", 2, gameCalls)
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(2, gameCalls)
    }

    @Test
    fun `a read that went through on a return is followed by one more ninety seconds later, past the window`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(6 * minute) // the window is long over

        refresher.onReturned()
        runCurrent()
        assertEquals("the return reads at once", 2, gameCalls)

        advanceTimeBy(90_000L - 1)
        runCurrent()
        assertEquals(2, gameCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("RA's counts lag a play: the follow-up reads again inside the new window", 3, gameCalls)

        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals("exactly one follow-up", 3, gameCalls)
        assertEquals("never two reads at once", 1, maxInFlight)
    }

    @Test
    fun `the follow-up waits behind a read in flight instead of running beside it`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(6 * minute)
        refresher.onReturned()
        runCurrent() // read 2, done
        // The follow-up's read is held open...
        val hold = CompletableDeferred<Unit>()
        holdReads = hold
        advanceTimeBy(90_000L)
        runCurrent()
        assertEquals("the follow-up has started its read", 3, gameCalls)
        // ...and a return on top of it is turned away by the window rather than starting a second read.
        refresher.onReturned()
        runCurrent()
        assertEquals(3, gameCalls)
        assertEquals(1, maxInFlight)
        hold.complete(Unit)
        runCurrent()
        assertEquals(1, maxInFlight)
    }

    @Test
    fun `an account change drops the pending read`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(3 * minute)
        refresher.onReturned()
        runCurrent()

        refresher.cancel()
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals("the trailing read was for the old account", 1, gameCalls)
    }

    @Test
    fun `a pending read does nothing once the connection is no longer allowed`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(3 * minute)
        refresher.onReturned()
        runCurrent()

        gate = false // the key was refused, or the player opted out, meanwhile
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(1, gameCalls)
    }

    // ── a read that brought nothing ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a read that brought nothing may be retried after a minute, not after five`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Unavailable }
        val loader = loader()
        assertEquals(ActivityBulkResult.UNAVAILABLE, loader.load())
        assertEquals(1, gameCalls)

        advanceTimeBy(59_999L)
        assertEquals(ActivityBulkResult.THROTTLED, loader.refreshAfterReturn())
        assertEquals(1, gameCalls)

        advanceTimeBy(1)
        gamesAnswer = { RaUserGamesOutcome.Read(games, games.size) }
        assertEquals("a minute after the failure the next return reads again", ActivityBulkResult.LOADED, loader.refreshAfterReturn())
        assertEquals(2, gameCalls)

        advanceTimeBy(4 * minute)
        assertEquals("and a read that DID bring something holds the whole window", ActivityBulkResult.THROTTLED, loader.refreshAfterReturn())
    }

    @Test
    fun `a partial read is a failure too`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Partial(games, 3) }
        val loader = loader()
        assertEquals(ActivityBulkResult.PARTIAL, loader.load())
        advanceTimeBy(60_000L)
        assertEquals(ActivityBulkResult.PARTIAL, loader.refreshAfterReturn())
        assertEquals(2, gameCalls)
    }

    @Test
    fun `a return after a failed read is retried when the short backoff ends`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Unavailable }
        val loader = loader()
        val refresher = refresher(loader)
        loader.load() // the cold start, offline

        advanceTimeBy(20_000L)
        refresher.onReturned()
        runCurrent()
        assertEquals("still inside the backoff", 1, gameCalls)

        gamesAnswer = { RaUserGamesOutcome.Read(games, games.size) }
        advanceTimeBy(40_000L)
        runCurrent()
        assertEquals("the backoff is over, but RA's counts have not had 90 s to catch up with this return", 1, gameCalls)
        advanceTimeBy(49_999L)
        runCurrent()
        assertEquals(1, gameCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("the read is due 90 s after the return, long before the five-minute window would allow one", 2, gameCalls)
        assertEquals(1, CompanionActivityAchievements.snapshots.value.size)
    }

    @Test
    fun `a trailing read is never due before the counts have caught up with the LAST return, even if the window ended sooner`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(4 * minute + 30_000L)
        refresher.onReturned()
        runCurrent()
        assertEquals("inside the window nothing is read at once", 1, gameCalls)

        advanceTimeBy(30_000L) // 5:00: the window has ended, but the play ended at 4:30
        runCurrent()
        assertEquals("the window's end alone is too early: the play ended 30 s ago", 1, gameCalls)
        advanceTimeBy(59_999L) // 5:59.999
        runCurrent()
        assertEquals(1, gameCalls)
        advanceTimeBy(1L) // 6:00, ninety seconds after the return
        runCurrent()
        assertEquals(2, gameCalls)
    }

    @Test
    fun `a later return pushes the pending read later instead of being dropped`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(3 * minute)
        refresher.onReturned() // pending at 5:00 (the window's end)
        runCurrent()
        advanceTimeBy(90_000L)
        refresher.onReturned() // 4:30: the counts of THIS play are not ready before 6:00
        runCurrent()

        advanceTimeBy(30_000L) // 5:00
        runCurrent()
        assertEquals("the read the first return would have made at 5:00 is pushed, not made", 1, gameCalls)
        advanceTimeBy(minute - 1) // 5:59.999
        runCurrent()
        assertEquals(1, gameCalls)
        advanceTimeBy(1) // 6:00
        runCurrent()
        assertEquals("one read serves both returns", 2, gameCalls)
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(2, gameCalls)
    }

    @Test
    fun `a window-turned return folds into a pending follow-up, as one read that ignores the window and is due after the later return`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(6 * minute)
        refresher.onReturned() // reads now (read 2); the follow-up is due at 7:30
        runCurrent()
        assertEquals(2, gameCalls)
        advanceTimeBy(20_000L) // 6:20
        refresher.onReturned() // the window (to 11:00) turns it away
        runCurrent()
        assertEquals(2, gameCalls)

        advanceTimeBy(89_999L) // 7:49.999
        runCurrent()
        assertEquals(2, gameCalls)
        advanceTimeBy(1L) // 7:50: 90 s after the second return
        runCurrent()
        assertEquals("a read that ignores the window, long before 11:00", 3, gameCalls)
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals("and only that one", 3, gameCalls)
        assertEquals(1, maxInFlight)
    }

    @Test
    fun `an account change cancels a read already in flight as well as the pending one`() = runTest {
        val loader = loader()
        val refresher = refresher(loader)
        loader.load()
        advanceTimeBy(6 * minute)
        refresher.onReturned()
        runCurrent() // read 2 done; the follow-up is pending at 7:30
        val hold = CompletableDeferred<Unit>()
        holdReads = hold
        advanceTimeBy(90_000L)
        runCurrent()
        assertEquals("the follow-up is reading", 3, gameCalls)
        assertEquals(1, inFlight)

        refresher.cancel()
        runCurrent()
        assertEquals("the read for the old account was cancelled", 0, inFlight)
        hold.complete(Unit)
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(3, gameCalls)
    }

    @Test
    fun `a failed read that an account switch overtook leaves the new account's window alone`() = runTest {
        val hold = CompletableDeferred<Unit>()
        holdReads = hold
        gamesAnswer = { RaUserGamesOutcome.Unavailable }
        val loader = loader()
        val running = async { loader.load() }
        runCurrent()

        signedIn = "someone-else"
        book.accountChanged()
        loader.reset()
        hold.complete(Unit)

        assertEquals(ActivityBulkResult.UNAVAILABLE, running.await())
        assertNull("the new account is not shut out by the old account's failure", loader.eligibleAtMillis())
    }

    // ── U6: a game played outside Mimir ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `coming back after two minutes or more counts as a game played elsewhere and a quick pause does not`() {
        var now = 0L
        val watch = OutsidePlayWatch({ now })
        assertFalse("never left", watch.onBack())

        watch.onLeft()
        now += 119_999L
        assertFalse("a permission dialog or the shade is shorter than two minutes", watch.onBack())

        watch.onLeft()
        now += 120_000L
        assertTrue(watch.onBack())
        assertFalse("the departure is spent", watch.onBack())

        watch.onLeft()
        now += 10_000L
        watch.onLeft() // a second pause inside the same absence changes nothing
        now += 115_000L
        assertTrue("the absence is counted from the first pause", watch.onBack())
    }
}
