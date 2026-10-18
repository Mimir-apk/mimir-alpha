package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Selection screen's reducer — F-11.
 *
 * The load-bearing assertion in this file is `a PICK session cannot hold a selection at all`: The safety contract requires PICK must be **incapable**, not restricted, and a reducer that armed in PICK
 * and then declined at the Delete button would be the restricted version.
 */
class FileBrowserSelectionTest {

    private fun tile(
        name: String,
        directory: Boolean = false,
        size: Long = 0L,
    ): FileBrowserTile {
        val item = FileItem(
            name = name,
            path = "/roms/$name",
            size = size,
            isDirectory = directory,
            extension = if (directory) "" else name.substringAfterLast('.', ""),
        )
        return fileBrowserPartition(listOf(item)) { FileBrowserRecognition.None }
            .let { it.tomes + it.files }
            .single()
    }

    private fun armed(vararg paths: String) =
        FileBrowserSelection(active = true, paths = paths.toSet())

    // ── Section 3c: PICK is incapable ─────────────────────────────────────────────────────────────

    @Test
    fun `a PICK session cannot enter selection mode`() {
        val selection = fileBrowserSelectionEnter(FileBrowserMode.PICK)

        assertEquals(
            "PICK must be INCAPABLE, not restricted: entering must return the inactive value unchanged",
            FileBrowserSelection.Inactive,
            selection,
        )
        assertFalse(selection.active)
    }

    @Test
    fun `a PICK session cannot hold a selection at all, so no destructive action is ever declinable`() {
        val afterEnter = fileBrowserSelectionEnter(FileBrowserMode.PICK)

        val afterTicks = afterEnter.toggled("/roms/Zelda.gba").toggled("/roms/Metroid.gba")

        assertEquals(
            "a tick on an un-armed selection must not accumulate paths -- there must be no PICK state in" +
                " which a Delete button has a non-empty list to act on",
            emptySet<String>(),
            afterTicks.paths,
        )
        assertEquals(
            emptyList<FileBrowserSelectionAction>(),
            fileBrowserSelectionActions(afterTicks, listOf(tile("Zelda.gba")), moveDestinationAvailable = true),
        )
    }

    @Test
    fun `MANAGE enters selection mode armed and empty`() {
        val selection = fileBrowserSelectionEnter(FileBrowserMode.MANAGE)

        assertTrue(selection.active)
        assertEquals(emptySet<String>(), selection.paths)
        assertFalse("an empty selection shows no pane -- there is nothing to act on yet", selection.showsPane)
    }

    // ── The reducer ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tick adds, a second tick on the same row removes`() {
        val once = fileBrowserSelectionEnter(FileBrowserMode.MANAGE).toggled("/roms/Zelda.gba")
        assertEquals(setOf("/roms/Zelda.gba"), once.paths)

        assertEquals(emptySet<String>(), once.toggled("/roms/Zelda.gba").paths)
    }

    @Test
    fun `picks keep their order, so a delete acts in an explainable sequence`() {
        val selection = fileBrowserSelectionEnter(FileBrowserMode.MANAGE)
            .toggled("/roms/c.gba")
            .toggled("/roms/a.gba")
            .toggled("/roms/b.gba")

        assertEquals(listOf("/roms/c.gba", "/roms/a.gba", "/roms/b.gba"), selection.paths.toList())
    }

    @Test
    fun `Clear leaves selection mode and empties it in one press`() {
        val cleared = armed("/roms/Zelda.gba").cleared()

        assertEquals(FileBrowserSelection.Inactive, cleared)
        assertFalse(
            "an 'empty but still armed' state would leave the player in a mode with no pane and no way out",
            cleared.active,
        )
    }

    @Test
    fun `the pane appears only once something is in the selection, and the inspector yields immediately`() {
        val armedEmpty = fileBrowserSelectionEnter(FileBrowserMode.MANAGE)

        assertFalse(armedEmpty.showsPane)
        assertTrue(
            "the inspector describes ONE focused file; it must yield the moment selection mode is entered",
            armedEmpty.suppressesInspector,
        )
        assertTrue(armedEmpty.toggled("/roms/Zelda.gba").showsPane)
        assertFalse(FileBrowserSelection.Inactive.suppressesInspector)
    }

    // ── The action set ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Extract is offered only when the selection actually holds an archive`() {
        val tiles = listOf(tile("Zelda.gba"), tile("bundle.zip"))

        assertTrue(
            fileBrowserSelectionAction(armed("/roms/bundle.zip"), tiles, FileBrowserSelectionAction.EXTRACT),
        )
        assertFalse(
            "on a selection of plain ROMs an Extract button has no possible outcome but a failure",
            fileBrowserSelectionAction(armed("/roms/Zelda.gba"), tiles, FileBrowserSelectionAction.EXTRACT),
        )
    }

    @Test
    fun `Move is withheld until a destination can actually be chosen -- the Move screen is unbuilt`() {
        val tiles = listOf(tile("Zelda.gba"))
        val selection = armed("/roms/Zelda.gba")

        assertFalse(
            "moveInManager(paths, destination) cannot be called without a folder picker; the default must" +
                " be the honest answer, not the optimistic one",
            FileBrowserSelectionAction.MOVE in fileBrowserSelectionActions(selection, tiles),
        )
        assertTrue(
            FileBrowserSelectionAction.MOVE in
                fileBrowserSelectionActions(selection, tiles, moveDestinationAvailable = true),
        )
    }

    @Test
    fun `Delete and Clear are always there once the pane is drawn`() {
        val actions = fileBrowserSelectionActions(armed("/roms/Zelda.gba"), listOf(tile("Zelda.gba")))

        assertEquals(
            "deleteInManager needs nothing but the list, and Clear is the way out",
            listOf(FileBrowserSelectionAction.DELETE, FileBrowserSelectionAction.CLEAR),
            actions,
        )
    }

    @Test
    fun `the pane's buttons keep the design's own order`() {
        val tiles = listOf(tile("bundle.zip"))

        assertEquals(
            listOf(
                FileBrowserSelectionAction.MOVE,
                FileBrowserSelectionAction.EXTRACT,
                FileBrowserSelectionAction.DELETE,
                FileBrowserSelectionAction.CLEAR,
            ),
            fileBrowserSelectionActions(armed("/roms/bundle.zip"), tiles, moveDestinationAvailable = true),
        )
    }

    @Test
    fun `no actions at all when the pane is not drawn`() {
        assertEquals(
            emptyList<FileBrowserSelectionAction>(),
            fileBrowserSelectionActions(fileBrowserSelectionEnter(FileBrowserMode.MANAGE), emptyList()),
        )
    }

    // ── The pane heading ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the heading counts rows and totals their bytes`() {
        val tiles = listOf(tile("Zelda.gba", size = 8L * 1024 * 1024), tile("Metroid.gba", size = 8L * 1024 * 1024))

        assertEquals(
            "2 files · 16.0 MB",
            fileBrowserSelectionLabel(armed("/roms/Zelda.gba", "/roms/Metroid.gba"), tiles),
        )
    }

    @Test
    fun `one row reads as a singular file`() {
        assertEquals(
            "1 file · 1.0 KB",
            fileBrowserSelectionLabel(armed("/roms/Zelda.gba"), listOf(tile("Zelda.gba", size = 1024))),
        )
    }

    @Test
    fun `a folder in the selection drops the size clause rather than under-reporting it`() {
        val tiles = listOf(tile("Zelda.gba", size = 4096), tile("GBA", directory = true))

        assertEquals(
            "File.length() answers 0 for a directory, so summing one would print a confidently wrong" +
                " figure for how much of the player's card the delete frees",
            "2 files",
            fileBrowserSelectionLabel(armed("/roms/Zelda.gba", "/roms/GBA"), tiles),
        )
    }

    @Test
    fun `a selected path with no tile to measure drops the size clause too`() {
        assertEquals(
            "2 files",
            fileBrowserSelectionLabel(
                armed("/roms/Zelda.gba", "/elsewhere/Metroid.gba"),
                listOf(tile("Zelda.gba", size = 4096)),
            ),
        )
    }

    // ── Focus identity ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `pane keys live in their own namespace, which no tile key can produce`() {
        FileBrowserSelectionAction.entries.forEach { action ->
            val key = FileBrowserSelectionFocus.actionKey(action)

            assertTrue(key.value.startsWith("files/selection/"))
            assertNotEquals(key, FileBrowserFocus.tileKey("/selection/${action.name}"))
            assertEquals(action, FileBrowserSelectionFocus.actionOf(key))
        }
    }

    @Test
    fun `actionOf refuses a key that is not a pane button`() {
        assertNull(FileBrowserSelectionFocus.actionOf(null))
        assertNull(FileBrowserSelectionFocus.actionOf(FileBrowserFocus.tileKey("/roms/Zelda.gba")))
        assertNull(FileBrowserSelectionFocus.actionOf(FocusKey("files/selection/RENAME")))
    }

    @Test
    fun `the pane registers exactly one key per drawn button`() {
        val actions = fileBrowserSelectionActions(
            armed("/roms/bundle.zip"),
            listOf(tile("bundle.zip")),
            moveDestinationAvailable = true,
        )

        assertEquals(actions.map(FileBrowserSelectionFocus::actionKey), fileBrowserSelectionKeys(actions))
        assertEquals(4, fileBrowserSelectionKeys(actions).size)
    }

    private fun fileBrowserSelectionAction(
        selection: FileBrowserSelection,
        tiles: List<FileBrowserTile>,
        action: FileBrowserSelectionAction,
    ): Boolean = action in fileBrowserSelectionActions(selection, tiles)
}
