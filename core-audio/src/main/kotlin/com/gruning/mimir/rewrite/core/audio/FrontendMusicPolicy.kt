package com.gruning.mimir.rewrite.core.audio

/**
 * Pure admission and mix law for the foreground frontend composition.
 *
 * Gain staging: The music was reading the UI **sound-effect**
 * controls — `AudioPreferences.enabled` is literally `KEY_ENABLED = "uiSoundEnabled"`, and
 * `AudioPreferences.volume` is the click/nav loudness that `MimirAudio` uses. Two consequences,
 * both defects rather than tuning:
 *
 *  - Turning navigation clicks off silenced the music, despite the separate music control.
 *  - The Utility rail shows `ambientVolume` (0.22) while the bed was delivered at
 *    `volume * ambientVolume` = 0.6 * 0.22 = 0.132 — the control lied by 4.4 dB, so tuning the
 *    music by ear was tuning against a number that did not mean what it said.
 *
 * The bed now takes its own control and delivers exactly what the rail reads. A quiet mix is then
 * an honest player choice instead of an accident of two unrelated sliders multiplying.
 */
internal data class FrontendMusicPolicyInput(
    val appForeground: Boolean,
    /** The bed's own loudness, as surfaced by the Utility rail. Zero is the off switch. */
    val musicVolume: Float,
    val focus: AudioFocusState,
    val environmentBlocked: Boolean,
    val competingOwnedBed: Boolean,
    /**
     * The Settings > Music switch. Off is silence everywhere, reached through the same fade-out as every
     * other yield, so the player hears the music leave rather than stop. Defaulted true so a caller that
     * predates the switch keeps its meaning.
     */
    val musicEnabled: Boolean = true,
    /**
     * Your music is the player (a folder plan), so it plays EVERYWHERE and CONTINUOUSLY (policy 10-01, 2): it ignores
     * [appForeground], a launched game's [AudioFocusState.Suspended], and [environmentBlocked], so moving between Mimir and
     * other apps never changes its level. Another app's audio is arbitrated by real Android audio focus instead
     * ([MusicPausePolicy], via [pauseReason]), which pauses and resumes the track where it was; a bind-time courtesy gate that
     * reads other music's playback would silence it before focus ever arbitrated. The theme bed keeps every gate: it is
     * Mimir-only and stops when Mimir backgrounds or a game launches. Hero Motion's duck ([competingOwnedBed]) stays for
     * both, since that is Mimir's own audio and not another app's.
     */
    val everywhere: Boolean = false,
    /** Why Your music is paused (focus lost, screen off, headphones unplugged, the player's pause). Read only when [everywhere]. */
    val pauseReason: MusicPauseReason? = null,
    /**
     * Android's STREAM_MUSIC level as a fraction of its steps (the "Game & media" slider). Your music's player gain compensates for
     * it so [musicVolume], the PERCEIVED level, stays where the player set it (see [FrontendMusicPolicy.compensatedGain]). Read only when
     * [everywhere]; the theme bed is a Mimir sound and rides the stream unchanged. Defaulted to full so callers that predate it keep
     * their meaning.
     */
    val mediaLevel: Float = 1f,
    /**
     * The time of day is outside the music hours (Settings › Music › Music hours). Silence for BOTH sources, unlike [pauseReason], which
     * only the everywhere path reads: a theme bed ignores the pause reasons (it is Mimir-only and stops with Mimir), but the hours are
     * the player's wish about the music as a whole. Carried by itself because [pauseReason] names one reason, and a real hold outranks
     * the hours in that one name.
     */
    val scheduleBlocked: Boolean = false,
    /**
     * "Play outside Mimir" (Settings › Music). Read only when [everywhere]: Off keeps Your music inside Mimir, so it is silent
     * whenever [appForeground] is false, exactly like the theme bed. On plays in games and other apps.
     */
    val playOutsideMimir: Boolean = true,
    /**
     * Another app (or a call) holds the audio ([MusicPausePolicy.heldByOtherApp]): a focus loss, or the yield to a player that started
     * while Mimir held no focus. Read only when NOT [everywhere]: Your music reads the same hold through [pauseReason]. The theme bed
     * obeys it so that it is one player at a time : the player's own start takes focus
     * and the other app pauses; another app starting afterwards pauses the bed. Defaulted false so callers that predate it keep their meaning.
     */
    val otherAppHeld: Boolean = false,
    /**
     * The player started the theme bed on purpose and nothing has ended that since ([BedTakeover]):
     * [environmentBlocked] does not hold it, so it sounds over an app that holds no audio focus (a game left running behind Mimir). Read only when NOT
     * [everywhere] (Your music ignores the environment altogether). It waives the courtesy to other music and nothing else: [callInterrupted],
     * [otherAppHeld], the music hours and a game in front still silence it. A passive start never has it, so passive starts are unchanged.
     */
    val environmentWaived: Boolean = false,
    /**
     * A call interrupts ([MimirMediaBed.callInterrupted]). It is part of [environmentBlocked] too; it is carried on its own because [environmentWaived]
     * lifts the one and must never lift the other. The theme bed hears a call only through the media bed's audio mode.
     */
    val callInterrupted: Boolean = false,
)

internal object FrontendMusicPolicy {
    fun targetVolume(input: FrontendMusicPolicyInput): Float {
        if (!input.musicEnabled) return 0f
        if (input.scheduleBlocked) return 0f
        if (input.everywhere) {
            if (input.pauseReason != null) return 0f
            if (!input.playOutsideMimir && !input.appForeground) return 0f
            val level = compensatedGain(input.musicVolume, input.mediaLevel)
            return if (input.competingOwnedBed) level * OwnedBedDuckRatio else level
        }
        if (!input.appForeground) return 0f
        if (input.focus is AudioFocusState.Suspended) return 0f
        if (input.callInterrupted) return 0f
        if (input.environmentBlocked && !input.environmentWaived) return 0f
        if (input.otherAppHeld) return 0f
        val normal = input.musicVolume.coerceIn(0f, 1f)
        return if (input.competingOwnedBed) normal * OwnedBedDuckRatio else normal
    }

    /**
     * Whether the THEME BED is on its way to sound, whatever else is playing: Music on, a level above zero, Mimir in front, no game
     * session and no off-hours. It deliberately does not ask the environment ([FrontendMusicPolicyInput.environmentBlocked]) or another
     * app's hold: the control uses it to decide whether the bed wants audio focus, and focus is how Mimir takes the audio from an
     * app that is already playing when the player starts the music. Zero is no focus (the 0-volume rule).
     */
    fun bedWantsToSound(input: FrontendMusicPolicyInput): Boolean =
        input.musicEnabled && !input.scheduleBlocked && !input.everywhere && input.appForeground &&
            input.focus !is AudioFocusState.Suspended && input.musicVolume > 0f

    /**
     * Your music's player gain for a PERCEIVED level [perceived] (the Music slider, M) under a STREAM_MUSIC level [streamLevel]
     * (the Game & media slider, S): the player's output is gain x S, so gain = M / S, capped at 1. Lowering Game & media therefore
     * leaves Your music where it is until S falls to M, and below that both fall together. S of zero is silence, not a division.
     */
    fun compensatedGain(perceived: Float, streamLevel: Float): Float {
        val music = perceived.coerceIn(0f, 1f)
        val stream = streamLevel.coerceIn(0f, 1f)
        if (music <= 0f || stream <= 0f) return 0f
        return (music / stream).coerceAtMost(1f)
    }

    /** Whether Game & media is below the Music level, so the perceived music is held under what the player set. */
    fun musicCappedByMedia(perceived: Float, streamLevel: Float): Boolean =
        perceived.coerceIn(0f, 1f) > streamLevel.coerceIn(0f, 1f)

    private const val OwnedBedDuckRatio = 0.15f
}

/**
 * Whether something OTHER than the frontend bed is audible.
 *
 * Takes the bed's whole token set, not one token, because the gapless loop runs two players: the one
 * sounding and the one already prepared to take over at the seam. With a single-token comparison the
 * bed would see its own second deck as a competitor and duck itself to 15% — for the whole life of
 * the loop, since there is always a next deck.
 */
internal fun frontendMusicHasCompetingBed(
    activeTokens: Set<Long>,
    frontendTokens: Set<Long>,
): Boolean = activeTokens.any { it !in frontendTokens }
