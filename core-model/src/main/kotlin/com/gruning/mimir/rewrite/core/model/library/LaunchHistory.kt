package com.gruning.mimir.rewrite.core.model.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How many local calendar days the Activity page's weekly bars span. */
const val LAUNCH_HISTORY_DAYS: Int = 7

/**
 * How many launches happened on one local calendar [date].
 *
 * A "launch" is a successful dispatch: a `launch_proofs` row whose outcome is `intent_sent`, the same rows
 * [GameRow.playCount] counts. It is a launch and nothing more. It says nothing about how long the game ran, so a surface
 * must never call it playtime.
 */
data class DailyLaunchCount(val date: LocalDate, val count: Int)

/**
 * The first instant of the oldest of the [days] local calendar days ending with the day that contains [nowMillis], in
 * [zone]. A launch stamped before this belongs to no bar.
 *
 * Built from the calendar date and the zone's own rules, never from "now minus N x 24 hours": a day is 23 or 25 hours long
 * when the clocks change, and a zone whose clocks skip local midnight starts that day at the first instant that exists.
 */
fun launchHistoryStartMillis(nowMillis: Long, zone: ZoneId, days: Int = LAUNCH_HISTORY_DAYS): Long {
    require(days >= 1) { "days must be at least 1, was $days" }
    return localDate(nowMillis, zone).minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * Buckets launch timestamps into the last [days] local calendar days ending with the day that contains [nowMillis], in
 * [zone] (the device's time zone when the caller asked; this function never reads a clock or a default zone itself).
 *
 * Always returns exactly [days] entries, OLDEST FIRST and today LAST, one per calendar day, a day with no launches present
 * as 0, so a surface can draw its bars without a gap check. A timestamp that falls before the first day, or whose INSTANT is
 * after [nowMillis] (a device clock that was wrong when the row was written: 18:00 stamped at noon is not a launch that has
 * happened yet, even though it is today's date), belongs to no bar and is dropped, not clamped into the nearest one. A
 * timestamp equal to [nowMillis] has happened and counts.
 *
 * Each timestamp is mapped to its date by the zone's rules (not by dividing by 86,400,000), so a launch at 23:30 on a
 * 25-hour autumn day is still on that day, and the first instant after a local midnight is on the new one.
 */
fun launchesPerLocalDay(
    timestampsMillis: Iterable<Long>,
    nowMillis: Long,
    zone: ZoneId,
    days: Int = LAUNCH_HISTORY_DAYS,
): List<DailyLaunchCount> {
    require(days >= 1) { "days must be at least 1, was $days" }
    val firstDay = localDate(nowMillis, zone).minusDays(days - 1L)
    val counts = IntArray(days)
    for (timestamp in timestampsMillis) {
        if (timestamp > nowMillis) continue
        val offset = ChronoUnit.DAYS.between(firstDay, localDate(timestamp, zone))
        if (offset in 0 until days) counts[offset.toInt()]++
    }
    return List(days) { DailyLaunchCount(firstDay.plusDays(it.toLong()), counts[it]) }
}

private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
