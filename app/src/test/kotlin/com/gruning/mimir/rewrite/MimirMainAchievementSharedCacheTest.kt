package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.model.library.GameRow
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private fun sharedProgress(unlocked: Int) = sharedProgressFor(9, unlocked)

private fun sharedProgressFor(gameId: Int, unlocked: Int) = RetroAchievementsProgress(
    gameId = gameId,
    title = "Game $gameId",
    achievements = (1..10).map {
        RetroAchievementsAchievement(it, "A$it", "a", 5, unlocked = it <= unlocked, unlockedHardcore = false, badgeName = "%05d".format(it))
    },
)

/**
 * The detail cache Main's card and the Bottom's hub share, with the REAL coordinator ([MimirAchievementsState]) driven through its seams (only the probe's file and network work
 * and the per-game read are replaced): the card probe on Main reads the game once, its whole answer reaches the page, and it is also that game's one read for the five-minute
 * window, so the Bottom's hub, asking for the same game, asks nothing; an account change forgets it; and a probe that began under another account seeds nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class MimirMainAchievementSharedCacheTest {

    private val account = MutableStateFlow<String?>("main-player")
    private val keyState = MutableStateFlow(RaKeyState.ACCEPTED)
    private val optedIn = MutableStateFlow(true)
    private val sessions = MutableStateFlow<String?>(null)
    private var now = 10_000_000L
    private var probeCalls = 0
    private val progressCalls = mutableListOf<Int>()
    private val states = mutableListOf<MimirAchievementsState>()

    private val x = GameRow(gameId = GameId("x"), title = "Game x", platformId = PlatformId("snes"), memberCount = 1, duplicateKind = null)

    @Before
    fun reset() {
        CompanionActivityAchievements.resetForTest()
        CompanionActivityCardDetails.resetForTest()
    }

    @After
    fun closeAll() = states.forEach(MimirAchievementsState::close)

    private fun state(): MimirAchievementsState =
        MimirAchievementsState(
            RuntimeEnvironment.getApplication(),
            MimirAchievementsSeams(
                account = account,
                keyState = keyState,
                optedIn = optedIn,
                sessionKeys = sessions,
                scopeContext = Dispatchers.Unconfined,
                probeWork = { _, report ->
                    probeCalls += 1
                    report(MimirAchievementLaneState.Loaded(sharedProgress(3)), null)
                },
                // Nothing here may reach the real client: an account change starts the bulk load, which these stand-ins answer with nothing.
                bulkGames = { RaUserGamesOutcome.Unavailable },
                bulkPoints = { RaPointsOutcome.Unavailable },
                bulkRecent = { RaRecentOutcome.Unavailable },
                bulkLibrary = { emptyList() },
                iconClear = {},
                cardDetailFetch = { raGameId ->
                    progressCalls += raGameId
                    RaProgressOutcome.Read(sharedProgress(5))
                },
                bulkContext = EmptyCoroutineContext,
                publishContext = EmptyCoroutineContext,
                clock = { now },
            ),
        ).also { states += it }

    @Test
    fun `Main's card probe hands the page its whole answer and warms the Bottom's cache, which then asks nothing`() = runBlocking {
        val state = state()
        state.openCardFromHome(x)
        assertNull("nothing is loaded before the probe answers", state.cardProgress())
        state.probeFromHome(state.homeProbeGame!!, state.homeProbeEpoch)

        val loaded = state.cardProgress()
        assertNotNull("the page reads the probe's own answer", loaded)
        assertEquals(9, loaded!!.gameId)
        assertEquals(10, loaded.totalCount)
        assertEquals(3, loaded.unlockedCount)
        assertEquals("one card probe, one read of the game", 1, probeCalls)
        assertTrue("the shared cache holds Main's answer", CompanionActivityCardDetails.state.value[9] is ActivityCardDetail.Loaded)

        // The Bottom's hub composes a card for the same game and asks for its detail.
        CompanionActivityCardDetails.request(9)
        assertEquals("the Bottom reads what Main read and asks nothing", emptyList<Int>(), progressCalls)
        val seeded = CompanionActivityCardDetails.state.value[9] as ActivityCardDetail.Loaded
        assertEquals(3, seeded.progress.unlockedCount)

        // Inside the window, however often it asks; after it, once.
        now += ACTIVITY_CARD_DETAIL_REFRESH_MS - 1
        CompanionActivityCardDetails.request(9)
        assertEquals(emptyList<Int>(), progressCalls)
        now += 2
        CompanionActivityCardDetails.request(9)
        CompanionActivityCardDetails.request(9)
        assertEquals("one read for the new window", listOf(9), progressCalls)
    }

    @Test
    fun `an account change forgets what Main's probe seeded`() = runBlocking {
        val state = state()
        state.openCardFromHome(x)
        state.probeFromHome(state.homeProbeGame!!, state.homeProbeEpoch)
        assertTrue(CompanionActivityCardDetails.state.value[9] is ActivityCardDetail.Loaded)
        account.value = "someone-else"
        assertEquals("nothing the old account's card knew is the new one's", emptyMap<Int, ActivityCardDetail>(), CompanionActivityCardDetails.state.value)
        assertNull("and the page has no answer for the open game", state.cardProgress())
    }

    @Test
    fun `the card's fence moves when a card opens, when its page lets go of the walk and when it is dismissed, and not when a page mounts or a stale release comes`() {
        val state = state()
        val start = state.cardFence
        state.openCardFromHome(x)
        val opened = state.cardFence
        assertTrue("opening a card is a new life", opened != start)
        val grid: (com.gruning.mimir.rewrite.core.input.ControllerAction) -> Boolean = { true }
        state.installCardGrid(grid)
        assertEquals("a page mounting ends nothing", opened, state.cardFence)
        state.releaseCardGrid { false }
        assertEquals("a stale release ends nothing", opened, state.cardFence)
        state.releaseCardGrid(grid)
        val released = state.cardFence
        assertTrue("the page letting go ends its life", released != opened)
        state.dismissCard()
        assertTrue("a dismissal ends the card's", state.cardFence != released)
    }

    @Test
    fun `a card that is not showing a loaded set has no page, and the walk is installed and released by the page that mounts it`() {
        val state = state()
        assertNull(state.cardProgress())
        assertNull(state.cardGrid)
        val first: (com.gruning.mimir.rewrite.core.input.ControllerAction) -> Boolean = { true }
        val second: (com.gruning.mimir.rewrite.core.input.ControllerAction) -> Boolean = { false }
        state.installCardGrid(first)
        assertTrue(state.cardGrid === first)
        state.installCardGrid(second)
        state.releaseCardGrid(first)
        assertTrue("a stale release never unplugs its successor", state.cardGrid === second)
        state.releaseCardGrid(second)
        assertNull(state.cardGrid)
    }
}

/** The seed itself, on the loader: it is the game's ask for the window, epoch-fenced, and publishes nothing for a probe that began under another account. */
class MimirMainAchievementSeedTest {

    private class Fixture(var now: Long = 1_000_000L) {
        val fetched = mutableListOf<Int>()
        val sink = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap())
        val book = RetroAchievementsLaneBook()
        /** Held by a test to keep a read under way. */
        var gate: CompletableDeferred<Unit>? = null
        /** One per read, taken in order: a test hangs a particular read on its own. */
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var unlockedFor: (call: Int) -> Int = { 2 }
        var account: String? = "me"
        val loader = ActivityCardDetailLoader(
            scope = CoroutineScope(Dispatchers.Unconfined),
            book = book,
            account = { account },
            fetch = { id ->
                fetched += id
                val call = fetched.size
                gates.removeFirstOrNull()?.await()
                gate?.await()
                RaProgressOutcome.Read(sharedProgressFor(id, unlockedFor(call)))
            },
            sink = sink,
            clock = { now },
            workContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
        )
    }

    @Test
    fun `a seeded game is not asked for again inside the window, and is asked for once after it`() {
        val f = Fixture()
        f.loader.seed(sharedProgress(4), f.book.accountEpoch())
        assertEquals(4, ((f.sink.value[9]) as ActivityCardDetail.Loaded).progress.unlockedCount)
        f.loader.request(9)
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS - 1
        f.loader.request(9)
        assertEquals(emptyList<Int>(), f.fetched)
        f.now += 2
        f.loader.request(9)
        f.loader.request(9)
        assertEquals(listOf(9), f.fetched)
    }

    @Test
    fun `twenty games visited leave eight answers, the most recently used, and no game is probed twice in its window whatever the cache holds`() {
        val f = Fixture()
        (1..20).forEach { f.loader.request(it) }
        assertEquals("the cache is bounded to the last eight games", (13..20).toSet(), f.sink.value.keys)
        assertEquals(20, f.fetched.size)
        // A served read makes a game the most recently used, so the next game in evicts the one after it.
        assertNotNull(f.loader.read(13))
        f.loader.request(21)
        assertEquals(8, f.sink.value.size)
        assertTrue("13 was just used, so it stays", 13 in f.sink.value.keys)
        assertFalse("14 was the least recently used, so it went", 14 in f.sink.value.keys)
        assertEquals(21, f.fetched.size)
        // Evicted games are inside their window: asking again probes nothing (the throttle outlives the cache) and the card reads "no detail", not an endless "Loading".
        f.loader.request(1)
        f.loader.request(14)
        assertEquals("no game is probed twice inside five minutes", 21, f.fetched.size)
        assertEquals(ActivityCardDetail.Unavailable, f.sink.value[1])
        assertEquals(8, f.sink.value.size)
        // After the window each is probed once more, and its answer replaces the placeholder.
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS + 1
        f.loader.request(1)
        f.loader.request(1)
        assertEquals(22, f.fetched.size)
        assertTrue(f.sink.value[1] is ActivityCardDetail.Loaded)
    }

    @Test
    fun `ping-ponging across twelve games for four minutes probes each game exactly once`() {
        val f = Fixture()
        repeat(12) {
            (1..12).forEach { f.loader.request(it) }
            f.now += 20_000L
        }
        assertEquals("twelve laps, twenty seconds apart: one probe per game", (1..12).toList(), f.fetched.sorted())
        assertTrue("and the cache stayed bounded", f.sink.value.size <= ACTIVITY_CARD_DETAIL_MAX_GAMES)
        // Past the five minutes each game is probed once more, no more.
        f.now += 61_000L
        repeat(3) { (1..12).forEach { f.loader.request(it) } }
        assertEquals(24, f.fetched.size)
    }

    @Test
    fun `sixty-five games asked about in one window each get their first read, and cycling again probes none of them a second time`() {
        val f = Fixture()
        (1..65).forEach { f.loader.request(it) }
        assertEquals("a game's first view is never turned away for want of room: the oldest stamp makes way", 65, f.fetched.size)
        assertTrue(f.sink.value[65] is ActivityCardDetail.Loaded)
        // Cycling through all of them again and again probes none a second time: the repeat for the game whose stamp made way waits for a stamp to expire.
        repeat(3) { (1..65).forEach { f.loader.request(it) } }
        assertEquals(65, f.fetched.size)
        f.loader.request(1)
        assertEquals("its card is not bare: it reads as a failure the player can tap", ActivityCardDetail.Unavailable, f.sink.value[1])
        // The tap reads it, whatever the window says.
        f.loader.retry(1)
        assertEquals(1, f.fetched.last())
        assertEquals(66, f.fetched.size)
        assertTrue(f.sink.value[1] is ActivityCardDetail.Loaded)
        // Once a window has passed the stamps expire and every game may be read once more.
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS + 1
        f.loader.request(2)
        assertEquals(67, f.fetched.size)
    }

    @Test
    fun `a second ask for a game whose read is still under way, inside its deadline, starts nothing`() {
        val f = Fixture()
        f.gate = CompletableDeferred()
        f.loader.request(7)
        assertEquals(listOf(7), f.fetched)
        f.loader.request(7)
        f.now += ACTIVITY_CARD_READ_DEADLINE_MS - 1
        f.loader.request(7)
        assertEquals("one request in flight", listOf(7), f.fetched)
        f.gate!!.complete(Unit)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
        // Once it has landed the game can be asked about again after its window.
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS + 1
        f.loader.request(7)
        assertEquals(listOf(7, 7), f.fetched)
    }

    @Test
    fun `a hung read stops blocking its game after its deadline, and its late answer is dropped when a newer read exists`() {
        val f = Fixture()
        val hung = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        f.gates += hung
        f.gates += second
        f.unlockedFor = { call -> if (call == 1) 1 else 3 }
        f.loader.request(7)
        assertEquals(listOf(7), f.fetched)
        // Past the deadline the hung read no longer holds the game, but the throttle still says it was asked inside its window: nothing goes out.
        f.now += ACTIVITY_CARD_READ_DEADLINE_MS + 1
        f.loader.request(7)
        assertEquals(listOf(7), f.fetched)
        // Once the window has passed, the game is asked for again, though the first read never came back.
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS
        f.loader.request(7)
        assertEquals("a hung fetch does not block its game for the loader's life", listOf(7, 7), f.fetched)
        // The hung read comes back late: a newer read exists, so it publishes nothing.
        hung.complete(Unit)
        assertFalse("the late answer was dropped: ${f.sink.value[7]}", f.sink.value[7] is ActivityCardDetail.Loaded)
        second.complete(Unit)
        assertEquals(3, (f.sink.value[7] as ActivityCardDetail.Loaded).progress.unlockedCount)
    }

    @Test
    fun `a new account's ask is not blocked by the old account's read, whose answer is dropped`() {
        val f = Fixture()
        val old = CompletableDeferred<Unit>()
        f.gates += old
        f.unlockedFor = { call -> if (call == 1) 1 else 3 }
        f.loader.request(7)
        // The account changes while that read is under way.
        f.account = "someone-else"
        f.book.accountChanged()
        f.loader.reset()
        f.loader.request(7)
        assertEquals("the new account asks at once", listOf(7, 7), f.fetched)
        assertEquals(3, (f.sink.value[7] as ActivityCardDetail.Loaded).progress.unlockedCount)
        old.complete(Unit)
        assertEquals("the old account's answer is dropped", 3, (f.sink.value[7] as ActivityCardDetail.Loaded).progress.unlockedCount)
        assertEquals(listOf(7, 7), f.fetched)
    }

    @Test
    fun `a shown card loads the new account's detail without a remount when the account changes under its read`() {
        val f = Fixture()
        val old = CompletableDeferred<Unit>()
        f.gates += old
        f.unlockedFor = { call -> if (call == 1) 1 else 3 }
        f.loader.request(7)
        val release = f.loader.hold(7)
        f.account = "someone-else"
        f.book.accountChanged()
        f.loader.reset()
        // Nothing asked for the card since (it was not remounted); the old read lands, is dropped, and the shown card's game is asked for as the new account.
        assertEquals(listOf(7), f.fetched)
        old.complete(Unit)
        assertEquals(listOf(7, 7), f.fetched)
        assertEquals(3, (f.sink.value[7] as ActivityCardDetail.Loaded).progress.unlockedCount)
        release()
        // A card that is not shown does not ask on its own behalf.
        val g = Fixture()
        val oldG = CompletableDeferred<Unit>()
        g.gates += oldG
        g.loader.request(8)
        g.account = "someone-else"
        g.book.accountChanged()
        g.loader.reset()
        oldG.complete(Unit)
        assertEquals(listOf(8), g.fetched)
    }

    @Test
    fun `an answer past five minutes is only stale, still served until its refresh lands, and no other game's ask takes it out`() {
        val f = Fixture()
        f.loader.seed(sharedProgressFor(9, 4), f.book.accountEpoch())
        assertFalse(f.loader.isStale(9))
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS + 1
        assertTrue("past the window it is stale", f.loader.isStale(9))
        assertNotNull("and still served", f.loader.read(9))
        f.loader.request(10)
        f.loader.seed(sharedProgressFor(11, 1), f.book.accountEpoch())
        assertEquals("other games asking and seeding leave it be", 4, (f.sink.value[9] as ActivityCardDetail.Loaded).progress.unlockedCount)
        // Its own ask, after the window, refreshes it.
        f.loader.request(9)
        assertEquals(listOf(10, 9), f.fetched)
        assertEquals(2, (f.sink.value[9] as ActivityCardDetail.Loaded).progress.unlockedCount)
        assertFalse(f.loader.isStale(9))
    }

    @Test
    fun `a shown card holds its game, so no other game evicts it, stale or not, until it lets go`() {
        val f = Fixture()
        f.loader.seed(sharedProgressFor(9, 4), f.book.accountEpoch())
        val release = f.loader.hold(9)
        (20..40).forEach { f.loader.seed(sharedProgressFor(it, 1), f.book.accountEpoch()) }
        assertTrue("the held game survives twenty-one others", f.sink.value[9] is ActivityCardDetail.Loaded)
        assertEquals(ACTIVITY_CARD_DETAIL_MAX_GAMES, f.sink.value.size)
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS + 1
        f.loader.request(50)
        assertTrue("stale or not, a held answer stays", f.sink.value[9] is ActivityCardDetail.Loaded)
        release()
        release()
        f.loader.seed(sharedProgressFor(60, 1), f.book.accountEpoch())
        assertFalse("once let go it is the least recently used, and goes", 9 in f.sink.value.keys)
        assertEquals(ACTIVITY_CARD_DETAIL_MAX_GAMES, f.sink.value.size)
    }

    @Test
    fun `a probe that began under another account seeds nothing and leaves the game unasked`() {
        val f = Fixture()
        val epoch = f.book.accountEpoch()
        f.book.accountChanged()
        f.loader.seed(sharedProgress(4), epoch)
        assertEquals(emptyMap<Int, ActivityCardDetail>(), f.sink.value)
        f.loader.request(9)
        assertEquals("the game was never asked, so it is asked now", listOf(9), f.fetched)
    }
}
