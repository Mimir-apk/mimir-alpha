package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.resolve
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/** The gap between the gamelist header's title and its "Local library" tag. */
internal const val GamelistTitleTagGapDp = 10f

/** The tag's own padding on each side. */
internal const val GamelistLocalLibraryTagPaddingDp = 9f

/** The light-contrast rail's padding on each side of the title and its tag. */
internal const val GamelistTitleRailPaddingDp = 8f

/** The English words the tag is measured with when a caller names none; the header measures and draws the player's language's (`library_gamelist_local_library`). */
internal const val GamelistLocalLibraryTagText = "Local library"

/** The header title's style: one place for the drawing ([GamelistHeader]'s title) and for the measure below. */
internal fun gamelistTitleStyle(compact: Boolean): TextStyle =
    MimirType.Title.copy(fontSize = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.Medium)

/**
 * The least room, in dp, the wide header's title and its tag need: the title's first word and an ellipsis ([ShelfEllipsis]; a one-word title is its whole word), the gap, and the
 * "Local library" tag at its full width, plus the light-contrast rail's padding when it is drawn. Below that the title can show no whole word, and the text engine's last resort,
 * an ellipsis inside the first word ("S…"), is what draws (sweep 10-11, the RP5 at font 1.3, round 2). Measured with the styles the header draws, as [GamelistSearchBar] measures
 * its placeholder.
 */
internal fun gamelistWideTitleFloorDp(
    title: String,
    mediaContrastRail: Boolean,
    measurer: TextMeasurer,
    density: Density,
    tagText: String = GamelistLocalLibraryTagText,
): Float {
    val trimmed = title.trim()
    val first = trimmed.split(Regex("\\s+")).first()
    val shown = if (first.length < trimmed.length) first + ShelfEllipsis else trimmed
    return gamelistWideTitleSpanDp(shown, mediaContrastRail, measurer, density, tagText)
}

/** The room, in dp, the wide header's WHOLE title and its tag stand in: the same measure as [gamelistWideTitleFloorDp], of every word. */
internal fun gamelistWideTitleWholeDp(
    title: String,
    mediaContrastRail: Boolean,
    measurer: TextMeasurer,
    density: Density,
    tagText: String = GamelistLocalLibraryTagText,
): Float =
    gamelistWideTitleSpanDp(title.trim(), mediaContrastRail, measurer, density, tagText) + GamelistMeasureSlackDp

private fun gamelistWideTitleSpanDp(shown: String, mediaContrastRail: Boolean, measurer: TextMeasurer, density: Density, tagText: String): Float {
    val titlePx = measurer.measure(shown, gamelistTitleStyle(compact = false), maxLines = 1, softWrap = false).size.width
    val tagWords = MimirTextIntent.StaticChrome(tagText).resolve().visualText
    val tagPx = measurer.measure(tagWords, MimirType.Pill, maxLines = 1, softWrap = false).size.width
    val measured = with(density) { (titlePx + tagPx).toDp().value }
    val rail = if (mediaContrastRail) 2f * GamelistTitleRailPaddingDp else 0f
    return measured + GamelistTitleTagGapDp + 2f * GamelistLocalLibraryTagPaddingDp + rail
}

/** The least room the count keeps when the row is too short for the title's first word beside it: it is shortened at a word to this, not below. */
internal const val GamelistMetaFloorDp = 48f

/** The light-contrast rail pads the count's label this much on each side. */
internal const val GamelistMetaRailPaddingDp = 6f

/** What the title's and the count's measures are widened by, in dp: the layout rounds each text to a whole pixel, and a count one pixel short is cut at a word. */
private const val GamelistMeasureSlackDp = 1f

/** The room, in dp, the wide header's count ("Favorites · 2 games") stands in at its natural width, the light-contrast rail's padding counted when it is drawn. */
internal fun gamelistMetaWholeDp(meta: String, mediaContrastRail: Boolean, measurer: TextMeasurer, density: Density): Float {
    val px = measurer.measure(meta, MimirType.RowSupport, maxLines = 1, softWrap = false).size.width
    val rail = if (mediaContrastRail) 2f * GamelistMetaRailPaddingDp else 0f
    return with(density) { px.toDp().value } + rail + GamelistMeasureSlackDp
}

/**
 * The width the wide header gives Search so that its title (with the tag) and its count stand WHOLE, or null when they cannot stand whole even beside the magnifier. Search gives way
 * first: it keeps [fullSearchDp] when the row has the room, narrows to what is left down to its readability floor (a label still readable), then is the
 * [LibraryGamelistSearchMarkWidthDp] magnifier alone. Only when even that leaves the title short is the answer null and the title shortens at a word, as it always did
 * (combined33 sweep: at font 1.3 the status pill grew, and the row that had held title, count and a full pill to the end of the old reserve held them no longer).
 */
internal fun gamelistSearchSqueezeDp(rowWidthDp: Float, titleWholeDp: Float, metaWholeDp: Float, gapDp: Float, fullSearchDp: Float): Float? {
    val room = rowWidthDp - titleWholeDp - gapDp - metaWholeDp - gapDp
    return when {
        room >= fullSearchDp -> fullSearchDp
        room >= LibraryGamelistSearchReadabilityFloorDp -> room
        room >= LibraryGamelistSearchMarkWidthDp -> LibraryGamelistSearchMarkWidthDp
        else -> null
    }
}
