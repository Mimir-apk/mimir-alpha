package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [fileBrowserPartition] -- the split, demotion and sort that turns a raw directory listing into
 * the tome/file folio. Also the one place [LocalListingPolicy.isExtractableArchive][
 * com.gruning.mimir.rewrite.core.files.LocalListingPolicy.isExtractableArchive] is consulted, so the
 * zip+7z support is pinned here too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserPartitionTest {
    private val english = filesEnglish()

    private fun dir(name: String) = FileItem(name = name, path = "/root/$name", isDirectory = true)
    private fun file(name: String, extension: String = name.substringAfterLast('.', "")) =
        FileItem(name = name, path = "/root/$name", extension = extension)

    private val noClassifier: (FileItem) -> FileBrowserRecognition = { FileBrowserRecognition.None }

    @Test
    fun `directories land in tomes, everything else lands in files`() {
        val items = listOf(dir("Roms"), file("bundle.zip"), file("readme.txt"))

        val partition = fileBrowserPartition(items, noClassifier)

        assertEquals(listOf("Roms"), partition.tomes.map { it.name })
        assertEquals(listOf("bundle.zip", "readme.txt"), partition.files.map { it.name })
    }

    @Test
    fun `a 7z archive classifies as extractable -- the design mockups stale zip-only gate is not ported`() {
        val partition = fileBrowserPartition(listOf(file("bundle.7z")), noClassifier)

        val tile = partition.files.single()
        assertEquals(FileBrowserTileKind.ARCHIVE, tile.kind)
        assertTrue(tile.extractable)
        assertEquals("7Z", fileBrowserBadgeLabel(english, tile))
    }

    @Test
    fun `a zip archive classifies as extractable`() {
        val tile = fileBrowserPartition(listOf(file("bundle.zip")), noClassifier).files.single()
        assertEquals(FileBrowserTileKind.ARCHIVE, tile.kind)
        assertTrue(tile.extractable)
    }

    @Test
    fun `a rar file is excluded from extraction by licence`() {
        val tile = fileBrowserPartition(listOf(file("bundle.rar")), noClassifier).files.single()
        assertEquals(FileBrowserTileKind.FILE, tile.kind)
        assertFalse(tile.extractable)
    }

    @Test
    fun `a tar-family file is NOT extractable even though ArchiveHelper can still read some of it`() {
        val tile = fileBrowserPartition(listOf(file("saves.tar.gz", extension = "gz")), noClassifier).files.single()
        assertEquals(FileBrowserTileKind.FILE, tile.kind)
        assertFalse(tile.extractable)
    }

    @Test
    fun `each block is sorted case-insensitively by name, independent of listing order`() {
        val items = listOf(dir("zelda"), dir("Alpha"), file("banana.txt"), file("Apple.txt"))

        val partition = fileBrowserPartition(items, noClassifier)

        assertEquals(listOf("Alpha", "zelda"), partition.tomes.map { it.name })
        assertEquals(listOf("Apple.txt", "banana.txt"), partition.files.map { it.name })
    }

    @Test
    fun `machinery folders are demoted -- present in tomes, flagged, with no glyph even if recognised by name`() {
        val classifyBySaves: (FileItem) -> FileBrowserRecognition = { item ->
            if (item.name == "saves") FileBrowserRecognition("gba", gameLike = true, bios = true) else FileBrowserRecognition.None
        }

        val partition = fileBrowserPartition(listOf(dir("saves"), dir("GBA")), classifyBySaves)

        val savesTile = partition.tomes.first { it.name == "saves" }
        assertTrue(savesTile.machinery)
        assertNull("demotion must win over a classifier match by name collision", savesTile.platformIdValue)
        assertFalse("a Mimir folder is never a game", savesTile.gameLike)
        assertFalse("a Mimir folder is never a BIOS", savesTile.bios)
    }

    @Test
    fun `the recognition's game-like and BIOS answers reach the tile, in the listing and in search`() {
        val classifier: (FileItem) -> FileBrowserRecognition = { item ->
            when (item.name) {
                "Disc.iso" -> FileBrowserRecognition(gameLike = true)
                "gba_bios.bin" -> FileBrowserRecognition(platformIdValue = "gba", bios = true)
                else -> FileBrowserRecognition.None
            }
        }
        val items = listOf(file("Disc.iso"), file("gba_bios.bin"), file("notes.txt"))

        val listed = fileBrowserPartition(items, classifier).files.associateBy { it.name }
        val searched = fileBrowserSearchTiles(items, classifyPlatform = classifier).associateBy { it.name }

        listOf(listed, searched).forEach { tiles ->
            assertTrue(tiles.getValue("Disc.iso").gameLike)
            assertFalse(tiles.getValue("Disc.iso").bios)
            assertTrue(tiles.getValue("gba_bios.bin").bios)
            assertEquals("gba", tiles.getValue("gba_bios.bin").platformIdValue)
            assertFalse(tiles.getValue("notes.txt").gameLike)
        }
    }

    @Test
    fun `a non-machinery directory is never demoted`() {
        val tile = fileBrowserPartition(listOf(dir("Roms")), noClassifier).tomes.single()
        assertFalse(tile.machinery)
    }

    @Test
    fun `classifyPlatform recognises a directory as a platform tome`() {
        val classifier: (FileItem) -> FileBrowserRecognition = { item ->
            if (item.name == "GBA") FileBrowserRecognition("game-boy-advance") else FileBrowserRecognition.None
        }

        val tile = fileBrowserPartition(listOf(dir("GBA")), classifier).tomes.single()

        assertEquals("game-boy-advance", tile.platformIdValue)
    }

    @Test
    fun `classifyPlatform recognises a file as game content`() {
        val classifier: (FileItem) -> FileBrowserRecognition = { item ->
            if (item.extension == "gba") FileBrowserRecognition("game-boy-advance") else FileBrowserRecognition.None
        }

        val tile = fileBrowserPartition(listOf(file("Metroid.gba")), classifier).files.single()

        assertEquals("game-boy-advance", tile.platformIdValue)
    }

    @Test
    fun `an empty listing partitions into two empty blocks`() {
        val partition = fileBrowserPartition(emptyList(), noClassifier)
        assertTrue(partition.isEmpty)
        assertEquals(0, partition.totalCount)
    }

    @Test
    fun `orderedTiles concatenates tomes before files, matching render and tab order`() {
        val partition = fileBrowserPartition(listOf(file("z.txt"), dir("a")), noClassifier)
        assertEquals(listOf("a", "z.txt"), partition.orderedTiles().map { it.name })
    }
}
