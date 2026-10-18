package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.DescriptionText
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.core.ui.MimirTimeCopy
import java.util.UUID

/** Primary Home content and its quick rail use separate scopes so popScope restores the exact game. */
val HomeShelfScope = FocusScope("home/shelf")
val HomeShelfQuickRailScope = FocusScope("home/rail")

val HomeShelfOpenLibraryKey = FocusKey("home/empty/open-library")

/**
 * Frontend-only Home identity. A Tome is deliberately not a [HomeShelfItemId]: only [Game] can be
 * resolved to a playable row, so collection presentation can never leak into launch dispatch.
 */
sealed interface TomeId {
    data class Platform(val platformId: PlatformId) : TomeId

    data class Custom(val id: UUID) : TomeId

    /**
     * Aggregate Home Folio identity. It is neither a platform nor a custom Tome, so it cannot be
     * pinned or edited as a collection.
     */
    data object HomeOverview : TomeId
}

fun TomeId.stableValue(): String = when (this) {
    is TomeId.Platform -> "platform/${platformId.value}"
    is TomeId.Custom -> "custom/$id"
    TomeId.HomeOverview -> "home/overview"
}

sealed interface HomeShelfEntry {
    val focusKey: FocusKey

    data class Game(val itemId: HomeShelfItemId) : HomeShelfEntry {
        override val focusKey: FocusKey = homeShelfFocusKey(itemId)
    }

    data class Tome(val tomeId: TomeId) : HomeShelfEntry {
        override val focusKey: FocusKey = homeShelfTomeFocusKey(tomeId)
    }
}

data class HomeTomePreview(
    val artUri: String,
    /** Known emulated platform identity, retained for accessibility/test identity only. */
    val platformId: PlatformId? = null,
    /**
     * What this preview's art is, as the resolver said it: a cover, the game article, or a square icon. Never inferred from the
     * uri or the platform. A separately scraped provider support image is [ShelfFaceShape.PHYSICAL].
     */
    val shape: ShelfFaceShape = ShelfFaceShape.LEDGE_COVER,
    /** Stable game identity shared by the Tome plate and its Home backdrop presentation. */
    val itemId: HomeShelfItemId? = null,
    /** Hero/ordinary art for the same game. Physical media is never eligible for the full-screen stage. */
    val backdropArtUri: String? = null,
    /**
     * The game's HERO slot and nothing else: the hero picked in Customize media, else the downloaded `-hero`
     * image (the column the Home shelf's own hero backdrop reads). Null when the game has neither -- never the
     * cover, the tile icon or physical media. The Tome Library's background reads this, so a game without a
     * hero shows the theme backdrop rather than a cover .
     */
    val heroArtUri: String? = null,
) {
    init {
        require(artUri.isNotBlank()) { "A Tome preview art URI must not be blank" }
    }

    /** Whether the art is the game article (a cartridge, disc or card render): it is fitted whole and is never stage art. */
    val isPhysicalMedia: Boolean get() = shape == ShelfFaceShape.PHYSICAL
}

data class HomeShelfTome(
    val tomeId: TomeId,
    val title: String,
    /** Already Tome-scoped and caller-authored; the visual never re-sorts manual member order. */
    val previews: List<HomeTomePreview>,
    val memberCount: Int,
) {
    val previewArtUris: List<String> get() = previews.map(HomeTomePreview::artUri)

    init {
        require(title.isNotBlank()) { "Tome title must not be blank" }
        require(memberCount >= 0) { "Tome member count must not be negative" }
        require(previews.size <= 4) { "A Tome preview accepts at most four authored seats" }
    }
}

fun homeShelfTomeFocusKey(tomeId: TomeId): FocusKey =
    FocusKey("home/shelf/tome/${encodeFocusPathSegment(tomeId.stableValue())}")

fun homeShelfEntryForKey(entries: List<HomeShelfEntry>, key: FocusKey?): HomeShelfEntry? =
    key?.let { focused -> entries.firstOrNull { it.focusKey == focused } }

/** One-shot exact Tome return intent. Generation prevents an old effect from reviving stale focus. */
data class HomeShelfTomeReturn(
    val focusKey: FocusKey,
    val generation: Long,
    val deferredActions: List<ControllerAction> = emptyList(),
)

data class HomeShelfTomeReturnState(
    val generation: Long = 0L,
    val pending: HomeShelfTomeReturn? = null,
)

enum class HomeShelfTomeReturnGate { READY, MISSING, STALE }
enum class HomeShelfTomeReturnOutcome { SETTLED, FAILED }

private fun nextHomeShelfTomeReturnGeneration(current: Long): Long {
    // Equality, not ordering, validates a transaction. Explicit wrap avoids overflow exceptions while
    // retaining a full 64-bit non-repeating window within any realistic process lifetime.
    return if (current == Long.MAX_VALUE) Long.MIN_VALUE else current + 1L
}

fun beginHomeShelfTomeReturn(
    state: HomeShelfTomeReturnState,
    focusKey: FocusKey,
): HomeShelfTomeReturnState {
    val generation = nextHomeShelfTomeReturnGeneration(state.generation)
    return HomeShelfTomeReturnState(
        generation = generation,
        pending = HomeShelfTomeReturn(focusKey, generation),
    )
}

/** Idle Home interaction is allocation-free; a live transaction advances once and clears. */
fun cancelHomeShelfTomeReturn(state: HomeShelfTomeReturnState): HomeShelfTomeReturnState =
    if (state.pending == null) state else {
        HomeShelfTomeReturnState(generation = nextHomeShelfTomeReturnGeneration(state.generation))
    }

const val HomeShelfTomeDeferredActionCap = 8

fun isHomeShelfTomeDeferredContentAction(action: ControllerAction): Boolean = when (action) {
    ControllerAction.NAV_UP,
    ControllerAction.NAV_DOWN,
    ControllerAction.NAV_LEFT,
    ControllerAction.NAV_RIGHT,
    ControllerAction.CONFIRM,
    ControllerAction.SECONDARY,
    ControllerAction.TERTIARY,
    ControllerAction.SYSTEM_SELECT -> true
    else -> false
}

/** Retargets the transaction generation so the sole screen requester lands before ordered replay. */
fun deferHomeShelfTomeReturnAction(
    state: HomeShelfTomeReturnState,
    action: ControllerAction,
): HomeShelfTomeReturnState {
    val pending = state.pending ?: return state
    if (!isHomeShelfTomeDeferredContentAction(action) ||
        pending.deferredActions.size >= HomeShelfTomeDeferredActionCap
    ) {
        return state
    }
    val generation = nextHomeShelfTomeReturnGeneration(state.generation)
    return HomeShelfTomeReturnState(
        generation = generation,
        pending = pending.copy(
            generation = generation,
            deferredActions = pending.deferredActions + action,
        ),
    )
}

fun finishHomeShelfTomeReturn(
    state: HomeShelfTomeReturnState,
    generation: Long,
    outcome: HomeShelfTomeReturnOutcome,
): HomeShelfTomeReturnState =
    when (outcome) {
        HomeShelfTomeReturnOutcome.SETTLED,
        HomeShelfTomeReturnOutcome.FAILED -> if (
            state.pending?.generation == generation && state.generation == generation
        ) {
            state.copy(pending = null)
        } else {
            state
        }
    }

fun gateHomeShelfTomeReturn(
    state: HomeShelfTomeReturnState,
    generation: Long,
    itemKeys: Collection<FocusKey>,
): HomeShelfTomeReturnGate {
    val pending = state.pending
    return when {
        pending == null || pending.generation != generation || state.generation != generation ->
            HomeShelfTomeReturnGate.STALE
        pending.focusKey !in itemKeys -> HomeShelfTomeReturnGate.MISSING
        else -> HomeShelfTomeReturnGate.READY
    }
}

/** Touch/click closure guard across the Back frame: logical and still-painted lanes must agree. */
fun canActivateHomeShelfContent(
    state: HomeShelfTomeReturnState,
    visibleContextKey: String,
    logicalContextKey: String,
): Boolean = state.pending == null && visibleContextKey == logicalContextKey

internal val HomeShelfQuickActions = listOf(
    MimirQuickRailAction.HOME,
    MimirQuickRailAction.LIBRARY,
    MimirQuickRailAction.BRIGHTNESS,
    MimirQuickRailAction.SETTINGS,
    MimirQuickRailAction.QUICK_LAUNCH,
    // The Files end-cap, last, because it is an end-cap: it terminates the rail rather than joining
    // the five navigation seats, and it is the one entry here that opens a surface instead of moving
    // between them. Order in this list is the rail's draw order AND its focus order -- the two must
    // not diverge, which is the same lockstep rule brightnessControls carries for the Utility rail.
    MimirQuickRailAction.FILES,
)

/**
 * The dock's seats with the Files end-cap offered or withdrawn. while a game runs the Files seat is withdrawn,
 * from the rail, the focus order and the press alike, and it comes back when the game ends. Absence, the way the Utility row does it:
 * the five remaining seats recentre. This module has no view of the session ledger; the app passes the answer to the screen and to
 * [dispatchToHomeShelf], so the pixels and the D-pad agree.
 */
internal fun homeShelfQuickActions(filesSeatOffered: Boolean): List<MimirQuickRailAction> =
    if (filesSeatOffered) HomeShelfQuickActions else HomeShelfQuickActions - MimirQuickRailAction.FILES

/**
 * Where the dock lands on entry (NAV_DOWN): HOME when nothing is remembered (NF01/HF01), nothing when the remembered seat is offered (the
 * scope push restored it), and the remembered seat's nearest offered neighbour when the host has withdrawn it: a withdrawn seat is not
 * registered, so the push landed on the first registered seat, and a lit seat that is withdrawn falls to its neighbour the same way.
 */
internal fun homeShelfQuickRailLanding(remembered: MimirQuickRailAction?, actions: List<MimirQuickRailAction>): MimirQuickRailAction? =
    when {
        remembered == null -> MimirQuickRailAction.HOME
        remembered in actions -> null
        else -> homeShelfQuickRailNeighbour(remembered, actions)
    }

/** The offered seat nearest to [seat] in the dock's own order; null when nothing is offered. */
internal fun homeShelfQuickRailNeighbour(seat: MimirQuickRailAction, actions: List<MimirQuickRailAction>): MimirQuickRailAction? {
    val at = HomeShelfQuickActions.indexOf(seat)
    return actions.minByOrNull { abs(HomeShelfQuickActions.indexOf(it) - at) }
}

/** Playable, already-enriched projection. Missing or hidden durable memberships never enter this list. */
data class HomeShelfRow(
    val itemId: HomeShelfItemId,
    val rank: Long,
    val addedAtMillis: Long,
    val title: String,
    val artworkUri: String? = null,
    val supportingLabel: String? = null,
    /** Typed emulated identity; never inferred from the display/supporting label by visual consumers. */
    val platformId: PlatformId? = null,
    /** Clear-logo wordmark (wheel) — feeds the companion two-zone Home lane (FUX-E), logo-first per FA-M2. */
    val flatArtUri: String? = null,
    /** Local provider support image showing the cartridge/disc/card itself. */
    val physicalMediaUri: String? = null,
    /**
     * The RESOLVED Shelf-state face for this row — his hand pick, else the cover ([mimirShelfFaceUri]).
     *
     * Resolved app-side because that is where the two pick stores live. Null on any row built
     * without it, and every painter falls back to [artworkUri], so a caller that has not been taught
     * about the face renders exactly what it rendered before.
     */
    val faceUri: String? = null,
    /**
     * Whether [faceUri] is physical media rather than a cover — resolved by the same [mimirShelfFace] call
     * that chose the uri, never re-derived. A consumer that guesses the kind from the uri paints a box at
     * the disc's square aspect; see [MimirShelfFace].
     *
     * False on any row built without it, matching [faceUri]'s own fallback: a caller that has not been
     * taught about the face renders a cover exactly as it did before.
     */
    val faceIsPhysicalMedia: Boolean = false,
    /** Local sibling preview clip — feeds the Hearth Home backdrop (FUX-F). Null for Android games. */
    val previewVideoUri: String? = null,
    /**
     * The AUTOMATIC managed Hero Art (`<base>-hero.<ext>`), the still counterpart to [previewVideoUri].
     *
     * Added 2026-09-17 because Home had no field for it and therefore no way to draw it. `GameRow` has
     * carried this column all along — its KDoc calls it "the only Library gamelist backdrop media", which
     * is exactly why the Library showed a hero behind the focused game and Home showed nothing.
     *
     * Distinct from [chosenHeroUri]: that one is a per-game pick from Customize Media and outranks this.
     */
    val heroArtUri: String? = null,
    /** FUX-04 SGDB test: app-internal square tile icon; when present the tile renders Switch-style. */
    val tileIconUri: String? = null,
    /**
     * UXP-01 : the per-game CHOSEN hero as a LOCAL file uri, or null for automatic.
     * The app layer resolves it from `CustomHeroStore` and hands it down, so feature-library never
     * reaches into an app-owned store.
     */
    val chosenHeroUri: String? = null,
    /** Pass 2: independently chosen LOCAL preview clip; null keeps the automatic sibling clip. */
    val chosenPreviewVideoUri: String? = null,
    /** Offline/read-model genre. Null is honest "not listed", never a loading sentinel. */
    val genre: String? = null,
    /** Trusted cached synopsis. Home never fetches this merely because focus moved. */
    val description: String? = null,
    /** Provider stamp for [description], or for a verified provider record with no synopsis. */
    val descriptionSource: String? = null,
    /**
     * Monotonic elapsed time from successful external dispatch to matching Mimir return, after the
     * quick-return threshold. This is not foreground-runtime or gameplay truth.
     */
    val externalSessionElapsedMillis: Long? = null,
    /**
     * What the shelf paints for this game, picture and shape together, as the resolver chose it ([withShelfFaces]).
     *
     * The shelf reads only this; it never looks at [tileIconUri], [faceUri] or [artworkUri], and nothing derives the shape from
     * which of them is set (that re-derivation painted a square pick as a box before). [tileIconUri], [faceUri] and
     * [faceIsPhysicalMedia] remain as the resolver's copies for the readers that have not moved to [face] yet (the launch
     * card's inheritance, the Tome plumbing, the picker's default art).
     *
     * The default is the row's own art as a ledge cover, which is the resolver's L2 answer and is what a row built without
     * the resolver (a preview, a menu row, a test) has always painted; a caller that knows better (an Android launcher icon
     * is a square) passes the face. Only the default reads [artworkUri], and it reads it once, when the row is built: a
     * `copy(artworkUri = ...)` that wants another face passes it with it.
     */
    val face: ShelfFace = ledgeCoverFaceOf(artworkUri),
) {
    init {
        require(title.isNotBlank()) { "Home Shelf title must not be blank" }
    }

    val focusKey: FocusKey = homeShelfFocusKey(itemId)
}

/** A cover on the ledge from [uri], or [ShelfFace.None] when there is none. */
fun ledgeCoverFaceOf(uri: String?): ShelfFace =
    uri?.takeIf(String::isNotBlank)?.let { ShelfFace(it, ShelfFaceShape.LEDGE_COVER) } ?: ShelfFace.None

/** A square icon tile from [uri] (an Android launcher icon is one), or [ShelfFace.None] when there is none. */
fun squareFaceOf(uri: String?): ShelfFace =
    uri?.takeIf(String::isNotBlank)?.let { ShelfFace(it, ShelfFaceShape.SQUARE) } ?: ShelfFace.None

internal const val HomeLaunchExternalSessionFactLabel = "SESSION TIME"

/**
 * Release copy keeps unavailable, safely unmeasured, and measured external elapsed states distinct. A measured total is the plain
 * "1 h 24 min" the Bottom's "before this session" line writes ([MimirTimeCopy.total], one wording for one number), where this row
 * used to shout it ("1 H 24 MIN") beside a "Played today" neighbour that does not.
 *
 * [neverPlayed]  is a game that has NEVER been launched, known from the launch history (no last-played timestamp, the same fact the LAST PLAYED row calls "Never"), not from the elapsed store: its line is
 * "NOT PLAYED YET", whether the store has no entry for it or holds a zero. A measured total outranks it. A game that WAS launched and returned in no measured time keeps "NO MEASURED ELAPSED" (the store's zero), and one
 * launched before the store tracked it keeps "NOT TRACKED" (no entry).
 */
internal fun homeShelfExternalSessionElapsedLabel(elapsedMillis: Long?, neverPlayed: Boolean = false): String = when {
    neverPlayed && (elapsedMillis ?: 0L) <= 0L -> "NOT PLAYED YET"
    elapsedMillis == null -> "NOT TRACKED"
    elapsedMillis <= 0L -> "NO MEASURED ELAPSED"
    else -> MimirTimeCopy.total(elapsedMillis)
}

/**
 * Typed-enough presentation policy for the cached synopsis seam.
 *
 * [isPcGames] exists because the two ScreenScraper-driven "no description" fallbacks below are
 * ScreenScraper concepts: "No English synopsis" names a language policy ScreenScraper enforces, and the
 * else-branch names ScreenScraper by name as the thing to go connect. Neither is true for `pc_games` —
 * ScreenScraper has no system id for that platform and structurally never verifies it (R-24), so a
 * `pc_games` row can only ever carry a `steam:store` description or none at all.
 *
 * [steamGridDbKeyPresent] exists because Enrich for `pc_games` is SteamGridDB's alone (R-24): without a
 * verified key, pressing Enrich there returns `OneResult.GateClosed`, so the "Use Enrich" copy must not be
 * shown — it would point at a button that cannot work. Provider-specific copy: a keyless `pc_games` row
 * gets its own sentence naming where to add the key, instead of reusing the provider-neutral "Use Enrich"
 * copy that a keyed `pc_games` row still gets (Enrich genuinely works there).
 *
 * The stored synopsis stays as the provider sent it; HTML character references in it ("&quot;") are decoded here, for display ([DescriptionText.decodeEntities]), before the
 * blank check, so a synopsis that was only references reads as no description rather than as an empty About.
 */
internal fun homeShelfDescriptionText(
    description: String?,
    descriptionSource: String?,
    screenScraperConnected: Boolean,
    isPcGames: Boolean = false,
    steamGridDbKeyPresent: Boolean = false,
): String {
    val text = description?.let(DescriptionText::decodeEntities)
    return when {
        !text.isNullOrBlank() -> text.trim()
        isPcGames && steamGridDbKeyPresent -> "No description yet."
        isPcGames -> "No description yet. Add a SteamGridDB key in Settings › Appearance › Enrichment & artwork."
        !descriptionSource.isNullOrBlank() ->
            "No English synopsis was available for this game."
        screenScraperConnected -> "No description yet."
        else -> "No description yet. Connect ScreenScraper in Settings › Appearance › Enrichment & artwork."
    }
}

/** Stable repository order, with duplicate identities failing closed before focus registration. */
internal fun canonicalHomeShelfRows(rows: List<HomeShelfRow>): List<HomeShelfRow> =
    rows.sortedWith(
        compareBy<HomeShelfRow> { it.rank }
            .thenBy { it.addedAtMillis }
            .thenBy { it.itemId.kind.name }
            .thenBy { it.itemId.rawIdentity() },
    ).distinctBy { it.itemId }

fun homeShelfFocusKey(itemId: HomeShelfItemId): FocusKey = when (itemId) {
    is HomeShelfItemId.Emulated -> FocusKey(
        "home/shelf/emulated/${encodeFocusPathSegment(itemId.gameId.value)}",
    )
    is HomeShelfItemId.AndroidGame -> FocusKey(
        "home/shelf/android-game/${encodeFocusPathSegment(itemId.packageName.value)}",
    )
    // Distinct prefix, not a shared "android/" one: a package that is both installed as a game and
    // shelved as an app must not collide onto one focus key (or one return anchor).
    is HomeShelfItemId.AndroidApp -> FocusKey(
        "home/shelf/android-app/${encodeFocusPathSegment(itemId.packageName.value)}",
    )
}

fun homeShelfQuickRailKey(action: MimirQuickRailAction): FocusKey =
    FocusKey("home/rail/${action.name.lowercase()}")

internal fun homeShelfQuickRailAction(key: FocusKey?): MimirQuickRailAction? =
    HomeShelfQuickActions.firstOrNull { action -> homeShelfQuickRailKey(action) == key }

// Public so the app-module companion push (FUX-E) can resolve the focused Home row without duplicating the
// focusKey match.
fun homeShelfRowForKey(rows: List<HomeShelfRow>, key: FocusKey?): HomeShelfRow? =
    key?.let { focused -> rows.firstOrNull { it.focusKey == focused } }

/**
 * Input reducer for Home. It updates logical focus synchronously; every visual animation merely retargets
 * after this function returns and can never delay or queue a controller event.
 */
fun dispatchToHomeShelf(
    authority: FocusAuthority,
    rows: List<HomeShelfRow>,
    entries: List<HomeShelfEntry> = canonicalHomeShelfRows(rows).map { HomeShelfEntry.Game(it.itemId) },
    action: ControllerAction,
    onLaunch: (HomeShelfRow) -> Unit,
    onOpenTome: (TomeId) -> Unit = {},
    onOpenAchievements: (HomeShelfRow) -> Unit = {},
    onOpenLibrary: () -> Unit,
    onQuickAction: (MimirQuickRailAction) -> Unit,
    onExit: () -> Unit,
    onTileMenu: (HomeShelfRow) -> Unit = {},
    onTomes: () -> Unit = {},
    logicalFocusKey: FocusKey? = null,
    onLogicalFocusChanged: (FocusKey) -> Unit = {},
    /** See [homeShelfQuickActions]: the rail the D-pad travels must be the rail that is drawn. */
    filesSeatOffered: Boolean = true,
): Boolean {
    val orderedRows = canonicalHomeShelfRows(rows)
    val gameRowsById = orderedRows.associateBy(HomeShelfRow::itemId)
    val actions = homeShelfQuickActions(filesSeatOffered)
    return when (authority.state.value.scope) {
        HomeShelfScope -> dispatchHomeContentAction(
            authority = authority,
            rows = orderedRows,
            entries = entries,
            gameRowsById = gameRowsById,
            action = action,
            onLaunch = onLaunch,
            onOpenTome = onOpenTome,
            onOpenAchievements = onOpenAchievements,
            onOpenLibrary = onOpenLibrary,
            onExit = onExit,
            onTileMenu = onTileMenu,
            onTomes = onTomes,
            logicalFocusKey = logicalFocusKey,
            onLogicalFocusChanged = onLogicalFocusChanged,
            actions = actions,
        )
        HomeShelfQuickRailScope -> dispatchHomeQuickRailAction(
            authority = authority,
            action = action,
            onQuickAction = onQuickAction,
            onTomes = onTomes,
            actions = actions,
        )
        else -> false
    }
}

private fun dispatchHomeContentAction(
    authority: FocusAuthority,
    rows: List<HomeShelfRow>,
    entries: List<HomeShelfEntry>,
    gameRowsById: Map<HomeShelfItemId, HomeShelfRow>,
    action: ControllerAction,
    onLaunch: (HomeShelfRow) -> Unit,
    onOpenTome: (TomeId) -> Unit,
    onOpenAchievements: (HomeShelfRow) -> Unit,
    onOpenLibrary: () -> Unit,
    onExit: () -> Unit,
    onTileMenu: (HomeShelfRow) -> Unit = {},
    onTomes: () -> Unit = {},
    logicalFocusKey: FocusKey? = null,
    onLogicalFocusChanged: (FocusKey) -> Unit = {},
    actions: List<MimirQuickRailAction> = HomeShelfQuickActions,
): Boolean = when (action) {
    // Y belongs only to a typed Game entry. A Tome consumes Y quietly so input cannot fall through to
    // another surface, but it claims no edit action and emits no callback/sound-producing command.
    ControllerAction.TERTIARY -> {
        val focused = authority.state.value.focused?.takeIf { key -> entries.any { it.focusKey == key } }
            ?: logicalFocusKey
        when (val entry = homeShelfEntryForKey(entries, focused)) {
            is HomeShelfEntry.Game -> gameRowsById[entry.itemId]?.let(onTileMenu)
            is HomeShelfEntry.Tome, null -> Unit
        }
        true
    }
    // X opens the Tome Library without changing the exact focused root identity.
    ControllerAction.SECONDARY -> {
        onTomes()
        true
    }
    ControllerAction.NAV_LEFT -> {
        moveHomeShelfEntryFocus(authority, entries, logicalFocusKey, delta = -1, onLogicalFocusChanged)
        true
    }
    ControllerAction.NAV_RIGHT -> {
        moveHomeShelfEntryFocus(authority, entries, logicalFocusKey, delta = 1, onLogicalFocusChanged)
        true
    }
    ControllerAction.NAV_DOWN -> {
        // NF01/HF01 : entering the dock RESTORES its previously-focused item; on the
        // first entry (nothing valid remembered) it defaults to HOME, not the positionally-first tile —
        // which read as "the dock starts from Quick Launch". pushScope's own empty-memory fallback is the
        // first-registered element, so override to HOME only when there is no remembered dock focus.
        val remembered = authority.rememberedFocusIn(HomeShelfQuickRailScope)?.let(::homeShelfQuickRailAction)
        authority.pushScope(HomeShelfQuickRailScope)
        // a remembered seat the host has withdrawn lands on its neighbour (homeShelfQuickRailLanding).
        homeShelfQuickRailLanding(remembered, actions)?.let { authority.requestFocus(homeShelfQuickRailKey(it)) }
        true
    }
    ControllerAction.NAV_UP -> true
    ControllerAction.CONFIRM -> {
        val focused = authority.state.value.focused?.takeIf { key ->
            key == HomeShelfOpenLibraryKey || entries.any { it.focusKey == key }
        } ?: logicalFocusKey
        when (focused) {
            HomeShelfOpenLibraryKey -> onOpenLibrary()
            else -> when (val entry = homeShelfEntryForKey(entries, focused)) {
                is HomeShelfEntry.Game -> gameRowsById[entry.itemId]?.let(onLaunch)
                is HomeShelfEntry.Tome -> onOpenTome(entry.tomeId)
                null -> Unit
            }
        }
        true
    }
    // RA01 policy: SELECT on an emulated Home game opens its read-only achievement card. The app
    // resolves row identity and intentionally consumes Android rows without changing their hide semantics.
    ControllerAction.SYSTEM_SELECT -> {
        val focused = authority.state.value.focused?.takeIf { key -> entries.any { it.focusKey == key } }
            ?: logicalFocusKey
        homeShelfRowForKey(rows, focused)?.let(onOpenAchievements)
        true
    }
    ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
        onExit()
        true
    }
    else -> false
}

private fun dispatchHomeQuickRailAction(
    authority: FocusAuthority,
    action: ControllerAction,
    onQuickAction: (MimirQuickRailAction) -> Unit,
    onTomes: () -> Unit,
    actions: List<MimirQuickRailAction>,
): Boolean = when (action) {
    ControllerAction.NAV_LEFT -> {
        moveHomeQuickRail(authority, delta = -1, actions = actions)
        true
    }
    ControllerAction.NAV_RIGHT -> {
        moveHomeQuickRail(authority, delta = 1, actions = actions)
        true
    }
    ControllerAction.NAV_UP, ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
        authority.popScope()
        true
    }
    ControllerAction.NAV_DOWN -> true
    ControllerAction.CONFIRM -> {
        // Resolved against the OFFERED seats, as the Library rail does: a seat withdrawn under the focus (R2) is not pressable.
        homeShelfQuickRailAction(authority.state.value.focused)?.takeIf { it in actions }?.let { selected ->
            activateHomeShelfQuickAction(authority, selected, onQuickAction)
        }
        true
    }
    // Sibling input consistency: X opens the Tome
    // Library from home/shelf's own dispatchHomeContentAction SECONDARY branch above, a global,
    // game-independent action — it must not go dead one D-pad press away in the dock. TERTIARY and
    // SYSTEM_SELECT stay unhandled here on purpose: both act on a focused game entry, and the rail has
    // none, so falling through to `else -> false` for them is correct, not an oversight.
    ControllerAction.SECONDARY -> {
        onTomes()
        true
    }
    else -> false
}

internal fun activateHomeShelfQuickAction(
    authority: FocusAuthority,
    action: MimirQuickRailAction,
    onQuickAction: (MimirQuickRailAction) -> Unit,
) {
    if (action == MimirQuickRailAction.HOME && authority.state.value.scope == HomeShelfQuickRailScope) {
        authority.popScope()
    } else {
        onQuickAction(action)
    }
}

private fun moveHomeShelfFocus(
    authority: FocusAuthority,
    rows: List<HomeShelfRow>,
    delta: Int,
) {
    val list = rows.map { it.focusKey }
    if (list.isEmpty()) return
    val current = authority.state.value.focused?.let(list::indexOf) ?: -1
    val next = if (current < 0) 0 else (current + delta).coerceIn(0, list.lastIndex)
    if (next != current) authority.requestFocus(list[next])
}

private fun moveHomeShelfEntryFocus(
    authority: FocusAuthority,
    entries: List<HomeShelfEntry>,
    logicalFocusKey: FocusKey?,
    delta: Int,
    onLogicalFocusChanged: (FocusKey) -> Unit,
) {
    val list = entries.map(HomeShelfEntry::focusKey)
    if (list.isEmpty()) return
    val currentKey = authority.state.value.focused?.takeIf { it in list }
        ?: logicalFocusKey?.takeIf { it in list }
    val current = currentKey?.let(list::indexOf) ?: -1
    val next = if (current < 0) 0 else (current + delta).coerceIn(0, list.lastIndex)
    if (next != current) {
        val target = list[next]
        onLogicalFocusChanged(target)
        authority.rememberFocusForScope(HomeShelfScope, target)
        authority.requestFocus(target)
    }
}

private fun moveHomeQuickRail(authority: FocusAuthority, delta: Int, actions: List<MimirQuickRailAction>) {
    // The Library rail's formula (dispatchLibraryQuickRail), so the two docks travel alike: an unknown or withdrawn seat counts as the first,
    // and the move goes to that seat's neighbour in the pressed direction.
    val current = (homeShelfQuickRailAction(authority.state.value.focused)?.let(actions::indexOf) ?: -1).coerceAtLeast(0)
    authority.requestFocus(homeShelfQuickRailKey(actions[(current + delta).floorMod(actions.size)]))
}

private fun HomeShelfItemId.rawIdentity(): String = when (this) {
    is HomeShelfItemId.Emulated -> gameId.value
    is HomeShelfItemId.AndroidGame -> packageName.value
    is HomeShelfItemId.AndroidApp -> packageName.value
}

private fun encodeFocusPathSegment(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xff
        if (
            code in 'a'.code..'z'.code ||
            code in 'A'.code..'Z'.code ||
            code in '0'.code..'9'.code ||
            code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
        ) {
            append(code.toChar())
        } else {
            append('%')
            append(HexDigits[code ushr 4])
            append(HexDigits[code and 0x0f])
        }
    }
}

private const val HexDigits = "0123456789ABCDEF"

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

private fun abs(value: Int): Int = if (value < 0) -value else value
