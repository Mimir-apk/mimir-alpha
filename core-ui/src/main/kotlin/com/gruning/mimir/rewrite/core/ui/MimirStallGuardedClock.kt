package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/*
 * A fade that survives a long frame.
 *
 * Thor drive 3 (combined45, 2026-10-15): the Settings pickers' arrival is one OverlayMs cross-swap with no lead, and on the device the Theme gallery opened in about 30 ms, the
 * Background picker in ONE frame (a hard cut) and the Mimir-button picker in about 50 ms, while the same three CLOSED in 190-250 ms. Every Compose animation reads the Choreographer's
 * frame time, so a frame that takes 110 ms to compose, lay out or draw (the first draw of a page: the Theme gallery's nine live scenes record for 213 ms on the UI thread, the Background
 * picker's stage and shelf pieces for 85 ms, and a cold process interprets all of it) moves the clock 110 ms in one step. The fade's first 110 ms then pass with nothing on screen, and the
 * next frame lands at 60% of the curve or past the end. The page tier hid this behind its 130 ms lead and a 540 ms ramp; a 180 ms swap with no lead has nothing to absorb it. A paused-clock
 * test cannot see it (its frames are always 16 ms).
 *
 * [withMimirStallGuard] runs an animation on a frame clock that moves by at most [capMs] a frame: a long frame advances the fade by a frame and a bit, not by its length, so the swap is always
 * drawn across the frames it has, a stutter on a hitch and never a cut. It scales nothing on a healthy device (the frames are 8 or 17 ms, under the cap).
 */

/** The longest a single frame may advance a guarded arrival: a 60 Hz frame (16.7 ms) and its jitter, so a healthy frame is never slowed and a stalled one moves the fade by a small, visible step (at most about a fifth of it). */
const val MimirArrivalFrameCapMs: Int = 20

/** The frame clock of [withMimirStallGuard]: the base clock's frames, with the time between two of them clamped to [capNanos]. One instance per run, so each arrival starts from its own first frame. */
internal class MimirStallGuardedFrameClock(
    private val base: MonotonicFrameClock,
    private val capNanos: Long,
) : MonotonicFrameClock {
    private var lastReal = Long.MIN_VALUE
    private var virtual = 0L

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = base.withFrameNanos { real ->
        virtual = if (lastReal == Long.MIN_VALUE) real else virtual + (real - lastReal).coerceIn(0L, capNanos)
        lastReal = real
        onFrame(virtual)
    }
}

/**
 * Runs [block] (an `animateTo`, typically) on a frame clock that advances by at most [capMs] per frame. [capMs] of 0 or less runs it on the ambient clock unchanged, which is what every arrival
 * that has not been moved onto the swap clock keeps; so does a context with no frame clock.
 */
suspend fun <R> withMimirStallGuard(capMs: Int, block: suspend () -> R): R {
    if (capMs <= 0) return block()
    val base = coroutineContext[MonotonicFrameClock] ?: return block()
    return withContext(MimirStallGuardedFrameClock(base, capMs * 1_000_000L)) { block() }
}
