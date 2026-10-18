package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsRecentUnlock
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.RaKeyState
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Activity page's recent-unlocks strip data: ONE `API_GetUserRecentAchievements` read per bulk load (with the games and the points, never on a swipe or a focus
 * move), none inside the five-minute return throttle, held in the lane's one list for one account, fenced by the account epoch exactly like the bulk, and gated
 * to the connected account by the hub view. Counting fakes stand in for the client, so every call count is the fake's own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsRecentBulkTest {

    private var signedIn: String? = "recent-player"
    private var now = 1_000_000L
    private var recentCalls = 0
    private var recentAnswer: () -> RaRecentOutcome = { RaRecentOutcome.Read(unlocks(3)) }
    private val book = RetroAchievementsLaneBook()
    private val games = listOf(RetroAchievementsUserGame(1, "Super Metroid", "Super Nintendo Entertainment System", earned = 12, total = 40))

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    private fun unlocks(count: Int) = (1..count).map {
        RetroAchievementsRecentUnlock(
            achievementId = it,
            title = "Badge $it",
            description = "Do $it",
            points = it,
            badgeName = "%05d".format(it),
            gameId = 1,
            gameTitle = "Super Metroid",
            consoleName = "SNES",
            awardedAtMillis = 2_000_000L - it,
            hardcore = it % 2 == 0,
        )
    }

    private fun loader(recentGate: CompletableDeferred<Unit>? = null) = ActivityBulkLoader(
        book = book,
        account = { signedIn },
        loadGames = { RaUserGamesOutcome.Read(games, games.size) },
        loadPoints = { RaPointsOutcome.Read(10) },
        library = { emptyList() },
        platformIdFor = { null },
        publish = { account, snapshots, points -> CompanionActivityAchievements.publishBulk(account, snapshots, points) },
        clock = { now },
        workContext = EmptyCoroutineContext,
        publishContext = EmptyCoroutineContext,
        loadRecent = {
            recentCalls += 1
            recentGate?.await()
            recentAnswer()
        },
        publishRecent = { account, unlocks -> CompanionActivityAchievements.publishRecent(account, unlocks) },
    )

    private fun heldIds() = CompanionActivityAchievements.recent.value?.unlocks?.map { it.achievementId }

    @Test
    fun `a bulk load makes one recent read and holds its unlocks for the account, badge as the validated path`() = runTest {
        assertEquals(ActivityBulkResult.LOADED, loader().load())

        assertEquals(1, recentCalls)
        val held = CompanionActivityAchievements.recent.value!!
        assertEquals("recent-player", held.account)
        assertEquals(listOf(1, 2, 3), held.unlocks.map { it.achievementId })
        assertEquals("/Badge/00001.png", held.unlocks.first().badgePath)
        assertEquals("Super Metroid", held.unlocks.first().gameTitle)
        assertEquals(1, held.unlocks.first().raGameId)
    }

    @Test
    fun `a return inside the throttle reads nothing and the first one after it reads once`() = runTest {
        val loader = loader()
        loader.load()
        assertEquals(1, recentCalls)

        now += 4 * 60_000L + 59_000L
        assertEquals(ActivityBulkResult.THROTTLED, loader.refreshAfterReturn())
        assertEquals(1, recentCalls)

        now += 1_000L
        assertEquals(ActivityBulkResult.LOADED, loader.refreshAfterReturn())
        assertEquals(2, recentCalls)
    }

    @Test
    fun `the lane keeps a bounded list however many the window held`() = runTest {
        recentAnswer = { RaRecentOutcome.Read(unlocks(200)) }
        loader().load()
        assertEquals(ACTIVITY_RECENT_KEEP, CompanionActivityAchievements.recent.value!!.unlocks.size)
        assertEquals("newest first, the head of the answer", 1, CompanionActivityAchievements.recent.value!!.unlocks.first().achievementId)
    }

    @Test
    fun `a window with nothing in it is a real empty answer and replaces the older list`() = runTest {
        loader().load()
        assertEquals(listOf(1, 2, 3), heldIds())
        recentAnswer = { RaRecentOutcome.Read(emptyList()) }
        loader().load()
        assertEquals(emptyList<Int>(), heldIds())
    }

    @Test
    fun `a recent read that failed keeps what the strip holds and a refused key stops the load`() = runTest {
        val loader = loader()
        loader.load()
        recentAnswer = { RaRecentOutcome.Unavailable }
        assertEquals("the games still publish", ActivityBulkResult.LOADED, loader.load())
        assertEquals(listOf(1, 2, 3), heldIds())
        recentAnswer = { RaRecentOutcome.CredentialsRejected }
        assertEquals(ActivityBulkResult.REJECTED, loader.load())
        assertEquals(listOf(1, 2, 3), heldIds())
    }

    @Test
    fun `a load that began under A and finished after a switch publishes no unlocks`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val loader = loader(recentGate = hold)
        val running = async { loader.load() }
        runCurrent()
        assertEquals("the recent read is in flight", 1, recentCalls)

        signedIn = "someone-else"
        book.accountChanged()
        hold.complete(Unit)
        assertEquals(ActivityBulkResult.DROPPED, running.await())
        assertNull(CompanionActivityAchievements.recent.value)
        assertTrue(CompanionActivityAchievements.snapshots.value.isEmpty())
    }

    @Test
    fun `an account change drops the old account's unlocks`() = runTest {
        loader().load()
        assertEquals(listOf(1, 2, 3), heldIds())
        CompanionActivityAchievements.dropAccountsExcept("recent-player")
        assertEquals("the same account keeps them", listOf(1, 2, 3), heldIds())
        CompanionActivityAchievements.dropAccountsExcept("someone-else")
        assertNull(CompanionActivityAchievements.recent.value)
    }

    @Test
    fun `an unchanged answer emits nothing`() = runTest {
        loader().load()
        val first = CompanionActivityAchievements.recent.value
        loader().load()
        assertTrue(CompanionActivityAchievements.recent.value === first)
    }

    // The hub view ----------------------------------------------------------------------------------------------------------------------

    private fun held(account: String = "recent-player") = CompanionActivityRecent(
        account,
        listOf(ActivityRecentUnlock(1, "Badge 1", "d", 5, "/Badge/00001.png", 1, "Super Metroid", 2_000_000L, hardcore = false)),
    )

    @Test
    fun `the view carries this account's unlocks only while the hub is enabled`() {
        fun view(account: String?, on: Boolean, validation: RaKeyState = RaKeyState.ACCEPTED, recent: CompanionActivityRecent? = held()) =
            companionActivityAchievementHubView(emptyList(), on, account, validation, recent = recent)
        assertEquals(1, view("recent-player", true).recent.size)
        assertTrue("another account's list is never shown", view("someone-else", true).recent.isEmpty())
        assertTrue("connected services off", view("recent-player", false).recent.isEmpty())
        assertTrue("a refused key", view("recent-player", true, RaKeyState.REJECTED_THIS_SESSION).recent.isEmpty())
        assertTrue("signed out", view(null, true).recent.isEmpty())
        assertTrue("nothing read yet", view("recent-player", true, recent = null).recent.isEmpty())
    }

    @Test
    fun `the flow carries the unlocks too and drops them on an account the list was not read for`() = runTest {
        val matching = companionActivityAchievementHubViewFlow(
            flowOf(emptyList()), flowOf(true), flowOf<String?>("recent-player"), flowOf(RaKeyState.ACCEPTED),
            recent = flowOf(held()),
        ).first()
        assertEquals(listOf(1), matching.recent.map { it.achievementId })
        val other = companionActivityAchievementHubViewFlow(
            flowOf(emptyList()), flowOf(true), flowOf<String?>("someone-else"), flowOf(RaKeyState.ACCEPTED),
            recent = flowOf(held()),
        ).first()
        assertTrue(other.recent.isEmpty())
    }
}
