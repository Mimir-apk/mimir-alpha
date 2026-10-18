package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** Shared fixtures for the remote-definitions tests. Nothing here touches a network. */
internal object TestBundled {
    const val FLYCAST = "com.flycast.emulator"
    const val RETROARCH = "com.retroarch.aarch64"
    const val PPSSPP = "org.ppsspp.ppsspp"
    const val RECOGNIZE_ONLY = "com.example.recognizeonly"

    val DREAMCAST = PlatformId("dreamcast")
    val GB = PlatformId("gb")
    val SNES = PlatformId("snes")
    val PSP = PlatformId("psp")
    val PS2 = PlatformId("ps2")

    const val FLYCAST_REICAST = "com.reicast.emulator.MainActivity"

    /** A small, fully controlled bundled state. */
    fun small(baselineOrdinal: Long = 0L, extraGbCores: List<String> = emptyList()): BundledDefinitions = BundledDefinitions(
        baselineOrdinal = baselineOrdinal,
        seeds = listOf(
            BundledSeed(FLYCAST, EmulatorCapability.DIRECT_PAYLOAD, setOf(DREAMCAST)),
            BundledSeed(
                RETROARCH,
                EmulatorCapability.DIRECT_PAYLOAD,
                setOf(GB, SNES, DREAMCAST),
                retroArchCores = mapOf(
                    GB to listOf("gambatte", "mgba") + extraGbCores,
                    SNES to listOf("snes9x"),
                    DREAMCAST to listOf("flycast"),
                ),
            ),
            BundledSeed(PPSSPP, EmulatorCapability.OPEN_ONLY, setOf(PSP)),
            BundledSeed(RECOGNIZE_ONLY, EmulatorCapability.RECOGNIZE_ONLY, setOf(PS2)),
        ),
        activityAlternates = mapOf(PackagePlatform(FLYCAST, DREAMCAST) to listOf(FLYCAST_REICAST)),
    )
}

/** Row builders: plain JSON text, so a test can also hand-write a bad row. */
internal object Rows {
    fun alt(pkg: String, platform: String, activity: String) =
        """{"type":"activity_alternate","package":"$pkg","platform":"$platform","activity":"$activity"}"""

    fun core(pkg: String, platform: String, core: String) =
        """{"type":"retroarch_core","package":"$pkg","platform":"$platform","core":"$core"}"""

    fun ext(pkg: String, platform: String, vararg extensions: String) =
        """{"type":"extensions","package":"$pkg","platform":"$platform","extensions":[${extensions.joinToString(",") { "\"$it\"" }}]}"""

    fun regex(pkg: String, platform: String, regexJsonBody: String) =
        """{"type":"filename_regex","package":"$pkg","platform":"$platform","regex":"$regexJsonBody"}"""
}

/** A built overlay file: the bytes to serve, plus the pin that goes with it. */
internal class Built(val bytes: ByteArray, val ordinal: Long, val canonicalSha: String) {
    val rawSha: String get() = sha256Hex(bytes)
    val pin: RemoteDefinitionsPin get() = RemoteDefinitionsPin("defs-$ordinal", rawSha)
    fun pinWithRef(ref: String) = RemoteDefinitionsPin(ref, canonicalSha)
}

internal object Overlays {
    /** The canonical hash of a body given as JSON text, computed the same way the validator does. */
    fun canonicalSha(bodyJson: String): String =
        sha256Hex(StrictJson.canonical(StrictJson.parse(bodyJson.toByteArray(Charsets.US_ASCII))).toByteArray(Charsets.US_ASCII))

    fun build(
        ordinal: Long,
        rows: List<String>,
        schema: Int = 1,
        sigJson: String = "null",
        extraEnvelope: String = "",
        pretty: Boolean = false,
    ): Built {
        val body = """{"rows":[${rows.joinToString(",")}]}"""
        return fromBody(ordinal, body, schema, sigJson, extraEnvelope, pretty)
    }

    fun fromBody(
        ordinal: Long,
        bodyJson: String,
        schema: Int = 1,
        sigJson: String = "null",
        extraEnvelope: String = "",
        pretty: Boolean = false,
    ): Built {
        // A body the strict reader itself refuses (too long a string, too many rows) has no canonical form; any
        // placeholder hash will do because the whole-file parse fails before the hash is looked at.
        val sha = try {
            canonicalSha(bodyJson)
        } catch (_: OverlayRejected) {
            "0".repeat(64)
        }
        val sep = if (pretty) ",\n  " else ","
        val text = buildString {
            append(if (pretty) "{\n  " else "{")
            append("\"schema\":$schema").append(sep)
            append("\"ordinal\":$ordinal").append(sep)
            append("\"sha256\":\"$sha\"").append(sep)
            append("\"sig\":$sigJson").append(sep)
            if (extraEnvelope.isNotEmpty()) append(extraEnvelope).append(sep)
            append("\"body\":").append(if (pretty) bodyJson.replace(",", ",\n    ") else bodyJson)
            append(if (pretty) "\n}\n" else "}")
        }
        return Built(text.toByteArray(Charsets.US_ASCII), ordinal, sha)
    }
}

/** One canned HTTP answer. Counts how often its body was opened so tests can assert "no body read". */
internal class FakeResponse(
    override val statusCode: Int,
    private val bodyBytes: ByteArray = ByteArray(0),
    private val headers: Map<String, String> = emptyMap(),
    override val declaredLength: Long = bodyBytes.size.toLong(),
    private val stream: (() -> InputStream)? = null,
) : RemoteDefinitionsResponse {
    val bodyCalls = AtomicInteger(0)
    val closeCalls = AtomicInteger(0)

    override fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun body(): InputStream {
        bodyCalls.incrementAndGet()
        return stream?.invoke() ?: bodyBytes.inputStream()
    }

    override fun close() {
        closeCalls.incrementAndGet()
    }

    companion object {
        fun ok(built: Built, etag: String? = "\"e1\"", extra: Map<String, String> = emptyMap()) =
            ok(built.bytes, etag, extra)

        fun ok(bytes: ByteArray, etag: String? = "\"e1\"", extra: Map<String, String> = emptyMap()) = FakeResponse(
            200,
            bytes,
            buildMap {
                if (etag != null) put("ETag", etag)
                putAll(extra)
            },
        )

        fun gzip(bytes: ByteArray, etag: String? = "W/\"gz\"") = ok(gzipped(bytes), etag, mapOf("Content-Encoding" to "gzip"))

        fun status(code: Int, headers: Map<String, String> = emptyMap()) = FakeResponse(code, ByteArray(0), headers)

        fun redirect(location: String, code: Int = 302) = FakeResponse(code, ByteArray(0), mapOf("Location" to location))

        fun gzipped(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }
    }
}

/** A scripted transport. [script] sees the request and answers; every request is recorded. */
internal class FakeEgress(private val script: (Request, Int) -> RemoteDefinitionsResponse?) : RemoteDefinitionsTransport {
    data class Request(val url: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    val requests = CopyOnWriteArrayList<Request>()
    val responses = CopyOnWriteArrayList<RemoteDefinitionsResponse>()

    override fun open(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestHeaders: Map<String, String>,
    ): RemoteDefinitionsResponse? {
        val index = requests.size
        requests += Request(url, requestHeaders)
        val answer = script(requests.last(), index)
        if (answer != null) responses += answer
        return answer
    }

    companion object {
        /** Answers request n with responses[n], repeating the last one. */
        fun sequence(vararg answers: () -> RemoteDefinitionsResponse?): FakeEgress =
            FakeEgress { _, i -> answers[minOf(i, answers.size - 1)]() }

        fun always(answer: () -> RemoteDefinitionsResponse?): FakeEgress = FakeEgress { _, _ -> answer() }
    }
}

/** Raised by the store fault hook to stand in for the process dying at that step. */
internal class SimulatedCrash : RuntimeException("simulated process death")

/** Everything a scenario needs, wired the way production would be wired (store, holder, refresher), on a temp dir. */
internal class Rig(
    val dir: File,
    val bundled: BundledDefinitions,
    val egress: FakeEgress,
    var enabled: Boolean = true,
    displayClock: () -> Long = { 1_700_000_000_000L },
    faultHook: (StoreStep) -> Unit = {},
    store: RemoteDefinitionsStore? = null,
) {
    val store: RemoteDefinitionsStore = store ?: RemoteDefinitionsStore(dir, faultHook)
    val holder = EffectiveDefinitionsHolder(DefinitionsMerge.merge(bundled, null))
    val refresher = RemoteDefinitionsRefresher(this.store, egress, bundled, holder, { enabled }, displayClock)

    fun refresh(built: Built): RefreshResult = refresher.refresh(built.pin)

    /** A second refresher over the SAME store and holder, as a second worker in one process would be. */
    fun sibling(egress: FakeEgress): RemoteDefinitionsRefresher =
        RemoteDefinitionsRefresher(store, egress, bundled, holder, { enabled })
}

/** A frozen picture of a directory: names, sizes and modification times. Equal pictures mean no writes happened. */
internal fun snapshot(dir: File): Map<String, String> =
    (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.associate { it.name to "${it.length()}@${it.lastModified()}" }

/** Sets every file's mtime to a fixed old moment so a later rewrite would be visible as a changed mtime. */
internal fun ageFiles(dir: File) {
    dir.listFiles()?.forEach { check(it.setLastModified(1_000_000_000_000L)) { "could not age ${it.name}" } }
}

internal fun fileNames(dir: File): Set<String> = (dir.listFiles() ?: emptyArray()).map { it.name }.toSet()

/** Serves [good] bytes then fails, the way a connection cut mid-chunk does. */
internal class CutStream(private val data: ByteArray, private val failAfter: Int) : InputStream() {
    private var pos = 0
    override fun read(): Int {
        if (pos >= failAfter) throw IOException("unexpected end of stream")
        return if (pos < data.size) data[pos++].toInt() and 0xFF else -1
    }
}

/** An endless stream of one byte that counts what was taken, for the "reads stay bounded" assertion. */
internal class EndlessStream(val served: AtomicInteger) : InputStream() {
    override fun read(): Int {
        served.incrementAndGet()
        return 'x'.code
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        served.addAndGet(len)
        java.util.Arrays.fill(b, off, off + len, 'x'.code.toByte())
        return len
    }
}
