package com.gruning.mimir.rewrite.core.tweaker

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only device proof. The harness must place PPSSPP foreground and grant Tweaker Usage Access first. */
@RunWith(AndroidJUnit4::class)
class TweakerLiveTelemetryDeviceTest {
    @Test
    fun foregroundPpssppPublishesTruthfulLiveTelemetry() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val session = TweakerSession(context)
            assertTrue("Tweaker service did not bind", session.bind())
            try {
                val samples = buildList {
                    repeat(8) {
                        session.getTelemetry()?.let(::add)
                        SystemClock.sleep(500L)
                    }
                }
                val foreground = samples.filter { it.foregroundPackageName == PpssppPackage }
                assertTrue("Tweaker never attested PPSSPP as foreground", foreground.isNotEmpty())
                assertTrue(
                    "Android 15 SurfaceFlinger layer never yielded a frame sample",
                    foreground.any { it.frameStatus == "AVAILABLE" && it.frameAverageFpsMilli != null },
                )
                assertTrue(
                    "aggregate CPU delta never warmed",
                    foreground.any { sample -> sample.cpuBusyPercent?.let { it in 0..100 } == true },
                )
                assertNotNull("classified CPU temperature unavailable", foreground.last().cpuTempC)
                assertNotNull("classified GPU temperature unavailable", foreground.last().gpuTempC)
                assertEquals(PpssppPackage, foreground.last().foregroundPackageName)
                foreground.last().let { sample ->
                    Log.i(
                        ProofTag,
                        "package=${sample.foregroundPackageName} fpsMilli=${sample.frameAverageFpsMilli} " +
                            "cpuTempC=${sample.cpuTempC} gpuTempC=${sample.gpuTempC} " +
                            "cpuBusy=${sample.cpuBusyPercent} gpuBusy=${sample.gpuBusyPercent} " +
                            "batteryPowerMw=${sample.batteryPowerMw} batteryStatus=${sample.batteryStatus}",
                    )
                }
            } finally {
                session.unbind()
            }
        }
    }

    private companion object {
        const val PpssppPackage = "org.ppsspp.ppsspp"
        const val ProofTag = "MimirTweakerTelemetryProof"
    }
}
