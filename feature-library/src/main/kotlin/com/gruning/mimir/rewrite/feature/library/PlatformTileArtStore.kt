package com.gruning.mimir.rewrite.feature.library

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

private const val LiveRevisionQuery = "mimirLive"

/**
 * Returns the stable on-disk identity carried by a rendered URI. The live revision is deliberately
 * presentation-only: persisted hydration must be able to compare its `?v=<mtime>` URI with the currently
 * published model without discarding a newer in-process image identity.
 */
internal fun platformArtPersistedUri(renderUri: String): String =
    renderUri
        .substringBefore("&$LiveRevisionQuery=")
        .substringBefore("?$LiveRevisionQuery=")

/**
 * StateFlow conflates equal maps, and some Android filesystems coalesce consecutive `lastModified` values.
 * An explicit successful replacement therefore receives a monotonic render identity even when its persisted
 * path + mtime are unchanged. Passive repository hydration retains that identity for the same persisted file,
 * preventing a later emission from walking back into a stale decoded-bitmap cache entry.
 */
internal fun platformArtRenderUri(
    currentRenderUri: String?,
    persistedUri: String,
    contentChanged: Boolean,
    liveRevision: Long,
): String = when {
    contentChanged -> buildString {
        append(persistedUri)
        append(if ('?' in persistedUri) '&' else '?')
        append(LiveRevisionQuery)
        append('=')
        append(liveRevision)
    }
    currentRenderUri != null && platformArtPersistedUri(currentRenderUri) == persistedUri -> currentRenderUri
    else -> persistedUri
}

/**
 * per-platform icon-art overrides for the Library shelf (the "Y → change platform art" flow).
 * The APP layer WRITES it — it owns the SteamGridDB file store + a Context; the shelf ([ShelfCoverflow] via
 * [PlatformHeroTile]) READS it to override a platform tile's drawn glyph with the picked art. Keyed by
 * `platformId.value` → a versioned local `file://…?v=<mtime>` URI (the `?v` busts Coil's model-string cache on a
 * re-pick). Lives in feature-library so the shelf can read it without an upward dependency on the app module.
 */
object PlatformTileArtStore {
    val art = MutableStateFlow<Map<String, String>>(emptyMap())
    /** False only until the first persisted-file pass has proved whether each platform has an override. */
    val hydrated = MutableStateFlow(false)

    private val liveRevision = AtomicLong(0L)

    /** Publish one durable platform-art observation, optionally invalidating the live decoded face. */
    @Synchronized
    fun publish(platformIdValue: String, persistedUri: String?, contentChanged: Boolean = false) {
        val current = art.value
        val updated = if (persistedUri == null) {
            current - platformIdValue
        } else {
            current + (
                platformIdValue to platformArtRenderUri(
                    currentRenderUri = current[platformIdValue],
                    persistedUri = persistedUri,
                    contentChanged = contentChanged,
                    liveRevision = if (contentChanged) liveRevision.incrementAndGet() else liveRevision.get(),
                )
            )
        }
        if (updated != current) art.value = updated
    }
}
