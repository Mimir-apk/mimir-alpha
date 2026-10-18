package com.gruning.mimir.rewrite.feature.files

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.gruning.mimir.rewrite.core.ui.MimirEllipsis
import com.gruning.mimir.rewrite.core.ui.MimirSoftHyphenTextStyle
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirHyphenateWideWords

/*
 * What a file tile says under its type chip: the NAME and the META line, set to the tile's room (emulator re-look vqa17, 2026-10-13, font 1.3: "vqa17-ma" / "rk", and "PNG · 3.0..."). The text-fit policy is that text never breaks inside a word; the cure for a file name is its own separators, and the one shared machinery for the rest.
 *
 * The name ([fileBrowserFitTileName]) is tried in this order and the first that holds on [FileBrowserTileNameLines] lines wins; a name that already holds is drawn exactly as it always was.
 *  1. As it is, when every line ends at a space or a separator (the engine does not always end one after a hyphen).
 *  2. With a zero-width space after each separator (`-`, `_` and `.`, not between two digits, not a leading dot), so "vqa17-mark" is "vqa17-" / "mark" and "holiday_photo_final" is
 *     "holiday_" / "photo_final".
 *  2b. A run between two breaks that is itself wider than the tile breaks where a letter meets a digit ( is "IMG20261013" / "WA0001", "vqa18screenshot..." is
 *     "vqa18" / "screenshot..."), never inside a run of digits, and only in a run too wide to stand on a line, so "Level 1st" is not split, and never between a number and its ordinal suffix ("Level" / "19th_edition").
 *  3. A run between two breaks that is still wider than the tile breaks at a SYLLABLE HYPHEN, "Screen-" / "shot", by the shared [mimirHyphenateWideWords] (R5).
 *  4. Cut at a separator, a letter-digit boundary or a space with an ellipsis, "Screenshot_20261013…", the way [com.gruning.mimir.rewrite.core.ui.mimirEllipsizeAtWord] cuts at a space (steps 2, 2b and 3 apply to the head).
 *  4b. When that cut would leave a line unused (it ends on the first line) and the name's own breaks give it all its lines, the last line is the rest of the name cut by the ellipsis: "vqa18" / "screenshotlo…".
 *  5. One line with an ellipsis, when even the first run has no hyphen to break at (a long name with no separator). The type is never made smaller than the design scale.
 * What is shortened is still spoken whole: the node's `contentDescription` is the name.
 *
 * The meta ([FileBrowserTileMeta]) is the first of [fileBrowserTileMetaCandidates] that stands on one line, so `PNG · 3.0 KB` becomes `3.0 KB` before it would become "PNG · 3.0...".
 */

/** How many lines a file tile's name may take. */
internal const val FileBrowserTileNameLines = 2

/**
 * Where a name's runs may be broken at a syllable hyphen, for the file tile names. Null, the product's value, asks the platform's hyphenator ([mimirHyphenateWideWords]); a host JVM ships no
 * hyphenation patterns, so a test of the drawing provides a dictionary here.
 */
internal val LocalFileBrowserNameBreaks = compositionLocalOf<((word: String) -> List<Int>?)?> { null }

private const val ZeroWidthSpace = '\u200B'
private const val SoftHyphen = '\u00AD'

/** What a file tile draws for its name: [text] (with zero-width spaces and soft hyphens where a line may end), in the soft-hyphen style when [hyphenated], on one line when [oneLine]. */
internal data class FileBrowserTileNameFit(val text: String, val hyphenated: Boolean = false, val oneLine: Boolean = false)

private fun isNameSeparator(c: Char): Boolean = c == '-' || c == '_' || c == '.'

private val OrdinalSuffixes = setOf("st", "nd", "rd", "th")

/** True when the two letters at [start] are an English ordinal's suffix (any case) that ends the name or is followed by a non-letter: "19th_edition", "2nd", "3RD", "21st.png", but not "3rdparty". */
private fun isOrdinalSuffix(text: String, start: Int): Boolean =
    start + 2 <= text.length && text.substring(start, start + 2).lowercase() in OrdinalSuffixes && (start + 2 == text.length || !text[start + 2].isLetter())

/**
 * True when a line may end between [text]'s characters at [nextStart - 1] and [nextStart] because a letter meets a digit or a digit meets a letter. Never inside a run of digits or of letters, never after a
 * single leading character ("v1.5", "x2" are not "v" / "1.5"), and never between a number and its ordinal suffix ("Level" | "19th_edition", not "Level19" | "th_edition", keep ordinals whole).
 */
internal fun fileBrowserLetterDigitBoundary(text: String, nextStart: Int): Boolean {
    if (nextStart < 2 || nextStart >= text.length) return false
    val before = text[nextStart - 1]
    val after = text[nextStart]
    return (before.isDigit() && after.isLetter() && !isOrdinalSuffix(text, nextStart)) || (before.isLetter() && after.isDigit())
}

/** A dot between two digits is a decimal point ("v1.0", "3.5"), not a place to end a line. */
private fun isNumberDot(text: String, index: Int): Boolean =
    text[index] == '.' && index > 0 && index + 1 < text.length && text[index - 1].isDigit() && text[index + 1].isDigit()

/**
 * [name] with a zero-width space put after each separator a line may end on: not a leading one (".nomedia"), not one that follows or is followed by another separator or a space, and not a
 * decimal point. The space is invisible and has no width; it is only a place the engine may end a line, which it does not always find after a hyphen.
 */
internal fun fileBrowserNameWithBreaks(name: String): String {
    val out = StringBuilder(name.length + 4)
    for (i in name.indices) {
        out.append(name[i])
        val next = name.getOrNull(i + 1) ?: continue
        val endsALine = isNameSeparator(name[i]) && i > 0 &&
            !isNameSeparator(name[i - 1]) && !name[i - 1].isWhitespace() &&
            !isNameSeparator(next) && !next.isWhitespace() &&
            !isNumberDot(name, i)
        if (endsALine) out.append(ZeroWidthSpace)
    }
    return out.toString()
}

/**
 * [name] cut right after a separator or a space and closed with an ellipsis, the longest such head for which [fits] says the result holds; the dangling separator the cut leaves
 * ("Screenshot_" + "…") is dropped. Null when no cut fits. The file name's own counterpart of `mimirEllipsizeAtWord`, whose cuts are spaces only.
 */
internal fun fileBrowserEllipsizeAtBoundary(name: String, fits: (String) -> Boolean): String? {
    for (cut in name.length - 1 downTo 1) {
        val before = cut - 1
        if (!(isNameSeparator(name[before]) || name[before].isWhitespace() || fileBrowserLetterDigitBoundary(name, cut)) || isNumberDot(name, before)) continue
        val head = name.substring(0, cut).trimEnd { isNameSeparator(it) || it.isWhitespace() }
        if (head.isEmpty()) continue
        val candidate = head + MimirEllipsis
        if (fits(candidate)) return candidate
    }
    return null
}

/** True when a line of [text] may end where the next starts, [nextStart]: after a space, a separator (not a decimal point), a zero-width space or a soft hyphen. */
private fun endsAtBoundary(text: String, nextStart: Int): Boolean {
    if (nextStart <= 0 || nextStart >= text.length) return true
    val before = text[nextStart - 1]
    return before.isWhitespace() || text[nextStart].isWhitespace() || before == ZeroWidthSpace || before == SoftHyphen ||
        (isNameSeparator(before) && !isNumberDot(text, nextStart - 1))
}

/** Whether [layout] holds [text] as drawn: nothing cut off the bottom or the side, and no line ends inside a word. */
private fun holds(text: String, layout: TextLayoutResult): Boolean =
    !layout.didOverflowHeight && !layout.didOverflowWidth &&
        (0 until layout.lineCount - 1).all { endsAtBoundary(text, layout.getLineStart(it + 1)) }

private fun layoutOf(measurer: TextMeasurer, text: String, style: TextStyle, roomPx: Int, maxLines: Int): TextLayoutResult =
    measurer.measure(
        text = AnnotatedString(text),
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = roomPx),
    )

private fun naturalWidth(measurer: TextMeasurer, text: String, style: TextStyle): Int =
    measurer.measure(AnnotatedString(text), style = style, softWrap = false, maxLines = 1).size.width

/** [text] cut into the runs a line can end between: each run ends with the zero-width space or the space that closes it. */
private fun runsOf(text: String): List<String> {
    val runs = ArrayList<String>()
    var start = 0
    for (i in text.indices) {
        if (text[i] == ZeroWidthSpace || text[i].isWhitespace()) {
            runs.add(text.substring(start, i + 1))
            start = i + 1
        }
    }
    if (start < text.length) runs.add(text.substring(start))
    return runs
}

/**
 * [spaced] with a zero-width space at each letter-digit boundary of each run wider than [roomPx] (a run that stands on a line whole is left as it is). The last run is measured with the [tail] that
 * will follow it (a cut name's ellipsis), which can be the width that makes it too wide.
 */
private fun breakWideRunsAtDigits(measurer: TextMeasurer, spaced: String, style: TextStyle, roomPx: Int, tail: String = ""): String {
    val out = StringBuilder(spaced.length + 4)
    val runs = runsOf(spaced)
    for ((index, run) in runs.withIndex()) {
        val core = run.trimEnd { it == ZeroWidthSpace || it.isWhitespace() }
        if (core.isEmpty() || naturalWidth(measurer, if (index == runs.lastIndex) core + tail else core, style) <= roomPx) {
            out.append(run)
            continue
        }
        for (i in core.indices) {
            out.append(core[i])
            if (fileBrowserLetterDigitBoundary(core, i + 1)) out.append(ZeroWidthSpace)
        }
        out.append(run, core.length, run.length)
    }
    return out.toString()
}

/** [spaced] with a soft hyphen put into each run wider than [roomPx], or null when no run is too wide or one of them has no syllable break that holds. */
private fun hyphenateWideRuns(
    measurer: TextMeasurer,
    spaced: String,
    style: TextStyle,
    roomPx: Int,
    maxLines: Int,
    syllableBreaksOf: ((word: String) -> List<Int>?)?,
): String? {
    var broke = false
    val out = StringBuilder(spaced.length + 4)
    for (run in runsOf(spaced)) {
        val core = run.trimEnd { it == ZeroWidthSpace || it.isWhitespace() }
        if (core.isEmpty() || naturalWidth(measurer, core, style) <= roomPx) {
            out.append(run)
            continue
        }
        val soft = mimirHyphenateWideWords(measurer, core, style, roomPx, maxLines, syllableBreaksOf) ?: return null
        broke = true
        out.append(soft).append(run, core.length, run.length)
    }
    return if (broke) out.toString() else null
}

/**
 * [text] arranged to hold: with a zero-width space after each separator, and a soft hyphen in each run still too wide for the room. A trailing ellipsis (a name already cut at a word) is set
 * aside while the rest is arranged, because the platform hyphenator does not know a word that ends in it. Null when no arrangement holds.
 */
private fun arrangement(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    roomPx: Int,
    maxLines: Int,
    syllableBreaksOf: ((word: String) -> List<Int>?)?,
): FileBrowserTileNameFit? {
    fun holdsIn(shown: String, drawnIn: TextStyle) = holds(shown, layoutOf(measurer, shown, drawnIn, roomPx, maxLines))
    val tail = if (text.endsWith(MimirEllipsis)) MimirEllipsis else ""
    val spaced = fileBrowserNameWithBreaks(text.removeSuffix(tail))
    if (holdsIn(spaced + tail, style)) return FileBrowserTileNameFit(spaced + tail)
    val broken = breakWideRunsAtDigits(measurer, spaced, style, roomPx, tail)
    if (broken != spaced && holdsIn(broken + tail, style)) return FileBrowserTileNameFit(broken + tail)
    val soft = hyphenateWideRuns(measurer, broken, style, roomPx, maxLines, syllableBreaksOf) ?: return null
    return FileBrowserTileNameFit(soft + tail, hyphenated = true).takeIf { holdsIn(soft + tail, style.merge(MimirSoftHyphenTextStyle)) }
}

/** [name] whole, as it is or arranged (steps 1 to 3 of this file's KDoc), on at most [maxLines] lines; null when it takes cutting. */
internal fun fileBrowserArrangeWhole(
    measurer: TextMeasurer,
    name: String,
    style: TextStyle,
    roomPx: Int,
    maxLines: Int,
    syllableBreaksOf: ((word: String) -> List<Int>?)? = null,
): FileBrowserTileNameFit? =
    if (holds(name, layoutOf(measurer, name, style, roomPx, maxLines))) FileBrowserTileNameFit(name) else arrangement(measurer, name, style, roomPx, maxLines, syllableBreaksOf)

/** How [name] is drawn in [style] in [roomPx], on at most [maxLines] lines: the first of the arrangements in this file's KDoc that holds. */
internal fun fileBrowserFitTileName(
    measurer: TextMeasurer,
    name: String,
    style: TextStyle,
    roomPx: Int,
    maxLines: Int,
    syllableBreaksOf: ((word: String) -> List<Int>?)? = null,
): FileBrowserTileNameFit {
    fileBrowserArrangeWhole(measurer, name, style, roomPx, maxLines, syllableBreaksOf)?.let { return it }
    var cut: FileBrowserTileNameFit? = null
    fileBrowserEllipsizeAtBoundary(name) { head -> arrangement(measurer, head, style, roomPx, maxLines, syllableBreaksOf).also { cut = it } != null }
    val head = cut
    // A cut that fills every line is the answer; one that ends on the first line leaves the rest unused, and the name's own breaks may do better (step 4b).
    if (head != null && layoutOf(measurer, head.text, if (head.hyphenated) style.merge(MimirSoftHyphenTextStyle) else style, roomPx, maxLines).lineCount >= maxLines) return head
    lastLineCut(measurer, name, style, roomPx, maxLines)?.let { return it }
    head?.let { return it }
    return FileBrowserTileNameFit(name, oneLine = true)
}

/**
 * [name] on all [maxLines] lines with its last line cut by the ellipsis: the lines before it end at a break, the last holds the rest of the name as far as it goes. Null when the name's breaks do
 * not give it every line, or when it needs no cut (the arrangements before this one would have taken it).
 */
private fun lastLineCut(measurer: TextMeasurer, name: String, style: TextStyle, roomPx: Int, maxLines: Int): FileBrowserTileNameFit? {
    val spaced = breakWideRunsAtDigits(measurer, fileBrowserNameWithBreaks(name), style, roomPx)
    val layout = measurer.measure(
        text = AnnotatedString(spaced),
        style = style,
        overflow = TextOverflow.Ellipsis,
        softWrap = true,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = roomPx),
    )
    if (layout.lineCount < maxLines || !layout.isLineEllipsized(maxLines - 1)) return null
    if (!(0 until maxLines - 1).all { endsAtBoundary(spaced, layout.getLineStart(it + 1)) }) return null
    return FileBrowserTileNameFit(spaced)
}

/** The first of [candidates] that [fits], else the last (the shortest), which the tile then lets wrap at its spaces rather than cut. */
internal fun fileBrowserFitTileMeta(candidates: List<String>, fits: (String) -> Boolean): String =
    candidates.firstOrNull(fits) ?: candidates.last()

/** A file tile's name in the tile's own room: see this file's KDoc. [name] is the display name (no extension); [style] and [color] are the tile's. */
@Composable
internal fun FileBrowserTileName(name: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val syllableBreaks = LocalFileBrowserNameBreaks.current
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val fit = remember(name, room, style, density.density, density.fontScale, syllableBreaks) {
            if (room == Constraints.Infinity) {
                FileBrowserTileNameFit(name)
            } else {
                fileBrowserFitTileName(measurer, name, style, room, FileBrowserTileNameLines, syllableBreaks)
            }
        }
        Text(
            text = fit.text,
            style = if (fit.hyphenated) style.merge(MimirSoftHyphenTextStyle) else style,
            color = color,
            maxLines = if (fit.oneLine) 1 else FileBrowserTileNameLines,
            softWrap = !fit.oneLine,
            overflow = TextOverflow.Ellipsis,
            // The whole name, without the hyphens and the invisible spaces, is what is spoken.
            modifier = if (fit.text == name) Modifier else Modifier.semantics { contentDescription = name },
        )
    }
}

/** A file tile's meta line in the tile's own room: the first of [candidates] that stands on its line, never cut. */
@Composable
internal fun FileBrowserTileMeta(candidates: List<String>, fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = if (fontSize == FileBrowserTileMetaFontSize) fileBrowserTileMetaStyle(LocalTextStyle.current) else LocalTextStyle.current.merge(TextStyle(fontSize = fontSize))
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val shown = remember(candidates, room, style, density.density, density.fontScale) {
            if (room == Constraints.Infinity) candidates.first() else fileBrowserFitTileMeta(candidates) { naturalWidth(measurer, it, style) <= room }
        }
        Text(
            text = shown,
            style = style,
            color = color,
            maxLines = if (shown == candidates.last()) 2 else 1,
            overflow = TextOverflow.Clip,
            modifier = if (shown == candidates.first()) Modifier else Modifier.semantics { contentDescription = candidates.first() },
        )
    }
}

// ── the tile's height: one number per font scale, shared by the tile, the grid's row pitch and the page stride ──

/** The design's file tile height at font 1.0 (`FileTile`'s `.defaultMinSize(minHeight = )` was this literal); rows are never shorter. */
internal const val FileBrowserFileTileMinHeightDp = 98f

/** The file tile's vertical geometry, named so [fileBrowserFileTileHeightDp] and the tile read the same numbers: above the chip, the chip's own padding (top and bottom), between name and meta, below the meta. */
internal const val FileBrowserTileTopPadDp = 8f
internal const val FileBrowserBadgeVerticalPadDp = 3f
internal const val FileBrowserTileTextGapDp = 2f
internal const val FileBrowserTileBottomPadDp = 10f

/** The meta line's size, a file tile's second line. */
internal val FileBrowserTileMetaFontSize: TextUnit = 10.sp

/** The meta line's style: the ambient one at [FileBrowserTileMetaFontSize], as the tile draws it. */
internal fun fileBrowserTileMetaStyle(ambient: TextStyle): TextStyle = ambient.merge(TextStyle(fontSize = FileBrowserTileMetaFontSize))

/**
 * How tall a file tile is when its name takes [FileBrowserTileNameLines] lines, in dp, never less than [FileBrowserFileTileMinHeightDp]: the height every row of the folio is given
 * (no ragged rows at font 1.3). Read off the type itself, not off a table: the chip's word, two lines of name and one of meta at this density and font scale,
 * so it is the design's 98 dp at font 1.0 (where the content is shorter than that) and grows with the type above it. The grid's row pitch is this plus the gap
 * ([fileBrowserSolveGrid]), which is what lets paging, scroll targets and focus moves land on the rows that are really drawn.
 *
 * A tile with a plate instead of a badge is not taller (the plate is a fixed 18 dp and the badge never less than that), and a name of one line is shorter, which is the slack the minimum height covers: it sits between the name and the meta, which the tile pins to its bottom edge .
 */
internal fun fileBrowserFileTileHeightDp(
    measurer: TextMeasurer,
    density: Density,
    badgeStyle: TextStyle,
    nameStyle: TextStyle,
    metaStyle: TextStyle,
): Float {
    fun heightOf(text: String, style: TextStyle) = measurer.measure(AnnotatedString(text), style = style, softWrap = false, density = density).size.height
    val px = with(density) {
        FileBrowserTileTopPadDp.dp.roundToPx() +
            heightOf("IMAGE", badgeStyle) + 2 * FileBrowserBadgeVerticalPadDp.dp.roundToPx() +
            heightOf("A\nA", nameStyle) +
            FileBrowserTileTextGapDp.dp.roundToPx() +
            heightOf("0", metaStyle) +
            FileBrowserTileBottomPadDp.dp.roundToPx()
    }
    return maxOf(FileBrowserFileTileMinHeightDp, px / density.density)
}

/** [fileBrowserFileTileHeightDp] for the tile as drawn here, remembered per density and font scale. */
@Composable
internal fun rememberFileBrowserFileTileHeightDp(): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val ambient = LocalTextStyle.current
    return remember(measurer, density.density, density.fontScale, ambient) {
        fileBrowserFileTileHeightDp(measurer, density, MimirTypeScale.Pill, MimirTypeScale.SupportCompact, fileBrowserTileMetaStyle(ambient))
    }
}
