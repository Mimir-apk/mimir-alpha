package com.gruning.mimir.rewrite

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * One Customize media page combines the media-type filter, type-specific filters and search.
 *
 * Customize media opens the picker straight from the Edit menu. Its top row is: Media (this file's types), the
 * chosen type's own chips, then Search (no Back chip: Main is pad-driven, B returns, the earlier update). This file is the page's pure side: which types a game offers, where the page
 * opens, how L1/R1 step through the types, how the Media panel and chip behave on the pad, and the picker's builder.
 * The drawing lives in CustomMediaTopRow.kt.
 */

/** One (kind, state) pair of the Media chip: Icon art is ICON + SHELF, Launch card is COVER + LAUNCH_CARD, and so on. */
internal data class CustomMediaSelection(val kind: EnrichMediaKind, val mediaState: MediaState)

/** A value of the Media chip: a [CustomMediaSelection] with the words the player reads (the MimirCopy wording). */
internal data class CustomMediaType(
    val kind: EnrichMediaKind,
    val mediaState: MediaState,
    val label: String,
) {
    val selection: CustomMediaSelection get() = CustomMediaSelection(kind, mediaState)

    /** The shelf's Icon art: the merged picker (a square, a box, the article and an upload in one gallery) with its Type chip. */
    val isMergedIconArt: Boolean get() = kind == EnrichMediaKind.ICON && mediaState == MediaState.SHELF
}

internal fun customMediaTypeOf(action: HomeTileMenuAction): CustomMediaType? = when (action) {
    // The ONE shelf type : a square, a box, the physical article or an upload, and the tile's
    // shape follows the pick. It was two types, "Shelves" (COVER + SHELF) and "Icon art" (ICON + SHELF).
    HomeTileMenuAction.CUSTOM_ART_ICON ->
        CustomMediaType(EnrichMediaKind.ICON, MediaState.SHELF, action.label)
    HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD ->
        CustomMediaType(EnrichMediaKind.COVER, MediaState.LAUNCH_CARD, action.label)
    HomeTileMenuAction.CUSTOM_HERO ->
        CustomMediaType(EnrichMediaKind.HERO, MediaState.SHELF, action.label)
    HomeTileMenuAction.CUSTOM_PREVIEW_CLIP ->
        CustomMediaType(EnrichMediaKind.CLIP, MediaState.SHELF, action.label)
    else -> null
}

/** The Media chip's fixed order, which L1 and R1 and the panel follow: the faces first (the shelf's Icon art, then the Launch card), then hero, motion. */
private val CustomMediaTypeOrder = listOf(
    HomeTileMenuAction.CUSTOM_ART_ICON,
    HomeTileMenuAction.CUSTOM_FACE_LAUNCH_CARD,
    HomeTileMenuAction.CUSTOM_HERO,
    HomeTileMenuAction.CUSTOM_PREVIEW_CLIP,
)

/**
 * The Media chip's values for this row, in [CustomMediaTypeOrder]. [homeTileCustomEnrichActions] stays the one gating source
 * (Library hero offers no icon, an Android package offers no Hero Motion, ...), so what the old submenu listed is what the
 * chip lists; only the order is the chip's own.
 */
internal fun homeTileCustomMediaTypes(state: HomeTileMenuState): List<CustomMediaType> =
    homeTileCustomEnrichActions(state)
        .filter { it in CustomMediaTypeOrder }
        .sortedBy { CustomMediaTypeOrder.indexOf(it) }
        .mapNotNull(::customMediaTypeOf)

/**
 * The chips each of [types] opens with for this game, at their opening values: what the top row reads to seat Search for the WIDEST type, so its width
 * does not change when the type does ( [customMediaPageSeating]). These are the builders' own sources: the merged Icon art picker takes the Type
 * values this game can answer (so a PC game or an Android row has no Region chip), every other type its default chips, and a PC game opens on SteamGridDB.
 */
internal fun customMediaTypeFilters(
    state: HomeTileMenuState,
    types: List<CustomMediaType> = homeTileCustomMediaTypes(state),
): Map<CustomMediaSelection, List<HomeCustomEnrichFilter>> {
    val emulated = state.row.itemId is HomeShelfItemId.Emulated
    val preferSteamGridDb = state.row.platformId == PlatformId.PC_GAMES
    return types.associate { type ->
        type.selection to if (type.isMergedIconArt) {
            homeCustomEnrichIconArtFilters(iconArtTypeValues(state.row.platformId, emulated))
        } else {
            homeCustomEnrichDefaultFilters(type.kind, preferSteamGridDb)
        }
    }
}

/**
 * Where the page opens when nothing was visited yet: Icon art from every door, the Library hero's included (, then Icon art on both doors, which retires the 2026-08-14 Hero-art landing of [libraryHeroPickerMenuState]'s door). The entry point stays a
 * parameter so a door can be given its own landing again without touching the callers.
 */
@Suppress("UNUSED_PARAMETER")
internal fun customMediaEntryDefault(entryPoint: HomeTileMenuEntryPoint): CustomMediaSelection =
    CustomMediaSelection(EnrichMediaKind.ICON, MediaState.SHELF)

/** The type the page opens on: the one last edited, else the entry point's default, else the first the row offers. */
internal fun customMediaOpening(
    state: HomeTileMenuState,
    types: List<CustomMediaType> = homeTileCustomMediaTypes(state),
): CustomMediaType? {
    val remembered = state.customEnrichFocus?.let { wanted -> types.firstOrNull { it.selection == wanted } }
    return remembered
        ?: types.firstOrNull { it.selection == customMediaEntryDefault(state.entryPoint) }
        ?: types.firstOrNull()
}

/** The page, opened (on its [customMediaOpening] type) from [state]; null when the host cannot build a picker for it. */
internal fun openCustomMedia(
    state: HomeTileMenuState,
    onOpen: (EnrichMediaKind, MediaState) -> HomeCustomEnrichState?,
): HomeTileMenuState? {
    val type = customMediaOpening(state) ?: return null
    val picker = onOpen(type.kind, type.mediaState) ?: return null
    return state.copy(
        phase = HomeTileMenuPhase.CustomEnrichPicker(picker),
        customEnrichFocus = type.selection,
    )
}

/** Where a legacy pick page (Loading, Pick, Unavailable) goes on B: back to the page, else the Edit menu. */
internal fun returnToCustomMedia(
    state: HomeTileMenuState,
    onOpen: (EnrichMediaKind, MediaState) -> HomeCustomEnrichState?,
): HomeTileMenuState = openCustomMedia(state, onOpen) ?: state.copy(phase = HomeTileMenuPhase.Menu)

/**
 * Where B goes once the page has nothing left to close (the keyboard, the panel and a running search go first): the Edit
 * menu it was opened from, or null (close everything) from the doors that have no menu of their own. The Library hero
 * door closed everything before this page existed and still does.
 */
internal fun customMediaBack(state: HomeTileMenuState): HomeTileMenuState? =
    if (state.entryPoint == HomeTileMenuEntryPoint.EDIT) {
        state.copy(phase = HomeTileMenuPhase.Menu)
    } else {
        null
    }

/** One step along the types from [from], stopping at the ends (no wrap): null when there is nowhere to go. */
internal fun customMediaStep(
    types: List<CustomMediaType>,
    from: CustomMediaSelection,
    delta: Int,
): CustomMediaType? {
    val index = types.indexOfFirst { it.selection == from }
    if (index < 0) return null
    return types.getOrNull(index + delta)
}

/** A type switch waits for the page to be at rest: never over a running search, a staged apply, the keyboard or a preview. */
internal fun customMediaSwitchAllowed(picker: HomeCustomEnrichState): Boolean =
    picker.searchStatus !is HomeCustomEnrichSearchStatus.Searching &&
        picker.operation == HomeCustomEnrichOperation.IDLE &&
        !picker.keyboardOpen &&
        !picker.previewExpanded

/** The menu after a switch: the new type's picker (the typed name carried over, never fetched) and the remembered type. */
internal fun customMediaSwitched(
    live: HomeTileMenuState,
    old: HomeCustomEnrichState,
    next: HomeCustomEnrichState,
    selection: CustomMediaSelection,
): HomeTileMenuState = live.copy(
    phase = HomeTileMenuPhase.CustomEnrichPicker(next.copy(queryDraft = old.queryDraft)),
    customEnrichFocus = selection,
)

internal fun HomeCustomEnrichState.mediaSelection(): CustomMediaSelection = CustomMediaSelection(kind, mediaState)

internal fun HomeCustomEnrichState.currentMediaType(): CustomMediaType? =
    mediaTypes.firstOrNull { it.kind == kind && it.mediaState == mediaState }

// ── The Media chip's and the Filters chip's keys ───────────────────────────────────────────────────────────────────

internal fun homeCustomEnrichMediaKey(state: HomeCustomEnrichState): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/media")

internal fun homeCustomEnrichMediaOptionKey(state: HomeCustomEnrichState, index: Int): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/media/option/$index")

/** The Filters chip: the type's own chips folded into one mark when the top row has no room for them (see [CustomMediaFold]). */
internal fun homeCustomEnrichFiltersKey(state: HomeCustomEnrichState): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/filters")

internal fun homeCustomEnrichFiltersOptionKey(state: HomeCustomEnrichState, index: Int): FocusKey =
    FocusKey("${homeCustomEnrichPrefix(state)}/filters/option/$index")

/**
 * Whether the drawn top row has folded the type's chips into the one Filters chip. The row decides it from what it can
 * measure (its width and the font) and writes it here; the pure reducer and the focus graph read it, so the pad walks
 * exactly the stops that are drawn. A plain map (no Compose state) because the reducer is pure and runs off the
 * composition; the overlay keeps its own copy in state so the graph recomposes when this changes.
 */
internal object CustomMediaFold {
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isFolded(sessionId: String): Boolean = sessions[sessionId] == true

    fun set(sessionId: String, folded: Boolean) {
        if (folded) sessions[sessionId] = true else sessions.remove(sessionId)
    }
}

/**
 * The Media chip's stop in the top row's lane (the panel's options in its place while the panel is open), then, when the
 * type's chips are folded, the Filters chip's stop (its panel's rows in its place while that is open).
 */
internal fun customMediaTopKeys(
    state: HomeCustomEnrichState,
    folded: Boolean = CustomMediaFold.isFolded(state.sessionId),
): List<FocusKey> {
    if (state.mediaTypes.isEmpty()) return emptyList()
    val media = if (state.mediaPanelOpen) {
        state.mediaTypes.indices.map { homeCustomEnrichMediaOptionKey(state, it) }
    } else {
        listOf(homeCustomEnrichMediaKey(state))
    }
    val filters = when {
        !folded -> emptyList()
        state.filtersPanelOpen -> state.visibleFilters().indices.map { homeCustomEnrichFiltersOptionKey(state, it) }
        else -> listOf(homeCustomEnrichFiltersKey(state))
    }
    return media + filters
}

/**
 * The air the Customize media page adds to each side of the panel's own padding, so a focused ring on the column clears the panel's clip: the
 * ring's reach ([MimirFocusTileRingReach], 14 dp) less the padding, that is 6 dp at compact's 8 and 2 dp at md's 12. The panel is that much
 * wider on each side (HomeCustomEnrichPickerOverlay), so the content keeps the width it had. Anything on the column (Search, a chip, a Default
 * tile, Upload) is then a whole reach from the clip, at Main and at compact.
 */
internal fun customMediaPanelSideAir(compactTypography: Boolean): Dp =
    homeCustomEnrichEdgeRingInset(homeCustomEnrichPanelPadding(compactTypography), flare = false)

/**
 * On Thor, the focused Default tile's ring and halo were cut at the top and the left. The page sits under the glass-backdrop layer
 * (`mimirGlassBackdropProgress`), which on every device that blurs clips to its OWN bounds. Those were the panel's content box, which is the panel less
 * its padding: the ring, which reaches [MimirFocusTileRingReach] past a tile that stands only [customMediaPanelSideAir] from that edge, was cut by
 * the layer a padding inside the panel's own clip. The layer is now the panel's whole interior: it bleeds the panel's padding on every side and the
 * page's content is padded by the same, so nothing on the page moves. From the layer's edge a tile on the column is then the padding plus the side
 * air, which is the ring's whole reach.
 */
internal fun customMediaBackdropBleed(compactTypography: Boolean): Dp = homeCustomEnrichPanelPadding(compactTypography)

/** The strip's viewport reaches this far above and below its band (its air plus this is the ring's reach), so its cross-axis clip cuts no ring either. */
internal val EnrichGalleryRingVerticalBleedDp: Int get() = EnrichRingClearanceDp - EnrichGalleryVerticalAirDp

/** The page's glass-backdrop layer (its bounds are what the layer clips to) and the gallery strip's scroll host, tagged for the test that holds a ring inside both. */
internal const val HomeCustomEnrichBackdropLayerTag = "custom-media-backdrop-layer"
internal const val CustomMediaStripHostTag = "custom-media-strip-host"

/** Grows this node by [bleed] on every side and seats it there, so it reports the size it was given: what its parent lays out around it does not move. */
internal fun Modifier.customMediaBackdropBleedBy(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + extra * 2).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra * 2 else constraints.maxWidth,
            minHeight = (constraints.minHeight + extra * 2).coerceAtLeast(0),
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extra * 2 else constraints.maxHeight,
        ),
    )
    layout(
        (placeable.width - extra * 2).coerceIn(constraints.minWidth, constraints.maxWidth),
        (placeable.height - extra * 2).coerceIn(constraints.minHeight, constraints.maxHeight),
    ) {
        placeable.place(-extra, -extra)
    }
}

/** What the top row adds at its end: nothing. The panel's side air holds the ring's whole reach on that side as on the column's. */
@Suppress("UNUSED_PARAMETER")
internal fun customMediaTopRowEdgeInset(compactTypography: Boolean): Dp = 0.dp

/**
 * The page's one left edge (sweep 10-09: the title stood at one x, the strip's first tile at another, Upload and the preview at two more):
 * the title, the top row, the status line, the strip's first tile, the preview's brackets and Upload all start here: the content's own edge,
 * which the panel's padding and side air ([customMediaPanelSideAir]) already stand a ring's whole reach from the clip. Nothing has a flare any
 * more (Upload is an unselected etched chip whose ring does not flare), so no allowance is kept for one.
 */
@Suppress("UNUSED_PARAMETER")
internal fun customMediaColumnStart(compactTypography: Boolean): Dp = 0.dp

/** What the footer row adds to the column's start: nothing. Upload has no flare, so it stands on the column with everything else. */
@Suppress("UNUSED_PARAMETER")
internal fun customMediaFooterInset(compactTypography: Boolean): Dp = 0.dp

/**
 * How far past the column's edge the strip's overflow cue (an 8 dp chevron) is seated: the room the panel's clip leaves it, less 1 dp, and never
 * more than [EnrichGalleryCueSeatDp]. The room is the panel's padding and its side air (14 dp at both paddings).
 */
internal fun customMediaCueSeatDp(compactTypography: Boolean): Int =
    minOf(
        EnrichGalleryCueSeatDp,
        (homeCustomEnrichPanelPadding(compactTypography) + customMediaPanelSideAir(compactTypography)).value.toInt() - 1,
    )

/** The page's title row, status line and preview stage, tagged for the test that asks they all stand on the column. */
internal const val CustomMediaTitleTag = "custom-media-title"
internal const val CustomMediaStatusTag = "custom-media-status"
internal const val CustomMediaPreviewStageTag = "custom-media-preview-stage"

/** The words NO ART in the Default tile of a page with no stage (compact squares, a short Main). */
internal const val CustomMediaDefaultNoArtTag = "custom-media-default-no-art"

/** One result, two results: the status line's count says "1 result", never "1 results". */
internal fun homeCustomEnrichResultsWord(count: Int): String = if (count == 1) "result" else "results"

/** The idle status line when no downloaded choice exists: it names the one key that fetches some, in the page's own legend words ("Y SEARCH"). */
internal const val CustomMediaNoChoicesSpoken = "No downloaded choices yet. Press Y to search."
internal const val CustomMediaNoChoicesVisual = "NO DOWNLOADED CHOICES · Y SEARCH"

/**
 * What the status line says while nothing is being searched. A gallery containing only Default is not ready. "Ready" is said only when a downloaded choice (a saved one, or a found one) stands in the gallery beside
 * Default; with none, the line says so and names the key that fetches some. True for every type, whatever it can search.
 */
internal fun homeCustomEnrichIdleStatusIntent(state: HomeCustomEnrichState): MimirTextIntent = when {
    state.catalogFirstRead -> MimirTextIntent.StaticChrome("")
    !state.catalogReady -> MimirTextIntent.StaticChrome("Preparing saved choices…")
    state.assets.any { it is HomeCustomEnrichAsset.Saved || it is HomeCustomEnrichAsset.Result } -> homeCustomEnrichDownloadedChoicesIntent()
    else -> MimirTextIntent.StaticChrome(spokenText = CustomMediaNoChoicesSpoken, visualOverride = CustomMediaNoChoicesVisual)
}

/** Where focus returns after a chip's value panel closes: the chip itself, or the Filters chip when the chips are folded. */
internal fun customMediaFilterReturnKey(state: HomeCustomEnrichState, chipKey: FocusKey): FocusKey =
    if (state.mediaTypes.isNotEmpty() && CustomMediaFold.isFolded(state.sessionId)) homeCustomEnrichFiltersKey(state) else chipKey

/** Focus lands on the current type when the Media panel opens, and on the first row when the Filters panel does. */
internal fun customMediaPanelLanding(state: HomeCustomEnrichState): FocusKey? {
    if (state.mediaPanelOpen) {
        val index = state.mediaTypes.indexOfFirst { it.kind == state.kind && it.mediaState == state.mediaState }
        return if (index >= 0) homeCustomEnrichMediaOptionKey(state, index) else null
    }
    if (state.filtersPanelOpen) {
        return state.visibleFilters().indices.firstOrNull()?.let { homeCustomEnrichFiltersOptionKey(state, it) }
    }
    return null
}

// ── The page's reducer hooks (called from reduceHomeCustomEnrich) ──────────────────────────────────────────────────

/**
 * The Media panel or the Filters panel, while one is open: B closes it (and gives focus back to its chip), Up and Down
 * walk its rows, A picks one. In the Media panel, picking the type already shown closes it and lands on the page's current
 * choice, and picking another closes it and asks for the switch. In the Filters panel a row opens that filter's own value
 * panel. Null while both are shut.
 */
internal fun customMediaPanelReduce(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    action: ControllerAction,
): HomeCustomEnrichTransition? {
    val media = state.mediaPanelOpen
    val filtersPanel = state.filtersPanelOpen && !media
    if (!media && !filtersPanel) return null
    val ownerKey = if (media) homeCustomEnrichMediaKey(state) else homeCustomEnrichFiltersKey(state)
    val optionKeys = if (media) {
        state.mediaTypes.indices.map { homeCustomEnrichMediaOptionKey(state, it) }
    } else {
        state.visibleFilters().indices.map { homeCustomEnrichFiltersOptionKey(state, it) }
    }
    val focusedIndex = optionKeys.indexOf(authority.state.value.focused)
    val closed = state.copy(mediaPanelOpen = false, filtersPanelOpen = false)
    return when (action) {
        ControllerAction.CANCEL,
        ControllerAction.SYSTEM_BACK,
        -> {
            authority.requestFocus(ownerKey)
            HomeCustomEnrichTransition(
                closed.copy(laneMemory = state.laneMemory + (HomeCustomEnrichLane.FILTERS to ownerKey.value)),
            )
        }
        ControllerAction.NAV_UP,
        ControllerAction.NAV_DOWN,
        -> {
            val delta = if (action == ControllerAction.NAV_UP) -1 else 1
            val target = optionKeys.getOrNull(focusedIndex + delta)
            if (target != null && focusedIndex >= 0) {
                authority.requestFocus(target)
                MimirNavFeedback.consumed()
            }
            HomeCustomEnrichTransition(state)
        }
        ControllerAction.CONFIRM -> if (media) {
            val picked = state.mediaTypes.getOrNull(focusedIndex)
            if (picked == null) {
                HomeCustomEnrichTransition(state)
            } else if (picked.selection == state.mediaSelection()) {
                // A pick, not a cancel: the same type lands where a different one does, on the page's current choice.
                val landing = homeCustomEnrichInitialKey(state)
                authority.requestFocus(landing)
                HomeCustomEnrichTransition(
                    closed.copy(laneMemory = state.laneMemory + (HomeCustomEnrichLane.GALLERY to landing.value)),
                )
            } else {
                HomeCustomEnrichTransition(closed, HomeCustomEnrichCommand.SwitchMedia(picked.selection))
            }
        } else {
            // A row of the Filters panel opens that filter's own value panel (the one a chip opens when the row is
            // not folded); its own landing, B and pick rules take over from there.
            val filter = state.visibleFilters().getOrNull(focusedIndex)
            if (filter == null) {
                HomeCustomEnrichTransition(state)
            } else {
                HomeCustomEnrichTransition(
                    closed.copy(
                        openFilterKey = filter.key,
                        laneMemory = state.laneMemory + (HomeCustomEnrichLane.FILTERS to ownerKey.value),
                    ),
                )
            }
        }
        else -> HomeCustomEnrichTransition(state)
    }
}

/**
 * The top row on the pad, for the page (null for a picker with no Media chip, which keeps the old row):
 *  - L1/R1 step to the previous or next type and stop at the ends; they do nothing while a search runs;
 *  - A on the Media chip opens its panel, and A on the Filters chip (the type's chips, folded when the row has no room
 *    for them) opens its own (nothing while a search runs);
 *  - Left and Right walk the row in the order it is drawn: Search, Media, then the type's chips (or the Filters chip).
 */
internal fun customMediaTopRowReduce(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    action: ControllerAction,
    focused: FocusKey,
    graph: Map<HomeCustomEnrichLane, List<FocusKey>>,
    remembered: Map<HomeCustomEnrichLane, String>,
): HomeCustomEnrichTransition? {
    if (state.mediaTypes.isEmpty()) return null
    val searching = state.searchStatus is HomeCustomEnrichSearchStatus.Searching
    when (action) {
        ControllerAction.PAGE_LEFT,
        ControllerAction.PAGE_RIGHT,
        -> {
            if (searching) return HomeCustomEnrichTransition(state)
            val delta = if (action == ControllerAction.PAGE_LEFT) -1 else 1
            val target = customMediaStep(state.mediaTypes, state.mediaSelection(), delta)
                ?: return HomeCustomEnrichTransition(state)
            return HomeCustomEnrichTransition(state, HomeCustomEnrichCommand.SwitchMedia(target.selection))
        }
        ControllerAction.NAV_LEFT,
        ControllerAction.NAV_RIGHT,
        -> {
            val row = graph.getValue(HomeCustomEnrichLane.SEARCH) + graph.getValue(HomeCustomEnrichLane.FILTERS)
            val at = row.indexOf(focused)
            if (at < 0) return null
            val delta = if (action == ControllerAction.NAV_LEFT) -1 else 1
            val target = row.getOrNull(at + delta)
                ?: return HomeCustomEnrichTransition(state.copy(laneMemory = remembered))
            authority.requestFocus(target)
            MimirNavFeedback.consumed()
            return HomeCustomEnrichTransition(
                state.copy(
                    laneMemory = remembered +
                        (HomeCustomEnrichLane.SEARCH to target.value) +
                        (HomeCustomEnrichLane.FILTERS to target.value),
                ),
            )
        }
        ControllerAction.CONFIRM -> {
            if (focused == homeCustomEnrichMediaKey(state)) {
                if (searching) return HomeCustomEnrichTransition(state)
                return HomeCustomEnrichTransition(state.copy(mediaPanelOpen = true, laneMemory = remembered))
            }
            if (focused == homeCustomEnrichFiltersKey(state) && CustomMediaFold.isFolded(state.sessionId)) {
                if (searching) return HomeCustomEnrichTransition(state)
                return HomeCustomEnrichTransition(state.copy(filtersPanelOpen = true, laneMemory = remembered))
            }
        }
        else -> Unit
    }
    return null
}

// ── The picker's builder (moved out of the coordinator so a type switch can reuse it) ──────────────────────────────

/**
 * Builds the picker for one (kind, state) of this row: the saved catalog, the legacy media still to be migrated into it,
 * the Default tile's art, and the Media chip's types. Nothing is fetched here: a search starts only on Y or Retry.
 *
 * **The disk reads are not in the key handler (audit 2026-10-14, RC-29: H-09, L-08).** Opening the page used to read the saved catalog (prefs,
 * JSON and two or four stats per entry), the legacy media files and the upload marker before the page's first frame, on the thread that
 * handled the press. A page being OPENED now returns at once with an empty gallery and `catalogFirstRead`, and the reads run on IO; the
 * gallery lands behind the page's arrival lead (its 130 ms of alpha 0), and the page lands its focus on the choice in use when it does. A switch of
 * type INSIDE the open page keeps its reads in the handler: that page is on screen, and a gallery that emptied and refilled would be seen.
 *
 * The STATE enters the key here, once, and every store downstream inherits it: the picker's saved catalog, the renderer
 * promotion, the selection tombstone. SHELF returns the bare key, so this is byte-identical for every pick already on
 * disk.
 */
internal fun buildHomeCustomEnrichPicker(
    context: Context,
    current: HomeTileMenuState,
    kind: EnrichMediaKind,
    mediaState: MediaState,
    migrations: MutableMap<String, Deferred<EnrichLegacyMigrationOutcome>>,
    scope: CoroutineScope,
    update: (String, (HomeCustomEnrichState) -> HomeCustomEnrichState) -> Unit,
): HomeCustomEnrichState {
    val cacheKey = mediaStorageKey(homeTileMenuCacheKey(current.row.itemId), mediaState)
    // The shelf's Icon art is the merged type : three stores, one gallery.
    if (kind == EnrichMediaKind.ICON && mediaState == MediaState.SHELF) {
        return buildIconArtPicker(context, current, cacheKey, migrations, scope, update)
    }
    val emulated = current.row.itemId as? HomeShelfItemId.Emulated
    val heroGameId = homeTileHeroStoreId(current.row.itemId)
    // A page already on screen (a switch of type) reads here; a page being opened reads on IO below.
    val early = if (current.phase is HomeTileMenuPhase.CustomEnrichPicker) {
        readEnrichStores(context, kind, cacheKey, emulated?.gameId, heroGameId)
    } else {
        null
    }
    val pageTypes = homeTileCustomMediaTypes(current)
    val initial = newHomeCustomEnrichState(
        sessionId = "${kind.wireName}-${System.nanoTime()}",
        kind = kind,
        cacheKey = cacheKey,
        gameTitle = current.row.title,
        catalog = early?.display ?: EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
        catalogReady = early?.ready ?: false,
        // For PC games, default the source filter to Steam: pc_games opens Customize Media with Source already on
        // SteamGridDB, the only provider that ever answers for it.
        preferSteamGridDb = current.row.platformId == PlatformId.PC_GAMES,
        // CP-03: `artworkUri` is AUTOMATIC art -- the chosen icon rides `tileIconUri`.
        defaultArt = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = kind,
            title = current.row.title,
            platformIdValue = current.row.platformId?.value
                ?: current.row.supportingLabel,
            automaticArtUri = current.row.artworkUri,
            tileIconUri = current.row.tileIconUri,
            heroArtUri = current.sourceGame?.heroArtUri?.takeIf {
                kind == EnrichMediaKind.HERO &&
                    current.entryPoint == HomeTileMenuEntryPoint.LIBRARY_HERO
            },
            previewVideoUri = current.row.previewVideoUri,
            // A cover is not a clip poster. Until a frame is extracted from the automatic clip, the Default card
            // names the ready clip honestly.
            clipPosterUri = null,
        ),
        mediaState = mediaState,
        mediaTypes = pageTypes,
        mediaTypeFilters = customMediaTypeFilters(current, pageTypes),
    ).copy(catalogFirstRead = early == null)
    if (early != null && early.ready) return initial
    scope.launch {
        val reading = early ?: withContext(Dispatchers.IO) {
            readEnrichStores(context, kind, cacheKey, emulated?.gameId, heroGameId)
        }
        if (early == null) {
            update(initial.sessionId) { live ->
                live.copy(
                    saved = reading.display.orderedForOpen(),
                    activeAssetId = reading.display.activeAssetId,
                    catalogReady = reading.ready,
                    catalogFirstRead = false,
                )
            }
        }
        if (reading.ready) return@launch
        val migrationKey = "${kind.wireName}/$cacheKey"
        val migration = migrations[migrationKey]
            ?: scope.async {
                withContext(NonCancellable) {
                    EnrichSavedStore.migrateLegacy(
                        context = context,
                        kind = kind,
                        cacheKey = cacheKey,
                        legacyActive = reading.legacy,
                        retainedUpload = reading.retainedUpload,
                        activeWasUpload = reading.activeWasUpload,
                    )
                }
            }.also { migrations[migrationKey] = it }
        val outcome = try {
            migration.await()
        } catch (_: Exception) {
            EnrichLegacyMigrationOutcome.Failed(
                EnrichSavedStore.load(context, kind, cacheKey),
            )
        } finally {
            if (migrations[migrationKey] === migration) {
                migrations.remove(migrationKey)
            }
        }
        update(initial.sessionId) { live ->
            when (outcome) {
                is EnrichLegacyMigrationOutcome.Ready -> live.copy(
                    saved = outcome.catalog.orderedForOpen(),
                    activeAssetId = outcome.catalog.activeAssetId,
                    catalogReady = true,
                )
                is EnrichLegacyMigrationOutcome.Failed -> live.copy(
                    catalogReady = false,
                    note = "Saved choice couldn’t be prepared. Go back and try again.",
                )
            }
        }
    }
    return initial
}

/**
 * What one single-lane page reads from disk before it can list its choices: the durable catalog, the legacy media still to be migrated
 * into it, the retained upload and the upload marker. Blocking (prefs, JSON, stats): the main thread never calls it for a page being opened.
 */
private class EnrichStoreReading(
    val durable: EnrichSavedCatalog,
    val display: EnrichSavedCatalog,
    val legacy: File?,
    val retainedUpload: File?,
    val activeWasUpload: Boolean,
) {
    /** False while a legacy pick still has to be adopted into the catalog (the page shows it at once and migrates behind it). */
    val ready: Boolean get() = display == durable
}

private fun readEnrichStores(
    context: Context,
    kind: EnrichMediaKind,
    cacheKey: String,
    emulatedGameId: GameId?,
    heroGameId: GameId,
): EnrichStoreReading {
    val legacy = when (kind) {
        EnrichMediaKind.ICON -> SteamGridDbTileFetcher.tileIconFile(context, cacheKey)
            .takeIf { it.isFile && it.length() > 0L }
        EnrichMediaKind.TOME_PHYSICAL -> TomeCartridgeArtStore.activeFile(context, cacheKey)
            .takeIf { it.isFile && it.length() > 0L }
        EnrichMediaKind.COVER -> CustomCoverStore.activeFile(context, cacheKey)
            .takeIf { it.isFile && it.length() > 0L }
        EnrichMediaKind.HERO -> heroGameId
            .takeIf { CustomHeroStore.hasOverride(context, it) }
            ?.let { CustomHeroStore.assetFile(context, it, video = false) }
        EnrichMediaKind.CLIP -> emulatedGameId
            ?.takeIf { CustomHeroStore.hasPreviewClipOverride(context, it) }
            ?.let { CustomHeroStore.assetFile(context, it, video = true) }
    }
    val retainedUpload = when (kind) {
        EnrichMediaKind.ICON -> CustomImageImportStore.retainedIconFile(context, cacheKey)
            .takeIf { it.isFile && it.length() > 0L }
        EnrichMediaKind.TOME_PHYSICAL -> null
        EnrichMediaKind.COVER -> null
        EnrichMediaKind.HERO -> CustomImageImportStore.retainedHeroFile(context, heroGameId)
            .takeIf { it.isFile && it.length() > 0L }
        EnrichMediaKind.CLIP -> null
    }
    val activeWasUpload = when (kind) {
        EnrichMediaKind.ICON -> SteamGridDbTileFetcher.uploadedPickActive(context, cacheKey)
        EnrichMediaKind.TOME_PHYSICAL -> false
        EnrichMediaKind.COVER -> false
        EnrichMediaKind.HERO -> CustomHeroStore.uploadedHeroActive(context, heroGameId)
        EnrichMediaKind.CLIP -> false
    }
    val durable = EnrichSavedStore.load(context, kind, cacheKey)
    val display = enrichCatalogWithProvisionalLocal(
        catalog = durable,
        kind = kind,
        legacyActive = legacy,
        retainedUpload = retainedUpload,
        activeWasUpload = activeWasUpload,
    )
    return EnrichStoreReading(durable, display, legacy, retainedUpload, activeWasUpload)
}

/**
 * The merged Icon art picker of a game . Its Saved gallery is the three lanes' catalogs read as one
 * ([IconArtStore]); a pick a lane already holds but never catalogued is shown at once and adopted behind the first frame, lane by
 * lane, exactly as the single-lane builder does it. Nothing is fetched here.
 */
private fun buildIconArtPicker(
    context: Context,
    current: HomeTileMenuState,
    cacheKey: String,
    migrations: MutableMap<String, Deferred<EnrichLegacyMigrationOutcome>>,
    scope: CoroutineScope,
    update: (String, (HomeCustomEnrichState) -> HomeCustomEnrichState) -> Unit,
): HomeCustomEnrichState {
    // A page being OPENED reads nothing here: the three lanes' catalogs, their legacy files and the shelf's winner are all read on IO below, behind
    // the page's arrival lead (audit 2026-10-14, RC-29). A switch of type inside the page that is already open keeps its lane reads in the handler,
    // so the gallery on screen does not empty and refill.
    val early = if (current.phase is HomeTileMenuPhase.CustomEnrichPicker) IconArtStore.laneInputs(context, cacheKey) else null
    // The lane the shelf paints from is the shelf's own reading: it stats files and reads markers, so it is never asked here, on the main
    // thread. The first frame lists the choices with none marked in use; the winner is read on the IO dispatcher straight after and handed to
    // the page, which fills the mark in (and, where a lane still holds an unadopted pick, adopts it).
    val emulated = current.row.itemId is HomeShelfItemId.Emulated
    val pageTypes = homeTileCustomMediaTypes(current)
    val initial = newHomeCustomEnrichState(
        sessionId = "${EnrichMediaKind.ICON.wireName}-${System.nanoTime()}",
        kind = EnrichMediaKind.ICON,
        cacheKey = cacheKey,
        gameTitle = current.row.title,
        catalog = EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
        catalogReady = false,
        // CP-03: `artworkUri` is AUTOMATIC art -- a chosen face rides `tileIconUri` or the wired face.
        defaultArt = homeCustomEnrichDefaultArt(
            subject = HomeCustomEnrichSubject.GAME,
            kind = EnrichMediaKind.ICON,
            title = current.row.title,
            platformIdValue = current.row.platformId?.value ?: current.row.supportingLabel,
            automaticArtUri = current.row.artworkUri,
            tileIconUri = current.row.tileIconUri,
        ),
        mediaState = MediaState.SHELF,
        mediaTypes = pageTypes,
        mediaTypeFilters = customMediaTypeFilters(current, pageTypes),
        iconArt = early?.let { read -> mergeIconArtCatalogs(read.associate { it.lane to it.display }, winner = null) } ?: IconArtCatalog.Empty,
        faceTypes = iconArtTypeValues(current.row.platformId, emulated),
    ).copy(catalogFirstRead = early == null)
    scope.launch {
        // Read once, before any lane is adopted: adopting must change nothing on the shelf, so no adopted pick is stamped later than the
        // pick the shelf paints now.
        val (inputs, winner) = withContext(Dispatchers.IO) {
            val read = early ?: IconArtStore.laneInputs(context, cacheKey)
            read to IconArtStore.shelfWinner(context, cacheKey)
        }
        val ready = inputs.none(IconArtLaneInput::needsMigration)
        val display = mergeIconArtCatalogs(inputs.associate { it.lane to it.display }, winner.lane)
        update(initial.sessionId) { live ->
            live.copy(
                saved = display.saved,
                savedLanes = display.lanes,
                activeAssetId = display.activeAssetId,
                catalogReady = ready,
                catalogFirstRead = false,
            )
        }
        if (ready) return@launch
        var failed = false
        val notAfter = winner.pickedAtMillis
        for (input in inputs.filter(IconArtLaneInput::needsMigration)) {
            val migrationKey = "${input.lane.kind.wireName}/$cacheKey"
            val migration = migrations[migrationKey]
                ?: scope.async {
                    withContext(NonCancellable) { IconArtStore.adopt(context, cacheKey, input, notAfter) }
                }.also { migrations[migrationKey] = it }
            val outcome = try {
                migration.await()
            } catch (_: Exception) {
                EnrichLegacyMigrationOutcome.Failed(EnrichSavedStore.load(context, input.lane.kind, cacheKey))
            } finally {
                if (migrations[migrationKey] === migration) migrations.remove(migrationKey)
            }
            if (outcome is EnrichLegacyMigrationOutcome.Failed) failed = true
        }
        val catalog = IconArtStore.loadOffMain(context, cacheKey)
        update(initial.sessionId) { live ->
            if (failed) {
                live.copy(catalogReady = false, note = "Saved choice couldn’t be prepared. Go back and try again.")
            } else {
                live.copy(
                    saved = catalog.saved,
                    savedLanes = catalog.lanes,
                    activeAssetId = catalog.activeAssetId,
                    catalogReady = true,
                )
            }
        }
    }
    return initial
}
