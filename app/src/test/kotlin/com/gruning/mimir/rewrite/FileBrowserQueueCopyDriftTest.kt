package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileOperation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's side of the Files Queue screen's translation (localization step 4b, item d).
 *
 * The store writes a card's title, second line and notes in English, and `feature-files` recognises each sentence by its frame and draws it in the player's language
 * (`FilesQueueText.kt`; `FilesQueueCardTextTest` there walks the same list). The two modules cannot see each other's internals, so this test is the other half of
 * the contract: every sentence below is written by the store exactly as `FilesQueueCardTextTest` expects it. **If one of these goes red, an English sentence in the
 * store was edited: change the matching frame and its strings in `feature-files` in the same commit, or the Spanish card quietly falls back to English.**
 *
 * The builders are called; the sentences the store writes inline (an `object` over a `Context` that no JVM test can drive) are pinned as source text.
 */
class FileBrowserQueueCopyDriftTest {

    private fun appSource(fileName: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }?.readText()
        ?: throw AssertionError("$fileName not reachable from the test working directory")

    private val store: String by lazy { appSource("FileBrowserStore.kt") }
    private val cards: String by lazy { appSource("FileBrowserCompanionCards.kt") }

    @Test
    fun `a stopped transfer writes the sentences the Queue screen knows`() {
        fun kept(operation: FileOperation, sources: Int, landed: Int, skipped: Int, companions: Int = 0) =
            cancelledTransferState(operation, sources, landed, skipped, "gba", companions).kept

        assertEquals("Nothing was moved. Everything is still where it was.", kept(FileOperation.MOVE, 3, 0, 0))
        assertEquals("Nothing was copied. The unfinished copy was removed.", kept(FileOperation.COPY, 3, 0, 0))
        assertEquals("Too late to stop: everything was already moved to gba.", kept(FileOperation.MOVE, 3, 3, 0))
        assertEquals("Too late to stop: everything was already copied to gba.", kept(FileOperation.COPY, 3, 2, 1))
        assertEquals(
            "Too late to stop: everything you picked was already moved to gba. The 1 file that goes with it is still where it was.",
            kept(FileOperation.MOVE, 3, 3, 0, companions = 1),
        )
        assertEquals(
            "Too late to stop: everything you picked was already copied to gba. The 12 files that go with it are still where they were.",
            kept(FileOperation.COPY, 3, 3, 0, companions = 12),
        )
        assertEquals(
            "834 of 900 files were moved to gba and kept. The rest are still where they were.",
            kept(FileOperation.MOVE, 900, 834, 0),
        )
        assertEquals(
            "1,234 of 2,000 files were copied to gba and kept. The rest were not copied.",
            kept(FileOperation.COPY, 2_000, 1_234, 0),
        )
    }

    @Test
    fun `a stopped extraction writes the sentences the Queue screen knows`() {
        assertEquals("Too late to stop: everything was already extracted.", cancelledExtractionState(null, allPlaced = true).kept)
        assertEquals("Nothing was extracted.", cancelledExtractionState(null, allPlaced = false).kept)
    }

    @Test
    fun `the lines and notes the builders write are the frames the Queue screen knows`() {
        assertEquals("Renamed Zelda.gba to Link.gba", renameSubtitle("Zelda.gba", "Link.gba"))
        assertEquals("Delete · saves and states kept", fileDeleteSubtitle(listOf("/a/x.srm")) { true })
        assertEquals("Delete", fileDeleteSubtitle(listOf("/a/saves")) { false })
        assertEquals("The disc it lists stays.", playlistStaysNote(playlists = 1, members = 1))
        assertEquals("The disc they list stays.", playlistStaysNote(playlists = 2, members = 1))
        assertEquals("The 3 discs it lists stay.", playlistStaysNote(playlists = 1, members = 3))
        assertEquals("The 3 discs they list stay.", playlistStaysNote(playlists = 2, members = 3))
        assertTrue("a note is joined to its card with the middot", cards.contains("\"\${entry.subtitle} · \$note\""))
        assertTrue(cards.contains("\"its cue lists it under that name\""))
        assertTrue(cards.contains("\"it only moves under its disc's name\""))
        assertTrue(cards.contains("\"\${File(left).name} stayed behind: \$why\""))
    }

    @Test
    fun `the sentences the store writes inline are the frames the Queue screen knows`() {
        listOf(
            "val label = if (paths.size == 1) File(paths.single()).name else \"\${paths.size} files\"",
            "val label = if (picked.size == 1) File(picked.single()).name else \"\${picked.size} files\"",
            "val verb = if (operation == FileOperation.MOVE) \"Moved\" else \"Copied\"",
            "\"\$verb from \$originFolder to \$destFolder\"",
            "\"\$verb \$label to \$destFolder\"",
            "\"Extract → \${File(destination).name}\"",
            "FileQueueState.Failed(\"Nothing to delete\")",
            "FileQueueState.Cancelled(\"Nothing was changed.\")",
            "\"\$it kept its old name\"",
            "\"A file named \$newName is already there.\"",
            "\"A file named \${error.conflicts.first().name} is already there.\"",
            "\"The file is back. Your library will re-add the game the next time it scans.\"",
            "\"That folder isn't one of your game sources, so it left your library.\"",
            "\"Your library doesn't list it as that platform's game any more.\"",
            "\"Mimir couldn't update your library — it'll catch up next time it scans.\"",
            "\"It was saved under a new name, so your library will re-add it on the next scan.\"",
        ).forEach { frame -> assertTrue("FileBrowserStore no longer writes: $frame", store.contains(frame)) }
    }
}
