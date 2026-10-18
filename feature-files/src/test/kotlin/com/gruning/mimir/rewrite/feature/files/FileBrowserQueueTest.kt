package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileConflictChoice
import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.files.FileOperationQueue
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry
import com.gruning.mimir.rewrite.core.input.FocusKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.gruning.mimir.rewrite.core.files.toJobKind

/**
 * Pins the Queue screen's decision layer — F-11.
 *
 * The invariants under test are the safety contract: **Undo stays honest** (a card can only offer
 * it when the state holds the handle that reverses the delete), **conflicts never resolve silently**
 * (all three answers, always, explicitly), and **PICK is incapable** rather than restricted.
 *
 * `FileOperationQueueTest` in `core-files` pins the model these read; this suite pins only the
 * projection on top of it, and deliberately re-asserts nothing that suite already owns except where the
 * point is that this file *delegates* rather than restates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserQueueTest {
    private val english = filesEnglish()

    private fun deletion(root: String = "/tmp/.mimir-staging/.delete-1") = StagedDeletion(
        operationRootPath = root,
        entries = listOf(StagedDeletionEntry("/tmp/Zelda.gba", "$root/payload/Zelda.gba")),
    )

    private fun entry(
        id: Long = 1,
        operation: FileOperation = FileOperation.DELETE,
        title: String = "Zelda.gba",
        subtitle: String = "Delete · saves and states kept",
        state: FileQueueState,
    ) = FileQueueEntry(id = id, kind = operation.toJobKind(), title = title, subtitle = subtitle, state = state)

    // ── Undo honesty ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Undo is offered exactly when the state carries the handle that reverses the delete`() {
        val held = fileBrowserQueueActions(FileQueueState.UndoAvailable(deletion()))

        assertTrue(
            "an entry holding a live StagedDeletion must offer Undo -- that handle IS the button's honesty",
            FileBrowserQueueAction.UNDO in held,
        )
        listOf(
            FileQueueState.Running(0.5f),
            FileQueueState.AwaitingConflict,
            FileQueueState.Done,
            FileQueueState.Failed("couldn't finish"),
            FileQueueState.Blocked("rar is not supported"),
        ).forEach { state ->
            assertTrue(
                "$state holds no StagedDeletion, so it must not offer Undo",
                FileBrowserQueueAction.UNDO !in fileBrowserQueueActions(state),
            )
        }
    }

    @Test
    fun `an undoable card offers both settlements, so a held delete is never stranded`() {
        val actions = fileBrowserQueueActions(FileQueueState.UndoAvailable(deletion()))

        assertEquals(
            "Undo puts the files back, Keep deleted frees the staged payload -- a card offering only one" +
                " would leave the player no way to settle the other direction short of closing the manager",
            listOf(FileBrowserQueueAction.UNDO, FileBrowserQueueAction.KEEP_DELETED),
            actions,
        )
    }

    // ── Conflicts resolve explicitly ──────────────────────────────────────────────────────────────

    @Test
    fun `an awaiting-conflict card offers Keep both, Replace and Skip -- all three, never a default`() {
        val actions = fileBrowserQueueActions(FileQueueState.AwaitingConflict)

        assertEquals(
            "conflicts never resolve silently: the three explicit answers, in the design's own order",
            listOf(
                FileBrowserQueueAction.KEEP_BOTH,
                FileBrowserQueueAction.REPLACE,
                FileBrowserQueueAction.SKIP,
            ),
            actions,
        )
    }

    @Test
    fun `each conflict action carries the core-files resolution, so no second mapping can drift`() {
        assertEquals(
            FileConflictChoice.KEEP_BOTH,
            FileBrowserQueueAction.KEEP_BOTH.conflictChoice,
        )
        assertEquals(FileConflictChoice.REPLACE, FileBrowserQueueAction.REPLACE.conflictChoice)
        assertEquals(FileConflictChoice.SKIP, FileBrowserQueueAction.SKIP.conflictChoice)
        assertNull(
            "Undo settles a delete, it does not answer a conflict",
            FileBrowserQueueAction.UNDO.conflictChoice,
        )
        assertNull(
            "Keep deleted settles a delete, it does not answer a conflict",
            FileBrowserQueueAction.KEEP_DELETED.conflictChoice,
        )
    }

    @Test
    fun `the three conflict buttons read in English as core-files names the answers`() {
        assertEquals(FileConflictChoice.KEEP_BOTH.label, english.getString(FileBrowserQueueAction.KEEP_BOTH.labelRes))
        assertEquals(FileConflictChoice.REPLACE.label, english.getString(FileBrowserQueueAction.REPLACE.labelRes))
        assertEquals(FileConflictChoice.SKIP.label, english.getString(FileBrowserQueueAction.SKIP.labelRes))
    }

    // ── No button over a missing engine ───────────────────────────────────────────────────────────

    @Test
    fun `the state alone gives a running operation no buttons -- Cancel comes from the entry's kind`() {
        assertEquals(
            "Cancel is decided by the entry, because a running delete has nothing to cancel; " +
                "see FileBrowserRenameAndCancelTest for the entry-based answer",
            emptyList<FileBrowserQueueAction>(),
            fileBrowserQueueActions(FileQueueState.Running(0.4f)),
        )
    }

    // ── the terminal-state dismiss, PR #140 ───────────────────────────────────────────────────────
    //
    // This block REPLACES `a finished or failed card carries no buttons -- the missing store dismiss is
    // disclosed, not faked`, which asserted emptyList() for all three terminal states and carried the
    // reason in its message: "FileOperationQueue.remove is reachable only through undo/releaseUndo, both
    // of which need an UndoAvailable handle, so a Done entry has no way to leave the queue yet". That was
    // true when it was written and is false now that FileBrowserStore.dismiss(entryId) exists. A test
    // asserting an absence that has since been filled is not merely stale -- it actively pins the defect,
    // so it is deleted rather than relaxed.

    @Test
    fun `every terminal card can be dismissed -- a Done entry is no longer stranded in the queue`() {
        listOf(
            FileQueueState.Done,
            FileQueueState.Failed("couldn't finish"),
            FileQueueState.Blocked("rar is not supported"),
        ).forEach { state ->
            assertEquals(
                "a $state card must offer exactly one button -- FileBrowserStore.dismiss(entryId), which " +
                    "accepts precisely these three states and nothing else",
                listOf(FileBrowserQueueAction.DISMISS),
                fileBrowserQueueActions(state),
            )
        }
    }

    @Test
    fun `an undoable card is NEVER dismissable -- tidying a card away must not finalise a delete`() {
        assertFalse(
            "the store no-ops dismiss for UndoAvailable, and this projection must not offer a button it " +
                "refuses. Undo is the only protection a delete has: a Dismiss that accepted an undoable " +
                "entry would silently release the staged payload of a deletion the player thought they " +
                "were merely tidying away",
            FileBrowserQueueAction.DISMISS in fileBrowserQueueActions(FileQueueState.UndoAvailable(deletion())),
        )
        assertFalse(
            FileBrowserQueueAction.DISMISS in fileBrowserQueueActions(FileQueueState.Running(0.5f)),
        )
        assertFalse(
            FileBrowserQueueAction.DISMISS in fileBrowserQueueActions(FileQueueState.AwaitingConflict),
        )
    }

    @Test
    fun `Dismiss and Keep deleted are different words because they do different things to the FILES`() {
        assertNotEquals(
            "one drops a card, the other releases a staged payload permanently -- sharing a word would " +
                "make the harmless press and the irreversible one indistinguishable",
            english.getString(FileBrowserQueueAction.DISMISS.labelRes),
            english.getString(FileBrowserQueueAction.KEEP_DELETED.labelRes),
        )
        assertEquals("Dismiss", english.getString(FileBrowserQueueAction.DISMISS.labelRes))
        assertNull(
            "Dismiss is not a conflict answer, so it must carry no FileConflictChoice for a dispatch " +
                "site to mistake for one",
            FileBrowserQueueAction.DISMISS.conflictChoice,
        )
    }

    // ── The card projection ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a progress bar is drawn only while running, and never as a zero-width bar under a finished card`() {
        val running = fileBrowserQueueCards(listOf(entry(state = FileQueueState.Running(0.42f)))).single()
        val done = fileBrowserQueueCards(listOf(entry(state = FileQueueState.Done))).single()

        assertEquals(0.42f, running.progress!!, 0.0001f)
        assertNull(
            "a 0f progress under a finished card would read as 'stalled at 0%' -- the F-09 fabrication class",
            done.progress,
        )
    }

    @Test
    fun `progress is clamped, so a bad fraction cannot paint outside the track`() {
        val over = fileBrowserQueueCards(listOf(entry(state = FileQueueState.Running(4f)))).single()
        val under = fileBrowserQueueCards(listOf(entry(state = FileQueueState.Running(-1f)))).single()

        assertEquals(1f, over.progress!!, 0.0001f)
        assertEquals(0f, under.progress!!, 0.0001f)
    }

    /**
     * **F-6 — classifies this module's behaviour for `Running(fraction = null)` with a test, because
     * `FileBrowserQueue.kt` cannot be edited to classify it with a new `when` arm.**
     *
     * `FileQueueState.Running` widened to a nullable fraction rather than a new sealed state specifically
     * so that `fileBrowserQueueActions`/`fileBrowserQueueTone`'s exhaustive `when`s here — a file outside
     * F-6's declared scope — would not need touching to keep `:feature-files:testDebugUnitTest` green (see
     * `FileQueueState.Running`'s own KDoc in `core-files` for the full reasoning). That is a deliberate
     * scope boundary, not a licence to leave the new value's behaviour here unexamined: this pins exactly
     * what a caller gets today, so a future change to this file's cast-based projection
     * (`fileBrowserQueueCards`' `(entry.state as? Running)?.fraction`) cannot silently start drawing
     * something else for it without a test noticing.
     */
    @Test
    fun `a Running card with no honest fraction draws no bar, no buttons, and a neutral tone`() {
        val indeterminate = FileQueueState.Running(fraction = null)
        val card = fileBrowserQueueCards(listOf(entry(state = indeterminate))).single()

        assertNull(
            "no honest fraction to draw -- this is the SAME null-means-no-bar contract a finished card " +
                "already uses (the test above), reached for a different reason",
            card.progress,
        )
        assertTrue(
            "still a running operation, and this is a DELETE entry (the helper's default): nothing to cancel, so no buttons",
            fileBrowserQueueActions(indeterminate).isEmpty(),
        )
        assertEquals(
            "still Running's own tone -- indeterminate is not a new kind of state, just a Running card " +
                "the engine cannot honestly measure right now",
            FileBrowserQueueTone.NEUTRAL,
            card.tone,
        )
    }

    @Test
    fun `the card carries its state and the English chip reads as core-files names it`() {
        val states = listOf(
            FileQueueState.Running(0.5f),
            FileQueueState.Running(fraction = null),
            FileQueueState.Running(0.4f, cancelling = true),
            FileQueueState.Running(0.9f, placed = 3, placeTotal = 8),
            FileQueueState.AwaitingConflict,
            FileQueueState.UndoAvailable(deletion()),
            FileQueueState.Done,
            FileQueueState.ReverseAvailable(ReversibleMove(listOf("/b/a.gba"), "/a")),
            FileQueueState.ReverseAvailable(ReversibleMove(listOf("/a/new.gba"), "/a", previousName = "old.gba")),
            FileQueueState.Skipped,
            FileQueueState.Cancelled("2 of 5 files were kept."),
            FileQueueState.Failed("couldn't finish"),
            FileQueueState.Blocked("rar is not supported"),
        )

        states.forEachIndexed { index, state ->
            val source = entry(id = index.toLong(), state = state)
            val card = fileBrowserQueueCards(listOf(source)).single()
            assertEquals("the card holds the entry's own state, never a pre-made English word", state, card.state)
            assertEquals(
                "the English chip for $state is the word core-files gives it, so the two vocabularies cannot drift",
                FileOperationQueue.chipLabel(source),
                fileBrowserQueueChipLabel(english, card.state),
            )
        }
    }

    /**
     * **The value-equality test above is a KNOWN HOLE, and this closes it.**
     *
     * Differential-tested 2026-08-14: replacing `FileOperationQueue.chipLabel(entry)` in
     * `FileBrowserQueue.kt` with a verbatim copy of that function's own `when` body left
     * `the chip text is core-files vocabulary` **green**. Of course it did — it compares two identical
     * strings, and it cannot see which code produced them. It still earns its place (it catches the
     * projection wiring the wrong state to the wrong label), but it does not pin the *delegation*, which
     * is the property that stops the queue card and the model drifting apart the next time `core-files`
     * re-words a chip.
     *
     * Source-level, following `app/`'s `FileBrowserStorePickSafetyTest` / `UtilityFilesRouteTest` idiom
     * for exactly this class of structural claim. **Disclosed limit:** this proves the call is written,
     * not that its result reaches the card — which is what the value test above is for. The two are
     * complementary and neither is sufficient alone.
     */
    @Test
    fun `the projection holds no chip wording of its own`() {
        val source = featureSource("FileBrowserQueue.kt")

        listOf("\"Name taken\"", "\"Undo available\"", "\"Not available\"").forEach { restated ->
            assertFalse(
                "$restated is a chip word: it lives in the string resources, chosen by state in FilesText.kt, never in this file",
                source.contains(restated),
            )
        }
        assertTrue(
            "the chip is chosen from the state on display, in one place",
            featureSource("FilesText.kt").contains("fun fileBrowserQueueChipLabel(res: Resources, state: FileQueueState): String = when (state) {"),
        )
    }

    @Test
    fun `tones follow the design's own chip classes`() {
        fun toneOf(state: FileQueueState) =
            fileBrowserQueueCards(listOf(entry(state = state))).single().tone

        assertEquals(FileBrowserQueueTone.NEUTRAL, toneOf(FileQueueState.Running(0.1f)))
        assertEquals(FileBrowserQueueTone.PENDING, toneOf(FileQueueState.AwaitingConflict))
        assertEquals(FileBrowserQueueTone.PENDING, toneOf(FileQueueState.UndoAvailable(deletion())))
        assertEquals(FileBrowserQueueTone.DONE, toneOf(FileQueueState.Done))
        assertEquals(FileBrowserQueueTone.FAILED, toneOf(FileQueueState.Failed("nope")))
        assertEquals(FileBrowserQueueTone.FAILED, toneOf(FileQueueState.Blocked("rar is not supported")))
    }

    @Test
    fun `cards keep the store's newest-first order rather than re-sorting it`() {
        val entries = FileOperationQueue.enqueue(
            FileOperationQueue.enqueue(emptyList(), entry(id = 1, title = "first", state = FileQueueState.Done)),
            entry(id = 2, title = "second", state = FileQueueState.Done),
        )

        assertEquals(listOf(2L, 1L), fileBrowserQueueCards(entries).map { it.entryId })
    }

    @Test
    fun `title and subtitle pass through untouched -- the store owns that prose`() {
        val card = fileBrowserQueueCards(
            listOf(entry(title = "3 files", subtitle = "Move → GBA", state = FileQueueState.Done)),
        ).single()

        assertEquals("3 files", card.title)
        assertEquals("Move → GBA", card.subtitle)
    }

    // ── Focus identity ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `queue keys live in their own namespace, which no tile or commit key can produce`() {
        val key = FileBrowserQueueFocus.actionKey(7, FileBrowserQueueAction.UNDO)

        assertTrue(
            "DefaultFocusAuthority.entries is keyed by FocusKey GLOBALLY -- a collision with a tile would" +
                " silently move that tile into the queue scope",
            key.value.startsWith("files/queue/"),
        )
        assertNotEquals(key, FileBrowserFocus.tileKey("/queue/7/UNDO"))
        assertNotEquals(key, FileBrowserFocus.commitKey("/queue/7/UNDO"))
    }

    @Test
    fun `a key is keyed by entry id and action, never by position -- an enqueue must not re-target focus`() {
        val undoOnSeven = FileBrowserQueueFocus.actionKey(7, FileBrowserQueueAction.UNDO)

        assertNotEquals(
            "two entries must not share a key: the queue is newest-first, so every enqueue shifts positions",
            undoOnSeven,
            FileBrowserQueueFocus.actionKey(8, FileBrowserQueueAction.UNDO),
        )
        assertNotEquals(
            "two buttons on the same card must not share a key",
            undoOnSeven,
            FileBrowserQueueFocus.actionKey(7, FileBrowserQueueAction.KEEP_DELETED),
        )
    }

    @Test
    fun `targetOf round-trips every action, so a dispatch site reads intent off the focused key`() {
        FileBrowserQueueAction.entries.forEach { action ->
            val key = FileBrowserQueueFocus.actionKey(42, action)

            assertEquals(
                FileBrowserQueueTarget(42, action),
                FileBrowserQueueFocus.targetOf(key),
            )
        }
    }

    @Test
    fun `targetOf refuses anything that is not a live queue button, rather than throwing`() {
        assertNull(FileBrowserQueueFocus.targetOf(null))
        assertNull(FileBrowserQueueFocus.targetOf(FileBrowserFocus.tileKey("/storage/emulated/0/Roms")))
        assertNull(
            "an unparseable id must degrade to 'nothing to press'",
            FileBrowserQueueFocus.targetOf(FocusKey("files/queue/notanumber/UNDO")),
        )
        assertNull(
            "an action name this build does not know must degrade too",
            FileBrowserQueueFocus.targetOf(FocusKey("files/queue/7/EXPLODE")),
        )
        assertNull(FileBrowserQueueFocus.targetOf(FocusKey("files/queue/7")))
    }

    @Test
    fun `the screen registers one key per button, and a queue of running cards registers none`() {
        val cards = fileBrowserQueueCards(
            listOf(
                entry(id = 1, state = FileQueueState.UndoAvailable(deletion())),
                entry(id = 2, state = FileQueueState.AwaitingConflict),
            ),
        )

        assertEquals(
            listOf(
                FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.UNDO),
                FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.KEEP_DELETED),
                FileBrowserQueueFocus.actionKey(2, FileBrowserQueueAction.KEEP_BOTH),
                FileBrowserQueueFocus.actionKey(2, FileBrowserQueueAction.REPLACE),
                FileBrowserQueueFocus.actionKey(2, FileBrowserQueueAction.SKIP),
            ),
            fileBrowserQueueKeys(cards),
        )
        assertEquals(
            "four running transfers offer nothing to press; a surface must not assume this list is non-empty",
            emptyList<FocusKey>(),
            fileBrowserQueueKeys(fileBrowserQueueCards(listOf(entry(state = FileQueueState.Running(0.5f))))),
        )
    }

    // ── §3c: PICK is incapable ────────────────────────────────────────────────────────────────────

    @Test
    fun `the queue screen exists only in MANAGE`() {
        assertTrue(fileBrowserQueueAvailable(FileBrowserMode.MANAGE))
        assertEquals(
            "a player adding ROMs can never reach the destructive half; PICK is incapable, not restricted",
            false,
            fileBrowserQueueAvailable(FileBrowserMode.PICK),
        )
    }

    /** Same working-directory tolerance the `app/` source-characterization tests use: Gradle runs unit
     * tests from the module directory, but a repo-root invocation is also common here. */
    private fun featureSource(fileName: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$fileName"),
        File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$fileName"),
        File("../feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$fileName"),
    ).firstOrNull(File::isFile)?.readText()
        ?: throw AssertionError("$fileName not reachable from ${File(".").absolutePath}")
}
