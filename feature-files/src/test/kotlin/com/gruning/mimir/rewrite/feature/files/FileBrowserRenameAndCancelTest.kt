package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileOperationQueue
import com.gruning.mimir.rewrite.core.files.RenameProblem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Rename** in the Actions overlay (typed on Mimir's own keyboard) and **Cancel** on
 * a running copy, move or extract card.
 *
 * Drives a real [FileBrowserController] and [DefaultFocusAuthority]. Composes nothing: the module has no
 * Compose harness, so what the keyboard looks like is device-QA's, and what is proved here is the part that
 * decides things -- which rows exist, what a typed name does, what a Cancel press reaches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserRenameAndCancelTest {
    private val english = filesEnglish()

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true),
            FileItem(name = "Mario.gba", path = "/root/Mario.gba", extension = "gba"),
            FileItem(name = "Golden Sun.zip", path = "/root/Golden Sun.zip", extension = "zip"),
        ),
    )

    private val file = "/root/Mario.gba"
    private val directory = "/root/Roms"

    private class Sinks {
        val renamed = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<Long>()
    }

    private data class Fixture(
        val controller: FileBrowserController,
        val authority: DefaultFocusAuthority,
        val sinks: Sinks,
    )

    private fun open(
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
        renameWired: Boolean = true,
    ): Fixture {
        val sinks = Sinks()
        val authority = DefaultFocusAuthority()
        val c = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = mode,
            listDirectory = { path -> tree[path].orEmpty() },
            onChoose = {},
            onDismiss = {},
            onRename = if (renameWired) { path, name -> sinks.renamed += path to name } else null,
            onCancelEntry = { id -> sinks.cancelled += id },
        )
        c.open()
        return Fixture(c, authority, sinks)
    }

    // ── the overlay row ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Rename is a row for every kind of tile, directories included, between Copy and Extract`() {
        fun rows(kind: FileBrowserTileKind, transfer: Boolean = true, delete: Boolean = true) =
            fileBrowserActionOverlayRows(kind, delete, transfer, renameAvailable = true)

        assertEquals(
            listOf(
                FileBrowserActionOverlayRowKind.MOVE,
                FileBrowserActionOverlayRowKind.COPY,
                FileBrowserActionOverlayRowKind.RENAME,
                FileBrowserActionOverlayRowKind.DELETE,
            ),
            rows(FileBrowserTileKind.FILE),
        )
        assertEquals(
            listOf(
                FileBrowserActionOverlayRowKind.MOVE,
                FileBrowserActionOverlayRowKind.COPY,
                FileBrowserActionOverlayRowKind.RENAME,
                FileBrowserActionOverlayRowKind.EXTRACT,
                FileBrowserActionOverlayRowKind.DELETE,
            ),
            rows(FileBrowserTileKind.ARCHIVE),
        )
        assertEquals(
            "a folder is renamed in place; the runaway that keeps it out of Move/Copy is a copy into itself",
            listOf(FileBrowserActionOverlayRowKind.RENAME, FileBrowserActionOverlayRowKind.DELETE),
            rows(FileBrowserTileKind.DIRECTORY),
        )
        assertEquals(
            "Rename alone is enough to open the overlay for a folder",
            listOf(FileBrowserActionOverlayRowKind.RENAME),
            rows(FileBrowserTileKind.DIRECTORY, transfer = false, delete = false),
        )
    }

    @Test
    fun `without a rename sink there is no Rename row and the answer is the one it always was`() {
        assertEquals(
            emptyList<FileBrowserActionOverlayRowKind>(),
            fileBrowserActionOverlayRows(FileBrowserTileKind.DIRECTORY, deleteAvailable = false, transferChoiceAvailable = false),
        )
        val picker = open(mode = FileBrowserMode.PICK)
        assertFalse("PICK is incapable of renaming, whatever is wired", picker.controller.renameAvailable)
        val unwired = open(renameWired = false)
        assertFalse(unwired.controller.renameAvailable)
        assertTrue(open().controller.renameAvailable)
    }

    @Test
    fun `the overlay words Rename and the legend names the verb`() {
        assertEquals("Rename…", fileBrowserActionOverlayLabel(english, FileBrowserActionOverlayRowKind.RENAME))
        assertEquals(
            listOf("A" to "Rename", "B" to "Back"),
            fileBrowserActionOverlayFooterHints(english, FileBrowserActionOverlayRowKind.RENAME),
        )
    }

    // ── the rename session ───────────────────────────────────────────────────────────────────

    @Test
    fun `pressing Rename in the overlay raises the keyboard holding the current name`() {
        val f = open()
        f.controller.openActionOverlay(file, FileBrowserTileKind.FILE)
        f.controller.handleActionOverlayTap(
            FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.RENAME),
        )

        assertFalse("the overlay closes before the keyboard opens", f.controller.actionOverlayOpen)
        assertTrue(f.controller.renameOpen)
        assertEquals("Mario.gba", f.controller.renameText)
        assertEquals("Mario.gba", f.controller.renameOriginal)
        assertNull("nothing is wrong until the player changes something", f.controller.renameProblem)
        assertTrue("nothing is renamed by merely opening the keyboard", f.sinks.renamed.isEmpty())
    }

    @Test
    fun `Done hands a usable name to the sink and closes the keyboard`() {
        val f = open()
        f.controller.openRename(file)
        f.controller.setRenameText("  Super Mario  ")
        assertNull(f.controller.renameProblem)

        f.controller.confirmRename()

        assertEquals("the sink gets the path and the TIDIED name", listOf(file to "Super Mario"), f.sinks.renamed)
        assertFalse(f.controller.renameOpen)
    }

    @Test
    fun `a name that breaks a rule keeps the keyboard up and says which rule`() {
        val f = open()
        f.controller.openRename(file)

        f.controller.setRenameText("a/b.gba")
        assertEquals(RenameProblem.SEPARATOR, f.controller.renameProblem)
        f.controller.confirmRename()
        assertTrue("Done on a bad name does not close", f.controller.renameOpen)
        assertTrue("and fires nothing", f.sinks.renamed.isEmpty())

        f.controller.setRenameText("")
        assertEquals(RenameProblem.EMPTY, f.controller.renameProblem)
        f.controller.setRenameText("..")
        assertEquals(RenameProblem.DOTS, f.controller.renameProblem)
        f.controller.setRenameText("Mario.")
        assertEquals(RenameProblem.TRAILING_DOT, f.controller.renameProblem)
        f.controller.setRenameText("CON")
        assertEquals(RenameProblem.RESERVED, f.controller.renameProblem)
        f.controller.setRenameText("Mario.gba")
        assertEquals("typing the same name back is nothing to rename", RenameProblem.UNCHANGED, f.controller.renameProblem)
        f.controller.setRenameText("mario.gba")
        assertNull("a change of case alone is a rename", f.controller.renameProblem)
    }

    @Test
    fun `B abandons the rename and renames nothing`() {
        val f = open()
        f.controller.openRename(directory)
        f.controller.setRenameText("Games")

        assertTrue("the modal absorbs the press", f.controller.dispatch(ControllerAction.CANCEL))

        assertFalse(f.controller.renameOpen)
        assertTrue(f.sinks.renamed.isEmpty())
    }

    @Test
    fun `while the keyboard is up every press is absorbed, so the listing underneath cannot move`() {
        val f = open()
        f.controller.openRename(file)
        val before = f.controller.currentPath

        assertTrue(f.controller.dispatch(ControllerAction.NAV_DOWN))
        assertTrue(f.controller.dispatch(ControllerAction.CONFIRM))

        assertEquals(before, f.controller.currentPath)
        assertTrue(f.controller.renameOpen)
        assertTrue(f.sinks.renamed.isEmpty())
    }

    @Test
    fun `closing the browser abandons a rename in progress`() {
        val f = open()
        f.controller.openRename(file)

        f.controller.close()

        assertFalse(f.controller.renameOpen)
        assertTrue(f.sinks.renamed.isEmpty())
    }

    @Test
    fun `the picker cannot open a rename at all`() {
        val f = open(mode = FileBrowserMode.PICK)
        f.controller.openRename(file)
        assertFalse(f.controller.renameOpen)
    }

    // ── Cancel ───────────────────────────────────────────────────────────────────────────────

    private fun entry(kind: JobKind, state: FileQueueState, id: Long = 7) =
        FileQueueEntry(id = id, kind = kind, title = "Mario.gba", subtitle = "Moved from Roms to gba", state = state)

    @Test
    fun `a running copy, move or extract carries Cancel and nothing else`() {
        listOf(JobKind.COPY, JobKind.MOVE).forEach { kind ->
            assertEquals(
                kind.name,
                listOf(FileBrowserQueueAction.CANCEL),
                fileBrowserQueueActions(entry(kind, FileQueueState.Running(0.4f))),
            )
            assertEquals(
                "an extract is tagged COPY by the store, so it is covered; an unmeasured card too",
                listOf(FileBrowserQueueAction.CANCEL),
                fileBrowserQueueActions(entry(kind, FileQueueState.Running(fraction = null))),
            )
        }
        assertEquals(
            listOf(FileBrowserQueueAction.CANCEL),
            fileBrowserQueueCards(listOf(entry(JobKind.COPY, FileQueueState.Running(0.4f)))).single().actions,
        )
    }

    @Test
    fun `a running delete has nothing to cancel, and a card already stopping offers nothing more`() {
        assertEquals(
            emptyList<FileBrowserQueueAction>(),
            fileBrowserQueueActions(entry(JobKind.DELETE, FileQueueState.Running(0.4f))),
        )
        assertEquals(
            "Cancelling… is an answer, not a second button",
            emptyList<FileBrowserQueueAction>(),
            fileBrowserQueueActions(entry(JobKind.MOVE, FileQueueState.Running(0.4f, cancelling = true))),
        )
        assertEquals(
            "the state-only signature still gives Running nothing: the kind lives on the entry",
            emptyList<FileBrowserQueueAction>(),
            fileBrowserQueueActions(FileQueueState.Running(0.4f)),
        )
    }

    @Test
    fun `Cancel is the lead and only button of a running card, and its legend word is Cancel`() {
        val card = fileBrowserQueueCards(listOf(entry(JobKind.MOVE, FileQueueState.Running(0.2f)))).single()
        assertEquals(FileBrowserQueueAction.CANCEL, fileBrowserQueueLeadAction(card))
        assertEquals("Cancel", english.getString(FileBrowserQueueAction.CANCEL.labelRes))
        assertEquals(
            listOf("A" to "Cancel", "B" to "Back"),
            fileBrowserQueueFooterHints(english, focusedAction = FileBrowserQueueAction.CANCEL),
        )
        assertEquals(
            listOf(FileBrowserQueueFocus.actionKey(7, FileBrowserQueueAction.CANCEL)),
            fileBrowserQueueKeys(listOf(card)),
        )
    }

    @Test
    fun `pressing Cancel reaches the store's cancel with the entry id and no other entry point`() {
        val cancelled = mutableListOf<Long>()
        val others = mutableListOf<String>()
        fileBrowserQueueDispatch(
            target = FileBrowserQueueTarget(7, FileBrowserQueueAction.CANCEL),
            onUndo = { others += "undo" },
            onReleaseUndo = { others += "release" },
            onDismiss = { others += "dismiss" },
            onResolveConflict = { _, _ -> others += "resolve" },
            onCancel = { cancelled += it },
        )
        assertEquals(listOf(7L), cancelled)
        assertTrue(others.isEmpty())
    }

    @Test
    fun `the controller's cancel sink is the one the queue screen reaches`() {
        val f = open()
        f.controller.cancelEntry(9)
        assertEquals(listOf(9L), f.sinks.cancelled)
    }

    @Test
    fun `a stopped card says Cancelled with what was kept, and can be dismissed`() {
        val kept = "2 of 5 files were moved to gba and kept. The rest are still where they were."
        val stopped = entry(JobKind.MOVE, FileQueueState.Cancelled(kept))
        val card = fileBrowserQueueCards(listOf(stopped)).single()

        assertEquals("Cancelled", fileBrowserQueueChipLabel(english, card.state))
        assertEquals(kept, card.subtitle)
        assertEquals("nothing went wrong, so it is not drawn as an alarm", FileBrowserQueueTone.NEUTRAL, card.tone)
        assertEquals(listOf(FileBrowserQueueAction.DISMISS), card.actions)
        assertNull("no bar under a stopped card", card.progress)
        assertEquals("Cancelling…", fileBrowserQueueChipLabel(english, FileQueueState.Running(0.5f, cancelling = true)))
    }

    @Test
    fun `a blank kept line falls back to the line the store wrote`() {
        val card = fileBrowserQueueCards(listOf(entry(JobKind.COPY, FileQueueState.Cancelled("")))).single()
        assertEquals("Moved from Roms to gba", card.subtitle)
    }

    @Test
    fun `a finished rename reads Renamed and offers Undo and Dismiss like a move`() {
        val state = FileQueueState.ReverseAvailable(
            com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove(
                landedPaths = listOf("/root/Super Mario.gba"),
                originFolder = "/root",
                previousName = "Mario.gba",
            ),
        )
        val renamed = entry(JobKind.MOVE, state)
        assertEquals("Renamed", FileOperationQueue.chipLabel(renamed))
        assertEquals(
            listOf(FileBrowserQueueAction.UNDO, FileBrowserQueueAction.DISMISS),
            fileBrowserQueueActions(renamed),
        )
    }
}
