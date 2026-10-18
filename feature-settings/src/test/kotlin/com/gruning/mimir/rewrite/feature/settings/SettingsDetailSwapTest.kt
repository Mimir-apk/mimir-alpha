package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings detail pane glides in. Its previous two-layer handoff looked jumpy on device.
 */
class SettingsDetailSwapTest {

    @Test
    fun `moving down the rail brings the new pane up from below and moving up mirrors it`() {
        assertEquals(1, settingsDetailSwapDirection(SettingsCategory.PROFILE, SettingsCategory.LIBRARY))
        assertEquals(1, settingsDetailSwapDirection(SettingsCategory.LIBRARY, SettingsCategory.ABOUT))
        assertEquals(-1, settingsDetailSwapDirection(SettingsCategory.ABOUT, SettingsCategory.CONTROLS))
        assertEquals(-1, settingsDetailSwapDirection(SettingsCategory.APPEARANCE, SettingsCategory.PROFILE))
        assertEquals("a first composition travels nowhere", 0, settingsDetailSwapDirection(null, SettingsCategory.PROFILE))
        assertEquals(0, settingsDetailSwapDirection(SettingsCategory.APPEARANCE, SettingsCategory.APPEARANCE))
    }

    @Test
    fun `a first pane and reduced motion start settled, every other change glides from the start`() {
        assertEquals(1f, settingsDetailGlideStart(first = true, reducedMotion = false), 0f)
        assertEquals(1f, settingsDetailGlideStart(first = false, reducedMotion = true), 0f)
        assertEquals(0f, settingsDetailGlideStart(first = false, reducedMotion = false), 0f)
    }

    @Test
    fun `the glide is subtle - short, a few dp, and never from blank`() {
        assertTrue(SettingsDetailGlideMs in 200..320)
        assertTrue(SettingsDetailGlideTravelDp <= 8)
        assertTrue("a burst never shows a blank pane", SettingsDetailGlideStartAlpha >= 0.3f)
    }

    @Test
    fun `one pane glides in alone - no departing layer and no input gate`() {
        val source = listOf(
            File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SettingsHome.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SettingsHome.kt"),
        ).first(File::isFile).readText()
        assertFalse(source.contains("MimirPageHandoff("))
        assertFalse(source.contains("MimirTransitionInputGate.hold("))
        assertTrue(source.contains("rememberSettingsDetailGlide(category, mimirReducedMotion())"))
    }
}
