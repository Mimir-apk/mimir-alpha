package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GameNameResolverTest {
    private val resolver = GameNameResolver()

    @Test
    fun `display catalog and filename identity use separate normalization domains`() {
        val name = resolver.resolve(
            ResolutionInput("Poke\u0301mon_Red (USA) (Rev 1).gb", "gb"),
            PlatformId.GB,
        )
        assertEquals("Pokémon Red", name.displayTitle)
        assertEquals("Pokémon Red (USA) (Rev 1)", name.catalogBaseName)
        assertEquals("pokémon red usa rev 1", name.filenameIdentityKey)
    }

    @Test
    fun `only disc ordinal is removed from filename identity`() {
        val first = resolver.resolve(ResolutionInput("Game (USA) (Disc 1).chd", "chd"), PlatformId.PS1)
        val second = resolver.resolve(ResolutionInput("Game (USA) (Disc 2).chd", "chd"), PlatformId.PS1)
        val revision = resolver.resolve(ResolutionInput("Game (USA) (Rev 2).chd", "chd"), PlatformId.PS1)
        assertEquals(first.filenameIdentityKey, second.filenameIdentityKey)
        assertNotEquals(first.filenameIdentityKey, revision.filenameIdentityKey)
    }

    @Test
    fun `terminal version cleans display but abbreviated Upd is not promoted to support`() {
        val portal = resolver.resolve(
            ResolutionInput("Portal 2 Upd v1.0.3.nsp", "nsp"),
            PlatformId.SWITCH,
        )
        assertEquals("Portal 2 Upd", portal.displayTitle)
        assertEquals("v1.0.3", portal.version)
        assertEquals(LocalContentRole.BASE, portal.contentRole)
    }

    @Test
    fun `shipping Switch title-id and exact tags determine support role`() {
        assertEquals(
            LocalContentRole.BASE,
            role("Game [0100123412345000][v0][APP].nsp"),
        )
        assertEquals(
            LocalContentRole.UPDATE,
            role("Game [0100123412345800][v262144][UPD].nsp"),
        )
        assertEquals(
            LocalContentRole.DLC,
            role("Game [0100123412345001].nsp"),
        )
    }

    @Test
    fun `support folders are exact parent segments`() {
        val input = ResolutionInput("updates.nes", "nes")
        assertEquals(LocalContentRole.SUPPORT, resolver.resolve(input, PlatformId.NES, "NES/updates/updates.nes").contentRole)
        assertEquals(LocalContentRole.BASE, resolver.resolve(input, PlatformId.NES, "NES/updates.nes").contentRole)
        assertEquals(LocalContentRole.BASE, resolver.resolve(input, PlatformId.NES, "NES/bioshock/updates.nes").contentRole)
    }

    @Test
    fun `disc sidecars are support while clonecd descriptor remains launch content`() {
        for (ext in listOf("sbi", "sub", "idx", "mds", "toc")) {
            val input = ResolutionInput("Disc.$ext", ext)
            assertEquals(ext, LocalContentRole.SUPPORT, resolver.resolve(input, PlatformId.PS1).contentRole)
        }
        assertEquals(
            LocalContentRole.BASE,
            resolver.resolve(ResolutionInput("Disc.ccd", "ccd"), PlatformId.PS1).contentRole,
        )
    }

    private fun role(name: String): LocalContentRole = resolver.resolve(
        ResolutionInput(name, name.substringAfterLast('.').lowercase()),
        PlatformId.SWITCH,
    ).contentRole

    @Test
    fun `the cheap BIOS check agrees with a full resolve on every BIOS rule and every near miss`() {
        // (relative path, is BIOS). The BIOS rows cover each rule: a bios/firmware folder, the exact names,
        // the name pattern on each BIOS extension. The rest are what must NOT be: a game, a sidecar, a
        // non-BIOS support folder, a BIOS-looking name on an extension that is never a BIOS.
        val cases = listOf(
            "scph1001.bin" to true,
            "SCPH-5501.BIN" to true,
            "neogeo.zip" to true,
            "naomi.zip" to true,
            "awbios.zip" to true,
            "dc_boot.bin" to true,
            "dc_flash.bin" to true,
            "saturn_bios.bin" to true,
            "PlayStation 2 BIOS.bin" to true,
            "bios/anything.dat" to true,
            "BIOS/Mario.gba" to true,
            "firmware/update.pup" to true,
            "psx/bios/scph1001.bin" to true,
            "Super Mario Advance.gba" to false,
            "neogeo.nes" to false,
            "scph1001.txt" to false,
            "Sonic.zip" to false,
            "Disc.sub" to false,
            "saves/scph1001.bin" to false,
            "roms/bioshock.bin" to false,
            "Biosphere.rom" to false,
        )
        cases.forEach { (relativePath, expected) ->
            val name = relativePath.substringAfterLast('/')
            val input = ResolutionInput(name, name.substringAfterLast('.', "").lowercase())
            val full = resolver.resolve(input, relativePath = relativePath).contentRole == LocalContentRole.BIOS
            assertEquals("$relativePath: the cheap check must equal the full resolve", full, resolver.isBiosPayload(input, relativePath))
            assertEquals("$relativePath is${if (expected) "" else " not"} a BIOS", expected, full)
        }
    }

    @Test
    fun `a BIOS check without a folder reads the name alone`() {
        assertEquals(true, resolver.isBiosPayload(ResolutionInput("scph1001.bin", "bin")))
        assertEquals(false, resolver.isBiosPayload(ResolutionInput("Zelda.gba", "gba")))
    }
}
