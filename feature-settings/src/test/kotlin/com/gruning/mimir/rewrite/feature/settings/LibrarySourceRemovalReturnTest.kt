package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.model.library.SourceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Audit 2026-10-15, F6: after Cancel on "Remove library folder?" the ring landed on ADD LIBRARY FOLDER, not on the REMOVE row it had left. The coordinator had one return row for both
 * ways out of the confirmation, and it was the neighbour's (the right row once the folder is gone, the wrong one when it is still listed).
 *
 * [librarySourceRemovalReturn] is the pure answer both exits read: Cancel returns to the pressed folder's own row, a removal to the neighbour that takes its place.
 */
class LibrarySourceRemovalReturnTest {

    private fun folder(name: String, primary: Boolean = false) =
        SourceRow(friendlyPath = "Internal storage/$name", rootUri = "test://source/$name", gameCount = 3, available = true, isPrimary = primary)

    private val primary = folder("ROMs", primary = true)
    private val a = folder("A")
    private val b = folder("B")
    private val c = folder("C")

    @Test
    fun `cancel returns to the row that was pressed, whichever folder it was`() {
        val sources = listOf(primary, a, b, c)
        listOf(a, b, c).forEach { pressed ->
            val back = librarySourceRemovalReturn(sources, pressed)
            assertEquals("Cancel lands on ${pressed.friendlyPath}'s own REMOVE row", librarySourceRemovalRowId(pressed.rootUri), back.onCancel)
            assertNotEquals("and never on ADD LIBRARY FOLDER", LibraryAddRowId, back.onCancel)
        }
    }

    @Test
    fun `cancel of the only folder is its own row, not ADD LIBRARY FOLDER (the reported case)`() {
        val back = librarySourceRemovalReturn(listOf(primary, a), a)
        assertEquals(librarySourceRemovalRowId(a.rootUri), back.onCancel)
        assertEquals("only a removal has nothing left to land on but ADD", LibraryAddRowId, back.onRemoved)
    }

    @Test
    fun `a removal goes to the folder after it, else the one before, else ADD LIBRARY FOLDER`() {
        val sources = listOf(primary, a, b, c)
        assertEquals("the middle folder hands over to the one below it", librarySourceRemovalRowId(c.rootUri), librarySourceRemovalReturn(sources, b).onRemoved)
        assertEquals("the first hands over to the second", librarySourceRemovalRowId(b.rootUri), librarySourceRemovalReturn(sources, a).onRemoved)
        assertEquals("the last hands over to the one above it", librarySourceRemovalRowId(b.rootUri), librarySourceRemovalReturn(sources, c).onRemoved)
        assertEquals(LibraryAddRowId, librarySourceRemovalReturn(listOf(primary, a), a).onRemoved)
    }

    @Test
    fun `the primary folder is never a neighbour, it has no REMOVE row`() {
        val sources = listOf(a, primary, b)
        assertEquals("the folder after A is B, the primary between them is skipped", librarySourceRemovalRowId(b.rootUri), librarySourceRemovalReturn(sources, a).onRemoved)
        assertEquals(librarySourceRemovalRowId(a.rootUri), librarySourceRemovalReturn(sources, b).onRemoved)
    }

    @Test
    fun `a folder the pane no longer lists still cancels to its own row`() {
        val back = librarySourceRemovalReturn(listOf(primary, a), b)
        assertEquals(librarySourceRemovalRowId(b.rootUri), back.onCancel)
        assertEquals(LibraryAddRowId, back.onRemoved)
    }
}
