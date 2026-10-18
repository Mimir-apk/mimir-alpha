package com.gruning.mimir.rewrite

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.PlatformTileArtStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.gruning.mimir.rewrite.core.audio.MimirSoundEvent
import com.gruning.mimir.rewrite.core.audio.MimirUiSounds
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.LaunchForegroundObserver
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.browse.dispatchToBrowse
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.GameDetailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.homeShelfCoverUri
import com.gruning.mimir.rewrite.feature.library.squareFaceOf
import com.gruning.mimir.rewrite.feature.library.homeShelfEntryForKey
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioActionRequest
import com.gruning.mimir.rewrite.feature.library.LibraryDetailActionState
import com.gruning.mimir.rewrite.feature.library.LibraryDetailPlayKey
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.dispatchToHomeShelf
import com.gruning.mimir.rewrite.feature.library.dispatchToLibrary
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptOutcome
import com.gruning.mimir.rewrite.feature.library.ProviderEnrichmentState
import com.gruning.mimir.rewrite.feature.library.providerEnrichmentState
import com.gruning.mimir.rewrite.feature.settings.dispatchToSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName

internal fun homeArtPickerFocusedIndex(
    context: android.content.Context,
    cacheKey: String,
    choices: List<HomeTileArtChoice>,
): Int = homeArtPickerFocusedIndex(
    choices = choices,
    hasActivePick = SteamGridDbTileFetcher.tileIconFile(context, cacheKey).isFile,
    uploadedActive = SteamGridDbTileFetcher.uploadedPickActive(context, cacheKey),
    providerIndex = SteamGridDbTileFetcher.activePickIndex(context, cacheKey),
)

internal fun homeArtPickerFocusedIndex(
    choices: List<HomeTileArtChoice>,
    hasActivePick: Boolean,
    uploadedActive: Boolean,
    providerIndex: Int?,
): Int {
    if (!hasActivePick) return 0
    if (uploadedActive) {
        return choices.indexOfFirst { it is HomeTileArtChoice.UploadedImage }.coerceAtLeast(0)
    }
    val resolvedProviderIndex = providerIndex ?: return 0
    return choices.withIndex()
        .filter { it.value is HomeTileArtChoice.Art }
        .getOrNull(resolvedProviderIndex)
        ?.index ?: 0
}

internal fun homeArtPickAfterImport(
    phase: HomeTileMenuPhase.ArtPick,
    retainedPath: String,
): HomeTileMenuPhase.ArtPick {
    val choices = phase.choices.filterNot { it is HomeTileArtChoice.UploadedImage }.toMutableList()
    val insertAt = (choices.indexOf(HomeTileArtChoice.UseDefault) + 1).coerceAtLeast(0)
    choices.add(insertAt, HomeTileArtChoice.UploadedImage(retainedPath))
    return phase.copy(choices = choices, focusedIndex = insertAt, footerFocused = false)
}

internal fun homeHeroPickAfterImport(
    phase: HomeTileMenuPhase.HeroPick,
    retainedPath: String,
): HomeTileMenuPhase.HeroPick {
    val choices = phase.choices.filterNot { it is HomeTileHeroChoice.UploadedImage }.toMutableList()
    val insertAt = (choices.indexOf(HomeTileHeroChoice.UseDefault) + 1).coerceAtLeast(0)
    choices.add(insertAt, HomeTileHeroChoice.UploadedImage(retainedPath))
    return phase.copy(choices = choices, focusedIndex = insertAt, footerFocused = false)
}
/** The tile store's cache key for a shelf row — MUST match the decorator in HomeShelfCoordinator. */
internal fun homeTileMenuCacheKey(itemId: HomeShelfItemId): String = when (itemId) {
    is HomeShelfItemId.Emulated -> SteamGridDbTileFetcher.emulatedKey(itemId.gameId)
    is HomeShelfItemId.AndroidGame -> SteamGridDbTileFetcher.androidKey(itemId.packageName.value)
    is HomeShelfItemId.AndroidApp -> SteamGridDbTileFetcher.androidKey(itemId.packageName.value)
}

/** Stable renderer identity shared by Home projection, saved choices, uploads, and Hero reset. */
internal fun homeTileHeroStoreId(itemId: HomeShelfItemId): GameId =
    GameId(homeTileMenuCacheKey(itemId))

/**
 * The ANDROID shelf's equivalent of [libraryHeroPickerRow] — Y on an installed package opens the same
 * Customize Media overlay, without the package becoming a Home Favorite.
 *
 * Android rows have no GameRow behind them, so the menu is built from what the shelf itself knows.
 * `tileIconUri` AND `artworkUri` both take the launcher icon deliberately: it is the only art the
 * package ships, and leaving `artworkUri` null would open the menu on an empty face.
 */
internal fun nativeAppTileMenuState(
    packageName: String,
    label: String,
    iconPath: String?,
    isGame: Boolean,
    // Null: the menu carries no favourite row (the Android shelf's own Y menu, as it always was). Non-null is the menu an Android game's Library card opens, and says whether the
    // game is a favourite right now: Remove favorite for one, Add favorite for one that is not. An app cannot be one.
    favorite: Boolean? = null,
): HomeTileMenuState =
    HomeTileMenuState(
        row = HomeShelfRow(
            itemId = if (isGame) {
                HomeShelfItemId.AndroidGame(AndroidPackageName(packageName))
            } else {
                HomeShelfItemId.AndroidApp(AndroidPackageName(packageName))
            },
            rank = 0L,
            addedAtMillis = 0L,
            title = label,
            artworkUri = iconPath,
            tileIconUri = iconPath,
            face = squareFaceOf(iconPath),
            supportingLabel = if (isGame) "Android game" else "Android app",
        ),
        removable = isGame && favorite == true,
        offerFavorite = isGame && favorite != null,
    )

/**
 * The exact [GameRow] a tile-menu action acts on (Achievements, hero and clip loading, Enrich). The Library's menus carry theirs ([HomeTileMenuState.sourceGame]),
 * so a Library game that is not a Home favourite still resolves; Home Edit has no such field and asks the shelf (which only knows its own games). Asking the shelf
 * first, or alone, left every non-favourite Library game with null: Achievements did nothing and hero loading ended at "no longer available in Favorites".
 */
internal fun homeTileMenuGame(state: HomeTileMenuState, homeGameFor: (HomeShelfRow) -> GameRow?): GameRow? =
    state.sourceGame ?: homeGameFor(state.row)

/** Library games reuse the established Customize Media overlay without becoming Home Favorites. */
internal fun libraryHeroPickerRow(game: GameRow): HomeShelfRow = HomeShelfRow(
    itemId = HomeShelfItemId.Emulated(game.gameId),
    rank = 0L,
    addedAtMillis = 0L,
    title = game.title,
    artworkUri = homeShelfCoverUri(game.platformId, game.artUri, game.flatArtUri),
    supportingLabel = game.platformId.value,
    platformId = game.platformId,
    flatArtUri = game.flatArtUri,
    physicalMediaUri = game.physicalMediaUri,
    previewVideoUri = game.previewVideoUri,
    genre = game.genre,
    description = game.description,
    descriptionSource = game.descriptionSource,
)

/**
 * the gamelist's
 * renamed Settings affordance lands on the TOP-LEVEL menu — Customize media / Add to Tome / Achievements,
 * [HomeTileMenuState]'s own default `phase` — and the player chooses, exactly like the Home tile Edit menu.
 * It previously forced straight into the media sub-menu (the retired CustomEnrich phase), which is why
 * Add to Tome and Achievements were unreachable from the gamelist at all.
 *
 * `customEnrichFocus` still pre-aims at Hero art: once the player explicitly picks "Customize media" from
 * the top-level menu, the page opens on Hero art.
 *
 * a verified-partial
 * game (provider answered, something's still missing) also gets Enrich back in this same menu, on
 * idempotency grounds. [ProviderEnrichmentState.NEVER_REQUESTED] never reaches this function at all — that
 * state's seat performs Enrich directly (`gamelistMediaAction`) and never opens this menu — so checking
 * only for [ProviderEnrichmentState.VERIFIED_PARTIAL] here is exhaustive for every real caller, not an
 * incomplete guard.
 */
/**
 * Whether a game's Y menu leads with Enrich. NEVER_REQUESTED: only compact's Y reaches the Library menu with one, since it paints no
 * Enrich pill; Enrich leads the list there too. VERIFIED_PARTIAL allows retry only after a FAILED attempt.
 * A finished attempt hides it, even with missing logo or cover. ATTEMPTED and COMPLETE never lead with it; the re-fetch is Customize
 * media. A fact about the GAME, so every door's menu for it asks this one function: the Library's
 * ([libraryHeroPickerMenuState]) and Home's ([homeTileMenuState]) list the same rows in the same order for the same game (the same menu on both doors).
 */
internal fun GameRow.tileMenuOffersEnrich(): Boolean = providerEnrichmentState().let {
    it.shouldOfferEnrich ||
        (it == ProviderEnrichmentState.VERIFIED_PARTIAL && enrichAttempt == EnrichAttemptOutcome.FAILED)
}

/**
 * Home's Y menu for [row]: the card's, the shelf tile's and a Tome member's. [game] is the row's game when it is an emulated one (Home asks its shelf), and Enrich leads the menu
 * when that game can be enriched, exactly as it does in the Library's menu for the same game. [removable] is whether the row is a favourite (every Home row is).
 */
internal fun homeTileMenuState(
    row: HomeShelfRow,
    game: GameRow?,
    removable: Boolean,
    activeCustomTomeId: TomeId.Custom? = null,
): HomeTileMenuState = HomeTileMenuState(
    row = row,
    removable = removable,
    activeCustomTomeId = activeCustomTomeId,
    offerEnrich = game?.tileMenuOffersEnrich() == true,
)

// Customize media opens on Icon art from this door too (Icon art on both doors): no type is pre-seeded here, so
// [customMediaOpening] falls through to [customMediaEntryDefault]. Until 10-13 this seeded Hero art .
internal fun libraryHeroPickerMenuState(game: GameRow): HomeTileMenuState =
    HomeTileMenuState(
        row = libraryHeroPickerRow(game),
        // Favourite lives in this menu on the Library's door : a favourite offers Remove favorite, any other game Add favorite
        // ([homeTileMenuActions]). It is still not made a Home favourite by opening the menu.
        removable = game.isFavorite,
        offerFavorite = true,
        sourceGame = game,
        entryPoint = HomeTileMenuEntryPoint.LIBRARY_HERO,
        offerEnrich = game.tileMenuOffersEnrich(),
    )

/**
 * DORMANT: the Edit menu's icon actions this serves (`onApplyPick`, `onApplyUploadedArt`, `onResetArt` in the coordinator) hang off the Home
 * `ArtPick` phase, which nothing enters now: `reduceHomeTileMenu` takes `onLoadArt` (the only thing that builds that phase) and never calls it.
 * The call and its source pin stay so the day a path does enter it, it publishes; the LIVE path is the merged picker's apply,
 * which funnels through `refreshCustomEnrichOwner` -> `LibraryRepository.refreshArt(gameId)`, and that re-projects every open row flow and so
 * re-reads each row's shelf face (pinned in `IconArtStoreTest` and `LibraryRepositoryShelfFaceTest`).
 *
 * What the Edit menu's icon actions (pick, upload, Default) publish once they have changed a game's icon: Home's own tiles, and
 * ONE re-projection of the Library's open flows so the Library shelf, the Bottom and the launch card repaint
 * ([LibraryRepository.refreshShelfFaces]). They used to refresh Home's tiles and nothing else, so a square picked here showed on
 * Home and not on the Library until the player left it and came back (one shelf face).
 */
internal fun publishTileMenuIconChange(refreshHomeTiles: () -> Unit, refreshShelfFaces: () -> Unit) {
    refreshHomeTiles()
    refreshShelfFaces()
}
