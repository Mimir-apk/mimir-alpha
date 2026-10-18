package com.gruning.mimir.rewrite.core.files

import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2 alpha-cert O6 (2026-10-08): the extract card read "100%" for over 90 seconds while about 1,400 of
 * 2,000 files were still waiting to be placed. The placing half of the job speaks in counts.
 */
class FileOperationQueuePlacingTest {

    private fun chip(state: FileQueueState) = FileOperationQueue.chipLabel(
        FileQueueEntry(id = 1, kind = JobKind.COPY, title = "many-2000-files.zip", subtitle = "Extract", state = state),
    )

    @Test
    fun `the placing phase counts files instead of showing a percentage`() {
        assertEquals(
            "Placing files 600/2000",
            chip(FileQueueState.Running(fraction = 0.65f, placed = 600, placeTotal = 2000)),
        )
    }

    @Test
    fun `no placing state ever reads as a percentage, so none can read 100 before Done`() {
        for (placed in listOf(0, 1, 999, 1999, 2000)) {
            val label = chip(FileQueueState.Running(fraction = 1f, placed = placed, placeTotal = 2000))
            assertFalse("$placed/2000 must not look finished: $label", label.contains("%"))
        }
        assertEquals("Done", chip(FileQueueState.Done))
    }

    @Test
    fun `a running state without counts keeps its old words`() {
        assertEquals("Working…", chip(FileQueueState.Running(null)))
        assertEquals("37%", chip(FileQueueState.Running(0.37f)))
    }

    @Test
    fun `a placing state with no total is not a placing state`() {
        assertFalse(FileQueueState.Running(0.5f, placed = 3, placeTotal = 0).isPlacing)
        assertFalse(FileQueueState.Running(0.5f).isPlacing)
        assertTrue(FileQueueState.Running(0.5f, placed = 0, placeTotal = 5).isPlacing)
    }
}
