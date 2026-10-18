package com.gruning.mimir.rewrite.core.audio

import android.graphics.Bitmap
import android.media.AudioAttributes
import kotlinx.coroutines.flow.StateFlow

/** Whose music a panel control drives. */
enum class MusicSource {
    /** The folder plan is the player and it is the engine in this process. */
    YOUR_MUSIC,

    /**
     * Another app's media session, as the companion's media bridge reads it through the notification-access grant. Without the grant
     * no other player is offered. Its controls go through that session's transport controls.
     */
    OTHER_PLAYER,

    NONE,
}

/**
 * Your music as the panel's card shows it. Present in [MusicControlState.yours] only while it is playing or paused (by the player,
 * another app, the screen or the headphones).
 *
 * @property title the current track's title tag, or its file name without the extension when it has none.
 * @property artist the current track's artist tag (the album artist when it names no artist), once the library has read it; null while unread and when the tags name none, so the card falls back to the format line.
 * @property trackId the id the library knows the track on air by (see [MusicCatalogTrack.id]); the key for its embedded art. Null while no folder track is on air.
 * @property isPlaying true while audible.
 * @property pausedReason why it is paused, or null when it is not paused.
 * @property pausedNeedsPlay the pause is another app's and nothing will end it but the player's play: a permanent loss without
 *  notification access (see [MusicPausePolicy.holdNeedsPlay]). The panel's hint says "press play" instead of "resumes".
 * @property canPrevious whether Previous has somewhere to go: more than one track.
 * @property canNext likewise for Next.
 * @property format the track's source and output format, once the player has reported it; null before that.
 * @property positionMs where the track was when [positionUpdatedAtElapsedRealtimeMs] was taken; null while there is no track on air to read. A reader projects it forward on the
 *  elapsed-realtime clock while [positionAdvancing] and the track [isPlaying] (the state is republished at a start, a pause, a seek and a track change, never on a tick).
 * @property durationMs the track's length, once the player has read it; null before that.
 * @property canSeek whether the track can be seeked: its length is known.
 * @property trackKey one value per playing of the track (see [YourMusicTiming.trackKey]), the identity a seek gesture is keyed on; null before the player reports one.
 */
data class YourMusicState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val pausedReason: MusicPauseReason? = null,
    val pausedNeedsPlay: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val format: TrackFormat? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val positionUpdatedAtElapsedRealtimeMs: Long = 0L,
    val positionAdvancing: Boolean = false,
    val canSeek: Boolean = false,
    val trackKey: String? = null,
    val trackId: String? = null,
    val artist: String? = null,
)

/**
 * Mimir's music while the THEME BED is its source (the default, "Mimir"), as a reader that only wants to say what is playing sees it. It is not a
 * panel player: it has no card, no transport and no pause of its own, so it never counts toward [MusicControlState.present], the M-tap rule or
 * [musicLastPlayed].
 *
 * @property title the bed's title, as the Now playing row names it.
 * @property isPlaying the bed has a deck sounding and the music level is above zero. The engine releases the deck when a hold silences the bed
 *  (another app, a call, the music hours, a game in front), so this is false while the bed is held, however long [title] stays set.
 */
data class ThemeBedState(val title: String, val isPlaying: Boolean)

/**
 * What the music panel shows and drives: up to two players at once.
 *
 * @property yours Your music while it is playing or paused, else null.
 * @property other another app's player while its session is playing or paused, else null. Needs the notification-access grant.
 * @property otherAccess whether that grant is held, so the panel can say where to allow it when it is not.
 * @property bed Mimir's music while the theme bed is the source and Music is on, else null (Your music present means no bed). Read only by what
 *  names the sound on the device (the Equalizer's header); the panel's own functions never see it.
 */
data class MusicControlState(
    val yours: YourMusicState? = null,
    val other: OtherPlayerSnapshot? = null,
    val otherAccess: Boolean = false,
    /**
     * The player that last sounded, for the panel's one full card when neither plays ([musicLastPlayed]). Your music until anything
     * has played; it survives that player's card leaving.
     */
    val lastPlayed: MusicSource = MusicSource.YOUR_MUSIC,
    val bed: ThemeBedState? = null,
) {
    /** Something is there to control: the panel can open. */
    val present: Boolean get() = yours != null || other != null

    /**
     * Mimir's own music is sounding right now: Your music is playing (a track on air and no pause), or the theme bed has a deck sounding at a level above
     * zero. False while it is held (another app, a call, the cable, the screen, the music hours, the player's pause), silent at level zero, or off. Another
     * app's [other] player is not Mimir's music and never counts. This is the one reading the Settings > Music status chip follows, so the chip never
     * says "Playing" for music that is waiting.
     */
    val mimirMusicSounding: Boolean get() = yours?.isPlaying == true || bed?.isPlaying == true
}

/**
 * Mimir's music is audible, not only sounding: Android's STREAM_MUSIC is the level of everything Mimir's music plays on ([mediaLevel], the "Game & media" slider), and at zero the
 * player is running into silence. The Settings > Music chip reads this, so it says Paused rather than Playing for music nobody can hear.
 */
fun mimirMusicAudible(sounding: Boolean, mediaLevel: Float): Boolean = sounding && mediaLevel > 0f

/**
 * Which player last sounded: exactly one of the two playing says it is that one; both or neither leaves [previous] standing.
 * Pure, so the policy "neither plays: the most recently played" is a table test. Sequential in the one collector that
 * derives [MusicControlState], so the held value needs no lock.
 */
internal fun musicLastPlayed(previous: MusicSource, state: MusicControlState): MusicSource {
    val yours = state.yours?.isPlaying == true
    val other = state.other?.isPlaying == true
    return when {
        yours && !other -> MusicSource.YOUR_MUSIC
        other && !yours -> MusicSource.OTHER_PLAYER
        else -> previous
    }
}

/**
 * One other player's session, in the shape the facade needs. The app maps its media-session bridge onto this.
 *
 * @property title the session's title.
 * @property artist the session's artist.
 * @property appLabel the session's app name.
 * @property artwork the session's artwork when it supplied a bitmap.
 * @property packageName the session's app package, for matching this player to the bridge's track by app (a label can repeat or be blank).
 * @property isPlaying playing or buffering (the bridge maps BUFFERING, CONNECTING and SKIPPING to playing); false is PAUSED. A STOPPED
 *  session is not offered at all.
 */
data class OtherPlayerSnapshot(
    val title: String,
    val artist: String?,
    val appLabel: String?,
    val artwork: Bitmap?,
    val isPlaying: Boolean,
    /** What the session's transport offers (the bridge reads them off the session); true until a bridge says otherwise. */
    val canPrevious: Boolean = true,
    val canNext: Boolean = true,
    val canPlayPause: Boolean = true,
    /** The session names art that is still loading (a URI being read): the panel draws neither the art nor a fallback until it has finished. */
    val artworkPending: Boolean = false,
    /** The session's art failed to load : the panel draws neither the art, nor a plate, nor the fallback glyph for it. */
    val artworkFailed: Boolean = false,
    /** The session named this title (authored: drawn in its own case). False when the bridge synthesised it because the session named none; the panel then draws it as chrome. */
    val titleIsAuthored: Boolean = true,
    /** The session's app package, so a surface can tell which app this is without trusting its label; null when the source did not carry it (it then never matches a package). */
    val packageName: String? = null,
)

/**
 * Another app's player, driven through its own MediaSession transport controls. Implemented by the app over the companion's
 * media-session bridge; core-audio has no notification-access of its own and holds no session.
 */
interface OtherPlayerSource {
    /** The session to offer, or null when there is none or access is not granted (then no other player is offered). */
    val player: StateFlow<OtherPlayerSnapshot?>

    /**
     * Whether the media-session grant (notification access) is held, so [player] is authoritative: null then means no other app
     * has a session. False hands the "is another app playing music" question to the playback-configuration heuristic.
     */
    val sessionAccessGranted: StateFlow<Boolean>

    fun playPause()

    fun next()

    fun previous()
}

internal data class MusicControlInputs(
    /** Your music is the source and playable, and the Music switch is on. */
    val yourMusicActive: Boolean,
    /** The engine has a track sounding: the folder's player, or the theme bed's deck (this includes the 260 ms fade-out of a pause). */
    val yourMusicSounding: Boolean,
    val title: String?,
    val pause: MusicPauseReason?,
    /** The music level; zero is the off switch. */
    val musicVolume: Float,
    val other: OtherPlayerSnapshot? = null,
    /** How many tracks Your music plays from; a single one has nothing to skip to. */
    val yourMusicTrackCount: Int = 0,
    /** Notification access is held (the session bridge is reading other apps' sessions). */
    val otherAccess: Boolean = false,
    /** The pause is another app's permanent hold and nothing but the player's play ends it ([MusicPausePolicy.holdNeedsPlay]). */
    val pauseNeedsPlay: Boolean = false,
    /** The track on air's format; only Your music's own track is ever described. */
    val format: TrackFormat? = null,
    /** Where the track on air is (W3e); only Your music's own track has one. */
    val timing: YourMusicTiming? = null,
    /** The track on air's library id, for its embedded art; null for the theme bed. */
    val trackId: String? = null,
    /** The track on air's artist tag, once read; null for the theme bed and for a track whose tags name none. */
    val artist: String? = null,
    /** The theme bed's title while the bed is what the engine is on (Music on); null for Your music and while the music is off. */
    val bedTitle: String? = null,
)

/**
 * The panel's state as a pure function of what the engine, the pause policy and the other-player bridge say. Both players are
 * carried: Your music when it is playing or paused, and another player's session when one is offered.
 */
internal fun deriveMusicControlState(input: MusicControlInputs): MusicControlState {
    val yourPresent = input.yourMusicActive && input.musicVolume > 0f && (input.yourMusicSounding || input.pause != null)
    val yours = if (yourPresent) {
        val playing = input.yourMusicSounding && input.pause == null
        YourMusicState(
            title = input.title,
            artist = input.artist,
            trackId = input.trackId,
            isPlaying = playing,
            pausedReason = input.pause.takeIf { !playing },
            pausedNeedsPlay = !playing && input.pause == MusicPauseReason.OTHER_APP && input.pauseNeedsPlay,
            canPrevious = input.yourMusicTrackCount > 1,
            canNext = input.yourMusicTrackCount > 1,
            format = input.format,
            positionMs = input.timing?.positionMs,
            durationMs = input.timing?.durationMs,
            positionUpdatedAtElapsedRealtimeMs = input.timing?.updatedAtElapsedRealtimeMs ?: 0L,
            positionAdvancing = input.timing?.advancing == true,
            canSeek = input.timing?.durationMs != null,
            trackKey = input.timing?.trackKey?.takeIf { it.isNotEmpty() },
        )
    } else {
        null
    }
    // The theme bed is never a panel player (see [ThemeBedState]); it is only so the Equalizer's header can say what is sounding.
    val bed = input.bedTitle?.takeIf { !input.yourMusicActive }?.let { title ->
        ThemeBedState(title = title, isPlaying = input.yourMusicSounding && input.musicVolume > 0f)
    }
    return MusicControlState(yours = yours, other = input.other, otherAccess = input.otherAccess, bed = bed)
}

/**
 * The M-tap rule's input: Your music is the active source and is playing or paused (by the player, another app, the screen or the
 * headphones), or another player's session is playing or paused. False when the music is off or stopped, when the theme bed is what
 * plays, and when no session is offered.
 */
internal fun musicPanelAvailable(input: MusicControlInputs): Boolean = deriveMusicControlState(input).present

/**
 * The fallback heuristic's per-configuration test, used only when the media-session grant is absent: a playback configuration
 * counts as another app's music player when it is USAGE_MEDIA tagged CONTENT_TYPE_MUSIC or CONTENT_TYPE_MOVIE (YouTube and Spotify
 * tag themselves so). USAGE_GAME, UI sonification and CONTENT_TYPE_UNKNOWN do not count: a backgrounded emulator (Dolphin on the
 * Nova) keeps an OpenSL player STARTED, USAGE_MEDIA with CONTENT_TYPE_UNKNOWN, indefinitely while holding no audio focus, and
 * that must neither stop Your music from starting nor keep a permanent focus loss held. A HEURISTIC: public Android names no
 * owner for a configuration, and an app that tags its music UNKNOWN is missed.
 */
internal fun isRealMediaPlayback(usage: Int, contentType: Int): Boolean =
    usage == AudioAttributes.USAGE_MEDIA &&
        (contentType == AudioAttributes.CONTENT_TYPE_MUSIC || contentType == AudioAttributes.CONTENT_TYPE_MOVIE)

/**
 * The stranger-audio heuristic as arithmetic: the real-media playback configurations ([isRealMediaPlayback]) that are active, less
 * those Mimir accounts for as its own players (which are USAGE_MEDIA music, so they are inside that count). The result is a
 * count of strangers, never negative.
 */
internal fun strangerPlayers(activeConfigurations: Int, ownActivePlayers: Int): Int =
    (activeConfigurations - ownActivePlayers).coerceAtLeast(0)

/**
 * "Another app is playing music", the one answer behind the start courtesy and the permanent-hold release. With the media-session
 * grant the bridge is authoritative: a non-Mimir session that is playing, and nothing else (a game or emulator has no media
 * session). Without the grant the playback-configuration heuristic stands in ([strangerPlayers] over [isRealMediaPlayback]).
 * It only gates Mimir's own focus decisions; nothing displays it and no panel state comes from it.
 */
internal fun anotherPlayerPlaying(sessionAccessGranted: Boolean, sessionPlaying: Boolean, heuristicStrangers: Int): Boolean =
    if (sessionAccessGranted) sessionPlaying else heuristicStrangers > 0
