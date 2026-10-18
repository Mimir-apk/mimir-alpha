package com.gruning.mimir.rewrite.core.audio

/**
 * When the frontend music announces a track (the song-title chip), as a pure rule so it can be asserted on the JVM.
 *
 * **The hazard it exists for.** The gapless loop hands over to a prepared deck on EVERY pass, so anything that fires when a
 * deck starts, or when `nowPlaying` is assigned, would announce every seam. A track is announced when it ENTERS, not when a
 * deck sounds: [entryBegan] marks that an entry from silence, a bed change or a deliberate skip has started, and
 * [levelApplied] answers true exactly once for that entry, when its fade first reaches the audible floor. The seam never
 * touches this, so a loop pass is silent by construction.
 *
 * Worker-thread only: the engine's audio worker is the single caller.
 */
internal class FrontendMusicTrackAnnouncer {
    private var pending = false

    /**
     * A track began entering. [announce] is false for the recovery re-entry after a seam whose prepared deck would not start
     * (the same track carrying on, merely restarted), which must stay silent like the seam it replaces.
     */
    fun entryBegan(announce: Boolean) {
        pending = announce
    }

    /** True exactly once per announced entry: when [level] first reaches [floor]. */
    fun levelApplied(level: Float, floor: Float): Boolean {
        if (!pending || level < floor) return false
        pending = false
        return true
    }
}

/**
 * What the Now playing row's A press does to the frontend music.
 *
 * There is one bed per theme, so there is no next track: a skip RESTARTS the bed (a short fade-out, then the ordinary
 * three-second entrance). That only makes sense while something is audible, so a skip with the player's music off, the app in
 * the background, or another app's media holding the floor does nothing at all rather than start music nobody asked for.
 */
internal object FrontendMusicSkip {
    /** Whether a skip has anything to restart: the player is sounding and the policy wants it audible. */
    fun canSkip(playing: Boolean, targetVolume: Float): Boolean = playing && targetVolume > 0f
}
