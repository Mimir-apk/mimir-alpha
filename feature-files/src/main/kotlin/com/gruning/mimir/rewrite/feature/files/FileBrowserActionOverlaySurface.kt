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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
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
 * **F-1 — the SELECT action overlay.**
 *
 * ## It REPLACES the browse content, on [FileBrowserExtractScreen]'s own argument
 *
 * Not re-argued here — the mechanism is identical and checkable the same way, because this screen
 * occupies the exact same content slot in `FileBrowserSurface.kt` as its five siblings: this surface's
 * ring and the whole standing inspector are derived from a scope-filtered focus key that goes null the
 * instant `pushScope(actionOverlayScope)` runs, so an anchored popup would leave a ringless folio and an
 * empty inspector behind the very tile it claims to be pointing at. "Pointing to the file" is delivered by
 * the subject line below, never by spatial anchoring .
 *
 * ## What a passing test in this module does NOT prove about this file
 *
 * `feature-files` configures no Compose harness — no Robolectric, no `createComposeRule`. Nothing here
 * can certify that a row reaches the screen, that the ring lands where it is drawn, or that a hit area
 * covers what it looks like it covers. The pure rules are pinned and the call sites are pinned as source
 * text; hardware is the gate.
 */
@Composable
internal fun FileBrowserActionOverlayScreen(
    state: FileBrowserActionOverlayState,
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
            text = stringResource(FileBrowserActionOverlayEyebrow),
            style = MimirTypeScale.RunicEyebrow,
            color = palette.textMuted,
            maxLines = 1,
        )
        Text(
            // fileBrowserTransferSubjectLabel's exact shape, reused rather than re-derived — its own name,
            // extension included, falling back to the whole path so this can never render a blank heading
            // over a live question.
            text = fileBrowserTransferSubjectLabel(state.path),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 19.sp,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FileBrowserActionOverlayRowWidthDp.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Never empty: FileBrowserController.openActionOverlay refuses to open on an empty row list
            // (the same contract openTransferChooser/openExtractChooser hold), so "the overlay is up" and
            // "there is something to press" stay one fact.
            //
            // key(rowKind), FileBrowserGhostFreeIdentity applied: content-derived, never index-derived.
            state.rows.forEach { rowKind ->
                val optionKey = FileBrowserActionOverlayFocus.optionKey(rowKind)
                key(rowKind) {
                    FileBrowserActionOverlayOptionRow(
                        kind = rowKind,
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
 * One row. `Modifier.mimirFocusable` + `detectTapGestures` + `.semantics { onClick }`, never `clickable`
 * — the design standing's sanctioned three-part pattern, all three legs, matching
 * [FileBrowserDiscSetOptionRow] / [FileBrowserTransferOptionRow] verbatim (hand-inlined to match every
 * sibling row's own textual shape, rather than `Modifier.mimirActivatable` — a packaged equivalent with
 * zero precedent elsewhere in this module and no Compose harness here to catch an integration surprise;
 * named as a considered alternative rather than a default, per the design spec §7).
 *
 * **Delete sits apart, not merely last.** `HomeTileMenuOverlay`'s own precedent for exactly this shape:
 * *"Destructive rows carry the palette's danger ink and sit apart from the safe actions above — the layout needs visual separation, not a different position."* One conditional top padding ahead of the
 * row, and `palette.danger` on its label — the same authored role, the same mechanism, reused rather than
 * reinvented.
 */
@Composable
private fun FileBrowserActionOverlayOptionRow(
    kind: FileBrowserActionOverlayRowKind,
    focused: Boolean,
    key: FocusKey,
    controller: FileBrowserController,
    authority: FocusAuthority,
    palette: MimirThemePalette,
) {
    val label = stringResource(fileBrowserActionOverlayLabelRes(kind))
    val detail = fileBrowserActionOverlayDetail(filesResources(), kind)
    val destructive = kind == FileBrowserActionOverlayRowKind.DELETE
    Row(
        modifier = Modifier
            // HomeTileMenuOverlay.kt's own mechanism: the gap sits ahead of the destructive row, not a
            // position rule — Delete is already last by construction (fileBrowserActionOverlayRows).
            .padding(top = if (destructive) MimirSpacing.sm else 0.dp)
            // widthIn OUTSIDE fillMaxWidth -- FileBrowserDiscSetOptionRow's own ordering: the cap has to
            // narrow the incoming constraints before the fill expands into them.
            .widthIn(max = FileBrowserActionOverlayRowWidthDp.dp)
            .fillMaxWidth()
            .heightIn(min = FileBrowserActionOverlayRowHeightDp.dp)
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Row(FileBrowserRowCorner))
            .clip(RoundedCornerShape(FileBrowserRowCorner))
            // These rows use the product's control leather.
            //
            // `materials.controlTop`->`controlBottom` is this product's CONTROL leather, already painted
            // by MimirLiquidStatusPill, MimirRunicQuickRail and the home shelf's rails. Not
            // `hero.coverTop/Middle/Bottom`, which is the TOME cover — MimirAchievementCard's own KDoc
            // records that using the control gradient on a card was the wrong recipe, and the inverse is
            // just as wrong. These are controls.
            //
            // This surface and the Return row need the same material as well as the same shape.
            // Copying the shape alone could not match them while this surface lacked the control leather.
            .background(
                Brush.verticalGradient(
                    listOf(
                        palette.materials.controlTop.copy(alpha = if (focused) 1f else 0.92f),
                        palette.materials.controlBottom.copy(alpha = if (focused) 1f else 0.92f),
                    ),
                ),
            )
            // No second scroll owner to fight: this screen has no LazyListState at all.
            .mimirFocusable(authority, key, controller.actionOverlayScope, autoBringIntoView = false)
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleActionOverlayTap(key) }) }
            .semantics(mergeDescendants = true) {
                onClick(label = label) {
                    controller.handleActionOverlayTap(key)
                    true
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                // `onCover` over leather, not `textPrimary` — reading textPrimary over a cover gradient
                // is how a label goes nearly invisible on one of the four identities. Delete keeps
                // `danger`, which is an authored semantic role rather than a surface-dependent ink, and
                // is the same colour HomeTileMenuOverlay already tints a destructive row with.
                color = if (destructive) palette.danger else palette.materials.hero.onCover,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Blank draws NOTHING rather than an empty line -- the extract/discset choosers' own rule.
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    fontSize = 11.5.sp,
                    // Muted BY ALPHA off the cover ink rather than by `textMuted`, which is authored
                    // against the flat surfaces this row no longer has.
                    color = palette.materials.hero.onCover.copy(alpha = 0.66f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The overlay's eyebrow — Wording remains unreviewed. "Actions" names the screen's category without naming a tile kind,
 * the same fixed-per-screen shape [FileBrowserDiscSetEyebrow] / [FileBrowserTransferEyebrow] use. The
 * design spec's alternative (the tile's own kind — FILE / FOLDER / ARCHIVE) is not built: it would make
 * the eyebrow content-dependent where every sibling screen's is a constant, and the subject line
 * immediately below already names the specific tile.
 */
@StringRes
internal val FileBrowserActionOverlayEyebrow: Int = R.string.files_overlay_eyebrow

/**
 * The overlay's arrival — a constant, `SCREEN`, on
 * [FileBrowserExtractArrival] / [FileBrowserTransferArrival] / [FileBrowserDiscSetArrival]'s identical
 * precedent: this screen's MOUNT is its arrival, and it dips rather than travels. The design spec §4's own
 * load-bearing recommendation — the app-level overlay dialect (`SCREEN_OVER`, since deleted with the keyboard's
 * move to a sheet, and `OverlayMs`) belongs to the ~18 app-level overlay kinds, not
 * to a sub-screen inside this browser's own content slot, and reaching for it because the ticket's own
 * word is "overlay" would hand this browser a second, inconsistent transition dialect for what is
 * mechanically the same SCREEN-arrival event as its five siblings. This also inherits the 2026-08-18 snap
 * fix by construction: `fileBrowserArrivalTravelDp` returns 0dp for SCREEN, so there is no geometry to
 * clip a focus ring on arrival.
 */
internal val FileBrowserActionOverlayArrival: FileBrowserArrival =
    FileBrowserArrival(FileBrowserArrivalKind.SCREEN, epoch = 0L)

/** Identical to the transfer/extract choosers', referenced rather than re-derived — same content-column slot. */
internal const val FileBrowserActionOverlayRowWidthDp: Float = FileBrowserTransferRowWidthDp

/** Identical to the transfer/extract choosers', referenced rather than re-derived — same reason. */
internal const val FileBrowserActionOverlayRowHeightDp: Float = FileBrowserTransferRowHeightDp
