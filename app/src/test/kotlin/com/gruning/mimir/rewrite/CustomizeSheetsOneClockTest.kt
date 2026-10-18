package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motion audit 2026-09-28, batch F: the Customize media sub-sheets ride the sheet tier, one OverlayMs clock each. */
class CustomizeSheetsOneClockTest {

    @Test
    fun `the filter dropdown and the keyboard are sheets, nothing there fades on FocusMs any more`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt"),
        ).first(File::isFile).readText()
        assertEquals("the dropdown, in and out", 2, Regex("fromTop = true").findAll(source).count())
        assertTrue(source.contains("enter = mimirSheetEnter(mimirReducedMotion()),"))
        assertTrue(source.contains("exit = mimirSheetExit(mimirReducedMotion()),"))
        assertTrue("the scrim on the sheet's clock", source.contains("fadeIn(mimirSheetSpec(mimirReducedMotion()))"))
        assertFalse(source.contains("slideInVertically("))
        assertFalse(source.contains("tween(MimirMotion.FocusMs)"))
        assertFalse(source.contains("tween(MimirMotion.SceneMs)"))
    }
}
