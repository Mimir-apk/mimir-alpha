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
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 *  on a coverless book, a single word too wide to fit whole is hyphenated at a syllable ("Earth-" / "bound") at the normal size,
 * and when no hyphenated layout fits the room the title is still hyphenated and cut to the room, its last line ending in the ellipsis ("Poke-" / "mon" /
 * "Diamo..."); the old one-line ellipsis stays only where no hyphen would show. Device evidence the same day: on the Thor's Nintendo DS shelf at Dense two
 * books both read "Poke...", and the face title is the only label there.
 *
 * The ladder ([mimirFitBookTitle]) is driven with the PRODUCTION measure on a real text measurer at the Thor Bottom's density (369 dpi), with the book's own
 * geometry, as [MimirBookTitleFitTest] does. The platform hyphenator itself cannot be proved here (the host ships no hyphenation patterns: see the last
 * test, which accepts either answer); everything around it is: the order of the ladder, that a forced cut is never accepted, that only a word too wide for
 * the line takes a hyphen, and that a soft hyphen ends a line the way the book draws it. A dictionary stands in for the hyphenator. The tests that need the
 * host engine to draw a soft hyphen skip themselves (an assumption, not a pass) if it does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirBookTitleHyphenationTest {

    @get:Rule
    val compose = createComposeRule()

    private val density = Density(369f / 160f, 1.0f)
    private val shy = MimirSoftHyphen.toString()
    private val floorSp = MimirTypeStep.Micro.value

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

    // The book's geometry, as MediaArtBookCover and MimirBookTitleFitTest compute it.
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

    private fun bookIn(seatWidthPx: Float, seatHeightPx: Float): Book {
        val byHeight = seatHeightPx * 0.75f
        return if (byHeight <= seatWidthPx) Book(byHeight, seatHeightPx) else Book(seatWidthPx, seatWidthPx / 0.75f)
    }

    private val standard get() = bookIn(220f, 310f)
    private val dense get() = bookIn(171f, 241f)
    private val denseWide get() = bookIn(171f, 117f)

    /** A 3:4 book whose title room is [textPx] wide. */
    private fun bookOfTextWidth(textPx: Float): Book {
        val widthPx = textPx / 0.765f
        return Book(widthPx, widthPx / 0.75f)
    }

    private fun plainFit(title: String, book: Book): MimirBookTitleFit = fitWith(title, book, null)

    private fun fitWith(
        title: String,
        book: Book,
        hyphenation: MimirBookTitleHyphenation?,
        events: MutableList<Pair<String, Float>>? = null,
    ): MimirBookTitleFit {
        val whole = mimirBookTitleMeasure(measurer, title, base, book.textWidthPx, maxLines = 3)
        return mimirFitBookTitle(
            maxSp = book.titleSp,
            minSp = floorSp,
            maxLines = 3,
            availableHeightPx = book.availablePx,
            measure = { sp ->
                events?.add("whole" to sp)
                whole(sp)
            },
            hyphenation = hyphenation,
        )
    }

    /** A text laid out as the hyphenated title is drawn: the production measure of [mimirBookTitleHyphenation], in [MimirBookTitleHyphenStyle]. */
    private fun measureText(text: String, book: Book, sp: Float, maxLines: Int = 3): TextLayoutResult =
        mimirBookTitleHyphenation(measurer, text, base, book.textWidthPx).measure(text, sp, maxLines)

    /**
     * The hyphenation seam with a recorded, scripted [softHyphenated] (the same answer to a strict and to a lenient ask) and the production measure. The ask
     * of the hyphenated pass is recorded as "hyph", the lenient ask of the ellipsis fallback as "lenient".
     */
    private fun scripted(book: Book, events: MutableList<Pair<String, Float>>?, softHyphenated: (Float) -> String?) =
        MimirBookTitleHyphenation(
            softHyphenated = { sp, lenient ->
                events?.add((if (lenient) "lenient" else "hyph") to sp)
                softHyphenated(sp)
            },
            measure = { text, sp, lines -> measureText(text, book, sp, lines) },
        )

    /** The hyphenation of the ladder as production composes it, with a dictionary in place of the platform hyphenator. */
    private fun dictionaryHyphenation(title: String, book: Book): MimirBookTitleHyphenation =
        MimirBookTitleHyphenation(
            softHyphenated = { sp, lenient ->
                mimirBookTitleSoftHyphens(
                    title,
                    book.textWidthPx,
                    naturalWidthPx = { word -> boldWidth(word, sp) },
                    breaksOf = { word -> dictionary[word] },
                    lenient = lenient,
                )
            },
            measure = { text, sp, lines -> measureText(text, book, sp, lines) },
        )

    private val dictionary = mapOf(
        "Pokémon" to listOf(4),
        "Diamond" to listOf(3),
        "Platinum" to listOf(4),
        "Version" to listOf(3),
        "Earthbound" to listOf(5),
    )

    private val throwing = MimirBookTitleHyphenation(
        softHyphenated = { _, _ -> error("the hyphenator was consulted") },
        measure = { _, _, _ -> error("the hyphenated layout was measured") },
    )

    private fun layoutIn(text: String, widthPx: Int, sp: Float = 12f, extra: TextStyle = TextStyle.Default): TextLayoutResult =
        measurer.measure(
            text = AnnotatedString(text),
            style = base.merge(TextStyle(fontSize = sp.sp)).merge(extra),
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = widthPx),
        )

    private fun naturalWidthPx(text: String, sp: Float = 12f): Int = layoutIn(text, Constraints.Infinity, sp).size.width

    /** The width of a word on one line in the book's title style at a size. */
    private fun boldWidth(word: String, sp: Float): Float {
        val single = measurer.measure(
            text = AnnotatedString(word),
            style = mimirBookTitleStyleAt(base, sp),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        )
        return single.getLineRight(0) - single.getLineLeft(0)
    }

    private fun lineText(layout: TextLayoutResult, line: Int): String =
        layout.layoutInput.text.text.substring(layout.getLineStart(line), layout.getLineEnd(line))

    private fun lines(layout: TextLayoutResult): List<String> = (0 until layout.lineCount).map { lineText(layout, it) }

    /** "Earth" + soft hyphen + "bound" in just enough room for "Earth-": the engine must end line 0 at the soft hyphen and draw the hyphen. */
    private fun softHyphenLayout(extra: TextStyle = MimirBookTitleHyphenStyle): TextLayoutResult =
        layoutIn("Earth${shy}bound", naturalWidthPx("Earth") + naturalWidthPx("-") + 2, extra = extra)

    private fun softHyphenHonoured(): Boolean {
        val layout = softHyphenLayout()
        return layout.lineCount >= 2 && mimirBookTitleEndsAtSoftHyphen(layout, 0) &&
            layout.getLineRight(0) - layout.getLineLeft(0) > naturalWidthPx("Earth") + naturalWidthPx("-") / 2f
    }

    private fun words(text: String): List<String> = text.split(Regex("[ /-]+")).filter { it.isNotEmpty() }

    /**
     * The law of a hyphenated answer, read off the layout and not through the function under test: the text is the title and nothing else but soft hyphens,
     * it fits the budget, every line that ends inside a word ends at a soft hyphen, there is no ellipsis, a word that fits the line stays whole, and a word
     * with a hyphen in it is one that did not fit the line.
     */
    private fun assertHyphenatedLaw(where: String, title: String, book: Book, fit: MimirBookTitleFit) {
        val text = fit.hyphenatedText
        assertNotNull("$where: a hyphenated answer carries its text", text)
        assertTrue("$where: and fits", fit.fits)
        assertEquals("$where: the text is the title with soft hyphens in it", title, text!!.replace(shy, ""))
        val layout = measureText(text, book, fit.sizeSp, fit.maxLines)
        assertFalse("$where: drops no text: ${lines(layout)}", layout.didOverflowHeight)
        assertTrue("$where: stands in the room", layout.size.height <= book.availablePx + 0.5f)
        assertTrue("$where: within three lines: ${lines(layout)}", layout.lineCount <= 3)
        assertFalse("$where: no ellipsis", (0 until layout.lineCount).any(layout::isLineEllipsized))
        for (line in mimirBookTitleCutLines(layout)) {
            assertTrue("$where: line $line ends inside a word without a hyphen: ${lines(layout)}", mimirBookTitleEndsAtSoftHyphen(layout, line))
        }
        for (word in words(text)) {
            val whole = word.replace(shy, "")
            if (word.contains(shy)) {
                assertTrue("$where: '$whole' took a hyphen though it fits a line (${boldWidth(whole, fit.sizeSp)} of ${book.textWidthPx} px)", boldWidth(whole, fit.sizeSp) > book.textWidthPx)
            } else {
                assertTrue("$where: '$whole' is too wide for a line and was not hyphenated", boldWidth(whole, fit.sizeSp) <= book.textWidthPx)
            }
        }
    }

    /**
     * The law of the ellipsis fallback, read off the layout: the same text as the title with soft hyphens, at the floor, in the lines it was measured in and
     * the room, the last line ending in the ellipsis, every other line ending where a line may end or at a soft hyphen (a forced cut is only ever the final
     * ellipsised line), at least one hyphen shown, and a soft hyphen only in a word too wide for the line.
     */
    private fun assertEllipsisLaw(where: String, title: String, book: Book, fit: MimirBookTitleFit) {
        val text = fit.hyphenatedText
        assertNotNull("$where: the fallback carries its hyphenated text", text)
        assertFalse("$where: it does not fit whole", fit.fits)
        assertEquals("$where: the text is the title with soft hyphens in it", title, text!!.replace(shy, ""))
        assertEquals("$where: at the floor", floorSp, fit.sizeSp, 0.001f)
        assertTrue("$where: two or three lines, since a hyphen has to show: ${fit.maxLines}", fit.maxLines in 2..3)
        val layout = measureText(text, book, fit.sizeSp, fit.maxLines)
        assertEquals("$where: drawn in the lines it was measured in: ${lines(layout)}", fit.maxLines, layout.lineCount)
        assertTrue("$where: stands in the room", layout.size.height <= book.availablePx + 0.5f)
        assertTrue("$where: the last line ends in the ellipsis: ${lines(layout)}", layout.isLineEllipsized(layout.lineCount - 1))
        for (line in mimirBookTitleCutLines(layout)) {
            assertTrue("$where: line $line ends inside a word without a hyphen: ${lines(layout)}", mimirBookTitleEndsAtSoftHyphen(layout, line))
        }
        assertTrue("$where: a hyphen shows: ${lines(layout)}", (0 until layout.lineCount - 1).any { mimirBookTitleEndsAtSoftHyphen(layout, it) })
        for (word in words(text)) {
            if (word.contains(shy)) {
                val whole = word.replace(shy, "")
                assertTrue("$where: '$whole' took a hyphen though it fits a line", boldWidth(whole, fit.sizeSp) > book.textWidthPx)
            }
        }
    }

    /** Either answer a hyphenation can give: the hyphenated title that fits, or the hyphenated title cut to the room. */
    private fun assertLawful(where: String, title: String, book: Book, fit: MimirBookTitleFit) =
        if (fit.fits) assertHyphenatedLaw(where, title, book, fit) else assertEllipsisLaw(where, title, book, fit)

    /** What the book shows of an ellipsised layout: its whole lines, and as much of the last as fits beside the ellipsis (the engine ends the line there). */
    private fun displayed(layout: TextLayoutResult, sp: Float, widthPx: Int): String {
        val last = layout.lineCount - 1
        val head = (0 until last).joinToString("") { lineText(layout, it) }.replace(shy, "")
        val rest = layout.layoutInput.text.text.substring(layout.getLineStart(last)).replace(shy, "")
        val keep = (rest.length downTo 0).firstOrNull { boldWidth(rest.take(it).trimEnd() + "\u2026", sp) <= widthPx } ?: 0
        return head + rest.take(keep).trimEnd() + "\u2026"
    }

    @Test
    fun `a soft hyphen draws its hyphen only in a style that hyphenates, which is why the title is drawn in one`() {
        val withoutHyphens = softHyphenLayout(extra = TextStyle.Default)
        println("BOOKHYPH style-pin without: lines=${lines(withoutHyphens).map { it.replace(shy, "<SHY>") }} right0=${withoutHyphens.getLineRight(0)} nat(Earth)=${naturalWidthPx("Earth")}")
        assumeTrue("this host breaks a line at a soft hyphen", withoutHyphens.lineCount >= 2 && mimirBookTitleEndsAtSoftHyphen(withoutHyphens, 0))
        assertFalse(
            "without automatic hyphens the break is made but no hyphen is drawn or counted",
            withoutHyphens.getLineRight(0) - withoutHyphens.getLineLeft(0) > naturalWidthPx("Earth") + naturalWidthPx("-") / 2f,
        )
        val withHyphens = softHyphenLayout()
        assertTrue("with them the hyphen is drawn and counted", withHyphens.getLineRight(0) - withHyphens.getLineLeft(0) > naturalWidthPx("Earth") + naturalWidthPx("-") / 2f)
    }

    // -- Telling a hyphen from a forced cut ----------------------------------------------------------

    @Test
    fun `a forced cut is told from a drawn hyphen`() {
        val cut = layoutIn("Earthbound", naturalWidthPx("Earthbound") / 2)
        assertTrue("the word is split in two or more lines: ${lines(cut)}", cut.lineCount >= 2)
        assertEquals(0, mimirBookTitleFirstCutLine(cut))
        assertFalse("a forced cut draws no hyphen: ${lines(cut)}", mimirBookTitleLineEndsAtHyphen(measurer, cut, 0))
        assertFalse("and is no soft hyphen", mimirBookTitleEndsAtSoftHyphen(cut, 0))

        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        val hyphenated = softHyphenLayout()
        println("BOOKHYPH soft-hyphen lines=${lines(hyphenated)} lineWidth0=${hyphenated.getLineRight(0)} hyphen=${naturalWidthPx("-")}")
        assertTrue("the drawn hyphen widens the line by about one hyphen", mimirBookTitleLineEndsAtHyphen(measurer, hyphenated, 0))
        assertTrue(mimirBookTitleEndsAtSoftHyphen(hyphenated, 0))
        assertEquals("the detector still reads the line as one that ends inside a word", 0, mimirBookTitleFirstCutLine(hyphenated))
    }

    @Test
    fun `accents are folded one character for one character so the hyphenator can see the word`() {
        assertEquals("Pokemon", mimirBookTitleAsciiFold("Pokémon"))
        assertEquals("Muller nandu Angstrom", mimirBookTitleAsciiFold("Müller ñandú Ångström"))
        for (title in listOf("Pokémon", "Müller ñandú Ångström", "Straße Øl", "ファイナル", "Earthbound")) {
            assertEquals("$title: the same length, so every offset maps back", title.length, mimirBookTitleAsciiFold(title).length)
        }
        assertEquals("a letter with no plain base stays", "Straße", mimirBookTitleAsciiFold("Straße"))
        assertEquals("ファイナル", mimirBookTitleAsciiFold("ファイナル"))
    }

    // -- Where a soft hyphen goes ---------------------------------------------------------------------

    @Test
    fun `soft hyphens go only into a word too wide for the line and a title with nothing to hyphenate says so`() {
        val widths = mapOf("Supercalifragilistic" to 190f, "Pokémon" to 70f, "Diamond" to 40f, "Platinum" to 80f, "Version" to 35f, "Super" to 30f, "Earthbound" to 90f, "Go" to 12f)
        fun hyphenate(title: String, lenient: Boolean = false, breaksOf: (String) -> List<Int>? = { dictionary[it] }) =
            mimirBookTitleSoftHyphens(title, 50, naturalWidthPx = { widths.getValue(it) }, breaksOf = breaksOf, lenient = lenient)
        assertEquals("Poké${shy}mon Diamond Version", hyphenate("Pokémon Diamond Version"))
        assertEquals("Poké${shy}mon Plat${shy}inum Version", hyphenate("Pokémon Platinum Version"))
        assertEquals("a slash is a place to break anyway, so each side is its own word", "Super/Earth${shy}bound", hyphenate("Super/Earthbound"))
        assertNull("no word is too wide: nothing to hyphenate", hyphenate("Diamond Version"))
        assertNull("a too-wide word with no real hyphen fails the whole title", hyphenate("Pokémon Go") { null })
        assertNull("so does one the hyphenator gave nothing for", hyphenate("Pokémon Go") { emptyList() })
        assertNull("and one whose break is not inside the word", hyphenate("Pokémon Go") { listOf(0) })
        assertNull("a too-wide word that cannot be broken fails the whole title", hyphenate("Pokémon Supercalifragilistic"))
        assertEquals(
            "but a lenient ask breaks the words that can be and leaves that one whole",
            "Poké${shy}mon Supercalifragilistic",
            hyphenate("Pokémon Supercalifragilistic", lenient = true),
        )
        assertNull("a lenient ask with no word broken at all says so", hyphenate("Pokémon Go", lenient = true) { null })
        assertNull("and so does one with nothing too wide", hyphenate("Diamond Version", lenient = true))
    }

    // -- The ladder -----------------------------------------------------------------------------------

    @Test
    fun `whole words first, then hyphens from the top size down, and the first size that holds the title wins`() {
        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        // A book whose room holds "Earth-" and "bound" at the floor and a little above it, but never "Earthbound".
        val textPx = maxOf(boldWidth("Earth-", floorSp), boldWidth("bound", floorSp)) + 6f
        assumeTrue("the word is too wide for the room at the floor", boldWidth("Earthbound", floorSp) > textPx + 5f)
        val book = bookOfTextWidth(textPx)
        assertFalse("whole words fit at no size", plainFit("Earthbound", book).fits)

        val events = mutableListOf<Pair<String, Float>>()
        val fit = fitWith("Earthbound", book, scripted(book, events) { "Earth${shy}bound" }, events)
        val wholeSizes = events.filter { it.first == "whole" }.map { it.second }
        val hyphSizes = events.filter { it.first == "hyph" }.map { it.second }
        println("BOOKHYPH ladder whole=$wholeSizes hyph=$hyphSizes -> $fit")

        val firstHyph = events.indexOfFirst { it.first == "hyph" }
        assertTrue("the hyphenator is asked", firstHyph > 0)
        assertTrue("every whole-word size was tried before it was asked", events.take(firstHyph).all { it.first == "whole" } && events.drop(firstHyph).none { it.first == "whole" })
        assertEquals("from the book's size", book.titleSp, wholeSizes.first(), 0.001f)
        assertEquals("down to the floor", floorSp, wholeSizes.last(), 0.001f)
        assertEquals("then hyphens start at the top size", book.titleSp, hyphSizes.first(), 0.001f)
        for ((above, below) in hyphSizes.zipWithNext()) assertEquals("and walk down by half a sp", 0.5f, above - below, 0.001f)
        assertTrue("the answer is hyphenated", fit.fits && fit.hyphenatedText == "Earth${shy}bound")
        assertTrue("and the ellipsis fallback was never asked", events.none { it.first == "lenient" })
        assertEquals("the first size that holds it is the answer, and no size below it was asked", fit.sizeSp, hyphSizes.last(), 0.001f)
        assertHyphenatedLaw("Earthbound", "Earthbound", book, fit)
        val layout = measureText(fit.hyphenatedText!!, book, fit.sizeSp, fit.maxLines)
        assertEquals(listOf("Earth$shy", "bound"), lines(layout))
    }

    @Test
    fun `a forced cut is never accepted, so a hyphenator that gives no hyphen leaves the ellipsis`() {
        val book = denseWide
        val plain = plainFit("Earthbound", book)
        assertFalse(plain.fits)
        val events = mutableListOf<Pair<String, Float>>()
        // The title handed back whole: the engine can only split the word where it runs out of room.
        val fit = fitWith("Earthbound", book, scripted(book, events) { "Earthbound" }, events)
        assertEquals("the old answer, exactly", plain, fit)
        assertNull(fit.hyphenatedText)
        val wholeSizes = events.filter { it.first == "whole" }.map { it.second }
        val hyphSizes = events.filter { it.first == "hyph" }.map { it.second }
        assertEquals("every size was offered, top to floor", wholeSizes, hyphSizes)
        assertEquals("the ellipsis fallback was asked once, at the floor", listOf(floorSp), events.filter { it.first == "lenient" }.map { it.second })
    }

    @Test
    fun `a hyphenator that has nothing to offer leaves the ellipsis exactly as it was`() {
        for (book in listOf(dense, denseWide)) {
            val title = "The Legend of Zelda Ocarina of Time Master Quest Collectors Edition"
            val plain = plainFit(title, book)
            val events = mutableListOf<Pair<String, Float>>()
            val fit = fitWith(title, book, scripted(book, events) { null }, events)
            assertEquals(plain, fit)
            if (!plain.fits) assertEquals("it was asked at every size", events.count { it.first == "whole" }, events.count { it.first == "hyph" })
        }
    }

    @Test
    fun `a title that holds its words whole at some size is untouched and never reaches the hyphenator`() {
        val titles = listOf("Super Mario World", "Chrono Trigger", "Super Metroid", "Earthbound", "Pokémon Diamond Version", "Pokémon Platinum Version")
        var pinned = 0
        for ((name, book) in listOf("Standard" to standard, "Dense" to dense)) {
            for (title in titles) {
                val plain = plainFit(title, book)
                println("BOOKHYPH pin $name '$title' -> $plain")
                if (!plain.fits) continue
                assertEquals("$name '$title'", plain, fitWith(title, book, throwing))
                assertNull(plain.hyphenatedText)
                pinned++
            }
        }
        assertTrue("the pin ran for the titles that fit: $pinned", pinned >= 4)
    }

    @Test
    fun `a script that breaks between characters is never hyphenated`() {
        val book = denseWide
        val title = "ファイナルファンタジー".repeat(6)
        val plain = plainFit(title, book)
        assumeTrue("the title overflows the narrowest book", !plain.fits)
        assertEquals(plain, fitWith(title, book, throwing))
        val mixed = "Earthbound ファイナルファンタジー"
        assertEquals("a title with any such character keeps the old answer too", plainFit(mixed, book), fitWith(mixed, book, throwing))
    }

    // -- Pokemon: accents and several words ----------------------------------------------------------

    @Test
    fun `Pokemon Go takes a hyphen only inside Pokemon, and whole words stay whole`() {
        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        val pokemonAtFloor = boldWidth("Pokémon", floorSp).toInt()
        val answers = mutableListOf<Pair<Int, MimirBookTitleFit>>()
        for (textPx in (pokemonAtFloor - 16)..(pokemonAtFloor - 1)) {
            val book = bookOfTextWidth(textPx.toFloat())
            val plain = plainFit("Pokémon Go", book)
            val fit = fitWith("Pokémon Go", book, dictionaryHyphenation("Pokémon Go", book))
            println("BOOKHYPH pokemon-go text=${book.textWidthPx}px plain=$plain -> $fit ${fit.hyphenatedText?.let { lines(measureText(it, book, fit.sizeSp)) } ?: ""}")
            if (fit.hyphenatedText == null) assertEquals(plain, fit) else assertLawful("Pokémon Go at $textPx px", "Pokémon Go", book, fit)
            answers.add(textPx to fit)
        }
        val hyphenated = answers.filter { it.second.hyphenatedText != null }
        assertTrue("a room too narrow for Pokémon and wide enough for Poké- and 'mon Go' hyphenates it: $answers", hyphenated.isNotEmpty())
        assertTrue(hyphenated.all { it.second.hyphenatedText == "Poké${shy}mon Go" })
    }

    @Test
    fun `Pokemon Diamond and Platinum Version keep every law at every width`() {
        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        val outcomes = mutableListOf<String>()
        for (title in listOf("Pokémon Diamond Version", "Pokémon Platinum Version")) {
            val widths = listOf(denseWide.textWidthPx, dense.textWidthPx) + (56..160 step 4)
            for (textPx in widths) {
                val book = bookOfTextWidth(textPx.toFloat())
                val plain = plainFit(title, book)
                val fit = fitWith(title, book, dictionaryHyphenation(title, book))
                if (fit.hyphenatedText == null) {
                    assertEquals("$title at $textPx px keeps the old answer", plain, fit)
                    outcomes.add("$textPx:${if (plain.fits) "whole" else "ellipsis"}")
                } else {
                    assertLawful("$title at $textPx px", title, book, fit)
                    outcomes.add(if (fit.fits) "$textPx:hyphen@${fit.sizeSp}" else "$textPx:hyphen+ellipsis/${fit.maxLines}lines")
                }
            }
            println("BOOKHYPH pokemon-versions '$title' $outcomes")
            outcomes.clear()
        }
        // The two named geometries, printed for the report.
        for ((name, book) in listOf("Dense" to dense, "Dense wide" to denseWide)) {
            for (title in listOf("Pokémon Diamond Version", "Pokémon Platinum Version")) {
                val fit = fitWith(title, book, dictionaryHyphenation(title, book))
                val shownText = fit.hyphenatedText ?: title
                println("BOOKHYPH named $name text=${book.textWidthPx}px '$title' -> $fit lines=${lines(measureText(shownText, book, fit.sizeSp, fit.maxLines))}")
            }
        }
    }

    // -- The ellipsis fallback : hyphenate, and end in the ellipsis --------------------

    @Test
    fun `Pokemon Diamond and Platinum Version at the DS Dense width are hyphenated, end in an ellipsis and stay distinguishable`() {
        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        // The Nintendo DS cover is 1.11 wide-to-tall (`platformCoverAspect`), so at Dense 6x3 its seat is the region's 171 px wide and 171 / 1.11 = 154 px tall,
        // and the 3:4 book in it is 115.5 px wide with 88 px of room for the title.
        val book = bookIn(171f, 154f)
        assertTrue("the DS Dense title room is about 88 px: ${book.textWidthPx}", book.textWidthPx in 87..89)
        val shown = mutableMapOf<String, String>()
        for (title in listOf("Pokémon Diamond Version", "Pokémon Platinum Version")) {
            val plain = plainFit(title, book)
            println("BOOKHYPH ds-dense old '$title' -> $plain")
            assumeTrue("Pokémon does not fit whole at the floor in the DS Dense room", !plain.fits)
            assertEquals("the old answer is the one line 'Poke...' of the device photo", 1, plain.maxLines)

            val events = mutableListOf<Pair<String, Float>>()
            val fit = fitWith(
                title,
                book,
                scripted(book, events) { sp -> mimirBookTitleSoftHyphens(title, book.textWidthPx, naturalWidthPx = { boldWidth(it, sp) }, breaksOf = { dictionary[it] }) },
                events,
            )
            val layout = measureText(fit.hyphenatedText ?: title, book, fit.sizeSp, fit.maxLines)
            val text = displayed(layout, fit.sizeSp, book.textWidthPx)
            println("BOOKHYPH ds-dense new '$title' -> $fit lines=${lines(layout).map { it.replace(shy, "<SHY>") }} shows='$text'")
            assertEllipsisLaw(title, title, book, fit)
            assertTrue("$title: the first line is Poké- : ${lines(layout)}", lineText(layout, 0) == "Poké$shy")
            assertTrue("$title: and 'mon' follows on the next line: ${lines(layout)}", lineText(layout, 1).startsWith("mon"))

            // The ladder's order: every whole-word size, then the hyphenated pass at every size top to floor, then ONE lenient ask at the floor and nothing after.
            val wholeSizes = events.filter { it.first == "whole" }.map { it.second }
            val hyphSizes = events.filter { it.first == "hyph" }.map { it.second }
            assertEquals("every size was offered to the hyphenated pass, top to floor", wholeSizes, hyphSizes)
            assertEquals(listOf(floorSp), events.filter { it.first == "lenient" }.map { it.second })
            assertEquals("and the fallback came last", "lenient", events.last().first)
            shown[title] = text
        }
        assertNotEquals("the two books read differently: $shown", shown.getValue("Pokémon Diamond Version"), shown.getValue("Pokémon Platinum Version"))
        assertTrue("Diamond shows its name: $shown", shown.getValue("Pokémon Diamond Version").contains("Dia"))
        assertTrue("Platinum shows its name: $shown", shown.getValue("Pokémon Platinum Version").contains("Pla"))
    }

    @Test
    fun `a word the hyphenator cannot break ends the title there, after the hyphens that do show`() {
        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        val book = bookIn(171f, 154f)
        val title = "Pokémon Supercalifragilistic"
        assumeTrue("the long word is too wide for the room", boldWidth("Supercalifragilistic", floorSp) > book.textWidthPx)
        val hyphenation = dictionaryHyphenation(title, book)
        assertNull("a strict ask fails on the word it cannot break", hyphenation.softHyphenated(floorSp, false))
        assertEquals("a lenient ask hyphenates the others", "Poké${shy}mon Supercalifragilistic", hyphenation.softHyphenated(floorSp, true))
        val fit = fitWith(title, book, hyphenation)
        val layout = measureText(fit.hyphenatedText ?: title, book, fit.sizeSp, fit.maxLines)
        println("BOOKHYPH unbreakable $fit lines=${lines(layout).map { it.replace(shy, "<SHY>") }} shows='${displayed(layout, fit.sizeSp, book.textWidthPx)}'")
        assertEllipsisLaw(title, title, book, fit)
    }

    @Test
    fun `the ellipsis fallback needs a hyphen that shows, so a room too narrow for the first syllable keeps the old ellipsis`() {
        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        // A room narrower than "Poké-" at the floor: the engine has to cut the first line inside the word, there is no hyphen to show.
        val book = bookOfTextWidth(50f)
        val title = "Pokémon Go"
        assumeTrue("Poké- is wider than the room", boldWidth("Poké-", floorSp) > book.textWidthPx + 2f)
        val plain = plainFit(title, book)
        assertFalse(plain.fits)
        val fit = fitWith(title, book, dictionaryHyphenation(title, book))
        assertEquals("the old ellipsis stands, no hyphenated text", plain, fit)
        assertNull(fit.hyphenatedText)
    }

    // -- The probe, driven through the seam by an engine that hyphenates -------------------------------------

    private val foldedPoints = mapOf("Pokemon" to listOf(4), "Diamond" to listOf(3), "Platinum" to listOf(4), "Version" to listOf(3), "Earthbound" to listOf(5))

    /**
     * The probe's side of the seam with an engine that hyphenates where the host's cannot (the host ships no hyphenation patterns): the accent-free word laid
     * out with a soft hyphen at each known point. [asked] collects the words the probe sends.
     */
    private fun standInEngine(asked: MutableList<String>): (String, TextStyle, Int) -> TextLayoutResult = { word, style, px ->
        asked.add(word)
        val text = StringBuilder(word)
        for (offset in (foldedPoints[word] ?: emptyList()).asReversed()) text.insert(offset, MimirSoftHyphen)
        measurer.measure(text = AnnotatedString(text.toString()), style = style, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = px))
    }

    @Test
    fun `the probe finds the breaks an engine that hyphenates gives, so a probe that finds none fails here`() {
        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        val book = bookIn(171f, 154f)
        val title = "Pok\u00e9mon Diamond Version"
        val asked = mutableListOf<String>()
        val hyphenation = mimirBookTitleHyphenation(measurer, title, base, book.textWidthPx, probeLayout = standInEngine(asked))
        val text = hyphenation.softHyphenated(floorSp, false)
        assertNotNull("the engine hyphenates Pokemon and Diamond, so the probe has breaks to give; a probe that answers null fails here", text)
        assertEquals(title, text!!.replace(shy, ""))
        assertTrue("Pok\u00e9mon is broken after Pok\u00e9: $text", text.contains("Pok\u00e9${shy}mon"))
        assertTrue("the engine is asked the accent-free word, never the accented one: $asked", asked.isNotEmpty() && "Pokemon" in asked && asked.all { word -> word.all { it.code < 0x80 } })
        assertEquals("and each word once per size, however often the fit asks (strict, then lenient)", asked.size, asked.toSet().size)
        val before = asked.size
        assertEquals("a lenient ask at the same size gives the same breaks", text, hyphenation.softHyphenated(floorSp, true))
        assertEquals("and probes nothing again", before, asked.size)
    }

    @Test
    fun `an engine with no hyphenation points leaves the probe nothing, strict or lenient`() {
        val book = bookIn(171f, 154f)
        val title = "Pok\u00e9mon Diamond Version"
        val engineWithoutPoints: (String, TextStyle, Int) -> TextLayoutResult = { word, style, px ->
            measurer.measure(text = AnnotatedString(word), style = style, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = px))
        }
        val hyphenation = mimirBookTitleHyphenation(measurer, title, base, book.textWidthPx, probeLayout = engineWithoutPoints)
        assertNull(hyphenation.softHyphenated(floorSp, false))
        assertNull(hyphenation.softHyphenated(floorSp, true))
    }

    @Test
    fun `the whole fit through the production hyphenation settles on the engine's breaks, and fails if the probe finds none`() {
        assumeTrue("this host draws a soft hyphen at a line end", softHyphenHonoured())
        // The DS Dense room: no size holds the hyphenated title, so it is cut to the room with the ellipsis.
        val ds = bookIn(171f, 154f)
        val title = "Pok\u00e9mon Platinum Version"
        val cut = fitWith(title, ds, mimirBookTitleHyphenation(measurer, title, base, ds.textWidthPx, probeLayout = standInEngine(mutableListOf())))
        assertNotNull("the probe found the engine's breaks, so the title is hyphenated and not the old 'Poke...'", cut.hyphenatedText)
        assertEllipsisLaw(title, title, ds, cut)
        // A room that holds Earth- and bound: the hyphenated pass answers.
        val textPx = maxOf(boldWidth("Earth-", floorSp), boldWidth("bound", floorSp)) + 6f
        assumeTrue("the word is too wide for the room at the floor", boldWidth("Earthbound", floorSp) > textPx + 5f)
        val room = bookOfTextWidth(textPx)
        val whole = fitWith("Earthbound", room, mimirBookTitleHyphenation(measurer, "Earthbound", base, room.textWidthPx, probeLayout = standInEngine(mutableListOf())))
        assertNotNull("and Earthbound is hyphenated at a size that holds it", whole.hyphenatedText)
        assertHyphenatedLaw("Earthbound", "Earthbound", room, whole)
    }

    /** True when the host's own engine, asked to hyphenate, ever ends a line in a drawn hyphen: it ships hyphenation patterns. */
    private fun hostHyphenates(): Boolean {
        val style = base.merge(TextStyle(fontSize = 12.sp)).merge(TextStyle(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto))
        for (word in listOf("Earthbound", "Pokemon", "Information", "Hyphenation")) {
            val full = naturalWidthPx(word)
            for (width in (full / 3)..(full - 2) step 3) {
                val layout = measurer.measure(text = AnnotatedString(word), style = style, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width))
                if (layout.lineCount >= 2 && mimirBookTitleLineEndsAtHyphen(measurer, layout, 0)) return true
            }
        }
        return false
    }

    @Test
    fun `where the host does ship hyphenation patterns the platform probe must find them`() {
        val hostHyphenates = hostHyphenates()
        println("BOOKHYPH host-patterns=$hostHyphenates")
        assumeTrue("this host ships no hyphenation patterns, so the platform hyphenator is device-only", hostHyphenates)
        val book = denseWide
        val hyphenation = mimirBookTitleHyphenation(measurer, "Earthbound", base, book.textWidthPx)
        assertNotNull("the host hyphenates, so a probe that finds no break for Earthbound is broken", hyphenation.softHyphenated(floorSp, false))
    }

    // -- The platform hyphenator itself (device only) ---------------------------------------------------

    @Test
    fun `with the platform hyphenator the answer is the old one or a lawful hyphenation, never a forced cut`() {
        for ((name, book) in listOf("Dense" to dense, "Dense wide" to denseWide)) {
            for (title in listOf("Earthbound", "Pokémon Diamond Version", "Pokémon Platinum Version", "Pokémon Go")) {
                val plain = plainFit(title, book)
                val fit = fitWith(title, book, mimirBookTitleHyphenation(measurer, title, base, book.textWidthPx))
                println("BOOKHYPH platform $name '$title' plain=$plain -> $fit")
                if (fit.hyphenatedText == null) {
                    assertEquals("$name '$title': this host has no hyphenation patterns, so the old answer", plain, fit)
                } else {
                    assertLawful("$name '$title'", title, book, fit)
                }
            }
        }
    }
}
