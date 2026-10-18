package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MediaArtBookTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-11 (vqa11), P1-1 on the Tome preview, the real [HomeTomeFolio] at the Thor's Main (832.5 x 468.3 dp) holding the sweep's three favourites with no art, at font scale
 * 1.0 and 1.3 (density 2, the window's own dp are what count).
 *
 * "Earthbo" / "und" at font 1.0: the cell's book never set its title to the book, so the text engine split the word where it ran out of room. A title is whole words or a syllable
 * hyphen now, never cut inside a word, and it ends above the embossed Mannaz (the fit also measured the platform label above it at 1.4 x its size, not the 24 dp line it stands in, so
 * on a book with a label the title's last line ran onto the rune). The host ships no hyphenation patterns, so a word no size holds whole ends in the ellipsis here and is hyphenated
 * on a handheld ([com.gruning.mimir.rewrite.core.ui.MimirBookTitleSeatsTest] pins the hyphen with an engine that has the patterns).
 *
 * The frames are written to `build/test-captures/sweepfix32/` for the lead to look at. The class runs as API 30 for the drawing: the glass panel cannot be drawn into a software bitmap on the
 * levels where the platform can blur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTomeFolioBookSeatsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f
    private val shy = '\u00AD'

    private val members = listOf(
        HomeTomeMember(itemId = HomeShelfItemId.Emulated(GameId("g1")), title = "Chrono Trigger", platformId = PlatformId.SNES),
        HomeTomeMember(itemId = HomeShelfItemId.Emulated(GameId("g2")), title = "Earthbound", platformId = PlatformId.SNES),
        HomeTomeMember(itemId = HomeShelfItemId.Emulated(GameId("g3")), title = "Tetris", platformId = PlatformId.GB),
    )

    @After
    fun restoreAnimators() = setCarouselAnimatorScale(1f)

    private fun mount(fontScale: Float, focusedIndex: Int = 1, shown: List<HomeTomeMember> = members) {
        val authority = DefaultFocusAuthority().apply { pushScope(HomeTomeFolioScope) }
        setCarouselAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(832.5.dp, 468.3.dp)) {
                        HomeTomeFolio(
                            state = HomeTomeFolioUiState(
                                tome = HomeShelfTome(
                                    tomeId = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000013")),
                                    title = "Home shelf",
                                    previews = emptyList(),
                                    memberCount = shown.size,
                                ),
                                members = shown,
                                focusedIndex = focusedIndex,
                                mode = HomeTomeFolioMode.BROWSE,
                                focusSettled = true,
                            ),
                            authority = authority,
                            onFocusMember = {},
                            onOpenMember = {},
                            onArrangeAction = {},
                            onFirstInteraction = {},
                        )
                    }
                }
            }
        }
        settle()
        authority.requestFocus(shown[focusedIndex].focusKey)
        settle()
    }

    private fun settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
        }
    }

    private fun books(): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(HomeTomeFolioTitleBookTestTag), useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.boundsInWindow.left }

    private fun titleOf(book: SemanticsNode): SemanticsNode =
        compose.onAllNodes(hasTestTag(MediaArtBookTitleTag) and hasAnyAncestor(hasTestTag(HomeTomeFolioTitleBookTestTag)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first { it.boundsInWindow.center.x in book.boundsInWindow.left..book.boundsInWindow.right }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("the title exposes its layout", node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    private fun lines(layout: TextLayoutResult) =
        (0 until layout.lineCount).map { layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(shy.toString(), "<->") }

    private fun assertNoCutInsideAWord(layout: TextLayoutResult, what: String) {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val next = layout.getLineStart(line + 1)
            if (next <= 0 || next >= text.length) continue
            val before = text[next - 1]
            val after = text[next]
            val edge = before.isWhitespace() || after.isWhitespace() || before == shy || before == '-' || before == '\u2010' || before == '\u2013' || before == '\u2014' || before == '/'
            assertTrue("$what: line ${line + 1} ends inside a word with no hyphen: ${lines(layout)}", edge)
        }
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

    private fun check(fontScale: Float) {
        mount(fontScale)
        val label = "%.1f".format(fontScale)
        val shown = books()
        assertEquals("the three games each wear a book", 3, shown.size)
        shown.forEachIndexed { index, book ->
            val title = titleOf(book)
            val layout = layoutOf(title)
            val what = "tome preview @${label}x '${members[index].title}'"
            val box = book.boundsInWindow
            println("BOOKSEAT $what book=${box.width / density}x${box.height / density} dp lines=${lines(layout)} size=${layout.layoutInput.style.fontSize}")
            assertNoCutInsideAWord(layout, what)
            assertEquals("$what: the title is the game's", members[index].title, layout.layoutInput.text.text.replace(shy.toString(), ""))
            // The title ends above where the embossed Mannaz begins (the book's own formula: 0.84 of its height, less half the rune).
            val bookRect = bookBox(box)
            val runeTop = bookRect.top + bookRect.height * 0.84f - bookRect.width * 0.083f - 2f * density
            assertTrue("$what: the title ends at ${title.boundsInWindow.bottom} px, below where the Mannaz begins ($runeTop px)", title.boundsInWindow.bottom <= runeTop + 1f)
        }
        // The ring hugs the book it is on (sweep 10-11: it was the square cell's size, about 30 px of air on each side of a book).
        val ring = ringBox()
        val book = bookBox(shown[1].boundsInWindow)
        println("RINGSEAT tome preview @${label}x ring=${ring.width / density}x${ring.height / density} dp at ${ring.left / density},${ring.top / density}; book=${book.width / density}x${book.height / density} dp at ${book.left / density},${book.top / density}; cell=${shown[1].boundsInWindow.width / density} dp")
        save("tome-preview-$label.png")
        assertRingHugs(ring, book, "tome preview @${label}x ring on the focused book")
    }

    private fun ringBox(): Rect = compose.onNodeWithTag(HomeTomeFolioRingTestTag).fetchSemanticsNode().boundsInWindow

    /** The ring's box is the painted leaf's, to a pixel or two (the 3:4 leaf the square cell centres), not the cell's. */
    private fun assertRingHugs(ring: Rect, subject: Rect, what: String) {
        val slack = 1.5f
        assertTrue("$what: ring left ${ring.left} vs ${subject.left}", kotlin.math.abs(ring.left - subject.left) <= slack)
        assertTrue("$what: ring right ${ring.right} vs ${subject.right}", kotlin.math.abs(ring.right - subject.right) <= slack)
        assertTrue("$what: ring top ${ring.top} vs ${subject.top}", kotlin.math.abs(ring.top - subject.top) <= slack)
        assertTrue("$what: ring bottom ${ring.bottom} vs ${subject.bottom}", kotlin.math.abs(ring.bottom - subject.bottom) <= slack)
    }

    /** The painted book inside its (square) cell: 3:4, as tall as the cell, centred. */
    private fun bookBox(cell: Rect): Rect {
        val width = minOf(cell.width, cell.height * 3f / 4f)
        val height = minOf(cell.height, cell.width * 4f / 3f)
        return Rect(cell.center.x - width / 2f, cell.center.y - height / 2f, cell.center.x + width / 2f, cell.center.y + height / 2f)
    }

    @Test
    fun `the Tome preview's books break no word and keep their title above the Mannaz at font 1_0`() = check(1.0f)

    @Test
    fun `the Tome preview's books break no word and keep their title above the Mannaz at font 1_3`() = check(1.3f)

    @Test
    fun `the ring follows the focus from one book to the next and keeps the leaf's width`() {
        mount(1.0f, focusedIndex = 0)
        val cells = books()
        assertRingHugs(ringBox(), bookBox(cells[0].boundsInWindow), "ring on the first book")
        // The leaf is narrower than the cell by about a quarter of it: 30 px of air each side at the sweep's size is gone.
        assertTrue("the ring is the leaf, not the cell: ${ringBox().width} px against a ${cells[0].boundsInWindow.width} px cell", ringBox().width < cells[0].boundsInWindow.width * 0.8f)
    }

    @Test
    fun `a cell that holds real art keeps a ring as wide as the cell`() {
        val withArt = members.toMutableList().also { it[1] = it[1].copy(artworkUri = "file:///sweepfix32-missing-cover.jpg") }
        mount(1.0f, focusedIndex = 1, shown = withArt)
        val cell = books().first().boundsInWindow
        val ring = ringBox()
        println("RINGSEAT art cell ring=${ring.width / density}x${ring.height / density} dp; cell=${cell.width / density}x${cell.height / density} dp")
        assertTrue("a cell of art is ringed whole: ring ${ring.width} px wide, cell ${cell.width} px", kotlin.math.abs(ring.width - cell.width) <= 1.5f)
        assertTrue("and as tall: ring ${ring.height} px, cell ${cell.height} px", kotlin.math.abs(ring.height - cell.height) <= 1.5f)
        save("tome-preview-art-cell-ring.png")
    }

    @Test
    fun `the ring seat is the leaf for a book and the cell for art, at any cell shape`() {
        val square = homeTomeFolioRingSeat(cellWidthDp = 100f, cellHeightDp = 100f, bookInside = true)
        assertEquals(75f, square.widthDp, 0.001f)
        assertEquals(com.gruning.mimir.rewrite.core.ui.mimirNorseBookCoverCorner(75f).value, square.cornerDp, 0.001f)
        // A cell narrower than its leaf is as wide as the cell, the book's own rule (aspectRatio, matchHeightConstraintsFirst).
        assertEquals(60f, homeTomeFolioRingSeat(cellWidthDp = 60f, cellHeightDp = 100f, bookInside = true).widthDp, 0.001f)
        val art = homeTomeFolioRingSeat(cellWidthDp = 100f, cellHeightDp = 100f, bookInside = false)
        assertEquals(100f, art.widthDp, 0.001f)
        assertEquals(com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner.value, art.cornerDp, 0.001f)
    }
}
