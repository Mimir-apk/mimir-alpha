package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix round after the E2 review, 2026-10-08: the store wiring that a JVM test cannot drive (the store is an
 * `object` over a `Context` with no Robolectric harness here), pinned at source level. Every pin carries a
 * sanity assert so it cannot go vacuous, and every one was run against the old text first.
 *
 *  - **"Deleting…" renders.** A held delete is put back into its folder's listing on the way OUT of the
 *    listing cache, never into it.
 *  - **Progress is posted to Main.** The engine reports from its own thread; the queue is written on Main, and
 *    a report after the card settled must not overwrite it.
 *  - **A Cancel that lands too late still tells the library.**
 *  - **The BIOS badge asks the cheap question.**
 */
class FileBrowserFixRoundWiringTest {

    private fun stripped(fileName: String): String =
        appSource(fileName).readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)//.*$"), "")

    private val store: String by lazy { stripped("FileBrowserStore.kt") }

    private fun bodyFrom(source: String, signature: String, until: String): String {
        assertTrue("$signature not found", source.contains(signature))
        return source.substringAfter(signature).substringBefore(until)
    }

    // ---- "Deleting…" renders ----

    @Test
    fun `both listing paths put the held deletes into what they return, and neither puts them in the cache`() {
        val sync = bodyFrom(store, "private fun listDirectory(", "private fun sweepInterruptedOperations(")
        val async = bodyFrom(store, "private suspend fun listDirectoryAsync(", "private fun withHeldDeletes(")

        assertTrue("the first, synchronous listing shows held deletes", sync.contains("withHeldDeletes(path,"))
        assertTrue("a cache hit shows held deletes", async.contains("return withHeldDeletes(path, cached)"))
        assertTrue("a cold read shows held deletes", async.contains("withHeldDeletes(path, repository.listFiles(path)"))
        assertTrue(
            "the cache is written with what the DISK says, inside the overlay's argument, not with its result",
            sync.contains("}.also { listingCache[path] = it })") &&
                async.contains(".also { listingCache[path] = it })"),
        )
    }

    @Test
    fun `the overlay is built from the queue's held list, not from the staging folder`() {
        val body = bodyFrom(store, "private fun withHeldDeletes(", "private fun revalidate(")

        assertTrue(body.contains("HeldDeleteListing.overlay(path, rows, FileOperationQueue.heldDeletions(mutableQueue.value))"))
        assertFalse("the staging folder stays hidden: nothing here lists it", body.contains("mimir-staging"))
        assertFalse("and the cache is not touched from here", body.contains("listingCache"))
    }

    @Test
    fun `releasing an Undo refreshes the folder AFTER the queue entry is gone, so the row leaves at once`() {
        val body = bodyFrom(store, "fun releaseUndo(", "fun dismiss(")
        val removed = body.indexOf("FileOperationQueue.remove(mutableQueue.value, entryId)")
        val refreshed = body.indexOf("mutableController.value?.refresh()")

        assertTrue("both must be present for the order to mean anything", removed >= 0 && refreshed >= 0)
        assertTrue("the overlay reads the queue, so the entry has to be gone before the re-read", removed < refreshed)
    }

    @Test
    fun `an undo that failed keeps the card and says why instead of dropping it`() {
        val body = bodyFrom(store, "fun undo(entryId: Long) {", "fun releaseUndo(")
        val read = body.indexOf("result.exceptionOrNull()")
        val kept = body.indexOf("return@launch")
        val dropped = body.indexOf("FileOperationQueue.remove(mutableQueue.value, entryId)")

        assertTrue("the result of undoDelete is read, not discarded", body.contains("val result = ops.undoDelete(deletion)") && read >= 0)
        assertTrue("a failure writes the reason on the card", body.contains("it.copy(subtitle = error.playerFacingReason())"))
        assertTrue(
            "and leaves before the card is removed, so the held delete keeps its Undo",
            kept in (read + 1) until dropped,
        )
    }

    // ---- progress is posted to Main ----

    @Test
    fun `extraction progress is posted, never written from the engine's thread`() {
        val body = bodyFrom(store, "private fun dispatchExtraction(", "\n    private fun ")

        assertTrue(body.contains("postProgress(id, extractUnpackingState(done, total))"))
        assertTrue(body.contains("postProgress(id, extractPlacingState(placed, total))"))
        assertFalse("onProgress/onPlacing must not call setState directly", body.contains("setState(id, extractUnpackingState"))
        assertFalse(body.contains("setState(id, extractPlacingState"))
    }

    @Test
    fun `delete and transfer progress are posted too, never written from the engine's IO thread`() {
        val delete = bodyFrom(store, "fun deleteInManager(", "fun undo(")
        val transfer = bodyFrom(store, "private fun transfer(", "internal fun commonParentFolderName(")

        assertTrue(
            "stageDelete's callback posts its fraction",
            delete.contains("postProgress(id, FileQueueState.Running(if (total > 0) staged.toFloat() / total else null))"),
        )
        assertTrue(
            "the engine's byte reports are posted",
            transfer.contains("postProgress(id, FileQueueState.Running(if (total > 0) done.toFloat() / total else null))"),
        )
        assertTrue(
            "and so are its accounted advances",
            transfer.contains("postProgress(id, FileQueueState.Running(fraction = null))"),
        )
        assertFalse(
            "neither body may write a Running state with setState: those lambdas run on IO",
            delete.contains("setState(id, FileQueueState.Running(") || transfer.contains("setState(id, FileQueueState.Running("),
        )
    }

    @Test
    fun `a posted report lands on Main and only while the card is still running`() {
        val helper = bodyFrom(store, "private fun postProgress(", "private fun setState(")

        assertTrue("posted to the store's Main scope", helper.contains("scope.launch"))
        assertTrue(
            "and dropped once the card settled, so a late report cannot overwrite Done, Failed or Cancelled",
            helper.contains("?.state is FileQueueState.Running"),
        )
    }

    // ---- a disc's libcrypt companions (#230, #231) ----
    //
    // What the companions ARE and when they go is run against real files in DiscCompanionFilesTest,
    // ManagedFileOperationsFollowersTest and FileBrowserCompanionRenameTest; these pin only that the store asks.

    @Test
    fun `move, delete and rename each ask the one companion resolver, off Main, and count only what was picked`() {
        val move = bodyFrom(store, "fun moveInManager(", "private fun undoMove(")
        val delete = bodyFrom(store, "fun deleteInManager(", "fun undo(")
        val transfer = bodyFrom(store, "private fun transfer(", "internal fun commonParentFolderName(")
        val rename = bodyFrom(store, "private fun rename(path: String, newName: String", "private fun undoRename(")

        assertTrue(
            "a move carries the companions -- 'Move just this one' reaches the same call",
            move.contains("transfer(FileOperation.MOVE, paths, destination, choice = null, carryCompanions = true)"),
        )
        assertTrue(
            "the move looks them up inside its job, in the IO dispatcher",
            transfer.contains("withContext(Dispatchers.IO) { DiscCompanionFiles.companionSet(picked) }") &&
                !transfer.substringBefore("val job = scope.launch {").contains("withCompanions"),
        )
        assertTrue(
            "and they travel as followers of their disc, so Keep both cannot rename one apart from it",
            transfer.contains("companionSet?.let(::moveFollowersOf)") && transfer.contains("ops.move(") && transfer.contains("followers,"),
        )
        assertTrue(
            "the delete stages the disc and its companions in one held delete, looked up in the IO dispatcher",
            delete.contains("val plan = withContext(Dispatchers.IO) { planDelete(paths) }") &&
                delete.contains("ops.stageDelete(everything) {") &&
                !delete.substringBefore("scope.launch").contains("planDelete"),
        )
        assertTrue(
            "the cards count and name what the player picked, not what rode along",
            delete.substringBefore("scope.launch").contains("val label = if (paths.size == 1)") &&
                transfer.substringBefore("val job = scope.launch {").contains("val label = if (picked.size == 1)"),
        )
        assertTrue(
            "a rename goes through renameWithCompanions and records what it renamed on the Undo handle",
            rename.contains("renameWithCompanions(ops, path, newName,") &&
                rename.contains("renameSettledState(from, landed, undoable, renamed.companionRenames)"),
        )
        assertTrue(
            "and Undo reverses exactly that record",
            bodyFrom(store, "private fun undoRename(", "fun moveInManager(").contains("undoing = reverse"),
        )
    }

    // ---- a Cancel that lands too late ----

    @Test
    fun `a cancel that landed after the last file was placed still tells the library`() {
        val cancel = store.substringAfter("private fun dispatchExtraction(")
            .substringAfter("catch (_: CancellationException)")
            .substringBefore("return@launch")

        assertTrue(
            "the files are there, so session.onExtracted (which rescans the library) must run",
            cancel.contains("session.onExtracted(archivePath, destination, lastPlaceTotal)"),
        )
        assertTrue(
            "only when no rollback report says the job was taken back",
            cancel.contains("cancelOutcome == null && lastPlaceTotal > 0 && lastPlaced >= lastPlaceTotal"),
        )
    }

    // ---- the BIOS badge ----

    @Test
    fun `the BIOS badge asks the resolver's cheap check, not a full title resolve per row`() {
        val source = stripped("FileBrowserRomFolders.kt")
        val body = bodyFrom(source, "fun isBiosPayload(item: FileItem): Boolean {", "private val resolver")

        assertTrue(body.contains("resolver.isBiosPayload("))
        assertFalse("a full resolve per row is the cost this removes", body.contains("resolver.resolve("))
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}
