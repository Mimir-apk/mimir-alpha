package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.DREAMCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST_REICAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.GB
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.PPSSPP
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.RETROARCH
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The matrix rows that need the REAL bundled tables (the seed registry and the alternate activities the shipping
 * profiles know), so they live apart from the pure-fixture class. Nothing here edits a production file.
 */
class RemoteDefinitionsRegistryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val real = RegistryBundledDefinitions.build()

    @Test
    fun the_bridge_reads_the_real_tables_without_changing_them() {
        val retroArch = real.seed(RETROARCH)
        assertNotNull(retroArch)
        assertEquals(EmulatorCapability.DIRECT_PAYLOAD, retroArch!!.capability)
        assertTrue(retroArch.retroArchCores.size > 20)
        assertEquals(
            "the Flycast drift ShippingLaunchProfiles already knows is the bundled alternate",
            listOf(FLYCAST_REICAST),
            real.activityAlternates[PackagePlatform(FLYCAST, DREAMCAST)],
        )
        assertEquals(EmulatorSeedRegistry.allDispatchPackages(), real.dispatchPackages)
        assertEquals(EmulatorSeedRegistry.SEEDS.map { it.packageName }, real.seeds.map { it.packageName })
        // Every seed package is queried in the manifest, so an overlay keyed to a seed can never name an invisible one.
        assertTrue(EmulatorSeedRegistry.allSeedPackages().containsAll(real.seeds.map { it.packageName }))
    }

    @Test
    fun t13_an_overlay_never_changes_the_auto_default_for_any_platform() {
        val retroArch = real.seed(RETROARCH)!!
        val platforms = retroArch.retroArchCores.keys.filter { it.value.matches(Regex("^[a-z0-9_]{1,64}$")) }.take(60)
        assertTrue(platforms.size > 20)
        val registryBefore = platforms.associateWith { p -> EmulatorSeedRegistry.candidatesFor(p) { true }.map { it.id.value } }

        // Adversarial: a core that sorts before everything, plus two bundled cores repeated in reverse.
        val rows = platforms.flatMap { p ->
            listOf(Rows.core(RETROARCH, p.value, "aaa_first")) +
                retroArch.retroArchCores.getValue(p).reversed().take(2).map { Rows.core(RETROARCH, p.value, it) }
        }.distinct()
        assertTrue("${rows.size} rows", rows.size <= RemoteDefinitionsLimits.MAX_ROWS)
        val built = Overlays.build(9, rows)
        val parsed = OverlayParser.parse(built.bytes, real, built.canonicalSha)
        assertTrue(parsed.toString().take(200), parsed is ParseResult.Accepted)
        val effective = DefinitionsMerge.merge(real, (parsed as ParseResult.Accepted).overlay)

        for (p in platforms) {
            val before = retroArch.retroArchCores.getValue(p)
            val after = effective.retroArchCoresFor(RETROARCH, p)
            assertEquals("Auto default for ${p.value}", before.first(), after.first())
            assertEquals("bundled order is a prefix for ${p.value}", before, after.take(before.size))
            assertEquals("exactly the new core is added for ${p.value}", before.size + 1, after.size)
            assertTrue(effective.coreItems(RETROARCH, p).last().fromOverlay)
        }
        assertEquals(
            "the registry's own candidate order is untouched",
            registryBefore,
            platforms.associateWith { p -> EmulatorSeedRegistry.candidatesFor(p) { true }.map { it.id.value } },
        )

        // Row order never matters.
        val shuffled = Overlays.build(9, rows.shuffled(java.util.Random(7)))
        val shuffledParse = OverlayParser.parse(shuffled.bytes, real)
        assertTrue(shuffledParse is ParseResult.Accepted)
        val again = DefinitionsMerge.merge(real, (shuffledParse as ParseResult.Accepted).overlay)
        assertEquals(effective.render(), again.render())
    }

    @Test
    fun t13_a_real_hyphenated_core_stem_is_a_legal_core_name() {
        // mesen-s is a real RetroArch core stem in the bundled catalog, so a stem pattern without the hyphen is wrong.
        val allCores = real.seed(RETROARCH)!!.retroArchCores.values.flatten()
        assertTrue(allCores.contains("mesen-s"))
        val built = Overlays.build(9, listOf(Rows.core(RETROARCH, "snes", "mesen-s")))
        assertTrue(OverlayParser.parse(built.bytes, real) is ParseResult.Accepted)
    }

    @Test
    fun t17_an_accepted_overlay_never_extends_force_stop_authority_on_the_real_registry() {
        val dispatchBefore = EmulatorSeedRegistry.allDispatchPackages()
        val built = Overlays.build(
            9,
            listOf(
                Rows.alt(FLYCAST, "dreamcast", "com.reicast.emulator.Other"),
                Rows.core(RETROARCH, "gb", "aaa_first"),
                Rows.ext(PPSSPP, "psp", "cso"),
            ),
        )
        val rig = Rig(tmp.newFolder("store"), real, FakeEgress.always { FakeResponse.ok(built) })

        assertTrue(rig.refresh(built) is RefreshResult.Applied)

        assertEquals(dispatchBefore, rig.holder.current.dispatchPackages)
        assertEquals(dispatchBefore, EmulatorSeedRegistry.allDispatchPackages())
        val alternates = rig.holder.current.activityAlternatesFor(FLYCAST, DREAMCAST)
        assertEquals("the bundled Flycast alternate is still first", FLYCAST_REICAST, alternates.first())
        assertEquals(2, alternates.size)
        assertFalse(alternates.last() == FLYCAST_REICAST)
        assertTrue(rig.holder.current.recognizes(PPSSPP, TestBundled.PSP, "Game.CSO"))
    }

    @Test
    fun t17_a_package_that_is_not_a_seed_is_refused_against_the_real_registry() {
        val notSeeded = "com.example.not.a.seed"
        assertTrue(real.seed(notSeeded) == null)
        val built = Overlays.build(9, listOf(Rows.alt(notSeeded, "dreamcast", "com.example.Main")))
        val parsed = OverlayParser.parse(built.bytes, real)
        assertTrue(parsed.toString(), parsed is ParseResult.Rejected && parsed.code == RejectCode.UNKNOWN_PACKAGE)
        // Even a RetroArch package variant that is only queried, not seeded, is not enough.
        val variant = Overlays.build(9, listOf(Rows.core("com.retroarch.ra32", "gb", "gambatte")))
        assertTrue(real.seed("com.retroarch.ra32") == null)
        assertTrue((OverlayParser.parse(variant.bytes, real) as ParseResult.Rejected).code == RejectCode.UNKNOWN_PACKAGE)
    }

    @Test
    fun t22_the_real_scan_and_launch_reads_make_no_request() {
        val built = Overlays.build(9, listOf(Rows.core(RETROARCH, "gb", "aaa_first")))
        val egress = FakeEgress.always { FakeResponse.ok(built) }
        val rig = Rig(tmp.newFolder("store"), real, egress, enabled = false)

        assertEquals(RefreshResult.NotEnabled, rig.refresh(built))
        // What a scan and a launch do with the real tables.
        EmulatorSeedRegistry.candidatesFor(GB) { true }
        EmulatorSeedRegistry.allDispatchPackages()
        RemoteDefinitionsLoader.load(rig.store, real)
        DefinitionsMerge.merge(real, null)

        assertEquals(0, egress.requests.size)
        assertTrue(File(rig.dir.path).listFiles().isNullOrEmpty())
    }
}
