package com.gruning.mimir.rewrite.core.companionnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import com.gruning.mimir.rewrite.core.model.mimirMarkInkBounds
import com.gruning.mimir.rewrite.core.model.mimirMarkTrimCrop
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MARK_TAG = "MimirCompanionNative"

/**
 * The drawable the floating ᛗ button strikes its metal through.
 *
 * [CompanionButtonMark.Rune] is the shipping Mannaz vector. [CompanionButtonMark.Image] is the player's 256 px mask
 * PNG from the app's own storage: a bitmap whose ALPHA is the mask, which `CompanionMenuCoinView` draws into a layer and
 * composites the theme's metal through with `SRC_IN`, the path the rune takes. So this returns a plain drawable and the
 * coin needs no second branch. The bitmap is the mask TRIMMED to its ink ([trimToInk]), so it is not square: the coin fits it.
 *
 * **A missing or unreadable file draws the rune** and logs `mark=rune reason=missing` (with the file's content-hash id,
 * never its path): the copy is app-private, but a hand-deleted or truncated file must never leave a button with no mark.
 * Called from the coin's construction on the main thread, so it decodes at most once per file (see
 * [CompanionButtonMarkImages]) and never for the rune.
 */
internal fun companionButtonMarkDrawable(context: Context, mark: CompanionButtonMark): Drawable? {
    if (mark is CompanionButtonMark.Image) {
        val bitmap = CompanionButtonMarkImages.bitmapFor(mark.path)
        if (bitmap != null) {
            // Each coin gets its own drawable (its bounds are the coin's), sharing the one decoded bitmap.
            return BitmapDrawable(context.resources, bitmap).apply { isFilterBitmap = true }
        }
        Log.i(MARK_TAG, "mark=rune reason=missing id=${mark.id}")
    }
    return context.getDrawable(R.drawable.mimir_mark)
}

/**
 * Decoded mask bitmaps, eight at a time (the six saved marks and the committed one, with a spare): a 256 px mask is a
 * quarter of a MiB, and the live button and the stage's three copies all read the same one, so sweeping the picker's
 * tiles decodes each mask once, not once per focus move. An entry is trusted only while the file's length and modified
 * time still match, so a rewritten or truncated file is decoded again rather than served stale.
 */
internal object CompanionButtonMarkImages {
    /** The processed mask is 256 px; anything past this is not ours, and a decode of it is refused. */
    internal const val MaxEdgePx = 1_024

    private class Entry(val length: Long, val modifiedMillis: Long, val bitmap: Bitmap)

    private val cache = LruCache<String, Entry>(8)

    /** The mask at [path], or null when the file is missing, empty, oversized or not a decodable image. */
    fun bitmapFor(path: String): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L) return null
        val modified = file.lastModified()
        cache.get(path)?.let { entry ->
            if (entry.length == length && entry.modifiedMillis == modified && !entry.bitmap.isRecycled) return entry.bitmap
        }
        val bitmap = decode(path) ?: return null
        cache.put(path, Entry(length, modified, bitmap))
        return bitmap
    }

    /** True when [path]'s mask is already decoded and trimmed: [bitmapFor] would answer from the cache without touching the decoder. No file is read. */
    fun isResident(path: String): Boolean = cache.get(path)?.bitmap?.isRecycled == false

    /** Forget every decoded mask (a test between files; nothing in production needs it). */
    internal fun clear() {
        cache.evictAll()
    }

    private fun decode(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth !in 1..MaxEdgePx || bounds.outHeight !in 1..MaxEdgePx) return@runCatching null
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )?.let { trimToInk(it) }?.apply {
            // The mask is drawn about a quarter of its size at 1x and half at the stage's 2x: minify from mips.
            setHasMipMap(true)
        }
    }.getOrNull()
}

/**
 * Decodes and trims the mask at [path] into the shared cache on the Default dispatcher, so the coin built from it a moment later (on the main
 * thread, [companionButtonMarkDrawable]) reads the cache and does not decode up to 1024 px and trim it on the UI thread when a new custom mark
 * first takes focus (audit 2026-10-14, RC-29: S-25). True when the mask is resident afterwards; false for a missing or unreadable file, which the
 * coin then draws as the rune. Nothing is logged and the path is never named.
 */
suspend fun warmMimirButtonMark(path: String): Boolean = withContext(Dispatchers.Default) {
    CompanionButtonMarkImages.bitmapFor(path) != null
}

/** True when [path]'s mask is already resident, so a coin built from it now draws without a decode on the calling thread. */
fun isMimirButtonMarkResident(path: String): Boolean = CompanionButtonMarkImages.isResident(path)

/**
 * [source] cropped to its ink (the custom mark must be bigger). A logo or a photograph carries transparent
 * padding of its own, and drawn in a box that padding made the mark read small however large the box was. The crop is the
 * ink's bounding box plus a 2 px margin on each side ([mimirMarkInkBounds] and [mimirMarkTrimCrop], in `:core-model`, the same
 * arithmetic the Settings picker's tiles trim with), held in the cache as THE decoded mask so every coin that draws it (the live
 * button on both displays and the stage's copies) reads the same trimmed bitmap.
 *
 * A mask with no ink, or one that already reaches its edges, is returned as it came; the original is recycled only when a
 * crop replaces it. The result is not square, so the coin fits it by aspect (`mimirMarkDrawRect` in `:core-model`, with the player's placement).
 */
internal fun trimToInk(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val crop = mimirMarkTrimCrop(mimirMarkInkBounds(pixels, width, height), width, height) ?: return source
    val cropped = Bitmap.createBitmap(source, crop[0], crop[1], crop[2] - crop[0], crop[3] - crop[1])
    if (cropped !== source) source.recycle()
    return cropped
}
