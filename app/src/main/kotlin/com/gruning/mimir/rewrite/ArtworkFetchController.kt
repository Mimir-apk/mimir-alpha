package com.gruning.mimir.rewrite

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.gruning.mimir.rewrite.core.metadata.ContentHash
import com.gruning.mimir.rewrite.core.metadata.MetadataOrigin
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.metadata.MetadataRecord
import com.gruning.mimir.rewrite.core.metadata.online.ConnectionAbort
import com.gruning.mimir.rewrite.core.metadata.online.IgdbMetadataSource
import com.gruning.mimir.rewrite.core.metadata.online.OnlineProviderPlatformProjection
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperLookupFailure
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataCache
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource
import com.gruning.mimir.rewrite.core.metadata.online.VerifiedMetadataCache
import com.gruning.mimir.rewrite.core.metadata.online.runAbortingOnCancel
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.ChangedMedia
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import com.gruning.mimir.rewrite.core.resolver.library.LibraryStore
import com.gruning.mimir.rewrite.core.resolver.library.LocalArtSource
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Translate the scan/enricher's durable hash syntax into ScreenScraper's typed fingerprint query. */
internal fun contentHashFromStored(stored: String?, sizeBytes: Long): ContentHash? {
    val normalized = stored?.trim()?.lowercase() ?: return null
    val rawSha1 = normalized.removePrefix("raw-sha1:")
        .takeIf { normalized.startsWith("raw-sha1:") }
    val hash = rawSha1 ?: normalized
    if (!hash.matches(Regex("^[0-9a-f]+$"))) return null
    return when {
        rawSha1 != null && hash.length == 40 -> ContentHash(sha1 = hash, sizeBytes = sizeBytes)
        rawSha1 != null -> null
        hash.length == 8 -> ContentHash(crc32 = hash, sizeBytes = sizeBytes)
        hash.length == 32 -> ContentHash(md5 = hash, sizeBytes = sizeBytes)
        hash.length == 40 -> ContentHash(sha1 = hash, sizeBytes = sizeBytes)
        else -> null
    }
}

internal val ConnectedArtRedirectStatusCodes = setOf(301, 302, 303, 307, 308)

internal fun connectedArtMediaUrlAllowed(url: String): Boolean {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    return parsed.scheme.equals("https", ignoreCase = true) &&
        parsed.rawUserInfo == null &&
        parsed.port in setOf(-1, 443) &&
        parsed.rawFragment == null &&
        MimirConnectedArtGate.isAllowedHost(parsed.host)
}

internal fun connectedArtMediaRedirectTarget(currentUrl: String, location: String?): String? {
    val raw = location?.takeIf(String::isNotBlank) ?: return null
    val resolved = runCatching {
        val current = URI(currentUrl)
        if (raw.startsWith("?")) {
            currentUrl.substringBefore('?').substringBefore('#') + raw
        } else {
            current.resolve(raw).toString()
        }
    }.getOrNull() ?: return null
    return resolved.takeIf(::connectedArtMediaUrlAllowed)
}

internal fun connectedArtContentLengthAllowed(reportedBytes: Long, maxBytes: Long): Boolean =
    maxBytes > 0L && (reportedBytes == -1L || reportedBytes in 1..maxBytes)

/**
 * Drops games whose platform has no metadata-provider coverage before they enter a bounded batch walk.
 *
 * Coverage-derived, not platform-literal: this asks [OnlineProviderPlatformProjection.screenScraperSystemId]
 * the same question [ScreenScraperMetadataSource.lookupOutcome] asks per-game (`ScreenScraperMetadataSource.kt:67-69`)
 * — whether the platform maps to a ScreenScraper system id — instead of naming a platform (e.g. `pc_games`)
 * directly. A platform with no id can only ever produce [ScreenScraperMetadataSource.LookupOutcome.PlatformUnsupported]:
 * no request is made, so nothing is learned about the game, and [ArtNoMatchStore] deliberately records no
 * negative for it (the `VerifiedLookupOutcome.PlatformUnsupported` branch inside `fetchArtworkForGame`, below).
 * Left unfiltered, every batch run re-pays the same local probes (`LocalArtSource`, [ArtResolutionStore],
 * [SafWritePreflight], a concurrency permit) for a game that structurally cannot match, and inflates the
 * progress overlay's `total` with games that can never move to `done` as a match.
 *
 * Filtering here — rather than caching a synthetic negative — means the filter opens on its own the moment
 * a platform gains a system id, exactly as xbox/xbox360/ps3 did on 2026-08-10, with no code change: this
 * function does not know `pc_games` exists, it only knows the coverage map's answer for whatever platform
 * id each row carries.
 */
internal fun gamesWithProviderCoverage(games: List<GameEntity>): List<GameEntity> =
    games.filter { OnlineProviderPlatformProjection.screenScraperSystemId(it.platformId) != null }

/**
 * Outcome of one Steam storefront `appdetails` call/parse (policy 2026-08-19). Deliberately three-valued, not a nullable
 * String: [NoDescription] (the endpoint answered `success:false`, or answered `success:true` with no
 * usable `short_description`) is an HONEST empty and must never be reported or treated as a provider
 * failure — conflating those two was the exact defect class the earlier update clip regression was about, and U4's
 * device call confirmed an unknown/delisted app id is HTTP 200 with `success:false`, not a non-200 status.
 * [Unreachable] is reserved for transport/parse trouble (bad JSON, non-200, oversized body, no response).
 */
internal sealed interface SteamStoreDescriptionOutcome {
    data class Found(val shortDescription: String) : SteamStoreDescriptionOutcome
    data object NoDescription : SteamStoreDescriptionOutcome
    data object Unreachable : SteamStoreDescriptionOutcome
}

/**
 * Pure parse of one storefront `appdetails` response body for [appId] — no I/O, so this is unit-testable
 * without a fake transport. `short_description` is used verbatim: it is confirmed plain text (U3, called
 * against Blasphemous 2 on 2026-08-19), unlike `detailed_description`/`about_the_game` which carry HTML the
 * About block would render literally. The response is keyed by the app id as a STRING (observed U4).
 */
internal fun parseSteamStoreAppDetails(appId: Int, body: String): SteamStoreDescriptionOutcome {
    val root = try {
        JSONObject(body)
    } catch (_: Exception) {
        return SteamStoreDescriptionOutcome.Unreachable
    }
    // Absent entirely is an unexpected shape (never observed for a real appid, keyless or not) -- a
    // transport/shape oddity, not the documented `success:false` honest-empty.
    val entry = root.optJSONObject(appId.toString()) ?: return SteamStoreDescriptionOutcome.Unreachable
    if (!entry.optBoolean("success", false)) return SteamStoreDescriptionOutcome.NoDescription
    val data = entry.optJSONObject("data") ?: return SteamStoreDescriptionOutcome.NoDescription
    val shortDescription = data.optString("short_description", "").takeIf(String::isNotBlank)
        ?: return SteamStoreDescriptionOutcome.NoDescription
    return SteamStoreDescriptionOutcome.Found(shortDescription)
}

/**
 * The opt-in artwork fetch. Cover art is stored as `media_art/<base>.png`; clear logos and Hero backdrops are
 * stored as `media_art/<base>-logo.png` / `<base>-hero.png`. The managed path is distinct from user-owned ROM
 * siblings and normally create-only. One verified legacy-repair path replaces unattributed art only when
 * the downloaded bytes differ from the local image. Every network step uses the existing
 * [MimirConnectedArtGate] and ScreenScraper host allowlist. The ROM itself is never written, moved, or replaced.
 *
 * A successful write invalidates only that game's memoized probes and version-busts its local render URIs,
 * so the open Library/Home projections repaint without a relaunch or rescan.
 */
object ArtworkFetchController {

    data class Progress(val done: Int, val total: Int, val matched: Int)

    /**
     * [writeBlocked] is the batch-level "storage said no" signal: nothing could be saved because the
     * library folder's access is read-only (or sibling writes failed systemically), as opposed to an
     * honest "no art matched". The overlay uses it to route the user to re-choose the library folder.
     */
    data class FetchResult(
        val total: Int,
        val matched: Int,
        val writeBlocked: Boolean = false,
        /** The game-music download held the process gate, so nothing ran. Not a result: the caller closes the card. */
        val busy: Boolean = false,
    ) {
        val unmatched get() = total - matched
    }

    internal data class ArtworkWriteResult(
        val coverWritten: Boolean = false,
        val flatArtWritten: Boolean = false,
        val heroArtWritten: Boolean = false,
        val physicalMediaWritten: Boolean = false,
        val previewVideoWritten: Boolean = false,
        /** A verified ScreenScraper record was durably cached, even if no media file changed. */
        val metadataMatched: Boolean = false,
        /** True when the game was skipped up front because its SAF tree has no write access. */
        val writeBlocked: Boolean = false,
        /** The provider could not answer truthfully; this is not a durable no-match. */
        val lookupFailed: Boolean = false,
        /** Final current identities/locators that actually received bytes. Never the pre-network snapshot. */
        val changedMedia: List<ChangedMedia> = emptyList(),
    ) {
        /** Batch and per-game success count games, never the number of individual image files. */
        val wroteAny: Boolean get() = coverWritten || flatArtWritten || heroArtWritten || physicalMediaWritten ||
            previewVideoWritten
        val matchedAny: Boolean get() = wroteAny || metadataMatched
    }

    private data class VerifiedMetadata(
        val record: MetadataRecord,
        val cached: Boolean,
    )

    /**
     * Internal (not private) so [artworkSlotsToFetch] and its red test can be exercised directly.
     *
     * [AUDIO] is the launch sound : `<base>-audio.m4a`, kept from the game's clip. Under the
     * Settings > Music behavior NO Enrich asks for it: the first-run card, Settings Download and the
     * per-game Enrich fetch no sound, and [artworkSlotsToFetch] never returns this slot. Only the explicit game-music
     * download does ([GameMusicBatch]), and what remains here for the slot is its negative's key, its file type and its
     * provider fact. It is not [CLIP] and never becomes one: [CLIP] stays retired from every bulk path, and the sound
     * carries no picture.
     */
    internal enum class ManagedMediaSlot { COVER, LOGO, HERO, PHYSICAL, CLIP, AUDIO }

    internal data class FinalMediaWrite(
        val target: GameEntity? = null,
        val writeBlocked: Boolean = false,
        /** A current, writable destination existed but the bounded local install failed. */
        val writeFailed: Boolean = false,
    ) {
        val written: Boolean get() = target != null
    }

    /**
     * Resolves the install target inside the same transaction that commits the local write. Kept as a
     * tiny injected seam so a deterministic latch test can prove an A→B flip during network work lands
     * only on B.
     */
    internal suspend fun <T, R> withFinalArtworkTarget(
        runTransaction: suspend (block: suspend () -> R) -> R,
        resolveCurrent: suspend () -> T?,
        missing: R,
        commit: suspend (T) -> R,
    ): R = runTransaction {
        resolveCurrent()?.let { commit(it) } ?: missing
    }

    /** Outcome of a single-game artwork rescrape; existing names remain for caller compatibility. */
    sealed interface OneResult {
        /** A cover, clear logo, physical support image, or any combination was found and written. */
        data object Matched : OneResult
        /** No artwork matched, the game wasn't found, or both writes failed. */
        data object NoMatch : OneResult
        /**
         * Nothing was asked of any provider: the row has no exact provider key and no searchable title
         * (today: a `pc_games` row with no `.steam` shortcut and a blank or tag-only title). Distinct from
         * [NoMatch] so the card never claims a provider "had no art" for a lookup that never happened
         * .
         */
        data object NoTitle : OneResult
        /** Transport, authentication, quota, or provider failure — never presented as "not found". */
        data object Failed : OneResult
        /** The ScreenScraper gate is closed (not connected) — the caller should route to connect. */
        data object GateClosed : OneResult
        /**
         * The game-music download holds [ArtworkOperationGate], so no Enrich may run beside it. Nothing was asked of any
         * provider; the player can try again when the download ends.
         */
        data object Busy : OneResult
    }

    /**
     * Batch-completion visibility hook: managed media files never touch the Room DB, so no library flow
     * re-emits on its own — without this bump, freshly written covers/logos/clips stay invisible until an
     * app restart (RP5 Bifröst-backdrop repro 2026-07-17). Each batch calls this from a `finally` so a
     * CANCELLED batch's partial writes surface too (the refresh is non-suspend by contract). No-op when
     * nothing was written.
     *
     * PERF-C (2026-07-17): TARGETED — the loops record exactly which games a write landed for, so only
     * those locators' probes re-resolve and only those rows re-emit (one emission). The old library-wide
     * `refreshAllMedia()` re-walked SAF for every game — including artless games re-memoizing their miss
     * (~6-11 resolver queries × 3 media kinds × each) — the reported post-fetch resettlement churn.
     */
    internal fun publishWrittenMedia(context: Context, changed: List<ChangedMedia>) {
        val current = changed.distinctBy { media ->
            Triple(media.gameId, media.sourceDocumentUri, media.managedPath)
        }
        if (current.isNotEmpty()) LibraryStore.repository(context).refreshMediaFor(current)
    }

    private fun GameEntity.asChangedMedia() =
        ChangedMedia(GameId(gameId), sourceDocumentUri = sourceDocumentUri, managedPath = managedPath)

    /**
     * R6-P2-03 §9 instrumentation: counts only, never a URL or credential. Reset at the start of every
     * [fetchAllArtwork] batch; incremented at the verified lookup call ([verifiedLookupOutcome]) and at
     * the top of [downloadGated] — both are shared helpers other entry points (per-game Enrich, the flat-
     * art/preview-video batches) also call, so a count can include their concurrent work too; that is an
     * accepted imprecision for a counts-only instrument, not a per-call-scoped measurement.
     */
    private val lookups = AtomicInteger(0)
    private val downloads = AtomicInteger(0)

    /**
     * Fetch missing covers, clear logos, Hero backdrops, and physical cartridge/disc/card art for every owned game. Present
     * media is retained, and every missing slot shares one metadata lookup. [onProgress] and
     * [FetchResult.matched] count games, not files.
     */
    suspend fun fetchAllArtwork(context: Context, onProgress: (Progress) -> Unit = {}): FetchResult {
        // The process gate is claimed before ANYTHING else, the quota refresh included, and released on every way out
        // (cancellation too): an Enrich never runs beside the game-music download. See [ArtworkOperationGate].
        val claim = ArtworkOperationGate.process.tryClaimEnrich() ?: return FetchResult(0, 0, busy = true)
        try {
            return fetchAllArtworkClaimed(context, onProgress)
        } finally {
            claim.release()
        }
    }

    private suspend fun fetchAllArtworkClaimed(context: Context, onProgress: (Progress) -> Unit): FetchResult =
        withContext(Dispatchers.IO) {
            if (!MimirConnectedArtGate.isOpen) return@withContext FetchResult(0, 0)
            ArtworkConnectController.attach(context)
            ArtResolutionStore.attach(context)
            ArtNoMatchStore.attach(context)
            // Take the value the refresh just computed rather than re-reading the field it wrote: a
            // refresh that raced another connection operation returns the retained allowance, and the
            // re-read could still observe the null this used to leave behind.
            val accountMaxThreads = ArtworkConnectController.refreshAccountMaxThreads(context)
            val startedAt = SystemClock.elapsedRealtime()
            lookups.set(0)
            downloads.set(0)
            beginEnrichBatch(EnrichBatchDownload)

            val gameDao = MimirDatabaseFactory.get(context).gameDao()
            val localArt = LocalArtSource(context)
            // R6-P2-03 lever C: favourites, then most recently played, then the rest alphabetically,
            // hidden last — the games he looks at get art first, and an interrupted run resumes into an
            // already-mostly-done prefix. allPrimaryGames() stays untouched for its scan-path callers.
            val games = gamesWithProviderCoverage(gameDao.primaryGamesForEnrichment())
            // After the ScreenScraper pass, a separate serial
            // SteamGridDB pass over pc_games rows, run only when the gate is open and a key resolves.
            // Computed up front so total/progress can include both passes from the first callback.
            val pcApiKey = SteamGridDbKeyStore.resolvedKey(context)
            val pcGameIds = if (MimirSteamGridDbGate.isOpen && pcApiKey.isNotBlank()) {
                gameDao.primaryGamesWithoutScreenScraperMetadata(Int.MAX_VALUE)
                    // Same predicate allPrimaryGames() uses: an SD-out row is unreachable, so it must not
                    // spend a SteamGridDB search on a game the batch cannot fetch anything for anyway.
                    .filter { it.availability == "AVAILABLE" }
                    .filter { PlatformId(it.platformId) == PlatformId.PC_GAMES }
                    .map { it.gameId }
            } else {
                emptyList()
            }
            val total = games.size + pcGameIds.size
            onProgress(Progress(done = 0, total = total, matched = 0))
            val preflight = SafWritePreflight(context)
            // R6-P2-03 lever B: a provider-failure breaker, distinct from WriteFailureBreaker's other use
            // (write-layer failures in fetchAllPreviewVideos) — this one counts consecutive UNREACHABLE/
            // FAILED lookups so a dead network stops the batch within a few games instead of N timeouts.
            val providerBreaker = WriteFailureBreaker(limit = MaxConsecutiveProviderFailures)
            var matched = 0
            var writeSkipped = 0
            var done = 0
            val changed = mutableListOf<ChangedMedia>()
            runBoundedArtworkBatch(
                items = games,
                concurrency = artworkBatchConcurrency(accountMaxThreads),
                step = { g ->
                    if (providerBreaker.tripped) {
                        ArtworkWriteResult()
                    } else {
                        fetchArtworkForGame(context, g, localArt, preflight, forceLookup = false)
                    }
                },
                onCompleted = { g, written ->
                    val wasTripped = providerBreaker.tripped
                    when {
                        written.lookupFailed -> providerBreaker.recordFailure()
                        written.matchedAny -> providerBreaker.recordSuccess()
                        // Anything else (no lookup made at all — media already complete, cooling down, or
                        // an honest per-game Empty) is neither a provider success nor a provider failure;
                        // the counter is left alone.
                    }
                    if (written.writeBlocked) writeSkipped++
                    if (written.wroteAny) {
                        matched++
                        changed += written.changedMedia
                    }
                    done++
                    if (!wasTripped && providerBreaker.tripped) {
                        Log.w(
                            LogTag,
                            "artwork batch: provider failing for $MaxConsecutiveProviderFailures " +
                                "consecutive games — stopping lookups " +
                                "(${games.size - done} game(s) left unattempted)",
                        )
                    }
                    onProgress(Progress(done = done, total = total, matched = matched))
                },
                publish = { publishWrittenMedia(context, changed) },
                recover = { ArtworkWriteResult() },
            )
            if (writeSkipped > 0) {
                Log.w(LogTag, "artwork batch: $writeSkipped game(s) skipped — library folder access is read-only")
            }
            if (pcGameIds.isNotEmpty()) {
                runSerialPcArtworkPass(
                    gameIds = pcGameIds,
                    delayBetweenRows = { delay(PcArtworkPassDelayMs) },
                    // explicitPress=false: this is R6-P2-03 lever F's BULK serial pass, not a user's
                    // Enrich press -- the IGDB name-search rung must stay unreached from here (R6-P3-01
                    // review fix).
                    fetchOne = { gameId -> fetchOneArtwork(context, gameId, explicitPress = false) },
                    onCompleted = { _, result ->
                        if (result == OneResult.Matched) matched++
                        done++
                        onProgress(Progress(done = done, total = total, matched = matched))
                    },
                )
            }
            // R6-P2-03 §9: counts only, no URLs or credentials — replaces t/L/p in the spec's throughput
            // estimate with numbers from a real run once pulled off a device.
            Log.i(
                LogTag,
                "artwork batch done games=${games.size} pc=${pcGameIds.size} matched=$matched " +
                    "lookups=$lookups downloads=$downloads " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                    "concurrency=${artworkBatchConcurrency(accountMaxThreads)} " +
                    "providerStopped=${providerBreaker.tripped}",
            )
            finishEnrichBatch(context, games = total, matched = matched)
            FetchResult(total = total, matched = matched, writeBlocked = writeSkipped > 0 && matched == 0)
        }

    /**
     * Compatibility entry point retained for existing callers. The user-facing "Fetch artwork" action now
     * acquires both missing image types through [fetchAllArtwork].
     */
    suspend fun fetchAllCovers(context: Context, onProgress: (Progress) -> Unit = {}): FetchResult =
        fetchAllArtwork(context, onProgress)

    /**
     * Fetch missing managed cover, clear-logo, Hero, and physical support art for one game in one metadata lookup.
     * A verified unattributed image is replaced only when its bytes differ; a successful write is
     * [OneResult.Matched].
     *
     * R-24 , policy 2026-08-19
     *: a `pc_games` row is
     * resolved and routed to [fetchOnePcArtwork] BEFORE the ScreenScraper gate — ScreenScraper has no
     * `pc_games` system id (R-24) and must never refuse this platform's Enrich for a gate it never
     * needed. The outer short-circuit below only proves BOTH providers are definitely closed; it must
     * not touch [context] on its own (FA-M5), so it cannot yet know the platform and asks neither gate's
     * full contract (SteamGridDB's is `resolvedKey` + [MimirSteamGridDbGate.isOpen]; checked properly
     * inside [fetchOnePcArtwork] once the row is known).
     */
    suspend fun fetchOneArtwork(
        context: Context,
        gameId: String,
        candidateNames: List<String> = emptyList(),
        // Defaults FALSE: source review found the new-games batch reaching the ScreenScraper PC-logo
        // fallback and IGDB's name search through the old default of true, which spends the player's quota on a run he did not
        // press. Only the per-game Enrich press passes true (`MimirArtworkState.fetchOne`); the bulk PC pass and the new-games batch
        // pass false, and nothing else gets either lane by default.
        explicitPress: Boolean = false,
    ): OneResult {
        // A shared enrich claim, taken before anything else: a batch that calls this per game simply holds one more, and a
        // press while the game-music download runs is refused here, in the one place every caller passes.
        val claim = ArtworkOperationGate.process.tryClaimEnrich() ?: return OneResult.Busy
        try {
            return fetchOneArtworkClaimed(context, gameId, candidateNames, explicitPress)
                .also { recordEnrichAttempt(context, gameId, it) }
        } finally {
            claim.release()
        }
    }

    private suspend fun fetchOneArtworkClaimed(
        context: Context,
        gameId: String,
        candidateNames: List<String>,
        explicitPress: Boolean,
    ): OneResult =
        withContext(Dispatchers.IO) {
            if (!MimirConnectedArtGate.isOpen && !MimirSteamGridDbGate.isOpen) {
                return@withContext OneResult.GateClosed
            }
            val database = MimirDatabaseFactory.get(context)
            val currentGameId = database.gameIdentityDao().resolveCurrentGameId(gameId)
            val g = database.gameDao().membersOfGame(currentGameId).firstOrNull()
                ?: return@withContext OneResult.NoMatch
            if (PlatformId(g.platformId) == PlatformId.PC_GAMES) {
                return@withContext fetchOnePcArtwork(context, g, explicitPress)
            }
            if (!MimirConnectedArtGate.isOpen) return@withContext OneResult.GateClosed
            ArtworkConnectController.attach(context)
            ArtResolutionStore.attach(context)
            ArtNoMatchStore.attach(context)
            val localArt = LocalArtSource(context)
            val written = artworkWriteResultFromStep(
                runArtworkStep {
                    fetchArtworkForGame(
                        context,
                        g,
                        localArt,
                        SafWritePreflight(context),
                        forceLookup = true,
                        candidateNames = candidateNames,
                    )
                },
            )
            // Per-game Enrich used to return before the managed-media repository epoch changed, leaving
            // the visible Library row/Coil key on the old image until a later rescan or process restart.
            // Publish the exact changed locator synchronously before reporting success.
            if (written.wroteAny) publishWrittenMedia(context, written.changedMedia)
            when {
                written.lookupFailed -> OneResult.Failed
                written.matchedAny -> OneResult.Matched
                else -> OneResult.NoMatch
            }
        }

    /**
     * R-24: SteamGridDB owns `pc_games` outright — ScreenScraper matches ROM files by hash/name and
     * structurally cannot identify a GameNative shortcut, so it is never consulted here regardless of
     * [MimirConnectedArtGate]'s state. WHICH SteamGridDB game is decided once, by [pcArtworkLookupPlan]:
     * the exact Steam app id parsed from the game's `.steam` shortcut ([steamAppIdForManagedPath], shared
     * with [pcGameSteamAppId] and the automatic Home-shelf tile lane) whenever there is one — never a
     * name guess over an exact key (R-23) — and otherwise R-25's other half, identification by name
     * (`.epic`/`.gog`/`.amazon`/`.pcgame`, and a SAF-only row with no managedPath at all).
     *
     * Reported Home/Library routing mismatch, 2026-09-11: this route used to return [OneResult.NoMatch] the moment [steamAppIdForManagedPath]
     * came back null, before SteamGridDB was asked anything, and the card then told the player SteamGridDB
     * had no art — while the Home shelf's Customize-media picker found the same game by name through the
     * very same autocomplete route. The name path here IS that route ([SteamGridDbTileFetcher.searchGameByTitle]:
     * [sgdbSearchCandidates] angles, every hit validated against the full title by [sgdbTitleMatches], so
     * an adjacent sequel/edition — R-25's stated worry — is rejected the same way the Home picker rejects
     * it). Whatever resolves the game, [fetchAndPersistSteamGridDbGame] then runs the ONE persist body for
     * it, so the two identification stories cannot drift apart in what they save.
     *
     * Writes nothing to [ArtResolutionStore], [ArtNoMatchStore], or [ScreenScraperMetadataCache]: an
     * SteamGridDB id fed back as a ScreenScraper `providerGameId` would poison a later ScreenScraper query
     * and disable the DB01 legacy-repair path (legacy-repair hazard). See [fetchAndPersistSteamGridDbGame] for what IS written and where.
     */
    private suspend fun fetchOnePcArtwork(context: Context, g: GameEntity, explicitPress: Boolean): OneResult {
        val apiKey = SteamGridDbKeyStore.resolvedKey(context)
        val title = g.canonicalTitle?.takeIf(String::isNotBlank) ?: g.title
        // No SteamGridDB key or gate: the player may still have ScreenScraper, which can serve a PC logo and nothing else. An
        // explicit Enrich press asks it; anything else still ends here as it always did.
        val steamGridDbUnavailable = apiKey.isBlank() || !MimirSteamGridDbGate.isOpen
        if (steamGridDbUnavailable) {
            return screenScraperOnlyPcLogo(context, g, title, explicitPress, nothingWritten = OneResult.GateClosed)
        }
        // R-25: `.steam` is the one PC extension with an exact identity; the other four
        // (.epic/.gog/.amazon/.pcgame) yield no app id here and identify by name instead. R6-P1-04: a
        // SAF-only entry (no managedPath) is no longer an automatic name search either -- the shortcut's
        // own body is read through the SAF grant when the managed path alone can't resolve one.
        val appId = steamAppIdForShortcut(context, g.managedPath, g.sourceDocumentUri, g.displayName)
        val plan = pcArtworkLookupPlan(appId, title)
        val lookup = when (plan) {
            is PcArtworkLookupPlan.SteamApp -> PcGameLookup.Resolved(SteamGridDbGameRef.SteamApp(plan.appId))
            is PcArtworkLookupPlan.NameSearch ->
                when (val search = SteamGridDbTileFetcher.searchGameByTitle(apiKey, plan.title, plan.candidates)) {
                    is SteamGridDbTileFetcher.TitleSearch.Found -> PcGameLookup.Resolved(search.game)
                    // SteamGridDB WAS asked and had no entry matching this title: an honest miss.
                    SteamGridDbTileFetcher.TitleSearch.NoMatch -> PcGameLookup.Ended(OneResult.NoMatch)
                    // Transport/provider trouble is never presented as "not found".
                    SteamGridDbTileFetcher.TitleSearch.Failed -> PcGameLookup.Ended(OneResult.Failed)
                }
            // Nothing to ask with: no shortcut id and a blank/tag-only title. Not a provider miss.
            PcArtworkLookupPlan.NoTitle -> PcGameLookup.Ended(OneResult.NoTitle)
        }
        val result = when (lookup) {
            is PcGameLookup.Resolved ->
                fetchAndPersistSteamGridDbGame(context, g, apiKey, title, lookup.game, explicitPress)
            // SteamGridDB was asked and knows no such game: ScreenScraper may still know it by name, for its logo alone.
            is PcGameLookup.Ended -> if (lookup.result == OneResult.NoMatch) {
                screenScraperOnlyPcLogo(context, g, title, explicitPress, nothingWritten = OneResult.NoMatch)
            } else {
                lookup.result
            }
        }
        // One line per Enrich press (never per candidate or asset): which plan ran, which SteamGridDB game
        // it settled on, and what came of it. No URLs, no key.
        Log.i(
            PcLookupLogTag,
            "pc lookup game=${g.gameId} plan=${pcArtworkLookupPlanLogName(plan)} " +
                "sgdbGame=${(lookup as? PcGameLookup.Resolved)?.game?.urlSegment ?: "none"} " +
                "result=${oneResultLogName(result)} verified=${verifiedTagFor(context, g)}",
        )
        return result
    }

    /** How [fetchOnePcArtwork]'s identification step ended: a game to fetch for, or a verdict already reached. */
    private sealed interface PcGameLookup {
        data class Resolved(val game: SteamGridDbGameRef) : PcGameLookup
        data class Ended(val result: OneResult) : PcGameLookup
    }

    /** Stable log names for the identification line — never `::class.simpleName`, which a minified build could rename. */
    private fun pcArtworkLookupPlanLogName(plan: PcArtworkLookupPlan): String = when (plan) {
        is PcArtworkLookupPlan.SteamApp -> "SteamApp"
        is PcArtworkLookupPlan.NameSearch -> "NameSearch"
        PcArtworkLookupPlan.NoTitle -> "NoTitle"
    }

    private fun oneResultLogName(result: OneResult): String = when (result) {
        OneResult.Matched -> "Matched"
        OneResult.NoMatch -> "NoMatch"
        OneResult.NoTitle -> "NoTitle"
        OneResult.Failed -> "Failed"
        OneResult.GateClosed -> "GateClosed"
        OneResult.Busy -> "Busy"
    }

    /**
     * R6-P1-04: read-only classification of [g]'s CURRENT verified metadata row, purely for the
     * identification log's `verified=` diagnostic -- never used to gate behavior. "synopsis" when a
     * non-blank description is cached (whoever wrote it), "marker" when only the SteamGridDB marker is
     * settled, "none" otherwise -- surfaces a silent decline of the marker persist on device.
     *
     * R6-P3-01: reads through [TitleMetadataCacheDao.freshByCatalogKey] rather than the raw
     * [TitleMetadataCacheDao.byCatalogKey] -- a capped IGDB synopsis past its 24h `cacheExpiresAt` must
     * read as expired here too, or this diagnostic would say "synopsis" for a row the Enrich seat itself
     * (via `Daos.kt`'s `FRESH_DESCRIPTION_SQL`) already shows as bare "No synopsis".
     */
    private suspend fun verifiedTagFor(context: Context, g: GameEntity): String = try {
        val database = MimirDatabaseFactory.get(context)
        // Same resolution persistVerifiedRecord itself uses (its default resolveCurrentGameId) -- reading
        // the row under g.gameId's own key would report "none" for an aliased id whose row now lives
        // under a different current key, even though the game IS settled.
        val currentGameId = database.gameIdentityDao().resolveCurrentGameId(g.gameId)
        val cached = database.titleMetadataCacheDao().freshByCatalogKey("game:$currentGameId")
        val description = cached?.description
        when {
            !description.isNullOrBlank() -> "synopsis"
            cached?.source?.equals(VerifiedMetadataCache.STEAMGRIDDB_SOURCE, ignoreCase = true) == true -> "marker"
            else -> "none"
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        "none"
    }

    /**
     * The ONE persist body for a `pc_games` row once SteamGridDB knows which game it is — shared by the
     * Steam-app-id plan and the name-search plan so the two cannot drift in what they save.
     * Every asset step is keyed by [game]'s id-type URL segment (`steam/<appid>` or
     * `game/<sgdbId>`), which every SteamGridDB asset route accepts identically.
     *
     * Persistence invariants shared with the app-id-only route:
     *
     * exactly ONE
     * metadata write, [fetchAndPersistSteamDescription], a `steam:store`-attributed `title_metadata_cache`
     * row through [VerifiedMetadataCache] — never [ScreenScraperMetadataCache], so the poisoning hazard
     * named on [fetchOnePcArtwork] does not apply (no `providerGameId` is ever set on a Steam record). It
     * needs a STEAM app id, so it is the one step a name-resolved [SteamGridDbGameRef.Game] skips.
     *
     * exactly ONE
     * `media_art/` write — an automatically-fetched hero backdrop lands at the same ROM-sibling
     * `media_art/<base>-hero.png` slot ScreenScraper already writes for every other platform — never
     * `filesDir/hero` (the distinct chosen-hero store) and never a new app-internal namespace. Create-only
     * (an existing hero, chosen or previously auto-fetched, is never refetched or overwritten), and staged
     * through [CustomHeroFetcher.stageCandidate] — the SteamGridDB-allowlisted download path already used
     * for a user's manual Custom Hero pick — never this file's ScreenScraper-host-only media download
     * helper, which cannot reach a SteamGridDB CDN URL at all.
     *
     * A second `media_art/` write (every enrich fetches what the launch card's
     * animation needs): the logo, at `media_art/<base>-logo.png`, on the same create-only terms, staged through
     * [CustomHeroFetcher.stageSteamGridDbLogo] on the same allowlist.
     *
     * Everything else stays outside `media_art/`: the app-internal `sgdb2` tile cache
     * [SteamGridDbTileFetcher] already writes for the Home shelf (`HomeShelfCoordinator.kt`), keyed
     * identically so both surfaces share one square tile, plus its portrait companion for the Library slot.
     */
    private suspend fun fetchAndPersistSteamGridDbGame(
        context: Context,
        g: GameEntity,
        apiKey: String,
        title: String,
        game: SteamGridDbGameRef,
        explicitPress: Boolean,
    ): OneResult {
        val cacheKey = SteamGridDbTileFetcher.emulatedKey(GameId(g.gameId))
        // Steam storefront description (policy 2026-08-19): independent of art, so this runs
        // BEFORE the `hasTile` early return below -- a game that already has its SteamGridDB tile must
        // still get its description filled in on a later Enrich press, not be skipped forever. Keyed by
        // the Steam app id ONLY: a name-resolved SteamGridDB entry carries no Steam identity to ask the
        // storefront about, so that plan honestly has no description step.
        val descriptionPersisted = when (game) {
            is SteamGridDbGameRef.SteamApp -> fetchAndPersistSteamDescription(context, g, game.appId) ||
                // R6-P3-01 (review fix): IGDB last-resort name search, only after the Steam storefront
                // found nothing for this exact app id, and ONLY on an explicit Enrich press -- R6-P2-03
                // lever F's bulk PC pass must never reach IGDB (explicitPress=false there).
                (explicitPress && fetchAndPersistIgdbDescription(context, g, listOf(title) + sgdbSearchCandidates(title)))
            // R6-P3-01: a name-resolved SteamGridDB entry carries no Steam identity to ask the storefront
            // about, so IGDB's own name search is this plan's ONLY description step (policy
            // PC synopsis uses IGDB, again explicit-press only.
            is SteamGridDbGameRef.Game ->
                explicitPress && fetchAndPersistIgdbDescription(context, g, listOf(title) + sgdbSearchCandidates(title))
        }
        // R6-P1-04: a press that identified the game settles it. Runs before the hasTile early return for the same
        // reason the description step does: a game already tiled under the pre-fix code (Nova's Blasphemous 2,
        // portrait tile written 2026-09-11) must still settle on its next press, not never.
        val markerPersisted = !descriptionPersisted && persistSteamGridDbVerifiedMarker(context, g)
        // Portrait Library-slot companion (policy 2026-08-19, gap-around-the-focus-ring fix). Awaited here unconditionally --
        // BEFORE the square early return below -- so a game already square-tiled under the pre-fix code
        // still gains its portrait asset the next time Enrich runs, not only on a brand-new tile. Its
        // count IS folded into the OneResult below (PM gate DEFECT-class fix, 2026-08-19): a portrait
        // grid landing on disk with no qualifying square grid must report Matched, not NoMatch -- the
        // pre-fix code discarded this return value entirely, so the overlay said "SteamGridDB had no art
        // for this game. Nothing was changed." while the portrait art was already on screen.
        val portraitFetched =
            SteamGridDbTileFetcher.fetchMissingPortrait(context, apiKey, mapOf(cacheKey to game))
        // Hero backdrop (policy 2026-08-19):
        // same create-only + "runs before the hasTile early return" shape as the portrait fetch above, for
        // the same reason -- a game that already has its SteamGridDB square/portrait tile must still gain
        // a hero on a later Enrich press, not be skipped forever. Independent slot, independent gate: the
        // tile cache and `media_art/<base>-hero.png` share nothing but the SteamGridDB game used to look
        // them up. Take the single best-ranked candidate (steamGridDbHeroesForGame already sorts by
        // target-dimension distance then score) -- this route never lets the user pick, unlike the manual
        // Custom Hero picker.
        val heroStageDir = CustomHeroStore.cacheDir(context).apply { mkdirs() }
        val heroStageFile = File(heroStageDir, "pc-hero-${System.nanoTime().toULong().toString(16)}.stage")
        val heroWritten: GameEntity? = try {
            attemptPcHeroWrite(
                existingHeroUri = LocalArtSource(context).heroUriFor(g.sourceDocumentUri, g.managedPath),
                fetchCandidate = {
                    // The SteamGridDB-allowlisted staging path below -- byte-bounded, decode-proof, quality
                    // floor, bounded redirects -- is already used for a user's manual Custom Hero apply. The
                    // ScreenScraper-only download helper used for the fetches below cannot reach a
                    // SteamGridDB CDN URL at all, by design -- reuse this path instead of adding one.
                    (
                        CustomHeroFetcher.steamGridDbHeroesForGame(apiKey, game)
                            as? CustomHeroProviderResult.Loaded
                        )?.candidates?.firstOrNull()
                },
                stage = { candidate ->
                    CustomHeroFetcher.stageCandidate(context, candidate, expectedVideo = false, heroStageFile)
                },
                install = {
                    installDownloadedMediaAtCurrentGame(
                        context = context,
                        requestedGame = g,
                        bytes = heroStageFile.readBytes(),
                        slot = ManagedMediaSlot.HERO,
                        nameSuffix = HeroSuffix,
                    ).target
                },
            )
        } finally {
            heroStageFile.delete()
        }
        // Publish the hero write HERE, not deferred to the shared publish call below: the hasTile
        // early-return two lines down can exit this function before that call ever runs, and a bare
        // media_art/ file write touches no Room table -- without an immediate publish here, a hero that
        // landed on disk this press would render only after a process restart (the exact defect class
        // publishWrittenMedia's own kdoc names, RP5 Bifrost-backdrop repro 2026-07-17).
        if (heroWritten != null) publishWrittenMedia(context, listOf(heroWritten.asChangedMedia()))
        // Enrich fetches the logo needed by the launch card's animation.
        // Same shape as the hero above: create-only, before the hasTile early return, published at once, and staged
        // through SteamGridDB's allowlisted path. It lands in the `<base>-logo` slot ScreenScraper fills for every other
        // platform, which is the one GameOpeningIntro reads.
        val logoStageFile = File(heroStageDir, "pc-logo-${System.nanoTime().toULong().toString(16)}.stage")
        val logoWritten: GameEntity? = try {
            // SteamGridDB's
            // logo first, as above, and ScreenScraper's `wheel` only when SteamGridDB gave this game none. The ScreenScraper
            // half is the player's own account and an explicit press only (the same rule as the IGDB rung: the bulk PC pass
            // never spends ScreenScraper quota on a game SteamGridDB has no logo for).
            attemptPcMediaWrite<Boolean>(
                existingUri = LocalArtSource(context).logoUriFor(g.sourceDocumentUri, g.managedPath),
                fetchCandidate = { true },
                stage = {
                    stagePcLogo(
                        steamGridDbLogoUrl = { CustomHeroFetcher.steamGridDbLogoUrlForGame(apiKey, game) },
                        stageSteamGridDb = { url -> CustomHeroFetcher.stageSteamGridDbLogo(url, logoStageFile) },
                        screenScraperLogoUrl = { if (explicitPress) pcScreenScraperLogoUrl(context, g, title) else null },
                        stageScreenScraper = { url -> stageScreenScraperLogo(url, logoStageFile) },
                        log = { line -> Log.i(PcLookupLogTag, line) },
                    ) != null
                },
                install = {
                    installDownloadedMediaAtCurrentGame(
                        context = context,
                        requestedGame = g,
                        bytes = logoStageFile.readBytes(),
                        slot = ManagedMediaSlot.LOGO,
                        nameSuffix = LogoSuffix,
                    ).target
                },
            )
        } finally {
            logoStageFile.delete()
        }
        if (logoWritten != null) publishWrittenMedia(context, listOf(logoWritten.asChangedMedia()))
        // fetchMissing only SKIPS an existing tile (no signal back); check presence first so a game that
        // already has art reports Matched instead of NoMatch.
        if (SteamGridDbTileFetcher.hasTile(context, cacheKey)) return OneResult.Matched
        // The square tile is fetched for the SAME resolved game as everything above (resolvedGamesByKey):
        // fetchMissing's own title-search rung is never reached from here, so one Enrich press is one
        // identification, not one per asset kind.
        val fetched = SteamGridDbTileFetcher.fetchMissing(
            context = context,
            apiKey = apiKey,
            titlesByKey = mapOf(cacheKey to title),
            resolvedGamesByKey = mapOf(cacheKey to game),
        )
        // No `publishWrittenMedia` for the tile: fetchMissing and fetchMissingPortrait each publish ONE shelf-face
        // refresh of their own when they write (SteamGridDbTileFetcher.publishTileWrite), which re-projects every open
        // row flow without evicting probes or re-keying the cover. A second bump here was a duplicate invalidation
        // of the same write (and re-keyed the cover's `#mv=` for a file that is not the cover).
        // A portrait-only success (square grid missing/no candidate, portrait grid landed) still counts
        // as Matched: the shelf-face resolver already prefers the portrait file for a game with no cover,
        // and fetchMissingPortrait publishes the same refresh when it writes -- so reporting NoMatch here would be the exact
        // "overlay says nothing changed while art is on screen" dishonesty this fix line exists to close.
        // descriptionPersisted folds in the same way ScreenScraper's own metadataMatched already does for
        // every other platform (ArtworkWriteResult.metadataMatched, above): a durable metadata write is a
        // match on its own, even with zero art bytes changed this press.
        // heroWritten folds in for the same reason: its publish already ran above, independent of this
        // return path, but a hero-only success (no square/portrait grid, no description change) must still
        // report Matched rather than NoMatch. logoWritten likewise.
        return if (
            fetched > 0 || portraitFetched > 0 || descriptionPersisted || markerPersisted || heroWritten != null ||
            logoWritten != null
        ) {
            OneResult.Matched
        } else {
            OneResult.NoMatch
        }
    }

    /**
     * R6-P1-04: the settle-only write for a press that identified the game on SteamGridDB but persisted no
     * synopsis this press (a name-resolved [SteamGridDbGameRef.Game], or a [SteamGridDbGameRef.SteamApp]
     * whose storefront call found nothing). Same shape as [fetchAndPersistSteamDescription]'s own persist
     * block: `canonicalTitle = ""` so the shared fallback ladder keeps local truth, `providerGameId` stays
     * null (the poisoning hazard on [fetchOnePcArtwork] is untouched), and the no-downgrade rule in
     * [VerifiedMetadataCache] refuses to relabel an existing synopsis-bearing row.
     */
    private suspend fun persistSteamGridDbVerifiedMarker(context: Context, g: GameEntity): Boolean = try {
        val database = MimirDatabaseFactory.get(context)
        VerifiedMetadataCache.persistVerifiedRecord(
            dao = database.titleMetadataCacheDao(),
            game = g,
            record = MetadataRecord(canonicalTitle = "", origin = MetadataOrigin.STEAMGRIDDB),
            gameIdentityDao = database.gameIdentityDao(),
            runTransaction = { block -> database.withTransaction { block() } },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(LogTag, "Unable to persist verified SteamGridDB marker", error)
        false
    }

    /** Compatibility entry point for the retired per-game Get art name. */
    suspend fun fetchOneCover(context: Context, gameId: String): OneResult =
        fetchOneArtwork(context, gameId)

    /**
     * how a per-game Enrich ended is kept ([EnrichAttemptStore]), so a FINISHED attempt hides Enrich for the game on
     * the direct seat and in the Y menu, and a FAILED one keeps it. [enrichAttemptOutcomeFor] decides what a result means; a closed or busy
     * gate asked nothing and records nothing. Runs for every per-game caller (the press, the bulk PC pass, the new-games batch), after the
     * result is known and the claim still held. The library projection reads the record, so a changed answer re-projects this one game.
     */
    internal suspend fun recordEnrichAttempt(context: Context, gameId: String, result: OneResult) {
        val outcome = enrichAttemptOutcomeFor(result) ?: return
        val changed = withContext(Dispatchers.IO) {
            EnrichAttemptStore.attach(context)
            EnrichAttemptStore.record(gameId, outcome)
        }
        if (changed) LibraryStore.repository(context).refreshArt(GameId(gameId))
    }

    /**
     * A ScreenScraper-only PC press that LANDED a logo identified the game as far as the seat can tell, so it writes the same settle-only record
     * the SteamGridDB door writes at the end of its press ([persistSteamGridDbVerifiedMarker]). Without it a keyless `pc_games` game kept its
     * Enrich seat after its logo was on disk (the record is what moves a game off NEVER_REQUESTED). Nothing is written when no logo landed.
     * The record's origin is SteamGridDB's settle-only marker, though SteamGridDB was not asked; it carries no synopsis and no provider id.
     */
    internal suspend fun settlePcLogoOnlyPress(logoLanded: Boolean, persistMarker: suspend () -> Boolean): Boolean =
        logoLanded && persistMarker()

    /**
     * Create-only decision for the automatic PC hero write (policy 2026-08-19), extracted as its own injectable-seam function
     * so the network/Room/Keystore-bound [fetchOnePcArtwork] can be exercised by a real JVM unit test.
     * [PcEnrichSteamGridDbRoutingWiringTest]'s own class kdoc documents why a genuine end-to-end run of
     * that function is unreachable from this module (no Robolectric+Room precedent here, and
     * `SteamGridDbKeyStore.resolvedKey` is exercised only by a device test elsewhere in this repo) -- this
     * seam is the escape hatch: [fetchCandidate], [stage] and [install] stand in for the three
     * network/DB-bound steps a fake can replace entirely, following this file's existing
     * [withFinalArtworkTarget] precedent for the same gap.
     *
     * [existingHeroUri] is [fetchOnePcArtwork]'s already-resolved `LocalArtSource(context).heroUriFor(...)`
     * probe -- a non-null value (chosen OR previously auto-fetched hero) short-circuits before
     * [fetchCandidate] ever runs, so a game that already has a hero is never refetched or overwritten by
     * this path.
     */
    internal suspend fun attemptPcHeroWrite(
        existingHeroUri: String?,
        fetchCandidate: suspend () -> CustomHeroCandidate?,
        stage: suspend (CustomHeroCandidate) -> Boolean,
        install: suspend () -> GameEntity?,
    ): GameEntity? = attemptPcMediaWrite(existingHeroUri, fetchCandidate, stage, install)

    /** One create-only PC media write: nothing if the slot is filled, and nothing installed unless staging proved the bytes. */
    internal suspend fun <C> attemptPcMediaWrite(
        existingUri: String?,
        fetchCandidate: suspend () -> C?,
        stage: suspend (C) -> Boolean,
        install: suspend () -> GameEntity?,
    ): GameEntity? {
        if (existingUri != null) return null
        val candidate = fetchCandidate() ?: return null
        if (!stage(candidate)) return null
        return install()
    }

    /** Which provider a PC game's staged logo came from. */
    internal enum class PcLogoSource(val logName: String) {
        STEAMGRIDDB("steamgriddb"),
        SCREENSCRAPER("screenscraper"),
    }

    /**
     * A PC game's logo, in provider fallback order: SteamGridDB's first, then ScreenScraper's `wheel`. A provider is
     * ASKED only while the one before it has produced nothing: a SteamGridDB logo that stages ends it with no ScreenScraper
     * request, and a SteamGridDB URL whose bytes will not stage falls on to ScreenScraper like a miss. A provider with no key
     * or account answers null (its own URL function makes no request), so with neither configured nothing is staged and the
     * game keeps the fallback it always had.
     *
     * [log] is handed FIXED lines only (the source and the outcome): never a URL, because a ScreenScraper media URL carries the
     * player's credentials, and never a key.
     */
    internal suspend fun stagePcLogo(
        steamGridDbLogoUrl: suspend () -> String?,
        stageSteamGridDb: suspend (String) -> Boolean,
        screenScraperLogoUrl: suspend () -> String?,
        stageScreenScraper: suspend (String) -> Boolean,
        log: (String) -> Unit = {},
    ): PcLogoSource? {
        steamGridDbLogoUrl()?.let { url ->
            if (stageSteamGridDb(url)) {
                log("pc logo source=steamgriddb outcome=staged")
                return PcLogoSource.STEAMGRIDDB
            }
            log("pc logo source=steamgriddb outcome=unusable")
        }
        screenScraperLogoUrl()?.let { url ->
            if (stageScreenScraper(url)) {
                log("pc logo source=screenscraper outcome=staged")
                return PcLogoSource.SCREENSCRAPER
            }
            log("pc logo source=screenscraper outcome=unusable")
        }
        return null
    }

    /**
     * A PC game's logo from ScreenScraper ALONE, for the two ways an Enrich press can reach no SteamGridDB game: no key or gate
     * for it, or SteamGridDB knowing no such title.
     *
     * Everything else about the lane is unchanged: explicit press only (a bulk run answers [nothingWritten] untouched and asks
     * nothing), the player's own connected account (a closed ScreenScraper gate does the same), the logo slot create-only, the one
     * gated download, fixed log lines only. It writes the logo and, once that has landed, the settle-only record ([settlePcLogoOnlyPress]), so the cover, hero and
     * tile steps that need a SteamGridDB game are not run. A logo that lands is [OneResult.Matched]; otherwise the verdict the caller already had,
     * except that a press which asked ScreenScraper and found nothing is a [OneResult.NoMatch] rather than a closed gate, and a
     * game whose logo is already on disk is [OneResult.Matched] like a game that already has its tile.
     */
    private suspend fun screenScraperOnlyPcLogo(
        context: Context,
        g: GameEntity,
        title: String,
        explicitPress: Boolean,
        nothingWritten: OneResult,
    ): OneResult {
        if (!explicitPress || !MimirConnectedArtGate.isOpen) return nothingWritten
        val existing = LocalArtSource(context).logoUriFor(g.sourceDocumentUri, g.managedPath)
        if (existing != null) return if (nothingWritten == OneResult.GateClosed) OneResult.Matched else nothingWritten
        val stageDir = CustomHeroStore.cacheDir(context).apply { mkdirs() }
        val stageFile = File(stageDir, "pc-logo-${System.nanoTime().toULong().toString(16)}.stage")
        var outcome: ScreenScraperMetadataSource.PcLogoOutcome? = null
        val written: GameEntity? = try {
            attemptPcMediaWrite<Boolean>(
                existingUri = null,
                fetchCandidate = { true },
                stage = {
                    stagePcLogo(
                        steamGridDbLogoUrl = { null },
                        stageSteamGridDb = { false },
                        screenScraperLogoUrl = {
                            val answer = pcScreenScraperLogoOutcome(context, g, title)
                            outcome = answer
                            (answer as? ScreenScraperMetadataSource.PcLogoOutcome.Found)?.url
                        },
                        stageScreenScraper = { url -> stageScreenScraperLogo(url, stageFile) },
                        log = { line -> Log.i(PcLookupLogTag, line) },
                    ) != null
                },
                install = {
                    installDownloadedMediaAtCurrentGame(
                        context = context,
                        requestedGame = g,
                        bytes = stageFile.readBytes(),
                        slot = ManagedMediaSlot.LOGO,
                        nameSuffix = LogoSuffix,
                    ).target
                },
            )
        } finally {
            stageFile.delete()
        }
        settlePcLogoOnlyPress(logoLanded = written != null) { persistSteamGridDbVerifiedMarker(context, g) }
        if (written != null) {
            publishWrittenMedia(context, listOf(written.asChangedMedia()))
            return OneResult.Matched
        }
        return pcLogoOnlyVerdict(nothingWritten, outcome)
    }

    /**
     * The verdict of the ScreenScraper-only logo door when no logo was written. A failure is never reported as a miss (fix round ux3):
     * [OneResult.NoMatch] only when ScreenScraper answered every PC system and had no matching logo. A logo that was offered but not
     * delivered (its download or decode failed, or the install did not land) and a lookup that failed (transport, auth, quota, an
     * unreadable answer) are both [OneResult.Failed], which the card keeps on screen with its failure wording. Nothing was asked
     * (connection closed, no registration) leaves [nothingWritten] as the caller had it.
     */
    internal fun pcLogoOnlyVerdict(
        nothingWritten: OneResult,
        outcome: ScreenScraperMetadataSource.PcLogoOutcome?,
    ): OneResult = when (outcome) {
        null, ScreenScraperMetadataSource.PcLogoOutcome.Unavailable -> nothingWritten
        ScreenScraperMetadataSource.PcLogoOutcome.NoMatch -> OneResult.NoMatch
        ScreenScraperMetadataSource.PcLogoOutcome.Failed, is ScreenScraperMetadataSource.PcLogoOutcome.Found -> OneResult.Failed
    }

    /**
     * ScreenScraper's `wheel` for a PC game, by name, on the player's own connected account; null when the connection is
     * closed or no account is configured (no request is built then), or when ScreenScraper has no logo for the title. One name
     * only, so the lane costs at most one request per PC system ([ScreenScraperMetadataSource.PC_LOGO_SYSTEME_IDS]).
     */
    private suspend fun pcScreenScraperLogoUrl(context: Context, g: GameEntity, title: String): String? =
        (pcScreenScraperLogoOutcome(context, g, title) as? ScreenScraperMetadataSource.PcLogoOutcome.Found)?.url

    /** [pcScreenScraperLogoUrl] with the reason it found nothing kept: a miss, a failure, or no request possible. */
    private suspend fun pcScreenScraperLogoOutcome(
        context: Context,
        g: GameEntity,
        title: String,
    ): ScreenScraperMetadataSource.PcLogoOutcome {
        if (!MimirConnectedArtGate.isOpen) return ScreenScraperMetadataSource.PcLogoOutcome.Unavailable
        ArtworkConnectController.attach(context)
        return ArtworkConnectController.source.pcLogoOutcome(
            MetadataQuery(platformId = PlatformId(g.platformId), candidateNames = listOf(title)),
        )
    }

    /** Downloads ScreenScraper's logo (through the one gated, host-checked download) into [target], proving it decodes. */
    private fun stageScreenScraperLogo(url: String, target: File): Boolean {
        val bytes = downloadGated(url, MaxImageBytes) ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        return try {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            true
        } catch (_: java.io.IOException) {
            target.delete()
            false
        }
    }

    /** Explicit Enrich always refreshes verified metadata; automatic work remains media/write bounded. */
    internal fun artworkMetadataLookupAllowed(
        forceLookup: Boolean,
        needsMedia: Boolean,
        writeAllowed: Boolean,
    ): Boolean = forceLookup || (needsMedia && writeAllowed)

    /**
     * R6-P2-03 lever A — the per-slot idempotency decision, extracted pure so it can be pinned without a
     * clock, Room, or SharedPreferences. COVER/LOGO/HERO: fetch when the
     * slot is absent and either explicit Enrich bypasses every cooldown or the slot's own provider-absent
     * negative isn't cooling down, OR when the slot is present but unattributed (the DB01 legacy-repair
     * re-verify, unrelated to cooldowns). PHYSICAL: absent-and-not-cooling only — unchanged from before
     * this lever, just expressed here. [slotCoolingDown] is the only clock-touching seam; this function
     * itself reads no clock, so the fourteen-day arithmetic stays exactly where it was already pinned.
     * CLIP is deliberately NOT here: Enrich downloads Hero Art instead. It used to be added behind a
     * preview-video opt-in; no bulk path may request a clip now. The per-game Customize Media picker
     * still fetches one on request through a separate path.
     * seam entirely, and the ruling retired the DOWNLOAD from enrich, not the feature.
     * AUDIO is deliberately NOT here either. Sound downloading is a Settings action ([GameMusicBatch])
     * with its own lookup, meter and
     * removed the same day: the sound is a Settings action ([GameMusicBatch]) with its own lookup, its own meter and its
     * own breaker, so nothing that enriches artwork can ask for it, and a game whose only missing slot is its sound
     * triggers no lookup at all ([artworkMetadataLookupAllowed] reads this set's emptiness as `needsMedia`).
     */
    internal fun artworkSlotsToFetch(
        present: Set<ManagedMediaSlot>,
        unattributed: Boolean,
        forceLookup: Boolean,
        slotCoolingDown: (ManagedMediaSlot) -> Boolean,
    ): Set<ManagedMediaSlot> {
        fun retryable(slot: ManagedMediaSlot) = slot !in present && (forceLookup || !slotCoolingDown(slot))
        fun legacyRepair(slot: ManagedMediaSlot) = slot in present && unattributed
        return buildSet {
            listOf(ManagedMediaSlot.COVER, ManagedMediaSlot.LOGO, ManagedMediaSlot.HERO).forEach { slot ->
                if (retryable(slot) || legacyRepair(slot)) add(slot)
            }
            if (retryable(ManagedMediaSlot.PHYSICAL)) add(ManagedMediaSlot.PHYSICAL)
        }
    }

    /**
     * The [ArtNoMatchStore] key for one slot's provider-absent negative. PHYSICAL keeps the exact literal
     * `"physical-media:<id>"` this file always used, so existing stored negatives stay valid; the other
     * four are new (R6-P2-03 lever A). Mirrored by [ArtNoMatchStore]'s own prefix list, which must iterate
     * the SAME new prefixes (now five, with `audio:`) so a rekeyed identity carries every slot's negative, not only
     * physical's.
     */
    internal fun artNoMatchSlotKey(slot: ManagedMediaSlot, gameId: String): String = when (slot) {
        ManagedMediaSlot.COVER -> "cover:$gameId"
        ManagedMediaSlot.LOGO -> "logo:$gameId"
        ManagedMediaSlot.HERO -> "hero:$gameId"
        ManagedMediaSlot.PHYSICAL -> "physical-media:$gameId"
        ManagedMediaSlot.CLIP -> "clip:$gameId"
        ManagedMediaSlot.AUDIO -> "audio:$gameId"
    }

    /**
     * Whether a verified [record] carries a URL for [slot] — the fact [artNoMatchSlotKey] caches.
     *
     * AUDIO answers through the same clip URL as CLIP, because the sound is cut from that clip. But the two are not
     * treated alike downstream: this fact CLEARS a slot's negative in [fetchArtworkForGame], and for AUDIO that loop is
     * skipped (see [audioNegativeAfterLookup]) -- a clip URL does not mean the clip has sound.
     */
    internal fun slotHasProviderUrl(record: MetadataRecord, slot: ManagedMediaSlot): Boolean = when (slot) {
        ManagedMediaSlot.COVER -> record.boxArtUri != null
        ManagedMediaSlot.LOGO -> record.flatArtUri != null
        ManagedMediaSlot.HERO -> record.heroArtUri != null
        ManagedMediaSlot.PHYSICAL -> record.physicalMediaUri != null
        ManagedMediaSlot.CLIP -> record.previewVideoUri != null
        ManagedMediaSlot.AUDIO -> record.previewVideoUri != null
    }

    /** The extension and MIME type a slot's managed file is written with. */
    internal fun managedMediaExtAndMime(slot: ManagedMediaSlot): Pair<String, String> = when (slot) {
        ManagedMediaSlot.CLIP -> "mp4" to "video/mp4"
        ManagedMediaSlot.AUDIO -> "m4a" to "audio/mp4"
        ManagedMediaSlot.COVER,
        ManagedMediaSlot.LOGO,
        ManagedMediaSlot.HERO,
        ManagedMediaSlot.PHYSICAL,
        -> "png" to "image/png"
    }

    /**
     * Shared combined per-game body. The cover and logo probes happen before a single metadata lookup, after
     * which each needed image is downloaded independently. An error or miss for one image does not suppress
     * the other image's attempt.
     */
    private suspend fun fetchArtworkForGame(
        context: Context,
        g: GameEntity,
        localArt: LocalArtSource,
        preflight: SafWritePreflight,
        forceLookup: Boolean,
        candidateNames: List<String> = emptyList(),
    ): ArtworkWriteResult {
        val existingCover = localArt.artUriFor(g.sourceDocumentUri, g.managedPath)
        val existingFlatArt = localArt.logoUriFor(g.sourceDocumentUri, g.managedPath)
        val existingHeroArt = localArt.heroUriFor(g.sourceDocumentUri, g.managedPath)
        val existingPhysicalMedia = localArt.physicalMediaUriFor(g.sourceDocumentUri, g.managedPath)
        val existingPreviewVideo = localArt.videoUriFor(g.sourceDocumentUri, g.managedPath)
        // ART02 stopped new Game Boy lookups from fetching Game Boy Advance artwork,
        // but wrong art written by the pre-verification lookup persists on disk and the create-only rule
        // skipped it forever. An existing image with NO ArtResolutionStore record predates verification
        // (unattributed) — this fetch re-runs the verified lookup and replaces it with system-verified art
        // only when the bytes differ. If the lookup can't verify, nothing is touched. Once verified, the
        // store record makes every later batch skip the game, so this re-check is one lookup per legacy game.
        val unattributed = ArtResolutionStore.resolvedGameIdFor(g.gameId) == null
        val replaceCover = existingCover != null && unattributed
        val replaceFlatArt = existingFlatArt != null && unattributed
        val replaceHeroArt = existingHeroArt != null && unattributed
        val present = buildSet {
            if (existingCover != null) add(ManagedMediaSlot.COVER)
            if (existingFlatArt != null) add(ManagedMediaSlot.LOGO)
            if (existingHeroArt != null) add(ManagedMediaSlot.HERO)
            if (existingPhysicalMedia != null) add(ManagedMediaSlot.PHYSICAL)
            if (existingPreviewVideo != null) add(ManagedMediaSlot.CLIP)
        }
        // R6-P2-03 lever A: every slot now cools down independently on its OWN provider-absent negative,
        // the same fourteen-day window PHYSICAL already used — a re-run no longer re-pays a lookup for
        // every game with any non-physical slot missing (spec §1.4). The decision itself is the pure
        // artworkSlotsToFetch; this closure only supplies the clock read it must not make itself.
        val toFetch = artworkSlotsToFetch(
            present = present,
            unattributed = unattributed,
            forceLookup = forceLookup,
            slotCoolingDown = { slot -> ArtNoMatchStore.isCoolingDown(artNoMatchSlotKey(slot, g.gameId)) },
        )
        val needsMedia = toFetch.isNotEmpty()
        val writeAllowed = preflight.allowsWrite(g.sourceDocumentUri, g.managedPath)
        // Automatic batches retain their bounded posture: no lookup when media is complete or cannot
        // be written. Explicit Enrich is also a metadata refresh, so it may cache a synopsis/provider
        // disposition without changing a media file or requiring sibling write access.
        if (!artworkMetadataLookupAllowed(forceLookup, needsMedia, writeAllowed)) {
            return ArtworkWriteResult(writeBlocked = needsMedia && !writeAllowed)
        }

        if (!forceLookup && ArtNoMatchStore.isCoolingDown(g.gameId)) return ArtworkWriteResult()
        val verified = when (val lookup = verifiedLookupOutcome(context, g, candidateNames)) {
            is VerifiedLookupOutcome.Loaded -> {
                ArtNoMatchStore.clear(g.gameId)
                lookup.metadata
            }
            VerifiedLookupOutcome.Empty -> {
                if (!forceLookup) {
                    ArtNoMatchStore.record(g.gameId)
                    return ArtworkWriteResult()
                }
                // R6-P3-01: ScreenScraper answered and had no match for this game -- IGDB's last-resort
                // name search, explicit Enrich press only (forceLookup=false already returned above, so
                // a bulk run never reaches this rung).
                return ArtworkWriteResult(
                    metadataMatched = fetchAndPersistIgdbDescription(
                        context,
                        g,
                        screenScraperMetadataQuery(g, candidateNames).candidateNames,
                    ),
                )
            }
            // Deliberately records nothing: no request was made, so there is no evidence about this
            // game to cache. The moment the platform gains a system id the very next batch retries it.
            VerifiedLookupOutcome.PlatformUnsupported -> {
                if (!forceLookup) return ArtworkWriteResult()
                // R6-P3-01: no ScreenScraper system id for this platform -- IGDB may still map it (today
                // only `pc_games` is grounded; see IgdbPlatformMap's OWED note for every ROM platform).
                return ArtworkWriteResult(
                    metadataMatched = fetchAndPersistIgdbDescription(
                        context,
                        g,
                        screenScraperMetadataQuery(g, candidateNames).candidateNames,
                    ),
                )
            }
            is VerifiedLookupOutcome.Failed -> return ArtworkWriteResult(lookupFailed = true)
        }
        if (!writeAllowed) {
            return ArtworkWriteResult(metadataMatched = verified.cached, writeBlocked = needsMedia)
        }
        if (!needsMedia) return ArtworkWriteResult(metadataMatched = verified.cached)
        val record = verified.record
        // R6-P2-03 lever A: a slot this lookup was ASKED about (toFetch) gets a negative WRITTEN when
        // the record carries no URL for it. Every slot's negative is CLEARED the moment this same record
        // carries a URL for it, even one NOT asked about this round (still cooling down, or already
        // present+attributed) — a slot never gets a negative recorded for a question it was never asked.
        ManagedMediaSlot.values().forEach { slot ->
            // AUDIO is not decided here, and is never asked for here (see artworkSlotsToFetch). Its negative means "the clip the
            // provider has gives no usable sound", and a clip URL being present would clear it on every lookup and send the
            // game-music download back to fetch the same silent clip. That download records and clears it, nobody else does.
            if (slot == ManagedMediaSlot.AUDIO) return@forEach
            val key = artNoMatchSlotKey(slot, g.gameId)
            if (slotHasProviderUrl(record, slot)) {
                ArtNoMatchStore.clear(key)
            } else if (slot in toFetch) {
                ArtNoMatchStore.record(key)
            }
        }
        // Mirrors physicalMediaWrite below: whichever slot paid for this lookup, EVERY absent slot the
        // record now answers gets downloaded — a slot cooling down on its own negative still accepts art
        // that arrived through a different slot's fresh lookup this round, never only the toFetch set.
        val coverWrite = if ((existingCover == null && record.boxArtUri != null) || replaceCover) {
            fetchImage(
                context = context,
                g = g,
                url = record.boxArtUri,
                slot = ManagedMediaSlot.COVER,
                overwrite = replaceCover,
            )
        } else {
            FinalMediaWrite()
        }
        val flatArtWrite = if ((existingFlatArt == null && record.flatArtUri != null) || replaceFlatArt) {
            fetchImage(
                context = context,
                g = g,
                url = record.flatArtUri,
                slot = ManagedMediaSlot.LOGO,
                nameSuffix = LogoSuffix,
                overwrite = replaceFlatArt,
            )
        } else {
            FinalMediaWrite()
        }
        // The replace above writes nothing when the verified record has no clear logo, which left the old file
        // standing: this slot formerly fell back to fan art, title screens and screenshots, so a game enriched
        // then kept a screenshot where its launch animation draws the logo. Remove it on this re-check,
        // knowing it deletes art on the card. Runs wherever the re-check
        // does, the automatic batch included, and takes whatever sits in that slot, whoever put it there.
        val staleLogoClear = if (staleUnattributedLogoShouldClear(replaceFlatArt, record.flatArtUri)) {
            clearStaleUnattributedLogo(context, g, requireNotNull(existingFlatArt))
        } else {
            FinalMediaWrite()
        }
        val heroArtWrite = if ((existingHeroArt == null && record.heroArtUri != null) || replaceHeroArt) {
            fetchImage(
                context = context,
                g = g,
                url = record.heroArtUri,
                slot = ManagedMediaSlot.HERO,
                nameSuffix = HeroSuffix,
                overwrite = replaceHeroArt,
            )
        } else {
            FinalMediaWrite()
        }
        // If another missing asset already paid for this lookup, accept newly available support art even
        // while an older support-miss cooldown is active.
        val physicalMediaWrite = if (existingPhysicalMedia == null && record.physicalMediaUri != null) {
            fetchImage(
                context = context,
                g = g,
                url = record.physicalMediaUri,
                slot = ManagedMediaSlot.PHYSICAL,
                nameSuffix = SupportSuffix,
            )
        } else {
            FinalMediaWrite()
        }
        // No bulk clip write since 2026-09-17: artworkSlotsToFetch never asks for CLIP, so a provider
        // record's previewVideoUri is ignored here even when it has one. Customize Media's own picker is
        // the only path that writes a motion hero now.
        val previewVideoWrite = FinalMediaWrite()
        // No sound here either: the game's sound is cut from the clip URL this record
        // carries, but fetching it is the game-music download's job and Enrich leaves the clip alone, so a game whose only
        // missing piece is its sound never reaches this line (artworkSlotsToFetch asks for no sound, so it paid for no lookup).
        val finalWrites = listOf(
            coverWrite,
            flatArtWrite,
            heroArtWrite,
            physicalMediaWrite,
            previewVideoWrite,
            staleLogoClear,
        )
        return ArtworkWriteResult(
            coverWritten = coverWrite.written,
            flatArtWritten = flatArtWrite.written,
            heroArtWritten = heroArtWrite.written,
            physicalMediaWritten = physicalMediaWrite.written,
            previewVideoWritten = previewVideoWrite.written,
            metadataMatched = verified.cached,
            writeBlocked = finalWrites.any(FinalMediaWrite::writeBlocked),
            changedMedia = finalWrites.mapNotNull(FinalMediaWrite::target).map { it.asChangedMedia() },
        )
    }

    private suspend fun fetchImage(
        context: Context,
        g: GameEntity,
        url: String?,
        slot: ManagedMediaSlot,
        nameSuffix: String = "",
        overwrite: Boolean = false,
    ): FinalMediaWrite {
        // Provider/network work is complete before the Room lock. Only the bounded local install is
        // serialized with alias resolution, so a concurrent scan cannot publish beside a retired winner.
        val startedAt = enrichCost.now()
        val bytes = url?.let { downloadGated(it, MaxImageBytes) }
        val written = if (bytes == null) {
            FinalMediaWrite()
        } else {
            installDownloadedMediaAtCurrentGame(
                context = context,
                requestedGame = g,
                bytes = bytes,
                slot = slot,
                nameSuffix = nameSuffix,
                overwriteRequested = overwrite,
            )
        }
        // The batch's cost line (MimirEnrichCost): this slot's whole step, download and install. Only steps that asked.
        if (url != null) enrichCost.addSlot(slot, enrichCost.now() - startedAt)
        return written
    }

    private suspend fun installDownloadedMediaAtCurrentGame(
        context: Context,
        requestedGame: GameEntity,
        bytes: ByteArray,
        slot: ManagedMediaSlot,
        nameSuffix: String = "",
        overwriteRequested: Boolean = false,
    ): FinalMediaWrite {
        val database = MimirDatabaseFactory.get(context)
        return withFinalArtworkTarget(
            runTransaction = { block -> database.withTransaction { block() } },
            resolveCurrent = {
                val currentGameId = database.gameIdentityDao().resolveCurrentGameId(requestedGame.gameId)
                database.gameDao().membersOfGame(currentGameId).firstOrNull()
            },
            missing = FinalMediaWrite(),
        ) { currentGame ->
            if (!SafWritePreflight(context).allowsWrite(
                    currentGame.sourceDocumentUri,
                    currentGame.managedPath,
                )
            ) {
                return@withFinalArtworkTarget FinalMediaWrite(writeBlocked = true)
            }

            val existingUri = currentManagedMediaUri(context, currentGame, slot)
            // A legacy-repair overwrite is evidence about the exact pre-network member. If a scan
            // changes the physical member meanwhile, the new winner's existing art remains authoritative.
            val mayOverwrite = overwriteRequested &&
                currentGame.entryKey == requestedGame.entryKey &&
                existingUri != null
            if (existingUri != null && !mayOverwrite) {
                return@withFinalArtworkTarget FinalMediaWrite()
            }

            val write = {
                writeManagedArt(
                    context = context,
                    sourceDocumentUri = currentGame.sourceDocumentUri,
                    managedPath = currentGame.managedPath,
                    romDisplayName = currentGame.displayName,
                    overwrite = mayOverwrite,
                    bytes = bytes,
                    ext = managedMediaExtAndMime(slot).first,
                    // The sound alone asks the platform which type its extension has, so a SAF provider keeps the
                    // `-audio.m4a` name (see safCreateMimeFor); the image and clip slots pass what they always did.
                    mime = if (slot == ManagedMediaSlot.AUDIO) {
                        safCreateMimeFor(managedMediaExtAndMime(slot).first)
                    } else {
                        managedMediaExtAndMime(slot).second
                    },
                    nameSuffix = nameSuffix,
                )
            }
            val written = if (mayOverwrite) {
                writeReplacementIfChanged(
                    downloaded = bytes,
                    existingLength = localArtLength(context, requireNotNull(existingUri)),
                    openExisting = { openLocalArtStream(context, existingUri) },
                    write = write,
                )
            } else {
                write()
            }
            FinalMediaWrite(
                target = currentGame.takeIf { written },
                writeFailed = !written && existingUri == null,
            )
        }
    }

    /**
     * Removes the logo [checkedUri] names, in the same transaction shape as [installDownloadedMediaAtCurrentGame]:
     * only for the member the lookup was about, only while that member's logo still resolves to [checkedUri], only
     * with write access, and only for a `<base>-logo` image inside `media_art` ([isManagedLogoPath]). A logo beside
     * the ROM is never Mimir's and is never touched. The returned target publishes like a write, so the slot empties
     * on screen.
     */
    private suspend fun clearStaleUnattributedLogo(
        context: Context,
        requestedGame: GameEntity,
        checkedUri: String,
    ): FinalMediaWrite {
        val database = MimirDatabaseFactory.get(context)
        return withFinalArtworkTarget(
            runTransaction = { block -> database.withTransaction { block() } },
            resolveCurrent = {
                val currentGameId = database.gameIdentityDao().resolveCurrentGameId(requestedGame.gameId)
                database.gameDao().membersOfGame(currentGameId).firstOrNull()
            },
            missing = FinalMediaWrite(),
        ) { currentGame ->
            if (currentGame.entryKey != requestedGame.entryKey) return@withFinalArtworkTarget FinalMediaWrite()
            if (!SafWritePreflight(context).allowsWrite(currentGame.sourceDocumentUri, currentGame.managedPath)) {
                return@withFinalArtworkTarget FinalMediaWrite(writeBlocked = true)
            }
            if (currentManagedMediaUri(context, currentGame, ManagedMediaSlot.LOGO) != checkedUri) {
                return@withFinalArtworkTarget FinalMediaWrite()
            }
            val path = localArtPath(checkedUri) ?: return@withFinalArtworkTarget FinalMediaWrite()
            if (!isManagedLogoPath(path)) return@withFinalArtworkTarget FinalMediaWrite()
            val removed = deleteLocalArt(context, checkedUri)
            // The game's id only: never the path or a URL.
            if (removed) Log.i(LogTag, "cleared stale unattributed logo game=${currentGame.gameId}")
            FinalMediaWrite(target = currentGame.takeIf { removed })
        }
    }

    /** The path a local art URI names: a `file://` path, or a SAF document id (`<volume>:<path>`). */
    private fun localArtPath(localUri: String): String? = runCatching {
        val uri = Uri.parse(localUri)
        when {
            uri.scheme.equals("file", ignoreCase = true) -> uri.path
            uri.scheme.equals("content", ignoreCase = true) -> DocumentsContract.getDocumentId(uri)
            else -> null
        }
    }.getOrNull()

    private fun deleteLocalArt(context: Context, localUri: String): Boolean = runCatching {
        val uri = Uri.parse(localUri)
        when {
            uri.scheme.equals("file", ignoreCase = true) ->
                uri.path?.let(::File)?.takeIf(File::isFile)?.delete() == true
            uri.scheme.equals("content", ignoreCase = true) ->
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            else -> false
        }
    }.getOrDefault(false)

    private fun currentManagedMediaUri(
        context: Context,
        game: GameEntity,
        slot: ManagedMediaSlot,
    ): String? {
        val local = LocalArtSource(context)
        return when (slot) {
            ManagedMediaSlot.COVER -> local.artUriFor(game.sourceDocumentUri, game.managedPath)
            ManagedMediaSlot.LOGO -> local.logoUriFor(game.sourceDocumentUri, game.managedPath)
            ManagedMediaSlot.HERO -> local.heroUriFor(game.sourceDocumentUri, game.managedPath)
            ManagedMediaSlot.PHYSICAL -> local.physicalMediaUriFor(
                game.sourceDocumentUri,
                game.managedPath,
            )
            ManagedMediaSlot.CLIP -> local.videoUriFor(game.sourceDocumentUri, game.managedPath)
            ManagedMediaSlot.AUDIO -> local.audioUriFor(game.sourceDocumentUri, game.managedPath)
        }
    }

    /**
     * PERF02 legacy repair: cheap size rejection first, then a streaming content comparison. An exact
     * match attributes the game without touching storage; only a known byte difference reaches [write].
     */
    internal fun writeReplacementIfChanged(
        downloaded: ByteArray,
        existingLength: Long?,
        openExisting: () -> InputStream?,
        write: () -> Boolean,
    ): Boolean {
        if (existingLength != null && existingLength != downloaded.size.toLong()) return write()
        val existing = openExisting() ?: return false
        val identical = existing.use { stream -> streamMatches(downloaded, stream) }
        return if (identical) false else write()
    }

    private fun streamMatches(expected: ByteArray, actual: InputStream): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var offset = 0
        while (true) {
            val read = actual.read(buffer)
            if (read < 0) return offset == expected.size
            if (read == 0) continue
            if (offset + read > expected.size) return false
            for (i in 0 until read) {
                if (buffer[i] != expected[offset + i]) return false
            }
            offset += read
        }
    }

    private fun localArtLength(context: Context, localUri: String): Long? = runCatching {
        val uri = Uri.parse(localUri)
        when {
            uri.scheme.equals("file", ignoreCase = true) ->
                uri.path?.let(::File)?.takeIf(File::isFile)?.length()
            uri.scheme.equals("content", ignoreCase = true) ->
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L }
                }
            else -> null
        }
    }.getOrNull()

    private fun openLocalArtStream(context: Context, localUri: String): InputStream? = runCatching {
        val uri = Uri.parse(localUri)
        when {
            uri.scheme.equals("file", ignoreCase = true) ->
                uri.path?.let(::File)?.takeIf(File::isFile)?.inputStream()
            uri.scheme.equals("content", ignoreCase = true) -> context.contentResolver.openInputStream(uri)
            else -> null
        }
    }.getOrNull()

    /** Automatic batches honor a recent miss; explicit per-game actions force the lookup and clear on hit. */
    internal suspend fun <T> lookupWithNoMatchCooldown(
        forceLookup: Boolean,
        isCoolingDown: () -> Boolean,
        lookup: suspend () -> T?,
        recordNoMatch: () -> Unit,
        clearNoMatch: () -> Unit,
    ): T? {
        if (!forceLookup && isCoolingDown()) return null
        val result = lookup()
        if (result == null) {
            if (!forceLookup) recordNoMatch()
        } else {
            clearNoMatch()
        }
        return result
    }

    internal fun screenScraperMetadataQuery(
        g: GameEntity,
        additionalCandidateNames: List<String> = emptyList(),
    ) = MetadataQuery(
        // ScreenScraper cleans release prefixes and region/tag groups before sending the name. The managed
        // cache still uses the raw ROM basename so LocalArtSource can resolve it.
        platformId = PlatformId(g.platformId),
        // ART02: the scan's persisted fingerprint rides every online lookup (hash-first privacy ladder —
        // the old query sent NOTHING but the raw name, which is what let cross-system fuzzy matches in).
        contentHash = contentHashFromStored(g.contentHash, g.sizeBytes),
        rawBaseName = baseName(g.displayName),
        // Candidate ladder: DAT canonical truth first, then the scan's cleaned title; the raw base name
        // stays the implicit last rung inside the source.
        candidateNames = (
            listOfNotNull(
                g.canonicalTitle?.takeIf { it.isNotBlank() },
                g.title.takeIf { it.isNotBlank() && it != g.canonicalTitle },
            ) + additionalCandidateNames
        ).map(String::trim).filter(String::isNotBlank).distinct(),
        providerGameId = ArtResolutionStore.resolvedGameIdFor(g.gameId),
    )

    /**
     * The same persisted identity ladder every explicit artwork surface must use. Home's render row
     * intentionally omits storage entities, so Custom Art/Hero asks here instead of falling back to the
     * visible label and losing DAT canonical truth.
     */
    suspend fun providerSearchTitles(
        context: Context,
        gameId: String,
        fallbackTitle: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val primary = MimirDatabaseFactory.get(context).gameDao().membersOfGame(gameId).firstOrNull()
        providerSearchTitleLadder(
            canonicalTitle = primary?.canonicalTitle,
            storedTitle = primary?.title,
            rawBaseName = primary?.displayName?.let(::baseName),
            fallbackTitle = fallbackTitle,
        )
    }

    private const val SteamStoreConnectTimeoutMs = 8_000
    private const val SteamStoreReadTimeoutMs = 15_000

    /** The storefront response carries several fields Mimir never models (screenshots/movies/price/etc.);
     *  bounded generously above `short_description`'s own size so a normal response is never rejected. */
    private const val MaxSteamStoreResponseBytes = 1L * 1024L * 1024L

    /**
     * The one call this route ever makes and the one persist it may trigger, run from
     * [fetchAndPersistSteamGridDbGame] on explicit ENRICH only (policy 2026-08-19). [appId] is only ever the exact id
     * [steamAppIdForManagedPath] parsed from this game's own `.steam` shortcut — never a title guess: a
     * name-resolved SteamGridDB entry carries no Steam identity, so that plan skips this step entirely.
     * Returns whether a description was durably cached, folded into [fetchOnePcArtwork]'s [OneResult]
     * exactly like ScreenScraper's own `metadataMatched` already is for every other platform.
     */
    private suspend fun fetchAndPersistSteamDescription(
        context: Context,
        g: GameEntity,
        appId: Int,
    ): Boolean {
        val outcome = fetchSteamStoreDescription(appId)
        val shortDescription = (outcome as? SteamStoreDescriptionOutcome.Found)?.shortDescription
            ?: return false
        return try {
            val database = MimirDatabaseFactory.get(context)
            VerifiedMetadataCache.persistVerifiedRecord(
                dao = database.titleMetadataCacheDao(),
                game = g,
                // Empty canonicalTitle: the shared persist function's own fallback ladder
                // (record title -> existing row -> game.canonicalTitle -> game.title) already prefers
                // local truth over inventing one here.
                record = MetadataRecord(
                    canonicalTitle = "",
                    origin = MetadataOrigin.STEAM,
                    description = shortDescription,
                ),
                gameIdentityDao = database.gameIdentityDao(),
                runTransaction = { block -> database.withTransaction { block() } },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LogTag, "Unable to persist verified Steam description", error)
            false
        }
    }

    /** One blocking storefront call + parse. Never throws; every failure mode is a typed outcome. */
    private fun fetchSteamStoreDescription(appId: Int): SteamStoreDescriptionOutcome {
        // Deliberately no redirect handling: a 3xx from this endpoint is not the pinned shape, so this
        // fails closed to Unreachable rather than chasing a Location header, unlike the media routes.
        val response = MimirProviderEgress.open(
            route = MimirProviderEgressRoute.STEAM_STORE_API,
            url = steamStoreAppDetailsUrl(appId),
            connectTimeoutMs = SteamStoreConnectTimeoutMs,
            readTimeoutMs = SteamStoreReadTimeoutMs,
            requestHeaders = mapOf("Accept" to "application/json"),
        ) ?: return SteamStoreDescriptionOutcome.Unreachable
        return try {
            if (response.statusCode != 200) return SteamStoreDescriptionOutcome.Unreachable
            if (!connectedArtContentLengthAllowed(response.declaredLength, MaxSteamStoreResponseBytes)) {
                return SteamStoreDescriptionOutcome.Unreachable
            }
            val body = readCustomHeroUtf8Bounded(response.body(), MaxSteamStoreResponseBytes)
                ?: return SteamStoreDescriptionOutcome.Unreachable
            parseSteamStoreAppDetails(appId, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SteamStoreDescriptionOutcome.Unreachable
        } finally {
            response.close()
        }
    }

    /**
     * ART02: run the (system-verified) lookup and persist the provider mapping the moment it succeeds —
     * BEFORE any download/write, because a verified resolution is durable truth regardless of whether
     * tonight's write lands. All fetch lanes go through here.
     */
    private suspend fun verifiedLookup(
        context: Context,
        g: GameEntity,
        additionalCandidateNames: List<String> = emptyList(),
    ): VerifiedMetadata? =
        (verifiedLookupOutcome(context, g, additionalCandidateNames) as? VerifiedLookupOutcome.Loaded)?.metadata

    private sealed interface VerifiedLookupOutcome {
        data class Loaded(val metadata: VerifiedMetadata) : VerifiedLookupOutcome
        data object Empty : VerifiedLookupOutcome
        /** No request was possible; never cached as a per-game negative. */
        data object PlatformUnsupported : VerifiedLookupOutcome

        /** The provider did not answer or refused. [reason] is what its answer says; null when no request was made (the gate closed). */
        data class Failed(val reason: ScreenScraperLookupFailure?) : VerifiedLookupOutcome
    }

    private suspend fun verifiedLookupOutcome(
        context: Context,
        g: GameEntity,
        additionalCandidateNames: List<String> = emptyList(),
        /** The meter this lookup is counted in: the Enrich meter by default, the game-music download's own for its step. */
        cost: EnrichCostAccumulator = enrichCost,
    ): VerifiedLookupOutcome {
        if (cost === enrichCost) lookups.incrementAndGet()
        val lookupStartedAt = cost.now()
        val outcome = ArtworkConnectController.source.lookupOutcome(
            screenScraperMetadataQuery(g, additionalCandidateNames),
        )
        cost.addLookup(cost.now() - lookupStartedAt)
        val record = when (outcome) {
            is ScreenScraperMetadataSource.LookupOutcome.Loaded -> outcome.record
            ScreenScraperMetadataSource.LookupOutcome.Empty -> return VerifiedLookupOutcome.Empty
            // No request was made, so there is nothing to learn about this game. Treating it as a miss
            // is what left every xbox/x360/ps3 title in a fourteen-day cooldown recorded before those
            // platforms had a system id -- a negative that outlived its own cause.
            ScreenScraperMetadataSource.LookupOutcome.PlatformUnsupported ->
                return VerifiedLookupOutcome.PlatformUnsupported
            ScreenScraperMetadataSource.LookupOutcome.Unreachable ->
                return VerifiedLookupOutcome.Failed(ScreenScraperLookupFailure.NO_ANSWER)
            is ScreenScraperMetadataSource.LookupOutcome.Failed -> return VerifiedLookupOutcome.Failed(outcome.reason)
            ScreenScraperMetadataSource.LookupOutcome.GateClosed -> return VerifiedLookupOutcome.Failed(null)
        }
            record.providerGameId?.let { ArtResolutionStore.record(g.gameId, it) }
            // Same already-authorized lookup, no new request: persist its trusted synopsis/provider
            // disposition before media I/O so Room can refresh Home even when no image writes.
            val cached = try {
                val database = MimirDatabaseFactory.get(context)
                ScreenScraperMetadataCache.persistVerifiedRecord(
                    dao = database.titleMetadataCacheDao(),
                    game = g,
                    record = record,
                    gameIdentityDao = database.gameIdentityDao(),
                    runTransaction = { block -> database.withTransaction { block() } },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LogTag, "Unable to persist verified ScreenScraper metadata", error)
                false
            }
        return VerifiedLookupOutcome.Loaded(VerifiedMetadata(record, cached))
    }

    /**
     * IGDB last-resort name-search synopsis. Runs
     * only from the two call sites above -- both explicit Enrich presses, both AFTER the exact-key
     * provider for this row (ScreenScraper for a ROM, the Steam storefront for a `.steam` shortcut)
     * already answered with nothing on the SAME press. Never bulk, never the hydration worker, never
     * scan or launch.
     *
     * Writes nothing to [ArtResolutionStore] or [ArtNoMatchStore], and never [ScreenScraperMetadataCache]
     * either: an IGDB id fed back as a ScreenScraper `providerGameId` would poison a later ScreenScraper
     * query, the exact same poisoning hazard [fetchAndPersistSteamGridDbGame]'s own kdoc names for
     * SteamGridDB. Persists through the provider-neutral [VerifiedMetadataCache], which enforces the
     * I1-I3 precedence rules (a stored exact-key synopsis always wins; a capped record with no expiry or
     * no synopsis is refused outright).
     */
    private suspend fun fetchAndPersistIgdbDescription(
        context: Context,
        g: GameEntity,
        candidateNames: List<String>,
    ): Boolean {
        val outcome = ArtworkConnectController.igdbSource.lookupOutcome(
            MetadataQuery(
                platformId = PlatformId(g.platformId),
                rawBaseName = baseName(g.displayName),
                candidateNames = candidateNames,
            ),
        )
        val mapped = outcome !is IgdbMetadataSource.LookupOutcome.PlatformUnsupported &&
            outcome !is IgdbMetadataSource.LookupOutcome.GateClosed
        val record = (outcome as? IgdbMetadataSource.LookupOutcome.Loaded)?.record
        val persisted = if (record == null) {
            false
        } else {
            try {
                val database = MimirDatabaseFactory.get(context)
                VerifiedMetadataCache.persistVerifiedRecord(
                    dao = database.titleMetadataCacheDao(),
                    game = g,
                    record = record,
                    gameIdentityDao = database.gameIdentityDao(),
                    runTransaction = { block -> database.withTransaction { block() } },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LogTag, "Unable to persist verified IGDB description", error)
                false
            }
        }
        // One log line per rung, no URLs and no credentials -- just the identification verdict.
        Log.i(
            LogTag,
            "igdb rung game=${g.gameId} platform=${g.platformId} mapped=$mapped " +
                "candidates=${candidateNames.size} outcome=${igdbOutcomeLogName(outcome)} persisted=$persisted",
        )
        return persisted
    }

    private fun igdbOutcomeLogName(outcome: IgdbMetadataSource.LookupOutcome): String = when (outcome) {
        is IgdbMetadataSource.LookupOutcome.Loaded -> "Loaded"
        IgdbMetadataSource.LookupOutcome.Empty -> "Empty"
        IgdbMetadataSource.LookupOutcome.PlatformUnsupported -> "PlatformUnsupported"
        IgdbMetadataSource.LookupOutcome.Unreachable -> "Unreachable"
        IgdbMetadataSource.LookupOutcome.Failed -> "Failed"
        IgdbMetadataSource.LookupOutcome.GateClosed -> "GateClosed"
    }

    private const val LogoSuffix = "-logo"
    private const val HeroSuffix = "-hero"
    private const val SupportSuffix = "-support"

    /** `media_art/<base>-audio.m4a`: the game's sound for the launch animation. Must match [LocalArtSource.audioUriFor]. */
    internal const val AudioSuffix = "-audio"
    private const val LaunchAudioStageDirectory = "launch-audio-stage"
    private const val LaunchAudioStaleStageMillis = 60L * 60L * 1_000L

    /**
     * Download clear-logo art for every owned game that lacks it. This deliberately mirrors the separate
     * preview-video lane: same closed-by-default gate, metadata source, download allowlist, cancellation, and
     * one-game failure isolation. Logos always use `<base>-logo.png`, including SAF documents.
     */
    suspend fun fetchAllFlatArt(context: Context, onProgress: (Progress) -> Unit = {}): FetchResult =
        withContext(Dispatchers.IO) {
            if (!MimirConnectedArtGate.isOpen) return@withContext FetchResult(0, 0)
            ArtworkConnectController.attach(context)
            ArtResolutionStore.attach(context)
            val gameDao = MimirDatabaseFactory.get(context).gameDao()
            val localArt = LocalArtSource(context)
            val games = gamesWithProviderCoverage(gameDao.primaryGamesForEnrichment())
            val preflight = SafWritePreflight(context)
            var matched = 0
            var writeSkipped = 0
            val changed = mutableListOf<ChangedMedia>()
            try {
                games.forEachIndexed { i, g ->
                    currentCoroutineContext().ensureActive()
                    runArtworkStep {
                        val already = localArt.logoUriFor(g.sourceDocumentUri, g.managedPath) != null
                        if (!already) {
                            if (!preflight.allowsWrite(g.sourceDocumentUri, g.managedPath)) {
                                writeSkipped++
                            } else {
                                val record = verifiedLookup(context, g)?.record
                                val write = fetchImage(
                                    context = context,
                                    g = g,
                                    url = record?.flatArtUri,
                                    slot = ManagedMediaSlot.LOGO,
                                    nameSuffix = LogoSuffix,
                                )
                                if (write.writeBlocked) writeSkipped++
                                if (write.written) {
                                    matched++
                                    changed += requireNotNull(write.target).asChangedMedia()
                                }
                            }
                        }
                    }
                    onProgress(Progress(done = i + 1, total = games.size, matched = matched))
                }
            } finally {
                publishWrittenMedia(context, changed)
            }
            if (writeSkipped > 0) {
                Log.w(LogTag, "flat-art batch: $writeSkipped game(s) skipped — library folder access is read-only")
            }
            FetchResult(total = games.size, matched = matched, writeBlocked = writeSkipped > 0 && matched == 0)
        }

    /** ~8 MB per image, enough for high-resolution PNG/WebP art without accepting unbounded responses. */
    internal const val MaxImageBytes = 8L * 1024 * 1024

    /**
     * ~12 MB per clip — plenty for a short "video-normalized" clip (about 1.7 MB measured); skip anything larger.
     * For launch sound, this is the cap [fetchLaunchAudioWith] streams a game's clip
     * under, on its own and not on [MaxImageBytes]. The clip goes to a `.part` file, never into memory.
     */
    internal const val MaxVideoBytes = 12L * 1024 * 1024
    private const val MaxMediaRedirects = 3

    /**
     * A gated + host-checked + HTTPS-only GET. Returns bytes on 200, else null. A declared oversized
     * Content-Length is refused up front and [readBounded] enforces the same cap for chunked/lying responses.
     * Never logs the URL (it carries creds).
     */
    internal fun downloadGated(url: String, maxBytes: Long): ByteArray? {
        downloads.incrementAndGet()
        if (maxBytes <= 0L) return null
        var current = url
        var redirects = 0
        return try {
            while (redirects <= MaxMediaRedirects) {
                if (!connectedArtMediaUrlAllowed(current)) return null
                val parsed = URI(current)
                if (!MimirConnectedArtGate.allowsRequestTo(parsed.host)) return null
                val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "MimirApk")
                }
                try {
                    val code = connection.responseCode
                    if (code in ConnectedArtRedirectStatusCodes) {
                        if (redirects == MaxMediaRedirects) return null
                        current = connectedArtMediaRedirectTarget(
                            currentUrl = current,
                            location = connection.getHeaderField("Location"),
                        ) ?: return null
                        redirects++
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK ||
                        !connectedArtContentLengthAllowed(connection.contentLengthLong, maxBytes)
                    ) {
                        return null
                    }
                    return connection.inputStream.use { readBounded(it, maxBytes) }
                } finally {
                    connection.disconnect()
                }
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [downloadGated]'s sibling for the launch sound's clip. The SAME host gate, HTTPS-only rule, redirect handling and
     * declared-length check (it calls the same helpers), but the body is streamed into [target] under its own cap instead of
     * being held in a byte array, so a clip of up to [MaxVideoBytes] never sits in memory. Never logs the URL (it carries
     * credentials). [downloadGated] itself is untouched.
     *
     * A clip that declares or streams past [maxBytes] is [ClipDownload.TooLarge] (a fact about that clip, worth a
     * negative); everything else that goes wrong is [ClipDownload.Unavailable] (says nothing about the clip). The partial
     * [target] of any non-[ClipDownload.Ok] result is deleted.
     *
     * [abort], when given, is told each connection this opens, so [downloadGatedToFileCancellable] can close it under a cancel.
     */
    internal fun downloadGatedToFile(url: String, maxBytes: Long, target: File, abort: ConnectionAbort? = null): ClipDownload {
        downloads.incrementAndGet()
        if (maxBytes <= 0L) return ClipDownload.Unavailable
        var current = url
        var redirects = 0
        return try {
            while (redirects <= MaxMediaRedirects) {
                if (!connectedArtMediaUrlAllowed(current)) return ClipDownload.Unavailable
                val parsed = URI(current)
                if (!MimirConnectedArtGate.allowsRequestTo(parsed.host)) return ClipDownload.Unavailable
                val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "MimirApk")
                }
                if (abort != null && !abort.attach(connection)) {
                    target.delete()
                    return ClipDownload.Unavailable
                }
                try {
                    val code = connection.responseCode
                    if (code in ConnectedArtRedirectStatusCodes) {
                        if (redirects == MaxMediaRedirects) return ClipDownload.Unavailable
                        current = connectedArtMediaRedirectTarget(
                            currentUrl = current,
                            location = connection.getHeaderField("Location"),
                        ) ?: return ClipDownload.Unavailable
                        redirects++
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) return ClipDownload.Unavailable
                    val declared = connection.contentLengthLong
                    if (declared > maxBytes) return ClipDownload.TooLarge
                    if (!connectedArtContentLengthAllowed(declared, maxBytes)) return ClipDownload.Unavailable
                    return connection.inputStream.use { copyBoundedToFile(it, maxBytes, target) }
                } finally {
                    connection.disconnect()
                }
            }
            ClipDownload.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            target.delete()
            ClipDownload.Unavailable
        }
    }

    /**
     * [downloadGatedToFile] that a cancel can stop: the clip's connection is closed from another thread the moment the caller is
     * cancelled, so a download parked on a dead network ends at once with a cancellation, never as an `Unavailable` the batch
     * would count as a failed game. This is the production seam of [fetchLaunchAudioWith].
     */
    internal suspend fun downloadGatedToFileCancellable(url: String, maxBytes: Long, target: File): ClipDownload {
        val abort = ConnectionAbort()
        return withContext(Dispatchers.IO) {
            runAbortingOnCancel(abort = abort::abort) { downloadGatedToFile(url, maxBytes, target, abort) }
        }
    }

    // ── The launch sound  ───────────────────────────────────────────────

    /** Where the clip streams to (`.part`) and is remuxed to (`.m4a`) before the sound is installed. Both are deleted. */
    private fun launchAudioStageDir(context: Context): File = File(context.cacheDir, LaunchAudioStageDirectory)

    /** A run killed mid-download leaves its `.part` behind; anything in the stage folder over an hour old is nobody's. */
    internal fun sweepLaunchAudioStage(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - LaunchAudioStaleStageMillis
            launchAudioStageDir(context).listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        }
    }

    /**
     * Removes everything in the stage folder. Only the game-music download calls it, at the end of its run: while it holds
     * [ArtworkOperationGate] it is the folder's only user, so every file left there is its own leftover (a cancelled step
     * deletes its own two files in its `finally`; this catches what a kill or a crash left behind).
     */
    internal fun clearLaunchAudioStage(context: Context) {
        runCatching { launchAudioStageDir(context).listFiles()?.forEach { if (it.isFile) it.delete() } }
    }

    /**
     * The game-music download's step for ONE game: a fresh verified `jeuInfos` lookup, then ONLY the sound step. It is
     * deliberately not [fetchArtworkForGame]: that body refreshes metadata, falls back to IGDB, and fetches every other
     * missing image, none of which a sound download may do.
     *
     * [forced] is the per-game press: it skips nothing the player asked for and records no game-level negative on a miss
     * (an explicit press is never a reason to cool a game down). The batch passes false. Either way this touches only this
     * game's negatives, and only the sound's own: `audio:` is recorded when the record carries no clip and by the step when
     * the clip cannot become a sound, and cleared by a written sound; the game-level "no ScreenScraper match" negative is
     * cleared when a verified lookup proves it wrong, exactly as the artwork path does. No other slot's negative is read
     * or written.
     *
     * Every failure is silent except in the result: a game with no usable sound writes nothing and the intro plays as it
     * did before. [cost] and [breaker] are the download's own, never the Enrich's.
     */
    internal suspend fun fetchGameMusicForGame(
        context: Context,
        g: GameEntity,
        localArt: LocalArtSource,
        forced: Boolean,
        cost: EnrichCostAccumulator,
        breaker: LaunchAudioBreaker,
    ): GameMusicStepResult = gameMusicStepWith(
        forced = forced,
        alreadyHasSound = { localArt.audioUriFor(g.sourceDocumentUri, g.managedPath) != null },
        lookup = {
            when (val outcome = verifiedLookupOutcome(context, g, cost = cost)) {
                is VerifiedLookupOutcome.Loaded -> GameMusicLookup.Loaded(outcome.metadata.record.previewVideoUri)
                VerifiedLookupOutcome.Empty -> GameMusicLookup.Empty
                VerifiedLookupOutcome.PlatformUnsupported -> GameMusicLookup.PlatformUnsupported
                is VerifiedLookupOutcome.Failed -> GameMusicLookup.Failed(outcome.reason?.let(::gameMusicFailureOfLookup))
            }
        },
        clearGameNegative = { ArtNoMatchStore.clear(g.gameId) },
        recordGameNegative = { ArtNoMatchStore.record(g.gameId) },
        recordNoClipNegative = { ArtNoMatchStore.record(artNoMatchSlotKey(ManagedMediaSlot.AUDIO, g.gameId)) },
        romPresent = { !SafWritePreflight(context).romMissing(g.sourceDocumentUri, g.managedPath) },
        cutSound = { clipUrl ->
            var stepOutcome: LaunchAudioStepOutcome? = null
            val write = fetchLaunchAudio(context, g, clipUrl, cost, breaker, onOutcome = { stepOutcome = it })
            GameMusicCut(changed = write.target?.asChangedMedia(), outcome = stepOutcome)
        },
    )

    internal suspend fun fetchLaunchAudio(
        context: Context,
        g: GameEntity,
        clipUrl: String,
        cost: EnrichCostAccumulator,
        breaker: LaunchAudioBreaker,
        onOutcome: (LaunchAudioStepOutcome) -> Unit = {},
    ): FinalMediaWrite {
        val key = artNoMatchSlotKey(ManagedMediaSlot.AUDIO, g.gameId)
        return fetchLaunchAudioWith(
            clipUrl = clipUrl,
            stageDir = launchAudioStageDir(context),
            download = ::downloadGatedToFileCancellable,
            probe = LaunchAudioRemux::probeAudio,
            copy = { input, output, maxDurationUs -> LaunchAudioRemux.remuxAacTrack(input, output, maxDurationUs) },
            transcode = { input, output, maxDurationUs -> LaunchAudioTranscode.transcodeToAac(input, output, maxDurationUs) },
            install = { bytes ->
                // The remuxed sound is a few hundred KB, so it is read into memory for the unchanged create-only
                // writeManagedArt path (plain file or SAF). Only the clip itself is large, and it never is.
                installDownloadedMediaAtCurrentGame(
                    context = context,
                    requestedGame = g,
                    bytes = bytes,
                    slot = ManagedMediaSlot.AUDIO,
                    nameSuffix = AudioSuffix,
                )
            },
            recordNegative = { ArtNoMatchStore.record(key) },
            clearNegative = { ArtNoMatchStore.clear(key) },
            cost = cost,
            breaker = breaker,
            onOutcome = onOutcome,
        )
    }

    /**
     * One game's launch sound, from its clip URL to an installed `<base>-audio.m4a`, with every seam injected so the whole
     * decision is testable without a network, a codec or a database.
     *
     * Stream the clip into a bounded `.part` file, look at its audio track ([probe]), make an AAC `.m4a` of the first
     * [LaunchAudioMaxDurationMs] of it, install the result create-only, delete both temp files. An AAC track is copied
     * ([copy], the fast path); any other audio track is decoded and re-encoded to AAC ([transcode], reported sample: 11 of
     * 12 clips were not AAC). **Every failure is silent**: nothing is written, nothing is shown, and the intro plays
     * as it did before. What each outcome records is [audioStepRecordsNegative]'s rule: a clip with no audio track, a track
     * the device has no decoder for, a file a codec or the muxer refused, or a clip over the cap records the slot's negative
     * (it will be the same next time); a refused or dropped download, a write failure and a breaker skip do not (they say
     * nothing about the clip). Only a written sound clears the negative.
     *
     * Never logs: the only things it reports are counts, durations, bytes and the source track's MIME type, through [cost].
     */
    internal suspend fun fetchLaunchAudioWith(
        clipUrl: String,
        stageDir: File,
        download: suspend (url: String, maxBytes: Long, target: File) -> ClipDownload,
        probe: (input: File) -> ClipAudioProbe,
        copy: (input: File, output: File, maxDurationUs: Long) -> LaunchAudioRemuxOutcome,
        transcode: (input: File, output: File, maxDurationUs: Long) -> LaunchAudioRemuxOutcome,
        install: suspend (ByteArray) -> FinalMediaWrite,
        recordNegative: () -> Unit,
        clearNegative: () -> Unit,
        cost: EnrichCostAccumulator,
        breaker: LaunchAudioBreaker,
        transcodeGate: TranscodeGate = launchAudioTranscodeGate,
        /** Told how the step ended, once, for a caller (the game-music download) that counts outcomes per game. */
        onOutcome: (LaunchAudioStepOutcome) -> Unit = {},
    ): FinalMediaWrite {
        if (breaker.tripped) {
            cost.audioOutcome(LaunchAudioStepOutcome.SkippedByBreaker)
            onOutcome(LaunchAudioStepOutcome.SkippedByBreaker)
            return FinalMediaWrite()
        }
        cost.audioClip()
        val stepStartedAt = cost.now()
        var write = FinalMediaWrite()
        var part: File? = null
        var sound: File? = null

        suspend fun step(): LaunchAudioStepOutcome {
            if (!stageDir.isDirectory) stageDir.mkdirs()
            if (!stageDir.isDirectory) return LaunchAudioStepOutcome.StepFailed
            val clip = File.createTempFile("clip", ".part", stageDir).also { part = it }
            val m4a = File.createTempFile("sound", ".m4a", stageDir).also { sound = it }

            val downloadStartedAt = cost.now()
            val downloaded = download(clipUrl, MaxVideoBytes, clip)
            val downloadMs = cost.now() - downloadStartedAt
            when (downloaded) {
                is ClipDownload.Ok -> cost.audioDownloaded(downloaded.bytes, downloadMs)
                ClipDownload.TooLarge -> {
                    cost.audioDownloadTime(downloadMs)
                    return LaunchAudioStepOutcome.TooLarge
                }
                ClipDownload.Unavailable -> {
                    cost.audioDownloadTime(downloadMs)
                    breaker.recordFailure()
                    return LaunchAudioStepOutcome.DownloadUnavailable
                }
            }
            breaker.recordSuccess()

            // Look at the clip's tracks, then take the path its audio decides: an AAC track is copied (the fast path), any
            // other audio track is decoded and re-encoded to AAC. Both keep only the first LaunchAudioMaxDurationMs.
            //
            // Only the transcode waits for a permit (transcodeGate): the download above and the copy stay fully parallel. The
            // time a lane spends waiting for a permit is not counted as the transcode's own time.
            val probeStartedAt = cost.now()
            val look = probe(clip)
            val probeMs = cost.now() - probeStartedAt
            var transcoded = false
            var pathMs = 0L
            val remuxed = when (look) {
                ClipAudioProbe.Unreadable -> LaunchAudioRemuxOutcome.Failed
                ClipAudioProbe.NoAudioTrack -> LaunchAudioRemuxOutcome.NoAudioTrack
                is ClipAudioProbe.Track -> {
                    cost.audioCodec(look.mime)
                    if (look.mime == LaunchAudioAacMime) {
                        val copyStartedAt = cost.now()
                        copy(clip, m4a, LaunchAudioMaxDurationUs).also { pathMs = cost.now() - copyStartedAt }
                    } else {
                        transcoded = true
                        transcodeGate.run {
                            val transcodeStartedAt = cost.now()
                            // Interruptible: a cancel stops the codec loop at its next turn instead of after the clip.
                            runInterruptible(Dispatchers.IO) { transcode(clip, m4a, LaunchAudioMaxDurationUs) }
                                .also { pathMs = cost.now() - transcodeStartedAt }
                        }
                    }
                }
            }
            if (transcoded) cost.audioTranscodeTime(probeMs + pathMs) else cost.audioRemuxTime(probeMs + pathMs)
            when (remuxed) {
                is LaunchAudioRemuxOutcome.Written -> if (transcoded) cost.audioTranscodedOne()
                LaunchAudioRemuxOutcome.NoAudioTrack -> return LaunchAudioStepOutcome.NoAudioTrack
                LaunchAudioRemuxOutcome.UnsupportedCodec -> return LaunchAudioStepOutcome.UnsupportedCodec
                LaunchAudioRemuxOutcome.Failed -> return LaunchAudioStepOutcome.RemuxFailed
                LaunchAudioRemuxOutcome.TransientFailure -> return LaunchAudioStepOutcome.TranscodeFailed
            }

            // A cancel that landed while the file was being cut stops here, before anything is written.
            currentCoroutineContext().ensureActive()
            write = install(m4a.readBytes())
            return when {
                write.written -> LaunchAudioStepOutcome.Written
                write.writeFailed -> LaunchAudioStepOutcome.WriteFailed
                else -> LaunchAudioStepOutcome.NotWritten
            }
        }

        val outcome = try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LaunchAudioStepOutcome.StepFailed
        } finally {
            part?.delete()
            sound?.delete()
        }
        cost.audioStepTime(cost.now() - stepStartedAt)
        cost.audioOutcome(outcome)
        onOutcome(outcome)
        if (audioStepRecordsNegative(outcome)) recordNegative()
        if (outcome == LaunchAudioStepOutcome.Written) clearNegative()
        return write
    }

    // ── What a batch cost (tag MimirEnrichCost) ──────────────────────────────────────────────────────

    /**
     * The one meter both Enrich batches report through: [fetchAllArtwork] (Settings Download, label `download`) and the
     * first-run card's batch in `MimirArtworkState.startNewGamesFetch` (label `first-run`). Always counting; only a
     * [beginEnrichBatch] zeroes it and only a [finishEnrichBatch] reads it out. Counts, durations and bytes only.
     *
     * The game-music download has a meter of its own ([GameMusicBatch.cost], label `game-music`), because this one is
     * process-global: two batches counting into it would each report the other's work. Under the Settings > Music
     * behavior neither Enrich batch fetches a sound, so the `audio*` figures of these two lines read zero.
     */
    internal val enrichCost = EnrichCostAccumulator()

    /** The process-wide bound on concurrent transcodes ([MaxConcurrentTranscodes]), shared by every batch and every lane. */
    internal val launchAudioTranscodeGate = TranscodeGate()

    internal const val EnrichBatchDownload = "download"
    internal const val EnrichBatchFirstRun = "first-run"

    /** The label of the game-music download's line (`MimirEnrichCost batch=game-music ...`). */
    internal const val EnrichBatchGameMusic = "game-music"

    /** Logcat tag of the per-batch summary. Nothing written under it carries a URL, a credential or a name. */
    internal const val EnrichCostLogTag = "MimirEnrichCost"

    /**
     * Starts an Enrich batch's meter. Call it AFTER the account's live quota has been read
     * ([ArtworkConnectController.refreshAccountMaxThreads] does that and keeps the connect card's `requestsToday`), so
     * "before" is the figure from just before the batch.
     */
    internal fun beginEnrichBatch(batch: String) {
        enrichCost.begin(batch, requestsBefore = ArtworkConnectController.lastRequestsToday)
    }

    /**
     * Logs the Enrich batch's one `MimirEnrichCost` line. Reads the connect card's `requestsToday` once
     * more for the "after" figure, which is ONE extra `ssuserInfos` request per batch; if that cannot be read the figure is
     * `unknown` and nothing else changes. [emit] and [readRequestsToday] are injectable for the test.
     */
    internal suspend fun finishEnrichBatch(
        context: Context,
        games: Int,
        matched: Int,
        readRequestsToday: suspend () -> Int? = { ArtworkConnectController.readRequestsToday(context) },
        emit: (String) -> Unit = ::logEnrichCostLine,
    ) = emitBatchCost(
        meter = enrichCost,
        games = games,
        matched = matched,
        audioBreakerTripped = false,
        readRequestsToday = readRequestsToday,
        emit = emit,
    )

    /**
     * The one place a batch's cost line is built and logged, for a [meter] of the caller's choosing: the Enrich batches
     * pass [enrichCost], the game-music download its own. The wall time is read BEFORE the quota request, so that request is
     * not counted as batch time.
     */
    internal suspend fun emitBatchCost(
        meter: EnrichCostAccumulator,
        games: Int,
        matched: Int,
        audioBreakerTripped: Boolean,
        readRequestsToday: suspend () -> Int?,
        emit: (String) -> Unit = ::logEnrichCostLine,
    ) {
        val wallMs = meter.wallMs()
        val after = try {
            readRequestsToday()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        emit(meter.summary(games = games, matched = matched, wallMs = wallMs, requestsAfter = after, audioBreakerTripped = audioBreakerTripped))
    }

    /** The one `Log` call of the cost line. Counts, durations and bytes only; no URL, credential or name can reach it. */
    private fun logEnrichCostLine(line: String) {
        Log.i(EnrichCostLogTag, line)
    }

    // fetchAllPreviewVideos was retired here . Enrich downloads Hero ART; a
    // moving backdrop stays a per-game choice in Customize Media, which still uses CustomHeroFetcher /
    // CustomHeroStore and is untouched. What went away is the LIBRARY-WIDE clip download and its
    // opt-in -- roughly MaxVideoBytes per title against a batch that was already the slowest thing the
    // product does. Nothing reads ManagedMediaSlot.CLIP in a bulk path any more.

    /** Read no more than [maxBytes] while streaming, including when Content-Length is absent or false. */
    internal fun readBounded(input: InputStream, maxBytes: Long): ByteArray? {
        if (maxBytes <= 0) return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray().takeIf(ByteArray::isNotEmpty)
            if (read == 0) continue
            if (output.size().toLong() + read > maxBytes) return null
            output.write(buffer, 0, read)
        }
    }

    /**
     * Batch write preflight for the SAF branch. `DocumentsContract.createDocument` on a tree whose
     * persisted grant is read-only throws `SecurityException` for EVERY game (seen live on the RP5
     * 2026-07-16: the library grant was persisted read-only, so writes were doomed before the first
     * download). One binder check per distinct tree — memoized — replaces one wasted download per game.
     * Plain-file locators pass: their writes are local and fail cheaply on their own.
     */
    internal class SafWritePreflight(
        private val context: Context,
        /** Whether a ROM document still resolves. Injected so the rule is tested without a provider or a file. */
        private val romPresent: (romDocumentUri: String) -> Boolean = { romDocumentPresent(context, it) },
    ) {
        private val writableTrees = mutableMapOf<String, Boolean>()
        private val romPresence = mutableMapOf<String, Boolean>()

        /**
         * True when the game is indexed through a `content://` ROM document that no longer resolves: its file or its folder
         * is gone from the device, while the library row (and the tree grant, which outlives the folder) remains. There is
         * nowhere to write beside it, so a download for it is thrown away. Asked once per document. A plain-file game is
         * never "missing" here: its write fails cheaply by itself. **Not** part of [allowsWrite], whose `false` the Enrich
         * card words as "folder access is read-only".
         */
        fun romMissing(sourceDocumentUri: String?, managedPath: String?): Boolean {
            if (managedPath != null) return false
            if (sourceDocumentUri == null || !sourceDocumentUri.startsWith("content://")) return false
            return !romPresence.getOrPut(sourceDocumentUri) { romPresent(sourceDocumentUri) }
        }

        fun allowsWrite(sourceDocumentUri: String?, managedPath: String?): Boolean {
            // Order matters: the plain-file answer must not touch [context] (FA-M5-style testability),
            // and a game with no writable destination shape is a guaranteed discard → skip its download.
            if (managedPath != null) return true
            if (sourceDocumentUri == null || !sourceDocumentUri.startsWith("content://")) return false
            if (allFilesRomPath(sourceDocumentUri) != null) return true // MANAGE_EXTERNAL_STORAGE lane
            val treeUri = safTreeUri(sourceDocumentUri) ?: return false
            return writableTrees.getOrPut(treeUri.toString()) { treeGrantsWrite(treeUri) }
        }

        private fun treeGrantsWrite(treeUri: Uri): Boolean = runCatching {
            context.contentResolver.persistedUriPermissions
                .any { it.uri == treeUri && it.isWritePermission } ||
                context.checkUriPermission(
                    treeUri,
                    Process.myPid(),
                    Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** The tree URI a SAF ROM document belongs to, or null for a non-tree-scoped locator. */
    internal fun safTreeUri(sourceDocumentUri: String): Uri? = runCatching {
        val uri = Uri.parse(sourceDocumentUri)
        DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()

    /**
     * The "All files access" write lane : a SAF-indexed game writes `media_art/` as plain
     * files when MANAGE_EXTERNAL_STORAGE is granted. Same create-only contract as the SAF lane —
     * [writeNewFile] never replaces an existing target.
     */
    private fun writeAllFilesManagedArt(
        romDocUri: String,
        displayName: String,
        bytes: ByteArray,
        overwrite: Boolean = false,
    ): Boolean {
        val romFile = allFilesRomPath(romDocUri) ?: return false
        val parent = romFile.parentFile ?: return false
        val managedDir = File(parent, ManagedArtDirectory)
        if (!managedDir.exists() && !managedDir.mkdirs()) return false
        return writeNewFile(File(managedDir, displayName), bytes, overwrite)
    }

    /**
     * The ROM's on-disk [File] when the all-files lane applies: MANAGE_EXTERNAL_STORAGE held, an
     * ExternalStorageProvider document (the only authority whose IDs are real paths), and the file exists.
     */
    internal fun allFilesRomPath(romDocUri: String): File? = allFilesRomCandidate(romDocUri)?.takeIf(File::isFile)

    /** Where the all-files lane says the ROM is, whether or not it is still there; null when the lane does not apply. */
    private fun allFilesRomCandidate(romDocUri: String): File? {
        if (!Environment.isExternalStorageManager()) return null
        val uri = runCatching { Uri.parse(romDocUri) }.getOrNull() ?: return null
        if (uri.authority != ExternalStorageAuthority) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return externalStorageDocFile(docId)
    }

    /**
     * Whether the ROM document still resolves: the file itself under All files access, otherwise one provider query for the
     * document (its id column, no children listed). A document the provider cannot find, or cannot answer for, is not there:
     * the tree grant that survives a deleted folder is exactly what must not pass this.
     */
    private fun romDocumentPresent(context: Context, romDocUri: String): Boolean {
        allFilesRomCandidate(romDocUri)?.let { return it.exists() }
        return runCatching {
            context.contentResolver.query(
                Uri.parse(romDocUri),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /** Pure ExternalStorageProvider docId → path mapping (`primary:…` / `<VOLUME-UUID>:…`). */
    internal fun externalStorageDocFile(docId: String): File? {
        val volume = docId.substringBefore(':', missingDelimiterValue = "")
        val relative = docId.substringAfter(':', missingDelimiterValue = "")
        if (volume.isEmpty() || relative.isEmpty()) return null
        if (relative.split('/').any { it == ".." }) return null
        val root = if (volume == "primary") {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }
        return File(root, relative)
    }

    private const val ExternalStorageAuthority = "com.android.externalstorage.documents"

    /**
     * Consecutive downloaded-then-unwritable games across one batch. A trip means the write layer is
     * failing systemically (grant revoked mid-run, card full, provider refusing creates) — the batch keeps
     * reporting progress but stops paying for downloads. Per-game misses (no match, no clip, download
     * refused) never count; only a completed download whose sibling write failed does.
     */
    internal class WriteFailureBreaker(private val limit: Int = MaxConsecutiveWriteFailures) {
        private var consecutive = 0

        var tripped = false
            private set

        fun recordSuccess() {
            consecutive = 0
        }

        fun recordFailure() {
            consecutive++
            if (consecutive >= limit) tripped = true
        }
    }

    /**
     * Create [bytes] under the managed `media_art/` directory next to the ROM. Plain-file sources create a
     * new file; SAF sources create a new document. A collision is a hard no-op, so neither user-owned
     * siblings nor pre-existing managed files can be overwritten.
     */
    internal fun writeManagedArt(
        context: Context,
        sourceDocumentUri: String?,
        managedPath: String?,
        romDisplayName: String,
        bytes: ByteArray,
        ext: String = "png",
        mime: String = "image/png",
        nameSuffix: String = "",
        overwrite: Boolean = false,
    ): Boolean = runCatching {
        val displayName = managedDisplayName(romDisplayName, nameSuffix, ext)
        when {
            managedPath != null -> {
                val parent = File(managedPath).parentFile ?: return false
                val managedDir = File(parent, ManagedArtDirectory)
                if (!managedDir.exists() && !managedDir.mkdirs()) return false
                writeNewFile(File(managedDir, displayName), bytes, overwrite)
            }
            sourceDocumentUri != null && sourceDocumentUri.startsWith("content://") -> {
                // "All files access" lane first : with MANAGE_EXTERNAL_STORAGE granted,
                // external-storage documents write as plain files — no tree WRITE grant needed (historical
                // picks persisted read-only, killing SAF createDocument). SAF stays the fallback.
                writeAllFilesManagedArt(sourceDocumentUri, displayName, bytes, overwrite) ||
                    writeSafManagedArt(context, sourceDocumentUri, displayName, bytes, mime, overwrite)
            }
            else -> false
        }
    }.getOrDefault(false)

    internal fun writeNewFile(
        target: File,
        bytes: ByteArray,
        overwrite: Boolean = false,
        move: (File, File, Boolean) -> Boolean = ::atomicMoveArtworkFile,
    ): Boolean {
        if (bytes.isEmpty() || (target.exists() && !overwrite)) return false
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val staged = File(
            parent,
            target.name + "." + System.nanoTime().toULong().toString(16) + ".mimir-stage",
        )
        staged.delete()
        return try {
            val written = runCatching {
                FileOutputStream(staged).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                staged.isFile && staged.length() == bytes.size.toLong()
            }.getOrDefault(false)
            if (!written || (!overwrite && target.exists())) return false
            move(staged, target, overwrite) &&
                target.isFile &&
                target.length() == bytes.size.toLong()
        } finally {
            staged.delete()
        }
    }

    private fun atomicMoveArtworkFile(from: File, to: File, overwrite: Boolean): Boolean =
        runCatching {
            if (overwrite) {
                Files.move(
                    from.toPath(),
                    to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            true
        }.getOrDefault(false)

    private fun writeSafManagedArt(
        context: Context,
        romDocUri: String,
        displayName: String,
        bytes: ByteArray,
        mime: String,
        overwrite: Boolean = false,
    ): Boolean {
        val uri = Uri.parse(romDocUri)
        val parentUri = resolveSafParent(context, uri) ?: return false
        val directory = findOrCreateSafDirectory(context, parentUri) ?: return false
        val existingChildren = safChildren(context, directory)
        val existing = existingChildren.firstOrNull { it.displayName == displayName }
        if (existing != null) {
            // DB01 verified-replace: SAF has no rename-over, so the stale document is removed only when
            // the caller holds VERIFIED replacement bytes in hand (a failed create below loses the old
            // image — accepted for the wrong-cover class; the plain-file lane keeps temp+rename safety).
            if (!overwrite) return false
            val deleted = runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, safChildUri(directory, existing))
            }.getOrDefault(false)
            if (!deleted) return false
        }
        val created = DocumentsContract.createDocument(context.contentResolver, directory, mime, displayName)
            ?: return false
        if (!createdChildHasName(context, directory, created, displayName)) {
            deleteCreatedSafDocument(context, created)
            return false
        }
        val output = context.contentResolver.openOutputStream(created)
        if (output == null) {
            deleteCreatedSafDocument(context, created)
            return false
        }
        return runCatching {
            output.use { it.write(bytes) }
            true
        }.getOrElse {
            deleteCreatedSafDocument(context, created)
            false
        }
    }

    /**
     * Find the ROM's actual parent without assuming a provider's document IDs encode a slash-separated path.
     * Hierarchical IDs get a verified fast path; generic providers fall back to a bounded tree walk.
     */
    private fun resolveSafParent(context: Context, romUri: Uri): Uri? {
        val romId = runCatching { DocumentsContract.getDocumentId(romUri) }.getOrNull() ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(romUri) }.getOrNull() ?: return null
        val treeUri = DocumentsContract.buildTreeDocumentUri(romUri.authority, treeId)
        val parentId = romId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentId.isNotEmpty()) {
            val candidate = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            if (safChildren(context, candidate).any { it.documentId == romId }) return candidate
        }
        return findSafParentInTree(context, treeUri, romId)
    }

    private fun findSafParentInTree(context: Context, treeUri: Uri, targetDocumentId: String): Uri? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val pending = ArrayDeque<Uri>().apply {
            add(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
        }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && visited.size < MaxSafParentSearchNodes) {
            val parent = pending.removeFirst()
            val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrNull() ?: continue
            if (!visited.add(parentId)) continue
            val children = safChildren(context, parent)
            if (children.any { it.documentId == targetDocumentId }) return parent
            children.filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
                .forEach { pending.add(safChildUri(parent, it)) }
        }
        return null
    }

    private fun findOrCreateSafDirectory(context: Context, parentUri: Uri): Uri? {
        val children = safChildren(context, parentUri)
        val existing = namedSafChild(children, ManagedArtDirectory)
        if (existing != null) {
            return if (existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                safChildUri(parentUri, existing)
            } else {
                null
            }
        }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            ManagedArtDirectory,
        ) ?: return null
        return if (createdChildHasName(
                context,
                parentUri,
                created,
                ManagedArtDirectory,
                DocumentsContract.Document.MIME_TYPE_DIR,
            )
        ) {
            created
        } else {
            deleteCreatedSafDocument(context, created)
            null
        }
    }

    private fun createdChildHasName(
        context: Context,
        parentUri: Uri,
        createdUri: Uri,
        expectedDisplayName: String,
        expectedMime: String? = null,
    ): Boolean {
        val createdId = runCatching { DocumentsContract.getDocumentId(createdUri) }.getOrNull() ?: return false
        return safChildren(context, parentUri).any {
            it.documentId == createdId &&
                it.displayName == expectedDisplayName &&
                (expectedMime == null || it.mimeType == expectedMime)
        }
    }

    private fun safChildren(context: Context, parentUri: Uri): List<SafChild> = runCatching {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) return@use emptyList()
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: continue
                    add(SafChild(documentId = id, displayName = name, mimeType = cursor.getString(mimeIndex)))
                }
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun safChildUri(parentUri: Uri, child: SafChild): Uri =
        DocumentsContract.buildDocumentUriUsingTree(parentUri, child.documentId)

    private fun deleteCreatedSafDocument(context: Context, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    internal data class SafChild(
        val documentId: String,
        val displayName: String,
        val mimeType: String?,
    )

    /** Select by provider-reported display name; document IDs may be arbitrary opaque tokens. */
    internal fun namedSafChild(children: List<SafChild>, displayName: String): SafChild? =
        children.firstOrNull { it.displayName == displayName }

    internal fun managedDisplayName(romDisplayName: String, nameSuffix: String = "", ext: String = "png"): String =
        "${baseName(romDisplayName)}$nameSuffix.$ext"

    internal inline fun <T> runArtworkStep(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

    /**
     * Non-cancellation failures from [runArtworkStep] are unexpected per-game fetch failures, never
     * an honest durable no-match. Cancellation is rethrown by [runArtworkStep] before a [Result]
     * exists.
     */
    internal fun artworkWriteResultFromStep(result: Result<ArtworkWriteResult>): ArtworkWriteResult =
        result.getOrElse { ArtworkWriteResult(lookupFailed = true) }

    /**
     * Semaphore-backed batch runner. Completion callbacks are serialized so progress/counters remain
     * coherent if the provider cap is raised later; publication always runs after success, failure, or
     * cancellation, and one bad item becomes an empty write result rather than aborting its siblings.
     */
    internal suspend fun <T, R> runBoundedArtworkBatch(
        items: List<T>,
        concurrency: Int,
        step: suspend (T) -> R,
        onCompleted: (T, R) -> Unit,
        publish: () -> Unit,
        /**
         * What one item's unexpected throw becomes. It is a parameter rather than a fixed empty result
         * because the new-game batch reports [OneResult], not [ArtworkWriteResult] — and a run that
         * swallowed a throw into "matched" would overcount silently.
         */
        recover: (Throwable) -> R,
    ) {
        require(concurrency >= 1)
        val semaphore = Semaphore(concurrency)
        val completionLock = Mutex()
        try {
            coroutineScope {
                items.map { item ->
                    async {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            val written = try {
                                step(item)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                recover(failure)
                            }
                            completionLock.withLock { onCompleted(item, written) }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            publish()
        }
    }

    private const val ManagedArtDirectory = "media_art"
    private const val MaxSafParentSearchNodes = 4_096

    /** Never logs URLs (they carry ScreenScraper creds) — counts and reasons only. */
    private const val LogTag = "MimirArtworkFetch"

    /**
     * The `pc_games` ENRICH identification line : one `Log.i` per press naming
     * the plan, the resolved SteamGridDB game segment and the verdict — the grep handle a device check
     * reads to tell "SteamGridDB said no" from "nothing was asked". Same no-URL, no-key discipline as [LogTag].
     */
    private const val PcLookupLogTag = "MimirArtFetch"

    /** Bail threshold for [WriteFailureBreaker]: at most ~5 × [MaxVideoBytes] is wasted before the batch stops. */
    internal const val MaxConsecutiveWriteFailures = 5

    /**
     * Local safety ceiling; the actual permit count never exceeds the live account's `ssuser.maxthreads`.
     * R6-P2-03 lever E, raised from 4 to 6 so a
     * multi-thread ScreenScraper account can use more of its own live allowance.
     */
    internal const val ArtworkBatchConcurrency = 6

    internal fun artworkBatchConcurrency(accountMaxThreads: Int?): Int =
        (accountMaxThreads ?: 1).coerceIn(1, ArtworkBatchConcurrency)

    /** R6-P2-03 lever B: consecutive UNREACHABLE/FAILED lookups before [fetchAllArtwork]'s batch stops. */
    internal const val MaxConsecutiveProviderFailures = 3

    /** R6-P2-03 lever F pace: the same polite delay [SteamGridDbTileFetcher] already uses between calls. */
    private const val PcArtworkPassDelayMs = 150L

    /**
     * After ScreenScraper, run the serial SteamGridDB PC
     * pass, one row at a time through the SAME per-game route every other PC Enrich press uses
     * ([fetchOneArtwork]) — nothing here is PC-specific, this is a generic paced walk that stops the
     * moment the provider itself fails (never presented as "not found"). [fetchOne]/[delayBetweenRows]
     * are injected so the loop is unit-testable without Room/Keystore/network.
     */
    internal suspend fun runSerialPcArtworkPass(
        gameIds: List<String>,
        delayBetweenRows: suspend () -> Unit,
        fetchOne: suspend (String) -> OneResult,
        onCompleted: (String, OneResult) -> Unit,
    ) {
        for (gameId in gameIds) {
            currentCoroutineContext().ensureActive()
            val result = fetchOne(gameId)
            onCompleted(gameId, result)
            if (result == OneResult.Failed) break
            delayBetweenRows()
        }
    }

    private fun baseName(fileName: String): String = fileName.substringBeforeLast('.')
}

internal fun providerSearchTitleLadder(
    canonicalTitle: String?,
    storedTitle: String?,
    rawBaseName: String?,
    fallbackTitle: String?,
): List<String> = listOfNotNull(canonicalTitle, storedTitle, fallbackTitle, rawBaseName)
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()

/**
 * How a `pc_games` row is identified on SteamGridDB for ENRICH — the one decision
 * `ArtworkFetchController.fetchOnePcArtwork` acts on, pure so it can be pinned without Room, Keystore or
 * network ( — the Library route gave up before asking whenever this decision would have been
 * [NameSearch]).
 *
 * R-23/R-25 : exact identification where an exact key exists,
 * name matching where it does not. [SteamApp] wins whenever a `.steam` shortcut supplied an app id, even
 * for a blank title (an exact key needs no name). [NameSearch] is the other four extensions and a
 * SAF-only row with no managed path: [NameSearch.candidates] are the [sgdbSearchCandidates] angles for
 * [NameSearch.title], the identity every autocomplete hit is validated against ([sgdbTitleMatches]).
 * [NoTitle] is a row with nothing to ask with — no app id and a title that cleans to nothing — and the
 * caller reports it as such ([ArtworkFetchController.OneResult.NoTitle]), never as a provider miss.
 */
internal sealed interface PcArtworkLookupPlan {
    data class SteamApp(val appId: Int) : PcArtworkLookupPlan
    data class NameSearch(val title: String, val candidates: List<String>) : PcArtworkLookupPlan
    data object NoTitle : PcArtworkLookupPlan
}

internal fun pcArtworkLookupPlan(steamAppId: Int?, title: String): PcArtworkLookupPlan {
    if (steamAppId != null) return PcArtworkLookupPlan.SteamApp(steamAppId)
    val candidates = sgdbSearchCandidates(title)
    if (candidates.isEmpty()) return PcArtworkLookupPlan.NoTitle
    return PcArtworkLookupPlan.NameSearch(title = title, candidates = candidates)
}

/**
 * Whether Enrich's unattributed re-check removes the logo it found: the slot held a file Mimir has no record of
 * ([replaceFlatArt]: present and unattributed, the DB01 rule) and the verified record has no clear logo to replace
 * it with.
 */
internal fun staleUnattributedLogoShouldClear(replaceFlatArt: Boolean, verifiedLogoUrl: String?): Boolean =
    replaceFlatArt && verifiedLogoUrl == null

private val ManagedLogoExtensions = setOf("png", "jpg", "jpeg", "webp")

/**
 * Whether [path] is a logo the enrich writes: `<base>-logo.<image ext>` whose parent folder is `media_art`. The
 * last guard before a delete, so it is deliberately narrow: nothing beside the ROM, no other slot, no backup folder.
 */
internal fun isManagedLogoPath(path: String): Boolean {
    val name = path.substringAfterLast('/')
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "").substringAfterLast('/')
    if (parent != "media_art") return false
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val base = name.substringBeforeLast('.', missingDelimiterValue = "")
    return ext in ManagedLogoExtensions && base.endsWith("-logo") && base.length > "-logo".length
}
