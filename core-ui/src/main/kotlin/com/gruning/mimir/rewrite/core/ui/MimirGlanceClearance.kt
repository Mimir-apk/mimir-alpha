package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * The glance row at the top end of Home and the Library (the song-title chip beside the status pill) is drawn over the screen, so a control that stands in its band would be
 * covered by it: the gamelist's Search pill was (the chip's words covered "SEARCH GAMES"). A control that can stand under that row says so with
 * [mimirGlanceKeepClear]; the row reads [clearRightPx] and gives its chip only the room to the right of it.
 *
 * Window pixels throughout, so a control and the row agree wherever each one's own parent sits. A snapshot-state map, so the row re-measures (and does not recompose) when a control
 * moves, appears or leaves.
 */
@Stable
class MimirGlanceClearance {
    private val controls = mutableStateMapOf<Any, Rect>()

    fun report(key: Any, bounds: Rect) {
        if (controls[key] != bounds) controls[key] = bounds
    }

    fun forget(key: Any) {
        controls.remove(key)
    }

    /**
     * The window x the glance row's chip must stay right of: the furthest right edge of the reported controls whose bounds share a band with [top]..[bottom] (the row's own),
     * or 0 when nothing stands there. A control above or below the band is no obstacle.
     */
    fun clearRightPx(top: Float, bottom: Float): Float =
        controls.values.filter { it.bottom > top && it.top < bottom }.maxOfOrNull { it.right } ?: 0f
}

/** The clearance the glance row reads; null (nothing to keep clear of) where no host provides one, as on the second screen. */
val LocalMimirGlanceClearance = staticCompositionLocalOf<MimirGlanceClearance?> { null }

/**
 * Registers this element's window bounds as a control the glance row must keep its chip off ([MimirGlanceClearance]). Put it on the element that is the control (its touch area),
 * so the row clears what the player can press. Leaves the registry when the element does.
 */
@Composable
fun Modifier.mimirGlanceKeepClear(key: Any): Modifier {
    val clearance = LocalMimirGlanceClearance.current ?: return this
    DisposableEffect(clearance, key) { onDispose { clearance.forget(key) } }
    return this.onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        clearance.report(
            key,
            Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height),
        )
    }
}
