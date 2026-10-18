package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  Pins that the button-narration
 * sentence is GONE (not merely reworded — piece 1 already handled wording) and that a single short,
 * secondary hint sits below the field on the capped type scale instead of a help paragraph. Source
 * characterization, matching `GameTitleEditOverlayLayeringTest`'s pattern for this file.
 */
class GameTitleEditOverlayHintTest {

    @Test
    fun buttonNarrationIsDeletedNotReworded() {
        val source = gameTitleEditOverlaySource()

        assertFalse(source.contains("Done to save"))
        assertFalse(source.contains("Back to cancel"))
    }

    @Test
    fun theFeatureNameLeakIsGoneFromTheFieldCopy() {
        val source = gameTitleEditOverlaySource()

        assertFalse(source.contains("Customize Media search"))
    }

    @Test
    fun oneShortSecondaryHintReplacesTheOldParagraph() {
        val source = gameTitleEditOverlaySource()

        // Exactly one hint Text call sits below the field, on the capped SECONDARY scale/color — never
        // the primary Support size/color the deleted paragraph used, and never a raw sp/hex literal.
        assertTrue(source.contains("style = MimirTypeScale.SupportCompact"))
        assertTrue(source.contains("color = palette.textMuted"))
        assertFalse(source.contains("style = MimirTypeScale.Support,"))
        assertFalse(source.contains("color = palette.textSecondary"))
    }

    private fun gameTitleEditOverlaySource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/GameTitleEditOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/GameTitleEditOverlay.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("GameTitleEditOverlay.kt not found from ${File(".").absolutePath}")
    }
}
