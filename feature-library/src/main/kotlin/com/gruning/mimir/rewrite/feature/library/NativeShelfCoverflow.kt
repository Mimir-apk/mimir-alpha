package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.memory.MemoryCache
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.litFocusIn
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MediaArtFallbackGlyph
import com.gruning.mimir.rewrite.core.ui.MimirArtFailureLog
import com.gruning.mimir.rewrite.core.ui.rememberMimirArtWired
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import com.gruning.mimir.rewrite.core.ui.mimirCoverCacheKey
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import java.io.File
import kotlin.math.abs
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

@Composable
fun NativeShelfCoverflow(
    rows: List<NativeShelfRow>,
    focusedKey: FocusKey?,
    authority: FocusAuthority,
    mode: LibraryShelfMode,
    onLaunchPackage: (String) -> Unit,
    modifier: Modifier = Modifier,
    scope: FocusScope = LibraryShelfScope,
    // See ShelfCoverflow: centre on the last-centred tile (not index 0) while the focus is momentarily off-ring,
    // so a mode switch / return doesn't flash tile 0 .
    preferredCenterKey: FocusKey? = null,
    onCenter: (FocusKey) -> Unit = {},
    heroBandHeight: androidx.compose.ui.unit.Dp = NativeHeroBandHeight,
    artBandHeight: androidx.compose.ui.unit.Dp = CoverflowArtBandHeightDp.dp,
    titleBandHeight: androidx.compose.ui.unit.Dp = CoverflowTitleBandHeightDp.dp,
    tileScale: Float = 1f,
    /**
     * Walk-32h: what the tile of [packageName] adds to its face, asked of the caller for each tile with whether it is the
     * centred one. The Library gives the centred tile the shatter's capture and bounds. Composable, because that is a
     * remembered layer; the default adds nothing.
     */
    tilePanel: @Composable (isCentered: Boolean, packageName: String) -> Modifier = { _, _ -> Modifier },
) {
    val palette = LocalMimirPalette.current
    if (rows.isEmpty()) {
        Box(modifier.fillMaxWidth().height(heroBandHeight), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(
                    if (mode == LibraryShelfMode.ANDROID_GAMES) R.string.library_shelf_empty_android_games else R.string.library_shelf_empty_android_apps,
                ),
                style = MimirTypeScale.Support,
                color = palette.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val stops = remember(rows, mode) {
        rows.map { row ->
            val subtitle = row.rowKindLabel ?: if (row.isGame) "Android game" else "Android app"
            NativeCoverflowStop(
                row = row,
                key = nativeShelfKey(row.packageName),
                subtitle = subtitle,
            )
        }
    }
    val stopIndexByKey = remember(stops) { stops.mapIndexed { index, stop -> stop.key to index }.toMap() }
    val focusedInRow = focusedKey?.let { stopIndexByKey[it] }
    val focusedIndex = focusedInRow
        ?: preferredCenterKey?.let { stopIndexByKey[it] }
        ?: 0
    val focused = stops[focusedIndex]
    // RING channel — see ShelfCoverflow's identical note. [focusedKey] is conserved through a scope push so
    // the row holds its centre and its hero emphasis; the ring is computed from the live scope so it leaves
    // with input and comes back on return.
    val litKey = authority.litFocusIn(scope, authority.state.collectAsState().value)
    // One keyed body per package. Its seat and visual role retarget continuously from the current frame,
    // which prevents the former stationary-hero/translated-side overlap on warm, cold, and fallback icons.
    LaunchedEffect(focusedInRow, stops) {
        if (focusedInRow != null) onCenter(stops[focusedInRow].key)
    }
    val focusKeys = remember(stops) { stops.map { it.key } }
    RegisterVirtualFocusRow(
        authority = authority,
        scope = scope,
        keys = focusKeys,
    )
    val linearMotion = rememberCoverflowLinearMotion(
        focusedIndex = focusedIndex,
        itemCount = stops.size,
        identityOrder = focusKeys,
    )
    val mountCentre = linearMotion.mountIndex
    val focusedWindow = remember(stops, mountCentre) {
        centeredCoverflowWindow(stops, mountCentre, NativeCoverflowRenderWindowRadius)
    }
    val visibleStops = focusedWindow.visibleItems
    // Touch (CarouselTouch.kt): while a finger holds the row, or a lift is settling it, tiles fade and stack around where the
    // row is PAINTED, not where focus still is; the row's centre is the one the glide drives.
    val referenceIndex = if (linearMotion.touchEngaged) mountCentre else focusedIndex

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
                .coverflowLinearTouch(
                    motion = linearMotion,
                    itemCount = stops.size,
                    focusedIndex = focusedIndex,
                    // The seats this row places its tiles with: coverflowSlotOffsetDp's first step (integer division
                    // included, 191 dp) and its later pitch, at the row's tile scale.
                    firstNeighborDp = ((CoverflowMotionFocusWidthDp + CoverflowMotionRestWidthDp) / 2 + CoverflowMotionGapDp) * tileScale,
                    restPitchDp = (CoverflowMotionRestWidthDp + CoverflowMotionGapDp) * tileScale,
                    enabled = true,
                    onSelect = { index ->
                        // The one authority a tap and the pad reach; nothing to ask for the tile that already holds it.
                        val key = stops[index].key
                        if (focusedInRow != index) authority.requestFocus(key)
                        // Whether it now holds the tile: false when another scope has input, and the row settles back.
                        authority.state.value.focused == key
                    },
                ),
        ) {
            // See ShelfCoverflow's identical note. This row's constants
            // are LARGER (neighbour offsets 191/332/473dp) and it never had ShelfCoverflow's item-count
            // gate at all, so it is the worse half of the same reported class — bound the alpha-visibility
            // radius to the MEASURED band width. Mount/render window untouched.
            //
            // restScale here is [NativeShelfCoverflowRestScale] (0.56, which lets radius 2 fit), NOT the shared [CoverflowMotionRestScale] (0.62) — see its doc for why
            // it is a separate constant. The `nativeRestShrink` multiplier below is what actually makes the
            // paint match this bound.
            val boundedWindowRadius = remember(maxWidth, tileScale) {
                coverflowBoundedWindowRadius(
                    viewportWidthDp = maxWidth.value,
                    maxRadius = NativeCoverflowWindowRadius,
                    restWidthDp = CoverflowMotionRestWidthDp,
                    focusWidthDp = CoverflowMotionFocusWidthDp,
                    gapDp = CoverflowMotionGapDp,
                    restScale = NativeShelfCoverflowRestScale,
                    tileScale = tileScale,
                )
            }
            visibleStops.forEach { stop ->
                val isHero = stop.key == focusedKey
                val isRingLit = litKey != null && stop.key == litKey
                val stopIndex = stopIndexByKey.getValue(stop.key)
                val isCentered = stopIndex == focusedIndex
                val slotPosition = stopIndex - referenceIndex
                key(stop.key.value) {
                    // PERF-D / CP04 port (2026-09-03): the ring is read in LAYOUT/DRAW lambdas, never in
                    // this composable's body. Reading it here is what made every glide frame recompose the
                    // whole tile loop — `GamelistCarousel.kt:458-461` records the identical bug and its fix.
                    val visualPositionOf: () -> Float = { linearMotion.visualPosition(stopIndex) }
                    val targetPosition = slotPosition.toFloat()
                    val viewportAlphaTarget = coverflowViewportAlphaTarget(
                        targetPosition = targetPosition,
                        paintedRadius = boundedWindowRadius,
                    )
                    val viewportAlpha by animateFloatAsState(
                        targetValue = viewportAlphaTarget,
                        animationSpec = tween(HomeShelfOutgoingFadeMs, easing = CoverflowMotionEasing),
                        label = "native-viewport-${stop.key.value}",
                    )
                    // Composition sees the DISCRETE gate only (audit 2026-10-14, the P3 draw pass): a composition-phase copy of the animated factor (`edgeAlpha`) read it in
                    // composition, so every tile of the row recomposed on every frame of the window-edge fade. The painted alpha and the semantic are read in
                    // the layer and semantics lambdas below.
                    val tileInteractive by remember { derivedStateOf { viewportAlpha > 0.01f } }
                    val centerProgressOf: () -> Float = {
                        (1f - abs(visualPositionOf())).coerceIn(0f, 1f)
                    }
                    // an EXTRA outer multiplier,
                    // on top of the shared 0.62 rest pose PlatformHeroTile already renders internally via
                    // coverflowBasePose(bookHero = false) -- NOT a change to that shared constant, which
                    // GamelistCarousel.kt's GameCard also reads (see CoverflowMotionRestScale's doc). Floors at
                    // NativeShelfCoverflowRestScale / CoverflowMotionRestScale (~0.9032) at full rest and
                    // interpolates to 1x at centerProgress == 1, so the focused hero -- and every other
                    // coverflow surface -- is untouched; only THIS row's resting neighbours end up smaller,
                    // converging the combined true rest scale to NativeShelfCoverflowRestScale (0.56).
                    val nativeRestShrinkFloor = NativeShelfCoverflowRestScale / CoverflowMotionRestScale
                    val nativeRestShrinkOf: () -> Float = {
                        nativeRestShrinkFloor + (1f - nativeRestShrinkFloor) * centerProgressOf()
                    }
                    PlatformHeroTile(
                        label = stop.row.label,
                        gameCount = 1,
                        countLabel = stop.subtitle,
                        platformIdValue = if (stop.row.isGame) "android-games" else "android-apps",
                        iconContent = { iconModifier, _, _ ->
                            NativePackageIcon(
                                iconPath = stop.row.iconPath,
                                fallbackTitle = stop.row.label,
                                modifier = iconModifier,
                            )
                        },
                        showFavoriteStar = stop.row.isFavorite,
                        statusBadgeLabel = MimirCopy.StatusReady,
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
                        onOpen = { onLaunchPackage(stop.row.packageName) },
                        faceModifier = tilePanel(isCentered, stop.row.packageName),
                        modifier = Modifier
                            // PERF-D: the seat is read HERE, in the layout lambda — motion re-lays this
                            // node out without recomposing it. Same math and same rounding as the Dp form.
                            .offset {
                                IntOffset(
                                    (coverflowSlotOffsetDp(visualPositionOf()) * tileScale).dp
                                        .toPx().roundToInt(),
                                    0,
                                )
                            }
                            .graphicsLayer {
                                alpha = viewportAlpha
                                val shrink = tileScale * nativeRestShrinkOf()
                                scaleX = shrink
                                scaleY = shrink
                                // RG03: seam fades must not composite the tile into a bounds-sized offscreen
                                // buffer — that buffer clips the focus ring's outside-bounds bloom while the
                                // row moves. Siblings inside the tile never overlap, so per-draw modulation
                                // renders identically.
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            .semantics {
                                coverflowVisualPosition = visualPositionOf()
                                coverflowEdgeAlpha = viewportAlpha
                            }
                            .then(
                                if (isCentered) {
                                    Modifier.testTag(LibraryShelfSelectedSlotTestTag)
                                } else {
                                    Modifier.testTag(nativeShelfCoverflowTileTestTag(stop.row.packageName))
                                },
                            )
                            // PERF-D: stacking is DISCRETE from the destination seat, the rule GameCard
                            // already follows (GamelistCarousel.kt:592) — "destination owns stacking from
                            // the same commit as focus". Identical at settle; during a glide it flips with
                            // the seat instead of ramping, which is what keeps it out of composition.
                            .zIndex(1f + (1f - abs(slotPosition.toFloat())).coerceIn(0f, 1f)),
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
                    title = focused.row.label,
                    subtitle = focused.subtitle,
                    modifier = Modifier
                        .testTag(CoverflowDestinationTitleTestTag)
                        .graphicsLayer { alpha = titleAlpha.value },
                )
            }
        }
    }
}

@Composable
private fun NativePackageIcon(
    iconPath: String?,
    fallbackTitle: String,
    modifier: Modifier = Modifier,
) {
    // R8a: a blank path is no icon, and so is a path whose file is gone (the off-main verdict of [rememberMimirArtWired]); both take the
    // no-art fallback. A wired icon that fails to decode draws nothing (R8). While the stat is pending the icon counts as wired.
    if (!rememberMimirArtWired(iconPath) || iconPath.isNullOrBlank()) {
        NativeIconFallback(title = fallbackTitle, modifier = modifier)
        return
    }

    key(iconPath) {
        val context = LocalContext.current
        val request = remember(iconPath, context) {
            mimirCoverRequest(context, File(iconPath), cacheModel = iconPath)
        }
        val painter = rememberAsyncImagePainter(model = request)
        val artReady = when (val state = painter.state) {
            is AsyncImagePainter.State.Success -> true
            is AsyncImagePainter.State.Loading -> state.painter != null
            else -> false
        }
        val warmHit = remember(iconPath, context) {
            context.imageLoader.memoryCache?.get(MemoryCache.Key(mimirCoverCacheKey(iconPath))) != null
        }
        val artAlpha = remember(iconPath) { Animatable(if (warmHit) 1f else 0f) }
        LaunchedEffect(iconPath, artReady) {
            if (artReady && artAlpha.value < 1f) {
                artAlpha.animateTo(
                    1f,
                    tween(CoverflowMotionDurationMs, easing = CoverflowMotionEasing),
                )
            }
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            Image(
                painter = painter,
                contentDescription = fallbackTitle,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(NativeIconVisualHeight)
                    .clip(RoundedCornerShape(NativeIconCorner))
                    .graphicsLayer { alpha = artAlpha.value },
            )
            // R8 : the icon is wired, so a decode failure draws nothing (the fallback is for no icon path).
            MimirArtFailureLog(failed = painter.state is AsyncImagePainter.State.Error, model = iconPath)
        }
    }
}

@Composable
private fun NativeIconFallback(title: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        MediaArtFallbackGlyph(title = title, focused = true, showTitle = false, fitTitle = true)
    }
}

private data class NativeCoverflowStop(
    val row: NativeShelfRow,
    val key: FocusKey,
    val subtitle: String,
)

private val NativeHeroBandHeight = CoverflowHeroBandHeightDp.dp
private val NativeIconVisualHeight = 96.dp
private val NativeIconCorner = 18.dp

// Radius 3 (7-wide window) so edge tiles slide in/out under the band clip during the smooth glide instead of
// popping — matches the Emulation coverflow (ShelfCoverflow.CoverflowWindowRadius).
internal fun nativeShelfCoverflowTileTestTag(packageName: String): String =
    "library_native_tile_$packageName"

private const val NativeCoverflowWindowRadius = 3
private const val NativeCoverflowRenderWindowRadius = NativeCoverflowWindowRadius + 1
