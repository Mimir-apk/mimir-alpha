package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`stageDelete`'s `onProgress` cadence, grounded with real files.**
 *
 * `FileBrowserStore.deleteInManager` turns this callback into the queue's progress
 * fraction: one `staged` increment per call, counted against a `total` fixed at enqueue
 * (`paths.size`). That derivation only reads as honest progress if the callback actually fires once
 * per requested path, in the order the paths were given, and only once that path has genuinely landed
 * under `.mimir-staging` -- never before, and never for a path that was never there to begin with.
 *
 * `stageDelete` itself is unchanged -- every existing call site passed no callback at all. This
 * file grounds the assumption the store's new plumbing now depends on, the same way
 * [ManagedFileOperationsTransferAccountingTest] grounds the onAccounted/onSkipped split.
 *
 * Real temp directories, real files, no mocking -- this package's own idiom.
 */
class ManagedFileOperationsStageDeleteProgressTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() { roots.forEach { it.deleteRecursively() } }

    private fun tempRoot(): File =
        Files.createTempDirectory("mimir-stage-delete-progress").toFile().also { roots += it }

    @Test
    fun `onProgress fires once per staged path, in order, named by filename`() = runBlocking {
        val root = tempRoot()
        val a = File(root, "a.zip").also { it.writeBytes(ByteArray(16)) }
        val b = File(root, "b.zip").also { it.writeBytes(ByteArray(16)) }
        val c = File(root, "c.zip").also { it.writeBytes(ByteArray(16)) }

        val progressed = mutableListOf<String>()
        val result = ManagedFileOperations().stageDelete(
            listOf(a.absolutePath, b.absolutePath, c.absolutePath),
        ) { name -> progressed += name }

        assertTrue("stageDelete failed: $result", result.isSuccess)
        assertEquals(
            "the store derives a completed/total fraction by counting these calls, one per requested " +
                "path, in the order given -- a different cadence would make that fraction lie about " +
                "which entry is next",
            listOf("a.zip", "b.zip", "c.zip"),
            progressed,
        )
        assertEquals(3, result.getOrThrow().entries.size)
    }

    @Test
    fun `onProgress fires only after the path has actually landed, never before`() = runBlocking {
        val root = tempRoot()
        val rom = File(root, "rom.zip").also { it.writeBytes(ByteArray(16)) }

        var sourceGoneWhenCalled = false
        val result = ManagedFileOperations().stageDelete(listOf(rom.absolutePath)) {
            // The store's callback is the queue's ONLY signal that this entry is done. If it could fire
            // while the source still exists at its original path, the fraction it drives would be a
            // prediction rather than measurement, which cannot establish live queue progress.
            // both trace to the player's own words.
            sourceGoneWhenCalled = !rom.exists()
        }

        assertTrue("stageDelete failed: $result", result.isSuccess)
        assertTrue(
            "onProgress must fire AFTER the atomic move off the source path, never before",
            sourceGoneWhenCalled,
        )
    }

    @Test
    fun `a path that no longer exists is silently skipped -- no onProgress call for it`() = runBlocking {
        val root = tempRoot()
        val real = File(root, "real.zip").also { it.writeBytes(ByteArray(16)) }
        val vanished = File(root, "vanished.zip").absolutePath // never created

        val progressed = mutableListOf<String>()
        val result = ManagedFileOperations().stageDelete(
            listOf(vanished, real.absolutePath),
        ) { name -> progressed += name }

        assertTrue("stageDelete failed: $result", result.isSuccess)
        assertEquals(
            "a vanished source costs the store one increment it will never get. That undercounts the " +
                "requested total in the caller's derived fraction -- honest (fewer things actually " +
                "happened than were asked for), never an overcount",
            listOf("real.zip"),
            progressed,
        )
        assertEquals(1, result.getOrThrow().entries.size)
    }
}
