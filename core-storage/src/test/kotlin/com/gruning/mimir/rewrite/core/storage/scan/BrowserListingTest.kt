package com.gruning.mimir.rewrite.core.storage.scan

import com.gruning.mimir.rewrite.core.storage.scan.BrowserListing.Entry
import com.gruning.mimir.rewrite.core.storage.scan.BrowserListing.RowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserListingTest {

    private val classify: (String) -> String? = { name ->
        when (name.substringAfterLast('.', "").lowercase()) {
            "gba" -> "Game Boy Advance"
            "chd" -> "PlayStation"
            else -> null
        }
    }

    @Test
    fun `directories come first, then names in the order a person expects`() {
        val rows = BrowserListing.rows(
            listOf(
                Entry("zelda.gba", isDirectory = false),
                Entry("Roms", isDirectory = true),
                Entry("advance wars.gba", isDirectory = false),
                Entry("bios", isDirectory = true),
            ),
            classify,
        )
        assertEquals(
            "directories first, then case-insensitive by name — filesystem order is arbitrary",
            listOf("bios", "Roms", "advance wars.gba", "zelda.gba"),
            rows.map { it.name },
        )
    }

    @Test
    fun `recognised content carries the platform from the injected classifier`() {
        val rows = BrowserListing.rows(listOf(Entry("zelda.gba", isDirectory = false)), classify)
        assertEquals(RowKind.GAME, rows.single().kind)
        assertEquals(
            "the hint must come from the resolver's existing table, never a second one declared here",
            "Game Boy Advance",
            rows.single().platformHint,
        )
    }

    @Test
    fun `archives are offered for extraction and nothing else is`() {
        val rows = BrowserListing.rows(
            listOf(
                Entry("pack.zip", isDirectory = false),
                Entry("pack.7z", isDirectory = false),
                Entry("zelda.gba", isDirectory = false),
                Entry("Roms", isDirectory = true),
            ),
            classify,
        )
        val extractable = rows.filter { it.extractable }.map { it.name }.sorted()
        assertEquals(listOf("pack.7z", "pack.zip"), extractable)
    }

    @Test
    fun `rar is not offered, because the unrar licence forbids it`() {
        assertFalse(
            "rar remains excluded: the reference unrar licence forbids " +
                "reimplementing RAR compression and is not a normal open licence",
            "rar" in BrowserListing.EXTRACTABLE_EXTENSIONS,
        )
        val rows = BrowserListing.rows(listOf(Entry("pack.rar", isDirectory = false)), classify)
        assertFalse("a rar must never advertise extraction", rows.single().extractable)
    }

    @Test
    fun `BIOS payloads are shown honestly instead of being offered as games`() {
        val rows = BrowserListing.rows(
            listOf(Entry("scph1001.bin", isDirectory = false, isBios = true)),
            classify,
        )
        assertEquals(RowKind.BIOS, rows.single().kind)
    }

    @Test
    fun `support files are dimmed but never hidden`() {
        val rows = BrowserListing.rows(
            listOf(
                Entry("zelda.srm", isDirectory = false),
                Entry("notes.txt", isDirectory = false),
                Entry(".nomedia", isDirectory = false),
                Entry("saves", isDirectory = true),
            ),
            classify,
        )
        assertEquals(
            "every file on disk must still appear — a browser that lies about storage is worse than none",
            4,
            rows.size,
        )
        assertTrue("support material is de-emphasised", rows.all { it.deEmphasised })
        assertTrue(
            "a support directory is still a directory the player can enter",
            rows.first { it.name == "saves" }.kind == RowKind.DIRECTORY,
        )
    }

    @Test
    fun `an archive is an archive even when its name looks like content`() {
        val rows = BrowserListing.rows(listOf(Entry("zelda.gba.zip", isDirectory = false)), classify)
        assertEquals(
            "extraction must win over a name-based platform guess, or the player is offered a launch " +
                "of something that is still compressed",
            RowKind.ARCHIVE,
            rows.single().kind,
        )
    }

    @Test
    fun `an empty directory produces no rows and does not throw`() {
        assertTrue(BrowserListing.rows(emptyList(), classify).isEmpty())
    }
}
