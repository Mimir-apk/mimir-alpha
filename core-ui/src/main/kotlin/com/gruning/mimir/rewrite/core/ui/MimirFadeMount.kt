package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * A host that fades in and out on the house overlay clock and is composed only while it is showing: a popover, a toast, a plate that must not cut.
 *
 * The two reads are the point. [mounted] is read in COMPOSITION and changes twice per showing (true as the fade begins, false once it has finished), so a
 * host written `if (visible || fade.mounted) { ... }` recomposes at the ends only. [alpha] is read in a DRAW or layer lambda (`graphicsLayer { alpha = fade.alpha }`)
 * and moves every frame without recomposing anything. The shape this replaces, `if (visible || animatedAlpha > 0.001f)`, read the animated alpha in composition and
 * recomposed its whole host on every frame of the fade.
 *
 * Opening and closing both run [MimirMotion.OverlayMs] with [MimirMotion.Easing]. Under [reducedMotion] the host snaps, as every host does. A host that is already
 * showing when it is first composed starts whole (nothing to fade in from); one that is not showing starts unmounted and at zero.
 *
 * **[restsWhole]** is for a host whose content has an entry of its own (a picker panel that enters on its own clock under the page's glass): the host adds no second fade on the
 * way in. It is whole whenever it is not leaving, so it is already whole on the first frame it is asked for, and it is made whole again once its fade out has finished and nothing
 * is composed (observed: a host left at nothing added a host fade under the panel's entry on every reopen). A host asked for again while it leaves fades back from where it is.
 */
@Stable
class MimirFadeMount internal constructor(initiallyVisible: Boolean, restsWhole: Boolean = false) {
    internal val progress = Animatable(if (initiallyVisible || restsWhole) 1f else 0f)

    /** True from the frame the host is asked to show until its fade-out has finished. Read in composition. */
    var mounted: Boolean by mutableStateOf(initiallyVisible)
        internal set

    /** The host's alpha, 0..1. Read it inside a draw or layer lambda only. */
    val alpha: Float get() = progress.value
}

/** [MimirFadeMount] for a host that is shown while [visible]. See [MimirFadeMount] for [restsWhole]. */
@Composable
fun rememberMimirFadeMount(visible: Boolean, reducedMotion: Boolean, restsWhole: Boolean = false): MimirFadeMount {
    val fade = remember { MimirFadeMount(visible, restsWhole) }
    LaunchedEffect(visible, reducedMotion) {
        if (visible) {
            fade.mounted = true
            if (reducedMotion) {
                fade.progress.snapTo(1f)
            } else if (fade.progress.value < 1f) {
                fade.progress.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
            }
        } else {
            if (reducedMotion) {
                fade.progress.snapTo(0f)
            } else {
                fade.progress.animateTo(0f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
            }
            fade.mounted = false
            // Whole again while nothing is composed, so the next showing is not faded in a second time under the content's own entry.
            if (restsWhole) fade.progress.snapTo(1f)
        }
    }
    return fade
}
