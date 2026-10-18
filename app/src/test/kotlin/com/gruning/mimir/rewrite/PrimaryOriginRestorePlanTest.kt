package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.GamelistViewMode
import com.gruning.mimir.rewrite.feature.library.LibraryOriginReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibrarySearchReturnFocus
import com.gruning.mimir.rewrite.feature.library.LibrarySearchReturnSnapshot
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryOriginRestorePlanTest {
    @Test
    fun `canonical library origin restores filters and exact scope target`() {
        val origin = PrimaryReturnOrigin(
            destination = MimirDestination.LIBRARY.name,
            arguments = mapOf(
                "library.surface" to "GAMELIST",
                "library.shelfMode" to "EMULATION",
                "library.focusKey" to "library/gamelist/game-7",
                "library.openPlatformId" to "ps1",
                "library.favoriteSegment" to "ANDROID_APPS",
                "library.searchQuery" to "metal",
                "library.favoritesOnly" to "true",
                "library.searchGenreKey" to "role playing",
                "library.searchPlatformId" to "snes",
                "library.searchLastFocused" to "RESULT",
                "library.searchLastFocusedGameId" to "snes/metal",
                "library.searchLastTopFocused" to "PLATFORM",
                "library.searchResultAnchorGameId" to "snes/metal",
                "library.gamelistViewMode" to "LIST",
            ),
        )

        assertEquals(
            LibraryOriginReturnTarget(
                surface = LibraryOriginReturnTarget.Surface.GAMELIST,
                shelfMode = LibraryShelfMode.EMULATION,
                focusKey = FocusKey("library/gamelist/game-7"),
                openPlatformId = PlatformId("ps1"),
                favoriteSegment = FavoriteSegment.ANDROID_APPS,
                searchQuery = "metal",
                favoritesOnly = true,
                searchSnapshot = LibrarySearchReturnSnapshot(
                    query = "metal",
                    genreKey = "role playing",
                    platformId = PlatformId.SNES,
                    lastFocused = LibrarySearchReturnFocus.RESULT,
                    lastFocusedResultGameId = com.gruning.mimir.rewrite.core.model.GameId("snes/metal"),
                    lastTopFocused = LibrarySearchReturnFocus.PLATFORM,
                    resultAnchorGameId = com.gruning.mimir.rewrite.core.model.GameId("snes/metal"),
                ),
                gamelistViewMode = GamelistViewMode.LIST,
            ),
            decodePrimaryLibraryOrigin(origin),
        )
    }

    /**
     * Stale-state pin (RC ticket, 2026-08-21): `LibraryShelfMode.ANDROID_APPS` is retired. A settings origin
     * persisted by an OLDER install can still carry the literal string "ANDROID_APPS" on disk (SharedPreferences
     * survives an app update). `readLibraryOrigin` already guards every enum decode with
     * `runCatching { LibraryShelfMode.valueOf(raw) }.getOrNull()` and returns null for the WHOLE origin when the
     * mode fails to parse (MimirApp.kt) -- this pins that it degrades sanely (no crash, origin simply dropped)
     * rather than throwing or resolving to a phantom empty shelf.
     */
    @Test
    fun `stale persisted ANDROID_APPS settings origin decodes to no origin, not a crash`() {
        val origin = PrimaryReturnOrigin(
            destination = MimirDestination.SETTINGS.name,
            arguments = mapOf(
                "focusKey" to "settings/detail/appearance/theme",
                "focusScope" to "settings/detail",
                "settingsOrigin.surface" to "SHELF",
                "settingsOrigin.shelfMode" to "ANDROID_APPS",
                "settingsOrigin.focusKey" to "library/native/com.example.app",
                "settingsOrigin.favoriteSegment" to "GAMES",
                "settingsOrigin.searchQuery" to "",
                "settingsOrigin.favoritesOnly" to "false",
            ),
        )

        assertNull(decodeSettingsLibraryOrigin(origin))
    }

    @Test
    fun `settings return target round trips independently from settings focus`() {
        val origin = PrimaryReturnOrigin(
            destination = MimirDestination.SETTINGS.name,
            arguments = mapOf(
                "focusKey" to "settings/detail/appearance/theme",
                "focusScope" to "settings/detail",
                "settingsOrigin.surface" to "SHELF",
                "settingsOrigin.shelfMode" to "ANDROID_GAMES",
                "settingsOrigin.focusKey" to "library/native/com.example.app",
                "settingsOrigin.favoriteSegment" to "GAMES",
                "settingsOrigin.searchQuery" to "",
                "settingsOrigin.favoritesOnly" to "false",
            ),
        )

        assertEquals(
            LibraryOriginReturnTarget(
                surface = LibraryOriginReturnTarget.Surface.SHELF,
                shelfMode = LibraryShelfMode.ANDROID_GAMES,
                focusKey = FocusKey("library/native/com.example.app"),
            ),
            decodeSettingsLibraryOrigin(origin),
        )
    }

    @Test
    fun `settings prefixed search snapshot and view mode round trip`() {
        val target = LibraryOriginReturnTarget(
            surface = LibraryOriginReturnTarget.Surface.GAMELIST,
            shelfMode = LibraryShelfMode.EMULATION,
            focusKey = FocusKey("library/gamelist/snes-game"),
            openPlatformId = LibraryViewModel.FAVORITES_PLATFORM_ID,
            favoriteSegment = FavoriteSegment.GAMES,
            searchQuery = "zelda",
            searchSnapshot = LibrarySearchReturnSnapshot(
                query = "zelda",
                genreKey = "adventure",
                platformId = PlatformId.SNES,
                lastFocused = LibrarySearchReturnFocus.GENRE,
                lastTopFocused = LibrarySearchReturnFocus.GENRE,
                resultAnchorGameId = com.gruning.mimir.rewrite.core.model.GameId("snes-game"),
            ),
            gamelistViewMode = GamelistViewMode.LIST,
        )
        val arguments = mutableMapOf(
            "focusKey" to "settings/detail/appearance/theme",
            "focusScope" to "settings/detail",
        )
        target.writeTo(arguments, "settingsOrigin.")

        assertEquals(
            target,
            decodeSettingsLibraryOrigin(
                PrimaryReturnOrigin(
                    destination = MimirDestination.SETTINGS.name,
                    arguments = arguments,
                ),
            ),
        )
    }

    @Test
    fun `legacy query-only origin defaults to no dormant snapshot and carousel`() {
        val decoded = decodePrimaryLibraryOrigin(
            PrimaryReturnOrigin(
                destination = MimirDestination.LIBRARY.name,
                arguments = mapOf(
                    "library.surface" to "GAMELIST",
                    "library.shelfMode" to "EMULATION",
                    "library.openPlatformId" to "ps1",
                    "library.searchQuery" to "legacy",
                ),
            ),
        )

        assertEquals("legacy", decoded.searchQuery)
        assertNull(decoded.searchSnapshot)
        assertEquals(GamelistViewMode.CAROUSEL, decoded.gamelistViewMode)
    }

    @Test
    fun `native favorites ignore injected dedicated search payload but retain list mode`() {
        // "library.shelfMode" is deliberately the retired "ANDROID_APPS" string here -- a stand-in for a
        // pre-RC-persisted origin. decodePrimaryLibraryOrigin's fallback (`?: LibraryShelfMode.EMULATION`)
        // must degrade it sanely rather than crash; the favoriteSegment field is unaffected since
        // FavoriteSegment.ANDROID_APPS is NOT retired (Favorites keeps all three segments).
        val decoded = decodePrimaryLibraryOrigin(
            PrimaryReturnOrigin(
                destination = MimirDestination.LIBRARY.name,
                arguments = mapOf(
                    "library.surface" to "GAMELIST",
                    "library.shelfMode" to "ANDROID_APPS",
                    "library.openPlatformId" to LibraryViewModel.FAVORITES_PLATFORM_ID.value,
                    "library.favoriteSegment" to "ANDROID_APPS",
                    "library.searchQuery" to "mail",
                    "library.searchLastFocused" to "RESULT",
                    "library.searchLastFocusedGameId" to "forged",
                    "library.gamelistViewMode" to "LIST",
                ),
            ),
        )

        assertEquals(LibraryShelfMode.EMULATION, decoded.shelfMode)
        assertEquals(FavoriteSegment.ANDROID_APPS, decoded.favoriteSegment)
        assertNull(decoded.searchSnapshot)
        assertEquals(GamelistViewMode.LIST, decoded.gamelistViewMode)
    }

    @Test
    fun `loaded shelf selects exact valid key and deterministic fallback`() {
        val platforms = listOf(
            PlatformRow(PlatformId("arcade"), "Arcade", 1),
            PlatformRow(PlatformId("ps1"), "PlayStation", 2),
        )
        val exact = LibraryOriginReturnTarget(
            surface = LibraryOriginReturnTarget.Surface.SHELF,
            shelfMode = LibraryShelfMode.EMULATION,
            focusKey = FocusKey("library/shelf/ps1"),
        )
        assertEquals(
            FocusKey("library/shelf/ps1"),
            selectStartupLibraryFocusTarget(exact, platforms, emptyList(), emptyList()),
        )
        assertEquals(
            FocusKey("library/shelf/arcade"),
            selectStartupLibraryFocusTarget(
                exact.copy(focusKey = FocusKey("library/shelf/removed")),
                platforms,
                emptyList(),
                emptyList(),
            ),
        )

        val native = listOf(NativeShelfRow("com.example.one", "One", null, false))
        assertEquals(
            FocusKey("library/native/com.example.one"),
            selectStartupLibraryFocusTarget(
                exact.copy(shelfMode = LibraryShelfMode.ANDROID_GAMES, focusKey = null),
                emptyList(),
                emptyList(),
                native,
            ),
        )
    }

    @Test
    fun `removed recovered platform falls back to a clean shelf`() {
        val removed = LibraryOriginReturnTarget(
            surface = LibraryOriginReturnTarget.Surface.GAMELIST,
            shelfMode = LibraryShelfMode.EMULATION,
            focusKey = FocusKey("library/gamelist/game-7"),
            openPlatformId = PlatformId("removed"),
            searchQuery = "metal",
            favoritesOnly = true,
            searchSnapshot = LibrarySearchReturnSnapshot(
                query = "metal",
                genreKey = "action",
                lastFocused = LibrarySearchReturnFocus.GENRE,
                lastTopFocused = LibrarySearchReturnFocus.GENRE,
            ),
            gamelistViewMode = GamelistViewMode.LIST,
        )

        assertEquals(
            LibraryOriginReturnTarget(
                surface = LibraryOriginReturnTarget.Surface.SHELF,
                shelfMode = LibraryShelfMode.EMULATION,
                focusKey = null,
                gamelistViewMode = GamelistViewMode.LIST,
            ),
            availableStartupLibraryTarget(
                removed,
                listOf(PlatformRow(PlatformId("ps1"), "PlayStation", 2)),
            ),
        )
    }

    @Test
    fun `startup recovery origin is one shot`() {
        CompanionPrimaryOriginStore.resetStartupRecoveryForTest()
        val origin = PrimaryReturnOrigin(MimirDestination.SETTINGS.name, revision = 9L)
        CompanionPrimaryOriginStore.offerStartupRecovery(origin)

        assertEquals(origin, CompanionPrimaryOriginStore.consumeStartupRecovery())
        assertNull(CompanionPrimaryOriginStore.consumeStartupRecovery())
    }
}
