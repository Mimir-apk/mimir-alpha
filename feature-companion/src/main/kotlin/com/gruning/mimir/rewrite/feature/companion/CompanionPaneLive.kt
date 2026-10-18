package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Whether the workspace pane this composition sits in is the one the player can see or is dragging (D24).
 *
 * The Bottom's workspace keeps each pane built once it has been built, so that a swipe between the lattice and the Activity page costs a place and a
 * draw and not a whole scene's composition. The pane that is neither shown nor being dragged is PARKED: built, measured, but not placed, drawn or
 * touched, and this reads false inside it. **A parked pane runs nothing**: no feed it collects, no ticker it owns, no timer it waits on. At rest only
 * the shown scene runs.
 *
 * True by default, so a surface that is not hosted in a workspace (Main, a preview, a test) runs as it always did.
 *
 * Reading this in composition recomposes the reader when the pane parks or un-parks. The helpers below ([collectAsStateWhileLive],
 * [LaunchedEffectWhileLive]) do NOT read it: they follow the same fact through a state they read only in an effect, so a pane's own composition is never
 * invalidated by the flip.
 */
val LocalCompanionPaneLive = compositionLocalOf { true }


/** A pane is parked when it is neither the shown one nor the one a drag is carrying. One place for the rule, so the two panes cannot disagree. */
internal fun companionPaneParked(shown: Boolean, panning: Boolean): Boolean = !(shown || panning)

/** Marks [content] as live or parked: provides both forms of the fact: [LocalCompanionPaneLive] and core-ui's `LocalMimirMotionLive`, which the keyboard caret and the ring gleam follow. The surface wraps each workspace pane in one. */
@Composable
fun CompanionPaneLiveScope(live: Boolean, content: @Composable () -> Unit) {
    val liveState = rememberUpdatedState(live)
    CompositionLocalProvider(
        LocalCompanionPaneLive provides live,
        LocalMimirMotionLive provides liveState,
    ) {
        content()
    }
}

/**
 * [collectAsState][androidx.compose.runtime.collectAsState] for a pane that can be parked: the subscription exists only while the pane is live. While
 * it is parked the upstream is not collected at all (a Room query, a midnight ticker, a store's flow), and the state keeps the LAST value it held; the pane
 * draws nothing then, and on the drag that un-parks it the flow is collected afresh and its first emission lands in the first frames of the drag.
 *
 * A value equal to the one held changes nothing (the state's own structural equality), so a re-subscription that finds the same data rebuilds nothing.
 */
@Composable
fun <T> Flow<T>.collectAsStateWhileLive(initial: T): State<T> {
    val live = LocalMimirMotionLive.current
    val upstream = this
    val state = remember(upstream) { mutableStateOf(initial) }
    LaunchedEffect(upstream, live) {
        snapshotFlow { live.value }.collectLatest { on ->
            if (on) upstream.collect { state.value = it }
        }
    }
    return state
}

/** [collectAsStateWhileLive] for a [StateFlow], which always has a value to start from. */
@Composable
fun <T> StateFlow<T>.collectAsStateWhileLive(): State<T> = collectAsStateWhileLive(value)

/**
 * A [LaunchedEffect] that runs [block] only while the pane is live. A pane that is parked has the effect cancelled (a ticker's loop, a timer's wait), a
 * key that changes while it is parked starts nothing, and the drag that un-parks it starts [block] again from the top, which is where a ticker writes
 * its position for that moment.
 */
@Composable
fun LaunchedEffectWhileLive(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val live = LocalMimirMotionLive.current
    LaunchedEffect(*keys, live) {
        snapshotFlow { live.value }.collectLatest { on ->
            if (on) coroutineScope { block() }
        }
    }
}
