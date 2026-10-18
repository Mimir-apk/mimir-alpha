package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileConflict
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileOperationConflictException
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling 4, 2026-08-14 — contract A's CALL SITE, not just its mechanism.
 *
 * `FileBrowserController.refresh()` was pinned in `feature-files`, but the line in
 * [FileBrowserStore] that actually calls it after a successful extraction was pinned by nothing:
 * deleting it failed no test, and extraction-visibility would have regressed silently. The store's
 * outcome handling was lifted into [FileBrowserStore.runExtraction] so a JVM test can drive it.
 *
 * What a player loses without this: they extract a zip, the archive's files land on disk, and the
 * listing keeps showing the pre-extraction rows until they back out of the directory and come back.
 * It reads as "extract did nothing".
 */
class FileBrowserExtractRefreshTest {

    private fun item(name: String) = FileItem(name = name, path = "/roms/$name")

    /** Counts how many times the controller re-read its directory. */
    private class CountingListing(var rows: List<FileItem>) {
        var reads: Int = 0
        fun list(@Suppress("UNUSED_PARAMETER") path: String): List<FileItem> {
            reads += 1
            return rows
        }
    }

    private fun controllerOver(listing: CountingListing): FileBrowserController =
        FileBrowserController(
            authority = DefaultFocusAuthority(),
            rootPath = "/roms",
            mode = FileBrowserMode.MANAGE,
            listDirectory = listing::list,
            onChoose = {},
            onDismiss = {},
        )

    @Test
    fun `a successful extraction re-reads the directory so the new files appear`() = runBlocking {
        val listing = CountingListing(listOf(item("pack.zip")))
        val controller = controllerOver(listing)
        controller.open()
        val readsAfterOpen = listing.reads

        // The archive's contents land on disk while the browser is sitting on this directory.
        listing.rows = listing.rows + item("rom.gba")

        var extracted = false
        FileBrowserStore.runExtraction(
            archivePath = "/roms/pack.zip",
            destination = "/roms/pack",
            extract = { _, _ -> Result.success(1) },
            currentController = { controller },
            onExtracted = { _, _, _ -> extracted = true },
            onExtractFailed = { _, _ -> },
        )

        assertTrue("the success callback must still fire", extracted)
        assertEquals(
            "a successful extraction must re-read the directory it wrote into -- without it the " +
                "extracted files stay invisible until the player backs out and returns, which reads " +
                "as \"extract did nothing\"",
            readsAfterOpen + 1,
            listing.reads,
        )
        controller.close()
    }

    @Test
    fun `a failed extraction reports the error and does not re-read`() = runBlocking {
        val listing = CountingListing(listOf(item("pack.zip")))
        val controller = controllerOver(listing)
        controller.open()
        val readsAfterOpen = listing.reads

        var failure: Throwable? = null
        FileBrowserStore.runExtraction(
            archivePath = "/roms/pack.zip",
            destination = "/roms/pack",
            extract = { _, _ -> Result.failure(IllegalStateException("bad archive")) },
            currentController = { controller },
            onExtracted = { _, _, _ -> },
            onExtractFailed = { _, error -> failure = error },
        )

        assertEquals("bad archive", failure?.message)
        assertEquals(
            "nothing was written, so there is nothing to re-read -- a failed extraction must not " +
                "disturb the listing the player is looking at",
            readsAfterOpen,
            listing.reads,
        )
        controller.close()
    }

    @Test
    fun `extraction survives the browser being closed mid-flight`() = runBlocking {
        var extracted = false
        FileBrowserStore.runExtraction(
            archivePath = "/roms/pack.zip",
            destination = "/roms/pack",
            extract = { _, _ -> Result.success(3) },
            // The player closed the browser while the archive was still being written.
            currentController = { null },
            onExtracted = { _, _, _ -> extracted = true },
            onExtractFailed = { _, _ -> },
        )
        assertTrue("the extraction still completed and reported its count", extracted)
    }

    // ---- D-1, extract's conflict card ----
    //
    // `ArchiveHelper.extract` now defaults to `ConflictResolution.ASK`, which REFUSES a collision by
    // throwing `FileOperationConflictException` -- nothing is written. `runExtraction` must route that
    // one exception type to `onConflict` instead of `onExtractFailed`, so `FileBrowserStore.dispatchExtraction`
    // can park the queue card as `AwaitingConflict` rather than finishing it as a failure. Every other
    // failure must keep going to `onExtractFailed`, unchanged.

    private fun conflictOn(name: String) =
        FileOperationConflictException(listOf(FileConflict(sourcePath = name, targetPath = "/roms/pack/$name")))

    @Test
    fun `a conflict routes to onConflict when the caller supplies one, not to onExtractFailed`() = runBlocking {
        var conflict: FileOperationConflictException? = null
        var failure: Throwable? = null
        var extracted = false
        FileBrowserStore.runExtraction(
            archivePath = "/roms/pack.zip",
            destination = "/roms/pack",
            extract = { _, _ -> Result.failure(conflictOn("rom.gba")) },
            currentController = { null },
            onExtracted = { _, _, _ -> extracted = true },
            onExtractFailed = { _, error -> failure = error },
            onConflict = { error -> conflict = error },
        )
        assertTrue(
            "MANAGE's card-parking path must receive the conflict -- this is the hook " +
                "FileBrowserStore.dispatchExtraction parks an AwaitingConflict card from",
            conflict != null,
        )
        assertEquals("rom.gba", conflict?.conflicts?.single()?.name)
        assertFalse(
            "SAFETY: a conflict must not ALSO be reported as an ordinary failure -- a caller wired to " +
                "both would show the player two contradictory outcomes for one archive",
            failure != null,
        )
        assertFalse("nothing was written, so the success callback must not fire either", extracted)
    }

    @Test
    fun `a conflict falls back to onExtractFailed when no onConflict is supplied -- the PICK path`() = runBlocking {
        var failure: Throwable? = null
        FileBrowserStore.runExtraction(
            archivePath = "/roms/pack.zip",
            destination = "/roms/pack",
            extract = { _, _ -> Result.failure(conflictOn("rom.gba")) },
            currentController = { null },
            onExtracted = { _, _, _ -> },
            onExtractFailed = { _, error -> failure = error },
            // onConflict deliberately omitted: this is PICK's exact call shape (dispatchExtraction's
            // `id == null` branch never passes one), and PICK must keep refusing -- a collision it cannot
            // enqueue a card for must still surface as a real failure, never a silent overwrite.
        )
        assertTrue(
            "PICK's own default must forward a conflict into onExtractFailed -- without this, a " +
                "collision reaches neither callback and the player sees nothing at all",
            failure is FileOperationConflictException,
        )
        assertEquals("rom.gba", (failure as FileOperationConflictException).conflicts.single().name)
    }

    @Test
    fun `a non-conflict failure never reaches onConflict`() = runBlocking {
        var conflictSeen = false
        var failure: Throwable? = null
        FileBrowserStore.runExtraction(
            archivePath = "/roms/pack.zip",
            destination = "/roms/pack",
            extract = { _, _ -> Result.failure(IllegalStateException("bad archive")) },
            currentController = { null },
            onExtracted = { _, _, _ -> },
            onExtractFailed = { _, error -> failure = error },
            onConflict = { conflictSeen = true },
        )
        assertFalse(
            "SAFETY: only FileOperationConflictException may reach onConflict -- routing every failure " +
                "there would let dispatchExtraction park a card for an error nothing can actually retry",
            conflictSeen,
        )
        assertEquals("bad archive", failure?.message)
    }
}
