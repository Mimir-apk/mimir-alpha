package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** The ellipsis a shortened line ends with. */
const val MimirEllipsis = "…"

/** What a shortened line never ends on: a separator or an opening bracket left dangling before the ellipsis. */
private val DanglingSeparators = charArrayOf('·', '-', '–', '—', ':', ',', ';', '&', '/', '(', '[', '|')

/**
 * [text] cut back to a WORD boundary and closed with an ellipsis, when [fits] says the whole of it does not.
 *
 * Text never breaks mid-word (a standing rule): Compose's own `TextOverflow.Ellipsis` cuts at the last letter that fits, which
 * read "Unbel..." on the Home title rail and "your pa..." in Settings. This keeps the longest run of whole words for which `head + "..."` still [fits],
 * dropping a separator the cut would leave dangling ("Zelda: ..." becomes "Zelda..."). When even the first word does not fit
 * the text is returned whole, because no whole-word answer exists: the caller's own overflow then decides, and a word wider than
 * its room is the one case a cut cannot be avoided.
 *
 * [fits] answers for a candidate string as the caller will draw it (its style, its room). Pure, so the order is a table test.
 */
fun mimirEllipsizeAtWord(text: String, fits: (String) -> Boolean): String {
    if (fits(text)) return text
    // A cut stands where a word ends: a space after a non-space.
    val cuts = text.indices.filter { it > 0 && text[it].isWhitespace() && !text[it - 1].isWhitespace() }
    for (cut in cuts.asReversed()) {
        val head = text.substring(0, cut).trimEnd(*DanglingSeparators).trimEnd()
        if (head.isEmpty()) continue
        val candidate = head + MimirEllipsis
        if (fits(candidate)) return candidate
    }
    return text
}

/** Whether [layout], laid out for [candidate], ended a line inside a word: a soft wrap at a place that is not whitespace. */
private fun breaksInsideAWord(candidate: String, layout: TextLayoutResult): Boolean {
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line, visibleEnd = false)
        if (end in 1 until candidate.length && !candidate[end - 1].isWhitespace() && !candidate[end].isWhitespace()) return true
    }
    return false
}

/**
 * [text] in [style], on at most [maxLines] lines, cut at a WORD boundary with an ellipsis ([mimirEllipsizeAtWord]) when it does not fit the room it is given, and never wrapped inside a
 * word. The room is the width this node is constrained to, so a caller bounds it with `widthIn(max = ...)`; one line stays one line (no soft wrap), more wrap at their spaces. The whole
 * text stays the node's spoken name (`contentDescription`) when it is shortened, so a screen reader still reads it in full.
 *
 * The style is measured as given: `Text(style = ...)` REPLACES the ambient style rather than merging into it, so merging `LocalTextStyle` here (letter spacing 0.5 sp) measured a line wider
 * than it is drawn and cut text that fit.
 *
 * [textAlign] other than the default lays the text across the whole width it is given and aligns each line in it (a caption or a tile's name, centred);
 * left at the default the text is exactly as it was for every earlier caller.
 */
@Composable
fun MimirWordEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val roomPx = constraints.maxWidth
        val shown = remember(text, roomPx, style, maxLines, density.density, density.fontScale) {
            if (roomPx == Constraints.Infinity) {
                text
            } else {
                mimirEllipsizeAtWord(text) { candidate ->
                    if (maxLines == 1) {
                        measurer.measure(
                            text = AnnotatedString(candidate),
                            style = style,
                            overflow = TextOverflow.Clip,
                            softWrap = false,
                            maxLines = 1,
                        ).size.width <= roomPx
                    } else {
                        val layout = measurer.measure(
                            text = AnnotatedString(candidate),
                            style = style,
                            overflow = TextOverflow.Clip,
                            softWrap = true,
                            maxLines = maxLines,
                            constraints = Constraints(maxWidth = roomPx),
                        )
                        !layout.didOverflowHeight && !layout.didOverflowWidth && !breaksInsideAWord(candidate, layout)
                    }
                }
            }
        }
        Text(
            text = shown,
            style = style,
            color = color,
            maxLines = maxLines,
            softWrap = maxLines > 1,
            textAlign = textAlign,
            // The last resort, for a first word wider than the room: the only cut that can fall inside a word.
            overflow = TextOverflow.Ellipsis,
            modifier = (if (textAlign == TextAlign.Unspecified) Modifier else Modifier.fillMaxWidth())
                .then(if (shown == text) Modifier else Modifier.semantics { contentDescription = text }),
        )
    }
}
