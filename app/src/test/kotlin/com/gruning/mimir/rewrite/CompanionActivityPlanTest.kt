package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.launchesPerLocalDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Activity page's rules, with no composition: the bento's carve-up at the Thor Bottom's 538 x 468 dp, the Most launched ranking and its sort
 * orders (nulls last), the Been a while pick (per-day determinism, the 15-game window, the no-history fallback), the weekly bars' words, and the
 * midnight roll. Everything counts launches; nothing here ranks or words anything as playtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionActivityPlanTest {

    private val utc = ZoneId.of("UTC")
    private val day = 24L * 60L * 60L * 1000L

    /** 2026-10-02 12:00 UTC. */
    private val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()

    private fun game(
        id: String,
        title: String = id,
        playCount: Int = 1,
        lastPlayedDaysAgo: Long? = 1,
        firstPlayedDaysAgo: Long? = lastPlayedDaysAgo,
    ) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId("genesis"),
        memberCount = 1,
        duplicateKind = null,
        playCount = playCount,
        lastPlayedMillis = lastPlayedDaysAgo?.let { now - it * day },
        firstPlayedMillis = firstPlayedDaysAgo?.let { now - it * day },
    )

    // ── the zones ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the zones carve the Thor body so both columns end on the same line`() {
        // 538 x 468 dp: 18 dp insets, a 57 dp header (the 48 dp back pill, the gap, the rule), 8 dp clearance and the 36 dp dock reserve.
        val body = solveActivityBento(bodyWidthDp = 502f, bodyHeightDp = 468f - 10f - 57f - 8f - 36f)
        assertEquals(357f, body.bodyHeightDp, 0.001f)
        assertEquals(502f, body.leftWidthDp + body.gapDp + body.rightWidthDp, 0.001f)
        assertEquals(body.leftColumnHeightDp, body.rightColumnHeightDp, 0.001f)
        assertEquals(357f, body.leftColumnHeightDp, 0.001f)
        assertEquals(218f, body.rankedHeightDp, 0.001f)
        assertEquals(176f, body.lowerLeftHeightDp, 0.001f)
        assertEquals(169f, body.lastPlayedHeightDp, 0.001f)
        assertEquals(127f, body.weekHeightDp, 0.001f)
        assertEquals(258f, body.leftWidthDp, 0.001f)
        assertEquals(232f, body.rightWidthDp, 0.001f)
    }

    @Test
    fun `a body too short for the minimums runs past it so the page scrolls instead of squeezing a tile`() {
        val short = solveActivityBento(bodyWidthDp = 502f, bodyHeightDp = 300f)
        assertEquals(ActivityLastPlayedMinDp, short.lastPlayedHeightDp, 0.001f)
        assertEquals(ActivityWeekMinDp, short.weekHeightDp, 0.001f)
        assertTrue(short.leftColumnHeightDp > 300f)
        assertEquals(ActivityRankedTileDp, short.rankedHeightDp, 0.001f)
    }

    @Test
    fun `a ranked row is 50 dp so the finger target clears 48 and the ranked tile holds three of them under a 48 dp header`() {
        assertEquals(10f + 48f + 3f * 50f + 10f, ActivityRankedTileDp, 0.001f)
        assertEquals(3, ActivityRankedRows)
    }

    // ── Most launched ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `launches rank high to low, then by most recently played, then title`() {
        val games = listOf(
            game("a", "Alpha", playCount = 3, lastPlayedDaysAgo = 5),
            game("b", "Beta", playCount = 9, lastPlayedDaysAgo = 9),
            game("c", "Gamma", playCount = 3, lastPlayedDaysAgo = 2),
            game("d", "Delta", playCount = 1, lastPlayedDaysAgo = 1),
        )
        assertEquals(listOf("b", "c", "a"), activityRank(games, ActivitySort.LAUNCHES).map { it.gameId.value })
        assertEquals(listOf("b", "c", "a", "d"), activityRank(games, ActivitySort.LAUNCHES, limit = 10).map { it.gameId.value })
    }

    @Test
    fun `a launches tie with the same last played falls to the title and then the id, so the order is total`() {
        val games = listOf(
            game("z2", "Same", playCount = 2, lastPlayedDaysAgo = 4),
            game("z1", "Same", playCount = 2, lastPlayedDaysAgo = 4),
            game("a1", "Apple", playCount = 2, lastPlayedDaysAgo = 4),
        )
        assertEquals(listOf("a1", "z1", "z2"), activityRank(games, ActivitySort.LAUNCHES).map { it.gameId.value })
        assertEquals(
            activityRank(games, ActivitySort.LAUNCHES).map { it.gameId.value },
            activityRank(games.reversed(), ActivitySort.LAUNCHES).map { it.gameId.value },
        )
    }

    @Test
    fun `last played is most recent first and a game with no date goes after every dated one`() {
        val games = listOf(
            game("old", lastPlayedDaysAgo = 30),
            game("none", lastPlayedDaysAgo = null, firstPlayedDaysAgo = null),
            game("new", lastPlayedDaysAgo = 1),
            game("mid", lastPlayedDaysAgo = 7),
        )
        assertEquals(listOf("new", "mid", "old", "none"), activityRank(games, ActivitySort.LAST_PLAYED, limit = 10).map { it.gameId.value })
    }

    @Test
    fun `first played is oldest first and a game with no first date goes last`() {
        val games = listOf(
            game("recent", firstPlayedDaysAgo = 2),
            game("none", lastPlayedDaysAgo = 3, firstPlayedDaysAgo = null),
            game("oldest", firstPlayedDaysAgo = 200),
            game("middle", firstPlayedDaysAgo = 40),
        )
        assertEquals(
            listOf("oldest", "middle", "recent", "none"),
            activityRank(games, ActivitySort.FIRST_PLAYED, limit = 10).map { it.gameId.value },
        )
    }

    @Test
    fun `nulls last holds under every sort even when every game is undated`() {
        val games = listOf(
            game("b", "Beta", playCount = 1, lastPlayedDaysAgo = null, firstPlayedDaysAgo = null),
            game("a", "Alpha", playCount = 1, lastPlayedDaysAgo = null, firstPlayedDaysAgo = null),
        )
        ActivitySort.entries.forEach { sort ->
            assertEquals("$sort", listOf("a", "b"), activityRank(games, sort).map { it.gameId.value })
        }
    }

    @Test
    fun `the ranked tile lists three and the sort names never say playtime or time`() {
        val games = (1..8).map { game("g$it", playCount = it) }
        assertEquals(3, activityRank(games, ActivitySort.LAUNCHES).size)
        ActivitySort.entries.forEach { sort ->
            val words = (sort.optionLabel + " " + sort.headerLabel).lowercase()
            assertTrue(words, "playtime" !in words && "elapsed" !in words && "session" !in words)
        }
        assertEquals(listOf("Launches", "Last played", "First played"), ActivitySort.entries.map { it.optionLabel })
        assertEquals(ActivitySort.LAUNCHES, ActivitySort.entries.first())
    }

    @Test
    fun `a ranked row's value is in launch terms and a dateless game says so`() {
        val g = game("a", playCount = 14, lastPlayedDaysAgo = 3, firstPlayedDaysAgo = 40)
        assertEquals("14 launches", activityRankedValue(g, ActivitySort.LAUNCHES, now, utc))
        assertEquals("3 days ago", activityRankedValue(g, ActivitySort.LAST_PLAYED, now, utc))
        assertEquals("40 days ago", activityRankedValue(g, ActivitySort.FIRST_PLAYED, now, utc))
        assertEquals("1 launch", activityLaunchesLabel(1))
        assertEquals("Yesterday", activityRankedValue(game("y", lastPlayedDaysAgo = 1), ActivitySort.LAST_PLAYED, now, utc))
        val dateless = game("n", lastPlayedDaysAgo = null, firstPlayedDaysAgo = null)
        assertEquals("Not played yet", activityRankedValue(dateless, ActivitySort.LAST_PLAYED, now, utc))
        assertEquals("Not played yet", activityRankedValue(dateless, ActivitySort.FIRST_PLAYED, now, utc))
    }

    // ── Been a while ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun history(vararg daysAgo: Long): List<GameRow> =
        daysAgo.mapIndexed { index, d -> game("h$index", "Game $index", lastPlayedDaysAgo = d) }

    @Test
    fun `the pick is the same all day and on every recomposition`() {
        val history = history(1, 9, 20, 40, 41, 90)
        val morning = Instant.parse("2026-10-02T00:00:05Z").toEpochMilli()
        val night = Instant.parse("2026-10-02T23:59:55Z").toEpochMilli()
        val a = pickBeenAWhile(history, emptyList(), emptySet(), morning, utc)
        val b = pickBeenAWhile(history, emptyList(), emptySet(), night, utc)
        assertNotNull(a)
        assertEquals(a!!.game.gameId, b!!.game.gameId)
        repeat(5) { assertEquals(a.game.gameId, pickBeenAWhile(history, emptyList(), emptySet(), morning, utc)!!.game.gameId) }
        assertEquals(BeenAWhileKind.BEEN_A_WHILE, a.kind)
    }

    @Test
    fun `the pick walks through the stale games as the date turns and only ever picks one that has been a while`() {
        val history = history(1, 2, 9, 20, 40)
        val picks = (0L until 6L).map { offset ->
            val at = now + offset * day
            // Stale is measured at each day, but with the history this old everything at or past 9 days stays stale.
            pickBeenAWhile(history, emptyList(), emptySet(), at, utc)!!
        }
        picks.forEach {
            assertEquals(BeenAWhileKind.BEEN_A_WHILE, it.kind)
            assertTrue("only a game that has been a while: ${it.daysSince}", it.daysSince!! >= ActivityBeenAWhileDays)
        }
        assertTrue("the pick moves with the date", picks.map { it.game.gameId }.distinct().size > 1)
        assertEquals(BeenAWhileKind.BEEN_A_WHILE, picks.first().kind)
        assertTrue(picks.first().daysSince!! >= ActivityBeenAWhileDays)
    }

    @Test
    fun `the window is the 15 most recently launched games, so a 16th is never offered`() {
        // 15 recent games (all within 6 days: none stale) and a 16th that is very stale: it is outside the window.
        val recent = (0 until 15).map { game("r$it", lastPlayedDaysAgo = (it % 6).toLong() + 1) }
        val ancient = game("ancient", lastPlayedDaysAgo = 400)
        val pick = pickBeenAWhile(recent + ancient, emptyList(), emptySet(), now, utc)!!
        assertNotEquals("ancient", pick.game.gameId.value)
        assertEquals("nothing in the window is old enough to claim a while", BeenAWhileKind.RECENTLY_PLAYED, pick.kind)
        // The same game, inside the window, is offered.
        val inside = pickBeenAWhile(recent.take(14) + ancient, emptyList(), emptySet(), now, utc)!!
        assertEquals("ancient", inside.game.gameId.value)
        assertEquals(BeenAWhileKind.BEEN_A_WHILE, inside.kind)
        assertEquals(400L, inside.daysSince)
    }

    @Test
    fun `an excluded game is never picked and does not take a window seat`() {
        val history = history(40, 50)
        val pick = pickBeenAWhile(history, emptyList(), exclude = setOf(GameId("h0")), now, utc)!!
        assertEquals("h1", pick.game.gameId.value)
        assertNull(pickBeenAWhile(history, emptyList(), exclude = setOf(GameId("h0"), GameId("h1")), now, utc))
    }

    @Test
    fun `recent history only reads Recently played and never claims a while`() {
        val pick = pickBeenAWhile(history(1, 2, 3), emptyList(), emptySet(), now, utc)!!
        assertEquals(BeenAWhileKind.RECENTLY_PLAYED, pick.kind)
        assertEquals("Recently played", pick.kind.eyebrow)
        assertEquals("h2", pick.game.gameId.value)
        assertTrue("while" !in pick.kind.eyebrow.replace("Been a while", ""))
    }

    @Test
    fun `with no launch history it reads from your shelf and states no while`() {
        val shelf = listOf(game("s1", "Shelf One", playCount = 0, lastPlayedDaysAgo = null), game("s2", "Shelf Two", playCount = 0, lastPlayedDaysAgo = null))
        val pick = pickBeenAWhile(emptyList(), shelf, emptySet(), now, utc)!!
        assertEquals(BeenAWhileKind.FROM_YOUR_SHELF, pick.kind)
        assertEquals("From your shelf", pick.kind.eyebrow)
        assertNull(pick.daysSince)
        assertEquals("Not played yet", pick.line(now, utc))
        assertTrue("no while anywhere in what it says", "while" !in (pick.kind.eyebrow + pick.line(now, utc)).lowercase())
        // Stable through the day, and the other tile's pick never repeats it.
        assertEquals(pick.game.gameId, pickBeenAWhile(emptyList(), shelf, emptySet(), now + 3_600_000L, utc)!!.game.gameId)
        val second = pickBeenAWhile(emptyList(), shelf, setOf(pick.game.gameId), now, utc)!!
        assertNotEquals(pick.game.gameId, second.game.gameId)
        assertNull(pickBeenAWhile(emptyList(), shelf, shelf.map { it.gameId }.toSet(), now, utc))
        assertNull(pickBeenAWhile(emptyList(), emptyList(), emptySet(), now, utc))
    }

    @Test
    fun `a launched game that is the only one and is excluded falls to the shelf pool`() {
        val live = game("live", lastPlayedDaysAgo = 0)
        val pick = pickBeenAWhile(listOf(live), listOf(game("fav", playCount = 0, lastPlayedDaysAgo = null)), setOf(live.gameId), now, utc)!!
        assertEquals("fav", pick.game.gameId.value)
        assertEquals(BeenAWhileKind.FROM_YOUR_SHELF, pick.kind)
    }

    @Test
    fun `the eyebrow says Been a while from exactly 7 days and Recently played before, never Jump back in`() {
        assertEquals(BeenAWhileKind.RECENTLY_PLAYED, pickBeenAWhile(history(6), emptyList(), emptySet(), now, utc)!!.kind)
        assertEquals(BeenAWhileKind.BEEN_A_WHILE, pickBeenAWhile(history(ActivityBeenAWhileDays), emptyList(), emptySet(), now, utc)!!.kind)
        BeenAWhileKind.values().forEach { assertTrue(it.eyebrow, !it.eyebrow.contains("Jump back", ignoreCase = true)) }
    }

    @Test
    fun `the shelf pool is wanted when no launched game is left once the shown ones are taken out, not by counting launched games`() {
        // Nothing launched, and one launched game that the top-left tile shows: nothing left to draw on.
        assertTrue(activityNeedsShelfPool(emptyList(), emptySet()))
        val one = history(1)
        assertTrue(activityNeedsShelfPool(one, activityVisibleGameIds(one, null, ActivitySort.LAUNCHES)))
        // Three launched games that Most launched lists, one of which is also the top-left tile: all three are on the page, so none is left.
        val three = history(1, 2, 3)
        val shownThree = activityVisibleGameIds(three, null, ActivitySort.LAUNCHES)
        assertEquals(setOf("h0", "h1", "h2"), shownThree.map { it.value }.toSet())
        assertTrue("three launched games all in Most launched leave nothing for Been a while", activityNeedsShelfPool(three, shownThree))
        // A fourth game is left, so the pool is not wanted; it is the one Been a while may show.
        val four = history(1, 2, 3, 4)
        val shownFour = activityVisibleGameIds(four, null, ActivitySort.LAUNCHES)
        assertTrue(!activityNeedsShelfPool(four, shownFour))
        assertEquals("h3", pickBeenAWhile(four, emptyList(), shownFour, now, utc)!!.game.gameId.value)
        // A running game that is not the last played one is the top-left tile's, and a ranked row is a ranked row.
        assertTrue(GameId("live") in activityVisibleGameIds(four, GameId("live"), ActivitySort.LAUNCHES))
        // The sort changes which rows are on the page, so which games are taken out.
        val sorted = listOf(
            game("a", playCount = 9, lastPlayedDaysAgo = 1, firstPlayedDaysAgo = 10),
            game("b", playCount = 8, lastPlayedDaysAgo = 2, firstPlayedDaysAgo = 20),
            game("c", playCount = 7, lastPlayedDaysAgo = 3, firstPlayedDaysAgo = 30),
            game("d", playCount = 1, lastPlayedDaysAgo = 4, firstPlayedDaysAgo = 400),
        )
        assertEquals(setOf("a", "b", "c"), activityVisibleGameIds(sorted, null, ActivitySort.LAUNCHES).map { it.value }.toSet())
        assertEquals(setOf("a", "b", "c", "d"), activityVisibleGameIds(sorted, null, ActivitySort.FIRST_PLAYED).map { it.value }.toSet())
        // A read that cannot know the sort keeps clear of every order's rows.
        assertEquals(setOf("a", "b", "c", "d"), activityPossiblyVisibleGameIds(sorted, null).map { it.value }.toSet())
    }

    @Test
    fun `three launched games already in Most launched do not leave Been a while repeating one of them, it reads from the shelf`() {
        val three = history(1, 2, 3)
        val shelf = listOf(game("s1", "Shelf One", playCount = 0, lastPlayedDaysAgo = null))
        val shown = activityVisibleGameIds(three, null, ActivitySort.LAUNCHES)
        val pick = pickBeenAWhile(three, shelf, shown, now, utc)!!
        assertEquals("s1", pick.game.gameId.value)
        assertEquals(BeenAWhileKind.FROM_YOUR_SHELF, pick.kind)
    }

    // ── the shelf pool's reads ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun platform(id: String, count: Int) = PlatformRow(PlatformId(id), id, count)

    private suspend fun pool(
        favourites: List<GameRow>,
        platforms: List<PlatformRow>,
        games: Map<String, List<GameRow>>,
        exclude: Set<GameId>,
        dayEpoch: Long = 0L,
        needed: Int = 1,
    ): List<String> = activityShelfPoolFlow(
        favourites = flowOf(favourites),
        platforms = flowOf(platforms),
        gamesOf = { flowOf(games[it.value].orEmpty()) },
        dayEpoch = dayEpoch,
        exclude = exclude,
        needed = needed,
    ).first().map { it.gameId.value }

    @Test
    fun `favourites are the pool while one of them is free to show`() = runTest {
        val favs = listOf(game("f1", lastPlayedDaysAgo = null), game("f2", lastPlayedDaysAgo = null))
        assertEquals(listOf("f1", "f2"), pool(favs, listOf(platform("genesis", 3)), mapOf("genesis" to listOf(game("g1"))), setOf(GameId("f1"))))
    }

    @Test
    fun `when exclusions exhaust the favourites the pool falls through to the rest of the shelf instead of leaving the tile empty`() = runTest {
        // The only favourite is the game another tile shows: the old read answered with it alone and Been a while said "Nothing here yet".
        val fav = game("fav", lastPlayedDaysAgo = null)
        val other = game("other", lastPlayedDaysAgo = null)
        val read = pool(listOf(fav), listOf(platform("genesis", 2)), mapOf("genesis" to listOf(fav, other)), setOf(fav.gameId))
        assertEquals(listOf("fav", "other"), read)
        val pick = pickBeenAWhile(emptyList(), activityShelfPoolFlow(
            favourites = flowOf(listOf(fav)), platforms = flowOf(listOf(platform("genesis", 2))),
            gamesOf = { flowOf(listOf(fav, other)) }, dayEpoch = 0L, exclude = setOf(fav.gameId),
        ).first(), setOf(fav.gameId), now, utc)!!
        assertEquals("other", pick.game.gameId.value)
    }

    @Test
    fun `with no launch history and one favourite in the top-left tile, Been a while still finds a game on the rest of the shelf`() = runTest {
        // Nothing launched: the top-left tile takes the pool's first game. The only favourite is that game, so a pool of one free game leaves the
        // fourth tile "Nothing here yet"; asking for two free games sends the read to the shelf for the second.
        val fav = game("fav", lastPlayedDaysAgo = null)
        val b = game("b", lastPlayedDaysAgo = null)
        val c = game("c", lastPlayedDaysAgo = null)
        val platforms = listOf(platform("genesis", 3))
        val games = mapOf("genesis" to listOf(fav, b, c))
        fun fourthTile(read: List<String>): String? {
            val rows = read.map { id -> listOf(fav, b, c).first { it.gameId.value == id } }
            val topLeft = rows.firstOrNull()?.gameId
            return pickBeenAWhile(emptyList(), rows, setOfNotNull(topLeft), now, utc)?.game?.gameId?.value
        }
        assertNull("one wanted game leaves the second tile with only the first's", fourthTile(pool(listOf(fav), platforms, games, emptySet(), needed = 1)))
        val two = pool(listOf(fav), platforms, games, emptySet(), needed = 2)
        assertEquals(listOf("fav", "b", "c"), two)
        assertEquals("b", fourthTile(two))
        // With a launched game on the page the top-left tile is that game and one free game is enough.
        assertEquals(listOf("fav"), pool(listOf(fav), platforms, games, emptySet(), needed = 1))
        // Two free favourites are already enough for two tiles.
        assertEquals(listOf("fav", "b"), pool(listOf(fav, b), platforms, games, emptySet(), needed = 2))
    }

        @Test
    fun `a favourite and a non-favourite on separate single-game platforms both reach the pool, whichever platform the day starts on`() = runTest {
        // Favourite A is alone on "aplat", non-favourite B alone on "bplat". Day 1 walks bplat first and then aplat: the walk used to drop B on
        // reaching A (A alone was "enough"), so T1 took A and T4 had nothing. It now accumulates until two free games are held.
        val a = game("a", lastPlayedDaysAgo = null)
        val b = game("b", lastPlayedDaysAgo = null)
        val platforms = listOf(platform("aplat", 1), platform("bplat", 1))
        val games = mapOf("aplat" to listOf(a), "bplat" to listOf(b))
        val read = pool(listOf(a), platforms, games, emptySet(), dayEpoch = 1L, needed = 2)
        assertEquals(listOf("a", "b"), read)
        val rows = read.map { id -> listOf(a, b).first { it.gameId.value == id } }
        assertEquals("b", pickBeenAWhile(emptyList(), rows, setOf(rows.first().gameId), now, utc)!!.game.gameId.value)
        // The other direction (day 0 walks aplat first) gives the same two games.
        assertEquals(setOf("a", "b"), pool(listOf(a), platforms, games, emptySet(), dayEpoch = 0L, needed = 2).toSet())
        // One wanted game is still just the favourite, and nothing is walked.
        assertEquals(listOf("a"), pool(listOf(a), platforms, games, emptySet(), dayEpoch = 1L, needed = 1))
    }

@Test
    fun `no favourites reads the day's platform, and a platform whose games are all excluded hands over to the next that has one`() = runTest {
        val a = game("a", lastPlayedDaysAgo = null)
        val b = game("b", lastPlayedDaysAgo = null)
        val platforms = listOf(platform("snes", 1), platform("genesis", 1))
        val games = mapOf("genesis" to listOf(a), "snes" to listOf(b))
        // Sorted by id: genesis, snes. Day 0 starts at genesis; with a excluded it hands over to snes.
        assertEquals(listOf("a"), pool(emptyList(), platforms, games, emptySet(), dayEpoch = 0L))
        assertEquals(listOf("b"), pool(emptyList(), platforms, games, setOf(a.gameId), dayEpoch = 0L))
        // Day 1 starts at snes; with b excluded it wraps to genesis.
        assertEquals(listOf("a"), pool(emptyList(), platforms, games, setOf(b.gameId), dayEpoch = 1L))
        // A shelf of nothing but excluded games answers with them, and the pick then refuses them: the tile truthfully has nothing to add.
        assertEquals(listOf("b"), pool(emptyList(), platforms, games, setOf(a.gameId, b.gameId), dayEpoch = 0L))
        assertNull(pickBeenAWhile(emptyList(), listOf(b), setOf(a.gameId, b.gameId), now, utc))
        // No platform with a game: nothing.
        assertEquals(emptyList<String>(), pool(emptyList(), listOf(platform("snes", 0)), emptyMap(), emptySet()))
    }


    @Test
    fun `the days since a launch are calendar days in the zone, never negative`() {
        assertEquals(0L, activityDaysSince(now, now, utc))
        assertEquals(1L, activityDaysSince(Instant.parse("2026-10-01T23:59:00Z").toEpochMilli(), Instant.parse("2026-10-02T00:01:00Z").toEpochMilli(), utc))
        assertEquals(0L, activityDaysSince(now + 5 * day, now, utc))
    }

    // ── This week ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun week(vararg counts: Int): List<DailyLaunchCount> =
        counts.mapIndexed { index, c -> DailyLaunchCount(LocalDate.of(2026, 9, 26).plusDays(index.toLong()), c) }

    @Test
    fun `the bars speak once, as launches, with the total and each day`() {
        val w = week(0, 3, 0, 1, 0, 0, 4)
        assertEquals(8, activityWeekTotal(w))
        assertEquals("8 launches", activityWeekHeadline(w))
        assertEquals("Launches this week: 8 (Sat 0, Sun 3, Mon 0, Tue 1, Wed 0, Thu 0, Fri 4)", activityWeekSpoken(w, Locale.US))
        assertTrue("never playtime", "play" !in activityWeekSpoken(w, Locale.US).lowercase())
    }

    @Test
    fun `an empty week is honest about it`() {
        val w = week(0, 0, 0, 0, 0, 0, 0)
        assertEquals("None yet", activityWeekHeadline(w))
        assertEquals("Launches this week: none yet", activityWeekSpoken(w, Locale.US))
        assertEquals("1 launch", activityWeekHeadline(week(0, 0, 0, 0, 0, 0, 1)))
    }

    @Test
    fun `a day gets one letter`() {
        assertEquals("F", activityDayLetter(LocalDate.of(2026, 10, 2), Locale.US))
    }

    // ── the midnight roll ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the time to the next local midnight follows the zone's own days`() {
        assertEquals(12L * 3_600_000L, millisUntilNextLocalMidnight(now, utc))
        // A 23-hour day (US spring forward, 2026-03-08): 01:00 local has 22 hours left, not 23. A 25-hour day (fall back, 2026-11-01): 00:30
        // local has 24.5 hours left, not 23.5. Noon is the same 12 hours on both, because the odd hour has already passed.
        val ny = ZoneId.of("America/New_York")
        fun at(month: Int, dayOfMonth: Int, hour: Int, minute: Int) =
            java.time.ZonedDateTime.of(2026, month, dayOfMonth, hour, minute, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(22L * 3_600_000L, millisUntilNextLocalMidnight(at(3, 8, 1, 0), ny))
        assertEquals(24L * 3_600_000L + 30L * 60_000L, millisUntilNextLocalMidnight(at(11, 1, 0, 30), ny))
        assertEquals(12L * 3_600_000L, millisUntilNextLocalMidnight(at(3, 8, 12, 0), ny))
        assertEquals(1L, millisUntilNextLocalMidnight(Instant.parse("2026-10-02T23:59:59.999Z").toEpochMilli(), utc))
    }

    @Test
    fun `a page left open past midnight re-subscribes and the new window is read`() = runTest {
        val start = Instant.parse("2026-10-02T22:00:00Z").toEpochMilli()
        val clock = { start + testScheduler.currentTime }
        var subscriptions = 0
        val windowsSeen = mutableListOf<LocalDate>()
        val ticker = activityDayTicker(nowMillis = clock, zone = { utc }, wait = { kotlinx.coroutines.delay(it) })
        val flow = activityWeekFlow(
            launches = {
                flow {
                    subscriptions++
                    // The repository's own contract: the window is cut as of each subscription.
                    emit(launchesPerLocalDay(emptyList(), clock(), utc))
                    kotlinx.coroutines.awaitCancellation()
                }
            },
            days = ticker,
        )
        val job = backgroundScope.launch { flow.collect { windowsSeen += it.last().date } }
        testScheduler.runCurrent()
        assertEquals("entering the page subscribes once", 1, subscriptions)
        assertEquals(LocalDate.of(2026, 10, 2), windowsSeen.last())

        advanceTimeBy(1L * 3_600_000L)
        testScheduler.runCurrent()
        assertEquals("an hour before midnight nothing has moved", 1, subscriptions)

        advanceTimeBy(1L * 3_600_000L + 1_000L)
        testScheduler.runCurrent()
        assertEquals("midnight re-subscribes", 2, subscriptions)
        assertEquals("and the window now ends on the new day", LocalDate.of(2026, 10, 3), windowsSeen.last())

        advanceTimeBy(24L * 3_600_000L)
        testScheduler.runCurrent()
        assertEquals("one subscription per day, no more", 3, subscriptions)
        assertEquals(LocalDate.of(2026, 10, 4), windowsSeen.last())
        job.cancel()
    }
}
