package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/*
 * Two parts of the glass menu panel, moved out of the app's HomeTileMenuOverlay unchanged so a menu in another module (the Bottom's)
 * rows and separates the same way the tile menu does.
 */

/** A hairline beam between a glass menu's rows (no centre node). */
@Composable
fun PlainMenuSeparator() {
    BeamSeparator(modifier = Modifier.height(1.dp), showCenterNode = false)
}

/**
 * The menu's focus (): the one selection ring on a capsule around the focused action's own label. Its distance
 * from the beams above and below is the row's remaining height plus the panel's row spacing -- at least 12 dp from a
 * 48 dp row, and the ring's inner and middle strokes reach 8.5 dp -- so it clears them by construction, and it never
 * meets the panel's clip. The wash lights the capsule, not the whole row. Keyed on the label, the row's content.
 */
@Composable
fun GlassMenuCapsule(
    focused: Boolean,
    identity: Any,
    /** The ring's flare on arrival: true only on an action group of four or fewer pills (the ring's own rule). Main's menus leave it off. */
    flare: Boolean = false,
    /** The host's arrival: the gleam and the flare wait until it says the surface has settled. A host with no arrival keeps the default. */
    armed: () -> Boolean = MimirFocusAlwaysArmed,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .mimirSelectionRing(focused = focused, identity = identity, shape = MimirRingShape.Pill, flare = flare, armed = armed)
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
