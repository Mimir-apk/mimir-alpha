package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fix round after the E2 review, 2026-10-08: **a delete held for Undo stays in its folder as "Deleting…".**
 *
 * The engine side (the row is built from the held list, under its own name) is pinned in `core-files`
 * (`HeldDeleteListingTest`). These cover what the Files surface does with such a row: the cover says
 * "Deleting…", and the row is a label for what the player just did rather than a thing -- pressing it
 * opens nothing, offers nothing, extracts nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserDeletingRowTest {
    private val english = filesEnglish()

    private fun tree(): Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "Held", path = "/root/Held", isDirectory = true, isPendingDelete = true),
            FileItem(name = "old.zip", path = "/root/old.zip", extension = "zip", isPendingDelete = true),
            FileItem(name = "live.zip", path = "/root/live.zip", extension = "zip"),
        ),
        "/root/Games" to listOf(FileItem(name = "game.gba", path = "/root/Games/game.gba", extension = "gba")),
        "/root/Held" to listOf(FileItem(name = "inside.gba", path = "/root/Held/inside.gba", extension = "gba")),
    )

    private fun newController(
        authority: DefaultFocusAuthority,
        onExtract: (String) -> Unit = {},
        onVerifyChecksum: (String) -> Unit = {},
    ) = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { path -> tree()[path].orEmpty() },
        onChoose = {},
        onDismiss = {},
        onExtract = onExtract,
        onVerifyChecksum = onVerifyChecksum,
        onDelete = null,
        classifyPlatform = { FileBrowserRecognition.None },
    )

    private fun tileOf(controller: FileBrowserController, name: String): FileBrowserTile =
        (controller.partition.tomes + controller.partition.files).first { it.name == name }

    @Test
    fun `a held folder wears Deleting on its cover and a held file says it where its size would be`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority).also { it.open() }

        val folder = tileOf(controller, "Held")
        val file = tileOf(controller, "old.zip")

        assertTrue(folder.deleting)
        assertEquals("Deleting…", fileBrowserTomeEyebrow(english, folder))
        assertTrue(file.deleting)
        assertEquals("Deleting…", fileBrowserTileMeta(english, file))
        assertFalse(tileOf(controller, "Games").deleting)
        assertEquals("Folder", fileBrowserTomeEyebrow(english, tileOf(controller, "Games")))
        assertEquals("ZIP", fileBrowserBadgeLabel(english, file))
    }

    @Test
    fun `a held folder reads Deleting even when its name is one of Mimir's own folders`() {
        val machinery = FileBrowserTile(
            item = FileItem(name = "saves", path = "/root/saves", isDirectory = true, isPendingDelete = true),
            kind = FileBrowserTileKind.DIRECTORY,
            machinery = true,
            platformIdValue = null,
        )
        val plainMachinery = machinery.copy(item = machinery.item.copy(isPendingDelete = false))

        assertEquals("Deleting…", fileBrowserTomeEyebrow(english, machinery))
        assertEquals("a live Mimir folder keeps its own word", "Mimir", fileBrowserTomeEyebrow(english, plainMachinery))
    }

    @Test
    fun `pressing a held folder opens nothing, and pressing a live one still does`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority).also { it.open() }

        controller.handleTap(tileOf(controller, "Held"))
        assertEquals("a held folder is a label, not a place", "/root", controller.currentPath)
        assertEquals("it can still be looked at: the cursor goes to it", "Held", controller.focusedTile()?.name)

        controller.handleTap(tileOf(controller, "Games"))
        assertEquals("the control: a live folder is entered", "/root/Games", controller.currentPath)
    }

    @Test
    fun `a held archive offers no menu, cannot be extracted or verified, and a live one can`() {
        val authority = DefaultFocusAuthority()
        val extracted = mutableListOf<String>()
        val verified = mutableListOf<String>()
        val controller = newController(authority, onExtract = { extracted += it }, onVerifyChecksum = { verified += it })
            .also { it.open() }

        // The held one: long-press (the secondary action), then the two buttons that read the focused tile.
        controller.handleLongPress(tileOf(controller, "old.zip"))
        assertFalse("a held archive opens no action menu", controller.actionOverlayOpen)
        controller.handleTap(tileOf(controller, "old.zip"))
        controller.verifyFocusedChecksum()
        controller.handleInspectorExtractTap()
        assertTrue("held: nothing extracted: $extracted", extracted.isEmpty())
        assertTrue("held: nothing verified: $verified", verified.isEmpty())

        // The control: the same three presses on a live archive do reach the sinks, so the checks above
        // are about the held row and not about a harness that never wires them.
        controller.handleTap(tileOf(controller, "live.zip"))
        controller.handleInspectorExtractTap()
        controller.verifyFocusedChecksum()
        assertEquals(listOf("/root/live.zip"), extracted)
        assertEquals(listOf("/root/live.zip"), verified)
        controller.handleLongPress(tileOf(controller, "live.zip"))
        assertTrue("a live archive opens its action menu", controller.actionOverlayOpen)
    }

    @Test
    fun `the inspector draws no Open, Extract or Verify on a held row, and draws them on a live one`() {
        val controller = newController(DefaultFocusAuthority()).also { it.open() }
        fun fieldsOf(name: String) = fileBrowserInspectorFields(english, tileOf(controller, name), "/root", "/root")

        val heldArchive = fieldsOf("old.zip")
        assertFalse("held archive: no Extract button", heldArchive.extractable)
        assertFalse("held archive: no Verify button", heldArchive.checksumable)
        assertFalse("held folder: no Open button", fieldsOf("Held").openable)

        val liveArchive = fieldsOf("live.zip")
        assertTrue("the control: a live archive keeps Extract", liveArchive.extractable)
        assertTrue("the control: a live archive keeps Verify", liveArchive.checksumable)
        assertTrue("the control: a live folder keeps Open", fieldsOf("Games").openable)
    }

    @Test
    fun `the footer legend advertises nothing for a held row and still advertises Open and Actions on a live one`() {
        val controller = newController(DefaultFocusAuthority()).also { it.open() }

        // Exactly the two reads FileBrowserSurface makes for the legend: the actions predicate and the hints.
        fun legend(name: String): List<Pair<String, String>> {
            val focused = tileOf(controller, name).actionableTile()
            val actions = fileBrowserActionsAvailable(
                focused = focused,
                manageMode = true,
                deleteAvailable = true,
                transferChoiceAvailable = true,
                destinationPick = null,
                renameAvailable = true,
            )
            return fileBrowserFooterHints(english, focused = focused, atRoot = false, actionsAvailable = actions)
        }

        for (held in listOf("old.zip", "Held")) {
            assertEquals(
                "$held is held for a pending delete: no A OPEN, no X EXTRACT, no X ACTIONS -- only the browser's own B",
                listOf("B" to "Back"),
                legend(held),
            )
        }
        assertTrue("the control: a live folder offers A OPEN", ("A" to "Open") in legend("Games"))
        assertTrue("the control: a live archive offers X ACTIONS", ("X" to "Actions") in legend("live.zip"))
    }

    @Test
    fun `the surface reads the legend and the actions predicate through the held-row filter`() {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"
        val source = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
            .first(File::isFile).readText()

        assertEquals(
            "both the predicate and the legend read the filtered tile",
            2,
            source.split("focused = focusedTile.actionableTile(),").size - 1,
        )
        assertFalse("and no unfiltered read is left beside them", source.contains("focused = focusedTile,"))
    }
}
