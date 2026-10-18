package com.gruning.mimir.rewrite.core.audio

/**
 * The frontend music beds this module can play.
 *
 * `:core-audio` is a leaf — it does not depend on `:core-ui` and has no idea what a theme is. The
 * caller owns the theme-to-bed mapping and pushes the result in through [MimirFrontendMusic.setBed],
 * which keeps the choice a product decision made where themes are actually known, and keeps this
 * module testable without dragging the palette in behind it.
 *
 * [title] is what the player reads in Settings > Music and the song-title chip: the piece's own name,
 * not the resource's working name (`the_well_remembers` is still the file; "Fallen Hero" is the piece).
 *
 * Both beds are 48 kHz stereo Ogg Vorbis, and that is the loop-seam requirement rather than a
 * packaging preference: Vorbis returns the exact frame count, while AAC drifts even when the decoder
 * honours the gapless tag. Receipts for both live in `core-audio/PROVENANCE.md`.
 */
enum class MimirFrontendBed(internal val audioResource: Int, val title: String) {
    /**
     * The default across the shipping themes — the slow ambient piece (115.200 s, 5,529,600 frames).
     */
    Ambient(R.raw.mimir_frontend_ambient_v1, "Mimir Ambient"),

    /**
     * "Fallen Hero", arrangement v22 (57.600 s, 2,764,800 frames). Kept for Hel, whose keening cry is
     * the whole reason that arrangement exists.
     */
    FallenHero(R.raw.mimir_the_well_remembers_v22, "Fallen Hero"),
}
