package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Emulator re-look vqa17, 2026-10-13 (P3):** at a 1.3 font the file picker's tile for `vqa17-mark.png` drew an EMPTY type chip, wrapped the name mid-word ("vqa17-ma" / "rk") and cut
 * the meta line to "PNG · 3.0...". The real file tile ([FileTileForCapture]) in the Files grid's own geometry on the Thor's Main (832.8 x 468.3 dp at 369 dpi, five 94.4 dp cells), in the image
 * picker, at the font scales 1.0 and 1.3. The PNGs land in `MIMIR_RENDER_DIR` (default `build/test-captures/tfp`); the pixels are the JVM's, a handheld's are device-owed.
 *
 * What is asserted is read off the drawn nodes: the chip's word is whole and its pill has the height its text needs; no name line ends inside a word; the meta is a whole `EXT . size` or a
 * whole size, never a cut number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserFileTileFitCaptureTest {
    private val english = filesEnglish()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val controller = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.PICK_IMAGE,
        listDirectory = { emptyList() },
        onChoose = {},
        onDismiss = {},
    )

    private val viewportWidthDp = 832.8f
    private val viewportHeightDp = 468.3f
    private val grid = fileBrowserSolveGrid(viewportWidthDp, viewportHeightDp)

    private fun file(name: String, kind: FileBrowserTileKind = FileBrowserTileKind.FILE) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", size = 3_072L),
        kind = kind,
        machinery = false,
        platformIdValue = null,
    )

    /** The picker's tiles: a hyphenated name, an underscored one, a camera-style one with three separators, one with none, and a short one. */
    private val names = listOf(
        file("vqa17-mark.png"),
        file("holiday_photo_final.jpg"),
        file("Screenshot_20261013-231455_Mimir.png"),
        file("Photographs.png"),
        file("box.png"),
    )

    /** A name with no separator and no syllable the hyphenator knows: the last resort, one line with an ellipsis. */
    private val unbreakable = file("Screenshotgalleryexport.png")

    /** What a handheld's hyphenator knows of the words this test lays out (a host JVM ships no hyphenation patterns). */
    private val handheldBreaks: (String) -> List<Int>? = { word ->
        when (word) {
            "Photographs" -> listOf(5)
            "Screenshot_", "Screenshot" -> listOf(6)
            else -> null
        }
    }

    /** The type words the chip says, one per kind of card. */
    private val words = listOf(
        file("box.png") to "Image",
        file("theme.mp3") to "Music",
        file("snap.mp4") to "Video",
        file("notes.txt") to "Doc",
        file("backup.zip", FileBrowserTileKind.ARCHIVE) to "ZIP",
    )

    private fun mount(fontScale: Float, rows: List<List<FileBrowserTile>>, breaks: ((String) -> List<Int>?)? = handheldBreaks, focusedPath: String? = null) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, fontScale), LocalFileBrowserNameBreaks provides breaks) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    // The folio's own tile height for this font scale: every tile as tall as the tallest (the grid's row pitch less its gap).
                    val tileHeight = rememberFileBrowserFileTileHeightDp()
                    Box(Modifier.requiredSize(viewportWidthDp.dp, viewportHeightDp.dp).background(palette.surfaceBase)) {
                        // A LazyColumn, as the folio draws its rows: the tile gets an UNBOUNDED height, which is what a weighted child measures differently under.
                        LazyColumn(
                            Modifier.padding(start = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
                        ) {
                            items(rows) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(FileBrowserFileColumnGapDp.dp)) {
                                    row.forEach { tile ->
                                        FileTileForCapture(
                                            tile = tile,
                                            focused = tile.path == focusedPath,
                                            authority = authority,
                                            key = FileBrowserFocus.tileKey(tile.path),
                                            scope = FileBrowserFocus.DefaultScope,
                                            controller = controller,
                                            palette = palette,
                                            minHeightDp = tileHeight,
                                            modifier = Modifier.width(grid.fileCellWidthDp.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private fun save(fileName: String): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/tfp")
        if (!dir.isDirectory) dir.mkdirs()
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("FILETILEFIT wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
        return bitmap
    }

    /** The ZIP chip is an outlined pill: inside its padding the ground is the tile's own (no filled or tinted chips). */
    private fun assertZipChipNotFilled(where: String, bitmap: Bitmap) {
        val density = compose.activity.resources.displayMetrics.density
        val word = nodesWith("ZIP").firstOrNull()?.boundsInRoot ?: error("$where: the ZIP chip is not drawn")
        val y = word.center.y.toInt()
        val inside = bitmap.getPixel((word.left - 5f * density).toInt(), y)
        val outside = bitmap.getPixel((word.left - 16f * density).toInt(), y)
        fun near(a: Int, b: Int) = kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= 8 &&
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= 8 &&
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= 8
        assertTrue("$where: the ZIP chip's inside (#${Integer.toHexString(inside)}) is the tile's own ground (#${Integer.toHexString(outside)}), not a fill", near(inside, outside))
    }

    private fun clean(text: String) = text.replace("\u200B", "").replace("\u00AD", "")

    /** The Text nodes whose characters, zero-width spaces and soft hyphens aside, are [text], or whose spoken name is (a name cut short at a word is still spoken whole). */
    private fun nodesWith(text: String): List<SemanticsNode> = compose.onAllNodes(
        SemanticsMatcher("text is $text") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { clean(it.text) == text } == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == text } == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertTrue("the node reports its layout", layouts.isNotEmpty())
        return layouts.first()
    }

    private fun lines(layout: TextLayoutResult) = (0 until layout.lineCount).map {
        layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim()
    }

    /** The first line (not the last) that ends between two letters of one word: where the text engine split a word. -1 when none does. */
    private fun firstSplitLine(layout: TextLayoutResult): Int {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val next = layout.getLineStart(line + 1)
            if (next <= 0 || next >= text.length) continue
            val before = text[next - 1]
            val after = text[next]
            val boundary = before.isWhitespace() || after.isWhitespace() || before in "-_." || before == '\u200B' || before == '\u00AD'
            if (!boundary) return line
        }
        return -1
    }

    private fun assertChipsWhole(where: String, rows: List<Pair<FileBrowserTile, String>>) {
        for ((_, word) in rows) {
            val node = nodesWith(word).firstOrNull() ?: error("$where: the chip '$word' is not drawn")
            val layout = layoutOf(node)
            println("FILETILEFIT $where chip '$word' text node=${node.boundsInRoot.width}x${node.boundsInRoot.height}px natural=${layout.multiParagraph.maxIntrinsicWidth}x${layout.multiParagraph.height}px")
            assertEquals("$where: the chip says '$word' whole", word, layout.layoutInput.text.text)
            // A pill squeezed by its box hands its text less height than the word needs, and the word is clipped away (the empty chip of the emulator re-look).
            assertTrue("$where: the '$word' chip gives its text its whole height (${node.boundsInRoot.height} of ${layout.multiParagraph.height} px)", node.boundsInRoot.height >= layout.multiParagraph.height - 1f)
            assertTrue("$where: the '$word' chip gives its text its whole width (${node.boundsInRoot.width} of ${layout.multiParagraph.maxIntrinsicWidth} px)", node.boundsInRoot.width >= layout.multiParagraph.maxIntrinsicWidth - 1f)
        }
    }

    private fun assertNameWhole(where: String, tile: FileBrowserTile) {
        val shown = fileBrowserDisplayName(tile)
        val layout = layoutOf(nodesWith(shown).firstOrNull() ?: error("$where: the name '$shown' is not drawn"))
        val text = layout.layoutInput.text.text
        println("FILETILEFIT $where '$shown' -> lines=${lines(layout)} ellipsized=${(0 until layout.lineCount).any(layout::isLineEllipsized)}")
        assertEquals("$where '$shown': no line ends inside a word (${lines(layout)})", -1, firstSplitLine(layout))
        assertTrue("$where '$shown': at most two lines (${lines(layout)})", layout.lineCount <= 2)
        val engineCut = (0 until layout.lineCount).any(layout::isLineEllipsized)
        assertTrue("$where '$shown': nothing is cut off the bottom", !layout.didOverflowHeight || engineCut)
        val drawn = clean(text)
        if (drawn.endsWith("…")) {
            // Cut at a word: what stands is the name up to a place a line may end, and the cut is the name's own separator.
            val head = drawn.dropLast(1)
            val atBoundary = head.length == shown.length || shown[head.length] in "-_. " || shown[head.length - 1].isDigit() != shown[head.length].isDigit() && shown[head.length - 1].isLetterOrDigit() && shown[head.length].isLetterOrDigit()
            assertTrue("$where '$shown': '$drawn' is a whole-word start of the name", shown.startsWith(head) && atBoundary)
        } else if (!engineCut) {
            assertEquals("$where '$shown': the whole name stands", shown, drawn)
        }
    }

    private fun assertMetaWhole(where: String, tile: FileBrowserTile) {
        val whole = fileBrowserTileMeta(english, tile)
        val size = whole.substringAfter(" · ")
        val node = nodesWith(whole).firstOrNull() ?: nodesWith(size).firstOrNull() ?: error("$where: the meta line '$whole' is not drawn whole")
        val layout = layoutOf(node)
        println("FILETILEFIT $where meta '${layout.layoutInput.text.text}' lines=${lines(layout)}")
        // (didOverflowWidth is no test of a wrap-content text: its paragraph is laid out in the room and its node is as wide as the text.)
        assertFalse("$where: the meta '${layout.layoutInput.text.text}' is not cut", (0 until layout.lineCount).any(layout::isLineEllipsized) || layout.didOverflowHeight)
        assertEquals("$where: the meta stands on one line", 1, layout.lineCount)
        assertTrue("$where: the meta (${layout.size.width} px) is inside the room (${layout.multiParagraph.width} px)", layout.size.width <= layout.multiParagraph.width)
        assertTrue("$where: the meta is a whole 'EXT . size' or a whole size, never a number cut midway", layout.layoutInput.text.text == whole || layout.layoutInput.text.text == size)
    }

    private fun case(fontScale: Float) {
        val where = "font $fontScale"
        mount(fontScale, listOf(names, words.map { it.first }, listOf(unbreakable)))
        val bitmap = save("files-tile-$fontScale.png")
        assertChipsWhole(where, words)
        assertZipChipNotFilled(where, bitmap)
        (names + unbreakable).forEach { assertNameWhole(where, it) }
        (names + unbreakable).forEach { assertMetaWhole(where, it) }
        // Each name where the player reads it: hyphenated names at their own separators, a long word at a syllable hyphen, the last resort an ellipsis on one line.
        fun shown(tile: FileBrowserTile) = lines(layoutOf(nodesWith(fileBrowserDisplayName(tile)).first())).map(::clean)
        if (fontScale == 1.0f) {
            assertEquals(listOf("vqa17-mark"), shown(names[0]))
            assertEquals(listOf("holiday_", "photo_final"), shown(names[1]))
            assertEquals(listOf("Photographs"), shown(names[3]))
        } else {
            assertEquals(listOf("vqa17-", "mark"), shown(names[0]))
            val photographs = layoutOf(nodesWith("Photographs").first())
            assertEquals("Photographs breaks at its syllable hyphen, Photo- / graphs", 2, photographs.lineCount)
            assertEquals('\u00AD', photographs.layoutInput.text.text[photographs.getLineEnd(0, visibleEnd = false) - 1])
        }
        assertEquals("the last resort is one line", 1, layoutOf(nodesWith(fileBrowserDisplayName(unbreakable)).first()).lineCount)
    }

    /** Names with digits and no separator (emulator re-look vqa18, 2026-10-14): a letter meeting a digit is a place a line may end, a run of digits is never broken. */
    private val digitNames = listOf(
        file("vqa18screenshotlongname.png"),
        file("IMG20261013WA0001.jpg"),
        file("DSC00123.png"),
        file("20261013.png"),
        file("Level 1st edition.png"),
    )

    /** The first line (not the last) that ends between two digits: where the text engine split a number. -1 when none does. */
    private fun firstSplitNumber(layout: TextLayoutResult): Int {
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val next = layout.getLineStart(line + 1)
            if (next > 0 && next < text.length && text[next - 1].isDigit() && text[next].isDigit()) return line
        }
        return -1
    }

    private fun digitsCase(fontScale: Float) {
        val where = "digits, font $fontScale"
        mount(fontScale, listOf(digitNames))
        save("names-digits-$fontScale.png")
        digitNames.forEach { assertNameWhole(where, it) }
        fun layout(tile: FileBrowserTile) = layoutOf(nodesWith(fileBrowserDisplayName(tile)).first())
        digitNames.forEach { tile ->
            assertEquals("$where '${tile.name}': no number is split between two lines", -1, firstSplitNumber(layout(tile)))
            println("FILETILEFIT $where '${tile.name}' lines=${lines(layout(tile)).map(::clean)} ellipsized=${(0 until layout(tile).lineCount).filter(layout(tile)::isLineEllipsized)}")
        }
        // The long name uses both lines: the stem and its number on the first, the rest, cut by the ellipsis, on the second.
        val long = layout(digitNames[0])
        assertEquals("$where: vqa18screenshotlongname uses both lines", 2, long.lineCount)
        assertEquals(listOf("vqa18"), lines(long).take(1).map(::clean))
        assertTrue("$where: the second line starts the word after the number (${lines(long)})", clean(lines(long)[1]).startsWith("screens"))
        assertTrue("$where: and the second line is the one cut", long.isLineEllipsized(1))
        // A camera name breaks between its letters and its digits and uses both lines when it does not fit on one.
        val camera = layout(digitNames[1])
        assertTrue("$where: IMG20261013WA0001 starts with its letters and their number on the first line (${lines(camera)})", clean(lines(camera)[0]).startsWith("IMG"))
        assertTrue("$where: IMG20261013WA0001 is on two lines at most", camera.lineCount in 1..2)
        // A short name and a bare number are not touched; a number's unit stays with it.
        assertEquals(listOf("DSC00123"), lines(layout(digitNames[2])).map(::clean))
        assertEquals(listOf("20261013"), lines(layout(digitNames[3])).map(::clean))
        val spaced = lines(layout(digitNames[4])).map(::clean)
        assertTrue("$where: '1st' stays whole: $spaced", spaced.none { it.endsWith("1") } && spaced.none { it.startsWith("st") })
    }

    /** Ordinals (emulator re-look vqa19, 2026-10-14): a number and its st / nd / rd / th suffix are one unit; a word that merely starts like one is not. */
    private val ordinalNames = listOf(
        file("Level19th_edition.png"),
        file("IMG20261019.png"),
        file("21st_century_photo.png"),
        file("3rdparty_logo.png"),
        file("Disc2nd_and_3RD.png"),
    )

    /** The first line (not the last) that ends between a number and its ordinal suffix: -1 when none does. */
    private fun firstSplitOrdinal(layout: TextLayoutResult): Int {
        val raw = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            val before = clean(raw.substring(0, end)).trimEnd()
            val after = clean(raw.substring(end))
            val suffix = after.take(2).lowercase()
            if (before.isNotEmpty() && before.last().isDigit() && suffix in listOf("st", "nd", "rd", "th") && (after.length == 2 || !after[2].isLetter())) return line
        }
        return -1
    }

    private fun ordinalsCase(fontScale: Float) {
        val where = "ordinals, font $fontScale"
        mount(fontScale, listOf(ordinalNames))
        save("names-ordinals-$fontScale.png")
        ordinalNames.forEach { assertNameWhole(where, it) }
        fun layout(tile: FileBrowserTile) = layoutOf(nodesWith(fileBrowserDisplayName(tile)).first())
        ordinalNames.forEach { tile ->
            assertEquals("$where '${tile.name}': no ordinal is split from its number", -1, firstSplitOrdinal(layout(tile)))
            assertEquals("$where '${tile.name}': no number is split between two lines", -1, firstSplitNumber(layout(tile)))
            println("FILETILEFIT $where '${tile.name}' lines=${lines(layout(tile)).map(::clean)} ellipsized=${(0 until layout(tile).lineCount).filter(layout(tile)::isLineEllipsized)}")
        }
        // The ordinal stays on one line with its number, wherever the name breaks.
        val level = lines(layout(ordinalNames[0])).map(::clean)
        assertTrue("$where: 'Level19th_edition' keeps 19th whole: $level", level.none { it.endsWith("19") } && level.none { it.startsWith("th") })
        val century = lines(layout(ordinalNames[2])).map(::clean)
        assertTrue("$where: '21st_century_photo' keeps 21st whole: $century", century.none { it.endsWith("21") } && century.none { it.startsWith("st") })
    }

    @Test
    fun `ordinals stay whole at 1x`() = ordinalsCase(1.0f)

    @Test
    fun `ordinals stay whole at 1_3x`() = ordinalsCase(1.3f)

    @Test
    fun `names with digits and no separator use both lines at 1x`() = digitsCase(1.0f)

    @Test
    fun `names with digits and no separator use both lines at 1_3x`() = digitsCase(1.3f)

    @Test
    fun `the file tile at 1x is whole`() = case(1.0f)

    @Test
    fun `the file tile at 1_3x keeps its chip, its name and its meta whole`() = case(1.3f)

    private fun chipsAt(fontScale: Float) {
        mount(fontScale, listOf(names, words.map { it.first }, listOf(unbreakable)))
        save("files-tile-$fontScale.png")
        assertChipsWhole("font $fontScale", words)
    }

    @Test
    fun `on a host with no hyphenator the names still never end a line inside a word`() {
        mount(1.3f, listOf(names, listOf(unbreakable)), breaks = null)
        save("files-tile-1.3-no-hyphenator.png")
        (names + unbreakable).forEach { assertNameWhole("no hyphenator, font 1.3", it) }
    }

    @Test
    fun `a focused tile at 1_3x keeps its chip whole and its ring is drawn round the taller tile`() {
        mount(1.3f, listOf(names), focusedPath = names[0].path)
        save("files-tile-1.3-focused.png")
        assertChipsWhole("focused, font 1.3", listOf(words[0]))
        assertNameWhole("focused, font 1.3", names[0])
    }

    @Test
    fun `the chip keeps its whole word at 1_5x`() = chipsAt(1.5f)

    @Test
    fun `the chip keeps its whole word at 2x`() = chipsAt(2.0f)
}
