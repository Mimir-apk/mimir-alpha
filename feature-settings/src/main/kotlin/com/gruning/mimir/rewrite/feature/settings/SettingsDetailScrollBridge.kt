package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The EDGE-DWELL bridge prevents navigation from advancing to the next menu while footer content
 * is still clipped. It connects the pure [dispatchToSettings] input contract to the
 * live detail pane's scroll.
 *
 * A DOWN dead-end at the last focusable row no longer chains straight to the next rail section: if the
 * pane can still scroll (non-focusable footer content — card padding, trailing copy — is clipped), the
 * first press REVEALS the pane bottom and consumes the input; only a press with the pane already fully
 * revealed advances the rail (SC02 chain). UP mirrors to the pane top before returning to the rail.
 *
 * One registered pane at a time (Settings shows exactly one detail pane; `remember(category)` swaps the
 * ScrollState on section change and re-attaches). Module-internal, main-thread only.
 */
internal object SettingsDetailScrollBridge {
    private var scroll: ScrollState? = null
    private var scope: CoroutineScope? = null
    private var revealJob: Job? = null
    private var revealTarget: Int? = null

    fun attach(scrollState: ScrollState, coroutineScope: CoroutineScope) {
        if (scroll !== scrollState) {
            revealJob?.cancel()
            revealJob = null
            revealTarget = null
        }
        scroll = scrollState
        scope = coroutineScope
    }

    fun detach(scrollState: ScrollState) {
        if (scroll === scrollState) {
            cancelReveal()
            scroll = null
            scope = null
        }
    }

    /**
     * A successful focus move owns the next visual settlement. Stop an older whole-pane edge reveal so
     * it cannot keep dragging the viewport away from the row FocusAuthority just selected.
     */
    fun cancelReveal() {
        revealJob?.cancel()
        revealJob = null
        revealTarget = null
    }

    /**
     * If the pane can still travel toward [down]'s edge, animate fully there and return true (input
     * consumed as a REVEAL); false when already at that edge (caller proceeds to the rail chain).
     */
    fun revealEdge(down: Boolean): Boolean {
        val s = scroll ?: return false
        val target = if (down) s.maxValue else 0
        if (s.value == target || s.maxValue == 0) return false
        val cs = scope ?: return false
        if (
            settingsDetailRevealShouldLaunch(
                activeTarget = revealTarget,
                active = revealJob?.isActive == true,
                requestedTarget = target,
            )
        ) {
            revealJob?.cancel()
            revealTarget = target
            // A whole-pane reveal on the overlay clock and the house ease, not the default spring (policy 10-14, Q6).
            revealJob = cs.launch { s.animateScrollTo(target, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)) }
        }
        // BM03: a reveal scrolls real content without moving focus — record it so the router's
        // tick gate hears it. The at-edge early returns above stay silent (true dead-end).
        MimirNavFeedback.consumed()
        return true
    }
}

internal fun settingsDetailRevealShouldLaunch(
    activeTarget: Int?,
    active: Boolean,
    requestedTarget: Int,
): Boolean = !active || activeTarget != requestedTarget
