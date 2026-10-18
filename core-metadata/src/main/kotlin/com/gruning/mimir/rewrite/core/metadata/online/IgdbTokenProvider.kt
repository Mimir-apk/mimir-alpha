package com.gruning.mimir.rewrite.core.metadata.online

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight, in-memory Twitch client-credentials token cache for IGDB (R6-P3-01). Mints a token only
 * on the FIRST IGDB rung of a process — never at startup — and re-mints when absent, when within 24h of
 * the recorded expiry, or on [forceMint] (a source-observed 401).
 *
 * NOT persisted, deliberately: a mint happens at most once per process per credential set under this
 * app's own request budget (§2.6), so the Twitch FAQ's 25-active-token cap is never approached by normal
 * use; a token inactivated by that cap (or by expiry drift on another install) surfaces as one 401, which
 * [IgdbMetadataSource] answers with exactly one [invalidate] + retry. Persisting the token would add a
 * bearer secret at rest for no gain.
 */
internal class IgdbTokenProvider(
    private val clientId: () -> String,
    private val clientSecret: () -> String,
    private val mint: suspend (clientId: String, clientSecret: String) -> IgdbTokenMintResult,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private var cached: CachedToken? = null

    /** Current bearer token, minting/re-minting as needed. Null on any mint failure. */
    suspend fun token(forceMint: Boolean = false): String? = mutex.withLock {
        val now = clock()
        val current = cached
        if (!forceMint && current != null && now + REMINT_WINDOW_MS < current.expiresAtMillis) {
            return@withLock current.value
        }
        when (val result = mint(clientId(), clientSecret())) {
            is IgdbTokenMintResult.Minted -> {
                cached = CachedToken(result.accessToken, now + result.expiresInSeconds * 1_000L)
                result.accessToken
            }
            IgdbTokenMintResult.Unreachable, IgdbTokenMintResult.Failed -> {
                cached = null
                null
            }
        }
    }

    /** Drop the cached token — called after a 401, so the very next [token] call re-mints. */
    suspend fun invalidate() = mutex.withLock { cached = null }

    private data class CachedToken(val value: String, val expiresAtMillis: Long) {
        /** Redacted: [value] is a live bearer token -- a data class's default toString must never carry it. */
        override fun toString(): String = "CachedToken(value=***, expiresAtMillis=$expiresAtMillis)"
    }

    private companion object {
        /** Re-mint proactively once the cached token is within 24h of its recorded expiry. */
        const val REMINT_WINDOW_MS = 24L * 60L * 60L * 1_000L
    }
}

/** The mint transport's typed result — never a thrown exception, no URL or secret in either failure arm. */
internal sealed interface IgdbTokenMintResult {
    data class Minted(val accessToken: String, val expiresInSeconds: Long) : IgdbTokenMintResult {
        /** Redacted: [accessToken] is a live bearer token -- never in a default toString. */
        override fun toString(): String = "Minted(accessToken=***, expiresInSeconds=$expiresInSeconds)"
    }
    /** Transport failure (timeout, IO, disallowed URL) — distinct from an honest credential rejection. */
    data object Unreachable : IgdbTokenMintResult
    /** Twitch answered but refused/could not be parsed (bad client id/secret, non-JSON body). */
    data object Failed : IgdbTokenMintResult
}
