package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import com.gruning.mimir.rewrite.core.metadata.online.ProviderTitleMatcher
import com.gruning.mimir.rewrite.core.resolver.library.LibraryStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 *  SteamGridDB square tiles for the HOME SHELF ONLY. Strictly scoped: fetches
 * 512×512 or 1024×1024 grids for SHELVED emulated games into the app-internal cache (`filesDir/sgdb2/<id>.png`) —
 * no DB schema, no SAF, no resolver changes; delete the dir and the look reverts to the ScreenScraper
 * covers (the explicit fallback). Automatic Home tiles require a verified non-blank user SteamGridDB key
 * ([SteamGridDbKeyStore.key]) and an open [MimirSteamGridDbGate]; ScreenScraper opt-in is not part of this path.
 */
/**
 * FUX-05 search ladder: the resolver's game identity, not the file label. The FULL cleaned title is
 * tried first (real subtitle dashes like "Castlevania - Symphony of the Night" keep winning); only a
 * miss strips edition/variant suffixes right-to-left ("Resident Evil 2 - DualShock" → "Resident Evil
 * 2"). Pure for JVM pinning.
 */
internal fun sgdbSearchCandidates(title: String): List<String> {
    val cleaned = title.substringBefore("(").substringBefore("[").trim()
    if (cleaned.isBlank()) return emptyList()
    val candidates = mutableListOf(cleaned)
    var current = cleaned
    while (current.contains(" - ")) {
        current = current.substringBeforeLast(" - ").trim()
        if (current.isNotBlank()) candidates.add(current)
    }
    return candidates.distinct()
}

/** Conservative autocomplete validation: an adjacent sequel/edition must never become the art identity. */
internal fun sgdbTitleMatches(query: String, result: String): Boolean =
    ProviderTitleMatcher.matches(query, result)

/** An automatic fetch may fill only a still-empty slot that the owner has not explicitly selected. */
internal fun automaticTileFetchMayInstall(
    targetExists: Boolean,
    selection: GameIdentityMediaSelection,
): Boolean = !targetExists && selection != GameIdentityMediaSelection.SELECTED

internal object SteamGridDbTileFetcher {
    private const val Api = SteamGridDbApiBase
    /** Per explicit page; more work happens only when the user activates the More-results tail. */
    private const val ManualPageSize = 12
    /** Defensive ceiling for legacy non-paged consumers. */
    private const val MaxPickerCandidates = 48
    /** Platform art : aggregate grids from up to this many console entries across name angles. */
    private const val MaxPlatformSearchGames = 6
    /** The platform picker shows a generous set so the user has real choice among icon styles. */
    private const val MaxPlatformPickerCandidates = 18

    private const val PoliteDelayMs = 150L
    private const val ConnectTimeoutMs = 8_000
    private const val ReadTimeoutMs = 15_000
    private const val MaxRedirects = 3
    private const val MaxJsonBytes = 2L * 1024L * 1024L
    private const val MaxPreviewMediaBytes = 4L * 1024L * 1024L
    private const val MaxProviderMediaBytes = CustomHeroStore.MaxAssetBytes

    internal fun canonicalTileIconFile(context: Context, cacheKey: String): File =
        File(File(context.filesDir, "sgdb2"), fileNameFor(cacheKey))

    /**
     * Portrait companion of [canonicalTileIconFile] (the Library cover slot is
     * portrait, so a square SteamGridDB grid leaves visible gaps around the focus ring). Lives at a DISTINCT path from the
     * square tile ([portraitFileNameFor] shares [fileNameFor]'s exact hex derivation of [cacheKey] with a
     * `-portrait` marker before the extension) so both shapes coexist on disk for one game:
     * [resolveShelfFaces] prefers this file over [canonicalTileIconFile] for a game that has no cover, on both
     * shelves (one shelf face, 2026-10-10); a square pick of his still outranks it.
     */
    internal fun canonicalPortraitTileIconFile(context: Context, cacheKey: String): File =
        File(File(context.filesDir, "sgdb2"), portraitFileNameFor(cacheKey))

    /**
     * Tells the library a tile was actually written: ONE re-projection of every open row flow, reading the shelf face
     * again (policy 2026-08-19, PM gate DEFECT 1; policy 2026-10-10, every platform, every surface).
     *
     * A tile write touches no Room table. For a game with no cover every projected URI is null before and after it, so the
     * repository's `distinctUntilChanged` swallowed the re-emission and the Library said "Game enriched" and repainted on
     * the next cold platform open. The face is read inside the repository's own row projection now
     * (`CoverOverrideSource.shelfFaceOverrideFor`), so a single `refreshShelfFaces` makes EVERY reader of the repository
     * see the new tile -- the Library, the Bottom platform shelf, cold-start warm-up -- not only a decorated copy.
     *
     * Called once per batch by [fetchMissing] and [fetchMissingPortrait], and only when a file was written, never on a no-op
     * call: an idle poll must not re-map the library for nothing. Failures are swallowed; a repaint must never fail a fetch.
     */
    private fun publishTileWrite(context: Context) {
        runCatching { LibraryStore.repository(context).refreshShelfFaces() }
    }

    /**
     * Whether a tile already exists on disk for [cacheKey] — the exact same file [fetchMissing] itself
     * checks before fetching (`fetchMissing`'s own `target.isFile` skip), exposed for callers that only
     * need the presence answer and must not reimplement [fileNameFor]'s scheme at their call site.
     */
    internal fun hasTile(context: Context, cacheKey: String): Boolean =
        canonicalTileIconFile(context, cacheKey).isFile

    /** Portrait counterpart of [hasTile], same exact-path contract against [canonicalPortraitTileIconFile]. */
    internal fun hasPortraitTile(context: Context, cacheKey: String): Boolean =
        canonicalPortraitTileIconFile(context, cacheKey).isFile

    fun tileIconFile(context: Context, cacheKey: String): File =
        when (val projection = EnrichSavedStore.rendererReadProjection(EnrichMediaKind.ICON, cacheKey)) {
            EnrichRendererReadProjection.Automatic ->
                File(File(context.filesDir, "enrich-rollback"), "automatic-no-icon")
            is EnrichRendererReadProjection.Override -> projection.file
            null -> canonicalTileIconFile(context, cacheKey)
        }

    /** One selectable art candidate: full-size url + small preview thumb (FUX-10 tile menu). */
    data class Candidate(
        val url: String,
        val thumbUrl: String,
        val previewPath: String? = null,
        /** Validated SteamGridDB title alias that can help the explicit ScreenScraper lookup. */
        val resolvedTitle: String? = null,
        /** ScreenScraper system logos are center-fit onto the ruled 1024-square icon canvas on apply. */
        val normalizeSquare: Boolean = false,
        val providerAssetId: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val assetKind: SteamGridDbAssetKind = SteamGridDbAssetKind.GRID,
        val style: String? = null,
        val providerLabel: String = "SteamGridDB",
        /**
         * The provider's own media type (`box-3d`, `support-2d`, ...), threaded so apply can record
         * WHICH KIND of face he chose. Structured rather than parsed out of [providerAssetId] or
         * [providerLabel]: an id is a content-addressed string and a label is copy, and deriving a
         * stored fact from copy means a wording change silently re-shapes his art.
         */
        val providerMediaType: String? = null,
        val savedSource: EnrichSavedSource = EnrichSavedSource.STEAMGRIDDB,
    ) {
        val stableId: String
            get() = savedSource.wireName + "-" + assetKind.name.lowercase() + "-" +
                (providerAssetId ?: url.hashCode().toUInt().toString(16))
    }

    private data class GameMatch(val id: Long, val title: String)
    private data class SteamGridDbCandidatePage(
        val candidates: List<Candidate>,
        val hasMore: Boolean,
    )

    sealed interface CandidateLookup {
        data object NeedsKey : CandidateLookup
        data object NeedsAccess : CandidateLookup
        data class Loaded(val candidates: List<Candidate>, val hasMore: Boolean = false) : CandidateLookup
        data object Busy : CandidateLookup
        data object Failed : CandidateLookup
    }

    /**
     * Outcome of [searchGameByTitle]. Three-valued on purpose: [searchFirstGameId]'s nullable shape folds
     * a transport failure into the same null as an honest miss, and the Library's ENRICH card must never
     * say "no art" for a lookup that never got an answer (it says "Couldn't enrich" for that instead).
     */
    sealed interface TitleSearch {
        data class Found(val game: SteamGridDbGameRef.Game, val title: String) : TitleSearch
        data object NoMatch : TitleSearch
        data object Failed : TitleSearch
    }

    /**
     * Presentation-only free-form search. Unlike [candidatesFor], this intentionally accepts the
     * autocomplete service's top matches and never feeds the resolved provider title into metadata.
     */
    suspend fun manualCandidatesFor(
        context: Context,
        apiKey: String,
        query: String,
        kind: String,
        style: String,
        size: String,
        generation: Int,
        page: Int = 0,
        pageSize: Int = ManualPageSize,
        /**
         * Fetch the PORTRAIT grid family as well as the square one. The face picker is merged -- it offers
         * the box family and the physical article in one grid -- so a platform whose faces come from
         * SteamGridDB needs both lanes: portrait grids stand in for the box, square for the article.
         */
        includePortraitGrids: Boolean = false,
        onCandidate: suspend (Candidate) -> Unit = {},
    ): CandidateLookup = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext CandidateLookup.NeedsKey
        if (!MimirSteamGridDbGate.isOpen) return@withContext CandidateLookup.NeedsAccess
        if (query.isBlank() || page < 0 || pageSize <= 0) return@withContext CandidateLookup.Loaded(emptyList())
        try {
            // One exact autocomplete identity keeps paging lossless and bounds every user step to at
            // most two asset calls (grid + icon). The search field remains editable for another identity.
            val games = manualSearchGamesOrThrow(apiKey, query, limit = 1)
            val candidatePages = mutableListOf<SteamGridDbCandidatePage>()
            val perEndpointLimit = if (kind == "All") (pageSize / 2).coerceAtLeast(1) else pageSize
            for (game in games) {
                if (kind != "Icons") {
                    candidatePages += manualGridCandidatesOrThrow(
                        apiKey,
                        game.id,
                        style,
                        size,
                        page,
                        perEndpointLimit,
                    )
                    if (includePortraitGrids) {
                        candidatePages += manualGridCandidatesOrThrow(
                            apiKey,
                            game.id,
                            style,
                            size,
                            page,
                            perEndpointLimit,
                            portrait = true,
                        )
                    }
                }
                if (kind != "Grids") {
                    candidatePages += manualIconCandidatesOrThrow(
                        apiKey,
                        game.id,
                        size,
                        page,
                        perEndpointLimit,
                    )
                }
                delay(PoliteDelayMs)
            }
            val aggregated = roundRobinDistinctCandidates(candidatePages.map { it.candidates }, pageSize)
            val previewRoot = File(context.cacheDir, "custom-enrich/sgdb").apply { mkdirs() }
            val generationPrefix = "manual-$generation-page-"
            val namespace = "$generationPrefix$page"
            previewRoot.listFiles()
                ?.filter { it.name.startsWith("manual-") && !it.name.startsWith(generationPrefix) }
                ?.forEach(File::deleteRecursively)
            val previewDir = File(previewRoot, namespace).apply {
                deleteRecursively()
                mkdirs()
            }
            val ready = mutableListOf<Candidate>()
            aggregated.forEachIndexed { index, candidate ->
                val preview = File(previewDir, "p$index.png")
                val raw = File(previewDir, "p$index.raw.part")
                val downloaded = downloadSavedSource(
                    candidate.thumbUrl,
                    raw,
                    candidate.savedSource,
                    MaxPreviewMediaBytes,
                )
                val prepared = if (downloaded && candidate.assetKind == SteamGridDbAssetKind.ICON) {
                    normalizeSquareStill(raw, preview, edgePx = 512)
                } else if (downloaded) {
                    installHeroAsset(
                        temp = raw,
                        target = preview,
                        validate = ::decodableStill,
                        commit = { true },
                    ) == HeroInstallOutcome.INSTALLED
                } else {
                    false
                }
                raw.delete()
                if (prepared && decodableStill(preview)) {
                    val loaded = candidate.copy(
                        previewPath = preview.absolutePath,
                        normalizeSquare = candidate.assetKind == SteamGridDbAssetKind.ICON,
                    )
                    ready += loaded
                    onCandidate(loaded)
                } else {
                    preview.delete()
                }
                delay(PoliteDelayMs)
            }
            CandidateLookup.Loaded(ready, hasMore = candidatePages.any(SteamGridDbCandidatePage::hasMore))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: SteamGridDbHttpException) {
            if (error.code == 429) CandidateLookup.Busy else CandidateLookup.Failed
        } catch (_: Exception) {
            CandidateLookup.Failed
        }
    }

    /**
     * All filtered square-grid candidates for a title — powers the tile menu's art picker. The app's
     * Coil loader is LOCAL-ONLY (posture), so each candidate's thumb is downloaded here into a
     * preview cache and surfaced as a local file path the overlay can render.
     */
    suspend fun candidatesFor(context: Context, apiKey: String, title: String): CandidateLookup =
        candidatesFor(context, apiKey, listOf(title))

    suspend fun candidatesFor(context: Context, apiKey: String, titles: List<String>): CandidateLookup =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext CandidateLookup.NeedsKey
            if (!MimirSteamGridDbGate.isOpen) return@withContext CandidateLookup.NeedsAccess
            try {
                val game = searchFirstGameOrThrow(apiKey, titles)
                    ?: return@withContext CandidateLookup.Loaded(emptyList())
                delay(PoliteDelayMs)
                val previews = File(context.filesDir, "sgdb2/preview").apply {
                    deleteRecursively()
                    mkdirs()
                }
                // Unique names per fetch: a reused path (p0.png for every game) would hit Coil's
                // model-string cache and show the PREVIOUS game's thumbs in the picker.
                val stamp = System.currentTimeMillis()
                val candidates = tileIconCandidatesOrThrow(apiKey, "game/${game.id}")
                    .take(MaxPickerCandidates)
                    .mapIndexed { i, candidate ->
                        val thumbFile = File(previews, "p$stamp-$i.png")
                        downloadSavedSource(candidate.thumbUrl, thumbFile, candidate.savedSource, MaxPreviewMediaBytes)
                        delay(PoliteDelayMs)
                        if (decodableStill(thumbFile)) {
                            candidate.copy(previewPath = thumbFile.absolutePath, resolvedTitle = game.title)
                        } else {
                            thumbFile.delete()
                            candidate.copy(resolvedTitle = game.title)
                        }
                    }
                CandidateLookup.Loaded(candidates)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                CandidateLookup.Failed
            }
        }

    /**
     * Resolve ONE game entry by name for a caller that will then fetch several asset kinds for it: the same autocomplete route, the same [sgdbSearchCandidates]-derived [queries] and the same
     * [sgdbTitleMatches] validation the Home shelf's picker ([candidatesFor]) already trusts for its name
     * search -- shared through [firstMatchingGameOrThrow], never a second matcher. Unlike [candidatesFor]
     * it downloads nothing: the caller keys its own fetches by the returned [SteamGridDbGameRef.Game] so
     * every asset kind (portrait grid, square grid, hero) agrees on WHICH game. Gate/key checks are the
     * caller's (`ArtworkFetchController.fetchOnePcArtwork`); a closed gate here surfaces as
     * [TitleSearch.Failed] because the guarded egress refuses to open.
     */
    suspend fun searchGameByTitle(apiKey: String, title: String, queries: List<String>): TitleSearch =
        withContext(Dispatchers.IO) {
            try {
                val match = firstMatchingGameOrThrow(apiKey, title, queries)
                    ?: return@withContext TitleSearch.NoMatch
                TitleSearch.Found(SteamGridDbGameRef.Game(match.id), match.title)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                TitleSearch.Failed
            }
        }

    /**
     * MANY platform-icon candidates for the user to choose from. Searches SGB across every canonical name angle for the console ("Genesis"
     * vs "Mega Drive", "PlayStation 1" vs "PSX", …), aggregates every matched console entry's grids, de-dupes by
     * url, and surfaces up to [MaxPlatformPickerCandidates] — far more choice than the single-title path.
     */
    suspend fun platformCandidatesFor(
        context: Context,
        apiKey: String,
        platformIdValue: String,
        fallbackLabel: String,
    ): List<Candidate> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !MimirSteamGridDbGate.isOpen) return@withContext emptyList()
        try {
            val gameIds = platformSearchGameIds(apiKey, platformIdValue, fallbackLabel)
            val seenUrls = LinkedHashSet<String>()
            val aggregated = mutableListOf<Candidate>()
            for (id in gameIds) {
                if (aggregated.size >= MaxPlatformPickerCandidates) break
                tileIconCandidates(apiKey, "game/$id").forEach { candidate ->
                    if (aggregated.size < MaxPlatformPickerCandidates && seenUrls.add(candidate.url)) {
                        aggregated.add(candidate)
                    }
                }
                delay(PoliteDelayMs)
            }
            val previews = File(context.filesDir, "sgdb2/preview").apply {
                deleteRecursively()
                mkdirs()
            }
            val stamp = System.currentTimeMillis()
            aggregated.mapIndexed { i, candidate ->
                val thumbFile = File(previews, "p$stamp-$i.png")
                downloadSavedSource(candidate.thumbUrl, thumbFile, candidate.savedSource, MaxPreviewMediaBytes)
                delay(PoliteDelayMs)
                if (decodableStill(thumbFile)) {
                    candidate.copy(previewPath = thumbFile.absolutePath)
                } else {
                    thumbFile.delete()
                    candidate
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * ScreenScraper's system-media fallback for platform identities that have no qualifying SteamGridDB
     * 512/1024 asset. Credential-bearing URLs remain transient download inputs; only 512-square local
     * previews leave this function, and the final pick is normalized to 1024 square by [applyPick].
     */
    suspend fun screenScraperPlatformCandidatesFor(
        context: Context,
        credentialedInputs: List<String>,
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val previews = File(context.filesDir, "sgdb2/preview").apply {
            deleteRecursively()
            mkdirs()
        }
        val stamp = System.currentTimeMillis()
        credentialedInputs
            .asSequence()
            .filter { customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, it) }
            .distinct()
            .take(MaxScreenScraperPlatformCandidates)
            .mapIndexedNotNull { index, input ->
                val raw = File(previews, "ss-$stamp-$index.raw.part")
                val preview = File(previews, "ss-$stamp-$index.png")
                download(input, raw, CustomHeroSource.SCREENSCRAPER, MaxPreviewMediaBytes)
                val installed = normalizeSquareStill(raw, preview, edgePx = 512)
                raw.delete()
                if (installed) {
                    Candidate(
                        url = input,
                        thumbUrl = input,
                        previewPath = preview.absolutePath,
                        normalizeSquare = true,
                        providerLabel = "ScreenScraper",
                        savedSource = EnrichSavedSource.SCREENSCRAPER,
                    )
                } else {
                    preview.delete()
                    null
                }
            }
            .toList()
    }

    /** Live-proof seam: provider match breadth without downloading any candidate image bytes. */
    suspend fun platformSearchMatchCount(
        apiKey: String,
        platformIdValue: String,
        fallbackLabel: String,
    ): Int = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            0
        } else {
            try {
                platformSearchGameIds(apiKey, platformIdValue, fallbackLabel).size
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                0
            }
        }
    }

    private suspend fun platformSearchGameIds(
        apiKey: String,
        platformIdValue: String,
        fallbackLabel: String,
    ): LinkedHashSet<Long> {
        val gameIds = LinkedHashSet<Long>()
        for (angle in platformArtSearchTitles(platformIdValue, fallbackLabel)) {
            if (gameIds.size >= MaxPlatformSearchGames) break
            searchTopGameIds(apiKey, angle, perTitle = 2).forEach {
                if (gameIds.size < MaxPlatformSearchGames) gameIds.add(it)
            }
            delay(PoliteDelayMs)
        }
        return gameIds
    }

    /** Downloads the user's explicit pick over the tile and records its index. Returns success. */
    suspend fun applyPick(context: Context, cacheKey: String, candidate: Candidate, index: Int): Boolean =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "sgdb2").apply { mkdirs() }
            val staged = File(dir, "identity-${System.nanoTime().toULong().toString(16)}.candidate.part")
            staged.delete()
            try {
                val ready = stagePick(context, candidate, staged)
                ready && GameIdentityMediaMutationGate.run(context, blocked = false) {
                    val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
                    val priorSelection = GameIdentityMediaSelectionStore.record(
                        context,
                        EnrichMediaKind.ICON,
                        currentCacheKey,
                    )
                    if (!GameIdentityMediaSelectionStore.set(
                            context,
                            EnrichMediaKind.ICON,
                            currentCacheKey,
                            GameIdentityMediaSelection.SELECTED,
                        )
                    ) {
                        return@run false
                    }
                    activateLocalPick(context, currentCacheKey, staged, index.toString()).also { installed ->
                        if (!installed) {
                            GameIdentityMediaSelectionStore.write(
                                context,
                                EnrichMediaKind.ICON,
                                currentCacheKey,
                                priorSelection,
                            )
                        }
                    }
                }
            } finally {
                staged.delete()
            }
        }

    /** Explicit Automatic is durable even when there is currently no custom file to delete. */
    suspend fun clearPickAsAutomatic(context: Context, cacheKey: String): Boolean =
        withContext(Dispatchers.IO) {
            GameIdentityMediaMutationGate.run(context, blocked = false) {
                val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
                val priorSelection = GameIdentityMediaSelectionStore.record(
                    context,
                    EnrichMediaKind.ICON,
                    currentCacheKey,
                )
                if (!GameIdentityMediaSelectionStore.set(
                        context,
                        EnrichMediaKind.ICON,
                        currentCacheKey,
                        GameIdentityMediaSelection.AUTOMATIC,
                    )
                ) {
                    return@run false
                }
                clearPick(context, currentCacheKey)
                val target = canonicalTileIconFile(context, currentCacheKey)
                val cleared = !target.exists() &&
                    !File(target.parentFile, target.name + ".idx").exists()
                if (!cleared) {
                    GameIdentityMediaSelectionStore.write(
                        context,
                        EnrichMediaKind.ICON,
                        currentCacheKey,
                        priorSelection,
                    )
                }
                cleared
            }
        }

    /**
     * Stages and validates a remote result without mutating the currently-rendered icon.
     *
     * [keepsShape] is for a BOX or the physical article, which are not square ( takes any
     * shape and the tile follows the pick). Such a pick is kept as the provider drew it -- decodable, bounded, never
     * canvas-normalised -- because squaring a 3D box onto a 1024 canvas is what made a box a square with bars. A SQUARE pick
     * (the default) is the unchanged path below.
     */
    suspend fun stagePick(context: Context, candidate: Candidate, target: File, keepsShape: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val raw = File(target.parentFile, target.name + ".download")
            raw.delete()
            target.delete()
            try {
                if (!downloadSavedSource(candidate.url, raw, candidate.savedSource)) return@withContext false
                if (keepsShape) {
                    return@withContext installHeroAsset(
                        temp = raw,
                        target = target,
                        validate = ::decodableStill,
                        commit = { true },
                    ) == HeroInstallOutcome.INSTALLED
                }
                // Icon Art is one canonical 1:1 presentation class. A provider may supply either
                // 512 or 1024 square (or a transparent non-square logo); every explicit pick is
                // center-fit onto the same 1024 canvas before it becomes renderer authority.
                val ready = normalizeSquareStill(raw, target, edgePx = 1_024)
                ready && decodableStill(target)
            } finally {
                raw.delete()
            }
        }

    /** Promotes a catalog file through the same rollback-safe active-file seam used by uploads. */
    suspend fun activateLocalPick(
        context: Context,
        cacheKey: String,
        source: File,
        marker: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!source.isFile || source.length() !in 1..CustomHeroStore.MaxAssetBytes) return@withContext false
        val target = canonicalTileIconFile(context, cacheKey)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".activate.part")
        temp.delete()
        val copied = copySyncedHeroFile(source, temp)
        if (!copied) {
            temp.delete()
            return@withContext false
        }
        installHeroAsset(
            temp = temp,
            target = target,
            validate = ::decodableStill,
            commit = {
                runCatching {
                    val index = pickIndexFile(context, cacheKey)
                    if (marker == null) {
                        !index.exists() || index.delete()
                    } else {
                        index.writeText(marker)
                        true
                    }
                }.getOrDefault(false)
            },
        ) == HeroInstallOutcome.INSTALLED
    }

    internal fun activePickMarker(context: Context, cacheKey: String): String? =
        when (val projection = EnrichSavedStore.rendererReadProjection(EnrichMediaKind.ICON, cacheKey)) {
            EnrichRendererReadProjection.Automatic -> null
            is EnrichRendererReadProjection.Override -> projection.iconMarker
            null -> runCatching {
                pickIndexFile(context, cacheKey).takeIf(File::isFile)?.readText()?.trim()
            }.getOrNull()
        }

    fun activePickIndex(context: Context, cacheKey: String): Int? =
        activePickMarker(context, cacheKey)?.toIntOrNull()

    fun uploadedPickActive(context: Context, cacheKey: String): Boolean =
        activePickMarker(context, cacheKey) == "upload"

    /** Stable cache keys: emulated games by id, Android rows by package (FUX-06). */
    fun emulatedKey(gameId: GameId): String = gameId.value
    fun androidKey(packageName: String): String = "android/" + packageName

    // platform tiles keyed by platformId, namespaced so a game id + a platform id can never
    // collide in the shared sgdb2 store. Powers the Library-shelf "Y → change platform icon art" flow.
    private const val PlatformKeyPrefix = "platform/"

    fun platformKey(platformId: PlatformId): String = PlatformKeyPrefix + platformId.value

    /**
     * True when a cache key addresses a PLATFORM tile rather than a game or Android row.
     *
     * The icon import path is shared by platform tiles, Home game tiles and Tome physical art, so a rule that
     * applies to platform icons alone (normalize custom platform icons to 512 px on
     * apply) has to be able to tell them apart. This predicate is the ONE place that knows the namespace, so a
     * caller never re-implements the prefix test and drifts from [platformKey].
     */
    fun isPlatformKey(cacheKey: String): Boolean = cacheKey.startsWith(PlatformKeyPrefix)

    // the SGB search term for a platform's art is the CANONICAL console title, not the shelf
    // label ("Playstation equals Playstation 1"). [platformArtSearchTitle] returns the primary term (locked by
    // PlatformArtSearchTitleTest); [platformArtSearchTitles] adds the community's alternate spellings/regions so
    // the picker can gather art across name angles. Every list's FIRST entry is the primary term. Un-mapped ids fall back to the label.
    fun platformArtSearchTitle(platformIdValue: String, fallbackLabel: String): String =
        platformArtSearchTitles(platformIdValue, fallbackLabel).first()

    fun platformArtSearchTitles(platformIdValue: String, fallbackLabel: String): List<String> = when (platformIdValue) {
        "ps1" -> listOf("PlayStation 1", "PlayStation", "Sony PlayStation", "PSX", "PSone")
        // Reported tested 2026-07-24: the Sony family's BARE names return nothing from the provider; the
        // "Sony "-prefixed angle is what finds art. Reported confirmed for ps2, ps3 and psp. The picker
        // seeds from FIRST only (see the note above), so a weak primary shows an empty picker even though a
        // working angle is sitting right here in the list. ps1 is NOT reordered — it was not reported, and
        // its bare "PlayStation 1" has no reported test. psvita likewise untested.
        //
        // ORDER MATTERS BEYOND THE PICKER, and an earlier revision of this comment got it wrong. The auto
        // path (platformArtGameIds) does NOT walk the whole list: it breaks at MaxPlatformSearchGames = 6
        // with perTitle = 2, so it consumes roughly the first THREE angles. Two consequences that are
        // measured-unknown rather than claimed: psp gained a fourth angle, pushing "PSP" out of ordinary
        // auto reach; and reordering ps2/ps3 changes LinkedHashSet insertion order, so the auto-selected
        // tile can differ for an existing install. Neither is device-tested. Retaining a trailing angle is
        // therefore NOT free coverage on the auto path — only on the picker's fallback walk.
        "ps2" -> listOf("Sony PlayStation 2", "PlayStation 2", "PS2")
        "ps3" -> listOf("Sony PlayStation 3", "PlayStation 3", "PS3")
        // psp needed an ADDED angle, not a reorder: reported tested 2026-07-24, the term that finds art is
        // "Sony PlayStation Portable", which was not in this list at all. The prior angles are retained.
        "psp" -> listOf("Sony PlayStation Portable", "Sony PSP", "PlayStation Portable", "PSP")
        "psvita" -> listOf("PlayStation Vita", "PS Vita", "Sony PlayStation Vita")
        "nes" -> listOf("Nintendo Entertainment System", "NES", "Famicom", "Nintendo Famicom")
        "snes" -> listOf("Super Nintendo Entertainment System", "Super Nintendo", "SNES", "Super Famicom")
        "n64" -> listOf("Nintendo 64", "N64")
        "gamecube" -> listOf("Nintendo GameCube", "GameCube", "Nintendo GameCube (GCN)")
        "wii" -> listOf("Nintendo Wii", "Wii")
        "wiiu" -> listOf("Nintendo Wii U", "Wii U")
        "switch" -> listOf("Nintendo Switch", "Switch")
        "gb" -> listOf("Game Boy", "Nintendo Game Boy")
        "gbc" -> listOf("Game Boy Color", "Nintendo Game Boy Color")
        "gba" -> listOf("Game Boy Advance", "Nintendo Game Boy Advance", "GBA")
        "ds" -> listOf("Nintendo DS", "Nintendo DS (NDS)")
        "3ds" -> listOf("Nintendo 3DS", "3DS")
        "dreamcast" -> listOf("Sega Dreamcast", "Dreamcast")
        "saturn" -> listOf("Sega Saturn", "Saturn")
        "megadrive", "genesis", "gmd" -> listOf("Sega Genesis", "Sega Mega Drive", "Mega Drive", "Genesis")
        "mastersystem", "sms" -> listOf("Sega Master System", "Master System")
        "gamegear", "gg" -> listOf("Sega Game Gear", "Game Gear")
        "arcade", "mame" -> listOf("Arcade", "MAME", "Multiple Arcade Machine Emulator", "Neo Geo")
        "3do", "panasonic3do" -> listOf("3DO Interactive Multiplayer", "Panasonic 3DO", "3DO")
        else -> listOf(fallbackLabel)
    }

    /**
     * Hard ceiling on the CP-02b angle walk, independent of the table's contents.
     *
     * [nextPlatformArtSearchAngle] re-derives position by case-insensitive string lookup, and
     * `indexOfFirst` always returns the FIRST match — so a case-insensitive duplicate anywhere in an angle
     * list makes the walk cycle rather than terminate, and every cycle issues a live provider request.
     * The table is hand-maintained and its documented edit pattern is "add the newly-tested spelling, keep
     * the old ones", which is exactly the edit that would introduce such a duplicate.
     *
     * Termination must therefore not depend on the table being well-formed. It is 6 because the longest
     * shipping list is 5; `platformArtSearchAnglesAreDistinct` fails at test time if any list grows past
     * this or gains a duplicate, so the ceiling is a backstop and not the primary defence.
     */
    const val MaxPlatformArtSearchAngles: Int = 6

    /**
     * CP-02b — the next angle to try after [currentQuery] came back empty, or null to stop.
     *
     * The picker seeds from [platformArtSearchTitle] (`.first()`) while the AUTO fetch walks every angle,
     * so a platform whose primary term is weak showed an empty picker even though this table already knew a
     * working spelling. Reported for ps2, then ps3 and psp.
     *
     * Two deliberate refusals, both about not surprising the user:
     * - **Non-platform pickers return null.** Game art has no angle table; there is nothing to fall back to.
     * - **A hand-edited query returns null.** If [currentQuery] is not itself one of this platform's angles
     *   the user typed it, and silently searching something else would answer a question they did not ask.
     *   Only the SEEDED chain walks.
     *
     * Position is re-derived by case-insensitive lookup, and `indexOfFirst` returns the FIRST match — so
     * a duplicate angle would cycle rather than terminate. Termination is NOT a property of this function:
     * callers must bound the walk with [MaxPlatformArtSearchAngles]. Do not remove that ceiling on the
     * strength of this contract.
     */
    fun nextPlatformArtSearchAngle(cacheKey: String, currentQuery: String): String? {
        val platformIdValue = cacheKey.removePrefix("platform/").takeIf { it != cacheKey } ?: return null
        val angles = platformArtSearchTitles(platformIdValue, currentQuery)
        val index = angles.indexOfFirst { it.equals(currentQuery, ignoreCase = true) }
        if (index < 0) return null
        return angles.getOrNull(index + 1)
    }

    /** Revert-to-default: delete the picked tile + its .idx sidecar for [cacheKey]. Returns true if one existed. */
    fun clearPick(context: Context, cacheKey: String): Boolean {
        val dir = File(context.filesDir, "sgdb2")
        val tile = File(dir, fileNameFor(cacheKey))
        val had = tile.exists()
        tile.delete()
        File(dir, fileNameFor(cacheKey) + ".idx").delete()
        return had
    }

    /**
     * Fetches missing tiles for [titlesByKey]; returns how many landed. Never throws.
     *
     * [steamAppIdsByKey] carries the exact Steam app id for any cache key the caller has already
     * resolved one for (policy 2026-08-19, extending R-23's exact-id hero lane to this automatic
     * tile fetch). For such a key, [steamGridDbUrlSegmentFromAppId] resolves a `steam/<appid>` segment
     * and the fuzzy [searchFirstGameId] title search is never evaluated — Kotlin's `?:` is short-circuit,
     * so the search call is skipped by language guarantee, not merely by convention. Every other key
     * (absent from the map — the default is empty, so every pre-existing caller is unaffected) behaves
     * exactly as before: title search, then a `game/<sgdbId>` segment. There is no fallback from a
     * resolved app id back to title search on a miss — an id that yields zero assets yields no tile for
     * that fetch, which is the point of the ruling, not an oversight.
     *
     * [resolvedGamesByKey] carries a SteamGridDB game ALREADY resolved by the caller (reported routing case,
     * 2026-09-11: Library ENRICH for a `pc_games` row with no `.steam` shortcut resolves the game by name
     * once, in `ArtworkFetchController`, and feeds the SAME [SteamGridDbGameRef] to every asset fetch).
     * Second rung of the same short-circuit ladder: never evaluated for a key with an app id, and a key
     * in this map never reaches [searchFirstGameId] — one identification per Enrich press, not one per
     * asset kind. Every pre-existing caller leaves it empty and is unaffected.
     */
    suspend fun fetchMissing(
        context: Context,
        apiKey: String,
        titlesByKey: Map<String, String>,
        steamAppIdsByKey: Map<String, Int> = emptyMap(),
        resolvedGamesByKey: Map<String, SteamGridDbGameRef> = emptyMap(),
    ): Int = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !MimirSteamGridDbGate.isOpen) return@withContext 0
        // FUX-08: v2 cache — the style filter changed (art-only), so v1 picks refetch once.
        File(context.filesDir, "sgdb").deleteRecursively()
        val dir = File(context.filesDir, "sgdb2").apply { mkdirs() }
        var fetched = 0
        titlesByKey.forEach { (cacheKey, title) ->
            val wrote = GameIdentityMediaMutationGate.run(context, blocked = false) {
                val target = File(dir, fileNameFor(cacheKey))
                if (!automaticTileFetchMayInstall(
                        targetExists = target.isFile,
                        selection = GameIdentityMediaSelectionStore.record(
                            context,
                            EnrichMediaKind.ICON,
                            cacheKey,
                        ).selection,
                    )
                ) {
                    return@run false
                }
                try {
                    val urlSegment = steamGridDbUrlSegmentFromAppId(cacheKey, steamAppIdsByKey)
                        ?: resolvedGamesByKey[cacheKey]?.urlSegment
                        ?: searchFirstGameId(apiKey, title)?.let { "game/$it" }
                    if (urlSegment != null) {
                        delay(PoliteDelayMs)
                        val candidates = tileIconUrls(apiKey, urlSegment)
                        if (candidates.isNotEmpty()) {
                            // FUX-09 REROLL: the sidecar remembers which candidate this tile used. Deleting a
                            // tile's png (dislike) advances the pick to the NEXT candidate on the next fetch.
                            val idxFile = File(dir, fileNameFor(cacheKey) + ".idx")
                            val pick = if (idxFile.isFile) {
                                (idxFile.readText().trim().toIntOrNull() ?: -1) + 1
                            } else {
                                0
                            }
                            delay(PoliteDelayMs)
                            val downloaded = download(
                                candidates[pick % candidates.size],
                                target,
                                CustomHeroSource.STEAMGRIDDB,
                            )
                            if (downloaded && decodableStill(target)) {
                                idxFile.writeText(pick.toString())
                                true
                            } else {
                                if (downloaded) target.delete()
                                false
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // One provider miss must not abort the remaining explicit batch.
                    false
                }
            }
            if (wrote) fetched++
            delay(PoliteDelayMs)
        }
        // Never on a no-op call: an idle poll that skipped every already-cached key must not re-map the library.
        if (fetched > 0) publishTileWrite(context)
        fetched
    }

    /**
     * Portrait companion of [fetchMissing] for games whose SteamGridDB identity the caller has ALREADY
     * resolved (the Library cover slot is portrait; a square SteamGridDB grid
     * there leaves visible gaps around the focus ring).
     * Each [SteamGridDbGameRef] supplies the id-type URL segment (`steam/<appid>` for a `.steam`
     * shortcut's exact id; `game/<sgdbId>` for a name-resolved game) against
     * the SAME already-admitted `grids/<segment>` route [fetchMissing] uses for the square lane, with only
     * the `dimensions=` query parameter swapped to [SteamGridDbPortraitDimensionsQuery]; no new host, no
     * allowlist change. There is no title search in here: resolving WHICH game is the caller's one
     * decision (`ArtworkFetchController`'s `pcArtworkLookupPlan`), never re-derived per asset kind.
     *
     * Writes to [canonicalPortraitTileIconFile], a file DISTINCT from [fetchMissing]'s
     * [canonicalTileIconFile] -- the two coexist on disk for one game so the shelf-face resolver can
     * prefer the portrait asset for a game with no cover while `HomeShelfCoordinator`'s square Home-shelf
     * tile is completely unaffected; it never reads this file. Calls [publishTileWrite] on a genuine write
     * under the same `fetched > 0` gating [fetchMissing] itself uses, so PM gate DEFECT 1's repaint
     * mechanism also covers a portrait-only write.
     */
    suspend fun fetchMissingPortrait(
        context: Context,
        apiKey: String,
        gamesByKey: Map<String, SteamGridDbGameRef>,
    ): Int = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !MimirSteamGridDbGate.isOpen) return@withContext 0
        val dir = File(context.filesDir, "sgdb2").apply { mkdirs() }
        var fetched = 0
        gamesByKey.forEach { (cacheKey, game) ->
            val target = File(dir, portraitFileNameFor(cacheKey))
            if (target.isFile) return@forEach
            try {
                delay(PoliteDelayMs)
                val body = getJson(steamGridDbPortraitGridQueryUrl(game.urlSegment), apiKey)
                val candidateUrl = body
                    ?.let { parseSquareGridCandidates(it, SteamGridDbAssetKind.GRID) }
                    ?.firstOrNull()
                    ?.url
                if (candidateUrl != null) {
                    delay(PoliteDelayMs)
                    val downloaded = download(candidateUrl, target, CustomHeroSource.STEAMGRIDDB)
                    if (downloaded && decodableStill(target)) {
                        fetched++
                    } else if (downloaded) {
                        target.delete()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One provider miss must not abort the remaining batch.
            }
            delay(PoliteDelayMs)
        }
        // Same gating as fetchMissing: never publish on a no-op call.
        if (fetched > 0) publishTileWrite(context)
        fetched
    }

    private fun fileNameFor(cacheKey: String): String =
        cacheKey.encodeToByteArray().joinToString("") { "%02x".format(it) } + ".png"

    /**
     * Portrait companion of [fileNameFor] -- reuses its EXACT hex derivation of [cacheKey] (never a
     * second hashing scheme) with a `-portrait` marker before the extension, so the square and portrait
     * assets for one game coexist as two distinct files under the same hex prefix.
     */
    private fun portraitFileNameFor(cacheKey: String): String =
        fileNameFor(cacheKey).removeSuffix(".png") + "-portrait.png"

    private fun pickIndexFile(context: Context, cacheKey: String): File =
        File(canonicalTileIconFile(context, cacheKey).parentFile, fileNameFor(cacheKey) + ".idx")

    private fun searchFirstGameId(apiKey: String, title: String): Long? {
        //  the resolver's identity, not the file label — try the cleaned display title,
        // then progressively strip edition/variant suffixes ("Resident Evil 2 - DualShock" →
        // "Resident Evil 2"). Real subtitle dashes still win because the FULL title is tried FIRST.
        sgdbSearchCandidates(title).forEach { candidate ->
            val encoded = URLEncoder.encode(candidate, "UTF-8")
            val body = getJson("$Api/search/autocomplete/$encoded", apiKey)
            val data = body?.optJSONArray("data") ?: return@forEach
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val resultTitle = item.optString("name")
                if (id > 0L && sgdbTitleMatches(title, resultTitle)) return id
            }
        }
        return null
    }

    private fun searchFirstGameOrThrow(apiKey: String, titles: List<String>): GameMatch? {
        titles.map(String::trim).filter(String::isNotBlank).distinct().forEach { title ->
            firstMatchingGameOrThrow(apiKey, title, sgdbSearchCandidates(title))?.let { return it }
        }
        return null
    }

    /**
     * One identity, several ways to ask for it: every autocomplete hit for any of [queries] must match
     * [title] ITSELF through [sgdbTitleMatches] before it is accepted -- a stripped angle ("Castlevania"
     * for "Castlevania - Symphony of the Night") only widens the search, it never becomes a second
     * identity a bare-title hit could satisfy. Throws on transport/provider failure so callers can tell
     * a miss from a failure. Shared by the Home picker's [searchFirstGameOrThrow] and the Library's
     * [searchGameByTitle] so both surfaces resolve a name the same way.
     */
    private fun firstMatchingGameOrThrow(apiKey: String, title: String, queries: List<String>): GameMatch? {
        queries.forEach { query ->
            val encoded = URLEncoder.encode(query, "UTF-8")
            val data = getJsonOrThrow("$Api/search/autocomplete/$encoded", apiKey).optJSONArray("data")
                ?: return@forEach
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val id = item.optLong("id")
                val resultTitle = item.optString("name")
                if (id > 0L && sgdbTitleMatches(title, resultTitle)) return GameMatch(id, resultTitle)
            }
        }
        return null
    }

    /** The top [perTitle] SGB entry ids for one search angle (score-ordered autocomplete) — the multi-angle base. */
    private fun searchTopGameIds(apiKey: String, title: String, perTitle: Int): List<Long> {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val data = getJson("$Api/search/autocomplete/$encoded", apiKey)?.optJSONArray("data") ?: return emptyList()
        val ids = mutableListOf<Long>()
        var i = 0
        while (i < data.length() && ids.size < perTitle) {
            data.getJSONObject(i).optLong("id").takeIf { it > 0L }?.let { ids.add(it) }
            i++
        }
        return ids
    }

    private fun tileIconUrls(apiKey: String, urlSegment: String): List<String> =
        tileIconCandidates(apiKey, urlSegment).map { it.url }

    /**
     * SteamGridDB exposes both square grids and a purpose-built icon collection. Mimir accepts only
     * 512/1024 square classes; merging both official endpoints expands sparse
     * platform entries (notably 3DO) without admitting rectangular covers or tiny launcher icons.
     *
     * [urlSegment] is the SteamGridDB id-type-keyed target — `game/<sgdbId>` from a title search, or
     * `steam/<appid>` for an exact Steam identity (mirrors [CustomHeroFetcher]'s
     * `steam/$steamAppId` lookup target for heroes).
     */
    private fun tileIconCandidates(apiKey: String, urlSegment: String): List<Candidate> =
        (squareGridCandidates(apiKey, urlSegment) + providerIconCandidates(apiKey, urlSegment))
            .distinctBy(Candidate::url)

    private fun tileIconCandidatesOrThrow(apiKey: String, urlSegment: String): List<Candidate> =
        (squareGridCandidatesOrThrow(apiKey, urlSegment) + providerIconCandidates(apiKey, urlSegment))
            .distinctBy(Candidate::url)

    private fun squareGridCandidates(apiKey: String, urlSegment: String): List<Candidate> {
        //  ART, not box scans — filter to the key-art styles (no white_logo, no
        // blurred, static only, no nsfw/humor) and take the community's highest-scored grid.
        val body = getJson(steamGridDbSquareGridQueryUrl(urlSegment), apiKey) ?: return emptyList()
        return parseSquareGridCandidates(body, SteamGridDbAssetKind.GRID)
    }

    private fun squareGridCandidatesOrThrow(apiKey: String, urlSegment: String): List<Candidate> =
        parseSquareGridCandidates(
            getJsonOrThrow(steamGridDbSquareGridQueryUrl(urlSegment), apiKey),
            SteamGridDbAssetKind.GRID,
        )

    private fun providerIconCandidates(apiKey: String, urlSegment: String): List<Candidate> {
        val body = getJson(steamGridDbIconQueryUrl(urlSegment), apiKey) ?: return emptyList()
        return parseSquareGridCandidates(body, SteamGridDbAssetKind.ICON)
    }

    private fun parseSquareGridCandidates(
        body: JSONObject,
        assetKind: SteamGridDbAssetKind = SteamGridDbAssetKind.GRID,
    ): List<Candidate> {
        val data = body.optJSONArray("data") ?: return emptyList()
        // Score-desc, then PNG before JPG — the auto pick, the reroll, AND the menu walk this order.
        data class Entry(
            val id: String?,
            val url: String,
            val thumb: String,
            val score: Long,
            val png: Boolean,
            val width: Int,
            val height: Int,
            val style: String?,
        )
        return (0 until data.length()).mapNotNull { i ->
            val item = data.getJSONObject(i)
            val url = item.optString("url")
            if (!steamGridDbMediaUrlAllowed(url)) return@mapNotNull null
            val thumb = item.optString("thumb").takeIf(::steamGridDbMediaUrlAllowed) ?: url
            Entry(
                id = item.optLong("id").takeIf { it > 0L }?.toString(),
                url = url,
                thumb = thumb,
                score = item.optLong("score"),
                png = url.substringBefore('?').endsWith(".png", ignoreCase = true),
                width = item.optInt("width"),
                height = item.optInt("height"),
                style = item.optString("style").takeIf(String::isNotBlank),
            )
        }.sortedWith(compareByDescending<Entry> { it.score }.thenByDescending { it.png })
            .map {
                Candidate(
                    url = it.url,
                    thumbUrl = it.thumb,
                    providerAssetId = it.id,
                    width = it.width,
                    height = it.height,
                    style = it.style,
                    assetKind = assetKind,
                    providerMediaType = if (assetKind == SteamGridDbAssetKind.GRID) {
                        steamGridDbGridMediaType(it.width, it.height)
                    } else {
                        null
                    },
                )
            }
    }

    private fun steamGridDbResponseHasMore(body: JSONObject, requestedPage: Int, requestedLimit: Int): Boolean {
        val dataSize = body.optJSONArray("data")?.length() ?: 0
        val meta = body.optJSONObject("meta")
        val total = meta?.optInt("total", -1)?.takeIf { it >= 0 }
        val page = meta?.optInt("page", requestedPage)?.takeIf { it >= 0 } ?: requestedPage
        val limit = meta?.optInt("limit", requestedLimit)?.takeIf { it > 0 } ?: requestedLimit
        return steamGridDbPageHasMore(total, page, limit, dataSize)
    }

    private fun manualSearchGamesOrThrow(apiKey: String, query: String, limit: Int): List<GameMatch> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val data = getJsonOrThrow("$Api/search/autocomplete/$encoded", apiKey).optJSONArray("data")
            ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.getJSONObject(index)
            val id = item.optLong("id")
            val title = item.optString("name")
            if (id > 0L && title.isNotBlank()) GameMatch(id, title) else null
        }.take(limit)
    }

    private fun manualGridCandidatesOrThrow(
        apiKey: String,
        gameId: Long,
        style: String,
        size: String,
        page: Int,
        limit: Int,
        portrait: Boolean = false,
    ): SteamGridDbCandidatePage {
        val dimensions = when {
            portrait -> SteamGridDbPortraitDimensionsQuery
            size == "512" -> "512x512"
            size == "1024" -> "1024x1024"
            else -> SteamGridDbSquareDimensionsQuery
        }
        val styles = when (style) {
            "Material" -> "material"
            "Alternate" -> "alternate"
            "All" -> "alternate,material,no_logo"
            else -> "no_logo"
        }
        val body = getJsonOrThrow(
                "$Api/grids/game/$gameId?dimensions=$dimensions&styles=$styles" +
                    "&types=static&nsfw=false&humor=false&limit=$limit&page=$page",
                apiKey,
        )
        return SteamGridDbCandidatePage(
            candidates = parseSquareGridCandidates(body, SteamGridDbAssetKind.GRID),
            hasMore = steamGridDbResponseHasMore(body, page, limit),
        )
    }

    private fun manualIconCandidatesOrThrow(
        apiKey: String,
        gameId: Long,
        size: String,
        page: Int,
        limit: Int,
    ): SteamGridDbCandidatePage {
        val dimensions = when (size) {
            "512" -> "512"
            "1024" -> "1024"
            else -> SteamGridDbIconDimensionsQuery
        }
        val body = getJsonOrThrow(
                "$Api/icons/game/$gameId?dimensions=$dimensions&styles=official,custom" +
                    "&mimes=image/png&types=static&nsfw=false&humor=false&limit=$limit&page=$page",
                apiKey,
        )
        return SteamGridDbCandidatePage(
            candidates = parseSquareGridCandidates(body, SteamGridDbAssetKind.ICON),
            hasMore = steamGridDbResponseHasMore(body, page, limit),
        )
    }

    private fun getJsonOrThrow(url: String, apiKey: String): JSONObject {
        var current = url
        var redirects = 0
        while (redirects <= MaxRedirects) {
            val response = MimirProviderEgress.open(
                route = MimirProviderEgressRoute.STEAMGRIDDB_API,
                url = current,
                connectTimeoutMs = ConnectTimeoutMs,
                readTimeoutMs = ConnectTimeoutMs,
                requestHeaders = mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer $apiKey",
                ),
            ) ?: throw IOException("SteamGridDB API authority rejected")
            try {
                val responseCode = response.statusCode
                if (responseCode in CustomHeroRedirectStatusCodes) {
                    if (redirects == MaxRedirects) throw IOException("SteamGridDB API redirect limit")
                    current = steamGridDbApiRedirectTarget(
                        currentUrl = current,
                        location = response.header("Location"),
                    ) ?: throw IOException("SteamGridDB API redirect rejected")
                    redirects++
                    continue
                }
                if (responseCode != 200) throw SteamGridDbHttpException(responseCode)
                if (!steamGridDbContentLengthAllowed(response.declaredLength, MaxJsonBytes)) {
                    throw IOException("SteamGridDB API response exceeded the safe limit")
                }
                val body = readCustomHeroUtf8Bounded(response.body(), MaxJsonBytes)
                    ?: throw IOException("SteamGridDB API response exceeded the safe limit")
                return JSONObject(body)
            } finally {
                response.close()
            }
        }
        throw IOException("SteamGridDB API redirect limit")
    }

    private fun getJson(url: String, apiKey: String): JSONObject? = try {
        getJsonOrThrow(url, apiKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun downloadSavedSource(
        url: String,
        target: File,
        savedSource: EnrichSavedSource,
        maxBytes: Long = MaxProviderMediaBytes,
    ): Boolean {
        val source = savedSource.toCustomHeroSource() ?: return false
        return download(url, target, source, maxBytes)
    }

    private fun download(
        url: String,
        target: File,
        source: CustomHeroSource,
        maxBytes: Long = MaxProviderMediaBytes,
    ): Boolean {
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
                val response = MimirProviderEgress.open(
                    route = expectedRoute,
                    url = current,
                    connectTimeoutMs = ConnectTimeoutMs,
                    readTimeoutMs = ReadTimeoutMs,
                ) ?: return false
                try {
                    val responseCode = response.statusCode
                    if (responseCode in CustomHeroRedirectStatusCodes) {
                        if (redirects == MaxRedirects) return false
                        current = customHeroRedirectTarget(
                            source = source,
                            currentUrl = current,
                            location = response.header("Location"),
                        ) ?: return false
                        redirects++
                        continue
                    }
                    if (responseCode != 200 ||
                        !steamGridDbContentLengthAllowed(response.declaredLength, maxBytes)
                    ) {
                        return false
                    }
                    val written = FileOutputStream(staged).use { output ->
                        val copied = response.body().use { input ->
                            copySteamGridDbResponseBounded(input, output, maxBytes)
                        }
                        if (copied != null) output.fd.sync()
                        copied
                    } ?: return false
                    if (written <= 0L) return false
                    return commitSteamGridDbDownloadedFile(staged, target)
                } finally {
                    response.close()
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

    private fun normalizeSquareStill(source: File, target: File, edgePx: Int): Boolean {
        if (edgePx <= 0 || !source.isFile || source.length() <= 0L) return false
        val decoded = BitmapFactory.decodeFile(source.absolutePath) ?: return false
        val square = Bitmap.createBitmap(edgePx, edgePx, Bitmap.Config.ARGB_8888)
        val normalized = File(target.parentFile, target.name + ".normalized.part")
        normalized.delete()
        return try {
            val scale = minOf(edgePx.toFloat() / decoded.width, edgePx.toFloat() / decoded.height)
            val width = decoded.width * scale
            val height = decoded.height * scale
            val destination = RectF(
                (edgePx - width) / 2f,
                (edgePx - height) / 2f,
                (edgePx + width) / 2f,
                (edgePx + height) / 2f,
            )
            Canvas(square).drawBitmap(
                decoded,
                null,
                destination,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val compressed = FileOutputStream(normalized).use { output ->
                square.compress(Bitmap.CompressFormat.PNG, 100, output).also { complete ->
                    if (complete) output.fd.sync()
                }
            }
            if (!compressed || !decodableStill(normalized)) {
                normalized.delete()
                false
            } else {
                installHeroAsset(
                    temp = normalized,
                    target = target,
                    validate = ::decodableStill,
                    commit = { true },
                ) == HeroInstallOutcome.INSTALLED
            }
        } catch (_: Exception) {
            false
        } finally {
            normalized.delete()
            decoded.recycle()
            square.recycle()
        }
    }

    private fun decodableStill(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }
}

private class SteamGridDbHttpException(val code: Int) : IOException("SteamGridDB response $code")

internal fun steamGridDbPageHasMore(
    total: Int?,
    page: Int,
    limit: Int,
    loaded: Int,
): Boolean = when {
    limit <= 0 || loaded <= 0 -> false
    total != null -> (page + 1L) * limit < total
    else -> loaded >= limit
}

internal fun roundRobinDistinctCandidates(
    groups: List<List<SteamGridDbTileFetcher.Candidate>>,
    limit: Int,
): List<SteamGridDbTileFetcher.Candidate> {
    if (limit <= 0 || groups.isEmpty()) return emptyList()
    val cursors = IntArray(groups.size)
    val seen = linkedSetOf<String>()
    val result = mutableListOf<SteamGridDbTileFetcher.Candidate>()
    var advanced: Boolean
    do {
        advanced = false
        groups.forEachIndexed { groupIndex, group ->
            while (cursors[groupIndex] < group.size) {
                val candidate = group[cursors[groupIndex]++]
                if (seen.add(candidate.url)) {
                    result += candidate.copy(resolvedTitle = null)
                    advanced = true
                    break
                }
            }
            if (result.size >= limit) return result
        }
    } while (advanced)
    return result
}

internal enum class SteamGridDbAssetKind { GRID, ICON }

internal fun steamGridDbApiUrlAllowed(url: String): Boolean {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    if (!parsed.scheme.equals("https", ignoreCase = true) ||
        parsed.host?.lowercase() != "www.steamgriddb.com" ||
        parsed.rawUserInfo != null ||
        parsed.port !in setOf(-1, 443) ||
        parsed.rawFragment != null
    ) {
        return false
    }
    val rawPath = parsed.rawPath ?: return false
    if (rawPath.contains('\\') ||
        rawPath.contains("//") ||
        rawPath.contains(';')
    ) {
        return false
    }
    if (SteamGridDbExactAssetApiPath.matches(rawPath)) return true
    if (!rawPath.startsWith(SteamGridDbAutocompleteApiPrefix)) return false
    val encodedQuery = rawPath.removePrefix(SteamGridDbAutocompleteApiPrefix)
    return steamGridDbCanonicalAutocompleteQueryAllowed(encodedQuery)
}

internal fun steamGridDbApiRedirectTarget(currentUrl: String, location: String?): String? {
    val raw = location?.takeIf(String::isNotBlank) ?: return null
    val resolved = runCatching {
        val current = URI(currentUrl)
        if (raw.startsWith("?")) {
            currentUrl.substringBefore('?').substringBefore('#') + raw
        } else {
            current.resolve(raw).toString()
        }
    }.getOrNull() ?: return null
    return resolved.takeIf(::steamGridDbApiUrlAllowed)
}

private const val SteamGridDbAutocompleteApiPrefix = "/api/v2/search/autocomplete/"
private val SteamGridDbExactAssetApiPath =
    // `steam` alongside `game`: R-23  looks heroes up by the
    // exact Steam app id parsed from a `.steam` shortcut's body, hitting SteamGridDB's own
    // id-type-keyed asset routes (e.g. /heroes/steam/{appid}) instead of the fuzzy autocomplete ladder.
    Regex("^/api/v2/(?:grids|heroes|icons)/(?:game|steam)/[1-9][0-9]*$")

private fun steamGridDbCanonicalAutocompleteQueryAllowed(encodedQuery: String): Boolean {
    if (encodedQuery.isBlank() || encodedQuery.contains('/')) return false
    val decoded = runCatching { URLDecoder.decode(encodedQuery, "UTF-8") }.getOrNull()
        ?: return false
    if (decoded.isBlank() || URLEncoder.encode(decoded, "UTF-8") != encodedQuery) return false

    var traversalCandidate = decoded
    repeat(4) {
        if (traversalCandidate.split('/', '\\').any { it == "." || it == ".." }) return false
        val decodedAgain = runCatching {
            URLDecoder.decode(traversalCandidate.replace("+", "%2B"), "UTF-8")
        }.getOrNull() ?: return true
        if (decodedAgain == traversalCandidate) return true
        traversalCandidate = decodedAgain
    }
    return traversalCandidate.split('/', '\\').none { it == "." || it == ".." }
}

internal fun steamGridDbMediaUrlAllowed(url: String): Boolean =
    customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, url)

internal fun customEnrichMediaUrlAllowed(url: String): Boolean =
    CustomHeroSource.entries.any { source -> customHeroUrlAllowed(source, url) }

internal fun steamGridDbContentLengthAllowed(reportedBytes: Long, maxBytes: Long): Boolean =
    maxBytes > 0L && (reportedBytes == -1L || reportedBytes in 1..maxBytes)

internal fun copySteamGridDbResponseBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
): Long? {
    if (maxBytes <= 0L) return null
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return total
}

internal fun commitSteamGridDbDownloadedFile(
    staged: File,
    target: File,
    move: (File, File) -> Boolean = { from, to ->
        runCatching {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrDefault(false)
    },
): Boolean {
    if (!staged.isFile || staged.length() <= 0L) {
        staged.delete()
        return false
    }
    target.parentFile?.mkdirs()
    val moved = runCatching { move(staged, target) }.getOrDefault(false)
    if (!moved) staged.delete()
    return moved && target.isFile && target.length() > 0L
}

internal const val SteamGridDbApiBase = "https://www.steamgriddb.com/api/v2"
internal const val SteamGridDbSquareDimensionsQuery = "512x512,1024x1024"
internal const val SteamGridDbIconDimensionsQuery = "512,1024"
/**
 * SteamGridDB's vertical-capsule grid class (the Library cover slot is
 * portrait, `GamelistCarousel.kt`'s `portraitArtW`/`portraitArtH`). UNKNOWN/OWED: whether SteamGridDB
 * serves this exact dimension for every title is a runtime, provider-side fact this offline change
 * cannot verify -- [fetchMissingPortrait] already treats a zero-candidate response as a
 * normal miss (same as the square lane), so a title with no qualifying 600x900 grid degrades to
 * the shelf-face resolver's documented square fallback rather than failing loudly.
 */
internal const val SteamGridDbPortraitDimensionsQuery = "600x900"

/**
 * The provider media types this product records for SteamGridDB grids.
 *
 * **Derived from the asset's own dimensions, never from what we asked for.** A request for portrait can
 * come back with whatever the provider has; recording the REQUEST would put a claim in his saved catalog
 * that the file does not support. `providerMediaType` exists so apply can tell which KIND of face he
 * chose, and a wrong one paints a square asset in a portrait lane — right picture, wrong shape.
 *
 * Deliberately NOT ScreenScraper's vocabulary. Writing `box-2d` for a SteamGridDB grid would make the
 * record lie about its own provenance to satisfy a prefix test; [wiredFaceKindOf] learns these instead.
 */
internal const val SteamGridDbPortraitGridType = "sgdb-grid-portrait"
internal const val SteamGridDbSquareGridType = "sgdb-grid-square"

/** Portrait when the asset is taller than it is wide; square otherwise. Null when the provider gave no size. */
internal fun steamGridDbGridMediaType(width: Int, height: Int): String? = when {
    width <= 0 || height <= 0 -> null
    height > width -> SteamGridDbPortraitGridType
    else -> SteamGridDbSquareGridType
}
private const val MaxScreenScraperPlatformCandidates = 4

/**
 * SteamGridDB square-grid query for [urlSegment] (`game/<sgdbId>` or `steam/<appid>`). Pulled out to a
 * pure top-level function — this file's established pattern for unit-testable pieces (see
 * [sgdbSearchCandidates], [steamGridDbApiUrlAllowed]) — so the exact query string [SteamGridDbTileFetcher]
 * sends can be pinned without a network call. Every query parameter (dimensions, styles, types, nsfw,
 * humor) is unchanged from the pre-2026-08-19 `game/<sgdbId>`-only shape.
 */
internal fun steamGridDbSquareGridQueryUrl(urlSegment: String): String =
    "$SteamGridDbApiBase/grids/$urlSegment" +
        "?dimensions=$SteamGridDbSquareDimensionsQuery&styles=alternate,material,no_logo" +
        "&types=static&nsfw=false&humor=false"

/** Icon-collection counterpart of [steamGridDbSquareGridQueryUrl]; same [urlSegment] contract. */
internal fun steamGridDbIconQueryUrl(urlSegment: String): String =
    "$SteamGridDbApiBase/icons/$urlSegment" +
        "?dimensions=$SteamGridDbIconDimensionsQuery&styles=official,custom" +
        "&mimes=image/png&types=static&nsfw=false&humor=false"

/**
 * Portrait counterpart of [steamGridDbSquareGridQueryUrl] -- byte-identical shape and the SAME
 * already-admitted `grids/<urlSegment>` route ([SteamGridDbExactAssetApiPath]/
 * [steamGridDbCanonicalAutocompleteQueryAllowed] match on `rawPath` only, so a query-string-only change
 * needs no allowlist edit), with only [SteamGridDbPortraitDimensionsQuery] swapped in for the
 * `dimensions=` parameter.
 */
internal fun steamGridDbPortraitGridQueryUrl(urlSegment: String): String =
    "$SteamGridDbApiBase/grids/$urlSegment" +
        "?dimensions=$SteamGridDbPortraitDimensionsQuery&styles=alternate,material,no_logo" +
        "&types=static&nsfw=false&humor=false"

/**
 * The exact-identity `steam/<appid>` URL segment for [cacheKey], when [steamAppIdsByKey] carries one —
 * policy 2026-08-19, extending R-23's exact-id precedent ( * [CustomHeroFetcher.steamGridDbHeroesForSteamAppId]) to the automatic Home-shelf tile fetch. Null means
 * "no known app id for this key" — the caller falls back to the title-search `game/<sgdbId>` route, never
 * the other way around: an id that resolves to zero assets does not retry by title.
 */
internal fun steamGridDbUrlSegmentFromAppId(cacheKey: String, steamAppIdsByKey: Map<String, Int>): String? =
    steamAppIdsByKey[cacheKey]?.let { SteamGridDbGameRef.SteamApp(it).urlSegment }

/**
 * One resolved SteamGridDB game, as the id-type-keyed URL segment every asset route accepts
 * (`grids/<segment>`, `heroes/<segment>`, `icons/<segment>` -- both shapes are already admitted by
 * [SteamGridDbExactAssetApiPath]). Exactly two ways a game gets resolved, so exactly two shapes:
 * [SteamApp] is R-23's exact identity from a `.steam` shortcut's body; [Game] is SteamGridDB's own entry
 * id from a name search ([SteamGridDbTileFetcher.searchGameByTitle]). Whoever resolves the game hands
 * this ONE value to every asset fetch, so the portrait grid, square grid and hero can never disagree
 * about which game they belong to .
 */
internal sealed interface SteamGridDbGameRef {
    val urlSegment: String

    data class SteamApp(val appId: Int) : SteamGridDbGameRef {
        override val urlSegment: String get() = "steam/$appId"
    }

    data class Game(val sgdbId: Long) : SteamGridDbGameRef {
        override val urlSegment: String get() = "game/$sgdbId"
    }
}
