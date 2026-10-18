package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Whether the part of the screen this composition sits in is one the player can see (D24). The Bottom's workspace PARKS the pane that is neither shown nor being
 * dragged: it stays built but is not placed, drawn or touched, and this reads false inside it. Anything that would otherwise keep a clock running for a pane
 * nobody can see (the keyboard's endless caret blink, a ring's gleam already under way) follows it and stands down.
 *
 * A [State], read where the clock waits (an effect, a snapshot flow) rather than in composition, so a pane parking or coming back recomposes nothing.
 * Static: the state object is one pane's own for the pane's life. True by default, so every other host (Main, overlays, previews, tests) is unchanged.
 */
val LocalMimirMotionLive = staticCompositionLocalOf<State<Boolean>> { MimirMotionAlwaysLive }

/** The default: always live, and recognisable by identity, so a clock can skip watching it. */
object MimirMotionAlwaysLive : State<Boolean> {
    override val value: Boolean = true
}

/**
 * Runs [block] while [live] reads true. When it turns false mid-run, [block] is cancelled and [onParked] runs (a clock snaps to its end); when it is false at the
 * start, [block] does not run at all and [onParked] does. A [live] that is the always-live default adds no watcher: [block] simply runs, as it always did.
 */
internal suspend fun runWhileMotionLive(live: State<Boolean>, onParked: suspend () -> Unit, block: suspend () -> Unit) {
    if (live === MimirMotionAlwaysLive) {
        block()
        return
    }
    if (!live.value) {
        onParked()
        return
    }
    coroutineScope {
        val run = launch { block() }
        val watch = launch {
            snapshotFlow { live.value }.first { !it }
            run.cancel()
        }
        run.join()
        watch.cancel()
    }
    if (!live.value) onParked()
}
