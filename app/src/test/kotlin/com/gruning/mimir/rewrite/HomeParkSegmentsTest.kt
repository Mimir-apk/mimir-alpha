package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Bottom's NOW PLAYING for a game that Home has parked and Resume brought back: the clock holds still while parked and runs on
 * from where it stopped, and the "before this session" total leaves out the minutes the clock shows. Pure: no device, no Compose.
 */
class HomeParkSegmentsTest {
    private val op = MimirGameSessionOperation("op-a")
    private val other = MimirGameSessionOperation("op-b")
    private val hour = 3_600_000L

    private fun parkedFirst(ledger: HomeParkSegmentLedger = HomeParkSegmentLedger()): HomeParkSegmentLedger = ledger.also {
        // Dispatched at 1 s, HOME pressed at 41 s: 40 s played; the store held 2 h before this session.
        it.parked(op, sessionStartedAtElapsedMs = 1_000L, pressedAtElapsedMs = 41_000L, totalBeforeMs = 2 * hour)
    }

    @Test
    fun `with no record the clock counts from dispatch as it always has`() {
        assertEquals("0:00:10", nowPlayingClockText(1_000L, 11_000L))
        assertEquals("0:00:10", nowPlayingClockText(1_000L, 11_000L, null))
        assertEquals(10_000L, nowPlayingSessionElapsed(1_000L, 11_000L, null))
        assertNull(nowPlayingClockText(null, 11_000L, null))
    }

    @Test
    fun `parked at the HOME press, the clock holds still however long the game stays parked`() {
        val record = parkedFirst().state.value

        assertEquals("0:00:40", nowPlayingClockText(1_000L, 41_000L, record))
        assertEquals("a minute later", "0:00:40", nowPlayingClockText(1_000L, 101_000L, record))
        assertEquals("a night later", "0:00:40", nowPlayingClockText(1_000L, 41_000L + 8 * hour, record))
    }

    @Test
    fun `after Resume it runs on from where it stopped - the parked gap is not on the clock`() {
        val ledger = parkedFirst()
        ledger.resumed(op, atElapsedMs = 41_000L + hour)

        val record = ledger.state.value
        assertEquals("the moment of Resume", "0:00:40", nowPlayingClockText(1_000L, 41_000L + hour, record))
        assertEquals("a minute into the new segment", "0:01:40", nowPlayingClockText(1_000L, 101_000L + hour, record))
    }

    @Test
    fun `a second park and Resume add only the second stretch, and the first total stays the baseline`() {
        val ledger = parkedFirst()
        ledger.resumed(op, atElapsedMs = 41_000L + hour)
        // 60 s into the second segment: HOME again. The dispatch time and the (by now bigger) total are not read a second time.
        ledger.parked(op, sessionStartedAtElapsedMs = 1_000L, pressedAtElapsedMs = 101_000L + hour, totalBeforeMs = 2 * hour + 40_000L)

        val record = ledger.state.value
        assertEquals(100_000L, record?.playedMs)
        assertNull(record?.segmentStartedAtElapsedMs)
        assertEquals(2 * hour, record?.totalBeforeMs)
        assertEquals("0:01:40", nowPlayingClockText(1_000L, 101_000L + 5 * hour, record))
    }

    @Test
    fun `the total line is the figure from before the session, not the live total that already holds the finished segment`() {
        val record = parkedFirst().state.value
        val liveTotal = 2 * hour + 40_000L

        assertEquals(2 * hour, nowPlayingTotalBeforeMs(liveTotal, record))
        assertEquals("2 h before this session", nowPlayingTotalText(nowPlayingTotalBeforeMs(liveTotal, record)))
    }

    @Test
    fun `without a record, or with a baseline that could not be read, the live total is used as before`() {
        assertEquals(5_000L, nowPlayingTotalBeforeMs(5_000L, null))
        assertNull(nowPlayingTotalBeforeMs(null, null))
        val unread = HomeParkSegmentLedger().also {
            it.parked(op, sessionStartedAtElapsedMs = 1_000L, pressedAtElapsedMs = 41_000L, totalBeforeMs = null)
        }.state.value
        assertEquals(5_000L, nowPlayingTotalBeforeMs(5_000L, unread))
    }

    @Test
    fun `a first game with no earlier total shows no total line, not the figure the session itself added`() {
        val record = HomeParkSegmentLedger().also {
            it.parked(op, sessionStartedAtElapsedMs = 1_000L, pressedAtElapsedMs = 41_000L, totalBeforeMs = 0L)
        }.state.value

        assertNull(nowPlayingTotalText(nowPlayingTotalBeforeMs(40_000L, record)))
    }

    @Test
    fun `a record is for its own session only`() {
        val record = parkedFirst().state.value

        assertEquals(record, record.forSession(op))
        assertNull(record.forSession(other))
        assertNull((null as HomeParkSegments?).forSession(op))
    }

    @Test
    fun `a new session's park replaces the old session's record, with its own start and total`() {
        val ledger = parkedFirst()
        ledger.parked(other, sessionStartedAtElapsedMs = 500_000L, pressedAtElapsedMs = 530_000L, totalBeforeMs = 3 * hour)

        val record = ledger.state.value
        assertEquals(other, record?.operation)
        assertEquals(30_000L, record?.playedMs)
        assertEquals(3 * hour, record?.totalBeforeMs)
    }

    @Test
    fun `Resume of a session that was never parked, or of another one, changes nothing`() {
        val empty = HomeParkSegmentLedger()
        empty.resumed(op, atElapsedMs = 9_000L)
        assertNull(empty.state.value)

        val ledger = parkedFirst()
        val before = ledger.state.value
        ledger.resumed(other, atElapsedMs = 9_000L)
        assertEquals(before, ledger.state.value)
    }

    @Test
    fun `a session that recorded no dispatch time shows no clock, parked or not`() {
        val record = HomeParkSegmentLedger().also {
            it.parked(op, sessionStartedAtElapsedMs = null, pressedAtElapsedMs = 41_000L, totalBeforeMs = 2 * hour)
        }.state.value

        assertNull(nowPlayingClockText(null, 99_000L, record))
        assertEquals("the total is still the baseline", 2 * hour, nowPlayingTotalBeforeMs(2 * hour + 1L, record))
    }

    @Test
    fun `the tick lands on the clock's own second boundary after a park`() {
        val record = parkedFirst().state.value.let { it?.copy(segmentStartedAtElapsedMs = 100_500L) }

        // 40 s played + 0.25 s into the running segment: 0.75 s to the next displayed second.
        val elapsed = nowPlayingSessionElapsed(1_000L, 100_750L, record)
        assertEquals(40_250L, elapsed)
        assertEquals(750L, nowPlayingClockDelayMs(elapsed!!))
    }
}
