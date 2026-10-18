package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Failure and empty-file regression coverage: **a failed card says why, and an empty file is not an archive.**
 *
 * Every observed failure read the single word "Failed". Each case below builds the same kind of input file, runs the real [ArchiveHelper.extract], and pins the sentence the card now shows -- plus that the
 * failure left nothing of Mimir's behind (no staging folder, no empty destination husk).
 */
class ArchiveFailureReasonTest {
    private val tempRoots = mutableListOf<Path>()

    @After
    fun cleanup() {
        tempRoots.forEach { it.toFile().deleteRecursively() }
        tempRoots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-archive-reason").also(tempRoots::add).toFile()

    private fun reasonOf(archive: File, destination: File): String {
        val result = runBlocking { ArchiveHelper().extract(archive.path, destination.path) }
        assertTrue("expected ${archive.name} to fail", result.isFailure)
        return FileFailureReasons.describe(result.exceptionOrNull()!!)
    }

    private fun assertLeftNothing(destination: File) {
        assertFalse(
            "a failed extraction made the destination folder and left it empty (D-6): ${destination.path}",
            destination.exists(),
        )
    }

    private fun source(root: File): File = File(root, "payload.txt").apply { writeText("hello") }

    private fun zipWith(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    @Test
    fun `a password protected zip says it is password protected, for both ZipCrypto and AES`() {
        val root = tempRoot()
        val legacy = File(root, "password-zipcrypto.zip")
        ZipFile(legacy, "cert-pass-1234".toCharArray()).addFile(
            source(root),
            ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.ZIP_STANDARD
            },
        )
        val aes = File(root, "password-aes256.zip")
        ZipFile(aes, "cert-pass-1234".toCharArray()).addFile(
            source(root),
            ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            },
        )
        val expected = "Password-protected. Mimir can't open archives that need a password."

        val legacyDestination = File(root, "legacy-out")
        val aesDestination = File(root, "aes-out")

        assertEquals(expected, reasonOf(legacy, legacyDestination))
        assertEquals(expected, reasonOf(aes, aesDestination))
        assertLeftNothing(legacyDestination)
        assertLeftNothing(aesDestination)
    }

    @Test
    fun `an encrypted 7z reads as password protected`() {
        val root = tempRoot()
        val archive = File(root, "password-header-encrypted.7z").apply { writeBytes(ByteArray(64)) }

        val classified = ArchiveFailureClassifier.classify(PasswordRequiredException(archive.name), archive, "7z")

        assertEquals(
            "Password-protected. Mimir can't open archives that need a password.",
            FileFailureReasons.describe(classified),
        )
    }

    @Test
    fun `a truncated zip is damaged or incomplete, not password protected and not a bare Failed`() {
        val root = tempRoot()
        val whole = File(root, "whole.zip")
        zipWith(whole, *(1..20).map { "file$it.txt" to "content of file $it ".repeat(20) }.toTypedArray())
        val bytes = whole.readBytes()
        val cutMiddle = File(root, "truncated-60pct.zip").apply { writeBytes(bytes.copyOf(bytes.size * 6 / 10)) }
        val tailLost = File(root, "truncated-tail-lost.zip").apply { writeBytes(bytes.copyOf(bytes.size - 10)) }

        assertEquals("Damaged or incomplete archive.", reasonOf(cutMiddle, File(root, "a")))
        assertEquals("Damaged or incomplete archive.", reasonOf(tailLost, File(root, "b")))
        assertLeftNothing(File(root, "a"))
        assertLeftNothing(File(root, "b"))
    }

    @Test
    fun `a truncated 7z is damaged or incomplete`() {
        val root = tempRoot()
        val whole = File(root, "whole.7z")
        SevenZOutputFile(whole).use { output ->
            repeat(5) { index ->
                val entry = SevenZArchiveEntry().apply {
                    name = "file$index.txt"
                    size = 2000
                }
                output.putArchiveEntry(entry)
                output.write(ByteArray(2000) { (it % 251).toByte() })
                output.closeArchiveEntry()
            }
        }
        val bytes = whole.readBytes()
        val truncated = File(root, "sevenz-truncated.7z").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }

        assertEquals("Damaged or incomplete archive.", reasonOf(truncated, File(root, "out")))
        assertLeftNothing(File(root, "out"))
    }

    @Test
    fun `text called dot zip is not a real zip, and so is a zip called dot 7z`() {
        val root = tempRoot()
        val text = File(root, "not-a-zip.zip").apply { writeText("this is just text ".repeat(20)) }
        val zipNamed7z = File(root, "zip-named-7z.7z")
        zipWith(zipNamed7z, "a.txt" to "A")
        val sevenNamedZip = File(root, "7z-named-zip.zip")
        SevenZOutputFile(sevenNamedZip).use { output ->
            output.putArchiveEntry(SevenZArchiveEntry().apply { name = "a.txt"; size = 1 })
            output.write('A'.code)
            output.closeArchiveEntry()
        }

        assertEquals("Not a real .zip archive.", reasonOf(text, File(root, "t")))
        assertEquals("Not a real .7z archive.", reasonOf(zipNamed7z, File(root, "z")))
        assertEquals("Not a real .zip archive.", reasonOf(sevenNamedZip, File(root, "s")))
        listOf("t", "z", "s").forEach { assertLeftNothing(File(root, it)) }
    }

    /**
     * A zero-byte `.zip` was observed to report "Done" with zero files rather than fail inside zip4j. Whatever the library does with it, an empty FILE is never
     * an archive -- even a valid zip with no entries is 22 bytes (`empty-no-entries.zip`, below, which stays
     * a success). So the engine decides this itself, before anything is created.
     */
    @Test
    fun `a zero-byte zip is not an archive and creates nothing`() {
        val root = tempRoot()
        val empty = File(root, "zero-byte.zip").apply { writeBytes(ByteArray(0)) }
        val destination = File(root, "zero-byte")

        assertEquals("Not an archive (empty file).", reasonOf(empty, destination))
        assertLeftNothing(destination)
    }

    @Test
    fun `a valid zip with no entries is still a success -- only zero BYTES is not an archive`() = runBlocking {
        val root = tempRoot()
        val archive = File(root, "empty-no-entries.zip")
        ZipOutputStream(FileOutputStream(archive)).use { }
        assertEquals("a valid empty zip is 22 bytes", 22L, archive.length())
        val destination = File(root, "empty-no-entries")

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertEquals(0, result.getOrThrow())
        assertTrue("the success keeps its (empty) folder", destination.isDirectory)
        assertFalse(
            "and leaves no staging folder",
            File(destination, FileOperationStaging.ROOT_DIRECTORY).exists(),
        )
    }

    @Test
    fun `unsafe paths read as unsafe, not as damaged`() {
        val root = tempRoot()
        val expected = "An entry inside has an unsafe path, so nothing was extracted."
        val cases = mapOf(
            "zip-slip-dotdot.zip" to "../../evil.txt",
            "zip-slip-absolute.zip" to "/evil.txt",
            "zip-slip-driveletter.zip" to "C:evil-drive.txt",
            "deep-66-segments.zip" to (1..66).joinToString("/") { "d$it" } + "/f.txt",
            "entry-name-4200-bytes.zip" to "n".repeat(4200),
        )
        cases.forEach { (archiveName, entryName) ->
            val archive = File(root, archiveName)
            zipWith(archive, "ok.txt" to "innocent", entryName to "payload")
            val destination = File(root, archiveName.removeSuffix(".zip"))

            assertEquals(archiveName, expected, reasonOf(archive, destination))
            assertLeftNothing(destination)
        }
    }

    @Test
    fun `the limits read as too many files and too large`() {
        val tooMany = ArchiveExtractionBudget()
        repeat(ArchiveEntryPathPolicy.MAX_ENTRIES) { tooMany.register("f$it", 0) }
        val manyFailure = runCatching { tooMany.register("one-too-many", 0) }.exceptionOrNull()!!
        assertEquals(
            "Too many files inside (more than 10,000).",
            FileFailureReasons.describe(manyFailure),
        )

        val bomb = ArchiveExtractionBudget()
        val bombFailure = runCatching {
            bomb.register("bomb.bin", ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES + 1)
        }.exceptionOrNull()!!
        assertEquals("Too large to unpack safely.", FileFailureReasons.describe(bombFailure))

        val duplicate = ArchiveExtractionBudget().apply { register("same.txt", 1) }
        val duplicateFailure = runCatching { duplicate.register("same.txt", 1) }.exceptionOrNull()!!
        assertEquals(
            "Two entries inside share a name, so it can't be unpacked safely.",
            FileFailureReasons.describe(duplicateFailure),
        )
    }

    @Test
    fun `a format Mimir does not extract says so`() {
        val root = tempRoot()
        val rar = File(root, "pack.rar").apply { writeBytes(ByteArray(16) { 0x52 }) }

        assertEquals("Unsupported archive format.", reasonOf(rar, File(root, "pack")))
        assertLeftNothing(File(root, "pack"))
    }

    @Test
    fun `an unknown error still gets a sentence`() {
        assertEquals("Mimir couldn't finish this.", FileFailureReasons.describe(IllegalStateException("boom")))
        assertEquals(
            "There isn't enough free space on the drive.",
            FileFailureReasons.describe(java.io.IOException("No space left on device")),
        )
    }
}
