package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.litFocusIn
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.derivedStateOf

/**
 * The **centered console coverflow** (VISUAL PARITY SPEC §1.3, §2). The shelf's headline surface:
 * one focused hero in a stable center seat, dimmed side art in fixed slots, and one fixed selected-title band.
 *
 * This PROMOTES the `feature-shelf-lab` prototype's centering approach (contentPadding centers item 0, so
 * scrolling item i to the row start centers item i — the prototype's `LaunchedEffect(focusedIndex) {
 * scrollToItem }`) and DRESSES it with the real hero tile ([PlatformHeroTile]) + the parity
 * caption. It does NOT own the identity header / mode strip / footer legend (`MimirShelfChrome`) —
 * only the coverflow band + its stage caption directly below the row.
 *
 * ### Focus + input (single authority — the design constraint)
 * Each hero registers with the one [FocusAuthority] via `Modifier.mimirFocusable` (INSIDE [PlatformHeroTile]);
 * there is NO Compose `focusable()` here. This composable reads [focusedKey] (the authority's current focused
 * key, passed by the host so it re-renders on every focus move) to decide which item is the hero and to
 * auto-center. Directional nav / CONFIRM are dispatched by the host (`dispatchToLibrary`); a tap opens
 * directly for touch parity , via [onOpenPlatform] (the hero
 * tile wires the tap → focus + open itself).
 *
 * Seat offsets, base scale, and alpha settle synchronously with logical focus. Ring, bloom, paint emphasis, and
 * title acknowledgement then perform a short cancellable handoff without scrolling or slot measurement changes.
 *
 * @param platforms the platforms that have games — the same shelf source as `PlatformsView`
 *   . Order-stable; keyed by [PlatformId.value].
 * @param favoritesCount legacy seam retained for call-site stability. Shipping now opens Favorites
 *   from shelf X, so production passes zero and no Favorites hero is shown.
 * @param focusedKey the authority's currently-focused key (host passes `focusState.focused`). Drives which
 *   hero is lit + the auto-center. When it matches no stop (first frame / focus elsewhere) the row parks on
 *   index 0 without lighting anything, so nothing false-lights.
 * @param authority the single focus authority; forwarded to each [PlatformHeroTile] for registration.
 * @param onOpenPlatform CONFIRM/tap on a platform opens its gamelist (host decides the route).
 * @param modifier outer modifier (the host places this band within the shelf column).
 * @param scope the focus scope the shelf runs in — forwarded to each [PlatformHeroTile.scope] so the tiles
 *   register in the SAME scope the host's shelf uses. Defaults to [CoverflowShelfScope] (`library/shelf`),
 *   which matches `LibraryScreen.LibraryShelfScope` 1:1; the integrator may pass its own `LibraryShelfScope`
 *   explicitly (it is `private` in LibraryScreen — see the manifest seam).
 * @param keyBuilder builds the stable [FocusKey] for a platform id. Defaults to the Library shelf key scheme
 *   (`library/shelf/<id>`) so a plain call matches `PlatformsView.platformKey(...)` 1:1. The integrator MAY
 *   pass `::platformKey` explicitly to share the private helper — see the manifest seam.
 * @param favoritesId the sentinel platform id the favorites lead opens (defaults to `__favorites__`, matching
 *   `LibraryViewModel.FAVORITES_PLATFORM_ID`). Passed so this file needs no dependency on the view-model.
 */
@Composable
fun ShelfCoverflow(
    platforms: List<PlatformRow>,
    favoritesCount: Int,
    focusedKey: FocusKey?,
    authority: FocusAuthority,
    onOpenPlatform: (PlatformId) -> Unit,
    modifier: Modifier = Modifier,
    scope: FocusScope = CoverflowShelfScope,
    keyBuilder: (PlatformId) -> FocusKey = ::coverflowPlatformKey,
    favoritesId: PlatformId = CoverflowFavoritesId,
    // The tile to centre when the authority focus is momentarily NOT a shelf key (the 1-frame collectAsState lag
    // as the shelf re-mounts on return) — so it lands on where you were, not tile 0. Persisted above the shelf so
    // it survives the shelf's unmount while a gamelist is open. `onCenter` reports the live centred tile back up.
    preferredCenterKey: FocusKey? = null,
    onCenter: (FocusKey) -> Unit = {},
    heroBandHeight: androidx.compose.ui.unit.Dp = HeroBandHeight,
    artBandHeight: androidx.compose.ui.unit.Dp = CoverflowArtBandHeightDp.dp,
    titleBandHeight: androidx.compose.ui.unit.Dp = CoverflowTitleBandHeightDp.dp,
    // Shelf seat solve  — Home's OWN solved seat
    // count ([mimirShelfSeatCount] evaluated at the FULL Library viewport width, the same width
    // `solveLibraryShelfGeometry` receives — NOT this composable's own narrower post-gutter band; see
    // [solvePlatformRowPlan]'s own doc for why that distinction is load-bearing). This row's window radius
    // is a THEOREM off it (`r = homeSeatCount - 2`), not a separate authored literal — replaces the former
    // hardcoded `CoverflowWindowRadius` private const. Required (no default): the one production caller
    // (`LibraryScreen.kt`) always has Home's solved count in hand via `LibraryShelfGeometry.homeSeatCount`.
    homeSeatCount: Int,
    /** Compact square: books fill the band instead of stopping at the configured 144dp (see [solvePlatformRowPlan]). */
    compact: Boolean = false,
) {
    val palette = LocalMimirPalette.current

    // The row is: platforms only in production. Build one flat, keyed, ordered list of "stops" so the
    // hero lookup, title, and center index all read the same stable sequence.
    val stops = remember(platforms, favoritesCount, favoritesId) {
        buildList {
            if (favoritesCount > 0) {
                add(
                    CoverflowStop(
                        id = favoritesId,
                        key = keyBuilder(favoritesId),
                        label = FavoritesLabel,
                        gameCount = favoritesCount,
                    ),
                )
            }
            platforms.forEach { p ->
                add(
                    CoverflowStop(
                        id = p.platformId,
                        key = keyBuilder(p.platformId),
                        label = p.label,
                        gameCount = p.gameCount,
                    ),
                )
            }
        }
    }

    if (stops.isEmpty()) {
        // No platforms with games yet: guard so an empty coverflow never trips the centering math or the hero lookup.
        // This line is the only empty state the Library draws.
        Box(modifier.fillMaxWidth().height(heroBandHeight), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.library_shelf_empty), style = MimirTypeScale.Support, color = palette.textMuted)
        }
        return
    }

    // Which stop is lit + which the caption describes. If focus is elsewhere, describe the fallback but light
    // nothing (isHero is a strict key match below), so the caption is never blank yet no tile false-lights. The
    // fallback is the LAST-centred tile (preferredCenterKey), NOT index 0 — so on return the shelf holds its place
    // through the 1-frame focus lag instead of flashing tile 0 then snapping back .
    val stopIndexByKey = remember(stops) { stops.mapIndexed { index, stop -> stop.key to index }.toMap() }
    val focusedInRow = focusedKey?.let { stopIndexByKey[it] }
    val focusedIndex = focusedInRow
        ?: preferredCenterKey?.let { stopIndexByKey[it] }
        ?: 0
    // RING channel (core-input's two-channel law). [focusedKey] is the CONSERVED key: the host passes a value
    // that survives a scope push, which is what keeps this row centred and the hero emphasised while the dock
    // or Utility owns input. The ring is the other half of that two-channel focus rule and must NOT survive it, so it is
    // computed here from the live scope rather than taken from the host's key.
    val litKey = authority.litFocusIn(scope, authority.state.collectAsState().value)
    val focused = stops[focusedIndex]
    // Each keyed stop owns one continuous visual position. A focus commit retargets that value from the
    // currently painted frame; there is no separate stationary hero and no translated copy underneath it.
    // Report the centred tile up whenever it reflects a real focus, so the fallback above has an accurate target.
    LaunchedEffect(focusedInRow, stops) {
        if (focusedInRow != null) onCenter(stops[focusedInRow].key)
    }
    val focusKeys = remember(stops) { stops.map { it.key } }
    RegisterVirtualFocusRow(
        authority = authority,
        scope = scope,
        keys = focusKeys,
    )
    // the Library PLATFORM carousel is circular. Keep the complete logical ring in
    // FocusAuthority, unwrap the painted centre across the terminal seam, and mount only a bounded cyclic
    // window. Home, gamelists, and native Android rows keep their separate finite navigation rules.
    val ringMotion = rememberCoverflowRingMotion(
        focusedIndex = focusedIndex,
        itemCount = stops.size,
        identityOrder = focusKeys,
        skipEvenSeamPreparation = true,
    )
    // Shelf seat solve §3b: the platform row's window radius is Home's own solved seat count minus two —
    // 1 (3 tiles) when Home solves 3, 2 (5 tiles) when Home solves 4. `coerceAtLeast(1)` is a defensive
    // floor only; mimirShelfSeatCount never returns below HomeShelfCompactSeatCount (3), so this never binds
    // for any real caller — kept because a radius of 0 would break the mount-buffer math below.
    val windowRadius = (homeSeatCount - 2).coerceAtLeast(1)
    val renderWindowRadius = windowRadius + 1
    // PERF-D / CP04 port (2026-09-03): `center.value` is a per-frame Animatable read, but the value this
    // derives is the ROUNDED seat, which changes once per stop. Read raw, it subscribed this whole
    // composable to every glide frame. `derivedStateOf` collapses that to the transitions that matter.
    // **Keyed on `ringMotion`, NOT on `stops.size` — the instance is the identity that matters.**
    // `rememberCoverflowRingMotion` recreates the state on `identityOrder` too
    // (`CoverflowMotion.kt:343`), and its own comment says why: *"A same-sized reorder is a different
    // ring."* Keyed on size alone, this closure would keep the FIRST composition's motion after such a
    // reorder — the driver `LaunchedEffect` is re-keyed on `motion`, so the captured Animatable freezes
    // and the derived value never invalidates again. The window would stop following the ring, and the
    // focused tile would simply stop being painted once focus left the stale window. Caught in review
    // before it reached a second build; the pre-derivedStateOf inline read was immune because it
    // re-read whatever `ringMotion` currently was.
    val mountCentre by remember(ringMotion, stops.size) {
        derivedStateOf { Math.floorMod(ringMotion.center.value.roundToInt(), stops.size) }
    }
    val focusedWindow = remember(stops, mountCentre, renderWindowRadius) {
        circularCoverflowWindow(stops, mountCentre, renderWindowRadius)
    }
    // Spec §5: when the whole ring already fits inside the solved window and its count is EVEN, the ring's
    // modulo wrap ties the antipode to one side at every settled stop — nothing left to slide, so the row
    // renders a STATIC centred strip (all stops, fixed symmetric offsets, focus emphasised in place) instead
    // of a sliding ring. Odd full rings stay a sliding ring (already symmetric at every stop). This does NOT
    // change focus traversal, registration, or wrap — RegisterVirtualFocusRow/ringMotion/mountCentre above
    // are computed exactly as before regardless of this branch; only which stops PAINT, and where, differs.
    val staticStrip = coverflowStaticStripEligible(itemCount = stops.size, windowRadius = windowRadius)
    val visibleStops = if (staticStrip) stops else focusedWindow.visibleItems
    // Touch (CarouselTouch.kt): the seat spacing this row solves, written by the band below and read by the drag, which is
    // built out here because the gesture belongs to the band's own node.
    val touchSpacing = remember { CoverflowTouchSpacing() }
    // per-platform SGB icon-art overrides (the "Y → change platform art" flow). Written by the
    // app layer, read here to swap a tile's drawn console glyph for the picked art. Empty = every tile default.
    val platformArtOverrides = PlatformTileArtStore.art.collectAsState().value
    val platformArtHydrated = PlatformTileArtStore.hydrated.collectAsState().value

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(heroBandHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(artBandHeight)
                // A static strip never moves, even for the pad, so there is nothing for a finger to carry.
                .coverflowRingTouch(
                    motion = ringMotion,
                    itemCount = stops.size,
                    focusedIndex = focusedIndex,
                    spacing = touchSpacing,
                    enabled = !staticStrip,
                    onSelect = { index ->
                        // The one authority a tap and the pad reach; nothing to ask for the tile that already holds it.
                        val key = stops[index].key
                        if (focusedInRow != index) authority.requestFocus(key)
                        // True only when this lift moved focus onto the tile. False when it was already there, or when the
                        // authority refused (another scope has input): the ring then settles back on the focused tile.
                        focusedInRow != index && authority.state.value.focused == key
                    },
                ),
        ) {
            // Shelf seat solve  — seat COUNT is decided
            // once by [windowRadius] (Home's own inherited count, see its declaration above); seat SIZE then
            // solves to FILL the measured band, capped at the configured 144dp tile (or the art band's own
            // physical limit, whichever binds tighter — see [solvePlatformRowPlan]/[coverflowBookSeatCapDp]).
            // [coverflowBoundedWindowRadius] below is no longer the count MECHANISM — it is a GUARD, asserted
            // (by `PlatformRowSeatSolveTest`) to never actually clip any certified viewport, kept wired as a
            // defensive backstop for an uncertified one. Previously this fed the fixed 144/20 cap constants
            // and a literal `maxRadius=1`; both are now the solved plan and [windowRadius].
            val plan = remember(maxWidth, artBandHeight, homeSeatCount, compact) {
                solvePlatformRowPlan(
                    bandDp = maxWidth.value,
                    homeSeatCount = homeSeatCount,
                    artBandHeightDp = artBandHeight.value,
                    authoredSeatCapDp = if (compact) null else CoverflowMotionBookFocusWidthDp.toFloat(),
                )
            }
            SideEffect {
                touchSpacing.firstNeighborDp = plan.seatDp + plan.gapDp
                touchSpacing.restPitchDp = plan.seatDp + plan.gapDp
            }
            val boundedWindowRadius = remember(maxWidth, plan, windowRadius) {
                coverflowBoundedWindowRadius(
                    viewportWidthDp = maxWidth.value,
                    maxRadius = windowRadius,
                    restWidthDp = plan.seatDp,
                    focusWidthDp = plan.seatDp,
                    gapDp = plan.gapDp,
                    restScale = CoverflowMotionBookRestScale,
                )
            }
            // RC-19 half 1  — the painted radius above is
            // symmetric by construction, but the RING's own modulo wrap (coverflowRingVisualPosition's doc)
            // can tie the antipode to one side once that radius reaches a full lap, painting one more tile
            // on one side than the other even though the focused tile stays dead-centre. leftRadius /
            // rightRadius are the farthest position actually PAINTED on each side under the SAME alpha gate
            // used below (targetPosition <= boundedWindowRadius) — no re-derivation of which tiles paint,
            // only of where the group sits. See coverflowRowRecenterOffsetDp's own doc for the full
            // mechanism and why it is provably never wider than the uncorrected row. Unused (and harmless)
            // for [staticStrip] rows — a static strip's offsets are built directly from index, never the
            // ring's wrap, so there is nothing asymmetric left to correct there.
            val (recenterLeftRadius, recenterRightRadius) = remember(focusedIndex, stops.size, boundedWindowRadius) {
                var left = 0
                var right = 0
                for (index in stops.indices) {
                    val position = coverflowRingVisualPosition(
                        itemIndex = index,
                        visualCenter = focusedIndex.toFloat(),
                        itemCount = stops.size,
                    )
                    if (abs(position) <= boundedWindowRadius.toFloat()) {
                        when {
                            position < 0f -> left = maxOf(left, (-position).roundToInt())
                            position > 0f -> right = maxOf(right, position.roundToInt())
                        }
                    }
                }
                left to right
            }
            val recenterTargetDp = coverflowRowRecenterOffsetDp(
                leftRadius = recenterLeftRadius,
                rightRadius = recenterRightRadius,
                restWidthDp = plan.seatDp,
                focusWidthDp = plan.seatDp,
                gapDp = plan.gapDp,
                restScale = CoverflowMotionBookRestScale,
            )
            // Animated, not snapped straight to target: the ring's split flips (1-left/2-right <->
            // 2-left/1-right) on ordinary focus moves, which would otherwise step this correction a full
            // restWidthDp+gapDp in one frame. Same house tween the per-tile viewportAlpha below already
            // uses, so the recentring settles alongside the tiles' own glide instead of fighting it.
            val recenterOffsetDp by animateFloatAsState(
                targetValue = recenterTargetDp,
                animationSpec = tween(HomeShelfOutgoingFadeMs, easing = CoverflowMotionEasing),
                label = "emulation-row-recenter",
            )
            val bookFocusWidthDp = plan.seatDp.dp
            val bookFocusHeightDp = (plan.seatDp * CoverflowMotionBookFocusHeightDp / CoverflowMotionBookFocusWidthDp).dp
            visibleStops.forEach { stop ->
                val isHero = stop.key == focusedKey
                val isRingLit = litKey != null && stop.key == litKey
                val stopIndex = stopIndexByKey.getValue(stop.key)
                val isCentered = stopIndex == focusedIndex
                key(stop.key.value) {
                    // Spec §5 static strip: fixed, symmetric-by-construction offsets from the CENTROID of the
                    // set (see coverflowStaticStripPosition's own doc for why this, not an integer window
                    // anchor, is required in this centre-anchored band) instead of the ring's continuous,
                    // wrap-aware position. No recenter correction: there is no wrap to correct.
                    // PERF-D / CP04 port: read in LAYOUT/DRAW lambdas, never in this body. Reading it here
                    // is what made every glide frame recompose the tile loop — `GamelistCarousel.kt:458-461`
                    // records the identical bug and the fix this mirrors.
                    val visualPositionOf: () -> Float = if (staticStrip) {
                        { coverflowStaticStripPosition(stopIndex, stops.size) }
                    } else {
                        { ringMotion.visualPosition(stopIndex, stops.size) }
                    }
                    val viewportAlphaTarget = if (staticStrip) {
                        // Every stop in a static strip is always fully painted — there is no window edge to
                        // fade at, the whole set already fits.
                        1f
                    } else {
                        // While a finger holds the ring, or a lift is settling it, the window of tiles that paint is the one
                        // around where the ring is PAINTED, not around focus (which only moves at the lift).
                        val targetPosition = coverflowRingVisualPosition(
                            itemIndex = stopIndex,
                            visualCenter = (if (ringMotion.touchEngaged) mountCentre else focusedIndex).toFloat(),
                            itemCount = stops.size,
                        )
                        // The continuous `edgeAlphaTarget(visualPosition)` factor USED to be multiplied in
                        // here, which retargeted this tween on every frame of a glide. It now applies in the
                        // draw lambda below instead: same product, but the tween animates only the discrete
                        // window factor it was written for. `edgeAlphaTarget` is itself smooth in position,
                        // so applying it per-frame needs no easing of its own.
                        coverflowViewportAlphaTarget(
                            targetPosition = targetPosition,
                            paintedRadius = boundedWindowRadius,
                        )
                    }
                    val viewportAlpha by animateFloatAsState(
                        targetValue = viewportAlphaTarget,
                        animationSpec = tween(HomeShelfOutgoingFadeMs, easing = CoverflowMotionEasing),
                        label = "emulation-viewport-${stop.key.value}",
                    )
                    // Composition sees the DISCRETE gate only (audit 2026-10-14, the P3 draw pass): a composition-phase copy of the animated factor (`edgeAlpha`) read it in
                    // composition, so every tile of the row recomposed on every frame of the window-edge fade. The painted alpha is the deferred
                    // product below, read in the layer.
                    val tileInteractive by remember { derivedStateOf { viewportAlpha > 0.01f } }
                    val edgeAlphaOf: () -> Float = if (staticStrip) {
                        { viewportAlpha }
                    } else {
                        { viewportAlpha * ringMotion.edgeAlphaTarget(visualPositionOf(), stops.size) }
                    }
                    // Static strip: focus is emphasised IN PLACE — a binary target per tile identity,
                    // animated with the same 140ms house tween [PlatformHeroTile] uses for its own bloom/ring
                    // emphasis. PlatformHeroTile's internal Animatable only drives THAT (focusProgress); its
                    // base `tileAlpha` reads `centerProgress` directly (`coverflowBasePose`'s own `alpha`), so
                    // a raw binary target here would snap the rest<->hero alpha in one frame instead of
                    // crossfading like the sliding ring does. Sliding ring: the continuous distance-derived
                    // progress, unchanged — already smooth because it rides the ring's own frame-by-frame
                    // glide; adding a second animateFloatAsState layer on top of that would decouple a tile's
                    // position from its pose during a fast slide, so this animation applies ONLY here.
                    val staticCenterProgress = animateFloatAsState(
                        targetValue = if (isCentered) 1f else 0f,
                        animationSpec = tween(CoverflowMotionDurationMs, easing = CoverflowMotionEasing),
                        label = "emulation-static-center-${stop.key.value}",
                    )
                    // Deferred: `.value` is read inside the consuming lambda, not here, so the static
                    // strip's own tween no longer recomposes the tile either.
                    val centerProgressOf: () -> Float = if (staticStrip) {
                        { staticCenterProgress.value }
                    } else {
                        { (1f - abs(visualPositionOf())).coerceIn(0f, 1f) }
                    }
                    // RC-19 half 1: the whole row (focused tile included) carries the same recentring
                    // correction, so the group's painted bounding box centres in the band even when the
                    // ring's wrap paints one more neighbour on one side than the other. The focused tile's
                    // own logical seat (visualPosition 0) is unaffected relative to its neighbours — only
                    // the row's placement inside the band shifts. The seat solve already sizes/spaces this
                    // row to fill the band exactly, so no outer tileScale multiplier applies any more (see
                    // solvePlatformRowPlan's own doc for why that would double-shrink the compact-square
                    // rows whose art band caps the seat below the configured 144dp).
                    val slotOffsetDpOf: () -> Float = {
                        coverflowSlotOffsetDp(
                            position = visualPositionOf(),
                            restWidthDp = plan.seatDp,
                            focusWidthDp = plan.seatDp,
                            gapDp = plan.gapDp,
                        ) + (if (staticStrip) 0f else recenterOffsetDp)
                    }
                    // Lane L4's hero tile owns the §2 geometry (ring+glow, radial fill), the §4 console
                    // line-art icon, the ᛗ Mannaz mark, its OWN authority registration + tap→open. We pass
                    // data + focus flag + scope/key + open hook. bookHero=true: this is the EMULATION shelf.
                    val platformArtUri = platformArtOverrides[stop.id.value]
                    PlatformHeroTile(
                        label = stop.label,
                        gameCount = stop.gameCount,
                        platformIdValue = stop.id.value,
                        // if the user picked SGB art for this platform (Y → change art), COVER the
                        // whole tile with it (rounded to the tile corner, cropped to keep aspect — not a small centred
                        // glyph); otherwise the default iconContent draws the console line-art.
                        overrideCoverArt = platformArtUri,
                        suppressDefaultArtWhileHydrating = !platformArtHydrated,
                        bookHero = true,
                        bookFocusWidthDp = bookFocusWidthDp,
                        bookFocusHeightDp = bookFocusHeightDp,
                        showLabels = false,
                        focused = isHero,
                        ringLit = isRingLit,
                        centered = isCentered,
                        centerProgress = centerProgressOf,
                        authority = authority,
                        key = stop.key,
                        scope = scope,
                        registerPhysicalFocus = false,
                        autoBringIntoView = false,
                        interactive = tileInteractive || isCentered,
                        onOpen = { onOpenPlatform(stop.id) },
                        modifier = Modifier
                            // PERF-D: the seat is read HERE, in the layout lambda — motion re-lays this
                            // node out without recomposing it. Same math, same rounding at layout.
                            .offset { IntOffset(slotOffsetDpOf().dp.toPx().roundToInt(), 0) }
                            .graphicsLayer {
                                alpha = edgeAlphaOf()
                                // The seat solve already produces the real, viewport-solved box size
                                // (bookFocusWidthDp/HeightDp above) — an outer visual scale would either be a
                                // no-op (1x) or, worse, double-shrink the compact-square rows. See this
                                // block's own comment above the slotOffset computation.
                                scaleX = 1f
                                scaleY = 1f
                                // RG03: seam fades must not composite the tile into a bounds-sized offscreen
                                // buffer — that buffer clips the focus ring's outside-bounds bloom while the
                                // row moves. Siblings inside the tile never overlap, so per-draw modulation
                                // renders identically.
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            .semantics {
                                coverflowVisualPosition = visualPositionOf()
                                coverflowEdgeAlpha = edgeAlphaOf()
                            }
                            .then(
                                if (isCentered) {
                                    Modifier.testTag(LibraryShelfSelectedSlotTestTag)
                                } else {
                                    Modifier.testTag(shelfCoverflowTileTestTag(stop.key))
                                },
                            )
                            // PERF-D: stacking is DISCRETE from the destination seat — the rule GameCard
                            // already follows (`GamelistCarousel.kt:592`), "destination owns stacking from
                            // the same commit as focus". Identical at settle; during a glide it flips with
                            // the seat rather than ramping, which is what keeps it out of composition.
                            .zIndex(1f + if (isCentered) 1f else 0f),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(titleBandHeight)
                .padding(horizontal = MimirSpacing.xxl),
            contentAlignment = Alignment.Center,
        ) {
            key(focused.key.value) {
                val titleAlpha = remember { Animatable(CoverflowTitleStartAlpha) }
                LaunchedEffect(Unit) {
                    titleAlpha.animateTo(
                        1f,
                        animationSpec = tween(CoverflowTitleDurationMs, easing = MimirMotion.Easing),
                    )
                }
                ShelfHeroTitle(
                    title = focused.label,
                    subtitle = libraryResources().getQuantityString(R.plurals.library_shelf_games_count, focused.gameCount, focused.gameCount),
                    modifier = Modifier
                        .testTag(CoverflowDestinationTitleTestTag)
                        .graphicsLayer { alpha = titleAlpha.value },
                )
            }
        }
    }
}

// ── Model + tokens ──────────────────────────────────────────────────────────────────────────────────

/**
 * One stop in the coverflow. Hero lookup, title, and center index all read this one ordered sequence.
 */
private data class CoverflowStop(
    val id: PlatformId,
    val key: FocusKey,
    val label: String,
    val gameCount: Int,
)

/**
 * The DEFAULT shelf focus scope — `library/shelf`, identical to `LibraryScreen.LibraryShelfScope` — so the
 * coverflow's tiles register in the same scope the host shelf uses and directional nav resolves one graph.
 * The integrator may override via the `scope` param (LibraryShelfScope is `private` in LibraryScreen).
 */
internal val CoverflowShelfScope: FocusScope = FocusScope("library/shelf")

/**
 * The DEFAULT shelf focus-key scheme — `library/shelf/<id>`, identical to `PlatformsView.platformKey(...)` —
 * so the coverflow's registered keys match the flat shelf's 1:1. The integrator may override via `keyBuilder`.
 */
internal fun coverflowPlatformKey(id: PlatformId): FocusKey = FocusKey("library/shelf/${id.value}")

/** Legacy sentinel retained for tests/previews that still exercise the old favorites lead seam. */
internal val CoverflowFavoritesId: PlatformId = PlatformId("__favorites__")

/** Legacy favorites label for the old coverflow lead seam. */
private const val FavoritesLabel = "★ Favorites"

// ── Geometry (spec §2) ────────────────────────────────────────────────────────────────────────────
// Shared with NativeShelfCoverflow so switching modes cannot move the center seat or selected-title baseline.
private val HeroBandHeight = CoverflowHeroBandHeightDp.dp

// The MOUNT/render window is always one wider than the PAINT window (below) so the tiles entering/leaving
// at the edges do so one extra slot out — they slide in under the band clip during the smooth glide instead
// of popping into existence at the visible edge.
internal fun shelfCoverflowTileTestTag(key: FocusKey): String =
    "library_emulation_tile_${key.value}"

// The former fixed `CoverflowWindowRadius`/`CoverflowRenderWindowRadius` literals (RC-19 v2: 1, "try using
// 3 tiles instead") are RETIRED — the shelf seat solve
//  makes the window radius a THEOREM off Home's
// own solved seat count (`r = homeSeatCount - 2`) instead of a hand-picked literal, computed as
// `windowRadius`/`renderWindowRadius` locals inside [ShelfCoverflow] itself (see their declaration for the
// full reasoning). Do not reintroduce a fixed top-level radius constant here — that is exactly the
// fixed device cap excluded by the viewport solve: *Seat count follows viewport size and aspect ratio rather than a device-model cap*
