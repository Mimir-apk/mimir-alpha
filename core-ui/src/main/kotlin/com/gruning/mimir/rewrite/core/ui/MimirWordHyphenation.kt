package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * Short labels whose one word is wider than the room they are given (a tile's name, "Atmosphere" on a 59 dp tile) break that word at a SYLLABLE HYPHEN, "Atmo-" / "sphere", the way
 * a coverless book's title does (policy 2026-10-08, [MimirBookTitleHyphenation.kt]): never inside a word without a hyphen, never past the room. This is the shared hyphenation machinery
 * for a label that has a room and a line budget but no size ladder.
 *
 * The style a text with soft hyphens (U+00AD) is DRAWN in: automatic hyphens on, with the simple line breaker. The hyphens must be on, or the engine breaks at a soft hyphen but
 * draws no hyphen and does not count its width in the line.
 */
val MimirSoftHyphenTextStyle: TextStyle get() = MimirBookTitleHyphenStyle

/**
 * [text] with a soft hyphen put at each syllable break of every word wider than [roomPx] in [style], or null when there is nothing to do (no word is too wide) or the result cannot
 * be drawn lawfully: it does not hold in [maxLines] lines of [roomPx], some too-wide word has no hyphen to break at, or a line would end inside a word without one. A caller draws the
 * returned text in [MimirSoftHyphenTextStyle] over [style], and keeps the original text as the node's spoken name.
 *
 * [syllableBreaksOf] gives the offsets at which a word may be broken (null: it cannot be). Left null, the platform's own hyphenator is asked, by laying the word out alone in a style that
 * hyphenates, and a host JVM, which ships no hyphenation patterns, gets none: a test of the drawing hands a dictionary here (see [mimirBookTitleHyphenBreaks]).
 */
fun mimirHyphenateWideWords(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    roomPx: Int,
    maxLines: Int,
    syllableBreaksOf: ((word: String) -> List<Int>?)? = null,
): String? {
    if (roomPx <= 0 || maxLines < 2) return null
    val probeStyle = style.merge(TextStyle(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto))
    val soft = mimirBookTitleSoftHyphens(
        title = text,
        widthPx = roomPx,
        naturalWidthPx = { word -> mimirBookTitleNaturalWidth(measurer, word, probeStyle) },
        breaksOf = { word ->
            // A dictionary, when one is given, is the whole answer (a word it has no break for has none); otherwise the platform's hyphenator is asked.
            if (syllableBreaksOf != null) {
                syllableBreaksOf(word)
            } else {
                mimirBookTitleHyphenBreaks(measurer, word, probeStyle, roomPx) { probe, probeAs, px ->
                    mimirBookTitleLayout(measurer, probe, probeAs, px, Int.MAX_VALUE)
                }
            }
        },
    ) ?: return null
    val layout = measurer.measure(
        text = AnnotatedString(soft),
        style = style.merge(MimirBookTitleHyphenStyle),
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = roomPx),
    )
    val lawful = !layout.didOverflowHeight && !layout.didOverflowWidth &&
        mimirBookTitleCutLines(layout).all { mimirBookTitleEndsAtSoftHyphen(layout, it) }
    return soft.takeIf { lawful }
}
