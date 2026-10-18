package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.RaKeyState
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Activity page's cold-start bulk read: ONE completion read (paged inside the client) and ONE points read per load, every game shown, matched to
 * the library where it can be, held in the one snapshot the hub view and the strip read, fenced by the account epoch, and refreshed after a return at
 * most once per five minutes. Counting fakes stand in for the client, so the call counts are the fakes' own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsActivityBulkTest {

    private var signedIn: String? = "bulk-player"
    private var now = 1_000_000L
    private var gameCalls = 0
    private var pointCalls = 0
    private var libraryCalls = 0
    private var gamesAnswer: () -> RaUserGamesOutcome = { RaUserGamesOutcome.Read(defaultGames, defaultGames.size) }
    private var pointsAnswer: () -> RaPointsOutcome = { RaPointsOutcome.Read(1500) }
    private val book = RetroAchievementsLaneBook()

    private val defaultGames = listOf(
        RetroAchievementsUserGame(1, "Super Metroid", "Super Nintendo Entertainment System", earned = 12, total = 40),
        RetroAchievementsUserGame(2, "Sonic the Hedgehog 2", "Mega Drive", earned = 3, total = 30),
        RetroAchievementsUserGame(3, "An Obscure Game", "Game Boy", earned = 1, total = 10),
    )
    private val library = listOf(
        LocalGameKey("local-metroid", "snes", "Super Metroid (USA)"),
        LocalGameKey("local-sonic", "genesis", "Sonic The Hedgehog 2"),
    )
    private val platforms = mapOf(
        "Super Nintendo Entertainment System" to "snes",
        "Mega Drive" to "genesis",
        "Game Boy" to "gb",
    )

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    private fun loader(
        workGate: CompletableDeferred<Unit>? = null,
        publishContext: kotlin.coroutines.CoroutineContext = EmptyCoroutineContext,
    ) = ActivityBulkLoader(
        book = book,
        account = { signedIn },
        loadGames = {
            gameCalls += 1
            workGate?.await()
            gamesAnswer()
        },
        loadPoints = {
            pointCalls += 1
            pointsAnswer()
        },
        library = {
            libraryCalls += 1
            library
        },
        platformIdFor = platforms::get,
        publish = { account, snapshots, points -> CompanionActivityAchievements.publishBulk(account, snapshots, points) },
        clock = { now },
        workContext = EmptyCoroutineContext,
        publishContext = publishContext,
    )

    private fun hub() = companionActivityAchievementHubView(
        snapshots = CompanionActivityAchievements.snapshots.value,
        connectedServicesOn = true,
        account = signedIn,
        validation = RaKeyState.ACCEPTED,
        points = CompanionActivityAchievements.points.value,
    )

    @Test
    fun `cold start makes one bulk load and one points load and the hub shows every game and the total`() = runTest {
        assertEquals(ActivityBulkResult.LOADED, loader().load())

        assertEquals(1, gameCalls)
        assertEquals(1, pointCalls)
        val view = hub()
        assertEquals(3, view.snapshots.size)
        assertEquals(12 + 3 + 1, view.unlocked)
        assertEquals(40 + 30 + 10, view.total)
        assertEquals(1500, view.totalPoints)
        // Every game still shows; the one the library does not have wears a neutral identity and the numbers RA gave.
        val byId = view.snapshots.associateBy { it.gameId }
        assertEquals(12, byId.getValue("local-metroid").unlocked)
        assertEquals("Super Metroid (USA)", byId.getValue("local-metroid").gameTitle)
        assertEquals(3, byId.getValue("local-sonic").unlocked)
        val unmatched = byId.getValue("ra:3")
        assertEquals("An Obscure Game", unmatched.gameTitle)
        assertEquals(1, unmatched.unlocked)
        // Counts only: the page says its badges are not loaded instead of claiming nothing was earned.
        assertEquals(com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy.BadgesNotLoaded, unmatched.toAchievementCardState().emptyLine)
        assertEquals("16 of 80 earned across 3 games · 1,500 points", activityAchievementsStripText(view.unlocked, view.total, view.snapshots.size, view.totalPoints))
    }

    @Test
    fun `nothing is asked when RetroAchievements is not connected`() = runTest {
        signedIn = null
        assertEquals(ActivityBulkResult.NOT_SIGNED_IN, loader().load())
        assertEquals(0, gameCalls)
        assertEquals(0, pointCalls)
    }

    @Test
    fun `a return refreshes at most once per five minutes`() = runTest {
        val loader = loader()
        loader.load()
        assertEquals(1, gameCalls)

        now += 4 * 60_000L + 59_000L
        assertEquals(ActivityBulkResult.THROTTLED, loader.refreshAfterReturn())
        assertEquals(1, gameCalls)
        assertEquals(1, pointCalls)

        now += 1_000L
        assertEquals(ActivityBulkResult.LOADED, loader.refreshAfterReturn())
        assertEquals(2, gameCalls)
        assertEquals(2, pointCalls)

        // The throttle counts from that refresh, not from the cold start.
        now += 60_000L
        assertEquals(ActivityBulkResult.THROTTLED, loader.refreshAfterReturn())
        assertEquals(2, gameCalls)
    }

    @Test
    fun `an account change resets the throttle and drops the old account's bulk data`() = runTest {
        val loader = loader()
        loader.load()
        assertEquals(3, hub().snapshots.size)

        // B signs in: the epoch is bumped, the old account's data is dropped, the throttle starts over.
        signedIn = "bulk-player-b"
        book.accountChanged()
        loader.reset()
        CompanionActivityAchievements.dropAccountsExcept(signedIn)
        assertTrue(hub().snapshots.isEmpty())
        assertNull(hub().totalPoints)
        assertNull(CompanionActivityAchievements.points.value)

        assertEquals(ActivityBulkResult.LOADED, loader.refreshAfterReturn())
        assertEquals(2, gameCalls)
        assertEquals(3, hub().snapshots.size)
        assertTrue(CompanionActivityAchievements.snapshots.value.all { it.account == "bulk-player-b" })
    }

    @Test
    fun `a load that began under A and finished after a switch publishes nothing`() = runTest {
        val gate = CompanionActivityAchievements
        val hold = CompletableDeferred<Unit>()
        val loader = loader(workGate = hold)
        val running = async { loader.load() }
        runCurrent()
        assertEquals(1, gameCalls)

        signedIn = "someone-else"
        book.accountChanged()
        hold.complete(Unit)
        assertEquals(ActivityBulkResult.DROPPED, running.await())
        assertTrue(gate.snapshots.value.isEmpty())
        assertNull(gate.points.value)
    }

    @Test
    fun `a second load while one runs is turned away and a failed read keeps what the page holds`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val loader = loader(workGate = hold)
        val first = async { loader.load() }
        runCurrent()
        assertEquals(ActivityBulkResult.BUSY, loader.load())
        hold.complete(Unit)
        assertEquals(ActivityBulkResult.LOADED, first.await())
        assertEquals(1, gameCalls)

        val held = CompanionActivityAchievements.snapshots.value
        gamesAnswer = { RaUserGamesOutcome.Unavailable }
        assertEquals(ActivityBulkResult.UNAVAILABLE, loader.load())
        assertEquals(held, CompanionActivityAchievements.snapshots.value)
        gamesAnswer = { RaUserGamesOutcome.CredentialsRejected }
        assertEquals(ActivityBulkResult.REJECTED, loader.load())
        assertEquals(held, CompanionActivityAchievements.snapshots.value)
    }

    @Test
    fun `points that could not be read do not stop the games`() = runTest {
        pointsAnswer = { RaPointsOutcome.Unavailable }
        assertEquals(ActivityBulkResult.LOADED, loader().load())
        assertEquals(3, hub().snapshots.size)
        assertNull(hub().totalPoints)
        assertEquals("16 of 80 earned across 3 games", activityAchievementsStripText(16, 80, 3, null))
    }

    // Matching ------------------------------------------------------------------------------------------------------------------------

    private fun match(
        games: List<RetroAchievementsUserGame> = defaultGames,
        local: List<LocalGameKey> = library,
        known: Map<Int, String> = emptyMap(),
    ) = matchActivityBulk(games, local, platforms::get, known, account = "p", nowMillis = 5L).associateBy { it.gameId }

    @Test
    fun `a game matches the one library entry on its platform with its title and never a guess`() {
        val ids = match().keys
        assertTrue(ids.containsAll(listOf("local-metroid", "local-sonic", "ra:3")))

        // Two library games with the same title on the platform: ambiguous, so neither is picked.
        val twin = library + LocalGameKey("local-metroid-2", "snes", "Super Metroid")
        assertNotNull(match(local = twin)["ra:1"])
        // The same title on another platform is another game.
        assertNotNull(match(local = listOf(LocalGameKey("gb-metroid", "gb", "Super Metroid")))["ra:1"])
    }

    @Test
    fun `an id a launch-card read already identified from the file wins over a title`() {
        val matched = match(
            local = library + LocalGameKey("renamed-by-owner", "snes", "Totally Different Name"),
            known = mapOf(1 to "renamed-by-owner"),
        )
        assertEquals(12, matched.getValue("renamed-by-owner").unlocked)
        assertFalse(matched.containsKey("local-metroid"))
    }

    @Test
    fun `a malformed count is never shown`() {
        val odd = listOf(
            RetroAchievementsUserGame(10, "No set", "Game Boy", earned = 0, total = 0),
            RetroAchievementsUserGame(11, "Too many", "Game Boy", earned = 9, total = 4),
            RetroAchievementsUserGame(12, "Fine", "Game Boy", earned = 1, total = 4),
        )
        assertEquals(setOf("ra:12"), match(games = odd).keys)
    }

    @Test
    fun `titles are compared without region notes punctuation articles or case`() {
        assertEquals(retroAchievementsMatchTitle("The Legend of Zelda: A Link to the Past (USA)"), retroAchievementsMatchTitle("Legend of Zelda - A Link to the Past"))
        assertEquals(retroAchievementsMatchTitle("Sonic & Knuckles"), retroAchievementsMatchTitle("Sonic and Knuckles [!]"))
        assertFalse(retroAchievementsMatchTitle("Sonic 2") == retroAchievementsMatchTitle("Sonic 3"))
    }

    @Test
    fun `a richer snapshot from a launch card survives a bulk answer with the same counts`() {
        val rich = CompanionActivityAchievementSnapshot(
            gameId = "local-metroid",
            account = "bulk-player",
            unlocked = 12,
            total = 40,
            gameTitle = "Super Metroid",
            earned = listOf(CompanionActivityEarnedAchievement(1, "First", "first", 5, hardcore = false)),
            checkedAtEpochMillis = 1L,
        )
        CompanionActivityAchievements.publish("local-metroid", "bulk-player", MimirAchievementLaneState.Loaded(
            com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress(
                gameId = 1,
                title = "Super Metroid",
                achievements = (1..40).map {
                    com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement(it, "A$it", "a", 1, unlocked = it <= 12, unlockedHardcore = false)
                },
            ),
        ))
        val heldRich = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(12, heldRich.earned.size)

        CompanionActivityAchievements.publishBulk("bulk-player", match().values.map { it.copy(account = "bulk-player") }, 10)
        val metroid = CompanionActivityAchievements.snapshots.value.single { it.gameId == "local-metroid" }
        // The rows stay when the counts agree; what the bulk read knew and the launch-card check did not (the console, for the Activity filter) is folded in.
        assertEquals("the richer snapshot's rows stay when the counts agree", heldRich.earned, metroid.earned)
        assertEquals(12, metroid.unlocked)
        assertNotNull("the bulk read's console reached the richer snapshot", metroid.consoleName)
        assertEquals(3, CompanionActivityAchievements.snapshots.value.size)
        assertEquals(rich.gameId, metroid.gameId)
        // The same answer again changes nothing: the snapshot is the same instance and nothing is emitted.
        CompanionActivityAchievements.publishBulk("bulk-player", match().values.map { it.copy(account = "bulk-player") }, 10)
        assertTrue("an unchanged answer keeps the snapshot", CompanionActivityAchievements.snapshots.value.single { it.gameId == "local-metroid" } === metroid)
    }

    @Test
    fun `two RA games whose titles normalize alike are both left unmatched under distinct keys`() {
        val twins = listOf(
            RetroAchievementsUserGame(21, "Sonic the Hedgehog", "Mega Drive", earned = 2, total = 20),
            RetroAchievementsUserGame(22, "Sonic the Hedgehog (Prototype)", "Mega Drive", earned = 1, total = 10),
            RetroAchievementsUserGame(23, "Super Metroid", "Super Nintendo Entertainment System", earned = 5, total = 50),
        )
        val local = listOf(LocalGameKey("local-sonic-1", "genesis", "Sonic The Hedgehog"), LocalGameKey("local-metroid", "snes", "Super Metroid"))
        val matched = matchActivityBulk(twins, local, platforms::get, emptyMap(), account = "p", nowMillis = 5L)
        val ids = matched.map { it.gameId }
        // The pair collides on the RA side, so neither claims the library game; the unique one still matches.
        assertEquals(listOf("ra:21", "ra:22", "local-metroid"), ids)
        assertEquals("every hub key is unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `a library game is claimed by one RA game only so keys never repeat`() {
        // Two RA ids the launch cards both identified as the same library game: the first keeps it, the second shows under its own RA key.
        val matched = matchActivityBulk(
            games = defaultGames.take(2),
            local = library.take(1), // only the Metroid entry: the second RA id has no other library game to fall back on
            platformIdFor = platforms::get,
            knownLocalByRaId = mapOf(1 to "local-metroid", 2 to "local-metroid"),
            account = "p",
            nowMillis = 5L,
        )
        assertEquals(listOf("local-metroid", "ra:2"), matched.map { it.gameId })
    }

    @Test
    fun `an account change mid-load queues the new account's load behind the cancelled one and ends with the new account loaded`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val loader = loader(workGate = hold)
        val old = async { loader.load() } // the old account's load, held in flight
        runCurrent()
        assertEquals(1, gameCalls)

        // B signs in: the old load is cancelled by the caller, the epoch moves, and B's load asks to wait rather than be turned away as BUSY.
        signedIn = "bulk-player-b"
        book.accountChanged()
        loader.reset()
        CompanionActivityAchievements.dropAccountsExcept(signedIn)
        old.cancel()
        hold.complete(Unit)
        val fresh = loader.load(waitForRunning = true)

        assertEquals(ActivityBulkResult.LOADED, fresh)
        assertEquals(2, gameCalls)
        assertEquals(3, hub().snapshots.size)
        assertTrue(CompanionActivityAchievements.snapshots.value.all { it.account == "bulk-player-b" })
    }

    @Test
    fun `without waiting a second load is still turned away as busy`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val loader = loader(workGate = hold)
        val running = async { loader.load() }
        runCurrent()
        assertEquals(ActivityBulkResult.BUSY, loader.load())
        hold.complete(Unit)
        running.await()
    }

    @Test
    fun `a switch between the read finishing and the write publishes nothing`() = runTest {
        // The publish hop runs on a context that lets the account change land first, which is exactly the window between "check" and "write" that
        // the fence and the write now share.
        val switchBeforePublish = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                signedIn = "someone-else"
                book.accountChanged()
                block.run()
            }
        }
        val result = loader(publishContext = switchBeforePublish).load()
        assertEquals(ActivityBulkResult.DROPPED, result)
        assertTrue(CompanionActivityAchievements.snapshots.value.isEmpty())
        assertNull(CompanionActivityAchievements.points.value)
    }
}
