package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFavoriteMarkerTest {
    @Test
    fun markerUsesTheReadableStampedMannazEnvelope() {
        assertEquals(32, LibraryFavoriteMarkerWidthDp)
        assertEquals(24, LibraryFavoriteMarkerHeightDp)
        assertEquals(0.88f, LibraryFavoriteMarkerHiddenScale, 0f)
    }
}
