package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.MimirWordEllipsisText
import com.gruning.mimir.rewrite.core.ui.mimirCrossSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirCrossSwapTransform
import com.gruning.mimir.rewrite.core.ui.mimirEllipsizeAtWord
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFade
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirSheetSpec
import com.gruning.mimir.rewrite.core.ui.resolve

/*
 * The Customize media page's top row , drawn with the Achievement hub's chip family. Left to right, on ONE line:
 * Search (seated at the page's one width, below), Media ▾, then the chosen type's own chips, right-aligned, as the Achievements
 * hub reads. No scroll. There is no Back chip
 * : the page opens only on Main, which is pad-driven, so B returns and the footer says "B BACK"; a tap outside the
 * panel closes it by touch. Back chips belong to the touch-first Bottom.
 *
 * Seating, in order, as the room shrinks (every step is measured, nothing is guessed):
 *  1. everything in full: "Icon art ▾", "Type · All ▾", and Search wide;
 *  2. Search keeps ONE width for the whole page. It is the row's one flexible piece, but it flexes by PAGE, not by type: [customMediaPageSeating] measures every
 *     type the page offers (each one's own Media chip and chips, at the values it opens with), seats the WIDEST group, and gives Search
 *     what that leaves. Switching types never resizes or moves Search. A type with fewer chips leaves its spare room between Search and
 *     its chips, which stay right-aligned. The chips' form (full, value, folded) is still each type's own;
 *  3. Search shows the game's name as far as it stands, and never cuts inside a word: the whole name when it fits, else the name cut at
 *     a WORD with an ellipsis ("The Legend of Zelda Tears…", [MimirWordEllipsisText]), and only when not even the first word and its
 *     ellipsis can stand is it the 48 dp magnifier (the Library header's rule, `LibraryGamelistSearchMarkWidthDp`). the earlier update's "never a
 *     clipped 'Metroid F'" still holds. The chips below give up their label only when Search at 48 dp cannot stand beside them;
 *  4. the chips give up their label and keep their value ("Both ▾"; the panel they open still names the filter);
 *  5. only if the chips STILL cannot stand beside Media and the magnifier, they fold into ONE Filters chip: a 48 dp etched
 *     mark with a dot when any filter is off its default, like the collapsed magnifier. Its panel lists the chips as rows
 *     ("Source · Both"), and a row opens that chip's own value panel. The row is ONE line at every width and font; the pad
 *     walks the stops that are drawn (see [CustomMediaFold]).
 */

internal const val CustomMediaTopRowTag = "custom-media-top-row"
internal const val CustomMediaTypeChipTag = "custom-media-type"
internal const val CustomMediaSearchTag = "custom-media-search"
internal const val CustomMediaSearchPlateTag = "custom-media-search-plate"
internal const val CustomMediaFiltersTag = "custom-media-filters"
internal const val CustomMediaFiltersPlateTag = "custom-media-filters-plate"
internal const val CustomMediaFiltersPanelTag = "custom-media-filters-panel"
internal const val CustomMediaMediaPanelTag = "custom-media-media-panel"
internal const val CustomMediaFilterPanelTag = "custom-media-filter-panel"

internal fun customMediaFilterChipTag(filterKey: String): String = "custom-media-chip-$filterKey"

/** How much of the scrim the panels bring over the page: the page is already inside a glass sheet, so half a veil is plenty. */
private const val CustomMediaPanelScrim = 0.5f

/** The smallest the search may be: a 48 dp target around its magnifier. */
private const val CustomMediaSearchMinDp = 48f

/** A chip's plate padding across (the hub's md on each side). */
private const val CustomMediaChipPaddingDp = 24f

/** The words a chip of the filters wears: with its label, or (when the row is tight) its value alone. */
internal enum class CustomMediaChipForm { FULL, VALUE }

internal data class CustomMediaRowPlan(
    val chipForm: CustomMediaChipForm,
    /** The type's chips are folded into the one Filters chip. */
    val foldChips: Boolean,
)

internal fun customMediaTypeChipText(type: CustomMediaType?): String = "${type?.label ?: "Media"} ▾"

internal fun customMediaFilterChipText(filter: HomeCustomEnrichFilter, form: CustomMediaChipForm): String = when (form) {
    CustomMediaChipForm.FULL -> "${filter.label} · ${filter.selectedDisplay} ▾"
    CustomMediaChipForm.VALUE -> "${filter.selectedDisplay} ▾"
}

/** A chip's width in the row: its word and plate padding, never narrower than the 48 dp target. */
internal fun customMediaChipLayoutDp(textDp: Float): Float = maxOf(MimirSpacing.minTarget.value, textDp + CustomMediaChipPaddingDp)

/**
 * Which seating the row takes (see the file header). Pure: every width is a measured chip width in dp, and
 * [availableDp] is the row's. Search is counted at its smallest, a 48 dp target, because it is the piece that gives.
 */
internal fun customMediaRowPlan(
    availableDp: Float,
    mediaDp: Float,
    chipFullDp: List<Float>,
    chipValueDp: List<Float>,
    searchMinDp: Float = CustomMediaSearchMinDp,
    gapDp: Float = MimirSpacing.xs.value,
): CustomMediaRowPlan {
    fun lineDp(items: List<Float>): Float = items.sum() + gapDp * (items.size - 1).coerceAtLeast(0)
    fun oneLine(chips: List<Float>): Boolean = lineDp(listOf(mediaDp) + chips + searchMinDp) <= availableDp
    if (oneLine(chipFullDp)) return CustomMediaRowPlan(CustomMediaChipForm.FULL, foldChips = false)
    if (oneLine(chipValueDp)) return CustomMediaRowPlan(CustomMediaChipForm.VALUE, foldChips = false)
    // Fold: Media, the one 48 dp Filters chip and Search at its smallest. Nothing is left to give up beyond that.
    return CustomMediaRowPlan(CustomMediaChipForm.VALUE, foldChips = true)
}

/** The rounding the page's seat leaves between Search and the widest group: chip plates are whole pixels, so a dp sum can run a pixel or two long. */
private const val CustomMediaSeatRoundingDp = 2f

/** What one type of the page asks of the row, as the row lays it out: its Media chip, and its chips in each form. */
internal data class CustomMediaTypeRoom(
    val mediaDp: Float,
    val chipFullDp: List<Float>,
    val chipValueDp: List<Float>,
)

/** The page's seating: Search's ONE width, and each type's own plan (in the order of the rooms it was given). */
internal data class CustomMediaPageSeating(
    val searchDp: Float,
    val plans: List<CustomMediaRowPlan>,
)

/** Media and the chips as [plan] seats them, with the gaps between: the width of the row's right-hand group. */
internal fun customMediaGroupDp(
    room: CustomMediaTypeRoom,
    plan: CustomMediaRowPlan,
    gapDp: Float = MimirSpacing.xs.value,
): Float {
    val chips = when {
        plan.foldChips -> listOf(MimirSpacing.minTarget.value)
        plan.chipForm == CustomMediaChipForm.FULL -> room.chipFullDp
        else -> room.chipValueDp
    }
    val pieces = listOf(room.mediaDp) + chips
    return pieces.sum() + gapDp * (pieces.size - 1)
}

/**
 * Where Search sits on the PAGE, whatever the type. Every type is planned as
 * [customMediaRowPlan] plans a row (Search counted at its smallest), and the WIDEST group that results is the one Search is seated beside: Search is what
 * that group leaves, never less than [searchMinDp]. The result depends on the page and on nothing the player has chosen, so a type switch cannot change
 * it. A type with fewer chips than the widest leaves the difference between Search and its chips; the chips' form (full, value, folded) stays each type's
 * own plan.
 */
internal fun customMediaPageSeating(
    availableDp: Float,
    rooms: List<CustomMediaTypeRoom>,
    searchMinDp: Float = CustomMediaSearchMinDp,
    gapDp: Float = MimirSpacing.xs.value,
): CustomMediaPageSeating {
    val plans = rooms.map { customMediaRowPlan(availableDp, it.mediaDp, it.chipFullDp, it.chipValueDp, searchMinDp, gapDp) }
    val widestDp = rooms.indices.maxOfOrNull { customMediaGroupDp(rooms[it], plans[it], gapDp) } ?: 0f
    return CustomMediaPageSeating(
        searchDp = (availableDp - gapDp - widestDp - CustomMediaSeatRoundingDp).coerceAtLeast(searchMinDp),
        plans = plans,
    )
}

/** One type's room, measured by [chipDp] (a chip's width in the row for its words). */
internal fun customMediaTypeRoom(type: CustomMediaType?, chips: List<HomeCustomEnrichFilter>, chipDp: (String) -> Float): CustomMediaTypeRoom =
    CustomMediaTypeRoom(
        mediaDp = chipDp(customMediaTypeChipText(type)),
        chipFullDp = chips.map { chipDp(customMediaFilterChipText(it, CustomMediaChipForm.FULL)) },
        chipValueDp = chips.map { chipDp(customMediaFilterChipText(it, CustomMediaChipForm.VALUE)) },
    )

/**
 * The most chips [type] shows at once, at its opening values: the merged Icon art picker shows Region (where ScreenScraper is asked), Style and Size only for
 * a Type of All or Square, so All is its widest; every other type shows all of its chips (Hero Motion drops Format for a provider-specific Source, but the
 * seat is for the widest it can be).
 */
internal fun customMediaSeatingChips(type: CustomMediaType, filters: List<HomeCustomEnrichFilter>): List<HomeCustomEnrichFilter> =
    if (type.isMergedIconArt) iconArtVisibleFilters(filters, IconArtType.All) else filters

/** The chips [type] opens with, from the builder's ([HomeCustomEnrichState.mediaTypeFilters]); a hand-made state without them reads the live ones for its own type. */
private fun HomeCustomEnrichState.openingFiltersOf(type: CustomMediaType): List<HomeCustomEnrichFilter> =
    mediaTypeFilters[type.selection]
        ?: when {
            type.selection == mediaSelection() -> filters
            type.isMergedIconArt -> homeCustomEnrichIconArtFilters()
            else -> homeCustomEnrichDefaultFilters(type.kind)
        }

/** Every type this page offers, as the row must seat it: one room per type, plus the live one when the state's own type is not among them. */
internal fun HomeCustomEnrichState.customMediaPageRooms(chipDp: (String) -> Float): List<CustomMediaTypeRoom> {
    val rooms = mediaTypes.map { type -> customMediaTypeRoom(type, customMediaSeatingChips(type, openingFiltersOf(type)), chipDp) }
    return if (currentMediaType() == null) rooms + customMediaTypeRoom(null, visibleFilters(), chipDp) else rooms
}

/** What Search draws: the game's name as far as it stands, or only the magnifier. */
internal sealed interface CustomMediaSearchFace {
    /** [text] is the name whole, or cut at a word and closed with an ellipsis. */
    data class Name(val text: String) : CustomMediaSearchFace

    /** Not even the first word and its ellipsis can stand in the room: the 48 dp magnifier. */
    data object Mark : CustomMediaSearchFace
}

/**
 * Which face Search wears for [name], given [fits] (does this text, as drawn, stand in Search's room?). The whole name when it fits; else the longest run of whole
 * words that fits with an ellipsis ([mimirEllipsizeAtWord]); else the magnifier. A name is never cut inside a word: [mimirEllipsizeAtWord] hands the text back whole
 * when no word boundary answers, and whole text that does not fit is the magnifier (the Library header's rule for its Search).
 */
internal fun customMediaSearchFace(name: String, fits: (String) -> Boolean): CustomMediaSearchFace {
    val shown = mimirEllipsizeAtWord(name, fits)
    return if (fits(shown)) CustomMediaSearchFace.Name(shown) else CustomMediaSearchFace.Mark
}

/** Search's plate chrome across: the padding on both sides, the magnifier and the gap between it and the name. */
private val CustomMediaSearchChromeDp: Float
    get() = ActivitySearchGlyphSize.value + MimirSpacing.xs.value + MimirSpacing.md.value * 2

/** Margin the name's fit keeps from the room's edge (px rounding in the plate's slot), so the word-cutting text never needs its mid-word last resort. */
private const val CustomMediaSearchFitMarginDp = 2f

/** The room a name has in a Search seated [seatDp] wide, less the margin it keeps from the edge. */
internal fun customMediaSearchNameRoomDp(seatDp: Float): Float = seatDp - CustomMediaSearchChromeDp - CustomMediaSearchFitMarginDp

private fun measuredDp(measurer: TextMeasurer, density: Density, text: String): Float =
    with(density) { measurer.measure(text, ActivityChipLabelStyle, maxLines = 1, softWrap = false).size.width.toDp().value }

/**
 * The page's runic eyebrow: it names the PAGE, whatever the type. The Media chip below already says which type is chosen, so the
 * eyebrow does not say it again.
 */
internal fun customMediaHeader(@Suppress("UNUSED_PARAMETER") type: CustomMediaType): Pair<String, String> =
    "Customize media" to "ᛗ · CUSTOMIZE MEDIA"

internal fun customMediaSearchDescription(state: HomeCustomEnrichState): String {
    val label = homeCustomEnrichSearchLabel(state.kind)
    return if (state.queryDraft.isBlank()) label else "$label, ${state.queryDraft}"
}

/**
 * A chip's 48 dp hit box on the page's own authority. Focus lives on this box and the box paints nothing; the RING is on the
 * plate ([customMediaChipRing]), so one ring stands on the plate's own metal rim and not floating a few dp off it (the double
 * rim reported in a screenshot). The focused chip is drawn above its neighbours: the plates sit 4 dp apart and the
 * ring reaches 14, so a later sibling's plate must not cover the ring's near side.
 */
@Composable
private fun customMediaChipTarget(authority: FocusAuthority, key: FocusKey, focused: Boolean): Modifier =
    Modifier
        .zIndex(if (focused) 1f else 0f)
        .mimirFocusable(authority, key, HomeCustomEnrichScope, autoBringIntoView = false)

/** The ring of a top-row control: the hub's pill ring, drawn on the PLATE before its etched surface (the ring paints outside the plate). */
@Composable
private fun customMediaChipRing(key: FocusKey, focused: Boolean): Modifier =
    Modifier.mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)

/** The ring for a row of a panel, lent to [ActivityChoicePicker] (it has no Activity pad here). */
@Composable
private fun customMediaPanelRowTarget(authority: FocusAuthority, key: FocusKey): Modifier {
    val focusState = authority.state.collectAsState()
    val focused = focusState.value.scope == HomeCustomEnrichScope && focusState.value.focused == key
    return Modifier
        .mimirFocusable(authority, key, HomeCustomEnrichScope, autoBringIntoView = false)
        .mimirSelectionRing(
            focused = focused,
            identity = key,
            shape = MimirRingShape.Row(MimirSpacing.xs),
            inset = MimirSpacing.xs,
        )
}

@Composable
internal fun CustomMediaTopRow(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
    onAction: (ControllerAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Told whether the chips are folded into the one Filters chip, so the host's focus graph walks what is drawn. */
    onFold: (Boolean) -> Unit = {},
) {
    val filters = state.visibleFilters()
    val type = state.currentMediaType()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    DisposableEffect(state.sessionId) { onDispose { CustomMediaFold.set(state.sessionId, false) } }
    BoxWithConstraints(modifier.testTag(CustomMediaTopRowTag)) {
        fun chipDp(text: String): Float = customMediaChipLayoutDp(measuredDp(measurer, density, text))
        // THIS type's own seating, from the chips it shows now: the form its chips take, and whether they fold.
        val plan = customMediaRowPlan(
            availableDp = maxWidth.value,
            mediaDp = chipDp(customMediaTypeChipText(type)),
            chipFullDp = filters.map { chipDp(customMediaFilterChipText(it, CustomMediaChipForm.FULL)) },
            chipValueDp = filters.map { chipDp(customMediaFilterChipText(it, CustomMediaChipForm.VALUE)) },
        )
        // Search's seat is the PAGE's, not this type's : the widest type on the page, measured at the values each opens with.
        val pageRooms = remember(state.mediaTypes, state.mediaTypeFilters, state.filters, measurer, density) {
            state.customMediaPageRooms(::chipDp)
        }
        val seatDp = customMediaPageSeating(maxWidth.value, pageRooms).searchDp
        SideEffect {
            CustomMediaFold.set(state.sessionId, plan.foldChips)
            onFold(plan.foldChips)
        }
        // ONE line at every width and font: Search, Media, the type's chips (or the one Filters chip), as the Achievements hub reads.
        Row(
            Modifier.fillMaxWidth().heightIn(min = homeCustomEnrichTopBandMinHeightDp().dp),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search is seated at the PAGE's width and stands at the row's start; the room the type's own chips leave beyond that sits to its right, between
            // it and the chips, which stay right-aligned. (A weighted child is measured after the others whatever its place in the row, so it can be the
            // first one drawn.) The seat gives way only if this type's chips, at values the player has since changed, are wider than the page's widest.
            // The focused Search is drawn above the Media chip beside it: the ring reaches 14 dp past a plate that stands 4 dp from it.
            BoxWithConstraints(
                Modifier.weight(1f).zIndex(if (focusedKey == homeCustomEnrichSearchKey(state)) 1f else 0f),
                contentAlignment = Alignment.CenterStart,
            ) {
                CustomMediaSearch(authority, state, focusedKey, onAction, seatDp = minOf(seatDp.dp, maxWidth))
            }
            CustomMediaTypeChip(authority, state, type, focusedKey, onAction)
            if (plan.foldChips) {
                CustomMediaFiltersChip(authority, state, filters, focusedKey, onAction)
            } else {
                filters.forEach { filter ->
                    key(filter.key) { CustomMediaFilterChip(authority, state, filter, plan.chipForm, focusedKey, onAction) }
                }
            }
        }
    }
}

@Composable
private fun CustomMediaTypeChip(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    type: CustomMediaType?,
    focusedKey: FocusKey?,
    onAction: (ControllerAction) -> Unit,
) {
    val key = homeCustomEnrichMediaKey(state)
    val focused = focusedKey == key
    ActivityFilterChip(
        text = customMediaTypeChipText(type),
        description = "Media, ${type?.label ?: "none"}",
        actionLabel = "choose the kind of media",
        selected = false,
        onClick = {
            if (!focused) authority.requestFocus(key)
            onAction(ControllerAction.CONFIRM)
        },
        modifier = customMediaChipTarget(authority, key, focused).testTag(CustomMediaTypeChipTag),
        plateModifier = customMediaChipRing(key, focused),
        plateFocused = focused,
    )
}

@Composable
private fun CustomMediaFilterChip(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    filter: HomeCustomEnrichFilter,
    form: CustomMediaChipForm,
    focusedKey: FocusKey?,
    onAction: (ControllerAction) -> Unit,
) {
    val key = homeCustomEnrichFilterKey(state, filter)
    val focused = focusedKey == key
    ActivityFilterChip(
        text = customMediaFilterChipText(filter, form),
        description = "${filter.label}, ${filter.selectedDisplay}",
        actionLabel = "choose ${filter.label.lowercase()}",
        selected = filter.selectedIndex != 0,
        onClick = {
            // A touch filter is a direct control: publish exact focus before the dispatch, so one tap opens this
            // filter and cannot reach the shelf beneath the modal.
            if (!focused) authority.requestFocus(key)
            onAction(ControllerAction.CONFIRM)
        },
        modifier = customMediaChipTarget(authority, key, focused).testTag(customMediaFilterChipTag(filter.key)),
        plateModifier = customMediaChipRing(key, focused),
        plateFocused = focused,
    )
}

internal fun customMediaFiltersDescription(filters: List<HomeCustomEnrichFilter>): String =
    "Filters, " + filters.joinToString(", ") { "${it.label} ${it.selectedDisplay}" }

/**
 * The type's chips, folded into one when the row has no room for them: a 48 dp etched mark (three bars of a filter, like the
 * magnifier's collapsed plate) with a dot when any filter is off its default. A opens the Filters panel, whose rows read
 * "Source · Both" and each open that chip's own value panel.
 */
@Composable
private fun CustomMediaFiltersChip(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    filters: List<HomeCustomEnrichFilter>,
    focusedKey: FocusKey?,
    onAction: (ControllerAction) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val key = homeCustomEnrichFiltersKey(state)
    val focused = focusedKey == key
    val source = remember { MutableInteractionSource() }
    val plateHeight = rememberActivityChipPlateHeight()
    val changed = filters.any { it.selectedIndex != 0 }
    val glyphColor = if (state.filtersPanelOpen || focused) palette.accentBright else palette.textMuted
    Box(
        Modifier
            .size(MimirSpacing.minTarget)
            .then(customMediaChipTarget(authority, key, focused))
            .mimirActivatable(
                label = "show filters",
                contentDescription = customMediaFiltersDescription(filters),
                interactionSource = source,
            ) {
                if (!focused) authority.requestFocus(key)
                onAction(ControllerAction.CONFIRM)
            }
            .mimirPressResponse(source)
            .testTag(CustomMediaFiltersTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = ActivitySearchGlyphSize + MimirSpacing.md * 2, height = plateHeight)
                .testTag(CustomMediaFiltersPlateTag)
                .then(customMediaChipRing(key, focused))
                .mimirEtchedChipSurface(palette, warmed = state.filtersPanelOpen, focused = focused, edgeFlush = true),
            contentAlignment = Alignment.Center,
        ) {
            CustomMediaFiltersGlyph(color = glyphColor)
            if (changed) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = MimirSpacing.xs, end = MimirSpacing.xs)
                        .size(7.dp)
                        .background(palette.accentBright, CircleShape),
                )
            }
        }
    }
}

/** Three bars, long to short, drawn so no font has to carry the mark (the magnifier beside it is drawn the same way). */
@Composable
private fun CustomMediaFiltersGlyph(color: Color) {
    Canvas(Modifier.size(ActivitySearchGlyphSize)) {
        val stroke = 1.6.dp.toPx()
        val w = size.width
        val h = size.height
        listOf(
            h * 0.24f to (0f to w),
            h * 0.50f to (w * 0.2f to w * 0.8f),
            h * 0.76f to (w * 0.4f to w * 0.6f),
        ).forEach { (y, span) ->
            drawLine(color, Offset(span.first, y), Offset(span.second, y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}


/**
 * The search, first in the row: an etched plate at the chips' height holding the magnifier and the name that will be looked up
 * (the game's title until it is typed over), on one line. It is [seatDp] wide, the width the PAGE seats it at ([customMediaPageSeating]), so it
 * never changes with the type . The name is shown WHOLE when it fits that width; else cut at a word with an ellipsis
 * ("The Legend of Zelda Tears…", [MimirWordEllipsisText]), never inside a word; and only when not even the first word and its ellipsis
 * can stand is it only the magnifier, a 48 dp mark like the Achievements hub's collapsed search and this row's Filters chip, with a dot
 * when a name is set (the name is on the keyboard's own pill when the search opens). [customMediaSearchFace] decides it. A opens the
 * keyboard and fetches nothing; Y (or Retry, below) is what fetches.
 */
@Composable
private fun CustomMediaSearch(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
    onAction: (ControllerAction) -> Unit,
    seatDp: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val key = homeCustomEnrichSearchKey(state)
    val focused = focusedKey == key
    val source = remember { MutableInteractionSource() }
    val plateHeight = rememberActivityChipPlateHeight()
    val hasQuery = state.queryDraft.isNotBlank()
    val glyphColor = if (state.keyboardOpen || focused) palette.accentBright else palette.textMuted
    val nameIntent = homeCustomEnrichSearchIntent(state, compactSquare = true)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The name as it is drawn (the intent's visual text), measured at the chip label's own type, against the room this seat leaves it.
    val name = nameIntent.resolve()
    val face = remember(name.visualText, seatDp, measurer, density) {
        val roomDp = customMediaSearchNameRoomDp(seatDp.value)
        customMediaSearchFace(name.visualText) { candidate -> measuredDp(measurer, density, candidate) <= roomDp }
    }
    val showName = face is CustomMediaSearchFace.Name
    Box(
        modifier
            .heightIn(min = MimirSpacing.minTarget)
            .width(if (showName) seatDp else MimirSpacing.minTarget)
            .then(customMediaChipTarget(authority, key, focused))
            .mimirActivatable(
                label = "search art",
                contentDescription = customMediaSearchDescription(state),
                interactionSource = source,
            ) {
                if (!focused) authority.requestFocus(key)
                onAction(ControllerAction.CONFIRM)
            }
            .mimirPressResponse(source)
            .testTag(CustomMediaSearchTag),
        contentAlignment = Alignment.Center,
    ) {
        if (showName) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(plateHeight)
                    .testTag(CustomMediaSearchPlateTag)
                    .then(customMediaChipRing(key, focused))
                    .mimirEtchedChipSurface(palette, warmed = state.keyboardOpen, focused = focused, edgeFlush = true)
                    .padding(horizontal = MimirSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActivitySearchGlyph(color = glyphColor)
                // The whole name, or cut at a word; the intent's spoken text stays the node's name, as it was when this was an intent text.
                MimirWordEllipsisText(
                    text = name.visualText,
                    style = ActivityChipLabelStyle,
                    color = if (hasQuery) palette.materials.hero.onCover else palette.textMuted,
                    modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = name.spokenText },
                    maxLines = 1,
                )
            }
        } else {
            Box(
                Modifier
                    .size(width = ActivitySearchGlyphSize + MimirSpacing.md * 2, height = plateHeight)
                    .testTag(CustomMediaSearchPlateTag)
                    .then(customMediaChipRing(key, focused))
                    .mimirEtchedChipSurface(palette, warmed = state.keyboardOpen, focused = focused, edgeFlush = true),
                contentAlignment = Alignment.Center,
            ) {
                ActivitySearchGlyph(color = glyphColor)
                if (hasQuery) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = MimirSpacing.xs, end = MimirSpacing.xs)
                            .size(7.dp)
                            .background(palette.accentBright, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * The page's motion states, for the type-switch sequence test (the shape of `LocalMimirTextLayoutObserver`): pixels cannot read a ring-safe page's alpha, so the states a
 * draw block reads are handed out instead. Null in the app; the test provides one.
 */
internal class CustomMediaMotionProbe {
    /** The glass under the Media panel (0 to 1), as the draw pass reads it. */
    var glass: (() -> Float)? = null

    /** The alpha each body page draws at, by the page's (kind, state), as last composed. */
    val pageAlphas: MutableMap<CustomMediaSelection, State<Float>> = mutableMapOf()
}

internal val LocalCustomMediaMotionProbe = staticCompositionLocalOf<CustomMediaMotionProbe?> { null }

/**
 * The page under the top row: status line, gallery and footer. They swap when the TYPE changes (keyed by the (kind, state) pair, so a
 * search result or an apply inside one type never restarts it), ring-safe, with the pad held until the arriving body shows. The top row
 * above never takes part.
 *
 * L1/R1 is the swap tier's one-clock cross-fade ([mimirCrossSwapTransform], as the Settings sub-pages swap): the page left and the page
 * arriving are complementary at every frame, so the pane is never emptier than the overlap. It was the lead swap, which stood the old tiles
 * for the whole dissolve and left the pane near-empty at 130 ms. A pick from the Media panel is [behindGlass]: the
 * glass under the panel holds while the panel dissolves, so the swap is MADE behind it, a cut, as the Library's OPEN WITH names the new
 * emulator at once. The first clear frame is the new type's, never the old one or an empty page.
 */
@Composable
internal fun CustomMediaBody(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    focusedKey: FocusKey?,
    compactSquare: Boolean,
    compactTypography: Boolean,
    narrowWidth: Boolean,
    onAction: (ControllerAction) -> Unit,
    modifier: Modifier = Modifier,
    behindGlass: Boolean = false,
) {
    // A cut is the cross-swap's 0 ms branch (reduced motion's own): both pages settle on the frame the type changes.
    val cut = mimirReducedMotion() || behindGlass
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = { mimirCrossSwapTransform(cut, fade = false) },
        label = "customMediaBody",
        contentKey = { it.kind to it.mediaState },
    ) { shown ->
        val pageAlpha = mimirCrossSwapFadeAlpha(cut)
        val gate = MimirSwapPageGate(pageAlpha)
        LocalCustomMediaMotionProbe.current?.pageAlphas?.set(shown.mediaSelection(), pageAlpha)
        val statusGap = if (compactSquare) {
            0.dp
        } else {
            (EnrichStatusToStripGapDp.dp - (if (compactTypography) MimirSpacing.xs else MimirSpacing.sm)).coerceAtLeast(0.dp)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The page's one column (customMediaColumnStart): the status line, strip, preview and footer start where the title does.
                .padding(start = customMediaColumnStart(compactTypography))
                .mimirRingSafeFade(pageAlpha)
                .then(gate),
            verticalArrangement = Arrangement.spacedBy(if (compactTypography) MimirSpacing.xs else MimirSpacing.sm),
        ) {
            EnrichStatusLine(shown, compactSquare)
            EnrichGallery(
                authority = authority,
                state = shown,
                focusedKey = focusedKey,
                compactSquare = compactSquare,
                onAction = onAction,
                onExpandPreview = { onAction(ControllerAction.SYSTEM_SELECT) },
                cueSeatDp = customMediaCueSeatDp(compactTypography),
                modifier = Modifier
                    .fillMaxWidth()
                    // The lit tile's halo reaches past its band: the status line and the strip stand EnrichStatusToStripGapDp apart in all, the
                    // column's own row gap included, so the halo stays off the line's descenders. The gap comes out of the band's minimum, not on top
                    // of it, so the certified height budget holds; the compact page, whose height is all spent, keeps its row gap.
                    .padding(top = statusGap)
                    .heightIn(min = homeCustomEnrichGalleryMinHeightDp(shown.kind, compactSquare).dp - statusGap)
                    .weight(1f),
            )
            EnrichFooter(
                authority = authority,
                state = shown,
                focusedKey = focusedKey,
                compactTypography = compactTypography,
                narrowWidth = narrowWidth,
                onAction = onAction,
                startInset = customMediaFooterInset(compactTypography),
            )
        }
    }
}

/**
 * The Media panel and the chips' value panel, in the hub's glass grammar ([ActivityChoicePicker]) on this page's own
 * authority: a scrim over the page and a glass panel with an eyebrow and the rows. The pad walks the rows through the
 * reducer (Up, Down, A, B); a tap on a row focuses it and confirms, a tap on the scrim is B.
 */
@Composable
internal fun CustomMediaPanels(
    authority: FocusAuthority,
    state: HomeCustomEnrichState,
    renderedFilter: HomeCustomEnrichFilter?,
    focusedKey: FocusKey?,
    onAction: (ControllerAction) -> Unit,
) {
    val reducedMotion = mimirReducedMotion()
    val rowTarget: @Composable (ActivityPickerRow) -> Modifier = { row -> customMediaPanelRowTarget(authority, row.padKey) }
    AnimatedVisibility(
        visible = state.mediaPanelOpen,
        enter = fadeIn(mimirSheetSpec(reducedMotion)),
        exit = fadeOut(mimirSheetSpec(reducedMotion)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val rows = state.mediaTypes.mapIndexed { index, type ->
            val optionKey = homeCustomEnrichMediaOptionKey(state, index)
            val selected = type.selection == state.mediaSelection()
            ActivityPickerRow(
                padKey = optionKey,
                text = type.label,
                description = activityFilterChipDescription(type.label, selected),
                selected = selected,
                tag = "custom-media-option-$index",
                actionLabel = "show ${type.label}",
                onPick = {
                    authority.requestFocus(optionKey)
                    onAction(ControllerAction.CONFIRM)
                },
            )
        }
        ActivityChoicePicker(
            eyebrow = "ᛗ · MEDIA",
            eyebrowTag = "custom-media-media-eyebrow",
            scrimTag = "custom-media-media-scrim",
            panelTag = CustomMediaMediaPanelTag,
            rowsTag = "custom-media-media-rows",
            closeLabel = "Close the list",
            rows = rows,
            onDismiss = { onAction(ControllerAction.CANCEL) },
            glass = { CustomMediaPanelScrim },
            reducedMotion = reducedMotion,
            rowTarget = rowTarget,
            rowsEdgeAir = MimirFocusTileRingReach,
            litKey = focusedKey,
            opaqueGround = true,
        )
    }
    AnimatedVisibility(
        visible = state.openFilterKey != null,
        enter = fadeIn(mimirSheetSpec(reducedMotion)),
        exit = fadeOut(mimirSheetSpec(reducedMotion)),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (renderedFilter != null) {
            val rows = renderedFilter.values.indices.map { index ->
                val optionKey = homeCustomEnrichFilterOptionKey(state, renderedFilter, index)
                val selected = index == renderedFilter.selectedIndex
                val shown = renderedFilter.copy(selectedIndex = index).selectedDisplay
                ActivityPickerRow(
                    padKey = optionKey,
                    text = shown,
                    description = activityFilterChipDescription(shown, selected),
                    selected = selected,
                    tag = "custom-media-filter-option-$index",
                    actionLabel = "use $shown",
                    onPick = {
                        authority.requestFocus(optionKey)
                        onAction(ControllerAction.CONFIRM)
                    },
                )
            }
            ActivityChoicePicker(
                eyebrow = "ᛗ · ${renderedFilter.label.uppercase()}",
                eyebrowTag = "custom-media-filter-eyebrow",
                scrimTag = "custom-media-filter-scrim",
                panelTag = CustomMediaFilterPanelTag,
                rowsTag = "custom-media-filter-rows",
                closeLabel = "Close the list",
                rows = rows,
                onDismiss = { onAction(ControllerAction.CANCEL) },
                glass = { CustomMediaPanelScrim },
                reducedMotion = reducedMotion,
                rowTarget = rowTarget,
                rowsEdgeAir = MimirFocusTileRingReach,
                litKey = focusedKey,
                opaqueGround = true,
            )
        }
    }
    // The Filters panel: the folded chips as rows ("Source · Both"); a row opens that chip's own value panel above.
    AnimatedVisibility(
        visible = state.filtersPanelOpen,
        enter = fadeIn(mimirSheetSpec(reducedMotion)),
        exit = fadeOut(mimirSheetSpec(reducedMotion)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val rows = state.visibleFilters().mapIndexed { index, filter ->
            val optionKey = homeCustomEnrichFiltersOptionKey(state, index)
            val text = "${filter.label} · ${filter.selectedDisplay}"
            ActivityPickerRow(
                padKey = optionKey,
                text = text,
                description = "${filter.label}, ${filter.selectedDisplay}",
                selected = false,
                tag = "custom-media-filters-option-$index",
                actionLabel = "choose ${filter.label.lowercase()}",
                onPick = {
                    authority.requestFocus(optionKey)
                    onAction(ControllerAction.CONFIRM)
                },
            )
        }
        ActivityChoicePicker(
            eyebrow = "ᛗ · FILTERS",
            eyebrowTag = "custom-media-filters-eyebrow",
            scrimTag = "custom-media-filters-scrim",
            panelTag = CustomMediaFiltersPanelTag,
            rowsTag = "custom-media-filters-rows",
            closeLabel = "Close the list",
            rows = rows,
            onDismiss = { onAction(ControllerAction.CANCEL) },
            glass = { CustomMediaPanelScrim },
            reducedMotion = reducedMotion,
            rowTarget = rowTarget,
            rowsEdgeAir = MimirFocusTileRingReach,
            litKey = focusedKey,
            opaqueGround = true,
        )
    }
}
