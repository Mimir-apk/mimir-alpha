package com.gruning.mimir.tweaker

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LabQualificationEvidenceSampler] replaced `LabQualificationEvidenceCadence`, which used to be
 * invoked from inside [MimirTweakerService]'s fan-control `ThermalTelemetryPort` closure
 * (`onFanCadenceTick()`). That put a synchronous, potentially IPC-blocking telemetry compose
 * directly on the single fan-monitor thread and starved `FAN_MONITOR_SAMPLE` under WLE load. This
 * sampler now owns its own dedicated thread with its own ~1 s cadence, started/stopped from
 * `startMonitor()`/`stopMonitor()` instead of from inside the fan-control closure.
 *
 * The first block below tests the sampler class in isolation, using real threads (short cadences,
 * polling with a bounded timeout) since the whole point of this fix is thread placement. The
 * second block statically scans the real `MimirTweakerService.kt` source to guard the actual
 * regression this fix addresses: nothing may reintroduce evidence sampling onto the fan-control
 * closure. Root resolution mirrors [GuardianEntryPosturePolicyTest]'s existing pattern -- Gradle's
 * test working directory differs between an IDE run and a module-relative `--offline` invocation.
 */
class LabQualificationEvidenceSamplerTest {

    // ---------------------------------------------------------------------------------------
    // Isolated sampler-class contract. Short cadence, bounded polling -- these are real threads.
    // ---------------------------------------------------------------------------------------

    private val awaitTimeoutMs = 5_000L

    @Test
    fun `enabled sampler samples telemetry repeatedly on its own cadence`() {
        val latch = CountDownLatch(3)
        val sampler = LabQualificationEvidenceSampler(
            enabled = { true },
            sampleTelemetry = { latch.countDown() },
            cadenceMs = 10L,
        )

        sampler.start()
        try {
            assertTrue("expected >=3 ticks", latch.await(awaitTimeoutMs, TimeUnit.MILLISECONDS))
        } finally {
            sampler.stop()
        }
    }

    @Test
    fun `disabled sampler never starts a thread or samples telemetry -- production and safe posture`() {
        val calls = AtomicInteger(0)
        val sampler = LabQualificationEvidenceSampler(
            enabled = { false },
            sampleTelemetry = { calls.incrementAndGet() },
            cadenceMs = 10L,
        )

        sampler.start()
        Thread.sleep(150L) // several cadence periods' worth of time to prove nothing ever fires
        sampler.stop()

        assertEquals(0, calls.get())
        assertFalse(
            "no mimir-tweaker-lab-evidence thread may exist outside LAB",
            Thread.getAllStackTraces().keys.any { it.name == "mimir-tweaker-lab-evidence" },
        )
    }

    @Test
    fun `sampleTelemetry runs on the sampler's own dedicated thread, never the caller's thread`() {
        val latch = CountDownLatch(1)
        val observedThreadName = AtomicReference<String>()
        val callingThreadName = Thread.currentThread().name
        val sampler = LabQualificationEvidenceSampler(
            enabled = { true },
            sampleTelemetry = {
                observedThreadName.set(Thread.currentThread().name)
                latch.countDown()
            },
            cadenceMs = 10L,
        )

        sampler.start()
        try {
            assertTrue(latch.await(awaitTimeoutMs, TimeUnit.MILLISECONDS))
            assertEquals("mimir-tweaker-lab-evidence", observedThreadName.get())
            assertTrue(observedThreadName.get() != callingThreadName)
        } finally {
            sampler.stop()
        }
    }

    @Test
    fun `an exception while sampling telemetry is swallowed and does not stop the cadence`() {
        val calls = AtomicInteger(0)
        val secondTickLatch = CountDownLatch(1)
        val sampler = LabQualificationEvidenceSampler(
            enabled = { true },
            sampleTelemetry = {
                val n = calls.incrementAndGet()
                if (n == 1) error("cross-process probe failure")
                if (n >= 2) secondTickLatch.countDown()
            },
            cadenceMs = 10L,
        )

        sampler.start()
        try {
            assertTrue(
                "a tick throwing must not prevent later ticks",
                secondTickLatch.await(awaitTimeoutMs, TimeUnit.MILLISECONDS),
            )
        } finally {
            sampler.stop()
        }
    }

    @Test
    fun `stop is idempotent even when never started`() {
        val sampler = LabQualificationEvidenceSampler(enabled = { true }, sampleTelemetry = {})
        sampler.stop()
        sampler.stop()
    }

    @Test
    fun `stop halts future ticks`() {
        val calls = AtomicInteger(0)
        val firstTick = CountDownLatch(1)
        val sampler = LabQualificationEvidenceSampler(
            enabled = { true },
            sampleTelemetry = { calls.incrementAndGet(); firstTick.countDown() },
            cadenceMs = 10L,
        )

        sampler.start()
        assertTrue(firstTick.await(awaitTimeoutMs, TimeUnit.MILLISECONDS))
        sampler.stop()
        val countAtStop = calls.get()
        Thread.sleep(150L) // several cadence periods' worth of time to prove nothing further fires
        assertEquals(countAtStop, calls.get())
    }

    @Test
    fun `sampler stops itself when enabled is lost mid-hold -- eg dry-run is enabled`() {
        val stillEnabled = AtomicBoolean(true)
        val calls = AtomicInteger(0)
        val firstTick = CountDownLatch(1)
        val sampler = LabQualificationEvidenceSampler(
            enabled = stillEnabled::get,
            sampleTelemetry = {
                calls.incrementAndGet()
                stillEnabled.set(false) // simulate enabling dry-run mid-hold
                firstTick.countDown()
            },
            cadenceMs = 10L,
        )

        sampler.start()
        assertTrue(firstTick.await(awaitTimeoutMs, TimeUnit.MILLISECONDS))
        // Give the self-stop (running on the sampler's own thread, inside tick()) a moment to
        // actually take effect, then confirm no further ticks land.
        Thread.sleep(150L)
        sampler.stop()

        assertEquals(1, calls.get())
    }

    // ---------------------------------------------------------------------------------------
    // Static regression guard on the real MimirTweakerService.kt source: nothing may reintroduce
    // LAB evidence sampling onto the fan-control ThermalTelemetryPort closure.
    // ---------------------------------------------------------------------------------------

    private fun mainSourceRoot(): File =
        sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")

    private fun fanControlClosureSource(): String {
        val service = File(mainSourceRoot(), "MimirTweakerService.kt").readText()
        val start = service.indexOf("telemetry = ThermalTelemetryPort {")
        assertTrue("ThermalTelemetryPort wiring not found in MimirTweakerService.kt", start >= 0)
        val end = service.indexOf("ownerLiveness = OwnerLivenessPort {", start)
        assertTrue("could not bound the ThermalTelemetryPort closure", end > start)
        return service.substring(start, end)
    }

    @Test
    fun `the fan-control closure does no LAB evidence sampling work`() {
        val closure = fanControlClosureSource()

        listOf(
            "labQualificationEvidenceSampler",
            "labQualificationEvidenceCadence",
            "onFanCadenceTick",
            "liveTelemetryCache",
            "sampleTelemetry",
            "LabQualificationEvidence",
        ).forEach { forbidden ->
            assertFalse(
                "fan-control ThermalTelemetryPort closure must do zero LAB evidence work; " +
                    "found reference to `$forbidden`",
                closure.contains(forbidden),
            )
        }
    }

    @Test
    fun `the sampler is started and stopped alongside the fan monitor, not from inside its closure`() {
        val service = File(mainSourceRoot(), "MimirTweakerService.kt").readText()

        assertTrue(
            "startMonitor() must start the LAB evidence sampler",
            service.contains("labQualificationEvidenceSampler.start()"),
        )
        assertTrue(
            "stopMonitor() must stop the LAB evidence sampler",
            service.contains("labQualificationEvidenceSampler.stop()"),
        )
    }
}
