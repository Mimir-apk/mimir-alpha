package com.gruning.mimir.rewrite.core.launch.remotedefs

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/**
 * The transport seam. It is shape-for-shape the app's `MimirProviderEgressOpener` / `MimirProviderEgressResponse`
 * (url, two timeouts, request headers in; status, header lookup, declared length, body, close out) so production's
 * adapter is one lambda around `MimirProviderEgress.open(<new route>, ...)`. It is declared here because
 * `:core-launch` cannot see `:app`, and an interface is not a network call: this module still holds no socket.
 */
fun interface RemoteDefinitionsTransport {
    fun open(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestHeaders: Map<String, String>,
    ): RemoteDefinitionsResponse?
}

interface RemoteDefinitionsResponse {
    val statusCode: Int
    fun header(name: String): String?

    /** -1 when unknown (chunked). */
    val declaredLength: Long
    fun body(): InputStream
    fun close()
}

/**
 * The pinned release: [ref] names an immutable tag or commit, [sha256] is the SHA-256 of the EXACT decoded bytes of
 * that release's `definitions.json` (what `sha256sum` prints). In production the pair comes from
 * the release pointer (checksum validation is active; signing remains future work); [RemoteDefinitionsRefresher.refresh] takes it
 * as an argument so the matrix can drive it directly.
 */
data class RemoteDefinitionsPin(val ref: String, val sha256: String)

/**
 * The one user-started refresh. Nothing in this class runs on its own: no timer, no scan or launch hook.
 *
 * TODO(integration): Settings > "Update emulator definitions" is the only intended caller, and `optIn` reads its switch
 * (default off). A production call site, the Settings row, the egress route and the posture gate are out of this
 * prototype's scope.
 *
 * Contract (note section 6): the received bytes are identified by their SHA-256 and by nothing else; an equal hash
 * writes nothing and emits nothing; a candidate must pass the whole parser, the pin and the ordinal rules before the
 * store is touched; the store switches atomically; any failure leaves the last good overlay in place. No decision
 * reads the clock; [displayClock] only stamps a display field and may even throw.
 */
class RemoteDefinitionsRefresher(
    private val store: RemoteDefinitionsStore,
    private val transport: RemoteDefinitionsTransport,
    private val bundled: BundledDefinitions,
    private val holder: EffectiveDefinitionsHolder,
    private val optIn: () -> Boolean,
    private val displayClock: () -> Long = System::currentTimeMillis,
    private val supportedSchema: Int = RemoteDefinitionsLimits.SUPPORTED_SCHEMA,
    private val source: RemoteDefinitionsSource = RemoteDefinitionsEndpoint.PRODUCTION,
    private val checkBudgetMillis: Long = RemoteDefinitionsLimits.CHECK_BUDGET_MS,
) {
    private val inFlight = AtomicBoolean(false)

    /** The wall-clock cap of the check in progress. Set only while [inFlight] is held. */
    @Volatile
    private var budget: CheckBudget? = null

    fun refresh(pin: RemoteDefinitionsPin): RefreshResult = singleFlight { refreshOnce(pin) }

    /**
     * Opt-in, one check at a time, and one wall-clock cap on the whole check (pointer and release together). When time
     * runs out a watchdog closes the response that is open, so a server that answers slowly cannot hold the check, and
     * through it the egress gate, open: the check ends as "no usable answer" and nothing is stored.
     */
    private fun singleFlight(work: () -> RefreshResult): RefreshResult {
        if (!optIn()) return RefreshResult.NotEnabled
        if (!inFlight.compareAndSet(false, true)) return RefreshResult.SkippedInFlight
        val cap = CheckBudget(checkBudgetMillis)
        budget = cap
        val watchdog = cap.startWatchdog()
        try {
            return work()
        } finally {
            watchdog.interrupt()
            budget = null
            inFlight.set(false)
        }
    }

    /**
     * The Settings action: read the pointer, then the release it names. Two requests at most, both to the pinned
     * repository; the pointer decides which tag and checksum to expect, and everything after that is [refresh].
     *
     * The pointer is read first and judged against what is already held, so a repeat press that finds nothing new
     * makes one small request and writes nothing: the stored checksum equal to the pointer's is [RefreshResult.NoChange]
     * without downloading the release again, and a pointer at or below what this build already contains is
     * [RefreshResult.NoChange] too (the app is at least as new as the data).
     */
    fun refreshLatest(): RefreshResult = singleFlight { latest() }

    private fun latest(): RefreshResult {
        val pointer = when (val fetched = fetch(source.pointerUrl, null)) {
            is Fetched.Failed -> return fetched.result
            Fetched.NotModified -> return RefreshResult.Rejected(RejectCode.HTTP_STATUS)
            is Fetched.Body -> when (val parsed = PointerParser.parse(fetched.bytes, supportedSchema)) {
                is PointerParseResult.Rejected -> {
                    if (parsed.code == RejectCode.SCHEMA_NEWER) holder.needsAppUpdate = true
                    return RefreshResult.Rejected(parsed.code)
                }
                is PointerParseResult.Accepted -> parsed.pointer
            }
        }
        val verified = store.readVerified()
        val stored = verified?.record
        when {
            verified != null && verified.record.rawSha256 == pointer.sha256 -> return storedCopyAnswers(verified)
            pointer.ordinal <= bundled.baselineOrdinal -> return RefreshResult.NoChange
            stored != null && pointer.ordinal < stored.ordinal -> return RefreshResult.Rejected(RejectCode.ROLLBACK)
            stored != null && pointer.ordinal == stored.ordinal -> return RefreshResult.Rejected(RejectCode.ORDINAL_REUSE)
            pointer.ordinal - heldOrdinal(stored) > RemoteDefinitionsLimits.MAX_ORDINAL_JUMP ->
                return RefreshResult.Rejected(RejectCode.ORDINAL_JUMP)
        }
        return refreshOnce(pointer.pin, expectedOrdinal = pointer.ordinal)
    }

    /** The highest ordinal this app already holds: what is stored, or what the build itself contains. */
    private fun heldOrdinal(stored: RemoteDefinitionsStore.Record?): Long =
        maxOf(stored?.ordinal ?: 0L, bundled.baselineOrdinal)

    /**
     * The pointer names the release already on disk. That is "nothing new" only while this build can still use it: a
     * stored release that no longer validates here (it names something this build does not have) is not in force, and
     * the player must be told so rather than that they are up to date.
     */
    private fun storedCopyAnswers(verified: RemoteDefinitionsStore.Verified): RefreshResult =
        when (OverlayParser.parse(verified.bytes, bundled, verified.record.canonicalSha256, supportedSchema)) {
            is ParseResult.Rejected -> RefreshResult.Rejected(RejectCode.NEEDS_NEWER_APP)
            is ParseResult.Accepted -> RefreshResult.NoChange
        }

    private fun refreshOnce(pin: RemoteDefinitionsPin, expectedOrdinal: Long? = null): RefreshResult {
        val url = source.urlFor(pin.ref)
        if (url == null || !SHA256.matches(pin.sha256)) return RefreshResult.Rejected(RejectCode.BAD_PIN)

        var current: RemoteDefinitionsStore.Record? = store.readVerified()?.record
        // A validator is a bandwidth hint, sent only while the stored copy re-verifies (note I9).
        var conditional = current?.etag != null
        while (true) {
            when (val fetched = fetch(url, if (conditional) current?.etag else null)) {
                is Fetched.Failed -> return fetched.result
                Fetched.NotModified -> {
                    if (!conditional) return RefreshResult.Rejected(RejectCode.HTTP_STATUS)
                    val live = store.readVerified()?.record
                    if (live != null) {
                        return if (live.rawSha256 == pin.sha256) {
                            RefreshResult.NoChange
                        } else {
                            RefreshResult.Rejected(RejectCode.PIN_MISMATCH)
                        }
                    }
                    // The 304 vouched for a copy that is gone or corrupt: ask again, once, with no validator.
                    current = null
                    conditional = false
                }
                is Fetched.Body -> return applyBody(fetched, pin, current, expectedOrdinal)
            }
        }
    }

    private fun applyBody(
        fetched: Fetched.Body,
        pin: RemoteDefinitionsPin,
        stored: RemoteDefinitionsStore.Record?,
        expectedOrdinal: Long? = null,
    ): RefreshResult {
        val overlay = when (val parsed = OverlayParser.parse(fetched.bytes, bundled, null, supportedSchema)) {
            is ParseResult.Rejected -> {
                if (parsed.code == RejectCode.SCHEMA_NEWER) holder.needsAppUpdate = true
                return RefreshResult.Rejected(parsed.code)
            }
            is ParseResult.Accepted -> parsed.overlay
        }
        // The pin is the SHA-256 of the exact decoded bytes. It is judged AFTER the file's own checks so that a damaged
        // file reports what is wrong with it, and BEFORE anything is stored or compared by ordinal.
        if (overlay.rawSha256 != pin.sha256) return RefreshResult.Rejected(RejectCode.PIN_MISMATCH)
        // A release whose own ordinal is not the one the pointer announced is not the release that was announced.
        if (expectedOrdinal != null && overlay.ordinal != expectedOrdinal) {
            return RefreshResult.Rejected(RejectCode.PIN_MISMATCH)
        }
        val effective = DefinitionsMerge.merge(bundled, overlay)
        var current = stored
        repeat(MAX_COMMIT_ATTEMPTS) {
            when {
                current != null && current!!.rawSha256 == overlay.rawSha256 -> return RefreshResult.NoChange
                current != null && overlay.ordinal < current!!.ordinal -> return RefreshResult.Rejected(RejectCode.ROLLBACK)
                current != null && overlay.ordinal == current!!.ordinal -> return RefreshResult.Rejected(RejectCode.ORDINAL_REUSE)
                overlay.ordinal <= bundled.baselineOrdinal -> return RefreshResult.Rejected(RejectCode.ROLLBACK)
                overlay.ordinal - heldOrdinal(current) > RemoteDefinitionsLimits.MAX_ORDINAL_JUMP ->
                    return RefreshResult.Rejected(RejectCode.ORDINAL_JUMP)
            }
            // Out of time: the player was already told (or is about to be told) that nothing changed, so nothing may land.
            if (budgetExpired()) return RefreshResult.Offline(TOO_SLOW)
            val record = RemoteDefinitionsStore.Record(
                rawSha256 = overlay.rawSha256,
                ordinal = overlay.ordinal,
                schema = overlay.schema,
                canonicalSha256 = overlay.canonicalSha256,
                etag = fetched.etag,
                fetchedAtMillis = runCatching { displayClock() }.getOrNull(),
            )
            val result = try {
                store.commit(fetched.bytes, record, current?.rawSha256) {
                    holder.publish(effective)
                    holder.needsAppUpdate = false
                }
            } catch (_: IOException) {
                return RefreshResult.Rejected(RejectCode.STORE_IO)
            }
            when (result) {
                RemoteDefinitionsStore.CommitResult.Committed ->
                    return RefreshResult.Applied(overlay.ordinal, overlay.rawSha256)
                is RemoteDefinitionsStore.CommitResult.Conflict -> current = result.current
            }
        }
        return RefreshResult.Rejected(RejectCode.CONFLICT)
    }

    private sealed interface Fetched {
        class Body(val bytes: ByteArray, val etag: String?) : Fetched
        data object NotModified : Fetched
        class Failed(val result: RefreshResult) : Fetched
    }

    private fun fetch(startUrl: String, ifNoneMatch: String?): Fetched {
        var url = startUrl
        var hops = 0
        while (true) {
            if (budgetExpired()) return timedOut()
            val headers = LinkedHashMap<String, String>()
            headers["Accept"] = "application/json"
            headers["Accept-Encoding"] = "identity"
            headers["User-Agent"] = USER_AGENT
            if (ifNoneMatch != null) headers["If-None-Match"] = ifNoneMatch

            val response: RemoteDefinitionsResponse? = try {
                transport.open(
                    url,
                    withinBudget(RemoteDefinitionsLimits.CONNECT_TIMEOUT_MS),
                    withinBudget(RemoteDefinitionsLimits.READ_TIMEOUT_MS),
                    headers,
                )
            } catch (_: IOException) {
                null
            }
            if (response == null) return if (budgetExpired()) timedOut() else Fetched.Failed(RefreshResult.Offline("no response"))
            budget?.track(response)
            try {
                if (budgetExpired()) return timedOut()
                val status = response.statusCode
                when {
                    status == 200 -> return readBody(response)
                    status == 304 -> return Fetched.NotModified
                    status in REDIRECTS -> {
                        hops++
                        if (hops > RemoteDefinitionsLimits.MAX_REDIRECTS) return rejected(RejectCode.REDIRECT)
                        val location = response.header("Location") ?: return rejected(RejectCode.REDIRECT)
                        val next = runCatching { URI(url).resolve(location).toString() }.getOrNull()
                            ?: return rejected(RejectCode.REDIRECT)
                        if (!source.urlAllowed(next)) return rejected(RejectCode.REDIRECT)
                        url = next
                    }
                    status == 429 || status in 500..599 -> return Fetched.Failed(RefreshResult.Offline("HTTP $status"))
                    else -> return rejected(RejectCode.HTTP_STATUS)
                }
            } catch (_: IOException) {
                // The connection dropped between the answer and the status line: no usable answer, like any other drop.
                return Fetched.Failed(RefreshResult.Offline("connection lost"))
            } finally {
                budget?.track(null)
                response.close()
            }
        }
    }

    private fun budgetExpired(): Boolean = budget?.isExpired() == true

    private fun timedOut(): Fetched.Failed = Fetched.Failed(RefreshResult.Offline(TOO_SLOW))

    /** A request never waits longer than the check has left. */
    private fun withinBudget(limitMs: Int): Int {
        val left = budget?.remainingMillis() ?: return limitMs
        return minOf(limitMs.toLong(), left.coerceAtLeast(1L)).toInt()
    }

    private fun readBody(response: RemoteDefinitionsResponse): Fetched {
        val gzip = when (response.header("Content-Encoding")?.trim()?.lowercase()) {
            null, "", "identity" -> false
            "gzip" -> true
            else -> return rejected(RejectCode.ENCODING)
        }
        val declared = response.declaredLength
        if (declared > RemoteDefinitionsLimits.MAX_BYTES) return rejected(RejectCode.SIZE)
        val bytes = try {
            val raw = response.body()
            val source: InputStream = if (gzip) GZIPInputStream(CappedInputStream(raw)) else raw
            readCapped(source)
        } catch (_: OverCap) {
            return rejected(RejectCode.SIZE)
        } catch (_: TimedOut) {
            return timedOut()
        } catch (_: ZipException) {
            return if (budgetExpired()) timedOut() else rejected(RejectCode.ENCODING)
        } catch (_: EOFException) {
            return if (budgetExpired()) timedOut() else rejected(RejectCode.TRUNCATED)
        } catch (_: IOException) {
            return if (budgetExpired()) timedOut() else rejected(RejectCode.TRUNCATED)
        }
        if (!gzip && declared >= 0 && bytes.size.toLong() != declared) return rejected(RejectCode.TRUNCATED)
        return Fetched.Body(bytes, response.header("ETag")?.trim())
    }

    private fun readCapped(source: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            if (budgetExpired()) throw TimedOut()
            val n = source.read(buffer)
            if (n < 0) break
            if (out.size() + n > RemoteDefinitionsLimits.MAX_BYTES) throw OverCap()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun rejected(code: RejectCode) = Fetched.Failed(RefreshResult.Rejected(code))

    private class OverCap : IOException("over the byte cap")

    private class TimedOut : IOException("over the time budget")

    /**
     * The wall-clock cap on one check. [isExpired] is read between steps; the watchdog also closes the response that is
     * open when time runs out, which is what ends a read that is blocked on a slow server.
     */
    private class CheckBudget(private val limitMillis: Long) {
        private val startNanos = System.nanoTime()

        @Volatile
        private var fired = false

        @Volatile
        private var open: RemoteDefinitionsResponse? = null

        fun remainingMillis(): Long = limitMillis - (System.nanoTime() - startNanos) / 1_000_000L

        fun isExpired(): Boolean = fired || remainingMillis() <= 0L

        fun track(response: RemoteDefinitionsResponse?) {
            open = response
        }

        fun startWatchdog(): Thread = Thread(
            {
                try {
                    Thread.sleep(maxOf(limitMillis, 0L))
                    fired = true
                    runCatching { open?.close() }
                } catch (_: InterruptedException) {
                    // The check finished first.
                }
            },
            "mimir-definitions-watchdog",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Bounds the COMPRESSED side too, so a gzip stream cannot make the app read forever. */
    private class CappedInputStream(source: InputStream) : FilterInputStream(source) {
        private var total = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++total > RemoteDefinitionsLimits.MAX_BYTES) throw OverCap()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                if (total > RemoteDefinitionsLimits.MAX_BYTES) throw OverCap()
            }
            return n
        }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MAX_COMMIT_ATTEMPTS = 5

        /** The detail of an [RefreshResult.Offline] that ran out of time. Never shown to the player. */
        const val TOO_SLOW = "took too long"

        /** A constant: no device id, no app version, nothing that varies per user. */
        const val USER_AGENT = "Mimir-EmulatorDefinitions/1"
    }
}
