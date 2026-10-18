package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * How much of an etched chip's metal rim the focus ring has taken over: 0 with the rim whole, 1 once the ring has the edge.
 *
 * The rim hands over on the ring's own clock. Thor and 120 Hz Odin captures exposed a brief
 * blink when a focused chip dropped its rim in ONE step (`rimAlpha = if (focused) 0f ...`) while the ring meant
 * to replace it arrives over [mimirFocusRingSpec] (420 ms settled, 140 ms while the pad is held) from almost nothing: for the first frames of
 * every focus move the destination had neither rim nor ring, a flat dark pill, and the pill that was LEAVING got its rim back in one step while its
 * ring was still fading, two outlines at once. Now the rim is `rest alpha x (1 - handover)`, with [handover] on the SAME spec, the same
 * reduced-motion snap and the same start as the ring the caller chains beside the chip (`mimirSelectionRing(focused = ...)`, which carries the same
 * value): at every frame the rim is as strong as the ring is not, so there is no frame with neither, and no frame with both whole.
 *
 * **Costs nothing for a chip that is never focused.** A footer keycap or a count chip passes no `focused`; its handover is the constant 0 and it
 * allocates no animation. The animation exists from the first composition in which the chip is focused, and starts where the chip was: 1 when it
 * is MOUNTED focused (a dialog that opens on a focused button, as the ring itself does: settled at once), 0 otherwise, so the first focus move
 * is animated, never snapped.
 *
 * Read the result only inside a draw block.
 */
@Composable
internal fun rememberEtchedChipRimHandover(focused: Boolean): () -> Float {
    val state = remember { EtchedChipRimState(focusedAtMount = focused) }
    if (focused) state.everFocused = true
    if (!state.everFocused) return EtchedChipNoHandover
    val reducedMotion = mimirReducedMotion()
    val progress = remember { Animatable(if (state.focusedAtMount) 1f else 0f) }
    // The clock is read where the target changes, as the ring does, so a traversal at speed hands over at the ring's fast clock.
    val spec = if (reducedMotion) null else mimirFocusRingSpec<Float>()
    LaunchedEffect(focused) {
        val target = if (focused) 1f else 0f
        if (progress.value == target) return@LaunchedEffect
        if (spec == null) progress.snapTo(target) else progress.animateTo(target, spec)
    }
    return remember(progress) { { progress.value } }
}

/** What a rim is drawn at: its resting [restAlpha] less what the ring has taken over. */
internal fun mimirEtchedChipRimAlpha(restAlpha: Float, handover: Float): Float = restAlpha * (1f - handover.coerceIn(0f, 1f))

/** The ring-coloured rim of an `edgeFlush` chip at [handover]: the ring's own bright stroke at the same progress, so that rim and stroke are one band. */
internal fun mimirEtchedChipRimTintAlpha(handover: Float): Float = MimirFocusRingBrightAlpha * handover.coerceIn(0f, 1f)

/**
 * The alpha of the metal rim UNDER the ring-coloured rim of an `edgeFlush` chip. The two together cover `c = rest + (bright - rest) x h`: from the rim at rest to the
 * ring's bright stroke, in a straight line, with no dip in between (cross-fading two layers at `rest x (1 - h)` and `rest x h` would sag to three quarters of a whole rim half
 * way). Solving `1 - (1 - under)(1 - tint) = c` for `under` gives [restAlpha] at the start and 0 at the end, where the tint alone is the rim.
 */
internal fun mimirEtchedChipRimUnderAlpha(restAlpha: Float, handover: Float): Float {
    val h = handover.coerceIn(0f, 1f)
    val covered = restAlpha + (MimirFocusRingBrightAlpha - restAlpha) * h
    val tint = mimirEtchedChipRimTintAlpha(h)
    if (tint >= 1f) return 0f
    return (1f - (1f - covered) / (1f - tint)).coerceIn(0f, 1f)
}

private val EtchedChipNoHandover: () -> Float = { 0f }

/** Plain memory for one chip's hand-over: whether it was mounted focused, and whether it has ever been focused (no animation until it is). */
private class EtchedChipRimState(val focusedAtMount: Boolean) {
    var everFocused: Boolean = focusedAtMount
}
