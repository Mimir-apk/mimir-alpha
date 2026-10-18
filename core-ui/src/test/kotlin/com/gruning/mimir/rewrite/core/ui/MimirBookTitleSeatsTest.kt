package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-11 (vqa11), P1-1: a coverless book's title broke inside a word with no hyphen ("Earthbo" / "und" on the Tome preview at font 1.0, "Earthboun" / "d" on the shelf at 1.3).
 *
 * The cause was not [mimirFitBookTitle]: it only runs for a book asked to [MediaArtBookCover]'s `fitTitle`, and the shelf's no-art painter ([MimirLedgeCoverArt], through the
 * no-art glyph) and the Tome folio's cell never asked. Their title took the book's one size in at most five lines and the text engine split the word where it ran out of room.
 *
 * Every book painter in core-ui now sets its title to the book. This class draws the shelf's seat (the ledge painter with no art, at the Library's resting side and at Home's) and
 * the Tome preview's seat (the book in a 64 x 85 dp cell, with the platform code as its eyebrow) at font scale 1.0 and 1.3, for three titles: "Earthbound" and "Chrono Trigger"
 * from the sweep, and a longer single word. The host ships no hyphenation patterns, so the book is given an engine that hyphenates where the host cannot
 * ([LocalMimirBookTitleHyphenator], as [MimirBookTitleDrawnTest] does): "Earth-" / "bound" shows where it must. Every drawn layout is read back through the title node's own
 * text-layout action and checked: no line ends inside a word unless it ends at a soft hyphen. The frames are written to `build/test-captures/sweepfix32/` for visual review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w897dp-h505dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirBookTitleSeatsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shy = MimirSoftHyphen.toString()

    /** Where the hyphenator would break each word: offsets into the word. */
    private val points = mapOf(
        "Earthbound" to listOf(5),
        "Counterintelligence" to listOf(4, 7, 9, 12, 14),
    )

    private val hyphenator: MimirBookTitleHyphenator = { measurer, title, base, widthPx ->
        mimirBookTitleHyphenation(
            measurer,
            title,
            base,
            widthPx,
            probeLayout = { word, style, px ->
                val text = StringBuilder(word)
                for (offset in (points[word] ?: emptyList()).asReversed()) text.insert(offset, MimirSoftHyphen)
                measurer.measure(text = AnnotatedString(text.toString()), style = style, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = px))
            },
        )
    }

    @Before
    fun forgetEveryDecidedFit() {
        MimirBookTitleFits.clear()
    }

    private enum class Seat(val label: String, val bookWidthDp: Float) {
        /** The Library's shelf at Main: a 161 dp seat, a book 121 dp wide. */
        LIBRARY_SHELF("library-shelf", 121f),

        /** Home's shelf: the hero seat, a book 139 dp wide. */
        HOME_SHELF("home-shelf", 139f),

        /** The Tome preview's cell: 85 dp square, a book 64 dp wide, the platform code as its eyebrow. */
        TOME_PREVIEW("tome-preview", 64f),
    }

    @Composable
    private fun SeatBook(seat: Seat, title: String, fitTitle: Boolean = true) {
        val side = (seat.bookWidthDp * 4f / 3f).dp
        when (seat) {
            Seat.TOME_PREVIEW -> Box(Modifier.requiredSize(side, side).testTag(SeatTag)) {
                MediaArtBookCover(title = title, platformIdValue = "snes", platformLabel = "SNES", fitTitle = fitTitle)
            }
            else -> Box(Modifier.requiredSize(side, side).testTag(SeatTag)) {
                if (fitTitle) {
                    MimirLedgeCoverArt(model = null, title = title, focused = false, caretProgress = 0f, modifier = Modifier.size(side))
                } else {
                    MediaArtBookCover(title = title, platformIdValue = null, fitTitle = false)
                }
            }
        }
    }

    private fun show(fontScale: Float, seats: List<Seat>, titles: List<String>, fitTitle: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, fontScale),
                LocalMimirBookTitleHyphenator provides hyphenator,
            ) {
                MimirTheme {
                    Column(
                        Modifier.background(LocalMimirPalette.current.surfaceBase).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (seat in seats) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (title in titles) SeatBook(seat, title, fitTitle)
                            }
                        }
                    }
                }
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
    }

    private fun drawn(index: Int): TextLayoutResult {
        val node = compose.onAllNodesWithTag(MediaArtBookTitleTag, useUnmergedTree = true)[index].fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("the title exposes its layout", node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    private fun lines(layout: TextLayoutResult) =
        (0 until layout.lineCount).map { layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(shy, "<->") }

    /** No line but the last ends inside a word unless it ends at a soft hyphen (which the engine draws as a hyphen). */
    private fun assertNoCutInsideAWord(layout: TextLayoutResult, what: String) {
        val cuts = mimirBookTitleCutLines(layout)
        assertTrue("$what: a line ends inside a word with no hyphen: ${lines(layout)}", cuts.all { mimirBookTitleEndsAtSoftHyphen(layout, it) })
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/test-captures/sweepfix32").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SWEEPFIX32 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: $out", out.isFile && out.length() > 1_000L)
    }

    private val titles = listOf("Earthbound", "Chrono Trigger", "Counterintelligence")

    /**
     * The title stands above the embossed Mannaz: the fit measures the room under the tooled rule down to the rune's top edge, so the drawn title must end there or
     * above. The book is the seat's 3:4, as tall as the seat; the rune's top is the book's own formula (`0.84 h` less half the rune's height, with 2 dp of air).
     */
    private fun assertTitleClearsTheRune(index: Int, seat: Seat, what: String) {
        val seatBounds = compose.onAllNodesWithTag(SeatTag, useUnmergedTree = true)[index].fetchSemanticsNode().boundsInWindow
        val title = compose.onAllNodesWithTag(MediaArtBookTitleTag, useUnmergedTree = true)[index].fetchSemanticsNode().boundsInWindow
        val density = 2f
        val bookHeightPx = seatBounds.height
        val bookWidthPx = seat.bookWidthDp * density
        val runeTop = seatBounds.top + bookHeightPx * 0.84f - bookWidthPx * 0.083f - 2f * density
        assertTrue("$what: the title ends at ${title.bottom} px, below where the Mannaz begins ($runeTop px)", title.bottom <= runeTop + 1f)
    }

    private fun everySeatAt(fontScale: Float, name: String) {
        val seats = Seat.values().toList()
        show(fontScale, seats, titles)
        var index = 0
        for (seat in seats) {
            for (title in titles) {
                val layout = drawn(index++)
                val what = "${seat.label} @${fontScale}x '$title'"
                println("BOOKSEAT $what lines=${lines(layout)} size=${layout.layoutInput.style.fontSize} maxLines=${layout.layoutInput.maxLines}")
                assertNoCutInsideAWord(layout, what)
                assertTitleClearsTheRune(index - 1, seat, what)
                assertEquals("$what: the title is the one asked for", title, layout.layoutInput.text.text.replace(shy, ""))
            }
        }
        save(name)
    }

    private companion object {
        const val SeatTag = "sweepfix32-seat"
    }

    @Test
    fun `no book seat breaks a word inside itself at font scale 1_0`() = everySeatAt(1.0f, "book-titles-1.0.png")

    @Test
    fun `no book seat breaks a word inside itself at font scale 1_3`() = everySeatAt(1.3f, "book-titles-1.3.png")

    /** Earthbound is the fixture title. Where no size holds it whole it reads "Earth-" / "bound" and is never cut inside the word without a hyphen. */
    @Test
    fun `Earthbound on the Tome preview and the shelf at 1_3 stands whole or hyphenated`() {
        show(1.3f, listOf(Seat.TOME_PREVIEW, Seat.LIBRARY_SHELF), listOf("Earthbound"))
        val tome = drawn(0)
        val shelf = drawn(1)
        for ((what, layout) in listOf("tome preview" to tome, "library shelf" to shelf)) {
            val text = layout.layoutInput.text.text
            val whole = lines(layout).size == 1 && text == "Earthbound"
            val hyphenated = text.contains(shy) && mimirBookTitleCutLines(layout).isNotEmpty()
            assertTrue("$what: Earthbound is one whole line or breaks at its syllable: ${lines(layout)}", whole || hyphenated)
        }
    }

    /** The harness sees the defect: the same book asked NOT to fit its title is cut inside the word at the Tome preview's width at 1.0 (the sweep's frame). */
    @Test
    fun `an unfitted book is cut inside Earthbound at the Tome preview, which is what the sweep saw`() {
        show(1.0f, listOf(Seat.TOME_PREVIEW), listOf("Earthbound"), fitTitle = false)
        val layout = drawn(0)
        println("BOOKSEAT unfitted lines=${lines(layout)}")
        assertTrue("an unfitted title is cut inside the word: ${lines(layout)}", mimirBookTitleCutLines(layout).isNotEmpty())
    }

    /** The one formula for where the Mannaz begins, against the Library shelf's measured book (121 x 161 dp: the rune's top read off the drawn frame at 125 dp). */
    @Test
    fun `the Mannaz's top edge is where the book draws it`() {
        assertEquals(125.2f, mimirBookMannazTopDp(120.75f, 161f), 0.3f)
        // A small book: the spine and the fore-edge floors (3 dp and 2 dp) apply under 40 dp, so the rune stands no higher than the same book drawn at its proportions.
        assertTrue(mimirBookMannazTopDp(30f, 40f) in 25f..34f)
        // Never above the middle of the book, never below its bottom, at any size a shelf draws.
        for (w in listOf(40f, 64f, 101f, 121f, 139f, 240f)) {
            val h = w * 4f / 3f
            assertTrue("$w: ${mimirBookMannazTopDp(w, h)}", mimirBookMannazTopDp(w, h) in (h * 0.6f)..(h * 0.84f))
        }
    }

    @Test
    fun `the ledge painter sets its no-art book's title to the book`() {
        show(1.3f, listOf(Seat.LIBRARY_SHELF), listOf("Earthbound"))
        val titleNodes = compose.onAllNodesWithTag(MediaArtBookTitleTag, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, titleNodes.size)
        // A fitted title is drawn at the size the fit chose, in at most three lines (an unfitted one is five).
        assertTrue("a fitted title has at most three lines: ${drawn(0).layoutInput.maxLines}", drawn(0).layoutInput.maxLines <= 3)
    }
}
