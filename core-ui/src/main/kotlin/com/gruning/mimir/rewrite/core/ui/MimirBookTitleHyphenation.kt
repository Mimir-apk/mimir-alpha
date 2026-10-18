package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/**
 * Hyphenating a coverless book's title : a single word too wide to fit whole is broken at a syllable, "Earth-" / "bound", at the
 * normal size. When no hyphenated layout fits the room, the title is still hyphenated and cut to the room, its last line ending in the ellipsis
 * ("Poké-" / "mon" / "Diamond..."; [mimirBookTitleHyphenatedEllipsis]); only when the platform gives no hyphen break that would show does
 * the old one-line ellipsis stand. See [mimirFitBookTitle] for where this sits in the size ladder.
 *
 * **How a hyphenated break is told from a forced cut.** The platform hyphenator is only asked WHERE a too-wide word may break; what is drawn is the
 * title with a soft hyphen (U+00AD) put at those places, in the book's own style, so the text engine ends a line at a soft hyphen exactly as it ends one
 * at a space. The check that decides a layout ([mimirBookTitleEndsAtSoftHyphen]) is then plain text: every line that ends inside a word must end
 * right after a U+00AD. A forced cut (a fragment wider than the line, so the engine splits the word wherever it runs out of room) leaves no soft hyphen
 * before the break, so it can never pass. What is measured is what is drawn: the same text, the same style ([MimirBookTitleHyphenStyle]), the same size.
 *
 * Two things made this the route and not `Hyphens.Auto` on the drawn text: the text-layout result of Compose does not say whether a line ended in a
 * hyphen the engine drew, and the English hyphenation patterns the platform ships cover only the letters a-z, so a word with an accent ("Pokémon") is
 * never hyphenated by `Hyphens.Auto` at all. The patterns are consulted on the word with its accents removed ([mimirBookTitleAsciiFold], the same length, so
 * every offset maps back), and the soft hyphens are put into the real word.
 */
internal const val MimirSoftHyphen = '\u00AD'

/**
 * The style a hyphenated title is measured and drawn in, over the book's own: automatic hyphens on, with the simple line breaker (which hyphenates only a word
 * that cannot fit a line by itself, and breaks everything else as the book's title always did). The hyphens must be ON: the host engine, asked for a line
 * to end at a soft hyphen, breaks there either way but draws the hyphen, and counts its width in the line's, only with them on.
 */
internal val MimirBookTitleHyphenStyle = TextStyle(lineBreak = LineBreak.Simple, hyphens = Hyphens.Auto)

/**
 * The two halves of the hyphenated passes of [mimirFitBookTitle]. [softHyphenated] returns the title with soft hyphens put into every word too wide for a
 * line at [sizeSp], or null when there is nothing to hyphenate or, unless [lenient], some too-wide word cannot be broken at a hyphen (a lenient call leaves
 * such a word whole, and is null only when no word could be broken at all). [measure] lays out a text exactly as the title `Text` sets it at a size, capped
 * at [maxLines] lines with an ellipsis, so the layout the fit checks is the one that is drawn. Both are seams: a test drives the ladder with fakes.
 */
internal class MimirBookTitleHyphenation(
    val softHyphenated: (sizeSp: Float, lenient: Boolean) -> String?,
    val measure: (text: String, sizeSp: Float, maxLines: Int) -> TextLayoutResult,
)

/** The book title's text style at a size in sp: bold, 1.08 line height. One definition for the fit's measure and the hyphenation's, so they cannot drift apart. */
internal fun mimirBookTitleStyleAt(base: TextStyle, sizeSp: Float): TextStyle =
    base.merge(TextStyle(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.08f).sp, fontWeight = FontWeight.Bold))

internal fun mimirBookTitleLayout(measurer: TextMeasurer, text: String, style: TextStyle, widthPx: Int, maxLines: Int): TextLayoutResult =
    measurer.measure(
        text = AnnotatedString(text),
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = widthPx),
    )

/**
 * The production [MimirBookTitleHyphenation] for [title] in [widthPx]. [probeLayout] lays out one accent-free word in a style that hyphenates, in a width: it
 * is the platform hyphenator's side of the seam (the production one is the text engine itself), and a test hands it an engine that hyphenates where the host,
 * which ships no hyphenation patterns, cannot. Each word is measured and probed once per size for the life of the instance, because the fit asks again for
 * the same words at the same sizes (a lenient ask after a strict one, at the floor).
 */
internal fun mimirBookTitleHyphenation(
    measurer: TextMeasurer,
    title: String,
    base: TextStyle,
    widthPx: Int,
    probeLayout: (word: String, style: TextStyle, widthPx: Int) -> TextLayoutResult = { word, style, px -> mimirBookTitleLayout(measurer, word, style, px, Int.MAX_VALUE) },
): MimirBookTitleHyphenation {
    val naturalWidths = HashMap<String, Float>()
    val breakOffsets = HashMap<String, List<Int>?>()
    return MimirBookTitleHyphenation(
        softHyphenated = { sizeSp, lenient ->
            // The hyphenator is asked in a style that hyphenates: automatic hyphens, and the high-quality line breaker (the simple one adds none).
            val probeStyle = mimirBookTitleStyleAt(base, sizeSp).merge(TextStyle(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto))
            mimirBookTitleSoftHyphens(
                title = title,
                widthPx = widthPx,
                naturalWidthPx = { word -> naturalWidths.getOrPut("$sizeSp|$word") { mimirBookTitleNaturalWidth(measurer, word, probeStyle) } },
                breaksOf = { word ->
                    val key = "$sizeSp|$word"
                    // containsKey, not getOrPut: a word that cannot be broken is a null, and that is the answer worth keeping.
                    if (breakOffsets.containsKey(key)) {
                        breakOffsets[key]
                    } else {
                        mimirBookTitleHyphenBreaks(measurer, word, probeStyle, widthPx, probeLayout).also { breakOffsets[key] = it }
                    }
                },
                lenient = lenient,
            )
        },
        measure = { text, sizeSp, maxLines ->
            mimirBookTitleLayout(measurer, text, mimirBookTitleStyleAt(base, sizeSp).merge(MimirBookTitleHyphenStyle), widthPx, maxLines)
        },
    )
}

/** How a book builds its [MimirBookTitleHyphenation] for a title, in a width, over a base style. */
internal typealias MimirBookTitleHyphenator = (measurer: TextMeasurer, title: String, base: TextStyle, widthPx: Int) -> MimirBookTitleHyphenation

/** The production [MimirBookTitleHyphenator]. One instance, so a fit remembered under it is found again (it is part of the key of [MimirBookTitleFits]). */
internal val MimirProductionBookTitleHyphenator: MimirBookTitleHyphenator = { measurer, title, base, widthPx -> mimirBookTitleHyphenation(measurer, title, base, widthPx) }

/** The hyphenator the book covers below use. Only a test provides another, to put an engine that hyphenates where the host's cannot. */
internal val LocalMimirBookTitleHyphenator = compositionLocalOf { MimirProductionBookTitleHyphenator }

/** True when a line of the title may end after [c] without the engine splitting a word: a space, a hyphen or dash, a slash or a backslash. */
private fun isBookTitleWordEdge(c: Char): Boolean =
    Character.isWhitespace(c) || Character.isSpaceChar(c) ||
        c == '-' || c == '\u2010' || c == '\u2013' || c == '\u2014' || c == '/' || c == '\\'

/**
 * [title] with a soft hyphen put into every word that is too wide for [widthPx] ([naturalWidthPx] is the width of a word on one line), at the offsets
 * [breaksOf] gives for it (where the hyphenator breaks the word with a real hyphen); a word that fits is left whole. Null when no word is too wide (there is
 * nothing to hyphenate) or when some too-wide word cannot be broken at a real hyphen ([breaksOf] gives null or nothing), because the engine would then have
 * to split it where it runs out of room and such a title is not accepted. With [lenient] such a word is left whole instead (the engine will split it,
 * and the caller cuts the title there), and the result is null only when no word was broken at all. The words are the runs between the places a line may end
 * anyway (spaces, hyphens, dashes, slashes), so "Spider-Man" is two words. Both functions are seams: the production ones ask the platform hyphenator at one
 * size, a test gives a dictionary.
 */
internal fun mimirBookTitleSoftHyphens(
    title: String,
    widthPx: Int,
    naturalWidthPx: (word: String) -> Float,
    breaksOf: (word: String) -> List<Int>?,
    lenient: Boolean = false,
): String? {
    val out = StringBuilder(title.length + 4)
    var inserted = 0
    var wordStart = -1
    for (i in 0..title.length) {
        val atEnd = i == title.length
        if (!atEnd && !isBookTitleWordEdge(title[i])) {
            if (wordStart < 0) wordStart = i
            continue
        }
        if (wordStart >= 0) {
            val word = title.substring(wordStart, i)
            if (naturalWidthPx(word) <= widthPx) {
                out.append(word)
            } else {
                val breaks = breaksOf(word)?.distinct()?.sorted()
                if (breaks.isNullOrEmpty() || breaks.any { it <= 0 || it >= word.length }) {
                    if (!lenient) return null
                    out.append(word)
                } else {
                    val hyphenated = StringBuilder(word)
                    // From the last break to the first, so the offsets of the earlier ones stay valid.
                    for (offset in breaks.asReversed()) hyphenated.insert(offset, MimirSoftHyphen)
                    out.append(hyphenated.toString())
                    inserted += breaks.size
                }
            }
            wordStart = -1
        }
        if (!atEnd) out.append(title[i])
    }
    return if (inserted > 0) out.toString() else null
}

/** The width of [word] on one line in [style], in px. */
internal fun mimirBookTitleNaturalWidth(measurer: TextMeasurer, word: String, style: TextStyle): Float {
    val single = measurer.measure(
        text = AnnotatedString(word),
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = false,
        maxLines = 1,
        constraints = Constraints(),
    )
    return single.getLineRight(0) - single.getLineLeft(0)
}

/**
 * The offsets in [word] at which the platform hyphenator breaks it, laid out alone in [widthPx] in [probeStyle] (one that hyphenates), or null when it breaks it
 * any other way: a line of it that ends in a forced cut and not in a drawn hyphen ([mimirBookTitleLineEndsAtHyphen]) fails the whole word. The hyphenator is
 * given the accent-free form of the word ([mimirBookTitleAsciiFold]).
 */
internal fun mimirBookTitleHyphenBreaks(
    measurer: TextMeasurer,
    word: String,
    probeStyle: TextStyle,
    widthPx: Int,
    probeLayout: (word: String, style: TextStyle, widthPx: Int) -> TextLayoutResult,
): List<Int>? {
    val layout = probeLayout(mimirBookTitleAsciiFold(word), probeStyle, widthPx)
    if (layout.lineCount < 2) return null
    val text = layout.layoutInput.text.text
    val breaks = ArrayList<Int>()
    for (line in 0 until layout.lineCount - 1) {
        if (!mimirBookTitleLineEndsAtHyphen(measurer, layout, line)) return null
        val start = layout.getLineStart(line + 1)
        // An engine that stands in for the hyphenator may mark its points with soft hyphens; they are not characters of the word.
        breaks.add(start - text.take(start).count { it == MimirSoftHyphen })
    }
    return breaks
}

/**
 * [text] with each accented letter replaced by its plain base letter ("Pokémon" becomes "Pokemon"), one character for one character, so an offset in the
 * result is the same offset in [text]. A character with no such base (ß, ø, a letter outside Latin) stays as it is. The platform's English hyphenation
 * patterns know only a-z, and a word with any other letter is not hyphenated at all; this is what lets them see the word.
 */
internal fun mimirBookTitleAsciiFold(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) {
        if (ch.code < 0x80) {
            out.append(ch)
            continue
        }
        val decomposed = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD)
        val plain = decomposed.firstOrNull()
        val onlyMarksFollow = decomposed.length > 1 && decomposed.drop(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        out.append(if (plain != null && plain.code < 0x80 && onlyMarksFollow) plain else ch)
    }
    return out.toString()
}

/**
 * True when [line] of [layout], a line that ends inside a word, ends in a hyphen the text engine drew, and false when it ends in a forced cut. The engine
 * draws the hyphen at the end of the line and counts its width in the line's, but the hyphen is not a character of the text; a forced cut draws nothing. So
 * the line is wider than its own characters laid out alone by about one hyphen when a hyphen was drawn, and by nothing when the word was cut: the test is
 * that surplus against half of the width of "-" in the same style. Only used to find where the hyphenator broke a word; the layout that is accepted and drawn
 * is checked by [mimirBookTitleEndsAtSoftHyphen].
 */
internal fun mimirBookTitleLineEndsAtHyphen(measurer: TextMeasurer, layout: TextLayoutResult, line: Int): Boolean {
    val input = layout.layoutInput
    val piece = input.text.text.substring(layout.getLineStart(line), layout.getLineEnd(line))
    fun advanceOf(text: String): Float {
        val single = measurer.measure(
            text = AnnotatedString(text),
            style = input.style,
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        )
        return single.getLineRight(0) - single.getLineLeft(0)
    }
    val hyphen = advanceOf("-")
    if (hyphen <= 0f) return false
    val drawn = layout.getLineRight(line) - layout.getLineLeft(line)
    return drawn - advanceOf(piece) >= hyphen * 0.5f
}

/** True when [line] of [layout] ends right after a soft hyphen, the one way a line may end inside a word of a hyphenated title. */
internal fun mimirBookTitleEndsAtSoftHyphen(layout: TextLayoutResult, line: Int): Boolean {
    val next = layout.getLineStart(line + 1)
    return next > 0 && layout.layoutInput.text.text[next - 1] == MimirSoftHyphen
}

/**
 * The fallback when no size holds the hyphenated title in the room : the title still hyphenated, at [sizeSp] (the floor: a smaller size never
 * shows less, it puts more characters on a line and more lines in the room, and it is where the old ellipsis already stood), cut to the room with its LAST line
 * ending in the ellipsis, "Poké-" / "mon" / "Diamond...". It shows as many lines as the room and [maxLines] hold, and no more than end at a hyphen or a place a
 * line may end anyway: the first line the engine would have to cut inside a word with no hyphen is the last line shown, so a forced cut is only ever the
 * ellipsised final line. Null, and the caller keeps the old one-line ellipsis, when there is no hyphen to break at, or none of the lines shown ends in one
 * (the hyphen would be cut off with the rest). The text drawn is the text measured: the same string, [MimirBookTitleHyphenStyle], size and line count.
 */
internal fun mimirBookTitleHyphenatedEllipsis(
    hyphenation: MimirBookTitleHyphenation,
    sizeSp: Float,
    maxLines: Int,
    availableHeightPx: Float,
): MimirBookTitleFit? {
    val text = hyphenation.softHyphenated(sizeSp, true) ?: return null
    val budget = hyphenation.measure(text, sizeSp, maxLines)
    val roomLines = (0 until budget.lineCount).count { budget.getLineBottom(it) <= availableHeightPx + 0.5f }
    val firstForcedCut = mimirBookTitleCutLines(budget).firstOrNull { !mimirBookTitleEndsAtSoftHyphen(budget, it) }
    val lines = minOf(roomLines, if (firstForcedCut != null) firstForcedCut + 1 else maxLines).coerceIn(1, maxLines)
    val shown = hyphenation.measure(text, sizeSp, lines)
    val lawful = shown.size.height <= availableHeightPx + 0.5f &&
        mimirBookTitleCutLines(shown).all { mimirBookTitleEndsAtSoftHyphen(shown, it) } &&
        (0 until shown.lineCount - 1).any { mimirBookTitleEndsAtSoftHyphen(shown, it) }
    return if (lawful) MimirBookTitleFit(sizeSp, lines, fits = false, hyphenatedText = text) else null
}
