package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 *  Compact abbreviated the mode tabs to EMU / GAMES and the pill to Add on purpose;
 * the full labels take about 295 dp of the RG's 524.7 dp row, so compact now shows them.
 */
class ShelfCompactFullLabelsTest {

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `compact shows the full mode labels`() {
        val chrome = source("MimirShelfChrome.kt")
        assertFalse(chrome.contains("\"EMU\""))
        assertFalse(chrome.contains("compactShelfModeLabel"))
    }

    @Test
    fun `compact shows the Add pill's own label`() {
        assertFalse(source("LibraryScreen.kt").contains("text = if (compact) \"Add\" else label"))
    }
}
