package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ContentHash
import com.gruning.mimir.rewrite.core.metadata.MetadataOrigin
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.metadata.MetadataRecord
import com.gruning.mimir.rewrite.core.metadata.MetadataSource
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The opt-in ONLINE metadata source (ScreenScraper.fr) — the app's ONE live network egress, and only when
 * every guard passes. Implements [MetadataSource] so it composes cache-first behind the offline sources.
 *
 * GUARDS (all must hold or `lookup` returns null WITHOUT touching the network):
 *  1. [gateOpen] — the runtime kill-switch ([com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate]);
 *     false ⇒ no request is ever built.
 *  2. Software registration present ([ScreenScraperDevRegistration.isConfigured]).
 *  3. The platform maps to a ScreenScraper system id.
 * Any failure/timeout/parse miss ⇒ null ⇒ the game keeps its honest silhouette. This MUST NOT throw.
 *
 * Never logs credentials or request URLs (they carry creds). [httpGet] is injectable so URL-building and
 * parsing are unit-tested without a socket; the default performs a host-checked HTTPS GET.
 */
class ScreenScraperMetadataSource(
    private val gateOpen: () -> Boolean,
    private val devAuth: () -> DevAuth? = ::defaultDevAuth,
    private val userAuth: () -> UserAuth? = { null },
    private val httpGet: suspend (String) -> String? = ::defaultHttpGet,
    // Release-alpha ruling: presentation metadata is English-only for now. Device locale must not make
    // two otherwise-identical installs cache different copy or localized provider titles.
    private val preferredSynopsisLanguages: () -> List<String> = { listOf("en") },
    private val preferredNameRegions: () -> List<String> = { listOf("us", "wor", "eu") },
) : MetadataSource {

    sealed interface LookupOutcome {
        data class Loaded(val record: MetadataRecord) : LookupOutcome
        /** The provider answered and has no match for this game. Real evidence about the game. */
        data object Empty : LookupOutcome
        /**
         * We never asked: this platform has no ScreenScraper system id, so no request was possible.
         *
         * Kept distinct from [Empty] because a caller that caches negatives must not treat it as
         * evidence about the game. It says something about OUR map, and it stops being true the moment
         * the platform is mapped -- as xbox/xbox360/ps3 were on 2026-08-10, at which point every
         * already-recorded "no match" for those games was stale but still suppressing lookups.
         */
        data object PlatformUnsupported : LookupOutcome
        data object Unreachable : LookupOutcome

        /** The provider answered with an error that is not "not found"; [reason] is what the answer says ([ScreenScraperApi.classifyLookupError]). */
        data class Failed(val reason: ScreenScraperLookupFailure = ScreenScraperLookupFailure.NO_ANSWER) : LookupOutcome
        data object GateClosed : LookupOutcome
    }

    override suspend fun lookup(query: MetadataQuery): MetadataRecord? =
        (lookupOutcome(query) as? LookupOutcome.Loaded)?.record

    /** Explicit fetches need to distinguish absence from transport/auth/quota failure. */
    suspend fun lookupOutcome(query: MetadataQuery): LookupOutcome {
        if (!gateOpen() || devAuth() == null) return LookupOutcome.GateClosed
        if (ScreenScraperSystemMap.systemeIdFor(query.platformId) == null) {
            return LookupOutcome.PlatformUnsupported
        }
        var providerAnswered = false
        var providerFailure: ScreenScraperLookupFailure? = null
        val record = firstVerified(
            query,
            verifiedProviderIdIsTerminal = true,
            onProviderAnswered = { providerAnswered = true },
            // The first error body names the reason; a later rung's does not replace it.
            onProviderFailure = { errorBody -> if (providerFailure == null) providerFailure = ScreenScraperApi.classifyLookupError(errorBody) },
        ) { body, providerTitle, identity ->
            val medias = ScreenScraperApi.parseMedias(body)
            val boxArtUrl = ArtMediaResolver.pickBoxArtUrl(medias)
            val flatArtUrl = ArtMediaResolver.pickLogoUrl(medias)
            val heroArtUrl = ArtMediaResolver.pickHeroArtUrl(medias)
            val physicalMediaUrl = ArtMediaResolver.pickPhysicalMediaUrl(medias)
            // The game's clip. Since the launch sound  it is the only way the
            // fetch learns where the game's audio is, so a verified record that carries nothing BUT a clip
            // is worth keeping: it stops the ladder here instead of on a rung that may not have one.
            val clipUrl = ArtMediaResolver.pickSoundClipUrl(medias)
            val description = ScreenScraperApi.parseSynopsis(body, preferredSynopsisLanguages())
            // Verified but without presentation metadata — continue the bounded ladder. A synopsis-only
            // record is valuable even when no image exists, and will be cached by the explicit fetch path.
            if (boxArtUrl == null && flatArtUrl == null && heroArtUrl == null &&
                physicalMediaUrl == null && clipUrl == null && description == null
            ) {
                null
            } else {
                MetadataRecord(
                    canonicalTitle = providerTitle ?: query.candidateNames.firstOrNull()
                        ?: query.rawBaseName.orEmpty(),
                    origin = MetadataOrigin.SCREENSCRAPER,
                    // REMOTE urls at this stage — the fetch pipeline downloads them to local siblings; the
                    // render path only ever reads the local file, per the MetadataRecord contract.
                    boxArtUri = boxArtUrl,
                    flatArtUri = flatArtUrl,
                    heroArtUri = heroArtUrl,
                    physicalMediaUri = physicalMediaUrl,
                    description = description,
                    // A REMOTE url, like the four above: download input only. The app keeps just the clip's sound.
                    previewVideoUri = clipUrl,
                    providerGameId = identity.gameId, // persisted by the fetch layer (ART02 DB mapping)
                )
            }
        }
        return when {
            record != null -> LookupOutcome.Loaded(record)
            providerFailure != null -> LookupOutcome.Failed(providerFailure!!)
            providerAnswered -> LookupOutcome.Empty
            else -> LookupOutcome.Unreachable
        }
    }

    /**
     * Custom Hero: the bounded media list for ONE explicit user action.
     *
     * Deliberately routed through this class rather than a second client in `app`: it inherits the exact
     * same guards as [lookup] — the connected-art gate, the software registration, the ScreenScraper
     * system mapping, strict cross-system rejection, the bounded attempt ladder, and the
     * HTTPS/host-checked egress. `app` never builds a credentialed URL; it receives the verified media URL
     * only as a download input and must never render, persist, or log it.
     *
     * Caller-side gating still applies on top: this is only ever invoked from Edit → Custom Hero, and the
     * caller filters video candidates behind the separate preview-video opt-in.
     *
     * Returns null when no rung verified, or when the verified game carried no media at all.
     */
    suspend fun heroMedia(query: MetadataQuery): HeroMediaResult? =
        (heroMediaOutcome(query) as? HeroMediaOutcome.Loaded)?.result

    /**
     * Credential-bearing system-image download inputs for an explicit platform-art picker action.
     * No request is made here. The app downloads each input into a bounded local scratch file, rejects
     * NOMEDIA/non-images, and exposes only normalized local previews. World/US/EU/Japan is a fixed,
     * English-first regional ladder rather than device-locale behavior.
     */
    fun systemImageInputs(platformId: PlatformId): List<String> {
        if (!gateOpen()) return emptyList()
        val dev = devAuth() ?: return emptyList()
        val systemeId = ScreenScraperSystemMap.systemeIdFor(platformId) ?: return emptyList()
        return SYSTEM_ICON_MEDIA.map { media ->
            ScreenScraperApi.systemMediaUrl(dev, userAuth(), systemeId, media)
        }
    }

    /** What [pcLogoOutcome] learned. A failure is never a miss: only an answered, complete search with no logo is [NoMatch]. */
    sealed interface PcLogoOutcome {
        /** A logo's credentialed download URL: a download input only, never logged, rendered or persisted. */
        data class Found(val url: String) : PcLogoOutcome
        /** ScreenScraper answered every PC system asked and had no matching logo. Real evidence about the game. */
        data object NoMatch : PcLogoOutcome
        /** A transport failure, an auth/quota/server rejection, or an unparseable answer on some system: nothing is known. */
        data object Failed : PcLogoOutcome
        /** No request was possible (connection closed or no registration). */
        data object Unavailable : PcLogoOutcome
    }

    /**
     * A PC game's clear logo (`wheel-hd`, then `wheel`), found by NAME on ScreenScraper's PC systems, or null.
     *
     *  SteamGridDB is asked
     * first by the caller; this is the fallback for a game it has no logo for. `pc_games` has no ScreenScraper system id in
     * [ScreenScraperSystemMap], and deliberately still has none: mapping it would pull every PC row into the ScreenScraper
     * cover/hero batch. So this one lane names its own systems ([PC_LOGO_SYSTEME_IDS]) and reads ONLY the logo, so nothing but a
     * logo can come out of it.
     *
     * Every guard of [lookup] holds, because it is the same ladder ([firstVerified]): the gate and the software registration
     * (closed or absent → no request is built), strict cross-system rejection against the system being asked, and a
     * name verified against the provider's own title. A PC game has no ROM hash, so [MetadataQuery.contentHash] and
     * [MetadataQuery.providerGameId] are ignored here: a SteamGridDB id must never ride into a ScreenScraper query (the
     * poisoning hazard the app's PC route documents).
     *
     * The returned URL carries credentials: a download input only, never logged, rendered or persisted. This function logs
     * nothing at all.
     *
     * Told apart from a failure: a verified miss ([PcLogoOutcome.NoMatch], every PC system answered and none had a matching logo) is
     * never confused with one ([PcLogoOutcome.Failed], any system unreachable or answering with an error), so a caller never reports
     * "no art" for a lookup that did not get an answer.
     */
    suspend fun pcLogoOutcome(query: MetadataQuery): PcLogoOutcome {
        if (!gateOpen() || devAuth() == null) return PcLogoOutcome.Unavailable
        val nameOnly = query.copy(contentHash = null, providerGameId = null)
        var incomplete = false
        for (systemeId in PC_LOGO_SYSTEME_IDS) {
            var answered = false
            var failed = false
            val url = firstVerified(
                nameOnly,
                systemeIdOverride = systemeId,
                titleMatches = { requested, provider -> pcLogoTitleMatches(requested, provider) },
                onProviderAnswered = { answered = true },
                onProviderFailure = { failed = true },
            ) { body, _, _ ->
                // The id alone is not trusted: the response must NAME a PC system too (a wrong id could be another real system).
                if (!isPcSystemName(ScreenScraperApi.parseSystemName(body))) return@firstVerified null
                ArtMediaResolver.pickLogoUrl(
                    ScreenScraperApi.parseMedias(body).filter { screenScraperMediaUrlAllowed(it.url) },
                )
            }
            if (url != null) return PcLogoOutcome.Found(url)
            if (failed || !answered) incomplete = true
        }
        return if (incomplete) PcLogoOutcome.Failed else PcLogoOutcome.NoMatch
    }

    /** [pcLogoOutcome]'s URL, or null for every outcome that is not a logo. */
    suspend fun pcLogoUrl(query: MetadataQuery): String? = (pcLogoOutcome(query) as? PcLogoOutcome.Found)?.url

    /** Distinguishes a verified media miss from a request that never received a provider response. */
    suspend fun heroMediaOutcome(query: MetadataQuery): HeroMediaOutcome =
        presentationMediaOutcome(
            query = query,
            acceptedTypes = STATIC_HERO_MEDIA_TYPES,
            cap = MAX_HERO_MEDIA,
            deprioritizedTypes = BOX_HERO_MEDIA_TYPES,
        )

    /**
     * Verified Hero Motion inputs for an explicit picker search. Video types are filtered before
     * the bound so regioned screenshots/fanart cannot consume the generic Hero window and turn a
     * provider response containing motion into a false empty result.
     */
    suspend fun videoMediaOutcome(query: MetadataQuery): HeroMediaOutcome =
        presentationMediaOutcome(
            query = query,
            acceptedTypes = VIDEO_MEDIA_TYPES,
            cap = MAX_VIDEO_MEDIA,
            companionTypes = HERO_MOTION_POSTER_TYPES,
        )

    /**
     * Verified physical cartridge/disc/card art for an explicit presentation picker. Filtering happens
     * before the cap: a media-rich response cannot crowd `support-*` entries out of the bounded result.
     * [PHYSICAL_MEDIA_TYPES] also admits `box-texture` as a fallback (see that constant) for titles the provider has no `support-*` scan for at all. This does not write or
     * reinterpret Library metadata.
     */
    /**
     * Verified COVER candidates for the explicit Cover picker.
     *
     * The whole box family, because in a picker a candidate is legitimate when it is labelled for what it
     * is and the player chose it — so the honest default is broad and labelled, not narrow and silent. The
     * one deliberate exclusion is `box-2D-side`: a spine is a long thin strip and cannot fill a portrait
     * cover lane without being stretched into nonsense. Excluded on GEOMETRY, and recorded here so it does
     * not later read as an oversight.
     *
     * No region filtering happens here. The automatic ladder leads with `wor`, but `box-3D` publishes no
     * `wor` at all (verified live 2026-08-25 — Metroid Prime's six are de/eu/fr/jp/uk/us), and applying
     * that ladder silently would hide most of the choice the picker exists to offer.
     */
    suspend fun coverArtOutcome(query: MetadataQuery): HeroMediaOutcome =
        presentationMediaOutcome(
            query = query,
            acceptedTypes = COVER_MEDIA_TYPES,
            cap = MAX_COVER_MEDIA,
        )

    suspend fun physicalMediaOutcome(query: MetadataQuery): HeroMediaOutcome =
        presentationMediaOutcome(
            query = query,
            acceptedTypes = PHYSICAL_MEDIA_TYPES,
            cap = MAX_PHYSICAL_MEDIA,
        )

    /**
     * [deprioritizedTypes] are accepted but sorted behind every other accepted type BEFORE [cap] is
     * applied. Empty for every caller that has one flat class of media, which is why the video and
     * physical outcomes are byte-identical to before it existed. The hero outcome needs it: since
     * 2026-08-23 its accepted set mixes real backdrops with a box-art fallback, and a provider record
     * heavy in box scans would otherwise spend the whole bounded window on them and starve the fanart
     * the picker actually wants to lead with. The sort is stable, so provider order still decides within
     * each class.
     */
    private suspend fun presentationMediaOutcome(
        query: MetadataQuery,
        acceptedTypes: Set<String>?,
        cap: Int,
        companionTypes: Set<String> = emptySet(),
        deprioritizedTypes: Set<String> = emptySet(),
    ): HeroMediaOutcome {
        if (!gateOpen() || devAuth() == null) return HeroMediaOutcome.GateClosed
        // A missing ScreenScraper platform mapping is a local capability fact, not a
        // transport failure. Keep it out of UNREACHABLE so Settings cannot suggest
        // retrying the network for a system Mimir does not map.
        if (ScreenScraperSystemMap.systemeIdFor(query.platformId) == null) {
            return HeroMediaOutcome.Empty
        }
        var providerAnswered = false
        var providerFailed = false
        val result = firstVerified(
            query,
            verifiedProviderIdIsTerminal = true,
            // A verified game that carries no cartridge art, or no video, still exists — the picker's
            // seeded-title rungs are the only way a second provider record for the same title can be
            // reached, and without this they were unreachable for every default (unedited) search.
            namesFallBackOnVerifiedIdMiss = true,
            onProviderAnswered = { providerAnswered = true },
            onProviderFailure = { providerFailed = true },
        ) { body, providerTitle, identity ->
            val providerMedia = ScreenScraperApi.parseMedias(body)
                .filter { screenScraperMediaUrlAllowed(it.url) }
            val primaryMedia = providerMedia
                .filter { acceptedTypes == null || it.type.lowercase() in acceptedTypes }
                .sortedBy { if (it.type.lowercase() in deprioritizedTypes) 1 else 0 }
                .take(cap)
            val medias = primaryMedia
                .plus(
                    if (primaryMedia.isEmpty()) {
                        emptyList()
                    } else {
                        providerMedia.firstOrNull {
                            it.type.lowercase() in companionTypes
                        }?.let { listOf(it) }.orEmpty()
                    },
                )
                .distinctBy { it.url }
            if (medias.isEmpty()) {
                null
            } else {
                HeroMediaResult(
                    providerGameId = identity.gameId,
                    medias = medias.map { it.type to it.url },
                    canonicalTitle = providerTitle,
                )
            }
        }
        return when {
            result != null -> HeroMediaOutcome.Loaded(result)
            providerFailed -> HeroMediaOutcome.Failed
            providerAnswered -> HeroMediaOutcome.Empty
            else -> HeroMediaOutcome.Unreachable
        }
    }

    /**
     * A verified game's media, as (type, url) pairs in provider order. The caller decides which types
     * make a usable hero — this seam stays generic rather than encoding one feature's taste.
     *
     * The urls are credential-bearing (ScreenScraper signs media links with dev/member params), so they
     * are download inputs only: never rendered, never logged, never persisted.
     */
    data class HeroMediaResult(
        val providerGameId: String?,
        val medias: List<Pair<String, String>>,
        val canonicalTitle: String? = null,
    )

    sealed interface HeroMediaOutcome {
        data class Loaded(val result: HeroMediaResult) : HeroMediaOutcome
        data object Empty : HeroMediaOutcome
        data object GateClosed : HeroMediaOutcome
        data object Unreachable : HeroMediaOutcome
        data object Failed : HeroMediaOutcome
    }

    /**
     * The shared ART02 ladder: walk the bounded attempt list, reject any cross-system match outright, and
     * return the first attempt for which [transform] yields a non-null result.
     *
     * [transform] returning null means "verified, but not what I need" and CONTINUES the ladder — that is
     * what preserves `lookup`'s original "verified but artless → try next rung" behaviour now that two
     * callers share this walk.
     */
    private suspend fun <T> firstVerified(
        query: MetadataQuery,
        verifiedProviderIdIsTerminal: Boolean = false,
        /**
         * Only meaningful with [verifiedProviderIdIsTerminal]. A verified identity that simply carries
         * none of the media this caller asked for is not a wrong identity, so presentation searches walk
         * on to the bounded name rungs. Transport failure, a provider error, an unparseable body, and a
         * cross-system answer stay terminal on that rung either way — those mean the stored identity is
         * unusable, and filename-guessing past them is the ART02 wrong-art class.
         */
        namesFallBackOnVerifiedIdMiss: Boolean = false,
        /** A system to ask about instead of the platform's mapped one: the PC logo lane, whose platform has no mapping (R-24). */
        systemeIdOverride: Int? = null,
        /** How a name-only answer is judged against the name asked: the shared matcher unless a caller needs a stricter one. */
        titleMatches: (requested: String, providerTitle: String) -> Boolean = ScreenScraperApi::canonicalTitleMatches,
        onProviderAnswered: () -> Unit = {},
        /** Told the provider's error body when it answered with an error that is not "not found". */
        onProviderFailure: (errorBody: String) -> Unit = {},
        transform: (body: String, matchedName: String?, identity: ScreenScraperApi.GameIdentity) -> T?,
    ): T? {
        if (!gateOpen()) return null
        val dev = devAuth() ?: return null
        val systemeId = systemeIdOverride ?: ScreenScraperSystemMap.systemeIdFor(query.platformId) ?: return null

        // Cross-system wrong-art prevention (gb→gba): jeuInfos' name search fuzzy-matches
        // ACROSS systems, and the old path accepted whatever came back. Every attempt's response is now
        // STRICTLY verified against the requested system (mismatch or unparseable systeme → reject and
        // try the next rung — "never a wrong cover", the module contract), and the single raw name is
        // replaced by a candidate ladder: verified provider id → hash + best name → remaining candidates
        // name-only. Capped so quota spend per game stays bounded.
        val names = (query.candidateNames + listOfNotNull(query.rawBaseName))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        data class Attempt(val providerGameId: String?, val hash: ContentHash?, val name: String?)
        val verifiedProviderId = query.providerGameId?.takeIf { it.isNotBlank() }
        val nameAttempts = buildList {
            add(Attempt(null, query.contentHash, names.firstOrNull()))
            names.drop(1).take(MAX_NAME_CANDIDATES).forEach { add(Attempt(null, null, it)) }
        }
        val attempts = when {
            verifiedProviderId == null -> nameAttempts
            // Custom Hero must never filename-guess over an identity already verified by the art lane.
            // The exact call comes first and, for a lookup, is the only call. A presentation search may
            // walk on when that identity carries none of the media KIND it asked for — see
            // [namesFallBackOnVerifiedIdMiss]; every other miss on this rung is still terminal below.
            !verifiedProviderIdIsTerminal || namesFallBackOnVerifiedIdMiss ->
                listOf(Attempt(verifiedProviderId, null, null)) + nameAttempts
            else -> listOf(Attempt(verifiedProviderId, null, null))
        }

        for (attempt in attempts) {
            val exactIdRungIsTerminal = attempt.providerGameId != null && verifiedProviderIdIsTerminal
            val url = ScreenScraperApi.gameInfoUrl(
                dev,
                userAuth(),
                systemeId,
                attempt.hash,
                attempt.name,
                attempt.providerGameId,
            )
            val body = getWithTransientRetry(url)
            if (body == null) {
                if (exactIdRungIsTerminal) return null
                continue
            }
            onProviderAnswered()
            val providerError = ScreenScraperApi.parseErrorMessage(body)
            if (providerError != null) {
                if (!ScreenScraperApi.isLookupNotFound(body)) onProviderFailure(body)
                if (exactIdRungIsTerminal) return null
                continue
            }
            val identity = ScreenScraperApi.parseGameIdentity(body)
            // cross-system match — rejected, never accepted
            if (identity == null || identity.systemeId != systemeId) {
                if (exactIdRungIsTerminal) return null
                continue
            }
            val providerTitle = ScreenScraperApi.parseCanonicalTitle(body, preferredNameRegions())
            val providerIdentityTitle = providerTitle
                ?: ScreenScraperApi.parseProviderIdentityTitle(body, preferredNameRegions())
            val hasExactProviderId = attempt.providerGameId != null
            val hasHashEvidence = attempt.hash?.let { hash ->
                !hash.crc32.isNullOrBlank() || !hash.md5.isNullOrBlank() || !hash.sha1.isNullOrBlank()
            } == true
            val nameVerified = attempt.name != null && providerIdentityTitle != null &&
                titleMatches(attempt.name, providerIdentityTitle)
            if (!hasExactProviderId && !hasHashEvidence && !nameVerified) continue
            transform(body, providerTitle ?: attempt.name, identity)?.let { return it }
        }
        return null
    }

    /**
     * Validate a member sign-in and read live caps — the connect flow's first (and legitimate) gate open.
     * Classifies the outcome so the UI can be honest about WHICH layer failed (developer key vs member
     * sign-in vs server busy vs offline). Never logs the credentials.
     */
    suspend fun signIn(user: UserAuth?): SignInOutcome {
        val dev = devAuth() ?: return SignInOutcome.NotConfigured
        // STEP 1 — confirm the BUILD's developer registration on a dev-only endpoint. ssuserInfos is the
        // MEMBER endpoint: with a bad developer key it answers "Vérifier les identifiants utilisateurs"
        // (blaming the member) even when the real fault is the dev key. ssinfraInfos names the developer
        // error plainly, so we can be honest that this is a build problem, not the user's account.
        val infra = getWithTransientRetry(ScreenScraperApi.infraInfoUrl(dev)) ?: return SignInOutcome.Unreachable
        if (ScreenScraperApi.isDeveloperError(infra)) return SignInOutcome.DeveloperRejected
        // STEP 2 — developer key accepted; now the member sign-in result is trustworthy.
        val body = getWithTransientRetry(ScreenScraperApi.userInfoUrl(dev, user)) ?: return SignInOutcome.Unreachable
        ScreenScraperApi.parseUserQuota(body)?.let { return SignInOutcome.Success(it) }
        return ScreenScraperApi.classifySignInError(body)
    }

    /** Quota-only convenience: the parsed caps on success, else null. */
    suspend fun validate(user: UserAuth?): UserQuota? =
        (signIn(user) as? SignInOutcome.Success)?.quota

    /**
     * retry a TRANSPORT failure only (timeout, IO, refused URL — a `null` body) at the
     * source, with a short backoff. A body is the provider's own answer — not-found, auth, "fermé/
     * surcharge", quota — and is never re-sent, so this never spends extra quota on a request the
     * provider already answered. [CancellationException] propagates through [delay] untouched.
     */
    private suspend fun getWithTransientRetry(url: String): String? {
        repeat(TRANSIENT_RETRIES) { attempt ->
            httpGet(url)?.let { return it }
            delay(TRANSIENT_BACKOFF_MS[attempt])
        }
        return httpGet(url)
    }

    companion object {
        /** Extra attempts [getWithTransientRetry] makes beyond the first, each preceded by a backoff. */
        private const val TRANSIENT_RETRIES = 2

        /** Backoff before each retry in [getWithTransientRetry], indexed by attempt number. */
        private val TRANSIENT_BACKOFF_MS = longArrayOf(500L, 1_500L)
        /**
         * Which platforms this build can scrape at all, as a comparable fingerprint.
         *
         * Exposed here rather than widening [ScreenScraperSystemMap], which stays internal: the only
         * thing outside this module needs is "did coverage change", so a negative cache can drop
         * entries recorded under coverage that no longer holds.
         */
        fun coverageEpoch(): String = ScreenScraperSystemMap.coverageEpoch()

        /**
         * The ScreenScraper systems [pcLogoUrl] asks, in order: PC Windows, then PC DOS (GOG and Amazon sell many DOS-era titles).
         *
         * UNVERIFIED against a live `systemesListe.php` (no authenticated call has verified these IDs): 138 and 135
         * are ScreenScraper's PC Windows and PC DOS in this mapping. A wrong id costs only a request that comes
         * back empty or cross-system and is rejected: never a wrong logo, because the response's own system must equal the one
         * asked. Confirm on a connected account before claiming PC logos work; this list is the one place to correct.
         */
        internal val PC_LOGO_SYSTEME_IDS: List<Int> = listOf(138, 135)

        /**
         * A system NAME the PC logo lane accepts: Windows or DOS, any case, as a word. ScreenScraper's own labels read like "PC Windows" and
         * "PC Dos". An id that answers with any other name (or none) is rejected, so a wrong id can never file another system's wheel.
         */
        internal fun isPcSystemName(name: String?): Boolean =
            name != null && Regex("""\b(windows|dos)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)

        /**
         * The PC logo lane's title check, stricter than the shared matcher and used by nothing else. The shared one drops every
         * parenthesised qualifier, so `Doom (1993)` and `Doom (2016)` are one title to it. Here a year in either title must be in both
         * and the same, and everything else must be the same tokens exactly, with the parenthesised words kept, so `Doom (Classic)` is
         * not `Doom (Eternal)` either.
         */
        internal fun pcLogoTitleMatches(requested: String, providerTitle: String): Boolean {
            val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            if (year.findAll(requested).map { it.value }.toSet() != year.findAll(providerTitle).map { it.value }.toSet()) return false
            val left = ProviderTitleMatcher.strictTokens(requested)
            return left.isNotEmpty() && left == ProviderTitleMatcher.strictTokens(providerTitle)
        }

        /** ART02 quota cap: name-only rungs AFTER the hash+primary attempt (≤3 calls per game total). */
        private const val MAX_NAME_CANDIDATES = 2

        /** Bound on the media list returned for one explicit Custom Hero action. */
        private const val MAX_HERO_MEDIA = 40

        /**
         * Box art comes back in regional FAMILIES — six `box-3D` variants for one GameCube title is
         * ordinary — so this bound has to hold a family, not a handful.
         */
        private const val MAX_COVER_MEDIA = 40

        /**
         * The FACE vocabulary: the box family (minus the spine) AND the physical article.
         *
         * The picker replaced the "Cover art" and "Cartridge Hero" rows with
         * two STATE rows, this is the only picker that reaches either vocabulary — so it must offer both,
         * or the menu would silently remove the ability to choose cartridge art at all. A face is a face:
         * which picture appears on a shelf is the player's choice, not a category the menu selects.
         *
         * `box-2D-side` remains the one exclusion, on geometry: a spine is a long thin strip and cannot
         * fill a portrait lane without being stretched into nonsense. See [coverArtOutcome].
         */
        private val COVER_MEDIA_TYPES = setOf(
            "box-2d",
            "box-3d",
            "box-texture",
            "box-2d-back",
            "box-scan",
            "support-2d",
            "support-3d",
            "support-label",
            "support-texture",
        )

        /**
         * ScreenScraper still types that make a usable static Hero backdrop.
         *
         * The three real backdrop types, plus the box family as EXPLICIT-PICKER-ONLY candidates.
         *
         * Box families are offered explicitly in the picker, where the player chooses them. This accepted-types filter
         * gates what even reaches the Hero picker's fetch, so the box types must be admitted here or the
         * picker can never offer them -- the same lesson [PHYSICAL_MEDIA_TYPES] records for physical media.
         *
         * The dup-cover ban is INTACT where it matters, and this is the seam: it bans a cover being put
         * behind a cover *silently*. `ArtMediaResolver.HERO_TYPE_PRIORITY`, which is what AUTOMATIC
         * enrichment ranks, is deliberately untouched and still carries only fanart/sstitle/ss. So Mimir
         * never installs box art as a backdrop on its own; the player sees a labelled candidate in
         * Customize Media and chooses it. [HERO_MOTION_POSTER_TYPES] is likewise untouched -- a box poster
         * standing behind a clip would be the silent case again.
         */
        /** The three purpose-built backdrop types. These alone may be ranked ahead of the cap. */
        private val REAL_HERO_MEDIA_TYPES = setOf("fanart", "sstitle", "ss")

        /**
         * Box art: accepted for the picker, DEMOTED before the cap.
         *
         * Kept as its own set because it has to be named twice -- once to admit it and once to hold it
         * behind every real backdrop in [presentationMediaOutcome]. Admitting it without the demotion is
         * a real defect, not a style point: a provider record carrying 45 `box-2D` scans and one `fanart`
         * filled the whole bounded window with box art and the one genuine backdrop never reached the
         * picker. That is the exact hazard `hero media filters before cap so fanart survives forty non
         * hero entries` was written for, and widening the accepted set turned it red.
         */
        private val BOX_HERO_MEDIA_TYPES = setOf("box-texture", "box-3d", "box-2d")

        private val STATIC_HERO_MEDIA_TYPES = REAL_HERO_MEDIA_TYPES + BOX_HERO_MEDIA_TYPES

        /** ScreenScraper currently exposes original and normalized video variants per verified game. */
        private const val MAX_VIDEO_MEDIA = 4
        private val VIDEO_MEDIA_TYPES = setOf("video", "video-normalized")
        private val HERO_MOTION_POSTER_TYPES = setOf("fanart", "sstitle", "ss")

        /** Physical support art is presentation-only and intentionally small. */
        private const val MAX_PHYSICAL_MEDIA = 8
        private val PHYSICAL_MEDIA_TYPES = setOf(
            "support-2d",
            "support-3d",
            "support-label",
            "support-texture",
            // Fallback,  Ground truth: ScreenScraper returned 21 media types and zero
            // support-* entries for Resident Evil 5 (Xbox 360) in Customize Media, but did carry
            // box-texture — this is the accepted-types filter that gates what even reaches the explicit
            // physical-media picker's fetch, so box-texture must be admitted here or it never gets this
            // far regardless of downstream ranking.
            //
            // SCOPE, this PICKER is now the only place the 08-12 fallback lives.
            // 5b9ef36d also admitted box-texture to ArtMediaResolver.PHYSICAL_MEDIA_TYPE_PRIORITY, which
            // ranks the AUTOMATIC enrichment slot; that silently installed a flattened Xbox 360 box wrapper
            // as Resident Evil 5's disc art and the regression report described it as missing disc art. It was removed
            // there on 08-22 and must stay removed: in this picker the player
            // SEES the candidate (app/CustomHeroStore.kt labels it "Box texture (fallback)") and CHOOSES
            // it, whereas enrichment files it as a disc scan with no label and no choice. Keep the honest
            // label if this list ever grows; an unlabelled box-texture candidate is the defect returning.
            "box-texture",
        )

        /** Bounded platform logo ladder documented by ScreenScraper's mediaSysteme endpoint. */
        private val SYSTEM_ICON_MEDIA = listOf("wheel(wor)", "wheel(us)", "wheel(eu)", "wheel(jp)")

        /** Provider JSON/error bodies are bounded; a hostile response cannot exhaust app memory. */
        private const val MAX_HTTP_BODY_BYTES = 4L * 1024L * 1024L
        private const val MAX_REDIRECTS = 3
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        /** The build's software registration, or null when this checkout has no creds (→ feature inert). */
        private fun defaultDevAuth(): DevAuth? =
            if (ScreenScraperDevRegistration.isConfigured) {
                DevAuth(
                    devId = ScreenScraperDevRegistration.devId,
                    devPassword = ScreenScraperDevRegistration.devPassword,
                    softName = ScreenScraperDevRegistration.softName,
                )
            } else {
                null
            }

        /** Hosts the client is EVER allowed to reach — defense-in-depth behind the gate's own allowlist. */
        private val ALLOWED_HOST_SUFFIX = "screenscraper.fr"

        private fun screenScraperMediaUrlAllowed(url: String): Boolean {
            val parsed = runCatching { URI(url) }.getOrNull() ?: return false
            if (!parsed.scheme.equals("https", ignoreCase = true) ||
                parsed.rawUserInfo != null ||
                parsed.port !in setOf(-1, 443) ||
                parsed.rawFragment != null
            ) {
                return false
            }
            val host = parsed.host?.lowercase() ?: return false
            return host == ALLOWED_HOST_SUFFIX || host.endsWith(".$ALLOWED_HOST_SUFFIX")
        }

        /**
         * The default egress: a host-checked, HTTPS-only, timeout-bounded GET. Returns the body on 200,
         * else null. Never logs the URL (it carries creds). Runs on IO.
         *
         * Cancelling the caller closes the live connection ([runAbortingOnCancel]), so a read parked on a dead network ends
         * at once and the cancellation is what the caller sees, not a null it would count as the provider failing.
         */
        private suspend fun defaultHttpGet(url: String): String? = withContext(Dispatchers.IO) {
            val abort = ConnectionAbort()
            runAbortingOnCancel(abort = abort::abort) { blockingHttpGet(url, abort) }
        }

        private fun blockingHttpGet(url: String, abort: ConnectionAbort): String? {
            try {
                var current = url
                var redirects = 0
                while (redirects <= MAX_REDIRECTS) {
                    if (!screenScraperApiUrlAllowed(current)) return null
                    val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty("User-Agent", "MimirApk")
                    }
                    if (!abort.attach(connection)) return null
                    try {
                        val code = connection.responseCode
                        if (code in ScreenScraperRedirectStatusCodes) {
                            if (redirects == MAX_REDIRECTS) return null
                            current = screenScraperApiRedirectTarget(
                                currentUrl = current,
                                location = connection.getHeaderField("Location"),
                            ) ?: return null
                            redirects++
                            continue
                        }
                        if (!screenScraperContentLengthAllowed(
                                connection.contentLengthLong,
                                MAX_HTTP_BODY_BYTES,
                            )
                        ) {
                            return null
                        }
                        // Return the body for BOTH success and error responses: ScreenScraper answers auth
                        // failures with a non-200 whose body is the real French reason (dev key vs member
                        // sign-in vs busy). Dropping it here is what made every failure look identical. The
                        // sign-in classifier reads this; the lookup path just finds no media → silhouette.
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        return stream?.let {
                            readScreenScraperUtf8Bounded(it, MAX_HTTP_BODY_BYTES)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                return null
            } catch (_: Exception) {
                return null
            }
        }
    }
}

internal val ScreenScraperRedirectStatusCodes = setOf(301, 302, 303, 307, 308)

private val ScreenScraperExactApiPaths = setOf(
    "/api2/jeuInfos.php",
    "/api2/mediaSysteme.php",
    "/api2/ssinfraInfos.php",
    "/api2/ssuserInfos.php",
)

internal fun screenScraperApiUrlAllowed(url: String): Boolean {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    return parsed.scheme.equals("https", ignoreCase = true) &&
        parsed.host?.lowercase() == "www.screenscraper.fr" &&
        parsed.rawUserInfo == null &&
        parsed.port in setOf(-1, 443) &&
        parsed.rawFragment == null &&
        parsed.rawPath in ScreenScraperExactApiPaths
}

internal fun screenScraperApiRedirectTarget(currentUrl: String, location: String?): String? {
    val raw = location?.takeIf(String::isNotBlank) ?: return null
    val resolved = runCatching {
        val current = URI(currentUrl)
        if (raw.startsWith("?")) {
            currentUrl.substringBefore('?').substringBefore('#') + raw
        } else {
            current.resolve(raw).toString()
        }
    }.getOrNull() ?: return null
    return resolved.takeIf(::screenScraperApiUrlAllowed)
}

internal fun screenScraperContentLengthAllowed(reportedBytes: Long, maxBytes: Long): Boolean =
    maxBytes > 0L && (reportedBytes == -1L || reportedBytes in 0..maxBytes)

/** Bounded full-body reader. Oversized JSON is rejected rather than truncated into ambiguous data. */
internal fun readScreenScraperUtf8Bounded(input: InputStream, maxBytes: Long): String? {
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
