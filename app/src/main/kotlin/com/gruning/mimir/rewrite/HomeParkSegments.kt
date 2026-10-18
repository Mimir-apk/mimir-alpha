package com.gruning.mimir.rewrite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What the Bottom's NOW PLAYING needs to know about a game that Home has parked and Resume brought back, which the session's
 * dispatch time alone cannot say. One session is several measured segments (dispatch to the HOME press, then each Resume to the
 * next press), and the parked hours between them are not played time.
 *
 * - [playedMs]: the segments already finished, press by press. The clock shows this plus the running segment.
 * - [segmentStartedAtElapsedMs]: when the running segment began, or null while the game is parked (the clock holds still).
 * - [totalBeforeMs]: the store's total for the game as it stood before this session's first segment was finished. The store adds
 *   each finished segment to that total at once, so read live it would count the same minutes the clock shows; null when it could
 *   not be read, and then the live total is used as before.
 *
 * A record names its [operation]; a reader for any other session ignores it, so a finished session needs no cleaning up.
 */
internal data class HomeParkSegments(
    val operation: MimirGameSessionOperation,
    val playedMs: Long,
    val segmentStartedAtElapsedMs: Long?,
    val totalBeforeMs: Long?,
)

/** The record, only if it is for [operation]'s session: a finished session's record is another session's no-record. */
internal fun HomeParkSegments?.forSession(operation: MimirGameSessionOperation): HomeParkSegments? =
    this?.takeIf { it.operation == operation }

/** The one record, as a flow the Bottom reads. Plain coroutine state, not Compose state: a JVM test may write it. */
internal class HomeParkSegmentLedger {
    private val flow = MutableStateFlow<HomeParkSegments?>(null)
    val state: StateFlow<HomeParkSegments?> get() = flow

    /**
     * The HOME press at [pressedAtElapsedMs]: the running segment ends. Its start is the one [resumed] recorded, or
     * [sessionStartedAtElapsedMs] for the first segment. [totalBeforeMs] is taken only by the first park of a session.
     */
    fun parked(
        operation: MimirGameSessionOperation,
        sessionStartedAtElapsedMs: Long?,
        pressedAtElapsedMs: Long,
        totalBeforeMs: Long?,
    ) = flow.update { current ->
        val same = current?.takeIf { it.operation == operation }
        val startedAt = if (same != null) same.segmentStartedAtElapsedMs else sessionStartedAtElapsedMs
        val ended = startedAt?.let { (pressedAtElapsedMs - it).coerceAtLeast(0L) } ?: 0L
        HomeParkSegments(
            operation = operation,
            playedMs = (same?.playedMs ?: 0L) + ended,
            segmentStartedAtElapsedMs = null,
            totalBeforeMs = if (same != null) same.totalBeforeMs else totalBeforeMs,
        )
    }

    /** Resume fronted the game at [atElapsedMs]: a segment is running again. A session that was never parked is left alone. */
    fun resumed(operation: MimirGameSessionOperation, atElapsedMs: Long) = flow.update { current ->
        current?.takeIf { it.operation == operation }?.copy(segmentStartedAtElapsedMs = atElapsedMs) ?: current
    }
}

/** The process's record: written by [MainGameHomePark] at the HOME press and at Resume, read by NOW PLAYING. */
internal val homeParkSegments = HomeParkSegmentLedger()

/**
 * The session's time on the clock: the finished segments plus the running one, and only the finished ones while the game is parked.
 * With no record for the session it is the time since dispatch, as it has always been. Null when the session recorded no
 * dispatch time (nothing is invented) or the clock moved back.
 */
internal fun nowPlayingSessionElapsed(startedAtElapsedMs: Long?, nowElapsedMs: Long, segments: HomeParkSegments?): Long? {
    if (segments == null) return companionActivitySessionElapsed(startedAtElapsedMs, nowElapsedMs)
    if (startedAtElapsedMs == null) return null
    val running = segments.segmentStartedAtElapsedMs ?: return segments.playedMs
    return companionActivitySessionElapsed(running, nowElapsedMs)?.let { segments.playedMs + it }
}

/** The total "before this session": the figure taken before the session's first segment was finished, else the store's live total. */
internal fun nowPlayingTotalBeforeMs(liveTotalMs: Long?, segments: HomeParkSegments?): Long? =
    segments?.totalBeforeMs ?: liveTotalMs
