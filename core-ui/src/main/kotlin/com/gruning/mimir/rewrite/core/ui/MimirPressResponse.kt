package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The one press response: while a finger holds a control the control draws in to [MimirPressScale], on
 * `tween(MimirMotion.FocusMs, MimirMotion.Easing)` both ways, and nothing else about it changes. It is the same beat as Main's focus ring.
 *
 * **Draw only.** The scale is a `graphicsLayer` read in the draw phase from an [Animatable] that lives in an effect, so a press recomposes nothing, moves
 * nothing in layout, and adds no time to the input path (`MimirMotion.InputDelayMs` is 0 and stays 0). It plants no focus node and takes no pad input:
 * the Bottom is touch-only, and [interactionSource] is only ever READ here.
 *
 * **What pressed means.** A [PressInteraction.Press] starts the response; a [PressInteraction.Release] or a [PressInteraction.Cancel] of that same press
 * ends it, so a press that turns into a drag (a lattice page swipe, a tile lift, a scroll) and is cancelled by whoever took the gesture releases the
 * scale instead of leaving the control stuck in. Presses are tracked as a set, so two overlapping presses release only when both have. A new press
 * retargets the animation from where it stands; nothing queues.
 *
 * **A tap is always seen.** A [PressInteraction.Release] (a tap that went ahead) lets a draw-in that is still on its way finish before the return begins, so a
 * quick tap, and a tap on a `clickable` inside a scrolling container (which reports its Press and Release together when the finger lifts inside the 100 ms
 * tap timeout) shows the whole dip rather than none. A [PressInteraction.Cancel] (the gesture was taken, dragged out or torn down) turns back at once: a
 * swipe never flicks a control in. Both are draw only; the activation itself ran on the finger's lift and waits for nothing.
 *
 * **Reduced motion** (the system's remove-animations setting, read as everywhere else through [mimirReducedMotion]): no scale at all. The control
 * stays at 1 and the press has no motion response; whatever a control says about pressed in colour is its own.
 *
 * **Where it goes in the chain: straight after the control's pointer modifier, and before the clip, border, background and ring it should carry.** The whole
 * control then moves as one and the hit box stays whole: a pointer node chained AFTER a `graphicsLayer` is hit-tested through the layer's transform, so at 0.97 its
 * edge would shrink by 1.5 percent a side while held, and a finger resting near the edge would see the press cancelled.
 *
 * Wiring: hand the same [MutableInteractionSource] to the control's activation (`clickable`/`combinedClickable`'s `interactionSource`, or
 * `mimirActivatable`'s) and to this. A bare `detectTapGestures` node reports through [mimirReportPress] from its `onPress`.
 */
@Composable
fun Modifier.mimirPressResponse(interactionSource: InteractionSource): Modifier {
    val reducedMotion = mimirReducedMotion()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource, reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        // A new source, or a source that arrives mid-press, starts from rest: nothing carries over from the one that was replaced.
        scale.snapTo(1f)
        val held = HashSet<PressInteraction.Press>()
        var drawIn: Job? = null
        var drawOut: Job? = null
        interactionSource.interactions.collect { interaction ->
            var tapped = false
            when (interaction) {
                is PressInteraction.Press -> held.add(interaction)
                is PressInteraction.Release -> { held.remove(interaction.press); tapped = true }
                is PressInteraction.Cancel -> held.remove(interaction.press)
                else -> return@collect
            }
            // Whatever was heading back is retargeted from where the scale stands: cancelled, never queued, and the collector is never held up by an animation.
            drawOut?.cancel()
            if (held.isNotEmpty()) {
                if (drawIn?.isActive != true) {
                    drawIn = launch { scale.animateTo(MimirPressScale, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)) }
                }
            } else {
                val arriving = drawIn?.takeIf { tapped && it.isActive }
                if (arriving == null) drawIn?.cancel()
                drawOut = launch {
                    // A tap lets a draw-in that is still on its way finish first, so it is seen; a cancel turns back at once.
                    arriving?.join()
                    scale.animateTo(1f, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
                }
            }
        }
    }
    return this.graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
    }
}

/**
 * Reports one press of a bare `detectTapGestures` node to [source], for a control that has no `clickable` to do it: [PressInteraction.Press] now,
 * [PressInteraction.Release] when the pointer comes up inside the node and the tap goes ahead, [PressInteraction.Cancel] when the gesture is
 * cancelled, consumed by another handler (a pager, a lift) or dragged out. Call it first from `onPress`; it returns when the press is over. A null
 * [source] reports nothing and returns at once, so a call site that has no response stays as it was.
 *
 * `finally` plus the non-suspending `tryEmit` is deliberate: when the node is torn down mid-press (disabled, or the composable leaves) a suspending
 * emit on the cancelled coroutine would never land and the Press would be left dangling.
 */
suspend fun PressGestureScope.mimirReportPress(source: MutableInteractionSource?, offset: Offset) {
    if (source == null) return
    val press = PressInteraction.Press(offset)
    source.tryEmit(press)
    var released = false
    try {
        released = tryAwaitRelease()
    } finally {
        source.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
    }
}
