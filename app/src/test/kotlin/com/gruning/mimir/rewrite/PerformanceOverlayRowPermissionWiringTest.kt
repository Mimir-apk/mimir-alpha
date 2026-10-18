package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-1:
 * [performanceOverlayRowValue]'s pure decision -- pinned behaviourally in
 * [TweakerUsageAccessTest.overlay row honestly reports ACCESS -- not ON -- once draw-over-other-apps is revoked]
 * -- is only honest if [AppBrightnessCoordinator] actually feeds it a *live* read of
 * `Settings.canDrawOverlays`, not a value cached from when the panel was opened. This module has no
 * Robolectric harness that can construct [AppBrightnessCoordinator] with a real `Context` and drive it
 * through app resume (matches the project's own `AppBrightnessCoordinatorDeclineFeedbackTest`/
 * `GameTitleEditOverlayResetWiringTest`/`homeLaunchCardSource()` precedent for exactly this gap), so this is
 * source characterization, not a behavioural test: it pins the three lines that make the live re-check
 * actually reach the row, not just that a function exists.
 *
 * The differential: revert any of the three assertions' target lines back to their pre-fix shape (dropping
 * the `canDrawOverlays` field/args, or leaving the composable call site on the old two-argument
 * `performanceOverlayRowValue` call) and this test goes red -- the wiring, not just the pure function, is
 * what the finding named as missing.
 */
class PerformanceOverlayRowPermissionWiringTest {

    @Test
    fun `TweakerPanelProjection carries canDrawOverlays and both constructors read it live`() {
        val source = appBrightnessCoordinatorSource()

        assertTrue(
            "TweakerPanelProjection must carry a live canDrawOverlays field",
            source.contains("val canDrawOverlays: Boolean"),
        )
        assertTrue(
            "the initial tweakerPanelProjection must read Settings.canDrawOverlays(context), not a stored " +
                "preference",
            source.contains("canDrawOverlays = Settings.canDrawOverlays(context)"),
        )
        // Both construction sites (the property initializer and projectTweakerUtilityState's refresh) use
        // the identical expression -- assert it appears twice, once per site, so a future edit that only
        // fixes one of the two silently regresses the other.
        val occurrences = Regex("canDrawOverlays = Settings\\.canDrawOverlays\\(context\\)")
            .findAll(source)
            .count()
        assertTrue(
            "expected canDrawOverlays = Settings.canDrawOverlays(context) at both TweakerPanelProjection " +
                "construction sites (init + projectTweakerUtilityState), found $occurrences",
            occurrences == 2,
        )
    }

    @Test
    fun `refreshOnResume and the periodic panel refresh both recompute the projection`() {
        val source = appBrightnessCoordinatorSource()

        // refreshOnResume is the Activity-lifecycle onResume path (wired from MimirAppCoordinator's
        // DefaultLifecycleObserver.onResume) -- it must still call refreshTweakerUtilityState(), which is
        // what recomputes canDrawOverlays. This is the "re-check when the player returns from Android
        // Settings" requirement.
        val refreshOnResumeBody = source.substringAfter("fun refreshOnResume() {").substringBefore("\n    }")
        assertTrue(
            "refreshOnResume() must still call refreshTweakerUtilityState() so a revoked draw-over grant " +
                "is caught the moment the player returns",
            refreshOnResumeBody.contains("refreshTweakerUtilityState()"),
        )

        // The panel's own periodic foreground refresh (while Utility is open) must also keep recomputing
        // it, so a permission revoked while the panel is on screen -- not just via a resume -- is caught too.
        // 42db89216 made the one-second beat a telemetry-only tick (`periodic = true`: the capability read behind it runs every
        // ten seconds). The live canDrawOverlays read does not ride on that read: refreshTweakerUtilityState projects the panel
        // synchronously BEFORE it chooses the tick, so every beat, periodic or not, still recomputes it. Both halves are pinned.
        assertTrue(
            "the panel's periodic refresh loop must call refreshTweakerUtilityState(periodic = true)",
            source.contains("delay(UTILITY_TWEAKER_TELEMETRY_REFRESH_MS)\n                coordinator.refreshTweakerUtilityState(periodic = true)"),
        )
        val refreshBody = source.substringAfter("internal fun refreshTweakerUtilityState(periodic: Boolean = false) {")
            .substringBefore("\n    private fun projectTweakerUtilityState()")
        val projected = refreshBody.indexOf("projectTweakerUtilityState()")
        val chosen = refreshBody.indexOf("if (periodic) tweakerPort.refreshForPanelTick()")
        assertTrue(
            "a periodic beat must still project (re-read canDrawOverlays) before the tick is chosen, or a revoked draw-over grant " +
                "goes unseen while the panel is on screen",
            projected in 0 until chosen,
        )
    }

    @Test
    fun `the composable row call site passes the live canDrawOverlays read, not a hardcoded or stale value`() {
        val source = appBrightnessCoordinatorSource()

        assertTrue(
            "the Utility panel's performanceOverlayRowValue() call must pass " +
                "coordinator.performanceOverlayCanDrawOverlays as its third argument",
            source.contains(
                "performanceOverlayRowValue = performanceOverlayRowValue(\n" +
                    "            performanceOverlayVisibility,\n" +
                    "            coordinator.tweakerUsageAccessState,\n" +
                    "            coordinator.performanceOverlayCanDrawOverlays,\n" +
                    "        )",
            ),
        )
        assertFalse(
            "no call site may go back to the pre-fix two-argument performanceOverlayRowValue(visibility, " +
                "access) signature",
            Regex("performanceOverlayRowValue\\(\\s*performanceOverlayVisibility,\\s*coordinator\\.tweakerUsageAccessState,\\s*\\)")
                .containsMatchIn(source),
        )
    }

    private fun appBrightnessCoordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("AppBrightnessCoordinator.kt not found from ${File(".").absolutePath}")
    }
}
