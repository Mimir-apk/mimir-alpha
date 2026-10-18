package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.MetadataOrigin
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.metadata.MetadataRecord
import com.gruning.mimir.rewrite.core.metadata.MetadataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * IGDB last-resort NAME-SEARCH metadata source, scoped to synopsis only. Explicit Enrich press ONLY — no bulk, no hydration worker, no scan, no launch caller exists
 * anywhere in this tree; see `ArtworkFetchController.fetchAndPersistIgdbDescription`'s two call sites for
 * the only places this class is ever invoked.
 *
 * GUARDS (all must hold or `lookupOutcome` returns WITHOUT touching the network, exactly like
 * [ScreenScraperMetadataSource]):
 *  1. [gateOpen] — the player's own metadata opt-in (the app composes this as
 *     `MimirConnectedArtGate.isOpen || MimirSteamGridDbGate.isOpen`, never a new toggle).
 *  2. [registration] — the build's Twitch Client ID + secret from `local.properties`; absent → inert.
 *  3. The platform maps to an IGDB `platforms` id ([IgdbPlatformMap]); today only `pc_games`.
 *
 * Never persists anything itself — [MetadataRecord] is returned to the caller, which persists it through
 * [VerifiedMetadataCache] under the `igdb` label and the I1–I3 precedence rules there. Never writes
 * [com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity]'s art columns (synopsis only,
 * §0/§7): [MetadataRecord.boxArtUri]/[MetadataRecord.heroArtUri]/etc. are always left null here.
 */
class IgdbMetadataSource(
    private val gateOpen: () -> Boolean,
    private val registration: () -> IgdbCredentials? = ::defaultIgdbRegistration,
    private val httpPost: suspend (url: String, headers: Map<String, String>, body: String) -> IgdbHttpResult? =
        ::defaultIgdbHttpPost,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : MetadataSource {

    sealed interface LookupOutcome {
        data class Loaded(val record: MetadataRecord) : LookupOutcome
        /** Every candidate was asked and IGDB matched none of them. Real evidence about the game. */
        data object Empty : LookupOutcome
        /** This platform has no IGDB `platforms` id yet (today: everything but `pc_games`) — no request. */
        data object PlatformUnsupported : LookupOutcome
        data object Unreachable : LookupOutcome
        data object Failed : LookupOutcome
        data object GateClosed : LookupOutcome
    }

    override suspend fun lookup(query: MetadataQuery): MetadataRecord? =
        (lookupOutcome(query) as? LookupOutcome.Loaded)?.record

    private val tokenProvider = IgdbTokenProvider(
        clientId = { registration()?.clientId.orEmpty() },
        clientSecret = { registration()?.clientSecret.orEmpty() },
        clock = clock,
        mint = { clientId, clientSecret ->
            val result = httpPost(
                TOKEN_URL,
                mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                IgdbApi.tokenFormBody(clientId, clientSecret),
            )
            when {
                result == null -> IgdbTokenMintResult.Unreachable
                result.status == 200 ->
                    IgdbApi.parseToken(result.body.orEmpty())
                        ?.let { (token, expiresIn) -> IgdbTokenMintResult.Minted(token, expiresIn) }
                        ?: IgdbTokenMintResult.Failed
                else -> IgdbTokenMintResult.Failed
            }
        },
    )

    private val paceMutex = Mutex()
    private var lastCallAtMillis: Long? = null

    /** Explicit fetches distinguish absence from transport/gate/mapping failure — same shape as SSMS. */
    suspend fun lookupOutcome(query: MetadataQuery): LookupOutcome {
        if (!gateOpen()) return LookupOutcome.GateClosed
        val creds = registration() ?: return LookupOutcome.GateClosed
        val igdbPlatformId = IgdbPlatformMap.igdbIdFor(query.platformId) ?: return LookupOutcome.PlatformUnsupported

        val names = (query.candidateNames + listOfNotNull(query.rawBaseName?.let(ScreenScraperApi::cleanRomName)))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_NAME_CANDIDATES)
        // Nothing to search with -- no request was possible, so nothing was learned from a dead
        // network or a bad response either. Honest absence, not a transport claim (review fix).
        if (names.isEmpty()) return LookupOutcome.Empty

        for (name in names) {
            when (val result = performGamesCall(creds, igdbPlatformId, name)) {
                is IgdbCallResult.Body -> {
                    val accepted = IgdbApi.parseGames(result.text).firstOrNull { hit ->
                        igdbPlatformId in hit.platforms &&
                            IgdbApi.titleMatches(name, hit.name) &&
                            !hit.summary.isNullOrBlank()
                    }
                    if (accepted != null) {
                        return LookupOutcome.Loaded(
                            MetadataRecord(
                                // Empty: the shared persist ladder in VerifiedMetadataCache keeps local
                                // truth (existing row / game.canonicalTitle / game.title), exactly like
                                // the Steam and SteamGridDB records already do.
                                canonicalTitle = "",
                                origin = MetadataOrigin.IGDB,
                                description = accepted.summary,
                                cacheExpiresAt = clock() + CACHE_TTL_MILLIS,
                            ),
                        )
                    }
                    // Verified answer, nothing usable on THIS candidate -- walk the ladder.
                }
                IgdbCallResult.Unreachable -> return LookupOutcome.Unreachable
                // performGamesCall never returns Unauthorized (it resolves to Failed after the one
                // recover-and-retry); handled here only so this `when` stays exhaustive.
                IgdbCallResult.Failed, IgdbCallResult.Unauthorized -> return LookupOutcome.Failed
            }
        }
        // Every candidate was asked and IGDB answered; none matched -- an honest miss.
        return LookupOutcome.Empty
    }

    /** One candidate's games call, including the one-shot 401 recover-and-retry (§2.5). */
    private suspend fun performGamesCall(
        creds: IgdbCredentials,
        igdbPlatformId: Int,
        name: String,
    ): IgdbCallResult {
        val token = tokenProvider.token() ?: return IgdbCallResult.Unreachable
        val first = gamesCall(token, creds.clientId, igdbPlatformId, name)
        if (first !is IgdbCallResult.Unauthorized) return first
        tokenProvider.invalidate()
        val retryToken = tokenProvider.token(forceMint = true) ?: return IgdbCallResult.Unreachable
        val second = gamesCall(retryToken, creds.clientId, igdbPlatformId, name)
        return if (second is IgdbCallResult.Unauthorized) IgdbCallResult.Failed else second
    }

    private suspend fun gamesCall(
        token: String,
        clientId: String,
        igdbPlatformId: Int,
        name: String,
    ): IgdbCallResult {
        pace()
        val result = httpPost(
            GAMES_URL,
            mapOf("Client-ID" to clientId, "Authorization" to "Bearer $token"),
            IgdbApi.gamesQueryBody(name, igdbPlatformId),
        ) ?: return IgdbCallResult.Unreachable
        return when (result.status) {
            200 -> IgdbCallResult.Body(result.body.orEmpty())
            401 -> IgdbCallResult.Unauthorized
            // 429 (rate limit) and every other non-2xx: an honest transport-level failure, never retried
            // here -- R6-P2-03 owns retry/backoff shape and can adopt this source later (§2.5).
            else -> IgdbCallResult.Failed
        }
    }

    /**
     * ≤4 requests/second by construction — a mutex-guarded last-call stamp, ≤1 open request at a time.
     *
     * [lastCallAtMillis] must record when THIS call will actually go out (`now +
     * waitMs`), not when it started computing its wait -- stamping `now` before pausing let two fast
     * round trips both read a short-or-zero `now - last` and both pause only up to the spacing floor,
     * so the actual requests could still land ~[MIN_CALL_SPACING_MILLIS] apart from EACH OTHER'S start
     * rather than from each other's issue time, drifting under real (non-zero-cost) round trips.
     */
    private suspend fun pace() {
        val waitMs = paceMutex.withLock {
            val now = clock()
            val last = lastCallAtMillis
            val wait = if (last == null) 0L else (MIN_CALL_SPACING_MILLIS - (now - last)).coerceAtLeast(0L)
            lastCallAtMillis = now + wait
            wait
        }
        if (waitMs > 0L) pause(waitMs)
    }

    private sealed interface IgdbCallResult {
        data class Body(val text: String) : IgdbCallResult
        data object Unauthorized : IgdbCallResult
        data object Failed : IgdbCallResult
        data object Unreachable : IgdbCallResult
    }

    private companion object {
        const val GAMES_URL = "https://api.igdb.com/v4/games"
        const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"

        /** First three cleaned candidates only — the ladder's own quota cap (§2.5/§2.6). */
        const val MAX_NAME_CANDIDATES = 3

        /** Policy: results expire after 24h (the Twitch Developer Services Agreement's caching cap). */
        const val CACHE_TTL_MILLIS = 86_400_000L

        /** ≥250ms between `games` POSTs -- 4 requests/second by construction. */
        const val MIN_CALL_SPACING_MILLIS = 250L
    }
}

/** IGDB/Twitch credential pair actually sent per-request. Never logged in the clear. */
data class IgdbCredentials(val clientId: String, val clientSecret: String) {
    override fun toString(): String = "IgdbCredentials(clientId=***, clientSecret=***)"
}

/**
 * The status-bearing transport result. No URL, header, or body is ever logged by a caller of this type.
 * Public (not `internal`): it appears in [IgdbMetadataSource]'s public constructor signature, exactly
 * like [ScreenScraperMetadataSource]'s own injectable-transport types.
 */
data class IgdbHttpResult(val status: Int, val body: String?)

/** The build's Twitch registration, or null when this checkout has no creds (→ feature inert). */
private fun defaultIgdbRegistration(): IgdbCredentials? =
    if (IgdbRegistration.isConfigured) {
        IgdbCredentials(IgdbRegistration.clientId, IgdbRegistration.clientSecret)
    } else {
        null
    }

/**
 * The default egress: a host/path-pinned, HTTPS-only, timeout-bounded POST. Returns the status + body
 * pair on any response IGDB/Twitch actually sends back (auth failures included, so callers can classify
 * them) and null only on a transport-level failure (timeout, IO, disallowed URL). Never logs the URL,
 * headers, or body -- both carry credentials. Runs on IO.
 */
private suspend fun defaultIgdbHttpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
): IgdbHttpResult? = withContext(Dispatchers.IO) {
    try {
        if (!IgdbApi.apiUrlAllowed(url) && !IgdbApi.tokenUrlAllowed(url)) return@withContext null
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = IgdbMetadataSourceTimeouts.CONNECT_TIMEOUT_MS
            readTimeout = IgdbMetadataSourceTimeouts.READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("User-Agent", "MimirApk")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (!igdbContentLengthAllowed(connection.contentLengthLong, IgdbMetadataSourceTimeouts.MAX_HTTP_BODY_BYTES)) {
                return@withContext null
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readIgdbUtf8Bounded(it, IgdbMetadataSourceTimeouts.MAX_HTTP_BODY_BYTES) }
            IgdbHttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** Timeout/size constants the default transport needs outside the class's own private companion. */
private object IgdbMetadataSourceTimeouts {
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000
    const val MAX_HTTP_BODY_BYTES = 1L * 1024L * 1024L
}

internal fun igdbContentLengthAllowed(reportedBytes: Long, maxBytes: Long): Boolean =
    maxBytes > 0L && (reportedBytes == -1L || reportedBytes in 0..maxBytes)

/** Bounded full-body reader, [ScreenScraperMetadataSource]'s reader ported for this transport. */
internal fun readIgdbUtf8Bounded(input: InputStream, maxBytes: Long): String? {
    if (maxBytes <= 0L) return null
    val out = ByteArrayOutputStream(minOf(maxBytes, 16L * 1024L).toInt())
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    input.use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
    }
    return out.toString(Charsets.UTF_8.name())
}
