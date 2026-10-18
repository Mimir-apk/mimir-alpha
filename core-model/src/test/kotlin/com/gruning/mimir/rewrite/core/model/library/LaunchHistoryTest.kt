package com.gruning.mimir.rewrite.core.model.library

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The weekly launch bars' day rules, pinned in explicit zones. Nothing here reads the JVM's default zone or the clock:
 * a test that did would pass on the author's machine and bucket differently on another.
 */
class LaunchHistoryTest {

    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val havana = ZoneId.of("America/Havana")

    @Test
    fun `an empty week is seven zeros, oldest first and today last`() {
        val week = launchesPerLocalDay(emptyList(), millis("2026-10-02T15:30:00Z"), utc)

        assertEquals(LAUNCH_HISTORY_DAYS, week.size)
        assertEquals(
            (26..30).map { LocalDate.of(2026, 9, it) } + (1..2).map { LocalDate.of(2026, 10, it) },
            week.map { it.date },
        )
        assertEquals(List(7) { 0 }, week.map { it.count })
    }

    @Test
    fun `a day with no launches is present as zero between days that have them`() {
        val week = launchesPerLocalDay(
            timestampsMillis = listOf(
                millis("2026-09-26T08:00:00Z"),
                millis("2026-09-26T09:00:00Z"),
                millis("2026-10-02T01:00:00Z"),
            ),
            nowMillis = millis("2026-10-02T15:30:00Z"),
            zone = utc,
        )

        assertEquals(listOf(2, 0, 0, 0, 0, 0, 1), week.map { it.count })
    }

    @Test
    fun `the first millisecond of a day is that day and the millisecond before it is the day before`() {
        val now = millis("2026-10-02T15:30:00Z")
        val week = launchesPerLocalDay(
            timestampsMillis = listOf(
                millis("2026-10-01T23:59:59.999Z"),
                millis("2026-10-02T00:00:00.000Z"),
            ),
            nowMillis = now,
            zone = utc,
        )

        assertEquals(1, week[5].count)
        assertEquals(LocalDate.of(2026, 10, 1), week[5].date)
        assertEquals(1, week[6].count)
        assertEquals(LocalDate.of(2026, 10, 2), week[6].date)
    }

    @Test
    fun `midnight is the device zone's midnight, not UTC's`() {
        // 2026-10-02 in Berlin runs from 2026-10-01T22:00Z (CEST, UTC+2) to 2026-10-02T22:00Z. "Now" is the last
        // millisecond of it, so that the third stamp has happened.
        val week = launchesPerLocalDay(
            timestampsMillis = listOf(
                millis("2026-10-01T21:59:59.999Z"),
                millis("2026-10-01T22:00:00.000Z"),
                millis("2026-10-02T21:59:59.999Z"),
            ),
            nowMillis = millis("2026-10-02T21:59:59.999Z"),
            zone = berlin,
        )

        assertEquals(listOf(0, 0, 0, 0, 0, 1, 2), week.map { it.count })
    }

    @Test
    fun `the window starts at the first instant of its oldest day, and a launch before it belongs to no bar`() {
        val now = millis("2026-10-02T15:30:00Z")
        val start = launchHistoryStartMillis(now, utc)

        assertEquals(millis("2026-09-26T00:00:00Z"), start)
        val week = launchesPerLocalDay(listOf(start - 1L, start), now, utc)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0), week.map { it.count })
    }

    @Test
    fun `a launch stamped after today by a wrong clock is dropped, not pulled onto today`() {
        val week = launchesPerLocalDay(
            timestampsMillis = listOf(millis("2026-10-03T00:00:00Z"), millis("2030-01-01T00:00:00Z")),
            nowMillis = millis("2026-10-02T15:30:00Z"),
            zone = utc,
        )

        assertEquals(List(7) { 0 }, week.map { it.count })
    }

    @Test
    fun `a launch stamped later today than now has not happened yet and is dropped, one up to now counts`() {
        // Noon UTC. 18:00 is today's DATE, but it is after now: a wrong clock, not a launch.
        val noon = millis("2026-10-02T12:00:00Z")
        val week = launchesPerLocalDay(
            timestampsMillis = listOf(
                millis("2026-10-02T11:59:00Z"), // counts
                noon, // exactly now has happened, so it counts
                noon + 1L, // one millisecond ahead does not
                millis("2026-10-02T18:00:00Z"), // later the same day does not
            ),
            nowMillis = noon,
            zone = utc,
        )

        assertEquals(2, week.last().count)
        assertEquals(2, week.sumOf { it.count })
    }

    @Test
    fun `the order of the timestamps does not matter`() {
        val stamps = listOf(
            millis("2026-10-02T10:00:00Z"),
            millis("2026-09-30T10:00:00Z"),
            millis("2026-10-02T09:00:00Z"),
            millis("2026-09-30T08:00:00Z"),
        )
        val now = millis("2026-10-02T15:30:00Z")

        assertEquals(
            launchesPerLocalDay(stamps, now, utc),
            launchesPerLocalDay(stamps.reversed(), now, utc),
        )
        assertEquals(listOf(0, 0, 0, 0, 2, 0, 2), launchesPerLocalDay(stamps, now, utc).map { it.count })
    }

    @Test
    fun `a spring-forward day is 23 hours long and its edges are still its own`() {
        // Berlin 2026-03-29: 02:00 CET jumps to 03:00 CEST. The day runs 2026-03-28T23:00Z to 2026-03-29T22:00Z.
        val week = launchesPerLocalDay(
            timestampsMillis = listOf(
                millis("2026-03-28T22:59:59.999Z"), // 23:59:59.999 CET on the 28th
                millis("2026-03-28T23:00:00.000Z"), // 00:00 CET on the 29th
                millis("2026-03-29T21:59:59.999Z"), // 23:59:59.999 CEST on the 29th
                millis("2026-03-29T22:00:00.000Z"), // 00:00 CEST on the 30th
            ),
            nowMillis = millis("2026-03-31T12:00:00Z"),
            zone = berlin,
        )

        assertEquals(LocalDate.of(2026, 3, 25), week.first().date)
        assertEquals(LocalDate.of(2026, 3, 31), week.last().date)
        val byDate = week.associate { it.date to it.count }
        assertEquals(1, byDate[LocalDate.of(2026, 3, 28)])
        assertEquals(2, byDate[LocalDate.of(2026, 3, 29)])
        assertEquals(1, byDate[LocalDate.of(2026, 3, 30)])
        assertEquals(4, week.sumOf { it.count })
        // Midnight today minus six 24-hour days would be 22:00Z, an hour early: the 29th is an hour short.
        assertEquals(millis("2026-03-24T23:00:00Z"), launchHistoryStartMillis(millis("2026-03-31T12:00:00Z"), berlin))
    }

    @Test
    fun `a fall-back day is 25 hours long and its late evening is not pushed onto the next day`() {
        // Berlin 2026-10-25: 03:00 CEST falls back to 02:00 CET. The day runs 2026-10-24T22:00Z to 2026-10-25T23:00Z.
        val week = launchesPerLocalDay(
            timestampsMillis = listOf(
                millis("2026-10-24T21:59:59.999Z"), // 23:59:59.999 CEST on the 24th
                millis("2026-10-24T22:00:00.000Z"), // 00:00 CEST on the 25th
                millis("2026-10-25T00:30:00.000Z"), // 02:30 CEST, the first 02:30
                millis("2026-10-25T01:30:00.000Z"), // 02:30 CET, the second 02:30
                millis("2026-10-25T22:30:00.000Z"), // 23:30 CET on the 25th: 24 hours and 30 minutes after the day began
                millis("2026-10-25T23:00:00.000Z"), // 00:00 CET on the 26th
            ),
            nowMillis = millis("2026-10-27T10:00:00Z"),
            zone = berlin,
        )

        val byDate = week.associate { it.date to it.count }
        assertEquals(1, byDate[LocalDate.of(2026, 10, 24)])
        assertEquals(4, byDate[LocalDate.of(2026, 10, 25)])
        assertEquals(1, byDate[LocalDate.of(2026, 10, 26)])
        assertEquals(6, week.sumOf { it.count })
    }

    @Test
    fun `a zone whose clocks skip local midnight starts that day at the first instant that exists`() {
        // Havana 2026-03-08: 00:00 CST jumps to 01:00 CDT, so local midnight does not exist that day.
        assertNotNull(
            "tzdata no longer skips midnight in Havana on 2026-03-08; pick another zone for this case",
            havana.rules.getTransition(LocalDateTime.of(2026, 3, 8, 0, 30)),
        )
        val dayStart = millis("2026-03-08T05:00:00Z") // the transition instant, 01:00 CDT

        assertEquals(dayStart, launchHistoryStartMillis(millis("2026-03-08T12:00:00Z"), havana, days = 1))
        val week = launchesPerLocalDay(
            timestampsMillis = listOf(dayStart - 1L, dayStart),
            nowMillis = millis("2026-03-09T12:00:00Z"),
            zone = havana,
        )
        val byDate = week.associate { it.date to it.count }
        assertEquals(1, byDate[LocalDate.of(2026, 3, 7)])
        assertEquals(1, byDate[LocalDate.of(2026, 3, 8)])
    }

    @Test
    fun `one day is one entry and a window under one day is refused`() {
        val now = millis("2026-10-02T15:30:00Z")

        assertEquals(
            listOf(DailyLaunchCount(LocalDate.of(2026, 10, 2), 1)),
            launchesPerLocalDay(listOf(millis("2026-10-02T00:00:00Z")), now, utc, days = 1),
        )
        assertThrows(IllegalArgumentException::class.java) { launchesPerLocalDay(emptyList(), now, utc, days = 0) }
        assertThrows(IllegalArgumentException::class.java) { launchHistoryStartMillis(now, utc, days = 0) }
    }

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()
}
