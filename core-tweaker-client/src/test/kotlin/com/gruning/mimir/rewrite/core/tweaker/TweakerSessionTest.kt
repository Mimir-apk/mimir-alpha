package com.gruning.mimir.rewrite.core.tweaker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.tweaker.IMimirTweaker
import com.gruning.mimir.tweaker.TweakerServiceActions
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerSessionTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `bind returns false without unbind when bindService throws SecurityException`() = runTest {
        var unbindCount = 0
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, _, _ -> throw SecurityException("signature mismatch") },
            unbindService = { unbindCount++ },
        )

        assertFalse(session.bind())
        assertFalse(session.isBound)
        assertEquals(0, unbindCount)
    }

    @Test
    fun `bind returns false without unbind when bindService returns false`() = runTest {
        var unbindCount = 0
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, _, _ -> false },
            unbindService = { unbindCount++ },
        )

        assertFalse(session.bind())
        assertFalse(session.isBound)
        assertEquals(0, unbindCount)
    }

    @Test
    fun `bind returns false and unbinds once when service never connects`() = runBlocking {
        var unbindCount = 0
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, _, _ -> true },
            unbindService = { unbindCount++ },
        )

        assertFalse(session.bind(timeoutMs = 50L))
        assertFalse(session.isBound)
        assertEquals(1, unbindCount)
    }

    @Test
    fun `late onServiceConnected after timeout is ignored`() = runBlocking {
        var captured: ServiceConnection? = null
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, connection, _ ->
                captured = connection
                true
            },
            unbindService = { },
        )

        assertFalse(session.bind(timeoutMs = 50L))
        assertFalse(session.isBound)

        captured?.onServiceConnected(
            ComponentName(TweakerSession.TWEAKER_PACKAGE, TweakerSession.TWEAKER_SERVICE),
            null,
        )

        assertFalse(session.isBound)
    }

    @Test
    fun `successful request scope can unbind exactly once`() = runTest {
        var unbindCount = 0
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, connection, _ ->
                connection.onServiceConnected(
                    ComponentName(TweakerSession.TWEAKER_PACKAGE, TweakerSession.TWEAKER_SERVICE),
                    Binder(),
                )
                true
            },
            unbindService = { unbindCount++ },
        )

        assertTrue(session.bind())
        assertTrue(session.isBound)

        session.unbind()
        session.unbind()

        assertFalse(session.isBound)
        assertEquals(1, unbindCount)
    }

    @Test
    fun `v4 active start uses exact action without binding`() {
        var startedIntent: Intent? = null
        var bindCount = 0
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, _, _ ->
                bindCount++
                false
            },
            unbindService = { },
            startForegroundService = { intent ->
                startedIntent = intent
                intent.component
            },
        )

        assertTrue(session.beginActiveSessionV4())
        assertEquals(0, bindCount)
        assertEquals(TweakerServiceActions.ACTION_BEGIN_ACTIVE_SESSION_V4, startedIntent?.action)
        assertEquals(COMPONENT, startedIntent?.component)
        assertFalse(session.isBound)
    }

    @Test
    fun `lab owner validation start uses exact guarded contract`() {
        var startedIntent: Intent? = null
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, _, _ -> false },
            unbindService = { },
            startForegroundService = { intent ->
                startedIntent = intent
                intent.component
            },
        )

        assertTrue(session.beginLabProfileHold("quiet", "dev.legacy.eden_emulator"))
        assertEquals(TweakerServiceActions.ACTION_BEGIN_LAB_PROFILE_HOLD, startedIntent?.action)
        assertEquals(COMPONENT, startedIntent?.component)
        assertTrue(startedIntent?.getBooleanExtra(TweakerServiceActions.EXTRA_LAB_APPLY_HOLD, false) == true)
        assertTrue(startedIntent?.getBooleanExtra(TweakerServiceActions.EXTRA_LAB_OWNER_VALIDATION, false) == true)
        assertEquals("quiet", startedIntent?.getStringExtra(TweakerServiceActions.EXTRA_PROFILE_ID))
        assertEquals(
            "dev.legacy.eden_emulator",
            startedIntent?.getStringExtra(TweakerServiceActions.EXTRA_WORKLOAD_PACKAGE),
        )
        assertEquals(0, startedIntent?.getIntExtra(TweakerServiceActions.EXTRA_LAB_OBSERVE_SECONDS, -1))
    }

    @Test
    fun `foreground start denial fails closed without bind`() {
        var bindCount = 0
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, _, _ ->
                bindCount++
                false
            },
            unbindService = { },
            startForegroundService = { throw SecurityException("background start denied") },
        )

        assertFalse(session.beginActiveSessionV4())
        assertEquals(0, bindCount)
        assertFalse(session.isBound)
    }

    @Test
    fun `foreground start runtime failure fails closed without bind`() {
        var bindCount = 0
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, _, _ ->
                bindCount++
                false
            },
            unbindService = { },
            startForegroundService = { throw IllegalStateException("foreground start unavailable") },
        )

        assertFalse(session.beginActiveSessionV4())
        assertEquals(0, bindCount)
        assertFalse(session.isBound)
    }

    @Test
    fun `v4 query reuses owner token but refreshes request identity and deadline`() = runTest {
        val calls = mutableListOf<Triple<String, Long, IBinder>>()
        val api = fakeApi(
            apiVersion = 4,
            query = { requestId, deadline, token ->
                calls += Triple(requestId, deadline, token)
                stockQueryBundle(requestId, deadline)
            },
        )
        var now = 1_000L
        val session = connectedSession(api, elapsedRealtimeMs = { now })

        assertTrue(session.bind())
        assertEquals(4, session.getApiVersion())
        assertTrue(session.querySessionV4(4) is TweakerV4SessionAssessment.Stock)
        now = 2_000L
        assertTrue(session.querySessionV4(4) is TweakerV4SessionAssessment.Stock)

        assertEquals(2, calls.size)
        assertTrue(calls[0].first != calls[1].first)
        assertEquals(2_500L, calls[0].second)
        assertEquals(3_500L, calls[1].second)
        assertTrue(calls[0].third === calls[1].third)
    }

    @Test
    fun `api below v4 never invokes query transport`() = runTest {
        var queryCalls = 0
        val api = fakeApi(
            apiVersion = 3,
            query = { _, _, _ ->
                queryCalls++
                Bundle()
            },
        )
        val session = connectedSession(api)

        assertTrue(session.bind())
        assertEquals(3, session.getApiVersion())
        assertTrue(session.querySessionV4(3) is TweakerV4SessionAssessment.Uncertain)
        assertEquals(0, queryCalls)
    }

    @Test
    fun `v4 apply and in-flight cancel reuse owner identity and run concurrently`() = runBlocking {
        val applyEntered = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val calls = mutableListOf<V4Call>()
        val now = AtomicLong(1_000L)
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val api = fakeApi(
            apiVersion = 4,
            applyV4 = { requestId, deadline, token, profileId, packageName ->
                synchronized(calls) {
                    calls += V4Call("apply", requestId, deadline, token, -1L, "$profileId:$packageName")
                }
                applyEntered.countDown()
                assertTrue(releaseApply.await(2, TimeUnit.SECONDS))
                stockOperationBundle(
                    requestId = requestId,
                    deadline = deadline,
                    success = false,
                    code = "REQUEST_CANCELLED",
                )
            },
            cancelV4 = { requestId, deadline, token, generation, transactionId ->
                synchronized(calls) {
                    calls += V4Call("cancel", requestId, deadline, token, generation, transactionId)
                }
                cancelEntered.countDown()
                applyingCancelBundle(requestId, deadline)
            },
        )
        val session = connectedSession(
            api = api,
            elapsedRealtimeMs = now::get,
            rpcDispatcher = dispatcher,
        )

        try {
            assertTrue(session.bind())
            val attempt = requireNotNull(
                session.createApplyAttemptV4(
                    profileId = "balanced",
                    packageName = "dev.legacy.eden_emulator",
                    timeoutMs = 5_000L,
                ),
            )
            coroutineScope {
                val apply = async(dispatcher) { session.applyProfileV4(4, attempt) }
                assertTrue(applyEntered.await(2, TimeUnit.SECONDS))
                now.set(1_100L)
                val cancel = async(dispatcher) {
                    session.cancelInFlightV4(4, attempt, timeoutMs = 2_500L)
                }
                assertTrue(cancelEntered.await(2, TimeUnit.SECONDS))
                releaseApply.countDown()

                assertTrue(cancel.await() is TweakerV4OperationAssessment.Accepted)
                assertTrue(apply.await() is TweakerV4OperationAssessment.Rejected)
            }

            val captured = synchronized(calls) { calls.toList() }
            assertEquals(listOf("apply", "cancel"), captured.map { it.name })
            assertEquals(attempt.requestId, captured[0].requestId)
            assertEquals(attempt.requestId, captured[1].requestId)
            assertTrue(captured[0].token === captured[1].token)
            assertEquals(0L, captured[1].generation)
            assertEquals("", captured[1].transactionId)
            assertEquals(6_000L, captured[0].deadline)
            assertEquals(3_600L, captured[1].deadline)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `v4 transport does not fall back to legacy mutation calls`() = runTest {
        var legacyApplyCalls = 0
        var legacyRestoreCalls = 0
        var v4ApplyCalls = 0
        val api = fakeApi(
            apiVersion = 4,
            applyV4 = { requestId, deadline, _, _, _ ->
                v4ApplyCalls++
                stockOperationBundle(
                    requestId = requestId,
                    deadline = deadline,
                    success = false,
                    code = "LEASE_POLICY_UNAVAILABLE",
                )
            },
            legacyApply = {
                legacyApplyCalls++
                Bundle()
            },
            legacyRestore = {
                legacyRestoreCalls++
                Bundle()
            },
        )
        val session = connectedSession(api)

        assertTrue(session.bind())
        val attempt = requireNotNull(
            session.createApplyAttemptV4(
                "quiet",
                "dev.legacy.eden_emulator",
                timeoutMs = 2_500L,
            ),
        )
        assertTrue(session.applyProfileV4(4, attempt) is TweakerV4OperationAssessment.Rejected)
        assertEquals(1, v4ApplyCalls)
        assertEquals(0, legacyApplyCalls)
        assertEquals(0, legacyRestoreCalls)
    }

    @Test
    fun `owned controls reuse exact identity with newly computed deadlines`() = runTest {
        val calls = mutableListOf<V4Call>()
        val now = AtomicLong(2_000L)
        val owner = TweakerV4OwnedSession(
            requestId = "mimir.apply.test.7",
            generation = 17L,
            transactionId = "tx-17",
            requestDeadlineElapsedRealtimeMs = 6_000L,
            leaseDeadlineElapsedRealtimeMs = 8_000L,
            hardLeaseDeadlineElapsedRealtimeMs = 12_000L,
        )
        val api = fakeApi(
            apiVersion = 4,
            renewV4 = { requestId, deadline, token, generation, transactionId ->
                calls += V4Call("renew", requestId, deadline, token, generation, transactionId)
                activeOperationBundle(
                    requestId = requestId,
                    callerDeadline = deadline,
                    owner = TweakerV4OwnedSession(
                        requestId = owner.requestId,
                        generation = owner.generation,
                        transactionId = owner.transactionId,
                        requestDeadlineElapsedRealtimeMs = owner.requestDeadlineElapsedRealtimeMs,
                        leaseDeadlineElapsedRealtimeMs = 9_000L,
                        hardLeaseDeadlineElapsedRealtimeMs = owner.hardLeaseDeadlineElapsedRealtimeMs,
                    ),
                    code = "LEASE_RENEWED",
                )
            },
            restoreV4 = { requestId, deadline, token, generation, transactionId ->
                calls += V4Call("restore", requestId, deadline, token, generation, transactionId)
                stockOperationBundle(
                    requestId = requestId,
                    deadline = deadline,
                    success = true,
                    code = "RESTORED",
                    restored = true,
                )
            },
        )
        val session = connectedSession(api, elapsedRealtimeMs = now::get)

        assertTrue(session.bind())
        val renew = session.renewLeaseV4(4, owner, timeoutMs = 1_500L)
        now.set(2_100L)
        val restore = session.restoreSessionV4(
            negotiatedApiVersion = 4,
            owner = requireNotNull((renew as TweakerV4OperationAssessment.Accepted).owner),
            timeoutMs = 1_500L,
        )

        assertTrue(restore is TweakerV4OperationAssessment.Accepted)
        assertEquals(listOf("renew", "restore"), calls.map { it.name })
        calls.forEach { call ->
            assertEquals(owner.requestId, call.requestId)
            assertEquals(owner.generation, call.generation)
            assertEquals(owner.transactionId, call.transactionId)
        }
        assertTrue(calls[0].token === calls[1].token)
        assertEquals(3_500L, calls[0].deadline)
        assertEquals(3_600L, calls[1].deadline)
    }

    @Test
    fun `fan session carries prior mode profile and retains mode 6 identity`() = runTest {
        val now = AtomicLong(1_000L)
        val capability = TweakerManualFanCapability(
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
        val api = fakeApi(
            apiVersion = 6,
            fanControlV6 = {
                    callerRequestId,
                    deadline,
                    _,
                    ownerRequestId,
                    generation,
                    transactionId,
                    mode,
                    curveBias,
                ->
                Bundle().apply {
                    putInt("apiVersion", 6)
                    putString("callerRequestId", callerRequestId)
                    putLong("callerDeadlineElapsedRealtimeMs", deadline)
                    putBoolean("success", true)
                    putString("code", "FAN_CONTROL_UPDATED")
                    putString("message", "ok")
                    putString("sessionState", "ACTIVE")
                    putBoolean("mutationMayBeActive", true)
                    putString("sessionScope", "SYSTEM")
                    putString("requestId", ownerRequestId)
                    putString("ownerRequestId", ownerRequestId)
                    putString("profileId", "balanced")
                    putLong("generation", generation)
                    putString("transactionId", transactionId)
                    putString("fanControlMode", mode)
                    putInt("fanCurveBias", curveBias)
                }
            },
        )
        val session = connectedSession(api, elapsedRealtimeMs = now::get)

        assertTrue(session.bind())
        val attempt = requireNotNull(
            session.createFanControlAttemptV6(
                capability = capability,
                mode = TweakerFanMode.MANUAL,
                curveBias = -40,
                timeoutMs = 5_000L,
            ),
        )

        assertEquals(TweakerFanMode.AUTO, attempt.priorMode)
        assertEquals("balanced", attempt.profileId)
        assertEquals(6_000L, attempt.requestDeadlineElapsedRealtimeMs)
        assertEquals(
            TweakerFanControlAssessment.Accepted(true, TweakerFanMode.MANUAL, -40),
            session.setFanControlV6(6, attempt),
        )
    }

    @Test
    fun `telemetry read returns a bounded failure instead of blocking on a hung daemon`() = runTest {
        val hungCallEntered = CountDownLatch(1)
        val releaseHungCall = CountDownLatch(1)
        val api = fakeApi(
            apiVersion = 7,
            telemetry = {
                hungCallEntered.countDown()
                // Simulates a daemon that never returns the Binder transaction.
                releaseHungCall.await()
                Bundle()
            },
        )
        val session = connectedSession(api)
        try {
            assertTrue(session.bind())

            val startedAtNs = System.nanoTime()
            assertNull(session.getTelemetry(deadlineMs = 250L))
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            assertTrue("expected a bounded return, took ${elapsedMs}ms", elapsedMs < 5_000L)
            assertTrue(hungCallEntered.await(2, TimeUnit.SECONDS))

            // While the single transport thread is still hung, the next call fails fast rather
            // than queueing behind a transaction that may never return.
            val retryStartedAtNs = System.nanoTime()
            assertNull(session.getTelemetry(deadlineMs = 2_500L))
            assertTrue((System.nanoTime() - retryStartedAtNs) / 1_000_000L < 1_000L)
        } finally {
            releaseHungCall.countDown()
        }
    }

    @Test
    fun `telemetry read still parses a healthy daemon bundle through the bounded path`() = runTest {
        val api = fakeApi(
            apiVersion = 7,
            telemetry = {
                Bundle().apply {
                    putLong("sampledAtElapsedRealtimeMs", 4_242L)
                    putString("foregroundPackageName", "org.ppsspp.ppsspp")
                    putString("frameStatus", "AVAILABLE")
                    putInt("frameAverageFpsMilli", 59_940)
                    putInt("frameCount", 120)
                    putInt("frameWindowDurationMs", 2_000)
                    putInt("frameLatestPresentAgeMs", 33)
                }
            },
        )
        val session = connectedSession(api)
        assertTrue(session.bind())

        val telemetry = session.getTelemetry()

        assertEquals(4_242L, telemetry?.sampledAtElapsedRealtimeMs)
        assertEquals("org.ppsspp.ppsspp", telemetry?.foregroundPackageName)
        assertEquals(59_940, telemetry?.frameAverageFpsMilli)
        assertEquals(120, telemetry?.frameCount)
        assertEquals(2_000, telemetry?.frameWindowDurationMs)
        assertEquals(33, telemetry?.frameLatestPresentAgeMs)
    }

    // --- getApiVersion / getCapabilities / getAdvancedCapabilitiesV6 deadline discipline ---
    //
    // Before this pass these three were plain synchronous AIDL calls with no deadline -- the only
    // ones on this transport without one -- so a wedge on any of them froze the whole bundled
    // Tweaker rail at last-known values with nothing in logcat to explain why. Mirrors the existing
    // `telemetry read returns a bounded failure instead of blocking on a hung daemon` differential
    // above. Each `@Test(timeout = ...)` is a safety net: on unpatched (pre-change) code these hang
    // scenarios do not fail with a message, they hang the test itself -- reverting only the
    // production change to confirm this would block the test JVM rather than print a failure
    // string, which is why the timeout annotation is load-bearing here and not decorative.

    @Test(timeout = 8_000L)
    fun `apiVersion read returns a bounded failure instead of blocking on a hung daemon`() = runTest {
        val hungCallEntered = CountDownLatch(1)
        val releaseHungCall = CountDownLatch(1)
        val api = fakeApi(
            apiVersion = 4,
            apiVersionCall = {
                hungCallEntered.countDown()
                // Simulates a daemon that never returns the Binder transaction.
                releaseHungCall.await()
                4
            },
        )
        val session = connectedSession(api)
        try {
            assertTrue(session.bind())

            val startedAtNs = System.nanoTime()
            assertNull(session.getApiVersion(deadlineMs = 250L))
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            assertTrue("expected a bounded return, took ${elapsedMs}ms", elapsedMs < 5_000L)
            assertTrue(hungCallEntered.await(2, TimeUnit.SECONDS))

            // R6-5E-01 correction: getApiVersion has its own dedicated one-thread executor
            // ([TweakerSession.apiVersionCallExecutor]), not the shared capability pool and not the
            // multi-thread deadline pool either -- so a stuck getApiVersion call can only ever be filled
            // by ANOTHER getApiVersion call, and the next one fails fast rather than queueing behind a
            // transaction that may never return.
            val retryStartedAtNs = System.nanoTime()
            assertNull(session.getApiVersion(deadlineMs = 2_500L))
            assertTrue((System.nanoTime() - retryStartedAtNs) / 1_000_000L < 1_000L)
        } finally {
            releaseHungCall.countDown()
        }
    }

    @Test
    fun `apiVersion read returns null without blocking when the underlying call throws`() = runTest {
        val api = fakeApi(
            apiVersion = 4,
            apiVersionCall = { throw IllegalStateException("apiVersion daemon crash") },
        )
        val session = connectedSession(api)
        assertTrue(session.bind())

        assertNull(session.getApiVersion())
    }

    @Test
    fun `apiVersion read returns null for a non-positive value without treating it as a crash`() = runTest {
        val api = fakeApi(apiVersion = 4, apiVersionCall = { 0 })
        val session = connectedSession(api)
        assertTrue(session.bind())

        assertNull(session.getApiVersion())
    }

    @Test(timeout = 8_000L)
    fun `capabilities read returns a bounded failure instead of blocking on a hung daemon`() = runTest {
        val hungCallEntered = CountDownLatch(1)
        val releaseHungCall = CountDownLatch(1)
        val api = fakeApi(
            apiVersion = 4,
            capabilities = {
                hungCallEntered.countDown()
                releaseHungCall.await()
                Bundle()
            },
        )
        val session = connectedSession(api)
        try {
            assertTrue(session.bind())

            val startedAtNs = System.nanoTime()
            assertNull(session.getCapabilities(deadlineMs = 250L))
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            assertTrue("expected a bounded return, took ${elapsedMs}ms", elapsedMs < 5_000L)
            assertTrue(hungCallEntered.await(2, TimeUnit.SECONDS))

            val retryStartedAtNs = System.nanoTime()
            assertNull(session.getCapabilities(deadlineMs = 2_500L))
            assertTrue((System.nanoTime() - retryStartedAtNs) / 1_000_000L < 1_000L)
        } finally {
            releaseHungCall.countDown()
        }
    }

    @Test
    fun `capabilities read returns null without blocking when the underlying call throws`() = runTest {
        val api = fakeApi(
            apiVersion = 4,
            capabilities = { throw IllegalStateException("capabilities daemon crash") },
        )
        val session = connectedSession(api)
        assertTrue(session.bind())

        assertNull(session.getCapabilities())
    }

    @Test
    fun `capabilities read distinguishes a clean null return from an empty-but-present bundle`() = runTest {
        val api = fakeApi(apiVersion = 4, capabilities = { null })
        val session = connectedSession(api)
        assertTrue(session.bind())

        // A genuinely null AIDL return is reported as null...
        assertNull(session.getCapabilities())
    }

    @Test
    fun `capabilities read still parses a healthy daemon bundle through the bounded path`() = runTest {
        val api = fakeApi(
            apiVersion = 4,
            capabilities = {
                Bundle().apply {
                    putString("status", "SUPPORTED")
                    putString("posture", "PRODUCTION")
                    putStringArrayList("notes", arrayListOf("ok"))
                }
            },
        )
        val session = connectedSession(api)
        assertTrue(session.bind())

        val capabilities = session.getCapabilities()

        assertEquals(TweakerCapabilityStatus.SUPPORTED, capabilities?.status)
        assertEquals(TweakerPosture.PRODUCTION, capabilities?.posture)
        assertEquals(listOf("ok"), capabilities?.notes)
    }

    @Test(timeout = 8_000L)
    fun `advancedCapabilitiesV6 read returns a bounded failure instead of blocking on a hung daemon`() =
        runTest {
            val hungCallEntered = CountDownLatch(1)
            val releaseHungCall = CountDownLatch(1)
            val api = fakeApi(
                apiVersion = 6,
                advancedCapabilitiesV6 = {
                    hungCallEntered.countDown()
                    releaseHungCall.await()
                    Bundle()
                },
            )
            val session = connectedSession(api)
            try {
                assertTrue(session.bind())

                val startedAtNs = System.nanoTime()
                assertNull(session.getAdvancedCapabilitiesV6(6, deadlineMs = 250L))
                val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                assertTrue("expected a bounded return, took ${elapsedMs}ms", elapsedMs < 5_000L)
                assertTrue(hungCallEntered.await(2, TimeUnit.SECONDS))

                val retryStartedAtNs = System.nanoTime()
                assertNull(session.getAdvancedCapabilitiesV6(6, deadlineMs = 2_500L))
                assertTrue((System.nanoTime() - retryStartedAtNs) / 1_000_000L < 1_000L)
            } finally {
                releaseHungCall.countDown()
            }
        }

    @Test
    fun `advancedCapabilitiesV6 read returns null without blocking when the underlying call throws`() =
        runTest {
            val api = fakeApi(
                apiVersion = 6,
                advancedCapabilitiesV6 = { throw IllegalStateException("advancedCapabilitiesV6 daemon crash") },
            )
            val session = connectedSession(api)
            assertTrue(session.bind())

            assertNull(session.getAdvancedCapabilitiesV6(6))
        }

    @Test
    fun `advancedCapabilitiesV6 read distinguishes a clean null return from an empty-but-present bundle`() =
        runTest {
            val api = fakeApi(apiVersion = 6, advancedCapabilitiesV6 = { null })
            val session = connectedSession(api)
            assertTrue(session.bind())

            // A genuinely null AIDL return is reported as null. An empty-but-present Bundle
            // instead parses to a non-null, defaulted TweakerAdvancedCapabilities -- see
            // TweakerAdvancedControlsBundleContractTest's "collapses rather than half-renders"
            // case. The two are NOT distinguishable from the return value alone; only the
            // outcome= log line tells them apart.
            assertNull(session.getAdvancedCapabilitiesV6(6))
        }

    @Test
    fun `advancedCapabilitiesV6 read still parses a healthy daemon bundle through the bounded path`() =
        runTest {
            val api = fakeApi(
                apiVersion = 6,
                advancedCapabilitiesV6 = {
                    Bundle().apply {
                        putInt("apiVersion", 6)
                        putIntegerArrayList("frameLimitsHz", arrayListOf(30, 60))
                        putBoolean("perGameOverridesAvailable", true)
                        putInt("fanControlContractVersion", 2)
                        putBoolean("manualFanControlAvailable", false)
                    }
                },
            )
            val session = connectedSession(api)
            assertTrue(session.bind())

            val advanced = session.getAdvancedCapabilitiesV6(6)

            assertEquals(listOf(30, 60), advanced?.frameLimitsHz)
            assertTrue(advanced?.perGameOverridesAvailable == true)
            assertEquals(null, advanced?.manualFan)
        }

    @Test
    fun `binding death clears transport and unbinds exactly once`() = runTest {
        var connection: ServiceConnection? = null
        var unbindCount = 0
        val api = fakeApi(apiVersion = 4)
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, created, _ ->
                connection = created
                created.onServiceConnected(COMPONENT, Binder())
                true
            },
            unbindService = { unbindCount++ },
            asInterface = { api },
        )

        assertTrue(session.bind())
        connection?.onBindingDied(COMPONENT)

        assertFalse(session.isBound)
        assertEquals(1, unbindCount)
        session.unbind()
        assertEquals(1, unbindCount)
    }

    @Test
    fun `synchronous null binding is released and cannot become bound`() = runTest {
        var unbindCount = 0
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, connection, _ ->
                connection.onNullBinding(COMPONENT)
                true
            },
            unbindService = { unbindCount++ },
        )

        assertFalse(session.bind())
        assertFalse(session.isBound)
        assertEquals(1, unbindCount)
    }

    @Test
    fun `stale connected callback cannot republish transport after unbind`() = runBlocking {
        val bindEntered = CountDownLatch(1)
        val interfaceEntered = CountDownLatch(1)
        val releaseInterface = CountDownLatch(1)
        var captured: ServiceConnection? = null
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val api = fakeApi(apiVersion = 4)
        val session = TweakerSession(
            appContext = appContext,
            bindService = { _, connection, _ ->
                captured = connection
                bindEntered.countDown()
                true
            },
            unbindService = { },
            asInterface = {
                interfaceEntered.countDown()
                assertTrue(releaseInterface.await(2, TimeUnit.SECONDS))
                api
            },
            rpcDispatcher = dispatcher,
        )

        try {
            coroutineScope {
                val binding = async(dispatcher) { session.bind(timeoutMs = 300L) }
                assertTrue(bindEntered.await(2, TimeUnit.SECONDS))
                val staleCallback = async(dispatcher) {
                    captured?.onServiceConnected(COMPONENT, Binder())
                }
                assertTrue(interfaceEntered.await(2, TimeUnit.SECONDS))

                session.unbind()
                releaseInterface.countDown()
                staleCallback.await()

                assertFalse(session.isBound)
                assertFalse(binding.await())
                assertFalse(session.isBound)
            }
        } finally {
            dispatcher.close()
        }
    }

    private fun connectedSession(
        api: IMimirTweaker,
        elapsedRealtimeMs: () -> Long = { 1_000L },
        rpcDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            kotlinx.coroutines.Dispatchers.IO,
    ): TweakerSession = TweakerSession(
        appContext = appContext,
        bindService = { _, connection, _ ->
            connection.onServiceConnected(COMPONENT, Binder())
            true
        },
        unbindService = { },
        asInterface = { api },
        elapsedRealtimeMs = elapsedRealtimeMs,
        rpcDispatcher = rpcDispatcher,
    )

    private fun fakeApi(
        apiVersion: Int,
        query: (String, Long, IBinder) -> Bundle = { requestId, deadline, _ ->
            stockQueryBundle(requestId, deadline)
        },
        applyV4: (String, Long, IBinder, String, String) -> Bundle =
            { requestId, deadline, _, _, _ ->
                stockOperationBundle(requestId, deadline, false, "NOT_CONFIGURED")
            },
        cancelV4: (String, Long, IBinder, Long, String) -> Bundle =
            { requestId, deadline, _, _, _ ->
                stockOperationBundle(requestId, deadline, false, "NOT_CONFIGURED")
            },
        renewV4: (String, Long, IBinder, Long, String) -> Bundle =
            { requestId, deadline, _, _, _ ->
                stockOperationBundle(requestId, deadline, false, "NOT_CONFIGURED")
            },
        restoreV4: (String, Long, IBinder, Long, String) -> Bundle =
            { requestId, deadline, _, _, _ ->
                stockOperationBundle(requestId, deadline, false, "NOT_CONFIGURED")
            },
        fanControlV6: (
            String,
            Long,
            IBinder,
            String,
            Long,
            String,
            String,
            Int,
        ) -> Bundle = { _, _, _, _, _, _, _, _ -> Bundle() },
        legacyApply: () -> Bundle = { Bundle() },
        legacyRestore: () -> Bundle = { Bundle() },
        telemetry: () -> Bundle = { Bundle() },
        apiVersionCall: () -> Int = { apiVersion },
        capabilities: () -> Bundle? = { Bundle() },
        advancedCapabilitiesV6: () -> Bundle? = { Bundle() },
    ): IMimirTweaker {
        val binder = Binder()
        return Proxy.newProxyInstance(
            IMimirTweaker::class.java.classLoader,
            arrayOf(IMimirTweaker::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "getApiVersion" -> apiVersionCall()
                "getCapabilities" -> capabilities()
                "getAdvancedCapabilitiesV6" -> advancedCapabilitiesV6()
                "getTelemetry" -> telemetry()
                "querySessionV4" -> query(
                    args?.get(0) as String,
                    args[1] as Long,
                    args[2] as IBinder,
                )
                "applyProfileV4" -> applyV4(
                    args?.get(0) as String,
                    args[1] as Long,
                    args[2] as IBinder,
                    args[3] as String,
                    args[4] as String,
                )
                "cancelRequestV4" -> cancelV4(
                    args?.get(0) as String,
                    args[1] as Long,
                    args[2] as IBinder,
                    args[3] as Long,
                    args[4] as String,
                )
                "renewLeaseV4" -> renewV4(
                    args?.get(0) as String,
                    args[1] as Long,
                    args[2] as IBinder,
                    args[3] as Long,
                    args[4] as String,
                )
                "restoreSessionV4" -> restoreV4(
                    args?.get(0) as String,
                    args[1] as Long,
                    args[2] as IBinder,
                    args[3] as Long,
                    args[4] as String,
                )
                "setFanControlV6" -> fanControlV6(
                    args?.get(0) as String,
                    args[1] as Long,
                    args[2] as IBinder,
                    args[3] as String,
                    args[4] as Long,
                    args[5] as String,
                    args[6] as String,
                    args[7] as Int,
                )
                "applyProfile" -> legacyApply()
                "restore" -> legacyRestore()
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    Bundle::class.java -> Bundle()
                    else -> null
                }
            }
        } as IMimirTweaker
    }

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

    private fun stockOperationBundle(
        requestId: String,
        deadline: Long,
        success: Boolean,
        code: String,
        restored: Boolean = false,
    ): Bundle = Bundle().apply {
        putInt("apiVersion", 4)
        putBoolean("success", success)
        putBoolean("applied", false)
        putBoolean("restored", restored)
        putString("code", code)
        putString("message", "test result")
        putString("callerRequestId", requestId)
        putLong("callerDeadlineElapsedRealtimeMs", deadline)
        putString("sessionState", "STOCK")
        putBoolean("mutationMayBeActive", false)
        putBoolean("leaseRenewable", false)
    }

    private fun applyingCancelBundle(requestId: String, deadline: Long): Bundle =
        stockOperationBundle(requestId, deadline, true, "CANCEL_REQUESTED").apply {
            putString("sessionState", "APPLYING")
            putString("requestId", requestId)
        }

    private fun activeOperationBundle(
        requestId: String,
        callerDeadline: Long,
        owner: TweakerV4OwnedSession,
        code: String,
    ): Bundle = stockOperationBundle(
        requestId = requestId,
        deadline = callerDeadline,
        success = true,
        code = code,
    ).apply {
        putString("sessionState", "ACTIVE")
        putBoolean("mutationMayBeActive", true)
        putString("requestId", owner.requestId)
        putLong("generation", owner.generation)
        putString("transactionId", owner.transactionId)
        putLong("requestDeadlineElapsedRealtimeMs", owner.requestDeadlineElapsedRealtimeMs)
        putLong("leaseDeadlineElapsedRealtimeMs", owner.leaseDeadlineElapsedRealtimeMs)
        putLong("hardLeaseDeadlineElapsedRealtimeMs", owner.hardLeaseDeadlineElapsedRealtimeMs)
        putBoolean("leaseRenewable", true)
    }

    private data class V4Call(
        val name: String,
        val requestId: String,
        val deadline: Long,
        val token: IBinder,
        val generation: Long,
        val transactionId: String,
    )

    private companion object {
        val COMPONENT = ComponentName(TweakerSession.TWEAKER_PACKAGE, TweakerSession.TWEAKER_SERVICE)
    }
}
