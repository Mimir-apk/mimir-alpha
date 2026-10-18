package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchForegroundEvidenceTest {
    @Test
    fun usageAccessMissingNeverProducesSilentDeathProof() {
        val observation = observation(
            foregroundObservable = false,
            targetForegroundObserved = false,
            usageAccessGranted = false,
        )

        assertFalse(observation.silentDeathProven)
        assertEquals("", observation.silentDeathReason)
    }

    @Test
    fun dualScreenLaunchShapeIsExcludedFromSilentDeathProof() {
        val observation = observation(
            singleScreenLaunchShape = false,
            foregroundObservable = true,
            targetForegroundObserved = false,
        )

        assertFalse(observation.silentDeathProven)
        assertEquals("", observation.silentDeathReason)
    }

    @Test
    fun observableNeverForegroundedTargetIsSilentDeathProof() {
        val observation = observation(
            foregroundObservable = true,
            targetForegroundObserved = false,
        )

        assertTrue(observation.silentDeathProven)
        assertEquals("launch_never_foregrounded", observation.silentDeathReason)
    }

    @Test
    fun boundedShortForegroundSpanIsSilentDeathProof() {
        val observation = observation(
            foregroundObservable = true,
            targetForegroundObserved = true,
            targetForegroundSpanMs = 112L,
            targetForegroundSpanBounded = true,
        )

        assertTrue(observation.silentDeathProven)
        assertEquals("launch_foreground_span_under_threshold", observation.silentDeathReason)
    }

    @Test
    fun unboundedShortForegroundSpanIsNotSilentDeathProof() {
        val observation = observation(
            foregroundObservable = true,
            targetForegroundObserved = true,
            targetForegroundSpanMs = 112L,
            targetForegroundSpanBounded = false,
        )

        assertFalse(observation.silentDeathProven)
        assertEquals("", observation.silentDeathReason)
    }

    @Test
    fun boundedThresholdOrLongerSpanIsNotSilentDeathProof() {
        val observation = observation(
            foregroundObservable = true,
            targetForegroundObserved = true,
            targetForegroundSpanMs = LaunchForegroundEvidence.SILENT_FOREGROUND_SPAN_THRESHOLD_MS,
            targetForegroundSpanBounded = true,
        )

        assertFalse(observation.silentDeathProven)
        assertEquals("", observation.silentDeathReason)
    }

    private fun observation(
        usageAccessGranted: Boolean = true,
        singleScreenLaunchShape: Boolean = true,
        foregroundObservable: Boolean,
        targetForegroundObserved: Boolean,
        targetForegroundSpanMs: Long = 0L,
        targetForegroundSpanBounded: Boolean = false,
    ) = LaunchForegroundObservation(
        targetPackage = "com.example.emulator",
        usageAccessGranted = usageAccessGranted,
        singleScreenLaunchShape = singleScreenLaunchShape,
        foregroundObservable = foregroundObservable,
        targetForegroundObserved = targetForegroundObserved,
        targetForegroundSpanMs = targetForegroundSpanMs,
        targetForegroundSpanBounded = targetForegroundSpanBounded,
        windowStartMs = 1_000L,
        windowEndMs = 5_000L,
    )
}
