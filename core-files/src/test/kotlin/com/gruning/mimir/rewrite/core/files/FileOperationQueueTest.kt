package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry

/**
 * F-01 pins for the queue model — in particular, **Undo is a queue STATE, not a toast.**
 *
 * Held deletion exposes **Undo available** as durable queue state instead of a transient toast. This suite pins the structural half of that (the state carries the means to reverse itself,
 * and nothing here expires it); [ManagedFileOperationsTest] pins the filesystem half.
 */
class FileOperationQueueTest {

    private fun deletion(id: String = "/tmp/.mimir-staging/.delete-1") = StagedDeletion(
        operationRootPath = id,
        entries = listOf(StagedDeletionEntry("/tmp/Zelda.gba", "$id/payload/Zelda.gba")),
    )

    private fun entry(
        id: Long = 1,
        operation: FileOperation = FileOperation.DELETE,
        state: FileQueueState = FileQueueState.Done,
    ) = FileQueueEntry(id = id, kind = operation.toJobKind(), title = "Zelda.gba", subtitle = "", state = state)

    @Test
    fun `an Undo-available entry carries the handle that actually reverses the delete`() {
        val held = entry(state = FileQueueState.UndoAvailable(deletion()))

        assertTrue("the entry must advertise its Undo", held.undoable)
        assertEquals(
            "Undo must be a STATE holding a real handle, not a toast with a message. An entry in this " +
                "state cannot exist without the means to reverse itself -- that is what makes the " +
                "affordance honest rather than decorative.",
            "/tmp/.mimir-staging/.delete-1",
            (held.state as FileQueueState.UndoAvailable).deletion.operationRootPath,
        )
        assertEquals("Undo available", FileOperationQueue.chipLabel(held))
    }

    @Test
    fun `nothing in the queue model expires an Undo`() {
        // The property, stated as code: there is no API here that takes a queue and returns it with
        // undoable entries removed or aged out. Only an explicit removal by id can drop one.
        val queue = listOf(entry(id = 1, state = FileQueueState.UndoAvailable(deletion())))

        assertEquals(
            "a state change to an unrelated id must leave the Undo alone",
            queue,
            FileOperationQueue.withState(queue, id = 99, state = FileQueueState.Done),
        )
        assertTrue(
            "only an explicit removal drops it",
            FileOperationQueue.remove(queue, 1).isEmpty(),
        )
    }

    @Test
    fun `only a delete is ever undoable`() {
        listOf(
            FileQueueState.Running(0.5f),
            FileQueueState.AwaitingConflict,
            FileQueueState.Blocked("7z not available"),
            FileQueueState.Done,
            FileQueueState.Failed("permission"),
        ).forEach { state ->
            assertFalse(
                "$state must not offer Undo -- only a staged delete has something held aside to restore",
                entry(state = state).undoable,
            )
        }
    }

    @Test
    fun `held deletions come out oldest first, which is the order a teardown must settle them in`() {
        val first = deletion("/tmp/.mimir-staging/.delete-first")
        val second = deletion("/tmp/.mimir-staging/.delete-second")
        var queue = emptyList<FileQueueEntry>()
        queue = FileOperationQueue.enqueue(queue, entry(id = 1, state = FileQueueState.UndoAvailable(first)))
        queue = FileOperationQueue.enqueue(queue, entry(id = 2, state = FileQueueState.Done))
        queue = FileOperationQueue.enqueue(queue, entry(id = 3, state = FileQueueState.UndoAvailable(second)))

        assertEquals(
            "newest first on screen, oldest first when settling",
            listOf(first, second),
            FileOperationQueue.heldDeletions(queue),
        )
    }

    @Test
    fun `newest operations sit at the top, matching the design's own ordering`() {
        var queue = emptyList<FileQueueEntry>()
        queue = FileOperationQueue.enqueue(queue, entry(id = 1))
        queue = FileOperationQueue.enqueue(queue, entry(id = 2))

        assertEquals(listOf(2L, 1L), queue.map { it.id })
    }

    @Test
    fun `chip labels are the design's own vocabulary`() {
        assertEquals("34%", FileOperationQueue.chipLabel(entry(state = FileQueueState.Running(0.34f))))
        assertEquals("Name taken", FileOperationQueue.chipLabel(entry(state = FileQueueState.AwaitingConflict)))
        assertEquals("Not available", FileOperationQueue.chipLabel(entry(state = FileQueueState.Blocked("7z"))))
        assertEquals("Done", FileOperationQueue.chipLabel(entry(state = FileQueueState.Done)))
        assertEquals(
            "a percentage must never render out of range, however a progress callback misbehaves",
            "100%",
            FileOperationQueue.chipLabel(entry(state = FileQueueState.Running(4f))),
        )
    }

    @Test
    fun `F-6 -- a Running card with no honest fraction gets a word, never a fabricated percentage`() {
        assertEquals(
            "null is not 0% -- it is the engine reporting a bookkeeping advance it never observed " +
                "(FileQueueState.Running's own KDoc). Rendering '0%' here would be exactly the fabrication " +
                "F-6 exists to remove.",
            "Working…",
            FileOperationQueue.chipLabel(entry(state = FileQueueState.Running(fraction = null))),
        )
    }

    @Test
    fun `the summary line carries the no-network claim the design makes`() {
        assertEquals("0 operations · nothing leaves this device", FileOperationQueue.summary(emptyList()))
        assertEquals("1 operation · nothing leaves this device", FileOperationQueue.summary(listOf(entry())))
    }

    @Test
    fun `the three conflict choices map onto the vendored resolutions, and ASK is not among them`() {
        assertEquals(
            listOf("Keep both", "Replace", "Skip"),
            FileConflictChoice.entries.map { it.label },
        )
        assertEquals(ConflictResolution.RENAME, FileConflictChoice.KEEP_BOTH.resolution)
        assertEquals(ConflictResolution.OVERWRITE, FileConflictChoice.REPLACE.resolution)
        assertEquals(ConflictResolution.SKIP, FileConflictChoice.SKIP.resolution)
        assertFalse(
            "ASK is the UNANSWERED state, not a choice a player can make -- offering it as a button " +
                "would mean resolving a conflict by deciding not to decide",
            FileConflictChoice.entries.any { it.resolution == ConflictResolution.ASK },
        )
    }
}
