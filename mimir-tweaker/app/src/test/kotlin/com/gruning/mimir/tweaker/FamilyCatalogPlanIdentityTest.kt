package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.bundledBytes
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.entryAt
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.mutated
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The catalog-driven planner reproduces every plan the compiled one produced.
 *
 * [FamilyCatalogGoldenHashes] is the `PlanHash` of every [FamilyCatalogGoldenFixtures] fixture x vendor key
 * (absent and present) x profile (216 rows), recorded from the compiled planner before its family tables
 * became data. Each test below replays that table through the real [TuningPlanner] under a different catalog
 * source; all must reproduce all 216 rows.
 *
 * The mutation tests exist so these cannot pass trivially through a compiled fallback: they install a catalog
 * whose numbers differ and show that the plan follows the installed numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FamilyCatalogPlanIdentityTest {
    @Before
    fun nothingInstalled() {
        FamilyCatalogRegistry.install(null)
        SupportedUncertifiedProfiles.clearComputeMemoForTest()
    }

    @After
    fun restore() {
        FamilyCatalogRegistry.install(null)
        SupportedUncertifiedProfiles.clearComputeMemoForTest()
    }

    private val planner = TuningPlanner()

    private fun accepted(bytes: ByteArray): FamilyCatalog =
        when (val parsed = FamilyCatalogParser.parse(bytes)) {
            is FamilyCatalogParse.Accepted -> parsed.catalog
            is FamilyCatalogParse.Rejected -> error("rejected: ${parsed.reason} ${parsed.detail}")
        }

    private fun fixture(id: String) = FamilyCatalogGoldenFixtures.all().single { it.id == id }.capabilities

    private fun plan(profile: String, capabilities: HardwareCapabilities, vendorKey: Boolean = false): TuningPlan =
        checkNotNull(
            planner.plan(
                profile,
                FamilyCatalogGoldenFixtures.PACKAGE,
                FamilyCatalogGoldenFixtures.withVendorKey(capabilities, vendorKey),
            ),
        )

    /** Every committed row, reproduced. On a difference the actual table is written beside the build for a diff. */
    private fun assertEveryGoldenRowIsReproduced(source: String) {
        val actual = FamilyCatalogGoldenFixtures.rows(planner)
        val actualText = actual.joinToString("\n") { it.toLine() }
        val committed = FamilyCatalogGoldenHashes.TABLE.lines().filter { it.isNotBlank() }
        val actualLines = actualText.lines()
        assertEquals("the golden table has 216 rows", 216, committed.size)

        // The format PlanHash digests is pinned independently of PlanHash itself.
        actual.forEach {
            assertEquals("${it.fixture}/${it.profile}: PlanHash no longer digests the canonical string", sha256(it.canonical), it.planHash)
        }

        if (committed != actualLines) {
            val out = File(File(System.getProperty("user.dir").orEmpty()), "build/family-catalog-golden/actual.txt")
            runCatching {
                out.parentFile!!.mkdirs()
                out.writeText(actualText + "\n")
            }
            val changed = (actualLines.toSet() - committed.toSet()).take(12)
            throw AssertionError(
                "[$source] golden plan hashes differ: committed=${committed.size} rows, actual=${actualLines.size} rows; " +
                    "the actual table was written to $out. First differing rows:\n" + changed.joinToString("\n"),
            )
        }
    }

    // ---- every golden row, under every catalog source ----------------------------------------

    @Test
    fun `nothing installed - the compiled default reproduces all 216 golden rows`() {
        assertEquals(null, FamilyCatalogRegistry.current())
        assertEveryGoldenRowIsReproduced("compiled default")
    }

    @Test
    fun `the bundled asset installed - all 216 golden rows`() {
        FamilyCatalogRegistry.install(accepted(bundledBytes()))
        assertEveryGoldenRowIsReproduced("bundled asset")
    }

    @Test
    fun `the bundled asset with its families in reverse file order - all 216 golden rows`() {
        FamilyCatalogRegistry.install(
            accepted(
                mutated { root ->
                    val families = root.getJSONArray("families")
                    val reversed = JSONArray()
                    for (i in families.length() - 1 downTo 0) reversed.put(families.getJSONObject(i))
                    root.put("families", reversed)
                },
            ),
        )
        assertEveryGoldenRowIsReproduced("reversed file order")
    }

    @Test
    fun `a newer revision with the same numbers - all 216 golden rows`() {
        FamilyCatalogRegistry.install(accepted(FamilyCatalogTestSupport.withRevision(99)))
        assertEveryGoldenRowIsReproduced("revision 99, same numbers")
    }

    @Test
    fun `the same catalog on the same device gives the same plan hash`() {
        val capabilities = fixture("odin-real-tables")
        val first = PlanHash.of(plan("balanced", capabilities, vendorKey = true))
        FamilyCatalogRegistry.install(accepted(bundledBytes()))
        val second = PlanHash.of(plan("balanced", capabilities, vendorKey = true))
        FamilyCatalogRegistry.install(null)
        assertEquals(first, second)
        assertEquals(second, PlanHash.of(plan("balanced", capabilities, vendorKey = true)))
    }

    // ---- the planner follows the installed numbers --------------------------------------------

    @Test
    fun `the 8 Elite khz row is read from the installed catalog`() {
        val odin = fixture("odin-real-tables")
        assertEquals(mapOf(0 to 1_785_600, 6 to 2_246_400), plan("quiet", odin).cpuMaxKhz)

        // quiet policy0 moves to a different rung that the Odin's live table does carry.
        FamilyCatalogRegistry.install(
            accepted(mutated { entryAt(it, "qualcomm-8-elite").getJSONObject("cpu").getJSONObject("profiles").getJSONObject("quiet").put("0", 1_996_800) }),
        )
        assertEquals(mapOf(0 to 1_996_800, 6 to 2_246_400), plan("quiet", odin).cpuMaxKhz)
        // balanced and performance rows are untouched.
        assertEquals(mapOf(0 to 1_996_800, 6 to 2_438_400), plan("balanced", odin).cpuMaxKhz)
    }

    @Test
    fun `the 8 Elite khz row still has to be a live OPP, whatever the catalog says`() {
        val odin = fixture("odin-real-tables")
        FamilyCatalogRegistry.install(
            accepted(mutated { entryAt(it, "qualcomm-8-elite").getJSONObject("cpu").getJSONObject("profiles").getJSONObject("quiet").put("0", 1_234_567) }),
        )
        val planned = plan("quiet", odin).cpuMaxKhz
        assertNotEquals("an off-table target is never planned", 1_234_567, planned.getValue(0))
        assertTrue(
            "the live validators decide, the catalog only proposes",
            planned.getValue(0) in odin.cpuPolicies.first { it.id == 0 }.availableFrequenciesKhz,
        )
    }

    @Test
    fun `the 8 Elite explicit GPU levels are read from the installed catalog`() {
        val odin = fixture("odin-real-tables")
        assertEquals(5, plan("quiet", odin).gpuMaxPowerLevel)
        FamilyCatalogRegistry.install(
            accepted(mutated { entryAt(it, "qualcomm-8-elite").getJSONObject("gpu").getJSONObject("levels").put("quiet", 4).put("balanced", 2) }),
        )
        assertEquals(4, plan("quiet", odin).gpuMaxPowerLevel)
        assertEquals(2, plan("balanced", odin).gpuMaxPowerLevel)
        assertEquals(0, plan("performance", odin).gpuMaxPowerLevel)
    }

    @Test
    fun `the transposed GPU fraction and the uncapped set are read from the installed catalog`() {
        val nova = fixture("nova-qcs8550-nine-gpu-levels")
        // 5 / 13 of a nine-level table is level 3 (the Nova's measured transposed level).
        assertEquals(3, plan("quiet", nova).gpuMaxPowerLevel)
        assertEquals(0, plan("balanced", nova).gpuMaxPowerLevel)

        FamilyCatalogRegistry.install(
            accepted(
                mutated { root ->
                    val gpu = entryAt(root, "qualcomm-8gen2").getJSONObject("gpu")
                    gpu.put("capped", JSONObject().put("quiet", 9).put("balanced", 4))
                    gpu.put("uncapped", JSONArray().put("performance"))
                },
            ),
        )
        // 9 / 13 * 8 = 5.54 -> 6; balanced is now capped at 4 / 13 * 8 = 2.46 -> 2; performance stays uncapped.
        assertEquals(6, plan("quiet", nova).gpuMaxPowerLevel)
        assertEquals(2, plan("balanced", nova).gpuMaxPowerLevel)
        assertEquals(0, plan("performance", nova).gpuMaxPowerLevel)
    }

    @Test
    fun `the anchor ratios are read from the installed catalog`() {
        val gen2 = fixture("gen2-alias-QCS8550")
        val before = plan("performance", gen2).cpuMaxKhz
        FamilyCatalogRegistry.install(
            accepted(
                mutated { root ->
                    // performance prime target 3,072,000 -> 2,246,400 of the anchor's 4,320,000.
                    entryAt(root, "qualcomm-8gen2").getJSONObject("cpu").getJSONObject("profiles")
                        .getJSONObject("performance").put("prime", JSONArray().put(2_246_400).put(4_320_000))
                },
            ),
        )
        val after = plan("performance", gen2).cpuMaxKhz
        assertNotEquals(before, after)
        // The prime policy (7) moves down; the little policy (0) sits at ordinal 0, which reads the unchanged little ratio.
        assertTrue("policy 7: ${after.getValue(7)} < ${before.getValue(7)}", after.getValue(7) < before.getValue(7))
        assertEquals(before.getValue(0), after.getValue(0))
    }

    @Test
    fun `the vendor mode row is read from the installed catalog, and only with the axis`() {
        val gen2 = fixture("gen2-alias-QCS8550")
        assertEquals(0, plan("quiet", gen2, vendorKey = true).vendorMode)
        FamilyCatalogRegistry.install(
            accepted(mutated { entryAt(it, "qualcomm-8gen2").getJSONObject("vendorMode").put("quiet", 2).remove("balanced") }),
        )
        assertEquals("catalog row replaces the profile default", 2, plan("quiet", gen2, vendorKey = true).vendorMode)
        assertEquals("a profile with no row takes the profile default", 1, plan("balanced", gen2, vendorKey = true).vendorMode)
        assertEquals(2, plan("performance", gen2, vendorKey = true).vendorMode)
        assertEquals("no axis, no vendor mode, whatever the table says", null, plan("quiet", gen2, vendorKey = false).vendorMode)
        assertEquals("stock is never written", null, plan("stock", gen2, vendorKey = true).vendorMode)
        // The other families' rows are untouched.
        assertEquals(0, plan("quiet", fixture("odin-real-tables"), vendorKey = true).vendorMode)
    }

    @Test
    fun `a family added as data is planned and admitted with no code change`() {
        val mt9999 = fixture("general-mt9999")
        val generic = plan("quiet", mt9999).cpuMaxKhz
        val admittedBefore = SupportedUncertifiedProfiles.compute(CapabilityStatus.SUPPORTED, "MT9999", mt9999.cpuPolicies)
        assertEquals(emptySet<String>(), admittedBefore)

        val entry = JSONObject(
            """
            {
              "id": "mediatek-mt9999",
              "label": "MediaTek MT9999 test family",
              "treatment": "mimir-certified-reference",
              "match": { "soc": ["MT9999"] },
              "cpu": {
                "kind": "khz",
                "profiles": {
                  "quiet": { "0": 300000, "4": 500000 },
                  "balanced": { "0": 700000, "4": 800000 },
                  "performance": { "0": 900000, "4": 1100000 }
                }
              }
            }
            """.trimIndent(),
        )
        FamilyCatalogRegistry.install(accepted(FamilyCatalogTestSupport.withEntry(entry)))
        SupportedUncertifiedProfiles.clearComputeMemoForTest()

        assertEquals("mediatek-mt9999", TweakerHardwareFamilies.resolve("MT9999").id)
        assertEquals(mapOf(0 to 300_000, 4 to 500_000), plan("quiet", mt9999).cpuMaxKhz)
        assertNotEquals(generic, plan("quiet", mt9999).cpuMaxKhz)
        assertEquals(mapOf(0 to 900_000, 4 to 1_100_000), plan("performance", mt9999).cpuMaxKhz)
        assertEquals(
            setOf("quiet", "balanced", "performance"),
            SupportedUncertifiedProfiles.compute(CapabilityStatus.SUPPORTED, "MT9999", mt9999.cpuPolicies),
        )
        // The three reserved families still plan exactly as before.
        assertEquals(mapOf(0 to 1_785_600, 6 to 2_246_400), plan("quiet", fixture("odin-real-tables")).cpuMaxKhz)
    }

    /** The bundled catalog plus MT9999 as a certified reference: MT9999 is generic without it and certified with it. */
    private fun mt9999Catalog(): FamilyCatalog = accepted(
        FamilyCatalogTestSupport.withEntry(
            JSONObject(
                """
                {
                  "id": "mediatek-mt9999",
                  "label": "MediaTek MT9999 test family",
                  "treatment": "mimir-certified-reference",
                  "match": { "soc": ["MT9999"] },
                  "cpu": {
                    "kind": "khz",
                    "profiles": {
                      "quiet": { "0": 300000, "4": 500000 },
                      "balanced": { "0": 700000, "4": 800000 },
                      "performance": { "0": 900000, "4": 1100000 }
                    }
                  }
                }
                """.trimIndent(),
            ),
        ),
    )

    private val allProfiles = setOf("quiet", "balanced", "performance")

    /**
     * F-P3-4 (2026-10-13 review): the planner read `resolve()` and `tables()` as separate registry reads, and
     * the admission set was memoised without the catalog, so a promotion landing mid-derivation could pair a
     * family from one revision with a table from another, or store the old answer under the new catalog. A
     * derivation now takes ONE catalog and answers every question from it.
     */
    @Test
    fun `a derivation answers every family question from the one catalog it was given`() {
        val mt9999 = fixture("general-mt9999")
        val given = mt9999Catalog()
        // The registry holds the compiled default (MT9999 is generic there); the plan follows the given catalog.
        val fromGiven = checkNotNull(planner.plan("quiet", FamilyCatalogGoldenFixtures.PACKAGE, mt9999, catalog = given))
        assertEquals(mapOf(0 to 300_000, 4 to 500_000), fromGiven.cpuMaxKhz)
        assertNotEquals(fromGiven.cpuMaxKhz, plan("quiet", mt9999).cpuMaxKhz)
        assertEquals(
            mapOf(0 to 300_000, 4 to 500_000),
            planner.certifiedCpuTargetsKhz("quiet", "MT9999", mt9999.cpuPolicies, catalog = given),
        )
        assertEquals(null, planner.certifiedCpuTargetsKhz("quiet", "MT9999", mt9999.cpuPolicies))

        // And the admission set: the same catalog resolves the family and validates the targets.
        assertEquals(allProfiles, SupportedUncertifiedProfiles.compute(given, CapabilityStatus.SUPPORTED, "MT9999", mt9999.cpuPolicies))
        assertEquals(
            "another catalog is a different answer, not the memoised one",
            emptySet<String>(),
            SupportedUncertifiedProfiles.compute(CapabilityStatus.SUPPORTED, "MT9999", mt9999.cpuPolicies),
        )
    }

    @Test
    fun `a promotion between two admission computes is never answered from the old catalog's memo`() {
        val mt9999 = fixture("general-mt9999")
        val compute = { SupportedUncertifiedProfiles.compute(CapabilityStatus.SUPPORTED, "MT9999", mt9999.cpuPolicies) }
        assertEquals(emptySet<String>(), compute()) // memoised under the compiled default
        assertEquals(1, SupportedUncertifiedProfiles.computeMemoSizeForTest())

        // The registry takes a promoted catalog. Nothing clears the memo: the catalog instance is part of its validity.
        FamilyCatalogRegistry.install(mt9999Catalog())
        assertEquals(allProfiles, compute())
        assertEquals("the old catalog's entry is gone, not kept beside the new one", 1, SupportedUncertifiedProfiles.computeMemoSizeForTest())

        FamilyCatalogRegistry.install(null)
        assertEquals(emptySet<String>(), compute())
        assertEquals(emptySet<String>(), compute())
        assertEquals(1, SupportedUncertifiedProfiles.computeMemoSizeForTest())
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
