package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.RaKeyState
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Two-score RetroAchievements contract: the hardcore points are the main score and the softcore points stand beside them, never added; a game's progress keeps the earned
 * count (an achievement earned in both modes is counted once) and says how many of those were earned in hardcore; and RA's award for a game (beaten, completed, mastered)
 * reaches the page. The real loader runs against counting fakes and the real ledger on a temporary folder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsTwoScoresTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = "two-score-player"
    private var signedIn: String? = account
    private var now = 1_000_000L
    private var gameCalls = 0
    private var gamesAnswer: () -> RaUserGamesOutcome = { RaUserGamesOutcome.Read(games, games.size) }
    private var pointsAnswer: () -> RaPointsOutcome = { RaPointsOutcome.Read(points = 900, softcorePoints = 300) }
    private val book = RetroAchievementsLaneBook()
    private val ledger by lazy { RetroAchievementsLedger(File(temp.root, "ra_ledger")) }

    // Super Metroid: all 40 earned, all 40 in hardcore (the hardcore ones are INSIDE the 40). Sonic: all 30 earned, only 12 of them in hardcore.
    private val games = listOf(
        RetroAchievementsUserGame(1, "Super Metroid", "Super Nintendo Entertainment System", earned = 40, total = 40, earnedHardcore = 40, awardKind = RaAwardKind.MASTERED),
        RetroAchievementsUserGame(2, "Sonic the Hedgehog 2", "Mega Drive", earned = 30, total = 30, earnedHardcore = 12, awardKind = RaAwardKind.COMPLETED),
        RetroAchievementsUserGame(3, "An Obscure Game", "Game Boy", earned = 7, total = 10, earnedHardcore = 7, awardKind = RaAwardKind.BEATEN_HARDCORE),
        RetroAchievementsUserGame(4, "Another Obscure Game", "Game Boy", earned = 4, total = 20, earnedHardcore = 0, awardKind = RaAwardKind.BEATEN_SOFTCORE),
    )
    private val library = listOf(
        LocalGameKey("local-metroid", "snes", "Super Metroid (USA)"),
        LocalGameKey("local-sonic", "genesis", "Sonic The Hedgehog 2"),
    )
    private val platforms = mapOf("Super Nintendo Entertainment System" to "snes", "Mega Drive" to "genesis", "Game Boy" to "gb")

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    private fun loader(bulkLedger: RetroAchievementsLedger? = ledger) = ActivityBulkLoader(
        book = book,
        account = { signedIn },
        loadGames = { gameCalls += 1; gamesAnswer() },
        loadPoints = { pointsAnswer() },
        library = { library },
        platformIdFor = platforms::get,
        publish = { acct, snapshots, points -> CompanionActivityAchievements.publishBulk(acct, snapshots, points) },
        clock = { now },
        workContext = EmptyCoroutineContext,
        publishContext = EmptyCoroutineContext,
        publishPoints = { acct, points -> CompanionActivityAchievements.publishPoints(acct, points) },
        publishSoftcore = { acct, softcore, hydrated -> CompanionActivityAchievements.publishSoftcore(acct, softcore, hydrated) },
        ledger = bulkLedger,
        publishHydrated = { acct, snapshots, points, recent -> CompanionActivityAchievements.publishHydrated(acct, snapshots, points, recent) },
        markSettled = CompanionActivityAchievements::markSettled,
        log = {},
    )

    private fun hub() = companionActivityAchievementHubView(
        snapshots = CompanionActivityAchievements.snapshots.value,
        connectedServicesOn = true,
        account = signedIn,
        validation = RaKeyState.ACCEPTED,
        points = CompanionActivityAchievements.points.value,
        settledAccount = CompanionActivityAchievements.settledAccount.value,
    )

    private fun bulkText() = File(File(File(temp.root, "ra_ledger"), RetroAchievementsLedger.accountKey(account)!!), "bulk.json").readText()

    // ── the two scores ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the hardcore score is the main one and the softcore score stands beside it, never added`() = runTest {
        assertEquals(ActivityBulkResult.LOADED, loader().load())

        val view = hub()
        assertEquals("hardcore is the main score", 900, view.totalPoints)
        assertEquals("softcore stands beside it", 300, view.softcorePoints)
        assertEquals(CompanionActivityPoints(account, 900, 300), CompanionActivityAchievements.points.value)
        assertEquals(
            "81 of 100 earned across 4 games · 900 points · Softcore 300",
            activityAchievementsStripText(view.unlocked, view.total, view.snapshots.size, view.totalPoints, view.softcorePoints),
        )
        assertFalse("never 1,200", "1,200" in activityAchievementsStripText(view.unlocked, view.total, view.snapshots.size, view.totalPoints, view.softcorePoints))
    }

    @Test
    fun `the strip says each score in short words and shows a bare zero only when there is nothing else to say`() {
        assertEquals("16 of 80 earned across 3 games · 1,500 points", activityAchievementsStripText(16, 80, 3, 1500))
        assertEquals("16 of 80 earned across 3 games · 1,500 points · Softcore 1,234", activityAchievementsStripText(16, 80, 3, 1500, 1234))
        assertEquals("a softcore-only player is not told 0 points", "16 of 80 earned across 3 games · Softcore 250", activityAchievementsStripText(16, 80, 3, 0, 250))
        assertEquals("16 of 80 earned in 1 game · 0 points", activityAchievementsStripText(16, 80, 1, 0, 0))
        assertEquals("without a points read the line is the counts alone", "16 of 80 earned across 3 games", activityAchievementsStripText(16, 80, 3, null, 300))
        assertEquals("1,234,567", activityPointsText(1_234_567))
        assertEquals("987", activityPointsText(987))
    }

    @Test
    fun `the hub view carries the softcore score only for the connected account`() = runTest {
        loader().load()
        val other = companionActivityAchievementHubView(
            snapshots = CompanionActivityAchievements.snapshots.value,
            connectedServicesOn = true,
            account = "someone-else",
            validation = RaKeyState.ACCEPTED,
            points = CompanionActivityAchievements.points.value,
        )
        assertNull("another account's points are not this account's", other.totalPoints)
        assertEquals(0, other.softcorePoints)
        val off = companionActivityAchievementHubView(
            snapshots = CompanionActivityAchievements.snapshots.value,
            connectedServicesOn = false,
            account = account,
            validation = RaKeyState.ACCEPTED,
            points = CompanionActivityAchievements.points.value,
        )
        assertEquals(0, off.softcorePoints)
    }

    @Test
    fun `a new hardcore total never blinks the softcore score away, and a saved copy never goes over a live one`() {
        CompanionActivityAchievements.publishBulk(account, emptyList(), 900)
        CompanionActivityAchievements.publishSoftcore(account, 300)
        assertEquals(CompanionActivityPoints(account, 900, 300), CompanionActivityAchievements.points.value)

        CompanionActivityAchievements.publishBulk(account, emptyList(), 950)
        assertEquals("the softcore score is held across a hardcore update", CompanionActivityPoints(account, 950, 300), CompanionActivityAchievements.points.value)
        CompanionActivityAchievements.publishPoints(account, 960)
        assertEquals(CompanionActivityPoints(account, 960, 300), CompanionActivityAchievements.points.value)

        CompanionActivityAchievements.publishSoftcore(account, 10, hydrated = true)
        assertEquals("a saved score does not go over a live answer", 300, CompanionActivityAchievements.points.value?.softcore)
        CompanionActivityAchievements.publishSoftcore("someone-else", 5)
        assertEquals("another account's score lands nowhere", 300, CompanionActivityAchievements.points.value?.softcore)
        CompanionActivityAchievements.publishSoftcore(account, 320)
        assertEquals(320, CompanionActivityAchievements.points.value?.softcore)
    }

    @Test
    fun `a softcore score with no hardcore points held lands nowhere`() {
        CompanionActivityAchievements.publishSoftcore(account, 300)
        assertNull(CompanionActivityAchievements.points.value)
    }

    // ── an achievement earned in both modes is one achievement ──────────────────────────────────────────────────────────────────

    @Test
    fun `hardcore sits inside the earned count and no total adds the two`() = runTest {
        loader().load()

        val view = hub()
        assertEquals("40 + 30 + 7 + 4, the union", 81, view.unlocked)
        assertEquals(100, view.total)
        view.snapshots.forEach { assertTrue("${it.gameId}: hardcore ${it.earnedHardcore} is inside unlocked ${it.unlocked}", it.earnedHardcore <= it.unlocked) }
        assertEquals("the hardcore counts are their own figure: 40 + 12 + 7 + 0", 59, view.snapshots.sumOf { it.earnedHardcore })
        assertEquals("so the earned total is never 81 + 59", 81, view.snapshots.sumOf { it.unlocked })
        assertEquals("Sonic finished in both modes is 30 of 30, not 42 of 30", 30, view.snapshots.first { it.gameId == "local-sonic" }.unlocked)
    }

    @Test
    fun `each game carries its hardcore count and RA's award, matched or not`() = runTest {
        loader().load()
        val byId = CompanionActivityAchievements.snapshots.value.associateBy { it.gameId }

        assertEquals(40, byId.getValue("local-metroid").earnedHardcore)
        assertEquals(RaAwardKind.MASTERED, byId.getValue("local-metroid").awardKind)
        assertEquals(12, byId.getValue("local-sonic").earnedHardcore)
        assertEquals(RaAwardKind.COMPLETED, byId.getValue("local-sonic").awardKind)
        assertEquals(RaAwardKind.BEATEN_HARDCORE, byId.getValue("ra:3").awardKind)
        assertEquals(RaAwardKind.BEATEN_SOFTCORE, byId.getValue("ra:4").awardKind)

        assertEquals(ActivityAwardTier.MASTERED, byId.getValue("local-metroid").awardTier())
        assertEquals(ActivityAwardTier.COMPLETED, byId.getValue("local-sonic").awardTier())
        assertEquals(ActivityAwardTier.BEATEN, byId.getValue("ra:3").awardTier())
        assertEquals(ActivityAwardTier.BEATEN, byId.getValue("ra:4").awardTier())
    }

    @Test
    fun `a hardcore count RA left out reads as none and a game can still be finished`() = runTest {
        gamesAnswer = { RaUserGamesOutcome.Read(listOf(RetroAchievementsUserGame(9, "Plain Game", "Game Boy", earned = 10, total = 10)), 1) }
        loader().load()
        val plain = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(0, plain.earnedHardcore)
        assertNull(plain.awardKind)
        assertEquals("every achievement earned, none in hardcore: Completed, and not Mastered", ActivityAwardTier.COMPLETED, plain.awardTier())
    }

    // ── the ledger ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `both scores, the hardcore counts and the awards are saved and come back at the next start with no network`() = runTest {
        loader().load()
        val gameCallsAfterLoad = gameCalls

        CompanionActivityAchievements.resetForTest()
        val fresh = loader()
        assertTrue(fresh.hydrate())

        assertEquals("not one request", gameCallsAfterLoad, gameCalls)
        assertEquals(CompanionActivityPoints(account, 900, 300), CompanionActivityAchievements.points.value)
        val byId = CompanionActivityAchievements.snapshots.value.associateBy { it.gameId }
        assertEquals(12, byId.getValue("local-sonic").earnedHardcore)
        assertEquals(RaAwardKind.COMPLETED, byId.getValue("local-sonic").awardKind)
        assertEquals(RaAwardKind.BEATEN_SOFTCORE, byId.getValue("ra:4").awardKind)
        assertEquals(81, hub().unlocked)
    }

    @Test
    fun `a ledger saved without any of the new fields reads as it always did and says nothing it does not know`() = runTest {
        ledger.saveBulk(account, listOf(RetroAchievementsUserGame(1, "Super Metroid", "Super Nintendo Entertainment System", earned = 12, total = 40)), 1500, null, nowMillis = 900_000L)
        val text = bulkText()
        assertFalse("no softcore key", "softcore" in text)
        assertFalse("no hardcore key", "\"hc\"" in text)
        assertFalse("no award key", "\"award\"" in text)

        assertTrue(loader().hydrate())
        assertEquals("the saved points are the points", CompanionActivityPoints(account, 1500, 0), CompanionActivityAchievements.points.value)
        val metroid = CompanionActivityAchievements.snapshots.value.single()
        assertEquals(0, metroid.earnedHardcore)
        assertNull(metroid.awardKind)
    }

    @Test
    fun `a damaged row cannot put more hardcore unlocks than earned ones`() {
        ledger.saveBulk(account, listOf(RetroAchievementsUserGame(1, "Odd", "Game Boy", earned = 5, total = 10, earnedHardcore = 99)), 10, null, nowMillis = 900_000L)
        val read = ledger.readBulk(account)!!.games.single()
        assertEquals(5, read.earnedHardcore)
        assertEquals(5, read.earned)
    }

    @Test
    fun `a per-game check folded into the ledger keeps the award the bulk read gave`() {
        ledger.saveBulk(account, games, 900, null, nowMillis = 900_000L, softcorePoints = 300)
        // A per-game read of Sonic (no award word of its own) with a fresh hardcore count.
        ledger.upsertBulkGames(account, listOf(RetroAchievementsUserGame(2, "Sonic the Hedgehog 2", "Mega Drive", earned = 30, total = 30, earnedHardcore = 30)), nowMillis = 950_000L)
        val sonic = ledger.readBulk(account)!!.games.first { it.gameId == 2 }
        assertEquals("the award the bulk read gave is not taken back", RaAwardKind.COMPLETED, sonic.awardKind)
        assertEquals("and the fresher hardcore count stands", 30, sonic.earnedHardcore)
        assertEquals("the profile's scores are untouched", 300, ledger.readBulk(account)!!.softcorePoints)
        assertEquals(900, ledger.readBulk(account)!!.points)
    }

    @Test
    fun `the award is stored as RA's own word, and a file that kept it as a number still reads`() {
        ledger.saveBulk(account, games, 900, null, nowMillis = 900_000L)
        val text = bulkText()
        assertTrue("RA's word is what is stored: $text", "\"awardWord\":\"beaten-hardcore\"" in text && "\"awardWord\":\"mastered\"" in text)
        assertFalse("and no number any more", "\"award\":" in text)
        assertEquals(games.map { it.awardKind }, ledger.readBulk(account)!!.games.map { it.awardKind })

        // The shape an earlier build wrote: the ordinal of the kind, plus one (beaten-softcore 1, beaten-hardcore 2, completed 3, mastered 4).
        val file = File(File(File(temp.root, "ra_ledger"), RetroAchievementsLedger.accountKey(account)!!), "bulk.json")
        file.writeText(
            text.replace("\"awardWord\":\"beaten-hardcore\"", "\"award\":2")
                .replace("\"awardWord\":\"beaten-softcore\"", "\"award\":1")
                .replace("\"awardWord\":\"completed\"", "\"award\":3")
                .replace("\"awardWord\":\"mastered\"", "\"award\":4"),
        )
        assertFalse("the word is gone from the rewritten file", "awardWord" in file.readText())
        assertEquals("every old number reads as its kind", games.map { it.awardKind }, ledger.readBulk(account)!!.games.map { it.awardKind })
        // A number out of range is no award, never a crash.
        file.writeText(file.readText().replaceFirst("\"award\":2", "\"award\":99"))
        assertNull(ledger.readBulk(account)!!.games.first { it.gameId == 3 }.awardKind)
    }

    @Test
    fun `each award kind has its RA word, and the word reads back as the kind`() {
        assertEquals(listOf("beaten-softcore", "beaten-hardcore", "completed", "mastered"), RaAwardKind.entries.map { it.word })
        RaAwardKind.entries.forEach { assertEquals(it, RaAwardKind.fromProvider(it.word)) }
    }

    @Test
    fun `RA's award words are read exactly and anything else is no award`() {
        assertEquals(RaAwardKind.BEATEN_SOFTCORE, RaAwardKind.fromProvider("beaten-softcore"))
        assertEquals(RaAwardKind.BEATEN_HARDCORE, RaAwardKind.fromProvider("beaten-hardcore"))
        assertEquals(RaAwardKind.COMPLETED, RaAwardKind.fromProvider("completed"))
        assertEquals(RaAwardKind.MASTERED, RaAwardKind.fromProvider(" Mastered "))
        assertNull(RaAwardKind.fromProvider(null))
        assertNull(RaAwardKind.fromProvider(""))
        assertNull(RaAwardKind.fromProvider("legendary"))
    }
}
