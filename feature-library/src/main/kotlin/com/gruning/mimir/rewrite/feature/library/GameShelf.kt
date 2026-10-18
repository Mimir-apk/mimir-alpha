package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.input.isRenderedFocusIn
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirLedgeCoverArt
import com.gruning.mimir.rewrite.core.ui.mimirBookMannazTopDp
import com.gruning.mimir.rewrite.core.ui.MimirLedgeCoverCorner
import com.gruning.mimir.rewrite.core.ui.MimirLedgeFocusRingShell
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinCornerDp
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinHeightDp
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirNorseBookAspectRatio
import com.gruning.mimir.rewrite.core.ui.MimirPlatformShelfmark
import com.gruning.mimir.rewrite.core.ui.MimirShelfmarkTier
import com.gruning.mimir.rewrite.core.ui.MimirSwitchTileArt
import com.gruning.mimir.rewrite.core.ui.MimirSwitchTileCorner
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirFocusTileRing
import com.gruning.mimir.rewrite.core.ui.mimirNorseBookCoverCorner
import com.gruning.mimir.rewrite.core.ui.mimirPlatformShelfmarkCode
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.rememberMimirFocusGameGleam
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import kotlin.math.roundToInt

// Home's tags: GameShelfIds.Home derives exactly these from its prefix (GameShelfIdsTest pins it), and the Home pins read them here.
internal const val HomeShelfTileTestTagPrefix = "home_shelf_tile_"
internal const val HomeShelfSelectedSlotTestTag = "home_shelf_selected_slot"
internal const val HomeShelfStationaryRingTestTag = "home_shelf_stationary_ring"
internal const val HomeShelfLeftContinuationTestTag = "home_shelf_continuation_left"
internal const val HomeShelfRightContinuationTestTag = "home_shelf_continuation_right"
internal val HomeShelfPresentationAlphaSemantics = SemanticsPropertyKey<Float>("HomeShelfPresentationAlpha")
internal val HomeShelfPresentationScaleSemantics = SemanticsPropertyKey<Float>("HomeShelfPresentationScale")
internal val HomeShelfVisualSlotSemantics = SemanticsPropertyKey<Float>("HomeShelfVisualSlot")

// Shelf-switch timing: 140ms read as a pixel shift on black-tile→black-tile moves. The glide and the
// hero's outgoing crossfade share ONE clock on purpose — a longer slide with a shorter fade exposed
// the incoming art mid-travel ("bleeds in before settling"), so both extended together to 210ms.
internal const val HomeShelfOutgoingFadeMs = 210

/** How far above and below the tile band (dp) a touch may begin a drag of the strip: the hero grows past its seat. */
private const val HomeShelfTouchBandMarginDp = 24f

// `internal`, not private, since 2026-09-01: CoverflowMotionRestAlpha is now defined as EQUAL to this
// value (shared ghost-tile alpha — see that constant's own doc), and CoverflowGhostTileAlphaTest pins the
// two together. A pin that restated 0.82 as its own literal would pass while the two silently drifted apart.
internal const val HomeShelfRestingAlpha = 0.82f

// FUX-01 §3.3/§3.4 focus grammar (panel §5, adopted): frameless art carries focus through scale, a DRAWN
// radial glow (never shadowElevation — the Thor graphicsLayer-alpha artifact, and a ghost box on frameless
// art), and the B2 caret. Glow lives only on the FOCUSED hero; the demoted hero (rail/picker owns focus)
// quiets to 0.9 with the glow dead.
private const val HomeShelfDemotedHeroAlpha = 0.9f

internal fun homeShelfTileTargetScale(focused: Boolean, selectedScale: Float): Float =
    if (focused) selectedScale else 1f

/**
 * Everything a [GameShelf] is shown and nothing it is asked: the items, the geometry, the surface's epoch and motion setting and
 * the names it wears, with what [rememberGameShelfState] resolves from them -- the strip's window, its two movers (a finger and the
 * logical window start), the mounted seats, and the eased seat the selection ring travels to.
 *
 * Every caller builds one with [rememberGameShelfState] and hands it to the shelf, which reads these from it and from nowhere else,
 * so a value cannot be given twice and differ. It is built in the caller's own composition because the caller may need it first:
 * Home's backdrop follows [visualSelectedSeat].
 */
@Stable
internal class GameShelfState(
    val items: List<GameShelfItem>,
    val geometry: GameShelfGeometry,
    val surfaceEpoch: Int,
    val reducedMotion: Boolean,
    val ids: GameShelfIds,
    val selectedIndex: Int?,
    val windowStart: Int,
    val mountStart: Int,
    val maxWindowStart: Int,
    val visibleSlots: List<HomeShelfVisibleSlot<GameShelfItem>>,
    val touch: CarouselStripTouch,
    val windowPos: Animatable<Float, AnimationVector1D>,
    val windowStartHolder: MutableState<Int>,
    /**
     * The selected seat, eased over [HomeShelfOutgoingFadeMs]; the stationary ring sits here and the backdrop follows it.
     *
     * The animation's State, not its value, and every reader takes it in a layout or layer lambda (the ring's `offset {}`, the
     * backdrop pool's `graphicsLayer {}`). Resolved as a Float here it was read in the CALLER's composition, so the whole Home
     * screen recomposed on every frame of the glide that follows each focus move.
     */
    val visualSelectedSeat: State<Float>,
    /** The game leaving the shelf, if one is (the Library's walk-32g/h departure); Home never has one. */
    val departure: GameShelfDeparture? = null,
    /** Where the strip was painted when [departure] began, in window starts: the old list's window, which the gap closes from. */
    val departureFromWindow: Float = 0f,
) {
    /**
     * The seat, in seats from the strip's left, the tile of the item at [itemIndex] is painted at. Without a departure that is
     * the item's place less the strip's one painted position. During one the strip is parked on the new list's window and
     * the games from the leaving index on start one seat further along, as the old list put them (and the whole strip starts where
     * it stood, when the shorter list clamps its window): at a gap of 1 every game sits where it sat before the removal and at
     * 0 where the shorter list puts it, on one clock. Read in layout lambdas only.
     */
    fun paintedSeat(itemIndex: Int): Float {
        val leaving = departure ?: return itemIndex - windowPos.value
        val shift = (if (itemIndex >= leaving.index) 1f else 0f) + (windowStart - departureFromWindow)
        return itemIndex - windowStart + leaving.gap() * shift
    }

    /** The seat the leaving tile stays in for as long as it dissolves: where the old list had it. */
    fun leavingSeat(): Float = (departure?.index ?: 0) - departureFromWindow
}

/**
 * The strip's state, resolved in the caller's composition so a caller can read [GameShelfState.visualSelectedSeat] before
 * the shelf itself composes.
 *
 * @param selectedKey the hero identity, focused or remembered; null while it is unresolved (a restoring surface).
 * @param windowMemory the calling surface's own memory, and [windowMemoryKey] the state of it to seed from and record to.
 * @param surfaceEpoch a new epoch starts the strip afresh.
 * @param ids the names the shelf wears for tests and tooling (its tags, semantics keys and animation labels); Home's are the
 *   default, so a surface with tests of its own builds a [GameShelfIds] from its own prefix, once.
 * A new [windowMemoryKey] starts it afresh too: the window, the finger's mover and the ring's seat are re-seeded from what the memory
 * holds for that key, so a surface that pages between states of equal length (the Library's L2/R2 between platforms) lands each
 * where it was left. Home's key is `HomeShelfWindowMemory.key(epoch, label)`; its label changes with its mode and every mode switch
 * bumps the epoch (a Tome return that falls back to Editor's Choice included), so the key changes only when the epoch does and Home
 * keeps every one of these exactly as the epoch alone kept them.
 */
@Composable
internal fun rememberGameShelfState(
    items: List<GameShelfItem>,
    selectedKey: FocusKey?,
    geometry: GameShelfGeometry,
    windowMemory: GameShelfWindowMemory,
    windowMemoryKey: Any,
    surfaceEpoch: Int,
    reducedMotion: Boolean,
    ids: GameShelfIds = GameShelfIds.Home,
    departure: GameShelfDeparture? = null,
): GameShelfState {
    val selectedIndex = items.indexOfFirst { it.focusKey == selectedKey }.takeIf { it >= 0 }
    // Four immutable envelopes at release widths (three only below the compact threshold). While another
    // item exists, landing on an edge advances the finite window on that same D-pad move so the next full
    // tile is already readable. One hidden identity on each side keeps hydration warm without painting it.
    // Seeded from where this surface's window stood when Home last showed it. Every return from the
    // Library composes Home afresh, and a window re-derived from 0 put the focused tile in a different seat.
    val windowStartHolder = remember(surfaceEpoch, windowMemoryKey, items.size) {
        mutableStateOf(
            (windowMemory.seed(windowMemoryKey) ?: 0)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        )
    }
    // Anchored on `selectedIndex` itself, not on a `?: 0` stand-in (see UI-02 in HomeShelfScreen). Feeding
    // `focusedIndex = 0` here did not merely mis-anchor for a frame: homeShelfWindowStart's
    // `focus < start -> focus` branch returns 0 outright, and the `.also` then WRITES that 0 back
    // into the holder -- so the surface forgot where it had been and had to scroll the whole way
    // back once the real index landed. Holding the last resolved start costs nothing and cannot
    // teleport.
    val windowStart = selectedIndex?.let { focus ->
        homeShelfWindowStart(
            previousStart = windowStartHolder.value,
            focusedIndex = focus,
            itemCount = items.size,
            visibleSeatCount = geometry.visibleSlotCount,
        ).also { windowStartHolder.value = it }
    } ?: windowStartHolder.value
    SideEffect { windowMemory.record(windowMemoryKey, windowStart) }
    // Touch (CarouselTouch.kt). The strip's PAINTED position is one scalar for every tile: the window start, as a float.
    // It follows the logical [windowStart] on a spring (the very spring each tile used to run for itself, from the
    // same rest, so a pad move paints exactly as it did), and a finger drives it directly. Tiles place themselves at
    // (item index - windowPos) in their layout lambdas; nothing here is read in composition while a finger moves it.
    // Keyed with the holder above: a new epoch or a new memory key starts the finger's mover and the painted position afresh.
    val touch = remember(surfaceEpoch, windowMemoryKey) { CarouselStripTouch() }
    val windowPos = remember(surfaceEpoch, windowMemoryKey) { Animatable(windowStart.toFloat()) }
    val maxWindowStart = (items.size - geometry.visibleSlotCount).coerceAtLeast(0)
    // The window start the painted strip is nearest: it changes once for each tile the strip crosses, and is read
    // in composition only while a touch is engaged.
    val paintedStart by remember(windowPos, maxWindowStart) {
        derivedStateOf { windowPos.value.roundToInt().coerceIn(0, maxWindowStart) }
    }
    // While a finger holds the strip, or a lift is settling it, tiles mount (and show) around where the strip is
    // painted; otherwise exactly around the logical window, as before.
    val mountStart = if (touch.engaged) paintedStart else windowStart
    // Seats stay measured from the LOGICAL start, so the ring and the roles keep their meaning.
    val visibleSlots = remember(items, windowStart, mountStart, geometry.visibleSlotCount) {
        homeShelfTouchMountedWindow(
            items = items,
            windowStart = windowStart,
            mountStart = mountStart,
            visibleSeatCount = geometry.visibleSlotCount,
        )
    }
    // The strip's two movers: a finger (one for one) and the logical window start (the shared seat spring, retargeted
    // with the velocity it has, as the per-tile springs were). See HomeShelfStripDriver.
    //
    // A departure paints the strip from the new list's window and closes its gap on its own clock ([GameShelfState.paintedSeat]), so
    // the spring must not also carry the strip: the driver snaps for as long as one is seated, and the position is where the
    // new window start puts it by the time the departure ends. The old window is read once, as the departure is seated, without
    // subscribing this composition to the position.
    val departureFromWindow = remember(departure) {
        if (departure == null) 0f else Snapshot.withoutReadObservation { windowPos.value }
    }
    HomeShelfStripDriver(
        touch = touch,
        windowPos = windowPos,
        windowStart = windowStart,
        reducedMotion = reducedMotion || departure != null,
    )
    // Focus emphasis follows the selected identity's actual list seat. It never jumps across the
    // viewport at an edge because the opposite end is not a neighbour anymore.
    //
    // The former ?:0 fallback directly drove the observed ring jump: it is read straight into the animateFloatAsState below, so an
    // unresolved selection did not hide the ring, it DROVE it -- an eased, fully-committed glide to
    // seat 0 and an equally eased glide back. Holding the last resolved seat is what makes the
    // repair invisible instead of merely smooth. Keyed on surfaceEpoch and windowMemoryKey to match the
    // animatable's own `key(surfaceEpoch, windowMemoryKey)`, so a genuine surface change resets both together and the animation
    // starts at its target rather than travelling to it.
    val emphasisSeatHolder = remember(surfaceEpoch, windowMemoryKey) { mutableStateOf(0) }
    val selectedSlotOffset = visibleSlots
        .firstOrNull { it.itemIndex == selectedIndex }
        ?.slotOffset
        ?.also { emphasisSeatHolder.value = it }
        ?: emphasisSeatHolder.value
    val visualSelectedSlotOffset = key(surfaceEpoch, windowMemoryKey) {
        animateFloatAsState(
            targetValue = selectedSlotOffset.toFloat(),
            animationSpec = if (reducedMotion) snap() else {
                tween(HomeShelfOutgoingFadeMs, easing = MimirMotion.Easing)
            },
            label = ids.emphasisSeatLabel,
        )
    }
    return GameShelfState(
        items = items,
        geometry = geometry,
        surfaceEpoch = surfaceEpoch,
        reducedMotion = reducedMotion,
        ids = ids,
        selectedIndex = selectedIndex,
        windowStart = windowStart,
        mountStart = mountStart,
        maxWindowStart = maxWindowStart,
        visibleSlots = visibleSlots,
        touch = touch,
        windowPos = windowPos,
        windowStartHolder = windowStartHolder,
        visualSelectedSeat = visualSelectedSlotOffset,
        departure = departure,
        departureFromWindow = departureFromWindow,
    )
}

/**
 * The game shelf: a finite, left-anchored strip of seats, one stationary selection ring, the continuation runes, and the
 * finger's drag and fling. The Home shelf is this component; the Library gamelist moves onto it (* the two shelves are one).
 *
 * It paints the band only. The title rail, the count, the hints, the dock and the backdrop are the screen's, and so is
 * focus registration: the caller registers its items' keys (the Library registers the same ids under its list view) and
 * this shelf reads which one is selected.
 *
 * What each parameter owns, so a second caller need not read the first:
 * - [state] is what the shelf is shown: the items, the geometry, the surface epoch, reduced motion, the [GameShelfIds] it wears and
 *   the window resolved from them, built by [rememberGameShelfState] (which also takes the surface's [GameShelfWindowMemory], one
 *   per surface, and the key of the state of it that is remembered across a recomposition of the screen). The shelf reads these
 *   from the state and nowhere else.
 * - [scope], [authority] and [focusState] are where focus lives. [focusState] is the caller's own snapshot of the authority, the one
 *   it derived the selected key it built [state] from, so the ring, the hero's scale and the Tome preview gate all read the same
 *   frame as the selection (a collector of the shelf's own could be a recomposition ahead or behind it). The STATE channel (the hero's scale and alpha) reads the rendered
 *   focus in [scope], so it holds through a pushed dock or modal; the RING channel reads the lit focus, so the ring goes
 *   dark when the scope loses input. [chromeHeldByCover] keeps the ring and the runes painted while a full-surface cover
 *   (Home's Tome Folio) owns input -- see [homeShelfLiveChromeLit]. A shelf with no such cover leaves it false.
 * - [showShelfmark] seats the platform plate on each game tile. Home does (a mixed-platform surface); a
 *   gamelist is one platform and does not.
 * - [inert] draws nothing and takes no touch: the app's restoration cover is up and no fallback may be selected.
 * - [emptyContent] is what an empty shelf shows (Home: the Open Library action; the Library: its loader or empty copy).
 * - [onRequestFocus] and [onActivate] are a tap: focus the tile, then act on it. A game launches; a Tome opens.
 * - [touchEnabled] and [ringGleamArmed] are read inside the shelf, so a flag that flips during a handoff recomposes the
 *   shelf and not the screen around it.
 * - [paintMarks] seats each game's [GameShelfMarks] (the favourite rune, the disc badge) on its art; Home has none and does not ask.
 * - [shatter] is the Library's hide stage: the selected tile draws its art through a layer and tells the stage where it is, so a
 *   hide can take its picture before the game leaves. The departure itself rides [GameShelfState.departure].
 */
@Composable
internal fun GameShelf(
    state: GameShelfState,
    scope: FocusScope,
    authority: FocusAuthority,
    focusState: FocusState,
    onRequestFocus: (FocusKey) -> Unit,
    onActivate: (GameShelfItem) -> Unit,
    emptyContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    inert: Boolean = false,
    showShelfmark: Boolean = true,
    chromeHeldByCover: Boolean = false,
    touchEnabled: () -> Boolean = { true },
    ringGleamArmed: () -> Boolean = { true },
    onTomePreviewPresented: (TomeId, HomeTomePreview?) -> Unit = { _, _ -> },
    paintMarks: Boolean = false,
    shatter: LibraryShatterStage? = null,
) {
    val items = state.items
    val geometry = state.geometry
    val surfaceEpoch = state.surfaceEpoch
    val reducedMotion = state.reducedMotion
    val ids = state.ids
    val selectedIndex = state.selectedIndex
    val selectedItem = selectedIndex?.let(items::getOrNull)
    val touch = state.touch
    val windowPos = state.windowPos
    val windowStart = state.windowStart
    val mountStart = state.mountStart
    val visibleSlots = state.visibleSlots
    val visualSelectedSlotOffset: () -> Float = { state.visualSelectedSeat.value }
    val departure = state.departure
    // A dissolve keeps painting the leaving game's tile where it stood, in the SAME keyed slot loop as every other tile: a tile is
    // remembered on its key at this one call site, so a second site would dispose the tile the player was looking at and start a
    // new one, and there would be nothing left to dissolve. It is not in the items, so it takes no focus, no press and no ring.
    val leavingSlot = departure?.takeIf { it.paintsLeavingTile }?.let { gone ->
        HomeShelfVisibleSlot<GameShelfItem>(item = gone.item, itemIndex = gone.index, slotOffset = gone.index - windowStart)
    }
    val paintedSlots = if (leavingSlot == null) visibleSlots else visibleSlots + leavingSlot

    // Touch (CarouselTouch.kt): a horizontal drag carries the strip one for one, and a lift settles it on a whole
    // window start and focuses the item then at the ring's seat (kept inside the seats the pad's own window rule
    // allows), through the one authority a tap and the pad reach. A tile's tap is its own, untouched. The gesture
    // begins only on the tile band, locks to the horizontal axis at the touch slop, and waits with the pad while
    // a mode switch hides the band.
    val touchDensity = LocalDensity.current.density
    val stridePx = geometry.slotStride * touchDensity
    val bandTopPx = (geometry.gameRailBand.top - HomeShelfTouchBandMarginDp) * touchDensity
    val bandBottomPx = (geometry.gameRailBand.bottom + HomeShelfTouchBandMarginDp) * touchDensity
    val stripTouch = Modifier.carouselTouchDrag(
        gestureKey = touch,
        enabled = !inert && items.isNotEmpty() && touchEnabled(),
        startRegion = { down -> down.y in bandTopPx..bandBottomPx },
        onDragStart = { touch.begin(windowPos.value) },
        onDrag = { dx ->
            touch.move(homeShelfDraggedWindowPosition(touch.position, dx, stridePx, state.maxWindowStart))
        },
        onDragEnd = { velocityX ->
            val velocitySlots = if (stridePx > 0f) -velocityX / stridePx else 0f
            val settledStart = homeShelfSettleWindowStart(
                position = touch.position,
                velocitySlotsPerSec = velocitySlots,
                itemCount = items.size,
                visibleSeatCount = geometry.visibleSlotCount,
            )
            val settledFocus = homeShelfFocusForWindow(
                currentSeat = (selectedIndex ?: windowStart) - windowStart,
                windowStart = settledStart,
                itemCount = items.size,
                visibleSeatCount = geometry.visibleSlotCount,
            )
            touch.release(velocitySlots)
            state.windowStartHolder.value = settledStart
            val settledKey = items.getOrNull(settledFocus)?.focusKey
            if (settledKey != null) {
                if (authority.state.value.scope == scope) {
                    authority.requestFocus(settledKey)
                } else {
                    // The dock or a modal holds input: the shelf's remembered item moves, and the pad finds it there.
                    authority.rememberFocusForScope(scope, settledKey)
                }
            }
        },
    )

    Box(modifier = modifier.fillMaxSize().then(stripTouch)) {
        if (inert) {
            // The app-level restoration cover is visible. Keep the underlying shelf equally inert so no
            // fallback row can be selected, focused, or launched before exact-return truth settles.
        } else if (items.isEmpty()) {
            emptyContent()
        } else {
            // One keyed tile body per durable identity. Position, scale, and alpha all retarget from the
            // currently painted frame. There is no separate fixed hero and no translated side copy, so a
            // cold/error/no-art destination cannot expose a previous identity underneath it.
            paintedSlots.forEach { slot ->
                key(surfaceEpoch, slot.item.focusKey) {
                    val leavingNow = leavingSlot != null && slot.item.focusKey == leavingSlot.item.focusKey
                    val selected = !leavingNow && slot.itemIndex == selectedIndex
                    val focused = selected &&
                        authority.isRenderedFocusIn(scope, slot.item.focusKey, focusState)
                    // Seats count from where the strip is painted while a finger or a settle has it.
                    val seat = if (touch.engaged) slot.itemIndex - mountStart else slot.slotOffset
                    val isBuffer = !leavingNow && (seat < 0 || seat >= geometry.visibleSlotCount)
                    // STATE channel (core-input's two-channel law). the
                    // selected tile keeps its hero SCALE AND ALPHA while a pushed scope (dock,
                    // Utility, any modal) owns FocusAuthority -- "the focused tile should stay static
                    // with the tile in the same state" -- painted from renderedFocusIn's remembered
                    // fallback, not just while this scope is literally the active one. Its RING is a
                    // separate channel and is NOT conserved (see the isLitFocusIn gate below):
                    // conserving both is what produced the ghost trail the regression report described on device.
                    //
                    // RC-50 scope-check, 2026-08-22: Ring indication is not conserved for the dock and the
                    // Utility modal -- surfaces BESIDE this one, where a lit ring is a claim on input
                    // this scope does not have. It is not a rule for full-surface covers. The Tome
                    // Folio conserves the ring channel too, via homeShelfLiveChromeLit; the dock and
                    // Utility are unchanged and stay that way by construction, since neither can set
                    // chromeHeldByCover.
                    //
                    // A game that is leaving was the hero a moment ago and keeps the pose it was last painted with (a dissolve fades
                    // it where it stood): dropping it to a resting neighbour's scale and alpha on the first frame would be a pop.
                    val targetScale = homeShelfTileTargetScale(focused || leavingNow, geometry.selectedScale)
                    val targetAlpha = when {
                        leavingNow -> 1f
                        selected -> if (focused) 1f else HomeShelfDemotedHeroAlpha
                        isBuffer -> 0f
                        else -> HomeShelfRestingAlpha
                    }
                    // A hydrated-but-unpainted buffer seat (alpha 0) must not take a tap: it is invisible, and a touch on the
                    // empty edge would focus and open a game nobody can see. Gated on the DISCRETE target, as the gamelist
                    // gates its edge cards (identical at settle), so the animated alpha is no composition subscription.
                    val tappable = targetAlpha > 0.01f && !leavingNow
                    val motionSpec: AnimationSpec<Float> = if (reducedMotion) {
                        snap()
                    } else {
                        // BM02: seat positions spring (velocity-preserving retargets — see
                        // CoverflowSeatSpring); fades keep the house tween.
                        CoverflowSeatSpring
                    }
                    // Read in the layout lambda: the list seat less the strip's one painted position (windowPos), or, while a game
                    // departs, the seat the departure paints it at.
                    val visualSlot: () -> Float = when {
                        leavingNow -> { { state.leavingSeat() } }
                        departure != null -> { { state.paintedSeat(slot.itemIndex) } }
                        else -> { { slot.itemIndex - windowPos.value } }
                    }
                    val leavingPresence = if (leavingNow) departure?.presence else null
                    // FUX-12n stabilization law: a gaining body may ease brighter/larger, but a body
                    // losing the hero/side role snaps down on the logical commit. Gradually fading and
                    // shrinking an outgoing body while it travels reads as panel persistence/afterimage.
                    val roleScale = remember { Animatable(targetScale) }
                    val roleAlpha = remember { Animatable(targetAlpha) }
                    LaunchedEffect(targetScale, reducedMotion) {
                        if (reducedMotion || targetScale < roleScale.value) {
                            roleScale.snapTo(targetScale)
                        } else {
                            roleScale.animateTo(targetScale, motionSpec)
                        }
                    }
                    LaunchedEffect(targetAlpha, reducedMotion) {
                        if (reducedMotion || targetAlpha < roleAlpha.value) {
                            roleAlpha.snapTo(targetAlpha)
                        } else {
                            roleAlpha.animateTo(targetAlpha, motionSpec)
                        }
                    }
                    GameShelfMovingSlot(
                        item = slot.item,
                        visualSlot = visualSlot,
                        selected = selected,
                        focused = focused,
                        // The band's layer carries the switch's alpha and travel (batch D); the 0.965
                        // settle scale retired with it.
                        alpha = if (leavingPresence == null) {
                            { roleAlpha.value }
                        } else {
                            { roleAlpha.value * leavingPresence() }
                        },
                        scale = { roleScale.value },
                        // Destination owns stacking from the same commit as focus/title; no outgoing
                        // body remains hero-high during the positional glide. A game that has left stands above its
                        // neighbours and below whichever tile is arriving at the ring.
                        heroProgress = if (selected) 1f else if (leavingNow) GameShelfLeavingStack else 0f,
                        geometry = geometry,
                        reducedMotion = reducedMotion,
                        showShelfmark = showShelfmark,
                        ids = ids,
                        enabled = tappable,
                        onRequestFocus = { onRequestFocus(slot.item.focusKey) },
                        onActivate = onActivate,
                        onTomePreviewPresented = onTomePreviewPresented,
                        leaving = leavingNow,
                        paintMarks = paintMarks,
                        shatter = shatter,
                    )
                }
            }
            // RING channel: the stationary focus ring is the literal thing the request specified to be
            // "gone until return". Gated on the LIVE scope, so opening Utility or dropping into the
            // dock takes the ring with it while the tile above keeps its hero pose.
            //
            // RC-50: except under the Tome Folio, which is a full-surface glass cover rather than a
            // surface beside this one -- see homeShelfLiveChromeLit for the scope-check. The lit read
            // below is unchanged and still the only thing the dock and Utility can see.
            if (selectedItem != null &&
                homeShelfLiveChromeLit(
                    scopeOwnsInput = authority.isLitFocusIn(scope, selectedItem.focusKey, focusState),
                    folioActive = chromeHeldByCover,
                )
            ) {
                // Travels with the band it annotates. A ring that held still while the tiles glided
                // in would visibly detach from the seat it is drawn around -- the same "ring in the
                // wrong place while the surface resolves" class of defect as UI-02, arriving by a
                // different route.
                Box(Modifier.fillMaxSize()) {
                    // The gleam when the traverse stops. The ring stays stationary and at full
                    // strength; only light crosses it, after the tiles have glided into the seat. A shelf tile
                    // gets the game gleam, three slow sweeps each ending in a sparkle; a Tome on the shelf shares the one ring, so it shines like its neighbours.
                    val ringGlint = rememberMimirFocusGameGleam(selectedItem.focusKey.takeIf { ringGleamArmed() })
                    // A shatter's survivor wears no ring while the hole the hide left is open: it arrives with the gap closing.
                    val ringReveal = departure?.ringReveal
                    val ringProgress: () -> Float = if (ringReveal == null) {
                        { 1f }
                    } else {
                        ringReveal
                    }
                    when (selectedItem) {
                        is GameShelfItem.Game -> GameShelfStationaryFocusRing(
                            face = selectedItem.face,
                            geometry = geometry,
                            visualSlotOffset = visualSelectedSlotOffset,
                            reducedMotion = reducedMotion,
                            glint = ringGlint,
                            tag = ids.ringTag,
                            progress = ringProgress,
                        )
                        is GameShelfItem.Tome -> GameShelfStationaryTomeFocusRing(
                            geometry = geometry,
                            visualSlotOffset = visualSelectedSlotOffset,
                            glint = ringGlint,
                            tag = ids.ringTag,
                        )
                    }
                }
            }
        }

        // Not while inert: the tiles and the ring above are not drawn then, and runes that stayed would flank a band that is not there
        // (and would announce "More items to the right" to a screen reader about a shelf nothing can reach).
        if (!inert && items.isNotEmpty() && selectedIndex != null) {
            // Same reason as the ring above: the runes mark the band's edges, so they travel with it.
            // Keyed on surfaceEpoch like the tiles: each rune has its own layer, and one that survived the
            // flip would re-record in the departing band with the NEW continuation.
            key(surfaceEpoch) {
                Box(Modifier.fillMaxSize()) {
                    GameShelfContinuationRunes(
                        continuation = homeShelfContinuation(
                            selectedIndex = selectedIndex,
                            itemCount = items.size,
                            // RING channel: the continuation runes are the shelf's "you can keep moving"
                            // affordance, which only means anything while this scope owns input.
                            //
                            // RC-50 sibling: the runes flank the same band the ring annotates and flip on
                            // the same live-scope read, so they vanish on the same tome commit frame. One
                            // decision, three sites -- see homeShelfLiveChromeLit.
                            active = selectedItem?.focusKey?.let {
                                homeShelfLiveChromeLit(
                                    scopeOwnsInput = authority.isLitFocusIn(scope, it, focusState),
                                    folioActive = chromeHeldByCover,
                                )
                            } == true,
                        ),
                        geometry = geometry,
                        reducedMotion = reducedMotion,
                        ids = ids,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameShelfMovingSlot(
    item: GameShelfItem,
    visualSlot: () -> Float,
    selected: Boolean,
    focused: Boolean,
    alpha: () -> Float,
    scale: () -> Float,
    heroProgress: Float,
    geometry: GameShelfGeometry,
    reducedMotion: Boolean,
    showShelfmark: Boolean,
    ids: GameShelfIds,
    enabled: Boolean,
    onRequestFocus: () -> Unit,
    onActivate: (GameShelfItem) -> Unit,
    onTomePreviewPresented: (TomeId, HomeTomePreview?) -> Unit,
    leaving: Boolean,
    paintMarks: Boolean,
    shatter: LibraryShatterStage?,
) {
    val resolvedHeroProgress = heroProgress.coerceIn(0f, 1f)
    // A Tome seat's press, drawn by the one press response and reported by its own tap.
    val tomeInteraction = remember(item.focusKey) { MutableInteractionSource() }
    // A Tome seat's spoken name, resolved here in composition: the semantics block below is not a composable lambda.
    val res = libraryResources()
    val tomeDescription = (item as? GameShelfItem.Tome)?.tome?.let { tome ->
        res.getString(
            R.string.library_home_tome_description,
            tome.title,
            res.getQuantityString(R.plurals.library_home_games_count, tome.memberCount, tome.memberCount),
        )
    }

    Box(
        modifier = Modifier
            // PERF (CP04 follow-up): the animated seat position is read HERE, in the layout lambda —
            // ring motion re-lays this node out without recomposing it.
            // the earlier update rev B : EVERY seat — the veiled peek included — sits at the uniform
            // slotStride from the centred hero. The first cut tucked the peek seat toward the SCREEN gutter,
            // which compressed the outermost gap and broke the centered hero-relative rhythm;
            // that tuck is retired.
            .offset {
                val slot = visualSlot()
                val left = geometry.contentLeft + slot * geometry.slotStride
                IntOffset(left.dp.roundToPx(), geometry.gameRailBand.top.dp.roundToPx())
            }
            .size(geometry.seatEnvelope.dp)
            .zIndex(1f + resolvedHeroProgress)
            .then(if (selected) Modifier.testTag(ids.selectedSlotTag) else Modifier),
        contentAlignment = Alignment.BottomCenter,
    ) {
        when (item) {
            is GameShelfItem.Game -> GameShelfTile(
                item = item,
                visualSlot = visualSlot,
                selected = selected,
                alpha = alpha,
                scale = scale,
                geometry = geometry,
                reducedMotion = reducedMotion,
                showShelfmark = showShelfmark,
                ids = ids,
                enabled = enabled,
                onRequestFocus = onRequestFocus,
                onActivate = onActivate,
                leaving = leaving,
                paintMarks = paintMarks,
                shatter = shatter,
            )
            is GameShelfItem.Tome -> HomeTomeFace(
                tome = item.tome,
                focused = focused,
                showFocusRing = false,
                reducedMotion = reducedMotion,
                onPreviewPresented = { preview ->
                    if (focused) onTomePreviewPresented(item.tome.tomeId, preview)
                },
                modifier = Modifier
                    .width((geometry.restingSide * 0.75f).dp)
                    .height(geometry.restingSide.dp)
                    .graphicsLayer {
                        val resolvedScale = scale()
                        scaleX = resolvedScale
                        scaleY = resolvedScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        this.alpha = alpha().coerceIn(0f, 1f)
                    }
                    .clickable(interactionSource = tomeInteraction, indication = null, enabled = enabled) {
                        onRequestFocus()
                        onActivate(item)
                    }
                    .mimirPressResponse(tomeInteraction)
                    .semantics(mergeDescendants = true) {
                        contentDescription = tomeDescription.orEmpty()
                        this.selected = selected
                        this[ids.presentationAlpha] = alpha()
                        this[ids.presentationScale] = scale()
                        this[ids.visualSlot] = visualSlot()
                    },
            )
        }
    }
}

@Composable
private fun GameShelfTile(
    item: GameShelfItem.Game,
    visualSlot: () -> Float,
    selected: Boolean,
    alpha: () -> Float,
    scale: () -> Float,
    geometry: GameShelfGeometry,
    reducedMotion: Boolean,
    showShelfmark: Boolean,
    ids: GameShelfIds,
    enabled: Boolean,
    onRequestFocus: () -> Unit,
    onActivate: (GameShelfItem) -> Unit,
    leaving: Boolean,
    paintMarks: Boolean,
    shatter: LibraryShatterStage?,
) {
    val interaction = remember(item.focusKey) { MutableInteractionSource() }

    // ── The platform SHELFMARK  ───────────────────────────────────────────
    // Console identifiers appear on Home tiles; gamelist covers already identify their platform
    //
    // Favorite Home is a mixed-platform surface, and custom Tomes may span machines. The plate therefore
    // does real identification work HERE, and only here — a gamelist is one platform by construction (every card
    // shares platformIdValue) and its covers already carry that platform's own nomenclature in the art.
    //
    // `platformIdValue` is empty for an Android game (HomeShelfCoordinator): an Android game has no console, so it gets
    // NO plate — that is the honest fallback contract (a meaningless plate is worse than the absence it stands in for),
    // not a special case.
    //
    // ONE FIXED TIER for every tile on this shelf, deliberately — NOT a RESTING/FOCUSED swap on selection.
    // Two reasons, and the second is the load-bearing one:
    //  1. The emphasis is already there for free. This tile's own graphicsLayer carries the shelf's scale
    //     (selectedScale on the hero) and alpha (HomeShelfRestingAlpha on neighbours), so the plate inherits
    //     "bigger + brighter when focused" from its parent without asking for anything.
    //  2. Swapping tiers would RESIZE the plate (16↔18dp) and re-measure its text node on every D-pad move —
    //     a per-move relayout on the surface PERF-D/PERF-G fought for frames on, and a visible pop where the
    //     design asked for a cross-fade. A fixed tier costs zero recomposition on focus: the tier is a
    //     constant, so nothing in this subtree reads the selection at all.
    val platformIdValue = item.platformIdValue
    val shelfmarkCode = remember(platformIdValue) { mimirPlatformShelfmarkCode(platformIdValue) }
    val shelfmarkContent: @Composable () -> Unit = {
        MimirPlatformShelfmark(platformIdValue = platformIdValue, tier = MimirShelfmarkTier.FOCUSED)
    }
    // Value-if , never a composable if-without-else.
    val shelfmark = if (!showShelfmark || shelfmarkCode == null) null else shelfmarkContent

    // FUX-01 §3.4 (Cover-Art Law): the card box is GONE — no fill, no border, no rounded silhouette.
    // Focus reads through scale, the drawn radial glow, and the B2 caret the cover itself carries.
    // The glow is DRAWN (never shadowElevation — the recorded Thor graphicsLayer-alpha+shadow artifact,
    // and a shadow would paint a ghost box around frameless art).
    // PERF (CP04 follow-up): scale/alpha are PROVIDERS read inside the graphicsLayer block — role/edge/
    // context animation redraws this layer without recomposing the tile subtree. Semantics snapshot the
    // providers at composition (identical to before at settle, which is when the oracles sample).
    Box(
        modifier = Modifier
            .size(geometry.restingSide.dp)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
                // The shelf is a LEDGE: the hero grows upward from the shared bottom edge, so the
                // baseline every cover stands on never moves under focus scale.
                transformOrigin = TransformOrigin(0.5f, 1f)
                this.alpha = alpha().coerceIn(0f, 1f)
                clip = false
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                onRequestFocus()
                onActivate(item)
            }
            // The one press response (0.97 for the focus beat, no ripple): drawn inside the tap, so it never moves the hit box.
            .mimirPressResponse(interaction)
            .semantics(mergeDescendants = true) {
                contentDescription = item.title
                this.selected = selected
                this[ids.presentationAlpha] = alpha()
                this[ids.presentationScale] = scale()
                this[ids.visualSlot] = visualSlot()
            }
            // A game that has left the list is not "the tile of" that game any more; a probe for the game's tile finds nothing, and
            // the leaving one is found under its own tag.
            .testTag((if (leaving) ids.leavingTilePrefix else ids.tilePrefix) + item.focusKey.value),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // FUX-04: the selection is the Switch-style ring + bloom hugging the tile —
        // supersedes both the caret AND the FUX-02 halo on HOME. A square face is the Switch tile; a ledge cover and
        // the game article take the one ledge painter (its decoded aspect carries the box or the disc), and a face
        // with no art takes that painter's own no-art state. The shape arrives with the uri and is never re-derived.
        val squareModel = item.face.squareModel()
        // The hide's picture is taken of the selected tile's art box (its corners, its marks, nothing of the empty seat around a
        // letterboxed cover), and the box tells the stage where it is. Only the selected, staying tile pays for the layer.
        val shatterPanel = rememberLibraryShatterPanel(
            stage = shatter,
            active = selected && !leaving,
            gameId = item.stableId,
            corner = if (squareModel != null) MimirSwitchTileCorner else MimirLedgeCoverCorner,
        )
        val marks = item.marks
        val markSeat: (@Composable BoxScope.() -> Unit)? = if (paintMarks) {
            { GameShelfMarkSeat(marks) }
        } else {
            null
        }
        // The runed book sets its title at the top of the leather, so its marks ride the bottom edge (sweep 10-11).
        val bookMarkSeat: (@Composable BoxScope.() -> Unit)? = if (paintMarks) {
            { GameShelfBookMarkSeat(marks) }
        } else {
            null
        }
        when {
            // The cover's absence is not established yet: the tile paints no art (the book is a claim --) and the
            // seat, the ring and the marks stay: a favourite is a favourite whether or not its cover has been looked up, so its rune
            // and badge are seated on the tile's own square while there is no art box to ride.
            item.artPending -> markSeat?.let { seat -> Box(Modifier.fillMaxSize(), content = seat) }
            squareModel != null -> MimirSwitchTileArt(
                model = squareModel,
                title = item.title,
                focused = false,
                focusRingProgress = { 0f },
                modifier = Modifier.fillMaxSize(),
                shelfmark = shelfmark,
                overlay = markSeat,
                artModifier = shatterPanel,
            )
            else -> MimirLedgeCoverArt(
                // C2 puts the Home shelf in the Shelf state, and until 2026-08-25 this tile had NO disc
                // tier at all -- it read the cover and nothing else, so a game whose only art was a disc
                // wore a placeholder here while showing that disc one screen away. One law now, shared
                // with the Library and the Tome wells.
                model = item.face.paintedUri(),
                title = item.title,
                focused = false,
                caretProgress = 0f,
                reducedMotion = reducedMotion,
                focusRingProgress = { 0f },
                shelfmark = shelfmark,
                overlay = markSeat,
                bookOverlay = bookMarkSeat,
                // One ᛗ per book : the favourite plate IS a Mannaz, so the book's embossed one yields while the plate is painted.
                showMannaz = !(paintMarks && marks.favorite),
                artModifier = shatterPanel,
            )
        }
    }
}

/**
 * The two marks a game tile can carry, on its art (favourite, 08-28 disc badge): the favourite rune top-leading, the
 * badge top-trailing, both inset from the art box's corners so they ride them at whatever shape the cover is. The favourite is
 * always mounted and animates only draw properties, so toggling Y cannot reflow or recreate the tile.
 */
@Composable
private fun BoxScope.GameShelfMarkSeat(marks: GameShelfMarks) {
    LibraryFavoriteMarker(
        favorite = marks.favorite,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(GameShelfMarkInset)
            .testTag(GameShelfFavoriteMarkTestTag),
    )
    marks.collapseBadge?.let { badge ->
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(GameShelfMarkInset),
        ) {
            GameShelfCollapseBadge(badge)
        }
    }
}

/**
 * The same two marks on a runed BOOK (a game with no cover). The book sets its title at the top of the leather, exactly where the top corners are, and a plate there covered
 * the start of the name (sweep 10-11), so the marks ride the bottom edge: the favourite bottom-leading, on the same plate at the same [GameShelfMarkInset]; the badge
 * bottom-trailing. The embossed Mannaz stands low on the leaf, between the two, and the badge is wider than the room beside it ("2 discs" is 54 dp, the room is 32 dp
 * on a 120 dp book, and on a 100 dp book at 1.3 the badge ran over the favourite plate as well), so the badge is raised to stand on the Mannaz's top edge
 * ([GameShelfBadgeRuneAirDp] clear of it, or of the plate's top where that is higher): bottom-trailing still, covering neither the Mannaz, the plate nor the title ([mimirBookMannazTopDp]).
 *
 * One ᛗ per book : the plate is itself a Mannaz, so a favourite's embossed one is not drawn ([GameShelfTile] passes `showMannaz = false` while the plate is painted); the plate
 * stays where it is, bottom-leading.
 */
@Composable
private fun BoxScope.GameShelfBookMarkSeat(marks: GameShelfMarks) {
    LibraryFavoriteMarker(
        favorite = marks.favorite,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(GameShelfMarkInset)
            .testTag(GameShelfFavoriteMarkTestTag),
    )
    marks.collapseBadge?.let { badge ->
        BoxWithConstraints(Modifier.matchParentSize()) {
            // Clear of whichever stands higher on the book, the Mannaz's top edge or the favourite plate's: on a narrow book the plate does (the rune stands 30 dp up the RP5's 101 dp
            // book, the plate 32), and the badge ran 2 dp off it. Both are counted whether or not the favourite is set, so toggling it never moves the badge.
            val runeTop = maxHeight.value - mimirBookMannazTopDp(maxWidth.value, maxHeight.value)
            val clear = (maxOf(runeTop, GameShelfPlateTopDp) + GameShelfBadgeRuneAirDp).coerceAtLeast(GameShelfMarkInset.value)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = GameShelfMarkInset, bottom = clear.dp),
            ) {
                GameShelfCollapseBadge(badge)
            }
        }
    }
}

/** The air, in dp, between a book's badge and the top edge of whatever stands highest beneath it: the embossed Mannaz or the favourite plate. */
private const val GameShelfBadgeRuneAirDp = 4f

/** The inset of the marks from the book's edge, in dp: the plate stands on it, and [GameShelfPlateTopDp] counts it. */
private const val GameShelfMarkInsetDp = 8f

/** Where the favourite plate's top edge stands on a book, in dp up from the book's bottom: its inset ([GameShelfMarkInsetDp]) and its height. */
private const val GameShelfPlateTopDp = GameShelfMarkInsetDp + MimirMannazPinHeightDp

private val GameShelfMarkInset = GameShelfMarkInsetDp.dp

/** The favourite rune's plate on a game tile, for the tests that read where it stands. */
internal const val GameShelfFavoriteMarkTestTag = "game_shelf_favorite_mark"

/**
 * The disc / version badge on a tile, on the same etched dark plate as the favourite rune beside it (the hero's leather and hardware
 * rim, palette tokens) with the rune's brand-gold ink, so it reads on any art. The list row keeps its tinted pill ([CollapseBadge]):
 * there it stands on the dark row, and a pill tinted at 16% is invisible on a bright cover (Library render observation, 2026-10-11).
 * The plate is the rune's plate in height and corner ([MimirMannazPinHeightDp], [MimirMannazPinCornerDp]), so the two marks on one tile
 * are one size of plate; the shelf's pin has no compact height (the 16 dp one is the Tome overlay's), so neither does this.
 */
@Composable
internal fun GameShelfCollapseBadge(text: String) {
    val hero = LocalMimirPalette.current.materials.hero
    val plate = RoundedCornerShape(MimirMannazPinCornerDp.dp)
    Box(
        modifier = Modifier
            .testTag(GameShelfCollapseBadgeTestTag)
            .heightIn(min = MimirMannazPinHeightDp.dp)
            .background(Brush.verticalGradient(listOf(hero.coverTop, hero.coverBottom)), plate)
            .border(GameShelfBadgeRim, hero.hardware.copy(alpha = 0.72f), plate)
            .padding(horizontal = MimirSpacing.sm, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MimirType.Pill, color = MimirThemePalette.BrandGoldBright, maxLines = 1, softWrap = false)
    }
}

internal const val GameShelfCollapseBadgeTestTag = "game_shelf_collapse_badge"
private val GameShelfBadgeRim = 0.8.dp

/** Where a game that has left stands in the stack: above its neighbours, below whichever tile is arriving at the ring. */
private const val GameShelfLeavingStack = 0.5f

/** The picture a square Switch tile paints, or null when this face is not a square one. */
private fun ShelfFace.squareModel(): String? = uri.takeIf { shape == ShelfFaceShape.SQUARE }

/** The picture a face paints, whatever its shape; null for a face with no art, which paints the no-art state. */
private fun ShelfFace.paintedUri(): String? = uri.takeIf { shape != ShelfFaceShape.NONE }

/** Selection chrome is a fixed hero-anchor overlay; only the one identity-owned art body moves. */
@Composable
private fun GameShelfStationaryFocusRing(
    face: ShelfFace,
    geometry: GameShelfGeometry,
    visualSlotOffset: () -> Float,
    reducedMotion: Boolean,
    glint: () -> Float,
    tag: String,
    progress: () -> Float = { 1f },
) {
    Box(
        modifier = Modifier
            // The glide's seat is read in the layout lambda, so each frame of it relays out this one node and recomposes nothing.
            .offset {
                IntOffset(
                    x = (geometry.contentLeft + visualSlotOffset() * geometry.slotStride).dp.roundToPx(),
                    y = geometry.gameRailBand.top.dp.roundToPx(),
                )
            }
            .size(geometry.seatEnvelope.dp)
            .zIndex(20f)
            .testTag(tag),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .size(geometry.restingSide.dp)
                .graphicsLayer {
                    scaleX = geometry.selectedScale
                    scaleY = geometry.selectedScale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    clip = false
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Keep one ring node alive across every identity. Its inner silhouette morphs at the fixed
            // anchor between square, neutral fallback, and decoded ledge proportions instead of swapping
            // two unrelated ring trees on the logical focus commit.
            MimirLedgeFocusRingShell(
                // The ring morphs to the silhouette of what is PAINTED, so it takes the same face the tile does --
                // otherwise a disc would sit inside a ring shaped for a cover.
                model = face.paintedUri(),
                progress = progress,
                square = face.squareModel() != null,
                reducedMotion = reducedMotion,
                glint = glint,
            )
        }
    }
}

/** Tomes use the same fixed-anchor three-stroke bloom as Home game tiles, shaped to the 3:4 cover. */
@Composable
private fun GameShelfStationaryTomeFocusRing(
    geometry: GameShelfGeometry,
    visualSlotOffset: () -> Float,
    glint: () -> Float,
    tag: String,
) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (geometry.contentLeft + visualSlotOffset() * geometry.slotStride).dp.roundToPx(),
                    y = geometry.gameRailBand.top.dp.roundToPx(),
                )
            }
            .size(geometry.seatEnvelope.dp)
            .zIndex(20f)
            .testTag(tag),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .width((geometry.restingSide * MimirNorseBookAspectRatio).dp)
                .height(geometry.restingSide.dp)
                .graphicsLayer {
                    scaleX = geometry.selectedScale
                    scaleY = geometry.selectedScale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    clip = false
                }
                .mimirFocusTileRing(
                    progress = { 1f },
                    color = palette.tileFocusRing,
                    // Derived from the same helper the cover clips with, not a hardcoded value.
                    // This ring floats over the seat and draws no book of its own, so nothing here
                    // would otherwise keep it honest: at 8.dp it curved tighter than the cover
                    // wherever the cover's own radius clamped above 8, and the art crossed the ring.
                    cornerRadius = mimirNorseBookCoverCorner(
                        geometry.restingSide * MimirNorseBookAspectRatio,
                    ),
                    glint = glint,
                ),
        )
    }
}

/**
 * The finite strip has no clipped ghost covers. These non-focusable Kenaz marks truthfully replace that
 * continuation hint: left exists only when focus can move left, right only when it can move right.
 */
@Composable
private fun GameShelfContinuationRunes(
    continuation: HomeShelfContinuation,
    geometry: GameShelfGeometry,
    reducedMotion: Boolean,
    ids: GameShelfIds,
) {
    GameShelfContinuationRune(
        left = true,
        visible = continuation.left,
        geometry = geometry,
        reducedMotion = reducedMotion,
        ids = ids,
    )
    GameShelfContinuationRune(
        left = false,
        visible = continuation.right,
        geometry = geometry,
        reducedMotion = reducedMotion,
        ids = ids,
    )
}

@Composable
private fun GameShelfContinuationRune(
    left: Boolean,
    visible: Boolean,
    geometry: GameShelfGeometry,
    reducedMotion: Boolean,
    ids: GameShelfIds,
) {
    val cueWidth = 16f
    val cueHeight = 48f
    val edgeLane = geometry.contentLeft - geometry.gutter
    val cueX = if (left) {
        geometry.gutter + (edgeLane - cueWidth) / 2f
    } else {
        geometry.viewport.width - geometry.gutter - (edgeLane + cueWidth) / 2f
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = if (left) ids.leftRuneLabel else ids.rightRuneLabel,
    )
    val moreItemsDescription = stringResource(
        if (left) R.string.library_home_more_items_left else R.string.library_home_more_items_right,
    )
    val semanticModifier = if (visible) {
        Modifier.semantics { contentDescription = moreItemsDescription }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    Box(
        modifier = Modifier
            .offset(
                x = cueX.dp,
                y = (geometry.gameRailBand.top + (geometry.gameRailBand.height - cueHeight) / 2f).dp,
            )
            .size(cueWidth.dp, cueHeight.dp)
            .graphicsLayer { this.alpha = alpha }
            .then(semanticModifier)
            .testTag(if (left) ids.leftRuneTag else ids.rightRuneTag)
            .zIndex(16f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "ᚲ",
            style = MimirTypeScale.RuneAction,
            color = LocalMimirPalette.current.accentBright,
            modifier = Modifier.graphicsLayer { scaleX = if (left) 1f else -1f },
        )
    }
}
