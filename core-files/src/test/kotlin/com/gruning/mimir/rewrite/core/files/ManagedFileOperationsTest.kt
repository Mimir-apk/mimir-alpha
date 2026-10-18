package com.gruning.mimir.rewrite.core.files

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [ManagedFileOperations] — the destructive half of the file manager.
 *
 * **The two tests that matter most are the safety properties, and they are named so a reviewer can find
 * them without reading the rest:**
 *  - [`a delete is recoverable for as long as its queue entry holds Undo`] — Undo is honest.
 *  - [`a held delete survives a later operation in the same folder`] — Undo is not silently reversed by
 *    the machinery that makes it possible.
 *
 * The complementary property — that PICK cannot reach any of this — is pinned in
 * `LocalFileRepositoryTest` (the picker's engine has no such member) and in `app/`'s
 * `FileBrowserStorePickSafetyTest` (the store constructs this class in exactly one branch).
 *
 * Real temp directories throughout; nothing here is mocked.
 */
class ManagedFileOperationsTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-managed-ops-test")
        .also { roots.add(it) }
        .toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    // ---- SAFETY PROPERTY: a delete is recoverable while the queue holds Undo ----

    @Test
    fun `a delete is recoverable for as long as its queue entry holds Undo`() = runBlocking {
        val root = tempRoot()
        val rom = root.file("Zelda.gba", "cartridge payload")
        val ops = ManagedFileOperations()

        val deletion = ops.stageDelete(listOf(rom.path)).getOrThrow()

        assertFalse(
            "the delete must LOOK done to the player -- the file is off their listing",
            rom.exists(),
        )
        assertTrue(
            "...and must still be held, or Undo would be a lie",
            ops.isHeld(deletion),
        )

        val restored = ops.undoDelete(deletion).getOrThrow()

        assertEquals("Undo must restore exactly the paths it took", 1, restored)
        assertTrue("the file must be back where it was", rom.exists())
        assertEquals(
            "Undo must restore the BYTES, not merely a file with the right name",
            "cartridge payload",
            rom.readText(),
        )
        assertFalse("the deletion is spent once undone", ops.isHeld(deletion))
    }

    @Test
    fun `a held delete leaves the payload intact on disk, which is what makes Undo survive a crash`() = runBlocking {
        val root = tempRoot()
        val rom = root.file("Metroid.gba", "payload bytes")
        val ops = ManagedFileOperations()

        val deletion = ops.stageDelete(listOf(rom.path)).getOrThrow()
        val staged = File(deletion.entries.single().stagedPath)

        assertTrue("the payload must still exist under .mimir-staging", staged.exists())
        assertEquals("byte-for-byte, not a placeholder", "payload bytes", staged.readText())
        assertTrue(
            "the journal must live under Mimir's own staging root",
            deletion.operationRootPath.contains(FileOperationStaging.ROOT_DIRECTORY),
        )

        // The crash path: a fresh process finds an orphaned ROLLBACK journal and restores it. This is
        // the property that makes Undo interrupt-survivable rather than a promise held in RAM.
        val recovered = FileOperationStaging.recoverOrphaned(root)

        assertEquals("an interrupted held delete must restore, never complete", 1, recovered)
        assertTrue(rom.exists())
        assertEquals("payload bytes", rom.readText())
    }

    // ---- SAFETY PROPERTY: the recovery machinery must not undo an Undo behind the player's back ----

    @Test
    fun `a held delete survives a later operation in the same folder`() = runBlocking {
        val root = tempRoot()
        val doomed = root.file("Deleted.gba", "gone")
        val other = root.file("Other.gba", "stays")
        val destination = File(root, "Elsewhere").apply { mkdirs() }
        val ops = ManagedFileOperations()

        val deletion = ops.stageDelete(listOf(doomed.path)).getOrThrow()
        assertFalse(doomed.exists())

        // ANY later operation sweeps orphaned journals in the directory it touches. Without the
        // `retain` guard this sweep reads the held delete as an interrupted one and restores it --
        // the player watches a file they deleted reappear, with an Undo button still offering to undo
        // something already undone.
        ops.copy(listOf(other.path), destination.path, ConflictResolution.SKIP).getOrThrow()
        ops.stageDelete(emptyList()).getOrThrow()
        ops.recoverInterrupted(root)

        assertFalse(
            "a held delete must NOT be resurrected by a later operation's recovery sweep -- see " +
                "FileOperationStaging.recoverOrphaned's `retain` parameter",
            doomed.exists(),
        )
        assertTrue("...and must still be undoable", ops.isHeld(deletion))

        assertEquals(1, ops.undoDelete(deletion).getOrThrow())
        assertTrue(doomed.exists())
    }

    /**
     * The same resurrection hazard, through a **timing window between two sessions** rather than within
     * one — and the reason [ManagedFileOperations]' held-root registry is process-level.
     *
     * `FileBrowserStore.clearSession` finalises held deletes asynchronously and drops its
     * [ManagedFileOperations] instance immediately. A player who closes the manager and reopens it
     * before that lands gets a fresh instance; if the registry were per-instance, the new one's map
     * would be empty, its first sweep of the same parent would read the old session's still-STAGED
     * journal as an interrupted delete, and the files would come back on screen with no player action.
     */
    @Test
    fun `a second session does not resurrect the first session's held delete`() = runBlocking {
        val root = tempRoot()
        val doomed = root.file("Doomed.gba", "gone")
        val bystander = root.file("Bystander.gba", "stays")
        val destination = File(root, "Elsewhere").apply { mkdirs() }

        val firstSession = ManagedFileOperations()
        val deletion = firstSession.stageDelete(listOf(doomed.path)).getOrThrow()
        assertFalse(doomed.exists())

        // The browser closes and reopens before the async finalise lands: a brand-new instance.
        val secondSession = ManagedFileOperations()
        secondSession.copy(listOf(bystander.path), destination.path, ConflictResolution.SKIP).getOrThrow()
        secondSession.recoverInterrupted(root)

        assertFalse(
            "a held delete must survive a NEW ManagedFileOperations instance sweeping the same folder " +
                "-- the held-root registry is process-level for exactly this window",
            doomed.exists(),
        )
        assertTrue("either instance must still see it as held", secondSession.isHeld(deletion))

        // Settle it, so this test leaves no journal behind for the next one.
        assertEquals(1, firstSession.finalizeDelete(deletion).getOrThrow())
    }

    @Test
    fun `finalising a delete makes it permanent and frees the staged payload`() = runBlocking {
        val root = tempRoot()
        val rom = root.file("Doomed.gba", "bytes")
        val ops = ManagedFileOperations()

        val deletion = ops.stageDelete(listOf(rom.path)).getOrThrow()
        val staged = File(deletion.entries.single().stagedPath)

        assertEquals(1, ops.finalizeDelete(deletion).getOrThrow())

        assertFalse("the file stays deleted", rom.exists())
        assertFalse("the staged payload must be released, or the card never frees the space", staged.exists())
        assertFalse("the journal must be gone", File(deletion.operationRootPath).exists())
        assertFalse("nothing left to undo", ops.isHeld(deletion))

        // A finalised delete must not be resurrectable either -- otherwise "permanent" is not.
        FileOperationStaging.recoverOrphaned(root)
        assertFalse(rom.exists())
    }

    @Test
    fun `undo and finalize are both idempotent, because a queue card can be pressed twice`() = runBlocking {
        val root = tempRoot()
        val rom = root.file("Twice.gba", "bytes")
        val ops = ManagedFileOperations()

        val deletion = ops.stageDelete(listOf(rom.path)).getOrThrow()
        assertEquals(1, ops.undoDelete(deletion).getOrThrow())
        assertEquals("a second Undo does nothing rather than failing", 0, ops.undoDelete(deletion).getOrThrow())
        assertEquals("...and finalising an undone delete must not delete it again", 0, ops.finalizeDelete(deletion).getOrThrow())
        assertTrue("the restored file must still be there after both no-ops", rom.exists())
    }

    // ---- Conflicts resolve explicitly, never silently ----

    @Test
    fun `a collision under ASK refuses and writes nothing`() = runBlocking {
        val root = tempRoot()
        val source = File(root, "src").apply { mkdirs() }.file("Pokemon Emerald.gba", "incoming")
        val destination = File(root, "dst").apply { mkdirs() }
        val existing = destination.file("Pokemon Emerald.gba", "already here")
        val ops = ManagedFileOperations()

        val result = ops.copy(listOf(source.path), destination.path, ConflictResolution.ASK)

        val error = result.exceptionOrNull()
        assertTrue(
            "ASK must refuse rather than silently renaming -- upstream's resolveTransferTarget treated " +
                "ASK as RENAME, which is exactly the silent resolution the design forbids. Got: $error",
            error is FileOperationConflictException,
        )
        assertEquals(
            "the conflict must name the colliding file so the queue can ask about it",
            "Pokemon Emerald.gba",
            (error as FileOperationConflictException).conflicts.single().name,
        )
        assertEquals("nothing may be written before the player answers", "already here", existing.readText())
        assertEquals(
            "and the destination must gain no keep-both artifact either",
            listOf("Pokemon Emerald.gba"),
            destination.listFiles().orEmpty().map { it.name }.filterNot { it.startsWith(".mimir-staging") }.sorted(),
        )
    }

    @Test
    fun `conflictsFor reports collisions without touching the filesystem`() = runBlocking {
        val root = tempRoot()
        val source = File(root, "src").apply { mkdirs() }
        val a = source.file("Clash.gba", "incoming")
        val b = source.file("Clear.gba", "incoming")
        val destination = File(root, "dst").apply { mkdirs() }
        destination.file("Clash.gba", "already here")

        val conflicts = ManagedFileOperations().conflictsFor(listOf(a.path, b.path), destination.path)

        assertEquals(listOf("Clash.gba"), conflicts.map { it.name })
        assertEquals(
            "the pre-flight must not create anything, not even a staging root",
            listOf("Clash.gba"),
            destination.listFiles().orEmpty().map { it.name }.sorted(),
        )
    }

    @Test
    fun `keep both, replace and skip each do a different, visible thing`() = runBlocking {
        val ops = ManagedFileOperations()

        // KEEP BOTH -- the existing file survives and the incoming one lands beside it.
        val keepRoot = tempRoot()
        val keepSource = File(keepRoot, "src").apply { mkdirs() }.file("Game.gba", "incoming")
        val keepDestination = File(keepRoot, "dst").apply { mkdirs() }
        keepDestination.file("Game.gba", "already here")
        ops.copy(
            listOf(keepSource.path),
            keepDestination.path,
            FileConflictChoice.KEEP_BOTH.resolution,
        ).getOrThrow()
        val keptNames = keepDestination.listFiles().orEmpty().map { it.name }.filterNot { it.startsWith(".") }
        assertEquals("both files must survive Keep both", 2, keptNames.size)
        assertEquals(
            "E2 O4: Keep both names the arriving file in plain words, never with a hash",
            listOf("Game (copy).gba", "Game.gba"),
            keptNames.sorted(),
        )
        assertEquals(
            "the original must be untouched",
            "already here",
            File(keepDestination, "Game.gba").readText(),
        )

        // REPLACE -- the existing file is gone, the incoming bytes are there under the same name.
        val replaceRoot = tempRoot()
        val replaceSource = File(replaceRoot, "src").apply { mkdirs() }.file("Game.gba", "incoming")
        val replaceDestination = File(replaceRoot, "dst").apply { mkdirs() }
        replaceDestination.file("Game.gba", "already here")
        ops.copy(
            listOf(replaceSource.path),
            replaceDestination.path,
            FileConflictChoice.REPLACE.resolution,
        ).getOrThrow()
        assertEquals(
            "Replace must leave the incoming bytes under the original name",
            "incoming",
            File(replaceDestination, "Game.gba").readText(),
        )

        // SKIP -- nothing changes at the destination at all.
        val skipRoot = tempRoot()
        val skipSource = File(skipRoot, "src").apply { mkdirs() }.file("Game.gba", "incoming")
        val skipDestination = File(skipRoot, "dst").apply { mkdirs() }
        skipDestination.file("Game.gba", "already here")
        ops.copy(
            listOf(skipSource.path),
            skipDestination.path,
            FileConflictChoice.SKIP.resolution,
        ).getOrThrow()
        assertEquals(
            "Skip must leave the existing file exactly as it was",
            "already here",
            File(skipDestination, "Game.gba").readText(),
        )
        assertTrue("Skip must leave the source alone too -- it is a copy", skipSource.exists())
    }

    // ---- Move and copy still do what they say ----

    @Test
    fun `move takes the file with it, copy leaves the original behind`() = runBlocking {
        val root = tempRoot()
        val source = File(root, "src").apply { mkdirs() }
        val moved = source.file("Moved.gba", "move me")
        val copied = source.file("Copied.gba", "copy me")
        val destination = File(root, "dst").apply { mkdirs() }
        val ops = ManagedFileOperations()

        assertEquals(1, ops.move(listOf(moved.path), destination.path, ConflictResolution.SKIP).getOrThrow())
        assertEquals(1, ops.copy(listOf(copied.path), destination.path, ConflictResolution.SKIP).getOrThrow())

        assertFalse("a move must not leave the original behind", moved.exists())
        assertEquals("move me", File(destination, "Moved.gba").readText())
        assertTrue("a copy must leave the original behind", copied.exists())
        assertEquals("copy me", File(destination, "Copied.gba").readText())
    }

    @Test
    fun `a move onto a nonexistent destination fails honestly instead of losing the file`() = runBlocking {
        val root = tempRoot()
        val rom = root.file("Precious.gba", "irreplaceable")
        val ops = ManagedFileOperations()

        val result = ops.move(listOf(rom.path), File(root, "no-such-folder").path, ConflictResolution.SKIP)

        assertTrue("a bad destination must fail, not succeed silently", result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue("and the source must still be there", rom.exists())
        assertEquals("irreplaceable", rom.readText())
    }
}
