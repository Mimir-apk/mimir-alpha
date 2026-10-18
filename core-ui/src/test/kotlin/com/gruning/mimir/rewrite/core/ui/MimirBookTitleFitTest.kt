package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Observed word-splitting defect:** the Bottom's coverless book set "Earthbound" on its spine as "Earthbo" / "und". [mimirFitBookTitle] accepted any size whose
 * layout stood in the room and never looked at where a line ended, so a word wider than the book's leaf was split by the text engine. It now rejects a size
 * that splits a word and, only at the type scale's floor, keeps the word whole by ending the line in an ellipsis.
 *
 * Driven with the PRODUCTION measure ([mimirBookTitleMeasure], on a real text measurer at the Thor Bottom's density, 369 dpi) and the book's own geometry
 * (the 0.765 leaf width and the title-size coefficient of `MediaArtBookCover`), at the book widths `CompanionShelfArtSeatTest` prints for Genesis (0.71):
 * the Standard 4x2 seat is 220 x 310 px and the Dense 6x3 seat 171 x 241 px; the 3:4 book fills the seat's width, so it is 220 px and 171 px wide. The wide
 * platform's Dense seat (N64: 171 x 117 px) holds a book 87.75 px wide, the narrowest the shelf draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirBookTitleFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val density = Density(density369, 1.0f)

    private lateinit var measurer: TextMeasurer
    private lateinit var base: TextStyle

    @Before
    fun captureARealMeasurer() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    measurer = rememberTextMeasurer()
                    base = LocalTextStyle.current
                }
            }
        }
        compose.waitForIdle()
    }

    /** A book of [widthPx] x [heightPx], and the title room `MediaArtBookCover` gives it (no eyebrow: the shelf passes none). */
    private class Book(val widthPx: Float, val heightPx: Float) {
        val densityPx = 369f / 160f
        val wDp = widthPx / densityPx
        val hDp = heightPx / densityPx
        val titleSp = (wDp * 0.11f).coerceIn(12f, 28f)
        val textWidthPx = with(Density(densityPx, 1.0f)) { (wDp * 0.765f).dp.toPx() }.toInt().coerceAtLeast(1)
        val availablePx = with(Density(densityPx, 1.0f)) {
            val topDp = wDp * 0.13f + 1.5f + wDp * 0.028f
            val runeTopDp = hDp * 0.84f - wDp * 0.083f - 2f
            (runeTopDp - topDp).coerceAtLeast(1f).dp.toPx()
        }
    }

    /** The 3:4 book inside a seat, as `aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)` sizes it. */
    private fun bookIn(seatWidthPx: Float, seatHeightPx: Float): Book {
        val byHeight = seatHeightPx * 0.75f
        return if (byHeight <= seatWidthPx) Book(byHeight, seatHeightPx) else Book(seatWidthPx, seatWidthPx / 0.75f)
    }

    private val standard get() = bookIn(220f, 310f)
    private val dense get() = bookIn(171f, 241f)
    private val denseWide get() = bookIn(171f, 117f)

    private fun fit(title: String, book: Book): MimirBookTitleFit = mimirFitBookTitle(
        maxSp = book.titleSp,
        minSp = MimirTypeStep.Micro.value,
        maxLines = 3,
        availableHeightPx = book.availablePx,
        measure = mimirBookTitleMeasure(measurer, title, base, book.textWidthPx, maxLines = 3),
    )

    /** The title as the book's `Text` will lay it out: at the chosen size, in the chosen number of lines. */
    private fun shown(title: String, book: Book, fit: MimirBookTitleFit): TextLayoutResult =
        mimirBookTitleMeasure(measurer, title, base, book.textWidthPx, fit.maxLines)(fit.sizeSp)

    /** A title's words: split at a space, a slash, a backslash or a hyphen, the places a line may end besides the spaces. */
    private fun words(text: String) = text.split(Regex("[\\s/\\\\-]+")).filter { it.isNotEmpty() }

    private fun lineText(layout: TextLayoutResult, line: Int): String =
        layout.layoutInput.text.text.substring(layout.getLineStart(line), layout.getLineEnd(line))

    /**
     * The word law, read straight off the layout and not through the function under test: every line that is not ellipsised holds only WHOLE words of the title
     * (a split word would leave a fragment that is no word of it), and a title that was given the ellipsis is the floor's.
     */
    private fun assertNoWordIsSplit(where: String, title: String, fit: MimirBookTitleFit, layout: TextLayoutResult) {
        val whole = words(title).toSet()
        for (line in 0 until layout.lineCount) {
            if (layout.isLineEllipsized(line)) continue
            for (word in words(lineText(layout, line))) {
                assertTrue("$where: line $line holds '$word', a piece of a word of '$title' (lines ${(0 until layout.lineCount).map { lineText(layout, it) }})", word in whole)
            }
        }
        assertEquals("$where: no line ends inside a word", -1, mimirBookTitleFirstCutLine(layout))
        if (fit.fits) {
            assertFalse("$where: a title that fits has no ellipsis", (0 until layout.lineCount).any(layout::isLineEllipsized))
            assertFalse("$where: and drops no text", layout.didOverflowHeight)
        } else {
            assertEquals("$where: the fallback is the floor", MimirTypeStep.Micro.value, fit.sizeSp, 0.01f)
            assertTrue("$where: and it ends in the ellipsis", layout.isLineEllipsized(layout.lineCount - 1))
        }
    }

    private fun report(tag: String, title: String, book: Book, fit: MimirBookTitleFit, layout: TextLayoutResult) =
        println(
            "BOOKFIT $tag '$title' book=${book.widthPx}px text=${book.textWidthPx}px max=${book.titleSp}sp -> ${fit.sizeSp}sp fits=${fit.fits} maxLines=${fit.maxLines} " +
                "lines=${(0 until layout.lineCount).map { lineText(layout, it) }} ellipsized=${layout.isLineEllipsized(layout.lineCount - 1)}",
        )

    // ── "Earthbound" ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Earthbound at Standard keeps its word whole and is not shrunk when it already fits`() {
        val book = standard
        val raw = mimirBookTitleMeasure(measurer, "Earthbound", base, book.textWidthPx, 3)(book.titleSp)
        val fit = fit("Earthbound", book)
        val layout = shown("Earthbound", book, fit)
        report("standard", "Earthbound", book, fit, layout)
        assertNoWordIsSplit("Standard", "Earthbound", fit, layout)
        if (mimirBookTitleFirstCutLine(raw) < 0) {
            assertEquals("a title that already stands whole at the book's size keeps it", book.titleSp, fit.sizeSp, 0.001f)
            assertTrue(fit.fits)
        } else {
            assertTrue("the word was split at ${book.titleSp} sp, so the fit is smaller or the ellipsis", fit.sizeSp < book.titleSp || !fit.fits)
        }
    }

    @Test
    fun `Earthbound at Dense is split by the raw size and the fit keeps it whole`() {
        val book = dense
        val raw = mimirBookTitleMeasure(measurer, "Earthbound", base, book.textWidthPx, 3)(book.titleSp)
        println("BOOKFIT dense raw lines=${(0 until raw.lineCount).map { lineText(raw, it) }}")
        assertTrue("without the rule the engine splits the word at the book's own ${book.titleSp} sp (this is the bug the rule closes)", mimirBookTitleFirstCutLine(raw) >= 0)
        val fit = fit("Earthbound", book)
        val layout = shown("Earthbound", book, fit)
        report("dense", "Earthbound", book, fit, layout)
        assertNoWordIsSplit("Dense", "Earthbound", fit, layout)
        assertTrue("so the size came down (or the floor's ellipsis took over): ${fit.sizeSp}", fit.sizeSp < book.titleSp)
        assertTrue("never past the floor", fit.sizeSp >= MimirTypeStep.Micro.value - 0.001f)
    }

    @Test
    fun `a word the narrowest book cannot hold at the floor ends in an ellipsis on one line, never split`() {
        val book = denseWide
        val fit = fit("Earthbound", book)
        val layout = shown("Earthbound", book, fit)
        report("dense-wide", "Earthbound", book, fit, layout)
        assertFalse("not even the floor holds the word whole", fit.fits)
        assertEquals(MimirTypeStep.Micro.value, fit.sizeSp, 0.001f)
        assertEquals("the one word is the one line the ellipsis closes", 1, fit.maxLines)
        assertEquals(1, layout.lineCount)
        assertNoWordIsSplit("Dense wide", "Earthbound", fit, layout)
    }

    // ── A title of several words still wraps at its spaces ───────────────────────────────────────

    @Test
    fun `a multi-word title wraps at its spaces at both widths and keeps the book's size`() {
        for ((name, book) in listOf("Standard" to standard, "Dense" to dense)) {
            val title = "Super Mario World"
            val fit = fit(title, book)
            val layout = shown(title, book, fit)
            report(name.lowercase(), title, book, fit, layout)
            assertNoWordIsSplit(name, title, fit, layout)
            assertTrue("$name: the title wraps (it is wider than one line)", layout.lineCount >= 2)
            assertTrue("$name: and fits", fit.fits)
            assertEquals("$name: at the book's own size, nothing was shrunk to avoid a break", book.titleSp, fit.sizeSp, 0.001f)
            for (line in 0 until layout.lineCount - 1) {
                assertTrue("$name: line $line ends at its space", layout.layoutInput.text.text[layout.getLineStart(line + 1) - 1].isWhitespace())
            }
        }
    }

    @Test
    fun `a long name in the narrowest book ends in an ellipsis at the floor and cuts no word`() {
        val title = "The Legend of Zelda Ocarina of Time Master Quest Collectors Edition"
        val book = denseWide
        val fit = fit(title, book)
        val layout = shown(title, book, fit)
        report("dense-wide", title, book, fit, layout)
        assertFalse(fit.fits)
        assertNoWordIsSplit("Dense wide long", title, fit, layout)
        assertTrue("the first words stay on their lines: ${lineText(layout, 0)}", lineText(layout, 0).trim().startsWith("The"))
    }

    // ── Scripts written without spaces, and a slash ──────────────────────────────────────────────

    /**
     * A title with no spaces in it, in a script where every character edge is a legal break, must wrap over lines at the book's size and not collapse to the
     * floor's one line ending in an ellipsis (the first version of the word rule read every such line end as a cut). The wrap itself is asserted only when the
     * title is wider than one line in this JVM's font, so a font that lacks the glyphs cannot fail the case for a reason that is not the rule's.
     */
    private fun assertWrapsAcrossCharacters(where: String, title: String, book: Book) {
        val fit = fit(title, book)
        val layout = shown(title, book, fit)
        report(where, title, book, fit, layout)
        println("BOOKFIT $where natural=${naturalWidthPx(title, fit.sizeSp)}px of ${book.textWidthPx}px")
        assertTrue("$where: it fits above the floor and does not fall back to the ellipsis: $fit", fit.fits)
        assertTrue("$where: above the floor: ${fit.sizeSp}", fit.sizeSp > MimirTypeStep.Micro.value)
        assertFalse("$where: no ellipsis", (0 until layout.lineCount).any(layout::isLineEllipsized))
        assertFalse("$where: drops no text", layout.didOverflowHeight)
        assertEquals("$where: no line end is read as a cut", -1, mimirBookTitleFirstCutLine(layout))
        if (naturalWidthPx(title, fit.sizeSp) > book.textWidthPx) {
            assertTrue("$where: wider than a line, so it wraps over lines, was ${layout.lineCount}", layout.lineCount >= 2)
        }
    }

    @Test
    fun `a Japanese title wraps over lines at the Dense width instead of collapsing to the floor`() {
        assertWrapsAcrossCharacters("katakana Dense", "ファイナルファンタジー", dense)
        assertWrapsAcrossCharacters("katakana Standard", "ファイナルファンタジー", standard)
        assertWrapsAcrossCharacters("mixed kana Dense", "どうぶつの森", dense)
    }

    @Test
    fun `a Chinese title wraps over lines at the Dense width instead of collapsing to the floor`() {
        assertWrapsAcrossCharacters("han Dense", "最终幻想勇者斗恶龙大冒险", dense)
        assertWrapsAcrossCharacters("han Standard", "最终幻想勇者斗恶龙大冒险", standard)
    }

    @Test
    fun `the detector passes a line that ends between two characters of a script written without spaces`() {
        for (title in listOf("ファイナルファンタジー", "最终幻想勇者斗恶龙大冒险")) {
            val layout = layoutIn(title, naturalWidthPx(title) / 2)
            println("BOOKFIT detector '$title' lines=${(0 until layout.lineCount).map { lineText(layout, it) }}")
            assertEquals("$title: every character edge is a legal break", -1, mimirBookTitleFirstCutLine(layout))
        }
    }

    @Test
    fun `a title with a slash may break after the slash, and a break inside a word of it is still a cut`() {
        // The detector reads the break the engine actually made: after the slash it passes, inside "Blue" it is a cut.
        val width = (naturalWidthPx("Red/") + naturalWidthPx("Red/Blue")) / 2
        val layout = layoutIn("Red/Blue", width)
        assertTrue("it wraps in $width px: ${layout.lineCount}", layout.lineCount >= 2)
        val first = lineText(layout, 0)
        println("BOOKFIT slash first line='$first' of ${(0 until layout.lineCount).map { lineText(layout, it) }}")
        assertEquals("'$first' / the rest", if (first.endsWith("/")) -1 else 0, mimirBookTitleFirstCutLine(layout))
        for ((name, book) in listOf("Standard" to standard, "Dense" to dense, "Dense wide" to denseWide)) {
            val title = "Pokémon Red/Blue"
            val fit = fit(title, book)
            val shownLayout = shown(title, book, fit)
            report("slash $name", title, book, fit, shownLayout)
            assertNoWordIsSplit("slash $name", title, fit, shownLayout)
        }
    }

    // ── The detector ─────────────────────────────────────────────────────────────────────────────

    private fun layoutIn(text: String, widthPx: Int, sp: Float = 12f): TextLayoutResult =
        measurer.measure(
            text = AnnotatedString(text),
            style = base.merge(TextStyle(fontSize = sp.sp)),
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = widthPx),
        )

    private fun naturalWidthPx(text: String, sp: Float = 12f): Int = layoutIn(text, Constraints.Infinity, sp).size.width

    @Test
    fun `the detector finds a line that ends inside a word and passes lines that end at a space`() {
        val narrow = naturalWidthPx("Earthbound") / 2
        val split = layoutIn("Earthbound", narrow)
        assertTrue("the word is split in $narrow px", split.lineCount >= 2)
        assertEquals(0, mimirBookTitleFirstCutLine(split))

        val oneLine = layoutIn("Earthbound", naturalWidthPx("Earthbound") + 4)
        assertEquals(1, oneLine.lineCount)
        assertEquals(-1, mimirBookTitleFirstCutLine(oneLine))

        val atSpace = layoutIn("Super Mario", (naturalWidthPx("Super") + naturalWidthPx("Super Mario")) / 2)
        assertEquals("wrapped at the space", 2, atSpace.lineCount)
        assertEquals(-1, mimirBookTitleFirstCutLine(atSpace))
    }
}
