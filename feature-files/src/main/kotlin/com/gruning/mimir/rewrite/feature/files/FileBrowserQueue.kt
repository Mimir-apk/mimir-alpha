package com.gruning.mimir.rewrite.feature.files

import androidx.annotation.StringRes
import com.gruning.mimir.rewrite.core.files.FileConflictChoice
import com.gruning.mimir.rewrite.core.files.FileOperationQueue
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion

/**
 * F-11's decision layer for the **Queue screen** (adoption spec §6) —
 *
 * **Written when the engine was finished, pinned, and completely unreachable.** `FileBrowserStore.queue`,
 * `deleteInManager`, `moveInManager`, `copyInManager`, `undo`, `releaseUndo` and `resolveConflict` all
 * existed in `app/`, were covered by `FileOperationQueueTest` / `ManagedFileOperationsTest` /
 * `FileBrowserStorePickSafetyTest`, and had **zero production callers** — *unreachable*, not *done*. This
 * file was the half of that gap closable without touching a file another lane held: every rule the Queue
 * screen has to obey, decided as a pure function a plain JUnit test can drive.
 *
 * **the earlier change gives it the door.** `SYSTEM_START` opens the screen and `SYSTEM_SELECT` deletes, both routed
 * through [FileBrowserController] (see [FileBrowserCommand.OpenQueue] / [FileBrowserCommand.Delete]); the
 * screen itself is `FileBrowserQueueSurface`. So the "no production caller" sentence this paragraph used
 * to end on is retired rather than left standing — what remains true is the *reason* the decisions live
 * here as pure functions, which is the sentence above.
 *
 * **It re-decides nothing `core-files` already decided.** [FileOperationQueue.chipLabel] owns the chip
 * vocabulary, [FileOperationQueue.summary] owns the header line (including its no-network claim), and
 * [FileConflictChoice] owns the three conflict labels. This file adds only what a *surface* must know
 * and the model does not: which buttons a card carries in each state, whether a progress bar is drawn
 * at all, and which focus key each button holds.
 *
 * **The action set is derived from the state, which is what keeps Undo honest.**
 * [FileQueueState.UndoAvailable] carries the live `StagedDeletion` handle (see its own KDoc), so
 * [FileBrowserQueueAction.UNDO] is offered if and only if the means to honour it is in hand. There is no
 * path here that can draw an Undo button over an entry that cannot be reversed, because the button is
 * not a flag on the card — it is a consequence of the state's own type.
 *
 * **No action is invented for a store entry point that does not exist.** One consequence remains, and it
 * is deliberate:
 *  - **A running delete carries no buttons** (it is a move into staging and is over before a press could
 *    land). A running copy, move or extract carries **Cancel** : the engine now
 *    stops at the next safe point and rolls back what this job placed, so the button is real, not chrome.
 *
 * **The terminal-state dismiss gap is CLOSED as of the earlier change, and the paragraph that used to disclose it
 * has been deleted rather than left standing.** Until this change, `FileOperationQueue.remove` was
 * reachable only from `FileBrowserStore.undo` and `releaseUndo` — both of which require a
 * [FileQueueState.UndoAvailable] handle — so a `Done`/`Failed`/`Blocked` card had no player-facing way to
 * leave the queue and accumulated until the session ended. `FileBrowserStore.dismiss(entryId)` (the earlier change
 * lane A) is that entry point, and [FileBrowserQueueAction.DISMISS] is the button that reaches it. The
 * old paragraph argued at length that drawing a Dismiss button would be dead chrome; that argument is now
 * false, and a confidently-worded stale justification is worse than no comment at all — so it is gone,
 * not amended in place.
 *
 * **`dismiss` is deliberately narrower than the button set implies, and the narrowness preserves Undo.**
 * The store no-ops `dismiss` for [FileQueueState.UndoAvailable] as well as for `Running`/`AwaitingConflict`.
 * This file matches that by never offering [FileBrowserQueueAction.DISMISS] on an undoable card: Undo is
 * the only protection a delete has, so a button that quietly discarded it while reading as "tidy this
 * away" would finalise a deletion the player never agreed to. An undoable card settles through
 * [FileBrowserQueueAction.UNDO] or [FileBrowserQueueAction.KEEP_DELETED], both of which say what they do
 * to the FILES.
 */
internal object FileBrowserQueueFocus {

    /**
     * The Queue screen's own [FocusScope], not a second set of keys inside the browse scope.
     *
     * Same mechanical reason [FileBrowserSearchScope] exists, and it is worth restating because getting
     * it wrong is silent: `DefaultFocusAuthority.entries` is keyed by [FocusKey] **globally**, with the
     * scope only a field of the entry, so any key this screen shares with the folio behind it would
     * overwrite that tile's entry and leave the listing one focus slot short when the queue closed.
     * Every key below is namespaced under `files/queue/`, a segment neither [FileBrowserFocus.tileKey]
     * (`files/tile<path>`) nor [FileBrowserFocus.commitKey] can produce.
     *
     * Popping this scope restores `focusMemory` for the scope underneath, so leaving the queue returns
     * the player to the tile they were standing on with no bookkeeping in this file at all.
     */
    val Scope: FocusScope = FocusScope("files/queue")

    /**
     * One button on one card. Keyed by the entry's [FileQueueEntry.id] and the action's enum NAME —
     * never by the card's position, and never by its title.
     *
     * Position would be catastrophic here specifically: the queue is newest-first, so **every enqueue
     * shifts every existing card down one**, and an index-qualified key would silently re-target a
     * focused Undo onto a different entry's delete. Title would be nearly as bad — two deletes of one
     * file in one session share a title but are different staged payloads.
     */
    fun actionKey(entryId: Long, action: FileBrowserQueueAction): FocusKey =
        FocusKey("$ACTION_PREFIX$entryId/${action.name}")

    /**
     * What [key] presses, or null when it is not a queue button.
     *
     * The inverse of [actionKey], so a dispatch site reads the player's intent off the focused key
     * rather than keeping a parallel map that could disagree with what was registered. An unparseable
     * id or an unknown action name answers null rather than throwing: a stale key from a previous
     * listing must degrade to "nothing to press", the same way [FileBrowserSearchFocus.resultPath]
     * refuses a blank path.
     */
    fun targetOf(key: FocusKey?): FileBrowserQueueTarget? {
        val body = key?.value?.takeIf { it.startsWith(ACTION_PREFIX) }?.removePrefix(ACTION_PREFIX)
            ?: return null
        val separator = body.indexOf('/')
        if (separator <= 0) return null
        val entryId = body.substring(0, separator).toLongOrNull() ?: return null
        val actionName = body.substring(separator + 1)
        val action = FileBrowserQueueAction.entries.firstOrNull { it.name == actionName } ?: return null
        return FileBrowserQueueTarget(entryId, action)
    }

    private const val ACTION_PREFIX = "files/queue/"
}

/**
 * **Why the queue screen was opened — W-1, and what B on it therefore means.**
 *
 * The screen is the same screen either way; the press that leaves it is not, and one boolean pair
 * (`queueOpen` + `exitReview`) can represent "not open but reviewing", which is meaningless. Collapsed
 * into one nullable value on [FileBrowserController] (null IS closed) for exactly the argument
 * [FileBrowserCommitPurpose] makes: the illegal combinations become unrepresentable rather than merely
 * unwritten.
 *
 * **Deriving this from the held-delete count instead was considered and is wrong.** "B closes the browser
 * whenever deletes are held" would finalise every held delete the moment a player who pressed START to
 * *look* at the queue pressed B to go back to the listing — turning a glance into a destruction. What
 * decides is how the screen was ENTERED, never what the queue happens to contain.
 */
internal enum class FileBrowserQueuePurpose {
    /**
     * `SYSTEM_START`, or the header plate: the player came to look. **B returns to the listing**, exactly
     * as it did before W-1, and nothing is finalised. This is the posture every pre-W-1 caller gets.
     */
    VISIT,

    /**
     * [FileBrowserCommand.ReviewHeldDeletes]: the player pressed B at the root to LEAVE, and was shown
     * what leaving would make permanent. **B here closes the browser for real** — that press is the
     * deliberate act `FileBrowserStore.clearSession`'s KDoc already claims the close is, made deliberate
     * by being taken on the screen that lists the payload.
     *
     * The player is not trapped: `SYSTEM_START` still closes the screen back to the listing (finalising
     * nothing), and Undo on each card is one A press away — after which B closes with nothing left to
     * finalise, and the legend says so.
     */
    EXIT_REVIEW,
}

/** What one focused queue button will do, as one value a dispatch site can act on. */
internal data class FileBrowserQueueTarget(
    val entryId: Long,
    val action: FileBrowserQueueAction,
)

/**
 * The buttons a queue card can carry, each naming the **one** `FileBrowserStore` entry point it
 * invokes. The mapping is stated here rather than at a call site so it is pinnable without Compose.
 *
 * @param labelRes the player-facing text, a string resource (localization step 4). The three conflict answers are the
 *   [FileConflictChoice] answers [choice] carries; their words live here rather than in `core-files`, whose English label is
 *   never drawn, so a language change reaches them.
 * @param choice the conflict answer this action carries, or null for the two delete-settling actions.
 *   Non-null exactly for the three [FileQueueState.AwaitingConflict] answers, which is what lets a
 *   dispatch site call `resolveConflict(id, choice)` without a second `when`.
 */
internal enum class FileBrowserQueueAction(
    private val choice: FileConflictChoice?,
    @StringRes val labelRes: Int,
) {
    /** `FileBrowserStore.undo(entryId)` — put the staged files back, then drop the card. */
    UNDO(choice = null, labelRes = R.string.files_queue_action_undo),

    /**
     * `FileBrowserStore.releaseUndo(entryId)` — make the delete permanent, then drop the card.
     *
     * **Not in the design, and added deliberately.** The design's prototype has one `onPrimary` per
     * card that both "undoes" and dismisses by filtering the entry out of a plain array; it never has to
     * distinguish restoring the files from committing the deletion because it has no files. This tree
     * does: `ManagedFileOperations.undoDelete` and `finalizeDelete` are different operations on a real
     * staging journal, and an entry that could only be undone would leave the player no way to say "yes,
     * I meant it" short of closing the manager (which `FileBrowserStore.clearSession` finalises anyway).
     * Labelled for what it does to the files rather than to the card — "Dismiss" would hide the fact
     * that pressing it frees the payload.
     */
    KEEP_DELETED(choice = null, labelRes = R.string.files_queue_action_keep_deleted),

    /**
     * `FileBrowserStore.dismiss(entryId)` — drop the card. **Frees nothing.**
     *
     * The label is the whole point of it being a separate action from [KEEP_DELETED]. Both remove a card;
     * only one of them touches the player's files. "Keep deleted" is named for what it does to the FILES
     * because pressing it releases a staged payload permanently; "Dismiss" is named for what it does to
     * the CARD because that is genuinely all it does — the operation it describes already finished,
     * failed, or was refused before the button existed. Reusing one word for both would make the harmless
     * press and the irreversible one indistinguishable.
     *
     * Offered only for [FileQueueState.Done], [FileQueueState.Failed] and [FileQueueState.Blocked] —
     * never for [FileQueueState.UndoAvailable], which the store also refuses. See
     * [FileBrowserQueueFocus]' KDoc for why that refusal is a safety boundary.
     */
    DISMISS(choice = null, labelRes = R.string.files_queue_action_dismiss),

    /**
     * `FileBrowserStore.cancel(entryId)` -- stop a running copy, move or extract at its next safe point.
     * Named for what the player asked for; the card then reads "Cancelling…" until the job has wound down
     * and "Cancelled" with what was kept.
     */
    CANCEL(choice = null, labelRes = R.string.files_queue_action_cancel),

    /** `FileBrowserStore.resolveConflict(entryId, FileConflictChoice.KEEP_BOTH)`. */
    KEEP_BOTH(choice = FileConflictChoice.KEEP_BOTH, labelRes = R.string.files_queue_action_keep_both),

    /** `FileBrowserStore.resolveConflict(entryId, FileConflictChoice.REPLACE)`. */
    REPLACE(choice = FileConflictChoice.REPLACE, labelRes = R.string.files_queue_action_replace),

    /** `FileBrowserStore.resolveConflict(entryId, FileConflictChoice.SKIP)`. */
    SKIP(choice = FileConflictChoice.SKIP, labelRes = R.string.files_queue_action_skip),
    ;

    /** The conflict answer to pass to `resolveConflict`, or null when this action is not one. */
    val conflictChoice: FileConflictChoice? get() = choice
}

/**
 * How loudly one card's chip reads. **Carries no colour** — mapped to palette tokens only at the
 * composable call site, exactly as [FileBrowserChipTone] is.
 *
 * Deliberately a second enum rather than a reuse of [FileBrowserChipTone]: that type has no failure
 * arm, and the design gives a blocked card its `error` chip class
 * (`Mimir Files.dc.html:833`). Adding an arm to [FileBrowserChipTone] would break the exhaustive `when`
 * in `FileBrowserSurface`'s `FileBrowserChip` — a separate rendering consumer.
 */
internal enum class FileBrowserQueueTone { NEUTRAL, PENDING, DONE, FAILED }

/**
 * One card on the Queue screen (`Mimir Files.dc.html:238-266`): title, second line, chip, an optional
 * progress bar, and the buttons this state earns.
 *
 * @param state the entry's own state, never a word: the chip's words are chosen from it on display ([fileBrowserQueueChipLabel]), so a
 *   language change reaches a card that is already on the queue. Title and subtitle are the store's English, kept as written here and drawn in the host's
 *   language by [fileBrowserQueueTitleText] and [fileBrowserQueueLineText] (`FilesQueueText.kt`), so a card already on the queue follows a language change too.
 * @param progress 0..1 when a bar is drawn, **null when it is not**. Null rather than 0f because the
 *   design's `showBar` is a separate flag from its `pct`, and a zero-width bar under a finished
 *   operation would read as "stalled at 0%" — the same fabrication class as the checksum row's
 *   "Not checked" after a successful verify (F-09).
 * @param actions in the design's own left-to-right order. Empty is a legitimate answer, and the two
 *   ways it happens are documented on [FileBrowserQueueFocus].
 */
internal data class FileBrowserQueueCard(
    val entryId: Long,
    val title: String,
    val subtitle: String,
    val state: FileQueueState,
    val tone: FileBrowserQueueTone,
    val progress: Float?,
    val actions: List<FileBrowserQueueAction>,
)

/**
 * Project the store's queue into cards, **in the order given** — the store already emits newest-first
 * ([FileOperationQueue.enqueue]) and re-sorting here would put two orderings in the tree.
 *
 * The header line above these cards is [FileOperationQueue.summary], used directly; it is not wrapped
 * here, so there is no second place for the "nothing leaves this device" claim to drift.
 */
internal fun fileBrowserQueueCards(entries: List<FileQueueEntry>): List<FileBrowserQueueCard> =
    entries.map { entry ->
        FileBrowserQueueCard(
            entryId = entry.id,
            title = entry.title,
            subtitle = fileBrowserQueueSubtitle(entry),
            state = entry.state,
            tone = fileBrowserQueueTone(entry.state),
            progress = (entry.state as? FileQueueState.Running)?.fraction?.coerceIn(0f, 1f),
            actions = fileBrowserQueueActions(entry),
        )
    }

/**
 * A card's second line. **A failed or refused card says why** (E2 alpha-cert O3: every failure read the
 * single word "Failed"): the state already carries the reason in plain words, so it replaces the line
 * written at enqueue -- which describes what was ASKED ("Moved from three to gb") and would otherwise sit
 * under a Failed chip as if it had happened. Every other state keeps the line the store wrote.
 */
internal fun fileBrowserQueueSubtitle(entry: FileQueueEntry): String = when (val state = entry.state) {
    is FileQueueState.Failed -> state.reason.ifBlank { entry.subtitle }
    is FileQueueState.Blocked -> state.reason.ifBlank { entry.subtitle }
    // A cancelled card says what the stop left on disk, in the same slot a failure says why.
    is FileQueueState.Cancelled -> state.kept.ifBlank { entry.subtitle }
    else -> entry.subtitle
}

/**
 * The buttons one card earns: the state's own, plus **Cancel** on a running copy, move or extract that is
 * not already winding down. Extract cards are tagged [JobKind.COPY] by the store, so COPY and MOVE cover all
 * three. A running DELETE (a move into staging) is over before a press
 * could land, so it carries nothing.
 */
internal fun fileBrowserQueueActions(entry: FileQueueEntry): List<FileBrowserQueueAction> {
    val state = entry.state
    return if (state is FileQueueState.Running) {
        if (!state.cancelling && (entry.kind == JobKind.COPY || entry.kind == JobKind.MOVE)) {
            listOf(FileBrowserQueueAction.CANCEL)
        } else {
            emptyList()
        }
    } else {
        fileBrowserQueueActions(state)
    }
}

/**
 * The buttons one state earns.
 *
 * Written over the state rather than over [FileQueueEntry] so the Undo handle's presence is what
 * decides — [FileQueueState.UndoAvailable] cannot be constructed without a `StagedDeletion`, so this
 * function cannot offer [FileBrowserQueueAction.UNDO] for a delete it could not reverse.
 */
internal fun fileBrowserQueueActions(state: FileQueueState): List<FileBrowserQueueAction> = when (state) {
    // A bar and nothing else from the state alone; the entry overload above adds Cancel for the kinds that
    // can be stopped.
    is FileQueueState.Running -> emptyList()
    // Explicitly, never silently -- the standing invariant. All three, always all three.
    FileQueueState.AwaitingConflict -> listOf(
        FileBrowserQueueAction.KEEP_BOTH,
        FileBrowserQueueAction.REPLACE,
        FileBrowserQueueAction.SKIP,
    )
    is FileQueueState.UndoAvailable -> listOf(
        FileBrowserQueueAction.UNDO,
        FileBrowserQueueAction.KEEP_DELETED,
    )
    // A completed move that can be sent back. UNDO is the same word for the same promise -- "put it back"
    // -- and the store branches on the state, so one action keeps one meaning. The second button is
    // DISMISS rather than KEEP_DELETED: nothing is being kept deleted, and the card is droppable because
    // the move is already final (`fileQueueDismissable`'s own ReverseAvailable arm).
    is FileQueueState.ReverseAvailable -> listOf(
        FileBrowserQueueAction.UNDO,
        FileBrowserQueueAction.DISMISS,
    )
    // A terminal card is removed by FileBrowserStore.dismiss(entryId), which accepts exactly these three
    // states. One button, and it frees nothing -- the operation it describes is already over. Deliberately
    // NOT offered on UndoAvailable above, which the store also refuses: see FileBrowserQueueFocus' KDoc.
    FileQueueState.Done, FileQueueState.Skipped, is FileQueueState.Failed, is FileQueueState.Blocked,
    is FileQueueState.Cancelled,
    -> listOf(FileBrowserQueueAction.DISMISS)
}

/** The design's own chip classes (`Mimir Files.dc.html:833`), as tones. */
private fun fileBrowserQueueTone(state: FileQueueState): FileBrowserQueueTone = when (state) {
    // Skipped is NEUTRAL, deliberately not DONE and not FAILED: the card must not read as an
    // achievement, and must not read as an alarm either. Nothing happened, and that is the whole message.
    is FileQueueState.Running, FileQueueState.Skipped, is FileQueueState.Cancelled -> FileBrowserQueueTone.NEUTRAL
    FileQueueState.AwaitingConflict, is FileQueueState.UndoAvailable -> FileBrowserQueueTone.PENDING
    // DONE, not PENDING: the move finished. PENDING is for a card still waiting on the player -- an
    // unanswered conflict, or a delete that has not been made permanent -- and a completed move is
    // neither. Drawing it as pending would say the transfer is unfinished when the files have arrived.
    FileQueueState.Done, is FileQueueState.ReverseAvailable -> FileBrowserQueueTone.DONE
    is FileQueueState.Failed, is FileQueueState.Blocked -> FileBrowserQueueTone.FAILED
}

/**
 * Every focusable key the Queue screen holds, in render order: each card's buttons, card by card.
 *
 * Cards with no buttons contribute nothing, so a queue of four running transfers registers **zero**
 * focus slots. That is correct and it is not a dead end: leaving the queue is a CANCEL, which
 * `FileBrowserController.dispatch` handles without consulting focus at all. A surface must not assume
 * this list is non-empty.
 */
internal fun fileBrowserQueueKeys(cards: List<FileBrowserQueueCard>): List<FocusKey> =
    cards.flatMap { card ->
        card.actions.map { action -> FileBrowserQueueFocus.actionKey(card.entryId, action) }
    }

/**
 * Whether the Queue screen exists for this session at all — **[FileBrowserMode.MANAGE] only**.
 *
 * Capability splits by MODE, and PICK must be *incapable*, not restricted. In
 * PICK the queue is already permanently empty (`FileBrowserStore.operations` is null, so nothing can
 * enqueue), which means a PICK queue screen would render an empty list rather than anything dangerous —
 * and that is exactly why the gate is stated here as well. A screen that is merely always-empty invites
 * the next reader to "fix" it by wiring an enqueue path into PICK; a screen that does not exist in PICK
 * does not.
 */
internal fun fileBrowserQueueAvailable(mode: FileBrowserMode): Boolean = mode == FileBrowserMode.MANAGE
