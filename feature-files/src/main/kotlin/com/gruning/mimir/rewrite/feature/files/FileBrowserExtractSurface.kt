package com.gruning.mimir.rewrite.feature.files

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing

/**
 * **F-06 — the Extract chooser's door.** It offers extraction here and into a chosen or recognised platform folder.
 *
 * The engine ([com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy]), the decision layer
 * ([fileBrowserExtractOptions]) and the controller ([FileBrowserController.openExtractChooser],
 * [FileBrowserController.handleExtractTap]) were all built and pinned by the earlier change with **no screen at
 * all**. This file is the screen. Until it existed, `app/`'s two `FileBrowserStore.mount()` lines were
 * deliberately held back, because wiring them would have pushed [FileBrowserController.extractScope]
 * behind a modal nothing renders — a live invisible dialog over a browse screen that still looks fine.
 *
 * ## It REPLACES the browse content. It is not an overlay.
 *
 * That is the precedent search (F-08) and the queue (F-01) already set, and `FileBrowserSurface`'s own
 * slot comment states their reason. A chooser is a short list about ONE archive, so an overlay that keeps the archive visible behind the
 * question would be worth the exception if it delivered that. **It does not, and the mechanism is
 * checkable rather than aesthetic:**
 *
 *  - `FileBrowserSurface` derives the folio's ring and its whole standing inspector from
 *    `focusState.takeIf { it.scope == controller.scope }?.focused`. [FileBrowserController
 *    .openExtractChooser] calls `pushScope(extractScope)`, so for the entire time the chooser is open
 *    that expression is **null**. An overlay would therefore leave the folio composed underneath with
 *    *no ring on any tile* and the inspector panel drawn as an empty box. The context an overlay exists
 *    to preserve is precisely what it loses: the archive the question is about would be the one tile on
 *    screen with nothing marking it.
 *  - So the archive is named **here**, at the top of this screen, which is strictly more context than a
 *    ringless folio behind a dialog: one line that says exactly which file is being extracted.
 *
 * The usual second leg of the replace argument — *two scroll owners on one list* — is honestly **absent
 * here**, and is recorded as absent rather than borrowed: this screen is a plain [Column] of at most three
 * rows and owns no `LazyListState` at all ([fileBrowserExtractOptions] can add both a smart destination
 * row and an explicit browse row).
 * The folio's own settlement pass would also have gone idle behind an overlay (it returns at its
 * `focusedKey == null` guard). Neither of those saves the ring, which is what decides it.
 *
 * ## Motion, and the one arrival this lane cannot give it
 *
 * [FileBrowserExtractArrival] is a constant, on the reasoning the search keyboard's arrival stated before F4
 * made it a sheet: this
 * subtree is composed only while [FileBrowserController.extractChooser] is non-null, so its **mount is
 * the event** and every `remember` in it is created fresh each time the chooser opens. It fades and does
 * not travel, which is [FileBrowserScreen]'s ring-safety rule applied unchanged — this lane cannot gate
 * a ring it does not own the key for, and an entrance that never moves geometry needs no gate.
 *
 * **Two arrivals are OWED and are contracts, not omissions** — both need
 * [fileBrowserScreenOf], which lives in `FileBrowserSurfaceMotion.kt`, a file whose exact call text `FileBrowserSurfaceMotionTest` pins:
 *  1. **Closing the chooser snaps.** The screen model answers `BROWSE` both while the chooser is open and
 *     after it closes, so no `SCREEN` arrival is produced for the return and the folio re-mounts at
 *     alpha 1.
 *  2. **`abandonDestinationPick` consumes a directory glide nothing plays.** That call arms a navigation
 *     glide, `load`s the origin and re-opens this chooser in one synchronous dispatch. On that frame
 *     `FileBrowserArrivalHistory.prepare` answers `DIRECTORY` and `commit` marks the sequence played —
 *     but `FileBrowserContent`, the only thing that applies a directory arrival, is not composed, because
 *     this screen holds the slot. The travel is spent on a listing nobody saw, and when the chooser is
 *     dismissed the folio arrives at the origin with no arrival left. Pinned as a pure sequence in
 *     `FileBrowserExtractSurfaceTest`, not inferred.
 *
 * Both close with the same two-line change: an `EXTRACT` case on [FileBrowserScreen] and a third
 * parameter on [fileBrowserScreenOf]. Neither is a ring hazard — a fresh mount at final geometry is the
 * one entrance `FileBrowserSurfaceMotion.kt`'s own KDoc says needs no gate.
 *
 * ## What a passing test in this module does NOT prove about this file
 *
 * `feature-files` configures no Compose harness — no Robolectric, no `createComposeRule`
 * (`build.gradle.kts`). Nothing here can certify that a row reaches the screen, that the ring lands where
 * it is drawn, or that a hit area covers what it looks like it covers. The pure rules are pinned and the
 * call sites are pinned **as source text**, this repo's established fallback (see
 * `FileBrowserSupportDimRenderTest`'s own disclosure). Hardware is still the gate.
 */
@Composable
internal fun FileBrowserExtractScreen(
    state: FileBrowserExtractChoiceState,
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = stringResource(FileBrowserExtractEyebrow),
            style = MimirTypeScale.RunicEyebrow,
            color = palette.textMuted,
            maxLines = 1,
        )
        Text(
            // The archive, named with its extension — see [fileBrowserExtractArchiveLabel] for why the
            // folio's extension-stripping rule is deliberately NOT reused on this screen.
            text = fileBrowserExtractArchiveLabel(state.archivePath),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 19.sp,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FileBrowserExtractRowWidthDp.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Never empty: FileBrowserController.openExtractChooser refuses an empty row list outright,
            // so "the chooser is open" and "there is something to press" are one fact rather than two.
            //
            // V-4: `key(option.kind)` — [FileBrowserGhostFreeIdentity] applied. **This screen could not
            // ghost today and the wrapper is still here**, which is the rule's own second corollary: the
            // chooser is composed only while `FileBrowserController.extractChooser` is non-null, so its
            // MOUNT is its arrival and every `animate*AsState` under it starts AT its target; and
            // `state.rows` is fixed for the life of one open. Both exemptions hold — but they hold by two
            // separate accidents of the controller, either of which a later change could take away
            // without touching this file. One `key` costs nothing and removes the dependency.
            state.rows.forEach { option ->
                // Renamed from `key` in V-4: the local shadowed `androidx.compose.runtime.key`, which is
                // the one symbol this loop now has to call.
                val optionKey = FileBrowserExtractFocus.optionKey(option.kind)
                key(option.kind) {
                    FileBrowserExtractOptionRow(
                        option = option,
                        focused = optionKey == focusedKey,
                        key = optionKey,
                        controller = controller,
                        authority = authority,
                        palette = palette,
                    )
                }
            }
        }
    }
}

/**
 * One option row.
 *
 * **The module's ordinary focus-row vocabulary, and the leather plate was considered and NOT taken.**
 * `FileBrowserCommitRow` wears [com.gruning.mimir.rewrite.core.ui.MimirMannazPin]'s leather-and-metal
 * treatment. That treatment is scoped to the commit row; these option rows
 * take `surfaceRaised` / `surfaceHigh` under [Modifier.mimirFocusRing], exactly as
 * `FileBrowserSearchResultRow` and the folio's own file tiles do. Extending the plate treatment here remains a separate design decision.
 *
 * **Both rows are styled identically and that is deliberate.** Making "Extract here" louder than
 * "Extract in…" would encode a recommendation this tree has no grounds for — Mimir cannot know which
 * destination the player wants, and the ring is what says which one is selected.
 *
 * Focus stays compositor-cheap (invariant #7): the fill colour and the ring change, nothing branches on
 * size or layout.
 */
@Composable
private fun FileBrowserExtractOptionRow(
    option: FileBrowserExtractOption,
    focused: Boolean,
    key: FocusKey,
    controller: FileBrowserController,
    authority: FocusAuthority,
    palette: MimirThemePalette,
) {
    Row(
        modifier = Modifier
            // widthIn OUTSIDE fillMaxWidth, FileBrowserCommitRow's own ordering: the cap has to narrow
            // the incoming constraints before the fill expands into them.
            .widthIn(max = FileBrowserExtractRowWidthDp.dp)
            .fillMaxWidth()
            .heightIn(min = FileBrowserExtractRowHeightDp.dp)
            // The one ring (ring migration, 2026-09-28): a row ring on the rows' shared corner. The ring paints the
            // row's own grey lift so the family's wash lands over it. Keyed on the row's own focus key.
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(FileBrowserRowCorner),
                restingOutline = true,
                fill = SolidColor(if (focused) palette.surfaceHigh else palette.surfaceRaised),
            )
            .clip(RoundedCornerShape(FileBrowserRowCorner))
            // No second scroll owner to fight: this screen has no LazyListState at all. Kept false to
            // match every other mimirFocusable in this module, so no call site here can ever be the one
            // that reintroduces `MimirFocusable.kt:43-44`'s two-scroll-systems defect.
            .mimirFocusable(authority, key, controller.extractScope, autoBringIntoView = false)
            // Focus first, then the controller's own CONFIRM path -- handleExtractTap does both, so touch
            // and the D-pad cannot disagree about what activating a row means.
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleExtractTap(key) }) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                // The decision layer's own word, rendered rather than restated -- these strings are
                // fileBrowserExtractOptions', pinned by FileBrowserExtractChoiceTest against the real
                // ExtractDestinationPolicy. A paraphrase here would be a second place for them to drift.
                text = option.label(filesResources()),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Blank for the picker row, which has no destination yet -- and then no line is drawn at all
            // rather than an empty one. The row's own trailing "…" is what says it opens somewhere else,
            // and it is the decision layer's word, not one invented here.
            if (option.detail.isNotBlank()) {
                Text(
                    text = option.detail,
                    fontSize = 10.sp,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The screen's eyebrow. One word, because the line under it is the whole subject: which archive.
 *
 * Not "Extract to" or "Choose a destination" — the *rows* say where, each in its own words, and a heading
 * that also answered "where?" would be a third copy of a question the footer legend already puts a button
 * against.
 */
@StringRes
internal val FileBrowserExtractEyebrow: Int = R.string.files_extract_eyebrow

/**
 * The archive this chooser is about, as the player reads it — **its own file name, extension included.**
 *
 * **Deliberately NOT [fileBrowserDisplayName]**, which strips the extension so a tile reads as a game
 * rather than as a file. That rule is right on the folio and would be actively confusing here:
 * [com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy.extractHereDestination] builds option
 * 1's destination as the archive's own **stem** beside it, so this heading and the first row's detail
 * line would print the identical word one above the other, with nothing to say which was the archive and
 * which was the folder about to appear next to it. The extension is the only thing that tells them apart.
 *
 * Falls back to the whole path when there is no name to take (a trailing separator, or an empty string),
 * so this can never render a blank heading over a live question.
 */
internal fun fileBrowserExtractArchiveLabel(archivePath: String): String =
    archivePath.substringAfterLast('/').ifBlank { archivePath }

/**
 * The chooser's arrival — a constant, for the reason the search keyboard's was before F4 made it a sheet: this
 * screen's MOUNT is its arrival, so there is no history to track and nothing for two `remember` keys to disagree
 * about.
 *
 * [FileBrowserArrivalKind.SCREEN], so it dips from
 * [FileBrowserGlideStartAlpha] and does not travel. That is `FileBrowserSurfaceMotion.kt`'s
 * ring-safety rule, not a taste call: the rows' ring is painted from a `focusedKey` this screen also has
 * no way to gate, and an entrance that never moves geometry cannot clip a ring.
 */
internal val FileBrowserExtractArrival: FileBrowserArrival =
    FileBrowserArrival(FileBrowserArrivalKind.SCREEN, epoch = 0L)

/**
 * How wide an option row is allowed to get — **the folio's own content-column width**, so the question
 * measures exactly the listing it replaced rather than stretching across a viewport that no longer has an
 * inspector in it.
 *
 * The number is [FileBrowserTomeRowPitchDp]'s own derivation, restated from that KDoc rather than
 * re-measured:
 * ```
 * content column width = 833   the Odin viewport      (adoption spec §2)
 *                       - 36   the content Row's padding(start =, end =)
 *                       - 14   the content Row's horizontalArrangement spacing
 *                       -270   FileBrowserInspectorPanel's width()
 *                       -  5   the content LazyColumn's contentPadding end
 *                       = 508 dp
 * ```
 * **Same `FileBrowserSurface.kt` contract every dp in `FileBrowserFocusRegistration.kt` carries:** if one
 * of those literals moves, this moves with it. A cap and not a fixed size — a narrower viewport still
 * gets rows that fit it. **PENDING REVIEW**, like [FileBrowserCommitRowWidthDp]: visual assessment on hardware is
 * still required to validate the proportion.
 */
internal const val FileBrowserExtractRowWidthDp: Float = 508f

/**
 * An option row's resting height.
 *
 * Above [MimirSpacing.minTarget] (48 dp, *"minimum touch/focus target on a handheld"*) by construction —
 * The target-size invariant requires this app's controls stop being drawn under that floor, and a row that is also a
 * touch target is exactly what that applies to. `heightIn`, never `defaultMinSize`, for V-3's stated
 * reason: `defaultMinSize` yields to an incoming constraint, and a floor a parent can silently shrink is
 * not a floor.
 *
 * It is also what keeps all rows the same height when one of them has no detail line (the picker row
 * carries a blank [FileBrowserExtractOption.detail]) — a ragged pair would sit oddly against
 * [fileBrowserExtractOptionBounds], which models the chooser as a uniform single-column pitch.
 */
internal const val FileBrowserExtractRowHeightDp: Float = 54f
