package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Departure-frame decision contract.
 *
 * The Folio's departure is made of RECORDED PIXELS, and the pixels that get recorded are the last frame
 * the Folio actually drew. `restoreRootEntry` pops the scope, nulls the session and begins the return in
 * ONE synchronous lambda, so without an extinguish step the recorded frame is the frame drawn before the
 * press — member ring lit — and the dissolving layer then drags a **second gold ring** across the Home
 * shelf for the whole hold, over the shelf's own ring, which [homeShelfLiveChromeLit] correctly relights
 * on that same frame.
 *
 * The law that decides it follows the existing focus contract: on a scope pop the **RING** channel is gone immediately and
 * the **STATE** channel is conserved (`core-input`'s `litFocusIn`/`renderedFocusIn`, and
 * [homeShelfLiveChromeLit] is the same law at the opening edge). These two functions are the two halves,
 * and they are deliberately not the same predicate — which is the whole point, and the thing a
 * straightforward implementation gets wrong by reusing one for both.
 */
class HomeTomeFolioDepartingChannelsTest {

    /** RING: out the instant the scope is gone, and out on the departing frame no matter what. */
    @Test
    fun theRingIsOutOnTheFrameThatGetsRecorded() {
        assertFalse(
            "the recorded frame must not carry a ring",
            homeTomeFolioRingLit(scopeOwnsFolio = true, departing = true),
        )
        assertFalse(homeTomeFolioRingLit(scopeOwnsFolio = false, departing = true))
    }

    /** RING: and nothing else changes. A live Folio that owns input still lights its member. */
    @Test
    fun aLiveFolioStillLightsItsMember() {
        assertTrue(homeTomeFolioRingLit(scopeOwnsFolio = true, departing = false))
        assertFalse(
            "a tile menu or launch card over the Folio takes the ring, as it already did",
            homeTomeFolioRingLit(scopeOwnsFolio = false, departing = false),
        )
    }

    /**
     * STATE: conserved across the departing frame, and this is why the two channels cannot share one
     * predicate. The recorded frame's job is to be *the frame the player was already looking at, minus the
     * ring* — so the footer's focused-member title, and a carried cell's selection, must still be there.
     * Dropping them would bake a content pop into the departure, which is a different defect wearing the
     * fix's name.
     */
    @Test
    fun everythingStatefulSurvivesTheDepartingFrame() {
        assertTrue(homeTomeFolioStateChannelLit(scopeOwnsFolio = false, departing = true))
        assertTrue(homeTomeFolioStateChannelLit(scopeOwnsFolio = true, departing = true))
        assertTrue(homeTomeFolioStateChannelLit(scopeOwnsFolio = true, departing = false))
    }

    /**
     * STATE: and it is still a scope check when nothing is departing. A Folio that has genuinely lost
     * input to another surface shows no focused member — unchanged behaviour, and the reason this is not
     * simply `true`.
     */
    @Test
    fun aFolioThatLostInputWithoutDepartingShowsNoFocusedMember() {
        assertFalse(homeTomeFolioStateChannelLit(scopeOwnsFolio = false, departing = false))
    }

    /**
     * The two channels differ on exactly one input, and that difference IS the two-channel law. Stated as
     * a property so a future "simplification" that collapses them fails here rather than on device.
     */
    @Test
    fun theTwoChannelsDisagreeOnlyOnTheDepartingFrame() {
        for (scopeOwnsFolio in listOf(true, false)) {
            for (departing in listOf(true, false)) {
                val ring = homeTomeFolioRingLit(scopeOwnsFolio, departing)
                val state = homeTomeFolioStateChannelLit(scopeOwnsFolio, departing)
                if (departing) {
                    assertFalse("ring is never lit while departing", ring)
                    assertTrue("state is always conserved while departing", state)
                } else {
                    assertTrue("off the departing frame the channels agree", ring == state)
                }
            }
        }
    }
}
