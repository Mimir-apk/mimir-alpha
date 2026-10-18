package com.gruning.mimir.rewrite.core.ui

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Time wording for the Bottom's Activity page and shelf, beside [MimirCopy]: a live clock, a total, and "how long ago".
 *
 * **Never the word "playtime".** The store forbids it: Mimir measures the time between a launch and the return to Mimir (and, for an external
 * game, says so), not the time a game ran. These helpers produce numbers and units only; the caller names what is counted ("Session time").
 *
 * The style is Main's: abbreviations that do not take a plural ("18 h 40 min", as `homeShelfExternalSessionElapsedLabel` and the music
 * rows' "5 min ago" / "3 h ago" write it, there in capitals for a fact row), and `musicElapsedLabel`'s "1 day ago" / "N days ago" rule for days, with the
 * singular day spelled "yesterday" as `relativePlayed` does. Phrases come back lowercase for the caller to lead with its own words
 * ("Last played · yesterday").
 */
object MimirTimeCopy {

    private const val MsPerSecond = 1_000L
    private const val MsPerMinute = 60_000L

    /**
     * A running clock: `h:mm:ss`, the hours unpadded and growing past 24 ("0:00:00", "0:00:59", "1:00:00", "26:05:09"). Negative input reads as zero.
     * Seconds are floored, so the clock never runs ahead of the elapsed time.
     */
    fun clock(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis.coerceAtLeast(0L) / MsPerSecond
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    /**
     * A total: whole minutes (rounded to the nearest, half up, as Main's elapsed fact row rounds), then hours and minutes. "18 h 40 min", "2 h",
     * "40 min", "0 min" for none, and "Less than a minute" for something under half a minute. Hours keep growing past a day ("26 h 5 min"): a total
     * is not a date, so there is no "days" unit to disagree with the "days ago" below. Zero and negative input read "0 min"; every non-negative Long, up
     * to Long.MAX_VALUE, rounds without overflow.
     */
    fun total(elapsedMillis: Long): String {
        if (elapsedMillis <= 0L) return "0 min"
        // Divide first: adding the half minute to a value near Long.MAX_VALUE would overflow and read as nothing counted.
        val totalMinutes = elapsedMillis / MsPerMinute + if (elapsedMillis % MsPerMinute >= MsPerMinute / 2) 1L else 0L
        if (totalMinutes <= 0L) return "Less than a minute"
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours <= 0L -> "$minutes min"
            minutes <= 0L -> "$hours h"
            else -> "$hours h $minutes min"
        }
    }

    /**
     * How many calendar days ago [thenMillis] was, by [zone]'s own midnights: 23:59 yesterday is "yesterday" at 00:01 today, though two minutes
     * have passed. "today", "yesterday", then "N days ago" (N from 2, so the plural is always right). A timestamp in the future, from a clock
     * that moved back, reads as today. The phrases are lowercase: a caller that starts a sentence with one capitalises it ("Yesterday").
     */
    fun daysAgo(thenMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val days = calendarDaysAgo(thenMillis, nowMillis, zone)
        return when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
    }

    /**
     * Whole local calendar days from [thenMillis] to [nowMillis], counted between [zone]'s own midnights and never negative (a clock that moved
     * back reads as 0). The one day count every "played N days ago" label stands on: [daysAgo] words it, and the Library's `relativePlayed`
     * buckets it into days, weeks, months and years. A 24-hour slab is not a day: 13:28 yesterday is 21.5 hours ago and still yesterday, and a
     * 23 or 25 hour DST day is still one day.
     */
    fun calendarDaysAgo(thenMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val then = Instant.ofEpochMilli(thenMillis).atZone(zone).toLocalDate()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(then, now).coerceAtLeast(0L)
    }
}
