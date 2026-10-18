package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources

/**
 * The footer legend, as data -- pure so the wording and the tile-sensitivity can be tested without
 * composing anything (mirrors this codebase's existing shelf/gamelist/folder-picker footer-hint split:
 * derive the tokens first, render them after).
 *
 * **Context-sensitive by focused tile, because a fixed legend would lie.** The SECONDARY gesture only
 * extracts when the focused tile is an archive; advertising an EXTRACT hint on a plain file or a
 * directory would teach a button that does nothing there. **`CHOOSE` restored 2026-08-14** for
 * [FileBrowserMode.PICK]'s pinned commit affordance -- see [FileBrowserController]'s KDoc for why it
 * came back; [commitFocused] is how this pure function learns the affordance (not a
 * [FileBrowserTile], so invisible to [focused]) currently holds focus.
 *
 * **F-06 changes three of these entries while a destination pick is open, and every one is a case of the
 * same standing rule rather than three new ones:**
 *  - A on the commit row says `EXTRACT HERE`, not `CHOOSE` — the row commits a destination now, not a
 *    ROM source, and the two words answer different questions. Read from [commitPurpose], which is
 *    [FileBrowserController.commitPurpose]'s own value, so the legend and [fileBrowserCommand]'s CONFIRM
 *    branch trace to the one gate rather than to two copies of a predicate.
 *  - The `X EXTRACT` hint disappears. `SECONDARY` is deliberately swallowed for the duration (see that
 *    branch), and a legend entry over a dead binding is the same lie as a binding with no legend entry.
 *  - B says `BACK` even at the volume root, where it normally says `CLOSE`. It cannot close the browser
 *    during a pick — see [FileBrowserDestinationPickPhase] — so `CLOSE` would promise an exit that press
 *    does not perform.
 *
 * **[commitFocused] and [commitPurpose] are two parameters on purpose, and this is NOT the illegal-state
 * shape [fileBrowserCommand] deliberately avoided.** There, only the conjunction was ever meaningful, so
 * one nullable value was right. Here the two facts are genuinely orthogonal and `FileBrowserSurface`
 * already computes them separately (`commitAvailable` and `commitFocused`, both at its line 193): a row
 * that exists and commits a destination while the ring sits on a tile is an ordinary state, not a
 * contradiction. The one combination worth naming is `commitFocused = true` with `commitPurpose = null`:
 * that is **every caller written before the earlier change, by definition**, and it renders the earlier word.
 * All three new behaviours default off, so an un-updated caller's legend is byte-identical to before —
 * pinned by this file's own existing tests, which are left calling the old signature unchanged.
 *
 * **G2 adds one entry and one parameter.** `X` now says **`PLACE`** on a focused plain FILE — see that
 * branch for why the word is neither "Move" nor "Copy" — and `A` on the commit row says `MOVE HERE` /
 * `COPY HERE` for the two new [FileBrowserCommitPurpose] arms. `transferAvailable` defaults **false**, so
 * an un-updated caller's legend stays byte-identical to before; the cost of forgetting to wire it is a
 * live `SECONDARY` binding with no legend entry, which is a **contract obligation on the surface** and is
 * requires a matching legend entry on the surface.
 *
 * Tokens (`"A"`, `"B"`, `"X"`) are the logical face-button names, unchanged from slice 5-6; a caller
 * renders them through whatever projects a token onto the player's own physical button labels
 * ([com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels]).
 *
 * **F-1, 2026-08-18, supersedes three of the above rather than adding a fourth.** `deleteAvailable` is
 * gone, replaced by [actionsAvailable]; `SELECT DELETE` is gone, replaced by `SELECT ACTIONS` (wording still awaiting review); `X EXTRACT` now also reads `!actionsAvailable`, and `X PLACE` — `transferAvailable`'s one call
 * site — is deleted outright rather than guarded, because guarding it would have produced a branch that
 * can never fire (see that arm's own comment for why). Every G2/F-06 paragraph above still describes the
 * moment it was written about correctly; it is the CURRENT truth for `SELECT`/`X` on a focused tile that
 * has moved one level — see [fileBrowserActionOverlayFooterHints] for where.
 */
internal fun fileBrowserFooterHints(
    res: Resources,
    focused: FileBrowserTile?,
    atRoot: Boolean,
    commitFocused: Boolean = false,
    // F-2. Whether the pinned row's "Back to origin" sibling currently holds focus -- policy
    // 2026-08-18. Checked FIRST in the `when` below, ahead of every commitFocused arm: the two rows are
    // never focused at once, so order does not change which branch fires, but this mirrors
    // FileBrowserDispatch's own CONFIRM table, which checks the identical condition first for the
    // identical reason (this row means one thing, unconditionally, whenever it holds focus).
    backToOriginFocused: Boolean = false,
    searchAvailable: Boolean = false,
    // F-1/R-3, 2026-08-18. Replaces `deleteAvailable` -- [fileBrowserActionsAvailable], the SAME predicate
    // `FileBrowserDispatch`'s SYSTEM_SELECT and SECONDARY arms gate on, threaded here rather than
    // recomputed so the legend and the dispatch table can never disagree about whether SELECT does
    // anything on the focused tile.
    actionsAvailable: Boolean = false,
    queueAvailable: Boolean = false,
    /**
     * **R-21: a focused file is committable in a source pick.** Same predicate `FileBrowserDispatch`'s
     * CONFIRM arm gates on, threaded rather than recomputed.
     *
     * Added after device observation exposed a missing legend: A really did add the
     * focused game -- I drove it and the shelf went from one game to two -- but the footer read only
     * "Y SEARCH / B BACK", so the press that worked was the press nothing advertised. This file's own
     * standing rule is that a binding with no legend entry is worse than no binding, and I had shipped
     * exactly that.
     */
    sourcePickAvailable: Boolean = false,
    /**
     * **[FileBrowserMode.PICK_IMAGE]: the focused file is a choosable image.** The same predicate
     * `FileBrowserDispatch`'s CONFIRM arm gates on ([fileBrowserFileChoosable]), threaded rather than
     * recomputed -- R-21's own lesson one parameter up: the press that works must be the press the
     * legend advertises, and the tile that is dimmed must be the tile that advertises nothing.
     */
    imagePickAvailable: Boolean = false,
    queueCount: Int = 0,
    commitPurpose: FileBrowserCommitPurpose? = null,
    destinationPick: FileBrowserDestinationPickPhase? = null,
    heldDeleteCount: Int = 0,
    /**
     * [FileBrowserMode.PICK_IMAGE]: whether the pinned row currently shows every file. Decides which way the
     * row's A reads (SHOW ALL, or back to IMAGES ONLY) -- the legend names the press's result, not its state.
     */
    imageFilterShowsAll: Boolean = false,
): List<Pair<String, String>> = buildList {
    when {
        // F-2. A short, generic verb -- deliberately NOT the origin folder's name, which the row's own
        // label already carries (fileBrowserBackToOriginLabel). Every other entry in this `when` is a
        // fixed verb phrase too (EXTRACT HERE, MOVE HERE, CHOOSE), never the dynamic detail the row
        // itself draws; B's own hint is this function's one precedent for embedding a live number, and
        // that number (a delete/queue count) is information the footer alone carries, unlike this row's
        // name, which is already on screen. Copy is a starting point -- still subject to design review, same
        // standing as the row's own visual weight.
        backToOriginFocused -> add("A" to res.getString(R.string.files_footer_back_to_origin))
        // "HERE" means "into the folder I am looking at", which is a different referent from the
        // chooser's own "Extract here" row (a sibling of the archive) -- but the two are never on screen
        // together, and the chooser is closed before this legend can be reached.
        commitFocused && commitPurpose == FileBrowserCommitPurpose.EXTRACT_DESTINATION ->
            add("A" to res.getString(R.string.files_footer_extract_here))
        // G2. The same referent shift, twice more, and the verb is the player's own row: they pressed
        // "Move into…" and this is where the "…" gets answered. `MOVE HERE` rather than a shared `PLACE
        // HERE` -- the umbrella word belongs to the press that OPENS the question (X, below), and this is
        // the press that performs it, where being vague would be hiding which of two verbs is about to run
        // on the player's file. MOVE can be walked back from the queue (2026-08-16); COPY cannot, and
        // needs no walking back. Neither word may imply the press is free.
        commitFocused && commitPurpose == FileBrowserCommitPurpose.MOVE_DESTINATION ->
            add("A" to res.getString(R.string.files_footer_move_here))
        commitFocused && commitPurpose == FileBrowserCommitPurpose.COPY_DESTINATION ->
            add("A" to res.getString(R.string.files_footer_copy_here))
        // The image picker's pinned row is a switch, so A names what it switches TO.
        commitFocused && commitPurpose == FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER ->
            add("A" to res.getString(if (imageFilterShowsAll) R.string.files_footer_images_only else R.string.files_footer_show_all))
        commitFocused -> add("A" to res.getString(R.string.files_footer_choose))
        focused?.kind == FileBrowserTileKind.DIRECTORY -> add("A" to res.getString(R.string.files_footer_open))
        // R-21. After the DIRECTORY arm, because a folder in a source pick still opens -- navigating into
        // it is how the player reaches the game. **Wording remains unreviewed:** the policy specifies the behaviour, not the word.
        sourcePickAvailable && focused != null -> add("A" to res.getString(R.string.files_footer_add_game))
        // PICK_IMAGE (2026-09-11). The commit row's own word, reused rather than coined: the press answers
        // the same "choose" question the pinned row answers in PICK, one tile at a time. A non-image file
        // falls through to no A entry at all, matching the dispatch table's Consumed for that tile.
        imagePickAvailable && focused != null -> add("A" to res.getString(R.string.files_footer_choose))
        // F-1/R-13, 2026-08-18. **Retired the moment actionsAvailable is true.** X used to reach Extract
        // directly on an archive; SELECT's overlay now offers the identical Extract row (plus Delete when
        // available), and `fileBrowserCommand`'s SECONDARY arm goes quiet on the SAME predicate this
        // guards against -- a hint over a binding SECONDARY no longer honours would be exactly this file's
        // own "a fixed legend would lie" rule. Still reachable in PICK, where `actionsAvailable` is false
        // by construction (the overlay never opens there) and the pre-F-1 direct extract is unchanged.
        focused?.kind == FileBrowserTileKind.ARCHIVE && destinationPick == null && !actionsAvailable ->
            add("X" to res.getString(R.string.files_footer_extract))
        // F-1/R-13. **The `X PLACE` arm this comment used to sit on is DELETED, not merely reworded.**
        // `transferChoiceAvailable` is MANAGE-only and MANAGE-plus-a-focused-FILE-plus-transferChoiceAvailable
        // is exactly the condition under which `actionsAvailable` is ALSO true (Move/Copy are two of this
        // tile's overlay rows) -- so a `!actionsAvailable` guard on this arm, mirroring EXTRACT's above,
        // would be permanently false, a dead branch pretending to be a live one. Unlike EXTRACT, Move/Copy
        // never had a PICK-reachable path to preserve (`transferChoiceAvailable`'s own gate is MANAGE-only,
        // matching `deleteAvailable`'s reasoning, not `extractChoiceAvailable`'s) -- so there is nothing
        // left for this arm to guard. `PLACE` remains the umbrella word for the SELECT-adjacent vocabulary
        // (the overlay's own eyebrow question is separate, design spec §9) but is no longer a legend entry.
        else -> Unit
    }
    // F-08. The one thing that makes TERTIARY a binding rather than a secret: it is drawn whenever it is
    // live and never when it is not, which is this function's whole existing rule ("a fixed legend would
    // lie") applied to a new verb. Not tile-sensitive -- unlike EXTRACT, search works from anywhere in
    // any listing, including an empty one.
    if (searchAvailable) add("Y" to res.getString(R.string.files_footer_search))
    // F-1/R-3, 2026-08-18. Replaces the old `SELECT DELETE` entry -- Delete moved inside the overlay as a
    // row, so SELECT's own word can no longer promise "delete" specifically. Reads the identical
    // [actionsAvailable] predicate [fileBrowserCommand]'s SYSTEM_SELECT arm gates on, so the legend and
    // the dispatch table cannot drift apart the way F-01's own KDoc warns against. **Wording remains unreviewed** —
    // design spec §9: `SELECT ACTIONS` / `SELECT MENU` / `SELECT OPTIONS` were the candidates; `ACTIONS`
    // is carried here as the recommendation, matching the overlay's own eyebrow word.
    //
    // `focused != null` is checked HERE too, belt-and-braces, on [deleteAvailable]'s own old shape --
    // `fileBrowserActionsAvailable` already answers false on a null tile when a caller computes it the
    // documented way, but this function must not depend on every caller doing that correctly to stay
    // honest on its own terms, the same defence-in-depth `openTransferChooser`'s door keeps beside the
    // dispatch table's.
    // **D-3, policy on the device 2026-08-18: the overlay moved from SELECT to X.** The legend
    // moves with it in the same edit, deliberately -- this file's own standing rule is that a binding
    // with no legend entry is worse than no binding, and its mirror is just as true: a legend entry
    // naming a button that no longer acts teaches the player something false about their own handheld.
    if (actionsAvailable && focused != null) add("X" to res.getString(R.string.files_footer_actions))
    // F-01 / C-1. Drawn whenever the queue exists, EMPTY OR NOT -- this one is deliberately not
    // context-sensitive, and that is the opposite call from EXTRACT and DELETE above.
    //
    // Undo is now the only protection a delete has. A player who deletes and never opens the queue has to
    // be able to FIND it, and UI-03 already established that a hint appearing and vanishing under his eye
    // does not register as a control. So the binding is persistent; only the COUNT is live. That still
    // obeys "a fixed legend would lie", because the queue screen genuinely exists in every state this
    // hint is drawn in -- an empty queue screen honestly reports an empty queue, unlike a search screen
    // with no engine, which would report "nothing by that name" about a card full of games.
    if (queueAvailable) {
        add("START" to if (queueCount > 0) res.getString(R.string.files_footer_queue_count, queueCount) else res.getString(R.string.files_footer_queue))
    }
    // At the volume root there is no parent to go up to, so the same button leaves the browser
    // entirely -- saying BACK there would promise somewhere to go back to that does not exist.
    // F-06: unless a destination pick is open, in which case B always has somewhere to go (up a
    // directory, or back to the chooser) and never closes anything.
    //
    // **W-1: and unless deletes are held, in which case the press does not close either -- it opens the
    // queue.** This is this function's own founding rule ("a fixed legend would lie") applied to the one
    // press that was still fixed: `CLOSE` over a press that now goes to the queue would be the same lie
    // as an EXTRACT hint over a plain file, and `CLOSE` over the press that FINALISED every held delete
    // was the lie W-1 found. The word names the destination and the stake, and the count is live for the
    // reason the START hint's is. The condition is [fileBrowserExitOpensQueue] -- literally the function
    // [fileBrowserCommand]'s CANCEL arm branches on, not a copy of its terms.
    add(
        "B" to when {
            fileBrowserExitOpensQueue(
                atRoot = atRoot,
                destinationPick = destinationPick,
                queueAvailable = queueAvailable,
                heldDeleteCount = heldDeleteCount,
            ) -> res.getQuantityString(R.plurals.files_footer_review_deletes, heldDeleteCount, heldDeleteCount)
            atRoot && destinationPick == null -> res.getString(R.string.files_footer_close)
            else -> res.getString(R.string.files_footer_back)
        },
    )
}

/*
 * The count both W-1 legends carry ("Review 3 deletes", "Close · 1 delete final") is a plural string of its own in each legend
 * (`files_footer_review_deletes`, `files_footer_close_final`), so no translated noun is joined to a number here.
 *
 * **It counts held delete OPERATIONS, which is what the queue draws as cards, not files.** A single
 * operation covering five files reads "1 delete" here and `5 files` on its own card. That is deliberate:
 * this word's whole job is to prepare the player for the screen one press away, and a number that did not
 * match the number of cards he then counts would be its own small lie. It is also exactly
 * `FileOperationQueue.heldDeletions(...).size` — the list `FileBrowserStore.clearSession` walks — so the
 * legend counts the same things the finalise counts. Today the distinction is invisible (the browser
 * deletes one path per press; selection mode is unbuilt) and it is named here so it stays a decision.
 */

/**
 * The Extract chooser's own legend — **F-06**. A fourth screen with a fourth set of verbs, split for the
 * reason [fileBrowserSearchFooterHints] and [fileBrowserQueueFooterHints] are: reusing the browse legend
 * here would advertise OPEN, DELETE and a START binding this screen does not implement, over a question
 * with exactly two answers.
 *
 * **A's word is the focused ROW's own verb, because the two rows do genuinely different things.**
 * [FileBrowserExtractOptionKind.HERE] and [FileBrowserExtractOptionKind.PLATFORM_FOLDER] both extract on
 * the spot; [FileBrowserExtractOptionKind.CHOOSE_FOLDER] extracts nothing at all — it hands the browser
 * back to the player to answer "where?" — so one fixed `EXTRACT` would be wrong on the row where being
 * wrong matters, promising a write that does not happen and leaving the player waiting for files.
 *
 * [focusedKind] is null only in the frame before the chooser's landing focus arrives, or if a foreign key
 * holds the ring; no A hint is drawn then, and B still leaves.
 */
internal fun fileBrowserExtractFooterHints(
    res: Resources,
    focusedKind: FileBrowserExtractOptionKind?,
): List<Pair<String, String>> = buildList {
    when (focusedKind) {
        FileBrowserExtractOptionKind.HERE,
        FileBrowserExtractOptionKind.PLATFORM_FOLDER,
        -> add("A" to res.getString(R.string.files_footer_extract))
        FileBrowserExtractOptionKind.CHOOSE_FOLDER -> add("A" to res.getString(R.string.files_footer_choose_folder))
        null -> Unit
    }
    // Always BACK: the chooser always has the listing it was opened from to return to, which is exactly
    // the condition the browse legend has to check for and this one does not.
    add("B" to res.getString(R.string.files_footer_back))
}

/**
 * The transfer chooser's own legend — **G2**. A fifth screen with a fifth set of verbs, split for the
 * reason [fileBrowserExtractFooterHints], [fileBrowserSearchFooterHints] and [fileBrowserQueueFooterHints]
 * are: reusing the browse legend here would advertise OPEN, DELETE and a START binding this screen does not
 * implement, over a question with exactly two answers.
 *
 * **A says `CHOOSE FOLDER` on both rows, and that is a finding rather than a shrug.** The obvious wording
 * — A's word being the focused row's own verb, `MOVE` or `COPY` — is the wording this screen may not use,
 * because **neither row moves or copies anything.** Both hand the browser back to the player to answer
 * "where?", so both would be promising a write that does not happen and leaving the player waiting for a
 * file to disappear from the listing. That is exactly why [FileBrowserExtractOptionKind.CHOOSE_FOLDER]
 * already draws `CHOOSE FOLDER` on the extract chooser instead of `EXTRACT`: same press, same word, one
 * language. **The verb the player armed is stated twice regardless** — on the row they are standing on
 * (`Move into…`), and again on the commit row's `A · MOVE HERE` at the moment it becomes true.
 *
 * **[focusedKind] is therefore not a vacuous parameter, and it is not kept for symmetry.** Its live branch
 * is the **null** one: null in the frame before the chooser's landing focus arrives, or if a foreign key
 * holds the ring, and no A hint may be drawn then. B still leaves, so that frame is not a dead end. The two
 * non-null arms answering one word is a property of *slice 1 only* — slice 2's smart rows (`Move into
 * GBA`) DO write on the spot, and are what will split them.
 */
internal fun fileBrowserTransferFooterHints(
    res: Resources,
    focusedKind: FileBrowserTransferKind?,
): List<Pair<String, String>> = buildList {
    when (focusedKind) {
        FileBrowserTransferKind.MOVE, FileBrowserTransferKind.COPY -> add("A" to res.getString(R.string.files_footer_choose_folder))
        null -> Unit
    }
    // Always BACK: the chooser always has the listing it was opened from to return to, which is exactly
    // the condition the browse legend has to check for and this one does not.
    add("B" to res.getString(R.string.files_footer_back))
}

/**
 * The SELECT action overlay's own legend — **F-1**. A sixth screen with a sixth set of verbs, split for
 * the reason [fileBrowserExtractFooterHints] / [fileBrowserTransferFooterHints] /
 * [fileBrowserDestinationFooterHints] / [fileBrowserQueueFooterHints] / [fileBrowserSearchFooterHints] all
 * are: reusing the browse legend here would advertise OPEN and a START binding this screen does not
 * implement, on a question the browse legend has no rows for.
 *
 * **The A-word rule already established for a hand-off row holds here too, and splits Move/Copy from
 * Extract, not a shared word for all three** — design spec §8. Move/Copy always hand off to a destination
 * pick (no smart-skip exists for them, `FileBrowserActionOverlayChoice.kt`'s own row model), so their
 * press is exactly [fileBrowserTransferFooterHints]'s own already-established `CHOOSE FOLDER` moment,
 * relocated one screen earlier. Extract does **not** always hand off —
 * `fileBrowserExtractNeedsChooser` can resolve it on the spot with no further screen at all — so
 * `CHOOSE FOLDER` would be wrong exactly when that happens; its word is [fileBrowserFooterHints]'s own
 * pre-F-1 `X EXTRACT` word, unchanged, because the press still means the same thing whether it resolves
 * immediately or opens a chooser.
 *
 * **Delete's row is the one exception to the whole rule** — pressing it IS the write (a staged one, per
 * the reversible-delete flow), so its word may say so directly. Wording remains unreviewed.
 *
 * [focusedKind] is null only in the frame before the overlay's landing focus arrives, or if a foreign key
 * holds the ring; no A hint is drawn then, and B still leaves.
 */
internal fun fileBrowserActionOverlayFooterHints(
    res: Resources,
    focusedKind: FileBrowserActionOverlayRowKind?,
): List<Pair<String, String>> = buildList {
    when (focusedKind) {
        FileBrowserActionOverlayRowKind.MOVE, FileBrowserActionOverlayRowKind.COPY ->
            add("A" to res.getString(R.string.files_footer_choose_folder))
        // Wording remains unreviewed.
        // Opens the keyboard rather than doing the rename, so the word is the verb of the next step.
        FileBrowserActionOverlayRowKind.RENAME -> add("A" to res.getString(R.string.files_footer_rename))
        FileBrowserActionOverlayRowKind.EXTRACT -> add("A" to res.getString(R.string.files_footer_extract))
        // Wording remains unreviewed.
        FileBrowserActionOverlayRowKind.DELETE -> add("A" to res.getString(R.string.files_footer_delete))
        null -> Unit
    }
    // Always BACK: the overlay always has the listing it was opened from to return to, matching every
    // other chooser's legend in this module.
    add("B" to res.getString(R.string.files_footer_back))
}

/**
 * The destination list's own legend — **policy 2026-08-16**.
 *
 * **A's word is the focused row's own outcome, on [fileBrowserExtractFooterHints]' rule exactly**, and
 * here the two rows differ in the direction that matters: a platform row *finishes the job on the spot*
 * — the file is extracted, moved or copied by that press — while the browse row writes nothing and hands
 * the browser back to answer "where?". One word for both would promise a write that does not happen, on
 * a screen where the other press commits a real transfer.
 *
 * The verb itself is deliberately NOT printed here (`A · EXTRACT` vs `A · MOVE`). This screen serves all
 * three, the pick underneath it is the authority on which, and the row the player is standing on names a
 * FOLDER — so the legend says what the press does to the list, and the verb was named on the screen
 * before it. **When the verb becomes wrong to omit, it comes from `FileBrowserCommitPurpose`, not from a
 * second copy of the pick's state.**
 *
 * **B says CANCEL, not BACK, and the difference is the whole contract of [dispatchDestination].** Backing
 * out of here does not return to a chooser — that closed itself to start the pick — it abandons the pick
 * outright. `BACK` over a press that cancels a move the player armed would be the same lie W-1 found on
 * the browse legend.
 */
internal fun fileBrowserDestinationFooterHints(
    res: Resources,
    focusedKind: FileBrowserDestinationRowKind?,
): List<Pair<String, String>> = buildList {
    when (focusedKind) {
        FileBrowserDestinationRowKind.PLATFORM -> add("A" to res.getString(R.string.files_footer_put_it_here))
        FileBrowserDestinationRowKind.BROWSE -> add("A" to res.getString(R.string.files_footer_choose_folder))
        null -> Unit
    }
    add("B" to res.getString(R.string.files_footer_cancel))
}

/**
 * The search screen's own legend — F-08. A different screen has different verbs, and reusing
 * [fileBrowserFooterHints] there would advertise EXTRACT and CHOOSE on a screen that offers neither.
 *
 * Context-sensitive by focused control, for the same reason the browse legend is by focused tile: A does
 * three different things on this screen (open the keyboard, apply a chip, reveal a hit) and one fixed
 * word for all three would be wrong twice.
 *
 * B always says BACK: search always has somewhere to go back to (the listing it was opened from), which
 * is exactly the condition the browse legend has to check for.
 */
internal fun fileBrowserSearchFooterHints(
    res: Resources,
    onQuery: Boolean,
    onFilter: Boolean,
    onResult: Boolean,
): List<Pair<String, String>> = buildList {
    when {
        onQuery -> add("A" to res.getString(R.string.files_footer_type))
        onFilter -> add("A" to res.getString(R.string.files_footer_filter))
        onResult -> add("A" to res.getString(R.string.files_footer_go_to_file))
        else -> Unit
    }
    add("B" to res.getString(R.string.files_footer_back))
}

/**
 * The Queue screen's own legend — F-01. A third screen with a third set of verbs, split for the same
 * reason [fileBrowserSearchFooterHints] is: reusing the browse legend here would advertise OPEN, EXTRACT
 * and a DELETE binding that the queue screen does not implement, on top of a START hint pointing at the
 * screen the player is already standing on.
 *
 * **A's word is the focused button's own label, never a fixed one.** The queue's buttons are heterogeneous
 * in a way no other screen's are — the same A press means "put my files back", "destroy them for good",
 * "drop this card", or one of three conflict answers, depending only on which button holds the ring. One
 * word for all six would be wrong five times, and on the two delete-settling buttons it would be wrong in
 * the direction that loses data. Taken from [FileBrowserQueueAction.label] rather than restated, so the
 * legend and the button can never disagree; upper-cased only because this footer's other verbs are, which
 * is presentation and not a second copy of the word.
 *
 * **[focusedAction] is legitimately null and the screen is not broken when it is.** A queue of nothing but
 * running transfers registers zero focus keys ([fileBrowserQueueKeys]) — there is genuinely nothing to
 * press, so no A hint is drawn. B still leaves, which is why that case is not a dead end; see
 * [FileBrowserQueueFocus]'s KDoc.
 *
 * **B's word is no longer fixed either — W-1.** This screen now has two postures
 * ([FileBrowserQueuePurpose]) and B does two different things: on a [FileBrowserQueuePurpose.VISIT] it
 * returns to the listing, and on a [FileBrowserQueuePurpose.EXIT_REVIEW] it closes the browser, which is
 * the press that makes every held delete permanent. `BACK` over that second press would be the same
 * defect W-1 found on the browse legend, one screen along and one press later — so the word says CLOSE,
 * and says how much becomes final. The count drops to nothing as the player presses Undo, and the word
 * follows it down: after undoing the last one, B closes and genuinely nothing is finalised, so nothing is
 * claimed.
 *
 * @param exitReview whether this screen was opened by [FileBrowserCommand.ReviewHeldDeletes] rather than
 *   by `SYSTEM_START`. False by default — every pre-W-1 caller's legend is byte-identical to before.
 * @param heldDeleteCount held deletes, from `FileOperationQueue.heldDeletions` — the same read
 *   `FileBrowserStore.clearSession` finalises from. Only ever rendered while [exitReview].
 */
internal fun fileBrowserQueueFooterHints(
    res: Resources,
    focusedAction: FileBrowserQueueAction?,
    exitReview: Boolean = false,
    heldDeleteCount: Int = 0,
): List<Pair<String, String>> = buildList {
    focusedAction?.let { add("A" to res.getString(it.labelRes)) }
    // BACK on a VISIT: the queue always has the listing it was opened from to return to, which is exactly
    // the condition the browse legend has to check for and this one does not. CLOSE on an EXIT_REVIEW,
    // because that is the press that leaves -- see this function's KDoc.
    add(
        "B" to when {
            !exitReview -> res.getString(R.string.files_footer_back)
            heldDeleteCount > 0 -> res.getQuantityString(R.plurals.files_footer_close_final, heldDeleteCount, heldDeleteCount)
            else -> res.getString(R.string.files_footer_close)
        },
    )
}
