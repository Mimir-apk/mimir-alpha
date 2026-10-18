package com.gruning.mimir.rewrite.core.model.library

/**
 * How the last per-game Enrich ended (the button treats a completed provider response differently from a failed request). A FINISHED attempt hides Enrich for the game, on the direct seat and in the Y menu, even when a source had no logo or
 * cover for it; only a FAILED one keeps it (or brings it back).
 *
 * [finished] is the whole distinction: a provider answered, or there was nothing to ask, so asking again changes nothing.
 * [FAILED] is a transport, authentication or provider error, which the player may retry.
 */
enum class EnrichAttemptOutcome(val finished: Boolean) {
    /** A cover, logo, hero, support image or verified detail was found and saved. */
    MATCHED(true),

    /** The provider was asked and had nothing acceptable. */
    NO_MATCH(true),

    /** There was nothing to ask with (no searchable title), so no provider was asked. */
    NO_TITLE(true),

    /** Offline, an error or a provider that could not answer. Not a durable verdict. */
    FAILED(false),
    ;

    companion object {
        /** The stored name back to a value; an unknown or blank name is no attempt at all. */
        fun fromName(name: String?): EnrichAttemptOutcome? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The per-game Enrich outcome the app persisted, handed to the library projection so [GameRow.enrichAttempt] carries it.
 * A `fun interface` with a no-op default for the same reason [CoverOverrideSource] is one: previews and tests build without it.
 * Keyed by game id; the answer is null for a game no Enrich has finished for.
 */
fun interface EnrichAttemptSource {
    fun outcomeFor(gameId: String): EnrichAttemptOutcome?
}
