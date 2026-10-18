package com.gruning.mimir.rewrite.feature.library

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.mimirFocusRingSpec
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import kotlin.math.abs
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.ImageLoader
import coil.imageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirPlatformShelfmarkCode
import com.gruning.mimir.rewrite.core.ui.MediaArtBookCover
import com.gruning.mimir.rewrite.core.ui.MimirPageRunes
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirFocusTileRing
import com.gruning.mimir.rewrite.core.ui.mimirGlassPanelEntry
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirNorseBookFrame
import com.gruning.mimir.rewrite.core.ui.mimirSurfaceShadow
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val HomeTomeFolioTestTag = "home_tome_folio"
const val HomeTomeFolioPanelViewportTestTag = "home_tome_folio/panel-viewport"

/** F13: an occupied folio cell whose game has no art wears this tag on its title book. */
internal const val HomeTomeFolioTitleBookTestTag = "home_tome_folio/title-book"
private const val HomeTomeCarryScale = 1.06f
private const val HomeTomeCarryLiftDp = 4f

/** Current pixel geometry read at each pointer event without restarting the gesture owner. */
private data class HomeTomeTouchGeometry(
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val gapPx: Float,
    val gridWidthPx: Float,
    val gridHeightPx: Float,
    val ringClearancePx: Float,
    val visibleColumns: Int,
    val rowWindowStart: Int,
    val visibleRows: Int,
)

/**
 * Large Tome destination. The folio, header, footer, and 6x3 square bed are immutable envelopes; focus and
 * Arrange only change tint/graphics state. Controller dispatch remains app-owned through [onArrangeAction].
 */
@Composable
fun HomeTomeFolio(
    state: HomeTomeFolioUiState,
    authority: FocusAuthority,
    onFocusMember: (Int) -> Unit,
    onOpenMember: (HomeTomeMember) -> Unit,
    onArrangeAction: (ControllerAction) -> Unit,
    /** Phase-B compatibility seam; [onBack] is the explicit final contract. */
    onDismiss: () -> Unit = {},
    onEditMember: (HomeTomeMember) -> Unit = {},
    onBack: () -> Unit = onDismiss,
    onGeneralAction: (ControllerAction) -> Unit = {},
    onTouchArrangeIntent: (HomeTomeArrangeIntent) -> Unit = {},
    onFocusSettled: (HomeTomeFolioFocusTarget) -> Unit = {},
    /**
     * Fired synchronously with every authoritative focus target/scope change so the app seam can
     * clear its settled latch BEFORE the next activation can observe stale settlement (the same
     * contract the Tome Library carousel already exposes).
     */
    onFocusUnsettled: () -> Unit = {},
    onFirstInteraction: () -> Unit,
    reducedMotion: Boolean = false,
    /**
     * The house glass materialisation clock, 0 → 1, supplied by the host. **Null keeps a static panel**,
     * so an unwired host (tests, previews) composes without any transition at all.
     *
     * **Why this surface needed it, in one constant.** The glass scrim over this surface resolves to
     * `MimirGlass.ScrimAlphaBlurred` = **0.52f**, and that constant's own KDoc says what it assumes:
     * *"The old recipe used 0.85 because it had to hide an unblurred scene by brute force. With a real
     * blur the scrim only needs to buy contrast, so the scene stays visible through the glass."* The
     * folio wears the thin scrim and **the scene behind it was never blurred** — it is a
     * destination-internal surface, not a `MimirOverlayKind`, so the host's scene-side ramp
     * (`MimirAppHost`'s `mimirGlassBackdropProgress`, gated on `mimirOverlayUsesGlass`) had never covered
     * it. Half a glass surface: panel and scrim, no backdrop. This briefly exposed the sharp Home shelf through the opening Folio.
     *
     * **One clock, and it is the host's.** The scene half must be applied to `HomeShelfScreen`, which is
     * this composable's SIBLING under the host's `Box` — so the progress cannot be created here without
     * being a second clock against the scene's, which this tree rules against by name
     * (`BrightnessOverlay.kt:480`, *"a second `rememberMimirGlassProgress` here would be two clocks on
     * one surface, and they drift"*). The host creates it, blurs the sibling with it, and passes it
     * here; the scrim's alpha, the panel's alpha and the panel's lift all read the same lambda.
     *
     * **The SCRIM is no longer painted here, and that is deliberate**. It used to be a `drawBehind` on this
     * composable's own root, which meant it **died on the commit frame the session ended** — a 0.52
     * luminance step in one frame, underneath a ramp, and on API 30 (`MimirGlass.supportsBlur` false)
     * the scrim is the ENTIRE transition, so that step was the whole return. A cover cannot be owned by
     * the thing it is covering for. The host now paints it as a sibling between the covered shelf and
     * this panel — identical stacking, identical colour, identical clock — so it survives this
     * composition and unwinds with the blur instead of vanishing with the panel.
     *
     * **Read only inside draw blocks.** Every consumer below is a `drawBehind` or a `graphicsLayer`, per
     * the primitive's own contract — a per-frame value read at composition scope would recompose the
     * whole folio every frame instead of invalidating draw.
     *
     * Reduced motion is the HOST's decision, not this parameter's: it supplies a progress that is
     * already settled at 1f, so the glass is a settled STATE here rather than suppressed motion. The
     * surface still looks like glass with reduced motion on; it simply does not travel to get there.
     */
    glassProgress: (() -> Float)? = null,
    /**
     * This composition has **already lost its focus scope** and is being drawn only so that its pixels
     * can be captured for the departure.
     * Exactly one frame ever wears it, and the host — not this composable — decides which one.
     *
     * What it changes is the RING channel and nothing else: the member ring, and the teaching plate's
     * ring, go out **on this frame's draw**, not over `MimirMotion.FocusMs`, because the recorded image
     * is a still and a half-faded ring is just a dimmer ghost. Everything stateful the surface derives
     * from focus — the footer's focused-member title, a carried cell's selection, the strip's geometry —
     * is CONSERVED, so the recorded frame is the frame the player was already looking at minus the ring.
     * Both halves are named and unit-pinned: [homeTomeFolioRingLit] and [homeTomeFolioStateChannelLit].
     *
     * Defaults to false, so every caller that never departs (tests, previews) is byte-identical.
     */
    departing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // App owns routing and persistence; remembered callbacks let a live touch gesture survive recomposition.
    val currentTouchArrangeIntent by rememberUpdatedState(onTouchArrangeIntent)
    val currentFirstInteraction by rememberUpdatedState(onFirstInteraction)
    val currentFocusSettled by rememberUpdatedState(onFocusSettled)
    val currentFocusUnsettled by rememberUpdatedState(onFocusUnsettled)
    val currentFocusMember by rememberUpdatedState(onFocusMember)
    val currentFolioState = rememberUpdatedState(state)
    val focusState by authority.state.collectAsState()
    val expectedFocusTarget = remember(
        state.tome.tomeId,
        state.memberSlots,
        state.focusedIndex,
    ) {
        homeTomeFolioFocusTarget(
            tomeId = state.tome.tomeId,
            memberSlots = state.memberSlots,
            focusedIndex = state.focusedIndex,
            mode = state.mode,
        )
    }
    // An A->B->A burst must never reuse A's stale settlement: every authoritative target/scope
    // change invalidates first; only the new target's motion-true settlement re-opens activation.
    LaunchedEffect(focusState.focused, focusState.scope) {
        currentFocusUnsettled()
    }
    val virtualFocusSlots = remember(state.tome.tomeId, state.memberSlots) {
        homeTomeVirtualFocusSeatSlots(
            tomeId = state.tome.tomeId,
            memberSlots = state.memberSlots,
        )
    }
    RegisterVirtualFocusSlots(
        authority = authority,
        scope = HomeTomeFolioScope,
        slots = virtualFocusSlots,
    )
    // Content-aware travel (policy): the strip scrolls with the active third/later column under
    // Home's settled-window law, exactly. The window itself is solved inside the geometry pass, where
    // the compact column count is known.
    val columnCount = homeTomeFolioColumnCount(state.memberSlots.size)
    val authoritySeatIndex = homeTomeFolioAuthoritativeSeatIndex(
        slots = virtualFocusSlots,
        activeScope = focusState.scope,
        activeKey = focusState.focused,
    )
    val visualFocusedIndex = homeTomeFolioVisualSeatIndex(
        slots = virtualFocusSlots,
        activeScope = focusState.scope,
        activeKey = focusState.focused,
        reducerFocusedIndex = state.focusedIndex,
    )
    val focusedColumn = homeTomeFolioColumnOfIndex(visualFocusedIndex)

    // Cover art attaches on the frame AFTER the folio opens, not on the open frame itself.
    //
    // Measured on the Thor 2026-09-04, five interleaved runs per arm: one Coil `AsyncImagePainter`
    // per mounted cell costs **12.2 ms** of the open's ~45 ms `record` phase (t = -9.3) -- about
    // 0.6 ms a cell, spread across Coil's request and interceptor machinery with no single hotspot
    // to attack. And `record` on this app is not display-list recording: it is Compose's measure
    // pass running inside `dispatchDraw` (`tools/framestats/README.md` section 5), so those painters
    // are built inside the one serial ~82 ms pass that is felt as a hitch at the press.
    //
    // Deferring cannot flash the NO-ART placeholder, which would be a worse trade than the 12 ms:
    // the cell keeps its `artUri` and loses only the painter, so `homeTomeFolioArtShowsPlaceholder`
    // stays FALSE and `homeTomeFolioArtShowsQuietBackdrop` stays TRUE -- the same quiet backdrop a
    // cover already paints while Coil loads it. For art that is not already in memory this changes
    // nothing whatever: Coil was going to deliver on a later frame either way.
    //
    // Keyed on the tome, so opening a different tome defers its art again -- that open mounts new
    // cells and pays the same cost.
    var folioArtAttached by remember(state.tome.tomeId) { mutableStateOf(false) }
    LaunchedEffect(state.tome.tomeId) {
        withFrameNanos { }
        folioArtAttached = true
    }
    LaunchedEffect(state.tome.tomeId, expectedFocusTarget) {
        val effectTarget = expectedFocusTarget ?: return@LaunchedEffect
        val latest = currentFolioState.value
        val latestTarget = homeTomeFolioFocusTarget(
            tomeId = latest.tome.tomeId,
            memberSlots = latest.memberSlots,
            focusedIndex = latest.focusedIndex,
            mode = latest.mode,
        )
        val current = authority.state.value
        if (shouldRepairHomeTomeFolioFocus(
                effectTarget.key,
                latestTarget?.key,
                current.scope,
                current.focused,
            )
        ) {
            authority.requestFocus(effectTarget.key)
        }
    }
    HomeTomeAdjacentArtPrewarm(
        members = state.members,
        pageIndex = state.pageIndex,
    )
    HomeTomeFocusedBackdropPrewarm(
        memberSlots = state.memberSlots,
        focusedIndex = state.focusedIndex,
    )
    var suppressActivationUntilMillis by remember { mutableLongStateOf(0L) }
    var touchDragPosition by remember(state.tome.tomeId) { mutableStateOf<Offset?>(null) }
    var touchGestureGeneration by remember(state.tome.tomeId) { mutableLongStateOf(0L) }
    var pendingDropDestination by remember(state.tome.tomeId) {
        mutableStateOf<HomeTomeFolioDropDestination?>(null)
    }

    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    // The two-channel law, resolved ONCE for the whole surface so the ring and the state it decorates can
    // never disagree. `scopeOwnsFolio` is the live read; the two derivations are unit-pinned in
    // HomeShelfScreen.kt beside `homeShelfLiveChromeLit`, which is the same law at the opening edge.
    //
    // Geometry deliberately still reads `focusState` directly below (the seat solvers, the settlement
    // effect): a departing frame must not MOVE, and the reducer index is exactly the fallback those
    // solvers already take when the scope is not the folio's.
    val scopeOwnsFolio = focusState.scope == HomeTomeFolioScope
    val folioRingLit = homeTomeFolioRingLit(scopeOwnsFolio = scopeOwnsFolio, departing = departing)
    val folioStateLit = homeTomeFolioStateChannelLit(scopeOwnsFolio = scopeOwnsFolio, departing = departing)
    // The scrim is the HOST's now (see [glassProgress]'s kdoc): it has to outlive this composition, and a
    // cover owned by the thing it covers for cannot. `MimirAppHost` paints it as this composable's sibling
    // over the covered shelf, on the same lambda the panel below rides.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag(HomeTomeFolioTestTag),
        contentAlignment = Alignment.Center,
    ) {
        val folioWidth = maxWidth * 0.90f
        val folioHeight = maxHeight * 0.90f
        MimirLiquidGlassPanel(
            modifier = Modifier
                .width(folioWidth)
                .height(folioHeight)
                // Panel alpha + the 0.96 -> 1.0 lift, same clock. This is a PANEL floating over a scene,
                // not a full-screen surface, so it takes the lift rather than `mimirGlassSurfaceEntry`'s
                // alpha-only treatment (4% reads as a lift on a panel and as a
                // zoom when the thing scaling is the whole screen — the folio is 90% x 90%, a panel).
                .then(
                    if (glassProgress == null) Modifier else Modifier.mimirGlassPanelEntry(glassProgress),
                ),
            contentPadding = 0.dp,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize(),
            ) {
                val viewport = rememberMeasuredViewport(
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    inDomain = { widthDp, heightDp ->
                        widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
                    },
                    beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
                )
                if (!viewport.shouldRender) return@BoxWithConstraints

                // The inner boundary has multiple existing top-level children, so this always-present
                // wrapper carries the measured size and Norse frame without changing their layout.
                Box(
                    modifier = Modifier
                        .mimirMeasuredViewport(viewport)
                        .fillMaxSize()
                        .testTag(HomeTomeFolioPanelViewportTestTag)
                        .mimirNorseBookFrame(palette),
                ) {
                val outer = viewport.width * 0.045f
                val density = LocalDensity.current
                val verticalPartitions = remember(viewport.height.value, density.fontScale) {
                    solveHomeTomeFolioVerticalPartitions(
                        panelHeight = viewport.height.value,
                        fontScale = density.fontScale,
                    )
                }
                val headerTop = verticalPartitions.headerTop.dp
                val pageTop = verticalPartitions.gridTop.dp
                val pageHeight = (verticalPartitions.gridBottom - verticalPartitions.gridTop).dp
                val pageLeft = outer
                val pageWidth = viewport.width - outer * 2f
                val pad = pageWidth * 0.03f
                val gap = pageWidth * 0.018f
                // mimirFocusTileRing needs its full reach; 10dp measurably clips. The product token was
                // promoted from this 14 (ring migration, 2026-09-28).
                val ringClearance = MimirFocusTileRingReach
                val rawUsableWidth = pageWidth - pad * 2f
                val usableHeight = pageHeight - ringClearance * 2f
                val gridSolve = solveHomeTomeFolioGrid(
                    usableWidth = rawUsableWidth.value,
                    usableHeight = usableHeight.value,
                    columnGap = gap.value,
                    rowGap = gap.value,
                )
                val visibleColumns = gridSolve.visibleColumns
                val visibleRows = gridSolve.visibleRows
                val cellSide = gridSolve.cellSideDp.dp
                val cellWidth = cellSide
                val cellHeight = cellSide
                // Sheet and ring share the exact FocusAuthority publication. The reducer may already
                // hold the next index, but moving the sheet before that key is authoritative makes the
                // old ring appear to flash through an empty seat.
                val focusedRow = homeTomeFolioRowOfIndex(visualFocusedIndex)
                val totalRows = homeTomeFolioTotalRows(state.memberSlots.size)
                // Derived from the focused row alone, with no remembered accumulator and no
                // SideEffect commit. The previous rule integrated focus deltas onto a clamped prior
                // start, which is lossy in both ways observed on device: a clamped edge swallowed
                // outbound travel that the return trip then still spent, so down-then-up drifted;
                // and because the accumulator only committed in SideEffect, a burst computed travel
                // from an anchor the user had already moved past. A total function of focus has
                // neither failure — the same seat always anchors identically, however it was
                // reached, which is what makes the sheet travel exactly as far as the focus did.
                val rowWindowStart = homeTomeFolioAnchorIndex(
                    focusedIndex = focusedRow,
                    lead = homeTomeFolioRowLead(visibleRows),
                    visible = visibleRows,
                    total = totalRows,
                )
                val clampedVisibleRows = homeTomeFolioClampedRowWindow(
                    focusedRow = focusedRow,
                    visibleRows = visibleRows,
                    totalRows = totalRows,
                ).second
                val strideX = cellWidth + gap
                val strideY = cellHeight + gap
                // The one stride every coordinate in this folio is derived from — painted seats,
                // the sheet pan, and drop proximity all read it rather than re-deriving a multiply.
                val folioStride = HomeTomeStride(x = strideX.value, y = strideY.value)
                val gridWidth = cellWidth * visibleColumns + gap * (visibleColumns - 1)
                val gridHeight = cellHeight * clampedVisibleRows + gap * (clampedVisibleRows - 1)
                val gridLeft = pageLeft + (pageWidth - gridWidth) / 2f
                val gridTop = pageTop + (pageHeight - gridHeight) / 2f
                val touchGeometry = with(density) {
                    HomeTomeTouchGeometry(
                        cellWidthPx = cellWidth.toPx(),
                        cellHeightPx = cellHeight.toPx(),
                        gapPx = gap.toPx(),
                        gridWidthPx = gridWidth.toPx(),
                        gridHeightPx = gridHeight.toPx(),
                        ringClearancePx = ringClearance.toPx(),
                        visibleColumns = visibleColumns,
                        rowWindowStart = rowWindowStart,
                        visibleRows = clampedVisibleRows,
                    )
                }
                val currentTouchGeometry = rememberUpdatedState(touchGeometry)
                val maxTravelStart = (columnCount - visibleColumns).coerceAtLeast(0)
                // Same rule as the rows, for the same reason: a remembered previous start made a
                // column's screen position depend on the path taken to it, so a reverse never
                // retraced the forward trip.
                val travelStart = homeTomeFolioAnchorIndex(
                    focusedIndex = focusedColumn,
                    lead = homeTomeFolioColumnLead(visibleColumns),
                    visible = visibleColumns,
                    total = columnCount,
                )
                // ONE animation for the sheet, carrying both axes as a single vector.
                //
                // These were two independent Animatables retargeted by two independent effects. A
                // diagonal move therefore ran two springs that were only coincidentally in step, and
                // because each retarget carried its own axis velocity, a burst could leave the axes
                // settling at visibly different times — the sheet arrived in an L rather than along
                // the line the focus actually travelled. Animating the anchor as one vector makes a
                // tile's on-screen position a single value at every frame, so the axes cannot
                // disagree and the focus ring cannot outrun the sheet beneath it.
                val anchorAnim = remember(state.tome.tomeId) {
                    Animatable(Offset(travelStart.toFloat(), rowWindowStart.toFloat()), Offset.VectorConverter)
                }
                // The sheet's pan is the house clock, not a spring of its own (all
                // off-token clock moves onto the tokens). It was a critically damped spring at stiffness 500, which
                // settles in about 180 ms, so it takes OverlayMs on the house easing and keeps the length it had.
                // A tween ends exactly on its target, so the old half-pixel settle threshold (which stopped the
                // spring a half-seat out when it was borrowed from pixel space) has nothing left to guard.
                // A retarget mid-pan now eases out from where the sheet is, with no carried velocity.
                LaunchedEffect(travelStart, rowWindowStart, reducedMotion) {
                    val target = Offset(travelStart.toFloat(), rowWindowStart.toFloat())
                    // A jump longer than the window is a teleport, not travel: gliding it would drag
                    // the whole sheet past every intervening seat.
                    val leap = abs(anchorAnim.value.x - target.x) > HomeTomeFolioColumns.toFloat()
                    if (reducedMotion || leap) {
                        anchorAnim.snapTo(target)
                    } else {
                        anchorAnim.animateTo(
                            targetValue = target,
                            animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
                        )
                    }
                }
                // The touch lambdas reach the anchor through the INSTANCE, never by capturing it.
                //
                // `rememberUpdatedState(anchorAnim.value.y)` used to sit here: a snapshot of the spring's
                // VALUE, taken in composition, which recomposed this whole composable on every frame of
                // every glide. Removing it was right. Replacing it with a DIRECT CAPTURE was not, and
                // review caught that before it reached a second build.
                //
                // **`pointerInput(Unit)` never restarts, so its closure is the FIRST composition's.**
                // A changed lambda with unchanged keys is stored, but the running gesture coroutine goes
                // on executing the original — the same restart contract the sibling node in this file
                // states out loud, and the reason every other value that node needs
                // (`currentFolioState`, `currentTouchGeometry`, every callback) arrives through
                // `rememberUpdatedState`. `anchorAnim` is `remember(state.tome.tomeId)`, so an A->B tome
                // swap REPLACES it while the folio stays mounted — `HomeShelfCoordinator.openTome`
                // writes the new session straight over the old with no null frame between. A captured
                // reference is then tome A's abandoned spring, frozen at its last travel pose, and every
                // touch hit-test in tome B is off by the difference between the two, persistently, until
                // the folio closes to null. It reads as zero whenever both tomes happen to sit at the
                // origin, which is exactly why it survives a casual device check.
                //
                // Snapshotting the INSTANCE costs nothing this fix set out to save: the read is of a
                // reference, not of `.value`, so composition never subscribes to the spring. It also
                // repairs the `.x` reads, which were captured directly and carried the same defect
                // LATENTLY before this pass — their asymmetry with `.y` was the bug's fingerprint, not
                // evidence that a direct capture was safe.
                val currentAnchor by rememberUpdatedState(anchorAnim)
                //
                // The MOUNT window is the one thing that genuinely needs the anchor at composition
                // time: it decides which rows are hydrated, and hydration is composition. It gets a
                // `derivedStateOf` instead of a raw read, so the folio re-enters composition when the
                // window crosses a whole row -- a handful of times per glide -- rather than on every
                // frame of the spring.
                //
                // KEYED ON THE `anchorAnim` INSTANCE, not on the tome id, and that is not a detail.
                // `anchorAnim` is itself `remember(state.tome.tomeId)`, so it is REPLACED whenever the
                // open tome changes. A `remember { }` with no key -- or one keyed on anything that does
                // not change in lockstep -- would capture the abandoned Animatable, and the hydration
                // window would silently freeze at wherever the previous tome left it: tiles keep
                // gliding (they read the live spring through their own draw-phase closures) inside a
                // window that stopped moving, and the focused tile stops being mounted once travel
                // passes its edge. That exact defect was shipped and caught by review on the platform
                // coverflow the day before this (see `CoverflowMountWindowKeyTest`); it is pinned here
                // by `HomeTomeFolioAnchorReadTest` for the same reason.
                val animatedRowWindowStart by remember(anchorAnim) {
                    derivedStateOf { kotlin.math.floor(anchorAnim.value.y.toDouble()).toInt() }
                }
                // Settlement is the DESTINATION, not the glide toward it: the painted seat and the
                // published key are authoritative the moment they agree, so activation opens after
                // exactly ONE frame — the frame that paints that seat. Waiting for the springs to
                // report idle and then another FocusMs made a burst-then-CONFIRM land behind a
                // destination the user was already looking at. A newer destination — or a strip that
                // repaints under the same seat — restarts this effect, so the latest destination wins
                // and the intervening frame closes activation instead of buffering it.
                LaunchedEffect(
                    expectedFocusTarget,
                    focusState.scope,
                    focusState.focused,
                    travelStart,
                    rowWindowStart,
                ) {
                    val target = homeTomeFolioSettlementTarget(
                        expectedTarget = expectedFocusTarget,
                        activeScope = focusState.scope,
                        activeKey = focusState.focused,
                    ) ?: return@LaunchedEffect
                    // A viewport/visible-column change can move the painted strip without changing
                    // seat+key. Close activation before observing that new travel endpoint.
                    currentFocusUnsettled()
                    withFrameNanos { }
                    currentFocusSettled(target)
                }
                LaunchedEffect(
                    state.carriedItemId,
                    state.focusSettledForActivation,
                    state.focusedIndex,
                    pendingDropDestination,
                    touchGestureGeneration,
                ) {
                    val destination = pendingDropDestination ?: return@LaunchedEffect
                    if (!shouldClearHomeTomeFolioPendingDrop(
                            pending = destination,
                            activeGestureGeneration = touchGestureGeneration,
                            focusedIndex = state.focusedIndex,
                            focusSettled = state.focusSettledForActivation,
                            carriedCleared = state.carriedItemId == null,
                        )
                    ) {
                        return@LaunchedEffect
                    }
                    withFrameNanos { }
                    val liveFolio = currentFolioState.value
                    val live = pendingDropDestination
                    if (
                        live != null &&
                        live.generation == destination.generation &&
                        live.transactionId == destination.transactionId &&
                        live.seatIndex == destination.seatIndex &&
                        shouldClearHomeTomeFolioPendingDrop(
                            pending = live,
                            activeGestureGeneration = touchGestureGeneration,
                            focusedIndex = liveFolio.focusedIndex,
                            focusSettled = liveFolio.focusSettledForActivation,
                            carriedCleared = liveFolio.carriedItemId == null,
                        )
                    ) {
                        pendingDropDestination = relinquishHomeTomeFolioPendingDrop(
                            pending = live,
                            gestureGeneration = touchGestureGeneration,
                        )
                    }
                }
                // Platform identity already lives in the uppercase Tome acronym and focused-game metadata.
                // Keep only a restrained authored mark for custom Tomes; the retired console glyph consumed
                // the exact horizontal space long member titles need on compact/square viewports.
                val headerEmblemSize = if (state.tome.tomeId is TomeId.Custom) 24.dp else 0.dp
                val horizontalPartitions = solveHomeTomeFolioHorizontalPartitions(
                    width = viewport.width.value,
                    outer = outer.value,
                    emblemWidth = headerEmblemSize.value,
                    titleInset = MimirSpacing.sm.value,
                    headerGap = MimirSpacing.md.value,
                    footerGap = MimirSpacing.sm.value,
                    requestedCloseWidth = 40f,
                )

                if (state.tome.tomeId is TomeId.Custom) {
                    Box(
                        modifier = Modifier
                            .offset(x = outer, y = headerTop)
                            .size(headerEmblemSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "ᛗ",
                            style = MimirTypeScale.RuneAction.copy(fontSize = 18.sp),
                            color = hero.hardware,
                        )
                    }
                }
                Text(
                    text = state.tome.title,
                    style = MimirTypeScale.Row,
                    color = hero.onCover,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(x = horizontalPartitions.titleLeft.dp, y = headerTop)
                        .width(horizontalPartitions.titleWidth.dp),
                )
                Text(
                    text = homeTomeMemberCountLabel(libraryResources(), state.tome.tomeId, state.members.size),
                    style = MimirTypeScale.Support,
                    color = hero.onCover.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(
                        x = horizontalPartitions.titleLeft.dp,
                        y = headerTop + verticalPartitions.titleLineHeight.dp + 2.dp,
                    ).width((horizontalPartitions.titleWidth - 80f).coerceAtLeast(1f).dp),
                )
                val arrangeIndicatorAlpha by animateFloatAsState(
                    targetValue = if (state.mode == HomeTomeFolioMode.ARRANGE) 1f else 0f,
                    animationSpec = if (reducedMotion) snap() else {
                        tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)
                    },
                    label = "home-tome-arrange-indicator",
                )
                val arrangePillShape = RoundedCornerShape(MimirSpacing.radiusPill)
                val arrangeActiveDescription = stringResource(R.string.library_home_folio_arrange_active)
                Box(
                    modifier = Modifier
                        .offset(
                            // Centred on the Tome CONTAINER — the ring-inclusive envelope the eye
                            // reads as the Tome — rather than right-aligned to the title block,
                            // which the container's width has nothing to do with.
                            x = homeTomeFolioCentredLeft(
                                gridLeft = gridLeft.value,
                                gridWidth = gridWidth.value,
                                ringClearance = ringClearance.value,
                                contentWidth = HomeTomeArrangePillWidthDp,
                            ).dp,
                            y = headerTop + verticalPartitions.titleLineHeight.dp,
                        )
                        .size(width = HomeTomeArrangePillWidthDp.dp, height = 20.dp)
                        .graphicsLayer { alpha = arrangeIndicatorAlpha }
                        .clip(arrangePillShape)
                        .background(hero.coverBottom.copy(alpha = 0.52f))
                        .border(1.dp, palette.accentBright.copy(alpha = 0.70f), arrangePillShape)
                        .then(
                            if (state.mode == HomeTomeFolioMode.ARRANGE) {
                                Modifier.semantics { contentDescription = arrangeActiveDescription }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.library_home_folio_arrange_pill),
                        style = MimirTypeScale.MicroStatus,
                        color = palette.accentBright,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                // STATE channel, conserved across the departing frame: the footer title is "which member
                // is selected", not "which member is lit", so dropping it on the recorded frame would bake
                // a content pop into the departure.
                val focusedMember = state.memberSlots.getOrNull(visualFocusedIndex)
                    ?.takeIf { folioStateLit }
                if (focusedMember != null) {
                    Text(
                        text = focusedMember.title,
                        style = MimirTypeScale.Support,
                        color = hero.onCover,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .offset(x = horizontalPartitions.metadataLeft.dp, y = headerTop)
                            .width(horizontalPartitions.metadataWidth.dp),
                    )
                    focusedMember.platformId
                        ?.let { platformId -> mimirPlatformShelfmarkCode(platformId.value) }
                        ?.let { platformCode ->
                        Text(
                            text = platformCode,
                            style = MimirTypeScale.MicroStatus,
                            color = hero.onCover.copy(alpha = 0.68f),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .offset(
                                    x = horizontalPartitions.metadataLeft.dp,
                                    y = headerTop + verticalPartitions.titleLineHeight.dp + 2.dp,
                                )
                                .width(horizontalPartitions.metadataWidth.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .offset(x = gridLeft - ringClearance, y = gridTop - ringClearance)
                        .size(gridWidth + ringClearance * 2f, gridHeight + ringClearance * 2f)

                        // One stable gesture owner survives travel/model recomposition. Cell identity
                        // changes cannot dispose the drag and fabricate a reducer CANCEL mid-carry.
                        .pointerInput(Unit) {
                            coroutineScope {
                                val gestureScope = this
                                var liftJob: Job? = null
                                var dropJob: Job? = null
                                var gestureTransactionId: Long? = null
                                var activeDropOwnership: HomeTomeTouchDropOwnership? = null

                                fun cancelOwnedTransactionOnce(transactionId: Long?) {
                                    val owned = transactionId ?: return
                                    if (gestureTransactionId != owned) return
                                    // Relinquish local ownership before dispatch so recomposition or a
                                    // concurrently-cancelled observation cannot emit this Cancel twice.
                                    gestureTransactionId = null
                                    currentTouchArrangeIntent(HomeTomeArrangeIntent.Cancel(owned))
                                }

                                fun relinquishTouchGestureNoEffect(generation: Long) {
                                    pendingDropDestination = relinquishHomeTomeFolioPendingDrop(
                                        pending = pendingDropDestination,
                                        gestureGeneration = generation,
                                    )
                                    if (generation == touchGestureGeneration) {
                                        touchDragPosition = null
                                    }
                                }

                                fun dropFrame(): HomeTomeFolioDropFrame {
                                    val live = currentFolioState.value
                                    val focus = authority.state.value
                                    return HomeTomeFolioDropFrame(
                                        mode = live.mode,
                                        arrangeTransactionId = live.arrangeTransactionId,
                                        carriedItemId = live.carriedItemId,
                                        focusedIndex = live.focusedIndex,
                                        focusSettledForActivation = live.focusSettledForActivation,
                                        focusTarget = live.focusTarget,
                                        authorityScope = focus.scope,
                                        authorityFocused = focus.focused,
                                    )
                                }

                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val previousGeneration = touchGestureGeneration
                                    pendingDropDestination = relinquishHomeTomeFolioPendingDrop(
                                        pending = pendingDropDestination,
                                        gestureGeneration = previousGeneration,
                                    )
                                    val supersededTransactionId = gestureTransactionId
                                    liftJob?.cancel()
                                    val supersededOwnership = activeDropOwnership
                                    supersededOwnership
                                        ?.takeCancelEffect()
                                        ?.let { cancel ->
                                            currentTouchArrangeIntent(
                                                HomeTomeArrangeIntent.Cancel(cancel.transactionId),
                                            )
                                        }
                                    if (supersededOwnership != null) {
                                        gestureTransactionId = null
                                        activeDropOwnership = null
                                    }
                                    val supersededDropJob = dropJob
                                    if (supersededDropJob != null) {
                                        // Its finally owns cancellation by its captured transaction ID.
                                        // New ownership cannot begin until the deliberate hold below.
                                        dropJob = null
                                        supersededDropJob.cancel()
                                    } else if (supersededOwnership == null) {
                                        cancelOwnedTransactionOnce(supersededTransactionId)
                                    }
                                    relinquishTouchGestureNoEffect(previousGeneration)
                                    touchGestureGeneration++
                                    val generation = touchGestureGeneration

                                    fun memberIndexAt(position: Offset): Int? {
                                        val live = currentFolioState.value
                                        val geometry = currentTouchGeometry.value
                                        val viewportY = position.y - geometry.ringClearancePx
                                        val rowHit = homeTomeFolioAnimatedRowHit(
                                            animatedRowWindowStart = currentAnchor.value.y,
                                            viewportRelativeY = viewportY,
                                            cellHeight = geometry.cellHeightPx,
                                            rowGap = geometry.gapPx,
                                        )
                                        return homeTomeFolioSeatIndexAtTravel(
                                            positionX = position.x - geometry.ringClearancePx,
                                            positionY = rowHit.adjustedPositionY,
                                            travelStartColumn = currentAnchor.value.x,
                                            seatCount = live.memberSlots.size,
                                            cellWidth = geometry.cellWidthPx,
                                            cellHeight = geometry.cellHeightPx,
                                            columnGap = geometry.gapPx,
                                            rowGap = geometry.gapPx,
                                            visibleColumns = geometry.visibleColumns,
                                            rowWindowStart = rowHit.discreteRowWindowStart,
                                            visibleRows = geometry.visibleRows,
                                        )?.takeIf { live.memberSlots.getOrNull(it) != null }
                                    }
                                    fun insertionIndexAt(position: Offset): Int? {
                                        val live = currentFolioState.value
                                        val geometry = currentTouchGeometry.value
                                        val viewportY = position.y - geometry.ringClearancePx
                                        val rowHit = homeTomeFolioAnimatedRowHit(
                                            animatedRowWindowStart = currentAnchor.value.y,
                                            viewportRelativeY = viewportY,
                                            cellHeight = geometry.cellHeightPx,
                                            rowGap = geometry.gapPx,
                                        )
                                        return homeTomeFolioSeatIndexAtTravel(
                                            positionX = position.x - geometry.ringClearancePx,
                                            positionY = rowHit.adjustedPositionY,
                                            travelStartColumn = currentAnchor.value.x,
                                            seatCount = live.memberSlots.size,
                                            cellWidth = geometry.cellWidthPx,
                                            cellHeight = geometry.cellHeightPx,
                                            columnGap = geometry.gapPx,
                                            rowGap = geometry.gapPx,
                                            visibleColumns = geometry.visibleColumns,
                                            rowWindowStart = rowHit.discreteRowWindowStart,
                                            visibleRows = geometry.visibleRows,
                                        )
                                    }
                                    fun nearestInsertionIndexAt(position: Offset): Int? {
                                        val live = currentFolioState.value
                                        val geometry = currentTouchGeometry.value
                                        val viewportY = position.y - geometry.ringClearancePx
                                        val rowHit = homeTomeFolioAnimatedRowHit(
                                            animatedRowWindowStart = currentAnchor.value.y,
                                            viewportRelativeY = viewportY,
                                            cellHeight = geometry.cellHeightPx,
                                            rowGap = geometry.gapPx,
                                        )
                                        return homeTomeFolioNearestSeatAtTravel(
                                            positionX = position.x - geometry.ringClearancePx,
                                            positionY = rowHit.adjustedPositionY,
                                            travelStartColumn = currentAnchor.value.x,
                                            seatCount = live.memberSlots.size,
                                            cellWidth = geometry.cellWidthPx,
                                            cellHeight = geometry.cellHeightPx,
                                            columnGap = geometry.gapPx,
                                            rowGap = geometry.gapPx,
                                            visibleColumns = geometry.visibleColumns,
                                            rowWindowStart = rowHit.discreteRowWindowStart,
                                            visibleRows = geometry.visibleRows,
                                        )
                                    }

                                    val liftedIndex = memberIndexAt(down.position)
                                        ?: return@awaitEachGesture
                                    val touchSlop = viewConfiguration.touchSlop
                                    var latestPosition = down.position
                                    val holdStart = SystemClock.uptimeMillis()
                                    var armed = false
                                    val endedBeforeHold = withTimeoutOrNull(HomeTomeTouchPickupMs) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: return@withTimeoutOrNull true
                                            latestPosition = change.position
                                            when (
                                                homeTomeTouchPickupDecision(
                                                    elapsedMs = SystemClock.uptimeMillis() - holdStart,
                                                    distancePx = (change.position - down.position).getDistance(),
                                                    touchSlopPx = touchSlop,
                                                    pressedPointerCount = event.changes.count { it.pressed },
                                                    stillPressed = change.pressed,
                                                )
                                            ) {
                                                HomeTomeTouchPickup.REJECTED -> return@withTimeoutOrNull true
                                                HomeTomeTouchPickup.ARMED -> {
                                                    armed = true
                                                    return@withTimeoutOrNull false
                                                }
                                                HomeTomeTouchPickup.PENDING -> Unit
                                            }
                                        }
                                    }
                                    if (!armed) {
                                        armed = endedBeforeHold == null
                                    }
                                    if (!armed) return@awaitEachGesture

                                    suppressActivationUntilMillis = SystemClock.uptimeMillis() + 500L
                                    touchDragPosition = latestPosition
                                    currentFirstInteraction()
                                    currentFocusMember(liftedIndex)
                                    var lastInsertion = liftedIndex
                                    val liftSettlementStartedAtMs = SystemClock.uptimeMillis()
                                    val liftTransactionId =
                                        currentFolioState.value.nextArrangeTransactionId
                                    val dropOwnership = HomeTomeTouchDropOwnership(
                                        transactionId = liftTransactionId,
                                        gestureGeneration = generation,
                                    )
                                    activeDropOwnership = dropOwnership
                                    liftJob = gestureScope.launch {
                                        var liftSettled = false
                                        // Focus/ring settlement is cancellable: leaving the Folio or
                                        // moving away before lift means this gesture owns nothing. It is also
                                        // bounded: a lost FocusAuthority publication cannot hold the release
                                        // coroutine forever ahead of the existing drop-observation timeout.
                                        while (generation == touchGestureGeneration &&
                                            SystemClock.uptimeMillis() - liftSettlementStartedAtMs <
                                            HomeTomeTouchLiftAwaitMs
                                        ) {
                                            val live = currentFolioState.value
                                            val focus = authority.state.value
                                            if (focus.scope != HomeTomeFolioScope ||
                                                live.focusedIndex != liftedIndex
                                            ) {
                                                return@launch
                                            }
                                            if (homeTomeTouchLiftReady(
                                                    focusedIndex = live.focusedIndex,
                                                    liftIndex = liftedIndex,
                                                    focusSettled = live.focusSettledForActivation,
                                                )
                                            ) {
                                                liftSettled = true
                                                break
                                            }
                                            withFrameNanos { }
                                        }
                                        if (generation != touchGestureGeneration) return@launch
                                        val lift = homeTomeTouchLiftIntent(
                                            liftSettled = liftSettled,
                                            seatIndex = liftedIndex,
                                            transactionId = liftTransactionId,
                                        ) ?: return@launch
                                        gestureTransactionId = liftTransactionId
                                        currentTouchArrangeIntent(lift)
                                        // Publication belongs to this gesture-local closure. A superseding
                                        // gesture may reuse the predicted ID only when this remains false.
                                        dropOwnership.publish()
                                        val buffered = lastInsertion
                                        if (buffered != liftedIndex) {
                                            currentTouchArrangeIntent(
                                                HomeTomeArrangeIntent.Retarget(
                                                    buffered,
                                                    liftTransactionId,
                                                ),
                                            )
                                        }
                                    }

                                    var edgeDirection: ControllerAction? = null
                                    var edgeJob: Job? = null
                                    fun setEdge(next: ControllerAction?) {
                                        if (next == edgeDirection) return
                                        edgeJob?.cancel()
                                        edgeDirection = next
                                        edgeJob = next?.let { direction ->
                                            gestureScope.launch {
                                                while (edgeDirection == direction &&
                                                    generation == touchGestureGeneration
                                                ) {
                                                    delay(HomeTomeTouchEdgeDwellMs)
                                                    gestureTransactionId?.let { transactionId ->
                                                        currentTouchArrangeIntent(
                                                            HomeTomeArrangeIntent.MoveCursor(
                                                                direction,
                                                                transactionId,
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    var released = false
                                    while (!released) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || event.changes.count { it.pressed } > 1) {
                                            break
                                        }
                                        latestPosition = change.position
                                        change.consume()
                                        if (!change.pressed) {
                                            released = true
                                            break
                                        }
                                        touchDragPosition = change.position
                                        // The SAME resolver the release uses, deliberately.
                                        //
                                        // This was `insertionIndexAt`, which is a containment test:
                                        // it only answers while the finger is literally inside a
                                        // cell, so dragging across a gap froze the preview on the
                                        // last seat entered. The release then resolved by PROXIMITY
                                        // and could legitimately name a different seat — so a tile
                                        // could land somewhere the drag never previewed. Preview and
                                        // commit have to be the same question or the geometry is
                                        // only precise at the instant you let go.
                                        nearestInsertionIndexAt(change.position)?.let { insertion ->
                                            if (insertion != lastInsertion) {
                                                lastInsertion = insertion
                                                gestureTransactionId?.let { transactionId ->
                                                    currentTouchArrangeIntent(
                                                        HomeTomeArrangeIntent.Retarget(
                                                            insertion,
                                                            transactionId,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                        val geometry = currentTouchGeometry.value
                                        val threshold = geometry.cellWidthPx * 0.22f
                                        setEdge(
                                            when {
                                                change.position.y <
                                                    geometry.ringClearancePx - threshold -> {
                                                    ControllerAction.NAV_UP
                                                }
                                                change.position.y >
                                                    geometry.ringClearancePx + geometry.gridHeightPx + threshold -> {
                                                    ControllerAction.NAV_DOWN
                                                }
                                                change.position.x <
                                                    geometry.ringClearancePx - threshold -> {
                                                    ControllerAction.NAV_LEFT
                                                }
                                                change.position.x >
                                                    geometry.ringClearancePx + geometry.gridWidthPx + threshold -> {
                                                    ControllerAction.NAV_RIGHT
                                                }
                                                else -> null
                                            },
                                        )
                                    }
                                    setEdge(null)
                                    edgeJob?.cancel()
                                    suppressActivationUntilMillis = SystemClock.uptimeMillis() + 500L
                                    if (!released) {
                                        liftJob?.cancel()
                                        dropOwnership.takeCancelEffect()?.let { cancel ->
                                            currentTouchArrangeIntent(
                                                HomeTomeArrangeIntent.Cancel(cancel.transactionId),
                                            )
                                        }
                                        if (activeDropOwnership === dropOwnership) {
                                            activeDropOwnership = null
                                            gestureTransactionId = null
                                        }
                                        relinquishTouchGestureNoEffect(generation)
                                        return@awaitEachGesture
                                    }

                                    val destination = nearestInsertionIndexAt(latestPosition)
                                    if (destination != null) {
                                        pendingDropDestination = homeTomeFolioDropDestination(
                                            seatIndex = destination,
                                            transactionId = dropOwnership.transactionId,
                                            generation = generation,
                                        )
                                    }
                                    // Release folds page travel into strip-space immediately; pointer
                                    // samples never become the ring's animation origin after lift.
                                    touchDragPosition = null
                                    val pendingLift = liftJob
                                    dropJob = gestureScope.launch {
                                        var dropCommitted = false
                                        var dropCancelled = false
                                        fun cancelDropOnce() {
                                            if (dropCancelled || dropCommitted) return
                                            dropCancelled = true
                                            homeTomeTouchDropCancelEffect(dropOwnership)
                                                ?.let { cancel ->
                                                    currentTouchArrangeIntent(
                                                        HomeTomeArrangeIntent.Cancel(
                                                            cancel.transactionId,
                                                        ),
                                                    )
                                                }
                                            if (homeTomeTouchDropMayClearSharedOwnership(
                                                    ownership = dropOwnership,
                                                    activeGestureGeneration = touchGestureGeneration,
                                                )
                                            ) {
                                                gestureTransactionId = null
                                            }
                                        }
                                        try {
                                            pendingLift?.join()
                                            if (generation != touchGestureGeneration) return@launch
                                            if (!dropOwnership.liftPublished) {
                                                relinquishTouchGestureNoEffect(generation)
                                                return@launch
                                            }
                                            if (destination == null) {
                                                cancelDropOnce()
                                                relinquishTouchGestureNoEffect(generation)
                                                return@launch
                                            }
                                            var currentDestination = homeTomeFolioDropDestination(
                                                seatIndex = destination,
                                                transactionId = dropOwnership.transactionId,
                                                generation = generation,
                                            )
                                            pendingDropDestination = currentDestination
                                            val sourceIndex = currentFolioState.value.focusedIndex
                                            val sourceFocusKey = authority.state.value.focused
                                            if (sourceFocusKey == null) {
                                                cancelDropOnce()
                                                relinquishTouchGestureNoEffect(generation)
                                                return@launch
                                            }
                                            var observation: HomeTomeFolioDropObservation? =
                                                beginHomeTomeFolioDropObservation(
                                                    destination = currentDestination,
                                                    sourceIndex = sourceIndex,
                                                    sourceFocusKey = sourceFocusKey,
                                                    liftTransactionObserved =
                                                        homeTomeTouchLiftTransactionObserved(
                                                            dropFrame(),
                                                            dropOwnership.transactionId,
                                                        ),
                                                )
                                            var retargetDispatched = false
                                            var lastAwaitUptime = SystemClock.uptimeMillis()
                                            while (generation == touchGestureGeneration) {
                                                val currentObservation = observation ?: break
                                                val frame = dropFrame()
                                                val now = SystemClock.uptimeMillis()
                                                val deltaMs = (now - lastAwaitUptime).coerceAtLeast(0L)
                                                lastAwaitUptime = now
                                                val step = stepHomeTomeFolioDropObservation(
                                                    observation = currentObservation,
                                                    frame = frame,
                                                    deltaMs = deltaMs,
                                                )
                                                observation = step.observation
                                                when (val effect = step.effect) {
                                                    is HomeTomeFolioDropEffect.CancelOwned -> {
                                                        cancelDropOnce()
                                                        relinquishTouchGestureNoEffect(generation)
                                                        return@launch
                                                    }
                                                    is HomeTomeFolioDropEffect.Drop -> {
                                                        currentTouchArrangeIntent(
                                                            HomeTomeArrangeIntent.Drop(
                                                                effect.seatIndex,
                                                                effect.transactionId,
                                                            ),
                                                        )
                                                        dropCommitted = true
                                                        dropOwnership.complete()
                                                        if (homeTomeTouchDropMayClearSharedOwnership(
                                                                ownership = dropOwnership,
                                                                activeGestureGeneration =
                                                                    touchGestureGeneration,
                                                            )
                                                        ) {
                                                            gestureTransactionId = null
                                                        }
                                                        if (generation == touchGestureGeneration) {
                                                            touchDragPosition = null
                                                        }
                                                        if (activeDropOwnership === dropOwnership) {
                                                            activeDropOwnership = null
                                                        }
                                                        return@launch
                                                    }
                                                    null -> Unit
                                                }
                                                if (observation == null) {
                                                    relinquishTouchGestureNoEffect(generation)
                                                    return@launch
                                                }
                                                if (!retargetDispatched &&
                                                    observation!!.phase ==
                                                    HomeTomeFolioDropPhase.AwaitingDestinationSettlement
                                                ) {
                                                    retargetDispatched = true
                                                    val txId = observation.destination.transactionId
                                                    if (destination != lastInsertion) {
                                                        lastInsertion = destination
                                                        currentTouchArrangeIntent(
                                                            HomeTomeArrangeIntent.Retarget(
                                                                destination,
                                                                txId,
                                                            ),
                                                        )
                                                    }
                                                }
                                                withFrameNanos { }
                                            }
                                        } finally {
                                            if (!dropCommitted) {
                                                cancelDropOnce()
                                                relinquishTouchGestureNoEffect(generation)
                                            }
                                            if (activeDropOwnership === dropOwnership) {
                                                activeDropOwnership = null
                                            }
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .offset(x = ringClearance, y = ringClearance)
                            .size(gridWidth, gridHeight)
                            // Member content clips to the EXACT grid viewport: ±1 row/column hydration
                            // buffers stay mounted but cannot leak ghost slivers into the ring band.
                            .clipToBounds()
                            .graphicsLayer {
                                // The pan is the animated anchor, resolved through the same mapping
                                // the painted seats use, so the sheet and its tiles cannot drift.
                                val pan = homeTomeFolioAnchorVector(
                                    anchorColumn = anchorAnim.value.x,
                                    anchorRow = anchorAnim.value.y,
                                    stride = folioStride,
                                )
                                translationX = -pan.x.dp.toPx()
                                translationY = -pan.y.dp.toPx()
                            },
                    ) {
                    val mountedFirst = (travelStart - 1).coerceAtLeast(0)
                    val mountedLast = (travelStart + visibleColumns)
                        .coerceAtMost((columnCount - 1).coerceAtLeast(0))
                    val hydrationRows = homeTomeFolioHydrationRowRange(
                        rowWindowStart = animatedRowWindowStart
                            .coerceIn(0, (totalRows - 1).coerceAtLeast(0)),
                        visibleRows = clampedVisibleRows,
                        totalRows = totalRows,
                    )
                    (mountedFirst..mountedLast).forEach { column ->
                        hydrationRows.forEach rows@{ globalRow ->
                        val absoluteIndex = homeTomeFolioIndexOf(column, globalRow)
                        if (absoluteIndex >= state.memberSlots.size) return@rows
                        val member = state.memberSlots[absoluteIndex]
                        val focused = member != null && authoritySeatIndex == absoluteIndex
                        // Seat position from the one mapping, not an inline multiply that merely
                        // happens to match the pan transform above.
                        val seatVector = homeTomeSeatVector(HomeTomeSeat(column, globalRow), folioStride)
                        val targetX = seatVector.x.dp
                        val targetY = seatVector.y.dp
                        val fixedCellModifier = Modifier
                            .offset(
                                x = targetX,
                                y = targetY,
                            )
                            .size(width = cellWidth, height = cellHeight)
                        if (member == null) {
                            val emptySeatKey = homeTomeSeatFocusKey(state.tome.tomeId, absoluteIndex)
                            val seatPainted = absoluteIndex in state.memberSlots.indices
                            val emptySeatFocused = authoritySeatIndex == absoluteIndex
                            val emptySeatDescription = stringResource(R.string.library_home_folio_empty_seat, absoluteIndex + 1)
                            HomeTomeEmptyFolioCell(
                                modifier = fixedCellModifier.then(
                                    if (seatPainted) {
                                        Modifier
                                            .mimirFocusable(
                                                authority = authority,
                                                key = emptySeatKey,
                                                scope = HomeTomeFolioScope,
                                                autoBringIntoView = false,
                                            )
                                            .semantics {
                                                contentDescription = emptySeatDescription
                                                selected = emptySeatFocused
                                            }
                                    } else {
                                        Modifier
                                    },
                                ),
                                onActivate = if (seatPainted) {
                                    {
                                        onFirstInteraction()
                                        onFocusMember(absoluteIndex)
                                    }
                                } else {
                                    null
                                },
                            )
                        } else key(member.itemId) {
                            // The two glides are States, read only in each node's `offset {}` lambda (layout phase): a tile
                            // gliding to its seat relays out its own node per frame and recomposes neither the cell nor
                            // this page.
                            val animatedX = animateDpAsState(
                                targetValue = targetX,
                                animationSpec = if (reducedMotion) snap() else {
                                    tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)
                                },
                                label = "home-tome-member-x",
                            )
                            val animatedY = animateDpAsState(
                                targetValue = targetY,
                                animationSpec = if (reducedMotion) snap() else {
                                    tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)
                                },
                                label = "home-tome-member-y",
                            )
                            val wellFace = homeTomeWellFace(
                                physicalMediaUri = member.physicalMediaUri,
                                wiredFaceShape = member.wiredFaceShape,
                                artworkUri = member.artworkUri,
                            )
                            val wellArtUri = wellFace.uri
                            val memberPhysical = homeTomeFolioMemberIsPhysical(wellFace)
                            if (member.itemId == state.carriedItemId) {
                                HomeTomeEmptyFolioCell(
                                    modifier = Modifier
                                        .offset { IntOffset(animatedX.value.roundToPx(), animatedY.value.roundToPx()) }
                                        .size(width = cellWidth, height = cellHeight),
                                )
                            } else {
                                HomeTomeFolioCell(
                                    member = member,
                                    artUri = wellArtUri,
                                    artDeferred = homeTomeFolioDefersArtPainter(
                                        folioArtAttached = folioArtAttached,
                                        artUri = wellArtUri,
                                    ),
                                    physical = memberPhysical,
                                    focused = focused,
                                    carried = false,
                                    modifier = Modifier
                                        .offset { IntOffset(animatedX.value.roundToPx(), animatedY.value.roundToPx()) }
                                        .size(width = cellWidth, height = cellHeight)
                                        // The focused cell rises above its later-composed neighbors so
                                        // the 13.5dp ring reach can never be overpainted mid-travel.
                                        .zIndex(if (focused) 6f else 2f)
                                        .mimirFocusable(
                                            authority = authority,
                                            key = member.focusKey,
                                            scope = HomeTomeFolioScope,
                                            autoBringIntoView = false,
                                        ),
                                    onActivate = {
                                        if (SystemClock.uptimeMillis() < suppressActivationUntilMillis) {
                                            suppressActivationUntilMillis = 0L
                                        } else {
                                            onFirstInteraction()
                                            // T-1 : in BROWSE one tap opens
                                            // this ROM's launch detail. Focus still moves — it is the
                                            // ring's receipt of where the action landed — but it is no
                                            // longer a PREREQUISITE for acting, which is what made this
                                            // seat cost two taps. ARRANGE keeps the settled-target gate
                                            // deliberately; the branch and its three reasons are
                                            // declared on [homeTomeFolioMemberTapAction].
                                            //
                                            // `onFocusMember` MUST run before `onOpenMember` and the
                                            // order is not cosmetic: the app's launch card refuses to
                                            // open unless the FocusAuthority already names the origin
                                            // it was handed (`homeLaunchCardOriginIsCurrent`, checked
                                            // inside `HomeLaunchCardCoordinator.open`). Publishing
                                            // focus first is what makes the tapped seat a valid
                                            // origin; flip these two and the tap silently does
                                            // nothing at all.
                                            val live = currentFolioState.value
                                            val tap = homeTomeFolioMemberTapAction(
                                                mode = live.mode,
                                                arrangeSettledHere = live.focusSettled &&
                                                    live.focusedIndex == absoluteIndex,
                                            )
                                            onFocusMember(absoluteIndex)
                                            when (tap) {
                                                HomeTomeFolioMemberTapAction.FOCUS_ONLY -> Unit
                                                HomeTomeFolioMemberTapAction.FOCUS_AND_OPEN ->
                                                    onOpenMember(member)
                                                HomeTomeFolioMemberTapAction.FOCUS_AND_ARRANGE_CONFIRM ->
                                                    onArrangeAction(ControllerAction.CONFIRM)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        }
                    }
                    }
                    if (state.members.isEmpty()) {
                        // STATE channel for `focused` (it also drives `semantics { selected }`), RING
                        // channel for the plate's own ring — the empty-tome plate is the only other
                        // `mimirFocusTileRing` on this surface and it would otherwise ship the same ghost.
                        val emptyFocused = folioStateLit &&
                            focusState.focused == HomeTomeEmptyFocusKey
                        HomeTomeTeachingPlate(
                            focused = emptyFocused,
                            ringSuppressed = departing,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width((gridWidth * 0.48f).coerceIn(240.dp, 380.dp))
                                .mimirFocusable(
                                    authority = authority,
                                    key = HomeTomeEmptyFocusKey,
                                    scope = HomeTomeFolioScope,
                                    autoBringIntoView = false,
                                ),
                            onActivate = {
                                onFirstInteraction()
                                onBack()
                            },
                        )
                    }
                }

                // The carried identity is rendered once in a Folio-owned sibling overlay. Keeping it
                // outside the traveling strip prevents edge clipping while touch follows the pointer.
                val carryPose = homeTomeFolioCarryPose(state)
                val strideXPx = with(density) { strideX.toPx() }
                val cellWidthPx = with(density) { cellWidth.toPx() }
                val cellHeightPx = with(density) { cellHeight.toPx() }
                val rowGapPx = with(density) { gap.toPx() }
                val gridLeftPx = with(density) { gridLeft.toPx() }
                val gridTopPx = with(density) { gridTop.toPx() }
                val ringClearancePx = touchGeometry.ringClearancePx
                val carriedMember = state.carriedItemId?.let { carriedId ->
                    state.members.firstOrNull { it.itemId == carriedId }
                }
                // The carried cell's pose is the one place the anchor spring legitimately has to be a
                // COMPOSITION value: `animateDpAsState` takes a target, and a target is state. So the
                // whole derivation sits INSIDE this branch rather than above it.
                //
                // It used to be unconditional, and that is the defect: nothing here is reachable
                // without a carry -- `paintedLeftPx`, `paintedTopPx`, `followPointer` and both strip
                // targets have no consumer outside this `if` -- yet `travelColumn` and the
                // `anchorAnim.value.y` term below subscribed the whole folio to the spring on EVERY
                // frame of EVERY glide, to feed maths that an open, a return and a d-pad move never
                // read. Arrange is the only gesture that carries, and it is not gliding while it does.
                if (carriedMember != null && carryPose != null) {
                    val travelColumn = anchorAnim.value.x
                    // `carryPose.column`/`.row` are non-null Ints, so the third elvis arm each of
                    // these used to carry was dead code inside this null-checked branch.
                    val carriedColumn = pendingDropDestination?.column ?: carryPose.column
                    val carriedRow = pendingDropDestination?.row ?: carryPose.row
                    val stripTargetLeftPx = touchDragPosition?.let { pointer ->
                        homeTomeFolioStripCellLeftX(
                            viewportX = pointer.x,
                            travelStartColumn = travelColumn,
                            ringClearancePx = ringClearancePx,
                            cellWidthPx = cellWidthPx,
                            strideXPx = strideXPx,
                        )
                    } ?: homeTomeFolioStripSeatedLeftX(carriedColumn, strideXPx)
                    val stripTargetTopPx = touchDragPosition?.let { pointer ->
                        homeTomeFolioStripPointerTopY(
                            viewportY = pointer.y,
                            ringClearancePx = ringClearancePx,
                            cellHeightPx = cellHeightPx,
                        )
                    } ?: ((carriedRow - anchorAnim.value.y) * (cellHeightPx + rowGapPx))
                    val paintedLeftPx = homeTomeFolioPaintLeftX(
                        stripLeftX = stripTargetLeftPx,
                        gridLeftPx = gridLeftPx,
                        travelStartColumn = travelColumn,
                        strideXPx = strideXPx,
                    )
                    val paintedTopPx = gridTopPx + stripTargetTopPx
                    val followPointer = touchDragPosition != null
                    val carriedX = animateDpAsState(
                        targetValue = with(density) { paintedLeftPx.toDp() },
                        animationSpec = if (reducedMotion || followPointer) snap() else {
                            tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)
                        },
                        label = "home-tome-carried-x",
                    )
                    val carriedY = animateDpAsState(
                        targetValue = with(density) { paintedTopPx.toDp() },
                        animationSpec = if (reducedMotion || followPointer) snap() else {
                            tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)
                        },
                        label = "home-tome-carried-y",
                    )
                    // STATE channel, same reason as the footer title: a carried cell's selection is what
                    // it IS, not what is lit on it.
                    val carriedFocused = folioStateLit &&
                        focusState.focused == carriedMember.focusKey
                    val carriedWellFace = homeTomeWellFace(
                        physicalMediaUri = carriedMember.physicalMediaUri,
                        wiredFaceShape = carriedMember.wiredFaceShape,
                        artworkUri = carriedMember.artworkUri,
                    )
                    val carriedWellArtUri = carriedWellFace.uri
                    HomeTomeFolioCell(
                        member = carriedMember,
                        artUri = carriedWellArtUri,
                        physical = homeTomeFolioMemberIsPhysical(carriedWellFace),
                        focused = carriedFocused,
                        carried = true,
                        modifier = Modifier
                            .offset { IntOffset(carriedX.value.roundToPx(), carriedY.value.roundToPx()) }
                            .size(width = cellWidth, height = cellHeight)
                            .zIndex(35f)
                            .graphicsLayer {
                                scaleX = HomeTomeCarryScale
                                scaleY = HomeTomeCarryScale
                            }
                            .mimirFocusable(
                                authority = authority,
                                key = carriedMember.focusKey,
                                scope = HomeTomeFolioScope,
                                autoBringIntoView = false,
                            ),
                        onActivate = {
                            onFirstInteraction()
                            // Coordinator owns the single exact-target pending-confirm latch. A second
                            // composable-local buffer could survive navigation and drop at a later seat.
                            onArrangeAction(ControllerAction.CONFIRM)
                        },
                    )
                }

                // One sibling overlay owns the ring above the traveling layer. Travel keeps the focused
                // column inside the visible window, so the ring can never shear at a clipped edge; its
                // horizontal seat folds the live travel offset into the same draw-phase transform.
                val ringSeatIndex = pendingDropDestination?.seatIndex ?: authoritySeatIndex
                // UI-02, 2026-08-14: the ring jumped to tile one before cancelling that move.
                //
                // An elvis-to-zero here was the second half of that sentence. When [ringSeatIndex] is
                // null the ring is ALREADY being hidden -- ringVisible below is false on the same
                // condition, and
                // ringProgress fades it over MimirMotion.FocusMs. But column/row fell back to 0 in the
                // same frame, and unlike ringProgress they are read straight into graphicsLayer with no
                // tween of their own ("no secondary X/Y tween", above). So the ring jumped to seat 0 and
                // only THEN faded out: it moved to the first tile, and cancelled. Both visible phases came from one fallback.
                //
                // [homeTomeFolioAuthoritativeSeatIndex] returns null on three routes, and the folio is
                // fully mounted with its own scope active on two of them -- the published key is null
                // (which the authority does whenever a pushScope lands before the remembered key is
                // registered, i.e. every folio entry) or it is the teaching plate's. So this is not a
                // teardown-only path that might never paint.
                //
                // Hold the last resolved seat instead. The ring now fades out WHERE IT WAS, which is what
                // "hidden" should have looked like all along, and nothing about when it hides changes:
                // ringVisible is deliberately still keyed on the live [ringSeatIndex], not on this.
                val lastPaintedRingSeat = remember(state.tome.tomeId) { mutableIntStateOf(0) }
                val paintedRingSeat = ringSeatIndex
                    ?.also { lastPaintedRingSeat.intValue = it }
                    ?: lastPaintedRingSeat.intValue
                val ringColumn = homeTomeFolioColumnOfIndex(paintedRingSeat)
                val ringRow = homeTomeFolioRowOfIndex(paintedRingSeat)
                val ringStripLeftPx = homeTomeFolioStripSeatedLeftX(ringColumn, strideXPx)
                // PERF-D/CP04: both of these are DRAW-phase providers, invoked inside the ring's own
                // `graphicsLayer` below. Every other term is a settled composition value; only the
                // anchor moves per frame, and the ring's transform is the only thing that needs it.
                //
                // They used to be plain `val`s computed here, which meant the anchor was read at
                // composition and the ring's position arrived by RECOMPOSING the folio each frame --
                // the same shape, and the same fix, as the platform coverflow's tile offsets. The
                // sheet beneath it already worked this way (`homeTomeFolioAnchorVector` inside the
                // strip's `graphicsLayer`), so the ring was the odd one out on its own surface.
                val ringPaintedLeftPx: () -> Float = {
                    homeTomeFolioPaintLeftX(
                        stripLeftX = ringStripLeftPx,
                        gridLeftPx = gridLeftPx,
                        travelStartColumn = anchorAnim.value.x,
                        strideXPx = strideXPx,
                    )
                }
                val ringPaintedTopPx: () -> Float = {
                    gridTopPx + (ringRow - anchorAnim.value.y) * (cellHeightPx + rowGapPx)
                }
                val ringVisible = ringSeatIndex != null && folioRingLit
                val ringCarrying = carryPose != null
                // The ring hugs what it selects: the book's 3:4 leaf when the seat's cell paints the title book, the cell otherwise ([homeTomeFolioRingSeat]; it was the square
                // cell's size round a book, about 30 px clear on each side, sweep 10-11). It follows the member the ring is on, or the one it carries, and glides to the
                // new width on the cell's own tween when the lit seat changes kind.
                val ringSubject = if (ringCarrying) carriedMember else state.memberSlots.getOrNull(paintedRingSeat)
                val ringSeat = homeTomeFolioRingSeat(
                    cellWidthDp = cellWidth.value,
                    cellHeightDp = cellHeight.value,
                    bookInside = ringSubject != null && homeTomeFolioMemberShowsTitleBook(ringSubject),
                )
                val ringWidth = animateDpAsState(
                    targetValue = ringSeat.widthDp.dp,
                    animationSpec = if (reducedMotion) snap() else tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
                    label = "home-tome-ring-width",
                )
                // Ring left/top track the authoritative painted pose directly — no secondary X/Y tween.
                val ringAnimatedProgress = animateFloatAsState(
                    targetValue = if (ringVisible) 1f else 0f,
                    animationSpec = if (reducedMotion) snap() else mimirFocusRingSpec(),
                    label = "home-tome-authoritative-ring",
                )
                // A departing frame is a STILL, and it is recorded the moment it draws. Retargeting the
                // tween would leave the ring near full alpha on that one frame (it unwinds over
                // MimirMotion.FocusMs = 140 ms, ~8 frames), so the recorded pixels would carry a barely
                // dimmed gold ring across the shelf for the whole hold — the artefact T-2 exists to
                // prevent, arriving through the animation instead of through the state. Overriding the
                // VALUE rather than the target is what makes "out" mean out on this frame.
                //
                // Gated on [departing] alone, NOT on `folioRingLit`: every OTHER way this surface loses
                // the scope — a tile menu or a launch card opening over it — leaves the Folio live and
                // on screen, and there the ring's FocusMs fade-out is the shipped behaviour and stays.
                val ringProgress: () -> Float = { if (departing) 0f else ringAnimatedProgress.value }
                Box(
                    Modifier
                        // The ring's width glides on its own tween, so it is read here in MEASURE, not composed: a Dp size modifier made
                        // the whole folio recompose on every frame of a width change.
                        .layout { measurable, constraints ->
                            val widthPx = constraints.constrainWidth(ringWidth.value.roundToPx())
                            val heightPx = constraints.constrainHeight(cellHeight.roundToPx())
                            val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
                            layout(widthPx, heightPx) { placeable.place(0, 0) }
                        }
                        .zIndex(40f)
                        .graphicsLayer {
                            translationX = ringPaintedLeftPx() + (cellWidthPx - ringWidth.value.toPx()) / 2f
                            translationY = ringPaintedTopPx() -
                                if (ringCarrying) HomeTomeCarryLiftDp.dp.toPx() else 0f
                            if (ringCarrying) {
                                scaleX = HomeTomeCarryScale
                                scaleY = HomeTomeCarryScale
                            }
                            clip = false
                        }
                        .testTag(HomeTomeFolioRingTestTag)
                        .mimirFocusTileRing(
                            progress = ringProgress,
                            color = if (state.mode == HomeTomeFolioMode.ARRANGE) {
                                palette.accentBright
                            } else {
                                palette.tileFocusRing
                            },
                            cornerRadius = ringSeat.cornerDp.dp,
                        ),
                )

                // Finite travel cues replace the page runes: a ᚲ names real off-window content on its
                // side only; the true terminus stays an honest edge (Home continuation vocabulary).
                val leftCueAlpha by animateFloatAsState(
                    targetValue = if (travelStart > 0) 1f else 0f,
                    animationSpec = if (reducedMotion) snap() else {
                        tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)
                    },
                    label = "home-tome-travel-cue-left",
                )
                val rightCueAlpha by animateFloatAsState(
                    targetValue = if (travelStart < maxTravelStart) 1f else 0f,
                    animationSpec = if (reducedMotion) snap() else {
                        tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)
                    },
                    label = "home-tome-travel-cue-right",
                )
                Text(
                    text = "ᚲ",
                    style = MimirTypeScale.RuneAction.copy(fontSize = 14.sp),
                    color = hero.hardware,
                    modifier = Modifier
                        .offset(x = gridLeft, y = (verticalPartitions.continuationTop + 2f).dp)
                        .graphicsLayer {
                            alpha = leftCueAlpha
                            scaleX = -1f
                        },
                )
                Text(
                    text = "ᚲ",
                    style = MimirTypeScale.RuneAction.copy(fontSize = 14.sp),
                    color = hero.hardware,
                    modifier = Modifier
                        .offset(
                            x = gridLeft + gridWidth - 14.dp,
                            y = (verticalPartitions.continuationTop + 2f).dp,
                        )
                        .graphicsLayer { alpha = rightCueAlpha },
                )
                MimirControlHintText(
                    text = homeTomeFolioFooter(
                        res = libraryResources(),
                        mode = state.mode,
                        carrying = state.carriedItemId != null,
                        itemCount = state.members.size,
                    ),
                    style = MimirTypeScale.ControlHintCompact,
                    color = hero.onCover.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(
                            x = horizontalPartitions.footerLegendLeft.dp,
                            y = (verticalPartitions.footerTop + 8f).dp,
                        )
                        .width(horizontalPartitions.footerLegendWidth.dp),
                )

                // Touch close target is deliberately text-light and inside the fixed footer envelope.
                MimirChromeText(
                    text = stringResource(R.string.library_home_folio_back),
                    style = MimirTypeScale.MenuAction,
                    color = hero.onCover.copy(alpha = 0.52f),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .offset(
                            x = horizontalPartitions.closeLeft.dp,
                            y = (verticalPartitions.footerTop + 8f).dp,
                        )
                        .width(horizontalPartitions.closeWidth.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onFirstInteraction()
                            onBack()
                        },
                )
            } // measured inner viewport wrapper
        } // inner BoxWithConstraints
    } // MimirLiquidGlassPanel
} // outer BoxWithConstraints
} // HomeTomeFolio

@Composable
private fun HomeTomeEmptyFolioCell(
    modifier: Modifier,
    onActivate: (() -> Unit)? = null,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    // An EMPTY slot plate, not box art -- deliberately its own 10dp and not MimirBoxArtCorner, so a
    // future "rounder box art" design change cannot silently drag an empty cell along with it.
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(hero.coverBottom.copy(alpha = 0.46f))
            .border(0.8.dp, hero.hardware.copy(alpha = 0.30f), shape)
            .then(
                onActivate?.let { activate ->
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = activate,
                    )
                } ?: Modifier,
            ),
    )
}

@Composable
private fun HomeTomeTeachingPlate(
    focused: Boolean,
    modifier: Modifier,
    onActivate: () -> Unit,
    /** See `HomeTomeFolio`'s `departing`: the ring is OUT on the recorded frame, not fading on it. */
    ringSuppressed: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val shape = RoundedCornerShape(10.dp)
    val animatedFocusProgress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = mimirFocusRingSpec(),
        label = "home-tome-empty-focus",
    )
    val focusProgress: () -> Float = { if (ringSuppressed) 0f else animatedFocusProgress.value }
    val emptyDescription = stringResource(R.string.library_home_folio_empty_description)
    Column(
        modifier = modifier
            .mimirFocusTileRing(
                progress = focusProgress,
                color = palette.tileFocusRing,
                cornerRadius = 10.dp,
            )
            .clip(shape)
            .background(hero.coverMiddle.copy(alpha = 0.92f))
            .border(0.8.dp, hero.hardware.copy(alpha = 0.54f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onActivate,
            )
            .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.md)
            .semantics {
                contentDescription = emptyDescription
                selected = focused
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.library_home_folio_empty_title),
            style = MimirTypeScale.Row,
            color = hero.onCover,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.library_home_folio_empty_hint),
            style = MimirTypeScale.Support,
            color = hero.onCover.copy(alpha = 0.72f),
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HomeTomeFolioCell(
    member: HomeTomeMember,
    focused: Boolean,
    carried: Boolean,
    modifier: Modifier,
    /**
     * Null renders a PAINT-ONLY cell: no click target and no accessibility identity. That is what the
     * outgoing-page backdrop needs — it must look like the page it is replacing without ever becoming a
     * second thing to touch, focus, arrange, or announce.
     */
    onActivate: (() -> Unit)?,
    /** Resolved by [homeTomeWellArtUri]; defaults keep every non-Tome-aware caller on cover art. */
    artUri: String? = member.artworkUri,
    physical: Boolean = false,
    /**
     * Skips building this cell's Coil painter for one frame while the folio's opening frame is
     * still being measured. [artUri] is deliberately still passed: a deferred cell must read as
     * "art is coming" (quiet backdrop), never as "there is no art" (placeholder). See the rationale
     * at the `folioArtAttached` state in [HomeTomeFolio].
     */
    artDeferred: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val shape = RoundedCornerShape(MimirBoxArtCorner)
    // The gesture node below is keyed on `Unit` and reads its target through `rememberUpdatedState`,
    // deliberately NOT keyed on the lambda. `pointerInput`'s keys are a RESTART contract: a changed
    // key disposes the detector and cancels the gesture in flight. This cell USED to recompose on every
    // frame of a page travel (its `animatedX`/`animatedY` were read straight into composition); they are
    // now read in the cell's `offset {}` lambda, so it recomposes only on a real change. The restart
    // contract stays, because a recomposition still happens on focus, carry and art changes.
    // Measured, not assumed: the lambda-keyed variant still delivered taps in the Compose harness,
    // because strong skipping (default-on from Compose compiler 2.0.20; this tree is Kotlin 2.0.21)
    // memoizes the literal despite its unstable `member` capture. So this is hardening that makes
    // the node independent of that compiler behaviour, not a fix for a live defect.
    val currentActivate by rememberUpdatedState(onActivate)
    val context = LocalContext.current
    val request = remember(artUri, context) {
        artUri?.let { uri ->
            mimirCoverRequest(context, uri)
        }
    }
    val painter = if (artDeferred) null else request?.let { rememberAsyncImagePainter(it) }
    val painterState = painter?.state
    val artReady = when (painterState) {
        is AsyncImagePainter.State.Success -> true
        is AsyncImagePainter.State.Loading -> painterState.painter != null
        else -> false
    }
    val intrinsic = painterState?.painter?.intrinsicSize
    val intrinsicSpecified = intrinsic?.isSpecified == true
    val (intrinsicWidth, intrinsicHeight) = homeTomeFolioArtIntrinsicDimensions(
        intrinsicWidth = intrinsic?.width ?: 0f,
        intrinsicHeight = intrinsic?.height ?: 0f,
        specified = intrinsicSpecified,
    )
    val fillWell = homeTomeFolioArtUsesFillScale(
        physical = physical,
        intrinsicWidth = intrinsicWidth,
        intrinsicHeight = intrinsicHeight,
    )
    val contentScale = if (fillWell) ContentScale.Crop else ContentScale.Fit
    val showPlaceholder = homeTomeFolioArtShowsPlaceholder(artUri, artReady)
    val showQuietBackdrop = homeTomeFolioArtShowsQuietBackdrop(artUri, artReady)
    Box(
        modifier = modifier
            .then(
                if (carried) {
                    Modifier.mimirSurfaceShadow(
                        palette = palette,
                        elevation = MimirSpacing.elevationCard,
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                translationY = if (carried) (-HomeTomeCarryLiftDp).dp.toPx() else 0f
            }
            .then(
                if (onActivate == null) {
                    Modifier
                } else {
                    Modifier
                        // A raw pointer gesture, never `clickable`: `clickable` builds an
                        // `AbstractClickableNode`, which constructs a `FocusableNode` and therefore a
                        // real `FocusTargetModifierNode` — a second Compose focus target beside the
                        // single FocusAuthority this cell already registers with through
                        // `mimirFocusable`. `tools/mimir_ui_invariants_gate.py` greps only for the
                        // literals `.focusable(`/`focusTarget(`, so it cannot see that; the same
                        // reasoning is already written down at `GamelistCarousel.kt`'s media row.
                        // `onTap` only — an `onDoubleTap` registration would defer every single tap
                        // by the platform double-tap timeout.
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { currentActivate?.invoke() })
                        }
                        // Carries the click ACTION that `clickable` used to publish to accessibility
                        // services, without carrying its focus node — the pairing `MimirKeyboard`
                        // already uses for its own touch-primary keys.
                        //
                        // `mergeDescendants = true` is a PARITY restore:
                        // `AbstractClickableNode.getShouldMergeDescendantSemantics` is a hardcoded
                        // `return true` in the shipped `foundation-android:1.7.3` (verified from the
                        // artifact's bytecode), so this seat was ONE accessibility node before. Plain
                        // `.semantics {}` would split it and leave the action on a node a screen
                        // reader never lands on.
                        .semantics(mergeDescendants = true) {
                            contentDescription = member.title
                            selected = focused
                            onClick(label = member.title) {
                                currentActivate?.invoke()
                                true
                            }
                        }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (showQuietBackdrop) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(hero.coverBottom.copy(alpha = 0.22f)),
            )
        }
        if (artReady && painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .then(if (physical) Modifier.padding(MimirSpacing.xs) else Modifier),
            )
        } else if (showPlaceholder) {
            // F13. A game with no art is still a game: it
            // wears the blank-book title lockup every other surface draws for it (launch card, gamelist,
            // Library), with the platform code the folio header already uses as its badge. The plain
            // coverBottom plate that stood here was the EMPTY slot's plate, so an occupied cell read as empty.
            MediaArtBookCover(
                title = member.title,
                platformIdValue = member.platformId?.value,
                focused = focused,
                platformLabel = member.platformId?.let { platformId -> mimirPlatformShelfmarkCode(platformId.value) },
                // Set to the book: a name wider than the leaf is shrunk, then hyphenated ("Earth-" / "bound"), and never cut inside a word (sweep 10-11).
                fitTitle = true,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(HomeTomeFolioTitleBookTestTag),
            )
        }
    }
}

@Composable
private fun HomeTomeAdjacentArtPrewarm(
    members: List<HomeTomeMember>,
    pageIndex: Int,
) {
    val context = LocalContext.current
    val candidates = remember(members, pageIndex) {
        homeTomeWarmWindow(members, pageIndex)
            .mapNotNull { member ->
                homeTomeWellArtUri(
                    physicalMediaUri = member.physicalMediaUri,
                    artworkUri = member.artworkUri,
                )
            }
            .filter(::isHomeTomeLocalArtUri)
            .distinct()
    }
    LaunchedEffect(candidates, context) {
        val loader: ImageLoader = context.imageLoader
        candidates.forEach { uri ->
            loader.enqueue(
                mimirCoverRequest(context, uri),
            )
        }
    }
}

/**
 * The focused Hero and its immediate geometric neighbours are the only 1080-class warm set. This
 * lets the Folio backdrop follow horizontal or vertical D-pad focus without a fallback flash while
 * keeping the existing page-wide cover prewarm bounded to its smaller decode envelope.
 */
@Composable
private fun HomeTomeFocusedBackdropPrewarm(
    memberSlots: List<HomeTomeMember?>,
    focusedIndex: Int,
) {
    val context = LocalContext.current
    val candidates = remember(memberSlots, focusedIndex) {
        listOf(
            focusedIndex - HomeTomeFolioColumns,
            focusedIndex - 1,
            focusedIndex,
            focusedIndex + 1,
            focusedIndex + HomeTomeFolioColumns,
        )
            .mapNotNull(memberSlots::getOrNull)
            .mapNotNull(HomeTomeMember::backdropArtUri)
            .filter(::isHomeTomeLocalArtUri)
            .distinct()
    }
    LaunchedEffect(candidates, context) {
        val loader: ImageLoader = context.imageLoader
        candidates.forEach { uri ->
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(MimirHomeStaticHeroDecodeWidthPx, MimirHomeStaticHeroDecodeHeightPx)
                    .scale(Scale.FILL)
                    .memoryCacheKey(mimirHomeStaticHeroCacheKey(uri))
                    .build(),
            )
        }
    }
}

internal fun isHomeTomeLocalArtUri(uri: String): Boolean =
    uri.startsWith("content://") || uri.startsWith("file://") || uri.startsWith("android.resource://")
