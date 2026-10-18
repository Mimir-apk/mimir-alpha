package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.PickerArt
import com.gruning.mimir.rewrite.core.ui.select.rememberPickerArt
import com.gruning.mimir.rewrite.core.ui.select.pickerMarkKey

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import com.gruning.mimir.rewrite.core.model.mimirMarkInkBounds
import com.gruning.mimir.rewrite.core.model.mimirMarkTrimCrop

/**
 * A saved Mimir-button mark's mask, trimmed to its ink the way the live button trims it (the picker's small
 * tiles show the image trimmed and bigger, to match the live button), for [MarkCoin] to draw. Decoded once per path on IO with
 * the picker's usual sample ([decodeSampledThumbnail]), then trimmed by the shared rule in `:core-model`
 * ([mimirMarkInkBounds] and [mimirMarkTrimCrop], the arithmetic `trimToInk` in `:core-companion-native` uses), and kept in
 * [PickerBitmapCache] so the next composition of the same mark has it on its first frame. Null while a first decode runs
 * or when the file fails; nothing is logged, and the path is never named in a message.
 */
@Composable
internal fun rememberMimirMarkThumbnail(path: String?): PickerArt =
    // Kept for the process like the background tiles (S-25): a mark seen before is trimmed and painted on the composition's first frame, so
    // the coin's mark does not pop in on every open of the picker; a first one fades in from nothing over FocusMs (Q13).
    rememberPickerArt(path?.let { pickerMarkKey(it, AppearanceThumbnailWidthPx) }) {
        path?.let { file -> decodeTrimmedMarkMask(file)?.asImageBitmap() }
    }

/** The mask at [file] decoded and trimmed to its ink, or null when it is missing or not an image. Blocking: run it on IO. */
internal fun decodeTrimmedMarkMask(file: String, targetWidthPx: Int = AppearanceThumbnailWidthPx): Bitmap? {
    val decoded = decodeSampledThumbnail(file, targetWidthPx) ?: return null
    // A trim that cannot be made leaves the mask as decoded: a tile that shows the image small beats one that shows nothing.
    return runCatching { trimMarkToInk(decoded) }.getOrDefault(decoded)
}

/**
 * [source] cropped to its ink plus the shared margin, the original recycled only when a crop replaces it. A mask with no ink, or
 * one that already reaches its edges, comes back as it was.
 */
internal fun trimMarkToInk(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val crop = mimirMarkTrimCrop(mimirMarkInkBounds(pixels, width, height), width, height) ?: return source
    val cropped = Bitmap.createBitmap(source, crop[0], crop[1], crop[2] - crop[0], crop[3] - crop[1])
    if (cropped !== source) source.recycle()
    return cropped
}
