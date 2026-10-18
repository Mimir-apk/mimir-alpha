package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-10-14, wave 3 (W3a): the P3 draw pass for two :app surfaces. A value is read where it is painted, and a literal hold has a name. Pinned by source, which is the proof the lane
 * brief names for "not read in composition".
 */
class AuditW3aDrawPassTest {

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `the artwork progress fill is drawn with the spring's value read in the draw phase, and the spring is still the existing default`() {
        val bar = source("ArtworkFetchOverlay.kt").substringAfter("private fun ProgressBar(fraction: Float)").substringBefore("\n}\n")
        assertFalse("not a composition parameter", bar.contains("fillMaxWidth(f)"))
        assertTrue(bar.contains("size = Size(size.width * f, size.height),"))
        assertTrue("the existing default spring is untouched", bar.contains("val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = \"artwork-progress\")"))
    }

    @Test
    fun `the pin feedback's hold has a name and keeps its value`() {
        val text = source("HomeTomeLibraryOverlay.kt")
        assertFalse("no unnamed literal", text.contains("delay(1_500L)"))
        assertTrue(text.contains("delay(HomeTomeLibraryPinFeedbackHoldMs)"))
        assertEquals(1_500L, HomeTomeLibraryPinFeedbackHoldMs)
    }
}
