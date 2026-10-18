package com.gruning.mimir.rewrite.core.metadata.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * The IGDB v4 / Twitch OAuth API contract — PURE url/body-building + response-parsing (no network, no
 * Android), the same split [ScreenScraperApi] draws against [ScreenScraperMetadataSource]. The one live
 * socket lives in [IgdbMetadataSource].
 *
 * ⚠️ CREDENTIAL SAFETY: [tokenFormBody] carries the Twitch client secret in its FORM BODY, never a query
 * string (IGDB's own docs put it in the URL; that is refused here — a URL reaches logs/proxies a body
 * does not). The mint response's `access_token` is a bearer credential too. Neither is ever logged.
 */
internal object IgdbApi {

    private const val GAMES_URL = "https://api.igdb.com/v4/games"
    private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Sanitised, length-capped Apicalypse query for one name-search candidate on one IGDB platform id. */
    fun gamesQueryBody(title: String, igdbPlatformId: Int): String {
        val cleaned = title
            .filter { it != '"' && it != '\\' && !it.isISOControl() }
            .trim()
            .take(MAX_TITLE_CHARS)
        return "search \"$cleaned\"; fields name,summary,platforms,version_parent; " +
            "where platforms = ($igdbPlatformId) & version_parent = null; limit 10;"
    }

    /** Form-encoded (never query-string) client-credentials mint body. */
    fun tokenFormBody(clientId: String, clientSecret: String): String =
        "client_id=${enc(clientId)}&client_secret=${enc(clientSecret)}&grant_type=client_credentials"

    /** One parsed `games` hit — tolerant like [ScreenScraperApi.parseMedias]: a bad entry is skipped, never thrown. */
    data class Hit(
        val id: Long?,
        val name: String,
        val summary: String?,
        val platforms: List<Int>,
    )

    /** Parse a `games` POST response body into hits, empty on any miss. */
    fun parseGames(body: String): List<Hit> = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = strOf(o, "name") ?: return@mapNotNull null
            Hit(
                id = longOf(o, "id"),
                name = name,
                summary = strOf(o, "summary")?.let(::normalizeSynopsis),
                platforms = (o["platforms"]?.let { runCatching { it.jsonArray }.getOrNull() })
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }
                    .orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    /** Parse a Twitch OAuth token-mint response into `(accessToken, expiresInSeconds)`, null on any miss. */
    fun parseToken(body: String): Pair<String, Long>? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val token = strOf(o, "access_token") ?: return null
        val expiresIn = longOf(o, "expires_in") ?: return null
        token to expiresIn
    }.getOrNull()

    /** Reuse [ScreenScraperApi]'s conservative cross-provider name matcher — see that call site's kdoc. */
    fun titleMatches(requested: String, providerTitle: String): Boolean =
        ProviderTitleMatcher.matches(requested, providerTitle)

    /** Exact-route policy for the `games` POST — no userinfo, no non-443 port, no fragment, no query. */
    fun apiUrlAllowed(url: String): Boolean = exactUrlAllowed(url, GAMES_URL)

    /** Exact-route policy for the Twitch token mint. */
    fun tokenUrlAllowed(url: String): Boolean = exactUrlAllowed(url, TOKEN_URL)

    private fun exactUrlAllowed(url: String, expected: String): Boolean {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val expectedParsed = runCatching { java.net.URI(expected) }.getOrNull() ?: return false
        return parsed.scheme.equals("https", ignoreCase = true) &&
            parsed.host?.lowercase() == expectedParsed.host?.lowercase() &&
            parsed.rawUserInfo == null &&
            parsed.port in setOf(-1, 443) &&
            parsed.rawFragment == null &&
            parsed.rawQuery == null &&
            parsed.rawPath == expectedParsed.rawPath
    }

    private fun normalizeSynopsis(raw: String): String? =
        raw.replace(Regex("""\s+"""), " ").trim().take(MAX_SYNOPSIS_CHARS).takeIf(String::isNotEmpty)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun strOf(o: JsonObject, key: String): String? =
        o[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun longOf(o: JsonObject, key: String): Long? =
        o[key]?.jsonPrimitive?.content?.trim()?.toLongOrNull()

    private const val MAX_TITLE_CHARS = 120
    private const val MAX_SYNOPSIS_CHARS = 4_000
}
