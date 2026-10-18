package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import com.gruning.mimir.rewrite.feature.companion.CompanionCoverArtWarmer
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.LibraryReadyGate
import com.gruning.mimir.rewrite.feature.library.LibraryWarmSignals
import com.gruning.mimir.rewrite.feature.library.MimirHomeStaticHeroDecodeHeightPx
import com.gruning.mimir.rewrite.feature.library.MimirHomeStaticHeroDecodeWidthPx
import com.gruning.mimir.rewrite.feature.library.PlatformCoverArtWarmer
import com.gruning.mimir.rewrite.feature.library.PlatformTileArtStore
import com.gruning.mimir.rewrite.feature.library.WarmupLedger
import com.gruning.mimir.rewrite.feature.library.WarmupTask
import com.gruning.mimir.rewrite.feature.library.mimirHomeStaticHeroCacheKey
import com.gruning.mimir.rewrite.core.ui.mimirCoverCacheKey
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import com.gruning.mimir.rewrite.core.ui.MimirCoverDecodeCapPx
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** The visible windows the warm pass decodes for — bounded so a huge library can't inflate the splash. */
internal const val WarmHomeRowCount = 6
private const val WarmGamelistCoverCount = 7
private const val WarmPlatformArtCount = 12

/** IA01: favorites covers warmed AFTER the gate settles (the view shows one cover at a time). */
private const val WarmFavoritesCoverCount = 3
private const val WarmFavoritesDelayMs = 1_800L

/** Returns only after the persisted override pass has published its final map, or after the bound. */
internal suspend fun awaitHydratedPlatformArt(
    hydrated: StateFlow<Boolean>,
    art: StateFlow<Map<String, String>>,
    timeoutMs: Long,
): Map<String, String> {
    withTimeoutOrNull(timeoutMs) { hydrated.first { it } }
    return art.value
}

/**
 * R01 "Land Warm" : registers the cold-start warm tasks the welcome lane now awaits
 * ([WarmupLedger]). Composes at the app root BEHIND the welcome mask (on a returning cold start, since O11b, one
 * handshake after the splash's first frame: SplashFirstContentMount.kt) — exactly like the
 * override-art hydration it rides alongside. Every decode here targets the EXACT cache identity the surface
 * itself uses (verified in the adversarial review), so the surfaces' own warm probes hit on first entry:
 *  - EVERY Coil cover surface (ledge/switch/gamelist/detail/companion/backdrop) shares the ONE PERF-B
 *    identity — `mimirCoverCacheKey(uri)` at the `MimirCoverDecodeCapPx` fit-within size;
 *  - the landing Home row's selected full-bleed hero is decoded at its exact 1080-class surface key;
 *  - the Library shelf + companion platform tiles read the two BitmapFactory LruCaches (via the warmers).
 */
/**
 * The warm request identities, hoisted to file scope by F2 (cold-launch settle audit, 2026-09-02).
 *
 * They were local functions inside [MimirColdStartWarmup]'s body. The return path needs the IDENTICAL
 * cache keys to rewarm with -- a request built any other way warms a different key and settles nothing --
 * so they are shared rather than restated. Behaviour is byte-identical to the locals they replace.
 */
internal fun mimirWarmCoverRequest(context: Context, uri: String): ImageRequest =
    mimirCoverRequest(context, uri)

internal fun mimirWarmStaticHeroRequest(context: Context, uri: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(MimirHomeStaticHeroDecodeWidthPx, MimirHomeStaticHeroDecodeHeightPx)
        .scale(Scale.FILL)
        .memoryCacheKey(mimirHomeStaticHeroCacheKey(uri))
        .placeholderMemoryCacheKey(mimirHomeStaticHeroCacheKey(uri))
        .crossfade(false)
        .build()

@Composable
internal fun MimirColdStartWarmup(
    homeShelfRows: List<HomeShelfRow>,
    repository: com.gruning.mimir.rewrite.core.model.library.LibraryRepository,
    // F1 (cold-launch settle audit, 2026-09-02): Home's own truth flag. The ledger's release inputs were
    // ALL Library-side -- LibraryReadyGate and firstGamelistRows -- so the mask lifted without ever
    // awaiting the landing surface's truth, and Home amended itself in full view straight afterwards.
    homeTruthReady: Boolean,
) {
    val context = LocalContext.current.applicationContext
    val rows = rememberUpdatedState(homeShelfRows)
    val homeTruth = rememberUpdatedState(homeTruthReady)
    // IA01 (adversarial trace risk 2): the favorites view (single side preview, Coil key = raw artUri) was
    // never warmed — a first favorites open decoded its focused cover cold unless it overlapped the landing
    // platform's top-7. Warm the first few favorites covers AFTER the gate settles (idle — never extends
    // the welcome hold); favorites are ordered most-recently-favorited-first, so first() is what lands.
    LaunchedEffect(Unit) {
        WarmupLedger.settled.first { it }
        kotlinx.coroutines.delay(WarmFavoritesDelayMs)
        val loader = context.imageLoader
        val favorites = runCatching { repository.observeFavorites().first() }.getOrNull().orEmpty()
        if (favorites.isEmpty()) return@LaunchedEffect
        var warmed = 0
        favorites.take(WarmFavoritesCoverCount).forEach { row ->
            row.artUri?.trim()?.takeIf { it.isNotEmpty() }?.let { uri ->
                runCatching {
                    loader.execute(
                        mimirCoverRequest(context, uri),
                    )
                }
                warmed++
            }
        }
        android.util.Log.i("MimirWarmup", "favorites warm: covers=$warmed")
    }
    LaunchedEffect(Unit) {
        val loader = context.imageLoader
        // O11b cold-start timeline: the ledger's begin on the one clock, so MimirColdSettle's "+ms after begin" places.
        MimirColdStartTrace.mark("ledger begin", "alreadySettled=${WarmupLedger.settled.value}")
        WarmupLedger.begin(
            listOf(
                // T1/T2 — rows + SAF art resolution: platforms ready AND the landing platform's gamelist
                // rows cached (LibraryViewModel's immediate warm also forces resolveSaf memoization).
                // F1 — Home's seat in the ledger. Budget-capped like every sibling, so a huge library
                // lands PARTIAL rather than hanging the mask; read through `snapshotFlow` exactly as
                // T-covers already reads `rows` below.
                WarmupTask(name = "homeTruth", label = "Settling your shelf…", budgetMs = 2_500L) {
                    snapshotFlow { homeTruth.value }.first { it }
                },
                WarmupTask(name = "rows", label = "Refreshing your library…", budgetMs = 4_000L) {
                    LibraryReadyGate.ready.first { it }
                    LibraryWarmSignals.firstGamelistRows.first { it != null }
                },
                // T3 — cover decodes for the visible windows. `execute` suspends until decoded, so task
                // completion genuinely means "art is in the memory cache".
                // PERF-B: both windows now share the ONE cover identity, so this single pass warms
                // every surface at once (capped decodes also finish well inside the budget).
                WarmupTask(name = "covers", label = "Binding covers…", budgetMs = 3_500L) {
                    val gamelist = LibraryWarmSignals.firstGamelistRows.first { it != null }.orEmpty()
                    val home = withTimeoutOrNull(1_500L) {
                        snapshotFlow { rows.value }.first { it.isNotEmpty() }
                    } ?: rows.value
                    coroutineScope {
                        val jobs = buildList {
                            home.take(WarmHomeRowCount).forEach { row ->
                                // The ladder the tile paints (one shelf face, 2026-10-10): the square, else the
                                // resolved face, else the cover. Reading only the first and the last left a
                                // hand-picked box or the automatic `pc_games` portrait to decode after the mask.
                                val uri = row.tileIconUri ?: row.faceUri ?: row.artworkUri
                                if (uri != null) {
                                    add(async { loader.execute(mimirWarmCoverRequest(context, uri)) })
                                }
                            }
                            home.firstOrNull()
                                ?.chosenHeroUri
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                                ?.let { uri -> add(async { loader.execute(mimirWarmStaticHeroRequest(context, uri)) }) }
                            gamelist.take(WarmGamelistCoverCount).forEach { game ->
                                // The face the shelf paints: his pick or the automatic tile ride `wiredFace`
                                // ahead of the cover, on every platform.
                                (game.wiredFace?.uri ?: game.artUri)?.trim()?.takeIf { it.isNotEmpty() }?.let { uri ->
                                    add(async { loader.execute(mimirWarmCoverRequest(context, uri)) })
                                }
                            }
                            // CPL01: the landing Library title uses the same custom-first, 1080-class Hero
                            // identity as the live gamelist. Decode it behind Welcome so the first platform
                            // open does not reveal the field and then pop the backdrop into place.
                            gamelist.firstOrNull()?.let { game ->
                                (CustomHeroStore.overrideUri(context, game.gameId) ?: game.heroArtUri)
                                    ?.trim()
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let { uri -> add(async { loader.execute(mimirWarmStaticHeroRequest(context, uri)) }) }
                            }
                        }
                        jobs.awaitAll()
                    }
                },
                // T4 — picked platform art into the two BitmapFactory caches (Library shelf + companion),
                // so the first Library entry and screen 2 paint the art on frame 0 (LS-01 P0).
                WarmupTask(name = "platformArt", label = "Setting the shelves…", budgetMs = 2_500L) {
                    val overrides = awaitHydratedPlatformArt(
                        hydrated = PlatformTileArtStore.hydrated,
                        art = PlatformTileArtStore.art,
                        timeoutMs = 1_200L,
                    )
                    overrides.values.take(WarmPlatformArtCount).forEach { uri ->
                        PlatformCoverArtWarmer.prewarm(uri)
                        CompanionCoverArtWarmer.prewarm(uri)
                    }
                },
            ),
        )
    }
}
