package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** OR-8 : a Favorite change in the Library says so. */
class LibraryFavoriteToastTest {

    @Test
    fun `adding and removing a Favorite each say what happened`() {
        assertEquals("Added to Favorites", libraryFavoriteToastText(nowFavorite = true))
        assertEquals("Removed from Favorites", libraryFavoriteToastText(nowFavorite = false))
    }
}
