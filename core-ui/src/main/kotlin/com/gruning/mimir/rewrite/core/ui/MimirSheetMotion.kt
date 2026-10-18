package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

/*
 * The sheet tier: a keyboard or a sub-sheet slides in by its
 * own height from the edge it is anchored to and fades, every channel on ONE MimirMotion.OverlayMs clock with the
 * house easing. It replaces the sheets that ran two clocks on one arrival (a 220 ms slide under a 120 ms fade on
 * Compose's default curve; SceneMs under FocusMs).
 *
 * The fade is a layer the size of the sheet, so a key ring that reaches past the sheet's edge is cut mid-fade; the
 * keyboard's dense rings stay inside it (ring batch 8 owns the keys).
 */

/** The one clock every channel of a sheet rides. */
fun <T> mimirSheetSpec(reducedMotion: Boolean): FiniteAnimationSpec<T> =
    tween(durationMillis = if (reducedMotion) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)

/** A sheet arriving from its anchored edge: the bottom by default, the top with [fromTop]. */
fun mimirSheetEnter(reducedMotion: Boolean, fromTop: Boolean = false): EnterTransition =
    slideInVertically(mimirSheetSpec(reducedMotion)) { height -> if (fromTop) -height else height } +
        fadeIn(mimirSheetSpec(reducedMotion))

/** The same sheet leaving the way it came. */
fun mimirSheetExit(reducedMotion: Boolean, fromTop: Boolean = false): ExitTransition =
    slideOutVertically(mimirSheetSpec(reducedMotion)) { height -> if (fromTop) -height else height } +
        fadeOut(mimirSheetSpec(reducedMotion))

/**
 * A sheet on the sheet tier that cannot be touched while it leaves: [visible] slides it in and out on [mimirSheetEnter] and
 * [mimirSheetExit], one OverlayMs clock, and it is [content] composed for the whole exit.
 *
 * A sheet that stays composed through its exit is a tree still taking pointer input after its owner has let go of it. The
 * keyboards passed a null controller and decorator so the pad could not reach it, but a touch could: a key tapped in the
 * 180 ms it took to leave typed into a field whose keyboard was closed. From the frame [visible] turns false the content
 * therefore has its semantics cleared, and a shield is laid over it that takes every touch. The shield is a SIBLING laid
 * over the content, not a modifier on it: an ancestor's consume stops what reads a consumed pointer as not its own
 * (`clickable`) and not what does not (`awaitFirstDown(requireUnconsumed = false)`, the keyboard's Backspace and Space
 * keys); a sibling hit first stops both. A sheet asked to come back while it is leaving is a sheet again at once.
 *
 * Only the exit is shielded. The caller keeps the pad off it, as the keyboards do by dropping their focus binding in the
 * frame [visible] flips (`MimirKeyboard`'s `DisposableEffect(controller)` pops the scope then, not at the end of the
 * fade), so the leaving sheet is never a second live focus graph. The fade is a layer the size of the sheet, so a ring
 * that reaches past its edge is cut mid-fade: the keyboard's stay inside it.
 *
 * [modifier] places the sheet (its edge and its width); the sheet's own size is its content's.
 */
@Composable
fun MimirSheetHost(
    visible: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = mimirSheetEnter(reducedMotion),
        exit = mimirSheetExit(reducedMotion),
        modifier = modifier,
    ) {
        val leaving = transition.targetState != EnterExitState.Visible
        Box {
            Box(if (leaving) Modifier.clearAndSetSemantics { } else Modifier) { content() }
            if (leaving) Box(Modifier.matchParentSize().mimirPageTouchShield())
        }
    }
}
