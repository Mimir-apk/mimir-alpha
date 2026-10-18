package com.gruning.mimir.rewrite.core.metadata.online

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The most recent award date the bulk completion read carries (`MostRecentAwardedDate`): the Activity page's "Recent" filter and its "Recent activity" order read it. An odd or
 * missing date is simply no date; it never fails the row, so one odd date cannot blank a user's Activity page.
 */
class RetroAchievementsAwardDateTest {

    private val expected = Instant.parse("2023-10-26T22:47:17Z").toEpochMilli()

    private fun row(date: String?): String {
        val field = if (date == null) "" else ",\"MostRecentAwardedDate\":$date"
        return """{"Count":1,"Total":1,"Results":[{"GameID":7,"Title":"Game 7","ConsoleName":"SNES","MaxPossible":10,"NumAwarded":3,"NumAwardedHardcore":0$field}]}"""
    }

    @Test
    fun `the offset form and the plain form both read as UTC`() {
        assertEquals(expected, RetroAchievementsApi.parseAwardedMillis("2023-10-26T22:47:17+00:00"))
        assertEquals(expected, RetroAchievementsApi.parseAwardedMillis("2023-10-26T22:47:17Z"))
        assertEquals(expected, RetroAchievementsApi.parseAwardedMillis("2023-10-26 22:47:17"))
        assertEquals(expected, RetroAchievementsApi.parseAwardedMillis("  2023-10-26 22:47:17  "))
    }

    @Test
    fun `an offset is honoured`() {
        assertEquals(expected - 2L * 60L * 60L * 1000L, RetroAchievementsApi.parseAwardedMillis("2023-10-26T22:47:17+02:00"))
    }

    @Test
    fun `blank and unreadable dates are no date, never an error`() {
        listOf(null, "", "   ", "garbage", "0000-00-00 00:00:00", "2023-13-45 99:99:99", "1698360437").forEach {
            assertNull("no date for: $it", RetroAchievementsApi.parseAwardedMillis(it))
        }
    }

    @Test
    fun `the completion row carries its date, and a missing or odd one leaves the row intact`() {
        val dated = RetroAchievementsApi.parseUserCompletionProgress(row("\"2023-10-26 22:47:17\""))!!.results.single()
        assertEquals(expected, dated.lastAwardedAtMillis)
        assertEquals("SNES", dated.consoleName)

        for (body in listOf(row(null), row("null"), row("12345"), row("\"not a date\""), row("""{"a":1}"""))) {
            val entry = RetroAchievementsApi.parseUserCompletionProgress(body)!!.results.single()
            assertNull(entry.lastAwardedAtMillis)
            assertEquals("the row still counts: a bad date never blanks a game's progress", 3, entry.numAwarded)
        }
    }
}
