package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirNorseBookFrame
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import java.util.Locale

/** Dedicated Library Search authority. The retained gamelist remains mounted underneath this scope. */
internal val LibrarySearchScope = FocusScope("library/search")

/** Hard child scope used only while a Genre or Platform choice list is open. */
internal val LibrarySearchFilterPickerScope = FocusScope("library/search/filterPicker")

/** Controller/touch entry on the retained gamelist header. */
internal val LibrarySearchLauncherKey = FocusKey("library/gamelist/search")

internal val LibrarySearchQueryControlKey = FocusKey("library/search/query")
internal val LibrarySearchGenreFilterKey = FocusKey("library/search/filter/genre")
internal val LibrarySearchPlatformFilterKey = FocusKey("library/search/filter/platform")
internal val LibrarySearchClearKey = FocusKey("library/search/clear")

internal enum class LibrarySearchPicker {
    GENRE,
    PLATFORM,
}

internal fun librarySearchGameResultKey(gameId: GameId): FocusKey =
    FocusKey("library/search/result/game/${gameId.value}")

internal fun librarySearchGenrePickerKey(option: LibrarySearchGenreOption?): FocusKey =
    FocusKey(
        option?.let { "library/search/picker/genre/exact/${it.key.value}" }
            ?: "library/search/picker/genre/all",
    )

internal fun librarySearchPlatformPickerKey(platformId: PlatformId?): FocusKey =
    FocusKey(
        platformId?.let { "library/search/picker/platform/exact/${it.value}" }
            ?: "library/search/picker/platform/all",
    )

internal fun librarySearchResultGameId(key: FocusKey?): GameId? {
    val value = key?.value ?: return null
    val prefix = "library/search/result/game/"
    return value.takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf(String::isNotBlank)
        ?.let(::GameId)
}

internal data class LibrarySearchGeometry(
    val compact: Boolean,
    val topControlsSingleRow: Boolean,
    val panelHorizontalInsetDp: Float,
    val panelVerticalInsetDp: Float,
    val controlMinHeightDp: Float,
    val resultMinHeightDp: Float,
)

internal fun resolveLibrarySearchGeometry(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
): LibrarySearchGeometry {
    val compact = widthDp < 600f || heightDp < 480f || fontScale > 1.1f
    return LibrarySearchGeometry(
        compact = compact,
        topControlsSingleRow = widthDp >= 600f,
        panelHorizontalInsetDp = if (compact) 14f else 28f,
        panelVerticalInsetDp = if (compact) 10f else 20f,
        controlMinHeightDp = if (compact) 44f else 48f,
        resultMinHeightDp = if (compact) 50f else 56f,
    )
}

/**
 * The result list's own bottom breathing room -- a plain constant.
 *
 * It once read `10f + <a primary-M reservation>`, reserving VERTICAL space at the bottom of the panel for
 * an orb that `defaultMButtonPosition` parks at the TOP-end corner. That reservation is retired product-wide
 * (no surface reserves for the orb at all), and this is the 10dp of breathing room
 * it always was underneath the arithmetic.
 */
internal const val LibrarySearchResultBottomPaddingDp: Float = 10f

/** The result list's own trailing content padding: none, so the rows end where the fields above them end. */
internal const val LibrarySearchResultEndPaddingDp: Float = 0f

/** The facet and result rows' corner: one symbol for the clip and the ring (ring migration, 2026-09-28; R-1). */
private val LibrarySearchRowCorner = 12.dp

/** The picker rows' corner, likewise. */
private val LibrarySearchPickerRowCorner = 10.dp

internal fun librarySearchTopControlKeys(
    origin: LibrarySearchOrigin,
): List<FocusKey> = buildList {
    add(LibrarySearchQueryControlKey)
    add(LibrarySearchGenreFilterKey)
    if (origin == LibrarySearchOrigin.FavoriteGames) add(LibrarySearchPlatformFilterKey)
}

internal fun librarySearchTransitionKey(
    session: LibrarySearchSession?,
): LibrarySearchOrigin? = session?.origin

internal fun resolveLibrarySearchTouchOrigin(
    focused: FocusKey?,
    games: List<GameRow>,
    detailGame: GameRow?,
    preparedGameId: GameId?,
): GameId? = when {
    focused == GamelistGetArtKey -> detailGame?.gameId
    else -> games.firstOrNull { gameKey(it) == focused }?.gameId
        ?: preparedGameId?.takeIf { candidate -> games.any { it.gameId == candidate } }
}

internal fun librarySearchSelectedPickerKey(
    picker: LibrarySearchPicker,
    criteria: LibrarySearchCriteria,
    facets: LibrarySearchFacets,
): FocusKey = when (picker) {
    LibrarySearchPicker.GENRE -> when (val genre = criteria.genre) {
        LibrarySearchGenreFilter.All -> librarySearchGenrePickerKey(null)
        is LibrarySearchGenreFilter.Exact ->
            facets.genres.firstOrNull { it.key == genre.key }
                ?.let(::librarySearchGenrePickerKey)
                ?: librarySearchGenrePickerKey(null)
    }
    LibrarySearchPicker.PLATFORM -> when (val platform = criteria.platform) {
        LibrarySearchPlatformFilter.All -> librarySearchPlatformPickerKey(null)
        is LibrarySearchPlatformFilter.Exact ->
            facets.platforms.firstOrNull { it == platform.platformId }
                ?.let(::librarySearchPlatformPickerKey)
                ?: librarySearchPlatformPickerKey(null)
    }
}

@Composable
internal fun LibrarySearchOverlay(
    session: LibrarySearchSession,
    criteria: LibrarySearchCriteria,
    facets: LibrarySearchFacets,
    games: List<GameRow>,
    platforms: List<PlatformRow>,
    picker: LibrarySearchPicker?,
    authority: FocusAuthority,
    focused: FocusKey?,
    onOpenKeyboard: () -> Unit,
    onOpenPicker: (LibrarySearchPicker) -> Unit,
    onSelectGenre: (LibrarySearchGenreFilter) -> Unit,
    onSelectPlatform: (LibrarySearchPlatformFilter) -> Unit,
    onClear: () -> Unit,
    onActivateGame: (GameRow) -> Unit,
    onDismissPicker: () -> Unit,
    modifier: Modifier = Modifier,
    /** A test seam: false leaves out the Genre/Platform picker's layer, which draws through a RenderNode that a software-canvas render test cannot draw. Production never passes it. */
    drawsPickerLayer: Boolean = true,
) {
    val resultKeys = remember(games) { games.map { librarySearchGameResultKey(it.gameId) } }
    RegisterLibrarySearchVirtualColumn(
        authority = authority,
        scope = LibrarySearchScope,
        keys = resultKeys,
        topOffset = 800f,
    )
    val listState = rememberLazyListState()
    val focusedResultIndex = focused?.let(resultKeys::indexOf) ?: -1
    ScrollFocusedLazyItemIntoView(
        listState = listState,
        focusedIndex = focusedResultIndex,
        focusIdentity = resultKeys.getOrNull(focusedResultIndex),
        contentIdentity = resultKeys,
    )
    LaunchedEffect(session.origin) {
        withFrameNanos { }
        authority.requestFocus(
            when (session.lastFocused) {
                LibrarySearchFocusIdentity.Genre -> LibrarySearchGenreFilterKey
                LibrarySearchFocusIdentity.Platform -> LibrarySearchPlatformFilterKey
                LibrarySearchFocusIdentity.Clear -> LibrarySearchClearKey
                is LibrarySearchFocusIdentity.Result -> {
                    val id = (session.lastFocused.identity as? LibrarySearchResultIdentity.Game)?.gameId
                    id?.let(::librarySearchGameResultKey)?.takeIf(resultKeys::contains)
                        ?: LibrarySearchQueryControlKey
                }
                LibrarySearchFocusIdentity.Query -> LibrarySearchQueryControlKey
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(mimirGlassScrimColor())
            .pointerInput(Unit) {
                // Consume only pointer changes left unclaimed by Search children. This is a semantics-neutral
                // modal shield: controls still click, blank glass can never activate the retained gamelist.
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            if (!change.isConsumed) change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val geometry = resolveLibrarySearchGeometry(
                widthDp = maxWidth.value,
                heightDp = maxHeight.value,
                fontScale = density.fontScale,
            )
            MimirLiquidGlassPanel(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = geometry.panelHorizontalInsetDp.dp,
                        vertical = geometry.panelVerticalInsetDp.dp,
                    ),
                contentPadding = 0.dp,
            ) {
              val panelPalette = LocalMimirPalette.current
              Column(
                modifier = Modifier
                    .fillMaxSize()
                    // One page of rows, not an open book: no spine through the results (update's sweep, 10-09: "a stray centre line runs through the result rows").
                    .mimirNorseBookFrame(panelPalette, spine = false)
                    .padding(if (geometry.compact) 16.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (geometry.compact) 10.dp else 14.dp),
              ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        MimirChromeText(
                            text = stringResource(R.string.library_search_title),
                            style = MimirTypeScale.RunicEyebrowCompact,
                            color = panelPalette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = libraryResources().getQuantityString(R.plurals.library_search_results, games.size, games.size),
                            style = MimirTypeScale.MicroStatus,
                            color = panelPalette.textSecondary,
                            maxLines = 1,
                        )
                    }
                    MimirControlHintText(
                        text = stringResource(R.string.library_search_hint_back),
                        style = MimirTypeScale.ControlHintCompact,
                        color = panelPalette.textSecondary,
                        maxLines = 1,
                    )
                }

                // The fields' focus ring reaches MimirFocusTileRingReach above them; the count line stood the column's 10-14 dp above, so the halo touched "3 RESULTS".
                // The whole reach, in room above the row, on top of that gap.
                Box(Modifier.padding(top = MimirFocusTileRingReach)) {
                    LibrarySearchTopControls(
                        session = session,
                        criteria = criteria,
                        facets = facets,
                        platforms = platforms,
                        authority = authority,
                        focused = focused,
                        geometry = geometry,
                        onOpenKeyboard = onOpenKeyboard,
                        onOpenPicker = onOpenPicker,
                    )
                }

                if (games.isEmpty()) {
                    LibrarySearchEmptyState(
                        criteriaActive = criteria != LibrarySearchCriteria(),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // Top only: the ring's reach on the list's own axis. A vertical list clips its sides 30 dp out (Compose's own scrollable clip), so a row's ring reaches past its edge
                        // without padding, and the rows share the left and right edges of the fields above them (update's sweep, 10-09: the fields began at x=70, the rows at x=102).
                        contentPadding = PaddingValues(
                            top = MimirFocusTileRingReach,
                            end = LibrarySearchResultEndPaddingDp.dp,
                            bottom = LibrarySearchResultBottomPaddingDp.dp,
                        ),
                    ) {
                        items(games, key = { it.gameId.value }) { game ->
                            LibrarySearchResultRow(
                                game = game,
                                platformLabel = platforms
                                    .firstOrNull { it.platformId == game.platformId }
                                    ?.label
                                    ?: game.platformId.value.uppercase(Locale.ROOT),
                                authority = authority,
                                focused = focused == librarySearchGameResultKey(game.gameId),
                                minHeightDp = geometry.resultMinHeightDp,
                                onActivate = { onActivateGame(game) },
                            )
                        }
                    }
                }

                if (criteria != LibrarySearchCriteria()) {
                    LibrarySearchControl(
                        key = LibrarySearchClearKey,
                        authority = authority,
                        focused = focused == LibrarySearchClearKey,
                        label = stringResource(R.string.library_search_clear_label),
                        value = stringResource(R.string.library_search_clear_value),
                        minHeightDp = geometry.controlMinHeightDp,
                        onActivate = onClear,
                        // The last thing in the frame: its focus ring's whole reach below it, so the halo stops short of the frame's lower corner brackets.
                        modifier = Modifier.fillMaxWidth().padding(bottom = MimirFocusTileRingReach),
                    )
                }
              }
            }

            if (drawsPickerLayer) LibraryOverlayPixelFadeTransition(targetState = picker) { activePicker ->
                LibrarySearchFilterPicker(
                    picker = activePicker,
                    criteria = criteria,
                    facets = facets,
                    platforms = platforms,
                    authority = authority,
                    focused = focused,
                    onSelectGenre = onSelectGenre,
                    onSelectPlatform = onSelectPlatform,
                    onDismiss = onDismissPicker,
                )
            }
        }
    }
}

@Composable
private fun LibrarySearchTopControls(
    session: LibrarySearchSession,
    criteria: LibrarySearchCriteria,
    facets: LibrarySearchFacets,
    platforms: List<PlatformRow>,
    authority: FocusAuthority,
    focused: FocusKey?,
    geometry: LibrarySearchGeometry,
    onOpenKeyboard: () -> Unit,
    onOpenPicker: (LibrarySearchPicker) -> Unit,
) {
    // The fields write the genre and the platform as the rest of the app does (sentence case; the platform's own name), not in capitals.
    val queryPlaceholder = stringResource(R.string.library_search_query_placeholder)
    val allGenres = stringResource(R.string.library_search_all_genres)
    val allPlatforms = stringResource(R.string.library_search_all_platforms)
    val queryValue = criteria.query.trim().ifBlank { queryPlaceholder }
    val genreValue = when (val genre = criteria.genre) {
        LibrarySearchGenreFilter.All -> allGenres
        is LibrarySearchGenreFilter.Exact ->
            facets.genres.firstOrNull { it.key == genre.key }?.label
                ?: genre.key.value.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
    val platformValue = when (val platform = criteria.platform) {
        LibrarySearchPlatformFilter.All -> allPlatforms
        is LibrarySearchPlatformFilter.Exact ->
            platforms.firstOrNull { it.platformId == platform.platformId }?.label
                ?: platform.platformId.value.uppercase(Locale.ROOT)
    }
    val query: @Composable (Modifier) -> Unit = { modifier ->
        LibrarySearchControl(
            key = LibrarySearchQueryControlKey,
            authority = authority,
            focused = focused == LibrarySearchQueryControlKey,
            label = stringResource(R.string.library_search_field_query),
            value = queryValue,
            minHeightDp = geometry.controlMinHeightDp,
            onActivate = onOpenKeyboard,
            modifier = modifier,
        )
    }
    val genre: @Composable (Modifier) -> Unit = { modifier ->
        LibrarySearchControl(
            key = LibrarySearchGenreFilterKey,
            authority = authority,
            focused = focused == LibrarySearchGenreFilterKey,
            label = stringResource(R.string.library_search_field_genre),
            value = genreValue,
            minHeightDp = geometry.controlMinHeightDp,
            onActivate = { onOpenPicker(LibrarySearchPicker.GENRE) },
            modifier = modifier,
        )
    }
    val platform: @Composable (Modifier) -> Unit = { modifier ->
        LibrarySearchControl(
            key = LibrarySearchPlatformFilterKey,
            authority = authority,
            focused = focused == LibrarySearchPlatformFilterKey,
            label = stringResource(R.string.library_search_field_platform),
            value = platformValue,
            minHeightDp = geometry.controlMinHeightDp,
            onActivate = { onOpenPicker(LibrarySearchPicker.PLATFORM) },
            modifier = modifier,
        )
    }

    if (geometry.topControlsSingleRow) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            query(Modifier.weight(1.35f))
            genre(Modifier.weight(1f))
            if (session.origin == LibrarySearchOrigin.FavoriteGames) platform(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            query(Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                genre(Modifier.weight(1f))
                if (session.origin == LibrarySearchOrigin.FavoriteGames) platform(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LibrarySearchControl(
    key: FocusKey,
    authority: FocusAuthority,
    focused: Boolean,
    label: String,
    value: String,
    minHeightDp: Float,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    // Update's sweep (10-09: "focused fields and rows are filled"): a field is the etched chip's plate, a capsule, no fill. The ring is on the plate itself, a pill before the surface with
    // no inset, so its wash is under the opaque metal; the rim hands over to it while the field is focused.
    Column(
        modifier = modifier
            .heightIn(min = minHeightDp.dp)
            .mimirFocusable(authority, key, LibrarySearchScope, autoBringIntoView = false)
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)
            .mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)
            .mimirActivatable(label = label, authority = authority, key = key, onActivate = onActivate)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        MimirChromeText(
            text = label,
            style = MimirTypeScale.MicroStatus,
            color = palette.accent,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MimirTypeScale.MicroStatus,
            color = palette.etchedChipInk(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LibrarySearchResultRow(
    game: GameRow,
    platformLabel: String,
    authority: FocusAuthority,
    focused: Boolean,
    minHeightDp: Float,
    onActivate: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val key = librarySearchGameResultKey(game.gameId)
    val shape = RoundedCornerShape(LibrarySearchRowCorner)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeightDp.dp)
            .mimirFocusable(authority, key, LibrarySearchScope, autoBringIntoView = false)
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(LibrarySearchRowCorner),
                restingOutline = true,
                // The same ground focused and at rest: the house wash under the ring does the rest, with no lift to a heavier fill (update's sweep, 10-09).
                fill = SolidColor(palette.surfaceRaised.copy(alpha = 0.82f)),
            )
            .clip(shape)
            .mimirActivatable(label = game.title, authority = authority, key = key, onActivate = onActivate)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (focused) palette.accent else palette.outline),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = game.title,
                style = MimirType.RowSupport.copy(fontWeight = FontWeight.SemiBold),
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The genre as the rest of the app writes it ("Platformer": the shelf's eyebrow, the list, the card), beside the platform's own label. The overlay's fields and pickers write them the
                // same way (sentence case; nothing is stored or applied in capitals).
                text = buildList {
                    add(platformLabel)
                    game.genre?.trim()?.takeIf(String::isNotBlank)?.let { add(it) }
                }.joinToString("  ·  "),
                style = MimirTypeScale.MicroStatus,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MimirControlHintText(
            text = stringResource(R.string.library_search_hint_open),
            style = MimirTypeScale.ControlHintCompact,
            color = palette.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun LibrarySearchEmptyState(
    criteriaActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MimirChromeText(
            text = stringResource(if (criteriaActive) R.string.library_search_empty_matches_title else R.string.library_search_empty_library_title),
            style = MimirType.TitleCaps,
            color = palette.textPrimary,
        )
        Text(
            text = stringResource(if (criteriaActive) R.string.library_search_empty_matches_body else R.string.library_search_empty_library_body),
            style = MimirTypeScale.MicroStatus,
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun LibrarySearchFilterPicker(
    picker: LibrarySearchPicker,
    criteria: LibrarySearchCriteria,
    facets: LibrarySearchFacets,
    platforms: List<PlatformRow>,
    authority: FocusAuthority,
    focused: FocusKey?,
    onSelectGenre: (LibrarySearchGenreFilter) -> Unit,
    onSelectPlatform: (LibrarySearchPlatformFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val options = when (picker) {
        LibrarySearchPicker.GENRE -> listOf(librarySearchGenrePickerKey(null)) +
            facets.genres.map(::librarySearchGenrePickerKey)
        LibrarySearchPicker.PLATFORM -> listOf(librarySearchPlatformPickerKey(null)) +
            facets.platforms.map(::librarySearchPlatformPickerKey)
    }
    RegisterLibrarySearchVirtualColumn(
        authority = authority,
        scope = LibrarySearchFilterPickerScope,
        keys = options,
        topOffset = 100f,
    )
    val selectedKey = librarySearchSelectedPickerKey(picker, criteria, facets)
    LaunchedEffect(picker, selectedKey) {
        withFrameNanos { }
        authority.requestFocus(selectedKey)
    }
    val listState = rememberLazyListState()
    val focusedIndex = focused?.let(options::indexOf) ?: -1
    ScrollFocusedLazyItemIntoView(
        listState = listState,
        focusedIndex = focusedIndex,
        focusIdentity = options.getOrNull(focusedIndex),
        contentIdentity = options,
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.surfaceBase.copy(alpha = 0.78f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .heightIn(max = 520.dp)
                .fillMaxWidth(0.78f)
                .mimirNorseBookFrame(palette, spine = false)
                .clickable(onClick = {})
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MimirChromeText(
                text = stringResource(if (picker == LibrarySearchPicker.GENRE) R.string.library_search_picker_genre else R.string.library_search_picker_platform),
                style = MimirTypeScale.RunicEyebrowCompact,
                color = palette.textPrimary,
                maxLines = 1,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(MimirFocusTileRingReach),
            ) {
                if (picker == LibrarySearchPicker.GENRE) {
                    item(key = librarySearchGenrePickerKey(null).value) {
                        LibrarySearchPickerRow(
                            key = librarySearchGenrePickerKey(null),
                            label = stringResource(R.string.library_search_all_genres),
                            selected = criteria.genre == LibrarySearchGenreFilter.All,
                            focused = focused == librarySearchGenrePickerKey(null),
                            authority = authority,
                            onActivate = { onSelectGenre(LibrarySearchGenreFilter.All) },
                        )
                    }
                    items(facets.genres, key = { it.key.value }) { option ->
                        LibrarySearchPickerRow(
                            key = librarySearchGenrePickerKey(option),
                            label = option.label,
                            selected = criteria.genre == LibrarySearchGenreFilter.Exact(option.key),
                            focused = focused == librarySearchGenrePickerKey(option),
                            authority = authority,
                            onActivate = {
                                onSelectGenre(LibrarySearchGenreFilter.Exact(option.key))
                            },
                        )
                    }
                } else {
                    item(key = librarySearchPlatformPickerKey(null).value) {
                        LibrarySearchPickerRow(
                            key = librarySearchPlatformPickerKey(null),
                            label = stringResource(R.string.library_search_all_platforms),
                            selected = criteria.platform == LibrarySearchPlatformFilter.All,
                            focused = focused == librarySearchPlatformPickerKey(null),
                            authority = authority,
                            onActivate = { onSelectPlatform(LibrarySearchPlatformFilter.All) },
                        )
                    }
                    items(facets.platforms, key = { it.value }) { platformId ->
                        LibrarySearchPickerRow(
                            key = librarySearchPlatformPickerKey(platformId),
                            label = platforms.firstOrNull { it.platformId == platformId }
                                ?.label
                                ?: platformId.value.uppercase(Locale.ROOT),
                            selected = criteria.platform == LibrarySearchPlatformFilter.Exact(platformId),
                            focused = focused == librarySearchPlatformPickerKey(platformId),
                            authority = authority,
                            onActivate = {
                                onSelectPlatform(LibrarySearchPlatformFilter.Exact(platformId))
                            },
                        )
                    }
                }
            }
            MimirControlHintText(
                text = stringResource(R.string.library_search_hint_picker),
                style = MimirTypeScale.ControlHintCompact,
                color = palette.textSecondary,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun LibrarySearchPickerRow(
    key: FocusKey,
    label: String,
    selected: Boolean,
    focused: Boolean,
    authority: FocusAuthority,
    onActivate: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(LibrarySearchPickerRowCorner)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .mimirFocusable(
                authority,
                key,
                LibrarySearchFilterPickerScope,
                autoBringIntoView = false,
            )
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(LibrarySearchPickerRowCorner),
                restingOutline = true,
                fill = SolidColor(palette.surfaceRaised.copy(alpha = if (focused) 0.98f else 0.86f)),
            )
            .clip(shape)
            .mimirActivatable(label = label, authority = authority, key = key, onActivate = onActivate)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "ᚱ" else "·",
            style = MimirTypeScale.RailAction,
            color = if (selected) palette.accent else palette.textSecondary,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MimirType.RowSupport,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RegisterLibrarySearchVirtualColumn(
    authority: FocusAuthority,
    scope: FocusScope,
    keys: List<FocusKey>,
    topOffset: Float,
) {
    DisposableEffect(authority, scope, keys, topOffset) {
        keys.forEachIndexed { index, key ->
            val top = topOffset + index * 100f
            authority.registerVirtual(
                key = key,
                scope = scope,
                bounds = FocusBounds(
                    left = 0f,
                    top = top,
                    right = 100f,
                    bottom = top + 80f,
                ),
            )
        }
        onDispose { keys.forEach(authority::unregisterVirtual) }
    }
}
