package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Utility panel's Feedback header.
 *
 * The Game art section was removed with its Cartridge Hero and Scene style controls.
 */
class UtilityFeedbackAndStyleRowTest {

    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    // ── QA-19 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Feedback header names the sound rows it contains`() {
        val overlay = source("BrightnessOverlay.kt")
        val section = overlay.substringAfter("""UtilitySectionLabel("Feedback & Sound", palette)""")
            .substringBefore("UtilityDivider(palette)")

        assertTrue(
            "search window missed the section -- assertions below would pass vacuously",
            section.length > 200,
        )
        // The header was "Feedback" while two of its three rows were sound. The rows are the evidence for
        // the rename, so they are what this asserts — not the header string on its own.
        assertTrue(section.contains("Mimir navigation sounds"))
        // "Ambient sound" became "Music volume" : the same value the Music
        // card's Volume dial moves, so one level carries one name everywhere.
        assertTrue(section.contains("Music volume"))
        assertFalse("the old name must not come back beside the new one", section.contains("Ambient sound"))
        assertTrue(section.contains("Mimir navigation haptics"))
        assertFalse(
            "the bare header must not come back while those rows are in it",
            overlay.contains("""UtilitySectionLabel("Feedback", palette)"""),
        )
    }
}
