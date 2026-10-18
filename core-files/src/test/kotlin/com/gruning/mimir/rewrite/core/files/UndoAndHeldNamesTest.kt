package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: **Undo can destroy the file it restores.**
 *
 * Delete `x.gba`, make another file called `x.gba`, press Undo. `atomicMove(replaceExisting = false)` now refuses
 * to overwrite, so the restore failed -- and Undo then deleted the staging folder with the original inside it,
 * while the store dropped the card without reading the result.
 *
 *  - Undo puts the original back under a Keep-both name when its own is taken, and never deletes staging that
 *    still holds a file.
 *  - Nothing new may take a name a held delete owns (rename, copy, move, extract), so this is rare.
 *  - A copy's Replace is decided from the plan, not from what is on disk when the commit starts.
 */
class UndoAndHeldNamesTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-undo-names").also { roots.add(it) }.toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private val operations = ManagedFileOperations()
    private val waiting = "That name is waiting on an Undo. Try again in a moment."
    private val incomplete = "Couldn't put everything back yet. It is still held, so Undo is still there."

    private fun stageDelete(vararg files: File) =
        runBlocking { operations.stageDelete(files.map { it.path }) }.getOrThrow()

    private fun sentenceOf(result: Result<*>): String {
        assertTrue("expected a failure, got $result", result.isFailure)
        return FileFailureReasons.describe(result.exceptionOrNull()!!)
    }

    // ---- (a) Undo restores under a Keep-both name and never discards staging that still holds a file ----

    @Test
    fun `undo puts the original back as a copy when its name was taken, and leaves the other file alone`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        val original = gb.file("x.gba", "ORIGINAL")
        val deletion = stageDelete(original)
        gb.file("x.gba", "SOMETHING ELSE") // a file renamed or made there after the delete

        try {
            val restored = runBlocking { operations.undoDelete(deletion) }

            assertEquals("one item came back", 1, restored.getOrThrow())
            assertEquals("the original is restored under a Keep-both name", "ORIGINAL", File(gb, "x (copy).gba").readText())
            assertEquals("the other file is untouched", "SOMETHING ELSE", File(gb, "x.gba").readText())
            assertFalse("staging is gone once the restore landed", File(gb, ".mimir-staging").exists())
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `an undo that cannot put a file back keeps the delete held and the file in staging`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        val deletion = stageDelete(gb.file("x.gba", "ORIGINAL"))
        gb.file("x.gba", "SOMETHING ELSE")
        for (attempt in 1..ConflictNamePolicy.MAX_ATTEMPTS) {
            gb.file(ConflictNamePolicy.keepBothName("x.gba", attempt), "TAKEN")
        }
        val staged = File(deletion.entries.single().stagedPath)

        try {
            val first = runBlocking { operations.undoDelete(deletion) }

            assertEquals(incomplete, sentenceOf(first))
            assertTrue("the only copy is still in staging", staged.isFile && staged.readText() == "ORIGINAL")
            assertTrue("and the delete is still held, so Undo is still there", operations.isHeld(deletion))

            // Room appears; pressing Undo again finishes the job.
            File(gb, "x (copy 7).gba").delete()
            val second = runBlocking { operations.undoDelete(deletion) }

            assertEquals(1, second.getOrThrow())
            assertEquals("ORIGINAL", File(gb, "x (copy 7).gba").readText())
            assertFalse(operations.isHeld(deletion))
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    // ---- (b) nothing new may take a held name ----

    @Test
    fun `renaming something to a name a held delete owns is refused`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        val deletion = stageDelete(gb.file("x.gba", "ORIGINAL"))
        val other = gb.file("y.gba", "OTHER")
        try {
            val result = runBlocking { operations.rename(other.path, "x.gba") }

            assertEquals(waiting, sentenceOf(result))
            assertEquals("the file kept its name", "OTHER", other.readText())
            assertFalse(File(gb, "x.gba").exists())

            assertEquals("and Undo still brings the original back under its own name", 1, runBlocking { operations.undoDelete(deletion) }.getOrThrow())
            assertEquals("ORIGINAL", File(gb, "x.gba").readText())
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `a held name is free again the moment the delete is released`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        val deletion = stageDelete(gb.file("x.gba", "ORIGINAL"))
        val other = gb.file("y.gba", "OTHER")

        runBlocking { operations.finalizeDelete(deletion) }
        val result = runBlocking { operations.rename(other.path, "x.gba") }

        assertTrue("nothing owns the name any more: $result", result.isSuccess)
        assertEquals("OTHER", File(gb, "x.gba").readText())
    }

    @Test
    fun `copy and move into a folder refuse a name a held delete owns`() {
        val root = tempRoot()
        val to = File(root, "to").apply { mkdirs() }
        val deletion = stageDelete(to.file("x.rom", "HELD"))
        val copySource = File(root, "from").file("x.rom", "COPY ME")
        val moveSource = File(root, "from2").file("x.rom", "MOVE ME")
        try {
            val copied = runBlocking { operations.copy(listOf(copySource.path), to.path) }
            val moved = runBlocking { operations.move(listOf(moveSource.path), to.path) }

            assertEquals(waiting, sentenceOf(copied))
            assertEquals(waiting, sentenceOf(moved))
            assertEquals("the sources are untouched", "COPY ME", copySource.readText())
            assertEquals("MOVE ME", moveSource.readText())
            assertFalse("nothing landed under the held name", File(to, "x.rom").exists())
            assertFalse("and no staging folder of theirs is left behind", File(to, ".mimir-staging").list().orEmpty().any { it.startsWith(".copy") || it.startsWith(".move") })
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `Keep both steps over a held name instead of taking it`() {
        val root = tempRoot()
        val to = File(root, "to").apply { mkdirs() }
        to.file("x.rom", "REAL")
        val deletion = stageDelete(to.file("x (copy).rom", "HELD"))
        val source = File(root, "from").file("x.rom", "INCOMING")
        try {
            val copied = runBlocking { operations.copy(listOf(source.path), to.path, ConflictResolution.RENAME) }

            assertTrue("$copied", copied.isSuccess)
            assertFalse("the held name was not handed out", File(to, "x (copy).rom").exists())
            assertEquals("INCOMING", File(to, "x (copy 2).rom").readText())
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `extracting onto a name a held delete owns is refused and writes nothing`() {
        val root = tempRoot()
        val destination = File(root, "roms").apply { mkdirs() }
        val deletion = stageDelete(destination.file("x.rom", "HELD"))
        val archive = File(root, "bundle.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("x.rom"))
            zip.write("INCOMING".toByteArray())
            zip.closeEntry()
        }
        try {
            val result = runBlocking { ArchiveHelper().extract(archive.path, destination.path) }

            assertEquals(waiting, sentenceOf(result))
            assertFalse("nothing was written under the held name", File(destination, "x.rom").exists())
            assertEquals("and the delete is still undoable", 1, runBlocking { operations.undoDelete(deletion) }.getOrThrow())
            assertEquals("HELD", File(destination, "x.rom").readText())
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    // ---- Replace is the plan's, not the disk's ----

    private fun commit(source: File, target: File, replaceTarget: Boolean): Result<File> {
        val operation = FileOperationStaging.createOperation(target.parentFile, "copy", StagingRecoveryPolicy.ROLLBACK)
        return try {
            Result.success(
                operations.commitStaged(
                    operation, "copy", source.toPath(), target.toPath(), 0L, 0L,
                    { _, _, _ -> }, { _, _, _ -> }, false, false, 0L, {}, replaceTarget,
                ),
            )
        } catch (e: Exception) {
            runCatching { FileOperationStaging.rollbackOperation(operation) }
            Result.failure(e)
        }
    }

    @Test
    fun `a name that was free when the copy was planned is not backed up and replaced when another job took it`() {
        val root = tempRoot()
        val source = File(root, "from").file("x.rom", "MINE")
        val target = File(root, "to").file("x (copy).rom", "THE OTHER JOB")

        val result = commit(source, target, replaceTarget = false)

        assertTrue("the commit must fail rather than take the name: $result", result.isFailure)
        assertEquals("the other job's file is where it was", "THE OTHER JOB", target.readText())
        assertEquals("the source is untouched", "MINE", source.readText())
        assertEquals(listOf("x (copy).rom"), target.parentFile.list().orEmpty().filterNot { it.startsWith(".") })
    }

    @Test
    fun `a planned Replace still replaces`() {
        val root = tempRoot()
        val source = File(root, "from").file("x.rom", "MINE")
        val target = File(root, "to").file("x.rom", "OLD")

        val result = commit(source, target, replaceTarget = true)

        assertTrue("$result", result.isSuccess)
        assertEquals("MINE", target.readText())
        result.getOrNull()?.let { FileOperationStaging.deleteRecursively(it) }
    }
}
