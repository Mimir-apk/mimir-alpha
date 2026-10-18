package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The names a shelf wears. Home's are what its pins and tests have always read, so the default changes nothing there; any other
 * prefix derives the same set under its own name.
 */
class GameShelfIdsTest {

    @Test
    fun `Home's ids are the tags, keys and labels Home has always worn`() {
        val home = GameShelfIds.Home
        assertEquals(HomeShelfTileTestTagPrefix, home.tilePrefix)
        assertEquals(HomeShelfSelectedSlotTestTag, home.selectedSlotTag)
        assertEquals(HomeShelfStationaryRingTestTag, home.ringTag)
        assertEquals(HomeShelfLeftContinuationTestTag, home.leftRuneTag)
        assertEquals(HomeShelfRightContinuationTestTag, home.rightRuneTag)
        assertEquals("home-emphasis-seat", home.emphasisSeatLabel)
        assertEquals("home-left-continuation", home.leftRuneLabel)
        assertEquals("home-right-continuation", home.rightRuneLabel)
        assertSame(HomeShelfPresentationAlphaSemantics, home.presentationAlpha)
        assertSame(HomeShelfPresentationScaleSemantics, home.presentationScale)
        assertSame(HomeShelfVisualSlotSemantics, home.visualSlot)
    }

    @Test
    fun `the prefix home derives the same strings as Home's ids, with keys of its own`() {
        val derived = GameShelfIds("home")
        val home = GameShelfIds.Home
        assertEquals(home.tilePrefix, derived.tilePrefix)
        assertEquals(home.selectedSlotTag, derived.selectedSlotTag)
        assertEquals(home.ringTag, derived.ringTag)
        assertEquals(home.leftRuneTag, derived.leftRuneTag)
        assertEquals(home.rightRuneTag, derived.rightRuneTag)
        assertEquals(home.emphasisSeatLabel, derived.emphasisSeatLabel)
        assertEquals(home.leftRuneLabel, derived.leftRuneLabel)
        assertEquals(home.rightRuneLabel, derived.rightRuneLabel)
        assertEquals(home.presentationAlpha.name, derived.presentationAlpha.name)
        assertEquals(home.presentationScale.name, derived.presentationScale.name)
        assertEquals(home.visualSlot.name, derived.visualSlot.name)
        // A semantics key is matched by identity, so a prefix built twice is two sets of keys: a surface builds its ids once.
        assertNotSame(home.presentationAlpha, derived.presentationAlpha)
    }

    @Test
    fun `another prefix wears its own name on every tag, key and label`() {
        val library = GameShelfIds("library")
        assertEquals("library_shelf_tile_", library.tilePrefix)
        assertEquals("library_shelf_selected_slot", library.selectedSlotTag)
        assertEquals("library_shelf_stationary_ring", library.ringTag)
        assertEquals("library_shelf_continuation_left", library.leftRuneTag)
        assertEquals("library_shelf_continuation_right", library.rightRuneTag)
        assertEquals("library-emphasis-seat", library.emphasisSeatLabel)
        assertEquals("library-left-continuation", library.leftRuneLabel)
        assertEquals("library-right-continuation", library.rightRuneLabel)
        assertEquals("LibraryShelfPresentationAlpha", library.presentationAlpha.name)
        assertNotEquals(GameShelfIds.Home.ringTag, library.ringTag)
        assertNotSame(GameShelfIds.Home.presentationAlpha, library.presentationAlpha)
    }
}
