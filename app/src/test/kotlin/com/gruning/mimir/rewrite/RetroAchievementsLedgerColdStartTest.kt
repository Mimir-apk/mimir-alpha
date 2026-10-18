package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerResolution
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerVerdict
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerVerdictKind
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsRecentUnlock
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.RaKeyState
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The REAL achievements lane ([MimirAchievementsState]) over a real ledger folder: a cold start with a saved ledger puts the counts on the Bottom's page before a
 * single request, an account's rows return instantly after a switch and never reach another account, signing out keeps them, and the lane's resolution (the
 * file verdicts and library links) is saved per account and merged, never erased. Only the network sources are replaced, through [MimirAchievementsSeams]; the
 * scope runs unconfined so everything happens synchronously and no credential or connection is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class RetroAchievementsLedgerColdStartTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = MutableStateFlow<String?>("alice")
    private val keyState = MutableStateFlow(RaKeyState.ACCEPTED)
    private val optedIn = MutableStateFlow(true)
    private val sessions = MutableStateFlow<String?>(null)
    private var gameCalls = 0
    private var gate: CompletableDeferred<Unit>? = null
    private var liveGames: List<RetroAchievementsUserGame> = listOf(RetroAchievementsUserGame(1, "Game 9", SNES, 5, 10))
    private val logs = mutableListOf<String>()
    private val states = mutableListOf<MimirAchievementsState>()
    private var nowMs = 10_000_000L

    private val ledger by lazy { RetroAchievementsLedger(File(temp.root, "ra_ledger")) }

    private val aliceSaved = listOf(
        RetroAchievementsUserGame(1, "Game 9", SNES, 4, 10),
        RetroAchievementsUserGame(2, "Another Game", SNES, 7, 12),
    )
    private val bobSaved = listOf(RetroAchievementsUserGame(3, "Bob's Game", SNES, 1, 5))

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
            sessionKeys = sessions,
            scopeContext = Dispatchers.Unconfined,
            bulkGames = {
                gameCalls += 1
                gate?.await()
                RaUserGamesOutcome.Read(liveGames, liveGames.size)
            },
            bulkPoints = { RaPointsOutcome.Read(777) },
            bulkRecent = { RaRecentOutcome.Read(emptyList()) },
            bulkLibrary = { listOf(LocalGameKey("x", "snes", "Game 9")) },
            iconClear = {},
            bulkContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
            clock = { nowMs },
            ledger = ledger,
            ledgerFlushDelayMillis = 0L,
            bulkLog = { logs += it },
        ),
    ).also { states += it }

    private fun held() = CompanionActivityAchievements.snapshots.value

    // The Bottom's page -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a cold start with a saved ledger shows the counts and points before the first request, then the live read refreshes them`() {
        ledger.saveBulk("alice", aliceSaved, 321, null, nowMillis = 5L)

        val state = state()

        assertEquals("constructing the lane asks nothing", 0, gameCalls)
        assertEquals(2, held().size)
        assertTrue(held().all { it.account == "alice" })
        assertEquals("x", held().first { it.raGameId == 1 }.gameId) // matched to the library from the saved games
        assertEquals(4 + 7, held().sumOf { it.unlocked })
        assertEquals(321, CompanionActivityAchievements.points.value?.points)
        assertEquals("alice", CompanionActivityAchievements.settledAccount.value)

        // The first frames pass and the live read begins: it refreshes, and drops the saved row RetroAchievements no longer lists.
        state.startActivityBulk()
        assertEquals(1, gameCalls)
        assertEquals(listOf("x"), held().map { it.gameId })
        assertEquals(5, held().single().unlocked)
        assertEquals(777, CompanionActivityAchievements.points.value?.points)
        assertEquals(listOf("bulk outcome=complete games=1 persisted=1"), logs)
        assertEquals(1, ledger.readBulk("alice")!!.games.size)
    }

    @Test
    fun `live games that landed before the saved copy was read do not keep the saved points and recent unlocks off the page`() {
        val unlock = RetroAchievementsRecentUnlock(7, "Saved unlock", "d", 5, "00007", 1, "Game 9", "SNES", 1_700_000_000_000L, hardcore = false)
        ledger.saveBulk("alice", aliceSaved, 321, listOf(unlock), nowMillis = 5L)
        // A live read had already put the games on the page, but its points and recent-unlocks requests failed.
        val live = matchActivityBulk(liveGames, emptyList(), { null }, emptyMap(), "alice", 9L)
        CompanionActivityAchievements.publishBulk("alice", live, null)
        assertNull(CompanionActivityAchievements.points.value)

        state()

        assertEquals("the live rows were not replaced by the saved ones", live, held())
        assertEquals(321, CompanionActivityAchievements.points.value?.points)
        assertEquals(listOf(7), CompanionActivityAchievements.recent.value?.unlocks?.map { it.achievementId })
    }

    @Test
    fun `a saved miss that crosses seven days while the launcher runs is no longer used`() {
        val hour = 60L * 60L * 1000L
        nowMs = 2_000_000_000L
        val learned = nowMs - (RETRO_ACHIEVEMENTS_SAVED_NEGATIVE_VERDICT_TTL_MS - hour)
        ledger.saveResolution(
            "alice",
            RetroAchievementsLedgerResolution(
                verdicts = listOf(RetroAchievementsLedgerVerdict("miss|1", RetroAchievementsLedgerVerdictKind.NOT_MATCHED, null, learned)),
            ),
        )
        val book = state().verdictBook

        assertEquals("restored at six days twenty-three hours: honoured", RetroAchievementsVerdict.NotMatched, book.get("miss|1"))
        nowMs += hour
        assertNull("seven days later it is asked about again", book.get("miss|1"))
    }

    @Test
    fun `a cold start with nothing saved shows nothing as known and asks the live read`() {
        state().startActivityBulk()

        assertEquals(1, gameCalls)
        assertEquals(listOf("bulk outcome=complete games=1 persisted=1"), logs)
        assertEquals(1, held().size)
        assertEquals("alice", CompanionActivityAchievements.settledAccount.value)
    }

    @Test
    fun `a cold start with the network down keeps the saved counts on the page`() {
        ledger.saveBulk("alice", aliceSaved, 321, null, nowMillis = 5L)
        val offline = MimirAchievementsState(
            RuntimeEnvironment.getApplication(),
            MimirAchievementsSeams(
                account = account,
                keyState = keyState,
                optedIn = optedIn,
                sessionKeys = sessions,
                scopeContext = Dispatchers.Unconfined,
                bulkGames = { gameCalls += 1; RaUserGamesOutcome.Unavailable },
                bulkPoints = { RaPointsOutcome.Unavailable },
                bulkLibrary = { emptyList() },
                iconClear = {},
                bulkContext = EmptyCoroutineContext,
                publishContext = EmptyCoroutineContext,
                ledger = ledger,
                ledgerFlushDelayMillis = 0L,
                bulkLog = { logs += it },
            ),
        ).also { states += it }

        offline.startActivityBulk()

        assertEquals(1, gameCalls)
        assertEquals("what was saved is still shown", 2, held().size)
        assertEquals(321, CompanionActivityAchievements.points.value?.points)
        assertEquals(listOf("bulk outcome=unavailable games=0 persisted=2"), logs)
        assertEquals("and still saved", 2, ledger.readBulk("alice")!!.games.size)
    }

    @Test
    fun `an account's counts come back instantly after a switch and nothing crosses accounts`() {
        ledger.saveBulk("alice", aliceSaved, 321, null, nowMillis = 5L)
        ledger.saveBulk("bob", bobSaved, 55, null, nowMillis = 5L)
        state()
        assertEquals(2, held().size)

        // B signs in; its live read is held up, so everything on the page is what the ledger gave.
        gate = CompletableDeferred()
        account.value = "bob"
        assertEquals(1, gameCalls)
        assertEquals(listOf(3), held().map { it.raGameId })
        assertTrue("none of A's is on B's page", held().all { it.account == "bob" })
        assertEquals(55, CompanionActivityAchievements.points.value?.points)

        // Back to A with the network still down: its counts are there at once, none of B's.
        account.value = "alice"
        assertEquals(2, gameCalls)
        assertEquals(2, held().size)
        assertTrue("none of B's is on A's page", held().all { it.account == "alice" })
        assertEquals(321, CompanionActivityAchievements.points.value?.points)
        assertEquals(4 + 7, held().sumOf { it.unlocked })
        assertEquals("B's rows stayed on disk", 1, ledger.readBulk("bob")!!.games.size)
    }

    @Test
    fun `signing out keeps the rows, empties the page, and signing back in is instant`() {
        ledger.saveBulk("alice", aliceSaved, 321, null, nowMillis = 5L)
        state()
        assertEquals(2, held().size)

        account.value = null
        assertTrue(held().isEmpty())
        assertNull(CompanionActivityAchievements.points.value)
        assertEquals("the rows are kept", 2, ledger.readBulk("alice")!!.games.size)

        gate = CompletableDeferred()
        account.value = "alice"
        assertEquals(2, held().size)
        assertEquals(321, CompanionActivityAchievements.points.value?.points)
    }

    // The resolution -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the resolution is read in per account, saved when the account goes, and never used under another`() {
        ledger.saveResolution(
            "alice",
            RetroAchievementsLedgerResolution(
                verdicts = listOf(RetroAchievementsLedgerVerdict("rom-a|10|5", RetroAchievementsLedgerVerdictKind.MATCHED, 77, 10_000_000L)),
                localByRaGameId = mapOf(77 to "local-77"),
            ),
        )
        val state = state()

        // A game the ledger already knows is not hashed again: its verdict and library link are there before any probe.
        assertEquals(RetroAchievementsVerdict.Matched(77), state.verdictBook.get("rom-a|10|5"))
        assertEquals("local-77", state.laneBook.knownLocalByRaId()[77])

        // The lane learns two more things under alice, then bob signs in.
        state.verdictBook.put("rom-b|20|6", RetroAchievementsVerdict.NotMatched)
        state.laneBook.noteMatchedRaId(78, "local-78")
        account.value = "bob"

        assertNull("nothing alice learned is used under bob", state.verdictBook.get("rom-a|10|5"))
        assertTrue(state.laneBook.knownLocalByRaId().isEmpty())
        val saved = ledger.readResolution("alice")
        assertEquals(setOf("rom-a|10|5", "rom-b|20|6"), saved.verdicts.map { it.fileKey }.toSet())
        assertEquals(mapOf(77 to "local-77", 78 to "local-78"), saved.localByRaGameId)
        assertTrue("bob has learned nothing, so no file was made for him", ledger.readResolution("bob").isEmpty)

        account.value = "alice"
        assertEquals(RetroAchievementsVerdict.Matched(77), state.verdictBook.get("rom-a|10|5"))
        assertEquals(RetroAchievementsVerdict.NotMatched, state.verdictBook.get("rom-b|20|6"))
        assertEquals("local-78", state.laneBook.knownLocalByRaId()[78])
    }

    @Test
    fun `saving the resolution only adds to what the ledger holds`() {
        ledger.saveResolution(
            "alice",
            RetroAchievementsLedgerResolution(
                verdicts = listOf(RetroAchievementsLedgerVerdict("old|1|1", RetroAchievementsLedgerVerdictKind.MATCHED, 5, 1L)),
                localByRaGameId = mapOf(5 to "local-5"),
            ),
        )
        val state = state()
        // Whatever memory holds, a save merges: the ledger's older row is still there afterwards.
        state.verdictBook.clear()
        state.verdictBook.put("new|2|2", RetroAchievementsVerdict.Matched(6))
        account.value = null

        val saved = ledger.readResolution("alice")
        assertEquals(setOf("old|1|1", "new|2|2"), saved.verdicts.map { it.fileKey }.toSet())
        assertEquals("local-5", saved.localByRaGameId[5])
    }

    @Test
    fun `the lane builds its ledger from the app's files directory when none is given`() {
        val state = MimirAchievementsState(
            RuntimeEnvironment.getApplication(),
            MimirAchievementsSeams(
                account = account,
                keyState = keyState,
                optedIn = optedIn,
                sessionKeys = sessions,
                scopeContext = Dispatchers.Unconfined,
                bulkGames = { RaUserGamesOutcome.Read(liveGames, liveGames.size) },
                bulkPoints = { RaPointsOutcome.Read(1) },
                bulkRecent = { RaRecentOutcome.Read(emptyList()) },
                bulkLibrary = { emptyList() },
                iconClear = {},
                bulkContext = EmptyCoroutineContext,
                publishContext = EmptyCoroutineContext,
            ),
        ).also { states += it }
        state.startActivityBulk()
        val real = RetroAchievementsLedgers.forContext(RuntimeEnvironment.getApplication())
        assertNotNull(real.readBulk("alice"))
        assertFalse(real.hasAccount("someone-nobody-signed-in"))
    }

    private companion object {
        const val SNES = "Super Nintendo Entertainment System"
    }
}
