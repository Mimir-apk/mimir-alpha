package com.gruning.mimir.rewrite.core.metadata.online

import android.util.Log
import com.gruning.mimir.rewrite.core.metadata.ContentHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * The ScreenScraper.fr API contract — PURE url-building + response-parsing (no network, no Android),
 * so it is fully unit-testable off-device. The one live socket lives in [ScreenScraperMetadataSource].
 *
 * ⚠️ CREDENTIAL SAFETY: every URL carries `devid`/`devpassword` (and, when supplied, `ssid`/`sspassword`)
 * as query params. These URLs must NEVER be logged. Callers log a redacted endpoint name only.
 *
 * ⚠️ UNVERIFIED CONTRACT POINTS (confirm on a live account; tolerant parsing preserves a miss): exact media-type key spelling (`support-2D` vs `support-label`), the
 * `mediaJeu` resize param spelling, and per-tier quota numbers (always read live, never assume).
 * The FLAT-ART lane's type strings are equally unverified: `wheel` / `wheel-hd` (clear-logo),
 * the only two in the current resolver (ArtMediaResolver.LOGO_TYPE_PRIORITY). If the live spelling differs, every
 * logo pick silently misses (honest fallback to the box cover) — `ScreenScraperFlatArtLiveTest` is the
 * proof vehicle; run it on a connected account before claiming the flat-art lane works.
 */
internal object ScreenScraperApi {

    private const val BASE = "https://www.screenscraper.fr/api2"

    /** Lenient + tolerant: ScreenScraper mixes string/number types and nests deeply; we navigate defensively. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Read a user's live quota/thread caps. Validates a member sign-in (the connect flow's first call). */
    fun userInfoUrl(dev: DevAuth, user: UserAuth?): String =
        "$BASE/ssuserInfos.php?" + authParams(dev, user)

    /**
     * Server infrastructure info — a DEV-ONLY endpoint (no member needed). We hit this FIRST in the connect
     * flow to check the build's own developer registration, because [userInfoUrl] masks a bad developer key
     * as a "check user credentials" error (it's the member endpoint, so it blames the member). ssinfraInfos
     * names the developer error plainly, letting us tell a build problem apart from a bad member sign-in.
     */
    fun infraInfoUrl(dev: DevAuth): String = "$BASE/ssinfraInfos.php?" + authParams(dev, null)

    /**
     * One system presentation image. The returned URL carries credentials and is a download input only:
     * never log, render remotely, or persist it. The provider performs the bounded resize; the app then
     * normalizes the decoded still onto Mimir's square icon canvas.
     */
    fun systemMediaUrl(
        dev: DevAuth,
        user: UserAuth?,
        systemeId: Int,
        media: String,
    ): String = "$BASE/mediaSysteme.php?" + authParams(dev, user) +
        "&systemeid=$systemeId&media=${enc(media)}&maxwidth=1024&maxheight=1024&outputformat=png"

    /**
     * One game record by fingerprint/name. Hash-first (privacy: no filename leaves the device on a hit);
     * [romName] is an already-CLEANED base name used only for the name fallback, never a raw path.
     */
    fun gameInfoUrl(
        dev: DevAuth,
        user: UserAuth?,
        systemeId: Int,
        hash: ContentHash?,
        romName: String?,
        /** a previously verified `jeu.id` — the most precise evidence, sent alone when present. */
        providerGameId: String? = null,
    ): String {
        val sb = StringBuilder("$BASE/jeuInfos.php?").append(authParams(dev, user))
        sb.append("&systemeid=").append(systemeId)
        providerGameId?.takeIf { it.isNotBlank() }?.let { sb.append("&gameid=").append(enc(it)) }
        hash?.crc32?.let { sb.append("&crc=").append(enc(it)) }
        hash?.md5?.let { sb.append("&md5=").append(enc(it)) }
        hash?.sha1?.let { sb.append("&sha1=").append(enc(it)) }
        hash?.sizeBytes?.let { sb.append("&romtaille=").append(it) }
        romName?.takeIf { it.isNotBlank() }?.let { sb.append("&romnom=").append(enc(cleanRomName(it))) }
        return sb.toString()
    }

    /** The response's OWN game identity — [systemeId] drives ART02's strict cross-system rejection. */
    data class GameIdentity(val gameId: String?, val systemeId: Int?)

    /**
     * Parse `jeu.id` + `jeu.systeme.id` from a jeuInfos body. jeuInfos fuzzy name search happily
     * matches ACROSS systems (the gb→gba wrong-art class) — callers must verify [GameIdentity.systemeId]
     * equals the REQUESTED system before accepting any media. Null when there is no jeu block at all.
     */
    fun parseGameIdentity(body: String): GameIdentity? = runCatching {
        val jeu = response(body)?.get("jeu")?.jsonObject ?: return null
        GameIdentity(
            gameId = strOf(jeu, "id"),
            systemeId = jeu["systeme"]?.jsonObject?.let { intOf(it, "id") },
        )
    }.getOrNull()

    /**
     * ScreenScraper's regional title, never the query string echoed back by Mimir. Only an explicitly
     * requested region may supply presentation copy: falling through to `nom_ss`, top-level `jeu.nom`,
     * or an arbitrary first entry can silently localize the shelf from provider/device state.
     */
    fun parseCanonicalTitle(body: String, preferredRegions: List<String>): String? = runCatching {
        val jeu = response(body)?.get("jeu")?.jsonObject ?: return null
        val nomsObject = jeu["noms"]?.let { runCatching { it.jsonObject }.getOrNull() }
        // Live JSON uses [{"region":"us","text":"..."}], while older fixtures/API variants expose
        // {"nom_us":"..."}. Both are legitimate serializations of the provider's regional names.
        val nomsArray = jeu["noms"]?.let { runCatching { it.jsonArray }.getOrNull() }
        fun regionalName(region: String): String? =
            nomsObject?.let { strOf(it, "nom_$region") }
                ?: nomsArray?.asSequence()
                    ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                    ?.firstOrNull { strOf(it, "region")?.equals(region, ignoreCase = true) == true }
                    ?.let { strOf(it, "text") }
        preferredRegions.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.lowercase() }
            .distinct()
            .mapNotNull(::regionalName)
            .firstOrNull()
    }.getOrNull()?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf(String::isNotEmpty)

    /**
     * The response's own name for the system the game is on (`jeu.systeme.text`, else `nom`), or null when it carries none.
     * Read by the PC logo lane, which accepts a system only by NAME as well as by id.
     */
    fun parseSystemName(body: String): String? = runCatching {
        val systeme = response(body)?.get("jeu")?.jsonObject?.get("systeme")?.jsonObject ?: return null
        strOf(systeme, "text") ?: strOf(systeme, "nom")
    }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

    /**
     * Provider identity evidence for rejecting a neighbouring name-search result. This deliberately may
     * read ScreenScraper's internal/default name because it is never presentation copy; callers must use
     * [parseCanonicalTitle] for anything shown or cached as the user's title.
     */
    internal fun parseProviderIdentityTitle(body: String, preferredRegions: List<String>): String? =
        parseCanonicalTitle(body, preferredRegions) ?: runCatching {
            val jeu = response(body)?.get("jeu")?.jsonObject ?: return null
            val nomsObject = jeu["noms"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val nomsArray = jeu["noms"]?.let { runCatching { it.jsonArray }.getOrNull() }
            strOf(nomsObject ?: jeu, if (nomsObject != null) "nom_ss" else "nom")
                ?: strOf(jeu, "nom")
                ?: nomsArray?.asSequence()
                    ?.mapNotNull { runCatching { it.jsonObject }.getOrNull()?.let { name -> strOf(name, "text") } }
                    ?.firstOrNull()
        }.getOrNull()?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf(String::isNotEmpty)

    /**
     * A name-only response must still identify the requested title, not merely another game on the same
     * console. Exact provider-id or hash attempts are stronger evidence and bypass this conservative check.
     */
    fun canonicalTitleMatches(requested: String, providerTitle: String): Boolean =
        ProviderTitleMatcher.matches(requested, providerTitle)

    private fun authParams(dev: DevAuth, user: UserAuth?): String {
        val sb = StringBuilder()
            .append("devid=").append(enc(dev.devId))
            .append("&devpassword=").append(enc(dev.devPassword))
            .append("&softname=").append(enc(dev.softName))
            .append("&output=json")
        if (user != null && user.id.isNotBlank()) {
            sb.append("&ssid=").append(enc(user.id)).append("&sspassword=").append(enc(user.password))
        }
        return sb.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private val releaseNumberPrefix = Regex("""^\s*\d+\s*-\s*""")   // "2623 - " No-Intro-style set number
    private val tagGroups = Regex("""\s*[(\[][^)\]]*[)\]]""")        // "(Europe) (En,Fr,…)" / "[b]" etc.

    /**
     * Strip ROM-name noise ScreenScraper's name search can't match, so the lookup uses the real title.
     * VERIFIED LIVE: the raw name `2623 - Final Fantasy IV (Europe) (En,Fr,De,Es,It)` returns HTTP 404, but
     * the cleaned `Final Fantasy IV` matches with box art. Removes a leading `NNNN - ` release-number prefix
     * and trailing region/language/revision tag groups. Extension-stripping is the caller's job (a title may
     * legitimately contain a dot, e.g. "Super Mario Bros. 3"). Applies to EVERY platform, not just DS.
     */
    fun cleanRomName(raw: String): String =
        raw.replace(releaseNumberPrefix, "").replace(tagGroups, " ").trim().replace(Regex("""\s+"""), " ")

    /**
     * One media entry from `jeu.medias[]`. [type] e.g. box-2D / box-3D / support-2D; [region] wor/eu/us/jp.
     *
     * Same-type candidates cannot be ranked by pixel fit or size: `jeu.medias[]` entries carry
     * `type`/`parent`/`url`/`region`/`crc`/`md5`/`sha1`/`size`(bytes)/`format`/`posx`/`posy`/`posw`/`posh`
     * (the last four are composite-mix placement coordinates for `mixrbv1`/`mixrbv2`, not the media's own
     * pixel dimensions) — no `width`/`height`. The byte-count `size` field is not a usable proxy for pixel
     * fit/aspect (see [ArtMediaResolver.pickHeroArtUrl] — no size-based tie-break is implemented for this
     * reason).
     * Contrast [com.gruning.mimir.rewrite.CustomHeroFetcher]'s SteamGridDB path, which DOES receive and
     * rank on real `width`/`height` from that provider's JSON — the gap is provider-specific, not a Mimir
     * parsing omission.
     */
    data class Media(val type: String, val url: String, val region: String?, val format: String?)

    /** Parse the `ssuser` block → live caps. Null when absent/unauthenticated (caller treats as sign-in fail). */
    fun parseUserQuota(body: String): UserQuota? = runCatching {
        val ssuser = response(body)?.get("ssuser")?.jsonObject ?: return null
        // Isolated, because this whole function is a `runCatching`: a diagnostic that can throw
        // inside it would turn a good quota into a null one -- i.e. into a sign-in failure. It did
        // exactly that the moment it was written (android.util.Log is not mocked on the JVM, and
        // three parse tests started returning null), which is the same failure a caller would see
        // on any device where logging misbehaved. A diagnostic must never break what it measures.
        runCatching { logUserQuotaShape(ssuser) }
        UserQuota(
            maxThreads = intOf(ssuser, "maxthreads") ?: 1,
            maxRequestsPerDay = intOf(ssuser, "maxrequestsperday") ?: 0,
            requestsToday = intOf(ssuser, "requeststoday") ?: 0,
        )
    }.getOrNull()

    /**
     * Which NUMERIC fields the `ssuser` block actually carried, to answer one question the parsed
     * [UserQuota] cannot: an account was reported to allow six concurrent threads, while a run on
     * 2026-09-17 logged `concurrency=2` — parsed from a real `maxthreads` of 2, not from the `?: 1`
     * default. Either ScreenScraper reports the API allowance differently from the figure on the account
     * page, or the width lives in a field Mimir does not read. Three lines of log settle it; guessing
     * does not, and the difference is roughly 17 vs 78 games per minute.
     *
     * **Numeric values only, and never a string.** `toIntOrNull` already drops every string-valued field,
     * which is what the login, the password, the dev credentials and the email all are. [QuotaLogDenyKeys]
     * covers the remaining case of an identifier that happens to parse as a number. ScreenScraper
     * credentials and media URLs must never reach logcat, so this errs toward dropping a field it could
     * have safely printed rather than printing one it should not.
     */
    private fun logUserQuotaShape(ssuser: JsonObject) {
        val fields = ssuser.keys
            .filter { key ->
                val lower = key.lowercase()
                lower !in QuotaLogDenyKeys && QuotaLogDenyFragments.none { lower.contains(it) }
            }
            .mapNotNull { key -> intOf(ssuser, key)?.let { "$key=$it" } }
            .sorted()
        Log.i("MimirArtworkQuota", "ssuser numeric fields (" + fields.size + "): " + fields.joinToString(" "))
    }

    /** Parse `jeu.medias[]`. Empty on any miss — an empty list means "keep the silhouette", never a crash. */
    fun parseMedias(body: String): List<Media> = runCatching {
        val jeu = response(body)?.get("jeu")?.jsonObject ?: return emptyList()
        val medias = jeu["medias"]?.jsonArray ?: return emptyList()
        medias.mapNotNull { el ->
            val o = el.jsonObject
            val url = strOf(o, "url") ?: return@mapNotNull null
            val type = strOf(o, "type") ?: return@mapNotNull null
            Media(type = type, url = url, region = strOf(o, "region"), format = strOf(o, "format"))
        }
    }.getOrDefault(emptyList())

    /**
     * Parse both ScreenScraper JSON shapes: the live `jeu.synopsis[]` entries (`langue` + `text`) and the
     * legacy/object `synopsis_xx` map. Only an explicitly preferred language may be returned; release-alpha
     * passes English alone, so a provider record with only French/Japanese copy stays honestly unavailable
     * instead of silently changing the product language. Provider text is normalized/bounded.
     */
    fun parseSynopsis(body: String, preferredLanguages: List<String>): String? = runCatching {
        val jeu = response(body)?.get("jeu")?.jsonObject ?: return null
        // A ROM-specific synopsis overrides the general game synopsis when ScreenScraper supplies one.
        val synopsisElements = listOfNotNull(
            jeu["rom"]?.let { runCatching { it.jsonObject["synopsis"] }.getOrNull() },
            jeu["synopsis"],
        )
        if (synopsisElements.isEmpty()) return null
        val preferred = preferredLanguages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.lowercase() }
            .distinct()
            .toList()
        val raw = synopsisElements.asSequence().mapNotNull { synopsisElement ->
            val synopsisObject = runCatching { synopsisElement.jsonObject }.getOrNull()
            val objectCopy = synopsisObject?.let { synopsis ->
                preferred.asSequence()
                    .mapNotNull { language -> strOf(synopsis, "synopsis_$language") }
                    .firstOrNull()
            }
            if (objectCopy != null) return@mapNotNull objectCopy

            val entries = runCatching { synopsisElement.jsonArray }.getOrNull()
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .orEmpty()
            preferred.asSequence()
                .mapNotNull { language ->
                    entries.firstOrNull {
                        strOf(it, "langue")?.equals(language, ignoreCase = true) == true
                    }?.let { strOf(it, "text") }
                }
                .firstOrNull()
        }.firstOrNull()
            ?: return null
        raw.replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_SYNOPSIS_CHARS)
            .takeIf(String::isNotEmpty)
    }.getOrNull()

    /** ScreenScraper surfaces auth/quota errors as a plain-text or header message; extract it for honest copy. */
    fun parseErrorMessage(body: String): String? = runCatching {
        val trimmed = body.trim()
        // Non-JSON error payloads (e.g. "API fermé pour les non membres", blacklist notice) come back raw.
        if (!trimmed.startsWith("{")) return trimmed.takeIf { it.isNotEmpty() && it.length < 300 }
        response(body)?.get("error")?.jsonPrimitive?.content
    }.getOrNull()

    /** Provider-declared empty lookup, distinct from authentication/quota/server failure copy. */
    fun isLookupNotFound(body: String): Boolean {
        val message = (parseErrorMessage(body) ?: return false).lowercase()
        return message.contains("non trouv") || message.contains("not found") ||
            message.contains("no game") || message.contains("no rom") || message.contains("nomatch")
    }

    /**
     * Classify a non-success `ssuserInfos` body into an honest [SignInOutcome]. ScreenScraper answers auth
     * failures in French plain text with a distinct message per layer, so we can tell the user WHICH thing
     * was wrong instead of blaming their password for everything:
     *  - "…identifiants développeur…"  → the BUILD's dev key is wrong/unapproved (the user can't fix this)
     *  - "…identifiants utilisateurs…" → the member ssid/sspassword was rejected (the user CAN fix this)
     *  - "…fermé… / surcharge / non membres" → the shared allowance is closed right now
     * Order matters: both dev + member messages contain "identifiant", so test "développeur" first. Never a
     * Success/NotConfigured/Unreachable here — those are decided by the source from context, not the body.
     */
    fun classifySignInError(body: String): SignInOutcome =
        matchedSignInError((parseErrorMessage(body) ?: body).lowercase())
            ?: SignInOutcome.MemberRejected // safest actionable default

    /** The layer [msg] (lower-cased) names, or null when it names none; [classifySignInError] and [classifyLookupError] share it. */
    private fun matchedSignInError(msg: String): SignInOutcome? = when {
        msg.contains("développeur") || msg.contains("developpeur") || msg.contains("developer") ->
            SignInOutcome.DeveloperRejected
        msg.contains("fermé") || msg.contains("ferme") || msg.contains("surcharge") ||
            msg.contains("closed") || msg.contains("non membre") -> SignInOutcome.Busy
        msg.contains("utilisateur") || msg.contains("login") || msg.contains("identifiant") ||
            msg.contains("member") -> SignInOutcome.MemberRejected
        else -> null
    }

    /**
     * Why a `jeuInfos` lookup the provider answered with an error (not "not found") was refused, for the game-music card's hint.
     * The same three layers as [classifySignInError] (build key, member sign-in, shared allowance closed) plus the one that
     * sign-in never meets: the day's quota spent, which ScreenScraper words with "quota" ("Votre quota de scrape est dépassé
     * pour aujourd'hui"). It is tested FIRST, because the sign-in classifier would read it as a member rejection. An error
     * that names no layer is [ScreenScraperLookupFailure.NO_ANSWER], never a sign-in blame it cannot prove.
     */
    fun classifyLookupError(body: String): ScreenScraperLookupFailure {
        val msg = (parseErrorMessage(body) ?: body).lowercase()
        if (msg.contains("quota")) return ScreenScraperLookupFailure.DAILY_LIMIT
        return when (matchedSignInError(msg)) {
            SignInOutcome.DeveloperRejected -> ScreenScraperLookupFailure.BUILD_REFUSED
            SignInOutcome.Busy -> ScreenScraperLookupFailure.BUSY
            SignInOutcome.MemberRejected -> ScreenScraperLookupFailure.SIGN_IN_REFUSED
            else -> ScreenScraperLookupFailure.NO_ANSWER
        }
    }

    /** True when [body] is ScreenScraper's DEVELOPER-credential rejection (vs a member or busy error). */
    fun isDeveloperError(body: String): Boolean = classifySignInError(body) == SignInOutcome.DeveloperRejected

    private fun response(body: String): JsonObject? = runCatching {
        json.parseToJsonElement(body).jsonObject["response"]?.jsonObject
    }.getOrNull()

    /** Identifiers that could plausibly be numeric. Everything else non-numeric is dropped by parsing. */
    private val QuotaLogDenyKeys = setOf("id", "login", "ssid", "userid", "numid", "idclient")

    /** Substring guard, so a field added upstream later cannot leak by having a name we never listed. */
    private val QuotaLogDenyFragments = listOf("pass", "key", "token", "mail", "secret", "login", "url")

    private fun intOf(o: JsonObject, key: String): Int? =
        o[key]?.jsonPrimitive?.content?.trim()?.toIntOrNull()

    private fun strOf(o: JsonObject, key: String): String? =
        o[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun canonicalNameKey(value: String): String =
        cleanRomName(value)
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    /** A provider response is already body-bounded; this separately bounds one durable UI field. */
    private const val MAX_SYNOPSIS_CHARS = 4_000
}

/** The app's software registration (devid/devpassword/softname). Never logged. */
data class DevAuth(val devId: String, val devPassword: String, val softName: String) {
    override fun toString(): String =
        "DevAuth(devId=***, devPassword=***, softName=$softName)"
}

/** An optional per-user member sign-in that raises the quota tier. Never logged, never persisted in clear. */
data class UserAuth(val id: String, val password: String) {
    /** Prevent accidental diagnostic interpolation from disclosing a member password. */
    override fun toString(): String = "UserAuth(id=$id, password=***)"
}

/** A user's live ScreenScraper caps — read from `ssuserInfos`, never assumed. Drives self-throttling. */
data class UserQuota(val maxThreads: Int, val maxRequestsPerDay: Int, val requestsToday: Int)

/**
 * The classified result of a sign-in attempt. Lets the connect UI say WHICH layer failed instead of
 * blaming the user's password for a build-level (developer-key) or server-side (busy) problem.
 */
/** Why a ScreenScraper lookup did not load a record, as far as the response says ([ScreenScraperApi.classifyLookupError]). */
enum class ScreenScraperLookupFailure {
    /** No answer worth the name: the request timed out or dropped, or the error named no layer. */
    NO_ANSWER,

    /** The account's quota for the day is spent. */
    DAILY_LIMIT,

    /** The shared allowance is closed right now (peak load, or the free tier shut). */
    BUSY,

    /** The member sign-in was refused: the player can reconnect. */
    SIGN_IN_REFUSED,

    /** The BUILD's developer key was refused: nothing the player can fix. */
    BUILD_REFUSED,
}

sealed interface SignInOutcome {
    /** Signed in; [quota] is the account's live caps. Only this opens the gate. */
    data class Success(val quota: UserQuota) : SignInOutcome
    /** The member ssid/sspassword was rejected — the user can retry with the right sign-in. */
    data object MemberRejected : SignInOutcome
    /** ScreenScraper rejected the BUILD's developer key (missing/unapproved). The user can't fix this. */
    data object DeveloperRejected : SignInOutcome
    /** The shared/free allowance is closed right now (peak load / non-contributor). */
    data object Busy : SignInOutcome
    /** No developer registration is compiled into this build → the feature is inert. */
    data object NotConfigured : SignInOutcome
    /** ScreenScraper couldn't be reached (offline/timeout). */
    data object Unreachable : SignInOutcome
}
