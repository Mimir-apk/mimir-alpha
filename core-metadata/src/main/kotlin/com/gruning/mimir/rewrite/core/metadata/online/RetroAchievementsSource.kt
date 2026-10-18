package com.gruning.mimir.rewrite.core.metadata.online

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The RetroAchievements read path — the ONE live socket for achievements, and only when every guard passes.
 *
 * GUARDS (all must hold or a call returns without touching the network):
 *  1. [gateOpen] — the connected-services opt-in. False ⇒ no request is ever built.
 *  2. [auth] — the user's own username + Web API key. Absent ⇒ nothing to authenticate with.
 *  3. RA must support the platform, Mimir must have its hash rule, and the live console list must map it.
 *
 * Any failure, timeout, or parse miss yields a typed unavailable result — never an exception, and never a
 * fabricated "0 of 0". Credential rejection remains distinct from a dead network all the way to the public
 * facade, because only the former says anything about the user's key.
 *
 * CACHING is deliberate, not incidental: RA's docs ask clients to cache static game data and warn that
 * "just because you can hit the API for N requests a second does not mean that you should". The console
 * list and each console's hash index are effectively static, so they are fetched once per process. Per-game
 * progress is NOT cached here (the count is read fresh whenever a launch card opens; the Activity page holds one bulk completion read instead).
 *
 * Credentials never reach a log: callers log [RetroAchievementsApi.redact] of a URL, never the URL.
 */
internal class RetroAchievementsSource(
    private val gateOpen: () -> Boolean,
    private val auth: () -> RetroAchievementsApi.Auth?,
    private val httpGet: suspend (String) -> RaHttpResult = ::defaultRetroAchievementsGet,
    private val hashLibraryGet: suspend () -> RaHashLibraryResult = ::defaultRetroAchievementsHashLibraryGet,
    private val chdHash:
        (suspend (consoleId: Int, target: RetroAchievementsChdResident) -> RetroAchievementsChdHashResult)? =
        null,
    private val nativeReaderAvailable: () -> Boolean = { RetroAchievementsNativeHash.isAvailable() },
    /** One keyless GET of an icon from the media host, bytes or null. Separate from [httpGet] so an icon is never an API call. */
    private val iconGet: suspend (String) -> ByteArray? = ::defaultRetroAchievementsIconGet,
    /** The clock the provider indexes' freshness is measured on (a test moves it; the real one is the wall clock). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long a console's game-list index and the whole-library hash map are trusted before the next lookup that needs them reads them again. */
    private val indexFreshMillis: Long = RETRO_ACHIEVEMENTS_PROVIDER_INDEX_FRESH_MS,
    /** After a refresh of an index that had gone stale FAILS, how long before the next try: a flaky network can never turn expiry into a burst of requests. */
    private val indexRetryMillis: Long = RETRO_ACHIEVEMENTS_PROVIDER_INDEX_RETRY_MS,
    /**
     * Spaces the key-bearing requests so a burst of card opens cannot hammer the API, and holds a Retry-After for EVERY client. Null (every test source) spaces nothing and
     * holds the wait for this source alone, on [clock]; the app's client passes the process-wide one.
     */
    pacer: RetroAchievementsRequestPacer? = null,
) {

    private val pacer: RetroAchievementsRequestPacer = pacer ?: RetroAchievementsRequestPacer.holdOnly(clock)

    private fun rateLimited(): Boolean = pacer.holding()

    /**
     * EVERY key-bearing request goes through here. While a Retry-After is in force nothing is sent at all (the answer is the same silent "couldn't reach" as being
     * offline, so the copy never changes); otherwise the request waits its turn at the pacer and goes out. A rate-limited answer starts (or extends) the backoff and
     * is handed on as unreachable, so no caller needs a branch of its own for it.
     */
    private suspend fun get(url: String): RaHttpResult {
        if (rateLimited()) return RaHttpResult.Unreachable
        pacer.admit()
        // The wait for a turn can outlast a backoff that another request started meanwhile.
        if (rateLimited()) return RaHttpResult.Unreachable
        val result = httpGet(url)
        if (result is RaHttpResult.RateLimited) {
            val wait = (result.retryAfterMillis ?: RA_DEFAULT_RATE_LIMIT_BACKOFF_MS).coerceIn(RA_MIN_RATE_LIMIT_BACKOFF_MS, RA_MAX_RETRY_AFTER_MS)
            pacer.holdOff(wait)
            raTransportWarning("rate limited, backing off")
            return RaHttpResult.Unreachable
        }
        return result
    }

    private val mutex = Mutex()
    private val hashLibraryMutex = Mutex()
    private var consoleIds: Map<Int, String>? = null
    private val consoleIdsFlights =
        mutableMapOf<RetroAchievementsApi.Auth, CompletableDeferred<RaLoadResult<Map<Int, String>>>>()

    /** Something read from the provider and the moment it must be read again. */
    private class Held<T>(val value: T, val expiresAtMillis: Long)

    /** A stale index whose refresh failed: no further try (and no request) before [untilMillis], and what that failure was. */
    private class RefreshBackoff(val untilMillis: Long, val reason: RaNoAnswerReason)

    private val hashIndexByConsole = mutableMapOf<Int, Held<RetroAchievementsApi.HashIndex>>()
    private val hashIndexBackoff = mutableMapOf<Int, RefreshBackoff>()
    private val hashIndexFlights =
        mutableMapOf<HashIndexFlightKey, CompletableDeferred<RaLoadResult<RetroAchievementsApi.HashIndex>>>()
    private var hashLibraryHeld: Held<RaHashLibraryResult>? = null

    /** Resolved RA identity for a local file, or null when this lane cannot honestly claim a match. */
    suspend fun resolveGameId(
        platformId: String,
        fileName: String,
        sizeBytes: Long,
        localPath: String? = null,
        targetChd: RetroAchievementsChdResident? = null,
        openStream: suspend () -> InputStream?,
    ): Int? = (
        lookup(platformId, fileName, sizeBytes, localPath, targetChd, openStream) as? RaLookup.Hit
        )?.gameId

    /**
     * The same search as [resolveGameId] but saying WHICH kind of nothing it found, because "we never
     * looked" and "we looked and RA does not carry this file" are different claims and the card renders
     * them differently. A bare null cannot carry that, which is why this exists alongside.
     */
    suspend fun lookup(
        platformId: String,
        fileName: String,
        sizeBytes: Long,
        localPath: String? = null,
        targetChd: RetroAchievementsChdResident? = null,
        openStream: suspend () -> InputStream?,
    ): RaLookup {
        val credentials = usableAuth()
            ?: return RaLookup.Unavailable(RaNoAnswerReason.UNCONFIGURED)
        if (!RetroAchievementsConsoleMap.supportsPlatform(platformId)) {
            return RaLookup.Unavailable(RaNoAnswerReason.UNSUPPORTED_SYSTEM)
        }
        if (!RetroAchievementsHash.supports(platformId)) {
            return RaLookup.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE)
        }
        if (!RetroAchievementsHash.supportsFile(platformId, fileName)) {
            return RaLookup.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE)
        }
        val requiresLocalPath = RetroAchievementsHash.requiresLocalPath(platformId, fileName)
        // The visible container may be an M3U. A freshly resolved target resident, not the primary
        // filename, is the authority for entering EP16's exact-path CHD engine.
        val usesChdEngine = requiresLocalPath && targetChd != null && chdHash != null
        if (requiresLocalPath && localPath.isNullOrBlank() && !usesChdEngine) {
            return RaLookup.Unavailable(RaNoAnswerReason.FILE_UNAVAILABLE)
        }
        // Without the rcheevos library every seek-reader digest is null, which the search below would report as a
        // confident "NO SET" for every disc game. That is a missing capability, not a claim about the file.
        if (requiresLocalPath && !usesChdEngine && !nativeReaderAvailable()) {
            return RaLookup.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE)
        }

        val consoles = when (val result = consoleIds(credentials)) {
            is RaLoadResult.Loaded -> result.value
            RaLoadResult.Rejected -> return RaLookup.Rejected(credentials.credentialRevision)
            is RaLoadResult.Unavailable -> return RaLookup.Unavailable(result.reason)
        }
        val candidates = RetroAchievementsConsoleMap.consoleIdsFor(consoles, platformId)
        if (candidates.isEmpty()) {
            return RaLookup.Unavailable(RaNoAnswerReason.CONSOLE_UNMAPPED)
        }

        val stream = if (requiresLocalPath) null else
            (openStream() ?: return RaLookup.Unavailable(RaNoAnswerReason.FILE_UNAVAILABLE))
        val hashAttempt = withContext(Dispatchers.IO) {
            try {
                if (usesChdEngine) {
                    val consoleId = RetroAchievementsHash.nativeConsoleId(platformId)
                        ?: return@withContext RaHashAttempt.Unavailable(
                            RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE,
                        )
                    when (val result = checkNotNull(chdHash).invoke(consoleId, checkNotNull(targetChd))) {
                        is RetroAchievementsChdHashResult.Hashed -> RaHashAttempt.Hashed(result.md5)
                        RetroAchievementsChdHashResult.FileUnavailable ->
                            RaHashAttempt.Unavailable(RaNoAnswerReason.FILE_UNAVAILABLE)
                        RetroAchievementsChdHashResult.IdentificationUnavailable ->
                            RaHashAttempt.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE)
                    }
                } else if (requiresLocalPath) {
                    RaHashAttempt.Hashed(
                        RetroAchievementsHash.hashPath(
                            platformId,
                            fileName,
                            checkNotNull(localPath),
                        ),
                    )
                } else {
                    RaHashAttempt.Hashed(
                        RetroAchievementsHash.hash(
                            platformId,
                            fileName,
                            sizeBytes,
                            checkNotNull(stream),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A read that THREW (card unmounted, SAF grant revoked, provider I/O error) says nothing about the
                // file's identity. As Hashed(null) it became a Miss, i.e. "NO SET", which the app caches as a file
                // verdict for the whole process. The damaged-archive ruling (2026-07-18) is unaffected: a damaged
                // zip is caught inside the zip walk and still returns a null digest, the honest Miss below.
                RaHashAttempt.Unavailable(RaNoAnswerReason.FILE_UNAVAILABLE)
            }
        }
        val hash = when (hashAttempt) {
            is RaHashAttempt.Hashed -> hashAttempt.value
            is RaHashAttempt.Unavailable -> return RaLookup.Unavailable(hashAttempt.reason)
        }
        // a damaged archive is simply UNMATCHED, not an error state. The same
        // exit also catches dumps whose hash is not supported here (a byte-swapped .v64), which
        // fits — Miss is already the deliberately-vague "nothing to show", not a claim about why.
        if (hash == null) return RaLookup.Miss
        val needle = hash.lowercase(Locale.US)

        var searched = false
        var complete = true
        val achievementGameIds = linkedSetOf<Int>()
        var unavailableReason: RaNoAnswerReason? = null
        for (consoleId in candidates) {
            when (val result = hashIndex(consoleId, credentials)) {
                is RaLoadResult.Loaded -> {
                    searched = true
                    achievementGameIds += result.value.gameIds
                    result.value.hashes[needle]?.let { return RaLookup.Hit(it) }
                    if (!result.value.complete) complete = false
                }
                RaLoadResult.Rejected ->
                    return RaLookup.Rejected(credentials.credentialRevision)
                is RaLoadResult.Unavailable -> {
                    complete = false
                    unavailableReason = unavailableReason ?: result.reason
                }
            }
        }
        // Ordering is load-bearing: a dead f=1 path never consults the keyless fallback, because that would
        // turn a network failure into a confident file claim. Once every f=1 candidate really searched, the
        // global map can close an incomplete index; its own failure preserves Miss only when f=1 was complete.
        if (!searched || unavailableReason != null) {
            return RaLookup.Unavailable(unavailableReason ?: RaNoAnswerReason.PROVIDER_DATA)
        }
        return when (val library = hashLibrary()) {
            is RaHashLibraryResult.Loaded -> {
                val gameId = library.gamesByHash[needle] ?: return RaLookup.Miss
                if (gameId in achievementGameIds) RaLookup.Hit(gameId) else RaLookup.RecognizedNoSet
            }
            RaHashLibraryResult.Unavailable -> if (complete) {
                // A complete f=1 search already earned the weaker file claim.
                RaLookup.Miss
            } else {
                // Empty-hash f=1 rows leave an identity gap. Without the global map, absence is not truth.
                RaLookup.Unavailable(RaNoAnswerReason.PROVIDER_DATA)
            }
        }
    }

    /** This user's progress on [gameId]. Not cached here. read fresh whenever a launch card opens; nothing is held between reads. */
    suspend fun progressFor(gameId: Int): RaProgressLookup {
        val credentials = usableAuth() ?: return RaProgressLookup.Unavailable
        return when (val result = get(RetroAchievementsApi.gameProgressUrl(credentials, gameId))) {
            is RaHttpResult.Body -> RetroAchievementsApi.parseGameProgress(result.text)
                ?.takeIf { it.gameId == gameId }
                ?.let(RaProgressLookup::Read)
                ?: RaProgressLookup.Unavailable
            RaHttpResult.Rejected ->
                RaProgressLookup.Rejected(credentials.credentialRevision)
            // A runtime resource 404 says this progress request has no usable answer. Only validation's
            // profile 404 has the narrower, documented meaning "that candidate username did not resolve".
            RaHttpResult.NotFound -> RaProgressLookup.Unavailable
            RaHttpResult.Unreachable, is RaHttpResult.RateLimited -> RaProgressLookup.Unavailable
        }
    }

    /**
     * One page of this user's completion progress. Not cached here: the app's Activity lane holds the one bulk answer. Credential rejection stays
     * distinct from an unreachable or unusable response.
     */
    suspend fun completionPage(offset: Int, pinned: RetroAchievementsApi.Auth? = null): RaCompletionPageLookup {
        val credentials = usableAuth(pinned) ?: return RaCompletionPageLookup.Unavailable
        return when (val result = get(RetroAchievementsApi.userCompletionProgressUrl(credentials, offset))) {
            is RaHttpResult.Body -> RetroAchievementsApi.parseUserCompletionProgress(result.text)
                ?.let(RaCompletionPageLookup::Read)
                ?: RaCompletionPageLookup.Unavailable
            RaHttpResult.Rejected -> RaCompletionPageLookup.Rejected(credentials.credentialRevision)
            RaHttpResult.NotFound, RaHttpResult.Unreachable, is RaHttpResult.RateLimited -> RaCompletionPageLookup.Unavailable
        }
    }

    /**
     * This user's recent unlocks across every game, in ONE request ([RetroAchievementsApi.userRecentAchievementsUrl]). Not cached here: the app's Activity lane
     * holds the one answer, refreshed with the bulk read. Credential rejection stays distinct from an unreachable or unusable response.
     */
    suspend fun recentUnlocks(pinned: RetroAchievementsApi.Auth? = null): RaRecentLookup {
        val credentials = usableAuth(pinned) ?: return RaRecentLookup.Unavailable
        return when (val result = get(RetroAchievementsApi.userRecentAchievementsUrl(credentials))) {
            is RaHttpResult.Body -> RetroAchievementsApi.parseRecentUnlocks(result.text)
                ?.let(RaRecentLookup::Read)
                ?: RaRecentLookup.Unavailable
            RaHttpResult.Rejected -> RaRecentLookup.Rejected(credentials.credentialRevision)
            RaHttpResult.NotFound, RaHttpResult.Unreachable, is RaHttpResult.RateLimited -> RaRecentLookup.Unavailable
        }
    }

    /**
     * One game icon, as the PNG bytes, or null. Keyless and public: it needs only the connected-services opt-in, never the user's key, and it is not a
     * RetroAchievements API call (it goes to the media host through [iconGet], not [httpGet]). Null for a closed gate, an invalid path, a body that is not
     * a PNG, or any failure.
     */
    suspend fun iconBytes(path: String): ByteArray? {
        if (!gateOpen()) return null
        val url = RetroAchievementsIcons.url(path) ?: return null
        if (!retroAchievementsIconUrlAllowed(url)) return null
        return iconGet(url)?.takeIf(::looksLikeRetroAchievementsPng)
    }

    /** This user's points, from the profile. Rejection stays distinct; any other failure is simply unavailable. */
    suspend fun userPoints(pinned: RetroAchievementsApi.Auth? = null): RaPointsLookup {
        val credentials = usableAuth(pinned) ?: return RaPointsLookup.Unavailable
        return when (val result = get(RetroAchievementsApi.userProfileUrl(credentials))) {
            is RaHttpResult.Body -> RetroAchievementsApi.parseUserPoints(result.text)
                ?.let(RaPointsLookup::Read)
                ?: RaPointsLookup.Unavailable
            RaHttpResult.Rejected -> RaPointsLookup.Rejected(credentials.credentialRevision)
            RaHttpResult.NotFound, RaHttpResult.Unreachable, is RaHttpResult.RateLimited -> RaPointsLookup.Unavailable
        }
    }

    /**
     * Validate candidate credentials without consulting [gateOpen] or the persisted [auth]. Connect-time
     * validation necessarily runs before either is available. The console-list request proves the Web API
     * key; the profile request separately proves the paired username. A successful console-list parse seeds
     * this source's static cache.
     */
    suspend fun validate(credentials: RetroAchievementsApi.Auth): RaValidation {
        if (credentials.apiKey.isBlank()) return RaValidation.KeyRejected
        if (credentials.username.isBlank()) return RaValidation.UsernameRejected

        // Validation must prove THIS candidate key. The runtime cache is credential-agnostic static data, so
        // consulting it here could let a new bad key inherit an old key's successful console-list response.
        val candidateConsoles = when (val result = loadConsoleIds(credentials)) {
            is RaLoadResult.Loaded -> result.value
            RaLoadResult.Rejected -> return RaValidation.KeyRejected
            is RaLoadResult.Unavailable -> return RaValidation.Unreachable
        }
        // The request above proved this key; its successful static payload is now safe for runtime reuse.
        mutex.withLock { consoleIds = candidateConsoles }

        return when (val result = get(RetroAchievementsApi.userProfileUrl(credentials))) {
            is RaHttpResult.Body -> {
                val profile = RetroAchievementsApi.parseUserProfile(result.text)
                    ?: return RaValidation.Unreachable
                // RA resolves `u` against display_name OR username case-insensitively (RAWeb
                // FindUserByIdentifierAction under the `uca1400_ai_ci` collation) and answers with the display
                // name's own casing. Mimir's keyboard capitalises an empty field's first letter, so the old exact
                // compare rejected real accounts ("Username not accepted") that every later progress read would
                // have resolved. A genuinely different name is still rejected.
                if (profile.username.equals(credentials.username.trim(), ignoreCase = true)) {
                    RaValidation.Accepted
                } else {
                    RaValidation.UsernameRejected
                }
            }
            RaHttpResult.Rejected -> RaValidation.KeyRejected
            RaHttpResult.NotFound -> RaValidation.UsernameRejected
            RaHttpResult.Unreachable, is RaHttpResult.RateLimited -> RaValidation.Unreachable
        }
    }

    /**
     * Both gate halves in one place: opted in AND the user supplied usable credentials. With [pinned] the credentials are the ones the caller captured when
     * its read began (a bulk read asks as ONE user for every page, whatever the stored sign-in becomes meanwhile); the opt-in is still read per request.
     */
    private fun usableAuth(pinned: RetroAchievementsApi.Auth? = null): RetroAchievementsApi.Auth? {
        if (!gateOpen()) return null
        return (pinned ?: auth())?.takeIf { it.isUsable }
    }

    private suspend fun consoleIds(
        credentials: RetroAchievementsApi.Auth,
    ): RaLoadResult<Map<Int, String>> {
        var ownsFlight = false
        val flight = mutex.withLock {
            consoleIds?.let { return RaLoadResult.Loaded(it) }
            consoleIdsFlights[credentials] ?: CompletableDeferred<RaLoadResult<Map<Int, String>>>().also {
                consoleIdsFlights[credentials] = it
                ownsFlight = true
            }
        }
        if (!ownsFlight) return flight.await()

        return try {
            val result = loadConsoleIds(credentials)
            mutex.withLock {
                if (result is RaLoadResult.Loaded) consoleIds = result.value
                if (consoleIdsFlights[credentials] === flight) consoleIdsFlights.remove(credentials)
                flight.complete(result)
            }
            result
        } catch (cancelled: CancellationException) {
            mutex.withLock {
                if (consoleIdsFlights[credentials] === flight) consoleIdsFlights.remove(credentials)
                flight.cancel(cancelled)
            }
            throw cancelled
        } catch (failure: Throwable) {
            mutex.withLock {
                if (consoleIdsFlights[credentials] === flight) consoleIdsFlights.remove(credentials)
                flight.completeExceptionally(failure)
            }
            throw failure
        }
    }

    private suspend fun loadConsoleIds(
        credentials: RetroAchievementsApi.Auth,
    ): RaLoadResult<Map<Int, String>> =
        when (val result = get(RetroAchievementsApi.consoleIdsUrl(credentials))) {
            is RaHttpResult.Body -> {
                val parsed = RetroAchievementsApi.parseConsoleIds(result.text)
                if (parsed.isEmpty()) {
                    raTransportWarning("API_GetConsoleIDs.php unusable response")
                    RaLoadResult.Unavailable(RaNoAnswerReason.PROVIDER_DATA)
                } else {
                    RaLoadResult.Loaded(parsed)
                }
            }
            RaHttpResult.Rejected -> RaLoadResult.Rejected
            RaHttpResult.NotFound -> RaLoadResult.Unavailable(RaNoAnswerReason.PROVIDER_DATA)
            RaHttpResult.Unreachable, is RaHttpResult.RateLimited -> RaLoadResult.Unavailable(RaNoAnswerReason.TRANSPORT)
        }

    private suspend fun hashIndex(
        consoleId: Int,
        credentials: RetroAchievementsApi.Auth,
    ): RaLoadResult<RetroAchievementsApi.HashIndex> {
        val flightKey = HashIndexFlightKey(consoleId, credentials)
        var ownsFlight = false
        val flight = mutex.withLock {
            val held = hashIndexByConsole[consoleId]
            if (held != null) {
                val now = clock()
                if (now < held.expiresAtMillis) return RaLoadResult.Loaded(held.value)
                // Past its freshness: a set added since is not in it, so it can no longer vouch for a file's absence and is read again. A refresh that
                // failed is not tried again until its backoff passes (the answer meanwhile is the failure, never a miss from the stale index), so expiry
                // costs at most one request per console per window and a bounded trickle when the provider is down.
                hashIndexBackoff[consoleId]?.let { if (now < it.untilMillis) return RaLoadResult.Unavailable(it.reason) }
            }
            hashIndexFlights[flightKey] ?:
                CompletableDeferred<RaLoadResult<RetroAchievementsApi.HashIndex>>().also {
                hashIndexFlights[flightKey] = it
                ownsFlight = true
            }
        }
        if (!ownsFlight) return flight.await()

        return try {
            val result = loadHashIndex(consoleId, credentials)
            mutex.withLock {
                val finished = clock()
                when (result) {
                    is RaLoadResult.Loaded -> {
                        hashIndexByConsole[consoleId] = Held(result.value, finished + indexFreshMillis)
                        hashIndexBackoff.remove(consoleId)
                    }
                    is RaLoadResult.Unavailable -> {
                        // Only an index that was HELD (and has gone stale) is backed off; a console never read keeps retrying per lookup, as it always did.
                        if (consoleId in hashIndexByConsole) {
                            hashIndexBackoff[consoleId] = RefreshBackoff(finished + indexRetryMillis, result.reason)
                        }
                    }
                    RaLoadResult.Rejected -> Unit
                }
                if (hashIndexFlights[flightKey] === flight) hashIndexFlights.remove(flightKey)
                flight.complete(result)
            }
            result
        } catch (cancelled: CancellationException) {
            mutex.withLock {
                if (hashIndexFlights[flightKey] === flight) hashIndexFlights.remove(flightKey)
                flight.cancel(cancelled)
            }
            throw cancelled
        } catch (failure: Throwable) {
            mutex.withLock {
                if (hashIndexFlights[flightKey] === flight) hashIndexFlights.remove(flightKey)
                flight.completeExceptionally(failure)
            }
            throw failure
        }
    }

    private suspend fun loadHashIndex(
        consoleId: Int,
        credentials: RetroAchievementsApi.Auth,
    ): RaLoadResult<RetroAchievementsApi.HashIndex> =
        when (val result = get(RetroAchievementsApi.gameListUrl(credentials, consoleId))) {
            is RaHttpResult.Body -> {
                val parsed = RetroAchievementsApi.parseUsableHashIndex(result.text, expectedConsoleId = consoleId)
                if (parsed == null || parsed.gameIds.isEmpty()) {
                    raTransportWarning(
                        "API_GetGameList.php unusable console=$consoleId " +
                            RetroAchievementsApi.hashIndexDiagnostic(result.text, consoleId),
                    )
                    RaLoadResult.Unavailable(RaNoAnswerReason.PROVIDER_DATA)
                } else {
                    RaLoadResult.Loaded(parsed)
                }
            }
            RaHttpResult.Rejected -> RaLoadResult.Rejected
            RaHttpResult.NotFound -> RaLoadResult.Unavailable(RaNoAnswerReason.PROVIDER_DATA)
            RaHttpResult.Unreachable, is RaHttpResult.RateLimited -> RaLoadResult.Unavailable(RaNoAnswerReason.TRANSPORT)
        }

    /**
     * Success and failure are both cached so an unavailable 2.18 MiB endpoint is never hammered per miss. A success is trusted for [indexFreshMillis] and a
     * failure for [indexRetryMillis], then the next lookup that needs the library reads it again; a refresh that fails keeps the library already held (what
     * it recognised stays true) and waits a retry window before trying again.
     */
    private suspend fun hashLibrary(): RaHashLibraryResult = hashLibraryMutex.withLock {
        val now = clock()
        val held = hashLibraryHeld
        if (held != null && now < held.expiresAtMillis) return@withLock held.value
        val fresh = hashLibraryGet()
        val finished = clock()
        val next: Held<RaHashLibraryResult> = when {
            fresh is RaHashLibraryResult.Loaded -> Held<RaHashLibraryResult>(fresh, finished + indexFreshMillis)
            held != null && held.value is RaHashLibraryResult.Loaded -> Held<RaHashLibraryResult>(held.value, finished + indexRetryMillis)
            else -> Held<RaHashLibraryResult>(fresh, finished + indexRetryMillis)
        }
        hashLibraryHeld = next
        next.value
    }

    /** Credential-specific failures must never be shared across two different candidate/runtime keys. */
    private data class HashIndexFlightKey(
        val consoleId: Int,
        val credentials: RetroAchievementsApi.Auth,
    )
}

private sealed interface RaHashAttempt {
    data class Hashed(val value: String?) : RaHashAttempt
    data class Unavailable(val reason: RaNoAnswerReason) : RaHashAttempt
}

/** The transport's status-bearing result. No URL or credentials cross this boundary. */
internal sealed interface RaHttpResult {

    data class Body(val text: String) : RaHttpResult

    /**
     * RA answered and refused the credentials: HTTP 401 (observed on-device), or a 403 whose body is RA's own JSON error ([isRetroAchievementsJsonError]). A 403 with any
     * other body (an HTML page from a firewall or CDN in front of RA) says nothing about the key and is [Unreachable].
     */
    data object Rejected : RaHttpResult

    /** The authenticated endpoint answered that its requested resource does not exist (404). */
    data object NotFound : RaHttpResult

    /** Timeout, DNS, server failure, a 403 that is not RA's own refusal, malformed URL, or a disallowed destination. */
    data object Unreachable : RaHttpResult

    /**
     * HTTP 429: the provider asked this client to slow down. [retryAfterMillis] is the wait its Retry-After header named (seconds or an HTTP date, already read and
     * capped at [RA_MAX_RETRY_AFTER_MS]), or null when it named none. The source turns this into [Unreachable] for its callers and keeps every request off the wire until
     * the wait has passed, so nothing above the transport ever sees this type.
     */
    data class RateLimited(val retryAfterMillis: Long?) : RaHttpResult
}

private sealed interface RaLoadResult<out T> {
    data class Loaded<T>(val value: T) : RaLoadResult<T>
    data object Rejected : RaLoadResult<Nothing>
    data class Unavailable(val reason: RaNoAnswerReason) : RaLoadResult<Nothing>
}

internal sealed interface RaProgressLookup {
    data class Read(val progress: RetroAchievementsApi.GameProgress) : RaProgressLookup
    data class Rejected(val credentialRevision: String?) : RaProgressLookup
    data object Unavailable : RaProgressLookup
}

/**
 * What one hash search actually concluded. Internal — [RetroAchievementsMatch] is the public shape; this is
 * the engine's own vocabulary. Recognized-without-a-set is produced only when f=1 really searched and missed
 * before the keyless whole-library membership cache confirms the hash.
 */
internal sealed interface RaLookup {

    /** A set carried this exact hash. */
    data class Hit(val gameId: Int) : RaLookup

    /** The search really ran and nothing carried the hash — including a damaged file with no usable hash. */
    data object Miss : RaLookup

    /** RA recognises this exact hash, but the successful f=1 search found no achievement-bearing set. */
    data object RecognizedNoSet : RaLookup

    /** RA answered and refused the exact credential revision that authenticated this request. */
    data class Rejected(val credentialRevision: String?) : RaLookup

    /** No safe file claim was possible; [reason] lets the app distinguish configuration, support and weather. */
    data class Unavailable(val reason: RaNoAnswerReason) : RaLookup
}

/**
 * How long a console's game-list index and the keyless whole-library hash map are trusted. The app trusts a "no set" verdict for exactly as long (a set may be
 * added later), so by the time a verdict expires the indexes it was learned from have expired too and the retry reads them again, once per console per window.
 */
const val RETRO_ACHIEVEMENTS_PROVIDER_INDEX_FRESH_MS: Long = 7L * 24L * 60L * 60L * 1000L

/** After a refresh of a stale index (or a first read of the library) fails, how long before the next try. */
const val RETRO_ACHIEVEMENTS_PROVIDER_INDEX_RETRY_MS: Long = 60L * 60L * 1000L

/** Only these provider hosts, ever — belt-and-suspenders behind the opt-in gate. */
private val RETROACHIEVEMENTS_HOSTS = setOf("retroachievements.org", "api.retroachievements.org")

internal fun isRetroAchievementsHost(host: String?): Boolean {
    val h = host?.trim()?.lowercase(Locale.US) ?: return false
    if (h.isEmpty()) return false
    return RETROACHIEVEMENTS_HOSTS.any { h == it || h.endsWith(".$it") }
}

/** The longest a Retry-After is honoured: ten minutes, whatever the provider asks. */
internal const val RA_MAX_RETRY_AFTER_MS: Long = 10L * 60L * 1000L

/** How long requests stop after a 429 that named no Retry-After. */
internal const val RA_DEFAULT_RATE_LIMIT_BACKOFF_MS: Long = 60_000L

/** The shortest backoff a 429 earns (a Retry-After of 0 still stops the very next request for a moment). */
internal const val RA_MIN_RATE_LIMIT_BACKOFF_MS: Long = 1_000L

/** How much of a 403's body is read to tell RA's own JSON refusal from a firewall's page; a larger body is not RA's. */
internal const val RA_MAX_ERROR_BODY_BYTES: Long = 16L * 1024L

/**
 * A Retry-After header as a wait in milliseconds: whole seconds, or an HTTP date (RFC 1123) measured from [nowMillis]; capped at [RA_MAX_RETRY_AFTER_MS], and 0 for a
 * date already past. Null when the header is absent or is neither form, so the caller's default applies.
 */
internal fun parseRetryAfterMillis(header: String?, nowMillis: Long): Long? {
    val text = header?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.all(Char::isDigit)) {
        val seconds = text.toLongOrNull() ?: return RA_MAX_RETRY_AFTER_MS
        return minOf(seconds, RA_MAX_RETRY_AFTER_MS / 1000L) * 1000L
    }
    val at = runCatching {
        java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull() ?: return null
    return (at - nowMillis).coerceIn(0L, RA_MAX_RETRY_AFTER_MS)
}

/**
 * Whether a 403's [body] is RetroAchievements' own JSON error (an object with a `message`, `error` or `errors` member) as opposed to anything else a 403 can carry: the
 * HTML page of a firewall, a CDN or a captive portal. Only the former says the key was refused. Never logged.
 */
internal fun isRetroAchievementsJsonError(body: String?): Boolean {
    val text = body?.trim().orEmpty()
    if (!text.startsWith("{") || !text.endsWith("}")) return false
    val parsed = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
    return parsed.containsKey("message") || parsed.containsKey("error") || parsed.containsKey("errors")
}

/**
 * Pure status classifier kept separate so the credential/error split is testable without a live socket. 401 always means the key was refused. A 403 means it only
 * when [errorBody] (read lazily, only for a 403) is RA's own JSON error; with no body, or any other body, it is a block in front of RA and unreachable. A 429 carries
 * the wait [retryAfter] (the header's value) names, read against [nowMillis].
 */
internal fun classifyRetroAchievementsResponse(
    responseCode: Int,
    errorBody: () -> String? = { null },
    retryAfter: () -> String? = { null },
    nowMillis: Long = System.currentTimeMillis(),
    readBody: () -> String,
): RaHttpResult = when (responseCode) {
    in 200..299 -> try {
        RaHttpResult.Body(readBody())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        RaHttpResult.Unreachable
    }
    401 -> RaHttpResult.Rejected
    403 -> if (isRetroAchievementsJsonError(runCatching(errorBody).getOrNull())) RaHttpResult.Rejected else RaHttpResult.Unreachable
    404 -> RaHttpResult.NotFound
    429 -> RaHttpResult.RateLimited(parseRetryAfterMillis(runCatching(retryAfter).getOrNull(), nowMillis))
    else -> RaHttpResult.Unreachable
}

internal val RetroAchievementsRedirectStatusCodes = setOf(301, 302, 303, 307, 308)

private val RetroAchievementsExactApiPaths = setOf(
    "/API/API_GetConsoleIDs.php",
    "/API/API_GetGameList.php",
    "/API/API_GetGameInfoAndUserProgress.php",
    "/API/API_GetUserProfile.php",
    "/API/API_GetUserCompletionProgress.php",
    "/API/API_GetUserRecentAchievements.php",
)

private fun retroAchievementsAuthorityAllowed(parsed: URI): Boolean =
    parsed.scheme.equals("https", ignoreCase = true) &&
        parsed.host?.lowercase(Locale.US) in RETROACHIEVEMENTS_HOSTS &&
        parsed.rawUserInfo == null &&
        parsed.port in setOf(-1, 443) &&
        parsed.rawFragment == null

private fun retroAchievementsRawQuery(parsed: URI): Map<String, String>? {
    val raw = parsed.rawQuery?.takeIf(String::isNotBlank) ?: return null
    val values = linkedMapOf<String, String>()
    for (part in raw.split('&')) {
        val key = part.substringBefore('=', missingDelimiterValue = "")
        val value = part.substringAfter('=', missingDelimiterValue = "")
        if (key.isBlank() || value.isBlank() || values.putIfAbsent(key, value) != null) return null
    }
    return values
}

private fun String.isPositiveDecimal(): Boolean =
    isNotEmpty() && all(Char::isDigit) && toLongOrNull()?.let { it > 0L } == true

/** Exact policy for URLs that carry the user's Web API key in their query string. */
internal fun retroAchievementsApiUrlAllowed(url: String): Boolean {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    if (!retroAchievementsAuthorityAllowed(parsed) ||
        parsed.rawPath !in RetroAchievementsExactApiPaths
    ) {
        return false
    }
    val query = retroAchievementsRawQuery(parsed) ?: return false
    if (query["y"].isNullOrBlank()) return false
    return when (parsed.rawPath) {
        "/API/API_GetConsoleIDs.php" ->
            query.keys == setOf("y", "a", "g") && query["a"] == "1" && query["g"] == "1"
        "/API/API_GetGameList.php" ->
            query.keys == setOf("y", "i", "h", "f") &&
                query["i"]?.isPositiveDecimal() == true &&
                query["h"] == "1" &&
                query["f"] == "1"
        "/API/API_GetGameInfoAndUserProgress.php" ->
            query.keys == setOf("y", "u", "g") &&
                !query["u"].isNullOrBlank() &&
                query["g"]?.isPositiveDecimal() == true
        "/API/API_GetUserCompletionProgress.php" ->
            query.keys == setOf("y", "u", "c", "o") &&
                !query["u"].isNullOrBlank() &&
                query["c"]?.isPositiveDecimal() == true &&
                query["o"]?.all { it in '0'..'9' } == true && !query["o"].isNullOrEmpty()
        "/API/API_GetUserRecentAchievements.php" ->
            query.keys == setOf("y", "u", "m") &&
                !query["u"].isNullOrBlank() &&
                query["m"]?.isPositiveDecimal() == true && query["m"]?.toLongOrNull()?.let { it <= RetroAchievementsApi.RECENT_UNLOCKS_WINDOW_MINUTES } == true
        "/API/API_GetUserProfile.php" ->
            query.keys == setOf("y", "u") && !query["u"].isNullOrBlank()
        else -> false
    }
}

/** Exact keyless endpoint approved for whole-library membership. */
internal fun retroAchievementsHashLibraryUrlAllowed(url: String): Boolean {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    return retroAchievementsAuthorityAllowed(parsed) &&
        parsed.rawPath == "/dorequest.php" &&
        retroAchievementsRawQuery(parsed) == mapOf("r" to "hashlibrary")
}

private fun retroAchievementsRedirectTarget(
    currentUrl: String,
    location: String?,
    allowed: (String) -> Boolean,
): String? {
    val raw = location?.takeIf(String::isNotBlank) ?: return null
    val resolved = runCatching {
        val current = URI(currentUrl)
        val target = if (raw.startsWith("?")) {
            currentUrl.substringBefore('?').substringBefore('#') + raw
        } else {
            current.resolve(raw).toString()
        }
        target.takeIf { URI(it).rawPath == current.rawPath }
    }.getOrNull() ?: return null
    return resolved.takeIf(allowed)
}

internal fun retroAchievementsApiRedirectTarget(currentUrl: String, location: String?): String? =
    retroAchievementsRedirectTarget(currentUrl, location, ::retroAchievementsApiUrlAllowed)

internal fun retroAchievementsHashLibraryRedirectTarget(currentUrl: String, location: String?): String? =
    retroAchievementsRedirectTarget(currentUrl, location, ::retroAchievementsHashLibraryUrlAllowed)

internal fun retroAchievementsContentLengthAllowed(reportedBytes: Long, maxBytes: Long): Boolean =
    maxBytes > 0L && (reportedBytes == -1L || reportedBytes in 1..maxBytes)

internal fun readRetroAchievementsUtf8Bounded(input: InputStream, maxBytes: Long): String? {
    if (maxBytes <= 0L) return null
    val output = ByteArrayOutputStream(minOf(maxBytes, 16L * 1024L).toInt())
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    input.use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) {
                val byte = stream.read()
                if (byte < 0) break
                total++
                if (total > maxBytes) return null
                output.write(byte)
                continue
            }
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
    }
    return output.toString(Charsets.UTF_8.name()).takeIf(String::isNotEmpty)
}

/**
 * Exact-route HTTPS GET. Credential rejection is distinct from every other failure so it cannot be rendered
 * as a transient network miss. NEVER logs the URL — it carries the user's API key.
 */
internal suspend fun defaultRetroAchievementsGet(url: String): RaHttpResult = withContext(Dispatchers.IO) {
    var current = url
    var redirects = 0
    try {
        while (redirects <= RA_MAX_REDIRECTS) {
            if (!retroAchievementsApiUrlAllowed(current)) return@withContext RaHttpResult.Unreachable
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mimir/0.1 (Android)")
            }
            try {
                val code = connection.responseCode
                if (code in RetroAchievementsRedirectStatusCodes) {
                    if (redirects == RA_MAX_REDIRECTS) return@withContext RaHttpResult.Unreachable
                    current = retroAchievementsApiRedirectTarget(
                        currentUrl = current,
                        location = connection.getHeaderField("Location"),
                    ) ?: return@withContext RaHttpResult.Unreachable
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    raTransportWarning("${RetroAchievementsApi.redact(current)} HTTP $code")
                    return@withContext classifyRetroAchievementsResponse(
                        responseCode = code,
                        // Only a 403 is read, and only to tell RA's own JSON refusal from a firewall's page; the body is never logged.
                        errorBody = { if (code == 403) readRetroAchievementsErrorBody(connection) else null },
                        retryAfter = { connection.getHeaderField("Retry-After") },
                    ) {
                        error("non-success RetroAchievements responses do not expose a body")
                    }
                }
                if (!retroAchievementsContentLengthAllowed(
                        connection.contentLengthLong,
                        RA_MAX_API_RESPONSE_BYTES,
                    )
                ) {
                    return@withContext RaHttpResult.Unreachable
                }
                val body = readRetroAchievementsUtf8Bounded(
                    connection.inputStream,
                    RA_MAX_API_RESPONSE_BYTES,
                ) ?: return@withContext RaHttpResult.Unreachable
                return@withContext RaHttpResult.Body(body)
            } finally {
                connection.disconnect()
            }
        }
        RaHttpResult.Unreachable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Endpoint name + exception TYPE only. Messages can echo a credential-bearing URL.
        raTransportWarning("${RetroAchievementsApi.redact(current)} ${error.javaClass.simpleName}")
        RaHttpResult.Unreachable
    }
}

/** The body of a refused request, read at most [RA_MAX_ERROR_BODY_BYTES] and only to be classified; null when there is none or it is larger. */
private fun readRetroAchievementsErrorBody(connection: HttpURLConnection): String? =
    runCatching { connection.errorStream?.let { readRetroAchievementsUtf8Bounded(it, RA_MAX_ERROR_BODY_BYTES) } }.getOrNull()

internal const val RA_MAX_API_RESPONSE_BYTES = 4L * 1024L * 1024L
internal const val RA_MAX_HASH_LIBRARY_RESPONSE_BYTES = 4L * 1024L * 1024L
internal const val RA_MAX_REDIRECTS = 3

private const val RA_TRANSPORT_TAG = "MimirRA"

/** Local JVM tests do not provide android.util.Log; diagnostics must never alter transport semantics. */
private fun raTransportWarning(message: String) {
    runCatching { Log.w(RA_TRANSPORT_TAG, message) }
}

/** One counts-only line under the MimirRA tag (the bulk read's per-page diagnostic). Never a name, key, URL or title; a plain JVM test has no logcat. */
internal fun raDiagnosticLine(message: String) {
    runCatching { Log.i(RA_TRANSPORT_TAG, message) }
}

internal sealed interface RaCompletionPageLookup {
    data class Read(val page: RetroAchievementsApi.CompletionPage) : RaCompletionPageLookup
    data class Rejected(val credentialRevision: String?) : RaCompletionPageLookup
    data object Unavailable : RaCompletionPageLookup
}

internal sealed interface RaRecentLookup {
    data class Read(val unlocks: List<RetroAchievementsApi.RecentUnlock>) : RaRecentLookup
    data class Rejected(val credentialRevision: String?) : RaRecentLookup
    data object Unavailable : RaRecentLookup
}

internal sealed interface RaPointsLookup {
    data class Read(val points: RetroAchievementsApi.UserPoints) : RaPointsLookup
    data class Rejected(val credentialRevision: String?) : RaPointsLookup
    data object Unavailable : RaPointsLookup
}

/** Exact policy for a game icon: HTTPS, RA's media host, no credential, no query, and a path of exactly `/Images/<digits>.png`. */
internal fun retroAchievementsIconUrlAllowed(url: String): Boolean {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    return parsed.scheme.equals("https", ignoreCase = true) &&
        parsed.host?.lowercase(Locale.US) == RetroAchievementsIcons.MEDIA_HOST &&
        parsed.rawUserInfo == null &&
        parsed.port in setOf(-1, 443) &&
        parsed.rawQuery == null &&
        parsed.rawFragment == null &&
        RetroAchievementsIcons.validatedMediaPath(parsed.rawPath) != null
}

internal fun readRetroAchievementsBytesBounded(input: InputStream, maxBytes: Long): ByteArray? {
    if (maxBytes <= 0L) return null
    val output = ByteArrayOutputStream(minOf(maxBytes, 16L * 1024L).toInt())
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    input.use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray().takeIf { it.isNotEmpty() }
}

/**
 * Exact-route, keyless HTTPS GET of one icon. Sends no credential and follows NO redirect (a 3xx is a refusal: the allowlist is the whole route), reads at
 * most [RetroAchievementsIcons.MAX_BYTES] and never logs the URL.
 */
internal suspend fun defaultRetroAchievementsIconGet(url: String): ByteArray? = withContext(Dispatchers.IO) {
    if (!retroAchievementsIconUrlAllowed(url)) return@withContext null
    try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "image/png")
            setRequestProperty("User-Agent", "Mimir/0.1 (Android)")
        }
        try {
            if (connection.responseCode != 200) return@withContext null
            if (!retroAchievementsContentLengthAllowed(connection.contentLengthLong, RetroAchievementsIcons.MAX_BYTES)) return@withContext null
            readRetroAchievementsBytesBounded(connection.inputStream, RetroAchievementsIcons.MAX_BYTES)
        } finally {
            connection.disconnect()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        raTransportWarning("icon ${error.javaClass.simpleName}")
        null
    }
}
