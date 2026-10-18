package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.ConflictResolution
import com.gruning.mimir.rewrite.core.files.ManagedFileOperations
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the earlier update follow-up: **a rename takes a disc's `.sbi` with it, and Undo reverses exactly what the rename did.**
 *
 * Run against real files, rename and then Undo, through the same two functions the store calls
 * ([renameWithCompanions] and [renameSettledState]) -- not pinned from source. The store is an `object` over a `Context`
 * no JVM test can drive; everything that decides what moves lives in these.
 */
class FileBrowserCompanionRenameTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ops = ManagedFileOperations()

    private fun File.make(name: String): File = File(this, name).apply { writeText(name) }

    private fun reverseOf(from: File, renamed: RenamedWithCompanions): ReversibleMove {
        val state = renameSettledState(from, renamed.landed!!, undoable = true, companionRenames = renamed.companionRenames)
        return (state as FileQueueState.ReverseAvailable).reverse
    }

    private fun undo(reverse: ReversibleMove): RenamedWithCompanions = runBlocking {
        renameWithCompanions(
            ops,
            reverse.landedPaths.single(),
            reverse.previousName!!,
            ConflictResolution.ASK,
            undoing = reverse,
        ).getOrThrow()
    }

    @Test
    fun `undo renames back only the companions the rename moved, never a stranger that shares the new name`() {
        val folder = temp.newFolder()
        val cue = folder.make("Game.cue") // no Game.sbi: the rename has no companion to carry
        folder.make("Better.chd")
        val strangersSbi = folder.make("Better.sbi") // belongs to Better.chd, which nobody touched

        val renamed = runBlocking { renameWithCompanions(ops, cue.path, "Better.cue", ConflictResolution.ASK).getOrThrow() }

        assertTrue("the cue was renamed", File(folder, "Better.cue").isFile)
        assertTrue("and carried nothing", renamed.companionRenames.isEmpty() && renamed.stayed.isEmpty())

        undo(reverseOf(cue, renamed))

        assertTrue("Undo put the cue back", File(folder, "Game.cue").isFile && !File(folder, "Better.cue").exists())
        assertEquals("and left Better.chd's sbi exactly where and what it was", "Better.sbi", strangersSbi.readText())
        assertFalse("rather than renaming it to Game.sbi", File(folder, "Game.sbi").exists())
    }

    @Test
    fun `a rename carries the sbi, records it, and undo returns both under their old names`() {
        val folder = temp.newFolder()
        val cue = folder.make("Game.cue")
        folder.make("Game.sbi")

        val renamed = runBlocking { renameWithCompanions(ops, cue.path, "Better.cue", ConflictResolution.ASK).getOrThrow() }

        assertTrue(File(folder, "Better.cue").isFile && File(folder, "Better.sbi").isFile)
        assertFalse(File(folder, "Game.sbi").exists())
        assertEquals(
            "the Undo handle records where the companion stands now and the name it had",
            mapOf("Better.sbi" to "Game.sbi"),
            renamed.companionRenames.mapKeys { File(it.key).name },
        )

        undo(reverseOf(cue, renamed))

        assertTrue(File(folder, "Game.cue").isFile && File(folder, "Game.sbi").isFile)
        assertFalse(File(folder, "Better.cue").exists() || File(folder, "Better.sbi").exists())
    }

    @Test
    fun `a companion whose new name is taken stays as it was, is named, and is not part of the Undo`() {
        val folder = temp.newFolder()
        val cue = folder.make("Game.cue")
        val gameSbi = folder.make("Game.sbi")
        folder.make("Better.sbi") // someone else's file on the name the companion would take

        val renamed = runBlocking { renameWithCompanions(ops, cue.path, "Better.cue", ConflictResolution.ASK).getOrThrow() }

        assertEquals(listOf("Game.sbi"), renamed.stayed)
        assertTrue("it was left as it was", gameSbi.isFile)
        assertEquals("and the other file is untouched", "Better.sbi", File(folder, "Better.sbi").readText())
        assertTrue("so the Undo handle records no companion", reverseOf(cue, renamed).companionRenames.isEmpty())
    }
}
