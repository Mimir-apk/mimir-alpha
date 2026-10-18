package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the earlier update : Quick Launch closes an app's copy on Main, so the app reopens fresh on the Bottom and the
 * keyboard can reach it. The four outcomes the brief names (no Main task, Main task removed, removal unproven, gateway not
 * ready) on a scripted vendor gateway, then what must never be asked of it, the launch's real deadline against a slow gateway, and
 * the hook on the launch's work stage.
 */
class BottomMainCopyClearanceTest {
    private val chrome = "com.android.chrome"
    private val mimir = "com.gruning.mimir.rewrite"

    /** A vendor gateway that answers from a script and records every call it was given. The last scripted answer repeats. */
    private class ScriptedGateway(
        private val resolves: List<VendorTaskOutcome>,
        private val removal: PrivilegedTaskRemoval = PrivilegedTaskRemoval.REMOVED,
    ) : PrivilegedTaskCloseGateway {
        val resolved = mutableListOf<Pair<String, Int>>()
        val removed = mutableListOf<Int>()
        var closes = 0

        override fun closeLaunchTarget(packageName: String): PrivilegedCloseOutcome {
            closes++
            return PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE
        }

        override fun removeTask(taskId: Int): PrivilegedTaskRemoval {
            removed += taskId
            return removal
        }

        override fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome {
            resolved += packageName to displayId
            return resolves[minOf(resolved.size - 1, resolves.lastIndex)]
        }
    }

    private class FakeClock {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        fun elapsed(): Long = now
        fun sleep(ms: Long) {
            sleeps += ms
            now += ms
        }
    }

    /** Plenty of time: the deadline the launch gives a clearance that starts at once, less nothing. */
    private val roomy = BottomLaunchBudgetMs - BottomMainCopyHostReserveMs

    /**
     * The call runner every scripted case runs through. Production hands each gateway call to [BottomMainCopyCallRunner.shared], a JVM-wide pool of
     * two threads that never queues: a third submit is refused as `busy` while two workers exist that are not parked in its queue, and a worker that has
     * only just answered is not parked yet. A runner whose Gradle fork has run other classes first, on a 2 vCPU machine, can find both workers in that gap
     * on its very first call (the Linux run of 2026-10-13: `resolve_busy`, nothing found, in a case that is only about the settle checks). These cases are
     * about what the clearance decides from a script on a fake clock, not about threads, so each runs its calls on the test's own thread, where there is no
     * pool to refuse, no hand-off to wait on and nothing left over for the next case. The two-thread pool and the deadline against a real slow gateway are
     * covered by the slow-gateway case below, on a pool of its own.
     */
    private class InlineCalls : ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        override fun execute(command: Runnable) = command.run()
    }

    private val inline = BottomMainCopyCallRunner(InlineCalls())

    private fun clear(
        gateway: PrivilegedTaskCloseGateway?,
        clock: FakeClock = FakeClock(),
        packageName: String = chrome,
        emulatorTarget: Boolean = false,
        gamePackages: Set<String> = emptySet(),
        stillWanted: () -> Boolean = { true },
        deadlineMs: Long = clock.now + roomy,
        beforeFirstRemoval: ((Long) -> String)? = null,
        calls: BottomMainCopyCallRunner = inline,
    ): BottomMainCopyOutcome = clearMainCopyBeforeBottomLaunch(
        packageName = packageName,
        mimirPackage = mimir,
        emulatorTarget = emulatorTarget,
        gamePackages = gamePackages,
        gateway = gateway,
        deadlineMs = deadlineMs,
        stillWanted = stillWanted,
        nowMs = clock::elapsed,
        sleepMs = clock::sleep,
        calls = calls,
        beforeFirstRemoval = beforeFirstRemoval,
    )

    // -- the four cases the brief names --------------------------------------------------------------------------------------

    @Test
    fun `no Main task - nothing is removed and the launch needs no note`() {
        val gateway = ScriptedGateway(listOf(VendorTaskOutcome.NoTask))

        val outcome = clear(gateway)

        assertEquals(BottomMainCopyOutcome.NoMainTask, outcome)
        assertEquals("it asked about display 0 for the package, once", listOf(chrome to 0), gateway.resolved)
        assertTrue("and removed nothing", gateway.removed.isEmpty())
        assertFalse(outcome.needsKeyboardNote())
    }

    @Test
    fun `Main task removed - the id is removed once and read back as gone before the launch`() {
        val clock = FakeClock()
        // The system keeps listing a removed task until its last activity is gone: the first read-back still sees it.
        val gateway = ScriptedGateway(
            listOf(VendorTaskOutcome.Found(41), VendorTaskOutcome.Found(41), VendorTaskOutcome.NoTask),
        )

        val outcome = clear(gateway, clock)

        assertEquals(BottomMainCopyOutcome.Removed(1), outcome)
        assertEquals("the task that was found, and only that one", listOf(41), gateway.removed)
        assertEquals("a read of display 0 before, one while it was still listed, one that proved it gone", 3, gateway.resolved.size)
        assertTrue("every read was of display 0", gateway.resolved.all { it == chrome to 0 })
        assertEquals("one settle wait between the two reads of the removed id", listOf(CompanionMainCopyRemovalSettlePollMs), clock.sleeps)
        assertFalse(outcome.needsKeyboardNote())
        assertEquals("nothing was force-stopped", 0, gateway.closes)
    }

    @Test
    fun `a second Main copy is removed too, and the clearance ends only when display 0 answers empty`() {
        val gateway = ScriptedGateway(
            listOf(VendorTaskOutcome.Found(41), VendorTaskOutcome.Found(52), VendorTaskOutcome.NoTask),
        )

        val outcome = clear(gateway)

        assertEquals(BottomMainCopyOutcome.Removed(2), outcome)
        assertEquals(listOf(41, 52), gateway.removed)
    }

    @Test
    fun `removal unproven - a task still listed after the settle checks launches with the note`() {
        val clock = FakeClock()
        val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41)))

        val outcome = clear(gateway, clock, deadlineMs = 60_000L)

        assertEquals(BottomMainCopyOutcome.Unproven("still_listed", mainTaskFound = true), outcome)
        assertEquals("removed once; a second removal of a finishing task does nothing", listOf(41), gateway.removed)
        assertEquals(CompanionMainCopyRemovalSettleChecks, clock.sleeps.size)
        assertTrue(outcome.needsKeyboardNote())
    }

    @Test
    fun `removal unproven - a refused removal is not believed`() {
        for (refusal in listOf(PrivilegedTaskRemoval.FAILED, PrivilegedTaskRemoval.NOT_ALLOWED)) {
            val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41)), removal = refusal)

            val outcome = clear(gateway)

            assertEquals(BottomMainCopyOutcome.Unproven("remove_${refusal.name.lowercase()}", mainTaskFound = true), outcome)
            assertEquals("it asked once and stopped", listOf(41), gateway.removed)
            assertTrue(outcome.needsKeyboardNote())
        }
    }

    @Test
    fun `removal unproven - the read-back going dark after a removal is not a proof, and it warns`() {
        val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41), VendorTaskOutcome.Unavailable("unavailable")))

        val outcome = clear(gateway)

        assertEquals(BottomMainCopyOutcome.Unproven("resolve_unavailable", mainTaskFound = true), outcome)
        assertEquals(listOf(41), gateway.removed)
        assertTrue(outcome.needsKeyboardNote())
    }

    @Test
    fun `removal unproven - the authority going away at the removal still warns, because a copy was seen`() {
        val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41)), removal = PrivilegedTaskRemoval.AUTHORITY_UNAVAILABLE)

        val outcome = clear(gateway)

        assertEquals(BottomMainCopyOutcome.Unproven("remove_authority_unavailable", mainTaskFound = true), outcome)
        assertTrue(outcome.needsKeyboardNote())
    }

    @Test
    fun `removal unproven - copies that keep coming stop at the limit`() {
        val ids = (1..10).map { VendorTaskOutcome.Found(100 + it) }
        val gateway = ScriptedGateway(ids)

        val outcome = clear(gateway)

        assertEquals(BottomMainCopyOutcome.Unproven("bounded", mainTaskFound = true), outcome)
        assertEquals(CompanionMainCopyEvictionLimit, gateway.removed.size)
    }

    // -- a gateway that is not ready says nothing (P3a) -----------------------------------------------------------------------

    @Test
    fun `gateway not ready - no authority at all asks nothing and owes no note`() {
        val outcome = clear(gateway = null)

        assertEquals(BottomMainCopyOutcome.GatewayNotReady, outcome)
        assertFalse("a gateway that is not ready must not warn on every launch", outcome.needsKeyboardNote())
    }

    @Test
    fun `gateway not ready - a read that cannot tell, before any copy is seen, is silent and removes nothing`() {
        for (reason in listOf("unavailable", "timeout", "empty", "parse")) {
            val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Unavailable(reason)))

            val outcome = clear(gateway)

            assertEquals(reason, BottomMainCopyOutcome.GatewayNotReady, outcome)
            assertTrue("nothing was removed on a guess", gateway.removed.isEmpty())
            assertFalse("$reason: no Main task was found, so no note", outcome.needsKeyboardNote())
        }
    }

    @Test
    fun `a gateway that throws is unproven for the launch, never a crash of it`() {
        val gateway = object : PrivilegedTaskCloseGateway {
            override fun closeLaunchTarget(packageName: String) = PrivilegedCloseOutcome.FAILED
            override fun removeTask(taskId: Int) = PrivilegedTaskRemoval.FAILED
            override fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome = error("binder died")
        }

        val outcome = clear(gateway)

        assertTrue("it is unproven, with the call's own reason: $outcome", outcome is BottomMainCopyOutcome.Unproven)
        assertTrue((outcome as BottomMainCopyOutcome.Unproven).reason.startsWith("resolve_"))
    }

    // -- the deadline (P2) ----------------------------------------------------------------------------------------------------

    @Test
    fun `no room for a call means no call - the clearance is skipped and the note is owed instead`() {
        val clock = FakeClock()
        val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41)))

        val outcome = clear(gateway, clock, deadlineMs = BottomMainCopyMinCallRoomMs - 1L)

        assertEquals(BottomMainCopyOutcome.Unproven("no_time", mainTaskFound = false), outcome)
        assertTrue("the gateway was never asked", gateway.resolved.isEmpty())
        assertTrue(outcome.needsKeyboardNote())
    }

    @Test
    fun `a read-back that has no room left is unproven, and the launch is not made to wait for it`() {
        val clock = FakeClock()
        val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41)))

        // Room for the first read and the removal, then the 100 ms settle wait leaves less than a call needs.
        val outcome = clear(gateway, clock, deadlineMs = BottomMainCopyMinCallRoomMs + CompanionMainCopyRemovalSettlePollMs - 1L)

        assertEquals(BottomMainCopyOutcome.Unproven("readback_no_time", mainTaskFound = true), outcome)
        assertEquals(listOf(41), gateway.removed)
        assertEquals("one settle wait and then it stopped", 1, clock.sleeps.size)
    }

    /** A gateway that takes two seconds a call, as the vendor binder's own bound allows; records that its wait was interrupted. */
    private class SlowGateway : PrivilegedTaskCloseGateway {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val interrupted = AtomicBoolean(false)

        private fun slow() {
            calls.incrementAndGet()
            try {
                Thread.sleep(2_000L)
            } catch (_: InterruptedException) {
                interrupted.set(true)
            }
        }

        override fun closeLaunchTarget(packageName: String): PrivilegedCloseOutcome = PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE

        override fun removeTask(taskId: Int): PrivilegedTaskRemoval {
            slow()
            return PrivilegedTaskRemoval.REMOVED
        }

        override fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome {
            slow()
            return VendorTaskOutcome.Found(41)
        }
    }

    @Test
    fun `a gateway that takes two seconds a call cannot hold the launch past its own deadline`() {
        val gateway = SlowGateway()
        // A pool of its own, of the production shape (two daemon threads, never queued, a third submit refused), so nothing an earlier class left in
        // BottomMainCopyCallRunner.shared can refuse this case's first call. It makes two calls, so two workers are all it can ever need.
        val pool = ThreadPoolExecutor(
            0,
            2,
            10L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "mimir-bottom-main-copy-test").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        try {
            assertSlowGatewayCannotHoldTheLaunch(gateway, BottomMainCopyCallRunner(pool))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun assertSlowGatewayCannotHoldTheLaunch(gateway: SlowGateway, calls: BottomMainCopyCallRunner) {
        val events = mutableListOf<String>()
        val io = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        var outcome: BottomMainCopyOutcome? = null
        val plannedAt = bottomLaunchClockMs()
        val plan = plan().copy(clearMainCopy = true, plannedAtMs = plannedAt)
        val coordinator = coordinatorFor(plan, events, io, main) { p ->
            outcome = clearMainCopyBeforeBottomLaunch(
                packageName = p.packageName,
                mimirPackage = mimir,
                emulatorTarget = false,
                gamePackages = emptySet(),
                gateway = gateway,
                deadlineMs = bottomMainCopyDeadlineMs(p.plannedAtMs),
                calls = calls,
            )
            events += "cleared"
        }

        assertTrue(coordinator.prepare(chrome, clearMainCopy = true)!!.dispatch())
        io.removeFirst().invoke()
        val clearedAt = bottomLaunchClockMs() - plannedAt
        main.removeFirst().invoke()
        val launchedAt = bottomLaunchClockMs() - plannedAt

        assertEquals(listOf("persist", "cleared", "launch"), events)
        // The read took its two seconds and found a copy; the removal then had the half second left and was given up at the deadline.
        assertEquals(BottomMainCopyOutcome.Unproven("remove_timeout", mainTaskFound = true), outcome)
        assertEquals("a read and a removal, and nothing started after the deadline", 2, gateway.calls.get())
        val deadline = BottomLaunchBudgetMs - BottomMainCopyHostReserveMs
        assertTrue("the clearance ended at its deadline, not two seconds after it: ${clearedAt}ms", clearedAt in (deadline - 300L)..(deadline + 400L))
        assertTrue("the launch is dispatched inside its 3 s: ${launchedAt}ms", launchedAt < BottomLaunchBudgetMs)
        val cutAt = bottomLaunchClockMs()
        while (!gateway.interrupted.get() && bottomLaunchClockMs() - cutAt < 1_000L) Thread.sleep(10L)
        assertTrue("the call that outlived its wait was interrupted, not left to sit out its two seconds", gateway.interrupted.get())
        assertTrue(outcome!!.needsKeyboardNote())
    }

    // -- the step before the first removal (e: O12) ---------------------------------------------------------------------------

    @Test
    fun `the bring-forward step runs once, after the copy is seen and before it is removed`() {
        val order = mutableListOf<String>()
        val gateway = object : PrivilegedTaskCloseGateway {
            var reads = 0
            override fun closeLaunchTarget(packageName: String) = PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE
            override fun removeTask(taskId: Int): PrivilegedTaskRemoval {
                order += "remove:$taskId"
                return PrivilegedTaskRemoval.REMOVED
            }

            override fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome {
                order += "resolve"
                reads++
                return when (reads) {
                    1 -> VendorTaskOutcome.Found(41)
                    2 -> VendorTaskOutcome.Found(52)
                    else -> VendorTaskOutcome.NoTask
                }
            }
        }
        var budget = 0L

        val outcome = clear(gateway, beforeFirstRemoval = { ms ->
            order += "front"
            budget = ms
            "reorder mainVisible=true"
        })

        assertEquals(listOf("resolve", "front", "remove:41", "resolve", "remove:52", "resolve"), order)
        assertEquals("it comes back in the log", BottomMainCopyOutcome.Removed(2, front = "reorder mainVisible=true"), outcome)
        assertTrue("it is told how long it may take, and that is less than the whole deadline: $budget", budget in 1 until roomy)
    }

    @Test
    fun `the bring-forward step is not run when there is no copy, when none is given, or when it throws`() {
        var ran = 0
        assertEquals(BottomMainCopyOutcome.NoMainTask, clear(ScriptedGateway(listOf(VendorTaskOutcome.NoTask)), beforeFirstRemoval = { ran++; "x" }))
        assertEquals("no Main copy, nothing to bring Mimir in front of", 0, ran)

        val withoutStep = ScriptedGateway(listOf(VendorTaskOutcome.Found(41), VendorTaskOutcome.NoTask))
        assertEquals(BottomMainCopyOutcome.Removed(1, front = null), clear(withoutStep))
        assertEquals(listOf(41), withoutStep.removed)

        val thrown = ScriptedGateway(listOf(VendorTaskOutcome.Found(41), VendorTaskOutcome.NoTask))
        val outcome = clear(thrown, beforeFirstRemoval = { error("activity manager died") })
        assertEquals("the removal goes ahead whatever the step does", listOf(41), thrown.removed)
        assertEquals(BottomMainCopyOutcome.Removed(1, front = "threw"), outcome)
    }

    // -- what must never be asked ---------------------------------------------------------------------------------------------

    @Test
    fun `Mimir itself, an emulator and a running game's package are never asked about`() {
        val cases = listOf(
            Triple(mimir, false, emptySet<String>()) to "mimir_itself",
            Triple("org.dolphinemu.dolphin", true, emptySet<String>()) to "emulator",
            Triple("org.example.game", false, setOf("org.example.game")) to "game_package",
        )
        for ((case, reason) in cases) {
            val (pkg, emulator, games) = case
            val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41)))

            val outcome = clear(gateway, packageName = pkg, emulatorTarget = emulator, gamePackages = games)

            assertEquals(BottomMainCopyOutcome.Skipped(reason), outcome)
            assertTrue("$pkg: the gateway was never read", gateway.resolved.isEmpty())
            assertTrue("$pkg: and nothing was removed", gateway.removed.isEmpty())
            assertFalse("$pkg: a skip owes the player no note", outcome.needsKeyboardNote())
        }
    }

    @Test
    fun `every package in the emulator registry is spared, the recognise-only ones included`() {
        // ePSXe, FPse, DraStic and NooDS are recognised but not launchable: the force-stop policy does not admit them, and a game
        // running in one on Main is still the player's game.
        val recogniseOnly = listOf("com.epsxe.ePSXe", "com.emulator.fpse64", "com.dsemu.drastic", "com.hydra.noods")
        val launchable = listOf("org.dolphinemu.dolphinemu", "me.magnum.melonds", "com.retroarch.aarch64", "com.retroarch.ra32")
        for (pkg in recogniseOnly + launchable) {
            assertTrue("$pkg is in the registry", isRegisteredEmulatorPackage(pkg))
            val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41)))

            // The caller says nothing about it being an emulator: the decision is the registry's, not the call site's.
            val outcome = clear(gateway, packageName = pkg, emulatorTarget = false)

            assertEquals(pkg, BottomMainCopyOutcome.Skipped("emulator"), outcome)
            assertTrue("$pkg: never read, never removed", gateway.resolved.isEmpty() && gateway.removed.isEmpty())
        }
        assertFalse("Chrome is an ordinary app", isRegisteredEmulatorPackage(chrome))
        assertTrue("and the match ignores case", isRegisteredEmulatorPackage("COM.EPSXE.EPSXE"))
        assertFalse(
            "the force-stop door is a different question and keeps its own answer",
            MimirLaunchTargetStopPolicy.isEmulatorTarget("com.epsxe.ePSXe"),
        )
    }

    @Test
    fun `a blank package is skipped and a launch the player has replaced removes nothing`() {
        assertEquals(BottomMainCopyOutcome.Skipped("package_missing"), clear(ScriptedGateway(listOf(VendorTaskOutcome.NoTask)), packageName = "  "))

        val gateway = ScriptedGateway(listOf(VendorTaskOutcome.Found(41)))
        val outcome = clear(gateway, stillWanted = { false })

        assertEquals(BottomMainCopyOutcome.Skipped("superseded"), outcome)
        assertTrue(gateway.removed.isEmpty())
    }

    @Test
    fun `a launch that parks a Main game is not cleared, and one already parked with Mimir in front is`() {
        assertFalse("no dual-screen game: nothing to park", bottomLaunchParksMainGame(dualScreenGameActive = false, parkedLeaseExists = false, primaryStopped = true))
        assertTrue("a live game not yet parked", bottomLaunchParksMainGame(dualScreenGameActive = true, parkedLeaseExists = false, primaryStopped = false))
        assertTrue("parked, but Mimir's Main is stopped again: the launch re-parks", bottomLaunchParksMainGame(dualScreenGameActive = true, parkedLeaseExists = true, primaryStopped = true))
        assertFalse("already parked with Mimir in front: the registry launches straight away", bottomLaunchParksMainGame(dualScreenGameActive = true, parkedLeaseExists = true, primaryStopped = false))
    }

    // -- the door the resolve goes through ------------------------------------------------------------------------------------

    @Test
    fun `the launch in flight is admitted for its own clearance and for nothing longer`() {
        val tracked = setOf("org.example.app")
        assertFalse(chrome in mimirSessionAllowedPackages(tracked, "org.example.running"))

        BottomLaunchAdmission.admitting(chrome) {
            val allowed = mimirSessionAllowedPackages(tracked, "org.example.running")
            assertTrue("the package being opened", chrome in allowed)
            assertTrue("the tabs and the running app are still there", allowed.containsAll(listOf("org.example.app", "org.example.running")))
        }

        assertFalse("the door closes when the clearance does", chrome in mimirSessionAllowedPackages(tracked, null))
    }

    @Test
    fun `the door closes even when the clearance throws, and never admits Mimir itself`() {
        runCatching { BottomLaunchAdmission.admitting(chrome) { error("boom") } }
        assertFalse(chrome in BottomLaunchAdmission.current())

        val previous = MimirLaunchTargetStopPolicy.sessionAllowedPackages
        try {
            MimirLaunchTargetStopPolicy.sessionAllowedPackages = { mimirSessionAllowedPackages(emptySet(), null) }
            assertFalse("not admitted before", MimirLaunchTargetStopPolicy.isAllowed(chrome))
            BottomLaunchAdmission.admitting(chrome) {
                assertTrue("admitted during", MimirLaunchTargetStopPolicy.isAllowed(chrome))
            }
            assertFalse("not admitted after", MimirLaunchTargetStopPolicy.isAllowed(chrome))
            BottomLaunchAdmission.admitting(BuildConfig.APPLICATION_ID) {
                assertFalse("the policy still refuses Mimir", MimirLaunchTargetStopPolicy.isAllowed(BuildConfig.APPLICATION_ID))
            }
        } finally {
            MimirLaunchTargetStopPolicy.sessionAllowedPackages = previous
        }
    }

    // -- the flag, and the hook on the launch's work stage --------------------------------------------------------------------

    private fun plan() = CompanionSecondaryLaunchPlan(
        operationId = UUID(0L, 266L),
        operationToken = OperationId.parseOrNull(UUID(0L, 266L).toString())!!,
        packageName = chrome,
        displayId = 4,
        primaryOrigin = PrimaryReturnOrigin("LIBRARY", revision = 4L),
        stateVersion = 12L,
        launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
    )

    private fun coordinatorFor(
        plan: CompanionSecondaryLaunchPlan,
        events: MutableList<String>,
        io: ArrayDeque<() -> Unit>,
        main: ArrayDeque<() -> Unit>,
        hook: (CompanionSecondaryLaunchPlan) -> Unit,
    ): CompanionSecondaryLaunchCoordinator = CompanionSecondaryLaunchCoordinator(
        preparePlan = { plan },
        persistPlan = { events += "persist"; true },
        isCurrentPlan = { true },
        launchPlan = { events += "launch"; true },
        failPlan = { _, reason -> events += "fail:$reason" },
        workScheduler = CompanionLaunchWorkScheduler { work -> io += work; true },
        mainDispatcher = CompanionLaunchMainDispatcher { work -> main += work },
        beforeDispatch = hook,
    )

    @Test
    fun `only a Quick Launch plan asks for the clearance - every other prepare leaves the flag off`() {
        val seen = mutableListOf<Boolean>()
        val io = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val coordinator = coordinatorFor(plan(), mutableListOf(), io, main) { p -> seen += p.clearMainCopy }

        assertTrue(coordinator.prepare(chrome)!!.dispatch())
        io.removeFirst().invoke()
        assertTrue(coordinator.prepare(chrome, clearMainCopy = false)!!.dispatch())
        io.removeFirst().invoke()
        assertTrue(coordinator.prepare(chrome, clearMainCopy = true)!!.dispatch())
        io.removeFirst().invoke()

        assertEquals("the plain prepare, an explicit false, then Quick Launch", listOf(false, false, true), seen)
    }

    @Test
    fun `the clearance runs on the work stage, after the plan is saved and before the host is started`() {
        val events = mutableListOf<String>()
        val io = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val coordinator = coordinatorFor(plan(), events, io, main) { plan -> events += "clear:${plan.packageName}" }

        assertTrue(coordinator.prepare(chrome, clearMainCopy = true)!!.dispatch())
        assertTrue("nothing has run yet: the tap returns at once", events.isEmpty())
        io.removeFirst().invoke()
        assertEquals("saved, then cleared, and the launch is still waiting for main", listOf("persist", "clear:$chrome"), events)
        main.removeFirst().invoke()
        assertEquals(listOf("persist", "clear:$chrome", "launch"), events)
    }

    @Test
    fun `a clearance that throws never stops the launch`() {
        val events = mutableListOf<String>()
        val io = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val coordinator = coordinatorFor(plan(), events, io, main) { error("binder died") }

        assertTrue(coordinator.prepare(chrome, clearMainCopy = true)!!.dispatch())
        io.removeFirst().invoke()
        main.removeFirst().invoke()

        assertEquals(listOf("persist", "launch"), events)
    }

    @Test
    fun `the keyboard note is one short line`() {
        assertTrue(BottomMainCopyKeyboardNote.length < 80)
        assertFalse(BottomMainCopyKeyboardNote.contains('\n'))
        assertTrue(BottomMainCopyKeyboardNote.contains("keyboard", ignoreCase = true))
    }
}
