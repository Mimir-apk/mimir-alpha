package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The coverless book as it is composed (hyphenated titles): [MediaArtBookCover] draws the fit's
 * hyphenated text in the style it was measured in, says the CLEAN title to accessibility and to a text match (the soft hyphens are for the line breaker and
 * would be read out as "Poke-mon"), draws a title that fits whole as it always did, and finds a title it has already settled in [MimirBookTitleFits].
 *
 * The host ships no hyphenation patterns, so the book is given an engine that hyphenates through [LocalMimirBookTitleHyphenator]: the production
 * hyphenation (the fold, the probe, the check of a drawn hyphen, the ladder, the drawing) over an engine that lays a word out with soft hyphens at known
 * points. Only the pattern lookup of the platform hyphenator is stood in for.
 *
 * The book is the Nintendo DS's at Dense on the Thor Bottom (369 dpi): 171 px of seat, 115 x 154 px of book, about 87 px of room for the title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirBookTitleDrawnTest {

    @get:Rule
    val compose = createComposeRule()

    private val density = Density(369f / 160f, 1.0f)
    private val shy = MimirSoftHyphen.toString()
    private val points = mapOf("Pokemon" to listOf(4), "Diamond" to listOf(3), "Platinum" to listOf(4), "Version" to listOf(3))

    @Before
    fun forgetEveryDecidedFit() {
        MimirBookTitleFits.clear()
    }

    /** The production hyphenation over an engine that hyphenates; [builds] collects the titles books built one for, [asked] the words the probe was sent. */
    private fun hyphenatorWithEngine(builds: MutableList<String>, asked: MutableList<String>): MimirBookTitleHyphenator = { measurer, title, base, widthPx ->
        builds.add(title)
        mimirBookTitleHyphenation(
            measurer,
            title,
            base,
            widthPx,
            probeLayout = { word, style, px ->
                asked.add(word)
                val text = StringBuilder(word)
                for (offset in (points[word] ?: emptyList()).asReversed()) text.insert(offset, MimirSoftHyphen)
                measurer.measure(text = AnnotatedString(text.toString()), style = style, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = px))
            },
        )
    }

    private fun show(hyphenator: MimirBookTitleHyphenator, vararg titles: String) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density, LocalMimirBookTitleHyphenator provides hyphenator) {
                MimirTheme {
                    Column {
                        for (title in titles) {
                            Box(Modifier.size(width = 50.08.dp, height = 66.77.dp)) {
                                MediaArtBookCover(title = title, platformIdValue = "nds", fitTitle = true)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun titleNode(index: Int) = compose.onAllNodesWithTag(MediaArtBookTitleTag, useUnmergedTree = true)[index].fetchSemanticsNode()

    /** The layout the title `Text` actually laid out, read the way the shelf tests read it. */
    private fun drawn(index: Int): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        titleNode(index).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun lines(layout: TextLayoutResult) = (0 until layout.lineCount).map { layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(shy, "<SHY>") }

    @Test
    fun `a hyphenated title is drawn as it was measured, in the hyphen style, and says its clean title`() {
        val title = "Pokémon Diamond Version"
        val builds = mutableListOf<String>()
        val asked = mutableListOf<String>()
        show(hyphenatorWithEngine(builds, asked), title)
        val layout = drawn(0)
        val input = layout.layoutInput
        println("BOOKDRAWN '$title' lines=${lines(layout)} style hyphens=${input.style.hyphens} lineBreak=${input.style.lineBreak} maxLines=${input.maxLines} size=${input.style.fontSize}")

        // What is drawn: the fit's hyphenated text, in the style the fit measured it in.
        assertTrue("the soft hyphens reach the line breaker: ${lines(layout)}", input.text.text.contains(shy))
        assertEquals(title, input.text.text.replace(shy, ""))
        assertEquals(Hyphens.Auto, input.style.hyphens)
        assertEquals(LineBreak.Simple, input.style.lineBreak)
        assertEquals(TextOverflow.Ellipsis, input.overflow)
        assertEquals("at the floor", 9f, input.style.fontSize.value, 0.001f)
        assertEquals(FontWeight.Bold, input.style.fontWeight)
        assertTrue("two or three lines, the hyphen shows: ${input.maxLines}", input.maxLines in 2..3)
        assertEquals("in the lines it was measured in", input.maxLines, layout.lineCount)
        assertTrue("the last line ends in the ellipsis", layout.isLineEllipsized(layout.lineCount - 1))
        assertEquals("the first line ends at a soft hyphen: ${lines(layout)}", MimirSoftHyphen, input.text.text[layout.getLineStart(1) - 1])

        // What is said: the clean title, to accessibility and to a text match, and never the soft hyphen.
        assertEquals(listOf(title), titleNode(0).config[SemanticsProperties.Text].map { it.text })
        compose.onNodeWithText(title, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(shy, substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a title that fits whole is drawn as it always was and the hyphenator is never asked a word`() {
        val title = "Super Mario World"
        val builds = mutableListOf<String>()
        val asked = mutableListOf<String>()
        show(hyphenatorWithEngine(builds, asked), title)
        val layout = drawn(0)
        assertEquals(title, layout.layoutInput.text.text)
        assertNotEquals("no hyphenating style on a title that needs none", Hyphens.Auto, layout.layoutInput.style.hyphens)
        assertTrue("and no word was probed: $asked", asked.isEmpty())
        assertEquals(listOf(title), titleNode(0).config[SemanticsProperties.Text].map { it.text })
    }

    @Test
    fun `a title already settled is found again, not laid out again`() {
        val diamond = "Pokémon Diamond Version"
        val platinum = "Pokémon Platinum Version"
        val builds = mutableListOf<String>()
        val asked = mutableListOf<String>()
        show(hyphenatorWithEngine(builds, asked), diamond, diamond, platinum)
        assertEquals("two books of one name are settled once; the third is another name: $builds", listOf(diamond, platinum), builds)
        assertEquals("two fits are remembered", 2, MimirBookTitleFits.size)
        assertEquals("the two Diamond books draw the same text", drawn(0).layoutInput.text.text, drawn(1).layoutInput.text.text)
        assertNotEquals("and the Platinum one reads differently", drawn(0).layoutInput.text.text, drawn(2).layoutInput.text.text)
    }

    // -- The memory itself -----------------------------------------------------------------------------

    private fun key(title: String, widthPx: Int = 88, style: TextStyle = TextStyle.Default, hyphenator: Any = this) = MimirBookTitleFitKey(
        title = title,
        widthPx = widthPx,
        availableHeightPx = 93f,
        maxSp = 12f,
        minSp = 9f,
        maxLines = 3,
        density = 2.3f,
        fontScale = 1f,
        style = style,
        locale = "en-US",
        hyphenator = hyphenator,
    )

    private fun fit(sizeSp: Float) = MimirBookTitleFit(sizeSp, 3, fits = true)

    @Test
    fun `the memory decides a key once and the least recently used one leaves first`() {
        val cache = MimirBookTitleFitCache(capacity = 2)
        var computes = 0
        fun get(key: MimirBookTitleFitKey, sizeSp: Float) = cache.getOrCompute(key) { computes++; fit(sizeSp) }
        assertEquals(12f, get(key("a"), 12f).sizeSp, 0f)
        assertEquals(11f, get(key("b"), 11f).sizeSp, 0f)
        assertEquals("a is found, not computed again", 12f, get(key("a"), 99f).sizeSp, 0f)
        assertEquals(2, computes)
        get(key("c"), 10f)
        assertEquals("a capacity of two holds two", 2, cache.size)
        assertEquals(3, computes)
        assertEquals("a was used last before c came, so it stayed", 12f, get(key("a"), 99f).sizeSp, 0f)
        assertEquals(3, computes)
        assertEquals("b was the least recently used, so it went and is computed again", 9f, get(key("b"), 9f).sizeSp, 0f)
        assertEquals(4, computes)
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `every thing a fit depends on is part of its key`() {
        val cache = MimirBookTitleFitCache(capacity = 16)
        val base = key("Pokémon")
        val others = listOf(
            base.copy(title = "Pokémon Go"),
            base.copy(widthPx = 87),
            base.copy(availableHeightPx = 80f),
            base.copy(maxSp = 14f),
            base.copy(minSp = 8f),
            base.copy(maxLines = 2),
            base.copy(density = 3f),
            base.copy(fontScale = 1.3f),
            base.copy(style = TextStyle(fontWeight = FontWeight.Bold)),
            base.copy(locale = "de-DE"),
            base.copy(hyphenator = Any()),
        )
        var computes = 0
        cache.getOrCompute(base) { computes++; fit(12f) }
        for (other in others) cache.getOrCompute(other) { computes++; fit(12f) }
        assertEquals("each is a key of its own", others.size + 1, computes)
        assertEquals(others.size + 1, cache.size)
    }
}
