package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legal reader's pure parts: how a packaged text is cut into reading blocks, the ids of its rows, and what a persisted focus on
 * one of its rows becomes. The composables are covered by the Settings pane tests; the packaged texts themselves are pinned by the
 * app module's `LegalNoticeFilesTest`.
 */
class LegalNoticeTest {

    private fun words(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `blank text has no blocks`() {
        assertEquals(emptyList<String>(), legalTextBlocks(""))
        assertEquals(emptyList<String>(), legalTextBlocks("\n\n   \n"))
    }

    @Test
    fun `short paragraphs share a block with a blank line between them`() {
        val blocks = legalTextBlocks("First paragraph.\nStill the first.\n\nSecond paragraph.\n\n\nThird.", maxChars = 200)
        assertEquals(listOf("First paragraph.\nStill the first.\n\nSecond paragraph.\n\nThird."), blocks)
    }

    @Test
    fun `a paragraph that would pass the limit starts the next block`() {
        val blocks = legalTextBlocks("aaaa aaaa\n\nbbbb bbbb\n\ncccc", maxChars = 20)
        assertEquals(listOf("aaaa aaaa\n\nbbbb bbbb", "cccc"), blocks)
    }

    @Test
    fun `a long paragraph is split between its lines and a line is never cut`() {
        val paragraph = (1..10).joinToString("\n") { "line number $it is here" }
        val blocks = legalTextBlocks(paragraph, maxChars = 60)
        assertTrue("expected more than one block, got ${blocks.size}", blocks.size > 1)
        assertEquals(paragraph, blocks.joinToString("\n"))
        val oneLine = "x".repeat(300)
        assertEquals(listOf(oneLine), legalTextBlocks(oneLine, maxChars = 50))
    }

    @Test
    fun `indentation is kept and trailing spaces are dropped`() {
        assertEquals(listOf("   centered title\n  second"), legalTextBlocks("   centered title   \n  second   \n"))
    }

    @Test
    fun `no word is lost or reordered in the packaged texts`() {
        listOf("../LICENSE", "../NOTICE", "../mimir-tweaker/NOTICE.md").forEach { path ->
            val file = File(path)
            assertTrue("$path must exist", file.isFile)
            val text = file.readText()
            val blocks = legalTextBlocks(text)
            assertTrue("$path must give several blocks", blocks.size > 5)
            assertEquals("$path: the words must be unchanged", words(text), words(blocks.joinToString("\n\n")))
            blocks.forEach { block ->
                assertTrue(
                    "$path: a block of ${block.length} characters is over the limit and is not a single line",
                    block.length <= LegalBlockMaxChars || block.lines().size == 1,
                )
            }
        }
    }

    @Test
    fun `the reader rows have their own ids under the legal prefix`() {
        assertTrue(AboutLegalSourceRowId.startsWith(AboutLegalRowPrefix))
        LegalDoc.entries.forEach { doc ->
            assertTrue(doc.rowId.startsWith(AboutLegalRowPrefix))
            assertTrue(legalBlockRowId(doc, 7).startsWith(doc.rowId + "/"))
        }
        assertNotEquals(LegalDoc.Notices.rowId, LegalDoc.License.rowId)
        assertNotEquals(legalBlockRowId(LegalDoc.Notices, 1), legalBlockRowId(LegalDoc.License, 1))
    }

    @Test
    fun `the packaged asset paths are the ones the app bundles`() {
        assertEquals("legal/NOTICE", LegalDoc.Notices.assetPath)
        assertEquals("legal/LICENSE", LegalDoc.License.assetPath)
        assertTrue(File("../app/src/main/assets/" + LegalDoc.Notices.assetPath).isFile)
        assertTrue(File("../app/src/main/assets/" + LegalDoc.License.assetPath).isFile)
    }

    @Test
    fun `a persisted focus on a reader row lands on the Credits row and the Credits row itself is kept`() {
        val credits = settingsDetailFocusKey(AboutCreditsRowId)
        listOf(AboutLegalSourceRowId, LegalDoc.Notices.rowId, LegalDoc.License.rowId, legalBlockRowId(LegalDoc.License, 12)).forEach { rowId ->
            assertEquals(rowId, credits, canonicalSettingsFocusKey(settingsDetailFocusKey(rowId)))
        }
        assertEquals(credits, canonicalSettingsFocusKey(credits))
        assertFalse(canonicalSettingsFocusKey(settingsDetailFocusKey("about/credits/gametdb")) == credits)
    }

    @Test
    fun `the source address is the public release repository`() {
        assertEquals("https://github.com/Mimir-apk/mimir-alpha", MimirSourceUrl)
    }
}
