package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.ExtractionCancelOutcome
import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Cancel** and **Rename** in the Files store -- the parts of them that are
 * decisions rather than Android plumbing: which job a press reaches, what a stopped card says it kept, what
 * a finished rename offers. The wiring into the store (an `object` needing a Context) is pinned from source,
 * as every sibling test here does; the engine behaviour behind it is proved on real files in `core-files`.
 */
class FileBrowserJobControlTest {

    private val store: String by lazy { appSource("FileBrowserStore.kt").readText() }
    private val brightness: String by lazy { appSource("AppBrightnessCoordinator.kt").readText() }

    // ── which job a press reaches ────────────────────────────────────────────────────────────

    @Test
    fun `a press reaches only the job it names, once, and a stale press reaches nothing`() {
        val control = FileBrowserJobControl()
        val one = Job()
        val two = Job()
        control.track(1, one) {}
        control.track(2, two) {}

        assertFalse("an unknown id is a stale press", control.requestCancel(99))
        assertTrue(control.requestCancel(1))
        assertFalse("the job has ended, so a second press has nothing left to reach", control.requestCancel(1))

        assertTrue(one.isCancelled)
        assertFalse("the other job is untouched", two.isCancelled)
    }

    @Test
    fun `a finished job is forgotten, so a late press on its card does nothing`() {
        val control = FileBrowserJobControl()
        val job = Job()
        control.track(1, job) {}
        job.complete()

        assertFalse(control.requestCancel(1))
        assertFalse(control.isCancelling(1))
    }

    @Test
    fun `a job cancelled before its body ever ran is settled, not left on Cancelling forever`() {
        val control = FileBrowserJobControl()
        val job = Job()
        var abandoned = 0
        control.track(1, job) { abandoned++ }

        control.requestCancel(1)

        assertEquals("the completion callback fired once", 1, abandoned)
        assertFalse("and the flag is cleared with the job", control.isCancelling(1))
    }

    @Test
    fun `a job that finishes normally never reports itself cancelled`() {
        val control = FileBrowserJobControl()
        val job = Job()
        var abandoned = 0
        control.track(1, job) { abandoned++ }

        job.complete()

        assertEquals(0, abandoned)
    }

    @Test
    fun `a progress report after Cancel keeps the card on Cancelling`() {
        val control = FileBrowserJobControl()
        val running = FileQueueState.Running(0.5f)
        runBlocking {
            // A job whose body is inside NonCancellable is cancelling, not yet cancelled: exactly the window
            // in which a late progress callback can land.
            val gate = CompletableDeferred<Unit>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { gate.await() } }
            control.track(5, job) {}

            assertEquals("nothing requested: untouched", running, control.keepCancelling(5, running))

            control.requestCancel(5)
            assertTrue(control.isCancelling(5))
            assertEquals(running.copy(cancelling = true), control.keepCancelling(5, running))
            assertEquals(
                "settled states are never rewritten",
                FileQueueState.Done,
                control.keepCancelling(5, FileQueueState.Done),
            )

            gate.complete(Unit)
            job.join()
            assertFalse("the flag goes with the job", control.isCancelling(5))
            assertEquals(running, control.keepCancelling(5, running))
        }
    }

    // ── what a stopped copy or move says ─────────────────────────────────────────────────────

    private fun transferText(operation: FileOperation, total: Int, landed: Int, skipped: Int = 0) =
        cancelledTransferState(operation, total, landed, skipped, "gba").kept

    @Test
    fun `a stopped move says what moved and that the rest never left`() {
        assertEquals(
            "Nothing was moved. Everything is still where it was.",
            transferText(FileOperation.MOVE, total = 3, landed = 0),
        )
        assertEquals(
            "2 of 5 files were moved to gba and kept. The rest are still where they were.",
            transferText(FileOperation.MOVE, total = 5, landed = 2),
        )
        assertEquals(
            "Too late to stop: everything was already moved to gba.",
            transferText(FileOperation.MOVE, total = 3, landed = 3),
        )
        assertEquals(
            "a single file that landed as the stop arrived",
            "Too late to stop: everything was already moved to gba.",
            transferText(FileOperation.MOVE, total = 1, landed = 1),
        )
    }

    @Test
    fun `a stopped copy says its unfinished file was removed`() {
        assertEquals(
            "Nothing was copied. The unfinished copy was removed.",
            transferText(FileOperation.COPY, total = 1, landed = 0),
        )
        assertEquals(
            "1,200 of 2,000 files were copied to gba and kept. The rest were not copied.",
            transferText(FileOperation.COPY, total = 2000, landed = 1200),
        )
    }

    @Test
    fun `sources the player chose to skip count as handled, not as missing`() {
        assertEquals(
            "Too late to stop: everything was already moved to gba.",
            transferText(FileOperation.MOVE, total = 3, landed = 2, skipped = 1),
        )
        assertEquals(
            "1 of 3 files were moved to gba and kept. The rest are still where they were.",
            transferText(FileOperation.MOVE, total = 3, landed = 1, skipped = 0),
        )
    }

    @Test
    fun `a cancelled transfer is a Cancelled state, never a Failed or a Done`() {
        val state = cancelledTransferState(FileOperation.MOVE, 2, 1, 0, "gba")
        assertTrue(state is FileQueueState.Cancelled)
        assertFalse(state.kept.isBlank())
    }

    // ── what a stopped extract says ──────────────────────────────────────────────────────────

    @Test
    fun `a stopped extract repeats what the engine's rollback found`() {
        val stopped = ExtractionCancelOutcome(removed = 626, remaining = 0, folder = "many-2000-files")
        assertEquals(stopped.sentence, cancelledExtractionState(stopped, allPlaced = false).kept)
        assertEquals(
            "Nothing was extracted. The 626 files already placed were removed again.",
            cancelledExtractionState(stopped, allPlaced = false).kept,
        )
    }

    @Test
    fun `without the engine's answer the card tells the truth from the last placement report`() {
        assertEquals("Nothing was extracted.", cancelledExtractionState(null, allPlaced = false).kept)
        assertEquals(
            "Too late to stop: everything was already extracted.",
            cancelledExtractionState(null, allPlaced = true).kept,
        )
    }

    // ── a finished rename ────────────────────────────────────────────────────────────────────

    @Test
    fun `a finished rename carries the old name so Undo can put it back`() {
        val state = renameSettledState(File("/sd/Roms/Mario.gba"), "/sd/Roms/Super Mario.gba", undoable = true)

        val reverse = (state as FileQueueState.ReverseAvailable).reverse
        assertEquals(listOf("/sd/Roms/Super Mario.gba"), reverse.landedPaths)
        assertEquals("Mario.gba", reverse.previousName)
        assertEquals(File("/sd/Roms").path, File(reverse.originFolder).path)
    }

    @Test
    fun `the rename-back an Undo performs is Done and does not offer to undo the undo`() {
        assertEquals(
            FileQueueState.Done,
            renameSettledState(File("/sd/Roms/Super Mario.gba"), "/sd/Roms/Mario.gba", undoable = false),
        )
    }

    @Test
    fun `a rename card reads as what happened, in the past tense, for its whole life`() {
        assertEquals("Renamed Mario.gba to Super Mario.gba", renameSubtitle("Mario.gba", "Super Mario.gba"))
        assertNull(ReversibleMove(listOf("/a/b"), "/a").previousName)
    }

    // ── the wiring, pinned from source ───────────────────────────────────────────────────────

    @Test
    fun `the browser is built with a rename sink and a cancel sink`() {
        val construction = store.substringAfter("mutableController.value = FileBrowserController(")
            .substringBefore("\n        )")
        assertTrue(construction.contains("onRename = { path, newName -> renameInManager(path, newName) }"))
        assertTrue(construction.contains("onCancelEntry = { entryId -> cancel(entryId) }"))
    }

    @Test
    fun `a transfer's coroutine is tracked, its cancellation settles the card, and a landed move tells the library`() {
        val body = store.substringAfter("private fun transfer(")
            .substringBefore("internal fun commonParentFolderName(")
        assertTrue("the job is what Cancel stops", body.contains("val job = scope.launch {"))
        assertTrue(body.contains("jobControl.track(id, job)"))
        assertTrue(
            "the engine call is guarded, or a cancelled job leaves its card on Running for good",
            body.contains("} catch (_: CancellationException) {") &&
                body.contains("settleCancelledTransfer(id, operation, picked, paths, destination, landings, skippedSources)"),
        )
        assertTrue(
            "a copy reports where each file landed too, so a stopped copy can say how many it kept",
            body.contains("ops.copy(") && body.contains("landings[source] = landed"),
        )

        val settle = store.substringAfter("private fun settleCancelledTransfer(")
            .substringBefore("fun renameInManager(")
        assertTrue(
            "a move that landed something before the stop owes the library what a finished move does",
            settle.contains("tellLibraryTheFilesMoved(") && settle.contains("session?.onMoved?.invoke("),
        )
        assertTrue(settle.contains("cancelledTransferCard("))
    }

    @Test
    fun `an extraction's coroutine is tracked and its cancellation is settled from the engine's own answer`() {
        val body = store.substringAfter("private fun dispatchExtraction(")
            .substringBefore("// ---- F-08: search ----")
        assertTrue(body.contains("val job = scope.launch {"))
        assertTrue(body.contains("jobControl.track(id, job)"))
        assertTrue(body.contains("} catch (_: CancellationException) {"))
        assertTrue(
            "a cancellation from the calling job beats any exception the engine throws, so the engine's answer " +
                "comes back through a callback, not through the exception",
            body.contains("onCancelled = { cancelOutcome = it }") && body.contains("cancelOutcome,"),
        )
        assertTrue(
            "the stop is written BEFORE the post-run sweep, which cannot suspend on a cancelled job",
            body.indexOf("catch (_: CancellationException)") < body.indexOf("enqueueInterruptedExtractions("),
        )
    }

    @Test
    fun `progress written after a Cancel press cannot clear Cancelling`() {
        val setState = store.substringAfter("private fun setState(id: Long, state: FileQueueState) {")
            .substringBefore("\n    }")
        assertTrue(setState.contains("jobControl.keepCancelling(id, state)"))
    }

    @Test
    fun `Undo on a rename renames back instead of moving`() {
        val undoMove = store.substringAfter("private fun undoMove(entryId: Long, reverse: ReversibleMove) {")
            .substringBefore("operations ?: return")
        assertTrue(undoMove.contains("reverse.previousName?.let { return undoRename(entryId, reverse, it) }"))
    }

    @Test
    fun `a rename that hits a taken name is parked and answered through the same conflict card`() {
        val resolve = store.substringAfter("fun resolveConflict(entryId: Long, choice: FileConflictChoice) {")
            .substringBefore("private class PendingTransfer(")
        assertTrue(resolve.contains("pendingRenames.remove(entryId)"))
        assertTrue(
            "Skip on a rename just drops the card; the other two re-run the rename with the player's own choice",
            resolve.contains("if (choice != FileConflictChoice.SKIP) {") &&
                resolve.contains("rename(pending.path, pending.newName, choice, undoable = true, undoing = pending.undoing)"),
        )
    }

    @Test
    fun `a finished rename tells the library to look again, as a move does`() {
        val fields = store.substringAfter("private class Session(").substringBefore("\n    )")
        assertTrue(fields.contains("val onRenamed:"))
        val construction = store.substringAfter("val opened = Session(").substringBefore("\n        )")
        assertTrue(construction.contains("onRenamed = onRenamed,"))
        val rename = store.substringAfter("private fun rename(path: String, newName: String")
            .substringBefore("private fun undoRename(")
        assertTrue(rename.contains("session?.onRenamed?.invoke(path, landed)"))

        val call = brightness.substringAfter("FileBrowserStore.open(").substringBefore("\n        )")
        val arm = call.substringAfter("onRenamed = ").substringBefore("onCopied = ")
        assertTrue(
            "without the rescan a ROM renamed inside a library folder leaves a stale row pointing at a name " +
                "that no longer exists. Arm was:\n$arm",
            arm.contains("kickTransferRescan(listOf(oldPath), File(newPath).parent ?: newPath)"),
        )
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}
