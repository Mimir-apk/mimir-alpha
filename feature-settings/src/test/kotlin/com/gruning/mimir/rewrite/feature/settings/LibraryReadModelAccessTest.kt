package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alpha cert 2026-10-08, E3: which Library folders the Settings pane may call Available while "All files access" is off. */
class LibraryReadModelAccessTest {

    private val shared = SourceRow("Internal storage › Roms", "/sdcard/Roms", gameCount = 4, available = true, isPrimary = true)
    private val tree = SourceRow(
        "SD card › Games",
        "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AGames",
        gameCount = 80,
        available = true,
    )

    private fun model(vararg sources: SourceRow, allFilesAccess: Boolean) = LibraryReadModel(
        summary = LibrarySummary(gameCount = 84, platformCount = 3),
        sources = sources.toList(),
        allFilesAccess = allFilesAccess,
    )

    @Test
    fun `a shared folder is not readable without the grant even though its last scan was fine`() {
        val locked = model(shared, tree, allFilesAccess = false)

        assertFalse(locked.isReadable(shared))
        assertTrue("a SAF folder keeps its own answer", locked.isReadable(tree))
        assertTrue(locked.foldersLockedByFileAccess)
    }

    @Test
    fun `the same folders are readable once the grant is held`() {
        val open = model(shared, tree, allFilesAccess = true)

        assertTrue(open.isReadable(shared))
        assertFalse(open.foldersLockedByFileAccess)
        assertFalse(open.everyFolderLockedByFileAccess)
    }

    @Test
    fun `a folder that a scan found missing stays unavailable whatever the grant`() {
        val gone = shared.copy(available = false)

        assertFalse(model(gone, allFilesAccess = true).isReadable(gone))
    }

    @Test
    fun `RESCAN is held back only when every folder is locked`() {
        assertTrue(model(shared, allFilesAccess = false).everyFolderLockedByFileAccess)
        assertFalse("the SAF folder can still be rescanned", model(shared, tree, allFilesAccess = false).everyFolderLockedByFileAccess)
        assertFalse("no folders is not a locked library", model(allFilesAccess = false).everyFolderLockedByFileAccess)
    }

    @Test
    fun `the copy says plainly what is wrong and what to do`() {
        assertEquals(
            "Mimir can’t read your folders right now. Allow file access to rescan.",
            LibraryFilesAccessLostCopy,
        )
        assertEquals(
            "Mimir can’t read some of your folders right now. Allow file access to rescan.",
            LibraryFilesAccessLostSomeCopy,
        )
    }

    @Test
    fun `the note says some when part of the library is locked and your folders when all of it is`() {
        assertEquals(LibraryFilesAccessLostCopy, model(shared, allFilesAccess = false).fileAccessNote)
        assertEquals(LibraryFilesAccessLostSomeCopy, model(shared, tree, allFilesAccess = false).fileAccessNote)
        assertNull("nothing locked, nothing said", model(tree, allFilesAccess = false).fileAccessNote)
        assertNull("the grant is held", model(shared, tree, allFilesAccess = true).fileAccessNote)
    }

    @Test
    fun `another app's folder is locked without the grant and Mimir's own is not`() {
        val own = "com.gruning.mimir.rewrite"
        val other = SourceRow("Other", "/storage/emulated/0/Android/media/com.other.app/ROMs", gameCount = 7, available = true)
        val mine = SourceRow("Mine", "/storage/emulated/0/Android/media/$own/ROMs", gameCount = 9, available = true)
        val locked = model(other, mine, allFilesAccess = false).copy(appPackage = own)

        assertFalse(locked.isReadable(other))
        assertTrue(locked.isReadable(mine))
        assertTrue(locked.foldersLockedByFileAccess)
        assertFalse("with no package named nothing is exempt", model(mine, allFilesAccess = false).isReadable(mine))
    }
}
