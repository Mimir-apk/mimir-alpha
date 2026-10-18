package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **[FileBrowserMode.PICK_IMAGE]** keeps Upload in the in-app browser. The pure gate ([fileBrowserFileChoosable]), the two tables that read it (dispatch, footer),
 * and the controller driven against a REAL [DefaultFocusAuthority] on [FileBrowserControllerTest]'s own
 * precedent: only the filesystem is faked.
 *
 * the picker lists images and folders by
 * default, a pinned row flips it to every file, and no card is ever faded.
 *
 * What this cannot prove, stated: the tile's semantics are a private `@Composable`, pinned as source text by
 * the render checks below; how the cards look on the Odin is device-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserImagePickTest {
    private val english = filesEnglish()

    // ── the pure gate ────────────────────────────────────────────────────────────────────────

    @Test
    fun `PICK_IMAGE takes exactly the six still-image extensions, case-insensitively`() {
        assertEquals(setOf("png", "jpg", "jpeg", "webp", "gif", "bmp"), FileBrowserImageExtensions)
        FileBrowserImageExtensions.forEach { ext ->
            assertTrue(ext, fileBrowserFileChoosable(FileBrowserMode.PICK_IMAGE, "cover.$ext"))
            assertTrue(
                "the player's card is not case-sensitive about .$ext, so neither is the gate",
                fileBrowserFileChoosable(FileBrowserMode.PICK_IMAGE, "Cover.${ext.uppercase()}"),
            )
        }
    }

    @Test
    fun `PICK_IMAGE refuses everything that is not a still image, including media the sidecar list dims`() {
        listOf("disc.iso", "game.gba", "pack.zip", "clip.mp4", "song.mp3", "README", "trailing.", "notes.txt")
            .forEach { name ->
                assertFalse(name, fileBrowserFileChoosable(FileBrowserMode.PICK_IMAGE, name))
            }
    }

    @Test
    fun `PICK and MANAGE never answer true, whatever the name -- the mode is tested first`() {
        listOf(FileBrowserMode.PICK, FileBrowserMode.MANAGE).forEach { mode ->
            assertFalse(
                "$mode must not gain a second file gate: R-21's source pick has its own, and MANAGE has none",
                fileBrowserFileChoosable(mode, "cover.png"),
            )
        }
    }

    @Test
    fun `the header reads Choose an image in PICK_IMAGE and Files everywhere else`() {
        assertEquals("ᛗ · Choose an image", fileBrowserHeaderTitle(english, FileBrowserMode.PICK_IMAGE))
        assertEquals("Files", fileBrowserHeaderTitle(english, FileBrowserMode.PICK))
        assertEquals("Files", fileBrowserHeaderTitle(english, FileBrowserMode.MANAGE))
        // Settings › Music's folder pick (2026-09-30) names itself the same way; see FileBrowserMusicFolderPickTest.
        assertEquals("ᛗ · Choose a music folder", fileBrowserHeaderTitle(english, FileBrowserMode.PICK_MUSIC_FOLDER))
    }

    // ── the two tables that read the gate ────────────────────────────────────────────────────

    private fun tile(name: String, kind: FileBrowserTileKind) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = kind == FileBrowserTileKind.DIRECTORY),
        kind = kind,
        machinery = false,
        platformIdValue = null,
    )

    @Test
    fun `a focused image commits itself when imagePickAvailable, and stays Consumed otherwise`() {
        val image = tile("cover.png", FileBrowserTileKind.FILE)
        assertEquals(
            FileBrowserCommand.CommitSourceFile("/root/cover.png"),
            fileBrowserCommand(ControllerAction.CONFIRM, image, atRoot = true, imagePickAvailable = true),
        )
        assertEquals(
            "the default keeps every pre-existing caller's table byte-identical",
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.CONFIRM, image, atRoot = true),
        )
        assertEquals(
            "a folder still opens in an image pick -- navigating into it is how the image is reached",
            FileBrowserCommand.Enter("/root/Pictures"),
            fileBrowserCommand(
                ControllerAction.CONFIRM,
                tile("Pictures", FileBrowserTileKind.DIRECTORY),
                atRoot = true,
                imagePickAvailable = true,
            ),
        )
    }

    @Test
    fun `the legend advertises A CHOOSE on a choosable image and nothing on any other file`() {
        val image = tile("cover.png", FileBrowserTileKind.FILE)
        assertEquals(
            listOf("A" to "Choose", "B" to "Close"),
            fileBrowserFooterHints(english, image, atRoot = true, imagePickAvailable = true),
        )
        assertEquals(
            "a non-image file advertises no primary verb, matching the dispatch table's Consumed",
            listOf("B" to "Close"),
            fileBrowserFooterHints(english, tile("disc.iso", FileBrowserTileKind.FILE), atRoot = true, imagePickAvailable = false),
        )
    }

    // ── the controller, against a real authority ─────────────────────────────────────────────

    private fun tree(): Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Pictures", path = "/root/Pictures", isDirectory = true),
            FileItem(name = "cover.png", path = "/root/cover.png", extension = "png"),
            FileItem(name = "disc.iso", path = "/root/disc.iso", extension = "iso"),
            FileItem(name = "pack.zip", path = "/root/pack.zip", extension = "zip"),
        ),
        "/root/Pictures" to listOf(
            FileItem(name = "hero.jpg", path = "/root/Pictures/hero.jpg", extension = "jpg"),
        ),
    )

    private class Sinks {
        var chosen: String? = null
        var dismissed = 0
        val deleted = mutableListOf<String>()
        val moved = mutableListOf<Pair<String, String>>()
        val copied = mutableListOf<Pair<String, String>>()
    }

    /** Every destructive sink WIRED, so incapability below is proven by mode and not by absence. */
    private fun controller(authority: DefaultFocusAuthority, sinks: Sinks) = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.PICK_IMAGE,
        listDirectory = { path -> tree()[path].orEmpty() },
        onChoose = { sinks.chosen = it },
        onDismiss = { sinks.dismissed += 1 },
        onDelete = { sinks.deleted += it },
        onMoveTo = { path, destination -> sinks.moved += path to destination },
        onCopyTo = { path, destination -> sinks.copied += path to destination },
    )

    @Test
    fun `open lists images and folders only, pins the switch, and lands on the first tile`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority, Sinks())

        c.open()

        assertEquals(
            "the files the picker cannot take are left out, not faded",
            listOf("cover.png"),
            c.partition.files.map { it.name },
        )
        assertEquals(listOf("Pictures"), c.partition.tomes.map { it.name })
        assertEquals("disc.iso and pack.zip are the two it is leaving out", 2, c.imagePickHiddenCount)
        assertTrue("the pinned slot carries the switch", c.commitAvailable)
        assertEquals(FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER, c.commitPurpose)
        assertTrue(authority.registeredKeysInScope(c.scope).contains(FileBrowserFocus.commitKey("/root")))
        assertEquals(
            "the switch is not a place to start: a first visit lands on the first folder",
            FileBrowserFocus.tileKey("/root/Pictures"),
            authority.state.value.focused,
        )
    }

    /** The pinned row's A, driven through the real dispatch table. */
    private fun flip(c: FileBrowserController, authority: DefaultFocusAuthority) {
        authority.requestFocus(FileBrowserFocus.commitKey(c.currentPath))
        assertTrue(c.dispatch(ControllerAction.CONFIRM))
    }

    @Test
    fun `A on the pinned row shows every file in place, keeps the player on the row, and A again goes back`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority, Sinks())
        c.open()

        flip(c, authority)

        assertTrue(c.imagePickShowsAll)
        assertEquals(listOf("cover.png", "disc.iso", "pack.zip"), c.partition.files.map { it.name })
        assertEquals(FileBrowserFocus.commitKey("/root"), authority.state.value.focused)
        assertEquals(
            "the row stays, now offering the way back",
            FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER,
            c.commitPurpose,
        )

        flip(c, authority)

        assertFalse(c.imagePickShowsAll)
        assertEquals(listOf("cover.png"), c.partition.files.map { it.name })
        assertEquals(FileBrowserFocus.commitKey("/root"), authority.state.value.focused)
    }

    @Test
    fun `the switch relists from memory -- the folder is not read again`() {
        val authority = DefaultFocusAuthority()
        var reads = 0
        val c = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = FileBrowserMode.PICK_IMAGE,
            listDirectory = { path -> reads++; tree()[path].orEmpty() },
            onChoose = {},
            onDismiss = {},
        )
        c.open()
        val readsAfterOpen = reads

        flip(c, authority)
        flip(c, authority)

        assertEquals("flipping twice must not touch the disk", readsAfterOpen, reads)
    }

    @Test
    fun `a folder with nothing to hide has no switch row, and the first tile is the landing`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority, Sinks())
        c.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Pictures"))
        assertTrue(c.dispatch(ControllerAction.CONFIRM))

        assertEquals("/root/Pictures", c.currentPath)
        assertEquals(0, c.imagePickHiddenCount)
        assertFalse("hero.jpg is the only file and it is an image: nothing to switch", c.commitAvailable)
        assertNull(c.commitPurpose)
        assertEquals(
            "with no switch row the first tile is the landing, as in MANAGE",
            FileBrowserFocus.tileKey("/root/Pictures/hero.jpg"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `a re-opened picker starts on images only again`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority, Sinks())
        c.open()
        flip(c, authority)
        assertTrue(c.imagePickShowsAll)

        c.close()
        c.open()

        assertFalse(c.imagePickShowsAll)
        assertEquals(listOf("cover.png"), c.partition.files.map { it.name })
    }

    @Test
    fun `PICK and MANAGE are untouched -- no filter, no switch, no hidden count`() {
        listOf(FileBrowserMode.PICK, FileBrowserMode.MANAGE).forEach { mode ->
            val authority = DefaultFocusAuthority()
            val c = FileBrowserController(
                authority = authority,
                rootPath = "/root",
                mode = mode,
                listDirectory = { path -> tree()[path].orEmpty() },
                onChoose = {},
                onDismiss = {},
            )
            c.open()

            assertEquals("$mode lists every file", listOf("cover.png", "disc.iso", "pack.zip"), c.partition.files.map { it.name })
            assertEquals(0, c.imagePickHiddenCount)
            assertFalse(c.commitPurpose == FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER)
        }
    }

    @Test
    fun `CONFIRM on a focused image closes the browser and reports the FILE's own path`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/cover.png"))

        assertTrue(c.dispatch(ControllerAction.CONFIRM))

        assertEquals("/root/cover.png", sinks.chosen)
        assertNotEquals(
            "the browser must have left its scope BEFORE onChoose ran -- close-then-report, R-21's own arm",
            c.scope,
            authority.state.value.scope,
        )
        assertEquals(0, sinks.dismissed)
    }

    @Test
    fun `one tap on an image tile does the same through handleTap -- per-element action parity`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open()

        c.handleTap(c.partition.files.first { it.name == "cover.png" })

        assertEquals("/root/cover.png", sinks.chosen)
    }

    @Test
    fun `CONFIRM on a non-image file or an archive is inert -- nothing chosen, browser still open`() {
        listOf("/root/disc.iso", "/root/pack.zip").forEach { path ->
            val authority = DefaultFocusAuthority()
            val sinks = Sinks()
            val c = controller(authority, sinks)
            c.open()
            flip(c, authority)
            authority.requestFocus(FileBrowserFocus.tileKey(path))

            assertTrue("a hard modal consumes the press", c.dispatch(ControllerAction.CONFIRM))

            assertNull("$path is not an image and must not be reported", sinks.chosen)
            assertEquals("the browser stays open on $path", c.scope, authority.state.value.scope)
            assertEquals("focus stays on $path", FileBrowserFocus.tileKey(path), authority.state.value.focused)
        }
    }

    @Test
    fun `a directory opens, and an image inside it is choosable with its full path`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open() // "Pictures" holds focus -- see the landing test above

        assertTrue(c.dispatch(ControllerAction.CONFIRM))
        assertEquals("/root/Pictures", c.currentPath)
        assertNull(sinks.chosen)

        authority.requestFocus(FileBrowserFocus.tileKey("/root/Pictures/hero.jpg"))
        assertTrue(c.dispatch(ControllerAction.CONFIRM))
        assertEquals("/root/Pictures/hero.jpg", sinks.chosen)
    }

    @Test
    fun `CANCEL at the root dismisses without choosing`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open()

        assertTrue(c.dispatch(ControllerAction.CANCEL))

        assertNull(sinks.chosen)
        assertEquals(1, sinks.dismissed)
        assertNotEquals(c.scope, authority.state.value.scope)
    }

    @Test
    fun `PICK_IMAGE is as incapable of a destructive operation as PICK, even with every sink wired`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val c = controller(authority, sinks)
        c.open()
        flip(c, authority)

        assertFalse("PICK_IMAGE cannot delete regardless of UI restrictions", c.deleteAvailable)
        assertFalse(c.transferChoiceAvailable)
        assertFalse(c.queueAvailable)
        assertFalse(fileBrowserQueueAvailable(FileBrowserMode.PICK_IMAGE))
        assertEquals(FileBrowserSelection.Inactive, fileBrowserSelectionEnter(FileBrowserMode.PICK_IMAGE))

        // The presses that reach delete / move / copy in MANAGE, on a plain file and on an archive.
        listOf("/root/disc.iso", "/root/pack.zip").forEach { path ->
            authority.requestFocus(FileBrowserFocus.tileKey(path))
            c.dispatch(ControllerAction.SYSTEM_SELECT)
            c.dispatch(ControllerAction.SECONDARY)
            c.dispatch(ControllerAction.SYSTEM_START)
        }
        assertTrue(sinks.deleted.isEmpty())
        assertTrue(sinks.moved.isEmpty())
        assertTrue(sinks.copied.isEmpty())
        assertFalse("no action overlay may open in an image pick", c.actionOverlayOpen)
        assertFalse(c.transferChooserOpen)
        assertFalse(c.queueOpen)
    }

    // ── the render half, as source text ──

    @Test
    fun `the file tile marks a non-image disabled with the reason, and fades nothing`() {
        val tile = surfaceSource()
            .substringAfter("private fun FileTile(")
            .substringBefore("internal fun fileBrowserDisplayName(")

        assertTrue(
            "the tile must read the same gate the dispatch table and legend read",
            tile.contains("!fileBrowserFileChoosable(controller.mode, tile.name)"),
        )
        assertTrue(
            "the semantics must name the REASON, and mark the tile disabled",
            tile.contains("stateDescription = notAnImage") && tile.contains("stringResource(FileBrowserNotAnImageDescription)") && tile.contains("disabled()"),
        )
        assertFalse(
            "a card the picker cannot take keeps its full look",
            Regex("""graphicsLayer|ModulateAlpha|DimAlpha|\.alpha\(|alpha = if""").containsMatchIn(tile),
        )
        assertTrue(
            "the header must draw the mode's own title",
            surfaceSource().contains("?: fileBrowserHeaderTitle(filesResources(), controller.mode)"),
        )
    }

    private fun surfaceSource(): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"
        return listOf(java.io.File(relative), java.io.File("feature-files/$relative"), java.io.File("../feature-files/$relative"))
            .firstOrNull(java.io.File::isFile)?.readText()
            ?: error("FileBrowserSurface.kt not found from ${java.io.File(".").absolutePath}")
    }
}
