package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device result (Thor, build b27866a1, 2026-09-12 01:09): SWAP up still slid on Main although
 * the target start carried `flg=0x30230000`. In AOSP 13 the slide is the Bottom restore guard's close transition: the
 * guard finished on display 4 (ActivityRecord.java:3407) and revealed the app there (ActivityRecord.java:5073), and when
 * the SWAP up start moved the app's task to display 0, ActivityRecord.onDisplayChanged carried that pending transition
 * with it (ActivityRecord.java:1734-1738; AppTransition.transferFrom). With no TO_FRONT request left, the transferred
 * CLOSE picks TRANSIT_OLD_TASK_CLOSE (AppTransitionController.getTransitCompatType), and a custom override cannot reach a
 * Task container (AppTransition.loadAnimation checks container.canCustomizeAppTransition(), false for a Task).
 *
 * So the order is the fix: the swap restore guard finishes only after the Main transfer host has been paused by the
 * move, and on SWAP back the transfer host closes while it is still covered, before Mimir's Main task comes forward.
 * Source pins, since no JVM test can watch a transition; comments are removed and whitespace collapsed.
 */
class CompanionSwapTransitionOrderTest {

    private fun appSource(name: String): String {
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        ).firstOrNull(File::isFile) ?: throw AssertionError("$name is not readable from ${File(".").absolutePath}")
        return file.readText()
            .replace(Regex("""(?ms)^\s*/\*.*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
            .replace(Regex("""\s+"""), " ")
    }

    private fun block(source: String, anchor: String): String {
        val at = source.indexOf(anchor)
        assertTrue("anchor not found: $anchor", at >= 0)
        val start = source.indexOf('{', at)
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start + 1, index)
                }
            }
        }
        throw AssertionError("unbalanced block after: $anchor")
    }

    private fun assertInOrder(message: String, text: String, vararg parts: String) {
        var from = 0
        parts.forEach { part ->
            val at = text.indexOf(part, from)
            assertTrue("$message: expected `$part` at or after $from", at >= 0)
            from = at + part.length
        }
    }

    @Test
    fun `the swap restore guard does not finish on mirror completion alone`() {
        val guard = appSource("CompanionReturnActivity.kt")
        val complete = block(guard, "ReturnHostDispositionDecision.COMPLETE ->")
        assertTrue(
            "the guard's finish revealed the app on the Bottom while the SWAP up start was about to move it, and that " +
                "pending close transition went to Main with the app. Complete through the swap order step. Was: $complete",
            complete.contains("mirrorCompletionObserved = true") &&
                complete.contains("finishRestoreGuardAfterSwapUpMove()") &&
                !complete.contains("finishWithoutTransition()"),
        )
    }

    @Test
    fun `the swap restore guard waits for the transfer host to be paused by the move, with a bound`() {
        val guard = appSource("CompanionReturnActivity.kt")
        val order = block(guard, "private fun finishRestoreGuardAfterSwapUpMove()")
        assertInOrder(
            "only a swap guard waits, and only until the transfer host has paused or gone",
            order,
            "if (!swapUpGuardHold || swapUpHostSettled.value)",
            "withTimeoutOrNull(CompanionSwapUpGuardHoldMs) { swapUpHostSettled.first { settled -> settled } }",
            "finishWithoutTransition()",
        )
        assertTrue(
            "the hold applies to the swap reclaim's guard only",
            guard.contains("swapUpGuardHold = companionSwapUpGuardHoldsForHost(isRestoreGuard(), restoreReason)"),
        )
        val callbacks = block(guard, "private val swapUpHostCallbacks = object : Application.ActivityLifecycleCallbacks")
        assertTrue(
            "the transfer host's pause or destruction settles the hold",
            block(callbacks, "override fun onActivityPaused(").contains("if (activity is PrimaryTransferActivity) swapUpHostSettled.value = true") &&
                block(callbacks, "override fun onActivityDestroyed(").contains("if (activity is PrimaryTransferActivity) swapUpHostSettled.value = true"),
        )
        assertTrue(
            "the guard registers the callbacks for a swap and always unregisters them",
            guard.contains("application.registerActivityLifecycleCallbacks(swapUpHostCallbacks)") &&
                block(guard, "override fun onDestroy()").contains("application.unregisterActivityLifecycleCallbacks(swapUpHostCallbacks)"),
        )
    }

    @Test
    fun `the swap reclaim reason is the one SWAP up passes`() {
        val order = appSource("CompanionSwapTransitionOrder.kt")
        assertTrue(order.contains("internal const val CompanionSwapUpReclaimReason = \"overlay_swap_to_primary\""))
        assertTrue(
            appSource("MainActivity.kt").contains("reason = \"overlay_swap_to_primary\","),
        )
    }

    @Test
    fun `on SWAP back the covered transfer host closes through its return path when its card clears`() {
        val host = appSource("PrimaryTransferActivity.kt")
        val close = block(host, "private fun closeWhenTransferCardClears(operation: UUID)")
        assertInOrder(
            "the host waits for its card to clear, then clears its transfer and closes, without resuming",
            close,
            "CompanionRunningAppState.running.first { app -> app?.packageName != targetPackageName }",
            "if (isFinishing || execution.phase != PrimaryTransferHostPhase.HOST_COVERED) return@launch",
            "clearCompanionRunningAppTransfer(operation, targetPackageName)",
            "finishWithoutAnimation()",
        )
        assertTrue(
            "the watch starts when the host is confirmed covered",
            block(host, "override fun onStop()").contains("if (confirmed) closeWhenTransferCardClears(operation)"),
        )
        assertTrue(
            "a recreated covered host watches again",
            block(host, "override fun onCreate(").contains(
                "if (execution.phase == PrimaryTransferHostPhase.HOST_COVERED) operationId?.let(::closeWhenTransferCardClears)",
            ),
        )
        assertTrue(
            "the watch ends with the host",
            block(host, "override fun onDestroy()").contains("coveredReturnJob?.cancel()"),
        )
    }

    @Test
    fun `the SWAP back front step waits for the transfer host to leave before it picks its target`() {
        val front = block(appSource("CompanionMainCopyEviction.kt"), "internal suspend fun bringCompanionMainForward(")
        assertInOrder(
            "Mimir's Main task comes forward only once the covered host has closed, so the host's own close is not in " +
                "the same transition",
            front,
            "withTimeoutOrNull(CompanionMainFrontHostWaitMs)",
            "companionMainTaskTopActivityClass(context) == PrimaryTransferActivity::class.java.name",
            "companionMainFrontPlan(",
        )
        assertFalse("no clear-top start of MainActivity", front.contains("FLAG_ACTIVITY_CLEAR_TOP"))
    }
}
