package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics

/*
 * The swap tier: one page replacing another in place (the tile menu's pages, Utility's two surfaces, the Tome library's
 * browser and editor).
 *
 * The departing page fades on [MimirMotion.OverlayMs]. The arriving one waits [MimirMotion.OverlayDissolveCoverMs], the
 * measured point where the departing page is at alpha 0.044, then fades in on OverlayMs: 310 ms in all, and on no frame
 * are both pages above alpha 0.15. This is the 08-15 double-exposure law applied with the measured CP01 lead, so it
 * needs no new timing constant. The crossfades it replaces ran both 180 ms fades at once: two full panels at about half alpha
 * each, mid-way.
 *
 * That is an entry law. A return (B, Cancel, a finished pick: back to the page this one was opened from) takes the
 * page tier's return transition instead ([MimirPageMotion.ReturnMs]): the page returned to is
 * at rest from the first frame and the page left dissolves over it on ReturnMs. Holding a return behind the 130 ms lead
 * blanked the screen after a B press that had already landed (review of 89d626d38).
 *
 * On Nova, a Customize media icon-art return still looked like a snap. The departing glass lets
 * the page beneath show through, and the fade
 * is mostly done in 80 ms, so the parent seemed simply to be there. With a travel the parent now glides back down into
 * place on the same [MimirPageMotion.ReturnMs] while the page left dissolves over it: still at full alpha from the
 * first frame (no blank, no lead), still as quick as today. And the resize on a return runs on ReturnMs too; at 0 it
 * jumped to the parent's height on the first frame and clipped the taller page still dissolving.
 */

/** The departing page's clock: [MimirMotion.OverlayMs], or [MimirPageMotion.ReturnMs] on a return. */
fun <T> mimirSwapExitSpec(reducedMotion: Boolean, returning: Boolean = false): FiniteAnimationSpec<T> = tween(
    durationMillis = when {
        reducedMotion -> 0
        returning -> MimirPageMotion.ReturnMs
        else -> MimirMotion.OverlayMs
    },
    easing = MimirMotion.Easing,
)

/**
 * The arriving page's clock: the same fade, after the departing one has gone. On a return the page is at rest at once,
 * with no lead: the page left dissolves over it ([mimirSwapTransform] draws it on top).
 */
fun <T> mimirSwapEnterSpec(reducedMotion: Boolean, returning: Boolean = false): FiniteAnimationSpec<T> = tween(
    durationMillis = if (reducedMotion || returning) 0 else MimirMotion.OverlayMs,
    delayMillis = if (reducedMotion || returning) 0 else MimirMotion.OverlayDissolveCoverMs,
    easing = MimirMotion.Easing,
)

/**
 * The swap's placement clock: a forward arrival's travel rides [mimirSwapEnterSpec]; a return's glide back
 * and the resize on a return ride [MimirPageMotion.ReturnMs], so neither snaps.
 */
fun <T> mimirSwapTravelSpec(reducedMotion: Boolean, returning: Boolean = false): FiniteAnimationSpec<T> =
    if (returning) mimirSwapExitSpec(reducedMotion, returning = true) else mimirSwapEnterSpec(reducedMotion)

/**
 * The swap as an `AnimatedContent` transform. The resize rides the arrival's clock, unclipped, never the default
 * spring a bare `togetherWith` lets through .
 *
 * [fade] false is for pages that fade themselves through [mimirSwapFadeAlpha] and [mimirRingSafeFade], because a ring
 * reaches past them: `fadeIn`/`fadeOut` fade through a layer the size of the page, which cuts it.
 *
 * [returning] is the host's call (a move back toward its home page): the page returned to sits under the page left,
 * which dissolves over it, as on the page tier.
 *
 * [returnTravelPx]: on a return the page returned to glides down from that far above into place on
 * [MimirPageMotion.ReturnMs] while the page left dissolves as before. [enterTravelPx]: on a forward swap the page left rises by it
 * and the arriving one comes up from that far below, on the swap's clocks. Travel is placement, never a layer, so it
 * is safe on pages that fade themselves to keep their rings whole.
 */
fun mimirSwapTransform(
    reducedMotion: Boolean,
    fade: Boolean = true,
    returning: Boolean = false,
    returnTravelPx: Int = 0,
    enterTravelPx: Int = 0,
): ContentTransform {
    val travel = if (returning) returnTravelPx else enterTravelPx
    var enter = if (fade) fadeIn(mimirSwapEnterSpec(reducedMotion, returning)) else EnterTransition.None
    var exit = if (fade) fadeOut(mimirSwapExitSpec(reducedMotion, returning)) else ExitTransition.None
    if (travel != 0 && !reducedMotion) {
        enter += slideInVertically(mimirSwapTravelSpec(reducedMotion, returning)) { if (returning) -travel else travel }
        // The page left keeps its plain dissolve on a return: a sink would keep a never-shown copy (a swap reversed
        // inside the lead) composed and taking touches for the whole ReturnMs, where the fade alone drops it at once.
        if (!returning) exit += slideOutVertically(mimirSwapExitSpec(reducedMotion)) { -travel }
    }
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        targetContentZIndex = if (returning) -1f else 0f,
        sizeTransform = SizeTransform(clip = false) { _, _ -> mimirSwapTravelSpec(reducedMotion, returning) },
    )
}

/**
 * The swap's one-clock form, for a page that replaces another in place and comes back the same way (a Settings sub-page and its parent): the Library's Carousel/List swap
 * (`libraryViewCrossfadeSpec`) as an `AnimatedContent` transform. ONE [MimirMotion.OverlayMs] on the house easing, no lead, the same in both directions
 *: the swap tier's lead left the pane near-empty from about 130 ms ("dips through dark"), and its return dissolved the page left over the page returned to for
 * three frames of two pages' text; they were two behaviours for a gesture and its reverse.
 *
 * Here the page left fades out while the page arriving fades in, complementary at every frame, so the pane is never emptier than the cross-fade's own overlap (a coverage of 0.75 at the
 * midpoint) and the two are readable together for about two frames, not three. A swap of translucent pages cannot have both a pane that is never emptier and no frame with both showing;
 * this holds a floor under the first and a ceiling over the second, the same each way. Both pages also travel [travelPx] (the caller's [MimirMotion.SwapTravelDp] in px) along the vertical
 * axis of the change: forward the page arriving rises from below and the page left lifts away, [returning] mirrors it (the page arriving comes down from above), and a swap that is not
 * moving a page by [travelPx] passes 0. Travel is placement, never a layer, so it is safe on pages that fade themselves with [mimirRingSafeFade]; [fade] false is for those pages, which
 * draw their own alpha from [mimirCrossSwapFadeAlpha]. The resize rides the same clock, unclipped. Reduced motion snaps.
 */
fun mimirCrossSwapTransform(
    reducedMotion: Boolean,
    fade: Boolean = true,
    returning: Boolean = false,
    travelPx: Int = 0,
): ContentTransform {
    var enter = if (fade) fadeIn(mimirCrossSwapSpec(reducedMotion)) else EnterTransition.None
    var exit = if (fade) fadeOut(mimirCrossSwapSpec(reducedMotion)) else ExitTransition.None
    if (travelPx != 0 && !reducedMotion) {
        val direction = if (returning) -1 else 1
        enter += slideInVertically(mimirCrossSwapSpec(reducedMotion)) { direction * travelPx }
        exit += slideOutVertically(mimirCrossSwapSpec(reducedMotion)) { -direction * travelPx }
    }
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        sizeTransform = SizeTransform(clip = false) { _, _ -> mimirCrossSwapSpec(reducedMotion) },
    )
}

/** The cross-swap's one duration, for a host that arrives a sub-page by its own pixel fade (the Theme gallery) and must stand on the same clock as [mimirCrossSwapSpec]. */
const val MimirCrossSwapMs: Int = MimirMotion.OverlayMs

/** [mimirCrossSwapTransform]'s one clock, for both pages and the resize: [MimirCrossSwapMs] on the house easing, no lead, 0 under reduced motion. */
fun <T> mimirCrossSwapSpec(reducedMotion: Boolean): FiniteAnimationSpec<T> = tween(
    durationMillis = if (reducedMotion) 0 else MimirCrossSwapMs,
    easing = MimirMotion.Easing,
)

/** A page's own alpha on [mimirCrossSwapSpec], for a host built with `mimirCrossSwapTransform(fade = false)`: the same clock in and out, so the two pages are complementary. */
@Composable
fun AnimatedVisibilityScope.mimirCrossSwapFadeAlpha(reducedMotion: Boolean): State<Float> =
    mimirRingSafeFadeAlpha(
        enter = mimirCrossSwapSpec(reducedMotion),
        exit = mimirCrossSwapSpec(reducedMotion),
    )

/**
 * A page's own alpha on the swap clocks, for a host built with `mimirSwapTransform(fade = false)`. Pass the host's
 * [returning], computed from its current target outside the pages, so the page being left picks up the return clock in
 * the same composition that retargets it.
 */
@Composable
fun AnimatedVisibilityScope.mimirSwapFadeAlpha(reducedMotion: Boolean, returning: Boolean = false): State<Float> =
    mimirRingSafeFadeAlpha(
        enter = mimirSwapEnterSpec(reducedMotion, returning),
        exit = mimirSwapExitSpec(reducedMotion, returning),
    )

/**
 * A page's alpha, run on the page's own enter/exit transition so `AnimatedContent` keeps a departing page until it has
 * faded out. Promoted from onboarding, where it fixed onboarding pill clipping (2026-09-28), for the swap tier.
 */
@Composable
fun AnimatedVisibilityScope.mimirRingSafeFadeAlpha(
    enter: FiniteAnimationSpec<Float>,
    exit: FiniteAnimationSpec<Float>,
    label: String = "mimirRingSafeFade",
): State<Float> = transition.animateFloat(
    transitionSpec = { if (targetState == EnterExitState.Visible) enter else exit },
    label = label,
) { state -> if (state == EnterExitState.Visible) 1f else 0f }

/**
 * Draws this page at [alpha] per drawing instruction, with no offscreen buffer, so a ring drawn outside it is never
 * cut. The one cost of ModulateAlpha: children that overlap show through each other mid-fade, so it belongs on pages
 * of rows, pills and text, not on panels that stack art.
 */
fun Modifier.mimirRingSafeFade(alpha: State<Float>): Modifier = graphicsLayer {
    this.alpha = alpha.value
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/**
 * The input half of a swap page: what the tile menu's pages have always done, for any page
 * on an `AnimatedContent` or `AnimatedVisibility` transition. Presses during a page change wait for the page they would
 * act on , and a page that stands at alpha 0 through the 130 ms lead is not there to be pressed.
 *
 * [alpha] is a PROBE on the page's own fade specs: [mimirSwapFadeAlpha], or [mimirRingSafeFadeAlpha] given the host's own
 * enter and exit specs. It is read here and drawn nowhere, so a host built with `fadeIn` and `fadeOut` keeps drawing
 * exactly as it did (its own animations run the same specs on the same transition, so the two agree frame for frame).
 * [minAlpha] is where the page counts as shown: 0f is its first painted frame, [MimirPageMotion.ShownAlpha] the readable
 * 15% used for Quick Launch. Shown means ABOVE it, so at 0 this is the tile menu's hold exactly.
 *
 * The pad: from this page's first frame [MimirTransitionInputGate] is held until the page is shown, or a reversal takes
 * it off the target. Back and the shoulders pass the gate as ever. [holdPad] false drops this half: the Bottom is
 * touch-only, and the gate is process-wide, so a Bottom page that held it would silence Main's controller for the lead.
 * It keeps the touch half below unchanged.
 *
 * Touch reaches the reducer without the router, so the returned Modifier goes on the page's root: a page that is leaving
 * is inert (its semantics cleared, no touch), an arriving page takes no touch until it is shown, and a page that shows
 * takes an empty Modifier.
 *
 * Only a page that ARRIVES COLD is gated, and that is decided once, on its first composition ([EnterExitState.PreEnter]).
 * A page a host opens on is already showing, so it holds nothing. So is one remounted inside a dismiss fade that a reopen
 * interrupted (the transition reads PostExit then, which is how Quick Launch's `key(openEpoch)` meets it): the page is
 * already on screen and springs back up, while a probe created in the new composition would start again from 0 and hold
 * the pad, and shield the touch, for a panel the player can see. That is the house behaviour, as at the tile menu.
 */
@Composable
fun AnimatedVisibilityScope.MimirSwapPageGate(
    alpha: State<Float>,
    minAlpha: Float = 0f,
    holdPad: Boolean = true,
): Modifier {
    val pageTransition = transition
    val arrivesCold = remember { pageTransition.currentState == EnterExitState.PreEnter }
    val pageLive = pageTransition.targetState == EnterExitState.Visible
    val shown by remember(alpha, minAlpha) { derivedStateOf { alpha.value > minAlpha } }
    val gateOwner = remember { Any() }
    LaunchedEffect(Unit) {
        // The Bottom is touch-only and must never deafen Main's controller: [MimirTransitionInputGate] is process-wide,
        // so a Bottom page passes holdPad = false and keeps only the touch half below.
        if (!arrivesCold || !holdPad) return@LaunchedEffect
        holdMimirInputUntilShown(gateOwner) {
            alpha.value > minAlpha || pageTransition.targetState != EnterExitState.Visible
        }
    }
    return when {
        !pageLive -> Modifier.clearAndSetSemantics { }.mimirPageTouchShield()
        arrivesCold && !shown -> Modifier.mimirPageTouchShield()
        else -> Modifier
    }
}
