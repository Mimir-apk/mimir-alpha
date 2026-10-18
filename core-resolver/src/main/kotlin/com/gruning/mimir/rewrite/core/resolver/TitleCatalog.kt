package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.PlatformId

/** The single canonical game a set of region/version/language variants collapse into. */
data class CanonicalTitle(val key: String, val displayTitle: String)

/**
 * OFFLINE, bundled canonical-title catalog — the safe realization of the region/version collapse. NO NETWORK, EVER (legal posture): this is compiled data shipped in
 * the APK, never fetched, synced, or scraped.
 *
 * It maps KNOWN filename variants of ONE game (region tags, revisions, alternate spellings, other-language
 * titles) to a single [CanonicalTitle]. It is **explicit and curated**: only the variants listed here
 * collapse, so it can NEVER merge two genuinely different games — Pokémon Red vs Blue, Zelda vs Zelda II,
 * Sonic 1 vs Sonic 2, Diamond vs Pearl all stay apart because only the listed keys resolve. A miss returns
 * null → the game keeps its own filename identity (today's conservative behavior).
 *
 * Lookup is PLATFORM-SCOPED (a `nes/` "zelda" never collides with a `gbc/` "zelda") over a deterministic
 * [normalizeKey] (No-Intro numeric index prefix stripped, region/version parens + brackets stripped,
 * lower-cased, non-alphanumerics collapsed to spaces). This is a starter catalog grounded in captured
 * variant clusters; it is meant to grow as new variant clusters appear.
 */
object TitleCatalog : CanonicalTitleSource {

    /** The canonical title for a file on [platformId] with base name [baseName], or null if not catalogued. */
    override fun resolve(platformId: PlatformId, baseName: String): CanonicalTitle? =
        ENTRIES[platformId.value]?.get(normalizeKey(baseName))

    /**
     * Deterministic lookup key: strip a leading No-Intro numeric index (`1015 - `), then region/version
     * parentheticals + bracket tags, lower-case, and collapse every non-alphanumeric run to one space.
     * The numeric strip requires a dash separator so a game literally named with digits (e.g. `1942`,
     * `2048`) is NOT mistaken for an index prefix.
     */
    fun normalizeKey(baseName: String): String {
        var s = baseName
        s = INDEX_PREFIX.replace(s, "")
        s = PAREN.replace(s, " ")
        s = BRACKET.replace(s, " ")
        s = foldDiacritics(s)          // Pokémon → Pokemon, so accented titles key cleanly
        s = VERSION.replace(s, " ")    // strip an embedded "v1.0.0" (a Switch base + update share a key)
        s = NON_ALNUM.replace(s, " ")
        // Split smashed camelCase per token so a filename like "DevilMayCry3SE" keys as "devil may cry 3 se"
        // (reuses the display path's boundary rules). Runs BEFORE lowercase (the splitter needs case);
        // idempotent afterward since a lowercase token has no boundary. Existing spaced/single-word keys are
        // unaffected (their tokens have no internal lower→upper / letter↔digit boundary).
        s = s.split(WHITESPACE).joinToString(" ") { DisplayTitle.spaceIfSmashed(it) }
        return s.trim().lowercase().replace(WHITESPACE, " ")
    }

    /** Decompose + drop combining marks: "Pokémon" → "Pokemon" (keeps accented titles ASCII-stable). */
    private fun foldDiacritics(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(COMBINING, "")

    private val INDEX_PREFIX = Regex("""^\s*\d{3,5}\s*-\s+""")
    private val PAREN = Regex("""\([^)]*\)""")
    private val BRACKET = Regex("""\[[^\]]*\]""")
    private val VERSION = Regex("""\bv\d+(?:\.\d+)+\b""", RegexOption.IGNORE_CASE)
    private val COMBINING = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^A-Za-z0-9 ]""")
    private val WHITESPACE = Regex("""\s+""")

    // platform → (normalized variant key → canonical). EVERY variant of one game maps to the SAME canonical.
    // Keys are the exact normalizeKey() output of captured filenames (verified 2026-07-05).
    private val ENTRIES: Map<String, Map<String, CanonicalTitle>> = buildMap {
        val zeldaNes = CanonicalTitle("nes:legend-of-zelda", "The Legend of Zelda")
        put("nes", mapOf(
            "legend of zelda" to zeldaNes,       // Legend of Zelda (USA)
            "legend of zelda the" to zeldaNes,   // Legend of Zelda, The (USA) (Rev 1)
            "zelda" to zeldaNes,                 // zelda.nes
        ))

        val pokemonRed = CanonicalTitle("gb:pokemon-red", "Pokémon Red")
        put("gb", mapOf(
            "pokemon red version" to pokemonRed, // Pokemon - Red Version (USA, Europe) (SGB Enhanced)
            "pokemon red" to pokemonRed,         // Pokemon Red (USA) + pokemon_red
        ))

        val zeldaOoa = CanonicalTitle("gbc:zelda-oracle-of-ages", "The Legend of Zelda: Oracle of Ages")
        put("gbc", mapOf(
            "legend of zelda the oracle of ages" to zeldaOoa, // Legend of Zelda, The - Oracle of Ages (USA, Australia)
            "zelda oracle of ages" to zeldaOoa,               // Zelda Oracle of Ages (USA)
            "zelda ages" to zeldaOoa,                         // zelda_ages
        ))

        val sonic1 = CanonicalTitle("megadrive:sonic-the-hedgehog", "Sonic the Hedgehog")
        put("megadrive", mapOf(
            "sonic the hedgehog" to sonic1,      // Sonic The Hedgehog (USA) + (USA, Europe)
            "sonic" to sonic1,                   // sonic.md
        ))

        // ── Nintendo 3DS (web-confirmed 2026-07-05) ──
        val castlevaniaMof = CanonicalTitle("3ds:castlevania-lords-of-shadow-mirror-of-fate", "Castlevania: Lords of Shadow - Mirror of Fate")
        val metroidSamus = CanonicalTitle("3ds:metroid-samus-returns", "Metroid: Samus Returns")
        put("3ds", mapOf(
            "castlevania lords of shadow mirror of fate" to castlevaniaMof, // base dump + "(alternate dump 1)"
            "metroid samus returns" to metroidSamus,                        // .3ds cart dump + .cia package
        ))

        // ── Nintendo Switch ──
        put("switch", mapOf(
            // base [APP] + update [UPD] — the embedded v1.0.0/v2.0.0 is stripped, é→e, so both key alike.
            "pokemon legends z a" to CanonicalTitle("switch:pokemon-legends-za", "Pokémon Legends: Z-A"),
        ))

        // ── Nintendo DS ── (English + German localizations of one game, both present in the library)
        val pokemonDiamond = CanonicalTitle("ds:pokemon-diamond", "Pokémon Diamond")
        val pokemonPlatinum = CanonicalTitle("ds:pokemon-platinum", "Pokémon Platinum")
        val pokemonBlack = CanonicalTitle("ds:pokemon-black", "Pokémon Black")
        val pokemonTrozei = CanonicalTitle("ds:pokemon-trozei", "Pokémon Trozei!")
        put("ds", mapOf(
            "pokemon diamond version" to pokemonDiamond,  // NNNN - Pokemon - Diamond Version (USA/Europe)
            "pokemon diamant edition" to pokemonDiamond,  // Pokemon - Diamant-Edition (Germany) — DE title
            "pokemon platinum version" to pokemonPlatinum, // Pokemon - Platinum Version (Europe)
            "pokemon platin edition" to pokemonPlatinum,   // Pokemon - Platin-Edition (Germany) — DE title
            "pokemon black version" to pokemonBlack,       // Pokemon - Black Version
            "pokemon schwarze edition" to pokemonBlack,    // Pokemon - Schwarze Edition (Germany) — DE title
            "pokemon trozei" to pokemonTrozei,             // Pokemon Trozei! (USA)
            "pokemon link" to pokemonTrozei,               // Pokemon Link! (Europe) — SAME game, PAL title
        ))

        // ── PlayStation 2 — camelCase filenames the new normalizeKey now splits, e.g.
        // "DevilMayCry3SE" → key "devil may cry 3 se". Keys are the normalizeKey() output of the real files.
        val dmc2 = CanonicalTitle("ps2:devil-may-cry-2", "Devil May Cry 2")
        put("ps2", mapOf(
            "devil may cry 3 se" to CanonicalTitle("ps2:devil-may-cry-3-special-edition", "Devil May Cry 3: Special Edition"),
            "devil may cry 2 disc 1" to dmc2,  // collapse both discs into one tile
            "devil may cry 2 disc 2" to dmc2,
            "dirge of cerberus ffvii" to CanonicalTitle("ps2:dirge-of-cerberus-ffvii", "Dirge of Cerberus: Final Fantasy VII"),
            "kingdom hearts 2 final mix" to CanonicalTitle("ps2:kingdom-hearts-ii-final-mix", "Kingdom Hearts II: Final Mix"),
            "kingdom hearts re chain" to CanonicalTitle("ps2:kingdom-hearts-re-chain-of-memories", "Kingdom Hearts Re:Chain of Memories"),
        ))

        // ── Sega Saturn — trailing bare article. normalizeKey keeps "house of the dead the"; the
        // pretty prefixed form lives in the canonical displayTitle (no global trailing-article fold, which
        // would break the nes "legend of zelda the" key).
        put("saturn", mapOf(
            "house of the dead the" to CanonicalTitle("saturn:house-of-the-dead", "The House of the Dead"),
        ))
    }
}
