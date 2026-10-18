package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.RaKeyState
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The REAL coordinator ([MimirAchievementsState]) with the "Achievement earned" watch wired in: a game being played is resolved to its RetroAchievements id from the Activity
 * page's own snapshot, read on the poll's tick (a channel the test releases), its new unlock toasted into the sink and its id written to the real ledger over a temporary folder; a
 * session that returns reads once more AFTER the page's own post-session read; and a closed gate asks nothing. Only the sources and the clock are replaced, through the seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class RetroAchievementsSessionToastWiringTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pollMs = 7_777L
    private val startMs = LocalDateTime.of(2026, 10, 8, 12, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val account = MutableStateFlow<String?>("wire-player")
    private val keyState = MutableStateFlow(RaKeyState.ACCEPTED)
    private val optedIn = MutableStateFlow(true)
    private val sessionKeys = MutableStateFlow<String?>(null)
    private val playing = MutableStateFlow<RaPlayingSession?>(null)
    private val ticks = Channel<Unit>(Channel.UNLIMITED)
    private val posted = ArrayList<AchievementToast>()
    private val order = ArrayList<String>()
    private var progressCalls = 0
    private var gameCalls = 0
    private var cardFetches = 0
    private var nowMs = 10_000_000L

    /** While set, the page's own bulk read waits on it: a post-session read that is still running. */
    private var bulkGate: Channel<Unit>? = null
    private val states = mutableListOf<MimirAchievementsState>()

    private fun unlock(id: Int, title: String, points: Int, at: String?) = RetroAchievementsAchievement(
        id = id,
        title = title,
        description = "d",
        points = points,
        unlocked = at != null,
        unlockedHardcore = false,
        badgeName = "%05d".format(id),
        earnedAt = at,
    )

    private var achievements = listOf(unlock(1, "Before", 5, "2026-10-08 11:00:00"), unlock(2, "Locked", 5, null))

    private fun progress() = RetroAchievementsProgress(gameId = 1_234, title = "Chrono Trigger", achievements = achievements)

    @Before
    fun reset() {
        CompanionActivityAchievements.resetForTest()
        CompanionActivityCardDetails.resetForTest()
    }

    @After
    fun closeAll() = states.forEach(MimirAchievementsState::close)

    private fun state(): MimirAchievementsState = MimirAchievementsState(
        RuntimeEnvironment.getApplication(),
        MimirAchievementsSeams(
            account = account,
            keyState = keyState,
            optedIn = optedIn,
            sessionKeys = sessionKeys,
            scopeContext = Dispatchers.Unconfined,
            bulkGames = {
                gameCalls += 1
                order += "bulk"
                bulkGate?.receive()
                RaUserGamesOutcome.Read(listOf(RetroAchievementsUserGame(1_234, "Chrono Trigger", "Super Nintendo Entertainment System", 1, 10)), 1)
            },
            bulkPoints = { RaPointsOutcome.Read(5) },
            bulkRecent = { RaRecentOutcome.Read(emptyList()) },
            bulkLibrary = { listOf(LocalGameKey("local-chrono", "snes", "Chrono Trigger")) },
            iconClear = {},
            bulkContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
            clock = { nowMs },
            ledger = RetroAchievementsLedger(File(temp.root, "ra")),
            playingSessions = playing,
            sessionProgress = {
                progressCalls += 1
                order += "progress"
                RaProgressOutcome.Read(progress())
            },
            requestsHeld = { false },
            cardDetailFetch = { _ ->
                cardFetches += 1
                RaProgressOutcome.Read(progress())
            },
            toastSink = {
                posted += it
                true
            },
            sessionPollMillis = pollMs,
            // The poll waits for the test's tick; every other wait (the follow-up read) is a real delay that the end of the test cancels.
            bulkPause = { millis -> if (millis == pollMs) ticks.receive() else delay(millis) },
        ),
    ).also { states += it }

    private fun savedIds(): List<Int> = RetroAchievementsLedger(File(temp.root, "ra")).readToasted("wire-player")

    @Test
    fun `a game being played is read on the poll's tick, its new unlock is toasted once and saved, and the end of the session stops the reads`() {
        val state = state()
        state.startActivityBulk()
        assertTrue(
            "the page's snapshot says which RetroAchievements game the library game is: ${CompanionActivityAchievements.snapshots.value}",
            CompanionActivityAchievements.snapshots.value.any { it.gameId == "local-chrono" && it.raGameId == 1_234 },
        )

        playing.value = RaPlayingSession(operation = "op", gameId = "local-chrono", startedAtEpochMillis = startMs)
        assertEquals("nothing is read before the first tick", 0, progressCalls)

        achievements = achievements + unlock(5, "First Blood", 10, "2026-10-08 12:05:00")
        ticks.trySend(Unit)
        assertEquals(1, progressCalls)
        assertEquals("the unlock earned after the session began is toasted", listOf("First Blood"), posted.map { it.title })
        assertEquals(listOf(5), savedIds())

        ticks.trySend(Unit)
        assertEquals("a second read of the same list toasts nothing", 2, progressCalls)
        assertEquals(1, posted.size)

        playing.value = null
        ticks.trySend(Unit)
        assertEquals("a session that has ended is not read", 2, progressCalls)
    }

    @Test
    fun `a returning session reads once more after the page's own read and toasts what the polls missed`() {
        val state = state()
        state.startActivityBulk()
        playing.value = RaPlayingSession(operation = "op", gameId = "local-chrono", startedAtEpochMillis = startMs)
        sessionKeys.value = retroAchievementsSessionKey("op", MimirSessionPhase.DISPATCHED)
        achievements = achievements + unlock(5, "First Blood", 10, "2026-10-08 12:05:00")
        order.clear()

        playing.value = null
        sessionKeys.value = retroAchievementsSessionKey("op", MimirSessionPhase.RETURNING)

        assertEquals("the return read found the unlock the polls never saw", listOf("First Blood"), posted.map { it.title })
        assertEquals(1, progressCalls)
        assertTrue("it came after the page's own read: $order", order.indexOf("progress") > order.indexOf("bulk") || !order.contains("bulk"))
        assertEquals(listOf(5), savedIds())

        sessionKeys.value = retroAchievementsSessionKey("op", MimirSessionPhase.ENDED)
        sessionKeys.value = null
        assertEquals("the later signals of the same return read nothing more", 1, progressCalls)
    }

    @Test
    fun `a return read goes to the game that returned, even when another game starts while the page's own read is still running`() {
        val state = state()
        state.startActivityBulk()
        val first = RaPlayingSession(operation = "op", gameId = "local-chrono", startedAtEpochMillis = startMs)
        playing.value = first
        sessionKeys.value = retroAchievementsSessionKey("op", MimirSessionPhase.DISPATCHED)
        achievements = achievements + unlock(5, "First Blood", 10, "2026-10-08 12:05:00")
        val bulkReadsBefore = gameCalls

        // A day later the page's own post-session read is allowed, and it is slow: it waits on the gate.
        nowMs += 24L * 60L * 60L * 1000L
        val gate = Channel<Unit>(Channel.UNLIMITED)
        bulkGate = gate
        playing.value = null
        sessionKeys.value = retroAchievementsSessionKey("op", MimirSessionPhase.RETURNING)
        assertEquals("the page's own read is under way, waiting", bulkReadsBefore + 1, gameCalls)
        assertEquals("and the unlock watch has not read yet: it follows the page's read", 0, progressCalls)

        // Another game starts while that read runs (the poller's session is now B's).
        playing.value = RaPlayingSession(operation = "op-b", gameId = "local-other", startedAtEpochMillis = startMs + 10L * 60L * 1000L)

        gate.trySend(Unit)
        assertEquals("the return read went to the game that came back, not to the one started meanwhile", 1, progressCalls)
        assertEquals(listOf("First Blood"), posted.map { it.title })
        assertEquals(1_234, posted.single().raGameId)
        assertEquals(listOf(5), savedIds())
    }

    @Test
    fun `the poll's read seeds the hub card's details at once, so the card shows the unlock and the game is not fetched again`() {
        val state = state()
        state.startActivityBulk()
        playing.value = RaPlayingSession(operation = "op", gameId = "local-chrono", startedAtEpochMillis = startMs)
        assertEquals("nothing is known of the game's card before the read", null, CompanionActivityCardDetails.state.value[1_234])

        achievements = achievements + unlock(5, "First Blood", 10, "2026-10-08 12:05:00")
        ticks.trySend(Unit)
        assertEquals(1, progressCalls)

        val seeded = CompanionActivityCardDetails.state.value[1_234]
        assertTrue("the card's detail is the poll's own answer: $seeded", seeded is ActivityCardDetail.Loaded)
        val unlocked = (seeded as ActivityCardDetail.Loaded).progress.achievements.filter { it.unlocked }.map { it.title }
        assertTrue("and it has the unlock the toast announced: $unlocked", "First Blood" in unlocked)

        CompanionActivityCardDetails.request(1_234)
        assertEquals("the hub's card is not asked for again within the window", 0, cardFetches)
    }

    @Test
    fun `the bulk read does not ask the client for recent unlocks, which no page shows`() {
        val source = (File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAchievementsCoordinator.kt").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAchievementsCoordinator.kt")).readText()
        assertFalse("the bulk loader's recent-unlocks request goes to the client no more", source.contains("client.recentUnlocks("))
        assertTrue("and the seam is still how a test supplies it", source.contains("seams.bulkRecent?.invoke()"))
    }

    @Test
    fun `no read is made when RetroAchievements is not opted in or the key was refused`() {
        val state = state()
        state.startActivityBulk()
        playing.value = RaPlayingSession(operation = "op", gameId = "local-chrono", startedAtEpochMillis = startMs)
        optedIn.value = false
        ticks.trySend(Unit)
        assertEquals("opted out: no request", 0, progressCalls)
        optedIn.value = true
        keyState.value = RaKeyState.REJECTED_THIS_SESSION
        ticks.trySend(Unit)
        assertEquals("the key was refused: no request", 0, progressCalls)
        assertTrue(posted.isEmpty())
    }
}
