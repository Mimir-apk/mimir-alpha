package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.IdentitySource
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for identity building: display title, region/version parsing, and gameId derivation. */
class GameIdentityBuilderTest {

    private val builder = GameIdentityBuilder()

    @Test
    fun `display title strips region and version parentheticals and separators`() {
        assertEquals("Chrono Trigger", builder.displayTitle("Chrono Trigger (USA)"))
        assertEquals("Super Mario 64", builder.displayTitle("Super_Mario_64 (USA) (Rev 1)"))
        assertEquals("Final Fantasy VII (Disc 1)", builder.displayTitle("Final.Fantasy.VII (Disc 1)"))
    }

    @Test
    fun `stored filename title uses the full display tidy`() {
        val identity = builder.build(
            PlatformId.DS,
            ResolutionInput("0119 - Legend of Zelda, The - Phantom Hourglass (Europe) (En,Fr,De,Es,It).nds", "nds"),
        )
        assertEquals("The Legend of Zelda - Phantom Hourglass", identity.title)
    }

    @Test
    fun `region token is extracted and canonicalized`() {
        assertEquals("USA", builder.extractRegion("Metroid Fusion (USA)"))
        assertEquals("Europe", builder.extractRegion("Metroid Fusion (Europe)"))
        assertEquals("Japan", builder.extractRegion("Rockman (J)"))
        assertEquals("Asia", builder.extractRegion("Some Game (Asia)"))
        assertEquals("Taiwan", builder.extractRegion("Some Game (Taiwan)"))
        assertEquals("Netherlands", builder.extractRegion("Some Game (Netherlands)"))
        assertEquals("Scandinavia", builder.extractRegion("Some Game (Scandinavia)"))
        assertEquals("Europe", builder.extractRegion("Some Game (En,Fr) (Europe, Australia)"))
        assertNull(builder.extractRegion("No Region Here"))
    }

    @Test
    fun `version token is extracted`() {
        assertEquals("Rev 1", builder.extractVersion("Zelda (USA) (Rev 1)"))
        assertTrue(builder.extractVersion("Sonic (Beta)")!!.contains("Beta"))
        assertNull(builder.extractVersion("Sonic (USA)"))
    }

    @Test
    fun `an UNcatalogued filename identity carries no catalog key (region siblings stay separate)`() {
        // A made-up title guaranteed absent from the catalog → no catalog proof → no auto-collapse.
        val usa = builder.build(PlatformId.DS, ResolutionInput("Obscure Homebrew RPG (USA).nds", "nds"))
        val eur = builder.build(PlatformId.DS, ResolutionInput("Obscure Homebrew RPG (Europe).nds", "nds"))
        assertEquals(IdentitySource.FILENAME, usa.source)
        assertNull(usa.titleCatalogKey)
        assertNull(eur.titleCatalogKey)
        assertEquals(IdentitySource.FILENAME, eur.source)
        assertTrue(usa.gameId != eur.gameId)
    }

    @Test
    fun `a catalogued title yields a shared catalog key and the canonical display title`() {
        // Two very different filenames of the SAME game resolve to one catalog key + canonical title.
        val a = builder.build(PlatformId.GB, ResolutionInput("Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb", "gb"))
        val b = builder.build(PlatformId.GB, ResolutionInput("pokemon_red.gb", "gb"))
        assertEquals("gb:pokemon-red", a.titleCatalogKey)
        assertEquals(a.titleCatalogKey, b.titleCatalogKey)
        assertEquals(a.gameId, b.gameId) // → they collapse to one group
        assertEquals("Pokémon Red", a.title)
        assertEquals("Pokémon Red", b.title)
    }

    @Test
    fun `serial evidence yields a serial-scoped gameId and native identity source`() {
        val id = builder.build(PlatformId.PS1, ResolutionInput("FF7 (Disc 1).chd", "chd", serial = "SLUS-00006"))
        assertEquals(IdentitySource.NATIVE_IDENTITY, id.source)
        assertEquals("SLUS-00006", id.serial)
        assertTrue(id.gameId.value.startsWith("serial:ps1:"))
    }

    @Test
    fun `content hash yields a hash-scoped gameId and content-hash source`() {
        val id = builder.build(PlatformId.SNES, ResolutionInput("Game.sfc", "sfc", contentHash = "deadbeef"))
        assertEquals(IdentitySource.CONTENT_HASH, id.source)
        assertEquals("hash:deadbeef", id.gameId.value)
    }

    @Test
    fun `multi-disc siblings share a title gameId when filename-only`() {
        val d1 = builder.build(PlatformId.PS1, ResolutionInput("Final Fantasy VII (Disc 1).chd", "chd"))
        val d2 = builder.build(PlatformId.PS1, ResolutionInput("Final Fantasy VII (Disc 2).chd", "chd"))
        assertEquals("discs of one game share the title key", d1.gameId, d2.gameId)
    }

    @Test
    fun `uncatalogued revision variants retain separate filename identities`() {
        val first = builder.build(PlatformId.DS, ResolutionInput("Obscure RPG (USA) (Rev 1).nds", "nds"))
        val second = builder.build(PlatformId.DS, ResolutionInput("Obscure RPG (USA) (Rev 2).nds", "nds"))
        assertTrue(first.gameId != second.gameId)
    }
}
