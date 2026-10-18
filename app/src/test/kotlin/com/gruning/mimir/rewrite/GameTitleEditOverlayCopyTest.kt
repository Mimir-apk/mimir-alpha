package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content-writing pass. Pins the strings that carry MEANING — an internal
 * feature name leaking into player-facing copy, a "detected"/"corrected" mechanism word replaced by a
 * player-plain one — rather than every literal, matching the source-characterization pattern
 * `GameTitleEditOverlayLayeringTest` already uses for this exact file (no Compose test rule available here).
 */
class GameTitleEditOverlayCopyTest {

    @Test
    fun eyebrowAndKeyboardLabelReuseTheApprovedRenameWordNotTheClinicalOne() {
        val source = gameTitleEditOverlaySource()

        assertTrue(source.contains("MimirChromeText(MimirCopy.RenameGame,"))
        assertTrue(source.contains("targetLabel = MimirCopy.RenameGame,"))
        assertFalse(source.contains("\"Game title\""))
    }

    @Test
    fun emptyDraftPlaceholderDropsTheCorrectionFramingForTheProfileOverlaysPhrasing() {
        val source = gameTitleEditOverlaySource()

        assertTrue(source.contains("MimirTextIntent.StaticChrome(\"Type a name\")"))
        assertFalse(source.contains("Type the corrected title"))
    }

    @Test
    fun resetButtonNoLongerNamesTheScannersOwnMechanism() {
        val source = gameTitleEditOverlaySource()

        assertTrue(source.contains("MimirCopy.ResetToOriginalName"))
        assertFalse(source.contains("ResetToDetectedName"))
        assertFalse(source.contains("detected name"))
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
