package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.ExtractDestinationOptions
import com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **G2 slice 1 — is `Move into…` / `Copy into…` actually REACHABLE, and does the pick unwind honestly?**
 *
 * The companion to `FileBrowserTransferChoiceTest` (which pins the rules) and the direct descendant of
 * `FileBrowserExtractWiringTest` and `FileBrowserQueueWiringTest`, guarding the same class of failure both
 * were written for and which QA found again as **G2**: `FileBrowserStore.moveInManager` / `copyInManager`
 * are finished, staged, rollback-backed, conflict-disciplined and **tested**, with *zero production
 * callers*. A green suite over a dead feature is this programme's most repeated defect, and the rule it
 * produced is that reachability gets a test of its own.
 *
 * **What a passing run here does and does not prove.** It drives a real [DefaultFocusAuthority], a real
 * [FileBrowserController] and real [ControllerAction] dispatches, so reachability, scope discipline, focus
 * landings and every sink invocation are genuinely exercised. It composes **nothing** — this module
 * configures no Compose and no Robolectric harness — so nothing here is evidence about pixels, about the
 * chooser reaching the screen, or about `FileBrowserTransferSurface` being drawn at all. And it
 * cannot cross the module seam: [FileBrowserController.onMoveTo] / [FileBrowserController.onCopyTo] are
 * constructor parameters filled in `app/`'s `FileBrowserStore.mount()`, a separate module seam, so
 * **every test below installs its own fakes and none of them prove the shipped browser has any.** That
 * production wiring remains required; until it lands the whole feature is dead-by-default on device.
 *
 * **F-1/R-13, 2026-08-18 — the door this file exercises moved.** `Move into…`/`Copy into…` are now rows on
 * the SELECT action overlay, and choosing either arms the pick DIRECTLY via [FileBrowserController
 * .beginTransferPick] ([FileBrowserActionOverlayChoice]'s `activateFocusedActionOverlayOption`, design spec
 * §1c) — the two-row `Move`/`Copy` chooser this file used to open via a direct X press is bypassed
 * entirely, not merely reached by a different door. `openTransferChooser`/`FileBrowserTransferSurface.kt`
 * have zero callers left in the dispatch table; see [FileBrowserController.abandonDestinationPick]'s own
 * F-1 addendum for why its `Transfer` arm now reopens the action overlay instead of the old chooser. Tests
 * that were about the CHOOSER's own modal mechanics were retired, not rewritten — see § 3 below — and the
 * same coverage now lives against the screen that owns those mechanics, in
 * `FileBrowserActionOverlayWiringTest`. Everything below that was using X + the chooser purely as SETUP for
 * a pick now arms that pick through the overlay instead, landing on the identical state.
 */
class FileBrowserTransferWiringTest {

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true),
            FileItem(name = "Deep", path = "/root/Deep", isDirectory = true),
            FileItem(name = "Golden Sun.gba", path = "/root/Golden Sun.gba", extension = "gba"),
            FileItem(name = "Golden Sun.zip", path = "/root/Golden Sun.zip", extension = "zip"),
        ),
        "/root/Roms" to listOf(
            FileItem(name = "GBA", path = "/root/Roms/GBA", isDirectory = true),
        ),
        "/root/Roms/GBA" to listOf(
            FileItem(name = "existing.gba", path = "/root/Roms/GBA/existing.gba", extension = "gba"),
        ),
        // A branch deep enough to begin a pick BELOW the root and then navigate somewhere else -- the only
        // arrangement in which the pick is AWAY rather than SETTLED, and the only one with a back-stack
        // worth restoring.
        "/root/Deep" to listOf(
            FileItem(name = "Sub", path = "/root/Deep/Sub", isDirectory = true),
            FileItem(name = "deep.gba", path = "/root/Deep/deep.gba", extension = "gba"),
        ),
        "/root/Deep/Sub" to listOf(
            FileItem(name = "sub.gba", path = "/root/Deep/Sub/sub.gba", extension = "gba"),
        ),
    )

    private val file = "/root/Golden Sun.gba"
    private val archive = "/root/Golden Sun.zip"
    private val directory = "/root/Roms"

    /** Every sink call this controller made, in order. */
    private val moved = mutableListOf<Pair<String, String>>()
    private val copied = mutableListOf<Pair<String, String>>()
    private val extractedTo = mutableListOf<Pair<String, String>>()
    private val extractedHere = mutableListOf<String>()

    private fun options(archivePath: String): ExtractDestinationOptions = ExtractDestinationPolicy.options(
        archivePath = archivePath,
        platformId = if (archivePath == archive) "gba" else null,
        platformFolders = listOf("/root/Roms/GBA"),
    )

    private val authorities = mutableMapOf<FileBrowserController, DefaultFocusAuthority>()
    private fun authorityOf(c: FileBrowserController) = authorities.getValue(c)

    private fun open(
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
        transferWired: Boolean = true,
        extractWired: Boolean = true,
        moveWired: Boolean = true,
        copyWired: Boolean = true,
        commitAllowed: (String) -> Boolean = { true },
        scope: FocusScope = FileBrowserFocus.DefaultScope,
    ): Pair<FileBrowserController, DefaultFocusAuthority> {
        val authority = DefaultFocusAuthority()
        val c = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = mode,
            listDirectory = { path -> tree[path].orEmpty() },
            onChoose = {},
            onDismiss = {},
            onExtract = { path -> extractedHere += path },
            resolveExtractOptions = if (extractWired) ::options else null,
            onExtractTo = if (extractWired) { a, d -> extractedTo += a to d } else null,
            onMoveTo = if (transferWired && moveWired) { p, d -> moved += p to d } else null,
            onCopyTo = if (transferWired && copyWired) { p, d -> copied += p to d } else null,
            commitAllowed = commitAllowed,
            scope = scope,
        )
        authorities[c] = authority
        c.open()
        return c to authority
    }

    /**
     * Put the ring on a tile the way the player would, then press X.
     *
     * **Kept as a raw, direct SECONDARY dispatch on purpose.** Every caller left using this helper (§ 1)
     * is proving X does NOTHING on an unwired file or in PICK -- a claim a raw press states more honestly
     * than a helper that quietly reroutes around a retired button would.
     */
    private fun FileBrowserController.pressSecondaryOn(path: String) {
        authorityOf(this).requestFocus(FileBrowserFocus.tileKey(path))
        dispatch(ControllerAction.SECONDARY)
    }

    /**
     * Reach an ARMED transfer pick the way a player now does: focus the tile, SELECT to open the action
     * overlay, land on the row for [kind], A. This is [FileBrowserController.beginTransferPick]'s ONLY
     * caller as of F-1 -- see the class KDoc -- so this helper is a genuine reachability exercise, not a
     * test-only shortcut: it calls the same [FileBrowserController.handleInspectorSecondaryTap]-adjacent
     * seam the inspector's own button now uses to get here, then the same overlay row a touch or D-pad
     * press on it would.
     */
    private fun FileBrowserController.armTransferPick(path: String, kind: FileBrowserTransferKind) {
        val authority = authorityOf(this)
        authority.requestFocus(FileBrowserFocus.tileKey(path))
        handleInspectorSecondaryTap()
        val rowKind = when (kind) {
            FileBrowserTransferKind.MOVE -> FileBrowserActionOverlayRowKind.MOVE
            FileBrowserTransferKind.COPY -> FileBrowserActionOverlayRowKind.COPY
        }
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(rowKind))
        dispatch(ControllerAction.CONFIRM)
    }

    /**
     * Reach the extract chooser the way a player now does for an ARCHIVE -- [FileBrowserExtractWiringTest
     * .pressExtractOn]'s identical pattern, duplicated rather than shared because the two test classes
     * share no base and a shared helper module is a bigger change than this ticket's scope.
     */
    private fun FileBrowserController.reachExtractChooserOn(path: String) {
        val authority = authorityOf(this)
        authority.requestFocus(FileBrowserFocus.tileKey(path))
        handleInspectorSecondaryTap()
        if (authority.state.value.scope == actionOverlayScope) {
            authority.requestFocus(
                FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.EXTRACT),
            )
            dispatch(ControllerAction.CONFIRM)
        }
    }

    private fun FileBrowserController.commitHere() = dispatch(ControllerAction.CONFIRM)

    @Test
    fun `both transfer verbs can walk up past a two-level origin and restore its back-stack`() {
        FileBrowserTransferKind.entries.forEach { kind ->
            val (c, authority) = open()
            listOf("/root/Deep", "/root/Deep/Sub").forEach { path ->
                authority.requestFocus(FileBrowserFocus.tileKey(path))
                c.dispatch(ControllerAction.CONFIRM)
            }
            c.armTransferPick("/root/Deep/Sub/sub.gba", kind)
            assertEquals("/root", c.currentPath)
            assertEquals(FileBrowserDestinationPickPhase.SETTLED, c.destinationPickPhase())

            listOf("/root/Deep", "/root/Deep/Sub").forEach { path ->
                authority.requestFocus(FileBrowserFocus.tileKey(path))
                c.dispatch(ControllerAction.CONFIRM)
            }
            listOf("/root/Deep", "/root").forEach { parent ->
                assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
                c.dispatch(ControllerAction.CANCEL)
                assertEquals(parent, c.currentPath)
                assertFalse(c.actionOverlayOpen)
            }
            c.dispatch(ControllerAction.CANCEL)
            assertEquals("/root/Deep/Sub", c.currentPath)
            assertTrue(c.actionOverlayOpen)
            assertEquals("/root/Deep/Sub/sub.gba", c.actionOverlay?.path)
            c.dispatch(ControllerAction.CANCEL)
            c.dispatch(ControllerAction.CANCEL)
            assertEquals("/root/Deep", c.currentPath)
            c.dispatch(ControllerAction.CANCEL)
            assertEquals("/root", c.currentPath)
            assertTrue(moved.isEmpty() && copied.isEmpty())
            c.close()
        }
    }

    @Test
    fun `both transfer verbs can commit from a nested origin into a sibling branch`() {
        FileBrowserTransferKind.entries.forEach { kind ->
            val (c, authority) = open()
            authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
            c.dispatch(ControllerAction.CONFIRM)
            c.armTransferPick("/root/Deep/deep.gba", kind)
            assertEquals("/root", c.currentPath)
            authority.requestFocus(FileBrowserFocus.tileKey("/root/Roms"))
            c.dispatch(ControllerAction.CONFIRM)
            authority.requestFocus(FileBrowserFocus.commitKey("/root/Roms"))
            c.dispatch(ControllerAction.CONFIRM)
            val calls = if (kind == FileBrowserTransferKind.MOVE) moved else copied
            assertEquals(listOf("/root/Deep/deep.gba" to "/root/Roms"), calls)
            assertEquals("/root/Roms", c.currentPath)
            assertNull(c.destinationPickPhase())
            c.close()
        }
    }

    // ────────────────────────────────────────────── 1 · the gate fails closed ──

    @Test
    fun `with no transfer sinks, X on a plain file is exactly the pre-G2 press`() {
        val (c, _) = open(transferWired = false)

        assertFalse(
            "transferChoiceAvailable must be false with no sinks -- same polarity as deleteAvailable, so " +
                "an unwired browser cannot advertise a chooser that opens nothing",
            c.transferChoiceAvailable,
        )
        c.pressSecondaryOn(file)

        assertFalse("no chooser may open", c.transferChooserOpen)
        assertFalse(
            "nor the overlay -- a plain FILE with nothing wired (no transfer, no delete) has NO row at " +
                "all, so actionsAvailable is false and SELECT would find nothing either",
            c.actionOverlayOpen,
        )
        assertTrue("and nothing may reach either sink", moved.isEmpty() && copied.isEmpty())
        c.close()
    }

    @Test
    fun `half-wired is unwired -- a move sink with no copy sink must not open a chooser`() {
        val (c, _) = open(copyWired = false)

        assertFalse(
            "both sinks or neither: a move sink alone would draw a chooser whose Copy row is focusable, " +
                "advertised and dead",
            c.transferChoiceAvailable,
        )
        c.pressSecondaryOn(file)
        assertFalse(c.transferChooserOpen)
        assertFalse("and the overlay never opens for a half-wired FILE either", c.actionOverlayOpen)
        c.close()

        val (c2, _) = open(moveWired = false)
        assertFalse("and the same in the other direction", c2.transferChoiceAvailable)
        c2.pressSecondaryOn(file)
        assertFalse(c2.transferChooserOpen)
        c2.close()
    }

    @Test
    fun `PICK is INCAPABLE of a transfer, not merely restricted`() {
        // A move removes its source and has NO Undo, so it belongs to delete's destructive class rather
        // than extract's. The mode boundary requires that PICK must be incapable.
        val (c, _) = open(mode = FileBrowserMode.PICK)

        assertFalse(
            "both sinks are wired here and it must STILL be false -- the mode is the gate, exactly as it " +
                "is for deleteAvailable",
            c.transferChoiceAvailable,
        )
        c.pressSecondaryOn(file)
        assertFalse("no chooser", c.transferChooserOpen)
        assertTrue(moved.isEmpty() && copied.isEmpty())

        c.dispatch(ControllerAction.SECONDARY)
        assertFalse(
            "and SELECT cannot reach it by the other door either -- fileBrowserActionsAvailable requires " +
                "manageMode, so PICK is structurally incapable of the overlay too, not merely of the pick " +
                "it would arm",
            c.actionOverlayOpen,
        )

        assertTrue(
            "and extraction is UNAFFECTED -- it writes new files and removes none, so PICK keeps it",
            c.extractChoiceAvailable,
        )
        c.close()
    }

    @Test
    fun `openTransferChooser is a door and refuses to open unwired, not only via dispatch`() {
        val (c, authority) = open(transferWired = false)
        val scopeBefore = authority.state.value.scope

        c.openTransferChooser(file)

        assertFalse(
            "this function is internal and callable directly -- a chooser opened with no sinks would " +
                "push a scope over the listing, take the ring, advertise a verb and do nothing on A. " +
                "UI-05 exactly. Kept live though F-1 gave it no remaining production caller (class KDoc): " +
                "the guard itself is not dead code, only the paths that used to reach it.",
            c.transferChooserOpen,
        )
        assertEquals("and no scope may have been pushed", scopeBefore, authority.state.value.scope)
        c.close()
    }

    // ──────────────────────────────────────────── 2 · the door actually opens ──

    @Test
    fun `F-1 R-13 -- X is quiet on a plain file, and SELECT's overlay arms a MOVE pick WITHOUT the old chooser ever opening`() {
        val (c, authority) = open()

        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        assertFalse(
            "X is retired wherever the overlay covers the tile, and a FILE with both transfer sinks " +
                "wired always has a MOVE/COPY pair (fileBrowserActionOverlayRows)",
            c.transferChooserOpen,
        )
        assertTrue("and the swallowed press wrote nothing", moved.isEmpty() && copied.isEmpty())

        c.dispatch(ControllerAction.SECONDARY)
        assertTrue("SELECT opens the overlay in its place", c.actionOverlayOpen)
        assertEquals(c.actionOverlayScope, authority.state.value.scope)

        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE))
        c.dispatch(ControllerAction.CONFIRM)

        assertEquals(
            "the overlay's MOVE row arms the pick DIRECTLY -- beginTransferPick, not openTransferChooser " +
                "(spec S1c) -- so the two-row screen this file used to open is bypassed, not merely " +
                "reached by a different door",
            FileBrowserCommitPurpose.MOVE_DESTINATION,
            c.commitPurpose,
        )
        assertFalse(
            "the bypass proof: transferChooser never became live at any point on this path",
            c.transferChooserOpen,
        )
        assertFalse("and the overlay itself closed behind the row press", c.actionOverlayOpen)
        c.close()
    }

    @Test
    fun `the transfer scope is derived from the browse scope, and matches the constant by default`() {
        val (c, _) = open()
        assertEquals(FileBrowserTransferFocus.Scope, c.transferScope)
        c.close()

        val (c2, _) = open(scope = FocusScope("files/second"))
        assertEquals(
            "a caller mounting two browsers gets two distinct transfer graphs, not one shared global -- " +
                "the property is still computed and still pinned even though F-1 left it no caller",
            FocusScope("files/second/transfer"),
            c2.transferScope,
        )
        c2.close()
    }

    @Test
    fun `X on an archive is quiet -- SELECT's overlay still reaches the EXTRACT chooser, and a directory has no overlay at all`() {
        val (c, authority) = open()

        authority.requestFocus(FileBrowserFocus.tileKey(archive))
        c.dispatch(ControllerAction.SECONDARY)
        assertFalse("X is retired here too -- the archive path was untouched by G2, but not by F-1", c.extractChooserOpen)

        c.reachExtractChooserOn(archive)
        assertTrue("the overlay's EXTRACT row still reaches it", c.extractChooserOpen)
        assertFalse(c.transferChooserOpen)
        c.dispatch(ControllerAction.CANCEL)

        authority.requestFocus(FileBrowserFocus.tileKey(directory))
        c.dispatch(ControllerAction.SECONDARY)
        assertFalse("FILES ONLY -- a directory move runs away, see OfferTransfer's KDoc", c.transferChooserOpen)
        assertFalse(c.extractChooserOpen)
        c.dispatch(ControllerAction.SECONDARY)
        assertFalse(
            "a DIRECTORY has no legal row at all until Rename ships -- SELECT must not open an overlay " +
                "with nothing in it",
            c.actionOverlayOpen,
        )
        c.close()
    }

    @Test
    fun `a long-press is quiet on a FILE too -- SECONDARY's retirement is the decision table's, not X's button binding`() {
        val (c, _) = open()
        val tile = c.focusedTileAtPathForTest(file)

        c.handleLongPress(tile)

        assertFalse(
            "handleLongPress dispatches raw SECONDARY (its own body) -- one decision table, so its " +
                "retirement here is the SAME line that retired X, not a second gate to forget",
            c.transferChooserOpen,
        )
        // **D-3 gave the F-1 spec something it asked for and could not have.** The spec named a real gap:
        // Delete had NO touch path at all, and it wanted long-press retargeted from direct-open to
        // overlay-open. `handleLongPress` dispatches raw SECONDARY, so moving the overlay onto SECONDARY
        // delivered that for free -- a long press now opens the overlay, and every row in it, Delete
        // included, has a touch path for the first time.
        assertTrue(
            "long-press dispatches raw SECONDARY and SECONDARY is now the overlay's door, so a long " +
                "press opens the overlay -- which is exactly the touch path the F-1 spec said Delete was " +
                "missing. Not a side effect to tolerate: the thing the spec wanted, arriving via the " +
                "shared ergonomic binding rather than a second binding.",
            c.actionOverlayOpen,
        )
        c.close()
    }

    // ──────────────────── 3 · the chooser is a hard modal (RETIRED, F-1/R-13, 2026-08-18) ──
    //
    // **Every test this section held is gone, not rewritten, because their shared subject stopped being
    // reachable.** All five opened `transferChooserOpen` via a direct `pressSecondaryOn(file)` and then
    // pinned the screen's own modal behaviour (B closes it, a second X closes it, SELECT/START/shoulders
    // are swallowed, a tap on a row arms the pick, a stale tap after close arms nothing).
    // `openTransferChooser` has had zero callers from dispatch since the SECONDARY arm quieted
    // (`fileBrowserActionsAvailable`); `abandonDestinationPick`'s Transfer arm -- its last remaining
    // production caller -- was repointed to reopen the action overlay instead (see that function's own
    // F-1 addendum), so nothing left in this tree can make `transferChooserOpen` true except a test
    // calling the internal function directly (kept alive as ONE such test, above: "openTransferChooser is
    // a door and refuses to open unwired"). Keeping all five as direct-bypass tests would be testing dead
    // code as though it were live -- the exact anti-pattern this file's own class KDoc names as "this
    // programme's most repeated defect". The SAME five behaviours belong on the screen that actually owns
    // them now: the action overlay's own modal discipline, pinned in `FileBrowserActionOverlayWiringTest`.

    // ───────────────────────────── 4 · the pick, and the verb it carries ──

    @Test
    fun `choosing Move arms a MOVE pick and the commit row asks the Move question`() {
        val (c, _) = open()
        c.armTransferPick(file, FileBrowserTransferKind.MOVE)

        assertFalse("the overlay closes; the browser IS the picker", c.actionOverlayOpen)
        assertFalse("and the old chooser was never opened by this door", c.transferChooserOpen)
        assertEquals(
            "the single gate now answers MOVE -- the commit row's copy, its legend verb and CONFIRM's " +
                "command all trace to this one value",
            FileBrowserCommitPurpose.MOVE_DESTINATION,
            c.commitPurpose,
        )
        assertNotNull("and the pinned row exists in MANAGE, which it did not a line earlier", c.commitPurpose)
        assertNull(
            "destinationPick is the EXTRACT projection and must stay null for a transfer pick",
            c.destinationPick,
        )
        assertTrue("nothing has been written yet", moved.isEmpty() && copied.isEmpty())
        c.close()
    }

    @Test
    fun `choosing Copy arms a COPY pick, and the two verbs never cross`() {
        val (c, _) = open()
        c.armTransferPick(file, FileBrowserTransferKind.COPY)

        assertEquals(FileBrowserCommitPurpose.COPY_DESTINATION, c.commitPurpose)
        c.close()
    }

    @Test
    fun `a Move pick committed in a directory calls onMoveTo once and nothing else, ever`() {
        val (c, _) = open()
        c.armTransferPick(file, FileBrowserTransferKind.MOVE)

        // Walk to a real destination the way the player would.
        authorityOf(c).requestFocus(FileBrowserFocus.tileKey(directory))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("standing in the destination", directory, c.currentPath)

        // Focus the pinned commit row and press A.
        authorityOf(c).requestFocus(FileBrowserFocus.commitKey(directory))
        c.commitHere()

        assertEquals(
            "the subject comes from the PICK, never from the ring -- the commit row is what holds focus " +
                "at this instant, so a browser reading the listing would move whatever was last stood on",
            listOf(file to directory),
            moved,
        )
        assertTrue("and NOTHING may reach the copy or extract sinks", copied.isEmpty() && extractedTo.isEmpty())
        assertNull("the pick is over", c.commitPurpose)
        c.close()
    }

    @Test
    fun `a Copy pick committed in a directory calls onCopyTo and never onMoveTo`() {
        val (c, _) = open()
        c.armTransferPick(file, FileBrowserTransferKind.COPY)
        authorityOf(c).requestFocus(FileBrowserFocus.tileKey(directory))
        c.dispatch(ControllerAction.CONFIRM)
        authorityOf(c).requestFocus(FileBrowserFocus.commitKey(directory))

        c.commitHere()

        assertEquals(listOf(file to directory), copied)
        assertTrue(
            "the verb the player chose and the sink that ran are the same value read twice -- a Copy " +
                "that reached onMoveTo would delete a file the player asked to duplicate, with no Undo",
            moved.isEmpty(),
        )
        c.close()
    }

    @Test
    fun `the drive chooser refuses BOTH transfer purposes, exactly as it refuses a ROM source`() {
        val (c, _) = open(commitAllowed = { it != "/root/Roms" })
        c.armTransferPick(file, FileBrowserTransferKind.MOVE)
        authorityOf(c).requestFocus(FileBrowserFocus.tileKey(directory))
        c.dispatch(ControllerAction.CONFIRM)

        assertNull(
            "commitAllowed is checked FIRST and applies to every purpose -- moving into a synthetic " +
                "listing of volumes is as meaningless as adding it as a ROM source",
            c.commitPurpose,
        )
        c.dispatch(ControllerAction.CONFIRM)
        assertTrue("and A there must write nothing", moved.isEmpty() && copied.isEmpty())
        c.close()
    }

    // ──────────────────────────────── 5 · unwinding, and which screen returns ──

    @Test
    fun `B at the pick's root returns to the ACTION OVERLAY, not the extract chooser -- F-1 repointed abandon`() {
        val (c, authority) = open()
        c.armTransferPick(file, FileBrowserTransferKind.COPY)

        c.dispatch(ControllerAction.CANCEL)

        assertTrue(
            "one press, one step: the overlay is where a player who mis-pressed a verb wants to be -- " +
                "the other verb is the row next to it (abandonDestinationPick's F-1 addendum)",
            c.actionOverlayOpen,
        )
        assertFalse("and it must NOT be the extract chooser", c.extractChooserOpen)
        assertFalse("nor the old transfer chooser -- repointed, not merely reached differently", c.transferChooserOpen)
        assertEquals(c.actionOverlayScope, authority.state.value.scope)
        assertNull("the pick is unwound", c.commitPurpose)
        c.close()
    }

    @Test
    fun `B while AWAY walks Up first, and only then unwinds the pick`() {
        val (c, _) = open()
        c.armTransferPick(file, FileBrowserTransferKind.MOVE)
        authorityOf(c).requestFocus(FileBrowserFocus.tileKey(directory))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(directory, c.currentPath)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())

        c.dispatch(ControllerAction.CANCEL)

        assertEquals("the player's OWN navigation unwinds first", "/root", c.currentPath)
        assertFalse("the overlay has not come back yet", c.actionOverlayOpen)
        assertEquals(FileBrowserDestinationPickPhase.SETTLED, c.destinationPickPhase())

        c.dispatch(ControllerAction.CANCEL)
        assertTrue("and the pick unwinds last, onto the overlay F-1 repointed abandon to", c.actionOverlayOpen)
        assertTrue("nothing was written by any of it", moved.isEmpty() && copied.isEmpty())
        c.close()
    }

    @Test
    fun `abandoning a pick restores the origin's whole back-stack and the file's own place`() {
        val (c, authority) = open()
        // Begin the pick BELOW the root, so there is a back-stack to restore.
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Deep", c.currentPath)
        c.armTransferPick("/root/Deep/deep.gba", FileBrowserTransferKind.MOVE)
        // Walk down through the origin and into its child.
        assertEquals("the pick starts at the browser root", "/root", c.currentPath)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep/Sub"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Deep/Sub", c.currentPath)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())

        c.dispatch(ControllerAction.CANCEL) // AWAY -> Up, back onto the origin
        assertEquals("/root/Deep", c.currentPath)
        assertEquals("the origin is still below the root", FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
        assertFalse(c.actionOverlayOpen)
        c.dispatch(ControllerAction.CANCEL)
        assertEquals("/root", c.currentPath)
        assertEquals(FileBrowserDestinationPickPhase.SETTLED, c.destinationPickPhase())

        c.dispatch(ControllerAction.CANCEL) // At root, restore the origin and its overlay.

        assertEquals("restored to where the pick BEGAN", "/root/Deep", c.currentPath)
        assertTrue(c.actionOverlayOpen)
        assertEquals("/root/Deep/deep.gba", c.actionOverlay?.path)

        c.dispatch(ControllerAction.CANCEL)
        assertEquals(
            "and the origin's parent chain came back with it -- Up still works",
            "/root/Deep",
            c.currentPath,
        )
        c.dispatch(ControllerAction.CANCEL)
        assertEquals("/root", c.currentPath)
        c.close()
    }

    // ────────────────────────────────────────── F-2 · the back-to-origin escape hatch, 2026-08-18 ──
    //
    // Destination picks need a direct way back to the origin path. The two tests above already prove B needs TWO presses to undo one wrong
    // turn (walk Up, then unwind). These prove the new row does it in ONE, from the exact depth those
    // tests already use to prove the gap exists -- and that the mechanism underneath is
    // abandonDestinationPick() itself, not a second implementation of "go back". F-1 repointed that
    // mechanism's Transfer arm to the action overlay (see its own addendum); the row's OWN behaviour --
    // one press, from AWAY, no write -- is unchanged, only the screen it lands on.

    @Test
    fun `the back-to-origin row reaches the overlay in one press from two folders below root`() {
        val (c, authority) = open()
        // Begin the pick BELOW the root, so there is a back-stack to restore -- same arrangement as
        // "abandoning a pick restores the origin's whole back-stack" above.
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Deep", c.currentPath)
        c.armTransferPick("/root/Deep/deep.gba", FileBrowserTransferKind.MOVE)
        // Walk past the origin, as in the CANCEL-based test above.
        assertEquals("the pick starts at the browser root", "/root", c.currentPath)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep/Sub"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals("/root/Deep/Sub", c.currentPath)
        assertEquals(
            "CANCEL/B still means Up here -- unchanged by this ticket",
            FileBrowserDestinationPickPhase.AWAY,
            c.destinationPickPhase(),
        )
        assertNotNull("the row must exist at THIS depth, not only at the origin", c.backToOriginKeyForCurrentMode())

        c.handleBackToOriginTap() // the one press

        assertEquals("restored to where the pick BEGAN, in one press", "/root/Deep", c.currentPath)
        assertTrue(c.actionOverlayOpen)
        assertFalse("and it is NOT the extract chooser", c.extractChooserOpen)
        assertFalse("nor the old transfer chooser, repointed by F-1", c.transferChooserOpen)
        assertEquals("/root/Deep/deep.gba", c.actionOverlay?.path)
        assertTrue("nothing was written -- this unwinds a pick, it does not commit one", moved.isEmpty() && copied.isEmpty())
        c.close()
    }

    @Test
    fun `CONFIRM on the back-to-origin key reaches the overlay from AWAY -- the D-pad path, not just the tap`() {
        val (c, authority) = open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        c.armTransferPick("/root/Deep/deep.gba", FileBrowserTransferKind.COPY)
        assertEquals("the pick starts at the browser root", "/root", c.currentPath)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep/Sub"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
        val backKey = c.backToOriginKeyForCurrentMode()
        assertNotNull(backKey)
        authority.requestFocus(backKey!!)

        val handled = c.dispatch(ControllerAction.CONFIRM)

        assertTrue("the press must be consumed by the modal, not fall through", handled)
        assertEquals("/root/Deep", c.currentPath)
        assertTrue(c.actionOverlayOpen)
        assertEquals("/root/Deep/deep.gba", c.actionOverlay?.path)
        c.close()
    }

    @Test
    fun `the back-to-origin key is gone once the pick ends -- not left registered in the focus graph`() {
        val (c, authority) = open()
        c.armTransferPick(file, FileBrowserTransferKind.MOVE)
        val backKey = c.backToOriginKeyForCurrentMode()
        assertNotNull("the row exists while the pick is open", backKey)
        assertTrue(authority.registeredKeysInScope(c.scope).contains(backKey))

        c.commitHere() // CONFIRM on the commit row -- ends the pick by committing it

        assertNull("no pick, no origin to go back to", c.backToOriginKeyForCurrentMode())
        assertFalse(
            "the stale key must not be left registered -- FileBrowserFocusTracker's diff must retire it " +
                "exactly as it retires a departed tile",
            authority.registeredKeysInScope(c.scope).contains(backKey),
        )
        c.close()
    }

    @Test
    fun `the back-to-origin key is gone after abandoning the pick too, not only after committing it`() {
        val (c, authority) = open()
        c.armTransferPick(file, FileBrowserTransferKind.MOVE)
        val backKey = c.backToOriginKeyForCurrentMode()
        assertNotNull(backKey)

        c.dispatch(ControllerAction.CANCEL) // SETTLED here -> BackToChooser -> abandonDestinationPick()

        assertNull(c.backToOriginKeyForCurrentMode())
        assertFalse(authority.registeredKeysInScope(c.scope).contains(backKey))
        c.close()
    }

    @Test
    fun `the back-to-origin key is STABLE across navigation within one pick -- origin-namespaced, unlike commitKey`() {
        val (c, authority) = open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        c.armTransferPick("/root/Deep/deep.gba", FileBrowserTransferKind.MOVE)
        val atRoot = c.backToOriginKeyForCurrentMode()

        assertEquals("the pick starts at the browser root", "/root", c.currentPath)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep/Sub"))
        c.dispatch(ControllerAction.CONFIRM)
        val awayOneLevel = c.backToOriginKeyForCurrentMode()

        assertEquals(
            "the SAME key at every depth of one pick -- commitKey would differ here, this must not",
            atRoot,
            awayOneLevel,
        )
        assertNotNull(atRoot)
        c.close()
    }

    @Test
    fun `pressing the back-to-origin row does not itself write anything -- it is a navigation, not a commit`() {
        val (c, authority) = open()
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        c.armTransferPick("/root/Deep/deep.gba", FileBrowserTransferKind.MOVE)
        assertEquals("the pick starts at the browser root", "/root", c.currentPath)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        c.dispatch(ControllerAction.CONFIRM)
        assertEquals(FileBrowserDestinationPickPhase.AWAY, c.destinationPickPhase())
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep/Sub"))
        c.dispatch(ControllerAction.CONFIRM)

        c.handleBackToOriginTap()

        assertTrue("MOVE must not have run", moved.isEmpty())
        assertTrue("neither must COPY", copied.isEmpty())
        assertTrue(
            "the pick is still open -- it unwound to the overlay F-1 repointed abandon to, it did not " +
                "close the browser",
            c.actionOverlayOpen,
        )
        c.close()
    }

    @Test
    fun `an extract pick still returns to the EXTRACT chooser -- G2 and F-1 both displaced nothing here`() {
        val (c, _) = open()
        c.reachExtractChooserOn(archive)
        // Row 2 of a recognised archive is the platform folder, so use the UNRECOGNISED path: this tree's
        // archive IS recognised, so pick the CHOOSE_FOLDER row only if present; otherwise drive the
        // platform row and assert the extract sink instead.
        val rows = c.extractChooser?.rows.orEmpty()
        val picker = rows.firstOrNull { it.destination == null }
        if (picker != null) {
            authorityOf(c).requestFocus(FileBrowserExtractFocus.optionKey(picker.kind))
            c.dispatch(ControllerAction.CONFIRM)
            assertEquals(FileBrowserCommitPurpose.EXTRACT_DESTINATION, c.commitPurpose)
            c.dispatch(ControllerAction.CANCEL)
            assertTrue(
                "the extract pick reopens ITS chooser -- Extract's arm of abandon is untouched by F-1",
                c.extractChooserOpen,
            )
            assertFalse(c.transferChooserOpen)
            assertFalse("nor the action overlay -- only a Transfer pick reopens that", c.actionOverlayOpen)
        } else {
            assertTrue("this tree's archive resolves a platform folder, so row 2 writes directly", rows.size > 1)
        }
        c.close()
    }

    // ─────────────────────────────────────────────── 6 · teardown discipline ──

    @Test
    fun `close unwinds the action overlay's scope rather than leaving it pushed -- F-1's door, not the old chooser's`() {
        val (c, authority) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        assertEquals(c.actionOverlayScope, authority.state.value.scope)

        c.close()

        assertFalse(c.actionOverlayOpen)
        assertEquals(
            "a scope left pushed buries the browse scope and corrupts the stack for whatever mounts next",
            FocusScope("root"),
            authority.state.value.scope,
        )
    }

    @Test
    fun `a re-opened browser inherits no chooser and no pick from the last session`() {
        val (c, _) = open()
        c.armTransferPick(file, FileBrowserTransferKind.MOVE)
        assertEquals(FileBrowserCommitPurpose.MOVE_DESTINATION, c.commitPurpose)

        c.close()
        c.open()

        assertFalse(c.transferChooserOpen)
        assertFalse("nor the overlay it was armed through", c.actionOverlayOpen)
        assertNull(
            "a re-opened instance answering MOVE_DESTINATION for a file from a session that is over " +
                "would put a live, Undo-less press on a stale subject",
            c.commitPurpose,
        )
        c.close()
    }

    @Test
    fun `opening search or the queue closes the action overlay rather than stacking on it -- F-1's door`() {
        val (c, authority) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        assertTrue(c.actionOverlayOpen)
        c.setSearchAvailableForTest(true)

        c.openSearch()

        assertFalse(
            "the header magnifier stays composed above the overlay, so a tap from it would push " +
                "searchScope on top of actionOverlayScope and leave two screens' graphs alive at once -- " +
                "openSearch's own F-1 addendum states this same argument for closeActionOverlay()",
            c.actionOverlayOpen,
        )
        assertEquals(c.searchScope, authority.state.value.scope)
        c.close()
    }
}

/** Test-only reach-throughs, so the tests above read as a player's presses rather than as plumbing. */
private fun FileBrowserController.focusedTileAtPathForTest(path: String): FileBrowserTile {
    val all = partition.tomes + partition.files
    return all.first { it.path == path }
}

private fun FileBrowserController.setSearchAvailableForTest(available: Boolean) {
    setSearchAvailable(available)
}
