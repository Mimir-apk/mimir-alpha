package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [fileBrowserCommand] -- the browser's entire input contract, as one pure function. Every case is
 * directly assertable against a hand-built [FileBrowserTile]; none of it needs a
 * [com.gruning.mimir.rewrite.core.input.FocusAuthority] or a [FileBrowserController].
 *
 * **This file is also the standing controller-table differential.** The 2026-08-14 touch-grammar change
 * rewrote how a TAP reaches this table and changed nothing inside it; `5eac3b26`'s lesson is that a
 * touch-routing error took down BOTH input paths, so a differential exercising only touch would not have
 * caught it. Every assertion below therefore has to keep passing untouched across that change, and the
 * >100-case sweep is what makes "untouched" a checked claim rather than a diff read.
 */
class FileBrowserDispatchTest {

    private fun tile(name: String, kind: FileBrowserTileKind) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = kind == FileBrowserTileKind.DIRECTORY),
        kind = kind,
        machinery = false,
        platformIdValue = null,
    )

    private val directory = tile("Roms", FileBrowserTileKind.DIRECTORY)
    private val archive = tile("bundle.zip", FileBrowserTileKind.ARCHIVE)
    private val plainFile = tile("readme.txt", FileBrowserTileKind.FILE)

    // ── Directional: adoption spec §5, "directions navigate" -- ALL FOUR, uniformly ──

    @Test
    fun `NAV_UP, NAV_DOWN, NAV_LEFT and NAV_RIGHT all become plain Move, regardless of the focused tile`() {
        listOf(ControllerAction.NAV_UP, ControllerAction.NAV_DOWN, ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT)
            .forEach { direction ->
                assertEquals(FileBrowserCommand.Move(direction), fileBrowserCommand(direction, archive, atRoot = false))
                assertEquals(FileBrowserCommand.Move(direction), fileBrowserCommand(direction, null, atRoot = true))
            }
    }

    @Test
    fun `NAV_LEFT at the root is now a plain Move, NOT a consumed no-op -- reversed from slice 5-6`() {
        assertEquals(FileBrowserCommand.Move(ControllerAction.NAV_LEFT), fileBrowserCommand(ControllerAction.NAV_LEFT, directory, atRoot = true))
    }

    @Test
    fun `NAV_LEFT below the root is a plain Move, NOT go-up -- reversed from slice 5-6`() {
        assertEquals(FileBrowserCommand.Move(ControllerAction.NAV_LEFT), fileBrowserCommand(ControllerAction.NAV_LEFT, directory, atRoot = false))
    }

    @Test
    fun `NAV_RIGHT on a focused directory is now a plain Move, NOT Enter -- reversed from slice 5-6`() {
        assertEquals(FileBrowserCommand.Move(ControllerAction.NAV_RIGHT), fileBrowserCommand(ControllerAction.NAV_RIGHT, directory, atRoot = true))
    }

    // ── CONFIRM: only opens a directory; no pinned action row exists any more ──

    @Test
    fun `CONFIRM on a directory enters it`() {
        assertEquals(FileBrowserCommand.Enter("/root/Roms"), fileBrowserCommand(ControllerAction.CONFIRM, directory, atRoot = false))
    }

    @Test
    fun `CONFIRM on an archive or a plain file is consumed -- opening is not defined for either`() {
        assertEquals(FileBrowserCommand.Consumed, fileBrowserCommand(ControllerAction.CONFIRM, archive, atRoot = true))
        assertEquals(FileBrowserCommand.Consumed, fileBrowserCommand(ControllerAction.CONFIRM, plainFile, atRoot = true))
    }

    @Test
    fun `CONFIRM with nothing focused is consumed`() {
        assertEquals(FileBrowserCommand.Consumed, fileBrowserCommand(ControllerAction.CONFIRM, null, atRoot = true))
    }

    // ── CONFIRM with the pinned commit affordance focused: PICK mode, restored 2026-08-14 ──

    @Test
    fun `CONFIRM with the commit affordance focused is Commit, regardless of atRoot`() {
        assertEquals(
            FileBrowserCommand.CommitSource,
            fileBrowserCommand(ControllerAction.CONFIRM, null, atRoot = true, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
        assertEquals(
            FileBrowserCommand.CommitSource,
            fileBrowserCommand(ControllerAction.CONFIRM, null, atRoot = false, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
    }

    @Test
    fun `commitFocused takes priority over a focused tile -- the two can never both be true in practice, but the branch order is pinned anyway`() {
        assertEquals(
            FileBrowserCommand.CommitSource,
            fileBrowserCommand(ControllerAction.CONFIRM, directory, atRoot = false, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
    }

    // ── CANCEL / SYSTEM_BACK -- unchanged from slice 5-6 ──

    @Test
    fun `CANCEL at the root closes the browser`() {
        assertEquals(FileBrowserCommand.Close, fileBrowserCommand(ControllerAction.CANCEL, directory, atRoot = true))
    }

    @Test
    fun `CANCEL below the root goes up instead of closing`() {
        assertEquals(FileBrowserCommand.Up, fileBrowserCommand(ControllerAction.CANCEL, directory, atRoot = false))
    }

    @Test
    fun `SYSTEM_BACK behaves exactly like CANCEL at every depth`() {
        assertEquals(
            fileBrowserCommand(ControllerAction.CANCEL, directory, atRoot = true),
            fileBrowserCommand(ControllerAction.SYSTEM_BACK, directory, atRoot = true),
        )
        assertEquals(
            fileBrowserCommand(ControllerAction.CANCEL, directory, atRoot = false),
            fileBrowserCommand(ControllerAction.SYSTEM_BACK, directory, atRoot = false),
        )
    }

    // ── SECONDARY: extract, unchanged in spirit, now zip+7z (see FileBrowserPartitionTest) ──

    @Test
    fun `SECONDARY on an extractable archive reports Extract with its path`() {
        assertEquals(FileBrowserCommand.Extract("/root/bundle.zip"), fileBrowserCommand(ControllerAction.SECONDARY, archive, atRoot = true))
    }

    @Test
    fun `SECONDARY is inert on a directory -- it must never mean bulk-extract everything inside`() {
        assertEquals(FileBrowserCommand.Consumed, fileBrowserCommand(ControllerAction.SECONDARY, directory, atRoot = true))
    }

    @Test
    fun `SECONDARY is inert on a plain file`() {
        assertEquals(FileBrowserCommand.Consumed, fileBrowserCommand(ControllerAction.SECONDARY, plainFile, atRoot = true))
    }

    @Test
    fun `SECONDARY with nothing focused is consumed`() {
        assertEquals(FileBrowserCommand.Consumed, fileBrowserCommand(ControllerAction.SECONDARY, null, atRoot = true))
    }

    @Test
    fun `SECONDARY is inert on the commit affordance -- it must never mean extract`() {
        assertEquals(
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.SECONDARY, null, atRoot = true, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
    }

    // ── Horizontal moves on the pinned commit affordance: a full-width row has no side neighbour ──

    @Test
    fun `NAV_LEFT and NAV_RIGHT are consumed while the commit affordance holds focus -- it is full-width`() {
        assertEquals(
            "RIGHT on a full-width pinned row must not teleport into the middle of the tome grid",
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.NAV_RIGHT, null, atRoot = true, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
        assertEquals(
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.NAV_LEFT, null, atRoot = true, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
    }

    @Test
    fun `NAV_UP and NAV_DOWN leave the commit affordance through the D-pad`() {
        assertEquals(
            FileBrowserCommand.Move(ControllerAction.NAV_DOWN),
            fileBrowserCommand(ControllerAction.NAV_DOWN, null, atRoot = true, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
        assertEquals(
            FileBrowserCommand.Move(ControllerAction.NAV_UP),
            fileBrowserCommand(ControllerAction.NAV_UP, null, atRoot = true, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
    }

    // ── F-2, 2026-08-18: the pinned row's new sibling, "Back to origin" -- lateral moves are ALWAYS
    // hand-computed here, never delegated to geometric Move; see this ticket's own dispatch-table comment
    // for the recorded hazard (FileBrowserFocusRegistrationTest's differential test proves it empirically).

    @Test
    fun `NAV_RIGHT off the commit affordance hops to the back-to-origin sibling when one is open`() {
        assertEquals(
            FileBrowserCommand.FocusBackToOrigin,
            fileBrowserCommand(
                ControllerAction.NAV_RIGHT,
                null,
                atRoot = true,
                focusedCommitPurpose = FileBrowserCommitPurpose.MOVE_DESTINATION,
                backToOriginAvailable = true,
            ),
        )
    }

    @Test
    fun `NAV_RIGHT off the commit affordance is still Consumed when no sibling is open -- differential, pre-F-2 behaviour`() {
        assertEquals(
            "backToOriginAvailable defaults false -- every caller written before F-2 gets the exact table it always had",
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.NAV_RIGHT, null, atRoot = true, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
    }

    @Test
    fun `NAV_LEFT off the commit affordance stays Consumed even when a sibling is open -- nothing sits to ITS left`() {
        assertEquals(
            FileBrowserCommand.Consumed,
            fileBrowserCommand(
                ControllerAction.NAV_LEFT,
                null,
                atRoot = true,
                focusedCommitPurpose = FileBrowserCommitPurpose.MOVE_DESTINATION,
                backToOriginAvailable = true,
            ),
        )
    }

    @Test
    fun `NAV_LEFT off the back-to-origin sibling hops back to the commit affordance`() {
        assertEquals(
            FileBrowserCommand.FocusCommitRow,
            fileBrowserCommand(ControllerAction.NAV_LEFT, null, atRoot = true, focusedBackToOrigin = true),
        )
    }

    @Test
    fun `NAV_RIGHT off the back-to-origin sibling is Consumed -- nothing sits to ITS right either`() {
        // The differential proof for THIS specific claim lives in FileBrowserFocusRegistrationTest ("raw
        // NAV_RIGHT from the back-to-origin sibling would escape into the tile grid"): raw moveFocus does
        // NOT safely dead-end here, which is exactly why this branch may never fall through to Move.
        assertEquals(
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.NAV_RIGHT, null, atRoot = true, focusedBackToOrigin = true),
        )
    }

    @Test
    fun `NAV_UP and NAV_DOWN stay real moves on the back-to-origin sibling too -- it is a real focus-graph participant`() {
        assertEquals(
            FileBrowserCommand.Move(ControllerAction.NAV_DOWN),
            fileBrowserCommand(ControllerAction.NAV_DOWN, null, atRoot = true, focusedBackToOrigin = true),
        )
        assertEquals(
            FileBrowserCommand.Move(ControllerAction.NAV_UP),
            fileBrowserCommand(ControllerAction.NAV_UP, null, atRoot = true, focusedBackToOrigin = true),
        )
    }

    @Test
    fun `CONFIRM on the back-to-origin sibling is BackToChooser regardless of atRoot`() {
        assertEquals(
            FileBrowserCommand.BackToChooser,
            fileBrowserCommand(ControllerAction.CONFIRM, null, atRoot = true, focusedBackToOrigin = true),
        )
        assertEquals(
            FileBrowserCommand.BackToChooser,
            fileBrowserCommand(ControllerAction.CONFIRM, null, atRoot = false, focusedBackToOrigin = true),
        )
    }

    @Test
    fun `CONFIRM on the back-to-origin sibling reaches BackToChooser at every destinationPick phase -- the whole point of this ticket`() {
        listOf(null, FileBrowserDestinationPickPhase.AWAY, FileBrowserDestinationPickPhase.SETTLED).forEach { phase ->
            assertEquals(
                "phase=$phase must not gate this row -- CANCEL is what phase gates, this row is the way " +
                    "around needing two CANCELs",
                FileBrowserCommand.BackToChooser,
                fileBrowserCommand(
                    ControllerAction.CONFIRM,
                    null,
                    atRoot = false,
                    focusedBackToOrigin = true,
                    destinationPick = phase,
                ),
            )
        }
    }

    @Test
    fun `focusedBackToOrigin takes priority over focusedCommitPurpose -- the two can never both be true in practice, but the branch order is pinned`() {
        assertEquals(
            FileBrowserCommand.BackToChooser,
            fileBrowserCommand(
                ControllerAction.CONFIRM,
                null,
                atRoot = true,
                focusedCommitPurpose = FileBrowserCommitPurpose.MOVE_DESTINATION,
                focusedBackToOrigin = true,
            ),
        )
    }

    @Test
    fun `SECONDARY, PAGE and every swallowed action are unaffected by focusedBackToOrigin -- it only changes NAV and CONFIRM`() {
        assertEquals(
            fileBrowserCommand(ControllerAction.SECONDARY, null, atRoot = true),
            fileBrowserCommand(ControllerAction.SECONDARY, null, atRoot = true, focusedBackToOrigin = true),
        )
        assertEquals(
            FileBrowserCommand.Page(forward = true),
            fileBrowserCommand(ControllerAction.PAGE_RIGHT, null, atRoot = true, focusedBackToOrigin = true),
        )
    }

    // ── Paging: L1/R1, grounded in the adoption spec's 833x468dp viewport (fileBrowserPageStride) ──

    @Test
    fun `PAGE_RIGHT pages forward and PAGE_LEFT pages back -- no longer swallowed`() {
        assertEquals(FileBrowserCommand.Page(forward = true), fileBrowserCommand(ControllerAction.PAGE_RIGHT, archive, atRoot = false))
        assertEquals(FileBrowserCommand.Page(forward = false), fileBrowserCommand(ControllerAction.PAGE_LEFT, archive, atRoot = false))
    }

    @Test
    fun `paging does not depend on what is focused -- it is a listing-level jump, not a tile action`() {
        listOf(directory, archive, plainFile, null).forEach { focused ->
            assertEquals(
                "PAGE_RIGHT with focused=$focused",
                FileBrowserCommand.Page(forward = true),
                fileBrowserCommand(ControllerAction.PAGE_RIGHT, focused, atRoot = true),
            )
        }
    }

    @Test
    fun `paging is still offered while the commit affordance holds focus -- it is a row of the listing too`() {
        assertEquals(
            FileBrowserCommand.Page(forward = true),
            fileBrowserCommand(ControllerAction.PAGE_RIGHT, null, atRoot = true, focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
    }

    // ── Everything else is swallowed: this is a hard modal ──

    @Test
    fun `triggers and every SYSTEM or GLOBAL action are swallowed`() {
        // SYSTEM_START and SYSTEM_SELECT are still in this list on purpose after the earlier change bound them.
        // Every call below takes the DEFAULT deleteAvailable/queueAvailable (both false), so this test now
        // does double duty: the hard-modal swallow it always pinned, and the polarity that makes
        // FileBrowserMode.PICK incapable rather than restricted. See the two the earlier change blocks below.
        val swallowed = listOf(
            ControllerAction.TERTIARY,
            ControllerAction.TRIGGER_LEFT,
            ControllerAction.TRIGGER_RIGHT,
            ControllerAction.SYSTEM_START,
            ControllerAction.SYSTEM_SELECT,
            ControllerAction.SYSTEM_HOME,
            ControllerAction.SYSTEM_MENU,
            ControllerAction.GLOBAL_QUICK_LAUNCH,
            ControllerAction.GLOBAL_UTILITY,
            ControllerAction.GLOBAL_TWEAKER,
            ControllerAction.GLOBAL_SETTINGS,
        )
        swallowed.forEach { unhandled ->
            assertEquals(
                "$unhandled must be consumed, never fall through",
                FileBrowserCommand.Consumed,
                fileBrowserCommand(unhandled, archive, atRoot = true),
            )
        }
    }

    // ── F-1/R-3, 2026-08-18: SELECT opens the action overlay, START opens the queue ──
    //
    // `SELECT deletes the focused tile...` is DELETED, not moved: `FileBrowserCommand.Delete` no longer
    // exists -- SELECT no longer deletes directly, it opens the menu Delete now lives inside
    // (`FileBrowserCommand.OpenActionOverlay`). The replacement below pins the new command with the same
    // "not tile-KIND-sensitive the way extract is" property this test used to pin for Delete, now true of
    // the DOOR (`actionsAvailable`) rather than the row.

    @Test
    fun `X opens the action overlay, carrying the focused tile's path AND kind`() {
        listOf(directory, archive, plainFile).forEach { focused ->
            assertEquals(
                "actionsAvailable is the one door -- true for any kind here (this fixture's caller " +
                    "declares itself capable of every row), so SELECT reaches the overlay regardless of " +
                    "which kind is focused, carrying that tile's own kind for the overlay's row partition",
                FileBrowserCommand.OpenActionOverlay(focused.path, focused.kind),
                fileBrowserCommand(
                    ControllerAction.SECONDARY,
                    focused,
                    atRoot = false,
                    actionsAvailable = true,
                ),
            )
        }
    }

    @Test
    fun `SELECT is inert with nothing focused -- the PICK commit affordance has no path to point the overlay at`() {
        // [focused] is null for the whole interval the pinned commit row holds focus (see
        // fileBrowserCommand's CONFIRM branch), so this single case IS the commit-row case.
        assertEquals(
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.SYSTEM_SELECT, null, atRoot = false, actionsAvailable = true),
        )
        assertEquals(
            "and explicitly with the commit affordance named, so the intent survives a future refactor " +
                "that lets a tile stay focused underneath it",
            FileBrowserCommand.Consumed,
            fileBrowserCommand(
                ControllerAction.SYSTEM_SELECT,
                null,
                atRoot = false,
                focusedCommitPurpose = FileBrowserCommitPurpose.CHOOSE_SOURCE,
                actionsAvailable = true,
            ),
        )
    }

    @Test
    fun `SELECT is inert with actionsAvailable false, whatever else is true -- the pre-F-1 fail-closed polarity`() {
        listOf(directory, archive, plainFile).forEach { focused ->
            assertEquals(
                "actionsAvailable defaults false, so an un-updated caller's SELECT is exactly as swallowed " +
                    "as it always was",
                FileBrowserCommand.Consumed,
                fileBrowserCommand(ControllerAction.SYSTEM_SELECT, focused, atRoot = false),
            )
        }
    }

    // ── F-1/R-13, 2026-08-18: X/SECONDARY goes quiet once the overlay covers the focused tile ──

    /**
     * **R-21, **
     *
     * Both halves are asserted, and the second is the one that protects everything else: a file becomes
     * committable **only** in a source pick. Everywhere else it stays exactly as inert as it was, so this
     * cannot reach MANAGE — where CONFIRM on a file would be a press with no meaning — or a destination
     * pick, where it would answer a "where?" question with a file.
     */
    @Test
    fun `a focused file commits itself as a source, and only in a source pick`() {
        listOf(plainFile, archive).forEach { focused ->
            assertEquals(
                "in a source pick, ${focused.kind} commits ITSELF -- a zipped ROM is a single game to " +
                    "every emulator that reads one, so ARCHIVE is included deliberately",
                FileBrowserCommand.CommitSourceFile(focused.path),
                fileBrowserCommand(
                    ControllerAction.CONFIRM,
                    focused,
                    atRoot = false,
                    sourcePickAvailable = true,
                ),
            )
            assertEquals(
                "outside a source pick it is untouched -- ${focused.kind} stays Consumed, which is what " +
                    "keeps R-21 out of MANAGE and out of every destination pick",
                FileBrowserCommand.Consumed,
                fileBrowserCommand(
                    ControllerAction.CONFIRM,
                    focused,
                    atRoot = false,
                    sourcePickAvailable = false,
                ),
            )
        }
    }

    @Test
    fun `a directory still enters, even in a source pick`() {
        assertEquals(
            "R-21 added a meaning for files and changed none for folders -- navigating INTO a folder is " +
                "how the player reaches the game in the first place",
            FileBrowserCommand.Enter(directory.path),
            fileBrowserCommand(
                ControllerAction.CONFIRM,
                directory,
                atRoot = false,
                sourcePickAvailable = true,
            ),
        )
    }

    /**
     * **X replaces SELECT for the browser overlay to improve reachability.**
     *
     * SELECT held delete before F-1 and the overlay after it. It now holds nothing, and this is the pin
     * that says so — asserted for every kind and for both availability states, because "quiet" that is
     * only quiet on the tile someone happened to test is not quiet.
     *
     * `Consumed`, not unbound: this browser is a hard modal and swallows what it does not use, so stray input must not reach past
     * Files to whatever is underneath.
     */
    @Test
    fun `SELECT is quiet in Files -- every kind, wired or not`() {
        listOf(directory, archive, plainFile).forEach { focused ->
            listOf(true, false).forEach { available ->
                assertEquals(
                    "SELECT must be swallowed for ${focused.kind} with actionsAvailable=$available",
                    FileBrowserCommand.Consumed,
                    fileBrowserCommand(
                        ControllerAction.SYSTEM_SELECT,
                        focused,
                        atRoot = false,
                        actionsAvailable = available,
                    ),
                )
            }
        }
    }

    @Test
    fun `X opens the overlay once actionsAvailable is true, on an archive it used to extract directly`() {
        assertEquals(
            "the overlay moved onto X. The archive's direct extract does not " +
                "come back beside it -- the overlay owns Extract, and a duplicate path is what the single-entry policy guards " +
                "against. Only which button opens the one door changed.",
            FileBrowserCommand.OpenActionOverlay(archive.path, archive.kind),
            fileBrowserCommand(
                ControllerAction.SECONDARY,
                archive,
                atRoot = true,
                actionsAvailable = true,
                extractChoiceAvailable = true,
            ),
        )
    }

    @Test
    fun `SECONDARY on an archive is UNCHANGED when actionsAvailable is false -- PICK's own path survives`() {
        // actionsAvailable defaults false in PICK by construction (fileBrowserActionsAvailable's own
        // manageMode gate) -- this pins that the archive arm underneath the new guard is still exactly the
        // byte-for-byte pre-F-1 behaviour, not merely unreachable-and-therefore-untested.
        assertEquals(
            FileBrowserCommand.OfferExtract("/root/bundle.zip"),
            fileBrowserCommand(
                ControllerAction.SECONDARY,
                archive,
                atRoot = true,
                extractChoiceAvailable = true,
            ),
        )
    }

    @Test
    fun `START opens the queue, and is consumed rather than falling through when it cannot`() {
        assertEquals(
            FileBrowserCommand.OpenQueue,
            fileBrowserCommand(ControllerAction.SYSTEM_START, archive, atRoot = false, queueAvailable = true),
        )
        assertEquals(
            "unbound START must keep the property it had when it was merely swallowed: a hard modal " +
                "never lets START reach the Settings screen underneath it",
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.SYSTEM_START, archive, atRoot = false, queueAvailable = false),
        )
    }

    @Test
    fun `START does not need a focused tile -- the queue is not about the listing`() {
        assertEquals(
            FileBrowserCommand.OpenQueue,
            fileBrowserCommand(ControllerAction.SYSTEM_START, null, atRoot = true, queueAvailable = true),
        )
    }

    @Test
    fun `PICK is INCAPABLE, not restricted -- no argument combination opens the action overlay or the queue`() {
        // PICK capability boundary. Exhaustive over the OTHER five arguments with the three capability
        // flags left at their false defaults (F-1 adds actionsAvailable beside deleteAvailable's own
        // former slot here): if any combination below produced a destructive command, PICK safety would
        // depend on a caller remembering a check somewhere downstream.
        val tiles = listOf(null, directory, archive, plainFile)
        var cases = 0
        ControllerAction.values().forEach { action ->
            tiles.forEach { focused ->
                listOf(true, false).forEach { atRoot ->
                    listOf(FileBrowserCommitPurpose.CHOOSE_SOURCE, null).forEach { focusedCommitPurpose ->
                        listOf(true, false).forEach { searchAvailable ->
                            val command = fileBrowserCommand(
                                action = action,
                                focused = focused,
                                atRoot = atRoot,
                                focusedCommitPurpose = focusedCommitPurpose,
                                searchAvailable = searchAvailable,
                            )
                            cases += 1
                            assertNotEquals(
                                "$action with focused=$focused must not open the action overlay when " +
                                    "actionsAvailable defaults false",
                                FileBrowserCommand.OpenActionOverlay(
                                    focused?.path.orEmpty(),
                                    focused?.kind ?: FileBrowserTileKind.FILE,
                                ),
                                command,
                            )
                            assertNotEquals(
                                "$action must not open the queue when queueAvailable defaults false",
                                FileBrowserCommand.OpenQueue,
                                command,
                            )
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must actually have run -- a zero-case loop asserts nothing", cases > 100)
    }

    // ── Tap ──
    //
    // Three tests lived here and are DELETED, not moved wholesale: they pinned `fileBrowserTapCommand`,
    // a second pure decision table that no longer exists (2026-08-14, policy on the touch
    // grammar). Its cases were "a single tap only focuses" and "a double tap on a directory opens" --
    // both statements about a rule that has been overruled, so keeping them green would have meant
    // keeping the defect.
    //
    // **The coverage they carried did not evaporate; it moved up one level, on purpose.** A tap is now
    // routed by `FileBrowserController.handleTap` through the CONFIRM arm asserted above, so the
    // equivalent pins are controller-level and live in `FileBrowserTouchGrammarTest`: a tap on a
    // directory enters in ONE call, a tap on an archive or a plain file focuses and does nothing else,
    // and a long-press reaches SECONDARY. Pinning them here instead would have re-created exactly the
    // parallel table this change deleted.
}
