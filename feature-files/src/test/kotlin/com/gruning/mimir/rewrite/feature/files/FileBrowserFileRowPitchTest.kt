package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's file row pitch follows the tile ([fileBrowserFileTileHeightDp]), which grows with the font scale (no ragged rows at font 1.3). The page stride, the scroll
 * estimate and the pitch list all read [FileBrowserGrid.fileRowPitchDp], so they follow the rows that are really drawn; at the authored font the numbers are the ones they always were.
 */
class FileBrowserFileRowPitchTest {

    private fun file(name: String) = FileBrowserTile(FileItem(name = name, path = "/root/$name", size = 3_072L), FileBrowserTileKind.FILE, machinery = false, platformIdValue = null)

    private fun folder(name: String) =
        FileBrowserTile(FileItem(name = name, path = "/root/$name", isDirectory = true, childCount = 1), FileBrowserTileKind.DIRECTORY, machinery = false, platformIdValue = null)

    private val partition = FileBrowserPartition(tomes = listOf(folder("a"), folder("b")), files = (1..10).map { file("f$it.png") })

    @Test
    fun `at the authored font the file row pitch is the 98 plus 9 it always was`() {
        val grid = fileBrowserSolveGrid(833f, 468f)
        assertEquals(FileBrowserFileRowPitchDp, grid.fileRowPitchDp, 0.001f)
        assertEquals(98f + 9f, grid.fileRowPitchDp, 0.001f)
        assertEquals(FileBrowserGrid.Authored, grid)
    }

    @Test
    fun `a taller tile gives a taller file row pitch and changes nothing else about the grid`() {
        val authored = fileBrowserSolveGrid(833f, 468f)
        val tall = fileBrowserSolveGrid(833f, 468f, fileTileHeightDp = 113.2f)
        assertEquals(113.2f + FileBrowserRowGapDp, tall.fileRowPitchDp, 0.001f)
        assertEquals(authored.copy(fileRowPitchDp = tall.fileRowPitchDp), tall)
    }

    @Test
    fun `the pitch list, the scroll estimate and the page stride all follow the tile height`() {
        val tall = fileBrowserSolveGrid(833f, 468f, fileTileHeightDp = 113.2f)
        val authored = fileBrowserSolveGrid(833f, 468f)

        val pitches = fileBrowserRowPitchesDp(partition, hasCommitRow = false, grid = tall)
        assertEquals("one pitch per focus row: two tomes, then two rows of five files", 1 + 2, pitches.size)
        assertEquals(tall.tomeRowPitchDp, pitches[0], 0.001f)
        assertEquals(113.2f + 9f, pitches[1], 0.001f)
        assertEquals(113.2f + 9f, pitches[2], 0.001f)

        val focusedFile = FileBrowserFocus.tileKey(partition.files[7].path)
        assertEquals("the reveal's estimate of a row is the tile height, not 98", 113.2f, fileBrowserFocusRowHeightDp(partition, focusedFile, null, tall)!!, 0.001f)
        assertEquals(98f, fileBrowserFocusRowHeightDp(partition, focusedFile, null, authored)!!, 0.001f)

        val rows = List(10) { tall.fileRowPitchDp }
        val authoredRows = List(10) { authored.fileRowPitchDp }
        assertEquals("three 107 dp rows fit one screen", 3, fileBrowserPageStride(authoredRows, 0, forward = true, viewportDp = authored.contentViewportDp))
        assertEquals("only two 122.2 dp rows do", 2, fileBrowserPageStride(rows, 0, forward = true, viewportDp = tall.contentViewportDp))
    }

    @Test
    fun `the surface solves the grid with the tile height and hands each row the pitch less the gap`() {
        val surface = listOf(
            File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
        ).first(File::isFile).readText()
        assertTrue(surface.contains("fileBrowserSolveGrid(maxWidth.value, maxHeight.value, fileTileHeightDp = tileHeightDp)"))
        assertTrue(surface.contains("grid.fileRowPitchDp - FileBrowserRowGapDp)"))
        assertTrue("the tile's minimum height is the row's, not a literal", surface.contains(".defaultMinSize(minHeight = minHeightDp.dp)"))
        assertTrue("no tile height literal is left in the surface", !surface.contains("defaultMinSize(minHeight = 98.dp)"))
    }
}
