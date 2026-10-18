package com.gruning.mimir.rewrite.core.audio

/**
 * The side effects a loop seam performs, named so their ORDER can be asserted off-device.
 *
 * The engine's own implementation touches `MediaPlayer`, a `HandlerThread` and [MimirMediaBed], none
 * of which exist on the JVM, so the decision was previously unreachable by any test. Naming the
 * effects — rather than the players — is what moves the ordering into reach without pretending a
 * gap can be measured off hardware.
 */
internal interface FrontendMusicSeamOperations {
    /**
     * Declare the promoted deck to [MimirMediaBed].
     *
     * Must precede [retireFinished]: the bed correlates each token to a live playback
     * configuration, and a window with the finished deck already gone and the promoted one not yet
     * declared is what the courtesy gate reads as `relevant=2 owned=1` — it then blocks on our own
     * seam and mutes the bed.
     */
    fun declarePromoted()

    /** Stop, release and undeclare the finished deck, and make the promoted one current. */
    fun retireFinished()

    /** Start the already-prepared deck. `false` if the player refused. */
    fun startPromoted(): Boolean

    /** Prepare — but do not attach — the deck for the seam after this one. */
    fun chainNextDeck()

    /** Record a completed hand-over. */
    fun noteSeam()

    /** Say out loud that the prepared deck would not start. Never silent. */
    fun reportStartFailure()

    /** Discard the deck that would not start, so nothing is left half-promoted. */
    fun discardPromoted()

    /**
     * Leave `playing` false and re-enter through the ordinary volume path.
     *
     * This is what keeps a failed seam from being terminal: the bed goes quiet for one entrance
     * rather than staying dead until the process restarts.
     */
    fun reenterFromSilence()
}

/**
 * The loop seam's decision, extracted from the engine so it can be tested on the JVM.
 *
 * **This is the explicit hand-over that replaced `setNextMediaPlayer` (commit `b32d976d`).** The
 * framework's next-player path left a NuPlayer renderer inside `mediaserver` spinning at ~600 kHz
 * forever from the first seam — about two-thirds of all CPU the device burned at idle. The
 * replacement starts a deck that was created and prepared a whole piece ahead, so the seam is a
 * `start()` on a buffered player rather than the `seekTo(0)` the two-deck chain exists to avoid.
 *
 * What this object fixes is only that the ORDER and the FAILURE ARMS are now provable. The size of
 * the audible gap at the seam is a wall-clock property of the hardware and remains device-only —
 * see `MediaPlayerLoopSeekGapDeviceTest.explicit_start_handover_is_measured_on_the_same_probe`.
 */
internal object FrontendMusicSeam {

    /**
     * Run one seam.
     *
     * @param playing whether the engine believes it is sounding; a completion after teardown is not
     *   our seam.
     * @param finishedIsCurrent whether the deck reporting completion is the one on air. A deck that
     *   was already retired can still deliver a late callback, and acting on it would promote a
     *   second time and strand the deck actually playing.
     * @param hasUpcoming whether a prepared deck exists to promote.
     */
    fun handOver(
        playing: Boolean,
        finishedIsCurrent: Boolean,
        hasUpcoming: Boolean,
        operations: FrontendMusicSeamOperations,
    ) {
        if (!playing) return
        if (!finishedIsCurrent) return

        if (!hasUpcoming) {
            // The hand-over had nothing to hand to. Fall back to a cold start rather than silence.
            operations.retireFinished()
            operations.reenterFromSilence()
            return
        }

        operations.declarePromoted()
        operations.retireFinished()
        if (!operations.startPromoted()) {
            operations.reportStartFailure()
            operations.discardPromoted()
            operations.reenterFromSilence()
            return
        }
        operations.chainNextDeck()
        operations.noteSeam()
    }
}
