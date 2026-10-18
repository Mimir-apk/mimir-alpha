package com.gruning.mimir.tweaker

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a catalog that has passed the size and signature checks goes. Implemented by the family catalog store
 * (`FamilyCatalogStore`, wired in `familyCatalogSinkFor`). The fetcher neither parses nor interprets the catalog:
 * schema, revision, reserved ids and per-entry validation are the sink's, in that order, after the fetcher's
 * size -> signature.
 */
internal interface VerifiedCatalogSink {
    /**
     * Lowercase hex SHA-256 of the exact bytes of the catalog now in force (the active file, or the bundled
     * asset when nothing has been promoted), or null when unknown. A download with this hash is a no-op.
     */
    fun activeSha256Hex(): String?

    /**
     * [bytes] verified against the compiled-in public key by [signature] (the raw 64 signature bytes, kept so the
     * store can persist `.sig` beside the file). Parse, validate and stage them. True when the sink accepted the
     * catalog (staged, or already identical); false to decline it (older revision, schema, reserved ids, an
     * invalid entry set, a write failure). Must not throw into the caller for ordinary rejections.
     */
    fun promoteVerified(bytes: ByteArray, signature: ByteArray): Boolean
}

/** One HTTP response as the fetcher needs it. [body] is empty unless [status] is 200, and holds at most cap + 1 bytes. */
internal class CatalogTransportResponse(
    val status: Int,
    /** The URL the bytes actually came from; the fetcher refuses anything off the configured host. */
    val finalUrl: String,
    val etag: String?,
    val body: ByteArray,
)

/**
 * A single GET. Implementations must not follow redirects (a 3xx is reported as such), must read at most
 * [maxBodyBytes] + 1 body bytes, send only the User-Agent and the conditional header, and fail with an
 * [IOException] (a [SocketTimeoutException] for time) inside [timeoutMillis] overall.
 */
internal interface CatalogTransport {
    @Throws(IOException::class)
    fun get(
        url: String,
        ifNoneMatch: String?,
        userAgent: String,
        maxBodyBytes: Int,
        timeoutMillis: Int,
    ): CatalogTransportResponse
}

internal enum class FamilyCatalogFetchOutcome {
    /** No usable public key compiled in: bundled catalog only, no connection was opened. */
    DISABLED,

    /** The server said 304 to our ETag. */
    NOT_MODIFIED,

    /** The download's SHA-256 equals the catalog already in force. */
    UNCHANGED,

    /** Size and signature passed and the sink took the catalog. */
    ACCEPTED,

    /** Size and signature passed, the sink declined it (older revision, invalid contents, write failure). */
    DECLINED_BY_SINK,

    /** Empty body, or larger than the 64 KiB catalog cap (256 byte signature cap). */
    BAD_SIZE,

    /** The `.sig` was malformed or did not verify the downloaded bytes. Nothing was handed on. */
    BAD_SIGNATURE,

    /** The configured URL is not plain https on one host, or the response came from a different host. */
    REFUSED_HOST,

    /** The server answered with a redirect; redirects are never followed. */
    REFUSED_REDIRECT,

    /** Any other HTTP status. */
    HTTP_ERROR,

    /** An I/O failure (offline, DNS, TLS, reset). */
    NETWORK_ERROR,

    /** The 10 s overall deadline ran out. */
    DEADLINE,

    /** Anything unexpected (a sink that threw). Quiet like the rest. */
    ERROR,
}

/**
 * Fetches the live family catalog and its Ed25519 signature, and hands verified bytes to a [VerifiedCatalogSink].
 *
 * The posture is "offline = bundled, nothing here gates running":
 * - HTTPS GET only, to the one host in [catalogUrl] (and `<catalogUrl>.sig` on the same host). Any other scheme,
 *   userinfo, port or host, any redirect and any response from elsewhere is refused.
 * - `If-None-Match` with the last ETag we saw accepted; the User-Agent is the only other header. No cookies, no
 *   identifiers, no body.
 * - The catalog is capped at 64 KiB and the signature at 256 bytes, the whole fetch at 10 s.
 * - Rejection order: size -> (same SHA-256 as the active file: no-op) -> signature -> sink (schema, revision,
 *   reserved ids, entries). Nothing reaches the sink unless the signature verified.
 * - Every failure is one log line and an outcome; nothing throws. An empty or malformed compiled-in public key
 *   disables the fetcher before any network call.
 *
 * Not for the transaction worker or any thread that applies a profile: callers run [fetchOnce] on their own
 * background thread ([FamilyCatalogPoller]).
 *
 * The ETag is kept for the life of the process only; persisting it would need a file, and the catalog file area
 * belongs to the store. A fresh process therefore re-downloads (at most 64 KiB) once and finds the SHA-256 unchanged.
 */
internal class FamilyCatalogFetcher(
    private val catalogUrl: String,
    publicKeyBase64: String,
    private val sink: VerifiedCatalogSink,
    private val transport: CatalogTransport,
    private val userAgent: String,
    private val nanoClock: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = { line -> runCatching { Log.i(TAG, line) } },
) {
    private val publicKey: ByteArray? = FamilyCatalogSignature.decodePublicKey(publicKeyBase64)
    private val origin: Origin? = Origin.parse(catalogUrl)
    private val signatureUrl: String = signatureUrlFor(catalogUrl)
    private val disabledLogged = AtomicBoolean(false)

    @Volatile private var etag: String? = null

    /** One full check-and-promote cycle. Safe to call from any one background thread at a time. */
    @Synchronized
    fun fetchOnce(): FamilyCatalogFetchOutcome {
        val outcome = try {
            fetchLocked()
        } catch (_: SocketTimeoutException) {
            FamilyCatalogFetchOutcome.DEADLINE
        } catch (_: IOException) {
            FamilyCatalogFetchOutcome.NETWORK_ERROR
        } catch (_: Exception) {
            FamilyCatalogFetchOutcome.ERROR
        }
        if (outcome == FamilyCatalogFetchOutcome.DISABLED) {
            if (disabledLogged.compareAndSet(false, true)) {
                log("family catalog live fetch disabled (no valid public key compiled in); bundled catalog only")
            }
        } else {
            log("family catalog fetch: $outcome")
        }
        return outcome
    }

    private fun fetchLocked(): FamilyCatalogFetchOutcome {
        val key = publicKey ?: return FamilyCatalogFetchOutcome.DISABLED
        if (origin == null) return FamilyCatalogFetchOutcome.REFUSED_HOST
        val deadlineNanos = nanoClock() + DEADLINE_MILLIS * NANOS_PER_MILLI

        val catalog = when (val step = get(catalogUrl, etag, MAX_CATALOG_BYTES, deadlineNanos)) {
            is Step.Stop -> return step.outcome
            Step.NotModified -> return FamilyCatalogFetchOutcome.NOT_MODIFIED
            is Step.Body -> step
        }
        // The bytes already in force were verified (or are the APK's own) when they were installed, so an
        // identical download needs neither the signature request nor the sink.
        if (sha256Hex(catalog.bytes) == sink.activeSha256Hex()) {
            remember(catalog.etag)
            return FamilyCatalogFetchOutcome.UNCHANGED
        }

        val signatureFile = when (val step = get(signatureUrl, null, FamilyCatalogSignature.MAX_SIGNATURE_FILE_BYTES, deadlineNanos)) {
            is Step.Stop -> return step.outcome
            Step.NotModified -> return FamilyCatalogFetchOutcome.HTTP_ERROR
            is Step.Body -> step
        }
        val signature = FamilyCatalogSignature.decodeSignatureFile(signatureFile.bytes)
            ?: return FamilyCatalogFetchOutcome.BAD_SIGNATURE
        if (!FamilyCatalogSignature.verify(key, catalog.bytes, signature)) {
            return FamilyCatalogFetchOutcome.BAD_SIGNATURE
        }

        if (!sink.promoteVerified(catalog.bytes, signature)) return FamilyCatalogFetchOutcome.DECLINED_BY_SINK
        remember(catalog.etag)
        return FamilyCatalogFetchOutcome.ACCEPTED
    }

    private sealed interface Step {
        class Stop(val outcome: FamilyCatalogFetchOutcome) : Step
        data object NotModified : Step
        class Body(val bytes: ByteArray, val etag: String?) : Step
    }

    private fun get(url: String, conditionalEtag: String?, capBytes: Int, deadlineNanos: Long): Step {
        val remainingMillis = (deadlineNanos - nanoClock()) / NANOS_PER_MILLI
        if (remainingMillis <= 0L) return Step.Stop(FamilyCatalogFetchOutcome.DEADLINE)
        val response = transport.get(url, conditionalEtag, userAgent, capBytes, remainingMillis.toInt())
        if (origin?.owns(response.finalUrl) != true) return Step.Stop(FamilyCatalogFetchOutcome.REFUSED_HOST)
        when (response.status) {
            HTTP_OK -> Unit
            HTTP_NOT_MODIFIED -> {
                return if (conditionalEtag != null) Step.NotModified else Step.Stop(FamilyCatalogFetchOutcome.HTTP_ERROR)
            }
            in 300..399 -> return Step.Stop(FamilyCatalogFetchOutcome.REFUSED_REDIRECT)
            else -> return Step.Stop(FamilyCatalogFetchOutcome.HTTP_ERROR)
        }
        if (response.body.isEmpty() || response.body.size > capBytes) {
            return Step.Stop(FamilyCatalogFetchOutcome.BAD_SIZE)
        }
        return Step.Body(response.body, response.etag)
    }

    private fun remember(responseEtag: String?) {
        etag = responseEtag?.takeIf { it.length <= MAX_ETAG_CHARS && ETAG_SHAPE.matches(it) }
    }

    /** The only host this fetcher may talk to, parsed once from the configured URL. */
    private class Origin(private val host: String) {
        fun owns(url: String): Boolean {
            val other = parse(url) ?: return false
            return other.host.equals(host, ignoreCase = true)
        }

        companion object {
            /** https only, a host, no userinfo, no port but 443, no query or fragment; else null. */
            fun parse(url: String): Origin? {
                val uri = try {
                    URI(url)
                } catch (_: Exception) {
                    return null
                }
                if (!"https".equals(uri.scheme, ignoreCase = true)) return null
                val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
                if (uri.rawUserInfo != null) return null
                if (uri.port != -1 && uri.port != 443) return null
                if (uri.rawQuery != null || uri.rawFragment != null) return null
                return Origin(host)
            }
        }
    }

    companion object {
        const val TAG = "FamilyCatalog"
        const val MAX_CATALOG_BYTES = 64 * 1024
        const val DEADLINE_MILLIS = 10_000L

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val HTTP_OK = 200
        private const val HTTP_NOT_MODIFIED = 304
        private const val MAX_ETAG_CHARS = 200
        private val ETAG_SHAPE = Regex("(?:W/)?\"[\\u0021\\u0023-\\u007e]*\"")

        /** `<catalog>.sig` on the same host: the signature file sits beside the catalog. */
        fun signatureUrlFor(catalogUrl: String): String = "$catalogUrl.sig"

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The real transport: one `HttpURLConnection`, no redirect following, no cache, no cookie handler installed,
 * only `User-Agent` (and `If-None-Match` when we have an ETag). The body is read in a bounded loop (never by
 * trusting Content-Length) and the whole GET is held to the caller's [timeoutMillis].
 */
internal class HttpsCatalogTransport : CatalogTransport {
    override fun get(
        url: String,
        ifNoneMatch: String?,
        userAgent: String,
        maxBodyBytes: Int,
        timeoutMillis: Int,
    ): CatalogTransportResponse {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMillis
            // A single stalled read must not outlast the deadline by much; the loop below checks the clock between reads.
            connection.readTimeout = minOf(timeoutMillis, READ_STALL_LIMIT_MILLIS)
            connection.setRequestProperty("User-Agent", userAgent)
            if (ifNoneMatch != null) connection.setRequestProperty("If-None-Match", ifNoneMatch)
            val status = connection.responseCode
            val body = if (status == 200) {
                connection.inputStream.use { readCapped(it, maxBodyBytes, deadlineNanos) }
            } else {
                ByteArray(0)
            }
            return CatalogTransportResponse(
                status = status,
                finalUrl = connection.url.toString(),
                etag = connection.getHeaderField("ETag"),
                body = body,
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun readCapped(input: InputStream, maxBodyBytes: Int, deadlineNanos: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        // One byte past the cap is enough for the caller to see "too large"; never buffer more than that.
        val limit = maxBodyBytes + 1
        while (out.size() < limit) {
            if (System.nanoTime() - deadlineNanos > 0L) throw SocketTimeoutException("family catalog deadline")
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val READ_STALL_LIMIT_MILLIS = 5_000
    }
}
