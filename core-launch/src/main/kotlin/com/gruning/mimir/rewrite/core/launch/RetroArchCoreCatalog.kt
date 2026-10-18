package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * Ordered libretro-core choices for every platform already served by Mimir's RetroArch launch seam.
 *
 * The catalog is a mechanical port of the shipping product's local-only core registry. The first
 * row for a platform is its existing recommended/default core; later rows are Open With choices.
 * A row says only that Mimir knows the core's launch artifact name. Android cannot reliably inspect
 * another app's private core directory, so this never claims that a core is installed.
 */
internal object RetroArchCoreCatalog {
    internal data class Core(
        /** Stem used by RetroArch's `<stem>_libretro_android.so` artifact. */
        val dispatchStem: String,
        val label: String,
        /** Stem in the shipping local registry, when the Android artifact uses a compatibility name. */
        val sourceStem: String = dispatchStem,
        /** Stable stem used in Mimir's persisted emulator id. */
        val persistedStem: String = sourceStem,
    )

    internal data class PlatformCores(
        val shippingPlatformId: String?,
        val cores: List<Core>,
    ) {
        init {
            require(cores.isNotEmpty()) { "A mapped RetroArch platform needs at least one core" }
            require(cores.map(Core::dispatchStem).distinct().size == cores.size) {
                "RetroArch dispatch stems must be unique within one platform"
            }
            require(cores.map(Core::persistedStem).distinct().size == cores.size) {
                "RetroArch persisted stems must be unique within one platform"
            }
        }
    }

    private fun shipping(platformId: String, vararg cores: Core) =
        PlatformCores(shippingPlatformId = platformId, cores = cores.toList())

    private fun extension(vararg cores: Core) =
        PlatformCores(shippingPlatformId = null, cores = cores.toList())

    /** Runtime ids intentionally use the classifier's established rewrite ids. */
    internal val byPlatform: Map<PlatformId, PlatformCores> = linkedMapOf(
        PlatformId.ARCADE to shipping(
            "arcade",
            Core("fbneo", "FBNeo"),
            Core("mame", "MAME"),
            Core("mame2003_plus", "MAME 2003-Plus"),
            Core("mame2010", "MAME 2010"),
            Core("mame2003", "MAME 2003"),
            Core("mame2000", "MAME 2000"),
            Core("mame2015", "Mame2015"),
            Core("mame2016", "Mame2016"),
            Core("fbalpha2012", "FB Alpha 2012"),
            Core("hbmame", "Hbmame"),
        ),
        PlatformId.GB to shipping(
            "gb",
            Core("gambatte", "Gambatte"),
            Core("sameboy", "SameBoy"),
            Core("gearboy", "Gearboy"),
            Core("tgbdual", "TGB Dual"),
            Core("mgba", "mGBA"),
            Core("doublecherrygb", "DoubleCherryGB"),
        ),
        PlatformId.GBC to shipping(
            "gbc",
            Core("gambatte", "Gambatte"),
            Core("sameboy", "SameBoy"),
            Core("gearboy", "Gearboy"),
            Core("tgbdual", "TGB Dual"),
            Core("mesen-s", "Mesen-S"),
            Core("doublecherrygb", "DoubleCherryGB"),
        ),
        PlatformId.GBA to shipping(
            "gba",
            Core("mgba", "mGBA"),
            Core("gpsp", "gpSP"),
            Core("vba_next", "VBA Next"),
            Core("vbam", "VBA-M"),
            Core("mednafen_gba", "Mednafen GBA"),
            Core("meteor", "Meteor"),
            Core("skyemu", "SkyEmu"),
        ),
        PlatformId("megadrive") to shipping(
            "genesis",
            Core("genesis_plus_gx_wide", "Genesis Plus GX Wide"),
            Core("genesis_plus_gx", "Genesis Plus GX"),
            Core("picodrive", "PicoDrive"),
        ),
        PlatformId.NES to shipping(
            "nes",
            Core("nestopia", "Nestopia"),
            Core("mesen", "Mesen"),
            Core("fceumm", "FCEUmm"),
            Core("quicknes", "QuickNES"),
        ),
        PlatformId.SNES to shipping(
            "snes",
            Core("snes9x", "Snes9x"),
            Core("snes9x2010", "Snes9x 2010"),
            Core("snes9x2005", "Snes9x2005"),
            Core("snes9x2005_plus", "Snes9x2005 Plus"),
            Core("bsnes", "Bsnes"),
            Core("mesen-s", "Mesen-S"),
            Core("mednafen_supafaust", "Mednafen Supafaust"),
        ),
        PlatformId.N64 to shipping(
            "n64",
            Core("mupen64plus_next_gles3", "Mupen64Plus-Next GLES3"),
            Core("mupen64plus_next_gles2", "Mupen64Plus-Next GLES2"),
            Core("parallel_n64", "ParaLLEl N64"),
        ),
        PlatformId.PS1 to shipping(
            "ps1",
            Core("pcsx_rearmed", "PCSX ReARMed"),
            Core("swanstation", "SwanStation"),
            Core("mednafen_psx_hw", "Beetle PSX HW"),
            Core("mednafen_psx", "Beetle PSX"),
        ),
        PlatformId.SATURN to shipping(
            "saturn",
            Core(
                dispatchStem = "mednafen_saturn",
                label = "Beetle Saturn",
                sourceStem = "beetle_saturn",
            ),
            Core("kronos", "Kronos"),
            Core("yabause", "Yabause"),
        ),
        PlatformId("mastersystem") to shipping(
            "sega_master_system_mark_iii",
            Core("genesis_plus_gx", "Genesis Plus GX"),
            Core("gearsystem", "Gearsystem"),
            Core("picodrive", "PicoDrive"),
        ),
        PlatformId("gamegear") to shipping(
            "sega_game_gear",
            Core("genesis_plus_gx", "Genesis Plus GX"),
            Core("gearsystem", "Gearsystem"),
        ),
        PlatformId("sg1000") to shipping(
            "sega_sg_1000",
            Core("genesis_plus_gx", "Genesis Plus GX"),
            Core("gearsystem", "Gearsystem"),
            Core("bluemsx", "Bluemsx"),
        ),
        PlatformId("segacd") to shipping(
            "sega_mega_cd_sega_cd",
            Core("genesis_plus_gx", "Genesis Plus GX"),
            Core("picodrive", "PicoDrive"),
        ),
        PlatformId("sega32x") to shipping("sega_32x", Core("picodrive", "PicoDrive")),
        PlatformId("pcengine") to shipping(
            "nec_pc_engine_turbografx_16",
            Core("mednafen_pce_fast", "Mednafen PCE Fast"),
            Core("mednafen_pce", "Mednafen PCE"),
        ),
        PlatformId("pcenginecd") to shipping(
            "nec_pc_engine_cd_turbografx_cd",
            Core("mednafen_pce_fast", "Mednafen PCE Fast"),
            Core("mednafen_pce", "Mednafen PCE"),
        ),
        PlatformId("atari2600") to shipping(
            "atari_2600",
            Core("stella", "Stella"),
            Core("stella2014", "Stella 2014"),
        ),
        PlatformId("atari7800") to shipping("atari_7800", Core("prosystem", "ProSystem")),
        PlatformId("atarilynx") to shipping(
            "atari_lynx",
            Core("mednafen_lynx", "Beetle Lynx"),
            Core("handy", "Handy"),
        ),
        PlatformId("atarijaguar") to shipping(
            "atari_jaguar",
            Core("virtualjaguar", "Virtual Jaguar"),
        ),
        PlatformId("wonderswan") to shipping(
            "bandai_wonderswan",
            Core("mednafen_wswan", "Beetle WonderSwan"),
        ),
        PlatformId("wonderswancolor") to shipping(
            "bandai_wonderswan_color",
            Core("mednafen_wswan", "Beetle WonderSwan"),
        ),
        PlatformId("ngp") to shipping(
            "snk_neo_geo_pocket",
            Core("mednafen_ngp", "Beetle NeoPop"),
            Core("race", "RACE"),
        ),
        PlatformId("ngpc") to shipping(
            "snk_neo_geo_pocket_color",
            Core("mednafen_ngp", "Beetle NeoPop"),
            Core("race", "RACE"),
        ),
        PlatformId("virtualboy") to shipping(
            "nintendo_virtual_boy",
            Core("mednafen_vb", "Beetle VB"),
        ),
        PlatformId("pokemini") to shipping(
            "nintendo_pokemon_mini",
            Core("pokemini", "PokéMini"),
        ),
        PlatformId("atari_5200") to shipping(
            "atari_5200",
            Core("a5200", "Atari 5200"),
            Core("atari800", "Atari800"),
        ),
        PlatformId("coleco_colecovision") to shipping(
            "coleco_colecovision",
            Core("gearcoleco", "Gearcoleco"),
            Core("bluemsx", "blueMSX"),
            Core("fbneo", "FBNeo"),
        ),
        PlatformId("commodore_64") to shipping(
            "commodore_64",
            Core("vice_x64", "VICE x64"),
            Core("vice_x64sc", "VICE x64sc"),
        ),
        PlatformId("commodore_amiga") to shipping(
            "commodore_amiga",
            Core("puae", "PUAE"),
            Core("puae2021", "PUAE 2021"),
        ),
        PlatformId("gce_vectrex") to shipping("gce_vectrex", Core("vecx", "vecx")),
        PlatformId("microsoft_msx") to shipping(
            "microsoft_msx",
            Core("bluemsx", "blueMSX"),
            Core("fmsx", "fMSX"),
        ),
        PlatformId("microsoft_msx2") to shipping(
            "microsoft_msx2",
            Core("bluemsx", "blueMSX"),
            Core("fmsx", "fMSX"),
        ),
        PlatformId("nec_pc_engine_supergrafx") to shipping(
            "nec_pc_engine_supergrafx",
            Core("mednafen_supergrafx", "Beetle SuperGrafx"),
            Core("mednafen_pce", "Beetle PCE"),
        ),
        PlatformId("nec_pc_fx") to shipping(
            "nec_pc_fx",
            Core("mednafen_pcfx", "Beetle PC-FX"),
        ),
        PlatformId("sinclair_zx_spectrum") to shipping(
            "sinclair_zx_spectrum",
            Core("fuse", "Fuse"),
        ),
        PlatformId("snk_neo_geo_cd") to shipping("snk_neo_geo_cd", Core("neocd", "NeoCD")),

        // These four are Mimir Alpha's later mapped completion rows. They retain their proven primary
        // artifacts even though the frozen shipping 49-platform registry did not carry registrations.
        PlatformId("commodore_cd32") to extension(Core("puae", "PUAE")),
        PlatformId("nintendo_family_computer_disk_system") to extension(Core("nestopia", "Nestopia")),
        PlatformId("philips_cd_i") to extension(Core("same_cdi", "SAME CD-i")),
        PlatformId("sharp_x68000") to extension(Core("px68k", "PX68k")),
    )

    internal val recommendedByPlatform: Map<PlatformId, String> = byPlatform.mapValues { (_, value) ->
        value.cores.first().dispatchStem
    }

    internal val additionalByPlatform: Map<PlatformId, List<String>> = byPlatform.mapValues { (_, value) ->
        value.cores.drop(1).map(Core::dispatchStem)
    }

    internal fun displayName(platformId: PlatformId, dispatchStem: String): String? =
        byPlatform[platformId]?.cores?.firstOrNull { it.dispatchStem == dispatchStem }?.label

    internal fun persistedStem(platformId: PlatformId, dispatchStem: String): String =
        byPlatform[platformId]?.cores
            ?.firstOrNull { it.dispatchStem == dispatchStem }
            ?.persistedStem
            ?: dispatchStem

    internal fun coreCount(): Int = byPlatform.values.sumOf { it.cores.size }
}
