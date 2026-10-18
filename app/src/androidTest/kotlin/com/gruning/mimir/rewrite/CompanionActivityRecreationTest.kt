package com.gruning.mimir.rewrite

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionActivityRecreationTest {
    @Test
    fun livePresentationRebindPreservesProcessRootAndMotionGate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { candidate ->
                candidate.displayId != Display.DEFAULT_DISPLAY &&
                    candidate.isValid &&
                    candidate.state == Display.STATE_ON
            }
        Assume.assumeTrue(
            "Requires a hardware-backed presentation display with compositor commit reporting",
            display != null && !isEmulator(),
        )

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val runtime = CompanionReturnCoordinator.runtime()
            val gate = CompanionProcessRuntime.motionGate
            val before = awaitMirror(runtime)
            val beforeMirror = before.disposition as SecondaryDisplayDisposition.MirrorPresented
            val beforeOwner = before.rendererOwnerId

            scenario.recreate()

            val after = awaitMirror(runtime) { snapshot -> snapshot.rendererOwnerId != beforeOwner }
            val afterMirror = after.disposition as SecondaryDisplayDisposition.MirrorPresented
            assertSame(gate, CompanionProcessRuntime.motionGate)
            assertNotEquals(beforeOwner, after.rendererOwnerId)
            assertEquals(beforeMirror.attempt.parentDispositionId, afterMirror.attempt.parentDispositionId)
        } finally {
            scenario.close()
        }
    }

    private fun awaitMirror(
        runtime: CompanionRuntimeCoordinator,
        extra: (CompanionRuntimeSnapshot) -> Boolean = { true },
    ): CompanionRuntimeSnapshot {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = runtime.snapshot.value
            if (snapshot.mirrorActive && extra(snapshot)) return snapshot
            SystemClock.sleep(50L)
        }
        error("Timed out waiting for an acknowledged companion mirror: ${runtime.snapshot.value}")
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.PRODUCT.startsWith("sdk_gphone")
}
