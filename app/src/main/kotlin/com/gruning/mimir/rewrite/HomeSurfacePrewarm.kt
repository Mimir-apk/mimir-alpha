package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.MimirFocusTraversal
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.mimirArtWarmInMemory
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.TomeId
import kotlinx.coroutines.delay

/*
 * Motion audit batch D, the prewarm it deferred: after an L1/R1 surface change, decode the covers
 * of the destination's visible seats + 2, so the next moves along the new band land on art already in memory.
 *
 * After the arrival, not on the press: the band itself requests its four visible seats and one hidden seat each side the
 * moment it composes, and Coil 2.7 does not merge identical in-flight requests, so a press-time pass would decode those
 * covers twice mid-glide. Once the arrival has settled they are in memory and skipped, and only the outer seats decode.
 * [HomeSurfacePrewarmReach] = 5 from the focused entry covers "visible (4) + 2" whichever of the four seats it sits in.
 *
 * Budget (desk numbers): at most [HomeSurfacePrewarmCap] decodes per surface change (a Tome brings up to four previews),
 * one at a time, about 20-30 ms each on the T618 and 5-10 ms on a Snapdragon 8 Gen 2. Skipped while a traverse streams;
 * the next surface change cancels it. Every request is the cold-start warm's own ([mimirWarmCoverRequest]), the key the
 * Home tiles paint from (MediaArtFrame's MimirSwitchTileArt / MimirLedgeCoverArt, mimirCoverRequest).
 */

internal const val HomeSurfacePrewarmReach = 5
internal const val HomeSurfacePrewarmCap = 8
internal const val HomeSurfacePrewarmAfterMs = MimirPageMotion.LeadMs + MimirPageMotion.ArriveMs
private const val HomeSurfacePrewarmRecheckMs = 100L

@Composable
internal fun HomeSurfacePrewarmEffect(homeShelf: HomeShelfCoordinator, focusAuthority: FocusAuthority) {
    val context = LocalContext.current.applicationContext
    val latest by rememberUpdatedState(homeShelf)
    LaunchedEffect(homeShelf.surfaceEpoch) {
        // A press flips the surface with a direction; a restore or a return (direction 0) warms nothing.
        if (latest.surfaceHandoffDirection == 0) return@LaunchedEffect
        delay(HomeSurfacePrewarmAfterMs.toLong())
        while (MimirFocusTraversal.streaming) delay(HomeSurfacePrewarmRecheckMs)
        val shelf = latest
        val uris = homeSurfacePrewarmUris(shelf.entries, shelf.rows, shelf.tomes, focusAuthority.state.value.focused)
        val loader = context.imageLoader
        var decoded = 0
        for (uri in uris) {
            if (decoded >= HomeSurfacePrewarmCap) break
            if (mimirArtWarmInMemory(context, uri)) continue
            loader.execute(mimirWarmCoverRequest(context, uri))
            decoded++
        }
    }
}

/**
 * The covers of the entries within [reach] of the focused one, nearest first (right before left), each through the art
 * ladder the tile itself paints: a game's square tile icon, else its resolved face, else its cover; a Tome's previews.
 */
internal fun homeSurfacePrewarmUris(
    entries: List<HomeShelfEntry>,
    rows: List<HomeShelfRow>,
    tomes: Map<TomeId, HomeShelfTome>,
    focused: FocusKey?,
    reach: Int = HomeSurfacePrewarmReach,
): List<String> {
    if (entries.isEmpty()) return emptyList()
    val centre = entries.indexOfFirst { it.focusKey == focused }.takeIf { it >= 0 } ?: 0
    val rowsById = rows.associateBy { it.itemId }
    val order = buildList {
        add(centre)
        for (step in 1..reach) {
            add(centre + step)
            add(centre - step)
        }
    }.filter { it in entries.indices }
    return order.flatMap { index ->
        when (val entry = entries[index]) {
            is HomeShelfEntry.Game -> listOfNotNull(
                rowsById[entry.itemId]?.let { row -> row.tileIconUri ?: row.faceUri ?: row.artworkUri },
            )
            is HomeShelfEntry.Tome -> tomes[entry.tomeId]?.previewArtUris.orEmpty()
        }
    }.map(String::trim).filter(String::isNotEmpty).distinct()
}
