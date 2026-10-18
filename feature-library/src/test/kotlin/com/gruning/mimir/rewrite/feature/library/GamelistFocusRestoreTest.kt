package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamelistFocusRestoreTest {
    private val platform = PlatformId.PS1
    private val gameKeys = listOf(
        FocusKey("library/gamelist/game-1"),
        FocusKey("library/gamelist/game-2"),
        FocusKey("library/gamelist/game-3"),
    )

    @Test
    fun focusLossReseedsToValidRememberedGameBeforeGameOne() {
        assertEquals(
            gameKeys[2],
            gamelistReseedTarget(
                gameKeys = gameKeys,
                focused = null,
                rememberedFocus = gameKeys[2],
            ),
        )
        assertEquals(
            gameKeys.first(),
            gamelistReseedTarget(
                gameKeys = gameKeys,
                focused = FocusKey("library/gamelist/removed"),
                rememberedFocus = FocusKey("library/gamelist/removed"),
            ),
        )
    }

    @Test
    fun loadedReopenCorrectsDefaultLandingToRememberedGameExactlyOnce() {
        val firstResolution = resolvePendingGamelistLanding(
            pending = PendingGamelistLanding(platform, rememberedFocus = gameKeys[2]),
            openPlatform = platform,
            gamesLoadedForPlatform = platform,
            gameKeys = gameKeys,
            focused = gameKeys.first(),
        )

        assertEquals(gameKeys[2], firstResolution.target)
        assertNull(firstResolution.pending)

        val laterRecomposition = resolvePendingGamelistLanding(
            pending = firstResolution.pending,
            openPlatform = platform,
            gamesLoadedForPlatform = platform,
            gameKeys = gameKeys,
            focused = gameKeys.first(),
        )
        assertNull(laterRecomposition.target)
    }

    @Test
    fun reopenWaitsForLoadedPlatformAndNeverOverridesUserNavigation() {
        val pending = PendingGamelistLanding(platform, rememberedFocus = gameKeys[2])
        val loading = resolvePendingGamelistLanding(
            pending = pending,
            openPlatform = platform,
            gamesLoadedForPlatform = null,
            gameKeys = gameKeys,
            focused = gameKeys.first(),
        )
        assertEquals(pending, loading.pending)
        assertNull(loading.target)

        val userMoved = resolvePendingGamelistLanding(
            pending = loading.pending,
            openPlatform = platform,
            gamesLoadedForPlatform = platform,
            gameKeys = gameKeys,
            focused = gameKeys[1],
        )
        assertNull(userMoved.pending)
        assertNull(userMoved.target)
    }

    @Test
    fun `first painted frame keeps remembered game instead of temporary game one`() {
        assertEquals(
            gameKeys[2],
            gamelistRenderedFocus(
                gameKeys = gameKeys,
                focused = gameKeys.first(),
                scopeOwnsGamelist = true,
                rememberedFocus = gameKeys.first(),
                pendingRememberedFocus = gameKeys[2],
                retainedGameFocus = null,
            ),
        )
    }

    @Test
    fun `child surface and refreshed rows paint from stable remembered identity`() {
        assertEquals(
            gameKeys[1],
            gamelistRenderedFocus(
                gameKeys = gameKeys,
                focused = FocusKey("library/detail/play"),
                scopeOwnsGamelist = false,
                rememberedFocus = gameKeys[1],
                pendingRememberedFocus = null,
                retainedGameFocus = gameKeys[1],
            ),
        )
    }

    @Test
    fun `native favorites paint the live authority key instead of row one`() {
        val nativeKeys = listOf(
            FocusKey("library/native/app-1"),
            FocusKey("library/native/app-2"),
            FocusKey("library/native/app-3"),
        )

        assertEquals(
            nativeKeys[2],
            nativeFavoritesRenderedFocus(
                rowKeys = nativeKeys,
                focused = nativeKeys[2],
                scopeOwnsGamelist = true,
                rememberedFocus = nativeKeys.first(),
            ),
        )
    }

    @Test
    fun `native favorites retain only an active key and otherwise land on first row`() {
        val nativeKeys = listOf(
            FocusKey("library/native/app-1"),
            FocusKey("library/native/app-2"),
        )

        assertEquals(
            nativeKeys[1],
            nativeFavoritesRenderedFocus(
                rowKeys = nativeKeys,
                focused = FocusKey("library/gamelist/rom-1"),
                scopeOwnsGamelist = false,
                rememberedFocus = nativeKeys[1],
            ),
        )
        assertEquals(
            nativeKeys.first(),
            nativeFavoritesRenderedFocus(
                rowKeys = nativeKeys,
                focused = FocusKey("library/native/other-segment"),
                scopeOwnsGamelist = true,
                rememberedFocus = FocusKey("library/native/removed"),
            ),
        )
        assertNull(
            nativeFavoritesRenderedFocus(
                rowKeys = emptyList(),
                focused = nativeKeys.first(),
                scopeOwnsGamelist = true,
                rememberedFocus = nativeKeys.first(),
            ),
        )
    }

    @Test
    fun `favorite and media refresh retain key order and never reseed game one`() {
        val original = listOf(
            game("game-1", "One"),
            game("game-2", "Two"),
            game("game-3", "Three"),
        )
        val focusedKey = gameKey(original[1])
        val refreshed = listOf(
            original[0],
            original[1].copy(
                isFavorite = true,
                heroArtUri = "file:///media/two-hero.webp#mv=2",
            ),
            original[2],
        )

        assertEquals(original.map(::gameKey), refreshed.map(::gameKey))
        assertNull(
            gamelistReseedTarget(
                gameKeys = refreshed.map(::gameKey),
                focused = focusedKey,
                rememberedFocus = focusedKey,
            ),
        )
        assertEquals(
            focusedKey,
            gamelistRenderedFocus(
                gameKeys = refreshed.map(::gameKey),
                focused = focusedKey,
                scopeOwnsGamelist = true,
                rememberedFocus = focusedKey,
                pendingRememberedFocus = null,
                retainedGameFocus = focusedKey,
            ),
        )
    }

    @Test
    fun `loaded list truth drops an absent retained detail while loading may retain it`() {
        val retained = game("game-2", title = "Old")
        val refreshed = retained.copy(title = "Fresh")

        assertEquals(refreshed, reconcileLiveGamelistDetail(retained, listOf(refreshed), loadedTruth = true))
        assertEquals(retained, reconcileLiveGamelistDetail(retained, emptyList(), loadedTruth = false))
        assertNull(reconcileLiveGamelistDetail(retained, emptyList(), loadedTruth = true))
    }

    @Test
    fun `verified enrich changes the stable action to Hero and keeps owning backdrop`() {
        val detailKey = gameKeys[1]

        assertEquals(GamelistMediaAction.ENRICH, gamelistMediaAction(ProviderEnrichmentState.NEVER_REQUESTED))
        assertEquals(GamelistMediaAction.HERO, gamelistMediaAction(ProviderEnrichmentState.ATTEMPTED))
        assertEquals(GamelistMediaAction.HERO, gamelistMediaAction(ProviderEnrichmentState.VERIFIED_PARTIAL))
        assertEquals(GamelistMediaAction.HERO, gamelistMediaAction(ProviderEnrichmentState.VERIFIED_COMPLETE))
        assertEquals(detailKey, gamelistBackdropFocusKey(GamelistGetArtKey, detailKey))
        assertEquals(gameKeys[2], gamelistBackdropFocusKey(gameKeys[2], detailKey))
    }

    @Test
    fun `normal and favorites lanes restore memory before choosing one nearest survivor`() {
        val universe = listOf(
            game("game-1", "One", favorite = true),
            game("game-2", "Two"),
            game("game-3", "Three", favorite = true),
            game("game-4", "Four"),
        )
        val favorites = universe.filter(GameRow::isFavorite)

        assertEquals(
            GameId("game-1"),
            resolveGamelistFilterTarget(
                orderedUniverse = universe,
                destination = favorites,
                focusedGameId = GameId("game-2"),
                rememberedDestinationGameId = GameId("game-1"),
            ),
        )
        // With no valid Favorites memory, game 2 is equidistant from 1 and 3; forward wins once.
        assertEquals(
            GameId("game-3"),
            resolveGamelistFilterTarget(
                orderedUniverse = universe,
                destination = favorites,
                focusedGameId = GameId("game-2"),
                rememberedDestinationGameId = GameId("removed"),
            ),
        )
        // Returning to Normal restores its separate non-Favorite identity.
        assertEquals(
            GameId("game-2"),
            resolveGamelistFilterTarget(
                orderedUniverse = universe,
                destination = universe,
                focusedGameId = GameId("game-1"),
                rememberedDestinationGameId = GameId("game-2"),
            ),
        )
    }

    @Test
    fun `filter landing paints and settles destination without treating source as restored`() {
        val pending = PendingGamelistFilterLanding(
            platformId = platform,
            favoritesOnly = false,
            sourceGameId = GameId("game-1"),
            targetGameId = GameId("game-3"),
        )
        val resolution = resolvePendingGamelistFilterLanding(
            pending = pending,
            openPlatform = platform,
            favoritesOnly = false,
            gamesLoadedForPlatform = platform,
            gameKeys = gameKeys,
            focused = gameKeys.first(),
        )

        assertNull(resolution.pending)
        assertEquals(gameKeys[2], resolution.target)
        assertEquals(
            gameKeys[2],
            gamelistRenderedFocus(
                gameKeys = gameKeys,
                focused = gameKeys.first(),
                scopeOwnsGamelist = true,
                rememberedFocus = gameKeys.first(),
                pendingRememberedFocus = null,
                retainedGameFocus = gameKeys.first(),
                pendingFilterFocus = gameKeys[2],
                pendingFilterSourceFocus = gameKeys.first(),
            ),
        )

        val userMoved = resolvePendingGamelistFilterLanding(
            pending = pending,
            openPlatform = platform,
            favoritesOnly = false,
            gamesLoadedForPlatform = platform,
            gameKeys = gameKeys,
            focused = gameKeys[1],
        )
        assertNull(userMoved.pending)
        assertNull(userMoved.target)
    }

    private fun game(id: String, title: String, favorite: Boolean = false) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = platform,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = favorite,
    )
}
