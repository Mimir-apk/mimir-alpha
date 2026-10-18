package com.gruning.mimir.tweaker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nova, 2026-10-01 09:41: `avc: denied { read } for name="gpu_busy_percentage"` from the Tweaker's own process.
 * The reader asks the helper, remembers the source that answered, reuses its value for a few seconds, and never falls
 * back to the direct read while a helper exists: on a permissive domain that read succeeds, sticks, and brings the
 * per-tick denial back.
 */
class GpuUtilizationReaderTest {

    private class Rig(
        var helper: Boolean = true,
        private val privileged: (String) -> String? = { null },
        private val direct: (String) -> String? = { null },
    ) {
        var now = 0L
        val privilegedReads = mutableListOf<String>()
        val directReads = mutableListOf<String>()
        val reader = GpuUtilizationReader(
            readPrivileged = { path -> privilegedReads += path; privileged(path) },
            readDirect = { path -> directReads += path; direct(path) },
            helperPresent = { helper },
            nowMs = { now },
        )
    }

    @Test
    fun `the helper answers first and the direct read is never made`() {
        val rig = Rig(privileged = { path -> if (path == GpuUtilizationTelemetry.BUSY_PERCENTAGE) "37" else null })

        repeat(5) {
            val reading = rig.reader.read()
            assertEquals(37, reading.percent)
            assertEquals(GpuUtilizationTelemetry.BUSY_PERCENTAGE, reading.source)
            assertEquals(GpuUtilizationRoute.PRIVILEGED, reading.route)
            rig.now += 6_000L // past the reuse window each time, so every read reaches the helper
        }
        assertTrue("a denied direct read is audited every call: none may be made", rig.directReads.isEmpty())
        assertEquals(List(5) { GpuUtilizationTelemetry.BUSY_PERCENTAGE }, rig.privilegedReads)
    }

    @Test
    fun `a privileged value is reused for five seconds so a 750 ms refresh does not fork a process each time`() {
        val rig = Rig(privileged = { path -> if (path == GpuUtilizationTelemetry.BUSY_PERCENTAGE) "37" else null })

        assertEquals(37, rig.reader.read().percent)
        repeat(6) {
            rig.now += 750L
            assertEquals(37, rig.reader.read().percent)
        }
        assertEquals("one read for ${rig.now} ms of refreshes", 1, rig.privilegedReads.size)

        rig.now += 1_000L // 5.5 s since the read
        assertEquals(37, rig.reader.read().percent)
        assertEquals(2, rig.privilegedReads.size)
    }

    @Test
    fun `with no helper at all the direct read is used once discovered and then sticks`() {
        val rig = Rig(helper = false, direct = { path -> if (path == GpuUtilizationTelemetry.BUSY_PERCENTAGE) "12 %" else null })

        val first = rig.reader.read()
        assertEquals(12, first.percent)
        assertEquals(GpuUtilizationRoute.DIRECT, first.route)
        rig.directReads.clear()

        repeat(4) { assertEquals(12, rig.reader.read().percent) }
        assertTrue("no helper exists to ask", rig.privilegedReads.isEmpty())
        assertEquals(List(4) { GpuUtilizationTelemetry.BUSY_PERCENTAGE }, rig.directReads)
    }

    @Test
    fun `lower priority sources are used only when the preferred one cannot be read`() {
        val rig = Rig(privileged = { path -> if (path == GpuUtilizationTelemetry.BUSY_INTERVAL) "3 8" else null })

        val reading = rig.reader.read()

        assertEquals(38, reading.percent)
        assertEquals(GpuUtilizationTelemetry.BUSY_INTERVAL, reading.source)
    }

    @Test
    fun `a malformed or out of range reply is a failure and never a zero`() {
        val rig = Rig(privileged = { "garbage" }, direct = { "101" })

        val reading = rig.reader.read()

        assertNull(reading.percent)
        assertNull(reading.source)
        assertNull(reading.route)
        assertTrue("a helper exists: the direct read is not its fallback", rig.directReads.isEmpty())
    }

    @Test
    fun `a source nothing can read is not probed again until the retry window ends`() {
        val rig = Rig()

        assertNull(rig.reader.read().percent)
        val burst = rig.privilegedReads.size
        assertEquals("one pass over every source", GpuUtilizationTelemetry.SOURCES.size, burst)

        repeat(10) {
            rig.now += 1_000L
            assertNull(rig.reader.read().percent)
        }
        assertEquals("ten quiet ticks must read nothing", burst, rig.privilegedReads.size)

        rig.now += GpuUtilizationReader.UNAVAILABLE_RETRY_MS
        assertNull(rig.reader.read().percent)
        assertEquals("the window ended: exactly one more burst", burst * 2, rig.privilegedReads.size)
    }

    /** The finding: after one privileged miss, rediscovery fell to the direct read, which parses on a permissive domain. */
    @Test
    fun `a helper that misses never hands the source to the direct read, and recovers when it answers again`() {
        var privilegedUp = true
        val rig = Rig(
            privileged = { path -> if (privilegedUp && path == GpuUtilizationTelemetry.BUSY_PERCENTAGE) "50" else null },
            direct = { path -> if (path == GpuUtilizationTelemetry.LOAD) "70%" else null },
        )
        assertEquals(50, rig.reader.read().percent)

        privilegedUp = false
        rig.now += 6_000L
        assertNull("honest blank, not the permissive direct read", rig.reader.read().percent)
        assertTrue(rig.directReads.isEmpty())

        privilegedUp = true
        rig.now += GpuUtilizationReader.UNAVAILABLE_RETRY_MS
        val back = rig.reader.read()
        assertEquals(50, back.percent)
        assertEquals(GpuUtilizationRoute.PRIVILEGED, back.route)
        assertTrue(rig.directReads.isEmpty())
    }

    @Test
    fun `a reader that throws is a failure and not a crash`() {
        val reader = GpuUtilizationReader(
            readPrivileged = { error("binder died") },
            readDirect = { error("denied") },
            helperPresent = { true },
            nowMs = { 0L },
        )

        assertNull(reader.read().percent)
    }

    /**
     * S9 seam: [BoundedRead] takes its executor, so this test CONTROLS where each call runs instead of racing a thread
     * pool and a wall clock.
     *
     * The wedge: the first call runs on a thread of its own and [ControlledReadExecutor.execute] returns only once the
     * helper stand-in has really ENTERED (so production's "started" flag is already set, and the deadline can only meet
     * a call that has begun). The stand-in then blocks on a latch the test holds and does not give way to an interrupt,
     * like a Binder call stuck inside the helper, so the deadline expiring is guaranteed rather than probable: nothing
     * but the test releasing the latch can end it, whatever the deadline's length. Every other outcome is decided by
     * counts, never by timing: the second read is refused without a submission, and once the thread is joined the
     * worker is free and the next call runs inline.
     */
    @Test(timeout = 30_000L)
    fun `a read past its deadline fails closed and a wedged helper costs one thread, not a queue`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val executor = ControlledReadExecutor()
        val bounded = BoundedRead(
            delegate = { _ ->
                calls.incrementAndGet()
                entered.countDown()
                var interrupted = false
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        interrupted = true // a stuck Binder call does not give way to an interrupt either
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
                "42"
            },
            timeoutMs = 20L,
            worker = executor,
        )

        try {
            executor.beginOnOwnThreadUntil = entered
            assertNull("the deadline passes with no value, never a zero", bounded.read("/p"))
            assertEquals("returned while the helper was still wedged", 1L, release.count)
            assertEquals("the wedged call really began", 1, calls.get())
            assertEquals(1, executor.submissions.get())

            assertNull("a second read while the first is wedged is refused", bounded.read("/p"))
            assertEquals("refused, not queued: the executor was never asked", 1, executor.submissions.get())
            assertEquals("and the helper was never called again", 1, calls.get())

            release.countDown()
            executor.joinOwnThread() // the wedged call has returned and released the worker
            executor.beginOnOwnThreadUntil = null // later calls run inline on the reading thread
            assertEquals("the worker is free again", "42", bounded.read("/p"))
            assertEquals(2, executor.submissions.get())
            assertEquals(2, calls.get())
        } finally {
            release.countDown() // a failed assertion must not leave the daemon thread blocked
        }
    }

    @Test(timeout = 30_000L)
    fun `a call the worker never began is released at the deadline, so it cannot wedge every later read`() {
        val executor = ControlledReadExecutor()
        val calls = AtomicInteger(0)
        val bounded = BoundedRead(delegate = { calls.incrementAndGet(); "7" }, timeoutMs = 20L, worker = executor)

        executor.holdSubmissions = true
        assertNull("a call that is never run times out", bounded.read("/p"))
        assertEquals("it was submitted and never began", 1, executor.submissions.get())
        assertEquals(0, calls.get())

        executor.holdSubmissions = false
        assertEquals("and the next read is not refused as wedged", "7", bounded.read("/p"))
        assertEquals(2, executor.submissions.get())
        assertEquals(1, calls.get())
    }

    /**
     * An executor the test drives. By default a task runs INLINE on the submitting thread (so its future is complete
     * before the caller waits on it). [beginOnOwnThreadUntil] runs the next task on its own thread and returns only once
     * that latch opens; [holdSubmissions] accepts a task and never runs it.
     */
    private class ControlledReadExecutor : java.util.concurrent.AbstractExecutorService() {
        val submissions = AtomicInteger(0)
        @Volatile var beginOnOwnThreadUntil: CountDownLatch? = null
        @Volatile var holdSubmissions = false
        @Volatile private var ownThread: Thread? = null

        override fun execute(command: Runnable) {
            submissions.incrementAndGet()
            if (holdSubmissions) return
            val begun = beginOnOwnThreadUntil
            if (begun == null) {
                command.run()
                return
            }
            val thread = Thread(command, "controlled-read-worker").apply { isDaemon = true }
            ownThread = thread
            thread.start()
            check(begun.await(10, TimeUnit.SECONDS)) { "the helper stand-in never began" }
        }

        fun joinOwnThread() {
            ownThread?.join(10_000L)
            check(ownThread?.isAlive != true) { "the wedged call did not return after its release" }
        }

        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
    }

    @Test
    fun `a read inside its deadline returns its value and a throw is null`() {
        assertEquals("7", BoundedRead(delegate = { "7" }, timeoutMs = 1_000L).read("/p"))
        assertNull(BoundedRead(delegate = { error("boom") }, timeoutMs = 1_000L).read("/p"))
        // The failure released the worker.
        val bounded = BoundedRead(delegate = { error("boom") }, timeoutMs = 1_000L)
        assertNull(bounded.read("/p"))
        assertNull(bounded.read("/p"))
    }

    @Test
    fun `the helper allowlist admits exactly the three utilization sources and nothing near them`() {
        val commands = mutableListOf<String>()
        val access = PServerProtectedNodeAccess(
            PServerClient(
                transport = PServerBinderTransport { command, _ ->
                    commands += command
                    PServerTransactResult(accepted = true, output = "9")
                },
            ),
        )

        GpuUtilizationTelemetry.SOURCES.forEach { path -> assertEquals("9", access.read(path)) }
        assertEquals(GpuUtilizationTelemetry.SOURCES.map { "cat $it" }, commands)

        commands.clear()
        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage2",
            "/sys/class/kgsl/kgsl-3d0/../kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/proc/stat",
        ).forEach { assertNull(it, access.read(it)) }
        assertTrue("a refused path must never reach the helper", commands.isEmpty())
    }
}
