package com.gruning.mimir.rewrite.core.resolver.library

import android.os.Process
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/** How often the favourites picker re-reads its full list while identification is writing. */
internal const val FavoriteCandidateRefreshMillis = 1_500L

/**
 * How often a platform gamelist re-reads while identification is writing. A change after a quiet
 * spell still lands at once; only a burst waits, so starring a game from its menu mid-identification can take up
 * to about two periods to show.
 */
internal const val GameListRefreshMillis = 1_500L

/**
 * Passes the first value at once, then at most one value per [periodMillis]. The collector sleeps after each
 * value, and while it sleeps the upstream is suspended. A Room flow suspended that way runs ONE query for a
 * whole burst of table invalidations (its invalidation channel is conflated) instead of one per write.
 *
 * Room hands results over a rendezvous channel, so its next query runs right after a delivery and then waits
 * for the period to end. A delivered list can therefore be up to one period old, and the last write of a
 * burst shows within about two periods. That is the intended trade, not a bug: do not add a buffer or
 * `conflate()` between the source and this operator, because either would run the source at full speed again.
 */
internal fun <T> Flow<T>.atMostEvery(periodMillis: Long): Flow<T> = transform { value ->
    emit(value)
    delay(periodMillis)
}

/**
 * One background-priority thread maps large library reads without occupying the UI thread.
 * The favourites picker maps 5,500 rows each time its
 * read lands; at normal priority that competed with the UI thread for the RG's cores. At background priority it
 * yields to the UI, and the list may land a little later, which the throttle above already allows for.
 */
internal val LibraryProjectionDispatcher: CoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            },
            "mimir-library-projection",
        ).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
