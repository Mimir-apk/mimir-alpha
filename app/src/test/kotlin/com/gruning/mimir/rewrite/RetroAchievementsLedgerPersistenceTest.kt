package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAccount
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerVerdict
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerVerdictKind
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerWrite
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsRecentUnlock
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleCodec
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleV1
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The durable RetroAchievements ledger in the app's lane: a complete bulk read is saved and the same one twice changes nothing; a partial, refused or
 * unavailable read never touches what is saved; a cold start shows the saved counts with no network at all; an account's rows come back instantly after a
 * switch and never reach another account; the art is found on disk before any fetch; and a backup carries the counts to another device. Counting fakes stand
 * in for the client, so every call count is the fakes' own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsLedgerPersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var signedIn: String? = "bulk-player"
    private var now = 1_000_000L
    private var gameCalls = 0
    private var pointCalls = 0
    private var libraryCalls = 0
    private val logs = mutableListOf<String>()
    private var gamesAnswer: () -> RaUserGamesOutcome = { RaUserGamesOutcome.Read(defaultGames, defaultGames.size) }
    private var pointsAnswer: () -> RaPointsOutcome = { RaPointsOutcome.Read(1500) }
    private var recentAnswer: () -> RaRecentOutcome = { RaRecentOutcome.Read(listOf(unlock(1))) }
    private val book = RetroAchievementsLaneBook()

    private val ledger by lazy { RetroAchievementsLedger(File(temp.root, "ra_ledger")) }

    private val defaultGames = listOf(
        RetroAchievementsUserGame(1, "Super Metroid", "Super Nintendo Entertainment System", earned = 12, total = 40, imageIconPath = "/Images/000001.png"),
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

    private fun unlock(id: Int) =
        RetroAchievementsRecentUnlock(id, "Unlock $id", "desc", 5, "%05d".format(id), 1, "Super Metroid", "SNES", 1_700_000_000_000L, hardcore = false)

    private fun loader(
        library: suspend () -> List<LocalGameKey> = { libraryCalls += 1; this.library },
        bulkLedger: RetroAchievementsLedger? = ledger,
    ) = ActivityBulkLoader(
        book = book,
        account = { signedIn },
        loadGames = { gameCalls += 1; gamesAnswer() },
        loadPoints = { pointCalls += 1; pointsAnswer() },
        library = library,
        platformIdFor = platforms::get,
        publish = { account, snapshots, points -> CompanionActivityAchievements.publishBulk(account, snapshots, points) },
        clock = { now },
        workContext = EmptyCoroutineContext,
        publishContext = EmptyCoroutineContext,
        loadRecent = { recentAnswer() },
        publishRecent = { account, unlocks -> CompanionActivityAchievements.publishRecent(account, unlocks) },
        ledger = bulkLedger,
        publishHydrated = { account, snapshots, points, recent -> CompanionActivityAchievements.publishHydrated(account, snapshots, points, recent) },
        markSettled = CompanionActivityAchievements::markSettled,
        log = { logs += it },
    )

    private fun hub(account: String? = signedIn) = companionActivityAchievementHubView(
        snapshots = CompanionActivityAchievements.snapshots.value,
        connectedServicesOn = true,
        account = account,
        validation = RaKeyState.ACCEPTED,
        points = CompanionActivityAchievements.points.value,
        recent = CompanionActivityAchievements.recent.value,
        settledAccount = CompanionActivityAchievements.settledAccount.value,
    )

    private fun bulkFile(account: String) = File(File(File(temp.root, "ra_ledger"), RetroAchievementsLedger.accountKey(account)!!), "bulk.json")

    /** What a switch does to the page: the epoch moves, the old account's rows leave memory, the throttle resets. */
    private fun switchTo(account: String?, loader: ActivityBulkLoader) {
        signedIn = account
        book.accountChanged()
        CompanionActivityAchievements.dropAccountsExcept(account)
        loader.reset()
    }

    // Saving -------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a complete read is saved to the ledger and says so in one line`() = runTest {
        assertEquals(ActivityBulkResult.LOADED, loader().load())

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals(listOf(1, 2, 3), saved.games.map { it.gameId })
        assertEquals(1500, saved.points)
        assertEquals(listOf(1), saved.recent!!.map { it.achievementId })
        assertEquals(listOf("bulk outcome=complete games=3 persisted=3"), logs)
    }

    @Test
    fun `the same answer twice changes nothing on disk`() = runTest {
        val loader = loader()
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        val file = bulkFile("bulk-player")
        val bytes = file.readBytes()
        assertTrue(file.setLastModified(5_000L))

        now += 60_000L
        assertEquals(ActivityBulkResult.LOADED, loader.load())

        assertTrue("byte-identical", bytes.contentEquals(file.readBytes()))
        assertEquals("not rewritten", 5_000L, file.lastModified())
        assertEquals(1_000_000L, ledger.readBulk("bulk-player")!!.savedAtMillis)
        assertEquals(2, logs.size)
        assertEquals(logs[0], logs[1])
    }

    @Test
    fun `a partial read does not overwrite a complete snapshot, and neither does an unavailable or refused one`() = runTest {
        val loader = loader()
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        val file = bulkFile("bulk-player")
        val bytes = file.readBytes()
        val held = CompanionActivityAchievements.snapshots.value
        logs.clear()

        gamesAnswer = { RaUserGamesOutcome.Partial(listOf(defaultGames[0].copy(earned = 40)), totalGames = 3) }
        assertEquals(ActivityBulkResult.PARTIAL, loader.load())
        gamesAnswer = { RaUserGamesOutcome.Unavailable }
        assertEquals(ActivityBulkResult.UNAVAILABLE, loader.load())
        gamesAnswer = { RaUserGamesOutcome.CredentialsRejected }
        assertEquals(ActivityBulkResult.REJECTED, loader.load())

        assertTrue("the saved snapshot is byte-identical", bytes.contentEquals(file.readBytes()))
        assertEquals(3, ledger.readBulk("bulk-player")!!.games.size)
        assertEquals(12, ledger.readBulk("bulk-player")!!.games.first().earned)
        assertEquals("nothing was published either", held, CompanionActivityAchievements.snapshots.value)
        assertEquals(
            listOf(
                "bulk outcome=partial games=1 persisted=3",
                "bulk outcome=unavailable games=0 persisted=3",
                "bulk outcome=unavailable games=0 persisted=3",
            ),
            logs,
        )
    }

    @Test
    fun `a partial read with nothing saved saves nothing and still ends the checking state`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Partial(defaultGames.take(2), totalGames = 3) }
        assertEquals(ActivityBulkResult.PARTIAL, loader().load())

        assertNull(ledger.readBulk("bulk-player"))
        assertFalse(bulkFile("bulk-player").exists())
        assertEquals(listOf("bulk outcome=partial games=2 persisted=0"), logs)
        assertEquals("bulk-player", CompanionActivityAchievements.settledAccount.value)
        assertTrue(CompanionActivityAchievements.snapshots.value.isEmpty())
    }

    @Test
    fun `an unavailable first read says unavailable with nothing held`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Unavailable }
        assertEquals(ActivityBulkResult.UNAVAILABLE, loader().load())
        assertEquals(listOf("bulk outcome=unavailable games=0 persisted=0"), logs)
        assertNull(ledger.readBulk("bulk-player"))
    }

    @Test
    fun `points that could not be read this time leave the saved total, while the games refresh`() = runTest {
        val loader = loader()
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        pointsAnswer = { RaPointsOutcome.Unavailable }
        recentAnswer = { RaRecentOutcome.Unavailable }
        gamesAnswer = { RaUserGamesOutcome.Read(defaultGames.map { it.copy(earned = it.earned + 1) }, 3) }
        now += 60_000L
        assertEquals(ActivityBulkResult.LOADED, loader.load())

        val saved = ledger.readBulk("bulk-player")!!
        assertEquals(1500, saved.points)
        assertEquals(listOf(13, 4, 2), saved.games.map { it.earned })
        assertEquals(listOf(1), saved.recent!!.map { it.achievementId })
    }

    @Test
    fun `no outcome line carries a name, a key or a url`() = runTest {
        val loader = loader()
        loader.load()
        gamesAnswer = { RaUserGamesOutcome.Unavailable }
        loader.load()
        gamesAnswer = { RaUserGamesOutcome.Partial(defaultGames, 3) }
        loader.load()
        for (line in logs) {
            assertTrue(line, Regex("^bulk outcome=(complete|partial|unavailable) games=\\d+ persisted=\\d+$").matches(line))
            assertFalse(line.contains("bulk-player"))
            assertFalse(line.contains("http", ignoreCase = true))
        }
    }

    private fun slowLoader(hold: CompletableDeferred<Unit>, pinSeen: MutableList<String?>? = null, credentials: (() -> RetroAchievementsAccount?)? = null) = ActivityBulkLoader(
        book = book,
        account = { signedIn },
        loadGames = { pin -> gameCalls += 1; pinSeen?.add(pin?.credentialRevision); hold.await(); gamesAnswer() },
        loadPoints = { pin -> pinSeen?.add(pin?.credentialRevision); RaPointsOutcome.Read(7) },
        library = { library },
        platformIdFor = platforms::get,
        publish = { account, snapshots, points -> CompanionActivityAchievements.publishBulk(account, snapshots, points) },
        clock = { now },
        workContext = EmptyCoroutineContext,
        publishContext = EmptyCoroutineContext,
        loadRecent = { pin -> pinSeen?.add(pin?.credentialRevision); RaRecentOutcome.Read(listOf(unlock(9))) },
        ledger = ledger,
        markSettled = CompanionActivityAchievements::markSettled,
        log = { logs += it },
        credentials = credentials,
    )

    @Test
    fun `a read that a switch overtook writes nothing, under either account`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val slow = slowLoader(hold)
        val running = async { slow.load() }
        runCurrent()
        switchTo("someone-else", slow)
        hold.complete(Unit)

        assertEquals(ActivityBulkResult.DROPPED, running.await())
        assertTrue("nothing reached the page", CompanionActivityAchievements.snapshots.value.isEmpty())
        assertNull("not under the account that asked", ledger.readBulk("bulk-player"))
        assertNull("and never under the one that is signed in now", ledger.readBulk("someone-else"))
        assertFalse(ledger.hasAccount("bulk-player"))
        assertFalse(ledger.hasAccount("someone-else"))
        assertEquals("the read still says how it ended, and that nothing is held", listOf("bulk outcome=complete games=3 persisted=0"), logs)
    }

    @Test
    fun `a read that a switch overtook leaves the account's earlier complete snapshot exactly as it was`() = runTest {
        seedLedger()
        val file = bulkFile("bulk-player")
        val before = file.readBytes()
        gamesAnswer = { RaUserGamesOutcome.Read(defaultGames.take(1), 1) }
        val hold = CompletableDeferred<Unit>()
        val slow = slowLoader(hold)
        val running = async { slow.load() }
        runCurrent()
        switchTo("someone-else", slow)
        hold.complete(Unit)

        assertEquals(ActivityBulkResult.DROPPED, running.await())
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(3, ledger.readBulk("bulk-player")!!.games.size)
    }

    @Test
    fun `every request of one read uses the sign-in captured when it began, however the stored one changes`() = runTest {
        val alice = RetroAchievementsAccount("bulk-player", "key-a", "rev-a")
        val bob = RetroAchievementsAccount("someone-else", "key-b", "rev-b")
        var captures = 0
        val seen = mutableListOf<String?>()
        val loader = slowLoader(CompletableDeferred<Unit>().also { it.complete(Unit) }, seen) { captures += 1; if (captures == 1) alice else bob }

        assertEquals(ActivityBulkResult.LOADED, loader.load())
        assertEquals("the stored sign-in was read once", 1, captures)
        assertEquals("games, points and recent unlocks all asked as the captured user", listOf<String?>("rev-a", "rev-a", "rev-a"), seen)
        assertEquals(3, ledger.readBulk("bulk-player")!!.games.size)
    }

    @Test
    fun `a switch in the middle of a pinned read writes nothing for either account`() = runTest {
        val alice = RetroAchievementsAccount("bulk-player", "key-a", "rev-a")
        val seen = mutableListOf<String?>()
        val hold = CompletableDeferred<Unit>()
        val loader = slowLoader(hold, seen) { alice }
        val running = async { loader.load() }
        runCurrent()
        switchTo("someone-else", loader)
        hold.complete(Unit)

        assertEquals(ActivityBulkResult.DROPPED, running.await())
        assertTrue("every request still asked as the account the read began for", seen.all { it == "rev-a" })
        assertFalse(ledger.hasAccount("bulk-player"))
        assertFalse(ledger.hasAccount("someone-else"))
        assertTrue(CompanionActivityAchievements.snapshots.value.isEmpty())
    }

    @Test
    fun `a stored sign-in that is not the account being read for, or is missing, asks nothing`() = runTest {
        val bob = RetroAchievementsAccount("someone-else", "key-b", "rev-b")
        for (held in listOf(bob, null)) {
            gameCalls = 0
            logs.clear()
            val seen = mutableListOf<String?>()
            val loader = slowLoader(CompletableDeferred<Unit>().also { it.complete(Unit) }, seen) { held }

            assertEquals(ActivityBulkResult.UNAVAILABLE, loader.load())
            assertEquals(0, gameCalls)
            assertTrue(seen.isEmpty())
            assertEquals(listOf("bulk outcome=unavailable games=0 persisted=0"), logs)
            assertFalse(ledger.hasAccount("bulk-player"))
            assertEquals("the page stops saying checking", "bulk-player", CompanionActivityAchievements.settledAccount.value)
            CompanionActivityAchievements.resetForTest()
        }
    }

    @Test
    fun `the account name is compared without regard to case when the sign-in is captured`() = runTest {
        val loader = slowLoader(CompletableDeferred<Unit>().also { it.complete(Unit) }, null) { RetroAchievementsAccount("BULK-Player", "k", "rev") }
        assertEquals(ActivityBulkResult.LOADED, loader.load())
    }

    @Test
    fun `a partial read leaves every saved row on the page and on disk`() = runTest {
        seedLedger()
        val loader = loader()
        assertTrue(loader.hydrate())
        assertEquals(3, CompanionActivityAchievements.snapshots.value.size)
        val before = bulkFile("bulk-player").readBytes()

        // RA reported 3 games; the read got 1 of them (the client answers Partial for a page chain that broke or held a row it could not read).
        gamesAnswer = { RaUserGamesOutcome.Partial(defaultGames.take(1), 3) }
        assertEquals(ActivityBulkResult.PARTIAL, loader.load())

        assertEquals("nothing is pruned from the page", 3, CompanionActivityAchievements.snapshots.value.size)
        assertTrue("and nothing is rewritten on disk", before.contentEquals(bulkFile("bulk-player").readBytes()))
        assertEquals(1500, CompanionActivityAchievements.points.value?.points)
        assertEquals("bulk-player", CompanionActivityAchievements.settledAccount.value)
    }

    // Cold start ---------------------------------------------------------------------------------------------------------------------

    private fun seedLedger(account: String = "bulk-player") {
        ledger.saveBulk(account, defaultGames, 1500, listOf(unlock(1)), nowMillis = 900_000L)
    }

    @Test
    fun `a cold start with a saved ledger shows the counts, points and strip with no network at all`() = runTest {
        seedLedger()
        val loader = loader()

        assertTrue(loader.hydrate())

        assertEquals("not one request", 0, gameCalls + pointCalls)
        val view = hub()
        assertEquals(3, view.snapshots.size)
        assertEquals(12 + 3 + 1, view.unlocked)
        assertEquals(40 + 30 + 10, view.total)
        assertEquals(1500, view.totalPoints)
        assertEquals(listOf(1), view.recent.map { it.achievementId })
        assertFalse("it knows something, so it is not checking", view.checking)
        // The saved games meet the library again exactly as a live read would.
        val byId = view.snapshots.associateBy { it.gameId }
        assertEquals("Super Metroid (USA)", byId.getValue("local-metroid").gameTitle)
        assertEquals(3, byId.getValue("local-sonic").unlocked)
        assertEquals("An Obscure Game", byId.getValue("ra:3").gameTitle)
        assertEquals("the time the data last changed", 900_000L, byId.getValue("local-metroid").checkedAtEpochMillis)
        assertEquals("/Images/000001.png", byId.getValue("local-metroid").iconPath)
    }

    @Test
    fun `before anything is known the page says checking and never zero, and a read ending settles it`() = runTest {
        val unknown = hub()
        assertTrue(unknown.enabled)
        assertTrue(unknown.checking)
        assertEquals(0, unknown.snapshots.size)
        // The hub page's card agrees with the Activity card's "Checking achievements...": its own pending state, not "nothing checked yet".
        assertEquals(MimirAchievementCardState.Unknown(MimirAchievementCopy.Checking), unknown.pageUnknownState())

        // A page that does not track settling (the default) is never "checking".
        val untracked = companionActivityAchievementHubView(
            snapshots = emptyList(),
            connectedServicesOn = true,
            account = "bulk-player",
            validation = RaKeyState.ACCEPTED,
        )
        assertFalse(untracked.checking)
        assertEquals(MimirAchievementCardState.Unknown(MimirAchievementCopy.NothingCheckedYet), untracked.pageUnknownState())
        // Not connected: the plain unknown state, not "checking".
        val disconnected = companionActivityAchievementHubView(emptyList(), true, null, RaKeyState.NEVER_CHECKED, settledAccount = null)
        assertFalse(disconnected.checking)
        assertEquals(MimirAchievementCardState.Unknown(MimirAchievementCopy.NoCredentials), disconnected.pageUnknownState())

        gamesAnswer = { RaUserGamesOutcome.Unavailable }
        loader().load()
        assertFalse("an offline read ends the checking state", hub().checking)
        assertEquals("and the page says nothing is checked yet, never still checking", MimirAchievementCardState.Unknown(MimirAchievementCopy.NothingCheckedYet), hub().pageUnknownState())
    }

    @Test
    fun `an empty ledger publishes nothing and leaves the page checking`() = runTest {
        val loader = loader()
        assertFalse(loader.hydrate())
        assertTrue(hub().checking)
        assertEquals(0, gameCalls)
    }

    @Test
    fun `the saved answer is never published over a live one`() = runTest {
        seedLedger()
        val loader = loader()
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        val live = CompanionActivityAchievements.snapshots.value

        val stale = defaultGames.map { it.copy(earned = 0) }.let { games ->
            matchActivityBulk(games, library, platforms::get, emptyMap(), "bulk-player", 1L)
        }
        assertFalse(CompanionActivityAchievements.publishHydrated("bulk-player", stale, 1, null))
        assertEquals(live, CompanionActivityAchievements.snapshots.value)
        assertEquals(1500, CompanionActivityAchievements.points.value?.points)
    }

    @Test
    fun `a saved answer read across an account change publishes nothing`() = runTest {
        seedLedger()
        val gate = CompletableDeferred<Unit>()
        val loader = loader(library = { gate.await(); library })
        val running = async { loader.hydrate() }
        runCurrent()
        switchTo("someone-else", loader)
        gate.complete(Unit)

        assertFalse(running.await())
        assertTrue(CompanionActivityAchievements.snapshots.value.isEmpty())
    }

    @Test
    fun `the first live answer drops a saved row that RetroAchievements no longer lists`() = runTest {
        seedLedger()
        val loader = loader()
        assertTrue(loader.hydrate())
        assertEquals(3, CompanionActivityAchievements.snapshots.value.size)

        gamesAnswer = { RaUserGamesOutcome.Read(defaultGames.take(2), 2) }
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        assertEquals(listOf("local-metroid", "local-sonic"), CompanionActivityAchievements.snapshots.value.map { it.gameId }.sorted())
    }

    @Test
    fun `account A then B then A brings A back instantly and B never sees A's data`() = runTest {
        val loader = loader()
        signedIn = "alice"
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        assertEquals(3, hub().snapshots.size)
        assertEquals(1, gameCalls)

        // B signs in: nothing of A's is on the page or reachable, and B has nothing saved.
        switchTo("bob", loader)
        assertTrue(CompanionActivityAchievements.snapshots.value.isEmpty())
        assertFalse(loader.hydrate())
        assertTrue("B starts from the checking state, not from A's counts", hub("bob").checking)
        gamesAnswer = { RaUserGamesOutcome.Read(listOf(defaultGames[1].copy(earned = 9)), 1) }
        pointsAnswer = { RaPointsOutcome.Read(42) }
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        assertEquals(2, gameCalls)
        assertEquals(listOf(9), hub("bob").snapshots.map { it.unlocked })
        assertTrue(CompanionActivityAchievements.snapshots.value.all { it.account == "bob" })
        assertEquals(42, hub("bob").totalPoints)

        // Back to A: its counts return from the ledger with no request, and none of B's is there.
        switchTo("alice", loader)
        assertTrue(loader.hydrate())
        assertEquals("instant: no third request", 2, gameCalls)
        val back = hub("alice")
        assertEquals(3, back.snapshots.size)
        assertTrue(back.snapshots.all { it.account == "alice" })
        assertEquals(16, back.unlocked)
        assertEquals(1500, back.totalPoints)
        assertEquals(listOf(1), back.recent.map { it.achievementId })
        assertEquals("B's rows are intact on disk", 1, ledger.readBulk("bob")!!.games.size)
        assertEquals(42, ledger.readBulk("bob")!!.points)
    }

    @Test
    fun `the checking flag follows the settled account through the hub view flow`() = runTest(UnconfinedTestDispatcher()) {
        val settled = MutableStateFlow<String?>(null)
        val flow = companionActivityAchievementHubViewFlow(
            snapshots = MutableStateFlow<List<CompanionActivityAchievementSnapshot>>(emptyList()),
            connectedServicesOn = MutableStateFlow(true),
            account = MutableStateFlow<String?>("p"),
            validation = MutableStateFlow(RaKeyState.ACCEPTED),
            settledAccount = settled,
        )
        val seen = mutableListOf<Boolean>()
        val job = launch { flow.collect { seen += it.checking } }
        settled.value = "p"
        job.cancel()
        assertEquals(listOf(true, false), seen)
    }

    // The saved answer kept on a card --------------------------------------------------------------------------------------------------

    @Test
    fun `a fresh read that could not reach RetroAchievements keeps a saved answer, anything else replaces it`() {
        assertTrue(retroAchievementsKeepsSavedAnswer(retroAchievementsUnreachableState()))
        assertTrue(retroAchievementsKeepsSavedAnswer(MimirAchievementLaneState.Unresolved(MimirAchievementUnresolvedReason.PROGRESS_UNAVAILABLE)))
        assertFalse(retroAchievementsKeepsSavedAnswer(retroAchievementsRejectedState()))
        assertFalse(retroAchievementsKeepsSavedAnswer(retroAchievementsCheckingState()))
        assertFalse(retroAchievementsKeepsSavedAnswer(MimirAchievementLaneState.NoSet(MimirAchievementNoSetReason.NOT_MATCHED)))
        assertFalse(retroAchievementsKeepsSavedAnswer(MimirAchievementLaneState.Loaded(detailProgress(7))))
    }

    // Resolution ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the verdicts round-trip through the ledger, and a saved miss expires but a saved match does not`() {
        var clock = 1_000_000L
        val cache = RetroAchievementsVerdictCache(clock = { clock })
        cache.put("a|1", RetroAchievementsVerdict.Matched(77))
        cache.put("b|1", RetroAchievementsVerdict.NotMatched)
        cache.put("c|1", RetroAchievementsVerdict.RecognizedNoSet)
        val rows = cache.toLedger()
        assertEquals(setOf("a|1", "b|1", "c|1"), rows.map { it.fileKey }.toSet())
        assertEquals(77, rows.first { it.fileKey == "a|1" }.raGameId)

        val restarted = RetroAchievementsVerdictCache(clock = { clock })
        assertEquals(3, restarted.restoreFrom(rows))
        assertEquals(RetroAchievementsVerdict.Matched(77), restarted.get("a|1"))
        assertEquals(RetroAchievementsVerdict.NotMatched, restarted.get("b|1"))

        // A week later the saved misses are asked about again (a set may have appeared); the match stands.
        clock += RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS + 1L
        val later = RetroAchievementsVerdictCache(clock = { clock })
        assertEquals(1, later.restoreFrom(rows))
        assertEquals(RetroAchievementsVerdict.Matched(77), later.get("a|1"))
        assertNull(later.get("b|1"))
        assertNull(later.get("c|1"))

        // What this process learned is newer than what was saved, and a match without its id is no match.
        val fresh = RetroAchievementsVerdictCache(clock = { clock })
        fresh.put("a|1", RetroAchievementsVerdict.NotMatched)
        fresh.restoreFrom(rows + RetroAchievementsLedgerVerdict("d|1", RetroAchievementsLedgerVerdictKind.MATCHED, null, clock))
        assertEquals(RetroAchievementsVerdict.NotMatched, fresh.get("a|1"))
        assertNull(fresh.get("d|1"))
    }

    @Test
    fun `a library link the lane learned is reported as a change once and restored for the account`() {
        val book = RetroAchievementsLaneBook()
        assertTrue(book.noteMatchedRaId(5, "local-5"))
        assertFalse("the same link again is not a change", book.noteMatchedRaId(5, "local-5"))
        assertTrue(book.noteMatchedRaId(5, "local-5b"))
        book.clearKnownLocal()
        assertTrue(book.knownLocalByRaId().isEmpty())
        book.noteMatchedRaId(6, "newer")
        book.restoreKnownLocal(mapOf(5 to "saved-5", 6 to "saved-6"))
        assertEquals(mapOf(5 to "saved-5", 6 to "newer"), book.knownLocalByRaId())
    }

    // Per-game detail ----------------------------------------------------------------------------------------------------------------

    private class DetailFixture(val ledger: RetroAchievementsLedger?) {
        val fetched = mutableListOf<Int>()
        val sink = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap())
        val book = RetroAchievementsLaneBook()
        var account: String? = "me"
        var answer: (Int) -> RaProgressOutcome = { RaProgressOutcome.Read(detailProgress(it, earned = 3)) }
        var gate: CompletableDeferred<Unit>? = null
        val loader = ActivityCardDetailLoader(
            scope = CoroutineScope(Dispatchers.Unconfined),
            book = book,
            account = { account },
            fetch = { id -> fetched += id; gate?.await(); answer(id) },
            sink = sink,
            workContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
            ledger = ledger,
        )
    }

    @Test
    fun `a game the memory cache lacks starts from its saved answer, then the fresh read replaces it and is saved`() {
        ledger.saveDetail("me", detailProgress(7, earned = 1), nowMillis = 10L)
        val f = DetailFixture(ledger)
        f.gate = CompletableDeferred()

        f.loader.request(7)

        // The fresh read is still under way, and the card already has its badges, flags and dates.
        val shown = f.sink.value[7] as ActivityCardDetail.Loaded
        assertEquals(1, shown.progress.unlockedCount)
        assertEquals("Nintendo 64", shown.progress.consoleName)
        assertEquals("2026-01-01 10:00:00", shown.progress.achievements.first { it.unlocked }.earnedAt)

        f.gate!!.complete(Unit)
        assertEquals(3, (f.sink.value[7] as ActivityCardDetail.Loaded).progress.unlockedCount)
        assertEquals("the fresh answer is what is saved now", 3, ledger.readDetail("me", 7)!!.progress.unlockedCount)
    }

    @Test
    fun `a refresh that fails keeps the saved answer on the card`() {
        ledger.saveDetail("me", detailProgress(7, earned = 2), nowMillis = 10L)
        val f = DetailFixture(ledger)
        f.answer = { RaProgressOutcome.Unavailable }

        f.loader.request(7)

        assertEquals(2, (f.sink.value[7] as ActivityCardDetail.Loaded).progress.unlockedCount)
        assertEquals(2, ledger.readDetail("me", 7)!!.progress.unlockedCount)
    }

    @Test
    fun `an evicted game inside its window starts from its saved answer and asks nothing`() {
        val f = DetailFixture(ledger)
        (1..9).forEach { f.loader.request(it) }
        assertNull("game 1's answer left the memory cache", f.sink.value[1])
        f.loader.request(1)
        assertEquals("the ledger has it, so the card is not bare", 3, (f.sink.value[1] as ActivityCardDetail.Loaded).progress.unlockedCount)
        assertEquals("and the network was not asked again", 9, f.fetched.size)
    }

    @Test
    fun `a tap on a failed card reads once outside the window and the answer is saved`() {
        val f = DetailFixture(ledger)
        f.answer = { RaProgressOutcome.Unavailable }
        f.loader.request(7)
        assertEquals(ActivityCardDetail.Unavailable, f.sink.value[7])
        assertNull(ledger.readDetail("me", 7))
        f.answer = { RaProgressOutcome.Read(detailProgress(it, earned = 3)) }
        f.loader.retry(7)
        assertEquals(listOf(7, 7), f.fetched)
        assertEquals(3, (f.sink.value[7] as ActivityCardDetail.Loaded).progress.unlockedCount)
        assertEquals(3, ledger.readDetail("me", 7)!!.progress.unlockedCount)
    }

    @Test
    fun `another account never sees a saved answer`() {
        ledger.saveDetail("alice", detailProgress(7, earned = 2), nowMillis = 10L)
        val f = DetailFixture(ledger)
        f.account = "bob"
        f.gate = CompletableDeferred()

        f.loader.request(7)

        assertEquals(ActivityCardDetail.Loading, f.sink.value[7])
        f.gate!!.complete(Unit)
        assertEquals(3, ledger.readDetail("bob", 7)!!.progress.unlockedCount)
        assertEquals("alice's is untouched", 2, ledger.readDetail("alice", 7)!!.progress.unlockedCount)
    }

    @Test
    fun `a probe's answer seeds the cache and is saved`() {
        val f = DetailFixture(ledger)
        f.loader.seed(detailProgress(8, earned = 2), f.book.accountEpoch())
        assertEquals(2, ledger.readDetail("me", 8)!!.progress.unlockedCount)
        assertEquals(2, (f.sink.value[8] as ActivityCardDetail.Loaded).progress.unlockedCount)
    }

    @Test
    fun `without a ledger the loader behaves as it always did`() {
        val f = DetailFixture(null)
        f.gate = CompletableDeferred()
        f.loader.request(7)
        assertEquals(ActivityCardDetail.Loading, f.sink.value[7])
        f.gate!!.complete(Unit)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
    }

    // Art ----------------------------------------------------------------------------------------------------------------------------

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    private fun iconDir() = File(temp.root, "ra_icons")

    @Test
    fun `a cold start finds the badge and the game icon on disk and makes no request`() = runBlocking {
        val first = ActivityIconStore(iconDir(), { _, target -> target.parentFile?.mkdirs(); target.writeBytes(png); true })
        first.ensure("/Badge/00123.png")
        first.ensure("/Badge/00123_lock.png")
        first.ensure("/Images/000009.png")

        // A new process: a new store over the same folder, whose fetch must never be called.
        val fetches = mutableListOf<String>()
        val cold = ActivityIconStore(iconDir(), { path, _ -> fetches += path; false })
        val badge = cold.acquire("/Badge/00123.png")
        val lock = cold.acquire("/Badge/00123_lock.png")
        assertNotNull(badge)
        assertNotNull(lock)
        assertEquals("badge_00123.png", badge!!.file.name)
        badge.release()
        lock!!.release()

        // The art resolver reaches the same disk first: the game's icon comes back with no fetch.
        val resolver = LibraryActivityAchievementArtResolver(heroUriFor = { null }, icons = cold)
        val sources = resolver.sources(
            CompanionActivityAchievementSnapshot(gameId = "ra:9", account = "p", unlocked = 1, total = 2, iconPath = "/Images/000009.png"),
        )
        assertEquals("000009.png", sources.iconFile!!.name)
        sources.release()
        assertEquals("not one fetch", emptyList<String>(), fetches)
    }

    @Test
    fun `the byte budget holds several full sets and the order of use survives a restart`() = runBlocking {
        assertEquals(192L * 1024L * 1024L, ACTIVITY_ICON_CACHE_MAX_BYTES)
        assertTrue(ACTIVITY_ICON_CACHE_MAX_FILES >= 10_000)

        var t = 1_000L
        val store = ActivityIconStore(iconDir(), { _, target -> target.parentFile?.mkdirs(); target.writeBytes(png); true }, clock = { t++ })
        // Two full 500-badge sets (earned art and locked art each) and a 500-game set of icons: 1,500 files, all kept.
        (1..500).forEach { store.ensure("/Badge/%05d.png".format(it)) }
        (1..500).forEach { store.ensure("/Badge/%05d_lock.png".format(it)) }
        (1..500).forEach { store.ensure("/Images/%06d.png".format(it)) }
        assertEquals(1_500, iconDir().listFiles()!!.size)

        // After a restart the least recently used still goes first: a small bound, the oldest files by modified time are the ones that leave.
        val small = ActivityIconStore(iconDir(), { _, target -> target.parentFile?.mkdirs(); target.writeBytes(png); true }, clock = { t++ }, maxFiles = 1_500)
        small.ensure("/Badge/00001.png") // the oldest is read, so it is the youngest now
        small.ensure("/Images/000999.png") // one more file than the bound allows
        val names = iconDir().listFiles()!!.map { it.name }.toSet()
        assertEquals(1_500, names.size)
        assertTrue("the one just read stays", "badge_00001.png" in names)
        assertTrue("the new one stays", "000999.png" in names)
        assertFalse("the next oldest went", "badge_00002.png" in names)
    }

    // Backup, restore and move -----------------------------------------------------------------------------------------------------

    @Test
    fun `a backup carries the signed-in account's counts and nothing for anyone else`() {
        seedLedger("Alice")
        ledger.saveBulk("Bob", defaultGames.take(1), 5, null, nowMillis = 1L)

        val exported = achievementsForBackup(ledger, "Alice")
        assertEquals(1, exported.size)
        assertEquals("Alice", exported.single().username)
        assertEquals(1500, exported.single().points)
        assertEquals(listOf(1, 2, 3), exported.single().games.map { it.raGameId })
        assertEquals(12, exported.single().games.first().earned)
        assertEquals("/Images/000001.png", exported.single().games.first().iconPath)

        assertEquals(emptyList<Any>(), achievementsForBackup(ledger, null))
        assertEquals(emptyList<Any>(), achievementsForBackup(ledger, "  "))
        assertEquals("an account with no saved progress carries nothing", emptyList<Any>(), achievementsForBackup(ledger, "Carol"))
    }

    @Test
    fun `a move to another device restores the counts additively and importing twice changes nothing`() {
        seedLedger("Alice")
        val bundleText = MigrationBundleCodec.encode(MigrationBundleV1(achievements = achievementsForBackup(ledger, "Alice")))
        assertFalse("a backup never carries a key", bundleText.contains("apiKey", ignoreCase = true))

        // The other device: a fresh ledger folder, the bundle parsed from the file.
        val other = RetroAchievementsLedger(File(temp.root, "other_device"))
        val parsed = MigrationBundleCodec.parse(bundleText).getOrThrow()
        assertEquals(3, restoreAchievementsFromBackup(other, parsed.achievements, nowMillis = 77L))
        val restored = other.readBulk("alice")!! // RA's names do not care about case
        assertEquals(1500, restored.points)
        assertEquals(defaultGames, restored.games)
        assertNull("restore invents no recent unlocks", restored.recent)

        // Again: the device already holds this account, so nothing is taken and nothing is rewritten.
        val file = File(File(File(temp.root, "other_device"), RetroAchievementsLedger.accountKey("Alice")!!), "bulk.json")
        val bytes = file.readBytes()
        assertEquals(0, restoreAchievementsFromBackup(other, parsed.achievements, nowMillis = 99L))
        assertTrue(bytes.contentEquals(file.readBytes()))

        // Another account's rows were never created.
        assertNull(other.readBulk("bob"))
    }

    @Test
    fun `a restore never overwrites what the device read itself`() {
        val device = RetroAchievementsLedger(File(temp.root, "device"))
        device.saveBulk("Alice", listOf(defaultGames[0].copy(earned = 30)), 9000, null, nowMillis = 5L)
        val old = achievementsForBackup(ledger.also { seedLedger("Alice") }, "Alice")

        assertEquals(0, restoreAchievementsFromBackup(device, old, nowMillis = 6L))
        assertEquals(30, device.readBulk("Alice")!!.games.single().earned)
        assertEquals(9000, device.readBulk("Alice")!!.points)
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, device.saveBulk("Alice", listOf(defaultGames[0].copy(earned = 30)), 9000, null, nowMillis = 7L))
    }

    // Hydration is per field ------------------------------------------------------------------------------------------------------

    @Test
    fun `live games that landed first do not keep the saved points and recent unlocks off the page when those reads failed`() = runTest {
        seedLedger()
        pointsAnswer = { RaPointsOutcome.Unavailable }
        recentAnswer = { RaRecentOutcome.Unavailable }
        val loader = loader()

        assertEquals(ActivityBulkResult.LOADED, loader.load())
        val live = CompanionActivityAchievements.snapshots.value
        assertNull("the live read brought no points", CompanionActivityAchievements.points.value)
        assertNull("and no recent unlocks", CompanionActivityAchievements.recent.value)

        assertTrue("the saved copy still fills the fields the live read did not bring", loader.hydrate())
        assertEquals(1500, CompanionActivityAchievements.points.value?.points)
        assertEquals(listOf(1), CompanionActivityAchievements.recent.value?.unlocks?.map { it.achievementId })
        assertEquals("the live games are untouched", live, CompanionActivityAchievements.snapshots.value)
    }

    @Test
    fun `a field the live read did bring is never replaced by the saved copy, a field it did not bring is filled`() = runTest {
        seedLedger()
        pointsAnswer = { RaPointsOutcome.Read(2000) }
        recentAnswer = { RaRecentOutcome.Unavailable }
        val loader = loader()
        assertEquals(ActivityBulkResult.LOADED, loader.load())

        loader.hydrate()
        assertEquals("live points stand", 2000, CompanionActivityAchievements.points.value?.points)
        assertEquals("saved recent unlocks fill the strip", 1, CompanionActivityAchievements.recent.value?.unlocks?.size)

        CompanionActivityAchievements.resetForTest()
        pointsAnswer = { RaPointsOutcome.Unavailable }
        recentAnswer = { RaRecentOutcome.Read(listOf(unlock(5), unlock(6))) }
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        loader.hydrate()
        assertEquals("saved points fill in", 2000, CompanionActivityAchievements.points.value?.points) // the ledger kept the last known points
        assertEquals("live recent unlocks stand", listOf(5, 6), CompanionActivityAchievements.recent.value?.unlocks?.map { it.achievementId })
    }

    @Test
    fun `the saved copy publishes nothing once every field has a live answer`() = runTest {
        seedLedger()
        val loader = loader()
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        assertFalse(loader.hydrate())
    }

    @Test
    fun `a switch forgets what had landed live, so the saved copy fills the account's page again`() = runTest {
        seedLedger()
        val loader = loader()
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        switchTo("someone-else", loader)
        switchTo("bulk-player", loader)
        assertTrue(CompanionActivityAchievements.snapshots.value.isEmpty())

        assertTrue(loader.hydrate())
        assertEquals(3, CompanionActivityAchievements.snapshots.value.size)
        assertEquals(1500, CompanionActivityAchievements.points.value?.points)
    }

    // A saved "no set" expires when it is used -----------------------------------------------------------------------------------

    @Test
    fun `a restored miss is honoured at six days twenty-three hours and asked about again once seven days have passed`() {
        val hour = 60L * 60L * 1000L
        var clock = 10_000_000_000L
        val cache = RetroAchievementsVerdictCache(clock = { clock })
        val learned = clock - (RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS - hour)
        val rows = listOf(
            RetroAchievementsLedgerVerdict("miss|1", RetroAchievementsLedgerVerdictKind.NOT_MATCHED, null, learned),
            RetroAchievementsLedgerVerdict("noset|1", RetroAchievementsLedgerVerdictKind.RECOGNIZED_NO_SET, null, learned),
            RetroAchievementsLedgerVerdict("hit|1", RetroAchievementsLedgerVerdictKind.MATCHED, 77, learned),
        )
        assertEquals(3, cache.restoreFrom(rows))
        assertEquals(RetroAchievementsVerdict.NotMatched, cache.get("miss|1"))
        assertEquals(RetroAchievementsVerdict.RecognizedNoSet, cache.get("noset|1"))

        // The launcher keeps running: one hour later the miss is seven days old and is no longer used (the game is resolved afresh).
        clock += hour
        assertNull(cache.get("miss|1"))
        assertNull(cache.get("noset|1"))
        assertEquals("a match never expires", RetroAchievementsVerdict.Matched(77), cache.get("hit|1"))
        assertEquals("and the expired rows are gone for good", listOf("hit|1"), cache.toLedger().map { it.fileKey })

        // Asked afresh, the answer is learned again with the new time.
        cache.put("miss|1", RetroAchievementsVerdict.NotMatched)
        assertEquals(RetroAchievementsVerdict.NotMatched, cache.get("miss|1"))
    }

    @Test
    fun `a miss learned in this process expires the same way`() {
        var clock = 5_000_000_000L
        val cache = RetroAchievementsVerdictCache(clock = { clock })
        cache.put("miss|1", RetroAchievementsVerdict.NotMatched)
        clock += RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS - 1L
        assertEquals(RetroAchievementsVerdict.NotMatched, cache.get("miss|1"))
        clock += 1L
        assertNull(cache.get("miss|1"))
    }

    // Restore is one atomic step ---------------------------------------------------------------------------------------------------

    @Test
    fun `a live save racing a restore always leaves the live snapshot`() {
        seedLedger("Alice")
        val backup = achievementsForBackup(ledger, "Alice")
        repeat(40) { round ->
            val device = RetroAchievementsLedger(File(temp.root, "race$round"))
            val live = listOf(defaultGames[0].copy(earned = 40))
            val start = CountDownLatch(1)
            val saver = thread { start.await(); device.saveBulk("Alice", live, 9000, null, nowMillis = 2L) }
            val restorer = thread { start.await(); restoreAchievementsFromBackup(device, backup, nowMillis = 1L) }
            start.countDown()
            saver.join(10_000)
            restorer.join(10_000)
            assertEquals("round $round", live, device.readBulk("Alice")!!.games)
            assertEquals(9000, device.readBulk("Alice")!!.points)
        }
    }

    @Test
    fun `a restore of an oversized backup is trimmed or refused, and an earlier snapshot stays whole`() {
        val device = RetroAchievementsLedger(File(temp.root, "device_big"))
        val long = "y".repeat(3000)
        val big = (1..RetroAchievementsLedger.MAX_BULK_GAMES + 5).map {
            com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementGame(raGameId = it, title = long, consoleName = long, earned = 1, total = 2)
        }
        val account = com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementAccount("Alice", 5, big)

        // More games than the ledger will keep: refused outright, nothing written.
        assertEquals(0, restoreAchievementsFromBackup(device, listOf(account), nowMillis = 1L))
        assertNull(device.readBulk("Alice"))

        // Within the bound the long text is cut to its caps.
        val fits = account.copy(games = big.take(50))
        assertEquals(50, restoreAchievementsFromBackup(device, listOf(fits), nowMillis = 2L))
        val kept = device.readBulk("Alice")!!.games
        assertEquals(50, kept.size)
        assertTrue(kept.all { it.title.length == RetroAchievementsLedger.MAX_TITLE_CHARS && it.consoleName.length == RetroAchievementsLedger.MAX_CONSOLE_CHARS })

        // And a later oversized one leaves that snapshot exactly as it was.
        val bytes = File(File(File(temp.root, "device_big"), RetroAchievementsLedger.accountKey("Alice")!!), "bulk.json").readBytes()
        assertEquals(0, restoreAchievementsFromBackup(device, listOf(account), nowMillis = 3L))
        assertTrue(bytes.contentEquals(File(File(File(temp.root, "device_big"), RetroAchievementsLedger.accountKey("Alice")!!), "bulk.json").readBytes()))
    }

    private companion object {
        fun detailProgress(gameId: Int, earned: Int = 2, count: Int = 4) = RetroAchievementsProgress(
            gameId = gameId,
            title = "Game $gameId",
            achievements = (1..count).map {
                RetroAchievementsAchievement(
                    id = gameId * 100 + it,
                    title = "A$it",
                    description = "d$it",
                    points = it * 5,
                    unlocked = it <= earned,
                    unlockedHardcore = false,
                    badgeName = "%05d".format(it),
                    earnedAt = if (it == 1 && earned >= 1) "2026-01-01 10:00:00" else if (it <= earned) "2026-01-0$it 10:00:00" else null,
                )
            },
            imageTitlePath = "/Images/%06d.png".format(gameId),
            consoleName = "Nintendo 64",
        )
    }
}
