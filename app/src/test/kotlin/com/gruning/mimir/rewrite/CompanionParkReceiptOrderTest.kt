package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.park` is a claim that the Main game is parked behind Mimir. It is written only once Main has actually resumed, and a park
 * that cannot complete says `session.park_failed reason=...` instead. The sequence runs on process coroutines that a unit test
 * cannot drive, so the order is pinned as source, as the neighbouring wiring pins are.
 */
class CompanionParkReceiptOrderTest {
    private val source = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionProcessRuntime.kt").also {
        assertTrue("CompanionProcessRuntime.kt must be readable", it.isFile)
    }.readText()

    private val parkLine = "BottomUiReceipts.log.session(\"park\", lease.operation.value, plan.displayId, \"bottom_app_launch\", plan.packageName)"

    @Test
    fun `the park is written once, after Main has resumed and the plan is still current`() {
        assertEquals("one park receipt", 1, Regex("""log\.session\(\s*"park"\s*,""").findAll(source).count())
        val at = source.indexOf(parkLine)
        assertTrue("the park receipt is the one in the Bottom launch sequence", at > 0)
        assertTrue("after Main is started", at > source.indexOf("Intent.FLAG_ACTIVITY_NO_ANIMATION"))
        assertTrue("after the resume wait", at > source.indexOf("PrimaryActivityObservationState.stopped.first { stopped -> !stopped }"))
        assertTrue("after the resume timeout branch", at > source.indexOf("\"main_game_front_timeout\""))
        assertTrue("the Bottom app is dispatched after it", source.indexOf("registry.launchOnSecondary(", at) > at)
    }

    @Test
    fun `every way the park can fail writes park_failed with the reason the launch failure carries`() {
        assertTrue(source.contains("\"park_failed\", lease.operation.value, plan.displayId, reason, plan.packageName"))
        for (reason in listOf("main_game_front_failed", "main_game_front_timeout", "plan_superseded")) {
            assertTrue("park_failed(\"$reason\")", source.contains("parkFailed(\"$reason\")"))
        }
    }
}
