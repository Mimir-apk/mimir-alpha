package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Main→Bottom swap's orphan, pinned as a table.
 *
 * Reproduced on the Thor 2026-09-11 (Mimir `a8544b36`, 11:00:32 local): YouTube on Main, M menu →
 * SWAP, and `am stack list` then showed YouTube on displayId=4 **and** displayId=0 while
 * `MimirSession` reported `primaryApp=ENDED`. Only the SCREEN_TRANSFER direction can produce that
 * second copy; every other launch mode either never had a Main copy or resumed the one task that
 * existed, so evicting on them would kill something the player put there deliberately.
 */
class CompanionMainCopyEvictionTest {

    private val bottomDisplayId = 4
    private val youTube = "com.google.android.youtube"

    @Test
    fun `a screen transfer evicts the surviving main copy`() {
        assertEquals(
            CompanionMainCopyEviction.Evict(youTube, fromDisplayId = 0),
            companionMainCopyEvictionDecision(
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER,
                yieldedPackage = youTube,
                secondaryDisplayId = bottomDisplayId,
            ),
        )
    }

    @Test
    fun `the bottom to main direction evicts nothing`() {
        // LAUNCH_MODE_RESUME resumes the one existing task; there is no second copy to remove, and
        // display 0 is where that app is *supposed* to be.
        assertEquals(
            CompanionMainCopyEviction.Skip("not_screen_transfer"),
            companionMainCopyEvictionDecision(
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_RESUME,
                yieldedPackage = youTube,
                secondaryDisplayId = bottomDisplayId,
            ),
        )
    }

    @Test
    fun `a plain bottom launch evicts nothing`() {
        assertEquals(
            CompanionMainCopyEviction.Skip("not_screen_transfer"),
            companionMainCopyEvictionDecision(
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
                yieldedPackage = youTube,
                secondaryDisplayId = bottomDisplayId,
            ),
        )
    }

    @Test
    fun `a bottom game evicts nothing`() {
        assertEquals(
            CompanionMainCopyEviction.Skip("not_screen_transfer"),
            companionMainCopyEvictionDecision(
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME,
                yieldedPackage = "org.ppsspp.ppsspp",
                secondaryDisplayId = bottomDisplayId,
            ),
        )
    }

    @Test
    fun `every remaining launch mode skips`() {
        listOf(
            CompanionReturnHostContract.LAUNCH_MODE_RESTORE_ONLY,
            CompanionReturnHostContract.LAUNCH_MODE_HOME_RECLAIM,
            "",
            "something_a_future_lane_adds",
        ).forEach { mode ->
            assertEquals(
                "launch mode '$mode' must never evict a task on Main",
                CompanionMainCopyEviction.Skip("not_screen_transfer"),
                companionMainCopyEvictionDecision(
                    launchMode = mode,
                    yieldedPackage = youTube,
                    secondaryDisplayId = bottomDisplayId,
                ),
            )
        }
    }

    @Test
    fun `a live main game for the same package blocks the eviction`() {
        assertEquals(
            CompanionMainCopyEviction.Skip("main_game_active"),
            companionMainCopyEvictionDecision(
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER,
                yieldedPackage = "org.ppsspp.ppsspp",
                secondaryDisplayId = bottomDisplayId,
                mainGamePackage = "ORG.PPSSPP.PPSSPP",
            ),
        )
    }

    @Test
    fun `a main game for a different package does not block the eviction`() {
        assertEquals(
            CompanionMainCopyEviction.Evict(youTube, fromDisplayId = 0),
            companionMainCopyEvictionDecision(
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER,
                yieldedPackage = youTube,
                secondaryDisplayId = bottomDisplayId,
                mainGamePackage = "org.ppsspp.ppsspp",
            ),
        )
    }

    @Test
    fun `a secondary display that resolves to display 0 can never evict the task that just landed`() {
        // Degenerate state, but the one that would be catastrophic: evicting "the Main copy" would be
        // evicting the task the swap just created.
        assertEquals(
            CompanionMainCopyEviction.Skip("secondary_is_default_display"),
            companionMainCopyEvictionDecision(
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER,
                yieldedPackage = youTube,
                secondaryDisplayId = 0,
            ),
        )
    }

    @Test
    fun `a blank package skips instead of evicting by empty name`() {
        assertEquals(
            CompanionMainCopyEviction.Skip("package_missing"),
            companionMainCopyEvictionDecision(
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER,
                yieldedPackage = "   ",
                secondaryDisplayId = bottomDisplayId,
            ),
        )
    }

    @Test
    fun `the eviction sweep is bounded at three copies`() {
        // Thor 6592f41d, 11:13:18: one successful removeTask left display 0 still showing YouTube
        // because TWO tasks were there. The bound recovers that observed state with a pass to spare,
        // and caps a resolver that keeps answering.
        assertEquals(3, CompanionMainCopyEvictionLimit)
        assertTrue(
            "the bound must leave room for the two-copy state measured on the Thor",
            CompanionMainCopyEvictionLimit >= 2,
        )
    }

    @Test
    fun `the evicted package is trimmed and the display is always 0`() {
        val decision = companionMainCopyEvictionDecision(
            launchMode = " ${CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER} ",
            yieldedPackage = "  $youTube  ",
            secondaryDisplayId = bottomDisplayId,
        )
        assertEquals(CompanionMainCopyEviction.Evict(youTube, fromDisplayId = 0), decision)
        assertEquals(0, (decision as CompanionMainCopyEviction.Evict).fromDisplayId)
    }
}

/**
 * The landing hook lives inside `CompanionProcessServices.create(context)` — a private function on a
 * process-wide object that builds the registry, the foreground monitor and the composite privilege
 * gateway from a real `Context`, with no seam to inject a fake gateway through. That is the same
 * accepted gap `CompanionRunningAppTransferStageFailureWiringTest` documents for `MainActivity`, so the
 * wiring is pinned as source characterization rather than behaviour. What it must pin is the ORDER and
 * the TARGET: the policy decides, the resolve runs for the decision's display (0) and never for the
 * lease's display (4), and the remove only ever runs on an id that resolve returned.
 */
class CompanionMainCopyEvictionWiringTest {

    /** The `Yielded` landing hook, from the task-id read to the end of that branch. */
    private fun landingHook(): String =
        sourceFile("CompanionProcessRuntime.kt")
            .substringAfter("val landed = lease.packageName")
            .substringBefore("val abandoned = pendingSecondaryReturnHost")

    /** Collapse Kotlin's `"a " +\n "b"` seams so a wrapped log line can be asserted whole. */
    private fun String.joinedStrings(): String = replace(Regex("\"\\s*\\+\\s*\\n\\s*\""), "")

    @Test
    fun theHookConsultsThePolicyAndCarriesTheGameGuardIn() {
        val hook = landingHook()
        assertTrue(
            "the landing hook must ask companionMainCopyEvictionDecision, not decide inline",
            hook.contains("companionMainCopyEvictionDecision("),
        )
        assertTrue(
            "the lease's launch mode is what separates a screen transfer from every other landing",
            hook.contains("launchMode = lease.launchMode"),
        )
        assertTrue(
            "a Main-placed game session for the package must reach the policy as an input",
            hook.contains("mainGamePackage = MimirSessionAuthority.state.value.gameSession") &&
                hook.contains("MimirGamePlacement.MAIN") &&
                hook.contains("targetPackageName"),
        )
    }

    @Test
    fun theEvictionResolvesBeforeItRemovesAndOnlyOnTheDecisionsDisplay() {
        val hook = landingHook()
        val resolveAt = hook.indexOf("authority.resolveTaskId(evictPackage, evictDisplayId)")
        val removeAt = hook.indexOf("authority.removeTask(mainTaskId)")
        assertTrue("the eviction must resolve the display-0 task id first", resolveAt > 0)
        assertTrue("the eviction must remove only a resolved id", removeAt > resolveAt)
        assertTrue(
            "the removed id must be the one resolve returned",
            hook.contains("val mainTaskId = resolved.getOrNull()"),
        )
        assertTrue(
            "the display comes from the decision (0), never from the lease (4)",
            hook.contains("val evictDisplayId = evictionDecision.fromDisplayId") &&
                !hook.contains("resolveTaskId(evictPackage, landedDisplayId)"),
        )
        assertTrue(
            "the privileged lookup must stay off the main thread",
            hook.contains("withContext(Dispatchers.IO) {"),
        )
    }

    @Test
    fun eachOutcomeLogsExactlyOneLineUnderTheRuntimeTag() {
        val hook = landingHook().joinedStrings()
        assertTrue(
            "the removed-task outcome line",
            hook.contains("\"main copy evicted package=\$evictPackage taskId=\$mainTaskId result=\$result\""),
        )
        assertTrue(
            "the no-task-on-display-0 outcome line",
            hook.contains("\"main copy absent package=\$evictPackage (no task on display 0)\""),
        )
        assertTrue(
            "the unavailable-authority outcome line",
            hook.contains("\"main copy eviction unavailable package=\$evictPackage reason=close_authority_missing\""),
        )
        assertTrue(
            "every outcome line is logged under MimirCompanionRuntime",
            hook.split("main copy").drop(1).isNotEmpty() &&
                hook.contains("\"MimirCompanionRuntime\","),
        )
    }

    @Test
    fun theEvictionSweepsEveryCopyOnDisplayZeroAndStopsAtTheBound() {
        val hook = landingHook()
        val joined = hook.joinedStrings()
        val loopAt = hook.indexOf("while (true) {")
        assertTrue("the eviction must sweep, not evict once", loopAt > 0)
        assertTrue(
            "the resolve must run again after each removal, inside the loop",
            hook.indexOf("authority.resolveTaskId(evictPackage, evictDisplayId)") > loopAt,
        )
        assertTrue(
            "the sweep ends when display 0 answers empty",
            hook.contains("val mainTaskId = resolved.getOrNull()") &&
                hook.contains("if (mainTaskId == null) {"),
        )
        assertTrue(
            "the absent line reports only a swap that evicted nothing -- a sweep already logged each copy",
            hook.contains("if (evicted == 0) {"),
        )
        assertTrue(
            "the sweep is bounded by the policy's limit, not by a literal at this call site",
            hook.contains("if (evicted >= CompanionMainCopyEvictionLimit) {") &&
                hook.contains("evicted++"),
        )
        assertTrue(
            "hitting the bound with a task still present must say so exactly once",
            joined.contains("\"main copy eviction bounded package=\$evictPackage remaining=true\""),
        )
        assertTrue(
            "a removal the authority did not carry out would resolve the same id forever",
            hook.contains("if (result != PrivilegedTaskRemoval.REMOVED) return@launch"),
        )
        assertTrue(
            "every pass resolves display 0 only -- the display-4 task is never a candidate",
            !hook.contains("resolveTaskId(evictPackage, landedDisplayId)"),
        )
    }

    @Test
    fun oneSwapOperationRunsAtMostOneSweep() {
        val source = sourceFile("CompanionProcessRuntime.kt")
        assertTrue(
            "`runtime.snapshot` republishes while a lease stays Yielded; the sweep must be keyed to the " +
                "operation so it does not restart on every publish",
            source.contains("var mainCopyEvictionOperation: OperationId? = null") &&
                landingHook().contains("mainCopyEvictionOperation != lease.operationId") &&
                landingHook().contains("mainCopyEvictionOperation = lease.operationId"),
        )
    }

    @Test
    fun theSiblingTaskIdLandingIsUnchanged() {
        // Healthy-path pin: this fix must not disturb the existing secondary task-id landing it sits
        // beside — that read is what lets the ✕ close a tracked task later.
        assertTrue(
            landingHook().contains("registry.noteSecondaryTaskId(landed, taskId)"),
        )
    }

    @Test
    fun theExchangeLegTwoSharesThisHook() {
        // Leg 2 of `exchangeCompanionApps` reaches the Bottom through `swapToSecondary`, which is the
        // same `requestSwap` that mints LAUNCH_MODE_SCREEN_TRANSFER — so it lands in this same hook and
        // needs no MainActivity change of its own.
        val mainActivity = sourceFile("MainActivity.kt")
        assertTrue(
            "leg 2 must still go through swapToSecondary",
            mainActivity.contains("secondaryTaskRegistry?.swapToSecondary(mainApp.packageName)"),
        )
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
