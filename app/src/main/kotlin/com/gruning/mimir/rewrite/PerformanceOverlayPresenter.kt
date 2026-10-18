package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gruning.mimir.rewrite.core.companionnative.OverlayScreenActivity
import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import com.gruning.mimir.rewrite.core.tweaker.tweakerProfileDisplayCaps
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirFonts
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal sealed interface PerformanceOverlayPresentation {
    data object Warming : PerformanceOverlayPresentation
    data object Paused : PerformanceOverlayPresentation
    data class Active(
        val telemetry: TweakerTelemetry,
        val environment: PerformanceOverlayEnvironment = PerformanceOverlayEnvironment(),
    ) : PerformanceOverlayPresentation
}

/** Which plate to present. Separated from construction so the decision is testable. */
internal enum class PerformanceOverlayPresentationKind { PAUSED, WARMING, ACTIVE }

/**
 * The presentation decision, extracted from the service's sampling loop so it can be exercised
 * directly — the loop itself is a private suspend function inside a Service and no test can reach it.
 * ALWAYS-ON presents from the first live sample. That sample already carries CPU, GPU and battery;
 * withholding the whole plate to announce TELEMETRY WARMING hid readings the overlay had in hand.
 * Frame rate is not a reason to withhold it: when it is absent [performanceOverlayFpsLabel] renders
 * "—" rather than a fabricated number.
 */
internal fun performanceOverlayPresentationKind(
    fresh: Boolean,
    receivedSamples: Int,
    visibility: PerformanceOverlayVisibility,
): PerformanceOverlayPresentationKind = when {
    !fresh -> PerformanceOverlayPresentationKind.PAUSED
    receivedSamples == 1 && visibility != PerformanceOverlayVisibility.ALWAYS ->
        PerformanceOverlayPresentationKind.WARMING
    else -> PerformanceOverlayPresentationKind.ACTIVE
}

internal data class PerformanceOverlayEnvironment(
    val displayRefreshRateHz: Float? = null,
    val ramUsedBytes: Long? = null,
    val ramTotalBytes: Long? = null,
    val batteryPercent: Int? = null,
    val batteryStatus: String? = null,
    val batteryStatusConflict: Boolean = false,
    val batteryEstimatedRemainingMs: Long? = null,
    /** Persisted only after API-v5 returns authoritative profile truth. */
    val selectedProfileId: String = TweakerBridge.STOCK_PROFILE,
    /** Whole-device CPU busy percentage from the current utilization snapshot. */
    val deviceCpuPercent: Int? = null,
    /** Whole-device GPU busy percentage from the current utilization snapshot. */
    val deviceGpuPercent: Int? = null,
    /** GameNative renderer FPS, or null when its fresh row is unavailable; other games remain blank. */
    val foregroundFrameRate: Int? = null,
    /** Retained for service compatibility; renderer sample availability controls the FPS graph state. */
    val mimirPrimaryForeground: Boolean = false,
)

/**
 * The canonical telemetry sequence, read left to right as one sentence: how it feels, why, what it
 * costs, how long that leaves you, then diagnostics.
 *
 * Declaration order **is** render order. A mode chooses which cells it carries
 * ([performanceOverlayCarriedCells]) and never the order they appear in, so every mode renders an
 * order-preserving subsequence of this list and an inverted mode cannot be expressed.
 */
internal enum class PerformanceOverlayCell(val label: String) {
    FPS("FPS"),
    CPU("CPU"),
    GPU("GPU"),
    DRAW("DRAW"),
    BATT("BATT"),
    LEFT("LEFT"),
    RAM("RAM"),
}

/**
 * Cell membership per mode. Deliberately a Set: sequencing belongs to [PerformanceOverlayCell]
 * alone, so a mode has no way to state an order that disagrees with the canonical one.
 */
private fun performanceOverlayCarriedCells(
    mode: PerformanceOverlayMode,
): Set<PerformanceOverlayCell> = when (mode) {
    // Compact spends its four slots on the two numbers that explain a dropped frame and the one that
    // says how long the session has left; DRAW is the cell it trades away.
    PerformanceOverlayMode.MINIMAL -> setOf(
        PerformanceOverlayCell.FPS,
        PerformanceOverlayCell.CPU,
        PerformanceOverlayCell.GPU,
        PerformanceOverlayCell.BATT,
    )
    PerformanceOverlayMode.CLASSIC -> setOf(
        PerformanceOverlayCell.FPS,
        PerformanceOverlayCell.CPU,
        PerformanceOverlayCell.GPU,
        PerformanceOverlayCell.DRAW,
    )
    PerformanceOverlayMode.DETAILED -> PerformanceOverlayCell.entries.toSet()
}

/** The cells [mode] renders — always an order-preserving subsequence of the canonical sequence. */
internal fun performanceOverlayCells(mode: PerformanceOverlayMode): List<PerformanceOverlayCell> {
    val carried = performanceOverlayCarriedCells(mode)
    return PerformanceOverlayCell.entries.filter { it in carried }
}

/**
 * The foreground game's own frame rate. `PerformanceOverlayService.readForegroundGameFrameRate` asks the
 * privileged utility for it on the overlay beat, for a launch-target package that UsageStats shows in
 * the foreground both before and after the read. Two producers answer:
 * - GameNative (`app.gamenative`): its renderer JSONL, on the vendor route. Policy
 *   2026-09-08: that value is base/rendered game FPS, not synthetic frame-generation output.
 * - Dispatch-registry emulators, on the vendor route only: a native helper counts the emulator process's
 *   own frame submissions per interval
 *   (`AndroidCertifiedVendorPrivilegeGateway.readEmulatorRenderFrameRateMilli`). A process it cannot
 *   attach a grounded producer to reports unsupported.
 * The HUD never substitutes a SurfaceFlinger or display-compositor rate, and it never fabricates a
 * refresh-rate cap.
 *
 * The value is unavailable whenever the route has no fresh accepted sample, including the warm-up before
 * a producer's first sample. Round-5 triage rank 7b: the "—" a new session starts on is that warm-up, not
 * a missing source.
 */
internal fun performanceOverlayFpsValue(environment: PerformanceOverlayEnvironment): Int? {
    return environment.foregroundFrameRate?.takeIf { it in 0..1_000 }
}

internal fun performanceOverlayFpsLabel(environment: PerformanceOverlayEnvironment): String =
    performanceOverlayFpsValue(environment)?.toString() ?: "—"

internal fun performanceOverlayDeviceLabel(temperatureC: Int?, usagePercent: Int?): String = when {
    temperatureC != null && usagePercent != null -> "$temperatureC°C · $usagePercent%"
    temperatureC != null -> "$temperatureC°C · —"
    usagePercent != null -> "— · $usagePercent%"
    else -> "—"
}

internal fun performanceOverlayClockLabel(clockHz: Long?): String = when {
    clockHz == null || clockHz <= 0L -> "—"
    else -> String.format(Locale.US, "%.1f GHz", clockHz / 1_000_000_000.0)
}

internal fun performanceOverlayCpuClockLabel(policyClocksHz: List<Long>): String {
    // A single readable current clock, never a current/limit-looking policy pair.
    return performanceOverlayClockLabel(policyClocksHz.filter { it > 0L }.maxOrNull())
}

/**
 * The processor line, shared by CPU and GPU so the two can never spell one shape two ways.
 *
 * The middle slot takes a formatted STRING so both cells carry the same whole-device busy fraction
 * and share one rendering shape.
 */
internal fun performanceOverlayProcessorLabel(
    temperatureC: Int?,
    usageLabel: String,
    clockLabel: String,
): String = listOf(
    temperatureC?.let { "$it°C" } ?: "—",
    usageLabel,
    clockLabel,
).joinToString("·")

/** A whole-device busy fraction, 0..100. */
internal fun performanceOverlayUsageLabel(usagePercent: Int?): String =
    usagePercent?.takeIf { it in 0..100 }?.let { "$it%" } ?: "—"

/**
 * Hottest CPU thermal zone (`cpuTempC`), not the package mean (`cpuPackageTempC`) — policy
 * 2026-08-22, made alongside the fan-control
 * temperature readout, which sources CPU the same way. Before this ruling the two Mimir surfaces
 * could print different CPU numbers in the same second (this cell showed the mean, the fan readout
 * shows hottest); that asymmetry is now retired product-wide by construction, not just documented.
 *
 * Why hottest, accepted cost stated to the player: a mean over classified thermal zones can sit
 * ~30 °C below the hottest core — an Odin test device measured CPU0/CPU1 at 89.9 °C while the package mean
 * read comfortable, the recorded incident behind this choice. A comfort/perf readout that smooths
 * away the hot cluster under-reports precisely the moment it exists for. The number is jumpier as a
 * result (cores spike where a mean would not) — an accepted tradeoff, not an oversight.
 *
 * The GPU temperature rendered in the identical slot beside it is also the hottest matching zone
 * (`telemetry.gpuTempC`), so [performanceOverlayProcessorLabel] formatting both the same way is now
 * honest symmetry rather than a mean-vs-peak collision — see [performanceOverlayCellSpokenName].
 */
internal fun performanceOverlayCpuTemperature(telemetry: TweakerTelemetry): Int? =
    telemetry.cpuTempC

internal fun performanceOverlayDrawLabel(
    telemetry: TweakerTelemetry,
    batteryStatus: String? = null,
    batteryStatusConflict: Boolean = false,
): String {
    if (batteryStatusConflict) return "—"
    val discharging = coherentPerformanceOverlayBatteryStatus(
        tweakerStatus = telemetry.batteryStatus,
        androidStatus = batteryStatus,
    ).equals("Discharging", ignoreCase = true)
    val power = telemetry.batteryPowerMw
    return if (discharging && power != null) {
        String.format(Locale.US, "%.1f W", abs(power.toDouble()) / 1_000.0)
    } else {
        "—"
    }
}

internal fun performanceOverlayRamLabel(usedBytes: Long?, totalBytes: Long?): String {
    if (usedBytes == null || totalBytes == null || totalBytes <= 0L || usedBytes !in 0L..totalBytes) return "—"
    val gib = 1024.0 * 1024.0 * 1024.0
    return String.format(Locale.US, "%.1f/%.1fG", usedBytes / gib, totalBytes / gib)
}

/**
 * BATT is battery level, in every mode. It used to carry the charge state too in Extended
 * (`73%·DISCHG`) while Compact showed the level alone, so one token named two different quantities
 * depending on which mode you were in. The state itself is not lost: DRAW already blanks unless the
 * pack is discharging, LEFT only estimates while it is, and the spoken description says it in words.
 */
internal fun performanceOverlayBatteryLevelLabel(percent: Int?): String =
    percent?.takeIf { it in 0..100 }?.let { "$it%" } ?: "—"

internal fun performanceOverlayRemainingLabel(remainingMs: Long?): String {
    val totalMinutes = remainingMs
        ?.takeIf { it in 60_000L..72L * 60L * 60L * 1_000L }
        ?.div(60_000L)
        ?: return "—"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}H ${minutes.toString().padStart(2, '0')}M" else "${minutes}M"
}

/**
 * The one place a cell becomes text, shared by the plate and the spoken description so the two can
 * never name different quantities. Compact keeps CPU/GPU to temperature alone to hold its plate
 * narrow; every other reading is identical in every mode that carries it.
 */
internal fun performanceOverlayCellValue(
    cell: PerformanceOverlayCell,
    mode: PerformanceOverlayMode,
    telemetry: TweakerTelemetry,
    environment: PerformanceOverlayEnvironment,
): String = when (cell) {
    PerformanceOverlayCell.FPS -> performanceOverlayFpsLabel(environment)
    PerformanceOverlayCell.CPU -> if (mode == PerformanceOverlayMode.MINIMAL) {
        performanceOverlayCpuTemperature(telemetry)?.let { "$it°C" } ?: "—"
    } else {
        performanceOverlayProcessorLabel(
            performanceOverlayCpuTemperature(telemetry),
            performanceOverlayUsageLabel(environment.deviceCpuPercent),
            performanceOverlayCpuClockLabel(telemetry.cpuPolicyClocksHz),
        )
    }
    PerformanceOverlayCell.GPU -> if (mode == PerformanceOverlayMode.MINIMAL) {
        telemetry.gpuTempC?.let { "$it°C" } ?: "—"
    } else {
        performanceOverlayProcessorLabel(
            telemetry.gpuTempC,
            performanceOverlayUsageLabel(environment.deviceGpuPercent),
            performanceOverlayClockLabel(telemetry.gpuClockHz),
        )
    }
    PerformanceOverlayCell.DRAW -> performanceOverlayDrawLabel(
        telemetry,
        environment.batteryStatus,
        environment.batteryStatusConflict,
    )
    PerformanceOverlayCell.BATT -> performanceOverlayBatteryLevelLabel(environment.batteryPercent)
    PerformanceOverlayCell.LEFT ->
        performanceOverlayRemainingLabel(environment.batteryEstimatedRemainingMs)
    PerformanceOverlayCell.RAM ->
        performanceOverlayRamLabel(environment.ramUsedBytes, environment.ramTotalBytes)
}

internal fun appendPerformanceOverlayFpsHistory(
    history: List<Int?>,
    sample: Int?,
    limit: Int = PerformanceOverlayHistoryLimit,
): List<Int?> = (history + sample).takeLast(limit.coerceAtLeast(1))

/** The graph follows the newest renderer sample; a missing newest sample clears stale history. */
@Suppress("UNUSED_PARAMETER")
internal fun performanceOverlayFrameStateLabel(
    primaryForeground: Boolean,
    samples: List<Int?>,
): String? = when {
    samples.lastOrNull() == null -> "FPS UNAVAILABLE"
    else -> null
}

internal fun performanceOverlayFrameHistoryForDrawing(
    primaryForeground: Boolean,
    samples: List<Int?>,
): List<Int?> =
    if (performanceOverlayFrameStateLabel(primaryForeground, samples) == null) samples else emptyList()

internal fun performanceOverlayStatusWidthDp(
    label: String,
    fontScale: Float,
    measureTextWidthDp: (text: String, fontSizeSp: Float) -> Float,
): Float {
    val safeFontScale = fontScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val measured = measureTextWidthDp(label, 8f).takeIf { it.isFinite() && it >= 0f }
        ?: label.length * 8f * 0.62f * safeFontScale
    val tracking = (label.length - 1).coerceAtLeast(0) * 0.25f * safeFontScale
    return ceil(
        PerformanceMinimalHorizontalPaddingDp +
            PerformanceOverlayIdentityTerminalWidthDp +
            PerformanceStatusGapDp +
            measured + tracking,
    )
}

internal fun performanceOverlayGraphX(
    sampleIndex: Int,
    sampleCount: Int,
    widthPx: Float,
): Float {
    if (sampleCount < 2 || !widthPx.isFinite() || widthPx <= 0f) return 0f
    return sampleIndex.coerceIn(0, sampleCount - 1) * widthPx / (sampleCount - 1)
}

internal fun performanceOverlayShouldStartDrag(distancePx: Float, touchSlopPx: Float): Boolean =
    distancePx.isFinite() && touchSlopPx.isFinite() && distancePx > touchSlopPx.coerceAtLeast(0f)

internal enum class PerformanceOverlayGestureOutcome {
    TAP,
    DRAG,
    NONE,
}

internal class PerformanceOverlayGestureArbiter(private val touchSlopPx: Float) {
    private var cancelled = false
    private var everDragged = false

    val dragging: Boolean get() = everDragged && !cancelled

    fun update(distanceFromDownPx: Float, pointerCount: Int): Boolean {
        if (pointerCount != 1) {
            cancelled = true
            return false
        }
        if (cancelled || everDragged) return false
        if (!performanceOverlayShouldStartDrag(distanceFromDownPx, touchSlopPx)) return false
        everDragged = true
        return true
    }

    fun cancel() {
        cancelled = true
    }

    fun finish(): PerformanceOverlayGestureOutcome = when {
        everDragged -> PerformanceOverlayGestureOutcome.DRAG
        cancelled -> PerformanceOverlayGestureOutcome.NONE
        else -> PerformanceOverlayGestureOutcome.TAP
    }
}

/**
 * Spoken name for a cell. The plate's labels are three- and four-letter abbreviations, and read
 * aloud two of them name the wrong quantity outright: DRAW is whole-device draw at the battery
 * terminal rather than processor draw, and RAM is every process on the device rather than the game's.
 *
 * The three-letter labels carry the established HUD vocabulary. CPU and GPU percentages are both
 * whole-device utilization, while their temperature slots remain hottest matching zones. The
 * 2026-08-22 ruling documented on
 * [performanceOverlayCpuTemperature] retired that asymmetry: both are now the hottest matching zone,
 * so both names say so. Kept as two distinct strings (not deduplicated to one shared constant)
 * because a future re-divergence should have to edit two literals, not silently drift one shared one.
 */
internal fun performanceOverlayCellSpokenName(cell: PerformanceOverlayCell): String = when (cell) {
    PerformanceOverlayCell.FPS -> "GameNative renderer FPS; other games unavailable"
    PerformanceOverlayCell.CPU ->
        "CPU, hottest thermal zone, whole device utilization"
    PerformanceOverlayCell.GPU -> "GPU, hottest thermal zone, whole device"
    PerformanceOverlayCell.DRAW -> "Battery draw"
    PerformanceOverlayCell.BATT -> "Battery level"
    PerformanceOverlayCell.LEFT -> "Estimated time remaining"
    PerformanceOverlayCell.RAM -> "System memory"
}

/** Cell values are authored for a 7 sp plate; its separators and its blank need words to be spoken. */
private fun performanceOverlaySpokenValue(value: String): String =
    value.replace("·", ", ").replace("—", "unavailable")

/** The charge state BATT no longer carries visually; see [performanceOverlayBatteryLevelLabel]. */
private fun performanceOverlayBatteryFlowSpeech(
    telemetry: TweakerTelemetry,
    environment: PerformanceOverlayEnvironment,
): String? {
    if (environment.batteryStatusConflict) return null
    val status = coherentPerformanceOverlayBatteryStatus(
        tweakerStatus = telemetry.batteryStatus,
        androidStatus = environment.batteryStatus,
    )
    return when (performanceOverlayBatteryFlow(status)) {
        PerformanceOverlayBatteryFlow.DISCHARGING -> "discharging"
        PerformanceOverlayBatteryFlow.CHARGING -> "charging"
        PerformanceOverlayBatteryFlow.FULL -> "full"
        PerformanceOverlayBatteryFlow.UNKNOWN -> null
    }
}

/**
 * Generated from the same cell list the plate renders, so the spoken order is the visual order and
 * the spoken cells are the visible cells — including under Compact, which announced readings it does
 * not show.
 */
internal fun performanceOverlayDescription(
    telemetry: TweakerTelemetry,
    environment: PerformanceOverlayEnvironment = PerformanceOverlayEnvironment(),
    mode: PerformanceOverlayMode,
): String = buildString {
    append("Performance overlay. ")
    performanceOverlayCells(mode).forEach { cell ->
        append(performanceOverlayCellSpokenName(cell)).append(' ')
        append(
            performanceOverlaySpokenValue(
                performanceOverlayCellValue(cell, mode, telemetry, environment),
            ),
        )
        if (cell == PerformanceOverlayCell.BATT) {
            performanceOverlayBatteryFlowSpeech(telemetry, environment)?.let {
                append(", ").append(it)
            }
        }
        append(". ")
    }
    append(performanceOverlayProfileDescription(environment.selectedProfileId)).append('.')
}

internal const val PerformanceOverlayHistoryLimit = 30

internal data class PerformanceOverlayDragBounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
)

internal data class PerformanceOverlayPoint(val x: Int, val y: Int)

internal data class PerformanceOverlayDimensionsDp(
    val width: Float,
    val height: Float,
    val renderable: Boolean = true,
)

internal data class PerformanceOverlayViewportPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val leftInset: Int = 0,
    val topInset: Int = 0,
    val rightInset: Int = 0,
    val bottomInset: Int = 0,
    val density: Float = 1f,
)

/** WindowManager overlay coordinates are local to the current display, even when source bounds are offset. */
internal fun performanceOverlayLocalViewport(
    sourceLeft: Int,
    sourceTop: Int,
    sourceRight: Int,
    sourceBottom: Int,
    leftInset: Int = 0,
    topInset: Int = 0,
    rightInset: Int = 0,
    bottomInset: Int = 0,
    density: Float = 1f,
): PerformanceOverlayViewportPx = PerformanceOverlayViewportPx(
    left = 0,
    top = 0,
    right = (sourceRight - sourceLeft).coerceAtLeast(1),
    bottom = (sourceBottom - sourceTop).coerceAtLeast(1),
    leftInset = leftInset,
    topInset = topInset,
    rightInset = rightInset,
    bottomInset = bottomInset,
    density = density,
)

internal fun performanceOverlayProfileMark(profileId: String): String = when (profileId.trim().lowercase()) {
    "quiet" -> "ᛁ"
    "balanced" -> "ᛃ"
    "performance" -> "ᛏ"
    else -> "ᛗ"
}

internal fun performanceOverlayProfileDescription(profileId: String): String =
    "Tweaker profile: ${tweakerProfileDisplayCaps(profileId.trim().lowercase().ifBlank { TweakerBridge.STOCK_PROFILE })}"

/**
 * Full Compact plate width; the mode's own entry point into the shared measurement below.
 */
internal fun performanceOverlayMinimalWidthDp(
    fontScale: Float,
    measureTextWidthDp: (text: String, fontSizeSp: Float) -> Float,
): Float = performanceOverlayContentWidthDp(
    PerformanceOverlayMode.MINIMAL,
    fontScale,
    measureTextWidthDp,
)

private fun estimatedPerformanceOverlayMinimalWidthDp(fontScale: Float): Float =
    performanceOverlayMinimalWidthDp(fontScale) { text, fontSizeSp ->
        text.length * fontSizeSp * 0.62f * fontScale.coerceAtLeast(1f)
    }

/**
 * Tight worst-case plate width for the selected mode; values may shrink inside this safe rail.
 *
 * Counted off the same cell list [PerformanceOverlayContent] composes rather than from per-mode
 * child totals, so adding or dropping a cell cannot leave the plate measuring a row it no longer
 * renders.
 */
internal fun performanceOverlayContentWidthDp(
    mode: PerformanceOverlayMode,
    fontScale: Float,
    measureTextWidthDp: (text: String, fontSizeSp: Float) -> Float,
): Float {
    val safeFontScale = fontScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    fun trackedWidth(text: String, fontSizeSp: Float, letterSpacingSp: Float): Float {
        val measured = measureTextWidthDp(text, fontSizeSp).takeIf { it.isFinite() && it >= 0f }
            ?: text.length * fontSizeSp * 0.62f * safeFontScale
        return measured + (text.length - 1).coerceAtLeast(0) * letterSpacingSp * safeFontScale
    }
    val cells = performanceOverlayCells(mode)
    val metricWidth = cells.sumOf { cell ->
        trackedWidth(cell.label, 7f, 0.2f).toDouble() +
            PerformanceMetricInnerGapDp +
            trackedWidth(performanceOverlayMetricMaximum(mode, cell), 8f, 0.1f)
    }.toFloat()
    // Row children: the rune, one per cell, one separator between adjacent cells, the graph behind
    // its own separator where the mode draws one, then the profile mark. Gaps are children - 1.
    val graphSeats = if (performanceOverlayDrawsGraph(mode)) 1 else 0
    val gapCount = cells.size * 2 + graphSeats * 2
    val separatorCount = (cells.size - 1).coerceAtLeast(0) + graphSeats
    return ceil(
        metricWidth +
            gapCount * performanceOverlayOuterGapDp(mode) +
            separatorCount * PerformanceMinimalSeparatorWidthDp +
            graphSeats * PerformanceDetailedGraphWidthDp +
            PerformanceOverlayIdentityTerminalWidthDp * 2f +
            PerformanceMinimalHorizontalPaddingDp,
    )
}

private fun estimatedPerformanceOverlayContentWidthDp(
    mode: PerformanceOverlayMode,
    fontScale: Float,
): Float = performanceOverlayContentWidthDp(mode, fontScale) { text, fontSizeSp ->
    text.length * fontSizeSp * 0.62f * fontScale.coerceAtLeast(1f)
}

internal fun performanceOverlayDimensionsDp(
    mode: PerformanceOverlayMode,
    availableWidthDp: Float,
    availableHeightDp: Float,
    fontScale: Float,
    minimalContentWidthDp: Float? = null,
    contentWidthDp: Float? = null,
): PerformanceOverlayDimensionsDp {
    val safeWidth = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeHeight = availableHeightDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeFontScale = fontScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val requestedWidth = when (mode) {
            PerformanceOverlayMode.MINIMAL -> minimalContentWidthDp
                ?.takeIf { it.isFinite() && it > 0f }
                ?: estimatedPerformanceOverlayMinimalWidthDp(safeFontScale)
            PerformanceOverlayMode.CLASSIC,
            PerformanceOverlayMode.DETAILED,
            -> contentWidthDp
                ?.takeIf { it.isFinite() && it > 0f }
                ?: estimatedPerformanceOverlayContentWidthDp(mode, safeFontScale)
        }
    val renderable = requestedWidth <= safeWidth
    val width = if (renderable) {
        requestedWidth
    } else {
        min(PerformanceOverlayUnavailableWidthDp * safeFontScale, safeWidth)
    }
    val baseHeight = when {
        !renderable -> 30f
        mode == PerformanceOverlayMode.MINIMAL -> 30f
        else -> 32f
    }
    return PerformanceOverlayDimensionsDp(
        width = width,
        height = min(baseHeight * safeFontScale, safeHeight),
        renderable = renderable,
    )
}

internal fun clampPerformanceOverlayPoint(
    point: PerformanceOverlayPoint,
    bounds: PerformanceOverlayDragBounds,
): PerformanceOverlayPoint = PerformanceOverlayPoint(
    x = point.x.coerceIn(bounds.minX, max(bounds.minX, bounds.maxX)),
    y = point.y.coerceIn(bounds.minY, max(bounds.minY, bounds.maxY)),
)

internal fun performanceOverlayPlacement(
    point: PerformanceOverlayPoint,
    bounds: PerformanceOverlayDragBounds,
): PerformanceOverlayPlacement {
    val clamped = clampPerformanceOverlayPoint(point, bounds)
    val horizontalRange = (bounds.maxX - bounds.minX).coerceAtLeast(0)
    val verticalRange = (bounds.maxY - bounds.minY).coerceAtLeast(0)
    return PerformanceOverlayPlacement(
        horizontalFraction = if (horizontalRange == 0) 0f else {
            (clamped.x - bounds.minX).toFloat() / horizontalRange.toFloat()
        },
        verticalFraction = if (verticalRange == 0) 0f else {
            (clamped.y - bounds.minY).toFloat() / verticalRange.toFloat()
        },
    )
}

internal fun performanceOverlayPoint(
    placement: PerformanceOverlayPlacement,
    bounds: PerformanceOverlayDragBounds,
): PerformanceOverlayPoint {
    val sanitized = placement.sanitized()
    val horizontalRange = (bounds.maxX - bounds.minX).coerceAtLeast(0)
    val verticalRange = (bounds.maxY - bounds.minY).coerceAtLeast(0)
    return PerformanceOverlayPoint(
        x = bounds.minX + (horizontalRange * sanitized.horizontalFraction).roundToInt(),
        y = bounds.minY + (verticalRange * sanitized.verticalFraction).roundToInt(),
    )
}

internal fun performanceOverlayRefreshPoint(
    currentPoint: PerformanceOverlayPoint,
    persistedPlacement: PerformanceOverlayPlacement?,
    bounds: PerformanceOverlayDragBounds,
    dragging: Boolean,
): PerformanceOverlayPoint = if (dragging) {
    clampPerformanceOverlayPoint(currentPoint, bounds)
} else {
    persistedPlacement?.let { performanceOverlayPoint(it, bounds) }
        ?: clampPerformanceOverlayPoint(currentPoint, bounds)
}

internal fun performanceOverlayDragBounds(
    viewport: PerformanceOverlayViewportPx,
    overlayWidth: Int,
    overlayHeight: Int,
    edgeMarginDp: Float = 0f,
): PerformanceOverlayDragBounds {
    val density = viewport.density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val edgeMargin = (edgeMarginDp.coerceAtLeast(0f) * density).roundToInt()
    val minX = viewport.left + viewport.leftInset.coerceAtLeast(0) + edgeMargin
    val minY = viewport.top + viewport.topInset.coerceAtLeast(0) + edgeMargin
    val safeRight = max(minX, viewport.right - viewport.rightInset.coerceAtLeast(0) - edgeMargin)
    val safeBottom = max(minY, viewport.bottom - viewport.bottomInset.coerceAtLeast(0) - edgeMargin)
    return PerformanceOverlayDragBounds(
        minX = minX,
        maxX = max(minX, safeRight - overlayWidth.coerceAtLeast(1)),
        minY = minY,
        maxY = max(minY, safeBottom - overlayHeight.coerceAtLeast(1)),
    )
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
private fun performanceOverlaySafeInsets(windowInsets: WindowInsets): android.graphics.Insets {
    return windowInsets.getInsetsIgnoringVisibility(
        WindowInsets.Type.displayCutout(),
    )
}

/** Small primary-display telemetry window. Only its measured plate accepts drag; outside touch passes through. */
internal class PerformanceOverlayPresenter(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var host: OverlayHost? = null
    private var overlayContext: Context? = null
    private var view: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var detachRunnable: Runnable? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    private var mode by mutableStateOf(PerformanceOverlaySettingsStore.mode(appContext))
    private var modeContentFits by mutableStateOf(true)
    private var fpsHistory by mutableStateOf<List<Int?>>(emptyList())
    private var lastHistorySampleAtMs: Long? = null
    private var presentation by mutableStateOf<PerformanceOverlayPresentation>(
        PerformanceOverlayPresentation.Warming,
    )
    private var visible by mutableStateOf(false)

    /**
     * Whether a [show] has been asked for and not yet answered by a [hide]. [visible] and [view] are
     * Compose/main-thread state and cannot be consulted from the sampling coroutine, so this is the
     * off-main fast path that lets a repeated [hide] cost nothing.
     *
     * RC-44's second finding: nothing on the hide path cancels sampling, so in GAMES_ONLY with no
     * dispatched game the service called [hide] on every telemetry beat, forever. Each of those posted a
     * Runnable onto the main looper to reach a body whose first act was `if (view == null) return`. The
     * work was already nil; the post and the main-thread wake were not.
     *
     * Written on the MAIN-thread path only, and read as a guard on the off-main path, so it means "a hide
     * has been applied" rather than "a hide has been queued". **The main-thread path deliberately does not
     * consult it** and hides unconditionally — that is what keeps this guard incapable of cancelling a hide
     * that is genuinely needed, and it is the property to preserve if this is ever touched again. Adding a
     * `showRequested` check to the main path would turn the off-main clear into a self-cancel: a posted
     * hide would arrive, find the flag already false, and return before detaching — a card that never
     * hides, and a bug invisible to JUnit, which has no Looper and takes the main-thread branch throughout.
     */
    @Volatile
    private var showRequested = false

    /** Set as the first step of [destroy]; once true, [show] refuses to (re)mount on either thread. */
    @Volatile
    private var destroyed = false

    fun show(next: PerformanceOverlayPresentation) {
        if (destroyed) return
        showRequested = true
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(next) }
            return
        }
        if (destroyed) return
        detachRunnable?.let(main::removeCallbacks)
        detachRunnable = null
        ensureMounted()
        if (next is PerformanceOverlayPresentation.Active) {
            val sampleAt = next.telemetry.sampledAtElapsedRealtimeMs
            if (sampleAt == null || sampleAt != lastHistorySampleAtMs) {
                fpsHistory = appendPerformanceOverlayFpsHistory(
                    history = fpsHistory,
                    sample = performanceOverlayFpsValue(next.environment),
                )
                lastHistorySampleAtMs = sampleAt
            }
        } else if (next is PerformanceOverlayPresentation.Paused && fpsHistory.lastOrNull() != null) {
            fpsHistory = appendPerformanceOverlayFpsHistory(fpsHistory, null)
        }
        presentation = next
        refreshWindowGeometry()
        visible = true
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!showRequested) return
            main.post(::hide)
            return
        }
        showRequested = false
        if (view == null) return
        visible = false
        fpsHistory = emptyList()
        lastHistorySampleAtMs = null
        detachRunnable?.let(main::removeCallbacks)
        val duration = if (android.animation.ValueAnimator.areAnimatorsEnabled()) MimirMotion.OverlayMs else 0
        val pending = Runnable(::detachNow)
        detachRunnable = pending
        main.postDelayed(pending, duration.toLong())
    }

    /**
     * The card was rebound from one game to another: forget the frame-rate trace and blank the frame-rate
     * cell to the placeholder dash, without touching the window. The previous game's samples and its number
     * must not read as the new game's while the new game's first, possibly blocking, read is in flight; the
     * next [show] puts the real value back. Nothing shown, nothing to forget.
     */
    fun rebind() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!showRequested) return
            main.post(::rebind)
            return
        }
        fpsHistory = emptyList()
        lastHistorySampleAtMs = null
        presentation = performanceOverlayReboundPresentation(presentation)
    }

    fun destroy() {
        destroyed = true
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::destroy)
            return
        }
        showRequested = false
        visible = false
        main.removeCallbacksAndMessages(null)
        detachNow()
    }

    @VisibleForTesting
    internal var mountAttempts: Int = 0

    private fun ensureMounted() {
        if (view != null) return
        mountAttempts++
        val owner = OverlayHost().also(OverlayHost::create)
        host = owner
        // A WindowContext is the authoritative visual context for a non-activity overlay. Its
        // WindowMetrics track the live display and rotation; application-context metrics can lag.
        val display = appContext
            .getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?: return
        val mountedContext = runCatching {
            appContext
                .createDisplayContext(display)
                .createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null,
                )
        }.getOrNull() ?: return
        overlayContext = mountedContext
        val wm = mountedContext.getSystemService(WindowManager::class.java)
        windowManager = wm
        val composeView = ComposeView(mountedContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val themeId by ThemeStore.themeId.collectAsState()
                val storedMode by PerformanceOverlaySettingsStore.selectedMode.collectAsState()
                val current = presentation
                val currentVisible = visible
                val reducedMotion = mimirReducedMotion()
                LaunchedEffect(storedMode) {
                    if (mode != storedMode) {
                        mode = storedMode
                        refreshWindowGeometry()
                    }
                }
                MimirTheme(themeId = themeId) {
                    AnimatedVisibility(
                        visible = currentVisible,
                        enter = fadeIn(tween(if (reducedMotion) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
                        exit = fadeOut(tween(if (reducedMotion) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
                    ) {
                        PerformanceOverlayCard(
                            presentation = current,
                            mode = mode,
                            modeContentFits = modeContentFits,
                            fpsHistory = fpsHistory,
                            onTap = ::cycleMode,
                            onDragStart = ::beginDrag,
                            onDrag = ::dragBy,
                            onDragEnd = ::settleDrag,
                        )
                    }
                }
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val sizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
                if (sizeChanged && !dragging) restorePlacement()
            }
        }
        view = composeView
        val params = layoutParams(wm)
        windowLayoutParams = params
        wm.addView(composeView, params)
        composeView.post(::restorePlacement)
    }

    private fun layoutParams(wm: WindowManager): WindowManager.LayoutParams {
        val viewport = runtimeViewport(wm)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val (overlayWidth, overlayHeight) = performanceOverlaySizePx(wm)
        val dragBounds = performanceOverlayDragBounds(
            viewport = viewport,
            overlayWidth = overlayWidth,
            overlayHeight = overlayHeight,
            edgeMarginDp = PerformanceOverlayEdgeMarginDp,
        )
        return WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // The presenter owns placement against live display bounds. Letting WindowManager also
            // fit system bars silently applies the same inset a second time and blocks the true edges.
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = dragBounds.minX
            y = dragBounds.minY
            title = "Mimir performance overlay"
        }
    }

    private fun cycleMode() {
        PerformanceOverlaySettingsStore.setMode(appContext, mode.next())
    }

    private fun refreshWindowGeometry() {
        val mounted = view ?: return
        val wm = windowManager ?: return
        val params = windowLayoutParams ?: return
        val (width, height) = performanceOverlaySizePx(wm)
        val bounds = dragBounds(wm, width, height)
        val currentPoint = PerformanceOverlayPoint(params.x, params.y)
        val nextPoint = performanceOverlayRefreshPoint(
            currentPoint = currentPoint,
            persistedPlacement = PerformanceOverlaySettingsStore.placement(appContext),
            bounds = bounds,
            dragging = dragging,
        )
        if (params.width == width && params.height == height &&
            params.x == nextPoint.x && params.y == nextPoint.y
        ) {
            return
        }
        params.width = width
        params.height = height
        params.x = nextPoint.x
        params.y = nextPoint.y
        wm.updateViewLayout(mounted, params)
    }

    private fun performanceOverlaySizePx(wm: WindowManager): Pair<Int, Int> {
        val viewport = runtimeViewport(wm)
        val density = viewport.density
        val fontScale = (overlayContext ?: appContext).resources.configuration.fontScale.coerceAtLeast(1f)
        val edgeMarginPx = (PerformanceOverlayEdgeMarginDp * density).roundToInt()
        val availableWidth = (
            viewport.right - viewport.left - viewport.leftInset - viewport.rightInset - edgeMarginPx * 2
            ).coerceAtLeast(1)
        val availableHeight = (
            viewport.bottom - viewport.top - viewport.topInset - viewport.bottomInset - edgeMarginPx * 2
            ).coerceAtLeast(1)
        // This Paint measures the strings that Compose then RENDERS (see the MimirTheme block in
        // mount()). The two must hold the same typeface or the window is sized for one font and filled
        // with another — the product face is wider than the platform's, so a stale `sans-serif-medium`
        // here would size a window that clips its own readout. Falls back to the platform face only if
        // the resource cannot be resolved; a slightly mis-measured overlay beats a dead one.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = MimirFonts.sansMediumTypeface(overlayContext ?: appContext)
                ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val scaledDensity = density * fontScale
        val statusLabel = when (presentation) {
            PerformanceOverlayPresentation.Warming -> "TELEMETRY WARMING"
            PerformanceOverlayPresentation.Paused -> "TELEMETRY PAUSED"
            is PerformanceOverlayPresentation.Active -> null
        }
        if (statusLabel != null) {
            val requestedWidthDp = performanceOverlayStatusWidthDp(statusLabel, fontScale) { text, fontSizeSp ->
                paint.textSize = fontSizeSp * scaledDensity
                paint.measureText(text) / density
            }
            modeContentFits = requestedWidthDp <= availableWidth / density
            val widthDp = min(requestedWidthDp, availableWidth / density)
            val heightDp = min(30f * fontScale, availableHeight / density)
            return (widthDp * density).roundToInt().coerceAtLeast(1) to
                (heightDp * density).roundToInt().coerceAtLeast(1)
        }
        val measuredContentWidthDp = performanceOverlayContentWidthDp(mode, fontScale) { text, fontSizeSp ->
            paint.textSize = fontSizeSp * scaledDensity
            paint.measureText(text) / density
        }
        val dimensions = performanceOverlayDimensionsDp(
            mode = mode,
            availableWidthDp = availableWidth / density,
            availableHeightDp = availableHeight / density,
            fontScale = fontScale,
            minimalContentWidthDp = measuredContentWidthDp.takeIf { mode == PerformanceOverlayMode.MINIMAL },
            contentWidthDp = measuredContentWidthDp.takeUnless { mode == PerformanceOverlayMode.MINIMAL },
        )
        modeContentFits = dimensions.renderable
        return (dimensions.width * density).roundToInt().coerceAtLeast(1) to
            (dimensions.height * density).roundToInt().coerceAtLeast(1)
    }

    private fun beginDrag() {
        val params = windowLayoutParams ?: return
        dragging = true
        dragX = params.x.toFloat()
        dragY = params.y.toFloat()
    }

    private fun dragBy(delta: Offset) {
        val mounted = view ?: return
        val wm = windowManager ?: return
        val params = windowLayoutParams ?: return
        dragX += delta.x
        dragY += delta.y
        val next = clampPerformanceOverlayPoint(
            point = PerformanceOverlayPoint(dragX.roundToInt(), dragY.roundToInt()),
            bounds = dragBounds(wm, mounted),
        )
        if (params.x == next.x && params.y == next.y) return
        params.x = next.x
        params.y = next.y
        wm.updateViewLayout(mounted, params)
    }

    private fun settleDrag() {
        val mounted = view ?: return
        val wm = windowManager ?: return
        val params = windowLayoutParams ?: return
        PerformanceOverlaySettingsStore.setPlacement(
            context = appContext,
            placement = performanceOverlayPlacement(
                point = PerformanceOverlayPoint(params.x, params.y),
                bounds = dragBounds(wm, mounted),
            ),
        )
        dragging = false
    }

    private fun restorePlacement() {
        val placement = PerformanceOverlaySettingsStore.placement(appContext) ?: return
        val mounted = view ?: return
        val wm = windowManager ?: return
        val params = windowLayoutParams ?: return
        val restored = performanceOverlayPoint(placement, dragBounds(wm, mounted))
        if (params.x == restored.x && params.y == restored.y) return
        params.x = restored.x
        params.y = restored.y
        wm.updateViewLayout(mounted, params)
    }

    private fun dragBounds(
        wm: WindowManager,
        mounted: ComposeView,
    ): PerformanceOverlayDragBounds = dragBounds(
        wm = wm,
        overlayWidth = windowLayoutParams?.width?.takeIf { it > 0 } ?: mounted.width,
        overlayHeight = windowLayoutParams?.height?.takeIf { it > 0 } ?: mounted.height,
    )

    private fun dragBounds(
        wm: WindowManager,
        overlayWidth: Int,
        overlayHeight: Int,
    ): PerformanceOverlayDragBounds = performanceOverlayDragBounds(
        viewport = runtimeViewport(wm),
        overlayWidth = overlayWidth,
        overlayHeight = overlayHeight,
        edgeMarginDp = PerformanceOverlayEdgeMarginDp,
    )

    private fun runtimeViewport(wm: WindowManager): PerformanceOverlayViewportPx {
        val fallback = (overlayContext ?: appContext).resources.displayMetrics
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return PerformanceOverlayViewportPx(
                left = 0,
                top = 0,
                right = fallback.widthPixels,
                bottom = fallback.heightPixels,
                density = fallback.density,
            )
        }
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = performanceOverlaySafeInsets(metrics.windowInsets)
        val runtimeDensity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            metrics.density
        } else {
            fallback.density
        }
        return performanceOverlayLocalViewport(
            sourceLeft = bounds.left,
            sourceTop = bounds.top,
            sourceRight = bounds.right,
            sourceBottom = bounds.bottom,
            leftInset = insets.left,
            topInset = insets.top,
            rightInset = insets.right,
            bottomInset = insets.bottom,
            density = runtimeDensity,
        )
    }

    private fun detachNow() {
        detachRunnable?.let(main::removeCallbacks)
        detachRunnable = null
        val mounted = view ?: return
        runCatching { windowManager?.removeViewImmediate(mounted) }
        mounted.disposeComposition()
        view = null
        overlayContext = null
        windowManager = null
        windowLayoutParams = null
        dragging = false
        host?.destroy()
        host = null
    }

    private companion object {
        // WindowInsets already define the safe display. An extra margin prevented exact corner placement.
        const val PerformanceOverlayEdgeMarginDp = 0f
    }

    private class OverlayHost : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun create() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

@Composable
private fun PerformanceOverlayCard(
    presentation: PerformanceOverlayPresentation,
    mode: PerformanceOverlayMode,
    modeContentFits: Boolean,
    fpsHistory: List<Int?>,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    val description = when (presentation) {
        PerformanceOverlayPresentation.Warming -> "Performance overlay. Telemetry warming."
        PerformanceOverlayPresentation.Paused -> "Performance overlay. Telemetry paused."
        is PerformanceOverlayPresentation.Active ->
            performanceOverlayDescription(presentation.telemetry, presentation.environment, mode)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .performanceOverlayPlateMaterial(palette, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // R6-P1-05 follow-up: this window has no FLAG_NOT_TOUCHABLE, so a real touch on the
                    // plate must wake a dimmed screen, the same as every other touchable overlay window --
                    // via the same process-level bridge the Tabs drawer uses. Unconditional on the down
                    // itself, so it never depends on the arbiter's own tap-vs-drag outcome below.
                    CompanionProcessRuntime.services(context.applicationContext).overlay
                        .notifyScreenActivity(OverlayScreenActivity.DIRECT)
                    var totalTravel = Offset.Zero
                    val arbiter = PerformanceOverlayGestureArbiter(viewConfiguration.touchSlop)
                    var dragSettled = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1) {
                                arbiter.cancel()
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                arbiter.cancel()
                                break
                            }
                            val delta = change.position - change.previousPosition
                            totalTravel += delta
                            if (arbiter.update(totalTravel.getDistance(), pointerCount = 1)) {
                                onDragStart()
                                onDrag(totalTravel)
                                change.consume()
                            } else if (arbiter.dragging && delta != Offset.Zero) {
                                onDrag(delta)
                                change.consume()
                            }
                            if (!change.pressed) break
                        }
                        when (arbiter.finish()) {
                            PerformanceOverlayGestureOutcome.TAP -> onTap()
                            PerformanceOverlayGestureOutcome.DRAG -> {
                                dragSettled = true
                                onDragEnd()
                            }
                            PerformanceOverlayGestureOutcome.NONE -> Unit
                        }
                    } finally {
                        if (!dragSettled && arbiter.finish() == PerformanceOverlayGestureOutcome.DRAG) {
                            onDragEnd()
                        }
                    }
                }
            }
            .padding(horizontal = PerformanceOverlayTerminalPaddingDp.dp, vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "$description Tap to change telemetry detail. Drag to reposition."
            },
        contentAlignment = Alignment.Center,
    ) {
        when (presentation) {
            PerformanceOverlayPresentation.Warming -> PerformanceOverlayStatus("TELEMETRY WARMING")
            PerformanceOverlayPresentation.Paused -> PerformanceOverlayStatus("TELEMETRY PAUSED")
            is PerformanceOverlayPresentation.Active -> {
                if (!modeContentFits) {
                    PerformanceOverlayStatus("MODE UNAVAILABLE")
                } else {
                    PerformanceOverlayContent(
                        mode = mode,
                        presentation = presentation,
                        fpsHistory = fpsHistory,
                    )
                }
            }
        }
    }
}

/**
 * The plate's glass: clipped, the control gradient at the overlay's opacity, the sheen border and the top highlight.
 * Shared by the live card and onboarding's picture of it ([PerformanceOverlayPicturePlate]), so the two cannot drift.
 */
private fun Modifier.performanceOverlayPlateMaterial(palette: MimirThemePalette, shape: Shape): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                palette.materials.controlTop.copy(
                    alpha = palette.materials.controlTop.alpha * PerformanceOverlayOpacityScale,
                ),
                palette.materials.controlBottom.copy(
                    alpha = palette.materials.controlBottom.alpha * PerformanceOverlayOpacityScale,
                ),
            ),
        ),
    )
    .border(1.dp, palette.materials.glassSheen.copy(alpha = 0.61f), shape)
    .drawBehind {
        drawLine(
            color = palette.materials.glassSheen.copy(
                alpha = palette.materials.glassSheen.alpha * PerformanceOverlayOpacityScale,
            ),
            start = Offset(size.width * 0.08f, 1.dp.toPx()),
            end = Offset(size.width * 0.92f, 1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
    }

/**
 * Onboarding's picture of this plate, on the Mimir Tweaker page. It is the Extended plate's own glass, rune, FPS cell, graph and profile mark, fed
 * sample readings: a 60 FPS game with one dip, on a tuned profile. The diagnostics cells between the FPS cell and the
 * graph are left out, so the plate is about 160 dp and keeps its own type size in a picture 114 to 235 dp wide; where
 * even that is too wide it scales down whole rather than wrapping. It reads no telemetry, takes no touch and says
 * nothing to TalkBack (the page's words carry it). [reveal] runs 0 to 1 and is read in the draw phase only: the graph
 * draws in from the left, so the picture never recomposes while it plays.
 */
@Composable
internal fun PerformanceOverlayPicturePlate(reveal: () -> Float, modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    Row(
        modifier = modifier
            .performanceOverlayScaleToFit()
            .performanceOverlayPlateMaterial(palette, RoundedCornerShape(MimirSpacing.radiusCard))
            .padding(horizontal = PerformanceOverlayTerminalPaddingDp.dp, vertical = 4.dp)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(PerformanceDetailedOuterGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PerformanceRune()
        PerformanceMetric(
            label = PerformanceOverlayCell.FPS.label,
            value = PerformanceOverlayPictureFpsHistory.last().toString(),
            maximumValue = performanceOverlayMetricMaximum(PerformanceOverlayMode.DETAILED, PerformanceOverlayCell.FPS),
        )
        PerformanceSeparator()
        PerformanceFpsGraph(
            samples = PerformanceOverlayPictureFpsHistory,
            refreshRateHz = PerformanceOverlayPictureRefreshHz,
            primaryForeground = true,
            modifier = Modifier
                .width(PerformanceDetailedGraphWidthDp.dp)
                .height(20.dp)
                .drawWithContent {
                    clipRect(right = size.width * reveal().coerceIn(0f, 1f)) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
        PerformanceProfileMark(PerformanceOverlayPictureProfileId)
    }
}

/** Measures at the natural width and, if the room is narrower, scales the whole plate down about its centre. */
private fun Modifier.performanceOverlayScaleToFit(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    val scale = if (constraints.hasBoundedWidth && placeable.width > constraints.maxWidth) {
        constraints.maxWidth / placeable.width.toFloat()
    } else {
        1f
    }
    val width = (placeable.width * scale).roundToInt()
    val height = (placeable.height * scale).roundToInt()
    layout(width, height) {
        placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
            scaleX = scale
            scaleY = scale
        }
    }
}

/** A 60 FPS game with one dip and its recovery: the picture's thirty samples, oldest first. */
internal val PerformanceOverlayPictureFpsHistory: List<Int?> = listOf(
    60, 60, 59, 60, 60, 60, 58, 54, 48, 44, 47, 53, 57, 59, 60,
    60, 60, 59, 60, 60, 60, 60, 58, 60, 60, 60, 59, 60, 60, 60,
)
private const val PerformanceOverlayPictureRefreshHz = 60f

/** A tuned profile, so the mark lights as it does once the Tweaker is tuning. */
internal const val PerformanceOverlayPictureProfileId = "balanced"

@Composable
private fun PerformanceOverlayContent(
    mode: PerformanceOverlayMode,
    presentation: PerformanceOverlayPresentation.Active,
    fpsHistory: List<Int?>,
) {
    val telemetry = presentation.telemetry
    val environment = presentation.environment
    Row(
        horizontalArrangement = Arrangement.spacedBy(performanceOverlayOuterGapDp(mode).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PerformanceRune()
        // The cell list is the canonical sequence filtered by this mode, so what the plate renders
        // cannot disagree with it — and one separator between every adjacent pair, in every mode,
        // so no run of cells reads as a single fused blob while its neighbours read as cells.
        performanceOverlayCells(mode).forEachIndexed { index, cell ->
            if (index > 0) PerformanceSeparator()
            PerformanceMetric(
                label = cell.label,
                value = performanceOverlayCellValue(cell, mode, telemetry, environment),
                maximumValue = performanceOverlayMetricMaximum(mode, cell),
            )
        }
        if (performanceOverlayDrawsGraph(mode)) {
            PerformanceSeparator()
            PerformanceFpsGraph(
                samples = fpsHistory,
                refreshRateHz = environment.displayRefreshRateHz,
                primaryForeground = environment.mimirPrimaryForeground,
                modifier = Modifier
                    .width(PerformanceDetailedGraphWidthDp.dp)
                    .height(20.dp),
            )
        }
        PerformanceProfileMark(environment.selectedProfileId)
    }
}

/**
 * The FPS graph is an elaboration of the FPS cell rather than a reading of its own, so it sits
 * outside the canonical sequence and keeps the terminal seat before the profile mark.
 */
private fun performanceOverlayDrawsGraph(mode: PerformanceOverlayMode): Boolean =
    mode == PerformanceOverlayMode.DETAILED

private fun performanceOverlayOuterGapDp(mode: PerformanceOverlayMode): Float = when (mode) {
    PerformanceOverlayMode.MINIMAL -> PerformanceMinimalOuterGapDp
    PerformanceOverlayMode.CLASSIC -> PerformanceClassicOuterGapDp
    PerformanceOverlayMode.DETAILED -> PerformanceDetailedOuterGapDp
}

@Composable
private fun PerformanceFpsGraph(
    samples: List<Int?>,
    refreshRateHz: Float?,
    primaryForeground: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val stateLabel = performanceOverlayFrameStateLabel(primaryForeground, samples)
    val drawableSamples = performanceOverlayFrameHistoryForDrawing(primaryForeground, samples)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(
                color = palette.textPrimary.copy(alpha = 0.14f),
                start = Offset(0f, size.height - 1.dp.toPx()),
                end = Offset(size.width, size.height - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            if (drawableSamples.size < 2) return@Canvas
            val refreshCeiling = refreshRateHz
                ?.takeIf { it.isFinite() && it > 0f }
                ?.roundToInt()
            val observedCeiling = drawableSamples.filterNotNull().maxOrNull()
            val ceiling = max(refreshCeiling ?: 0, observedCeiling ?: 0).takeIf { it > 0 }
                ?: return@Canvas
            val denominator = ceiling.coerceAtLeast(1).toFloat()
            var path = Path()
            var segmentStarted = false
            drawableSamples.forEachIndexed { index, value ->
                if (value == null) {
                    if (segmentStarted) {
                        drawPath(
                            path = path,
                            color = palette.accentBright,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            ),
                        )
                    }
                    path = Path()
                    segmentStarted = false
                } else {
                    val x = performanceOverlayGraphX(index, drawableSamples.size, size.width)
                    val y = size.height * (1f - value.coerceIn(0, ceiling) / denominator)
                    if (segmentStarted) path.lineTo(x, y) else path.moveTo(x, y)
                    segmentStarted = true
                }
            }
            if (segmentStarted) {
                drawPath(
                    path = path,
                    color = palette.accentBright,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }
        stateLabel?.let { status ->
            Text(
                text = status,
                color = palette.textSecondary,
                fontSize = 5.sp,
                lineHeight = 6.sp,
                letterSpacing = 0.1.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun PerformanceRune() {
    Box(
        modifier = Modifier.width(PerformanceOverlayIdentityTerminalWidthDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "ᛗ",
            style = MimirTypeScale.RunicEyebrowCompact.copy(
                fontSize = 8.sp,
                lineHeight = 9.sp,
                letterSpacing = 0.2.sp,
            ),
            color = LocalMimirPalette.current.accentBright,
            maxLines = 1,
        )
    }
}

@Composable
private fun PerformanceProfileMark(profileId: String) {
    val normalized = profileId.trim().lowercase()
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier
            .width(PerformanceOverlayIdentityTerminalWidthDp.dp)
            .height(PerformanceOverlayIdentityTerminalWidthDp.dp)
            .clearAndSetSemantics {
                contentDescription = performanceOverlayProfileDescription(normalized)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = performanceOverlayProfileMark(normalized),
            style = MimirTypeScale.RunicEyebrowCompact.copy(
                fontSize = 8.sp,
                lineHeight = 9.sp,
                letterSpacing = 0.2.sp,
            ),
            color = if (normalized == TweakerBridge.STOCK_PROFILE) {
                palette.textMuted.copy(alpha = 0.72f)
            } else {
                palette.accentBright
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun PerformanceOverlayStatus(label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(PerformanceStatusGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PerformanceRune()
        Text(
            text = label,
            style = MimirTypeScale.MicroStatusCompact.copy(
                fontSize = 8.sp,
                lineHeight = 9.sp,
                letterSpacing = 0.25.sp,
            ),
            color = LocalMimirPalette.current.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun PerformanceMetric(label: String, value: String, maximumValue: String) {
    Box {
        PerformanceMetricRow(
            label = label,
            value = maximumValue,
            expandValue = false,
            modifier = Modifier
                .alpha(0f)
                .clearAndSetSemantics { },
        )
        PerformanceMetricRow(
            label = label,
            value = value,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun PerformanceMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    expandValue: Boolean = true,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PerformanceMetricInnerGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MimirTypeScale.MicroStatusCompact.copy(
                fontSize = 7.sp,
                lineHeight = 8.sp,
                letterSpacing = 0.2.sp,
            ),
            color = LocalMimirPalette.current.textMuted,
            maxLines = 1,
        )
        Box(
            modifier = if (expandValue) Modifier.weight(1f) else Modifier,
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = value,
                style = MimirTypeScale.MenuActionCompact.copy(
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    letterSpacing = 0.1.sp,
                ),
                color = if (value == "—") LocalMimirPalette.current.textMuted else LocalMimirPalette.current.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun PerformanceSeparator() {
    Box(
        Modifier
            .width(1.dp)
            .height(12.dp)
            .background(LocalMimirPalette.current.textPrimary.copy(alpha = 0.16f)),
    )
}

/**
 * The widest value a slot can ever hold, so the plate is measured once and values never reflow it.
 * Keyed by cell rather than by label text, which removes the per-mode ordered tables that used to be
 * a second, silently divergent statement of the render order.
 */
internal fun performanceOverlayMetricMaximum(
    mode: PerformanceOverlayMode,
    cell: PerformanceOverlayCell,
): String {
    if (cell !in performanceOverlayCells(mode)) error("No $mode telemetry slot for ${cell.label}")
    return when (cell) {
        PerformanceOverlayCell.FPS -> "1000"
        // CPU and GPU share one whole-device utilization scale, so their maxima stay identical.
        PerformanceOverlayCell.CPU ->
            if (mode == PerformanceOverlayMode.MINIMAL) "100°C" else "100°C·100%·4.0 GHz"
        PerformanceOverlayCell.GPU ->
            if (mode == PerformanceOverlayMode.MINIMAL) "100°C" else "100°C·100%·4.0 GHz"
        PerformanceOverlayCell.DRAW -> "99.9 W"
        PerformanceOverlayCell.BATT -> "100%"
        PerformanceOverlayCell.LEFT -> "72H 00M"
        PerformanceOverlayCell.RAM -> "99.9/99.9G"
    }
}

private const val PerformanceOverlayOpacityScale = 0.85f
internal const val PerformanceOverlayTerminalPaddingDp = 6f
internal const val PerformanceOverlayIdentityTerminalWidthDp = 14f
private const val PerformanceMinimalOuterGapDp = 4f
private const val PerformanceMinimalSeparatorWidthDp = 1f
private const val PerformanceMinimalHorizontalPaddingDp = PerformanceOverlayTerminalPaddingDp * 2f
private const val PerformanceMetricInnerGapDp = 2f
private const val PerformanceClassicOuterGapDp = 4f
private const val PerformanceDetailedOuterGapDp = 4f
private const val PerformanceDetailedGraphWidthDp = 72f
private const val PerformanceOverlayUnavailableWidthDp = 120f
private const val PerformanceStatusGapDp = 4f
