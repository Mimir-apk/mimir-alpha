package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * Mimir `PlatformId` → ScreenScraper numeric `systemeid`. A Kotlin **source map** (never a bundled asset —
 * no vendor/endpoint strings in `assets/` or `.java`; this compiles to `classes.dex`).
 *
 * The IDs are ScreenScraper's stable, long-lived system identifiers. They are NOT the server-driven
 * quota/thread numbers (those MUST be read live from `ssuserInfos`); a system id is a fixed catalog key.
 * Still, treat this as best-effort and **confirm any doubtful id against `systemesListe.php` on a live
 * account** — an unknown/wrong id just means the lookup returns null and the game keeps its silhouette
 * (honest fallback), never a wrong cover.
 */
internal object ScreenScraperSystemMap {

    // Keyed by Mimir's lowercase platform ids (as seen in the library: dreamcast, psp, gamecube, …).
    private val byPlatform: Map<String, Int> = mapOf(
        "nes" to 3,
        "snes" to 4,
        "n64" to 14,
        "gamecube" to 13,
        "wii" to 16,
        "switch" to 225,
        "gb" to 9,
        "gbc" to 10,
        "gba" to 12,
        "ds" to 15,         // canonical PlatformId.DS is "ds" (the classifier only ever emits "ds")
        "nds" to 15,        // alias — harmless belt-and-suspenders
        "3ds" to 17,
        "genesis" to 1,     // Sega Mega Drive / Genesis
        "megadrive" to 1,
        "saturn" to 22,
        "dreamcast" to 23,
        "ps1" to 57,
        "psx" to 57,
        "ps2" to 58,
        "psp" to 61,
        "arcade" to 75,
        "mame" to 75,
        "3do" to 29,
        "the_3do_company_3do" to 29,
        // CP03 Batch 1  — platforms the scanner already recognizes + labels + draws, but
        // couldn't fetch covers for. IDs validated LIVE against systemesListe.php. Keys match the ids the
        // ExtensionPlatformClassifier already emits, so covers now light up for these with no scan changes.
        "mastersystem" to 2,        // Master System
        "gamegear" to 21,           // Game Gear
        "sg1000" to 109,            // SG-1000
        "sega32x" to 19,            // Megadrive 32X
        "segacd" to 20,             // Mega-CD
        "pcengine" to 31,           // PC Engine / TurboGrafx-16
        "pcenginecd" to 114,        // PC Engine CD-ROM
        "atari2600" to 26,          // Atari 2600
        "atari7800" to 41,          // Atari 7800
        "atarilynx" to 28,          // Atari Lynx
        "atarijaguar" to 27,        // Atari Jaguar
        "wonderswan" to 45,         // WonderSwan
        "wonderswancolor" to 46,    // WonderSwan Color
        "ngp" to 25,                // Neo-Geo Pocket
        "ngpc" to 82,               // Neo-Geo Pocket Color
        "virtualboy" to 11,         // Virtual Boy
        "pokemini" to 211,          // Pokémon mini
        // These three platforms were launch-recognized (EmulatorSeedRegistry seeds xenia/x360-mobile/
        // xendroid for xbox360 and rpcsx/aps3e for ps3) but had no system id at all, so every title on
        // them kept its silhouette forever.
        //
        // IDs read LIVE from systemesListe.php using the development registration, not assumed. That
        // mattered here more than usual: Xbox (32) and Xbox 360 (33) are adjacent in the catalog and
        // RE5 shipped on both, so a one-off id would have returned a real cover for the wrong system --
        // the header's "a wrong id just returns null" only holds for an id that does not exist.
        // Xbox One is 34 if it is ever seeded; deliberately not added while nothing can launch it.
        //
        // These keys are already the canonical platform ids in mimir-platform-registry.v2.json, so
        // unlike wii/psx/megadrive they need no canonicalPlatformByAlias entry.
        "xbox" to 32,               // Microsoft Xbox
        "xbox360" to 33,            // Microsoft Xbox 360
        "ps3" to 59,                // Sony PlayStation 3
        // 2026-08-10, closing the class rather than the report. Comparing every platform some
        // emulator seed can serve against every platform this map can ask about left seven more
        // that launch fine and could never fetch art -- the same defect xbox/x360/ps3 had, just
        // never reported because nobody had those games in front of them. IDs read live from
        // systemesListe.php; all seven are already canonical registry ids, so no alias entries.
        "commodore_64" to 66,                          // Commodore 64
        "microsoft_msx" to 113,                        // MSX
        "microsoft_msx2" to 116,                       // MSX2 (MSX2+ is 117, Turbo R 118 -- unseeded)
        "nec_pc_engine_supergrafx" to 105,             // PC Engine SuperGrafx
        "nintendo_family_computer_disk_system" to 106, // Family Computer Disk System
        "nintendo_wii_u" to 18,                        // Wii U
        "sony_playstation_vita" to 62,                 // PS Vita
        // The rest of the same class. Each of these had a seed and no way to fetch art. The
        // catalog is full of near-misses here, so the sibling chosen is named next to the one
        // taken -- picking the neighbour returns a real cover for the wrong machine.
        "atari_5200" to 40,                            // Atari 5200
        "coleco_colecovision" to 48,                   // Colecovision (NOT 183 "Coleco", NOT 89 "Adam")
        "commodore_amiga" to 64,                       // Amiga (NOT 111 AGA / 129 CDTV / 134 Amiga CD)
        "commodore_cd32" to 130,                       // Amiga CD32 (NOT 139, which is a hack set)
        "gce_vectrex" to 102,                          // Vectrex
        "nec_pc_fx" to 72,                             // PC-FX
        "philips_cd_i" to 133,                         // CD-i
        "sharp_x68000" to 79,                          // Sharp X68000
        "sinclair_zx_spectrum" to 76,                  // ZX Spectrum
        "snk_neo_geo_cd" to 70,                        // Neo-Geo CD
    )

    /** Provider-local/runtime keys → the one canonical platform target used by the cross-authority gate. */
    private val canonicalPlatformByAlias: Map<String, String> = mapOf(
        "wii" to "nintendo_wii",
        "nds" to "ds",
        "megadrive" to "genesis",
        "psx" to "ps1",
        "mame" to "arcade",
        "3do" to "the_3do_company_3do",
        "mastersystem" to "sega_master_system_mark_iii",
        "gamegear" to "sega_game_gear",
        "sg1000" to "sega_sg_1000",
        "sega32x" to "sega_32x",
        "segacd" to "sega_mega_cd_sega_cd",
        "pcengine" to "nec_pc_engine_turbografx_16",
        "pcenginecd" to "nec_pc_engine_cd_turbografx_cd",
        "atari2600" to "atari_2600",
        "atari7800" to "atari_7800",
        "atarilynx" to "atari_lynx",
        "atarijaguar" to "atari_jaguar",
        "wonderswan" to "bandai_wonderswan",
        "wonderswancolor" to "bandai_wonderswan_color",
        "ngp" to "snk_neo_geo_pocket",
        "ngpc" to "snk_neo_geo_pocket_color",
        "virtualboy" to "nintendo_virtual_boy",
        "pokemini" to "nintendo_pokemon_mini",
    )

    /** The ScreenScraper systeme id for [platformId], or null when unmapped (→ lookup skipped, silhouette kept). */
    fun systemeIdFor(platformId: PlatformId): Int? = byPlatform[platformId.value.lowercase()]

    /**
     * A stable fingerprint of which platforms this build can scrape at all.
     *
     * Negative caches ("this game has no art") are only trustworthy while the coverage that produced
     * them holds. When xbox/xbox360/ps3 were added on 2026-08-10, every already-recorded miss for
     * those titles became stale -- recorded because no request was possible, then suppressing the
     * request that would finally have worked. Callers that cache misses compare this value and drop
     * their cache when it changes, so a coverage change can never leave art permanently unfetched.
     *
     * Derived from the keys, not the ids: a platform gaining or losing a mapping is what invalidates a
     * negative. Correcting a wrong id also changes the epoch only if the key set moves -- an id-only
     * correction is handled by the same explicit-refetch path any other bad-art fix uses.
     */
    fun coverageEpoch(): String = byPlatform.keys.sorted().joinToString(",").let { keys ->
        // Short, stable, and cheap to compare; this is a change detector, not a security boundary.
        keys.hashCode().toUInt().toString(16).padStart(8, '0') + "-" + byPlatform.size
    }

    internal fun platformAuthorityRows(): List<OnlineProviderPlatformRow> =
        byPlatform.map { (rawPlatformId, systemId) ->
            OnlineProviderPlatformRow(
                provider = OnlinePlatformProvider.SCREEN_SCRAPER,
                rawPlatformId = rawPlatformId,
                declaredCanonicalPlatformId = canonicalPlatformByAlias[rawPlatformId] ?: rawPlatformId,
                providerKey = systemId.toString(),
            )
        }.sortedWith(
            compareBy(
                { it.declaredCanonicalPlatformId },
                { it.rawPlatformId },
                { it.providerKey },
            ),
        )
}
