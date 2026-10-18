package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileOperationQueue
import com.gruning.mimir.rewrite.core.files.FileOperationStaging
import com.gruning.mimir.rewrite.core.files.InterruptedExtraction
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import com.gruning.mimir.rewrite.core.resolver.scan.MIMIR_STAGING_FOLDER_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2 alpha-cert O6 and P2 (2026-10-08), as the queue card sees them.
 */
class FileBrowserExtractProgressTest {

    private fun chip(state: FileQueueState) = FileOperationQueue.chipLabel(
        FileQueueEntry(1, JobKind.COPY, "many-2000-files.zip", "Extract", state),
    )

    @Test
    fun `unpacking fills only the first half of the bar`() {
        assertEquals(0f, extractUnpackingState(0, 100).fraction!!, 0f)
        assertEquals(0.25f, extractUnpackingState(50, 100).fraction!!, 0.0001f)
        assertEquals(
            "the moment every byte is unpacked, nothing is where it was asked for yet: half, not 100%",
            0.5f,
            extractUnpackingState(100, 100).fraction!!,
            0.0001f,
        )
        assertEquals("50%", chip(extractUnpackingState(100, 100)))
    }

    @Test
    fun `an archive of unknown size draws no bar rather than a made-up one`() {
        assertNull(extractUnpackingState(10, -1).fraction)
        assertNull(extractUnpackingState(0, 0).fraction)
        assertEquals("Working…", chip(extractUnpackingState(10, -1)))
    }

    @Test
    fun `placing counts files and fills the second half`() {
        val state = extractPlacingState(600, 2000)

        assertEquals("Placing files 600/2000", chip(state))
        assertEquals(0.65f, state.fraction!!, 0.0001f)
    }

    @Test
    fun `the card never reads 100 percent while files are still being placed`() {
        for (placed in 0..2000 step 50) {
            val label = chip(extractPlacingState(placed, 2000))
            assertFalse("$placed/2000 read as $label", label.contains("%"))
        }
        assertTrue(
            "the bar is monotonic across the hand-over from unpacking to placing",
            extractPlacingState(0, 2000).fraction!! >= extractUnpackingState(100, 100).fraction!!,
        )
        assertEquals("Done", chip(FileQueueState.Done))
    }

    @Test
    fun `placing with nothing to place is just working`() {
        assertEquals("Working…", chip(extractPlacingState(0, 0)))
    }

    @Test
    fun `an interrupted extraction becomes a failed card with the sentence E2 asked for`() {
        var next = 40L
        val entries = interruptedExtractionEntries(
            listOf(InterruptedExtraction("many-2000-files.zip", "/storage/emulated/0/Download/many-2000-files", 626, 2000)),
        ) { next++ }

        val entry = entries.single()
        assertEquals(40L, entry.id)
        assertEquals("many-2000-files.zip", entry.title)
        assertEquals(
            "Interrupted: 626 of 2,000 files were extracted to many-2000-files. Those files were left in place.",
            entry.subtitle,
        )
        assertEquals(entry.subtitle, (entry.state as FileQueueState.Failed).reason)
        assertEquals("never tagged EXTRACT -- see FileBrowserQueueHostWiringTest", JobKind.COPY, entry.kind)
        assertEquals("Failed", FileOperationQueue.chipLabel(entry))
    }

    @Test
    fun `several interrupted extractions each get their own card and id`() {
        var next = 1L
        val entries = interruptedExtractionEntries(
            listOf(
                InterruptedExtraction("a.zip", "/x/a", 0, -1),
                InterruptedExtraction(null, "/x/b", -1, -1),
            ),
        ) { next++ }

        assertEquals(listOf(1L, 2L), entries.map { it.id })
        assertEquals("a.zip", entries[0].title)
        assertEquals("Extraction", entries[1].title)
    }

    /**
     * The library scan lives in `core-resolver`, which does not depend on `core-files`, so the staging
     * folder's name is written twice. This is the one place both are visible.
     */
    @Test
    fun `the library scan and the staging engine agree on the working folder's name`() {
        assertEquals(FileOperationStaging.ROOT_DIRECTORY, MIMIR_STAGING_FOLDER_NAME)
    }
}
