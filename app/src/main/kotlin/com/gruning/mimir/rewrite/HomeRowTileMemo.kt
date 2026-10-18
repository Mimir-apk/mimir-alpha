package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId

/**
 * What decides one Home row's tile resolution, and nothing that merely positions the row.
 *
 * The resolution reads files and prefs for the row's identity ([itemId]), whether its own art is a real cover and which art that
 * is ([artworkUri]). Everything else on a [com.gruning.mimir.rewrite.feature.library.HomeShelfRow] (its `rank`, which is a
 * position in the current order, its title, its elapsed time) is copied through unchanged afterwards and so is not an input. That
 * is the point of this key: a game's return reorders the shelf, so every row's `rank` moves, and keying on the whole row would
 * have missed the memo for every row that shifted.
 */
internal data class HomeRowTileInputs(
    val itemId: HomeShelfItemId,
    val hasAutomaticCover: Boolean,
    val artworkUri: String?,
)

/**
 * A per-row memo for the Home shelf's file-state resolution (RC-48, audit 2026-10-14).
 *
 * `tiledRows` did about 10-15 stats and prefs reads per Home row on the composition thread whenever the rows changed: Home open,
 * every return from a game (a reorder, so a new list) and every art epoch. This remembers each row's resolved tile against its
 * [HomeRowTileInputs] and the epoch it was resolved under, so a reorder resolves only the rows whose inputs changed.
 *
 * **The epoch is the invalidation, and it is the one the coordinator already has.** A tile write (the SteamGridDB fetch) and
 * every Customize Media apply bump `sgdbEpoch` (`refreshCustomEnrichOwner` -> `refreshTiles`), and the file state a row was
 * resolved from changes only through one of them or through the row's own art. A new epoch drops everything, so a fresh pick is
 * never cached away.
 *
 * **Correct on the first frame, never stale.** There is no async hand-off: a miss resolves right here, on the caller's thread,
 * exactly as the block it replaces did; the memo only skips what it has already resolved under the same inputs and epoch. A row
 * that leaves the list is dropped, so the memo is never larger than the shelf.
 *
 * Not thread-safe by design: it is read and written inside one composition's `remember`.
 */
internal class HomeRowTileMemo<V : Any> {
    private var epoch = Int.MIN_VALUE
    private var resolved = HashMap<HomeRowTileInputs, V>()

    /** One resolved value per input, in the order given. [compute] runs only for an input not already resolved under [epoch]. */
    fun resolve(epoch: Int, inputs: List<HomeRowTileInputs>, compute: (HomeRowTileInputs) -> V): List<V> {
        if (epoch != this.epoch) {
            this.epoch = epoch
            resolved = HashMap()
        }
        val next = HashMap<HomeRowTileInputs, V>(inputs.size * 2)
        val out = inputs.map { input -> (next[input] ?: resolved[input] ?: compute(input)).also { next[input] = it } }
        resolved = next
        return out
    }
}
