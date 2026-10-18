package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsLimiterServiceSessionTest {
    private val identity = GameModeFpsPackageIdentity(
        packageName = "dev.eden.eden_emulator",
        userId = 0,
        uid = 10_123,
        versionCode = 7L,
        signingCertificateSha256 = "a".repeat(64),
    )

    @Test
    fun `started admission owns one exact apply and restore session`() {
        val fixture = Fixture()
        val token = FakeToken()
        fixture.session.begin(startId = 7, graceMs = 10_000L)

        val applied = fixture.session.apply(fixture.request(), token) as FpsLimiterApplyResult.Applied

        assertEquals(
            FpsLimiterRestoreResult.Restored,
            fixture.session.restore(
                ownerUid = 1_000,
                requestId = "mimir.fps.request",
                token = token,
                generation = applied.record.generation,
                transactionId = applied.record.transactionId,
            ),
        )
        assertEquals(listOf("apply", "reset", "standard"), fixture.port.operations)
        assertEquals(FpsLimiterTerminalTruth(true, true, true, true), fixture.session.terminalTruth())
        assertTrue(fixture.scheduler.handles.single().cancelled)
    }

    @Test
    fun `apply without a started admission performs no command`() {
        val fixture = Fixture()

        val result = fixture.session.apply(fixture.request(), FakeToken())

        assertEquals(FpsLimiterApplyResult.Rejected("FPS_SERVICE_NOT_STARTED"), result)
        assertTrue(fixture.port.operations.isEmpty())
        assertEquals(FpsLimiterJournalLoad.Empty, fixture.journal.load())
    }

    @Test
    fun `exact binder death restores and clears public ownership`() {
        val fixture = Fixture()
        val token = FakeToken()
        fixture.session.begin(startId = 8, graceMs = 10_000L)
        assertTrue(fixture.session.apply(fixture.request(), token) is FpsLimiterApplyResult.Applied)

        token.die()

        assertEquals(listOf("apply", "reset", "standard"), fixture.port.operations)
        assertEquals(FpsLimiterJournalLoad.Empty, fixture.journal.load())
        assertEquals(FpsLimiterTerminalTruth(true, true, true, true), fixture.session.terminalTruth())
    }

    @Test
    fun `locked orphan retains recovery then exact unlock replay clears debt`() {
        var unlocked = true
        val journal = InMemoryFpsLimiterJournal()
        val port = FakePort()
        val owner = Fixture(journal, port, unlocked = { unlocked })
        owner.session.begin(startId = 9, graceMs = 10_000L)
        assertTrue(owner.session.apply(owner.request(), FakeToken()) is FpsLimiterApplyResult.Applied)

        val recoveredProcess = Fixture(journal, port, unlocked = { unlocked })
        recoveredProcess.session.markRecovery(startId = 10)
        unlocked = false
        assertEquals(
            FpsLimiterRestoreResult.Retained("FPS_USER_LOCKED"),
            recoveredProcess.session.recoverOrphan(),
        )
        assertFalse(recoveredProcess.session.terminalTruth().journalEmpty)
        assertTrue(recoveredProcess.session.terminalTruth().ownerAbsent)
        assertFalse(recoveredProcess.session.terminalTruth().lifetimeIdle)
        assertEquals(listOf("apply"), port.operations)

        unlocked = true
        assertEquals(FpsLimiterRestoreResult.Restored, recoveredProcess.session.recoverOrphan())
        assertEquals(listOf("apply", "reset", "standard"), port.operations)
        assertEquals(FpsLimiterTerminalTruth(true, true, true, true), recoveredProcess.session.terminalTruth())
    }

    @Test
    fun `service owned hard expiry restores exact generation and clears owner`() {
        val fixture = Fixture()
        fixture.session.begin(startId = 11, graceMs = 10_000L)
        val applied = fixture.session.apply(fixture.request(), FakeToken()) as FpsLimiterApplyResult.Applied
        assertFalse(fixture.session.terminalTruth().expiryIdle)

        fixture.now = applied.record.hardDeadlineElapsedRealtimeMs
        fixture.scheduler.handles.single().run()

        assertEquals(listOf("apply", "reset", "standard"), fixture.port.operations)
        assertEquals(FpsLimiterJournalLoad.Empty, fixture.journal.load())
        assertEquals(FpsLimiterTerminalTruth(true, true, true, true), fixture.session.terminalTruth())
    }

    @Test
    fun `stale expiry callback cannot restore a newer transaction`() {
        val fixture = Fixture()
        fixture.session.begin(startId = 12, graceMs = 10_000L)
        val firstToken = FakeToken()
        val first = fixture.session.apply(fixture.request(), firstToken) as FpsLimiterApplyResult.Applied
        val stale = fixture.scheduler.handles.single()
        fixture.session.restore(
            ownerUid = 1_000,
            requestId = "mimir.fps.request",
            token = firstToken,
            generation = first.record.generation,
            transactionId = first.record.transactionId,
        )
        fixture.port.operations.clear()
        fixture.session.begin(startId = 13, graceMs = 10_000L)
        fixture.session.apply(fixture.request("mimir.fps.request.2"), FakeToken())

        stale.runEvenIfCancelled()

        assertEquals(listOf("apply"), fixture.port.operations)
        assertFalse(fixture.session.terminalTruth().journalEmpty)
    }

    @Test
    fun `expiry restore failure retains durable recovery without a live timer`() {
        val fixture = Fixture()
        fixture.session.begin(startId = 14, graceMs = 10_000L)
        val applied = fixture.session.apply(fixture.request(), FakeToken()) as FpsLimiterApplyResult.Applied
        fixture.port.restoreBaseline = false
        fixture.now = applied.record.hardDeadlineElapsedRealtimeMs

        fixture.scheduler.handles.single().run()

        assertFalse(fixture.session.terminalTruth().journalEmpty)
        assertTrue(fixture.session.terminalTruth().ownerAbsent)
        assertFalse(fixture.session.terminalTruth().lifetimeIdle)
        assertTrue(fixture.session.terminalTruth().expiryIdle)
    }

    private inner class Fixture(
        val journal: InMemoryFpsLimiterJournal = InMemoryFpsLimiterJournal(),
        val port: FakePort = FakePort(),
        unlocked: () -> Boolean = { true },
    ) {
        var now = 10L
        var stateChanges = 0
        val scheduler = FakeExpiryScheduler()
        private val controller = FpsLimiterController(
            journal = journal,
            packageIdentity = GameModeFpsPackageIdentityPort { packageName ->
                identity.takeIf { it.packageName == packageName }
            },
            control = port,
            refreshRate = ActiveRefreshRatePort { 120 },
            userUnlocked = unlocked,
            bootIdentity = { "boot-1" },
            elapsedRealtimeMs = { now },
            transactionId = { "mimir.fps.tx" },
            sleepMs = {},
        )
        val session = FpsLimiterServiceSession(
            controller = controller,
            journal = journal,
            elapsedRealtimeMs = { now },
            executeAsync = { work -> work() },
            expiryScheduler = scheduler,
            onStateChanged = { stateChanges++ },
        )

        fun request(requestId: String = "mimir.fps.request") = FpsLimiterRequest(
            requestId = requestId,
            ownerUid = 1_000,
            packageName = identity.packageName,
            targetFps = 60,
            deadlineElapsedRealtimeMs = 1_000L,
        )

    }

    private inner class FakePort : GameModeFpsControlPort {
        override val available = true
        val operations = mutableListOf<String>()
        private var current = baseline()
        var restoreBaseline = true

        override fun observe(packageName: String): GameModeFpsReadback = current

        override fun executeChecked(command: String): CheckedCommandResult {
            when {
                "--fps 60" in command -> {
                    operations += "apply"
                    current = target(60)
                }
                " reset " in command -> operations += "reset"
                " standard " in command -> {
                    operations += "standard"
                    if (restoreBaseline) current = baseline()
                }
                else -> error("Unexpected command: $command")
            }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED)
        }
    }

    private class FakeExpiryScheduler : FpsLimiterExpiryScheduler {
        val handles = mutableListOf<FakeExpiryHandle>()

        override fun schedule(delayMs: Long, action: () -> Unit): FpsLimiterExpiryHandle =
            FakeExpiryHandle(delayMs, action).also(handles::add)
    }

    private class FakeExpiryHandle(
        val delayMs: Long,
        private val action: () -> Unit,
    ) : FpsLimiterExpiryHandle {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
        fun run() {
            if (!cancelled) action()
        }
        fun runEvenIfCancelled() = action()
    }

    private class FakeToken : ClientTokenHandle {
        private var alive = true
        private var death: (() -> Unit)? = null

        override fun sameToken(other: ClientTokenHandle): Boolean = this === other
        override fun isAlive(): Boolean = alive
        override fun linkToDeath(onDeath: () -> Unit): Boolean {
            death = onDeath
            return alive
        }
        override fun unlinkFromDeath() {
            death = null
        }

        fun die() {
            if (!alive) return
            alive = false
            death?.invoke()
        }
    }

    private fun baseline() = readback("standard", GameModeConfigReadback.Baseline(identity.packageName))
    private fun target(fps: Int) = readback(
        "custom",
        GameModeConfigReadback.FpsOverride(identity.packageName, fps),
    )
    private fun readback(mode: String, config: GameModeConfigReadback) = GameModeFpsReadback(
        modes = GameModeModesReadback(
            packageName = identity.packageName,
            currentMode = mode,
            availableModes = setOf("standard", "performance", "battery", "custom"),
        ),
        config = config,
    )
}
