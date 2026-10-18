package com.gruning.mimir.rewrite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirGlass
import com.gruning.mimir.rewrite.core.ui.MimirGlassScrim
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSearchGlyph
import com.gruning.mimir.rewrite.core.ui.MimirSearchGlyphSize
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.PlatformConsoleArt
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirGlassPanelEntry
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassPanelProgress

internal const val AchievementFilterHeaderTag = "achievement-filter-header"
internal const val AchievementFilterSearchTag = "achievement-filter-search"
internal const val AchievementFilterSearchClearTag = "achievement-filter-search-clear"

/** The filled dot on the collapsed search chip that says a query is set. */
internal const val AchievementFilterSearchDotTag = "achievement-filter-search-dot"

/** The console's line-art on its chip, shown only when the console's name has no room to stand on one line. */
internal const val AchievementFilterConsoleGlyphTag = "achievement-filter-console-glyph"
internal const val AchievementFilterConsoleTag = "achievement-filter-console"
internal const val AchievementFilterConsoleListTag = "achievement-filter-console-list"

/** The "Show: All" chip: what the page lists (All, In progress, Beaten, Completed, Mastered, Recent), chosen from the same glass panel the console chip opens. */
internal const val AchievementFilterShowTag = "achievement-filter-show"
internal const val AchievementFilterShowListTag = "achievement-filter-show-list"
internal const val AchievementFilterShowScrimTag = "achievement-filter-show-scrim"
internal const val AchievementFilterShowEyebrowTag = "achievement-filter-show-eyebrow"
internal const val AchievementFilterShowRowsTag = "achievement-filter-show-rows"

/** A chip's visible plate (inside its 48 dp target): the height the Back pill's plate matches. */
internal const val AchievementFilterChipPlateTag = "achievement-filter-chip-plate"

/** The search pill's visible plate (inside its 48 dp target), at the chips' plate height. */
internal const val AchievementFilterSearchPlateTag = "achievement-filter-search-plate"
internal const val AchievementFilterCountTag = "achievement-filter-count"
internal const val AchievementFilterEmptyTag = "achievement-filter-empty"
internal const val AchievementFilterClearTag = "achievement-filter-clear"
internal const val AchievementFilterKeyboardTag = "achievement-filter-keyboard"

internal fun achievementFilterShowItemTag(status: ActivityAchievementStatus): String = "achievement-filter-show-item-${status.name.lowercase()}"

internal fun achievementFilterConsoleItemTag(name: String?): String = "achievement-filter-console-item-${name ?: "all"}"

/** The few games below which a filter has nothing to choose between: the page then keeps its old layout and shows no header. */
internal const val ACTIVITY_FILTER_MIN_GAMES = 2

/** A chip says its state with its name: "In progress, selected". An unselected chip says only its name. */
internal fun activityFilterChipDescription(label: String, selected: Boolean): String = if (selected) "$label, selected" else label

/** The search pill, for a screen reader: "Search achievements" and, once something is typed, ", <query>". */
internal fun activitySearchDescription(query: String): String = if (query.isBlank()) "Search achievements" else "Search achievements, $query"

/** The Show chip's words: "Show: All ▾" while nothing narrows (it says what the control is), the chosen name alone ("In progress ▾") once something does: the shorter of the two. */
internal fun activityShowChipText(status: ActivityAchievementStatus): String = if (status == ActivityAchievementStatus.ALL) "Show: All ▾" else "${status.label} ▾"

internal fun activityShowChipDescription(status: ActivityAchievementStatus): String = "Show, ${status.label}"

internal fun activityConsoleChipDescription(console: String?): String = "Console, ${console ?: "all consoles"}"

/** One row of the console list, for a screen reader: the console's name as the player reads it ([display], RetroAchievements' own by default), its games, and whether it is the chosen one. */
internal fun activityConsoleItemDescription(entry: ActivityConsoleCount, selected: Boolean, display: String = entry.name): String =
    activityFilterChipDescription("$display, ${entry.count} ${if (entry.count == 1) "game" else "games"}", selected)

/**
 * One etched chip: a 48 dp touch target (the hit box is the whole of it) around a smaller metal plate, warmed when selected. A press shows through the
 * Bottom's one press response; the tap goes through [mimirActivatable], so a screen reader's click and a finger's tap are one path. Nothing here takes focus.
 *
 * The label is ONE line, never wrapped and never ellipsized (every plate in the row is the same height). A chip that may not have the room for it
 * ([fallbackGlyphId], the console chip) measures the label against the room it is given: when it does not fit on one line the chip shows that platform's line-art (the
 * lattice's glyph) and [fallbackText] in its place, and the full name stays in [description].
 */
@Composable
internal fun ActivityFilterChip(
    text: String,
    description: String,
    actionLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackGlyphId: String? = null,
    fallbackText: String = "▾",
    /** Applied to the PLATE, before its etched surface: a ring that must hug the metal (not the 48 dp box around it) goes here. */
    plateModifier: Modifier = Modifier,
    /** True while [plateModifier]'s ring is lit: the plate hands its rim to it. */
    plateFocused: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    val color = if (selected) palette.accentBright else palette.materials.hero.onCover
    Box(
        modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .mimirActivatable(label = actionLabel, contentDescription = description, interactionSource = source, onActivate = onClick)
            .mimirPressResponse(source),
        contentAlignment = Alignment.Center,
    ) {
        if (fallbackGlyphId == null) {
            ActivityChipPlate(selected, plateModifier, plateFocused) { ActivityChipLabel(text, color) }
        } else {
            BoxWithConstraints {
                val measurer = rememberTextMeasurer()
                val wanted = remember(text, measurer) { measurer.measure(text, ActivityChipLabelStyle, maxLines = 1, softWrap = false).size.width }
                val padding = with(LocalDensity.current) { (MimirSpacing.md * 2).roundToPx() }
                val oneLine = !constraints.hasBoundedWidth || wanted + padding <= constraints.maxWidth
                // The glyph is as tall as a line of the label, so the plate is the height of every other plate in the row.
                val lineHeight = rememberActivityChipPlateHeight() - MimirSpacing.sm * 2
                ActivityChipPlate(selected, plateModifier, plateFocused) {
                    if (oneLine) {
                        ActivityChipLabel(text, color)
                    } else {
                        PlatformConsoleArt(
                            platformIdValue = fallbackGlyphId,
                            tint = color,
                            modifier = Modifier.size(lineHeight).testTag(AchievementFilterConsoleGlyphTag),
                        )
                        ActivityChipLabel(fallbackText, color)
                    }
                }
            }
        }
    }
}

/** A chip's metal plate: the label (or the glyph and the label) on one line between the chip's padding. */
@Composable
private fun ActivityChipPlate(selected: Boolean, plateModifier: Modifier, plateFocused: Boolean, content: @Composable RowScope.() -> Unit) {
    val palette = LocalMimirPalette.current
    Row(
        plateModifier
            .testTag(AchievementFilterChipPlateTag)
            .mimirEtchedChipSurface(palette, warmed = selected, focused = plateFocused, edgeFlush = true)
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One line, never wrapped and never ellipsized: a label that cannot fit is the caller's to replace, not this text's to cut. */
@Composable
private fun ActivityChipLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MimirTypeScale.SupportCompact,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/**
 * The search pill: a 48 dp touch target around an etched plate at the filter chips' height, so the Back pill, the search and the chips read as one row of equal plates
 * around compact content: a drawn magnifier and, only when the room allows it, the word "Search";
 * a query that filters takes the words' place, with a clear mark that is a target of its own. It opens the Mimir keyboard.
 */
@Composable
internal fun ActivitySearchPill(
    query: String,
    active: Boolean,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** Applied to the PLATE, before its etched surface (see [ActivityFilterChip]). */
    plateModifier: Modifier = Modifier,
    /** True while [plateModifier]'s ring is lit: the plate hands its rim to it. */
    plateFocused: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    val plateHeight = rememberActivityChipPlateHeight()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val wordWidth = remember(measurer, density) { with(density) { measurer.measure("Search", ActivityChipLabelStyle, maxLines = 1).size.width.toDp() } }
    BoxWithConstraints(modifier.heightIn(min = MimirSpacing.minTarget).widthIn(min = MimirSpacing.minTarget), contentAlignment = Alignment.CenterStart) {
        val hasQuery = query.isNotBlank()
        // The word is spelled out only when the glyph, the word and the plate's padding all fit; otherwise the glyph stands alone and nothing is cut.
        val showWord = !hasQuery && maxWidth >= wordWidth + ActivitySearchGlyphSize + MimirSpacing.xs + MimirSpacing.md * 2
        // The plate only paints: the 48 dp boxes drawn over it are the controls, so the hit areas are the whole of the pill and no visible size is spent on them.
        Box(
            Modifier
                .fillMaxWidth()
                .height(plateHeight)
                .testTag(AchievementFilterSearchPlateTag)
                .then(plateModifier)
                .mimirEtchedChipSurface(palette, warmed = active, focused = plateFocused, edgeFlush = true),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = MimirSpacing.minTarget)
                    .mimirActivatable(
                        label = "search achievements",
                        contentDescription = activitySearchDescription(query),
                        interactionSource = source,
                        onActivate = onOpen,
                    )
                    .mimirPressResponse(source)
                    .padding(horizontal = if (hasQuery) MimirSpacing.md else MimirSpacing.sm)
                    .testTag(AchievementFilterSearchTag),
                contentAlignment = if (hasQuery || showWord) Alignment.CenterStart else Alignment.Center,
            ) {
                if (hasQuery) {
                    Text(
                        text = query,
                        style = MimirTypeScale.Support,
                        color = palette.materials.hero.onCover,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        ActivitySearchGlyph(color = if (active) palette.accentBright else palette.textMuted)
                        if (showWord) {
                            Text(
                                text = "Search",
                                style = ActivityChipLabelStyle,
                                color = palette.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            ActivitySearchClear(visible = hasQuery, onClear = onClear)
        }
    }
}

/**
 * The search, collapsed ( while a console is picked, so the console's whole name has the room): an etched chip at the filter
 * chips' plate height holding only the magnifier, inside the same 48 dp target. A tap opens the search as the pill's does. With a query set the chip carries a filled dot, so
 * the query is known to be there; the query's own text is never cut into a space that cannot hold it (it is on the keyboard's pill when the search opens).
 */
@Composable
internal fun ActivitySearchChip(
    query: String,
    active: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Applied to the PLATE, before its etched surface (see [ActivityFilterChip]). */
    plateModifier: Modifier = Modifier,
    /** True while [plateModifier]'s ring is lit: the plate hands its rim to it. */
    plateFocused: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    val plateHeight = rememberActivityChipPlateHeight()
    val hasQuery = query.isNotBlank()
    Box(
        modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .mimirActivatable(
                label = "search achievements",
                contentDescription = activitySearchDescription(query),
                interactionSource = source,
                onActivate = onOpen,
            )
            .mimirPressResponse(source)
            .testTag(AchievementFilterSearchTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = ActivitySearchGlyphSize + MimirSpacing.md * 2, height = plateHeight)
                .testTag(AchievementFilterSearchPlateTag)
                .then(plateModifier)
                .mimirEtchedChipSurface(palette, warmed = active || hasQuery, focused = plateFocused, edgeFlush = true),
            contentAlignment = Alignment.Center,
        ) {
            ActivitySearchGlyph(color = if (active || hasQuery) palette.accentBright else palette.textMuted)
            if (hasQuery) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = MimirSpacing.xs, end = MimirSpacing.xs)
                        .size(ActivitySearchDotSize)
                        .background(palette.accentBright, androidx.compose.foundation.shape.CircleShape)
                        .testTag(AchievementFilterSearchDotTag),
                )
            }
        }
    }
}

/** The "a query is set" dot on the collapsed search chip. */
private val ActivitySearchDotSize = 7.dp

/** The magnifier's square, a drawn mark so no font has to carry the glyph: core-ui's, the one size every search control draws it at (the gamelist's search bar too). */
internal val ActivitySearchGlyphSize = MimirSearchGlyphSize

@Composable
internal fun ActivitySearchGlyph(color: androidx.compose.ui.graphics.Color) = MimirSearchGlyph(color = color)

/**
 * The height of a filter chip's plate: one line of the chip's label (its style and weight) and the chip's vertical padding twice. Measured rather than assumed so a
 * plate drawn for another control (the search pill) is the chip's height at every font scale, not a number that drifts when the type scale does.
 */
@Composable
internal fun rememberActivityChipPlateHeight(): androidx.compose.ui.unit.Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        val line = measurer.measure("Ag", ActivityChipLabelStyle, maxLines = 1).size.height
        with(density) { line.toDp() } + MimirSpacing.sm * 2
    }
}

/** A filter chip's label style (the chip adds its own colour and ellipsis). */
internal val ActivityChipLabelStyle = MimirTypeScale.SupportCompact.copy(fontWeight = FontWeight.SemiBold)

@Composable
private fun ActivitySearchClear(visible: Boolean, onClear: () -> Unit) {
    if (!visible) return
    val palette = LocalMimirPalette.current
    Box(
        Modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .mimirActivatable(label = "clear search", contentDescription = "Clear search", onActivate = onClear)
            .testTag(AchievementFilterSearchClearTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✕", style = MimirTypeScale.Support, color = palette.accentBright, maxLines = 1)
    }
}

/**
 * The filter controls of the page's ONE top row, to the right of the Back pill: the short search, the Show chip and the console
 * chip. Show lists what the page shows (All, In progress, Beaten, Completed, Mastered, Recent) in the glass panel the console chip opens, so six status chips and a sort button
 * are one chip. Every plate is one line at one height. With a console picked the search is only its magnifier, and the console chip names the console as the lattice does (or,
 * when that name has no room, draws the lattice's glyph for it): the row never runs past its end.
 */
@Composable
internal fun RowScope.ActivityFilterControls(
    filter: ActivityAchievementFilter,
    keyboardOpen: Boolean,
    onOpenSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onShow: () -> Unit,
    onConsole: () -> Unit,
    /** How the chosen console is called and drawn (see [ActivityConsoleLook]); null while none is chosen. */
    consoleLook: ActivityConsoleLook? = null,
) {
    Spacer(Modifier.width(MimirSpacing.sm))
    val searchLit = activityPadFocused(ActivityPadKeys.Search)
    val showLit = activityPadFocused(ActivityPadKeys.Show)
    val consoleLit = activityPadFocused(ActivityPadKeys.Console)
    if (filter.console == null) {
        ActivitySearchPill(
            query = filter.query,
            active = keyboardOpen || filter.query.isNotBlank(),
            onOpen = onOpenSearch,
            onClear = onClearSearch,
            // The 48 dp box registers with the pad; the ring is on the PLATE inside it (see [activityPadFocused]), so it hugs the metal.
            modifier = Modifier.weight(1f).activityPadStop(ActivityPadKeys.Search, revealOutset = MimirFocusTileRingReach),
            plateModifier = Modifier.mimirSelectionRing(focused = searchLit, identity = ActivityPadKeys.Search, shape = MimirRingShape.Pill, edgeFlush = true),
            plateFocused = searchLit,
        )
    } else {
        // A console is picked: the name needs the room, so the search is only its magnifier (a tap still opens it).
        ActivitySearchChip(
            query = filter.query,
            active = keyboardOpen,
            onOpen = onOpenSearch,
            modifier = Modifier.activityPadStop(ActivityPadKeys.Search, revealOutset = MimirFocusTileRingReach),
            plateModifier = Modifier.mimirSelectionRing(focused = searchLit, identity = ActivityPadKeys.Search, shape = MimirRingShape.Pill, edgeFlush = true),
            plateFocused = searchLit,
        )
    }
    Spacer(Modifier.width(MimirSpacing.xs))
    ActivityFilterChip(
        text = activityShowChipText(filter.status),
        description = activityShowChipDescription(filter.status),
        actionLabel = "choose what to show",
        selected = filter.status != ActivityAchievementStatus.ALL,
        onClick = onShow,
        modifier = Modifier.activityPadStop(ActivityPadKeys.Show, revealOutset = MimirFocusTileRingReach).testTag(AchievementFilterShowTag),
        plateModifier = Modifier.mimirSelectionRing(focused = showLit, identity = ActivityPadKeys.Show, shape = MimirRingShape.Pill, edgeFlush = true),
        plateFocused = showLit,
    )
    Spacer(Modifier.width(MimirSpacing.xs))
    val consoleName = consoleLook?.display ?: filter.console
    ActivityFilterChip(
        text = "${consoleName ?: "Console"} ▾",
        description = activityConsoleChipDescription(consoleName),
        actionLabel = "choose a console",
        selected = filter.console != null,
        onClick = onConsole,
        modifier = Modifier
            .activityPadStop(ActivityPadKeys.Console, revealOutset = MimirFocusTileRingReach)
            .testTag(AchievementFilterConsoleTag),
        plateModifier = Modifier.mimirSelectionRing(focused = consoleLit, identity = ActivityPadKeys.Console, shape = MimirRingShape.Pill, edgeFlush = true),
        plateFocused = consoleLit,
        // One line, whatever the name: when it has no room the chip draws the platform's glyph and the chevron, and the full name is what a screen reader hears.
        fallbackGlyphId = if (filter.console != null) consoleLook?.artId ?: "console" else null,
    )
}

/** The result count, live for a screen reader, and (beside it, when there is more than one page) where the pager stands. */
@Composable
internal fun ActivityFilterCountRow(
    countText: String,
    pages: Int,
    current: () -> Int,
    modifier: Modifier = Modifier,
    /** The page's recent-unlocks strip, between the count and the page position; empty adds nothing to the row (the count keeps all the room it had). */
    recent: List<ActivityRecentUnlock> = emptyList(),
    badgeSource: ActivityBadgeSource = NoActivityBadgeArt,
    onOpenRecent: (ActivityRecentUnlock) -> Unit = {},
) {
    val palette = LocalMimirPalette.current
    // The count row is text only: the band an "Achievement earned" toast stands in on this page. With the recent-unlocks strip in it (tiles that open a card) the band is the
    // count's words alone, so the toast never stands over a tile.
    val rowBand = if (recent.isEmpty()) Modifier.achievementToastBand(AchievementToastVertical.Top) else Modifier
    val countBand = if (recent.isEmpty()) Modifier else Modifier.achievementToastBand(AchievementToastVertical.Top)
    Row(modifier.then(rowBand), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = countText,
            style = MimirTypeScale.Support,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(countBand)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(AchievementFilterCountTag),
        )
        // The strip takes two shares to the count's one, so the count keeps a third of what the page position leaves; the tiles scroll when they do not fit.
        if (recent.isNotEmpty()) {
            Spacer(Modifier.width(MimirSpacing.sm))
            ActivityRecentStrip(unlocks = recent, badgeSource = badgeSource, onOpen = onOpenRecent, modifier = Modifier.weight(2f))
            Spacer(Modifier.width(MimirSpacing.sm))
        }
        ActivityPagePosition(pages = pages, current = current)
    }
}

/**
 * Where the list stands, as a place in a VERTICAL list: "Game 3 of 12". The cards stack top to bottom and a swipe sideways goes nowhere, so neither the words nor the
 * mark may suggest paging: the row of page runes this replaces read as "the next card is to the right" . It keeps the runes' tag, and says the same
 * words to a screen reader.
 */
@Composable
internal fun ActivityPagePosition(pages: Int, current: () -> Int, modifier: Modifier = Modifier) {
    if (pages <= 1) return
    val palette = LocalMimirPalette.current
    val text = activityPositionText(current(), pages)
    Text(
        text = text,
        style = MimirTypeScale.MicroStatus,
        color = palette.accentBright,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag(AchievementHubRunesTag).semantics { contentDescription = text },
    )
}

/** "Game 3 of 12" for the card at zero-based [page]: a place in the list, in words that mean down the list and never sideways. */
internal fun activityPositionText(page: Int, pages: Int): String = "Game ${page + 1} of $pages"

internal const val AchievementFilterConsoleScrimTag = "achievement-filter-console-scrim"
internal const val AchievementFilterConsoleEyebrowTag = "achievement-filter-console-eyebrow"
internal const val AchievementFilterConsoleRowsTag = "achievement-filter-console-rows"

internal const val ActivityConsolePickerEyebrow = "ᛗ · CONSOLE"

internal const val ActivityShowPickerEyebrow = "ᛗ · SHOW"

/** One row of a picker: the pad's key for it, its words, whether it is the chosen one, and what a tap on it does. */
internal data class ActivityPickerRow(
    val padKey: FocusKey,
    val text: String,
    val description: String,
    val selected: Boolean,
    val tag: String,
    val actionLabel: String,
    val onPick: () -> Unit,
)

/**
 * A picker, in the Bottom's glass grammar (the same pieces as its quick-launch picker and its choice dialog): the page behind it blurs and darkens on the
 * house clock ([glass], the scene's ramp, which the hub also feeds to `mimirGlassBackdropProgress` on the page), and a glass panel arrives over it with an eyebrow and
 * the list. The list SCROLLS: the panel is as tall as its rows up to the window less a margin, so nothing is ever cut at any font size. A tap on the scrim, anywhere
 * outside the panel, is [onDismiss]; a tap inside the panel that lands on nothing is absorbed. The pad walks the rows through the page's own authority (see
 * [ActivityPickerRows]); B is the page's modal rule, which closes it and gives focus back to the chip that opened it.
 */
@Composable
internal fun ActivityChoicePicker(
    eyebrow: String,
    eyebrowTag: String,
    scrimTag: String,
    panelTag: String,
    rowsTag: String,
    closeLabel: String,
    rows: List<ActivityPickerRow>,
    onDismiss: () -> Unit,
    glass: () -> Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    /**
     * A host with no Activity pad of its own (the Customize media page, the earlier update) lends the focus stop and ring for a row from
     * its own authority; null keeps the hub's pad walk, which is what the hub passes.
     */
    rowTarget: (@Composable (ActivityPickerRow) -> Modifier)? = null,
    /**
     * Air above the first row and below the last, inside the scrolling list (which clips): the lit row's ring and halo reach past the
     * row, and without it they are cut flat at the list's edge. 0 keeps the hub's list as it was.
     */
    rowsEdgeAir: Dp = 0.dp,
    /**
     * The row the host has lit, when the host is not the hub (the hub's own pad says it): the Customize media page lends its focus
     * stops from its own authority, so the list has to be told which row is lit to keep it whole in view. Null reads the hub's pad.
     */
    litKey: FocusKey? = null,
    /**
     * Whether the panel's ground is OPAQUE: the glass body is the theme's own cover tone at 0.78 / 0.88 alpha, which lets the page behind read through the
     * panel's header and rows (a chip's words faintly under the eyebrow). True lays that same cover tone at full alpha under the glass, so the panel looks as
     * it did and nothing behind it can be read (as on the Bottom's ᛗ card). False keeps the hub's glass as it was.
     */
    opaqueGround: Boolean = false,
    /**
     * Whether the picker draws its own scrim (darkening on [glass]). True keeps the Customize media page's picker as it was. The hub passes false and draws ONE scrim at page level on
     * the same glass, so the scrim outlives the panel's own removal and clears with the blur; the full-size tap target that dismisses stays either way.
     */
    drawScrim: Boolean = true,
) {
    val panelProgress = rememberMimirGlassPanelProgress(visible = true, reducedMotion = reducedMotion, holdPad = false)
    val opaqueTone = LocalMimirPalette.current.materials.hero.coverMiddle
    // A host that lends its own lit key (the Customize media page) lists up to seven rows, and its panel takes the window: a margin of 4 dp, a padding of 8 dp, no gap under the
    // eyebrow (the list's own air stands there), so seven whole rows and a lit ring stand in the 405 dp the page leaves it at font 1.3 (they did not at the hub's 24 / 16 / 8 dp:
    // the last row was half cut, or the first, sweep 10-11). The hub (no lit key) keeps its geometry byte for byte.
    val wholeRows = litKey != null
    val panelPadding = if (wholeRows) ActivityPickerWholeRowsPadding else MimirSpacing.lg
    val sideInset = if (wholeRows) MimirSpacing.lg - ActivityPickerWholeRowsPadding else 0.dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelMaxHeight = (maxHeight - if (wholeRows) ActivityPickerWholeRowsMargin else MimirSpacing.xl).coerceAtLeast(MimirSpacing.minTarget)
        val scrimModifier = Modifier
            .mimirActivatable(label = closeLabel.lowercase(), contentDescription = closeLabel, onActivate = onDismiss)
            .testTag(scrimTag)
        if (drawScrim) {
            MimirGlassScrim(progress = glass, modifier = scrimModifier)
        } else {
            Box(scrimModifier.fillMaxSize())
        }
        MimirLiquidGlassPanel(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .heightIn(max = panelMaxHeight)
                .mimirGlassPanelEntry(panelProgress)
                .then(if (opaqueGround) Modifier.background(opaqueTone, RoundedCornerShape(MimirGlass.panelRadius)) else Modifier)
                // Absorbs a tap that lands on the panel's own glass, so it never reaches the dismissing scrim beneath.
                .pointerInput(Unit) { detectTapGestures { } }
                .testTag(panelTag),
            contentPadding = panelPadding,
            verticalArrangement = Arrangement.spacedBy(if (wholeRows) 0.dp else MimirSpacing.sm),
            gradientBody = false,
            radius = MimirGlass.panelRadius,
        ) {
            val palette = LocalMimirPalette.current
            MimirChromeText(
                text = eyebrow,
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = sideInset).testTag(eyebrowTag),
            )
            ActivityPickerRows(rows = rows, rowsTag = rowsTag, rowTarget = rowTarget, edgeAir = rowsEdgeAir, litKey = litKey, sideInset = sideInset)
        }
    }
}

/** The panel's margin from the window's edge, in total, and its padding, when the host lends its own lit key ([ActivityChoicePicker]'s `litKey`). */
private val ActivityPickerWholeRowsMargin = MimirSpacing.xs
private val ActivityPickerWholeRowsPadding = MimirSpacing.sm

/**
 * The console picker: "All consoles" and each console with its game count, exactly as RA names it ("PlayStation 2"), in the shared glass panel ([ActivityChoicePicker]).
 */
@Composable
internal fun ActivityConsolePicker(
    consoles: List<ActivityConsoleCount>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    glass: () -> Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    /** How each console is called (the lattice's name), by RetroAchievements' name; a console without an entry is called by RetroAchievements' own. */
    looks: Map<String, ActivityConsoleLook> = emptyMap(),
    /** See [ActivityChoicePicker]: false when the host draws the scrim itself. */
    drawScrim: Boolean = true,
) {
    val rows = remember(consoles, selected, onPick, looks) {
        buildList {
            add(
                ActivityPickerRow(
                    padKey = ActivityPadKeys.consoleItem(null),
                    text = "All consoles",
                    description = activityFilterChipDescription("All consoles", selected == null),
                    selected = selected == null,
                    tag = achievementFilterConsoleItemTag(null),
                    actionLabel = "show games from this console",
                    onPick = { onPick(null) },
                ),
            )
            consoles.forEach { entry ->
                val isSelected = entry.name == selected
                val display = looks[entry.name]?.display ?: entry.name
                add(
                    ActivityPickerRow(
                        padKey = ActivityPadKeys.consoleItem(entry.name),
                        text = "$display (${entry.count})",
                        description = activityConsoleItemDescription(entry, isSelected, display),
                        selected = isSelected,
                        tag = achievementFilterConsoleItemTag(entry.name),
                        actionLabel = "show games from this console",
                        onPick = { onPick(entry.name) },
                    ),
                )
            }
        }
    }
    ActivityChoicePicker(
        eyebrow = ActivityConsolePickerEyebrow,
        eyebrowTag = AchievementFilterConsoleEyebrowTag,
        scrimTag = AchievementFilterConsoleScrimTag,
        panelTag = AchievementFilterConsoleListTag,
        rowsTag = AchievementFilterConsoleRowsTag,
        closeLabel = "Close the console list",
        rows = rows,
        onDismiss = onDismiss,
        glass = glass,
        reducedMotion = reducedMotion,
        modifier = modifier,
        drawScrim = drawScrim,
    )
}

/** The Show picker: what the page lists, each of [ActivityAchievementStatus] by its name, in the shared glass panel ([ActivityChoicePicker]). */
@Composable
internal fun ActivityShowPicker(
    selected: ActivityAchievementStatus,
    onPick: (ActivityAchievementStatus) -> Unit,
    onDismiss: () -> Unit,
    glass: () -> Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    /** See [ActivityChoicePicker]: false when the host draws the scrim itself. */
    drawScrim: Boolean = true,
) {
    val rows = remember(selected, onPick) {
        ActivityAchievementStatus.entries.map { status ->
            ActivityPickerRow(
                padKey = ActivityPadKeys.showItem(status),
                text = status.label,
                description = activityFilterChipDescription(status.label, status == selected),
                selected = status == selected,
                tag = achievementFilterShowItemTag(status),
                actionLabel = "show ones that are ${status.label}",
                onPick = { onPick(status) },
            )
        }
    }
    ActivityChoicePicker(
        eyebrow = ActivityShowPickerEyebrow,
        eyebrowTag = AchievementFilterShowEyebrowTag,
        scrimTag = AchievementFilterShowScrimTag,
        panelTag = AchievementFilterShowListTag,
        rowsTag = AchievementFilterShowRowsTag,
        closeLabel = "Close the list",
        rows = rows,
        onDismiss = onDismiss,
        glass = glass,
        reducedMotion = reducedMotion,
        modifier = modifier,
        drawScrim = drawScrim,
    )
}

/**
 * A picker's rows: a lazy column that takes the panel's remaining height (so a long list scrolls inside it and a short one is as tall as its rows). The pad walks
 * the whole list, composed or not: a lit row the lazy list has not composed is scrolled to, and a lit row that shows only in part is scrolled until it is whole,
 * with [edgeAir] clear of the list's edge so its focus ring is whole too (a row the list's clip cuts is a ring cut flat).
 *
 * A host that lends its own lit key (the Customize media page, [litKey]) gets whole rows at rest as well ([activityPickerListFit]): a list that fits keeps [edgeAir] and
 * does not scroll; one that must scroll is a whole number of rows tall and keeps a whole row, not [edgeAir], clear above and below the lit row, so the list only ever rests
 * with its rows on its edges, and its blank end bands are whole rows too. The hub (no lit key) is untouched.
 */
@Composable
private fun ColumnScope.ActivityPickerRows(
    rows: List<ActivityPickerRow>,
    rowsTag: String,
    rowTarget: (@Composable (ActivityPickerRow) -> Modifier)? = null,
    edgeAir: Dp = 0.dp,
    litKey: FocusKey? = null,
    sideInset: Dp = 0.dp,
) {
    val listState = rememberLazyListState()
    if (litKey == null) {
        ActivityPickerLazyList(listState, rows, rowsTag, rowTarget, edgeAir, litKey, sideInset, Modifier.weight(1f, fill = false))
        return
    }
    val density = LocalDensity.current
    val minRowPx = with(density) { MimirSpacing.minTarget.roundToPx() }
    // A row is the target's 48 dp, or its words and padding when those are taller (a very large font): the first composed row says which, as the list laid it out.
    val firstRowPx by remember(listState) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0 } }
    BoxWithConstraints(Modifier.weight(1f, fill = false)) {
        val fit = activityPickerListFit(constraints.maxHeight, maxOf(minRowPx, firstRowPx), with(density) { edgeAir.roundToPx() }, rows.size)
        ActivityPickerLazyList(
            listState, rows, rowsTag, rowTarget,
            with(density) { fit.airPx.toDp() },
            litKey,
            sideInset,
            if (fit.heightPx != null) Modifier.height(with(density) { fit.heightPx.toDp() }) else Modifier,
        )
    }
}

@Composable
private fun ActivityPickerLazyList(
    listState: LazyListState,
    rows: List<ActivityPickerRow>,
    rowsTag: String,
    rowTarget: (@Composable (ActivityPickerRow) -> Modifier)?,
    air: Dp,
    litKey: FocusKey?,
    sideInset: Dp,
    modifier: Modifier,
) {
    val lit = litKey ?: rememberActivityPadLit()
    val litIndex = remember(rows, lit) { if (lit == null) -1 else rows.indexOfFirst { it.padKey == lit } }
    val airPx = with(LocalDensity.current) { air.toPx() }
    LaunchedEffect(litIndex, airPx) {
        if (litIndex < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == litIndex }
        when {
            item == null -> listState.scrollToItem(litIndex)
            // The part-visible scroll is the page's alone: the hub (no [litKey]) brings its own row into view with a glide of its own
            // (activityPadTarget's bring-into-view), and a second scroll here would cancel or replace it.
            litKey != null -> {
                val delta = activityPickerRevealDelta(item.offset, item.size, info.viewportStartOffset, info.viewportEndOffset, airPx)
                if (delta != 0f) listState.scrollBy(delta)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = sideInset).testTag(rowsTag),
        contentPadding = PaddingValues(vertical = air),
        // Snapped to the first row after the air, not the default's middle of the viewport: the middle is a whole row's edge only when the list holds an odd number of rows.
        flingBehavior = if (litKey != null) rememberSnapFlingBehavior(listState, SnapPosition.Start) else ScrollableDefaults.flingBehavior(),
    ) {
        items(rows, key = { it.padKey.value }) { row -> PickerListItem(row, rowTarget) }
    }
}

/**
 * How a picker whose host lends its lit key lays its list out in [room] px: [airPx] is the air at each end of the list and [heightPx] the height it is held to, or null to take
 * what its rows need. A list of [rows] rows of [rowPx] that fits with [ringAirPx] of air at each end does not scroll and keeps that air (the lit ring's reach). One that does not
 * fit is a whole number of rows tall, with a whole row of air at each end instead: a list scrolled by whole rows rests with a row on each of its edges, and a lit row's ring has a whole
 * neighbour (or the blank end band) beyond it, never a sliver of one. A room under three rows keeps the ring's air and the room. Pure, so a test drives it with any room.
 */
internal data class ActivityPickerListFit(val airPx: Int, val heightPx: Int?)

internal fun activityPickerListFit(room: Int, rowPx: Int, ringAirPx: Int, rows: Int): ActivityPickerListFit {
    if (rowPx <= 0 || rows <= 0 || rows.toLong() * rowPx + 2L * ringAirPx <= room) return ActivityPickerListFit(ringAirPx, null)
    val whole = room / rowPx
    return if (whole >= 3) ActivityPickerListFit(rowPx, whole * rowPx) else ActivityPickerListFit(ringAirPx, null)
}

/**
 * How far a lit row that shows only in part must scroll to stand whole with [airPx] clear of the list's edge: positive to bring a row up from
 * below the viewport's end, negative to bring one down from above its start, 0 when it is whole already. Offsets and sizes are the lazy list's
 * ([LazyListItemInfo], relative to the content start, the content padding area lying outside [viewportStart]..[viewportEnd]).
 */
internal fun activityPickerRevealDelta(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int, airPx: Float): Float = when {
    itemOffset + itemSize + airPx > viewportEnd -> itemOffset + itemSize + airPx - viewportEnd
    itemOffset - airPx < viewportStart -> itemOffset - airPx - viewportStart
    else -> 0f
}

@Composable
private fun PickerListItem(row: ActivityPickerRow, rowTarget: (@Composable (ActivityPickerRow) -> Modifier)? = null) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    Row(
        (rowTarget?.invoke(row) ?: Modifier.activityPadTarget(row.padKey, MimirRingShape.Row(MimirSpacing.xs), inset = MimirSpacing.xs))
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .mimirActivatable(label = row.actionLabel, contentDescription = row.description, interactionSource = source, onActivate = row.onPick)
            .mimirPressResponse(source)
            .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm)
            .testTag(row.tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.text,
            style = MimirTypeScale.Support,
            color = if (row.selected) palette.accentBright else palette.textPrimary,
            fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        // Drawn, not the font's U+2713 (which renders as a root sign on a handheld).
        if (row.selected) EnrichTileCheck(palette.accentBright)
    }
}

/** What the pager's place shows when the filter lets no game through: the plain fact and the one way out. The chip clears the filters. */
@Composable
internal fun ActivityFilterEmpty(onClear: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(palette.surfaceBase.copy(alpha = 0.94f))
            .padding(MimirSpacing.lg)
            .testTag(AchievementFilterEmptyTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "No games match", style = MimirTypeScale.Title, color = palette.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(MimirSpacing.xs))
        Text(
            text = "Try another title, status or console.",
            style = MimirTypeScale.Support,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MimirSpacing.sm))
        val clearLit = activityPadFocused(ActivityPadKeys.ClearFilters)
        ActivityFilterChip(
            text = "Clear filters",
            description = "Clear filters",
            actionLabel = "clear the filters",
            selected = true,
            onClick = onClear,
            modifier = Modifier.activityPadStop(ActivityPadKeys.ClearFilters, revealOutset = MimirFocusTileRingReach).testTag(AchievementFilterClearTag),
            plateModifier = Modifier.mimirSelectionRing(focused = clearLit, identity = ActivityPadKeys.ClearFilters, shape = MimirRingShape.Pill, edgeFlush = true),
            plateFocused = clearLit,
        )
    }
}
