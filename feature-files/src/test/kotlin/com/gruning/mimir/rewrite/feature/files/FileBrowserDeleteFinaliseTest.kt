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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.gruning.mimir.rewrite.core.model.jobs.JobKind

/**
 * **W-1 — the press that ends reversibility, and the word on it.**
 *
 * The defect, verified hop by hop at HEAD before this file existed: B at the drive root produced
 * `FileBrowserCommand.Close` (`FileBrowserDispatch.kt`), the footer word for that press was literally
 * `CLOSE` (`FileBrowserFooterHints.kt`), `Close` called `onDismiss()` (`FileBrowserController.kt`),
 * `onDismiss` was `clearSession()` (`FileBrowserStore.kt:627`), and `clearSession` ran
 * `held.forEach { ops.finalizeDelete(it) }` (`FileBrowserStore.kt:885-890`). So one press on a button
 * labelled `CLOSE` destroyed, unrecoverably, every delete the player was still holding an Undo for — and
 * nothing said so before, during or after.
 *
 * Pressing CLOSE with held deletions opens QUEUE instead of closing, so the player sees what is about to be finalised
 * and closes from there. One extra press, only when something is genuinely pending. **No confirm
 * dialog** —  the redirect replaces a dialog rather than
 * reintroducing one.
 *
 * Every assertion below is one of the four things this safety contract requires, and the file is ordered by them:
 *  1. **nothing changes when nothing is held** — this is the pin the request specified for by name;
 *  2. the redirect happens when something IS held, and finalises nothing on the way;
 *  3. **closing still works from the queue and still finalises** — no room the player cannot leave;
 *  4. the legend never says a word the press does not perform, in either screen.
 *
 * Driven for real wherever the failure is a live state (a real `DefaultFocusAuthority`, a real
 * [FileBrowserController], real dispatches). The two surface pins at the end are source pins because this
 * module configures no Compose harness; they prove the call site names the right values and nothing about
 * pixels. Hardware is still the gate for how any of this looks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserDeleteFinaliseTest {
    private val english = filesEnglish()

    // ────────────────────────────────────────────────────────────────────────────── Fixtures ──

    private fun tree(): Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "game.gba", path = "/root/game.gba", extension = "gba"),
        ),
        "/root/Games" to listOf(
            FileItem(name = "a.gba", path = "/root/Games/a.gba", extension = "gba"),
            FileItem(name = "b.gba", path = "/root/Games/b.gba", extension = "gba"),
        ),
    )

    private fun deletion(id: Long = 1) = StagedDeletion(
        operationRootPath = "/tmp/.mimir-staging/.delete-$id",
        entries = listOf(StagedDeletionEntry("/root/game$id.gba", "/tmp/.mimir-staging/.delete-$id/game$id.gba")),
    )

    /** One card holding a live Undo — i.e. one delete that `clearSession` would finalise. */
    private fun held(id: Long = 1) = FileQueueEntry(
        id = id,
        kind = JobKind.DELETE,
        title = "game$id.gba",
        subtitle = "Delete",
        state = FileQueueState.UndoAvailable(deletion(id)),
    )

    private fun entry(id: Long, state: FileQueueState) = FileQueueEntry(
        id = id,
        kind = JobKind.DELETE,
        title = "game$id.gba",
        subtitle = "Delete",
        state = state,
    )

    /** A MANAGE browser standing at the drive ROOT with the delete and queue sinks both wired. */
    private fun wiredAtRoot(
        authority: DefaultFocusAuthority,
        dismissals: MutableList<Unit> = mutableListOf(),
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
    ): FileBrowserController {
        val controller = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = mode,
            listDirectory = { path -> tree()[path].orEmpty() },
            onChoose = {},
            onDismiss = { dismissals.add(Unit) },
            onDelete = {},
        )
        controller.setQueueAction { }
        controller.open()
        return controller
    }

    // ──────────────────────────────────── 1 · nothing changes when no deletion is held ──

    /**
     * **Only pending deletes add a press.** An empty queue must leave B at the root byte-identically what it was — one press,
     * out, no screen in between. If this ever fails, the fix has taxed every exit from the file manager
     * to mark a moment that is not happening.
     */
    @Test
    fun `with nothing held, B at the root still closes in one press`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals)
        controller.publishQueue(emptyList())

        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertFalse(
            "an empty queue must not put a screen between the player and the exit -- the redirect is " +
                "priced on being rare, and a queue with nothing held is the common case",
            controller.queueOpen,
        )
        assertEquals("one press, one dismiss", 1, dismissals.size)
    }

    /** The same, with a queue that is not empty but holds no UNDO — a finished delete finalises nothing,
     * so there is nothing to review and nothing to mark. This is the case a `queueEntries.isNotEmpty()`
     * shortcut would get wrong, which is why the count comes from `FileOperationQueue.heldDeletions`. */
    @Test
    fun `a queue with no held Undo does not redirect, however full it is`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals)
        controller.publishQueue(
            listOf(
                entry(1, FileQueueState.Done),
                entry(2, FileQueueState.Running(0.4f)),
                entry(3, FileQueueState.Failed("no space")),
            ),
        )

        assertEquals("precondition: three cards, nothing reversible", 0, controller.heldDeleteCount)
        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertFalse("none of these three states can be finalised, so none of them earns a review", controller.queueOpen)
        assertEquals(1, dismissals.size)
    }

    /** Below the root the press was never the finalising one -- it walks Up, and W-1 does not touch it. */
    @Test
    fun `below the root, B still walks up with deletes held`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals)
        controller.publishQueue(listOf(held()))
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Games"))
        controller.dispatch(ControllerAction.CONFIRM)
        assertEquals("precondition: one level down", "/root/Games", controller.currentPath)

        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertEquals("B below the root is Up, exactly as before", "/root", controller.currentPath)
        assertFalse("and it opens nothing", controller.queueOpen)
        assertTrue("and finalises nothing", dismissals.isEmpty())
    }

    /**
     * **PICK stays incapable by construction, with no mode test in the redirect.** `queueAvailable` is
     * MANAGE-only, so there is no value of any other argument that reaches the redirect from PICK — and
     * a PICK session's `FileBrowserStore.operations` is null, so it cannot hold a deletion in the first
     * place. Driven with a queue force-published anyway, which is the state PICK can never actually
     * reach: even then the exit is the plain close it has always been.
     */
    @Test
    fun `PICK cannot be redirected -- its B at the root closes as it always did`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals, mode = FileBrowserMode.PICK)
        controller.publishQueue(listOf(held(), held(2)))

        assertFalse("precondition: MANAGE-only, by mode", controller.queueAvailable)
        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertFalse("no press in PICK may open the queue screen, this one included", controller.queueOpen)
        assertEquals("and the exit is unchanged", 1, dismissals.size)
    }

    // ─────────────────────────────────────────── 2 · the redirect, and what it does NOT do ──

    /**
     * **The finding, inverted into a pin.** The press that used to finalise everything now shows the
     * player what it was about to finalise. The second assertion is the one that matters most: `onDismiss`
     * -- which is `FileBrowserStore.clearSession`, which is `finalizeDelete` -- must not have run.
     */
    @Test
    fun `with a delete held, B at the root opens the queue and finalises nothing`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals)
        controller.publishQueue(listOf(held()))

        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertTrue("the press must land on the queue, not outside the browser", controller.queueOpen)
        assertEquals(FileBrowserQueuePurpose.EXIT_REVIEW, controller.queuePurpose)
        assertTrue(
            "onDismiss is clearSession is finalizeDelete. If this ever fires here, the review screen is " +
                "being shown AFTER the files are already gone -- which is worse than the defect W-1 found",
            dismissals.isEmpty(),
        )
        assertEquals(
            "and the queue's own scope must own the D-pad, or the ring is driving the listing behind a " +
                "screen the player is reading",
            controller.queueScope,
            authority.state.value.scope,
        )
        assertEquals(
            "focus lands on the newest card's first button -- the Undo for the delete he is being shown",
            FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.UNDO),
            authority.state.value.focused,
        )
    }

    // ────────────────────────────────── 3 · closing from the queue still works, and finalises ──

    /**
     * **Two presses out, and the second one is the deliberate act.** The policy ends "and closes
     * from there" — so B on the review screen must leave the BROWSER, not just the screen. A B that
     * returned to the listing here would be a room the player cannot leave: the next root B would redirect
     * him straight back.
     */
    @Test
    fun `B on the review screen closes the browser for real, and only then finalises`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals)
        controller.publishQueue(listOf(held()))

        assertTrue(controller.dispatch(ControllerAction.CANCEL)) // redirected to the review
        assertTrue(controller.dispatch(ControllerAction.CANCEL)) // and out

        assertEquals("exactly one dismiss for the whole exit, on the second press", 1, dismissals.size)
        assertFalse("the screen is gone", controller.queueOpen)
        assertNotEquals(
            "the queue scope must be unwound, or the next surface mounts underneath a buried scope",
            controller.queueScope,
            authority.state.value.scope,
        )
        assertNotEquals(
            "and so must the browse scope -- close() unwinds both",
            controller.scope,
            authority.state.value.scope,
        )
        assertEquals(FocusScope.Root, authority.state.value.scope)
    }

    /**
     * **The escape hatch, and the re-arm.** A player redirected on his way out who changes his mind can
     * still get back to his listing: START closes the screen it always closed, finalising nothing. And the
     * posture must die with the screen — if `closeQueue` ever leaks it, the SECOND exit skips its review
     * silently, which is the original defect with an extra step.
     */
    @Test
    fun `START leaves the review for the listing, finalises nothing, and the redirect re-arms`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals)
        controller.publishQueue(listOf(held()))
        controller.dispatch(ControllerAction.CANCEL)
        assertEquals("precondition: on the review", FileBrowserQueuePurpose.EXIT_REVIEW, controller.queuePurpose)

        assertTrue(controller.dispatch(ControllerAction.SYSTEM_START))

        assertFalse("back on the listing", controller.queueOpen)
        assertEquals(
            "the posture must not outlive the screen -- a leaked EXIT_REVIEW makes the NEXT visit's B " +
                "close the browser out of nowhere",
            null,
            controller.queuePurpose,
        )
        assertTrue("nothing was finalised on the way out of the review", dismissals.isEmpty())
        assertEquals(controller.scope, authority.state.value.scope)

        // And the exit is still guarded the second time.
        assertTrue(controller.dispatch(ControllerAction.CANCEL))
        assertEquals(
            "the redirect must re-arm from the queue's live contents, not fire once per session",
            FileBrowserQueuePurpose.EXIT_REVIEW,
            controller.queuePurpose,
        )
        assertTrue(dismissals.isEmpty())
    }

    /**
     * **A START VISIT's B must still be a harmless back press.** This is the hazard that decided the
     * design: had "B closes the browser" been derived from the queue's CONTENTS rather than from how the
     * screen was ENTERED, a player who pressed START to glance at his queue and B to go back would have
     * destroyed every held delete instead. The posture is carried for exactly this test.
     */
    @Test
    fun `a START visit's B returns to the listing even with deletes held`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals)
        controller.publishQueue(listOf(held(), held(2)))

        assertTrue(controller.dispatch(ControllerAction.SYSTEM_START))
        assertEquals(FileBrowserQueuePurpose.VISIT, controller.queuePurpose)

        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertFalse(controller.queueOpen)
        assertTrue(
            "a glance at the queue must never be able to finalise anything -- two files would have died " +
                "here for a press that means 'go back'",
            dismissals.isEmpty(),
        )
        assertEquals("and it lands back on the listing", controller.scope, authority.state.value.scope)
    }

    /**
     * Undo everything from inside the review and the exit is still one press, with nothing left to
     * finalise. The player must never be trapped by having emptied the queue he was sent to look at.
     */
    @Test
    fun `undoing everything inside the review still leaves by the same press`() {
        val authority = DefaultFocusAuthority()
        val dismissals = mutableListOf<Unit>()
        val controller = wiredAtRoot(authority, dismissals)
        controller.publishQueue(listOf(held()))
        controller.dispatch(ControllerAction.CANCEL)

        // What FileBrowserStore.undo publishes back: the card is gone, the files are restored.
        controller.publishQueue(emptyList())
        assertEquals(0, controller.heldDeleteCount)
        assertTrue("still on the review screen", controller.queueOpen)

        assertTrue(controller.dispatch(ControllerAction.CANCEL))

        assertFalse(controller.queueOpen)
        assertEquals(
            "B still leaves the browser. There is simply nothing held for " +
                "clearSession to finalise now",
            1,
            dismissals.size,
        )
    }

    /** The count is the store's own, not a local recount: `FileOperationQueue.heldDeletions` is the exact
     * list `FileBrowserStore.clearSession` walks to `finalizeDelete`. A second opinion here is how the
     * word and the consequence drift apart. */
    @Test
    fun `the held count is FileOperationQueue's, and only UndoAvailable counts`() {
        val authority = DefaultFocusAuthority()
        val controller = wiredAtRoot(authority)

        controller.publishQueue(listOf(held(1), entry(2, FileQueueState.Done), held(3)))

        assertEquals("two undoable deletes among three cards", 2, controller.heldDeleteCount)
    }

    // ──────────────────────────────────── 4 · the legend cannot disagree with the press ──

    /**
     * **The paired test: the word and the branch are one condition, not two that agree today.**
     * `fileBrowserFooterHints` and `fileBrowserCommand` both call [fileBrowserExitOpensQueue]; this walks
     * the whole state grid and asserts the legend says REVIEW **exactly** when the dispatch table answers
     * [FileBrowserCommand.ReviewHeldDeletes]. Re-point either consumer at its own copy of the terms and
     * this names the disagreement.
     */
    @Test
    fun `the B legend says REVIEW exactly when the B press reviews`() {
        val picks = listOf(null) + FileBrowserDestinationPickPhase.entries
        for (atRoot in listOf(true, false)) {
            for (pick in picks) {
                for (queueAvailable in listOf(true, false)) {
                    for (count in listOf(0, 1, 3)) {
                        val command = fileBrowserCommand(
                            action = ControllerAction.CANCEL,
                            focused = null,
                            atRoot = atRoot,
                            queueAvailable = queueAvailable,
                            destinationPick = pick,
                            heldDeleteCount = count,
                        )
                        val word = fileBrowserFooterHints(english,
                            focused = null,
                            atRoot = atRoot,
                            queueAvailable = queueAvailable,
                            destinationPick = pick,
                            heldDeleteCount = count,
                        ).first { it.first == "B" }.second
                        val state = "atRoot=$atRoot pick=$pick queue=$queueAvailable held=$count"

                        assertEquals(
                            "the legend and the dispatch table must answer the same question. $state -> " +
                                "command=$command, word=$word",
                            command == FileBrowserCommand.ReviewHeldDeletes,
                            word.startsWith("Review"),
                        )
                        assertEquals(
                            "and CLOSE must be drawn only over a press that actually closes. $state -> " +
                                "command=$command, word=$word",
                            command == FileBrowserCommand.Close,
                            word == "Close",
                        )
                    }
                }
            }
        }
    }

    /** The words themselves, including the singular. A count in the legend that disagrees with the number
     * of cards on the screen it sends him to would be its own small lie. */
    @Test
    fun `the browse legend names the destination and the stake`() {
        fun bWord(count: Int, queueAvailable: Boolean = true, atRoot: Boolean = true) =
            fileBrowserFooterHints(english,
                focused = null,
                atRoot = atRoot,
                queueAvailable = queueAvailable,
                heldDeleteCount = count,
            ).first { it.first == "B" }.second

        assertEquals("Review 3 deletes", bWord(3))
        assertEquals("a single held delete must not read '1 deletes'", "Review 1 delete", bWord(1))
        assertEquals(
            "with nothing held the word is the one that has always been there -- W-1 changes the press " +
                "and the word together, or neither",
            "Close",
            bWord(0),
        )
        assertEquals("PICK never sees the new word", "Close", bWord(3, queueAvailable = false))
        assertEquals("and below the root B is still Back", "Back", bWord(3, atRoot = false))
    }

    /** Every pre-W-1 caller's legend is byte-identical: the new parameter defaults to the value that
     * reproduces the old table, which is this file's established polarity for a new gate. */
    @Test
    fun `a caller that passes no count gets exactly the pre-W-1 legend`() {
        assertEquals(
            listOf("START" to "Queue", "B" to "Close"),
            fileBrowserFooterHints(english, focused = null, atRoot = true, queueAvailable = true),
        )
        assertEquals(listOf("B" to "Back"), fileBrowserQueueFooterHints(english, focusedAction = null))
    }

    /**
     * **The queue screen's own B, which is now the finalising press.** `BACK` over it would be the same
     * defect one screen along: the word must say it closes, and say how much that makes permanent.
     */
    @Test
    fun `the review screen's B says CLOSE and how much becomes final`() {
        fun bWord(exitReview: Boolean, count: Int) =
            fileBrowserQueueFooterHints(english,
                focusedAction = FileBrowserQueueAction.UNDO,
                exitReview = exitReview,
                heldDeleteCount = count,
            ).first { it.first == "B" }.second

        assertEquals("Close · 3 deletes final", bWord(exitReview = true, count = 3))
        assertEquals("Close · 1 delete final", bWord(exitReview = true, count = 1))
        assertEquals(
            "undo the last one and nothing becomes final, so nothing may be claimed",
            "Close",
            bWord(exitReview = true, count = 0),
        )
        assertEquals(
            "a START visit's B is still a back press and must still say so, held deletes or not",
            "Back",
            bWord(exitReview = false, count = 3),
        )
    }

    /** A's word is untouched by W-1 -- it is still the focused button's own label. */
    @Test
    fun `the review screen still names the focused button, whatever B says`() {
        assertEquals(
            listOf("A" to "Keep deleted", "B" to "Close · 2 deletes final"),
            fileBrowserQueueFooterHints(english,
                focusedAction = FileBrowserQueueAction.KEEP_DELETED,
                exitReview = true,
                heldDeleteCount = 2,
            ),
        )
    }

    // ────────────────────────────────────────────────── 5 · the surface threads both values ──

    /**
     * Source pins, and the two failure modes they are written against: a **vacuous slice** (an anchor pair
     * that matches an empty region and passes trivially) and a **comment-blind** match (a KDoc mentioning
     * the code the pin is looking for). [callArguments] walks the call's own parentheses rather than
     * slicing between two loose anchors, and asserts the region is non-blank; the source is stripped of
     * every comment first, so no prose can satisfy either pin.
     */
    @Test
    fun `the browse footer is given the held count, or B lies again`() {
        val args = callArguments(surfaceSource(), "fileBrowserFooterHints(")
        assertTrue(
            "without this the legend cannot know the press was redirected, and goes back to saying " +
                "CLOSE over the press that opens the queue. Found: <$args>",
            args.contains("heldDeleteCount = controller.heldDeleteCount"),
        )
    }

    @Test
    fun `the review screen's legend is given the posture and the count`() {
        val args = callArguments(surfaceSource(), "fileBrowserQueueFooterHints(")
        assertTrue(
            "without the posture the queue's B says BACK over the press that closes the browser and " +
                "finalises every held delete. Found: <$args>",
            args.contains("exitReview = controller.queuePurpose == FileBrowserQueuePurpose.EXIT_REVIEW"),
        )
        assertTrue(
            "and without the count it cannot say how much becomes final. Found: <$args>",
            args.contains("heldDeleteCount = controller.heldDeleteCount"),
        )
    }

    // ───────────────────────────────────────────────────────────────────────────── Helpers ──

    /**
     * The text between a call's own parentheses, matched by depth rather than by a second anchor string.
     *
     * A `substringAfter(a).substringBefore(b)` pin passes vacuously when `b` follows `a` immediately and
     * silently over-reads when `b` precedes `a`; this cannot do either, and asserts the region is
     * non-blank so an empty argument list can never satisfy a `contains` check.
     */
    private fun callArguments(source: String, callee: String): String {
        val start = source.indexOf(callee)
        assertTrue("call site not found in the stripped source: $callee", start >= 0)
        assertEquals(
            "the pin assumes ONE call site; $callee appears more than once and the wrong one may be read",
            start,
            source.lastIndexOf(callee),
        )
        var depth = 0
        var index = start + callee.length - 1
        val open = index
        while (index < source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        val args = source.substring(open + 1, index)
                        assertTrue("empty argument list for $callee -- a vacuous pin", args.isNotBlank())
                        return args
                    }
                }
            }
            index += 1
        }
        error("unbalanced parentheses after $callee")
    }

    /** `FileBrowserSurface.kt` with every comment removed, so no KDoc or `//` line can satisfy a pin. */
    private fun surfaceSource(): String = stripComments(moduleSource("FileBrowserSurface.kt"))

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2)
                    index = if (end < 0) source.length else end + 2
                }
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index)
                    index = if (end < 0) source.length else end
                }
                else -> {
                    out.append(source[index])
                    index += 1
                }
            }
        }
        return out.toString()
    }

    private fun moduleSource(name: String): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
