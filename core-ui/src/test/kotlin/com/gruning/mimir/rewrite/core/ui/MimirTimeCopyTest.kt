package com.gruning.mimir.rewrite.core.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Bottom's time wording: the boundaries of the live clock, the totals, and "how long ago" by calendar day. */
class MimirTimeCopyTest {

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute

    @Test
    fun `the live clock is h mm ss, floored, hours unpadded and growing`() {
        assertEquals("0:00:00", MimirTimeCopy.clock(0L))
        assertEquals("0:00:00", MimirTimeCopy.clock(999L))
        assertEquals("0:00:01", MimirTimeCopy.clock(1_000L))
        assertEquals("0:00:59", MimirTimeCopy.clock(59 * second))
        assertEquals("0:00:59", MimirTimeCopy.clock(59 * second + 999L))
        assertEquals("0:01:00", MimirTimeCopy.clock(minute))
        assertEquals("0:59:59", MimirTimeCopy.clock(hour - 1L))
        assertEquals("1:00:00", MimirTimeCopy.clock(hour))
        assertEquals("23:59:59", MimirTimeCopy.clock(24 * hour - 1L))
        assertEquals("24:00:00", MimirTimeCopy.clock(24 * hour))
        assertEquals("26:05:09", MimirTimeCopy.clock(26 * hour + 5 * minute + 9 * second))
        assertEquals("a clock never runs backwards", "0:00:00", MimirTimeCopy.clock(-5_000L))
    }

    @Test
    fun `a total is hours and minutes, rounded to the minute as Main's elapsed row rounds`() {
        assertEquals("0 min", MimirTimeCopy.total(0L))
        assertEquals("0 min", MimirTimeCopy.total(-1L))
        assertEquals("Less than a minute", MimirTimeCopy.total(1L))
        assertEquals("Less than a minute", MimirTimeCopy.total(29_999L))
        assertEquals("1 min", MimirTimeCopy.total(30_000L))
        assertEquals("1 min", MimirTimeCopy.total(59 * second))
        assertEquals("1 min", MimirTimeCopy.total(minute))
        assertEquals("40 min", MimirTimeCopy.total(40 * minute))
        assertEquals("59 min", MimirTimeCopy.total(59 * minute + 29_999L))
        assertEquals("59.5 minutes rounds up into the hour", "1 h", MimirTimeCopy.total(59 * minute + 30_000L))
        assertEquals("1 h", MimirTimeCopy.total(hour))
        assertEquals("1 h 1 min", MimirTimeCopy.total(hour + minute))
        assertEquals("18 h 40 min", MimirTimeCopy.total(18 * hour + 40 * minute))
        assertEquals("2 h", MimirTimeCopy.total(2 * hour))
        assertEquals("hours keep growing past a day", "24 h", MimirTimeCopy.total(24 * hour))
        assertEquals("26 h 5 min", MimirTimeCopy.total(26 * hour + 5 * minute))
        assertEquals("125 h 30 min", MimirTimeCopy.total(125 * hour + 30 * minute))
    }

    @Test
    fun `a total never overflows and every extreme reads as the large total it is`() {
        fun expected(millis: Long): String {
            val m = java.math.BigInteger.valueOf(millis)
            val minuteMs = java.math.BigInteger.valueOf(60_000L)
            val total = m.add(java.math.BigInteger.valueOf(30_000L)).divide(minuteMs)
            val hours = total.divide(java.math.BigInteger.valueOf(60L))
            val minutes = total.mod(java.math.BigInteger.valueOf(60L))
            return if (minutes.signum() == 0) "$hours h" else "$hours h $minutes min"
        }
        for (millis in listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1L, Long.MAX_VALUE - 29_999L, Long.MAX_VALUE - 30_000L, Long.MAX_VALUE / 2, Int.MAX_VALUE.toLong() * 1_000L)) {
            assertEquals("millis=$millis", expected(millis), MimirTimeCopy.total(millis))
        }
        assertEquals("2562047788015 h 13 min", MimirTimeCopy.total(Long.MAX_VALUE))
        assertEquals("nothing near the top reads as under a minute", false, MimirTimeCopy.total(Long.MAX_VALUE).startsWith("Less"))
        assertEquals("the most negative Long reads as zero", "0 min", MimirTimeCopy.total(Long.MIN_VALUE))
        assertEquals("the clock holds at the extreme too", "2562047788015:12:55", MimirTimeCopy.clock(Long.MAX_VALUE))
        assertEquals("0:00:00", MimirTimeCopy.clock(Long.MIN_VALUE))
    }

    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `days ago counts calendar days by the local midnight, not 24 hour windows`() {
        val now = at(2026, 10, 2, 0, 1)
        assertEquals("today", MimirTimeCopy.daysAgo(at(2026, 10, 2, 0, 0), now, zone))
        assertEquals("today", MimirTimeCopy.daysAgo(now, now, zone))
        assertEquals("two minutes ago across midnight is yesterday", "yesterday", MimirTimeCopy.daysAgo(at(2026, 10, 1, 23, 59), now, zone))
        assertEquals("yesterday", MimirTimeCopy.daysAgo(at(2026, 10, 1, 0, 0), now, zone))
        assertEquals("2 days ago", MimirTimeCopy.daysAgo(at(2026, 9, 30, 23, 59), now, zone))
        assertEquals("an hour short of 48 h is still two calendar days back", "2 days ago", MimirTimeCopy.daysAgo(at(2026, 9, 30, 12, 0), now, zone))
        assertEquals("30 days ago", MimirTimeCopy.daysAgo(at(2026, 9, 2, 12, 0), now, zone))
    }

    @Test
    fun `late in the day the same launch is still today, and the next midnight turns it into yesterday`() {
        val launch = at(2026, 10, 1, 9, 0)
        assertEquals("today", MimirTimeCopy.daysAgo(launch, at(2026, 10, 1, 23, 59), zone))
        assertEquals("yesterday", MimirTimeCopy.daysAgo(launch, at(2026, 10, 2, 0, 0), zone))
        assertEquals("yesterday", MimirTimeCopy.daysAgo(launch, at(2026, 10, 2, 23, 59), zone))
        assertEquals("2 days ago", MimirTimeCopy.daysAgo(launch, at(2026, 10, 3, 0, 0), zone))
    }

    @Test
    fun `a day is counted across a clock change and a timestamp in the future reads as today`() {
        // Europe/Berlin leaves summer time on 2026-10-25: that day is 25 hours long.
        assertEquals("yesterday", MimirTimeCopy.daysAgo(at(2026, 10, 25, 0, 30), at(2026, 10, 26, 0, 30), zone))
        assertEquals("2 days ago", MimirTimeCopy.daysAgo(at(2026, 10, 24, 12, 0), at(2026, 10, 26, 0, 30), zone))
        assertEquals("today", MimirTimeCopy.daysAgo(at(2026, 10, 3, 12, 0), at(2026, 10, 2, 12, 0), zone))
    }

    @Test
    fun `the calendar day count is the number daysAgo words, never negative`() {
        val now = at(2026, 10, 2, 0, 1)
        assertEquals(0L, MimirTimeCopy.calendarDaysAgo(at(2026, 10, 2, 0, 0), now, zone))
        assertEquals(1L, MimirTimeCopy.calendarDaysAgo(at(2026, 10, 1, 23, 59), now, zone))
        assertEquals(2L, MimirTimeCopy.calendarDaysAgo(at(2026, 9, 30, 12, 0), now, zone))
        assertEquals("a clock that moved back reads as zero", 0L, MimirTimeCopy.calendarDaysAgo(at(2026, 10, 3, 12, 0), now, zone))
        // 25-hour Berlin day (2026-10-25): midnight to 23:30 is one day, whatever the slab length.
        assertEquals(0L, MimirTimeCopy.calendarDaysAgo(at(2026, 10, 25, 0, 0), at(2026, 10, 25, 23, 30), zone))
        assertEquals(1L, MimirTimeCopy.calendarDaysAgo(at(2026, 10, 25, 0, 0), at(2026, 10, 26, 0, 0), zone))
    }

    @Test
    fun `the plural is Main's, never one days`() {
        for (days in 2L..400L) {
            val now = at(2027, 6, 1, 12, 0)
            val then = ZonedDateTime.of(2027, 6, 1, 12, 0, 0, 0, zone).minusDays(days).toInstant().toEpochMilli()
            assertEquals("$days days ago", MimirTimeCopy.daysAgo(then, now, zone))
        }
    }

    @Test
    fun `the helpers never say playtime`() {
        val phrases = buildList {
            for (ms in listOf(0L, 1L, 59 * second, hour, 18 * hour + 40 * minute, 26 * hour)) {
                add(MimirTimeCopy.clock(ms))
                add(MimirTimeCopy.total(ms))
            }
            val now = at(2026, 10, 2, 12, 0)
            for (back in listOf(0L, 1L, 2L, 30L)) add(MimirTimeCopy.daysAgo(now - back * 24 * hour, now, zone))
        }
        phrases.forEach { assertFalse("'$it' must not say playtime", it.contains("playtime", ignoreCase = true) || it.contains("play time", ignoreCase = true)) }
    }
}
