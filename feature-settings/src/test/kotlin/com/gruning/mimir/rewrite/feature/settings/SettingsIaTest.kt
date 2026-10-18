package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the release-alpha Settings architecture: seven sections with MUSIC after APPEARANCE.
 */
class SettingsIaTest {

    @Test
    fun railIsExactlyTheRatifiedOrder() {
        assertEquals(
            listOf(
                SettingsCategory.PROFILE,
                SettingsCategory.LIBRARY,
                SettingsCategory.EMULATORS,
                SettingsCategory.APPEARANCE,
                SettingsCategory.MUSIC,
                SettingsCategory.CONTROLS,
                SettingsCategory.ABOUT,
            ),
            SettingsCategory.rail,
        )
    }

    @Test
    fun everyRailMarkIsUnique() {
        val marks = SettingsCategory.rail.map { it.mark }
        assertEquals("a rail whose entries share a glyph has stopped distinguishing them", marks.size, marks.toSet().size)
    }

    @Test
    fun railEntriesKeepConsoleSizedTargets() {
        assertEquals(48, SettingsRailEntryHeightDp)
    }

    @Test
    fun everyCategoryLandsOnAnActionableDetailTarget() {
        val expected = mapOf(
            SettingsCategory.PROFILE to "profile/name",
            SettingsCategory.LIBRARY to "library/add",
            SettingsCategory.EMULATORS to "emulators/assign/0",
            SettingsCategory.APPEARANCE to "appearance/theme",
            SettingsCategory.MUSIC to "music/player/enabled",
            SettingsCategory.CONTROLS to "controls/utility",
            SettingsCategory.ABOUT to "about/credits/gametdb",
        )

        expected.forEach { (category, rowId) ->
            assertEquals(rowId, settingsFirstDetailRowId(category))
        }
    }

    @Test
    fun appearanceBlurbKeepsArtworkServicesAndKeepsBackgroundAwayFromBackdrops() {
        val blurb = SettingsCategory.APPEARANCE.blurb
        assertEquals("the rail entry and the pane header say the same thing", AppearanceBlurb, blurb)
        assertTrue("artwork services are still in Appearance", "artwork services" in blurb)
        assertTrue("the player's background is named", "background" in blurb)
        assertTrue("and the Mimir button", "the Mimir button" in blurb)
        assertTrue("a game's hero art is a GAME backdrop", "game backdrops" in blurb)
        // R2: "Background" (the player's image) and "backdrops" (a game's hero) must never read as one list.
        val sentences = blurb.split(". ")
        val withBackground = sentences.filter { "background" in it }
        val withBackdrops = sentences.filter { "backdrop" in it }
        assertEquals(1, withBackground.size)
        assertEquals(1, withBackdrops.size)
        assertTrue("in different sentences", withBackground.single() != withBackdrops.single())
    }

    @Test
    fun everyCategoryHasLabelAndBlurb() {
        SettingsCategory.entries.forEach {
            assert(it.label.isNotBlank()) { "$it missing label" }
            assert(it.blurb.isNotBlank()) { "$it missing blurb" }
        }
    }
}
