package com.gruning.mimir.rewrite

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameNativeForegroundReaderTest {
    @Test
    fun `one sibling stopping does not clear a still resumed GameNative activity`() {
        val ledger = GameNativeActivityLedger()
        ledger.observe("app.gamenative", "Launcher", UsageEvents.Event.ACTIVITY_RESUMED)
        ledger.observe("app.gamenative", "Renderer", UsageEvents.Event.ACTIVITY_RESUMED)
        ledger.observe("app.gamenative", "Launcher", UsageEvents.Event.ACTIVITY_STOPPED)

        assertEquals("app.gamenative", ledger.currentPackage())
    }

    @Test
    fun `current query keeps steady foreground and clears then recovers after failure`() {
        var now = 1_000L
        var queryCount = 0
        var fail = false
        val reader = GameNativeForegroundReader(
            nowEpochMs = { now },
            accessGranted = { true },
            queryEvents = { _, _, emit ->
                queryCount += 1
                if (fail) {
                    false
                } else {
                    if (queryCount == 1 || queryCount == 4) {
                        emit(GameNativeUsageEvent("app.gamenative", "Renderer", UsageEvents.Event.ACTIVITY_RESUMED))
                    }
                    true
                }
            },
        )

        assertEquals("app.gamenative", reader.currentPackage())
        now = 2_000L
        assertEquals("app.gamenative", reader.currentPackage())
        fail = true
        now = 3_000L
        assertNull(reader.currentPackage())
        fail = false
        now = 4_000L
        assertEquals("app.gamenative", reader.currentPackage())
        assertTrue(queryCount >= 4)
    }

    @Test
    fun `missing access clears state and permission recovery replays active foreground`() {
        var now = 1_700_000_000_000L
        var access = true
        val reader = GameNativeForegroundReader(
            nowEpochMs = { now },
            accessGranted = { access },
            queryEvents = { _, _, emit ->
                emit(GameNativeUsageEvent("app.gamenative", "Renderer", UsageEvents.Event.ACTIVITY_RESUMED))
                true
            },
        )

        assertEquals("app.gamenative", reader.currentPackage())
        access = false
        now += 2_000L
        assertNull(reader.currentPackage())
        access = true
        now += 2_000L
        assertEquals("app.gamenative", reader.currentPackage())
    }

    @Test
    fun `clock reversal clears an established foreground before reseeded recovery`() {
        var now = 1_700_000_000_000L
        var emitGame = true
        val reader = GameNativeForegroundReader(
            nowEpochMs = { now },
            accessGranted = { true },
            queryEvents = { _, _, emit ->
                if (emitGame) {
                    emit(
                        GameNativeUsageEvent(
                            "app.gamenative",
                            "Renderer",
                            UsageEvents.Event.ACTIVITY_RESUMED,
                        ),
                    )
                }
                true
            },
        )

        assertEquals("app.gamenative", reader.currentPackage())
        emitGame = false
        now += 10_000L
        assertEquals("app.gamenative", reader.currentPackage())

        now -= 20_000L
        assertNull(reader.currentPackage())
        emitGame = true
        now += 1_000L
        assertEquals("app.gamenative", reader.currentPackage())
    }

    @Test
    fun `steady foreground survives long no-event reads`() {
        var now = 1_700_000_000_000L
        var queryCount = 0
        val reader = GameNativeForegroundReader(
            nowEpochMs = { now },
            accessGranted = { true },
            queryEvents = { _, _, emit ->
                queryCount += 1
                if (queryCount == 1) {
                    emit(
                        GameNativeUsageEvent(
                            "app.gamenative",
                            "Renderer",
                            UsageEvents.Event.ACTIVITY_RESUMED,
                        ),
                    )
                }
                true
            },
        )

        assertEquals("app.gamenative", reader.currentPackage())
        repeat(6) {
            now += 5 * 60_000L
            assertEquals("app.gamenative", reader.currentPackage())
        }
        assertEquals(7, queryCount)
    }

    @Test
    fun `initial query replays an already active GameNative session across the 24 hour bootstrap`() {
        val now = 1_700_000_000_000L
        var observedFrom = Long.MIN_VALUE
        var observedTo = Long.MIN_VALUE
        val reader = GameNativeForegroundReader(
            nowEpochMs = { now },
            accessGranted = { true },
            queryEvents = { from, to, emit ->
                observedFrom = from
                observedTo = to
                emit(
                    GameNativeUsageEvent(
                        "app.gamenative",
                        "Renderer",
                        UsageEvents.Event.ACTIVITY_RESUMED,
                    ),
                )
                true
            },
        )

        assertEquals("app.gamenative", reader.currentPackage())
        assertEquals(now - 24L * 60L * 60L * 1_000L, observedFrom)
        assertEquals(now, observedTo)
    }
}
