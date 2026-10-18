package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Main's "Played …" labels turn over at local midnight with no data change: the Bottom's Activity page re-reads at midnight, and Main's list
 * and hero used to keep "Played today" until something else moved. The wall clock is fixed and crossed by hand; no row is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayedDayClockTest {

    @get:Rule
    val compose = createComposeRule()

    private val owner = ZoneOffset.ofHours(-4)

    private fun at(month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, owner).toInstant().toEpochMilli()

    @After
    fun restoreClock() {
        PlayedDayClock.clock = System::currentTimeMillis
    }

    @Test
    fun `a Played label turns from today to yesterday at midnight with the game row untouched`() {
        val launched = at(10, 7, 19, 0)
        val clock = AtomicLong(at(10, 7, 23, 59))
        PlayedDayClock.clock = clock::get
        val midnightTicks = Channel<Unit>(Channel.UNLIMITED)
        val waits = mutableListOf<Long>()

        compose.setContent {
            PlayedDayClockEffect(
                nowMillis = clock::get,
                zone = { owner },
                wait = { millis ->
                    waits += millis
                    midnightTicks.receive()
                },
            )
            // The row is the SAME launched time on every pass: only the date can change what this says.
            BasicText(relativePlayed(launched, zone = owner), Modifier.testTag("played"))
        }

        compose.onNodeWithTag("played").assertTextEquals("Played today")
        assertEquals("the effect sleeps until the next local midnight, one minute away", listOf(60_000L), waits)

        // The clock crosses midnight, but nothing has told the screen yet: no data changed and no tick came, so nothing is recomposed.
        clock.set(at(10, 8, 0, 1))
        compose.waitForIdle()
        compose.onNodeWithTag("played").assertTextEquals("Played today")

        // The midnight tick alone turns it over.
        midnightTicks.trySend(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("played").assertTextEquals("Played yesterday")
        assertEquals("and it sleeps to the next midnight, a whole day on", 24L * 3_600_000L - 60_000L, waits.last())
    }

    @Test
    fun `the label keeps turning over on every midnight a screen stays open`() {
        val launched = at(10, 7, 19, 0)
        val clock = AtomicLong(at(10, 7, 23, 59))
        PlayedDayClock.clock = clock::get
        val midnightTicks = Channel<Unit>(Channel.UNLIMITED)

        compose.setContent {
            PlayedDayClockEffect(nowMillis = clock::get, zone = { owner }, wait = { midnightTicks.receive() })
            BasicText(relativePlayed(launched, zone = owner), Modifier.testTag("played"))
        }

        compose.onNodeWithTag("played").assertTextEquals("Played today")
        listOf(
            at(10, 8, 0, 1) to "Played yesterday",
            at(10, 9, 0, 1) to "Played 2d ago",
            at(10, 10, 0, 1) to "Played 3d ago",
        ).forEach { (now, expected) ->
            clock.set(now)
            midnightTicks.trySend(Unit)
            compose.waitForIdle()
            compose.onNodeWithTag("played").assertTextEquals(expected)
        }
    }
}
