package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How solid a resting coverflow tile paints.
 *
 * The value chosen was not a fresh tuning pick — it is Home's own resting alpha, so the two shelves agree
 * about how present an unfocused tile is. That is the part worth pinning: an equality, not a literal.
 */
class CoverflowGhostTileAlphaTest {

    @Test
    fun theGhostTileAlphaIsHomesOwnRestingValueAndNotACopyOfIt() {
        // Pinned as an EQUALITY so a later edit to either shelf cannot quietly separate them again. A test
        // that restated 0.82 here would keep passing while the two drifted apart, which is the failure it
        // exists to prevent.
        assertEquals(HomeShelfRestingAlpha, CoverflowMotionRestAlpha, 0f)
    }

    @Test
    fun itIsBolderThanTheValueHeCalledTooTransparent() {
        assertTrue("0.48 is the prior rest-alpha value", CoverflowMotionRestAlpha > 0.48f)
    }

    @Test
    fun aRestingTileStillReadsAsSubordinateToTheHero() {
        // The other half of the alpha constraint: bolder, not equal. A resting neighbour that paints at full strength
        // would make the focus cue carry entirely on the ring, which is the opposite complaint.
        assertTrue(CoverflowMotionRestAlpha < 1f)
        val rest = coverflowBasePose(centered = false, bookHero = true)
        val hero = coverflowBasePose(centered = true, bookHero = true)
        assertEquals(CoverflowMotionRestAlpha, rest.alpha, 0f)
        assertEquals(1f, hero.alpha, 0f)
    }
}
