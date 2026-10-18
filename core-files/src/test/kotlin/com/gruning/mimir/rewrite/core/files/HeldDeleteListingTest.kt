package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix round after the E2 review, 2026-10-08: **a delete held for Undo stays in its folder as "Deleting…".**
 *
 * The listing hides `.mimir-staging`, so the "Deleting…" label the Files cover was drawn for never rendered
 * and the file simply vanished. The row is now built from the held-delete list: the real engine stages a real
 * delete, the real repository lists the folder, and the overlay puts the held row back under its own name.
 */
class HeldDeleteListingTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-held-listing").also { roots.add(it) }.toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun list(folder: File): List<FileItem> =
        runBlocking { LocalFileRepository().listFiles(folder.path).first() }

    @Test
    fun `a file held for Undo is listed in its own folder, under its own name, marked Deleting`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        gb.file("keep.gba", "KEEP")
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()
        try {
            val onDisk = list(gb)
            assertEquals("the listing itself still hides the file and the staging folder", listOf("keep.gba"), onDisk.map { it.name })

            val shown = HeldDeleteListing.overlay(gb.path, onDisk, operations.heldDeletions())

            assertEquals(listOf("keep.gba", "x.sav"), shown.map { it.name }.sorted())
            val held = shown.first { it.name == "x.sav" }
            assertTrue("marked as held", held.isPendingDelete)
            assertEquals("under its original path", File(gb, "x.sav").path, File(held.path).path)
            assertEquals("with the size it had", 4L, held.size)
            assertFalse(held.isDirectory)
            assertEquals("sav", held.extension)
            assertFalse("the file it sits beside is not marked", shown.first { it.name == "keep.gba" }.isPendingDelete)
            assertTrue("no staging folder is ever a row", shown.none { FileOperationStaging.isWorkingFolderName(it.name) })
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `a folder held for Undo is listed as a folder with its item count`() {
        val root = tempRoot()
        val parent = File(root, "roms").apply { mkdirs() }
        File(parent, "Old").apply { mkdirs() }
        parent.file("Old/a.gba", "A")
        parent.file("Old/b.gba", "B")
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(parent, "Old").path)) }.getOrThrow()
        try {
            val shown = HeldDeleteListing.overlay(parent.path, list(parent), operations.heldDeletions())

            val held = shown.single()
            assertEquals("Old", held.name)
            assertTrue(held.isPendingDelete)
            assertTrue("a folder stays a folder", held.isDirectory)
            assertEquals(2, held.childCount)
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `a real file at the held name is never marked Deleting, never covered and never doubled`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()
        try {
            // Something else now has the name: a file renamed or made there after the delete.
            gb.file("x.sav", "A DIFFERENT, REAL FILE")
            val onDisk = list(gb)

            val shown = HeldDeleteListing.overlay(gb.path, onDisk, operations.heldDeletions())

            assertEquals("one row, the real one", 1, shown.size)
            assertFalse("a real file is never marked Deleting", shown.single().isPendingDelete)
            assertEquals(22L, shown.single().size)
            assertEquals("and it is the very item the repository listed", onDisk.single(), shown.single())
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `a stale listing that still has the pre-delete row is left alone, not marked and not doubled`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        val before = list(gb)
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()
        try {
            val shown = HeldDeleteListing.overlay(gb.path, before, operations.heldDeletions())

            assertEquals(before, shown)
            assertFalse(shown.single().isPendingDelete)
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `two deletes of the same name are one row`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        val operations = ManagedFileOperations()
        val first = runBlocking { operations.stageDelete(listOf(gb.file("x.sav", "ONE").path)) }.getOrThrow()
        val second = runBlocking { operations.stageDelete(listOf(gb.file("x.sav", "TWO").path)) }.getOrThrow()
        try {
            val shown = HeldDeleteListing.overlay(gb.path, list(gb), operations.heldDeletions())

            assertEquals("a path is one focus key, so it is one row", 1, shown.size)
            assertTrue(shown.single().isPendingDelete)
        } finally {
            runBlocking { operations.finalizeDelete(first) }
            runBlocking { operations.finalizeDelete(second) }
        }
    }

    @Test
    fun `another folder's listing, and a listing with nothing held, come back untouched`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        val elsewhere = File(root, "elsewhere").apply { mkdirs() }
        elsewhere.file("other.gba", "O")
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()
        try {
            val otherListing = list(elsewhere)
            assertSame(otherListing, HeldDeleteListing.overlay(elsewhere.path, otherListing, operations.heldDeletions()))
            assertSame(otherListing, HeldDeleteListing.overlay(elsewhere.path, otherListing, emptyList()))
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `once the delete is undone or released the row is gone`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        gb.file("y.sav", "SAVE2")
        val operations = ManagedFileOperations()
        val undone = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()
        val released = runBlocking { operations.stageDelete(listOf(File(gb, "y.sav").path)) }.getOrThrow()
        assertEquals(2, HeldDeleteListing.overlay(gb.path, list(gb), operations.heldDeletions()).size)

        runBlocking { operations.undoDelete(undone) }
        runBlocking { operations.finalizeDelete(released) }

        val shown = HeldDeleteListing.overlay(gb.path, list(gb), operations.heldDeletions())
        assertEquals("only what is really there: the restored file", listOf("x.sav"), shown.map { it.name })
        assertFalse(shown.single().isPendingDelete)
    }

    @Test
    fun `a held entry whose payload is gone gets no row`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        val deletion = com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion(
            operationRootPath = File(gb, ".mimir-staging/.delete-1").path,
            entries = listOf(
                com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry(
                    originalPath = File(gb, "ghost.sav").path,
                    stagedPath = File(gb, ".mimir-staging/.delete-1/payload/ghost.sav").path,
                ),
            ),
        )

        val shown = HeldDeleteListing.overlay(gb.path, emptyList(), listOf(deletion))

        assertTrue("there is nothing to show for a payload that is not there", shown.isEmpty())
    }
}
