package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.library.GamelistBackdropMode
import com.gruning.mimir.rewrite.feature.settings.BackdropValueBackdrop
import com.gruning.mimir.rewrite.feature.settings.BackdropValueMedia
import com.gruning.mimir.rewrite.feature.settings.BackdropValueOff
import com.gruning.mimir.rewrite.feature.settings.backdropValueLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bifröst has exactly two user-facing states and Settings is their sole
 * authority. These pin the conservative migration the ruling specified, plus the exact copy.
 */
class BifrostSettingMigrationTest {

    @Test
    fun `stored OFF stays Off`() {
        assertEquals(GamelistBackdropMode.OFF, bifrostModeFromStored(GamelistBackdropMode.OFF.name))
    }

    @Test
    fun `every Bifrost-enabled state migrates to Cover plus Hero`() {
        assertEquals(
            GamelistBackdropMode.BIFROST,
            bifrostModeFromStored(GamelistBackdropMode.BIFROST.name),
        )
        // The retired "Backdrop only" — the one state that used to hide cover art.
        assertEquals(
            GamelistBackdropMode.BIFROST,
            bifrostModeFromStored(GamelistBackdropMode.BIFROST_BACKDROP.name),
        )
    }

    @Test
    fun `unsettable internal and unparseable values land on the default rather than a dead state`() {
        assertEquals(
            GamelistBackdropMode.BIFROST,
            bifrostModeFromStored(GamelistBackdropMode.AURORA.name),
        )
        assertEquals(GamelistBackdropMode.BIFROST, bifrostModeFromStored("not-a-mode"))
        assertEquals(GamelistBackdropMode.BIFROST, bifrostModeFromStored(null))
    }

    @Test
    fun `migration never yields a state the two-state picker cannot express`() {
        val stored = listOf(
            GamelistBackdropMode.OFF.name,
            GamelistBackdropMode.BIFROST.name,
            GamelistBackdropMode.BIFROST_BACKDROP.name,
            GamelistBackdropMode.AURORA.name,
            "junk",
            null,
        )

        val migrated = stored.map(::bifrostModeFromStored).toSet()

        assertEquals(setOf(GamelistBackdropMode.OFF, GamelistBackdropMode.BIFROST), migrated)
    }

    @Test
    fun `the two states carry the shared copy`() {
        assertEquals("Off", backdropValueLabel(BackdropValueOff))
        assertEquals("On", backdropValueLabel(BackdropValueMedia))
        // A legacy stored value still resolves to the surviving on-state's label, never a blank row.
        assertEquals("On", backdropValueLabel(BackdropValueBackdrop))
    }
}
