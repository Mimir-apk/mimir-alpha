package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where Your music's track on air is, as the panel's seek bar needs it (W3e). A snapshot, not a clock: the position the player reported at
 * [updatedAtElapsedRealtimeMs] and whether the track was advancing then, so a reader projects the position forward on the elapsed-realtime clock exactly as it does for
 * another player's session. It is republished only when something moves it (a start, a pause, a seek, a track change, the duration arriving), never on a tick.
 *
 * @property durationMs the track's length, or null while the player has not read it (a stream or a track still opening): with none, the track cannot be seeked or read as progress.
 * @property advancing the track was sounding when this was taken.
 * @property trackKey one value per playing of a track: the track's own id (its file or document) and a count that moves at every track change, a repeat of the same track and
 *  every playlist load. Two tracks that share a title and a length (a re-ripped album, a duplicate in two folders) are still two keys, so a seek gesture that began on one
 *  can never land on the other.
 */
internal data class YourMusicTiming(
    val positionMs: Long,
    val durationMs: Long?,
    val updatedAtElapsedRealtimeMs: Long,
    val advancing: Boolean,
    val trackKey: String = "",
)

/** Where [YourMusicPlayback] puts its [YourMusicTiming]; a separate object so reading the flow never touches the player's main-looper handler. */
internal object YourMusicTimingFeed {
    private val mutableTiming = MutableStateFlow<YourMusicTiming?>(null)

    /** Null while there is no track on air. */
    val timing: StateFlow<YourMusicTiming?> = mutableTiming.asStateFlow()

    fun publish(value: YourMusicTiming?) {
        mutableTiming.value = value
    }
}
