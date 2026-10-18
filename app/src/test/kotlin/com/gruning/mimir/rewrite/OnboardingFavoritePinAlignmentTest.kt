package com.gruning.mimir.rewrite

import androidx.compose.ui.Alignment
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compact review 2026-09-27 (ONB-3): the favourite pin covered the first title line of no-art books. */
class OnboardingFavoritePinAlignmentTest {

    @Test
    fun `art keeps the Tome library's corner`() {
        assertEquals(Alignment.TopEnd, onboardingFavoritePinAlignment(hasCover = true))
    }

    @Test
    fun `a no-art book wears the pin away from its title`() {
        assertEquals(Alignment.BottomEnd, onboardingFavoritePinAlignment(hasCover = false))
    }
}
