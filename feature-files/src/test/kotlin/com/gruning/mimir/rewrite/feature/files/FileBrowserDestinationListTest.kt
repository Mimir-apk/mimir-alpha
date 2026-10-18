package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.ExtractDestinationOptions
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.PlatformDestination
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 *
 *
 * The screen that answers *where?* for all three verbs. Every test here drives the real controller
 * against a real [DefaultFocusAuthority] — only the filesystem and the destination resolver are faked,
 * matching `FileBrowserControllerTest`'s own posture.
 *
 * The scenario under all of it is the captured field case: a zipped PS2 game, which recognition answers **null** for
 * (PS2's content contract does not list `zip`), being put into a PS2 folder the library has never sourced
 * a single game from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserDestinationListTest {
    private val english = filesEnglish()

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true),
            FileItem(name = "Final Fantasy X.zip", path = "/root/Final Fantasy X.zip", extension = "zip"),
            FileItem(name = "notes.txt", path = "/root/notes.txt", extension = "txt"),
        ),
        "/root/Roms" to listOf(
            FileItem(name = "gba", path = "/root/Roms/gba", isDirectory = true),
        ),
    )

    private val ps2 = PlatformDestination(
        platformId = "ps2",
        path = "/sd/Roms/ps2",
        fromLibrary = false,
        recognised = false,
        onSubjectVolume = false,
    )
    private val gba = PlatformDestination(
        platformId = "gba",
        path = "/root/Roms/gba",
        fromLibrary = true,
        recognised = false,
        onSubjectVolume = true,
    )

    private class Sinks {
        val extracted = mutableListOf<Pair<String, String>>()
        val moved = mutableListOf<Pair<String, String>>()
        val copied = mutableListOf<Pair<String, String>>()
    }

    private fun newController(
        authority: DefaultFocusAuthority,
        sinks: Sinks = Sinks(),
        destinations: List<PlatformDestination> = listOf(gba, ps2),
    ): FileBrowserController = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = {},
        onDismiss = {},
        onDelete = { },
        resolveExtractOptions = { archivePath ->
            // No smart default: this IS the PS2 case, where the classifier answers null.
            ExtractDestinationOptions(here = "$archivePath-here", suggestedPlatformFolder = null, platformId = null)
        },
        onExtractTo = { archive, destination -> sinks.extracted += archive to destination },
        onMoveTo = { path, destination -> sinks.moved += path to destination },
        onCopyTo = { path, destination -> sinks.copied += path to destination },
        resolvePlatformDestinations = { destinations },
    )

    /**
     * Focus the archive, reach the extract chooser, then take its "Extract in…" row.
     *
     * **F-1/R-13, 2026-08-18: via SELECT's action overlay, not a direct X** -- `FileBrowserExtractWiringTest
     * .pressExtractOn`'s identical pattern and KDoc.
     */
    private fun openDestinationListViaExtract(controller: FileBrowserController, authority: DefaultFocusAuthority) {
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Final Fantasy X.zip"))
        controller.handleInspectorSecondaryTap()
        authority.requestFocus(
            FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.EXTRACT),
        )
        controller.dispatch(ControllerAction.CONFIRM)
        // The chooser's rows are "Extract here" and "Extract in…"; the second is what begins the pick.
        val rows = controller.extractChooser?.rows.orEmpty()
        val chooseIndex = rows.indexOfFirst { it.destination == null }
        authority.requestFocus(FileBrowserExtractFocus.optionKey(rows[chooseIndex].kind))
        controller.dispatch(ControllerAction.CONFIRM)
    }

    /**
     * Focus a FILE, reach the action overlay, then take its Move/Copy row -- arms the pick directly
     * (spec §1c), bypassing the old two-row transfer chooser exactly as production does as of F-1.
     */
    private fun beginTransferPickVia(
        controller: FileBrowserController,
        authority: DefaultFocusAuthority,
        path: String,
        kind: FileBrowserTransferKind,
    ) {
        authority.requestFocus(FileBrowserFocus.tileKey(path))
        controller.handleInspectorSecondaryTap()
        val rowKind = when (kind) {
            FileBrowserTransferKind.MOVE -> FileBrowserActionOverlayRowKind.MOVE
            FileBrowserTransferKind.COPY -> FileBrowserActionOverlayRowKind.COPY
        }
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(rowKind))
        controller.dispatch(ControllerAction.CONFIRM)
    }

    @Test
    fun `a zipped PS2 game reaches its PS2 folder in ONE press, with nothing recognised`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)

        openDestinationListViaExtract(controller, authority)

        assertTrue("the list opens at the head of the pick", controller.destinationListOpen)
        val rows = controller.destinationList!!.rows
        assertEquals(
            "F-3, 2026-08-18: the browse escape leads, ahead of every destination",
            listOf("Browse for a folder instead…", "gba", "ps2"),
            rows.map { fileBrowserDestinationRowLabel(english, it) },
        )

        val ps2Index = rows.indexOfFirst { it.label == "ps2" }
        authority.requestFocus(FileBrowserDestinationFocus.optionKey(ps2Index))
        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals(
            "before this fix the same press extracted nowhere: the archive was unrecognised, so the " +
                "chooser offered a folder picker and the player navigated by hand",
            listOf("/root/Final Fantasy X.zip" to "/sd/Roms/ps2"),
            sinks.extracted,
        )
        assertFalse(controller.destinationListOpen)
        assertNull("the pick is answered, not left open", controller.destinationPick)
    }

    @Test
    fun `the browse row hands the pick back to the browser instead of committing`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)
        openDestinationListViaExtract(controller, authority)

        val browseIndex = controller.destinationList!!.rows.indexOfFirst {
            it.kind == FileBrowserDestinationRowKind.BROWSE
        }
        authority.requestFocus(FileBrowserDestinationFocus.optionKey(browseIndex))
        controller.dispatch(ControllerAction.CONFIRM)

        assertFalse(controller.destinationListOpen)
        assertTrue("the list is every folder MIMIR can see, not every folder the player wants", sinks.extracted.isEmpty())
        assertEquals(
            "the pick underneath stays live -- the browser is still asking where, with its commit row",
            "/root/Final Fantasy X.zip",
            controller.destinationPick?.archivePath,
        )
    }

    @Test
    fun `CANCEL abandons the whole pick rather than dropping the player onto a live commit row`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        openDestinationListViaExtract(controller, authority)

        controller.dispatch(ControllerAction.CANCEL)

        assertFalse(controller.destinationListOpen)
        assertNull(
            "falling back to a browse listing wearing a commit row for a verb the player just backed " +
                "out of is the ghost-affordance defect this module keeps closing",
            controller.destinationPick,
        )
    }

    @Test
    fun `the recognised folder is preselected only when recognition is unambiguous`() {
        val authority = DefaultFocusAuthority()
        // The recognised row is deliberately NOT index 1 (the first platform row): with it first among
        // the platform rows, this test would pass against a controller that always landed on the first
        // PLATFORM row regardless of recognition, and would be pinning nothing. Rows are now
        // [BROWSE(0), gba(1), ps2*recognised(2)] since F-3 (2026-08-18) moved browse to index 0.
        val controller = newController(
            authority,
            destinations = listOf(gba, ps2.copy(recognised = true)),
        )

        openDestinationListViaExtract(controller, authority)

        assertEquals(
            "the recognised folder must be preselected; landing on the first platform row " +
                "regardless would ignore recognition",
            FileBrowserDestinationFocus.optionKey(2),
            authority.state.value.focused,
        )
    }

    @Test
    fun `unsure lands on the first platform row, never the browse escape`() {
        // BROWSE moved to index 0 on 2026-08-18 so the different-folder option appears first.
        // A naive fallback to literal row 0 would now focus that escape hatch whenever nothing is recognised --
        // the COMMON case, since recognition is false far more often than true
        // (fileBrowserDestinationLanding's own KDoc). This test is its own method, not a second half of
        // the recognised-case test above, specifically so a differential run (revert production, keep
        // this test) fails HERE for THIS reason rather than being masked by the first test's own failure.
        val unsure = DefaultFocusAuthority()
        val unsureController = newController(unsure, destinations = listOf(gba, ps2))
        openDestinationListViaExtract(unsureController, unsure)

        val unsureRows = unsureController.destinationList!!.rows
        assertFalse("nothing recognised in this fixture", unsureRows.any { it.recognised })
        assertEquals(
            "a zipped PS2 game recognises nothing, and this lands on the first PLATFORM row (gba, " +
                "index 1) -- NOT on the browse escape that now occupies row 0. Landing on the escape " +
                "hatch by default would put the ring on the exit door in the COMMON case: recognition " +
                "is false far more often than true, per fileBrowserDestinationLanding's own KDoc",
            FileBrowserDestinationFocus.optionKey(1),
            unsure.state.value.focused,
        )
        val landedIndex = FileBrowserDestinationFocus.indexOf(unsure.state.value.focused)
        assertEquals(
            "explicitly: the landed row is a real destination, never the escape hatch",
            FileBrowserDestinationRowKind.PLATFORM,
            unsureRows[landedIndex!!].kind,
        )
    }

    @Test
    fun `with no destinations at all the screen is skipped, not opened empty`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, destinations = emptyList())

        openDestinationListViaExtract(controller, authority)

        assertFalse(
            "a door with nothing behind it is worse than no door: the pick behaves exactly as it did " +
                "before this screen existed",
            controller.destinationListOpen,
        )
        assertEquals("/root/Final Fantasy X.zip", controller.destinationPick?.archivePath)
    }

    @Test
    fun `MOVE commits through the move sink, and COPY through the copy one - never each other's`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)
        controller.open()
        beginTransferPickVia(controller, authority, "/root/notes.txt", FileBrowserTransferKind.MOVE)

        assertTrue("the same destination list serves Move", controller.destinationListOpen)
        val gbaIndex = controller.destinationList!!.rows.indexOfFirst { it.path == gba.path }
        authority.requestFocus(FileBrowserDestinationFocus.optionKey(gbaIndex))
        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals(listOf("/root/notes.txt" to "/root/Roms/gba"), sinks.moved)
        assertTrue(
            "a move has no Undo, so a subject recovered against the wrong verb would relocate a file " +
                "the player never chose with nothing to walk it back",
            sinks.copied.isEmpty() && sinks.extracted.isEmpty(),
        )
    }

    @Test
    fun `COPY reaches the copy sink through the same screen`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)
        controller.open()
        beginTransferPickVia(controller, authority, "/root/notes.txt", FileBrowserTransferKind.COPY)

        val gbaIndex = controller.destinationList!!.rows.indexOfFirst { it.path == gba.path }
        authority.requestFocus(FileBrowserDestinationFocus.optionKey(gbaIndex))
        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals(listOf("/root/notes.txt" to "/root/Roms/gba"), sinks.copied)
        assertTrue(sinks.moved.isEmpty())
    }

    @Test
    fun `closing the browser tears the screen down - its keys may not outlive its scope`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        openDestinationListViaExtract(controller, authority)
        assertTrue(controller.destinationListOpen)

        controller.close()

        assertFalse(controller.destinationListOpen)
        assertTrue(
            "left pushed, the browse scope is buried and nothing below it is ever popped",
            authority.state.value.scope != controller.destinationScope,
        )
    }

    @Test
    fun `the rows carry the drive and the library standing, because they are different claims`() {
        val rows = fileBrowserDestinationRows(listOf(gba, ps2))

        assertEquals(
            "F-3, 2026-08-18: 'search in different folder should be at the top of the selection, not " +
                "at the bottom' -- the browse escape is row 0, ahead of every platform row",
            FileBrowserDestinationRowKind.BROWSE,
            rows[0].kind,
        )
        assertTrue("the library's own folder says so", rows[1].fromLibrary)
        assertFalse("a folder that merely exists does not", rows[2].fromLibrary)
        assertTrue(
            "a destination on another drive is a copy across storage, not a rename, and the row says so",
            rows[2].onOtherDrive && fileBrowserDestinationRowDetail(english, rows[2]).startsWith("On another drive · "),
        )
    }

    @Test
    fun `an extraction destination list keeps its archive operation context`() {
        assertEquals(
            "Extract Final Fantasy X.zip to…",
            fileBrowserDestinationContext(english, "/root/Final Fantasy X.zip"),
        )
    }
}
