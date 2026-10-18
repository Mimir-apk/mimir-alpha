package com.gruning.mimir.rewrite.core.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.sin

/**
 * The ember life of the trackpad's plates, gauge, slab and the Bottom header's Trackpad plate: a warm rim light along the top, a faint ember glow inside and around, a very slow breath and a
 * brighter glow when lit or pressed.
 *
 * **Colour rule.** The design's amber is the THEME's ember: [MimirThemePalette.accent], [MimirThemePalette.accentBright],
 * [MimirThemePalette.tileGlow], [MimirThemePalette.tileGlowStrong], [MimirThemePalette.emberSoft] and the console-icon metal
 * (`iconGrad*`). There is no colour literal here, so every effect follows every theme.
 *
 * **Motion rule (no unbounded decorative work at rest).** The life is BOUNDED. It animates for a short
 * window ([EmberLifeWindowMs]) after an ARRIVAL (the surface appearing, the pad opening, a parked pane coming back, the screen coming
 * on) or an INTERACTION ([EmberLife.arm]: a touch on the pad or a plate), then eases over [EmberEaseMs] to a STATIC resting look:
 * the same rim light and glow at the mid level, not dark. After that nothing requests a frame: an idle Bottom draws nothing for
 * the ember. Any new interaction re-arms the window. The loop never runs while the pane is parked (D24, [LocalMimirMotionLive]),
 * while the screen is off or the host is stopped, or under reduced motion; and it takes its liveness as an EXPLICIT argument, so
 * reuse outside a motion-live scope cannot create an always-on clock by default.
 *
 * Everything is drawn in the draw phase from [FloatState]s that only draw blocks read, so a breath never recomposes anything.
 */
const val EmberBreathPeriodMs = 5_000L

/** The wall's resting shimmer is slower still, and its amplitude lower: it never competes with the glow under a finger. */
internal const val EmberShimmerPeriodMs = 9_000L

/** The slow clocks need not run at the display rate: this is about fifteen steps a second. */
internal const val EmberStepMs = 66L

/** How long the life animates after an arrival or an interaction, before it eases to rest. */
const val EmberLifeWindowMs = 6_000L

/** How long the ease from the animated look to the static resting look takes at the end of the window. */
const val EmberEaseMs = 1_200L

/** Where a clock stands when it is not running (reduced motion, parked): the middle of its wave, so the static look is the calm one. */
internal const val EmberStaticPhase = 0f

internal fun emberPhaseAt(nowMs: Long, periodMs: Long): Float = (nowMs % periodMs).toFloat() / periodMs

/** 0 to 1, a smooth wave of [phase]; [EmberStaticPhase] gives 0.5. */
fun emberWave(phase: Float): Float = 0.5f + 0.5f * sin(2f * PI.toFloat() * phase)

/** A slow ember clock runs only when the player allows motion and the pane is the one being seen. */
internal fun emberClockRuns(reducedMotion: Boolean, live: Boolean): Boolean = !reducedMotion && live

/** The wave with the life's [level] applied: the whole wave at level 1, the middle (the static resting look) at level 0. */
fun emberEasedWave(wave: Float, level: Float): Float = 0.5f + (wave - 0.5f) * level.coerceIn(0f, 1f)

/** The life's level [elapsedMs] after the window began: 1 through the window, easing to 0 over [EmberEaseMs], 0 after. */
internal fun emberWindowLevel(elapsedMs: Long): Float = when {
    elapsedMs < EmberLifeWindowMs -> 1f
    elapsedMs >= EmberLifeWindowMs + EmberEaseMs -> 0f
    else -> 1f - (elapsedMs - EmberLifeWindowMs).toFloat() / EmberEaseMs
}

/** The host is running when its lifecycle is at least STARTED and the screen is interactive. */
internal fun emberHostRuns(lifecycleStarted: Boolean, screenInteractive: Boolean): Boolean = lifecycleStarted && screenInteractive

/**
 * One surface's bounded ember life: the two phases (the plates' breath and the wall's shimmer), the [level] that eases them out, and
 * [arm] to re-open the window. Draw blocks read [breath], [shimmer] and [level]; nothing here is read in composition.
 */
class EmberLife internal constructor() {
    internal val breathState = mutableFloatStateOf(EmberStaticPhase)
    internal val shimmerState = mutableFloatStateOf(EmberStaticPhase)
    internal val levelState = mutableFloatStateOf(0f)
    internal val trigger = mutableIntStateOf(0)

    /** The window is open: a loop is awaiting frames. Plain fields, written on the main thread only. */
    internal var running = false
    internal var rearm = false

    val breath: FloatState get() = breathState
    val shimmer: FloatState get() = shimmerState

    /** 1 while the window is open, easing to 0 at its end; 0 means the static resting look. */
    val level: FloatState get() = levelState

    /** A touch or an arrival: the window (re)opens now. A loop already running just restarts its window; an idle one is woken. */
    fun arm() {
        rearm = true
        if (!running) trigger.intValue += 1
    }

    /** The plates' wave in 0 to 1, eased to the middle at rest. Read in a draw block. */
    fun wave(): Float = emberEasedWave(emberWave(breathState.floatValue), levelState.floatValue)

    /** Back to the static resting look, with no loop running. */
    internal fun settle() {
        running = false
        levelState.floatValue = 0f
    }

    /**
     * One window: the life animates from [awaitFrame]'s frame times until the window and its ease are over, then returns, having asked for no
     * more frames. [arm] while it runs restarts the window. Cancelling it (parked, screen off) leaves the static look.
     */
    internal suspend fun runWindow(awaitFrame: suspend () -> Long) {
        running = true
        rearm = false
        levelState.floatValue = 1f
        try {
            var windowStartMs = Long.MIN_VALUE
            var lastStepMs = Long.MIN_VALUE
            while (true) {
                val nowMs = awaitFrame() / 1_000_000L
                if (windowStartMs == Long.MIN_VALUE || rearm) {
                    rearm = false
                    windowStartMs = nowMs
                }
                val level = emberWindowLevel(nowMs - windowStartMs)
                if (level <= 0f) break
                if (lastStepMs == Long.MIN_VALUE || nowMs - lastStepMs >= EmberStepMs) {
                    lastStepMs = nowMs
                    breathState.floatValue = emberPhaseAt(nowMs, EmberBreathPeriodMs)
                    shimmerState.floatValue = emberPhaseAt(nowMs, EmberShimmerPeriodMs)
                    levelState.floatValue = level
                }
            }
        } finally {
            settle()
        }
    }
}

/**
 * Drives [life]: a window opens when the surface arrives and again whenever [live] and [host] turn true or [EmberLife.arm] wakes it; it
 * is cancelled when either turns false, and never opens under [reduced]. Takes its frames from [awaitFrame], so a test can count them.
 */
internal suspend fun driveEmberLife(
    life: EmberLife,
    reduced: Boolean,
    live: State<Boolean>,
    host: State<Boolean>,
    awaitFrame: suspend () -> Long,
) {
    if (reduced) {
        life.settle()
        return
    }
    snapshotFlow { (live.value && host.value) to life.trigger.intValue }.collectLatest { (on, _) ->
        if (!on) {
            life.settle()
            return@collectLatest
        }
        life.runWindow(awaitFrame)
    }
}

/**
 * Whether the hosting window is worth animating: its lifecycle is at least STARTED and the screen is interactive (a receiver follows
 * the screen going off and on; [PowerManager.isInteractive] is the first answer). A State read only in effects.
 */
@Composable
internal fun rememberEmberHostRunning(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val interactive = remember { mutableStateOf(emberScreenInteractive(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                interactive.value = emberScreenInteractive(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val registered = runCatching { context.registerReceiver(receiver, filter) }.isSuccess
        interactive.value = emberScreenInteractive(context)
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
    return remember {
        derivedStateOf { emberHostRuns(lifecycleState.value.isAtLeast(Lifecycle.State.STARTED), interactive.value) }
    }
}

private fun emberScreenInteractive(context: Context): Boolean =
    runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive ?: true }.getOrDefault(true)

/**
 * A bounded ember life for a surface, for draw blocks to read. [live] has NO default on purpose: pass the pane's own liveness
 * ([LocalMimirMotionLive] for a pane of the Bottom's workspace, the window's own for a window of its own), so a caller outside
 * any motion-live scope has to say what it is. The first window opens at arrival.
 */
@Composable
fun rememberEmberLife(live: State<Boolean>): EmberLife {
    val life = remember { EmberLife() }
    val reduced = mimirReducedMotion()
    val host = rememberEmberHostRunning()
    LaunchedEffect(life, reduced, live, host) {
        driveEmberLife(life, reduced, live, host) { withInfiniteAnimationFrameNanos { it } }
    }
    return life
}

/** How strongly a plate glows: its breath at rest, brighter when [lit] or [pressed]. The numbers are the design's: low amplitude. */
fun emberPlateStrength(wave: Float, lit: Boolean, pressed: Boolean): Float {
    val rest = EmberRestStrength + EmberBreathAmplitude * wave
    return if (lit || pressed) (rest * EmberLitBoost).coerceAtMost(1f) else rest
}

internal const val EmberRestStrength = 0.50f
internal const val EmberBreathAmplitude = 0.22f
internal const val EmberLitBoost = 1.75f

private const val GlowRings = 4
private val GlowRingStep = 1.6.dp

/**
 * Draws a plate: the console-icon metal body, a warm rim light along the top, an inner ember glow, a hairline, and the outer ember
 * glow. The outer glow reaches past the plate's own bounds (a draw block is not clipped), so the caller must not clip it.
 */
internal fun DrawScope.drawEmberPlate(
    palette: MimirThemePalette,
    body: Brush,
    corner: Dp,
    strength: Float,
    lit: Boolean,
) {
    val radius = CornerRadius(corner.toPx())
    val step = GlowRingStep.toPx()
    // The outer glow first, so the body covers its inner edge.
    for (i in GlowRings downTo 1) {
        val fade = (1f - (i - 1).toFloat() / GlowRings)
        val grow = i * step
        drawRoundRect(
            color = palette.tileGlow.copy(alpha = (palette.tileGlow.alpha * fade * strength).coerceIn(0f, 1f)),
            topLeft = Offset(-grow, -grow),
            size = Size(size.width + 2f * grow, size.height + 2f * grow),
            cornerRadius = CornerRadius(radius.x + grow),
            style = Stroke(width = step),
        )
    }
    drawRoundRect(brush = body, alpha = if (lit) 1f else EmberBodyAlpha, cornerRadius = radius)
    // The inner glow: rings drawn inward from the edge, strongest at the edge.
    for (i in 0 until GlowRings) {
        val fade = 1f - i.toFloat() / GlowRings
        val inset = i * step + step / 2f
        drawRoundRect(
            color = palette.emberSoft.copy(alpha = (palette.emberSoft.alpha * fade * strength * EmberInnerGain).coerceIn(0f, 1f)),
            topLeft = Offset(inset, inset),
            size = Size((size.width - 2f * inset).coerceAtLeast(0f), (size.height - 2f * inset).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((radius.x - inset).coerceAtLeast(0f)),
            style = Stroke(width = step),
        )
    }
    // The warm rim light along the top edge.
    val rim = 1.dp.toPx()
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, palette.accentBright.copy(alpha = EmberRimAlpha), Color.Transparent),
            startX = radius.x,
            endX = (size.width - radius.x).coerceAtLeast(radius.x + 1f),
        ),
        start = Offset(radius.x, rim),
        end = Offset((size.width - radius.x).coerceAtLeast(radius.x + 1f), rim),
        strokeWidth = rim,
    )
    drawRoundRect(
        color = palette.accent.copy(alpha = if (lit) EmberLitBorderAlpha else EmberRestBorderAlpha),
        cornerRadius = radius,
        style = Stroke(width = 0.5.dp.toPx()),
    )
}

private const val EmberBodyAlpha = 0.55f
private const val EmberInnerGain = 2.2f
private const val EmberRimAlpha = 0.45f
private const val EmberLitBorderAlpha = 0.9f
private const val EmberRestBorderAlpha = 0.4f

/**
 * The plate's whole look: [drawEmberPlate] behind the content, its strength read from [life] (a bounded draw-phase clock) and [pressed]
 * (read in draw too), so neither recomposes anything. At rest the life is eased to the middle: the same rim light and glow, steady.
 */
internal fun Modifier.emberPlate(
    palette: MimirThemePalette,
    corner: Dp,
    lit: Boolean,
    life: EmberLife,
    pressed: State<Boolean>? = null,
): Modifier = this.drawWithCache {
    val body = platformConsoleIconBrush(palette)
    onDrawBehind {
        drawEmberPlate(
            palette = palette,
            body = body,
            corner = corner,
            strength = emberPlateStrength(life.wave(), lit, pressed?.value == true),
            lit = lit,
        )
    }
}
