package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device result (Thor, build 8804787e, 2026-09-12 02:27 and 02:28, 2 of 2): SWAP back still
 * moved on Main. YouTube's window reflowed for one frame, then shrank into a tall rounded card toward the right edge over
 * Mimir's Home and vanished. The event log shows picture-in-picture entry:
 *  - `wm_task_to_front [0,2815]` for the Main copy's own task, 1-2 ms after the front START. Entering PiP moves the
 *    copy's task into pinned mode and to the top (RootWindowContainer.java:2013-2016, 2114; Task.java:1995-1996;
 *    TaskDisplayArea.java:260-266, 415; Task.java:1299-1304; TaskDisplayArea.java:450-470).
 *  - `wm_finish_activity … remove-task` for that copy 215 ms after MainActivity resumed, after Mimir's task was reported
 *    visible. The removal finishes only a resumed or visible activity (Task.java:1600-1605). A fullscreen copy under a
 *    visible Mimir task is not visible; a pinned one is.
 *
 * In AOSP 13 a start is a user-leaving start unless it carries FLAG_ACTIVITY_NO_USER_ACTION (ActivityStarter.java:2461).
 * Moving Mimir's task to the front marks the resumed, PiP-capable app on top as allowed to enter PiP on this task switch
 * (Task.java:5534-5535, 5590; 5162-5178; 5185-5208). Pausing that app as a user leave, with auto-enter enabled, enters
 * PiP directly (TaskFragment.java:1587-1591, 1602-1604; ActivityTaskManagerService.java:3454-3497).
 *
 * The same run removed task 2815 three times, because `am stack remove` returns before the task leaves the list.
 *
 * Source pins; comments are removed and whitespace collapsed.
 */
class CompanionSwapNoUserLeaveTest {

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

    @Test
    fun `the SWAP back front start is not a user-leaving start`() {
        val flags = appSource("CompanionMainCopyEviction.kt")
            .substringAfter("internal fun companionMainFrontLaunchFlags(): Int =", missingDelimiterValue = "")
            .substringBefore("internal ")
        assertTrue(
            "without FLAG_ACTIVITY_NO_USER_ACTION the front start pauses the app on Main as a user leave, and an app with " +
                "auto-enter PiP shrinks into a picture-in-picture card on Main. Flags were: $flags",
            "Intent.FLAG_ACTIVITY_NO_USER_ACTION" in flags,
        )
    }

    @Test
    fun `the exchange's cut Main start is not a user-leaving start either`() {
        val flags = appSource("NativeShelfPackages.kt")
            .substringAfter("internal fun nativeShelfLaunchFlags(", missingDelimiterValue = "")
            .substringBefore("private fun ")
        assertTrue(
            "leg 3 of the exchange pauses the Main app's copy the same way; the SWAP request carries NO_USER_ACTION " +
                "with NO_ANIMATION, and a plain launch carries neither. Was: $flags",
            "(if (noAnimation) Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION else 0)" in flags,
        )
    }

    @Test
    fun `the eviction waits for a removed task to leave the list instead of removing it again`() {
        val hook = appSource("CompanionProcessRuntime.kt")
            .substringAfter("val landed = lease.packageName")
            .substringBefore("val abandoned = pendingSecondaryReturnHost")
        val check = hook.indexOf("if (mainTaskId == lastRemovedTaskId) {")
        val bound = hook.indexOf("if (evicted >= CompanionMainCopyEvictionLimit) {")
        val remove = hook.indexOf("authority.removeTask(mainTaskId)")
        val record = hook.indexOf("lastRemovedTaskId = mainTaskId")
        assertTrue(
            "each resolved id is compared with the one just removed, before the pass budget and the removal: " +
                "check=$check bound=$bound remove=$remove",
            check >= 0 && check < bound && bound < remove,
        )
        assertTrue("the removed id is recorded after its removal: remove=$remove record=$record", record > remove)
        val settle = hook.substring(check, bound)
        assertTrue(
            "a re-listed id waits, reads display 0 again, and stops after a bounded number of reads. Was: $settle",
            "if (removalSettleChecks >= CompanionMainCopyRemovalSettleChecks) {" in settle &&
                "return@launch" in settle &&
                "removalSettleChecks++" in settle &&
                "delay(CompanionMainCopyRemovalSettlePollMs)" in settle &&
                "continue" in settle,
        )
        assertTrue("a re-listed id is never removed again. Was: $settle", "authority.removeTask" !in settle)
    }
}
