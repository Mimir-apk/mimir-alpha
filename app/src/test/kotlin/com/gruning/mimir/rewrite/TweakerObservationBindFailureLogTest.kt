package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-5 B6, Thor, 2026-09-11 18:36:30: one failed Tweaker observation bind cleared the display-rate cache and
 * APPLY refused on it, and nothing had logged why the bind failed. The Utility retries that observation every
 * second while it is open, so the line is gated to one per transition from binding to failing.
 */
class TweakerObservationBindFailureLogTest {
    private val eligible = TweakerPairIdentity(TweakerPairState.ELIGIBLE)
    private val absent = TweakerPairIdentity(TweakerPairState.ABSENT)

    @Test
    fun `speaks once per transition from binding to failing with mayBind and the failure type`() {
        val log = TweakerObservationBindFailureLog()

        assertNull("a bind that answers says nothing", log.lineFor(bound = true, pairIdentity = eligible))
        assertEquals(
            "observation bind failed mayBind=true failure=bind_returned_false pair=ELIGIBLE",
            log.lineFor(bound = false, pairIdentity = eligible),
        )
        assertNull(
            "the next 1 s retry of the same failure stays quiet",
            log.lineFor(bound = false, pairIdentity = eligible),
        )
        assertNull(log.lineFor(bound = false, pairIdentity = eligible))
        assertNull(
            "recovery re-arms the gate without a line of its own",
            log.lineFor(bound = true, pairIdentity = eligible),
        )
        assertEquals(
            "observation bind failed mayBind=false failure=pair_refused pair=ABSENT",
            log.lineFor(bound = false, pairIdentity = absent),
        )
    }
}
