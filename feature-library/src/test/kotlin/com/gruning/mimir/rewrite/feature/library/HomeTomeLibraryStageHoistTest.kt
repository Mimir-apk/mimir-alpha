package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy 2026-08-27, *"one backdrop under both surfaces"*, settling how the Tome Library's backdrop
 * relates to the Home shelf's.
 *
 * The Library used to mount its OWN full-screen host over the shelf's, so crossing that boundary mounted
 * and unmounted an entire backdrop. Measured on the Odin: 30.6 of frame difference in a single frame going
 * in, 28.8 coming back, against 3% first-frame share for a genuine crossfade in the same recording.
 *
 * These two predicates are what make the Library drive the shelf's already-mounted stage instead. Both
 * conditions were deliberately absent before — the stage excluded `tomeLibraryActive` precisely because a
 * second host owned that surface — so this pins the reversal, not an oversight.
 */
class HomeTomeLibraryStageHoistTest {

    @Test
    fun `the Library alone mounts the stage`() {
        assertTrue(
            "with no second host to paint it, the Library is a third condition this one stage must cover",
            homeShelfTomeStageMounted(folioActive = false, tomeSelected = false, tomeLibraryActive = true),
        )
        // The two original conditions are untouched.
        assertTrue(homeShelfTomeStageMounted(folioActive = true, tomeSelected = false))
        assertTrue(homeShelfTomeStageMounted(folioActive = false, tomeSelected = true))
        assertFalse(
            "a plain shelf with no Tome surface anywhere still mounts no stage",
            homeShelfTomeStageMounted(folioActive = false, tomeSelected = false, tomeLibraryActive = false),
        )
    }

    @Test
    fun `the Library's art wins while the Library is the surface on top`() {
        assertEquals(
            "what the player is looking at is the tome under their focus, not the shelf's held lead art",
            "library",
            homeShelfTomeStageArt(
                folioStageHandedOff = true,
                folioArt = "folio",
                tomeLeadArt = "lead",
                libraryArt = "library",
            ),
        )
    }

    @Test
    fun `without the Library the existing priority is exactly as it was`() {
        assertEquals(
            "lead",
            homeShelfTomeStageArt(folioStageHandedOff = true, folioArt = "folio", tomeLeadArt = "lead"),
        )
        assertEquals(
            "folio",
            homeShelfTomeStageArt(folioStageHandedOff = true, folioArt = "folio", tomeLeadArt = null),
        )
        assertNull(
            "a folio that has not handed off still contributes nothing",
            homeShelfTomeStageArt(folioStageHandedOff = false, folioArt = "folio", tomeLeadArt = null),
        )
    }

    @Test
    fun `an unresolved Library preview does not blank the stage`() {
        // The Library can be up before its focused tome has resolved a preview. Returning null here would
        // let the caller's `stillUri` fall through and UNMOUNT the host — the very cut this closes — so the
        // shelf's own still has to remain reachable as a fallback.
        assertNull(
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = null,
                libraryArt = null,
            ),
        )
        assertTrue(
            "and the stage stays MOUNTED across that gap, which is what makes the fallback reachable",
            homeShelfTomeStageMounted(folioActive = false, tomeSelected = false, tomeLibraryActive = true),
        )
    }
}
