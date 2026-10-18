package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchForegroundTimelineTest {
    @Test
    fun laterForegroundWithoutFinalBackgroundKeepsSpanUnbounded() {
        val timeline = LaunchForegroundTimeline()
            .recordForeground(1_000L)
            .recordBackground(1_100L)
            .recordForeground(4_500L)

        assertTrue(timeline.targetForegroundObserved)
        assertFalse(timeline.targetForegroundSpanBounded)
        assertEquals(4_000L, timeline.targetForegroundSpanMs(returnedAtMs = 5_000L))
        assertEquals("FOREGROUND", timeline.lastTargetEventType)
        assertFalse(observation(timeline).silentDeathProven)
    }

    @Test
    fun backgroundAfterLastForegroundBoundsSpan() {
        val timeline = LaunchForegroundTimeline()
            .recordForeground(1_000L)
            .recordBackground(1_100L)

        assertTrue(timeline.targetForegroundObserved)
        assertTrue(timeline.targetForegroundSpanBounded)
        assertEquals(100L, timeline.targetForegroundSpanMs(returnedAtMs = 5_000L))
        assertEquals("BACKGROUND", timeline.lastTargetEventType)
        assertTrue(observation(timeline).silentDeathProven)
    }

    private fun observation(timeline: LaunchForegroundTimeline) = LaunchForegroundObservation(
        targetPackage = "com.example.emulator",
        usageAccessGranted = true,
        singleScreenLaunchShape = true,
        foregroundObservable = true,
        targetForegroundObserved = timeline.targetForegroundObserved,
        firstForegroundAtMs = timeline.firstForegroundAtMs,
        lastForeignForegroundAtMs = timeline.lastForegroundAtMs,
        lastForeignBackgroundAtMs = timeline.lastBackgroundAtMs,
        targetForegroundSpanMs = timeline.targetForegroundSpanMs(returnedAtMs = 5_000L),
        targetForegroundSpanBounded = timeline.targetForegroundSpanBounded,
        lastTargetEventType = timeline.lastTargetEventType,
        windowStartMs = 500L,
        windowEndMs = 5_500L,
    )
}
