package com.gruning.mimir.rewrite

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import kotlinx.coroutines.flow.first

/*
 * The Artist / Album browser's drawing : Artists > Albums > Tracks as a list of rows in the music panel's own row style (the switch row's: a 48 dp row, the
 * ring on the row's clip corner, the name over a muted line). One body for both surfaces: Main's panel passes the controller's focus (the ring on the row under
 * the cursor, B to go back, no back chip) and the Bottom's player passes touch (taller rows, no ring, an etched Back chip).
 */

/** Tags for the render tests. */
internal const val MusicBrowserListTestTag = "music/browser/list"
internal const val MusicBrowserRowTestTag = "music/browser/row/"
internal const val MusicBrowserBackTestTag = "music/browser/back"
internal const val MusicBrowserEmptyTestTag = "music/browser/empty"
internal const val MusicBrowserReadingTestTag = "music/browser/reading"
internal const val MusicBrowserContextTestTag = "music/browser/context"
internal const val MusicBrowserThumbTestTag = "music/browser/thumb/"

/** The Library chip's tag, for the render tests. */
internal const val MusicPanelBrowseChipTestTag = "music/panel/browse-chip"

/** The chip's word. */
internal const val MusicPanelBrowseLabel = "Library"

/** The row's thumbnail: a small square of the track's cover, or a plate with a note when there is none. */
internal val MusicBrowseThumbDp = 40.dp

/** A touch row is taller than Main's 48 dp, the Bottom's own comfortable target. */
internal val MusicBrowseTouchRowDp = 56.dp

/** The gap between two rows: [MimirSpacing.sm], the Utility list's row gap, which keeps the focused row's ring clear of the row beside it. */
internal val MusicBrowseRowGap = MimirSpacing.sm

/** The room a list keeps at its ends for the focus ring's glow: the ring's flare reaches about 12 dp past a row, and a list clips on its scroll axis. */
internal val MusicBrowseRingRoom = 14.dp

/** The browser page's height on Main's card: a stable seat, so the card does not change height as the level (and its row count) changes. */
internal const val MusicBrowsePageMainDp = 300

/**
 * The browser page. [ringFocused] is Main's: the controller holds focus on the list and the ring is on the row under the cursor ([MusicBrowseUi.selectedKey]);
 * [focusModifier] is the list's one focus registration. [touch] is the Bottom's: no ring, taller rows, the press response, and a Back chip (one level up; from the
 * top it closes the sheet the page is in). [onPress] takes a row's key (a tap selects it and acts, as A does).
 */
@Composable
internal fun MusicLibraryBrowserPage(
    ui: MusicBrowseUi,
    palette: MimirThemePalette,
    ringFocused: Boolean,
    onPress: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
    touch: Boolean = false,
    /** Applied to each control the finger can use: the Bottom keeps the workspace strip from panning under it. */
    controlModifier: Modifier = Modifier,
    /** The level word (Artists, Albums, Tracks) above the page: Main's card puts it in its own heading instead. */
    showEyebrow: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MusicBrowseHeader(ui, palette, touch, showEyebrow, onBack, controlModifier)
        when {
            ui.empty != null -> MimirChromeText(
                ui.empty,
                style = MimirTypeScale.ControlHintCompact,
                color = palette.textMuted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(MusicBrowserEmptyTestTag),
                textAlign = TextAlign.Center,
            )
            else -> {
                // A level starts at its top: the cursor effect below then brings the row the player came from back into view.
                val state = remember(ui.level) { LazyListState() }
                // The cursor stays in view: a row that scrolled out is brought back to the middle, one that is already on screen does not move the list.
                LaunchedEffect(ui.level, ui.selectedKey, ui.rows.size) {
                    val at = ui.rows.indexOfFirst { it.key == ui.selectedKey }
                    if (at < 0) return@LaunchedEffect
                    // Before the list's first layout there is no viewport to judge a row against: wait for it, or the cursor's row would be scrolled to the top.
                    snapshotFlow { state.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
                    val info = state.layoutInfo
                    val shown = info.visibleItemsInfo.firstOrNull { it.index == at }
                    val fits = shown != null && shown.offset >= info.viewportStartOffset && shown.offset + shown.size <= info.viewportEndOffset
                    if (!fits) {
                        val height = (shown?.size ?: info.visibleItemsInfo.firstOrNull()?.size ?: 0)
                        state.scrollToItem(at, scrollOffset = -((info.viewportEndOffset - info.viewportStartOffset - height) / 2).coerceAtLeast(0))
                    }
                }
                LazyColumn(
                    state = state,
                    modifier = focusModifier.fillMaxWidth().weight(1f, fill = false).testTag(MusicBrowserListTestTag),
                    // The ring's glow reaches about 12 dp past a row and a list clips on its scroll axis: room at both ends, so the first and last rows are never cut flat.
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = MusicBrowseRingRoom),
                    // The ring's inner and middle strokes reach 8 dp past a row (MimirSpacing.sm is what siblings need clear, at 0 gap): the Utility list's own row gap. A smaller one
                    // lets the focused row's halo run into the thumbnail of the next.
                    verticalArrangement = Arrangement.spacedBy(MusicBrowseRowGap),
                ) {
                    itemsIndexed(ui.rows, key = { _, row -> ui.level.name + "/" + row.key }) { _, row ->
                        MusicBrowseRowItem(
                            row = row,
                            level = ui.level,
                            selected = row.key == ui.selectedKey,
                            ringFocused = ringFocused,
                            palette = palette,
                            touch = touch,
                            onPress = { onPress(row.key) },
                            controlModifier = controlModifier,
                        )
                    }
                }
            }
        }
    }
}

/** The line(s) above the list: the Back chip (touch only), the level word (unless the host draws it), the artist or album it is inside, and the tag pass's progress. */
@Composable
private fun MusicBrowseHeader(ui: MusicBrowseUi, palette: MimirThemePalette, touch: Boolean, showEyebrow: Boolean, onBack: () -> Unit, controlModifier: Modifier) {
    if (!touch && !showEyebrow && ui.context == null && ui.contextSub == null && ui.reading == null) return
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (touch) MusicBrowseBackChip(palette = palette, onBack = onBack, modifier = controlModifier)
        Column(Modifier.weight(1f)) {
            if (showEyebrow) {
                MimirChromeText(ui.level.eyebrow, style = MimirTypeScale.RunicEyebrow, color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ui.context?.let { name ->
                MimirIntentText(
                    MimirTextIntent.Authored(name),
                    style = MimirTypeScale.MenuAction,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag(MusicBrowserContextTestTag),
                )
            }
            ui.contextSub?.let { sub ->
                MimirIntentText(
                    MimirTextIntent.Authored(sub),
                    style = MimirTypeScale.SupportCompact,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ui.reading?.let { line ->
                MimirChromeText(
                    line,
                    style = MimirTypeScale.ControlHintCompact,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag(MusicBrowserReadingTestTag),
                )
            }
        }
    }
}

/**
 * Back, the Bottom's only: an unselected etched chip like the filters (a back control is never a filled blob), in a 48 dp hit box with the
 * plate at the chips' height. Main has no such chip: B goes back there.
 */
@Composable
internal fun MusicBrowseBackChip(palette: MimirThemePalette, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val plateHeight = rememberActivityChipPlateHeight()
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .testTag(MusicBrowserBackTestTag)
            .mimirActivatable(label = "Back", contentDescription = "Back", interactionSource = source, onActivate = onBack)
            .mimirPressResponse(source),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(Modifier.height(plateHeight), palette = palette) {
            MimirChromeText("Back", style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(), maxLines = 1)
        }
    }
}

@Composable
private fun MusicBrowseRowItem(
    row: MusicBrowseRow,
    level: MusicBrowseLevel,
    selected: Boolean,
    ringFocused: Boolean,
    palette: MimirThemePalette,
    touch: Boolean,
    onPress: () -> Unit,
    controlModifier: Modifier,
) {
    val pressSource = remember { MutableInteractionSource() }
    val ringKey = "music-panel/browse/" + level.name.lowercase() + "/" + row.key
    val activation = Modifier.mimirActivatable(
        label = row.title,
        role = null,
        interactionSource = if (touch) pressSource else null,
        onActivate = onPress,
    )
    val seat = Modifier
        .fillMaxWidth()
        .heightIn(min = if (touch) MusicBrowseTouchRowDp else 48.dp)
        .testTag(MusicBrowserRowTestTag + row.key)
        .then(controlModifier)
    // Main's chain is the switch row's: the ring, the clip, then the press. The Bottom takes the press first and its response right after, so the whole row
    // draws in and its hit box stays whole; no ring there (the Bottom is touch-only).
    // The picked row (the artist or album the player's queue lives under; "All music" while there is no pick) wears the family's selected look: the ring's `active` plate,
    // a fill and a border in the accent, which the Settings rail gives its open category. Warm, because the accent is: it is the same plate, not a new style. Focus adds the
    // ring over it; on the Bottom (touch) there is no ring, only the plate.
    val accent = if (palette.usesBrightField) palette.materials.readableAccent else palette.accent
    val base = if (touch) {
        seat
            .then(activation)
            .mimirPressResponse(pressSource)
            .mimirSelectionRing(focused = false, identity = ringKey, shape = MimirRingShape.Row(UtilityRowCorner), active = row.picked, accent = accent)
            .clip(RoundedCornerShape(UtilityRowCorner))
    } else {
        seat
            .mimirSelectionRing(
                focused = ringFocused && selected,
                identity = ringKey,
                shape = MimirRingShape.Row(UtilityRowCorner),
                active = row.picked,
                accent = accent,
            )
            .clip(RoundedCornerShape(UtilityRowCorner))
            .then(activation)
    }
    val chain = base.semantics { this.selected = row.picked }
    Row(
        modifier = chain.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (level == MusicBrowseLevel.TRACKS) {
            MimirChromeText(
                row.number ?: "–",
                style = MimirTypeScale.MicroStatus,
                color = if (row.playing) palette.accentBright else palette.textMuted,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 28.dp),
                textAlign = TextAlign.End,
            )
        } else {
            MusicBrowseThumb(row, palette)
        }
        Column(Modifier.weight(1f)) {
            MimirIntentText(
                if (row.titleAuthored) MimirTextIntent.Authored(row.title) else MimirTextIntent.StaticChrome(row.title),
                style = MimirTypeScale.MenuAction,
                color = when {
                    row.playing -> palette.accentBright
                    row.picked -> accent
                    else -> palette.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.subtitle?.let { line ->
                MimirIntentText(
                    if (row.subtitleAuthored) MimirTextIntent.Authored(line) else MimirTextIntent.StaticChrome(line),
                    style = MimirTypeScale.SupportCompact,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            row.trailing != null -> MimirChromeText(
                row.trailing,
                style = MimirTypeScale.MicroStatus,
                color = if (row.playing) palette.accentBright else palette.textSecondary,
                maxLines = 1,
            )
            row.opens -> MusicBrowseChevron(if (selected && ringFocused) palette.accentBright else palette.textSecondary)
        }
    }
}

/** A thin right-pointing chevron, drawn: the row opens. A font glyph would shift with the font; this one is the same on every device. */
@Composable
private fun MusicBrowseChevron(color: Color) {
    Canvas(Modifier.size(width = 8.dp, height = 14.dp)) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val half = stroke.width / 2f
        val path = Path().apply {
            moveTo(half, half)
            lineTo(size.width - half, size.height / 2f)
            lineTo(half, size.height - half)
        }
        drawPath(path, color, style = stroke)
    }
}

/**
 * A row's thumbnail: the picture of [MusicBrowseRow.artTrackId] if there is one, else a plate with a note. Art that is known to exist is never replaced by the note
 * while it loads (the panel's art law): the plate stays empty until the picture fades in.
 */
@Composable
private fun MusicBrowseThumb(row: MusicBrowseRow, palette: MimirThemePalette, size: Dp = MusicBrowseThumbDp) {
    val art = if (row.artTrackId != null) rememberYourMusicArt(row.artTrackId, size) else YourMusicArtState.None
    val image = remember(art.bitmap) { art.bitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }
    val alpha by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(if (mimirReducedMotion()) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "music-browse-thumb",
    )
    Box(
        Modifier
            .size(size)
            .testTag(MusicBrowserThumbTestTag + row.key)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            )
        } else if (!art.loading) {
            // No picture: the plate with a drawn note. The book cover the player uses is made for a card-sized seat; at 40 dp its title wraps into nonsense.
            MusicBrowseNote(palette.textMuted.copy(alpha = 0.85f), Modifier.size(size / 2).clearAndSetSemantics { })
        }
    }
}

/** An eighth note, drawn: the same on every device, where a font glyph would shift with the font. */
@Composable
private fun MusicBrowseNote(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stem = w * 0.09f
        val headRadius = w * 0.2f
        val headCentre = Offset(w * 0.34f, h * 0.78f)
        drawCircle(color, radius = headRadius, center = headCentre)
        val stemX = headCentre.x + headRadius - stem / 2f
        drawLine(color, Offset(stemX, headCentre.y), Offset(stemX, h * 0.1f), strokeWidth = stem, cap = StrokeCap.Round)
        val flag = Path().apply {
            moveTo(stemX, h * 0.1f)
            quadraticBezierTo(w * 0.78f, h * 0.2f, w * 0.78f, h * 0.52f)
        }
        drawPath(flag, color, style = Stroke(width = stem, cap = StrokeCap.Round))
    }
}

/**
 * Your music's Library chip, at the right end of Main's card heading: the browser's way in. An unselected etched chip like the filters (never a filled pill or a tinted
 * blob) at the chips' plate height, the ring on the plate itself so one ring stands on one rim. [modifier] carries the controller's focus registration
 * ([MusicPanelControl.BROWSE]). The heading line has no room for a 48 dp box (the card is capped at the display's height and every dp of it is spoken for), so the layout
 * is the plate and no more: a finger still gets a 48 dp target, because Compose widens the touch bounds of every pressable to the platform's minimum touch target.
 */
@Composable
internal fun MusicPanelBrowseChip(
    focused: Boolean,
    palette: MimirThemePalette,
    modifier: Modifier,
    pressResponse: Boolean,
    onActivate: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val plateHeight = rememberActivityChipPlateHeight()
    Box(
        modifier
            .testTag(MusicPanelBrowseChipTestTag)
            .mimirActivatable(label = MusicPanelBrowseLabel, interactionSource = if (pressResponse) source else null, onActivate = onActivate)
            .then(if (pressResponse) Modifier.mimirPressResponse(source) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(
            modifier = Modifier
                .height(plateHeight)
                .mimirSelectionRing(focused = focused, identity = musicPanelFocusKey(MusicPanelControl.BROWSE).value, shape = MimirRingShape.Pill, edgeFlush = true),
            focused = focused,
            palette = palette,
            edgeFlush = true,
        ) {
            MimirChromeText(MusicPanelBrowseLabel, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(), maxLines = 1)
        }
    }
}
