package com.gruning.mimir.rewrite

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dev hook's bounded wait for the clock route (F2, AYN Thor, 2026-09-11).
 *
 * The hook fired from `onCreate`'s intent handling 61 ms before the bind answered and was refused with
 * `route=REFUSE`, so a proof run against a v10 daemon measured the REFUSAL rather than the write. Every
 * assertion below is about the shape of the wait, because that is the whole of the fix: it is bounded,
 * it stops at the first non-refusal, and it proceeds anyway when the bound expires so the refusal — if
 * it is real — is still what the device prints.
 */
class MimirTunerRouteWaitTest {

    private val daemon = MimirTunerRouteDecision.Daemon
    private val refuse = MimirTunerRouteDecision.Refuse(MimirTunerRefuseDaemonBusy)

    @Test
    fun `a route that is already resolved is not waited for at all`() = runTest {
        var asked = 0
        val decided = awaitMimirTunerRouteResolved(decision = { asked += 1; daemon })

        assertEquals(daemon, decided)
        assertEquals("one read, no delay: a resolved route costs the hook nothing", 1, asked)
    }

    @Test
    fun `the wait ends on the first answer that is not a refusal`() = runTest {
        // The Thor's own timing: refused at 08:30:00.069, answered at 08:30:00.130 — two polls away.
        var asked = 0
        val decided = awaitMimirTunerRouteResolved(
            decision = {
                asked += 1
                if (asked > 2) daemon else refuse
            },
        )

        assertEquals(daemon, decided)
        assertEquals("it stops asking the moment it has an answer", 3, asked)
    }

    @Test
    fun `a direct route ends the wait exactly as a daemon route does`() = runTest {
        // A device with no Tweaker resolves to DIRECT, never to DAEMON, and a wait that only accepted
        // one of them would hang the hook for its whole budget on the commonest handheld there is.
        var asked = 0
        val decided = awaitMimirTunerRouteResolved(
            decision = {
                asked += 1
                if (asked > 1) MimirTunerRouteDecision.Direct else refuse
            },
        )

        assertEquals(MimirTunerRouteDecision.Direct, decided)
        assertEquals(2, asked)
    }

    @Test
    fun `the wait is bounded, and expiry proceeds with the refusal rather than swallowing it`() = runTest {
        // **Proceeding is the requirement, not giving up.** A device that genuinely cannot resolve its
        // route must still print `outcome=REFUSED(ROUTE_REFUSED)` with the reason the daemon gave; a
        // hook that returned early and logged nothing would turn a diagnosable refusal into silence.
        var asked = 0
        val decided = awaitMimirTunerRouteResolved(decision = { asked += 1; refuse })

        assertEquals("the refusal is handed back intact", refuse, decided)
        assertEquals(
            "60 polls of 50 ms and not one more",
            (MimirTunerRouteWaitTimeoutMs / MimirTunerRouteWaitPollMs).toInt() + 1,
            asked,
        )
    }

    @Test
    fun `the budget is the specified value`() {
        assertEquals(3_000L, MimirTunerRouteWaitTimeoutMs)
        assertEquals(50L, MimirTunerRouteWaitPollMs)
    }

    @Test
    fun `the dev hook waits on both arms, off the main thread, and takes only integers`() {
        // A source pin: `maybeRequestCeiling` needs an Activity, an Intent and a live discovery to
        // reach from a JVM test, and the defect it is fixing was pure ordering — the hook asked before
        // the bind could answer. The three properties pinned here are exactly the three the device
        // trace named: the wait, the dispatcher, and that nothing a string carries reaches the writer.
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()
        val hook = source
            .substringAfter("private fun maybeRequestCeiling()")
            .substringBefore("private fun maybeArmLabTunerAxis()")

        assertEquals(
            "both the request and the withdraw arm wait for the route",
            2,
            Regex("awaitMimirTunerRouteResolved\\(\\)").findAll(hook).count(),
        )
        assertEquals(
            "and both still run off the main thread",
            2,
            Regex("lifecycleScope\\.launch\\(Dispatchers\\.IO\\)").findAll(hook).count(),
        )
        // The wait must come BEFORE the call it is waiting for, on both arms; a wait after the write
        // would pass every grep and change nothing on the device.
        listOf("MimirTunerWrite.withdraw()", "MimirTunerWrite.requestCeiling(").forEach { call ->
            val callAt = hook.indexOf(call)
            assertTrue("$call must be in the hook", callAt >= 0)
            val waitAt = hook.lastIndexOf("awaitMimirTunerRouteResolved()", callAt)
            assertTrue("the wait must precede $call", waitAt in 0 until callAt)
        }
        assertTrue(
            "the ceiling is still an integer extra, never a string",
            hook.contains("getIntExtra(\"mimir.capPolicy\"") && hook.contains("getIntExtra(\"mimir.capKhz\""),
        )
        assertTrue("and the existing cap line is unchanged", hook.contains("\"cap ${'$'}{policy.label} requested=${'$'}requestedKhz \""))
    }

    @Test
    fun `a re-delivered intent runs the same hook path`() {
        // singleTop: an `am start` against the running top instance lands in onNewIntent, not onCreate.
        // The wait lives inside `maybeRequestCeiling`, so it covers both — provided onNewIntent still
        // adopts the new intent and re-runs the hooks, which is what this pins.
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()
        val onNewIntent = source
            .substringAfter("override fun onNewIntent(intent: android.content.Intent)")
            .substringBefore("private fun consumeMButtonCommandIntent")

        assertTrue("the new intent must become getIntent()", onNewIntent.contains("setIntent(intent)"))
        assertTrue("and the debug hooks must re-run against it", onNewIntent.contains("runDebugIntentHooks()"))
        assertTrue(
            "maybeRequestCeiling is reached through that one gate",
            source.substringAfter("private fun runDebugIntentHooks()").contains("maybeRequestCeiling()"),
        )
    }
}
