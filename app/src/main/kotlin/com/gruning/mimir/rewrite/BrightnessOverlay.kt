package com.gruning.mimir.rewrite


import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeiling
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import com.gruning.mimir.rewrite.core.tweaker.tweakerProfileDisplayCaps
import com.gruning.mimir.rewrite.core.ui.BottomUiTransitionProbe
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirHintSwap
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirFilledControlRingReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirHintToastRail
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.liquidGlassContentPalette
import com.gruning.mimir.rewrite.core.ui.mimirGlassPanelEntry
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.core.ui.warmShifted
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.gruning.mimir.rewrite.core.ui.holdMimirInputUntilShown
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSheetSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapExitSpec
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform

internal fun tweakerProfileSupportingText(
    availability: TweakerUtilityAvailability,
    pairState: TweakerPairState? = null,
    qualificationTestMode: Boolean = false,
    discovered: TweakerDiscoveredCeiling? = null,
    heldByUnfinishedRestore: Boolean = false,
    answerPending: Boolean = false,
): String = when {
    availability == TweakerUtilityAvailability.READY && heldByUnfinishedRestore ->
        "Profile changes are paused. Tweaker recovery has not finished."
    // The list is the daemon's last real answer and stays pressable; what is not claimed is which one is in force.
    availability == TweakerUtilityAvailability.READY && answerPending ->
        "Checking which profile is in force. You can still choose one."
    qualificationTestMode ->
        "Guardian-protected profile changes are verified before activation."
    // **Ahead of the pair states on purpose.** When the row is serving a discovered ceiling, what it
    // CONTROLS is the useful sentence; why the daemon is absent is a second-order fact the Tweaker route
    // row already carries, and it is appended here rather than replacing the row's own function. This is
    // where the install CTA goes — never into the value, which now names a real clock.
    availability == TweakerUtilityAvailability.DISCOVERED_CEILING -> buildString {
        val policy = discovered?.policyLabel ?: "CPU"
        // Read and write are separate facts, so the line says which half this device gives.
        append(
            if (discovered?.canWrite == true) {
                "Sets the $policy clock ceiling directly."
            } else {
                "Reading the $policy clock ceiling. Read only on this device."
            },
        )
        if (pairState != null && pairState != TweakerPairState.ELIGIBLE) {
            append(" Install the matching Mimir Tweaker for named profiles.")
        }
    }
    pairState == TweakerPairState.UNCHECKED ->
        "Checking Mimir Tweaker…"
    pairState == TweakerPairState.SIGNATURE_MISMATCH ||
        pairState == TweakerPairState.SERVICE_MISSING ||
        pairState == TweakerPairState.SERVICE_INCOMPATIBLE ->
        "Install the matching Mimir Tweaker release."
    pairState == TweakerPairState.COMPONENT_DISABLED ||
        pairState == TweakerPairState.CONTROL_PERMISSION_DENIED ->
        "Enable Mimir Tweaker access, then retry."
    pairState == TweakerPairState.PACKAGE_MANAGER_ERROR ->
        "Mimir couldn’t check Tweaker. Retry."
    availability == TweakerUtilityAvailability.NOT_INSTALLED ->
        "Mimir Tweaker is not installed on this device."
    availability == TweakerUtilityAvailability.UNAVAILABLE ->
        "Mimir Tweaker didn’t respond. Reopen it, then retry."
    availability == TweakerUtilityAvailability.RESPONDING_STOCK_ONLY ->
        "This Tweaker build supports Stock only. Install the matching production companion for profiles."
    else ->
        "Choose a performance profile."
}

/**
 * The profile row's own status line, shown BENEATH [tweakerProfileSupportingText] rather than
 * replacing it (both must read together, not one or the other).
 *
 * GPU temperature, not package/CPU: [fanControl]'s curve (both the AUTO and biased-MANUAL fan
 * selections resolve to `FanThermalStrategy.AutoCurve`) is driven by GPU temperature, and the
 * package sensor is retained only for the independent 84C full-fan safety intervention. Null
 * (unavailable or stale — see `TweakerBridge.freshUtilityFanGpuTempC`) omits the field rather
 * than showing a placeholder.
 */
internal fun tweakerProfileFanReadoutText(
    fanControl: TweakerFanControlReadout,
    gpuTempC: Int? = null,
): String =
    // `fanControl.strategy` (HOLD_TARGET/AUTO_CURVE) does not distinguish a true AUTO selection
    // from a biased MANUAL one -- both resolve to AUTO_CURVE; only an unexposed curve-bias value
    // tells them apart (see AdvancedFanControl.kt's strategy()). So the readout names no mode: the revised copy replaced "Automatic fan · N% · T°C", which
    // claimed AUTO for a biased MANUAL selection too, with a labelled speed and GPU temperature.
    // The Fan mode row is where the mode is stated.
    "Fan ${fanControl.dutyPercent}%" + (gpuTempC?.let { " · GPU $it°C" } ?: "")

/**
 * The row shows the drafted profile while a decision is pending, and committed truth otherwise.
 * Availability copy (INSTALL TWEAKER · UNAVAILABLE · STOCK ONLY) still comes from the row itself,
 * because a preview can only exist on a READY row in the first place.
 */
internal fun tweakerProfileValueLabel(
    row: TweakerUtilityRowState,
    previewProfileId: String?,
): String = previewProfileId?.let(::tweakerProfileDisplayCaps) ?: row.valueLabelCaps

/**
 * Names the target so CONFIRM can never be ambiguous about which profile it is about to run. The
 * profile token is the same capped one the value label and every toast on this surface already use;
 * core-tweaker-client exposes no natural-case profile name yet (OWED, carried from `3e84dc36`).
 */
internal fun tweakerProfileDraftSupportingText(previewProfileId: String): String =
    "Apply to switch profile to ${tweakerProfileDisplayCaps(previewProfileId)}."

/**
 * The drafted ceiling, said as what APPLY will do.
 *
 * The top of the scale is spoken as RELEASING rather than as a frequency, because that is what the press
 * performs: `MimirTunerWrite.withdraw` gives back what was captured, which on a device that booted under
 * a vendor or thermal cap is not the maximum. Naming a clock the device may not land on would be the
 * row promising a number it cannot deliver.
 */
internal fun tweakerCeilingDraftSupportingText(previewLabel: String, releasesHold: Boolean): String =
    if (releasesHold) {
        "Apply to release Mimir's ceiling and give the clock back."
    } else {
        "Apply to cap at $previewLabel."
    }

/** Stable keys mapped into the historical brightness FocusAuthority scope. */
enum class BrightnessControlKey {
    PRIMARY,
    SECONDARY,
    SCREEN_TIMEOUT,
    M_BUTTON_VISIBILITY,
    NAVIGATION_HINTS,
    CALM_SHINE,

    JOYSTICK_LIGHT,
    JOYSTICK_COLOR,
    THEME_AWARE_LIGHT,
    MIMIR_HAPTIC,
    NAV_SOUND,
    AMBIENT_SOUND,
    MEDIA_VOLUME,
    TWEAKER_PROFILE,
    TWEAKER_FPS_CAP,
    TWEAKER_FAN_MODE,
    TWEAKER_FAN_CURVE,
    PERFORMANCE_OVERLAY,
    OPEN_TWEAKER,
    OPEN_FILES,
    TWEAKER_USAGE_ACCESS,
    PRIVILEGED_ACCESS,
    ALLOW_SYSTEM_CHANGES,
    CLOSE,
}

internal enum class UtilitySurface {
    UTILITY,
    TWEAKER,
}

internal enum class UtilityRailReveal {
    START,
    END,
    ROW,
    NONE,
}

internal data class UtilityRailItemGeometry(
    val topPx: Int,
    val heightPx: Int,
)

/**
 * One visual settlement target for Utility's sole scroll owner.
 *
 * Middle controls settle around the viewport centre so every vertical step visibly carries the panel.
 * The first and final rail controls retain the full header/footer edge reveal.
 */
internal fun utilityRailSettlementTarget(
    reveal: UtilityRailReveal,
    item: UtilityRailItemGeometry?,
    viewportHeightPx: Int,
    maxScrollPx: Int,
): Int? = when (reveal) {
    UtilityRailReveal.START -> 0
    UtilityRailReveal.END -> maxScrollPx.coerceAtLeast(0)
    UtilityRailReveal.ROW -> item?.takeIf { viewportHeightPx > 0 }?.let {
        (it.topPx + it.heightPx / 2 - viewportHeightPx / 2)
            .coerceIn(0, maxScrollPx.coerceAtLeast(0))
    }
    UtilityRailReveal.NONE -> null
}

/**
 * At a terminal rail item reveal the complete beginning/end of the content, not merely the focused
 * row. Middle items still use BringIntoViewRequester so D-pad holds settle without scroll jumps.
 */
internal fun utilityRailReveal(
    focusedControl: BrightnessControlKey?,
    railControls: List<BrightnessControlKey>,
): UtilityRailReveal = when {
    focusedControl == null || focusedControl !in railControls -> UtilityRailReveal.NONE
    focusedControl == railControls.firstOrNull() -> UtilityRailReveal.START
    focusedControl == railControls.lastOrNull() -> UtilityRailReveal.END
    else -> UtilityRailReveal.ROW
}

/** Utility's footer line beside Allow: the only place Mimir asks for Android's "Modify system settings". */
internal const val MAIN_DISPLAY_ACCESS_MESSAGE =
    "Allow Android access for main screen changes."

/**
 * The same footer under auto-brightness, grant or no grant. The slider
 * writes the system brightness only in manual mode (`BrightnessController.updatePrimary`), so Allow is not offered
 * ([BrightnessCapabilities.mainWriteAccessUseful] is false) and this line is the instruction. The onboarding page it
 * came from showed it above the grant, so a player who already holds the grant still reads it here too.
 */
internal const val UtilityAutoBrightnessMessage = "Turn off auto-brightness so Mimir's slider works in games."

/**
 * The Tweaker row's instruction, moved from onboarding's TWEAKER_USAGE page by OR-12. Android lists Mimir and Mimir
 * Tweaker side by side in one Usage access list, and picking the wrong one grants the wrong app.
 */
internal const val TweakerUsageAccessInstruction = "In Android's list, pick Mimir Tweaker, not Mimir."
// MimirChromeText renders these in full caps and speaks them as written, so the authored form is
// sentence case: a pre-uppercased literal looks identical and only changes what TalkBack shouts.
internal const val UtilityHeader = "ᛗ · Utility"
internal const val TweakerHeader = "ᛗ · Tweaker"

/**
 * Fan mode's own display contract. The row used to print `TweakerFanMode.name` straight from the
 * core-tweaker enum, which reads correctly today only by coincidence — an enum constant is an
 * identity, not copy, so renaming one would silently change what the rail says. Every other value on
 * these surfaces resolves through a display label; this one now does too.
 */
internal fun tweakerFanModeValueLabel(mode: TweakerFanMode): String = when (mode) {
    TweakerFanMode.AUTO -> "AUTO"
    TweakerFanMode.MANUAL -> "MANUAL"
}

/**
 * A Mimir-duty-owned contract without qualified MANUAL (raw PWM) entry can advertise
 * `fan.modes == {AUTO}` only (see `MimirTweakerService.advancedCapabilitiesV6`'s
 * `fanControlSectionAvailable`/`dutyOwnedAvailable` shape). Stepping the row must never offer a
 * mode the service didn't advertise -- showing a step that no-ops is exactly the fake mode change
 * this row exists to avoid, even though `AppBrightnessCoordinator.stepTweakerFanMode` already
 * no-ops it safely at the mutation layer.
 */
internal fun tweakerFanModeCanStepLeft(fan: TweakerManualFanCapability): Boolean =
    fan.currentMode != TweakerFanMode.AUTO && TweakerFanMode.AUTO in fan.modes

internal fun tweakerFanModeCanStepRight(fan: TweakerManualFanCapability): Boolean =
    fan.currentMode != TweakerFanMode.MANUAL && TweakerFanMode.MANUAL in fan.modes

internal fun tweakerFanCurveLabel(curveBias: Int): String = when {
    curveBias < 0 -> "Cooler · ${(-curveBias / 10).coerceIn(1, 10)}"
    curveBias > 0 -> "Quieter · ${(curveBias / 10).coerceIn(1, 10)}"
    else -> "Standard"
}

internal fun tweakerFanCurveSupportingText(curveBias: Int): String = when {
    curveBias < 0 -> "Fan speed rises earlier. Lower temperatures, more fan noise."
    curveBias > 0 -> "Fan speed rises later. Less fan noise, higher temperatures."
    else -> "Mimir automatically balances temperature and fan noise for this profile."
}

/**
 * Live device state for the Fan response row. GPU leads because it is the axis this row's curve is drawn against (§2.1); CPU follows because
 * GPU alone can read cool while the chassis feels hot in the player's hand. Never a preview, never a
 * bias-implied target (§2.2) — `tweakerFanBiasDegrees` stays dead and is never wired in here.
 *
 * Whole degrees, no decimals, no sign, no padding; `—` for a missing/stale sensor, never a placeholder
 * number. One formatter for both the visual line and [tweakerFanTemperatureSpokenText] so the two can
 * never name different quantities.
 */
internal fun tweakerFanTemperatureReadoutText(gpuTempC: Int?, cpuTempC: Int?): String {
    val gpu = gpuTempC?.let { "$it°C" } ?: "—"
    val cpu = cpuTempC?.let { "$it°C" } ?: "—"
    return "GPU $gpu · CPU $cpu"
}

/**
 * TalkBack form of [tweakerFanTemperatureReadoutText] — spells out "degrees" so the em dash in the
 * visual line is never read aloud as "dash", and never as a bare "unavailable" glyph, matching
 * [performanceOverlaySpokenValue]'s treatment of the same '—' glyph elsewhere in this app. Appended to
 * the curve's own `stateDescription` (§5); the visible line itself carries `clearAndSetSemantics { }`
 * so TalkBack never meets both forms of the same sentence.
 */
internal fun tweakerFanTemperatureSpokenText(gpuTempC: Int?, cpuTempC: Int?): String {
    val gpu = gpuTempC?.let { "GPU $it degrees." } ?: "GPU temperature unavailable."
    val cpu = cpuTempC?.let { "CPU $it degrees." } ?: "CPU temperature unavailable."
    return if (gpuTempC == null && cpuTempC == null) "Temperature unavailable." else "$gpu $cpu"
}

internal fun tweakerFanBiasDegrees(curveBias: Int): String {
    val tenths = (abs(curveBias).coerceIn(0, 100) * 8) / 10
    return "${tenths / 10}.${tenths % 10}"
}

internal fun tweakerFanCurveAccessibilityLabel(curveBias: Int): String = when {
    curveBias < 0 -> "Cooler, level ${(-curveBias / 10).coerceIn(1, 10)} of 10"
    curveBias > 0 -> "Quieter, level ${(curveBias / 10).coerceIn(1, 10)} of 10"
    else -> "Standard"
}

internal fun tweakerFanCurveBiasFromFraction(
    fraction: Float,
    range: IntRange = -100..100,
    step: Int = 10,
): Int {
    val safeStep = step.coerceAtLeast(1)
    val span = (range.last - range.first).coerceAtLeast(0)
    val raw = range.first + fraction.coerceIn(0f, 1f) * span
    return (range.first + ((raw - range.first) / safeStep).roundToInt() * safeStep)
        .coerceIn(range)
}

internal fun tweakerFanCurveFraction(curveBias: Int, range: IntRange): Float {
    val span = range.last - range.first
    if (span <= 0) return 0f
    return ((curveBias.coerceIn(range) - range.first) / span.toFloat()).coerceIn(0f, 1f)
}

internal data class TweakerSettlementPrompt(
    val titleCaps: String,
    val supportingCaps: String,
    val canCancel: Boolean = false,
)

/** Product-facing pending copy. Presence is also the single visual input-shield condition. */
internal fun tweakerSettlementPrompt(
    profileChange: TweakerProfileChangeState?,
    fanControlChange: TweakerFanControlChangeState?,
): TweakerSettlementPrompt? = profileChange?.let { change ->
    when (change.phase) {
        TweakerProfileChangePhase.APPLYING -> TweakerSettlementPrompt(
            titleCaps = "APPLYING ${tweakerProfileDisplayCaps(change.requestedProfileId)}…",
            supportingCaps = "PLEASE WAIT",
            canCancel = true,
        )
        TweakerProfileChangePhase.CANCELLING -> TweakerSettlementPrompt(
            titleCaps = "RESTORING ${tweakerProfileDisplayCaps(change.priorProfileId)}…",
            supportingCaps = "PLEASE WAIT",
        )
    }
} ?: fanControlChange?.let { change ->
    val title = when {
        change.requestedMode == TweakerFanMode.AUTO -> "APPLYING AUTO FAN…"
        change.priorMode == TweakerFanMode.AUTO -> "APPLYING MANUAL FAN…"
        change.requestedCurveBias != change.priorCurveBias -> "APPLYING FAN RESPONSE…"
        else -> "APPLYING MANUAL FAN…"
    }
    TweakerSettlementPrompt(titleCaps = title, supportingCaps = "PLEASE WAIT")
}

internal fun mButtonVisibilitySupportingText(policy: MButtonVisibilityPolicy): String = when (policy) {
    MButtonVisibilityPolicy.DISABLED -> "The Mimir button is off."
    MButtonVisibilityPolicy.ALWAYS_VISIBLE -> "The Mimir button stays visible."
    else -> "The Mimir button hides after inactivity. Touch the screen to bring it back."
}

internal fun mButtonVisibilityStateDescription(policy: MButtonVisibilityPolicy): String = when (policy) {
    MButtonVisibilityPolicy.DISABLED -> "Mimir button off"
    MButtonVisibilityPolicy.ALWAYS_VISIBLE -> "Mimir button always visible"
    else -> "Mimir button hides after ${policy.displayLabel}"
}

/** Names the actual GAMES_ONLY/ALWAYS scope plainly; never implies platform-wide game detection. */
internal fun performanceOverlaySupportingText(visibility: PerformanceOverlayVisibility): String = when (visibility) {
    PerformanceOverlayVisibility.OFF -> "Off."
    PerformanceOverlayVisibility.GAMES_ONLY -> "Shows over games you open from Mimir."
    PerformanceOverlayVisibility.ALWAYS -> "Always on, over Mimir and every app."
}

/**
 * Utility's Calm shine row: a switch for a player the focus shine is too much
 * for. Off is the default and today's shine, untouched. On, a focused game gleams once and nothing else shines.
 */
internal fun calmShineValueLabel(enabled: Boolean): String = if (enabled) "On" else "Off"

/** What a screen reader says for the row: its name with its state, as the other Utility switches do. */
internal fun calmShineStateDescription(enabled: Boolean): String =
    if (enabled) "Reduce shimmer effects on" else "Reduce shimmer effects off"

/**
 * Stateless Utility modal. It never creates a second focus authority or uses platform focus traversal.
 * The parent keeps the established Brightness route/key for safe Home and Library focus restoration.
 */
@Composable
internal fun MimirBrightnessOverlay(
    state: BrightnessState,
    focusedControl: BrightnessControlKey?,
    onSetFraction: (BrightnessTarget, Float) -> Unit,
    onSetThemeAwareJoystickLight: (Boolean) -> Unit,
    onRequestWriteAccess: () -> Unit,
    onRequestPrivilegedAccess: () -> Unit,
    onOpenTweaker: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    /** Must match `brightnessControls`' own parameter, including its default. See its comment. */
    filesRouteAvailable: Boolean = true,
    allFilesAccessGranted: Boolean = false,
    tweakerFpsCapRow: TweakerFpsCapRow = TweakerFpsCapRow.NoSubject,
    onStepTweakerFpsCap: (Int) -> Unit = {},
    tweakerFpsCapDisplayName: String? = null,
    primaryMButtonControlsAvailable: Boolean = false,
    mButtonVisibilityPolicy: MButtonVisibilityPolicy = MButtonVisibilityPolicy.DISABLED,
    onStepMButtonVisibilityPolicy: (Int) -> Unit = {},
    navigationHintVisibility: NavigationHintVisibility = NavigationHintVisibility.ON,
    onStepNavigationHintVisibility: (Int) -> Unit = {},
    /** Utility's Calm shine switch: off is today's shine, on tones it down . */
    calmShine: Boolean = false,
    onSetCalmShine: (Boolean) -> Unit = {},
    tweakerRow: TweakerUtilityRowState = DefaultTweakerUtilityRow,
    tweakerFanGpuTempC: Int? = null,
    tweakerFanCpuTempC: Int? = null,
    tweakerProfileChange: TweakerProfileChangeState? = null,
    tweakerFanControlChange: TweakerFanControlChangeState? = null,
    onStepTweakerProfile: (Int) -> Boolean = { false },
    tweakerProfilePreviewId: String? = null,
    tweakerProfileDraftPending: Boolean = false,
    /** The drafted discovered CEILING, already spelled; null unless that path is serving this row. */
    tweakerCeilingPreviewLabel: String? = null,
    tweakerCeilingDraftPending: Boolean = false,
    tweakerCeilingPreviewReleasesHold: Boolean = false,
    onApplyTweakerProfile: () -> Unit = {},
    onCancelTweakerProfile: () -> Unit = {},
    tweakerManualFan: TweakerManualFanCapability? = null,
    onStepTweakerFanMode: (Int) -> Boolean = { false },
    tweakerFanCurvePreviewBias: Int? = null,
    tweakerFanCurveDraftPending: Boolean = false,
    onPreviewTweakerFanCurve: (Int) -> Unit = {},
    onApplyTweakerFanCurve: () -> Unit = {},
    onCancelTweakerFanCurve: () -> Unit = {},
    performanceOverlayVisibility: PerformanceOverlayVisibility = PerformanceOverlayVisibility.OFF,
    performanceOverlayMode: PerformanceOverlayMode = PerformanceOverlayMode.CLASSIC,
    performanceOverlayRowValue: PerformanceOverlayRowValue = PerformanceOverlayRowValue.UNAVAILABLE,
    onStepPerformanceOverlay: (Int) -> Unit = {},
    tweakerUsageAccessState: TweakerUsageAccessState = TweakerUsageAccessState.UNAVAILABLE,
    onRequestTweakerUsageAccess: () -> Unit = {},
    surface: UtilitySurface = UtilitySurface.UTILITY,
    tweakerMessage: MimirMessage? = null,
    onTweakerMessageDelivered: (Long) -> Unit = {},
    onBack: () -> Unit,
    onClose: () -> Unit,
    onCancelTweakerMutation: () -> Unit = onBack,
    backActionLabel: String = "Close",
    controllerHintsAvailable: Boolean = true,
    enableBackHandler: Boolean = true,
    /** Back from the file browser: the page returned to, at rest from its first frame. */
    settledOnMount: Boolean = false,
    /** False for the Bottom's instruments host: the pad gate is process-wide, so a Bottom panel that held it would mute Main's controller for the lead. Main keeps true. */
    holdPad: Boolean = true,
    modifier: Modifier = Modifier,
    controlModifier: @Composable (BrightnessControlKey) -> Modifier = { Modifier },
    onRequestFocus: (BrightnessControlKey) -> Unit = {},
) {
    // Utility content is hosted by the authored dark glass volume. Light app themes therefore use
    // their on-glass ink ramp here while the surrounding scene and scrim keep the original palette.
    val palette = LocalMimirPalette.current.liquidGlassContentPalette()
    val tweakerSettlement = if (surface == UtilitySurface.TWEAKER) {
        tweakerSettlementPrompt(tweakerProfileChange, tweakerFanControlChange)
    } else {
        null
    }
    val tweakerMutationPending = tweakerSettlement != null
    val tweakerDecisionPending = tweakerProfileDraftPending ||
        tweakerFanCurveDraftPending
    if (enableBackHandler) BackHandler(onBack = onBack)
    var pendingPointerFocus by remember { mutableStateOf<BrightnessControlKey?>(null) }
    val requestPointerFocus: (BrightnessControlKey) -> Unit = { control ->
        if (surface == UtilitySurface.TWEAKER) pendingPointerFocus = control
        onRequestFocus(control)
    }

    // Blur, scrim, panel alpha and panel lift advance
    // together on ONE curve. The scrim is the half that is easy to forget, because it is spelled as a
    // static `.background(...)` at every glass overlay in this tree and therefore snaps to full opacity on
    // the frame the panel mounts -- a hard cut sitting underneath a ramp, which reads as the transition
    // being broken rather than as the scrim being wrong.
    //
    // Hoisted above the scrim so BOTH it and the panel below read the SAME progress. One Animatable, one
    // clock: a second `rememberMimirGlassProgress` here would be two clocks on one surface, and they drift.
    //
    // This also matters most where it is least visible: on API 30 the platform cannot blur at all, so the
    // scene-side ramp is a no-op and THIS is the whole transition.
    //
    // drawBehind, not background(alpha): the alpha is read in the draw block, so a ramping scrim costs
    // draw invalidation instead of recomposing this overlay every frame.
    // Back from the file browser, Utility is the page returned to. It used to re-arrive from scratch
    // (the scrim's ramp, then the panel's 130 ms lead and 540 ms arrival, presses held until it showed) while the
    // browser was already gone at 180 ms: a near-empty screen after B. Now it is at rest from its first frame and the
    // browser dissolves over it on the return clock. Read once: a cold open always arrives.
    val settledOnReturn = remember { settledOnMount }
    val panelEntry: () -> Float = if (settledOnReturn) {
        { 1f }
    } else {
        rememberMimirGlassProgress(visible = true)
    }
    //  Utility opens on the SWAP tier, like Settings: ONE fade on
    // MimirMotion.OverlayMs. The scrim, the panel's alpha and its lift all ride panelEntry's single clock from the same frame
    // beat. Batch F  had put the panel on the page clock, 540 ms from a 130 ms lead, under the 180 ms scrim:
    // on a dark scene the region under the panel dimmed and then rose (device: luminance 19.9, then 27.7) and an open took
    // 337-484 ms against Settings' 125-131. The page tier is for pages; a menu panel is a swap.
    //
    // The pad still waits for the first paint of a COLD open: the hold the page clock's primitive took for the panel is
    // taken here, on the one ramp. A host on the Bottom passes holdPad = false, because the gate is process-wide.
    val panelArrival: () -> Float = panelEntry
    if (holdPad && !settledOnReturn) {
        LaunchedEffect(Unit) { holdMimirInputUntilShown(panelEntry) { panelEntry() > 0f } }
    }
    val scrimColor = mimirGlassScrimColor()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // No catch-all pointer handler on this box. The full-size backdrop below is already the touch target that keeps
            // the scene beneath from being touched (a pointer-input sibling is not shared with the ones under it), and a
            // handler that consumed every event after its children cancelled every drifting finger tap on the panel's
            // controls and the backdrop (detectTapGestures checks the Final pass of each move).
            .drawBehind { drawRect(color = scrimColor, alpha = panelEntry().coerceIn(0f, 1f)) },
        contentAlignment = Alignment.Center,
    ) {
        val panelWidth = (maxWidth - 32.dp).coerceAtMost(560.dp).coerceAtLeast(0.dp)
        val panelMaxHeight = (maxHeight - 24.dp).coerceAtLeast(180.dp)
        val tweakerPanelHeight = (maxHeight - 48.dp).coerceAtLeast(180.dp)
        // Utility owns one explicit visual-settlement scroll. FocusAuthority publishes the destination;
        // measured row geometry then carries the panel so controller movement is visible, not merely a
        // logical focus update.
        // A tool panel always starts at its first option, so this rail keeps NO position: plain
        // `remember` keyed on surface, deliberately not rememberSaveable.
        //
        // Two ways an offset used to survive and drag the panel to its end. Across the surface flip:
        // open() changes `surface` with isOpen still true, so the overlay never leaves composition and
        // an unkeyed state carried the Utility offset into the shorter Tweaker rail, where it clamped
        // to maxValue -- Profile above the fold, the Display row's footer fragments at the top, Fan
        // control and Telemetry complete below, which is the 2026-08-07 corrections §5 report. And
        // across a close/reopen: rememberSaveable restores through the saved-state registry, so once
        // the Tweaker rail had been scrolled down, every later open was restored to that offset.
        // `remember` is discarded when the panel closes, which is exactly the wanted behaviour.
        // Keep one ScrollState per rendered surface. AnimatedContent may retain the previous panel while
        // the live surface acquires a new rail, so the outgoing layer must use its last live object rather
        // than a snapshot initialized from the new rail's offset.
        val railScrollBySurface = remember { mutableMapOf<UtilitySurface, ScrollState>() }
        val railScroll = remember(surface) { ScrollState(0) }
        railScrollBySurface[surface] = railScroll
        // The Tweaker profile and overlay rows are stable on their dedicated surface. Setup exists only while
        // Android explicitly reports the helper package denied, so READY/unknown states never add an inert
        // focus stop.
        // Every input this list is derived from has to be both a remember key AND an argument. Two of
        // them were neither: the frame cap row vanished from reveal only -- drawn, reachable by NAV, and
        // never scrolled into view -- and availability changes left a stale list behind. The rail
        // contract test cannot see this; it calls both functions with the same inputs, and the defect is
        // that this call site did not.
        val railControls = remember(
            state,
            primaryMButtonControlsAvailable,
            tweakerUsageAccessState,
            tweakerManualFan,
            surface,
            tweakerRow.availability,
            tweakerFpsCapRow,
            filesRouteAvailable,
        ) {
            brightnessRailControls(
                state,
                primaryMButtonControlsAvailable,
                tweakerUsageAccessState,
                surface,
                tweakerManualFan,
                tweakerRow.availability,
                tweakerFpsCapRow,
                filesRouteAvailable,
            )
        }
        val railScrollReducedMotion = mimirReducedMotion()
        var railViewportTopPx by remember { mutableIntStateOf(0) }
        var railViewportHeightPx by remember { mutableIntStateOf(0) }
        val railItemGeometry = remember { mutableStateMapOf<BrightnessControlKey, UtilityRailItemGeometry>() }
        val focusedRailGeometry = focusedControl?.let(railItemGeometry::get)
        var railSettledOnce by remember(surface) { mutableStateOf(false) }
        LaunchedEffect(
            focusedControl,
            focusedRailGeometry,
            railViewportHeightPx,
            railControls,
            railScroll.maxValue,
            surface,
        ) {
            // Touch must never recenter a visible row underneath the player's finger. Consume exactly
            // the focus request initiated by that pointer. Ownership remains until controller focus
            // changes, so clearing it cannot restart this effect and recenter the touched row.
            if (surface == UtilitySurface.TWEAKER && pendingPointerFocus == focusedControl) {
                return@LaunchedEffect
            }
            if (surface == UtilitySurface.TWEAKER) pendingPointerFocus = null
            utilityRailSettlementTarget(
                reveal = utilityRailReveal(focusedControl, railControls),
                item = focusedRailGeometry,
                viewportHeightPx = railViewportHeightPx,
                maxScrollPx = railScroll.maxValue,
            )?.let { target ->
                if (railSettledOnce) {
                    //  the rail follows the focus on the focus clock and the house easing, never Compose's default
                    // spring (a separate clock beside the ring); reduced motion jumps, like every other motion here.
                    if (railScrollReducedMotion) {
                        railScroll.scrollTo(target)
                    } else {
                        railScroll.animateScrollTo(target, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
                    }
                } else {
                    railScroll.scrollTo(target)
                    railSettledOnce = true
                }
            }
        }
        //  This surface was the review's worst case:
        // THREE glass usages and ZERO animation constructs, so the heaviest material in the product
        // hard-cut in. The panel's alpha and its 0.96 -> 1.0 lift ride the same MimirMotion.OverlayMs
        // curve as the scene blur wired at MimirAppHost and the scrim (`panelArrival` is `panelEntry`: Utility is on the swap tier).
        //
        // `visible = true` is not a placeholder: AppBrightnessHost is only in composition while the panel
        // is open (its own comment says so), so "mounted" and "open" are the same instant here. That only
        // animates because rememberMimirGlassProgress now starts its Animatable at 0 -- with the previous
        // `if (visible) 1f else 0f` initialiser this call would have been born settled and played nothing.
        //
        // There is deliberately no exit ramp. Closing pops this host, and the return transition is the
        // SCENE un-blurring behind it -- the parent arriving. Animating the panel away would mean keeping
        // a popped scope composed and taking pointer input, which is the rule that forbids it.
        //
        // `panelEntry` is the one declared above the scrim, deliberately shared: scrim and panel are one
        // event on one clock.
        // Keep the touch-only secondary host dismissible without involving the foreign app's Back
        // dispatcher. The card consumes its own taps; this sibling receives only the outside backdrop.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onBack,
                ),
        )
        // The swap tier (motion audit batch A): the old surface out, then the new one in from the 130 ms lead, the
        // resize on the arrival's clock. They used to fade at once: two full glass panels at half alpha each. Back to
        // Utility is a return: Utility at rest at once, the Tweaker dissolving over it on the return clock.
        val swapReducedMotion = mimirReducedMotion()
        val swapGlidePx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.roundToPx() }
        // The host's own call, from its target and outside the pages: Utility is what a move back returns to. The spec
        // below and the pages' probe read this one value, so the page being left picks up the return clock in the same
        // composition that retargets it (mimirSwapFadeAlpha).
        val swapReturning = surface == UtilitySurface.UTILITY
        // The panels differ in height (the Tweaker is fixed, Utility wraps its rail), and this container defaulted to
        // TopStart inside the centred host: the size snapped on Back and the dissolving Tweaker jumped by half the
        // difference, and on open it drifted as it faded in. Centred like its host, neither panel moves.
        AnimatedContent(
            targetState = surface,
            transitionSpec = {
                // Utility glides back into place on the return clock rather than simply being there.
                mimirSwapTransform(
                    swapReducedMotion,
                    returning = swapReturning,
                    returnTravelPx = swapGlidePx,
                )
            },
            contentAlignment = Alignment.Center,
            label = "brightness-surface-transition",
        ) { presentedSurface ->
            // AnimatedContent retains the outgoing surface while the incoming one fades in. It must
            // not retain its FocusAuthority registrations or callbacks: both surfaces share keys and
            // the old layer would otherwise steal focus or mutate state during the handoff.
            val isLive = presentedSurface == surface
            // Receipt: Utility <-> Tweaker inside the open panel is this swap. Silent on Main (no Bottom display provided).
            if (isLive) BottomUiTransitionProbe("instruments.swap", transition)
            // The departing panel showed the arriving one's footer label ("Back" on Utility, "Close" on the
            // Tweaker) and its ring cut to wherever the new panel's focus went. Each layer keeps what it showed
            // while it was live, so it dissolves exactly as it was. Plain memory, written only while live.
            val layerMemory = remember { UtilityLayerMemory(focusedControl, backActionLabel) }
            if (isLive) {
                layerMemory.focusedControl = focusedControl
                layerMemory.backActionLabel = backActionLabel
            }
            val layerFocusedControl = if (isLive) focusedControl else layerMemory.focusedControl
            val layerBackActionLabel = if (isLive) backActionLabel else layerMemory.backActionLabel
            // Keep the outgoing rail's scroll bounds private while the incoming surface settles. The
            // two surfaces have different content heights, so sharing ScrollState lets the old layer
            // overwrite the live max/value during AnimatedContent's overlap.
            val presentedRailScroll = if (isLive) {
                railScroll
            } else {
                railScrollBySurface[presentedSurface] ?: railScroll
            }
            // Presses during a page change (alpha audit, 2026-09-30): the Tweaker stands at alpha 0 through
            // the 130 ms lead while its CONFIRM is already live, and an A inside it applied a pending draft nobody had
            // seen. The transition fades the pages itself (fadeIn and fadeOut), so there is no alpha to read here: the
            // gate takes a probe on the same swap specs. The pad waits from this page's first frame until it paints, or a
            // reversal takes it off the target; Back and the shoulders pass. The same modifier keeps the departing copy
            // inert (semantics cleared, no touch: it was this block's own `outgoingInput`) and shields the arriving one
            // from touch until it shows.
            val pageInput = MimirSwapPageGate(mimirSwapFadeAlpha(swapReducedMotion, returning = swapReturning), holdPad = holdPad)
            val railControlModifier: @Composable (BrightnessControlKey) -> Modifier = if (isLive) {
                { key ->
                    controlModifier(key).onGloballyPositioned { coordinates ->
                        if (coordinates.size.height <= 0 || railViewportHeightPx <= 0) return@onGloballyPositioned
                        val visibleTop = coordinates.positionInWindow().y.roundToInt()
                        val measured = UtilityRailItemGeometry(
                            topPx = visibleTop - railViewportTopPx + presentedRailScroll.value,
                            heightPx = coordinates.size.height,
                        )
                        if (railItemGeometry[key] != measured) railItemGeometry[key] = measured
                    }
                }
            } else {
                { Modifier }
            }
            val footerControlModifier: @Composable (BrightnessControlKey) -> Modifier = { key ->
                if (isLive) controlModifier(key) else Modifier
            }
            MimirLiquidGlassPanel(
                modifier = Modifier
                    .width(panelWidth)
                    .mimirGlassPanelEntry(panelArrival)
                    .then(pageInput)
                    .pointerInput(Unit) {
                        // Keep panel bounds in this subtree so gaps cannot fall through to the backdrop; never consume.
                        awaitEachGesture {
                            awaitFirstDown()
                        }
                    }
                    .then(
                        if (presentedSurface == UtilitySurface.TWEAKER) {
                            Modifier.height(tweakerPanelHeight)
                        } else {
                            Modifier.heightIn(max = panelMaxHeight)
                        },
                    )
                    .then(
                        if (tweakerMutationPending) Modifier.clearAndSetSemantics { } else Modifier,
                    ),
                contentPadding = 0.dp,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
            // ── FIXED header ─────────────────────────────────────────────────────────────────────
            // Reduce the header-to-profile gap.
            // bottom trimmed 8.dp -> 4.dp; this is the last space before UtilityDivider, and combines
            // with the rail content's own top padding below (6.dp) to set the header-to-first-row gap
            // on BOTH surfaces -- identical on UTILITY and TWEAKER now that the TWEAKER hint/toast rail
            // no longer occupies a row of its own (it now lives in the FIXED footer -- see the footer
            // note below). top/start/end are untouched: they set the header's own inset from the panel
            // edge, not the header-to-content gap.
            Column(
                modifier = Modifier.padding(
                    PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                ),
            ) {
                MimirChromeText(
                    if (presentedSurface == UtilitySurface.TWEAKER) TweakerHeader else UtilityHeader,
                    style = MimirTypeScale.RunicEyebrow,
                    color = palette.accent,
                )
                if (presentedSurface == UtilitySurface.TWEAKER) {
                    // Chrome, so it goes through the caps renderer rather than being typed in caps:
                    // a plain Text here painted the caps and handed them to TalkBack as well.
                    MimirChromeText(
                        text = "Performance controls",
                        style = MimirTypeScale.ControlHintCompact,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            UtilityDivider(palette)

            // ── Bounded control rail: the ONLY scrolling region ──────────────────────────────────
            // Keep toast hints visible beside Close in the footer. Pass 3 put the TWEAKER hint/toast in an
            // OVERLAY here so it painted on top of the rail instead of reserving its own row -- that
            // closed the idle reserved-height gap but cost an active message painting over the top row
            // (including its focus ring) for the toast window. That overlap was reported on device; the message now lives in the FIXED footer,
            // beside Close (see the footer note below). This Box is back to a plain scroll viewport
            // with no overlay child; MimirHintToastRail and MimirRunicMessageCard remain untouched, so
            // MimirAppHost, HomeShelfScreen, and the other MimirRunicMessageCard callers
            // (CrossAppLaunchStatusPresenter, StorageNoticeHost) keep their existing reserved-band
            // behavior exactly as before.
            Box(
                Modifier
                    .weight(1f, fill = presentedSurface == UtilitySurface.TWEAKER)
                    .onGloballyPositioned { coordinates ->
                        if (isLive) {
                            railViewportTopPx = coordinates.positionInWindow().y.roundToInt()
                            railViewportHeightPx = coordinates.size.height
                        }
                    }
                    // The scroll viewport reaches UtilityRailRingBleed up behind the divider, so the first row sits UtilityRailFirstRowInset
                    // (6 dp) under it and its ring's reach is not clipped. After the report above (it keeps measuring the
                    // visible viewport); once scrolled the clip returns to the divider, so no row draws under the header.
                    .utilityRailClipBleedWhenScrolled(presentedRailScroll)
                    .utilityRailBleedUp(UtilityRailRingBleed),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(presentedRailScroll)
                        // Focus-safe padding: a focused ring at either end still clears the edge
                        // rather than sitting flush against the fade. Top and bottom are intentionally
                        // asymmetric: the bottom edge keeps the full UtilityRailSafePaddingDp (12.dp)
                        // so the last row's focus ring still clears the bottom fade exactly as before;
                        // only the top inset -- which sits between the divider and the first row on
                        // BOTH surfaces now that the toast rail no longer reserves its own row on
                        // TWEAKER -- is trimmed, to 6.dp.
                        .padding(
                            PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                // Since the ring migration the row's treatment reaches MimirFocusTileRingReach past the
                                // row, and the rail clips on its axis: both ends clear that reach. The top's clearance is
                                // taken back by the viewport's UtilityRailRingBleed above, so the first row still sits
                                // UtilityRailFirstRowInset (6 dp) under the divider.
                                top = MimirFocusTileRingReach,
                                bottom = maxOf(UtilityRailSafePaddingDp.dp, MimirFocusTileRingReach),
                            ),
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
            // The two
            // ROUTES lead the rail, above every setting. They are the only rows here that leave Utility
            // for somewhere else; everything below them adjusts something in place. Both were previously
            // at the tail, so reaching Files meant scrolling past display, joystick, sound and fan rows.
            //
            // Their order here must stay identical to `brightnessControls`' order in AppBrightnessHost:
            // the rail reveals BY INDEX, so a row listed early and drawn late swallows the press that
            // should have reached it. That file's own comment says the same thing from the other side.
            //
            // The Tweaker route was reachable only from the physical M button before it existed, so a
            // player sitting in Utility had no way there and the whole panel read as missing. Pressing
            // it never takes device control silently -- the decision below is the same one the M button
            // and macros consult.
            if (presentedSurface == UtilitySurface.UTILITY && tweakerRoutePaired(tweakerRow.availability)) {
                val tweakerRoute = tweakerRouteDecision(
                    tweakerPaired = tweakerRoutePaired(tweakerRow.availability),
                )
                UtilityChoiceRow(
                    label = "Tweaker",
                    supportingText = tweakerRouteSupportingText(tweakerRoute),
                    valueLabel = tweakerRouteValueLabel(tweakerRoute),
                    stateDescription = tweakerRouteValueLabel(tweakerRoute),
                    focused = layerFocusedControl == BrightnessControlKey.OPEN_TWEAKER,
                    palette = palette,
                    modifier = railControlModifier(BrightnessControlKey.OPEN_TWEAKER),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.OPEN_TWEAKER) },
                    onStep = { onOpenTweaker() },
                    onActivate = onOpenTweaker,
                    canStepLeft = false,
                    canStepRight = false,
                    // An unreachable Tweaker still shows its row and says why, rather than vanishing
                    // and leaving the player to wonder where the feature went.
                    enabled = !tweakerDecisionPending && tweakerRoute != TweakerRouteDecision.UNAVAILABLE,
                    showStepAffordances = false,
                    compactSupporting = true,
                )
            }

            //  Files lives in Utility, and Utility is the ONLY door to
            // FileBrowserMode.MANAGE -- the half of the browser that can move and delete. The picker
            // reached from Settings stays PICK forever, so a player adding ROMs cannot delete anything.
            //
            // It KEEPS its own "Storage" section on the way up. This panel
            // also holds the Tweaker and diagnostics, so Files must read as a player tool
            // without diagnostic chrome -- a heading below the Tweaker route still satisfies
            // that, because a label annexes what comes AFTER it, and what comes after is Files alone.
            if (presentedSurface == UtilitySurface.UTILITY && filesRouteAvailable) {
                val filesRoute = filesRouteDecision(
                    hostsInAppSurfaces = filesRouteAvailable,
                    allFilesAccessGranted = allFilesAccessGranted,
                )
                UtilityDivider(palette)
                UtilitySectionLabel("Storage", palette)
                UtilityChoiceRow(
                    label = "Files",
                    supportingText = filesRouteSupportingText(filesRoute),
                    valueLabel = filesRouteValueLabel(filesRoute),
                    stateDescription = filesRouteValueLabel(filesRoute),
                    focused = layerFocusedControl == BrightnessControlKey.OPEN_FILES,
                    palette = palette,
                    modifier = railControlModifier(BrightnessControlKey.OPEN_FILES),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.OPEN_FILES) },
                    onStep = { onOpenFiles() },
                    onActivate = onOpenFiles,
                    canStepLeft = false,
                    canStepRight = false,
                    // Never disabled. Both reachable states do something real on press -- open, or ask
                    // Android for the access that makes opening possible -- and this row is the only
                    // regrant path there is, because the design's Permissions screen lives inside the
                    // manager the missing permission is blocking.
                    enabled = !tweakerDecisionPending,
                    showStepAffordances = false,
                    compactSupporting = true,
                )
            }

                    if (presentedSurface == UtilitySurface.UTILITY) {
                    // Guarded: with neither route drawn -- no Tweaker paired and no browser to open --
                    // this would otherwise be a divider with nothing above it, a rule the rail applies
                    // to every other section boundary.
                    if (tweakerRoutePaired(tweakerRow.availability) || filesRouteAvailable) {
                        UtilityDivider(palette)
                    }
                    UtilitySectionLabel("Screens", palette)
            UtilitySliderRow(
                label = if (state.capabilities.mainSystemBrightnessActive) {
                    "Main screen"
                } else {
                    "Main screen (Mimir only)"
                },
                valueLabel = percentLabel(state.primaryFraction),
                fraction = state.primaryFraction,
                target = BrightnessTarget.PRIMARY,
                enabled = true,
                focused = layerFocusedControl == BrightnessControlKey.PRIMARY,
                palette = palette,
                focusModifier = railControlModifier(BrightnessControlKey.PRIMARY),
                onRequestFocus = { requestPointerFocus(BrightnessControlKey.PRIMARY) },
                onValueChange = { onSetFraction(BrightnessTarget.PRIMARY, it) },
            )

            if (state.capabilities.showsSecondary) {
                UtilitySliderRow(
                    label = secondaryBrightnessLabel(state),
                    valueLabel = percentLabel(state.secondaryFraction ?: state.primaryFraction),
                    fraction = state.secondaryFraction ?: state.primaryFraction,
                    target = BrightnessTarget.SECONDARY,
                    enabled = state.capabilities.canAdjustSecondary,
                    focused = layerFocusedControl == BrightnessControlKey.SECONDARY,
                    palette = palette,
                    focusModifier = railControlModifier(BrightnessControlKey.SECONDARY),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.SECONDARY) },
                    onValueChange = { onSetFraction(BrightnessTarget.SECONDARY, it) },
                    deferredHardwareReadback =
                        state.capabilities.secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM,
                )
            }

            UtilitySliderRow(
                label = "Dim screens after",
                valueLabel = screenTimeoutLabel(state.screenTimeoutSeconds),
                fraction = BrightnessMath.screenTimeoutFraction(state.screenTimeoutSeconds),
                target = BrightnessTarget.SCREEN_TIMEOUT,
                enabled = true,
                focused = layerFocusedControl == BrightnessControlKey.SCREEN_TIMEOUT,
                palette = palette,
                focusModifier = railControlModifier(BrightnessControlKey.SCREEN_TIMEOUT),
                onRequestFocus = { requestPointerFocus(BrightnessControlKey.SCREEN_TIMEOUT) },
                onValueChange = { onSetFraction(BrightnessTarget.SCREEN_TIMEOUT, it) },
                steps = BrightnessMath.ScreenTimeoutSeconds.size - 2,
            )

            if (primaryMButtonControlsAvailable) {
                UtilityDivider(palette)
                UtilitySectionLabel("Mimir button", palette)
                UtilityChoiceRow(
                    label = "Mimir button visibility",
                    supportingText = mButtonVisibilitySupportingText(mButtonVisibilityPolicy),
                    valueLabel = mButtonVisibilityPolicy.displayLabel,
                    stateDescription = mButtonVisibilityStateDescription(mButtonVisibilityPolicy),
                    focused = layerFocusedControl == BrightnessControlKey.M_BUTTON_VISIBILITY,
                    palette = palette,
                    modifier = railControlModifier(BrightnessControlKey.M_BUTTON_VISIBILITY),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.M_BUTTON_VISIBILITY) },
                    onStep = onStepMButtonVisibilityPolicy,
                    canStepLeft = mButtonVisibilityPolicy.step(-1) != mButtonVisibilityPolicy,
                    canStepRight = mButtonVisibilityPolicy.step(1) != mButtonVisibilityPolicy,
                    // the Tweaker-only scoping below no longer holds -- the
                    // normalized SupportCompact/48.dp treatment now applies panel-wide, Utility surface
                    // included. See the UtilityChoiceRow definition's compactSupporting doc for the
                    // superseded scoping note.
                    compactSupporting = true,
                )
            }

            UtilityDivider(palette)
            UtilitySectionLabel("Navigation", palette)
            UtilityChoiceRow(
                label = "Navigation hints",
                supportingText = "Show the expandable controller legend in Library.",
                valueLabel = navigationHintVisibility.displayLabel,
                stateDescription = "Library controller legend ${navigationHintVisibility.displayLabel.lowercase()}",
                focused = layerFocusedControl == BrightnessControlKey.NAVIGATION_HINTS,
                palette = palette,
                modifier = railControlModifier(BrightnessControlKey.NAVIGATION_HINTS),
                onRequestFocus = { requestPointerFocus(BrightnessControlKey.NAVIGATION_HINTS) },
                onStep = onStepNavigationHintVisibility,
                canStepLeft = navigationHintVisibility.step(-1) != navigationHintVisibility,
                canStepRight = navigationHintVisibility.step(1) != navigationHintVisibility,
                compactSupporting = true,
            )

            // Calm shine keeps the current shine unless the player asks for less, with a plain-language title.
            // The same two-value row as Navigation hints, so a press, either arrow or a tap all reach it; its focus
            // key sits right after that row's in brightnessControls, because the rail reveals by index.
            UtilityDivider(palette)
            UtilitySectionLabel("Motion", palette)
            UtilityChoiceRow(
                label = "Reduce shimmer effects",
                supportingText = "Games sparkle once; icons and menus don’t shimmer.",
                valueLabel = calmShineValueLabel(calmShine),
                stateDescription = calmShineStateDescription(calmShine),
                focused = layerFocusedControl == BrightnessControlKey.CALM_SHINE,
                palette = palette,
                modifier = railControlModifier(BrightnessControlKey.CALM_SHINE),
                onRequestFocus = { requestPointerFocus(BrightnessControlKey.CALM_SHINE) },
                onStep = { direction -> onSetCalmShine(direction > 0) },
                canStepLeft = calmShine,
                canStepRight = !calmShine,
                onActivate = { onSetCalmShine(!calmShine) },
                compactSupporting = true,
            )

            if (
                state.capabilities.joystickLightSupported ||
                state.capabilities.joystickLightColorSupported ||
                state.themeAwareJoystickLightRequested ||
                state.themeAwareJoystickLightRestorePending
            ) {
                UtilityDivider(palette)
                UtilitySectionLabel("Lights", palette)
            }

            if (state.capabilities.joystickLightSupported) {
                UtilitySliderRow(
                    // "Joy-Con" is a Nintendo trademark and these are not Joy-Cons — they are this
                    // handheld's own joystick LEDs. The surrounding API
                    // (joystickLightSupported / JOYSTICK_LIGHT) was always correct; only the copy was wrong.
                    label = "Joystick light brightness",
                    valueLabel = percentLabel(state.joystickLightFraction ?: 0f),
                    fraction = state.joystickLightFraction ?: 0f,
                    target = BrightnessTarget.JOYSTICK_LIGHT,
                    enabled = state.capabilities.canAdjustJoystickLight,
                    focused = layerFocusedControl == BrightnessControlKey.JOYSTICK_LIGHT,
                    palette = palette,
                    focusModifier = railControlModifier(BrightnessControlKey.JOYSTICK_LIGHT),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.JOYSTICK_LIGHT) },
                    onValueChange = { onSetFraction(BrightnessTarget.JOYSTICK_LIGHT, it) },
                    deferredHardwareReadback = true,
                )
            }

            if (state.capabilities.joystickLightColorSupported) {
                val colorArgb = state.joystickColorArgb ?: 0xffffffff.toInt()
                UtilitySliderRow(
                    label = "Joystick light color",
                    valueLabel = joystickColorLabel(colorArgb, state.themeAwareJoystickLight),
                    fraction = state.joystickColorFraction ?: 0f,
                    target = BrightnessTarget.JOYSTICK_COLOR,
                    enabled = state.capabilities.canAdjustJoystickColor &&
                        !state.themeAwareJoystickLightRequested,
                    focused = layerFocusedControl == BrightnessControlKey.JOYSTICK_COLOR,
                    palette = palette,
                    focusModifier = railControlModifier(BrightnessControlKey.JOYSTICK_COLOR),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.JOYSTICK_COLOR) },
                    onValueChange = { onSetFraction(BrightnessTarget.JOYSTICK_COLOR, it) },
                    trackColors = JoystickColorCodec.presets.map(::Color),
                    thumbColor = Color(colorArgb),
                    steps = JoystickColorCodec.presets.size - 2,
                    deferredHardwareReadback = true,
                )
            }

            //  the "Follow Mimir theme" joystick-light toggle is
            // DISCARDED with the theme-follow feature — the manual joystick color slider above stays
            // the one LED control; Mimir never drives the stick lights from the theme again.

            privilegedUtilityMessage(state)?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    // The message stands the ring's whole reach from the action beside it (the earlier update review).
                    horizontalArrangement = Arrangement.spacedBy(UtilityActionRingReach),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MimirTypeScale.Support,
                        color = palette.textMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.capabilities.shouldOfferPrivilegedAccess) {
                        UtilityAction(
                            label = privilegedUtilityActionLabel(
                                state.capabilities.privilegedUtilityPosture,
                            ),
                            primary = false,
                            focused = layerFocusedControl == BrightnessControlKey.PRIVILEGED_ACCESS,
                            palette = palette,
                            modifier = railControlModifier(BrightnessControlKey.PRIVILEGED_ACCESS),
                            onClick = {
                                requestPointerFocus(BrightnessControlKey.PRIVILEGED_ACCESS)
                                onRequestPrivilegedAccess()
                            },
                        )
                    }
                }
            }

            if (state.capabilities.mimirHapticsSupported ||
                state.capabilities.navSoundSupported ||
                state.capabilities.ambientSoundSupported ||
                state.capabilities.mediaVolumeSupported
            ) {
                UtilityDivider(palette)
                // QA-19: the section holds "Mimir navigation haptics", "Mimir navigation sounds" and
                // "Ambient sound" -- two of its three rows are sound, and the header named only the
                // other one. A section header is a claim about scope like any other line of copy
                // (same rule the "Game art" header below was corrected under on 2026-08-25).
                UtilitySectionLabel("Feedback & Sound", palette)
            }

            if (state.capabilities.mimirHapticsSupported) {
                val haptic = state.mimirHapticFraction ?: 0f
                UtilitySliderRow(
                    label = "Mimir navigation haptics",
                    valueLabel = if (haptic <= 0f) "OFF" else if (state.capabilities.mimirHapticAmplitudeControl) {
                        percentLabel(haptic)
                    } else {
                        "ON"
                    },
                    fraction = haptic,
                    target = BrightnessTarget.MIMIR_HAPTIC,
                    enabled = true,
                    focused = layerFocusedControl == BrightnessControlKey.MIMIR_HAPTIC,
                    palette = palette,
                    focusModifier = railControlModifier(BrightnessControlKey.MIMIR_HAPTIC),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.MIMIR_HAPTIC) },
                    onValueChange = { onSetFraction(BrightnessTarget.MIMIR_HAPTIC, it) },
                    steps = if (state.capabilities.mimirHapticAmplitudeControl) 19 else 0,
                )
            }

            if (state.capabilities.navSoundSupported) {
                val nav = state.navSoundFraction ?: 0f
                UtilitySliderRow(
                    // Navigation-sound loudness (tick / back / page) relative to master UI volume.
                    label = "Mimir navigation sounds",
                    valueLabel = if (nav <= 0f) "OFF" else percentLabel(nav),
                    fraction = nav,
                    target = BrightnessTarget.NAV_SOUND,
                    enabled = true,
                    focused = layerFocusedControl == BrightnessControlKey.NAV_SOUND,
                    palette = palette,
                    focusModifier = railControlModifier(BrightnessControlKey.NAV_SOUND),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.NAV_SOUND) },
                    onValueChange = { onSetFraction(BrightnessTarget.NAV_SOUND, it) },
                    steps = 19,
                )
            }

            if (state.capabilities.ambientSoundSupported) {
                val ambient = state.ambientSoundFraction ?: 0f
                UtilitySliderRow(
                    // Settings › Music: the same value as the Music card's Volume
                    // dial (`ambientSoundVolume`), so it carries the same name. No second music volume exists.
                    label = "Music volume",
                    valueLabel = if (ambient <= 0f) "OFF" else percentLabel(ambient),
                    fraction = ambient,
                    target = BrightnessTarget.AMBIENT_SOUND,
                    enabled = true,
                    focused = layerFocusedControl == BrightnessControlKey.AMBIENT_SOUND,
                    palette = palette,
                    focusModifier = railControlModifier(BrightnessControlKey.AMBIENT_SOUND),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.AMBIENT_SOUND) },
                    onValueChange = { onSetFraction(BrightnessTarget.AMBIENT_SOUND, it) },
                    steps = 19,
                )
            }

                    }

            // The permanent PROFILE row is not gated on the Tweaker answering, because a row that
            // disappears with the service reads as Mimir forgetting the feature rather than as the service
            // being gone. The capped value is the whole truth: INSTALL TWEAKER · UNAVAILABLE · STOCK · a
            // certified profile. Chevrons only appear when a certified alternative genuinely exists.
            if (presentedSurface == UtilitySurface.TWEAKER) {
            UtilitySectionLabel("Profile", palette)
            TweakerProfileRow(
                row = tweakerRow,
                supportingText = tweakerProfileSupportingText(
                    availability = tweakerRow.availability,
                    pairState = TweakerBridge.pairState(),
                    qualificationTestMode = tweakerRow.qualificationTestMode,
                    discovered = tweakerRow.discovered,
                    heldByUnfinishedRestore = tweakerRow.heldByUnfinishedRestore,
                    answerPending = tweakerRow.answerPending,
                ),
                // The profile subtext above and this readout are shown together, not one replacing the
                // other  -- see TweakerProfileRow for where they compose.
                fanReadoutText = tweakerRow.fanControl?.let {
                    tweakerProfileFanReadoutText(it, tweakerFanGpuTempC)
                },
                previewProfileId = tweakerProfilePreviewId,
                previewCeilingLabel = tweakerCeilingPreviewLabel,
                previewCeilingReleases = tweakerCeilingPreviewReleasesHold,
                draftPending = tweakerProfileDraftPending || tweakerCeilingDraftPending,
                focused = layerFocusedControl == BrightnessControlKey.TWEAKER_PROFILE,
                palette = palette,
                modifier = railControlModifier(BrightnessControlKey.TWEAKER_PROFILE),
                onRequestFocus = { requestPointerFocus(BrightnessControlKey.TWEAKER_PROFILE) },
                onStep = { direction -> onStepTweakerProfile(direction) },
                onApply = onApplyTweakerProfile,
                onCancel = onCancelTweakerProfile,
                controllerHintsAvailable = controllerHintsAvailable,
                // Each row excludes its OWN draft from the gate; a pending profile decision must stay
                // steppable, exactly as Fan response stays steppable while it drafts.
                enabled = !tweakerMutationPending &&
                    !tweakerFanCurveDraftPending,
            )

            (tweakerFpsCapRow.takeIf { TweakerFrameCapGate.enabled } as? TweakerFpsCapRow.Available)?.let { capRow ->
                UtilityDivider(palette)
                UtilitySectionLabel("Frame cap", palette)
                UtilityChoiceRow(
                    label = "Frame cap",
                    supportingText = tweakerFpsCapSupportingText(capRow, tweakerFpsCapDisplayName),
                    valueLabel = tweakerFpsCapValueLabel(capRow),
                    stateDescription = tweakerFpsCapValueLabel(capRow),
                    focused = layerFocusedControl == BrightnessControlKey.TWEAKER_FPS_CAP,
                    palette = palette,
                    modifier = railControlModifier(BrightnessControlKey.TWEAKER_FPS_CAP),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.TWEAKER_FPS_CAP) },
                    onStep = onStepTweakerFpsCap,
                    onActivate = { onStepTweakerFpsCap(1) },
                    canStepLeft = capRow.selectedHz != null,
                    canStepRight = capRow.selectedHz != capRow.optionsHz.last(),
                    enabled = !tweakerDecisionPending,
                    showStepAffordances = !tweakerDecisionPending,
                    compactSupporting = true,
                )
            }

            tweakerManualFan?.let { fan ->
                UtilityDivider(palette)
                UtilitySectionLabel("Fan control", palette)
                UtilityChoiceRow(
                    label = "Fan mode",
                    // The sentence names who is driving the fan, and that is a live fact the daemon
                    // attests, not a property of AUTO. 2026-08-23 this said "the device's own fan
                    // controller"; the 2026-09-07 ownership ruling made Mimir's curve the ordinary
                    // case and that line a false claim on the Thor (frames 2026-10-13, fan_mode=6).
                    // See tweakerFanModeSupportingText for the three AUTO states and why.
                    supportingText = tweakerFanModeSupportingText(fan),
                    valueLabel = tweakerFanModeValueLabel(fan.currentMode),
                    stateDescription = tweakerFanModeStateDescription(fan),
                    focused = layerFocusedControl == BrightnessControlKey.TWEAKER_FAN_MODE,
                    palette = palette,
                    modifier = railControlModifier(BrightnessControlKey.TWEAKER_FAN_MODE),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.TWEAKER_FAN_MODE) },
                    onStep = { direction -> onStepTweakerFanMode(direction) },
                    canStepLeft = tweakerFanModeCanStepLeft(fan),
                    canStepRight = tweakerFanModeCanStepRight(fan),
                    onActivate = { onStepTweakerFanMode(1) },
                    enabled = !tweakerMutationPending && !tweakerDecisionPending,
                    showStepAffordances = !tweakerMutationPending && !tweakerDecisionPending,
                    compactSupporting = true,
                )
                if (fan.currentMode == TweakerFanMode.MANUAL) {
                    TweakerFanCurveRow(
                        authoritativeBias = fan.currentCurveBias,
                        previewBias = tweakerFanCurvePreviewBias ?: fan.currentCurveBias,
                        curveBiasRange = fan.curveBiasRange,
                        curveBiasStep = fan.curveBiasStep,
                        gpuTempC = tweakerFanGpuTempC,
                        cpuTempC = tweakerFanCpuTempC,
                        targetTempC = tweakerRow.fanControl?.targetTempC,
                        draftPending = tweakerFanCurveDraftPending,
                        focused = layerFocusedControl == BrightnessControlKey.TWEAKER_FAN_CURVE,
                        palette = palette,
                        modifier = railControlModifier(BrightnessControlKey.TWEAKER_FAN_CURVE),
                        onRequestFocus = { requestPointerFocus(BrightnessControlKey.TWEAKER_FAN_CURVE) },
                        onPreviewBias = onPreviewTweakerFanCurve,
                        onApply = onApplyTweakerFanCurve,
                        onCancel = onCancelTweakerFanCurve,
                        controllerHintsAvailable = controllerHintsAvailable,
                        enabled = !tweakerMutationPending &&
                            !tweakerProfileDraftPending,
                    )
                }
            } ?: run {
                // Keep the product concept visible without inventing mutation authority. The row is
                // deliberately outside the FocusAuthority ring until the helper returns a typed,
                // signed-session capability.
                UtilityDivider(palette)
                UtilitySectionLabel("Fan control", palette)
                UtilityChoiceRow(
                    label = "Fan mode",
                    supportingText = if (tweakerRow.canStep) {
                        "Select a profile to enable fan control."
                    } else {
                        "Stock only. No profile is available for fan control."
                    },
                    valueLabel = "UNAVAILABLE",
                    stateDescription = "Fan control unavailable.",
                    focused = false,
                    palette = palette,
                    modifier = Modifier,
                    onRequestFocus = {},
                    onStep = {},
                    canStepLeft = false,
                    canStepRight = false,
                    interactive = false,
                    compactSupporting = true,
                )
            }

            UtilityDivider(palette)
            // The feature is the performance overlay; the data it shows is telemetry. Every other
            // section here names a subsystem, and "OVERLAY" only repeated the row sitting inside it.
            UtilitySectionLabel("Telemetry", palette)
            UtilityChoiceRow(
                label = "Performance overlay",
                supportingText = performanceOverlaySupportingText(performanceOverlayVisibility),
                valueLabel = performanceOverlayControlLabel(
                    performanceOverlayVisibility,
                    performanceOverlayMode,
                    performanceOverlayRowValue,
                ),
                stateDescription = performanceOverlayControlLabel(
                    performanceOverlayVisibility,
                    performanceOverlayMode,
                    performanceOverlayRowValue,
                ),
                focused = layerFocusedControl == BrightnessControlKey.PERFORMANCE_OVERLAY,
                palette = palette,
                modifier = railControlModifier(BrightnessControlKey.PERFORMANCE_OVERLAY),
                onRequestFocus = { requestPointerFocus(BrightnessControlKey.PERFORMANCE_OVERLAY) },
                onStep = onStepPerformanceOverlay,
                onActivate = { onStepPerformanceOverlay(0) },
                canStepLeft = performanceOverlayVisibility != PerformanceOverlayVisibility.OFF,
                // Scope only. The view is cycled by touching the overlay, so the row must not offer a
                // step past its last scope as though there were more of this setting to reach.
                canStepRight = performanceOverlayVisibility != PerformanceOverlayVisibility.ALWAYS,
                enabled = !tweakerDecisionPending,
                showStepAffordances = !tweakerDecisionPending,
                compactSupporting = true,
            )

            if (tweakerUsageAccessState == TweakerUsageAccessState.SET_UP) {
                UtilityChoiceRow(
                    label = "Tweaker usage access",
                    supportingText =
                        "Lets Mimir Tweaker read system stats for the overlay." + " " + TweakerUsageAccessInstruction,
                    valueLabel = tweakerUsageAccessState.valueLabel,
                    stateDescription = tweakerUsageAccessState.valueLabel,
                    focused = layerFocusedControl == BrightnessControlKey.TWEAKER_USAGE_ACCESS,
                    palette = palette,
                    modifier = railControlModifier(BrightnessControlKey.TWEAKER_USAGE_ACCESS),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.TWEAKER_USAGE_ACCESS) },
                    onStep = { onRequestTweakerUsageAccess() },
                    onActivate = onRequestTweakerUsageAccess,
                    canStepLeft = false,
                    canStepRight = false,
                    enabled = !tweakerDecisionPending,
                    showStepAffordances = false,
                    compactSupporting = true,
                )
            }
            }

            if (presentedSurface == UtilitySurface.UTILITY && state.capabilities.mediaVolumeSupported) {
                val media = state.mediaVolumeFraction ?: 0f
                UtilitySliderRow(
                    label = "Device media volume",
                    valueLabel = if (state.capabilities.mediaVolumeFixed) "FIXED" else percentLabel(media),
                    fraction = media,
                    target = BrightnessTarget.MEDIA_VOLUME,
                    enabled = state.capabilities.canAdjustMediaVolume,
                    focused = layerFocusedControl == BrightnessControlKey.MEDIA_VOLUME,
                    palette = palette,
                    focusModifier = railControlModifier(BrightnessControlKey.MEDIA_VOLUME),
                    onRequestFocus = { requestPointerFocus(BrightnessControlKey.MEDIA_VOLUME) },
                    onValueChange = { onSetFraction(BrightnessTarget.MEDIA_VOLUME, it) },
                )
            }

                }
                // Continuation cue: a subtle fade at whichever edge still has content beyond it.
                UtilityRailEdgeFade(presentedRailScroll, palette)
            }

            // ── FIXED footer: Close can never scroll away or collide with a control ──────────────
            UtilityDivider(palette)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Bottom: the panel clips here (its contentPadding is 0), so the footer clears an action's ring
                    // and its flare (ring migration, 2026-09-28). Every action is an etched chip now , its ring on the plate
                    // inside a 48 dp hit box, so the plate already stands a few dp clear of the row's edge. The reserve stays the
                    // 23 dp it was (the filled reach plus the flare): more than the pill ring's 14 dp and its flare need, so nothing cuts.
                    .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = MimirFilledControlRingReach + MimirFocusFlareReach)),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (presentedSurface == UtilitySurface.UTILITY && state.capabilities.shouldOfferMainWriteAccess) {
                    Text(
                        text = MAIN_DISPLAY_ACCESS_MESSAGE,
                        style = MimirTypeScale.Support,
                        color = palette.textMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The hint stops a halo short of the chip it sits beside, so a focused Allow's ring never runs into its words (the earlier update review).
                    Spacer(Modifier.width(UtilityActionRingReach))
                    UtilityAction(
                        label = "Allow",
                        primary = true,
                        focused = layerFocusedControl == BrightnessControlKey.ALLOW_SYSTEM_CHANGES,
                        palette = palette,
                        modifier = footerControlModifier(BrightnessControlKey.ALLOW_SYSTEM_CHANGES),
                        onClick = {
                            requestPointerFocus(BrightnessControlKey.ALLOW_SYSTEM_CHANGES)
                            onRequestWriteAccess()
                        },
                    )
                    Spacer(Modifier.width(UtilityActionRingReach))
                } else if (presentedSurface == UtilitySurface.UTILITY && !state.capabilities.mainWriteAccessUseful) {
                    // OR-12: auto-brightness. No Allow (the grant would do nothing yet); the line is the instruction.
                    Text(
                        text = UtilityAutoBrightnessMessage,
                        style = MimirTypeScale.Support,
                        color = palette.textMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(UtilityActionRingReach))
                } else if (presentedSurface == UtilitySurface.TWEAKER) {
                    //  the Tweaker hint/toast now lives here, in
                    // the same weighted slot this Row already uses for UTILITY's main-display-access
                    // message above -- same shape (weighted content on the left, actions on the
                    // right). The two can never co-occur: this branch is TWEAKER-only and that one is
                    // UTILITY-only, so there is no collision case to design around.
                    //
                    // MimirHintToastRail is reused rather than hand-rolled as a plain Text so that
                    // MimirRunicMessageCard's existing message-class -> palette accent mapping
                    // (Guidance/Confirmation/Warning/Blocking in MimirHintToastRail.kt) keeps telling a
                    // refusal (Warning) apart from a success (Confirmation) here exactly as it does
                    // everywhere else a MimirMessage renders; a hand-rolled Text would have had to
                    // reinvent that distinction using only the palette (no new colors, no hex).
                    //
                    // Mounted unconditionally -- idle or active -- rather than only while a message is
                    // showing, so this weighted slot always claims the same share of the Row's width
                    // and Close's position never moves when a message arrives or expires (see
                    // `Close does not move when a Tweaker message arrives or expires` below).
                    // MimirHintToastRail's own internal contract does the rest: it always measures a
                    // fixed 32/36.dp band and paints nothing while idle, so an idle TWEAKER footer
                    // looks the same as an idle UTILITY footer with no main-display-access message.
                    //
                    // Auto-dismiss timing (~2.4s, MimirHintToastRailDefaults.ToastWindowMs) and the
                    // pending-mutation suppression (`takeUnless { tweakerMutationPending }`) are both
                    // unchanged from pass 3 -- this is a placement change only, not a lifetime redesign.
                    MimirHintToastRail(
                        hint = null,
                        toast = tweakerMessage.takeUnless { tweakerMutationPending },
                        onMessageDelivered = onTweakerMessageDelivered,
                        modifier = Modifier.weight(1f),
                        palette = palette,
                    )
                    Spacer(Modifier.width(UtilityActionRingReach))
                }
                UtilityAction(
                    label = layerBackActionLabel,
                    primary = false,
                    focused = layerFocusedControl == BrightnessControlKey.CLOSE,
                    palette = palette,
                    modifier = footerControlModifier(BrightnessControlKey.CLOSE),
                    enabled = !tweakerMutationPending,
                    onClick = {
                        requestPointerFocus(BrightnessControlKey.CLOSE)
                        if (backActionLabel == "Back") onBack() else onClose()
                    },
                )
            }
            }
        }
        TweakerSettlementLayer(
            prompt = tweakerSettlement,
            width = (panelWidth - 48.dp).coerceAtLeast(0.dp).coerceAtMost(360.dp),
            palette = palette,
            onCancel = onCancelTweakerMutation,
        )
    }
}

/**
 * The settlement scrim and card, fading in and out on the overlay clock instead of cutting (RC-40, audit 2026-10-14; the
 * "APPLYING... PLEASE WAIT" prompt of a Tweaker profile or fan change, in the Main Utility panel and the Bottom window alike).
 *
 * The prompt used to be `tweakerSettlement?.let { Overlay(it) }`: a 0.56 scrim and a glass card that appeared and vanished in one
 * frame inside a panel that is itself fading. [prompt] null starts the exit and the last prompt is held for it, so the card leaves
 * with the words it arrived with. The input shield is not this layer's: `tweakerMutationPending` reads the live state, so the panel
 * accepts input from the frame the change settles. While the layer leaves it only absorbs touch (no cancel action), so a tap on
 * the fading scrim cannot cancel a change that has already ended or, through `onCancelTweakerMutation`'s Back default, close the panel.
 */
@Composable
internal fun TweakerSettlementLayer(
    prompt: TweakerSettlementPrompt?,
    width: androidx.compose.ui.unit.Dp,
    palette: MimirThemePalette,
    onCancel: () -> Unit,
    reducedMotion: Boolean = mimirReducedMotion(),
) {
    // Plain memory written only while a prompt is live (the UtilityLayerMemory shape): nothing is drawn from it directly.
    val lastPrompt = remember { arrayOfNulls<TweakerSettlementPrompt>(1) }
    if (prompt != null) lastPrompt[0] = prompt
    val shown = prompt ?: lastPrompt[0]
    AnimatedVisibility(
        visible = prompt != null,
        enter = fadeIn(mimirSheetSpec(reducedMotion)),
        exit = fadeOut(mimirSheetSpec(reducedMotion)),
    ) {
        if (shown != null) {
            TweakerSettlementOverlay(
                // The leaving card keeps its copy ("Cancel change" included) while it fades; only the action goes inert (W1A #2).
                prompt = shown,
                width = width,
                palette = palette,
                onCancel = if (prompt == null) ({}) else onCancel,
            )
        }
    }
}

@Composable
private fun TweakerSettlementOverlay(
    prompt: TweakerSettlementPrompt,
    width: androidx.compose.ui.unit.Dp,
    palette: MimirThemePalette,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.scrim.copy(alpha = 0.56f))
            // The pending layer is the input owner. During APPLYING a backdrop tap requests the same
            // idempotent cancellation as the visible action; during CANCELLING it only absorbs input.
            .then(
                if (prompt.canCancel) {
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onCancel,
                    )
                } else {
                    Modifier.pointerInput(prompt) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { change ->
                                    if (!change.isConsumed) change.consume()
                                }
                            }
                        }
                    }
                },
            )
            .clearAndSetSemantics {
                contentDescription = "${prompt.titleCaps}. ${prompt.supportingCaps}"
                liveRegion = LiveRegionMode.Assertive
                if (prompt.canCancel) {
                    customActions = listOf(
                        CustomAccessibilityAction("Cancel change") {
                            onCancel()
                            true
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        MimirLiquidGlassPanel(
            modifier = Modifier
                .width(width)
                // Contain taps on the card itself so the full-screen scrim cannot interpret a card
                // tap as a cancellation. The child action remains the only active card action.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            contentPadding = MimirSpacing.lg,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            MimirChromeText(
                text = "ᛉ · Tweaker",
                modifier = Modifier.fillMaxWidth(),
                color = palette.accent,
                style = MimirTypeScale.RunicEyebrow,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            MimirChromeText(
                text = prompt.titleCaps,
                modifier = Modifier.fillMaxWidth(),
                color = palette.textPrimary,
                style = MimirTypeScale.TitleCapsCompact,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            MimirChromeText(
                text = prompt.supportingCaps,
                modifier = Modifier.fillMaxWidth(),
                color = palette.accentBright,
                style = MimirTypeScale.ControlHint,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (prompt.canCancel) {
                            Modifier.clickable(onClick = onCancel)
                        } else {
                            Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { change ->
                                            if (!change.isConsumed) change.consume()
                                        }
                                    }
                                }
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (prompt.canCancel) {
                    MimirChromeText(
                        text = "Cancel change",
                        color = palette.accentBright,
                        style = MimirTypeScale.ControlHint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * UT01 focus-safe padding (dp) at both ends of the control rail, so a focused ring at either extreme
 * clears the edge fade and the fixed chrome instead of sitting flush against it.
 */
internal const val UtilityRailSafePaddingDp: Int = 12

/** The divider-to-first-row inset. */
internal val UtilityRailFirstRowInset = 6.dp

/**
 * How far the rail's scroll viewport reaches up behind the divider: the rail pads MimirFocusTileRingReach so the
 * scroll clip never cuts a focused first row's ring, and the viewport bleeds the difference upward so the row itself
 * keeps UtilityRailFirstRowInset.
 */
internal val UtilityRailRingBleed: Dp = MimirFocusTileRingReach - UtilityRailFirstRowInset

/**
 * Measures its content [bleed] taller and places it [bleed] higher, reporting the height it was given: the content
 * extends up over whatever precedes it without moving anything after it. Holds zero: a zero-height slot reports zero.
 */
internal fun Modifier.utilityRailBleedUp(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = constraints.minHeight + extra,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else constraints.maxHeight,
        ),
    )
    val height = (placeable.height - extra).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, -extra) }
}

/** The corner every Utility row and action clips and rings on (ring migration, 2026-09-28; R-1: one symbol for both). */
internal val UtilityRowCorner = 6.dp
internal const val UtilityRailEdgeFadeMaxAlpha: Float = 0.05f

/**
 * The continuation cue: a short fade at whichever rail edge still has content beyond it, so "there is
 * more below" is visible without a scrollbar. Purely decorative — it never intercepts input.
 */
@Composable
private fun BoxScope.UtilityRailEdgeFade(scroll: ScrollState, palette: MimirThemePalette) {
    val fadeHeight = 12.dp
    val edgeInk = palette.surfaceRaised.copy(alpha = UtilityRailEdgeFadeMaxAlpha)
    if (scroll.value > 0) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(fadeHeight)
                .background(
                    Brush.verticalGradient(
                        listOf(edgeInk, edgeInk.copy(alpha = 0f)),
                    ),
                ),
        )
    }
    if (scroll.value < scroll.maxValue) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(fadeHeight)
                .background(
                    Brush.verticalGradient(
                        listOf(edgeInk.copy(alpha = 0f), edgeInk),
                    ),
                ),
        )
    }
}

internal fun secondaryBrightnessLabel(state: BrightnessState): String =
    if (state.capabilities.secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM) {
        "Bottom screen"
    } else {
        "Bottom screen (Mimir only)"
    }

internal fun privilegedUtilityMessage(state: BrightnessState): String? {
    if (!state.capabilities.hasVendorControls) return null
    state.privilegedFailureMessage?.let { return it }
    val posture = state.capabilities.privilegedUtilityPosture
    if (posture.authority == PrivilegedUtilityAuthority.CERTIFIED_VENDOR) {
        if (posture.status == PrivilegedUtilityStatus.PERMISSION_REQUIRED) {
            return "This handheld offers built-in verified controls. Enable them for Mimir."
        }
        if (posture.status == PrivilegedUtilityStatus.READY &&
            posture.supplementalAccessStatus != null
        ) {
            // No alternate authority remains for a secondary panel the vendor route cannot control.
            return "These device controls are unavailable on this device."
        }
    }
    return when (posture.status) {
        PrivilegedUtilityStatus.UNSUPPORTED_DEVICE ->
            "These device controls are read-only on this device."
        PrivilegedUtilityStatus.MANAGER_MISSING ->
            "These device controls need special access. Set it up below."
        PrivilegedUtilityStatus.SERVICE_STOPPED ->
            "These device controls are unavailable on this device."
        PrivilegedUtilityStatus.API_UNSUPPORTED ->
            "These device controls aren’t supported on this Android version."
        PrivilegedUtilityStatus.PERMISSION_REQUIRED ->
            "Allow Mimir in special access to enable these device controls."
        PrivilegedUtilityStatus.PERMISSION_DENIED ->
            "Mimir isn’t allowed to use special access."
        PrivilegedUtilityStatus.BINDING ->
            "Checking special access…"
        // QA-11 : both of these said "This device doesn't offer this control."
        // On the Thor that sentence is false, and the log proves it in the same run --
        // `MimirPrivilege: vendor route admitted profile=ayn-snapdragon_8_gen_2-pserver-v2` at startup,
        // and the controls work either side of the message. Neither state is about what the device
        // offers:
        //
        //  * REMOTE_PERMISSION_MISSING used to represent a remote grant withholding either vendor setting
        //    or secondary-display access. It is now treated as an unavailable device route.
        //  * READY + hasReadOnlyVendorControls means a vendor control resolves but cannot be WRITTEN on
        //    the access currently held (BrightnessCapability:135-139). Readable, not absent.
        //
        // Two different causes must not share one sentence, and neither may claim the device is
        // incapable. UNSUPPORTED_DEVICE above keeps "read-only on this device" -- that one really is a
        // device statement, and is the precedent for the wording here.
        PrivilegedUtilityStatus.REMOTE_PERMISSION_MISSING ->
            "These device controls are unavailable on this device."
        PrivilegedUtilityStatus.READY -> if (state.capabilities.hasReadOnlyVendorControls) {
            "Some of these controls can be read but not changed with the access Mimir currently holds."
        } else {
            null
        }
        PrivilegedUtilityStatus.CONNECTION_FAILED ->
            "The device control couldn’t be verified. Reconnect and try again."
        PrivilegedUtilityStatus.READBACK_UNSATISFIABLE ->
            "Special access is connected, but Mimir can’t verify these controls on this device."
    }
}

internal fun privilegedUtilityActionLabel(posture: PrivilegedUtilityPosture): String =
    if (posture.authority == PrivilegedUtilityAuthority.CERTIFIED_VENDOR &&
        posture.status == PrivilegedUtilityStatus.PERMISSION_REQUIRED
    ) {
        // One verb for every permission : onboarding's built-in controls button reads Allow.
        "Allow"
    } else {
        privilegedUtilityActionLabel(posture.requestedAccessStatus ?: posture.status)
    }

internal fun privilegedUtilityActionLabel(status: PrivilegedUtilityStatus): String =
    if (status == PrivilegedUtilityStatus.PERMISSION_REQUIRED) "Allow" else "Unavailable"

@Composable
private fun UtilitySectionLabel(label: String, palette: MimirThemePalette) {
    MimirChromeText(label, style = MimirTypeScale.RunicEyebrow, color = palette.textSecondary)
}

@Composable
internal fun UtilityChoiceRow(
    label: String,
    supportingText: String,
    valueLabel: String,
    stateDescription: String,
    focused: Boolean,
    palette: MimirThemePalette,
    modifier: Modifier,
    onRequestFocus: () -> Unit,
    onStep: (Int) -> Unit,
    canStepLeft: Boolean,
    canStepRight: Boolean,
    onActivate: (() -> Unit)? = null,
    interactive: Boolean = true,
    enabled: Boolean = true,
    showStepAffordances: Boolean = interactive,
    // Visual caps + SupportCompact-scale (11sp) subtext, and the tightened 48.dp row floor that size
    // allows. Originally a Tweaker-panel-only opt-in ; superseded the same day --
    // every current caller (Tweaker's own rows, plus the Utility-surface M button, Navigation hints,
    // Cartridge art, and Open Tweaker rows) now opts in, so this reads as the one live UtilityChoiceRow
    // look. The parameter and its false default stay so a future caller can still choose the plain
    // 58.dp/Support look deliberately, but nothing in this file does today.
    compactSupporting: Boolean = false,
    // One line each for the label and the supporting text, ellipsized (the music panel's switch row: a name over a track title).
    singleLine: Boolean = false,
    /**
     * The Bottom's (touch-only): the step chevrons are `mimirActivatable` with the one press response (B5) instead of `clickable`, which plants a Compose focus
     * target beside the one FocusAuthority. Off (Main's and every other caller's), they are exactly the `clickable`s they were.
     */
    pressResponse: Boolean = false,
) {
    val shape = RoundedCornerShape(UtilityRowCorner)
    val leftInteraction = remember { MutableInteractionSource() }
    val rightInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compactSupporting) 48.dp else 58.dp)
            // The one ring (ring migration, 2026-09-28): a row ring on the rail's corner, whose wash replaces the
            // 0.08 tint and whose halo reaches past the row (the rail's padding clears it). Keyed on the label.
            .mimirSelectionRing(focused = focused, identity = label, shape = MimirRingShape.Row(UtilityRowCorner))
            .clip(shape)
            // The row names itself, the same way TweakerProfileRow and TweakerFanCurveRow do. Every
            // row built on this composable gains a locatable identity at once, which is what lets
            // TweakerRailRenderOrderTest see its POSITION -- the declaration site that shipped a drawn
            // but unreachable frame-cap row and has no other gate. The label is authored natural case;
            // the caps renderer handles the visual, so this is the spoken form, not a second string.
            .semantics {
                contentDescription = label
                this.stateDescription = stateDescription
            }
            .then(
                if (interactive) {
                    // Not `clickable`: `modifier` already carries AppBrightnessHost's mimirFocusable, and a
                    // clickable beside it plants a second focus node (MimirActivatable.kt). onRequestFocus
                    // publishes focus first, as before.
                    Modifier.mimirActivatable(label = label, enabled = enabled, role = null) {
                        onRequestFocus()
                        onActivate?.invoke()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            MimirChromeText(
                label,
                style = MimirTypeScale.MenuAction,
                color = palette.textPrimary,
                maxLines = if (singleLine) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (compactSupporting) {
                // Chrome: visual caps come from the renderer, not a pre-uppercased literal, so
                // TalkBack keeps reading words instead of spelling letters (see the row-name comment
                // above for the same pattern). SupportCompact is the one normalized subtext size across
                // Tweaker's rows .
                MimirChromeText(
                    supportingText,
                    style = MimirTypeScale.SupportCompact,
                    color = palette.textMuted,
                    maxLines = if (singleLine) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    supportingText,
                    style = MimirTypeScale.Support,
                    color = palette.textMuted,
                    maxLines = if (singleLine) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            // the step/value cluster's own spacing read as too generous.
            // Was 8.dp; each chevron already carries 4.dp of its own clickable padding on this side,
            // so 8.dp of arrangement on top of that was double-counted air. Left the chevron padding
            // and the outer 12.dp label/cluster gap alone -- see the report for why.
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (interactive) {
                Text(
                    text = "‹",
                    style = MimirTypeScale.Pill,
                    color = when {
                        !showStepAffordances -> Color.Transparent
                        canStepLeft -> palette.accentBright
                        else -> palette.textMuted.copy(alpha = 0.24f)
                    },
                    // A hidden chevron (showStepAffordances = false) carries no clickable (B5, round-5 triage): a
                    // disabled clickable still consumes the tap, and touch hit-testing widens this glyph to the
                    // minimum touch target, over the value label beside it, so a tap on OPEN never reached the row.
                    // The Text stays for its width, so nothing reflows; its semantics clear, as TweakerFpsStepAction's
                    // do. A shown chevron keeps its clickable even when it cannot step, so stepping rows are unchanged.
                    modifier = Modifier
                        .then(
                            if (showStepAffordances && pressResponse) {
                                Modifier
                                    .mimirActivatable(
                                        label = "Previous $label",
                                        enabled = enabled && canStepLeft,
                                        interactionSource = leftInteraction,
                                    ) {
                                        onRequestFocus()
                                        onStep(-1)
                                    }
                                    .mimirPressResponse(leftInteraction)
                            } else if (showStepAffordances) {
                                Modifier.clickable(
                                    enabled = enabled && canStepLeft,
                                    interactionSource = leftInteraction,
                                    indication = null,
                                ) {
                                    onRequestFocus()
                                    onStep(-1)
                                }
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            MimirChromeText(
                text = valueLabel,
                style = MimirTypeScale.MicroStatus,
                color = if (focused) palette.accentBright else palette.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (interactive) {
                Text(
                    text = "›",
                    style = MimirTypeScale.Pill,
                    color = when {
                        !showStepAffordances -> Color.Transparent
                        canStepRight -> palette.accentBright
                        else -> palette.textMuted.copy(alpha = 0.24f)
                    },
                    // Same rule as the ‹ above.
                    modifier = Modifier
                        .then(
                            if (showStepAffordances && pressResponse) {
                                Modifier
                                    .mimirActivatable(
                                        label = "Next $label",
                                        enabled = enabled && canStepRight,
                                        interactionSource = rightInteraction,
                                    ) {
                                        onRequestFocus()
                                        onStep(1)
                                    }
                                    .mimirPressResponse(rightInteraction)
                            } else if (showStepAffordances) {
                                Modifier.clickable(
                                    enabled = enabled && canStepRight,
                                    interactionSource = rightInteraction,
                                    indication = null,
                                ) {
                                    onRequestFocus()
                                    onStep(1)
                                }
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The PROFILE row, drafting like the two rows beneath it.
 *
 * Left/right move a preview and cost nothing; APPLY is the row's only transaction. Status and action
 * seats are permanent — as on Fan response — so entering preview never reflows the rail, and the
 * capped value is still the whole truth: INSTALL TWEAKER · UNAVAILABLE · STOCK ONLY · a profile.
 */
@Composable
private fun TweakerProfileRow(
    row: TweakerUtilityRowState,
    supportingText: String,
    fanReadoutText: String?,
    previewProfileId: String?,
    /** The drafted CEILING, already spelled; null unless this row is serving the discovered path. */
    previewCeilingLabel: String?,
    /** The drafted ceiling is the top of the scale, so APPLY releases the hold rather than writing one. */
    previewCeilingReleases: Boolean,
    draftPending: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    modifier: Modifier,
    onRequestFocus: () -> Unit,
    onStep: (Int) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    controllerHintsAvailable: Boolean,
    enabled: Boolean,
) {
    val shape = RoundedCornerShape(UtilityRowCorner)
    // Bottom omits the 14.dp controller-hint seat; 48.dp control + 48.dp status/actions + 12.dp
    // vertical padding keeps the same touch geometry without reserving that hidden seat.
    val rowMinHeight = if (controllerHintsAvailable) 122.dp else 108.dp
    val valueLabel = previewCeilingLabel ?: tweakerProfileValueLabel(row, previewProfileId)
    val rowSupportingText = when {
        draftPending && previewCeilingLabel != null ->
            tweakerCeilingDraftSupportingText(previewCeilingLabel, previewCeilingReleases)
        draftPending && previewProfileId != null -> tweakerProfileDraftSupportingText(previewProfileId)
        else -> supportingText
    }
    // The fan readout rides along with the settled subtext, not with a pending Apply/Cancel decision:
    // while drafting, "Apply to switch profile to X" is the row's one actionable message and stays
    // alone, exactly as it did before the readout existed.
    val rowFanReadoutText = fanReadoutText
        .takeUnless { draftPending && (previewProfileId != null || previewCeilingLabel != null) }
    val richStateCopy = row.availability == TweakerUtilityAvailability.READY ||
        row.availability == TweakerUtilityAvailability.DISCOVERED_CEILING
    // Shared by the accessibility sentence and visible status label so held state cannot drift into
    // two conflicting descriptions.
    val currentStatusWord =
        if (row.availability == TweakerUtilityAvailability.READY && row.heldByUnfinishedRestore) {
            "Recovery unfinished"
        } else {
            "Current setting"
        }
    val stateCopy = if (!richStateCopy) {
        "$valueLabel. $rowSupportingText"
    } else {
        buildString {
            append(valueLabel)
            append(". ")
            append(rowSupportingText)
            rowFanReadoutText?.let {
                append(". ")
                append(it)
            }
            append(if (draftPending) " Preview, not applied." else " $currentStatusWord.")
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = rowMinHeight)
            .mimirSelectionRing(focused = focused, identity = "Tweaker profile", shape = MimirRingShape.Row(UtilityRowCorner))
            .clip(shape)
            .semantics {
                contentDescription = "Tweaker profile"
                stateDescription = stateCopy
            }
            // mimirActivatable, not clickable: `modifier` carries the host's mimirFocusable (one focus node).
            .mimirActivatable(label = "Tweaker profile", enabled = enabled, role = null, onActivate = onRequestFocus)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // 48.dp, not the former 58.dp: this floor is a minimum, not a cap (heightIn keeps growing to
        // fit real content), and it now matches the 48.dp touch-target unit already used everywhere
        // else in this file (TweakerFpsStepAction, TweakerDraftAction, the status row below) instead of
        // an ad hoc number. Label + one subtext line measures well under 48.dp; label + subtext + the
        // fan readout still clears it comfortably, so the two-line case grows the row rather than
        // clipping it.
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MimirChromeText(
                    text = "Tweaker profile",
                    style = MimirTypeScale.MenuAction,
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Chrome, not plain Text: visual caps come from the renderer, not from the authored
                // string, so TalkBack still reads words instead of spelling a literal. Normalized to
                // SupportCompact panel-wide  -- one subtext size across Tweaker's
                // rows, with the fan readout below as the one deliberately smaller exception.
                TweakerRowStateSwap(answerPending = row.answerPending, anchor = Alignment.CenterStart) {
                    MimirChromeText(
                        text = rowSupportingText,
                        style = MimirTypeScale.SupportCompact,
                        color = palette.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                rowFanReadoutText?.let { readout ->
                    MimirChromeText(
                        text = readout,
                        style = MimirTypeScale.MicroStatus,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TweakerFpsStepAction(
                    glyph = "‹",
                    contentDescription = "Previous profile",
                    active = enabled && row.canStep,
                    palette = palette,
                    onClick = {
                        onRequestFocus()
                        onStep(-1)
                    },
                )
                // CHECKING… and a profile name replace each other by dissolving on the overlay clock (reduced motion
                // jumps). A STEP between profile names does not swap: the value snaps and ticks (MimirNavFeedback), because
                // a crossfade restarted by each rapid D-pad press reads as jitter. See [TweakerRowStateSwap].
                TweakerRowStateSwap(answerPending = row.answerPending) {
                    MimirChromeText(
                        text = valueLabel,
                        style = MimirTypeScale.MicroStatus,
                        color = if (focused) palette.accentBright else palette.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                TweakerFpsStepAction(
                    glyph = "›",
                    contentDescription = "Next profile",
                    active = enabled && row.canStep,
                    palette = palette,
                    onClick = {
                        onRequestFocus()
                        onStep(1)
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirChromeText(
                text = when {
                    draftPending -> "Preview · not applied"
                    // **`richStateCopy`, not `== READY`.** Found on glass: the row was naming a real
                    // clock the device is holding and this seat, one line below it, said "Not
                    // available". That is E3's defect again at a smaller scale -- a status derived from
                    // whether the DAEMON is serving, printed under a value that came from the kernel.
                    // A discovered ceiling IS the current setting.
                    !richStateCopy -> "Not available"
                    else -> currentStatusWord
                },
                style = MimirTypeScale.MicroStatus,
                color = if (draftPending) palette.accentBright else palette.textMuted,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TweakerDraftAction(
                    label = "Cancel",
                    contentDescription = "Cancel profile",
                    visible = draftPending,
                    enabled = enabled && draftPending,
                    palette = palette,
                    emphasized = false,
                    onClick = onCancel,
                )
                // the Cancel/Apply gap read as too generous. Was 12.dp,
                // identically here and in TweakerFanCurveRow below. The
                // TweakerDraftAction buttons themselves (72.dp x 48.dp touch targets) are untouched --
                // that is button size, not the space between them.
                Spacer(Modifier.width(8.dp))
                TweakerDraftAction(
                    label = "Apply",
                    contentDescription = "Apply profile",
                    visible = draftPending,
                    enabled = enabled && draftPending,
                    palette = palette,
                    emphasized = true,
                    onClick = onApply,
                )
            }
        }
        if (controllerHintsAvailable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .then(if (row.canStep) Modifier else Modifier.clearAndSetSemantics { }),
                contentAlignment = Alignment.CenterStart,
            ) {
                MimirChromeText(
                    text = if (draftPending) "A apply · B cancel" else "L/R choose",
                    style = MimirTypeScale.ControlHintCompact,
                    color = if (row.canStep) palette.textMuted else Color.Transparent,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

@Composable
private fun TweakerFpsStepAction(
    glyph: String,
    contentDescription: String,
    active: Boolean,
    palette: MimirThemePalette,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(
                if (active) {
                    Modifier
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                        .clearAndSetSemantics {
                            role = Role.Button
                            this.contentDescription = contentDescription
                            onClick {
                                onClick()
                                true
                            }
                        }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MimirTypeScale.Pill,
            color = if (active) palette.accentBright else Color.Transparent,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * How warm should the temperature readout run right now? `0f` is the authored token untouched.
 *
 * Target-relative warming replaces the earlier one-ink design.
 * The earlier objection to colouring remains relevant:
 * Its objection was to an **absolute threshold**: anything near 76 °C would paint balanced's own
 * measured PASS (GPU mean 71.5 against a 70/77 target) as a problem, and anything at 85 °C echoes
 * `GPU_WITNESS_FULL_FAN_C`, a safety witness, saying "danger" on a screen upstream of every real
 * protection.
 *
 * This ramp is **target-relative**, which is why it does not inherit that objection: it references
 * [targetTempC], the number the active profile's own curve is already regulating toward, not a
 * temperature this surface invented. Reaching your target is not a verdict, and the ink says only
 * "the fan is working hardest here" -- which is exactly what the readout exists to show.
 *
 * **GPU only, and deliberately not the hotter of the two numbers.** [targetTempC] is the fan curve's
 * own target and the curve is driven by GPU temperature (`FanThermalProfiles`: 55/65/70 for
 * quiet/balanced/performance); the package/CPU sensor exists for the independent 84 °C full-fan
 * safety intervention, on its own scale. A first cut of this warmed on `max(gpu, cpu)`, which
 * compares a CPU reading against a GPU target — on an Odin test device, CPU 89.9 °C against a 70 °C
 * GPU target would have pinned the readout warm permanently, for a reason the target does not
 * describe. The CPU number still displays; it simply does not drive the colour, because nothing
 * established says what it is near. [[right-numbers-wrong-mechanism-label]]
 *
 * Null target or null reading means no shift at all: with nothing established to be near, the
 * readout must not imply a judgement. That is §2.3's principle preserved, not discarded.
 *
 * [APPROACH_BAND_C] and [MAX_WARM_FRACTION] are **provisional design parameters** -- named so they can
 * be tuned in one place. The requirement fixes whether to warm and against what; the geometry is a
 * design detail that still needs visual review.
 */
internal fun tweakerFanTempWarmFraction(gpuTempC: Int?, targetTempC: Int?): Float {
    if (gpuTempC == null || targetTempC == null) return 0f
    val approachStart = targetTempC - APPROACH_BAND_C
    if (gpuTempC <= approachStart) return 0f
    if (gpuTempC >= targetTempC) return MAX_WARM_FRACTION
    val progress = (gpuTempC - approachStart).toFloat() / APPROACH_BAND_C.toFloat()
    return progress * MAX_WARM_FRACTION
}

/** Degrees below the profile's own target at which the readout starts to warm. */
private const val APPROACH_BAND_C = 10

/**
 * Capped well short of full molten amber. The readout is `textMuted` subtext, not a control, and it
 * has to stay legible at its authored weight -- a fully-blended token reads as an alarm, which is
 * the verdict §2.3 was right to refuse.
 */
private const val MAX_WARM_FRACTION = 0.55f

@Composable
private fun TweakerFanCurveRow(
    authoritativeBias: Int,
    previewBias: Int,
    curveBiasRange: IntRange,
    curveBiasStep: Int,
    gpuTempC: Int?,
    cpuTempC: Int?,
    /** The active profile's own curve target; null disables the warm shift entirely. */
    targetTempC: Int?,
    draftPending: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    modifier: Modifier,
    onRequestFocus: () -> Unit,
    onPreviewBias: (Int) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    controllerHintsAvailable: Boolean,
    enabled: Boolean,
) {
    val fraction = tweakerFanCurveFraction(previewBias, curveBiasRange)
    val shape = RoundedCornerShape(UtilityRowCorner)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Status and action seats are permanent so entering preview never reflows the rail.
            // heightIn(min=), not a hard height: a fixed height with SpaceBetween arrangement clips
            // silently if any child grows past its share (exactly the risk a supporting-text resize
            // creates). 156.dp was the tightened floor for this row's content at SupportCompact; the
            // subtext below is smaller still (ControlHintCompact, correction). Raised
            // to 170.dp to seat the
            // new temperature readout line ADDED to the row's floor rather than eaten from the existing
            // SpaceBetween gaps -- a bare content-height drop into this arrangement would re-rhythm a
            // row nobody asked to change. The row still grows past this floor rather than clipping if
            // real content needs more.
            .heightIn(min = 170.dp)
            .mimirSelectionRing(focused = focused, identity = "Tweaker fan curve", shape = MimirRingShape.Row(UtilityRowCorner))
            .clip(shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirChromeText(
                text = "Fan response",
                style = MimirTypeScale.MenuAction,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = tweakerFanCurveLabel(previewBias),
                style = MimirTypeScale.MicroStatus,
                color = if (focused) palette.accentBright else palette.textSecondary,
                textAlign = TextAlign.End,
            )
        }
        //  SupportCompact (11sp, the Profile/Display-rate treatment above)
        // read too large once seen on device for this row specifically -- taken back down to
        // ControlHintCompact (9sp). Checked for the reported collision before landing here:
        // this row has no "L/R choose" legend at all (the curve is a continuous drag/slider, not an L/R
        // stepped control like Profile), so there is no duplicate string. The row's only
        // other ControlHintCompact use is the conditional "A apply · B cancel" hint at the row's own
        // foot (below, only while draftPending) -- spatially separated from this line by the curve
        // preview and status/action row between them, so the two reuse the same scale without reading
        // as one merged element. Only this row's subtext moved; Profile above is unchanged.
        MimirChromeText(
            text = tweakerFanCurveSupportingText(previewBias),
            style = MimirTypeScale.ControlHintCompact,
            color = palette.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Live device state -- what
        // the device IS right now, never a preview and never a bias-implied target (§2.2); the
        // before-and-after evidence for the trade the subtext above just stated in words. GPU leads
        // (§2.1: it is the axis the curve below is drawn against); CPU follows because GPU alone can
        // read cool while the chassis feels hot in the player's hand. One ink always, focused or not
        // (§2.3: a colour that changed with temperature would be an unearned safety claim this product
        // has not established) -- unlike the value label above, this line does NOT brighten on focus.
        // clearAndSetSemantics{}: this Column carries no top-level semantics node of its own (unlike
        // TweakerProfileRow, whose whole row is one merged node via its outer .mimirActivatable), so a plain
        // Text sibling here would be its own independent TalkBack stop reading the literal string --
        // em dash spelled out -- doubling the spoken form appended to the curve's stateDescription
        // below (§5). No liveRegion, no animation (§3.3): the value is replaced, not announced.
        MimirChromeText(
            text = tweakerFanTemperatureReadoutText(gpuTempC, cpuTempC),
            style = MimirTypeScale.MicroStatus,
            // Warm the readout near the target. The ink is still the authored
            // `palette.textMuted` -- warmShifted runs that token hotter through the theme's own
            // blend rather than substituting a colour, so the readout keeps following the subtext
            // and no hex enters this module. Fraction 0 at rest returns the token untouched, which
            // is every device that is not near its profile's target.
            color = warmShifted(
                palette.textMuted,
                tweakerFanTempWarmFraction(gpuTempC = gpuTempC, targetTempC = targetTempC),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics {
                    contentDescription = "Fan response"
                    stateDescription = buildString {
                        append(tweakerFanCurveAccessibilityLabel(previewBias))
                        append(". ")
                        append(tweakerFanCurveSupportingText(previewBias))
                        append(if (draftPending) " Preview, not applied." else " Current setting.")
                        append(" ")
                        append(tweakerFanTemperatureSpokenText(gpuTempC, cpuTempC))
                        append(" Fan speed increases upward. GPU temperature increases to the right.")
                    }
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = fraction,
                        range = 0f..1f,
                        steps = (
                            (curveBiasRange.last - curveBiasRange.first) /
                                curveBiasStep.coerceAtLeast(1) - 1
                            ).coerceAtLeast(0),
                    )
                    if (enabled) {
                        setProgress { requested ->
                            onRequestFocus()
                            val next = tweakerFanCurveBiasFromFraction(
                                requested,
                                curveBiasRange,
                                curveBiasStep,
                            )
                            onPreviewBias(next)
                            true
                        }
                    }
                }
                .pointerInput(enabled, authoritativeBias, curveBiasRange, curveBiasStep) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // The page gate shields an arriving panel (through its lead) and a departing copy by consuming every press in the
                        // Initial pass. `requireUnconsumed = false` would otherwise step past that shield and let a lead-time tap move the draft.
                        if (down.isConsumed) return@awaitEachGesture
                        onRequestFocus()
                        fun previewAt(x: Float) {
                            if (size.width <= 0) return
                            onPreviewBias(
                                tweakerFanCurveBiasFromFraction(
                                    x / size.width.toFloat(),
                                    curveBiasRange,
                                    curveBiasStep,
                                ),
                            )
                        }
                        previewAt(down.position.x)
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                            previewAt(change.position.x)
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            val thumbSize = 12.dp
            TweakerFanCurvePreview(
                curveBias = previewBias,
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .align(Alignment.TopStart),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.textMuted.copy(alpha = 0.22f)),
            )
            Box(
                Modifier
                    .offset(x = (maxWidth - thumbSize) * fraction)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(palette.accentBright),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirChromeText(
                text = if (draftPending) "Preview · not applied" else "Current setting",
                style = MimirTypeScale.MicroStatus,
                color = if (draftPending) palette.accentBright else palette.textMuted,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TweakerDraftAction(
                    label = "Cancel",
                    contentDescription = "Cancel fan response",
                    visible = draftPending,
                    enabled = enabled && draftPending,
                    palette = palette,
                    emphasized = false,
                    onClick = onCancel,
                )
                Spacer(Modifier.width(8.dp))
                TweakerDraftAction(
                    label = "Apply",
                    contentDescription = "Apply fan response",
                    visible = draftPending,
                    enabled = enabled && draftPending,
                    palette = palette,
                    emphasized = true,
                    onClick = onApply,
                )
            }
        }
        if (controllerHintsAvailable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .then(if (draftPending) Modifier else Modifier.clearAndSetSemantics { }),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draftPending) {
                    MimirChromeText(
                        text = "A apply · B cancel",
                        style = MimirTypeScale.ControlHintCompact,
                        color = palette.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TweakerFanCurvePreview(
    curveBias: Int,
    palette: MimirThemePalette,
    modifier: Modifier,
) {
    Box(modifier = modifier.clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val horizontalInset = 3.dp.toPx()
            val verticalInset = 2.dp.toPx()
            val usableWidth = (size.width - horizontalInset * 2f).coerceAtLeast(1f)
            val usableHeight = (size.height - verticalInset * 2f).coerceAtLeast(1f)
            val biasShift = curveBias.coerceIn(-100, 100) / 100f * usableWidth * 0.12f

            fun curvePath(shiftX: Float): Path = Path().apply {
                val points = listOf(
                    0.00f to 0.08f,
                    0.25f to 0.10f,
                    0.43f to 0.25f,
                    0.61f to 0.48f,
                    0.80f to 0.72f,
                    1.00f to 1.00f,
                )
                points.forEachIndexed { index, (x, y) ->
                    val px = (horizontalInset + x * usableWidth + shiftX)
                        .coerceIn(horizontalInset, size.width - horizontalInset)
                    val py = size.height - verticalInset - y * usableHeight
                    if (index == 0) moveTo(px, py) else lineTo(px, py)
                }
            }

            drawPath(
                path = curvePath(0f),
                color = palette.textMuted.copy(alpha = 0.32f),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawPath(
                // Cooler (negative) moves the response left; Quieter moves it right.
                path = curvePath(biasShift),
                color = palette.accentBright,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        MimirChromeText(
            text = "Fan speed % ↑",
            style = MimirTypeScale.MicroStatus,
            color = palette.textMuted,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 2.dp),
        )
        MimirChromeText(
            text = "GPU temp °C →",
            style = MimirTypeScale.MicroStatus,
            color = palette.textMuted,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun TweakerDraftAction(
    label: String,
    contentDescription: String,
    visible: Boolean,
    enabled: Boolean,
    palette: MimirThemePalette,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .then(
                if (visible) {
                    Modifier.clearAndSetSemantics {
                        role = Role.Button
                        this.contentDescription = contentDescription
                        if (enabled) {
                            onClick {
                                onClick()
                                true
                            }
                        }
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            text = label,
            style = MimirTypeScale.MicroStatus,
            color = when {
                !visible -> Color.Transparent
                emphasized -> palette.accentBright
                else -> palette.textSecondary
            },
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * A disabled row's dim, eased rather than snapped when the switch that disables it flips . The clock is the
 * one Settings' disabled values already use (`SettingsChip`'s ink: [mimirSwapExitSpec], the overlay clock on the house easing), and
 * reduced motion jumps. Read it inside a draw-time `graphicsLayer` on a ModulateAlpha layer, after the ring, so a ring is never cut.
 */
@Composable
internal fun utilityDisabledAlpha(enabled: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (enabled) 1f else 0.48f,
        animationSpec = mimirSwapExitSpec(mimirReducedMotion()),
        label = "utility-disabled-dim",
    )

@Composable
internal fun UtilitySliderRow(
    label: String,
    valueLabel: String,
    fraction: Float,
    target: BrightnessTarget,
    enabled: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    focusModifier: Modifier,
    onRequestFocus: () -> Unit,
    onValueChange: (Float) -> Unit,
    trackColors: List<Color>? = null,
    thumbColor: Color? = null,
    steps: Int = 19,
    deferredHardwareReadback: Boolean = false,
    /** A small tick at this fraction of the track (the music panel's Game & media level on the Music slider); null draws none. */
    markerFraction: Float? = null,
    /** Where the fill starts (the equalizer's 0 dB centre); null fills from the track's left end. The fill grows from there to the thumb, either way. */
    fillFromFraction: Float? = null,
    /** The finger left the track (the music panel clears its one cap line there). */
    onInteractionEnd: () -> Unit = {},
) {
    val bounded = BrightnessMath.quantize(target, fraction)
    val rowShape = RoundedCornerShape(UtilityRowCorner)
    val dim by utilityDisabledAlpha(enabled)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(focusModifier)
            .mimirSelectionRing(focused = focused, identity = label, shape = MimirRingShape.Row(UtilityRowCorner))
            // The disabled dim sits AFTER the ring on a ModulateAlpha layer: a plain alpha layer composites at its
            // own bounds and would cut the ring's halo off.
            .graphicsLayer {
                alpha = dim
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .clip(rowShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirChromeText(
                text = label,
                style = MimirTypeScale.MenuAction,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MimirTypeScale.MicroStatus,
                color = if (focused) palette.accentBright else palette.textSecondary,
                textAlign = TextAlign.End,
            )
        }
        UtilitySlider(
            label = label,
            fraction = bounded,
            target = target,
            enabled = enabled,
            focused = focused,
            palette = palette,
            onRequestFocus = onRequestFocus,
            onValueChange = onValueChange,
            trackColors = trackColors,
            thumbColor = thumbColor,
            steps = steps,
            deferredHardwareReadback = deferredHardwareReadback,
            markerFraction = markerFraction,
            fillFromFraction = fillFromFraction,
            onInteractionEnd = onInteractionEnd,
        )
    }
}

@Composable
private fun UtilitySlider(
    label: String,
    fraction: Float,
    target: BrightnessTarget,
    enabled: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    onRequestFocus: () -> Unit,
    onValueChange: (Float) -> Unit,
    trackColors: List<Color>?,
    thumbColor: Color?,
    steps: Int,
    deferredHardwareReadback: Boolean,
    markerFraction: Float?,
    fillFromFraction: Float?,
    onInteractionEnd: () -> Unit,
) {
    val thumbSize = 18.dp
    val latestInteractionEnd by rememberUpdatedState(onInteractionEnd)
    val latestRequestFocus by rememberUpdatedState(onRequestFocus)
    val latestValueChange by rememberUpdatedState(onValueChange)
    var dragPreview by remember(target) { mutableStateOf<Float?>(null) }
    val displayedFraction = if (deferredHardwareReadback) dragPreview ?: fraction else fraction
    LaunchedEffect(dragPreview, fraction, enabled, deferredHardwareReadback) {
        val preview = dragPreview ?: return@LaunchedEffect
        if (!deferredHardwareReadback || !enabled || abs(preview - fraction) < 0.001f) {
            dragPreview = null
            return@LaunchedEffect
        }
        // The hardware readback owns final truth. This only prevents a Binder round-trip from making
        // the thumb appear stuck while the last-write-wins worker settles (or times out visibly).
        delay(2_000)
        if (dragPreview == preview) dragPreview = null
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(displayedFraction, 0f..1f, steps)
                if (enabled) {
                    setProgress { requested ->
                        val bounded = BrightnessMath.quantize(target, requested)
                        if (deferredHardwareReadback) dragPreview = bounded
                        onValueChange(bounded)
                        true
                    }
                }
            }
            .pointerInput(target, enabled) {
                if (!enabled) return@pointerInput
                // A tap, or a drag along the track past touch slop, moves the value; a swipe across it scrolls the list it sits in.
                detectSliderTouch(
                    onEngaged = { latestRequestFocus() },
                    emitAt = { x ->
                        if (size.width > 0) {
                            val requested = BrightnessMath.quantize(target, x / size.width.toFloat())
                            if (deferredHardwareReadback) dragPreview = requested
                            latestValueChange(requested)
                        }
                    },
                    onEnd = { latestInteractionEnd() },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fill = displayedFraction.coerceIn(0f, 1f)
        val track = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
        if (trackColors != null && trackColors.size >= 2) {
            Box(track.background(Brush.horizontalGradient(trackColors)))
        } else {
            Box(track.background(palette.surfaceHigh))
            if (fillFromFraction == null) {
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (enabled) palette.accent else palette.textMuted),
                )
            } else {
                // From the origin (the equalizer's 0 dB) to the thumb's centre, either way: a cut fills leftward, a boost rightward.
                val origin = fillFromFraction.coerceIn(0f, 1f)
                val travel = maxWidth - thumbSize
                val originX = travel * origin + thumbSize / 2
                val thumbX = travel * fill + thumbSize / 2
                Box(
                    Modifier
                        .offset(x = minOf(originX, thumbX))
                        .width(abs(thumbX.value - originX.value).dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (enabled) palette.accent else palette.textMuted),
                )
            }
        }
        Box(
            Modifier
                .offset(x = (maxWidth - thumbSize) * fill)
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor ?: if (enabled) palette.accentBright else palette.textMuted)
                // The row rings; the thumb keeps its rim only (ring migration, 2026-09-28) -- two rings lit on one
                // focus were one too many. The value label still turns accentBright with the row.
                .border(width = 1.dp, color = palette.surfaceRaised, shape = CircleShape),
        )
        if (markerFraction != null) {
            // The music panel's cap: a tick on the thumb's own centre line, taller than the thumb so it still reads when the
            // thumb has stopped on it.
            val markerWidth = 2.dp
            Box(
                Modifier
                    .offset(x = (maxWidth - thumbSize) * markerFraction.coerceIn(0f, 1f) + (thumbSize - markerWidth) / 2)
                    .size(width = markerWidth, height = 22.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(palette.textSecondary)
                    .clearAndSetSemantics { },
            )
        }
    }
}

@Composable
internal fun UtilityToggleRow(
    label: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    modifier: Modifier,
    onRequestFocus: () -> Unit,
    onToggle: () -> Unit,
    supportingMaxLines: Int = 2,
) {
    val shape = RoundedCornerShape(UtilityRowCorner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .mimirSelectionRing(focused = focused, identity = label, shape = MimirRingShape.Row(UtilityRowCorner))
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.48f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .clip(shape)
            // mimirActivatable, not clickable: `modifier` carries the host's mimirFocusable (one focus node).
            .mimirActivatable(label = label, enabled = enabled, role = null) {
                onRequestFocus()
                onToggle()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MimirChromeText(label, style = MimirTypeScale.MenuAction, color = palette.textPrimary, maxLines = 1)
            Text(
                supportingText,
                style = MimirTypeScale.Support,
                color = palette.textMuted,
                maxLines = supportingMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MimirChromeText(
            if (checked) "On" else if (enabled) "Off" else "Access",
            style = MimirTypeScale.MicroStatus,
            color = if (checked) palette.accentBright else palette.textSecondary,
        )
    }
}

@Composable
private fun UtilityDivider(palette: MimirThemePalette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.outline))
}

/**
 * The whole reach of a Utility action's focus ring past its plate: the settled halo ([MimirFocusTileRingReach], 14 dp) plus the arrival flare ([MimirFocusFlareReach], 6 dp), which the
 * actions ask for. What stands beside an action (the hint, the neighbouring action) is kept this far off, so a focused chip's halo never runs into either (the same room the earlier update gave
 * two other halos, 552a83d08).
 */
internal val UtilityActionRingReach: Dp = MimirFocusTileRingReach + MimirFocusFlareReach

/** The plate of the Utility action labelled [label]: what a layout test measures a halo from. */
internal fun utilityActionPlateTag(label: String): String = etchedChipPlateTag("utility", label)

/**
 * One footer action of the Utility and Tweaker panels (Close, Back, Allow, the privileged-access button): an etched chip.
 * The pad's focus is the shared selection ring on the PLATE itself, with
 * no inset and BEFORE the surface, so the ring is the plate's one edge and its wash is under the opaque metal; the plate then draws no rim while focused. [primary] is the screen's
 * main button (Allow): the same dark plate, its rim and word in the accent, never a fill. The plate sits INSIDE a hit box of at least 48 dp, so the target is larger than what is
 * drawn. The disabled dim sits on the plate after the ring (a ModulateAlpha layer dims per draw call and leaves the ring whole). It activates through [mimirActivatable], never
 * `clickable`: [modifier] carries the host's `mimirFocusable` (one focus node).
 */
@Composable
internal fun UtilityAction(
    label: String,
    primary: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .mimirActivatable(label = label, enabled = enabled, onActivate = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(
            modifier = Modifier
                .testTag(utilityActionPlateTag(label))
                // The one ring on an action (ring migration, 2026-09-28): the footer is at most two actions, and the ring flares on arrival.
                .mimirSelectionRing(
                    focused = focused,
                    identity = label,
                    shape = MimirRingShape.Pill,
                    flare = true,
                    edgeFlush = true,
                )
                .graphicsLayer {
                    alpha = if (enabled) 1f else 0.46f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            focused = focused,
            primary = primary,
            palette = palette,
            edgeFlush = true,
        ) {
            MimirChromeText(
                text = label,
                style = MimirEtchedChipLabelStyle,
                color = palette.etchedChipInk(primary = primary),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private fun percentLabel(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"

private fun joystickColorLabel(argb: Int, followsTheme: Boolean): String {
    if (followsTheme) return "THEME"
    if ((argb or (0xff shl 24)) == 0xffffffff.toInt()) return "WHITE"
    return "${(JoystickColorCodec.hueFraction(argb) * 360f).roundToInt()}°"
}


/** What a Utility/Tweaker layer last showed while live, so a departing layer dissolves as it was. */
private class UtilityLayerMemory(var focusedControl: BrightnessControlKey?, var backActionLabel: String)

/**
 * The Tweaker profile row's one transition: the daemon not having answered ([answerPending]) swapping with the daemon
 * having answered. The swap is keyed on that STATE, never on the words, so a step between profile names (D-pad left
 * or right, every press restarting a crossfade) snaps in place and ticks with `MimirNavFeedback`, the house rule for
 * stepper values. Only CHECKING… <-> a name, and the supporting line that goes with it, dissolve.
 */
@Composable
internal fun TweakerRowStateSwap(
    answerPending: Boolean,
    anchor: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    MimirHintSwap(key = answerPending, anchor = anchor, content = content)
}
