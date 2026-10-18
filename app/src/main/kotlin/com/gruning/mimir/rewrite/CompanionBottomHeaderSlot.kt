package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Bottom's page-header slot for the recent-app tile: ONE reserved room at the trailing end of a page's top row, so every page lays out around the tile
 * instead of being drawn under it (Thor drive 2026-10-08: the Chrome tile covered the elapsed clock on Now Playing and the "Recent activity" sort chip on
 * Achievements, shadowing its tap target).
 *
 * The tile is not part of any page. The Presentation lays it over the Activity page's top end ([RecentAppTileTopDp] from the top, [RecentAppTileEndDp] from the
 * end, 48 dp square) once the user has leased an app to the Bottom, so a page that wants to be clear of it has to know it is there. The workspace surface is the
 * one place that knows ([CompanionWorkspaceSurface] provides [LocalBottomHeaderTrailingReserve] over every scene it hosts), and a page's top row asks for the room
 * with [bottomHeaderTrailingReserve]. With no app leased the reserve is zero and the modifier adds nothing, so a page's layout is exactly what it was.
 *
 * Pages that wear it: the Now Playing header (its clock and total), the Achievements hub's top row (Back, search and sort), and the Activity page's header stat,
 * which takes the same value through its `trailingReserve` parameter. The music player's top band reserves the same room unconditionally (its heading is centred
 * between two equal ends), so it needs no help from here.
 */
internal val LocalBottomHeaderTrailingReserve = compositionLocalOf { 0.dp }

/** The tile's distance from the top of the page, so the reserve and the tile cannot drift apart. */
internal const val RecentAppTileTopDp = 8f

/** The tile's distance from the end of the page. */
internal const val RecentAppTileEndDp = 24f

/** The trailing room a page's top row leaves: [ActivityRecentAppReserveDp] (past the page's own inset) while a recent app is shown, nothing otherwise. */
internal fun bottomHeaderTrailingReserveFor(recentAppShown: Boolean): Dp =
    if (recentAppShown) ActivityRecentAppReserveDp.dp else 0.dp

/** A page's top row stops short of the recent-app tile: its end padding grows by the slot's reserve, which is zero when no app is leased. */
@Composable
internal fun Modifier.bottomHeaderTrailingReserve(): Modifier = padding(end = LocalBottomHeaderTrailingReserve.current)

/**
 * The Bottom layers that close on a tap outside them (the achievements console picker) and sit under the recent-app tile's overlay: while one is open the tile is not
 * drawn, so a tap on the page's top-right corner closes the layer instead of opening the leased app. The tile is drawn by the Presentation (a sibling of the page that
 * owns the layer), so a layer reports in here and the Presentation reads [covered]. The open launch card is the Presentation's own state and is read there directly.
 */
internal object BottomRecentAppTileCover {
    private var count by mutableIntStateOf(0)

    /** True while at least one layer that closes on an outside tap is open. */
    val covered: Boolean get() = count > 0

    /** One layer opens; the returned release closes it, and a second call to it does nothing. */
    fun cover(): () -> Unit {
        count += 1
        var released = false
        return {
            if (!released) {
                released = true
                count -= 1
            }
        }
    }
}

/** Reports a layer that closes on an outside tap as open while [covered] is true and this composition is live; it lets go when it closes or leaves. */
@Composable
internal fun BottomRecentAppTileCoverEffect(covered: Boolean) {
    DisposableEffect(covered) {
        if (covered) {
            val release = BottomRecentAppTileCover.cover()
            onDispose(release)
        } else {
            onDispose {}
        }
    }
}
