package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
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
 * **F-1 — is the SELECT action overlay actually REACHABLE, and does its own modal discipline hold?**
 *
 * The direct descendant of `FileBrowserExtractWiringTest`/`FileBrowserTransferWiringTest`/
 * `FileBrowserQueueWiringTest`, guarding the same class of failure they were all written for: a rule
 * pinned in one file and its wiring pinned by nothing is how a green suite sits over a dead feature. This
 * file is the overlay's OWN wiring test — `FileBrowserActionOverlayChoiceTest`-shaped coverage (row
 * partition, gate refusals, R-16 label identity) plus the controller-driven reachability and modal-mechanics
 * coverage promised by `FileBrowserTransferWiringTest`'s retired § 3 (its own deletion comment names this
 * file as where that coverage now lives) and `FileBrowserExtractWiringTest`'s sibling sections.
 *
 * Move/Copy/Extract/Delete's own SINK-level routing (does the right row reach the right
 * `onMoveTo`/`onCopyTo`/`onExtractTo`/`onDelete`) is deliberately NOT re-proven exhaustively here — that is
 * `FileBrowserTransferWiringTest`, `FileBrowserExtractWiringTest` and `FileBrowserControllerTest`'s job, and
 * duplicating it would be a second place to forget a gate rather than a second proof of one. This file's
 * one routing test covers DELETE, the row kind those other files do not touch, and the close-before-fire
 * ordering all four share.
 *
 * **What a passing run here does and does not prove.** It drives a real [DefaultFocusAuthority], a real
 * [FileBrowserController] and real [ControllerAction] dispatches — reachability, scope discipline, focus
 * landings and registration lifecycle are genuinely exercised. It composes **nothing**: this module
 * configures no Compose and no Robolectric harness, so nothing here is evidence about pixels, about the
 * overlay's appearance, or about `FileBrowserSurface` having wired it at all. Hardware is still the gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserActionOverlayWiringTest {
    private val english = filesEnglish()

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true),
            FileItem(name = "Mario.gba", path = "/root/Mario.gba", extension = "gba"),
            FileItem(name = "Golden Sun.zip", path = "/root/Golden Sun.zip", extension = "zip"),
        ),
    )

    private val file = "/root/Mario.gba"
    private val archive = "/root/Golden Sun.zip"
    private val directory = "/root/Roms"

    private class Sinks {
        val moved = mutableListOf<Pair<String, String>>()
        val copied = mutableListOf<Pair<String, String>>()
        val extractedHere = mutableListOf<String>()
        val deleted = mutableListOf<String>()
    }

    private data class Fixture(val controller: FileBrowserController, val authority: DefaultFocusAuthority, val sinks: Sinks)

    private fun open(
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
        deleteWired: Boolean = true,
        transferWired: Boolean = true,
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
            onExtract = { path -> sinks.extractedHere += path },
            onDelete = if (deleteWired) { path -> sinks.deleted += path } else null,
            onMoveTo = if (transferWired) { p, d -> sinks.moved += p to d } else null,
            onCopyTo = if (transferWired) { p, d -> sinks.copied += p to d } else null,
        )
        c.open()
        return Fixture(c, authority, sinks)
    }

    // ──────────────────────────────────────────── 1 · the row partition (pure) ──

    @Test
    fun `the row partition -- MOVE COPY for a FILE, EXTRACT for an ARCHIVE, DELETE on identity, RENAME nowhere`() {
        assertEquals(
            "a FILE with both transfer sinks gets Move then Copy, fixed order",
            listOf(FileBrowserActionOverlayRowKind.MOVE, FileBrowserActionOverlayRowKind.COPY),
            fileBrowserActionOverlayRows(FileBrowserTileKind.FILE, deleteAvailable = false, transferChoiceAvailable = true),
        )
        assertEquals(
            "a FILE with transfer unwired gets NOTHING from that clause -- Move/Copy do not degrade to a " +
                "lesser row, they are simply absent",
            emptyList<FileBrowserActionOverlayRowKind>(),
            fileBrowserActionOverlayRows(FileBrowserTileKind.FILE, deleteAvailable = false, transferChoiceAvailable = false),
        )
        assertEquals(
            "R-19: an ARCHIVE moves and copies like any other file, AND keeps Extract -- a .zip is a file " +
                "to the player, and the old files-only limit was a dispatch condition, never an engine one",
            listOf(
                FileBrowserActionOverlayRowKind.MOVE,
                FileBrowserActionOverlayRowKind.COPY,
                FileBrowserActionOverlayRowKind.EXTRACT,
            ),
            fileBrowserActionOverlayRows(FileBrowserTileKind.ARCHIVE, deleteAvailable = false, transferChoiceAvailable = true),
        )
        assertEquals(
            "an ARCHIVE with transfer unwired still gets EXTRACT -- that row gates on kind alone, so " +
                "unwiring transfers takes the transfer rows and nothing else",
            listOf(FileBrowserActionOverlayRowKind.EXTRACT),
            fileBrowserActionOverlayRows(FileBrowserTileKind.ARCHIVE, deleteAvailable = false, transferChoiceAvailable = false),
        )
        assertEquals(
            "R-19 widened FILE to FILE-or-ARCHIVE and NOTHING else -- one set, read by both the dispatch " +
                "table and this row model, so the two cannot drift apart the way the schema ceiling did",
            setOf(FileBrowserTileKind.FILE, FileBrowserTileKind.ARCHIVE),
            FileBrowserTransferableTileKinds,
        )
        assertEquals(
            "a DIRECTORY gets NOTHING from kind alone -- Move/Copy exclude it on a measurement (parent-" +
                "into-descendant runaway, this enum's own KDoc), and EXTRACT does not apply",
            emptyList<FileBrowserActionOverlayRowKind>(),
            fileBrowserActionOverlayRows(FileBrowserTileKind.DIRECTORY, deleteAvailable = false, transferChoiceAvailable = true),
        )
        assertEquals(
            "DELETE acts on identity, not content -- present for every kind once deleteAvailable, and " +
                "last in fixed order behind whichever of Move/Copy/Extract this kind carries",
            listOf(FileBrowserActionOverlayRowKind.MOVE, FileBrowserActionOverlayRowKind.COPY, FileBrowserActionOverlayRowKind.DELETE),
            fileBrowserActionOverlayRows(FileBrowserTileKind.FILE, deleteAvailable = true, transferChoiceAvailable = true),
        )
        assertEquals(
            listOf(FileBrowserActionOverlayRowKind.DELETE),
            fileBrowserActionOverlayRows(FileBrowserTileKind.DIRECTORY, deleteAvailable = true, transferChoiceAvailable = true),
        )
        assertEquals(
            "a DIRECTORY with nothing wired at all has NO legal row -- this is F-1's Rename gap: no " +
                "engine, no command, no sink to hang a row on, so the overlay must not open one with " +
                "nothing in it",
            emptyList<FileBrowserActionOverlayRowKind>(),
            fileBrowserActionOverlayRows(FileBrowserTileKind.DIRECTORY, deleteAvailable = false, transferChoiceAvailable = false),
        )
        assertEquals(
            "RENAME supports Rename and Cancel; these five cases " +
                "are its complete exhaustive set, in display order",
            listOf("MOVE", "COPY", "RENAME", "EXTRACT", "DELETE"),
            FileBrowserActionOverlayRowKind.entries.map { it.name },
        )
    }

    // ─────────────────────────────────────── 2 · fileBrowserActionsAvailable's refusals (pure) ──

    @Test
    fun `actionsAvailable's three refusals -- null focus, PICK mode, and mid-pick, each alone is enough`() {
        val tile = FileBrowserTile(
            item = FileItem(name = "Mario.gba", path = file, extension = "gba"),
            kind = FileBrowserTileKind.FILE,
            machinery = false,
            platformIdValue = null,
        )
        assertFalse(
            "nothing focused -- SELECT with no ring has nothing to open a menu about",
            fileBrowserActionsAvailable(
                focused = null, manageMode = true, deleteAvailable = true, transferChoiceAvailable = true,
                destinationPick = null,
            ),
        )
        assertFalse(
            "PICK is incapable of destructive operations, checked first and fails " +
                "closed even with every sink wired",
            fileBrowserActionsAvailable(
                focused = tile, manageMode = false, deleteAvailable = true, transferChoiceAvailable = true,
                destinationPick = null,
            ),
        )
        assertFalse(
            "mid-pick -- SELECT must not overwrite an ALREADY-open pick's origin snapshot via its own " +
                "Move/Copy rows, the destinationPick != null hazard SECONDARY's own guard already names",
            fileBrowserActionsAvailable(
                focused = tile, manageMode = true, deleteAvailable = true, transferChoiceAvailable = true,
                destinationPick = FileBrowserDestinationPickPhase.SETTLED,
            ),
        )
        assertTrue(
            "precondition: with none of the three refusals in play and a real row available, it answers true",
            fileBrowserActionsAvailable(
                focused = tile, manageMode = true, deleteAvailable = true, transferChoiceAvailable = true,
                destinationPick = null,
            ),
        )
    }

    /**
     * **The defect R-19 CREATED, pinned.**
     *
     * While transfers were files-only, `abandonDestinationPick` could reopen the overlay with a hardcoded
     * `FILE` and be right by construction — a Transfer session could not hold anything else. R-19 lets an
     * archive be moved, and that constant became a lie the instant it could be wrong: abandoning an
     * archive's Move pick would have reopened an overlay **missing its own Extract row**.
     *
     * The fix threads `subjectTileKind` onto the session. This composes the two functions the abandon arm
     * composes, in the same order, so a regression to a literal fails here.
     *
     * **Honest about its tier:** this pins the data path, not a rendered reopen. `feature-files` has no
     * Compose harness, so no test in this module proves a frame.
     */
    @Test
    fun `an abandoned ARCHIVE pick reopens an ARCHIVE overlay, not a FILE one`() {
        val f = open()
        f.controller.openActionOverlay(archive, FileBrowserTileKind.ARCHIVE)
        f.controller.handleActionOverlayTap(
            FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE),
        )
        assertFalse("precondition: taking Move closes the overlay and opens a pick", f.controller.actionOverlayOpen)

        f.controller.dispatch(ControllerAction.CANCEL)

        assertTrue("abandoning the pick must reopen the overlay", f.controller.actionOverlayOpen)
        assertEquals(
            "it must reopen on the ARCHIVE it was opened from. Hardcoding FILE here was correct while " +
                "transfers were files-only and became wrong the instant R-19 let an archive be moved.",
            FileBrowserTileKind.ARCHIVE,
            f.controller.actionOverlay?.kind,
        )
        assertTrue(
            "and the reopened overlay must still carry Extract -- losing that row is exactly what a " +
                "hardcoded FILE would have done, silently",
            FileBrowserActionOverlayRowKind.EXTRACT in f.controller.actionOverlay?.rows.orEmpty(),
        )
    }

    @Test
    fun `actionsAvailable also refuses a DIRECTORY when NEITHER sink is wired -- no row at all, no Rename`() {
        val directoryTile = FileBrowserTile(
            item = FileItem(name = "Roms", path = directory, isDirectory = true),
            kind = FileBrowserTileKind.DIRECTORY,
            machinery = false,
            platformIdValue = null,
        )
        assertFalse(
            "with neither delete nor transfer wired, a DIRECTORY's row list is empty (no Rename engine " +
                "exists to hang a row on), so SELECT must not open an overlay with nothing to press",
            fileBrowserActionsAvailable(
                focused = directoryTile, manageMode = true, deleteAvailable = false, transferChoiceAvailable = true,
                destinationPick = null,
            ),
        )
        assertTrue(
            "precondition/contrast: the SAME directory answers true once delete alone is wired -- a " +
                "DIRECTORY legitimately has ONE legal row (Delete) until Rename ships, this file's own " +
                "row-partition test already pins fileBrowserActionOverlayRows(DIRECTORY, true, ...) as " +
                "[DELETE] -- so actionsAvailable's refusal here is about the ROW LIST being empty, not " +
                "about the DIRECTORY kind itself",
            fileBrowserActionsAvailable(
                focused = directoryTile, manageMode = true, deleteAvailable = true, transferChoiceAvailable = true,
                destinationPick = null,
            ),
        )
    }

    // ──────────────────────────────────────────────────── 3 · R-16, the row words ──

    @Test
    fun `R-16 -- Move and Copy read fileBrowserTransferOptions' own labels, not a duplicate`() {
        val moveOption = fileBrowserTransferOptions().first { it.kind == FileBrowserTransferKind.MOVE }
        val copyOption = fileBrowserTransferOptions().first { it.kind == FileBrowserTransferKind.COPY }
        assertEquals(
            "the overlay's MOVE row must be the SAME string FileBrowserTransferChoice.kt already carries " +
                "the shared implementation, read rather than copied",
            english.getString(moveOption.labelRes),
            fileBrowserActionOverlayLabel(english, FileBrowserActionOverlayRowKind.MOVE),
        )
        assertEquals(
            english.getString(copyOption.labelRes),
            fileBrowserActionOverlayLabel(english, FileBrowserActionOverlayRowKind.COPY),
        )
        assertFalse(
            "and PLACE must not appear as either row's own word -- R-16's whole point: PLACE stays the " +
                "legend/eyebrow word, a row is never vague",
            fileBrowserActionOverlayLabel(english, FileBrowserActionOverlayRowKind.MOVE).contains("Place", ignoreCase = true) ||
                fileBrowserActionOverlayLabel(english, FileBrowserActionOverlayRowKind.COPY).contains("Place", ignoreCase = true),
        )
    }

    // ──────────────────────────────────────────────────────── 4 · reachability ──

    @Test
    fun `SELECT opens the overlay in MANAGE when a row exists, and lands on row 0`() {
        val (c, authority, _) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))

        c.dispatch(ControllerAction.SECONDARY)

        assertTrue(c.actionOverlayOpen)
        assertEquals(file, c.actionOverlay?.path)
        assertEquals(c.actionOverlayScope, authority.state.value.scope)
        assertEquals(
            "row 0 is seeded explicitly, never a remembered kind from a different tile -- " +
                "openActionOverlay's own KDoc",
            FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE),
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `SELECT is quiet in MANAGE on a DIRECTORY with nothing wired -- no row, no overlay`() {
        val (c, authority, _) = open(deleteWired = false, transferWired = false)
        authority.requestFocus(FileBrowserFocus.tileKey(directory))

        c.dispatch(ControllerAction.SECONDARY)

        assertFalse(c.actionOverlayOpen)
        assertEquals(
            "SELECT with nothing to open must not even push a scope -- a pushed-then-abandoned scope " +
                "buries the browse scope for whatever presses next",
            c.scope,
            authority.state.value.scope,
        )
        c.close()
    }

    @Test
    fun `PICK never opens the overlay, even with move, copy and delete all wired`() {
        val (c, authority, _) = open(mode = FileBrowserMode.PICK)
        authority.requestFocus(FileBrowserFocus.tileKey(file))

        c.dispatch(ControllerAction.SECONDARY)

        assertFalse(
            "PICK is incapable, not merely restricted, regardless of what " +
                "this fixture's controller has wired",
            c.actionOverlayOpen,
        )
        assertEquals(c.scope, authority.state.value.scope)
        c.close()
    }

    // ────────────────────────────────────────────────────── 5 · the modal itself ──

    @Test
    fun `CANCEL and SYSTEM_BACK both close the overlay before either could mean Up`() {
        listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { closer ->
            val (c, authority, _) = open()
            authority.requestFocus(FileBrowserFocus.tileKey(file))
            c.dispatch(ControllerAction.SECONDARY)
            assertTrue(c.actionOverlayOpen)

            c.dispatch(closer)

            assertFalse("$closer must close the overlay", c.actionOverlayOpen)
            assertEquals("and must not also have walked the player anywhere", "/root", c.currentPath)
            assertEquals(c.scope, authority.state.value.scope)
            c.close()
        }
    }

    @Test
    fun `a second X is swallowed, not a toggle-close -- B remains the one way out`() {
        val (c, authority, _) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        assertTrue(c.actionOverlayOpen)

        val handled = c.dispatch(ControllerAction.SECONDARY)

        assertTrue("the press must be consumed by the modal, not fall through", handled)
        assertTrue(
            "design spec S5, unchanged by D-3's swap: the button that OPENS the overlay does not also " +
                "close it. The rule was written about SELECT because SELECT opened it; it is about the " +
                "opening button, not about that particular key.",
            c.actionOverlayOpen,
        )
        c.close()
    }

    @Test
    fun `NAV moves between rows, and a horizontal is absorbed rather than escaping`() {
        val (c, authority, _) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        val move = FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE)
        val copy = FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.COPY)
        assertEquals(move, authority.state.value.focused)

        c.dispatch(ControllerAction.NAV_DOWN)
        assertEquals("DOWN reaches row 1", copy, authority.state.value.focused)
        c.dispatch(ControllerAction.NAV_UP)
        assertEquals(move, authority.state.value.focused)

        assertTrue("a horizontal is still absorbed by the modal", c.dispatch(ControllerAction.NAV_RIGHT))
        assertEquals(
            "full-width rows give the scoring no candidate, exactly like every other chooser in this module",
            move,
            authority.state.value.focused,
        )
        assertEquals(c.actionOverlayScope, authority.state.value.scope)
        c.close()
    }

    @Test
    fun `B pops back to the tile X was pressed on`() {
        val (c, authority, _) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        c.dispatch(ControllerAction.NAV_DOWN) // move the ring off row 0 first

        c.dispatch(ControllerAction.CANCEL)

        assertEquals(
            "popScope restores the tile the player pressed SELECT on -- no bookkeeping needed here, the " +
                "same idiom every chooser in this module relies on",
            FileBrowserFocus.tileKey(file),
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `closing tears down the tracker's registrations -- none of the overlay's keys outlive it`() {
        val (c, authority, _) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        val moveKey = FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE)
        assertTrue(authority.registeredKeysInScope(c.actionOverlayScope).contains(moveKey))

        c.dispatch(ControllerAction.CANCEL)

        assertTrue(
            "a stale registration surviving the close is a key a LATER-opened overlay's own landing " +
                "could collide with, or a ghost the D-pad could still land the ring on",
            authority.registeredKeysInScope(c.actionOverlayScope).isEmpty(),
        )
        c.close()
    }

    // ──────────────────────────────────────────────────────── 6 · the touch seam ──

    @Test
    fun `handleActionOverlayTap is the touch seam, and a stale tap after the overlay closes arms nothing`() {
        val (c, authority, _) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        val copyKey = FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.COPY)
        val moveKey = FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE)

        // Close the overlay out from under a tap that is already in flight -- CANCEL standing in for a
        // race between a stale pointer event and a close reaching the controller first, [handleDiscSetTap]
        // /[handleTransferTap]'s own guarded hazard.
        c.dispatch(ControllerAction.CANCEL)
        c.handleActionOverlayTap(moveKey)

        assertNull("no pick may be armed by a tap on a screen that is gone", c.commitPurpose)
        assertEquals("and the player must not have been navigated anywhere", "/root", c.currentPath)

        // The live path still works, for contrast -- a tap really does reach CONFIRM's own path.
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        c.handleActionOverlayTap(copyKey)
        assertFalse("the overlay closes behind a LIVE row press", c.actionOverlayOpen)
        assertEquals(FileBrowserCommitPurpose.COPY_DESTINATION, c.commitPurpose)
        c.close()
    }

    // ─────────────────────────────────────────────────── 7 · openActionOverlay is a door ──

    @Test
    fun `openActionOverlay refuses an empty row list, not only via dispatch`() {
        val (c, authority, _) = open(deleteWired = false, transferWired = false)
        val scopeBefore = authority.state.value.scope

        c.openActionOverlay(directory, FileBrowserTileKind.DIRECTORY)

        assertFalse(
            "a DIRECTORY with nothing wired has no row at all -- opening would push a scope over the " +
                "listing, take the ring, and advertise nothing. UI-05 exactly, openTransferChooser's own " +
                "precedent for this same defensive gate",
            c.actionOverlayOpen,
        )
        assertEquals("and no scope may have been pushed", scopeBefore, authority.state.value.scope)
        c.close()
    }

    @Test
    fun `openActionOverlay is idempotent -- a second call while one is already open changes nothing`() {
        val (c, authority, _) = open()
        c.openActionOverlay(file, FileBrowserTileKind.FILE)
        assertEquals(file, c.actionOverlay?.path)
        c.dispatch(ControllerAction.NAV_DOWN) // move off row 0, so a re-open landing would be visible

        c.openActionOverlay(archive, FileBrowserTileKind.ARCHIVE) // a DIFFERENT tile, while the first is open

        assertEquals(
            "the first overlay's subject must survive a second open call untouched -- openActionOverlay's " +
                "own if (actionOverlayOpen) return guard",
            file,
            c.actionOverlay?.path,
        )
        assertEquals(
            "and the ring must not have been reset to row 0 either",
            FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.COPY),
            authority.state.value.focused,
        )
        c.close()
    }

    // ───────────────────────────────────────────── 8 · DELETE's own routing, and the close ordering ──

    @Test
    fun `DELETE is reported via onDelete, and the overlay closes BEFORE the sink fires`() {
        val (c, authority, sinks) = open()
        authority.requestFocus(FileBrowserFocus.tileKey(file))
        c.dispatch(ControllerAction.SECONDARY)
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.DELETE))

        c.dispatch(ControllerAction.CONFIRM)

        assertFalse(
            "closed BEFORE the sink fires -- a screen still up over a queue card that just appeared is " +
                "the stale-affordance shape activateFocusedActionOverlayOption's own KDoc refuses",
            c.actionOverlayOpen,
        )
        assertEquals(listOf(file), sinks.deleted)
        assertTrue(
            "DELETE must reach NOTHING else -- staging, the Undo handle and the queue entry all belong " +
                "to FileBrowserStore, never performed here",
            sinks.moved.isEmpty() && sinks.copied.isEmpty() && sinks.extractedHere.isEmpty(),
        )
        c.close()
    }

    @Test
    fun `a key whose kind is not in the carried rows degrades to nothing, never to another row's action`() {
        // A DIRECTORY-with-delete-only fixture carries just DELETE; a stale MOVE key (as if leaked from a
        // FILE's overlay) must not be treated as though it were the carried DELETE row.
        val (c, authority, sinks) = open(transferWired = false)
        authority.requestFocus(FileBrowserFocus.tileKey(directory))
        c.dispatch(ControllerAction.SECONDARY)
        assertTrue(c.actionOverlayOpen)
        assertEquals(listOf(FileBrowserActionOverlayRowKind.DELETE), c.actionOverlay?.rows)
        val stale = FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE)
        authority.registerVirtual(stale, c.actionOverlayScope, fileBrowserActionOverlayOptionBounds(1))
        authority.requestFocus(stale)
        assertEquals("the stale key really does hold the ring", stale, authority.state.value.focused)

        c.dispatch(ControllerAction.CONFIRM)

        assertTrue("nothing moved", sinks.moved.isEmpty())
        assertTrue("nothing deleted either -- a no-op, not a silent substitution", sinks.deleted.isEmpty())
        assertTrue("the overlay is still up -- a no-op, not a silent dismissal", c.actionOverlayOpen)
        c.close()
    }
}
