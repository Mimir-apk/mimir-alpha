package com.gruning.mimir.rewrite.core.audio

/**
 * Which track's source format is the one on air. The audio renderer reads the next track's format ahead of the transition (gapless
 * playback decodes the next file while the last of the current one is still sounding), so its "input format changed" event arrives
 * for the track that is coming, not the one playing. A format for the current track is [current] at once; a format for any other
 * track waits and becomes [current] only when the player says that track is now the one playing. Pure: nothing here touches
 * Media3, so the rule is JVM-testable.
 *
 * Main thread only, like the tracker that owns it.
 */
internal class FormatTransitionGate<T : Any> {
    private var playingId: String? = null
    private var waitingId: String? = null
    private var waiting: T? = null

    /** The format of the track that is playing; null until the player has said what it is. */
    var current: T? = null
        private set

    /** The renderer read [format] for the track [mediaId]. */
    fun onFormat(mediaId: String?, format: T) {
        if (mediaId == null || mediaId == playingId) {
            current = format
            if (mediaId == waitingId) clearWaiting()
            return
        }
        waitingId = mediaId
        waiting = format
    }

    /** The player's current track is [mediaId] (a skip, a gapless step, a new playlist); null when it has none. */
    fun onTransition(mediaId: String?) {
        if (mediaId == playingId) return
        playingId = mediaId
        current = if (mediaId != null && mediaId == waitingId) waiting else null
        clearWaiting()
    }

    fun reset() {
        playingId = null
        current = null
        clearWaiting()
    }

    private fun clearWaiting() {
        waitingId = null
        waiting = null
    }
}
