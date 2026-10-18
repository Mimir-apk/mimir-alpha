package com.gruning.mimir.rewrite.core.ui.select

import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The small decoded images the Appearance pickers draw (a saved background's tile, a saved mark's trimmed mask, an app's launcher icon in
 * the Open-with list), kept for the process (audit 2026-10-14, RC-28: S-07, S-24, S-25, S-27).
 *
 * Each of those used to decode on every composition: the tile was empty, then the picture popped in, every time the page opened. A key this
 * cache holds is read on the composition's FIRST frame, so a second open paints at once and nothing decodes at all; a key it does not hold is
 * decoded once on IO and kept, and fades in from nothing over FocusMs when it lands (Q13, no placeholder, warm paths stay).
 *
 * Bounded by bytes, least recently used out. Entries are keyed by what produced them (the file's path, the sample width, the trim), and the
 * files behind the keys are content-named copies the app wrote once, so a key never goes stale; a path whose file is gone simply is not asked for.
 */
internal object PickerBitmapCache {
    /** Six backgrounds at the tile sample, six trimmed marks and a page of launcher icons, with room to spare. */
    private const val MaxBytes = 6 * 1024 * 1024

    private val entries = object : LruCache<String, ImageBitmap>(MaxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4).coerceAtLeast(1)
    }

    fun peek(key: String): ImageBitmap? = entries.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        entries.put(key, bitmap)
    }

    /** A test between cases; nothing in production needs it. */
    internal fun clear() {
        entries.evictAll()
    }

    /** How many images are held (`LruCache.size()` is the byte total, not the count). */
    internal val size: Int get() = entries.snapshot().size
}

/** The cache key of a saved image decoded to [targetWidthPx] wide. */
fun pickerThumbnailKey(path: String, targetWidthPx: Int): String = "thumb:$targetWidthPx:$path"

/** The cache key of a saved mark's mask, trimmed to its ink, decoded to [targetWidthPx] wide. */
fun pickerMarkKey(path: String, targetWidthPx: Int): String = "mark:$targetWidthPx:$path"

/** The cache key of an installed package's launcher icon at [sizePx]. */
internal fun pickerAppIconKey(packageName: String, sizePx: Int): String = "icon:$sizePx:$packageName"

/**
 * A picker image and the alpha it is drawn at. A warm one (the cache held it on the composition's first frame) is whole at once. A COLD one
 * -- its decode still running on the first frame -- draws nothing until it lands (no placeholder), then fades in over [MimirMotion.FocusMs] on
 * the house curve (a reduced-motion player gets the cut). [alpha] is read in the DRAW pass
 * only, so the fade costs the tile a draw per frame and no recomposition.
 */
class PickerArt internal constructor(val bitmap: ImageBitmap?, private val fade: Animatable<Float, AnimationVector1D>?) {
    /** The alpha to draw [bitmap] at: 1 when warm, otherwise the fade's value. */
    val alpha: Float get() = fade?.value ?: 1f

    companion object {
        val None = PickerArt(null, null)
    }
}

/**
 * The image behind [cacheKey], as [PickerArt]: from [PickerBitmapCache] on the composition's first frame when it is there (whole, no fade),
 * otherwise [decode] on IO, once, kept, and faded in when it lands. Nothing for a null [cacheKey] and for a [decode] that answers null (a
 * missing or unreadable file; nothing is cached for it). [decode] is blocking and runs on IO; it must read nothing from composition.
 */
@Composable
fun rememberPickerArt(cacheKey: String?, decode: () -> ImageBitmap?): PickerArt {
    if (cacheKey == null) return PickerArt.None
    val reduced = mimirReducedMotion()
    // Keyed like the producer it replaces: a new key starts from nothing, never from the image of the key before it.
    return key(cacheKey) {
        val warm = remember { PickerBitmapCache.peek(cacheKey) }
        val fade = remember { Animatable(if (warm != null) 1f else 0f) }
        val bitmap = if (warm != null) {
            warm
        } else {
            produceState<ImageBitmap?>(initialValue = null) {
                val decoded = withContext(Dispatchers.IO) { decode() }?.also { PickerBitmapCache.put(cacheKey, it) }
                value = decoded
                if (decoded != null) {
                    if (reduced) fade.snapTo(1f) else fade.animateTo(1f, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
                }
            }.value
        }
        PickerArt(bitmap, fade)
    }
}
