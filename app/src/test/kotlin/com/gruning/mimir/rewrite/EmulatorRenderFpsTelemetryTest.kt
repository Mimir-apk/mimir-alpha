package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorRenderFpsTelemetryTest {
    @Test
    fun `request and record preserve native V1 schema`() {
        val request = EmulatorRenderFpsRequest(
            token = "0123abcd",
            packageName = "com.example.emulator",
            uid = 4_294_967_295L,
            pid = 321,
            startTicks = 77_000L,
            generation = 4L,
            leaseBoottimeMs = 11_000L,
        )
        val requestWire = requireNotNull(formatEmulatorRenderFpsRequest(request))
        assertEquals(request, parseEmulatorRenderFpsRequest(requestWire))

        val record = EmulatorRenderFpsRecord(
            token = request.token,
            packageName = request.packageName,
            uid = request.uid,
            pid = request.pid,
            startTicks = request.startTicks,
            generation = request.generation,
            sampleBoottimeMs = 1_250L,
            intervalMs = 1_000L,
            count = 60L,
            status = "ok",
        )
        val recordWire = requireNotNull(formatEmulatorRenderFpsRecord(record))
        assertEquals(record, parseEmulatorRenderFpsRecord(recordWire))
    }

    @Test
    fun `parser rejects duplicate unknown missing malformed and non ascii fields`() {
        val valid = requestWire()
        assertNull(parseEmulatorRenderFpsRequest(valid.replace("pid=321\n", "pid=321\npid=322\n")))
        assertNull(parseEmulatorRenderFpsRequest(valid.replace("pid=321\n", "other=321\n")))
        assertNull(parseEmulatorRenderFpsRequest(valid.removeSuffix("interval_ms=1000\n")))
        assertNull(parseEmulatorRenderFpsRequest(valid.replace("interval_ms=1000", "interval_ms=999")))
        assertNull(parseEmulatorRenderFpsRequest(valid.replace("token=0123abcd", "token=0123abg8")))
        assertNull(
            parseEmulatorRenderFpsRequest(valid.replace("package=com.example.emulator", "package=com..emulator")),
        )
        assertNull(parseEmulatorRenderFpsRequest(valid.replace("package=com.example.emulator", "package=com.éxample")))
        assertNull(parseEmulatorRenderFpsRequest(valid.removeSuffix("\n")))

        val record = recordWire()
        assertNull(parseEmulatorRenderFpsRecord(record.replace("status=ok", "status=unknown")))
        assertNull(parseEmulatorRenderFpsRecord(record.replace("count=60", "count=1000001")))
        assertNull(parseEmulatorRenderFpsRecord(record.replace("interval=1000", "interval=0")))
        assertNull(parseEmulatorRenderFpsRecord(record.replace("boot=1250", "boot=-1")))
    }

    @Test
    fun `semantic acceptance requires successful nonempty fresh same lease identity`() {
        val request = makeRequest(
            token = "0123abcd",
            lease = 10_000L,
            generation = 3L,
        )
        val base = makeRecord(
            token = request.token,
            generation = request.generation,
            boot = 8_000L,
        )
        assertTrue(base.isAcceptableFor(request, nowBoottimeMs = 9_000L))
        assertFalse(base.copy(status = "warming").isAcceptableFor(request, 9_000L))
        assertFalse(base.copy(count = 0L).isAcceptableFor(request, 9_000L))
        assertFalse(base.copy(token = "deadbeef").isAcceptableFor(request, 9_000L))
        assertFalse(base.copy(pid = request.pid + 1).isAcceptableFor(request, 9_000L))
        assertFalse(base.copy(startTicks = 0L).isAcceptableFor(request, 9_000L))
        assertFalse(base.copy(generation = request.generation + 1).isAcceptableFor(request, 9_000L))
        assertFalse(base.copy(sampleBoottimeMs = 9_001L).isAcceptableFor(request, 9_000L))
        assertFalse(base.copy(sampleBoottimeMs = 6_499L).isAcceptableFor(request, 9_000L))
        assertFalse(base.isAcceptableFor(request.copy(leaseBoottimeMs = 9_000L), 9_000L))
        assertFalse(base.isAcceptableFor(request.copy(leaseBoottimeMs = 20_001L), 9_000L))
    }

    @Test
    fun `non-ok native status may carry zero interval while successful rate honors interval`() {
        val request = makeRequest(lease = 10_000L)
        val warming = makeRecord(request = request, status = "warming", boot = 8_000L)
            .copy(intervalMs = 0L, count = 0L)
        assertNotNull(parseEmulatorRenderFpsRecord(formatEmulatorRenderFpsRecord(warming)!!))
        assertFalse(warming.isAcceptableFor(request, 9_000L))
        assertNull(
            parseEmulatorRenderFpsRecord(
                recordWire().replace("status=ok", "status=warming").replace("count=60", "count=1"),
            ),
        )

        val shortOk = makeRecord(request = request, boot = 8_000L).copy(intervalMs = 999L)
        assertNotNull(parseEmulatorRenderFpsRecord(formatEmulatorRenderFpsRecord(shortOk)!!))

        val slower = makeRecord(request = request, boot = 8_000L).copy(
            intervalMs = 2_000L,
            count = 120L,
        )
        assertTrue(slower.isAcceptableFor(request, 9_000L))
        assertEquals(60_000L, slower.count * 1_000_000L / slower.intervalMs)
        val faster = slower.copy(intervalMs = 1_500L, count = 45L)
        assertEquals(30_000L, faster.count * 1_000_000L / faster.intervalMs)
    }

    @Test
    fun `session rejects unapproved or unvalidated identity and creates bounded lease`() {
        var now = 1_000L
        val session = EmulatorRenderFpsSession(
            approvedPackages = setOf("com.example.emulator"),
            nowBoottimeMs = { now },
            tokenFactory = { "cafebabe" },
        )

        assertNull(session.begin(EmulatorRenderFpsTargetIdentity("com.other", 10L, 20, 30L)))
        assertNull(session.begin(EmulatorRenderFpsTargetIdentity("com.example.emulator", 10L, 20, 0L)))
        val request = requireNotNull(
            session.begin(EmulatorRenderFpsTargetIdentity("com.example.emulator", 10L, 20, 30L)),
        )
        assertEquals(4_500L, request.leaseBoottimeMs)
        assertEquals(1L, request.generation)
        assertEquals(request, session.request)
        assertEquals(request, parseEmulatorRenderFpsRequest(requireNotNull(formatEmulatorRenderFpsRequest(request))))

        now = 4_499L
        assertNotNull(session.request)
        assertEquals(60L, session.accept(makeRecord(request = request, boot = 4_000L)))
        now = 4_500L
        assertNull(session.accept(makeRecord(request = request, boot = 4_000L)))
        assertNull(session.request)
        session.invalidate()
        assertNull(session.request)
    }

    @Test
    fun `identity mismatch invalidates lease while same identity unavailable can retry`() {
        var now = 1_000L
        val session = EmulatorRenderFpsSession(
            approvedPackages = setOf("com.example.emulator"),
            nowBoottimeMs = { now },
            tokenFactory = { "0123abcd" },
        )
        val target = EmulatorRenderFpsTargetIdentity("com.example.emulator", 10L, 20, 30L)
        val request = requireNotNull(session.begin(target))
        assertNull(session.accept(makeRecord(request = request, status = "lost", boot = 1_000L)))
        assertNotNull(session.request)
        assertNull(session.accept(makeRecord(request = request, pid = 21, boot = 1_000L)))
        assertNull(session.request)
    }

    @Test
    fun `same identity renews generation while expiry starts a new generation`() {
        var now = 1_000L
        val target = EmulatorRenderFpsTargetIdentity("com.example.emulator", 10L, 20, 30L)
        val session = EmulatorRenderFpsSession(
            approvedPackages = setOf(target.packageName),
            nowBoottimeMs = { now },
            tokenFactory = { "0123abcd" },
        )
        val first = requireNotNull(session.begin(target))
        now = 2_000L
        val renewed = requireNotNull(session.renew(target))
        assertEquals(first.token, renewed.token)
        assertEquals(first.generation, renewed.generation)
        assertEquals(5_500L, renewed.leaseBoottimeMs)
        now = renewed.leaseBoottimeMs
        val restarted = requireNotNull(session.renew(target))
        assertEquals(first.generation + 1L, restarted.generation)
        assertEquals(9_000L, restarted.leaseBoottimeMs)
    }

    private fun requestWire(): String = formatEmulatorRenderFpsRequest(makeRequest())!!

    private fun recordWire(): String = formatEmulatorRenderFpsRecord(makeRecord())!!

    private fun makeRequest(
        token: String = "0123abcd",
        lease: Long = 11_000L,
        generation: Long = 1L,
    ) = EmulatorRenderFpsRequest(
        token = token,
        packageName = "com.example.emulator",
        uid = 10L,
        pid = 321,
        startTicks = 77_000L,
        generation = generation,
        leaseBoottimeMs = lease,
    )

    private fun makeRecord(
        request: EmulatorRenderFpsRequest = makeRequest(),
        token: String = request.token,
        pid: Int = request.pid,
        generation: Long = request.generation,
        boot: Long = 1_250L,
        status: String = "ok",
    ) = EmulatorRenderFpsRecord(
        token = token,
        packageName = request.packageName,
        uid = request.uid,
        pid = pid,
        startTicks = request.startTicks,
        generation = generation,
        sampleBoottimeMs = boot,
        intervalMs = 1_000L,
        count = 60L,
        status = status,
    )
}
