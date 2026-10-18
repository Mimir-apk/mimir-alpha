package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two appearance-image pickers' place in the overlay stack (concept A, brief §B): BELOW `FOLDER_BROWSER`, so
 * Mimir's image browser covers them on + Add image and they come back when it closes; above the accent picker
 * and the dial like the theme gallery; and never outranking the Quick Launch panel.
 */
class AppearancePickerOverlayRankTest {

    @Test
    fun `the image browser covers both pickers`() {
        val withBackground = resolveMimirOverlayStack(MimirOverlaySnapshot(folderBrowser = true, backgroundPicker = true))
        assertEquals(MimirOverlayKind.FOLDER_BROWSER, withBackground.top)
        assertEquals(listOf(MimirOverlayKind.FOLDER_BROWSER, MimirOverlayKind.BACKGROUND_PICKER), withBackground.entries)
        val withMark = resolveMimirOverlayStack(MimirOverlaySnapshot(folderBrowser = true, mimirButtonPicker = true))
        assertEquals(listOf(MimirOverlayKind.FOLDER_BROWSER, MimirOverlayKind.MBUTTON_PICKER), withMark.entries)
    }

    @Test
    fun `a picker comes back on top once the browser closes`() {
        assertEquals(MimirOverlayKind.BACKGROUND_PICKER, resolveMimirOverlayStack(MimirOverlaySnapshot(backgroundPicker = true)).top)
        assertEquals(MimirOverlayKind.MBUTTON_PICKER, resolveMimirOverlayStack(MimirOverlaySnapshot(mimirButtonPicker = true)).top)
    }

    @Test
    fun `both sit with the Settings pickers, under the panel`() {
        val stack = resolveMimirOverlayStack(
            MimirOverlaySnapshot(homeQuickLaunch = true, themePicker = true, backgroundPicker = true, mimirButtonPicker = true, brightness = true),
        ).entries
        assertEquals(
            listOf(
                MimirOverlayKind.HOME_QUICK_LAUNCH,
                MimirOverlayKind.THEME_PICKER,
                MimirOverlayKind.BACKGROUND_PICKER,
                MimirOverlayKind.MBUTTON_PICKER,
                MimirOverlayKind.BRIGHTNESS,
            ),
            stack,
        )
        assertFalse(mimirOverlayOutranksQuickLaunch(MimirOverlayKind.BACKGROUND_PICKER))
        assertFalse(mimirOverlayOutranksQuickLaunch(MimirOverlayKind.MBUTTON_PICKER))
        assertFalse(mimirOverlayOutranksQuickLaunch(MimirOverlayKind.FOLDER_BROWSER))
        assertTrue(mimirOverlayStaysUnderQuickLaunch(MimirOverlayKind.FOLDER_BROWSER))
    }
}
