package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.ui.MimirLocalDay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Makes Main's "Played today / yesterday / 2d ago" labels turn over at local midnight with no data change (alpha cert review: the
 * Bottom's Activity page re-read at midnight and Main did not, so Main said "Played today" while the Bottom said "Yesterday").
 *
 * [relativePlayed] reads its "now" through [nowMillis], which reads [day], a Compose state. A composition that shows a label has therefore
 * subscribed to the date, and the one flip at local midnight recomposes every label without touching the rows. The flip comes from
 * [PlayedDayClockEffect], which collects the same [MimirLocalDay.ticker] the Bottom's Activity page uses.
 */
internal object PlayedDayClock {

    private val day = mutableLongStateOf(LocalDate.now().toEpochDay())

    /** The wall clock. A test sets it to cross midnight without waiting for one; production leaves it alone. */
    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    /** The current time. Reading it inside a composition subscribes that composition to the local date. */
    fun nowMillis(): Long {
        day.longValue
        return clock()
    }

    /** Publishes [date]; the write is a no-op unless the day turned over. Its own snapshot, so no bare global write. */
    fun publish(date: LocalDate) {
        Snapshot.withMutableSnapshot {
            val epochDay = date.toEpochDay()
            if (day.longValue != epochDay) day.longValue = epochDay
        }
    }
}

/**
 * While this is composed, the date is kept current: it flips [PlayedDayClock] at each local midnight. Put it once in each screen that shows a
 * "Played …" label; several at once are harmless (a repeated publish of the same day writes nothing).
 */
@Composable
internal fun PlayedDayClockEffect(
    nowMillis: () -> Long = System::currentTimeMillis,
    zone: () -> ZoneId = ZoneId::systemDefault,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    LaunchedEffect(Unit) {
        MimirLocalDay.ticker(nowMillis, zone, wait).collect { PlayedDayClock.publish(it) }
    }
}
