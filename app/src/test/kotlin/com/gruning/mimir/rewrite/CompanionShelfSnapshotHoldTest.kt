package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.feature.library.HomeShelfOpenLibraryKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression, reproduced on Thor: with an empty Home shelf, focus rests on the empty-state OPEN LIBRARY
 * affordance (`focusKey=home/empty/open-library`, `focusScope=home/shelf`). That key is in HomeShelfScope
 * but is not a row, so `homeShelfRowForKey` returned null, the companion inferred "snapshot in flight",
 * and held `Loading…` forever — with fabricated metadata (LAST PLAYED / TIMES PLAYED / OPENS WITH) for a
 * game that does not exist.
 */
class CompanionShelfSnapshotHoldTest {

    @Test
    fun `the empty-shelf affordance never justifies a hold`() {
        assertFalse(
            "OPEN LIBRARY is not a row; no snapshot will ever arrive for it",
            companionKeyCanBecomeShelfRow(HomeShelfOpenLibraryKey),
        )
    }

    @Test
    fun `a real shelf row key still holds`() {
        assertTrue(companionKeyCanBecomeShelfRow(FocusKey("home/shelf/item/abc123")))
        assertTrue(companionKeyCanBecomeShelfRow(FocusKey("home/tome/seat/t1/0")))
    }

    @Test
    fun `the guard is keyed on identity, not on an empty row list`() {
        // Deliberate: an empty row list is ALSO the ordinary cold-start state, where a real row is about
        // to land. Refusing the hold there would reintroduce the identity flash the hold prevents, so the
        // discriminator must be the focused key rather than a row count.
        assertTrue(
            "a row key must hold even when the shelf has not populated yet",
            companionKeyCanBecomeShelfRow(FocusKey("home/shelf/item/not-yet-loaded")),
        )
    }
}
