package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState

/**
 * **Queue progress reports observed work as a percentage.**
 *
 * ## What was actually wrong
 *
 * Nothing in the UI. [FileOperationQueue.chipLabel] renders a real percentage from
 * `FileQueueState.Running.fraction`, the queue card draws a real bar, and `FileBrowserStore` really does
 * install an `onProgress` callback that calls `setState`. Every layer was honest.
 *
 * The defect was **emission granularity in the engine**: `Files.copy` transfers an entire file inside one
 * blocking call, so progress could only ever be reported *between* files. On this product's dominant
 * case — **one large ROM or one disc image** — the fraction went `0f` straight to `1f`, and the player
 * watched "0%" for the whole transfer. A bar that is correct and never moves is indistinguishable from a
 * bar that is decorative, which is exactly how it was read.
 *
 * ## What this pins
 *
 * That a single large file reports **more than once, monotonically, before it finishes**. That is the
 * property the old code could not have had: with one `Files.copy` there is exactly one callback per file,
 * and it arrives when the file is already complete.
 *
 * ## What this does NOT prove
 *
 * Not a rate, not a smoothness, and nothing about the UI. Whether the bar *looks* live on a device, over
 * a real SD card, is device-only. This pins that the engine emits something to be live with.
 */
class ManagedFileOperationsProgressTest {

    private val roots = mutableListOf<File>()

    private fun tempRoot(): File =
        Files.createTempDirectory("mimir-progress").toFile().also { roots += it }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    /** Comfortably above the 2 MiB streaming threshold, so the step is the 1 MiB floor: ~5 emissions. */
    private fun writeLargeFile(parent: File, name: String, megabytes: Int): File =
        File(parent, name).also { file ->
            val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
            file.outputStream().use { out -> repeat(megabytes) { out.write(chunk) } }
        }

    @Test
    fun `a single large copy reports progress while it is still copying`() = runBlocking {
        val source = tempRoot()
        val destination = tempRoot()
        val big = writeLargeFile(source, "disc.iso", megabytes = 6)

        val fractions = mutableListOf<Float>()
        val operations = ManagedFileOperations()
        val result = operations.copy(
            sources = listOf(big.absolutePath),
            destination = destination.absolutePath,
            conflictResolution = ConflictResolution.OVERWRITE,
            onProgress = { done, total, _ ->
                if (total > 0) fractions += (done.toFloat() / total)
            },
        )

        assertTrue("the copy itself must succeed: $result", result.isSuccess)
        assertTrue(
            "A 6 MiB single-file copy reported ${fractions.size} progress callback(s). With the old " +
                "Files.copy path this is exactly 1, and it arrives when the file is ALREADY COMPLETE — " +
                "which leaves a stale graphic. Progress must be emitted DURING the " +
                "transfer.",
            fractions.size >= 3,
        )
        val beforeCompletion = fractions.dropLast(1)
        assertTrue(
            "Every callback before the last must report an INCOMPLETE fraction; got $fractions. A " +
                "callback that already says 1.0 before the transfer ends is not a probe.",
            beforeCompletion.isNotEmpty() && beforeCompletion.all { it < 1f },
        )
        assertEquals(
            "The final callback must land on exactly 1.0 — a bar that stops at 0.98 reads as a stall.",
            1f,
            fractions.last(),
            0.0001f,
        )
        assertEquals(
            "Progress must never go backwards.",
            fractions.sorted(),
            fractions.toList(),
        )
    }

    @Test
    fun `the streamed copy still produces a byte-identical file`() = runBlocking {
        val source = tempRoot()
        val destination = tempRoot()
        val big = writeLargeFile(source, "rom.bin", megabytes = 5)

        val operations = ManagedFileOperations()
        val result = operations.copy(
            sources = listOf(big.absolutePath),
            destination = destination.absolutePath,
            conflictResolution = ConflictResolution.OVERWRITE,
        )

        assertTrue("copy failed: $result", result.isSuccess)
        val copied = File(destination, "rom.bin")
        assertTrue("the destination file must exist", copied.exists())
        assertEquals("size must match exactly", big.length(), copied.length())
        assertTrue(
            "CONTENT must match byte for byte. This is the pin that matters: the streaming loop replaced " +
                "a platform Files.copy on a path that moves the player's ROMs, and a chunking bug that " +
                "dropped or duplicated a buffer would still produce a plausible-looking file of roughly " +
                "the right size.",
            big.readBytes().contentEquals(copied.readBytes()),
        )
    }

    @Test
    fun `a small file still copies, but reports through onAccounted, never onProgress`() = runBlocking {
        val source = tempRoot()
        val destination = tempRoot()
        val small = File(source, "cover.png").also { it.writeBytes(ByteArray(4096) { i -> i.toByte() }) }

        val fractions = mutableListOf<Float>()
        val accounted = mutableListOf<Pair<Long, Long>>()
        val operations = ManagedFileOperations()
        val result = operations.copy(
            sources = listOf(small.absolutePath),
            destination = destination.absolutePath,
            conflictResolution = ConflictResolution.OVERWRITE,
            onProgress = { done, total, _ -> if (total > 0) fractions += (done.toFloat() / total) },
            onAccounted = { done, total, _ -> accounted += done to total },
        )

        assertTrue("copy failed: $result", result.isSuccess)
        val copied = File(destination, "cover.png")
        assertTrue(copied.exists())
        assertTrue("content must match", small.readBytes().contentEquals(copied.readBytes()))
        // a single `Files.copy` call reports AFTER the file is already fully written -- there was
        // never anything to observe DURING it, which is the same "accounting, not a measurement" shape
        // as an atomic rename or a skipped source. Folding it into onProgress (the old behaviour: a
        // fraction that jumps straight to 1.0) is exactly an unobserved progress claim, so the
        // engine must route it through onAccounted and leave onProgress untouched for this file.
        assertTrue(
            "a small file keeps the single-call fast path deliberately -- streaming a 4 KB icon would " +
                "cost real speed for progress nobody can see -- but that one call must land on " +
                "onAccounted, not onProgress: fractions was $fractions",
            fractions.isEmpty(),
        )
        assertEquals(
            "exactly one accounted call, reporting the whole file done in one step",
            listOf(4096L to 4096L),
            accounted,
        )
    }
}
