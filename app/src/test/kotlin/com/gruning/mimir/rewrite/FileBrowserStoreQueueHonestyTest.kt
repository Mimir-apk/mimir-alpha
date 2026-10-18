package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gruning.mimir.rewrite.core.model.jobs.JobKind

/**
 * The store's two honesty defects, pinned as pure rules.
 *
 * **What a passing run here does NOT prove:** nothing about how the Queue screen looks, and nothing about
 * `FileBrowserStore.dismiss` executing in a live session — the store is an `object` with a `Context`
 * requirement and no Robolectric harness exists in this module. The delegations are pinned at source
 * level instead, and those pins carry their own sanity asserts so they cannot go vacuous.
 */
class FileBrowserStoreQueueHonestyTest {

    private val storeSource: String by lazy { appSource("FileBrowserStore.kt").readText() }

    private fun entry(id: Long, state: FileQueueState) = FileQueueEntry(
        id = id,
        kind = JobKind.DELETE,
        title = "rom.zip",
        subtitle = "Delete",
        state = state,
    )

    private fun heldDelete() = FileQueueState.UndoAvailable(
        StagedDeletion(
            operationRootPath = "/roms/.mimir-staging/op-1",
            entries = listOf(
                StagedDeletionEntry("/roms/rom.zip", "/roms/.mimir-staging/op-1/payload/rom.zip"),
            ),
        ),
    )

    // ---- A-1: dismiss, terminal states only ----

    @Test
    fun `a done card can be dismissed`() {
        val queue = listOf(entry(1, FileQueueState.Done))
        assertEquals(
            "a finished card must be able to leave the queue -- that is the whole gap this closes",
            emptyList<FileQueueEntry>(),
            fileQueueAfterDismiss(queue, 1),
        )
    }

    @Test
    fun `a failed card can be dismissed`() {
        val queue = listOf(entry(1, FileQueueState.Failed("couldn't finish")))
        assertTrue(fileQueueAfterDismiss(queue, 1).isEmpty())
    }

    @Test
    fun `a blocked card can be dismissed`() {
        val queue = listOf(entry(1, FileQueueState.Blocked("Mimir won't open .rar")))
        assertTrue(fileQueueAfterDismiss(queue, 1).isEmpty())
    }

    /** **THE RULING.** If this ever goes green in the other direction, a delete finalises silently. */
    @Test
    fun `an undoable delete REFUSES to be dismissed, because dismissing it would finalise the delete`() {
        val queue = listOf(entry(1, heldDelete()))

        assertFalse(
            "SAFETY: UndoAvailable must NOT be dismissable. Undo is the only protection on a delete " +
                "(there is no confirm dialog). A dismiss that accepted this handle " +
                "would silently finalise a delete the player thought they were merely tidying away, and " +
                "would ALSO strand the .mimir-staging journal -- clearSession reads held deletions out " +
                "of the QUEUE itself, so a card removed this way is invisible to teardown, " +
                "ManagedFileOperations.held keeps retaining the root, and ROLLBACK resurrects the files " +
                "after the process dies. releaseUndo is the one deliberate way to finalise.",
            fileQueueDismissable(heldDelete()),
        )
        assertEquals(
            "...and the queue must come back untouched, card still present with its handle intact",
            queue,
            fileQueueAfterDismiss(queue, 1),
        )
    }

    @Test
    fun `a running operation refuses dismissal`() {
        val queue = listOf(entry(1, FileQueueState.Running(0.4f)))
        assertEquals(
            "dropping a card mid-transfer would hide work that is still writing to the player's card",
            queue,
            fileQueueAfterDismiss(queue, 1),
        )
    }

    @Test
    fun `an unanswered conflict refuses dismissal`() {
        val queue = listOf(entry(1, FileQueueState.AwaitingConflict))
        assertEquals(
            "dismissing an unanswered conflict strands its pendingTransfers entry and leaves the " +
                "player no way to answer Keep both / Replace / Skip",
            queue,
            fileQueueAfterDismiss(queue, 1),
        )
    }

    @Test
    fun `dismissing an unknown id changes nothing and drops no neighbour`() {
        val queue = listOf(entry(1, FileQueueState.Done), entry(2, heldDelete()))
        assertEquals(queue, fileQueueAfterDismiss(queue, 99))
    }

    @Test
    fun `dismiss removes only its own card`() {
        val queue = listOf(
            entry(1, FileQueueState.Done),
            entry(2, heldDelete()),
            entry(3, FileQueueState.Done),
        )
        assertEquals(listOf(2L, 3L), fileQueueAfterDismiss(queue, 1).map { it.id })
    }

    @Test
    fun `the store's dismiss delegates to the rule rather than re-deciding it`() {
        assertTrue(
            "sanity: dismiss must exist, or every pin above is green over a feature nothing can call",
            storeSource.contains("fun dismiss(entryId: Long)"),
        )
        val body = storeSource.substringAfter("fun dismiss(entryId: Long)").substringBefore("\n    }")
        assertTrue(
            "dismiss must route through fileQueueAfterDismiss -- a second copy of the rule at the call " +
                "site is how the UndoAvailable refusal gets lost. Body was:\n$body",
            body.contains("fileQueueAfterDismiss("),
        )
        assertFalse(
            "SAFETY: dismiss must never settle a delete. It removes a CARD; finalizeDelete/undoDelete " +
                "free or restore the player's bytes and belong to releaseUndo/undo alone.",
            body.contains("finalizeDelete") || body.contains("undoDelete"),
        )
    }

    // ---- A-2: the delete subtitle ----

    @Test
    fun `deleting plain files keeps the saves claim, because that delete moves nothing else`() {
        assertEquals(
            "Delete · saves and states kept",
            fileDeleteSubtitle(listOf("/roms/a.zip", "/roms/b.zip")) { true },
        )
    }

    @Test
    fun `ONE directory in the list withdraws the saves claim for the whole delete`() {
        assertEquals(
            "stageDelete atomicMoves a directory with its whole subtree. A player deleting their " +
                "`saves` folder must not be told their saves were kept -- and one directory among ten " +
                "files is enough, because the claim is about the delete, not about the majority of it.",
            "Delete",
            fileDeleteSubtitle(listOf("/roms/a.zip", "/roms/saves")) { it.endsWith(".zip") },
        )
    }

    @Test
    fun `a path that cannot be confirmed as a plain file gets no claim`() {
        assertEquals(
            "isFile answers false for a path that no longer exists as well as for a directory. " +
                "Unverifiable must fall to the quieter line, never to the benefit of the doubt.",
            "Delete",
            fileDeleteSubtitle(listOf("/roms/vanished.zip")) { false },
        )
    }

    @Test
    fun `an empty path list makes no claim`() {
        assertEquals(
            "`all {}` is vacuously true, so an unguarded empty list would make the strongest claim in " +
                "the file about deleting nothing at all",
            "Delete",
            fileDeleteSubtitle(emptyList()) { true },
        )
    }

    @Test
    fun `the saves claim exists in exactly ONE place, and the delete card goes through it`() {
        val occurrences = Regex(Regex.escape("\"Delete · saves and states kept\""))
            .findAll(storeSource).count()
        assertEquals(
            "HONESTY: the saves claim must be spelled exactly once, inside fileDeleteSubtitle. A second " +
                "occurrence means someone restored it at a call site that cannot know whether it is " +
                "true -- which is the exact defect A-2 fixed.",
            1,
            occurrences,
        )

        assertTrue(
            "sanity: deleteInManager must exist",
            storeSource.contains("fun deleteInManager("),
        )
        val enqueueBlock = storeSource.substringAfter("fun deleteInManager(").substringBefore("scope.launch")
        assertTrue(
            "the DELETE card's subtitle must be computed, not literal. Block was:\n$enqueueBlock",
            enqueueBlock.contains("subtitle = fileDeleteSubtitle(paths)"),
        )
        assertTrue(
            "...and the predicate must be a real filesystem answer, not a constant",
            enqueueBlock.contains("File(it).isFile"),
        )
    }

    // ---- F-7: delete's progress bar is a real fraction, never the false zero it shipped with ----

    @Test
    fun `deleteInManager enqueues an honest null, never the false zero F-7 found`() {
        val body = deleteInManagerBody()
        assertFalse(
            "F-7: `Running(0f)` at enqueue asserts a measurement that has not started -- the exact " +
                "defect F-6 already fixed for move/copy (FileQueueState.Running's own KDoc). Body was:" +
                "\n$body",
            body.contains("Running(0f)"),
        )
        assertTrue(
            "the enqueue must use the same null-means-indeterminate vocabulary transfer() already uses",
            body.substringBefore("scope.launch").contains("Running(fraction = null)"),
        )
    }

    @Test
    fun `deleteInManager passes a real callback to stageDelete, not the silent default`() {
        val body = deleteInManagerBody()
        assertFalse(
            "SAFETY/F-7: a bare `stageDelete(paths).fold(` keeps stageDelete's default no-op callback " +
                "-- the exact shape that shipped the bar frozen at zero for the whole operation. Body " +
                "was:\n$body",
            body.contains("stageDelete(paths).fold("),
        )
        assertTrue(
            "deleteInManager must supply its own onProgress lambda to stageDelete",
            body.contains("stageDelete(everything) {"),
        )
        assertTrue(
            "the callback must actually publish the fraction to the queue's state -- a lambda that " +
                "computes `staged.toFloat() / total` and never calls setState with it would leave every " +
                "pin above green over a dead expression. `postProgress(id, FileQueueState.Running(` is " +
                "unambiguous here: the success/failure arms below settle with UndoAvailable/Failed, " +
                "never with Running, so this substring can only come from the progress callback. It is " +
                "POSTED (to Main) rather than written with setState from stageDelete's IO thread -- #237.",
            body.contains("postProgress(id, FileQueueState.Running("),
        )
    }

    @Test
    fun `deleteInManager's callback derives a real fraction, and the CODE never spells a literal 0f`() {
        val code = stripLineComments(deleteInManagerBody())
        assertFalse(
            "F-7: no literal `0f` token in this function's CODE (comments may still name the bug for " +
                "the record) -- that placeholder claims a measurement of zero rather than an absence of " +
                "one, which is the whole defect. A fallback branch re-adding it would be the same lie in " +
                "a new spot. Code was:\n$code",
            Regex("""\b0[fF]\b""").containsMatchIn(code),
        )
        assertTrue(
            "the callback must compute completed/total, not a hand-picked constant",
            code.contains("staged.toFloat() / total"),
        )
    }

    // ── extraction, the third and last false zero in this store ──────────────────────────────────────

    @Test
    fun `no operation in this store enqueues or falls back to a fabricated zero`() {
        val body = stripLineComments(appSource("FileBrowserStore.kt").readText())

        assertFalse(
            "**`Running(0f)` is the sentence this whole field exists to stop.** Zero is a MEASUREMENT " +
                "of no progress; absence is the honest answer when there is nothing to measure against. " +
                "Three operations claimed the former: move/copy (F-6), delete (F-7, frozen there for the " +
                "whole operation because its callback was never even passed), and extraction -- which " +
                "enqueued 0f AND fell back to 0f whenever the engine had no total. This asserts over the " +
                "WHOLE file rather than one function, so a fourth operation cannot reintroduce it " +
                "somewhere this suite is not looking.",
            Regex("""Running\(\s*0f\s*\)""").containsMatchIn(body) ||
                Regex("""Running\(fraction\s*=\s*0f\s*\)""").containsMatchIn(body),
        )
        assertFalse(
            "and no progress lambda may fall back to a zero fraction either -- `else 0f` is the same " +
                "claim made one layer down, where it is harder to see",
            Regex("""else\s+0f""").containsMatchIn(body),
        )
    }

    @Test
    fun `extraction reports a real fraction when it has one, and nothing when it does not`() {
        // **Anchored on dispatchExtraction, not on the first `onProgress` in the file.** The first
        // version of this test opened its window at `substringAfter("val onProgress: (Long, Long,
        // String) -> Unit")` — which lands on the TRANSFER's lambda, several hundred lines earlier —
        // so it read F-6's `else null` and passed while extraction's `0f` was fully restored. Caught by
        // the differential, which is the only thing that could have caught it: it was green either way.
        val body = stripLineComments(appSource("FileBrowserStore.kt").readText())
            .substringAfter("private fun dispatchExtraction(")
            .substringBefore("\n    private fun ")

        // E2 O6 (2026-10-08): the fraction itself moved into FileBrowserExtractProgress.kt, because the
        // card's bar now spans TWO phases (unpacking, then placing). This function must still hand every
        // progress call to it, and the no-total fallback must still be an absence, not a zero.
        assertTrue(
            "dispatchExtraction must route the engine's byte progress through extractUnpackingState",
            body.contains("extractUnpackingState(done, total)"),
        )
        val progress = stripLineComments(appSource("FileBrowserExtractProgress.kt").readText())
        assertTrue(
            "the live path is untouched -- extraction genuinely streams and its bar was never the lie",
            progress.contains("done.toFloat() / total"),
        )
        assertTrue(
            "only the no-total fallback changed, from a fabricated zero to an honest absence. **Asserted " +
                "as the WHOLE expression, not as `contains(\"else null\")`** -- that substring appears " +
                "elsewhere, so the looser version passed with the fabricated zero fully restored.",
            progress.contains("if (total > 0) UNPACKING_SHARE * (done.toFloat() / total).coerceIn(0f, 1f) else null"),
        )
    }

    private fun deleteInManagerBody(): String {
        assertTrue(
            "sanity: deleteInManager must exist",
            storeSource.contains("fun deleteInManager("),
        )
        return storeSource.substringAfter("fun deleteInManager(").substringBefore("\n    fun undo(")
    }

    /** Drops `//` line comments so a source-text pin can check CODE without tripping on prose. */
    private fun stripLineComments(text: String): String =
        text.lineSequence().joinToString("\n") { line -> line.substringBefore("//") }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")

    // ──────────────────── macro shortcuts stop at the browser ──

    /**
     * Controller shortcuts and macros must remain unavailable in both file-browser modes.
     *
     * A source pin, and weak as evidence — said out loud rather than implied. What it actually protects is
     * the SHAPE of the guard, because the behaviour needs an Activity and a real controller bus:
     *
     *  - the check exists at the one place a shortcut becomes an M-button command, and
     *  - the guarded branch still answers `true`, i.e. **consumed**. Answering `false` would hand the key
     *    to whatever sits behind the browser, which is the fall-through the guard exists to stop.
     *
     * The reason a source pin is the honest tier here: a controller shortcut never reaches the browser's
     * dispatch table at all. It resolves in `InputRouter` and publishes straight to the command bus, so
     * the modal that swallows every unbound action was never consulted — the modal was airtight and the
     * macro walked around it.
     */
    @Test
    fun `a controller shortcut is swallowed while the file browser owns the screen`() {
        val body = appSource("MimirAppCoordinator.kt").readText()
            .substringAfter("inputRouter.onControllerShortcut = { shortcut ->")
            .substringBefore("inputRouter.navMoveProbe")

        assertTrue(
            "the guard must read the browser's own ownership signal, not re-derive one",
            body.contains("FileBrowserStore.screenOwned"),
        )
        assertTrue(
            "publishing must be INSIDE the negated guard -- an unguarded publish beside a guarded one " +
                "is how this defect returns",
            body.contains("if (!FileBrowserStore.screenOwned) {") &&
                body.indexOf("MButtonNavigationCommands.publish") > body.indexOf("if (!FileBrowserStore.screenOwned) {"),
        )
        assertTrue(
            "and the press stays CONSUMED -- returning false would hand the key to whatever is behind " +
                "the browser, which is the fall-through this stops",
            body.trimEnd().endsWith("true\n        }") || body.contains("\n            true\n"),
        )
    }

}
