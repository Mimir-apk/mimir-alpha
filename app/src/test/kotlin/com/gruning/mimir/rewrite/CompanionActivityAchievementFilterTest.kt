package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Activity achievement page's filter logic (): the title search (case and diacritics
 * insensitive), each status, the console counts, both sorts, the result count and the held state. Pure: it reads the snapshot it is handed and the clock it is handed, so a
 * filter can start no request and cost no network call.
 */
class CompanionActivityAchievementFilterTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun snap(
        id: String,
        title: String?,
        unlocked: Int,
        total: Int,
        console: String? = null,
        awarded: Long? = null,
        checked: Long? = null,
        rowsKnown: Boolean = false,
        hardcore: Int = 0,
        award: RaAwardKind? = null,
    ) = CompanionActivityAchievementSnapshot(
        gameId = id,
        account = "me",
        unlocked = unlocked,
        total = total,
        gameTitle = title,
        checkedAtEpochMillis = checked,
        rowsKnown = rowsKnown,
        consoleName = console,
        lastAwardedAtMillis = awarded,
        earnedHardcore = hardcore,
        awardKind = award,
    )

    private val pokemon = snap("1", "Pokémon Red", 10, 151, console = "Game Boy", awarded = now - 2 * day)
    private val metroid = snap("2", "Super Metroid", 40, 40, console = "SNES", awarded = now - 40 * day, hardcore = 40, award = RaAwardKind.MASTERED)
    private val sonic = snap("3", "Sonic the Hedgehog 2", 5, 40, console = "Genesis/Mega Drive", awarded = now - 29 * day)
    private val chrono = snap("4", "Chrono Trigger", 0, 60, console = "SNES")
    private val eclair = snap("5", "Éclair Quest", 3, 10, console = "SNES", awarded = now - 10 * day)
    private val mystery = snap("6", "Mystery Game", 1, 5)
    private val all = listOf(pokemon, metroid, sonic, chrono, eclair, mystery)
    private val index = ActivityAchievementIndex(all)

    private fun ids(filter: ActivityAchievementFilter, from: ActivityAchievementIndex = index): List<String> =
        activityAchievementResult(from, filter, now).visible.map { it.gameId }

    private fun idSet(filter: ActivityAchievementFilter): Set<String> = ids(filter).toSet()

    // ── the title search ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a title matches without regard to case or accents, in either direction`() {
        assertEquals(setOf("1"), idSet(ActivityAchievementFilter(query = "pokemon")))
        assertEquals(setOf("1"), idSet(ActivityAchievementFilter(query = "POKEMON")))
        assertEquals(setOf("1"), idSet(ActivityAchievementFilter(query = "Pokémon")))
        assertEquals(setOf("1"), idSet(ActivityAchievementFilter(query = "POKÉMON")))
        assertEquals("an accent in the TITLE is matched by a plain query", setOf("5"), idSet(ActivityAchievementFilter(query = "eclair")))
        assertEquals("and an accent in the QUERY by a plain title", setOf("5"), idSet(ActivityAchievementFilter(query = "éCLAIR")))
        assertEquals("a part of a word is enough", setOf("1"), idSet(ActivityAchievementFilter(query = "oke")))
    }

    @Test
    fun `every word of the query must be in the title, in any order`() {
        assertEquals(setOf("3"), idSet(ActivityAchievementFilter(query = "sonic 2")))
        assertEquals(setOf("3"), idSet(ActivityAchievementFilter(query = "hedgehog   SONIC")))
        assertEquals(emptySet<String>(), idSet(ActivityAchievementFilter(query = "sonic metroid")))
        assertEquals(emptySet<String>(), idSet(ActivityAchievementFilter(query = "zzz")))
    }

    @Test
    fun `a blank query lets every game through`() {
        assertEquals(all.map { it.gameId }.toSet(), idSet(ActivityAchievementFilter(query = "")))
        assertEquals(all.map { it.gameId }.toSet(), idSet(ActivityAchievementFilter(query = "   ")))
        assertFalse("a blank query is not a filter", ActivityAchievementFilter(query = "  ").narrows)
    }

    @Test
    fun `a game with no title is found by the id the page shows for it`() {
        val untitled = snap("zelda-local", " ", 1, 4)
        assertEquals("zelda-local", untitled.displayTitle())
        val result = ids(ActivityAchievementFilter(query = "zelda"), ActivityAchievementIndex(listOf(untitled, chrono)))
        assertEquals(listOf("zelda-local"), result)
    }

    @Test
    fun `the fold drops accents and case and collapses white space`() {
        assertEquals("pokemon red", activityTitleFold("  Pokémon   RED "))
        assertEquals("pokemon", activityTitleFold("Pokémon"))
        assertEquals("cafe", activityTitleFold("CAFÉ"))
        assertEquals("", activityTitleFold("   "))
    }

    // ── status ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `in progress is everything with something left to earn, and mastered is every achievement earned in hardcore`() {
        assertEquals(setOf("1", "3", "4", "5", "6"), idSet(ActivityAchievementFilter(status = ActivityAchievementStatus.IN_PROGRESS)))
        assertEquals(setOf("2"), idSet(ActivityAchievementFilter(status = ActivityAchievementStatus.MASTERED)))
        assertEquals(
            "in progress, completed and mastered split All exactly, so their counts always add up",
            all.size,
            listOf(ActivityAchievementStatus.IN_PROGRESS, ActivityAchievementStatus.COMPLETED, ActivityAchievementStatus.MASTERED).sumOf { ids(ActivityAchievementFilter(status = it)).size },
        )
        assertEquals(all.size, ids(ActivityAchievementFilter(status = ActivityAchievementStatus.ALL)).size)
        assertTrue(metroid.isFinished())
        assertFalse("a game with no achievements is not finished", snap("x", "X", 0, 0).isFinished())
    }

    // ── RA's terms : Mastered = all in hardcore, Completed = all in softcore, Beaten = RA's own beaten award ────────────

    private val softcoreFinish = snap("7", "Softcore Finish", 20, 20, hardcore = 0, award = RaAwardKind.COMPLETED)
    private val partlyHardcoreFinish = snap("8", "Mixed Finish", 20, 20, hardcore = 12, award = RaAwardKind.COMPLETED)
    private val beatenHard = snap("9", "Beaten Hard", 14, 20, hardcore = 14, award = RaAwardKind.BEATEN_HARDCORE)
    private val beatenSoft = snap("10", "Beaten Soft", 9, 20, hardcore = 0, award = RaAwardKind.BEATEN_SOFTCORE)
    private val termIndex = ActivityAchievementIndex(all + listOf(softcoreFinish, partlyHardcoreFinish, beatenHard, beatenSoft))

    private fun termIds(status: ActivityAchievementStatus) = ids(ActivityAchievementFilter(status = status), termIndex).toSet()

    @Test
    fun `a game finished in softcore, or only partly in hardcore, is Completed and not Mastered`() {
        assertEquals(setOf("2"), termIds(ActivityAchievementStatus.MASTERED))
        assertEquals("every achievement earned, not all in hardcore", setOf("7", "8"), termIds(ActivityAchievementStatus.COMPLETED))
        assertEquals(ActivityAwardTier.COMPLETED, softcoreFinish.awardTier())
        assertEquals(ActivityAwardTier.COMPLETED, partlyHardcoreFinish.awardTier())
        assertEquals(ActivityAwardTier.MASTERED, metroid.awardTier())
    }

    @Test
    fun `Beaten is only RA's own beaten award, softcore or hardcore, on a game that is not finished`() {
        assertEquals(setOf("9", "10"), termIds(ActivityAchievementStatus.BEATEN))
        assertEquals(ActivityAwardTier.BEATEN, beatenHard.awardTier())
        assertEquals(ActivityAwardTier.BEATEN, beatenSoft.awardTier())
        assertEquals("a game with no award word is never called beaten", ActivityAwardTier.NONE, pokemon.awardTier())
        // A beaten game still has things to earn, so In progress keeps it; once it is finished the finish wins.
        assertTrue(termIds(ActivityAchievementStatus.IN_PROGRESS).containsAll(setOf("9", "10")))
        val beatenThenFinished = snap("11", "Beaten Then Finished", 20, 20, hardcore = 5, award = RaAwardKind.BEATEN_HARDCORE)
        assertEquals(ActivityAwardTier.COMPLETED, beatenThenFinished.awardTier())
        val beatenThenMastered = snap("12", "Beaten Then Mastered", 20, 20, hardcore = 20, award = RaAwardKind.BEATEN_HARDCORE)
        assertEquals(ActivityAwardTier.MASTERED, beatenThenMastered.awardTier())
    }

    @Test
    fun `the three-way split of In progress, Completed and Mastered covers every game exactly once`() {
        val everyone = termIndex.snapshots.map { it.gameId }.toSet()
        val inProgress = termIds(ActivityAchievementStatus.IN_PROGRESS)
        val completed = termIds(ActivityAchievementStatus.COMPLETED)
        val mastered = termIds(ActivityAchievementStatus.MASTERED)
        assertEquals(everyone, inProgress + completed + mastered)
        assertEquals(everyone.size, inProgress.size + completed.size + mastered.size)
    }

    @Test
    fun `an earned count that lags the hardcore count is read as the hardcore one, so the split stays exact`() {
        // Hardcore sits inside the earned count, so it can never be the larger: a lagging 38 beside 40 hardcore is a game with all 40 earned.
        val lagging = snap("16", "Lagging Count", 38, 40, hardcore = 40)
        val index = ActivityAchievementIndex(listOf(lagging, pokemon))
        fun idsFor(status: ActivityAchievementStatus) = ids(ActivityAchievementFilter(status = status), index).toSet()
        assertEquals(setOf("16"), idsFor(ActivityAchievementStatus.MASTERED))
        assertEquals(setOf("1"), idsFor(ActivityAchievementStatus.IN_PROGRESS))
        assertEquals(emptySet<String>(), idsFor(ActivityAchievementStatus.COMPLETED))
        assertTrue(lagging.isFinished())
    }

    @Test
    fun `RA's own award is read before the counts, and the higher of the two stands`() {
        // RA says mastered: a set that grew since (3 of 20 now) keeps the mastery RA still shows on the game.
        val grown = snap("13", "Grown Set", 3, 20, hardcore = 0, award = RaAwardKind.MASTERED)
        assertEquals(ActivityAwardTier.MASTERED, grown.awardTier())
        assertEquals("Mastered", activityAwardLabel(3, 20, 0, RaAwardKind.MASTERED))
        assertEquals(ActivityAwardTier.COMPLETED, snap("17", "Completed Word", 5, 20, award = RaAwardKind.COMPLETED).awardTier())
        // The counts see an unlock the award has not caught up with: every achievement in hardcore beats a "completed" word, and a finished set beats a "beaten" one.
        assertEquals(ActivityAwardTier.MASTERED, snap("18", "Newly Mastered", 20, 20, hardcore = 20, award = RaAwardKind.COMPLETED).awardTier())
        assertEquals(ActivityAwardTier.COMPLETED, snap("19", "Newly Completed", 20, 20, hardcore = 4, award = RaAwardKind.BEATEN_SOFTCORE).awardTier())
        // No word and no full set is no award, and an empty set has none.
        assertEquals(ActivityAwardTier.NONE, snap("20", "Nothing", 3, 20).awardTier())
        assertEquals(ActivityAwardTier.NONE, snap("14", "Empty Set", 0, 0, hardcore = 0).awardTier())
        // A game RA calls mastered is finished, so it is not also In progress: the three still split All exactly.
        val index = ActivityAchievementIndex(listOf(grown, pokemon))
        fun idsFor(status: ActivityAchievementStatus) = ids(ActivityAchievementFilter(status = status), index).toSet()
        assertEquals(setOf("13"), idsFor(ActivityAchievementStatus.MASTERED))
        assertEquals(setOf("1"), idsFor(ActivityAchievementStatus.IN_PROGRESS))
        assertTrue(grown.isFinished())
    }

    @Test
    fun `the labels say it in RA's words`() {
        assertEquals("Mastered", activityAwardLabel(40, 40, 40, RaAwardKind.MASTERED))
        assertEquals("Mastered", activityAwardLabel(40, 40, 40, null))
        assertEquals("Completed", activityAwardLabel(40, 40, 12, RaAwardKind.COMPLETED))
        assertEquals("Completed", activityAwardLabel(40, 40, 0, null))
        assertEquals("a hardcore beat is plain Beaten", "Beaten", activityAwardLabel(14, 40, 14, RaAwardKind.BEATEN_HARDCORE))
        assertEquals("a softcore beat says so", "Beaten (softcore)", activityAwardLabel(9, 40, 0, RaAwardKind.BEATEN_SOFTCORE))
        assertEquals(null, activityAwardLabel(9, 40, 0, null))
        assertEquals(
            listOf("All", "In progress", "Beaten", "Completed", "Mastered", "Recent"),
            ActivityAchievementStatus.entries.map { it.label },
        )
    }

    @Test
    fun `an achievement earned in both modes is counted once whatever the totals say`() {
        // 20 achievements, 12 earned in hardcore (each ALSO a softcore earn on RA's side): unlocked is the union, 20, and 12 sits INSIDE it.
        val both = snap("15", "Both Modes", 20, 20, hardcore = 12)
        assertEquals(20, both.unlocked)
        assertTrue(both.earnedHardcore <= both.unlocked)
        assertEquals("progress is 20 of 20, never 32 of 20", ActivityAwardTier.COMPLETED, both.awardTier())
        assertFalse("12 hardcore plus 20 earned must not read as all in hardcore", both.awardTier() == ActivityAwardTier.MASTERED)
    }

    @Test
    fun `recent is a most recent award within thirty days, and a game with no date is never recent`() {
        assertEquals(setOf("1", "3", "5"), idSet(ActivityAchievementFilter(status = ActivityAchievementStatus.RECENT)))
        val edge = ActivityAchievementIndex(
            listOf(
                snap("on", "On the line", 1, 5, awarded = now - ACTIVITY_RECENT_WINDOW_MS),
                snap("past", "Just past", 1, 5, awarded = now - ACTIVITY_RECENT_WINDOW_MS - 1L),
                snap("ahead", "Clock ahead", 1, 5, awarded = now + 60_000L),
                snap("none", "No date", 1, 5, awarded = null, checked = now, rowsKnown = true),
            ),
        )
        assertEquals(
            "thirty days is inside, one millisecond more is outside, a date a little ahead of this clock is recent, and a check time is not an award date",
            setOf("on", "ahead"),
            ids(ActivityAchievementFilter(status = ActivityAchievementStatus.RECENT), edge).toSet(),
        )
        assertEquals(30L * 24L * 60L * 60L * 1000L, ACTIVITY_RECENT_WINDOW_MS)
    }

    @Test
    fun `a status and a query and a console all have to agree`() {
        val filter = ActivityAchievementFilter(query = "e", status = ActivityAchievementStatus.IN_PROGRESS, console = "SNES")
        assertEquals(setOf("4", "5"), idSet(filter))
    }

    // ── consoles ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the console list holds each console in the data with its game count, A to Z, and no console without one`() {
        assertEquals(
            listOf(ActivityConsoleCount("Game Boy", 1), ActivityConsoleCount("Genesis/Mega Drive", 1), ActivityConsoleCount("SNES", 3)),
            index.consoles,
        )
        assertEquals("SNES (3)", index.consoles.last().label)
        val odd = ActivityAchievementIndex(
            listOf(snap("a", "A", 1, 2, console = "  "), snap("b", "B", 1, 2, console = " NES "), snap("c", "C", 1, 2, console = "NES"), snap("d", "D", 1, 2, console = "gba")),
        )
        assertEquals(
            "a blank console is no console, and names are trimmed so one console is counted once",
            listOf(ActivityConsoleCount("gba", 1), ActivityConsoleCount("NES", 2)),
            odd.consoles,
        )
    }

    @Test
    fun `a console narrows the list, and a console that is not in the data any more is dropped`() {
        assertEquals(setOf("2", "4", "5"), idSet(ActivityAchievementFilter(console = "SNES")))
        assertEquals(setOf("1"), idSet(ActivityAchievementFilter(console = "Game Boy")))
        val gone = ActivityAchievementFilter(console = "Atari 2600")
        assertNull("another account's console cannot filter this account's list", index.resolve(gone).console)
        assertEquals("so the list is whole, never an unexplained empty page", all.size, ids(gone).size)
        assertEquals(setOf("2", "4", "5"), activityAchievementResult(index, ActivityAchievementFilter(console = "SNES"), now).visible.map { it.gameId }.toSet())
    }

    // ── order ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the newest award comes first, and the games with no activity follow by title with accents and case folded`() {
        assertEquals(
            "newest award first; the games with no date follow by title",
            listOf("1", "5", "3", "2", "4", "6"),
            ids(ActivityAchievementFilter()),
        )
        val undated = ActivityAchievementIndex(listOf(snap("5", "Éclair Quest", 3, 10, console = "SNES"), chrono, mystery))
        assertEquals("Éclair sorts as eclair, between Chrono and Mystery", listOf("4", "5", "6"), ids(ActivityAchievementFilter(), undated))
    }

    @Test
    fun `a game's last play counts with its last unlock, a check of its rows does not, and the later of the two orders it`() {
        // A check's time, or the time a bulk read was made, says nothing about the game: it never outranks a real date (another game's 0 of 68 stood first).
        val checked = snap("rich", "Checked Game", 3, 9, checked = now, rowsKnown = true)
        val bulk = snap("bulk", "Bulk Game", 3, 9, checked = now, rowsKnown = false)
        val dated = snap("dated", "Dated Game", 3, 9, awarded = now - 2 * day, checked = now, rowsKnown = true)
        val played = snap("played", "Played Game", 0, 68, checked = now, rowsKnown = true).copy(lastPlayedAtMillis = now - day)
        val both = snap("both", "Both Game", 3, 9, awarded = now - 5 * day).copy(lastPlayedAtMillis = now - 3 * day)
        val earnedSince = snap("earned", "Earned Since", 4, 9, awarded = now - 60_000L).copy(lastPlayedAtMillis = now - 2 * day)
        val order = ids(ActivityAchievementFilter(), ActivityAchievementIndex(listOf(checked, bulk, dated, played, both, earnedSince)))
        assertEquals(
            "the unlock a minute ago, then yesterday's play, then 2 days ago, then the later of 5 days (unlock) and 3 (play); no activity last, by title",
            listOf("earned", "played", "dated", "both", "bulk", "rich"),
            order,
        )
        assertEquals(0L, checked.activityAtMillis())
        assertEquals(0L, bulk.activityAtMillis())
        assertEquals(now - 2 * day, dated.activityAtMillis())
        assertEquals(now - day, played.activityAtMillis())
        assertEquals("the later of the two", now - 3 * day, both.activityAtMillis())
        assertEquals("a play does not date the last UNLOCK", null, played.lastAwardedAtMillis)
        assertFalse("and a game only played is not Recent", played.isRecent(now))
    }

    @Test
    fun `the game just played stands first even beside a game with a fresher unlock date, and a fresh unlock beats an older play`() {
        val justPlayed = snap("now", "Just Played", 1, 68).copy(lastPlayedAtMillis = now)
        val oldUnlock = snap("old", "Old Unlock", 30, 40, awarded = now - 7 * day)
        val staleZero = snap("zero", "Stale Zero", 0, 68, checked = now, rowsKnown = true)
        assertEquals(listOf("now", "old", "zero"), ids(ActivityAchievementFilter(), ActivityAchievementIndex(listOf(staleZero, oldUnlock, justPlayed))))
        val justEarned = snap("earn", "Just Earned", 31, 40, awarded = now + 1_000L)
        assertEquals(listOf("earn", "now", "old", "zero"), ids(ActivityAchievementFilter(), ActivityAchievementIndex(listOf(staleZero, oldUnlock, justPlayed, justEarned))))
    }

    @Test
    fun `ties are broken by title and then by id, so the order never shuffles between recompositions`() {
        val same = ActivityAchievementIndex(listOf(snap("b", "Twin", 1, 2), snap("a", "Twin", 1, 2), snap("c", "Another", 1, 2)))
        assertEquals(listOf("c", "a", "b"), ids(ActivityAchievementFilter(), same))
    }

    // ── the count ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the count reads 8 of 47 while a filter narrows and 47 games while it does not`() {
        val many = ActivityAchievementIndex((1..47).map { snap("g$it", if (it <= 8) "Sonic $it" else "Other $it", 1, 5) })
        val narrowed = activityAchievementResult(many, ActivityAchievementFilter(query = "sonic"), now)
        assertEquals(8, narrowed.visible.size)
        assertEquals("8 of 47 games", activityAchievementCountText(narrowed))
        assertEquals("47 games", activityAchievementCountText(activityAchievementResult(many, ActivityAchievementFilter(), now)))
        assertEquals("0 of 47 games", activityAchievementCountText(activityAchievementResult(many, ActivityAchievementFilter(query = "nothing like it"), now)))
        assertEquals("1 game", activityAchievementCountText(activityAchievementResult(ActivityAchievementIndex(listOf(chrono)), ActivityAchievementFilter(), now)))
    }

    // ── the held state ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the holder starts at its default, keeps what it is given, and clearing empties it`() {
        val holder = ActivityAchievementFilterHolder()
        assertEquals(ActivityAchievementFilter(), holder.state.value)

        holder.setQuery("sonic")
        holder.setStatus(ActivityAchievementStatus.MASTERED)
        holder.setConsole("SNES")
        assertEquals(ActivityAchievementFilter("sonic", ActivityAchievementStatus.MASTERED, "SNES"), holder.state.value)
        assertTrue(holder.state.value.narrows)

        holder.clearFilters()
        assertEquals(ActivityAchievementFilter(), holder.state.value)
        assertFalse(holder.state.value.narrows)
    }

    @Test
    fun `a typed query is capped and a blank console is no console`() {
        val holder = ActivityAchievementFilterHolder()
        holder.setQuery("x".repeat(500))
        assertEquals(ACTIVITY_FILTER_QUERY_MAX, holder.state.value.query.length)
        holder.setConsole("  ")
        assertNull(holder.state.value.console)
        holder.setConsole("SNES")
        holder.setConsole(null)
        assertNull(holder.state.value.console)
    }

    @Test
    fun `a new holder is a cold start and begins at the default whatever another held`() {
        val first = ActivityAchievementFilterHolder()
        first.setQuery("sonic")
        assertEquals(ActivityAchievementFilter(), ActivityAchievementFilterHolder().state.value)
    }

    // ── what a screen reader says ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a chip says its state with its name and the search pill says its query`() {
        assertEquals("In progress, selected", activityFilterChipDescription("In progress", selected = true))
        assertEquals("In progress", activityFilterChipDescription("In progress", selected = false))
        assertEquals("Search achievements", activitySearchDescription(""))
        assertEquals("Search achievements", activitySearchDescription("   "))
        assertEquals("Search achievements, sonic", activitySearchDescription("sonic"))
        assertEquals("Show: All ▾", activityShowChipText(ActivityAchievementStatus.ALL))
        assertEquals("the chosen name alone once something is chosen", "In progress ▾", activityShowChipText(ActivityAchievementStatus.IN_PROGRESS))
        assertEquals("Completed ▾", activityShowChipText(ActivityAchievementStatus.COMPLETED))
        assertEquals("Show, All", activityShowChipDescription(ActivityAchievementStatus.ALL))
        assertEquals("Show, Mastered", activityShowChipDescription(ActivityAchievementStatus.MASTERED))
        assertEquals("Console, all consoles", activityConsoleChipDescription(null))
        assertEquals("Console, SNES", activityConsoleChipDescription("SNES"))
        assertEquals("SNES, 12 games, selected", activityConsoleItemDescription(ActivityConsoleCount("SNES", 12), selected = true))
        assertEquals("Game Boy, 1 game", activityConsoleItemDescription(ActivityConsoleCount("Game Boy", 1), selected = false))
    }
}
