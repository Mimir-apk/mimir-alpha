package com.gruning.mimir.rewrite.core.storage.dao

import com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount
import com.gruning.mimir.rewrite.core.model.library.launchHistoryStartMillis
import com.gruning.mimir.rewrite.core.model.library.launchesPerLocalDay
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/**
 * Launches per local calendar day for the last seven days ending today, from the `launch_proofs` the game rows' `playCount`
 * reads. The Activity page's weekly bars; `LibraryRepository.observeLaunchesPerDay` is this, and its KDoc is the contract.
 *
 * The query is the whole read: every successful dispatch since a lower bound, except launches of a game that is no longer in the
 * library (an orphan: [LaunchProofDao.intentSentTimestampsSince] says which and why), bucketed here in Kotlin by
 * [launchesPerLocalDay] so a DST day is 23 or 25 hours long like the zone says, and so that a day with no launches is a 0
 * and not a missing row.
 *
 * [clock] and [zone] are read on EVERY emission, not once. The lower bound is the first instant of the oldest local day in
 * the zone at that moment, with no margin, and it is held only for as long as it still covers the window: a launch made
 * after midnight on a screen that has been up since the day before re-emits, is bucketed against the new day, and lands on
 * today's bar. When an emission finds the window starting EARLIER than the bound it holds (a time-zone change can move the
 * oldest day's start back by more than a day: Tongatapu to Chatham moves it 24 h 45 min), the rows before the bound were
 * never fetched, so that emission is withheld and the query is re-subscribed from the new start. A window that starts later
 * only means the query fetched a few rows too many, and the bucketing drops them. What no emission can do is arrive by
 * itself when midnight passes or the zone changes with no launch: Room re-runs the query on a write to `launch_proofs` or to `games`
 * (the orphan filter reads it, so a scan or an identification burst re-runs it too, and `distinctUntilChanged` keeps an unchanged
 * week off the page), not on the clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun LaunchProofDao.observeLaunchesPerDay(
    clock: () -> Long = System::currentTimeMillis,
    zone: () -> ZoneId = ZoneId::systemDefault,
): Flow<List<DailyLaunchCount>> = flow {
    var fromMillis = launchHistoryStartMillis(clock(), zone())
    var rebound: Boolean
    do {
        rebound = false
        emitAll(
            intentSentTimestampsSince(fromMillis).transformWhile { stamps ->
                val now = clock()
                val zoneNow = zone()
                val windowStart = launchHistoryStartMillis(now, zoneNow)
                if (windowStart < fromMillis) {
                    // Rows between the new start and the old bound were never read. Stop this query, ask for them.
                    fromMillis = windowStart
                    rebound = true
                    false
                } else {
                    emit(launchesPerLocalDay(stamps, now, zoneNow))
                    true
                }
            },
        )
    } while (rebound)
}.distinctUntilChanged()
