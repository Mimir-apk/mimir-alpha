package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The offline canonical-title catalog: normalization robustness + captured variant clusters
 * (web-verified 2026-07-05). Every assertion uses actual captured filenames so the keys are real.
 */
class TitleCatalogTest {

    private fun base(fileName: String) = fileName.substringBeforeLast('.', fileName)

    // ── normalizeKey robustness ──────────────────────────────────────────────

    @Test
    fun `normalizeKey folds diacritics and strips embedded version markers`() {
        // Pokémon → pokemon; the un-bracketed v1.0.0 / v2.0.0 is stripped so base + update share a key.
        assertEquals(
            "pokemon legends z a",
            TitleCatalog.normalizeKey(base("Pokémon Legends Z-A v1.0.0[0100F43008C44000][v0][APP].nsp")),
        )
        assertEquals(
            "pokemon legends z a",
            TitleCatalog.normalizeKey(base("Pokémon Legends Z-A v2.0.0[0100F43008C44800][v262144][UPD].nsp")),
        )
    }

    @Test
    fun `normalizeKey strips a No-Intro index prefix but not a game named with digits`() {
        assertEquals("pokemon diamond version", TitleCatalog.normalizeKey(base("1015 - Pokemon - Diamond Version (USA) (Rev 5).nds")))
        assertEquals("1942", TitleCatalog.normalizeKey(base("1942.zip"))) // arcade game, NOT an index prefix
    }

    // ── CP06: smashed camelCase filenames key like their spaced form + are idempotent ────────────
    @Test
    fun `normalizeKey splits smashed camelCase and is idempotent`() {
        assertEquals("devil may cry 3 se", TitleCatalog.normalizeKey(base("DevilMayCry3SE.iso")))
        assertEquals("dirge of cerberus ffvii", TitleCatalog.normalizeKey(base("DirgeOfCerberusFFVII.iso")))
        assertEquals("kingdom hearts 2 final mix", TitleCatalog.normalizeKey(base("KingdomHearts2FinalMix.iso")))
        assertEquals("kingdom hearts re chain", TitleCatalog.normalizeKey(base("KingdomHeartsReChain.iso")))
        // idempotent: normalizing the already-normalized key is a no-op.
        assertEquals("devil may cry 3 se", TitleCatalog.normalizeKey("devil may cry 3 se"))
    }

    @Test
    fun `CP06 camelCase ps2 + trailing-article saturn titles resolve to canonical`() {
        assertEquals(
            "Devil May Cry 3: Special Edition",
            TitleCatalog.resolve(PlatformId("ps2"), base("DevilMayCry3SE.iso"))?.displayTitle,
        )
        assertEquals(
            "Kingdom Hearts Re:Chain of Memories",
            TitleCatalog.resolve(PlatformId("ps2"), base("KingdomHeartsReChain.iso"))?.displayTitle,
        )
        // both DMC2 discs collapse to ONE canonical
        assertEquals(
            TitleCatalog.resolve(PlatformId("ps2"), "DevilMayCry2Disc1")?.key,
            TitleCatalog.resolve(PlatformId("ps2"), "DevilMayCry2Disc2")?.key,
        )
        assertEquals(
            "The House of the Dead",
            TitleCatalog.resolve(PlatformId("saturn"), base("House of the Dead The.cue"))?.displayTitle,
        )
    }

    // ── captured variant clusters collapse ───────────────────────────────────

    @Test
    fun `switch base and update resolve to one canonical title`() {
        val app = TitleCatalog.resolve(PlatformId.SWITCH, base("Pokémon Legends Z-A v1.0.0[0100F43008C44000][v0][APP].nsp"))
        val upd = TitleCatalog.resolve(PlatformId.SWITCH, base("Pokémon Legends Z-A v2.0.0[0100F43008C44800][v262144][UPD].nsp"))
        assertNotNull(app)
        assertEquals(app, upd)
        assertEquals("Pokémon Legends: Z-A", app!!.displayTitle)
    }

    @Test
    fun `3ds format and dump variants resolve to one canonical title`() {
        val cart = TitleCatalog.resolve(PlatformId.THREE_DS, base("Metroid - Samus Returns (USA) (En,Fr,Es).3ds"))
        val cia = TitleCatalog.resolve(PlatformId.THREE_DS, base("Metroid - Samus Returns (USA).cia"))
        assertEquals("Metroid: Samus Returns", cart?.displayTitle)
        assertEquals(cart, cia)

        val cvBase = TitleCatalog.resolve(PlatformId.THREE_DS, base("Castlevania - Lords of Shadow - Mirror of Fate (USA) (En,Fr,Es).3ds"))
        val cvAlt = TitleCatalog.resolve(PlatformId.THREE_DS, base("Castlevania - Lords of Shadow - Mirror of Fate (USA) (En,Fr,Es) (alternate dump 1).3ds"))
        assertEquals(cvBase, cvAlt)
        assertNotNull(cvBase)
    }

    @Test
    fun `ds english and other-language editions of the same game collapse`() {
        // Platinum: English (Europe) .nds + German Platin-Edition .zip → same game.
        assertEquals(
            TitleCatalog.resolve(PlatformId.DS, base("3797 - Pokemon - Platinum Version (Europe) (Rev 10).nds")),
            TitleCatalog.resolve(PlatformId.DS, base("Pokemon - Platin-Edition (Germany).zip")),
        )
        // Black: English + German Schwarze Edition.
        assertEquals(
            TitleCatalog.resolve(PlatformId.DS, base("Pokemon - Black Version.nds")),
            TitleCatalog.resolve(PlatformId.DS, base("Pokemon - Schwarze Edition (Germany) (NDSi Enhanced) [b].zip")),
        )
        // Trozei! (USA) == Link! (Europe) — same 2006 puzzle game under its two regional titles.
        assertEquals(
            TitleCatalog.resolve(PlatformId.DS, base("0350 - Pokemon Trozei! (USA).nds")),
            TitleCatalog.resolve(PlatformId.DS, base("0432 - Pokemon Link! (Europe) (En,Fr,De,Es,It).nds")),
        )
    }

    // ── never merges different games ─────────────────────────────────────────

    @Test
    fun `different Pokemon generations and versions never share a canonical key`() {
        val diamond = TitleCatalog.resolve(PlatformId.DS, base("1015 - Pokemon - Diamond Version (USA) (Rev 5).nds"))!!.key
        val platinum = TitleCatalog.resolve(PlatformId.DS, base("3797 - Pokemon - Platinum Version (Europe) (Rev 10).nds"))!!.key
        val black = TitleCatalog.resolve(PlatformId.DS, base("Pokemon - Black Version.nds"))!!.key
        assertEquals(3, setOf(diamond, platinum, black).size) // three distinct games, three distinct keys
        // Pearl is NOT catalogued → stays on its own (never folded into Diamond).
        assertNull(TitleCatalog.resolve(PlatformId.DS, base("1286 - Pokemon - Pearl Version (Europe) (Rev 13).nds")))
        // White (Weisse Edition) is a different game from Black and is absent → not catalogued.
        assertNull(TitleCatalog.resolve(PlatformId.DS, base("Pokemon - Weisse Edition (Germany).zip")))
    }

    @Test
    fun `a platform-scoped key never leaks across platforms`() {
        // "pokemon red" is a gb entry; the same string on another platform must not resolve to it.
        assertNotNull(TitleCatalog.resolve(PlatformId.GB, "Pokemon Red"))
        assertNull(TitleCatalog.resolve(PlatformId.DS, "Pokemon Red"))
    }
}
