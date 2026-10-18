package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The performance card's CPU percentage
 * is this process's own share of the whole SoC, and its frame rate is Mimir's own window.
 *
 * Everything asserted here is pure — the samplers take their clock, their tick rate, their core count
 * and their file read as constructor arguments precisely so the maths can be pinned without a device.
 * What is NOT pinned here, and is not implied by a green test: that
 * `/proc/self/stat` and `FrameMetrics` behave on the Thor as the kernel and the framework document.
 */
class MimirProcessTelemetryTest {

    /**
     * Field 2 of `/proc/<pid>/stat` is `comm`, which is arbitrary bytes inside parentheses. A
     * whitespace split from the FRONT mis-indexes every field after it — and Android process names
     * routinely contain a colon, a dot, and (for a renderer or a webview) spaces and parens.
     */
    @Test
    fun `self stat is split after the last paren so a process name cannot shift the fields`() {
        val plain = "1234 (mimir) S 1 1234 0 0 -1 4194560 900 0 3 0 " +
            "1700 420 0 0 20 0 42 0 900 0 0 0"
        assertEquals(2_120L, mimirProcessCpuTicks(plain))

        val awkward = "1234 (a name (with) parens) S 1 1234 0 0 -1 4194560 900 0 3 0 " +
            "1700 420 0 0 20 0 42 0 900 0 0 0"
        assertEquals(2_120L, mimirProcessCpuTicks(awkward))

        assertNull(mimirProcessCpuTicks(null))
        assertNull(mimirProcessCpuTicks(""))
        assertNull(mimirProcessCpuTicks("1234 mimir S 1 1234"))
        assertNull(mimirProcessCpuTicks("1234 (mimir) S 1 1234"))
    }

    /**
     * **The denominator is the WHOLE SoC, and that is the decision this test exists to hold.**
     *
     * One second of CPU time on an eight-core chip over one wall-clock second is 12.5% of the device,
     * not 100% of a core. Per-core would let one process read `380%` — true in `top`'s dialect, and a
     * lie one separator away from a GPU cell that is a 0..100 busy fraction.
     */
    @Test
    fun `CPU share is a fraction of every core, not of one`() {
        assertEquals(
            12.5f,
            mimirProcessCpuPercent(
                deltaTicks = 100L,
                deltaMs = 1_000L,
                ticksPerSecond = 100L,
                coreCount = 8,
            )!!,
            0.001f,
        )
        // The Thor test device, measured: this process at ~0.31% of the chip over an 8 s window. Twenty
        // ticks of 100/s is 0.2 s of CPU against the 64 core-seconds an 8-core chip offers in 8 s.
        assertEquals(
            0.3125f,
            mimirProcessCpuPercent(
                deltaTicks = 20L,
                deltaMs = 8_000L,
                ticksPerSecond = 100L,
                coreCount = 8,
            )!!,
            0.001f,
        )
        assertNull(mimirProcessCpuPercent(100L, 0L, 100L, 8))
        assertNull(mimirProcessCpuPercent(-1L, 1_000L, 100L, 8))
        assertNull("a sysconf that failed must not be divided by", mimirProcessCpuPercent(100L, 1_000L, 0L, 8))
        assertNull("a sysconf that failed must not be divided by", mimirProcessCpuPercent(100L, 1_000L, 100L, 0))
    }

    /**
     * A rate needs two samples an interval apart. The first call seeds and returns null, the cell
     * shows "—" once, and nothing publishes a since-start average dressed as "now" — the same law
     * `MimirDiscoveredTelemetry`'s `/proc/stat` reader was written under before this replaced it.
     */
    @Test
    fun `the CPU sampler refuses to answer from one sample`() {
        var nowMs = 0L
        val reads = ArrayDeque(
            listOf(
                "1 (mimir) S 1 1 0 0 -1 0 0 0 0 0 1000 0 0 0 20 0 1 0 0",
                "1 (mimir) S 1 1 0 0 -1 0 0 0 0 0 1020 5 0 0 20 0 1 0 0",
            ),
        )
        val sampler = MimirProcessCpuSampler(
            ticksPerSecond = 100L,
            coreCount = 8,
            readSelfStat = { reads.removeFirstOrNull() },
            elapsedRealtimeMs = { nowMs },
        )

        assertNull("the first read is a baseline, never a reading", sampler.sample())
        nowMs = 1_000L
        // 25 ticks of 100/s over 1 s of an 8-core chip = 0.25 s busy / 8 s available = 3.125%.
        assertEquals(3.125f, sampler.sample()!!, 0.001f)
        assertNull("a read that fails is absent, not zero", sampler.sample())
    }

    /**
     * The loop skips reading entirely while the screen is off, so the gap between two environment
     * reads is not bounded by the beat. A percentage across a gap of hours is arithmetically correct
     * and describes nothing; the baseline is thrown away instead, and the cell says "—" for one beat.
     */
    @Test
    fun `a baseline older than the stale bound is discarded rather than divided`() {
        var nowMs = 0L
        val stat = { "1 (mimir) S 1 1 0 0 -1 0 0 0 0 0 1000 0 0 0 20 0 1 0 0" }
        val sampler = MimirProcessCpuSampler(
            ticksPerSecond = 100L,
            coreCount = 8,
            readSelfStat = stat,
            elapsedRealtimeMs = { nowMs },
            maxIntervalMs = MimirTelemetryStaleBaselineMs,
        )
        assertNull(sampler.sample())
        nowMs = MimirTelemetryStaleBaselineMs + 1L
        assertNull("a screen-off gap is not a longer average", sampler.sample())
        // The refused sample still re-seeded, so the next one inside the bound answers normally.
        nowMs += 1_000L
        assertEquals(0f, sampler.sample()!!, 0.001f)

        var frames = 0L
        val frameSampler = MimirFrameRateSampler(
            renderedFrames = { frames },
            elapsedRealtimeMs = { nowMs },
            maxIntervalMs = MimirTelemetryStaleBaselineMs,
        )
        assertNull(frameSampler.sample())
        nowMs += MimirTelemetryStaleBaselineMs + 1L
        frames = 60L
        assertNull(frameSampler.sample())
        nowMs += 1_000L
        frames = 120L
        assertEquals(60, frameSampler.sample())
    }

    @Test
    fun `frame rate is frames over wall clock and a stale baseline is dropped rather than divided`() {
        assertEquals(60, mimirFrameRate(deltaFrames = 60L, deltaMs = 1_000L))
        assertEquals(19, mimirFrameRate(deltaFrames = 19L, deltaMs = 1_000L))
        assertEquals(0, mimirFrameRate(deltaFrames = 0L, deltaMs = 1_000L))
        assertNull(mimirFrameRate(deltaFrames = 60L, deltaMs = 0L))

        var nowMs = 0L
        var frames = 0L
        val sampler = MimirFrameRateSampler(
            renderedFrames = { frames },
            elapsedRealtimeMs = { nowMs },
        )
        assertNull(sampler.sample())
        nowMs = 1_000L
        frames = 58L
        assertEquals(58, sampler.sample())

        // Mimir spends twenty minutes behind a game. Without the reset, the two frames it drew on the
        // way out would be divided by twenty minutes and reported as 0 fps for the second it came back.
        sampler.reset()
        nowMs = 1_201_000L
        frames = 60L
        assertNull("the sample after a reset seeds again", sampler.sample())
        nowMs = 1_202_000L
        frames = 120L
        assertEquals(60, sampler.sample())
    }
}
