package com.gruning.mimir.rewrite

import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Lane mem (2026-10-14): the decision behind a low-memory recovery start, and what the deferral does with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MimirLowMemoryRecoveryTest {
    private val now = 1_000_000L
    private val lean = MimirLowMemoryRecovery.Posture(lowMemoryExitAgeMs = 1_500L)
    private val normal = MimirLowMemoryRecovery.Posture(lowMemoryExitAgeMs = null)
    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MimirLowMemoryRecovery.resetForTest()
    }

    @After
    fun tearDown() {
        MimirLowMemoryRecovery.resetForTest()
    }

    @Test
    fun `a low-memory exit seconds ago is a lean start`() {
        val posture = MimirLowMemoryRecovery.postureFor(
            MimirLowMemoryRecovery.LastExit(ApplicationExitInfo.REASON_LOW_MEMORY, timestampMs = now - 1_500L, rssKb = 278_056L),
            nowMs = now,
        )
        assertTrue(posture.lean)
        assertEquals(1_500L, posture.lowMemoryExitAgeMs)
    }

    @Test
    fun `the window's last millisecond is still a lean start, the next one is history`() {
        val edge = MimirLowMemoryRecovery.LastExit(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            timestampMs = now - MimirLowMemoryRecovery.RecoveryWindowMs,
            rssKb = 0L,
        )
        assertTrue(MimirLowMemoryRecovery.postureFor(edge, nowMs = now).lean)
        assertFalse(MimirLowMemoryRecovery.postureFor(edge, nowMs = now + 1L).lean)
    }

    @Test
    fun `any other exit, no record, or a record from the future is a normal start`() {
        assertFalse(MimirLowMemoryRecovery.postureFor(null, nowMs = now).lean)
        listOf(ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_SIGNALED)
            .forEach { reason ->
                assertFalse(
                    "reason $reason is not a crunch",
                    MimirLowMemoryRecovery.postureFor(
                        MimirLowMemoryRecovery.LastExit(reason, timestampMs = now - 10L, rssKb = 0L),
                        nowMs = now,
                    ).lean,
                )
            }
        assertFalse(
            MimirLowMemoryRecovery.postureFor(
                MimirLowMemoryRecovery.LastExit(ApplicationExitInfo.REASON_LOW_MEMORY, timestampMs = now + 5_000L, rssKb = 0L),
                nowMs = now,
            ).lean,
        )
    }

    @Test
    fun `the deferral is longer than the crunches measured and shorter than the platform's own sticky restart`() {
        // The two Thor loads of 2026-10-14 stayed under pressure for about 8 s and 5 s; the platform's first sticky
        // restart of the daemon in that log was 106 s out.
        assertTrue(MimirLowMemoryRecovery.TweakerBindDeferralMs >= 10_000L)
        assertTrue(MimirLowMemoryRecovery.TweakerBindDeferralMs <= 30_000L)
        assertTrue(MimirLowMemoryRecovery.RecoveryWindowMs > MimirLowMemoryRecovery.TweakerBindDeferralMs)
    }

    @Test
    fun `a normal start runs the bridge start inline, exactly once, and defers nothing`() {
        var starts = 0
        MimirLowMemoryRecovery.startTweakerBridge(context, normal, start = { starts++ })
        assertEquals(1, starts)
        assertFalse(MimirLowMemoryRecovery.tweakerBridgeStartDeferred())
        MimirLowMemoryRecovery.onMainActivityCreated()
        assertEquals("a later MainActivity has nothing to run", 1, starts)
    }

    @Test
    fun `a lean start defers the bridge start, and observation is refused meanwhile`() {
        var starts = 0
        MimirLowMemoryRecovery.startTweakerBridge(context, lean, start = { starts++ }, deferralMs = 60_000L)
        assertEquals("nothing binds at process start", 0, starts)
        assertTrue(MimirLowMemoryRecovery.tweakerBridgeStartDeferred())
        // The bridge's pure may-open rule says no while the start is deferred, whatever else is true...
        assertFalse(
            tweakerTelemetryObservationMayOpen(
                pairMayBind = true,
                hasPendingLaunch = false,
                mayOwnTransport = false,
                sessionBound = false,
                reservationIdle = true,
                bridgeStartDeferred = true,
            ),
        )
        assertTrue(
            "the same facts open once the deferral is over",
            tweakerTelemetryObservationMayOpen(
                pairMayBind = true,
                hasPendingLaunch = false,
                mayOwnTransport = false,
                sessionBound = false,
                reservationIdle = true,
                bridgeStartDeferred = false,
            ),
        )
        // ...so the overlay's kernel-read fallback keeps reading instead of yielding into a null observation.
        val fallback = discoveredTelemetryObservation(
            initial = null,
            read = { null },
            elapsedRealtimeMs = { 100_000L },
            tweakerMayOpen = { false },
            retryAfterMs = 0L,
        )
        assertFalse("no yield while the Tweaker path is closed", fallback.yieldRequested())
    }

    @Test
    fun `the MainActivity trigger runs the deferred start inline on the main thread, exactly once`() {
        var starts = 0
        var onMainThread = false
        MimirLowMemoryRecovery.startTweakerBridge(
            context,
            lean,
            start = {
                starts++
                onMainThread = Looper.myLooper() == Looper.getMainLooper()
            },
            deferralMs = 60_000L,
        )
        MimirLowMemoryRecovery.onMainActivityCreated()
        assertEquals("the start ran before onMainActivityCreated returned", 1, starts)
        assertTrue(onMainThread)
        assertFalse(MimirLowMemoryRecovery.tweakerBridgeStartDeferred())
        MimirLowMemoryRecovery.onMainActivityCreated()
        assertEquals("a second trigger does not start the bridge again", 1, starts)
    }

    @Test
    fun `the deferral timer runs the start once on the main looper, and a later MainActivity does not repeat it`() {
        var starts = 0
        var onMainThread = false
        MimirLowMemoryRecovery.startTweakerBridge(
            context,
            lean,
            start = {
                starts++
                onMainThread = Looper.myLooper() == Looper.getMainLooper()
            },
            deferralMs = 20L,
        )
        // The timer takes the deferred start on its own thread and hands it to the main looper; both steps are
        // asynchronous to this thread, so idle the looper until the start has run (or the deadline says it never did).
        val deadline = System.currentTimeMillis() + 10_000L
        while (starts == 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        assertFalse("the timer took the deferred start", MimirLowMemoryRecovery.tweakerBridgeStartDeferred())
        assertEquals("the start ran once, from the timer", 1, starts)
        assertTrue("on the main looper, not the timer's thread", onMainThread)
        MimirLowMemoryRecovery.onMainActivityCreated()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the MainActivity trigger finds nothing left to run", 1, starts)
    }
}
