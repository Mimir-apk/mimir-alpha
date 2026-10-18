package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MediaArtBookTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.io.File
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
 * Sweep 10-11 (vqa11), P1-1 and P1-2, on the real [LibraryGameShelf] at the Thor's Main (833 dp wide) and the RP5's 700 x 350 dp, at font scale 1.0 and 1.3: every game is a
 * coverless book.
 *
 *  - P1-1: no book's title breaks inside a word. The drawn title's own layout is read back and no line may end inside a word (unless it ends at a soft hyphen, which the
 *    engine draws as a hyphen); "Earthboun" / "d" was the sweep's frame. The host ships no hyphenation patterns, so a word no size holds whole ends in the ellipsis here and
 *    would be hyphenated on a handheld ([MimirBookTitleSeatsTest] pins the hyphen with an engine that has the patterns).
 *  - P1-2: the favourite plate and the disc badge ride the book's bottom edge (bottom-edge placement), and never intersect the title's text bounds. The plate hid "hrono", "arthbound"
 *    and "tris" when it sat top-leading. They also stay inside the book. Measured at those seats (badge bottom-trailing), the "2 discs" badge stood over the embossed
 *    Mannaz on every book at every size here, and over the favourite plate on the RP5's 100 dp book at 1.3; the badge therefore stands on the Mannaz's top edge
 *    ([mimirBookMannazTopDp], or the favourite plate's top where that stands higher: 30 dp up the RP5's 101 dp book against the plate's 32), and no mark covers the other mark or
 *    the title.
 *  - One ᛗ per book : a favourite wears the plate and no embossed Mannaz; a book that is not a favourite wears the emboss and no plate. Read from the pixels of the
 *    frame the shelf drew, in the plate's box and in the Mannaz's, and the badge never covers the emboss where it is drawn.
 *
 * The frames are written to `build/test-captures/sweepfix32/` for visual inspection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryBookSeatsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val density = 2f
    private val shy = '\u00AD'

    private fun game(index: Int, title: String, favorite: Boolean, discs: Int = 0) = GameRow(
        gameId = GameId("g$index"),
        title = title,
        platformId = PlatformId.SNES,
        memberCount = if (discs > 0) discs else 1,
        duplicateKind = if (discs > 0) DuplicateGroupKind.MULTI_DISC else null,
        discCount = discs,
        isFavorite = favorite,
        artResolutionSettled = true,
    )

    /** The sweep's three favourites (Chrono Trigger, Earthbound, Tetris) and a longer single word, one of them a two-disc game so the badge stands too. */
    private val games = listOf(
        game(0, "Chrono Trigger", favorite = true, discs = 2),
        game(1, "Earthbound", favorite = true),
        game(2, "Counterintelligence", favorite = false, discs = 3),
        game(3, "Tetris", favorite = true),
    )

    @After
    fun restoreAnimators() = setCarouselAnimatorScale(1f)

    private class Viewport(val label: String, val widthDp: Float, val screenHeightDp: Float, val bodyHeightDp: Float)

    private val main = Viewport("main", 833f, 469.6f, 380f)
    private val rp5 = Viewport("rp5", 700f, 350f, 270f)

    private fun mount(viewport: Viewport, fontScale: Float) {
        authority.pushScope(GamelistScope)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(viewport.widthDp, viewport.screenHeightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(viewport.widthDp.dp, viewport.bodyHeightDp.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        Box(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp)) {
                            val focus by authority.state.collectAsState()
                            LibraryGameShelf(
                                games = games,
                                emptyMessage = "Nothing here yet.",
                                isLoading = false,
                                searchQuery = "",
                                authority = authority,
                                focusState = focus,
                                centeredKey = authority.renderedFocusIn(GamelistScope, focus),
                                getArtFocused = false,
                                detailGame = null,
                                metaForGame = { "Meta of ${it.title}" },
                                statusForGame = { null },
                                onOpenGame = {},
                                mediaContrastRail = false,
                                bottomControlInset = 60.dp,
                                windowMemoryKey = "book-seats-${viewport.label}-$fontScale",
                                departureFrame = null,
                                shatter = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        compose.touchFrames(3)
        authority.requestFocus(gameKey(games[1]))
        compose.touchFrames(2)
        compose.touchSettle(1_000L)
    }

    private fun tileBounds(index: Int): Rect =
        compose.onNodeTag(libraryShelfTileTestTag(games[index])).boundsInWindow

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeTag(tag: String) =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first()

    private fun inTile(index: Int, tag: String): List<androidx.compose.ui.semantics.SemanticsNode> =
        compose.onAllNodes(
            hasTestTag(tag) and hasAnyAncestor(hasTestTag(libraryShelfTileTestTag(games[index]))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()

    private fun titleLayout(index: Int): TextLayoutResult {
        val node = inTile(index, MediaArtBookTitleTag).single()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("the title exposes its layout", node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    private fun lines(layout: TextLayoutResult) =
        (0 until layout.lineCount).map { layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).replace(shy.toString(), "<->") }

    /** No line but the last ends inside a word: either neighbour of a line end is whitespace, the line ends in a hyphen, dash or slash, or in a soft hyphen. */
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

    /** The book in a tile: the 3:4 of the tile's height, standing on its bottom edge, centred. */
    private fun bookRect(tile: Rect): Rect {
        val width = tile.height * 3f / 4f
        val left = tile.center.x - width / 2f
        return Rect(left, tile.top, left + width, tile.bottom)
    }

    /** Where the embossed Mannaz stands on a book (`MediaArtBookCover`'s drawing, in the book's own proportions): the box of its four tips. */
    private fun runeRect(book: Rect): Rect {
        val spine = maxOf(book.width * 0.075f, 3f * density)
        val foreEdge = maxOf(book.width * 0.055f, 2f * density)
        val leafWidth = book.width - foreEdge - spine
        val markWidth = leafWidth * 0.18f
        val markHeight = markWidth * 1.06f
        val centreX = book.left + spine + leafWidth / 2f
        val centreY = book.top + book.height * 0.84f
        return Rect(centreX - markWidth / 2f, centreY - markHeight / 2f, centreX + markWidth / 2f, centreY + markHeight / 2f)
    }

    private fun frame(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** How much the pixels of [box] vary in brightness (standard deviation, 0..255): a smooth leather gradient barely does, a drawn glyph on it does a lot. */
    private fun spread(bitmap: Bitmap, box: Rect): Float {
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val luma = 0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)
            sum += luma
            sumSquares += luma * luma
            count++
        }
        val mean = sum / count
        return kotlin.math.sqrt((sumSquares / count - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    private fun save(name: String) {
        val bitmap = frame()
        val dir = File("build/test-captures/sweepfix32").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SWEEPFIX32 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: $out", out.isFile && out.length() > 1_000L)
    }

    private fun check(viewport: Viewport, fontScale: Float) {
        mount(viewport, fontScale)
        val label = "${viewport.label}-${"%.1f".format(fontScale)}"
        val shot = frame()
        games.indices.forEach { index ->
            val tile = tileBounds(index)
            val book = bookRect(tile)
            val what = "$label '${games[index].title}'"
            val layout = titleLayout(index)
            println("BOOKSEAT $what tile=${tile.width / density}x${tile.height / density} dp, title lines=${lines(layout)} size=${layout.layoutInput.style.fontSize}")
            // P1-1: the title is whole words or hyphenated, never cut inside a word.
            assertNoCutInsideAWord(layout, what)
            assertEquals("$what: the title is the game's", games[index].title, layout.layoutInput.text.text.replace(shy.toString(), ""))

            // P1-2: the marks stand on the bottom edge of the book and never touch its title.
            val titleBox = inTile(index, MediaArtBookTitleTag).single().boundsInWindow
            val rune = runeRect(book)
            val marks = buildList {
                if (games[index].isFavorite) inTile(index, GameShelfFavoriteMarkTestTag).singleOrNull()?.let { add("favourite" to it.boundsInWindow) }
                inTile(index, GameShelfCollapseBadgeTestTag).singleOrNull()?.let { add("badge" to it.boundsInWindow) }
            }
            assertTrue("$what: every favourite and every badge is on the tile", (if (games[index].isFavorite) 1 else 0) + (if (games[index].discCount > 0) 1 else 0) == marks.size)
            marks.forEach { (name, box) ->
                println("BOOKSEAT $what $name=${box.left},${box.top},${box.right},${box.bottom} px; title=${titleBox.left},${titleBox.top},${titleBox.right},${titleBox.bottom}; rune=${rune.left},${rune.top},${rune.right},${rune.bottom}; book=${book.left},${book.top},${book.right},${book.bottom}")
                assertTrue("$what: the $name does not cover the title ($box vs $titleBox)", !box.overlaps(titleBox))
                assertTrue("$what: the $name stands on the book's bottom half ($box vs $book)", box.top >= book.top + book.height / 2f - 1f)
                assertTrue("$what: the $name stays inside the book ($box vs $book)", box.left >= book.left - 1f && box.right <= book.right + 1f && box.bottom <= book.bottom + 1f)
                // The badge never covers the emboss where it is drawn; a favourite draws none (below).
                if (!games[index].isFavorite) assertTrue("$what: the $name does not cover the embossed Mannaz ($box vs $rune)", !box.overlaps(rune))
            }
            // One ᛗ per book: the plate's box and the Mannaz's box, read from the pixels the shelf drew.
            val plate = inTile(index, GameShelfFavoriteMarkTestTag).single().boundsInWindow
            val plateInk = spread(shot, plate)
            val embossInk = spread(shot, rune)
            println("BOOKSEAT $what plateInk=$plateInk embossInk=$embossInk favourite=${games[index].isFavorite}")
            if (games[index].isFavorite) {
                assertTrue("$what: a favourite draws its plate (spread $plateInk)", plateInk > DrawnInk)
                assertTrue("$what: a favourite draws no embossed Mannaz (spread $embossInk)", embossInk < BareInk)
            } else {
                assertTrue("$what: a book that is not a favourite draws its embossed Mannaz (spread $embossInk)", embossInk > DrawnInk)
                assertTrue("$what: and no plate (spread $plateInk)", plateInk < BareInk)
            }
            if (marks.size == 2) {
                assertTrue("$what: the favourite plate and the badge do not cover each other (${marks[0].second} vs ${marks[1].second})", !marks[0].second.overlaps(marks[1].second))
                // Stacked clear, not touching: the badge's bottom edge stands the air (4 dp) above the plate's top edge, whichever of the plate and the Mannaz is higher.
                val gap = (marks[0].second.top - marks[1].second.bottom) / density
                println("BOOKSEAT $what badge stands ${gap} dp above the favourite plate (horizontal overlap ${(minOf(marks[0].second.right, marks[1].second.right) - maxOf(marks[0].second.left, marks[1].second.left)) / density} dp)")
                assertTrue("$what: the badge clears the favourite plate by $gap dp", gap >= 3.5f)
            }
        }
        save("shelf-books-$label.png")
    }

    /**
     * Brightness spread above which a box holds a drawn glyph, and below which it is bare leather. Measured on these frames: a drawn plate 52 to 64 and a drawn emboss 41; bare leather
     * 1 to 2, and 9 where the plate's box meets the spine's lower band on Main.
     */
    private val DrawnInk = 25f
    private val BareInk = 15f

    @Test
    fun `the Library shelf's books at Main, font 1_0, break no word and carry their marks on the bottom edge`() = check(main, 1.0f)

    @Test
    fun `the Library shelf's books at Main, font 1_3, break no word and carry their marks on the bottom edge`() = check(main, 1.3f)

    @Test
    fun `the Library shelf's books at the RP5, font 1_0, break no word and carry their marks on the bottom edge`() = check(rp5, 1.0f)

    @Test
    fun `the Library shelf's books at the RP5, font 1_3, break no word and carry their marks on the bottom edge`() = check(rp5, 1.3f)
}
