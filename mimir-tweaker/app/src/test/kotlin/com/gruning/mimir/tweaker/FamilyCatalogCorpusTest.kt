package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.bundledCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host-side tooling (`tools/family-catalog`) only drafts; this Kotlin validator is the authority. This runs it
 * over every `.json` file in `tools/family-catalog/corpus`.
 *
 * Each corpus file is validated **on its own** ([FamilyCatalogParser.parseStandaloneEntry]), not wrapped in a
 * catalog: the drafts reuse reserved ids and the bundled aliases (`qualcomm-8gen2`, `qualcomm-sm8250`, the 8 Elite
 * SoC and platform names). In a catalog they would fail the reserved-ids rule, and appended to the bundled three
 * they would fail alias overlap. Those are catalog-level rules and are exercised by `FamilyCatalogTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FamilyCatalogCorpusTest {
    private val corpusDirectory: File = generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
        .map { File(it, "tools/family-catalog/corpus") }
        .firstOrNull { it.isDirectory }
        ?: error("Unable to locate tools/family-catalog/corpus from ${System.getProperty("user.dir")}")

    private fun corpus(): List<File> = corpusDirectory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
        .orEmpty()
        .sortedBy { it.name }

    private fun entry(name: String): FamilyEntry {
        val file = File(corpusDirectory, name)
        assertTrue("missing corpus file $name", file.isFile)
        return when (val parsed = FamilyCatalogParser.parseStandaloneEntry(file.readBytes())) {
            is FamilyEntryParse.Valid -> parsed.entry
            is FamilyEntryParse.Invalid -> error("$name is rejected by the validator: ${parsed.reason}")
        }
    }

    @Test
    fun `every corpus entry validates on its own`() {
        val files = corpus()
        assertTrue("the corpus is not empty", files.size >= 3)
        files.forEach { file ->
            val parsed = FamilyCatalogParser.parseStandaloneEntry(file.readBytes())
            assertTrue("${file.name}: $parsed", parsed is FamilyEntryParse.Valid)
        }
    }

    @Test
    fun `the corpus files are plain LF ASCII, the same bytes the catalog parser accepts`() {
        corpus().forEach { file ->
            val bytes = file.readBytes()
            assertTrue("${file.name}: ASCII only", bytes.all { it.toInt() in 0..0x7F })
            assertTrue("${file.name}: LF line endings", bytes.none { it == '\r'.code.toByte() })
        }
    }

    @Test
    fun `the rp5 draft keeps the reserved SM8250 id with the general treatment`() {
        val rp5 = entry("rp5-sm8250.json")
        assertEquals(TweakerHardwareFamilies.SM8250_FAMILY_ID, rp5.id)
        assertEquals(TweakerHardwareFamilies.GENERAL_LIVE_OPP, rp5.treatment)
        assertEquals(listOf("SM8250"), rp5.socAliases)
        assertEquals(listOf("kona"), rp5.platformAliases)
        assertTrue(rp5.tables.cpu is FamilyCpuTable.Anchor)
        assertTrue(rp5.tables.gpu is FamilyGpuTable.Transposed)
        // No vendor key was seen on that capture, so none is drafted.
        assertEquals(emptyMap<String, Int>(), rp5.tables.vendorMode)
    }

    @Test
    fun `the odin draft is a re-derivation as anchor and transposed, not the bundled khz and levels entry`() {
        val odin = entry("odin3-cq8725s.json")
        assertTrue(odin.tables.cpu is FamilyCpuTable.Anchor)
        assertTrue(odin.tables.gpu is FamilyGpuTable.Transposed)
        val bundled = bundledCatalog().entry(TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID)!!
        assertTrue(bundled.tables.cpu is FamilyCpuTable.Khz)
        assertTrue(bundled.tables.gpu is FamilyGpuTable.Levels)
        // It validates; it is not claimed to equal the bundled entry.
        assertNotEquals(bundled.tables.cpu, odin.tables.cpu)
        assertNotEquals(bundled.tables.gpu, odin.tables.gpu)
        assertEquals(listOf("CQ8725S"), odin.socAliases)
        assertEquals(listOf("sun"), odin.platformAliases)
    }

    @Test
    fun `the thor draft's cpu, gpu and vendor mode are exactly the bundled 8 Gen 2 entry's`() {
        val thor = entry("thor-qcs8550.json")
        val bundled = bundledCatalog().entry(TweakerHardwareFamilies.SNAPDRAGON_8_GEN_2_FAMILY_ID)!!
        assertEquals(bundled.tables.cpu, thor.tables.cpu)
        assertEquals(bundled.tables.gpu, thor.tables.gpu)
        assertEquals(bundled.tables.vendorMode, thor.tables.vendorMode)
        // Its aliases and transports are the draft's own (the transports list is wider than the bundled one).
        assertEquals(listOf("QCS8550"), thor.socAliases)
        assertEquals(listOf("kalama"), thor.platformAliases)
        assertTrue(FamilyTransport.GPU_DEVFREQ_MAX in thor.tables.transports)
    }

    @Test
    fun `an entry broken on one field is refused with a named reason`() {
        val good = File(corpusDirectory, "thor-qcs8550.json").readText(Charsets.US_ASCII)
        val broken = good.replace("\"anchorLevels\": 14", "\"anchorLevels\": 1")
        assertNotEquals(good, broken)
        val parsed = FamilyCatalogParser.parseStandaloneEntry(broken.toByteArray(Charsets.US_ASCII))
        assertEquals(FamilyEntryParse.Invalid("gpu.anchor-levels"), parsed)
        assertEquals(FamilyEntryParse.Invalid("not-object"), FamilyCatalogParser.parseStandaloneEntry("[]".toByteArray()))
        assertTrue(FamilyCatalogParser.parseStandaloneEntry(ByteArray(0)) is FamilyEntryParse.Invalid)
        assertTrue(FamilyCatalogParser.parseStandaloneEntry("{\"id\": 'x'}".toByteArray()) is FamilyEntryParse.Invalid)
    }
}
