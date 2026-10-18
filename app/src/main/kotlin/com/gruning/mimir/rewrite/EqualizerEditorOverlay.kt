package com.gruning.mimir.rewrite

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresets
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MediaOutputWatcher
import com.gruning.mimir.rewrite.core.audio.MimirEqualizer
import com.gruning.mimir.rewrite.core.audio.MimirMusicControl
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.companionnative.MusicPanelEdgeClearanceDp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import androidx.compose.material3.Text
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirFooterKeycap
import com.gruning.mimir.rewrite.core.ui.MimirFilledControlRingReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.MimirGlassScrim
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.liquidGlassContentPalette
import com.gruning.mimir.rewrite.core.ui.mimirGlassPanelEntry
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.feature.settings.settingsModalWidthCapDp
import kotlin.math.roundToInt

/** The editor's card, for the layout harness. */
internal const val EqualizerEditorCardTestTag = "music/equalizer/card"

/** The editor's scroll viewport (the controls' window under the header), for the layout harness. */
internal const val EqualizerEditorViewportTestTag = "music/equalizer/viewport"

/** The foot hint (the keycap and its word), for the layout harness. */
internal const val EqualizerEditorFootHintTestTag = "music/equalizer/foot-hint"

/** The read-only curve (it says nothing to a screen reader; the tag is for the layout harness only). */
internal const val EqualizerEditorCurveTestTag = "music/equalizer/curve"

/** The authored width cap of the sheet: Settings' modals share it, narrowed on a narrow display by the settings width gate. */
internal const val EqualizerEditorWidthCapDp = 640f

/** The curve preview's height and its dB range (the curve can exceed a slider's twelve where a band and a shelf add). */
private val CurvePreviewHeight = 72.dp

/** The scroll content's top padding and the gap between its children: with [CurvePreviewHeight] they are the block the curve takes at the top of the controls. */
private val EqualizerContentTopPadding = 8.dp
private val EqualizerContentGap = 8.dp

/** How far the controls' window dissolves what scrolls under its top and bottom edges, so a row cut by an edge fades out and does not end in a lone thumb tip (sweep 10-09). */
internal val EqualizerEdgeFade = 16.dp
private const val CurveRangeDb = 18f

/**
 * Settings › Music › Equalizer: a hard-modal glass sheet over the Settings pane, built from Utility's own rows. It arrives on the
 * M panel's entry tier (180 ms, [com.gruning.mimir.rewrite.core.ui.MimirMotion.Easing], the 0.96 to 1 lift, the scrim on the same
 * clock) and has no exit: the host detaches it on the same frame.
 *
 * Stateless, so the layout harness drives it with plain values. [authority] null draws no focus registration (tests). A measured
 * zero viewport (a live font-scale or display change) holds the last valid size and draws nothing for that frame
 * ([rememberMeasuredViewport]), so a transient zero neither crashes nor moves the card.
 *
 * Reading order: the header (the heading, then the information block: where the sound goes, what is playing, Mimir's own track's format right under
 * its entry, which Equalizer is active), a read-only curve of the combined response, then the controls: the one switch, the preset, Bass, Treble and the ten bands. While the Equalizer is off only its switch is reachable and
 * the rest are dimmed.
 */
@Composable
internal fun EqualizerEditorOverlay(
    settings: EqualizerSettings,
    focused: EqualizerEditorControl?,
    authority: FocusAuthority?,
    onToggle: () -> Unit,
    onPresetStep: (Int) -> Unit,
    onGainRequested: (EqualizerEditorControl, Float) -> Unit,
    onRequestFocus: (EqualizerEditorControl) -> Unit,
    onDismiss: () -> Unit,
    systemEq: SystemEqualizerState = SystemEqualizerState(),
    infoLines: List<EqualizerInfoLine> = emptyList(),
) {
    val palette = LocalMimirPalette.current.liquidGlassContentPalette()
    val entry = rememberMimirGlassProgress(visible = true)
    BackHandler(enabled = true, onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val measuredWidth = maxWidth
        val measuredHeight = maxHeight
        if (!measuredWidth.value.isFinite() || !measuredHeight.value.isFinite()) return@BoxWithConstraints
        val viewport = rememberMeasuredViewport(
            maxWidth = measuredWidth,
            maxHeight = measuredHeight,
            inDomain = { widthDp, heightDp -> widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints
        Box(Modifier.mimirMeasuredViewport(viewport)) {
            MimirGlassScrim(progress = entry)
            Box(
                Modifier
                    .fillMaxSize()
                    // A tap that misses the card closes the editor and keeps the changes. A pointer handler, not clickable():
                    // clickable adds a Compose focus target and the one FocusAuthority owns focus here.
                    .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) },
            )
            val density = LocalDensity.current
            val insetPx = with(density) { MusicPanelEdgeClearanceDp.dp.roundToPx() }
            val widthCapPx = with(density) {
                settingsModalWidthCapDp(viewport.width.value, viewport.height.value, EqualizerEditorWidthCapDp).dp.roundToPx()
            }
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    EqualizerEditorCard(
                        settings = settings,
                        systemEq = systemEq,
                        infoLines = infoLines,
                        focused = focused,
                        authority = authority,
                        palette = palette,
                        entry = entry,
                        onToggle = onToggle,
                        onPresetStep = onPresetStep,
                        onGainRequested = onGainRequested,
                        onRequestFocus = onRequestFocus,
                    )
                },
            ) { measurables, constraints ->
                val windowWidth = constraints.maxWidth
                val windowHeight = constraints.maxHeight
                val cardWidth = minOf(widthCapPx, windowWidth - 2 * insetPx).coerceAtLeast(0)
                val cardMaxHeight = (windowHeight - 2 * insetPx).coerceAtLeast(0)
                val card = measurables.single().measure(Constraints(cardWidth, cardWidth, 0, cardMaxHeight))
                layout(windowWidth, windowHeight) {
                    card.place((windowWidth - card.width) / 2, (windowHeight - card.height) / 2)
                }
            }
        }
    }
}

@Composable
private fun EqualizerEditorCard(
    settings: EqualizerSettings,
    systemEq: SystemEqualizerState,
    infoLines: List<EqualizerInfoLine>,
    focused: EqualizerEditorControl?,
    authority: FocusAuthority?,
    palette: MimirThemePalette,
    entry: () -> Float,
    onToggle: () -> Unit,
    onPresetStep: (Int) -> Unit,
    onGainRequested: (EqualizerEditorControl, Float) -> Unit,
    onRequestFocus: (EqualizerEditorControl) -> Unit,
) {
    val enabled = settings.enabled
    // The curve is audible when the Equalizer is on (through the global engine, or in Mimir's own music): only then do its controls mean something.
    val live = equalizerEditorLive(settings)
    val scroll = rememberScrollState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val geometry = remember { mutableStateMapOf<EqualizerEditorControl, UtilityRailItemGeometry>() }
    // Row geometry is read as the row's position inside the viewport, not as two window positions: the card scales 0.96 to 1 on its
    // entry and a window position carries that scale (see the music panel's card for the full reasoning).
    val viewportCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val rowCoords = remember { mutableMapOf<EqualizerEditorControl, LayoutCoordinates>() }
    fun remeasureRows() {
        val viewport = viewportCoords[0]?.takeIf { it.isAttached && it.size.height > 0 } ?: return
        rowCoords.entries.forEach { (control, row) ->
            if (!row.isAttached || row.size.height <= 0) return@forEach
            val measured = UtilityRailItemGeometry(
                topPx = viewport.localPositionOf(row, Offset.Zero).y.roundToInt() + scroll.value,
                heightPx = row.size.height,
            )
            if (geometry[control] != measured) geometry[control] = measured
        }
    }
    val order = remember(live) { equalizerEditorReachable(live) }
    val focusedGeometry = focused?.let(geometry::get)
    // The card scrolls only when the display is shorter than it: the focused control then carries it the way Utility's rail does
    // (the first control reveals the top, the last the bottom, the rest settle on the centre). The foot is the foot of the sliders: while
    // the Equalizer is off its switch is the only control reachable, and settles at the top like the first control of any list rather
    // than carrying the sheet down to the dimmed sliders.
    // The curve and the air under it, in the scroll's own px: a settlement that lands inside it would leave the curve half under the header, its 0 dB line standing right under the
    // OUTPUT line (sweep 10-09, font 1.3), so it goes to one side of it ([equalizerSettleOffCurve]).
    val density = LocalDensity.current
    val curveBlockPx = with(density) { (EqualizerContentTopPadding + CurvePreviewHeight + EqualizerContentGap).roundToPx() }
    val ringReachPx = with(density) { MimirFocusTileRingReach.roundToPx() }
    LaunchedEffect(focused, focusedGeometry, viewportHeightPx, scroll.maxValue, order) {
        val reveal = when {
            focused == null || focused !in order -> UtilityRailReveal.NONE
            focused == order.first() -> UtilityRailReveal.START
            focused == order.last() && focused.isSlider -> UtilityRailReveal.END
            else -> UtilityRailReveal.ROW
        }
        utilityRailSettlementTarget(reveal, focusedGeometry, viewportHeightPx, scroll.maxValue)?.let { target ->
            if (scroll.maxValue > 0) {
                val settled = if (reveal == UtilityRailReveal.ROW) {
                    equalizerSettleOffCurve(target, curveBlockPx, focusedGeometry, viewportHeightPx, ringReachPx, scroll.maxValue)
                } else {
                    target
                }
                scroll.animateScrollTo(settled, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
            }
        }
    }
    // First frame beat, then the ring: a key only takes focus once it has laid out and registered.
    if (authority != null) {
        LaunchedEffect(authority) {
            withFrameNanos { }
            if (equalizerEditorControlFor(authority.state.value.focused) == null) {
                authority.requestFocus(equalizerEditorFocusKey(EqualizerEditorControl.TOGGLE))
            }
        }
    }

    @Composable
    fun control(control: EqualizerEditorControl, reachable: Boolean): Modifier {
        var modifier: Modifier = Modifier
        if (authority != null && reachable) {
            modifier = modifier.mimirFocusable(
                authority = authority,
                key = equalizerEditorFocusKey(control),
                scope = EqualizerEditorScope,
                autoBringIntoView = false,
            )
        }
        return modifier.onGloballyPositioned { coordinates ->
            rowCoords[control] = coordinates
            remeasureRows()
        }
    }

    // Derived once per settings value: the headroom runs a frequency sweep and the curve evaluates every filter.
    val headroomLine = remember(settings) { equalizerHeadroomLine(settings.effectiveHeadroomDb) }
    val targetCurve = remember(live, settings.bandsDb, settings.bassDb, settings.trebleDb) {
        // Nothing equalizing is a byte copy: the response is flat whatever the sliders say.
        MimirEqualizer.responseCurveDb(if (live) settings else EqualizerSettings())
    }
    // A preset moves many bands at once: they glide to it, and the curve with them, on the overlay clock. One band moved by a finger or
    // a step snaps (direct manipulation never lags the hand); reduced motion snaps both.
    val reducedMotion = mimirReducedMotion()
    val lastSettings = remember { arrayOfNulls<EqualizerSettings>(1) }
    val bulkMove = remember(settings) {
        val before = lastSettings[0]
        lastSettings[0] = settings
        before != null && equalizerBandsMoved(before, settings) > 1
    }
    val glide = bulkMove && !reducedMotion
    val curve = rememberMorphedCurve(targetCurve, animate = glide)

    MimirLiquidGlassPanel(
        modifier = Modifier
            .mimirGlassPanelEntry(entry)
            .testTag(EqualizerEditorCardTestTag)
            .pointerInput(Unit) {
                // Keeps the card's bounds out of the backdrop's reach so a tap on it never closes the editor; never consumes.
                awaitEachGesture { awaitFirstDown() }
            },
        contentPadding = 0.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Column(
            modifier = Modifier.padding(PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MimirChromeText(EqualizerEditorHeading, style = MimirTypeScale.RunicEyebrow, color = palette.accent)
            // Where the sound goes, what is playing (with Mimir's own track's format right under its entry, never under another app's), which
            // Equalizer is active: natural-case data, as the Music panel's own are (a unit stays "kHz", a name stays as its owner wrote it),
            // wrapping at word boundaries (a name or a title is never cut mid-word; only the third line of one is ellipsized).
            infoLines.forEach { line ->
                Text(
                    line.text,
                    style = MimirTypeScale.SupportCompact,
                    color = when (line.tone) {
                        EqualizerInfoTone.NORMAL -> palette.textSecondary
                        EqualizerInfoTone.ATTENTION -> palette.textPrimary
                        EqualizerInfoTone.QUIET -> palette.textMuted
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .weight(1f, fill = false)
                .testTag(EqualizerEditorViewportTestTag)
                .equalizerEdgeFade(scroll)
                .onGloballyPositioned { coordinates ->
                    viewportCoords[0] = coordinates
                    viewportHeightPx = coordinates.size.height
                    remeasureRows()
                },
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scroll)
                    .padding(
                        PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = EqualizerContentTopPadding,
                            // The card clips at its bottom edge (no content padding): clear the ring and its flare, as Utility's footer does.
                            bottom = MimirFilledControlRingReach + MimirFocusFlareReach,
                        ),
                    ),
                verticalArrangement = Arrangement.spacedBy(EqualizerContentGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EqualizerCurvePreview(curve = curve, palette = palette, active = live)
                UtilityToggleRow(
                    label = EqualizerToggleLabel,
                    supportingText = EqualizerToggleSupporting,
                    checked = enabled,
                    enabled = true,
                    focused = focused == EqualizerEditorControl.TOGGLE,
                    palette = palette,
                    modifier = control(EqualizerEditorControl.TOGGLE, true),
                    onRequestFocus = { onRequestFocus(EqualizerEditorControl.TOGGLE) },
                    onToggle = onToggle,
                    supportingMaxLines = 3,
                )
                val presetLabel = EqualizerPresets.label(settings.presetId)
                UtilityChoiceRow(
                    label = EqualizerPresetLabel,
                    supportingText = headroomLine,
                    valueLabel = presetLabel,
                    stateDescription = presetLabel,
                    focused = focused == EqualizerEditorControl.PRESET,
                    palette = palette,
                    modifier = control(EqualizerEditorControl.PRESET, live).dimmedUnless(live),
                    onRequestFocus = { onRequestFocus(EqualizerEditorControl.PRESET) },
                    onStep = onPresetStep,
                    canStepLeft = live,
                    canStepRight = live,
                    enabled = live,
                    compactSupporting = true,
                )
                EqualizerEditorControl.entries.filter { it.isSlider }.forEach { slider ->
                    EqualizerBandRow(
                        slider = slider,
                        db = equalizerGainOf(settings, slider),
                        glide = glide,
                        live = live,
                        focused = focused == slider,
                        palette = palette,
                        focusModifier = control(slider, live),
                        onRequestFocus = onRequestFocus,
                        onGainRequested = onGainRequested,
                    )
                }
            }
        }
        // The hint is held in its seat while nothing asks for it, so it appearing moves nothing. The seat is the keycap's height and its room.
        Box(Modifier.fillMaxWidth().height(EqualizerFootSeatHeight).padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
            val hint = equalizerFootHint(settings, systemEq, focused)
            if (hint != null) EqualizerFootHintRow(hint, palette)
        }
    }
}

/** The foot's seat: the footer keycap's 24 dp plate and the 4 dp the card keeps under it. */
private val EqualizerFootSeatHeight = 28.dp

/** The gap between the footer keycap and its word: the shell legend's own. */
private val EqualizerFootKeycapGap = 6.dp

/** The foot hint, as every footer draws a key: the etched keycap ([MimirFooterKeycap], the pad's own label for the key) and its word in the legend's hint style. A key the controller map leaves unbound is not drawn. */
@Composable
private fun EqualizerFootHintRow(hint: EqualizerFootHint, palette: MimirThemePalette) {
    val key = LocalMimirControllerHintLabels.current.labelFor(hint.token)
    if (key.isBlank()) return
    Row(
        modifier = Modifier.testTag(EqualizerEditorFootHintTestTag),
        horizontalArrangement = Arrangement.spacedBy(EqualizerFootKeycapGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MimirFooterKeycap(key = key, palette = palette)
        MimirChromeText(
            hint.verb,
            style = MimirTypeScale.ControlHint,
            color = palette.textMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Where a focus settlement lands once the curve is not to be left cut. [target] is the settlement Utility's rail asks for (the focused row on the window's centre); when it falls inside the
 * curve's block ([curveBlockPx], the curve and the air under it) the curve would stand half under the header, so the settlement goes to the top (0) when the row still stands whole
 * with its ring's room at the top ([reachPx]), and past the block otherwise. A target at the top, or past the block, is as asked. Never beyond [maxScroll].
 */
internal fun equalizerSettleOffCurve(
    target: Int,
    curveBlockPx: Int,
    row: UtilityRailItemGeometry?,
    viewportHeightPx: Int,
    reachPx: Int,
    maxScroll: Int,
): Int {
    if (target <= 0 || target >= curveBlockPx) return target.coerceIn(0, maxScroll.coerceAtLeast(0))
    val standsAtTop = row != null && viewportHeightPx > 0 && row.topPx + row.heightPx + reachPx <= viewportHeightPx
    return (if (standsAtTop) 0 else curveBlockPx).coerceIn(0, maxScroll.coerceAtLeast(0))
}

/**
 * One band's slider. It owns the glide of its value (audit 2026-10-14, M-04 (2)): a preset moves many bands at once and each glides to it, and the animated fraction is read here,
 * in the band's own scope, so a frame of the glide recomposes this row and not the whole card (the curve, the toggle, the preset row and the other bands) the way the read did in
 * the card's body. Off a bulk move ([glide] false) the value is the target itself the same frame: a finger or a step is never a frame behind.
 */
@Composable
private fun EqualizerBandRow(
    slider: EqualizerEditorControl,
    db: Float,
    glide: Boolean,
    live: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    focusModifier: Modifier,
    onRequestFocus: (EqualizerEditorControl) -> Unit,
    onGainRequested: (EqualizerEditorControl, Float) -> Unit,
) {
    val targetFraction = EqGain.fractionForDb(db)
    val gliding by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "eq-band",
    )
    val shownFraction = if (glide) gliding else targetFraction
    UtilitySliderRow(
        label = equalizerControlLabel(slider),
        valueLabel = equalizerGainReadout(db),
        fraction = shownFraction,
        target = BrightnessTarget.EQ_GAIN,
        enabled = live,
        focused = focused,
        palette = palette,
        focusModifier = focusModifier,
        onRequestFocus = { onRequestFocus(slider) },
        onValueChange = { onGainRequested(slider, it) },
        steps = EqGain.Steps,
        markerFraction = EqGain.CentreFraction,
        fillFromFraction = EqGain.CentreFraction,
    )
}

/**
 * The window under the editor's header dissolves what scrolls under its edges: [EqualizerEdgeFade] at the top once the controls have scrolled, and at the bottom while there is more below.
 * A control cut by the window used to end in a lone mark (the tip of the Bass thumb, 1x) or half a word ("BASS", 1.3x); now the cut row fades out. At rest on the first control (the top)
 * and at the last (the bottom) nothing is faded. The mask is multiplied in the draw phase, so nothing recomposes while it scrolls.
 */
@Composable
private fun Modifier.equalizerEdgeFade(scroll: androidx.compose.foundation.ScrollState): Modifier {
    // One layer paint for the life of the window, not one per drawn frame.
    val layerPaint = remember { Paint() }
    // And the two masks built once per edge height, not once per scrolled frame (audit 2026-10-14, M-04 (3)): a scroll redraws this every frame and the gradients depend only
    // on the edge and the viewport's height.
    val masks = remember { EqualizerEdgeMasks() }
    return this.drawWithContent {
        val edge = EqualizerEdgeFade.toPx().coerceAtMost(size.height / 3f)
        val fadeTop = scroll.value > 0
        val fadeBottom = scroll.value < scroll.maxValue
        if (!fadeTop && !fadeBottom) {
            drawContent()
            return@drawWithContent
        }
        // An explicit layer for the window's content, so the mask below multiplies only it and never what is behind (a plain compositing strategy is not honoured by a software canvas).
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), layerPaint)
            this@drawWithContent.drawContent()
            // The mask is clear for the last fifth of the fade, so the lowest pixels of a cut control (a thumb's tip) are gone and not a faint speck.
            masks.fit(edge, size.height)
            if (fadeTop) {
                drawRect(
                    brush = masks.top,
                    size = Size(size.width, edge),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (fadeBottom) {
                drawRect(
                    brush = masks.bottom,
                    topLeft = Offset(0f, size.height - edge),
                    size = Size(size.width, edge),
                    blendMode = BlendMode.DstIn,
                )
            }
            canvas.restore()
        }
    }
}

/**
 * The edge fade's two masks, rebuilt only when the edge or the viewport's height changes (a scroll redraws the fade every frame; the brushes do not change with it). Plain
 * memory read only inside the draw, so nothing here is observed.
 */
private class EqualizerEdgeMasks {
    private var edge = Float.NaN
    private var height = Float.NaN
    var top: Brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black)
        private set
    var bottom: Brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent)
        private set

    fun fit(edge: Float, height: Float) {
        if (edge == this.edge && height == this.height) return
        this.edge = edge
        this.height = height
        top = Brush.verticalGradient(
            0f to Color.Transparent,
            0.2f to Color.Transparent,
            0.4f to Color.Black.copy(alpha = 0.35f),
            1f to Color.Black,
            startY = 0f,
            endY = edge,
        )
        bottom = Brush.verticalGradient(
            0f to Color.Black,
            0.6f to Color.Black.copy(alpha = 0.35f),
            0.8f to Color.Transparent,
            1f to Color.Transparent,
            startY = height - edge,
            endY = height,
        )
    }
}

/** A row the switch has put out of reach: dimmed the way Utility's disabled sliders are, on a layer that keeps any halo whole, and eased both ways when the switch flips. */
@Composable
private fun Modifier.dimmedUnless(enabled: Boolean): Modifier {
    val dim by utilityDisabledAlpha(enabled)
    return graphicsLayer {
        alpha = dim
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/**
 * The combined response of the equalizer as one thin line over 20 Hz to 20 kHz, with the 0 dB line behind it. Read-only: it takes
 * no focus and no touch, and says nothing to a screen reader (the sliders carry the values).
 */
@Composable
private fun EqualizerCurvePreview(curve: FloatArray, palette: MimirThemePalette, active: Boolean) {
    val line = if (active) palette.accentBright else palette.textMuted
    val zero = palette.outline
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(CurvePreviewHeight)
            .clearAndSetSemantics { testTag = EqualizerEditorCurveTestTag },
    ) {
        if (size.width <= 0f || size.height <= 0f || curve.size < 2) return@Canvas
        val mid = size.height / 2f
        drawLine(zero, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        val path = Path()
        val last = (curve.size - 1).toFloat()
        curve.forEachIndexed { i, db ->
            val x = size.width * i / last
            val y = mid - (db.coerceIn(-CurveRangeDb, CurveRangeDb) / CurveRangeDb) * (mid - 2.dp.toPx())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = line,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** The editor as the app shell hosts it: the open controller's input and the engine's flows, composed only while it is open. */
@Composable
internal fun MimirEqualizerEditorHost(bindings: MimirAppBindings) {
    val editor = bindings.settings.equalizerEditor ?: return
    val settings by editor.settings.collectAsState()
    val systemEq by editor.systemEq.collectAsState()
    val focus by editor.authority.state.collectAsState()
    // Where the sound goes is watched only while the editor is open: the callback is held for exactly that long.
    val context = LocalContext.current
    val watcher = remember { MediaOutputWatcher(context.applicationContext) }
    DisposableEffect(watcher) {
        watcher.start()
        onDispose { watcher.stop() }
    }
    val output by watcher.output.collectAsState()
    // What is playing, from any app: the music control's own merge of Your music and the media-session reader's other player, and the grant.
    val music by MimirMusicControl.state.collectAsState()
    val infoLines = remember(output, music, settings, systemEq) { equalizerInfoLines(output, music, settings, systemEq) }
    EqualizerEditorOverlay(
        settings = settings,
        infoLines = infoLines,
        focused = equalizerEditorControlFor(focus.focused),
        authority = editor.authority,
        onToggle = editor::onToggle,
        systemEq = systemEq,
        onPresetStep = { editor.onPresetStep(it) },
        onGainRequested = editor::onGainRequested,
        onRequestFocus = editor::requestFocus,
        onDismiss = bindings.settings.closeEqualizerEditor,
    )
}

/** How many of the sliders (Bass, Treble and the ten bands) sit at a different gain in [to] than in [from]. */
internal fun equalizerBandsMoved(from: EqualizerSettings, to: EqualizerSettings): Int =
    EqualizerEditorControl.entries.count { it.isSlider && equalizerGainOf(from, it) != equalizerGainOf(to, it) }

/** The curve between two responses: [t] 0 is [from], 1 is [to]. A change of length is not interpolated: [to] stands. */
internal fun lerpCurve(from: FloatArray, to: FloatArray, t: Float): FloatArray =
    if (from.size != to.size || t >= 1f) to else FloatArray(to.size) { i -> from[i] + (to[i] - from[i]) * t }

/**
 * The curve that is drawn: [target] itself when it holds still, and a morph from what was on screen to the new [target] over
 * [MimirMotion.OverlayMs] on the house easing when [animate]. A new target restarts from wherever the morph is, so it never jumps.
 * The two ends and the progress are written in one snapshot, so no frame shows the new end at the old progress.
 */
@Composable
private fun rememberMorphedCurve(target: FloatArray, animate: Boolean): FloatArray {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    var progress by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        val onScreen = lerpCurve(from, to, progress)
        Snapshot.withMutableSnapshot {
            from = onScreen
            to = target
            progress = if (animate) 0f else 1f
        }
        if (animate) {
            animate(0f, 1f, animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)) { value, _ -> progress = value }
        }
    }
    return lerpCurve(from, to, progress)
}
