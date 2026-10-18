package com.gruning.mimir.rewrite.core.audio

/**
 * Why Your music is paused right now. The panel names it; [MusicPauseState.reason] picks one when several hold. [SCHEDULE] is the music
 * hours (Settings › Music › Music hours): the time of day is outside the window the player chose.
 */
enum class MusicPauseReason { OTHER_APP, SCREEN_OFF, HEADPHONES, USER, SCHEDULE }

/**
 * How another app holds the audio.
 *
 * Android's focus callbacks do not name the taker, and a PERMANENT loss ([FocusSignal.LOSS]) is never followed by a gain:
 * the system drops Mimir's request. So "resumes when focus returns" is two different returns:
 *  - [TRANSIENT] (a transient loss, or a request the system delayed): the GAIN callback is the return. Never inferred from
 *    "no other player", because a phone call is silent to that heuristic and must not be resumed over.
 *  - [PERMANENT] (a permanent loss, or Mimir yielding to music that was already playing): the return is the other player being
 *    CONFIRMED paused or stopped ([MusicPausePolicy.onOtherSessionStopped]), after which Mimir
 *    asks for focus again. Only the media-session grant can confirm it, so WITHOUT notification access nothing ends this hold
 *    but the player's own play: a player that merely goes quiet for a moment (YouTube buffering or switching screens) is not a
 *    player that stopped.
 *  - [NEEDS_FOCUS]: Mimir let go of its focus request while a [TRANSIENT] hold was on (the schedule closed, Mimir went to the
 *    background, a game came to the front), so no GAIN can ever arrive for it. The music stays held, and silent, until a FRESH request is
 *    granted: clearing this only allows that request to be made, never playback.
 */
internal enum class OtherAppHold { NONE, TRANSIENT, PERMANENT, NEEDS_FOCUS }

/** What the audio-focus listener reports, with the framework's constants already mapped. */
internal enum class FocusSignal { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

/**
 * Every independent reason Your music is not playing. They are separate flags rather than one enum so each releases only
 * what it set: screen-on clears [screenOff] and nothing else, so a track the player paused, or one a pair of unplugged
 * headphones paused, stays paused when the screen comes back.
 */
internal data class MusicPauseState(
    val user: Boolean = false,
    val headphones: Boolean = false,
    val screenOff: Boolean = false,
    val otherApp: OtherAppHold = OtherAppHold.NONE,
    /** The time of day is outside the music hours. Set only at the edge into the off-hours, so a deliberate play overrides it until the next edge. */
    val schedule: Boolean = false,
    /**
     * A phone call, a VoIP call or a ringing phone: the audio mode says so ([MusicCallPolicy.inCall]), whether or not Mimir holds focus. A
     * fact about the device, not a choice: only the mode returning to normal clears it, not a press of play.
     */
    val call: Boolean = false,
) {
    /**
     * The reason the panel shows. The player's own choice outranks the others, then the unplugged cable, then another app, then the
     * screen, and the music hours come last: any real hold is the more useful thing to name, and the hours add nothing once one is set.
     */
    val reason: MusicPauseReason?
        get() = when {
            user -> MusicPauseReason.USER
            headphones -> MusicPauseReason.HEADPHONES
            otherApp != OtherAppHold.NONE || call -> MusicPauseReason.OTHER_APP
            screenOff -> MusicPauseReason.SCREEN_OFF
            schedule -> MusicPauseReason.SCHEDULE
            else -> null
        }

    val paused: Boolean get() = reason != null
}

/**
 * The pause rules for Your music, as pure transitions. The controller feeds each Android
 * event through one of these and acts on the result; nothing here touches Android.
 */
internal object MusicPausePolicy {

    /**
     * Whether a game is on screen: [evidence] (Mimir's launch is armed, or a known emulator package is in front), or a live game
     * [sessionLive] while Mimir is not [mimirInFront]. A session alone only says a game EXISTS: one parked behind Mimir (HOME, or
     * switched away) keeps its session while Mimir is the screen, and it is not in front then (turning the music on takes audio from other apps while a game is parked). [evidence] is the costly UsageStats read, so it
     * is asked only when the session term has not already answered.
     *
     * A game parked behind Mimir by the switch button (a non-home lease, e.g. a DS or 3DS game behind the Bottom-app lease) is a parked game here too
     * : Main's resume lets go of the launch's Suspended for that park as for a HOME park, so the launch is not evidence
     * and the player's start takes the audio. Main stopping under the game (fronted, or covered by Recents) arms the launch again, and the way back to either kind of park
     * begins the app's hand-back evidence before the task is fronted.
     */
    fun gameInFront(sessionLive: Boolean, mimirInFront: Boolean, evidence: () -> Boolean): Boolean =
        (sessionLive && !mimirInFront) || evidence()

    /**
     * Another app moved audio focus. [gameInFront] is read at the moment of the callback, not remembered: a game (a
     * Mimir-launched session, or a known emulator package in front) is EXEMPT, so a loss while one is in front leaves the
     * music playing. Whether the taker was a game is not in the callback; the foreground state stands in for it.
     */
    fun onFocus(state: MusicPauseState, signal: FocusSignal, gameInFront: Boolean): MusicPauseState = when (signal) {
        FocusSignal.GAIN -> state.copy(otherApp = OtherAppHold.NONE)
        // The framework ducks Mimir's players itself for a short prompt; there is nothing to pause or restore.
        FocusSignal.LOSS_TRANSIENT_CAN_DUCK -> state
        FocusSignal.LOSS_TRANSIENT ->
            if (gameInFront) state else state.copy(otherApp = OtherAppHold.TRANSIENT)
        FocusSignal.LOSS ->
            if (gameInFront) state else state.copy(otherApp = OtherAppHold.PERMANENT)
    }

    /**
     * The audio mode entered or left a call ([MusicCallPolicy.inCall]). While one is up the music is held under OTHER_APP, which a call is,
     * even when a game is in front and Mimir has let go of focus (so no loss ever reaches it). When it ends the hold clears and the music
     * resumes the way it always does: asking for focus where focus is required, and under the game exemption otherwise.
     */
    fun onCall(state: MusicPauseState, inCall: Boolean): MusicPauseState = state.copy(call = inCall)

    /** The screen went off or on. On clears only what off set. */
    fun onScreen(state: MusicPauseState, on: Boolean): MusicPauseState = state.copy(screenOff = !on)

    /** ACTION_AUDIO_BECOMING_NOISY. Pauses; nothing here ever clears it except the player's own play. */
    fun onNoisy(state: MusicPauseState): MusicPauseState = state.copy(headphones = true)

    /**
     * The clock crossed an edge of the music hours: [outsideHours] is true on the way out of them and false on the way in. Only the
     * control's edge detector calls this, so a play the player pressed during the off-hours (which clears [MusicPauseState.schedule] through
     * [onUserPlayPause]) is not undone by the next look at the clock.
     */
    fun onSchedule(state: MusicPauseState, outsideHours: Boolean): MusicPauseState = state.copy(schedule = outsideHours)

    /**
     * The pause state as Your music itself must obey it. A screen-off is a hold only while "Pause when screen is off" is on; with it
     * off, Your music plays through. The raw [MusicPauseState.reason] is unchanged for everything else that follows the screen (the
     * launch card's sound stops at a closed lid whatever this setting says). The unplug stop needs no such view: when it is off, the
     * event is not recorded at all.
     */
    fun forYours(state: MusicPauseState, pausesOnScreenOff: Boolean): MusicPauseState =
        if (pausesOnScreenOff) state else state.copy(screenOff = false)

    /**
     * Play/pause pressed (panel, notification, media key). While playing it is the player's pause. While paused for any
     * reason it is an explicit take-over: every hold clears, because the player asked for the music now. [yoursPaused] is whether
     * Your music is paused as it must obey the state ([forYours]); it defaults to the raw state's own answer.
     */
    fun onUserPlayPause(state: MusicPauseState, yoursPaused: Boolean = state.paused): MusicPauseState =
        // A call is not a choice to take over: the mode ending it is the only thing that does.
        if (yoursPaused) MusicPauseState(call = state.call) else state.copy(user = true)

    /**
     * The other player's media session has been confirmed paused or stopped ([ConfirmedPausedStreak]): a PERMANENT hold ends. A
     * TRANSIENT one waits for its GAIN (a phone call has no media session at all, so "no session" must never resume over it).
     */
    fun onOtherSessionStopped(state: MusicPauseState): MusicPauseState =
        if (state.otherApp == OtherAppHold.PERMANENT) state.copy(otherApp = OtherAppHold.NONE) else state

    /**
     * One observation of whether the other player has stopped, for [ConfirmedPausedStreak]. Needs the media-session grant: without
     * it nothing here can tell a stopped player from a quiet one, so this is never true. With it, the session reports PAUSED
     * (a snapshot that is not playing), STOPPED or NONE (no snapshot); BUFFERING, CONNECTING and SKIPPING arrive as playing, so
     * a player's blip while it buffers is not a stop.
     */
    fun otherSessionStopped(sessionAccessGranted: Boolean, session: OtherPlayerSnapshot?): Boolean =
        sessionAccessGranted && session?.isPlaying != true

    /** A PERMANENT hold that nothing will end by itself: no grant, so only the player's play resumes. The panel says so. */
    fun holdNeedsPlay(state: MusicPauseState, sessionAccessGranted: Boolean): Boolean =
        state.otherApp == OtherAppHold.PERMANENT && !sessionAccessGranted

    /**
     * One player sounds at a time (policy 10-01, Nova 18:13): a deliberate play of Your music pauses the other player's session
     * while it is PLAYING, through its own controller. [state] is the state BEFORE the press, so a press that resumes a pause is a
     * play. Needs the media-session grant: with none there is no card and no controller, and nothing is paused.
     */
    fun pausesOtherOnYoursPlay(state: MusicPauseState, sessionAccessGranted: Boolean, other: OtherPlayerSnapshot?): Boolean =
        state.paused && sessionAccessGranted && other != null && other.isPlaying && other.canPlayPause

    /**
     * The other direction: a play pressed on the OTHER player's card while Your music is [yoursSounding] pauses Your music. It is the
     * same hold a permanent focus loss sets ([onOtherPlayerPlayWhileYoursSounds]), so the 10 s confirmed-paused resume applies
     * identically in and out of games; it is not a user pause.
     */
    fun pausesYoursOnOtherPlay(sessionAccessGranted: Boolean, other: OtherPlayerSnapshot?, yoursSounding: Boolean): Boolean =
        yoursSounding && sessionAccessGranted && other != null && !other.isPlaying && other.canPlayPause

    fun onOtherPlayerPlayWhileYoursSounds(state: MusicPauseState): MusicPauseState = state.copy(otherApp = OtherAppHold.PERMANENT)

    /** The system queued the focus request (a call is up, say): the GAIN callback will say when it is Mimir's. */
    fun onFocusDelayed(state: MusicPauseState): MusicPauseState = state.copy(otherApp = OtherAppHold.TRANSIENT)

    /** The system refused the request outright. Nothing will call back, so only the player's play (or a restart) retries. */
    fun onFocusRefused(state: MusicPauseState): MusicPauseState = state.copy(otherApp = OtherAppHold.TRANSIENT)

    /**
     * Mimir let go of its focus request (the music is not wanted just now: the schedule closed, it left Mimir with "Play outside Mimir" Off,
     * a game came to the front, the level went to zero). A [OtherAppHold.TRANSIENT] hold ends only with the GAIN that request would have
     * received, so with the request gone nothing could ever end it. It becomes [OtherAppHold.NEEDS_FOCUS]: still a hold, so the music stays
     * silent (a call is still a call), and the next want may ask afresh. Only that request being GRANTED ([onFocus] with GAIN) plays; a
     * delayed or refused one holds again. A PERMANENT hold has its own end (the other player confirmed stopped).
     */
    fun onFocusAbandoned(state: MusicPauseState): MusicPauseState =
        if (state.otherApp == OtherAppHold.TRANSIENT) state.copy(otherApp = OtherAppHold.NEEDS_FOCUS) else state

    /** Another player was already audible when Your music wanted to start: yield without taking its focus. */
    fun onStartCourtesy(state: MusicPauseState): MusicPauseState = state.copy(otherApp = OtherAppHold.PERMANENT)

    /** Whether Mimir should be holding audio focus. Another app's hold does not release the request: its GAIN is what resumes. */
    fun wantsFocus(state: MusicPauseState): Boolean = !state.user && !state.headphones && !state.screenOff && !state.schedule

    /**
     * Whether to ask for focus now. Never while a game is in front (taking focus from a game would pause the game), and never
     * while another app's hold is set (its own return, not a retry loop, ends that). The one exception is [OtherAppHold.NEEDS_FOCUS],
     * whose request is gone: it is asked for afresh, and the music plays only if that request is granted.
     */
    fun mayRequestFocus(state: MusicPauseState, gameInFront: Boolean): Boolean =
        wantsFocus(state) && !gameInFront && !state.call && (state.otherApp == OtherAppHold.NONE || state.otherApp == OtherAppHold.NEEDS_FOCUS)

    /**
     * Whether a focus request must yield to another player that is audible (after Nova 12:46: Mimir must never
     * take the audio from another player). Only a deliberate play from the player is exempt. Everything else (a start, a return from
     * a game, screen-on, any reconcile) yields, EVEN when Your music is already sounding: music that played through a game without
     * focus is no incumbent, and asking for focus now would make the other player's app pause itself for good.
     */
    fun yieldsToPlayingOther(deliberate: Boolean, otherPlayerPlaying: Boolean): Boolean = !deliberate && otherPlayerPlaying

    /** What a reconcile does when Your music wants focus and holds none. */
    enum class FocusDecision { NONE, YIELD_TO_OTHER, REQUEST }

    /**
     * The one decision behind every (re-)request of focus: nothing while a game is in front or a hold is set; otherwise yield (the
     * [onStartCourtesy] hold, no request) to another playing player unless the play was [deliberate]; otherwise ask.
     */
    fun focusDecision(state: MusicPauseState, gameInFront: Boolean, deliberate: Boolean, otherPlayerPlaying: Boolean): FocusDecision = when {
        !mayRequestFocus(state, gameInFront) -> FocusDecision.NONE
        yieldsToPlayingOther(deliberate, otherPlayerPlaying) -> FocusDecision.YIELD_TO_OTHER
        else -> FocusDecision.REQUEST
    }

    /**
     * Focus Mimir holds is let go while a game is in front ([gameInFront]: a Mimir launch is armed, an emulator package is in front, or
     * a live session while Mimir is off the front). Android 12+ fades the holder's players for 2 s when another app requests focus, and dispatches the loss after, so
     * letting go first spares Your music the dip; it keeps playing (the exemption), and asks again once no game is in front.
     */
    fun abandonsFocusForGame(focusHeld: Boolean, gameInFront: Boolean): Boolean = focusHeld && gameInFront

    /**
     * A real media player has just started while Mimir's music is sounding without holding focus (a game took it and was exempt, so
     * the request was dropped or never renewed; or a theme bed that was never given focus): the newcomer's focus request reached no
     * Mimir listener, so no loss will ever come. Mimir yields by hand, unless a hold is already set or a game is in front (the
     * exemption stands). Both sources use it: the theme bed obeys the hold it sets ([heldByOtherApp]).
     */
    fun yieldsToStartedPlayer(focusHeld: Boolean, ownMusicSounding: Boolean, state: MusicPauseState, gameInFront: Boolean): Boolean =
        !focusHeld && ownMusicSounding && state.otherApp == OtherAppHold.NONE && !gameInFront

    // --- one player at a time, for the theme bed as well as Your music ---------------

    /**
     * The player started Mimir's music on purpose (turned Music on, picked the source): another app's hold ends, so the request the
     * start makes is not met by a standing yield. Only that hold. The player's own pause, the unplugged cable, the screen, the music
     * hours and a call are not another app's and do not end here, and this is not a play press, so it never sets [MusicPauseState.user].
     */
    fun onDeliberateStart(state: MusicPauseState): MusicPauseState = state.copy(otherApp = OtherAppHold.NONE)

    /**
     * The player turned Music ON (turning Music on is a deliberate play, so Music on means it plays). Everything
     * [onDeliberateStart] ends, and the player's own pause too: Music was off, and a pause made before it was switched off must not keep it
     * silent now. The unplugged cable, the screen, the music hours and a call are not the player's to clear here and stay.
     */
    fun onMusicTurnedOn(state: MusicPauseState): MusicPauseState = onDeliberateStart(state).copy(user = false)

    /**
     * The player chose the music source in Settings (choosing a source is a deliberate play, as turning Music on is). Everything [onMusicTurnedOn] ends: another app's hold and the player's own earlier pause,
     * so a pause pressed on one source does not keep the one just chosen silent. The cable, the screen, the music hours and a call stay.
     */
    fun onSourceChosen(state: MusicPauseState): MusicPauseState = onMusicTurnedOn(state)

    /**
     * The plan flipped to the folder the player just picked (picking the folder plays it). The same
     * clearing as choosing the source: another app's hold and the player's own earlier pause end, so a pause pressed before the pick does not keep the
     * folder just chosen silent. The cable, the screen, the music hours and a call stay.
     */
    fun onFolderChosen(state: MusicPauseState): MusicPauseState = onSourceChosen(state)

    /**
     * Whether another app (or a call) holds the audio, as the THEME BED must obey it. The bed has no play button, so the player's own
     * pause, the unplugged cable and the screen are Your music's alone ([MusicPauseState.reason] would name one of them before the other
     * app, and the bed would then wait for a press that cannot come); the bed obeys only this. The music hours reach it separately.
     */
    fun heldByOtherApp(state: MusicPauseState): Boolean = state.otherApp != OtherAppHold.NONE || state.call

    /** The pause state as the theme bed's focus decision reads it: the holds that are Your music's alone are cleared ([heldByOtherApp]). */
    fun forBed(state: MusicPauseState): MusicPauseState = state.copy(user = false, headphones = false, screenOff = false)

    /**
     * What a reconcile does when the THEME BED wants to sound and holds no focus. The bed takes focus only when the player started it
     * ([deliberate]): then it asks, whoever is playing, so that the other app pauses itself. Any other start (cold start, the return to
     * Mimir, a rescan) never takes audio from a player: it yields to one that is audible and otherwise plays without focus, as the bed
     * always did, with the courtesy gate and [yieldsToStartedPlayer] watching for a newcomer. A game in front is the same exemption as
     * for Your music: nothing is requested.
     */
    fun bedFocusDecision(state: MusicPauseState, gameInFront: Boolean, deliberate: Boolean, otherPlayerPlaying: Boolean): FocusDecision = when {
        !mayRequestFocus(forBed(state), gameInFront) -> FocusDecision.NONE
        deliberate -> FocusDecision.REQUEST
        otherPlayerPlaying -> FocusDecision.YIELD_TO_OTHER
        else -> FocusDecision.NONE
    }

    /**
     * Mimir let go of the bed's focus (or holds none). A hold only a GAIN of that request could end ([OtherAppHold.TRANSIENT]), or one
     * that waits for a fresh request ([OtherAppHold.NEEDS_FOCUS]), can never end for the bed: it makes no request unless the player
     * starts it. It ends here; the courtesy gate still keeps the bed quiet while a call or another player is audible. A PERMANENT hold
     * has its own end ([otherPlayerQuiet]).
     */
    fun onBedFocusAbandoned(state: MusicPauseState): MusicPauseState =
        if (state.otherApp == OtherAppHold.TRANSIENT || state.otherApp == OtherAppHold.NEEDS_FOCUS) state.copy(otherApp = OtherAppHold.NONE) else state

    /**
     * The one observation behind the release of a PERMANENT hold ([ConfirmedPausedStreak]). Your music: the other player's media
     * session reads paused or stopped ([otherSessionStopped], which needs the notification-access grant); without it only the player's
     * play ends the hold. The THEME BED has no play button, so it also lets go when no other player has been audible ([strangerQuiet]:
     * the stranger-audio signal, settled, and not rising); it then re-enters WITHOUT taking focus (a bed start is never deliberate on
     * its own), so a player that merely buffered is not paused for good (Nova 13:38). This is the single place that rule lives.
     */
    fun otherPlayerQuiet(sessionAccessGranted: Boolean, session: OtherPlayerSnapshot?, bedIsSource: Boolean, strangerQuiet: Boolean): Boolean =
        otherSessionStopped(sessionAccessGranted, session) || (bedIsSource && strangerQuiet)

}

/**
 * The folder the player has just picked as Your music, until the plan plays it (a pick is a deliberate start, keyed
 * to that pick and not to a timer). A pick starts a scan, which can outlast any window; the start is marked when the plan actually flips to the
 * picked folder. A new pick replaces the old one, a plan for another folder leaves it waiting, and the flip to it uses it up. Any thread.
 */
internal class PickedFolder {
    private val pending = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** The player picked [tree]. */
    fun pick(tree: String) {
        pending.set(tree)
    }

    /** The plan is now the folder [tree]: true once, if that is the folder the player picked. */
    fun playedNow(tree: String): Boolean = pending.compareAndSet(tree, null)
}

/** How long a deliberate start stands, waiting for the music it started to be wanted (the preference flows hop threads first). */
internal const val DeliberateStartWindowMs = 5_000L

/**
 * "The player started the music on purpose", as a mark with a lifetime (handler thread only). A start is deliberate only for the
 * request it leads to, so the mark is spent by that request and, failing one, runs out: a volume raised an hour later is not a start.
 * The control marks it at the player's own acts (the panel's play, turning Music on, choosing the source) and reads it in reconcile.
 */
internal class DeliberateStart(private val windowMs: Long = DeliberateStartWindowMs) {
    private var atMs = Never

    /** The player just started the music, at [nowMs]. */
    fun mark(nowMs: Long) {
        atMs = nowMs
    }

    /** Whether a start was marked within the window ending at [nowMs]. */
    fun live(nowMs: Long): Boolean = atMs != Never && nowMs - atMs in 0..windowMs

    /** The request was made (or a game took the moment): the mark is used up. */
    fun spend() {
        atMs = Never
    }

    private companion object {
        const val Never = Long.MIN_VALUE
    }
}

/**
 * Which request the audio-focus callbacks belong to. Every request has its own generation and its own listener, and a request that is
 * let go retires its generation, so a callback that was already queued when the request was abandoned is ignored even after a fresh one
 * is granted: it would otherwise set a hold (a transient loss) that no GAIN of the new request could ever end. Handler thread only.
 */
internal class FocusRequestGeneration {
    private var current = 0L

    /** A new request is being made: its callbacks carry the returned generation. */
    fun issue(): Long {
        current += 1L
        return current
    }

    /** The request was let go: nothing carrying an earlier generation counts any more. */
    fun retire() {
        current += 1L
    }

    fun isCurrent(generation: Long): Boolean = generation == current
}

/** How long the other player's session must read paused or stopped, without a break, before Your music takes the audio back. */
internal const val OtherPlayerConfirmedPausedMs = 10_000L

/**
 * The "continuously for 10 s" rule as a pure streak : each observation of the other player says whether it is
 * stopped ([MusicPausePolicy.otherSessionStopped]); any observation that says it is not (it is playing or buffering again) throws
 * the streak away, so a blip that clears in a second is not a stop however many blips there are.
 */
internal class ConfirmedPausedStreak(private val requiredMs: Long = OtherPlayerConfirmedPausedMs) {
    private var sinceMs = NotStopped

    /** Records one observation at [atMs]; true once the stop has been continuous for the required time. */
    fun onSample(stopped: Boolean, atMs: Long): Boolean {
        if (!stopped) {
            sinceMs = NotStopped
            return false
        }
        if (sinceMs == NotStopped) sinceMs = atMs
        return atMs - sinceMs >= requiredMs
    }

    /** How long until [onSample] would confirm if the stop goes on, or null while no stop is under way. */
    fun remainingMs(atMs: Long): Long? =
        if (sinceMs == NotStopped) null else (sinceMs + requiredMs - atMs).coerceAtLeast(0L)

    fun reset() {
        sinceMs = NotStopped
    }

    private companion object {
        const val NotStopped = Long.MIN_VALUE
    }
}
