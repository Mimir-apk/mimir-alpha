package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * MIMIR ADDITION — real-filesystem coverage for the vendored `LocalFileRepository.kt` (slice 4):
 * basic listing behavior and the two listing rules implemented in this class. The third rule
 * is covered by `LocalListingPolicyTest.kt`, since it has no call site here.
 */
class LocalFileRepositoryTest {

    @Test
    fun `listFiles returns the real children of a directory`() = runBlocking {
        val root = Files.createTempDirectory("mimir-local-repo-basic-test")
        try {
            File(root.toFile(), "zelda.gba").writeText("x")
            File(root.toFile(), "Roms").mkdirs()

            val rows = LocalFileRepository().listFiles(root.toString()).first().associateBy { it.name }

            assertEquals(2, rows.size)
            assertTrue(rows.getValue("Roms").isDirectory)
            assertFalse(rows.getValue("zelda.gba").isDirectory)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `listFiles on a path that does not exist returns empty rather than failing`() = runBlocking {
        val result = LocalFileRepository().listFiles("/this/path/does/not/exist/on/any/machine").first()
        assertTrue(result.isEmpty())
    }

    /**
     * RULING 2 pin, wired end-to-end. Anchored on the REAL filesystem root `/` rather than a
     * fabricated temp directory: `/proc`/`/sys`/`/dev` cannot be fabricated (they are the real OS
     * pseudo-filesystems the ruling exists to hide), so exercising the actual production code path
     * needs the real `/`. This is the honest reading of "tests against real temp directories" where
     * one cannot be built for the thing under test — the pure predicate is unit-tested against
     * literal strings in `LocalListingPolicyTest`; this test proves the WIRING inside [listFiles]
     * actually calls it. `/dev` is POSIX-standard and present on every platform this can run on
     * (verified here on macOS as well as Linux), so this is not vacuously true from `/proc`/`/sys`
     * merely not existing on the CI/dev machine.
     */
    @Test
    fun `listFiles on the real root excludes every known plumbing path`() = runBlocking {
        val rows = LocalFileRepository().listFiles("/").first()
        val paths = rows.map { it.path }.toSet()

        assertTrue(
            "sanity: '/' must actually be listable in this environment, or this test proves nothing",
            rows.isNotEmpty(),
        )
        assertTrue(
            "/dev is POSIX-standard and must exist on the machine running this test -- if this " +
                "fails, the sanity check above is what actually failed, not the plumbing filter",
            File("/dev").exists(),
        )
        LocalListingPolicy.PLUMBING_PATHS.forEach { plumbing ->
            assertFalse(
                "a plumbing path must never surface as a row: $plumbing",
                plumbing in paths,
            )
        }
    }

    /**
     * RULING 3 pin. Removes read+execute from a real directory and asserts [LocalFileRepository]
     * lists it empty instead of throwing.
     *
     * Guarded per this project's "verify the instrument can see the defect" discipline: if the
     * permission change does not actually take effect (most commonly because the test process is
     * running as root, which bypasses POSIX permission bits entirely), the trial is VOID and must
     * not be reported as a pass. Permissions are restored in `finally` before cleanup, or
     * `deleteRecursively()` on the now-unreadable directory would itself silently fail.
     */
    @Test
    fun `listFiles on an unreadable directory returns empty instead of throwing`() = runBlocking {
        val root = Files.createTempDirectory("mimir-local-repo-unreadable-test")
        val restricted = root.resolve("locked").toFile()
        try {
            restricted.mkdirs()
            File(restricted, "hidden.txt").writeText("secret")

            val readableChanged = restricted.setReadable(false, false)
            val executableChanged = restricted.setExecutable(false, false)
            val isRoot = System.getProperty("user.name") == "root"
            if (!readableChanged || !executableChanged || isRoot || Files.isReadable(restricted.toPath())) {
                // VOID: this sandbox cannot simulate permission denial, so this branch proves nothing about it.
                return@runBlocking
            }

            val result = LocalFileRepository().listFiles(restricted.path).first()

            assertTrue(
                "a permission-denied directory must list empty, not throw or crash the caller",
                result.isEmpty(),
            )
        } finally {
            restricted.setReadable(true, false)
            restricted.setExecutable(true, false)
            root.toFile().deleteRecursively()
        }
    }

    /**
     * POLICY 1 pin (2026-08-14): [FileItem.childCount] is WRITTEN, so a tome's meta line stops
     * rendering blank on the device.
     *
     * Pinned end-to-end through [LocalFileRepository.listFiles] rather than against the private
     * `pathToFileItem` helper, and asserting [FileItem.displaySize] as well as the raw field, because
     * the defect this closes was never in `displaySize` (which always read the field correctly) — it
     * was that nothing ever populated it. A test that only checked the field would still pass if the
     * render path were re-broken, and one that only checked the string would pass on a fabricated
     * count; both halves together are what make the row honest.
     *
     * The empty-directory assertion is deliberate and is the counterpart to the unreadable case below:
     * a readable-but-empty folder genuinely IS "0 items", and must not be conflated with "cannot read".
     */
    @Test
    fun `a directory row carries its real child count and renders it, a file carries none`() = runBlocking {
        val root = Files.createTempDirectory("mimir-local-repo-child-count-test")
        try {
            val roms = File(root.toFile(), "Roms").apply { mkdirs() }
            File(roms, "zelda.gba").writeText("x")
            File(roms, "metroid.gba").writeText("x")
            File(roms, "GBA").mkdirs()
            File(root.toFile(), "Empty").mkdirs()
            File(root.toFile(), "loose.gba").writeText("x")

            val rows = LocalFileRepository().listFiles(root.toString()).first().associateBy { it.name }

            assertEquals(
                "a directory must report its real child count -- counting files AND subdirectories",
                3,
                rows.getValue("Roms").childCount,
            )
            assertEquals(
                "the count must reach the tome's meta line through displaySize",
                "3 items",
                rows.getValue("Roms").displaySize,
            )
            assertEquals(
                "a readable but empty directory is genuinely 0, not unknown",
                0,
                rows.getValue("Empty").childCount,
            )
            assertEquals("0 items", rows.getValue("Empty").displaySize)
            assertNull(
                "a plain file has no children; childCount must stay null so displaySize shows its size",
                rows.getValue("loose.gba").childCount,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * POLICY 1's second half: an unreadable directory reports **unknown**, never `0`.
     *
     * "0 items" and "cannot read this folder" are different claims about the filesystem, and the tome
     * meta line renders them differently on purpose ([FileItem.displaySize] draws nothing for null).
     * A count of 0 here would be the browser inventing a fact it does not have — the same class of lie
     * `fileBrowserFooterHints` refuses ("a fixed legend would lie") and `MimirPlatformShelfmark` refuses
     * (draw nothing rather than a wrong plate).
     *
     * The parent is listed, not the locked directory itself: the row for `locked` is produced by the
     * parent's own listing, which is exactly the production path. Guarded with the same VOID discipline
     * as the ruling-3 test above -- a sandbox that cannot revoke read permission proves nothing here.
     */
    @Test
    fun `an unreadable directory reports an unknown child count, never zero`() = runBlocking {
        val root = Files.createTempDirectory("mimir-local-repo-child-count-unreadable-test")
        val restricted = root.resolve("locked").toFile()
        try {
            restricted.mkdirs()
            File(restricted, "hidden.txt").writeText("secret")

            val readableChanged = restricted.setReadable(false, false)
            val executableChanged = restricted.setExecutable(false, false)
            val isRoot = System.getProperty("user.name") == "root"
            if (!readableChanged || !executableChanged || isRoot || Files.isReadable(restricted.toPath())) {
                // VOID, not a pass: a sandbox that cannot revoke read permission cannot
                // establish the permission-denied behavior.
                return@runBlocking
            }

            val rows = LocalFileRepository().listFiles(root.toString()).first().associateBy { it.name }
            val locked = rows.getValue("locked")

            assertTrue("sanity: the locked row must still BE a directory row", locked.isDirectory)
            assertNull(
                "an unreadable directory must report unknown, never a fabricated 0 -- \"0 items\" and " +
                    "\"cannot read\" are different claims",
                locked.childCount,
            )
            assertEquals(
                "unknown renders as nothing at all, not as \"0 items\"",
                "",
                locked.displaySize,
            )
        } finally {
            restricted.setReadable(true, false)
            restricted.setExecutable(true, false)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `read-only surface still works after stripping write operations`() = runBlocking {
        val root = Files.createTempDirectory("mimir-local-repo-readonly-surface-test")
        try {
            val file = File(root.toFile(), "zelda.gba").apply { writeText("cartridge payload") }
            val repo = LocalFileRepository()

            val info = repo.getFileInfo(file.path)
            assertEquals("zelda.gba", info?.name)

            assertTrue(repo.exists(file.path))
            assertFalse(repo.exists(root.resolve("missing.gba").toString()))

            assertEquals("cartridge payload".length.toLong(), repo.calculateSize(listOf(file.path)))

            val found = repo.search(root.toString(), "zelda").first()
            assertEquals("zelda.gba", found.name)

            val checksum = repo.getChecksum(file.path)
            assertEquals(64, checksum.length) // SHA-256, hex-encoded
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * MIMIR ADDITION (2026-08-14) — a VALUE pin for [LocalFileRepository.getChecksum], not the length
     * check above, which passes for any 64-hex-character output including a wrong one.
     *
     * The second half is the part that matters for the file browser's owed "Verify" button. The design
     * adoption spec (§7) says checksum verification confirms identity *against the bundled DAT
     * inventory*, and that corpus is keyed on **SHA-1** (`core-metadata/BundledDatSource.kt:17`:
     * "the frozen corpus has SHA-1 for every admitted source row") and CRC32
     * (`core-metadata/DatIdentitySource.kt:5`). This method's DEFAULT is SHA-256, which matches nothing
     * in that corpus — so a "Verify" wired to `getChecksum(path)` would compute a real, correct,
     * permanently unmatchable hash. Both algorithms are pinned here so the mismatch is a documented
     * property of the engine rather than a surprise found later at the wiring site.
     *
     * Expected digests are independent constants (computed outside this tree, from the same literal
     * bytes) rather than a second `MessageDigest` call, which would only prove the implementation
     * agrees with itself.
     */
    @Test
    fun `getChecksum computes real digests, and its default is not the DAT corpus algorithm`() = runBlocking {
        val root = Files.createTempDirectory("mimir-local-repo-checksum-test")
        try {
            val file = File(root.toFile(), "fixture.bin").apply { writeText("MIMIR-CHECKSUM-FIXTURE") }
            val repo = LocalFileRepository()

            assertEquals(
                "SHA-1 is what the bundled DAT corpus is keyed on",
                "820143092bf4eb3fd793921076d496c7b52381ed",
                repo.getChecksum(file.path, "SHA-1"),
            )
            assertEquals(
                "the DEFAULT algorithm is SHA-256, which no bundled DAT row can ever match",
                "b42aa48b7e221f43414289be43dadc24cf9a27751800b459a8be6753c9c58699",
                repo.getChecksum(file.path),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
