package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SWAP back: Main cuts the way the Bottom does. A device run recorded a ~460 ms slide
 * on Main when the leftover Main copy of the app was removed (`am stack remove`) while it was still the resumed app on
 * display 0. The eviction now brings Mimir's own Main task forward with a no-animation start first, and removes the copy
 * only once MainActivity has resumed. Never while an exchange is in flight, where leg 3 is about to put the former Bottom
 * app on Main.
 *
 * Source pins: no JVM test can watch a window transition or a task reorder. Comments are removed and whitespace
 * collapsed, so prose can neither satisfy nor break a pin.
 */
class CompanionSwapBackMainFrontTest {

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

    private fun landingHook(): String = appSource("CompanionProcessRuntime.kt")
        .substringAfter("val landed = lease.packageName")
        .substringBefore("val abandoned = pendingSecondaryReturnHost")

    @Test
    fun `the eviction brings Mimir's Main task forward before it removes the leftover copy`() {
        val hook = landingHook()
        val front = hook.indexOf("bringCompanionMainForward(")
        val removal = hook.indexOf("authority.removeTask(mainTaskId)")
        assertTrue(
            "the leftover Main copy is removed while it is still the resumed app on display 0, which prepares a close " +
                "transition (the ~460 ms SWAP back slide); bring Mimir's Main task forward first. front=$front removal=$removal",
            front in 0 until removal,
        )
        assertTrue(
            "only the first pass brings Main forward; later passes remove copies that are already covered",
            hook.contains("if (evicted == 0) bringCompanionMainForward("),
        )
    }

    @Test
    fun `the exchange state is read when the copy lands, not when the removal runs`() {
        val hook = landingHook()
        val captured = hook.indexOf("val exchangeInFlight = CompanionExchangeInFlight.active")
        val launch = hook.indexOf("processScope.launch", startIndex = hook.indexOf("mainCopyEvictionOperation = lease.operationId"))
        assertTrue(
            "the exchange clears its flag after leg 3 dispatches, so a removal that read it late could bring Mimir over " +
                "leg 3's app. Read it synchronously at the landing. captured=$captured launch=$launch",
            captured in 0 until launch,
        )
        assertTrue(
            "the captured value is the one handed to the front step",
            hook.contains("bringCompanionMainForward(context, exchangeInFlight)"),
        )
    }

    @Test
    fun `the front start reuses the task's own top activity with no animation on Main`() {
        val eviction = appSource("CompanionMainCopyEviction.kt")
        val flags = eviction
            .substringAfter("internal fun companionMainFrontLaunchFlags(): Int =", missingDelimiterValue = "")
            .substringBefore("internal ")
        listOf(
            "Intent.FLAG_ACTIVITY_NEW_TASK",
            "Intent.FLAG_ACTIVITY_REORDER_TO_FRONT",
            "Intent.FLAG_ACTIVITY_NO_ANIMATION",
        ).forEach { flag -> assertTrue("the front start carries $flag, flags were: $flags", flag in flags) }
        val front = block(eviction, "internal suspend fun bringCompanionMainForward(")
        assertTrue(
            "the start names the top activity of Mimir's Main task, so nothing above MainActivity is cleared",
            front.contains("Intent().setClassName(context.packageName, plan.topActivityClass)") &&
                front.contains(".addFlags(flags)"),
        )
        assertTrue(
            "the start lands on Main",
            front.contains("ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()"),
        )
        listOf("CATEGORY_HOME", "FLAG_ACTIVITY_CLEAR_TOP", "moveTaskToFront").forEach { forbidden ->
            assertFalse("the front step must not use $forbidden", eviction.contains(forbidden))
        }
    }

    @Test
    fun `the leftover copy is removed only after MainActivity has resumed, with a bound`() {
        val front = block(appSource("CompanionMainCopyEviction.kt"), "internal suspend fun bringCompanionMainForward(")
        val start = front.indexOf("context.startActivity(")
        val wait = front.indexOf("withTimeoutOrNull(CompanionMainFrontResumeTimeoutMs)")
        assertTrue("the front step waits after its start, start=$start wait=$wait", start in 0 until wait)
        assertTrue(
            "the wait ends when MainActivity has resumed",
            front.contains("PrimaryActivityObservationState.stopped.first { stopped -> !stopped }"),
        )
    }

    @Test
    fun `the exchange is in flight for its whole run`() {
        val exchange = block(appSource("MainActivity.kt"), "private fun exchangeCompanionApps(")
        val launch = exchange.indexOf("runningAppTransferJob = lifecycleScope.launch")
        assertTrue("the exchange still runs in its transfer job", launch >= 0)
        val job = exchange.substring(launch)
        val set = job.indexOf("CompanionExchangeInFlight.active = true")
        val legOne = job.indexOf("companionController?.restore(")
        assertTrue(
            "the exchange marks itself inside its try, before leg 1, so its finally always clears it: set=$set legOne=$legOne",
            job.indexOf("try {") in 0 until set && set in 0 until legOne,
        )
        assertTrue(
            "the exchange clears the mark when it ends, however it ends",
            block(job, "} finally").contains("CompanionExchangeInFlight.active = false"),
        )
    }

    @Test
    fun `the evicted line names the front lever`() {
        val joined = landingHook().replace(Regex("\"\\s*\\+\\s*\""), "")
        assertTrue(
            "the existing evicted line keeps its fields and adds the lever",
            joined.contains("\"main copy evicted package=\$evictPackage taskId=\$mainTaskId result=\$result front=\$front\""),
        )
    }
}
