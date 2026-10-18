package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.lang.ref.WeakReference

/**
 * A gamelist's focus keys, built ONCE per list and asked in constant time (audit 2026-10-14, RC-45: "the Library does O(n) work per focus
 * step over the full game list").
 *
 * A key is a string built from the game's id, so every `games.map(::gameKey)`, `games.firstOrNull { gameKey(it) == key }` and
 * `keys.indexOf(key)` the screen ran per focus step cost a string and a list slot per game (about 30,000 allocations a step at 5,500
 * games) and a full walk besides. A focus step changes nothing about the list, so none of that work had a reason to run again. This is the
 * one place the keys of a list are made; everything that asks "which game is this key", "where does it stand" or "is it in the list"
 * asks it here, and a step allocates only the lookup key it asks with.
 *
 * It is a value of the list it was built from: the screen remembers it on the list (`remember(games)`), and the dispatch, which has no
 * composition, reaches the same instance through [of], which hands back the index it last built for that very list.
 *
 * [keys] has one slot per game in list order, exactly `games.map(::gameKey)`. [keySet] is the same keys as a set that keeps the list's order, so
 * `first()` is the first game and `in` is a hash lookup: the pure landing functions take a `Collection<FocusKey>` and read both. A key that
 * stood twice (it cannot: a game id is unique in its list) would answer with its first seat.
 */
@Stable
internal class GamelistKeyIndex private constructor(val games: List<GameRow>) {
    val keys: List<FocusKey> = games.map(::gameKey)

    private val positions: LinkedHashMap<FocusKey, Int> = LinkedHashMap<FocusKey, Int>(positionsCapacity(keys.size)).also { map ->
        for (index in keys.indices) map.putIfAbsent(keys[index], index)
    }

    /** The keys in list order, for the functions that ask `first()`, `isEmpty()` and `in`. */
    val keySet: Set<FocusKey> get() = positions.keys

    /** Where [key] stands in the list, or -1 when it names no game of it. */
    fun indexOf(key: FocusKey): Int = positions[key] ?: -1

    /** Where the game with [id] stands in the list, or -1. */
    fun indexOf(id: GameId): Int = indexOf(gameKey(id))

    /** The game [key] names, or null when it names none of this list's. */
    fun gameFor(key: FocusKey): GameRow? = positions[key]?.let(games::get)

    operator fun contains(key: FocusKey): Boolean = positions.containsKey(key)

    companion object {
        private val recent = arrayOfNulls<WeakReference<GamelistKeyIndex>>(RecentCapacity)

        /**
         * The index of [games], reusing the one built for this very list (the same instance, not an equal one) while anything still holds it.
         * Two are kept: the list the screen shows and the list the view model holds are usually the same instance and sometimes two.
         */
        @Synchronized
        fun of(games: List<GameRow>): GamelistKeyIndex {
            for (slot in recent) {
                val held = slot?.get()
                if (held != null && held.games === games) return held
            }
            val built = GamelistKeyIndex(games)
            for (i in RecentCapacity - 1 downTo 1) recent[i] = recent[i - 1]
            recent[0] = WeakReference(built)
            return built
        }

        private const val RecentCapacity = 2

        private fun positionsCapacity(size: Int): Int = (size * 4 / 3) + 1
    }
}

/** The index of [games], remembered on the list: built when the list changes, never on a focus step. */
@Composable
internal fun rememberGamelistKeyIndex(games: List<GameRow>): GamelistKeyIndex = remember(games) { GamelistKeyIndex.of(games) }
