package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import kotlinx.coroutines.flow.collectLatest

/*
 * OPEN WITH, built before A is pressed (owperf follow-up, 2026-10-15).
 *
 * Thor, compiled beta: the key-down frame of OPEN WITH took 30.7 ms and the next 27.7 (simpleperf, build/test-captures/combined45/thor/PROFILE_OW.md): the frame that pushes the
 * scope is the frame that composes the sheet (a glass panel, a measured viewport, four rows, their icons), measures and lays it out and records it, and no one piece
 * of that is the hotspot. Cutting what the screen behind it re-runs (owperf) did not show on the device, because the sheet itself was the cost.
 *
 * So the sheet is built ahead. Once the card has settled (or the pad is on the OPEN WITH chip, whichever comes first) and its options are loaded, the Library keeps the
 * sheet COMPOSED AND MEASURED, in the very slot the opening will use, but not placed. A node that is measured and not placed is not drawn, not hit-tested, not in the
 * semantics tree and has not laid out its position, so its rows do not register with the focus authority (they do that when placed); the sheet's Back handler is off
 * and it seats no row ([MimirSelectOverlay]'s `active`). Pressing A then changes what it always changed (the scope), the slot places the sheet, the rows register in
 * that frame's layout exactly as they did, and the fade runs on the same clocks: only the building has moved off that frame.
 */

/**
 * How long a card is up before its sheet is built ahead if the pad has not reached the OPEN WITH chip: the card's own arrival (the 130 ms lead, then the page clock).
 * The sheet is built a frame after that, so the card's arrival does not pay for it.
 */
internal val LibraryOpenWithPrewarmSettleNanos: Long =
    (MimirMotion.OverlayDissolveCoverMs + MimirMotion.ShineMs) * 1_000_000L

/**
 * Whether the OPEN WITH sheet is to be kept built ahead. [wanted] is "a card is up and its options are loaded"; [onChip] is read once per frame while waiting, not in
 * composition (the host composes with a focus that does not name the card's chip, and a pad move must not recompose it). False the moment [wanted] is, so a closed
 * card takes its sheet with it.
 */
@Composable
fun rememberLibraryOpenWithPrewarm(wanted: Boolean, onChip: () -> Boolean): Boolean {
    var built by remember { mutableStateOf(false) }
    LaunchedEffect(wanted) {
        if (!wanted) {
            built = false
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (!onChip() && withFrameNanos { it } - start < LibraryOpenWithPrewarmSettleNanos) {
            // A frame at a time: the card is still arriving.
        }
        withFrameNanos { }
        built = true
    }
    return built && wanted
}

/**
 * Composes and measures [content] always, and places it only while [active] is true. [active] is read where it is placed, so the flip is a relayout of this slot and
 * not a recomposition of anything; the sheet inside has been built long before. Unplaced content is not drawn, not hit-tested, not in the semantics tree, and fires no
 * `onGloballyPositioned` (the rows' registration).
 */
@Composable
fun LibraryStandbySlot(
    active: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        // An unplaced node is still in the semantics tree (a test, or a screen reader, would find an "Open with" that is not open), so until it is placed the content
        // is cleared from it. Read here, in the slot's own subcomposition: the flip re-runs this wrapper and the content below it is the same lambda.
        val placeables = subcompose(Unit) {
            Box(if (active()) Modifier else Modifier.clearAndSetSemantics { }) { content() }
        }.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) {
            if (active()) placeables.forEach { it.place(0, 0) }
        }
    }
}

/**
 * The options a standing sheet is handed: [latest] while the sheet is [active], and, once it stops being, what it showed until its exit has cleared.
 *
 * A pick writes new options in the very frame that pops the picker's scope (the row names the pick at once, then the reload lands). A sheet that is built ahead is no longer
 * disposed by the pop, so those options reached it as a recomposition and a re-measure of the whole sheet (a viewport, four rows, their icons) in the closing frames, under the
 * fade that is still carrying its pixels (Thor c47: the close got about 10 ms worse). The sheet is now handed the new options one deadline after the pop (the exit's own
 * [MimirMotion.OverlayMs], on the frame clock), once, whether one write or two arrived in between; the card behind the sheet reads the options itself and is not held back.
 * A reopening inside that window is [active] again and gets the latest at once.
 */
@Composable
fun rememberOpenWithSheetOptions(latest: OpenWithOptions, active: () -> Boolean): OpenWithOptions {
    var shown by remember { mutableStateOf(latest) }
    val latestNow = rememberUpdatedState(latest)
    LaunchedEffect(Unit) {
        var armed = false
        var clearAt = 0L
        snapshotFlow { active() to latestNow.value }.collectLatest { (isActive, options) ->
            if (isActive) {
                armed = true
                shown = options
                return@collectLatest
            }
            if (armed) {
                armed = false
                clearAt = withFrameNanos { it } + MimirMotion.OverlayMs * 1_000_000L
            }
            // A deadline, not a wait that starts again with each write: the reload landing inside the fade is held to the same moment.
            while (withFrameNanos { it } < clearAt) {
                // The exit is still carrying the sheet's pixels.
            }
            shown = options
        }
    }
    return shown
}

/**
 * The OPEN WITH sheet of a launch card whose owner holds the options and the open/closed fact itself (Home's card): the standing slot, the options kept off the exit, and
 * the overlay, which are the Library door's three pieces and the same calls. [latest] is the options as they are now, [open] is read where the sheet is placed and where its
 * Back and its first row are decided, never in a body, and the callbacks are the card's own. Hand it callbacks that are the same instances from one pass to the next
 * (bound to the card, remembered): a changed one re-runs the sheet.
 */
@Composable
fun OpenWithStandingSheet(
    latest: OpenWithOptions,
    open: () -> Boolean,
    authority: FocusAuthority,
    onChooseAuto: () -> Unit,
    onChoose: (OpenWithCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = rememberOpenWithSheetOptions(latest, open)
    LibraryStandbySlot(active = open) {
        OpenWithOverlay(
            authority = authority,
            options = options,
            onChooseAuto = onChooseAuto,
            onChoose = onChoose,
            onDismiss = onDismiss,
            active = open,
        )
    }
}

/**
 * The OPEN WITH sheet as the Library hosts it, in its own function so that what is handed to it is compared and not rebuilt: the host's composable lambdas are re-created
 * whenever the host re-runs, which re-runs whatever they call, and a sheet built ahead must not be re-run by the opening that only flips [active].
 */
@Composable
internal fun LibraryOpenWithSheet(
    viewModel: LibraryViewModel,
    authority: FocusAuthority,
    options: OpenWithOptions,
    detailGameState: State<GameRow?>,
    active: () -> Boolean,
) {
    OpenWithOverlay(
        authority = authority,
        options = options,
        onChooseAuto = {
            detailGameState.value?.let { viewModel.resetEmulator(it) }
            authority.popScope()
        },
        onChoose = { candidate ->
            detailGameState.value?.let { viewModel.chooseEmulator(it, candidate) }
            authority.popScope()
        },
        onDismiss = {
            viewModel.closeOpenWith()
            authority.popScope()
        },
        active = active,
    )
}
