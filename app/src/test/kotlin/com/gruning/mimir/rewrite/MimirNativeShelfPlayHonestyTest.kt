package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library's Android
 * Games / Android Apps Play control -- [rememberMimirNativeShelfCoordinator]'s `launchPackage` lambda --
 * had a four-arm failure shape and reported on exactly one of four. The fourth arm (the session-mutex
 * refusal at `:262-277`, O-3, 2026-08-12) was already fixed and is pinned by this module's own
 * `SessionMutexRefusalWiringTest.nativeShelfLaunchPackageToastsTheSharedSentenceOnMutexRefusal`. This test
 * covers the remaining three: B1 (no launchable intent), B2 (`return_anchor_arm_failed`), B3
 * (`activity_not_started`).
 *
 * `launchPackage` is built inside `remember(...)` and its `scope.launch { }` body runs inside a
 * `@Composable` closure that this module has no Robolectric harness to drive with a real `Context` /
 * `Toast` -- the identical gap `AppBrightnessCoordinatorDeclineFeedbackTest`, `SessionMutexRefusalWiringTest`
 * and `PerformanceOverlayRowPermissionWiringTest` already document and accept for this exact file and its
 * siblings. **These are source-characterization tests, not behavioural ones** -- each greps the exact
 * production line, so reverting the wiring (not just a pure helper) turns it red. Every one of the four
 * differentials below was captured for real: the production `Toast.makeText(...)` line was deleted, the
 * test was run and its exact failure string recorded during the differential check, then the line was restored
 * and the test re-confirmed green.
 */
class MimirNativeShelfPlayHonestyTest {

    /** B1 (`:252-260`): no launchable intent for the target package -- used to `return@launch` in total silence. */
    @Test
    fun noLaunchableIntentArmToastsAppCouldNotBeLaunched() {
        val arm = source()
            .substringAfter("resolveNativeShelfLaunchIntent(appContext, packageName)\n                } ?: run {")
            .substringBefore("}")

        assertTrue(
            "the no-launchable-intent arm must toast \"App couldn’t be launched\" before return@launch " +
                "-- verbatim from MimirAppCoordinator.kt:1217",
            arm.contains("""showMimirMessage(context, "App couldn’t be launched")"""),
        )
        assertTrue("the arm must still return@launch", arm.contains("return@launch"))
    }

    /** B2 (`:283-293`): the return-anchor arm/persist failed -- used to `return@withContext` in total silence. */
    @Test
    fun returnAnchorArmFailedArmToastsAppCouldNotBeLaunched() {
        val arm = source()
            .substringAfter("if (!returnArmed) {")
            .substringBefore("}")

        assertTrue(
            "the return_anchor_arm_failed arm must still call appFailed with its named reason",
            arm.contains("""MimirSessionAuthority.appFailed(operation, "return_anchor_arm_failed")"""),
        )
        assertTrue(
            "the return_anchor_arm_failed arm must toast \"App couldn’t be launched\" before " +
                "return@withContext -- this arm has no Quick Launch counterpart, so the copy is proposed, " +
                "copy consistency for this refusal remains unresolved",
            arm.contains("""showMimirMessage(context, "App couldn’t be launched")"""),
        )
        assertTrue("the arm must still return@withContext", arm.contains("return@withContext"))
    }

    /** B3 (`:310-319`): the launch Intent failed to start -- used to end the else branch in total silence. */
    @Test
    fun activityNotStartedArmToastsAppCouldNotBeLaunched() {
        val arm = source()
            .substringAfter("""MimirSessionAuthority.appFailed(operation, "activity_not_started")""")
            .substringBefore("}")

        assertTrue(
            "the activity_not_started arm must toast \"App couldn’t be launched\" -- verbatim from " +
                "MimirAppCoordinator.kt:1244",
            arm.contains("""showMimirMessage(context, "App couldn’t be launched")"""),
        )
    }

    /**
     * The healthy path must be unchanged: the `started == true` branch still dispatches and records
     * recency, and carries no new Toast of its own -- this fix touches only the three failure arms.
     */
    @Test
    fun healthyStartedPathIsUnchangedAndToastFree() {
        val startedBranch = source()
            .substringAfter("val started = startNativeShelfLaunchIntent(context, packageName, target)\n")
            .substringAfter("if (started) {")
            .substringBefore("} else {")

        assertTrue(
            "the healthy path must still dispatch the session",
            startedBranch.contains("MimirSessionAuthority.appDispatched(operation)"),
        )
        assertTrue(
            "the healthy path must still record Android-game launch recency",
            startedBranch.contains("recordAndroidGameDispatchRecency("),
        )
        assertFalse(
            "the healthy path must not toast -- this fix is failure-arm-only",
            startedBranch.contains("showMimirMessage("),
        )
    }

    /**
     * The general regression guard, not just the three fixes above: every `return@launch` /
     * `return@withContext` inside `launchPackage`'s `scope.launch { }` body must be preceded by a
     * `showMimirMessage(` call in the same arm -- the shape that let B1/B2 (and, before O-3, the mutex arm)
     * go silent for however long they did. `LOOKBACK_LINES` is generous enough to cover this file's actual
     * arm bodies (comment lines plus one statement) without reaching into an unrelated preceding arm.
     *
     * **Structural limit, stated not hidden:** this scan cannot see B3. B3's failure arm is the trailing
     * `else` branch of an `if (started)` -- it has no `return@launch` / `return@withContext` keyword at
     * all, it simply falls off the end of the block. B3 is pinned above, by name, in
     * [activityNotStartedArmToastsAppCouldNotBeLaunched] instead.
     */
    @Test
    fun everyReturnInsideLaunchPackageIsPrecededByAMessageEmission() {
        val lines = launchPackageScopeLaunchBody().lines()
        val returnPattern = Regex("""return@(launch|withContext)\b""")
        val messagePattern = Regex("""showMimirMessage\(""")

        val unguarded = lines.indices.filter { i ->
            returnPattern.containsMatchIn(lines[i]) &&
                !messagePattern.containsMatchIn(
                    lines.subList(maxOf(0, i - LOOKBACK_LINES), i + 1).joinToString("\n"),
                )
        }.map { i -> "line ${i + 1}: ${lines[i].trim()}" }

        assertTrue(
            "every return@launch / return@withContext inside launchPackage's scope.launch body must be " +
                "preceded (within $LOOKBACK_LINES lines) by a showMimirMessage(...) call -- found " +
                "${unguarded.size} without one: $unguarded",
            unguarded.isEmpty(),
        )
    }

    /** Sanity check on the scan above: exactly three returns exist today (B1, the O-3 mutex arm, B2). */
    @Test
    fun scopeLaunchBodyHasExactlyThreeGuardedReturns() {
        val body = launchPackageScopeLaunchBody()
        val returnCount = Regex("""return@(launch|withContext)\b""").findAll(body).count()
        val toastCount = Regex("""showMimirMessage\(""").findAll(body).count()

        assertEquals("expected exactly 3 return@launch/return@withContext sites today", 3, returnCount)
        // B1, the O-3 mutex arm, B2, and B3 (which has no return) = 4 toasts for 3 returns.
        assertEquals("expected exactly 4 showMimirMessage(...) call sites today", 4, toastCount)
    }

    private fun launchPackageScopeLaunchBody(): String =
        source()
            .substringAfter("val launchPackage: (String) -> Unit = remember(context, scope, androidGameProofs) {")
            .substringBefore("\n    val toggleFavorite: (String) -> Unit = remember(context) {")
            .substringAfter("scope.launch {")

    private fun source(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirNativeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirNativeShelfCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirNativeShelfCoordinator.kt not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val LOOKBACK_LINES = 4
    }
}
