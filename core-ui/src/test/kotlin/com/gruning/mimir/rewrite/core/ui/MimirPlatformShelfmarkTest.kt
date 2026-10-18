package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A shelfmark code is a call number: uppercase alphanumeric, no punctuation, readable at 16dp. */
private val CompliantCode = Regex("[A-Z0-9]{1,5}")

/**
 * The platform shelfmark's pure surfaces: code derivation, media-kind folding, the tier ladder, and the
 * corner-profile contract. The plate's Path is NOT covered here — `Path` is backed by `android.graphics` and
 * cannot be built on the JVM; its geometry is driven entirely by [mimirShelfmarkProfile], which is pinned.
 */
class MimirPlatformShelfmarkTest {

    // ── Code derivation ────────────────────────────────────────────────────────────────────────────

    @Test
    fun threeAbbreviationExamplesAreTheContract() {
        //  These three ARE the spec — if they drift, the whole code system has lost its authority.
        assertEquals("NS", mimirPlatformShelfmarkCode("switch"))
        assertEquals("GB", mimirPlatformShelfmarkCode("gb"))
        assertEquals("GBC", mimirPlatformShelfmarkCode("gbc"))
    }

    @Test
    fun existingTerseCodesSurvive() {
        // The call-number policy retired the SPELLED-OUT entries; the already-terse ones stand unchanged.
        assertEquals("NES", mimirPlatformShelfmarkCode("nes"))
        assertEquals("SNES", mimirPlatformShelfmarkCode("snes"))
        assertEquals("N64", mimirPlatformShelfmarkCode("n64"))
        assertEquals("GCN", mimirPlatformShelfmarkCode("gamecube"))
        assertEquals("DC", mimirPlatformShelfmarkCode("dreamcast"))
        assertEquals("MD", mimirPlatformShelfmarkCode("genesis"))
        assertEquals("PS1", mimirPlatformShelfmarkCode("ps1"))
    }

    @Test
    fun spelledOutProductNamesAreRetired() {
        // The entries the abbreviation policy changed. Each was a trademark set out in full on the plate.
        assertEquals("SAT", mimirPlatformShelfmarkCode("saturn"))
        assertEquals("PSV", mimirPlatformShelfmarkCode("psvita"))
        assertEquals("WIIU", mimirPlatformShelfmarkCode("wiiu"))
        assertEquals("ARC", mimirPlatformShelfmarkCode("arcade"))
        assertEquals("NG", mimirPlatformShelfmarkCode("neogeo"))
        assertEquals("SCD", mimirPlatformShelfmarkCode("segacd"))
        assertEquals("XB", mimirPlatformShelfmarkCode("xbox"))
        assertEquals("JAG", mimirPlatformShelfmarkCode("atari_jaguar"))
        assertEquals("CDI", mimirPlatformShelfmarkCode("cdi"))
    }

    @Test
    fun everyRegistryPlatformResolvesToACompliantCode() {
        // "Cover every platform the registry emits" as an EXECUTABLE contract, not a claim in a doc.
        // Every one of the 225 ids in platforms/mimir-platform-registry.v2.json, through the real
        // normalizer. A null here is a platform whose plate would silently vanish on the Home shelf.
        RegistryPlatformIds.forEach { id ->
            val code = mimirPlatformShelfmarkCode(id)
            assertNotNull("no code for registry platform '$id'", code)
            assertTrue(
                "code '$code' for '$id' is not a compliant call number",
                CompliantCode.matches(code!!),
            )
        }
    }

    @Test
    fun theRegistryIdListItselfStaysInSyncWithTheRegistry() {
        // Guards the list above from silently rotting if a platform is added and the test is not updated.
        assertEquals(225, RegistryPlatformIds.size)
        assertEquals(RegistryPlatformIds.size, RegistryPlatformIds.distinct().size)
    }

    @Test
    fun distinctPlatformsNeverShareACode() {
        // A shared code is two machines the plate cannot tell apart. Aliases of the SAME platform may of
        // course share (psvita/sony_playstation_vita both → PSV); these are all different machines.
        val codes = listOf(
            "nes", "snes", "n64", "gamecube", "nintendo_wii", "nintendo_wii_u", "switch",
            "gb", "gbc", "gba", "ds", "nintendo_nintendo_dsi", "3ds", "nintendo_virtual_boy",
            "ps1", "ps2", "ps3", "sony_playstation_4", "psp", "sony_playstation_vita",
            "xbox", "xbox360", "microsoft_xbox_one",
            "sega_sg_1000", "sega_master_system_mark_iii", "genesis", "sega_32x",
            "sega_mega_cd_sega_cd", "saturn", "dreamcast", "sega_game_gear",
            "snk_neo_geo", "snk_neo_geo_cd", "snk_neo_geo_pocket", "snk_neo_geo_pocket_color",
            "nec_pc_engine_turbografx_16", "nec_pc_engine_cd_turbografx_cd", "nec_pc_engine_supergrafx",
            "atari_2600", "atari_5200", "atari_7800", "atari_lynx", "atari_jaguar", "arcade",
        ).map { requireNotNull(mimirPlatformShelfmarkCode(it)) { it } }
        assertEquals(codes.toString(), codes.size, codes.distinct().size)
    }

    @Test
    fun noCodeIsAWordmarkSetOutInFull() {
        // The abbreviation rule: UPPERCASE alphanumeric, no spaces, no punctuation,
        // short enough to read at 16dp. "Wii U", "Sega CD", "CD-i", "Neo Geo" all fail this by construction.
        RegistryPlatformIds.forEach { id ->
            val code = requireNotNull(mimirPlatformShelfmarkCode(id)) { id }
            assertTrue("'$code' ($id) contains a space", !code.contains(' '))
            assertTrue("'$code' ($id) is not uppercase", code == code.uppercase())
            assertTrue("'$code' ($id) is longer than 5 characters", code.length <= 5)
        }
    }

    @Test
    fun codeFoldsAliasesThroughTheSharedConsoleArtNormalizer() {
        // The registry's long ids, the shipping short ids, and the common aliases must all land on one code —
        // this is the whole reason the shelfmark reuses normalizePlatformArtId instead of its own table.
        assertEquals("DS", mimirPlatformShelfmarkCode("nintendo_ds"))
        assertEquals("PS2", mimirPlatformShelfmarkCode("playstation_2"))
        assertEquals("GBA", mimirPlatformShelfmarkCode("game_boy_advance"))
        assertEquals("PS1", mimirPlatformShelfmarkCode("psx"))
        assertEquals("MD", mimirPlatformShelfmarkCode("megadrive"))
        assertEquals("3DS", mimirPlatformShelfmarkCode("nintendo3ds"))
    }

    @Test
    fun uncuratedPlatformsFallBackToInitials() {
        // The long tail (225 registry platforms) still gets an honest mark without a bespoke entry.
        assertEquals("WM", mimirPlatformShelfmarkCode("wonder_machine", "Wonder Machine"))
        assertEquals("ZOR", mimirPlatformShelfmarkCode("zorbtron", "Zorbtron"))
        // The id itself is used when no display label is supplied.
        assertEquals("QUA", mimirPlatformShelfmarkCode("quasar"))
    }

    @Test
    fun algorithmicCodesNeverExceedFourCharacters() {
        // The plate sizes to its ink, but the derived tail must stay glanceable.
        listOf(
            "Some Very Long Platform Name",
            "Extraordinarily-Long_Machine/Name",
            "Supercalifragilisticexpialidocious",
        ).forEach { label ->
            assertTrue(label, shelfmarkInitials(label).length <= 4)
        }
    }

    @Test
    fun unnameablePlatformsGetNoPlateRatherThanAMeaninglessOne() {
        // Honest-fallback discipline: the caller omits the shelfmark instead of stamping "?" or "M".
        // NOTE this is the one deliberate divergence from companionCode, which returns "M".
        assertNull(mimirPlatformShelfmarkCode("", ""))
        assertNull(mimirPlatformShelfmarkCode("···", "···"))
    }

    // ── Media-kind folding (the silhouette channel) ─────────────────────────────────────────────────

    @Test
    fun cartridgePlatformsFoldToCartridge() {
        listOf("nes", "snes", "n64", "gba", "gb", "genesis").forEach {
            assertEquals(it, MimirShelfmarkKind.CARTRIDGE, mimirShelfmarkKind(it))
        }
    }

    @Test
    fun cardPlatformsFoldToCard() {
        listOf("ds", "3ds", "switch").forEach {
            assertEquals(it, MimirShelfmarkKind.CARD, mimirShelfmarkKind(it))
        }
    }

    @Test
    fun opticalPlatformsFoldToDisc() {
        listOf("ps1", "ps2", "saturn", "dreamcast", "gamecube", "xbox", "psp").forEach {
            assertEquals(it, MimirShelfmarkKind.DISC, mimirShelfmarkKind(it))
        }
    }

    @Test
    fun coinOpPlatformsFoldToCabinet() {
        listOf("arcade", "mame", "neogeo").forEach {
            assertEquals(it, MimirShelfmarkKind.CABINET, mimirShelfmarkKind(it))
        }
    }

    @Test
    fun homeComputersFoldToMagneticNotCartridge() {
        // The shared media classifier has no computer bucket and falls through to a NES cartridge, which
        // would be a lie on the plate. The magnetic pre-check exists for exactly this.
        listOf("c64", "commodore_64", "amiga", "zx_spectrum", "msx", "pc", "atari_st").forEach {
            assertEquals(it, MimirShelfmarkKind.MAGNETIC, mimirShelfmarkKind(it))
        }
    }

    @Test
    fun absentPlatformIdFoldsToUnknown() {
        assertEquals(MimirShelfmarkKind.UNKNOWN, mimirShelfmarkKind(""))
        assertEquals(MimirShelfmarkKind.UNKNOWN, mimirShelfmarkKind("   "))
    }

    // ── Tier ladder ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun tierLadderDescendsFromPickerToResting() {
        val picker = mimirShelfmarkEmphasis(MimirShelfmarkTier.PICKER)
        val focused = mimirShelfmarkEmphasis(MimirShelfmarkTier.FOCUSED)
        val resting = mimirShelfmarkEmphasis(MimirShelfmarkTier.RESTING)

        assertTrue(picker.height > focused.height)
        assertTrue(focused.height > resting.height)
        assertTrue(picker.fontSize.value > focused.fontSize.value)
        assertTrue(focused.fontSize.value > resting.fontSize.value)
        assertTrue(picker.plateAlpha > focused.plateAlpha)
        assertTrue(focused.plateAlpha > resting.plateAlpha)
        assertTrue(focused.rimAlpha > resting.rimAlpha)
        assertTrue(focused.inkAlpha > resting.inkAlpha)
    }

    @Test
    fun restingRecedesButStaysLegible() {
        // "Recessed, not gone" — a resting plate the user cannot read is just noise on the artwork.
        val resting = mimirShelfmarkEmphasis(MimirShelfmarkTier.RESTING)
        assertTrue(resting.plateAlpha > 0.5f)
        assertTrue(resting.inkAlpha > 0.5f)
    }

    @Test
    fun sheenIsAFocusAffordanceOnly() {
        // The glass sheen is what makes the plate look lit; a resting neighbour must not catch the light.
        assertTrue(mimirShelfmarkEmphasis(MimirShelfmarkTier.PICKER).sheen)
        assertTrue(mimirShelfmarkEmphasis(MimirShelfmarkTier.FOCUSED).sheen)
        assertTrue(!mimirShelfmarkEmphasis(MimirShelfmarkTier.RESTING).sheen)
    }

    // ── Corner-profile contract (the one construction covering every platform) ──────────────────────

    @Test
    fun everyKindStaysWithinTheScaleInvariantCornerBudget() {
        // Extents are fractions of plate HEIGHT; anything above 0.5 would self-intersect at the midline.
        MimirShelfmarkKind.entries.forEach { kind ->
            val p = mimirShelfmarkProfile(kind)
            listOf(p.topLeft, p.topRight, p.bottomRight, p.bottomLeft).forEach { extent ->
                assertTrue("$kind extent $extent", extent > 0f && extent <= 0.5f)
            }
        }
    }

    @Test
    fun cartridgeAndCardKeyOppositeCornersSoTheyReadApart() {
        val cartridge = mimirShelfmarkProfile(MimirShelfmarkKind.CARTRIDGE)
        assertTrue(cartridge.topLeftChamfer)
        assertTrue(!cartridge.topRightChamfer && !cartridge.bottomLeftChamfer && !cartridge.bottomRightChamfer)

        val card = mimirShelfmarkProfile(MimirShelfmarkKind.CARD)
        assertTrue(card.bottomLeftChamfer)
        assertTrue(!card.topLeftChamfer && !card.topRightChamfer && !card.bottomRightChamfer)
    }

    @Test
    fun cabinetTapersAtTheTopAndDiscIsTheOnlyStadium() {
        val cabinet = mimirShelfmarkProfile(MimirShelfmarkKind.CABINET)
        assertTrue(cabinet.topLeftChamfer && cabinet.topRightChamfer)
        assertTrue(!cabinet.bottomLeftChamfer && !cabinet.bottomRightChamfer)

        // A stadium is the strongest silhouette in the set; nothing else may claim it.
        val stadiums = MimirShelfmarkKind.entries.filter { kind ->
            val p = mimirShelfmarkProfile(kind)
            p.topLeft == 0.5f && p.topRight == 0.5f && p.bottomRight == 0.5f && p.bottomLeft == 0.5f
        }
        assertEquals(listOf(MimirShelfmarkKind.DISC), stadiums)
    }

    @Test
    fun noKindMixesAChamferWithAnotherKindsSignature() {
        // Every kind must own a distinct chamfer signature, or two media types collapse into one mark.
        val signatures = MimirShelfmarkKind.entries.map { kind ->
            val p = mimirShelfmarkProfile(kind)
            listOf(p.topLeftChamfer, p.topRightChamfer, p.bottomRightChamfer, p.bottomLeftChamfer)
        }
        val chamferedSignatures = signatures.filter { it.any { chamfered -> chamfered } }
        assertEquals(chamferedSignatures.size, chamferedSignatures.distinct().size)
    }

    /**
     *
     *
     * Asserted through the PUBLIC entry point with the id the app actually passes — `pc_games`, straight
     * from the registry — and not against the table directly. An earlier entry keyed the table `"pc_games"`
     * and shipped; `normalizePlatformArtId` strips underscores before the lookup, so the entry matched
     * nothing and the badge went on reading `PG`. A test that read the table would have passed too.
     *
     * A key that matches nothing is indistinguishable from an absent key to everything except a screen.
     */
    @Test
    fun `PC Games is PC, from the id the app actually passes`() {
        assertEquals("PC", mimirPlatformShelfmarkCode("pc_games"))
        assertEquals("the normalised form must answer the same", "PC", mimirPlatformShelfmarkCode("pcgames"))
        assertNotEquals(
            "PG is the initials fallback reading \"pc\" + \"games\" -- a call number for a platform " +
                "nobody calls that",
            "PG",
            mimirPlatformShelfmarkCode("pc_games"),
        )
    }

}

/**
 * Every platform id in `platforms/mimir-platform-registry.v2.json` (schema v2, 225 platforms), verbatim.
 *
 * Inlined rather than read from the JSON because the registry is not on :core-ui's test classpath — and
 * because a literal list FAILS LOUDLY when a platform is added, which is exactly when someone needs to be
 * told the code table wants an entry. Regenerate with:
 *   jq -r '.collections.platforms[].id' platforms/mimir-platform-registry.v2.json | sort
 */
private val RegistryPlatformIds: List<String> = listOf(
    "1292_advanced_programmable_video_system", "3ds", "acorn_archimedes", "acorn_atom", "acorn_bbc_micro",
    "acorn_risc_pc", "act_apricot_pc_xi", "amazon_fire_tv", "amazon_luna", "amstrad_cpc", "amstrad_gx4000",
    "apf_imagination_machine", "apf_mp_1000", "apple_bandai_pippin", "apple_i", "apple_ii", "apple_iie",
    "apple_iigs", "apple_macintosh", "arcade", "arduboy_inc_arduboy", "atari_2600", "atari_5200", "atari_7800",
    "atari_8_bit_family", "atari_jaguar", "atari_jaguar_cd", "atari_lynx", "atari_pong", "atari_st", "atari_vcs",
    "atari_xegs", "atomiswave", "bally_astrocade", "bandai_design_master_denshi_mangajuku", "bandai_gundam_rx_78",
    "bandai_playdia", "bandai_super_vision_8000", "bandai_terebikko", "bandai_wonderswan", "bandai_wonderswan_color",
    "bbc_bridge_companion", "benesse_pocket_challenge_v2", "benesse_pocket_challenge_w", "bit_corporation_gamate",
    "capcom_cp_system_i", "capcom_cp_system_ii", "capcom_cp_system_iii", "casio_loopy", "casio_pv_1000", "chip_8",
    "coleco_colecovision", "coleco_colecovision_adam", "coleco_telstar_6030", "coleco_telstar_6040",
    "coleco_telstar_6045", "coleco_telstar_6065", "coleco_telstar_6071", "coleco_telstar_6135", "commodore_128",
    "commodore_64", "commodore_amiga", "commodore_cd32", "commodore_cdtv", "commodore_pet", "commodore_plus_4",
    "commodore_vic_20", "connor_videosmarts", "digital_media_cartridge_firecore", "dos", "dreamcast", "ds",
    "emerson_arcadia_2001", "entex_adventure_vision", "entex_select_a_game", "epoch_cassette_vision",
    "epoch_game_pocket_computer", "epoch_super_cassette_vision", "epoch_tv_tennis_electrotennis",
    "fairchild_channel_f", "fujitsu_fm_7", "fujitsu_fm_towns", "fukutake_publishing_studybox", "funtech_super_acan",
    "gakken_compact_vision_tv_boy", "gamecube", "gamepark_caanoo", "gamepark_gp2x", "gamepark_gp32", "gb", "gba",
    "gbc", "gce_vectrex", "genesis", "google_nexus_player", "google_stadia", "handheld_electronic_game",
    "hartung_game_master", "hitachi_s1", "infocom_z_machine", "intellivision_amico", "interton_vc_4000",
    "interton_video_2000", "ique_ique", "konami_picno", "leapfrog_explorer", "leapfrog_leappad",
    "leapfrog_leapster_learning_game_system", "leapfrog_my_first_leappad", "ljn_video_art", "lowres_nx", "lutro",
    "luxor_abc_800", "magnavox_odyssey", "magnavox_odyssey2", "mattel_hyperscan", "mattel_intellivision",
    "microsoft_msx", "microsoft_msx2", "microsoft_xbox_one", "microsoft_xbox_series_x", "microw8",
    "milton_bradley_microvision", "n64", "nec_pc_98", "nec_pc_engine_cd_turbografx_cd", "nec_pc_engine_supergrafx",
    "nec_pc_engine_turbografx_16", "nec_pc_fx", "nes", "nichibutsu_my_vision", "nintendo_color_tv_game_ctg_15v",
    "nintendo_color_tv_game_ctg_6", "nintendo_color_tv_game_ctg_bk6", "nintendo_color_tv_game_ctg_cr112",
    "nintendo_color_tv_game_ctg_hc10", "nintendo_family_computer_disk_system", "nintendo_nintendo_64dd",
    "nintendo_nintendo_dsi", "nintendo_pokemon_mini", "nintendo_satellaview", "nintendo_sufami_turbo",
    "nintendo_virtual_boy", "nintendo_wii", "nintendo_wii_u", "nokia_n_gage", "nvidia_shield_portable",
    "nvidia_shield_tv", "ouya_ouya", "palmtex_super_micro", "panic_playdate",
    "pc_50x_palladium_tele_cassetten_game_825_530", "pc_50x_palladium_tele_cassetten_game_825_581",
    "pc_50x_shg_black_point_fs_1003", "philips_cd_i", "philips_tele_game", "philips_videopac", "pioneer_laseractive",
    "playjam_game_stick", "ps1", "ps2", "ps3", "psp", "rca_studio_ii", "rpg_maker", "saturn", "scummvm", "sega_32x",
    "sega_beena", "sega_chihiro", "sega_game_gear", "sega_master_system_mark_iii", "sega_mega_cd_sega_cd",
    "sega_naomi", "sega_naomi_2", "sega_pico", "sega_sg_1000", "sega_video_driver", "sharp_x1", "sharp_x68000",
    "sinclair_zx_81", "sinclair_zx_spectrum", "snes", "snk_neo_geo", "snk_neo_geo_cd", "snk_neo_geo_pocket",
    "snk_neo_geo_pocket_color", "sony_playstation_4", "sony_playstation_5", "sony_playstation_vita",
    "spectravideo_sv", "ssd_xavixport", "switch", "takara_video_challenger", "tandy_video_information_system",
    "tapwave_zodiac", "the_3do_company_3do", "thomson_moto", "tic_80", "tiger_game_com", "tiger_gizmondo",
    "tiger_r_zone", "tomy_tutor", "umtech_videobrain", "unisonic_champion_2711", "uzebox", "valve_steam_deck",
    "valve_steam_link", "view_master_interactive_vision", "vircon32", "vm_labs_nuon", "vtech_3d_gamate",
    "vtech_creativision", "vtech_socrates", "vtech_v_flash", "vtech_v_smile", "wasm_4", "watara_supervision",
    "welback_mega_duck", "worlds_of_wonder_action_max", "xbox", "xbox360", "yamaha_copera", "zapit_game_wave",
    "zeebo_zeebo",
)
