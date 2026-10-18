package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryDetailTargetTest {

    @Test
    fun emulatedTargetDerivesTypedShelfIdentityAndSourceKey() {
        val game = gameRow()

        val target = LibraryDetailTarget.Emulated(game)

        assertEquals(HomeShelfItemId.Emulated(game.gameId), target.itemId)
        assertEquals(FocusKey("library/gamelist/${game.gameId.value}"), target.sourceFocusKey)
    }

    @Test
    fun emulatedTargetRejectsMismatchedShelfIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryDetailTarget.Emulated(
                game = gameRow(),
                itemId = HomeShelfItemId.Emulated(GameId("gba/other")),
            )
        }
    }

    @Test
    fun androidGameTargetIsTypedAndAndroidAppCannotBecomeDetailTarget() {
        val game = nativeRow(packageName = "com.example.game", isGame = true)
        val app = nativeRow(packageName = "com.example.app", isGame = false)

        val target = game.toLibraryDetailTargetOrNull()

        assertEquals(
            HomeShelfItemId.AndroidGame(AndroidPackageName(game.packageName)),
            target?.itemId,
        )
        assertEquals(nativeShelfKey(game.packageName), target?.sourceFocusKey)
        assertNull(app.toLibraryDetailTargetOrNull())
        assertThrows(IllegalArgumentException::class.java) {
            LibraryDetailTarget.AndroidGame(app)
        }
    }

    @Test
    fun androidGameTargetRejectsMismatchedShelfIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryDetailTarget.AndroidGame(
                row = nativeRow(packageName = "com.example.game", isGame = true),
                itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.other")),
            )
        }
    }

    @Test
    fun detailActionKeysAndLabelsAreStable() {
        val target = LibraryDetailTarget.Emulated(gameRow())

        assertEquals("library/detail/play", LibraryDetailPlayKey.value)
        assertEquals("library/detail/restart", LibraryDetailRestartKey.value)
        assertEquals("library/detail/openwith", LibraryDetailOpenWithKey.value)
        assertEquals("library/detail/achievements", LibraryDetailAchievementsKey.value)
        // Favourite is the Y menu's row now, not a button: the action state carries only whether the game is one.
        assertEquals(false, LibraryDetailActionState(target, isOnShelf = false).isFavorite)
        assertEquals(true, LibraryDetailActionState(target, isOnShelf = true).isFavorite)
    }

    /** Favourite is off the card's button row : the Library contract names Play, Restart, Open with and Achievements and no favourite key. */
    @Test
    fun theLibraryContractHasNoFavouriteSlot() {
        val contract = LibraryLaunchCardFocusContract
        assertEquals(GameDetailScope, contract.scope)
        assertEquals(
            listOf(
                LibraryDetailTitleEditKey,
                LibraryDetailPlayKey,
                LibraryDetailRestartKey,
                LibraryDetailOpenWithKey,
                LibraryDetailAchievementsKey,
            ),
            homeLaunchCardActionKeys(emulated = true, hasExtraAction = true, contract = contract),
        )
        assertEquals(
            listOf(LibraryDetailTitleEditKey, LibraryDetailPlayKey, LibraryDetailOpenWithKey, LibraryDetailAchievementsKey),
            homeLaunchCardActionKeys(emulated = true, hasExtraAction = false, contract = contract),
        )
        assertEquals(listOf(LibraryDetailPlayKey), homeLaunchCardActionKeys(emulated = false, contract = contract))
    }

    @Test
    fun launchCardUsesResolvedPlatformCopyAndNeverLeaksTheRawSlug() {
        val target = LibraryDetailTarget.Emulated(gameRow())
        val resolved = libraryLaunchCardUiState(
            target = target,
            metadata = LibraryDetailMetadata(),
            achievementProgress = null,
            statusLine = null,
            screenScraperConnected = false,
            platformLabel = "Game Boy Advance",
        )
        val fallback = libraryLaunchCardUiState(
            target = target,
            metadata = LibraryDetailMetadata(),
            achievementProgress = null,
            statusLine = null,
            screenScraperConnected = false,
        )

        assertEquals("Game Boy Advance", resolved.platformLabel)
        assertEquals("GBA", fallback.platformLabel)
    }

    /**
     * Provider-specific copy, plus the coordinator's follow-up finding: `HomeLaunchCardUiState`
     * has two production constructors, and the Library gamelist detail card built here (via
     * `libraryLaunchCardUiState`) is the second — Home's `HomeLaunchCardLiveContent` is the other. A keyed
     * `pc_games` row on THIS card must reach the same "Use Enrich" copy Home shows, never the keyless
     * "add a key" sentence naming a key the player already has — the same dishonesty class as the clip
     * empty-state defect that started this thread ("Connect ScreenScraper or add a SteamGridDB key" shown
     * to a man with both connected). Runs the two real production functions together
     * (`libraryLaunchCardUiState` then `homeShelfDescriptionText`, with the identical `isPcGames` expression
     * `HomeLaunchCard.kt`'s own render uses) rather than a Compose render, because `feature-library`'s unit
     * tests carry no Robolectric dependency to render through — the same reason
     * `PcEnrichSteamGridDbRoutingWiringTest` characterizes rather than renders.
     */
    @Test
    fun `Library detail card gives a keyed pc_games row the Enrich sentence, never the add-a-key sentence`() {
        val target = LibraryDetailTarget.Emulated(pcGameRow())

        val keyed = libraryLaunchCardUiState(
            target = target,
            metadata = LibraryDetailMetadata(),
            achievementProgress = null,
            statusLine = null,
            screenScraperConnected = false,
            steamGridDbKeyPresent = true,
        )
        val keyless = libraryLaunchCardUiState(
            target = target,
            metadata = LibraryDetailMetadata(),
            achievementProgress = null,
            statusLine = null,
            screenScraperConnected = false,
            steamGridDbKeyPresent = false,
        )

        assertEquals(true, keyed.steamGridDbKeyPresent)
        assertEquals(false, keyless.steamGridDbKeyPresent)
        assertEquals(
            "No description yet.",
            homeShelfDescriptionText(
                keyed.row.description,
                keyed.row.descriptionSource,
                screenScraperConnected = keyed.screenScraperConnected,
                isPcGames = keyed.row.platformId == PlatformId.PC_GAMES,
                steamGridDbKeyPresent = keyed.steamGridDbKeyPresent,
            ),
        )
        assertEquals(
            "No description yet. Add a SteamGridDB key in Settings › Appearance › Enrichment & artwork.",
            homeShelfDescriptionText(
                keyless.row.description,
                keyless.row.descriptionSource,
                screenScraperConnected = keyless.screenScraperConnected,
                isPcGames = keyless.row.platformId == PlatformId.PC_GAMES,
                steamGridDbKeyPresent = keyless.steamGridDbKeyPresent,
            ),
        )
    }

    private fun gameRow() = GameRow(
        gameId = GameId("gba/metroid-fusion"),
        title = "Metroid Fusion",
        platformId = PlatformId.GBA,
        memberCount = 1,
        duplicateKind = null,
    )

    /** No `description`/`descriptionSource` (both default null) -- the keyless/keyed no-description case. */
    private fun pcGameRow() = GameRow(
        gameId = GameId("pc_games/keyed-example"),
        title = "Keyed Example",
        platformId = PlatformId.PC_GAMES,
        memberCount = 1,
        duplicateKind = null,
    )

    private fun nativeRow(packageName: String, isGame: Boolean) = NativeShelfRow(
        packageName = packageName,
        label = packageName.substringAfterLast('.'),
        launchActivity = null,
        isGame = isGame,
    )
}
