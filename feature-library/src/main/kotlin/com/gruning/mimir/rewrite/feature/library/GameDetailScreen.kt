package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.LocalFocusAuthority
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.mimirPlatformShelfmarkCode

data class LibraryDetailDisc(
    val number: Int,
    val displayName: String,
)

/** Track payload extensions — the members that are never a disc's launchable root. */
internal val LibraryDetailTrackExtensions = setOf("bin", "img")

/**
 * The distinct physical discs behind a collapsed game, one row per disc, ordered by disc number.
 *
 * **Dedupe is load-bearing, not tidying.** `ScanRecognizer.discNumberOf` reads the disc token off the
 * FILENAME, so `Game (Disc 2).cue` and every one of its `Game (Disc 2) (Track NN).bin` siblings all carry
 * `discNumber = 2`, and `GAME_MEMBERS_READ_SQL` filters none of them. Rendering members directly — which
 * is what the read-only display did before these rows became launchable — repeats "Disc 2" once per
 * track, and would have offered the same disc as several different things to activate.
 *
 * Within one disc the surviving row prefers a member that is NOT a `.bin`/`.img` track, mirroring
 * `multiDiscLaunchMembers`' comparator in `core-launch` so the name shown is the name of the root that
 * will actually be launched. It mirrors rather than shares it: that comparator is `internal` to
 * `core-launch`, and [GameMemberRow] carries no `contentKind` to sort on. This only picks the LABEL —
 * the launch still crosses the seam as a disc NUMBER, so a mismatch here can never launch the wrong file.
 */
fun libraryDetailDiscs(members: List<GameMemberRow>): List<LibraryDetailDisc> = members
    .asSequence()
    .filter { it.discNumber > 0 }
    .groupBy { it.discNumber }
    .toSortedMap()
    .map { (number, sameDisc) ->
        val root = sameDisc.minWith(
            compareBy(
                { member: GameMemberRow ->
                    if (member.displayName.substringAfterLast('.', "").lowercase()
                        in LibraryDetailTrackExtensions
                    ) {
                        1
                    } else {
                        0
                    }
                },
                GameMemberRow::displayName,
                { member: GameMemberRow -> member.entryKey.value },
            ),
        )
        LibraryDetailDisc(number = number, displayName = root.displayName)
    }

data class LibraryDetailMetadata(
    /** The emulator the launch would start ([libraryDetailLauncherLabel]); null keeps the card's pre-change "Automatic". */
    val launcherLabel: String? = null,
    val sizeBytes: Long? = null,
    val discs: List<LibraryDetailDisc> = emptyList(),
)

/**
 * Tears the Library card's virtual actions down when its session ends: the shared registrar's keys under the Library contract, and the description key the card's
 * composable registered with its physical bounds. Registration is the shared [registerHomeLaunchCardActions] (the card's own composable resolves the geometry and
 * calls it with [LibraryLaunchCardFocusContract]), so there is no Library-only bounds solver left to keep in step with it.
 */
internal fun unregisterLibraryDetailSessionActions(authority: FocusAuthority) {
    unregisterHomeLaunchCardActions(authority, LibraryLaunchCardFocusContract)
    authority.unregisterVirtual(LibraryDetailDescriptionKey)
}

/**
 * Compatibility entry point for the existing emulated-game host. The typed overload below is also used by
 * Android-game detail and exposes the primary-owned action contract to later host wiring.
 */
@Composable
fun GameDetailScreen(
    viewModel: LibraryViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    authority: FocusAuthority = LocalFocusAuthority.current,
    actionState: LibraryDetailActionState? = null,
    achievementProgress: String? = null,
    statusLine: String? = null,
    runtimeStatusLine: String? = null,
    screenScraperConnected: Boolean = false,
    steamGridDbKeyPresent: Boolean = false,
    cartridgeArtUri: String? = null,
    /** Whether [cartridgeArtUri] is physical media. Threaded, not inferred — see the parameter of the
     *  same name on [libraryLaunchCardUiState] for the box-squaring defect this closes. */
    cartridgeArtIsPhysical: Boolean = false,
    descriptionScrollOwnerToken: Long = 0L,
    descriptionScrollCommand: HomeLaunchDescriptionScrollCommand = HomeLaunchDescriptionScrollCommand(),
    onDescriptionScrollStateChanged: (HomeLaunchDescriptionScrollState) -> Unit = {},
    onOpenAchievements: (LibraryDetailTarget.Emulated) -> Unit = {},
    onEditTitle: (LibraryDetailTarget.Emulated) -> Unit = {},
) {
    val live by viewModel.gameDetail.collectAsState()
    val opened by viewModel.openGameDetail.collectAsState()
    val platforms by viewModel.platforms.collectAsState()
    val game = live ?: opened ?: return
    val members by viewModel.gameDetailMembers.collectAsState()
    val openWith by viewModel.openWith.collectAsState()
    val target = actionState?.target ?: LibraryDetailTarget.Emulated(game)

    // Round 5b: the emulator the launch would start, else the card's pre-change text (libraryDetailLauncherLabel).
    val launcherLabel = libraryDetailLauncherLabel(openWith)
    val sizeBytes = launchCardSizeBytes(game, members)
    val discs = libraryDetailDiscs(members)

    GameDetailScreen(
        actionState = actionState ?: LibraryDetailActionState(target = target, isOnShelf = false),
        metadata = LibraryDetailMetadata(
            launcherLabel = launcherLabel,
            sizeBytes = sizeBytes,
            discs = discs,
        ),
        authority = authority,
        onPlay = { selected ->
            if (selected is LibraryDetailTarget.Emulated) viewModel.launch(selected.game)
        },
        onOpenWith = { selected ->
            if (viewModel.loadOpenWith(selected.game)) authority.pushScope(OpenWithScope)
        },
        achievementProgress = achievementProgress,
        statusLine = statusLine,
        runtimeStatusLine = runtimeStatusLine,
        platformLabel = platforms.firstOrNull { it.platformId == game.platformId }?.label,
        screenScraperConnected = screenScraperConnected,
        steamGridDbKeyPresent = steamGridDbKeyPresent,
        cartridgeArtUri = cartridgeArtUri,
        cartridgeArtIsPhysical = cartridgeArtIsPhysical,
        onOpenAchievements = onOpenAchievements,
        onEditTitle = onEditTitle,
        descriptionScrollOwnerToken = descriptionScrollOwnerToken,
        descriptionScrollCommand = descriptionScrollCommand,
        onDescriptionScrollStateChanged = onDescriptionScrollStateChanged,
        onClose = onClose,
        modifier = modifier,
    )
}

/**
 * The Library door's adapter onto THE launch card ([HomeLaunchCard], the one Home draws): it turns this module's typed target into the card's state and its callbacks,
 * and names the Library's focus contract. The card, its dispatcher, its bounds solver and its action set (Play or Resume, Restart while parked, Open with, Achievements)
 * are Home's; favourite lives in the Y menu on both doors.
 */
@Composable
fun GameDetailScreen(
    actionState: LibraryDetailActionState,
    onPlay: (LibraryDetailTarget) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    authority: FocusAuthority = LocalFocusAuthority.current,
    metadata: LibraryDetailMetadata = LibraryDetailMetadata(),
    onOpenWith: ((LibraryDetailTarget.Emulated) -> Unit)? = null,
    achievementProgress: String? = null,
    statusLine: String? = null,
    runtimeStatusLine: String? = null,
    platformLabel: String? = null,
    screenScraperConnected: Boolean = false,
    steamGridDbKeyPresent: Boolean = false,
    /**
     * The already-resolved LAUNCH CARD face for this card, or null to paint the cover.
     *
     * **Corrected 2026-08-26.** This said the gate and the pick order *"both live in
     * `homeLaunchCardCartridgeArtUri`"*. They do not any more: `:app` resolves this through
     * `launchCardWiredFace`, which runs the same FL-2 ladder as the Home-side card — his launch-card
     * pick, then the article (ungated: the switch rules the shelves), then the Shelf's resolved face.
     * The old resolver was gated on Cartridge Hero, which vetoed a hand-picked cartridge and is what C4
     * forbids. What has not changed: this module still never learns that either store exists.
     */
    cartridgeArtUri: String? = null,
    /** Whether [cartridgeArtUri] is physical media. Now that L3 can hand over a wired COVER, this must
     *  travel WITH the uri rather than be assumed — the two are one fact. */
    cartridgeArtIsPhysical: Boolean = false,
    descriptionScrollOwnerToken: Long = 0L,
    descriptionScrollCommand: HomeLaunchDescriptionScrollCommand = HomeLaunchDescriptionScrollCommand(),
    onDescriptionScrollStateChanged: (HomeLaunchDescriptionScrollState) -> Unit = {},
    onOpenAchievements: (LibraryDetailTarget.Emulated) -> Unit = {},
    onEditTitle: (LibraryDetailTarget.Emulated) -> Unit = {},
) {
    val target = actionState.target
    val uiState = libraryLaunchCardUiState(
        target = target,
        metadata = metadata,
        achievementProgress = achievementProgress,
        statusLine = statusLine,
        runtimeStatusLine = runtimeStatusLine,
        platformLabel = platformLabel,
        screenScraperConnected = screenScraperConnected,
        steamGridDbKeyPresent = steamGridDbKeyPresent,
        cartridgeArtUri = cartridgeArtUri,
        cartridgeArtIsPhysical = cartridgeArtIsPhysical,
        externalSessionElapsedMillis = actionState.externalSessionElapsedMillis,
    )
    HomeLaunchCard(
        state = uiState,
        onPlay = { onPlay(target) },
        onAchievements = {
            (target as? LibraryDetailTarget.Emulated)?.let(onOpenAchievements)
        },
        onOpenWith = {
            (target as? LibraryDetailTarget.Emulated)?.let { onOpenWith?.invoke(it) }
        },
        onEditTitle = {
            (target as? LibraryDetailTarget.Emulated)?.let(onEditTitle)
        },
        onClose = onClose,
        modifier = modifier,
        authority = authority,
        focusContract = LibraryLaunchCardFocusContract,
        descriptionScrollOwnerToken = descriptionScrollOwnerToken,
        descriptionScrollCommand = descriptionScrollCommand,
        onDescriptionScrollStateChanged = onDescriptionScrollStateChanged,
    )
}

internal fun libraryLaunchCardUiState(
    target: LibraryDetailTarget,
    metadata: LibraryDetailMetadata,
    achievementProgress: String?,
    statusLine: String?,
    runtimeStatusLine: String? = null,
    screenScraperConnected: Boolean,
    platformLabel: String? = null,
    steamGridDbKeyPresent: Boolean = false,
    cartridgeArtUri: String? = null,
    /**
     * Whether [cartridgeArtUri] is physical media.
     *
     * **That day arrived, 2026-08-26.** This used to justify its `true` default by saying *"every caller
     * resolves the value through `homeLaunchCardCartridgeArtUri`, whose two tiers are both cartridges by
     * construction"*, and noted it was a parameter *"so that a caller which one day hands over a wired
     * COVER can say so, instead of having its box squared off at the disc aspect."* The Library caller
     * now runs the full FL-2 ladder, whose L3 tier inherits the Shelf's resolved face — which is a COVER
     * whenever he picked one. **Callers must pass this explicitly; the default is now the risk it warned
     * about, kept only so existing test call sites read unchanged.**
     */
    cartridgeArtIsPhysical: Boolean = false,
    /** The game's total session time , from the app's elapsed store: the same total the Home card reads. Null = none tracked. */
    externalSessionElapsedMillis: Long? = null,
): HomeLaunchCardUiState {
    val row = when (target) {
        is LibraryDetailTarget.Emulated -> HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(target.game.gameId),
            rank = 0L,
            addedAtMillis = 0L,
            title = target.game.title,
            artworkUri = target.game.libraryCoverArtUri(),
            supportingLabel = target.game.platformId.value,
            platformId = target.game.platformId,
            flatArtUri = target.game.flatArtUri,
            previewVideoUri = target.game.previewVideoUri,
            genre = target.game.genre,
            description = target.game.description,
            descriptionSource = target.game.descriptionSource,
            externalSessionElapsedMillis = externalSessionElapsedMillis,
        )
        is LibraryDetailTarget.AndroidGame -> HomeShelfRow(
            itemId = HomeShelfItemId.AndroidGame(AndroidPackageName(target.row.packageName)),
            rank = 0L,
            addedAtMillis = 0L,
            title = target.row.label,
            artworkUri = target.row.iconPath,
            tileIconUri = target.row.iconPath,
            face = squareFaceOf(target.row.iconPath),
            supportingLabel = target.row.rowKindLabel ?: "Android game",
            externalSessionElapsedMillis = externalSessionElapsedMillis,
        )
    }
    val game = (target as? LibraryDetailTarget.Emulated)?.game
    val extraFacts = launchCardFacts(game, metadata.sizeBytes, metadata.discs)
    return HomeLaunchCardUiState(
        row = row,
        platformLabel = target.detailPlatformLabel(platformLabel),
        coverArtUri = game?.libraryCoverArtUri(),
        cartridgeArtIsPhysical = cartridgeArtIsPhysical,
        statusLine = statusLine,
        runtimeStatusLine = runtimeStatusLine,
        achievementProgress = achievementProgress,
        emulatorLabel = metadata.launcherLabel,
        lastPlayedLabel = game?.lastPlayedMillis?.let(::relativePlayed),
        screenScraperConnected = screenScraperConnected,
        steamGridDbKeyPresent = steamGridDbKeyPresent,
        cartridgeArtUri = cartridgeArtUri,
        extraFacts = extraFacts,
    )
}

private fun LibraryDetailTarget.detailPlatformLabel(resolvedLabel: String?): String = when (this) {
    is LibraryDetailTarget.Emulated -> resolvedLabel?.takeIf(String::isNotBlank)
        ?: mimirPlatformShelfmarkCode(game.platformId.value)
        ?: "Unknown platform"
    is LibraryDetailTarget.AndroidGame -> row.rowKindLabel ?: "Android game"
}

// Size formatting shared with the companion detail payload lives in LibraryComponents.formatSizeLabel.
