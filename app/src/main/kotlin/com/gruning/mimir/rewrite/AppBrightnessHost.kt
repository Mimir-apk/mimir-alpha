package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.MimirMessage

internal val BrightnessScope = FocusScope("brightness")

internal fun brightnessFocusKey(control: BrightnessControlKey): FocusKey =
    FocusKey("brightness/${control.name.lowercase()}")

/**
 * @param filesRouteAvailable defaults **true**, unlike every other optional row in this list: the Files
 *   route is the norm and `PrimaryMButtonPanelHost` is the single exception, so the exception names
 *   itself rather than every ordinary caller opting in. A default of `false` would silently drop the row
 *   from any call site that forgot it — the drawn-but-unreachable defect this function's own comments
 *   describe, inverted. See [filesRouteDecision]'s KDoc for what the `false` case protects against.
 *
 * Parameter documentation belongs HERE and not inline in the list below: `BrightnessControlCallSiteContractTest`
 * counts this declaration's arity by splitting the raw parameter text on commas **without stripping
 * comments**, so a comma inside an inline comment silently inflates the count and fails that gate.
 */
internal fun brightnessControls(
    state: BrightnessState,
    primaryMButtonControlsAvailable: Boolean = false,
    tweakerUsageAccessState: TweakerUsageAccessState = TweakerUsageAccessState.UNAVAILABLE,
    surface: UtilitySurface = UtilitySurface.UTILITY,
    tweakerManualFan: TweakerManualFanCapability? = null,
    tweakerAvailability: TweakerUtilityAvailability = TweakerUtilityAvailability.NOT_INSTALLED,
    tweakerFpsCapRow: TweakerFpsCapRow = TweakerFpsCapRow.NoSubject,
    filesRouteAvailable: Boolean = true,
): List<BrightnessControlKey> = buildList {
    if (surface == UtilitySurface.TWEAKER) {
        add(BrightnessControlKey.TWEAKER_PROFILE)
        // Only when a game is actually in front. A cap row with no subject is the app-wide slot
        // TweakerBridge deliberately retired -- it would write onto whatever launches next.
        if (TweakerFrameCapGate.enabled && tweakerFpsCapRow is TweakerFpsCapRow.Available) {
            add(BrightnessControlKey.TWEAKER_FPS_CAP)
        }
        if (tweakerManualFan != null) {
            add(BrightnessControlKey.TWEAKER_FAN_MODE)
            if (tweakerManualFan.currentMode == TweakerFanMode.MANUAL) {
                add(BrightnessControlKey.TWEAKER_FAN_CURVE)
            }
        }
        add(BrightnessControlKey.PERFORMANCE_OVERLAY)
        if (tweakerUsageAccessState == TweakerUsageAccessState.SET_UP) {
            add(BrightnessControlKey.TWEAKER_USAGE_ACCESS)
        }
        add(BrightnessControlKey.CLOSE)
        return@buildList
    }
    //  -- the two ROUTES
    // lead the rail, above every setting, because they are the only rows that leave Utility for
    // somewhere else. Both used to sit at the tail, so reaching Files meant paging past display,
    // joystick, sound and fan rows. Moved here and in BrightnessOverlay together: see the index note
    // below, which is exactly what a one-sided move breaks.
    //
    // Offered only where there is something to route to; a device with no Tweaker carries no dead row.
    if (tweakerRoutePaired(tweakerAvailability)) add(BrightnessControlKey.OPEN_TWEAKER)
    // Below the `surface == TWEAKER` early return above, which is what keeps the file manager out of the
    // Tweaker panel -- pinned by UtilityControlOrderTest.theFilesRouteBelongsToUtilityAndNotToTheTweakerPanel.
    if (filesRouteAvailable) add(BrightnessControlKey.OPEN_FILES)
    add(BrightnessControlKey.PRIMARY)
    // This list is the focus/navigation projection of what BrightnessOverlay actually renders. Keep
    // read-only hardware rows in the ring: omitting them here while still drawing them made the rail's
    // computed terminal item differ from its visual terminal item, so END reveal could scroll the
    // focused row away on fixed-volume or temporarily unavailable vendor-control states.
    if (state.capabilities.showsSecondary) add(BrightnessControlKey.SECONDARY)
    add(BrightnessControlKey.SCREEN_TIMEOUT)
    if (primaryMButtonControlsAvailable) add(BrightnessControlKey.M_BUTTON_VISIBILITY)
    add(BrightnessControlKey.NAVIGATION_HINTS)
    // Calm shine : Utility's switch for the focus shine. Unconditional, like Navigation hints, so
    // this list keeps its parameters; BrightnessOverlay draws it right after that row (the rail reveals by index).
    add(BrightnessControlKey.CALM_SHINE)
    if (state.capabilities.joystickLightSupported) add(BrightnessControlKey.JOYSTICK_LIGHT)
    if (state.capabilities.joystickLightColorSupported) add(BrightnessControlKey.JOYSTICK_COLOR)
    // CF01: THEME_AWARE_LIGHT is never offered — the joystick theme-follow feature was discarded.
    if (state.capabilities.shouldOfferPrivilegedAccess) add(BrightnessControlKey.PRIVILEGED_ACCESS)
    if (state.capabilities.mimirHapticsSupported) add(BrightnessControlKey.MIMIR_HAPTIC)
    if (state.capabilities.navSoundSupported) add(BrightnessControlKey.NAV_SOUND)
    if (state.capabilities.ambientSoundSupported) add(BrightnessControlKey.AMBIENT_SOUND)
    // Reachable by controller, not merely drawn: a row absent from this list renders and can never
    // be focused. Its position here must also match where it RENDERS -- the rail reveals by index,
    // so a row listed early and drawn late swallows the press that should have reached it. That is
    // why the two routes above moved in BOTH files at once.
    //
    // Device media volume is the rail's terminal row again, a seat the Files route took from it when
    // Files was added at the tail and has now given back. UtilityRailReveal.END follows the computed
    // last item, so this must stay the last row BrightnessOverlay draws.
    if (state.capabilities.mediaVolumeSupported) add(BrightnessControlKey.MEDIA_VOLUME)
    if (state.capabilities.shouldOfferMainWriteAccess) add(BrightnessControlKey.ALLOW_SYSTEM_CHANGES)
    add(BrightnessControlKey.CLOSE)
}

/**
 * Where a surface puts focus when it opens.
 *
 * Utility lands on its first row. Moving Tweaker and Files routes above brightness changes the
 * row order independently of the initial focus; a stray Left or Right on arrival must not change
 * brightness. The slider remains a press or two down.
 *
 * The Tweaker surface still lands on its own first control, its profile row.
 */
internal fun brightnessLandingControl(
    controls: List<BrightnessControlKey>,
    surface: UtilitySurface,
): BrightnessControlKey? = when (surface) {
    UtilitySurface.TWEAKER -> controls.firstOrNull()
    UtilitySurface.UTILITY -> controls.firstOrNull()
}

/**
 * F15 (product review 2026-09-28; rg-rotate-7, eu-nova-12): B from the Tweaker is a route RETURN, so Utility gives
 * focus back to the row that opened the Tweaker instead of landing on the brightness slider again, where a stray
 * Left or Right changes brightness. Null for anything that is not that return; where Utility lands on a plain open
 * stays [brightnessLandingControl]'s decision (its first row since OR-6).
 */
internal fun brightnessReturnLandingControl(
    controls: List<BrightnessControlKey>,
    surface: UtilitySurface,
    previousSurface: UtilitySurface?,
): BrightnessControlKey? = BrightnessControlKey.OPEN_TWEAKER.takeIf {
    surface == UtilitySurface.UTILITY && previousSurface == UtilitySurface.TWEAKER && it in controls
}

/** Controls inside the scrolling rail; fixed-footer actions never participate in rail reveal. */
internal fun brightnessRailControls(
    state: BrightnessState,
    primaryMButtonControlsAvailable: Boolean = false,
    tweakerUsageAccessState: TweakerUsageAccessState = TweakerUsageAccessState.UNAVAILABLE,
    surface: UtilitySurface = UtilitySurface.UTILITY,
    tweakerManualFan: TweakerManualFanCapability? = null,
    tweakerAvailability: TweakerUtilityAvailability = TweakerUtilityAvailability.NOT_INSTALLED,
    tweakerFpsCapRow: TweakerFpsCapRow = TweakerFpsCapRow.NoSubject,
    filesRouteAvailable: Boolean = true,
): List<BrightnessControlKey> =
    brightnessControls(
        state,
        primaryMButtonControlsAvailable,
        tweakerUsageAccessState,
        surface,
        tweakerManualFan,
        tweakerAvailability,
        tweakerFpsCapRow,
        filesRouteAvailable,
    ).filterNot { control ->
        control == BrightnessControlKey.ALLOW_SYSTEM_CHANGES || control == BrightnessControlKey.CLOSE
    }

@Composable
internal fun AppBrightnessHost(
    state: BrightnessState,
    authority: FocusAuthority,
    onSetFraction: (BrightnessTarget, Float) -> Unit,
    onSetThemeAwareJoystickLight: (Boolean) -> Unit,
    onRequestWriteAccess: () -> Unit,
    onRequestPrivilegedAccess: () -> Unit,
    onOpenTweaker: () -> Unit,
    onOpenFiles: () -> Unit = {},
    filesRouteAvailable: Boolean = true,
    allFilesAccessGranted: Boolean = false,
    tweakerFpsCapRow: TweakerFpsCapRow,
    onStepTweakerFpsCap: (Int) -> Unit,
    primaryMButtonControlsAvailable: Boolean,
    mButtonVisibilityPolicy: MButtonVisibilityPolicy,
    onStepMButtonVisibilityPolicy: (Int) -> Unit,
    navigationHintVisibility: NavigationHintVisibility,
    onStepNavigationHintVisibility: (Int) -> Unit,
    calmShine: Boolean = false,
    onSetCalmShine: (Boolean) -> Unit = {},
    tweakerRow: TweakerUtilityRowState = DefaultTweakerUtilityRow,
    tweakerFanGpuTempC: Int? = null,
    tweakerFanCpuTempC: Int? = null,
    tweakerProfileChange: TweakerProfileChangeState? = null,
    tweakerFanControlChange: TweakerFanControlChangeState? = null,
    onStepTweakerProfile: (Int) -> Boolean = { false },
    tweakerProfilePreviewId: String? = null,
    tweakerCeilingPreviewLabel: String? = null,
    tweakerCeilingDraftPending: Boolean = false,
    tweakerCeilingPreviewReleasesHold: Boolean = false,
    tweakerProfileDraftPending: Boolean = false,
    onApplyTweakerProfile: () -> Boolean = { false },
    onCancelTweakerProfile: () -> Boolean = { false },
    tweakerManualFan: TweakerManualFanCapability? = null,
    onStepTweakerFanMode: (Int) -> Boolean = { false },
    onStepTweakerFanCurve: (Int) -> Boolean = { false },
    tweakerFanCurvePreviewBias: Int? = null,
    tweakerFanCurveDraftPending: Boolean = false,
    onPreviewTweakerFanCurve: (Int) -> Unit = {},
    onApplyTweakerFanCurve: () -> Boolean = { false },
    onCancelTweakerFanCurve: () -> Boolean = { false },
    performanceOverlayVisibility: PerformanceOverlayVisibility = PerformanceOverlayVisibility.OFF,
    performanceOverlayMode: PerformanceOverlayMode = PerformanceOverlayMode.CLASSIC,
    performanceOverlayRowValue: PerformanceOverlayRowValue = PerformanceOverlayRowValue.UNAVAILABLE,
    onStepPerformanceOverlay: (Int) -> Unit = {},
    tweakerUsageAccessState: TweakerUsageAccessState = TweakerUsageAccessState.UNAVAILABLE,
    onRequestTweakerUsageAccess: () -> Unit = {},
    surface: UtilitySurface = UtilitySurface.UTILITY,
    tweakerMessage: MimirMessage? = null,
    onTweakerMessageDelivered: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    onCancelTweakerMutation: () -> Unit = onBack,
    backActionLabel: String = "Close",
    focusEnabled: Boolean = true,
    enableBackHandler: Boolean = true,
    settledOnMount: Boolean = false,
    holdPad: Boolean = true,
) {
    val focus by authority.state.collectAsState()
    val focusedControl = BrightnessControlKey.entries.firstOrNull {
        brightnessFocusKey(it) == focus.focused
    }
    val visibleControls = brightnessControls(
        state,
        primaryMButtonControlsAvailable,
        tweakerUsageAccessState,
        surface,
        tweakerManualFan,
        tweakerRow.availability,
        tweakerFpsCapRow,
        filesRouteAvailable,
    )
    // Utility and Tweaker are tool panels: entering one, or crossing between them, lands on its first
    // option rather than wherever the player last was.
    //
    // This runs here and not in open() because FocusAuthority.requestFocus silently drops a key that is
    // not registered yet (DefaultFocusAuthority.kt:113), and the incoming surface's rows only register
    // when this composition runs. open()'s request was therefore a no-op, leaving focus on the single
    // control registered across the swap -- the footer's CLOSE, which is in BOTH surfaces' lists, so the
    // corrective effect below saw nothing wrong. Keyed on surface, and the host is only in composition
    // while the panel is open, so this fires exactly once per open and once per surface change.
    // F15: the surface shown before this one, so a return from the Tweaker can give focus back to its row.
    // A plain holder rather than state: nothing is drawn from it, so writing it must not recompose.
    val previousSurfaceHolder = remember { arrayOfNulls<UtilitySurface>(1) }
    LaunchedEffect(authority, surface, focusEnabled) {
        val previousSurface = previousSurfaceHolder[0]
        previousSurfaceHolder[0] = surface
        if (!focusEnabled || authority.state.value.scope != BrightnessScope) return@LaunchedEffect
        val landing = brightnessReturnLandingControl(visibleControls, surface, previousSurface)
            ?: brightnessLandingControl(visibleControls, surface)
        landing?.let {
            // RC-50: a request for a row that has not registered yet is dropped. The memory beside it is what the first registration
            // seeds, so the panel never rests on a row a past visit left behind while it waits for the landing row to mount.
            authority.rememberFocusForScope(BrightnessScope, brightnessFocusKey(it))
            authority.requestFocus(brightnessFocusKey(it))
        }
    }
    LaunchedEffect(authority, visibleControls, focusEnabled) {
        if (!focusEnabled || authority.state.value.scope != BrightnessScope) return@LaunchedEffect
        val current = BrightnessControlKey.entries.firstOrNull {
            brightnessFocusKey(it) == authority.state.value.focused
        }
        if (current !in visibleControls) {
            authority.requestFocus(brightnessFocusKey(visibleControls.first()))
        }
    }
    MimirBrightnessOverlay(
        state = state,
        focusedControl = focusedControl,
        onSetFraction = onSetFraction,
        onSetThemeAwareJoystickLight = onSetThemeAwareJoystickLight,
        onRequestWriteAccess = onRequestWriteAccess,
        onRequestPrivilegedAccess = onRequestPrivilegedAccess,
        onOpenTweaker = onOpenTweaker,
        onOpenFiles = onOpenFiles,
        filesRouteAvailable = filesRouteAvailable,
        allFilesAccessGranted = allFilesAccessGranted,
        tweakerFpsCapRow = tweakerFpsCapRow,
        onStepTweakerFpsCap = onStepTweakerFpsCap,
        primaryMButtonControlsAvailable = primaryMButtonControlsAvailable,
        mButtonVisibilityPolicy = mButtonVisibilityPolicy,
        onStepMButtonVisibilityPolicy = onStepMButtonVisibilityPolicy,
        navigationHintVisibility = navigationHintVisibility,
        onStepNavigationHintVisibility = onStepNavigationHintVisibility,
        calmShine = calmShine,
        onSetCalmShine = onSetCalmShine,
        tweakerRow = tweakerRow,
        tweakerFanGpuTempC = tweakerFanGpuTempC,
        tweakerFanCpuTempC = tweakerFanCpuTempC,
        tweakerProfileChange = tweakerProfileChange,
        tweakerFanControlChange = tweakerFanControlChange,
        onStepTweakerProfile = onStepTweakerProfile,
        tweakerProfilePreviewId = tweakerProfilePreviewId,
        tweakerCeilingPreviewLabel = tweakerCeilingPreviewLabel,
        tweakerCeilingDraftPending = tweakerCeilingDraftPending,
        tweakerCeilingPreviewReleasesHold = tweakerCeilingPreviewReleasesHold,
        tweakerProfileDraftPending = tweakerProfileDraftPending,
        onApplyTweakerProfile = { onApplyTweakerProfile() },
        onCancelTweakerProfile = { onCancelTweakerProfile() },
        tweakerManualFan = tweakerManualFan,
        onStepTweakerFanMode = onStepTweakerFanMode,
        tweakerFanCurvePreviewBias = tweakerFanCurvePreviewBias,
        tweakerFanCurveDraftPending = tweakerFanCurveDraftPending,
        onPreviewTweakerFanCurve = onPreviewTweakerFanCurve,
        onApplyTweakerFanCurve = { onApplyTweakerFanCurve() },
        onCancelTweakerFanCurve = { onCancelTweakerFanCurve() },
        performanceOverlayVisibility = performanceOverlayVisibility,
        performanceOverlayMode = performanceOverlayMode,
        performanceOverlayRowValue = performanceOverlayRowValue,
        onStepPerformanceOverlay = onStepPerformanceOverlay,
        tweakerUsageAccessState = tweakerUsageAccessState,
        onRequestTweakerUsageAccess = onRequestTweakerUsageAccess,
        surface = surface,
        tweakerMessage = tweakerMessage,
        onTweakerMessageDelivered = onTweakerMessageDelivered,
        onBack = onBack,
        onClose = onDismiss,
        onCancelTweakerMutation = onCancelTweakerMutation,
        backActionLabel = backActionLabel,
        controllerHintsAvailable = focusEnabled,
        enableBackHandler = enableBackHandler,
        settledOnMount = settledOnMount,
        holdPad = holdPad,
        onRequestFocus = { control -> if (focusEnabled) authority.requestFocus(brightnessFocusKey(control)) },
        controlModifier = { control ->
            if (focusEnabled && control in visibleControls) {
                Modifier.mimirFocusable(
                    authority = authority,
                    key = brightnessFocusKey(control),
                    scope = BrightnessScope,
                    // BrightnessOverlay owns one bounded rail and its terminal/header reveal rules.
                    // A second automatic requester races that scroll and can hide the section heading.
                    autoBringIntoView = false,
                )
            } else {
                Modifier
            }
        },
    )
}

internal fun dispatchBrightnessOverlay(
    authority: FocusAuthority,
    state: BrightnessState,
    action: ControllerAction,
    onStep: (BrightnessTarget, Int) -> Unit,
    onSetThemeAwareJoystickLight: (Boolean) -> Unit,
    onRequestWriteAccess: () -> Unit,
    onRequestPrivilegedAccess: () -> Unit,
    // Defaulted so the many focus-order tests need not restate a route they do not exercise;
    // the production call site in AppBrightnessCoordinator passes it explicitly.
    onOpenTweaker: () -> Unit = {},
    /**
     * Defaulted for the same reason [onOpenTweaker] is: the many focus-order tests need not restate a
     * route they do not exercise. `UtilityControlOrderTest.everyRailRowDoesSomethingWhenConfirmed` is
     * what stops that default hiding an inert row -- it presses CONFIRM on every rail row and fails
     * unless something fires.
     */
    onOpenFiles: () -> Unit = {},
    filesRouteAvailable: Boolean = true,
    tweakerAvailability: TweakerUtilityAvailability = TweakerUtilityAvailability.NOT_INSTALLED,
    tweakerFpsCapRow: TweakerFpsCapRow = TweakerFpsCapRow.NoSubject,
    onStepTweakerFpsCap: (Int) -> Unit = {},
    primaryMButtonControlsAvailable: Boolean = false,
    mButtonVisibilityPolicy: MButtonVisibilityPolicy = MButtonVisibilityPolicy.DISABLED,
    onStepMButtonVisibilityPolicy: (Int) -> Unit = {},
    navigationHintVisibility: NavigationHintVisibility = NavigationHintVisibility.ON,
    onStepNavigationHintVisibility: (Int) -> Unit = {},
    calmShine: Boolean = false,
    onSetCalmShine: (Boolean) -> Unit = {},
    /**
     * Returns whether the press moved the profile DRAFT. This dispatcher deliberately does not pre-judge
     * that — the coordinator owns the single decision, and also owns telling the player why a press was a
     * no-op. All this needs back is whether to fire nav feedback.
     */
    onStepTweakerProfile: (Int) -> Boolean = { false },
    tweakerProfileDraftPending: Boolean = false,
    onApplyTweakerProfile: () -> Boolean = { false },
    onCancelTweakerProfile: () -> Boolean = { false },
    tweakerManualFan: TweakerManualFanCapability? = null,
    onStepTweakerFanMode: (Int) -> Boolean = { false },
    onStepTweakerFanCurve: (Int) -> Boolean = { false },
    tweakerFanCurveDraftPending: Boolean = false,
    onApplyTweakerFanCurve: () -> Boolean = { false },
    onCancelTweakerFanCurve: () -> Boolean = { false },
    tweakerProfileChangeInProgress: Boolean = false,
    tweakerFanControlChangeInProgress: Boolean = false,
    performanceOverlayVisibility: PerformanceOverlayVisibility = PerformanceOverlayVisibility.OFF,
    onStepPerformanceOverlay: (Int) -> Unit = {},
    tweakerUsageAccessState: TweakerUsageAccessState = TweakerUsageAccessState.UNAVAILABLE,
    onRequestTweakerUsageAccess: () -> Unit = {},
    surface: UtilitySurface = UtilitySurface.UTILITY,
    onDismiss: () -> Unit,
): Boolean {
    val tweakerMutationPending = surface == UtilitySurface.TWEAKER &&
        (tweakerProfileChangeInProgress || tweakerFanControlChangeInProgress)
    if (tweakerMutationPending) {
        // Policy: the settlement layer owns every action, including Back. The committed panel,
        // FocusAuthority, and scroll position stay untouched until authoritative terminal readback.
        return true
    }
    // One pending decision at a time, and the row that owns it owns Back. Identical to the two rows
    // below it: leaving a drafted row without deciding would hide an uncommitted choice off-screen.
    if (tweakerProfileDraftPending) {
        when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> return true
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                onCancelTweakerProfile()
                return true
            }
            else -> Unit
        }
    }
    if (tweakerFanCurveDraftPending) {
        when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> return true
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                onCancelTweakerFanCurve()
                return true
            }
            else -> Unit
        }
    }
    val controls = brightnessControls(
        state,
        primaryMButtonControlsAvailable,
        tweakerUsageAccessState,
        surface,
        tweakerManualFan,
        // Must match railControls below. `focused` is only honoured if it appears in THIS list;
        // a row missing here silently falls back to controls.first() and the press lands on the
        // wrong row. That is how the Tweaker route shipped focusable and inert.
        tweakerAvailability,
        tweakerFpsCapRow,
        filesRouteAvailable,
    )
    val footerControls = buildList {
        if (surface == UtilitySurface.UTILITY && state.capabilities.shouldOfferMainWriteAccess) {
            add(BrightnessControlKey.ALLOW_SYSTEM_CHANGES)
        }
        add(BrightnessControlKey.CLOSE)
    }
    val railControls = brightnessRailControls(
        state,
        primaryMButtonControlsAvailable,
        tweakerUsageAccessState,
        surface,
        tweakerManualFan,
        tweakerAvailability,
        tweakerFpsCapRow,
        filesRouteAvailable,
    )
    val focusedCandidate = BrightnessControlKey.entries.firstOrNull {
        brightnessFocusKey(it) == authority.state.value.focused
    }
    val focused = focusedCandidate?.takeIf { it in controls } ?: controls.first()
    return when (action) {
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
            val target = when {
                focused in footerControls && action == ControllerAction.NAV_UP -> railControls.lastOrNull()
                focused in footerControls -> focused
                focused in railControls && action == ControllerAction.NAV_DOWN && focused == railControls.last() ->
                    footerControls.firstOrNull()
                focused in railControls -> {
                    val current = railControls.indexOf(focused)
                    val delta = if (action == ControllerAction.NAV_UP) -1 else 1
                    railControls[(current + delta).coerceIn(0, railControls.lastIndex)]
                }
                else -> controls.firstOrNull()
            }
            target?.let { authority.requestFocus(brightnessFocusKey(it)) }
            true
        }
        ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT -> {
            if (focused in footerControls) {
                val current = footerControls.indexOf(focused)
                val delta = if (action == ControllerAction.NAV_LEFT) -1 else 1
                authority.requestFocus(
                    brightnessFocusKey(footerControls[(current + delta).coerceIn(0, footerControls.lastIndex)]),
                )
            } else if (focused == BrightnessControlKey.M_BUTTON_VISIBILITY) {
                val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                if (mButtonVisibilityPolicy.step(direction) != mButtonVisibilityPolicy) {
                    MimirNavFeedback.consumed()
                    onStepMButtonVisibilityPolicy(direction)
                }
            } else if (focused == BrightnessControlKey.NAVIGATION_HINTS) {
                val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                if (navigationHintVisibility.step(direction) != navigationHintVisibility) {
                    MimirNavFeedback.consumed()
                    onStepNavigationHintVisibility(direction)
                }
            } else if (focused == BrightnessControlKey.CALM_SHINE) {
                // A two-value switch: Right turns it on, Left turns it off, and a press past either end is silent.
                val enable = action == ControllerAction.NAV_RIGHT
                if (enable != calmShine) {
                    MimirNavFeedback.consumed()
                    onSetCalmShine(enable)
                }
            } else if (focused == BrightnessControlKey.TWEAKER_PROFILE) {
                // Unlike the rows above, this one is NEVER swallowed at a boundary: an unreachable Tweaker
                // or a device with nothing certified beyond stock still owes the player an honest line, and
                // only the coordinator can say which. Feedback still rides a real change only.
                //
                // No settlement guard here: this moves a draft and starts no transaction, so the guard that
                // exists to prevent overlapping transactions has nothing to protect. CONFIRM still carries it.
                val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                if (onStepTweakerProfile(direction)) MimirNavFeedback.consumed()
            } else if (focused == BrightnessControlKey.TWEAKER_FAN_MODE) {
                if (!tweakerProfileChangeInProgress && !tweakerFanControlChangeInProgress) {
                    val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                    if (onStepTweakerFanMode(direction)) MimirNavFeedback.consumed()
                }
            } else if (focused == BrightnessControlKey.TWEAKER_FAN_CURVE) {
                if (!tweakerProfileChangeInProgress && !tweakerFanControlChangeInProgress) {
                    val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                    if (onStepTweakerFanCurve(direction)) MimirNavFeedback.consumed()
                }
            } else if (focused == BrightnessControlKey.PERFORMANCE_OVERLAY) {
                val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                MimirNavFeedback.consumed()
                onStepPerformanceOverlay(direction)
            } else if (focused == BrightnessControlKey.TWEAKER_FPS_CAP) {
                val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                MimirNavFeedback.consumed()
                onStepTweakerFpsCap(direction)
            } else {
                focused.toTargetOrNull()?.takeIf(state::canAdjustUtilityTarget)?.let { target ->
                    val direction = if (action == ControllerAction.NAV_LEFT) -1 else 1
                    // BM03: a slider step is a real action that never moves app focus — record it so
                    // the router's tick gate hears it. A non-adjustable row stays silent.
                    MimirNavFeedback.consumed()
                    onStep(target, direction)
                }
            }
            true
        }
        ControllerAction.CONFIRM -> {
            // No `else`: every key is listed, so a new rail row shows up as a missing-branch warning
            // here instead of silently doing nothing on press. UtilityRailContractTest is the hard
            // gate: UtilityControlOrderTest.everyRailRowDoesSomethingWhenConfirmed presses CONFIRM on
            // every rail row and fails if nothing fires.
            when (focused) {
                BrightnessControlKey.THEME_AWARE_LIGHT -> {
                    val enable = if (state.themeAwareJoystickLightRestorePending) {
                        false
                    } else {
                        !state.themeAwareJoystickLightRequested
                    }
                    onSetThemeAwareJoystickLight(enable)
                }
                BrightnessControlKey.PRIVILEGED_ACCESS -> onRequestPrivilegedAccess()
                BrightnessControlKey.OPEN_TWEAKER -> onOpenTweaker()
                BrightnessControlKey.OPEN_FILES -> onOpenFiles()
                // A press with no direction nudges forward, matching the other value rows.
                BrightnessControlKey.TWEAKER_FPS_CAP -> onStepTweakerFpsCap(1)
                BrightnessControlKey.M_BUTTON_VISIBILITY -> {
                    if (mButtonVisibilityPolicy.step(1) != mButtonVisibilityPolicy) {
                        onStepMButtonVisibilityPolicy(1)
                    }
                }
                BrightnessControlKey.NAVIGATION_HINTS -> {
                    if (navigationHintVisibility.step(1) != navigationHintVisibility) {
                        onStepNavigationHintVisibility(1)
                    }
                }
                // A switch, so a press flips it both ways (QA-21: a press that only stepped forward left a two-value
                // row inert once it was on).
                BrightnessControlKey.CALM_SHINE -> onSetCalmShine(!calmShine)
                // Confirm APPLIES the draft — the row's only transaction — exactly as it does on the
                // Fan response row below it. With nothing drafted it is inert.
                BrightnessControlKey.TWEAKER_PROFILE -> {
                    if (
                        tweakerProfileDraftPending &&
                        !tweakerProfileChangeInProgress &&
                        !tweakerFanControlChangeInProgress
                    ) {
                        onApplyTweakerProfile()
                    }
                }
                BrightnessControlKey.TWEAKER_FAN_MODE -> {
                    if (!tweakerProfileChangeInProgress && !tweakerFanControlChangeInProgress) {
                        onStepTweakerFanMode(1)
                    }
                }
                BrightnessControlKey.TWEAKER_FAN_CURVE -> {
                    if (
                        tweakerFanCurveDraftPending &&
                        !tweakerProfileChangeInProgress &&
                        !tweakerFanControlChangeInProgress
                    ) {
                        onApplyTweakerFanCurve()
                    }
                }
                BrightnessControlKey.PERFORMANCE_OVERLAY -> onStepPerformanceOverlay(0)
                BrightnessControlKey.TWEAKER_USAGE_ACCESS -> onRequestTweakerUsageAccess()
                // QA-21: was `step(1)`, which on a two-value setting meant CONFIRM could turn this ON
                // and then never off again -- the row went inert under A once enabled, with LEFT the only
                // way back. It is a toggle now, in the dispatch as well as in the render.
                BrightnessControlKey.ALLOW_SYSTEM_CHANGES -> onRequestWriteAccess()
                BrightnessControlKey.CLOSE -> onDismiss()
                // Sliders and value rows: CONFIRM is deliberately inert, they act on LEFT/RIGHT.
                BrightnessControlKey.PRIMARY,
                BrightnessControlKey.SECONDARY,
                BrightnessControlKey.SCREEN_TIMEOUT,
                BrightnessControlKey.JOYSTICK_LIGHT,
                BrightnessControlKey.JOYSTICK_COLOR,
                BrightnessControlKey.MIMIR_HAPTIC,
                BrightnessControlKey.NAV_SOUND,
                BrightnessControlKey.AMBIENT_SOUND,
                BrightnessControlKey.MEDIA_VOLUME,
                -> Unit
            }
            true
        }
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
            onDismiss()
            true
        }
        else -> true
    }
}

internal fun BrightnessState.canAdjustUtilityTarget(target: BrightnessTarget): Boolean = when (target) {
    BrightnessTarget.PRIMARY -> true
    BrightnessTarget.SECONDARY -> capabilities.canAdjustSecondary
    BrightnessTarget.SCREEN_TIMEOUT -> true
    BrightnessTarget.JOYSTICK_LIGHT -> capabilities.canAdjustJoystickLight
    BrightnessTarget.JOYSTICK_COLOR ->
        capabilities.canAdjustJoystickColor && !themeAwareJoystickLightRequested
    BrightnessTarget.MIMIR_HAPTIC -> capabilities.mimirHapticsSupported
    BrightnessTarget.NAV_SOUND -> capabilities.navSoundSupported
    BrightnessTarget.AMBIENT_SOUND -> capabilities.ambientSoundSupported
    BrightnessTarget.MEDIA_VOLUME -> capabilities.canAdjustMediaVolume
    // The equalizer editor's slider shape: not a Utility control.
    BrightnessTarget.EQ_GAIN -> false
}

private fun BrightnessControlKey.toTargetOrNull(): BrightnessTarget? = when (this) {
    BrightnessControlKey.PRIMARY -> BrightnessTarget.PRIMARY
    BrightnessControlKey.SECONDARY -> BrightnessTarget.SECONDARY
    BrightnessControlKey.SCREEN_TIMEOUT -> BrightnessTarget.SCREEN_TIMEOUT
    BrightnessControlKey.M_BUTTON_VISIBILITY -> null
    BrightnessControlKey.NAVIGATION_HINTS -> null
    BrightnessControlKey.CALM_SHINE -> null
    BrightnessControlKey.JOYSTICK_LIGHT -> BrightnessTarget.JOYSTICK_LIGHT
    BrightnessControlKey.JOYSTICK_COLOR -> BrightnessTarget.JOYSTICK_COLOR
    BrightnessControlKey.MIMIR_HAPTIC -> BrightnessTarget.MIMIR_HAPTIC
    BrightnessControlKey.NAV_SOUND -> BrightnessTarget.NAV_SOUND
    BrightnessControlKey.AMBIENT_SOUND -> BrightnessTarget.AMBIENT_SOUND
    BrightnessControlKey.MEDIA_VOLUME -> BrightnessTarget.MEDIA_VOLUME
    BrightnessControlKey.OPEN_TWEAKER,
    BrightnessControlKey.OPEN_FILES,
    BrightnessControlKey.TWEAKER_FPS_CAP,
    BrightnessControlKey.THEME_AWARE_LIGHT,
    BrightnessControlKey.TWEAKER_PROFILE,
    BrightnessControlKey.TWEAKER_FAN_MODE,
    BrightnessControlKey.TWEAKER_FAN_CURVE,
    BrightnessControlKey.PERFORMANCE_OVERLAY,
    BrightnessControlKey.TWEAKER_USAGE_ACCESS,
    BrightnessControlKey.PRIVILEGED_ACCESS,
    BrightnessControlKey.ALLOW_SYSTEM_CHANGES,
    BrightnessControlKey.CLOSE,
    -> null
}
