package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySourceReconciliationTest {
    @Test
    fun `empty registry reports that there is nothing to rescan`() {
        val result = receipt()

        assertEquals("NO LIBRARY FOLDERS TO RESCAN", sourceReconcileFeedback(result))
        assertFalse(sourceReconcileChangedLibrary(result))
    }

    @Test
    fun `mixed receipt exposes every typed terminal state`() {
        val result = receipt(
            sourcesChecked = 4,
            healthySources = 1,
            removedSources = 1,
            unavailableSources = 1,
            failedSources = 1,
        )

        assertEquals(
            "4 CHECKED · 1 READY · 1 UNAVAILABLE · 1 FAILED · 1 REMOVED",
            sourceReconcileFeedback(result),
        )
        assertTrue(sourceReconcileChangedLibrary(result))
    }

    @Test
    fun `preserved failures do not claim that the library index changed`() {
        val result = receipt(
            sourcesChecked = 2,
            unavailableSources = 1,
            failedSources = 1,
        )

        assertEquals(
            "2 CHECKED · 0 READY · 1 UNAVAILABLE · 1 FAILED · 0 REMOVED",
            sourceReconcileFeedback(result),
        )
        assertFalse(sourceReconcileChangedLibrary(result))
    }

    private fun receipt(
        sourcesChecked: Int = 0,
        healthySources: Int = 0,
        removedSources: Int = 0,
        unavailableSources: Int = 0,
        failedSources: Int = 0,
    ) = SourceReconcileResult(
        sourcesChecked = sourcesChecked,
        healthySources = healthySources,
        removedSources = removedSources,
        filesSeen = 0,
        recognized = 0,
        groups = 0,
        skipped = 0,
        unavailableSources = unavailableSources,
        failedSources = failedSources,
    )
}
