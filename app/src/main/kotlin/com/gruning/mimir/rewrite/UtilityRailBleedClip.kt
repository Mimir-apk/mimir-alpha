package com.gruning.mimir.rewrite

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Keeps the rail's scrolled rows from drawing under the panel's header.
 *
 * [utilityRailBleedUp] extends the scroll viewport [UtilityRailRingBleed] (8 dp) above the divider so the first row's focus ring is not clipped. The header's own bottom
 * padding is only 4 dp, so that band reaches into the title's line box: a row scrolled through it drew under "ᛗ · UTILITY" (Thor drive 2026-10-08, D44).
 *
 * The ring needs the band only at the top of the rail: the first row is the one rail control whose reveal is a scroll to zero, and a middle row settles at the viewport's centre.
 * So once the rail has scrolled, the viewport is clipped to its visible bounds (the divider down); at zero it is not, and the ring still reaches over the divider.
 *
 * Place it BEFORE (outside) [utilityRailBleedUp] in the chain: it clips to the bounds that modifier reports, and its overflow above them is what gets cut. The clip is a
 * layer property read at draw time through a derived flag, so a scroll that stays on one side of zero re-composes and re-draws nothing extra.
 */
@Composable
internal fun Modifier.utilityRailClipBleedWhenScrolled(scroll: ScrollState): Modifier {
    val scrolled = remember(scroll) { derivedStateOf { scroll.value > 0 } }
    return graphicsLayer { clip = scrolled.value }
}
