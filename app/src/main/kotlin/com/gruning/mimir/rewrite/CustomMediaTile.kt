package com.gruning.mimir.rewrite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale

/*
 * What the gallery's tiles say about themselves . The portrait box tile is 80 dp wide (the certified height budget fixes it),
 * so a label that does not fit cannot wrap: a word broken mid-word ("DEFAUL/T", "ScreenSc/raper") reads as a defect. Every piece
 * of tile text is one line and WHOLE, chosen by measuring the room it has:
 *  - every tile's bar is BELOW its art and says everything the tile says about itself: the Default tile's DEFAULT, a saved or
 *    found tile's label (the whole "ScreenScraper · 600×800" if it fits, otherwise the provider's name or short form and the
 *    dimensions, otherwise the dimensions, otherwise the provider), and its check, dot and remove marks. Nothing of this is ever
 *    drawn over the art (the sweep, 10-09), and the bar is the same height lit or not, so focus moves nothing in it;
 *  - a found tile whose size is not known says no size at all (never "size unknown"): its bar says the provider;
 *  - a tile with no preview says NO PREVIEW in its art (or NO ART where even that does not fit).
 */

/** A tile label split at its last " · ": the whole of it, and the dimensions after the provider ("600×800"), if it has them. */
internal data class EnrichTileLabelParts(val full: String, val dims: String?)

internal fun enrichTileLabelParts(label: String): EnrichTileLabelParts {
    val dims = label.substringAfterLast(" · ", missingDelimiterValue = "").trim()
    return EnrichTileLabelParts(label, dims.takeIf { it.isNotEmpty() })
}

/** The provider as a badge, whole first and then in the short form players know it by: "SCREENSCRAPER" then "SS". */
internal fun enrichTileBadgeCandidates(label: String): List<String> {
    val first = label.substringBefore(" · ").trim()
    val short = when {
        first.startsWith("SteamGridDB", ignoreCase = true) -> "SGDB"
        first.startsWith("ScreenScraper", ignoreCase = true) -> "SS"
        first.startsWith("Uploaded", ignoreCase = true) -> "UPLOAD"
        first.startsWith("Existing", ignoreCase = true) -> "SAVED"
        else -> null
    }
    return listOfNotNull(first.uppercase().takeIf { it.isNotBlank() }, short).distinct()
}

internal data class EnrichTileLabelPlan(
    /** The one line in the bar, or null when none fits (or the tile says nothing there). */
    val text: String?,
    /** The provider's name (or its short form), said IN the bar before the text, never on the art; or null. */
    val badge: String?,
)

/**
 * What a tile's bar says: the bar is below the art, and everything a tile says about itself is said there (the sweep, 10-09:
 * badges and captions over the art covered it). Pure: [labelDp] and [badgeDp] are measured widths, [roomDp] is what the bar has
 * for its words (the tile less the bar's padding and the check, dot and remove marks it also carries). [showsLabel] is false for
 * the More tile. [sizeKnown] is false for a found tile with no dimensions: its bar then says only the provider.
 *
 * In order, the first that fits: the whole label ("ScreenScraper · 600×800"); the provider and the dimensions ("SCREENSCRAPER
 * 600×800", then "SS 600×800"); the dimensions alone; the provider alone. Nothing is cut to fit.
 */
internal fun enrichTileLabelPlan(
    label: String,
    showsLabel: Boolean,
    roomDp: Float,
    labelDp: (String) -> Float,
    badgeDp: (String) -> Float,
    sizeKnown: Boolean = true,
    /** A tile that always says one word of its own (the Default tile: DEFAULT), in the bar, whole or not at all. */
    fixedBadge: String? = null,
    gapDp: Float = EnrichBarGapDp,
): EnrichTileLabelPlan {
    if (fixedBadge != null) return EnrichTileLabelPlan(null, fixedBadge.takeIf { badgeDp(it) <= roomDp })
    if (!showsLabel) return EnrichTileLabelPlan(null, null)
    val badges = enrichTileBadgeCandidates(label)
    val providerAlone = EnrichTileLabelPlan(null, badges.firstOrNull { badgeDp(it) <= roomDp })
    if (!sizeKnown) return providerAlone
    val parts = enrichTileLabelParts(label)
    if (labelDp(parts.full) <= roomDp) return EnrichTileLabelPlan(parts.full, null)
    val dims = parts.dims
    if (dims != null) {
        val dimsDp = labelDp(dims)
        badges.firstOrNull { badgeDp(it) + gapDp + dimsDp <= roomDp }?.let { return EnrichTileLabelPlan(dims, it) }
        if (dimsDp <= roomDp) return EnrichTileLabelPlan(dims, null)
    }
    return providerAlone
}

internal data class EnrichTileLabelLayout(
    val plan: EnrichTileLabelPlan,
    /** The bar's height: one line of its label plus its own padding. Every tile's bar is exactly this tall, lit or not. */
    val barHeightDp: Float,
)

/** The bar's own padding across and up and down, and the gap between its children (as the tile has always drawn them). */
private const val EnrichBarVerticalPaddingDp = 3f
private const val EnrichBarGapDp = 5f
private const val EnrichBarDotDp = 6f
private const val EnrichBarRemovePaddingDp = 10f

/** The drawn check mark's seat in a bar (a square, so its width is known without measuring a glyph). */
internal const val EnrichTileCheckDp = 12f

/** The strip's overflow cues, the bar's dot and the bar's remove mark, tagged for the tests that ask where they stand. */
internal const val CustomMediaGalleryCueTag = "custom-media-gallery-cue"
internal const val CustomMediaTileDotTag = "custom-media-bar-dot"
internal const val CustomMediaTileRemoveTag = "custom-media-bar-remove"

/** The tile's check mark, tagged for the tests that ask where it stands. */
internal const val CustomMediaTileCheckTag = "custom-media-check-mark"

/**
 * The "in use" check, DRAWN (not the font's U+2713, which falls back to a symbol font whose line box sat the glyph low and let it
 * hang off the bar on a handheld). It is a fixed square seat centred in the bar, as the footer's plus and the row's magnifier are.
 */
@Composable
internal fun EnrichTileCheck(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(EnrichTileCheckDp.dp).testTag(CustomMediaTileCheckTag)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.10f, h * 0.54f), Offset(w * 0.38f, h * 0.80f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.38f, h * 0.80f), Offset(w * 0.92f, h * 0.22f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/**
 * The strip's "more this way" cue: a drawn chevron (never a rune, which reads as a back arrow or a letter), shown only when the strip
 * overflows and there is more on that side.
 */
@Composable
internal fun EnrichGalleryCue(pointsRight: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 8.dp, height = 14.dp).testTag(CustomMediaGalleryCueTag)) {
        val stroke = 1.6.dp.toPx()
        val tipX = if (pointsRight) size.width else 0f
        val tailX = if (pointsRight) 0f else size.width
        drawLine(color, Offset(tailX, 0f), Offset(tipX, size.height / 2f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(tipX, size.height / 2f), Offset(tailX, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** One bar's height in dp: a line of the bar's label and its padding, measured so it is right at every font scale. */
@Composable
internal fun rememberEnrichTileBarHeightDp(): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        with(density) { measurer.measure("0", MimirTypeScale.RailAction, maxLines = 1).size.height.toDp().value } + EnrichBarVerticalPaddingDp * 2
    }
}

@Composable
internal fun rememberEnrichTileLabelLayout(
    label: String,
    showsLabel: Boolean,
    sizeKnown: Boolean,
    tileWidthDp: Int,
    active: Boolean,
    savedDot: Boolean,
    /** Whether the tile can be removed at all: the remove mark's seat is reserved whether or not it is lit, so focus never moves the bar's marks. */
    removable: Boolean,
    fixedBadge: String? = null,
): EnrichTileLabelLayout {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val barHeightDp = rememberEnrichTileBarHeightDp()
    return remember(label, showsLabel, sizeKnown, tileWidthDp, active, savedDot, removable, fixedBadge, barHeightDp, measurer, density) {
        fun widthDp(text: String, style: TextStyle): Float = with(density) {
            measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp().value
        }
        val barLabel = MimirTypeScale.RailAction
        val tick = if (active) EnrichTileCheckDp else 0f
        val remove = if (removable) widthDp("×", MimirTypeScale.Pill) + EnrichBarRemovePaddingDp else 0f
        val dot = if (savedDot) EnrichBarDotDp else 0f
        val marks = listOf(tick, dot, remove).filter { it > 0f }
        val marksDp = marks.sum() + EnrichBarGapDp * marks.size
        val roomDp = tileWidthDp - MimirSpacing.sm.value * 2 - marksDp - 1f
        EnrichTileLabelLayout(
            plan = enrichTileLabelPlan(
                label = label,
                showsLabel = showsLabel,
                roomDp = roomDp,
                labelDp = { widthDp(it, barLabel) },
                badgeDp = { widthDp(it.uppercase(), MimirTypeScale.MicroStatus) },
                sizeKnown = sizeKnown,
                fixedBadge = fixedBadge,
            ),
            barHeightDp = barHeightDp,
        )
    }
}

/**
 * The narrowest the Default tile may be, whole dp: its bar holds DEFAULT, the check and the bar's own padding at the live font scale, so the word
 * is never the thing that gives way (a cover's portrait tile, 80 dp, has no room for both at font 1.3). Measured, so it is right at every scale.
 */
@Composable
internal fun rememberEnrichDefaultTileFloorDp(): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        val word = with(density) {
            measurer.measure(MimirCopy.Default.uppercase(), MimirTypeScale.MicroStatus, maxLines = 1, softWrap = false).size.width.toDp().value
        }
        // The room the bar counts (rememberEnrichTileLabelLayout): the tile less the bar's padding across, the check and its gap, and 1 dp of margin.
        kotlin.math.ceil(word + EnrichTileCheckDp + EnrichBarGapDp + MimirSpacing.sm.value * 2 + 1f).toInt()
    }
}

/** The no-preview words a tile can hold, widest first: NO PREVIEW, then NO ART where even that has no room. */
internal val EnrichTileNoPreviewWords: List<String> = listOf("NO PREVIEW", "NO ART")

/**
 * A tile with no preview to show: NO PREVIEW, one line, centred in the art (the bar is below the art, so nothing covers it). A result still to be prepared keeps its own words.
 */
@Composable
internal fun EnrichTileNoPreview(
    asset: HomeCustomEnrichAsset,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val measurer = rememberTextMeasurer()
    // The plate is the whole art (tagged before the padding); only its words keep clear of the edges .
    Box(
        modifier
            .fillMaxSize()
            .testTag(CustomMediaTilePlateTag)
            .padding(MimirSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        if (homeCustomEnrichPreviewActionLabel(asset, previewUnavailable = true) != "PREVIEW UNAVAILABLE") {
            EnrichPreviewActionLabel(asset, Modifier)
        } else {
            BoxWithConstraints(contentAlignment = Alignment.Center) {
                val words = EnrichTileNoPreviewWords.firstOrNull {
                    measurer.measure(it, MimirTypeScale.MicroStatus, maxLines = 1, softWrap = false).size.width <= constraints.maxWidth
                }
                if (words != null) {
                    MimirChromeText(
                        text = "No preview",
                        visualOverride = words,
                        style = MimirTypeScale.MicroStatus,
                        color = palette.textSecondary.copy(alpha = 0.84f),
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal fun customMediaTileTag(stableId: String): String = "custom-media-tile-$stableId"

internal const val CustomMediaTileBarTag = "custom-media-label-bar"

/** The tile's content plate (the Default face, the art, the no-preview box): it fills the tile, so the ring hugs what is drawn. */
internal const val CustomMediaTilePlateTag = "custom-media-plate"

/** The title book on a Default tile (its box is the book itself, offset up to centre the title-to-rune window), tagged for the tests that ask where it stands. */
internal const val CustomMediaTitleBookTag = "custom-media-title-book"

/** The words a found tile used to carry when it knew no dimensions; it carries nothing now . */
private const val EnrichSizeUnknownFiller = " · size unknown"

/** A stored label without the old "size unknown" filler, which a choice saved before the earlier update may still carry. */
internal fun enrichLabelWithoutSizeFiller(label: String): String = label.removeSuffix(EnrichSizeUnknownFiller)

/**
 * Whether a gallery tile knows its size. A found tile with no dimensions does not, and a choice saved with the old filler did
 * not; everything else (Default, More, an upload, a legacy pick) has no size to know and reads as known.
 */
internal fun homeCustomEnrichAssetSizeKnown(asset: HomeCustomEnrichAsset): Boolean = when (asset) {
    is HomeCustomEnrichAsset.Result -> asset.result.width > 0 && asset.result.height > 0
    is HomeCustomEnrichAsset.Saved -> !asset.record.label.endsWith(EnrichSizeUnknownFiller)
    HomeCustomEnrichAsset.Automatic, HomeCustomEnrichAsset.More -> true
}

// ── A cover tile is the shape of its art ──────────────────────────────────────────────────────────────────────────────
//
// The cover strip had ONE width for every cover (0.72 of the strip's height), so a Nintendo DS cover (about 0.9) or a square
// box render was cropped at the Default tile and barred on the others, and the ring framed glass the art did not fill. The strip's
// HEIGHT is the certified budget and stays; each tile's WIDTH is that height times its art's own aspect, clamped between the old
// portrait tile (the narrowest) and a square (the widest: an icon tile at that height), so the art fills the tile and the ring hugs it.
// With no aspect known the tile is the old 0.72 one.

/**
 * The certified tile: the strip's band less its air above and below. The band is the certified height budget (148/124 and the rest). Where the
 * page has no room (a compact panel, whose height is all spent) the bar below the art (the 10-09 sweep) is carved OUT of this height; where it
 * has (Main) the strip grows by up to a bar's height ([homeCustomEnrichStripGrowthDp]) and the art keeps this height and the widths that go
 * with it. The room comes from the preview stage, which is below the strip and takes only what the strip leaves it.
 */
internal fun homeCustomEnrichTileHeightDp(kind: EnrichMediaKind, compactSquare: Boolean): Int =
    homeCustomEnrichGalleryMinHeightDp(kind, compactSquare) - EnrichGalleryVerticalAirDp * 2

/**
 * How much taller than its certified band the strip stands: the bar's height ([barDp]) where the gallery's measured height has that much room
 * beyond the band, whatever room there is where it has less, and nothing on a compact square. Never more than the room, so the strip never
 * overflows the slot it was given and the certified viewports' budgets hold.
 */
internal fun homeCustomEnrichStripGrowthDp(measuredGalleryHeightDp: Int, kind: EnrichMediaKind, compactSquare: Boolean, barDp: Int): Int =
    if (compactSquare) 0 else (measuredGalleryHeightDp - homeCustomEnrichGalleryMinHeightDp(kind, compactSquare)).coerceIn(0, barDp.coerceAtLeast(0))

/** A cover tile's whole height: the strip's, that is the cover band less its air above and below. */
internal fun homeCustomEnrichCoverTileHeightDp(compactSquare: Boolean): Int =
    homeCustomEnrichTileHeightDp(EnrichMediaKind.COVER, compactSquare)

/**
 * The art's height on a tile: the tile (the certified one, and [growthDp] more where the strip grows) less its [barDp] (a bar is one measured
 * label line and its padding, whole dp). With the strip grown by a whole bar, that is the certified tile's height.
 */
internal fun homeCustomEnrichTileArtHeightDp(kind: EnrichMediaKind, compactSquare: Boolean, barDp: Int, growthDp: Int = 0): Int =
    (homeCustomEnrichTileHeightDp(kind, compactSquare) + growthDp - barDp).coerceAtLeast(1)

/**
 * The width of a non-cover tile: its art's own shape (a square for an icon or a cartridge, 16:9 for a hero or a clip) at the art's height,
 * never more than the kind's authored width or the viewport allows. A cover takes [homeCustomEnrichCoverTileWidthDp].
 */
internal fun homeCustomEnrichBarredTileWidthDp(
    kind: EnrichMediaKind,
    compactSquare: Boolean,
    galleryViewportWidthDp: Int,
    barDp: Int,
    growthDp: Int = 0,
): Int {
    val effective = homeCustomEnrichEffectiveTileWidthDp(kind, compactSquare, galleryViewportWidthDp)
    val aspect = when (kind) {
        EnrichMediaKind.ICON, EnrichMediaKind.TOME_PHYSICAL -> 1f
        EnrichMediaKind.HERO, EnrichMediaKind.CLIP -> 16f / 9f
        EnrichMediaKind.COVER -> return effective
    }
    return minOf(effective, kotlin.math.round(homeCustomEnrichTileArtHeightDp(kind, compactSquare, barDp, growthDp) * aspect).toInt())
}

/**
 * The aspect (width over height) of what a tile will show, or null when it is not known. A saved or found choice carries its
 * dimensions; the Default tile's art is only known once it has loaded ([defaultAspect], reported by the tile itself).
 */
internal fun homeCustomEnrichAssetAspect(asset: HomeCustomEnrichAsset, defaultAspect: Float?): Float? {
    val (width, height) = when (asset) {
        HomeCustomEnrichAsset.Automatic -> return defaultAspect?.takeIf { it.isFinite() && it > 0f }
        HomeCustomEnrichAsset.More -> return null
        is HomeCustomEnrichAsset.Saved -> asset.record.width to asset.record.height
        is HomeCustomEnrichAsset.Result -> asset.result.width to asset.result.height
    }
    return if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
}

/**
 * One cover tile's width: its art's height (the strip's, less [barDp]) times [aspect], clamped to the old portrait tile at the low end and a square at the high
 * end, and never wider than the gallery viewport allows ([homeCustomEnrichEffectiveTileWidthDp] decides that for the portrait tile).
 * With no aspect it is the portrait tile.
 */
internal fun homeCustomEnrichCoverTileWidthDp(
    compactSquare: Boolean,
    aspect: Float?,
    galleryViewportWidthDp: Int = 0,
    barDp: Int = 0,
    growthDp: Int = 0,
): Int {
    val portrait = homeCustomEnrichEffectiveTileWidthDp(EnrichMediaKind.COVER, compactSquare, galleryViewportWidthDp)
    if (aspect == null || !aspect.isFinite() || aspect <= 0f) return portrait
    // The art is the tile less its bar: a square is as wide as the art is tall.
    val artHeight = homeCustomEnrichTileArtHeightDp(EnrichMediaKind.COVER, compactSquare, barDp, growthDp)
    val shaped = kotlin.math.round(artHeight * aspect).toInt()
    // The widest is a 2:1 picture's own shape (a wide banner, a panorama box render), never more than the strip's viewport lets a ring-safe tile be.
    val widest = kotlin.math.round(artHeight * EnrichCoverTileMaxAspect).toInt()
        .let { if (galleryViewportWidthDp > 0) minOf(it, (galleryViewportWidthDp - EnrichRingClearanceDp * 2).coerceAtLeast(96)) else it }
    return shaped.coerceIn(portrait, widest.coerceAtLeast(portrait))
}

/** The widest picture a cover tile takes whole, as width over height: 2:1. A wider one is contained in a 2:1 tile with its bars. */
internal const val EnrichCoverTileMaxAspect = 2f

// ── The title book on a tile ─────────────────────────────────────────────────────────────────────────────────────────
//
// The title book (MediaArtBookCover) is 3:4: a tooled rule and the title near its top, an embossed rune near its bottom. A Default tile shows ALL of it,
// contained: the tile takes the book's own shape (the way a cover tile takes its art's), so the book fills it with no band beside it and nothing is
// cropped (reported on device: the cover was cut at the top, its rule gone and the title under the ring; before that the bar covered the rune).

/** The title book's own shape, width over height. */
internal const val EnrichTitleBookAspect = 0.75f

/** The book's width in an art box: contained, the whole box width at most and never taller than the box. */
internal fun homeCustomEnrichTitleBookWidthDp(boxWidthDp: Float, boxHeightDp: Float): Float =
    minOf(boxWidthDp, boxHeightDp * EnrichTitleBookAspect).coerceAtLeast(1f)

/**
 * The width a Default tile takes from its own art, or null where the kind's one width stands (and for a cover's loaded art, which has its own law,
 * [homeCustomEnrichCoverTileWidthDp]). The title book is 3:4 at the art's height; a loaded picture is its own shape, between a narrow portrait and
 * [maxWidthDp] (the icon tile's square). The art is contained in that tile, so the tile is what the whole of the art makes it.
 */
internal fun homeCustomEnrichDefaultTileWidthDp(
    kind: EnrichMediaKind,
    art: HomeCustomEnrichDefaultArt,
    loadedAspect: Float?,
    artHeightDp: Int,
    maxWidthDp: Int,
): Int? = when {
    kind != EnrichMediaKind.ICON && kind != EnrichMediaKind.TOME_PHYSICAL && kind != EnrichMediaKind.COVER -> null
    art is HomeCustomEnrichDefaultArt.TitleBook -> kotlin.math.round(artHeightDp * EnrichTitleBookAspect).toInt()
    art is HomeCustomEnrichDefaultArt.Automatic && kind != EnrichMediaKind.COVER && loadedAspect != null && loadedAspect.isFinite() && loadedAspect > 0f ->
        kotlin.math.round(artHeightDp * loadedAspect).toInt().coerceIn(kotlin.math.round(artHeightDp * 0.5f).toInt(), maxWidthDp.coerceAtLeast(1))
    else -> null
}

/**
 * The aspect (width over height) of a LOCAL image, read from its file header alone (no decode): null for a model that is not a readable
 * local file. The Default tile asks this first, so its tile takes the art's shape at once; the painter's own size is the fallback for
 * art that is not a file. Call it off the main thread.
 */
internal fun customMediaLocalImageAspect(model: String): Float? {
    val path = when {
        model.startsWith("file:") -> android.net.Uri.parse(model).path
        model.startsWith("/") -> model
        else -> null
    } ?: return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        // Through Java's own file handling (as the image loader does): the native decoder takes a path string as it is.
        java.io.File(path).inputStream().use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    } catch (e: java.io.IOException) {
        return null
    } catch (e: SecurityException) {
        return null
    }
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight.toFloat() else null
}

/** Whether [widthDp] is the art's own shape (not clamped), so the art fills the tile with nothing cropped and no bars. */
internal fun homeCustomEnrichCoverTileFitsAspect(widthDp: Int, aspect: Float?, compactSquare: Boolean, barDp: Int = 0, growthDp: Int = 0): Boolean {
    if (aspect == null || !aspect.isFinite() || aspect <= 0f) return false
    val heightDp = homeCustomEnrichTileArtHeightDp(EnrichMediaKind.COVER, compactSquare, barDp, growthDp)
    return kotlin.math.abs(widthDp.toFloat() / heightDp - aspect) <= 0.5f / heightDp + 1e-4f
}

/**
 * Deterministic gallery scroll seat for a focused asset when the tiles are not all one width: [homeCustomEnrichGalleryScrollOffsetDp]'s
 * law, with each tile's own width. The previous tile stays peeked on the left and the ring clearance is honoured. Given the strip's
 * [viewportDp], the focused tile is also seated WHOLE: a wide cover (a 2:1 picture's own shape) is never cut at the strip's right edge
 * because the tile before it was kept in view; the peek gives up what the focused tile needs, and the tile's own start always shows.
 */
internal fun homeCustomEnrichGalleryScrollOffsetDp(
    focusedAssetIndex: Int,
    tileWidthsDp: List<Int>,
    gapDp: Int,
    viewportDp: Int = 0,
): Float {
    if (focusedAssetIndex <= 0 || focusedAssetIndex > tileWidthsDp.lastIndex) return 0f
    val childX = EnrichRingClearanceDp + tileWidthsDp.take(focusedAssetIndex).sum() + gapDp * focusedAssetIndex
    val peeked = (childX - tileWidthsDp[focusedAssetIndex - 1] - gapDp - EnrichRingClearanceDp).coerceAtLeast(0)
    if (viewportDp <= 0) return peeked.toFloat()
    val whole = (childX + tileWidthsDp[focusedAssetIndex] + EnrichRingClearanceDp - viewportDp).coerceAtMost(childX - EnrichRingClearanceDp)
    return maxOf(peeked, whole).coerceAtLeast(0).toFloat()
}
