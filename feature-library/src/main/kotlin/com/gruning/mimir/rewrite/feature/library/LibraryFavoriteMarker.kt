package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gruning.mimir.rewrite.core.ui.MimirMannazPin
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinHeightDp
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinHiddenScale
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinWidthDp

// Aliases, not copies. The numbers live once, in core-ui; these exist only so the Library's own
// contract tests keep naming the marker in the Library's vocabulary. Re-typing a literal here is how
// two "identical" envelopes drift apart one release later.
internal const val LibraryFavoriteMarkerWidthDp = MimirMannazPinWidthDp
internal const val LibraryFavoriteMarkerHeightDp = MimirMannazPinHeightDp
internal const val LibraryFavoriteMarkerHiddenScale = MimirMannazPinHiddenScale

/**
 * Library-only favorite identity: the shared [MimirMannazPin] under the Library's own name.
 *
 * The geometry, the leather plate, the hardware rim, the branded-gold rune, and the draw-only
 * presence envelope all moved to `core-ui` on 2026-08-14 (UI-06) so the Tome collection could reuse
 * the shared Library gamelist Mannaz pin with
 * no second copy of the drawing. **Nothing about what the gamelist renders at rest changed:** this wrapper
 * overrides neither the height nor the presence clock, so every default (32x24dp, 0.88 hidden scale,
 * `MimirMotion.FocusMs`) is the value that was authored here.
 *
 * What it does pass is `sparkleOnMark`: this is a GAME's mark, so starring a game plays a focused game's
 * gleam on the rune. A starred game scrolling into view does not sparkle; the pin only answers a mark
 * it saw happen.
 *
 * Keep it a wrapper. If a future change needs to re-inline the drawing to tweak the gamelist alone,
 * that is the moment the two surfaces silently diverge -- add a parameter to [MimirMannazPin] instead.
 * `LibraryFavoriteMarkerFidelityTest` fails loudly if the geometry comes back here.
 */
@Composable
internal fun LibraryFavoriteMarker(
    favorite: Boolean,
    modifier: Modifier = Modifier,
) {
    MimirMannazPin(present = favorite, modifier = modifier, sparkleOnMark = true)
}
