package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.LibrarySourceRole
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySourceRoutingTest {
    @Test
    fun `Add Games asks for the primary when missing or unavailable`() {
        assertEquals(LibrarySourceRole.PRIMARY, libraryAddGamesSourceRole(emptyList()))
        assertEquals(
            LibrarySourceRole.PRIMARY,
            libraryAddGamesSourceRole(listOf(source("card", primary = true, available = false))),
        )
    }

    @Test
    fun `Add Games becomes additive only after a healthy primary exists`() {
        assertEquals(
            LibrarySourceRole.ADDITIONAL,
            libraryAddGamesSourceRole(
                listOf(
                    source("main", primary = true, available = true),
                    source("extra", primary = false, available = false),
                ),
            ),
        )
    }

    @Test
    fun `folder picker is blocked before launch while a source operation is active`() {
        val healthy = listOf(source("main", primary = true, available = true))

        assertEquals(
            LibraryFolderPickerRoute.BUSY,
            libraryAddGamesPickerRoute(healthy, sourceOperationRunning = true),
        )
        assertEquals(
            LibraryFolderPickerRoute.ADDITIONAL,
            libraryAddGamesPickerRoute(healthy, sourceOperationRunning = false),
        )
        assertEquals(
            LibraryFolderPickerRoute.PRIMARY,
            libraryAddGamesPickerRoute(emptyList(), sourceOperationRunning = false),
        )
    }

    private fun source(name: String, primary: Boolean, available: Boolean) = SourceRow(
        friendlyPath = name,
        rootUri = "content://source/$name",
        gameCount = 1,
        available = available,
        isPrimary = primary,
    )
}
