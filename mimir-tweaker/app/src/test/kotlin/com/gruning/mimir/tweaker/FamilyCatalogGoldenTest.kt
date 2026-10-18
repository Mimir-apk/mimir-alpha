package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.bundledCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bundled `tweaker-families.json` is what the planner's private constants used to be. These tests
 * pin that the file, the Kotlin [FamilyCatalogCompiledDefault] and the numbers the rest of the
 * planner still names agree. The proof that no plan moved is `FamilyCatalogPlanIdentityTest` (every
 * [FamilyCatalogGoldenHashes] row through the real, catalog-driven planner).
 *
 * The reflection half of this class (it read the planner's private tables to compare the file with
 * them) is gone with those tables: the golden hashes, recorded from the compiled planner before they
 * were removed, are now the oracle. So is the small reference planner that redid the table reads;
 * the real planner does them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FamilyCatalogGoldenTest {
    private val catalog = bundledCatalog()

    private fun bits(value: Double) = value.toRawBits()

    @Test
    fun `the compiled default is the bundled file, entry for entry and number for number`() {
        // FamilyCatalog.equals is structural over the revision and every entry (aliases, tables, transports).
        assertEquals(catalog, FamilyCatalogCompiledDefault.catalog)
        assertEquals(FamilyCatalogCompiledDefault.REVISION, catalog.revision)
        assertEquals(catalog.families.map { it.id }, FamilyCatalogCompiledDefault.catalog.families.map { it.id })
    }

    @Test
    fun `the three families' ids, labels, treatments and aliases are the ones the compiled when carried`() {
        // Pinned literals, recorded from the compiled `TweakerHardwareFamilies.resolve` before it read the catalog.
        val expectedIds = mapOf(
            "SM8250" to "qualcomm-sm8250",
            "sm8250" to "qualcomm-sm8250",
            " Sm8750 " to "qualcomm-8-elite",
            "cq8725s\n" to "qualcomm-8-elite",
            "CQ8725S" to "qualcomm-8-elite",
            "SM8750" to "qualcomm-8-elite",
            "QCS8550 " to "qualcomm-8gen2",
            "QCS8550" to "qualcomm-8gen2",
            "SM8550" to "qualcomm-8gen2",
        )
        expectedIds.forEach { (soc, id) -> assertEquals("'$soc'", id, catalog.resolve(soc)?.id) }

        // Everything else falls to a compiled, data-free fallback (the entry is null here).
        listOf(
            "", "  ", "SM8650", "SM8350", "SM8450", "SDM845", "MSM8998", "MT6893", "CQ8725", "SM87500", "SM875",
            "SM8250X", "QCS8550A", "kona", "kalama", "sun", "ayn odin 3", "SM8750-AC",
        ).forEach { soc -> assertNull("'$soc' must fall to the compiled fallback", catalog.resolve(soc)) }

        assertTrue("no star alias is needed to reproduce today's `when`", catalog.families.none { f -> f.socAliases.any { it.endsWith("*") } })
        assertEquals(FamilyCatalogLimits.RESERVED_IDS.toSet(), catalog.families.map { it.id }.toSet())
        catalog.families.forEach { entry ->
            assertEquals(entry.id, TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE, entry.treatment)
            assertTrue("${entry.id} label is within the bound", entry.label.length <= FamilyCatalogLimits.MAX_LABEL_LENGTH)
        }
        assertEquals("Qualcomm SM8250 / Snapdragon 865", catalog.entry("qualcomm-sm8250")!!.label)
        assertEquals("Qualcomm QCS8550 / SM8550 / Snapdragon 8 Gen 2", catalog.entry("qualcomm-8gen2")!!.label)
        assertEquals(
            "Qualcomm SM8750 Snapdragon 8 Elite / CQ8725S Q-8750 / AYN Odin 3",
            catalog.entry("qualcomm-8-elite")!!.label,
        )
        assertEquals(listOf("sun"), catalog.entry("qualcomm-8-elite")!!.platformAliases)
        assertEquals(listOf("kalama"), catalog.entry("qualcomm-8gen2")!!.platformAliases)
        assertEquals(listOf("kona"), catalog.entry("qualcomm-sm8250")!!.platformAliases)
    }

    @Test
    fun `the khz row is the signed sequence 10 vector and only the 8 Elite has one`() {
        val khzFamilies = catalog.families.filter { it.tables.cpu is FamilyCpuTable.Khz }
        assertEquals(listOf(TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID), khzFamilies.map { it.id })
        val row = (khzFamilies.single().tables.cpu as FamilyCpuTable.Khz).byProfile
        assertEquals(
            mapOf(
                "quiet" to mapOf(0 to 1_785_600, 6 to 2_246_400),
                "balanced" to mapOf(0 to 1_996_800, 6 to 2_438_400),
                "performance" to mapOf(0 to 1_996_800, 6 to 3_072_000),
            ),
            row,
        )
    }

    @Test
    fun `the anchor families carry the signed vector over the anchor's own live maxima`() {
        val anchorFamilies = catalog.families.filter { it.tables.cpu is FamilyCpuTable.Anchor }
        assertEquals(
            setOf(TweakerHardwareFamilies.SM8250_FAMILY_ID, TweakerHardwareFamilies.SNAPDRAGON_8_GEN_2_FAMILY_ID),
            anchorFamilies.map { it.id }.toSet(),
        )
        val eliteRow = (catalog.entry(TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID)!!.tables.cpu as FamilyCpuTable.Khz).byProfile
        anchorFamilies.forEach { family ->
            val anchor = family.tables.cpu as FamilyCpuTable.Anchor
            assertEquals(family.id, setOf("quiet", "balanced", "performance"), anchor.byProfile.keys)
            anchor.byProfile.forEach { (profile, row) ->
                // The numerators are the anchor's own literal targets, the denominators its live maxima.
                assertEquals("${family.id} $profile", eliteRow.getValue(profile).getValue(0), row.little.numerator)
                assertEquals("${family.id} $profile", eliteRow.getValue(profile).getValue(6), row.prime.numerator)
                assertEquals(family.id, 3_532_800, row.little.denominator)
                assertEquals(family.id, 4_320_000, row.prime.denominator)
                // The fraction is a plain Double over an Int, never a decimal literal.
                assertEquals(bits(row.little.numerator.toDouble() / 3_532_800), bits(anchor.fractions(profile)!!.first))
                assertEquals(bits(row.prime.numerator.toDouble() / 4_320_000), bits(anchor.fractions(profile)!!.second))
            }
            assertNull(family.id, anchor.fractions("stock"))
        }
    }

    @Test
    fun `the GPU tables are the explicit levels and the position fractions the planner used`() {
        // 8 Elite: explicit levels, with balanced uncapped since 2026-10-13.
        val levels = catalog.entry(TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID)!!.tables.gpu as FamilyGpuTable.Levels
        assertEquals(
            mapOf(
                "quiet" to TuningPlanner.ODIN_3_QUIET_GPU_MAX_POWER_LEVEL,
                "balanced" to TuningPlanner.UNCAPPED_GPU_MAX_POWER_LEVEL,
                "performance" to TuningPlanner.ODIN_3_PERFORMANCE_GPU_MAX_POWER_LEVEL,
            ),
            levels.byProfile,
        )
        assertNull(levels.levelFor("stock"))

        // Transposed families: only quiet does arithmetic (level 5 of the Odin's 14); balanced and
        // performance are uncapped and short-circuit before any fraction.
        listOf(TweakerHardwareFamilies.SM8250_FAMILY_ID, TweakerHardwareFamilies.SNAPDRAGON_8_GEN_2_FAMILY_ID).forEach { id ->
            val gpu = catalog.entry(id)!!.tables.gpu as FamilyGpuTable.Transposed
            assertEquals(id, 14, gpu.anchorLevels)
            assertEquals(id, setOf("balanced", "performance"), gpu.uncapped)
            assertEquals(id, mapOf("quiet" to 5), gpu.capped)
            assertEquals(id, bits(5.0 / 13), bits(gpu.fraction("quiet")!!))
            assertNull(id, gpu.fraction("balanced"))
            assertNull(id, gpu.fraction("performance"))
        }
        assertEquals(5, TuningPlanner.ODIN_3_QUIET_GPU_MAX_POWER_LEVEL)
        assertEquals(0, TuningPlanner.UNCAPPED_GPU_MAX_POWER_LEVEL)
    }

    @Test
    fun `the vendor mode row is the profile default through the compiled table`() {
        val defaults = mapOf(
            "quiet" to TuningPlanner.VENDOR_MODE_STANDARD,
            "balanced" to TuningPlanner.VENDOR_MODE_PERFORMANCE,
            "performance" to TuningPlanner.VENDOR_MODE_HIGH_PERFORMANCE,
        )
        assertEquals((FamilyCatalogLimits.MIN_VENDOR_MODE..FamilyCatalogLimits.MAX_VENDOR_MODE).toSet(), TuningPlanner.KNOWN_VENDOR_MODES)
        catalog.families.forEach { family ->
            defaults.forEach { (profile, default) ->
                val compiledMode = VendorModeTable.modeFor(family.id, profile, profileDefault = default)
                assertEquals("${family.id} $profile", compiledMode, family.tables.vendorMode[profile])
            }
            assertNull("stock is never written", family.tables.vendorMode["stock"])
        }
    }

    @Test
    fun `the generic tier factors and the profile set are the compiled ones`() {
        assertEquals(bits(TuningPlanner.LARGE_UNDERCLOCK_CPU_FACTOR), bits(FamilyCpuTable.General.DEFAULT.large.toDouble()))
        assertEquals(bits(TuningPlanner.MEDIUM_UNDERCLOCK_CPU_FACTOR), bits(FamilyCpuTable.General.DEFAULT.medium.toDouble()))
        assertEquals(TuningPlanner().profileIds() - "stock", FamilyCatalogLimits.PROFILE_IDS)
        assertEquals(listOf("quiet", "balanced", "performance"), FamilyCatalogLimits.PROFILE_ORDER)
    }

    @Test
    fun `the golden table covers every fixture, both vendor key states and all four profiles`() {
        val fixtures = FamilyCatalogGoldenFixtures.all()
        assertEquals("fixture ids are unique", fixtures.size, fixtures.map { it.id }.toSet().size)
        assertEquals(
            fixtures.size * 2 * FamilyCatalogGoldenFixtures.PROFILES.size,
            FamilyCatalogGoldenHashes.rows.size,
        )
        fixtures.forEach { fixture ->
            listOf(false, true).forEach { key ->
                FamilyCatalogGoldenFixtures.PROFILES.forEach { profile ->
                    assertTrue(
                        "${fixture.id} key=$key $profile",
                        FamilyCatalogGoldenHashes.hashFor(fixture.id, key, profile) != null,
                    )
                }
            }
        }
        // The vendor key only appends |vmode= to a plan; it never moves a CPU or GPU number.
        FamilyCatalogGoldenHashes.rows.groupBy { it.fixture to it.profile }.forEach { (key, rows) ->
            val without = rows.single { !it.vendorKeyPresent }.canonical
            val with = rows.single { it.vendorKeyPresent }.canonical
            assertTrue("$key: '$with' vs '$without'", with == without || with.startsWith("$without|vmode="))
        }
    }
}
