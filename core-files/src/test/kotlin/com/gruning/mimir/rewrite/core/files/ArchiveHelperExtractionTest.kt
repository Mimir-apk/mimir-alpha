package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.data`. Upstream path:
 * `core/data/src/test/java/com/explorer/fileexplorer/core/data/ArchiveHelperExtractionTest.kt`.
 *
 * These are the tests that prove zip-slip and zip-bomb defence actually work end-to-end through
 * [ArchiveHelper] — the safety properties required from the vendored extractor.
 *
 * Rewritten from `kotlin.test` to `org.junit` — this project's exclusive test convention
 * (verified: zero `kotlin.test` imports anywhere in the tree). `assertFailsWith<T> { }` becomes
 * `assertThrows(T::class.java) { }` (JUnit 4.13+, matching `core-model`'s `IdsTest.kt`);
 * `@AfterTest` becomes `@org.junit.After`. Assertion logic is otherwise unmodified.
 *
 * DROPPED: `supportedArchiveExtensionsIncludeReadOnlyRarAndRejectMalformedRar` — it exercised the
 * deleted RAR path (asserted `isArchive(".rar") == true`, and that a malformed `.rar` failed via
 * junrar's own parser throwing mid-read). Both assertions are meaningless now: `isArchive(".rar")`
 * is `false` (see `ArchiveHelper.kt`'s header), and there is no junrar parser left to throw.
 * Replaced by [noRarPathExistsAnywhereInTheVendoredSurface] below, which pins the same RAR exclusion by
 * construction instead of by upstream's now-deleted behaviour.
 *
 * KEPT: `sevenZipTraversalFailsBeforeCommittingAnyOutput` — 7z stays fully supported (it is
 * `commons-compress`'s own `sevenz` package, Apache-2.0, never `sevenzipjbinding`; see
 * `ArchiveHelper.kt`'s header for the correction to the adoption spec's § 3 table).
 *
 * The `.fileexplorer-extract-` prefix check in the two zip tests below is ported verbatim,
 * including its literal upstream string (not renamed to `.mimir-`): read closely, it checks
 * `destination`'s DIRECT children, but `FileOperationStaging`'s actual nesting is
 * `destination/<ROOT_DIRECTORY>/.extract-<random>` — two levels down, not one — so this assertion
 * cannot observe the pattern it names, upstream or here. Inherited as an inert no-op rather than
 * silently rewritten into a new, unverified assertion.
 */
class ArchiveHelperExtractionTest {
    private val tempRoots = mutableListOf<Path>()

    @After
    fun cleanup() {
        tempRoots.forEach { it.toFile().deleteRecursively() }
        tempRoots.clear()
    }

    @Test
    fun zipExtractionStagesAndCommitsSafeEntries() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("safe.zip").toFile()
        createZip(archive, "folder/file.txt" to "safe")
        val destination = root.resolve("destination").toFile()

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertEquals(1, result.getOrThrow())
        assertEquals("safe", destination.resolve("folder/file.txt").readText())
        assertTrue(destination.listFiles().orEmpty().none { it.name.startsWith(".fileexplorer-extract-") })
    }

    @Test
    fun zipTraversalFailsBeforeCommittingAnyOutput() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("traversal.zip").toFile()
        createZip(archive, "../outside.txt" to "blocked")
        val destination = root.resolve("destination").toFile()
        val outside = root.resolve("outside.txt").toFile()

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertTrue(result.isFailure)
        assertFalse(outside.exists())
        assertTrue(destination.listFiles().orEmpty().none { it.name.startsWith(".fileexplorer-extract-") })
    }

    @Test
    fun sevenZipTraversalFailsBeforeCommittingAnyOutput() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("traversal.7z").toFile()
        SevenZOutputFile(archive).use { output ->
            val entry = SevenZArchiveEntry().apply {
                name = "../outside.txt"
                size = 7
            }
            output.putArchiveEntry(entry)
            output.write("blocked".toByteArray())
            output.closeArchiveEntry()
        }
        val destination = root.resolve("destination").toFile()
        val outside = root.resolve("outside.txt").toFile()

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertTrue(result.isFailure)
        assertFalse(outside.exists())
    }

    @Test
    fun tarSymbolicLinkIsRejected() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("link.tar").toFile()
        TarArchiveOutputStream(FileOutputStream(archive)).use { output ->
            val entry = TarArchiveEntry("link", TarConstants.LF_SYMLINK).apply {
                linkName = "../outside.txt"
            }
            output.putArchiveEntry(entry)
            output.closeArchiveEntry()
        }
        val destination = root.resolve("destination").toFile()

        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertTrue(result.isFailure)
        assertTrue(destination.listFiles().orEmpty().none { it.name.startsWith(".fileexplorer-extract-") })
    }

    @Test
    fun policyRejectsAbsoluteAndBackslashTraversalPaths() {
        val root = tempRoot()
        val tooDeep = (1..(ArchiveEntryPathPolicy.MAX_ENTRY_DEPTH + 1)).joinToString("/") { "folder" }

        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), "/outside.txt") != null)
        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), "C:\\outside.txt") != null)
        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), "folder\\..\\outside.txt") != null)
        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), "bad\u0000name") != null)
        assertFalse(ArchiveEntryPathPolicy.safeDestination(root.toString(), tooDeep) != null)
    }

    @Test
    fun budgetRejectsOversizedAndTooManyEntries() {
        val oversized = ArchiveExtractionBudget()
        assertThrows(ArchiveExtractionException::class.java) {
            oversized.register("large.bin", ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES + 1)
        }

        val tooMuchData = ArchiveExtractionBudget()
        repeat(
            (ArchiveEntryPathPolicy.MAX_TOTAL_UNCOMPRESSED_BYTES / ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES)
                .toInt(),
        ) {
            tooMuchData.register("data-$it", ArchiveEntryPathPolicy.MAX_ENTRY_UNCOMPRESSED_BYTES)
        }
        assertThrows(ArchiveExtractionException::class.java) {
            tooMuchData.register("data-over-limit", 1)
        }

        val tooMany = ArchiveExtractionBudget()
        repeat(ArchiveEntryPathPolicy.MAX_ENTRIES) {
            tooMany.register("entry-$it", 0)
        }
        assertThrows(ArchiveExtractionException::class.java) {
            tooMany.register("one-too-many", 0)
        }
    }

    @Test
    fun writableFormatsRoundTripThroughListAndExtract() = runBlocking {
        val root = tempRoot()
        val source = root.resolve("source.txt").toFile().apply { writeText("archive payload") }

        ArchiveFormat.entries.forEach { format ->
            val archive = root.resolve("bundle.${format.extension}").toFile()
            assertTrue(ArchiveHelper().createArchive(archive.path, listOf(source.path), format).isSuccess)

            val listed = ArchiveHelper().listArchive(archive.path)
            assertEquals(listOf("source.txt"), listed.map { it.name })

            val destination = root.resolve("extract-${format.extension.replace('.', '-')}").toFile()
            assertEquals(1, ArchiveHelper().extract(archive.path, destination.path).getOrThrow())
            assertEquals("archive payload", destination.resolve("source.txt").readText())
        }
    }

    /**
     * NOT ported from upstream — new for this slice. Pins the supported-format policy by construction, including refusal of RAR
     * (`BrowserListingTest.kt`'s `` `rar is not offered, because the unrar licence forbids it` ``):
     * assert the absence of the capability, not merely that one input happens to fail today.
     *
     * The [ClassNotFoundException] check is the strongest of the three assertions here: it fails
     * the moment `com.github.junrar` reappears as a dependency of `:core-files`, for any reason,
     * regardless of whether any code path calls it into — which is what the UnRAR licence actually
     * cares about (shipping the library), not merely whether our own code happens to invoke it.
     */
    @Test
    fun noRarPathExistsAnywhereInTheVendoredSurface() = runBlocking {
        val root = tempRoot()
        val helper = ArchiveHelper()

        assertFalse(
            "rar remains excluded: the reference unrar licence " +
                "forbids reimplementing RAR compression and is not a normal open licence",
            helper.isArchive("pack.rar"),
        )

        val rar = root.resolve("pack.rar").toFile().apply { writeBytes(ByteArray(16) { 0x52 }) }
        val destination = root.resolve("destination").toFile()
        val result = helper.extract(rar.path, destination.path)
        assertTrue("a .rar must never silently succeed -- it must fail honestly", result.isFailure)
        // Not destination.listFiles().isEmpty(): FileOperationStaging.createOperation() creates
        // its ROOT_DIRECTORY (".mimir-staging") inside `destination` before ArchiveHelper's own
        // `when` even dispatches on the extension, and FileOperationStaging.recoverOrphaned()
        // (called from extract()'s `finally` on this failure path) only deletes each operation's
        // OWN subdirectory, never that root -- confirmed empirically here, not assumed. So an
        // empty ROOT_DIRECTORY is real, pre-existing, vendored (slice 2) behaviour on every failed
        // extraction, not something this test or this slice introduced. What ruling 3 actually
        // requires -- that no bytes from the refused .rar land on disk -- is checked directly.
        assertTrue(
            "no bytes from a refused .rar may land on disk",
            destination.walkTopDown().none { it.isFile },
        )

        assertThrows(
            "junrar (NOASSERTION -- the UnRAR licence verbatim) must not be on the classpath at " +
                "all; this must fail the moment it is re-added as a dependency, for any reason",
            ClassNotFoundException::class.java,
        ) {
            Class.forName("com.github.junrar.Archive")
        }
        // Explicit trailing Unit: assertThrows returns the caught Throwable, and this function is
        // an expression body (`= runBlocking { ... }`) whose type JUnit4 requires to be void --
        // without this, the caught ClassNotFoundException would be the inferred return type and
        // JUnitCore rejects the whole test class at runner-construction time ("should be void"),
        // before any of its tests -- including this one's real assertions above -- ever run.
        Unit
    }

    private fun tempRoot(): Path = Files.createTempDirectory("mimir-archive-test").also(tempRoots::add)

    private fun createZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }
}
