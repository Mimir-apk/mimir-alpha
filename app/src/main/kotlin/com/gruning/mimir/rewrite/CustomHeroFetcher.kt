package com.gruning.mimir.rewrite

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.Log
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal sealed interface CustomHeroProviderResult {
    data class Loaded(
        val candidates: List<CustomHeroCandidate>,
        val hasMore: Boolean = false,
    ) : CustomHeroProviderResult
    data object NeedsAccess : CustomHeroProviderResult
    data object Unreachable : CustomHeroProviderResult
    data object ProviderFailed : CustomHeroProviderResult
}

/** Transport/DNS/timeout/open-null failures — never credential or HTTP-body faults. */
internal class SteamGridDbUnreachableException : IOException()

/** HTTP, redirect, body, or JSON/provider failures after a route was opened. */
internal class SteamGridDbProviderException : IOException()

/** Live provider verdict for a user-submitted key. Only ACCEPTED may become durable authority. */
internal enum class SteamGridDbKeyValidationResult {
    ACCEPTED,
    REJECTED,
    UNREACHABLE,
    PROVIDER_UNAVAILABLE,
}

internal enum class CustomHeroStageOutcome {
    READY,
    DOWNLOAD_FAILED,
    UNSUPPORTED_MEDIA,
    BELOW_QUALITY_FLOOR,
}

/**
 * UXP-01 Custom Hero network layer .
 *
 * **Every entry point here is suspend and is only ever called from an explicit Edit → Custom Hero
 * action.** There is no ambient caller: navigation, scan, and launch never reach this file, which is how
 * the no-network-on-scan/launch invariant survives a feature that is inherently online.
 *
 * ScreenScraper is NOT spoken to directly from here. Its credentialed URL building, dev/member auth,
 * connected-art gate, system verification and host-checked egress all live in the one guarded seam
 * (`ScreenScraperMetadataSource.heroMedia`, reached via the already-wired `ArtworkConnectController.source`).
 * This file only ever downloads the resulting media URLs, each host-allowlisted to its own provider.
 */
internal object CustomHeroFetcher {

    private const val SgdbApi = "https://www.steamgriddb.com/api/v2"
    private const val ConnectTimeoutMs = 8_000
    private const val ReadTimeoutMs = 15_000
    private const val MaxPreviewBytes: Long = CustomHeroStore.MaxPreviewBytes
    private const val MaxJsonBytes: Long = 2L * 1024L * 1024L
    private const val MaxRedirects = 3

    private data class SteamGridDbGameMatch(val id: Long, val title: String)

    /**
     * Validate a candidate key before it is persisted or allowed to open the normal provider gate.
     * The exact probe URL is provider-owned, read-only, and returns 401 for an invalid bearer token.
     */
    suspend fun validateSteamGridDbKey(
        apiKey: String,
        opener: MimirProviderEgressOpener? = null,
    ): SteamGridDbKeyValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SteamGridDbKeyValidationResult.REJECTED
        val response = if (opener == null) {
            MimirProviderEgress.open(
                route = MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION,
                url = SteamGridDbKeyValidationUrl,
                connectTimeoutMs = ConnectTimeoutMs,
                readTimeoutMs = ReadTimeoutMs,
                requestHeaders = mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer ${apiKey.trim()}",
                ),
            )
        } else {
            MimirProviderEgress.open(
                route = MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION,
                url = SteamGridDbKeyValidationUrl,
                connectTimeoutMs = ConnectTimeoutMs,
                readTimeoutMs = ReadTimeoutMs,
                requestHeaders = mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer ${apiKey.trim()}",
                ),
                opener = opener,
            )
        } ?: return@withContext SteamGridDbKeyValidationResult.UNREACHABLE
        try {
            when (response.statusCode) {
                200 -> SteamGridDbKeyValidationResult.ACCEPTED
                401, 403 -> SteamGridDbKeyValidationResult.REJECTED
                else -> SteamGridDbKeyValidationResult.PROVIDER_UNAVAILABLE
            }
        } finally {
            response.close()
        }
    }

    /**
     * SteamGridDB heroes for [title], best community score first.
     *
     * Keeps the provider's own `thumb` beside the full-size `url`: the picker previews the thumb and only
     * the applied pick downloads the full asset.
     */
    suspend fun steamGridDbHeroes(apiKey: String, title: String): CustomHeroProviderResult =
        steamGridDbHeroes(apiKey, listOf(title))

    suspend fun steamGridDbHeroes(apiKey: String, titles: List<String>): CustomHeroProviderResult =
        steamGridDbHeroesInternal(apiKey, titles, manual = false, size = "Any")

    /**
     * SteamGridDB heroes for the game that owns Steam app id [steamAppId] — R-23
     * . Exact identity, not a title guess: hits SteamGridDB's
     * own `.../steam/{appid}` asset routes directly, so there is no fuzzy autocomplete step and no title
     * to match against at all. Use only when the id came from `GameNativeLaunchRoute.parse`'s validated
     * `.steam` payload — never a filename or user-typed string.
     *
     * [size] and [fit] default to `"Any"` so the tile-menu quick pick (`CustomHeroStore`, unfiltered)
     * keeps its exact current behaviour; the picker call site forwards its real Size/Fit so this route
     * pre-filters identically to the title route instead of relying solely on the caller's post-filter.
     */
    suspend fun steamGridDbHeroesForSteamAppId(
        apiKey: String,
        steamAppId: Int,
        size: String = "Any",
        fit: String = "Any",
    ): CustomHeroProviderResult =
        steamGridDbHeroesForGame(apiKey, SteamGridDbGameRef.SteamApp(steamAppId), size = size, fit = fit)

    /**
     * SteamGridDB heroes for ONE already-resolved [game] -- the exact `steam/<appid>` identity
     * ([steamGridDbHeroesForSteamAppId]) or the `game/<sgdbId>` entry a name search resolved
     * (`SteamGridDbTileFetcher.searchGameByTitle`). Either way there is no
     * autocomplete step in here: this hits SteamGridDB's own id-type-keyed hero route for [game]'s
     * segment, so the caller's one resolution decides which game every asset kind belongs to.
     */
    suspend fun steamGridDbHeroesForGame(
        apiKey: String,
        game: SteamGridDbGameRef,
        size: String = "Any",
        fit: String = "Any",
    ): CustomHeroProviderResult =
        steamGridDbHeroesInternal(apiKey, titles = emptyList(), manual = false, size = size, fit = fit, exactGame = game)

    /** Free-form presentation search: bounded title-ranked matches, with no canonical-title side effects. */
    suspend fun manualSteamGridDbHeroes(
        apiKey: String,
        query: String,
        size: String,
        fit: String = "Any",
        page: Int = 0,
        pageSize: Int = 12,
    ): CustomHeroProviderResult = steamGridDbHeroesInternal(
        apiKey,
        listOf(query),
        manual = true,
        size = size,
        fit = fit,
        page = page,
        pageSize = pageSize,
    )

    /** SteamGridDB motion is an animated Hero image; structural validation admits WebP or GIF bytes. */
    suspend fun manualSteamGridDbMotionArt(
        apiKey: String,
        query: String,
        fallbackQuery: String? = null,
        size: String,
        page: Int = 0,
        pageSize: Int = 12,
    ): CustomHeroProviderResult = steamGridDbHeroesInternal(
        apiKey = apiKey,
        titles = listOfNotNull(query, fallbackQuery)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() },
        manual = true,
        size = size,
        page = page,
        pageSize = pageSize,
        animated = true,
    )

    private suspend fun steamGridDbHeroesInternal(
        apiKey: String,
        titles: List<String>,
        manual: Boolean,
        size: String,
        fit: String = "Any",
        page: Int = 0,
        pageSize: Int = CustomHeroMaxCandidates,
        animated: Boolean = false,
        /**
         * An already-resolved game: R-23's exact Steam app id, or the SteamGridDB entry a name search
         * validated. When present, this replaces title search entirely (see below).
         */
        exactGame: SteamGridDbGameRef? = null,
    ): CustomHeroProviderResult =
        withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) return@withContext CustomHeroProviderResult.Loaded(emptyList())
                if (!MimirSteamGridDbGate.isOpen) return@withContext CustomHeroProviderResult.NeedsAccess
                // Each target is a SteamGridDB URL id-segment ("game/<sgdbId>" or "steam/<appid>") paired
                // with the resolvedTitle a manual search should surface. An already-resolved game needs
                // neither a title-search round trip nor a resolved title -- it is the exact identity R-23
                // asks for (straight from GameNativeLaunchRoute's validated `.steam` payload) or the one
                // entry the caller's own name search already validated.
                val lookupTargets: List<Pair<String, String?>> = if (exactGame != null) {
                    listOf(exactGame.urlSegment to null)
                } else {
                    val games = (if (manual) {
                        titles
                            .flatMap { title -> searchManualGamesOrThrow(apiKey, title, limit = 3) }
                            .distinctBy(SteamGridDbGameMatch::id)
                            .take(3)
                    } else {
                        listOfNotNull(searchFirstGameOrThrow(apiKey, titles))
                    })
                    if (games.isEmpty()) return@withContext CustomHeroProviderResult.Loaded(emptyList())
                    games.map { game -> "game/${game.id}" to game.title.takeIf { manual } }
                }
                data class Entry(
                    val id: String?,
                    val url: String,
                    val thumb: String?,
                    val score: Long,
                    val width: Int,
                    val height: Int,
                    val resolvedTitle: String?,
                )
                var hasMore = false
                val perGameEntries = lookupTargets.map { (urlSegment, resolvedTitle) ->
                    val body = getJsonOrThrow(
                        "$SgdbApi/heroes/$urlSegment?types=${if (animated) "animated" else "static"}" +
                            "&nsfw=false&humor=false&epilepsy=false" +
                            heroDimensionsQuery(size) + "&limit=$pageSize&page=$page",
                        apiKey,
                    )
                    val data = body.optJSONArray("data") ?: return@map emptyList<Entry>()
                    val meta = body.optJSONObject("meta")
                    val total = meta?.optInt("total", -1)?.takeIf { it >= 0 }
                    val responsePage = meta?.optInt("page", page)?.takeIf { it >= 0 } ?: page
                    val responseLimit = meta?.optInt("limit", pageSize)?.takeIf { it > 0 } ?: pageSize
                    hasMore = hasMore || steamGridDbPageHasMore(total, responsePage, responseLimit, data.length())
                    (0 until data.length()).mapNotNull { index ->
                        val item = data.getJSONObject(index)
                        val url = item.optString("url")
                        if (!customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, url)) return@mapNotNull null
                        val thumb = item.optString("thumb")
                            .takeIf { customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, it) }
                        Entry(
                            id = item.optLong("id").takeIf { it > 0L }?.toString(),
                            url = url,
                            thumb = thumb,
                            score = item.optLong("score"),
                            width = item.optInt("width"),
                            height = item.optInt("height"),
                            resolvedTitle = resolvedTitle,
                        )
                    }.sortedWith(
                        compareBy<Entry> { entry ->
                            customHeroTargetDistance(entry.width, entry.height)
                        }
                            .thenByDescending { it.score }
                            .thenByDescending { entry ->
                                if (entry.width > 0 && entry.height > 0) entry.width * entry.height else 0
                            },
                    ).filter { entry ->
                        when {
                            animated && entry.width > 0 && entry.height > 0 ->
                                validCustomHeroMotionArtDimensions(entry.width, entry.height)
                            animated -> true
                            entry.width > 0 && entry.height > 0 ->
                                validCustomHeroStaticApplyDimensions(entry.width, entry.height)
                            else -> true
                        }
                    }.filter { entry ->
                        !steamGridDbEntryKnownAndRejected(entry.width, entry.height, animated, size, fit)
                    }
                }
                val candidateGroups = perGameEntries.map { entries ->
                    entries.map { entry ->
                        CustomHeroCandidate(
                            source = CustomHeroSource.STEAMGRIDDB,
                            mediaType = if (animated) {
                                CustomHeroMediaType.ANIMATED_IMAGE
                            } else {
                                CustomHeroMediaType.STATIC
                            },
                            remoteUrl = entry.url,
                            thumbUrl = entry.thumb,
                            ordinal = 1,
                            resolvedTitle = entry.resolvedTitle,
                            providerAssetId = entry.id,
                            width = entry.width,
                            height = entry.height,
                            mediaSubtype = if (animated) "motion-art" else "hero",
                        )
                    }
                }
                val interleaved = roundRobinDistinctCustomHeroCandidates(
                    candidateGroups,
                    if (manual) pageSize else CustomHeroMaxCandidates,
                )
                val candidates = interleaved.mapIndexed { index, candidate ->
                    candidate.copy(ordinal = page * pageSize + index + 1)
                }
                CustomHeroProviderResult.Loaded(candidates, hasMore = manual && hasMore)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SteamGridDbUnreachableException) {
                CustomHeroProviderResult.Unreachable
            } catch (_: SteamGridDbProviderException) {
                CustomHeroProviderResult.ProviderFailed
            } catch (_: Exception) {
                CustomHeroProviderResult.ProviderFailed
            }
        }

    /**
     * ScreenScraper hero candidates for [game], through the guarded metadata seam.
     *
     * The query is built from the EXACT emulated row: its real [GameRow.platformId] (so the system
     * verification can reject a cross-system match) and the already-verified provider game id when one
     * exists. A verified id is sent alone and first — the ladder never filename-guesses over identity the
     * app has already proven.
     */
    suspend fun screenScraperHeroes(
        game: GameRow,
        videoAllowed: Boolean,
        candidateNames: List<String> = listOf(game.title),
        useVerifiedIdentity: Boolean = true,
        persistResolution: Boolean = true,
    ): CustomHeroProviderResult {
        val query = MetadataQuery(
            platformId = game.platformId,
            candidateNames = candidateNames.map(String::trim).filter(String::isNotBlank).distinct(),
            providerGameId = ArtResolutionStore.resolvedGameIdFor(game.gameId.value).takeIf { useVerifiedIdentity },
        )
        val outcome = try {
            if (videoAllowed) {
                ArtworkConnectController.source.videoMediaOutcome(query)
            } else {
                ArtworkConnectController.source.heroMediaOutcome(query)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return CustomHeroProviderResult.ProviderFailed
        }
        val result = when (outcome) {
            is com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.Loaded ->
                outcome.result
            com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.Empty ->
                return CustomHeroProviderResult.Loaded(emptyList())
            com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.GateClosed ->
                return CustomHeroProviderResult.NeedsAccess
            com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.Unreachable ->
                return CustomHeroProviderResult.Unreachable
            com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.Failed ->
                return CustomHeroProviderResult.ProviderFailed
        }
        // Persist the mapping the moment it verifies, exactly as the art lane does — a verified
        // resolution is durable truth regardless of whether the user applies a hero tonight.
        if (persistResolution) result.providerGameId?.let { ArtResolutionStore.record(game.gameId.value, it) }
        return CustomHeroProviderResult.Loaded(
            (if (videoAllowed) {
                screenScraperPreviewClipCandidates(result.medias)
            } else {
                screenScraperHeroCandidates(result.medias, videoAllowed = false)
            })
                .map { it.copy(resolvedTitle = result.canonicalTitle) }
                .filter { customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, it.remoteUrl) },
        )
    }

    /**
     * Verified ScreenScraper cartridge/disc/card art for explicit presentation search only. The
     * resolver identity and Library's distinct `-support` media slot are deliberately not written.
     */
    /**
     * Verified COVER candidates for the explicit picker (policy 2026-08-25, R5).
     *
     * Deliberately the same shape as [screenScraperPhysicalMedia] one function below — same query
     * construction, same identity handling, same failure mapping — because they are the same transaction
     * against a different accepted-types set. Diverging here would mean two ways to be wrong about
     * provider identity.
     */
    suspend fun screenScraperCoverArt(
        game: GameRow,
        candidateNames: List<String> = listOf(game.title),
        useVerifiedIdentity: Boolean = true,
        persistResolution: Boolean = false,
    ): CustomHeroProviderResult = screenScraperPickerMedia(
        game = game,
        candidateNames = candidateNames,
        useVerifiedIdentity = useVerifiedIdentity,
        persistResolution = persistResolution,
        outcomeOf = { ArtworkConnectController.source.coverArtOutcome(it) },
        candidatesOf = ::screenScraperCoverCandidates,
    )

    suspend fun screenScraperPhysicalMedia(
        game: GameRow,
        candidateNames: List<String> = listOf(game.title),
        useVerifiedIdentity: Boolean = true,
        persistResolution: Boolean = false,
    ): CustomHeroProviderResult = screenScraperPickerMedia(
        game = game,
        candidateNames = candidateNames,
        useVerifiedIdentity = useVerifiedIdentity,
        persistResolution = persistResolution,
        outcomeOf = { ArtworkConnectController.source.physicalMediaOutcome(it) },
        candidatesOf = ::screenScraperPhysicalMediaCandidates,
    )

    /**
     * One transaction shape for every explicit picker search: build the query, honour the verified
     * identity, map the outcome, optionally persist the resolution, rank the candidates.
     *
     * Extracted when the Cover picker arrived (2026-08-25) rather than copied. The part worth not
     * duplicating is the identity handling — `providerGameId` gated on [useVerifiedIdentity], and the
     * conditional [ArtResolutionStore] write — because two copies of that is two ways to be wrong about
     * which game the provider thinks it is answering about.
     */
    private suspend fun screenScraperPickerMedia(
        game: GameRow,
        candidateNames: List<String>,
        useVerifiedIdentity: Boolean,
        persistResolution: Boolean,
        outcomeOf: suspend (MetadataQuery) -> com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome,
        candidatesOf: (List<Pair<String, String>>) -> List<CustomHeroCandidate>,
    ): CustomHeroProviderResult {
        val query = MetadataQuery(
            platformId = game.platformId,
            candidateNames = candidateNames.map(String::trim).filter(String::isNotBlank).distinct(),
            providerGameId = ArtResolutionStore.resolvedGameIdFor(game.gameId.value).takeIf { useVerifiedIdentity },
        )
        val outcome = try {
            outcomeOf(query)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return CustomHeroProviderResult.ProviderFailed
        }
        val result = when (outcome) {
            is com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.Loaded ->
                outcome.result
            com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.Empty ->
                return CustomHeroProviderResult.Loaded(emptyList())
            com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.GateClosed ->
                return CustomHeroProviderResult.NeedsAccess
            com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.Unreachable ->
                return CustomHeroProviderResult.Unreachable
            com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource.HeroMediaOutcome.Failed ->
                return CustomHeroProviderResult.ProviderFailed
        }
        if (persistResolution) result.providerGameId?.let { ArtResolutionStore.record(game.gameId.value, it) }
        return CustomHeroProviderResult.Loaded(
            candidatesOf(result.medias).map { it.copy(resolvedTitle = result.canonicalTitle) },
        )
    }

    /**
     * Downloads [candidate] and installs it as [gameId]'s hero, atomically with rollback.
     *
     * The bytes are bounded on the way in, proved to decode as real media, and only then swapped into
     * place — see [installHeroAsset] for the ordering guarantees. Any failure leaves the previous hero,
     * chosen or automatic, exactly as it was.
     */
    suspend fun applyHero(
        context: android.content.Context,
        gameId: GameId,
        candidate: CustomHeroCandidate,
    ): Boolean = applyCandidate(context, gameId, candidate, expectedVideo = false) { currentGameId ->
        CustomHeroStore.commitHero(context, currentGameId)
    }

    /** Installs the independently chosen preview clip without replacing the chosen static hero. */
    suspend fun applyPreviewClip(
        context: android.content.Context,
        gameId: GameId,
        candidate: CustomHeroCandidate,
    ): Boolean = applyCandidate(context, gameId, candidate, expectedVideo = true) { currentGameId ->
        CustomHeroStore.commitPreviewClip(context, currentGameId)
    }

    private suspend fun applyCandidate(
        context: android.content.Context,
        gameId: GameId,
        candidate: CustomHeroCandidate,
        expectedVideo: Boolean,
        commit: (GameId) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val motion = candidate.mediaType != CustomHeroMediaType.STATIC
        if (motion != expectedVideo) return@withContext false
        val dir = CustomHeroStore.cacheDir(context).apply { mkdirs() }
        val temp = File(dir, "identity-${System.nanoTime().toULong().toString(16)}.stage")
        try {
            if (!stageCandidate(context, candidate, expectedVideo, temp)) return@withContext false
            val kind = if (motion) EnrichMediaKind.CLIP else EnrichMediaKind.HERO
            val outcome = GameIdentityMediaMutationGate.run(
                context,
                blocked = HeroInstallOutcome.PERSIST_FAILED,
            ) {
                val currentGameId = GameIdentityAliasResolver.resolve(context, gameId)
                val priorSelection = GameIdentityMediaSelectionStore.record(
                    context,
                    kind,
                    currentGameId.value,
                )
                if (!GameIdentityMediaSelectionStore.set(
                        context,
                        kind,
                        currentGameId.value,
                        GameIdentityMediaSelection.SELECTED,
                    )
                ) {
                    return@run HeroInstallOutcome.PERSIST_FAILED
                }
                installHeroAsset(
                    temp = temp,
                    target = CustomHeroStore.assetFile(context, currentGameId, motion),
                    validate = { file ->
                        isDecodableSavedMedia(file, candidate.mediaType) &&
                            meetsCustomHeroMediaApplyFloor(file, candidate.mediaType)
                    },
                    commit = { commit(currentGameId) },
                ).also { result ->
                    if (result != HeroInstallOutcome.INSTALLED) {
                        GameIdentityMediaSelectionStore.write(
                            context,
                            kind,
                            currentGameId.value,
                            priorSelection,
                        )
                    }
                }
            }
            if (outcome == HeroInstallOutcome.INSTALLED) CustomHeroStore.sweep(context)
            if (outcome != HeroInstallOutcome.INSTALLED) {
                Log.w(
                    "MimirCustomHero",
                    "hero install ${outcome.name}: ${customHeroSafeLogLabel(candidate)}",
                )
            }
            outcome == HeroInstallOutcome.INSTALLED
        } finally {
            // Cancellation may arrive during either network staging or durable-state mutation.
            // The candidate is scratch state until the atomic installer consumes it.
            temp.delete()
        }
    }

    /**
     * The best SteamGridDB logo for a game enrich already resolved ([steamGridDbBestLogo]'s order), or null.
     *
     * Every enrich fetches what the launch card's animation needs,
     * and for a PC game that includes a logo, which no provider gave it before (Mimir asked SteamGridDB for heroes
     * and grids, never `/logos`). Static images only, because the animation draws the logo still.
     */
    suspend fun steamGridDbLogoUrlForGame(apiKey: String, game: SteamGridDbGameRef): String? =
        withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank() || !MimirSteamGridDbGate.isOpen) return@withContext null
                val body = getJsonOrThrow(
                    "$SgdbApi/logos/${game.urlSegment}?types=static&nsfw=false&humor=false&epilepsy=false&limit=20",
                    apiKey,
                )
                val data = body.optJSONArray("data") ?: return@withContext null
                val entries = (0 until data.length()).map { index ->
                    val item = data.getJSONObject(index)
                    SteamGridDbLogoEntry(
                        url = item.optString("url"),
                        style = item.optString("style"),
                        score = item.optLong("score"),
                    )
                }.filter { customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, it.url) }
                steamGridDbBestLogo(entries)?.url
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Downloads a SteamGridDB logo into [target] and proves the bytes decode as an image. SteamGridDB's own CDN
     * only (the same allowlist as a hero), and a still's byte cap, but no hero size floor: a logo is small by nature.
     */
    suspend fun stageSteamGridDbLogo(url: String, target: File): Boolean = withContext(Dispatchers.IO) {
        if (!customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, url)) return@withContext false
        target.parentFile?.mkdirs()
        target.delete()
        val staged = download(url, target, CustomHeroSource.STEAMGRIDDB, customHeroMaxAssetBytes(CustomHeroMediaType.STATIC)) &&
            isDecodableSavedMedia(target, CustomHeroMediaType.STATIC)
        if (!staged) target.delete()
        staged
    }

    /** Downloads and validates a result without mutating the active Home renderer. */
    suspend fun stageCandidate(
        context: android.content.Context,
        candidate: CustomHeroCandidate,
        expectedVideo: Boolean,
        target: File,
    ): Boolean = stageCandidateOutcome(context, candidate, expectedVideo, target) ==
        CustomHeroStageOutcome.READY

    suspend fun stageCandidateOutcome(
        context: android.content.Context,
        candidate: CustomHeroCandidate,
        expectedVideo: Boolean,
        target: File,
    ): CustomHeroStageOutcome = withContext(Dispatchers.IO) {
        if (!customHeroUrlAllowed(candidate.source, candidate.remoteUrl)) {
            Log.w("MimirCustomHero", "rejected off-domain hero: ${customHeroSafeLogLabel(candidate)}")
            return@withContext CustomHeroStageOutcome.UNSUPPORTED_MEDIA
        }
        val motion = candidate.mediaType != CustomHeroMediaType.STATIC
        if (motion != expectedVideo) return@withContext CustomHeroStageOutcome.UNSUPPORTED_MEDIA
        target.parentFile?.mkdirs()
        target.delete()
        val maxBytes = customHeroMaxAssetBytes(candidate.mediaType)
        // A full motion preview may be installed without another transfer. Animated search cards now
        // carry only a bounded provider thumbnail in posterPath, leaving previewPath empty; that poster
        // must never be mistaken for the selected original. Every static picker preview is likewise a
        // bounded 1280×720 JPEG, including ScreenScraper's.
        val reusable = candidate.previewPath
            ?.takeIf { customHeroPreviewReusableAsInstalledAsset(candidate.mediaType) }
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() in 1..maxBytes }
        val copied = reusable?.let { preview -> copySyncedHeroFile(preview, target) } == true
        if (!copied && !download(candidate.remoteUrl, target, candidate.source, maxBytes)) {
            target.delete()
            Log.w("MimirCustomHero", "hero download failed: ${customHeroSafeLogLabel(candidate)}")
            return@withContext CustomHeroStageOutcome.DOWNLOAD_FAILED
        }
        if (!isDecodableSavedMedia(target, candidate.mediaType)) {
            target.delete()
            return@withContext CustomHeroStageOutcome.UNSUPPORTED_MEDIA
        }
        if (!meetsCustomHeroMediaApplyFloor(target, candidate.mediaType)) {
            target.delete()
            return@withContext CustomHeroStageOutcome.BELOW_QUALITY_FLOOR
        }
        CustomHeroStageOutcome.READY
    }

    /**
     * Fetches bounded local picker previews. Static art uses provider thumbnails. Animated Hero Motion
     * prefers its provider thumbnail, then derives a transient first-frame poster from the bounded original
     * only when that thumbnail is absent or unusable; the original is never mounted as a selected asset here.
     * A ScreenScraper clip has no motion thumbnail, so the legacy explicit clip picker downloads its bounded
     * clip, derives a stable poster, and can reuse those full bytes on apply.
     */
    suspend fun withLocalPreviews(
        context: android.content.Context,
        candidates: List<CustomHeroCandidate>,
        previewNamespace: String? = null,
        cachedPreviewFor: (CustomHeroCandidate) -> String? = { null },
        onCandidateReady: suspend (CustomHeroCandidate) -> Unit = {},
    ): List<CustomHeroCandidate> = withContext(Dispatchers.IO) {
        val persistentPreviewRoot = File(CustomHeroStore.cacheDir(context), "preview")
        val safeNamespace = previewNamespace?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val previewRoot = if (safeNamespace == null) {
            persistentPreviewRoot
        } else {
            File(context.cacheDir, "custom-enrich/hero")
        }
        if (safeNamespace != null) {
            val generationPrefix = customHeroPreviewGenerationPrefix(safeNamespace)
            previewRoot.listFiles()
                ?.filter { candidate ->
                    candidate.name.startsWith("manual-") &&
                        !candidate.name.startsWith(generationPrefix)
                }
                ?.forEach(File::deleteRecursively)
        }
        val dir = safeNamespace?.let { File(previewRoot, it) } ?: previewRoot
        dir.mkdirs()
        // Previews are transient: clear the whole preview subdirectory each time so an abandoned pick
        // can't accumulate. Scoped to `hero/preview`, so no cover/icon/clip is ever in reach.
        dir.listFiles()?.forEach { it.deleteRecursively() }
        candidates.mapIndexed { index, candidate ->
            val video = candidate.mediaType == CustomHeroMediaType.VIDEO
            val animated = candidate.mediaType == CustomHeroMediaType.ANIMATED_IMAGE
            // The provider query has already run — only the picker-art transfer is skipped when the
            // Saved rail already holds this exact asset on disk. Never for a clip: this branch mounts
            // previewPath for playback, and a Saved clip's file is a poster, not the selected original.
            val cached = if (video) {
                null
            } else {
                customHeroReusableCachedPreview(candidate, cachedPreviewFor(candidate))
            }
            if (cached != null) {
                val cachedBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cached.absolutePath, cachedBounds)
                val reused = customHeroWithCachedPreview(
                    candidate,
                    cached,
                    cachedBounds.outWidth,
                    cachedBounds.outHeight,
                )
                onCandidateReady(reused)
                return@mapIndexed reused
            }
            val ready = if (video) {
                val file = File(
                    dir,
                    "p$index.mp4",
                )
                if (
                    download(
                        candidate.remoteUrl,
                        file,
                        candidate.source,
                        customHeroMaxAssetBytes(candidate.mediaType),
                    ) &&
                    isDecodableSavedMedia(file, candidate.mediaType)
                ) {
                    val poster = File(dir, "p$index.jpg")
                    val posterPath = poster.takeIf { extractVideoPoster(file, it) }?.absolutePath
                    candidate.copy(previewPath = file.absolutePath, posterPath = posterPath)
                } else {
                    file.delete()
                    candidate
                }
            } else {
                val scratch = File(dir, "p$index.scratch")
                val output = File(dir, "p$index.jpg")
                val sourceBound = staticPreviewSourceByteBound(candidate)
                val hydrated = customHeroPickerPreviewUrls(candidate).any { previewUrl ->
                    scratch.delete()
                    output.delete()
                    download(previewUrl, scratch, candidate.source, sourceBound) &&
                        hydrateBoundedStaticPreview(scratch, output)
                }
                scratch.delete()
                if (hydrated) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(output.absolutePath, bounds)
                    customHeroWithHydratedDimensions(candidate, bounds.outWidth, bounds.outHeight).copy(
                        // An animated provider thumbnail is presentation-only. Leave previewPath empty
                        // so apply/expanded inspect downloads the original motion bytes, never this
                        // bounded first-frame JPEG.
                        previewPath = output.absolutePath.takeUnless { animated },
                        posterPath = output.absolutePath,
                    )
                } else {
                    output.delete()
                    candidate
                }
            }
            onCandidateReady(ready)
            ready
        }
    }

    /**
     * Prepares only ScreenScraper's small game still for clip result cards. This never preloads a video;
     * the full bounded clip is fetched exactly once, after the user chooses that result.
     */
    suspend fun withLocalClipPosters(
        context: android.content.Context,
        candidates: List<CustomHeroCandidate>,
        previewNamespace: String,
        cachedPreviewFor: (CustomHeroCandidate) -> String? = { null },
        onCandidateReady: suspend (CustomHeroCandidate) -> Unit = {},
    ): List<CustomHeroCandidate> = withContext(Dispatchers.IO) {
        val safeNamespace = previewNamespace.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val previewRoot = File(context.cacheDir, "custom-enrich/clip-posters").apply { mkdirs() }
        previewRoot.listFiles()
            ?.filter { it.name.startsWith("manual-") && it.name != safeNamespace }
            ?.forEach(File::deleteRecursively)
        val dir = File(previewRoot, safeNamespace).apply {
            deleteRecursively()
            mkdirs()
        }
        val localByRemote = linkedMapOf<String, String>()
        candidates.mapIndexed { index, candidate ->
            // Poster-only by construction, so a Saved clip's poster is exactly the right reuse here.
            // customHeroWithCachedPreview still refuses to call it a preview, so apply re-downloads
            // the clip itself.
            customHeroReusableCachedPreview(candidate, cachedPreviewFor(candidate))?.let { cached ->
                val reused = customHeroWithCachedPreview(candidate, cached, candidate.width, candidate.height)
                onCandidateReady(reused)
                return@mapIndexed reused
            }
            val previewUrl = candidate.thumbUrl?.takeIf {
                customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, it)
            }
            val local = previewUrl?.let(localByRemote::get) ?: previewUrl?.let { remote ->
                val scratch = File(dir, "p$index.scratch")
                val output = File(dir, "p$index.jpg")
                val hydrated = download(remote, scratch, CustomHeroSource.SCREENSCRAPER, MaxPreviewBytes) &&
                    hydrateBoundedStaticPreview(scratch, output)
                scratch.delete()
                if (hydrated) {
                    output.absolutePath.also { localByRemote[remote] = it }
                } else {
                    output.delete()
                    null
                }
            }
            candidate.copy(posterPath = local).also { onCandidateReady(it) }
        }
    }

    /**
     * Produces picker art without mounting a second video player. The frame is sampled after the usual
     * black-title-card window, scaled by the platform retriever, and stored beside the transient preview.
     * A codec that cannot expose a frame simply keeps the existing labelled fallback tile.
     */
    private fun extractVideoPoster(video: File, poster: File): Boolean {
        val retriever = MediaMetadataRetriever()
        var bitmap: android.graphics.Bitmap? = null
        return try {
            retriever.setDataSource(video.absolutePath)
            val durationMillis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return false
            bitmap = retriever.getScaledFrameAtTime(
                previewClipPosterTimeUs(durationMillis),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                PreviewPosterWidthPx,
                PreviewPosterHeightPx,
            ) ?: retriever.getFrameAtTime(
                previewClipPosterTimeUs(durationMillis),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return false
            FileOutputStream(poster).use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, PreviewPosterJpegQuality, output)
            } && poster.isFile && poster.length() > 0L
        } catch (_: Exception) {
            poster.delete()
            false
        } finally {
            bitmap?.recycle()
            runCatching { retriever.release() }
        }
    }

    /** Saved-catalog seam: derive the same bounded poster after a metadata-first clip is chosen. */
    internal fun createClipPoster(video: File, poster: File): Boolean = extractVideoPoster(video, poster)

    /** Animated WebP/GIF picker art uses a stable first-frame poster; only the backdrop decoder animates. */
    internal fun createMotionArtPoster(image: File, poster: File): Boolean =
        extractMotionArtPoster(image, poster)

    private fun extractMotionArtPoster(image: File, poster: File): Boolean {
        var bitmap: android.graphics.Bitmap? = null
        return try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(image)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sourceWidth = info.size.width.coerceAtLeast(1)
                val sourceHeight = info.size.height.coerceAtLeast(1)
                val scale = minOf(
                    1f,
                    PreviewPosterWidthPx.toFloat() / sourceWidth,
                    PreviewPosterHeightPx.toFloat() / sourceHeight,
                )
                decoder.setTargetSize(
                    (sourceWidth * scale).toInt().coerceAtLeast(1),
                    (sourceHeight * scale).toInt().coerceAtLeast(1),
                )
            }
            FileOutputStream(poster).use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, PreviewPosterJpegQuality, output)
            } && poster.isFile && poster.length() > 0L
        } catch (_: Exception) {
            poster.delete()
            false
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Proves the downloaded bytes are actually the media kind claimed.
     *
     * HTTP 200 with a non-empty body is NOT proof: providers and interception portals both answer with
     * HTML error pages. A still must decode to real dimensions; a clip must expose a duration.
     */
    internal fun isDecodableSavedMedia(file: File, video: Boolean): Boolean {
        return isDecodableSavedMedia(
            file,
            if (video) CustomHeroMediaType.VIDEO else CustomHeroMediaType.STATIC,
        )
    }

    internal fun isDecodableSavedMedia(file: File, mediaType: CustomHeroMediaType): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return when (mediaType) {
            CustomHeroMediaType.VIDEO -> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                validCustomHeroVideoMetadata(duration, width, height)
            } catch (_: Exception) {
                false
            } finally {
                runCatching { retriever.release() }
            }
            }
            CustomHeroMediaType.STATIC -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.outWidth > 0 && options.outHeight > 0
            }
            CustomHeroMediaType.ANIMATED_IMAGE -> isAnimatedImage(file)
        }
    }

    internal fun detectSavedMediaType(file: File): CustomHeroMediaType = when {
        isAnimatedImage(file) -> CustomHeroMediaType.ANIMATED_IMAGE
        isDecodableSavedMedia(file, CustomHeroMediaType.VIDEO) -> CustomHeroMediaType.VIDEO
        else -> CustomHeroMediaType.STATIC
    }

    private fun isAnimatedImage(file: File): Boolean = isAnimatedWebp(file) || isGif(file)

    private fun isGif(file: File): Boolean = runCatching {
        val maxBytes = customHeroMaxAssetBytes(CustomHeroMediaType.ANIMATED_IMAGE)
        if (!file.isFile || file.length() !in 6..maxBytes) return@runCatching false
        file.inputStream().buffered().use { input ->
            val header = ByteArray(6)
            input.read(header) == header.size &&
                header.decodeToString() in setOf("GIF87a", "GIF89a")
        }
    }.getOrDefault(false)

    private fun isAnimatedWebp(file: File): Boolean = runCatching {
        val maxBytes = customHeroMaxAssetBytes(CustomHeroMediaType.ANIMATED_IMAGE)
        if (!file.isFile || file.length() !in 21..maxBytes) return@runCatching false
        file.inputStream().buffered().use { input ->
            val header = ByteArray(minOf(file.length().toInt(), 256 * 1024))
            val read = input.read(header)
            if (read < 21) return@use false
            val riff = header.copyOfRange(0, 4).decodeToString() == "RIFF"
            val webp = header.copyOfRange(8, 12).decodeToString() == "WEBP"
            riff && webp && (0..read - 4).any { start ->
                header[start] == 'A'.code.toByte() &&
                    header[start + 1] == 'N'.code.toByte() &&
                    header[start + 2] == 'I'.code.toByte() &&
                    header[start + 3] == 'M'.code.toByte()
            }
        }
    }.getOrDefault(false)

    private fun searchFirstGameOrThrow(apiKey: String, titles: List<String>): SteamGridDbGameMatch? {
        titles.map(String::trim).filter(String::isNotBlank).distinct().forEach { title ->
            sgdbSearchCandidates(title).forEach { candidate ->
                val encoded = URLEncoder.encode(candidate, "UTF-8")
                val data = getJsonOrThrow("$SgdbApi/search/autocomplete/$encoded", apiKey).optJSONArray("data")
                if (data != null) {
                    for (index in 0 until data.length()) {
                        val item = data.getJSONObject(index)
                        val id = item.optLong("id")
                        val resultTitle = item.optString("name")
                        if (id > 0L && sgdbTitleMatches(title, resultTitle)) {
                            return SteamGridDbGameMatch(id, resultTitle)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun searchManualGamesOrThrow(
        apiKey: String,
        query: String,
        limit: Int,
    ): List<SteamGridDbGameMatch> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val data = getJsonOrThrow("$SgdbApi/search/autocomplete/$encoded", apiKey).optJSONArray("data")
            ?: return emptyList()
        val rows = (0 until data.length()).mapNotNull { index ->
            val item = data.getJSONObject(index)
            val id = item.optLong("id")
            val title = item.optString("name")
            if (id > 0L && title.isNotBlank()) id to title else null
        }
        return rankSteamGridDbAutocompleteMatches(query, rows, limit)
            .map { SteamGridDbGameMatch(it.id, it.title) }
    }

    private fun heroDimensionsQuery(size: String): String = when (size) {
        // SteamGridDB documents Hero canvases at 1600x650, 1920x620, and 3840x1240. HD must query
        // every official usable size; 2K+ remains the high-resolution subset only.
        "HD" -> "&dimensions=1600x650,1920x620,3840x1240"
        "2K+" -> "&dimensions=3840x1240"
        else -> ""
    }

    private fun getJsonOrThrow(url: String, apiKey: String): JSONObject {
        var current = url
        var redirects = 0
        while (redirects <= MaxRedirects) {
            val egress = MimirProviderEgress.open(
                route = MimirProviderEgressRoute.STEAMGRIDDB_API,
                url = current,
                connectTimeoutMs = ConnectTimeoutMs,
                readTimeoutMs = ReadTimeoutMs,
                requestHeaders = mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer $apiKey",
                ),
            ) ?: throw SteamGridDbUnreachableException()
            try {
                val statusCode = egress.statusCode
                if (statusCode in CustomHeroRedirectStatusCodes) {
                    if (redirects == MaxRedirects) throw SteamGridDbProviderException()
                    current = steamGridDbApiRedirectTarget(
                        currentUrl = current,
                        location = egress.header("Location"),
                    ) ?: throw SteamGridDbProviderException()
                    redirects++
                    continue
                }
                if (statusCode != 200) throw SteamGridDbProviderException()
                if (!steamGridDbContentLengthAllowed(egress.declaredLength, MaxJsonBytes)) {
                    throw SteamGridDbProviderException()
                }
                val body = readCustomHeroUtf8Bounded(egress.body(), MaxJsonBytes)
                    ?: throw SteamGridDbProviderException()
                return JSONObject(body)
            } finally {
                egress.close()
            }
        }
        throw SteamGridDbProviderException()
    }

    private fun download(url: String, target: File, source: CustomHeroSource, maxBytes: Long): Boolean {
        val expectedRoute = routeForSource(source) ?: return false
        if (mediaRouteFor(url) != expectedRoute) return false
        if (maxBytes <= 0L) return false
        target.parentFile?.mkdirs()
        val staged = File(
            target.parentFile,
            target.name + "." + System.nanoTime().toULong().toString(16) + ".network.part",
        )
        staged.delete()
        var current = url
        var redirects = 0
        return try {
            while (redirects <= MaxRedirects) {
                if (mediaRouteFor(current) != expectedRoute) return false
                val egress = MimirProviderEgress.open(
                    route = expectedRoute,
                    url = current,
                    connectTimeoutMs = ConnectTimeoutMs,
                    readTimeoutMs = ReadTimeoutMs,
                ) ?: return false
                try {
                    val statusCode = egress.statusCode
                    if (statusCode in CustomHeroRedirectStatusCodes) {
                        if (redirects == MaxRedirects) return false
                        current = customHeroRedirectTarget(
                            source = source,
                            currentUrl = current,
                            location = egress.header("Location"),
                        ) ?: return false
                        redirects++
                        continue
                    }
                    if (statusCode != 200 ||
                        !steamGridDbContentLengthAllowed(egress.declaredLength, maxBytes)
                    ) {
                        return false
                    }
                    val written = FileOutputStream(staged).use { output ->
                        val copied = egress.body().use { input ->
                            copySteamGridDbResponseBounded(input, output, maxBytes)
                        }
                        if (copied != null) output.fd.sync()
                        copied
                    } ?: return false
                    if (written <= 0L) return false
                    return commitSteamGridDbDownloadedFile(staged, target)
                } finally {
                    egress.close()
                }
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            staged.delete()
        }
    }
    internal fun staticPreviewSourceByteBound(candidate: CustomHeroCandidate): Long = when {
        candidate.mediaType == CustomHeroMediaType.ANIMATED_IMAGE -> CustomHeroStore.MaxAssetBytes
        candidate.source == CustomHeroSource.SCREENSCRAPER &&
            candidate.mediaSubtype?.startsWith("support-") == true -> CustomHeroStore.MaxAssetBytes
        else -> MaxPreviewBytes
    }

    /**
     * Reuses a hydrated picker poster when local regeneration fails after a successful motion staging.
     * Never accepts previewPath: that may be the selected original, not presentation art.
     */
    internal fun customHeroReusableCandidatePoster(candidate: CustomHeroCandidate): File? =
        candidate.posterPath
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() in 1..MaxPreviewBytes }

    /**
     * Decodes a downloaded scratch file into a bounded local JPEG preview. Invalid, HTML, or undecodable
     * sources return false so the picker keeps its honest unavailable tile.
     */
    internal fun hydrateBoundedStaticPreview(
        scratch: File,
        output: File,
        maxOutputBytes: Long = MaxPreviewBytes,
    ): Boolean {
        if (isAnimatedImage(scratch)) {
            return extractMotionArtPoster(scratch, output)
        }
        if (!isDecodableSavedMedia(scratch, video = false)) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(scratch.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (
            bounds.outWidth / sample > PreviewPosterWidthPx * 2 ||
            bounds.outHeight / sample > PreviewPosterHeightPx * 2
        ) {
            sample *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeFile(scratch.absolutePath, decodeOptions) ?: return false
        return try {
            val scaled = scalePreviewPosterBitmap(bitmap)
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
            var quality = PreviewPosterJpegQuality
            while (quality >= 40) {
                output.parentFile?.mkdirs()
                output.delete()
                FileOutputStream(output).use { stream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
                }
                if (output.isFile && output.length() in 1..maxOutputBytes) return true
                quality -= 10
            }
            output.delete()
            false
        } catch (_: Exception) {
            output.delete()
            false
        } finally {
            bitmap.recycle()
        }
    }

    private fun scalePreviewPosterBitmap(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val sourceWidth = source.width.coerceAtLeast(1)
        val sourceHeight = source.height.coerceAtLeast(1)
        val scale = minOf(
            1f,
            PreviewPosterWidthPx.toFloat() / sourceWidth,
            PreviewPosterHeightPx.toFloat() / sourceHeight,
        )
        if (scale >= 1f) return source
        val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

}

internal fun customHeroPreviewReusableAsInstalledAsset(mediaType: CustomHeroMediaType): Boolean =
    mediaType == CustomHeroMediaType.VIDEO

/**
 * Ordered picker-art sources. Static originals stay explicit-inspect/apply only. Animated Hero Motion may
 * fall back to its bounded original solely to derive a transient first-frame poster when SteamGridDB's
 * thumbnail is absent or invalid; [CustomHeroFetcher.withLocalPreviews] never publishes those original bytes
 * as previewPath.
 */
internal fun customHeroPickerPreviewUrls(candidate: CustomHeroCandidate): List<String> = buildList {
    candidate.thumbUrl
        ?.takeIf { customHeroUrlAllowed(candidate.source, it) }
        ?.let(::add)
    if (candidate.mediaType == CustomHeroMediaType.ANIMATED_IMAGE) {
        candidate.remoteUrl
            .takeIf { customHeroUrlAllowed(candidate.source, it) }
            ?.takeUnless(::contains)
            ?.let(::add)
    }
}

/**
 * A Saved rail file that may paint this search's picker art instead of transferring it again, or null
 * to hydrate from the provider exactly as before. Missing, empty, or oversized bytes always fall back:
 * a cache is an optimisation, never a source of truth.
 *
 * Bounds reuse the ceiling that already governs each kind — Saved keeps a bounded poster for a clip
 * and the full asset for every other kind — so no new limit is introduced here. This never deletes or
 * moves the file it inspects; the Saved catalog owns those bytes.
 */
internal fun customHeroReusableCachedPreview(
    candidate: CustomHeroCandidate,
    cachedPath: String?,
): File? {
    val file = cachedPath?.takeIf(String::isNotBlank)?.let(::File) ?: return null
    val maxBytes = when (candidate.mediaType) {
        CustomHeroMediaType.VIDEO,
        CustomHeroMediaType.ANIMATED_IMAGE,
        -> CustomHeroStore.MaxPreviewBytes
        CustomHeroMediaType.STATIC -> customHeroMaxAssetBytes(candidate.mediaType)
    }
    return file.takeIf { it.isFile && it.length() in 1..maxBytes }
}

/**
 * The candidate a reused Saved file produces. It is picker ART first: only a still may also stand in
 * as [CustomHeroCandidate.previewPath], so a clip's poster and an animated hero's first frame can
 * never be mistaken for the selected original by the installer's reuse path.
 */
internal fun customHeroWithCachedPreview(
    candidate: CustomHeroCandidate,
    cached: File,
    decodedWidth: Int,
    decodedHeight: Int,
): CustomHeroCandidate = customHeroWithHydratedDimensions(
    candidate,
    decodedWidth,
    decodedHeight,
    // Saved picker-art reuse is still a presentation file, not a verified full-asset measure.
    verified = false,
).copy(
    previewPath = cached.absolutePath.takeIf { candidate.mediaType == CustomHeroMediaType.STATIC },
    posterPath = cached.absolutePath,
)

/**
 * Applies decoded bounds only when they are verified (provider-declared already present, or a full
 * cached asset). Preview-derived dimensions stay unknown so size/fit filters cannot treat a
 * downscaled proxy as authoritative.
 */
internal fun customHeroWithHydratedDimensions(
    candidate: CustomHeroCandidate,
    decodedWidth: Int,
    decodedHeight: Int,
    verified: Boolean = false,
): CustomHeroCandidate {
    if (candidate.width > 0 && candidate.height > 0) return candidate
    if (!verified) return candidate
    return candidate.copy(
        width = decodedWidth.coerceAtLeast(0),
        height = decodedHeight.coerceAtLeast(0),
    )
}

/** All provider/page preview directories from one search generation must coexist until that search closes. */
internal fun customHeroPreviewGenerationPrefix(namespace: String): String {
    val parts = namespace.split('-')
    return if (parts.size >= 2 && parts[0] == "manual" && parts[1].isNotBlank()) {
        "manual-${parts[1]}-"
    } else {
        "$namespace-"
    }
}

/** Stable poster sample: 15% in, but never before 0.5 s or after 3 s. */
internal fun previewClipPosterTimeUs(durationMillis: Long): Long =
    (durationMillis.coerceAtLeast(1L) * 15L / 100L)
        .coerceIn(500L, 3_000L)
        .coerceAtMost(durationMillis.coerceAtLeast(1L)) * 1_000L

private const val PreviewPosterWidthPx = 1280
private const val PreviewPosterHeightPx = 720
private const val PreviewPosterJpegQuality = 90

internal val CustomHeroRedirectStatusCodes = setOf(301, 302, 303, 307, 308)

/** Resolves a redirect only when it remains HTTPS on the same provider allowlist. */
internal fun customHeroRedirectTarget(
    source: CustomHeroSource,
    currentUrl: String,
    location: String?,
): String? {
    val raw = location?.takeIf(String::isNotBlank) ?: return null
    val resolved = runCatching {
        val current = URI(currentUrl)
        if (raw.startsWith("?")) {
            currentUrl.substringBefore('?').substringBefore('#') + raw
        } else {
            current.resolve(raw).toString()
        }
    }.getOrNull() ?: return null
    return resolved.takeIf { customHeroUrlAllowed(source, it) }
}

/** Bounded UTF-8 reader for provider JSON; an oversized body is rejected, never truncated and parsed. */
internal fun readCustomHeroUtf8Bounded(input: InputStream, maxBytes: Long): String? {
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

internal fun validCustomHeroVideoMetadata(durationMillis: Long, width: Int, height: Int): Boolean =
    durationMillis > 0L &&
        width > 0 &&
        height > 0

/** Structural decode and release-quality admission are deliberately separate verdicts. */
internal fun validCustomHeroVideoApplyDimensions(width: Int, height: Int): Boolean =
    width > 0 &&
        height > 0 &&
        minOf(width, height) >= CustomHeroVideoMinShortSidePx

internal const val CustomHeroVideoMinShortSidePx = 480

/** Wide hero stills must clear an honest landscape floor; exact target choice stays user-driven. */
internal fun validCustomHeroStaticApplyDimensions(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0 || width <= height) return false
    return isCustomHeroNormalLandscape(width, height) || isCustomHeroWideBanner(width, height)
}

internal const val CustomHeroStaticMinLandscapeWidthPx = 1_280
internal const val CustomHeroStaticMinLandscapeHeightPx = 720
internal const val CustomHeroWideBannerMinWidthPx = 1_600
internal const val CustomHeroWideBannerMinHeightPx = 620
internal const val CustomHeroWideBannerAspectMin = 2.4f
internal const val CustomHeroWideBannerAspectMax = 3.5f

internal fun isCustomHeroWideBanner(width: Int, height: Int): Boolean {
    val landscapeWidth = maxOf(width, height)
    val landscapeHeight = minOf(width, height)
    if (landscapeWidth < CustomHeroWideBannerMinWidthPx ||
        landscapeHeight < CustomHeroWideBannerMinHeightPx
    ) {
        return false
    }
    val aspect = landscapeWidth.toFloat() / landscapeHeight
    return aspect in CustomHeroWideBannerAspectMin..CustomHeroWideBannerAspectMax
}

internal fun isCustomHeroNormalLandscape(width: Int, height: Int): Boolean {
    if (isCustomHeroWideBanner(width, height)) return false
    val landscapeWidth = maxOf(width, height)
    val landscapeHeight = minOf(width, height)
    return landscapeWidth >= CustomHeroStaticMinLandscapeWidthPx &&
        landscapeHeight >= CustomHeroStaticMinLandscapeHeightPx
}

/**
 * Hero Motion is user-selected animation, not bulk enrichment. A verified landscape 480p asset is
 * acceptable; higher SteamGridDB native Hero canvases still rank ahead through provider score/size.
 */
internal fun validCustomHeroMotionArtDimensions(width: Int, height: Int): Boolean {
    val landscapeWidth = maxOf(width, height)
    val landscapeHeight = minOf(width, height)
    return width > height &&
        landscapeWidth >= CustomHeroMotionMinLongSidePx &&
        landscapeHeight >= CustomHeroMotionMinShortSidePx
}

internal const val CustomHeroMotionMinLongSidePx = 854
internal const val CustomHeroMotionMinShortSidePx = 480

/**
 * Resolution preference for a 1080p hero. Unknown or below-floor dimensions sort last;
 * known usable candidates nearest 1920×1080 sort first without pretending provider score
 * can turn a low-resolution result into a release-quality backdrop.
 */
internal fun customHeroTargetDistance(width: Int, height: Int): Long {
    if (!validCustomHeroStaticApplyDimensions(width, height)) return Long.MAX_VALUE
    val landscapeWidth = maxOf(width, height)
    val landscapeHeight = minOf(width, height)
    val (targetWidth, targetHeight) = if (isCustomHeroWideBanner(width, height)) {
        1_920L to 620L
    } else {
        1_920L to 1_080L
    }
    return kotlin.math.abs(landscapeWidth.toLong() - targetWidth) +
        kotlin.math.abs(landscapeHeight.toLong() - targetHeight)
}

internal data class SteamGridDbGameRankedMatch(
    val id: Long,
    val title: String,
    val rank: Int,
)

/**
 * Deterministic SteamGridDB autocomplete ranking for manual Hero search. Exact canonical equality
 * wins, then conservative whole-token prefix/subsequence matches. Unrelated rows are rejected.
 */
internal fun rankSteamGridDbAutocompleteMatches(
    query: String,
    rows: List<Pair<Long, String>>,
    maxIdentities: Int = 3,
): List<SteamGridDbGameRankedMatch> {
    val cap = maxIdentities.coerceAtLeast(0)
    if (cap == 0 || rows.isEmpty()) return emptyList()
    val ranked = rows.mapNotNull { (id, title) ->
        steamGridDbTitleMatchRank(query, title)?.let { rank ->
            SteamGridDbGameRankedMatch(id = id, title = title, rank = rank)
        }
    }.sortedWith(
        compareBy<SteamGridDbGameRankedMatch> { it.rank }
            .thenBy { steamGridDbNormalizedTitleKey(it.title) }
            .thenBy { it.id },
    )
    if (ranked.isEmpty()) {
        val fallback = rows.filter { (id, title) ->
            steamGridDbProviderFallbackRelevant(query, title)
        }
        return fallback.take(cap).map { (id, title) ->
            SteamGridDbGameRankedMatch(id = id, title = title, rank = Int.MAX_VALUE)
        }
    }
    val bestRank = ranked.first().rank
    if (bestRank == 0 && ranked.count { it.rank == 0 } == 1) {
        return listOf(ranked.first())
    }
    return ranked.take(cap)
}

internal fun steamGridDbTitleMatchRank(query: String, candidateTitle: String): Int? {
    if (steamGridDbTitleEquivalence(query, candidateTitle)) return 0
    val queryTokens = steamGridDbSearchTokens(query).map(::steamGridDbNormalizeSequelToken)
    val candidateTokens = steamGridDbSearchTokens(candidateTitle).map(::steamGridDbNormalizeSequelToken)
    if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return null
    if (isWholeTokenSubsequence(queryTokens, candidateTokens)) {
        return 100 + (candidateTokens.size - queryTokens.size)
    }
    if (candidateTokens.size >= queryTokens.size &&
        queryTokens == candidateTokens.take(queryTokens.size)
    ) {
        return 200 + (candidateTokens.size - queryTokens.size)
    }
    return null
}

internal fun steamGridDbTitleEquivalence(query: String, candidateTitle: String): Boolean {
    if (sgdbTitleMatches(query, candidateTitle)) return true
    val queryIdentity = steamGridDbIdentityTokens(query).map(::steamGridDbNormalizeSequelToken)
    val candidateIdentity = steamGridDbIdentityTokens(candidateTitle).map(::steamGridDbNormalizeSequelToken)
    if (queryIdentity.isNotEmpty() && queryIdentity == candidateIdentity) return true
    return steamGridDbAcronymMatches(query, candidateTitle)
}

internal fun roundRobinDistinctCustomHeroCandidates(
    groups: List<List<CustomHeroCandidate>>,
    limit: Int,
): List<CustomHeroCandidate> {
    if (limit <= 0 || groups.isEmpty()) return emptyList()
    val cursors = IntArray(groups.size)
    val seen = linkedSetOf<String>()
    val result = mutableListOf<CustomHeroCandidate>()
    var advanced: Boolean
    do {
        advanced = false
        groups.forEachIndexed { groupIndex, group ->
            while (cursors[groupIndex] < group.size) {
                val candidate = group[cursors[groupIndex]++]
                if (seen.add(candidate.stableId)) {
                    result += candidate
                    advanced = true
                    break
                }
            }
            if (result.size >= limit) return result
        }
    } while (advanced)
    return result
}

internal fun decodeAnimatedImageBounds(file: File): Pair<Int, Int>? = runCatching {
    var width = 0
    var height = 0
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        width = info.size.width
        height = info.size.height
        decoder.setTargetSize(1, 1)
        decoder.isMutableRequired = false
    }
    if (width > 0 && height > 0) width to height else null
}.getOrNull()

internal fun steamGridDbEntryKnownAndRejected(
    width: Int,
    height: Int,
    animated: Boolean,
    size: String,
    fit: String,
): Boolean {
    if (width <= 0 || height <= 0) return false
    if (animated) return !validCustomHeroMotionArtDimensions(width, height)
    val mediaType = if (animated) {
        CustomHeroMediaType.ANIMATED_IMAGE
    } else {
        CustomHeroMediaType.STATIC
    }
    return !customEnrichHeroMatchesFilters(
        CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = mediaType,
            remoteUrl = "https://cdn2.steamgriddb.com/hero.png",
            width = width,
            height = height,
        ),
        size = size,
        fit = fit,
    )
}

private fun steamGridDbNormalizedTitleKey(title: String): String =
    steamGridDbSearchTokens(title).joinToString("\u0000")

private val steamGridDbRemovableEditionSuffixes = setOf(
    "dualshock",
    "dual shock",
    "dualshock version",
    "director s cut",
    "directors cut",
    "definitive edition",
    "deluxe edition",
    "complete edition",
    "special edition",
    "collector s edition",
    "collectors edition",
    "remastered",
    "remaster",
    "enhanced edition",
    "greatest hits",
    "game of the year edition",
    "goty edition",
    "anniversary edition",
)

private fun steamGridDbIdentityTokens(value: String): List<String> {
    val withoutTags = value
        .replace(Regex("""\([^)]*\)|\[[^]]*\]"""), " ")
        .trim()
    val segments = withoutTags.split(Regex("""\s+-\s+""")).toMutableList()
    if (segments.isNotEmpty()) segments[0] = steamGridDbRestoreSortableEnglishArticle(segments[0].trim())
    val identitySegments = segments.toMutableList()
    while (identitySegments.size > 1) {
        val suffix = steamGridDbCanonicalSearchTokens(identitySegments.last()).joinToString(" ")
        if (suffix !in steamGridDbRemovableEditionSuffixes) break
        identitySegments.removeAt(identitySegments.lastIndex)
    }
    return steamGridDbCanonicalSearchTokens(identitySegments.joinToString(" - "))
}

private fun steamGridDbSearchTokens(value: String): List<String> {
    val withoutTags = value
        .replace(Regex("""\([^)]*\)|\[[^]]*\]"""), " ")
        .trim()
    val segments = withoutTags.split(Regex("""\s+-\s+"""))
    val head = steamGridDbRestoreSortableEnglishArticle(segments.first().trim())
    val normalized = if (segments.size == 1) {
        head
    } else {
        (listOf(head) + segments.drop(1)).joinToString(" - ")
    }
    return steamGridDbCanonicalSearchTokens(normalized)
}

private fun steamGridDbRestoreSortableEnglishArticle(segment: String): String {
    val match = Regex("""^(.+),\s*(the|an|a)\s*$""", RegexOption.IGNORE_CASE)
        .matchEntire(segment.trim())
        ?: return segment
    val stem = match.groupValues[1].trim()
    val article = match.groupValues[2].lowercase()
    return "$article $stem"
}

private fun steamGridDbCanonicalSearchTokens(value: String): List<String> = Normalizer
    .normalize(value, Normalizer.Form.NFD)
    .replace(Regex("""\p{M}+"""), "")
    .lowercase()
    .replace(Regex("""[^a-z0-9]+"""), " ")
    .trim()
    .split(Regex("""\s+"""))
    .filter(String::isNotBlank)

private val steamGridDbRomanSequelValues = mapOf(
    "i" to 1,
    "ii" to 2,
    "iii" to 3,
    "iv" to 4,
    "v" to 5,
    "vi" to 6,
    "vii" to 7,
    "viii" to 8,
    "ix" to 9,
    "x" to 10,
    "xi" to 11,
    "xii" to 12,
    "xiii" to 13,
)

private fun steamGridDbNormalizeSequelToken(token: String): String {
    val lowered = token.lowercase()
    steamGridDbRomanSequelValues[lowered]?.let { return it.toString() }
    return lowered
}

private fun steamGridDbProviderFallbackRelevant(query: String, candidateTitle: String): Boolean {
    val queryIdentity = steamGridDbIdentityTokens(query).map(::steamGridDbNormalizeSequelToken)
    val candidateIdentity = steamGridDbIdentityTokens(candidateTitle).map(::steamGridDbNormalizeSequelToken)
    if (queryIdentity.isNotEmpty() && candidateIdentity.isNotEmpty()) {
        val sharedStem = queryIdentity.dropLast(1).isNotEmpty() &&
            queryIdentity.dropLast(1) == candidateIdentity.dropLast(1)
        if (sharedStem && queryIdentity.last() != candidateIdentity.last()) return false
    }
    return true
}

private fun steamGridDbAcronymMatches(query: String, candidateTitle: String): Boolean {
    val compact = Normalizer
        .normalize(query, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^a-zA-Z0-9]"""), "")
        .lowercase()
    if (compact.length < 2) return false
    val trailingDigits = compact.takeLastWhile(Char::isDigit)
    val acronymLetters = compact.dropLast(trailingDigits.length)
    if (acronymLetters.isEmpty() || !acronymLetters.all(Char::isLetter)) return false
    val tokens = steamGridDbSearchTokens(candidateTitle)
    if (tokens.size < acronymLetters.length) return false
    val initials = tokens
        .take(acronymLetters.length)
        .mapNotNull { it.firstOrNull()?.lowercaseChar() }
        .joinToString("")
    if (initials != acronymLetters) return false
    if (trailingDigits.isEmpty()) return tokens.size == acronymLetters.length
    val sequelToken = tokens.getOrNull(acronymLetters.length) ?: return false
    return steamGridDbNormalizeSequelToken(sequelToken) == trailingDigits
}

private fun isWholeTokenSubsequence(needle: List<String>, haystack: List<String>): Boolean {
    if (needle.isEmpty()) return false
    var index = 0
    haystack.forEach { token ->
        if (token == needle[index]) index++
        if (index == needle.size) return true
    }
    return false
}

internal fun meetsCustomHeroStaticApplyFloor(file: File): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return validCustomHeroStaticApplyDimensions(options.outWidth, options.outHeight)
}

private fun meetsCustomHeroMediaApplyFloor(
    file: File,
    mediaType: CustomHeroMediaType,
): Boolean = when (mediaType) {
    CustomHeroMediaType.STATIC -> meetsCustomHeroStaticApplyFloor(file)
    CustomHeroMediaType.VIDEO -> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            validCustomHeroVideoApplyDimensions(width, height)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }
    CustomHeroMediaType.ANIMATED_IMAGE -> decodeAnimatedImageBounds(file)
        ?.let { (width, height) -> validCustomHeroMotionArtDimensions(width, height) }
        ?: false
}

/** One logo SteamGridDB offers for a game: where it is, its style, and its community score. */
internal data class SteamGridDbLogoEntry(val url: String, val style: String, val score: Long)

/**
 * The logo the launch animation draws: the publisher's official one first, then a white one, then any other, and a
 * black one last, because Mimir draws it over a dark backdrop where a black logo disappears. Within a style, the
 * community's highest score.
 */
internal fun steamGridDbBestLogo(entries: List<SteamGridDbLogoEntry>): SteamGridDbLogoEntry? =
    entries
        .filter { it.url.isNotBlank() }
        .sortedWith(compareBy<SteamGridDbLogoEntry> { steamGridDbLogoStyleRank(it.style) }.thenByDescending { it.score })
        .firstOrNull()

private fun steamGridDbLogoStyleRank(style: String): Int = when (style.lowercase()) {
    "official" -> 0
    "white" -> 1
    "black" -> 3
    else -> 2
}
