package com.gruning.mimir.rewrite.core.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * the Thor keyboard's trackpad. Core-ui owns the host contract, the pure gesture machine and the surface;
 * the app owns the Accessibility service that places taps and swipes (`MimirTrackpadAccessibilityService`).
 *
 * Nothing here reads the screen, a field or what is typed: the trackpad only turns finger movement on the
 * keyboard into pointer movement and a handful of gestures.
 */
interface MimirTrackpadHost {
    /** Whether Mimir's Accessibility service is connected right now; when false a tap on the key shows consent. */
    val serviceConnected: Boolean

    /**
     * The trackpad surface appeared: show the pointer (at the centre of Main the first time) and return THIS
     * activation's handle. Every gesture is sent through the returned handle, which carries the session token captured
     * here, so a callback left over from an earlier activation can never act in a later one. The handle's own
     * [begin] returns itself.
     */
    fun begin(): MimirTrackpadHost

    /** Move the pointer by an already-accelerated delta in Main's pixels. */
    fun pointerMove(dx: Float, dy: Float)

    /** A tap: one click at the pointer. */
    fun click()

    /** The SECOND tap of a double click (the first was sent as [click]); the system sees two taps in its window. */
    fun doubleClick()

    /** Two fingers moved by this finger-direction delta (trackpad pixels); the host sends one swipe at [scrollEnd]. */
    fun scrollBy(dx: Float, dy: Float)

    /** Two fingers lifted: send the swipe the deltas added up to (natural or reversed per the player's setting). */
    fun scrollEnd()

    /** The two-finger gesture was cancelled: send nothing. */
    fun scrollCancel()

    /** A 450 ms long press with no move: press and keep holding at the pointer. */
    fun holdBegin()

    /** Drag the held press by an accelerated delta. */
    fun holdMove(dx: Float, dy: Float)

    /** Lift: release the held press (an unmoved hold is therefore a long press). */
    fun holdEnd()

    /** Open Android's Accessibility settings so the player can turn the service on. */
    fun openAccessibilitySettings()

    /**
     * Opens the full-screen trackpad window on the Bottom (service on). The pad is its own window, owned by the
     * Accessibility service, so it stays open until the player closes it; nothing in the keyboard closes it. A no-op on
     * a host or handle that has no pad.
     */
    fun openPad() = Unit

    /** The pointer speed step, 1..[TrackpadSpeed.Steps]; the pad scales the pointer's gain by [TrackpadSpeed.factor] of it. */
    val speedStep: Int get() = TrackpadSpeed.Default

    /**
     * Sets the pointer speed to [step] (clamped to 1..[TrackpadSpeed.Steps]), stores it where Settings reads it, and returns the step
     * now in force (pad8: the Slow-to-Fast gauge sets a level directly; nothing cycles any more).
     */
    fun setSpeed(step: Int): Int = speedStep

    /** The pad's Back plate: one Back on MAIN. A no-op where the host has no such action, or the activation is over. */
    fun back() = Unit

    /** Whether this activation may still inject (its session is live and the device is unlocked). A spin that outlives it stops. */
    val sessionLive: Boolean get() = true

    /**
     * The mode ended: release any held stroke and hide the pointer. Called on the HOST (the keyboard closing, a secret
     * field taking over) it ends whichever activation is live; called on an activation's handle it ends only that one.
     */
    fun end()
}

/** Shared trackpad consent copy. */
object MimirTrackpadCopy {
    const val Title = "Turn on the trackpad"
    const val Body = "The trackpad needs Android's Accessibility permission so Mimir can tap and swipe on your main " +
        "screen for you. Mimir uses it only to place taps and swipes where your pointer is, and only while the " +
        "trackpad is on. It does not read your screen, your typing or your passwords, and nothing leaves the device."
    const val OpenSettings = "Open Accessibility settings"
    const val NotNow = "Not now"
    const val GreyedOutHint = "If the switch is greyed out, open Mimir's App info, tap the three dots, choose " +
        "Allow restricted settings, then try again."
    const val KeysStrip = "Keys"
    const val PadTitle = "ᛗ TRACKPAD"
    const val KeyboardPlate = "Keyboard"
    const val ClosePlate = "Close"
    const val CloseDescription = "Close trackpad"
    const val Legend = "tap to click  ·  hold to drag  ·  two fingers to scroll"
    const val BackPlate = "Back ↩"
    const val BackDescription = "Back on the main screen"
    const val SpeedDescription = "Pointer speed"
    const val SlowLabel = "Slow"
    const val FastLabel = "Fast"
    const val DrumDescription = "Rune drum"
}

/**
 * The pointer speed dial : five steps, the default the speed the pad had before the dial. The factor
 * multiplies the pointer gain curve; named, never an inline number.
 */
object TrackpadSpeed {
    const val Steps = 5
    const val Default = 3
    const val Factor1 = 0.6f
    const val Factor2 = 0.8f
    const val Factor3 = 1.0f
    const val Factor4 = 1.3f
    const val Factor5 = 1.7f

    fun clamp(step: Int): Int = step.coerceIn(1, Steps)

    fun factor(step: Int): Float = when (clamp(step)) {
        1 -> Factor1
        2 -> Factor2
        3 -> Factor3
        4 -> Factor4
        else -> Factor5
    }

    /** Tap cycles 1 to 5 and back to 1. */
    fun next(step: Int): Int = if (clamp(step) >= Steps) 1 else clamp(step) + 1
}

/**
 * Whether the keyboard offers the trackpad key at all: API 32+ (the gesture API's display id), a presentation
 * display to be the other screen, and the SoC posture passing. Never reasoned from a device model.
 */
fun mimirTrackpadCapability(sdkInt: Int, hasPresentationDisplay: Boolean, socPosturePasses: Boolean): Boolean =
    sdkInt >= 32 && hasPresentationDisplay && socPosturePasses

/** Finger speed to pointer gain: 1.2x for slow, careful movement up to 4x for a fast flick, monotonic and capped. */
object PointerAcceleration {
    const val MinGain = 1.2f
    const val MaxGain = 4f
    private const val SlowSpeedPxPerMs = 0.05f
    private const val FastSpeedPxPerMs = 1.5f

    /** [speedPxPerMs] is the finger's speed on the trackpad surface; negative or NaN is treated as zero. */
    fun gain(speedPxPerMs: Float): Float {
        val speed = if (speedPxPerMs.isNaN()) 0f else speedPxPerMs.coerceAtLeast(0f)
        val t = ((speed - SlowSpeedPxPerMs) / (FastSpeedPxPerMs - SlowSpeedPxPerMs)).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return MinGain + (MaxGain - MinGain) * eased
    }
}

/** The pointer's place on Main, always inside the display; [reclamped] follows a rotation or a resize. */
data class MimirPointerPosition(val x: Float, val y: Float) {
    fun moved(dx: Float, dy: Float, width: Int, height: Int): MimirPointerPosition =
        MimirPointerPosition(x + dx, y + dy).reclamped(width, height)

    fun reclamped(width: Int, height: Int): MimirPointerPosition = MimirPointerPosition(
        x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat()),
        y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat()),
    )

    companion object {
        fun centre(width: Int, height: Int) = MimirPointerPosition(width / 2f, height / 2f).reclamped(width, height)
    }
}

/**
 * The trackpad's gesture machine: pure, no Compose, no Android. Fed per-pointer down/move/up with times and a tick
 * for the long press, it drives a [MimirTrackpadHost].
 *  - one finger, moving past [slopPx]: pointer movement with [PointerAcceleration];
 *  - one finger, lifted unmoved within [TapMs]: a click; a second within [DoubleTapMs]: a double click;
 *  - one finger, still and unmoved for [LongPressMs]: press and hold; moving then drags; lifting releases;
 *  - a second finger before any hold: a two-finger scroll; a stray finger during a hold or a scroll is ignored;
 *  - cancel: a held press is released, a scroll is dropped.
 */
class TrackpadGestureMachine(
    private val host: MimirTrackpadHost,
    private val slopPx: Float = 12f,
    /** Scales the pointer gain: the speed dial's factor, read at each move so a change applies at once. */
    private val gainScale: () -> Float = { 1f },
) {
    private enum class Mode { IDLE, ONE, HOLD, TWO, IGNORE }

    private class Finger(var x: Float, var y: Float, var t: Long)

    private val fingers = LinkedHashMap<Int, Finger>()
    private var mode = Mode.IDLE
    private var downAt = 0L
    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private var lastTapAt = Long.MIN_VALUE
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f
    private var scrolling = false

    /** True while a one-finger touch could still become a long press (the surface ticks only then). */
    val longPressPending: Boolean get() = mode == Mode.ONE && !moved

    /** How long the current one-finger touch has been down while it could still become a hold; 0 in every other state. */
    fun holdPendingElapsedMs(t: Long): Long = if (longPressPending) (t - downAt).coerceAtLeast(0L) else 0L

    /** True while a long press is held and being dragged (the pad tints its field). */
    val isHolding: Boolean get() = mode == Mode.HOLD

    /** True while a two-finger scroll is under way (the pad lights its rail). */
    val isScrolling: Boolean get() = mode == Mode.TWO && scrolling

    fun down(id: Int, x: Float, y: Float, t: Long) {
        when (mode) {
            Mode.IDLE -> {
                fingers.clear()
                fingers[id] = Finger(x, y, t)
                mode = Mode.ONE
                downAt = t
                startX = x
                startY = y
                moved = false
            }
            Mode.ONE -> {
                fingers[id] = Finger(x, y, t)
                mode = Mode.TWO
                scrollAccumX = 0f
                scrollAccumY = 0f
                scrolling = false
            }
            // A stray finger during a hold, a scroll or after one finger lifted never changes what is in progress.
            Mode.HOLD, Mode.TWO, Mode.IGNORE -> Unit
        }
    }

    fun move(id: Int, x: Float, y: Float, t: Long) {
        val finger = fingers[id] ?: return
        val dx = x - finger.x
        val dy = y - finger.y
        val dt = (t - finger.t).coerceAtLeast(1L)
        finger.x = x
        finger.y = y
        finger.t = t
        when (mode) {
            Mode.ONE -> {
                if (!moved && hypot(x - startX, y - startY) > slopPx) moved = true
                if (moved) {
                    val gain = PointerAcceleration.gain(hypot(dx, dy) / dt) * gainScale()
                    host.pointerMove(dx * gain, dy * gain)
                }
            }
            Mode.HOLD -> {
                val gain = PointerAcceleration.gain(hypot(dx, dy) / dt) * gainScale()
                host.holdMove(dx * gain, dy * gain)
            }
            Mode.TWO -> {
                // One event moves one finger; the pair's centre moves half as far.
                val cx = dx / 2f
                val cy = dy / 2f
                if (!scrolling) {
                    scrollAccumX += cx
                    scrollAccumY += cy
                    if (hypot(scrollAccumX, scrollAccumY) > slopPx) {
                        scrolling = true
                        host.scrollBy(scrollAccumX, scrollAccumY)
                    }
                } else {
                    host.scrollBy(cx, cy)
                }
            }
            Mode.IDLE, Mode.IGNORE -> Unit
        }
    }

    fun up(id: Int, t: Long) {
        if (fingers.remove(id) == null) return
        when (mode) {
            Mode.ONE -> {
                if (!moved && t - downAt <= TapMs) {
                    if (lastTapAt != Long.MIN_VALUE && t - lastTapAt <= DoubleTapMs) {
                        host.doubleClick()
                        lastTapAt = Long.MIN_VALUE
                    } else {
                        host.click()
                        lastTapAt = t
                    }
                }
                mode = Mode.IDLE
            }
            Mode.HOLD -> {
                host.holdEnd()
                mode = Mode.IDLE
            }
            Mode.TWO -> {
                if (scrolling) host.scrollEnd()
                mode = Mode.IGNORE
            }
            Mode.IDLE, Mode.IGNORE -> Unit
        }
        if (fingers.isEmpty()) mode = Mode.IDLE
    }

    /** The touch was taken away (or the surface left): a held press is released and a scroll is dropped. */
    fun cancel() {
        when (mode) {
            Mode.HOLD -> host.holdEnd()
            Mode.TWO -> if (scrolling) host.scrollCancel()
            else -> Unit
        }
        fingers.clear()
        mode = Mode.IDLE
        scrolling = false
    }

    /** Called while a one-finger touch is down; starts the hold once the finger has stayed still long enough. */
    fun tick(t: Long) {
        if (mode == Mode.ONE && !moved && t - downAt >= LongPressMs) {
            mode = Mode.HOLD
            host.holdBegin()
        }
    }

    companion object {
        const val TapMs = 180L
        const val DoubleTapMs = 300L
        const val LongPressMs = 450L

        /** The hold ring appears only once a press has lasted this long (about 60 percent of [LongPressMs]); a tap never reaches it. */
        const val HoldVisualDelayMs = LongPressMs * 6 / 10
    }
}

private val TrackpadShape = RoundedCornerShape(12.dp)
private val KeysStripHeight = 34.dp

/** The consent card the Trackpad key shows while Mimir's Accessibility service is off. Same height as the key rows. */
@Composable
internal fun MimirTrackpadConsentCard(
    onOpenSettings: () -> Unit,
    height: Dp,
    palette: MimirThemePalette,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (height.isSpecified) Modifier.height(height) else Modifier)
            .background(palette.accent.copy(alpha = 0.07f), TrackpadShape)
            .border(0.5.dp, palette.accent.copy(alpha = 0.35f), TrackpadShape)
            .padding(MimirSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        // The consent copy is shown exactly as written (never upper-cased as chrome): authored/body intents.
        MimirIntentText(
            intent = MimirTextIntent.Authored(MimirTrackpadCopy.Title),
            style = MimirTypeScale.Row,
            color = palette.textPrimary,
            maxLines = 1,
        )
        MimirIntentText(
            intent = MimirTextIntent.Body(MimirTrackpadCopy.Body),
            style = MimirTypeScale.SupportCompact,
            color = palette.textPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        MimirIntentText(
            intent = MimirTextIntent.Body(MimirTrackpadCopy.GreyedOutHint),
            style = MimirTypeScale.SupportCompact,
            color = palette.textMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
            KeysStrip(
                palette = palette,
                label = MimirTrackpadCopy.OpenSettings,
                onTap = {
                    onOpenSettings()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                accent = true,
                exactCase = true,
            )
            KeysStrip(
                palette = palette,
                label = MimirTrackpadCopy.NotNow,
                onTap = onDismiss,
                modifier = Modifier.weight(1f),
                exactCase = true,
            )
        }
    }
}

/**
 * The same consent card as a window's whole content (the Bottom menu's Trackpad row while the service is off): centred on
 * the surface, themed by the caller's [MimirTheme].
 */
@Composable
fun MimirTrackpadConsentPanel(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    Box(
        modifier = modifier.fillMaxSize().background(palette.surfaceBase.copy(alpha = 0.92f)).padding(MimirSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        MimirTrackpadConsentCard(
            onOpenSettings = onOpenSettings,
            height = Dp.Unspecified,
            palette = palette,
            onDismiss = onDismiss,
        )
    }
}

/** A touch-only strip button: pointer input, never a focus node. */
@Composable
private fun KeysStrip(
    palette: MimirThemePalette,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    exactCase: Boolean = false,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(KeysStripHeight)
            .background(palette.accent.copy(alpha = if (accent) 0.22f else 0.10f), shape)
            .border(0.5.dp, palette.accent.copy(alpha = 0.45f), shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label = label) {
                    onTap()
                    true
                }
            }
            .mimirPlateTap(onTap),
        contentAlignment = Alignment.Center,
    ) {
        MimirIntentText(
            intent = if (exactCase) MimirTextIntent.Authored(label) else MimirTextIntent.StaticChrome(label),
            style = MimirTypeScale.Pill,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

