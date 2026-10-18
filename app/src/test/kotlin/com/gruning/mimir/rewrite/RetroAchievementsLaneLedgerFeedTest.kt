package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The REAL achievements lane ([MimirAchievementsState]) over a real ledger folder, with a bulk read that finds nothing (the Thor's logcat at two cold starts):
 * a per-game result the lane publishes reaches the ledger, and the next cold start shows it before a single request. Only the network sources and the probe's
 * file work are replaced; the lane's publish gate, the loader and the ledger are the production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class RetroAchievementsLaneLedgerFeedTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = MutableStateFlow<String?>("alice")
    private val keyState = MutableStateFlow(RaKeyState.ACCEPTED)
    private val optedIn = MutableStateFlow(true)
    private val sessions = MutableStateFlow<String?>(null)
    private val logs = mutableListOf<String>()
    private val states = mutableListOf<MimirAchievementsState>()
    private var gameCalls = 0

    private val ledger by lazy { RetroAchievementsLedger(File(temp.root, "ra_ledger")) }
    private val metroid = GameRow(gameId = GameId("local-metroid"), title = "Super Metroid", platformId = PlatformId("snes"), memberCount = 1, duplicateKind = null)

    @Before
    fun reset() {
        CompanionActivityAchievements.resetForTest()
        CompanionActivityCardDetails.resetForTest()
    }

    @After
    fun closeAll() = states.forEach(MimirAchievementsState::close)

    private fun loaded(unlocked: Int) = MimirAchievementLaneState.Loaded(
        RetroAchievementsProgress(
            gameId = 9,
            title = "Super Metroid",
            achievements = (1..10).map {
                RetroAchievementsAchievement(
                    id = it,
                    title = "A$it",
                    description = "a",
                    points = 5,
                    unlocked = it <= unlocked,
                    unlockedHardcore = false,
                    earnedAt = if (it <= unlocked) "2026-03-0$it 12:00:00" else null,
                )
            },
            imageIconPath = "/Images/000009.png",
            consoleName = "Super Nintendo Entertainment System",
        ),
    )

    private fun state(work: MimirAchievementProbeWork? = null): MimirAchievementsState = MimirAchievementsState(
        RuntimeEnvironment.getApplication(),
        MimirAchievementsSeams(
            account = account,
            keyState = keyState,
            optedIn = optedIn,
            sessionKeys = sessions,
            scopeContext = Dispatchers.Unconfined,
            probeWork = work,
            // The bulk read finds no games at all, as it did on the Thor, and the profile has points.
            bulkGames = { gameCalls += 1; RaUserGamesOutcome.Read(emptyList(), 0) },
            bulkPoints = { RaPointsOutcome.Read(640) },
            bulkRecent = { RaRecentOutcome.Read(emptyList()) },
            bulkLibrary = { listOf(LocalGameKey("local-metroid", "snes", "Super Metroid")) },
            iconClear = {},
            bulkContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
            ledger = ledger,
            ledgerFlushDelayMillis = 0L,
            bulkLog = { logs += it },
        ),
    ).also { states += it }

    @Test
    fun `a result the lane publishes reaches the ledger, and the next cold start shows it with no request while the empty bulk read changes nothing`() {
        val first = state { _, report -> report(loaded(unlocked = 3), null) }
        runBlocking { first.probe(metroid) }

        val saved = ledger.readBulk("alice")!!
        assertEquals(listOf(9), saved.games.map { it.gameId })
        assertEquals(3, saved.games.single().earned)
        assertEquals(10, saved.games.single().total)
        assertEquals("Super Nintendo Entertainment System", saved.games.single().consoleName)
        assertEquals("/Images/000009.png", saved.games.single().imageIconPath)

        // The next cold start: nothing held in memory, a new lane over the same folder. The ledger is on the page before the first request.
        CompanionActivityAchievements.resetForTest()
        val second = state()
        assertEquals("constructing the lane asked nothing", 0, gameCalls)
        val shown = CompanionActivityAchievements.snapshots.value.single()
        assertEquals("local-metroid", shown.gameId)
        assertEquals(3, shown.unlocked)
        assertEquals(10, shown.total)
        assertEquals(9, shown.raGameId)
        assertEquals("alice", CompanionActivityAchievements.settledAccount.value)

        // The live read then finds no games while the profile has points: it is not believed, so the page and the ledger keep the game.
        second.startActivityBulk()
        assertEquals(1, gameCalls)
        assertEquals(listOf("bulk outcome=suspect-empty games=0 persisted=1 why=points"), logs)
        assertEquals("the points the same read brought are on the page and in the ledger", 640, CompanionActivityAchievements.points.value?.points)
        assertEquals(640, ledger.readBulk("alice")!!.points)
        assertEquals(listOf(9), ledger.readBulk("alice")!!.games.map { it.gameId })
        assertEquals(3, CompanionActivityAchievements.snapshots.value.single().unlocked)
    }

    @Test
    fun `a result for a game with nothing earned is kept too, and a placeholder or a failed read writes nothing`() {
        val zero = state { _, report -> report(loaded(unlocked = 0), null) }
        runBlocking { zero.probe(metroid) }
        assertEquals(0, ledger.readBulk("alice")!!.games.single().earned)

        val folder = File(temp.root, "ra_ledger")
        val before = folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to it.readBytes().toList() }
        val unresolved = state { _, report -> report(MimirAchievementLaneState.Unresolved(MimirAchievementUnresolvedReason.UNREACHABLE), null) }
        runBlocking { unresolved.probe(metroid) }
        val after = folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to it.readBytes().toList() }
        assertTrue("an unresolved answer leaves the ledger as it was", before == after)
    }
}
