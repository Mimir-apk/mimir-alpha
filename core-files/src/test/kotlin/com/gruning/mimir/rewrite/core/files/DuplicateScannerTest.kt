package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The I/O half. Real temp directories and a **real symlink** throughout — the alias hazard below
 * cannot be faked convincingly, because the whole point is that no [FileItem] involved is itself a
 * link.
 *
 * Hashing is injected wherever the test is about a decision rather than a digest, and left as the
 * real [LocalFileRepository.getChecksum] in the one end-to-end test that pins the actual SHA-1
 * value.
 */
class DuplicateScannerTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-duplicate-scan-test")
        .also { roots.add(it) }
        .toFile()

    private fun File.file(name: String, text: String): File = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private suspend fun inventory(vararg files: File): List<FileItem> {
        val repo = LocalFileRepository()
        return files.mapNotNull { repo.getFileInfo(it.path) }
    }

    /** A digest that never runs; for tests where reaching the hasher at all would be the failure. */
    private val refuseToHash: suspend (String) -> String =
        { throw AssertionError("nothing should have been hashed: $it") }

    // ---- the alias hazard ----

    /**
     * **The one failure mode where a detector that deletes nothing still causes data loss.**
     *
     * On Android `/sdcard` links to `/storage/self/primary`, which links to `/storage/emulated/0`.
     * So one file is reachable by two paths, and NEITHER [FileItem] is a symlink — the link is an
     * *ancestor*, which [FileItem.isSymlink] cannot see. Without canonicalisation the scan reports
     * that file as a duplicate of itself, and a review asking *"which copy stays"* then invites the
     * player to remove the only copy.
     *
     * Reproduced with a real symlinked ancestor and the real default canonicaliser. The injected
     * hasher refuses to run, which also proves the alias is dropped *before* anything is opened.
     *
     * Note this is equally the design's own reading being made safe: two aliases of one file are
     * literally "same name and checksum in two folders".
     */
    @Test
    fun `two paths to one file through a symlinked ancestor are collapsed, never paired`() = runBlocking {
        val root = tempRoot()
        val real = File(root, "real").apply { mkdirs() }
        val rom = real.file("pokemon.gba", "MIMIR-DUP-FIXTURE-A")
        Files.createSymbolicLink(File(root, "link").toPath(), real.toPath())
        val viaLink = File(root, "link/pokemon.gba")

        val result = DuplicateScanner(digestOf = refuseToHash)
            .scan(inventory(rom, viaLink))

        assertTrue(
            "one file reached by two names is NOT two copies; offering a choice between it and " +
                "itself would let the player delete the only copy. Groups: ${result.groups}",
            result.groups.isEmpty(),
        )
        assertEquals(
            "the alias is reported, and deterministically it is the path-ascending second one",
            listOf(rom.path),
            result.aliased,
        )
        assertTrue(
            "collapsing an alias makes the scan CORRECT, not incomplete",
            result.isComplete,
        )
        assertEquals("and nothing was ever opened", 0, result.filesHashed)
    }

    // ---- end-to-end, with real hashing ----

    /**
     * The production wiring, unfaked: [DuplicateScanner.using] hashes through
     * [LocalFileRepository.getChecksum] at [DuplicateScanPolicy.DIGEST_ALGORITHM].
     *
     * All three files are the same 19 bytes long, so the size prune admits all three and the digest
     * is what actually decides — the third file proves a size match alone never becomes a group.
     *
     * The expected digest is an independent constant (`shasum -a 1` over the same literal bytes),
     * not a second `MessageDigest` call, which would only prove the implementation agrees with
     * itself. Same convention as `LocalFileRepositoryTest`'s own value pin.
     */
    @Test
    fun `identical bytes in two folders are found, and a same-size stranger is not`() = runBlocking {
        val root = tempRoot()
        val loose = root.file("Roms/pokemon.gba", "MIMIR-DUP-FIXTURE-A")
        val copied = root.file("Roms/GBA/pokemon.gba", "MIMIR-DUP-FIXTURE-A")
        val stranger = root.file("Roms/GBA/zelda.gba", "MIMIR-DUP-FIXTURE-B")

        val result = DuplicateScanner.using().scan(inventory(loose, copied, stranger))

        val group = result.groups.single()
        assertEquals(
            "the real SHA-1 of the shared bytes, computed outside this tree",
            "9e47fd914832cb833b954885dbd033c6bac1d9df",
            group.digest,
        )
        assertEquals(
            "both copies, and only them",
            listOf(copied.path, loose.path),
            group.files.map(FileItem::path),
        )
        assertEquals("all three shared a size, so all three were hashed", 3, result.filesHashed)
        assertEquals("and all 57 of their bytes were read", 57L, result.bytesHashed)
        assertTrue(
            "this is exactly the design's recorded rule -- same name, same checksum, two folders",
            DuplicateScanPolicy.isSameNameAcrossFolders(group),
        )
    }

    // ---- failure, and what a failure must not do ----

    @Test
    fun `a path that cannot be resolved is excluded and reported, never assumed separate`() = runBlocking {
        val root = tempRoot()
        val present = root.file("a.gba", "MIMIR-DUP-FIXTURE-A")
        val vanished = FileItem(name = "gone.gba", path = "${root.path}/gone.gba", size = 19L)

        val result = DuplicateScanner(digestOf = { "identical" })
            .scan(inventory(present) + vanished)

        assertEquals("the unresolvable path is named", listOf(vanished.path), result.unreadable)
        assertTrue(
            "and it is NOT paired with the surviving file on the strength of its literal path",
            result.groups.isEmpty(),
        )
        assertFalse("a scan that could not see everything says so", result.isComplete)
    }

    /**
     * One unreadable file never kills a scan — the same posture [LocalFileRepository.search] takes
     * for a permission-denied directory. The remaining pair is still found and still reported.
     */
    @Test
    fun `one file that fails to hash is skipped and the rest of the scan still completes`() = runBlocking {
        val root = tempRoot()
        val a = root.file("a.gba", "MIMIR-DUP-FIXTURE-A")
        val b = root.file("b.gba", "MIMIR-DUP-FIXTURE-A")
        val locked = root.file("locked.gba", "MIMIR-DUP-FIXTURE-A")

        val result = DuplicateScanner(
            digestOf = { path ->
                if (path == locked.path) throw IOException("permission denied") else "shared"
            },
        ).scan(inventory(a, b, locked))

        assertEquals(
            "the readable pair is still found -- one failure does not void the scan",
            listOf(a.path, b.path),
            result.groups.single().files.map(FileItem::path),
        )
        assertEquals("the failure is named, not swallowed", listOf(locked.path), result.unreadable)
        assertFalse("and the result admits it is partial", result.isComplete)
    }

    /**
     * Catch ORDER is the subject. A player who closes the review aborts through the suspending
     * hasher, from inside the scanner's own `try`. If the broad `catch (Exception)` came first, that
     * cancellation would be recorded as "unreadable file, keep going" and the device would hash the
     * rest of the volume for a screen nobody is looking at.
     */
    @Test
    fun `cancellation propagates out of the scan instead of being recorded as a skip`() {
        val root = tempRoot()
        val a = root.file("a.gba", "MIMIR-DUP-FIXTURE-A")
        val b = root.file("b.gba", "MIMIR-DUP-FIXTURE-A")

        val thrown = try {
            runBlocking {
                DuplicateScanner(digestOf = { throw CancellationException("review closed") })
                    .scan(inventory(a, b))
            }
            null
        } catch (e: CancellationException) {
            e
        }

        assertEquals(
            "the cancellation must escape the scan, not be filed as a per-file failure",
            "review closed",
            thrown?.message,
        )
    }

    // ---- the budget ----

    /**
     * **Budget pressure may report fewer groups; it must never report a group that understates its
     * own membership.**
     *
     * Three byte-identical copies, and a cap that only covers two of them. Spending the budget
     * per-file would hash two, emit a group claiming *2 copies*, and the player would answer "which
     * copy stays" against a group with a hidden third member — wrong in the one direction that
     * matters. So the budget is spent per size class, all or nothing.
     */
    @Test
    fun `a budget too small for a whole size class defers it rather than understating the group`() = runBlocking {
        val root = tempRoot()
        val a = root.file("a.gba", "MIMIR-DUP-FIXTURE-A")
        val b = root.file("b.gba", "MIMIR-DUP-FIXTURE-A")
        val c = root.file("c.gba", "MIMIR-DUP-FIXTURE-A")

        val result = DuplicateScanner(digestOf = { "shared" }).scan(
            inventory(a, b, c),
            budget = DuplicateHashBudget.ofBytes(38L), // exactly two of the three 19-byte files
        )

        assertTrue(
            "a group of 2 here would be a lie about a group of 3. Groups: ${result.groups}",
            result.groups.isEmpty(),
        )
        assertEquals(
            "all three are deferred together, and named",
            listOf(a.path, b.path, c.path),
            result.deferred,
        )
        assertEquals("nothing was opened", 0, result.filesHashed)
        assertFalse("and the result says the picture is incomplete", result.isComplete)
    }

    /** A budget that covers the work changes nothing about the answer. */
    @Test
    fun `a budget large enough to cover the scan yields the same groups as no budget at all`() = runBlocking {
        val root = tempRoot()
        val a = root.file("a.gba", "MIMIR-DUP-FIXTURE-A")
        val b = root.file("b.gba", "MIMIR-DUP-FIXTURE-A")

        val capped = DuplicateScanner(digestOf = { "shared" })
            .scan(inventory(a, b), budget = DuplicateHashBudget.ofBytes(1_000L))
        val uncapped = DuplicateScanner(digestOf = { "shared" }).scan(inventory(a, b))

        assertEquals(
            "the cap only decides what a scan can reach, never what a duplicate is",
            uncapped.groups.map { it.files.map(FileItem::path) },
            capped.groups.map { it.files.map(FileItem::path) },
        )
        assertTrue("both are complete", capped.isComplete && uncapped.isComplete)
    }

    /** Progress is reported per hashed file, against a total known before anything is opened. */
    @Test
    fun `progress counts against a total the size prune established up front`() = runBlocking {
        val root = tempRoot()
        val a = root.file("a.gba", "MIMIR-DUP-FIXTURE-A")
        val b = root.file("b.gba", "MIMIR-DUP-FIXTURE-A")
        val lonely = root.file("lonely.gba", "A-DIFFERENT-LENGTH-ENTIRELY")

        val seen = mutableListOf<Pair<Int, Int>>()
        DuplicateScanner(digestOf = { "shared" })
            .scan(inventory(a, b, lonely)) { hashed, total -> seen += hashed to total }

        assertEquals(
            "the uniquely-sized file is never counted, because it is never opened",
            listOf(1 to 2, 2 to 2),
            seen,
        )
    }
}
