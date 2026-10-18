package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirTimeCopy
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Played today / yesterday / Nd ago" on the Library list row, the hero meta row, the detail page and the Main launch card, on fixed clocks in
 * an explicit zone (never the JVM's default).
 *
 * The day is a local calendar day, the same count the Bottom's Activity page words with [MimirTimeCopy.daysAgo]. It used to be `now - millis`
 * in 24-hour slabs, which called 13:28 yesterday "today" at 10:58 (observed on Thor in UTC-4).
 */
class RelativePlayedTest {

    private val owner = ZoneOffset.ofHours(-4)
    private val newYork = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun played(then: Long, now: Long, zone: ZoneId): String = relativePlayed(then, now, zone)

    @Test
    fun `UTC-4, castlevania launched yesterday at 13-28 reads yesterday at 10-58, 21 and a half hours later`() {
        val launched = at(owner, 2026, 10, 7, 13, 28)
        val now = at(owner, 2026, 10, 8, 10, 58)
        assertEquals("Played yesterday", played(launched, now, owner))
    }

    @Test
    fun `UTC-4, chocobo launched two days back at 19-01 reads 2d ago at 10-58, 40 hours later`() {
        val launched = at(owner, 2026, 10, 6, 19, 1)
        val now = at(owner, 2026, 10, 8, 10, 58)
        assertEquals("Played 2d ago", played(launched, now, owner))
    }

    @Test
    fun `the same local day is today, however late and however early`() {
        val now = at(owner, 2026, 10, 8, 23, 59)
        assertEquals("Played today", played(at(owner, 2026, 10, 8, 0, 0), now, owner))
        assertEquals("Played today", played(at(owner, 2026, 10, 8, 9, 0), at(owner, 2026, 10, 8, 10, 58), owner))
        assertEquals("Played today", played(now, now, owner))
    }

    @Test
    fun `23-59 yesterday is yesterday at 00-01, two minutes on, and was today until midnight`() {
        val launched = at(owner, 2026, 10, 7, 23, 59)
        assertEquals("Played today", played(launched, at(owner, 2026, 10, 7, 23, 59), owner))
        assertEquals("Played today", played(launched, at(owner, 2026, 10, 7, 23, 59) + 59_000L, owner))
        assertEquals("Played yesterday", played(launched, at(owner, 2026, 10, 8, 0, 1), owner))
        assertEquals("Played yesterday", played(launched, at(owner, 2026, 10, 8, 23, 59), owner))
        assertEquals("Played 2d ago", played(launched, at(owner, 2026, 10, 9, 0, 0), owner))
    }

    @Test
    fun `21 and a half hours ago is yesterday, 40 hours ago is two days back`() {
        val now = at(owner, 2026, 10, 8, 10, 58)
        val hour = 3_600_000L
        assertEquals("Played yesterday", played(now - 21 * hour - hour / 2, now, owner))
        assertEquals("Played 2d ago", played(now - 40 * hour, now, owner))
    }

    @Test
    fun `a spring-forward day is one day and a fall-back day is one day, not 23 or 25 hours of slab`() {
        // America/New_York springs forward on 2026-03-08 (a 23 hour day): 00:10 EST that day to 00:05 EDT the next is 22 h 55 min, yesterday.
        assertEquals(
            "Played yesterday",
            played(at(newYork, 2026, 3, 8, 0, 10), at(newYork, 2026, 3, 9, 0, 5), newYork),
        )
        // It falls back on 2026-11-01 (a 25 hour day): 00:30 EDT to 23:45 EST that day is 24 h 15 min, still today.
        assertEquals(
            "Played today",
            played(at(newYork, 2026, 11, 1, 0, 30), at(newYork, 2026, 11, 1, 23, 45), newYork),
        )
        assertEquals(
            "Played yesterday",
            played(at(newYork, 2026, 11, 1, 23, 45), at(newYork, 2026, 11, 2, 0, 5), newYork),
        )
    }

    @Test
    fun `the buckets past a week keep their short units, counted in calendar days`() {
        val now = at(owner, 2026, 10, 8, 12, 0)
        fun back(days: Long): Long = ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, owner).minusDays(days).toInstant().toEpochMilli()
        assertEquals("Played 3d ago", played(back(3), now, owner))
        assertEquals("Played 6d ago", played(back(6), now, owner))
        assertEquals("Played 1w ago", played(back(7), now, owner))
        assertEquals("Played 2w ago", played(back(14), now, owner))
        assertEquals("Played 1mo ago", played(back(30), now, owner))
        assertEquals("Played 2mo ago", played(back(60), now, owner))
        assertEquals("Played 1y ago", played(back(365), now, owner))
    }

    @Test
    fun `a launch stamped after now reads just now, as before`() {
        val now = at(owner, 2026, 10, 8, 12, 0)
        assertEquals("Played just now", played(now + 1L, now, owner))
    }

    @Test
    fun `the list, the card and the Bottom's Activity page agree on the day, for every day back to six`() {
        val now = at(owner, 2026, 10, 8, 10, 58)
        for (clockTime in listOf(Triple(0, 0, 0), Triple(9, 30, 0), Triple(13, 28, 0), Triple(23, 59, 0))) {
            for (days in 0L..6L) {
                val then = ZonedDateTime.of(2026, 10, 8, clockTime.first, clockTime.second, 0, 0, owner)
                    .minusDays(days).toInstant().toEpochMilli()
                if (then > now) continue
                val activity = MimirTimeCopy.daysAgo(then, now, owner)
                val expected = when (activity) {
                    "today" -> "Played today"
                    "yesterday" -> "Played yesterday"
                    else -> "Played ${activity.substringBefore(' ')}d ago"
                }
                assertEquals("$days days back at $clockTime", expected, played(then, now, owner))
            }
        }
    }
}
