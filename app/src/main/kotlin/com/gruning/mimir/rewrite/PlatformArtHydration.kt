package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val PlatformArtHydrationDeadlineMs = 1_200L

/**
 * The first repository emission is authoritative; no UI placeholder state participates in this gate.
 * The fallback hold is bounded: a stalled or failed repository must never leave every default platform
 * emblem transparent forever. Later emissions still refresh persisted overrides after the gate releases.
 */
internal suspend fun hydratePersistedPlatformArt(
    platforms: Flow<List<com.gruning.mimir.rewrite.core.model.library.PlatformRow>>,
    refresh: (PlatformId) -> Unit,
    markHydrated: () -> Unit,
    initialDeadlineMs: Long = PlatformArtHydrationDeadlineMs,
) = coroutineScope {
    val released = AtomicBoolean(false)
    val releaseOnce = {
        if (released.compareAndSet(false, true)) markHydrated()
    }
    val deadline = launch {
        delay(initialDeadlineMs)
        releaseOnce()
    }
    try {
        platforms.collect { rows ->
            rows.forEach { refresh(it.platformId) }
            releaseOnce()
        }
    } finally {
        deadline.cancel()
        releaseOnce()
    }
}
