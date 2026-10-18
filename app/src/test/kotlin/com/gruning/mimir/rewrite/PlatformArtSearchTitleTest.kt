package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the SGB platform-art fetch searches by the CANONICAL console title, not the shelf label
 * ("Playstation equals Playstation 1"). Locks the ruling + the fallback for un-mapped ids.
 */
class PlatformArtSearchTitleTest {

    private fun title(id: String, label: String = "shelf-label") =
        SteamGridDbTileFetcher.platformArtSearchTitle(id, label)

    @Test
    fun `ps1 maps to the canonical PlayStation 1`() {
        assertEquals("PlayStation 1", title("ps1"))
        // Reported tested 2026-07-24: the Sony family's bare names return nothing; the "Sony " form finds
        // art. Confirmed for ps2, ps3 and psp. ps1 stays on its bare name — untested, not reordered.
        assertEquals("Sony PlayStation 2", title("ps2"))
        assertEquals("Sony PlayStation 3", title("ps3"))
        assertEquals("Sony PlayStation Portable", title("psp"))
    }

    @Test
    fun `nintendo + sega ids map to full console names`() {
        assertEquals("Nintendo 64", title("n64"))
        assertEquals("Super Nintendo Entertainment System", title("snes"))
        assertEquals("Nintendo GameCube", title("gamecube"))
        assertEquals("Sega Dreamcast", title("dreamcast"))
        assertEquals("Sega Genesis", title("megadrive"))
        assertEquals("Sega Genesis", title("genesis"))
    }

    @Test
    fun `arcade mame and 3do search full canonical names before abbreviations`() {
        assertEquals(
            listOf("Arcade", "MAME", "Multiple Arcade Machine Emulator", "Neo Geo"),
            SteamGridDbTileFetcher.platformArtSearchTitles("arcade", "Arcade"),
        )
        assertEquals(
            listOf("Arcade", "MAME", "Multiple Arcade Machine Emulator", "Neo Geo"),
            SteamGridDbTileFetcher.platformArtSearchTitles("mame", "MAME"),
        )
        assertEquals(
            listOf("3DO Interactive Multiplayer", "Panasonic 3DO", "3DO"),
            SteamGridDbTileFetcher.platformArtSearchTitles("3do", "3DO"),
        )
    }

    @Test
    fun `an un-mapped id falls back to the shelf label`() {
        assertEquals("My Homebrew System", title("unknownplatform", "My Homebrew System"))
    }

    @Test
    fun `every alternate angle is retained when a primary is reordered`() {
        // CP-02 reordered ps2's primary. The other angles must survive, because the AUTO fetch
        // (SteamGridDbTileFetcher.kt:358) walks the whole list and would silently lose coverage.
        val ps2 = SteamGridDbTileFetcher.platformArtSearchTitles("ps2", "PlayStation 2")
        assertTrue("the working term must lead", ps2.first() == "Sony PlayStation 2")
        assertTrue("the bare name must remain as an angle", ps2.contains("PlayStation 2"))
        assertTrue("the abbreviation must remain as an angle", ps2.contains("PS2"))
    }

    @Test
    fun `the Sony family leads with the owner-tested prefixed angle, and keeps the rest`() {
        // The bare names are the ones that return nothing, so they must NOT lead — but they stay as
        // angles because the auto fetch (SteamGridDbTileFetcher.kt:358) walks the whole list.
        mapOf(
            "ps2" to Triple("Sony PlayStation 2", "PlayStation 2", "PS2"),
            "ps3" to Triple("Sony PlayStation 3", "PlayStation 3", "PS3"),
            "psp" to Triple("Sony PlayStation Portable", "PlayStation Portable", "PSP"),
        ).forEach { (id, expected) ->
            val angles = SteamGridDbTileFetcher.platformArtSearchTitles(id, "fallback")
            val (primary, bare, abbrev) = expected
            assertEquals("$id must lead with the tested primary term", primary, angles.first())
            assertTrue("$id must retain the bare name as an angle", angles.contains(bare))
            assertTrue("$id must retain the abbreviation as an angle", angles.contains(abbrev))
        }
    }

    @Test
    fun `ps1 is deliberately NOT reordered`() {
        // Not reported or device-tested. Reordering it on inference needs its own evidence first.
        assertEquals("PlayStation 1", title("ps1"))
    }

    @Test
    fun `psp keeps the angle it was previously led by`() {
        // "Sony PSP" led briefly before the primary term was tested. It stays as an angle rather than
        // being dropped — the auto fetch walks every entry, so removing a plausible spelling only costs
        // coverage.
        val psp = SteamGridDbTileFetcher.platformArtSearchTitles("psp", "fallback")
        assertEquals("Sony PlayStation Portable", psp.first())
        assertTrue("the previous primary must survive as an angle", psp.contains("Sony PSP"))
    }
}
