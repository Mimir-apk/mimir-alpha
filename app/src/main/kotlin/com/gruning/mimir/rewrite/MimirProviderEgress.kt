package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.remotedefs.RemoteDefinitionsEndpoint
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirEmulatorDefinitionsGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException

internal enum class MimirProviderEgressRoute {
    /** One exact, user-triggered API probe used before a candidate key becomes durable authority. */
    STEAMGRIDDB_KEY_VALIDATION,
    STEAMGRIDDB_API,
    STEAMGRIDDB_MEDIA,
    SCREENSCRAPER_MEDIA,
    /** Keyless Steam storefront `short_description` lookup, `pc_games`-only, riding the SteamGridDB gate
     *   — see [MimirSteamGridDbGate]'s kdoc for why no separate toggle exists. */
    STEAM_STORE_API,
    /** The emulator definitions pointer and release (Settings > Emulators), only while the player's own press is running. */
    EMULATOR_DEFINITIONS,
}

internal interface MimirProviderEgressResponse {
    val statusCode: Int
    fun header(name: String): String?
    val declaredLength: Long
    fun body(): InputStream
    fun close()
}

internal fun interface MimirProviderEgressOpener {
    fun open(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestHeaders: Map<String, String>,
    ): MimirProviderEgressResponse?
}

internal object MimirProviderEgress {
    private val liveOpener = MimirProviderEgressOpener(::liveOpen)

    fun open(
        route: MimirProviderEgressRoute,
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestHeaders: Map<String, String> = emptyMap(),
        opener: MimirProviderEgressOpener = liveOpener,
    ): MimirProviderEgressResponse? {
        if (!urlAllowed(route, url)) return null
        val host = parseUrlHost(url) ?: return null
        if (!gateAllowsRequestTo(route, host)) return null
        return opener.open(url, connectTimeoutMs, readTimeoutMs, requestHeaders)
    }

    private fun urlAllowed(route: MimirProviderEgressRoute, url: String): Boolean = when (route) {
        MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION ->
            url == SteamGridDbKeyValidationUrl
        MimirProviderEgressRoute.STEAMGRIDDB_API -> steamGridDbApiUrlAllowed(url)
        MimirProviderEgressRoute.STEAMGRIDDB_MEDIA -> steamGridDbMediaUrlAllowed(url)
        MimirProviderEgressRoute.SCREENSCRAPER_MEDIA ->
            customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, url)
        MimirProviderEgressRoute.STEAM_STORE_API -> steamStoreAppDetailsUrlAllowed(url)
        MimirProviderEgressRoute.EMULATOR_DEFINITIONS -> RemoteDefinitionsEndpoint.urlAllowed(url)
    }

    private fun gateAllowsRequestTo(route: MimirProviderEgressRoute, host: String): Boolean = when (route) {
        // Submitting a key is the explicit opt-in action. This route is restricted above to one harmless
        // autocomplete URL, so validation can happen before the durable-key gate is opened.
        MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION ->
            MimirSteamGridDbGate.isAllowedHost(host)
        MimirProviderEgressRoute.STEAMGRIDDB_API,
        MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
        MimirProviderEgressRoute.STEAM_STORE_API,
        -> MimirSteamGridDbGate.allowsRequestTo(host)
        MimirProviderEgressRoute.SCREENSCRAPER_MEDIA -> MimirConnectedArtGate.allowsRequestTo(host)
        MimirProviderEgressRoute.EMULATOR_DEFINITIONS -> MimirEmulatorDefinitionsGate.allowsRequestTo(host)
    }
}

/**
 * Hardened admission for the Steam storefront description endpoint: exact scheme, exact host (no
 * subdomain — Valve serves this only at the bare `store.steampowered.com`, unlike SteamGridDB's `cdn2.`
 * media host), exact path, and a query string that is nothing but one positive-decimal `appids` — the same
 * shape [com.gruning.mimir.rewrite.core.launch.GameNativeLaunchRoute.parseAppId] already pins for a
 * `.steam` shortcut's own app id. No redirect helper exists for this route deliberately: a 3xx here means
 * "not the exact endpoint we pinned", and the caller must fail closed, never follow it.
 */
internal fun steamStoreAppDetailsUrlAllowed(url: String): Boolean {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    if (!parsed.scheme.equals("https", ignoreCase = true) ||
        parsed.host?.lowercase() != "store.steampowered.com" ||
        parsed.rawUserInfo != null ||
        parsed.port !in setOf(-1, 443) ||
        parsed.rawFragment != null
    ) {
        return false
    }
    if (parsed.rawPath != "/api/appdetails") return false
    val query = parsed.rawQuery ?: return false
    return query.matches(Regex("^appids=[1-9][0-9]*$"))
}

/** Builds the one exact URL this route ever requests — never string-concatenated ad hoc at the call site. */
internal fun steamStoreAppDetailsUrl(appId: Int): String =
    "https://store.steampowered.com/api/appdetails?appids=$appId"

internal const val SteamGridDbKeyValidationUrl =
    "https://www.steamgriddb.com/api/v2/search/autocomplete/Mimir"

internal fun parseUrlHost(url: String): String? =
    runCatching { URI(url).host }.getOrNull()

internal fun routeForSource(source: CustomHeroSource): MimirProviderEgressRoute? = when (source) {
    CustomHeroSource.STEAMGRIDDB -> MimirProviderEgressRoute.STEAMGRIDDB_MEDIA
    CustomHeroSource.SCREENSCRAPER -> MimirProviderEgressRoute.SCREENSCRAPER_MEDIA
}

internal fun mediaRouteFor(url: String): MimirProviderEgressRoute? = when {
    steamGridDbMediaUrlAllowed(url) -> MimirProviderEgressRoute.STEAMGRIDDB_MEDIA
    customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, url) ->
        MimirProviderEgressRoute.SCREENSCRAPER_MEDIA
    else -> null
}

private class LiveMimirProviderEgressResponse(
    private val connection: HttpURLConnection,
) : MimirProviderEgressResponse {
    override val statusCode: Int
        get() = connection.responseCode

    override fun header(name: String): String? = connection.getHeaderField(name)

    override val declaredLength: Long
        get() = connection.contentLengthLong

    override fun body(): InputStream =
        if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

    override fun close() {
        runCatching { connection.disconnect() }
    }
}

private fun liveOpen(
    url: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    requestHeaders: Map<String, String>,
): MimirProviderEgressResponse? {
    var connection: HttpURLConnection? = null
    return try {
        connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.connect()
        LiveMimirProviderEgressResponse(connection)
    } catch (cancelled: CancellationException) {
        connection?.disconnect()
        throw cancelled
    } catch (_: Exception) {
        connection?.disconnect()
        null
    }
}
