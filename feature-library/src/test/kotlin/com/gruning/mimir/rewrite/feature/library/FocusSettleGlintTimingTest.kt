package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.InputTuning
import com.gruning.mimir.rewrite.core.ui.MimirFocusSettleDelayMs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring gleam waits for a selection to SETTLE. The settle contract excludes any ring that regrows on each
 * step of a traverse, so the gleam has to be timed so that a traverse never produces one.
 */
class FocusSettleGlintTimingTest {

    @Test
    fun aHeldPadRekeysBeforeTheGleamCanStart() {
        val repeat = InputTuning().directionalRepeatMs
        assertTrue(
            "a held pad steps every $repeat ms; the gleam waits $MimirFocusSettleDelayMs ms, so it must be longer",
            MimirFocusSettleDelayMs > repeat,
        )
    }

    @Test
    fun theGleamLandsOnATileThatHasFinishedGlidingIntoTheSeat() {
        assertTrue(
            "the shelves glide into the seat over $CoverflowMotionDurationMs ms; the gleam must not start before",
            MimirFocusSettleDelayMs >= CoverflowMotionDurationMs,
        )
    }
}
