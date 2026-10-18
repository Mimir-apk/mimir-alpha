package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion

/**
 * Whether the gamelist's Hints button is on screen, and how much of it (audit 2026-10-14, RC-18, L-15 / X-60a: "the shelf's Hints circle
 * overprints B BACK during the launch-card close").
 *
 * The button used to be mounted by `if (visible)`: it left the frame the detail took the scope and came back, at full alpha, on the frame
 * the scope returned, while the card it came back under was still dissolving, so for the card's last 180 ms the circle stood over the card's
 * own "B BACK". Here it FADES on alpha instead, on the swap grammar every other page uses (the product's one language): it leaves on
 * [MimirMotion.OverlayMs] at once, and when it comes back it waits [MimirMotion.OverlayDissolveCoverMs], the point where the page it comes
 * back under is at alpha .044, and then arrives on [MimirMotion.OverlayMs]. It stays composed only while it has any alpha (or is about to), so
 * a button nobody can see takes no touches and costs nothing. The first composition is a settled state, not an arrival.
 *
 * [alpha] is read only in a draw block (`graphicsLayer`), never in composition.
 */
@Stable
internal class LibraryHintRailPresence(val animatable: Animatable<Float, AnimationVector1D>, mounted: Boolean) {
    /** True while the button is on screen or on its way: the composition is held for it. */
    var mounted by mutableStateOf(mounted)
        internal set

    val alpha: Float get() = animatable.value
}

@Composable
internal fun rememberLibraryHintRailPresence(wanted: Boolean): LibraryHintRailPresence {
    val reducedMotion = mimirReducedMotion()
    val presence = remember { LibraryHintRailPresence(Animatable(if (wanted) 1f else 0f), mounted = wanted) }
    LaunchedEffect(wanted, reducedMotion) {
        if (wanted) presence.mounted = true
        val animatable = presence.animatable
        if (reducedMotion) {
            animatable.snapTo(if (wanted) 1f else 0f)
        } else if (wanted) {
            // A re-arrival waits out the lead; one that is already part-way (the scope came back mid-fade) carries on from where it is.
            animatable.animateTo(
                1f,
                tween(
                    MimirMotion.OverlayMs,
                    delayMillis = if (animatable.value <= 0f) MimirMotion.OverlayDissolveCoverMs else 0,
                    easing = MimirMotion.Easing,
                ),
            )
        } else {
            animatable.animateTo(0f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        }
        if (!wanted) presence.mounted = false
    }
    return presence
}

/** The composition is held for a button that is wanted or still has alpha: the frame it is wanted is the frame it mounts. */
internal fun LibraryHintRailPresence.composed(wanted: Boolean): Boolean = wanted || mounted
