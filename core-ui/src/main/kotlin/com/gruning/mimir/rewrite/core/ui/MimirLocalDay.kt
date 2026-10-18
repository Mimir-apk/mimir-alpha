package com.gruning.mimir.rewrite.core.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The local calendar date and when it turns over, for every surface whose wording depends on the DAY ("Today", "Yesterday", "Played 2d ago").
 * One definition for the Bottom's Activity page and Main's Library, so the two cannot disagree across midnight.
 */
object MimirLocalDay {

    /** Milliseconds from [nowMillis] to the next local midnight in [zone] (at least 1), cut by the zone's own rules, so a 23 or 25 hour day is right. */
    fun millisUntilNextMidnight(nowMillis: Long, zone: ZoneId): Long {
        val next = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
        return (next - nowMillis).coerceAtLeast(1L)
    }

    /**
     * The local date now, then again each time local midnight passes. [wait] is the sleep (virtual time in a test).
     *
     * Nothing about a launch or a library write happens at midnight, so a surface that is simply left open would keep yesterday's "today" until
     * something else moved. Collect this while the surface is composed, and nothing runs while it is not.
     */
    fun ticker(
        nowMillis: () -> Long,
        zone: () -> ZoneId,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): Flow<LocalDate> = flow {
        while (true) {
            val now = nowMillis()
            val z = zone()
            emit(Instant.ofEpochMilli(now).atZone(z).toLocalDate())
            wait(millisUntilNextMidnight(now, z))
        }
    }
}
