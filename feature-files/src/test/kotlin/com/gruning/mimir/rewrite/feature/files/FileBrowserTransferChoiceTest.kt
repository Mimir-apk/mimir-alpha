package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion

/**
 * **G2 slice 1 — the transfer chooser's RULES, with no controller and no authority anywhere.**
 *
 * The companion to `FileBrowserTransferWiringTest`, which pins that a press *reaches* these rules. This
 * file pins the rules themselves: the row model, the focus identity, the dispatch table's widened
 * `SECONDARY` gate, and the legend. Everything here is a pure function, so a failure names a decision
 * rather than a wiring.
 *
 * **What a passing run here does NOT prove.** It composes nothing — this module configures no Compose and
 * no Robolectric harness — so nothing below is evidence that a row reaches the screen, that a hit area
 * covers what it looks like it covers, or that two rows fit the 468 dp viewport. The chooser's `Column`
 * owns no scroll and overflow **clips silently**; two rows is arithmetically safe and four is the case M1
 * gates. Device measurement is still needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserTransferChoiceTest {
    private val english = filesEnglish()

    private fun tile(
        name: String,
        path: String,
        extension: String = "",
        isDirectory: Boolean = false,
        kind: FileBrowserTileKind,
    ) = FileBrowserTile(
        item = FileItem(name = name, path = path, extension = extension, isDirectory = isDirectory),
        kind = kind,
        machinery = false,
        platformIdValue = null,
    )

    private fun plainFile(path: String = "/root/Golden Sun.gba") =
        tile(name = "Golden Sun.gba", path = path, extension = "gba", kind = FileBrowserTileKind.FILE)

    private fun archive(path: String = "/root/Golden Sun.zip") =
        tile(name = "Golden Sun.zip", path = path, extension = "zip", kind = FileBrowserTileKind.ARCHIVE)

    private fun directory(path: String = "/root/Roms") =
        tile(name = "Roms", path = path, isDirectory = true, kind = FileBrowserTileKind.DIRECTORY)

    // ───────────────────────────────────────────────────── 1 · the row model ──

    @Test
    fun `the chooser is exactly two rows, Move then Copy, and slice 1 cannot draw a third`() {
        val rows = fileBrowserTransferOptions()

        assertEquals(
            "NEVER more than two rows in slice 1. The chooser's Column owns no scroll and no " +
                "LazyListState, so a third row clips SILENTLY -- the 4-row case is gated on a device " +
                "measurement (M1) nobody has taken. This is the pin that keeps slice 2 honest.",
            2,
            rows.size,
        )
        assertEquals(
            listOf(FileBrowserTransferKind.MOVE, FileBrowserTransferKind.COPY),
            rows.map { it.kind },
        )
    }

    @Test
    fun `the row labels are shared operation labels, and neither row says PLACE`() {
        val rows = fileBrowserTransferOptions()

        assertEquals("Move into…", english.getString(rows[0].labelRes))
        assertEquals("Copy into…", english.getString(rows[1].labelRes))
        assertTrue(
            "PLACE is the umbrella word for the LEGEND and the eyebrow -- one word covering both verbs. " +
                "A ROW may never be vague: a row is what gets pressed, and there is no Undo behind it.",
            rows.none { english.getString(it.labelRes).contains("Place", ignoreCase = true) },
        )
    }

    @Test
    fun `no row promises an Undo, a rescan, or a library effect`() {
        // A completed move or copy has NO Undo: FileBrowserQueueAction.UNDO needs FileQueueState
        // .UndoAvailable, which cannot be constructed without a StagedDeletion, and `move` deletes its own
        // staging on success. No presentation string may imply otherwise.
        val words = fileBrowserTransferOptions().map { english.getString(it.labelRes) } +
            fileBrowserTransferFooterHints(english, FileBrowserTransferKind.MOVE).map { it.second } +
            fileBrowserTransferFooterHints(english, FileBrowserTransferKind.COPY).map { it.second }

        listOf("undo", "revert", "safe", "reversible", "library", "rescan").forEach { forbidden ->
            assertTrue(
                "no transfer string may contain \"$forbidden\" -- a completed move has no Undo and the " +
                    "library does not notice it until slice 3. Found in: $words",
                words.none { it.contains(forbidden, ignoreCase = true) },
            )
        }
    }

    @Test
    fun `each kind projects onto exactly one commit purpose, and the two never collide`() {
        assertEquals(
            FileBrowserCommitPurpose.MOVE_DESTINATION,
            FileBrowserTransferKind.MOVE.commitPurpose,
        )
        assertEquals(
            FileBrowserCommitPurpose.COPY_DESTINATION,
            FileBrowserTransferKind.COPY.commitPurpose,
        )
        assertNotEquals(
            "if these two ever answered the same purpose, a Move question could be answered by a Copy",
            FileBrowserTransferKind.MOVE.commitPurpose,
            FileBrowserTransferKind.COPY.commitPurpose,
        )
    }

    // ───────────────────────────────────────────────────── 2 · focus identity ──

    @Test
    fun `the transfer scope and keys cannot collide with the extract chooser's`() {
        assertNotEquals(FileBrowserExtractFocus.Scope, FileBrowserTransferFocus.Scope)
        assertEquals("files/transfer", FileBrowserTransferFocus.Scope.value)

        val transferKeys = FileBrowserTransferKind.entries.map(FileBrowserTransferFocus::optionKey)
        val extractKeys = FileBrowserExtractOptionKind.entries.map(FileBrowserExtractFocus::optionKey)
        assertTrue(
            "DefaultFocusAuthority.entries is keyed by FocusKey GLOBALLY, so a shared key would evict " +
                "the other screen's row from the graph. Overlap found: " +
                "${transferKeys.intersect(extractKeys.toSet())}",
            transferKeys.intersect(extractKeys.toSet()).isEmpty(),
        )
        assertEquals(
            "and the two kinds must not share a key with each other either",
            transferKeys.size,
            transferKeys.toSet().size,
        )
    }

    @Test
    fun `optionOf recovers a kind only from this chooser's own keys`() {
        FileBrowserTransferKind.entries.forEach { kind ->
            assertEquals(kind, FileBrowserTransferFocus.optionOf(FileBrowserTransferFocus.optionKey(kind)))
        }
        assertNull(FileBrowserTransferFocus.optionOf(null))
        assertNull(FileBrowserTransferFocus.optionOf(FocusKey("files/tile:/root/x.gba")))
        assertNull(
            "an EXTRACT chooser key must not decode as a transfer row",
            FileBrowserTransferFocus.optionOf(
                FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
            ),
        )
        assertNull(FileBrowserTransferFocus.optionOf(FocusKey("files/transfer/RENAME")))
    }

    @Test
    fun `the rows' synthetic bounds are a single column, so a horizontal press has no answer`() {
        val first = fileBrowserTransferOptionBounds(0)
        val second = fileBrowserTransferOptionBounds(1)

        assertEquals("rows are full width and start-aligned", first.left, second.left, 0f)
        assertEquals(first.right, second.right, 0f)
        assertTrue("row 1 sits below row 0", second.top >= first.bottom)
    }

    // ─────────────────────────────────────── 3 · the widened SECONDARY gate ──

    @Test
    fun `SECONDARY on a plain file offers a transfer only when both sinks are wired`() {
        assertEquals(
            FileBrowserCommand.OfferTransfer("/root/Golden Sun.gba"),
            fileBrowserCommand(
                action = ControllerAction.SECONDARY,
                focused = plainFile(),
                atRoot = true,
                transferChoiceAvailable = true,
            ),
        )
        assertEquals(
            "fail closed: an unwired browser must be INCAPABLE of offering a chooser that opens nothing, " +
                "exactly as deleteAvailable makes a PICK browser incapable of deleting",
            FileBrowserCommand.Consumed,
            fileBrowserCommand(
                action = ControllerAction.SECONDARY,
                focused = plainFile(),
                atRoot = true,
                transferChoiceAvailable = false,
            ),
        )
    }

    @Test
    fun `a DIRECTORY never gets a transfer verb, whatever else is true`() {
        // QA drove a parent-into-descendant move in core-files: 9x amplification in 34 ms, no termination,
        // no free-space check, no cancel. `move`'s only self-guard is exact path equality, which does not
        // catch /Roms/PS2 -> /Roms/PS2/PS2. The refusal belongs in core-files and does not exist yet.
        listOf(true, false).forEach { atRoot ->
            listOf(true, false).forEach { extractWired ->
                assertEquals(
                    "SECONDARY on a DIRECTORY must stay inert -- slice 1 is FILES ONLY",
                    FileBrowserCommand.Consumed,
                    fileBrowserCommand(
                        action = ControllerAction.SECONDARY,
                        focused = directory(),
                        atRoot = atRoot,
                        extractChoiceAvailable = extractWired,
                        transferChoiceAvailable = true,
                    ),
                )
            }
        }
    }

    @Test
    fun `an ARCHIVE keeps its extract chooser and never produces OfferTransfer`() {
        assertEquals(
            "the archive arm is byte-for-byte the one that shipped",
            FileBrowserCommand.OfferExtract("/root/Golden Sun.zip"),
            fileBrowserCommand(
                action = ControllerAction.SECONDARY,
                focused = archive(),
                atRoot = true,
                extractChoiceAvailable = true,
                transferChoiceAvailable = true,
            ),
        )
        assertEquals(
            "and with only the transfer sinks wired it falls to the LEGACY extract path, not to transfer",
            FileBrowserCommand.Extract("/root/Golden Sun.zip"),
            fileBrowserCommand(
                action = ControllerAction.SECONDARY,
                focused = archive(),
                atRoot = true,
                extractChoiceAvailable = false,
                transferChoiceAvailable = true,
            ),
        )
    }

    @Test
    fun `extractable and FILE are disjoint, so the two arms can never both match`() {
        // Not a restatement of the branch order: FileBrowserTile.extractable is DERIVED as
        // `kind == ARCHIVE`, so this is a property of the type rather than of this dispatch table.
        listOf(plainFile(), archive(), directory()).forEach { t ->
            assertFalse(
                "no tile may be both extractable and a plain FILE",
                t.extractable && t.kind == FileBrowserTileKind.FILE,
            )
        }
    }

    @Test
    fun `SECONDARY stays swallowed for the whole duration of a destination pick`() {
        FileBrowserDestinationPickPhase.entries.forEach { phase ->
            assertEquals(
                "a second SECONDARY mid-pick would overwrite the pending pick's origin snapshot, so " +
                    "cancelling would restore the WRONG directory and the subject would silently change",
                FileBrowserCommand.Consumed,
                fileBrowserCommand(
                    action = ControllerAction.SECONDARY,
                    focused = plainFile(),
                    atRoot = false,
                    transferChoiceAvailable = true,
                    destinationPick = phase,
                ),
            )
        }
    }

    @Test
    fun `CONFIRM on the commit row maps each purpose to exactly one command`() {
        val mapping = mapOf(
            FileBrowserCommitPurpose.CHOOSE_SOURCE to FileBrowserCommand.CommitSource,
            // Settings › Music (2026-09-30): the music-folder pick commits the folder in view exactly as a source
            // pick does (close, then `onChoose(currentPath)`); the caller decides what the folder IS.
            FileBrowserCommitPurpose.CHOOSE_MUSIC_FOLDER to FileBrowserCommand.CommitSource,
            FileBrowserCommitPurpose.EXTRACT_DESTINATION to FileBrowserCommand.CommitExtractDestination,
            FileBrowserCommitPurpose.MOVE_DESTINATION to FileBrowserCommand.CommitMoveDestination,
            FileBrowserCommitPurpose.COPY_DESTINATION to FileBrowserCommand.CommitCopyDestination,
            // the image picker's pinned row is a switch, not a commit.
            FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER to FileBrowserCommand.ToggleImageFilter,
        )
        assertEquals(
            "every purpose must be covered -- a new arm with no command is a commit row that swallows A",
            FileBrowserCommitPurpose.entries.toSet(),
            mapping.keys,
        )
        mapping.forEach { (purpose, expected) ->
            assertEquals(
                expected,
                fileBrowserCommand(
                    action = ControllerAction.CONFIRM,
                    focused = null,
                    atRoot = false,
                    focusedCommitPurpose = purpose,
                ),
            )
        }
        assertEquals(
            "four distinct commands -- no arm may cover two verbs; only the two folder picks share CommitSource",
            mapping.size - 1,
            mapping.values.toSet().size,
        )
    }

    // ─────────────────────────────────────────────────────── 4 · the legend ──
    //
    // **`X says PLACE on a plain file...` and `the X word follows the ring...` are DELETED, not moved,
    // 2026-08-18 (F-1/R-13).** Both pinned `fileBrowserFooterHints`' `transferAvailable` parameter and its
    // `X PLACE` branch -- the parameter is gone (compile error, not a behaviour change nobody would
    // notice) and the branch cannot fire under ANY input any more, on `fileBrowserCommand`'s own SECONDARY
    // arm: `transferChoiceAvailable` is MANAGE-only, and MANAGE plus a focused FILE plus
    // `transferChoiceAvailable` is exactly the condition under which `fileBrowserActionsAvailable` is ALSO
    // true (Move/Copy are two of that tile's overlay rows), so a guarded `X PLACE` arm would have been a
    // branch that can never fire -- see `fileBrowserFooterHints`' own comment at the deleted arm's former
    // position for the full argument. Coverage did not evaporate: `FileBrowserFooterHintsTest` now pins
    // `SELECT ACTIONS` replacing `SELECT DELETE`, and `X EXTRACT`'s new `!actionsAvailable` guard, on the
    // one shared `actionsAvailable` predicate both the dispatch table and this legend read.
    //
    // **`every pre-G2 legend call is byte-identical -- the new parameter defaults off` is also DELETED**,
    // for the identical reason: it pinned `transferAvailable`'s default-off byte-identity, and the
    // parameter it pinned no longer exists. `fileBrowserFooterHints`' new `actionsAvailable` parameter has
    // its own default-off pin in `FileBrowserFooterHintsTest`.

    @Test
    fun `the commit row names the verb about to run, never the umbrella word`() {
        val move = fileBrowserFooterHints(english,
            focused = null,
            atRoot = false,
            commitFocused = true,
            commitPurpose = FileBrowserCommitPurpose.MOVE_DESTINATION,
            destinationPick = FileBrowserDestinationPickPhase.AWAY,
        )
        val copy = fileBrowserFooterHints(english,
            focused = null,
            atRoot = false,
            commitFocused = true,
            commitPurpose = FileBrowserCommitPurpose.COPY_DESTINATION,
            destinationPick = FileBrowserDestinationPickPhase.AWAY,
        )

        assertEquals(listOf("A" to "Move here"), move.filter { it.first == "A" })
        assertEquals(listOf("A" to "Copy here"), copy.filter { it.first == "A" })
        assertTrue(
            "the umbrella word belongs to the press that OPENS the question, never to the one that " +
                "performs it -- this press has no Undo behind it and may not be vague about which verb runs",
            (move + copy).none { it.second.contains("Place", ignoreCase = true) },
        )
    }

    @Test
    fun `B never says CLOSE during a transfer pick`() {
        FileBrowserDestinationPickPhase.entries.forEach { phase ->
            assertEquals(
                "CANCEL cannot close the browser mid-pick, so CLOSE would promise an exit it does not do",
                listOf("B" to "Back"),
                fileBrowserFooterHints(english,
                    focused = null,
                    atRoot = true,
                    commitFocused = true,
                    commitPurpose = FileBrowserCommitPurpose.MOVE_DESTINATION,
                    destinationPick = phase,
                ).filter { it.first == "B" },
            )
        }
    }

    @Test
    fun `the chooser legend promises no write, on either row`() {
        FileBrowserTransferKind.entries.forEach { kind ->
            val hints = fileBrowserTransferFooterHints(english, kind)
            assertEquals(
                "NEITHER row moves or copies anything -- both hand the browser back to answer \"where?\". " +
                    "A `MOVE` here would promise a write that does not happen and leave the player " +
                    "waiting for a file to leave the listing. Same press, same word as the extract " +
                    "chooser's own picker row.",
                listOf("A" to "Choose folder", "B" to "Back"),
                hints,
            )
        }
        assertEquals(
            "null in the frame before the landing focus arrives: no A hint, but B still leaves",
            listOf("B" to "Back"),
            fileBrowserTransferFooterHints(english, null),
        )
    }
}
