package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flavor-specific BuildConfig truth for TW04-A posture fields. */
class PostureBuildConfigTest {
    @Test
    fun `compiled posture flags match the active variant contract`() {
        when (BuildConfig.POSTURE) {
            TweakerPostureTruth.SAFE -> {
                assertFalse(BuildConfig.HARDWARE_WRITES_COMPILED)
                assertFalse(BuildConfig.DIAGNOSTICS_COMPILED)
                assertFalse(BuildConfig.SIGNED_MANIFEST_REQUIRED)
            }
            TweakerPostureTruth.LAB -> {
                assertTrue(BuildConfig.HARDWARE_WRITES_COMPILED)
                assertTrue(BuildConfig.DIAGNOSTICS_COMPILED)
                assertFalse(BuildConfig.SIGNED_MANIFEST_REQUIRED)
            }
            TweakerPostureTruth.PRODUCTION -> {
                assertTrue(BuildConfig.HARDWARE_WRITES_COMPILED)
                assertFalse(BuildConfig.DIAGNOSTICS_COMPILED)
                assertTrue(BuildConfig.SIGNED_MANIFEST_REQUIRED)
            }
            else -> error("Unknown posture BuildConfig: ${BuildConfig.POSTURE}")
        }
        assertEquals(BuildConfig.POSTURE, TweakerPostureTruth.posture)
        assertEquals(emptySet<String>(), TweakerPostureTruth.productQualifiedProfileIds())
        if (BuildConfig.POSTURE != TweakerPostureTruth.PRODUCTION) {
            assertTrue(BuildConfig.QUALIFICATION_TRUST_ANCHORS_BASE64.isEmpty())
            assertTrue(BuildConfig.QUALIFICATION_MANIFESTS_BASE64.isEmpty())
        }
    }

    @Test
    fun `service mutation entry gates preserve SAFE and production contracts`() {
        assertEquals(
            if (BuildConfig.POSTURE == TweakerPostureTruth.SAFE) "WRITE_GATE_DISABLED" else null,
            publicApplyPostureFailureCode(BuildConfig.HARDWARE_WRITES_COMPILED),
        )
        assertEquals(
            if (BuildConfig.POSTURE == TweakerPostureTruth.PRODUCTION) "API_UPGRADE_REQUIRED" else null,
            legacyMutationFailureCode(BuildConfig.POSTURE),
        )
    }

    // Thermal rung verification: the thermal-derate rung
    // diagnostic is LAB-only. This runs once per flavor (testProductionDebugUnitTest,
    // testSafeDebugUnitTest, testLabDebugUnitTest each observe their own compiled
    // DIAGNOSTICS_COMPILED constant), so it directly pins the command's unreachability in
    // production and safe, and its reachability in LAB, without needing a bound service/Activity.
    @Test
    fun `thermal derate rung apply is unreachable outside LAB`() {
        assertEquals(
            if (BuildConfig.DIAGNOSTICS_COMPILED) null else "LAB_ONLY",
            thermalDerateRungLabFailureCode(BuildConfig.DIAGNOSTICS_COMPILED),
        )
        assertEquals(BuildConfig.POSTURE == TweakerPostureTruth.LAB, BuildConfig.DIAGNOSTICS_COMPILED)
    }
}
