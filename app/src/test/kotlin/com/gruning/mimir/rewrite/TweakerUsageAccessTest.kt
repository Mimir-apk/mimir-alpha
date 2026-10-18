package com.gruning.mimir.rewrite

import android.provider.Settings
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerUsageAccessTest {
    @Test
    fun `detail intent targets the Tweaker package before global fallbacks`() {
        val intents = tweakerUsageAccessIntentSpecs()

        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intents[0].action)
        assertEquals("package:com.gruning.mimir.tweaker", intents[0].data)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intents[1].action)
        assertEquals(Settings.ACTION_SETTINGS, intents[2].action)
        assertTrue(intents.all { it.newTask })
    }

    @Test
    fun `known denial is setup and unknown never invents denial`() {
        assertEquals(
            TweakerUsageAccessState.SET_UP,
            resolveTweakerUsageAccessState(true, capabilities(false)),
        )
        assertEquals(
            TweakerUsageAccessState.UNAVAILABLE,
            resolveTweakerUsageAccessState(true, null),
        )
        assertEquals(
            TweakerUsageAccessState.INSTALL_TWEAKER,
            resolveTweakerUsageAccessState(false, null),
        )
    }

    @Test
    fun `overlay row reflects access before preference`() {
        assertEquals(
            PerformanceOverlayRowValue.ACCESS,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.ALWAYS,
                TweakerUsageAccessState.SET_UP,
                canDrawOverlays = true,
            ),
        )
        assertEquals(
            PerformanceOverlayRowValue.ON,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.ALWAYS,
                TweakerUsageAccessState.READY,
                canDrawOverlays = true,
            ),
        )
        assertEquals(
            PerformanceOverlayRowValue.ON,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.GAMES_ONLY,
                TweakerUsageAccessState.READY,
                canDrawOverlays = true,
            ),
        )
    }

    /**
     * Overlay-permission regression: revoking "Display over other apps" in Android Settings used to leave this row reading ON forever,
     * because [performanceOverlayRowValue] never looked at [android.provider.Settings.canDrawOverlays] --
     * only at the persisted [PerformanceOverlayVisibility] preference and Tweaker usage access. This pins
     * both directions named by the finding: granted stays honest, and revoked reports honestly instead of
     * silently continuing to say ON.
     *
     * The differential (reverting the production fix and restoring it):
     * dropping the `!canDrawOverlays -> PerformanceOverlayRowValue.ACCESS` branch turns
     * `overlay row honestly reports ACCESS -- not ON -- once draw-over-other-apps is revoked` red with
     * `expected:<ACCESS> but was:<ON>`.
     */
    @Test
    fun `overlay row honestly reports ACCESS -- not ON -- once draw-over-other-apps is revoked`() {
        // Permission granted: the row reads ON exactly as it did before the fix.
        assertEquals(
            PerformanceOverlayRowValue.ON,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.ALWAYS,
                TweakerUsageAccessState.READY,
                canDrawOverlays = true,
            ),
        )
        assertEquals(
            PerformanceOverlayRowValue.ON,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.GAMES_ONLY,
                TweakerUsageAccessState.READY,
                canDrawOverlays = true,
            ),
        )

        // Permission revoked while the persisted preference is unchanged (ALWAYS/GAMES_ONLY, still
        // "enabled"): the row must not keep reading ON. It reports the same terse ACCESS value the row
        // already uses for a missing Tweaker usage access grant, never a bare, unexplained OFF.
        assertEquals(
            PerformanceOverlayRowValue.ACCESS,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.ALWAYS,
                TweakerUsageAccessState.READY,
                canDrawOverlays = false,
            ),
        )
        assertEquals(
            PerformanceOverlayRowValue.ACCESS,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.GAMES_ONLY,
                TweakerUsageAccessState.READY,
                canDrawOverlays = false,
            ),
        )

        // A player-initiated OFF stays OFF regardless of draw-over-other-apps state -- nothing is running,
        // so there is nothing for the missing grant to lie about.
        assertEquals(
            PerformanceOverlayRowValue.OFF,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.OFF,
                TweakerUsageAccessState.READY,
                canDrawOverlays = false,
            ),
        )

        // Tweaker usage access is still checked first: a missing draw grant never masks a missing usage
        // access grant behind the same ACCESS label incorrectly attributing readiness.
        assertEquals(
            PerformanceOverlayRowValue.UNAVAILABLE,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.ALWAYS,
                TweakerUsageAccessState.INSTALL_TWEAKER,
                canDrawOverlays = false,
            ),
        )
    }

    private fun capabilities(granted: Boolean) = TweakerCapabilities(
        status = TweakerCapabilityStatus.CALIBRATION_REQUIRED,
        deviceProfileId = "device",
        hardwareFingerprint = "fingerprint",
        supportedAxes = emptySet(),
        notes = emptyList(),
        usageAccessGranted = granted,
    )
}
