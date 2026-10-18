package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision

/**
 * PERF-B (2026-07-17): the ONE decode contract for confirmed-local cover/tile art.
 *
 * Before this, covers decoded at `Size.ORIGINAL` (1000–2100 px sources for ≤ ~500 px tiles) under TWO
 * cache identities (`"$uri#original"` on the ledge/switch lane, bare `"$uri"` everywhere else), with no
 * configured memory-cache size — so a handful of 10 MB+ hardware bitmaps filled the cache, covers
 * evicted, and navigating BACK re-decoded them cold (the observed cover-resettlement flash). The
 * measured cost sat in the Graphics/EGL bucket (~109 MB EGL on Thor at 41 games).
 *
 * The contract now:
 *  - EVERY cover request resolves at the same explicit [MimirCoverDecodeCapPx] fit-within cap. The cap
 *    is chosen ABOVE the largest real consumer (Thor focused book-hero ≈ 500 px, game-detail hero and
 *    the 1240×1080 companion detail cover ≈ 800 px tall), so nothing ever renders below its display
 *    resolution (parity standard: 1:1-or-better) while sources stop being decoded at 2× and beyond.
 *  - EVERY cover request + warm pass + synchronous warm probe uses the same [mimirCoverCacheKey]
 *    identity, so one bitmap per cover serves ledge, switch tile, gamelist, detail, companion and
 *    backdrop alike — and a warm revisit HITS regardless of which surface warmed it.
 *  - An explicit fixed size resolves OFF-DRAW exactly like `Size.ORIGINAL` did, so the two documented
 *    draw-size deadlock guards (MediaArtFrame ledge contract, GamelistCarousel R1 fix) hold unchanged:
 *    the hazard was DRAW-derived sizing, which this is not.
 */
const val MimirCoverDecodeCapPx: Int = 1024

/** The single Coil memory-cache key for a cover [model]. Every request, warm and probe goes through this. */
fun mimirCoverCacheKey(model: Any): String = "$model#c$MimirCoverDecodeCapPx"

/**
 * The one cover request: the shared cap, the shared key, no crossfade, at INEXACT precision (checked against Coil 2.7 bytecode). INEXACT lets a source smaller than the cap decode at its own
 * size; a larger one still scales down to the cap. Compose painters already forced INEXACT, but the warm passes
 * (`loader.execute` / `enqueue`) resolved the fixed size exactly, so a 500 px cover warmed into the cache upscaled to
 * about 1024 px under the same key: four times the memory against the 30% budget, and the evictions that followed
 * re-decoded covers cold on the way back. [cacheModel] is the identity where [data] wraps it (a `File` for an icon path).
 */
fun mimirCoverRequest(context: Context, data: Any, cacheModel: Any = data): ImageRequest =
    ImageRequest.Builder(context)
        .data(data)
        .size(MimirCoverDecodeCapPx)
        .precision(Precision.INEXACT)
        .memoryCacheKey(mimirCoverCacheKey(cacheModel))
        .placeholderMemoryCacheKey(mimirCoverCacheKey(cacheModel))
        .crossfade(false)
        .build()

/**
 * Is [model]'s bitmap ALREADY in Coil's memory cache — i.e. will it paint on frame 0 rather than fade in?
 *
 * Synchronous by design and cheap: one map lookup on the same [mimirCoverCacheKey] identity the request
 * itself uses. A fresh `AsyncImagePainter` reports not-ready on frame 0 even for a cached bitmap
 * (observed in the earlier implementation), so asking the painter is the wrong question when what you need to know is
 * *"will this surface arrive already wearing its art"*.
 *
 * Extracted 2026-08-26 rather than copied a fourth time: this exact expression lived inline in three
 * places in `MediaArtFrame`, and the fourth caller wanted it from another module. Three copies of a
 * cache-identity predicate is three ways to drift off the one key the whole decode contract rests on.
 */
fun mimirArtWarmInMemory(context: Context, model: Any?): Boolean =
    model != null &&
        context.imageLoader.memoryCache?.get(MemoryCache.Key(mimirCoverCacheKey(model))) != null

/**
 * Bounded [BitmapFactory] decode for the platform-art lane (Library shelf + companion tiles), which
 * bypasses Coil. Power-of-two `inSampleSize` keeps the long edge under 2×[capPx]; picked SGB heroes
 * (often ~1920 px wide) stop decoding at full size for ≤ ~500 px tiles.
 */
fun mimirDecodeBoundedCoverBitmap(path: String, capPx: Int = MimirCoverDecodeCapPx): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= capPx) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
