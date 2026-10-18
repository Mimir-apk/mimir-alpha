package com.gruning.mimir.rewrite.core.model.library

/**
 * The offline genre lookup: a game TITLE → its genre string, or null when the bundled map has no entry.
 *
 * Deliberately a `fun interface` (SAM) so a no-op `GenreSource { null }` is the trivial default — the
 * repository takes the real bundled implementation in production and the no-op in previews/tests. Genre is
 * a quiet display tag, never load-bearing, so a miss returns null gracefully.
 * LOCAL-ONLY: the implementation reads a static bundled map (no network).
 */
fun interface GenreSource {
    /** The genre for [title] (implementation normalizes the title to the map's key), or null if unknown. */
    fun genreFor(title: String): String?
}
