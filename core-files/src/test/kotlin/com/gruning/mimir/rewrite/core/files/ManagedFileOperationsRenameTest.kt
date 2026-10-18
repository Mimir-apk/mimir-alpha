package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Rename**, in the engine.
 *
 * Real temp folders, real collisions, real bytes. The assertions are about which file holds which content
 * under which name afterwards, and about what a rename leaves behind (nothing: no staging folder).
 */
class ManagedFileOperationsRenameTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-rename-test").also { roots.add(it) }.toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun rename(
        source: File,
        newName: String,
        resolution: ConflictResolution = ConflictResolution.ASK,
    ): Result<String?> = runBlocking { ManagedFileOperations().rename(source.path, newName, resolution) }

    private fun names(folder: File): List<String> = folder.list().orEmpty().sorted()

    @Test
    fun `a file is renamed in place, keeping its bytes, and nothing is left behind`() {
        val root = tempRoot()
        val source = root.file("Mario.gba", "mario bytes")

        val result = rename(source, "Super Mario.gba")

        assertEquals(File(root, "Super Mario.gba").path, result.getOrThrow())
        assertEquals(listOf("Super Mario.gba"), names(root))
        assertEquals("mario bytes", File(root, "Super Mario.gba").readText())
        assertFalse("no .mimir-staging folder survives a finished rename", File(root, ".mimir-staging").exists())
    }

    @Test
    fun `a folder is renamed with everything in it`() {
        val root = tempRoot()
        val folder = File(root, "Roms").apply { mkdirs() }
        folder.file("a.gba", "A")
        folder.file("deep/b.gba", "B")

        val result = rename(folder, "Games")

        assertEquals(File(root, "Games").path, result.getOrThrow())
        assertEquals(listOf("Games"), names(root))
        assertEquals("A", File(root, "Games/a.gba").readText())
        assertEquals("B", File(root, "Games/deep/b.gba").readText())
    }

    @Test
    fun `the name is tidied and checked by the same policy the keyboard uses`() {
        val root = tempRoot()
        val source = root.file("Mario.gba", "m")

        assertEquals(File(root, "Tidy.gba").path, rename(source, "  Tidy.gba  ").getOrThrow())

        val tidy = File(root, "Tidy.gba")
        val refused = listOf("", "a/b", "..", "Bad:Name", "CON", "Trailing.", "Tidy.gba")
        refused.forEach { bad ->
            val failure = rename(tidy, bad).exceptionOrNull()
            assertTrue("'$bad' should be refused, got $failure", failure is FileRefusedException)
            assertEquals(listOf("Tidy.gba"), names(root))
        }
        assertEquals(
            "the refusal is the keyboard's own sentence",
            RenameProblem.SEPARATOR.sentence,
            FileFailureReasons.describe(rename(tidy, "a/b").exceptionOrNull()!!),
        )
    }

    @Test
    fun `a name already taken is a question, and nothing is written before the answer`() {
        val root = tempRoot()
        val source = root.file("Mario.gba", "mine")
        root.file("Zelda.gba", "theirs")

        val failure = rename(source, "Zelda.gba").exceptionOrNull()

        assertTrue(failure is FileOperationConflictException)
        assertEquals("Zelda.gba", File((failure as FileOperationConflictException).conflicts.single().targetPath).name)
        assertEquals(listOf("Mario.gba", "Zelda.gba"), names(root))
        assertEquals("mine", File(root, "Mario.gba").readText())
        assertEquals("theirs", File(root, "Zelda.gba").readText())
    }

    @Test
    fun `Keep both names the arriving item with a human suffix and touches the incumbent not at all`() {
        val root = tempRoot()
        val source = root.file("Mario.gba", "mine")
        root.file("Zelda.gba", "theirs")
        root.file("Zelda (copy).gba", "already a copy")

        val landed = rename(source, "Zelda.gba", ConflictResolution.RENAME).getOrThrow()

        assertEquals(File(root, "Zelda (copy 2).gba").path, landed)
        assertEquals("mine", File(landed!!).readText())
        assertEquals("theirs", File(root, "Zelda.gba").readText())
        assertEquals("already a copy", File(root, "Zelda (copy).gba").readText())
        assertFalse(File(root, "Mario.gba").exists())
    }

    @Test
    fun `Replace swaps the file in and keeps nothing of the old one`() {
        val root = tempRoot()
        val source = root.file("Mario.gba", "mine")
        root.file("Zelda.gba", "theirs")

        val landed = rename(source, "Zelda.gba", ConflictResolution.OVERWRITE).getOrThrow()

        assertEquals(File(root, "Zelda.gba").path, landed)
        assertEquals(listOf("Zelda.gba"), names(root))
        assertEquals("mine", File(root, "Zelda.gba").readText())
    }

    @Test
    fun `Replace never covers a folder, in either direction`() {
        val root = tempRoot()
        val folder = File(root, "Roms").apply { mkdirs() }.also { it.file("a.gba", "A") }
        val other = File(root, "Games").apply { mkdirs() }.also { it.file("b.gba", "B") }
        val file = root.file("note.txt", "n")

        assertTrue(rename(folder, "Games", ConflictResolution.OVERWRITE).exceptionOrNull() is FileRefusedException)
        assertTrue(rename(file, "Games", ConflictResolution.OVERWRITE).exceptionOrNull() is FileRefusedException)
        assertTrue(rename(other, "note.txt", ConflictResolution.OVERWRITE).exceptionOrNull() is FileRefusedException)

        assertEquals(listOf("Games", "Roms", "note.txt"), names(root))
        assertEquals("B", File(root, "Games/b.gba").readText())
        assertEquals("A", File(root, "Roms/a.gba").readText())
    }

    @Test
    fun `Skip leaves everything alone and answers that nothing was renamed`() {
        val root = tempRoot()
        val source = root.file("Mario.gba", "mine")
        root.file("Zelda.gba", "theirs")

        val result = rename(source, "Zelda.gba", ConflictResolution.SKIP)

        assertNull(result.getOrThrow())
        assertEquals(listOf("Mario.gba", "Zelda.gba"), names(root))
    }

    @Test
    fun `a change of letter case alone is a rename and not a collision`() {
        val root = tempRoot()
        val source = root.file("mario.gba", "m")

        // On a case-insensitive filesystem the new name "exists" (it is the same file); on a case-sensitive
        // one it does not. Both must end with exactly one file under the new spelling.
        val result = rename(source, "Mario.gba")

        assertEquals(File(root, "Mario.gba").path, result.getOrThrow())
        assertEquals(listOf("Mario.gba"), names(root))
        assertEquals("m", File(root, "Mario.gba").readText())
    }

    @Test
    fun `renaming back restores the original name, which is all Undo does`() {
        val root = tempRoot()
        val source = root.file("Mario.gba", "m")

        val renamed = File(rename(source, "Super Mario.gba").getOrThrow()!!)
        val back = rename(renamed, "Mario.gba").getOrThrow()

        assertEquals(File(root, "Mario.gba").path, back)
        assertEquals(listOf("Mario.gba"), names(root))
        assertEquals("m", File(root, "Mario.gba").readText())
    }

    @Test
    fun `a missing source fails with a sentence and changes nothing`() {
        val root = tempRoot()
        val failure = rename(File(root, "gone.gba"), "Other.gba").exceptionOrNull()

        assertTrue(failure != null)
        assertEquals("The file or folder is no longer there.", FileFailureReasons.describe(failure!!))
        assertEquals(emptyList<String>(), names(root))
    }

    @Test
    fun `nothing can be renamed inside Mimir's own working folder`() {
        val root = tempRoot()
        val inside = File(root, FileOperationStaging.ROOT_DIRECTORY).apply { mkdirs() }.file("x.bin", "x")

        val failure = rename(inside, "y.bin").exceptionOrNull()

        assertTrue(failure != null)
        assertEquals("That is Mimir's own working folder, so nothing can be put there.", FileFailureReasons.describe(failure!!))
        assertTrue(inside.exists())
    }
}
