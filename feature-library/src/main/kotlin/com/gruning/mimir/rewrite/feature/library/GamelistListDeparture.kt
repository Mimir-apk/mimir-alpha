package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import kotlin.math.roundToInt

/*
 * The LIST view's departure transition. The carousel paints the game that left as a ghost card and closes the gap
 * over it; the list does the same with a ghost ROW, which is the row of the game that left, kept in the LazyColumn under its
 * own key at the place it stood. It has to be the same item, in the same place, for three reasons, and they are the whole
 * design:
 *
 *  - The list a departure is drawn over is, frame for frame, the list before the removal: the same keys in the same order.
 *    So the LazyColumn sees nothing change when the game goes, its scroll state is not re-created and its reveal pass is not
 *    told the content was replaced (both are remembered on the departure frame's lineage, not on the keys), and the only
 *    thing that moves is what the departure moves.
 *  - The row is not re-created, so its ring does not start again and its gleam does not sweep a second time while it fades.
 *  - Its dissolve is folded into the draw as a per-draw alpha ([CompositingStrategy.ModulateAlpha]) and never a layer alpha
 *    of its own: a layer below alpha 1 is composited into a buffer at its own bounds, and the ring's bloom, which paints
 *    outside them, would be cut the moment the fade began.
 *
 * The gap closes by the row's own height: [gamelistLeavingRow] reports the row's height times the departure's gap, so every
 * row after it glides up on the departure's clock, in the layout phase, recomposing nothing. The LazyColumn spaces its rows
 * by [GamelistListRowSpacing], and the leaver has a space on each side where a list without it has one, so the row beside
 * the gap ([gamelistGapNeighbour]) gives up that space over the same clock; at the last frame the list is exactly the list
 * the new games make, and dropping the ghost moves nothing.
 */

/** The space between two rows of the list: one place, read by the LazyColumn that lays them out and by the gap that closes. */
internal val GamelistListRowSpacing: Dp = MimirSpacing.sm

internal fun gamelistListRowTestTag(game: GameRow): String = "gamelist_row_${game.gameId.value}"

internal fun gamelistListLeavingRowTestTag(game: GameRow): String = "gamelist_row_leaving_${game.gameId.value}"

internal val GamelistListPresentationAlphaSemantics = SemanticsPropertyKey<Float>("GamelistListPresentationAlpha")
internal var SemanticsPropertyReceiver.gamelistListPresentationAlpha by GamelistListPresentationAlphaSemantics

/**
 * What the list draws while a departure runs: the rows the new list holds, plus the row that left at the place it stood, so
 * that the LazyColumn keeps laying out the list it had. [ghostIndex] is that place in [games], or null when nothing is leaving.
 */
internal class GamelistListShown(val games: List<GameRow>, val ghostIndex: Int?)

internal fun gamelistListShown(games: List<GameRow>, departure: GamelistDeparture?): GamelistListShown {
    // A departure whose game is somehow listed again (the star put back while it was dissolving) is not drawn twice.
    if (departure == null || games.any { it.gameId == departure.game.gameId }) return GamelistListShown(games, null)
    val at = departure.index.coerceIn(0, games.size)
    return GamelistListShown(games.toMutableList().apply { add(at, departure.game) }, at)
}

/** The row beside the gap, and which side of it: the one that was after the leaver, or (the last row left) the one before. */
internal class GamelistGapNeighbour(val gameId: GameId, val below: Boolean)

/** [games] is the NEW list, without the leaver. */
internal fun gamelistGapNeighbour(games: List<GameRow>, departure: GamelistDeparture?): GamelistGapNeighbour? = when {
    departure == null || games.isEmpty() -> null
    departure.index < games.size -> GamelistGapNeighbour(games[departure.index].gameId, below = true)
    else -> GamelistGapNeighbour(games.last().gameId, below = false)
}

/**
 * How present the leaving row is, 1 to 0. A dissolve fades on the departure's own progress; a shatter paints nothing at all,
 * because the shards are drawn from a picture of the row and a row fading under them would show through.
 */
internal fun gamelistLeavingPresence(departure: GamelistDeparture): Float = when (departure.kind) {
    GamelistDepartureKind.Shatter -> 0f
    GamelistDepartureKind.Dissolve -> (1f - departure.progress.value).coerceIn(0f, 1f)
}

/**
 * The row of the game that left. It is laid out at [GamelistDeparture.gap] of its own height, so the rows after it close up
 * over it, and it is drawn at [gamelistLeavingPresence], in place, at its full size over the space it is giving up. Both are
 * read in the layout and draw phases only.
 */
internal fun Modifier.gamelistLeavingRow(departure: GamelistDeparture): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * departure.gap().coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
    .graphicsLayer {
        alpha = gamelistLeavingPresence(departure)
        // Per-draw alpha, not a buffer at the row's bounds: the ring's bloom paints outside them.
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    .testTag(gamelistListLeavingRowTestTag(departure.game))
    .semantics { gamelistListPresentationAlpha = gamelistLeavingPresence(departure) }

/**
 * The row beside the gap. The LazyColumn puts [GamelistListRowSpacing] on each side of the leaver where the new list has one,
 * so this row takes that space back over the departure's clock: it is laid out that much shorter, and (when it is the row
 * after the gap, [below]) drawn that much higher, so that every row after it moves with it and, at the last frame, the ghost
 * is a row of no height between two rows that sit exactly where the new list puts them.
 */
internal fun Modifier.gamelistGapNeighbour(departure: GamelistDeparture, below: Boolean): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val closed = (1f - departure.gap()).coerceIn(0f, 1f)
        val shrink = (GamelistListRowSpacing.roundToPx() * closed).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, placeable.height - shrink) { placeable.place(0, if (below) -shrink else 0) }
    }
