package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerModelsTest {
    @Test
    fun `calibration required is reachable for read contract`() {
        assertTrue(TweakerCapabilityStatus.CALIBRATION_REQUIRED.isReachable())
    }

    @Test
    fun `unsupported is not reachable`() {
        assertFalse(TweakerCapabilityStatus.UNSUPPORTED.isReachable())
    }

    @Test
    fun `parse status falls back to unavailable`() {
        assertEquals(TweakerCapabilityStatus.UNAVAILABLE, parseTweakerStatus(null))
        assertEquals(TweakerCapabilityStatus.SUPPORTED, parseTweakerStatus("SUPPORTED"))
    }

    private val certifiedPerformance = TweakerLaunchSelectionContext(
        selectedProfileId = "performance",
        certifiedProfileIds = setOf("performance"),
    )

    @Test
    fun `preparation skipped never warns -- launch names the active profile or is silent`() {
        assertNull(TweakerPreparationResult.Skipped("dry-run").launchWarning(certifiedPerformance))
        assertNull(TweakerPreparationResult.Applied("performance").launchWarning(certifiedPerformance))
    }

    @Test
    fun `unavailable warns only for intentional certified selection`() {
        assertEquals(
            "Tweaker unavailable — launch continues without tuning",
            TweakerPreparationResult.Unavailable.launchWarning(certifiedPerformance),
        )
        assertNull(
            TweakerPreparationResult.Unavailable.launchWarning(
                certifiedPerformance.copy(selectedProfileId = TWEAKER_STOCK_PROFILE_ID),
            ),
        )
    }

    @Test
    fun `selectable profiles are stock plus product-qualified only`() {
        assertEquals(
            listOf("stock", "balanced", "performance"),
            selectableTweakerProfileIds(setOf("performance", "balanced")),
        )
        assertEquals(
            listOf("stock"),
            selectableTweakerProfileIds(
                TweakerCapabilities(
                    status = TweakerCapabilityStatus.CALIBRATION_REQUIRED,
                    deviceProfileId = "rp5",
                    hardwareFingerprint = "fp",
                    supportedAxes = emptySet(),
                    certifiedProfileIds = setOf("performance"),
                    notes = emptyList(),
                    posture = TweakerPosture.SAFE,
                    mutationAvailable = false,
                    qualificationSource = TweakerQualificationSource.LOCAL_REPORT,
                ),
            ),
        )
    }

    @Test
    fun `launch confirmation copy is exact but pre dispatch applied state stays silent`() {
        assertEquals(
            "TWEAKER · BALANCED · ACTIVE",
            formatTweakerLaunchConfirmation("balanced"),
        )
        assertEquals("TWEAKER · PERFORMANCE · ACTIVE", formatTweakerLaunchConfirmation("performance"))
        assertNull(TweakerPreparationResult.Applied("balanced").launchConfirmation())
        assertNull(
            TweakerPreparationResult.Applied("performance")
                .toLaunchOverlayOrNull(certifiedPerformance, "Pokemon Z-A"),
        )
    }

    @Test
    fun `stock no op emits no launch overlay`() {
        assertNull(TweakerPreparationResult.StockNoOp.launchConfirmation())
        assertNull(TweakerPreparationResult.StockNoOp.toLaunchOverlayOrNull(certifiedPerformance, "Pokemon Z-A"))
    }

    @Test
    fun `only unavailable maps to a warning overlay -- skipped stays silent`() {
        assertNull(
            TweakerPreparationResult.Skipped("dry-run").toLaunchOverlayOrNull(certifiedPerformance, "Pokemon Z-A"),
        )
        assertEquals(
            TweakerLaunchOverlayMessage.Warning("Tweaker unavailable — launch continues without tuning"),
            TweakerPreparationResult.Unavailable.toLaunchOverlayOrNull(certifiedPerformance, "Pokemon Z-A"),
        )
    }

    @Test
    fun `profile stepping wraps certified profiles plus stock only`() {
        val ids = listOf("stock", "quiet", "balanced", "performance")
        assertEquals("quiet", steppedTweakerProfileId("stock", 1, ids))
        assertEquals("performance", steppedTweakerProfileId("balanced", 1, ids))
        assertEquals("stock", steppedTweakerProfileId("quiet", -1, ids))
        assertTrue(tweakerProfileCanStepLeft("balanced", ids))
        assertTrue(tweakerProfileCanStepRight("balanced", ids))
        assertFalse(tweakerProfileCanStepLeft("stock", listOf("stock")))
        assertFalse(tweakerProfileCanStepRight("stock", listOf("stock")))
    }

    @Test
    fun `profile toast caps stock label`() {
        assertEquals("STOCK", tweakerProfileDisplayCaps("stock"))
        assertEquals("EFFICIENCY", tweakerProfileDisplayCaps("quiet"))
        assertEquals("BALANCED", tweakerProfileDisplayCaps("balanced"))
        assertEquals("PERFORMANCE", tweakerProfileDisplayCaps("performance"))
    }
}
