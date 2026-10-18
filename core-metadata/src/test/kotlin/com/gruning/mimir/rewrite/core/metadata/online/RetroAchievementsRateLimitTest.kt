package com.gruning.mimir.rewrite.core.metadata.online

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the transport does when RetroAchievements (or something in front of it) pushes back, with a fake HTTP answer and a fake clock:
 *  - a 403 is a refusal of the key only when its body is RA's own JSON error, never when it is a firewall's HTML page (so one block cannot latch the whole session);
 *  - a 429 stops every key-bearing request until its Retry-After has passed (seconds or an HTTP date, never longer than ten minutes), and looks to the caller exactly
 *    like being offline;
 *  - a burst of requests is spread to at most four starts a second.
 */
class RetroAchievementsRateLimitTest {

    private val auth = RetroAchievementsApi.Auth("player", "key")

    private val progressBody =
        """{"ID":77,"Title":"Sonic","NumAchievements":1,"NumAwardedToUser":1,"NumAwardedToUserHardcore":0,"Achievements":{"1":{"ID":1,"Title":"First","Description":"Start","Points":5,"DateEarned":"2026-01-01 00:00:00"}}}"""

    private val htmlBlock = "<html><head><title>Access denied</title></head><body><h1>Error 1020</h1></body></html>"

    private fun source(
        now: () -> Long = { 1_000_000L },
        pacer: RetroAchievementsRequestPacer? = null,
        httpGet: suspend (String) -> RaHttpResult,
    ) = RetroAchievementsSource(gateOpen = { true }, auth = { auth }, httpGet = httpGet, clock = now, pacer = pacer)

    // ── A2: a 403 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a 403 with an HTML body is not a refusal of the key`() {
        assertEquals(RaHttpResult.Unreachable, classifyRetroAchievementsResponse(403, errorBody = { htmlBlock }) { error("must not read") })
        // Nothing readable, a body that is not an object, a cut-off object and an object that is not RA's error shape are none of them a refusal either.
        listOf<String?>(null, "", "[]", "\"denied\"", "{\"message\":", """{"status":"blocked"}""").forEach { body ->
            assertEquals("not a refusal: $body", RaHttpResult.Unreachable, classifyRetroAchievementsResponse(403, errorBody = { body }) { error("must not read") })
        }
        assertEquals(
            "a body that cannot be read at all is not a refusal",
            RaHttpResult.Unreachable,
            classifyRetroAchievementsResponse(403, errorBody = { error("socket closed") }) { error("must not read") },
        )
    }

    @Test
    fun `a 403 carrying RA's own JSON error, and any 401, are refusals of the key`() {
        listOf("""{"message":"Invalid API key"}""", """{ "error": "forbidden" }""", """{"message":"Forbidden.","errors":{}}""").forEach { body ->
            assertEquals("a refusal: $body", RaHttpResult.Rejected, classifyRetroAchievementsResponse(403, errorBody = { body }) { error("must not read") })
        }
        assertEquals(
            "a 401 is a refusal whatever it carries, and its body is never read",
            RaHttpResult.Rejected,
            classifyRetroAchievementsResponse(401, errorBody = { error("must not read") }) { error("must not read") },
        )
    }

    @Test
    fun `through the source a firewall 403 is simply unavailable and RA's own 403 is a rejection`() = runBlocking {
        val blocked = source { classifyRetroAchievementsResponse(403, errorBody = { htmlBlock }) { error("must not read") } }
        assertEquals(RaProgressLookup.Unavailable, blocked.progressFor(77))
        assertEquals(RaPointsLookup.Unavailable, blocked.userPoints())
        assertEquals(RaValidation.Unreachable, blocked.validate(auth))

        val refused = source { classifyRetroAchievementsResponse(403, errorBody = { """{"message":"Invalid API key"}""" }) { error("must not read") } }
        assertTrue(refused.progressFor(77) is RaProgressLookup.Rejected)
        assertEquals(RaValidation.KeyRejected, refused.validate(auth))
    }

    // ── A3: a 429 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a Retry-After in seconds or as an HTTP date is read and never honoured past ten minutes`() {
        val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        assertEquals(120_000L, parseRetryAfterMillis("120", now))
        assertEquals(30_000L, parseRetryAfterMillis("  30 ", now))
        assertEquals(0L, parseRetryAfterMillis("0", now))
        assertEquals(600_000L, parseRetryAfterMillis("99999", now))
        assertEquals("a number too large for a Long is still only ten minutes", 600_000L, parseRetryAfterMillis("99999999999999999999", now))
        // 2026-10-08 is a Thursday.
        assertEquals(120_000L, parseRetryAfterMillis("Thu, 08 Oct 2026 12:02:00 GMT", now))
        assertEquals("a date already past is no wait", 0L, parseRetryAfterMillis("Thu, 08 Oct 2026 11:00:00 GMT", now))
        assertEquals("a date a day away is ten minutes", 600_000L, parseRetryAfterMillis("Fri, 09 Oct 2026 12:00:00 GMT", now))
        listOf(null, "", "   ", "soon", "-5", "12.5").forEach { assertNull("no wait named: $it", parseRetryAfterMillis(it, now)) }
    }

    @Test
    fun `the classifier turns a 429 into a rate limit that carries the header's wait`() {
        assertEquals(RaHttpResult.RateLimited(45_000L), classifyRetroAchievementsResponse(429, retryAfter = { "45" }, nowMillis = 0L) { error("must not read") })
        assertEquals(RaHttpResult.RateLimited(null), classifyRetroAchievementsResponse(429, retryAfter = { "later" }) { error("must not read") })
        assertEquals(RaHttpResult.RateLimited(null), classifyRetroAchievementsResponse(429, retryAfter = { error("no header") }) { error("must not read") })
        assertEquals("other server errors are still plain unreachable", RaHttpResult.Unreachable, classifyRetroAchievementsResponse(503, retryAfter = { "30" }) { error("must not read") })
    }

    @Test
    fun `after a 429 no request is made until the Retry-After has passed`() = runBlocking {
        var now = 1_000_000L
        var calls = 0
        var answer: RaHttpResult = RaHttpResult.RateLimited(30_000L)
        val source = source(now = { now }) {
            calls += 1
            answer
        }

        // The 429 itself looks like being offline: the same silent answer, so the card's copy is the existing "couldn't reach".
        assertEquals(RaProgressLookup.Unavailable, source.progressFor(77))
        assertEquals(1, calls)

        now += 29_999L
        assertEquals(RaProgressLookup.Unavailable, source.progressFor(77))
        assertEquals(RaPointsLookup.Unavailable, source.userPoints())
        assertEquals(RaRecentLookup.Unavailable, source.recentUnlocks())
        assertEquals(RaCompletionPageLookup.Unavailable, source.completionPage(0))
        assertEquals("a second before the wait ends, still nothing sent", 1, calls)

        now += 1L
        answer = RaHttpResult.Body(progressBody)
        assertTrue("and the moment it passes, requests flow again", source.progressFor(77) is RaProgressLookup.Read)
        assertEquals(2, calls)
    }

    @Test
    fun `a 429 that names no wait backs off a minute and one that names a huge wait only ten`() = runBlocking {
        var now = 5_000_000L
        var calls = 0
        var answer: RaHttpResult = RaHttpResult.RateLimited(null)
        val source = source(now = { now }) {
            calls += 1
            answer
        }

        source.userPoints()
        now += 59_999L
        source.userPoints()
        assertEquals(1, calls)
        now += 1L
        answer = RaHttpResult.RateLimited(Long.MAX_VALUE)
        source.userPoints()
        assertEquals(2, calls)

        now += 599_999L
        source.userPoints()
        assertEquals("ten minutes is the most any Retry-After holds requests", 2, calls)
        now += 1L
        source.userPoints()
        assertEquals(3, calls)
    }

    @Test
    fun `a wait one source was handed holds every source that shares the pacer`() = runBlocking {
        var clock = 0L
        val pacer = RetroAchievementsRequestPacer(nowMillis = { clock })
        var callsA = 0
        var callsB = 0
        val a = source(pacer = pacer) {
            callsA += 1
            RaHttpResult.RateLimited(30_000L)
        }
        val b = source(pacer = pacer) {
            callsB += 1
            RaHttpResult.Body(progressBody)
        }

        assertEquals(RaPointsLookup.Unavailable, a.userPoints())
        assertEquals(1, callsA)
        assertEquals("the second client is held by the first one's wait", RaProgressLookup.Unavailable, b.progressFor(77))
        assertEquals(0, callsB)

        clock += 29_999L
        b.progressFor(77)
        assertEquals("a millisecond before the wait ends, still nothing", 0, callsB)
        clock += 1L
        assertTrue(b.progressFor(77) is RaProgressLookup.Read)
        assertEquals(1, callsB)
    }

    @Test
    fun `a hold on the pacer only ever extends, and ends exactly when it says`() {
        var clock = 0L
        val pacer = RetroAchievementsRequestPacer(nowMillis = { clock })
        assertFalse(pacer.holding())
        pacer.holdOff(10_000L)
        pacer.holdOff(1_000L)
        clock = 9_999L
        assertTrue("a shorter ask does not shorten the wait", pacer.holding())
        clock = 10_000L
        assertFalse(pacer.holding())
        pacer.holdOff(5_000L)
        assertTrue(pacer.holding())
    }

    // ── S3: a burst of requests ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a burst is spread to four starts in any second`() = runBlocking {
        var clock = 0L
        val pauses = mutableListOf<Long>()
        val pacer = RetroAchievementsRequestPacer(nowMillis = { clock }, pause = { millis ->
            pauses += millis
            clock += millis
        })

        repeat(4) { pacer.admit() }
        assertTrue("four starts fit in the window", pauses.isEmpty())
        pacer.admit()
        assertEquals("the fifth waits for the first to leave the window", listOf(1_000L), pauses)
        repeat(3) { pacer.admit() }
        assertEquals("and the three after it fit beside it", 1, pauses.size)
        pacer.admit()
        assertEquals("the ninth waits for a second window", listOf(1_000L, 1_000L), pauses)
    }

    @Test
    fun `the source waits its turn at the pacer before every request, and sends all of them`() = runBlocking {
        var clock = 0L
        val pauses = mutableListOf<Long>()
        var calls = 0
        val pacer = RetroAchievementsRequestPacer(maxStarts = 2, nowMillis = { clock }, pause = { millis ->
            pauses += millis
            clock += millis
        })
        val source = source(pacer = pacer) {
            calls += 1
            RaHttpResult.Body("""{"TotalPoints":1,"TotalSoftcorePoints":0}""")
        }

        repeat(5) { source.userPoints() }

        assertEquals("every request is still made", 5, calls)
        assertEquals("two a second: the third and the fifth waited a window", 2, pauses.size)
        assertFalse(pauses.any { it <= 0L })
    }
}
