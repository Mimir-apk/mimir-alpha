package com.gruning.mimir.rewrite.core.tweaker

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only release gate for the exact signed Odin Production helper. */
@RunWith(AndroidJUnit4::class)
class OdinProductionCapabilityDeviceTest {
    @Test
    fun signedProductionHelperPublishesProfilesAndLiveTelemetry() = runBlocking {
        assertTrue("This proof is scoped to Odin3", Build.MODEL.equals("Odin3", ignoreCase = true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = TweakerSession(context)

        assertTrue("signature-protected Tweaker service did not bind", session.bind())
        try {
            val apiVersion = requireNotNull(session.getApiVersion())
            val capabilities = requireNotNull(session.getCapabilities())
            val telemetry = session.getTelemetry()
            Log.i(
                ProofTag,
                "api=$apiVersion capabilities=$capabilities telemetry=$telemetry",
            )

            assertTrue("Production API v7 is required", apiVersion >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION)
            assertEquals(TweakerPosture.PRODUCTION, capabilities.posture)
            assertEquals(TweakerQualificationSource.SIGNED_MANIFEST, capabilities.qualificationSource)
            assertEquals(true, capabilities.mutationAvailable)
            assertEquals(
                listOf("stock", "quiet", "balanced", "performance"),
                selectableTweakerProfileIds(capabilities),
            )
            assertNotNull("live telemetry was unavailable", telemetry)
            assertNotNull("telemetry timestamp was unavailable", telemetry?.sampledAtElapsedRealtimeMs)
            assertNotNull("Odin CPU package temperature was unavailable", telemetry?.cpuPackageTempC)
            assertNotNull("Odin GPU temperature was unavailable", telemetry?.gpuTempC)
        } finally {
            session.unbind()
        }
    }

    private companion object {
        const val ProofTag = "MimirTweakerReleaseProof"
    }
}
