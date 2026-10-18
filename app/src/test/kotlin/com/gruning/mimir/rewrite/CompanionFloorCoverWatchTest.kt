package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.ActivityResumedObservation
import com.gruning.mimir.rewrite.core.companionnative.isCompanionShellPackage
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D41 (Thor 2026-10-08): the decision that turns "the floor stopped on display 4" plus "a foreign package resumed" into a yield. Every case here is
 * one the routing contract covers: the picked task yields, and a transient system surface, Mimir's own windows, an ambiguous or late resume, a display
 * going dark, the device asleep and a launch lease's own cover do not. A task that was under Recents on Main resumes in the same breath as the
 * pick; telling the two apart is the Main-package filter and the display-task lookup.
 */
class CompanionFloorCoverWatchTest {
    private val own = "com.gruning.mimir.rewrite"
    private val chrome = "com.android.chrome"
    private val mainApp = "com.example.mainapp"

    @Test
    fun `a task picked from Recents yields the Bottom once its resume falls inside the floor's pause to stop`() {
        val f = Fixture()

        f.watch.floorPaused(4)
        assertEquals("the resume subscription starts a little before the pause", f.now - CompanionFloorCoverWatch.ResumeLeadMs, f.subscriptions.single().since)
        f.resumed(chrome, at = f.now + 60L)
        assertTrue("nothing yields before the floor has stopped", f.yields.isEmpty())
        f.now += 500L
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        assertTrue("the stop alone decides nothing: a second resume may still join the span", f.yields.isEmpty())
        f.settle()

        assertEquals(listOf(4 to chrome), f.yields)
        assertTrue("the subscription is let go", f.subscriptions.single().closed)
        assertTrue("so are the timers", f.timers.all { it.cancelled })
    }

    @Test
    fun `a resume published a little after the stop still counts, once`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.now += 300L
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        assertTrue(f.yields.isEmpty())

        f.now += 100L
        f.resumed(chrome, at = f.now - 400L)
        f.resumed(chrome, at = f.now - 400L)
        assertTrue("still inside the settle", f.yields.isEmpty())
        f.settle()

        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a stop with no foreign resume, a display going dark, yields nothing and closes after the settle`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        assertFalse(f.subscriptions.single().closed)

        f.timer(CompanionFloorCoverWatch.SettleMs).run()

        assertTrue(f.yields.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `a dialog that pauses the floor but never stops it yields nothing, and the window closes itself`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.resumed("com.google.android.permissioncontroller")

        f.timer(CompanionFloorCoverWatch.WatchMs).run()
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        assertTrue(f.yields.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `Recents, the launcher, SystemUI and Mimir's own windows are never the covering task`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.resumed("com.android.launcher3")
        f.resumed("com.android.systemui")
        f.resumed("com.google.android.apps.nexuslauncher")
        f.resumed(own)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        assertTrue("still waiting, nothing foreign yet", f.yields.isEmpty())

        f.resumed(chrome)
        assertTrue("and still inside the settle", f.yields.isEmpty())
        f.settle()

        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a task under Recents on Main resumes too, and only the package with a task on the Bottom is the cover`() {
        val f = Fixture()
        f.outcomes = mapOf(chrome to VendorTaskOutcome.Found(47), mainApp to VendorTaskOutcome.NoTask)
        f.watch.floorPaused(4)
        f.resumed(mainApp)
        f.resumed(chrome)
        assertTrue("nothing is asked before the floor has stopped", f.lookups.isEmpty())

        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        assertEquals("one lookup, for this display and both candidates", listOf(4 to listOf(mainApp, chrome)), f.lookups)
        assertEquals(listOf(4 to chrome), f.yields)
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `two packages on the Bottom, or neither, or a lookup with no proof, yield nothing`() {
        listOf<Map<String, VendorTaskOutcome>>(
            mapOf(chrome to VendorTaskOutcome.Found(47), mainApp to VendorTaskOutcome.Found(12)),
            mapOf(chrome to VendorTaskOutcome.NoTask, mainApp to VendorTaskOutcome.NoTask),
            mapOf(chrome to VendorTaskOutcome.Unavailable("unavailable"), mainApp to VendorTaskOutcome.Unavailable("timeout")),
            emptyMap<String, VendorTaskOutcome>(),
        ).forEach { outcomes ->
            val f = Fixture()
            f.outcomes = outcomes
            f.watch.floorPaused(4)
            f.resumed(mainApp)
            f.resumed(chrome)
            f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
            assertEquals("asked once for $outcomes", 1, f.lookups.size)
            assertTrue("no yield for $outcomes", f.yields.isEmpty())
            assertTrue(f.subscriptions.single().closed)
        }
    }

    @Test
    fun `the one package proven on the Bottom wins over a package the lookup could not place`() {
        val f = Fixture()
        f.outcomes = mapOf(chrome to VendorTaskOutcome.Found(47), mainApp to VendorTaskOutcome.Unavailable("unavailable"))
        f.watch.floorPaused(4)
        f.resumed(mainApp)
        f.resumed(chrome)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a lookup still out leaves the Bottom with Mimir, and an answer after the floor resumed is dropped`() {
        val f = Fixture()
        f.answerNow = false
        f.watch.floorPaused(4)
        f.resumed(mainApp)
        f.resumed(chrome)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        f.resumed("com.example.third")
        assertEquals("a resume during the lookup changes nothing", 1, f.lookups.size)
        assertTrue(f.yields.isEmpty())
        assertFalse("the watch waits for the answer", f.subscriptions.single().closed)

        f.watch.floorResumed(4)
        f.deliver(mapOf(chrome to VendorTaskOutcome.Found(47), mainApp to VendorTaskOutcome.NoTask))

        assertTrue(f.yields.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `a lookup that answers after the disposition moved on, or never answers, yields nothing`() {
        val moved = Fixture()
        moved.answerNow = false
        moved.watch.floorPaused(4)
        moved.resumed(mainApp)
        moved.resumed(chrome)
        moved.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        moved.snapshot = launchPending()
        moved.deliver(mapOf(chrome to VendorTaskOutcome.Found(47), mainApp to VendorTaskOutcome.NoTask))
        assertTrue(moved.yields.isEmpty())

        val silent = Fixture()
        silent.answerNow = false
        silent.watch.floorPaused(4)
        silent.resumed(mainApp)
        silent.resumed(chrome)
        silent.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        silent.timer(CompanionFloorCoverWatch.WatchMs).run()
        silent.deliver(mapOf(chrome to VendorTaskOutcome.Found(47)))
        assertTrue(silent.yields.isEmpty())
        assertTrue(silent.subscriptions.single().closed)
    }

    @Test
    fun `packages Mimir has live on Main are dropped before anything is asked`() {
        val f = Fixture()
        f.mainPackages = setOf("COM.EXAMPLE.MAINAPP")
        f.watch.floorPaused(4)
        f.resumed(mainApp)
        f.resumed(chrome)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        f.settle()

        assertTrue("no lookup: one candidate is left", f.lookups.isEmpty())
        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `when every foreign resume is Mains own, nothing yields and the watch closes after the settle`() {
        val f = Fixture()
        f.mainPackages = setOf(mainApp)
        f.watch.floorPaused(4)
        f.resumed(mainApp)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        assertTrue(f.yields.isEmpty())

        f.timer(CompanionFloorCoverWatch.SettleMs).run()

        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `the ledger's live Main packages are the PRIMARY app and a Main game, and nothing finished or on the Bottom`() {
        val game = gameSession(MimirGamePlacement.MAIN, MimirSessionPhase.FOREGROUND_PROVEN, "org.example.emulator")
        val app = appSession(MimirAppPlacement.PRIMARY, MimirSessionPhase.DISPATCHED, "com.example.mainapp")

        assertEquals(
            setOf("org.example.emulator", "com.example.mainapp"),
            mainSessionPackages(MimirSessionSnapshot(gameSession = game, primaryAppSession = app)),
        )
        assertEquals(emptySet<String>(), mainSessionPackages(MimirSessionSnapshot()))
        assertEquals(
            "a game on the Bottom is a likely cover",
            emptySet<String>(),
            mainSessionPackages(MimirSessionSnapshot(gameSession = gameSession(MimirGamePlacement.BOTTOM, MimirSessionPhase.FOREGROUND_PROVEN, "org.example.emulator"))),
        )
        assertEquals(
            "an ended game is not live",
            emptySet<String>(),
            mainSessionPackages(MimirSessionSnapshot(gameSession = gameSession(MimirGamePlacement.MAIN, MimirSessionPhase.ENDED, "org.example.emulator"))),
        )
        assertEquals(
            "an ended app is not live",
            emptySet<String>(),
            mainSessionPackages(MimirSessionSnapshot(primaryAppSession = appSession(MimirAppPlacement.PRIMARY, MimirSessionPhase.ENDED, "com.example.mainapp"))),
        )
        assertEquals(
            "a Bottom app is a likely cover",
            emptySet<String>(),
            mainSessionPackages(MimirSessionSnapshot(secondaryAppSession = appSession(MimirAppPlacement.SECONDARY, MimirSessionPhase.DISPATCHED, "com.example.mainapp"))),
        )
    }

    @Test
    fun `a stop while the device is not interactive is sleep, not a cover`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.resumed(chrome)
        f.interactive = false

        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        assertTrue(f.yields.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `a pause while the device is not interactive opens no watch`() {
        val f = Fixture()
        f.interactive = false
        f.watch.floorPaused(4)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        assertTrue(f.subscriptions.isEmpty())
        assertTrue(f.yields.isEmpty())
    }

    @Test
    fun `a Recents or home component missing from the static package list is still a shell`() {
        val f = Fixture()
        f.shellClasses = setOf("com.vendor.recents.RecentsActivity")
        f.watch.floorPaused(4)
        f.resumed("com.vendor.recents", className = "com.vendor.recents.RecentsActivity")
        f.resumed(chrome)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        f.settle()

        assertTrue("the shell did not make the span ambiguous", f.lookups.isEmpty())
        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a resume after the stop's settle belongs to something that woke later, not to the cover`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        f.now += 3_000L
        f.resumed("com.example.game", at = f.now)

        assertTrue(f.yields.isEmpty())
    }

    @Test
    fun `a floor that resumes first ends the watch`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.resumed(chrome)

        f.watch.floorResumed(4)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        f.resumed(chrome)

        assertTrue(f.yields.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `a finishing or reconfiguring floor is not covered by anything`() {
        listOf(true to false, false to true).forEach { (finishing, reconfiguring) ->
            val f = Fixture()
            f.watch.floorPaused(4)
            f.resumed(chrome)
            f.watch.floorStopped(4, finishing = finishing, changingConfigurations = reconfiguring)
            assertTrue(f.yields.isEmpty())
            assertTrue(f.subscriptions.single().closed)
        }
    }

    @Test
    fun `a floor covered by Mimir's own launch host or reclaim guard opens no watch`() {
        listOf(
            launchPending(),
            reclaimPending(),
            snapshot(SecondaryDisplayDisposition.NoDisplay),
            snapshot(SecondaryDisplayDisposition.Yielded(hostLease(), YieldEvidence.HOST_COVERED)),
        ).forEach { covered ->
            val f = Fixture(covered)
            f.watch.floorPaused(4)
            f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
            assertTrue("no subscription for ${covered.disposition}", f.subscriptions.isEmpty())
            assertTrue(f.yields.isEmpty())
        }
    }

    @Test
    fun `a disposition that moved on between the pause and the stop yields nothing`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.resumed(chrome)
        f.snapshot = launchPending()

        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        assertTrue(f.yields.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `another display, or none, opens nothing and ends nothing`() {
        val f = Fixture()
        f.watch.floorPaused(5)
        f.watch.floorPaused(null)
        assertTrue(f.subscriptions.isEmpty())

        f.watch.floorPaused(4)
        f.resumed(chrome)
        f.watch.floorStopped(5, finishing = false, changingConfigurations = false)
        assertTrue(f.yields.isEmpty())
        assertFalse("a stop on another display leaves this watch alone", f.subscriptions.single().closed)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        f.settle()
        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a presenting display opens the watch too`() {
        val f = Fixture(snapshot(SecondaryDisplayDisposition.Presenting(attempt(), PresentCause.DISPLAY_AVAILABLE)))
        f.watch.floorPaused(4)
        f.resumed(chrome)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        f.settle()
        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a second pause supersedes the first watch and the runtime's refusal is asked once`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.watch.floorPaused(4)
        assertTrue(f.subscriptions.first().closed)
        assertFalse(f.subscriptions.last().closed)

        f.yieldResult = false
        f.resumed(chrome)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        f.settle()
        f.resumed(chrome)

        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a lone candidate at the stop yields after the settle, not before`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.resumed(chrome)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        assertTrue("the stop alone decides nothing", f.yields.isEmpty())
        assertFalse(f.subscriptions.single().closed)
        assertTrue("the settle is armed", f.timers.any { it.delayMs == CompanionFloorCoverWatch.SettleMs && !it.cancelled })

        f.settle()

        assertEquals(listOf(4 to chrome), f.yields)
        assertTrue("one candidate needs no lookup", f.lookups.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `a Main app resumed first and the cover published after the stop are told apart, not named by the first`() {
        val f = Fixture()
        f.outcomes = mapOf(chrome to VendorTaskOutcome.Found(47), mainApp to VendorTaskOutcome.NoTask)
        f.watch.floorPaused(4)
        f.resumed(mainApp)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        assertTrue("the Main app alone does not yield", f.yields.isEmpty())
        assertTrue(f.lookups.isEmpty())

        f.now += 100L
        f.resumed(chrome)

        assertEquals("the second resume inside the settle makes the span ambiguous", listOf(4 to listOf(mainApp, chrome)), f.lookups)
        assertEquals("and the lease names the package proven on the Bottom", listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a second package inside the settle that the lookup cannot separate yields nothing`() {
        val f = Fixture()
        f.outcomes = mapOf(chrome to VendorTaskOutcome.Found(47), mainApp to VendorTaskOutcome.Found(12))
        f.watch.floorPaused(4)
        f.resumed(mainApp)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        f.resumed(chrome)

        assertEquals(1, f.lookups.size)
        assertTrue(f.yields.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `a resume past the settle does not join the span of a lone candidate`() {
        val f = Fixture()
        f.watch.floorPaused(4)
        f.resumed(chrome)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)

        f.now += CompanionFloorCoverWatch.SettleMs + 100L
        f.resumed(mainApp, at = f.now)
        f.settle()

        assertTrue(f.lookups.isEmpty())
        assertEquals(listOf(4 to chrome), f.yields)
    }

    @Test
    fun `a lone candidate waiting out the settle is dropped when the floor resumes, Mimir moves on, or the device sleeps`() {
        val resumedFirst = Fixture()
        resumedFirst.watch.floorPaused(4)
        resumedFirst.resumed(chrome)
        resumedFirst.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        resumedFirst.watch.floorResumed(4)
        resumedFirst.settle()
        assertTrue(resumedFirst.yields.isEmpty())
        assertTrue(resumedFirst.subscriptions.single().closed)

        val moved = Fixture()
        moved.watch.floorPaused(4)
        moved.resumed(chrome)
        moved.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        moved.snapshot = launchPending()
        moved.settle()
        assertTrue(moved.yields.isEmpty())
        assertTrue(moved.subscriptions.single().closed)

        val asleep = Fixture()
        asleep.watch.floorPaused(4)
        asleep.resumed(chrome)
        asleep.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        asleep.interactive = false
        asleep.settle()
        assertTrue(asleep.yields.isEmpty())
        assertTrue(asleep.subscriptions.single().closed)
    }

    @Test
    fun `a stale lookup answer for a span that began as a lone candidate is dropped`() {
        val f = Fixture()
        f.answerNow = false
        f.watch.floorPaused(4)
        f.resumed(mainApp)
        f.watch.floorStopped(4, finishing = false, changingConfigurations = false)
        f.resumed(chrome)
        assertEquals("the lookup is out", 1, f.lookups.size)

        f.watch.floorResumed(4)
        f.deliver(mapOf(chrome to VendorTaskOutcome.Found(47), mainApp to VendorTaskOutcome.NoTask))

        assertTrue(f.yields.isEmpty())
        assertTrue(f.subscriptions.single().closed)
    }

    @Test
    fun `the cover rule names Presenting and MirrorPresented on this display only`() {
        val attempt = attempt()
        assertTrue(floorCoverable(snapshot(SecondaryDisplayDisposition.MirrorPresented(attempt)), 4))
        assertTrue(floorCoverable(snapshot(SecondaryDisplayDisposition.Presenting(attempt, PresentCause.RENDERER_REBOUND)), 4))
        assertFalse(floorCoverable(snapshot(SecondaryDisplayDisposition.MirrorPresented(attempt)), 5))
        assertFalse(floorCoverable(launchPending(), 4))
        assertFalse(floorCoverable(reclaimPending(), 4))
        assertFalse(floorCoverable(snapshot(SecondaryDisplayDisposition.NoDisplay), 4))
    }

    private inner class Fixture(var snapshot: CompanionRuntimeSnapshot = mirrored()) {
        var now = 1_000_000L
        var yieldResult = true
        var interactive = true
        var mainPackages = emptySet<String>()
        var shellClasses = emptySet<String>()
        val yields = mutableListOf<Pair<Int, String>>()

        /** The display-task lookup: what it was asked, what it answers, and whether it answers at once or when [deliver] is called. */
        val lookups = mutableListOf<Pair<Int, List<String>>>()
        var outcomes: Map<String, VendorTaskOutcome> = emptyMap()
        var answerNow = true
        private var pendingAnswer: ((Map<String, VendorTaskOutcome>) -> Unit)? = null

        inner class Subscription(val since: Long, val listener: (ActivityResumedObservation) -> Unit) {
            var closed = false
        }

        inner class Timer(val delayMs: Long, private val task: () -> Unit) {
            var cancelled = false
            fun run() {
                if (!cancelled) task()
            }
        }

        val subscriptions = mutableListOf<Subscription>()
        val timers = mutableListOf<Timer>()

        val watch = CompanionFloorCoverWatch(
            snapshot = { snapshot },
            observeResumes = { since, listener ->
                val subscription = Subscription(since, listener).also { subscriptions += it }
                AutoCloseable { subscription.closed = true }
            },
            postToMain = { block -> block() },
            scheduleOnMain = { delayMs, task ->
                val timer = Timer(delayMs, task).also { timers += it }
                AutoCloseable { timer.cancelled = true }
            },
            epochMs = { now },
            ownPackageName = own,
            yieldTo = { displayId, packageName ->
                yields += displayId to packageName
                yieldResult
            },
            isShellResume = { packageName, className -> isCompanionShellPackage(packageName) || className in shellClasses },
            isInteractive = { interactive },
            mainPackages = { mainPackages },
            displayTasks = { displayId, packages, onResolved ->
                lookups += displayId to packages
                if (answerNow) onResolved(outcomes) else pendingAnswer = onResolved
            },
        )

        fun deliver(answer: Map<String, VendorTaskOutcome>) {
            pendingAnswer!!.invoke(answer)
        }

        /** The monitor's thread delivers a resume to the open subscription. */
        fun resumed(packageName: String, at: Long = now, className: String = "$packageName.Main") {
            subscriptions.last().listener(ActivityResumedObservation(packageName, className, at, now))
        }

        fun timer(delayMs: Long): Timer = timers.last { it.delayMs == delayMs }

        /** The settle after the stop elapses. */
        fun settle() = timer(CompanionFloorCoverWatch.SettleMs).run()
    }

    private fun attempt() = RenderAttempt(
        operationId = OperationId.create(),
        parentDispositionId = OperationId.create(),
        rendererOwnerId = RendererOwnerId.create(),
        displayId = 4,
    )

    private fun snapshot(disposition: SecondaryDisplayDisposition) = CompanionRuntimeSnapshot(
        stateVersion = 1L,
        displayId = 4,
        disposition = disposition,
        rendererOwnerId = null,
        mirrorActive = false,
        launchLockOperationId = null,
        launchSettlement = null,
    )

    private fun mirrored() = snapshot(SecondaryDisplayDisposition.MirrorPresented(attempt()))

    private fun gameSession(placement: MimirGamePlacement, phase: MimirSessionPhase, packageName: String) = MimirGameSession(
        operation = MimirGameSessionOperation("op-1"),
        sequence = 1L,
        gameId = GameId("g"),
        platformId = PlatformId("snes"),
        returnAnchor = MimirGameReturnAnchor.Library(
            com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                "shelf",
            ),
        ),
        startedAtEpochMs = 1L,
        targetPackageName = packageName,
        placement = placement,
        dualScreen = false,
        phase = phase,
    )

    private fun appSession(placement: MimirAppPlacement, phase: MimirSessionPhase, packageName: String) = MimirAppSession(
        operationId = "app:1",
        sequence = 1L,
        packageName = packageName,
        label = "Example",
        placement = placement,
        returnAnchor = PrimaryReturnOrigin("LIBRARY"),
        startedAtEpochMs = 1L,
        phase = phase,
    )

    private fun hostLease() = HostLease(
        operationId = OperationId.create(),
        packageName = "com.example.app",
        displayId = 4,
        launchMode = "secondary_app",
        primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
    )

    private fun launchPending() = snapshot(SecondaryDisplayDisposition.LaunchPending(hostLease(), LaunchPhase.TARGET_DISPATCHED))

    private fun reclaimPending() = snapshot(
        SecondaryDisplayDisposition.ReclaimPending(
            lease = ReclaimLease(
                operationId = OperationId.create(),
                parentHostOperationId = null,
                displayId = 4,
                primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
                reason = "overlay_edge_back",
            ),
            phase = ReclaimPhase.GUARD_REQUESTED,
        ),
    )
}
