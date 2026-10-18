package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.bundledBytes
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.bundledCatalog
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.entryAt
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.extraEntry
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.mutated
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.withEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The catalog parser and validator (design D1). `org.json` is the Android implementation only
 * under Robolectric, so every test here runs on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FamilyCatalogTest {

    private fun parse(bytes: ByteArray) = FamilyCatalogParser.parse(bytes)

    private fun accepted(bytes: ByteArray): FamilyCatalogParse.Accepted {
        val parsed = parse(bytes)
        assertTrue("expected Accepted, got $parsed", parsed is FamilyCatalogParse.Accepted)
        return parsed as FamilyCatalogParse.Accepted
    }

    private fun rejection(bytes: ByteArray): FamilyCatalogParse.Rejected {
        val parsed = parse(bytes)
        assertTrue("expected Rejected, got $parsed", parsed is FamilyCatalogParse.Rejected)
        return parsed as FamilyCatalogParse.Rejected
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    /** The bundled document with each quoted sentinel replaced by raw JSON (what `org.json` itself cannot emit). */
    private fun raw(edit: (JSONObject) -> Unit, vararg replacements: Pair<String, String>): ByteArray {
        var text = String(mutated(edit), Charsets.US_ASCII)
        replacements.forEach { (sentinel, json) ->
            assertTrue("sentinel $sentinel is not in the document", text.contains("\"$sentinel\""))
            text = text.replace("\"$sentinel\"", json)
        }
        return ascii(text)
    }

    // ---- the shipped catalog ------------------------------------------------------------

    @Test
    fun `the bundled catalog parses with its three certified families and nothing dropped`() {
        val parsed = accepted(bundledBytes())

        assertEquals(1, parsed.catalog.revision)
        assertEquals(
            listOf("qualcomm-8-elite", "qualcomm-8gen2", "qualcomm-sm8250"),
            parsed.catalog.families.map { it.id },
        )
        assertTrue(parsed.dropped.isEmpty())
        parsed.catalog.families.forEach {
            assertEquals(it.id, TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE, it.treatment)
        }
    }

    @Test
    fun `the same bytes give the same catalog and formatting does not matter`() {
        val first = accepted(bundledBytes()).catalog
        val second = accepted(bundledBytes()).catalog
        // A failed parse in between must not leave state behind.
        rejection(ascii("{"))
        val third = accepted(bundledBytes()).catalog
        // Re-serialised by org.json: different whitespace and escaping, same content.
        val reserialised = accepted(mutated { }).catalog

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first, third)
        assertEquals(first, reserialised)
        assertNotEquals(first, accepted(FamilyCatalogTestSupport.withRevision(2)).catalog)
    }

    @Test
    fun `the 8 Elite entry carries today's khz row, explicit GPU levels and the vendor mode row`() {
        val tables = bundledCatalog().tables("qualcomm-8-elite")!!

        val cpu = tables.cpu as FamilyCpuTable.Khz
        assertEquals(mapOf(0 to 1_785_600, 6 to 2_246_400), cpu.targets("quiet"))
        assertEquals(mapOf(0 to 1_996_800, 6 to 2_438_400), cpu.targets("balanced"))
        assertEquals(mapOf(0 to 1_996_800, 6 to 3_072_000), cpu.targets("performance"))
        assertNull(cpu.targets("stock"))
        val gpu = tables.gpu as FamilyGpuTable.Levels
        assertEquals(5, gpu.levelFor("quiet"))
        assertEquals(0, gpu.levelFor("balanced"))
        assertEquals(0, gpu.levelFor("performance"))
        assertNull(gpu.levelFor("stock"))
        assertEquals(mapOf("quiet" to 0, "balanced" to 1, "performance" to 2), tables.vendorMode)
        assertTrue(FamilyTransport.CPU_MSM_PERFORMANCE_QOS in tables.transports)
    }

    @Test
    fun `the transposed entries carry integer anchor pairs and only quiet does GPU arithmetic`() {
        listOf("qualcomm-8gen2", "qualcomm-sm8250").forEach { id ->
            val tables = bundledCatalog().tables(id)!!
            val cpu = tables.cpu as FamilyCpuTable.Anchor
            assertEquals(id, AnchorRow(Ratio(1_785_600, 3_532_800), Ratio(2_246_400, 4_320_000)), cpu.byProfile["quiet"])
            assertEquals(id, AnchorRow(Ratio(1_996_800, 3_532_800), Ratio(2_438_400, 4_320_000)), cpu.byProfile["balanced"])
            assertEquals(id, AnchorRow(Ratio(1_996_800, 3_532_800), Ratio(3_072_000, 4_320_000)), cpu.byProfile["performance"])
            assertNull(id, cpu.fractions("stock"))
            val gpu = tables.gpu as FamilyGpuTable.Transposed
            assertEquals(id, 14, gpu.anchorLevels)
            assertEquals(id, mapOf("quiet" to 5), gpu.capped)
            assertEquals(id, setOf("balanced", "performance"), gpu.uncapped)
            assertEquals(id, 5.0 / 13, gpu.fraction("quiet")!!, 0.0)
            assertNull(id, gpu.fraction("balanced"))
            assertNull(id, gpu.fraction("stock"))
        }
        assertNull(bundledCatalog().tables("general-live-opp"))
        assertNull(bundledCatalog().entry("nope"))
    }

    @Test
    fun `ratios divide exactly as the compiled planner does`() {
        // 1_785_600.0 / ODIN_ANCHOR_LITTLE_MAX_KHZ: a Double over an Int, never a decimal literal.
        assertEquals(1_785_600.0 / 3_532_800, Ratio(1_785_600, 3_532_800).toDouble(), 0.0)
        assertEquals(0.55, Ratio(55, 100).toDouble(), 0.0)
        assertEquals(0.78, Ratio(78, 100).toDouble(), 0.0)
        assertEquals(0.55.toRawBits(), Ratio(55, 100).toDouble().toRawBits())
        assertEquals(0.78.toRawBits(), Ratio(78, 100).toDouble().toRawBits())
    }

    // ---- whole-catalog refusals ----------------------------------------------------------

    @Test
    fun `byte level refusals`() {
        val padded = bundledBytes().copyOf(FamilyCatalogLimits.MAX_BYTES).also { out ->
            for (i in bundledBytes().size until out.size) out[i] = ' '.code.toByte()
        }
        assertEquals(FamilyCatalogLimits.MAX_BYTES, padded.size)
        // Exactly 64 KiB is allowed; one byte more is not.
        accepted(padded)
        assertEquals(CatalogRejection.TOO_LARGE, rejection(padded + ' '.code.toByte()).reason)

        assertEquals(CatalogRejection.SYNTAX, rejection(ByteArray(0)).reason)
        assertEquals(CatalogRejection.ENCODING, rejection(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bundledBytes()).reason)
        assertEquals(CatalogRejection.ENCODING, rejection(ascii("{\"schema\":1,\"label\":\"caf") + byteArrayOf(0xC3.toByte(), 0xA9.toByte()) + ascii("\"}")).reason)
        assertEquals(CatalogRejection.ENCODING, rejection(ascii("{\"a\":\"x") + byteArrayOf(0) + ascii("\"}")).reason)
        assertEquals(CatalogRejection.ENCODING, rejection(ascii("{\"a\":\"x") + byteArrayOf(0x7F) + ascii("\"}")).reason)
        // A raw newline inside a string is not JSON either.
        assertEquals(CatalogRejection.ENCODING, rejection(ascii("{\"a\":\"x\ny\"}")).reason)
    }

    @Test
    fun `syntax refusals include everything org json would otherwise accept leniently`() {
        val text = String(bundledBytes(), Charsets.US_ASCII)
        val cases = linkedMapOf(
            "truncated" to text.substring(0, text.length / 2),
            "not an object" to "[1, 2]",
            "a bare number" to "7",
            "null" to "null",
            "trailing content" to "$text x",
            "second document" to "$text $text",
            "line comment" to "// note\n$text",
            "hash comment" to "# note\n$text",
            "block comment" to text.replaceFirst("{", "{ /* note */"),
            "single-quoted key" to text.replaceFirst("\"schema\"", "'schema'"),
            "equals for colon" to text.replaceFirst("\"revision\": 1", "\"revision\" = 1"),
            "semicolon separator" to text.replaceFirst("\"revision\": 1,", "\"revision\": 1;"),
            "nesting beyond 12" to "{\"a\":" + "[".repeat(20) + "]".repeat(20) + "}",
        )
        val failures = cases.filter { (_, document) ->
            val parsed = parse(ascii(document))
            !(parsed is FamilyCatalogParse.Rejected && parsed.reason == CatalogRejection.SYNTAX)
        }.keys
        assertTrue("not refused as SYNTAX: $failures", failures.isEmpty())
    }

    @Test
    fun `schema must be the integer 1 and a forward schema is refused whole`() {
        val cases = mapOf(
            "forward schema 2" to mutated { it.put("schema", 2) },
            "schema 0" to mutated { it.put("schema", 0) },
            "missing" to mutated { it.remove("schema") },
            "string" to mutated { it.put("schema", "1") },
            "boolean" to mutated { it.put("schema", true) },
            "double" to raw({ it.put("schema", "@d") }, "@d" to "1.0"),
        )
        cases.forEach { (name, bytes) -> assertEquals(name, CatalogRejection.SCHEMA, rejection(bytes).reason) }
    }

    @Test
    fun `revision must be a positive integer`() {
        val cases = mapOf(
            "zero" to mutated { it.put("revision", 0) },
            "negative" to mutated { it.put("revision", -3) },
            "missing" to mutated { it.remove("revision") },
            "string" to mutated { it.put("revision", "2") },
            "double" to raw({ it.put("revision", "@d") }, "@d" to "2.5"),
            "beyond Int" to raw({ it.put("revision", "@d") }, "@d" to "99999999999"),
        )
        cases.forEach { (name, bytes) -> assertEquals(name, CatalogRejection.REVISION, rejection(bytes).reason) }
        assertEquals(Int.MAX_VALUE, accepted(FamilyCatalogTestSupport.withRevision(Int.MAX_VALUE)).catalog.revision)
    }

    @Test
    fun `families must be a non-empty array within the bound`() {
        val sixtyFive = mutated { root ->
            val families = JSONArray()
            repeat(FamilyCatalogLimits.MAX_FAMILIES + 1) { families.put(extraEntry().put("id", "mediatek-$it")) }
            root.put("families", families)
        }
        val cases = mapOf(
            "missing" to mutated { it.remove("families") },
            "empty" to mutated { it.put("families", JSONArray()) },
            "an object" to mutated { it.put("families", JSONObject()) },
            "65 entries" to sixtyFive,
        )
        cases.forEach { (name, bytes) -> assertEquals(name, CatalogRejection.FAMILIES, rejection(bytes).reason) }
    }

    @Test
    fun `a reserved id missing, not certified-reference or only dropped refuses the whole catalog`() {
        FamilyCatalogLimits.RESERVED_IDS.forEach { reserved ->
            val removed = mutated { root ->
                val families = root.getJSONArray("families")
                val kept = JSONArray()
                for (i in 0 until families.length()) {
                    if (families.getJSONObject(i).getString("id") != reserved) kept.put(families.getJSONObject(i))
                }
                root.put("families", kept)
            }
            val missing = rejection(removed)
            assertEquals(reserved, CatalogRejection.RESERVED_ID, missing.reason)
            assertTrue(missing.detail, missing.detail.contains(reserved))

            val downgraded = rejection(mutated { entryAt(it, reserved).put("treatment", "general-live-opp") })
            assertEquals(reserved, CatalogRejection.RESERVED_ID, downgraded.reason)

            // An entry that is dropped as invalid is as good as absent -- and the reason is named.
            val invalid = rejection(mutated { entryAt(it, reserved).getJSONObject("cpu").put("kind", "regex") })
            assertEquals(reserved, CatalogRejection.RESERVED_ID, invalid.reason)
            assertTrue(invalid.detail, invalid.detail.contains("cpu.kind.unknown"))
        }
    }

    @Test
    fun `two entries with one id refuse the whole catalog`() {
        val twice = mutated { root ->
            val families = root.getJSONArray("families")
            families.put(extraEntry())
            families.put(extraEntry().put("match", JSONObject("""{"soc":["OTHER1"]}""")))
        }
        val reservedTwice = mutated { root ->
            val copy = JSONObject(entryAt(root, "qualcomm-8gen2").toString())
            copy.put("match", JSONObject("""{"soc":["OTHER2"]}"""))
            root.getJSONArray("families").put(copy)
        }
        assertEquals(CatalogRejection.DUPLICATE_ID, rejection(twice).reason)
        assertEquals(CatalogRejection.DUPLICATE_ID, rejection(reservedTwice).reason)
    }

    @Test
    fun `an alias two entries both match refuses the whole catalog`() {
        fun soc(vararg aliases: String): (JSONObject) -> Unit = { entry ->
            entry.put("match", JSONObject().put("soc", JSONArray(aliases.toList())))
        }
        fun platform(vararg aliases: String): (JSONObject) -> Unit = { entry ->
            entry.getJSONObject("match").put("platform", JSONArray(aliases.toList()))
        }
        val cases = linkedMapOf(
            "same exact soc alias" to soc("CQ8725S"),
            "same alias after another" to soc("ZZZ111", "SM8250"),
            "prefix covers an exact alias" to soc("SM87*"),
            "the 8 Elite alias again, alone" to soc("SM8750"),
            "same platform alias" to platform("kalama"),
            "platform prefix covers an alias" to platform("kal*"),
        )
        cases.forEach { (name, edit) ->
            val result = rejection(withEntry(extraEntry().also(edit)))
            assertEquals(name, CatalogRejection.ALIAS_OVERLAP, result.reason)
            assertTrue("$name: ${result.detail}", result.detail.contains("mediatek-extra-family"))
        }

        // Two prefixes where one extends the other.
        val nested = mutated { root ->
            val families = root.getJSONArray("families")
            families.put(extraEntry())
            families.put(extraEntry().put("id", "mediatek-extra-two").put("match", JSONObject("""{"soc":["XYZ9*"]}""")))
        }
        assertEquals(CatalogRejection.ALIAS_OVERLAP, rejection(nested).reason)

        // Disjoint prefixes, the same text in different namespaces, and a repeat inside one entry are all fine.
        val fine = mutated { root ->
            val families = root.getJSONArray("families")
            families.put(extraEntry().put("match", JSONObject("""{"soc":["XYZ99*","XYZ99*"],"platform":["sm8750"]}""")))
            families.put(extraEntry().put("id", "mediatek-extra-two").put("match", JSONObject("""{"soc":["XYZ98*"]}""")))
        }
        assertEquals(5, accepted(fine).catalog.families.size)
        assertEquals(listOf("XYZ99*"), accepted(fine).catalog.entry("mediatek-extra-family")!!.socAliases)
    }

    @Test
    fun `alias overlap is symmetric and exact`() {
        val pairs = listOf(
            Triple("SM8750", "SM8750", true),
            Triple("SM8750", "SM8751", false),
            Triple("SM87*", "SM8750", true),
            Triple("SM8750", "SM87*", true),
            Triple("SM87*", "SM88*", false),
            Triple("SM8*", "SM87*", true),
            Triple("SM87*", "SM8*", true),
            Triple("SM8750*", "SM8750", true),
            Triple("SM8750", "SM875", false),
        )
        pairs.forEach { (a, b, overlaps) ->
            assertEquals("$a vs $b", overlaps, FamilyCatalogParser.aliasesOverlap(a, b))
            assertEquals("$b vs $a", overlaps, FamilyCatalogParser.aliasesOverlap(b, a))
        }
    }

    // ---- per-entry drops --------------------------------------------------------------------

    private fun anchorCpu() = JSONObject(
        """
        {"kind":"anchor","profiles":{
          "quiet":{"little":[1785600,3532800],"prime":[2246400,4320000]},
          "balanced":{"little":[1996800,3532800],"prime":[2438400,4320000]}}}
        """.trimIndent(),
    )

    private fun transposedGpu() = JSONObject(
        """{"kind":"transposed","anchorLevels":14,"capped":{"quiet":5},"uncapped":["balanced","performance"]}""",
    )

    private fun levelsGpu() = JSONObject("""{"kind":"levels","levels":{"quiet":5,"balanced":0,"performance":0}}""")

    private fun khzProfile(entry: JSONObject, profile: String) = entry.getJSONObject("cpu").getJSONObject("profiles").getJSONObject(profile)

    private fun invalid(reason: String, edit: (JSONObject) -> Unit): Pair<String, (JSONObject) -> Unit> = reason to edit

    private fun soc(count: Int) = JSONArray((0 until count).map { "AB${it.toString().padStart(2, '0')}" })

    @Test
    fun `an invalid entry is dropped with a named reason and the rest are kept`() {
        // Each case breaks exactly one thing in an otherwise valid fourth entry.
        val cases = linkedMapOf<String, Pair<String, (JSONObject) -> Unit>>(
            "id missing" to invalid("id.missing") { it.remove("id") },
            "id not a string" to invalid("id.missing") { it.put("id", 5) },
            "id too short" to invalid("id.invalid") { it.put("id", "ab") },
            "id upper case" to invalid("id.invalid") { it.put("id", "Extra-Family") },
            "id underscore" to invalid("id.invalid") { it.put("id", "extra_family") },
            "id 41 characters" to invalid("id.invalid") { it.put("id", "a".repeat(41)) },
            "compiled fallback id" to invalid("id.compiled-fallback") { it.put("id", "general-live-opp") },
            "compiled fallback candidate id" to invalid("id.compiled-fallback") { it.put("id", "qualcomm-adreno-candidate") },
            // F-P2-2: an id is never allowed into the chassis namespace, and never to mint a compiled profile.
            "id without a vendor prefix" to invalid("id.vendor-prefix") { it.put("id", "extra-family") },
            "id claiming the retroid fan family" to invalid("id.vendor-prefix") { it.put("id", "retroid-x") },
            "id claiming the ayn fan family" to invalid("id.vendor-prefix") { it.put("id", "ayn-nova") },
            "id that is only a vendor prefix" to invalid("id.vendor-prefix") { it.put("id", "qualcomm-") },
            "id with a vendor name inside it" to invalid("id.vendor-prefix") { it.put("id", "my-qualcomm-chip") },
            "id minting the Odin 3 profile" to invalid("id.compiled-profile") { it.put("id", "ayn-odin3-cq8725s") },
            "id minting the Thor profile" to invalid("id.compiled-profile") { it.put("id", "ayn-qcs8550") },
            "id minting the Nova profile" to invalid("id.compiled-profile") { it.put("id", "retroid-qcs8550") },
            "id equal to the RP5 profile" to invalid("id.compiled-profile") { it.put("id", "rp5") },
            "id equal to the Odin 3 profile itself" to invalid("id.compiled-profile") { it.put("id", "ayn-odin3-cq8725s-pserver") },
            "label missing" to invalid("label.missing") { it.remove("label") },
            "label empty" to invalid("label.length") { it.put("label", "") },
            "label 65 characters" to invalid("label.length") { it.put("label", "x".repeat(65)) },
            "label blank" to invalid("label.blank") { it.put("label", "   ") },
            "treatment missing" to invalid("treatment.missing") { it.remove("treatment") },
            "treatment unknown" to invalid("treatment.unknown") { it.put("treatment", "certified") },
            "match missing" to invalid("match.missing") { it.remove("match") },
            "soc missing" to invalid("match.soc.missing") { it.getJSONObject("match").remove("soc") },
            "soc empty" to invalid("match.soc.empty") { it.getJSONObject("match").put("soc", JSONArray()) },
            "soc not an array" to invalid("match.soc.not-array") { it.getJSONObject("match").put("soc", "MT6893") },
            "soc 17 aliases" to invalid("match.soc.too-many") { it.getJSONObject("match").put("soc", soc(17)) },
            "soc lower case" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf("mt6893"))) },
            "soc one character" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf("A"))) },
            "soc 33 characters" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf("A".repeat(33)))) },
            "soc a bare star" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf("*"))) },
            "soc prefix of two" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf("SM*"))) },
            "soc star inside" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf("SM*87"))) },
            "soc with a space" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf("MT 6893"))) },
            "soc regex" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf("SM8(7|8)50"))) },
            "soc not a string" to invalid("match.soc.alias-invalid") { it.getJSONObject("match").put("soc", JSONArray(listOf(5))) },
            "platform not an array" to invalid("match.platform.not-array") { it.getJSONObject("match").put("platform", "kalama") },
            "platform upper case" to invalid("match.platform.alias-invalid") { it.getJSONObject("match").put("platform", JSONArray(listOf("KALAMA"))) },
            "platform 9 aliases" to invalid("match.platform.too-many") { it.getJSONObject("match").put("platform", JSONArray((0 until 9).map { n -> "plat$n" })) },
            "cpu missing" to invalid("cpu.missing") { it.remove("cpu") },
            "cpu kind missing" to invalid("cpu.kind.unknown") { it.getJSONObject("cpu").remove("kind") },
            "cpu kind a regex engine" to invalid("cpu.kind.unknown") { it.getJSONObject("cpu").put("kind", "regex") },
            "khz below 100000" to invalid("cpu.khz.out-of-bounds") { khzProfile(it, "quiet").put("0", 99_999) },
            "khz above 6000000" to invalid("cpu.khz.out-of-bounds") { khzProfile(it, "quiet").put("0", 6_000_001) },
            "khz negative" to invalid("cpu.khz.out-of-bounds") { khzProfile(it, "quiet").put("0", -500_000) },
            "khz a string" to invalid("cpu.khz.not-integer") { khzProfile(it, "quiet").put("0", "500000") },
            "khz a boolean" to invalid("cpu.khz.not-integer") { khzProfile(it, "quiet").put("0", true) },
            "khz policy not a number" to invalid("cpu.khz.policy-id") { khzProfile(it, "quiet").put("x", 500_000) },
            "khz policy 32" to invalid("cpu.khz.policy-id") { khzProfile(it, "quiet").put("32", 500_000) },
            "khz policy 01" to invalid("cpu.khz.policy-id") { khzProfile(it, "quiet").put("01", 500_000) },
            "khz policy -1" to invalid("cpu.khz.policy-id") { khzProfile(it, "quiet").put("-1", 500_000) },
            "khz 17 policies" to invalid("cpu.khz.policy-count") { entry -> khzProfile(entry, "quiet").also { row -> (0..16).forEach { n -> row.put("$n", 500_000) } } },
            "khz empty row" to invalid("cpu.khz.policy-count") { it.getJSONObject("cpu").getJSONObject("profiles").put("quiet", JSONObject()) },
            "khz row not an object" to invalid("cpu.khz.row-invalid") { it.getJSONObject("cpu").getJSONObject("profiles").put("quiet", 5) },
            "khz policy sets differ" to invalid("cpu.khz.policy-sets-differ") { khzProfile(it, "balanced").put("7", 800_000) },
            "khz profile stock" to invalid("cpu.khz.profiles.profile-unknown") { it.getJSONObject("cpu").getJSONObject("profiles").put("stock", JSONObject().put("0", 500_000)) },
            "khz profiles empty" to invalid("cpu.khz.profiles.empty") { it.getJSONObject("cpu").put("profiles", JSONObject()) },
            "khz profiles missing" to invalid("cpu.khz.profiles.missing") { it.getJSONObject("cpu").remove("profiles") },
            "anchor numerator above the max" to invalid("cpu.anchor.above-max") { it.put("cpu", anchorCpu().also { c -> c.getJSONObject("profiles").getJSONObject("quiet").put("little", JSONArray(listOf(3_532_801, 3_532_800))) }) },
            "anchor numerator below the bound" to invalid("cpu.anchor.out-of-bounds") { it.put("cpu", anchorCpu().also { c -> c.getJSONObject("profiles").getJSONObject("quiet").put("little", JSONArray(listOf(99_999, 3_532_800))) }) },
            "anchor denominator above the bound" to invalid("cpu.anchor.out-of-bounds") { it.put("cpu", anchorCpu().also { c -> c.getJSONObject("profiles").getJSONObject("quiet").put("prime", JSONArray(listOf(2_246_400, 6_000_001))) }) },
            "anchor pair not an array" to invalid("cpu.anchor.pair.not-array") { it.put("cpu", anchorCpu().also { c -> c.getJSONObject("profiles").getJSONObject("quiet").put("little", 5) }) },
            "anchor pair of three" to invalid("cpu.anchor.pair.length") { it.put("cpu", anchorCpu().also { c -> c.getJSONObject("profiles").getJSONObject("quiet").put("little", JSONArray(listOf(1_785_600, 3_532_800, 1))) }) },
            "anchor pair with a string" to invalid("cpu.anchor.pair.not-integer") { it.put("cpu", anchorCpu().also { c -> c.getJSONObject("profiles").getJSONObject("quiet").put("little", JSONArray(listOf("1785600", 3_532_800))) }) },
            "anchor row not an object" to invalid("cpu.anchor.row-invalid") { it.put("cpu", anchorCpu().also { c -> c.getJSONObject("profiles").put("quiet", 5) }) },
            "anchor prime missing" to invalid("cpu.anchor.pair.not-array") { it.put("cpu", anchorCpu().also { c -> c.getJSONObject("profiles").getJSONObject("quiet").remove("prime") }) },
            "general factor of one" to invalid("cpu.general.factor-bounds") { it.put("cpu", generalCpu(JSONArray(listOf(100, 100)), JSONArray(listOf(78, 100)))) },
            "general factor of zero" to invalid("cpu.general.factor-bounds") { it.put("cpu", generalCpu(JSONArray(listOf(0, 100)), JSONArray(listOf(78, 100)))) },
            "general denominator of one" to invalid("cpu.general.factor-bounds") { it.put("cpu", generalCpu(JSONArray(listOf(55, 1)), JSONArray(listOf(78, 100)))) },
            "general denominator past 1000" to invalid("cpu.general.factor-bounds") { it.put("cpu", generalCpu(JSONArray(listOf(55, 100)), JSONArray(listOf(78, 1_001)))) },
            "general factors not an object" to invalid("cpu.general.factors-invalid") { it.put("cpu", JSONObject().put("kind", "general").put("factors", 5)) },
            "general medium missing" to invalid("cpu.general.factor.not-array") { it.put("cpu", JSONObject().put("kind", "general").put("factors", JSONObject().put("large", JSONArray(listOf(55, 100))))) },
            "gpu not an object" to invalid("gpu.not-object") { it.put("gpu", 5) },
            "gpu kind unknown" to invalid("gpu.kind.unknown") { it.getJSONObject("gpu").put("kind", "regex") },
            "levels above 63" to invalid("gpu.levels.out-of-range") { it.put("gpu", levelsGpu().also { g -> g.getJSONObject("levels").put("quiet", 64) }) },
            "levels negative" to invalid("gpu.levels.out-of-range") { it.put("gpu", levelsGpu().also { g -> g.getJSONObject("levels").put("quiet", -1) }) },
            "levels a string" to invalid("gpu.levels.not-integer") { it.put("gpu", levelsGpu().also { g -> g.getJSONObject("levels").put("quiet", "5") }) },
            "levels for stock" to invalid("gpu.levels.profile-unknown") { it.put("gpu", levelsGpu().also { g -> g.getJSONObject("levels").put("stock", 0) }) },
            "levels empty" to invalid("gpu.levels.empty") { it.put("gpu", levelsGpu().also { g -> g.put("levels", JSONObject()) }) },
            "levels missing" to invalid("gpu.levels.missing") { it.put("gpu", levelsGpu().also { g -> g.remove("levels") }) },
            "anchor levels of one" to invalid("gpu.anchor-levels") { it.getJSONObject("gpu").put("anchorLevels", 1) },
            "anchor levels of 65" to invalid("gpu.anchor-levels") { it.getJSONObject("gpu").put("anchorLevels", 65) },
            "anchor levels a string" to invalid("gpu.anchor-levels") { it.getJSONObject("gpu").put("anchorLevels", "14") },
            "anchor levels missing" to invalid("gpu.anchor-levels") { it.getJSONObject("gpu").remove("anchorLevels") },
            "capped level past the table" to invalid("gpu.capped.out-of-range") { it.getJSONObject("gpu").getJSONObject("capped").put("quiet", 14) },
            "capped for stock" to invalid("gpu.capped.profile-unknown") { it.getJSONObject("gpu").getJSONObject("capped").put("stock", 1) },
            "uncapped stock" to invalid("gpu.uncapped.profile-unknown") { it.getJSONObject("gpu").put("uncapped", JSONArray(listOf("stock"))) },
            "uncapped not a string" to invalid("gpu.uncapped.profile-unknown") { it.getJSONObject("gpu").put("uncapped", JSONArray(listOf(5))) },
            "uncapped not an array" to invalid("gpu.uncapped.not-array") { it.getJSONObject("gpu").put("uncapped", "balanced") },
            "profile capped and uncapped" to invalid("gpu.profile-capped-and-uncapped") { it.getJSONObject("gpu").getJSONObject("capped").put("balanced", 2) },
            "transposed with nothing" to invalid("gpu.transposed-empty") { it.getJSONObject("gpu").put("capped", JSONObject()).remove("uncapped") },
            "vendor mode not an object" to invalid("vendorMode.not-object") { it.put("vendorMode", 5) },
            "vendor mode for stock" to invalid("vendorMode.profile-unknown") { it.getJSONObject("vendorMode").put("stock", 1) },
            "vendor mode 3" to invalid("vendorMode.out-of-range") { it.getJSONObject("vendorMode").put("balanced", 3) },
            "vendor mode -1" to invalid("vendorMode.out-of-range") { it.getJSONObject("vendorMode").put("balanced", -1) },
            "vendor mode a string" to invalid("vendorMode.not-integer") { it.getJSONObject("vendorMode").put("balanced", "1") },
            "transport unknown" to invalid("transports.unknown") { it.put("transports", JSONArray(listOf("sysfs-anything"))) },
            "transport not a string" to invalid("transports.unknown") { it.put("transports", JSONArray(listOf(5))) },
            "transports not an array" to invalid("transports.not-array") { it.put("transports", "fan-pwm") },
            "transports more than exist" to invalid("transports.too-many") { it.put("transports", JSONArray(List(7) { "fan-pwm" })) },
        )

        val failures = ArrayList<String>()
        cases.forEach { (name, case) ->
            val (reason, edit) = case
            val parsed = parse(withEntry(extraEntry().also(edit)))
            if (parsed !is FamilyCatalogParse.Accepted) {
                failures += "$name: the whole catalog was refused: $parsed"
                return@forEach
            }
            val dropped = parsed.dropped.singleOrNull()
            if (dropped == null || dropped.reason != reason || dropped.index != 3) {
                failures += "$name: expected '$reason' at #3, got ${parsed.dropped}"
            }
            if (parsed.catalog.families.map { it.id } != listOf("qualcomm-8-elite", "qualcomm-8gen2", "qualcomm-sm8250")) {
                failures += "$name: the valid entries were not kept"
            }
        }
        assertTrue("\n" + failures.joinToString("\n"), failures.isEmpty())
        assertTrue("the corpus should stay broad", cases.size >= 90)
    }

    private fun generalCpu(large: JSONArray, medium: JSONArray) = JSONObject()
        .put("kind", "general")
        .put("factors", JSONObject().put("large", large).put("medium", medium))

    @Test
    fun `a dropped entry names its id when the id is readable`() {
        val broken = extraEntry().also { it.getJSONObject("cpu").put("kind", "bogus") }
        val noId = extraEntry().also { it.remove("id") }
        val parsed = accepted(mutated { root ->
            root.getJSONArray("families").put(broken)
            root.getJSONArray("families").put(noId)
            root.getJSONArray("families").put("not an object")
        })

        assertEquals(
            listOf(
                DroppedEntry(3, "mediatek-extra-family", "cpu.kind.unknown"),
                DroppedEntry(4, null, "id.missing"),
                DroppedEntry(5, null, "entry.not-object"),
            ),
            parsed.dropped,
        )
        assertEquals(3, parsed.catalog.families.size)
    }

    @Test
    fun `the label bound is exactly 64`() {
        // The 8 Elite's own label is 64 characters; one more is refused, and so is a non-ASCII or control character.
        assertEquals(64, bundledCatalog().entry("qualcomm-8-elite")!!.label.length)
        val sixtyFour = accepted(withEntry(extraEntry().put("label", "y".repeat(64))))
        assertEquals(64, sixtyFour.catalog.entry("mediatek-extra-family")!!.label.length)

        val accent = raw({ it.getJSONArray("families").put(extraEntry().put("label", "@label")) }, "@label" to "\"Caf\\u00e9\"")
        val control = raw({ it.getJSONArray("families").put(extraEntry().put("label", "@label")) }, "@label" to "\"Bad\\u0001label\"")
        val separator = raw({ it.getJSONArray("families").put(extraEntry().put("label", "@label")) }, "@label" to "\"Line\\u2028break\"")
        listOf(accent, control, separator).forEach { bytes ->
            assertEquals("label.not-printable-ascii", accepted(bytes).dropped.single().reason)
        }
    }

    // ---- unknown fields and optional parts ----------------------------------------------------------

    @Test
    fun `unknown fields are ignored everywhere they may appear`() {
        val baseline = bundledCatalog()
        val noisy = mutated { root ->
            root.put("futureTopLevel", JSONObject().put("x", 1))
            val families = root.getJSONArray("families")
            for (i in 0 until families.length()) {
                val entry = families.getJSONObject(i)
                entry.put("note", "ignored")
                entry.put("futureEntryField", JSONArray(listOf(1, 2, 3)))
                entry.getJSONObject("match").put("futureMatchField", true)
                entry.getJSONObject("cpu").put("futureCpuField", 7)
                entry.getJSONObject("gpu").put("futureGpuField", "x")
                val profiles = entry.getJSONObject("cpu").getJSONObject("profiles")
                if (entry.getJSONObject("cpu").getString("kind") == "anchor") {
                    profiles.getJSONObject("quiet").put("futureRowField", 1)
                }
            }
        }

        val parsed = accepted(noisy)
        assertEquals(baseline, parsed.catalog)
        assertTrue(parsed.dropped.isEmpty())
    }

    @Test
    fun `optional parts default to nothing and a general entry defaults to the compiled factors`() {
        val parsed = accepted(
            withEntry(
                JSONObject(
                    """
                    {"id":"mediatek-bare-family","label":"Bare","treatment":"general-live-opp",
                     "match":{"soc":["BARE1"]},"cpu":{"kind":"general"}}
                    """.trimIndent(),
                ),
            ),
        ).catalog

        val entry = parsed.entry("mediatek-bare-family")!!
        assertEquals(emptyList<String>(), entry.platformAliases)
        assertEquals(FamilyCpuTable.General.DEFAULT, entry.tables.cpu)
        assertEquals(Ratio(55, 100), FamilyCpuTable.General.DEFAULT.large)
        assertEquals(Ratio(78, 100), FamilyCpuTable.General.DEFAULT.medium)
        assertSame(FamilyGpuTable.Full, entry.tables.gpu)
        assertEquals(emptyMap<String, Int>(), entry.tables.vendorMode)
        assertEquals(emptySet<FamilyTransport>(), entry.tables.transports)

        val explicit = accepted(
            withEntry(
                extraEntry().put("cpu", generalCpu(JSONArray(listOf(1, 2)), JSONArray(listOf(3, 4)))),
            ),
        ).catalog.entry("mediatek-extra-family")!!
        assertEquals(FamilyCpuTable.General(Ratio(1, 2), Ratio(3, 4)), explicit.tables.cpu)
    }

    @Test
    fun `the extra test entry is a valid khz family with a transposed GPU`() {
        val entry = accepted(withEntry(extraEntry())).catalog.entry("mediatek-extra-family")!!

        assertEquals(listOf("MT6893", "XYZ99*"), entry.socAliases)
        assertEquals(listOf("mt6893"), entry.platformAliases)
        assertEquals(
            FamilyCpuTable.Khz(
                mapOf(
                    "quiet" to mapOf(0 to 500_000, 4 to 600_000),
                    "balanced" to mapOf(0 to 700_000, 4 to 800_000),
                    "performance" to mapOf(0 to 900_000, 4 to 1_000_000),
                ),
            ),
            entry.tables.cpu,
        )
        assertEquals(setOf(FamilyTransport.CPU_SCALING_MAX, FamilyTransport.FAN_PWM), entry.tables.transports)
    }

    // ---- the alias index -----------------------------------------------------------------------------

    @Test
    fun `soc aliases match exactly, trimmed and case-insensitively`() {
        val catalog = bundledCatalog()

        mapOf(
            "CQ8725S" to "qualcomm-8-elite",
            "SM8750" to "qualcomm-8-elite",
            "QCS8550" to "qualcomm-8gen2",
            "SM8550" to "qualcomm-8gen2",
            "SM8250" to "qualcomm-sm8250",
            "sm8250" to "qualcomm-sm8250",
            "  Sm8750\n" to "qualcomm-8-elite",
        ).forEach { (soc, id) -> assertEquals(soc, id, catalog.resolve(soc)?.id) }

        listOf("", "   ", "SM8650", "SM87500", "SM875", "SM8750X", "CQ8725", "MT6893").forEach {
            assertNull("'$it' must not match", catalog.resolve(it))
        }
    }

    @Test
    fun `a star alias matches by prefix and a platform alias is the fallback`() {
        val catalog = accepted(withEntry(extraEntry())).catalog

        assertEquals("mediatek-extra-family", catalog.resolve("xyz991")?.id)
        assertEquals("mediatek-extra-family", catalog.resolve("XYZ99")?.id)
        assertNull(catalog.resolve("XYZ9"))
        assertNull(catalog.resolve("XYZ89"))

        // By platform only when the SoC model gave no match.
        assertEquals("qualcomm-8gen2", catalog.resolve("", "kalama")?.id)
        assertEquals("qualcomm-8-elite", catalog.resolve("unknown-soc", " SUN ")?.id)
        assertEquals("qualcomm-sm8250", catalog.resolve("n/a", "kona")?.id)
        assertEquals("mediatek-extra-family", catalog.resolve("n/a", "mt6893")?.id)
        assertNull(catalog.resolve("n/a", "pineapple"))
        assertNull(catalog.resolve("n/a", null))
        assertNull(catalog.resolve("n/a", "  "))
        // The SoC model wins over a contradicting platform.
        assertEquals("qualcomm-sm8250", catalog.resolve("SM8250", "kalama")?.id)

        val platformPrefix = accepted(
            withEntry(extraEntry().put("match", JSONObject("""{"soc":["PFX1"],"platform":["abc*"]}"""))),
        ).catalog
        assertEquals("mediatek-extra-family", platformPrefix.resolve("none", "abcd")?.id)
        assertNull(platformPrefix.resolve("none", "ab"))
    }

    @Test
    fun `resolution does not depend on the order of the entries`() {
        val forward = accepted(withEntry(extraEntry())).catalog
        val reversed = accepted(
            mutated { root ->
                val families = root.getJSONArray("families")
                families.put(extraEntry())
                val flipped = JSONArray()
                for (i in families.length() - 1 downTo 0) flipped.put(families.getJSONObject(i))
                root.put("families", flipped)
            },
        ).catalog

        listOf("CQ8725S", "SM8250", "QCS8550", "MT6893", "XYZ995", "SM8650", "").forEach {
            assertEquals(it, forward.resolve(it)?.id, reversed.resolve(it)?.id)
        }
        assertEquals(forward.resolve("n", "mt6893")?.id, reversed.resolve("n", "mt6893")?.id)
    }

    @Test
    fun `the board platform reader lower-cases, shape-checks and never throws`() {
        assertEquals("kalama", BoardPlatform.read { " Kalama\n" })
        assertEquals("sun", BoardPlatform.read { "sun" })
        assertNull(BoardPlatform.read { null })
        assertNull(BoardPlatform.read { "" })
        assertNull(BoardPlatform.read { "not a platform!" })
        assertNull(BoardPlatform.read { "x".repeat(33) })
        assertNull(BoardPlatform.read { error("getprop failed") })
        assertEquals("ro.board.platform", run {
            var asked: String? = null
            BoardPlatform.read { name -> asked = name; null }
            asked
        })
    }
}
