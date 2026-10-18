package com.gruning.mimir.rewrite.feature.files

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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.files.DiscSetMoveOption
import com.gruning.mimir.rewrite.core.files.DiscSetWarning
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing

/**
 * **The multi-disc warning** precedes the move and offers the complete set.
 *
 * The last screen before a move that would break a game, and the only screen in this module whose reason
 * for existing is to make a press *harder*. Everything about it is [FileBrowserTransferScreen]'s
 * construction — same slot, same row idiom, same three-part focus/tap/semantics pattern — with two
 * deliberate differences, both of which are the warning doing its job:
 *
 * 1. **It leads with a sentence, not a filename.** The transfer chooser heads with the subject because
 *    the player already knows what they are doing and only needs to confirm which file. Here the file is
 *    not the news; *"Disc 2 of 3 — Final Fantasy IX"* is, and the line under it says what breaks.
 * 2. **Its rows carry a second line.** [FileBrowserTransferOption] has nothing to say per row; these rows
 *    do, because "move all 3 discs" and "move just this one" have consequences that differ, and a row
 *    whose consequence is invisible is the thing that made this screen necessary.
 *
 * ## It re-decides nothing
 *
 * The rows arrive already chosen by `discSetMoveOptions`, which offers *move all* only when
 * [DiscSetWarning.wholeSetCanTravel] — a checked fact about the playlist's own text, not a design
 * intention. This file renders that answer. It does not consult [DiscSetWarning.wholeSetCanTravel] to
 * decide whether to DRAW a row; it only reads it to choose the honest wording, which is the one thing the
 * policy deliberately left to the presentation layer.
 *
 * ## What a passing test in this module does NOT prove about this file
 *
 * `feature-files` configures no Compose harness — no Robolectric, no `createComposeRule`. Nothing here
 * can certify that a row reaches the screen, that the ring lands where it is drawn, or that a hit area
 * covers what it looks like it covers. The pure rules are pinned and the call sites are pinned as source
 * text; hardware is the gate.
 */
@Composable
internal fun FileBrowserDiscSetScreen(
    state: FileBrowserDiscSetPrompt,
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
            text = filesResources().getString(FileBrowserDiscSetEyebrow),
            style = MimirTypeScale.RunicEyebrow,
            // textMuted, not an alarm colour -- and this is a CHOICE, not a constraint. The first version
            // of this comment gave a false reason: it claimed the palette has no "warning" role. It has
            // two. `warning` and `danger` are authored semantic roles on `MimirThemePalette`, already
            // consumed by `MimirHintToastRail`'s Warning/Blocking classes and by `HomeTileMenuOverlay`'s
            // destructive row. So the choice stands on the argument that was always the real one: the
            // words are the warning, and a screen that shouts before it has been read is one the player
            // learns to skip. Nothing on this surface is warned about twice.
            color = palette.textMuted,
            maxLines = 1,
        )
        Text(
            text = fileBrowserDiscSetHeadline(filesResources(), state.warning),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 19.sp,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FileBrowserDiscSetRowWidthDp.dp),
        )
        Text(
            text = fileBrowserDiscSetDetail(filesResources(), state.warning),
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = palette.textMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FileBrowserDiscSetRowWidthDp.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Never empty: `discSetMoveOptions` always ends with CANCEL, so "the warning is up" and "there
            // is something to press" stay one fact -- the same contract openTransferChooser holds by
            // refusing an empty row list.
            //
            // `key(option)`, FileBrowserGhostFreeIdentity applied: content-derived, never index-derived.
            state.rows.forEach { option ->
                val optionKey = FileBrowserDiscSetFocus.optionKey(option)
                key(option) {
                    FileBrowserDiscSetOptionRow(
                        option = option,
                        warning = state.warning,
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
 * One option row, with its consequence underneath.
 *
 * **All rows styled identically, including Cancel.** The temptation is to make *move all* louder, since
 * it is the one that keeps the game working — and that is exactly the encoding this module has refused
 * before ([FileBrowserTransferScreen]'s own note): the screen's job is to make the difference legible,
 * not to make the choice. Order already carries the recommendation, and `discSetMoveOptions` owns it.
 *
 * `Modifier.mimirFocusable` + `detectTapGestures` + `.semantics { onClick }`, never `clickable` — the
 * design standing's §3b sanctioned three-part pattern, all three legs, for [FileBrowserTransferScreen]'s
 * stated reasons.
 */
@Composable
private fun FileBrowserDiscSetOptionRow(
    option: DiscSetMoveOption,
    warning: DiscSetWarning,
    focused: Boolean,
    key: FocusKey,
    controller: FileBrowserController,
    authority: FocusAuthority,
    palette: MimirThemePalette,
) {
    val label = fileBrowserDiscSetLabel(filesResources(), option, warning)
    val detail = fileBrowserDiscSetDetail(filesResources(), option, warning)
    Row(
        modifier = Modifier
            // widthIn OUTSIDE fillMaxWidth -- the cap has to narrow the incoming constraints before the
            // fill expands into them. FileBrowserTransferScreen's own ordering.
            .widthIn(max = FileBrowserDiscSetRowWidthDp.dp)
            .fillMaxWidth()
            .heightIn(min = FileBrowserDiscSetRowHeightDp.dp)
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
            // No second scroll owner to fight: this screen has no LazyListState at all.
            .mimirFocusable(authority, key, controller.discSetScope, autoBringIntoView = false)
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleDiscSetTap(key) }) }
            .semantics(mergeDescendants = true) {
                onClick(label = label) {
                    controller.handleDiscSetTap(key)
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
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Blank draws NOTHING rather than an empty line -- the extract chooser's own rule. "Leave it
            // where it is" needs no explanation, and a reserved-but-empty line under it would make the row
            // look like it lost its text.
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    fontSize = 11.5.sp,
                    color = palette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The screen's eyebrow.
 *
 * **`THIS GAME HAS 3 DISCS` would be better and is not available here** — the eyebrow is a constant in
 * every other chooser in this module and the type scale treats it as a fixed rune. `MULTI-DISC GAME`
 * states the category, and the headline right underneath states the specifics, so nothing is lost.
 * Its words are stored in the string resource in sentence case, "Multi-disc game", where this screen
 * previously drew MULTI-DISC GAME.
 */
internal val FileBrowserDiscSetEyebrow: Int = R.string.files_discset_eyebrow

/**
 * The warning's arrival — a constant, on [FileBrowserTransferArrival]'s precedent: this screen's MOUNT is
 * its arrival, so there is no history to track, and it **dips rather than travels** — it appears at
 * [FileBrowserGlideStartAlpha] and firms up, never from nothing. This screen is the one observed snapping on device; the cause was the shared start alpha, not this arrival.
 */
internal val FileBrowserDiscSetArrival: FileBrowserArrival =
    FileBrowserArrival(FileBrowserArrivalKind.SCREEN, epoch = 0L)

/** Identical to the transfer chooser's, referenced rather than re-derived — same content-column slot. */
internal const val FileBrowserDiscSetRowWidthDp: Float = FileBrowserTransferRowWidthDp

/** Identical to the transfer chooser's, referenced rather than re-derived — same reason. */
internal const val FileBrowserDiscSetRowHeightDp: Float = FileBrowserTransferRowHeightDp
