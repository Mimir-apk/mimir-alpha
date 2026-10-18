package com.gruning.mimir.rewrite.feature.files

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.ui.MimirEllipsis
import com.gruning.mimir.rewrite.core.ui.MimirSoftHyphenTextStyle

/*
 * What the detail panel's field rows say to the right of their labels ("Where", "Modified", "Launches", "Upload"), set to the room the panel gives them (emulator re-look vqa18, 2026-10-14, font 1.3: "Internal
 * Storage › Downl..." and "Nothing yet — extract it f..." were cut on one line). Text never breaks inside a word; the panel's own long values already wrap (the name takes three
 * lines, the digest three), so a value takes [FileBrowserInspectorValueLines] lines and wraps at its words.
 *
 * A path that still does not stand on them keeps its volume and its last folder and gives up the folders between ("Internal Storage › … › Download"); a value that is not a path, or a path with nothing to give up,
 * is cut at a word with an ellipsis (the file tile's own arrangement, [fileBrowserFitTileName]). What is shortened is still spoken whole.
 */

/** How many lines a field row's value may take. */
internal const val FileBrowserInspectorValueLines = 3

/** A value's line height as a multiple of its font size. */
private const val FileBrowserInspectorValueLineHeightRatio = 1.35f

/** The separator of the panel's friendly path ([fileBrowserFriendlyPath]). */
internal const val FileBrowserPathSeparator = " › "

/**
 * The shorter forms of the friendly [path], longest first: the volume, an ellipsis, then the last folders (fewer each time), and last the ellipsis and the last folder alone. Empty for a path of fewer
 * than three parts, which has no folder to give up.
 */
internal fun fileBrowserPathElisions(path: String): List<String> {
    val parts = path.split(FileBrowserPathSeparator)
    if (parts.size < 3) return emptyList()
    val forms = ArrayList<String>()
    for (kept in parts.size - 2 downTo 1) {
        forms.add((listOf(parts.first(), MimirEllipsis) + parts.takeLast(kept)).joinToString(FileBrowserPathSeparator))
    }
    forms.add(listOf(MimirEllipsis, parts.last()).joinToString(FileBrowserPathSeparator))
    return forms
}

/** The path's separator bound to the word before it, so a line ends AFTER a "›" and never starts with one. */
private fun bindSeparators(text: String): String = text.replace(FileBrowserPathSeparator, " ›" + " ")

/**
 * How a field row's [text] is drawn in [style] in [roomPx], on at most [maxLines] lines: whole at its words when it holds, else a path with its middle folders given up, else cut at a word.
 */
internal fun fileBrowserFitInspectorValue(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    roomPx: Int,
    maxLines: Int,
    syllableBreaksOf: ((word: String) -> List<Int>?)? = null,
): FileBrowserTileNameFit {
    val elisions = fileBrowserPathElisions(text)
    fun arrange(candidate: String) = fileBrowserArrangeWhole(measurer, bindSeparators(candidate), style, roomPx, maxLines, syllableBreaksOf)
    arrange(text)?.let { return it }
    for (form in elisions) arrange(form)?.let { return it }
    return fileBrowserFitTileName(measurer, bindSeparators(elisions.lastOrNull() ?: text), style, roomPx, maxLines, syllableBreaksOf)
}

/** A detail-panel field row's value: see this file's KDoc. [text] is the value as the panel computes it; [fontSize] and [color] are the row's. */
@Composable
internal fun FileBrowserInspectorValue(text: String, fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val syllableBreaks = LocalFileBrowserNameBreaks.current
    // The line height is the panel's own (the name's is 19 sp at 15 sp): the ambient 24 sp would stand a wrapped value's lines a blank line apart.
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = fontSize, lineHeight = (fontSize.value * FileBrowserInspectorValueLineHeightRatio).sp))
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val fit = remember(text, room, style, density.density, density.fontScale, syllableBreaks) {
            if (room == Constraints.Infinity) {
                FileBrowserTileNameFit(text)
            } else {
                fileBrowserFitInspectorValue(measurer, text, style, room, FileBrowserInspectorValueLines, syllableBreaks)
            }
        }
        Text(
            text = fit.text,
            style = if (fit.hyphenated) style.merge(MimirSoftHyphenTextStyle) else style,
            color = color,
            maxLines = if (fit.oneLine) 1 else FileBrowserInspectorValueLines,
            softWrap = !fit.oneLine,
            overflow = TextOverflow.Ellipsis,
            // The whole value, not the shortened or hyphenated one, is what is spoken.
            modifier = if (fit.text == text) Modifier else Modifier.semantics { contentDescription = text },
        )
    }
}
