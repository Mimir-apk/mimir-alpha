package com.gruning.mimir.rewrite.core.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The rune drum's physics: a thumb turns it one for one (the surface moves as far as the finger), every turn scrolls through the
 * activation's existing scroll path, and a flick keeps it spinning, scrolling as it turns, until friction stops it. Touching it
 * stops the spin.
 */
internal object MillPhysics {
    /** Friction: the rim's speed decays as exp(-k t). About a quarter-second half-life. */
    const val FrictionPerMs = 0.003f

    /** Below this the wheel is at rest. */
    const val StopSpeedPxPerMs = 0.03f

    /** A lift slower than this is not a flick: the drag's scroll is sent as it always was. */
    const val MinFlingSpeedPxPerMs = 0.4f

    /** The window the lift's speed is measured over. */
    const val VelocityWindowMs = 100L

    /**
     * While spinning, the scroll is sent in bursts at least this far apart: a swipe lasts at most 450 ms, and a new swipe
     * dispatched while one is still in flight cancels it, so bursts never overlap.
     */
    const val BurstMs = 450L

    /** A burst smaller than this (the injector sends nothing under its minimum swipe) is held back and added to the next. */
    const val MinBurstPx = 10f

    /** A fling never runs longer than this, whatever its speed (the wall-clock cap), then it settles. */
    const val FlingMaxMs = 2_500L

    /** The fastest a fling may spin: a lift faster than this is capped to it, so one violent flick cannot run away. */
    const val MaxFlingSpeedPxPerMs = 5f

    fun decay(velocity: Float, dtMs: Float, friction: Float = FrictionPerMs): Float = velocity * exp(-friction * dtMs)

    /** The rim's travel over [dtMs] at [velocity] with the same friction, in closed form. */
    fun distance(velocity: Float, dtMs: Float, friction: Float = FrictionPerMs): Float = velocity / friction * (1f - exp(-friction * dtMs))
}

/**
 * The wheel's whole behaviour, free of Compose: its angle, its spin and what it sends the activation. [host] is the raw
 * activation (never the pad's feedback wrapper), [radiusPx] the rim radius the angle is measured on.
 */
internal class MillSpin(
    private val host: MimirTrackpadHost,
    private val radiusPx: () -> Float,
    private val friction: Float = MillPhysics.FrictionPerMs,
    /** pad8: told of every scroll this drum itself sends, so the rune wall can drift with it. Never part of the physics. */
    private val onTurn: (dy: Float) -> Unit = {},
) {
    /** Radians; the rim moves [radiusPx] times this far. */
    var angle = 0f
        private set

    /** The rim's speed in px per ms while spinning; the sign follows the finger (positive is down). */
    var velocity = 0f
        private set
    var touching = false
        private set

    /** True from a flick until the spin has stopped AND its last scroll has been sent or dropped (a settling spin still counts). */
    var spinning = false
        private set

    /** The spin is over and its last scroll is waiting for the previous swipe's spacing to pass (nothing turns meanwhile). */
    private var draining = false

    private var lastY = 0f
    private var sent = 0f

    /** Where the thumb is on the drum, in px from its top (the ember glow band sits there); kept after the lift for the glow's fade. */
    var touchY = 0f
        private set

    /** When the last swipe was dispatched (or the fling began): no swipe goes out within [MillPhysics.BurstMs] of it. */
    private var lastDispatchAt = Long.MIN_VALUE
    private var flingStartAt = 0L
    private val sampleT = LongArray(SampleCount)
    private val sampleY = FloatArray(SampleCount)
    private var samples = 0

    private fun radius(): Float = radiusPx().coerceAtLeast(1f)

    /** The wheel turns with [dy] of content movement it did not cause itself (the field's two-finger scroll). */
    fun turnVisual(dy: Float) {
        angle += dy / radius()
    }

    private fun turn(dy: Float) {
        angle += dy / radius()
        host.scrollBy(0f, dy)
        sent += dy
        onTurn(dy)
    }

    private fun sample(y: Float, nowMs: Long) {
        val slot = samples % SampleCount
        sampleT[slot] = nowMs
        sampleY[slot] = y
        samples += 1
    }

    private fun spacingPassed(nowMs: Long): Boolean = lastDispatchAt == Long.MIN_VALUE || nowMs - lastDispatchAt >= MillPhysics.BurstMs

    /** Sends the accumulated scroll as one swipe, and only when the previous swipe's spacing has passed: never two within it. */
    private fun dispatch(nowMs: Long) {
        host.scrollEnd()
        lastDispatchAt = nowMs
        sent = 0f
    }

    /** Drops what a spin still owes (its unsent scroll) and ends it, without dispatching anything. */
    private fun dropTail() {
        val had = spinning || draining || sent != 0f
        spinning = false
        draining = false
        velocity = 0f
        if (had) host.scrollCancel()
        sent = 0f
    }

    /**
     * ANY touch on the pad's field stops the inertia and drops its pending tail BEFORE the field's gesture is admitted: a fling's
     * swipe must never cancel a held gesture's continuation, and the field's two-finger scroll must not share this wheel's
     * accumulator. A finger still turning the wheel is let go of too.
     */
    fun yieldToField() {
        if (touching) {
            touching = false
            host.scrollCancel()
            sent = 0f
        }
        if (spinning) dropTail()
    }

    /** The finger landed: a spin in progress stops and its tail is dropped, never sent; a new drag begins. */
    fun touchDown(y: Float, nowMs: Long) {
        if (spinning) dropTail()
        touching = true
        velocity = 0f
        samples = 0
        lastY = y
        touchY = y
        sample(y, nowMs)
    }

    fun drag(y: Float, nowMs: Long) {
        if (!touching) return
        val dy = y - lastY
        lastY = y
        touchY = y
        sample(y, nowMs)
        if (dy != 0f) turn(dy)
    }

    /** The finger's speed at the lift, over the last [MillPhysics.VelocityWindowMs]. */
    private fun liftVelocity(nowMs: Long): Float {
        if (samples < 2) return 0f
        val count = minOf(samples, SampleCount)
        var newest = -1
        var newestT = Long.MIN_VALUE
        for (i in 0 until count) if (sampleT[i] > newestT) { newestT = sampleT[i]; newest = i }
        // A finger that stopped before it lifted is not a flick, however fast it moved before.
        if (nowMs - newestT > MillPhysics.VelocityWindowMs) return 0f
        var oldestY = sampleY[newest]
        var oldestT = newestT
        for (i in 0 until count) {
            if (newestT - sampleT[i] <= MillPhysics.VelocityWindowMs && sampleT[i] < oldestT) {
                oldestT = sampleT[i]
                oldestY = sampleY[i]
            }
        }
        val dt = (newestT - oldestT).coerceAtLeast(1L)
        return (sampleY[newest] - oldestY) / dt
    }

    /**
     * The finger lifted ([cancelled] false) or the touch was taken away. A cancel sends nothing. A lift fast enough starts a
     * spin; a slow one sends the drag's scroll once, as the rail always did, but never inside the spacing of the previous swipe
     * (it waits, settling, until that has passed). Returns true when the caller must keep stepping.
     */
    fun release(nowMs: Long, cancelled: Boolean): Boolean {
        if (!touching) return false
        touching = false
        if (cancelled) {
            host.scrollCancel()
            sent = 0f
            return false
        }
        val v = liftVelocity(nowMs).coerceIn(-MillPhysics.MaxFlingSpeedPxPerMs, MillPhysics.MaxFlingSpeedPxPerMs)
        if (abs(v) >= MillPhysics.MinFlingSpeedPxPerMs) {
            velocity = v
            spinning = true
            draining = false
            flingStartAt = nowMs
            // The lift is the baseline: the first burst is a full spacing later (the drag itself dispatched nothing).
            lastDispatchAt = nowMs
            return true
        }
        if (spacingPassed(nowMs)) {
            dispatch(nowMs)
            return false
        }
        spinning = true
        draining = true
        return true
    }

    /**
     * One step of the spin, [dtMs] long, at [nowMs]: turns the wheel and the scroll by the distance friction allows, sends a
     * burst when one is due, and settles at rest or at the wall-clock cap. False once everything is over. A dead session stops
     * it at once and sends nothing. A settling spin waits for the previous swipe's spacing before its last scroll goes.
     */
    fun step(dtMs: Float, nowMs: Long): Boolean {
        if (!spinning) return false
        if (!host.sessionLive) {
            dropTail()
            return false
        }
        if (draining) return drain(nowMs)
        turn(MillPhysics.distance(velocity, dtMs, friction))
        velocity = MillPhysics.decay(velocity, dtMs, friction)
        if (abs(velocity) < MillPhysics.StopSpeedPxPerMs || nowMs - flingStartAt >= MillPhysics.FlingMaxMs) {
            velocity = 0f
            draining = true
            return drain(nowMs)
        }
        if (spacingPassed(nowMs) && abs(sent) >= MillPhysics.MinBurstPx) dispatch(nowMs)
        return true
    }

    /** The last scroll: sent once the spacing has passed (or dropped when it is too small to be a swipe). */
    private fun drain(nowMs: Long): Boolean {
        if (abs(sent) < MillPhysics.MinBurstPx) {
            spinning = false
            draining = false
            host.scrollCancel()
            sent = 0f
            return false
        }
        if (!spacingPassed(nowMs)) return true
        dispatch(nowMs)
        spinning = false
        draining = false
        return false
    }

    /** The wheel left the screen (Close, a lock): nothing more is sent, whatever was turning. */
    fun dispose() {
        if (spinning || touching || sent != 0f) host.scrollCancel()
        spinning = false
        draining = false
        touching = false
        velocity = 0f
        sent = 0f
    }

    private companion object {
        const val SampleCount = 6
    }
}

internal const val MimirTrackpadPadDrumTag = "mimir-trackpad-pad-drum"

/**
 * The drum's geometry: a ribbed cylinder seen edge-on, its axis across the
 * strip. A rib sits at angle `theta` round the cylinder and shows at `centre + radius * sin(theta)`, so at the middle a rib moves
 * exactly as far as the thumb (the turn is `dy / radius`, the same radius [MillSpin] measures) and toward the top and bottom ribs
 * crowd together and fade, as a cylinder's do. Pure functions of the turn, free of Compose, so a test can hold them.
 */
internal object DrumGeometry {
    /** The arc between two ribs, at the middle of the drum. */
    const val RibPitchDp = 12f

    /** The drum's strip width. */
    const val WidthDp = 56f

    /** A rib's ridge, and the groove below it. */
    const val RibStrokeDp = 2f
    const val RibInsetDp = 7f

    /** The ember glow band's half height, and the engraved rune's size. */
    const val GlowHalfDp = 44f
    const val RuneSp = 24f

    /** The share of the drum's height, at each end, that falls into shadow. */
    const val EndShadowFraction = 0.30f
}

private val HalfPi = (PI / 2.0).toFloat()

internal fun drumRibAngle(rib: Int, pitch: Float, turn: Float): Float = rib * pitch + turn

/** The first and last rib that show (angle within a quarter turn either side of the middle) for a [turn] of the drum. */
internal fun drumFirstRib(pitch: Float, turn: Float): Int = kotlin.math.ceil((-HalfPi - turn) / pitch).toInt()

internal fun drumLastRib(pitch: Float, turn: Float): Int = kotlin.math.floor((HalfPi - turn) / pitch).toInt()

/** Where a rib at [theta] shows: the middle of the drum at 0, the edges at a quarter turn. */
internal fun drumRibY(centerY: Float, radius: Float, theta: Float): Float = centerY + radius * sin(theta)

/** How full-on a rib at [theta] faces the player: 1 at the middle, 0 at the rim. It scales a rib's width and brightness. */
internal fun drumRibDepth(theta: Float): Float = cos(theta).coerceAtLeast(0f)

/** The strength of the ember glow band: full while a thumb is on the drum, 0.7 while it coasts, then the [fade] of the lift. */
internal fun drumGlowStrength(touching: Boolean, spinning: Boolean, fade: Float): Float =
    if (touching) 1f else if (spinning) DrumCoastGlow else fade * DrumCoastGlow

private const val DrumCoastGlow = 0.7f

/**
 * The rune drum's strip (replaces the mill wheel): a ribbed cylinder edge-on. The ribs move with the thumb (one for one at the
 * middle), compress and darken toward the ends, an ember glow band sits where the thumb is, and a rune etched in metal at the centre
 * catches a little ember light while the drum turns. Drawn in the draw phase from the drum's own state and the frame tick; it recomposes nothing.
 */
@Composable
internal fun PadDrum(
    mill: MillSpin,
    feedback: TrackpadPadFeedback,
    tick: MutableIntState,
    invalidator: PadInvalidator,
    alive: () -> Boolean,
    life: EmberLife,
    measurer: TextMeasurer,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(mill) { onDispose { mill.dispose() } }
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .testTag(MimirTrackpadPadDrumTag)
            .semantics { contentDescription = MimirTrackpadCopy.DrumDescription }
            .drawWithCache {
                val radius = (size.height / 2f).coerceAtLeast(8f)
                feedback.millRadiusPx = radius
                val clip = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(PadFieldRadius.toPx())))
                }
                val body = Brush.verticalGradient(
                    colorStops = arrayOf(0f to palette.surfaceBase, 0.5f to palette.surfaceHigh, 1f to palette.surfaceBase),
                )
                val shade = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = EndShadowAlpha),
                        DrumGeometry.EndShadowFraction to Color.Transparent,
                        1f - DrumGeometry.EndShadowFraction to Color.Transparent,
                        1f to Color.Black.copy(alpha = EndShadowAlpha),
                    ),
                )
                val metal = platformConsoleIconBrush(palette)
                val frame = Brush.linearGradient(
                    colorStops = arrayOf(0f to palette.iconGradMid, 0.5f to palette.iconGradDeep, 1f to palette.iconGradMid),
                )
                val runeStyle = MimirTypeScale.RunicEyebrow.copy(fontSize = DrumGeometry.RuneSp.sp)
                val rune = measurer.measure(AnnotatedString(DrumRune), runeStyle)
                val pitch = DrumGeometry.RibPitchDp.dp.toPx() / radius
                val ribStroke = DrumGeometry.RibStrokeDp.dp.toPx()
                val inset = DrumGeometry.RibInsetDp.dp.toPx()
                val glowHalf = DrumGeometry.GlowHalfDp.dp.toPx()
                val frameStroke = Stroke(width = 1.5.dp.toPx())
                val corner = CornerRadius(PadFieldRadius.toPx())
                onDrawBehind {
                    tick.intValue // the one subscription: a frame tick redraws, it never recomposes
                    val now = SystemClock.uptimeMillis()
                    val turning = mill.touching || mill.spinning
                    val fade = trackpadScrollFade(feedback.drumAtMs, now)
                    val lit = if (turning) 1f else fade
                    val cy = size.height / 2f
                    clipPath(clip) {
                        drawRect(brush = body)
                        // The ember glow band, where the thumb is.
                        val glow = drumGlowStrength(mill.touching, mill.spinning, fade)
                        if (glow > 0f) {
                            val at = mill.touchY.coerceIn(0f, size.height)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        palette.tileGlowStrong.copy(alpha = (palette.tileGlowStrong.alpha * glow).coerceIn(0f, 1f)),
                                        Color.Transparent,
                                    ),
                                    startY = at - glowHalf,
                                    endY = at + glowHalf,
                                ),
                                topLeft = Offset(0f, at - glowHalf),
                                size = Size(size.width, glowHalf * 2f),
                            )
                        }
                        // The ribs: they move exactly as far as the thumb at the middle, and crowd and fade toward the ends.
                        var rib = drumFirstRib(pitch, mill.angle)
                        val last = drumLastRib(pitch, mill.angle)
                        val ridge = lerp(palette.iconGradLight, palette.accentBright, lit * DrumRibTint)
                        while (rib <= last) {
                            val theta = drumRibAngle(rib, pitch, mill.angle)
                            val depth = drumRibDepth(theta)
                            val y = drumRibY(cy, radius, theta)
                            val width = ribStroke * (0.3f + 0.7f * depth)
                            drawLine(
                                color = Color.Black.copy(alpha = DrumGrooveAlpha * depth),
                                start = Offset(inset, y + width),
                                end = Offset(size.width - inset, y + width),
                                strokeWidth = width,
                            )
                            drawLine(
                                color = ridge.copy(alpha = ((DrumRibRest + DrumRibLit * lit) * depth * depth).coerceIn(0f, 1f)),
                                start = Offset(inset, y),
                                end = Offset(size.width - inset, y),
                                strokeWidth = width,
                                cap = StrokeCap.Round,
                            )
                            rib += 1
                        }
                        drawRect(brush = shade)
                        // The rune is ETCHED IN METAL: the face is the header plates' own metal
                        // brush, with a dark groove along its upper edge and a light lip along its lower one. At rest it is metal; while the drum
                        // turns it catches a little ember light over the face.
                        val topLeft = Offset((size.width - rune.size.width) / 2f, cy - rune.size.height / 2f)
                        val lip = 1.dp.toPx()
                        drawText(rune, color = palette.iconGradLight.copy(alpha = DrumEtchLip), topLeft = topLeft + Offset(0f, lip))
                        drawText(rune, color = Color.Black.copy(alpha = DrumEngraveShadow), topLeft = topLeft - Offset(0f, lip * 0.8f))
                        drawText(rune, brush = metal, topLeft = topLeft)
                        val ember = drumRuneEmber(lit)
                        if (ember > 0f) drawText(rune, color = palette.accentBright.copy(alpha = ember), topLeft = topLeft)
                    }
                    drawRoundRect(brush = frame, cornerRadius = corner, style = frameStroke)
                }
            }
            .pointerInput(mill) {
                coroutineScope {
                    val scope = this
                    var spin: Job? = null
                    awaitPointerEventScope {
                        var tracking = -1
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                for (change in event.changes) {
                                    val id = change.id.value.toInt()
                                    when {
                                        tracking < 0 && change.pressed -> {
                                            spin?.cancel()
                                            spin = null
                                            tracking = id
                                            life.arm() // a touch on the drum re-opens the ember window
                                            feedback.drumAtMs = SystemClock.uptimeMillis()
                                            // A finger on the field owns the shared scroll accumulator: the drum stays still under one.
                                            if (feedback.contacts.any { it.active }) mill.yieldToField() else mill.touchDown(change.position.y, change.uptimeMillis)
                                        }
                                        id != tracking -> Unit
                                        change.pressed -> {
                                            feedback.drumAtMs = SystemClock.uptimeMillis()
                                            mill.drag(change.position.y, change.uptimeMillis)
                                        }
                                        else -> {
                                            tracking = -1
                                            feedback.drumAtMs = SystemClock.uptimeMillis()
                                            val flung = mill.release(change.uptimeMillis, cancelled = change.isConsumed)
                                            if (flung) {
                                                spin = scope.launch {
                                                    var last = androidx.compose.runtime.withFrameNanos { it }
                                                    while (mill.spinning) {
                                                        val now = androidx.compose.runtime.withFrameNanos { it }
                                                        val dt = ((now - last) / 1_000_000f).coerceIn(1f, 50f)
                                                        last = now
                                                        feedback.drumAtMs = SystemClock.uptimeMillis()
                                                        if (!mill.step(dt, now / 1_000_000L)) break
                                                        tick.intValue += 1
                                                    }
                                                    tick.intValue += 1
                                                }
                                            }
                                        }
                                    }
                                    change.consume()
                                }
                                invalidator.request()
                                // The glow band and the wall's drift fade out after the lift: keep drawing until they have.
                                invalidator.animateWhile(alive)
                            }
                        } finally {
                            if (tracking >= 0) mill.release(SystemClock.uptimeMillis(), cancelled = true)
                        }
                    }
                }
            },
    )
}

private const val DrumRune = "ᛗ"
private const val EndShadowAlpha = 0.55f
private const val DrumGrooveAlpha = 0.45f
private const val DrumRibRest = 0.30f
private const val DrumRibLit = 0.30f
private const val DrumRibTint = 0.5f
private const val DrumEngraveShadow = 0.6f
private const val DrumEtchLip = 0.6f

/** How much ember light the etched rune catches while the drum turns: none at rest (it is metal), a little at full turn. */
internal fun drumRuneEmber(lit: Float): Float = lit.coerceIn(0f, 1f) * DrumRuneEmberMax

private const val DrumRuneEmberMax = 0.3f
