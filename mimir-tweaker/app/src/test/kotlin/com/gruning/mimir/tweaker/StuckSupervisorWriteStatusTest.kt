package com.gruning.mimir.tweaker

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S3 closing review #6: [HardwareSessionSupervisor.execute] holds the supervisor monitor across its write-capable
 * ports, and `activeSession()` / `leaseRenewable()` need that monitor. A worker timeout freed a Binder thread to answer
 * SERVICE_BUSY, but building that answer read both, so the freed thread blocked behind the very write it was freed
 * from; and the main-thread terminal-settlement recheck read `activeSession()` too, so a stuck write could block MAIN.
 * Both now read the supervisor's published snapshot, which needs no monitor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class StuckSupervisorWriteStatusTest {
    private var now = 0L
    private val releaseWrite = CountDownLatch(1)
    private val writeStarted = CountDownLatch(1)
    private val supervisor = HardwareSessionSupervisor(
        policy = SupervisorPolicy(
            sampleIntervalMs = 1_000L,
            activationGraceMs = 10_000L,
            leaseWindowMs = 30_000L,
            missingTelemetryLimit = 3,
        ),
        clock = SupervisorClock { now },
        workload = WorkloadObservationPort { WorkloadTruth.TARGET_FOREGROUND },
        telemetry = ThermalTelemetryPort { null },
        ownerLiveness = OwnerLivenessPort { _ -> true },
        restorer = GenerationBoundRestorer { _, _ ->
            // The stuck privileged write: blocks inside execute(), with the supervisor monitor held.
            writeStarted.countDown()
            releaseWrite.await()
            TransactionResult(true, false, true, "RESTORED", "ok", transactionId = "tx-7", generation = 7L)
        },
    )
    private var stuckExecutor: Thread? = null

    @After
    fun tearDown() {
        releaseWrite.countDown()
        stuckExecutor?.join(3_000)
    }

    private fun identity(scope: ActiveSessionScope = ActiveSessionScope.LAUNCH) = ActiveSessionIdentity(
        generation = 7L,
        transactionId = "tx-7",
        requestId = "req-7",
        ownerUid = 42,
        targetPackage = "example.game",
        activationDeadlineMs = 10_000L,
        leaseDeadlineMs = ActiveSessionLeaseDeadline.initial(
            scope = scope,
            nowElapsedRealtimeMs = 0L,
            renewableWindowMs = 30_000L,
            hardDeadlineElapsedRealtimeMs = 120_000L,
        ),
        targetEverForeground = true,
        ownerTokenId = NO_OWNER_TOKEN_ID,
        hardLeaseDeadlineMs = 120_000L,
        scope = scope,
    )

    /** Parks a thread inside execute(), holding the supervisor monitor until [tearDown] releases the write. */
    private fun wedgeSupervisorInAWrite() {
        supervisor.adoptAppliedSession(identity())
        stuckExecutor = Thread {
            supervisor.execute(SupervisorDecision.Restore(7L, "tx-7", "TEST"))
        }.apply { isDaemon = true; start() }
        assertTrue(writeStarted.await(3, TimeUnit.SECONDS))
    }

    /** Runs [read] on its own thread and reports whether it returned within a second, so a block is a failure, not a hang. */
    private fun <T> returnsPromptly(read: () -> T): Pair<Boolean, T?> {
        val out = AtomicReference<Any?>(null)
        val done = CountDownLatch(1)
        Thread {
            out.set(read())
            done.countDown()
        }.apply { isDaemon = true; start() }
        val returned = done.await(1, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return returned to (out.get() as T?)
    }

    @Test
    fun `the monitor readers do block behind a stuck write, which is why the snapshot exists`() {
        wedgeSupervisorInAWrite()

        assertFalse("activeSession() takes the monitor", returnsPromptly { supervisor.activeSession() }.first)
        assertFalse("leaseRenewable() takes the monitor", returnsPromptly { supervisor.leaseRenewable() }.first)
    }

    @Test
    fun `a SERVICE_BUSY bundle is built and returned while the supervisor write is stuck`() {
        wedgeSupervisorInAWrite()

        val (returned, bundle) = returnsPromptly {
            // Exactly what the service's publicSessionTruth() reads, then the SERVICE_BUSY answer around it.
            val truth = PublicSessionTruthResolver.resolve(
                journal = JournalLoadResult.Empty,
                active = supervisor.activeSessionNoLock(),
                recoveryOwned = false,
                inFlightOwner = null,
                leaseRenewable = supervisor.leaseRenewableNoLock(),
            )
            TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = SERVICE_BUSY_CODE,
                message = "SERVICE_BUSY: the transaction worker was still running this call",
            ).toPublicBundle(callerRequestId = "req-1", callerDeadlineElapsedRealtimeMs = 1_000L, truth = truth)
        }

        assertTrue("the SERVICE_BUSY answer must not wait on the supervisor monitor", returned)
        assertEquals(SERVICE_BUSY_CODE, bundle!!.getString("code"))
        assertFalse(bundle.getBoolean("success"))
    }

    @Test
    fun `the main-thread terminal gate decides while the supervisor write is stuck, and sees the live session`() {
        wedgeSupervisorInAWrite()

        val (returned, mayStop) = returnsPromptly {
            val truthNow = StartedServiceTerminalTruth(
                journalEmpty = true,
                publicOwnerAbsent = true,
                supervisorSessionAbsent = supervisor.activeSessionNoLock() == null,
                monitorAbsent = true,
                recoveryOwnerAbsent = true,
                restoreRetryAbsent = true,
                foregroundRetryAbsent = true,
                lifetimeIdle = true,
                labHoldInactive = true,
                calibrationInactive = true,
                transactionWorkTerminal = true,
            )
            StartedServiceTerminalGate.mayStopNow(plannedStartId = 3, truthNow = truthNow, latestStartIdNow = 3)
        }

        assertTrue("the gate must not wait on the supervisor monitor", returned)
        assertFalse("a session is still held, so the service must not stop", mayStop!!)
    }

    @Test
    fun `the snapshot tracks every change the monitor readers see`() {
        fun assertSame(label: String) {
            assertEquals("$label: session", supervisor.activeSession(), supervisor.activeSessionNoLock())
            assertEquals("$label: lease", supervisor.leaseRenewable(), supervisor.leaseRenewableNoLock())
        }
        assertSame("empty")
        assertNull(supervisor.activeSessionNoLock())
        assertFalse(supervisor.leaseRenewableNoLock())

        supervisor.adoptAppliedSession(identity())
        assertSame("adopted")
        now = 1_000L // a renewal only counts once the window has moved past the lease it already holds
        assertTrue(supervisor.leaseRenewableNoLock())

        now = 5_000L
        assertTrue(
            supervisor.renewLease(ownerUid = 42, generation = 7L, transactionId = "tx-7", requestId = "req-7"),
        )
        assertSame("renewed")

        supervisor.armBoundedRetryAfterFailedRestore()
        assertSame("retry armed")
        assertFalse("a pending restore retry blocks renewal", supervisor.leaseRenewableNoLock())

        supervisor.adoptAppliedSession(identity())
        assertSame("re-adopted clears the retry")
        assertTrue(supervisor.leaseRenewableNoLock())

        supervisor.enterCorruptHold()
        assertSame("corrupt hold")
        assertFalse(supervisor.leaseRenewableNoLock())

        supervisor.enterCrossStoreHold()
        assertSame("cross-store hold")
        assertFalse(supervisor.leaseRenewableNoLock())

        supervisor.clearSession()
        assertSame("cleared")
        assertNull(supervisor.activeSessionNoLock())

        supervisor.adoptAppliedSession(identity(scope = ActiveSessionScope.SYSTEM))
        assertSame("system scope")
        assertFalse("SYSTEM sessions never renew", supervisor.leaseRenewableNoLock())
    }

    /** The service must keep building its public truth and its main-thread recheck from the snapshot. */
    @Test
    fun `the service reads the supervisor lock-free for public truth and the terminal recheck`() {
        val service = source("MimirTweakerService.kt")
        val truth = service.substringAfter("private fun publicSessionTruth(): PublicSessionTruth {").substringBefore("\n    }\n")
        assertTrue(truth.contains("supervisor.activeSessionNoLock()"))
        assertTrue(truth.contains("supervisor.leaseRenewableNoLock()"))
        assertFalse(truth.contains("supervisor.activeSession()"))
        assertFalse(truth.contains("supervisor.leaseRenewable()"))

        val terminal = service.substringAfter("private fun terminalTruthNow(").substringBefore("\n    )\n")
        assertTrue(terminal.contains("supervisorSessionAbsent = supervisor.activeSessionNoLock() == null"))
        assertFalse(terminal.contains("supervisor.activeSession()"))
    }

    private fun source(fileName: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $fileName not found")
}
