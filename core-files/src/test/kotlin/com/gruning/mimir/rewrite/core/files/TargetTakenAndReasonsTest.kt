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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix round after the E2 review, 2026-10-08.
 *
 *  - **Two jobs, one "(copy)" name.** Keep-both plans `x (copy).rom` while it is free. A second job that
 *    planned the same name used to be able to destroy the first job's file: the move over it was not
 *    refused, and a failing one left a journal entry that made the rollback delete what stood there.
 *  - **Every refusal says why.** The generic "Mimir couldn't unpack this archive." is now only for a
 *    failure nothing can name.
 */
class TargetTakenAndReasonsTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-taken-test").also { roots.add(it) }.toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun zipOf(file: File, vararg entries: Pair<String, String>): File {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }

    private val generic = "Mimir couldn't unpack this archive."

    private fun reasonOf(archive: File, destination: File, resolution: ConflictResolution = ConflictResolution.ASK): String {
        val result = runBlocking { ArchiveHelper().extract(archive.path, destination.path, conflictResolution = resolution) }
        assertTrue("expected ${archive.name} to fail", result.isFailure)
        return FileFailureReasons.describe(result.exceptionOrNull()!!)
    }

    // ---- Two jobs, one name ----

    @Test
    fun `an extraction whose Keep-both name was taken meanwhile fails and leaves the other job's file alone`() {
        val root = tempRoot()
        val archive = zipOf(File(root, "bundle.zip"), "x.rom" to "INCOMING")
        val destination = File(root, "roms").apply { mkdirs() }
        destination.file("x.rom", "ORIGINAL")
        val copyName = ConflictNamePolicy.keepBothName("x.rom", 1)
        var otherJobPlaced = false

        val result = runBlocking {
            ArchiveHelper().extract(
                archive.path,
                destination.path,
                conflictResolution = ConflictResolution.RENAME,
                // The placing phase has started and nothing is placed yet: the moment a second job, which
                // planned the same free name, gets there first.
                onPlacing = { placed, _ ->
                    if (placed == 0 && !otherJobPlaced) {
                        destination.file(copyName, "THE OTHER JOB")
                        otherJobPlaced = true
                    }
                },
            )
        }

        assertTrue("the other job placed its file", otherJobPlaced)
        assertTrue("this job must fail, not overwrite: $result", result.isFailure)
        assertEquals(
            "Something else put a file there first while this was extracting, so nothing was overwritten.",
            FileFailureReasons.describe(result.exceptionOrNull()!!),
        )
        assertEquals("the other job's file is untouched", "THE OTHER JOB", File(destination, copyName).readText())
        assertEquals("the original is untouched", "ORIGINAL", File(destination, "x.rom").readText())
        assertEquals(listOf(copyName, "x.rom").sorted(), destination.list().orEmpty().sorted())
    }

    @Test
    fun `a copy whose Keep-both name was taken meanwhile fails and neither file is lost`() {
        val root = tempRoot()
        val source = File(root, "from").apply { mkdirs() }.file("x.rom", "INCOMING")
        val destination = File(root, "to").apply { mkdirs() }
        destination.file("x.rom", "ORIGINAL")
        val copyName = ConflictNamePolicy.keepBothName("x.rom", 1)
        var otherJobPlaced = false

        val result = runBlocking {
            ManagedFileOperations().copy(
                listOf(source.path),
                destination.path,
                ConflictResolution.RENAME,
                // Fires once the bytes are staged and before the commit: the other job lands here.
                onAccounted = { _, _, _ ->
                    if (!otherJobPlaced) {
                        destination.file(copyName, "THE OTHER JOB")
                        otherJobPlaced = true
                    }
                },
            )
        }

        assertTrue("the other job placed its file", otherJobPlaced)
        assertTrue("this copy must fail, not replace it: $result", result.isFailure)
        assertEquals("THE OTHER JOB", File(destination, copyName).readText())
        assertEquals("ORIGINAL", File(destination, "x.rom").readText())
        assertEquals("the source is still there", "INCOMING", source.readText())
        assertFalse("no staging folder is left behind", File(destination, ".mimir-staging").exists())
    }

    // ---- Every refusal says why ----

    @Test
    fun `extracting into something that is a file says the place is a file`() {
        val root = tempRoot()
        val archive = zipOf(File(root, "bundle.zip"), "a.rom" to "A")
        val notAFolder = root.file("roms", "I am a file")

        val reason = reasonOf(archive, notAFolder)

        assertEquals("The place to extract into is a file, not a folder.", reason)
        assertEquals("the file was not touched", "I am a file", notAFolder.readText())
    }

    @Test
    fun `a file standing where the archive needs a folder says so`() {
        val root = tempRoot()
        val archive = zipOf(File(root, "bundle.zip"), "games/a.rom" to "A")
        val destination = File(root, "roms").apply { mkdirs() }
        destination.file("games", "a file called games")

        val reason = reasonOf(archive, destination)

        assertEquals("A file and a folder share a name in the destination, so nothing was extracted.", reason)
        assertEquals("a file called games", File(destination, "games").readText())
    }

    @Test
    fun `a folder standing where the archive needs a file says so`() {
        val root = tempRoot()
        val archive = zipOf(File(root, "bundle.zip"), "a.rom" to "A")
        val destination = File(root, "roms").apply { mkdirs() }
        File(destination, "a.rom").mkdirs()

        val reason = reasonOf(archive, destination, ConflictResolution.RENAME)

        assertEquals("A file and a folder share a name in the destination, so nothing was extracted.", reason)
        assertTrue(File(destination, "a.rom").isDirectory)
    }

    @Test
    fun `when every Keep-both name is taken the extraction says so`() {
        val root = tempRoot()
        val archive = zipOf(File(root, "bundle.zip"), "x.rom" to "INCOMING")
        val destination = File(root, "roms").apply { mkdirs() }
        destination.file("x.rom", "ORIGINAL")
        for (attempt in 1..ConflictNamePolicy.MAX_ATTEMPTS) {
            destination.file(ConflictNamePolicy.keepBothName("x.rom", attempt), "TAKEN")
        }

        val reason = reasonOf(archive, destination, ConflictResolution.RENAME)

        assertEquals("All the (copy) names for a file inside are already taken, so both can't be kept.", reason)
        assertNotEquals(generic, reason)
    }

    @Test
    fun `when every Keep-both name is taken a copy says so instead of a bare failure`() {
        val root = tempRoot()
        val source = File(root, "from").apply { mkdirs() }.file("x.rom", "INCOMING")
        val destination = File(root, "to").apply { mkdirs() }
        destination.file("x.rom", "ORIGINAL")
        for (attempt in 1..ConflictNamePolicy.MAX_ATTEMPTS) {
            destination.file(ConflictNamePolicy.keepBothName("x.rom", attempt), "TAKEN")
        }

        val result = runBlocking {
            ManagedFileOperations().copy(listOf(source.path), destination.path, ConflictResolution.RENAME)
        }

        assertTrue(result.isFailure)
        assertEquals(
            "All the (copy) names for x.rom are already taken, so both can't be kept.",
            FileFailureReasons.describe(result.exceptionOrNull()!!),
        )
    }

    @Test
    fun `a folder that cannot be made has its own sentence, and none of the new reasons is the generic one`() {
        val sentences = mapOf(
            ArchiveFailureKind.CANNOT_CREATE_FOLDER to "Mimir couldn't make a folder it needed in the destination.",
            ArchiveFailureKind.DESTINATION_NOT_A_FOLDER to "The place to extract into is a file, not a folder.",
            ArchiveFailureKind.FILE_FOLDER_CLASH to
                "A file and a folder share a name in the destination, so nothing was extracted.",
            ArchiveFailureKind.NO_FREE_COPY_NAME to
                "All the (copy) names for a file inside are already taken, so both can't be kept.",
            ArchiveFailureKind.TARGET_TAKEN to
                "Something else put a file there first while this was extracting, so nothing was overwritten.",
        )
        sentences.forEach { (kind, sentence) ->
            val described = FileFailureReasons.describe(ArchiveExtractionException("detail", kind))
            assertEquals(kind.name, sentence, described)
            assertNotEquals(kind.name, generic, described)
        }
    }

    // ---- Names Mimir keeps for itself ----

    @Test
    fun `renaming something to the staging folder's name is refused with a sentence and changes nothing`() {
        val root = tempRoot()
        val folder = File(root, "saves-backup").apply { mkdirs() }
        folder.file("a.srm", "A")

        listOf(".mimir-staging", ".MIMIR-STAGING", ".delete-123456789", ".extract-1").forEach { name ->
            val result = runBlocking { ManagedFileOperations().rename(folder.path, name) }

            assertTrue("$name must be refused", result.isFailure)
            assertEquals(
                RenameProblem.WORKING_FOLDER.sentence,
                FileFailureReasons.describe(result.exceptionOrNull()!!),
            )
            assertTrue("$name: the folder kept its name", folder.isDirectory)
        }
        assertEquals(listOf("saves-backup"), root.list().orEmpty().toList())
    }
}
