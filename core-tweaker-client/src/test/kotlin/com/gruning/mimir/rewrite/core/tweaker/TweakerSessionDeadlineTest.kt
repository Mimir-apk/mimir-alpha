package com.gruning.mimir.rewrite.core.tweaker

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.tweaker.IMimirTweaker
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Every Tweaker call that hands the daemon a deadline stops waiting at that deadline (round 5e).
 *
 * V4 query and V5 selection previously had no enforced client deadline. A daemon
 * that ignores the deadline it is given holds the reservation past 215 s, blocking every other
 * privileged row. The daemon in this tree checks the deadline when a request is admitted and between
 * the commands of a transaction, but its binder thread waits on a single transaction worker with no
 * bound (`MimirTweakerService.onTransactionWorker`), so a reply can leave after the deadline it echoes.
 *
 * Two fake daemons per call. One never answers. The other answers well after the deadline with a reply
 * the parser would accept: the session's clock is frozen, so nothing but the wait itself stands between
 * that late reply and the caller. Both must end at the deadline with the named timeout, and the late
 * reply must be dropped unread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerSessionDeadlineTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val caller = Executors.newCachedThreadPool()

    @Before
    fun clearLogs() {
        ShadowLog.clear()
    }

    @After
    fun stopCaller() {
        caller.shutdownNow()
    }

    // --- The calls that send the daemon a deadline ---

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 session query ends at its deadline when the daemon never answers`() = neverAnswers(query())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 session query drops a reply that arrives after its deadline`() = answersLate(query())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V5 system selection ends at its deadline when the daemon never answers`() =
        neverAnswers(systemSelection())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V5 system selection drops a reply that arrives after its deadline`() =
        answersLate(systemSelection())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 apply ends at its deadline when the daemon never answers`() = neverAnswers(v4Apply())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 apply drops a reply that arrives after its deadline`() = answersLate(v4Apply())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 in-flight cancel ends at its deadline when the daemon never answers`() =
        neverAnswers(v4CancelInFlight())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 in-flight cancel drops a reply that arrives after its deadline`() =
        answersLate(v4CancelInFlight())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 active cancel ends at its deadline when the daemon never answers`() =
        neverAnswers(v4CancelActive())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 active cancel drops a reply that arrives after its deadline`() = answersLate(v4CancelActive())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 lease renewal ends at its deadline when the daemon never answers`() = neverAnswers(v4Renew())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 lease renewal drops a reply that arrives after its deadline`() = answersLate(v4Renew())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 restore ends at its deadline when the daemon never answers`() = neverAnswers(v4Restore())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V4 restore drops a reply that arrives after its deadline`() = answersLate(v4Restore())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V6 fan control ends at its deadline when the daemon never answers`() = neverAnswers(fanControl())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V6 fan control drops a reply that arrives after its deadline`() = answersLate(fanControl())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V7 frame-limit apply ends at its deadline and keeps its restore debt`() = neverAnswers(fpsApply())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V7 frame-limit apply drops a reply that arrives after its deadline`() = answersLate(fpsApply())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V7 frame-limit restore ends at its deadline when the daemon never answers`() =
        neverAnswers(fpsRestore())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V7 frame-limit restore drops a reply that arrives after its deadline`() = answersLate(fpsRestore())

    // --- Read-only calls that carry no deadline, bounded like the capability reads (register O-2) ---

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V9 discovered-session read ends at its bound when the daemon never answers`() =
        neverAnswers(discoveredSessionRead())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V9 discovered-session read drops a reply that arrives after its bound`() =
        answersLate(discoveredSessionRead())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V10 discovered-ceiling read ends at its bound when the daemon never answers`() =
        neverAnswers(discoveredCeilingRead())

    @Test(timeout = TEST_TIMEOUT_MS)
    fun `V10 discovered-ceiling read drops a reply that arrives after its bound`() =
        answersLate(discoveredCeilingRead())

    /**
     * R6-5E-02 (policy 2026-09-15, "Daemon change, next release"): the daemon queues
     * `queryFpsLimiterV7` behind its one transaction worker, exactly where the advanced-capabilities read
     * above can stick during an owner-death restore. A client-side deadline on a READ cannot misreport a
     * write, so it is safe to bound the same way as the capability reads (register O-2), ahead of the V11
     * daemon contract this ticket otherwise waits on.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun `a stuck frame-limit query ends at its deadline instead of hanging`() = neverAnswers(frameLimitQuery())

    // --- What a daemon that ignores every deadline may cost, and what the owner gives back ---

    /**
     * A reply that never comes keeps one thread blocked in its Binder transaction; the client cannot
     * interrupt that. So the stuck calls are counted and capped, and the call after the cap is refused at
     * once with its own named outcome instead of adding a thread. When the daemon answers, they clear.
     */
    @Test(timeout = 30_000L)
    fun `calls stuck past their deadlines are capped and the next one is refused at once`() {
        val release = CountDownLatch(1)
        val scenario = query()
        val session = connectedSession(
            fakeDaemon(scenario) { args ->
                release.await()
                scenario.reply(args)
            },
        )
        try {
            assertTrue(runBlocking { session.bind() })
            repeat(STUCK_CALL_CAP) { index ->
                val (result, _) = callWithinDeadline(scenario) { scenario.invoke(session) }
                assertTrue("stuck query ${index + 1} must end with its timeout, got $result", scenario.timedOut(result))
            }
            val startedNs = System.nanoTime()
            val refused = session.querySessionV4(4, CALL_DEADLINE_MS)
            val tookMs = (System.nanoTime() - startedNs) / 1_000_000L
            assertTrue(
                "with $STUCK_CALL_CAP calls stuck past their deadlines the next query must be refused, got $refused",
                (refused as? TweakerV4SessionAssessment.Uncertain)?.reason?.contains(REFUSED) == true,
            )
            assertTrue("the refusal must not wait for the daemon, took $tookMs ms", tookMs < CALL_DEADLINE_MS / 2)
        } finally {
            release.countDown()
        }
        val healthyByNs = System.nanoTime() + 5_000_000_000L
        while (session.querySessionV4(4, CALL_DEADLINE_MS) !is TweakerV4SessionAssessment.Stock) {
            if (System.nanoTime() > healthyByNs) fail("the transport never recovered once the daemon answered")
            Thread.sleep(20L)
        }
    }

    /**
     * R6-5E-01: the daemon serves `getAdvancedCapabilitiesV6` on its one transaction worker, which an
     * owner-death restore can hold for seconds; a call stuck past its deadline keeps its thread, since a
     * Binder transaction cannot be cancelled from the client. Before this fix [TweakerSession.getApiVersion]
     * shared that read's one-thread executor, so the tuner's version read -- the one the cold-start reconcile
     * needs to resolve the route -- was refused (`outcome=REJECTED reason=capability_call_in_flight`) for as
     * long as the stuck call held it, and the route flipped to REFUSE. [TweakerSession.getApiVersion] now runs
     * on the unshared deadline pool instead, so it answers regardless.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun `an API-version read is answered while an advanced-capabilities read is stuck past its deadline`() {
        val release = CountDownLatch(1)
        val scenario = advancedCapabilitiesRead()
        val session = connectedSession(
            fakeDaemon(scenario) { args ->
                release.await()
                scenario.reply(args)
            },
        )
        try {
            assertTrue(runBlocking { session.bind() })

            val (stuck, _) = callWithinDeadline(scenario) {
                session.getAdvancedCapabilitiesV6(10, CALL_DEADLINE_MS)
            }
            assertEquals("the stuck advanced read must end null at its deadline", null, stuck)
            awaitSessionLog("getAdvancedCapabilitiesV6 outcome=TIMED_OUT")

            val startedNs = System.nanoTime()
            val version = session.getApiVersion(CALL_DEADLINE_MS)
            val tookMs = (System.nanoTime() - startedNs) / 1_000_000L
            assertEquals("getApiVersion must answer while the advanced read is still stuck", 10, version)
            assertTrue(
                "getApiVersion took $tookMs ms; it must not wait on the stuck advanced read",
                tookMs < CALL_DEADLINE_MS / 2,
            )
        } finally {
            release.countDown()
        }
    }

    /** The process-start confirmation's bind is released when its query times out, and nothing stays owned. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun `process-start confirmation ends at the query deadline and releases its bind`() {
        val queries = AtomicInteger()
        val release = CountDownLatch(1)
        val unbinds = AtomicInteger()
        val scenario = query()
        val session = connectedSession(
            api = fakeDaemon(scenario) { args ->
                if (queries.incrementAndGet() == 1) release.await()
                scenario.reply(args)
            },
            unbinds = unbinds,
        )
        val owner = TweakerV4Owner(session)
        try {
            val (outcome, _) = callWithinDeadline(scenario) {
                runBlocking {
                    owner.confirmAfterProcessStart(bindTimeoutMs = BIND_TIMEOUT_MS, queryTimeoutMs = CALL_DEADLINE_MS)
                }
            }
            assertTrue(
                "confirmation must end with the query's timeout, got $outcome",
                (outcome as? TweakerV4OwnerOutcome.Uncertain)?.reason?.contains(TIMED_OUT) == true,
            )
            assertFalse("the bind must be released", session.isBound)
            assertEquals(1, unbinds.get())
            assertTrue(owner.state.value is TweakerV4OwnerState.Unconfirmed)
        } finally {
            release.countDown()
        }
        assertEquals(
            "nothing may stay held: the next confirmation is admitted and reads Stock",
            TweakerV4OwnerOutcome.Stock,
            runBlocking { owner.confirmAfterProcessStart(BIND_TIMEOUT_MS, CALL_DEADLINE_MS) },
        )
    }

    /** A Stock reply the daemon sends after the query deadline must not turn a timed-out start into Stock. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun `process-start confirmation ignores a Stock reply that arrives after the query deadline`() {
        val answered = CountDownLatch(1)
        val unbinds = AtomicInteger()
        val scenario = query()
        val session = connectedSession(
            api = fakeDaemon(scenario) { args ->
                try {
                    Thread.sleep(CALL_DEADLINE_MS + LATE_BY_MS)
                    scenario.reply(args)
                } finally {
                    answered.countDown()
                }
            },
            unbinds = unbinds,
        )
        val owner = TweakerV4Owner(session)

        val (outcome, _) = callWithinDeadline(scenario) {
            runBlocking {
                owner.confirmAfterProcessStart(bindTimeoutMs = BIND_TIMEOUT_MS, queryTimeoutMs = CALL_DEADLINE_MS)
            }
        }
        assertTrue(
            "confirmation must end with the query's timeout, got $outcome",
            (outcome as? TweakerV4OwnerOutcome.Uncertain)?.reason?.contains(TIMED_OUT) == true,
        )
        assertTrue(answered.await(CALL_DEADLINE_MS + LATE_BY_MS + 2_000L, TimeUnit.MILLISECONDS))
        awaitSessionLog("querySessionV4 outcome=LATE_REPLY_IGNORED")

        assertTrue("the late Stock changes nothing", owner.state.value is TweakerV4OwnerState.Unconfirmed)
        assertFalse(session.isBound)
        assertEquals(1, unbinds.get())
    }

    private fun neverAnswers(scenario: DeadlineCall) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = connectedSession(
            fakeDaemon(scenario) { args ->
                entered.countDown()
                release.await()
                scenario.reply(args)
            },
        )
        try {
            assertTrue(runBlocking { session.bind() })
            val (result, tookMs) = callWithinDeadline(scenario) { scenario.invoke(session) }

            assertTrue(
                "${scenario.name}: the fake daemon never received the call",
                entered.await(0L, TimeUnit.MILLISECONDS),
            )
            assertTrue("${scenario.name} must end with its named timeout, got $result", scenario.timedOut(result))
            assertTrue(
                "${scenario.name} gave up after $tookMs ms, before its ${scenario.deadlineMs} ms deadline",
                tookMs >= scenario.deadlineMs - EARLY_TOLERANCE_MS,
            )
            assertTrue("${scenario.name}: a timeout tears down nothing its caller owns", session.isBound)
        } finally {
            release.countDown()
        }
    }

    private fun answersLate(scenario: DeadlineCall) {
        val answered = CountDownLatch(1)
        val session = connectedSession(
            fakeDaemon(scenario) { args ->
                try {
                    Thread.sleep(scenario.deadlineMs + LATE_BY_MS)
                    scenario.reply(args)
                } finally {
                    answered.countDown()
                }
            },
        )
        assertTrue(runBlocking { session.bind() })

        val (result, _) = callWithinDeadline(scenario) { scenario.invoke(session) }

        assertFalse(
            "${scenario.name} handed its caller a reply that arrived after the deadline: $result",
            scenario.acceptedLateReply(result),
        )
        assertTrue("${scenario.name} must end with its named timeout, got $result", scenario.timedOut(result))
        assertTrue(answered.await(scenario.deadlineMs + LATE_BY_MS + 2_000L, TimeUnit.MILLISECONDS))
        awaitSessionLog("${scenario.name} outcome=LATE_REPLY_IGNORED")
        assertTrue("${scenario.name}: the late reply tears down nothing", session.isBound)
    }

    /**
     * Runs [block] off the test thread and waits no longer than the deadline plus [RETURN_SLACK_MS]. On code
     * that does not enforce the deadline this fails with a message instead of hanging the test JVM.
     */
    private fun <T> callWithinDeadline(scenario: DeadlineCall, block: () -> T): Pair<T, Long> {
        val startedNs = System.nanoTime()
        val pending = caller.submit(Callable { block() })
        val result = try {
            pending.get(scenario.deadlineMs + RETURN_SLACK_MS, TimeUnit.MILLISECONDS)
        } catch (stillWaiting: TimeoutException) {
            fail(
                "${scenario.name} was still waiting on the daemon ${scenario.deadlineMs + RETURN_SLACK_MS} ms " +
                    "after it was called; its deadline was ${scenario.deadlineMs} ms",
            )
            throw stillWaiting
        }
        return result to (System.nanoTime() - startedNs) / 1_000_000L
    }

    private fun awaitSessionLog(fragment: String) {
        val untilNs = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < untilNs) {
            if (ShadowLog.getLogsForTag(SESSION_TAG).any { it.msg.contains(fragment) }) return
            Thread.sleep(10L)
        }
        val seen = ShadowLog.getLogsForTag(SESSION_TAG).map { it.msg }
        fail("no $SESSION_TAG line containing \"$fragment\"; saw $seen")
    }

    private fun connectedSession(
        api: IMimirTweaker,
        unbinds: AtomicInteger = AtomicInteger(),
    ): TweakerSession = TweakerSession(
        appContext = appContext,
        bindService = { _, connection, _ ->
            connection.onServiceConnected(COMPONENT, Binder())
            true
        },
        unbindService = { unbinds.incrementAndGet() },
        asInterface = { api },
        elapsedRealtimeMs = { FROZEN_NOW_MS },
    )

    private fun fakeDaemon(scenario: DeadlineCall, answer: (Array<out Any?>) -> Any?): IMimirTweaker {
        val binder = Binder()
        return Proxy.newProxyInstance(
            IMimirTweaker::class.java.classLoader,
            arrayOf(IMimirTweaker::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "getApiVersion" -> scenario.apiVersion
                scenario.method -> answer(args.orEmpty())
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as IMimirTweaker
    }

    /** One deadline-carrying call: how to make it, a reply its parser accepts, and how each ending reads. */
    private class DeadlineCall(
        val name: String,
        val apiVersion: Int,
        val method: String,
        val deadlineMs: Long,
        val reply: (Array<out Any?>) -> Bundle,
        val invoke: (TweakerSession) -> Any?,
        val acceptedLateReply: (Any?) -> Boolean,
        val timedOut: (Any?) -> Boolean,
    )

    private fun query() = DeadlineCall(
        name = "querySessionV4",
        apiVersion = 4,
        method = "querySessionV4",
        deadlineMs = CALL_DEADLINE_MS,
        reply = { args -> stockQueryBundle(args[0] as String, args[1] as Long) },
        invoke = { session -> session.querySessionV4(4, CALL_DEADLINE_MS) },
        acceptedLateReply = { it is TweakerV4SessionAssessment.Stock },
        timedOut = { (it as? TweakerV4SessionAssessment.Uncertain)?.reason?.contains(TIMED_OUT) == true },
    )

    private fun systemSelection() = DeadlineCall(
        name = "selectSystemProfileV5",
        apiVersion = 5,
        method = "selectSystemProfileV5",
        deadlineMs = CALL_DEADLINE_MS,
        reply = { args -> systemStockBundle(args[0] as String, args[1] as Long) },
        invoke = { session ->
            val attempt = requireNotNull(session.createSystemSelectionAttemptV5("stock", CALL_DEADLINE_MS))
            runBlocking { session.selectSystemProfileV5(5, attempt) }
        },
        acceptedLateReply = { it is TweakerV5SystemSelectionAssessment.Stock },
        timedOut = { (it as? TweakerV5SystemSelectionAssessment.Uncertain)?.reason?.contains(TIMED_OUT) == true },
    )

    private fun v4Apply() = v4Operation("applyProfileV4") { session ->
        val attempt = requireNotNull(session.createApplyAttemptV4("balanced", V4_PACKAGE, CALL_DEADLINE_MS))
        runBlocking { session.applyProfileV4(4, attempt) }
    }

    private fun v4CancelInFlight() = v4Operation("cancelRequestV4") { session ->
        val attempt = requireNotNull(session.createApplyAttemptV4("balanced", V4_PACKAGE, 60_000L))
        runBlocking { session.cancelInFlightV4(4, attempt, CALL_DEADLINE_MS) }
    }

    private fun v4CancelActive() = v4Operation("cancelRequestV4") { session ->
        runBlocking { session.cancelActiveV4(4, OWNED, CALL_DEADLINE_MS) }
    }

    private fun v4Renew() = v4Operation("renewLeaseV4") { session ->
        runBlocking { session.renewLeaseV4(4, OWNED, CALL_DEADLINE_MS) }
    }

    private fun v4Restore() = v4Operation("restoreSessionV4") { session ->
        runBlocking { session.restoreSessionV4(4, OWNED, CALL_DEADLINE_MS) }
    }

    /** A structurally valid rejection is a reply every V4 operation parser accepts. */
    private fun v4Operation(method: String, invoke: (TweakerSession) -> Any?) = DeadlineCall(
        name = method,
        apiVersion = 4,
        method = method,
        deadlineMs = CALL_DEADLINE_MS,
        reply = { args -> rejectedOperationBundle(args[0] as String, args[1] as Long) },
        invoke = invoke,
        acceptedLateReply = { it is TweakerV4OperationAssessment.Rejected },
        timedOut = { (it as? TweakerV4OperationAssessment.Uncertain)?.reason?.contains(TIMED_OUT) == true },
    )

    private fun fanControl() = DeadlineCall(
        name = "setFanControlV6",
        apiVersion = 6,
        method = "setFanControlV6",
        deadlineMs = CALL_DEADLINE_MS,
        reply = { args -> fanUpdatedBundle(args) },
        invoke = { session ->
            val attempt = requireNotNull(
                session.createFanControlAttemptV6(FAN_CAPABILITY, TweakerFanMode.MANUAL, -40, CALL_DEADLINE_MS),
            )
            runBlocking { session.setFanControlV6(6, attempt) }
        },
        acceptedLateReply = { it is TweakerFanControlAssessment.Accepted },
        timedOut = { (it as? TweakerFanControlAssessment.Uncertain)?.reason?.contains(TIMED_OUT) == true },
    )

    private fun fpsApply() = DeadlineCall(
        name = "setFpsLimitV7",
        apiVersion = 7,
        method = "setFpsLimitV7",
        deadlineMs = CALL_DEADLINE_MS,
        reply = { args -> fpsAppliedBundle(args[0] as String, args[1] as Long, args[3] as String, args[4] as Int) },
        invoke = { session ->
            val attempt = requireNotNull(session.createFpsApplyAttemptV7(FPS_PACKAGE, 60, CALL_DEADLINE_MS))
            runBlocking { session.setFpsLimitV7(7, attempt) }
        },
        acceptedLateReply = { it is TweakerFpsApplyAssessment.Applied },
        // The daemon may have applied the cap after the client stopped waiting, so the debt stays owed.
        timedOut = {
            (it as? TweakerFpsApplyAssessment.Uncertain)?.let { uncertain ->
                uncertain.reason.contains(TIMED_OUT) && uncertain.restoreDebtRetained
            } == true
        },
    )

    private fun fpsRestore() = DeadlineCall(
        name = "restoreFpsLimitV7",
        apiVersion = 7,
        method = "restoreFpsLimitV7",
        deadlineMs = CALL_DEADLINE_MS,
        reply = { args -> fpsRestoredBundle(args[0] as String, args[1] as Long) },
        invoke = { session -> runBlocking { session.restoreFpsLimitV7(7, FPS_OWNER, CALL_DEADLINE_MS) } },
        acceptedLateReply = { it is TweakerFpsRestoreAssessment.Restored },
        timedOut = { (it as? TweakerFpsRestoreAssessment.Uncertain)?.reason?.contains(TIMED_OUT) == true },
    )

    private fun discoveredSessionRead() = DeadlineCall(
        name = "queryDiscoveredSessionV9",
        apiVersion = 9,
        method = "queryDiscoveredSessionV9",
        deadlineMs = TweakerSession.CAPABILITY_CALL_DEADLINE_MS,
        reply = {
            Bundle().apply {
                putInt("apiVersion", 9)
                putBoolean("success", true)
                putBoolean("held", false)
            }
        },
        invoke = { session -> session.queryDiscoveredSessionV9(9) },
        acceptedLateReply = { it == TweakerDiscoveredSessionRead.Absent },
        timedOut = { (it as? TweakerDiscoveredSessionRead.Unavailable)?.reason?.contains(TIMED_OUT) == true },
    )

    private fun discoveredCeilingRead() = DeadlineCall(
        name = "queryDiscoveredCeilingV10",
        apiVersion = 10,
        method = "queryDiscoveredCeilingV10",
        deadlineMs = TweakerSession.CAPABILITY_CALL_DEADLINE_MS,
        reply = {
            Bundle().apply {
                putInt("apiVersion", 10)
                putString("outcome", "NOT_HELD")
            }
        },
        invoke = { session -> session.queryDiscoveredCeilingV10(10) },
        acceptedLateReply = { it == TweakerDiscoveredCeilingRead.NotHeld },
        timedOut = { (it as? TweakerDiscoveredCeilingRead.Unavailable)?.reason?.contains(TIMED_OUT) == true },
    )

    private fun frameLimitQuery() = DeadlineCall(
        name = "queryFpsLimiterV7",
        apiVersion = 7,
        method = "queryFpsLimiterV7",
        deadlineMs = TweakerSession.CAPABILITY_CALL_DEADLINE_MS,
        reply = { Bundle() },
        invoke = { session -> session.queryFpsLimiterV7(7, FPS_PACKAGE) },
        acceptedLateReply = { false },
        timedOut = { (it as? TweakerFpsQueryAssessment.Uncertain)?.reason?.contains(TIMED_OUT) == true },
    )

    /** Stuck on purpose (R6-5E-01): its daemon answer blocks on the latch the test controls. */
    private fun advancedCapabilitiesRead() = DeadlineCall(
        name = "getAdvancedCapabilitiesV6",
        apiVersion = 10,
        method = "getAdvancedCapabilitiesV6",
        deadlineMs = CALL_DEADLINE_MS,
        reply = { Bundle() },
        invoke = { session -> session.getAdvancedCapabilitiesV6(10, CALL_DEADLINE_MS) },
        acceptedLateReply = { false },
        timedOut = { it == null },
    )

    private fun stockQueryBundle(requestId: String, deadline: Long): Bundle = Bundle().apply {
        putInt("apiVersion", 4)
        putBoolean("success", true)
        putBoolean("applied", false)
        putBoolean("restored", false)
        putString("code", "SESSION_QUERY")
        putString("callerRequestId", requestId)
        putLong("callerDeadlineElapsedRealtimeMs", deadline)
        putString("sessionState", "STOCK")
        putBoolean("mutationMayBeActive", false)
        putBoolean("leaseRenewable", false)
    }

    private fun systemStockBundle(requestId: String, deadline: Long): Bundle = Bundle().apply {
        putInt("apiVersion", 5)
        putBoolean("success", true)
        putBoolean("applied", false)
        putBoolean("restored", false)
        putString("code", "SYSTEM_STOCK_UNCHANGED")
        putString("message", "test result")
        putString("callerRequestId", requestId)
        putLong("callerDeadlineElapsedRealtimeMs", deadline)
        putString("sessionState", "STOCK")
        putBoolean("mutationMayBeActive", false)
        putBoolean("leaseRenewable", false)
        putString("sessionScope", "NONE")
    }

    private fun rejectedOperationBundle(requestId: String, deadline: Long): Bundle = Bundle().apply {
        putInt("apiVersion", 4)
        putBoolean("success", false)
        putBoolean("applied", false)
        putBoolean("restored", false)
        putString("code", "LEASE_POLICY_UNAVAILABLE")
        putString("message", "test result")
        putString("callerRequestId", requestId)
        putLong("callerDeadlineElapsedRealtimeMs", deadline)
        putString("sessionState", "STOCK")
        putBoolean("mutationMayBeActive", false)
        putBoolean("leaseRenewable", false)
    }

    private fun fanUpdatedBundle(args: Array<out Any?>): Bundle = Bundle().apply {
        putInt("apiVersion", 6)
        putString("callerRequestId", args[0] as String)
        putLong("callerDeadlineElapsedRealtimeMs", args[1] as Long)
        putBoolean("success", true)
        putString("code", "FAN_CONTROL_UPDATED")
        putString("message", "ok")
        putString("sessionState", "ACTIVE")
        putBoolean("mutationMayBeActive", true)
        putString("sessionScope", "SYSTEM")
        putString("requestId", args[3] as String)
        putString("ownerRequestId", args[3] as String)
        putString("profileId", "balanced")
        putLong("generation", args[4] as Long)
        putString("transactionId", args[5] as String)
        putString("fanControlMode", args[6] as String)
        putInt("fanCurveBias", args[7] as Int)
    }

    private fun fpsAppliedBundle(requestId: String, deadline: Long, packageName: String, fps: Int): Bundle =
        Bundle().apply {
            putInt("apiVersion", 7)
            putString("callerRequestId", requestId)
            putLong("callerDeadlineElapsedRealtimeMs", deadline)
            putBoolean("success", true)
            putString("code", "FPS_LIMIT_APPLIED")
            putString("message", "applied")
            putBoolean("mutationMayBeActive", true)
            putBoolean("restoreDebtRetained", true)
            putString("ownerRequestId", requestId)
            putLong("generation", 3L)
            putString("transactionId", "mimir.fps.tx.3")
            putString("packageName", packageName)
            putInt("targetFps", fps)
        }

    private fun fpsRestoredBundle(requestId: String, deadline: Long): Bundle = Bundle().apply {
        putInt("apiVersion", 7)
        putString("callerRequestId", requestId)
        putLong("callerDeadlineElapsedRealtimeMs", deadline)
        putBoolean("success", true)
        putString("code", "FPS_LIMIT_RESTORED")
        putString("message", "restored")
        putBoolean("mutationMayBeActive", false)
        putBoolean("restoreDebtRetained", false)
    }

    private companion object {
        const val SESSION_TAG = "TweakerSession"

        /** The session's clock never moves, so a late reply still reads as in time to every parser. */
        const val FROZEN_NOW_MS = 1_000L
        const val CALL_DEADLINE_MS = 300L
        const val BIND_TIMEOUT_MS = 500L

        /** A reply this far past the deadline is past [RETURN_SLACK_MS] too, so old code cannot pass by luck. */
        const val LATE_BY_MS = 1_500L
        const val RETURN_SLACK_MS = 900L
        const val EARLY_TOLERANCE_MS = 50L
        const val TEST_TIMEOUT_MS = 20_000L

        /** Hardcoded on purpose: a test that read the production cap could not notice the cap moving. */
        const val STUCK_CALL_CAP = 4

        const val TIMED_OUT = "timed out"
        const val REFUSED = "refused"
        const val V4_PACKAGE = "dev.legacy.eden_emulator"
        const val FPS_PACKAGE = "dev.eden.eden_emulator"

        val COMPONENT = ComponentName(TweakerSession.TWEAKER_PACKAGE, TweakerSession.TWEAKER_SERVICE)

        val OWNED = TweakerV4OwnedSession(
            requestId = "mimir.apply.test.7",
            generation = 17L,
            transactionId = "tx-17",
            requestDeadlineElapsedRealtimeMs = 6_000L,
            leaseDeadlineElapsedRealtimeMs = 8_000L,
            hardLeaseDeadlineElapsedRealtimeMs = 12_000L,
        )

        val FPS_OWNER = TweakerFpsOwner(
            requestId = "mimir.fps.test.1",
            generation = 3L,
            transactionId = "mimir.fps.tx.3",
            packageName = FPS_PACKAGE,
            targetFps = 60,
        )

        val FAN_CAPABILITY = TweakerManualFanCapability(
            modes = TweakerFanMode.entries.toSet(),
            curveBiasRange = -100..100,
            curveBiasStep = 10,
            curveSensorType = "GPU",
            currentMode = TweakerFanMode.AUTO,
            currentCurveBias = 0,
            ownerRequestId = "mimir.system.1",
            generation = 7L,
            transactionId = "tx-7",
            profileId = "balanced",
        )
    }
}
