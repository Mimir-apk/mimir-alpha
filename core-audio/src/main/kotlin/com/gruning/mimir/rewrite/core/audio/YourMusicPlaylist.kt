package com.gruning.mimir.rewrite.core.audio

/**
 * Your music's playlist and gain, as pure rules so they can be asserted on the JVM. [YourMusicPlayback] is the hands: it owns the
 * Media3 player and applies what these say, on the player's own (main) looper.
 */

/** One folder track as the player is given it: the id the folder index calls it, where to read it, and what to show. */
internal data class YourMusicEntry(
    val mediaId: String,
    val uri: String,
    val title: String,
    /** The artist tag, else null (the session then shows the generic label). */
    val artist: String? = null,
    val album: String? = null,
)

/** Whether two playlists play the same tracks in the same order, whatever they are called: the player is only reloaded for a difference in what is played. */
internal fun List<YourMusicEntry>.playsTheSameAs(other: List<YourMusicEntry>): Boolean =
    size == other.size && indices.all { this[it].mediaId == other[it].mediaId && this[it].uri == other[it].uri }

/** How a track's bytes are named to the player. The Android edge implements it; tests supply their own. */
internal interface YourMusicUris {
    /** A track of a plain `file://` folder: [path] is the file's absolute path (the index stores it as the track id). */
    fun forPath(path: String): String

    /** A track of a picked system tree: [documentId] is the track's document id (the index stores it as the track id). */
    fun forDocument(tree: String, documentId: String): String
}

/**
 * The playlist for [tracks] of [tree], in the order given (index order; the caller has already chosen the folder's root tracks,
 * see [FrontendMusicSourceResolver]). The player repeats the whole list, so the last track is followed by the first.
 */
internal fun yourMusicEntries(
    tree: String,
    tracks: List<MusicTrack>,
    uris: YourMusicUris,
    labelOf: (MusicTrack) -> YourMusicLabel = { YourMusicLabel(musicTrackTitle(it.name)) },
): List<YourMusicEntry> {
    val plain = MusicTreeUri.plainFolderPath(tree) != null
    return tracks.map { track ->
        val label = labelOf(track)
        YourMusicEntry(
            mediaId = track.id,
            uri = if (plain) uris.forPath(track.id) else uris.forDocument(tree, track.id),
            title = label.title,
            artist = label.artist,
            album = label.album,
        )
    }
}

/** What the player does at the edge of a gain change. */
internal enum class YourMusicTransport {
    /** Start the player (silent), then ramp up. */
    START,

    /** Ramp the sounding player to the new level. */
    RETARGET,

    /** Ramp to silence, then pause: the music is not allowed (a pause reason, the Music switch, the level at zero). */
    PAUSE,

    /** Nothing to do: silent and staying silent. */
    NONE,
}

internal object YourMusicGain {
    /** The entrance of a track arriving from silence (a start, a skip): the same three seconds the theme bed arrives in. */
    const val EntranceMs = 3_000L

    /** A pause or a skip's way out: prompt, because yielding to another app or the screen should not linger. */
    const val ExitMs = 260L

    /** The same music returning after a pause: well under an entrance. */
    const val ResumeMs = 400L

    /** A level change while sounding is at least this long, so a hardware volume step does not click. */
    const val MinRetargetMs = 80L

    const val StepMs = 40L

    /** Below this the music is inaudible: the song-title chip is not shown for it, and the media bed does not count it. */
    const val AudibleFloor = 0.01f

    /** The player's volume for a policy target: the policy already folded in the Music level, Game & media and the duck. */
    fun playerVolume(target: Float): Float = target.coerceIn(0f, 1f)

    fun transport(target: Float, sounding: Boolean): YourMusicTransport = when {
        target > 0f && !sounding -> YourMusicTransport.START
        target > 0f -> YourMusicTransport.RETARGET
        sounding -> YourMusicTransport.PAUSE
        else -> YourMusicTransport.NONE
    }

    /** How long the climb from [from] to [to] takes: a fall is prompt, an entrance or resume is fixed, a retarget is proportional. */
    fun rampMs(from: Float, to: Float, entering: Boolean, resuming: Boolean): Long = when {
        to <= from -> ExitMs
        entering -> EntranceMs
        resuming -> ResumeMs
        else -> ((to - from) * EntranceMs).toLong().coerceIn(MinRetargetMs, EntranceMs)
    }

    fun steps(durationMs: Long): Int = (durationMs / StepMs).coerceAtLeast(1L).toInt()

    /** The player volume at [step] (1..[steps]) of a linear ramp; the last step is exactly [to]. */
    fun rampValue(from: Float, to: Float, step: Int, steps: Int): Float {
        if (step >= steps) return to
        return from + (to - from) * (step.toFloat() / steps.toFloat())
    }
}

/** What to do with a track that would not play. */
internal sealed interface YourMusicFailure {
    /** Try the track after it. */
    data object SkipToNext : YourMusicFailure

    /** The folder cannot play: the theme bed takes over, with [reason] shown in Settings. */
    data class GiveUp(val reason: CustomMusicFallback) : YourMusicFailure
}

internal fun yourMusicFailure(consecutiveFailures: Int, reason: CustomMusicFallback, maxAttempts: Int): YourMusicFailure =
    if (consecutiveFailures >= maxAttempts) YourMusicFailure.GiveUp(reason) else YourMusicFailure.SkipToNext

/** The next index when a skip or a failure moves on, wrapping; the previous one likewise. 0 for an empty list. */
internal fun yourMusicStep(index: Int, count: Int, forward: Boolean): Int =
    if (count <= 0) 0 else if (forward) (index + 1) % count else (index - 1 + count) % count

/**
 * Where a freshly loaded playlist starts. In order it opens on the first track; shuffled it opens on a track [pick]ed at random out of
 * [count], or every cold start would open on the same song and Shuffle would look as if it did nothing. One track has nowhere else to start.
 */
internal fun yourMusicStartIndex(count: Int, shuffle: Boolean, pick: (Int) -> Int): Int =
    if (shuffle && count > 1) pick(count).coerceIn(0, count - 1) else 0

/**
 * Where a skip or a failure moves on to, in the order the player is playing in. [playerNeighbour] is the index the player itself would
 * go to (`nextMediaItemIndex` or `previousMediaItemIndex`), which follows Shuffle and the repeat mode; [yourMusicStep] is the list's own
 * order, used when the player has no answer (nothing is loaded, or it reports an index outside the list).
 */
internal fun yourMusicSkipIndex(from: Int, count: Int, forward: Boolean, playerNeighbour: Int?): Int =
    playerNeighbour?.takeIf { it in 0 until count } ?: yourMusicStep(from, count, forward)

/**
 * Where a press of Next or Previous lands. Previous past [PreviousRestartsAfterMs] into a track restarts that track (the same index);
 * every other press goes to [yourMusicSkipIndex].
 */
internal fun yourMusicSkipDestination(from: Int, count: Int, forward: Boolean, positionMs: Long, playerNeighbour: Int?): Int =
    if (!forward && positionMs > PreviousRestartsAfterMs) from else yourMusicSkipIndex(from, count, forward, playerNeighbour)

/**
 * The gapless volume dip between tracks ("Fade between tracks"). One player sounds at a time , so a crossfade, which
 * needs two players at once, is not built: the one player's volume falls over the end of a track and rises over the start of the
 * next. It is polled, not scheduled: [step] says what to do at each look at the track's remaining time.
 */
internal object YourMusicDip {
    /** The way out, over the last second of a track. */
    const val OutMs = 1_000L

    /** The way in, over the first second of the next. */
    const val InMs = 1_000L

    /** How often the playing track is looked at. */
    const val PollMs = 250L

    /** A fade out that starts this close to the end is still audible as one. */
    private const val MinOutMs = 200L

    enum class Step { NONE, FADE_OUT, FADE_IN }

    /**
     * What to do now. [dipped] is whether the volume is down for the end of a track. [remainingMs] is the time left in the track on
     * air, or null while its length is not known. With the fade off, with fewer than two tracks (a single one loops, and has nothing
     * to fade between) or without a length, nothing is faded and a dip that is down comes back up.
     */
    fun step(enabled: Boolean, trackCount: Int, dipped: Boolean, remainingMs: Long?): Step {
        if (!enabled || trackCount < 2 || remainingMs == null) return if (dipped) Step.FADE_IN else Step.NONE
        return when {
            !dipped && remainingMs <= OutMs + PollMs -> Step.FADE_OUT
            // The new track's whole length is ahead: the way in has not been taken, so take it now.
            dipped && remainingMs > OutMs + 2 * PollMs -> Step.FADE_IN
            else -> Step.NONE
        }
    }

    /** How long the way out takes: what is left of the track, so it reaches silence as the track ends. */
    fun outMs(remainingMs: Long): Long = remainingMs.coerceIn(MinOutMs, OutMs)
}

/** Who is moving the one player's volume. Only one ramp runs at a time; a newer one cancels the older. */
internal enum class YourMusicRampKind {
    /** The policy's level: a start's entrance, a retarget (Hero Motion's duck, the slider), a skip's entrance on the new track. */
    LEVEL,

    /** The volume dip between tracks ("Fade between tracks"), out and in. */
    DIP,

    /** A skip's way out, which ends in the move to the other track. The move rides on the ramp finishing. */
    SKIP,

    /** A pause's way out, which ends in the player pausing. The pause rides on the ramp finishing. */
    PAUSE,
}

/**
 * The one owner of the volume ramp. The dip is automatic and the rest answer a command, and a ramp that is cancelled never runs
 * its ending: a dip that cancelled a skip's way out would lose the move, and one that cancelled a pause's would leave the player
 * playing at zero volume, holding its wake lock. So the dip yields to everything, and a skip's way out is not undone by a level change.
 */
internal object YourMusicRamps {
    /** Whether a ramp of [requested] kind may replace the one in flight ([inFlight], null for none). */
    fun mayStart(requested: YourMusicRampKind, inFlight: YourMusicRampKind?): Boolean = when (requested) {
        // A command's ramp is never cut short by the dip; a level ramp carries nothing that would be lost.
        YourMusicRampKind.DIP -> inFlight == null || inFlight == YourMusicRampKind.LEVEL || inFlight == YourMusicRampKind.DIP
        // The skip's entrance reads the level when the move is made, so a change of level during its way out is not lost by waiting.
        YourMusicRampKind.LEVEL -> inFlight != YourMusicRampKind.SKIP
        // A command replaces whatever runs: a play over a pause fade has already cleared it, and a pause over a skip takes the move first.
        YourMusicRampKind.SKIP, YourMusicRampKind.PAUSE -> true
    }

    enum class AfterTrackChange { NOTHING, FADE_BACK_IN, DROP_DIP }

    /**
     * What a track change does to a dipped volume. The way back in is the dip's, so it is taken only when the dip may act; under a
     * pause or a skip the dip is simply over (the pause's own ending silences the player, the skip's own entrance brings it back).
     */
    fun afterTrackChange(dipped: Boolean, inFlight: YourMusicRampKind?): AfterTrackChange = when {
        !dipped -> AfterTrackChange.NOTHING
        mayStart(YourMusicRampKind.DIP, inFlight) -> AfterTrackChange.FADE_BACK_IN
        else -> AfterTrackChange.DROP_DIP
    }
}
