package com.gruning.mimir.rewrite.core.ui

import android.os.SystemClock
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * The focus ring's clock when focus lands: three times the house focus response, 420 ms.
 *
 * Ring and flare timing slowed product-wide through 2x, 2.5x and then 3x, matching onboarding.
 * Every
 * focus ring settles on this clock and every gleam runs [MimirFocusGlintMs]. Only the ring's own channels take it (its
 * colour, width or progress); the scale and dimming that ride beside it keep [MimirFocusRingDurationMs], and rings
 * that snap by design (CP07's game card, the hero tile, Home's stationary ring) still snap.
 *
 * A held D-pad keeps [MimirMotion.FocusMs] ([mimirFocusRingMs]), so the ring never trails a traverse.
 */
const val MimirFocusRingSettleMs: Int = MimirMotion.FocusMs * 3

/**
 * A move that lands sooner than this after the previous one is part of a traverse. It is the settle clock itself: a
 * move arriving before the previous ring would have finished is the one a slow ring trails. It clears the pad's first
 * repeat (`InputTuning.directionalRepeatDelayMs`, 260 ms), so a hold is fast from its first repeat, and its cruise
 * (120 ms) by far.
 */
const val MimirFocusTraversalStreamMs: Long = MimirFocusRingSettleMs.toLong()

/**
 * Whether focus is being walked by a held or rapidly tapped D-pad right now.
 *
 * Fed from the one place every accepted directional move passes (the app's input router, before the move is handled),
 * read by the rings when their target changes. Kept here rather than in core-input because core-ui has no dependency on
 * it, and the question the rings ask is only "is this a traverse".
 *
 * Judged when READ, from both stamps: the last two moves were close together AND the last is recent. A flag set when
 * the move is written would still say "held" when a touch or a programmatic focus change lands seconds after a hold
 * ends. Not snapshot state on purpose: a ring reads it in the recomposition its own focus change causes, and making it
 * observable would recompose every ring on screen at each flip.
 */
object MimirFocusTraversal {
    /** Uptime, the base of `KeyEvent.eventTime` and of the router's synthesized HAT ticks. Injectable for tests. */
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    @Volatile private var lastMoveMs: Long = NoMove

    @Volatile private var previousMoveMs: Long = NoMove

    /** An accepted directional move, stamped at the input's own [eventTimeMs] (uptime). */
    fun onDirectionalMove(eventTimeMs: Long) {
        previousMoveMs = lastMoveMs
        lastMoveMs = eventTimeMs
    }

    val streaming: Boolean
        get() {
            val last = lastMoveMs
            val previous = previousMoveMs
            // Before any move the clock is never read, so a JVM test that composes nothing needs no Android clock.
            if (last == NoMove || previous == NoMove) return false
            if (last - previous !in 0 until MimirFocusTraversalStreamMs) return false
            return clock() - last in 0 until MimirFocusTraversalStreamMs
        }

    /**
     * Whether an accepted move was pressed at or after [uptimeMs]. Not snapshot state: read it where a focus change
     * already recomposes (walk-32b, the Files listing's ring during an arrival).
     */
    fun movedSince(uptimeMs: Long): Boolean {
        val last = lastMoveMs
        return last != NoMove && last >= uptimeMs
    }

    fun reset() {
        lastMoveMs = NoMove
        previousMoveMs = NoMove
    }

    private const val NoMove = Long.MIN_VALUE / 2
}

/** The ring's clock for the focus change happening now: [MimirFocusRingSettleMs], or [MimirMotion.FocusMs] mid-traverse. */
fun mimirFocusRingMs(): Int = if (MimirFocusTraversal.streaming) MimirMotion.FocusMs else MimirFocusRingSettleMs

/** [mimirFocusRingMs] on the house focus ease. Read where the ring's target changes, so each change picks its clock. */
fun <T> mimirFocusRingSpec(): TweenSpec<T> = tween(mimirFocusRingMs(), easing = MimirFocusEasing)
