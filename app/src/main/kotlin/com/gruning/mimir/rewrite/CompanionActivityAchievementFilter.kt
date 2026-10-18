package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Activity achievement page's filter. Everything here is PURE and works
 * on the snapshot the page already holds: it starts no request and reads no clock but the one it is handed.
 *
 * A status is a single choice, in RetroAchievements' own words: [ALL]; [IN_PROGRESS] (the game still has something to earn: earned < total); [BEATEN] (RA reports a
 * beaten award, softcore or hardcore, and the game is not finished); [COMPLETED] (every achievement earned in softcore or in both modes, but not all in hardcore);
 * [MASTERED] (every achievement earned in hardcore); and [RECENT] (RA's own `MostRecentAwardedDate` is inside the last [ACTIVITY_RECENT_WINDOW_MS]). In progress,
 * Completed and Mastered split All exactly, so their counts always add up; Beaten is the beaten games among the unfinished ones.
 */
internal enum class ActivityAchievementStatus(val label: String) {
    ALL("All"),
    IN_PROGRESS("In progress"),
    BEATEN("Beaten"),
    COMPLETED("Completed"),
    MASTERED("Mastered"),
    RECENT("Recent"),
}

/** Thirty days: how far back "Recent" looks at a game's most recent award. */
internal const val ACTIVITY_RECENT_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1000L

/** More than a title is ever long: a typed query is clipped here so a held key cannot grow the state without bound. */
internal const val ACTIVITY_FILTER_QUERY_MAX = 64

/**
 * What the player has asked to see. [console] is the console's name as the bulk read gave it (the same string the console list shows), or null for every
 * console. There is one order and no choice of it: the game with the newest activity (the later of its last unlock and its last play) comes first.
 */
internal data class ActivityAchievementFilter(
    val query: String = "",
    val status: ActivityAchievementStatus = ActivityAchievementStatus.ALL,
    val console: String? = null,
) {
    /** True while anything narrows the list: a query, a status other than All, or a console. */
    val narrows: Boolean get() = query.isNotBlank() || status != ActivityAchievementStatus.ALL || console != null

    /** The same ask with nothing narrowing it. */
    fun cleared(): ActivityAchievementFilter = copy(query = "", status = ActivityAchievementStatus.ALL, console = null)
}

/**
 * The filter, held for the PROCESS: it survives a page swipe, the pane being parked and the hub being left and re-entered, and a cold start begins it at its
 * default because a new process builds a new holder. It is not persisted to disk. Each change is one atomic update; reading it never starts work.
 */
internal class ActivityAchievementFilterHolder(initial: ActivityAchievementFilter = ActivityAchievementFilter()) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<ActivityAchievementFilter> = mutable.asStateFlow()

    fun setQuery(query: String) = mutable.update { it.copy(query = query.take(ACTIVITY_FILTER_QUERY_MAX)) }

    fun setStatus(status: ActivityAchievementStatus) = mutable.update { it.copy(status = status) }

    fun setConsole(console: String?) = mutable.update { it.copy(console = console?.takeIf(String::isNotBlank)) }

    fun clearFilters() = mutable.update { it.cleared() }

    /** The sink is a process singleton, so a test names its own holder; this is the production one. */
    companion object {
        val process = ActivityAchievementFilterHolder()
    }
}

/** One console in the bulk data and how many of the page's games it holds. */
internal data class ActivityConsoleCount(val name: String, val count: Int) {
    /** RetroAchievements' own words with the count, "SNES (12)". The console list prints the lattice's name for the console instead (see [ActivityConsoleLook]). */
    val label: String get() = "$name ($count)"
}

/** A title folded for matching: accents dropped, case ignored, runs of white space made one. "Pokémon" and "POKEMON" fold alike. */
internal fun activityTitleFold(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    val builder = StringBuilder(decomposed.length)
    var lastSpace = true
    for (ch in decomposed) {
        val type = Character.getType(ch)
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) continue
        if (ch.isWhitespace()) {
            if (!lastSpace) builder.append(' ')
            lastSpace = true
        } else {
            builder.append(ch)
            lastSpace = false
        }
    }
    return builder.toString().trimEnd().lowercase(Locale.ROOT)
}

/** The title the page shows for a game, and so the title a query is matched against. */
internal fun CompanionActivityAchievementSnapshot.displayTitle(): String = gameTitle?.takeIf(String::isNotBlank) ?: gameId

/** Finished: Mastered or Completed, by RA's award or by the counts. A finished game is not In progress. */
internal fun CompanionActivityAchievementSnapshot.isFinished(): Boolean = awardTier().let { it == ActivityAwardTier.MASTERED || it == ActivityAwardTier.COMPLETED }

/** How far a game has been taken, in RetroAchievements' terms, highest first. [NONE] is a game with no award yet. */
internal enum class ActivityAwardTier { MASTERED, COMPLETED, BEATEN, NONE }

/**
 * The tier for one game: RetroAchievements' own award word FIRST ([awardKind]: it is what RA shows on the game, and a set that grew since keeps its mastery), then the
 * counts, and the higher of the two stands (the counts see an unlock the award has not caught up with yet). By the counts, Mastered is every achievement earned in
 * HARDCORE and Completed is every achievement earned (in softcore, or in both modes) but not all in hardcore; Beaten is only ever RA's word. An achievement earned in both
 * modes is one achievement ([unlocked] already counts it once), so nothing here can count it twice.
 */
internal fun activityAwardTier(unlocked: Int, total: Int, earnedHardcore: Int, awardKind: RaAwardKind?): ActivityAwardTier {
    val byAward = when (awardKind) {
        RaAwardKind.MASTERED -> ActivityAwardTier.MASTERED
        RaAwardKind.COMPLETED -> ActivityAwardTier.COMPLETED
        RaAwardKind.BEATEN_HARDCORE, RaAwardKind.BEATEN_SOFTCORE -> ActivityAwardTier.BEATEN
        null -> ActivityAwardTier.NONE
    }
    val byCount = when {
        total > 0 && earnedHardcore >= total -> ActivityAwardTier.MASTERED
        total > 0 && unlocked >= total -> ActivityAwardTier.COMPLETED
        else -> ActivityAwardTier.NONE
    }
    // The tiers are declared highest first.
    return if (byAward.ordinal <= byCount.ordinal) byAward else byCount
}

internal fun CompanionActivityAchievementSnapshot.awardTier(): ActivityAwardTier = activityAwardTier(unlocked, total, earnedHardcore, awardKind)

/** The word a card shows for a game's award, or null for none: "Mastered", "Completed", "Beaten" (hardcore) or "Beaten (softcore)". */
internal fun activityAwardLabel(unlocked: Int, total: Int, earnedHardcore: Int, awardKind: RaAwardKind?): String? =
    when (activityAwardTier(unlocked, total, earnedHardcore, awardKind)) {
        ActivityAwardTier.MASTERED -> "Mastered"
        ActivityAwardTier.COMPLETED -> "Completed"
        ActivityAwardTier.BEATEN -> if (awardKind == RaAwardKind.BEATEN_SOFTCORE) "Beaten (softcore)" else "Beaten"
        ActivityAwardTier.NONE -> null
    }

/**
 * When the game last saw activity, for the page's one order (newest first): the LATER of RA's own date for its most recent award and the library's last play, else never.
 * A check's own time is not activity: a game whose rows were merely read has not been played or earned in, and used to leapfrog the game that was (another
 * game's 0 of 68 stood first after a real unlock).
 */
internal fun CompanionActivityAchievementSnapshot.activityAtMillis(): Long = maxOf(lastAwardedAtMillis ?: 0L, lastPlayedAtMillis ?: 0L)

/**
 * Recent: the game's most recent award is no older than the window. A date AHEAD of this clock (RA's server and the handheld disagree by a little) is as recent
 * as it gets. A game with no known award date is never Recent, whatever else is known about it.
 */
internal fun CompanionActivityAchievementSnapshot.isRecent(nowMillis: Long): Boolean =
    lastAwardedAtMillis?.let { it >= nowMillis - ACTIVITY_RECENT_WINDOW_MS } == true

/**
 * The page's games indexed ONCE per change of the list: each title folded once and the console counts taken once, so a keystroke only runs substring checks
 * over strings that already exist.
 */
internal class ActivityAchievementIndex(val snapshots: List<CompanionActivityAchievementSnapshot>) {
    private val folded: List<String> = snapshots.map { it.displayTitle().let(::activityTitleFold) }

    private val consoleCounts: Map<String, Int> = snapshots
        .mapNotNull { it.consoleName?.trim()?.takeIf(String::isNotEmpty) }
        .groupingBy { it }
        .eachCount()

    /** How each console present is called and drawn (the lattice's name for its platform), by RetroAchievements' name, which is what the filter holds. */
    val consoleLooks: Map<String, ActivityConsoleLook> = activityConsoleLooks(consoleCounts.keys)

    /** The consoles present, A to Z by RetroAchievements' name, each with its game count. A game whose console is unknown is in no console, only in All consoles. */
    val consoles: List<ActivityConsoleCount> = consoleCounts
        .map { (name, count) -> ActivityConsoleCount(name, count) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ActivityConsoleCount::name))

    /** The filter with a console that is not in the data any more (another account's) taken out: nothing may be filtered by a console the list cannot show. */
    fun resolve(filter: ActivityAchievementFilter): ActivityAchievementFilter =
        if (filter.console != null && consoles.none { it.name == filter.console }) filter.copy(console = null) else filter

    /** The games the (resolved) [filter] lets through, in its order. */
    fun apply(filter: ActivityAchievementFilter, nowMillis: Long): List<CompanionActivityAchievementSnapshot> {
        val tokens = activityTitleFold(filter.query).split(' ').filter(String::isNotEmpty)
        val picked = ArrayList<Int>(snapshots.size)
        for (i in snapshots.indices) {
            val game = snapshots[i]
            if (!tokens.all { folded[i].contains(it) }) continue
            val statusOk = when (filter.status) {
                ActivityAchievementStatus.ALL -> true
                ActivityAchievementStatus.IN_PROGRESS -> !game.isFinished()
                ActivityAchievementStatus.BEATEN -> game.awardTier() == ActivityAwardTier.BEATEN
                ActivityAchievementStatus.COMPLETED -> game.awardTier() == ActivityAwardTier.COMPLETED
                ActivityAchievementStatus.MASTERED -> game.awardTier() == ActivityAwardTier.MASTERED
                ActivityAchievementStatus.RECENT -> game.isRecent(nowMillis)
            }
            if (!statusOk) continue
            if (filter.console != null && game.consoleName?.trim() != filter.console) continue
            picked += i
        }
        val order = compareByDescending<Int> { snapshots[it].activityAtMillis() }.thenBy { folded[it] }.thenBy { snapshots[it].gameId }
        return picked.sortedWith(order).map { snapshots[it] }
    }
}

/** What the page shows for one filter: the resolved filter, the games, and how many there were before it. */
internal data class ActivityAchievementResult(
    val filter: ActivityAchievementFilter,
    val visible: List<CompanionActivityAchievementSnapshot>,
    val totalGames: Int,
)

internal fun activityAchievementResult(index: ActivityAchievementIndex, filter: ActivityAchievementFilter, nowMillis: Long): ActivityAchievementResult {
    val resolved = index.resolve(filter)
    return ActivityAchievementResult(resolved, index.apply(resolved, nowMillis), index.snapshots.size)
}

/** "8 of 47 games" while a filter narrows the list, "47 games" while it does not, "1 game" for one. */
internal fun activityAchievementCountText(result: ActivityAchievementResult): String {
    val noun = if (result.totalGames == 1) "game" else "games"
    return if (result.filter.narrows) "${result.visible.size} of ${result.totalGames} games" else "${result.totalGames} $noun"
}
