package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId

data class LibraryLaunchReturnTarget(
    val surface: Surface,
    val shelfMode: LibraryShelfMode,
    val shelfKey: String? = null,
    val openPlatformId: PlatformId? = null,
    val romPlatformId: PlatformId? = null,
    val gameId: GameId? = null,
    val nativePackageName: String? = null,
    val favoriteSegment: FavoriteSegment = FavoriteSegment.GAMES,
    val searchQuery: String = "",
    val favoritesOnly: Boolean = false,
    val restoreLaunchCard: Boolean = false,
    val searchSnapshot: LibrarySearchReturnSnapshot? = null,
    val gamelistViewMode: GamelistViewMode = GamelistViewMode.CAROUSEL,
) {
    init {
        when (surface) {
            Surface.SHELF -> {
                require(!shelfKey.isNullOrBlank()) { "shelf return target requires a shelf key" }
                require(openPlatformId == null) { "shelf return target cannot carry an open platform" }
                require(gameId == null) { "shelf return target cannot carry a ROM game id" }
                require(searchSnapshot == null) { "shelf return target cannot carry Search state" }
                require(!restoreLaunchCard || shelfMode == LibraryShelfMode.ANDROID_GAMES) {
                    "Only an Android game shelf return can restore the launch card"
                }
            }
            Surface.GAMELIST -> {
                require(openPlatformId != null) { "gamelist return target requires an open platform" }
                require((gameId == null) != (nativePackageName == null)) {
                    "gamelist return target requires exactly one launch identity"
                }
                require(gameId == null || romPlatformId != null) {
                    "ROM gamelist return target requires the launched ROM platform"
                }
                require(nativePackageName == null || searchSnapshot == null) {
                    "native gamelist return target cannot carry dedicated Search state"
                }
            }
        }
    }

    enum class Surface {
        SHELF,
        GAMELIST,
    }

    /**
     * A flat, versioned encoding for the host's saved instance state: the target a launch left pending, put back when the process was killed
     * while the game ran (a low-memory kill during an emulator load; the Thor, 2026-10-14) so the return can still land on the launch
     * card. Everything but the dedicated Search snapshot, which does not survive a kill (the query string does).
     */
    fun toSavedState(): ArrayList<String> = arrayListOf(
        SavedStateVersion,
        surface.name,
        shelfMode.name,
        shelfKey.orEmpty(),
        openPlatformId?.value.orEmpty(),
        romPlatformId?.value.orEmpty(),
        gameId?.value.orEmpty(),
        nativePackageName.orEmpty(),
        favoriteSegment.name,
        searchQuery,
        if (favoritesOnly) "1" else "0",
        if (restoreLaunchCard) "1" else "0",
        gamelistViewMode.name,
    )

    companion object {
        private const val SavedStateVersion = "ret1"

        /** The target [toSavedState] wrote, or null when there is none or it is not one this build can read (a changed layout, a removed enum value). */
        fun fromSavedState(saved: List<String>?): LibraryLaunchReturnTarget? {
            if (saved == null || saved.size != 13 || saved[0] != SavedStateVersion) return null
            return runCatching {
                LibraryLaunchReturnTarget(
                    surface = Surface.valueOf(saved[1]),
                    shelfMode = LibraryShelfMode.valueOf(saved[2]),
                    shelfKey = saved[3].ifEmpty { null },
                    openPlatformId = saved[4].ifEmpty { null }?.let(::PlatformId),
                    romPlatformId = saved[5].ifEmpty { null }?.let(::PlatformId),
                    gameId = saved[6].ifEmpty { null }?.let(::GameId),
                    nativePackageName = saved[7].ifEmpty { null },
                    favoriteSegment = FavoriteSegment.valueOf(saved[8]),
                    searchQuery = saved[9],
                    favoritesOnly = saved[10] == "1",
                    restoreLaunchCard = saved[11] == "1",
                    gamelistViewMode = GamelistViewMode.valueOf(saved[12]),
                )
            }.getOrNull()
        }

        fun shelf(
            shelfMode: LibraryShelfMode,
            shelfKey: String,
            restoreLaunchCard: Boolean = false,
            gamelistViewMode: GamelistViewMode = GamelistViewMode.CAROUSEL,
        ): LibraryLaunchReturnTarget =
            LibraryLaunchReturnTarget(
                surface = Surface.SHELF,
                shelfMode = shelfMode,
                shelfKey = shelfKey,
                restoreLaunchCard = restoreLaunchCard,
                gamelistViewMode = gamelistViewMode,
            )

        fun gamelistRom(
            openPlatformId: PlatformId,
            romPlatformId: PlatformId,
            gameId: GameId,
            shelfMode: LibraryShelfMode,
            favoriteSegment: FavoriteSegment,
            searchQuery: String,
            favoritesOnly: Boolean,
            restoreLaunchCard: Boolean = false,
            searchSnapshot: LibrarySearchReturnSnapshot? = null,
            gamelistViewMode: GamelistViewMode = GamelistViewMode.CAROUSEL,
        ): LibraryLaunchReturnTarget =
            LibraryLaunchReturnTarget(
                surface = Surface.GAMELIST,
                shelfMode = shelfMode,
                openPlatformId = openPlatformId,
                romPlatformId = romPlatformId,
                gameId = gameId,
                favoriteSegment = favoriteSegment,
                searchQuery = searchQuery,
                favoritesOnly = favoritesOnly,
                searchSnapshot = searchSnapshot,
                gamelistViewMode = gamelistViewMode,
                restoreLaunchCard = restoreLaunchCard,
            )

        fun gamelistNative(
            openPlatformId: PlatformId,
            nativePackageName: String,
            shelfMode: LibraryShelfMode,
            favoriteSegment: FavoriteSegment,
            searchQuery: String,
            restoreLaunchCard: Boolean = false,
            gamelistViewMode: GamelistViewMode = GamelistViewMode.CAROUSEL,
        ): LibraryLaunchReturnTarget =
            LibraryLaunchReturnTarget(
                surface = Surface.GAMELIST,
                shelfMode = shelfMode,
                openPlatformId = openPlatformId,
                nativePackageName = nativePackageName,
                favoriteSegment = favoriteSegment,
                searchQuery = searchQuery,
                favoritesOnly = false,
                restoreLaunchCard = restoreLaunchCard,
                gamelistViewMode = gamelistViewMode,
            )
    }
}

data class LibraryOriginReturnTarget(
    val surface: Surface,
    val shelfMode: LibraryShelfMode,
    val focusKey: FocusKey?,
    val openPlatformId: PlatformId? = null,
    val favoriteSegment: FavoriteSegment = FavoriteSegment.GAMES,
    val searchQuery: String = "",
    val favoritesOnly: Boolean = false,
    val searchSnapshot: LibrarySearchReturnSnapshot? = null,
    val gamelistViewMode: GamelistViewMode = GamelistViewMode.CAROUSEL,
) {
    init {
        when (surface) {
            Surface.SHELF -> {
                require(openPlatformId == null) { "shelf origin cannot carry an open platform" }
                require(searchSnapshot == null) { "shelf origin cannot carry Search state" }
            }
            Surface.GAMELIST -> {
                require(openPlatformId != null) { "gamelist origin requires an open platform" }
                require(
                    openPlatformId != LibraryViewModel.FAVORITES_PLATFORM_ID ||
                        favoriteSegment == FavoriteSegment.GAMES ||
                        searchSnapshot == null,
                ) { "native Favorites origin cannot carry dedicated Search state" }
            }
        }
    }

    enum class Surface {
        SHELF,
        GAMELIST,
    }
}
