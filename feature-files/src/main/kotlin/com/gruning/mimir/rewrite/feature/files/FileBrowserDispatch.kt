package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.input.ControllerAction

/**
 * What the pinned commit affordance commits — **the one row, two jobs, made structural.**
 *
 * The row itself is unchanged: index 0, virtual bounds, a real D-pad participant (policy 6), one
 * [FileBrowserFocus.commitKey] per directory. What the earlier change adds is a second thing it can *mean*, and
 * every consumer takes the meaning from here rather than re-deriving it:
 * [FileBrowserController.commitKeyForCurrentMode] builds the key from this and nothing else, the footer
 * legend picks its verb from it, and [fileBrowserCommand] turns it into two distinct commands.
 *
 * **Why an enum rather than a second boolean beside `commitFocused`.** `commitFocused = true` with no
 * purpose, or a purpose with `commitFocused = false`, are both meaningless states that a two-parameter
 * signature can represent and a caller can therefore pass. Collapsing them into one nullable value makes
 * the illegal combinations unrepresentable — the same argument [FileBrowserController.queueAction] makes
 * for deriving "the queue is wired" from the sink's own nullability instead of a parallel flag.
 *
 * This is also the state the surface renders the row's *copy* from. The controller deliberately hardcodes no
 * player-facing string for it: "Use this folder" and "Extract into this folder" answer two different
 * questions, and which question is being asked is a decision, while the wording is a rendering.
 */
internal enum class FileBrowserCommitPurpose {
    /** [FileBrowserMode.PICK]'s original job: report this directory to `onChoose` as a ROM source. */
    CHOOSE_SOURCE,

    /**
     * [FileBrowserMode.PICK_MUSIC_FOLDER]'s job (Settings › Music, 2026-09-30): report this directory to
     * `onChoose` as the music folder. Its own arm rather than CHOOSE_SOURCE because `sourcePickAvailable`
     * (R-21's single-file commit) reads CHOOSE_SOURCE by name, and a music folder pick must never offer
     * "ADD GAME" on a track. The row's copy differs too ("Use this folder", not "Add this folder").
     */
    CHOOSE_MUSIC_FOLDER,

    /**
     * F-06: extract the [FileBrowserDestinationPick]'s archive into this directory.
     *
     * **Available in [FileBrowserMode.MANAGE] as well as [FileBrowserMode.PICK]**, deliberately. PICK is kept *incapable* of anything destructive —
     * but an extraction writes new files and removes none, so it is not destructive and has always been
     * offered in both modes (`FileBrowserStore.extractOptions`' own KDoc says so). Gating this to MANAGE
     * by reflex would have removed from PICK a capability PICK already had.
     */
    EXTRACT_DESTINATION,

    /**
     * G2: move the [FileBrowserDestinationPick]'s file into this directory.
     *
     * **[FileBrowserMode.MANAGE]-only, and on the opposite reasoning from [EXTRACT_DESTINATION]'s.** That
     * arm is offered in both modes because an extraction writes new files and removes none. **A move
     * removes its source**, so it belongs to the destructive operations PICK must be
     * *incapable* of, beside [FileBrowserCommand.Delete]. The gate is
     * [FileBrowserController.transferChoiceAvailable] (`mode == MANAGE && onMoveTo != null && onCopyTo !=
     * null`) — mode-gated exactly as `deleteAvailable` is, not mode-blind as `extractChoiceAvailable` is.
     * No mode test is restated *here*, because this purpose is only reachable through an open transfer
     * pick, which is only reachable through that gate.
     */
    MOVE_DESTINATION,

    /**
     * G2: copy the [FileBrowserDestinationPick]'s file into this directory.
     *
     * **A separate arm from [MOVE_DESTINATION], never a boolean beside it.** The two reach different sinks
     * (`FileBrowserStore.moveInManager` / `copyInManager`) and the difference between them is whether the
     * player's file still exists where they left it. One arm plus an `isCopy` flag is exactly the shape
     * D-3 forbids, in the one place in this browser where getting it backwards destroys something.
     */
    COPY_DESTINATION,

    /**
     * [FileBrowserMode.PICK_IMAGE]'s pinned row: flip between "images only" and "all files". It commits nothing and closes nothing -- the one purpose that is not a question about a
     * folder -- and it lives in the pinned-row slot because that mode has no "use this folder" row.
     */
    TOGGLE_IMAGE_FILTER,
}

/**
 * What the browser should do in response to one controller action. Every input produces exactly one of
 * these -- there is no "unhandled" case, which is what lets [FileBrowserController.dispatch] return
 * `true` unconditionally (a hard modal that ever falls through would let the same press move whatever
 * destination sits underneath it).
 *
 * **`Commit` restored 2026-08-14.** Slice 5-6 had a `Commit` command for the pinned "Choose this
 * folder" action row. The design-adoption rebuild (adoption spec §4/§6) dropped it because the new
 * design has no such row anywhere in the Browse screen -- but the browser's ONE production caller
 * (`MimirSettingsCoordinator`'s "add ROMs source" flow, via `FileBrowserStore.open`) is a folder
 * PICKER, not the general-purpose manager the design describes, and dropping `Commit` made its
 * `onChoose` structurally unreachable: a real regression, not a deliberate drop. [FileBrowserMode]
 * reintroduces the distinction the design itself doesn't need to make -- `Commit` fires only when
 * [FileBrowserController]'s caller asked for [FileBrowserMode.PICK] AND the pinned commit affordance
 * (not a tile) currently holds focus; see [FileBrowserController]'s KDoc.
 */
internal sealed interface FileBrowserCommand {
    /** Move focus within the current directory; the authority performs the geometry. */
    data class Move(val action: ControllerAction) : FileBrowserCommand

    /**
     * Jump a whole screenful of rows -- L1/R1. [forward] is R1/PAGE_RIGHT (down the listing).
     *
     * Distinct from [Move] because the authority's directional geometry cannot express it: `moveFocus`
     * resolves ONE geometric neighbour, and there is no "neighbour three rows away" in that vocabulary.
     * The jump is resolved by [fileBrowserPageTarget] against the listing's own row model instead, so
     * this adds no primitive to `core-input` (policy 5's constraint) -- the browser does its own
     * arithmetic and then uses the plain `requestFocus` the authority already offers.
     */
    data class Page(val forward: Boolean) : FileBrowserCommand

    /** Descend into the directory at [path]. */
    data class Enter(val path: String) : FileBrowserCommand

    /** Go to the parent directory. */
    data object Up : FileBrowserCommand

    /** Leave the browser without choosing anything. */
    data object Close : FileBrowserCommand

    /**
     * **Exit with held deletes opens the QUEUE instead of closing.** The final close then happens
     * from the screen listing the deletions that will become permanent.
     *
     * **Why this is a separate command and not a flag on [Close].** `FileBrowserStore.onDismiss` is
     * `clearSession()`, and `clearSession` finalises every held delete (`FileBrowserStore.kt`'s own
     * KDoc: *"They are FINALISED, not undone"*). So [Close] is the press that ends reversibility, and a
     * `Close(reviewFirst = true)` would put the irreversible press and the redirect behind one token,
     * distinguished by a branch a later reader can collapse — exactly the shape [CommitSource] /
     * [CommitExtractDestination] were split apart to avoid (D-3). Two commands means
     * [FileBrowserController.dispatch]'s exhaustive `when` cannot compile without handling each.
     *
     * **Reversible deletes need no confirmation dialog.** The existing delete flow rests on deletes being reversible; every press it governs stays exactly as
     * it was. This one press is where reversibility ends, and the redirect is what marks it — one extra
     * press, only when something is genuinely held, and no dialog anywhere.
     *
     * Produced only when [fileBrowserExitOpensQueue] says so, which requires `queueAvailable` — so
     * [FileBrowserMode.PICK] cannot produce it for the same by-construction reason it cannot produce
     * [Delete] or [OpenQueue], with no mode test of its own.
     */
    data object ReviewHeldDeletes : FileBrowserCommand

    /** [FileBrowserMode.PICK] only: the pinned commit affordance was confirmed **as a ROM source**.
     * Reported to the caller via `onChoose` with the directory open at the moment of activation -- see
     * [FileBrowserController.dispatch]'s own branch, which reads `currentPath` fresh rather than
     * capturing it here (this command carries no path itself for exactly that reason: a path fossilized
     * onto the command at dispatch-table-evaluation time would be one function call staler than the
     * controller's own state by the time the branch runs).
     *
     * **Renamed from `Commit` in the earlier change, and the rename is the point.** The same pinned row now also
     * answers "extract where?" ([CommitExtractDestination]), and one `Commit` command whose meaning came
     * from a `when` over controller state is exactly the shape D-3 forbids: two behaviours behind one
     * token, distinguished by a branch a later reader can collapse. Two commands means
     * [FileBrowserController.dispatch]'s own exhaustive `when` cannot compile without handling each, and
     * there is no arm that can accidentally cover both. */
    data object CommitSource : FileBrowserCommand

    /**
     * **R-21: add THIS game, not the folder it sits in.** Single-game source selection must stay available.
     *
     * A separate command from [CommitSource] rather than a nullable path on it, on this file's own
     * standing rule: *"one `Commit` token whose sense a downstream `when` had to recover would be that
     * defect's next form."* Two meanings, two tokens, and the compiler is what makes a third impossible
     * to add without deciding what it does.
     *
     * **[FileBrowserMode.PICK_IMAGE] produces this same command (2026-09-11), deliberately not a third
     * token.** Its dispatch arm is exactly what an image pick needs -- close, then report the TILE's path
     * -- and the two flows differ only in their gate (`sourcePickAvailable` vs `imagePickAvailable`),
     * which is decided before this command exists. The caller's `onChoose` knows which browser it
     * opened; nothing downstream has to recover a sense from the token.
     */
    data class CommitSourceFile(val path: String) : FileBrowserCommand

    /**
     * The pinned commit affordance was confirmed **as an extract destination** — F-06's "Extract in…",
     * landed. Reachable only while [FileBrowserController] holds a [FileBrowserDestinationPick].
     *
     * Carries no path for [CommitSource]'s reason, and carries no *archive* for a sharper one: the
     * archive belongs to the pick, not to the press. See [FileBrowserExtractChoiceState]'s KDoc — the
     * commit row is what holds focus here, so the browse ring is nowhere near the archive and reading it
     * would extract the wrong file.
     */
    data object CommitExtractDestination : FileBrowserCommand

    /**
     * The pinned commit affordance was confirmed **as a MOVE destination** — G2. Reachable only while
     * [FileBrowserController] holds a [FileBrowserDestinationPick] whose purpose is
     * [FileBrowserCommitPurpose.MOVE_DESTINATION].
     *
     * Carries no path for [CommitSource]'s reason and no *file* for [CommitExtractDestination]'s sharper
     * one: the subject belongs to the pick, not to the press. The commit row is what holds focus here, so
     * the browse ring is nowhere near the file and reading it would move the wrong one — with no press to
     * recover from that, which is why this browser's "never re-read the subject from the ring" rule is at
     * its most load-bearing on exactly this command.
     */
    data object CommitMoveDestination : FileBrowserCommand

    /**
     * The pinned commit affordance was confirmed **as a COPY destination** — G2.
     *
     * **A third commit command rather than a parameter on the second, and this is the D-3 case that most
     * earns it.** `CommitTransferDestination(kind)` would put a move and a copy behind one token, so a
     * downstream `when` arm could cover both and a later reader could collapse the branch. Three commands
     * means [FileBrowserController.dispatch]'s exhaustive `when` cannot compile without handling each, and
     * there is no arrangement in which the arm that runs is not the arm the player's row chose.
     */
    data object CommitCopyDestination : FileBrowserCommand

    /** Flip the image picker between images-only and all files. Changes what is listed, nothing else. */
    data object ToggleImageFilter : FileBrowserCommand

    /** Extract the archive at [path] into its own sibling directory, now — the pre-F-06 behaviour,
     * reported to the caller via `onExtract` and unchanged from slice 5-6.
     *
     * **This is the fail-closed arm as of the earlier change.** It is what `SECONDARY` still produces when the
     * mounting caller wired no destination sinks ([extractChoiceAvailable] false), so a browser built
     * the way every caller written before the earlier change builds one behaves byte-identically to before. When
     * the sinks ARE wired, `SECONDARY` produces [OfferExtract] instead and this command is unreachable. */
    data class Extract(val path: String) : FileBrowserCommand

    /**
     * Decide what Extract *means* for the archive at [path] (F-06): extract here or into a chosen or recognised platform folder.
     *
     * **This command deliberately does not say which destination wins**, because that decision needs the
     * library — `ExtractDestinationPolicy` has to be resolved against the player's real ROM folders and
     * the archive's own volume, which is I/O this pure function must not do and does not have. The
     * *resolution* arrives through [FileBrowserController.resolveExtractOptions] exactly as a delete
     * arrives through `onDelete`; what stays pure is the ROW model ([fileBrowserExtractOptions]) and the
     * "is this worth a chooser at all" rule ([fileBrowserExtractNeedsChooser]), both of which are pinned
     * without any sink at all.
     *
     * Produced only when [extractChoiceAvailable] — same polarity as [Delete] and [OpenQueue], and for
     * the same reason: an unwired sink must not advertise a chooser that opens nothing.
     */
    data class OfferExtract(val path: String) : FileBrowserCommand

    /**
     * Decide what `SECONDARY` *means* for the plain file at [path] (G2): offer Move/Copy for a plain file.
     *
     * **This is the door.** `FileBrowserStore.moveInManager` / `copyInManager` are finished, staged,
     * rollback-backed and tested with **zero production callers**; nothing downstream of this command is
     * new work. Before it, `SECONDARY` on a plain file fell to [Consumed] and the whole engine was
     * unreachable.
     *
     * **FILES ONLY, and the refusal for directories is a measurement rather than a preference.** QA drove
     * a parent-into-descendant move in `core-files` and watched it run away: 9x amplification in 34 ms, no
     * termination, no free-space check, no cancel. `ManagedFileOperations.move`'s only self-guard is exact
     * path equality, which does not catch `/Roms/PS2` -> `/Roms/PS2/PS2`. The refusal belongs in
     * `core-files` and does not exist yet, so a DIRECTORY tile produces [Consumed] here exactly as it
     * always has. An ARCHIVE keeps [OfferExtract]: the destination question for an archive is *"where does
     * this platform's game content live?"*, and the operation that produces content there is extract, not
     * move (moving an archive as a file-management act belongs to selection mode, G3).
     *
     * Produced only when [transferChoiceAvailable] — same fail-closed polarity as [OfferExtract]'s
     * `extractChoiceAvailable` and [Delete]'s `deleteAvailable`, and the same consequence: with it false
     * there is **no** value of any other argument that produces this command, so a browser whose caller
     * wired no transfer sinks is *incapable* of offering the chooser rather than merely unlikely to.
     */
    data class OfferTransfer(val path: String) : FileBrowserCommand

    /**
     * Unwind one step of a destination pick: back to the chooser the pick came from — F-06, extended by G2.
     *
     * Produced by `CANCEL` only in [FileBrowserDestinationPickPhase.SETTLED]; in
     * [FileBrowserDestinationPickPhase.AWAY] the same press is still a plain [Up]. See that enum's KDoc
     * for why the two levels are ordered that way and what the ordering costs.
     *
     * **Renamed from `BackToExtractChooser` in G2 slice 1, and the rename is the honest half of a decision
     * that was NOT to split this command.** The three commit commands above are split per verb because
     * they reach three different sinks; this one is not, because it has exactly one effect —
     * *"unwind the open pick to the chooser it came from"* — and **which** chooser reopens is data carried
     * on the pick ([FileBrowserDestinationPick.purpose]), not a decision this press makes. The rejected
     * alternative was a second `BackToTransferChooser` arm, which would have forced the pick's purpose
     * into [fileBrowserCommand] as a parameter beside the phase — two values that can disagree about one
     * pick, which is the representable-illegal-state shape [FileBrowserCommitPurpose]'s own KDoc rejects.
     * Keeping the purpose out of the table entirely means the table and the controller cannot disagree.
     * [FileBrowserController]'s branch is an exhaustive `when` over [FileBrowserCommitPurpose], so a
     * fourth purpose cannot be added without answering for this press too.
     */
    data object BackToChooser : FileBrowserCommand

    /**
     * F-2. Hops focus sideways within the pinned row, commit affordance -> its "Back to origin" sibling
     * -- policy 2026-08-18. A HAND-COMPUTED hop (the controller does `requestFocus` directly on
     * the known sibling key, [FileBrowserCommand.Page]'s own precedent), never [Move] / geometric
     * `moveFocus`: see the NAV_LEFT/NAV_RIGHT branch's comment for the recorded hazard (an asymmetric
     * row teleports focus into the tile grid under raw scoring) this sidesteps by construction rather
     * than by trusting the scorer's current numbers to keep favouring the sibling.
     */
    data object FocusBackToOrigin : FileBrowserCommand

    /** The return hop, F-2's "Back to origin" row -> the commit affordance beside it. Mirrors
     * [FocusBackToOrigin] exactly, the other direction. */
    data object FocusCommitRow : FileBrowserCommand

    /**
     * Open the search screen (F-08).
     *
     * Produced only when the mounting caller actually wired a search provider ([searchAvailable]); an
     * unwired browser answers TERTIARY with [Consumed] instead, because a search screen with no engine
     * behind it would report "nothing here by that name" about a card full of games. Same polarity as
     * SECONDARY, which is inert on a tile that cannot be extracted rather than opening a dead dialog.
     */
    data object OpenSearch : FileBrowserCommand

    /**
     * Open the SELECT action overlay for the tile at [path] of tile-kind [kind] — **F-1, `SYSTEM_SELECT`**,
     *  (R-3). Delete moved INSIDE the overlay as a row rather than staying SELECT's own direct
     * effect — see `FileBrowserActionOverlayChoice.kt`.
     *
     * **Replaces `Delete(path)`, retired in the same change — its duties, ledgered rather than dropped
     * silently:** deleting a tile is still exactly what it was (reported to [FileBrowserController]'s
     * `onDelete`, staged, held for Undo, nothing destroyed at the moment of the press) — it now happens
     * from `FileBrowserController.activateFocusedActionOverlayOption`'s DELETE arm, the same
     * perform-then-report shape [DiscSetMoveOption.MOVE_WHOLE_SET]/`MOVE_SUBJECT_ONLY` already use for a
     * row activation that is not itself a [FileBrowserCommand]. Nothing downstream of `onDelete` changed;
     * only the door the press walks through did.
     *
     * **Carries [kind] as well as [path]**, unlike every other path-carrying command here, because the
     * overlay's row set ([fileBrowserActionOverlayRows]) is kind-partitioned and the tile that produced
     * this command is the only place that kind is known — recovering it downstream would mean a second,
     * possibly-stale partition read against a listing the player may have already left.
     *
     * **Why SELECT and not a face button.** Every face button in this browser is already taken — A
     * open/choose, B back/close, X extract/move/copy (now retired, R-13), Y search — and the shoulders
     * page. L2/R2 stay swallowed ([ControllerAction.TRIGGER_LEFT]'s own KDoc warns of the missing-key-up
     * dual-source hazard). What made SELECT the right one rather than merely the free one still holds: it
     * already meant "remove this from my view" in this app (the native shelf's HIDE), and every verb this
     * overlay now carries is a bigger job for the same button, not a new meaning grafted onto it.
     *
     * Produced only when [actionsAvailable] and a TILE holds focus. Never while the [FileBrowserMode.PICK]
     * commit affordance holds focus — that control has no path, and [focused] is null throughout the
     * interval it is focused (see the CONFIRM branch), so the tile check alone is what enforces it. See
     * [actionsAvailable]'s own parameter KDoc for why PICK can never produce this at all.
     */
    data class OpenActionOverlay(val path: String, val kind: FileBrowserTileKind) : FileBrowserCommand

    /**
     * Open the queue screen — **F-01, `SYSTEM_START`**.
     *
     * **This keeps the property that made START worth swallowing in the first place.** `SYSTEM_START` is
     * consumed here on purpose: a hard modal must not let START open Settings from underneath it. Binding
     * it to a screen this browser owns changes nothing about that — the press is still consumed, still
     * never falls through — while giving it a job instead of a silence.
     */
    data object OpenQueue : FileBrowserCommand

    /** Swallowed on purpose. The browser is a hard modal and never lets input fall through. */
    data object Consumed : FileBrowserCommand
}

/**
 * The browser's entire input contract, as a pure function: state (the focused tile, whether we are at
 * the volume root) plus one [ControllerAction] in, exactly one [FileBrowserCommand] out. No
 * [com.gruning.mimir.rewrite.core.input.FocusAuthority], no I/O, no Compose type anywhere in this
 * function's signature or body.
 *
 * **Two deliberate reversals from slice 5-6's own dispatch table** (adoption spec §5: *"← → ↑ ↓
 * navigate ... Everything reachable by D-pad or touch"* -- directions ONLY navigate now):
 *  - `NAV_LEFT` no longer means "go up a directory" below the root. It is a plain directional [Move],
 *    exactly like the other three -- [FocusAuthority.moveFocus][
 *    com.gruning.mimir.rewrite.core.input.FocusAuthority.moveFocus]'s own geometric scoring handles a
 *    grid's left edge by returning false, same as any other edge.
 *  - `NAV_RIGHT` no longer enters a focused directory; on the controller only `CONFIRM` opens. **The
 *    reason originally written here is half retired.** It cited the adoption spec §5 sentence *"Single
 *    tap inspects. Double tap or Enter opens"* and pointed at a matching touch table below. That touch
 *    rule was retired on 2026-08-14 and its table is deleted (see the note at the foot of
 *    this file), so the sentence no longer supports anything here. What survives stands on its own and
 *    needs no spec quotation: in a 2-D grid, RIGHT should move right. Whether RIGHT should ALSO open is
 *    a separate navigation decision which this change did not answer -- the
 *    controller table is byte-for-byte the one that shipped.
 *
 * **`SECONDARY`'s reach, corrected by G2 rather than merely widened.** This paragraph used to say it
 * *"stays inert on everything except an [FileBrowserTile] whose `extractable` is true -- including tomes
 * and files with no archive kind"*, and that sentence is now false for half of what it names. It reaches
 * an **archive** (the extract chooser, unchanged) and a **plain file** (the transfer chooser, and only
 * when [transferChoiceAvailable]); it is still inert on a **directory**, on the pinned commit affordance,
 * and for the whole duration of a destination pick. `CANCEL`/`SYSTEM_BACK` stay identical at every depth,
 * matching every other modal surface in this tree.
 *
 * **Two additions, 2026-08-14** (both were on this module's own owed list):
 *  - `PAGE_LEFT`/`PAGE_RIGHT` are no longer swallowed. They page by one screenful of rows, measured in
 *    dp by [fileBrowserPageStride] against the adoption spec's stated 833x468dp Odin viewport -- the
 *    measurement slice 5-6 was missing. A screenful is a different NUMBER of rows in each section
 *    (three file rows, two tome rows), which is why the stride is measured rather than counted; see
 *    [FileBrowserTomeRowPitchDp]. No `core-input` primitive was added (policy 5):
 *    [FileBrowserCommand.Page] is resolved by this module's own row arithmetic and applied with the
 *    authority's existing `requestFocus`.
 *  - `NAV_LEFT`/`NAV_RIGHT` are consumed while the PICK-mode commit affordance holds focus. See that
 *    branch's own comment -- a full-width row has no horizontal neighbour, and letting the geometry
 *    answer anyway teleported the player into the middle of the tome row.
 *
 * **Two more, the earlier change (F-01): `SYSTEM_SELECT` -> [FileBrowserCommand.Delete], `SYSTEM_START` ->
 * [FileBrowserCommand.OpenQueue].** Both were previously in the `else` arm's blanket swallow; both are
 * gated by a parameter defaulting **false**, so every caller written before this change keeps the exact
 * behaviour it had. That default is not a convenience -- it is how [FileBrowserMode.PICK] is made
 * *incapable* of deleting rather than merely restricted from it: with
 * [deleteAvailable] false there is no value of any other argument that produces a
 * [FileBrowserCommand.Delete].
 *
 * **Three more, the earlier change (F-06).** `SECONDARY` splits into [FileBrowserCommand.Extract] (unwired, today's
 * behaviour) and [FileBrowserCommand.OfferExtract] (wired) on [extractChoiceAvailable]; `CONFIRM` on the
 * pinned commit row splits into [FileBrowserCommand.CommitSource] and
 * [FileBrowserCommand.CommitExtractDestination] on [focusedCommitPurpose]; and `CANCEL` grows a level
 * ([destinationPick]). All three follow the established polarity — the new parameters default to the
 * value that reproduces the earlier table exactly, so every caller written before this change keeps
 * the behaviour it had, and `extractChoiceAvailable = false` makes an unwired browser *incapable* of
 * producing [FileBrowserCommand.OfferExtract] rather than merely unlikely to.
 *
 * @param actionsAvailable **F-1.** Whether SELECT would find at least one legal overlay row on [focused]
 *   — [fileBrowserActionsAvailable], read from here and nowhere re-derived. False in PICK, always: that
 *   function is `manageMode`-gated at the door (the incapable-rather-than-restricted
 *   shape), so PICK cannot produce [FileBrowserCommand.OpenActionOverlay] whatever else is true — the
 *   identical polarity [deleteAvailable] used to hold directly, now inherited through the shared gate.
 *   Also false for the whole duration of a destination pick, folded into the gate rather than checked a
 *   second time here: the overlay's own MOVE/COPY rows would otherwise be reachable mid-pick and arm a
 *   SECOND pick on top of the one already open, clobbering its origin snapshot — the identical hazard
 *   [destinationPick]'s own SECONDARY guard exists for.
 * @param queueAvailable the mounting caller wired a real queue AND this is [FileBrowserMode.MANAGE].
 *   Same polarity, and the same reason [searchAvailable] exists rather than being assumed: a queue screen
 *   with no queue behind it would report "nothing in progress" over a delete the player is still holding
 *   an Undo for.
 * @param focusedCommitPurpose what the pinned commit affordance commits **if it currently holds focus**,
 *   null otherwise — replacing the earlier change's `commitFocused: Boolean`. One nullable value rather than a
 *   boolean plus a purpose; see [FileBrowserCommitPurpose]'s KDoc for why the two-parameter form was
 *   rejected.
 * @param focusedBackToOrigin F-2: whether the pinned row's "Back to origin" sibling currently holds
 *   focus. A plain boolean, unlike [focusedCommitPurpose] — this row has exactly one meaning (unwind the
 *   open pick, [FileBrowserCommand.BackToChooser]) whenever it exists at all, so there is no second axis
 *   to fold in.
 * @param backToOriginAvailable whether that sibling EXISTS in the directory currently open, regardless of
 *   which key currently holds focus — read only by NAV_LEFT/NAV_RIGHT, to decide whether RIGHT off the
 *   commit affordance has anywhere real to go. False by default, which reproduces the pre-F-2 table
 *   exactly: with no sibling, both horizontal directions stay consumed on the commit row precisely as
 *   they always have.
 * @param extractChoiceAvailable the mounting caller wired BOTH an options resolver and an extract-to
 *   sink. False by default, and false is what keeps `SECONDARY` on the pre-F-06 path.
 * @param transferChoiceAvailable the mounting caller wired BOTH a move sink and a copy sink — G2. False
 *   by default, and false is what keeps `SECONDARY` inert on a plain file exactly as it was before G2.
 *   **Both or neither, never one**: a browser with a move sink and no copy sink would draw a two-row
 *   chooser whose second row is focusable, advertised and dead — the same failure
 *   [FileBrowserController]'s two extract sinks are a pair for.
 * @param destinationPick how much of a destination pick is left to unwind, or null when none is open —
 *   see [FileBrowserDestinationPickPhase]. Only `CANCEL`/`SYSTEM_BACK` and `SECONDARY` read it.
 * @param heldDeleteCount how many deletes the queue is still holding an Undo for — W-1. Zero by
 *   default, which is the value that reproduces the pre-W-1 table exactly. Read only by
 *   `CANCEL`/`SYSTEM_BACK`, and only through [fileBrowserExitOpensQueue].
 */
internal fun fileBrowserCommand(
    action: ControllerAction,
    focused: FileBrowserTile?,
    atRoot: Boolean,
    focusedCommitPurpose: FileBrowserCommitPurpose? = null,
    focusedBackToOrigin: Boolean = false,
    backToOriginAvailable: Boolean = false,
    searchAvailable: Boolean = false,
    actionsAvailable: Boolean = false,
    queueAvailable: Boolean = false,
    extractChoiceAvailable: Boolean = false,
    transferChoiceAvailable: Boolean = false,
    destinationPick: FileBrowserDestinationPickPhase? = null,
    heldDeleteCount: Int = 0,
    /**
     * **R-21, **
     *
     * True when this browser is choosing a library SOURCE — `CHOOSE_SOURCE`, the add-games flow. It is
     * the gate that lets CONFIRM commit a focused file, and it exists as its own parameter rather than
     * being read off [focusedCommitPurpose] because those are different questions:
     * [focusedCommitPurpose] is non-null only while the pinned affordance HOLDS focus, and the whole
     * point of this arm is the moment a TILE holds it instead.
     */
    sourcePickAvailable: Boolean = false,
    /**
     * **[FileBrowserMode.PICK_IMAGE], **
     *
     * True when the focused tile is a FILE whose extension is an image AND this browser is choosing an
     * image -- [fileBrowserFileChoosable], computed by the controller and threaded here. Its own
     * parameter rather than a second reading of [sourcePickAvailable] because the two answer different
     * questions ("which game?" / "which picture?") and because every caller written before it must keep
     * its exact table, which the `false` default guarantees. Produces the same
     * [FileBrowserCommand.CommitSourceFile] R-21 built: the path is the tile's, the arm closes then
     * reports, and no other arm of this table reads it.
     */
    imagePickAvailable: Boolean = false,
): FileBrowserCommand = when (action) {
    ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> FileBrowserCommand.Move(action)

    // Horizontal moves are inert while the pinned commit affordance holds focus: with no sibling in the
    // row it has no horizontal neighbour to move to. Without this, FocusAuthority's geometric scoring
    // answers the press with whatever tile lies furthest right of the row's synthetic centre -- NOT the
    // first tile, but the SECOND (or third) column, because the commit row's bounds are column-0-aligned
    // for NAV_DOWN determinism (see fileBrowserCommitVirtualBounds). Pressing RIGHT on "Use this folder"
    // would drop the player into the middle of the tome row having skipped its first tile entirely, while
    // LEFT did nothing at all: a silent, asymmetric teleport. Vertical moves are left alone -- UP/DOWN off
    // this row are real and wanted (it is reachable by D-pad, and must be leavable the same way).
    //
    // **F-2, 2026-08-18: the row can now have a real sibling ("Back to origin"), and the hop to and from
    // it is HAND-COMPUTED here, never handed to raw geometric `Move`.** Checked against the scorer's own
    // formula, not assumed: RIGHT-from-commit and LEFT-from-back both resolve correctly under
    // `moveFocus` (the sibling is the nearest candidate by a wide margin), but RIGHT-from-back does NOT --
    // with no third column to its right, the scorer reaches past the pinned row entirely and finds a tome
    // column two rows down, the exact teleport this comment already describes, recurring on the new key's
    // open edge. Trusting the scorer for half the cases and distrusting it for the other half is fragile
    // (a future row-pitch change could silently flip which candidate wins); every hop in this row is
    // therefore explicit, and NONE of the four falls through to [FileBrowserCommand.Move].
    ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> when {
        focusedCommitPurpose != null && backToOriginAvailable && action == ControllerAction.NAV_RIGHT ->
            FileBrowserCommand.FocusBackToOrigin
        focusedBackToOrigin && action == ControllerAction.NAV_LEFT -> FileBrowserCommand.FocusCommitRow
        focusedCommitPurpose != null || focusedBackToOrigin -> FileBrowserCommand.Consumed
        else -> FileBrowserCommand.Move(action)
    }

    // L1/R1 jump a screenful of rows -- a screenful measured in dp, not a fixed row count, because a
    // tome row and a file row are different heights. Grounded in the adoption spec's 833x468dp Odin
    // viewport, not invented; see fileBrowserPageStride for the walk and FileBrowserTomeRowPitchDp for
    // the arithmetic. Slice 5-6 left this unimplemented for exactly the want of that measurement.
    ControllerAction.PAGE_LEFT -> FileBrowserCommand.Page(forward = false)
    ControllerAction.PAGE_RIGHT -> FileBrowserCommand.Page(forward = true)

    // The pinned commit affordance is never a FileBrowserTile (no backing FileItem -- see
    // FileBrowserController's KDoc), so [focused] is always null while it holds focus; checked first,
    // ahead of the tile-kind branch below, rather than folded into it.
    //
    // **The two commit arms are separate COMMANDS, not one command plus a flag** (D-3). UI-05 was a row
    // that rendered, registered bounds, took focus, advertised "A - CHOOSE" and then swallowed CONFIRM
    // because two places disagreed about whether it existed; the same row now also has two possible
    // MEANINGS, and one `Commit` token whose sense a downstream `when` had to recover would be that
    // defect's next form. The compiler enforces the distinction instead.
    // F-2. Checked BEFORE focusedCommitPurpose, not folded into its `when`: the back-to-origin row is
    // never a commit purpose (it commits nothing -- it unwinds the pick), and it is reachable at ANY
    // destination-pick phase (AWAY included), not only the SETTLED phase B is gated on below. Same
    // command B already produces once SETTLED -- FileBrowserController.abandonDestinationPick already
    // restores the origin's stack, focus memory and glide and reopens the right chooser for whichever
    // verb is open; this row's only job is to reach it from depths B cannot reach in one press.
    ControllerAction.CONFIRM -> if (focusedBackToOrigin) {
        FileBrowserCommand.BackToChooser
    } else {
        when (focusedCommitPurpose) {
            FileBrowserCommitPurpose.CHOOSE_SOURCE -> FileBrowserCommand.CommitSource
            // The same command: close, then `onChoose(currentPath)`. The caller decides what the folder IS.
            FileBrowserCommitPurpose.CHOOSE_MUSIC_FOLDER -> FileBrowserCommand.CommitSource
            FileBrowserCommitPurpose.EXTRACT_DESTINATION -> FileBrowserCommand.CommitExtractDestination
            // G2. Four purposes, four commands, one `when` -- the compiler is what guarantees a fifth
            // purpose cannot be added without deciding what CONFIRM does with it, and what makes it
            // impossible for a Move question to be answered by a Copy.
            FileBrowserCommitPurpose.MOVE_DESTINATION -> FileBrowserCommand.CommitMoveDestination
            FileBrowserCommitPurpose.COPY_DESTINATION -> FileBrowserCommand.CommitCopyDestination
            FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER -> FileBrowserCommand.ToggleImageFilter
            null -> when (focused?.kind) {
                FileBrowserTileKind.DIRECTORY -> FileBrowserCommand.Enter(focused.path)
                // **R-21.** *"A plain file is not actionable at all this slice"* was true when this line
                // was written and stopped being true on 2026-08-13, when the add-games route moved from
                // the system picker to this browser. That change was made for fan stability -- every hop to
                // documentsui slams the Odin's to 50% of the PWM period -- and narrowed what a source can
                // be on the way past, because the picker it replaced could take a single file and this
                // browser could only ever commit `currentPath`.
                //
                // Gated on [sourcePickAvailable], so it changes NOTHING in MANAGE or in a destination
                // pick: a file is still inert everywhere it was, and actionable only in the one flow whose
                // whole job is answering "which game?".
                //
                // ARCHIVE is included deliberately. A zipped ROM is a single game to every emulator that
                // reads one, and refusing it here would reproduce the same defect one file type over.
                //
                // PICK_IMAGE (2026-09-11) joins this arm through [imagePickAvailable]. It is already false
                // on an ARCHIVE by construction -- no archive extension is an image -- so the two gates
                // never widen each other: a source pick still takes a zipped ROM, an image pick never does.
                FileBrowserTileKind.ARCHIVE, FileBrowserTileKind.FILE ->
                    if (sourcePickAvailable || imagePickAvailable) {
                        FileBrowserCommand.CommitSourceFile(focused.path)
                    } else {
                        FileBrowserCommand.Consumed
                    }
                null -> FileBrowserCommand.Consumed
            }
        }
    }

    // F-06. THREE levels of CANCEL now (destination-pick -> chooser -> browse), and the chooser's own
    // level is not here at all -- it is [FileBrowserController.dispatchExtract], which runs AHEAD of this
    // table for exactly the reason dispatchQueue does: B on the chooser must close the chooser before it
    // can ever mean "go up a directory".
    //
    // Within a pick, the player's OWN navigation unwinds first and the pick unwinds last. See
    // [FileBrowserDestinationPickPhase] for the decision and the alternative that was rejected. Note that
    // [FileBrowserCommand.Close] is unreachable while a pick is open -- deliberate, and costed there.
    //
    // AWAY at the root cannot occur (the controller answers SETTLED for it), and if a caller ever passed
    // it anyway the resulting Up is a harmless no-op on an empty stack rather than a surprise close.
    //
    // **W-1: at the root, with deletes held, the press goes to the QUEUE rather than out.** The root
    // arm is the only one that changes; every depth below it still walks Up, and with nothing held the
    // root press is still the identical Close it has always been. The condition is
    // [fileBrowserExitOpensQueue] rather than an inline `&&` chain because
    // [fileBrowserFooterHints] has to draw the word for THIS press and the two must not be able to
    // disagree -- the same argument the SYSTEM_SELECT branch's `focused != null` makes for the DELETE
    // hint reading the dispatch table's own check instead of a copy of it.
    ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> when (destinationPick) {
        FileBrowserDestinationPickPhase.SETTLED -> FileBrowserCommand.BackToChooser
        FileBrowserDestinationPickPhase.AWAY -> FileBrowserCommand.Up
        null -> when {
            fileBrowserExitOpensQueue(
                atRoot = atRoot,
                destinationPick = destinationPick,
                queueAvailable = queueAvailable,
                heldDeleteCount = heldDeleteCount,
            ) -> FileBrowserCommand.ReviewHeldDeletes
            atRoot -> FileBrowserCommand.Close
            else -> FileBrowserCommand.Up
        }
    }

    // [focused] is always null while the commit affordance holds focus (see the CONFIRM branch above),
    // so SECONDARY is inert there automatically -- matching slice 5-6's own pinned-action-row precedent
    // ("SECONDARY is inert on everything except an extractable row -- including... the pinned action
    // row") with no extra commit check needed here.
    //
    // **F-06, and the destination-pick guard is a correctness gate rather than tidiness.** A second
    // SECONDARY while a pick is open would resolve a second archive's options and, on CHOOSE_FOLDER,
    // overwrite the pending pick's origin snapshot -- so cancelling would restore the WRONG directory,
    // and the archive the player thought they were extracting would silently change. The press is
    // swallowed instead; the player answers or abandons the question in front of them first.
    // [fileBrowserFooterHints] drops the EXTRACT hint for the same interval, so nothing is advertised
    // that does nothing.
    //
    // **G2 widens this gate and displaces nothing.** Before it, a plain FILE fell straight to Consumed --
    // SECONDARY was inert there, and `FileBrowserStore.moveInManager`/`copyInManager` had no production
    // caller at all. The archive arm below is byte-for-byte the one that shipped; the file arm is new and
    // fails closed on [transferChoiceAvailable] exactly as the archive arm does on
    // [extractChoiceAvailable]. The two are mutually exclusive by CONSTRUCTION rather than by ordering:
    // `FileBrowserTile.extractable` is derived as `kind == ARCHIVE`, so no tile can satisfy both.
    ControllerAction.SECONDARY -> when {
        destinationPick != null -> FileBrowserCommand.Consumed
        // X is the single entry to the actions overlay, where the thumb already rests; SELECT does
        // not duplicate it. PICK keeps its archive-extraction path, while MANAGE offers file actions
        // through the overlay. actionsAvailable remains the legal-overlay-row gate.
        actionsAvailable && focused != null ->
            FileBrowserCommand.OpenActionOverlay(focused.path, focused.kind)
        actionsAvailable -> FileBrowserCommand.Consumed
        focused?.extractable == true ->
            // The gate that keeps an unwired browser on the pre-F-06 path, exactly as `deleteAvailable`
            // keeps a PICK browser incapable of deleting: there is no value of any other argument that
            // produces OfferExtract with this false.
            if (extractChoiceAvailable) {
                FileBrowserCommand.OfferExtract(focused.path)
            } else {
                FileBrowserCommand.Extract(focused.path)
            }
        // FILES ONLY. `kind == FILE` excludes the DIRECTORY tile deliberately and permanently for this
        // slice -- see [FileBrowserCommand.OfferTransfer] for the measured runaway that refusal is made
        // of. There is no `else` arm for a file here: with the sinks unwired the press stays Consumed,
        // which is precisely the pre-G2 behaviour, because unlike extract there is no legacy single-sink
        // path a transfer could fall back to.
        // R-19 widened FILE to FILE-or-ARCHIVE. This arm and the overlay's row model read the SAME set
        // ([FileBrowserTransferableTileKinds]) rather than two copies of one rule -- the schema-ceiling
        // failure mode where one fact written in five places drifted apart.
        focused != null && focused.kind in FileBrowserTransferableTileKinds && transferChoiceAvailable ->
            FileBrowserCommand.OfferTransfer(focused.path)
        else -> FileBrowserCommand.Consumed
    }

    // F-08. TERTIARY (FACE_Y — `ControllerKeymap.kt:40`) is search's controller path, and it is the ONLY
    // binding this surface added for it.
    //
    // **Why a face button rather than a focusable header icon.** The design's header carries a magnifier
    // and its interaction model is "everything reachable by D-pad or touch" — so the icon is drawn and
    // takes a tap (`FileBrowserHeader`), exactly like the breadcrumbs beside it already do. Putting it in
    // the FOCUS GRAPH as well was considered and rejected: it would need synthetic bounds above the
    // commit row, a NAV_LEFT/RIGHT consumption rule of its own, a `fileBrowserScrollTargetIndex` case, and
    // a paging row that is not a `LazyColumn` row — four additions to the exact geometry the earlier change has just
    // finished stabilising, to reach a control a face button reaches in one press from anywhere in the
    // listing. The function is D-pad reachable; the icon is a touch shortcut to it.
    //
    // **Advertised, never silent.** [fileBrowserFooterHints] draws "Y SEARCH" whenever this is live —
    // this file's own standing rule that a binding with no legend entry is worse than no binding.
    //
    // TERTIARY was previously named in `FileBrowserController`'s owed list as "the natural binding" for
    // the inspector's Verify. Taking it here is a real trade, stated rather than quiet: search is the item
    // needed for the search shortcut and Verify already has a working touch path.
    ControllerAction.TERTIARY ->
        if (searchAvailable) FileBrowserCommand.OpenSearch else FileBrowserCommand.Consumed

    // F-1/R-3, 2026-08-18. SELECT opens the action overlay on the focused TILE -- Place/Copy/Extract/
    // Delete, partitioned by kind (FileBrowserActionOverlayChoice.kt). Two conditions, both required, and
    // each one is a different invariant:
    //  - [actionsAvailable] is the MODE-and-CONTENT gate. PICK must be
    //    *incapable*, not restricted, and [fileBrowserActionsAvailable] enforces that at the door rather
    //    than only inside the overlay's own rows -- it defaults false in this signature, so a caller that
    //    does not opt in cannot produce this command at all, exactly the polarity [deleteAvailable] used
    //    to enforce directly for the command this one replaces.
    //  - `focused != null` is the TARGET gate. The overlay needs a path, and the PICK commit affordance has
    //    none. [focused] is null throughout the interval that affordance holds focus (see the CONFIRM
    //    branch above), so this single check covers it without a second `commitFocused` test -- exactly
    //    the argument SECONDARY already makes for itself. ([actionsAvailable] already implies this --
    //    [fileBrowserActionsAvailable] answers false on a null tile -- kept explicit so the compiler can
    //    smart-cast `focused` below rather than for a case this couldn't otherwise reach.)
    // Falls to Consumed otherwise, the same polarity SECONDARY and TERTIARY use: a press with nothing to
    // act on is swallowed by the modal, never passed down.
    // **D-3: SELECT goes quiet.** It held delete before F-1 and the overlay after it; it now holds nothing, because X does. Swallowed rather than unbound, which is this table's
    // standing polarity: a press with nothing to act on is absorbed by the modal, never passed down to
    // whatever is underneath; otherwise stray input could reach Files.
    ControllerAction.SYSTEM_SELECT -> FileBrowserCommand.Consumed

    // F-01. START opens the queue -- and when [queueAvailable] is false it stays exactly as swallowed as
    // it was before this binding existed, which is what makes the hard-modal property survive the change.
    ControllerAction.SYSTEM_START ->
        if (queueAvailable) FileBrowserCommand.OpenQueue else FileBrowserCommand.Consumed

    // Everything else (both triggers, every remaining SYSTEM_*/GLOBAL_* action) is swallowed. This
    // browser is a hard modal: an action falling through to the destination underneath would tear the
    // player out of a browse session they are halfway through -- which is why SYSTEM_START above is bound
    // to a screen this browser OWNS rather than being allowed to reach Settings.
    // TRIGGER_LEFT/TRIGGER_RIGHT stay swallowed even though the shoulders are bound -- L2/R2 are the
    // dual-source hazard ControllerAction.TRIGGER_LEFT's own KDoc warns about (a missing key-up), and this
    // surface has no third bulk gesture that needs them.
    else -> FileBrowserCommand.Consumed
}

/**
 * Whether B/`CANCEL` opens the **queue** instead of leaving the browser — **W-1, and the one condition
 * both the dispatch table and the footer legend read.**
 *
 * The finding this exists to close: `Close` -> `onDismiss` -> `FileBrowserStore.clearSession` ->
 * `finalizeDelete` on every held deletion, under a footer that said `CLOSE` and nothing else. The fix is a redirect, not a dialog — the press lands on the screen that lists what is about to become
 * permanent, and he closes from there ([FileBrowserQueuePurpose.EXIT_REVIEW]).
 *
 * **All four terms are load-bearing, and none of them is a mode check:**
 *  - [atRoot] — below the root the press is [FileBrowserCommand.Up] and finalises nothing, so there is
 *    nothing to mark. Unchanged at every depth.
 *  - [destinationPick] — mid-pick the press cannot close the browser at all (see
 *    [FileBrowserDestinationPickPhase]), so a redirect there would answer a press that was never leaving.
 *  - [queueAvailable] — you cannot send a player to a screen that does not exist. This is also what makes
 *    the redirect **[FileBrowserMode.PICK]-proof by construction**: `fileBrowserQueueAvailable` is MANAGE
 *    -only and a PICK session's `FileBrowserStore.operations` is null, so PICK can neither hold a
 *    deletion nor reach this branch, with no second `mode ==` test anywhere.
 *  - [heldDeleteCount] — **nothing changes when nothing is held.** With zero, this is false and B is the
 *    byte-identical `Close` it always was. The count comes from `FileOperationQueue.heldDeletions`, the
 *    same read `clearSession` walks, so the word and the consequence cannot disagree.
 *
 * **Fails toward the old behaviour, deliberately.** If the queue sink is somehow absent while deletes are
 * held (unreachable in production — `FileBrowserQueueSink` installs it for the browser's whole life, and
 * `setQueueAction(null)` closes the screen), this answers false and the press closes and finalises as it
 * did before W-1. A browser that cannot be left is a worse defect than an unmarked exit; the honest
 * alternative would be to refuse the exit, and that is not an alternative.
 */
internal fun fileBrowserExitOpensQueue(
    atRoot: Boolean,
    destinationPick: FileBrowserDestinationPickPhase?,
    queueAvailable: Boolean,
    heldDeleteCount: Int,
): Boolean = atRoot && destinationPick == null && queueAvailable && heldDeleteCount > 0

/**
 * Whether SELECT would open the action overlay for [focused] right now — **F-1, the one condition
 * `SYSTEM_SELECT`, SECONDARY's quiet-down (R-13), the browse footer legend and the inspector's re-pointed
 * Extract…/Place… buttons all read**, on [fileBrowserExitOpensQueue]'s own precedent for why this is a
 * function and not a copy of its terms scattered across four call sites: "the word and the consequence
 * cannot disagree" only if they trace to one predicate.
 *
 * **Deliberately a pure top-level function, not a `FileBrowserController` getter.** A getter would read
 * `focusedTile()` — a LIVE, synchronous re-read of `authority.state.value` — while
 * [FileBrowserSurface]'s footer-legend and inspector call sites already work from a *collected* snapshot
 * of the same state. That mismatch is the exact asymmetry `focusedTileAt`'s own KDoc names and F-09's
 * bug lived in: a caller on the live side and a caller on the snapshot side can each pass their own
 * [focused] here and get an answer consistent with what THEY are looking at, rather than one of them
 * silently reading the other's.
 *
 * @param focused the tile SELECT would act on, however the caller obtained it (live or collected).
 * @param manageMode `mode == FileBrowserMode.MANAGE`. Checked first and fails closed: The safety boundary requires PICK to be *incapable*, not merely unlikely, of opening a menu that offers Delete —
 *   the identical polarity [FileBrowserController.deleteAvailable] already enforces for the command this
 *   gate replaces.
 * @param deleteAvailable `FileBrowserController.deleteAvailable`, read and not re-derived — MANAGE plus a
 *   real delete sink.
 * @param transferChoiceAvailable `FileBrowserController.transferChoiceAvailable`, read and not re-derived —
 *   MANAGE plus both transfer sinks.
 * @param renameAvailable `FileBrowserController.renameAvailable`, read and not re-derived -- MANAGE plus the rename
 *   sink. Defaults false so a caller that wired no rename keeps the answer it always had.
 * @param destinationPick mid-pick, SELECT must not open the overlay: its own Move/Copy rows call
 *   `FileBrowserController.beginTransferPick`, which would overwrite the ALREADY-open pick's origin
 *   snapshot — byte-for-byte the hazard `SECONDARY`'s own `destinationPick != null` guard exists for, one
 *   screen up. Folded into this gate rather than checked a second time at each call site.
 */
internal fun fileBrowserActionsAvailable(
    focused: FileBrowserTile?,
    manageMode: Boolean,
    deleteAvailable: Boolean,
    transferChoiceAvailable: Boolean,
    destinationPick: FileBrowserDestinationPickPhase?,
    renameAvailable: Boolean = false,
): Boolean {
    if (focused == null || !manageMode || destinationPick != null) return false
    return fileBrowserActionOverlayRows(focused.kind, deleteAvailable, transferChoiceAvailable, renameAvailable).isNotEmpty()
}

// ─────────────────────────────────────────────────────────── the touch table, and why there isn't one ──
//
// **DELETED 2026-08-14: `FileBrowserTapCommand` / `fileBrowserTapCommand`.** This file used to carry a
// SECOND decision table, parallel to [fileBrowserCommand] above, that answered "what does a tap do" with
// its own two-case sealed interface and a `doubleTap: Boolean`. It is gone, and the deletion is the fix
// rather than a tidy-up.
//
// A tap is now routed by [FileBrowserController.handleTap] straight through [fileBrowserCommand]'s own
// `CONFIRM` arm, and a long-press through its `SECONDARY` arm. So this file has exactly ONE table again,
// the compiler enforces its exhaustiveness, and every gate the controller table already carries
// (`deleteAvailable`, `extractChoiceAvailable`, the destination-pick guard, the commit-row branch) applies
// to touch without being restated -- which is the property the old table's KDoc claimed and could not
// deliver, because it duplicated the CONFIRM decision instead of calling it.
//
// **Do not reintroduce a tap-shaped parameter here.** A `doubleTap` flag left in place "for
// compatibility" is how the next reader re-derives the workaround, and the gesture that fed it imposed a
// full platform double-tap timeout on EVERY tap (see [FileBrowserController.handleTap]'s KDoc for the
// measurement and `FileBrowserTouchGrammarTest` for the pin that keeps it out).
