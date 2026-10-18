package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.DREAMCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST_REICAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.GB
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.PPSSPP
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.PSP
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.RECOGNIZE_ONLY
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.RETROARCH
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The idempotency test matrix of REMOTE_EMULATOR_DEFINITIONS_2026-10-08.md section 6, T1 to T23 plus T3b and T10b.
 * Every test name starts with its matrix id. Pure JVM: a fake transport that returns canned bodies and statuses, and
 * a temp directory. No network, no device, no emulator.
 */
class RemoteDefinitionsMatrixTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bundled = TestBundled.small()

    private val alt = Rows.alt(FLYCAST, "dreamcast", "com.reicast.emulator.AltActivity")
    private val coreGb = Rows.core(RETROARCH, "gb", "mgba_extra")
    private val ext = Rows.ext(PPSSPP, "psp", "cso", "pbp")
    private val regex = Rows.regex(PPSSPP, "psp", "^.*\\\\.(?:cso|ciso)\$")

    private fun newDir(name: String = "store"): File = tmp.newFolder(name)

    private fun rig(egress: FakeEgress, bundled: BundledDefinitions = this.bundled, name: String = "store") =
        Rig(newDir(name), bundled, egress)

    private fun v(ordinal: Long, vararg rows: String): Built = Overlays.build(ordinal, rows.toList())

    // ---------------------------------------------------------------------------------------------------------
    // T1 .. T3b: fetching the same thing again

    @Test
    fun t01_fetch_twice_same_bytes_writes_nothing_the_second_time() {
        val built = v(5, alt, coreGb, ext)
        val rig = rig(FakeEgress.always { FakeResponse.ok(built) })

        assertEquals(RefreshResult.Applied(5, built.rawSha), rig.refresh(built))
        ageFiles(rig.dir)
        val files = snapshot(rig.dir)
        val commits = rig.store.commitCount
        val emissions = rig.holder.emissions
        val key = rig.holder.current.stateKey

        assertEquals(RefreshResult.NoChange, rig.refresh(built))

        assertEquals("no file written and no mtime touched", files, snapshot(rig.dir))
        assertEquals(commits, rig.store.commitCount)
        assertEquals("no Flow emission", emissions, rig.holder.emissions)
        assertEquals(key, rig.holder.current.stateKey)
        assertEquals("the stored validator was offered", "\"e1\"", rig.egress.requests[1].header("If-None-Match"))
    }

    @Test
    fun t02a_identical_bytes_with_a_new_etag_writes_nothing() {
        val built = v(5, alt, coreGb)
        val rig = rig(FakeEgress.sequence({ FakeResponse.ok(built, "\"a\"") }, { FakeResponse.ok(built, "\"b\"") }))

        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        ageFiles(rig.dir)
        val files = snapshot(rig.dir)

        assertEquals("the hash decides, not the ETag", RefreshResult.NoChange, rig.refresh(built))
        assertEquals(files, snapshot(rig.dir))
        assertEquals("the old validator is kept; a newer one is not worth a write", "\"a\"", rig.store.readRecord()!!.etag)
        assertEquals(1, rig.store.commitCount)
    }

    @Test
    fun t02b_gzip_and_identity_encodings_of_the_same_content_are_one_identity() {
        val built = v(5, alt, coreGb)
        val rig = rig(FakeEgress.sequence({ FakeResponse.ok(built, "\"id\"") }, { FakeResponse.gzip(built.bytes) }))

        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        ageFiles(rig.dir)
        val files = snapshot(rig.dir)

        assertEquals("gzip carries a different weak ETag on GitHub; the decoded hash is the same", RefreshResult.NoChange, rig.refresh(built))
        assertEquals(files, snapshot(rig.dir))
        assertEquals("identity is always requested first", "identity", rig.egress.requests[1].header("Accept-Encoding"))
    }

    @Test
    fun t03_304_with_a_good_local_copy_reads_no_body_and_writes_nothing() {
        val built = v(5, alt, coreGb)
        val rig = rig(FakeEgress.sequence({ FakeResponse.ok(built) }, { FakeResponse.status(304) }))

        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        ageFiles(rig.dir)
        val files = snapshot(rig.dir)

        assertEquals(RefreshResult.NoChange, rig.refresh(built))

        assertEquals(0, (rig.egress.responses[1] as FakeResponse).bodyCalls.get())
        assertEquals("\"e1\"", rig.egress.requests[1].header("If-None-Match"))
        assertEquals(files, snapshot(rig.dir))
        assertEquals(1, rig.store.commitCount)
    }

    @Test
    fun t03b_304_but_the_local_copy_is_corrupt_refetches_unconditionally_then_applies() {
        val built = v(5, alt, coreGb)
        lateinit var rigRef: Rig
        val egress = FakeEgress { _, i ->
            when (i) {
                1 -> {
                    // The copy that vouched for the 304 goes bad after the request was sent.
                    val file = rigRef.store.overlayFile(built.rawSha)
                    val bytes = file.readBytes()
                    bytes[10] = (bytes[10].toInt() xor 0x01).toByte()
                    file.writeBytes(bytes)
                    FakeResponse.status(304)
                }
                else -> FakeResponse.ok(built)
            }
        }
        rigRef = rig(egress)

        assertTrue(rigRef.refresh(built) is RefreshResult.Applied)
        val result = rigRef.refresh(built)

        assertEquals(RefreshResult.Applied(5, built.rawSha), result)
        assertEquals(3, egress.requests.size)
        assertNotNull("the second request still offered the validator", egress.requests[1].header("If-None-Match"))
        assertNull("the retry must not offer one", egress.requests[2].header("If-None-Match"))
        assertTrue(built.bytes.contentEquals(rigRef.store.readVerified()!!.bytes))
    }

    @Test
    fun t03b_304_but_the_local_copy_is_missing_refetches_unconditionally_then_applies() {
        val built = v(5, alt, coreGb)
        lateinit var rigRef: Rig
        val egress = FakeEgress { _, i ->
            when (i) {
                1 -> {
                    assertTrue(rigRef.store.overlayFile(built.rawSha).delete())
                    FakeResponse.status(304)
                }
                else -> FakeResponse.ok(built)
            }
        }
        rigRef = rig(egress)

        assertTrue(rigRef.refresh(built) is RefreshResult.Applied)
        assertEquals(RefreshResult.Applied(5, built.rawSha), rigRef.refresh(built))
        assertNull(egress.requests[2].header("If-None-Match"))
        assertNotNull(rigRef.store.readVerified())
    }

    @Test
    fun t03b_a_304_to_a_request_that_asked_for_nothing_conditional_is_refused() {
        val rig = rig(FakeEgress.always { FakeResponse.status(304) })
        assertEquals(RefreshResult.Rejected(RejectCode.HTTP_STATUS), rig.refresher.refresh(v(5, alt).pin))
        assertEquals("no retry loop", 1, rig.egress.requests.size)
    }

    // ---------------------------------------------------------------------------------------------------------
    // T4: truncation

    @Test
    fun t04_truncated_body_is_not_applied() {
        val v5 = v(5, alt)
        val v6 = v(6, alt, coreGb, ext)
        var mode = 0
        val full = v6.bytes
        val egress = FakeEgress { _, _ ->
            when (mode) {
                0 -> FakeResponse.ok(v5)
                // Short Content-Length: the server promised everything and delivered half.
                1 -> FakeResponse(200, full.copyOf(full.size / 2), mapOf("ETag" to "\"x\""), declaredLength = full.size.toLong())
                // Chunked, connection cut early.
                2 -> FakeResponse(200, full, mapOf("ETag" to "\"x\""), declaredLength = -1) { CutStream(full, 40) }
                else -> null
            }
        }
        val rig = rig(egress)
        assertTrue(rig.refresh(v5) is RefreshResult.Applied)
        val pointer = rig.store.readRecord()
        val key = rig.holder.current.stateKey
        val files = fileNames(rig.dir)

        mode = 1
        assertEquals(RefreshResult.Rejected(RejectCode.TRUNCATED), rig.refresh(v6))
        mode = 2
        assertEquals(RefreshResult.Rejected(RejectCode.TRUNCATED), rig.refresh(v6))

        assertEquals(pointer, rig.store.readRecord())
        assertEquals(key, rig.holder.current.stateKey)
        assertEquals("no temp file and no new overlay left behind", files, fileNames(rig.dir))
    }

    @Test
    fun t04_cut_at_every_byte_boundary_never_applies() {
        val v5 = v(5, alt)
        val v6 = v(6, alt, coreGb, ext)
        var cut = -1
        val egress = FakeEgress { _, _ ->
            if (cut < 0) FakeResponse.ok(v5) else FakeResponse(200, v6.bytes.copyOf(cut), emptyMap(), declaredLength = -1)
        }
        val rig = rig(egress)
        assertTrue(rig.refresh(v5) is RefreshResult.Applied)
        val pointer = rig.store.readRecord()
        val files = fileNames(rig.dir)
        val allowed = setOf(RejectCode.NOT_JSON, RejectCode.PARSE, RejectCode.TRUNCATED, RejectCode.HASH_MISMATCH, RejectCode.TOO_DEEP)

        for (at in 0 until v6.bytes.size) {
            cut = at
            val result = rig.refresh(v6)
            assertTrue("cut at $at gave $result", result is RefreshResult.Rejected && result.code in allowed)
        }

        assertEquals(pointer, rig.store.readRecord())
        assertEquals(files, fileNames(rig.dir))
    }

    @Test
    fun t04_a_tampered_but_complete_body_fails_its_hash_and_a_wrong_pin_is_caught() {
        val v5 = v(5, alt)
        val v6 = v(6, alt, coreGb)
        val tampered = String(v6.bytes, Charsets.US_ASCII).replace("mgba_extra", "mgba_extrb").toByteArray(Charsets.US_ASCII)
        val other = v(6, alt, ext) // internally consistent, but not what the pin names
        var serve: ByteArray = v5.bytes
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve) })
        assertTrue(rig.refresh(v5) is RefreshResult.Applied)

        serve = tampered
        assertEquals(RefreshResult.Rejected(RejectCode.HASH_MISMATCH), rig.refresh(v6))
        serve = other.bytes
        assertEquals(RefreshResult.Rejected(RejectCode.PIN_MISMATCH), rig.refresh(v6))
        assertEquals(5L, rig.store.readRecord()!!.ordinal)
    }

    // ---------------------------------------------------------------------------------------------------------
    // T5: malformed JSON

    @Test
    fun t05_malformed_json_is_not_applied() {
        val good = v(5, alt)
        val next = v(6, alt, coreGb)
        val deep = "{\"a\":".repeat(1000) + "1" + "}".repeat(1000)
        val manyKeys = "{" + (1..17).joinToString(",") { "\"k$it\":1" } + "}"
        val bigArray = "{\"schema\":1,\"a\":[" + (1..257).joinToString(",") { "0" } + "]}"
        val cases: List<Triple<String, ByteArray, RejectCode>> = listOf(
            Triple("syntax", "{\"schema\":1,".toByteArray(), RejectCode.PARSE),
            Triple("duplicate key", "{\"schema\":1,\"schema\":1}".toByteArray(), RejectCode.DUPLICATE_KEY),
            Triple("byte order mark", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + next.bytes, RejectCode.BOM),
            Triple("NaN", "{\"schema\":NaN}".toByteArray(), RejectCode.PARSE),
            Triple("Infinity", "{\"schema\":Infinity}".toByteArray(), RejectCode.PARSE),
            Triple("depth 1000", deep.toByteArray(), RejectCode.TOO_DEEP),
            Triple("html page", "<html><body>Sign in to Wi-Fi</body></html>".toByteArray(), RejectCode.NOT_JSON),
            Triple("empty", ByteArray(0), RejectCode.NOT_JSON),
            Triple("top-level array", "[]".toByteArray(), RejectCode.NOT_JSON),
            Triple("trailing bytes", next.bytes + "x".toByteArray(), RejectCode.PARSE),
            Triple("fraction", "{\"schema\":1,\"ordinal\":1.5}".toByteArray(), RejectCode.PARSE),
            Triple("exponent", "{\"schema\":1e0}".toByteArray(), RejectCode.PARSE),
            Triple("leading zero", "{\"schema\":01}".toByteArray(), RejectCode.PARSE),
            Triple("raw tab in a string", "{\"schema\":1,\"x\":\"a\tb\"}".toByteArray(), RejectCode.BAD_STRING),
            Triple("non-ASCII in a string", "{\"schema\":1,\"x\":\"caf\u00e9\"}".toByteArray(Charsets.UTF_8), RejectCode.BAD_STRING),
            Triple("escaped newline", "{\"schema\":1,\"x\":\"a\\nb\"}".toByteArray(), RejectCode.BAD_STRING),
            Triple("escaped NUL", "{\"schema\":1,\"x\":\"\\u0000\"}".toByteArray(), RejectCode.BAD_STRING),
            Triple("bad escape", "{\"schema\":1,\"x\":\"\\q\"}".toByteArray(), RejectCode.PARSE),
            Triple("17 keys in one object", manyKeys.toByteArray(), RejectCode.PARSE),
            Triple("257 array elements", bigArray.toByteArray(), RejectCode.TOO_MANY_ROWS),
        )
        var serve: ByteArray = good.bytes
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve, etag = null) })
        assertTrue(rig.refresh(good) is RefreshResult.Applied)
        val pointer = rig.store.readRecord()
        val key = rig.holder.current.stateKey
        val files = fileNames(rig.dir)

        for ((name, bytes, code) in cases) {
            serve = bytes
            assertEquals(name, RefreshResult.Rejected(code), rig.refresh(next))
            assertEquals("$name must leave the pointer alone", pointer, rig.store.readRecord())
            assertEquals("$name must leave the effective state alone", key, rig.holder.current.stateKey)
            assertEquals("$name must leave no stray files", files, fileNames(rig.dir))
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // T6, T7: schema and closed shape

    @Test
    fun t06_schema_bump_is_refused_with_last_good_kept_and_one_update_flag() {
        val good = v(5, alt)
        val future = Overlays.fromBody(6, """{"rows":[],"newThing":{"deep":true}}""", schema = 2, extraEnvelope = "\"futureField\":7")
        var serve: Built = good
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve) })
        assertTrue(rig.refresh(good) is RefreshResult.Applied)
        val pointer = rig.store.readRecord()
        val key = rig.holder.current.stateKey
        assertFalse(rig.holder.needsAppUpdate)

        serve = future
        val before = rig.egress.requests.size
        assertEquals(RefreshResult.Rejected(RejectCode.SCHEMA_NEWER), rig.refresh(future))
        assertTrue(rig.holder.needsAppUpdate)
        assertEquals("exactly one request, no retry loop", before + 1, rig.egress.requests.size)
        assertEquals(RefreshResult.Rejected(RejectCode.SCHEMA_NEWER), rig.refresh(future))
        assertEquals(before + 2, rig.egress.requests.size)
        assertEquals(pointer, rig.store.readRecord())
        assertEquals(key, rig.holder.current.stateKey)

        // A schema below 1 or of the wrong type is a different fault, not "needs an update".
        assertEquals(RefreshResult.Rejected(RejectCode.BAD_SCHEMA), rig.refresh(Overlays.build(7, listOf(alt), schema = 0).also { serve = it }))
        serve = Overlays.build(7, listOf(alt)).let { Built(String(it.bytes, Charsets.US_ASCII).replace("\"schema\":1", "\"schema\":\"1\"").toByteArray(), 7, it.canonicalSha) }
        assertEquals(RefreshResult.Rejected(RejectCode.BAD_ENVELOPE), rig.refresh(serve))

        // A supported file afterwards clears the flag.
        val next = v(8, alt, coreGb)
        serve = next
        assertTrue(rig.refresh(next) is RefreshResult.Applied)
        assertFalse(rig.holder.needsAppUpdate)
    }

    @Test
    fun t07_unknown_key_at_the_same_schema_rejects_the_whole_file() {
        val good = v(5, alt)
        val partial = Overlays.fromBody(
            6,
            """{"rows":[$coreGb,$ext,{"type":"extensions","package":"$PPSSPP","platform":"psp","extensions":["x"],"note":"hi"}]}""",
        )
        val cases = mapOf(
            "unknown envelope key" to Overlays.build(6, listOf(coreGb), extraEnvelope = "\"unexpected\":1"),
            "unknown body key" to Overlays.fromBody(6, """{"rows":[$coreGb],"note":"x"}"""),
            "unknown row key (valid rows beside it)" to partial,
        )
        var serve: Built = good
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve, etag = null) })
        assertTrue(rig.refresh(good) is RefreshResult.Applied)
        val key = rig.holder.current.stateKey
        val emissions = rig.holder.emissions

        for ((name, built) in cases) {
            serve = built
            assertEquals(name, RefreshResult.Rejected(RejectCode.UNKNOWN_KEY), rig.refresh(built))
        }
        assertEquals("nothing from a rejected file leaks into the view", key, rig.holder.current.stateKey)
        assertEquals(emissions, rig.holder.emissions)
        assertEquals(5L, rig.store.readRecord()!!.ordinal)
    }

    // ---------------------------------------------------------------------------------------------------------
    // T8, T9: offline and clocks

    @Test
    fun t08_offline_and_other_failures_keep_last_good_with_one_request_each() {
        val good = v(5, alt, coreGb)
        val next = v(6, alt, coreGb, ext)
        var failure: (() -> RemoteDefinitionsResponse?)? = null
        val egress = FakeEgress { _, _ ->
            val f = failure
            if (f == null) FakeResponse.ok(good, etag = null) else f()
        }
        val rig = rig(egress)
        assertTrue(rig.refresh(good) is RefreshResult.Applied)
        ageFiles(rig.dir)
        val files = snapshot(rig.dir)
        val key = rig.holder.current.stateKey

        val cases: List<Pair<String, () -> RemoteDefinitionsResponse?>> = listOf(
            "DNS failure (no connection)" to { null },
            "timeout" to { throw IOException("timeout") },
            "429" to { FakeResponse.status(429) },
            "503" to { FakeResponse.status(503) },
            "500" to { FakeResponse.status(500) },
            "404 on a pinned tag" to { FakeResponse.status(404) },
            "captive portal 200 html" to { FakeResponse.ok("<html>login</html>".toByteArray(), etag = null) },
        )
        val expected = listOf(
            RefreshResult.Offline("no response"),
            RefreshResult.Offline("no response"),
            RefreshResult.Offline("HTTP 429"),
            RefreshResult.Offline("HTTP 503"),
            RefreshResult.Offline("HTTP 500"),
            RefreshResult.Rejected(RejectCode.HTTP_STATUS),
            RefreshResult.Rejected(RejectCode.NOT_JSON),
        )
        cases.forEachIndexed { i, (name, answer) ->
            failure = answer
            val before = egress.requests.size
            assertEquals(name, expected[i], rig.refresh(next))
            assertEquals("$name: one request, no retry storm", before + 1, egress.requests.size)
        }

        assertEquals(files, snapshot(rig.dir))
        assertEquals(key, rig.holder.current.stateKey)

        // The launch path never reaches the network: loading and reading make no request.
        val requests = egress.requests.size
        val loaded = RemoteDefinitionsLoader.load(rig.store, bundled)
        assertEquals(LoadStatus.OVERLAY_IN_USE, loaded.status)
        loaded.effective.retroArchCoresFor(RETROARCH, GB)
        rig.holder.current.activityAlternatesFor(FLYCAST, DREAMCAST)
        assertEquals(requests, egress.requests.size)
    }

    @Test
    fun t09_clock_skew_changes_no_decision() {
        val v5 = v(5, alt)
        val v6 = v(6, alt, coreGb)
        val tenYears = 10L * 365 * 24 * 3600 * 1000
        val future = "Fri, 01 Jan 2037 00:00:00 GMT"
        val past = "Mon, 01 Jan 1990 00:00:00 GMT"

        fun scenario(clock: () -> Long, dateHeader: String, name: String): List<RefreshResult> {
            var step = 0
            val skewHeaders = mapOf("Date" to dateHeader, "Last-Modified" to dateHeader, "Expires" to dateHeader, "Age" to "999999999")
            val egress = FakeEgress { _, _ ->
                when (step) {
                    0, 1 -> FakeResponse.ok(v5.bytes, "\"a\"", skewHeaders)
                    2, 4 -> FakeResponse.ok(v6.bytes, "\"b\"", skewHeaders)
                    else -> FakeResponse.ok(v5.bytes, "\"a\"", skewHeaders)
                }
            }
            val rig = Rig(newDir(name), bundled, egress, displayClock = clock)
            val pins = listOf(v5, v5, v6, v5, v6)
            val results = pins.map { built ->
                rig.refresher.refresh(built.pin).also { step++ }
            }
            egress.requests.forEach { request ->
                assertNull("never a time-based validator", request.header("If-Modified-Since"))
                assertNull(request.header("If-Unmodified-Since"))
            }
            return results
        }

        val nominal = scenario({ 1_700_000_000_000L }, future, "nominal")
        val plusTen = scenario({ 1_700_000_000_000L + tenYears }, future, "plus10")
        val minusTen = scenario({ 1_700_000_000_000L - tenYears }, past, "minus10")
        val clockReads = AtomicInteger(0)
        val throwing = scenario({ clockReads.incrementAndGet(); throw IllegalStateException("the clock must not be a decision input") }, future, "throwing")

        assertEquals(
            listOf(
                RefreshResult.Applied(5, v5.rawSha),
                RefreshResult.NoChange,
                RefreshResult.Applied(6, v6.rawSha),
                RefreshResult.Rejected(RejectCode.ROLLBACK),
                RefreshResult.NoChange,
            ),
            nominal,
        )
        assertEquals(nominal, plusTen)
        assertEquals(nominal, minusTen)
        assertEquals(nominal, throwing)
        assertEquals("the clock is read only to stamp the two applies, and a throwing clock does not break them", 2, clockReads.get())
    }

    // ---------------------------------------------------------------------------------------------------------
    // T10, T10b: concurrency

    private class Gate {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun passThrough() {
            reached.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "gate never released" }
        }
    }

    @Test
    fun t10_concurrent_fetches_of_the_same_content_apply_once() {
        val built = v(5, alt, coreGb)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // (a) one refresher: the second call is told another refresh is in flight.
            val gate = Gate()
            val one = rig(FakeEgress { _, _ -> gate.passThrough(); FakeResponse.ok(built) }, name = "a")
            val first = pool.submit(Callable { one.refresh(built) })
            assertTrue(gate.reached.await(20, TimeUnit.SECONDS))
            assertEquals(RefreshResult.SkippedInFlight, one.refresh(built))
            gate.release.countDown()
            assertEquals(RefreshResult.Applied(5, built.rawSha), first.get(20, TimeUnit.SECONDS))
            assertEquals(1, one.store.commitCount)

            // (b) two refreshers on one store, both mid-request: one apply, one no-op, one overlay file.
            val gateA = Gate()
            val gateB = Gate()
            val a = rig(FakeEgress { _, _ -> gateA.passThrough(); FakeResponse.ok(built) }, name = "b")
            val b = a.sibling(FakeEgress { _, _ -> gateB.passThrough(); FakeResponse.ok(built) })
            val fa = pool.submit(Callable { a.refresh(built) })
            val fb = pool.submit(Callable { b.refresh(built.pin) })
            assertTrue(gateA.reached.await(20, TimeUnit.SECONDS))
            assertTrue(gateB.reached.await(20, TimeUnit.SECONDS))
            gateA.release.countDown()
            gateB.release.countDown()
            val results = setOf(fa.get(20, TimeUnit.SECONDS), fb.get(20, TimeUnit.SECONDS))

            assertEquals(setOf(RefreshResult.Applied(5, built.rawSha), RefreshResult.NoChange), results)
            assertEquals("a single write", 1, a.store.commitCount)
            assertEquals(setOf("pointer", "overlay-${built.rawSha}.json"), fileNames(a.dir))
            assertEquals(built.rawSha, a.store.readVerified()!!.record.rawSha256)
            assertEquals(1, a.holder.emissions)
        } finally {
            pool.shutdownNow()
        }
    }

    /** A response whose body blocks on [gate] first, so the test chooses which refresh commits first. */
    private fun gatedBody(built: Built, gate: Gate) =
        FakeResponse(200, built.bytes, mapOf("ETag" to "\"g${built.ordinal}\"")) { gate.passThrough(); built.bytes.inputStream() }

    @Test
    fun t10b_racing_ordinals_5_and_6_end_at_6_in_either_arrival_order() {
        val v5 = v(5, alt)
        val v6 = v(6, alt, coreGb)
        val pool = Executors.newFixedThreadPool(2)
        try {
            for (sixLast in listOf(true, false)) {
                val gate5 = Gate()
                val gate6 = Gate()
                val a = rig(FakeEgress { _, _ -> gatedBody(v5, gate5) }, name = if (sixLast) "six-last" else "five-last")
                val b = a.sibling(FakeEgress { _, _ -> gatedBody(v6, gate6) })
                val f5 = pool.submit(Callable { a.refresh(v5) })
                val f6 = pool.submit(Callable { b.refresh(v6.pin) })
                assertTrue(gate5.reached.await(20, TimeUnit.SECONDS))
                assertTrue(gate6.reached.await(20, TimeUnit.SECONDS))

                val r5: RefreshResult
                val r6: RefreshResult
                if (sixLast) {
                    gate5.release.countDown()
                    r5 = f5.get(20, TimeUnit.SECONDS)
                    gate6.release.countDown()
                    r6 = f6.get(20, TimeUnit.SECONDS)
                    assertEquals(RefreshResult.Applied(5, v5.rawSha), r5)
                    assertEquals(RefreshResult.Applied(6, v6.rawSha), r6)
                } else {
                    gate6.release.countDown()
                    r6 = f6.get(20, TimeUnit.SECONDS)
                    gate5.release.countDown()
                    r5 = f5.get(20, TimeUnit.SECONDS)
                    assertEquals(RefreshResult.Applied(6, v6.rawSha), r6)
                    assertEquals("5 must never replace 6", RefreshResult.Rejected(RejectCode.ROLLBACK), r5)
                }
                assertEquals("final = 6 (sixLast=$sixLast)", 6L, a.store.readVerified()!!.record.ordinal)
                assertEquals(v6.rawSha, a.store.readVerified()!!.record.rawSha256)
                assertEquals(setOf("pointer", "overlay-${v6.rawSha}.json"), fileNames(a.dir))
                assertEquals(1, a.holder.current.retroArchCoresFor(RETROARCH, GB).count { it == "mgba_extra" })
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun t10b_free_running_race_of_four_ordinals_ends_at_the_highest_with_an_intact_store() {
        val builts = (1L..4L).map { n -> v(n, alt, Rows.core(RETROARCH, "gb", "core_$n")) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(10) { round ->
                val first = rig(FakeEgress { _, _ -> null }, name = "stress-$round")
                val refreshers = builts.map { built ->
                    built to first.sibling(FakeEgress { _, _ -> FakeResponse.ok(built) })
                }
                val start = CountDownLatch(1)
                val futures = refreshers.shuffled(java.util.Random(round.toLong())).map { (built, refresher) ->
                    pool.submit(Callable { start.await(); refresher.refresh(built.pin) })
                }
                start.countDown()
                futures.forEach { f ->
                    val r = f.get(30, TimeUnit.SECONDS)
                    assertTrue("round $round: $r", r is RefreshResult.Applied || r == RefreshResult.Rejected(RejectCode.ROLLBACK))
                }
                val live = first.store.readVerified()
                assertNotNull(live)
                assertEquals("round $round", 4L, live!!.record.ordinal)
                assertEquals(setOf("pointer", "overlay-${builts[3].rawSha}.json"), fileNames(first.dir))
                assertTrue(first.holder.current.hasCore(RETROARCH, GB, "core_4"))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // T11: app upgrade with a cached overlay

    private fun cachedOverlayDir(): Pair<File, Built> {
        val built = v(5, alt, coreGb, ext)
        val rig = rig(FakeEgress.always { FakeResponse.ok(built) })
        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        return rig.dir to built
    }

    @Test
    fun t11a_app_upgrade_with_older_bundled_data_uses_the_overlay() {
        val (dir, _) = cachedOverlayDir()
        val loaded = RemoteDefinitionsLoader.load(RemoteDefinitionsStore(dir), TestBundled.small(baselineOrdinal = 0))

        assertEquals(LoadStatus.OVERLAY_IN_USE, loaded.status)
        assertEquals(5L, loaded.effective.overlayOrdinal)
        assertTrue(loaded.effective.hasCore(RETROARCH, GB, "mgba_extra"))
        assertEquals(listOf(FLYCAST_REICAST, "com.reicast.emulator.AltActivity"), loaded.effective.activityAlternatesFor(FLYCAST, DREAMCAST))
        assertTrue(loaded.effective.recognizes(PPSSPP, PSP, "Game.PBP"))
        // The same inputs always give the same view.
        val again = RemoteDefinitionsLoader.load(RemoteDefinitionsStore(dir), TestBundled.small(baselineOrdinal = 0))
        assertEquals(loaded.effective.stateKey, again.effective.stateKey)
        assertEquals(loaded.effective.render(), again.effective.render())
    }

    @Test
    fun t11b_app_upgrade_with_newer_bundled_data_retires_the_overlay() {
        val (dir, _) = cachedOverlayDir()
        val newer = TestBundled.small(baselineOrdinal = 5)
        val loaded = RemoteDefinitionsLoader.load(RemoteDefinitionsStore(dir), newer)

        assertEquals(LoadStatus.OVERLAY_RETIRED, loaded.status)
        assertTrue(loaded.effective.overlayRetired)
        assertNull(loaded.effective.overlayOrdinal)
        assertEquals(DefinitionsMerge.merge(newer, null).stateKey, loaded.effective.stateKey)
        assertFalse(loaded.effective.hasCore(RETROARCH, GB, "mgba_extra"))
    }

    @Test
    fun t11c_an_entry_now_duplicated_in_the_bundled_data_is_not_listed_twice_and_bundled_wins() {
        val (dir, _) = cachedOverlayDir()
        val bundledNow = TestBundled.small(extraGbCores = listOf("mgba_extra"))
        val loaded = RemoteDefinitionsLoader.load(RemoteDefinitionsStore(dir), bundledNow)

        assertEquals(LoadStatus.OVERLAY_IN_USE, loaded.status)
        assertEquals(listOf("gambatte", "mgba", "mgba_extra"), loaded.effective.retroArchCoresFor(RETROARCH, GB))
        assertFalse("provenance stays bundled", loaded.effective.coreItems(RETROARCH, GB).last().fromOverlay)
    }

    @Test
    fun t11d_a_cached_schema_the_app_no_longer_reads_is_ignored_without_a_crash() {
        val (dir, _) = cachedOverlayDir()
        val store = RemoteDefinitionsStore(dir)
        val loaded = RemoteDefinitionsLoader.load(store, bundled, supportedSchema = 0)

        assertEquals(LoadStatus.SCHEMA_UNSUPPORTED, loaded.status)
        assertEquals(DefinitionsMerge.merge(bundled, null).stateKey, loaded.effective.stateKey)
        assertNotNull("kept for the next upgrade", store.readRecord())
    }

    @Test
    fun t11e_a_cached_overlay_that_no_longer_fits_the_new_bundled_data_is_ignored_not_half_applied() {
        val (dir, _) = cachedOverlayDir()
        val store = RemoteDefinitionsStore(dir)
        val withoutPpsspp = bundled.copy(seeds = bundled.seeds.filterNot { it.packageName == PPSSPP })
        val loaded = RemoteDefinitionsLoader.load(store, withoutPpsspp)

        assertEquals(LoadStatus.INVALID_FOR_THIS_BUILD, loaded.status)
        assertEquals(DefinitionsMerge.merge(withoutPpsspp, null).stateKey, loaded.effective.stateKey)
        assertFalse(loaded.effective.hasCore(RETROARCH, GB, "mgba_extra"))
        assertNotNull(store.readRecord())
    }

    // ---------------------------------------------------------------------------------------------------------
    // T12: user overrides survive

    /** Stand-in for the Room override table: the refresh path is never given a handle to it. */
    private class FakeOverrides {
        val rows = linkedMapOf("game-1" to "mgba_extra", "game-2" to "gambatte")
        fun picture() = rows.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    @Test
    fun t12_user_overrides_survive_a_refetch_and_an_update_that_removes_the_chosen_core() {
        val withExtra = v(5, coreGb)
        val without = v(6, alt)
        var serve: Built = withExtra
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve, etag = null) })
        val overrides = FakeOverrides()
        assertTrue(rig.refresh(withExtra) is RefreshResult.Applied)
        val picture = overrides.picture()

        // (a) a re-fetch of the same content
        assertEquals(RefreshResult.NoChange, rig.refresh(withExtra))
        assertEquals("rows byte-equal", picture, overrides.picture())
        assertTrue(rig.holder.current.hasCore(RETROARCH, GB, overrides.rows.getValue("game-1")))

        // (b) an update that drops the chosen candidate
        serve = without
        assertTrue(rig.refresh(without) is RefreshResult.Applied)
        assertEquals("the stored id is kept, never remapped", picture, overrides.picture())
        assertFalse("the candidate now reads unavailable", rig.holder.current.hasCore(RETROARCH, GB, "mgba_extra"))
        assertTrue("an untouched choice still resolves", rig.holder.current.hasCore(RETROARCH, GB, overrides.rows.getValue("game-2")))
    }

    // ---------------------------------------------------------------------------------------------------------
    // T14: rollback and ordinal reuse

    @Test
    fun t14_rollback_and_ordinal_reuse_are_refused_and_change_nothing() {
        val ten = v(10, alt, coreGb)
        val nine = v(9, alt, ext)
        val tenOther = v(10, alt, ext)
        var serve: Built = ten
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve, etag = null) })
        assertTrue(rig.refresh(ten) is RefreshResult.Applied)
        ageFiles(rig.dir)
        val files = snapshot(rig.dir)
        val key = rig.holder.current.stateKey

        serve = nine
        assertEquals(RefreshResult.Rejected(RejectCode.ROLLBACK), rig.refresh(nine))
        serve = tenOther
        assertEquals(RefreshResult.Rejected(RejectCode.ORDINAL_REUSE), rig.refresh(tenOther))

        assertEquals(files, snapshot(rig.dir))
        assertEquals(key, rig.holder.current.stateKey)
        assertEquals(10L, rig.store.readRecord()!!.ordinal)

        // The bundled baseline is a floor too: a first fetch at or below it is a rollback.
        val floorRig = rig(FakeEgress { _, _ -> FakeResponse.ok(v(7, alt), etag = null) }, bundled = TestBundled.small(baselineOrdinal = 7), name = "floor")
        assertEquals(RefreshResult.Rejected(RejectCode.ROLLBACK), floorRig.refresh(v(7, alt)))
        assertNull(floorRig.store.readRecord())
    }

    // ---------------------------------------------------------------------------------------------------------
    // T15: size

    @Test
    fun t15_oversize_is_refused_at_the_cap_for_declared_chunked_and_gzip_bodies() {
        val good = v(5, alt)
        val next = v(6, alt, coreGb)
        val cap = RemoteDefinitionsLimits.MAX_BYTES
        val served = AtomicInteger(0)
        var mode = ""
        var lastResponse: FakeResponse? = null
        val egress = FakeEgress { _, _ ->
            val response = when (mode) {
                "declared" -> FakeResponse(200, ByteArray(0), emptyMap(), declaredLength = (cap + 1).toLong())
                "chunked" -> FakeResponse(200, ByteArray(0), emptyMap(), declaredLength = -1) { EndlessStream(served) }
                "gzip-expansion" -> FakeResponse.gzip(ByteArray(8 * 1024 * 1024) { '0'.code.toByte() })
                "gzip-compressed-too-big" -> {
                    val noise = ByteArray(cap * 2).also { java.util.Random(3).nextBytes(it) }
                    FakeResponse.gzip(noise)
                }
                "exactly-cap-plus-one" -> FakeResponse.ok(ByteArray(cap + 1) { 'x'.code.toByte() })
                "exactly-cap" -> FakeResponse.ok(ByteArray(cap) { 'x'.code.toByte() })
                else -> FakeResponse.ok(good)
            }
            lastResponse = response
            response
        }
        val rig = rig(egress)
        assertTrue(rig.refresh(good) is RefreshResult.Applied)
        val pointer = rig.store.readRecord()
        val files = fileNames(rig.dir)

        mode = "declared"
        assertEquals(RefreshResult.Rejected(RejectCode.SIZE), rig.refresh(next))
        assertEquals("a declared oversize body is never opened", 0, lastResponse!!.bodyCalls.get())

        mode = "chunked"
        assertEquals(RefreshResult.Rejected(RejectCode.SIZE), rig.refresh(next))
        assertTrue("reads stayed bounded: ${served.get()}", served.get() <= cap + 8 * 1024)

        mode = "gzip-expansion"
        assertEquals(RefreshResult.Rejected(RejectCode.SIZE), rig.refresh(next))
        mode = "gzip-compressed-too-big"
        assertEquals(RefreshResult.Rejected(RejectCode.SIZE), rig.refresh(next))
        mode = "exactly-cap-plus-one"
        assertEquals(RefreshResult.Rejected(RejectCode.SIZE), rig.refresh(next))
        mode = "exactly-cap"
        assertEquals("at the cap it is read, then refused as not JSON", RefreshResult.Rejected(RejectCode.NOT_JSON), rig.refresh(next))

        assertEquals(pointer, rig.store.readRecord())
        assertEquals("no temp left", files, fileNames(rig.dir))
    }

    // ---------------------------------------------------------------------------------------------------------
    // T16: redirects

    private val tagUrl = "https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/defs-5/definitions.json"

    @Test
    fun t16_redirects_off_host_to_http_or_past_two_hops_are_refused_without_reading_a_body() {
        val built = v(5, alt)
        val refused = mapOf(
            "other host" to "https://evil.example/Mimir-apk/mimir-emulator-definitions/defs-5/definitions.json",
            "http scheme" to "http://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/defs-5/definitions.json",
            "other path on the same host" to "https://raw.githubusercontent.com/someone/else/main/payload.json",
            "a branch instead of a tag" to "https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/main/definitions.json",
            "user info" to "https://user@raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/defs-5/definitions.json",
            "a query string" to "$tagUrl?token=1",
        )
        for ((name, target) in refused) {
            val egress = FakeEgress.sequence({ FakeResponse.redirect(target) }, { FakeResponse.ok(built) })
            val rig = rig(egress, name = "r-" + name.replace(' ', '-'))
            assertEquals(name, RefreshResult.Rejected(RejectCode.REDIRECT), rig.refresh(built))
            assertEquals("$name: the redirect target was never requested", 1, egress.requests.size)
            assertEquals(0, (egress.responses[0] as FakeResponse).bodyCalls.get())
            assertNull(rig.store.readRecord())
        }

        // More than two hops.
        val loop = FakeEgress { _, _ -> FakeResponse.redirect(tagUrl, 301) }
        val loopRig = rig(loop, name = "loop")
        assertEquals(RefreshResult.Rejected(RejectCode.REDIRECT), loopRig.refresh(built))
        assertEquals("the original plus two hops, then stop", 3, loop.requests.size)
        loop.responses.forEach { assertEquals(0, (it as FakeResponse).bodyCalls.get()) }

        // Positive controls: one and two allowed hops work, and a relative Location is resolved safely.
        val one = FakeEgress.sequence({ FakeResponse.redirect(tagUrl) }, { FakeResponse.ok(built) })
        assertTrue(rig(one, name = "one-hop").refresh(built) is RefreshResult.Applied)
        val two = FakeEgress.sequence({ FakeResponse.redirect(tagUrl, 301) }, { FakeResponse.redirect(tagUrl, 307) }, { FakeResponse.ok(built) })
        assertTrue(rig(two, name = "two-hops").refresh(built) is RefreshResult.Applied)
        val relative = FakeEgress.sequence(
            { FakeResponse.redirect("/Mimir-apk/mimir-emulator-definitions/defs-5/definitions.json") },
            { FakeResponse.ok(built) },
        )
        assertTrue(rig(relative, name = "relative").refresh(built) is RefreshResult.Applied)
        assertEquals(tagUrl, one.requests[0].url)
    }

    // ---------------------------------------------------------------------------------------------------------
    // T17: allow-list violations, one case per rule

    private fun rowWith(extraJson: String) =
        """{"type":"activity_alternate","package":"$FLYCAST","platform":"dreamcast","activity":"com.x.Main",$extraJson}"""

    @Test
    fun t17_every_allow_list_rule_rejects_the_whole_file() {
        val longClass = "com." + "a".repeat(170) + ".Main"
        val cases: List<Triple<String, String, RejectCode>> = listOf(
            // package and platform
            Triple("package outside the seeds", Rows.alt("com.evil.app", "dreamcast", "com.evil.Main"), RejectCode.UNKNOWN_PACKAGE),
            Triple("package with a path", Rows.alt("../../x", "dreamcast", "com.evil.Main"), RejectCode.UNKNOWN_PACKAGE),
            Triple("platform the package does not serve", Rows.alt(FLYCAST, "gb", "com.x.Main"), RejectCode.PLATFORM_NOT_SERVED),
            Triple("malformed platform", Rows.alt(FLYCAST, "../dreamcast", "com.x.Main"), RejectCode.BAD_PLATFORM),
            Triple("alternate on a recognise-only package", Rows.alt(RECOGNIZE_ONLY, "ps2", "com.x.Main"), RejectCode.NOT_DISPATCHABLE),
            // activity names: path and shell characters
            Triple("slash in class name", Rows.alt(FLYCAST, "dreamcast", "com.x/Main"), RejectCode.BAD_CLASS_NAME),
            Triple("path traversal", Rows.alt(FLYCAST, "dreamcast", "../../x"), RejectCode.BAD_CLASS_NAME),
            Triple("absolute path", Rows.alt(FLYCAST, "dreamcast", "/sdcard/x"), RejectCode.BAD_CLASS_NAME),
            Triple("content uri", Rows.alt(FLYCAST, "dreamcast", "content://media/x"), RejectCode.BAD_CLASS_NAME),
            Triple("file uri", Rows.alt(FLYCAST, "dreamcast", "file:///etc/passwd"), RejectCode.BAD_CLASS_NAME),
            Triple("semicolon", Rows.alt(FLYCAST, "dreamcast", "com.x.Main;reboot"), RejectCode.BAD_CLASS_NAME),
            Triple("space", Rows.alt(FLYCAST, "dreamcast", "com.x.Main reboot"), RejectCode.BAD_CLASS_NAME),
            Triple("dollar", Rows.alt(FLYCAST, "dreamcast", "com.x.Main\$Inner"), RejectCode.BAD_CLASS_NAME),
            Triple("backtick", Rows.alt(FLYCAST, "dreamcast", "com.x.`id`"), RejectCode.BAD_CLASS_NAME),
            Triple("unknown placeholder", Rows.alt(FLYCAST, "dreamcast", "com.x.{tags.name}"), RejectCode.BAD_CLASS_NAME),
            Triple("relative class", Rows.alt(FLYCAST, "dreamcast", ".MainActivity"), RejectCode.BAD_CLASS_NAME),
            Triple("single segment", Rows.alt(FLYCAST, "dreamcast", "Main"), RejectCode.BAD_CLASS_NAME),
            Triple("trailing dot", Rows.alt(FLYCAST, "dreamcast", "com.x."), RejectCode.BAD_CLASS_NAME),
            Triple("digit-led segment", Rows.alt(FLYCAST, "dreamcast", "com.1x.Main"), RejectCode.BAD_CLASS_NAME),
            Triple("empty class", Rows.alt(FLYCAST, "dreamcast", ""), RejectCode.BAD_CLASS_NAME),
            Triple("over 160 characters", Rows.alt(FLYCAST, "dreamcast", longClass), RejectCode.BAD_CLASS_NAME),
            Triple("escaped newline", Rows.alt(FLYCAST, "dreamcast", "com.x.Main\\nreboot"), RejectCode.BAD_STRING),
            Triple("string over 256", Rows.alt(FLYCAST, "dreamcast", "com." + "a".repeat(300)), RejectCode.STRING_TOO_LONG),
            // fields the scope ruling forbids (no such field exists in the schema)
            Triple("action", rowWith("\"action\":\"android.intent.action.VIEW\""), RejectCode.FORBIDDEN_FIELD),
            Triple("path-like extra", rowWith("\"extras\":{\"ROM\":\"/storage/emulated/0/rom.iso\"}"), RejectCode.FORBIDDEN_FIELD),
            Triple("content:// extra", rowWith("\"extras\":{\"k\":\"content://media/external/x\"}"), RejectCode.FORBIDDEN_FIELD),
            Triple("dot-dot extra", rowWith("\"extras\":{\"k\":\"../x\"}"), RejectCode.FORBIDDEN_FIELD),
            Triple("placeholder extra", rowWith("\"extras\":{\"k\":\"{tags.name}\"}"), RejectCode.FORBIDDEN_FIELD),
            Triple("DISPATCHER_OWNED_PAYLOAD", rowWith("\"pathStrategy\":\"DISPATCHER_OWNED_PAYLOAD\""), RejectCode.FORBIDDEN_FIELD),
            Triple("kill field", rowWith("\"killPackageProcesses\":true"), RejectCode.FORBIDDEN_FIELD),
            Triple("capability promotion", rowWith("\"capability\":\"DIRECT_PAYLOAD\""), RejectCode.FORBIDDEN_FIELD),
            Triple("component", rowWith("\"component\":\"x/y\""), RejectCode.FORBIDDEN_FIELD),
            Triple("url", rowWith("\"url\":\"https://evil.example/x\""), RejectCode.FORBIDDEN_FIELD),
            Triple("am start arguments", rowWith("\"amStartArguments\":\"-n x/y -d {file.uri}\""), RejectCode.FORBIDDEN_FIELD),
            Triple("unknown key that is not on the forbidden list", rowWith("\"placeholder\":\"{file.mime}\""), RejectCode.UNKNOWN_KEY),
            Triple("a new direct-launch entry", """{"type":"direct_launch","package":"$FLYCAST","platform":"dreamcast"}""", RejectCode.BAD_ROW_TYPE),
            Triple("a new emulator entry", """{"type":"emulator","package":"com.evil.app","platform":"dreamcast"}""", RejectCode.BAD_ROW_TYPE),
            Triple("missing field", """{"type":"activity_alternate","package":"$FLYCAST","platform":"dreamcast"}""", RejectCode.BAD_ENVELOPE),
            // cores
            Triple("core with traversal", Rows.core(RETROARCH, "gb", "../../evil"), RejectCode.BAD_CORE),
            Triple("core with a slash", Rows.core(RETROARCH, "gb", "a/b"), RejectCode.BAD_CORE),
            Triple("core in upper case", Rows.core(RETROARCH, "gb", "UPPER"), RejectCode.BAD_CORE),
            Triple("core led by a hyphen", Rows.core(RETROARCH, "gb", "-rf"), RejectCode.BAD_CORE),
            Triple("core over 64", Rows.core(RETROARCH, "gb", "x".repeat(65)), RejectCode.BAD_CORE),
            Triple("core on a package without cores", Rows.core(FLYCAST, "dreamcast", "flycast"), RejectCode.PLATFORM_NOT_SERVED),
            Triple("core for a platform RetroArch does not serve", Rows.core(RETROARCH, "psp", "ppsspp"), RejectCode.PLATFORM_NOT_SERVED),
            // recognition rules
            Triple("extension in upper case", Rows.ext(PPSSPP, "psp", "ISO"), RejectCode.BAD_EXTENSION),
            Triple("extension with a path", Rows.ext(PPSSPP, "psp", "../bin"), RejectCode.BAD_EXTENSION),
            Triple("extension with a dot", Rows.ext(PPSSPP, "psp", "a.b"), RejectCode.BAD_EXTENSION),
            Triple("extension too long", Rows.ext(PPSSPP, "psp", "abcdefghijk"), RejectCode.BAD_EXTENSION),
            Triple("empty extension list", Rows.ext(PPSSPP, "psp"), RejectCode.BAD_EXTENSION),
            Triple("17 extensions", Rows.ext(PPSSPP, "psp", *(1..17).map { "e$it" }.toTypedArray()), RejectCode.BAD_EXTENSION),
            Triple("repeated extension", Rows.ext(PPSSPP, "psp", "iso", "iso"), RejectCode.DUPLICATE_ROW),
            Triple("regex nested quantifier (a+)+", Rows.regex(PPSSPP, "psp", "^(a+)+\$"), RejectCode.BAD_REGEX),
            Triple("regex two unbounded repeats", Rows.regex(PPSSPP, "psp", "^.*.*\$"), RejectCode.BAD_REGEX),
            Triple("regex star over a group", Rows.regex(PPSSPP, "psp", "^(?:a|b)*\$"), RejectCode.BAD_REGEX),
            Triple("regex counted repeat", Rows.regex(PPSSPP, "psp", "^a{1,}\$"), RejectCode.BAD_REGEX),
            Triple("regex capturing group", Rows.regex(PPSSPP, "psp", "^(.*)\\\\.iso\$"), RejectCode.BAD_REGEX),
            Triple("regex lookahead", Rows.regex(PPSSPP, "psp", "^(?=a)a\$"), RejectCode.BAD_REGEX),
            Triple("regex backreference", Rows.regex(PPSSPP, "psp", "^(?:a)\\\\1\$"), RejectCode.BAD_REGEX),
            Triple("regex without anchors", Rows.regex(PPSSPP, "psp", ".*\\\\.iso"), RejectCode.BAD_REGEX),
            Triple("regex class shorthand", Rows.regex(PPSSPP, "psp", "^\\\\d+\$"), RejectCode.BAD_REGEX),
            Triple("regex over 100 characters", Rows.regex(PPSSPP, "psp", "^" + "a".repeat(100) + "\$"), RejectCode.BAD_REGEX),
        )
        val good = v(5, alt)
        var serve: Built = good
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve, etag = null) })
        assertTrue(rig.refresh(good) is RefreshResult.Applied)
        val pointer = rig.store.readRecord()
        val key = rig.holder.current.stateKey
        val emissions = rig.holder.emissions

        for ((name, bad, code) in cases) {
            // One valid row beside the bad one: the file must be refused whole, not applied minus the bad row.
            val built = Overlays.build(9, listOf(coreGb, bad))
            serve = built
            assertEquals(name, RefreshResult.Rejected(code), rig.refresh(built))
            assertEquals("$name: pointer", pointer, rig.store.readRecord())
            assertEquals("$name: view", key, rig.holder.current.stateKey)
            assertEquals("$name: dispatch authority", bundled.dispatchPackages, rig.holder.current.dispatchPackages)
        }
        assertEquals(emissions, rig.holder.emissions)

        val repeated = Overlays.build(9, listOf(alt, alt))
        serve = repeated
        assertEquals(RefreshResult.Rejected(RejectCode.DUPLICATE_ROW), rig.refresh(repeated))
        assertEquals(pointer, rig.store.readRecord())
    }

    @Test
    fun t17_row_count_and_alternate_count_caps() {
        val good = v(5, alt)
        var serve: Built = good
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve, etag = null) })
        assertTrue(rig.refresh(good) is RefreshResult.Applied)

        val tooMany = Overlays.build(9, List(RemoteDefinitionsLimits.MAX_ROWS + 1) { alt })
        serve = tooMany
        assertEquals(RefreshResult.Rejected(RejectCode.TOO_MANY_ROWS), rig.refresh(tooMany))

        val fiveAlternates = Overlays.build(9, (1..5).map { Rows.alt(FLYCAST, "dreamcast", "com.reicast.emulator.Alt$it") })
        serve = fiveAlternates
        assertEquals(RefreshResult.Rejected(RejectCode.TOO_MANY_ROWS), rig.refresh(fiveAlternates))

        // The boundary itself is fine: exactly 256 distinct rows, and 4 alternates.
        val atCap = Overlays.build(9, (1..RemoteDefinitionsLimits.MAX_ROWS).map { Rows.core(RETROARCH, "gb", "core_$it") })
        serve = atCap
        assertTrue(rig.refresh(atCap) is RefreshResult.Applied)
        val fourAlternates = Overlays.build(10, (1..4).map { Rows.alt(FLYCAST, "dreamcast", "com.reicast.emulator.Alt$it") })
        serve = fourAlternates
        assertTrue(rig.refresh(fourAlternates) is RefreshResult.Applied)
    }

    // ---------------------------------------------------------------------------------------------------------
    // T18, T19: crashes and corruption

    @Test
    fun t18_a_crash_at_any_step_leaves_the_old_or_the_new_overlay_whole_and_converges() {
        val v5 = v(5, alt)
        val v6 = v(6, alt, coreGb)
        for (step in StoreStep.values()) {
            var crashAt: StoreStep? = null
            val dir = newDir("crash-$step")
            val live = Rig(dir, bundled, FakeEgress.sequence({ FakeResponse.ok(v5) }, { FakeResponse.ok(v6) }),
                faultHook = { if (it == crashAt) throw SimulatedCrash() })
            assertTrue(live.refresh(v5) is RefreshResult.Applied)

            crashAt = step
            try {
                live.refresh(v6)
                throw AssertionError("the simulated crash at $step did not fire")
            } catch (_: SimulatedCrash) {
                // the process "died" here
            }

            // Restart: a new store over the same directory, then the boot load.
            val restarted = RemoteDefinitionsStore(dir)
            assertTrue("$step: stale temp files are cleaned at start", fileNames(dir).none { it.endsWith(".tmp") })
            val loaded = RemoteDefinitionsLoader.load(restarted, bundled)
            val expectedOrdinal = if (step == StoreStep.AFTER_POINTER_SWITCH) 6L else 5L
            assertEquals("$step: boot sees one whole overlay", LoadStatus.OVERLAY_IN_USE, loaded.status)
            assertEquals("$step", expectedOrdinal, loaded.effective.overlayOrdinal)
            assertEquals("$step", expectedOrdinal == 6L, loaded.effective.hasCore(RETROARCH, GB, "mgba_extra"))

            // The next refresh converges.
            val again = Rig(dir, bundled, FakeEgress.always { FakeResponse.ok(v6) }, store = restarted)
            val result = again.refresh(v6)
            assertEquals(
                "$step",
                if (step == StoreStep.AFTER_POINTER_SWITCH) RefreshResult.NoChange else RefreshResult.Applied(6, v6.rawSha),
                result,
            )
            assertEquals(RefreshResult.NoChange, again.refresh(v6))
            assertEquals("$step", setOf("pointer", "overlay-${v6.rawSha}.json"), fileNames(dir))
        }
    }

    @Test
    fun t18_a_crash_during_the_very_first_apply_leaves_bundled_data_and_no_debris() {
        val v5 = v(5, alt)
        for (step in listOf(StoreStep.AFTER_TMP_WRITE, StoreStep.AFTER_OVERLAY_RENAME, StoreStep.BEFORE_POINTER_SWITCH)) {
            val dir = newDir("first-$step")
            val live = Rig(dir, bundled, FakeEgress.always { FakeResponse.ok(v5) }, faultHook = { if (it == step) throw SimulatedCrash() })
            try {
                live.refresh(v5)
                throw AssertionError("no crash at $step")
            } catch (_: SimulatedCrash) {
            }
            val restarted = RemoteDefinitionsStore(dir)
            val loaded = RemoteDefinitionsLoader.load(restarted, bundled)
            assertEquals("$step", LoadStatus.NO_OVERLAY, loaded.status)
            assertEquals("$step", DefinitionsMerge.merge(bundled, null).stateKey, loaded.effective.stateKey)
            assertTrue(fileNames(dir).none { it.endsWith(".tmp") })
            assertEquals(RefreshResult.Applied(5, v5.rawSha), Rig(dir, bundled, FakeEgress.always { FakeResponse.ok(v5) }, store = restarted).refresh(v5))
        }
    }

    @Test
    fun t19_a_corrupted_stored_copy_falls_back_to_bundled_clears_the_pointer_and_reapplies() {
        val built = v(5, alt, coreGb)
        val damage: Map<String, (File) -> Unit> = mapOf(
            "one flipped byte" to { f: File -> f.writeBytes(f.readBytes().also { it[20] = (it[20].toInt() xor 0x01).toByte() }) },
            "truncated" to { f: File -> f.writeBytes(f.readBytes().copyOf(30)) },
            "deleted" to { f: File -> assertTrue(f.delete()) },
            "emptied" to { f: File -> f.writeBytes(ByteArray(0)) },
        )
        for ((name, hurt) in damage) {
            val dir = newDir("corrupt-" + name.replace(' ', '-'))
            val rig = Rig(dir, bundled, FakeEgress.always { FakeResponse.ok(built) })
            assertTrue(rig.refresh(built) is RefreshResult.Applied)
            hurt(rig.store.overlayFile(built.rawSha))

            val restarted = RemoteDefinitionsStore(dir)
            val loaded = RemoteDefinitionsLoader.load(restarted, bundled)

            assertEquals(name, LoadStatus.CORRUPT_CLEARED, loaded.status)
            assertEquals(name, DefinitionsMerge.merge(bundled, null).stateKey, loaded.effective.stateKey)
            assertFalse("$name: pointer cleared", restarted.hasPointerFile())
            val again = Rig(dir, bundled, FakeEgress.always { FakeResponse.ok(built) }, store = restarted)
            assertEquals("$name: the next refresh re-applies", RefreshResult.Applied(5, built.rawSha), again.refresh(built))
            assertTrue(built.bytes.contentEquals(restarted.readVerified()!!.bytes))
        }

        // A garbage pointer is treated the same way.
        val dir = newDir("garbage-pointer")
        val rig = Rig(dir, bundled, FakeEgress.always { FakeResponse.ok(built) })
        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        File(dir, "pointer").writeText("rd1 not a pointer\n")
        val loaded = RemoteDefinitionsLoader.load(RemoteDefinitionsStore(dir), bundled)
        assertEquals(LoadStatus.CORRUPT_CLEARED, loaded.status)
    }

    // ---------------------------------------------------------------------------------------------------------
    // T20, T21: equal behaviour is silent

    @Test
    fun t20_same_entries_in_a_different_order_makes_no_downstream_emission() {
        val first = v(5, alt, coreGb, ext)
        val reordered = v(6, ext, coreGb, alt) // different bytes, different canonical hash, same entries
        val prettyAgain = Overlays.build(7, listOf(alt, coreGb, ext), pretty = true)
        var serve: Built = first
        val rig = rig(FakeEgress { _, _ -> FakeResponse.ok(serve, etag = null) })

        assertTrue(rig.refresh(first) is RefreshResult.Applied)
        val emissions = rig.holder.emissions
        val key = rig.holder.current.stateKey
        val render = rig.holder.current.render()
        assertTrue(first.rawSha != reordered.rawSha)

        serve = reordered
        val result = rig.refresh(reordered)
        assertTrue("applied by ordinal: $result", result is RefreshResult.Applied)
        serve = prettyAgain
        assertTrue(rig.refresh(prettyAgain) is RefreshResult.Applied)

        assertEquals("effective state equal", key, rig.holder.current.stateKey)
        assertEquals(render, rig.holder.current.render())
        assertEquals("no emission for equal behaviour", emissions, rig.holder.emissions)
        assertEquals("the ordinal still advances so downgrade protection holds", 7L, rig.store.readRecord()!!.ordinal)
        assertEquals(7L, rig.holder.current.overlayOrdinal)
    }

    @Test
    fun t21_re_applying_a_validated_overlay_twice_writes_nothing() {
        val built = v(5, alt, coreGb)
        val rig = rig(FakeEgress.always { FakeResponse.ok(built) })
        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        ageFiles(rig.dir)
        val files = snapshot(rig.dir)
        val commits = rig.store.commitCount
        val emissions = rig.holder.emissions

        // Boot-time re-apply, three times, and a direct publish of the same merged view.
        repeat(3) {
            val loaded = RemoteDefinitionsLoader.load(rig.store, bundled)
            assertFalse(rig.holder.publish(loaded.effective))
        }
        // A refresh answered with a different encoding and no validator is still the same identity.
        assertEquals(RefreshResult.NoChange, rig.refresh(built))

        assertEquals(files, snapshot(rig.dir))
        assertEquals("storage write count = 0", commits, rig.store.commitCount)
        assertEquals(emissions, rig.holder.emissions)
    }

    // ---------------------------------------------------------------------------------------------------------
    // T22, T23

    @Test
    fun t22_opt_in_off_means_no_egress_ever_including_during_scan_and_launch_flows() {
        val built = v(5, alt, coreGb)
        val egress = FakeEgress.always { FakeResponse.ok(built) }
        val rig = Rig(newDir(), bundled, egress, enabled = false)

        assertEquals(RefreshResult.NotEnabled, rig.refresh(built))
        assertEquals(RefreshResult.NotEnabled, rig.refresh(built))
        assertTrue("nothing was even written", fileNames(rig.dir).isEmpty())

        // The scan and launch flows: boot load, then every read the launcher makes.
        val loaded = RemoteDefinitionsLoader.load(rig.store, bundled)
        loaded.effective.retroArchCoresFor(RETROARCH, GB)
        loaded.effective.activityAlternatesFor(FLYCAST, DREAMCAST)
        loaded.effective.recognizes(PPSSPP, PSP, "game.cso")
        rig.holder.current.dispatchPackages

        assertEquals("the transport was never opened", 0, egress.requests.size)

        // Turning it on is what lets a request out.
        rig.enabled = true
        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        assertEquals(1, egress.requests.size)
    }

    @Test
    fun t23_the_single_file_design_has_no_index_to_drift() {
        val built = v(5, alt)
        val egress = FakeEgress.always { FakeResponse.ok(built) }
        val rig = rig(egress)

        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        assertEquals("one refresh is one file, never an index plus N files", 1, egress.requests.size)
        assertEquals(tagUrl, egress.requests[0].url)

        // The data can never choose the next fetch's origin (the Cocoon index's baseUri).
        val withBaseUri = Overlays.build(6, listOf(alt), extraEnvelope = "\"baseUri\":\"https://evil.example/\"")
        val rig2 = rig(FakeEgress.always { FakeResponse.ok(withBaseUri) }, name = "baseuri")
        assertEquals(RefreshResult.Rejected(RejectCode.FORBIDDEN_FIELD), rig2.refresh(withBaseUri))
        val withList = Overlays.build(6, listOf(alt), extraEnvelope = "\"platformList\":[]")
        val rig3 = rig(FakeEgress.always { FakeResponse.ok(withList) }, name = "platformlist")
        assertEquals(RefreshResult.Rejected(RejectCode.UNKNOWN_KEY), rig3.refresh(withList))
    }

    // ---------------------------------------------------------------------------------------------------------
    // Guards beyond the matrix

    @Test
    fun a_pin_that_is_not_an_immutable_tag_or_commit_is_refused_before_any_request() {
        val built = v(5, alt)
        val egress = FakeEgress.always { FakeResponse.ok(built) }
        val rig = rig(egress)
        for (ref in listOf("main", "master", "refs/heads/main", "defs-", "defs-1/../../x", "ABCDEF".repeat(7).take(40), "")) {
            assertEquals(ref, RefreshResult.Rejected(RejectCode.BAD_PIN), rig.refresher.refresh(RemoteDefinitionsPin(ref, built.canonicalSha)))
        }
        assertEquals(RefreshResult.Rejected(RejectCode.BAD_PIN), rig.refresher.refresh(RemoteDefinitionsPin("defs-5", "nothex")))
        assertEquals(0, egress.requests.size)
        assertNotNull("a 40-hex commit is accepted", RemoteDefinitionsEndpoint.urlFor("0123456789abcdef0123456789abcdef01234567"))
    }

    @Test
    fun a_request_carries_no_cookie_no_credential_and_no_device_identifier() {
        val built = v(5, alt)
        val egress = FakeEgress.always { FakeResponse.ok(built) }
        val rig = rig(egress)
        assertTrue(rig.refresh(built) is RefreshResult.Applied)
        val names = egress.requests.single().headers.keys.map { it.lowercase() }.toSet()
        assertEquals(setOf("accept", "accept-encoding", "user-agent"), names)
        assertEquals("Mimir-EmulatorDefinitions/1", egress.requests.single().header("User-Agent"))
    }
}
