package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import kotlin.math.max
import kotlin.math.min

/*
 * The Mimir-button picker's ADJUST mode. The rules are [mimirButtonPickerTransition]'s; this file is what the player sees and touches:
 * the stage (a large coin to work on beside the real 56 dp button) and the chips that stand where the tiles do.
 */

/** Maximum large-coin size as a multiple of the live button: approximately 3x. */
internal const val MimirAdjustCoinMaxScale: Float = 3f

/** Test tags: the large coin's touch layer, and a chip by its word. */
internal const val MimirAdjustCoinTag = "mbutton-adjust-coin"
internal fun mimirAdjustChipTag(label: String) = "mbutton-adjust-chip-$label"

private const val MimirAdjustStageGapDp = 16f
private const val MimirAdjustStagePaddingDp = 12f

/** The caption under a coin: its one line of micro type at the user's font scale, and the 8 dp the seat puts between the coin and it. */
private fun adjustCaptionBandDp(fontScale: Float): Float = 8f + 12f * fontScale

/** The widest the real button's caption ("ACTUAL SIZE · 56 DP") runs in micro type, per unit of font scale: the seat is that wide, not the coin's 56 dp. */
private fun adjustRealSeatWidthDp(fontScale: Float): Float = max(56f, 112f * fontScale)

/**
 * How many times the real 56 dp button the large coin is drawn, for a stage [availableWidthDp] x [availableHeightDp] inside its padding: up to
 * [MimirAdjustCoinMaxScale], fewer when the stage is short (a 360 dp square handheld at a large font has room for about 2x) or narrow, and
 * never below 1, so the large coin is never smaller than the real one it sits beside. Pure, so the stage's fit can be pinned off-device.
 */
internal fun adjustCoinScale(availableWidthDp: Float, availableHeightDp: Float, fontScale: Float): Float {
    val scale = fontScale.coerceAtLeast(1f)
    val byHeight = (availableHeightDp - adjustCaptionBandDp(scale)) / 56f
    val byWidth = (availableWidthDp - adjustRealSeatWidthDp(scale) - MimirAdjustStageGapDp) / 56f
    return min(MimirAdjustCoinMaxScale, min(byHeight, byWidth)).coerceAtLeast(1f)
}

/**
 * Adjust mode's stage: the large coin to work on (touch drags it and pinches it) beside the real 56 dp button, which is the truth. Both are
 * drawn by [coin] from the SAME [mark] -- the image wearing the placement being shaped -- so they cannot disagree: the app hosts the native
 * overlay's own view for each, and the large one is that view scaled by the compositor, as the browse stage's 2x copy is.
 *
 * [onGesture] gets a drag in dp on the reference 56 dp coin (the pixels divided by the density and by the coin's scale, so a finger and a pad
 * step move the image the same distance on the real button) and the pinch's ratio.
 */
@Composable
fun BoxScope.MimirButtonPickerAdjustStageLayout(
    mark: MimirButtonMark,
    onGesture: (dxDp: Float, dyDp: Float, zoom: Float) -> Unit,
    coin: @Composable (mark: MimirButtonMark, scale: Float) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val fontScale = LocalDensity.current.fontScale
    // The scene without its sun: on a strip the Default theme's sun anchors lower right, on top of the "ACTUAL SIZE" caption (review 2026-10-13).
    MimirShelfBackground(modifier = Modifier.fillMaxSize(), palette = palette, sceneLight = false) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(MimirAdjustStagePaddingDp.dp)) {
            val scale = adjustCoinScale(maxWidth.value, maxHeight.value, fontScale)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageSeat(caption = "DRAG · PINCH", palette = palette) { WorkCoin(mark, scale, onGesture, coin) }
                // The real button sits in a seat as tall as the large coin's, so the two captions share a line.
                StageSeat(caption = "ACTUAL SIZE · 56 DP", palette = palette) {
                    Box(Modifier.height(MimirButtonStageCoinSize * scale), contentAlignment = Alignment.Center) {
                        StageCoinBox(scale = 1f) { coin(mark, 1f) }
                    }
                }
            }
        }
    }
}

/** The coin to work on: [coin] at [scale], with a transparent touch layer over it that reads the drag and the pinch. */
@Composable
private fun WorkCoin(
    mark: MimirButtonMark,
    scale: Float,
    onGesture: (dxDp: Float, dyDp: Float, zoom: Float) -> Unit,
    coin: @Composable (mark: MimirButtonMark, scale: Float) -> Unit,
) {
    // Long-lived gesture node, short-lived targets: read through rememberUpdatedState so a recomposition never restarts a drag in flight.
    val currentGesture by rememberUpdatedState(onGesture)
    val currentScale by rememberUpdatedState(scale)
    // The one focus ring, hugging the circle the pad moves: with the stage's focus on the image's tile and the coin the thing the D-pad acts on, a pad user
    // would otherwise see nothing lit (review 2026-10-13). Always on while adjusting; paint only, the coin takes no focus of its own.
    Box(
        Modifier
            .size(MimirButtonStageCoinSize * scale)
            .mimirSelectionRing(focused = true, identity = mark.id, shape = MimirRingShape.Circle),
    ) {
        StageCoinBox(scale = scale) { coin(mark, scale) }
        Box(
            Modifier
                .matchParentSize()
                .testTag(MimirAdjustCoinTag)
                // Its own node for a screen reader, not folded into the stage (the chips beside it do the same by touch).
                .semantics(mergeDescendants = true) { contentDescription = "Your mark. Drag to move it, pinch to size it." }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Pixels on the large coin to dp on the reference coin: the coin is drawn `scale` times the real button.
                        val perDp = density * currentScale
                        currentGesture(pan.x / perDp, pan.y / perDp, zoom)
                    }
                },
        )
    }
}

/**
 * The chips that stand where the tiles do in adjust mode: Smaller · Larger · Reset · Done (touch; each does what the pad key does). Each is an
 * unselected etched chip inside a 48 dp hit box, tap-only (no focus key: the pad's focus stays on the image's tile, like the tile's ✕), with no
 * fill of its own -- the plate is the only surface. One line each, never wrapped.
 */
@Composable
internal fun BoxScope.MimirButtonAdjustChips(
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdjustChip(MimirButtonChipSmaller, onSmaller)
        AdjustChip(MimirButtonChipLarger, onLarger)
        AdjustChip(MimirButtonChipReset, onReset)
        AdjustChip(MimirButtonChipDone, onDone)
    }
}

/** An unselected etched chip inside a 48 dp hit box (update: the plate is the only surface, never a filled or tinted button). */
@Composable
private fun AdjustChip(label: String, onActivate: () -> Unit) {
    val palette = LocalMimirPalette.current
    Box(
        Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .testTag(mimirAdjustChipTag(label))
            .mimirActivatable(label = label, onActivate = onActivate),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(palette = palette) {
            MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(), maxLines = 1, softWrap = false)
        }
    }
}
