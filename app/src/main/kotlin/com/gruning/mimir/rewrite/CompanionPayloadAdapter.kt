package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeShelfDetail
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.CompanionShelfRow
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.resolver.library.PlatformLabels
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.gameLaunchSessionHeldRailLabel
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import com.gruning.mimir.rewrite.feature.library.formatSizeLabel
import com.gruning.mimir.rewrite.feature.library.relativePlayed

/**
 * THE primary→companion payload adapter — extracted from the MimirApp monolith (B11 / companion review
 * WP-4 F10) so the mode selection, row mapping, and status vocabulary are PURE functions with table-driven
 * unit tests ([CompanionPayloadAdapterTest]). This is the layer whose bugs (F1/F8/F9) shipped unnoticed
 * while it was a `private fun` in a ~2000-LOC composable file.
 *
 * Two stages on purpose (WP-0C decouple): [companionRowSet] does the O(N) row mapping and is keyed on the
 * LIBRARY SHAPE only, so the host `remember`s it; [buildCompanionPayload] resolves the SELECTION (focus
 * tick, launch state) over the precomputed rows — a pure focus move never re-maps rows.
 *
 * Stays in the app module deliberately: the inputs are feature-library types (GameRow, shelf modes,
 * GameLaunchUiState), so a feature-companion home would drag feature-library into the display-only module.
 */

/** Which surface the precomputed rows mirror (drives [buildCompanionPayload]'s mode selection). */
internal enum class CompanionRowKind { NATIVE_FAVORITES, OPEN_PLATFORM, SHELF }

/**
 * The selection-independent half of the payload: mode context + mapped rows + static labels. Rebuilt only
 * when the library SHAPE changes (shelf mode, platform, games list, native snapshot, favorites segment) —
 * never on a focus tick.
 */
internal data class CompanionRowSet(
    val kind: CompanionRowKind,
    val rows: List<CompanionShelfRow>,
    val statsLabel: String? = null,
    val statusLabel: String? = null,
    val shelfModeLabel: String? = null,
    val openPlatform: PlatformId? = null,
    val openPlatformLabel: String? = null,
    /** OPEN_PLATFORM only: the games behind [rows], for detail-card selection. */
    val visibleGames: List<GameRow> = emptyList(),
    /** SHELF/NATIVE_FAVORITES: the static 3DS-shelf HOME lanes (HP-11 Slice 1). */
    val homeLanes: List<CompanionHomeLane> = emptyList(),
    /**
     * The stats line the RESTING HOME wears, when it differs from [statsLabel].
     *
     * OPEN_PLATFORM's [statsLabel] is the gamelist header — *"Dreamcast · 1 game"* — and NATIVE_FAVORITES's is the segment's ("3 apps"): lines
     * screen 1 is already showing, and putting it on the shelf would reintroduce the mirror D1-R1
     * retired by the back door. Null on the SHELF route, whose [statsLabel] already is the shelf's line.
     */
    val homeStatsLabel: String? = null,
)

/**
 * stamp the user-picked SGB platform art (Y → change platform art) onto the home-lane PLATFORM
 * tiles so the companion covers them exactly like the primary Library tile. Applied at the PUSH's point of use —
 * NOT inside the remembered [companionRowSet] — because that remember can race the display-active transition at
 * startup and capture an empty override even as the store is already populated. Recomputing here from the freshly
 * collected map (cheap: a handful of tiles) sidesteps the race. Keyed by `platformId.value`; recurses into tomes.
 */
internal fun CompanionRowSet.withPlatformArt(overrides: Map<String, String>): CompanionRowSet {
    if (overrides.isEmpty() || homeLanes.isEmpty()) return this
    return copy(homeLanes = homeLanes.map { lane -> lane.copy(tiles = lane.tiles.map { it.stampPlatformArt(overrides) }) })
}

private fun CompanionHomeTile.stampPlatformArt(overrides: Map<String, String>): CompanionHomeTile = when (kind) {
    CompanionHomeTileKind.PLATFORM -> overrides[id]?.let { copy(artPath = it) } ?: this
    CompanionHomeTileKind.FOLDER -> copy(children = children.map { it.stampPlatformArt(overrides) })
    CompanionHomeTileKind.ANDROID_APP -> this
}

/** Precompute the mirror rows + static labels for the current library shape (the heavy O(N) stage). */
internal fun companionRowSet(
    openPlatform: PlatformId?,
    visibleGames: List<GameRow>,
    platforms: List<PlatformRow>,
    nativeSnapshot: NativeShelfSnapshot,
    shelfMode: LibraryShelfMode,
    favoriteSegment: FavoriteSegment,
    /** The user's filed 3DS-shelf order (S4). Empty = the default order stands. */
    homeOrder: List<String> = emptyList(),
    /** The tomes the user bound (S6). */
    homeFolders: List<CompanionFolder> = emptyList(),
): CompanionRowSet {
    if (openPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID && favoriteSegment != FavoriteSegment.GAMES) {
        val nativeRows = when (favoriteSegment) {
            FavoriteSegment.GAMES -> emptyList()
            FavoriteSegment.ANDROID_GAMES -> nativeSnapshot.games.filter { it.isFavorite }
            FavoriteSegment.ANDROID_APPS -> nativeSnapshot.apps.filter { it.isFavorite }
        }
        val rows = nativeRows.map(::companionNativeRow)
        return CompanionRowSet(
            kind = CompanionRowKind.NATIVE_FAVORITES,
            rows = rows,
            statsLabel = companionNativeStatsLabel(favoriteSegment, rows.size),
            statusLabel = "Favorites",
            shelfModeLabel = favoriteSegment.label,
            homeLanes = buildCompanionHomeLanes(platforms, nativeSnapshot, homeOrder, homeFolders),
            // The resting HOME wears the shelf's line, not the favorites segment's ("3 apps"): the Bottom's lattice header reads it, and
            // the lattice is the platform shelf whichever segment Main is browsing.
            homeStatsLabel = companionShelfStatsLabel(LibraryShelfMode.EMULATION, platforms, nativeSnapshot),
        )
    }

    if (openPlatform != null) {
        val openPlatformLabel = platformLabelForCompanion(openPlatform, platforms)
        return CompanionRowSet(
            kind = CompanionRowKind.OPEN_PLATFORM,
            rows = visibleGames.map(::companionGameRow),
            statsLabel = listOfNotNull(openPlatformLabel, plural(visibleGames.size, "game")).joinToString(" · "),
            shelfModeLabel = openPlatformLabel,
            openPlatform = openPlatform,
            openPlatformLabel = openPlatformLabel,
            visibleGames = visibleGames,
            // The Library route rests at HOME, not IDLE. Every needed input is already a parameter of this
            // function, and the two sibling branches above and below make the same call.
            homeLanes = buildCompanionHomeLanes(platforms, nativeSnapshot, homeOrder, homeFolders),
            homeStatsLabel = companionShelfStatsLabel(LibraryShelfMode.EMULATION, platforms, nativeSnapshot),
        )
    }

    // Slice 1 (HP-11): the resting surface is the static HOME — stats pin to the EMULATION shape so a
    // shelf-lane swap on the primary doesn't repaint the (unchanged) home on screen 2.
    return CompanionRowSet(
        kind = CompanionRowKind.SHELF,
        rows = emptyList(),
        statsLabel = companionShelfStatsLabel(LibraryShelfMode.EMULATION, platforms, nativeSnapshot),
        homeLanes = buildCompanionHomeLanes(platforms, nativeSnapshot, homeOrder, homeFolders),
    )
}

/**
 * Resolve the SELECTION over a precomputed [rowSet] into the payload the companion renders (the cheap
 * stage — no row mapping).
 *
 * Mode selection, faithfully carried over from the monolith with ONE dead branch removed (critique-panel
 * extra): the old open-platform LIBRARY_MIRROR branch was unreachable — [companionSelectedShelfId] always
 * falls back to the first row, so non-empty rows ALWAYS resolve a detail game.
 *
 * **Corrected 2026-08-30 (review):** this said an open platform "yields GAMELIST_DETAIL or IDLE". D1-R1
 * retired that mode in the same change that left the sentence behind. An open platform now yields
 * **NOW_PLAYING while a launch is in flight, and IDLE otherwise** -- browsing no longer mirrors the
 * focused row. The IDLE rest is D1-R25's declared interim; D1-R2's answer is HOME, and reaching it needs
 * home lanes plumbed onto this branch, which builds none.
 */
internal fun buildCompanionPayload(
    rowSet: CompanionRowSet,
    // R02: friendly platform labels for the NOW_PLAYING card (defaulted so existing callers/tests are unchanged).
    platforms: List<PlatformRow> = emptyList(),
    detailGame: GameRow?,
    focusedKey: FocusKey?,
    launchState: GameLaunchUiState,
    themeId: String,
    themeSigilRef: String? = null,
    launcherLabel: String? = null,
    runningApp: CompanionRunningApp? = null,
    // FUX-E: the primary navigation destination. `homeShelfFocus` and `libraryGameDetail` used to ride
    // alongside it; **both were removed 2026-08-30 after review found them write-only** -- D1-R1 deleted
    // their only consumers (the two detail-card branches) and left the parameters, so callers were still
    // paying Room reads to build values this function never looked at.
    destination: MimirDestination = MimirDestination.LIBRARY,
): CompanionPayload {
    if (runningApp != null) {
        return companionRunningAppPayload(
            app = runningApp,
            themeId = themeId,
            themeSigilRef = themeSigilRef,
        )
    }
    val launchLoading = launchState == GameLaunchUiState.Launching ||
        launchState == GameLaunchUiState.IntentSent

    // **RETIRED by policy D1-R1, 2026-08-30.** This returned a two-zone detail card mirroring the
    // focused HOME shelf row -- a row screen 1 is already showing, which is the test that retires it.
    // Removing it is the whole change: control now reaches the SHELF branch's HOME lanes below, which
    // is what this block's own comment always said would happen, and HOME is the mode D1-R1 kept and
    // promoted. `homeShelfFocus` is still a parameter because the loading path still carries it.

    when (rowSet.kind) {
        CompanionRowKind.OPEN_PLATFORM -> {
            val selectedGame = companionSelectedGame(rowSet, detailGame, focusedKey)
            if (selectedGame != null) {
                val statusLabel = companionGameStatusLabel(launchState, selectedGame)
                // NOW_PLAYING (policy R11 / companion review F1): while a launch is in flight or the
                // game is away running (dispatch-only claim — the state resets on return), screen 2 shows
                // the hero card instead of the browsing detail. DS/3DS titles never reach here: their
                // screen-2 host suppresses the companion via the B11 FSM before any payload is consumed.
                // The launch card is primary-only on every device. Screen 2 remains a display-only browsing
                // detail until launch state promotes it to NOW_PLAYING.
                // **D1-R1, 2026-08-30: browsing no longer mirrors.** This chose between two detail cards
                // whenever a launch was not in flight; both are retired, because the gamelist row they
                // drew is a row screen 1 is already showing.
                //
                // **A launch still promotes to NOW_PLAYING** -- that mode was KEPT, and it is the one
                // thing screen 2 can uniquely do while an emulator owns the primary.
                //
                // **Browsing rests at HOME (D1-R25, re-ruled 2026-08-30).** Not a second copy of the
                // resting logic -- the SAME `companionRestingPayload` the shelf route returns, so the
                // empty-lane guard exists once and an empty 3x3 grid is structurally unreachable here.
                // It also means the Library rest and the Home rest are the same VALUE, which the
                // StateFlow deduplicates: walking the library does not repaint screen 2.
                if (!launchLoading) return companionRestingPayload(rowSet, focusedKey, themeSigilRef, themeId, launchLoading)
                val mode = CompanionMode.NOW_PLAYING
                // Outside the gamelist, prefer flat art without a platform reference: the clear logo is preferred on BOTH companion game surfaces — the browsing
                // detail and NOW_PLAYING. The box cover remains only as the no-logo fallback.
                val clearLogo = localCompanionFlatArtRef(selectedGame.flatArtUri)
                return CompanionPayload(
                    mode = mode,
                    gameId = selectedGame.gameId,
                    title = selectedGame.title,
                    platformId = selectedGame.platformId,
                    platformLabel = platformLabelForCompanion(selectedGame.platformId, platforms),
                    heroArtRef = clearLogo ?: selectedGame.wiredFace?.uri?.takeIf { it.isNotBlank() } ?: selectedGame.artUri,
                    heroArtIsLogo = clearLogo != null,
                    themeSigilRef = themeSigilRef,
                    themeId = themeId,
                    statsLabel = rowSet.statsLabel,
                    statusLabel = statusLabel,
                    statusReady = statusLabel == MimirCopy.StatusReady,
                    launcherLabel = launcherLabel,
                    positionLabel = null,
                    shelfModeLabel = rowSet.shelfModeLabel,
                    shelfRows = rowSet.rows,
                    selectedShelfId = selectedGame.gameId.value,
                    isLoading = launchLoading,
                )
            }
            return companionRestingPayload(rowSet, focusedKey, themeSigilRef, themeId, launchLoading)
        }
        CompanionRowKind.NATIVE_FAVORITES, CompanionRowKind.SHELF ->
            return companionRestingPayload(rowSet, focusedKey, themeSigilRef, themeId, launchLoading)
    }
}

/**
 * The resolved focused HOME-shelf row plus its display-ready detail, assembled at the push site (E4) so
 * [buildCompanionPayload] stays a pure function. This is the RESTING two-zone card; the in-flight launch
 * seed is a separate synchronous path ([homeLaunchSeedPayload]) so a slow fetch / focus move can't drop it.
 */
internal data class CompanionHomeShelfFocus(
    val row: HomeShelfRow,
    val detail: CompanionHomeShelfDetail,
    val gameId: GameId? = null,
    val platformId: PlatformId? = null,
    val statusLabel: String? = null,
    val statusReady: Boolean = false,
    val launcherLabel: String? = null,
)

/** Pure: the Android two-zone detail struct — no member fetch (E2), type/package/favorite only. */
internal fun homeShelfAndroidDetail(row: NativeShelfRow): CompanionHomeShelfDetail =
    CompanionHomeShelfDetail(
        favorite = row.isFavorite,
        androidTypeLabel = row.rowKindLabel ?: "Android game",
        androidPackage = row.packageName,
    )

/**
 * G-2 launch seed (review #2/#3): a NOW_PLAYING(isLoading) card built PURELY from the launching Home row —
 * no repository fetch, no focus dependency. The push emits it synchronously whenever a Home launch is in
 * flight (keyed on the coordinator's launchingRow, not the focused row), so [CompanionState.set] reliably
 * captures it into `lastNowPlaying` and the armed-owner republish carries the hero once the game is away
 * (E6). A slow member fetch or a fast post-CONFIRM focus move can no longer drop the seed.
 *
 * Its launcher line is not built here: [CompanionSeedLauncherLine] adds it as the seed publishes, because an
 * override's label needs a read this pure seed must not wait on.
 */
internal fun homeLaunchSeedPayload(
    row: HomeShelfRow,
    themeId: String,
    themeSigilRef: String? = null,
    platforms: List<PlatformRow> = emptyList(),
): CompanionPayload {
    val clearLogo = localCompanionFlatArtRef(row.flatArtUri)
    val emulatedId = (row.itemId as? HomeShelfItemId.Emulated)?.gameId
    return CompanionPayload(
        mode = CompanionMode.NOW_PLAYING,
        gameId = emulatedId,
        title = row.title,
        // Emulated rows carry the platformId as their supportingLabel (projectHomeShelf); Android rows don't.
        platformId = emulatedId?.let { row.supportingLabel?.takeIf(String::isNotBlank)?.let(::PlatformId) },
        platformLabel = emulatedId?.let {
            platformLabelForCompanion(
                row.supportingLabel?.takeIf(String::isNotBlank)?.let(::PlatformId),
                platforms,
            )
        },
        // companion art priority — clear logo → SteamGridDB alternate grid (opt-in, local file)
        // → platform-branded box cover. SGB replaces only the box fallback; the flat clear logo still wins (FA-M2).
        // The square, else the resolved face (his pick or the automatic box), else the cover: the ladder Home's
        // tile paints, so the Bottom wears the face the shelf does .
        heroArtRef = clearLogo ?: row.tileIconUri?.takeIf { it.isNotBlank() }
            ?: row.faceUri?.takeIf { it.isNotBlank() } ?: row.artworkUri,
        heroArtIsLogo = clearLogo != null,
        themeSigilRef = themeSigilRef,
        themeId = themeId,
        isLoading = true,
    )
}

/**
 * The NOW_PLAYING seed for a game the COMPANION display launched .
 *
 * The twin of [homeLaunchSeedPayload], from a [GameRow] instead of a Home row, because a game tapped on
 * screen 2 need not be on the Home shelf at all.
 *
 * **This exists because the card was MISSING.** `CompanionPayloadPush`'s HOME branch seeds NOW_PLAYING from
 * `launchingRow` — the Home shelf's own launch state — and a companion launch never sets it, so a game
 * started from screen 2 left screen 2 sitting on its shelf with no Now Playing card at all. It was reported
 * on the first build that could launch from there.
 *
 * Nothing downstream needed changing: publishing this through `CompanionState.set` retains it as
 * `lastNowPlaying`, so the G-2 path republishes it as a stable card when the emulator is observed
 * foreground, and `clearRetainedNowPlaying` ends it on return — the same lifecycle a Home launch gets.
 * Its launcher line comes from [CompanionSeedLauncherLine] at publish, exactly as the Home seed's does.
 */
internal fun companionLaunchSeedPayload(
    game: GameRow,
    themeId: String,
    platforms: List<PlatformRow> = emptyList(),
): CompanionPayload {
    // FA-M2: the flat clear logo wins on every companion game surface; the cover is the fallback.
    val clearLogo = localCompanionFlatArtRef(game.flatArtUri)
    return CompanionPayload(
        mode = CompanionMode.NOW_PLAYING,
        gameId = game.gameId,
        title = game.title,
        platformId = game.platformId,
        platformLabel = platformLabelForCompanion(game.platformId, platforms),
        heroArtRef = clearLogo ?: game.wiredFace?.uri?.takeIf { it.isNotBlank() } ?: game.artUri,
        heroArtIsLogo = clearLogo != null,
        themeSigilRef = null,
        themeId = themeId,
        isLoading = true,
    )
}

/** Keep the shelf lanes visible when a running-app projection has no lanes of its own. */
internal fun companionPayloadWithRetainedHomeLanes(
    activePayload: CompanionPayload,
    retainedHomePayload: CompanionPayload?,
): CompanionPayload {
    if (activePayload.mode != CompanionMode.RUNNING_APP || activePayload.homeLanes.any { it.tiles.isNotEmpty() }) {
        return activePayload
    }
    val retained = retainedHomePayload?.takeIf {
        it.mode == CompanionMode.HOME && it.homeLanes.any { lane -> lane.tiles.isNotEmpty() }
    } ?: return activePayload
    return activePayload.copy(homeLanes = retained.homeLanes)
}

/** Keep the passive companion card pinned while primary focus moves from the selected game to launch actions. */
internal fun companionSelectedOrOpenGame(
    rowSet: CompanionRowSet,
    openGameDetail: GameRow?,
    detailGame: GameRow?,
    focusedKey: FocusKey?,
): GameRow? = openGameDetail ?: companionSelectedGame(rowSet, detailGame, focusedKey)

internal fun localCompanionFlatArtRef(value: String?): String? {
    val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return normalized.takeIf {
        when (normalized.substringBefore(':', missingDelimiterValue = "").lowercase()) {
            "content", "file", "android.resource" -> true
            else -> false
        }
    }
}

internal fun companionShelfRows(
    mode: LibraryShelfMode,
    platforms: List<PlatformRow>,
    nativeSnapshot: NativeShelfSnapshot,
): List<CompanionShelfRow> = when (mode) {
    LibraryShelfMode.EMULATION -> platforms.map { row ->
        CompanionShelfRow(
            id = row.platformId.value,
            code = companionCode(row.label),
            name = row.label,
            countLabel = plural(row.gameCount, "game"),
            detailLines = listOf(row.platformId.value.uppercase()),
        )
    }
    LibraryShelfMode.ANDROID_GAMES -> nativeSnapshot.games.map(::companionNativeRow)
}

internal fun companionGameRow(row: GameRow): CompanionShelfRow =
    CompanionShelfRow(
        id = row.gameId.value,
        code = companionCode(row.platformId.value),
        name = row.title,
        countLabel = buildList {
            row.genre?.takeIf { it.isNotBlank() }?.let(::add)
            if (row.isFavorite) add("Favorite")
            if (row.hasEmulatorOverride) add("Open with")
        }.joinToString(" · ").ifBlank { MimirCopy.StatusReady },
        detailLines = buildList {
            row.genre?.takeIf { it.isNotBlank() }?.let(::add)
            if (row.memberCount > 1) add(plural(row.memberCount, "file"))
            if (row.hasEmulatorOverride) add("Open with override")
            if (row.isFavorite) add("Favorite")
        },
    )

internal fun companionNativeRow(row: NativeShelfRow): CompanionShelfRow =
    CompanionShelfRow(
        id = row.packageName,
        code = companionCode(row.label),
        name = row.label,
        countLabel = listOfNotNull(
            row.rowKindLabel ?: if (row.isGame) "Android game" else "Android app",
            "Favorite".takeIf { row.isFavorite },
        ).joinToString(" · "),
        detailLines = buildList {
            add(row.packageName)
            row.rowKindLabel?.let(::add)
            if (row.isFavorite) add("Favorite")
        },
    )

/**
 * I1 (policy 2026-07-11, reverses ruling 4): the companion HOME ring MIRRORS the primary shelf focus.
 * Decode the focused primary platform/native id from [focusedKey] and return it ONLY when it maps to a tile
 * that's actually on the companion home. Ring-only: homeLanes are unchanged (same reference → no shelf rebuild,
 * so no "janky carousel"); the only cost is a per-focus payload push that moves the highlight. Off-page focus
 * simply shows no ring (we don't auto-page).
 */

internal fun companionSelectedShelfId(focusedKey: FocusKey?, rows: List<CompanionShelfRow>): String? {
    val value = focusedKey?.value
    val focusedId = listOfNotNull(
        value.removePrefixOrNull(GameListFocusPrefix),
        value.removePrefixOrNull(PlatformShelfFocusPrefix),
        value.removePrefixOrNull(NativeShelfFocusPrefix),
    ).firstOrNull { id -> rows.any { it.id == id } }
    return focusedId ?: rows.firstOrNull()?.id
}

internal fun companionShelfStatsLabel(
    mode: LibraryShelfMode,
    platforms: List<PlatformRow>,
    nativeSnapshot: NativeShelfSnapshot,
): String = when (mode) {
    LibraryShelfMode.EMULATION ->
        "${plural(platforms.size, "platform")} · ${plural(platforms.sumOf { it.gameCount }, "game")}"
    LibraryShelfMode.ANDROID_GAMES -> companionNativeStatsLabel(FavoriteSegment.ANDROID_GAMES, nativeSnapshot.games.size)
}

internal fun companionNativeStatsLabel(segment: FavoriteSegment, count: Int): String =
    when (segment) {
        FavoriteSegment.GAMES -> plural(count, "game")
        FavoriteSegment.ANDROID_GAMES -> plural(count, "game")
        FavoriteSegment.ANDROID_APPS -> plural(count, "app")
    }

internal fun platformLabelForCompanion(platformId: PlatformId?, platforms: List<PlatformRow>): String? =
    when (platformId) {
        null -> null
        LibraryViewModel.FAVORITES_PLATFORM_ID -> "Favorites"
        else -> platforms.firstOrNull { it.platformId == platformId }?.label ?: PlatformLabels.labelFor(platformId)
    }

/**
 * Browse's platform names for its hidden games, resolved the way the shelf resolves one ([platformLabelForCompanion]): the library's own label when the platform still has games on the shelf,
 * the registry's name when it has none. `platforms` lists only platforms with visible games, so a lone hidden Game Boy game was named "Unknown platform" (update's sweep, 10-09).
 */
internal fun browsePlatformLabels(hidden: List<GameRow>, platforms: List<PlatformRow>): Map<PlatformId, String> {
    val labels = HashMap<PlatformId, String>()
    platforms.forEach { labels[it.platformId] = it.label }
    hidden.forEach { game -> labels.getOrPut(game.platformId) { PlatformLabels.labelFor(game.platformId) } }
    return labels
}

/**
 * Settings › Library › Hidden games' platform names, resolved the way Browse resolves them ([browsePlatformLabels]), as the rows the Settings read model carries: the read model was built without
 * any, so every hidden row said "Unknown platform" (update's sweep, 10-09).
 */
internal fun hiddenGamePlatformRows(hidden: List<GameRow>, platforms: List<PlatformRow>): List<PlatformRow> =
    browsePlatformLabels(hidden, platforms).map { (platformId, label) ->
        PlatformRow(platformId, label, platforms.firstOrNull { it.platformId == platformId }?.gameCount ?: 0)
    }

internal fun companionGameStatusLabel(state: GameLaunchUiState, game: GameRow): String =
    companionLaunchStatusLabel(state)
        ?: when {
            game.hasEmulatorOverride -> "Open with set"
            game.isFavorite -> "Favorite"
            else -> MimirCopy.StatusReady
        }

internal fun companionLaunchStatusLabel(state: GameLaunchUiState): String? =
    when (state) {
        GameLaunchUiState.Idle -> null
        GameLaunchUiState.Launching,
        GameLaunchUiState.IntentSent -> MimirCopy.StatusLaunching
        GameLaunchUiState.Unwired -> "Launch unavailable"
        // O-3 (2026-08-12): the short status-label twin of the session-held sentence. O15
        // splits it like the sentence, starting vs running, and feature-library's one selector owns both labels. Not
        // painted today: this feeds the companion payload's status label, which no production code reads.
        is GameLaunchUiState.ClosedUnexpectedly -> "Closed unexpectedly"
        is GameLaunchUiState.SessionHeld -> gameLaunchSessionHeldRailLabel(state.hold)
        is GameLaunchUiState.NeedsEmulator -> MimirCopy.StatusNeedsEmulator
        is GameLaunchUiState.NeedsBios -> {
            val files = state.missingFileNames.joinToString(", ").ifBlank { "BIOS" }
            "Needs $files"
        }
        GameLaunchUiState.MissingFile -> "File unavailable"
        is GameLaunchUiState.Unsupported -> MimirCopy.StatusUnsupported
        is GameLaunchUiState.DispatchFailed -> companionDispatchFailureLabel(state.reason)
    }

internal fun companionDispatchFailureLabel(reason: com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure): String =
    when (reason) {
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.TARGET_MISSING -> "Target missing"
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.TARGET_NOT_INSTALLED -> "Target not installed"
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.ROM_PATH_MISSING -> "File missing"
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.LOCATOR_FORM_REJECTED -> MimirCopy.FailureWrongFileForm
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE -> "Access unavailable"
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.LAUNCH_PROFILE_UNPROVEN -> MimirCopy.FailureCouldntStart
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.ACTIVITY_NOT_STARTED -> MimirCopy.FailureAndroidBlocked
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.LOCATOR_CONTENT_MALFORMED -> "Shortcut damaged"
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING -> "File missing"
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.EMULATOR_NEEDS_OWN_ROM_FOLDER -> "Update emulator"
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.RETROARCH_CORE_MISSING -> "Core not installed"
        com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure.BRIDGE_ERROR -> "Launch error"
    }

/**
 * The game the OPEN_PLATFORM payload will detail — the caller resolves its launcher label with this
 * BEFORE [buildCompanionPayload] (label resolution is suspend; the adapter stays a pure function).
 * Same logic the payload build uses, so the two can't disagree.
 */
internal fun companionSelectedGame(
    rowSet: CompanionRowSet,
    detailGame: GameRow?,
    focusedKey: FocusKey?,
): GameRow? {
    if (rowSet.kind != CompanionRowKind.OPEN_PLATFORM) return null
    // `detailGame` deliberately survives while focus moves onto the metadata/Get-Art affordance, but it
    // must never pin A once the ring has directly focused B. Decode only a RECOGNIZED gamelist key here;
    // unlike companionSelectedShelfId this lookup has no first-row fallback, so the precedence stays:
    // direct ring identity -> retained off-ring identity -> first visible identity.
    val focusedGame = focusedKey?.value
        ?.removePrefixOrNull(GameListFocusPrefix)
        ?.let { id -> rowSet.visibleGames.firstOrNull { it.gameId.value == id } }
    val retainedGame = detailGame?.let { retained ->
        // Prefer the row-set copy so a same-ID art/title refresh is visible even while Get Art owns focus.
        rowSet.visibleGames.firstOrNull { it.gameId == retained.gameId }
    }
    return focusedGame ?: retainedGame ?: rowSet.visibleGames.firstOrNull()
}

/**
 * The launcher display label behind a shelf launch-status line ("Launch · RetroArch 64 / mGBA" → the
 * label), or null for the non-ready sentinels — the "Opens with · X" row never names a launcher that
 * won't actually launch (policy R13: REAL labels only).
 */
internal fun companionLauncherLabel(line: String?): String? {
    val value = line?.removePrefix("Launch ·")?.trim().orEmpty()
    return value.takeIf {
        it.isNotBlank() &&
            !it.equals(MimirCopy.StatusNeedsEmulator, ignoreCase = true) &&
            !it.equals(MimirCopy.StatusUnsupported, ignoreCase = true)
    }
}

internal fun companionCode(label: String): String {
    val tokens = label
        .split(Regex("[\\s_\\-/]+"))
        .map { it.filter(Char::isLetterOrDigit) }
        .filter { it.isNotBlank() }
    val code = if (tokens.size > 1) {
        tokens.take(3).mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
    } else {
        label.filter(Char::isLetterOrDigit).take(3)
    }
    return code.uppercase().ifBlank { "M" }.take(4)
}

// Shared with MimirApp.kt's focus plumbing (same package): the focus-key namespaces the adapter decodes,
// and the tiny formatting helpers both sides use.
internal const val PlatformShelfFocusPrefix = "library/shelf/"
internal const val GameListFocusPrefix = "library/gamelist/"
internal const val NativeShelfFocusPrefix = "library/native/"

internal fun plural(count: Int, noun: String): String =
    "$count ${if (count == 1) noun else "${noun}s"}"

internal fun String?.removePrefixOrNull(prefix: String): String? =
    this?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotBlank() }

/**
 * Build the 3DS-shelf HOME: ONE interleaved order of tiles — consoles and favorited Android apps
 * are equal citizens the user can file anywhere. Consoles are every platform with games
 * (the same truth the primary shelf reads); Android tiles are FAVORITES ONLY, and an empty shelf
 * teaches instead of sitting blank.
 *
 * [homeOrder] is the user's filed order (S4): tiles it names take those positions; anything it doesn't know
 * — a platform that just gained its first game, a freshly favorited app — APPENDS AT THE TAIL, never
 * inserted mid-grid. Arrival is never a disruption. Pure; keyed on library shape + order upstream.
 */
/**
 * **The one resting return.** Every route that is not showing a launch lands here: the shelf, native
 * favorites, and — since D1-R25 was re-ruled on 2026-08-30 — an open platform being browsed.
 *
 * HP-11 Slice 1: the resting surface is the STATIC three-lane HOME. Nothing focus-derived crosses into
 * the lanes, so browsing produces value-equal payloads and the `StateFlow` deduplicates them — zero
 * screen-2 repaints while screen 1 walks the library.
 *
 * **Sharing it is the point, not a tidy-up.** The empty-lane guard exists exactly once, so a HOME with
 * an empty 3x3 grid is unreachable by construction rather than by two branches remembering to check.
 * It is also what makes the Library rest and the Home rest the same VALUE rather than two payloads
 * that happen to agree.
 */
private fun companionRestingPayload(
    rowSet: CompanionRowSet,
    focusedKey: FocusKey?,
    themeSigilRef: String?,
    themeId: String,
    launchLoading: Boolean,
): CompanionPayload {
    // OPEN_PLATFORM's own statsLabel is the gamelist header screen 1 already shows; the resting shelf
    // wears the shelf's line instead. D1-R1 retired the mirror, and a header is a mirror.
    val stats = rowSet.homeStatsLabel ?: rowSet.statsLabel
    if (rowSet.homeLanes.all { it.tiles.isEmpty() }) {
        // Keep the explicit empty HOME snapshot even though the visible mode idles. A drag may still be
        // holding the previous non-empty shelf; this is the authoritative removal signal that lets its
        // transaction cancel instead of committing against stale retained visuals.
        return CompanionPayload(
            mode = CompanionMode.IDLE,
            themeSigilRef = themeSigilRef,
            themeId = themeId,
            statsLabel = stats,
            homeLanes = rowSet.homeLanes,
            isLoading = launchLoading,
        )
    }
    return CompanionPayload(
        mode = CompanionMode.HOME,
        themeSigilRef = themeSigilRef,
        themeId = themeId,
        statsLabel = stats,
        homeLanes = rowSet.homeLanes,
        // I1: mirror the primary shelf focus as the HOME ring . homeLanes stays
        // the same reference so the shelf never rebuilds; only the ring moves. A `library/gamelist/*`
        // key is not a home tile, so browsing resolves to null here rather than backfilling the ring
        // with the open platform -- "which platform screen 1 has open" is the mirror class D1-R1 retired.
        // *** This used to be
        // `companionFocusedHomeTileId(focusedKey, rowSet.homeLanes)` — screen 1's focus, mirrored onto
        // screen 2's HOME ring (I1, 2026-07-11). §5b's *the second screen's clock is never driven by
        // the first screen's input* wins; the ring now reads screen 2's own persisted touch cursor.
        //
        // **Cutting it here, not only at the render, is the point.** Left in the payload it would keep
        // changing on every focus move, and `CompanionState.publish` dedups on payload equality — so a
        // field nobody draws would still have cost a push, a revision bump and a Presentation redraw
        // per d-pad press. Null makes the HOME payload actually identical across focus moves.
        selectedShelfId = null,
        isLoading = launchLoading,
    )
}

internal fun buildCompanionHomeLanes(
    platforms: List<PlatformRow>,
    nativeSnapshot: NativeShelfSnapshot,
    homeOrder: List<String> = emptyList(),
    homeFolders: List<CompanionFolder> = emptyList(),
): List<CompanionHomeLane> {
    val defaultTiles = platforms.map { p ->
        CompanionHomeTile(
            id = p.platformId.value,
            kind = CompanionHomeTileKind.PLATFORM,
            // Policy R1: the Bottom names a platform the way Main does, in full ("GameCube", not "GCN"),
            // and the lattice and the shelf header give the name two lines to fit it.
            label = p.label,
            countLabel = plural(p.gameCount, "game"),
        )
    } + (nativeSnapshot.games + nativeSnapshot.apps).filter { it.isFavorite }.map(::companionHomeAppTile)

    // Bind the tomes (S6): a folder tile takes its cell, its children leave the top level.
    //
    // An empty tome and a vanished tome look alike from here, so tell them apart by intent (CR-17): a tome
    // the user just created holds NO children and renders, waiting to be filled. A tome whose children have
    // all vanished (uninstalled apps, emptied platforms) held some, resolves to none, and stays hidden until
    // they come back — retained in the store either way, exactly like an order entry.
    val byId = defaultTiles.associateBy { it.id }
    val bound = mutableSetOf<String>()
    val folderTiles = homeFolders.mapNotNull { folder ->
        val children = folder.children.mapNotNull(byId::get)
        if (folder.children.isNotEmpty() && children.isEmpty()) return@mapNotNull null
        bound += children.map { it.id }
        CompanionHomeTile(
            id = folder.id,
            kind = CompanionHomeTileKind.FOLDER,
            label = folder.name,
            countLabel = plural(children.size, "item"),
            children = children,
        )
    }
    val topLevel = defaultTiles.filterNot { it.id in bound } + folderTiles

    val filed = homeOrder.withIndex().associate { (index, id) -> id to index }
    val tiles = topLevel
        .filter { it.id in filed }
        .sortedBy { filed.getValue(it.id) } + topLevel.filterNot { it.id in filed }

    return listOf(
        CompanionHomeLane(
            id = "shelf",
            // QA-25: was "Shelf". This lane's own hint below describes the HOME shelf ("favorite an Android
            // app to pin it here"), and the companion sits beside a Library that also had a "shelf". The id
            // stays "shelf" -- it is a wire key, not copy.
            title = "Home shelf",
            hint = "Add games from Settings › Library, or favorite an Android app to pin it here.",
            tiles = tiles,
        ),
    )
}

private fun companionHomeAppTile(row: NativeShelfRow): CompanionHomeTile = CompanionHomeTile(
    id = row.packageName,
    kind = CompanionHomeTileKind.ANDROID_APP,
    label = row.label,
    iconPath = row.iconPath,
    kindLabel = row.rowKindLabel,
)

