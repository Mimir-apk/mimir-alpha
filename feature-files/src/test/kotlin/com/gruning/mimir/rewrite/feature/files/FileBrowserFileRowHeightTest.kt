package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **no ragged rows, at any font scale.** At font 1.3 a tile whose name took a second line was taller than the tile beside it, and the grid's row pitch (107 dp, 98 + 9) was
 * the pitch of a tile that no longer existed. The folio's own rows ([FileTileRow], in the lazy list the folio draws them in, five 94.4 dp cells on the Thor's Main) at fonts 1.0, 1.3, 1.5 and 2.0, with names of
 * different lengths in one row: every tile is as tall as every other, as tall as the grid's pitch says (less the gap), at 1.0 exactly the design's 98 dp, and no tile's text runs into its bottom padding.
 * The heights are read off the pixels (a tile is the run of non-ground pixels through its chip). The PNGs `rows-1.0.png`, `rows-1.3.png`, `rows-1.5.png`, `rows-2.0.png` and `rows-1.3-focused.png`
 * land in `MIMIR_RENDER_DIR` (default `build/test-captures/tfp`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserFileRowHeightTest {

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

    /** The content column of the folio on this screen: the surface less its padding, the gap, the inspector and the list's end padding ([fileBrowserSolveGrid]). */
    private val contentWidthDp = Math.round(viewportWidthDp) - FileBrowserContentRowHorizontalPaddingDp - FileBrowserContentRowGapDp - FileBrowserInspectorWidthDp - FileBrowserContentListEndPaddingDp

    private fun file(name: String, kind: FileBrowserTileKind = FileBrowserTileKind.FILE, size: Long = 3_072L) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", size = size),
        kind = kind,
        machinery = false,
        platformIdValue = null,
    )

    /** Five tiles of five name lengths in one row; a row of short names; a row with an archive among them. */
    private val mixedRow = listOf(file("vqa17-mark.png"), file("holiday_photo_final.jpg"), file("Screenshot_20261013-231455_Mimir.png"), file("Photographs.png"), file("box.png"))
    private val shortRow = listOf(file("a.png"), file("b.png"), file("cover.png"), file("map.png"), file("x.png"))
    private val kindsRow = listOf(file("theme.mp3"), file("snap.mp4"), file("notes.txt"), file("backup.zip", FileBrowserTileKind.ARCHIVE), file("a_much_longer_archive_name.zip", FileBrowserTileKind.ARCHIVE))
    private val rows = listOf(mixedRow, shortRow, kindsRow)

    /** What a handheld's hyphenator knows of the words laid out here (a host JVM ships no hyphenation patterns); the same table as [FileBrowserFileTileFitCaptureTest]. */
    private val handheldBreaks: (String) -> List<Int>? = { word ->
        when (word) {
            "Photographs" -> listOf(5)
            "Screenshot_", "Screenshot" -> listOf(6)
            else -> null
        }
    }

    private val chipWords = setOf("Image", "Music", "Video", "Doc", "ZIP")

    private var tileHeightDp = 0f
    private var groundArgb = 0

    private fun mount(fontScale: Float, focusedPath: String? = null, minHeightOverride: Float? = null, rows: List<List<FileBrowserTile>> = this.rows) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(369f / 160f, fontScale), LocalFileBrowserNameBreaks provides handheldBreaks) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    val tileHeight = rememberFileBrowserFileTileHeightDp()
                    SideEffect {
                        tileHeightDp = tileHeight
                        groundArgb = palette.surfaceBase.toArgb()
                    }
                    val grid = fileBrowserSolveGrid(viewportWidthDp, viewportHeightDp, fileTileHeightDp = tileHeight)
                    Box(Modifier.requiredSize(viewportWidthDp.dp, viewportHeightDp.dp).background(palette.surfaceBase)) {
                        // A LazyColumn with the folio's own gap: a tile gets an unbounded height here, as it does on the device.
                        LazyColumn(
                            Modifier.padding(start = 18.dp).width(contentWidthDp.dp),
                            verticalArrangement = Arrangement.spacedBy(FileBrowserRowGapDp.dp),
                            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
                        ) {
                            itemsIndexed(rows) { index, row ->
                                FileTileRow(
                                    row = row,
                                    columns = grid.fileColumns,
                                    rowIndex = index,
                                    focusedKey = focusedPath?.let(FileBrowserFocus::tileKey),
                                    authority = authority,
                                    scope = FileBrowserFocus.DefaultScope,
                                    controller = controller,
                                    palette = palette,
                                    tileHeightDp = minHeightOverride ?: (grid.fileRowPitchDp - FileBrowserRowGapDp),
                                )
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
        println("FILEROWS wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
        return bitmap
    }

    private fun chips(): List<SemanticsNode> = compose.onAllNodes(
        SemanticsMatcher("a chip") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text in chipWords } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun metas(): List<SemanticsNode> = compose.onAllNodes(
        SemanticsMatcher("a meta line") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.endsWith("KB") } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun isGround(bitmap: Bitmap, x: Int, y: Int): Boolean {
        val p = bitmap.getPixel(x, y)
        return abs(AndroidColor.red(p) - AndroidColor.red(groundArgb)) <= 4 &&
            abs(AndroidColor.green(p) - AndroidColor.green(groundArgb)) <= 4 &&
            abs(AndroidColor.blue(p) - AndroidColor.blue(groundArgb)) <= 4
    }

    /** The tile through [chip]: the run of pixels that are not the page's ground, going up from just above the chip and down from just below it, down the chip's centre. */
    private fun tileExtentPx(bitmap: Bitmap, chip: SemanticsNode): Pair<Int, Int> {
        val x = chip.boundsInRoot.center.x.toInt()
        var top = chip.boundsInRoot.top.toInt()
        while (top > 0 && !isGround(bitmap, x, top - 1)) top--
        var bottom = chip.boundsInRoot.bottom.toInt()
        while (bottom < bitmap.height - 1 && !isGround(bitmap, x, bottom + 1)) bottom++
        return top to bottom + 1
    }

    private fun heights(bitmap: Bitmap): List<Int> = chips().map { chip -> tileExtentPx(bitmap, chip).let { (top, bottom) -> bottom - top } }

    /** [rowCount] rows of [rows] on show: at font 2.0 a third row would run off the bottom of the viewport, which is the test's frame and not the tile. */
    private fun case(fontScale: Float, rowCount: Int = 3) {
        mount(fontScale, rows = rows.take(rowCount))
        val bitmap = save("rows-$fontScale.png")
        val density = compose.activity.resources.displayMetrics.density
        val chipNodes = chips()
        assertEquals("one chip per tile, $rowCount rows of five", 5 * rowCount, chipNodes.size)
        val heights = heights(bitmap)
        val expectedPx = tileHeightDp * density
        println("FILEROWS font $fontScale tile=${tileHeightDp}dp expected=${expectedPx}px measured=$heights")
        assertTrue("every tile is as tall as the grid's pitch says (${tileHeightDp} dp = $expectedPx px): $heights", heights.all { abs(it - expectedPx) <= 3f })
        assertTrue("no tile is taller or shorter than another, in a row or between rows: $heights", heights.max() - heights.min() <= 2)
        // No row has a tile of its own height: the three rows' tops are one pitch apart.
        val tops = chipNodes.map { tileExtentPx(bitmap, it).first }.distinct().sorted().let { raw -> raw.fold(listOf<Int>()) { acc, y -> if (acc.isNotEmpty() && y - acc.last() <= 2) acc else acc + y } }
        assertEquals("$rowCount rows", rowCount, tops.size)
        assertTrue("the rows are one pitch (${tileHeightDp + FileBrowserRowGapDp} dp) apart: $tops", tops.zipWithNext().all { (a, b) -> abs((b - a) - (tileHeightDp + FileBrowserRowGapDp) * density) <= 3f })
        assertMetaPinnedAndNamesHung(bitmap, chipNodes, density)
    }

    /** The text nodes of a tile: its name is the one that is neither the chip's word nor the meta. */
    private fun names(): List<SemanticsNode> = compose.onAllNodes(
        SemanticsMatcher("a name") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text !in chipWords && !it.text.endsWith("KB") } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    /**
     * the size line is pinned to the bottom of the tile, so every meta in a row sits at one height (its bottom edge, which is its baseline's own offset), however many
     * lines the name takes; the chip and the name stay hung from the top. For every tile: the meta ends the bottom padding above the tile's own bottom; across each row the meta bottoms are one
     * number, and the name tops and chip tops are one number too; the meta clears the name, and the chip.
     */
    private fun assertMetaPinnedAndNamesHung(bitmap: Bitmap, chipNodes: List<SemanticsNode>, density: Float) {
        val metaNodes = metas()
        val nameNodes = names()
        assertEquals("a meta line per tile", chipNodes.size, metaNodes.size)
        class Tile(val top: Int, val bottom: Int, val chip: SemanticsNode, val meta: SemanticsNode, val name: SemanticsNode)
        val tiles = chipNodes.map { chip ->
            val (top, bottom) = tileExtentPx(bitmap, chip)
            val x = chip.boundsInRoot.center.x
            fun inTile(node: SemanticsNode) = node.boundsInRoot.center.y > top && node.boundsInRoot.center.y < bottom && abs(node.boundsInRoot.center.x - x) < 60 * density
            val metaCandidates = metaNodes.filter(::inTile)
            val nameCandidates = nameNodes.filter(::inTile)
            assertEquals("one meta in the tile through the chip at x=$x", 1, metaCandidates.size)
            assertTrue("a name in the tile through the chip at x=$x", nameCandidates.isNotEmpty())
            Tile(top, bottom, chip, metaCandidates.single(), nameCandidates.minBy { it.boundsInRoot.top })
        }
        tiles.forEach { tile ->
            assertEquals("the meta ends the bottom padding above the tile's bottom", tile.bottom.toFloat() - FileBrowserTileBottomPadDp * density, tile.meta.boundsInRoot.bottom, 2f)
            assertTrue("the meta is clear of the name: name ends ${tile.name.boundsInRoot.bottom}, meta starts ${tile.meta.boundsInRoot.top}", tile.meta.boundsInRoot.top >= tile.name.boundsInRoot.bottom - 1f)
            assertTrue("the name is clear of the chip", tile.name.boundsInRoot.top >= tile.chip.boundsInRoot.bottom - 1f)
        }
        // The tiles of one row have one top (to a pixel).
        val rowsOfTiles = tiles.sortedBy { it.top }.fold(listOf<List<Tile>>()) { acc, tile ->
            if (acc.isNotEmpty() && abs(tile.top - acc.last().first().top) <= 2) acc.dropLast(1) + listOf(acc.last() + tile) else acc + listOf(listOf(tile))
        }
        assertEquals("rows", tiles.size / 5, rowsOfTiles.size)
        rowsOfTiles.forEachIndexed { index, row ->
            val metaBottoms = row.map { it.meta.boundsInRoot.bottom }
            val metaTops = row.map { it.meta.boundsInRoot.top }
            val nameTops = row.map { it.name.boundsInRoot.top }
            val chipTops = row.map { it.chip.boundsInRoot.top }
            println("FILEROWS row $index meta bottoms=$metaBottoms name tops=$nameTops chip tops=$chipTops")
            assertTrue("row $index: every meta ends at one height: $metaBottoms", metaBottoms.max() - metaBottoms.min() <= 1f)
            assertTrue("row $index: every meta starts at one height: $metaTops", metaTops.max() - metaTops.min() <= 1f)
            assertTrue("row $index: every name hangs from one height: $nameTops", nameTops.max() - nameTops.min() <= 1f)
            assertTrue("row $index: every chip hangs from one height: $chipTops", chipTops.max() - chipTops.min() <= 1f)
        }
        // Between rows the offset from the tile's top is the same too, so the pitch is the only thing that separates them.
        val offsets = tiles.map { it.meta.boundsInRoot.bottom - it.top }
        assertTrue("the meta is the same distance from every tile's top: $offsets", offsets.max() - offsets.min() <= 1.5f)
    }

    @Test
    fun `at font 1_0 every tile is the design's 98 dp and the pitch is the 107 it always was`() {
        case(1.0f)
        assertEquals(98f, tileHeightDp, 0f)
    }

    @Test
    fun `at font 1_3 a row of mixed names is one height, and the pitch is that height plus the gap`() {
        case(1.3f)
        assertTrue("the tile grew with the type ($tileHeightDp dp)", tileHeightDp > 98f)
    }

    @Test
    fun `at font 1_5 the rows are even too`() = case(1.5f)

    @Test
    fun `at font 2 the rows are even too`() = case(2.0f, rowCount = 2)

    @Test
    fun `a focused tile's ring is drawn round the uniform tile at 1_3`() {
        mount(1.3f, focusedPath = mixedRow[4].path)
        save("rows-1.3-focused.png")
        assertEquals(15, chips().size)
    }

    @Test
    fun `the height the grid is given is the height of a tile whose name takes two lines`() {
        // No minimum: each tile at the height its own content gives it.
        mount(1.3f, minHeightOverride = 0f, rows = listOf(listOf(file("holiday_photo_final.jpg"), file("box.png"))))
        val bitmap = save("rows-1.3-natural.png")
        val density = compose.activity.resources.displayMetrics.density
        val (twoLines, oneLine) = heights(bitmap)
        println("FILEROWS natural at 1.3: two-line tile=$twoLines px, one-line tile=$oneLine px, grid tile=${tileHeightDp * density} px")
        assertTrue("the grid's tile height (${tileHeightDp * density} px) is the two-line tile's ($twoLines px)", abs(twoLines - tileHeightDp * density) <= 2f)
        assertTrue("and the one-line tile is shorter ($oneLine px), which is what the minimum evens out", oneLine < twoLines)
    }

    @Test
    fun `the tile height scales with the font and never goes under the design's 98 dp`() {
        val scales = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 2.0f)
        val seen = FloatArray(scales.size)
        // One composition, one density per scale: the function is pure over a measurer and a density.
        compose.setContent {
            scales.forEachIndexed { index, scale ->
                CompositionLocalProvider(LocalDensity provides Density(369f / 160f, scale)) {
                    MimirTheme {
                        val measured = rememberFileBrowserFileTileHeightDp()
                        SideEffect { seen[index] = measured }
                    }
                }
            }
        }
        compose.waitForIdle()
        println("FILEROWS tile height by font scale ${scales.zip(seen.toList())}")
        assertTrue("never under 98: ${seen.toList()}", seen.all { it >= 98f })
        assertEquals("98 at the authored font and below", 98f, seen[1], 0f)
        assertEquals(98f, seen[0], 0f)
        assertTrue("it grows with the type: ${seen.toList()}", seen[2] <= seen[3] && seen[3] < seen[4] && seen[4] < seen[5])
        assertTrue("and is taller than the design from 1.3 up: ${seen.toList()}", seen[3] > 98f)
    }
}
