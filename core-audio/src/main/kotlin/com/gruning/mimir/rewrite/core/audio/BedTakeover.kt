package com.gruning.mimir.rewrite.core.audio

/**
 * When the player starts Mimir's theme music on purpose (turns Music on, chooses the source), it
 * plays even over an app that holds no audio focus, such as a game left running behind Mimir. The courtesy gate ([MimirMediaBed.environmentBlocked])
 * reads that app's stream as another app's music and would keep the bed at zero; the player's own act outranks it.
 *
 * The waiver is the control's word ([MimirMusicControl.bedTakeover]), kept by [next] at every reconcile:
 *
 *  - It is raised when the bed is wanted while a deliberate start is live ([DeliberateStart.live], the five seconds the preference flows
 *    need to catch up with the act).
 *  - It stands for as long as the bed stays wanted, in front, with no game in front and no other app holding the audio. The bed being unwanted
 *    (Music off, level zero, the music hours) ends it, and so does Mimir leaving the front, a game opening, or another app taking the audio
 *    (a focus loss, or a newcomer the control sees start playing): that app started after the player, so it wins, and the waiver does not come
 *    back inside the window of the start it outlived (the control spends the mark when it sees the hold).
 *  - A call is never waived. A call reaches the bed only through the media bed's audio mode ([MimirMediaBed.callInterrupted]), and the policy
 *    checks that before it looks at the waiver ([FrontendMusicPolicy.targetVolume]).
 *  - Passive starts never raise it: a cold start, the return to Mimir, screen-on and a rescan still wait behind the gate and yield to real music.
 */
internal object BedTakeover {
    /**
     * The waiver after one look: [current] is what it was, [bedWanted] whether the engine has the bed on its way to sound, [deliberateLive] whether
     * a player's start is live, [gameInFront] and [mimirInFront] where the player is, [otherAppHeld] whether another app (or a call) holds the audio.
     */
    fun next(
        current: Boolean,
        bedWanted: Boolean,
        deliberateLive: Boolean,
        gameInFront: Boolean = false,
        mimirInFront: Boolean = true,
        otherAppHeld: Boolean = false,
    ): Boolean = when {
        // The player is elsewhere, or somebody else has the audio: the start no longer stands, whatever is left of its window.
        !mimirInFront || gameInFront || otherAppHeld -> false
        bedWanted && deliberateLive -> true
        // Not wanted and no start waiting for the flows that carry it: the take-over is over.
        !bedWanted && !deliberateLive -> false
        else -> current
    }
}
