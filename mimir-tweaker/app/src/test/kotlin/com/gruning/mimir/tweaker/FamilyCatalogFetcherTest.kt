package com.gruning.mimir.tweaker

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FamilyCatalogFetcherTest {
    private val pair = keyPair()
    private val keyBase64 = b64(checkNotNull(ed25519RawPublicKey(pair.public.encoded)))
    private val sink = FakeSink()
    private val transport = FakeTransport()
    private val logs = CopyOnWriteArrayList<String>()
    private var nanos = 1_000_000_000L

    private fun fetcher(
        url: String = CATALOG_URL,
        key: String = keyBase64,
    ) = FamilyCatalogFetcher(
        catalogUrl = url,
        publicKeyBase64 = key,
        sink = sink,
        transport = transport,
        userAgent = UA,
        nanoClock = { nanos },
        log = { logs += it },
    )

    private val catalog = "{\"schema\":1,\"revision\":8,\"families\":[]}".toByteArray()

    private fun serveValidPair(bytes: ByteArray = catalog, etag: String? = "\"abc123\"") {
        transport.script(CATALOG_URL) { ok(CATALOG_URL, bytes, etag) }
        transport.script(SIG_URL) { ok(SIG_URL, (b64(sign(pair, bytes)) + "\n").toByteArray(), null) }
    }

    @Test
    fun `200 with a valid signature hands the exact bytes to the sink once`() {
        serveValidPair()

        assertEquals(FamilyCatalogFetchOutcome.ACCEPTED, fetcher().fetchOnce())

        assertEquals(1, sink.promoted.size)
        assertArrayEquals(catalog, sink.promoted.single().first)
        assertArrayEquals(sign(pair, catalog), sink.promoted.single().second)
        assertEquals(listOf(CATALOG_URL, SIG_URL), transport.calls.map { it.url })
        transport.calls.forEach {
            assertEquals(UA, it.userAgent)
            assertNull(it.ifNoneMatch)
            assertTrue(it.timeoutMillis in 1..FamilyCatalogFetcher.DEADLINE_MILLIS.toInt())
        }
        assertEquals(FamilyCatalogFetcher.MAX_CATALOG_BYTES, transport.calls[0].maxBodyBytes)
        assertEquals(FamilyCatalogSignature.MAX_SIGNATURE_FILE_BYTES, transport.calls[1].maxBodyBytes)
        assertEquals(1, logs.size)
    }

    @Test
    fun `a later poll sends the accepted ETag and a 304 calls nothing`() {
        serveValidPair()
        val fetcher = fetcher()
        assertEquals(FamilyCatalogFetchOutcome.ACCEPTED, fetcher.fetchOnce())
        transport.calls.clear()
        transport.script(CATALOG_URL) { CatalogTransportResponse(304, CATALOG_URL, null, ByteArray(0)) }

        assertEquals(FamilyCatalogFetchOutcome.NOT_MODIFIED, fetcher.fetchOnce())

        assertEquals(1, sink.promoted.size)
        assertEquals(listOf(CATALOG_URL), transport.calls.map { it.url })
        assertEquals("\"abc123\"", transport.calls.single().ifNoneMatch)
    }

    @Test
    fun `a 304 with no ETag sent is an error not a no-op`() {
        transport.script(CATALOG_URL) { CatalogTransportResponse(304, CATALOG_URL, null, ByteArray(0)) }
        assertEquals(FamilyCatalogFetchOutcome.HTTP_ERROR, fetcher().fetchOnce())
        assertTrue(sink.promoted.isEmpty())
    }

    @Test
    fun `the 64 KiB cap is inclusive and one more byte is rejected before the signature is even asked for`() {
        val atCap = ByteArray(FamilyCatalogFetcher.MAX_CATALOG_BYTES) { 'a'.code.toByte() }
        serveValidPair(atCap)
        assertEquals(FamilyCatalogFetchOutcome.ACCEPTED, fetcher().fetchOnce())

        transport.calls.clear()
        val overCap = ByteArray(FamilyCatalogFetcher.MAX_CATALOG_BYTES + 1) { 'a'.code.toByte() }
        serveValidPair(overCap)
        sink.promoted.clear()
        assertEquals(FamilyCatalogFetchOutcome.BAD_SIZE, fetcher().fetchOnce())
        assertTrue(sink.promoted.isEmpty())
        assertEquals(listOf(CATALOG_URL), transport.calls.map { it.url })
    }

    @Test
    fun `an empty body or an oversized signature file is a size rejection`() {
        transport.script(CATALOG_URL) { ok(CATALOG_URL, ByteArray(0), null) }
        assertEquals(FamilyCatalogFetchOutcome.BAD_SIZE, fetcher().fetchOnce())

        transport.script(CATALOG_URL) { ok(CATALOG_URL, catalog, null) }
        transport.script(SIG_URL) {
            ok(SIG_URL, ByteArray(FamilyCatalogSignature.MAX_SIGNATURE_FILE_BYTES + 1) { 'A'.code.toByte() }, null)
        }
        assertEquals(FamilyCatalogFetchOutcome.BAD_SIZE, fetcher().fetchOnce())
        assertTrue(sink.promoted.isEmpty())
    }

    @Test
    fun `a bad signature never reaches the sink and is not remembered`() {
        val stranger = keyPair()
        transport.script(CATALOG_URL) { ok(CATALOG_URL, catalog, "\"e1\"") }
        transport.script(SIG_URL) { ok(SIG_URL, b64(sign(stranger, catalog)).toByteArray(), null) }
        val fetcher = fetcher()
        assertEquals(FamilyCatalogFetchOutcome.BAD_SIGNATURE, fetcher.fetchOnce())

        // A flipped catalog byte under an otherwise valid signature.
        val tampered = catalog.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        transport.script(CATALOG_URL) { ok(CATALOG_URL, tampered, "\"e2\"") }
        transport.script(SIG_URL) { ok(SIG_URL, b64(sign(pair, catalog)).toByteArray(), null) }
        assertEquals(FamilyCatalogFetchOutcome.BAD_SIGNATURE, fetcher.fetchOnce())

        // Garbage and wrong-length signature files.
        for (body in listOf("%%%%".toByteArray(), b64(ByteArray(63)).toByteArray(), "AAAA".toByteArray())) {
            transport.script(SIG_URL) { ok(SIG_URL, body, null) }
            assertEquals(FamilyCatalogFetchOutcome.BAD_SIGNATURE, fetcher.fetchOnce())
        }

        assertTrue(sink.promoted.isEmpty())
        // None of those ETags was kept: the next request is unconditional.
        assertNull(transport.calls.last { it.url == CATALOG_URL }.ifNoneMatch)
    }

    @Test
    fun `a response from another host or a redirect is refused`() {
        transport.script(CATALOG_URL) { ok("https://evil.example.com/tweaker-families.json", catalog, null) }
        assertEquals(FamilyCatalogFetchOutcome.REFUSED_HOST, fetcher().fetchOnce())

        transport.script(CATALOG_URL) { ok("http://raw.githubusercontent.com/x/tweaker-families.json", catalog, null) }
        assertEquals(FamilyCatalogFetchOutcome.REFUSED_HOST, fetcher().fetchOnce())

        for (status in listOf(301, 302, 307, 308)) {
            transport.script(CATALOG_URL) { CatalogTransportResponse(status, CATALOG_URL, null, ByteArray(0)) }
            assertEquals("status $status", FamilyCatalogFetchOutcome.REFUSED_REDIRECT, fetcher().fetchOnce())
        }

        // The signature request is held to the same rules.
        transport.script(CATALOG_URL) { ok(CATALOG_URL, catalog, null) }
        transport.script(SIG_URL) { ok("https://evil.example.com/tweaker-families.json.sig", ByteArray(88), null) }
        assertEquals(FamilyCatalogFetchOutcome.REFUSED_HOST, fetcher().fetchOnce())
        transport.script(SIG_URL) { CatalogTransportResponse(302, SIG_URL, null, ByteArray(0)) }
        assertEquals(FamilyCatalogFetchOutcome.REFUSED_REDIRECT, fetcher().fetchOnce())
        assertTrue(sink.promoted.isEmpty())
    }

    @Test
    fun `a configured URL that is not plain https on one host opens no connection`() {
        val bad = listOf(
            "http://raw.githubusercontent.com/Mimir-apk/tweaker-families/main/tweaker-families.json",
            "https://user:pw@raw.githubusercontent.com/x/tweaker-families.json",
            "https://raw.githubusercontent.com:8443/x/tweaker-families.json",
            "ftp://raw.githubusercontent.com/x/tweaker-families.json",
            "https:///tweaker-families.json",
            "https://raw.githubusercontent.com/x/tweaker-families.json#frag",
            "https://raw.githubusercontent.com/x/tweaker-families.json?ref=main",
            "not a url",
            "",
        )
        for (url in bad) {
            assertEquals(url, FamilyCatalogFetchOutcome.REFUSED_HOST, fetcher(url = url).fetchOnce())
        }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `the same SHA-256 as the active file is a no-op that skips the signature request`() {
        sink.activeSha = FamilyCatalogFetcher.sha256Hex(catalog)
        serveValidPair()

        assertEquals(FamilyCatalogFetchOutcome.UNCHANGED, fetcher().fetchOnce())

        assertTrue(sink.promoted.isEmpty())
        assertEquals(listOf(CATALOG_URL), transport.calls.map { it.url })
    }

    @Test
    fun `a deadline is a quiet failure`() {
        transport.script(CATALOG_URL) { throw SocketTimeoutException("slow") }
        assertEquals(FamilyCatalogFetchOutcome.DEADLINE, fetcher().fetchOnce())

        // The catalog arrives but eats the whole budget: the signature is never requested.
        transport.calls.clear()
        transport.script(CATALOG_URL) {
            nanos += FamilyCatalogFetcher.DEADLINE_MILLIS * 1_000_000L + 1
            ok(CATALOG_URL, catalog, null)
        }
        transport.script(SIG_URL) { ok(SIG_URL, (b64(sign(pair, catalog))).toByteArray(), null) }
        assertEquals(FamilyCatalogFetchOutcome.DEADLINE, fetcher().fetchOnce())
        assertEquals(listOf(CATALOG_URL), transport.calls.map { it.url })
        assertTrue(sink.promoted.isEmpty())
    }

    @Test
    fun `the second request gets only the time that is left`() {
        transport.script(CATALOG_URL) {
            nanos += 4_000L * 1_000_000L
            ok(CATALOG_URL, catalog, null)
        }
        transport.script(SIG_URL) { ok(SIG_URL, b64(sign(pair, catalog)).toByteArray(), null) }
        assertEquals(FamilyCatalogFetchOutcome.ACCEPTED, fetcher().fetchOnce())
        assertEquals(10_000, transport.calls[0].timeoutMillis)
        assertEquals(6_000, transport.calls[1].timeoutMillis)
    }

    @Test
    fun `no or an invalid public key means no network call at all and one log line each`() {
        serveValidPair()
        val keys = listOf("", "   ", b64(ByteArray(31)), b64(ByteArray(33)), "***")
        for (key in keys) {
            val fetcher = fetcher(key = key)
            assertEquals(FamilyCatalogFetchOutcome.DISABLED, fetcher.fetchOnce())
            assertEquals(FamilyCatalogFetchOutcome.DISABLED, fetcher.fetchOnce())
        }
        assertTrue(transport.calls.isEmpty())
        assertTrue(sink.promoted.isEmpty())
        // One "disabled" line per fetcher instance, however often it is asked.
        assertEquals(keys.size, logs.size)
        assertTrue(logs.all { it.contains("disabled") })
    }

    @Test
    fun `network errors server errors and a throwing sink are quiet`() {
        transport.script(CATALOG_URL) { throw IOException("offline") }
        assertEquals(FamilyCatalogFetchOutcome.NETWORK_ERROR, fetcher().fetchOnce())

        transport.script(CATALOG_URL) { CatalogTransportResponse(503, CATALOG_URL, null, ByteArray(0)) }
        assertEquals(FamilyCatalogFetchOutcome.HTTP_ERROR, fetcher().fetchOnce())
        transport.script(CATALOG_URL) { CatalogTransportResponse(404, CATALOG_URL, null, ByteArray(0)) }
        assertEquals(FamilyCatalogFetchOutcome.HTTP_ERROR, fetcher().fetchOnce())

        serveValidPair()
        sink.throwOnPromote = true
        assertEquals(FamilyCatalogFetchOutcome.ERROR, fetcher().fetchOnce())
        sink.throwOnPromote = false
        sink.throwOnActive = true
        assertEquals(FamilyCatalogFetchOutcome.ERROR, fetcher().fetchOnce())
    }

    @Test
    fun `a sink that declines keeps the ETag out so the next poll retries`() {
        serveValidPair()
        sink.accept = false
        val fetcher = fetcher()
        assertEquals(FamilyCatalogFetchOutcome.DECLINED_BY_SINK, fetcher.fetchOnce())
        assertEquals(1, sink.promoted.size)

        sink.accept = true
        assertEquals(FamilyCatalogFetchOutcome.ACCEPTED, fetcher.fetchOnce())
        assertNull(transport.calls.filter { it.url == CATALOG_URL }.last().ifNoneMatch)
        assertEquals(2, sink.promoted.size)
    }

    @Test
    fun `only a well formed ETag is echoed back`() {
        for (etag in listOf("no-quotes", "\"has space\"", "\"" + "x".repeat(250) + "\"", "\"a\r\nX-Evil: 1\"")) {
            val fetcher = fetcher()
            serveValidPair(etag = etag)
            assertEquals(FamilyCatalogFetchOutcome.ACCEPTED, fetcher.fetchOnce())
            transport.calls.clear()
            transport.script(CATALOG_URL) { CatalogTransportResponse(500, CATALOG_URL, null, ByteArray(0)) }
            fetcher.fetchOnce()
            assertNull(etag, transport.calls.single().ifNoneMatch)
        }
        val fetcher = fetcher()
        serveValidPair(etag = "W/\"weak-1\"")
        fetcher.fetchOnce()
        transport.calls.clear()
        transport.script(CATALOG_URL) { CatalogTransportResponse(500, CATALOG_URL, null, ByteArray(0)) }
        fetcher.fetchOnce()
        assertEquals("W/\"weak-1\"", transport.calls.single().ifNoneMatch)
    }

    @Test
    fun `the signature URL sits beside the catalog on the same host`() {
        assertEquals(SIG_URL, FamilyCatalogFetcher.signatureUrlFor(CATALOG_URL))
    }

    // --- the real transport, against a loopback socket server ---

    private var server: ServerSocket? = null

    @After
    fun stopServer() {
        runCatching { server?.close() }
    }

    /** A one-shot-per-connection HTTP/1.1 server on loopback: [handler] gets the lowercased request headers and the raw output. */
    private fun serve(handler: (Map<String, String>, OutputStream) -> Unit): String {
        val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = listener
        Thread {
            while (!listener.isClosed) {
                val socket = try {
                    listener.accept()
                } catch (_: IOException) {
                    return@Thread
                }
                Thread {
                    socket.use {
                        runCatching {
                            val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val headers = HashMap<String, String>()
                            reader.readLine() // request line
                            while (true) {
                                val line = reader.readLine()
                                if (line.isNullOrEmpty()) break
                                val colon = line.indexOf(':')
                                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                            }
                            handler(headers, it.getOutputStream())
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${listener.localPort}/tweaker-families.json"
    }

    /** Writes a response head: the status line and header lines, CRLF separated, ended by a blank line. */
    private fun OutputStream.head(vararg lines: String) {
        write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    @Test
    fun `the real transport caps the body at one byte past the limit and sends only the User-Agent`() {
        val seen = CopyOnWriteArrayList<Map<String, String>>()
        val url = serve { headers, out ->
            seen += headers
            out.head("HTTP/1.1 200 OK", "Content-Length: 200000", "ETag: \"tag-1\"", "Connection: close")
            runCatching { out.write(ByteArray(200_000) { 'z'.code.toByte() }) }
        }

        val response = HttpsCatalogTransport().get(url, null, UA, 1000, 5000)

        assertEquals(200, response.status)
        assertEquals(1001, response.body.size)
        assertEquals("\"tag-1\"", response.etag)
        assertEquals(url, response.finalUrl)
        val headers = seen.single()
        assertEquals(UA, headers["user-agent"])
        assertFalse(headers.containsKey("cookie"))
        assertFalse(headers.containsKey("authorization"))
        assertFalse(headers.containsKey("if-none-match"))
    }

    @Test
    fun `the real transport sends If-None-Match, reports 304 and does not follow redirects`() {
        val mode = AtomicReference("etag")
        val url = serve { headers, out ->
            if (mode.get() == "etag") {
                val status = if (headers["if-none-match"] == "\"tag-1\"") "304 Not Modified" else "200 OK"
                out.head("HTTP/1.1 $status", "Content-Length: 0", "Connection: close")
            } else {
                out.head("HTTP/1.1 302 Found", "Location: https://evil.example.com/x", "Content-Length: 0", "Connection: close")
            }
        }

        val notModified = HttpsCatalogTransport().get(url, "\"tag-1\"", UA, 1000, 5000)
        assertEquals(304, notModified.status)
        assertEquals(0, notModified.body.size)

        mode.set("redirect")
        val redirected = HttpsCatalogTransport().get(url, null, UA, 1000, 5000)
        assertEquals(302, redirected.status)
        assertEquals(url, redirected.finalUrl)
        assertEquals(0, redirected.body.size)
    }

    @Test
    fun `the real transport gives up on a server that drips bytes past the deadline`() {
        val url = serve { _, out ->
            out.head("HTTP/1.1 200 OK", "Connection: close")
            runCatching {
                repeat(50) {
                    out.write('a'.code)
                    out.flush()
                    Thread.sleep(100)
                }
            }
        }

        val started = System.nanoTime()
        try {
            HttpsCatalogTransport().get(url, null, UA, 64 * 1024, 700)
            fail("expected a timeout")
        } catch (_: SocketTimeoutException) {
            // expected
        }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMillis ms", tookMillis < 3_000)
    }

    // --- fakes and helpers ---

    private class FakeSink : VerifiedCatalogSink {
        var activeSha: String? = null
        var accept = true
        var throwOnPromote = false
        var throwOnActive = false
        val promoted = CopyOnWriteArrayList<Pair<ByteArray, ByteArray>>()

        override fun activeSha256Hex(): String? {
            if (throwOnActive) throw IllegalStateException("storage locked")
            return activeSha
        }

        override fun promoteVerified(bytes: ByteArray, signature: ByteArray): Boolean {
            promoted += bytes to signature
            if (throwOnPromote) throw IllegalStateException("disk full")
            return accept
        }
    }

    private class Call(
        val url: String,
        val ifNoneMatch: String?,
        val userAgent: String,
        val maxBodyBytes: Int,
        val timeoutMillis: Int,
    )

    private class FakeTransport : CatalogTransport {
        val calls = CopyOnWriteArrayList<Call>()
        private val scripts = HashMap<String, () -> CatalogTransportResponse>()

        fun script(url: String, response: () -> CatalogTransportResponse) {
            scripts[url] = response
        }

        override fun get(
            url: String,
            ifNoneMatch: String?,
            userAgent: String,
            maxBodyBytes: Int,
            timeoutMillis: Int,
        ): CatalogTransportResponse {
            calls += Call(url, ifNoneMatch, userAgent, maxBodyBytes, timeoutMillis)
            return (scripts[url] ?: throw IOException("no script for $url"))()
        }
    }

    private fun ok(finalUrl: String, body: ByteArray, etag: String?) = CatalogTransportResponse(200, finalUrl, etag, body)

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sign(pair: KeyPair, message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(message)
        sign()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val CATALOG_URL = "https://raw.githubusercontent.com/Mimir-apk/tweaker-families/main/tweaker-families.json"
        const val SIG_URL = "$CATALOG_URL.sig"
        const val UA = "MimirTweaker/0.4.2-test"
    }
}
