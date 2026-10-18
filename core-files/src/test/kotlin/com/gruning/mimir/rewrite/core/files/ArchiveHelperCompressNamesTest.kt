package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **compress can lose another job's file.**
 *
 * `createArchive` moved whatever stood at the output name to a backup and deleted the backup once the new archive
 * landed, and it never asked whether a delete still held that name for Undo -- the check move, copy, rename and
 * extract all make. It now refuses both before it writes anything, the way every other job does.
 */
class ArchiveHelperCompressNamesTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-compress-names").also { roots.add(it) }.toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private val operations = ManagedFileOperations()

    private fun stagingNames(folder: File): Set<String> =
        File(folder, FileOperationStaging.ROOT_DIRECTORY).list().orEmpty().toSet()

    @Test
    fun `compress refuses a name a delete holds for Undo and writes nothing, then succeeds once it is released`() {
        val folder = tempRoot()
        val source = folder.file("notes.txt", "SOURCE")
        val held = folder.file("x.zip", "PLAYER")
        val deletion = runBlocking { operations.stageDelete(listOf(held.path)) }.getOrThrow()
        val stagingBefore = stagingNames(folder)
        try {
            val refused = runBlocking { ArchiveHelper().createArchive(held.path, listOf(source.path)) }

            assertTrue("compress onto a held name is refused: $refused", refused.isFailure)
            assertEquals(
                "with the sentence every other job gives",
                ManagedFileOperations.NAME_WAITING_ON_UNDO,
                FileFailureReasons.describe(refused.exceptionOrNull()!!),
            )
            assertFalse("and nothing stands where Undo will restore the player's file", held.exists())
            assertTrue("and the delete is still held, so Undo is still there", operations.isHeld(deletion))
            assertEquals("no compress staging was created beside it", stagingBefore, stagingNames(folder))
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }

        val released = runBlocking { ArchiveHelper().createArchive(held.path, listOf(source.path)) }
        assertTrue("the control: once the delete is final the name is free and compress lands: $released", released.isSuccess)
        assertTrue(held.isFile)
    }

    @Test
    fun `compress raises a conflict for a file already at the output name and leaves it untouched`() {
        val folder = tempRoot()
        val source = folder.file("notes.txt", "SOURCE")
        val existing = folder.file("x.zip", "ANOTHER JOB'S OUTPUT")

        val result = runBlocking { ArchiveHelper().createArchive(existing.path, listOf(source.path)) }

        val error = result.exceptionOrNull()
        assertTrue("a collision is a conflict, not a replace: $result", error is FileOperationConflictException)
        assertEquals(
            listOf(existing.canonicalPath),
            (error as FileOperationConflictException).conflicts.map { it.targetPath },
        )
        assertEquals("the file is byte for byte what it was", "ANOTHER JOB'S OUTPUT", existing.readText())
        assertFalse("and nothing was staged", File(folder, FileOperationStaging.ROOT_DIRECTORY).exists())
    }

    @Test
    fun `a file that appears after the check is refused at the commit and survives the rollback sweep`() {
        val folder = tempRoot()
        // The check has passed; now another job's file lands on the output name before the move.
        val racer = folder.file("x.zip", "ANOTHER JOB'S OUTPUT")
        val operationRoot = FileOperationStaging.createOperation(folder, "compress", StagingRecoveryPolicy.ROLLBACK)
        val staged = File(operationRoot, "payload/x.zip.part").apply {
            parentFile.mkdirs()
            writeText("NEW ARCHIVE")
        }

        val thrown = runCatching { ArchiveHelper().commitStagedFile(staged, racer, operationRoot) }.exceptionOrNull()

        assertTrue("the move refuses an existing target instead of replacing it: $thrown", thrown is FileOperationConflictException)
        assertEquals("nothing was backed up and deleted", "ANOTHER JOB'S OUTPUT", racer.readText())
        // What createArchive runs after any failure. The journal must not still claim the target, or this deletes it.
        FileOperationStaging.recoverOrphaned(folder, ManagedFileOperations.heldRootPaths())
        assertEquals("and the sweep that follows leaves it alone", "ANOTHER JOB'S OUTPUT", racer.readText())
        assertFalse("the staged archive is what the sweep removes", staged.exists())
    }
}
