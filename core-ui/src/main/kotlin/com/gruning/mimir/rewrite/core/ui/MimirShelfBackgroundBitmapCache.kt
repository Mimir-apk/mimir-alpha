package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.LayoutDirection

/**
 * The bounded process-owned cache for completed, static shelf background bitmaps.
 *
 * This cache is UI-thread-owned: callers must access it on the UI thread -- the draw path, or a UI-thread continuation
 * publishing a bitmap that was baked elsewhere (the off-thread palette bakes of `mimirThemeSceneBackdrop` and
 * `MimirShelfBackground`).
 */
internal class MimirShelfBackgroundBitmapCache(
    private val maxEntries: Int = MaxEntries,
    private val maxBytes: Long = MaxBytes,
) {
    private data class Entry(
        val bitmap: ImageBitmap,
        val allocationBytes: Long,
    )

    private val entries = LinkedHashMap<MimirShelfBackgroundCacheKey, Entry>(
        /* initialCapacity = */ maxEntries.coerceAtLeast(1),
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    )
    private var cachedBytes = 0L

    /** Return the existing completed bitmap, or build it once and publish only its completed result. */
    fun getOrBuild(
        key: MimirShelfBackgroundCacheKey,
        estimatedBytes: Long?,
        build: () -> MimirShelfBackgroundCacheBuild,
    ): ImageBitmap {
        entries[key]?.let { return it.bitmap }

        // The arithmetic preflight is deliberately before the builder. A surface that cannot fit the cache
        // still uses the same synchronous painter path, but never becomes a cache entry.
        if (estimatedBytes == null || estimatedBytes <= 0L || estimatedBytes > maxBytes) {
            return build().bitmap
        }

        val built = build()
        val allocationBytes = built.allocationBytes
        if (allocationBytes <= 0L || allocationBytes > maxBytes) {
            return built.bitmap
        }

        entries.remove(key)?.let { cachedBytes -= it.allocationBytes }
        entries[key] = Entry(built.bitmap, allocationBytes)
        cachedBytes += allocationBytes
        evictUntilWithinBounds()
        return built.bitmap
    }

    /** The completed bitmap cached for [key], if any. Never builds; counts as a use for the LRU order. */
    fun get(key: MimirShelfBackgroundCacheKey): ImageBitmap? = entries[key]?.bitmap

    internal val entryCount: Int
        get() = entries.size

    internal val byteCount: Long
        get() = cachedBytes

    private fun evictUntilWithinBounds() {
        while (entries.size > maxEntries || cachedBytes > maxBytes) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
            cachedBytes -= eldest.value.allocationBytes
        }
    }

    companion object {
        internal const val MaxEntries = 2
        internal const val MaxBytes = 16L * 1024L * 1024L

        /** Overflow-safe nominal ARGB preflight; the actual allocation is charged after the bitmap is built. */
        internal fun nominalAllocationBytes(width: Int, height: Int): Long? {
            if (width <= 0 || height <= 0) return 0L
            val widthLong = width.toLong()
            val heightLong = height.toLong()
            if (widthLong > Long.MAX_VALUE / heightLong) return null
            val pixels = widthLong * heightLong
            if (pixels > Long.MAX_VALUE / 4L) return null
            return pixels * 4L
        }

        internal fun actualAllocationBytes(bitmap: ImageBitmap): Long =
            bitmap.asAndroidBitmap().allocationByteCount.toLong()
    }
}

internal data class MimirShelfBackgroundCacheKey(
    val size: Size,
    val density: Float,
    val layoutDirection: LayoutDirection,
    val palette: MimirThemePalette,
    val showAtmosphericClouds: Boolean,
    /** The scene's bright light dimmed for a compact dock (see `MimirShelfBackground`'s `quietSceneLight`). */
    val quietSceneLight: Boolean = false,
    /** The scene's bright light moved below the text column on a 4:3 or square field (`sceneLightBelowText`). */
    val sceneLightBelowText: Boolean = false,
    /** False on a stage where the scene's one bright light would sit over a caption (see `MimirShelfBackground`'s `sceneLight`); a different raster. */
    val sceneLight: Boolean = true,
    /** Miniature factor of the bake: 1 = the full-size shelf raster; `MimirThemeSceneBackdrop` bakes at 1/n. */
    val downscale: Int = 1,
    /** Software box-blur radius applied once at bake time, in the baked bitmap's own pixels (0 = none). */
    val blurRadiusPx: Int = 0,
    /**
     * The Well scene's diffraction spikes on its bright stars. True everywhere but the Bottom's miniature backdrop, which
     * drops them (V1: at 1/4 size they bake to sub-pixel specks that read as grey squares). A different raster, so in the key.
     */
    val shineStars: Boolean = true,
    /**
     * The player's background in place of the theme's drawn scene (concept A, 2026-09-30), or null for the scene. Its
     * id, dim and blur are in the key, so a change of any of them is a different raster; when it is null the pixels
     * are exactly the scene's, as before.
     */
    val ground: MimirSceneGround? = null,
)

internal data class MimirShelfBackgroundCacheBuild(
    val bitmap: ImageBitmap,
    val allocationBytes: Long,
)
