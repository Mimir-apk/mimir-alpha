package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.GB
import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.RETROARCH
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The same service against a REAL local HTTP server and `HttpURLConnection`, so the status lines, headers, content
 * lengths, redirects and dropped connections are the real thing and not a fake's idea of them. The pinned URL is a
 * constant ([RemoteDefinitionsEndpoint.BASE_URL]); the test builds a loopback [RemoteDefinitionsSource] with the same
 * repository path, which production code cannot construct.
 *
 * The server is a few lines over `java.net.ServerSocket`: `com.sun.net.httpserver` is not on the Android test classpath.
 */
class RemoteDefinitionsHttpServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Request(val path: String, val headers: Map<String, String>)

    /** What the server does with one request. */
    private sealed interface Reply {
        class Respond(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0)) : Reply

        /** Promises [declared] bytes, sends [body] (fewer), and drops the connection. */
        class Truncated(val declared: Int, val body: ByteArray) : Reply
    }

    private class TinyHttpServer(private val handler: (Request) -> Reply) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val hits = ConcurrentHashMap<String, AtomicInteger>()
        val seen = CopyOnWriteArrayList<Map<String, String>>()

        init {
            thread(isDaemon = true, name = "tiny-http-accept") {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(isDaemon = true, name = "tiny-http-client") { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            client.use {
                try {
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                    val requestLine = reader.readLine() ?: return
                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                    }
                    val path = requestLine.split(' ')[1]
                    hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                    seen += headers
                    val out = client.getOutputStream()
                    when (val reply = handler(Request(path, headers))) {
                        is Reply.Respond -> {
                            out.write(head(reply.status, reply.headers, reply.body.size))
                            out.write(reply.body)
                        }
                        is Reply.Truncated -> {
                            out.write(head(200, emptyMap(), reply.declared))
                            out.write(reply.body)
                        }
                    }
                    out.flush()
                } catch (_: IOException) {
                    // A client that gave up, or a test that cut the connection on purpose.
                }
            }
        }

        private fun head(status: Int, headers: Map<String, String>, length: Int): ByteArray {
            val reason = when (status) {
                200 -> "OK"
                302 -> "Found"
                404 -> "Not Found"
                503 -> "Service Unavailable"
                else -> "Status"
            }
            return buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Length: $length\r\n")
                append("Connection: close\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
        }

        fun stop() {
            runCatching { socket.close() }
        }
    }

    private lateinit var server: TinyHttpServer

    @Volatile
    private var pointerBody: ByteArray = ByteArray(0)
    private val files = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var behaviour: (Request) -> Reply? = { null }

    private val bundled = TestBundled.small()
    private val repo = RemoteDefinitionsEndpoint.REPO_PATH
    private val alt = Rows.alt(FLYCAST, "dreamcast", "com.reicast.emulator.AltActivity")
    private val coreGb = Rows.core(RETROARCH, "gb", "mgba_extra")

    private val source: RemoteDefinitionsSource get() = RemoteDefinitionsSource("http", "127.0.0.1", server.port, repo)

    @Before
    fun start() {
        server = TinyHttpServer { request ->
            behaviour(request) ?: run {
                val path = request.path
                val body: ByteArray? = when {
                    path == "$repo/main/pointer.json" -> pointerBody
                    path.startsWith("$repo/") && path.endsWith("/definitions.json") ->
                        files[path.removePrefix("$repo/").removeSuffix("/definitions.json")]
                    else -> null
                }
                if (body == null) Reply.Respond(404) else Reply.Respond(200, body = body)
            }
        }
    }

    @After
    fun stop() {
        server.stop()
    }

    /** `HttpURLConnection` behind the transport seam. This is test code: the app's own adapter lives in :app. */
    private class UrlConnectionTransport : RemoteDefinitionsTransport {
        override fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int, requestHeaders: Map<String, String>): RemoteDefinitionsResponse? =
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.connect()
                object : RemoteDefinitionsResponse {
                    override val statusCode: Int get() = connection.responseCode
                    override fun header(name: String): String? = connection.getHeaderField(name)
                    override val declaredLength: Long get() = connection.contentLengthLong
                    override fun body(): InputStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
                    override fun close() = connection.disconnect()
                }
            } catch (_: IOException) {
                null
            }
    }

    private class Consent : DefinitionsConsent {
        override var granted: Boolean = true
    }

    private fun publish(built: Built, sha: String = built.rawSha) {
        files["defs-${built.ordinal}"] = built.bytes
        pointerBody = """{"schema":1,"ordinal":${built.ordinal},"tag":"defs-${built.ordinal}","sha256":"$sha","sig":null}""".toByteArray(Charsets.US_ASCII)
    }

    private class Rig(val dir: File, val holder: EffectiveDefinitionsHolder, val service: EmulatorDefinitionsService)

    private fun rig(dir: File = tmp.newFolder()): Rig {
        val holder = EffectiveDefinitionsHolder(DefinitionsMerge.merge(bundled, null))
        return Rig(dir, holder, EmulatorDefinitionsService(dir, UrlConnectionTransport(), Consent(), bundled, holder, source))
    }

    private fun hitsFor(path: String) = server.hits[path]?.get() ?: 0

    @Test
    fun a_release_is_fetched_over_real_http_and_a_second_check_reads_only_the_pointer() {
        publish(Overlays.build(2, listOf(alt, coreGb)))
        val r = rig()

        assertEquals(DefinitionsCheck.Updated(2), r.service.check())
        ageFiles(r.dir)
        val files = snapshot(r.dir)
        assertEquals(DefinitionsCheck.UpToDate, r.service.check())

        assertEquals(files, snapshot(r.dir))
        assertEquals(2, hitsFor("$repo/main/pointer.json"))
        assertEquals(1, hitsFor("$repo/defs-2/definitions.json"))
        assertTrue(r.holder.current.retroArchCoresFor(RETROARCH, GB).contains("mgba_extra"))
    }

    @Test
    fun the_request_carries_no_cookie_no_credentials_and_no_device_identity() {
        publish(Overlays.build(2, listOf(alt)))
        rig().service.check()

        assertTrue(server.seen.isNotEmpty())
        for (headers in server.seen) {
            assertFalse("cookie" in headers)
            assertFalse("authorization" in headers)
            assertFalse("referer" in headers)
            assertEquals("identity", headers["accept-encoding"])
            assertEquals("Mimir-EmulatorDefinitions/1", headers["user-agent"])
        }
    }

    @Test
    fun a_body_that_is_cut_short_is_refused_and_leaves_nothing_behind() {
        val built = Overlays.build(2, listOf(alt, coreGb))
        publish(built)
        behaviour = { request ->
            // Promise the whole file, send half, drop the connection.
            if (request.path.endsWith("/definitions.json")) Reply.Truncated(built.bytes.size, built.bytes.copyOf(built.bytes.size / 2)) else null
        }
        val r = rig()

        val result = r.service.check()

        assertEquals(DefinitionsCheck.Failed(RejectCode.TRUNCATED, offline = false, needsAppUpdate = false, usingFetched = false), result)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
        assertNull(r.service.info().tag)
    }

    @Test
    fun an_unreachable_server_is_offline_and_changes_nothing() {
        publish(Overlays.build(2, listOf(alt)))
        val r = rig()
        server.stop()

        val result = r.service.check()

        assertEquals(DefinitionsCheck.Failed(null, offline = true, needsAppUpdate = false, usingFetched = false), result)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
    }

    @Test
    fun a_missing_tag_and_a_server_error_are_refused_and_change_nothing() {
        publish(Overlays.build(2, listOf(alt)))
        files.clear()
        val r = rig()

        assertEquals(DefinitionsCheck.Failed(RejectCode.HTTP_STATUS, false, false, false), r.service.check())

        behaviour = { Reply.Respond(503) }
        val unavailable = r.service.check()
        assertTrue(unavailable.toString(), unavailable is DefinitionsCheck.Failed && unavailable.offline)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
    }

    @Test
    fun a_redirect_to_another_host_is_followed_by_nobody() {
        publish(Overlays.build(2, listOf(alt)))
        behaviour = {
            Reply.Respond(302, mapOf("Location" to "http://example.invalid/Mimir-apk/mimir-emulator-definitions/main/pointer.json"))
        }
        val r = rig()

        assertEquals(DefinitionsCheck.Failed(RejectCode.REDIRECT, false, false, false), r.service.check())
        assertEquals("only the pinned server was ever asked", 1, server.hits.values.sumOf { it.get() })
    }

    @Test
    fun a_file_that_does_not_match_the_pointers_checksum_is_refused_over_real_http() {
        publish(Overlays.build(2, listOf(alt, coreGb)), sha = "1".repeat(64))
        val r = rig()

        assertEquals(DefinitionsCheck.Failed(RejectCode.PIN_MISMATCH, false, false, false), r.service.check())
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
    }

    @Test
    fun a_release_that_tries_to_add_a_direct_launch_entry_is_refused_over_real_http_even_with_a_matching_checksum() {
        val hostile = Overlays.build(
            2,
            listOf(
                coreGb,
                """{"type":"activity_alternate","package":"com.example.newemu","platform":"dreamcast","activity":"com.example.newemu.MainActivity"}""",
            ),
        )
        publish(hostile)
        val r = rig()
        val key = r.holder.current.stateKey

        val result = r.service.check()

        assertEquals(DefinitionsCheck.Failed(RejectCode.UNKNOWN_PACKAGE, false, false, false), result)
        assertEquals(key, r.holder.current.stateKey)
        assertEquals(emptyList<String>(), r.dir.list()!!.toList())
    }
}
