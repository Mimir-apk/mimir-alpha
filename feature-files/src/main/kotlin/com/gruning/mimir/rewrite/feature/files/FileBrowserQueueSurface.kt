package com.gruning.mimir.rewrite.feature.files

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.files.FileConflictChoice
import com.gruning.mimir.rewrite.core.files.FileOperationQueue
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.ctaMaterial
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState

/**
 * **F-01 — the Queue screen.** , on top of the
 * reversible-delete flow without a confirmation dialog.
 *
 * **This screen is the safety mechanism, not a status pane.** The no-confirmation flow is only
 * sound because Undo exists, and Undo exists *only* on a card here
 * ([FileBrowserQueueAction.UNDO] → `FileBrowserStore.undo`). Until this file existed the engine was
 * finished, tested and had zero production callers — `deleteInManager`, `undo`, `releaseUndo`,
 * `resolveConflict` and the `queue` flow itself. A delete with no reachable Undo has no protection at
 * all, which is why this is the lane the delete binding waits on.
 *
 * **It decides nothing.** Every rule it renders is a pure function that a plain JUnit test can drive,
 * because `feature-files` configures no Compose harness (no Robolectric, no `createComposeRule` —
 * `build.gradle.kts`) and a decision made inside a composable here could never be asserted:
 *  - which buttons a card carries → [fileBrowserQueueActions] (`FileBrowserQueue.kt`)
 *  - the chip's words → [FileOperationQueue.chipLabel]; the header line → [FileOperationQueue.summary],
 *    **used directly** so the "nothing leaves this device" claim has exactly one source
 *  - each button's text → [FileBrowserQueueAction.label]; the conflict answers take theirs from
 *    `core-files`' own [com.gruning.mimir.rewrite.core.files.FileConflictChoice]
 *  - which focus key a button holds → [FileBrowserQueueFocus.actionKey]
 *  - which button is drawn loudest → [fileBrowserQueueLeadAction], below, which is the one decision
 *    this file adds and therefore the one it pins.
 *
 * **It REPLACES the browse content; it is not an overlay.** [FileBrowserSurface] composes it in the same
 * slot search takes, for the reason stated at that call site: an overlay keeps the folio's own scroll
 * effect and its whole focus graph composed underneath a screen that has its own. Two live graphs on one
 * list was half of this module's D-pad defect.
 *
 * **One scroll owner.** Every [mimirFocusable] below passes `autoBringIntoView = false` and the single
 * [LaunchedEffect] settlement pass owns the [androidx.compose.foundation.lazy.LazyListState] — the same
 * arrangement `FileBrowserSearchResults` uses, reusing the folio's own pure [fileBrowserRevealAction]
 * rather than restating its cadence.
 *
 * **What a passing test in this module does NOT prove about this file:** that any of it reaches the
 * screen, that a button is where it looks, or that the ring lands on it. The pure functions are pinned;
 * the call sites are pinned as source text (this repo's established fallback — see
 * `FileBrowserSupportDimRenderTest`'s own disclosure). Hardware is still the gate.
 */
@Composable
internal fun FileBrowserQueueScreen(
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    val cards = controller.queueCards
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            // The no-network promise is one plural string (`files_queue_summary`), read through fileBrowserQueueSummary and
            // pinned in both languages; the English matches core-files' FileOperationQueue.summary word for word.
            text = fileBrowserQueueSummary(filesResources(), controller.queueEntries.size),
            style = MimirTypeScale.RunicEyebrow,
            color = palette.textMuted,
            maxLines = 1,
        )
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(FileBrowserQueueEmptyMessage),
                    style = MimirTypeScale.Support,
                    color = palette.textMuted,
                )
            }
            return@Column
        }
        FileBrowserQueueCards(
            cards = cards,
            controller = controller,
            authority = authority,
            focusedKey = focusedKey,
            palette = palette,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * What an empty queue says, and the two things it deliberately does not say.
 *
 * The header line above it already reports the operation count and [FileOperationQueue.summary]'s
 * no-network claim — quoted nowhere, here or in any comment, because a stale copy of a pinned sentence
 * is exactly what that pin exists to prevent. So this line exists only to stop the screen reading as
 * broken rather than as empty. It names **delete** and not move/copy, and as of Move/Copy slice 1 (G2)
 * that is no longer because a transfer has no production caller — `FileBrowserStore.moveInManager` /
 * `copyInManager` are wired (`onMoveTo` / `onCopyTo`, reached from the transfer chooser's commit row) —
 * it is because **a COPY has no Undo** -- nothing was taken away, and "undoing" one would mean deleting
 * a file the player asked to create. A completed MOVE does have one as of 2026-08-16
 * (`FileQueueState.ReverseAvailable`), so this line under-promises for half the transfers it covers, which
 * is the direction to be wrong in. Promising *"waits here, with Undo"* for the half that never gets one
 * would be the
 * same fabrication class as a fixed footer legend ([fileBrowserFooterHints]) or a `"Not checked"` row
 * after a successful verify — just aimed the other way, at a guarantee instead of an absence. **The
 * string therefore still needs copy review**: it now describes one of two operation
 * classes that reach this screen, and whether a player should be told anything different about the
 * other remains undecided.
 *
 * It names **Undo** because that is the whole point of the player being able to find this screen for a
 * delete: there is no confirm dialog, so Undo is a delete's only protection (C-1).
 */
@StringRes
internal val FileBrowserQueueEmptyMessage: Int = R.string.files_queue_empty

@Composable
private fun FileBrowserQueueCards(
    cards: List<FileBrowserQueueCard>,
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // The folio's own settlement model, reused rather than restated -- `fileBrowserRevealAction` is pure
    // and takes plain numbers, so it applies to any LazyColumn. Keyed on the FOCUSED KEY and the cards,
    // so a publish that re-lands focus (which is the COMMON case here -- pressing Undo removes the card
    // its own button belonged to; see FileBrowserController.publishQueue) re-settles once.
    LaunchedEffect(focusedKey, cards) {
        val index = cards.indexOfFirst { card -> fileBrowserQueueCardHolds(card, focusedKey) }
        if (index < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        when (
            val action = fileBrowserRevealAction(
                targetIndex = index,
                firstVisibleIndex = layout.visibleItemsInfo.firstOrNull()?.index,
                visibleCount = layout.visibleItemsInfo.size,
                focusedItemOffset = item?.offset,
                focusedItemSize = item?.size,
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
                // A card's height varies with its state (a bar or no bar, one button or three), so this
                // is an ESTIMATE and is named as one -- it feeds only the centring offset of the two
                // seek/animate branches, which run when nothing is laid out yet. Once a card is measured,
                // `focusedItemSize` above is the real number and this is not consulted.
                estimatedItemSize = with(density) { FileBrowserQueueCardEstimateDp.dp.roundToPx() },
            )
        ) {
            FileBrowserRevealAction.None -> Unit
            is FileBrowserRevealAction.SeekTo -> listState.scrollToItem(action.index, action.scrollOffset)
            is FileBrowserRevealAction.AnimateTo -> listState.animateScrollToItem(action.index, action.scrollOffset)
            is FileBrowserRevealAction.ScrollBy -> listState.animateScrollBy(
                value = action.delta,
                animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FileBrowserRowGapDp.dp),
        // Top and bottom: a lazy list clips exactly on its axis, and a focused row's ring reaches
        // MimirFocusTileRingReach past the row (ring migration, 2026-09-28). End stays the design's own.
        contentPadding = PaddingValues(top = MimirFocusTileRingReach, end = 5.dp, bottom = MimirFocusTileRingReach),
    ) {
        // Keyed by the ENTRY id, never by position: the queue is newest-first, so every enqueue shifts
        // every existing card down one and a positional key would re-target a composed card's state onto
        // a different operation. Same reasoning FileBrowserQueueFocus.actionKey states for focus keys.
        items(cards, key = { it.entryId }) { card ->
            FileBrowserQueueCardRow(
                card = card,
                controller = controller,
                authority = authority,
                focusedKey = focusedKey,
                palette = palette,
            )
        }
    }
}

/**
 * Whether [focusedKey] is one of [card]'s own buttons.
 *
 * Answered through [FileBrowserQueueFocus.targetOf] rather than by rebuilding each key, so the screen
 * reads a focused key exactly the way `FileBrowserController.dispatchQueue` does — one parser, so the
 * card that scrolls into view and the button that fires can never disagree about who owns a key.
 */
internal fun fileBrowserQueueCardHolds(card: FileBrowserQueueCard, focusedKey: FocusKey?): Boolean =
    FileBrowserQueueFocus.targetOf(focusedKey)?.entryId == card.entryId

/** One card's floor, matching the design's own card rhythm on this viewport (the folio's rows are 46 dp;
 * a queue card carries two text lines and a button row, so it stands taller). */
internal const val FileBrowserQueueCardMinHeightDp: Float = 74f

/**
 * The card height the settlement pass assumes before anything is measured: the
 * [FileBrowserQueueCardMinHeightDp] floor plus one [FileBrowserRowGapDp].
 *
 * Deliberately the *floor*, not an average: over-estimating the pitch centres a short card too low and
 * can push the first row off the top on the very first frame, which is the one frame the player watches
 * the screen appear.
 */
internal const val FileBrowserQueueCardEstimateDp: Float =
    FileBrowserQueueCardMinHeightDp + FileBrowserRowGapDp

/**
 * One operation (`Mimir Files.dc.html:238-266`): title, second line, chip, an optional bar, and the
 * buttons this state earns.
 *
 * **The card itself is not focusable — its buttons are.** A focusable card would put a focus slot on
 * every row that does nothing when pressed (a `Running` transfer carries no buttons at all, by
 * [fileBrowserQueueActions]), which is this module's standing objection to controls that cannot act.
 */
@Composable
private fun FileBrowserQueueCardRow(
    card: FileBrowserQueueCard,
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    palette: MimirThemePalette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FileBrowserQueueCardMinHeightDp.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surfaceRaised)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileBrowserQueueTitleText(filesResources(), card.title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The store writes this line in English; the screen draws it in the host's language (FilesQueueText.kt).
                    text = fileBrowserQueueLineText(filesResources(), card.subtitle, card.title),
                    fontSize = 10.5.sp,
                    color = palette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FileBrowserQueueChip(fileBrowserQueueChipLabel(filesResources(), card.state), card.tone, palette)
        }
        // Drawn ONLY when the model says there is a fraction. A zero-width bar under a finished
        // operation would read as "stalled at 0%" -- FileBrowserQueueCard.progress is null rather than
        // 0f for exactly this reason, and honouring that is the whole contract of this branch.
        //
        // F-6: null now ALSO covers a Running card the engine cannot honestly measure -- a same-folder
        // rename, a small file, a skipped source (FileQueueState.Running's own KDoc). This branch needed
        // no change for that case: the null-means-no-bar contract already says exactly the right thing
        // for an operation with nothing to show a fraction of, which is why the fix landed in the model
        // (core-files) rather than here. The card still carries a chip ("Working…" for that state) and its title/subtitle, so the row is not blank -- only the bar,
        // which would otherwise have been the fabrication, is withheld.
        card.progress?.let { fraction -> FileBrowserQueueProgressBar(fraction, palette) }
        if (card.actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val lead = fileBrowserQueueLeadAction(card)
                // V-4: `key(action)` — [FileBrowserGhostFreeIdentity], and a LIVE case rather than a
                // precaution. The card is content-keyed above (`items(cards, key = { it.entryId })`), so
                // a card cannot inherit another card's slot; the BUTTONS inside one card were positional,
                // and a card's action list is not fixed — an entry that finishes staging gains and loses
                // buttons under a player who has not moved. Without this, a button that held the ring
                // handed its four `mimirFocusRing` animations to whatever action took its position, which
                // then faded a gold border out of a button the player never selected. That is the folio's
                // defect on the queue screen, reached by the SAME structural door.
                card.actions.forEach { action ->
                    key(action) {
                        FileBrowserQueueButton(
                            entryId = card.entryId,
                            action = action,
                            lead = action == lead,
                            focusedKey = focusedKey,
                            controller = controller,
                            authority = authority,
                            palette = palette,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Which of a card's buttons is drawn as the loud one — **the safest one, in every state.**
 *
 * The design's prototype gives each card a single `onPrimary`, so one emphasised button per card is the
 * ported shape. *Which* one is the decision this file makes, and it is a safety decision rather than a
 * visual one: the emphasised button is the one a player presses without reading, so on a screen whose
 * whole reason for existing is that there is no confirm dialog, it must never be the button that loses
 * data.
 *
 * Taking [FileBrowserQueueCard.actions]`.first()` yields exactly that for all five states, because
 * [fileBrowserQueueActions] already emits them in the design's left-to-right order:
 *  - `UndoAvailable` → **Undo** (the files come back), not `KEEP_DELETED` (which frees the payload)
 *  - `AwaitingConflict` → **Keep both** (both files survive), not `REPLACE` (which overwrites one)
 *  - `Done`/`Failed`/`Blocked` → **Dismiss**, which by its own KDoc frees nothing
 *  - `Running` → null; that state carries no buttons at all
 *
 * So this is a one-line function with a real contract, and `FileBrowserQueueEmphasisTest` pins the
 * contract rather than the line: flip it to `last()` and an `UndoAvailable` card draws **Keep deleted**
 * as its loudest control.
 */
internal fun fileBrowserQueueLeadAction(card: FileBrowserQueueCard): FileBrowserQueueAction? =
    card.actions.firstOrNull()

/**
 * One button on one card — a real [mimirFocusable] participant in the queue's own scope.
 *
 * Not a reuse of `FileBrowserSurface`'s `FileBrowserButton`: that control is touch-only and takes no
 * focus (it sits inside the inspector, which the D-pad reaches through the tile grid). Every control on
 * this screen has to be reachable by D-pad, so the focus registration is the difference, not the paint.
 *
 * Activation goes through [FileBrowserController.handleQueueTap], which focuses the key and then reuses
 * `dispatch`'s own CONFIRM path — so a tap and a press run the identical code, and the intent is read
 * off the focused key in both cases.
 */
@Composable
private fun FileBrowserQueueButton(
    entryId: Long,
    action: FileBrowserQueueAction,
    lead: Boolean,
    focusedKey: FocusKey?,
    controller: FileBrowserController,
    authority: FocusAuthority,
    palette: MimirThemePalette,
) {
    val key = remember(entryId, action) { FileBrowserQueueFocus.actionKey(entryId, action) }
    val focused = focusedKey == key
    // Update  supersedes the earlier "rounded rectangle, not a pill" note: every pressable is an etched chip. The lead action (the safest one) is the
    // PRIMARY chip (the same dark plate, its rim and word in the accent), the rest the unselected one, at the same height and width as before, so the row stays even. The Pill ring
    // is on the plate itself and BEFORE the surface: its edge is the plate's edge, and only the focused button wears it.
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = FileBrowserQueueButtonHeightDp.dp)
            // This screen runs its own settlement scroll; a second bring-into-view here would fight it
            // for the same LazyListState.
            .mimirFocusable(authority, key, controller.queueScope, autoBringIntoView = false)
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)
            .mimirEtchedChipSurface(palette, focused = focused, primary = lead, edgeFlush = true)
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleQueueTap(key) }) }
            .padding(horizontal = 13.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // The action's own label, never a word chosen here: "Keep deleted" and "Dismiss" mean
            // different things to the player's files and FileBrowserQueueAction is where that
            // distinction is decided.
            text = stringResource(action.labelRes),
            fontSize = 11.sp,
            fontWeight = if (lead) FontWeight.Black else FontWeight.SemiBold,
            color = palette.etchedChipInk(primary = lead),
            maxLines = 1,
        )
    }
}

/** Test seam: a queue card exactly as the list draws it ([FileBrowserQueueCardRow] stays private for the source pins). */
@Composable
internal fun FileBrowserQueueCardRowForCapture(card: FileBrowserQueueCard, controller: FileBrowserController, authority: FocusAuthority, focusedKey: FocusKey?, palette: MimirThemePalette) =
    FileBrowserQueueCardRow(card, controller, authority, focusedKey, palette)

/** The queue button's floor. Matches the inspector's own compact button (27 dp) plus the touch slack a
 * destructive-adjacent control earns on a 369 dpi handheld. */
internal const val FileBrowserQueueButtonHeightDp: Float = 30f

/**
 * The card's chip — [FileBrowserSurface]'s own `FileBrowserChip` recipe with the fourth arm
 * [FileBrowserQueueTone] has and [FileBrowserChipTone] lacks: an outlined pill, never a filled or tinted
 * one .
 *
 * The two enums stay separate deliberately (see [FileBrowserQueueTone]'s KDoc): adding a failure arm to
 * [FileBrowserChipTone] would break the exhaustive `when` in `FileBrowserChip`. What is shared is the
 * *mapping shape*, restated here for one extra case rather than forked wholesale — each tone is the
 * colour of the word and of the outline, and `FAILED` reads through [MimirThemePalette.danger], the one
 * token in this palette that means "this went wrong".
 */
@Composable
private fun FileBrowserQueueChip(label: String, tone: FileBrowserQueueTone, palette: MimirThemePalette) {
    val (text, border) = when (tone) {
        FileBrowserQueueTone.NEUTRAL -> palette.textMuted to palette.outline
        FileBrowserQueueTone.PENDING -> palette.emberHot to palette.emberHot
        FileBrowserQueueTone.DONE -> palette.success to palette.success
        FileBrowserQueueTone.FAILED -> palette.danger to palette.danger
    }
    Text(
        text = label,
        style = MimirTypeScale.Pill,
        color = text,
        maxLines = 1,
        modifier = Modifier
            .defaultMinSize(minHeight = 26.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .drawWithCache {
                onDrawBehind {
                    drawRoundRect(
                        color = border,
                        cornerRadius = CornerRadius(size.minDimension / 2f),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

/**
 * The `Running` bar. Composed only where [FileBrowserQueueCard.progress] is non-null, so this composable
 * never has to represent "no progress" — it always draws a real fraction.
 *
 * A plain two-`Box` fill rather than Material's `LinearProgressIndicator`: that component brings its own
 * indeterminate animation and its own colour scheme, and this module renders from
 * [com.gruning.mimir.rewrite.core.ui.LocalMimirPalette] only.
 */
@Composable
private fun FileBrowserQueueProgressBar(fraction: Float, palette: MimirThemePalette) {
    // **a DECORATIVE ramp, value-independent.** Drawn rather than stacked,
    // and that is the whole point. The previous shape sized its fill `Box` by the fraction itself
    // — so a gradient on ITS background would be a gradient across the FRACTION: at 10% the entire
    // ramp squeezes into a tenth of the track and the ink at the leading edge changes as the job
    // advances. That is a colour that moves with a value, which is §4's threshold CLAIM arriving by
    // another route, and it is the trap this shape sets for anyone adding a gradient later.
    //
    // (The banned call is deliberately not spelled out in this comment: the pin greps this file, and a
    // comment quoting the pattern it forbids is indistinguishable from the pattern coming back.)
    //
    // The gradient therefore spans the TRACK's width and the fill is clipped to the fraction, so the
    // same two inks appear at 5% and at 95%. Same treatment as the companion's job bar, which is the
    // point: SS4 says a quantity Mimir already speaks must be spoken identically everywhere.
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(FileBrowserQueueBarHeightDp.dp),
    ) {
        val cap = StrokeCap.Round
        val y = size.height / 2f
        drawLine(
            color = palette.surfaceHigh,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.height,
            cap = cap,
        )
        val filled = size.width * fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(palette.accentBright, palette.accent),
                    startX = 0f,
                    endX = size.width,
                ),
                start = Offset(0f, y),
                end = Offset(filled, y),
                strokeWidth = size.height,
                cap = cap,
            )
        }
    }
}

/** The design's own progress bar height (`Mimir Files.dc.html`'s 6px track), in dp. */
internal const val FileBrowserQueueBarHeightDp: Float = 6f

// ──────────────────────────────────────────────────────────────────── The store seam ──

/**
 * Binds the store to the queue screen: **one sink for every button, and one publish for the queue.**
 *
 * ## Why the callbacks are re-read rather than captured
 *
 * The lambda handed to [FileBrowserController.setQueueAction] outlives the composition that created it —
 * it sits in a `mutableStateOf` on the controller until the next `DisposableEffect` cycle. Capturing
 * [onUndo] and friends directly would freeze whichever host lambdas existed at the FIRST composition, so
 * a host that re-created them (a new `FileBrowserStore` session, a recomposed `MimirAppHost`) would keep
 * routing Undo into the previous session's store. [rememberUpdatedState] is the standard answer and is
 * what the effect below reads through.
 *
 * ## Why the target is routed with no second `when`
 *
 * [FileBrowserQueueAction.conflictChoice] is non-null for exactly the three
 * [com.gruning.mimir.rewrite.core.model.jobs.FileQueueState.AwaitingConflict] answers and null for the three
 * settle/dismiss actions, which is stated on the enum and pinned there. So the conflict case is one
 * `?.let` and the remaining three are a three-arm `when` over actions that carry no choice — no branch
 * here can disagree with the enum about which store call an action names.
 *
 * ## Why `setQueueAction(null)` on dispose
 *
 * That is the documented "the engine was taken away" path: the controller closes the screen rather than
 * leaving a pushed scope with nothing to act in. Matches `setSearchAvailable(false)`.
 *
 * ## Why publish is a LaunchedEffect and not a SideEffect
 *
 * [FileBrowserController.publishQueue] mutates focus registrations and has a repair branch that can
 * `clearFocus()` and re-`requestFocus`. Running that on every recomposition — which is what `SideEffect`
 * does — would re-run a focus repair for reasons that have nothing to do with the queue changing. Keyed
 * on the list, it runs when the list actually changes.
 *
 * **A disclosed consequence of the defaulted no-op callbacks:** a MANAGE caller that wires none of them
 * still gets a queue screen, because the sink is non-null either way. That screen is honestly empty (the
 * store never enqueued anything, so `queue` is empty and the header says `0 operations`), which is the
 * same judgement `fileBrowserFooterHints` already records for the persistent START hint — unlike a search
 * screen with no engine, an empty queue screen tells the truth.
 */
@Composable
internal fun FileBrowserQueueSink(
    controller: FileBrowserController,
    queue: List<FileQueueEntry>,
    onUndo: (Long) -> Unit,
    onReleaseUndo: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    onResolveConflict: (Long, FileConflictChoice) -> Unit,
) {
    val undo by rememberUpdatedState(onUndo)
    val releaseUndo by rememberUpdatedState(onReleaseUndo)
    val dismiss by rememberUpdatedState(onDismiss)
    val resolveConflict by rememberUpdatedState(onResolveConflict)

    DisposableEffect(controller) {
        controller.setQueueAction { target ->
            fileBrowserQueueDispatch(
                target = target,
                onUndo = undo,
                onReleaseUndo = releaseUndo,
                onDismiss = dismiss,
                onResolveConflict = resolveConflict,
                onCancel = controller::cancelEntry,
            )
        }
        onDispose { controller.setQueueAction(null) }
    }
    LaunchedEffect(controller, queue) { controller.publishQueue(queue) }
}

/**
 * One activated queue button → the one `FileBrowserStore` entry point it names.
 *
 * **A pure function rather than a lambda body inside [FileBrowserQueueSink], deliberately.** This is the
 * seam where a destructive action is chosen: getting `KEEP_DELETED` and `DISMISS` the wrong way round
 * would free a staged payload the player was only tidying away, and a routing table buried in a
 * `DisposableEffect` is unreachable by a module with no Compose harness. Out here it is a plain JUnit
 * assertion per arm.
 *
 * The three conflict answers are routed **off [FileBrowserQueueAction.conflictChoice]**, not by naming
 * them — that property is non-null for exactly those three and null for the other three, pinned on the
 * enum. So this function cannot disagree with the enum about which action is a conflict answer, and
 * adding a fourth conflict choice upstream would route itself.
 */
internal fun fileBrowserQueueDispatch(
    target: FileBrowserQueueTarget,
    onUndo: (Long) -> Unit,
    onReleaseUndo: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    onResolveConflict: (Long, FileConflictChoice) -> Unit,
    onCancel: (Long) -> Unit = {},
) {
    val choice = target.action.conflictChoice
    if (choice != null) {
        onResolveConflict(target.entryId, choice)
        return
    }
    when (target.action) {
        // Puts the files back.
        FileBrowserQueueAction.UNDO -> onUndo(target.entryId)
        // Frees the staged payload -- the one irreversible press on this screen.
        FileBrowserQueueAction.KEEP_DELETED -> onReleaseUndo(target.entryId)
        // Drops the card and touches nothing on disk.
        FileBrowserQueueAction.DISMISS -> onDismiss(target.entryId)
        // Stops a running copy, move or extract at its next safe point; the store says what it left.
        FileBrowserQueueAction.CANCEL -> onCancel(target.entryId)
        // Unreachable: every remaining action carries a conflict choice and returned above. An explicit
        // no-op rather than a `throw` -- a stale focus key that somehow parsed must degrade to "nothing
        // happened", never crash the browser mid-delete.
        FileBrowserQueueAction.KEEP_BOTH,
        FileBrowserQueueAction.REPLACE,
        FileBrowserQueueAction.SKIP,
        -> Unit
    }
}
