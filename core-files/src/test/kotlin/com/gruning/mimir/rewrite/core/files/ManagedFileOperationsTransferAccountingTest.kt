package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue's `onAccounted`/`onSkipped` split keeps progress tied to actual transferred work.
 *
 * `copy`/`move` reported every advance through `onProgress`, whether or not anything was actually
 * observed moving: a skipped source, a source already at its destination, a small file finished in one
 * `Files.copy` call, and (see the class-level finding below) a same-folder atomic rename all folded into
 * the same callback a genuinely streamed large file uses. A caller could not tell "this fraction is a
 * real measurement" from "this fraction is bookkeeping" without re-deriving the engine's own internals.
 * `onAccounted` and `onSkipped` are the two callbacks that make the distinction observable from outside
 * the engine, following the same additive-callback shape `onLanded` already established.
 *
 * Real temp directories, real bytes, matching this package's own [ManagedFileOperationsLandingReportTest]
 * and [ManagedFileOperationsProgressTest] idiom — a fresh [ManagedFileOperations] per test, manual
 * cleanup, no mocking of the filesystem.
 *
 * **A finding this file also pins, not just documents:** `move`'s `stageByMove` (the branch this ticket's
 * source material names as the mechanism for "moving between folders on one SD card") requires
 * `target.parent == sourcePath.parent`. `target.parent` is always `destinationDirectory` (conflict
 * resolution only ever renames the leaf, never the parent), so that condition reduces to
 * `destinationDirectory == sourcePath.parent` — which, for the SAME source, is exactly the precondition
 * `move`'s own earlier "already at destination" branch already tests via
 * `requestedTarget.normalize() == sourcePath.normalize()` (`requestedTarget` is built from
 * `destinationDirectory.resolve(sourcePath.fileName)`, so `destinationDirectory == sourcePath.parent`
 * forces `requestedTarget == sourcePath` outright, not merely after normalizing). That branch `continue`s
 * before `stageByMove` is ever evaluated. So `stageByMove` cannot be true for any source that reaches it
 * — the atomic-rename branch this ticket's mechanism describes is unreachable, not merely rare, and the
 * large-file test below is the empirical half of that claim: a cross-folder move on ONE filesystem
 * streams in full, exactly like a cross-filesystem one would.
 */
class ManagedFileOperationsTransferAccountingTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() { roots.forEach { it.deleteRecursively() } }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-transfer-accounting").toFile().also { roots += it }
    private fun File.dir(name: String) = File(this, name).apply { mkdirs() }
    private fun writeFile(parent: File, name: String, megabytes: Int): File = File(parent, name).also { file ->
        val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
        file.outputStream().use { out -> repeat(megabytes) { out.write(chunk) } }
    }

    @Test
    fun `a large cross-folder move streams in full -- stageByMove never fires for a reachable source`() =
        runBlocking {
            val root = tempRoot()
            val from = root.dir("Downloads")
            val to = root.dir("ps2")
            val big = writeFile(from, "disc.iso", megabytes = 6)

            val progress = mutableListOf<Long>()
            val accounted = mutableListOf<Long>()
            val result = ManagedFileOperations().move(
                sources = listOf(big.absolutePath),
                destination = to.absolutePath,
                conflictResolution = ConflictResolution.OVERWRITE,
                onProgress = { done, _, _ -> progress += done },
                onAccounted = { done, _, _ -> accounted += done },
            )

            assertTrue("move failed: $result", result.isSuccess)
            assertTrue(
                "his most common operation, by the source material's own framing, is exactly this shape " +
                    "-- a folder-to-folder move on one card. It is fully observable: $progress",
                progress.size >= 3,
            )
            assertEquals(
                "and NOTHING in it is bookkeeping-only -- if this ever goes non-empty, stageByMove has " +
                    "become reachable and the class KDoc's proof needs re-checking, not silently accepting " +
                    "a new accounted call here",
                emptyList<Long>(),
                accounted,
            )
            assertEquals(1_048_576L * 6, progress.last())
        }

    @Test
    fun `a skip is accounted, not observed, and is named by onSkipped -- move`() = runBlocking {
        val root = tempRoot()
        val from = root.dir("Downloads")
        val to = root.dir("ps2")
        val rom = File(from, "Zelda.iso").also { it.writeBytes(ByteArray(1024) { i -> i.toByte() }) }
        to.let { File(it, "Zelda.iso").writeBytes(ByteArray(1024)) }

        val progress = mutableListOf<Long>()
        val accounted = mutableListOf<Long>()
        val skipped = mutableListOf<String>()
        val landed = mutableListOf<String>()
        val result = ManagedFileOperations().move(
            sources = listOf(rom.absolutePath),
            destination = to.absolutePath,
            conflictResolution = ConflictResolution.SKIP,
            onProgress = { done, _, _ -> progress += done },
            onAccounted = { done, _, _ -> accounted += done },
            onSkipped = { source -> skipped += source },
        ) { source, _ -> landed += source }

        assertTrue("move failed: $result", result.isSuccess)
        assertEquals("HANDLED count still increments for a skip -- unchanged, source-compatible", 1, result.getOrThrow())
        assertTrue("nothing was observed moving", progress.isEmpty())
        assertEquals("the bookkeeping advance carries the full source size", listOf(1024L), accounted)
        assertEquals("onSkipped names exactly the one source the player left alone", listOf(rom.absolutePath), skipped)
        assertTrue("onLanded is never fired for a source that did not land", landed.isEmpty())
        assertTrue("SKIP writes nothing -- the source is still there", rom.exists())
    }

    @Test
    fun `a skip is accounted, not observed, and is named by onSkipped -- copy`() = runBlocking {
        val root = tempRoot()
        val from = root.dir("Downloads")
        val to = root.dir("ps2")
        val rom = File(from, "Zelda.iso").also { it.writeBytes(ByteArray(2048) { i -> i.toByte() }) }
        File(to, "Zelda.iso").writeBytes(ByteArray(2048))

        val progress = mutableListOf<Long>()
        val accounted = mutableListOf<Long>()
        val skipped = mutableListOf<String>()
        val result = ManagedFileOperations().copy(
            sources = listOf(rom.absolutePath),
            destination = to.absolutePath,
            conflictResolution = ConflictResolution.SKIP,
            onProgress = { done, _, _ -> progress += done },
            onAccounted = { done, _, _ -> accounted += done },
            onSkipped = { source -> skipped += source },
        )

        assertTrue("copy failed: $result", result.isSuccess)
        assertEquals(1, result.getOrThrow())
        assertTrue("nothing was observed moving", progress.isEmpty())
        assertEquals(listOf(2048L), accounted)
        assertEquals(listOf(rom.absolutePath), skipped)
        assertTrue("SKIP writes nothing on the copy side either", rom.exists())
    }

    @Test
    fun `a source already at its destination is accounted, not observed`() = runBlocking {
        // Built entirely from `.canonicalFile`: ManagedFileOperations.requireDestination canonicalises
        // `destination` before comparing it against `sourcePath.parent`, which is NOT canonicalised
        // (ManagedFileOperations.move's own early-return, `requestedTarget.normalize() ==
        // sourcePath.normalize()`). On this machine `Files.createTempDirectory` lands under `/var`, which
        // is itself a symlink to `/private/var` -- so a test built from plain `.absolutePath` silently
        // stops exercising this branch at all (it falls through to a REAL transfer instead, which this
        // test's first draft did without noticing, since a small file "accounted" the same way by
        // coincidence). Canonicalising on both sides here is closing the SAME gap
        // `FileBrowserStore.namesSameFileAs` closes one layer up, not a workaround for this test alone.
        val root = tempRoot().canonicalFile
        val from = root.dir("Downloads")
        val rom = writeFile(from, "Zelda.iso", megabytes = 3)

        val progress = mutableListOf<Long>()
        val accounted = mutableListOf<Long>()
        val landed = mutableListOf<Pair<String, String>>()
        val result = ManagedFileOperations().move(
            sources = listOf(rom.canonicalPath),
            destination = from.canonicalPath,
            conflictResolution = ConflictResolution.OVERWRITE,
            onProgress = { done, _, _ -> progress += done },
            onAccounted = { done, _, _ -> accounted += done },
        ) { source, landedAt -> landed += source to landedAt }

        assertTrue("move failed: $result", result.isSuccess)
        assertTrue(
            "a no-op move (already at its destination) transfers zero bytes -- there is nothing for " +
                "onProgress to have observed. A 3 MiB file is comfortably above the streaming threshold, " +
                "so if this branch were NOT actually reached, the fallback would be an unmistakably " +
                "different signature: several real onProgress calls, not silence: $progress",
            progress.isEmpty(),
        )
        assertEquals(listOf(3_145_728L), accounted)
        assertEquals(
            "onLanded fires exactly once -- it DID land, under its own name",
            listOf(rom.canonicalPath to rom.canonicalPath),
            landed,
        )
    }
}
