package com.gruning.mimir.rewrite.core.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full-screen Bottom trackpad. It is its own window, owned by Mimir's
 * Accessibility service, so it stays open until the player closes it: an injected click, the keyboard hiding or Keys
 * never close it. This is the composable that window hosts.
 *
 *  the field is a rune wall that glows under the finger, the scroll rail is a drum turned with the
 * thumb, the header carries Back and the pointer speed, and the hold ring appears only for a real hold.
 *
 * Rune Pad Forge: the field is a bound slab (carved surface, iron brackets and rivets,
 * a resting rune shimmer), the rail is a ribbed rune drum, scrolling drifts the wall, the header plates breathe an ember glow,
 * Back reads "Back ↩" and the speed is a Slow to Fast bar gauge a tap sets directly. Every effect is drawn in the draw phase from a
 * theme token and stands down while parked and under reduced motion.
 *
 * **Nothing recomposes per pointer move** (the pad shares the main thread with Main and the Bottom Presentation).
 * Finger positions, the glow trail and the wheel live in plain holders ([TrackpadPadFeedback], [MillSpin]); the one
 * frame-invalidated [MutableIntState] tick is read ONLY inside draw and layer blocks, so a move invalidates a frame,
 * never a composition. Every colour, size and clock is an existing token.
 *
 * @param session this activation's handle (what `MimirTrackpadHost.begin()` returned): every gesture goes through it, so
 *   once the activation is over a late callback is a no-op.
 * @param keyboardWaiting a text field has taken the input method while the pad is open: the Keyboard plate lights.
 * @param onKeyboard the Keyboard plate was tapped (the window closes the pad, revealing the keyboard).
 * @param onClose the Close plate was tapped.
 * @param closing the window is closing: the pad plays its entrance backwards.
 * @param onClosed the closing ramp has finished, on the pad's own frame clock: the window may go now. It is [MimirTrackpadPadRampMs]
 *   after [closing] turned true, the same clock the pad rose on.
 * @param recompositionProbe called on every recomposition of this composable, for the budget test; null in production.
 */
@Composable
fun MimirTrackpadPad(
    session: MimirTrackpadHost,
    keyboardWaiting: Boolean,
    onKeyboard: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    closing: Boolean = false,
    onClosed: () -> Unit = {},
    recompositionProbe: (() -> Unit)? = null,
) {
    SideEffect { recompositionProbe?.invoke() }

    val feedback = remember { TrackpadPadFeedback() }
    val tick = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val invalidator = remember { PadInvalidator(tick, scope) }
    val legendAlpha = remember { Animatable(1f) }
    val enter = remember { Animatable(0f) }
    val measurer = rememberTextMeasurer()
    val speed = remember(session) { mutableIntStateOf(session.speedStep) }
    val alive = remember(feedback) { { feedback.alive(SystemClock.uptimeMillis()) } }
    // pad8: the ember clocks, read only in draw blocks (nothing here recomposes per frame). Parked or reduced: they hold still.
    // Bounded motion: The life animates for a short window after the pad opens and after each touch, then eases to the static
    // resting look and asks for no frames. The window's own liveness is "not closing"; there is no pane parking here.
    val padLive = rememberUpdatedState(!closing)
    val life = rememberEmberLife(padLive)
    val reducedMotion = mimirReducedMotion()
    SideEffect { feedback.reducedMotion = reducedMotion }
    val mill = remember(session) {
        MillSpin(
            session,
            radiusPx = { feedback.millRadiusPx },
            onTurn = { dy -> feedback.noteScroll(dy, SystemClock.uptimeMillis()) },
        ).also { feedback.mill = it }
    }

    val host = remember(session, mill) {
        PadGestureHost(
            session = session,
            onScroll = { dy ->
                // A two-finger scroll turns the drum too and drifts the wall: drawn in the draw phase, never a recomposition.
                mill.turnVisual(dy)
                feedback.noteScroll(dy, SystemClock.uptimeMillis())
                invalidator.request()
            },
            onTap = { feedback.noteTap(SystemClock.uptimeMillis()) },
        ) {
            feedback.gestures += 1
            if (feedback.gestures == LegendGestures) {
                scope.launch { legendAlpha.animateTo(0f, tween(MimirMotion.SceneMs, easing = MimirMotion.Easing)) }
            }
            invalidator.request()
        }
    }
    val machine = remember(host) { TrackpadGestureMachine(host, gainScale = { TrackpadSpeed.factor(speed.intValue) }) }
    DisposableEffect(machine) {
        onDispose {
            machine.cancel()
            feedback.clear()
        }
    }
    // One clock both ways, on the house easing (RC-22): the pad rises on it and leaves on it, and the window goes when the leave ends.
    val currentOnClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(closing) {
        enter.animateTo(if (closing) 0f else 1f, tween(MimirTrackpadPadRampMs, easing = MimirMotion.Easing))
        if (closing) currentOnClosed()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * size.height * PadRiseFraction
            }
            // pad12: the theme's own background, blurred ONCE and cached (the Bottom shelf's backdrop: the quiet, speck-free scene, so nothing
            // animates), behind the header and the slab. The flat ground stays underneath as the fallback (before the first bake, a zero
            // size), and a soft scrim of the theme's own ground over it keeps the plates and the leather legible.
            .background(palette.surfaceBase)
            .mimirThemeSceneBackdrop(palette, quietSceneLight = true, shineStars = false)
            .background(palette.surfaceBase.copy(alpha = palette.surfaceBase.alpha * PadBackdropScrimAlpha))
            .padding(MimirSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        PadHeader(
            palette = palette,
            keyboardWaiting = keyboardWaiting,
            speedStep = speed.intValue,
            life = life,
            onBack = session::back,
            onSpeed = { step -> speed.intValue = session.setSpeed(step) },
            onKeyboard = onKeyboard,
            onClose = onClose,
        )
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        ) {
            PadField(
                machine = machine,
                feedback = feedback,
                tick = tick,
                invalidator = invalidator,
                alive = alive,
                legendAlpha = legendAlpha,
                life = life,
                reducedMotion = reducedMotion,
                measurer = measurer,
                palette = palette,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            PadDrum(
                mill = mill,
                feedback = feedback,
                tick = tick,
                invalidator = invalidator,
                alive = alive,
                life = life,
                measurer = measurer,
                palette = palette,
                modifier = Modifier.width(PadDrumWidth).fillMaxHeight(),
            )
        }
    }
}

/** Contact feedback, kept OFF the snapshot system: plain fields the gesture loop mutates and the draw phase reads. */
internal class TrackpadPadFeedback {
    class Contact {
        var id = -1
        var x = 0f
        var y = 0f
        var downAtMs = 0L
        var active = false
    }

    val contacts = Array(MaxContacts) { Contact() }

    /** The short fading trail behind a moving finger: the runes it passed over stay lit a moment. */
    val trail = TrackpadTrail()

    /** The machine is in a one-finger touch that could still become a hold (so the hold ring may show, once it has lasted). */
    var holdPending = false
    var holding = false
    var scrolling = false
    var gestures = 0

    /** Where the last touch was, for the tap flash. */
    var lastX = 0f
    var lastY = 0f
    var tapX = 0f
    var tapY = 0f
    var tapAtMs = Long.MIN_VALUE

    /** The drum's radius in px (half its height), set when it is measured, and the drum itself (the loop reads whether it turns). */
    var millRadiusPx = 80f
    var mill: MillSpin? = null

    /** pad8: reduced motion is on, so nothing drifts and nothing fades in or out decoratively. */
    var reducedMotion = false

    /** pad8 scroll feedback: how far the wall has drifted (px, wrapped by the draw), and when the last scroll was, for the fade. */
    var driftY = 0f
        private set
    var scrollAtMs = Long.MIN_VALUE
        private set

    /** pad8: the last time a thumb was on the drum (or it spun), for the glow band's fade after the lift. */
    var drumAtMs = Long.MIN_VALUE

    /** A scroll of [dy] px was sent (by the drum or two fingers): the wall drifts with it and its runes fade at the edges. */
    fun noteScroll(dy: Float, nowMs: Long) {
        if (reducedMotion) return
        driftY = (driftY + dy * TrackpadVisuals.DriftGain) % DriftWrapPx
        scrollAtMs = nowMs
    }

    /** 1 while scrolling, fading to 0 over [TrackpadVisuals.ScrollFadeMs] after the last scroll. */
    fun scrollStrength(nowMs: Long): Float = trackpadScrollFade(scrollAtMs, nowMs)

    fun down(id: Int, x: Float, y: Float, nowMs: Long) {
        val slot = contacts.firstOrNull { !it.active } ?: return
        slot.id = id
        slot.x = x
        slot.y = y
        slot.downAtMs = nowMs
        slot.active = true
        lastX = x
        lastY = y
        trail.add(x, y, nowMs)
    }

    fun move(id: Int, x: Float, y: Float, nowMs: Long = SystemClock.uptimeMillis()) {
        val slot = contacts.firstOrNull { it.active && it.id == id } ?: return
        slot.x = x
        slot.y = y
        lastX = x
        lastY = y
        trail.add(x, y, nowMs)
    }

    fun up(id: Int) {
        contacts.firstOrNull { it.active && it.id == id }?.active = false
    }

    /** A click was sent: the rune under the finger flashes (the only thing a tap shows). */
    fun noteTap(nowMs: Long) {
        tapX = lastX
        tapY = lastY
        tapAtMs = nowMs
    }

    /**
     * Whether something on the pad is still FADING or SPINNING at [nowMs], so the draw clock keeps ticking. A finger that is
     * merely resting does not count (its glow is static and the hold ring has its own clock): an idle pad draws no frames.
     */
    fun alive(nowMs: Long): Boolean =
        trail.alive(nowMs) || trackpadFade(tapAtMs, nowMs) > 0f || mill?.spinning == true ||
            scrollStrength(nowMs) > 0f || (mill?.touching != true && trackpadScrollFade(drumAtMs, nowMs) > 0f)

    /** A cancel or the pad leaving: every contact, hold and glow is cleared at once. */
    fun clear() {
        contacts.forEach { it.active = false }
        holdPending = false
        holding = false
        scrolling = false
        trail.clear()
        tapAtMs = Long.MIN_VALUE
        scrollAtMs = Long.MIN_VALUE
        drumAtMs = Long.MIN_VALUE
    }

    companion object {
        const val MaxContacts = 4

        /** The drift is kept inside this, so a long session never loses float precision; the wall wraps far more often than this. */
        const val DriftWrapPx = 100_000f
    }
}

/** How strongly a scroll made at [atMs] still shows at [nowMs]: 1 at once, 0 after [TrackpadVisuals.ScrollFadeMs]. */
internal fun trackpadScrollFade(atMs: Long, nowMs: Long): Float {
    if (atMs == Long.MIN_VALUE) return 0f
    return (1f - (nowMs - atMs).toFloat() / TrackpadVisuals.ScrollFadeMs).coerceIn(0f, 1f)
}

/** Requests ONE draw per frame: the tick changes inside a frame callback, and draw blocks that read it redraw. */
internal class PadInvalidator(private val tick: MutableIntState, private val scope: CoroutineScope) {
    private var pending = false

    fun request() {
        if (pending) return
        pending = true
        scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            pending = false
            tick.intValue += 1
        }
    }

    private var aliveJob: Job? = null

    /** One frame a tick while [alive] says something is lit or turning (a glow fading, a spin), then one last draw. */
    fun animateWhile(alive: () -> Boolean) {
        if (aliveJob?.isActive == true) return
        aliveJob = scope.launch {
            while (alive()) {
                androidx.compose.runtime.withFrameNanos { }
                tick.intValue += 1
            }
            tick.intValue += 1
        }
    }
}

/** Counts finished gestures for the legend's fade and flashes a tap; every other call passes straight through to the activation. */
private class PadGestureHost(
    private val session: MimirTrackpadHost,
    private val onScroll: (dy: Float) -> Unit,
    private val onTap: () -> Unit,
    private val onGesture: () -> Unit,
) : MimirTrackpadHost by session {
    override fun scrollBy(dx: Float, dy: Float) {
        session.scrollBy(dx, dy)
        onScroll(dy)
    }

    override fun click() {
        session.click()
        onTap()
        onGesture()
    }

    override fun doubleClick() {
        session.doubleClick()
        onTap()
        onGesture()
    }

    override fun scrollEnd() {
        session.scrollEnd()
        onGesture()
    }

    override fun holdEnd() {
        session.holdEnd()
        onGesture()
    }
}

@Composable
private fun PadHeader(
    palette: MimirThemePalette,
    keyboardWaiting: Boolean,
    speedStep: Int,
    life: EmberLife,
    onBack: () -> Unit,
    onSpeed: (Int) -> Unit,
    onKeyboard: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(PadHeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        PadPlate(
            palette = palette,
            label = MimirTrackpadCopy.BackPlate,
            description = MimirTrackpadCopy.BackDescription,
            lit = false,
            state = null,
            life = life,
            onTap = onBack,
            modifier = Modifier.width(PadBackWidth),
        )
        MimirChromeText(
            text = MimirTrackpadCopy.PadTitle,
            style = MimirTypeScale.Pill,
            color = palette.accentBright,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = MimirSpacing.xs),
        )
        PadSpeedGauge(palette = palette, step = speedStep, life = life, onSet = onSpeed)
        PadPlate(
            palette = palette,
            label = MimirTrackpadCopy.KeyboardPlate,
            description = MimirTrackpadCopy.KeyboardPlate,
            lit = keyboardWaiting,
            state = if (keyboardWaiting) "ready" else "idle",
            life = life,
            onTap = onKeyboard,
            modifier = Modifier.width(PadPlateWidth),
        )
        PadPlate(
            palette = palette,
            label = "\u00D7  " + MimirTrackpadCopy.ClosePlate,
            description = MimirTrackpadCopy.CloseDescription,
            lit = false,
            state = null,
            life = life,
            onTap = onClose,
            modifier = Modifier.width(PadCloseWidth),
        )
    }
}

/** A header plate: the shelf's metal brush with the ember life, lit with the accent when [lit]. Touch-only, never a focus node. */
@Composable
private fun PadPlate(
    palette: MimirThemePalette,
    label: String,
    description: String,
    lit: Boolean,
    state: String?,
    life: EmberLife,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metal = remember(palette) { platformConsoleIconBrush(palette) }
    PadPlateShell(palette = palette, description = description, lit = lit, state = state, life = life, onTap = onTap, modifier = modifier) {
        // The label is inked in the same metal as the plate's rim, as a brush-filled style, not a palette text colour.
        MimirChromeText(
            text = label,
            style = MimirTypeScale.Pill.copy(brush = metal, alpha = MimirMetalRestAlpha),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The pointer speed gauge: "Slow", one slim progress bar and "Fast". The bar is 4 dp tall with
 * rounded ends, the level in the theme's accent over the muted track the seek bar draws, filled to `level / steps` (the five steps are the stored value).
 * A tap on the bar sets that level directly, with no cycling, and a horizontal swipe anywhere along the gauge moves the level a step at a time as the finger
 * travels, right faster ([mimirGaugeSlide]). The step is stored where Settings reads it and scales the pointer's gain. The bar is slim; its touch target is
 * the gauge, the whole 48 dp header height, as tall as every other header target.
 */
@Composable
private fun PadSpeedGauge(
    palette: MimirThemePalette,
    step: Int,
    life: EmberLife,
    onSet: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = TrackpadSpeed.clamp(step)
    val pressed = remember { mutableStateOf(false) }
    val bars = remember { GaugeBars() }
    val metal = remember(palette) { platformConsoleIconBrush(palette) }
    val latestSet = rememberUpdatedState(onSet)
    val latestLevel = rememberUpdatedState(level)
    val labelStyle = MimirTypeScale.MicroStatus.copy(brush = metal, alpha = MimirMetalRestAlpha)
    val fill = padSpeedFill(level)
    val trackColor = palette.textMuted.copy(alpha = PadSpeedTrackAlpha)
    val fillColor = palette.accent
    Box(
        modifier = modifier
            .height(PadHeaderHeight)
            .testTag(MimirTrackpadSpeedGaugeTag)
            .onGloballyPositioned { bars.gaugeRootX = it.positionInRoot().x }
            .semantics {
                contentDescription = MimirTrackpadCopy.SpeedDescription
                stateDescription = "$level of ${TrackpadSpeed.Steps}"
                // A slider for a screen reader: setProgress across the five steps.
                progressBarRangeInfo = ProgressBarRangeInfo(level.toFloat(), 1f..TrackpadSpeed.Steps.toFloat(), TrackpadSpeed.Steps - 2)
                setProgress { value ->
                    latestSet.value(TrackpadSpeed.clamp(Math.round(value)))
                    true
                }
            }
            // The swipe and the tap, in one gesture over the whole gauge, 48 dp tall: it consumes a swipe so nothing else sees it.
            .mimirGaugeSlide(bars, TrackpadSpeed.Steps, { latestLevel.value }, { latestSet.value(it) }, pressed, onPress = life::arm),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = (PadHeaderHeight - PadPlateHeight) / 2)
                .mimirMetalPlate(palette, PadPlateRadius, lit = false, life = life, pressed = pressed, raised = true),
        )
        Row(
            // pad13: pressed, the housing sinks and its content sits a dp lower with it.
            modifier = Modifier.mimirRaisedPressNudge(pressed).fillMaxHeight().padding(horizontal = PadGaugePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PadGaugeGap),
        ) {
            MimirChromeText(text = MimirTrackpadCopy.SlowLabel, style = labelStyle, maxLines = 1)
            Box(
                modifier = Modifier
                    .width(PadSpeedBarWidth)
                    .height(PadSpeedBarHeight)
                    .testTag(MimirTrackpadSpeedBarTag)
                    .onGloballyPositioned {
                        bars.barsRootX = it.positionInRoot().x
                        bars.width = it.size.width.toFloat()
                    }
                    .semantics { mimirTrackpadSpeedFill = fill }
                    .drawBehind {
                        val corner = CornerRadius(size.height / 2f)
                        drawRoundRect(color = trackColor, cornerRadius = corner)
                        val filled = size.width * fill
                        if (filled > 0f) drawRoundRect(color = fillColor, size = Size(filled, size.height), cornerRadius = corner)
                    },
            )
            MimirChromeText(text = MimirTrackpadCopy.FastLabel, style = labelStyle, maxLines = 1)
        }
    }
}

/** How much of the bar the level fills: `level / steps`, so the five steps are five even stops and the top one is full. */
internal fun padSpeedFill(level: Int): Float = TrackpadSpeed.clamp(level).toFloat() / TrackpadSpeed.Steps

/** The fill the speed bar draws (0..1), for tests and tooling; it is not an accessibility property. */
internal val MimirTrackpadSpeedFillSemantics = SemanticsPropertyKey<Float>("MimirTrackpadSpeedFill")
private var SemanticsPropertyReceiver.mimirTrackpadSpeedFill by MimirTrackpadSpeedFillSemantics

/** A plate's whole target is the 48 dp header height; the visible plate is [PadPlateHeight] inside it, with the ember glow reaching past. */
@Composable
private fun PadPlateShell(
    palette: MimirThemePalette,
    description: String,
    lit: Boolean,
    state: String?,
    life: EmberLife,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pressed = remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(PadHeaderHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (state != null) stateDescription = state
                onClick(label = description) {
                    onTap()
                    true
                }
            }
            .mimirPlateTap(onTap, pressed, onPress = life::arm),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(PadPlateHeight)
                .mimirMetalPlate(palette, PadPlateRadius, lit = lit, life = life, pressed = pressed, raised = true),
            contentAlignment = Alignment.Center,
        ) {
            // pad13: a pressed plate sinks, and its label sits a dp lower with it.
            Box(Modifier.mimirRaisedPressNudge(pressed), contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
private fun PadField(
    machine: TrackpadGestureMachine,
    feedback: TrackpadPadFeedback,
    tick: MutableIntState,
    invalidator: PadInvalidator,
    alive: () -> Boolean,
    legendAlpha: Animatable<Float, *>,
    life: EmberLife,
    reducedMotion: Boolean,
    measurer: TextMeasurer,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    // pad12: the tooled wall's tile, held across recompositions (the draw cache below is rebuilt whenever this recomposes); a new size or theme bakes a new one.
    val tiles = remember { TooledRuneTileCache() }
    Box(
        modifier = modifier
            .testTag(MimirTrackpadPadFieldTag)
            // pad8: the bound slab. The carved surface is behind the runes, the iron binding over them.
            .boundSlabSurface(palette, PadFieldRadius)
            .drawWithCache {
                // The rune wall and its glyph layouts are built once per size; the tooled tile is painted once per size and theme (never per frame);
                // each frame only draws the tile and the contact feedback.
                val wall = RuneWallLayout(size.width, size.height, TrackpadVisuals.CellDp.dp.toPx())
                val runeStyle = MimirTypeScale.RunicEyebrow.copy(fontSize = RuneSizeSp.sp)
                val layouts = TrackpadVisuals.Runes.map { measurer.measure(AnnotatedString(it), runeStyle) }
                // The hold ring's runes keep their own size: a miniature wall does not shrink the hold circle.
                val holdStyle = MimirTypeScale.RunicEyebrow.copy(fontSize = TrackpadVisuals.HoldRuneSp.sp)
                val holdLayouts = TrackpadVisuals.Runes.map { measurer.measure(AnnotatedString(it), holdStyle) }
                val tileKey = TooledRuneKey(size.width.toInt(), size.height.toInt(), density, fontScale, palette)
                val tile = tiles.get(tileKey) { bakeTooledRuneTile(tileKey, this, layoutDirection, wall, layouts) }
                val tilePeriod = tile.image.height.toFloat()
                val tint = tooledEmberFilter(palette.emberHot)
                val glow = RuneGlowSources()
                val reach = TrackpadVisuals.GlowReachDp.dp.toPx()
                val ring = PadRingDp.dp.toPx()
                val ringStroke = PadRingStrokeDp.dp.toPx()
                onDrawBehind {
                    tick.intValue // the one subscription: a frame tick redraws, it never recomposes
                    val now = SystemClock.uptimeMillis()
                    if (feedback.holding) {
                        drawRect(palette.accent.copy(alpha = 0.10f), size = Size(size.width, size.height))
                    }
                    // A whole-px drift, so the glowing runes sit exactly on the tooled ones they light.
                    val drift = Math.round(feedback.driftY).toFloat()
                    val scroll = feedback.scrollStrength(now)
                    drawTooledWall(
                        tile = tile,
                        drift = drift,
                        scroll = scroll,
                        shimmerPhase = life.shimmer.floatValue, // read here, in draw: a shimmer step redraws, never recomposes
                        // The shimmer is the life's: full amplitude in the window, eased to nothing at rest (a plain, steady tile), none when reduced.
                        shimmerAmplitude = if (reducedMotion) 0f else TrackpadVisuals.ShimmerAmplitude * life.level.floatValue,
                        shimmerTintMix = if (reducedMotion) 0f else TrackpadVisuals.ShimmerTint * life.level.floatValue,
                        tintFilter = tint,
                    )
                    glow.gather(feedback, now, reach)
                    drawRuneGlow(wall, layouts, glow, palette.accent, drift, tilePeriod, scroll)
                    for (contact in feedback.contacts) {
                        if (!contact.active) continue
                        val at = Offset(contact.x, contact.y)
                        // The hold ring is for a HOLD only: nothing for a tap or a scroll, nothing until the press has lasted
                        // past the visual delay, then it fills to the actual long-press moment; full (and tinted) while held.
                        val progress = trackpadHoldProgress(now - contact.downAtMs, feedback.holdPending, feedback.holding)
                        if (feedback.holding) {
                            drawCircle(palette.accent.copy(alpha = 0.30f), radius = ring, center = at)
                        }
                        if (progress > 0f) drawHoldCircle(at, ring, progress, holdLayouts, palette.accentBright, ringStroke)
                    }
                }
            }
            .boundSlabIron(palette, PadFieldRadius)
            .pointerInput(machine) {
                coroutineScope {
                    val scope = this
                    // The long-press clock runs only while a one-finger touch could still become a hold.
                    var ticker: Job? = null
                    awaitPointerEventScope {
                        val down = HashSet<Int>()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                for (change in event.changes) {
                                    val id = change.id.value.toInt()
                                    val now = change.uptimeMillis
                                    when {
                                        change.pressed && id !in down -> {
                                            down += id
                                            // Any finger on the field first stops the wheel's inertia and drops its pending tail, so a fling's
                                            // swipe can never cancel this gesture's continuation or share its scroll accumulator.
                                            feedback.mill?.yieldToField()
                                            life.arm() // a touch on the pad re-opens the ember window
                                            machine.down(id, change.position.x, change.position.y, now)
                                            feedback.down(id, change.position.x, change.position.y, SystemClock.uptimeMillis())
                                            if (ticker?.isActive != true) {
                                                ticker = scope.launch {
                                                    while (machine.longPressPending) {
                                                        delay(PadTickMs)
                                                        machine.tick(SystemClock.uptimeMillis())
                                                        feedback.holding = machine.isHolding
                                                        feedback.holdPending = machine.longPressPending
                                                        invalidator.request()
                                                    }
                                                    feedback.holding = machine.isHolding
                                                    feedback.holdPending = machine.longPressPending
                                                    invalidator.request()
                                                }
                                            }
                                        }
                                        change.pressed -> {
                                            machine.move(id, change.position.x, change.position.y, now)
                                            feedback.move(id, change.position.x, change.position.y)
                                        }
                                        id in down -> {
                                            down -= id
                                            // A cancelled touch arrives as a synthetic release that is already consumed; a real
                                            // lift never is. A cancel injects nothing and releases any hold.
                                            if (change.isConsumed) {
                                                down.clear()
                                                machine.cancel()
                                                feedback.clear()
                                            } else {
                                                machine.up(id, now)
                                                feedback.up(id)
                                            }
                                        }
                                    }
                                    change.consume()
                                }
                                feedback.holding = machine.isHolding
                                feedback.holdPending = machine.longPressPending
                                feedback.scrolling = machine.isScrolling
                                invalidator.request()
                                invalidator.animateWhile(alive)
                            }
                        } finally {
                            // The gesture coroutine ended without a real lift: nothing is injected, a hold is released.
                            machine.cancel()
                            feedback.clear()
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        MimirChromeText(
            text = MimirTrackpadCopy.Legend,
            style = MimirTypeScale.Pill,
            color = palette.textMuted,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(bottom = MimirSpacing.sm, start = MimirSpacing.sm, end = MimirSpacing.sm)
                .graphicsLayer { alpha = legendAlpha.value },
        )
    }
}

internal const val MimirTrackpadPadFieldTag = "mimir-trackpad-pad-field"
internal const val MimirTrackpadSpeedGaugeTag = "mimir-trackpad-speed-gauge"

/** The gauge's one slim progress bar. */
internal const val MimirTrackpadSpeedBarTag = "mimir-trackpad-speed-bar"

/** The number of finished gestures after which the legend has said its piece and fades. */
internal const val LegendGestures = 3

/**
 * The pad's ramp: it rises on this and leaves on exactly this, on [MimirMotion.Easing] (observed: the window used to
 * outlive the leave by a wall-clock 60 ms, so the pad's close and its open ran two clocks). The window removes itself from the pad's
 * own [MimirTrackpadPad] `onClosed`, which this clock ends, not from a timer of its own.
 */
const val MimirTrackpadPadRampMs: Int = MimirMotion.OverlayMs

private const val PadRiseFraction = 0.06f

/** pad12: how much of the theme's ground tints the blurred backdrop (the Bottom shelf's tint is 0.35; the pad carries leather and light runes on it). */
private const val PadBackdropScrimAlpha = 0.55f
private const val PadTickMs = 40L
private const val PadRingDp = 26f
private const val PadRingStrokeDp = 2f
private const val RuneSizeSp = TrackpadVisuals.RuneSp
/** The speed bar's track is the muted line the music seek bar's track is drawn in (the text-muted ink at this alpha), so the pad adds no colour of its own. */
private const val PadSpeedTrackAlpha = 0.28f
/** The bar is as thick as the seek bar's track (4 dp), rounded at both ends, and shorter than the five columns it replaces (5 x 17 dp). */
private val PadSpeedBarHeight = 4.dp
private val PadSpeedBarWidth = 56.dp
private val PadGaugePadding = 8.dp
private val PadGaugeGap = 6.dp

/** pad8: the header is one 48 dp target tall (every plate and the gauge), with the visible plate [PadPlateHeight] inside it. */
internal val PadHeaderHeight = 48.dp
private val PadPlateHeight = 34.dp
private val PadPlateWidth = 88.dp
private val PadBackWidth = 76.dp
private val PadCloseWidth = 80.dp
/** The signature plate is in Mimir's card corner. */
private val PadPlateRadius = MimirSpacing.radiusCard
internal val PadFieldRadius = 16.dp
internal val PadDrumWidth = DrumGeometry.WidthDp.dp
