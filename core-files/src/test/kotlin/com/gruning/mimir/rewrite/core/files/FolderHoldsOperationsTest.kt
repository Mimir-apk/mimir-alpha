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
 * **a folder with a delete held for Undo, or a job working,
 * inside it cannot be renamed or moved.**
 *
 * The journal of both lives in `<folder>/.mimir-staging` and is remembered by path. Rename the folder and
 * the journal goes with it while the remembered path stays behind, so the next sweep of the renamed folder
 * deleted the held payload and Undo returned nothing. These tests run that scenario,
 * with real folders and the real engine.
 */
class FolderHoldsOperationsTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-holds-test").also { roots.add(it) }.toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private val heldSentence = "Finish or undo the pending delete in this folder first."
    private val jobSentence = "Wait for the copy, move or extract in this folder to finish first."

    @Test
    fun `renaming a folder with a held delete inside is refused and Undo still returns the file`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        gb.file("keep.gba", "KEEP")
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()
        try {
            val result = runBlocking { operations.rename(gb.path, "gameboy") }

            assertTrue("the rename must be refused", result.isFailure)
            assertEquals(heldSentence, FileFailureReasons.describe(result.exceptionOrNull()!!))
            assertTrue("the folder is still where it was", gb.isDirectory)
            assertFalse("nothing was renamed", File(root, "gameboy").exists())

            // The player opens the folder: the listing sweeps it. The held delete must survive that sweep.
            runBlocking { operations.recoverInterrupted(gb) }
            val restored = runBlocking { operations.undoDelete(deletion) }.getOrThrow()

            assertEquals("Undo returns the file the review said it lost", 1, restored)
            assertEquals("SAVE", File(gb, "x.sav").readText())
            assertEquals("KEEP", File(gb, "keep.gba").readText())
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `moving a folder with a held delete inside is refused too`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        val elsewhere = File(root, "elsewhere").apply { mkdirs() }
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()
        try {
            val result = runBlocking { operations.move(listOf(gb.path), elsewhere.path) }

            assertTrue(result.isFailure)
            assertEquals(heldSentence, FileFailureReasons.describe(result.exceptionOrNull()!!))
            assertTrue("the folder did not move", gb.isDirectory)
            assertFalse(File(elsewhere, "gb").exists())
            assertEquals(1, runBlocking { operations.undoDelete(deletion) }.getOrThrow())
            assertEquals("SAVE", File(gb, "x.sav").readText())
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `a sibling whose name merely starts the same, and the files inside, are not refused`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        gb.file("keep.gba", "KEEP")
        val gba = File(root, "gba").apply { mkdirs() }
        gba.file("mario.gba", "MARIO")
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()
        try {
            val sibling = runBlocking { operations.rename(gba.path, "advance") }
            val inside = runBlocking { operations.rename(File(gb, "keep.gba").path, "kept.gba") }

            assertTrue("gba is not inside gb: $sibling", sibling.isSuccess)
            assertTrue("a FILE inside the folder can still be renamed: $inside", inside.isSuccess)
            assertEquals("MARIO", File(root, "advance/mario.gba").readText())
            assertEquals(1, runBlocking { operations.undoDelete(deletion) }.getOrThrow())
        } finally {
            runBlocking { operations.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `once the delete is released the folder can be renamed again`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("x.sav", "SAVE")
        gb.file("keep.gba", "KEEP")
        val operations = ManagedFileOperations()
        val deletion = runBlocking { operations.stageDelete(listOf(File(gb, "x.sav").path)) }.getOrThrow()

        runBlocking { operations.finalizeDelete(deletion) }
        val result = runBlocking { operations.rename(gb.path, "gameboy") }

        assertTrue("nothing is held any more: $result", result.isSuccess)
        assertEquals("KEEP", File(root, "gameboy/keep.gba").readText())
        assertFalse(File(root, "gameboy/x.sav").exists())
    }

    @Test
    fun `a folder a running job is working in cannot be renamed until the job ends`() {
        val root = tempRoot()
        val gb = File(root, "gb").apply { mkdirs() }
        gb.file("a.gba", "A")
        val operations = ManagedFileOperations()
        val job = FileOperationStaging.createOperation(
            gb,
            "copy",
            StagingRecoveryPolicy.ROLLBACK,
            live = true,
        )
        try {
            val refused = runBlocking { operations.rename(gb.path, "gameboy") }
            assertTrue(refused.isFailure)
            assertEquals(jobSentence, FileFailureReasons.describe(refused.exceptionOrNull()!!))
            assertTrue(gb.isDirectory)
        } finally {
            FileOperationStaging.rollbackOperation(job)
        }

        val allowed = runBlocking { operations.rename(gb.path, "gameboy") }
        assertTrue("the job is over: $allowed", allowed.isSuccess)
    }

    @Test
    fun `an operation folder is marked live the moment it exists, so a sweep cannot take it`() {
        val base = tempRoot()
        val live = FileOperationStaging.createOperation(
            base,
            "copy",
            StagingRecoveryPolicy.ROLLBACK,
            live = true,
        )
        val unmarked = FileOperationStaging.createOperation(
            base,
            "copy",
            StagingRecoveryPolicy.ROLLBACK,
        )
        try {
            assertTrue(
                "live = true marks it before createOperation returns",
                live.canonicalPath in FileOperationStaging.liveRootPaths(),
            )
            assertFalse(unmarked.canonicalPath in FileOperationStaging.liveRootPaths())

            FileOperationStaging.recoverOrphaned(base)

            assertTrue("a job's own folder survives a sweep of its folder", live.isDirectory)
            assertFalse("an unmarked one is read as abandoned (the control)", unmarked.exists())
        } finally {
            FileOperationStaging.rollbackOperation(live)
            FileOperationStaging.rollbackOperation(unmarked)
        }
    }

    @Test
    fun `a retained operation folder is recognised through another spelling of the same path`() {
        // ManagedFileOperations resolves every destination to its canonical path before it sweeps, while a held
        // delete is remembered under the path it was made with: a Windows short name, an Android /sdcard link.
        val base = tempRoot()
        File(base, "x").mkdirs()
        File(base, "gb").mkdirs()
        val spelled = File(base, "x/../gb")
        val held = FileOperationStaging.createOperation(spelled, "delete", StagingRecoveryPolicy.ROLLBACK)
        val abandoned = FileOperationStaging.createOperation(spelled, "delete", StagingRecoveryPolicy.ROLLBACK)
        try {
            FileOperationStaging.recoverOrphaned(File(base, "gb").canonicalFile, setOf(held.absolutePath))

            assertTrue("the held delete survives a sweep that names its folder differently", held.isDirectory)
            assertFalse("the control: an operation nobody retains is swept", abandoned.exists())
        } finally {
            FileOperationStaging.rollbackOperation(held)
        }
    }

    @Test
    fun `a folder a copy is landing in cannot be renamed until the copy is done`() {
        val root = tempRoot()
        val source = File(root, "from").apply { mkdirs() }.file("x.rom", "INCOMING")
        val destination = File(root, "to").apply { mkdirs() }
        val operations = ManagedFileOperations()
        var duringTheCopy: Result<String?>? = null

        val copied = runBlocking {
            operations.copy(
                listOf(source.path),
                destination.path,
                // Fires once the bytes are staged and before the commit: the copy is mid-flight in "to".
                onAccounted = { _, _, _ ->
                    if (duringTheCopy == null) {
                        duringTheCopy = runBlocking { ManagedFileOperations().rename(destination.path, "renamed") }
                    }
                },
            )
        }

        assertTrue("the copy itself finished: $copied", copied.isSuccess)
        val refused = duringTheCopy!!
        assertTrue("the rename was refused while the copy was running", refused.isFailure)
        assertEquals(jobSentence, FileFailureReasons.describe(refused.exceptionOrNull()!!))
        assertEquals("INCOMING", File(destination, "x.rom").readText())

        val afterwards = runBlocking { operations.rename(destination.path, "renamed") }
        assertTrue("and allowed once it is over: $afterwards", afterwards.isSuccess)
        assertEquals("INCOMING", File(root, "renamed/x.rom").readText())
    }
}
