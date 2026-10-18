package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Mimir-button mark's processing seam (concept A; brief §B and §D), which
 * [AppearanceImageStore.importMark] calls after its bounded copy and content hash.
 *
 * The button strikes the theme's metal THROUGH the mark, so the file this writes is a mask, not a picture: a
 * 256 x 256 PNG with alpha (white ink, so the tile thumbnails read too), with the picture's SUBJECT fitted into it.
 *
 *  - **Decode:** the picture is decoded in software and sRGB with its long edge at most [DecodeLongEdgePx] (never up), so
 *    a wide-gamut source cannot change what the luminance means and a panorama never allocates its full width. An
 *    animated image keeps its first frame ("Animated images are saved as a still."); one under [MinSourceEdgePx] on its
 *    shorter edge is refused, as a background is.
 *  - **Mask** ([appearanceMarkMask]): an image with real transparency keeps its alpha as the mask (its colours are
 *    dropped). An opaque one -- a JPEG, a photo, a flat PNG -- has its luminance written into alpha: light becomes ink,
 *    dark becomes see-through, unless its edges are mostly light (a dark logo on white), when the DARK parts are the mark
 *    ; there is no setting
 *    for it, because R3 rules out a row. Before that reading, an opaque image's edge-connected, near-uniform background
 *    is cut out ([appearanceMarkBackgroundCutout]), so a logo on a blue field or a grey card comes out as the logo, not as a lit card with the logo
 *    on it; a photo, or any picture whose border is not one colour, is left exactly as it was. An image that would leave
 *    (almost) nothing to strike is refused rather than saved as a button with no mark on it.
 *  - **Fit** ([appearanceMarkFit]): the mask's transparent and
 *    empty margins are trimmed to the subject's bounding box, and the subject is scaled to sit centred in the mark
 *    with [MarkInsetPx] clear on every side -- instead of a blind centre crop that lost a wide logo's ends.
 *
 * `p90` is a background's recorded brightness (nothing decides on it any more); a mark has none, so it is 0.
 */
internal object AppearanceMarkProcessing {
    internal const val MarkEdgePx = 256

    /** The clear margin inside the mark's edge around the fitted subject: a sixteenth of the edge, 16 px. */
    internal const val MarkInsetPx = MarkEdgePx / 16

    /** The subject is found and fitted from a copy with its long edge at most this: 512, twice the mark, bounded memory. */
    internal const val DecodeLongEdgePx = MarkEdgePx * 2

    /** A subject under half the edge, in the source's own pixels, is scaled up visibly: "It may look soft." */
    internal const val SoftBelowEdgePx = MarkEdgePx / 2

    internal const val MinSourceEdgePx = 32

    private const val Tag = "MimirAppearanceImage"

    /** The processed mark, or null for any refusal; [processOutcome] says WHICH refusal. */
    fun process(source: File, target: File): AppearanceProcessedImage? =
        (processOutcome(source, target) as? AppearanceProcessOutcome.Processed)?.image

    /**
     * As [process], telling a picture with nothing to strike ([AppearanceProcessOutcome.NoSubject]: blank, or one flat
     * colour, which decoded fine) from one that could not be used at all ([AppearanceProcessOutcome.Unusable]), so the
     * player is told the true reason (cert E6): a blank PNG is not "not a PNG".
     */
    fun processOutcome(source: File, target: File): AppearanceProcessOutcome {
        var animated = false
        var sourceShortEdge = 0
        var sourceLongEdge = 0
        val decoded = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                animated = info.isAnimated
                sourceShortEdge = min(info.size.width, info.size.height)
                sourceLongEdge = max(info.size.width, info.size.height)
                val size = appearanceMarkDecodeSize(info.size.width, info.size.height)
                decoder.setTargetSize(size.width, size.height)
            }
        }.onFailure { Log.w(Tag, "kind=MARK decode failed: ${it.javaClass.simpleName}") }.getOrNull()
            ?: return AppearanceProcessOutcome.Unusable
        try {
            if (sourceShortEdge < MinSourceEdgePx) return AppearanceProcessOutcome.Unusable
            val width = decoded.width
            val height = decoded.height
            val pixels = IntArray(width * height)
            decoded.getPixels(pixels, 0, width, 0, 0, width, height)
            val mask = appearanceMarkMask(pixels, width)
            if (mask == null) {
                Log.i(Tag, "kind=MARK refused: nothing left to strike")
                return AppearanceProcessOutcome.NoSubject
            }
            val fitted = appearanceMarkFit(mask.argb, width, height)
            if (fitted == null) {
                Log.i(Tag, "kind=MARK refused: no subject to fit")
                return AppearanceProcessOutcome.NoSubject
            }
            val out = Bitmap.createBitmap(fitted.argb, MarkEdgePx, MarkEdgePx, Bitmap.Config.ARGB_8888)
            try {
                val written = FileOutputStream(target).use { output -> out.compress(Bitmap.CompressFormat.PNG, 100, output) }
                if (!written) return AppearanceProcessOutcome.Unusable
            } finally {
                out.recycle()
            }
            // The subject's long edge in the SOURCE's own pixels: under half the mark's edge, the fit's upscale shows.
            val decodedLongEdge = max(width, height).coerceAtLeast(1)
            val subjectSourcePx = max(fitted.subjectWidth, fitted.subjectHeight).toLong() * sourceLongEdge / decodedLongEdge
            val note = listOfNotNull(
                AppearanceImageStore.NoteAnimated.takeIf { animated },
                AppearanceImageStore.NoteSoft.takeIf { subjectSourcePx < SoftBelowEdgePx },
            ).joinToString(" · ").ifEmpty { null }
            Log.i(
                Tag,
                "kind=MARK mask=${mask.source.logName} cutout=${mask.backgroundRemoved} " +
                    "subject=${fitted.subjectWidth}x${fitted.subjectHeight} fit=${fitted.fittedWidth}x${fitted.fittedHeight} src=${sourceShortEdge}px",
            )
            return AppearanceProcessOutcome.Processed(
                AppearanceProcessedImage(MarkEdgePx, MarkEdgePx, p90 = 0f, note = note),
            )
        } finally {
            decoded.recycle()
        }
    }
}

/**
 * The size to decode a [width] x [height] source at: its own size, or scaled DOWN so the long edge is
 * [AppearanceMarkProcessing.DecodeLongEdgePx] -- never up, the fit does that once it knows the subject. A degenerate size
 * answers 1 x 1 so the decoder never sees zero.
 */
internal fun appearanceMarkDecodeSize(width: Int, height: Int): CustomImageSize {
    if (width <= 0 || height <= 0) return CustomImageSize(1, 1)
    val longEdge = max(width, height)
    if (longEdge <= AppearanceMarkProcessing.DecodeLongEdgePx) return CustomImageSize(width, height)
    val scale = AppearanceMarkProcessing.DecodeLongEdgePx.toDouble() / longEdge
    return CustomImageSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * Where an image's mask came from: its own transparency, its brightness, or -- for an opaque image with a mostly light
 * border, a dark logo on white -- its DARKNESS. [logName] is the `mask=` value of the import's log line.
 */
internal enum class AppearanceMarkMaskSource(val logName: String) {
    ALPHA("alpha"),
    LUMINANCE("luminance"),
    LUMINANCE_DARK("luminance-dark"),
}

/**
 * White ink whose alpha is the mask, as packed straight ARGB in the picture's own pixels, where the mask came from, and
 * whether an opaque picture's uniform background was cut out first.
 */
internal class AppearanceMarkMask(val argb: IntArray, val source: AppearanceMarkMaskSource, val backgroundRemoved: Boolean = false)

/** A pixel below this alpha is see-through for the "has real transparency" test. */
private const val TranslucentBelowAlpha = 128

/** A mask alpha at or above this is ink for the "is anything left" test. */
private const val InkAtLeastAlpha = 32

/**
 * The outer ring of the picture whose brightness decides which way an opaque image is read, and whose colour is the
 * background a cut-out removes: this fraction of the shorter edge, so 20 px of 256. Wide enough to see the background
 * of a logo or a photo, narrow enough that a subject filling the frame does not outvote it.
 */
internal const val MarkBorderFraction = 0.08

/**
 * The border counts as LIGHT at this luma or above: 128, the mask's own midpoint (mid grey is half ink either way, so a
 * flat mid-grey border is the point where reading the image as light-on-dark or dark-on-light are equally good, and
 * only a border past it is clearly a background one would rather see through).
 */
internal const val MarkLightBorderLuma = 128

/**
 * How far a channel may sit from the border's median colour and still be the same background, in sRGB codes: wide
 * enough for JPEG's flat-area noise and a faint vignette, narrow enough that a subject's own colour, or the blend of a
 * soft edge, is never taken for it.
 */
internal const val MarkCutoutTolerance = 24

/** The share of the border ring that must be the one colour before it is read as a background at all: a photo is not. */
internal const val MarkCutoutUniformFraction = 0.9

/** The cut-out must be at least this share of the picture (there IS a background) ... */
internal const val MarkCutoutMinFraction = 0.05

/** ... and at most this (there IS a subject), else nothing is cut and the picture is read as it always was. */
internal const val MarkCutoutMaxFraction = 0.98

/** A mask alpha at or above this counts as the subject when its bounding box is measured for the fit. */
internal const val MarkTrimAlpha = 16

/**
 * The mask of [pixels] -- a picture [width] wide, packed straight ARGB, as `Bitmap.getPixels` answers -- or null when there
 * is (almost) nothing to strike.
 *
 *  - An image is "transparent" when at least 1% of its pixels are more than half see-through: a logo on nothing, a cut-out.
 *    Its own alpha is then the mask and its colours are dropped, whatever their brightness.
 *  - Anything else is "opaque": a JPEG, a photo, a flat PNG, a picture whose only alpha is a rounded corner. Its LUMINANCE
 *    (the Rec. 709 weights on the sRGB codes, so mid grey is half ink) times its alpha is the mask: light becomes ink --
 *    unless its border is mostly light ([appearanceMarkBorderMedianLuma] at or above [MarkLightBorderLuma]), when it is
 *    read the other way, `(255 - luminance)` times alpha: the dark parts become the mark, so a black logo on white comes out
 *    as the logo and not as a metal square with the logo cut out of it. The median, not the mean, decides, so a logo
 *    touching the edge, or a noisy border, cannot tip it; an exactly even border keeps the light-becomes-ink reading.
 *  - An opaque image's edge-connected, near-uniform background ([appearanceMarkBackgroundCutout]) is made see-through
 *    FIRST, and the reading above is decided on the picture as it was: the border that says "light" is the very
 *    background being removed, and a subject's enclosed light parts (the inside of an O) stay a see-through part of the
 *    reading rather than becoming a solid disc, which is what reading the cut-out as "real transparency" would make them.
 *  - The ink is written as white, so a thumbnail of the file reads on the dark tile and only the alpha matters to the button.
 *  - Under 0.5% of the pixels at ink strength is blank (an all-black JPEG, or an all-white one, which the dark reading
 *    inverts to nothing): null, so the import says so instead of saving a button with no mark on it.
 */
internal fun appearanceMarkMask(pixels: IntArray, width: Int): AppearanceMarkMask? {
    if (pixels.isEmpty()) return null
    require(width > 0 && pixels.size % width == 0) { "${pixels.size} pixels do not fill rows ${width} wide" }
    var seeThrough = 0
    for (pixel in pixels) if ((pixel ushr 24) < TranslucentBelowAlpha) seeThrough++
    val usesAlpha = seeThrough * 100L >= pixels.size
    val darkIsInk = !usesAlpha && (appearanceMarkBorderMedianLuma(pixels, width) ?: 0) >= MarkLightBorderLuma
    val source = when {
        usesAlpha -> AppearanceMarkMaskSource.ALPHA
        darkIsInk -> AppearanceMarkMaskSource.LUMINANCE_DARK
        else -> AppearanceMarkMaskSource.LUMINANCE
    }
    val cutout = if (usesAlpha) null else appearanceMarkBackgroundCutout(pixels, width)
    val out = IntArray(pixels.size)
    var ink = 0
    for (index in pixels.indices) {
        val pixel = pixels[index]
        val alpha = if (cutout != null && cutout[index]) 0 else pixel ushr 24
        val mask = when (source) {
            AppearanceMarkMaskSource.ALPHA -> alpha
            AppearanceMarkMaskSource.LUMINANCE_DARK -> (255 - appearanceMarkLuma(pixel)) * alpha / 255
            AppearanceMarkMaskSource.LUMINANCE -> appearanceMarkLuma(pixel) * alpha / 255
        }
        out[index] = (mask shl 24) or 0x00FFFFFF
        if (mask >= InkAtLeastAlpha) ink++
    }
    if (ink * 200L < pixels.size) return null
    return AppearanceMarkMask(out, source, backgroundRemoved = cutout != null)
}

/**
 * The background of an opaque picture [width] wide that can be cut out, as one flag per pixel (true = background), or
 * null when the picture has no such background and must be read exactly as it was.
 *
 * The background is the ONE colour of the border ring ([MarkBorderFraction] of the shorter edge): the per-channel median
 * of its visible pixels. It counts only when at least [MarkCutoutUniformFraction] of that ring sits within
 * [MarkCutoutTolerance] of it on every channel -- a photo, a gradient or a busy border fails here and nothing is cut.
 * From every such ring pixel the background is flood-filled, four-connected, through pixels within the same tolerance of
 * the border's colour (a see-through pixel is passed through too), so an enclosed region -- the inside of an O, a window
 * in a badge -- is never reached and never removed. The fill must cover at least [MarkCutoutMinFraction] of the picture
 * (there is a background) and at most [MarkCutoutMaxFraction] (there is a subject); outside that the picture is left alone.
 */
internal fun appearanceMarkBackgroundCutout(pixels: IntArray, width: Int): BooleanArray? {
    if (pixels.isEmpty() || width <= 0) return null
    val height = pixels.size / width
    val ring = (min(width, height) * MarkBorderFraction).roundToInt().coerceAtLeast(1)
    // The border's colour: one median per channel, counted by code.
    val red = IntArray(256)
    val green = IntArray(256)
    val blue = IntArray(256)
    var ringVisible = 0
    forEachRingIndex(width, height, ring) { index ->
        val pixel = pixels[index]
        if ((pixel ushr 24) >= TranslucentBelowAlpha) {
            red[(pixel shr 16) and 0xFF]++
            green[(pixel shr 8) and 0xFF]++
            blue[pixel and 0xFF]++
            ringVisible++
        }
    }
    if (ringVisible == 0) return null
    val medianRed = medianCode(red, ringVisible)
    val medianGreen = medianCode(green, ringVisible)
    val medianBlue = medianCode(blue, ringVisible)
    fun near(pixel: Int): Boolean =
        max(
            max(kotlin.math.abs(((pixel shr 16) and 0xFF) - medianRed), kotlin.math.abs(((pixel shr 8) and 0xFF) - medianGreen)),
            kotlin.math.abs((pixel and 0xFF) - medianBlue),
        ) <= MarkCutoutTolerance
    var ringNear = 0
    forEachRingIndex(width, height, ring) { index ->
        val pixel = pixels[index]
        if ((pixel ushr 24) >= TranslucentBelowAlpha && near(pixel)) ringNear++
    }
    if (ringNear < ringVisible * MarkCutoutUniformFraction) return null
    // The flood fill, from the ring inward.
    val background = BooleanArray(pixels.size)
    val queue = IntArray(pixels.size)
    var head = 0
    var tail = 0
    fun fillable(index: Int): Boolean {
        val pixel = pixels[index]
        return (pixel ushr 24) < TranslucentBelowAlpha || near(pixel)
    }
    forEachRingIndex(width, height, ring) { index ->
        if (!background[index] && fillable(index)) {
            background[index] = true
            queue[tail++] = index
        }
    }
    while (head < tail) {
        val index = queue[head++]
        val x = index % width
        val y = index / width
        if (x > 0) visitCutout(index - 1, background, queue, tail, ::fillable).also { tail = it }
        if (x < width - 1) visitCutout(index + 1, background, queue, tail, ::fillable).also { tail = it }
        if (y > 0) visitCutout(index - width, background, queue, tail, ::fillable).also { tail = it }
        if (y < height - 1) visitCutout(index + width, background, queue, tail, ::fillable).also { tail = it }
    }
    val filled = tail
    if (filled < pixels.size * MarkCutoutMinFraction || filled > pixels.size * MarkCutoutMaxFraction) return null
    return background
}

private inline fun visitCutout(index: Int, background: BooleanArray, queue: IntArray, tail: Int, fillable: (Int) -> Boolean): Int {
    if (background[index] || !fillable(index)) return tail
    background[index] = true
    queue[tail] = index
    return tail + 1
}

/** Every index of the outer [ring] of a [width] x [height] picture, once each. */
private inline fun forEachRingIndex(width: Int, height: Int, ring: Int, block: (Int) -> Unit) {
    for (y in 0 until height) {
        val rowIsRing = y < ring || y >= height - ring
        var x = 0
        while (x < width) {
            // Below the top and above the bottom band only the first and last `ring` columns are the border.
            if (!rowIsRing && x == ring && width - ring > x) {
                x = width - ring
                continue
            }
            block(y * width + x)
            x++
        }
    }
}

/** The lower median of [counts] (codes 0..255) over [total] samples. */
private fun medianCode(counts: IntArray, total: Int): Int {
    val medianIndex = (total - 1) / 2
    var seen = 0
    for (code in counts.indices) {
        seen += counts[code]
        if (seen > medianIndex) return code
    }
    return 0
}

/** The fitted mark: [argb] is the 256 x 256 mask, the subject's box in the picture's pixels, and where it landed. */
internal class AppearanceMarkFit(
    val argb: IntArray,
    val subjectWidth: Int,
    val subjectHeight: Int,
    val left: Int,
    val top: Int,
    val fittedWidth: Int,
    val fittedHeight: Int,
)

/**
 * The [mask] (white ink on alpha, [width] x [height]) fitted into the mark: its transparent and empty margins are trimmed
 * to the bounding box of the pixels at or above [MarkTrimAlpha], and that box is scaled -- up or down, keeping its
 * aspect -- to sit centred inside the mark's edge less [AppearanceMarkProcessing.MarkInsetPx] on every side. A wide
 * logo therefore keeps its ends and a tall one its top and bottom, where the old centre crop lost them. Scaling down
 * averages the source pixels each mark pixel covers; scaling up samples bilinearly, so an edge stays soft rather than
 * stepped. Null when the mask holds no subject at all.
 */
internal fun appearanceMarkFit(mask: IntArray, width: Int, height: Int): AppearanceMarkFit? {
    if (mask.isEmpty() || width <= 0 || height <= 0 || mask.size != width * height) return null
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (index in mask.indices) {
        if ((mask[index] ushr 24) < MarkTrimAlpha) continue
        val x = index % width
        val y = index / width
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
    }
    if (right < 0) return null
    val edge = AppearanceMarkProcessing.MarkEdgePx
    val content = edge - 2 * AppearanceMarkProcessing.MarkInsetPx
    val subjectWidth = right - left + 1
    val subjectHeight = bottom - top + 1
    val scale = min(content.toDouble() / subjectWidth, content.toDouble() / subjectHeight)
    val fittedWidth = (subjectWidth * scale).roundToInt().coerceIn(1, content)
    val fittedHeight = (subjectHeight * scale).roundToInt().coerceIn(1, content)
    val originX = (edge - fittedWidth) / 2
    val originY = (edge - fittedHeight) / 2
    val out = IntArray(edge * edge) { 0x00FFFFFF }
    val stepX = subjectWidth.toDouble() / fittedWidth
    val stepY = subjectHeight.toDouble() / fittedHeight
    fun alphaAt(x: Int, y: Int): Int = mask[y * width + x] ushr 24
    for (y in 0 until fittedHeight) {
        for (x in 0 until fittedWidth) {
            val alpha = if (scale < 1.0) {
                // Down: the mean of the source pixels this mark pixel covers.
                val x0 = (left + floor(x * stepX)).toInt().coerceIn(left, right)
                val x1 = (left + ceil((x + 1) * stepX)).toInt().coerceIn(x0 + 1, right + 1)
                val y0 = (top + floor(y * stepY)).toInt().coerceIn(top, bottom)
                val y1 = (top + ceil((y + 1) * stepY)).toInt().coerceIn(y0 + 1, bottom + 1)
                var sum = 0L
                for (sy in y0 until y1) for (sx in x0 until x1) sum += alphaAt(sx, sy)
                (sum / ((x1 - x0).toLong() * (y1 - y0))).toInt()
            } else {
                // Up: a bilinear sample at the mark pixel's centre.
                val sx = (left + (x + 0.5) * stepX - 0.5).coerceIn(left.toDouble(), right.toDouble())
                val sy = (top + (y + 0.5) * stepY - 0.5).coerceIn(top.toDouble(), bottom.toDouble())
                val x0 = floor(sx).toInt()
                val y0 = floor(sy).toInt()
                val x1 = min(x0 + 1, right)
                val y1 = min(y0 + 1, bottom)
                val fx = sx - x0
                val fy = sy - y0
                val topRow = alphaAt(x0, y0) * (1 - fx) + alphaAt(x1, y0) * fx
                val bottomRow = alphaAt(x0, y1) * (1 - fx) + alphaAt(x1, y1) * fx
                (topRow * (1 - fy) + bottomRow * fy).roundToInt()
            }
            out[(originY + y) * edge + originX + x] = (alpha.coerceIn(0, 255) shl 24) or 0x00FFFFFF
        }
    }
    return AppearanceMarkFit(out, subjectWidth, subjectHeight, originX, originY, fittedWidth, fittedHeight)
}

/**
 * The median luma (0..255) of the outer ring of a picture [width] wide -- [MarkBorderFraction] of the shorter edge, at least
 * one pixel -- counting only its visible pixels (a see-through rounded corner says nothing about the picture's
 * background); null when the ring has none. The LOWER median, so a border split exactly in half reads as dark: a tie does
 * not flip an image, and it keeps today's light-becomes-ink reading. Counts by luma code, one pass and no sort.
 */
internal fun appearanceMarkBorderMedianLuma(pixels: IntArray, width: Int): Int? {
    if (pixels.isEmpty() || width <= 0) return null
    val height = pixels.size / width
    val ring = (min(width, height) * MarkBorderFraction).roundToInt().coerceAtLeast(1)
    val counts = IntArray(256)
    var total = 0
    forEachRingIndex(width, height, ring) { index ->
        val pixel = pixels[index]
        if ((pixel ushr 24) >= TranslucentBelowAlpha) {
            counts[appearanceMarkLuma(pixel)]++
            total++
        }
    }
    if (total == 0) return null
    return medianCode(counts, total)
}

/** The Rec. 709 luma of an sRGB pixel's codes, 0..255: white 255, black 0, mid grey (128) 128. */
internal fun appearanceMarkLuma(pixel: Int): Int {
    val r = (pixel shr 16) and 0xFF
    val g = (pixel shr 8) and 0xFF
    val b = pixel and 0xFF
    return (2126 * r + 7152 * g + 722 * b + 5000) / 10000
}
