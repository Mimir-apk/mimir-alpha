package com.gruning.mimir.rewrite.core.metadata.online

import java.util.Locale

/**
 * The Mimir platform id RetroAchievements' console [consoleName] maps to (the registry's id, as [RetroAchievementsClient.platformIdForConsoleName] says it), or null when it
 * has none (a hub, an event, an unmapped system). The same answer without a client, for a page that only has the console's name.
 */
fun retroAchievementsPlatformIdForConsoleName(consoleName: String): String? = RetroAchievementsConsoleMap.platformIdFor(consoleName)

/**
 * RetroAchievements console identity ↔ Mimir platform identity.
 *
 * This table deliberately answers a different question from [RetroAchievementsHash]: whether RA currently
 * supports achievements for the platform, not whether Mimir has already ported RA's file-identification
 * algorithm. Keeping those facts separate prevents a supported disc system such as PlayStation or PSP from
 * being presented as an unsupported RA system merely because Mimir still owes the disc/CHD reader.
 *
 * Ground truth is the intersection of Mimir's current B21 + R49 platform sets and RA's current supported
 * emulator/system list (audited 2026-07-19). Console numbers still come from `API_GetConsoleIDs.php`; this
 * class recognises the returned names. Several Mimir platforms intentionally share one RA console identity:
 * PC Engine and SuperGrafx, MSX and MSX2, WonderSwan and WonderSwan Color, Neo Geo Pocket and Color, and the
 * two Neo Geo cartridge ids through RA's Arcade system. NES also accepts RA's separate Famicom Disk System
 * identity, while DS accepts both Nintendo DS and DSi.
 */
internal object RetroAchievementsConsoleMap {

    /**
     * The measured 59-platform shipping surface's 45 ids in the Mimir/RA-supported intersection.
     * Discoverable registry additions are mapped separately so they do not rewrite that closed audit.
     */
    val mappedProductPlatformIds: Set<String> get() = consoleKeysByPlatform.keys

    private val consoleKeysByPlatform: Map<String, Set<String>> = mapOf(
        // Nintendo
        "gb" to setOf("gameboy"),
        "gba" to setOf("gameboyadvance"),
        "gbc" to setOf("gameboycolor"),
        "n64" to setOf("nintendo64"),
        "nes" to setOf("nintendoentertainmentsystem", "famicomdisksystem"),
        "snes" to setOf("supernintendoentertainmentsystem"),
        "ds" to setOf("nintendods", "nintendodsi"),
        "gamecube" to setOf("gamecube"),
        "nintendo_wii" to setOf("wii"),
        "nintendo_pokemon_mini" to setOf("pokemonmini"),
        "nintendo_virtual_boy" to setOf("virtualboy"),

        // Sega
        "genesis" to setOf("segagenesis"),
        "sega_32x" to setOf("sega32x"),
        "sega_game_gear" to setOf("gamegear"),
        "sega_master_system_mark_iii" to setOf("mastersystem"),
        "sega_sg_1000" to setOf("sg1000"),
        "sega_mega_cd_sega_cd" to setOf("segacd"),
        "saturn" to setOf("segasaturn"),
        "dreamcast" to setOf("dreamcast"),

        // Sony
        "ps1" to setOf("playstation"),
        "ps2" to setOf("playstation2"),
        "psp" to setOf("playstationportable"),

        // Atari
        "atari_2600" to setOf("atari2600"),
        "atari_7800" to setOf("atari7800"),
        "atari_jaguar" to setOf("atarijaguar"),
        "atari_lynx" to setOf("atarilynx"),

        // NEC
        "nec_pc_engine_turbografx_16" to setOf("pcengine"),
        "nec_pc_engine_supergrafx" to setOf("pcengine"),
        "nec_pc_engine_cd_turbografx_cd" to setOf("pcenginecd"),
        "nec_pc_fx" to setOf("pcfx"),

        // SNK / Bandai. RA carries Neo Geo cartridge sets under Arcade.
        "snk_neo_geo_pocket" to setOf("neogeopocket"),
        "snk_neo_geo_pocket_color" to setOf("neogeopocket"),
        "snk_neo_geo_cd" to setOf("neogeocd"),
        "bandai_wonderswan" to setOf("wonderswan"),
        "bandai_wonderswan_color" to setOf("wonderswan"),
        "neo_geo" to setOf("arcade"),
        "snk_neo_geo" to setOf("arcade"),

        // Other current Mimir platforms supported by RA
        "amstrad_cpc" to setOf("amstradcpc"),
        "arcade" to setOf("arcade"),
        "coleco_colecovision" to setOf("colecovision"),
        "gce_vectrex" to setOf("vectrex"),
        "mattel_intellivision" to setOf("intellivision"),
        "microsoft_msx" to setOf("msx"),
        "microsoft_msx2" to setOf("msx"),
        "the_3do_company_3do" to setOf("3do"),
    )

    /**
     * Canonical registry systems discovered outside the closed 45 + 14 shipping audit.
     *
     * Mapping provider identity is not a claim that Mimir can identify a local file. Jaguar CD remains
     * absent from [RetroAchievementsHash], so lookup reports IDENTIFICATION_UNAVAILABLE before file/network
     * work until its multi-session disc reader is explicitly certified.
     */
    private val discoverableConsoleKeysByPlatform: Map<String, Set<String>> = mapOf(
        "atari_jaguar_cd" to setOf("atarijaguarcd"),
    )

    private val allConsoleKeysByPlatform: Map<String, Set<String>> =
        consoleKeysByPlatform + discoverableConsoleKeysByPlatform

    /** Historical/runtime ids accepted at module boundaries while the registry finishes canonical rollout. */
    private val platformAliases = mapOf(
        "megadrive" to "genesis",
        "wii" to "nintendo_wii",
        "atari2600" to "atari_2600",
        "atari7800" to "atari_7800",
        "atarijaguar" to "atari_jaguar",
        "atarilynx" to "atari_lynx",
        "mastersystem" to "sega_master_system_mark_iii",
        "gamegear" to "sega_game_gear",
        "sg1000" to "sega_sg_1000",
        "sega32x" to "sega_32x",
        "segacd" to "sega_mega_cd_sega_cd",
        "pcengine" to "nec_pc_engine_turbografx_16",
        "pcenginecd" to "nec_pc_engine_cd_turbografx_cd",
        "ngp" to "snk_neo_geo_pocket",
        "ngpc" to "snk_neo_geo_pocket_color",
        "neogeocd" to "snk_neo_geo_cd",
        "virtualboy" to "nintendo_virtual_boy",
        "wonderswan" to "bandai_wonderswan",
        "wonderswancolor" to "bandai_wonderswan_color",
        "pokemini" to "nintendo_pokemon_mini",
        "3do" to "the_3do_company_3do",
    )

    /** Cosmetic/live endpoint aliases → one stable comparison key. */
    private val consoleKeyByName: Map<String, String> = mapOf(
        "3do" to "3do",
        "3dointeractivemultiplayer" to "3do",
        "amstradcpc" to "amstradcpc",
        "arcade" to "arcade",
        "atari2600" to "atari2600",
        "atari7800" to "atari7800",
        "atarijaguar" to "atarijaguar",
        "atarijaguarcd" to "atarijaguarcd",
        "atarilynx" to "atarilynx",
        "colecovision" to "colecovision",
        "dreamcast" to "dreamcast",
        "famicomdisksystem" to "famicomdisksystem",
        "gameboy" to "gameboy",
        "gameboyadvance" to "gameboyadvance",
        "gameboycolor" to "gameboycolor",
        "gamecube" to "gamecube",
        "gamegear" to "gamegear",
        "intellivision" to "intellivision",
        "mastersystem" to "mastersystem",
        "segamastersystem" to "mastersystem",
        "megadrive" to "segagenesis",
        "genesis" to "segagenesis",
        "segagenesis" to "segagenesis",
        "megadrivegenesis" to "segagenesis",
        "genesismegadrive" to "segagenesis",
        "msx" to "msx",
        "neogeocd" to "neogeocd",
        "neogeopocket" to "neogeopocket",
        "neogeopocketcolor" to "neogeopocket",
        "nintendo64" to "nintendo64",
        "nintendods" to "nintendods",
        "nintendodsi" to "nintendodsi",
        "nintendoentertainmentsystem" to "nintendoentertainmentsystem",
        "nes" to "nintendoentertainmentsystem",
        "famicom" to "nintendoentertainmentsystem",
        "nesfamicom" to "nintendoentertainmentsystem",
        "pcengine" to "pcengine",
        "pcengineturbografx16" to "pcengine",
        "turbografx16" to "pcengine",
        "pcenginecd" to "pcenginecd",
        "pcenginecdturbografxcd" to "pcenginecd",
        "pcfx" to "pcfx",
        "playstation" to "playstation",
        "playstation2" to "playstation2",
        "playstationportable" to "playstationportable",
        "psp" to "playstationportable",
        "pokemonmini" to "pokemonmini",
        "sega32x" to "sega32x",
        "32x" to "sega32x",
        "segacd" to "segacd",
        "segasaturn" to "segasaturn",
        "saturn" to "segasaturn",
        "sg1000" to "sg1000",
        "supernintendo" to "supernintendoentertainmentsystem",
        "supernintendoentertainmentsystem" to "supernintendoentertainmentsystem",
        "snes" to "supernintendoentertainmentsystem",
        "superfamicom" to "supernintendoentertainmentsystem",
        "snessuperfamicom" to "supernintendoentertainmentsystem",
        "vectrex" to "vectrex",
        "virtualboy" to "virtualboy",
        "wii" to "wii",
        "wonderswan" to "wonderswan",
        "wonderswancolor" to "wonderswan",
    )

    /** Primary Mimir identity used only by diagnostics/tests when an RA console fans out to several ids. */
    private val primaryPlatformByConsoleKey: Map<String, String> = mapOf(
        "3do" to "the_3do_company_3do",
        "amstradcpc" to "amstrad_cpc",
        "arcade" to "arcade",
        "atari2600" to "atari_2600",
        "atari7800" to "atari_7800",
        "atarijaguar" to "atari_jaguar",
        "atarijaguarcd" to "atari_jaguar_cd",
        "atarilynx" to "atari_lynx",
        "colecovision" to "coleco_colecovision",
        "dreamcast" to "dreamcast",
        "famicomdisksystem" to "nes",
        "gameboy" to "gb",
        "gameboyadvance" to "gba",
        "gameboycolor" to "gbc",
        "gamecube" to "gamecube",
        "gamegear" to "sega_game_gear",
        "intellivision" to "mattel_intellivision",
        "mastersystem" to "sega_master_system_mark_iii",
        "segagenesis" to "genesis",
        "msx" to "microsoft_msx",
        "neogeocd" to "snk_neo_geo_cd",
        "neogeopocket" to "snk_neo_geo_pocket",
        "nintendo64" to "n64",
        "nintendods" to "ds",
        "nintendodsi" to "ds",
        "nintendoentertainmentsystem" to "nes",
        "pcengine" to "nec_pc_engine_turbografx_16",
        "pcenginecd" to "nec_pc_engine_cd_turbografx_cd",
        "pcfx" to "nec_pc_fx",
        "playstation" to "ps1",
        "playstation2" to "ps2",
        "playstationportable" to "psp",
        "pokemonmini" to "nintendo_pokemon_mini",
        "sega32x" to "sega_32x",
        "segacd" to "sega_mega_cd_sega_cd",
        "segasaturn" to "saturn",
        "sg1000" to "sega_sg_1000",
        "supernintendoentertainmentsystem" to "snes",
        "vectrex" to "gce_vectrex",
        "virtualboy" to "nintendo_virtual_boy",
        "wii" to "nintendo_wii",
        "wonderswan" to "bandai_wonderswan",
    )

    /** True when RA exposes this canonical platform, including supplemental discoverable registry systems. */
    fun supportsPlatform(platformId: String): Boolean =
        canonicalPlatformId(platformId) in allConsoleKeysByPlatform

    /** The primary Mimir platform id for an RA console [name], or null when RA does not support it here. */
    fun platformIdFor(name: String): String? =
        consoleKey(name)?.let(primaryPlatformByConsoleKey::get)

    /** RA console id → primary Mimir platform id, for diagnostics and compatibility with the original seam. */
    fun resolve(consoleIds: Map<Int, String>): Map<Int, String> =
        consoleIds.mapNotNull { (id, name) -> platformIdFor(name)?.let { id to it } }.toMap()

    /** Every live RA console id that can serve [platformId], including shared/fanned-out identities. */
    fun consoleIdsFor(consoleIds: Map<Int, String>, platformId: String): List<Int> {
        val keys = allConsoleKeysByPlatform[canonicalPlatformId(platformId)] ?: return emptyList()
        return consoleIds.filter { (_, name) -> consoleKey(name) in keys }.keys.sorted()
    }

    private fun canonicalPlatformId(platformId: String): String {
        val raw = platformId.lowercase(Locale.US)
        return platformAliases[raw] ?: raw
    }

    internal fun platformAuthorityRows(): List<OnlineProviderPlatformRow> {
        val canonicalRows = allConsoleKeysByPlatform.map { (rawPlatformId, consoleKeys) ->
            OnlineProviderPlatformRow(
                provider = OnlinePlatformProvider.RETRO_ACHIEVEMENTS,
                rawPlatformId = rawPlatformId,
                declaredCanonicalPlatformId = authorityCanonicalPlatformId(rawPlatformId),
                providerKey = consoleKeys.sorted().joinToString(","),
            )
        }
        val aliasRows = platformAliases.map { (rawPlatformId, canonicalPlatformId) ->
            val consoleKeys = requireNotNull(allConsoleKeysByPlatform[canonicalPlatformId]) {
                "RetroAchievements alias $rawPlatformId targets unmapped platform $canonicalPlatformId"
            }
            OnlineProviderPlatformRow(
                provider = OnlinePlatformProvider.RETRO_ACHIEVEMENTS,
                rawPlatformId = rawPlatformId,
                declaredCanonicalPlatformId = authorityCanonicalPlatformId(canonicalPlatformId),
                providerKey = consoleKeys.sorted().joinToString(","),
            )
        }
        return (canonicalRows + aliasRows)
            .distinct()
            .sortedWith(
                compareBy(
                    { it.declaredCanonicalPlatformId },
                    { it.rawPlatformId },
                    { it.providerKey },
                ),
            )
    }

    private fun authorityCanonicalPlatformId(platformId: String): String =
        when (val canonical = canonicalPlatformId(platformId)) {
            // Registry keeps this compatibility id in the RetroArch-registration namespace.
            "neo_geo" -> "snk_neo_geo"
            else -> canonical
        }

    private fun consoleKey(name: String): String? = consoleKeyByName[normalize(name)]

    private fun normalize(name: String): String =
        name.lowercase(Locale.US).filter { it.isLetterOrDigit() }
}
