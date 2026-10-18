package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The faces the launch card last wore, by game: a small recency-bounded map the screen holds for as long as it lives (review W2A #7). A card that opens again for a game it
 * showed a moment ago (a reopen inside the close, or a reduced-motion snap that has no lead to hide the resolve behind) starts from the face it had, so the face does not pop in.
 * Written only with an answer the resolver gave; read once, to seed a card.
 */
internal class LibraryLaunchCardFaceMemory(private val capacity: Int = 16) {
    private val faces = object : LinkedHashMap<GameId, MimirShelfFace?>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GameId, MimirShelfFace?>): Boolean = size > capacity
    }

    fun recall(gameId: GameId): MimirShelfFace? = faces[gameId]

    fun remember(gameId: GameId, face: MimirShelfFace?) {
        faces[gameId] = face
    }
}

/**
 * The face the Library's launch card wears, resolved OFF the main thread (audit 2026-10-14, RC-16, L-20: "the launch screen's first frame is
 * 49.9 ms").
 *
 * [resolve] is the host's `launchCardWiredFace`: the cover, cartridge and wired-face stores, about 8 to 15 file stats, and it used to run inside a
 * `remember` on the card's FIRST frame, on the composition thread, and again for every media apply. Here it runs on [Dispatchers.IO] when the card
 * opens (or its game, its target or the resolver changes), so the first frame composes without it. The card is still behind its 130 ms lead (or,
 * for a launch return, under the return cover) when the answer lands, so the face arrives before the card is seen.
 *
 * **The last face is kept**: a new answer for the same game (the host re-keys [resolve] on every media apply, which is how a changed face reaches an
 * open card) replaces the face when it lands, and the card shows the face it had until then, never a blank one. A different game starts from none, so
 * one game's face is never worn by another. **And it is remembered across openings** ([memory]): the card starts from the face this game wore last, so a
 * reopen has no blank frame to pop the face in from, and the answer that lands replaces it only if it differs.
 */
@Composable
internal fun rememberLibraryLaunchCardFace(
    game: GameRow,
    target: Any,
    resolve: (GameRow) -> MimirShelfFace?,
    memory: LibraryLaunchCardFaceMemory = remember { LibraryLaunchCardFaceMemory() },
): State<MimirShelfFace?> {
    val face = remember(game.gameId) { mutableStateOf(memory.recall(game.gameId)) }
    LaunchedEffect(target, resolve) {
        // A resolver that FAILS leaves the face as it was; one that answers "none" clears it (a pick that was reset).
        val answer = withContext(Dispatchers.IO) { runCatching { resolve(game) } }
        if (answer.isSuccess) {
            val resolved = answer.getOrNull()
            memory.remember(game.gameId, resolved)
            if (face.value != resolved) face.value = resolved
        }
    }
    return face
}
