package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A hint whose words change never cuts: its last frame dissolves while the new words arrive.
 * (A line whose segments are shared by the lines it changes between, so that the shared words must stay whole and never print twice, takes
 * [MimirSegmentedHintSwap] instead: this one cross-fades the whole line as pixels, which cannot tell a shared word from a changed one.)
 *
 * The simultaneous alpha crossfade uses
 * [MimirMotion.OverlayMs] with [MimirMotion.Easing] -- the dialect [MimirHintToastRail] already speaks when its
 * message changes -- with no travel, because travel means direction and a hint changing in place has none.
 *
 * **Keyed on [key], the hint's CONTENT, never on focus.** Moving from one game to another shows the same words and
 * animates nothing; only a change of words does. That is the main defence against a strobe under a held D-pad.
 *
 * **Pixels, not compositions.** The live hint's pixels are recorded into one of two reusable layers every frame
 * ([MimirTransitionEpochLedger], the shape `MimirPixelFadeHost` uses). On a change the ledger rotates the layers:
 * the old words are frozen pixels drawn at a falling alpha, the new words are the only live composition. Both
 * alphas are read in the draw phase, so a swap recomposes and relays out nothing per frame, and never holds more
 * than two layers. A change mid-swap re-targets from the alpha on screen; nothing queues.
 *
 * **Width.** Wrap the whole hint, pill body included, so a hint that changes width dissolves instead of jumping.
 * The old frame is drawn at [anchor] inside the new bounds: `CenterEnd` for a hint pinned right, `CenterStart` for
 * one that reads from the left, `Center` for a centred one. Never place this inside a clip, or a wider old frame is
 * cut off.
 *
 * The first composition is already settled, so a hint arriving with its page never runs a second transition on
 * top of the page's own. Under reduced motion ([mimirReducedMotion]) a change is instant and nothing is recorded
 * for the outgoing side. Input is never delayed: this is pixels only.
 *
 * Glide: non-zero [glide] replaces the dissolve with a directional handover in miniature,
 * using the same out-then-in order as onboarding:
 * - the old words travel [glide] toward the start while they fade, over [MimirMotion.OverlayMs];
 * - then the new words arrive from [glide] toward the end while they fade in, over [MimirMotion.OverlayMs].
 *
 * Both are drawn at a shift, so nothing relays out. A change during the out half starts the new departure from the
 * words' shift and alpha on screen.
 *
 * [durationMs] is each half's clock (and the dissolve's); [MimirMotion.OverlayMs] unless a surface keeps its own
 * tempo, as onboarding does.
 */
@Composable
fun MimirHintSwap(
    key: Any?,
    modifier: Modifier = Modifier,
    anchor: Alignment = Alignment.Center,
    glide: Dp = 0.dp,
    durationMs: Int = MimirMotion.OverlayMs,
    content: @Composable () -> Unit,
) {
    val reducedMotion = mimirReducedMotion()
    val glidePx = with(LocalDensity.current) { glide.toPx() }
    val firstLayer = rememberGraphicsLayer()
    val secondLayer = rememberGraphicsLayer()
    val layers = remember(firstLayer, secondLayer) { listOf(firstLayer, secondLayer) }
    // Draw-thread snapshots, not Compose state: the alpha each layer was last painted at, so a change mid-swap
    // starts the departing words from where they are on screen rather than from full.
    val displayedAlphas = remember { floatArrayOf(1f, 0f) }
    // The shift each layer was last painted at, for the same reason: a glide re-targeted mid-way leaves from there.
    val displayedShifts = remember { floatArrayOf(0f, 0f) }
    val epochs = remember { MimirTransitionEpochLedger(key) }
    val frame = remember(key) { epochs.prepare(key) { from, to -> from != to && !reducedMotion } }
    SideEffect { epochs.commit(frame) }

    val incomingAlpha = remember(frame.epoch) { Animatable(if (frame.outgoingLayerIndex == null) 1f else 0f) }
    val outgoingStartAlpha = remember(frame.epoch) {
        frame.outgoingLayerIndex?.let { displayedAlphas[it].coerceIn(0f, 1f) } ?: 0f
    }
    val outgoingStartShift = remember(frame.epoch) { frame.outgoingLayerIndex?.let { displayedShifts[it] } ?: 0f }
    val outgoingAlpha = remember(frame.epoch) { Animatable(outgoingStartAlpha) }
    LaunchedEffect(frame.epoch) {
        if (frame.outgoingLayerIndex == null) {
            incomingAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        if (glidePx > 0f) {
            // Out, then in. The out half is as long as the alpha it still has to lose.
            val outMs = (durationMs * outgoingStartAlpha).roundToInt()
            outgoingAlpha.animateTo(0f, tween(outMs, easing = MimirMotion.Easing))
            incomingAlpha.animateTo(1f, tween(durationMs, easing = MimirMotion.Easing))
            return@LaunchedEffect
        }
        coroutineScope {
            launch { outgoingAlpha.animateTo(0f, tween(durationMs, easing = MimirMotion.Easing)) }
            incomingAlpha.animateTo(1f, tween(durationMs, easing = MimirMotion.Easing))
        }
    }

    Box(
        modifier = modifier.drawWithContent {
            val primaryIndex = frame.primaryLayerIndex
            val primary = layers[primaryIndex]
            primary.alpha = 1f
            primary.record { this@drawWithContent.drawContent() }
            val alpha = incomingAlpha.value
            primary.alpha = alpha
            displayedAlphas[primaryIndex] = alpha
            val shift = mimirHintGlideShiftPx(glidePx, gone = 1f - alpha, incoming = true, layoutDirection)
            displayedShifts[primaryIndex] = shift
            translate(shift, 0f) { drawLayer(primary) }

            val outgoingIndex = frame.outgoingLayerIndex ?: return@drawWithContent
            val fading = outgoingAlpha.value
            displayedAlphas[outgoingIndex] = fading
            if (fading <= 0f) return@drawWithContent
            val outgoing = layers[outgoingIndex]
            outgoing.alpha = fading
            val offset = mimirHintSwapOutgoingOffset(
                outgoing = outgoing.size,
                space = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                anchor = anchor,
                layoutDirection = layoutDirection,
            )
            val gone = if (outgoingStartAlpha > 0f) 1f - fading / outgoingStartAlpha else 1f
            val outgoingShift = outgoingStartShift +
                mimirHintGlideShiftPx(glidePx, gone, incoming = false, layoutDirection)
            displayedShifts[outgoingIndex] = outgoingShift
            translate(offset.x + outgoingShift, offset.y.toFloat()) { drawLayer(outgoing) }
        },
    ) {
        content()
    }
}

/**
 * Where the departing frame sits inside the arriving hint's bounds: aligned at [anchor], so a hint pinned right
 * keeps its right edge and a centred one its centre. A wider departing frame gets a negative offset and overhangs
 * both sides evenly (or the free side), which is why the swap must never sit inside a clip.
 */
fun mimirHintSwapOutgoingOffset(
    outgoing: IntSize,
    space: IntSize,
    anchor: Alignment,
    layoutDirection: LayoutDirection,
): IntOffset = anchor.align(outgoing, space, layoutDirection)

/**
 * A gliding hint's shift along x, in px. [gone] runs 0 -> 1 as a side leaves (the departing words) or as the rest
 * of its way is covered (the arriving words: 1 - its alpha). The departing words travel toward the start, the
 * arriving ones come from the end, so the glide reads forward in either layout direction. 0 when [travelPx] is 0.
 */
fun mimirHintGlideShiftPx(travelPx: Float, gone: Float, incoming: Boolean, layoutDirection: LayoutDirection): Float {
    if (travelPx <= 0f) return 0f
    val towardTheEnd = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val travelled = travelPx * gone.coerceIn(0f, 1f)
    return if (incoming) travelled * towardTheEnd else -travelled * towardTheEnd
}
