package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-5: [performanceOverlayResumeAction]'s pure
 * decision -- pinned behaviourally in [PerformanceOverlayResumeActionTest] -- is only honest if
 * [MainActivity.onCreate] and [MainActivity.onResume] actually route through it, unconditionally, with no
 * locally remembered belief in between. This module has no Robolectric harness that can construct
 * [MainActivity] and drive it through a real Activity lifecycle (same gap
 * [PerformanceOverlayRowPermissionWiringTest] documents for [AppBrightnessCoordinator]), so this is source
 * characterization, not a behavioural test: it pins the lines that make the live decision actually reach
 * [PerformanceOverlayService] on every call, not just that [performanceOverlayResumeAction] exists.
 *
 * This is the second version of this file. The first pinned a design (`performanceOverlayServiceRunning`, a
 * remembered "did I start it" belief, diffed against the live verdict) that `tools/cert-overlay-permission-lifecycle.sh`
 * falsified on real hardware: [PerformanceOverlayService] stops itself independently of `MainActivity`, so
 * that belief could silently disagree with reality. These assertions instead pin the *absence* of any such
 * per-Activity memory, and that both lifecycle callbacks share the exact same application path.
 */
class MainActivityPerformanceOverlayResumeWiringTest {

    @Test
    fun `no per-Activity memory of the service's running state survives in this file`() {
        val source = mainActivitySource()

        // Checks for the FIELD DECLARATION specifically (not just any mention -- the doc comment on
        // performanceOverlayResumeAction legitimately narrates this history by name) so a reintroduced
        // `private var performanceOverlayServiceRunning` is caught without forbidding explaining why not.
        assertFalse(
            "MainActivity must not keep a locally remembered belief about whether " +
                "PerformanceOverlayService is running (e.g. a performanceOverlayServiceRunning field) -- " +
                "the service can and does stop itself without telling MainActivity, so any such belief can " +
                "silently go stale; this was the exact falsified first attempt at this fix",
            Regex("(private\\s+)?var\\s+performanceOverlayServiceRunning\\b").containsMatchIn(source),
        )
    }

    @Test
    fun `onCreate and onResume both route through applyPerformanceOverlayLiveDecision`() {
        val source = mainActivitySource()

        val onCreateBody = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {")
            .substringBefore("override fun onSaveInstanceState(")
        assertTrue(
            "onCreate must apply the live performance-overlay decision by calling " +
                "applyPerformanceOverlayLiveDecision(), not keep its own separate start/stop logic",
            onCreateBody.contains("PerformanceOverlaySettingsStore.initialize(this)\n        applyPerformanceOverlayLiveDecision()"),
        )

        val onResumeBody = source.substringAfter("override fun onResume() {")
            .substringBefore("private fun settleReturningGameSession(")
        assertTrue(
            "onResume must apply the same live performance-overlay decision by calling " +
                "applyPerformanceOverlayLiveDecision() -- the identical call onCreate uses, not a second, " +
                "independently-drifting copy of the logic",
            onResumeBody.contains("applyPerformanceOverlayLiveDecision()"),
        )
    }

    @Test
    fun `applyPerformanceOverlayLiveDecision reads live state and applies START or STOP unconditionally`() {
        val source = mainActivitySource()
        val methodBody = source.substringAfter("private fun applyPerformanceOverlayLiveDecision() {")
            .substringBefore("\n    }")

        assertTrue(
            "applyPerformanceOverlayLiveDecision must call performanceOverlayResumeAction with a LIVE " +
                "Settings.canDrawOverlays(this) read -- a cached or stored permission read would silently " +
                "reintroduce O-5",
            methodBody.contains(
                "performanceOverlayResumeAction(\n" +
                    "                PerformanceOverlaySettingsStore.visibility.value,\n" +
                    "                Settings.canDrawOverlays(this),\n" +
                    "            )",
            ),
        )
        assertTrue(
            "PerformanceOverlayResumeAction.START must call PerformanceOverlayService.start(this) " +
                "unconditionally -- no state write, no guard against 'was it already running'",
            methodBody.contains(
                "PerformanceOverlayResumeAction.START -> PerformanceOverlayService.start(this)",
            ),
        )
        assertTrue(
            "PerformanceOverlayResumeAction.STOP must call PerformanceOverlayService.stop(this) " +
                "unconditionally",
            methodBody.contains(
                "PerformanceOverlayResumeAction.STOP -> PerformanceOverlayService.stop(this)",
            ),
        )
    }

    @Test
    fun `the resume decision is not a second competing lifecycle path around onCreate`() {
        val source = mainActivitySource()

        // performanceOverlayShouldRun must appear exactly once in this file -- inside
        // performanceOverlayResumeAction's own body. Both onCreate and onResume reach it only indirectly,
        // through applyPerformanceOverlayLiveDecision -> performanceOverlayResumeAction. A second direct
        // call site anywhere else would mean a second, independently-drifting copy of the same live check.
        val occurrences = Regex("performanceOverlayShouldRun\\(").findAll(source).count()
        assertTrue(
            "expected performanceOverlayShouldRun( to appear exactly once in MainActivity.kt (inside " +
                "performanceOverlayResumeAction's own body), found $occurrences -- any other call site would " +
                "be a second, competing decision path",
            occurrences == 1,
        )
        assertFalse(
            "performanceOverlayResumeAction must not reimplement visibility.enabled && canDrawOverlays " +
                "itself -- it is required to call performanceOverlayShouldRun(...)",
            Regex("fun performanceOverlayResumeAction[\\s\\S]{0,400}visibility\\.enabled\\s*&&\\s*canDrawOverlays")
                .containsMatchIn(source),
        )
    }

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MainActivity.kt not found from ${File(".").absolutePath}")
    }
}
