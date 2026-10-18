package com.gruning.mimir.rewrite.feature.files

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing

/**
 * One platform-folder destination screen shared by Extract, Move and Copy.
 *
 * Takes the folio's slot exactly as [FileBrowserExtractScreen] and [FileBrowserTransferScreen] do, and
 * for the argument stated in full there: every ring and the whole inspector on this surface are derived
 * from a scope-filtered key that goes null the moment `pushScope` runs, so an overlay would leave a
 * ringless folio and an empty inspector behind the question.
 *
 * ## The one structural difference from the two screens it mirrors
 *
 * **This one scrolls, and it is the first screen in this module outside the folio that had to.** The
 * choosers are a fixed two or three rows and are plain `Column`s; `FileBrowserTransferChoice`'s own KDoc
 * records that a fourth row there *"clips silently"*. This list is as long as the player's drives make
 * it — a card with a dozen platform folders is ordinary — so it owns a [rememberLazyListState] and
 * follows the ring with [fileBrowserRevealAction], which is the mechanism the browse folio already uses.
 * Virtual focus bounds ([fileBrowserDestinationOptionBounds]) stay the model `moveFocus` scores against,
 * so a row scrolled out of sight is still reachable — and reaching it is what scrolls it back.
 */
@Composable
internal fun FileBrowserDestinationScreen(
    state: FileBrowserDestinationList,
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Follow the ring. `fileBrowserRevealAction` is the browse folio's own policy, reused rather than
    // re-derived: it answers SeekTo for a row that is off-screen and AnimateTo for one that is merely
    // near the edge, so arriving on this screen does not animate a scroll the player never asked for.
    val density = LocalDensity.current
    LaunchedEffect(focusedKey, state.rows.size) {
        val index = FileBrowserDestinationFocus.indexOf(focusedKey) ?: return@LaunchedEffect
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val action = fileBrowserRevealAction(
            targetIndex = index,
            firstVisibleIndex = layout.visibleItemsInfo.firstOrNull()?.index,
            visibleCount = layout.visibleItemsInfo.size,
            focusedItemOffset = item?.offset,
            focusedItemSize = item?.size,
            viewportStart = layout.viewportStartOffset,
            viewportEnd = layout.viewportEndOffset,
            // Every row on this screen is the same height, so the estimate is a constant rather than the
            // folio's per-kind measurement — there are no tome rows, file rows and headings here to
            // disagree about pitch.
            estimatedItemSize = with(density) { FileBrowserDestinationRowHeightDp.dp.roundToPx() },
        )
        // Same three-way split the folio documents: a seek is unanimated by definition, an
        // index-addressed travel takes Compose's default, and only the measured settle carries a tween.
        when (action) {
            FileBrowserRevealAction.None -> Unit
            is FileBrowserRevealAction.SeekTo -> listState.scrollToItem(action.index, action.scrollOffset)
            is FileBrowserRevealAction.AnimateTo -> listState.animateScrollToItem(action.index, action.scrollOffset)
            is FileBrowserRevealAction.ScrollBy -> listState.animateScrollBy(
                value = action.delta,
                animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
            )
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = stringResource(FileBrowserDestinationEyebrow),
            style = MimirTypeScale.RunicEyebrow,
            color = palette.textMuted,
            maxLines = 1,
        )
        Text(
            // The subject, extension and all — [fileBrowserExtractArchiveLabel]'s referent-collision rule,
            // and sharper here because the rows below are folder names: stripping the extension would
            // leave two lines of this screen naming things that look alike and are not.
            text = state.subjectPath.substringAfterLast('/'),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 19.sp,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FileBrowserDestinationRowWidthDp.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            // A LazyColumn clips on its scroll axis, and each row's selection ring reaches
            // MimirFocusTileRingReach past the row, so both scroll ends carry that reach or the first
            // folder loses its ring's top edge and the last its bottom (ring migration, 2026-09-28).
            contentPadding = PaddingValues(vertical = MimirFocusTileRingReach),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Never empty: `openDestinationList` refuses an empty destination list outright, so "this
            // screen is open" and "there is something to press" stay one fact — the contract both chooser
            // screens state and this one inherits unchanged.
            // `key = the folder's own path` — [FileBrowserGhostFreeIdentity], and NOT the index, which
            // that census forbids module-wide with no exceptions. The rows carry an `animate*AsState`
            // ring, so an index-keyed slot would hand a focus animation from one folder to whatever the
            // next list puts at that position. The browse row has no path and is keyed by its label,
            // which is a constant and is unique in the list by construction.
            itemsIndexed(state.rows, key = { _, row -> row.path ?: row.label }) { index, row ->
                FileBrowserDestinationRowView(
                    row = row,
                    key = FileBrowserDestinationFocus.optionKey(index),
                    focused = FileBrowserDestinationFocus.optionKey(index) == focusedKey,
                    controller = controller,
                    authority = authority,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun FileBrowserDestinationRowView(
    row: FileBrowserDestinationRow,
    key: FocusKey,
    focused: Boolean,
    controller: FileBrowserController,
    authority: FocusAuthority,
    palette: MimirThemePalette,
) {
    Row(
        modifier = Modifier
            // widthIn OUTSIDE fillMaxWidth — FileBrowserExtractOptionRow's ordering, for its reason: the
            // cap has to narrow the incoming constraints before the fill expands into them.
            .widthIn(max = FileBrowserDestinationRowWidthDp.dp)
            .fillMaxWidth()
            .heightIn(min = FileBrowserDestinationRowHeightDp.dp)
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
            // **`autoBringIntoView = false` even though this screen DOES own a scroll owner**, and that is
            // the rule rather than an exception: `MimirFocusable.kt:43-44` is about two scroll systems
            // fighting, and the LaunchedEffect above is this screen's single one. Letting Compose bring
            // the row into view as well would be the second.
            .mimirFocusable(authority, key, controller.destinationScope, autoBringIntoView = false)
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleDestinationTap(key) }) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = fileBrowserDestinationRowLabel(filesResources(), row),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Blank on the browse row, which has no folder yet — and then no line is drawn at all rather
            // than an empty one. Its own trailing "…" is what says it goes somewhere else.
            if (row.detail.isNotBlank()) {
                Text(
                    text = fileBrowserDestinationRowDetail(filesResources(), row),
                    fontSize = 10.sp,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Two different claims, and the player is the one who knows which they meant: "your PS2 folder"
        // (the library sources games from it) versus "a folder called ps2 that exists". Drawn as one word
        // rather than an icon, because an unexplained glyph on a destination row is a guess about what it
        // means, and this screen exists precisely because guessing was the problem.
        if (row.fromLibrary) {
            Text(
                text = stringResource(FileBrowserDestinationLibraryMark),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.accent,
                maxLines = 1,
            )
        }
    }
}

/** The mark on a folder the library actually sources from. */
@StringRes
internal val FileBrowserDestinationLibraryMark: Int = R.string.files_destination_in_library

/** [FileBrowserExtractRowWidthDp]'s derivation, unchanged — see that constant's KDoc. */
internal const val FileBrowserDestinationRowWidthDp: Float = 508f

/** [FileBrowserExtractRowHeightDp]'s value, unchanged: these rows say the same amount. */
internal const val FileBrowserDestinationRowHeightDp: Float = 54f
