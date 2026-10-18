package com.gruning.mimir.rewrite

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import com.gruning.mimir.rewrite.core.tweaker.TweakerSession
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4Owner
import com.gruning.mimir.tweaker.IMimirTweaker
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Tweaker session reservation comes back when a daemon call runs past its deadline (round 5e).
 *
 * `TweakerBridge.initialize` takes the reservation as a generic owner and runs the process-start
 * confirmation under it; the reservation is released only when that confirmation's job ends. Its V4
 * query hands the daemon a deadline. Before round 5e nothing on the client stopped waiting at that
 * deadline, so a daemon that did not answer kept the job alive and the reservation held, and every
 * privileged row after it — the standing re-arm, a display-rate APPLY, a fan press — was refused.
 *
 * This runs the production chain end to end on the JVM — [TweakerV4LaunchLifecycle],
 * [AndroidTweakerV4OwnerPort], [TweakerV4Owner] and [TweakerSession] — with only the daemon faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class TweakerProcessStartDeadlineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stopScope() {
        scope.cancel()
    }

    @Test(timeout = 20_000L)
    fun `a process-start query the daemon never answers releases the Tweaker reservation at its deadline`() {
        val release = CountDownLatch(1)
        val rig = ProcessStartRig { args ->
            release.await()
            stockQueryBundle(args)
        }
        try {
            rig.start()

            assertTrue(
                "process-start confirmation still held the Tweaker reservation $RETURN_BUDGET_MS ms after it " +
                    "started; its query deadline was $QUERY_DEADLINE_MS ms",
                rig.finished.await(RETURN_BUDGET_MS, TimeUnit.MILLISECONDS),
            )
            rig.assertReleasedUnconfirmed()
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 20_000L)
    fun `a Stock reply that arrives after the process-start query deadline changes nothing`() {
        val answered = CountDownLatch(1)
        val rig = ProcessStartRig { args ->
            try {
                Thread.sleep(QUERY_DEADLINE_MS + LATE_BY_MS)
                stockQueryBundle(args)
            } finally {
                answered.countDown()
            }
        }
        rig.start()

        assertTrue(
            "process-start confirmation still held the Tweaker reservation $RETURN_BUDGET_MS ms after it " +
                "started; its query deadline was $QUERY_DEADLINE_MS ms",
            rig.finished.await(RETURN_BUDGET_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue(answered.await(QUERY_DEADLINE_MS + LATE_BY_MS + 2_000L, TimeUnit.MILLISECONDS))
        // The dropped reply's thread finishes after the fake returns; give it a moment before reading state.
        Thread.sleep(LATE_REPLY_SETTLE_MS)

        rig.assertReleasedUnconfirmed()
    }

    private inner class ProcessStartRig(answerQuery: (Array<out Any?>) -> Bundle) {
        private val unbinds = AtomicInteger()
        private val daemon = fakeDaemon(answerQuery)
        private val session = TweakerSession(
            appContext = RuntimeEnvironment.getApplication(),
            bindService = { _, connection, _ ->
                connection.onServiceConnected(COMPONENT, Binder())
                true
            },
            unbindService = { unbinds.incrementAndGet() },
            asInterface = { daemon },
            // Frozen: a late reply still reads as in time to the parser, so only the wait can refuse it.
            elapsedRealtimeMs = { FROZEN_NOW_MS },
        )
        private val reservation = TweakerSessionReservation()
        private val owner = AndroidTweakerV4OwnerPort(TweakerV4Owner(session))
        private val lifecycle = TweakerV4LaunchLifecycle(
            owner = owner,
            scope = scope,
            elapsedRealtimeMs = { FROZEN_NOW_MS },
            bindTimeoutMs = BIND_TIMEOUT_MS,
            queryTimeoutMs = QUERY_DEADLINE_MS,
            onTransportReleased = { reservation.endOwner() },
        )
        val finished = CountDownLatch(1)

        /** The order `TweakerBridge.initialize` uses: the generic owner first, then the confirmation under it. */
        fun start() {
            assertTrue(reservation.tryBeginOwner())
            assertTrue(lifecycle.confirmProcessStartOnce(validatePair = { true }) { finished.countDown() })
        }

        fun assertReleasedUnconfirmed() {
            assertFalse("a timed-out confirmation must not keep owning the transport", lifecycle.mayOwnTransport())
            assertEquals(
                "the confirmation ends unconfirmed, never on the daemon's late word",
                TweakerV4OwnerPortDisposition.UNCERTAIN,
                owner.currentOutcome().disposition,
            )
            assertTrue("the reservation must be free for the next privileged row", reservation.tryBeginObservation())
            reservation.endObservation()
            assertFalse("the confirmation's bind is released", session.isBound)
            assertEquals(1, unbinds.get())
        }
    }

    private fun fakeDaemon(answerQuery: (Array<out Any?>) -> Bundle): IMimirTweaker {
        val binder = Binder()
        return Proxy.newProxyInstance(
            IMimirTweaker::class.java.classLoader,
            arrayOf(IMimirTweaker::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "getApiVersion" -> 4
                "querySessionV4" -> answerQuery(args.orEmpty())
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as IMimirTweaker
    }

    private fun stockQueryBundle(args: Array<out Any?>): Bundle = Bundle().apply {
        putInt("apiVersion", 4)
        putBoolean("success", true)
        putBoolean("applied", false)
        putBoolean("restored", false)
        putString("code", "SESSION_QUERY")
        putString("callerRequestId", args[0] as String)
        putLong("callerDeadlineElapsedRealtimeMs", args[1] as Long)
        putString("sessionState", "STOCK")
        putBoolean("mutationMayBeActive", false)
        putBoolean("leaseRenewable", false)
    }

    private companion object {
        const val FROZEN_NOW_MS = 1_000L
        const val BIND_TIMEOUT_MS = 500L
        const val QUERY_DEADLINE_MS = 300L

        /** The fake binds at once, so the whole confirmation is the query's deadline plus scheduling slack. */
        const val RETURN_BUDGET_MS = QUERY_DEADLINE_MS + 900L

        /** Past [RETURN_BUDGET_MS] as well, so a client that waited for the reply cannot pass by luck. */
        const val LATE_BY_MS = 1_500L
        const val LATE_REPLY_SETTLE_MS = 150L

        val COMPONENT = ComponentName(TweakerSession.TWEAKER_PACKAGE, TweakerSession.TWEAKER_SERVICE)
    }
}
