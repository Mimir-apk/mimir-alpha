package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Achievement earned" watch (during play and on return): a running game's own progress is read every two minutes through the client (so through the process
 * pacer), each unlock earned after the session began and not yet toasted is toasted once, several found together are one toast, the toasted ids survive a restart, the session's
 * return reads once more, and nothing is read without a key, without a running session, after the session ends or inside a Retry-After. Virtual time drives the real watch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsSessionPollTest {

    private val minute = 60_000L
    private val startMs = LocalDateTime.of(2026, 10, 8, 12, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val session = RaPlayingSession(operation = "op-a", gameId = "local-chrono", startedAtEpochMillis = startMs)
    private val sessions = MutableStateFlow<RaPlayingSession?>(null)

    private var signedIn: String? = "poll-player"
    private var gate = true
    private var held = false
    private var raId: Int? = 1_234
    private var reads = 0
    private var answers = ArrayDeque<RaProgressOutcome>()
    private val posted = ArrayList<AchievementToast>()
    private val logs = ArrayList<String>()
    private val stored = HashMap<String, List<Int>>()
    private var saves = 0
    private var failNext = 0
    private var slowRead = false
    private var epochNow = 0L
    private val progressed = ArrayList<Pair<Int, Long>>()

    private fun achievement(id: Int, title: String, points: Int, at: String?) = RetroAchievementsAchievement(
        id = id,
        title = title,
        description = "d$id",
        points = points,
        unlocked = at != null,
        unlockedHardcore = false,
        badgeName = "%05d".format(id),
        earnedAt = at,
    )

    /** Before the session (11:59), exactly at its start (12:00), locked, and then whatever a test adds after it. */
    private val older = achievement(1, "Before", 5, "2026-10-08 11:59:00")
    private val atStart = achievement(4, "At the start", 5, "2026-10-08 12:00:00")
    private val locked = achievement(3, "Locked", 5, null)
    private var achievements: List<RetroAchievementsAchievement> = listOf(older, atStart, locked)

    private fun progress() = RetroAchievementsProgress(gameId = 1_234, title = "Chrono Cross", achievements = achievements)

    private fun TestScope.poller(): AchievementSessionPoller = AchievementSessionPoller(
        scope = backgroundScope,
        sessions = sessions,
        gateOpen = { gate },
        account = { signedIn },
        raGameIdFor = { id -> raId.takeIf { id == "local-chrono" } },
        held = { held },
        read = {
            reads += 1
            if (slowRead) delay(3 * minute)
            if (failNext > 0) {
                failNext -= 1
                throw IllegalStateException("the read blew up")
            }
            answers.removeFirstOrNull() ?: RaProgressOutcome.Read(progress())
        },
        loadToasted = { name -> stored[name].orEmpty() },
        saveToasted = { name, ids ->
            saves += 1
            stored[name] = ids
        },
        post = {
            posted += it
            true
        },
        epoch = { epochNow },
        onProgress = { progress, began -> progressed += progress.gameId to began },
        pause = { delay(it) },
        log = { logs += it },
        workContext = EmptyCoroutineContext,
    ).also { it.start() }

    private suspend fun TestScope.tick(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    // ── cadence and stop ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a running game is read every two minutes and not before, and the reads stop when its session ends`() = runTest {
        poller()
        runCurrent()
        sessions.value = session
        runCurrent()

        tick(2 * minute - 1)
        assertEquals("nothing a millisecond before the first two minutes", 0, reads)
        tick(1)
        assertEquals("the first read at two minutes", 1, reads)
        tick(2 * minute - 1)
        assertEquals(1, reads)
        tick(1)
        assertEquals("then every two minutes", 2, reads)
        assertTrue("each read leaves its line, with no key, name or URL: $logs", logs.contains("session-poll game=1234 outcome=read"))

        sessions.value = null
        runCurrent()
        tick(30 * minute)
        assertEquals("nothing is read once the session has ended", 2, reads)
    }

    @Test
    fun `no read is made without a connected key, with the account gone, or with no running session`() = runTest {
        poller()
        runCurrent()

        // No session at all: the loop does not exist.
        tick(10 * minute)
        assertEquals(0, reads)

        // A session, but RetroAchievements is not connected, opted in or accepted.
        gate = false
        sessions.value = session
        runCurrent()
        tick(10 * minute)
        assertEquals("no key: no request", 0, reads)
        assertTrue(posted.isEmpty())

        // The gate opens, but there is no account name to keep the toasted ids under.
        gate = true
        signedIn = null
        tick(4 * minute)
        assertEquals(0, reads)

        // Connected: the next two-minute mark reads.
        signedIn = "poll-player"
        tick(2 * minute)
        assertTrue("it reads again once connected", reads >= 1)
    }

    @Test
    fun `a game not yet resolved to RetroAchievements is not read`() = runTest {
        poller()
        raId = null
        sessions.value = session
        runCurrent()
        tick(6 * minute)
        assertEquals(0, reads)
        assertTrue("and the line says so: $logs", logs.any { it == "session-poll game=unresolved outcome=unresolved" })
        raId = 1_234
        tick(2 * minute)
        assertEquals("it is read once it is resolved", 1, reads)
    }

    @Test
    fun `nothing is asked inside a Retry-After and the next mark after it reads again`() = runTest {
        poller()
        held = true
        sessions.value = session
        runCurrent()
        tick(6 * minute)
        assertEquals("no request while the host asked to slow down", 0, reads)
        assertTrue("held: $logs", logs.contains("session-poll game=1234 outcome=held"))
        held = false
        tick(2 * minute)
        assertEquals("and a read when it has passed", 1, reads)
    }

    // ── what is toasted ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `only an unlock earned after the session began is toasted, once, however often the game is read`() = runTest {
        poller()
        achievements = listOf(older, atStart, locked, achievement(2, "First Blood", 10, "2026-10-08 12:05:00"))
        sessions.value = session
        runCurrent()

        tick(2 * minute)
        assertEquals("the unlock at 12:05 only; one before the start and one exactly AT it are not this session's", 1, posted.size)
        val toast = posted.single()
        assertEquals("First Blood", toast.title)
        assertEquals(1, toast.count)
        assertEquals(10, toast.points)
        assertEquals("Chrono Cross", toast.gameTitle)
        assertEquals("the badge art is the earned one's", "/Badge/00002.png", toast.badgePath)
        assertEquals(1_234, toast.raGameId)
        assertEquals("Achievement earned", achievementToastHeadline(toast))
        assertEquals("10 points", achievementToastPoints(toast))
        assertTrue("the toast line: $logs", logs.contains("toast new=1 game=1234"))

        tick(2 * minute)
        tick(2 * minute)
        assertEquals("the game was read again and again, and the same unlock was not", 3, reads)
        assertEquals(1, posted.size)

        achievements = achievements + achievement(5, "Second Wind", 25, "2026-10-08 12:08:00")
        tick(2 * minute)
        assertEquals("a later unlock is toasted by itself", 2, posted.size)
        assertEquals("Second Wind", posted.last().title)
        assertEquals(25, posted.last().points)
    }

    @Test
    fun `several unlocks found by one read are ONE toast with the total points and the count in the headline`() = runTest {
        poller()
        achievements = listOf(
            older,
            achievement(2, "First Blood", 10, "2026-10-08 12:05:00"),
            achievement(5, "Second Wind", 15, "2026-10-08 12:07:00"),
            achievement(6, "Warm Up", 5, "2026-10-08 12:03:00"),
        )
        sessions.value = session
        runCurrent()
        tick(2 * minute)

        val toast = posted.single()
        assertEquals(3, toast.count)
        assertEquals("the total", 30, toast.points)
        assertEquals("the newest unlock leads", "Second Wind", toast.title)
        assertEquals("3 achievements earned · Chrono Cross", achievementToastHeadline(toast))
        assertEquals("30 points", achievementToastPoints(toast))
        assertTrue(achievementToastDescription(toast).startsWith("3 achievements earned · Chrono Cross"))
        assertTrue("one line for the lot: $logs", logs.contains("toast new=3 game=1234"))
    }

    // ── a restart ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the toasted ids are saved under the account, so the next process has them`() = runTest {
        poller()
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:05:00"))
        sessions.value = session
        runCurrent()
        tick(2 * minute)
        assertEquals(listOf(2), stored["poll-player"])
        assertEquals(1, saves)
        tick(2 * minute)
        assertEquals("a read that finds nothing new writes nothing", 1, saves)
    }

    @Test
    fun `a cold start with the ids already saved toasts nothing for the same unlock`() = runTest {
        stored["poll-player"] = listOf(2)
        poller()
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:05:00"))
        sessions.value = session
        runCurrent()
        tick(2 * minute)
        assertEquals("the game was read", 1, reads)
        assertTrue("and the unlock the last process toasted is not toasted again: $posted", posted.isEmpty())
        assertEquals(0, saves)
        achievements = achievements + achievement(7, "Fresh", 5, "2026-10-08 12:09:00")
        tick(2 * minute)
        assertEquals("Fresh", posted.single().title)
        assertEquals(listOf(2, 7), stored["poll-player"])
    }

    @Test
    fun `ids saved under one account are not another account's`() = runTest {
        stored["someone-else"] = listOf(2)
        poller()
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:05:00"))
        sessions.value = session
        runCurrent()
        tick(2 * minute)
        assertEquals("this account never toasted it", 1, posted.size)
    }

    // ── on return ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `on return one more read toasts what the polls missed, and the three signals of one return make one read`() = runTest {
        val poller = poller()
        sessions.value = session
        runCurrent()
        tick(minute) // inside the first two minutes: no poll yet
        assertEquals(0, reads)
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:00:40"))

        sessions.value = null // the session is over
        runCurrent()
        val finished = poller.sessionToRead()
        poller.onReturned(finished)
        poller.onReturned(finished)
        poller.onReturned(poller.sessionToRead())
        runCurrent()
        assertEquals("one read for the return", 1, reads)
        assertEquals("First Blood", posted.single().title)

        tick(10 * minute)
        poller.onReturned(finished)
        runCurrent()
        assertEquals("a return already read is not read again, and there is no poll", 1, reads)
    }

    @Test
    fun `a return that arrives while the game still counts as running reads it once too`() = runTest {
        val poller = poller()
        sessions.value = session
        runCurrent()
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:00:40"))
        poller.onReturned(poller.sessionToRead())
        runCurrent()
        assertEquals(1, reads)
        assertEquals(1, posted.size)
        sessions.value = null
        runCurrent()
        poller.onReturned(poller.sessionToRead())
        runCurrent()
        assertEquals("the session's end after its return adds no read", 1, reads)
    }

    @Test
    fun `a return with no session behind it reads nothing`() = runTest {
        val poller = poller()
        runCurrent()
        assertNull("no session, nothing to read on a return", poller.sessionToRead())
        poller.onReturned(poller.sessionToRead())
        runCurrent()
        assertEquals(0, reads)
        assertNull(posted.firstOrNull())
    }

    @Test
    fun `a return read that got no answer is tried again a minute later, twice at most`() = runTest {
        val first = poller()
        sessions.value = session
        runCurrent()
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:00:40"))
        sessions.value = null
        runCurrent()
        answers.addLast(RaProgressOutcome.Unavailable)
        first.onReturned(first.sessionToRead())
        runCurrent()
        assertEquals("the first try got nothing", 1, reads)
        assertTrue(posted.isEmpty())
        tick(minute)
        assertEquals("the second try, a minute later, is answered", 2, reads)
        assertEquals("First Blood", posted.single().title)

        // And a return that never gets an answer stops after the third try.
        val again = poller()
        sessions.value = RaPlayingSession("op-b", "local-chrono", startMs)
        runCurrent()
        sessions.value = null
        runCurrent()
        reads = 0
        repeat(5) { answers.addLast(RaProgressOutcome.Unavailable) }
        again.onReturned(again.sessionToRead())
        runCurrent()
        tick(10 * minute)
        assertEquals("one try and two more, then it gives up", 3, reads)
    }

    @Test
    fun `a return read goes to the session that returned, even when another game starts while the page's own read is still running`() = runTest {
        val poller = poller()
        sessions.value = session // game A
        runCurrent()
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:00:40"))
        sessions.value = null // A ends; the return signal fires and the session is taken NOW
        runCurrent()
        val finished = poller.sessionToRead()
        assertEquals("the session that just ended", session, finished)

        // Game B starts while the post-session read of the Activity page is still running.
        val other = RaPlayingSession(operation = "op-b", gameId = "local-other", startedAtEpochMillis = startMs + 10 * minute)
        sessions.value = other
        runCurrent()
        assertEquals("the poller now names B", other, poller.sessionToRead())

        // That read ends and the follower runs with A's session, not whatever is current by now.
        poller.onReturned(finished)
        runCurrent()
        assertEquals("A's game was read (B resolves to nothing here, so a read of B would be no read at all)", 1, reads)
        assertEquals("and A's unlock is the toast", "First Blood", posted.single().title)
        assertEquals(1_234, posted.single().raGameId)
    }

    @Test
    fun `the session to read on a return is the one that just ended, else the one still running, else none`() = runTest {
        val poller = poller()
        runCurrent()
        assertNull(poller.sessionToRead())
        sessions.value = session
        runCurrent()
        assertEquals("running", session, poller.sessionToRead())
        sessions.value = null
        runCurrent()
        assertEquals("just ended", session, poller.sessionToRead())
        val next = RaPlayingSession(operation = "op-b", gameId = "local-chrono", startedAtEpochMillis = startMs + 30 * minute)
        sessions.value = next
        runCurrent()
        assertEquals("a new game was started: it is the running one", next, poller.sessionToRead())
    }

    @Test
    fun `a read that blows up is a missed poll, logged and tried again at the next mark, and the watch goes on`() = runTest {
        poller()
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:05:00"))
        failNext = 1
        sessions.value = session
        runCurrent()
        tick(2 * minute)
        assertEquals("the first read was made and failed", 1, reads)
        assertTrue("and the line says so, with nothing else in it: $logs", logs.contains("session-poll game=1234 outcome=failed"))
        assertTrue(posted.isEmpty())
        tick(2 * minute)
        assertEquals("the watch was not ended by it: the next mark read again", 2, reads)
        assertEquals("First Blood", posted.single().title)
    }

    @Test
    fun `a poll cut short by the session ending is a cancellation, not a failure, and toasts nothing`() = runTest {
        poller()
        achievements = listOf(older, achievement(2, "First Blood", 10, "2026-10-08 12:05:00"))
        slowRead = true
        sessions.value = session
        runCurrent()
        tick(2 * minute) // the read has begun and is waiting
        assertEquals(1, reads)
        sessions.value = null
        runCurrent()
        tick(10 * minute)
        assertFalse("a cancelled read is not logged as a failure: $logs", logs.any { it.contains("outcome=failed") })
        assertTrue("and posts nothing", posted.isEmpty())
        assertEquals("nor does the watch read again", 1, reads)
    }

    @Test
    fun `each answered read hands its progress and the epoch it began under to the owner of the card details`() = runTest {
        poller()
        sessions.value = session
        runCurrent()
        epochNow = 5L
        tick(2 * minute)
        epochNow = 6L
        tick(2 * minute)
        assertEquals("the game and the epoch the read began under, each time", listOf(1_234 to 5L, 1_234 to 6L), progressed)
        answers.addLast(RaProgressOutcome.Unavailable)
        tick(2 * minute)
        assertEquals("an unanswered read hands over nothing", 2, progressed.size)
    }

    @Test
    fun `a rejected key is not read again as a toast and a refused answer toasts nothing`() = runTest {
        poller()
        sessions.value = session
        runCurrent()
        answers.addLast(RaProgressOutcome.CredentialsRejected)
        tick(2 * minute)
        assertEquals(1, reads)
        assertTrue(posted.isEmpty())
        assertTrue("rejected: $logs", logs.contains("session-poll game=1234 outcome=rejected"))
        assertFalse(logs.any { it.contains("toast") })
    }
}
