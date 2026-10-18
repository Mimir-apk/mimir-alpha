package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.model.library.GameRow

@JvmInline
value class LibraryGameLaunchOperation(val value: Long) {
    init {
        require(value > 0L) { "Game launch operation must be positive" }
    }
}

sealed interface LibraryGameSessionEvent {
    val operation: LibraryGameLaunchOperation
    val game: GameRow
    val returnTarget: LibraryLaunchReturnTarget

    data class Preparing(
        override val operation: LibraryGameLaunchOperation,
        override val game: GameRow,
        override val returnTarget: LibraryLaunchReturnTarget,
        val startedAtEpochMs: Long,
    ) : LibraryGameSessionEvent

    data class Settled(
        override val operation: LibraryGameLaunchOperation,
        override val game: GameRow,
        override val returnTarget: LibraryLaunchReturnTarget,
        val result: LaunchResult,
    ) : LibraryGameSessionEvent

    data class Failed(
        override val operation: LibraryGameLaunchOperation,
        override val game: GameRow,
        override val returnTarget: LibraryLaunchReturnTarget,
        val reason: String,
    ) : LibraryGameSessionEvent
}

fun interface LibraryGameSessionObserver {
    /**
     * Returns whether the semantic session authority admitted the event. Preparing is the launch
     * mutex; a rejected Preparing event must never dispatch or arm a return target.
     */
    fun onEvent(event: LibraryGameSessionEvent): Boolean

    /**
     * O15: when [onEvent] refused [event], whether the session in the way was still starting or already running, as the
     * session that refused this exact event read it. An observer that cannot tell answers
     * [GameLaunchSessionHold.STARTING], which keeps today's sentence.
     */
    fun holdAfterRefusal(event: LibraryGameSessionEvent.Preparing): GameLaunchSessionHold =
        GameLaunchSessionHold.STARTING

    /**
     * R6-P0-02 follow-up ("release at the press", policy): after [onEvent] refuses [event], what a
     * stale parked lease did about it. [ParkedLeaseReleaseOutcome.Released] means the caller should retry
     * the SAME admission once, in the same press, instead of leaving the refusal to stand -- a proper Close
     * Game reached through Android Recents (task gone, the emulator's own process still cached) must not
     * leave every later PLAY silently dead. Deliberately a small feature-library-owned sealed result, not a
     * `Boolean` -- [ParkedLeaseReleaseOutcome.StillParked] carries the shared refusal copy from
     * `:app` (which owns the title/lease formatting) without feature-library depending on `:app` for it, so
     * `LibraryViewModel` can publish it as a one-shot message and `LibraryScreen` can paint it exactly like
     * every other Main surface's refusal toast -- no site here writes its own words.
     */
    suspend fun releaseParkedLeaseAfterRefusal(event: LibraryGameSessionEvent.Preparing): ParkedLeaseReleaseOutcome =
        ParkedLeaseReleaseOutcome.NotParked
}

/**
 * R6-P0-02 review fix: [LibraryGameSessionObserver.releaseParkedLeaseAfterRefusal]'s result. [NotParked] is an
 * ordinary refusal -- nothing was parked, so today's O15 sentence (already shown by the caller) is the whole
 * story and no additional message is ever produced for it.
 */
sealed interface ParkedLeaseReleaseOutcome {
    /** The stale lease was released; the caller retries the same admission once, in the same press. */
    data object Released : ParkedLeaseReleaseOutcome

    /** A live parked lease is still held. [message] is the shared refusal copy to show once. */
    data class StillParked(val message: String) : ParkedLeaseReleaseOutcome

    /** Nothing was parked -- an ordinary refusal, held by something else. No message. */
    data object NotParked : ParkedLeaseReleaseOutcome
}

internal val NoOpLibraryGameSessionObserver = LibraryGameSessionObserver { true }
