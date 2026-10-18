package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareClassifierTest {
    @Test
    fun `sm8250 handheld with pServer remains calibration required`() {
        val result = HardwareClassifier.classify(
            manufacturer = "Example",
            model = "Handheld",
            socModel = "SM8250",
            deviceProfileId = "generic-sm8250-pServer",
            pServerAvailable = true,
            cpuPolicyIds = setOf(0, 4, 7),
            gpuNodeAvailable = true,
            fanNodeAvailable = false,
            refreshRatesHz = listOf(60, 120),
        )

        assertEquals(CapabilityStatus.SUPPORTED, result.first)
        assertTrue(result.second.any { "SM8250" in it })
        assertTrue(result.second.any { "mimir-certified-reference" in it })
        // TR-10: a chipset-supported family (SM8250 is mimir-certified-reference) is admitted
        // through SupportedUncertifiedProfiles' code-resident envelope with or without a signed
        // manifest, so the "still owed" note -- which reads as a blocker -- must not fire here.
        assertFalse(result.second.any { "signed product qualification" in it })
    }

    @Test
    fun `rp5 profile still classifies when model matches`() {
        val result = HardwareClassifier.classify(
            manufacturer = "Moorechip",
            model = "Retroid Pocket 5",
            socModel = "SM8250",
            deviceProfileId = "rp5",
            pServerAvailable = true,
            cpuPolicyIds = setOf(0, 4, 7),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
        )

        assertEquals(CapabilityStatus.SUPPORTED, result.first)
    }

    @Test
    fun `missing PServer is unsupported`() {
        val result = HardwareClassifier.classify(
            manufacturer = "Moorechip",
            model = "Retroid Pocket 5",
            socModel = "SM8250",
            deviceProfileId = "rp5",
            pServerAvailable = false,
            cpuPolicyIds = setOf(0, 4, 7),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
        )

        assertEquals(CapabilityStatus.UNSUPPORTED, result.first)
        assertTrue(result.second.any { "PServerBinder" in it })
    }

    @Test
    fun `odin 3 two policy topology is a supported read contract`() {
        val result = HardwareClassifier.classify(
            manufacturer = "AYN",
            model = "Odin3",
            socModel = "CQ8725S",
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            pServerAvailable = true,
            cpuPolicyIds = setOf(0, 6),
            gpuNodeAvailable = true,
            fanNodeAvailable = false,
            refreshRatesHz = listOf(60, 120),
        )

        assertEquals(CapabilityStatus.SUPPORTED, result.first)
        assertTrue(result.second.any { "Odin 3" in it })
        assertTrue(result.second.any { "2 CPU policy" in it })
    }

    @Test
    fun `snapdragon 8 gen 2 aliases share one family contract`() {
        listOf("QCS8550", "SM8550").forEach { soc ->
            val result = HardwareClassifier.classify(
                manufacturer = "AYN",
                model = "Thor",
                socModel = soc,
                deviceProfileId = "ayn-qcs8550-pserver",
                pServerAvailable = true,
                cpuPolicyIds = setOf(0, 3, 7),
                gpuNodeAvailable = true,
                fanNodeAvailable = false,
                refreshRatesHz = listOf(60, 120),
            )

            assertEquals(soc, CapabilityStatus.SUPPORTED, result.first)
            assertTrue(result.second.any { "Snapdragon 8 Gen 2" in it })
        }
    }

    @Test
    fun `other Qualcomm Adreno topology enters candidate qualification`() {
        val result = HardwareClassifier.classify(
            manufacturer = "Example",
            model = "Future Handheld",
            socModel = "SM8650",
            deviceProfileId = "qualcomm-adreno-candidate-pserver",
            pServerAvailable = true,
            cpuPolicyIds = setOf(0, 2, 7),
            gpuNodeAvailable = true,
            fanNodeAvailable = false,
            refreshRatesHz = listOf(120),
        )

        assertEquals(CapabilityStatus.SUPPORTED, result.first)
        assertTrue(result.second.any { "general-live-opp" in it })
    }

    @Test
    fun `empty policy topology is unsupported even for a known family`() {
        val result = HardwareClassifier.classify(
            manufacturer = "AYN",
            model = "Odin3",
            socModel = "CQ8725S",
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            pServerAvailable = true,
            cpuPolicyIds = emptySet(),
            gpuNodeAvailable = true,
            fanNodeAvailable = false,
            refreshRatesHz = listOf(120),
        )

        assertEquals(CapabilityStatus.UNSUPPORTED, result.first)
        assertTrue(result.second.any { "No CPU cpufreq policies" in it })
    }

    @Test
    fun `non Qualcomm soc receives general CPU live OPP treatment`() {
        val result = HardwareClassifier.classify(
            manufacturer = "Example",
            model = "Phone",
            socModel = "MT6893",
            deviceProfileId = "unsupported-mt6893",
            pServerAvailable = true,
            cpuPolicyIds = setOf(0, 4, 7),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
        )

        assertEquals(CapabilityStatus.SUPPORTED, result.first)
        assertTrue(result.second.any { "General live-OPP CPU topology" in it })
        assertTrue(result.second.any { "general-live-opp" in it })
        // TR-10: general-live-opp is NOT a chipset-supported family -- SupportedUncertifiedProfiles
        // never admits it (compute() gates on MIMIR_CERTIFIED_REFERENCE), so an exact-device signed
        // manifest remains the only route to a product profile here. This is the device class the
        // "still owed" note is genuinely still true for.
        assertTrue(result.second.any { "signed product qualification" in it })
    }

    @Test
    fun `general CPU tuning does not require an Adreno GPU node`() {
        val result = HardwareClassifier.classify(
            manufacturer = "Example",
            model = "CPU-only Handheld",
            socModel = "MT6893",
            deviceProfileId = "general-live-opp-pserver",
            pServerAvailable = true,
            cpuPolicyIds = setOf(0, 4, 7),
            gpuNodeAvailable = false,
            fanNodeAvailable = false,
            refreshRatesHz = listOf(60),
        )

        assertEquals(CapabilityStatus.SUPPORTED, result.first)
        assertTrue(result.second.any { "CPU cluster tuning remains available" in it })
    }

    @Test
    fun `Snapdragon 8 Elite aliases receive one certified reference treatment`() {
        listOf("SM8750", "CQ8725S").forEach { soc ->
            val result = HardwareClassifier.classify(
                manufacturer = "AYN",
                model = "Odin3",
                socModel = soc,
                deviceProfileId = "ayn-odin3-cq8725s-pserver",
                pServerAvailable = true,
                cpuPolicyIds = setOf(0, 6),
                gpuNodeAvailable = true,
                fanNodeAvailable = false,
                refreshRatesHz = listOf(60, 120),
            )

            assertEquals(soc, CapabilityStatus.SUPPORTED, result.first)
            assertTrue(result.second.any { "mimir-certified-reference" in it })
        }
    }

    /**
     * TR-10. A tunable Nova (QCS8550, chipset-supported family, no signed manifest) legitimately
     * offers all three profiles through SupportedUncertifiedProfiles' code-resident admission
     * path -- policy 2026-08-21/22 retired certification as a behavioural gate. The notes
     * must not assert a signed manifest is *required* to get product profiles on this device.
     */
    @Test
    fun `tunable nova notes do not claim a signed manifest is required`() {
        val result = HardwareClassifier.classify(
            manufacturer = "Retroid",
            model = "Nova",
            socModel = "QCS8550",
            deviceProfileId = "retroid-qcs8550-pserver",
            pServerAvailable = true,
            cpuPolicyIds = setOf(0, 3, 7),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60, 120),
        )

        assertEquals(CapabilityStatus.SUPPORTED, result.first)
        assertFalse(result.second.any { "require a signed" in it })
        assertFalse(result.second.any { "remain owed" in it })
        if (TweakerPostureTruth.posture == TweakerPostureTruth.PRODUCTION) {
            assertTrue(result.second.any { "code-resident envelope" in it })
        }
    }
}
