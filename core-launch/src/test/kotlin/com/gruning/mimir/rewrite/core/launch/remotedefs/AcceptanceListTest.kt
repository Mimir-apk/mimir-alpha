package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.GB
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.PPSSPP
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.PS2
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.PSP
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.RECOGNIZE_ONLY
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.RETROARCH
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * which files an emulator's list accepts. No rows at all means no list (null: every file, as today); the first row
 * narrows it to a list and later rows only add to it, bundled rows first and the overlay's on top.
 */
class AcceptanceListTest {
    private val bundled = TestBundled.small()

    private fun overlay(ordinal: Long, vararg rows: OverlayRow) = ValidatedOverlay(1, ordinal, "raw", "canonical", rows.toList())

    private fun effective(vararg rows: OverlayRow, from: BundledDefinitions = bundled, ordinal: Long = 3): EffectiveDefinitions =
        DefinitionsMerge.merge(from, if (rows.isEmpty()) null else overlay(ordinal, *rows))

    private fun bundledWith(
        extensions: Map<PlatformId, Set<String>> = emptyMap(),
        regexes: Map<PlatformId, Set<String>> = emptyMap(),
        baselineOrdinal: Long = 0L,
    ): BundledDefinitions {
        val base = TestBundled.small(baselineOrdinal)
        return base.copy(
            seeds = base.seeds.map { if (it.packageName == PPSSPP) it.copy(listedExtensions = extensions, listedRegexes = regexes) else it },
        )
    }

    // ---- the truth table ----------------------------------------------------------------------------------------

    @Test
    fun with_no_rows_anywhere_no_emulator_has_a_list_and_every_file_is_accepted() {
        val none = effective()
        for (name in listOf("game.iso", "GAME.PBP", "noext", "", ".hidden", "a".repeat(300))) {
            assertNull("no list for $name", none.listsFile(PPSSPP, PSP, name))
            assertNull(none.listsFile(RETROARCH, GB, name))
            assertNull(none.listsFile(FLYCAST, PlatformId("dreamcast"), name))
        }
        assertFalse(none.hasAcceptanceList(PPSSPP, PSP))
    }

    @Test
    fun the_first_extension_row_narrows_acceptance_to_a_list() {
        val e = effective(ExtensionsRow(PPSSPP, PSP, listOf("cso", "pbp")))
        assertTrue(e.hasAcceptanceList(PPSSPP, PSP))
        val table = listOf(
            "Game.PBP" to true,
            "game.cso" to true,
            "archive.tar.pbp" to true,
            "GAME.CSO" to true,
            "game.iso" to false,
            "game.pbp.bak" to false,
            "pbp" to false,
            "noext" to false,
            "" to false,
        )
        for ((name, expected) in table) assertEquals("listsFile($name)", expected, e.listsFile(PPSSPP, PSP, name))
        // Other platforms and other emulators still have no list.
        assertNull(e.listsFile(PPSSPP, PS2, "game.iso"))
        assertNull(e.listsFile(FLYCAST, PSP, "game.iso"))
        assertNull(e.listsFile(RETROARCH, GB, "game.iso"))
    }

    @Test
    fun a_filename_rule_alone_is_a_list_too() {
        val e = effective(FilenameRegexRow(PPSSPP, PSP, "^eboot\\.bin\$"))
        assertEquals(true, e.listsFile(PPSSPP, PSP, "EBOOT.BIN"))
        assertEquals(false, e.listsFile(PPSSPP, PSP, "game.iso"))
        assertEquals(false, e.listsFile(PPSSPP, PSP, "xeboot.bin"))
    }

    @Test
    fun extension_rows_and_filename_rules_add_to_each_other() {
        val e = effective(
            ExtensionsRow(PPSSPP, PSP, listOf("cso")),
            ExtensionsRow(PPSSPP, GB, listOf("gb")),
            FilenameRegexRow(PPSSPP, PSP, "^eboot\\.bin\$"),
            FilenameRegexRow(PPSSPP, PSP, "^.*\\.(?:pbp|elf)\$"),
        )
        assertEquals(true, e.listsFile(PPSSPP, PSP, "a.cso"))
        assertEquals(true, e.listsFile(PPSSPP, PSP, "EBOOT.BIN"))
        assertEquals(true, e.listsFile(PPSSPP, PSP, "a.pbp"))
        assertEquals(true, e.listsFile(PPSSPP, PSP, "a.elf"))
        assertEquals(false, e.listsFile(PPSSPP, PSP, "a.iso"))
        // The GB row named a platform PPSSPP does not serve: dropped, it never reaches a platform the APK does not serve.
        assertNull(e.listsFile(PPSSPP, GB, "a.gb"))
    }

    @Test
    fun a_name_over_255_characters_is_not_on_any_list_but_the_list_still_exists() {
        val e = effective(ExtensionsRow(PPSSPP, PSP, listOf("iso")), FilenameRegexRow(PPSSPP, PSP, "^.*\$"))
        assertEquals(true, e.listsFile(PPSSPP, PSP, "a".repeat(251) + ".iso"))
        assertEquals(false, e.listsFile(PPSSPP, PSP, "a".repeat(252) + ".iso"))
        assertEquals(false, e.listsFile(PPSSPP, PSP, "a".repeat(400)))
    }

    @Test
    fun a_rule_that_cannot_be_compiled_matches_nothing_and_never_throws() {
        // The parser refuses this regex; the merge is given it directly, as a corrupt stored copy could.
        val e = effective(FilenameRegexRow(PPSSPP, PSP, "^(a+)+\$"), FilenameRegexRow(PPSSPP, PSP, "not a pattern"))
        assertTrue("the rows still make a list", e.hasAcceptanceList(PPSSPP, PSP))
        assertEquals(false, e.listsFile(PPSSPP, PSP, "aaaa"))
        assertEquals(false, e.listsFile(PPSSPP, PSP, "a".repeat(60) + "b"))
        // And a good rule beside them still works.
        val mixed = effective(FilenameRegexRow(PPSSPP, PSP, "^(a+)+\$"), ExtensionsRow(PPSSPP, PSP, listOf("iso")))
        assertEquals(true, mixed.listsFile(PPSSPP, PSP, "game.iso"))
        assertEquals(false, mixed.listsFile(PPSSPP, PSP, "aaaa"))
    }

    @Test
    fun a_retired_overlay_gives_no_list() {
        val e = DefinitionsMerge.merge(
            bundledWith(baselineOrdinal = 9),
            overlay(5, ExtensionsRow(PPSSPP, PSP, listOf("cso"))),
        )
        assertTrue(e.overlayRetired)
        assertNull(e.listsFile(PPSSPP, PSP, "game.iso"))
    }

    @Test
    fun rows_for_a_package_or_platform_the_apk_does_not_serve_are_skipped() {
        // Each row is dropped for the reason it names: an unbundled package, and a platform the (served) package does not serve.
        val e = effective(
            ExtensionsRow("com.example.unknown", PSP, listOf("iso")),
            ExtensionsRow(RECOGNIZE_ONLY, PSP, listOf("iso")),
            ExtensionsRow(PPSSPP, PS2, listOf("iso")),
        )
        assertNull("not a bundled package", e.listsFile("com.example.unknown", PSP, "game.iso"))
        assertNull("the recognise-only package serves PS2 only, so a PSP row has nowhere to go", e.listsFile(RECOGNIZE_ONLY, PSP, "game.iso"))
        assertNull("PPSSPP serves PSP only", e.listsFile(PPSSPP, PS2, "game.iso"))
        assertEquals(e.stateKey, effective().stateKey)
    }

    @Test
    fun a_recognise_only_emulators_list_is_kept_because_capability_does_not_filter_rows() {
        // The row is on the one platform this package serves (PS2), so the only rule in play is the capability one. The merge
        // does not apply it to acceptance rows: the list exists and answers. The picker still says "Not supported yet" for such
        // a row (a state subtitle beats the label: OpenWithPickerTest), so nothing wrong reaches a player.
        assertEquals(EmulatorCapability.RECOGNIZE_ONLY, bundled.seed(RECOGNIZE_ONLY)?.capability)
        assertTrue(bundled.seed(RECOGNIZE_ONLY)!!.platforms.contains(PS2))
        val e = effective(ExtensionsRow(RECOGNIZE_ONLY, PS2, listOf("iso")))
        assertTrue(e.hasAcceptanceList(RECOGNIZE_ONLY, PS2))
        assertEquals(true, e.listsFile(RECOGNIZE_ONLY, PS2, "game.ISO"))
        assertEquals(false, e.listsFile(RECOGNIZE_ONLY, PS2, "game.bin"))
        // And it is still not something that can be launched or force-stopped.
        assertFalse(RECOGNIZE_ONLY in e.dispatchPackages)
    }

    @Test
    fun a_list_for_several_files_is_false_only_when_none_of_them_is_listed() {
        val e = effective(ExtensionsRow(PPSSPP, PSP, listOf("iso")))
        assertEquals(true, e.listsAnyFile(PPSSPP, PSP, listOf("Game (Europe).cso", "Game (USA).iso")))
        assertEquals(true, e.listsAnyFile(PPSSPP, PSP, listOf("Game (USA).ISO", "Game (Europe).cso")))
        assertEquals(false, e.listsAnyFile(PPSSPP, PSP, listOf("Game (Europe).cso", "Game (Japan).chd")))
        assertNull("no names, nothing to say", e.listsAnyFile(PPSSPP, PSP, emptyList()))
        assertNull("no list, nothing to say", e.listsAnyFile(FLYCAST, PlatformId("dreamcast"), listOf("a.iso", "b.cso")))
    }

    // ---- union: remote rows only add ----------------------------------------------------------------------------

    @Test
    fun bundled_rows_alone_make_a_list_with_no_overlay() {
        val e = DefinitionsMerge.merge(bundledWith(extensions = mapOf(PSP to setOf("iso")), regexes = mapOf(PSP to setOf("^eboot\\.bin\$"))), null)
        assertEquals(true, e.listsFile(PPSSPP, PSP, "game.ISO"))
        assertEquals(true, e.listsFile(PPSSPP, PSP, "eboot.bin"))
        assertEquals(false, e.listsFile(PPSSPP, PSP, "game.cso"))
    }

    @Test
    fun an_overlay_never_drops_a_bundled_row_it_only_adds() {
        val base = bundledWith(extensions = mapOf(PSP to setOf("iso", "bin")), regexes = mapOf(PSP to setOf("^eboot\\.bin\$")))
        val withOverlay = DefinitionsMerge.merge(
            base,
            overlay(
                4,
                ExtensionsRow(PPSSPP, PSP, listOf("cso")),
                FilenameRegexRow(PPSSPP, PSP, "^.*\\.pbp\$"),
            ),
        )
        // Every bundled row is still there ...
        assertEquals(true, withOverlay.listsFile(PPSSPP, PSP, "a.iso"))
        assertEquals(true, withOverlay.listsFile(PPSSPP, PSP, "a.bin"))
        assertEquals(true, withOverlay.listsFile(PPSSPP, PSP, "EBOOT.BIN"))
        // ... and the overlay's are on top.
        assertEquals(true, withOverlay.listsFile(PPSSPP, PSP, "a.cso"))
        assertEquals(true, withOverlay.listsFile(PPSSPP, PSP, "a.pbp"))
        assertEquals(false, withOverlay.listsFile(PPSSPP, PSP, "a.chd"))
        // The extension and rule lists are a superset of the bundled ones.
        assertTrue(withOverlay.extensionsFor(PPSSPP, PSP).containsAll(listOf("iso", "bin")))
        assertTrue(withOverlay.regexesFor(PPSSPP, PSP).contains("^eboot\\.bin\$"))

        // Any file the bundled list accepted, the union accepts.
        val bundledOnly = DefinitionsMerge.merge(base, null)
        for (name in listOf("a.iso", "b.BIN", "eboot.bin", "x.cso", "y.pbp", "z.chd", "noext", "")) {
            if (bundledOnly.listsFile(PPSSPP, PSP, name) == true) {
                assertEquals("the union dropped $name", true, withOverlay.listsFile(PPSSPP, PSP, name))
            }
        }
    }

    @Test
    fun an_overlay_row_alone_cannot_remove_a_list_or_widen_it_to_everything() {
        val base = bundledWith(extensions = mapOf(PSP to setOf("iso")))
        // No overlay, a retired one, one for another platform: the bundled list is exactly what it was.
        val untouched = listOf(
            DefinitionsMerge.merge(base, null),
            DefinitionsMerge.merge(bundledWith(extensions = mapOf(PSP to setOf("iso")), baselineOrdinal = 10), overlay(2, ExtensionsRow(PPSSPP, PSP, listOf("cso")))),
            DefinitionsMerge.merge(base, overlay(2, RetroArchCoreRow(RETROARCH, GB, "extra"))),
        )
        for (e in untouched) {
            assertEquals(true, e.listsFile(PPSSPP, PSP, "a.iso"))
            assertEquals(false, e.listsFile(PPSSPP, PSP, "a.cso"))
        }
    }

    // ---- the bridge rule ----------------------------------------------------------------------------------------

    @Test
    fun acceptance_rows_never_touch_what_can_be_launched_or_force_stopped() {
        val plain = effective()
        val withRows = effective(
            ExtensionsRow(PPSSPP, PSP, listOf("cso")),
            FilenameRegexRow(PPSSPP, PSP, "^.*\\.pbp\$"),
            ExtensionsRow(RETROARCH, GB, listOf("gb")),
        )
        assertEquals(plain.dispatchPackages, withRows.dispatchPackages)
        assertEquals(bundled.dispatchPackages, withRows.dispatchPackages)
        assertEquals(plain.retroArchCoresFor(RETROARCH, GB), withRows.retroArchCoresFor(RETROARCH, GB))
        assertEquals(plain.activityAlternatesFor(FLYCAST, PlatformId("dreamcast")), withRows.activityAlternatesFor(FLYCAST, PlatformId("dreamcast")))
        // Nothing in a bundled seed's launch facts moved either.
        assertEquals(EmulatorCapability.OPEN_ONLY, bundled.seed(PPSSPP)?.capability)
    }

    @Test
    fun the_parser_still_refuses_an_unbundled_package_so_remote_data_adds_no_emulator() {
        val built = Overlays.build(7, listOf(Rows.ext("com.example.newemulator", "psp", "iso")))
        val result = OverlayParser.parse(built.bytes, bundled)
        assertTrue(result.toString(), result is ParseResult.Rejected)
        assertEquals(RejectCode.UNKNOWN_PACKAGE, (result as ParseResult.Rejected).code)
        // A RetroArch HOST build is not a seed package either: only the seed's own package can carry rows.
        val host = Overlays.build(8, listOf(Rows.ext("com.retroarch.ra32", "gb", "gb")))
        assertEquals(RejectCode.UNKNOWN_PACKAGE, (OverlayParser.parse(host.bytes, bundled) as ParseResult.Rejected).code)
    }
}
