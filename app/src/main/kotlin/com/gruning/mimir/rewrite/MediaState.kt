package com.gruning.mimir.rewrite

/**
 * WHERE a game is being looked at.
 *
 * Customization did not clearly distinguish the surface being edited. This axis makes that explicit. Until now the product had a MEDIA axis ([EnrichMediaKind]) and one
 * global switch, so the same game could wear three different faces depending on which screen resolved it,
 * with nothing telling a player why.
 *
 * The axis multiplies exactly one thing: the **face** a game wears — the cover-or-cartridge picture. The
 * tile icon, Hero art and Hero Motion each have one rendered slot and therefore no state.
 *
 * By default a game wears the SAME face in both states: an unwired Launch card shows whatever the Shelf
 * resolved to. This keeps the default library ready to play without requiring per-surface customization.
 */
internal enum class MediaState {
    /** Home, Library, and inside a Tome. The anchor: what he sees without pressing anything. */
    SHELF,

    /** The big card that opens when he picks a game. Inherits the Shelf until it is wired. */
    LAUNCH_CARD,
}

/**
 * The storage key for one game's media in one state (design ruling C5-K).
 *
 * ```
 * mediaStorageKey(cacheKey, SHELF)       == cacheKey                 // byte-identical to today
 * mediaStorageKey(cacheKey, LAUNCH_CARD) == "launch-card#" + cacheKey
 * ```
 *
 * **This is a ZERO-WRITE migration, and that is the whole point of the shape.** `SHELF` returns the bare
 * key, so every file already on the reference device resolves at its existing path, under its existing name, in its
 * existing directory. Nothing is copied, moved, rewritten or rekeyed. The `LAUNCH_CARD` branch mints a new
 * key for a state that has no files yet.
 *
 * **It does not relax R3-M.** That ruling froze the *derivations* — same cacheKey in, same filename out —
 * and that still holds exactly. What changes is that an additional key exists. No existing file changes
 * address. Anyone reading this as permission to touch a directory name, a wire name or the SHA-256
 * derivation has read it backwards: those remain frozen, and renaming one orphans every image he has ever
 * picked.
 *
 * **Prefix, not suffix, deliberately.** A suffix collides if a real cacheKey ever ENDS in the marker; a
 * prefix collides only if one BEGINS with `launch-card#`. Real cacheKeys are a game id, or `android/` plus
 * a package name — neither can begin with that literal. Pinned rather than argued: see
 * `MediaStorageKeyTest`.
 */
internal fun mediaStorageKey(cacheKey: String, state: MediaState): String = when (state) {
    MediaState.SHELF -> cacheKey
    MediaState.LAUNCH_CARD -> LaunchCardKeyPrefix + cacheKey
}

/**
 * The marker that separates a launch-card key from a shelf key.
 *
 * `#` is not decorative: it cannot appear in an Android package name, and a game id is a UUID-shaped
 * string, so the prefix is unforgeable from either real cacheKey shape.
 */
internal const val LaunchCardKeyPrefix = "launch-card#"
