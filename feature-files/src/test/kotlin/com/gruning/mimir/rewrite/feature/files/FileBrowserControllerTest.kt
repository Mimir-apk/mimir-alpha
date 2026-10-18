package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gruning.mimir.rewrite.core.model.jobs.JobKind

/**
 * Integration-style coverage for [FileBrowserController] against a REAL [DefaultFocusAuthority] -- no
 * fake/mock authority, matching slice 5-6's own precedent. Only the filesystem is faked (a plain
 * `Map<String, List<FileItem>>`), because [FileBrowserController] is deliberately ignorant of how a
 * listing arrives -- see that class's KDoc.
 */
class FileBrowserControllerTest {

    private fun fakeTree(): Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "pack.zip", path = "/root/pack.zip", extension = "zip"),
            FileItem(name = "readme.txt", path = "/root/readme.txt", extension = "txt"),
        ),
        "/root/Games" to listOf(
            FileItem(name = "game.gba", path = "/root/Games/game.gba", extension = "gba"),
        ),
    )

    private fun newController(
        authority: DefaultFocusAuthority,
        tree: Map<String, List<FileItem>> = fakeTree(),
        // MANAGE here, NOT FileBrowserController's own PICK default -- this helper exists so every
        // pre-existing, mode-agnostic test in this file keeps exercising the no-commit-row behaviour it
        // always has. FileBrowserController's OWN default is pinned separately, deliberately NOT through
        // this helper -- see "FileBrowserController's own default mode is PICK" below.
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
        onChoose: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
        onExtract: (String) -> Unit = {},
        onVerifyChecksum: (String) -> Unit = {},
        // Null by default, matching FileBrowserController's own parameter: a browser with no delete sink
        // wired is exactly what `deleteAvailable` has to answer false for, so every pre-PR-140 test in
        // this file keeps running against a browser that cannot delete.
        onDelete: ((String) -> Unit)? = null,
        classifyPlatform: (FileItem) -> FileBrowserRecognition = { FileBrowserRecognition.None },
    ): FileBrowserController = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = mode,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = onChoose,
        onDismiss = onDismiss,
        onExtract = onExtract,
        onVerifyChecksum = onVerifyChecksum,
        onDelete = onDelete,
        classifyPlatform = classifyPlatform,
    )

    @Test
    fun `open lists the root split into tomes and files, tomes first, root's tome focused`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)

        controller.open()

        assertEquals(listOf("Games"), controller.partition.tomes.map { it.name })
        assertEquals(listOf("pack.zip", "readme.txt"), controller.partition.files.map { it.name })
        assertEquals(FileBrowserFocus.tileKey("/root/Games"), authority.state.value.focused)
    }

    @Test
    fun `CONFIRM on the focused directory navigates into it, and CANCEL navigates back out`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open() // "Games" is already focused -- see the test above's own pin

        assertTrue(controller.dispatch(ControllerAction.CONFIRM))
        assertEquals("/root/Games", controller.currentPath)
        assertFalse(controller.atRoot)
        assertEquals(listOf("game.gba"), controller.partition.files.map { it.name })

        assertTrue(controller.dispatch(ControllerAction.CANCEL))
        assertEquals("/root", controller.currentPath)
        assertTrue(controller.atRoot)
        assertEquals(3, controller.partition.totalCount)
    }

    @Test
    fun `in MANAGE mode, onChoose is unreachable -- no commit affordance is ever registered to trigger it`() {
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        val controller = newController(authority, mode = FileBrowserMode.MANAGE, onChoose = { chosen = it })
        controller.open()

        assertFalse(
            "MANAGE mode must never register a commit key -- the pinned row is PICK-only",
            authority.registeredKeysInScope(controller.scope).contains(FileBrowserFocus.commitKey("/root")),
        )

        // Every input this hard modal accepts, fired once at the root with the tome focused.
        ControllerAction.values().forEach { controller.dispatch(it) }

        assertNull("MANAGE mode has no commit affordance -- onChoose must never fire", chosen)
    }

    // ── PICK mode: the commit affordance restored 2026-08-14 -- see FileBrowserMode's KDoc ──

    @Test
    fun `in PICK mode open registers the commit affordance and focuses index 0 first`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, mode = FileBrowserMode.PICK)

        controller.open()

        val commitKey = FileBrowserFocus.commitKey("/root")
        assertEquals(
            "the commit affordance must be index 0 -- the first key in render/tab order",
            commitKey,
            fileBrowserKeys(controller.partition, FileBrowserGrid.Authored, commitKey).first(),
        )
        assertEquals("index 0 must also be where initial focus lands, matching the pre-rebuild ACTION row", commitKey, authority.state.value.focused)
        assertTrue(
            "the commit affordance must be a REAL registered focus participant, not chrome",
            authority.registeredKeysInScope(controller.scope).contains(commitKey),
        )
    }

    @Test
    fun `in PICK mode, CONFIRM on the focused commit affordance calls onChoose with the current directory`() {
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        val controller = newController(authority, mode = FileBrowserMode.PICK, onChoose = { chosen = it })
        controller.open() // commit affordance already focused -- see the index-0 test above

        assertTrue(controller.dispatch(ControllerAction.CONFIRM))

        assertEquals("/root", chosen)
    }

    @Test
    fun `in PICK mode, the commit affordance is reachable by NAV_UP from the tile grid -- a focus participant, not chrome`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, mode = FileBrowserMode.PICK)
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games")) // step off the commit row first

        assertTrue("NAV_UP must be a real directional move, resolved by the authority's own geometry", controller.dispatch(ControllerAction.NAV_UP))

        assertEquals(
            "D-pad UP from the top tome row must reach the commit affordance -- it participates in " +
                "FocusAuthority's geometric graph exactly like a tile",
            FileBrowserFocus.commitKey("/root"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `in PICK mode, NAV_RIGHT on the commit affordance leaves focus exactly where it is`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, mode = FileBrowserMode.PICK)
        controller.open() // commit affordance focused
        val commitKey = FileBrowserFocus.commitKey("/root")

        assertTrue("the press is still consumed -- this is a hard modal", controller.dispatch(ControllerAction.NAV_RIGHT))

        assertEquals(
            "a full-width pinned row has no horizontal neighbour; letting the authority's geometry answer " +
                "anyway drops the player into the tome grid past its first tile, while LEFT does nothing " +
                "at all -- a silent, asymmetric teleport",
            commitKey,
            authority.state.value.focused,
        )
    }

    @Test
    fun `in PICK mode, committing after navigating into a directory yields the NEW path, not the old one`() {
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        val controller = newController(authority, mode = FileBrowserMode.PICK, onChoose = { chosen = it })
        controller.open() // commit@/root focused
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))
        assertTrue(controller.dispatch(ControllerAction.CONFIRM)) // enters Games
        assertEquals("/root/Games", controller.currentPath)
        // Entering a directory refocuses ITS OWN commit affordance (index 0 again, see the test above) --
        // confirmed structurally, not assumed, before the second CONFIRM below relies on it.
        assertEquals(FileBrowserFocus.commitKey("/root/Games"), authority.state.value.focused)

        assertTrue(controller.dispatch(ControllerAction.CONFIRM)) // commits the directory now open

        assertEquals("committing must report the directory actually open, not the root it started from", "/root/Games", chosen)
    }

    @Test
    fun `FileBrowserController's own default mode is PICK -- FileBrowserStore's un-editable call site depends on it`() {
        // Deliberately NOT using newController() -- that helper defaults to MANAGE for the convenience of
        // every other test in this file. This test pins FileBrowserController's OWN constructor default,
        // the one production actually runs on: FileBrowserStore.kt's open() constructs this class without
        // naming `mode` at all (positionally: authority, rootPath, listDirectory, onChoose, onDismiss,
        // onExtract -- mirrored exactly below). See FileBrowserMode's KDoc, "Defaults to PICK,
        // not MANAGE," for the compatibility and safety rationale. If this default ever flips to MANAGE, this is the test that
        // must catch it -- every other PICK test above goes through newController(mode = PICK) and would
        // stay green regardless of what the bare constructor default is.
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        val controller = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            listDirectory = { path -> fakeTree()[path].orEmpty() },
            onChoose = { chosen = it },
            onDismiss = {},
        )

        controller.open()

        assertEquals(
            "FileBrowserController's bare default must be PICK, or FileBrowserStore's existing " +
                "add-ROMs-source call regresses to the exact bug this class exists to fix",
            FileBrowserFocus.commitKey("/root"),
            authority.state.value.focused,
        )
        assertTrue(controller.dispatch(ControllerAction.CONFIRM))
        assertEquals("/root", chosen)
    }

    @Test
    fun `CANCEL at the root dismisses without choosing anything`() {
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        var dismissed = false
        val controller = newController(authority, onChoose = { chosen = it }, onDismiss = { dismissed = true })
        controller.open()

        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertTrue(dismissed)
        assertNull(chosen)
        assertEquals(FocusScope.Root, authority.state.value.scope)
    }

    /**
     * **F-1/R-13, 2026-08-18, supersedes this test's own former claim.** An ARCHIVE's overlay row for
     * Extract is unconditional on `extractChoiceAvailable` (`FileBrowserActionOverlayChoice.kt`'s own
     * KDoc: extract always has somewhere to go, `onExtract`'s safe default), so `fileBrowserActionsAvailable`
     * is true for ANY archive in MANAGE here regardless of what this helper's `newController` wires --
     * SECONDARY is quiet on this tile now, in lockstep with X on the controller side.
     */
    @Test
    fun `SECONDARY on the focused archive is quiet in MANAGE -- the overlay owns Extract now`() {
        val authority = DefaultFocusAuthority()
        var extracted: String? = null
        val controller = newController(authority, onExtract = { extracted = it })
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/pack.zip"))

        assertTrue(controller.dispatch(ControllerAction.SECONDARY))

        assertNull("R-13: X/SECONDARY retired once the overlay covers this tile", extracted)
    }

    @Test
    fun `X then the overlay's Extract row reports the archive's path via onExtract -- the new path`() {
        val authority = DefaultFocusAuthority()
        var extracted: String? = null
        val controller = newController(authority, onExtract = { extracted = it })
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/pack.zip"))

        assertTrue(controller.dispatch(ControllerAction.SECONDARY))
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.EXTRACT))
        assertTrue(controller.dispatch(ControllerAction.CONFIRM))

        assertEquals(
            "extractChoiceAvailable is false in this fixture, so the row falls back to the legacy " +
                "sibling-directory extract -- onExtract -- exactly as SECONDARY's own pre-F-1 fallback did",
            "/root/pack.zip",
            extracted,
        )
    }

    @Test
    fun `SECONDARY on a focused directory never calls onExtract`() {
        val authority = DefaultFocusAuthority()
        var extractCalls = 0
        val controller = newController(authority, onExtract = { extractCalls++ })
        controller.open() // "Games" focused

        assertTrue(controller.dispatch(ControllerAction.SECONDARY))

        assertEquals(0, extractCalls)
    }

    @Test
    fun `verifyFocusedChecksum reports the focused tile's path, and computes nothing itself`() {
        val authority = DefaultFocusAuthority()
        var verified: String? = null
        val controller = newController(authority, onVerifyChecksum = { verified = it })
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/pack.zip"))

        controller.verifyFocusedChecksum()

        assertEquals("/root/pack.zip", verified)
    }

    @Test
    fun `verifyFocusedChecksum is a no-op when nothing is focused`() {
        val authority = DefaultFocusAuthority()
        var calls = 0
        val controller = newController(authority, onVerifyChecksum = { calls++ })
        controller.open()
        authority.clearFocus()

        controller.verifyFocusedChecksum()

        assertEquals(0, calls)
    }

    /**
     * **Replaces two tests, and the replacement is the behaviour change.** This pair used to read
     * *"a double tap on a focused directory tile opens it"* and *"a single tap only moves focus -- it
     * never opens"*. Both were true of the grammar the former two-tap implementation; the second one is
     * now the exact statement of the defect. The full case table (archive, plain file, long-press, the
     * gesture's absence in source) is [FileBrowserTouchGrammarTest] -- this one stays here because
     * [FileBrowserControllerTest] is where the rest of the controller's tap surface is pinned.
     */
    @Test
    fun `ONE tap on a directory tile opens it -- the same thing one CONFIRM does`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        val gamesTile = controller.partition.tomes.single { it.name == "Games" }

        controller.handleTap(gamesTile)

        assertEquals(
            "one tap must land in the directory. A second-tap binding also imposed a ~300ms timeout " +
                "before the FIRST one did anything at all.",
            "/root/Games",
            controller.currentPath,
        )
        // And the tap must have OWNED the focus it acted on before descending. Asserted through the
        // unwind rather than directly, because Enter reloads and the landing focus is inside the child
        // by the time this line runs: if handleTap had dispatched CONFIRM without first requesting the
        // tapped tile's key, `rememberFocusInCurrentDirectory()` would have banked whatever tile was
        // focused BEFORE the finger landed, and backing out would return the player to the wrong tome.
        // That is also the recovery that requires a device check: one B undoes a mis-tap.
        controller.dispatch(ControllerAction.CANCEL)

        assertEquals("B unwinds the tap", "/root", controller.currentPath)
        assertEquals(
            "backing out must land on the tome the finger actually touched",
            FileBrowserFocus.tileKey("/root/Games"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `classifyPlatform is threaded through to the rendered partition`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, classifyPlatform = { item ->
            if (item.name == "Games") FileBrowserRecognition("game-boy-advance") else FileBrowserRecognition.None
        })

        controller.open()

        assertEquals("game-boy-advance", controller.partition.tomes.single().platformIdValue)
    }

    @Test
    fun `dispatch is refused once the browser's scope is no longer active`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        controller.dispatch(ControllerAction.CANCEL) // closes; scope pops back to Root

        val handled = controller.dispatch(ControllerAction.CONFIRM)

        assertFalse(handled)
    }

    // ────────────────────────────────────────────── per-directory focus memory (added 2026-08-14) ──
    //
    // Before this, load() refocused index 0 unconditionally, so every navigation step -- in OR out --
    // dumped the player back at the top of the listing. Revert `focusByDirectory` and every test in this
    // block fails on the same shape of assertion: it reports the FIRST tile where the remembered one
    // belongs.

    private fun deepTree(): MutableMap<String, MutableList<FileItem>> = mutableMapOf(
        "/root" to mutableListOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "Tools", path = "/root/Tools", isDirectory = true),
            FileItem(name = "readme.txt", path = "/root/readme.txt", extension = "txt"),
        ),
        "/root/Games" to mutableListOf(
            FileItem(name = "a.gba", path = "/root/Games/a.gba", extension = "gba"),
            FileItem(name = "b.gba", path = "/root/Games/b.gba", extension = "gba"),
            FileItem(name = "c.gba", path = "/root/Games/c.gba", extension = "gba"),
        ),
        "/root/Tools" to mutableListOf(FileItem(name = "t.txt", path = "/root/Tools/t.txt", extension = "txt")),
    )

    @Test
    fun `backing out of a directory restores the tile the player left, not index 0`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = deepTree())
        controller.open() // index 0 == the "Games" tome
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Tools"))
        assertTrue(controller.dispatch(ControllerAction.CONFIRM)) // descend into Tools

        assertTrue(controller.dispatch(ControllerAction.CANCEL)) // back out

        assertEquals(
            "coming back up must land on the folder the player went into, not the first tile of the " +
                "listing -- re-traversing a large ROM directory from the top on every step is the tax " +
                "this memory exists to remove",
            FileBrowserFocus.tileKey("/root/Tools"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `re-entering a directory restores where the player was inside it`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = deepTree())
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))
        controller.dispatch(ControllerAction.CONFIRM) // into Games; lands on a.gba (index 0)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games/c.gba"))
        controller.dispatch(ControllerAction.CANCEL) // out again

        controller.dispatch(ControllerAction.CONFIRM) // "Games" is focused again -- back in

        assertEquals(FileBrowserFocus.tileKey("/root/Games/c.gba"), authority.state.value.focused)
    }

    @Test
    fun `a remembered tile that vanished while the browser was elsewhere degrades to index 0`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = newController(authority, tree = tree)
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Tools"))
        controller.dispatch(ControllerAction.CONFIRM)
        tree.getValue("/root").removeIf { it.name == "Tools" } // deleted from under us

        controller.dispatch(ControllerAction.CANCEL)

        assertEquals(
            "a remembered key is validated against the incoming listing, never trusted",
            FileBrowserFocus.tileKey("/root/Games"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `focus memory is path-qualified -- a REORDERED listing restores the same file, not the same position`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = newController(authority, tree = tree)
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))
        controller.dispatch(ControllerAction.CONFIRM)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games/c.gba")) // index 2 of 3
        controller.dispatch(ControllerAction.CANCEL)
        // A new file sorts ahead of everything, pushing c.gba from index 2 to index 3.
        tree.getValue("/root/Games").add(FileItem(name = "0-new.gba", path = "/root/Games/0-new.gba", extension = "gba"))

        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals(
            "an index-qualified memory would have landed on whatever now sits at index 2",
            FileBrowserFocus.tileKey("/root/Games/c.gba"),
            authority.state.value.focused,
        )
        assertEquals("b.gba", controller.partition.files[2].name) // the tile the position now holds
    }

    @Test
    fun `a FIRST visit to a directory still lands on index 0 -- memory only speaks for places already seen`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = deepTree())
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))

        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals(FileBrowserFocus.tileKey("/root/Games/a.gba"), authority.state.value.focused)
    }

    @Test
    fun `in PICK mode a first visit still lands on the commit affordance, and a return visit restores it too`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = deepTree(), mode = FileBrowserMode.PICK)
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))

        controller.dispatch(ControllerAction.CONFIRM)
        assertEquals(
            "the pinned commit row is still index 0 on a fresh descent",
            FileBrowserFocus.commitKey("/root/Games"),
            authority.state.value.focused,
        )

        controller.dispatch(ControllerAction.CANCEL) // out; /root remembers the Games tome
        controller.dispatch(ControllerAction.CONFIRM) // back in
        assertEquals(
            "the commit row is a focus participant like any other, so it is what gets remembered here",
            FileBrowserFocus.commitKey("/root/Games"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `focus memory dies with the browse session -- a fresh open starts at index 0 again`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = deepTree())
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Tools"))
        controller.dispatch(ControllerAction.CONFIRM)
        controller.dispatch(ControllerAction.CANCEL) // /root now remembers Tools
        controller.close()

        controller.open()

        assertEquals(FileBrowserFocus.tileKey("/root/Games"), authority.state.value.focused)
    }

    // ──────────────────────────────────────────────────────── paging: L1/R1 (added 2026-08-14) ──

    private fun pagedTree(): Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(FileItem(name = "Big", path = "/root/Big", isDirectory = true)),
        // 20 files -> 4 file rows at FileBrowserFileColumns wide. Zero-padded so the listing's own
        // case-insensitive name sort is the same order the numbers read in.
        "/root/Big" to (1..20).map {
            val name = "f%02d.gba".format(it)
            FileItem(name = name, path = "/root/Big/$name", extension = "gba")
        },
    )

    @Test
    fun `PAGE_RIGHT jumps a screenful of rows and PAGE_LEFT comes back`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = pagedTree())
        controller.open()
        controller.dispatch(ControllerAction.CONFIRM) // into Big; lands on f01, row 0 column 0

        assertTrue(controller.dispatch(ControllerAction.PAGE_RIGHT))
        assertEquals(
            "one screenful of 107dp file rows is 3 of them, so row 0 pages to row 3, same column",
            FileBrowserFocus.tileKey("/root/Big/f16.gba"),
            authority.state.value.focused,
        )

        assertTrue(controller.dispatch(ControllerAction.PAGE_LEFT))
        assertEquals(FileBrowserFocus.tileKey("/root/Big/f01.gba"), authority.state.value.focused)
    }

    @Test
    fun `paging against the end of the listing leaves focus exactly where it was`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = pagedTree())
        controller.open()
        controller.dispatch(ControllerAction.CONFIRM)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Big/f16.gba")) // last row

        assertTrue("the press is still consumed -- this is a hard modal", controller.dispatch(ControllerAction.PAGE_RIGHT))

        assertEquals(FileBrowserFocus.tileKey("/root/Big/f16.gba"), authority.state.value.focused)
    }

    @Test
    fun `a listing shorter than one page clamps to its last row rather than overshooting`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority) // /root: one tome row, then one file row
        controller.open() // "Games", row 0 column 0

        assertTrue(controller.dispatch(ControllerAction.PAGE_RIGHT))

        assertEquals(
            "a screenful-long jump over a 2-row listing lands on the last row's first tile",
            FileBrowserFocus.tileKey("/root/pack.zip"),
            authority.state.value.focused,
        )
    }

    // ──────────────────────────────────────────────── teardown: close() must not walk the listing ──

    @Test
    fun `closing a large directory does not walk focus through every tile on the way out`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = pagedTree())
        controller.open()
        controller.dispatch(ControllerAction.CONFIRM) // into Big -- 20 registered tiles
        val standingOn = FileBrowserFocus.tileKey("/root/Big/f05.gba")
        authority.requestFocus(standingOn)

        controller.close()

        assertEquals(
            "unregistering the focused key while the scope is still active makes DefaultFocusAuthority " +
                "fall focus onto a sibling, which is then unregistered too -- a focus change per " +
                "remaining tile, leaving the scope's remembered key on a tile that no longer exists " +
                "instead of the one the player was standing on",
            standingOn,
            authority.rememberedFocusIn(controller.scope),
        )
    }

    @Test
    fun `close is idempotent -- a host's safety-net teardown after a dispatch-triggered close does not throw or double-pop`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        controller.dispatch(ControllerAction.CANCEL) // controller-triggered close

        controller.close() // e.g. FileBrowserSurface's DisposableEffect running on dispose

        assertEquals(FocusScope.Root, authority.state.value.scope)
    }

    // ── F-03: the directional glide that acknowledges a change of directory ──
    //
    // The directional glide acknowledges directory changes. The controller's share is arming a direction and an identity; the pixels are `FileBrowserSurface`'s
    // `fileBrowserNavigationGlide`, restated from `feature-library`'s `GamelistDestinationPageTransition`
    // (`LibraryScreen.kt:3844-3885`). `feature-files` has NO Compose harness, so nothing below sees a
    // rendered frame -- whether 22dp of travel at 160ms reads as "entering" versus "returning" is the
    // subject of device observation, and nothing here should be read as covering it.

    @Test
    fun `a mount does not glide -- there is no previous listing to have arrived from`() {
        // Deliberately asserted after a NAVIGATION, not on a fresh instance. A fresh controller's motion
        // is already null from its own initializer, so opening one proves nothing about `open()` and
        // would stay green with open()'s clear deleted -- a hollow test. Re-opening a controller that
        // HAS navigated is the only shape in which that line is load-bearing.
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        controller.dispatch(ControllerAction.CONFIRM) // into /root/Games -- arms a glide
        assertTrue(controller.navigationMotion != null)
        controller.close()

        controller.open()

        assertNull(controller.navigationMotion)
        assertFalse(fileBrowserNavigationGlideApplies(controller.currentPath, controller.navigationMotion))
    }

    @Test
    fun `entering a folder arms the glide for the CHILD, in the entering direction`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()

        controller.dispatch(ControllerAction.CONFIRM) // into /root/Games

        val motion = controller.navigationMotion
        assertEquals("/root/Games", motion?.path)
        assertEquals(FileBrowserGlideEnter, motion?.direction)
        assertTrue(fileBrowserNavigationGlideApplies(controller.currentPath, motion))
    }

    @Test
    fun `returning arms the OPPOSITE direction -- one symmetric fade would read as neither`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        controller.dispatch(ControllerAction.CONFIRM) // into /root/Games
        val entering = controller.navigationMotion

        controller.dispatch(ControllerAction.CANCEL) // back to /root

        val returning = controller.navigationMotion
        assertEquals("/root", returning?.path)
        assertEquals(FileBrowserGlideReturn, returning?.direction)
        assertEquals(
            "entering and returning are opposite movements; equal directions would render one fade",
            -1,
            entering!!.direction * returning!!.direction,
        )
    }

    @Test
    fun `re-entering the same folder glides again -- the sequence, not the path, is the identity`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        controller.dispatch(ControllerAction.CONFIRM) // into /root/Games
        val first = controller.navigationMotion
        controller.dispatch(ControllerAction.CANCEL) // back to /root
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))

        controller.dispatch(ControllerAction.CONFIRM) // into /root/Games AGAIN

        val second = controller.navigationMotion
        assertEquals("/root/Games", second?.path)
        assertTrue(
            "a path-only identity would leave the surface's animation key unchanged, so the second " +
                "descent into the same folder would play no glide at all",
            second!!.sequence > first!!.sequence,
        )
    }

    @Test
    fun `a search reveal arms a motion a plain Enter into the same folder does not`() {
        // R6-P2-02,  The row settle can travel onto the found file only if the motion says this Enter is a
        // reveal; at 471cc2ed nothing tells the two apart, so the reveal seats blind like a plain Enter.
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        controller.openSearch()
        val found = FileItem(name = "game.gba", path = "/root/Games/game.gba", extension = "gba")
        controller.publishSearchResults(listOf(found), FileBrowserSearchPhase.SETTLED)
        authority.requestFocus(FileBrowserSearchFocus.resultKey(found.path))
        assertTrue(controller.dispatch(ControllerAction.CONFIRM)) // Reveal -> revealPath
        val reveal = requireNotNull(controller.navigationMotion)
        assertEquals("/root/Games", reveal.path)
        assertEquals(FileBrowserGlideEnter, reveal.direction)
        assertEquals("the controller owns the target", FileBrowserFocus.tileKey(found.path), authority.state.value.focused)

        controller.dispatch(ControllerAction.CANCEL) // Up to /root
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))
        controller.dispatch(ControllerAction.CONFIRM) // a plain Enter into the same folder
        val plain = requireNotNull(controller.navigationMotion)

        assertNotEquals(
            "same path, same direction, sequence aside: a reveal must carry what a plain Enter does not, " +
                "or the settlement effect cannot travel for one and seat for the other",
            plain,
            reveal.copy(sequence = plain.sequence),
        )
    }

    @Test
    fun `a refresh does NOT glide -- an extraction changes the contents, not the state`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        controller.dispatch(ControllerAction.CONFIRM) // into /root/Games -- arms a glide
        val afterEnter = controller.navigationMotion

        controller.refresh()

        assertEquals(
            "refresh must not arm a new motion; the listing is the same directory it already was",
            afterEnter,
            controller.navigationMotion,
        )
    }

    @Test
    fun `a CANCEL at the root closes without arming a glide for a listing that will not be rendered`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()

        controller.dispatch(ControllerAction.CANCEL) // at root -> Close, not Up

        assertNull(controller.navigationMotion)
    }

    @Test
    fun `the glide only applies to the listing actually open -- a stale motion animates nothing`() {
        // The surface reads `navigationMotion` next to `currentPath`; the applies-predicate is what
        // keeps a motion armed for a directory the browser is no longer in from playing anyway.
        assertFalse(
            fileBrowserNavigationGlideApplies(
                currentPath = "/root",
                motion = FileBrowserNavigationMotion("/root/Games", FileBrowserGlideEnter, 3L),
            ),
        )
        assertFalse(fileBrowserNavigationGlideApplies("/root", null))
        assertTrue(
            fileBrowserNavigationGlideApplies(
                currentPath = "/root/Games",
                motion = FileBrowserNavigationMotion("/root/Games", FileBrowserGlideEnter, 3L),
            ),
        )
    }

    // ══════════════════════════════════ F-01 (the earlier change): delete on SELECT, the queue on START ══════
    //
    // `feature-files` has NO Compose or Robolectric harness,
    // so nothing below sees a rendered frame: these pin the SCOPE STACK, the FOCUS LANDINGS and the
    // COMMANDS. Whether the queue screen reads correctly, whether the ring is visible on a card, and
    // whether the delete feels instant require device observation and nothing here covers them.

    private fun deletion(root: String = "/tmp/.mimir-staging/.delete-1") = StagedDeletion(
        operationRootPath = root,
        entries = listOf(StagedDeletionEntry("/root/Games/b.gba", "$root/payload/b.gba")),
    )

    private fun queueEntry(id: Long = 1, state: FileQueueState) = FileQueueEntry(
        id = id,
        kind = JobKind.DELETE,
        title = "b.gba",
        subtitle = "Delete",
        state = state,
    )

    /** A MANAGE browser with both F-01 capabilities wired, standing on `/root/Games/b.gba`. */
    private fun wiredInGames(
        authority: DefaultFocusAuthority,
        tree: MutableMap<String, MutableList<FileItem>>,
        deleted: MutableList<String> = mutableListOf(),
        targets: MutableList<FileBrowserQueueTarget> = mutableListOf(),
    ): FileBrowserController {
        val controller = newController(authority, tree = tree, onDelete = { deleted.add(it) })
        controller.setQueueAction { targets.add(it) }
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))
        controller.dispatch(ControllerAction.CONFIRM) // into Games; a.gba, b.gba, c.gba
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games/b.gba"))
        return controller
    }

    // ── B-1: the two commands, at the controller ──

    /**
     * **F-1/R-3, 2026-08-18, supersedes this test's own former one-press claim.** SELECT no longer
     * deletes directly -- it opens the overlay Delete now lives inside as a row
     * (`FileBrowserCommand.OpenActionOverlay`). This pins the SAME two properties the deleted one-press
     * version pinned (the path reaches `onDelete` unmodified; the controller never re-reads the
     * directory itself), now over the two presses the door and the row each need.
     */
    @Test
    fun `X then the overlay's Delete row reports the focused tile's path via onDelete, and performs nothing itself`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val deleted = mutableListOf<String>()
        val controller = wiredInGames(authority, tree, deleted)

        assertTrue(controller.dispatch(ControllerAction.SECONDARY))
        assertTrue(
            "the door opens the overlay; nothing is deleted on this press alone",
            deleted.isEmpty(),
        )
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.DELETE))
        assertTrue(controller.dispatch(ControllerAction.CONFIRM))

        assertEquals(listOf("/root/Games/b.gba"), deleted)
        assertEquals(
            "the controller must not re-read the directory itself: staging is asynchronous, so a refresh " +
                "issued here would re-read a directory the file is still in",
            listOf("a.gba", "b.gba", "c.gba"),
            controller.partition.files.map { it.name },
        )
    }

    @Test
    fun `a MANAGE browser with no delete sink wired advertises nothing and deletes nothing`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, tree = deepTree()) // onDelete defaults null
        controller.open()

        assertFalse(
            "an unwired sink must make the whole binding vanish, or the footer promises a press that " +
                "silently does nothing -- the failure searchAvailable exists to prevent",
            controller.deleteAvailable,
        )
        assertTrue("still consumed -- this is a hard modal", controller.dispatch(ControllerAction.SECONDARY))
    }

    @Test
    fun `PICK is INCAPABLE of deleting or queueing -- every action, with both sinks wired`() {
        val authority = DefaultFocusAuthority()
        val deleted = mutableListOf<String>()
        val controller = newController(
            authority,
            tree = deepTree(),
            mode = FileBrowserMode.PICK,
            onDelete = { deleted.add(it) },
        )
        controller.setQueueAction { }
        controller.open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games")) // a real tile, not the commit row

        ControllerAction.values().forEach { controller.dispatch(it) }

        assertFalse("PICK cannot delete, regardless of UI restrictions", controller.deleteAvailable)
        assertFalse(controller.queueAvailable)
        assertTrue("a wired sink must still never be reached from PICK: $deleted", deleted.isEmpty())
        assertFalse("no press in PICK may open the queue screen", controller.queueOpen)
    }

    // ── B-2: the queue screen's controller state ──

    @Test
    fun `the queue scope derives from the browse scope AND equals FileBrowserQueueFocus Scope by default`() {
        val authority = DefaultFocusAuthority()
        val standard = newController(authority)
        assertEquals(
            "the brief names FileBrowserQueueFocus.Scope; the search precedent DERIVES from the browse " +
                "scope so two mounted browsers get two graphs. Deriving satisfies both -- but only while " +
                "these two string literals, in two different files, agree",
            FileBrowserQueueFocus.Scope,
            standard.queueScope,
        )

        val second = FileBrowserController(
            authority = DefaultFocusAuthority(),
            rootPath = "/root",
            listDirectory = { emptyList() },
            onChoose = {},
            onDismiss = {},
            scope = FocusScope("files-second"),
        )
        assertEquals(FocusScope("files-second/queue"), second.queueScope)
    }

    @Test
    fun `START opens the queue on its own scope, and CANCEL there closes the queue -- it never means Up`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)
        controller.publishQueue(listOf(queueEntry(state = FileQueueState.UndoAvailable(deletion()))))

        assertTrue(controller.dispatch(ControllerAction.SYSTEM_START))
        assertTrue(controller.queueOpen)
        assertEquals(controller.queueScope, authority.state.value.scope)
        assertEquals(
            FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.UNDO),
            authority.state.value.focused,
        )

        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertFalse(controller.queueOpen)
        assertEquals(controller.scope, authority.state.value.scope)
        assertEquals(
            "B on the queue must close the QUEUE, never also walk the player out of the directory they " +
                "are still standing in",
            "/root/Games",
            controller.currentPath,
        )
        assertEquals(
            "popScope restores the browse scope's memory, so the player lands back on the tile they left",
            FileBrowserFocus.tileKey("/root/Games/b.gba"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `a queue with ZERO focusable buttons is still escapable -- BUG#01's exact window`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)
        // Every transfer running: fileBrowserQueueKeys returns EMPTY, so the pushed scope holds nothing.
        controller.publishQueue(
            listOf(
                queueEntry(id = 1, state = FileQueueState.Running(0.2f)),
                queueEntry(id = 2, state = FileQueueState.Running(0.7f)),
            ),
        )

        assertTrue(controller.dispatch(ControllerAction.SYSTEM_START))
        assertEquals(controller.queueScope, authority.state.value.scope)
        assertNull("precondition: nothing is registered, so nothing is focused", authority.state.value.focused)
        assertFalse("and no direction can move anything -- this IS the dead-D-pad shape", authority.moveFocus(ControllerAction.NAV_DOWN))

        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertFalse(
            "CANCEL must not consult focus to answer, or a screen with nothing registered becomes a room " +
                "with no door -- the defect already observed on device",
            controller.queueOpen,
        )
        assertEquals(controller.scope, authority.state.value.scope)
        assertEquals(FileBrowserFocus.tileKey("/root/Games/b.gba"), authority.state.value.focused)
    }

    @Test
    fun `CONFIRM on a focused queue button reports the entry and the action it names`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val targets = mutableListOf<FileBrowserQueueTarget>()
        val controller = wiredInGames(authority, tree, targets = targets)
        controller.publishQueue(listOf(queueEntry(id = 7, state = FileQueueState.UndoAvailable(deletion()))))
        controller.dispatch(ControllerAction.SYSTEM_START)
        authority.requestFocus(FileBrowserQueueFocus.actionKey(7, FileBrowserQueueAction.KEEP_DELETED))

        assertTrue(controller.dispatch(ControllerAction.CONFIRM))

        assertEquals(
            "intent is read off the focused KEY, so a dispatch site cannot hold a second opinion about " +
                "which entry the ring is on",
            listOf(FileBrowserQueueTarget(7, FileBrowserQueueAction.KEEP_DELETED)),
            targets,
        )
    }

    @Test
    fun `an entry leaving the queue under a focused button repairs focus instead of stranding it`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)
        controller.publishQueue(
            listOf(
                queueEntry(id = 2, state = FileQueueState.UndoAvailable(deletion())),
                queueEntry(id = 1, state = FileQueueState.Done),
            ),
        )
        controller.dispatch(ControllerAction.SYSTEM_START)
        authority.requestFocus(FileBrowserQueueFocus.actionKey(2, FileBrowserQueueAction.UNDO))

        // Pressing Undo removes entry 2 from the store's queue -- i.e. the focused button's own card.
        controller.publishQueue(listOf(queueEntry(id = 1, state = FileQueueState.Done)))

        assertEquals(
            "the common case on this screen is a press that invalidates its own focus; leaving the ring " +
                "on a removed card's key would blank it and kill the D-pad",
            FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.DISMISS),
            authority.state.value.focused,
        )
    }

    @Test
    fun `close unwinds a pushed queue scope, so the next surface does not inherit a buried stack`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)
        controller.publishQueue(listOf(queueEntry(state = FileQueueState.Done)))
        controller.dispatch(ControllerAction.SYSTEM_START)
        assertEquals(controller.queueScope, authority.state.value.scope)

        controller.close()

        assertEquals(FocusScope.Root, authority.state.value.scope)
        assertTrue(authority.registeredKeysInScope(controller.queueScope).isEmpty())
        assertTrue(authority.registeredKeysInScope(controller.scope).isEmpty())
    }

    // ── B-4: where focus goes when the tile under the ring is deleted ──
    //
    // `DefaultFocusAuthority.removeEntryAndFallback` cannot catch this: `load()` calls `clearFocus()` BEFORE the tracker retires the departed key, so
    // `_state.value.focused` is already null when the entry is removed and that branch never matches.
    // Focus was landed by `load`'s closing `requestFocus(keys.firstOrNull())` -- index 0. Not a dead
    // D-pad, but the player thrown to the top of the listing on every delete.

    @Test
    fun `deleting the focused tile lands focus on its surviving neighbour, and the D-pad still works`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)
        // F-1/R-3: SELECT opens the overlay; the overlay's own Delete row is what now performs the
        // delete AND closes back to the browse scope -- both presses are needed to land back where this
        // test's own focus-fallback assertions below expect to read from (the browse scope's remembered
        // focus, not the overlay's).
        assertTrue(controller.dispatch(ControllerAction.SECONDARY))
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.DELETE))
        assertTrue(controller.dispatch(ControllerAction.CONFIRM))

        // What the store does when staging completes: the file is off the listing, then refresh().
        tree.getValue("/root/Games").removeIf { it.name == "b.gba" }
        controller.refresh()

        assertEquals(
            "the tile that slid into the gap, NOT index 0 -- being thrown to the top of a 200-entry ROM " +
                "directory on every delete is the same navigation tax focusByDirectory exists to remove",
            FileBrowserFocus.tileKey("/root/Games/c.gba"),
            authority.state.value.focused,
        )
        assertTrue(
            "and the landing must be a LIVE key: a focus sitting on a retired entry would leave moveFocus " +
                "with no bounds to score from, which on device is a dead D-pad on a screen that looks ready",
            authority.moveFocus(ControllerAction.NAV_LEFT),
        )
        assertEquals(FileBrowserFocus.tileKey("/root/Games/a.gba"), authority.state.value.focused)
    }

    @Test
    fun `deleting the LAST tile falls back to the one before it, not forward off the end`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games/c.gba"))

        tree.getValue("/root/Games").removeIf { it.name == "c.gba" }
        controller.refresh()

        assertEquals(FileBrowserFocus.tileKey("/root/Games/b.gba"), authority.state.value.focused)
    }

    @Test
    fun `emptying a directory entirely leaves focus null rather than on a key that no longer exists`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)

        tree.getValue("/root/Games").clear()
        controller.refresh()

        assertNull(
            "there is no honest landing in an empty directory. Null draws no ring and moves nothing, " +
                "which is true; a retired key would draw no ring and move nothing while LOOKING focused",
            authority.state.value.focused,
        )
        assertTrue("and the player can still leave", controller.dispatch(ControllerAction.CANCEL))
        assertEquals("/root", controller.currentPath)
    }

    @Test
    fun `an extraction's refresh still keeps the player exactly where they were -- unchanged by B-4`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)

        // Extraction ADDS files; the focused tile survives, so the per-directory memory answers and the
        // neighbour walk must not run at all.
        tree.getValue("/root/Games").add(FileItem(name = "0-new.gba", path = "/root/Games/0-new.gba", extension = "gba"))
        controller.refresh()

        assertEquals(FileBrowserFocus.tileKey("/root/Games/b.gba"), authority.state.value.focused)
    }

    @Test
    fun `a fresh open still starts at index 0 -- the neighbour walk must not resurrect a closed session`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)
        controller.dispatch(ControllerAction.CANCEL) // back to /root
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Tools"))
        controller.close()

        controller.open()

        assertEquals(
            "open() re-loads the SAME path it last held, so a neighbour walk gated on 'same directory' " +
                "alone would restore the previous session's tile through the authority's own focus memory " +
                "-- silently undoing the clear open() just performed",
            FileBrowserFocus.tileKey("/root/Games"),
            authority.state.value.focused,
        )
    }

    // ── B-4, the P0 pairing: an async delete completing while the queue screen is up ──

    @Test
    fun `a delete completing while the queue is open must not take the queue's focus`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val targets = mutableListOf<FileBrowserQueueTarget>()
        val controller = wiredInGames(authority, tree, targets = targets)
        // F-1/R-3: SELECT opens the overlay; the Delete row is what performs the delete AND closes back
        // to the browse scope. Both presses are needed here -- left mid-overlay, the very next
        // SYSTEM_START below would route to the overlay's own hard-modal dispatch (which swallows START,
        // design spec §5) instead of opening the queue this test depends on.
        controller.dispatch(ControllerAction.SECONDARY)
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.DELETE))
        controller.dispatch(ControllerAction.CONFIRM)
        // The store enqueues a Running entry immediately; a running card has no buttons.
        controller.publishQueue(listOf(queueEntry(state = FileQueueState.Running(0f))))
        // The player presses START to watch it -- the normal sequence, not a race.
        controller.dispatch(ControllerAction.SYSTEM_START)
        // Staging finishes: the entry gains its Undo button, and the store calls refresh().
        controller.publishQueue(listOf(queueEntry(state = FileQueueState.UndoAvailable(deletion()))))
        val onTheUndoButton = FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.UNDO)
        assertEquals("precondition: the queue's own repair seated the ring", onTheUndoButton, authority.state.value.focused)

        tree.getValue("/root/Games").removeIf { it.name == "b.gba" }
        controller.refresh()

        assertEquals(controller.queueScope, authority.state.value.scope)
        assertEquals(
            "load() runs clearFocus() and requestFocus() -- both act on the ACTIVE scope. Unguarded, the " +
                "clear nulls the QUEUE's focused button and parks the cleared-sentinel in the queue's own " +
                "memory, and the requestFocus of a browse key is then refused for scope mismatch. The " +
                "player is left on a queue screen with a dead D-pad: BUG#01, on the screen this PR adds",
            onTheUndoButton,
            authority.state.value.focused,
        )
        assertNotNull(authority.state.value.focused)
        // Asserted through the SINK, not through dispatch's return value: dispatchQueue answers `true`
        // unconditionally (it is a hard modal), so `assertTrue(dispatch(CONFIRM))` would pass over a
        // screen whose focus had been nulled and would prove nothing at all.
        controller.dispatch(ControllerAction.CONFIRM)
        assertEquals(
            "the Undo the player came to this screen for must still be reachable after the refresh",
            listOf(FileBrowserQueueTarget(1, FileBrowserQueueAction.UNDO)),
            targets,
        )
    }

    @Test
    fun `leaving the queue after that delete lands on the neighbour, with no repair pass`() {
        val authority = DefaultFocusAuthority()
        val tree = deepTree()
        val controller = wiredInGames(authority, tree)
        // F-1/R-3: this test's own name depends on the QUEUE actually being open below -- left mid-overlay
        // (SELECT alone), SYSTEM_START would route to the overlay's own hard modal and swallow it (design
        // spec §5), never opening the queue at all, and the two-scope pop this test's neighbour-focus
        // assertion depends on would happen to land on the same tile BY COINCIDENCE, through the wrong
        // screen -- exactly the vacuous-pass shape this repo's evidence standard refuses. Complete the
        // overlay's own Delete row first, same as every other SELECT call site above.
        controller.dispatch(ControllerAction.SECONDARY)
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.DELETE))
        controller.dispatch(ControllerAction.CONFIRM)
        controller.publishQueue(listOf(queueEntry(state = FileQueueState.Running(0f))))
        controller.dispatch(ControllerAction.SYSTEM_START)
        assertEquals(
            "precondition: the queue must genuinely be open here, or CANCEL below leaves nothing to test",
            controller.queueScope,
            authority.state.value.scope,
        )
        controller.publishQueue(listOf(queueEntry(state = FileQueueState.UndoAvailable(deletion()))))
        tree.getValue("/root/Games").removeIf { it.name == "b.gba" }
        controller.refresh()

        assertTrue(controller.dispatch(ControllerAction.CANCEL)) // leave the queue

        assertEquals(
            "with browse inactive, load() writes the landing into the browse scope's focus MEMORY instead " +
                "of requesting it -- so popScope hands it over on the way out, with no second requestFocus " +
                "and no frame where nothing is focused",
            FileBrowserFocus.tileKey("/root/Games/c.gba"),
            authority.state.value.focused,
        )
        assertTrue(authority.moveFocus(ControllerAction.NAV_LEFT))
    }

    @Test
    fun `a direction that is neither enter nor return is rejected at construction`() {
        // The reference constrains the same field the same way (`LibraryViewModel.kt:74-76`). A 0 would
        // silently render a fade with no travel -- exactly the "reads as neither" failure F-03 warns of.
        val thrown = runCatching { FileBrowserNavigationMotion("/root", 0, 1L) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }
}
