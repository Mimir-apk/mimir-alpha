package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.runBlocking
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
 * **"Extract into ⟨platform⟩" must never overwrite a ROM the player already has without them
 * choosing it.** The conflict check runs before any overwrite;
 *
 *
 * Separate from [ArchiveHelperExtractionTest] deliberately — that class is the **vendored** upstream
 * suite (zip-slip / zip-bomb defence, ported assertion-for-assertion), and mixing a Mimir behaviour
 * change into it would blur which assertions came from upstream and which this tree owns. Everything
 * here is new, and every test in it was differentially verified: the subject was reverted, the exact
 * failure captured, the subject restored.
 *
 * The two properties under test, in the order the engine encounters them:
 *
 *  1. **A collision refuses, and refuses having written nothing** — [ConflictResolution.ASK] is the
 *     default, so a caller that does not opt in cannot overwrite. The three answers the queue already
 *     renders ([FileConflictChoice]) each do what their label says.
 *  2. **A commit that throws halfway does not leave earlier entries destroyed** — the backups now
 *     outlive the commit loop, so [FileOperationStaging.recoverOrphaned] can put the originals back.
 *     [midCommitFailureRestoresAnAlreadyOverwrittenOriginal] drives that end-to-end through a real
 *     archive; [recoveryRestoresABackupLeftBehindByAnInterruptedExtraction] proves the recovery
 *     mechanism it depends on directly, so the claim does not rest on a reading of `recoverRollback`.
 */
class ArchiveHelperConflictTest {
    private val tempRoots = mutableListOf<Path>()
    /** Directories chmod-ed read-only by a test; restored before cleanup can delete them. */
    private val lockedDirectories = mutableListOf<File>()

    @After
    fun cleanup() {
        lockedDirectories.forEach { it.setWritable(true) }
        lockedDirectories.clear()
        tempRoots.forEach { it.toFile().deleteRecursively() }
        tempRoots.clear()
    }

    // ---- 1. Nothing overwrites without an explicit answer ----

    /**
     * The default is a refusal. No caller opts into overwriting by forgetting a parameter.
     *
     * Also the **completeness** pin: the exception must carry *every* collision, because the queue
     * raises one card per entry from that list. And the refusal must be total — the non-colliding
     * entry of the same archive must not have landed either, since a half-extracted archive is a
     * state the player never asked for.
     */
    @Test
    fun collidingExtractionRefusesByDefaultAndWritesNothing() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("bundle.zip").toFile()
        createZip(
            archive,
            "taken-one.rom" to "INCOMING ONE",
            "taken-two.rom" to "INCOMING TWO",
            "free.rom" to "INCOMING FREE",
        )
        val destination = root.resolve("ps3").toFile().apply { mkdirs() }
        destination.resolve("taken-one.rom").writeText("ORIGINAL ONE")
        destination.resolve("taken-two.rom").writeText("ORIGINAL TWO")

        val result = ArchiveHelper().extract(archive.path, destination.path)

        val failure = result.exceptionOrNull()
        assertTrue(
            "a collision must refuse, not overwrite. Got: $failure",
            failure is FileOperationConflictException,
        )
        assertEquals(
            "the card is raised per colliding entry, so the exception must carry ALL of them, not " +
                "just the first one the commit loop happened to reach",
            listOf("taken-one.rom", "taken-two.rom"),
            (failure as FileOperationConflictException).conflicts.map { it.name }.sorted(),
        )
        assertEquals("ORIGINAL ONE", destination.resolve("taken-one.rom").readText())
        assertEquals("ORIGINAL TWO", destination.resolve("taken-two.rom").readText())
        assertFalse(
            "the refusal must be total: the non-colliding entry must not land either, or the player " +
                "is left with half an archive they never agreed to",
            destination.resolve("free.rom").exists(),
        )
    }

    /**
     * The refusal leaves no husk.
     *
     * An observed failed extraction left an empty directory named exactly like a game in a PS3 folder. A
     * refusal must not add to that. `.mimir-staging` itself is excluded from this
     * assertion for the reason [ArchiveHelperExtractionTest.noRarPathExistsAnywhereInTheVendoredSurface]
     * already documents: `createOperation` makes that root before any of this runs and
     * `recoverOrphaned` never removes it — pre-existing vendored behaviour, not this change.
     */
    @Test
    fun refusingLeavesTheDestinationExactlyAsItWas() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("bundle.zip").toFile()
        createZip(archive, "taken.rom" to "INCOMING", "folder/nested.rom" to "INCOMING NESTED")
        val destination = root.resolve("ps3").toFile().apply { mkdirs() }
        destination.resolve("taken.rom").writeText("ORIGINAL")

        val before = visibleTree(destination)
        val result = ArchiveHelper().extract(archive.path, destination.path)

        assertTrue(result.isFailure)
        assertEquals(
            "a refused extraction must not create the directories it was going to extract into -- " +
                "that is how D-6's empty game-named husk appeared on his card",
            before,
            visibleTree(destination),
        )
    }

    /** *Replace*: the player asked for it, so it happens — and nothing is left behind. */
    @Test
    fun replaceOverwritesOnlyWhenItIsExplicitlyChosen() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("bundle.zip").toFile()
        createZip(archive, "taken.rom" to "INCOMING")
        val destination = root.resolve("ps3").toFile().apply { mkdirs() }
        destination.resolve("taken.rom").writeText("ORIGINAL")

        val result = ArchiveHelper().extract(
            archivePath = archive.path,
            destination = destination.path,
            conflictResolution = FileConflictChoice.REPLACE.resolution,
        )

        assertEquals(1, result.getOrThrow())
        assertEquals(
            "Replace is the one answer that is allowed to overwrite",
            "INCOMING",
            destination.resolve("taken.rom").readText(),
        )
        assertTrue(
            "a completed Replace must leave no operation journal behind",
            stagingOperationRoots(destination).isEmpty(),
        )
    }

    /** *Keep both*: the original survives untouched and the incoming entry lands beside it. */
    @Test
    fun keepBothLeavesTheOriginalAloneAndNamesTheIncomingFileDeterministically() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("bundle.zip").toFile()
        createZip(archive, "taken.rom" to "INCOMING", "folder/nested.rom" to "INCOMING NESTED")
        val destination = root.resolve("ps3").toFile().apply { mkdirs() }
        destination.resolve("taken.rom").writeText("ORIGINAL")
        destination.resolve("folder").mkdirs()
        destination.resolve("folder/nested.rom").writeText("ORIGINAL NESTED")

        val result = ArchiveHelper().extract(
            archivePath = archive.path,
            destination = destination.path,
            conflictResolution = FileConflictChoice.KEEP_BOTH.resolution,
        )

        assertEquals(2, result.getOrThrow())
        assertEquals(
            "Keep both means BOTH: the file already on the card must survive untouched",
            "ORIGINAL",
            destination.resolve("taken.rom").readText(),
        )
        assertEquals(
            "Keep both uses ConflictNamePolicy's plain name -- `name (copy).ext`, never a hash (E2 O4) -- " +
                "the same as move/copy",
            "INCOMING",
            destination.resolve("taken (copy).rom").readText(),
        )
        assertEquals("ORIGINAL NESTED", destination.resolve("folder/nested.rom").readText())
        assertEquals(
            "a nested entry must keep both INSIDE its own folder, not at the destination root",
            "INCOMING NESTED",
            destination.resolve("folder/nested (copy).rom").readText(),
        )
    }

    /** *Skip*: the existing file wins, and the entry still counts as handled — same as `copy()`. */
    @Test
    fun skipLeavesTheExistingFileAloneAndStillCountsTheEntry() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("bundle.zip").toFile()
        createZip(archive, "taken.rom" to "INCOMING", "free.rom" to "INCOMING FREE")
        val destination = root.resolve("ps3").toFile().apply { mkdirs() }
        destination.resolve("taken.rom").writeText("ORIGINAL")

        val result = ArchiveHelper().extract(
            archivePath = archive.path,
            destination = destination.path,
            conflictResolution = FileConflictChoice.SKIP.resolution,
        )

        assertEquals(
            "a skipped entry counts as handled, matching ManagedFileOperations.copy's SKIP branch, so " +
                "the queue's progress does not stall on a decision the player already made",
            2,
            result.getOrThrow(),
        )
        assertEquals(
            "Skip means the file already on the card wins",
            "ORIGINAL",
            destination.resolve("taken.rom").readText(),
        )
        assertEquals(
            "skipping one entry must not abandon the rest of the archive",
            "INCOMING FREE",
            destination.resolve("free.rom").readText(),
        )
    }

    /**
     * A destination **directory** standing where a file must go stays a refusal in every mode.
     *
     * It is not a conflict card: *Replace* would have to delete a whole directory tree to honour it,
     * and no queue answer should be able to do that. Pinned under [FileConflictChoice.REPLACE]
     * precisely because that is the answer most likely to be read as "just do it".
     */
    @Test
    fun aDirectoryInTheWayIsRefusedEvenUnderReplace() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("bundle.zip").toFile()
        createZip(archive, "taken.rom" to "INCOMING")
        val destination = root.resolve("ps3").toFile().apply { mkdirs() }
        val blocker = destination.resolve("taken.rom").apply { mkdirs() }
        blocker.resolve("inside.txt").writeText("PRECIOUS")

        val result = ArchiveHelper().extract(
            archivePath = archive.path,
            destination = destination.path,
            conflictResolution = FileConflictChoice.REPLACE.resolution,
        )

        assertTrue(
            "a directory standing where a file must go must refuse outright",
            result.isFailure,
        )
        assertFalse(
            "and it must NOT be offered as a Keep both / Replace / Skip card -- no queue answer " +
                "should be able to delete a directory tree",
            result.exceptionOrNull() is FileOperationConflictException,
        )
        // `takeIf { it.isFile }?.readText()` rather than a bare readText(): if the refusal is ever
        // removed, the directory gets backed up and deleted, and a bare read would blow up with a
        // FileNotFoundException instead of naming what went wrong.
        assertEquals(
            "Replace must never be able to delete a directory tree",
            "PRECIOUS",
            blocker.resolve("inside.txt").takeIf { it.isFile }?.readText(),
        )
    }

    // ---- 2. The mid-commit window ----

    /**
     * **The money test.** An extraction that throws partway through the commit must not leave the
     * entries it already overwrote destroyed.
     *
     * Construction, and why each piece is load-bearing:
     *  - The commit order is `sortedBy { nameCount }`, so `first.rom` (one path segment) is always
     *    committed before `locked/second.rom` (two). Depth, not same-directory listing order, which is
     *    unspecified — the ordering this test depends on is the one the source actually guarantees.
     *  - `first.rom` collides, so committing it takes a backup. That backup is the thing the old code
     *    deleted immediately (`ArchiveHelper.kt:441` before this change) and the new code holds.
     *  - `locked/` is made read-only **after** it exists, so the destination-directory validation in
     *    pass A passes and the failure lands in pass B's `atomicMove` — i.e. genuinely mid-commit,
     *    which is the only place this defect lives. A pass-A refusal would prove nothing here.
     *
     * If the platform ever stops enforcing the read-only bit (running as root, an exotic filesystem),
     * the extraction succeeds and the `isFailure` assertion below fails loudly rather than passing
     * vacuously — the instrument reports when it cannot see the defect.
     */
    @Test
    fun midCommitFailureRestoresAnAlreadyOverwrittenOriginal() = runBlocking {
        val root = tempRoot()
        val archive = root.resolve("bundle.zip").toFile()
        createZip(archive, "first.rom" to "INCOMING FIRST", "locked/second.rom" to "INCOMING SECOND")
        val destination = root.resolve("ps3").toFile().apply { mkdirs() }
        destination.resolve("first.rom").writeText("ORIGINAL FIRST")
        val locked = destination.resolve("locked").apply { mkdirs() }
        lockedDirectories += locked
        assertTrue("test setup: the directory must actually become read-only", locked.setWritable(false))

        val result = ArchiveHelper().extract(
            archivePath = archive.path,
            destination = destination.path,
            conflictResolution = FileConflictChoice.REPLACE.resolution,
        )

        assertTrue(
            "instrument check: if this extraction SUCCEEDED, the read-only directory did not block " +
                "the second commit and this test cannot observe the mid-commit window at all",
            result.isFailure,
        )
        assertEquals(
            "an extraction that dies after overwriting an earlier entry must leave that entry's " +
                "ORIGINAL in place -- the backup has to outlive the commit loop for recovery to have " +
                "anything to put back",
            "ORIGINAL FIRST",
            destination.resolve("first.rom").readText(),
        )
    }

    /**
     * The recovery half of the claim above, proved on its own.
     *
     * [midCommitFailureRestoresAnAlreadyOverwrittenOriginal] shows the end-to-end outcome; this shows
     * *why* — that a journal left in the exact shape the new commit loop leaves it (primary
     * `COMMITTED`, backup still `BACKUP_MOVED`) is one [FileOperationStaging.recoverOrphaned] sweep
     * away from the original coming back. Written as its own test because the end-to-end one would
     * still pass if it were recovering for some other reason, and because this is the path a **process
     * death** takes: no `finally` runs, and the next sweep of that directory is what repairs it.
     */
    @Test
    fun recoveryRestoresABackupLeftBehindByAnInterruptedExtraction() {
        val root = tempRoot()
        val destination = root.resolve("ps3").toFile().apply { mkdirs() }
        val committed = destination.resolve("game.rom").apply { writeText("INCOMING") }

        val operationRoot = FileOperationStaging.createOperation(
            baseDirectory = destination,
            operation = "extract",
            recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
        )
        val backup = operationRoot.resolve("backup").resolve("0")
        backup.parentFile?.mkdirs()
        backup.writeText("ORIGINAL")
        FileOperationStaging.writeManifest(
            operationRoot,
            StagingManifest(
                operation = "extract",
                recoveryPolicy = StagingRecoveryPolicy.ROLLBACK,
                entries = listOf(
                    StagedEntry(
                        originalPath = "",
                        stagedPath = operationRoot.resolve("payload/game.rom").path,
                        committedPath = committed.path,
                        state = FileOperationStaging.STATE_COMMITTED,
                    ),
                    StagedEntry(
                        originalPath = committed.path,
                        stagedPath = backup.path,
                        state = FileOperationStaging.STATE_BACKUP_MOVED,
                    ),
                ),
            ),
        )

        val recovered = FileOperationStaging.recoverOrphaned(destination)

        assertEquals(1, recovered)
        assertEquals(
            "a backup still held at BACKUP_MOVED is what makes an interrupted extraction reversible; " +
                "once it is marked COMMITTED and deleted, recoverRollback treats the entry as " +
                "cleanup-only and the player's file is gone for good",
            "ORIGINAL",
            committed.readText(),
        )
        assertFalse(operationRoot.exists())
    }

    // ---- helpers ----

    private fun tempRoot(): Path = Files.createTempDirectory("mimir-archive-conflict").also(tempRoots::add)

    /** Everything the player could see: the staging root is theirs to leak, not this change's. */
    private fun visibleTree(destination: File): List<String> =
        destination.walkTopDown()
            .filterNot { it.path.contains(FileOperationStaging.ROOT_DIRECTORY) }
            .map { it.toRelativeString(destination) }
            .toList()
            .sorted()

    private fun stagingOperationRoots(destination: File): List<File> =
        destination.resolve(FileOperationStaging.ROOT_DIRECTORY)
            .listFiles().orEmpty().filter { it.isDirectory }

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
