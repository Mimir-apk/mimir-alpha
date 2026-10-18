package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsLimiterControllerTest {
    private val identity = GameModeFpsPackageIdentity(
        packageName = "dev.eden.eden_emulator",
        userId = 0,
        uid = 10123,
        versionCode = 7L,
        signingCertificateSha256 = "a".repeat(64),
    )

    @Test
    fun `capability requires game identity empty journal exact repeated baseline and refresh divisor`() {
        val port = FakePort(baseline())
        val controller = controller(port = port)

        assertEquals(listOf(30, 40, 60, 120), controller.capability(identity.packageName)?.frameLimitsHz)
        port.observations += target(60)
        assertEquals(null, controller.capability(identity.packageName))
    }

    @Test
    fun `apply persists PREPARED before command and adopts only exact target readback`() {
        val journal = RecordingJournal()
        val port = FakePort(baseline())
        val controller = controller(journal, port)
        port.onExecute = { port.observations += target(60) }

        val result = controller.apply(request())

        assertTrue(result is FpsLimiterApplyResult.Applied)
        assertEquals(listOf(FpsLimiterJournalPhase.PREPARED, FpsLimiterJournalPhase.APPLIED), journal.persisted)
        assertTrue(port.commands.single().contains("--fps 60"))
        assertEquals(
            10L + ProductOwnerHardLeasePolicy.HARD_LEASE_MS,
            (result as FpsLimiterApplyResult.Applied).record.hardDeadlineElapsedRealtimeMs,
        )
        assertFalse(result.record.hardDeadlineElapsedRealtimeMs == request().deadlineElapsedRealtimeMs)
    }

    @Test
    fun `ambiguous command triages exact target without replay and keeps restore debt`() {
        val journal = RecordingJournal()
        val port = FakePort(baseline()).apply {
            status = CheckedCommandStatus.AMBIGUOUS
            onExecute = { observations += target(60) }
        }
        val controller = controller(journal, port)

        assertTrue(controller.apply(request()) is FpsLimiterApplyResult.Applied)
        assertEquals(1, port.commands.size)
        assertTrue(controller.activeRecord()?.phase == FpsLimiterJournalPhase.APPLIED)
    }

    @Test
    fun `ambiguous apply with exact baseline clears as rejected`() {
        val journal = RecordingJournal()
        val port = FakePort(baseline()).apply { status = CheckedCommandStatus.AMBIGUOUS }
        val controller = controller(journal, port)

        val result = controller.apply(request())

        assertEquals(FpsLimiterApplyResult.Rejected("FPS_APPLY_REJECTED"), result)
        assertEquals(FpsLimiterJournalLoad.Empty, journal.load())
    }

    @Test
    fun `unknown apply result retains signed restore debt`() {
        val journal = RecordingJournal()
        val port = FakePort(baseline()).apply {
            onExecute = { observations += other() }
        }
        val controller = controller(journal, port)

        val result = controller.apply(request()) as FpsLimiterApplyResult.Uncertain

        assertTrue(result.restoreDebtRetained)
        assertEquals(FpsLimiterJournalPhase.RESTORE_FAILED, controller.activeRecord()?.phase)
    }

    @Test
    fun `restore orders reset then standard and clears only exact baseline`() {
        val journal = RecordingJournal()
        val port = FakePort(baseline()).apply { onExecute = { command -> if (" standard " in command) observations += baseline() } }
        val controller = controller(journal, port)
        port.onExecute = { command ->
            if ("--fps" in command) port.observations += target(60)
            if (" standard " in command) port.observations += baseline()
        }
        assertTrue(controller.apply(request()) is FpsLimiterApplyResult.Applied)

        assertEquals(FpsLimiterRestoreResult.Restored, controller.restore())
        assertTrue(port.commands[1].contains(" reset "))
        assertTrue(port.commands[2].contains(" standard "))
        assertEquals(FpsLimiterJournalLoad.Empty, journal.load())
    }

    @Test
    fun `identity drift and locked user retain debt without restore command`() {
        val journal = RecordingJournal()
        val port = FakePort(baseline()).apply { onExecute = { observations += target(60) } }
        var unlocked = true
        var currentIdentity: GameModeFpsPackageIdentity? = identity
        val controller = controller(
            journal = journal,
            port = port,
            unlocked = { unlocked },
            identityPort = { currentIdentity },
        )
        assertTrue(controller.apply(request()) is FpsLimiterApplyResult.Applied)
        val commandCount = port.commands.size
        unlocked = false
        assertEquals(FpsLimiterRestoreResult.Retained("FPS_USER_LOCKED"), controller.restore())
        assertEquals(commandCount, port.commands.size)
        unlocked = true
        currentIdentity = identity.copy(versionCode = 8L)
        assertEquals(FpsLimiterRestoreResult.Retained("FPS_PACKAGE_IDENTITY_CHANGED"), controller.restore())
        assertEquals(commandCount, port.commands.size)
    }

    private fun controller(
        journal: RecordingJournal = RecordingJournal(),
        port: FakePort = FakePort(baseline()),
        unlocked: () -> Boolean = { true },
        identityPort: (String) -> GameModeFpsPackageIdentity? = { identity },
    ) = FpsLimiterController(
        journal = journal,
        packageIdentity = GameModeFpsPackageIdentityPort(identityPort),
        control = port,
        refreshRate = ActiveRefreshRatePort { 120 },
        userUnlocked = unlocked,
        bootIdentity = { "boot-1" },
        elapsedRealtimeMs = { 10L },
        transactionId = { "mimir.fps.tx" },
        sleepMs = {},
    )

    private fun request() = FpsLimiterRequest(
        requestId = "mimir.fps.request",
        ownerUid = 1000,
        packageName = identity.packageName,
        targetFps = 60,
        deadlineElapsedRealtimeMs = 1_000L,
    )

    private fun baseline() = readback("standard", GameModeConfigReadback.Baseline(identity.packageName))
    private fun target(fps: Int) = readback("custom", GameModeConfigReadback.FpsOverride(identity.packageName, fps))
    private fun other() = readback("performance", GameModeConfigReadback.FpsOverride(identity.packageName, 30))

    private fun readback(mode: String, config: GameModeConfigReadback) = GameModeFpsReadback(
        modes = GameModeModesReadback(
            identity.packageName,
            mode,
            setOf("standard", "performance", "battery", "custom"),
        ),
        config = config,
    )

    private class FakePort(initial: GameModeFpsReadback) : GameModeFpsControlPort {
        override val available = true
        val observations = ArrayDeque<GameModeFpsReadback>().apply { add(initial) }
        val commands = mutableListOf<String>()
        var status = CheckedCommandStatus.CONFIRMED
        var onExecute: (String) -> Unit = {}
        private var last = initial

        override fun observe(packageName: String): GameModeFpsReadback {
            if (observations.isNotEmpty()) last = observations.removeFirst()
            return last
        }

        override fun executeChecked(command: String): CheckedCommandResult {
            commands += command
            onExecute(command)
            return CheckedCommandResult(status)
        }
    }

    private class RecordingJournal : FpsLimiterJournal {
        private val delegate = InMemoryFpsLimiterJournal()
        val persisted = mutableListOf<FpsLimiterJournalPhase>()
        override fun load() = delegate.load()
        override fun nextGeneration() = delegate.nextGeneration()
        override fun persist(record: FpsLimiterJournalRecord): Boolean = delegate.persist(record).also {
            if (it) persisted += record.phase
        }
        override fun clear(expectedTransactionId: String) = delegate.clear(expectedTransactionId)
    }
}
