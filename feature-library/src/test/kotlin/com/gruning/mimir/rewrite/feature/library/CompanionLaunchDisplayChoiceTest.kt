package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionLaunchDisplayChoiceTest {
    @Test
    fun labelsPinTheChooserActionStrings() {
        assertEquals("Main screen", companionLaunchDisplayChoiceLabel(CompanionLaunchDisplayChoice.MAIN))
        assertEquals("Bottom screen", companionLaunchDisplayChoiceLabel(CompanionLaunchDisplayChoice.BOTTOM))
        assertEquals("Ask next time", companionLaunchDisplayChoiceLabel(CompanionLaunchDisplayChoice.ASK))
    }

    @Test
    fun glyphFillMarksTheChosenDsScreen() {
        assertEquals(true to false, companionLaunchDisplayGlyphFill(CompanionLaunchDisplayChoice.MAIN))
        assertEquals(false to true, companionLaunchDisplayGlyphFill(CompanionLaunchDisplayChoice.BOTTOM))
        assertEquals(false to false, companionLaunchDisplayGlyphFill(CompanionLaunchDisplayChoice.ASK))
    }
}
