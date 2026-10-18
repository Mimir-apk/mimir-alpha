package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the magnifier in Browse and Files search was a font glyph, about 14 px beside 30 px text; every search draws the SAME mark at the SAME size now, core-ui's
 * [com.gruning.mimir.rewrite.core.ui.MimirSearchGlyph] (what Customize media's `ActivitySearchGlyph` draws). Source pins across the UI modules, this module's idiom for a rule about which
 * composable a surface calls: no production source draws the magnifier as text, and the search controls call the one glyph.
 */
class OneSearchMagnifierTest {

    private val modules = listOf("app", "core-ui", "feature-library", "feature-settings", "feature-files", "feature-browse", "feature-companion")

    private fun base(): File = listOf(File("."), File("..")).first { File(it, "feature-files/src/main").isDirectory }

    /** The code of [text] with its comments blanked, so a rule's own explanation never trips a pin. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun sourceOf(module: String, path: String): String =
        code(File(base(), "$module/src/main/kotlin/com/gruning/mimir/rewrite/$path").readText())

    @Test
    fun `no production source draws the magnifier as a font glyph`() {
        val offenders = modules.flatMap { module ->
            File(base(), "$module/src/main").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { code(it.readText()).contains("\"\u2315\"") }
                .map { "${module}/${it.name}" }
                .toList()
        }
        assertEquals("a search magnifier is MimirSearchGlyph, not the text mark: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `Browse, Files, the Library and the Activity filter call the one drawn glyph`() {
        assertTrue(sourceOf("feature-browse", "feature/browse/BrowseScreen.kt").contains("MimirSearchGlyph(color = MimirColors.TextSecondary)"))
        assertTrue(sourceOf("feature-files", "feature/files/FileBrowserSearchSurface.kt").contains("MimirSearchGlyph(color = palette.textMuted)"))
        assertTrue(sourceOf("feature-library", "feature/library/LibraryScreen.kt").contains("MimirSearchGlyph(color = MimirColors.TextSecondary)"))
        assertTrue(sourceOf("app", "CompanionActivityAchievementFilterHeader.kt").contains("MimirSearchGlyph(color = color)"))
    }

    @Test
    fun `the Files width model prices the magnifier at the glyph's own size`() {
        val layout = sourceOf("feature-files", "feature/files/FileBrowserSearchLayout.kt")
        assertTrue("the query floor's glyph term is the drawn mark's size, not a font ink measurement", layout.contains("MimirSearchGlyphSize.value +"))
    }
}
