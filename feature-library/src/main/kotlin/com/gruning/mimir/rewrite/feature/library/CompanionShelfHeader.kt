package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlate
import com.gruning.mimir.rewrite.core.ui.GlassMenuCapsule
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeader
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.PlainMenuSeparator
import com.gruning.mimir.rewrite.core.ui.PlatformConsoleArt
import com.gruning.mimir.rewrite.core.ui.mimirAppIconShape
import com.gruning.mimir.rewrite.core.ui.mimirArtLanded
import com.gruning.mimir.rewrite.core.ui.mimirArtWarmInMemory
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import com.gruning.mimir.rewrite.core.ui.rememberMimirColdArtReveal
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirReportPress
import com.gruning.mimir.rewrite.core.ui.rememberMimirArtWired
import com.gruning.mimir.rewrite.core.ui.rememberMimirFadeMount

/*
 * The platform shelf's header (slice L4, reworked in W2c): the Bottom's one compact header with a 48 dp back pill that names its target, the platform's own
 * icon in the seat the Mimir mark takes elsewhere, the platform's name (two lines, then an ellipsis) and game count, and ONE density chip ("4x2 v") that
 * opens a three-row popover. Nothing draws under any control: the band has no backdrop, and the icon has a seat of its own.
 *
 * Touch only, structurally (R9a): the pill, the chip and the popover's rows are plain tap detectors with button semantics. Nothing here registers on the
 * FocusAuthority, takes focus, or holds the process-wide pad gate. Motion: the popover's fade only (MimirMotion.OverlayMs, house easing, snapped under reduced
 * motion), on alpha. The chip's effect on the grid is the shelf's swap.
 */

/** The header band the compact header is centred in: the compact header's row, 6 dp gap and 3 dp rule, with room to spare at 1.0 and 1.3. */
internal const val CompanionShelfHeaderBandDp = 64f

/** The chip, the popover's rows and the back pill are the Bottom's 48 dp touch target. */
internal const val CompanionShelfTargetDp = 48f

/** The platform icon's seat, in the place the Mimir mark takes in the compact header: 40 dp whatever it holds, so the row never moves when art arrives. */
internal const val CompanionShelfIconSeatDp = 40f

/** The popover's width: wide enough for "4x2 . Standard" in a capsule at font scale 1.3, and the same at every scale so it does not jump. */
internal const val CompanionShelfDensityMenuWidthDp = 200f

/** The gap between the chip's bottom edge and the popover's top. */
internal const val CompanionShelfDensityMenuGapDp = 4f

const val CompanionShelfHeaderBandTag = "companion-shelf-header-band"
const val CompanionShelfBackPillTag = "companion-shelf-back-pill"
const val CompanionShelfCountTag = "companion-shelf-count"
const val CompanionShelfIconSeatTag = "companion-shelf-icon-seat"
const val CompanionShelfIconWiredTag = "companion-shelf-icon-wired"
const val CompanionShelfIconConsoleTag = "companion-shelf-icon-console"
const val CompanionShelfDensityChipTag = "companion-shelf-density-chip"
const val CompanionShelfDensityChipTextTag = "companion-shelf-density-chip-text"
const val CompanionShelfDensityMenuTag = "companion-shelf-density-menu"
const val CompanionShelfDensityCatcherTag = "companion-shelf-density-catcher"

/** The popover row for [density]: `companion-shelf-density-rung-large`, `-standard`, `-dense`. */
internal fun companionShelfDensityRungTag(density: CompanionShelfDensity): String = "companion-shelf-density-rung-${density.storageKey}"

internal enum class CompanionShelfIconKind { NONE, WIRED, CONSOLE }

/**
 * What the icon seat holds (policy 4: no synthesized art, and a game's hero never stands in for the platform).
 *  - Nothing until the first persisted-art pass has proved whether this platform has art ([hydrated]), so console art never flashes before wired art.
 *  - The platform's wired art, cropped into the app-icon shape, when it has some on disk ([wiredUri] with [wired] true). While it loads, or if it fails to
 *    decode, nothing is drawn in its place and the seat keeps its 40 dp (R3/R8).
 *  - Otherwise the console line art at full ink. A wired file that was deleted is no art (R8a), so it is this too.
 */
internal fun companionShelfIconKind(hydrated: Boolean, wiredUri: String?, wired: Boolean): CompanionShelfIconKind = when {
    !hydrated -> CompanionShelfIconKind.NONE
    wiredUri.isNullOrBlank() -> CompanionShelfIconKind.CONSOLE
    wired -> CompanionShelfIconKind.WIRED
    else -> CompanionShelfIconKind.CONSOLE
}

/**
 * The header: [CompanionShelfHeaderBandDp] tall, the [MimirCompactHeader] centred in it. The back pill leads, the platform's icon takes the badge's seat, and
 * the count and the chip trail; the title takes whatever width is left, wraps at a word break onto a second line, and only then ellipsizes. No 48 dp target
 * shrinks and the count never yields.
 *
 * [menuOpen] only says which way the chip's description reads; the popover itself is [CompanionShelfDensityMenu], drawn by the shelf over the page (a
 * child outside this band's bounds would never be hit), and [onDensityChipBounds] hands it the chip's place. [onWiredIconState] is the wired art's load
 * result, for the render test.
 */
@Composable
internal fun CompanionShelfHeader(
    platformIdValue: String,
    title: String,
    gameCount: Int?,
    density: CompanionShelfDensity,
    active: Boolean,
    onBack: () -> Unit,
    onDensityMenu: () -> Unit,
    modifier: Modifier = Modifier,
    menuOpen: Boolean = false,
    onDensityChipBounds: (Rect) -> Unit = {},
    onWiredIconState: (AsyncImagePainter.State) -> Unit = {},
) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CompanionShelfHeaderBandDp.dp)
            .testTag(CompanionShelfHeaderBandTag),
        contentAlignment = Alignment.Center,
    ) {
        MimirCompactHeader(
            title = title,
            palette = palette,
            // The count is NOT the header's stat: that yields first when a name is long. On the shelf the count never yields, so it rides in the
            // trailing slot, which is measured first at its own size and keeps it; the name wraps and ellipsizes in what is left.
            stat = null,
            titleMaxLines = 2,
            leading = { CompanionShelfBackPill(enabled = active, onClick = onBack) },
            badge = { CompanionShelfIconSeat(platformIdValue, onWiredIconState) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                    if (gameCount != null) {
                        Text(
                            text = companionShelfGameCountLabel(gameCount),
                            style = MimirTypeScale.Support,
                            color = palette.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag(CompanionShelfCountTag),
                        )
                    }
                    CompanionShelfDensityChip(
                        density = density,
                        enabled = active,
                        menuOpen = menuOpen,
                        onTap = onDensityMenu,
                        onBounds = onDensityChipBounds,
                    )
                    // The Trackpad plate, where this device can host one: the same gateway as the lattice header and the menu row.
                    val openTrackpad = LocalBottomTrackpadOpen.current
                    if (openTrackpad != null) {
                        BottomTrackpadPlate(onOpen = { if (active) openTrackpad() })
                    }
                }
            },
        )
    }
}

/** The 40 dp seat of the platform's icon: wired art cropped to the app-icon shape, or the console art at full ink, or nothing (and still 40 dp). */
@Composable
private fun CompanionShelfIconSeat(platformIdValue: String, onWiredState: (AsyncImagePainter.State) -> Unit) {
    val palette = LocalMimirPalette.current
    val hydrated by PlatformTileArtStore.hydrated.collectAsState()
    val art by PlatformTileArtStore.art.collectAsState()
    val wiredUri = art[platformIdValue]
    val wired = rememberMimirArtWired(wiredUri)
    val context = LocalContext.current
    Box(Modifier.size(CompanionShelfIconSeatDp.dp).testTag(CompanionShelfIconSeatTag)) {
        when (companionShelfIconKind(hydrated, wiredUri, wired)) {
            CompanionShelfIconKind.NONE -> Unit
            CompanionShelfIconKind.WIRED -> {
                // Warm at the seat's birth: whole on the first frame. Cold: nothing while it loads, then FocusMs in from nothing (Q13, 2026-10-14).
                val warm = remember(wiredUri, context) { mimirArtWarmInMemory(context, wiredUri) }
                // The one request, remembered: the painter's state is landed when it answered THIS request (not when its model equals the request's data by type).
                val request = remember(wiredUri, context) { wiredUri?.let { mimirCoverRequest(context, it) } }
                var landed by remember(wiredUri) { mutableStateOf(false) }
                val reveal = rememberMimirColdArtReveal(identity = wiredUri, warm = warm, ready = landed)
                AsyncImage(
                    // The product's one cover request (the shared cap, key and placeholder key): art the Main shelves already warmed paints on the seat's first frame,
                    // where the bare model it replaces loaded from nothing each time the shelf was shown.
                    model = request,
                    contentDescription = null,
                    // Cropped to the icon shape, bare: no tint, no blur. Coil draws nothing while it loads or after a failure, and there is no
                    // placeholder or error painter to change that (R3/R8).
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (mimirArtLanded(state, request)) landed = true
                        onWiredState(state)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = reveal() }
                        .clip(mimirAppIconShape(CompanionShelfIconSeatDp.dp))
                        .testTag(CompanionShelfIconWiredTag),
                )
            }
            CompanionShelfIconKind.CONSOLE -> PlatformConsoleArt(
                platformIdValue = platformIdValue,
                tint = palette.accent,
                modifier = Modifier.fillMaxSize().testTag(CompanionShelfIconConsoleTag),
            )
        }
    }
}

/**
 * A tap on the Bottom, as the shelf does everywhere: a plain `detectTapGestures` node (never `clickable` beside a focus modifier, never
 * `mimirFocusable`), with button semantics so a screen reader can press it. [enabled] false is inert and says so.
 *
 * [interactionSource] is the control's press, reported to (`mimirReportPress`) and never read here: the caller chains `mimirPressResponse` with the same source straight
 * AFTER this modifier and before the fill, the ring and the clip, so they draw in with it while the hit box stays whole (B5, R4). Null leaves a control with no press response, as before.
 */
@Composable
internal fun Modifier.companionShelfTap(
    enabled: Boolean,
    label: String,
    interactionSource: MutableInteractionSource? = null,
    onTap: () -> Unit,
): Modifier {
    // The detector's coroutine outlives a recomposition, so it calls the latest lambda rather than the one in scope when it started.
    val latestTap by rememberUpdatedState(onTap)
    // Read through the state, not captured: the gesture's keys are only `enabled`, so a source that is replaced while it is unchanged must still get the presses.
    val latestSource by rememberUpdatedState(interactionSource)
    return this
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = label
            if (enabled) onClick(label = label) { latestTap(); true } else disabled()
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = { mimirReportPress(latestSource, it) }, onTap = { latestTap() })
        }
}

/** The shelf back pill's visible plate, inside its 48 dp touch target: an ordinary etched chip, identical to an unselected one. */
const val CompanionShelfBackPillPlateTag = "companion-shelf-back-pill-plate"

/** The density chip's visible plate, inside its 48 dp target: the same height as [CompanionShelfBackPillPlateTag]. */
const val CompanionShelfDensityChipPlateTag = "companion-shelf-density-chip-plate"

/**
 * A back control is an ordinary etched chip, identical to an
 * unselected chip: the struck-metal plate ([mimirEtchedChipSurface], not warmed), the chip's padding (md across, sm down), the chip's label style (SupportCompact, SemiBold) in the
 * unselected chip's text colour, and the chevron in the same colour. It has no fill of its own. It sits inside a 48 dp touch target: the outer box is the whole hit area with the tap
 * and the press response on it; the chevron and the label are two centred texts one [MimirSpacing.xs] apart. A separate composable from the Activity pages' `NowPlayingBackPill`
 * (that one lives in `app`).
 */
@Composable
private fun CompanionShelfBackPill(enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .testTag(CompanionShelfBackPillTag)
            .heightIn(min = CompanionShelfTargetDp.dp)
            .widthIn(min = CompanionShelfTargetDp.dp)
            .companionShelfTap(enabled = enabled, label = "Back to Shelf", interactionSource = source, onTap = onClick)
            // After the tap and before the fill: the pill draws in whole while its hit box stays whole (B5, R4).
            .mimirPressResponse(source),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .testTag(CompanionShelfBackPillPlateTag)
                .mimirEtchedChipSurface(palette, warmed = false)
                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("‹", "Shelf").forEach { word ->
                Text(
                    text = word,
                    style = MimirTypeScale.SupportCompact,
                    color = palette.materials.hero.onCover,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The density chip: "4×2 ▾" as an ordinary etched chip, identical to an unselected one and to the Back pill beside it: the struck-metal plate
 * ([mimirEtchedChipSurface], not warmed), md across and sm down, the chip's label style in the unselected chip's text colour, no fill of its own. Inside a 48 dp target. The outer box
 * is the whole hit area and the anchor the popover hangs from. It opens the popover.
 */
@Composable
private fun CompanionShelfDensityChip(
    density: CompanionShelfDensity,
    enabled: Boolean,
    menuOpen: Boolean,
    onTap: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    val latestBounds by rememberUpdatedState(onBounds)
    Box(
        modifier = Modifier
            .testTag(CompanionShelfDensityChipTag)
            .onGloballyPositioned { latestBounds(it.boundsInRoot()) }
            .heightIn(min = CompanionShelfTargetDp.dp)
            .widthIn(min = CompanionShelfTargetDp.dp)
            .companionShelfTap(
                enabled = enabled,
                label = "Shelf grid ${density.columns} by ${density.rows}, ${if (menuOpen) "close the menu" else "change"}",
                interactionSource = source,
                onTap = onTap,
            )
            // After the tap and before the fill: the chip draws in whole while its hit box stays whole (B5, R4).
            .mimirPressResponse(source),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .testTag(CompanionShelfDensityChipPlateTag)
                .mimirEtchedChipSurface(palette, warmed = false)
                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        ) {
            Text(
                text = density.readout,
                style = MimirTypeScale.SupportCompact,
                color = palette.materials.hero.onCover,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag(CompanionShelfDensityChipTextTag),
            )
            MimirChromeText("▾", style = MimirTypeScale.RunicEyebrow, color = palette.materials.hero.onCover, maxLines = 1)
        }
    }
}

/**
 * The density popover and the catcher under it, drawn over the whole shelf panel (the caller's root box): a transparent tap catcher while [open] (an outside tap
 * closes the menu and nothing under it, the pager, the tiles or the swipe back, sees that touch), and the three-row popover anchored under the chip ([anchor], in
 * root space), its right edge on the chip's. The rows are 48 dp `GlassMenuCapsule`s, the current rung lit. It fades on alpha only; it never moves.
 */
@Composable
internal fun CompanionShelfDensityMenu(
    density: CompanionShelfDensity,
    active: Boolean,
    open: Boolean,
    anchor: Rect?,
    onPick: (CompanionShelfDensity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    // Composed from the frame it opens until its fade out has finished; the fade itself is read in the layer, so the shelf does not recompose on each frame of it.
    val fade = rememberMimirFadeMount(visible = open, reducedMotion = mimirReducedMotion())
    var origin by remember { mutableStateOf(Offset.Zero) }
    var width by remember { mutableStateOf(0) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val pixels = LocalDensity.current
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                width = it.size.width
            },
    ) {
        if (open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(CompanionShelfDensityCatcherTag)
                    // Eats the whole gesture: a tap closes the menu, a drag or a swipe does nothing, and nothing under it is reached.
                    .pointerInput(Unit) { detectTapGestures(onTap = { latestDismiss() }) },
            )
        }
        if (open || fade.mounted) {
            val chip = anchor
            val x = if (chip == null) 0 else -(width - (chip.right - origin.x)).toInt()
            val y = if (chip == null) {
                with(pixels) { (CompanionShelfTopInsetDp + CompanionShelfHeaderBandDp).dp.roundToPx() }
            } else {
                (chip.bottom - origin.y + with(pixels) { CompanionShelfDensityMenuGapDp.dp.toPx() }).toInt()
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(x, y) }
                    .width(CompanionShelfDensityMenuWidthDp.dp)
                    .testTag(CompanionShelfDensityMenuTag)
                    .graphicsLayer { alpha = fade.alpha }
                    .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                    .background(palette.surfaceHigh)
                    .padding(vertical = 4.dp),
            ) {
                CompanionShelfDensity.values().forEachIndexed { index, option ->
                    if (index > 0) PlainMenuSeparator()
                    val source = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = CompanionShelfTargetDp.dp)
                            .testTag(companionShelfDensityRungTag(option))
                            .companionShelfTap(
                                enabled = active && open,
                                label = "${option.rungLabel}, ${option.columns} by ${option.rows} games a page",
                                interactionSource = source,
                                onTap = { onPick(option) },
                            )
                            .mimirPressResponse(source),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassMenuCapsule(focused = option == density, identity = option) {
                            Text(
                                text = option.rungLabel,
                                style = MimirTypeScale.Row,
                                color = palette.textPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
