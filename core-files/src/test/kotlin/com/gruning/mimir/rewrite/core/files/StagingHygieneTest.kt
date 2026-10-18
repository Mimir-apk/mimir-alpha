package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * E2 alpha-cert O1, O2, O4 (2026-10-08): **Mimir's working folder is Mimir's, and it does not outlive the job.**
 *
 *  - O1: every extract left an empty `.mimir-staging` (28 after E2, one inside a library platform folder),
 *    and the Files listing drew it as an ordinary folder.
 *  - O2: a copy INTO `.mimir-staging` was accepted ("Copied from dst to .mimir-staging"), then swept away.
 *  - O4: Keep both named the arriving file `(copy-e33b4f5f40fe)`.
 */
class StagingHygieneTest {
    private val tempRoots = mutableListOf<Path>()

    @After
    fun cleanup() {
        tempRoots.forEach { it.toFile().deleteRecursively() }
        tempRoots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-hygiene").also(tempRoots::add).toFile()

    private fun stagingOf(directory: File) = File(directory, FileOperationStaging.ROOT_DIRECTORY)

    private fun file(directory: File, name: String, content: String = name): File =
        File(directory, name).apply { parentFile.mkdirs(); writeText(content) }

    private fun zip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    // ---- O1: the staging folder does not outlive the job ----

    @Test
    fun `a successful extract leaves no staging folder`() = runBlocking {
        val root = tempRoot()
        val archive = File(root, "ok.zip").also { zip(it, "a.txt" to "A", "dir/b.txt" to "B") }
        val destination = File(root, "ok")

        ArchiveHelper().extract(archive.path, destination.path).getOrThrow()

        assertTrue(File(destination, "dir/b.txt").isFile)
        assertFalse(stagingOf(destination).exists())
    }

    @Test
    fun `a failed extract leaves no staging folder and no empty destination it made`() = runBlocking {
        val root = tempRoot()
        val archive = File(root, "slip.zip").also { zip(it, "ok.txt" to "A", "../../evil.txt" to "E") }
        val destination = File(root, "slip")

        assertTrue(ArchiveHelper().extract(archive.path, destination.path).isFailure)

        assertFalse("no staging folder", stagingOf(destination).exists())
        assertFalse("no empty game-named husk (D-6)", destination.exists())
    }

    @Test
    fun `a failed extract into a folder that already existed leaves that folder exactly as it was`() = runBlocking {
        val root = tempRoot()
        val archive = File(root, "slip.zip").also { zip(it, "ok.txt" to "A", "../../evil.txt" to "E") }
        val destination = File(root, "existing").apply { mkdirs() }
        file(destination, "mine.txt", "mine")

        assertTrue(ArchiveHelper().extract(archive.path, destination.path).isFailure)

        assertEquals(listOf("mine.txt"), destination.list().orEmpty().toList())
    }

    @Test
    fun `copy move and delete each remove the staging folder when they finish`() = runBlocking {
        val root = tempRoot()
        val ops = ManagedFileOperations()
        val source = file(File(root, "src"), "Game.gba", "rom")
        val copyTo = File(root, "copy-to").apply { mkdirs() }
        val moveTo = File(root, "move-to").apply { mkdirs() }

        ops.copy(listOf(source.path), copyTo.path).getOrThrow()
        assertFalse("copy", stagingOf(copyTo).exists())

        ops.move(listOf(source.path), moveTo.path).getOrThrow()
        assertFalse("move", stagingOf(moveTo).exists())

        val moved = File(moveTo, "Game.gba")
        val held = ops.stageDelete(listOf(moved.path)).getOrThrow()
        assertTrue("a held delete keeps its staging, because the player's file is in it", stagingOf(moveTo).isDirectory)
        ops.undoDelete(held).getOrThrow()
        assertFalse("undo", stagingOf(moveTo).exists())
        assertEquals("rom", moved.readText())

        val again = ops.stageDelete(listOf(moved.path)).getOrThrow()
        ops.finalizeDelete(again).getOrThrow()
        assertFalse("keep deleted", stagingOf(moveTo).exists())
        assertFalse(moved.exists())
    }

    @Test
    fun `a failed copy rolls back and removes its staging folder`() = runBlocking {
        val root = tempRoot()
        val destination = File(root, "dst").apply { mkdirs() }
        val missing = File(root, "no-such-source.bin")

        assertTrue(ManagedFileOperations().copy(listOf(missing.path), destination.path).isFailure)

        assertFalse(stagingOf(destination).exists())
    }

    @Test
    fun `an empty staging folder from an older run is tidied by the next sweep, a busy one is not`() {
        val root = tempRoot()
        val idle = File(root, "idle").apply { mkdirs() }
        stagingOf(idle).mkdirs()
        val busy = File(root, "busy").apply { mkdirs() }
        File(stagingOf(busy), ".delete-1/payload").mkdirs()

        FileOperationStaging.recoverOrphaned(idle, retain = emptySet())
        FileOperationStaging.recoverOrphaned(busy, retain = setOf(File(stagingOf(busy), ".delete-1").absolutePath))

        assertFalse(stagingOf(idle).exists())
        assertTrue("a held delete's journal keeps the root alive", stagingOf(busy).isDirectory)
    }

    // ---- O1: Files never lists it; the scan never sees it ----

    @Test
    fun `the Files listing hides Mimir's working folders but nothing else`() = runBlocking {
        val root = tempRoot()
        File(stagingOf(root), ".extract-1927038605654158004").mkdirs()
        File(root, ".extract-1927038605654158004").mkdirs()
        File(root, ".extract-notes").mkdirs()
        File(root, ".hidden-by-player").mkdirs()
        File(root, "saves").mkdirs()
        file(root, "Game.gba")

        val names = LocalFileRepository().listFiles(root.path).first().map { it.name }.sorted()

        assertEquals(listOf(".extract-notes", ".hidden-by-player", "Game.gba", "saves"), names)
    }

    @Test
    fun `a folder's item count does not include the staging folder`() = runBlocking {
        val root = tempRoot()
        val folder = File(root, "three").apply { mkdirs() }
        file(folder, "a.txt")
        file(folder, "b.txt")
        stagingOf(folder).mkdirs()

        val row = LocalFileRepository().listFiles(root.path).first().single { it.name == "three" }

        assertEquals("the screen said '3 items' for a folder with two", 2, row.childCount)
    }

    @Test
    fun `search never walks into staging, even when hidden files are asked for`() = runBlocking {
        val root = tempRoot()
        file(root, "found.txt")
        file(File(stagingOf(root), ".delete-1/payload"), "found.txt")

        val hits = LocalFileRepository().search(root.path, "found", includeHidden = true).toList()

        assertEquals(1, hits.size)
        assertFalse(hits.single().path.contains(FileOperationStaging.ROOT_DIRECTORY))
    }

    @Test
    fun `working folder names are matched exactly, never by prefix`() {
        assertTrue(FileOperationStaging.isWorkingFolderName(".mimir-staging"))
        assertTrue(FileOperationStaging.isWorkingFolderName(".MIMIR-STAGING"))
        assertTrue(FileOperationStaging.isWorkingFolderName(".extract-1927038605654158004"))
        assertTrue(FileOperationStaging.isWorkingFolderName(".delete-12"))
        assertFalse(FileOperationStaging.isWorkingFolderName(".extract-notes"))
        assertFalse(FileOperationStaging.isWorkingFolderName(".extract-"))
        assertFalse(FileOperationStaging.isWorkingFolderName("mimir-staging"))
        assertFalse(FileOperationStaging.isWorkingFolderName(".mimir"))
    }

    // ---- O2: staging is refused as a destination ----

    @Test
    fun `copying or moving into the staging folder is refused and writes nothing`() = runBlocking {
        val root = tempRoot()
        val source = file(File(root, "src"), "Game.gba", "rom")
        val staging = stagingOf(File(root, "dst")).apply { mkdirs() }
        val ops = ManagedFileOperations()

        val copy = ops.copy(listOf(source.path), staging.path)
        val move = ops.move(listOf(source.path), File(staging, ".extract-1").apply { mkdirs() }.path)

        listOf(copy, move).forEach { result ->
            assertTrue(result.isFailure)
            assertEquals(
                "That is Mimir's own working folder, so nothing can be put there.",
                FileFailureReasons.describe(result.exceptionOrNull()!!),
            )
        }
        assertTrue("the source is untouched", source.isFile)
        assertEquals("nothing landed in staging", emptyList<String>(), staging.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun `extracting into the staging folder is refused and writes nothing`() = runBlocking {
        val root = tempRoot()
        val archive = File(root, "ok.zip").also { zip(it, "a.txt" to "A") }
        val staging = stagingOf(File(root, "dst")).apply { mkdirs() }

        val result = ArchiveHelper().extract(archive.path, staging.path)

        assertTrue(result.isFailure)
        assertEquals(
            "That is Mimir's own working folder, so nothing can be put there.",
            FileFailureReasons.describe(result.exceptionOrNull()!!),
        )
        assertTrue(staging.walkTopDown().none { it.isFile })
    }

    // ---- O4: Keep both is named in plain words ----

    @Test
    fun `keep both names are name copy, then name copy 2, and the extension stays last`() {
        assertEquals("Game (copy).gba", ConflictNamePolicy.keepBothName("Game.gba", 1))
        assertEquals("Game (copy 2).gba", ConflictNamePolicy.keepBothName("Game.gba", 2))
        assertEquals("Game (copy 3).gba", ConflictNamePolicy.keepBothName("Game.gba", 3))
        assertEquals("README (copy)", ConflictNamePolicy.keepBothName("README", 1))
        assertEquals(".gitignore (copy)", ConflictNamePolicy.keepBothName(".gitignore", 1))
        assertEquals("a.tar (copy).gz", ConflictNamePolicy.keepBothName("a.tar.gz", 1))
    }

    @Test
    fun `the first free keep both name wins, and an exhausted walk answers null`() {
        val taken = setOf("Game (copy).gba", "Game (copy 2).gba")
        assertEquals("Game (copy 3).gba", ConflictNamePolicy.firstFree("Game.gba") { it in taken })
        assertNull(ConflictNamePolicy.firstFree("Game.gba") { true })
    }

    @Test
    fun `copying the same file in again and again keeps every copy under a plain name`() = runBlocking {
        val root = tempRoot()
        val source = file(File(root, "src"), "Game.gba", "incoming")
        val destination = File(root, "dst").apply { mkdirs() }
        file(destination, "Game.gba", "original")
        val ops = ManagedFileOperations()

        repeat(2) {
            ops.copy(listOf(source.path), destination.path, ConflictResolution.RENAME).getOrThrow()
        }

        assertEquals(
            listOf("Game (copy 2).gba", "Game (copy).gba", "Game.gba"),
            destination.list().orEmpty().filterNot { it.startsWith(".") }.sorted(),
        )
        assertEquals("original", File(destination, "Game.gba").readText())
    }

    @Test
    fun `extract keep both names every colliding entry in plain words, and never reuses a claimed name`() = runBlocking {
        val root = tempRoot()
        val archive = File(root, "bundle.zip").also {
            zip(it, "taken.rom" to "INCOMING", "dir/nested.rom" to "NESTED")
        }
        val destination = File(root, "out").apply { mkdirs() }
        file(destination, "taken.rom", "ORIGINAL")
        // The first free name is already on the card: Keep both must walk past it.
        file(destination, "taken (copy).rom", "ALREADY HERE")
        file(destination, "dir/nested.rom", "ORIGINAL NESTED")

        ArchiveHelper().extract(archive.path, destination.path, conflictResolution = ConflictResolution.RENAME).getOrThrow()

        assertEquals("ORIGINAL", File(destination, "taken.rom").readText())
        assertEquals("ALREADY HERE", File(destination, "taken (copy).rom").readText())
        assertEquals("INCOMING", File(destination, "taken (copy 2).rom").readText())
        assertEquals("NESTED", File(destination, "dir/nested (copy).rom").readText())
        assertFalse(stagingOf(destination).exists())
    }
}
