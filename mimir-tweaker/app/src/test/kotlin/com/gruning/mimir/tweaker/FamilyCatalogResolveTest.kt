package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.extraEntry
import com.gruning.mimir.tweaker.FamilyCatalogTestSupport.withEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TweakerHardwareFamilies]: same signature and same answers as the compiled `when` it replaced, now read from
 * the catalog in force, with the compiled default when nothing is installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FamilyCatalogResolveTest {
    @Before
    fun nothingInstalled() = FamilyCatalogRegistry.install(null)

    @After
    fun restore() = FamilyCatalogRegistry.install(null)

    private fun install(bytes: ByteArray) {
        val parsed = FamilyCatalogParser.parse(bytes) as FamilyCatalogParse.Accepted
        FamilyCatalogRegistry.install(parsed.catalog)
    }

    @Test
    fun `with nothing installed the compiled default answers exactly as the compiled when did`() {
        assertSame(FamilyCatalogCompiledDefault.catalog, TweakerHardwareFamilies.catalog())
        val sm8250 = "qualcomm-sm8250" to "Qualcomm SM8250 / Snapdragon 865"
        val gen2 = "qualcomm-8gen2" to "Qualcomm QCS8550 / SM8550 / Snapdragon 8 Gen 2"
        val elite = "qualcomm-8-elite" to "Qualcomm SM8750 Snapdragon 8 Elite / CQ8725S Q-8750 / AYN Odin 3"
        mapOf(
            "SM8250" to sm8250, " sm8250 " to sm8250,
            "QCS8550" to gen2, "SM8550" to gen2, "qcs8550\n" to gen2,
            "CQ8725S" to elite, "SM8750" to elite, " cq8725s " to elite,
        ).forEach { (soc, expected) ->
            val family = TweakerHardwareFamilies.resolve(soc, boardPlatform = null)
            assertEquals("'$soc'", expected, family.id to family.label)
            assertEquals("'$soc'", TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE, family.profileTreatment)
        }

        // A Qualcomm-shaped SoC nobody has characterised: the compiled candidate. Anything else: the general family.
        listOf("SM8650", "SM8350", "SM8450", "SDM845", "MSM8998", "QCS9075", "CQ8725", "SM8250X", "sm8750-ac").forEach { soc ->
            val family = TweakerHardwareFamilies.resolve(soc, boardPlatform = null)
            assertEquals("'$soc'", "qualcomm-adreno-candidate", family.id)
            assertEquals("'$soc'", TweakerHardwareFamilies.GENERAL_LIVE_OPP, family.profileTreatment)
        }
        listOf("", "  ", "MT6893", "Tensor G3", "unknown", "kona", "ayn odin 3").forEach { soc ->
            val family = TweakerHardwareFamilies.resolve(soc, boardPlatform = null)
            assertEquals("'$soc'", "general-live-opp", family.id)
            assertEquals("'$soc'", "General live-OPP CPU topology", family.label)
        }
    }

    @Test
    fun `the compiled fallbacks are never data`() {
        assertNull(TweakerHardwareFamilies.tables("qualcomm-adreno-candidate"))
        assertNull(TweakerHardwareFamilies.tables("general-live-opp"))
        assertNull(TweakerHardwareFamilies.tables("no-such-family"))
        FamilyCatalogLimits.RESERVED_IDS.forEach { assertNotNull(it, TweakerHardwareFamilies.tables(it)) }
    }

    @Test
    fun `the board platform backs up a SoC miss and never overrides a SoC match`() {
        // The SoC string is unreadable ("unknown" is what the probe substitutes) but the platform says what it is.
        assertEquals("qualcomm-8gen2", TweakerHardwareFamilies.resolve("unknown", "kalama").id)
        assertEquals("qualcomm-8-elite", TweakerHardwareFamilies.resolve("", "sun").id)
        assertEquals("qualcomm-sm8250", TweakerHardwareFamilies.resolve("SM9999", "kona").id)
        // A SoC match wins whatever the platform says.
        assertEquals("qualcomm-8-elite", TweakerHardwareFamilies.resolve("SM8750", "kona").id)
        // A platform nobody knows changes nothing.
        assertEquals("qualcomm-adreno-candidate", TweakerHardwareFamilies.resolve("SM8650", "pineapple").id)
        assertEquals("general-live-opp", TweakerHardwareFamilies.resolve("MT6893", "mt6893").id)
        assertEquals("general-live-opp", TweakerHardwareFamilies.resolve("MT6893", null).id)
    }

    @Test
    fun `an installed catalog is what resolve and tables read, and removing it restores the default`() {
        install(withEntry(extraEntry()))
        // MT6893 is an exact alias, XYZ99* a prefix alias, mt6893 a platform alias of the extra entry.
        assertEquals("mediatek-extra-family", TweakerHardwareFamilies.resolve("MT6893", boardPlatform = null).id)
        assertEquals("mediatek-extra-family", TweakerHardwareFamilies.resolve("xyz9912", boardPlatform = null).id)
        assertEquals("mediatek-extra-family", TweakerHardwareFamilies.resolve("", "mt6893").id)
        assertEquals(TweakerHardwareFamilies.GENERAL_LIVE_OPP, TweakerHardwareFamilies.resolve("MT6893", null).profileTreatment)
        val tables = TweakerHardwareFamilies.tables("mediatek-extra-family")!!
        assertTrue(tables.cpu is FamilyCpuTable.Khz)
        assertTrue(tables.gpu is FamilyGpuTable.Transposed)
        // The reserved three are untouched.
        assertEquals("qualcomm-8-elite", TweakerHardwareFamilies.resolve("SM8750", null).id)

        FamilyCatalogRegistry.install(null)
        assertEquals("general-live-opp", TweakerHardwareFamilies.resolve("MT6893", null).id)
        assertNull(TweakerHardwareFamilies.tables("mediatek-extra-family"))
    }

    @Test
    fun `the classifier reads the installed family's label and treatment`() {
        install(withEntry(extraEntry()))
        val (status, notes) = HardwareClassifier.classify(
            manufacturer = "Example",
            model = "Handheld",
            socModel = "MT6893",
            deviceProfileId = "x",
            pServerAvailable = true,
            cpuPolicyIds = setOf(0, 4),
            gpuNodeAvailable = true,
            fanNodeAvailable = false,
            refreshRatesHz = listOf(60),
        )
        assertEquals(CapabilityStatus.SUPPORTED, status)
        assertTrue(notes.any { "Extra test family (general-live-opp)" in it })
        assertFalse(notes.any { "General live-OPP CPU topology" in it })
    }

    @Test
    fun `the one-argument resolve is the two-argument one with the real board platform`() {
        // On the JVM there is no getprop, so the real platform is null and both overloads agree for any SoC.
        listOf("SM8750", "SM8250", "MT6893", "", "SM8650").forEach { soc ->
            assertEquals(soc, TweakerHardwareFamilies.resolve(soc, boardPlatform = null), TweakerHardwareFamilies.resolve(soc))
        }
    }
}
