package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.MimirLocalDay
import com.gruning.mimir.rewrite.core.ui.MimirTimeCopy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/*
 * The Activity page's resting layout, decided without a composition: how the page is carved, which game each tile shows, how the ranked
 * tile is sorted, what the weekly bars say, and when the week's window rolls over. Pure, so every rule here is a test rather than a claim.
 *
 * Everything here is a LAUNCH count or a launch date. Mimir measures the time between a launch and the return to Mimir, never how long a
 * game ran, so no function in this file ranks, sorts or words anything as playtime.
 */

// ── The bento's zones ────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** The gap between tiles, both ways. */
internal const val ActivityBentoGapDp = 12f

/** The page's side inset: the Bottom panel is 538 dp wide, so the body is 502. */
internal const val ActivityPageInsetDp = 18f

/** The page's top inset, above the header. */
internal const val ActivityPageTopDp = 10f

/** The dock handle's reserve at the bottom of the panel, which the body never draws under. */
internal const val ActivityDockReserveDp = 36f

/** The right column (This week over Most launched). The left (Last played over the media tile) takes the rest of the body. */
internal const val ActivityRightColumnDp = 232f

/**
 * Most launched's height: its pad (10), the 48 dp sort header, three 50 dp rows and its pad (10). A row is 50 dp so the finger target
 * clears 48 dp, and the tile never shrinks below what three of them need.
 */
internal const val ActivityRankedTileDp = 10f + 48f + 3f * 50f + 10f

/**
 * The media / Been a while tile's height. Media: its pad (10), the 56 dp art row (the art beside the status eyebrow and the title), the 48 dp
 * seek lane, the 52 dp transport row (Play/Pause is 52, Previous and Next 48) and its pad (10), which is 176. Been a while fits the same
 * height: its pad, the eyebrow (18), 6, the 112 dp cover beside a column of title, line and a 48 dp Open pill, and its pad.
 */
internal const val ActivityLowerLeftTileDp = 176f

/** Smallest the two flexing tiles are allowed to be: below these the page scrolls instead of squeezing them. */
internal const val ActivityLastPlayedMinDp = 160f
internal const val ActivityWeekMinDp = 120f

/** The tiles' sizes in dp. Each is a MINIMUM: at a large font scale a tile grows to fit its text and the page scrolls. */
internal data class ActivityBentoZones(
    val bodyWidthDp: Float,
    val bodyHeightDp: Float,
    val leftWidthDp: Float,
    val rightWidthDp: Float,
    val gapDp: Float,
    /** Last played / Now playing, over the left column. */
    val lastPlayedHeightDp: Float,
    /** This week, over the right column. */
    val weekHeightDp: Float,
    /** Most launched, under This week. */
    val rankedHeightDp: Float,
    /** The media tile or Been a While, under Last played. */
    val lowerLeftHeightDp: Float,
) {
    /** The two columns' heights: equal to the body when it is tall enough, taller (so the page scrolls) when it is not. */
    val leftColumnHeightDp: Float get() = lastPlayedHeightDp + gapDp + lowerLeftHeightDp
    val rightColumnHeightDp: Float get() = weekHeightDp + gapDp + rankedHeightDp
}

/**
 * Carves the body ([bodyWidthDp] x [bodyHeightDp]: the panel less the inset, the header, its clearance and the dock reserve) into the four
 * tiles. Most launched and the lower-left tile keep the heights their rows and controls need; Last played and This week take what is left of
 * their columns, so both columns end on the same line. When the body is too short for the minimums the columns run past it (the page scrolls)
 * rather than the tiles being squeezed.
 */
internal fun solveActivityBento(bodyWidthDp: Float, bodyHeightDp: Float): ActivityBentoZones {
    val gap = ActivityBentoGapDp
    val right = minOf(ActivityRightColumnDp, (bodyWidthDp - gap) / 2f).coerceAtLeast(0f)
    val left = (bodyWidthDp - gap - right).coerceAtLeast(0f)
    return ActivityBentoZones(
        bodyWidthDp = bodyWidthDp,
        bodyHeightDp = bodyHeightDp,
        leftWidthDp = left,
        rightWidthDp = right,
        gapDp = gap,
        lastPlayedHeightDp = maxOf(ActivityLastPlayedMinDp, bodyHeightDp - gap - ActivityLowerLeftTileDp),
        weekHeightDp = maxOf(ActivityWeekMinDp, bodyHeightDp - gap - ActivityRankedTileDp),
        rankedHeightDp = ActivityRankedTileDp,
        lowerLeftHeightDp = ActivityLowerLeftTileDp,
    )
}

// ── Most launched: the ranking and its sort ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * How the ranked tile orders games. [optionLabel] is the sort chip's menu entry, [headerLabel] is what the tile calls itself under that
 * order, so the tile never says "Most launched" over a list sorted by something else.
 *
 * There is no sort by time: the store measures a launch-to-return span, not play time, and the wording law forbids ranking by it.
 */
internal enum class ActivitySort(val optionLabel: String, val headerLabel: String) {
    LAUNCHES("Launches", "Most launched"),
    LAST_PLAYED("Last played", "Last played"),
    FIRST_PLAYED("First played", "First played"),
}

/** How many games the ranked tile lists. */
internal const val ActivityRankedRows = 3

private fun titleKey(row: GameRow): String = row.title.lowercase(Locale.ROOT)

/** Null last, whichever way the present values run. */
private fun <T : Comparable<T>> nullsLast(descending: Boolean): Comparator<T?> =
    Comparator { a, b ->
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            descending -> b.compareTo(a)
            else -> a.compareTo(b)
        }
    }

/**
 * The first [limit] of [games] under [sort]. A game with no recorded launch has no last or first played date and goes after every dated
 * one. Ties fall to the next most meaningful field, then the title, then the id, so the order is total and the same on every run.
 *
 *  - [ActivitySort.LAUNCHES]: launches high to low, then most recently played.
 *  - [ActivitySort.LAST_PLAYED]: most recent first.
 *  - [ActivitySort.FIRST_PLAYED]: OLDEST first, so the list reads as the games you have had longest. First played can only be as old as the
 *    launch history that is still stored (see `GameRow.firstPlayedMillis`).
 */
internal fun activityRank(games: List<GameRow>, sort: ActivitySort, limit: Int = ActivityRankedRows): List<GameRow> {
    val byLaunches = compareByDescending<GameRow> { it.playCount }
    val byLast = Comparator<GameRow> { a, b -> nullsLast<Long>(descending = true).compare(a.lastPlayedMillis, b.lastPlayedMillis) }
    val byFirst = Comparator<GameRow> { a, b -> nullsLast<Long>(descending = false).compare(a.firstPlayedMillis, b.firstPlayedMillis) }
    val order = when (sort) {
        ActivitySort.LAUNCHES -> byLaunches.then(byLast)
        ActivitySort.LAST_PLAYED -> byLast.then(byLaunches)
        ActivitySort.FIRST_PLAYED -> byFirst.then(byLaunches)
    }.thenBy { titleKey(it) }.thenBy { it.gameId.value }
    return games.sortedWith(order).take(limit)
}

/** "1 launch", "14 launches". */
internal fun activityLaunchesLabel(count: Int): String = if (count == 1) "1 launch" else "$count launches"

/** The first letter up, for a phrase that starts a line ("yesterday" becomes "Yesterday"). */
internal fun activityCapitalised(text: String): String =
    text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

/**
 * The value line under a ranked row: what the current sort ranks by, in launch terms only. A game with no date under a date sort says so
 * rather than leaving the line blank.
 */
internal fun activityRankedValue(game: GameRow, sort: ActivitySort, nowMillis: Long, zone: ZoneId): String = when (sort) {
    ActivitySort.LAUNCHES -> activityLaunchesLabel(game.playCount)
    ActivitySort.LAST_PLAYED -> game.lastPlayedMillis
        ?.let { activityCapitalised(MimirTimeCopy.daysAgo(it, nowMillis, zone)) } ?: "Not played yet"
    ActivitySort.FIRST_PLAYED -> game.firstPlayedMillis
        ?.let { activityCapitalised(MimirTimeCopy.daysAgo(it, nowMillis, zone)) } ?: "Not played yet"
}

// ── Been a while ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** How many of the most recently launched games the pick looks across. */
internal const val ActivityBeenAWhileWindow = 15

/** A game last launched this many calendar days ago or more has "been a while"; the eyebrow says "Been a while" only at or past this. */
internal const val ActivityBeenAWhileDays = 7L

internal enum class BeenAWhileKind(val eyebrow: String) {
    /** Launched, and long enough ago to say so. */
    BEEN_A_WHILE("Been a while"),

    /** Launched, but everything in the window is recent: the product's existing Activity wording, with no claim about a "while". */
    RECENTLY_PLAYED("Recently played"),

    /** No launch history to speak of: a game from the shelf, with no claim about time. */
    FROM_YOUR_SHELF("From your shelf"),
}

internal data class BeenAWhilePick(val game: GameRow, val kind: BeenAWhileKind, val daysSince: Long?) {
    /** The line under the title. Empty when the pick makes no claim about time (a shelf game that has never been launched says so). */
    fun line(nowMillis: Long, zone: ZoneId): String = when {
        game.lastPlayedMillis != null -> "Last played " + MimirTimeCopy.daysAgo(game.lastPlayedMillis!!, nowMillis, zone)
        game.playCount <= 0 -> "Not played yet"
        else -> ""
    }
}

private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

/** Whole local calendar days from [thenMillis] to [nowMillis], never negative (a clock that went back reads as today). */
internal fun activityDaysSince(thenMillis: Long, nowMillis: Long, zone: ZoneId): Long =
    MimirTimeCopy.calendarDaysAgo(thenMillis, nowMillis, zone)

/**
 * Which game "Been a while" shows today, deterministic per local calendar day (it does not change on a recomposition or a re-entry, only when
 * the date does).
 *
 *  1. Over the [history] window ([ActivityBeenAWhileWindow] most recently launched games, most recent first, less [exclude]): the games last
 *     launched [ActivityBeenAWhileDays] or more days ago, in a stable order, one chosen by the date. That is "Been a while".
 *  2. Launched games but none that old: the one launched longest ago, worded "Recently played" (the Activity page's own wording), never as a "while".
 *  3. Nothing launched (outside [exclude]): a game from [shelf], chosen the same way by the date, worded "From your shelf" with no time claim.
 *
 * Null when there is nothing to show. [exclude] keeps the pick off the game another tile already shows.
 */
internal fun pickBeenAWhile(
    history: List<GameRow>,
    shelf: List<GameRow>,
    exclude: Set<GameId>,
    nowMillis: Long,
    zone: ZoneId,
): BeenAWhilePick? {
    val today = localDate(nowMillis, zone).toEpochDay()
    val window = history.asSequence()
        .filter { it.gameId !in exclude && it.lastPlayedMillis != null }
        .take(ActivityBeenAWhileWindow)
        .toList()
    val stable = compareBy<GameRow> { it.lastPlayedMillis }.thenBy { titleKey(it) }.thenBy { it.gameId.value }
    val stale = window.filter { activityDaysSince(it.lastPlayedMillis!!, nowMillis, zone) >= ActivityBeenAWhileDays }.sortedWith(stable)
    if (stale.isNotEmpty()) {
        val game = stale[Math.floorMod(today, stale.size.toLong()).toInt()]
        return BeenAWhilePick(game, BeenAWhileKind.BEEN_A_WHILE, activityDaysSince(game.lastPlayedMillis!!, nowMillis, zone))
    }
    window.sortedWith(stable).firstOrNull()?.let { game ->
        return BeenAWhilePick(game, BeenAWhileKind.RECENTLY_PLAYED, activityDaysSince(game.lastPlayedMillis!!, nowMillis, zone))
    }
    val pool = shelf.filter { it.gameId !in exclude }
        .distinctBy { it.gameId }
        .sortedWith(compareBy<GameRow> { titleKey(it) }.thenBy { it.gameId.value })
    if (pool.isEmpty()) return null
    return BeenAWhilePick(pool[Math.floorMod(today, pool.size.toLong()).toInt()], BeenAWhileKind.FROM_YOUR_SHELF, null)
}

// ── This week ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** The launches in [week] (oldest first, today last), summed. */
internal fun activityWeekTotal(week: List<DailyLaunchCount>): Int = week.sumOf { it.count }

/** The tile's headline: "24 launches" or "None yet". Launches, never playtime. */
internal fun activityWeekHeadline(week: List<DailyLaunchCount>): String =
    activityWeekTotal(week).let { if (it <= 0) "None yet" else activityLaunchesLabel(it) }

/**
 * The ONE spoken summary for the bars: the weekly total, then each day. The bars are read-only, so a screen reader gets this node and none of
 * the seven bars or their letters.
 */
internal fun activityWeekSpoken(week: List<DailyLaunchCount>, locale: Locale = Locale.getDefault()): String {
    val total = activityWeekTotal(week)
    val lead = if (total <= 0) "Launches this week: none yet" else "Launches this week: $total"
    if (total <= 0) return lead
    val days = week.joinToString(", ") { "${it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${it.count}" }
    return "$lead ($days)"
}

/** One letter for a bar's label. */
internal fun activityDayLetter(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)

// ── The midnight roll ────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** Milliseconds from [nowMillis] to the next local midnight in [zone] (at least 1), cut by the zone's own rules, so a 23 or 25 hour day is right. */
internal fun millisUntilNextLocalMidnight(nowMillis: Long, zone: ZoneId): Long = MimirLocalDay.millisUntilNextMidnight(nowMillis, zone)

/**
 * The local date now, then again each time local midnight passes. [wait] is the sleep (virtual time in a test).
 *
 * The launch history flow re-emits when a launch is recorded, not when the clock crosses midnight, so a page left open past midnight would
 * keep yesterday's window (and "N days ago") until something else moved. This is the one tick that fixes that, and it lives only while the
 * Activity page is composed: nothing runs while it is not. It is [MimirLocalDay.ticker], which Main's Library "Played …" labels share.
 */
internal fun activityDayTicker(
    nowMillis: () -> Long,
    zone: () -> ZoneId,
    wait: suspend (Long) -> Unit = { delay(it) },
): Flow<LocalDate> = MimirLocalDay.ticker(nowMillis, zone, wait)

/**
 * The weekly launch counts, re-subscribed whenever the date turns over so the window rolls forward at midnight. [launches] is the
 * repository's `observeLaunchesPerDay`; a new day cancels the old subscription and reads a fresh window. A page that is entered fresh
 * subscribes fresh by being composed fresh (the Activity scene leaves composition when the page does).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun activityWeekFlow(
    launches: () -> Flow<List<DailyLaunchCount>>,
    days: Flow<LocalDate>,
): Flow<List<DailyLaunchCount>> = days.flatMapLatest { launches() }

// ── The shelf pool ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The games the page already shows, so no tile repeats one. The top-left tile shows the running game (else the last played one), and Most launched
 * shows its three rows under [sort]. Pure: it takes the launched list as the page reads it, most recently played first.
 */
internal fun activityVisibleGameIds(launched: List<GameRow>, liveGameId: GameId?, sort: ActivitySort): Set<GameId> = buildSet {
    (liveGameId ?: launched.firstOrNull()?.gameId)?.let { add(it) }
    activityRank(launched, sort).forEach { add(it.gameId) }
}

/**
 * Every game that COULD be on the page under some sort, for the one read that cannot know which sort the player has chosen (the shelf pool is
 * subscribed outside the page): the running (or last played) game and the top rows of all three orders. A pool kept clear of all of them is clear of
 * the shown ones whatever the sort.
 */
internal fun activityPossiblyVisibleGameIds(launched: List<GameRow>, liveGameId: GameId?): Set<GameId> = buildSet {
    ActivitySort.entries.forEach { addAll(activityVisibleGameIds(launched, liveGameId, it)) }
}

/**
 * Whether the shelf pool must be read for "Been a while": when no launched game is left to draw on once every game the page already shows is
 * taken out ([excluded], see [activityVisibleGameIds]). The count of launched games is NOT the test: with three launched games all in Most launched
 * there is nothing left, and with twenty there is always something. With nothing left, the pool (favourites first, else one platform's games)
 * keeps the tile from repeating a visible game or claiming "nothing here" while games exist.
 */
internal fun activityNeedsShelfPool(launched: List<GameRow>, excluded: Set<GameId>): Boolean =
    launched.none { it.gameId !in excluded && it.lastPlayedMillis != null }

/**
 * Games for Been a while to draw from when no launched game is left to show: the favourites if any of them is not [exclude]d (a cheap read),
 * otherwise the games of one platform, the platform chosen by the date ([dayEpoch]) so it is stable through the day. Never the whole library
 * (its read holds the database's write connection and the Bottom is always up), and the caller subscribes to this only while it is needed.
 *
 * When the exclusions exhaust the favourites (the only favourite is the game another tile shows), it falls through to the rest of the shelf rather
 * than answering with games that can only be refused. The platform is the day's, and if every game of it is excluded too, the next platform that
 * has one is read; only a shelf with nothing but excluded games answers with them, and the tile then truthfully has nothing to add.
 *
 * [needed] is how many free games the tiles that draw on the pool want: two when nothing has ever been launched (the top-left tile and Been a while
 * each take one, and the second must not find only the first's game), else one. Fewer free favourites than that fall through to the shelf too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun activityShelfPoolFlow(
    favourites: Flow<List<GameRow>>,
    platforms: Flow<List<PlatformRow>>,
    gamesOf: (PlatformId) -> Flow<List<GameRow>>,
    dayEpoch: Long,
    exclude: Set<GameId>,
    needed: Int = 1,
): Flow<List<GameRow>> = favourites.flatMapLatest { favs ->
    if (favs.count { it.gameId !in exclude } >= needed) {
        flowOf(favs)
    } else {
        platforms.flatMapLatest { rows ->
            val withGames = rows.filter { it.gameCount > 0 }.sortedBy { it.platformId.value }
            if (withGames.isEmpty()) {
                flowOf(favs)
            } else {
                val start = Math.floorMod(dayEpoch, withGames.size.toLong()).toInt()
                val order = withGames.drop(start) + withGames.take(start)
                // The favourites that are left still count: the rest of the shelf is added to them, never swapped for them.
                firstPlatformWithEnough(order, 0, gamesOf, exclude, needed, favs)
            }
        }
    }
}

/**
 * The walk across the day's platforms: each one adds its free games to [have] (the favourites that are left, then what earlier platforms gave) until
 * [needed] free games are held, so a platform that gave one game and left the second to the next is not forgotten when the next gives its own. On the
 * last platform it ends with that platform's games whatever they are, so a shelf of nothing but excluded games still answers (and the pick refuses them).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun firstPlatformWithEnough(
    order: List<PlatformRow>,
    index: Int,
    gamesOf: (PlatformId) -> Flow<List<GameRow>>,
    exclude: Set<GameId>,
    needed: Int,
    have: List<GameRow>,
): Flow<List<GameRow>> = gamesOf(order[index].platformId).flatMapLatest { games ->
    val merged = (have + games.filter { it.gameId !in exclude }).distinctBy { it.gameId }
    when {
        merged.count { it.gameId !in exclude } >= needed -> flowOf(merged)
        index == order.lastIndex -> flowOf((merged + games).distinctBy { it.gameId })
        else -> firstPlatformWithEnough(order, index + 1, gamesOf, exclude, needed, merged)
    }
}

/** [activityShelfPoolFlow] over the library repository's own reads. */
internal fun activityShelfPoolFlow(repository: LibraryRepository, dayEpoch: Long, exclude: Set<GameId>, needed: Int = 1): Flow<List<GameRow>> =
    activityShelfPoolFlow(
        favourites = repository.observeFavorites(),
        platforms = repository.observePlatforms(),
        gamesOf = { platformId -> repository.observeGames(platformId) },
        dayEpoch = dayEpoch,
        exclude = exclude,
        needed = needed,
    )
