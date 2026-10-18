package com.gruning.mimir.rewrite.core.companionnative

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the launch cover's activity-resume observation, which cannot be run without a device's UsageStats. It
 * exists so that turning the poll on for a single-screen device does not also change what every other consumer of the
 * shared monitor hears.
 */
class ActivityResumeObservationSourceTest {

    private val source: String = monitorSource()

    private val observation: String = source.substring(
        source.indexOf("override fun observeActivityResumes("),
        source.indexOf("override fun stop()"),
    )

    @Test
    fun `the cover's poll never emits on the shared flows or moves the shared cursors`() {
        for (shared in listOf(
            "_returned",
            "_foregroundObserved",
            "_foreignForegroundObserved",
            "scanCursorMs",
            "lastForeignForegroundPackage",
            "lastForeignObservationAtMs",
            "taskObservationBurstUntilUptimeMs",
            "observationDemand",
        )) {
            assertFalse("the cover's observation must not touch $shared", observation.contains(shared))
        }
    }

    @Test
    fun `the cover's beat comes from the cadence policy and not from a literal`() {
        assertTrue(source.contains("cadencePolicy.launchCoverDelayMs(usageAccessGranted)"))
        assertFalse(
            "a second literal beat would drift from the task-evidence cadence",
            Regex("""postDelayed\(this,\s*\d""").containsMatchIn(source),
        )
    }

    @Test
    fun `each resume is read with a class name, from the foreground event kinds only`() {
        assertTrue(observation.contains("isForegroundUsageEvent(event.eventType)"))
        assertTrue(observation.contains("event.className"))
        assertTrue("re-reads are deduped, so the window may reach back", observation.contains("ledger.firstSight("))
        assertTrue(observation.contains("ACTIVITY_RESUME_LOOKBACK_MS"))
    }

    @Test
    fun `a listener that throws cannot end the poll`() {
        assertTrue(observation.contains("activity-resume listener failed"))
    }

    private fun monitorSource(): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidExternalForegroundMonitor.kt"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val local = File(dir, relative)
            if (local.isFile) return local.readText()
            val fromRoot = File(dir, "core-companion-native/$relative")
            if (fromRoot.isFile) return fromRoot.readText()
            dir = dir.parentFile
        }
        error("AndroidExternalForegroundMonitor.kt not found from ${File(".").absolutePath}")
    }
}
