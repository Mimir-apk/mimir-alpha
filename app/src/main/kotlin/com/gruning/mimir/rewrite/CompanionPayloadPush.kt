package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.model.CompanionHomeShelfDetail
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfOpenLibraryKey
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.PlatformTileArtStore
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.homeShelfRowForKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Feed the SECONDARY-display companion (M7): remember the O(N) row set on the library shape (gated on a
 * LIVE companion — B2/WP-0B: single-screen devices never build it; the false→true re-key pushes one fresh
 * payload to a newly-attached screen), then push the selection-resolved payload. Extracted from the
 * MimirApp body (B12): the monolith had grown past an ART verifier edge (VerifyError on classes15.dex),
 * and the companion push is a self-contained concern anyway.
 *
 * "Opens with · X" (policy R13): the platform default comes free from the remembered launch-status
 * lines; a per-game override is resolved through the (suspend) launch controller AT MOST once per game —
 * the memo is keyed on the games list, so an override change (which refreshes the list) drops the stale
 * label. Bridge calls stay off the nav hot path .
 */
/**
 * Can [key] ever resolve to a Home-shelf row, and therefore justify holding a `Loading…` placeholder?
 *
 * The hold exists so a context-cycle gap never republishes the PREVIOUS game's identity on screen 2, and
 * is correct while a row snapshot is genuinely in flight. [HomeShelfOpenLibraryKey] is the empty-shelf
 * affordance ("OPEN LIBRARY"): it lives in `HomeShelfScope` so controller nav reaches it, but it is not a
 * row and no snapshot will ever arrive for it. Holding on it left the companion on `Loading…`
 * indefinitely — reproduced on Thor with `focusKey=home/empty/open-library` and an empty shelf.
 *
 * Keyed on the FOCUSED KEY, deliberately, not on a row count. An empty row list is also the ordinary
 * cold-start state, where a real row IS about to land; refusing the hold there would reintroduce the
 * identity flash the hold prevents.
 */
internal fun companionKeyCanBecomeShelfRow(key: FocusKey): Boolean = key != HomeShelfOpenLibraryKey

@Composable
internal fun CompanionPayloadPush(
    companionDisplayActive: StateFlow<Boolean>,
    detailGame: GameRow?,
    /** Pins the passive companion card while primary focus moves onto the launch-card action row. */
    openGameDetail: GameRow?,
    openPlatform: PlatformId?,
    games: List<GameRow>,
    platforms: List<PlatformRow>,
    nativeShelfSnapshot: NativeShelfSnapshot,
    shelfMode: LibraryShelfMode,
    favoriteSegment: FavoriteSegment,
    focusState: StateFlow<FocusState>,
    launchState: GameLaunchUiState,
    themeId: MimirThemeId,
    shelfLaunchStatusLines: Map<PlatformId, String>,
    launchController: LaunchController,
    //  the 6.1 anti-flash gate needs to tell a LOAD BEAT
    // (games not landed yet) from a user FILTER that legitimately emptied the list, and to know the
    // synthetic Favorites collection's size (it is never in `platforms`, so its count can't come from there).
    favoritesCount: StateFlow<Int>,
    searchQuery: StateFlow<String>,
    favoritesOnly: StateFlow<Boolean>,
    // FUX-E: the primary destination + the Home shelf projection + the row a Home launch is seeding (E6),
    // plus a repository handle for the focused row's members (size sum / disc lines, D3/D7).
    destination: MimirDestination,
    homeShelfRows: List<HomeShelfRow>,
    launchingRow: HomeShelfRow?,
    /** Round 5b: [launchingRow]'s game, so its seed can name the launcher; null for an Android row. */
    launchingGame: GameRow?,
    repository: LibraryRepository,
) {
    val companionDisplayActiveNow by companionDisplayActive.collectAsState()
    val favoritesCountNow by favoritesCount.collectAsState()
    val searchQueryNow by searchQuery.collectAsState()
    val favoritesOnlyNow by favoritesOnly.collectAsState()
    val focusStateNow by focusState.collectAsState()
    val runningAppNow by CompanionRunningAppState.running.collectAsState()
    val sessionStateNow by MimirSessionAuthority.state.collectAsState()
    val focusedKey = focusStateNow.focused
    // The user's filed 3DS-shelf order (S4) is library SHAPE, not selection: a drop re-keys this remember
    // and the home rebuilds once — never on a focus tick.
    val homeOrder by CompanionOrderStore.order.collectAsState()
    val homeFolders by CompanionOrderStore.folders.collectAsState()
    // user-picked SGB platform art (Y → change platform art). The heavy row mapping stays keyed on
    // library SHAPE only; the override is stamped OUTSIDE this remember (below) because folding it into the key here
    // races the display-active transition at startup — the remember can rebuild reading an empty map even as the
    // store is already populated (the primary Library tile shows the art but the companion tile wouldn't).
    val platformArtOverridesNow by PlatformTileArtStore.art.collectAsState()
    val platformArtHydratedNow by PlatformTileArtStore.hydrated.collectAsState()
    val companionRowsBase = remember(companionDisplayActiveNow, openPlatform, games, platforms, nativeShelfSnapshot, shelfMode, favoriteSegment, homeOrder, homeFolders) {
        if (!companionDisplayActiveNow) {
            null
        } else {
            companionRowSet(
                openPlatform = openPlatform,
                visibleGames = games,
                platforms = platforms,
                nativeSnapshot = nativeShelfSnapshot,
                shelfMode = shelfMode,
                favoriteSegment = favoriteSegment,
                homeOrder = homeOrder,
                homeFolders = homeFolders,
            )
        }
    }
    // Stamp the freshest override onto the home tiles every composition (cheap; a handful of tiles). The body read of
    // `platformArtOverridesNow` is reliable where the remember key wasn't — so the same pick that covers the primary
    // shelf tile covers screen 2 too. Reference-stable when the override is empty (returns `this`), so no extra push.
    val companionRows = companionRowsBase
        ?.takeIf { platformArtHydratedNow }
        ?.withPlatformArt(platformArtOverridesNow)
    val companionOverrideLabels = remember(games) { mutableMapOf<String, String?>() }
    // FUX-E: the focused Home row's disc members (size sum + disc lines) — fetched once per gameId and
    // cached so revisits are instant. Keyed on `games` (like companionOverrideLabels) so a library rescan
    // drops stale disc/size data instead of pinning it for the process lifetime (review #6/#9).
    // HP-3: the reclaim epoch re-keys this effect once per reclaim settle — without it an UNCHANGED
    // payload never re-pushes and the "Reconnecting…" hold decays into a stale pre-launch snapshot.
    val reclaimEpochNow by CompanionState.reclaimEpoch.collectAsState()
    // Round 5b: the Home launch seed's launcher line, one per launch. It is re-keyed on the launching row, so the push
    // below can re-publish its seed on any key change without dropping a label this launch already resolved. The
    // override read runs in its own scope: the push restarts on every key change, and clearing `launchingRow` once
    // the launch settles must not cancel a read still in flight.
    val homeSeedLauncherLine = remember(launchingRow) { CompanionSeedLauncherLine() }
    val seedLauncherScope = rememberCoroutineScope()
    LaunchedEffect(
        companionDisplayActiveNow,
        companionRows,
        detailGame,
        openGameDetail,
        focusedKey,
        focusStateNow.scope,
        launchState,
        themeId,
        favoritesCountNow,
        searchQueryNow,
        favoritesOnlyNow,
        shelfLaunchStatusLines,
        reclaimEpochNow,
        runningAppNow,
        sessionStateNow,
        destination,
        homeShelfRows,
        launchingRow,
        launchingGame,
    ) {
        if (!companionDisplayActiveNow || companionRows == null) return@LaunchedEffect
        // A transferred Android app owns Main. Publish before either library anti-flash return so a stale shelf
        // loading beat cannot suppress RUNNING_APP. This also wins over stale game launch state by contract.
        if (runningAppNow != null) {
            CompanionState.set(
                buildCompanionPayload(
                    rowSet = companionRows,
                    platforms = platforms,
                    detailGame = detailGame,
                    focusedKey = focusedKey,
                    launchState = launchState,
                    themeId = themeId.storageKey,
                    themeSigilRef = null,
                    runningApp = runningAppNow,
                ),
            )
            return@LaunchedEffect
        }
        // A reclaim can re-key this effect after the Main game has already resumed. Keep its exact
        // retained hero ahead of the HOME branch; the identity/phase check prevents stale cards after exit.
        if (companionNowPlayingReclaimGuard(
                sessionStateNow.gameSession,
                CompanionState.lastNowPlayingGameId(),
            )
        ) {
            // The card may already be stable; in that case republish returns false, but HOME must still
            // be skipped for this effect run.
            CompanionState.republishRetainedNowPlaying()
            return@LaunchedEffect
        }
        //  opening a platform flips `openPlatform`
        // a beat BEFORE its games list loads; pushing that in-between frame flashed "no games" on the
        // companion. Hold the previous payload ONLY while the emptiness could be that load beat — never
        // when the user's own filter legitimately emptied the list, which must push an honest empty.
        //   #2: the synthetic Favorites view is never in `platforms`, so its expected size comes from
        //       favoritesCount, not a platform gameCount (which would resolve 0 and flash every open).
        //   #3: `games` is search- AND favorites-filtered; a no-match search or an X-filter with zero
        //       favorites empties it legitimately. Gating on those flags stops the gate from freezing
        //       the companion on stale content for as long as the filter stays empty.
        val expectedGames = when (openPlatform) {
            null -> 0
            LibraryViewModel.FAVORITES_PLATFORM_ID -> favoritesCountNow
            else -> platforms.firstOrNull { it.platformId == openPlatform }?.gameCount ?: 0
        }
        val couldBeLoadBeat = searchQueryNow.isBlank() && !favoritesOnlyNow
        if (openPlatform != null && games.isEmpty() && expectedGames > 0 && couldBeLoadBeat) {
            // D1-R1: opening a platform no longer pushes a loading mirror to screen 2.
            return@LaunchedEffect
        }
        // V7 (review 2026-07-11): a shoulder-hop flips `openPlatform` a beat before observeGames(next) lands,
        // so `games` still holds the OLD platform's (non-empty) rows under the NEW id — the empty-only gate
        // above misses it and screen 2 flashes NES-labelled SNES content. Every row in a non-favorites
        // gamelist shares openPlatform, so a first row tagged with a different platform is the pre-hop stale
        // beat; hold the prior payload until the new list lands.
        if (openPlatform != null &&
            openPlatform != LibraryViewModel.FAVORITES_PLATFORM_ID &&
            games.isNotEmpty() &&
            games.first().platformId != openPlatform
        ) {
            // D1-R1: opening a platform no longer pushes a loading mirror to screen 2.
            return@LaunchedEffect
        }
        // FUX-E: on the HOME destination the focused shelf row becomes the two-zone detail card (E3/E4).
        // Sits BELOW the RUNNING_APP + library load-beat gates above (all no-ops on Home — openPlatform is
        // null there). No focused row → homeShelfFocus is null and the payload falls to today's HOME lanes.
        if (destination == MimirDestination.HOME) {
            val launching = launchingRow
            if (launching != null) {
                // G-2 seed (review #2/#3): emit NOW_PLAYING(loading) DIRECTLY from the LAUNCHING row —
                // synchronous, pure, no repository fetch, and independent of where focus sits — so a slow
                // member fetch or a fast post-CONFIRM nav can never drop the seed CompanionState captures
                // into lastNowPlaying for the armed-owner republish.
                // Round 5b: published through the launcher line, which names the launcher the Library names, still
                // synchronously. An override's label is read after the seed and lands on the retained card.
                homeSeedLauncherLine.publish(
                    seed = homeLaunchSeedPayload(launching, themeId.storageKey, platforms = platforms),
                    game = launchingGame,
                    shelfLaunchStatusLines = shelfLaunchStatusLines,
                    resolutionScope = seedLauncherScope,
                    resolveOpenWith = { game -> launchController.openWithOptions(game.gameId, game.platformId) },
                )
                return@LaunchedEffect
            }
            // **The focused-row chain was removed 2026-08-30 after review.** D1-R1 deleted the two-zone
            // card this resolved, but left the resolution running: every focus move on the Home shelf did a
            // synchronous identity build and then an async `buildHomeShelfFocus` -- a Room read of the game,
            // plus a member read for multi-disc titles -- to fill a `homeShelfFocus` parameter that
            // `buildCompanionPayload` no longer looks at. HOME does not vary with the focused row, so the
            // whole chain produced a value-identical payload at the cost of per-focus I/O.
            fun homePayload() = buildCompanionPayload(
                    rowSet = companionRows,
                    platforms = platforms,
                    detailGame = null,
                    focusedKey = focusedKey,
                    launchState = launchState,
                    themeId = themeId.storageKey,
                    themeSigilRef = null,
                    destination = destination,
                )
            fun publishHome() = CompanionState.set(homePayload())
            // Both arms produced the same HOME payload once the focus chain went; the branch itself
            // was only ever there to decide which flavour of detail card to resolve.
            publishHome()
            return@LaunchedEffect
        }
        val selectedGame = companionSelectedOrOpenGame(
            rowSet = companionRows,
            openGameDetail = openGameDetail,
            detailGame = detailGame,
            focusedKey = focusedKey,
        )
        val selectedLaunchState = companionLaunchStateForSelection(
            selectedGame = selectedGame,
            detailGame = detailGame,
            openGameDetail = openGameDetail,
            launchState = launchState,
        )
        val effectiveLaunchState = if (selectedGame == null || sessionStateNow.permitsGamePresentation(selectedGame.gameId)) {
            selectedLaunchState
        } else {
            GameLaunchUiState.Idle
        }
        val libraryLaunchLoading = effectiveLaunchState == GameLaunchUiState.Launching ||
            effectiveLaunchState == GameLaunchUiState.IntentSent
        // Library follows the same identity-first contract as Home. Publish title/art/platform synchronously,
        // before override or member reads, then enrich under this LaunchedEffect's cancellation epoch.
        if (selectedGame != null && !libraryLaunchLoading) {
            val identityPayload = buildCompanionPayload(
                    rowSet = companionRows,
                    platforms = platforms,
                    detailGame = selectedGame,
                    focusedKey = focusedKey,
                    launchState = effectiveLaunchState,
                    themeId = themeId.storageKey,
                    themeSigilRef = null,
                    launcherLabel = companionLauncherLabel(shelfLaunchStatusLines[selectedGame.platformId]),
                )
            identityPayload
                ?.let(CompanionState::set)
        }
        // Round 5b follow-up: an override that is not installed, or a read that fails, names NO launcher. The
        // platform default this used to fall back to is not what that launch starts (companionLibraryLauncherLabel).
        val launcherLabel = selectedGame?.let { game ->
            companionLibraryLauncherLabel(game, shelfLaunchStatusLines, companionOverrideLabels) {
                launchController.openWithOptions(it.gameId, it.platformId)
            }
        }
        // The per-focus member read that built the browsing two-zone detail was removed 2026-08-30:
        // D1-R1 retired the card, and `buildCompanionPayload` stopped reading the parameter it fed.
        // A focus-keyed effect may have been canceled while resolving an override/member row. Never let its
        // enriched A payload overwrite the identity-first B commit that canceled it.
        coroutineContext.ensureActive()
        CompanionState.set(
            buildCompanionPayload(
                rowSet = companionRows,
                    platforms = platforms,
                detailGame = selectedGame,
                focusedKey = focusedKey,
                launchState = effectiveLaunchState,
                themeId = themeId.storageKey,
                // No sigil ASSET ships (companion review correction #3) — the companion draws the
                // palette-driven ᛗ mark instead (policy R10); the payload field stays reserved.
                themeSigilRef = null,
                launcherLabel = launcherLabel,
            ),
        )
    }
}

internal suspend fun companionOverrideLabel(
    fallback: String?,
    resolve: suspend () -> String?,
): String? = try {
    resolve() ?: fallback
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    fallback
}


/** Launch feedback belongs to the game that armed it, never to a newer direct-focus identity. */
internal fun companionLaunchStateForSelection(
    selectedGame: GameRow?,
    detailGame: GameRow?,
    openGameDetail: GameRow?,
    launchState: GameLaunchUiState,
): GameLaunchUiState {
    val launchOwner = openGameDetail?.gameId ?: detailGame?.gameId
    return launchState.takeIf { selectedGame?.gameId != null && selectedGame.gameId == launchOwner }
        ?: GameLaunchUiState.Idle
}
internal fun companionNowPlayingReclaimGuard(
    game: MimirGameSession?,
    retainedGameId: GameId?,
): Boolean = companionMainDualScreenGameActive(game) &&
    game?.gameId == retainedGameId

internal fun companionMainDualScreenGameActive(game: MimirGameSession?): Boolean = game != null &&
    game.placement == MimirGamePlacement.MAIN &&
    game.dualScreen &&
    game.phase in setOf(
        MimirSessionPhase.DISPATCHED,
        MimirSessionPhase.FOREGROUND_PROVEN,
        MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    )
