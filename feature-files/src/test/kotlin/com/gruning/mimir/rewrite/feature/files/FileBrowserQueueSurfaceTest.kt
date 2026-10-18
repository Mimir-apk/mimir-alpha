package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileConflictChoice
import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.files.FileOperationQueue
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry
import com.gruning.mimir.rewrite.core.input.FocusKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.gruning.mimir.rewrite.core.model.jobs.JobKind

/**
 * **The Queue screen — C-3, plus the safety property that makes the no-confirmation delete flow
 * sound.**
 *
 * Delete has no confirm dialog by policy. That is only safe because Undo exists, and Undo exists
 * only on a card on this screen. So the assertions here are not about how a card looks — they are about
 * which store call a press reaches and which button is drawn loudest, both of which decide whether a
 * player's files come back.
 *
 * **What these tests do NOT prove, stated rather than implied.** `feature-files` configures no Compose
 * harness — plain JUnit, no Robolectric, no `createComposeRule` (`build.gradle.kts`). Nothing here shows
 * that a card reaches the screen, that a button is where it looks, or that the focus ring lands on it.
 * The pure functions are proven; the composable call sites are pinned as source text, which is this
 * repo's established fallback for exactly this situation (`FileBrowserSupportDimRenderTest` discloses
 * the same limit). Hardware is still the gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserQueueSurfaceTest {
    private val english = filesEnglish()

    // ─────────────────────────────────────────────────────── The loud button is the safe button ──

    /**
     * **The one that matters.** The emphasised button on a card is the one a player presses without
     * reading. On a screen whose whole reason for existing is that there is no confirm dialog, it must
     * never be the button that loses data.
     *
     * Asserted per state rather than as one rule, because each state has a different wrong answer:
     * `UndoAvailable`'s is "Keep deleted" (frees the staged payload for good) and `AwaitingConflict`'s
     * is "Replace" (overwrites a file that still exists).
     */
    @Test
    fun `the emphasised button never destroys data, in every state a card can be in`() {
        val destructive = mapOf(
            FileBrowserQueueAction.KEEP_DELETED to
                "permanently frees the staged files the player was one press away from getting back",
            FileBrowserQueueAction.REPLACE to "overwrites a file that still exists",
        )

        everyQueueState().forEach { state ->
            val lead = fileBrowserQueueLeadAction(cardFor(state))
            assertFalse(
                "the loudest button on a $state card is \"${lead?.name}\", which ${destructive[lead]}. " +
                    "The emphasised control is the one a player presses without reading, and this screen " +
                    "exists because there is no confirm dialog -- the lead must be the SAFE action in " +
                    "every state. See fileBrowserQueueLeadAction's KDoc.",
                lead in destructive.keys,
            )
        }
    }

    @Test
    fun `the emphasised button is Undo on an undoable delete, and Keep both on a conflict`() {
        assertEquals(
            "Undo is what returns the player's files; it must be the lead on an UndoAvailable card",
            FileBrowserQueueAction.UNDO,
            fileBrowserQueueLeadAction(cardFor(FileQueueState.UndoAvailable(stagedDeletion()))),
        )
        assertEquals(
            "Keep both is the answer under which nothing is lost, so it leads a conflict card",
            FileBrowserQueueAction.KEEP_BOTH,
            fileBrowserQueueLeadAction(cardFor(FileQueueState.AwaitingConflict)),
        )
        assertEquals(
            "a terminal card's only button frees nothing, so it is trivially the safe lead",
            FileBrowserQueueAction.DISMISS,
            fileBrowserQueueLeadAction(cardFor(FileQueueState.Done)),
        )
    }

    /** A running transfer carries no buttons at all, so there is nothing to emphasise. Null, not a
     * fabricated first entry. */
    @Test
    fun `a running card has no emphasised button because it has no buttons`() {
        assertNull(
            "a running DELETE (this helper's kind) has nothing to cancel, so no buttons; the lead must be " +
                "null rather than a default",
            fileBrowserQueueLeadAction(cardFor(FileQueueState.Running(0.4f))),
        )
    }

    // ─────────────────────────────────────────────────────────────── Where a press actually goes ──

    /**
     * Every arm of the routing, because getting two of them the wrong way round is a data-loss bug that
     * reads correctly: "Keep deleted" and "Dismiss" both remove a card, and only one of them frees the
     * player's files.
     */
    @Test
    fun `each queue button reaches its own store entry point, and no other`() {
        fun route(action: FileBrowserQueueAction): String {
            val log = mutableListOf<String>()
            fileBrowserQueueDispatch(
                target = FileBrowserQueueTarget(entryId = 7L, action = action),
                onUndo = { log += "undo($it)" },
                onReleaseUndo = { log += "releaseUndo($it)" },
                onDismiss = { log += "dismiss($it)" },
                onResolveConflict = { id, choice -> log += "resolveConflict($id,$choice)" },
            )
            return log.singleOrNull() ?: log.toString()
        }

        assertEquals("Undo must put the files back", "undo(7)", route(FileBrowserQueueAction.UNDO))
        assertEquals(
            "Keep deleted must FINALISE the delete (releaseUndo). Routing it to dismiss would leave the " +
                "staging journal holding the payload forever; routing it to undo would silently restore " +
                "files the player just said to destroy.",
            "releaseUndo(7)",
            route(FileBrowserQueueAction.KEEP_DELETED),
        )
        assertEquals(
            "Dismiss must reach dismiss and NOTHING else -- it drops a terminal card and frees nothing. " +
                "Routing it to releaseUndo would make a tidy-up press irreversible.",
            "dismiss(7)",
            route(FileBrowserQueueAction.DISMISS),
        )
        assertEquals(
            "resolveConflict(7,KEEP_BOTH)",
            route(FileBrowserQueueAction.KEEP_BOTH),
        )
        assertEquals("resolveConflict(7,REPLACE)", route(FileBrowserQueueAction.REPLACE))
        assertEquals("resolveConflict(7,SKIP)", route(FileBrowserQueueAction.SKIP))
    }

    /** The conflict answers are routed off `conflictChoice`, not by name, so the enum stays the single
     * source of "which action is a conflict answer". */
    @Test
    fun `the conflict route carries the enum's own choice, never a re-picked one`() {
        FileBrowserQueueAction.entries.filter { it.conflictChoice != null }.forEach { action ->
            var seen: FileConflictChoice? = null
            fileBrowserQueueDispatch(
                target = FileBrowserQueueTarget(1L, action),
                onUndo = {},
                onReleaseUndo = {},
                onDismiss = {},
                onResolveConflict = { _, choice -> seen = choice },
            )
            assertEquals(
                "$action must resolve with its own FileBrowserQueueAction.conflictChoice",
                action.conflictChoice,
                seen,
            )
        }
    }

    /** A settle/dismiss action must never reach `resolveConflict` — that call would answer a conflict
     * the player was never asked about. */
    @Test
    fun `no settling action can reach resolveConflict`() {
        listOf(
            FileBrowserQueueAction.UNDO,
            FileBrowserQueueAction.KEEP_DELETED,
            FileBrowserQueueAction.DISMISS,
        ).forEach { action ->
            var resolved = false
            fileBrowserQueueDispatch(
                target = FileBrowserQueueTarget(1L, action),
                onUndo = {},
                onReleaseUndo = {},
                onDismiss = {},
                onResolveConflict = { _, _ -> resolved = true },
            )
            assertFalse("$action must not answer a conflict", resolved)
        }
    }

    // ──────────────────────────────────────────────────────────────── Which card owns a focus key ──

    @Test
    fun `a focused button belongs to its own card and to no neighbour`() {
        val mine = cardFor(FileQueueState.UndoAvailable(stagedDeletion()), entryId = 4L)
        val neighbour = cardFor(FileQueueState.Done, entryId = 5L)
        val key = FileBrowserQueueFocus.actionKey(4L, FileBrowserQueueAction.UNDO)

        assertTrue("the card holding the focused button must claim it", fileBrowserQueueCardHolds(mine, key))
        assertFalse(
            "a NEIGHBOUR must not claim it -- the queue is newest-first, so every enqueue shifts every " +
                "card down one and a mis-attributed key would scroll the wrong operation into view",
            fileBrowserQueueCardHolds(neighbour, key),
        )
    }

    @Test
    fun `a foreign or stale key belongs to no card`() {
        val card = cardFor(FileQueueState.Done, entryId = 4L)
        assertFalse("null focus belongs to nothing", fileBrowserQueueCardHolds(card, null))
        assertFalse(
            "a browse tile key must not resolve to a queue card",
            fileBrowserQueueCardHolds(card, FileBrowserFocus.tileKey("/root/game.gba")),
        )
        assertFalse(
            "an unparseable queue key degrades to 'no card', never to card zero",
            fileBrowserQueueCardHolds(card, FocusKey("files/queue/not-a-number/UNDO")),
        )
    }

    // ───────────────────────────────────────────────────────────────── The empty screen's honesty ──

    @Test
    fun `the empty queue message promises only what has a production caller`() {
        assertTrue(
            "C-1: the empty screen has to name Undo -- it is the only protection a delete has, and this " +
                "screen is the only place it lives",
            english.getString(FileBrowserQueueEmptyMessage).contains("Undo"),
        )
        listOf("move", "Move", "copy", "Copy", "transfer", "Transfer").forEach { word ->
            assertFalse(
                "\"$word\" must not appear: moveInManager/copyInManager have production callers as of " +
                    "Move/Copy slice 1, but a completed move or copy has NO Undo, so promising this " +
                    "screen's \"waits here, with Undo\" for one would be a lie the other direction",
                english.getString(FileBrowserQueueEmptyMessage).contains(word),
            )
        }
    }

    @Test
    fun `the empty message does not restate the header line's no-network claim`() {
        assertFalse(
            "the header summary already carries \"nothing leaves this device\" and is rendered " +
                "directly above this line. A second copy is a second place for that promise to drift.",
            english.getString(FileBrowserQueueEmptyMessage).contains("leaves this device"),
        )
    }

    // ─────────────────────────────────────────────────────────── Call-site pins (source text only) ──

    /**
     * The header line is [fileBrowserQueueSummary], one plural string.
     *
     * **This is the pin that stops a paraphrase.** That string carries the no-network promise; its English is pinned
     * word for word against core-files' `FileOperationQueue.summary` below, and its Spanish in `FilesQueueTextTest`. A hand-written
     * version in this file would be a second source for a claim about the player's privacy.
     */
    @Test
    fun `the queue header renders the summary string and restates nothing`() {
        val source = queueSurfaceSource()
        assertTrue(
            "the summary must be called, not paraphrased",
            source.contains("fileBrowserQueueSummary(filesResources(), controller.queueEntries.size)"),
        )
        listOf(0, 1, 2, 7).forEach { count ->
            assertEquals(
                "the English summary reads as core-files words it",
                FileOperationQueue.summary(List(count) { FileQueueEntry(it.toLong(), JobKind.DELETE, "Zelda.gba", "Delete", FileQueueState.Done) }),
                fileBrowserQueueSummary(english, count),
            )
        }
        assertEquals(
            "no literal copy of the summary's wording may exist in this file",
            0,
            Regex("operations · nothing").findAll(source).count(),
        )
        // Sanity that the pin is looking at the right sentence at all.
        assertTrue(
            "precondition: core-files' summary is the sentence carrying the claim",
            FileOperationQueue.summary(emptyList()).contains("nothing leaves this device"),
        )
    }

    /** The bar is drawn only where the model says there is a fraction — a zero-width bar under a
     * finished operation would read as "stalled at 0%". */
    @Test
    fun `the progress bar is composed only under a non-null progress`() {
        val source = queueSurfaceSource()
        assertTrue(
            "the bar must hang off `card.progress?.let`; an `if (card.progress != null)` that then read " +
                "`?: 0f` would reintroduce the stalled-at-zero render",
            source.contains("card.progress?.let { fraction -> FileBrowserQueueProgressBar(fraction, palette) }"),
        )
        assertEquals(
            "the bar composable must have exactly one call site (the declaration's own " +
                "`(fraction: Float` does not match this pattern)",
            1,
            Regex("FileBrowserQueueProgressBar\\(fraction,").findAll(source).count(),
        )
    }

    /**
     * One scroll owner. Every focusable on this screen opts out of Compose's bring-into-view so the
     * single settlement `LaunchedEffect` owns the `LazyListState` — reintroducing a second owner is
     * half of this module's historical D-pad defect.
     */
    @Test
    fun `every focusable on the queue screen opts out of the second scroll owner`() {
        val source = queueSurfaceSource()
        val focusables = Regex("mimirFocusable\\([^)]*\\)").findAll(source).map { it.value }.toList()
        assertTrue("precondition: the screen registers at least one focusable", focusables.isNotEmpty())
        focusables.forEach { call ->
            assertTrue(
                "autoBringIntoView = false is what keeps this screen to ONE scroll owner. Found: <$call>",
                call.contains("autoBringIntoView = false"),
            )
        }
    }

    /** The single-FocusAuthority invariant, asserted on the new file rather than trusted to the
     * repo-wide grep gate. */
    @Test
    fun `the queue screen uses no Compose focus API`() {
        val source = queueSurfaceSource()
        listOf(".focusable(", "focusTarget(", "FocusRequester").forEach { forbidden ->
            assertFalse(
                "prohibited Compose focus API `$forbidden` -- this app has one FocusAuthority",
                source.contains(forbidden),
            )
        }
    }

    /** No hardcoded hex: nine themes, all dark, two metallic. Every colour on this screen is a palette
     * token. */
    @Test
    fun `the queue screen carries no hardcoded colour`() {
        val source = queueSurfaceSource()
        assertEquals(
            "no Color(0x...) literal may exist in a feature module -- LocalMimirPalette is the only source",
            0,
            Regex("Color\\(0x").findAll(source).count(),
        )
        assertTrue(
            "the FAILED chip arm must route through palette.danger, the one token meaning \"this went " +
                "wrong\" -- FileBrowserQueueTone has that arm precisely because FileBrowserChipTone does not",
            source.contains("FileBrowserQueueTone.FAILED -> palette.danger to palette.danger"),
        )
        // the card's chip is an outline, never a filled or tinted pill -- no background, no tint, the rim in the tone's colour.
        val chip = source.substringAfter("private fun FileBrowserQueueChip(").substringBefore("drawRoundRect")
        assertFalse("the queue chip has no fill", chip.contains("background(") || chip.contains("emberSoft") || chip.contains(".copy(alpha") || chip.contains("Color.Transparent"))
        assertTrue("the queue chip's tones are (word, rim) pairs", chip.contains("FileBrowserQueueTone.PENDING -> palette.emberHot to palette.emberHot") && chip.contains("FileBrowserQueueTone.DONE -> palette.success to palette.success"))
    }

    // ───────────────────────────────────────────────────────────── Helpers ──

    private fun everyQueueState(): List<FileQueueState> = listOf(
        FileQueueState.Running(0.5f),
        FileQueueState.AwaitingConflict,
        FileQueueState.UndoAvailable(stagedDeletion()),
        FileQueueState.Done,
        FileQueueState.Failed("disk full"),
        FileQueueState.Blocked("rar is not supported"),
    )

    private fun cardFor(state: FileQueueState, entryId: Long = 1L): FileBrowserQueueCard =
        fileBrowserQueueCards(
            listOf(
                FileQueueEntry(
                    id = entryId,
                    kind = JobKind.DELETE,
                    title = "Zelda.gba",
                    subtitle = "Delete",
                    state = state,
                ),
            ),
        ).single()

    /** A real [StagedDeletion], because [FileQueueState.UndoAvailable] cannot be constructed without one
     * — which is the structural property that keeps Undo honest. No filesystem is touched: the handle is
     * only carried, never used, by anything under test here. */
    private fun stagedDeletion(): StagedDeletion = StagedDeletion(
        operationRootPath = "/tmp/mimir-test/.mimir-staging/op-1",
        entries = listOf(
            StagedDeletionEntry(
                originalPath = "/tmp/mimir-test/Zelda.gba",
                stagedPath = "/tmp/mimir-test/.mimir-staging/op-1/Zelda.gba",
            ),
        ),
    )

    private fun queueSurfaceSource(): String = moduleSource("FileBrowserQueueSurface.kt")

    private fun moduleSource(name: String): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
