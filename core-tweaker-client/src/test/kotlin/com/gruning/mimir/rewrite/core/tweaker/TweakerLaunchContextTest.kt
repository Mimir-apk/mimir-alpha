package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TweakerLaunchContextTest {
    private val certifiedPerformance = TweakerLaunchSelectionContext(
        selectedProfileId = "performance",
        certifiedProfileIds = setOf("performance"),
        tweakerReachable = true,
    )
    private val stockSelection = TweakerLaunchSelectionContext(
        selectedProfileId = TWEAKER_STOCK_PROFILE_ID,
        certifiedProfileIds = setOf("performance"),
        tweakerReachable = false,
    )

    @Test
    fun `stock plus unavailable tweaker stays silent`() {
        assertNull(TweakerPreparationResult.Unavailable.launchWarning(stockSelection))
        assertNull(
            TweakerPreparationResult.Unavailable.toLaunchOverlayOrNull(stockSelection, "Pokemon Z-A"),
        )
    }

    @Test
    fun `a skipped preflight is silent, whatever its internal reason says`() {
        // Preflight is read-only and never authoritative about an active session, and `reason` is an
        // internal string -- interpolating it showed players "Tweaker owner is still settling", where
        // "owner" means the transport owner. Launch names the ACTIVE profile or says nothing.
        assertNull(
            TweakerPreparationResult.Skipped("Tweaker apply failed — stock settings restored")
                .launchWarning(certifiedPerformance),
        )
        assertNull(
            TweakerPreparationResult.Skipped("Tweaker owner is still settling")
                .launchWarning(certifiedPerformance),
        )
    }

    @Test
    fun `applied certified profile remains silent before post dispatch active readback`() {
        val overlay = TweakerPreparationResult.Applied("balanced")
            .toLaunchOverlayOrNull(certifiedPerformance.copy(selectedProfileId = "balanced", certifiedProfileIds = setOf("balanced")), "Pokemon Z-A")
        assertNull(overlay)
        assertEquals("READY FOR Pokemon Z-A.", formatTweakerLaunchReadyLine("Pokemon Z-A"))
    }

    @Test
    fun `stale selection stays silent even when skipped`() {
        val stale = TweakerLaunchSelectionContext(
            selectedProfileId = "balanced",
            certifiedProfileIds = setOf("performance"),
            tweakerReachable = true,
        )
        assertNull(
            TweakerPreparationResult.Skipped("Performance profile apply did not take — stock settings restored")
                .launchWarning(stale),
        )
    }

    @Test
    fun `degraded read-only pre-dispatch says no new session started`() {
        assertEquals(
            "Tweaker didn't respond — launch continues; no new tuning session started",
            tweakerPreDispatchDegradedWarning(certifiedPerformance, timedOut = true),
        )
        assertEquals(
            "Tweaker preparation failed — launch continues; no new tuning session started",
            tweakerPreDispatchDegradedWarning(certifiedPerformance, timedOut = false),
        )
    }

    @Test
    fun `degraded pre-dispatch stays silent for a stock selection`() {
        assertNull(tweakerPreDispatchDegradedWarning(stockSelection, timedOut = true))
        assertNull(tweakerPreDispatchDegradedWarning(stockSelection, timedOut = false))
    }

    @Test
    fun `unavailable intentional certified selection warns`() {
        assertEquals(
            "Tweaker unavailable — launch continues without tuning",
            TweakerPreparationResult.Unavailable.launchWarning(certifiedPerformance),
        )
    }
}
