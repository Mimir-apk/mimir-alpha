package com.gruning.mimir.rewrite

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirHold

/**
 * The glass the Favorites step puts under its platform list (RC-27 / RC-05, audit 2026-10-14, O-27 and O-27e). The list blurs the sky
 * and the boxes together , and the blur is a progress read only inside a draw block
 * (`mimirGlassBackdropProgress`), so a frame of its ramp invalidates a draw and never recomposes the page.
 *
 * The timeline, from the frame [open] changes:
 *  - opening ramps 0 to 1 over [MimirMotion.OverlayMs] on [MimirMotion.Easing], from the frame beat after the flag flips, with the
 *    list fading in on the same clock beside it;
 *  - closing holds the glass at its current strength for [MimirMotion.OverlayDissolveCoverMs] while the list dissolves (08-15: a
 *    modal dissolves fully before the glass under it clears), then clears it over [MimirMotion.OverlayMs].
 *
 * [mimirHold] waits on the animation clock, so the hold scales with the animator setting exactly as the fades it covers do. A flag
 * that flips back mid-hold retargets: nothing replays and nothing snaps.
 */
@Composable
internal fun rememberOnboardingFavoritesFilterGlass(open: Boolean): () -> Float {
    val glass = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            // The frame beat before any pixel moves (UI-01): a subtree still rebuilding must not restart this at its target.
            withFrameNanos { }
            glass.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        } else {
            // Nothing to clear (first composition, or the glass never came up).
            if (glass.value <= 0f) return@LaunchedEffect
            mimirHold(MimirMotion.OverlayDissolveCoverMs)
            glass.animateTo(0f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
        }
    }
    return { glass.value }
}
