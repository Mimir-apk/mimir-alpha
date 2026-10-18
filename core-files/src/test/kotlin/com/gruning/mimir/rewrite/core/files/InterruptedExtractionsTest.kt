package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **an interrupted extract is never silent.**
 *
 * Mimir was killed while it was PLACING the files of a 2,000-file extract. 626 stayed in the folder, 1,376
 * sat in an orphaned staging folder, the relaunched app showed an empty queue, and a later sweep deleted
 * the 1,376 without a word.
 *
 * ## How "killed" is simulated, and why it is faithful
 *
 * A process kill runs no `finally`, so throwing from a callback would not do: the engine would roll itself
 * back. Instead the test takes a COPY of the whole destination (and of the app-private pointer folder) at
 * the exact moment the engine reports a placement, lets the real run finish, then puts the copy back over
 * the top. What is on disk afterwards is, byte for byte, what a kill at that instant leaves. The restored
 * journal names the same absolute paths, so the next "process" -- a [InterruptedExtractions.resetForTest]
 * -- finds a real, abandoned job.
 */
class InterruptedExtractionsTest {
    private val tempRoots = mutableListOf<Path>()

    @Before
    fun fresh() {
        InterruptedExtractions.resetForTest()
    }

    @After
    fun cleanup() {
        InterruptedExtractions.resetForTest()
        tempRoots.forEach { it.toFile().deleteRecursively() }
        tempRoots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-interrupted").also(tempRoots::add).toFile()

    /** 12 files in 3 folders, so a kill has somewhere to land that is not the first or last file. */
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

    private class Setup(val root: File, val journals: File, val archive: File, val destination: File)

    private fun setup(): Setup {
        val root = tempRoot()
        val journals = File(root, "app-private").apply { mkdirs() }
        InterruptedExtractions.configure(journals)
        val destination = File(root, "out").apply { mkdirs() }
        File(destination, "keep.txt").writeText("the player's own file")
        return Setup(root, journals, twelveFileArchive(root), destination)
    }

    /**
     * Run a real extraction, snapshotting at the point [snapshotAt] picks, then replace the finished
     * result with that snapshot and start a "new process".
     */
    private fun killedAt(
        setup: Setup,
        atBytes: Boolean = false,
        placedWhenKilled: Int = 0,
    ) {
        val snapshotDestination = File(setup.root, "snapshot-out")
        val snapshotJournals = File(setup.root, "snapshot-journals")
        var taken = false
        fun snapshot() {
            if (taken) return
            taken = true
            setup.destination.copyRecursively(snapshotDestination)
            setup.journals.copyRecursively(snapshotJournals)
        }
        val result = runBlocking {
            ArchiveHelper().extract(
                setup.archive.path,
                setup.destination.path,
                onProgress = { _, _, _ -> if (atBytes) snapshot() },
                onPlacing = { placed, _ -> if (!atBytes && placed == placedWhenKilled) snapshot() },
            )
        }
        result.getOrThrow()
        assertTrue("the snapshot point was never reached", taken)

        setup.destination.deleteRecursively()
        setup.journals.deleteRecursively()
        snapshotDestination.copyRecursively(setup.destination)
        snapshotJournals.copyRecursively(setup.journals)
        // A new process: nothing in memory, only what is on disk.
        InterruptedExtractions.resetForTest()
        InterruptedExtractions.configure(setup.journals)
    }

    private fun extractedFiles(destination: File): List<String> =
        destination.walkTopDown().filter { it.isFile && it.name != "keep.txt" }
            .filterNot { it.path.contains(FileOperationStaging.ROOT_DIRECTORY) }
            .map { it.toRelativeString(destination).replace('\\', '/') }.sorted().toList()

    @Test
    fun `a kill between two placements is reported with how far it got, and the staging is cleaned up`() {
        val s = setup()
        killedAt(s, placedWhenKilled = 5)
        // The shape E2 found: some files placed, the rest stranded in the staging folder.
        assertEquals(5, extractedFiles(s.destination).size)
        assertTrue(
            "the unplaced remainder is sitting in staging",
            File(s.destination, FileOperationStaging.ROOT_DIRECTORY).isDirectory,
        )

        val reports = InterruptedExtractions.reconcile(emptySet())

        assertEquals(1, reports.size)
        val report = reports.single()
        assertEquals("bundle.zip", report.archiveName)
        assertEquals(5, report.placed)
        assertEquals(12, report.total)
        assertEquals(
            "Interrupted: 5 of 12 files were extracted to out. Those files were left in place.",
            report.sentence,
        )
        assertEquals("bundle.zip", report.title)
        assertEquals("the files already placed stay where they are", 5, extractedFiles(s.destination).size)
        assertEquals(
            "a file that was never Mimir's is never touched",
            "the player's own file",
            File(s.destination, "keep.txt").readText(),
        )
        assertFalse(
            "the orphaned staging folder is gone, and the empty .mimir-staging with it",
            File(s.destination, FileOperationStaging.ROOT_DIRECTORY).exists(),
        )
        assertTrue(
            "the pointer has done its job",
            File(s.journals, "jobs").listFiles().orEmpty().none { it.name.endsWith(".job") },
        )
        assertTrue("reporting is once, not on every start", InterruptedExtractions.reconcile(emptySet()).isEmpty())
    }

    @Test
    fun `a kill while still unpacking reports that nothing was extracted`() {
        val s = setup()
        killedAt(s, atBytes = true)

        val report = InterruptedExtractions.reconcile(emptySet()).single()

        assertEquals("Interrupted: nothing was extracted to out.", report.sentence)
        assertTrue(extractedFiles(s.destination).isEmpty())
        assertFalse(File(s.destination, FileOperationStaging.ROOT_DIRECTORY).exists())
        assertEquals("the folder itself was there before and stays", "the player's own file", File(s.destination, "keep.txt").readText())
    }

    @Test
    fun `a kill in the last instant, after every file was placed, is not a failure`() {
        val s = setup()
        killedAt(s, placedWhenKilled = 12)

        assertTrue(
            "all 12 are placed and only the journal's own tidy-up was missing: nothing is owed to the player",
            InterruptedExtractions.reconcile(emptySet()).isEmpty(),
        )
        assertEquals(12, extractedFiles(s.destination).size)
        assertFalse(File(s.destination, FileOperationStaging.ROOT_DIRECTORY).exists())
    }

    @Test
    fun `any recovery sweep leaves the report, whichever one reaches the folder first`() {
        val s = setup()
        killedAt(s, placedWhenKilled = 3)

        // Not the start-up pass: just a plain sweep, as a browse or a copy into this folder would run.
        FileOperationStaging.recoverOrphaned(s.destination)
        InterruptedExtractions.resetForTest()
        InterruptedExtractions.configure(s.journals)

        val report = InterruptedExtractions.reconcile(emptySet()).single()
        assertEquals("the report survives the process that swept", 3, report.placed)
        assertEquals("bundle.zip", report.archiveName)
        assertEquals(
            "and a pointer whose journal was already reported does not say it all over again",
            0,
            InterruptedExtractions.reconcile(emptySet()).size,
        )
    }

    @Test
    fun `a job that is still running is not an interruption`() {
        val s = setup()
        var sweptWhileRunning = false
        val result = runBlocking {
            ArchiveHelper().extract(
                s.archive.path,
                s.destination.path,
                onPlacing = { placed, _ ->
                    if (placed == 4) {
                        // Another job's start-up sweep, and the next Files session's reconcile, mid-publish.
                        FileOperationStaging.recoverOrphaned(s.destination)
                        InterruptedExtractions.reconcile(emptySet())
                        sweptWhileRunning = true
                    }
                },
            )
        }

        assertTrue(sweptWhileRunning)
        assertEquals("the running job finishes untouched", 12, result.getOrThrow())
        assertEquals(12, extractedFiles(s.destination).size)
        assertTrue(InterruptedExtractions.drain().isEmpty())
        assertFalse(File(s.destination, FileOperationStaging.ROOT_DIRECTORY).exists())
    }

    @Test
    fun `a pointer with no journal left still says Mimir stopped, without inventing counts`() {
        val s = setup()
        val ghostRoot = File(s.destination, "${FileOperationStaging.ROOT_DIRECTORY}/.extract-1")
        assertTrue(InterruptedExtractions.begin(s.archive.path, s.destination, ghostRoot) != null)
        // A new process; the journal it named was removed by something that did not report it.
        InterruptedExtractions.resetForTest()
        InterruptedExtractions.configure(s.journals)

        val report = InterruptedExtractions.reconcile(emptySet()).single()

        assertEquals(-1, report.placed)
        assertEquals(
            "Interrupted: Mimir stopped while extracting to out. Check that folder for any files already placed.",
            report.sentence,
        )
    }

    @Test
    fun `the sentence is plain, grammatical and says what was kept`() {
        fun sentence(placed: Int, total: Int) =
            InterruptedExtraction("big.zip", "/storage/Roms/many-2000-files", placed, total).sentence

        assertEquals(
            "Interrupted: 626 of 2,000 files were extracted to many-2000-files. Those files were left in place.",
            sentence(626, 2000),
        )
        assertEquals(
            "Interrupted: 1 of 5 files was extracted to many-2000-files. Those files were left in place.",
            sentence(1, 5),
        )
        assertEquals("Interrupted: nothing was extracted to many-2000-files.", sentence(0, -1))
        assertNull(InterruptedExtraction(null, "/x/out", 0, -1).archiveName)
        assertEquals("Extraction", InterruptedExtraction(null, "/x/out", 0, -1).title)
    }

    @Test
    fun `an extraction that finishes leaves no pointer and no staging`() {
        val s = setup()

        runBlocking { ArchiveHelper().extract(s.archive.path, s.destination.path).getOrThrow() }

        assertEquals(12, extractedFiles(s.destination).size)
        assertTrue(File(s.journals, "jobs").listFiles().orEmpty().isEmpty())
        assertFalse(File(s.destination, FileOperationStaging.ROOT_DIRECTORY).exists())
        assertTrue(InterruptedExtractions.drain().isEmpty())
    }
}
