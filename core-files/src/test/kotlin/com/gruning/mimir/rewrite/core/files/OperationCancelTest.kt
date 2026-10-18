package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **Cancel** on a running copy, move or extract.
 *
 * A job is stopped the way the store stops it: its coroutine is cancelled, here from INSIDE an engine
 * callback so the stop lands at an exact, repeatable point (a progress report, a landing, a placement)
 * instead of on a timer. Real temp folders and real bytes throughout, so every assertion is about what is on
 * disk afterwards.
 */
class OperationCancelTest {

    private val roots = mutableListOf<Path>()

    @Before
    fun fresh() {
        InterruptedExtractions.resetForTest()
    }

    @After
    fun cleanup() {
        InterruptedExtractions.resetForTest()
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-cancel-test").also { roots.add(it) }.toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    /** Above the engine's streaming threshold, so a copy reports real progress while it runs. */
    private fun File.bigFile(name: String, megabytes: Int = 8) = File(this, name).apply {
        parentFile?.mkdirs()
        FileOutputStream(this).use { out ->
            val chunk = ByteArray(1024 * 1024) { 7 }
            repeat(megabytes) { out.write(chunk) }
        }
    }

    /**
     * Runs [body] as a coroutine that [stop] cancels, and answers whatever it threw (null if it finished).
     * [stop] is meant to be called from inside an engine callback, which runs on the engine's IO thread.
     */
    private fun runStoppable(body: suspend (stop: () -> Unit) -> Unit): Throwable? = runBlocking {
        var thrown: Throwable? = null
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            try {
                body { job.cancel() }
            } catch (t: Throwable) {
                thrown = t
            }
        }
        job.start()
        job.join()
        thrown
    }

    private fun names(folder: File): List<String> = folder.list().orEmpty().sorted()

    // ───────────────────────────────────────────────────────────────────────────── copy ──

    @Test
    fun `a copy stopped part-way through a big file leaves no half-written file and no staging`() {
        val root = tempRoot()
        val source = root.bigFile("disc.bin")
        val destination = File(root, "dest").apply { mkdirs() }
        val landed = mutableListOf<String>()

        val thrown = runStoppable { stop ->
            ManagedFileOperations().copy(
                listOf(source.path), destination.path, ConflictResolution.ASK,
                onProgress = { _, _, _ -> stop() },
                onLanded = { from, _ -> landed += from },
            )
        }

        assertTrue("stopped: $thrown", thrown is CancellationException)
        assertEquals("the destination is exactly as it was", emptyList<String>(), names(destination))
        assertTrue("the original is untouched", source.exists() && source.length() == 8L * 1024 * 1024)
        assertTrue("nothing reached the destination", landed.isEmpty())
    }

    @Test
    fun `a copy of several files stopped between them keeps the ones that landed and reports each`() {
        val root = tempRoot()
        val one = root.file("one.gba", "1")
        val two = root.file("two.gba", "2")
        val three = root.file("three.gba", "3")
        val destination = File(root, "dest").apply { mkdirs() }
        val landed = mutableListOf<String>()

        val thrown = runStoppable { stop ->
            ManagedFileOperations().copy(
                listOf(one.path, two.path, three.path), destination.path, ConflictResolution.ASK,
                onLanded = { from, _ ->
                    landed += from
                    if (landed.size == 1) stop()
                },
            )
        }

        assertTrue(thrown is CancellationException)
        assertEquals("exactly the one that finished before the stop", listOf(one.path), landed)
        assertEquals(listOf("one.gba"), names(destination))
        assertEquals(listOf("one.gba", "three.gba", "two.gba"), names(root).filter { it.endsWith(".gba") })
    }

    // ───────────────────────────────────────────────────────────────────────────── move ──

    @Test
    fun `a move stopped while its file is still being copied puts nothing in the destination and keeps the source`() {
        val root = tempRoot()
        val source = root.bigFile("disc.bin")
        val destination = File(root, "dest").apply { mkdirs() }
        val landed = mutableListOf<String>()

        val thrown = runStoppable { stop ->
            ManagedFileOperations().move(
                listOf(source.path), destination.path, ConflictResolution.ASK,
                onProgress = { _, _, _ -> stop() },
                onLanded = { from, _ -> landed += from },
            )
        }

        assertTrue(thrown is CancellationException)
        assertTrue("the source is never lost", source.exists() && source.length() == 8L * 1024 * 1024)
        assertEquals(emptyList<String>(), names(destination))
        assertTrue(landed.isEmpty())
    }

    @Test
    fun `a move stopped between files finishes the one in hand and leaves the rest where they were`() {
        val root = tempRoot()
        val origin = File(root, "origin").apply { mkdirs() }
        val one = origin.file("one.gba", "1")
        val two = origin.file("two.gba", "2")
        val three = origin.file("three.gba", "3")
        val destination = File(root, "dest").apply { mkdirs() }
        val landed = linkedMapOf<String, String>()

        val thrown = runStoppable { stop ->
            ManagedFileOperations().move(
                listOf(one.path, two.path, three.path), destination.path, ConflictResolution.ASK,
                onLanded = { from, to ->
                    landed[from] = to
                    if (landed.size == 1) stop()
                },
            )
        }

        assertTrue(thrown is CancellationException)
        assertEquals("the first is whole at the destination, byte for byte", "1", File(destination, "one.gba").readText())
        assertFalse("and gone from where it was: moved, not duplicated", one.exists())
        assertTrue("the others never left", two.exists() && three.exists())
        assertEquals(listOf("one.gba"), names(destination))
        assertEquals("the engine reported exactly what is on disk", listOf(one.path), landed.keys.toList())
    }

    // ────────────────────────────────────────────────────────────────────────── extract ──

    /** 12 files in 3 folders, so a stop has somewhere to land that is neither the first nor the last file. */
    private fun twelveFileArchive(root: File): File {
        val archive = File(root, "bundle.zip")
        ZipOutputStream(FileOutputStream(archive)).use { output ->
            for (folder in 0 until 3) for (file in 0 until 4) {
                output.putNextEntry(ZipEntry("d$folder/f$file.txt"))
                output.write("folder $folder file $file ".repeat(10).toByteArray())
                output.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `an extract stopped in the publish phase takes back every file it placed and the folders it made`() {
        val root = tempRoot()
        val archive = twelveFileArchive(root)
        val destination = File(root, "out")
        var placedWhenStopped = -1
        var outcome: ExtractionCancelOutcome? = null

        val thrown = runStoppable { stop ->
            ArchiveHelper().extract(
                archive.path, destination.path,
                onPlacing = { placed, _ ->
                    if (placed == 5) {
                        placedWhenStopped = placed
                        stop()
                    }
                },
                onCancelled = { outcome = it },
            )
        }

        assertEquals("the stop landed with files already published", 5, placedWhenStopped)
        assertTrue("the caller still sees a plain cancellation: $thrown", thrown is CancellationException)
        val stopped = outcome!!
        assertEquals("every one of the five this job placed was found in its journal and removed", 5, stopped.removed)
        assertEquals(0, stopped.remaining)
        assertFalse("the folder this job created is gone, with its staging", destination.exists())
        assertTrue(stopped.sentence, stopped.sentence.startsWith("Nothing was extracted."))
        assertTrue(stopped.sentence, stopped.sentence.contains("5 files"))
    }

    @Test
    fun `a stopped extract into a folder the player already had touches none of the player's files`() {
        val root = tempRoot()
        val archive = twelveFileArchive(root)
        val destination = File(root, "out").apply { mkdirs() }
        destination.file("keep.txt", "the player's own file")
        destination.file("d1/theirs.txt", "also theirs")

        var outcome: ExtractionCancelOutcome? = null

        val thrown = runStoppable { stop ->
            ArchiveHelper().extract(
                archive.path, destination.path,
                onPlacing = { placed, _ -> if (placed == 8) stop() },
                onCancelled = { outcome = it },
            )
        }

        assertTrue(thrown is CancellationException)
        assertEquals("all eight this job placed were removed", 8, outcome!!.removed)
        assertEquals("the player's own files stand", "the player's own file", File(destination, "keep.txt").readText())
        assertEquals("also theirs", File(destination, "d1/theirs.txt").readText())
        assertEquals("d1 still holds their file, and only theirs", listOf("theirs.txt"), names(File(destination, "d1")))
        assertEquals("the other folders this job made are gone again", listOf("d1", "keep.txt"), names(destination))
    }

    @Test
    fun `a stopped Replace puts the original back`() {
        val root = tempRoot()
        val archive = twelveFileArchive(root)
        val destination = File(root, "out").apply { mkdirs() }
        destination.file("d1/f2.txt", "ORIGINAL")

        var outcome: ExtractionCancelOutcome? = null

        val thrown = runStoppable { stop ->
            ArchiveHelper().extract(
                archive.path, destination.path,
                conflictResolution = ConflictResolution.OVERWRITE,
                onPlacing = { placed, _ -> if (placed == 11) stop() },
                onCancelled = { outcome = it },
            )
        }

        assertTrue(thrown is CancellationException)
        assertTrue("the rollback ran and reported", outcome != null)
        assertEquals(
            "whether the replacement had been published or not, the file ends with the content it started with",
            "ORIGINAL",
            File(destination, "d1/f2.txt").readText(),
        )
        assertEquals(listOf("f2.txt"), names(File(destination, "d1")))
        assertEquals(listOf("d1"), names(destination))
    }

    @Test
    fun `an extract stopped while still unpacking has placed nothing and says so`() {
        val root = tempRoot()
        val archive = twelveFileArchive(root)
        val destination = File(root, "out")

        var outcome: ExtractionCancelOutcome? = null

        val thrown = runStoppable { stop ->
            ArchiveHelper().extract(
                archive.path, destination.path,
                onProgress = { _, _, _ -> stop() },
                onCancelled = { outcome = it },
            )
        }

        assertTrue("$thrown", thrown is CancellationException)
        val stopped = outcome!!
        assertEquals("Nothing was extracted.", stopped.sentence)
        assertFalse(destination.exists())
    }

    @Test
    fun `a stop that arrives after the last file is placed cannot take anything back`() {
        val root = tempRoot()
        val archive = twelveFileArchive(root)
        val destination = File(root, "out")

        var outcome: ExtractionCancelOutcome? = null

        runStoppable { stop ->
            ArchiveHelper().extract(
                archive.path, destination.path,
                onPlacing = { placed, total -> if (placed == total) stop() },
                onCancelled = { outcome = it },
            )
        }

        assertNull(
            "there is no checkpoint after the final placement, so the engine does not roll back",
            outcome,
        )
        assertEquals("all twelve stand", 12, destination.walkTopDown().count { it.isFile })
        assertFalse(File(destination, FileOperationStaging.ROOT_DIRECTORY).exists())
    }

    @Test
    fun `the cancelled extract outcome words what the rollback found`() {
        val stopped = ExtractionCancelOutcome(removed = 626, remaining = 0, folder = "out")
        assertEquals("Nothing was extracted. The 626 files already placed were removed again.", stopped.sentence)
        assertEquals(
            "Stopped. 3 files already placed couldn’t be removed and were left in out.",
            ExtractionCancelOutcome(removed = 5, remaining = 3, folder = "out").sentence,
        )
        assertEquals(
            "Stopped. 1 file already placed couldn’t be removed and was left where you asked for them.",
            ExtractionCancelOutcome(removed = 0, remaining = 1, folder = null).sentence,
        )
        assertNotNull(ExtractionCancelOutcome(0, 0, null).sentence)
        assertEquals("Nothing was extracted.", ExtractionCancelOutcome(0, 0, "out").sentence)
    }
}
