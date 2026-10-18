package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserFooterHintsTest {
    private val english = filesEnglish()

    private fun tile(name: String, kind: FileBrowserTileKind) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = kind == FileBrowserTileKind.DIRECTORY),
        kind = kind,
        machinery = false,
        platformIdValue = null,
    )

    private val directory = tile("Roms", FileBrowserTileKind.DIRECTORY)
    private val archive = tile("bundle.zip", FileBrowserTileKind.ARCHIVE)
    private val plainFile = tile("readme.txt", FileBrowserTileKind.FILE)

    @Test
    fun `a directory hints OPEN`() {
        assertEquals(listOf("A" to "Open", "B" to "Back"), fileBrowserFooterHints(english, directory, atRoot = false))
    }

    @Test
    fun `an extractable archive hints EXTRACT on X, never on A`() {
        assertEquals(listOf("X" to "Extract", "B" to "Back"), fileBrowserFooterHints(english, archive, atRoot = false))
    }

    @Test
    fun `a plain non-actionable file advertises no primary verb -- only the back hint`() {
        assertEquals(listOf("B" to "Back"), fileBrowserFooterHints(english, plainFile, atRoot = false))
    }

    @Test
    fun `nothing focused advertises no primary verb either`() {
        assertEquals(listOf("B" to "Back"), fileBrowserFooterHints(english, null, atRoot = false))
    }

    @Test
    fun `at the root the back hint reads CLOSE instead of BACK`() {
        assertEquals(listOf("A" to "Open", "B" to "Close"), fileBrowserFooterHints(english, directory, atRoot = true))
    }

    @Test
    fun `CHOOSE hint appears iff the commit affordance is focused -- restored 2026-08-14 for PICK mode`() {
        assertEquals(
            listOf("A" to "Choose", "B" to "Close"),
            fileBrowserFooterHints(english, null, atRoot = true, commitFocused = true),
        )
        assertEquals(
            "default commitFocused=false must reproduce every pre-2026-08-14 caller's behaviour unchanged",
            emptyList<Pair<String, String>>(),
            fileBrowserFooterHints(english, directory, atRoot = true).filter { it.second == "Choose" },
        )
    }

    @Test
    fun `commitFocused takes priority over a focused tile for the primary-verb hint`() {
        assertEquals(
            listOf("A" to "Choose", "B" to "Back"),
            fileBrowserFooterHints(english, archive, atRoot = false, commitFocused = true),
        )
    }

    // ── F-2, 2026-08-18: the back-to-origin row's own hint ──

    @Test
    fun `BACK TO ORIGIN hint appears iff the back-to-origin row is focused`() {
        assertEquals(
            listOf("A" to "Back to origin", "B" to "Back"),
            fileBrowserFooterHints(english, null, atRoot = false, backToOriginFocused = true),
        )
        assertEquals(
            "default backToOriginFocused=false must reproduce every pre-F-2 caller's legend unchanged",
            emptyList<Pair<String, String>>(),
            fileBrowserFooterHints(english, directory, atRoot = false).filter { it.second == "Back to origin" },
        )
    }

    @Test
    fun `backToOriginFocused takes priority over commitFocused -- the two rows are never both focused, but the branch order is pinned`() {
        assertEquals(
            listOf("A" to "Back to origin", "B" to "Back"),
            fileBrowserFooterHints(english, null, atRoot = false, commitFocused = true, backToOriginFocused = true),
        )
    }

    @Test
    fun `BACK TO ORIGIN is drawn even at the root, mirroring B's own BACK-during-a-pick exception`() {
        // The row only exists at all while a pick is open, and a pick's B already says BACK (never
        // CLOSE) at the root -- see fileBrowserExitOpensQueue's own destinationPick gate. This pins that
        // A's word does not regress to something root-specific when this row is what holds focus there.
        assertEquals(
            listOf("A" to "Back to origin", "B" to "Back"),
            fileBrowserFooterHints(english,
                null,
                atRoot = true,
                backToOriginFocused = true,
                destinationPick = FileBrowserDestinationPickPhase.SETTLED,
            ),
        )
    }

    // ── F-1/R-3, 2026-08-18: SELECT ACTIONS (replaces SELECT DELETE), X EXTRACT retires when it fires ──
    //
    // `DELETE is advertised on SELECT...` and `no DELETE hint is drawn with nothing focused...` are
    // DELETED, not moved: `deleteAvailable` is gone from this function's signature (Delete moved inside
    // the overlay as a row) and `SELECT DELETE` no longer exists as a word this legend can print. The
    // three tests below pin the CURRENT truth with the SAME properties the deleted two established --
    // tile-independence, the null-focus refusal, and "the legend reads the identical gate the dispatch
    // table does" -- against `actionsAvailable` rather than `deleteAvailable`.

    @Test
    fun `ACTIONS is advertised on X whenever the overlay has a row for the focused tile`() {
        assertEquals(
            listOf("A" to "Open", "X" to "Actions", "B" to "Back"),
            fileBrowserFooterHints(english, directory, atRoot = false, actionsAvailable = true),
        )
        assertEquals(
            "actionsAvailable is not tile-KIND-sensitive the way the OLD deleteAvailable-only gate looked " +
                "-- it is true here because THIS fixture's caller already resolved at least one legal row " +
                "for the tile, whichever kind it is -- and X's old EXTRACT hint is replaced by ACTIONS rather than sitting beside it, " +
                "R-13: the overlay now owns that verb",
            listOf("X" to "Actions", "B" to "Back"),
            fileBrowserFooterHints(english, archive, atRoot = false, actionsAvailable = true),
        )
    }

    @Test
    fun `X EXTRACT survives untouched when actionsAvailable is false -- PICK's own legend is unchanged`() {
        assertEquals(
            "actionsAvailable defaults false (PICK, by construction) -- the pre-F-1 X EXTRACT hint must " +
                "still read exactly as it always did, with no SELECT entry drawn beside it",
            listOf("X" to "Extract", "B" to "Back"),
            fileBrowserFooterHints(english, archive, atRoot = false),
        )
    }

    @Test
    fun `no ACTIONS hint is drawn with nothing focused -- the commit affordance has no tile to point the overlay at`() {
        assertEquals(
            listOf("B" to "Back"),
            fileBrowserFooterHints(english, null, atRoot = false, actionsAvailable = true),
        )
        assertEquals(
            "the legend must read the same condition fileBrowserCommand's SYSTEM_SELECT branch reads, " +
                "or the footer promises a press the dispatch table refuses -- unchanged shape from the " +
                "deleted deleteAvailable-era test this replaces",
            listOf("A" to "Choose", "B" to "Close"),
            fileBrowserFooterHints(english, null, atRoot = true, commitFocused = true, actionsAvailable = true),
        )
    }

    @Test
    fun `actionsAvailable defaults false, so an un-updated caller's legend is byte-identical to pre-F-1`() {
        listOf(directory, archive, plainFile, null).forEach { focused ->
            listOf(true, false).forEach { atRoot ->
                assertEquals(
                    fileBrowserFooterHints(english, focused, atRoot = atRoot),
                    fileBrowserFooterHints(english, focused, atRoot = atRoot, actionsAvailable = false),
                )
            }
        }
    }

    // ── F-1: the action overlay's own legend ──

    @Test
    fun `the overlay legend hands off on Move Copy, names the verb on Extract and Delete, and always leaves`() {
        assertEquals(
            listOf("A" to "Choose folder", "B" to "Back"),
            fileBrowserActionOverlayFooterHints(english, FileBrowserActionOverlayRowKind.MOVE),
        )
        assertEquals(
            listOf("A" to "Choose folder", "B" to "Back"),
            fileBrowserActionOverlayFooterHints(english, FileBrowserActionOverlayRowKind.COPY),
        )
        assertEquals(
            listOf("A" to "Extract", "B" to "Back"),
            fileBrowserActionOverlayFooterHints(english, FileBrowserActionOverlayRowKind.EXTRACT),
        )
        assertEquals(
            "Delete's row IS the write -- the one exception design spec §8 names",
            listOf("A" to "Delete", "B" to "Back"),
            fileBrowserActionOverlayFooterHints(english, FileBrowserActionOverlayRowKind.DELETE),
        )
        assertEquals(
            "before landing focus arrives (or on a foreign key), no A hint -- B still leaves",
            listOf("B" to "Back"),
            fileBrowserActionOverlayFooterHints(english, null),
        )
    }

    @Test
    fun `the QUEUE hint is PERSISTENT, count or no count -- UI-03, a hint that vanishes does not register`() {
        assertEquals(
            "an empty queue still draws the binding: Undo is the only protection a delete has, and a " +
                "player who never sees the hint never finds the screen that holds it",
            listOf("A" to "Open", "START" to "Queue", "B" to "Back"),
            fileBrowserFooterHints(english, directory, atRoot = false, queueAvailable = true, queueCount = 0),
        )
        assertEquals(
            listOf("A" to "Open", "START" to "Queue · 3", "B" to "Back"),
            fileBrowserFooterHints(english, directory, atRoot = false, queueAvailable = true, queueCount = 3),
        )
    }

    @Test
    fun `the QUEUE hint does not depend on a focused tile -- unlike every other verb on this legend`() {
        assertEquals(
            listOf("START" to "Queue · 1", "B" to "Close"),
            fileBrowserFooterHints(english, null, atRoot = true, queueAvailable = true, queueCount = 1),
        )
    }

    @Test
    fun `PICK advertises neither verb -- both default false, so the pre-PR-140 legend is unchanged`() {
        listOf(directory, archive, plainFile, null).forEach { focused ->
            listOf(true, false).forEach { atRoot ->
                val hints = fileBrowserFooterHints(english, focused, atRoot = atRoot, searchAvailable = true)
                assertTrue(
                    "a PICK browser must advertise no destructive verb for focused=$focused: $hints",
                    hints.none { it.first == "SELECT" || it.first == "START" },
                )
            }
        }
    }

    @Test
    fun `SELECT and START are real controller hint tokens, not raw text that skips label projection`() {
        // MimirControllerHintLabels' own token list (core-ui/MimirControllerHints.kt) carries "START" and
        // "SELECT" alongside A/B/X/Y. If a token here were not in that list, FileBrowserFooterLegend's
        // `labels.labelFor(token)` would fall through to the raw string and the player's remapped physical
        // button name would never reach the legend.
        val tokens = MimirControllerHintLabels(byFactoryToken = mapOf("SELECT" to "−", "START" to "+"))
        assertEquals("−", tokens.labelFor("SELECT"))
        assertEquals("+", tokens.labelFor("START"))
    }

    // ── the earlier change / F-01: the Queue screen's own legend ──

    @Test
    fun `the queue legend names the focused button's own verb, never one fixed word for all six`() {
        assertEquals(
            listOf("A" to "Undo", "B" to "Back"),
            fileBrowserQueueFooterHints(english, FileBrowserQueueAction.UNDO),
        )
        assertEquals(
            listOf("A" to "Keep deleted", "B" to "Back"),
            fileBrowserQueueFooterHints(english, FileBrowserQueueAction.KEEP_DELETED),
        )
        assertEquals(
            listOf("A" to "Dismiss", "B" to "Back"),
            fileBrowserQueueFooterHints(english, FileBrowserQueueAction.DISMISS),
        )
    }

    @Test
    fun `every queue action has a distinct legend word -- one word for two would be wrong on a delete`() {
        val words = FileBrowserQueueAction.entries.map { action ->
            fileBrowserQueueFooterHints(english, action).first { it.first == "A" }.second
        }
        assertEquals(
            "a legend that read the same for Undo and Keep deleted would make the recoverable press and " +
                "the irreversible one indistinguishable at the moment of pressing",
            words.size,
            words.toSet().size,
        )
    }

    @Test
    fun `a queue with nothing focusable still advertises the way out`() {
        assertEquals(
            "a queue of running transfers registers zero focus keys; B leaves without consulting focus, " +
                "so the screen is escapable and the legend must say so",
            listOf("B" to "Back"),
            fileBrowserQueueFooterHints(english, null),
        )
    }
}
