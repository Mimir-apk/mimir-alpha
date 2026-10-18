package com.gruning.mimir.rewrite

import android.os.Handler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.gruning.mimir.rewrite.core.ui.MimirGlassScrim
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.mimirPageTouchShield
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassHold
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import kotlinx.coroutines.flow.first

/**
 * How long after the exit starts a native panel window is taken down whether or not Compose has reported its fade finished: a leak
 * guard, not a clock (a display that is off draws no frame, so the report never comes). Ten overlay clocks cover an animator
 * scale of 10, the slowest the slow-motion checks use.
 */
internal const val NativePanelExitBackstopMs: Long = MimirMotion.OverlayMs * 10L

/**
 * The exit of a panel that lives in a native overlay window of its own ([PrimaryMButtonPanelHost]), which used to leave the way
 * a window does: removed on the frame it was told to go (audit 2026-10-14, RC-38). It is now one sequence:
 *
 *  1. [begin]: [exiting] turns true. [NativePanelExitFade] reads it and fades the whole window body on
 *     [MimirMotion.OverlayMs], and the host stops answering input (the key and motion listeners consume, a touch shield covers
 *     the body): the panel is dead to input for the whole leave, and the window stays up, so nothing it had focused reaches
 *     the app underneath mid-press.
 *  2. The fade ends and Compose reports it ([onFullyHidden], posted: a composition is never disposed from inside its own frame),
 *     or the [backstopMs] guard fires: [finish] takes the window down. It runs once, whichever comes first.
 *
 * [cancel] is for a panel that is disposed by another road (the lease was revoked, or the next panel replaced it): its window
 * is already gone, so neither trigger may run [finish] afterwards.
 */
internal class NativePanelExit(
    private val handler: Handler,
    private val backstopMs: Long = NativePanelExitBackstopMs,
    private val finish: () -> Unit,
) {
    /** Compose state: the fade host reads it. False until [begin]. */
    var exiting by mutableStateOf(false)
        private set

    private var done = false
    private val backstop = Runnable { complete() }

    /** Starts the exit. False when it had already started (or ended), so a second [begin] changes nothing. */
    fun begin(): Boolean {
        if (exiting || done) return false
        exiting = true
        handler.postDelayed(backstop, backstopMs)
        return true
    }

    /** Compose reports the fade finished. Ignored unless an exit is under way. */
    fun onFullyHidden() {
        if (!exiting || done) return
        handler.post(::complete)
    }

    /** Nothing is left to finish: the window went by another road. */
    fun cancel() {
        done = true
        handler.removeCallbacks(backstop)
    }

    private fun complete() {
        if (done) return
        done = true
        handler.removeCallbacks(backstop)
        finish()
    }
}

/**
 * What a native panel window draws: [content] at full strength, then, when [exiting] turns true, the same content fading out
 * on [mimirOverlayLeaveSpec] (OverlayMs, the house easing) with every touch shielded. [onFullyHidden] is called once the leave
 * has settled. There is no enter here: the content's own glass and panel ramps are its arrival, and the window is up from the
 * frame it is added.
 *
 * The content stays composed for the whole fade, so a face that would leave composition when its model closes (the Utility
 * panel's `AppBrightnessCoordinatorHost`) must be told to retain itself (`retainedForExit`), or there is nothing left to fade.
 */
@Composable
internal fun NativePanelExitFade(
    exiting: Boolean,
    onFullyHidden: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(true) }
    visibleState.targetState = !exiting
    LaunchedEffect(visibleState.targetState, visibleState.isIdle) {
        if (!visibleState.targetState && visibleState.isIdle) onFullyHidden()
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = EnterTransition.None,
            exit = fadeOut(mimirOverlayLeaveSpec()),
        ) {
            content()
        }
        // A SIBLING laid over the content, not a modifier on it: a shield that must stop what reads a consumed pointer as not its
        // own (`awaitFirstDown(requireUnconsumed = false)`) is hit first (`MimirSheetHost`'s shape).
        if (exiting) Box(Modifier.fillMaxSize().mimirPageTouchShield())
    }
}

/**
 * A native panel window whose glass leaves the way the house's modals do, "dissolve, then clear" : the card
 * ([content]) fades with the body on [MimirMotion.OverlayMs] ([NativePanelExitFade]), while the glass behind it, the scrim AND the
 * window's blur (Q14), is held at full strength for [MimirMotion.OverlayDissolveCoverMs] after the exit begins
 * ([rememberMimirGlassHold]) and only then clears on [MimirMotion.OverlayMs]. A half-faded card never sits over a sharpening game.
 *
 * The glass is one value, [rememberMimirGlassProgress], read by the scrim in draw and handed to [applyBlur] every frame it changes
 * (a window's blur is the compositor's, not Compose's, so it does not follow the body's alpha by itself). It rises on the open and
 * clears on the close, and [onFullyHidden] is called only when BOTH the body has finished fading AND the glass has reached 0: the window
 * must never be detached under a blur that is still clearing (the previously observed cut). The host's leak guard stands behind that.
 * What [applyBlur] does with the fraction, and whether the platform can blur across windows at all, is the controller's
 * (`CompanionOverlayController.setPrimaryPanelBlurBehind`); where it cannot, the scrim alone is the glass, as before.
 */
@Composable
internal fun NativePanelGlassExitFade(
    exiting: Boolean,
    onFullyHidden: () -> Unit,
    applyBlur: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val held = rememberMimirGlassHold(requested = !exiting)
    val glass = rememberMimirGlassProgress(visible = held)
    var bodyHidden by remember { mutableStateOf(false) }
    val latestHeld by rememberUpdatedState(held)
    val latestApply by rememberUpdatedState(applyBlur)
    val latestHidden by rememberUpdatedState(onFullyHidden)
    LaunchedEffect(Unit) {
        snapshotFlow { glass() }.collect { latestApply(it) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { bodyHidden && !latestHeld && glass() <= 0f }.first { it }
        latestHidden()
    }
    Box(Modifier.fillMaxSize()) {
        MimirGlassScrim(progress = glass)
        NativePanelExitFade(exiting = exiting, onFullyHidden = { bodyHidden = true }, content = content)
    }
}
