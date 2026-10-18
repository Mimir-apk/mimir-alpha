package com.gruning.mimir.rewrite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gruning.mimir.rewrite.core.files.PermissionHelper
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerLastRestore
import com.gruning.mimir.rewrite.core.tweaker.TweakerThermalDerate
import com.gruning.mimir.rewrite.core.ui.MimirCalmShineSettings
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityStepOutcome
import com.gruning.mimir.rewrite.core.tweaker.TweakerCeilingApplyDecision
import com.gruning.mimir.rewrite.core.tweaker.TweakerCeilingDraft
import com.gruning.mimir.rewrite.core.tweaker.steppedTweakerCeilingDraftOutcome
import com.gruning.mimir.rewrite.core.tweaker.tweakerCeilingApplyDecision
import com.gruning.mimir.rewrite.core.tweaker.tweakerCeilingDraftFor
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerAdvancedCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlAssessment
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_STOCK_PROFILE_ID
import com.gruning.mimir.rewrite.core.tweaker.stepOutcome
import com.gruning.mimir.rewrite.core.tweaker.tweakerProfileDisplayCaps
import com.gruning.mimir.rewrite.core.tweaker.utilityToastCaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

internal enum class TweakerProfileChangePhase {
    APPLYING,
    CANCELLING,
}

internal data class TweakerProfileChangeState(
    val operationId: Long,
    val priorProfileId: String,
    val requestedProfileId: String,
    val phase: TweakerProfileChangePhase,
)

internal fun TweakerProfileChangeState.supportingTextCaps(): String = when (phase) {
    TweakerProfileChangePhase.APPLYING ->
        "APPLYING · ${tweakerProfileDisplayCaps(requestedProfileId)}"
    TweakerProfileChangePhase.CANCELLING ->
        "RESTORING · ${tweakerProfileDisplayCaps(priorProfileId)}"
}

internal data class TweakerFanControlChangeState(
    val operationId: Long,
    val priorMode: TweakerFanMode,
    val requestedMode: TweakerFanMode,
    val priorCurveBias: Int,
    val requestedCurveBias: Int,
)

internal fun TweakerFanControlChangeState.supportingTextCaps(): String = when {
    requestedMode == TweakerFanMode.AUTO -> "APPLYING · FAN AUTO"
    priorMode == TweakerFanMode.AUTO -> "APPLYING · MANUAL FAN"
    requestedCurveBias != priorCurveBias -> "APPLYING · FAN RESPONSE"
    else -> "APPLYING · MANUAL FAN"
}

/**
 * One Compose commit for profile truth and its matching typed fan capability.
 *
 * [canDrawOverlays] is [Settings.canDrawOverlays] read fresh on every projection (see
 * [AppBrightnessCoordinator.projectTweakerUtilityState]) so the performance-overlay row can tell a revoked
 * "Display over other apps" grant apart from a merely-off preference — see [performanceOverlayRowValue].
 */
internal data class TweakerPanelProjection(
    val row: TweakerUtilityRowState,
    val advancedCapabilities: TweakerAdvancedCapabilities?,
    val usageAccessState: TweakerUsageAccessState,
    val canDrawOverlays: Boolean,
)

/**
 * A profile preview is UI-only until the player explicitly applies it.
 *
 * The PROFILE row was the last row on this rail that committed on every step, so travelling
 * Stock → Performance paid three complete Guardian settlements to reach one destination. Drafting
 * makes stepping free and leaves exactly one transaction — the one that was always necessary — at
 * the moment the player confirms. Nothing about that transaction's verification window changes.
 */
internal data class TweakerProfileDraft(
    val previewProfileId: String,
) {
    /**
     * A preview survives only while the visible row still admits the profile it names. A row that
     * loses READY, loses the profile's qualification, or becomes held by an unfinished restore, must
     * retire the preview: confirm may never apply something the row can no longer promise, and must
     * never substitute a different profile.
     */
    fun admitted(row: TweakerUtilityRowState): Boolean =
        row.availability == TweakerUtilityAvailability.READY &&
            !row.heldByUnfinishedRestore &&
            previewProfileId in row.selectableProfileIds
}

/**
 * Steps the preview, not the device.
 *
 * The certified [stepOutcome] decision is reused unchanged by asking the row what it would say from
 * the previewed position instead of the live one, so an absent Tweaker or a stock-only device still
 * answers with its own honest line rather than being swallowed by a new code path.
 */
internal fun steppedTweakerProfileDraftOutcome(
    current: TweakerProfileDraft?,
    row: TweakerUtilityRowState,
    direction: Int,
): TweakerUtilityStepOutcome {
    val from = current?.takeIf { it.admitted(row) }?.previewProfileId ?: row.effectiveProfileId
    return renderedTweakerStepOutcome(row.copy(selectedProfileId = from), direction)
}

/** Stepping back onto the running profile leaves nothing to decide, so it leaves no draft. */
internal fun tweakerProfileDraftFor(
    row: TweakerUtilityRowState,
    steppedProfileId: String,
): TweakerProfileDraft? =
    if (steppedProfileId == row.effectiveProfileId) null else TweakerProfileDraft(steppedProfileId)

/** What CONFIRM on the profile row does. Exactly one branch opens a Guardian transaction. */
internal sealed class TweakerProfileApplyDecision {
    /** Nothing is drafted; confirm is inert, exactly as it is on Fan response. */
    data object NoDraft : TweakerProfileApplyDecision()

    /** The drafted profile is already the running one: consume the press, run no transaction. */
    data class AlreadyActive(val profileId: String) : TweakerProfileApplyDecision()

    /** The row no longer admits the drafted profile. Refuse and say so; never substitute another. */
    data class Unavailable(val profileId: String) : TweakerProfileApplyDecision()

    /** The single branch that costs a settlement. */
    data class Apply(val profileId: String) : TweakerProfileApplyDecision()
}

internal fun tweakerProfileApplyDecision(
    row: TweakerUtilityRowState,
    draft: TweakerProfileDraft?,
): TweakerProfileApplyDecision = when {
    draft == null -> TweakerProfileApplyDecision.NoDraft
    !draft.admitted(row) -> TweakerProfileApplyDecision.Unavailable(draft.previewProfileId)
    draft.previewProfileId == row.effectiveProfileId ->
        TweakerProfileApplyDecision.AlreadyActive(draft.previewProfileId)
    else -> TweakerProfileApplyDecision.Apply(draft.previewProfileId)
}

/** A fan response preview is UI-only until the player explicitly applies it. */
internal data class TweakerFanCurveDraft(
    val profileId: String,
    val mode: TweakerFanMode,
    val authoritativeBias: Int,
    val curveBiasRange: IntRange,
    val curveBiasStep: Int,
    val supportedModes: Set<TweakerFanMode>,
    val curveSensorType: String,
    val ownerRequestId: String,
    val previewBias: Int,
) {
    fun matches(capability: TweakerManualFanCapability): Boolean =
        capability.profileId == profileId &&
            capability.currentMode == mode &&
            capability.currentCurveBias == authoritativeBias &&
            capability.curveBiasRange == curveBiasRange &&
            capability.curveBiasStep == curveBiasStep &&
            capability.modes == supportedModes &&
            capability.curveSensorType == curveSensorType &&
            capability.ownerRequestId == ownerRequestId
}

internal fun snappedTweakerFanCurveBias(
    requestedBias: Int,
    range: IntRange,
    step: Int,
): Int {
    val safeStep = step.coerceAtLeast(1)
    val bounded = requestedBias.coerceIn(range)
    return (range.first + ((bounded - range.first).toFloat() / safeStep).roundToInt() * safeStep)
        .coerceIn(range)
}

internal fun updatedTweakerFanCurveDraft(
    current: TweakerFanCurveDraft?,
    capability: TweakerManualFanCapability,
    requestedBias: Int,
): TweakerFanCurveDraft? {
    if (capability.currentMode != TweakerFanMode.MANUAL) return null
    val preview = snappedTweakerFanCurveBias(
        requestedBias,
        capability.curveBiasRange,
        capability.curveBiasStep,
    )
    if (preview == capability.currentCurveBias) return null
    val validCurrent = current?.takeIf { it.matches(capability) }
    return (validCurrent ?: TweakerFanCurveDraft(
        profileId = capability.profileId,
        mode = capability.currentMode,
        authoritativeBias = capability.currentCurveBias,
        curveBiasRange = capability.curveBiasRange,
        curveBiasStep = capability.curveBiasStep,
        supportedModes = capability.modes,
        curveSensorType = capability.curveSensorType,
        ownerRequestId = capability.ownerRequestId,
        previewBias = capability.currentCurveBias,
    )).copy(previewBias = preview)
}

internal fun steppedTweakerFanCurveDraft(
    current: TweakerFanCurveDraft?,
    capability: TweakerManualFanCapability,
    direction: Int,
): TweakerFanCurveDraft? {
    if (capability.currentMode != TweakerFanMode.MANUAL) return null
    val currentPreview = current?.takeIf { it.matches(capability) }?.previewBias
        ?: capability.currentCurveBias
    val requested = currentPreview + if (direction < 0) {
        -capability.curveBiasStep
    } else {
        capability.curveBiasStep
    }
    if (snappedTweakerFanCurveBias(
            requested,
            capability.curveBiasRange,
            capability.curveBiasStep,
        ) == currentPreview
    ) {
        return current
    }
    return updatedTweakerFanCurveDraft(current, capability, requested)
}

/** A capability from another profile/session must never become actionable under the committed row. */
/**
 * Does this restore deserve a player-facing sentence right now? Pure so it can be pinned; the
 * coordinator keeps the mutable latch. See [AppBrightnessCoordinator.surfaceTweakerRestoreIfNewlyLegible]
 * for the ruling and the reasoning.
 */
/**
 * Does this derate deserve a player-facing sentence right now?
 *
 * Keyed on `reason:rung` rather than mere presence, so a rung ESCALATION re-announces: going from
 * rung 1 to rung 2 is a second, separate loss of performance and staying silent about it would
 * repeat the original defect one level down.
 *
 * Pure so it can be pinned; the coordinator keeps the latch. Mirrors
 * [shouldSurfaceTweakerRestore] deliberately — same problem, same shape.
 */
internal fun shouldSurfaceTweakerDerate(
    derate: TweakerThermalDerate?,
    lastSurfacedKey: String?,
): Boolean = derate != null && tweakerDerateKey(derate) != lastSurfacedKey

internal fun tweakerDerateKey(derate: TweakerThermalDerate): String =
    "${derate.reason}:${derate.rung}"

internal fun shouldSurfaceTweakerRestore(
    lastRestore: TweakerLastRestore?,
    lastSurfacedKey: String?,
): Boolean = lastRestore != null &&
    // Fan-fence fix F4 (2026-10-14): a guardian hand-back is the second class with an action the
    // player can take (re-apply). Every other restore class stays silent, as before.
    (lastRestore.isTelemetryTransport || lastRestore.isGuardianHandback) &&
    // P3-6: one announcement per (reason, generation) -- a repeat of the same reason on a later
    // session is a new drop, announced again; the same pair is the same drop.
    lastRestore.incidentKey != lastSurfacedKey

/**
 * May a settlement in flight refuse to let the player dismiss the Utility surface?
 *
 * **Asymmetric on purpose.**
 *
 * A PROFILE change holds. It is the larger mutation and it has an explicit cancellation path
 * (`requestTweakerProfileCancellation`) that Back should reach rather than bypass.
 *
 * A FAN change does NOT hold, and used to. The justification for holding was that the centered
 * modal is the sole feedback surface — but `publishTweakerMessage` already falls back to a Toast
 * when the panel is closed, so the outcome reaches the player either way. Against that, the cost
 * was measured on the Odin 3: a fan mode transition takes **6.3 s** entering Manual and
 * **8.5 s** returning to Auto, and Back and Close were both inert for the whole of it. Trapping
 * someone in a modal for eight and a half seconds is a worse surface than letting them walk away
 * from a change that completes regardless.
 *
 * [fanControlChange] stays in the signature deliberately rather than being deleted: the parameter
 * is what makes the asymmetry visible at the call site and reviewable here, instead of an
 * unexplained absence. [[absence-of-behaviour-needs-its-own-gate]].
 */
internal fun utilitySurfaceHoldsAgainstDismiss(
    profileChange: TweakerProfileChangeState?,
    @Suppress("UNUSED_PARAMETER") fanControlChange: TweakerFanControlChangeState?,
): Boolean = profileChange != null

/** Cap for the temporary fan debug detail line -- long enough to carry a daemon code plus a short
 * message, short enough not to wrap off a handheld's Utility rail. */
private const val FAN_DEBUG_DETAIL_MAX = 96

/**
 * Bounded one-line detail for the temporary fan debug messages. Uppercased to match the rail's
 * voice and clipped so a long daemon string cannot push the row off a handheld screen. Top-level
 * and internal so it can be pinned without a Robolectric coordinator.
 */
/**
 * The daemon declined, and said why. A definite negative: nothing changed.
 *
 * Kept beside [FAN_MESSAGE_UNCONFIRMED] and pinned apart in `TweakerFanDebugMessageTest`, because
 * the defect these two replaced was exactly their collapse into one string.
 */
internal const val FAN_MESSAGE_DECLINED = "FAN CONTROL DECLINED"

/**
 * The outcome is UNKNOWN -- the change may well have landed.
 *
 * A single "FAN RESPONSE NOT APPLIED" once covered both this and [FAN_MESSAGE_DECLINED], and told
 * the player that four fan changes had failed when every one had applied and verified on hardware. This
 * string must never assert a failure.
 */
internal const val FAN_MESSAGE_UNCONFIRMED = "FAN CONTROL UNCONFIRMED"

internal fun fanDebugDetailText(detail: String): String {
    val flat = detail.trim().replace(Regex("\\s+"), " ").uppercase()
    return if (flat.length <= FAN_DEBUG_DETAIL_MAX) flat else flat.take(FAN_DEBUG_DETAIL_MAX - 1) + "…"
}

/**
 * Should the frontend put the fan back into Manual, unasked?
 *
 * The fan
 * really does return to vendor Auto when the frontend dies -- `OWNER_DEATH_RESTORE`, observed on
 * the Odin at 06:13:26 when an install force-stopped the app -- and that stays exactly as it is.
 * Manual stops the vendor's regulation loop, so nothing may hold the fan while nothing is watching;
 * that is the abandoned-fan guarantee. The remembered
 * CHOICE must outlive the restart, and a choice is frontend state.
 *
 * Every conjunct is load-bearing:
 * - a capability must exist, so there is a live session to act on at all;
 * - the remembered choice must be MANUAL, so a deliberate Auto is never overridden;
 * - MANUAL must be advertised, so this never asks for a mode the session cannot serve;
 * - and it must not already have been attempted for this exact session identity, so a refusal
 *   cannot become a retry loop that fights the daemon once a second.
 *
 * **`currentMode` is trusted ONLY where the daemon attests hardware ownership.** This conjunct
 * changed twice in one day; the history is kept because the naive form looks obviously right and is
 * not. It used to plainly require `currentMode == AUTO`, "so this only ever repairs a fallback". On
 * the Odin at
 * 12:31:14, with the hardware sitting on vendor Auto (`fan_mode=4`, daemon `contractFan=4`), the
 * advertised capability read `currentMode=MANUAL` -- and the fan stayed on the vendor curve
 * indefinitely because this gate concluded there was nothing to repair. The cause is a genuine
 * daemon-side divergence: `fanControlMode` is published from `ActiveFanControlSelectionStore`,
 * a DURABLE per-profile preference that deliberately survives session teardown, while the
 * hardware contract is re-established at vendor SMART. Two independent memories of "Manual",
 * neither driving the hardware, each assuming the other did. The bundle publishes no live
 * hardware ownership, so the client cannot tell a real Manual from a stale selection and must
 * stop pretending it can.
 *
 * **The daemon-side fix landed the same afternoon** -- `fanHardwareDutyOwned`, live hardware
 * ownership published beside the selection -- so the three cases are now distinct, and the
 * redundant re-apply that dropping the conjunct outright had cost is gone:
 * - `hardwareDutyOwned == null` -- an older Tweaker that cannot attest. `currentMode` is
 *   untrustworthy, is ignored, and this is the only case that re-applies redundantly.
 * - `hardwareDutyOwned == true` with `currentMode == MANUAL` -- genuinely in Manual, so skip. This
 *   is the property the ORIGINAL conjunct wanted and had no way to actually check.
 * - `hardwareDutyOwned == false` -- the parser has already rewritten `currentMode` to AUTO, so the
 *   check below reads AUTO and falls through to repair. That is the defect being fixed.
 */
internal fun shouldRestoreRememberedManualFan(
    capability: TweakerManualFanCapability?,
    rememberedMode: TweakerFanMode?,
    lastAttemptedSessionKey: String?,
): Boolean {
    val fan = capability ?: return false
    if (rememberedMode != TweakerFanMode.MANUAL) return false
    // Trust the advertised mode only where an attestation backs it. Deliberately not
    // `hardwareDutyOwned == true`: when the daemon says false, the parser has already rewritten
    // currentMode to AUTO, so this reads AUTO and correctly falls through to repair.
    if (fan.hardwareDutyOwned != null && fan.currentMode != TweakerFanMode.AUTO) return false
    if (TweakerFanMode.MANUAL !in fan.modes) return false
    return tweakerFanSessionKey(fan) != lastAttemptedSessionKey
}

internal fun tweakerFanSessionKey(capability: TweakerManualFanCapability): String =
    "${capability.generation}:${capability.transactionId}:${capability.profileId}"

/**
 * The one-line, per-session-key observation logged when [shouldRestoreRememberedManualFan] would
 * have fired -- extracted so the message and its "left to the daemon" framing (fan ownership spec
 * I6) are pinned independently of the coordinator's side effects.
 */
internal fun rememberedManualObservation(profileId: String): String =
    "remembered MANUAL for $profileId left to the daemon's renewal"

/**
 * Which curve bias a fan MODE change should carry.
 *
 * Switching from Manual to Auto and back lost the previous Manual bias (for example, cooler 2).
 * The old expression was
 * `if (next == AUTO) 0 else fan.currentCurveBias`, and the else branch was reading back the mode
 * switch's own side effect: the daemon requires `fanCurveBias == 0` while the mode is AUTO (see
 * `parseManualFanCapability`'s `require`), so by the time the player asks for Manual again the
 * authoritative bias is zero and the number they chose exists nowhere.
 *
 * AUTO still goes out as an explicit 0 -- that is the daemon's contract, not a default -- and the
 * remembered value is only ever a fallback for [currentBias], never an override of a bias the
 * capability is already carrying.
 */
internal fun tweakerFanModeEntryBias(
    next: TweakerFanMode,
    rememberedManualBias: Int?,
    currentBias: Int,
): Int = when (next) {
    TweakerFanMode.AUTO -> 0
    TweakerFanMode.MANUAL -> rememberedManualBias ?: currentBias
}

internal fun matchedTweakerAdvancedCapabilities(
    row: TweakerUtilityRowState,
    advanced: TweakerAdvancedCapabilities?,
): TweakerAdvancedCapabilities? = advanced?.copy(
    // Same per-profile filtering the fan capability already gets: a derate recorded against
    // another profile must never decorate this row.
    thermalDerate = advanced.thermalDerate?.takeIf { it.profileId == row.effectiveProfileId },
    manualFan = advanced.manualFan?.takeIf { fan ->
        row.availability == TweakerUtilityAvailability.READY &&
            row.effectiveProfileId != TWEAKER_STOCK_PROFILE_ID &&
            fan.profileId == row.effectiveProfileId
    },
)

internal fun acceptedUtilitySurfaceOpenRequest(
    requestedSurface: UtilitySurface,
    profileChange: TweakerProfileChangeState?,
    fanControlChange: TweakerFanControlChangeState?,
): UtilitySurface? = requestedSurface.takeIf {
    profileChange == null && fanControlChange == null
}

/**
 * Where a Utility row's request for the Tweaker surface lands: on the host that is showing the row.
 * A row can only be activated while its panel is open, so a closed coordinator falls back to the
 * PRIMARY default the direct routes (physical M, macros) already use.
 */
internal fun tweakerSurfaceOpenTarget(
    isOpen: Boolean,
    presentationTarget: AppBrightnessPresentationTarget,
): AppBrightnessPresentationTarget =
    if (isOpen) presentationTarget else AppBrightnessPresentationTarget.PRIMARY

/**
 * The Tweaker calls this coordinator makes that must be stand-in-able: the rendered row, the refresh
 * behind it, and the one call that opens a Guardian settlement.
 *
 * Production binds the single process bridge — `TweakerBridge.selectSystemProfile` already injects its
 * own `bindAndSelect` the same way. A test binds a synthesized READY row, so "this press ran no
 * transaction" becomes an assertion about a counter rather than about an unreachable device.
 */
internal interface TweakerProfilePort {
    fun utilityRowState(): TweakerUtilityRowState

    suspend fun refreshCapabilities()

    /**
     * The panel's once-a-second beat while Utility is open: the live fan and temperature readout needs a fresh sample every
     * second, and the full capability projection (version, session, capabilities, advanced controls) does not. A stand-in
     * that has only [refreshCapabilities] gets it for both.
     */
    suspend fun refreshForPanelTick() = refreshCapabilities()

    suspend fun selectSystemProfileSettled(
        profileId: String,
        admittedProfileIds: Set<String>,
        control: TweakerProfileSelectionControl,
    ): TweakerSystemProfileSelectionResult
}

internal object TweakerBridgeProfilePort : TweakerProfilePort {
    override fun utilityRowState(): TweakerUtilityRowState = TweakerBridge.utilityRowState()

    override suspend fun refreshCapabilities() {
        TweakerBridge.refreshCapabilities()
    }

    override suspend fun refreshForPanelTick() {
        TweakerBridge.refreshForPanelTick()
    }

    override suspend fun selectSystemProfileSettled(
        profileId: String,
        admittedProfileIds: Set<String>,
        control: TweakerProfileSelectionControl,
    ): TweakerSystemProfileSelectionResult = TweakerBridge.selectSystemProfileSettled(
        profileId = profileId,
        admittedProfileIds = admittedProfileIds,
        control = control,
    )
}

/** Owns the brightness modal as one app-shell state machine instead of scattering it through MimirApp. */
internal enum class AppBrightnessPresentationTarget { PRIMARY, SECONDARY }

@Stable
internal class AppBrightnessCoordinator internal constructor(
    internal var context: Context,
    private var controller: BrightnessController?,
    internal var authority: FocusAuthority,
    private val scope: CoroutineScope,
    private val privilegedIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private var beforeExternalActivity: () -> Unit = {},
    private val tweakerPort: TweakerProfilePort = TweakerBridgeProfilePort,
    /**
     * Whether this panel is hosted by the Activity that also composes `FileBrowserSurface`.
     *
     * True for the in-app Utility ([rememberAppBrightnessCoordinator], reached from MimirAppHost's
     * overlay stack). **False for [PrimaryMButtonPanelHost]**, which draws this same panel into a
     * native overlay window over the foreground app with its own isolated `DefaultFocusAuthority`.
     * Only the Files route consults it, and only to withhold itself -- see [filesRouteDecision]'s KDoc
     * for the divorced-browser lockup that gate prevents. Declared rather than sniffed from
     * [beforeExternalActivity]: a lambda's identity is not a statement about the host.
     */
    internal var hostsInAppSurfaces: Boolean = true,
) {
    /** One coordinator/model may move between the two windows; hosts never create a second writer. */
    var presentationTarget by mutableStateOf(AppBrightnessPresentationTarget.PRIMARY)
        private set

    var state by mutableStateOf(controller?.state)
        private set

    var isOpen by mutableStateOf(false)
        private set

    var surface by mutableStateOf(UtilitySurface.UTILITY)
        private set

    /**
     * R2 : a game is running, so Utility's Files row is withdrawn and comes back when the game ends. The browser
     * would open on MainActivity's stack, beneath the emulator, and a player in a game did not ask to browse the library. Compose
     * state, so an open Utility redraws when a game starts or ends: refreshed on every open and by the panel's host from the
     * session authority.
     *
     * A MIRROR of the ledger ([filesRouteGameRunning]), not the truth: nothing writes it while Utility is closed, so the dock's
     * Files seat, which never composes Utility, refreshes it itself before it decides ([refreshGameRunning], RC-06).
     */
    internal var gameRunning by mutableStateOf(false)

    /** Brings [gameRunning] up to date with the ledger; the route's decision points call it so a stale mirror never decides. */
    internal fun refreshGameRunning() {
        gameRunning = filesRouteGameRunning(MimirSessionAuthority.state.value)
    }

    /** Whether the Files row is offered: the one answer for the row, its focus order and its press. */
    internal val filesRouteOffered: Boolean
        get() = utilityFilesRowOffered(hostsInAppSurfaces, gameRunning)

    /** The in-place surface to reveal when Back returns from a routed tool panel. */
    private var surfaceReturnTarget: UtilitySurface? = null

    /** The Tweaker footer is a route return when Utility opened it in place. */
    internal val backActionLabel: String
        get() = if (surface == UtilitySurface.TWEAKER && surfaceReturnTarget != null) "Back" else "Close"

    /** The dismissal may close only the exact profile operation it asked to cancel. */
    private var closeAfterSecondaryProfileCancellationOperationId: Long? = null
    private var secondaryPresentationOwner: UUID? = null

    /**
     * All-files access, as a snapshot rather than a live read.
     *
     * The Files row's value label depends on it, and the rail recomposes constantly (every slider drag
     * repaints it), so calling `Environment.isExternalStorageManager()` from composition would cross into
     * the framework once per frame. Refreshed exactly where it can change from Mimir's point of view:
     * [open], and [refreshOnResume] -- which is the return path from the grant screen the row itself
     * raises, since [requestAllFilesAccess] closes the panel via [leaveForExternalActivity].
     *
     * Same one-snapshot-per-refresh posture [tweakerPanelProjection] already documents.
     */
    var allFilesAccessGranted by mutableStateOf(false)
        private set

    /** Profile, fan capability, and access posture are exposed as one authoritative visual snapshot. */
    private var tweakerPanelProjection by mutableStateOf(
        TweakerPanelProjection(
            row = tweakerPort.utilityRowState(),
            advancedCapabilities = null,
            usageAccessState = resolveTweakerUsageAccessState(
                TweakerBridge.isTweakerInstalled(),
                TweakerBridge.capabilities,
            ),
            canDrawOverlays = Settings.canDrawOverlays(context),
        ),
    )

    val tweakerRow: TweakerUtilityRowState
        get() = tweakerPanelProjection.row

    /**
     * GPU temperature alongside the PROFILE row's fan readout and the Fan control section's
     * temperature readout. Published on the same cadence as [tweakerPanelProjection] rather than
     * read live on every recomposition, for the same reason that projection exists: one snapshot per
     * refresh, not a fresh cross-process read per frame.
     */
    var tweakerFanGpuTempC by mutableStateOf(TweakerBridge.utilityFanGpuTempC())
        private set

    /**
     * CPU temperature (hottest core) alongside [tweakerFanGpuTempC], feeding the Fan control
     * section's temperature readout only — the PROFILE row's `· 88°C` suffix stays GPU-only
     * ([tweakerProfileFanReadoutText]; retired with R2, not here). Same cadence, same freshness gate,
     * same "not gated on a selected profile" contract as [tweakerFanGpuTempC].
     */
    var tweakerFanCpuTempC by mutableStateOf(TweakerBridge.utilityFanCpuTempC())
        private set

    /**
     * the fan readout ("Automatic fan · 25% · 62°C") must stay visible for
     * as long as a Mimir-owned fan session exists (a non-Stock profile selected), not blink out
     * whenever one 3.5s telemetry sample lands late — see [freshUtilityFanControl] /
     * [freshUtilityFanGpuTempC]. These hold the last known reading; [projectTweakerUtilityState]
     * reads the live, gated-fresh value into a LOCAL each cycle (never published on its own), uses it
     * to update these two when non-null, and then publishes THESE into [tweakerRow]`.fanControl` and
     * [tweakerFanGpuTempC] above — so every renderer downstream of those two already sees the held
     * value and needs no separate fallback.
     *
     * [retainedFanProfileId] is the profile the held reading belongs to. A genuine profile switch
     * (including to Stock, which has no Mimir-owned fan session) clears it immediately in
     * [projectTweakerUtilityState] rather than bridging a reading across the switch, and [close]
     * clears it on session end so a reopened panel never starts by showing a reading from before it
     * closed as though it were current.
     *
     * [retainedFanControl] itself stays unbounded by staleness — a live defect (its own `dutyPercent`
     * can paint stale forever if the daemon dies mid-session), but it is routed to a later ticket,
     * not fixed here (design spec §11: fixing it is moot once R2 nulls the readout entirely). The two
     * temperature fields below ARE bounded ([FAN_TEMPERATURE_RETENTION_HOLD_MS], §4.3 of the spec) —
     * the fan-control temperature readout is the number the release valve is calibrated by, so an
     * unbounded hold is not tolerable for it the way it was for a duty readout nobody acted on.
     */
    private var retainedFanControl by mutableStateOf<TweakerFanControlReadout?>(null)
    private var retainedFanGpuTempC by mutableStateOf<Int?>(null)
    private var retainedFanGpuTempCAtElapsedRealtimeMs: Long? = null
    private var retainedFanCpuTempC by mutableStateOf<Int?>(null)
    private var retainedFanCpuTempCAtElapsedRealtimeMs: Long? = null
    private var retainedFanProfileId by mutableStateOf<String?>(null)

    val tweakerAdvancedCapabilities: TweakerAdvancedCapabilities?
        get() = tweakerPanelProjection.advancedCapabilities

    val tweakerUsageAccessState: TweakerUsageAccessState
        get() = tweakerPanelProjection.usageAccessState

    /**
     * Live [Settings.canDrawOverlays] as of the last projection refresh — never the persisted
     * [PerformanceOverlayVisibility] preference. Refreshed by every [projectTweakerUtilityState] call, which
     * runs on [open], the panel's periodic foreground refresh, and [refreshOnResume] — the same path that
     * already re-checks [tweakerUsageAccessState] after a trip to Android Settings.
     */
    val performanceOverlayCanDrawOverlays: Boolean
        get() = tweakerPanelProjection.canDrawOverlays

    var tweakerMessage by mutableStateOf<MimirMessage?>(null)
        private set

    /** See [surfaceTweakerRestoreIfNewlyLegible] -- the once-per-incident latch, keyed `reason#generation`. */
    private var lastSurfacedRestoreKey: String? = null

    /** See [surfaceTweakerDerateIfNewlyLegible] -- same latch, keyed `reason:rung`. */
    private var lastSurfacedDerateKey: String? = null

    /** See [restoreRememberedManualFanIfNeeded] -- one attempt per session identity, never a loop. */
    private var lastManualRearmSessionKey: String? = null

    var tweakerProfileChange by mutableStateOf<TweakerProfileChangeState?>(null)
        private set

    var tweakerFanControlChange by mutableStateOf<TweakerFanControlChangeState?>(null)
        private set

    private var tweakerProfileDraft by mutableStateOf<TweakerProfileDraft?>(null)
    private var tweakerFanCurveDraft by mutableStateOf<TweakerFanCurveDraft?>(null)

    /**
     * The discovered-ceiling preview. Separate from [tweakerProfileDraft] because the two can never be
     * live at once — the row is either serving certified profiles or a discovered ceiling — and folding
     * them together would mean one nullable holding two vocabularies.
     */
    private var tweakerCeilingDraft by mutableStateOf<TweakerCeilingDraft?>(null)

    /** The drafted profile while one is pending; null means the row shows committed truth. */
    internal val tweakerProfilePreviewId: String?
        get() = tweakerProfileDraft?.takeIf { it.admitted(tweakerRow) }?.previewProfileId

    internal val tweakerProfileDraftPending: Boolean
        get() = tweakerProfileDraft?.admitted(tweakerRow) == true

    /** The drafted ceiling, already spelled, or null when the row shows the committed one. */
    internal val tweakerCeilingPreviewLabel: String?
        get() = tweakerCeilingDraft?.takeIf { it.admitted(tweakerRow) }?.let { draft ->
            tweakerRow.discovered?.steps?.firstOrNull { it.khz == draft.previewKhz }?.labelCaps
        }

    internal val tweakerCeilingDraftPending: Boolean
        get() = tweakerCeilingDraft?.admitted(tweakerRow) == true

    /**
     * True when the drafted step is the top of the table, where APPLY gives the device back instead of
     * writing a cap. The row says which of the two it is about to do, before the press.
     */
    internal val tweakerCeilingPreviewReleasesHold: Boolean
        get() = tweakerCeilingDraft?.takeIf { it.admitted(tweakerRow) }?.previewKhz ==
            tweakerRow.discovered?.top?.khz

    /**
     * The frame-cap row, published as one snapshot rather than re-read on every access.
     *
     * It was a getter that called [TweakerBridge.attestedForegroundGamePackage] each read, which lands
     * a synchronous cross-process telemetry call on the INPUT path: `dispatch` evaluates this argument
     * eagerly to build the focus order, so every controller press paid for it, on Utility as well as
     * Tweaker. MimirInputCost measured the resulting handler at ~152 ms on an Odin 3 (60 samples,
     * 145-166 ms) against an 8 ms log threshold. The same getter was read once per recomposition.
     *
     * The subject is still attested, never remembered from a launch -- [refreshTweakerFpsCapRow]
     * re-reads the live foreground off the main thread on the same cadence that publishes the rest of
     * this panel's truth. The invariant the old comment was protecting is stronger here, not weaker:
     * render, focus order, and the press now agree because they read ONE snapshot, where three
     * independent live reads could each name a different package inside a single frame.
     */
    internal var tweakerFpsCapRow by mutableStateOf<TweakerFpsCapRow>(TweakerFpsCapRow.NoSubject)
        private set

    private var tweakerFpsCapJob: Job? = null

    /** Re-attests the foreground off the main thread, then publishes. Never blocks input. */
    private fun refreshTweakerFpsCapRow() {
        // Withheld from Alpha: no row to feed, so do not pay a cross-process telemetry read for it.
        if (!TweakerFrameCapGate.enabled) return
        if (tweakerFpsCapJob?.isActive == true) return
        tweakerFpsCapJob = scope.launch {
            val next = withContext(privilegedIoDispatcher) { readTweakerFpsCapRow() }
            tweakerFpsCapRow = next
            tweakerFpsCapJob = null
        }
    }

    private fun readTweakerFpsCapRow(): TweakerFpsCapRow {
        val target = TweakerBridge.attestedForegroundGamePackage()
        return tweakerFpsCapRow(
            foregroundPackage = target,
            optionsHz = TweakerBridge.gameFpsLimitOptionsHz(),
            selectedHz = target?.let(TweakerBridge::selectedGameFpsLimitHz),
        )
    }

    /**
     * Persists against the package the row itself names, never a freshly-read foreground — if the
     * game changed between render and press, the write must not silently land on the new one.
     */
    internal fun stepTweakerFpsCap(direction: Int) {
        val row = tweakerFpsCapRow as? TweakerFpsCapRow.Available ?: return
        val next = stepTweakerFpsCap(row, direction)
        if (next == row.selectedHz) return
        if (!TweakerBridge.persistGameFpsLimitHz(row.packageName, next)) {
            Toast.makeText(context, "FRAME CAP NOT STORED", Toast.LENGTH_SHORT).show()
            return
        }
        // Show the write that just committed, against the package this row names. Re-attesting the
        // foreground is the cadence's job; doing it here would put the telemetry call back on the press.
        tweakerFpsCapRow = row.copy(selectedHz = next)
    }

    internal val tweakerFanCurvePreviewBias: Int?
        get() {
            val fan = tweakerAdvancedCapabilities?.manualFan ?: return null
            return tweakerFanCurveDraft?.takeIf { it.matches(fan) }?.previewBias
                ?: fan.currentCurveBias
        }

    internal val tweakerFanCurveDraftPending: Boolean
        get() {
            val fan = tweakerAdvancedCapabilities?.manualFan ?: return false
            return tweakerFanCurveDraft?.matches(fan) == true
        }

    private var tweakerMessageEventId = 0L

    /**
     * Privileged vendor and SettingsProvider calls are synchronous Binder transactions. Keep every
     * privileged Utility operation behind one IO mutex so slider input, controller steps, theme writes,
     * and the liveness heartbeat cannot overlap or block Compose's applying dispatcher.
     */
    private val privilegedOperationMutex = Mutex()
    private val privilegedFractionJobs = mutableMapOf<BrightnessTarget, Job>()
    private val privilegedFractionGenerations = mutableMapOf<BrightnessTarget, Long>()
    private val pendingPrivilegedFractions = mutableMapOf<BrightnessTarget, Float>()
    private val privilegedStepRuns = mutableMapOf<BrightnessTarget, ArrayDeque<PrivilegedStepRun>>()
    private val privilegedStepJobs = mutableMapOf<BrightnessTarget, Job>()
    private var themeJob: Job? = null
    private var themeGeneration = 0L
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var readyHandshakeJob: Job? = null
    private var readyHandshakeGeneration = 0L
    private var tweakerRefreshJob: Job? = null
    private var tweakerRefreshGeneration = 0L
    private var tweakerSelectionJob: Job? = null
    private var tweakerFanControlJob: Job? = null

    /**
     * The in-flight discovered-ceiling write.
     *
     * **It exists because the ceiling press stopped being synchronous (2026-09-11).** On the daemon
     * route the write is a Binder round trip, so it moved off the main thread; two confirms landing a
     * frame apart would otherwise open two privileged writes against one node, which is the arbitration
     * failure this route was ruled to end. A press arriving while one is in flight re-states the interim
     * line rather than being swallowed — the row must never go quiet on a press.
     */
    private var tweakerCeilingJob: Job? = null
    private var tweakerSelectionControl: TweakerProfileSelectionControl? = null
    private var tweakerSelectionOperationId = 0L
    private var tweakerFanControlOperationId = 0L

    init {
        PerformanceOverlaySettingsStore.initialize(context)
        // **The missing trigger, device-proven 2026-08-26.** Every path that re-evaluates the fan
        // re-arm is foreground-gated: the surface-open path, refreshOnResume, and a LaunchedEffect
        // that only runs while the panel is open. On the Odin the two projections after a
        // restart both ran BEFORE the session existed (`rawFan=null`), the session arrived 3.6s
        // later, and nothing re-evaluated for the next two minutes -- so a reboot into a game would
        // spend the whole session on vendor Auto. The standing-rearm watch already hears about the
        // new session (it is what restores the PROFILE); this is the fan half listening to the same
        // observation. Last registration wins: a newer coordinator replaces a dead one.
        //
        // The `isActive` guard is not defensive dressing -- the unit suite reproduced the hazard it
        // closes. `TweakerBridge` is a process singleton, so the registration outlives the
        // coordinator that made it: a superseded instance's lambda still holds its dead scope, and
        // invoking it threw an uncaught exception into whatever ran next. Last registration wins,
        // and a stale one must degrade to nothing rather than to a crash.
        TweakerBridge.onStandingRearmProjectionRefreshed = {
            if (scope.isActive) scope.launch { projectTweakerUtilityState() }
        }
    }

    private data class PrivilegedStepRun(
        val direction: Int,
        var count: Int,
    )

    fun open(
        requestedSurface: UtilitySurface = UtilitySurface.UTILITY,
        target: AppBrightnessPresentationTarget = AppBrightnessPresentationTarget.PRIMARY,
    ) {
        val acceptedSurface = acceptedUtilitySurfaceOpenRequest(
            requestedSurface = requestedSurface,
            profileChange = tweakerProfileChange,
            fanControlChange = tweakerFanControlChange,
        ) ?: requestedSurface.takeIf {
            // A profile settlement may move from the secondary Presentation to the recreated primary
            // host, but it may not start a second surface or mutation while the first operation lives.
            tweakerProfileChange != null && tweakerFanControlChange == null && requestedSurface == surface
        } ?: return
        val activeController = controller
        if (activeController == null) {
            Toast.makeText(context, "Utility controls are unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        // A closed-Utility recovery handshake must surrender before the foreground status cadence starts.
        // Otherwise its 250 ms loop remains the real owner while the visible policy claims 500 ms.
        cancelReadyHandshake()
        val wasOpen = isOpen
        val previousSurface = surface
        val surfaceChanged = previousSurface != acceptedSurface
        if (surfaceChanged) {
            tweakerProfileDraft = null
            tweakerFanCurveDraft = null
            // A direct Tweaker request (for example the physical M route) has no in-panel origin and
            // therefore closes on Back. Only an already-open Utility panel establishes a return path.
            surfaceReturnTarget = previousSurface.takeIf { wasOpen }
        } else if (!wasOpen) {
            // A closed coordinator may retain its last rendered surface; that stale value is never a
            // Back destination for a fresh open.
            surfaceReturnTarget = null
        }
        // A new host/open is a new presentation intent. Never let a cancellation request from the
        // previous host close this one after its operation settles.
        closeAfterSecondaryProfileCancellationOperationId = null
        surface = acceptedSurface
        presentationTarget = target
        refreshGameRunning()
        isOpen = true
        // P6: no line anywhere on the Utility open path (§1.13 "SILENT").
        runCatching {
            android.util.Log.i("MimirUtility", "open surface=$acceptedSurface target=$target wasOpen=$wasOpen")
        }
        if (target == AppBrightnessPresentationTarget.SECONDARY) {
            // A secondary touch-only projection must not leave the primary controller scope armed
            // after a main→bottom handoff.
            authority.discardScope(BrightnessScope)
        }
        refreshAllFilesAccess()
        if (activeController.state.capabilities.hasVendorControls) {
            state = activeController.state
            refreshPrivilegedState(
                activeController = activeController,
                alwaysReconcileTheme = false,
                applyWindowFallbacks = false,
            )
        } else {
            state = activeController.refresh()
        }
        refreshTweakerUtilityState()
        // The bottom Presentation is touch-only and cannot claim controller focus from a game on
        // the primary display. The same coordinator remains authoritative for both hosts.
        if (target == AppBrightnessPresentationTarget.PRIMARY &&
            authority.state.value.scope != BrightnessScope
        ) {
            stampBrightnessLanding()
            authority.pushScope(BrightnessScope)
        }
        val visible = visibleControlsFor(activeController.state)
        val currentControl = BrightnessControlKey.entries.firstOrNull {
            brightnessFocusKey(it) == authority.state.value.focused
        }
        // Landing on the first option is NOT decided here: requestFocus silently drops a key that has
        // not registered yet (DefaultFocusAuthority.kt:113), and the incoming surface's rows only
        // register once its composition runs. AppBrightnessHost owns that rule.
        if (surfaceChanged || currentControl !in visible) {
            visible.firstOrNull()?.let {
                // RC-50: the request above is dropped while the key is unregistered; the memory it leaves is what the first
                // registration seeds, so the panel never opens on the row the last visit ended on.
                if (target == AppBrightnessPresentationTarget.PRIMARY) {
                    authority.rememberFocusForScope(BrightnessScope, brightnessFocusKey(it))
                }
                authority.requestFocus(brightnessFocusKey(it))
            }
        }
    }

    /**
     * RC-50 (audit 2026-10-14, U-01 (5)): [close] leaves the last-used control as the scope's memory and the panel's rows are not
     * registered when the scope is pushed, so a reopen seeded THAT row (a slider, a step control) until the host's landing effect
     * moved focus to the first option a frame or more later; a stray Left, Right or A in that window acted on the old row. The first
     * option is stamped as the memory before the push, so the first row to register is the landing row.
     */
    private fun stampBrightnessLanding() {
        val current = state ?: controller?.state ?: return
        brightnessLandingControl(visibleControlsFor(current), surface)?.let {
            authority.rememberFocusForScope(BrightnessScope, brightnessFocusKey(it))
        }
    }

    /** The focus order the open panel shows, from [current]: one place for the open path's landing and its pre-push stamp. */
    private fun visibleControlsFor(current: BrightnessState): List<BrightnessControlKey> = brightnessControls(
        current,
        CompanionProcessRuntime.services(context).mButtonTopology.mButtonVisibilityControlsAvailable.value,
        tweakerUsageAccessState,
        surface,
        tweakerAdvancedCapabilities?.manualFan,
        tweakerRow.availability,
        tweakerFpsCapRow,
        filesRouteOffered,
    )

    fun close() {
        // A settlement operation owns this surface until its terminal readback. The centered modal
        // provides the sole feedback surface; external close/toggle requests cannot tear it down early.
        //
        // **A FAN change no longer holds it.** The
        // premise above is false for this path: `publishTweakerMessage` falls back to a Toast when
        // the panel is closed, so the outcome still reaches the player. And the cost was measured --
        // a fan mode transition takes 6.3 s entering Manual and 8.5 s returning to Auto on an
        // Odin 3, during which Back and Close were both inert. Trapping someone in a modal for
        // eight and a half seconds is a worse surface than letting them walk away from a change
        // that completes regardless.
        //
        // A PROFILE change still holds it, deliberately: it is the larger mutation, and it has an
        // explicit cancellation path (`requestTweakerProfileCancellation`) that a player pressing
        // Back should reach rather than bypass.
        if (utilitySurfaceHoldsAgainstDismiss(tweakerProfileChange, tweakerFanControlChange)) return
        // CLOSE is explicit: discard any uncommitted preview and close in the same activation.
        cancelTweakerProfileDraft()
        cancelTweakerFanCurveDraft()
        isOpen = false
        // P6: no line anywhere on the Utility close path (§1.13 "SILENT"). Read presentationTarget
        // before it resets to PRIMARY below, so this names the target that was actually open.
        runCatching {
            android.util.Log.i("MimirUtility", "close surface=$surface target=$presentationTarget")
        }
        presentationTarget = AppBrightnessPresentationTarget.PRIMARY
        surfaceReturnTarget = null
        closeAfterSecondaryProfileCancellationOperationId = null
        tweakerMessage = null
        cancelForegroundRefreshes()
        // The Mimir-owned fan session's held readout ends with the panel: a reopened panel must
        // start blank and re-earn its first fresh sample, never show a reading left over from
        // before Close as though it were current .
        retainedFanControl = null
        retainedFanGpuTempC = null
        retainedFanGpuTempCAtElapsedRealtimeMs = null
        retainedFanCpuTempC = null
        retainedFanCpuTempCAtElapsedRealtimeMs = null
        retainedFanProfileId = null
        // Release the scope wherever it sits, not only when it is on top. `close()` is reached from
        // MimirAppCoordinator's M-button path directly after `overlays.dismissConflictingModals()`,
        // and that sweep does NOT guarantee a clear stack -- it breaks on RETURN_COVER and the moment
        // one CANCEL fails to move the top overlay, as its own comment records. It is also reached
        // from PrimaryMButtonPanelHost.dispose(), which runs against whatever stack exists at
        // teardown. A guarded pop under a cover would leave BrightnessScope orphaned for the life of
        // the process. No current path is known to push a scope OVER this modal, so this is
        // hardening rather than a fix for a reproduced defect -- but the guarded form has no
        // compensating advantage: `discardScope` is identical when the scope is active or absent and
        // emits nothing when it is buried. Same shape retired from `leaveHomeTomeScope` 2026-09-06.
        authority.discardScope(BrightnessScope)
    }

    /** The Activity lifecycle calls this before it leaves RESUMED; no posture loop may outlive it. */
    fun onHostPaused() {
        cancelForegroundRefreshes()
    }

    /** Rebind the process-retained model to an exact registered host without touching a pending write. */
    internal fun rebindHost(
        registration: CompanionProcessRuntime.BrightnessHostRegistration,
    ) {
        val hostContext = registration.context
        val hostController = registration.controller
        val hostAuthority = registration.authority
        if (authority !== hostAuthority) {
            // A recreated Activity owns a new focus stack. Release the old stack before handing the
            // retained model to it; this does not cancel the process-scoped profile operation.
            authority.discardScope(BrightnessScope)
            authority = hostAuthority
        }
        context = hostContext.applicationContext
        beforeExternalActivity = registration.beforeExternalActivity
        hostsInAppSurfaces = registration.hostsInAppSurfaces
        if (controller !== hostController) {
            controller = hostController
            if (tweakerProfileChange == null) state = hostController?.state
        }
        activeBrightnessHost = registration
        if (registration.kind == CompanionProcessRuntime.BrightnessHostKind.ACTIVITY &&
            presentationTarget == AppBrightnessPresentationTarget.PRIMARY &&
            isOpen && hostController != null && authority.state.value.scope != BrightnessScope
        ) {
            stampBrightnessLanding()
            authority.pushScope(BrightnessScope)
        } else if (registration.kind == CompanionProcessRuntime.BrightnessHostKind.ACTIVITY &&
            presentationTarget == AppBrightnessPresentationTarget.SECONDARY
        ) {
            authority.discardScope(BrightnessScope)
        } else if (registration.kind == CompanionProcessRuntime.BrightnessHostKind.PRIMARY_OVERLAY) {
            authority.discardScope(BrightnessScope)
        }
    }

    /** Compatibility seam for focused coordinator tests that exercise rebinding without the registry. */
    internal fun rebindHost(
        hostContext: Context,
        hostController: BrightnessController?,
        hostAuthority: FocusAuthority,
        hostBeforeExternalActivity: () -> Unit = beforeExternalActivity,
        hostHostsInAppSurfaces: Boolean = hostsInAppSurfaces,
    ) = rebindHost(
        CompanionProcessRuntime.BrightnessHostRegistration(
            kind = CompanionProcessRuntime.BrightnessHostKind.ACTIVITY,
            controller = hostController,
            authority = hostAuthority,
            context = hostContext.applicationContext,
            beforeExternalActivity = hostBeforeExternalActivity,
            hostsInAppSurfaces = hostHostsInAppSurfaces,
            token = 0L,
        ),
    )

    /** The model's current primary host; stale teardown must not affect a successor. */
    // Compose observes this so a host handoff removes one primary renderer/poller and reveals the
    // successor in the same state commit.
    private var activeBrightnessHost by mutableStateOf<CompanionProcessRuntime.BrightnessHostRegistration?>(null)

    internal fun ownsBrightnessHost(
        registration: CompanionProcessRuntime.BrightnessHostRegistration,
    ): Boolean = activeBrightnessHost?.token == registration.token

    internal fun ownsBrightnessHost(token: Long): Boolean = activeBrightnessHost?.token == token

    internal fun isPrimaryHostActive(kind: CompanionProcessRuntime.BrightnessHostKind): Boolean =
        activeBrightnessHost?.kind == kind

    /** Drop an Activity/overlay-bound controller when that exact host is torn down. */
    internal fun detachHost(
        registration: CompanionProcessRuntime.BrightnessHostRegistration,
    ) {
        if (!ownsBrightnessHost(registration)) return
        authority.discardScope(BrightnessScope)
        controller = null
        beforeExternalActivity = {}
        hostsInAppSurfaces = true
        activeBrightnessHost = null
        if (tweakerProfileChange == null) state = null
    }

    internal fun onHostPausedIfCurrent(
        registration: CompanionProcessRuntime.BrightnessHostRegistration,
    ) {
        if (ownsBrightnessHost(registration)) onHostPaused()
    }

    internal fun onHostPausedIfCurrent(token: Long) {
        if (ownsBrightnessHost(token)) onHostPaused()
    }

    internal fun onActivityHostPaused(expectedAuthority: FocusAuthority) {
        if (activeBrightnessHost?.kind == CompanionProcessRuntime.BrightnessHostKind.ACTIVITY &&
            activeBrightnessHost?.authority === expectedAuthority &&
            presentationTarget == AppBrightnessPresentationTarget.PRIMARY
        ) {
            onHostPaused()
        }
    }

    internal fun closeIfCurrent(
        registration: CompanionProcessRuntime.BrightnessHostRegistration,
    ) {
        if (ownsBrightnessHost(registration)) close()
    }

    internal fun closeIfCurrent(token: Long) {
        if (ownsBrightnessHost(token)) close()
    }

    internal fun refreshActivityHostOnResume(expectedAuthority: FocusAuthority) {
        if (activeBrightnessHost?.kind == CompanionProcessRuntime.BrightnessHostKind.ACTIVITY &&
            activeBrightnessHost?.authority === expectedAuthority &&
            presentationTarget == AppBrightnessPresentationTarget.PRIMARY
        ) {
            refreshOnResume()
        }
    }

    /** The exact Presentation owner is the only one allowed to revoke secondary feedback. */
    internal fun attachSecondaryPresentation(owner: UUID) {
        secondaryPresentationOwner = owner
    }

    internal fun detachSecondaryPresentation(owner: UUID) {
        if (secondaryPresentationOwner != owner) return
        secondaryPresentationOwner = null
        onHostPaused()
    }

    /** Called by the Presentation before teardown; stale Presentation instances cannot detach a successor. */
    internal fun onSecondaryPresentationStopped(owner: UUID) = detachSecondaryPresentation(owner)

    fun refreshOnResume() {
        refreshTweakerUtilityState()
        // The return path from the row's own grant screen: the player leaves for Android settings,
        // flips All files access, and comes back. Without this the row would still read NEEDS ACCESS
        // after the permission was granted.
        refreshAllFilesAccess()
        controller?.let { activeController ->
            if (activeController.state.capabilities.hasVendorControls) {
                activeController.applyWindowFallbacks()
                state = activeController.state
                refreshPrivilegedState(
                    activeController = activeController,
                    alwaysReconcileTheme = true,
                    applyWindowFallbacks = true,
                )
            } else {
                state = activeController.refresh()
                state = activeController.reconcileThemeAwareJoystickLight().state
                activeController.applyWindowFallbacks()
            }
        }
    }

    fun dispatch(action: ControllerAction): Boolean {
        val activeController = controller
        val activeState = state
        if (activeController == null || activeState == null) {
            close()
            return true
        }
        return dispatchBrightnessOverlay(
            authority = authority,
            state = activeState,
            action = action,
            onStep = ::step,
            onSetThemeAwareJoystickLight = ::setThemeAwareJoystickLight,
            onRequestWriteAccess = ::openWriteAccess,
            onRequestPrivilegedAccess = ::requestPrivilegedAccess,
            onOpenTweaker = ::openTweakerSurface,
            onOpenFiles = ::openFilesManager,
            filesRouteAvailable = filesRouteOffered,
            tweakerAvailability = tweakerRow.availability,
            tweakerFpsCapRow = tweakerFpsCapRow,
            onStepTweakerFpsCap = ::stepTweakerFpsCap,
            primaryMButtonControlsAvailable = CompanionProcessRuntime.services(context)
                .mButtonTopology.mButtonVisibilityControlsAvailable.value,
            mButtonVisibilityPolicy = MButtonSettingsStore.visibilityPolicy.value,
            onStepMButtonVisibilityPolicy = ::stepMButtonVisibilityPolicy,
            navigationHintVisibility = NavigationHintSettingsStore.visibility.value,
            onStepNavigationHintVisibility = ::stepNavigationHintVisibility,
            calmShine = MimirCalmShineSettings.enabled,
            onSetCalmShine = ::setCalmShine,
            onStepTweakerProfile = ::stepTweakerProfile,
            // **The OR is load-bearing.** This flag gates CONFIRM into apply and BACK into cancel for
            // this row. A ceiling draft that did not ride it would draw a preview the player could
            // neither commit nor dismiss — a pending decision with no way out, which is worse than the
            // lie this story set out to fix.
            tweakerProfileDraftPending = tweakerProfileDraftPending || tweakerCeilingDraftPending,
            onApplyTweakerProfile = ::applyTweakerProfileDraft,
            onCancelTweakerProfile = ::cancelTweakerProfileDraft,
            tweakerManualFan = tweakerAdvancedCapabilities?.manualFan,
            onStepTweakerFanMode = ::stepTweakerFanMode,
            onStepTweakerFanCurve = ::stepTweakerFanCurve,
            tweakerFanCurveDraftPending = tweakerFanCurveDraftPending,
            onApplyTweakerFanCurve = ::applyTweakerFanCurveDraft,
            onCancelTweakerFanCurve = ::cancelTweakerFanCurveDraft,
            tweakerProfileChangeInProgress = tweakerProfileChange != null,
            tweakerFanControlChangeInProgress = tweakerFanControlChange != null,
            performanceOverlayVisibility = PerformanceOverlaySettingsStore.visibility.value,
            onStepPerformanceOverlay = ::stepPerformanceOverlay,
            tweakerUsageAccessState = tweakerUsageAccessState,
            onRequestTweakerUsageAccess = ::openTweakerUsageAccess,
            surface = surface,
            // Controller CANCEL/SYSTEM_BACK and the footer's confirm share this callback. Back must
            // consume a routed Tweaker surface first, while a directly opened Tweaker still closes.
            onDismiss = ::handleBack,
        )
    }

    internal fun stepMButtonVisibilityPolicy(direction: Int) {
        val current = MButtonSettingsStore.visibilityPolicy.value
        val next = current.step(direction)
        if (next == current) return
        MButtonSettingsStore.setVisibilityPolicy(context, next)
        // O13 : turning the M button ON arms the one notification ask, and it is weighed when
        // MButtonKeepAliveService actually starts for it (MButtonTopologyController.reconcile). Only a PRIMARY_SINGLE
        // M runs that service, so a Bottom M never asks for it. Turning it off disarms; timeout steps change neither.
        if (!current.buttonEnabled && next.buttonEnabled) {
            MimirNotificationPermissionAsk.armMButton(context)
        } else if (!next.buttonEnabled) {
            MimirNotificationPermissionAsk.disarmMButton(context)
        }
    }

    internal fun stepNavigationHintVisibility(direction: Int) {
        val current = NavigationHintSettingsStore.visibility.value
        val next = current.step(direction)
        if (next == current) return
        NavigationHintSettingsStore.setVisibility(context, next)
    }

    /**
     * Utility's Calm shine row. The store writes the durable choice and publishes it to every window through
     * [MimirCalmShineSettings], so this is one call and no coordinator state; a press that changes nothing is a no-op.
     */
    internal fun setCalmShine(enabled: Boolean) {
        if (MimirCalmShineSettings.enabled == enabled) return
        AppearanceOverrideStore.setCalmShine(context, enabled)
    }

    internal fun refreshTweakerUtilityState(periodic: Boolean = false) {
        // Selection owns the sole Binder reservation and publishes its own authoritative refresh on
        // completion. Starting an observation here can only replace the row the player acted on with a
        // transient unavailable projection.
        if (
            tweakerSelectionJob?.isActive == true ||
            tweakerFanControlJob?.isActive == true ||
            // A ceiling write holds the same exclusive reservation on the daemon route, and its interim
            // line is the row the player is looking at. A refresh landing mid-write would replace both.
            tweakerCeilingJob?.isActive == true ||
            tweakerRefreshJob?.isActive == true
        ) return
        projectTweakerUtilityState()
        val generation = ++tweakerRefreshGeneration
        tweakerRefreshJob = scope.launch {
            try {
                if (periodic) tweakerPort.refreshForPanelTick() else tweakerPort.refreshCapabilities()
                if (generation == tweakerRefreshGeneration) projectTweakerUtilityState()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (generation == tweakerRefreshGeneration) projectTweakerUtilityState()
            } finally {
                if (generation == tweakerRefreshGeneration) tweakerRefreshJob = null
            }
        }
    }

    private fun projectTweakerUtilityState() {
        val freshRow = tweakerPort.utilityRowState()
        val freshFanGpuTempC = TweakerBridge.utilityFanGpuTempC()
        val freshFanCpuTempC = TweakerBridge.utilityFanCpuTempC()
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        // Hold the last known fan reading across a stale/momentarily-null sample, but never past a
        // genuine profile switch: a reading captured under a different profile (including Stock,
        // which has no Mimir-owned fan session) must not be shown as though it belonged to this one.
        // See the [retainedFanControl] doc comment for the full policy; [close] handles session end.
        if (freshRow.selectedProfileId != retainedFanProfileId) {
            retainedFanControl = null
            retainedFanGpuTempC = null
            retainedFanGpuTempCAtElapsedRealtimeMs = null
            retainedFanCpuTempC = null
            retainedFanCpuTempCAtElapsedRealtimeMs = null
            retainedFanProfileId = freshRow.selectedProfileId
        }
        freshRow.fanControl?.let { retainedFanControl = it }
        // The hold clock stamps at ACCEPTANCE (this projection cycle), not at the sample's own
        // `sampledAtElapsedRealtimeMs` -- the 3 500 ms freshness gate already spent part of the
        // sample's age before it reached here, and double-counting that against the 4 000 ms hold
        // would silently shrink the actual hold window below what §4.3 states (worst case ~7.5s).
        freshFanGpuTempC?.let {
            retainedFanGpuTempC = it
            retainedFanGpuTempCAtElapsedRealtimeMs = nowElapsedRealtimeMs
        }
        freshFanCpuTempC?.let {
            retainedFanCpuTempC = it
            retainedFanCpuTempCAtElapsedRealtimeMs = nowElapsedRealtimeMs
        }
        // The derate is matched against the row's own effective profile first, then folded INTO
        // the row -- the row cannot carry a derate belonging to a profile it is not showing.
        val matchedForRow = matchedTweakerAdvancedCapabilities(
            row = freshRow.copy(fanControl = retainedFanControl),
            advanced = TweakerBridge.advancedCapabilities,
        )
        val row = freshRow.copy(
            fanControl = retainedFanControl,
            thermalDerate = matchedForRow?.thermalDerate,
        )
        val advanced = matchedTweakerAdvancedCapabilities(
            row = row,
            advanced = TweakerBridge.advancedCapabilities,
        )
        surfaceTweakerRestoreIfNewlyLegible(advanced?.lastRestore)
        surfaceTweakerDerateIfNewlyLegible(advanced?.thermalDerate)
        restoreRememberedManualFanIfNeeded(advanced?.manualFan)
        val access = resolveTweakerUsageAccessState(
            TweakerBridge.isTweakerInstalled(), TweakerBridge.capabilities,
        )
        tweakerPanelProjection = TweakerPanelProjection(
            row = row,
            advancedCapabilities = advanced,
            usageAccessState = access,
            // Re-read on every projection, not cached from open() — a permission revoked while Mimir is
            // backgrounded must be caught the moment the player returns, not just the moment they next
            // press the row. See performanceOverlayRowValue()'s doc comment for the defect this closes.
            canDrawOverlays = Settings.canDrawOverlays(context),
        )
        // Bounded retention (design spec §4.3): a held reading survives at most
        // FAN_TEMPERATURE_RETENTION_HOLD_MS past the projection cycle that accepted it, then renders
        // as absent rather than painting a stale number forever if the daemon dies (G-12). The
        // PROFILE row's `· 88°C` suffix reads this same tweakerFanGpuTempC field, so it now inherits
        // the bound too -- it stays visible exactly as long as before under a live daemon, and goes
        // honestly blank instead of stale-forever once one dies. See [retainedFanControl]'s doc
        // comment for why `retainedFanControl` (dutyPercent/rpm) itself stays unbounded.
        tweakerFanGpuTempC = boundedRetainedFanTempC(
            retained = retainedFanGpuTempC,
            retainedAtElapsedRealtimeMs = retainedFanGpuTempCAtElapsedRealtimeMs,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
        tweakerFanCpuTempC = boundedRetainedFanTempC(
            retained = retainedFanCpuTempC,
            retainedAtElapsedRealtimeMs = retainedFanCpuTempCAtElapsedRealtimeMs,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
        // The frame-cap subject rides the same cadence as the rest of the projection, off the input
        // path. Its options come from `advanced`, so it must follow the projection, not lead it.
        refreshTweakerFpsCapRow()
        // A drafted profile that loses qualification or reachability retires LOUDLY. Dropping it
        // silently would leave the row showing a profile the device can no longer enter, which is
        // exactly the fake mode change this surface exists to avoid.
        tweakerProfileDraft?.let { draft ->
            if (!draft.admitted(row)) retireTweakerProfileDraft(draft.previewProfileId)
        }
        tweakerFanCurveDraft = tweakerFanCurveDraft?.takeIf { draft ->
            advanced?.manualFan?.let(draft::matches) == true
        }
    }

    private fun retireTweakerProfileDraft(profileId: String) {
        tweakerProfileDraft = null
        publishTweakerMessage(
            "${tweakerProfileDisplayCaps(profileId)} · NO LONGER AVAILABLE",
            MimirMessageClass.Warning,
        )
    }

    internal fun stepTweakerFanMode(direction: Int): Boolean {
        val fan = tweakerAdvancedCapabilities?.manualFan ?: return false
        val ordered = listOf(TweakerFanMode.AUTO, TweakerFanMode.MANUAL).filter { it in fan.modes }
        val current = ordered.indexOf(fan.currentMode).takeIf { it >= 0 } ?: return false
        val delta = if (direction < 0) -1 else 1
        val next = ordered[(current + delta).coerceIn(0, ordered.lastIndex)]
        if (next == fan.currentMode) return false
        tweakerFanCurveDraft = null
        // returning to Manual forgot the response the player had chosen.
        // `fan.currentCurveBias` reads zero here BY CONTRACT -- the daemon requires bias 0 while
        // the mode is AUTO -- so re-entering with it was reading back the mode switch's own side
        // effect. AUTO still goes out as an explicit 0 (the daemon rejects anything else).
        applyTweakerFanControl(
            fan,
            next,
            tweakerFanModeEntryBias(
                next = next,
                rememberedManualBias = TweakerBridge.rememberedManualCurveBias(fan),
                currentBias = fan.currentCurveBias,
            ),
        )
        return true
    }

    internal fun stepTweakerFanCurve(direction: Int): Boolean {
        val fan = tweakerAdvancedCapabilities?.manualFan
            ?.takeIf { it.currentMode == TweakerFanMode.MANUAL }
            ?: return false
        val currentPreview = tweakerFanCurveDraft?.takeIf { it.matches(fan) }?.previewBias
            ?: fan.currentCurveBias
        val nextDraft = steppedTweakerFanCurveDraft(tweakerFanCurveDraft, fan, direction)
        val nextPreview = nextDraft?.previewBias ?: fan.currentCurveBias
        if (nextPreview == currentPreview) return false
        tweakerProfileDraft = null
        tweakerFanCurveDraft = nextDraft
        return true
    }

    internal fun previewTweakerFanCurveBias(curveBias: Int) {
        val fan = tweakerAdvancedCapabilities?.manualFan
            ?.takeIf { it.currentMode == TweakerFanMode.MANUAL }
            ?: return
        tweakerProfileDraft = null
        tweakerFanCurveDraft = updatedTweakerFanCurveDraft(tweakerFanCurveDraft, fan, curveBias)
    }

    internal fun applyTweakerFanCurveDraft(): Boolean {
        val fan = tweakerAdvancedCapabilities?.manualFan
            ?.takeIf { it.currentMode == TweakerFanMode.MANUAL }
            ?: return false
        val draft = tweakerFanCurveDraft?.takeIf { it.matches(fan) } ?: return false
        // Once Apply is accepted, the blocking settlement layer owns the interaction and the panel
        // returns to committed truth. The preview must never masquerade as applied underneath it.
        tweakerFanCurveDraft = null
        applyTweakerFanControl(fan, TweakerFanMode.MANUAL, draft.previewBias)
        return true
    }

    internal fun cancelTweakerFanCurveDraft(): Boolean {
        if (!tweakerFanCurveDraftPending) {
            tweakerFanCurveDraft = null
            return false
        }
        tweakerFanCurveDraft = null
        return true
    }

    private fun applyTweakerFanControl(
        fan: TweakerManualFanCapability,
        mode: TweakerFanMode,
        curveBias: Int,
        // A re-arm is not the player asking for anything; it is Mimir restoring what they already
        // chose. It must not narrate. Without this, every session start would publish "FAN RESPONSE
        // APPLIED" at a player who did not touch the control -- and, when the panel happens to be
        // shut, queue it to surface later against nothing at all.
        silent: Boolean = false,
    ) {
        if (tweakerFanControlJob?.isActive == true || tweakerProfileChange != null) {
            if (!silent) publishTweakerMessage("FAN CONTROL SETTLING", MimirMessageClass.Guidance)
            return
        }
        val operationId = ++tweakerFanControlOperationId
        tweakerFanControlChange = TweakerFanControlChangeState(
            operationId = operationId,
            priorMode = fan.currentMode,
            requestedMode = mode,
            priorCurveBias = fan.currentCurveBias,
            requestedCurveBias = curveBias,
        )
        if (!silent) {
            publishTweakerMessage(
                tweakerFanControlChange?.supportingTextCaps().orEmpty(),
                MimirMessageClass.Guidance,
            )
        }
        tweakerFanControlJob = scope.launch {
            try {
                val result = runCatching { TweakerBridge.setFanControl(fan, mode, curveBias) }
                    .getOrElse {
                        TweakerFanControlAssessment.Uncertain("Fan control call failed")
                    }
                projectTweakerUtilityState()
                when (result) {
                    is TweakerFanControlAssessment.Accepted -> {
                        // P8: the accepted branch published a toast and nothing else (§1.14 "Accepted →
                        // no log"); REJECTED/UNKNOWN already had logFanAssessment, so this is its
                        // Log.i twin rather than a Log.w for a change that succeeded.
                        logFanAssessmentInfo("ACCEPTED", "mode=${result.mode} bias=${result.curveBias}")
                        // Remember what MANUAL actually settled at, never what was requested: the
                        // daemon is free to snap or clamp, and the value the player returns to has
                        // to be the one their fan was really running.
                        if (result.mode == TweakerFanMode.MANUAL) {
                            TweakerBridge.rememberManualCurveBias(fan.profileId, result.curveBias)
                        }
                        // The MODE is remembered for both choices. Picking Auto deliberately has to
                        // stick just as firmly, or the re-arm below would fight the player.
                        TweakerBridge.rememberFanMode(fan.profileId, result.mode)
                        if (!silent) {
                            publishTweakerMessage(
                                if (result.mode == TweakerFanMode.AUTO) {
                                    "FAN CONTROL · AUTO"
                                } else {
                                    "FAN RESPONSE APPLIED"
                                },
                                MimirMessageClass.Confirmation,
                            )
                        }
                    }
                    // The headline is player-facing; the cause line stays, because
                    // without it a decline is indistinguishable from a parse fault and the only way
                    // to tell them apart is a daemon logcat the player does not have.
                    //
                    // **The two branches must never be collapsed into one.** The single
                    // "FAN RESPONSE NOT APPLIED" they replaced rendered UNCERTAIN as a confirmed
                    // failure, and four fan changes that had applied and verified on hardware were
                    // reported to the player as failures because the client could not parse the
                    // response it got back. DECLINED means the daemon said no. UNCONFIRMED means we
                    // do not know -- and the change may well have landed. Anything that makes the
                    // second read like the first re-opens the defect that cost a whole diagnosis.
                    is TweakerFanControlAssessment.Rejected -> {
                        logFanAssessment("REJECTED", "${result.code} · ${result.message}")
                        publishTweakerMessage(
                            FAN_MESSAGE_DECLINED,
                            MimirMessageClass.Warning,
                            supportingText = fanDebugDetail("${result.code} · ${result.message}"),
                        )
                    }
                    is TweakerFanControlAssessment.Uncertain -> {
                        logFanAssessment("UNKNOWN", result.reason)
                        publishTweakerMessage(
                            // NOT "not applied": the change may well have landed. Saying so is the
                            // difference between an honest surface and the one that misled us.
                            FAN_MESSAGE_UNCONFIRMED,
                            MimirMessageClass.Warning,
                            supportingText = fanDebugDetail(result.reason),
                        )
                    }
                }
            } finally {
                if (tweakerFanControlChange?.operationId == operationId) {
                    tweakerFanControlChange = null
                }
                tweakerFanCurveDraft = null
                tweakerFanControlJob = null
            }
        }
    }

    /**
     * The single coordinator path behind left, right, and touch on the Tweaker PROFILE row.
     *
     * This moves a DRAFT — no transaction, no device write, no latency. The row used to commit on every
     * step, so Stock → Quiet → Balanced → Performance ran three complete Guardian settlements to reach
     * one destination; it now costs exactly one, at [applyTweakerProfileDraft].
     *
     * The overlay dispatcher deliberately does not pre-judge whether a press "counts" — every press lands
     * here, this decides, and every branch says something. A boundary or an absent service therefore gets
     * an honest line instead of silence, and only a genuine draft movement reports back `true` (which is
     * what drives nav feedback), so a no-op can never be dressed up as a mode change.
     *
     * Deliberately NOT gated on [tweakerProfileChange]: that guard exists to stop overlapping
     * transactions, and a draft starts none. [applyTweakerProfileDraft] still respects it.
     */
    internal fun stepTweakerProfile(direction: Int): Boolean {
        // The row the player acted on is the admission truth for this press. Re-reading the bridge here
        // races the foreground refresh and can turn a visibly READY row into a false UNAVAILABLE result.
        val visibleRow = tweakerRow
        // The discovered path steps a clock scale rather than a named ring, so it has its own draft and
        // its own outcomes. Everything else about the press is identical: the visible row is the
        // admission truth, a refusal is reported rather than swallowed, and no device write happens here.
        if (visibleRow.availability == TweakerUtilityAvailability.DISCOVERED_CEILING) {
            val outcome = steppedTweakerCeilingDraftOutcome(tweakerCeilingDraft, visibleRow, direction)
            if (outcome !is TweakerUtilityStepOutcome.CeilingChanged) {
                publishTweakerMessage(outcome.utilityToastCaps(), MimirMessageClass.Warning)
                return false
            }
            tweakerFanCurveDraft = null
            tweakerProfileDraft = null
            tweakerCeilingDraft = tweakerCeilingDraftFor(visibleRow, outcome.khz)
            return true
        }
        val outcome = steppedTweakerProfileDraftOutcome(tweakerProfileDraft, visibleRow, direction)
        if (outcome !is TweakerUtilityStepOutcome.Changed) {
            publishTweakerMessage(outcome.utilityToastCaps(), MimirMessageClass.Warning)
            return false
        }
        tweakerFanCurveDraft = null
        tweakerCeilingDraft = null
        tweakerProfileDraft = tweakerProfileDraftFor(visibleRow, outcome.profileId)
        return true
    }

    /**
     * CONFIRM on a discovered-ceiling row.
     *
     * **No Guardian settlement.** A ceiling is player-owned and boot-bound rather than a leased session,
     * so this borrows none of the profile path's settlement machinery — it writes, re-reads, and renders
     * what came back.
     *
     * The policy is re-read at APPLY rather than carried in the draft. `TweakerDiscoveredCeiling` holds
     * primitives so it can cross into `core-tweaker-client`, and acting on a freshly read policy is
     * correct regardless: thermal and the vendor HAL both move these nodes while the rail is open.
     *
     * **The write is no longer synchronous, and the ruling is why (2026-09-11).** The policy of
     * 2026-09-03 routes the ceiling through the Tweaker daemon wherever one is installed, which makes
     * this press a Binder round trip; running that on the main thread would put the daemon's latency
     * inside the frame that is meant to acknowledge the press. So the write goes to the coordinator's
     * existing background scope on [privilegedIoDispatcher] — the same dispatcher every other privileged
     * call in this file already uses — the row states an interim line immediately, and the outcome
     * replaces it when it lands.
     *
     * **Scheduling is not an authoritative hardware change**, so like [applyTweakerProfileDraft]'s own
     * transaction branch this returns false: nav feedback must never fire for a write whose answer has
     * not arrived. The two branches that decide without writing — already in force, no longer offered —
     * still answer synchronously and truthfully.
     */
    private fun applyTweakerCeilingDraft(visibleRow: TweakerUtilityRowState): Boolean {
        when (val decision = tweakerCeilingApplyDecision(visibleRow, tweakerCeilingDraft)) {
            TweakerCeilingApplyDecision.NoDraft -> return false

            is TweakerCeilingApplyDecision.Unavailable -> {
                tweakerCeilingDraft = null
                publishTweakerMessage("CEILING NO LONGER OFFERED", MimirMessageClass.Warning)
                return false
            }

            is TweakerCeilingApplyDecision.AlreadyInForce -> {
                tweakerCeilingDraft = null
                publishTweakerMessage(
                    "${companionTunerClockLabel(decision.khz)} · ALREADY IN FORCE",
                    MimirMessageClass.Guidance,
                )
                return true
            }

            TweakerCeilingApplyDecision.Release -> {
                // The withdrawal restores what was CAPTURED, which is not necessarily the top of the
                // table: a device that booted under a vendor or thermal cap gets that back, not the
                // maximum. Giving back what was there is the honest act; asserting the maximum would be
                // a write dressed as a restore, which `MimirTunerWrite` refuses to do for this reason.
                // On the daemon route the daemon owns the capture and gives back its own.
                startTweakerCeilingWrite(draftedKhz = null) { MimirTunerWrite.withdraw() }
                return false
            }

            is TweakerCeilingApplyDecision.Apply -> {
                val policy = primeCpuPolicy(readTunerAvailability().policies)
                if (policy == null) {
                    tweakerCeilingDraft = null
                    publishTweakerMessage("TWEAKER UNAVAILABLE", MimirMessageClass.Warning)
                    return false
                }
                // Bound before the lambda: what the write asks for and what the row compares the
                // answer against must be the same number, read once, at the moment of the press.
                val requestedKhz = decision.khz
                startTweakerCeilingWrite(draftedKhz = requestedKhz) {
                    MimirTunerWrite.requestCeiling(policy, requestedKhz)
                }
                return false
            }
        }
    }

    /** The line the row shows while a privileged ceiling write is in flight. */
    private val tweakerCeilingInterimMessage = "TWEAKER · CEILING…"

    /**
     * Run one ceiling write off the main thread and report exactly what came back.
     *
     * **The draft is retired when the OUTCOME arrives, not when the press is accepted.** A draft cleared
     * up front would leave the row showing the committed ceiling for the whole round trip, so a refusal
     * would land on a row that had already silently discarded what the player asked for.
     *
     * A throw is not an outcome: `runCatching` here matches the fan path's existing discipline, and a
     * transaction that died on the way out is reported as unreachable rather than taking the
     * coordinator's scope down with it.
     */
    private fun startTweakerCeilingWrite(
        draftedKhz: Int?,
        write: () -> MimirTunerWriteOutcome,
    ) {
        publishTweakerMessage(tweakerCeilingInterimMessage, MimirMessageClass.Guidance)
        // A second confirm while one is in flight re-states the interim line and starts nothing. Two
        // privileged writes racing one node is the arbitration failure the route exists to end, and a
        // press that produced no line at all would read as a dead row.
        if (tweakerCeilingJob?.isActive == true) return
        tweakerCeilingJob = scope.launch {
            try {
                val outcome = withContext(privilegedIoDispatcher) {
                    runCatching { write() }
                        .onFailure { failure ->
                            runCatching {
                                android.util.Log.w("MimirTuner", "ceiling write threw: ${failure.message}")
                            }
                        }
                        .getOrNull()
                }
                tweakerCeilingDraft = null
                // Invalidated after ANY outcome that could have moved the node, which is any outcome at
                // all: a refusal still crossed to a daemon that may have changed the row for its own
                // reasons, and a stale memo is most visible exactly after a press.
                TweakerBridge.invalidateDiscoveredCeiling()
                projectTweakerUtilityState()
                publishTweakerCeilingOutcome(outcome, draftedKhz)
            } finally {
                tweakerCeilingJob = null
            }
        }
    }

    /**
     * One outcome, one line on the row.
     *
     * **[MimirTunerWriteOutcome.InForce] renders the read-back, not the request** — thermal and the HAL
     * both speak after the writer does. The one exception is a daemon that CLAMPED: when the admitted
     * step is not the step the player drafted, the admitted step is what the daemon actually applied and
     * saying anything else would leave the row claiming a number nobody wrote.
     *
     * A refusal is rendered by its CODE rather than by a single "not applied", because the two refusals
     * a player can act on — a live profile owning the node, and a route that cannot resolve the pair —
     * are indistinguishable in one sentence and demand different things of them.
     */
    private fun publishTweakerCeilingOutcome(outcome: MimirTunerWriteOutcome?, draftedKhz: Int?) {
        when (outcome) {
            // The transaction threw. Nobody judged anything, which is what unreachable means.
            null -> publishTweakerMessage("TWEAKER UNREACHABLE", MimirMessageClass.Warning)

            is MimirTunerWriteOutcome.InForce -> {
                val clamped = outcome.admittedKhz != null &&
                    draftedKhz != null &&
                    outcome.admittedKhz != draftedKhz
                val shown = if (clamped) outcome.admittedKhz else outcome.khz
                publishTweakerMessage(
                    if (shown == null) {
                        "CEILING NOT APPLIED"
                    } else {
                        "TWEAKER · ${companionTunerClockLabel(shown)}"
                    },
                    if (shown == null) MimirMessageClass.Warning else MimirMessageClass.Guidance,
                )
            }

            is MimirTunerWriteOutcome.Released ->
                publishTweakerMessage("TWEAKER · CEILING RELEASED", MimirMessageClass.Guidance)

            is MimirTunerWriteOutcome.NothingToRelease ->
                publishTweakerMessage("NOTHING TO RELEASE", MimirMessageClass.Guidance)

            is MimirTunerWriteOutcome.Refused -> publishTweakerMessage(
                when (outcome.code) {
                    MimirTunerRefusalProfileActive -> "PROFILE ACTIVE · CEILING NOT APPLIED"
                    // Already spelled for this rail by the seam: TWEAKER BUSY / PAIR UNRESOLVED.
                    MimirTunerRefusalRouteRefused -> outcome.detail
                    else -> "CEILING REFUSED · ${outcome.code}"
                },
                MimirMessageClass.Warning,
            )

            is MimirTunerWriteOutcome.Unavailable ->
                publishTweakerMessage("TWEAKER UNREACHABLE", MimirMessageClass.Warning)
        }
    }

    /**
     * The one press on this row that costs a Guardian settlement.
     *
     * Every branch is reportable and only [TweakerProfileApplyDecision.Apply] opens a transaction:
     * confirming the profile already running consumes the press without one, and a drafted profile the
     * row no longer admits is refused rather than silently swapped for something else. Once Apply is
     * accepted the settlement layer owns the interaction and the panel returns to committed truth, so a
     * failed apply lands the row on the LIVE profile and never on the draft.
     */
    internal fun applyTweakerProfileDraft(): Boolean {
        if (tweakerProfileChange != null || tweakerSelectionJob?.isActive == true) {
            android.util.Log.i(
                "MimirUtility",
                "apply profile draft ignored: in progress change=$tweakerProfileChange " +
                    "selectionJobActive=${tweakerSelectionJob?.isActive == true}",
            )
            publishTweakerMessage("PROFILE CHANGE IN PROGRESS", MimirMessageClass.Guidance)
            return false
        }
        if (tweakerRow.availability == TweakerUtilityAvailability.DISCOVERED_CEILING) {
            return applyTweakerCeilingDraft(tweakerRow)
        }
        val visibleRow = tweakerRow
        val requestedProfileId = when (
            val decision = tweakerProfileApplyDecision(visibleRow, tweakerProfileDraft)
        ) {
            TweakerProfileApplyDecision.NoDraft -> {
                android.util.Log.i(
                    "MimirUtility",
                    "apply profile draft ignored: no draft availability=${visibleRow.availability} " +
                        "effective=${visibleRow.effectiveProfileId}",
                )
                return false
            }
            is TweakerProfileApplyDecision.AlreadyActive -> {
                android.util.Log.i(
                    "MimirUtility",
                    "apply profile draft ignored: already active profile=${decision.profileId}",
                )
                tweakerProfileDraft = null
                publishTweakerMessage(
                    "${tweakerProfileDisplayCaps(decision.profileId)} · ALREADY ACTIVE",
                    MimirMessageClass.Guidance,
                )
                return true
            }
            is TweakerProfileApplyDecision.Unavailable -> {
                android.util.Log.i(
                    "MimirUtility",
                    "apply profile draft ignored: unavailable profile=${decision.profileId} " +
                        "availability=${visibleRow.availability} selectable=${visibleRow.selectableProfileIds}",
                )
                retireTweakerProfileDraft(decision.profileId)
                return false
            }
            is TweakerProfileApplyDecision.Apply -> decision.profileId
        }
        tweakerProfileDraft = null
        tweakerFanCurveDraft = null
        tweakerRefreshGeneration++
        val refreshToSettle = tweakerRefreshJob
        refreshToSettle?.cancel()
        tweakerRefreshJob = null
        val operationId = ++tweakerSelectionOperationId
        val control = TweakerProfileSelectionControl()
        tweakerSelectionControl = control
        tweakerProfileChange = TweakerProfileChangeState(
            operationId = operationId,
            priorProfileId = visibleRow.effectiveProfileId,
            requestedProfileId = requestedProfileId,
            phase = TweakerProfileChangePhase.APPLYING,
        )
        publishTweakerMessage(
            "APPLYING · ${tweakerProfileDisplayCaps(requestedProfileId)}",
            MimirMessageClass.Guidance,
        )
        tweakerSelectionJob = scope.launch {
            try {
                refreshToSettle?.cancelAndJoin()
                val result = try {
                    tweakerPort.selectSystemProfileSettled(
                        profileId = requestedProfileId,
                        admittedProfileIds = visibleRow.selectableProfileIds.toSet(),
                        control = control,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    TweakerSystemProfileSelectionResult.Uncertain(
                        failure.message.orEmpty().ifBlank { "Profile selection failed" },
                    )
                }
                // Release-safe, like the pair/availability line TweakerBridge already emits. A profile
                // change that fails says one of eight capped strings on screen and nothing anywhere
                // else, so a device report cannot name the branch it took and the failure has to be
                // reproduced blind. This is the log that ends that.
                runCatching {
                    android.util.Log.i(
                        "MimirTweakerSelection",
                        "requested=$requestedProfileId prior=${visibleRow.effectiveProfileId} " +
                            "result=${result.diagnosticLabel()} target=$presentationTarget",
                    )
                }
                runCatching { tweakerPort.refreshCapabilities() }
                projectTweakerUtilityState()
                if (tweakerProfileChange?.operationId == operationId) {
                    tweakerProfileChange = null
                    tweakerSelectionControl = null
                    publishTweakerMessage(
                        result.systemProfileToastCaps(),
                        result.systemProfileMessageClass(),
                    )
                    closeAfterSecondaryCancellationIfSettled(operationId, result)
                }
            } finally {
                control.markSettled()
                if (tweakerProfileChange?.operationId == operationId) {
                    tweakerProfileChange = null
                    tweakerSelectionControl = null
                }
                if (tweakerSelectionOperationId == operationId) tweakerSelectionJob = null
            }
        }
        // Scheduling is not an authoritative hardware change and must not fire success navigation feedback.
        return false
    }

    /** Cancel reverts to the LIVE profile. It starts nothing, so it can never leave a half-applied row. */
    /**
     * BACK on this row dismisses whichever preview it is holding.
     *
     * One row, two possible drafts — a certified profile or a discovered ceiling — and they are mutually
     * exclusive by construction. Both are cleared unconditionally so a draft that stopped being admitted
     * (the write host went away, the daemon arrived) cannot survive as an invisible pending decision.
     */
    internal fun cancelTweakerProfileDraft(): Boolean {
        val pending = tweakerProfileDraftPending || tweakerCeilingDraftPending
        tweakerProfileDraft = null
        tweakerCeilingDraft = null
        return pending
    }

    internal fun handleBack() {
        if (tweakerProfileChange != null) {
            requestTweakerProfileCancellation()
            return
        }
        if (cancelTweakerProfileDraft()) return
        if (cancelTweakerFanCurveDraft()) return
        surfaceReturnTarget?.takeIf { it != surface }?.let { returnSurface ->
            surface = returnSurface
            surfaceReturnTarget = null
            return
        }
        close()
    }

    /**
     * The Bottom panel's footer when it reads "Back": the Tweaker surface was opened from the Bottom Utility
     * (2026-09-11, tweakerSurfaceOpenTarget), so Back is a route return to that Utility, exactly as on Main.
     * Before this the SECONDARY host wired Back to [dismissFromSecondaryPresentation], and a footer that said
     * Back closed the whole panel (surface map finding F2). A footer that reads "Close" still dismisses.
     */
    internal fun handleSecondaryBack() {
        if (backActionLabel == "Back") handleBack() else dismissFromSecondaryPresentation()
    }

    /** Secondary touch dismissal: discard drafts, or request profile cancellation before closing. */
    internal fun dismissFromSecondaryPresentation(): Boolean {
        val activeProfileChange = tweakerProfileChange
        // P6: no line anywhere on the Utility close path (§1.13 "SILENT").
        runCatching {
            android.util.Log.i("MimirUtility", "secondary dismiss profileChange=${activeProfileChange != null}")
        }
        if (activeProfileChange != null) {
            // Repeated taps while CANCELLING must not issue another cancellation or replace the
            // operation token with a newer presentation intent.
            closeAfterSecondaryProfileCancellationOperationId = activeProfileChange.operationId
            requestTweakerProfileCancellation()
            return true
        }
        cancelTweakerProfileDraft()
        cancelTweakerFanCurveDraft()
        close()
        return true
    }

    private fun closeAfterSecondaryCancellationIfSettled(
        operationId: Long,
        result: TweakerSystemProfileSelectionResult,
    ) {
        if (closeAfterSecondaryProfileCancellationOperationId != operationId ||
            presentationTarget != AppBrightnessPresentationTarget.SECONDARY ||
            tweakerSelectionOperationId != operationId ||
            tweakerProfileChange != null ||
            result !is TweakerSystemProfileSelectionResult.Cancelled
        ) return
        closeAfterSecondaryProfileCancellationOperationId = null
        close()
    }

    internal fun requestTweakerProfileCancellation(): Boolean {
        val active = tweakerProfileChange ?: return false
        if (active.phase == TweakerProfileChangePhase.CANCELLING) return true
        val control = tweakerSelectionControl ?: return true
        if (!control.requestCancellation()) {
            publishTweakerMessage("PROFILE CHANGE SETTLING", MimirMessageClass.Guidance)
            return true
        }
        tweakerProfileChange = active.copy(phase = TweakerProfileChangePhase.CANCELLING)
        publishTweakerMessage(
            "RESTORING · ${tweakerProfileDisplayCaps(active.priorProfileId)}",
            MimirMessageClass.Guidance,
        )
        return true
    }

    internal fun consumeTweakerMessage(eventId: Long) {
        if (tweakerMessage?.eventId == eventId) tweakerMessage = null
    }

    /**
     *
     *
     * The daemon has always computed the reason and shipped it over the wire, and
     * [MimirCopy.TweakerProfileDroppedTelemetryLost] / [MimirCopy.TweakerProfileDroppedRestartHint]
     * already provide the shared copy in `MimirCopy`. This surface uses those strings
     * **verbatim**.
     *
     * Only the telemetry-transport class is surfaced. The other restore reasons (an ordinary session
     * end, an owner token dying, a lease expiring) are not failures the player can act on, and a
     * message with no action is noise that teaches people to ignore the channel.
     *
     * Fires once per distinct reason. [lastSurfacedRestoreReason] is what stops the projection
     * refresh -- which runs on every panel tick -- from re-announcing the same incident forever.
     * A null reason deliberately CLEARS the latch rather than being ignored, so a second genuine
     * drop with the same reason later in the session is announced again instead of being swallowed
     * as a duplicate.
     */
    private fun surfaceTweakerRestoreIfNewlyLegible(lastRestore: TweakerLastRestore?) {
        if (lastRestore == null) {
            lastSurfacedRestoreKey = null
            return
        }
        if (!shouldSurfaceTweakerRestore(lastRestore, lastSurfacedRestoreKey)) return
        lastSurfacedRestoreKey = lastRestore.incidentKey
        // The telemetry-transport class keeps its own sentence and restart hint; a guardian
        // hand-back (fan-fence fix F4) names the guardian and asks for a re-apply instead --
        // restarting the Tweaker is not the remedy there, and saying so would be wrong.
        if (lastRestore.isTelemetryTransport) {
            publishTweakerMessage(
                text = MimirCopy.TweakerProfileDroppedTelemetryLost,
                messageClass = MimirMessageClass.Warning,
                supportingText = MimirCopy.TweakerProfileDroppedRestartHint,
            )
        } else {
            publishTweakerMessage(
                text = MimirCopy.TweakerProfileDroppedGuardianHandback,
                messageClass = MimirMessageClass.Warning,
                supportingText = MimirCopy.TweakerProfileDroppedReapplyHint,
            )
        }
    }

    /**
     * Tell the player, once per incident, that heat cut their clocks.
     *
     * **A device report described reduced `performance` clocks mid-game with no notice.**
     * The row went on reading `performance` at nominal caps while the hardware sat a rung down,
     * and the ladder is monotonically non-decreasing, so it was never coming back on its own for
     * that session. Losing performance silently is bad; losing it silently and permanently is
     * worse.
     *
     * Null clears the latch so a second, later incident re-announces -- same reasoning as the
     * restore latch beside it.
     */
    private fun surfaceTweakerDerateIfNewlyLegible(derate: TweakerThermalDerate?) {
        if (derate == null) {
            lastSurfacedDerateKey = null
            return
        }
        if (!shouldSurfaceTweakerDerate(derate, lastSurfacedDerateKey)) return
        lastSurfacedDerateKey = tweakerDerateKey(derate)
        publishTweakerMessage(
            text = MimirCopy.tweakerProfileDeratedHeat(tweakerProfileDisplayCaps(derate.profileId)),
            messageClass = MimirMessageClass.Warning,
            supportingText = MimirCopy.TweakerProfileDeratedHoldHint,
        )
    }

    /**
     * Observe, once per session, that the player's remembered MANUAL fan choice survived an
     * owner-death fallback -- and leave renewing it to the daemon.
     *
     * **The daemon now owns renewal end-to-end**: D2 enters MANUAL directly from Stock
     * on the ordinary re-arm select, P3 renews a SMART contract on its own backoff, and P4 re-enters
     * MANUAL after an external change. This function used to ALSO issue a silent
     * `applyTweakerFanControl(..., MANUAL, silent = true)` here -- a second renewal driver competing
     * with the daemon's. The two never converged: any daemon-side rollback of that silent write
     * (session-lost/E1 handling) mints a new generation, a new generation mints a new session key
     * here (see [tweakerFanSessionKey]), and a new session key re-armed the latch and fired the same
     * silent request again on the very next projection.
     *
     * See [shouldRestoreRememberedManualFan] for the ruling on WHEN a remembered Manual should be
     * considered live -- this function still evaluates that gate, it just no longer acts on a
     * positive answer. The session-key latch is kept exactly as before so the observation below logs
     * once per session identity rather than once per refresh tick. Explicit player gestures (the mode
     * toggle calling [applyTweakerFanControl] directly) are completely unaffected -- this function
     * never calls it.
     */
    private fun restoreRememberedManualFanIfNeeded(capability: TweakerManualFanCapability?) {
        val fan = capability ?: return
        val remembered = TweakerBridge.rememberedFanMode(fan.profileId)
        if (tweakerFanControlChange != null || tweakerProfileChange != null) {
            logSilentRearmDecline(fan, remembered, "change_in_flight")
            return
        }
        if (!shouldRestoreRememberedManualFan(
                capability = fan,
                rememberedMode = remembered,
                lastAttemptedSessionKey = lastManualRearmSessionKey,
            )
        ) {
            logSilentRearmDecline(fan, remembered, "gate_declined")
            return
        }
        lastManualRearmSessionKey = tweakerFanSessionKey(fan)
        logFanAssessment("REARM_OBSERVED", rememberedManualObservation(fan.profileId))
    }

    /** Session identity this coordinator has already reported a suspicious re-arm decline for. */
    private var lastRearmDeclineLogSessionKey: String? = null

    /**
     * Say something ONLY when the fan should be ours and is not, and we still did nothing.
     *
     * **This is what the stripped probes were for, reduced to its useful core.** Those printed every
     * gate input on every projection; this prints once per session identity, and only in the shape
     * that actually went wrong: the player's remembered choice is MANUAL, the daemon attests the
     * hardware is NOT ours, and the re-arm declined anyway. In a healthy session it never fires --
     * a genuine Manual has `hardwareDutyOwned == true`, and an unattested daemon reports null.
     *
     * A silent decline is precisely what cost a whole diagnosis: the fan sat on the vendor curve for
     * two minutes across twelve watch ticks with nothing in logcat to say a decision had even been
     * reached. The absence needed its own signal,
     * not a louder version of the present one.
     */
    private fun logSilentRearmDecline(
        fan: TweakerManualFanCapability,
        rememberedMode: TweakerFanMode?,
        reason: String,
    ) {
        if (rememberedMode != TweakerFanMode.MANUAL) return
        if (fan.hardwareDutyOwned != false) return
        val sessionKey = tweakerFanSessionKey(fan)
        if (lastRearmDeclineLogSessionKey == sessionKey) return
        lastRearmDeclineLogSessionKey = sessionKey
        logFanAssessment(
            "REARM_DECLINED",
            "$reason · hardware is not ours and MANUAL is remembered · ${fan.profileId} · " +
                "latched=${lastManualRearmSessionKey == sessionKey}",
        )
    }

    /**
     * One greppable line whenever a fan change does not come back Accepted.
     *
     * **This gap cost a device report.** The daemon's decline path has logged since the ninth
     * blocker, so a refusal there is readable in logcat — but the FRONTEND's failure paths logged
     * nothing at all, and the client is where the last two of these actually happened. On
     * 2026-08-26 a player switched Auto→Manual mid-game, the daemon logged
     * `transition apply code=APPLIED applied=true liveFanMode=6`, and the row still showed
     * "unknown error". The only record of WHY was a short-lived toast during gameplay.
     * Both ends of this chain need a durable diagnostic.
     */
    private fun logFanAssessment(outcome: String, detail: String) {
        runCatching {
            android.util.Log.w(
                "MimirTweakerBridge",
                "fanchange outcome=$outcome detail=${detail.replace(Regex("\\s+"), " ").trim()}",
            )
        }
    }

    /** P8: [logFanAssessment]'s `Log.i` twin for an outcome that is not a decline or an uncertainty. */
    private fun logFanAssessmentInfo(outcome: String, detail: String) {
        runCatching {
            android.util.Log.i(
                "MimirTweakerBridge",
                "fanchange outcome=$outcome detail=${detail.replace(Regex("\\s+"), " ").trim()}",
            )
        }
    }

    /**
     * Bounded one-line detail for the temporary fan debug messages. Uppercased to match the rail's
     * voice and clipped so a long daemon string cannot push the row off a handheld screen.
     */
    private fun fanDebugDetail(detail: String): String = fanDebugDetailText(detail)

    private fun publishTweakerMessage(
        text: String,
        messageClass: MimirMessageClass,
        supportingText: String? = null,
    ) {
        val feedbackDetached = presentationTarget == AppBrightnessPresentationTarget.SECONDARY &&
            secondaryPresentationOwner == null
        if (!isOpen) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            return
        }
        // Keep the message in retained state for the next host, but surface it now if the secondary
        // Presentation was revoked while the process-scoped operation was still settling.
        if (feedbackDetached) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        tweakerMessageEventId += 1L
        tweakerMessage = MimirMessage(
            eventId = tweakerMessageEventId,
            text = text,
            messageClass = messageClass,
            supportingText = supportingText,
        )
    }

    private var performanceOverlayToast: Toast? = null

    internal fun stepPerformanceOverlay(direction: Int) {
        // The step that turned the overlay on may have launched Android's notification dialog. Until its answer returns this row takes
        // no further steps: a second D-pad press reached it underneath the dialog and moved Games only to Always (Nova, 10-01 09:31:24).
        if (MimirNotificationPermissionAsk.isAskPending(context, NotificationAskFeature.PERFORMANCE_OVERLAY)) {
            android.util.Log.i(NotificationAskLogTag, "performance overlay step ignored: permission ask pending")
            return
        }
        val current = PerformanceOverlaySettingsStore.visibility.value
        val currentMode = PerformanceOverlaySettingsStore.selectedMode.value
        val next = stepPerformanceOverlayControl(current, currentMode, if (direction == 0) 1 else direction)
        if (next.visibility == current && next.mode == currentMode) return
        if (next.visibility.enabled) PerformanceOverlaySettingsStore.setMode(context, next.mode)
        val result = setPerformanceOverlayVisibility(context, next.visibility, tweakerUsageAccessState)
        // One toast, the latest. Toasts queue, so two quick steps showed the first for its full two seconds and the
        // second only after it, stale by then (Nova 10-01 21:49:57 and 21:49:59.99, two steps 0.34 s apart).
        performanceOverlayToast?.cancel()
        performanceOverlayToast = Toast.makeText(context, result.message, Toast.LENGTH_SHORT).also { it.show() }
        when (result) {
            PerformanceOverlayChangeResult.NEEDS_USAGE_ACCESS -> openTweakerUsageAccess()
            PerformanceOverlayChangeResult.NEEDS_DRAW_ACCESS -> openDrawOverlayAccess()
            else -> Unit
        }
    }

    internal fun openTweakerUsageAccess() {
        when (tweakerUsageAccessState) {
            TweakerUsageAccessState.SET_UP -> {
                if (requestTweakerUsageAccess(context)) {
                    leaveForExternalActivity()
                } else {
                    Toast.makeText(context, "TWEAKER USAGE ACCESS UNAVAILABLE", Toast.LENGTH_SHORT).show()
                }
            }
            TweakerUsageAccessState.READY ->
                Toast.makeText(context, "TWEAKER USAGE ACCESS READY", Toast.LENGTH_SHORT).show()
            TweakerUsageAccessState.INSTALL_TWEAKER ->
                Toast.makeText(context, "INSTALL MIMIR TWEAKER FIRST", Toast.LENGTH_SHORT).show()
            TweakerUsageAccessState.UNAVAILABLE ->
                Toast.makeText(context, "TWEAKER USAGE ACCESS UNAVAILABLE", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDrawOverlayAccess() {
        val exact = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = listOf(exact, fallback).firstOrNull {
            it.resolveActivity(context.packageManager) != null
        }
        if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
            Toast.makeText(context, "DRAW OVER OTHER APPS UNAVAILABLE", Toast.LENGTH_SHORT).show()
        } else {
            leaveForExternalActivity()
        }
    }

    internal fun setFraction(target: BrightnessTarget, fraction: Float) {
        controller?.let { activeController ->
            val activeState = state ?: activeController.state
            if (activeState.requiresPrivilegedUtility(target)) {
                enqueuePrivilegedFraction(activeController, target, fraction)
                return
            }
            reportBrightnessOutcome(activeController.setFraction(target, fraction))
        }
    }

    private fun step(target: BrightnessTarget, direction: Int) {
        val activeController = controller ?: return
        val activeState = state ?: activeController.state
        if (!activeState.requiresPrivilegedUtility(target)) {
            reportBrightnessOutcome(activeController.step(target, direction))
            return
        }
        enqueuePrivilegedStep(activeController, target, direction)
    }

    /**
     * Release UX/UI gate, stale-render/silence-on-failure finding (charter §3.4, 2026-08-12): every
     * caller of this coordinator's write paths used to keep only [BrightnessUpdateOutcome.state] and
     * discard the outcome variant, so a declined write ([BrightnessUpdateOutcome.WriteFailed] /
     * [BrightnessUpdateOutcome.Unsupported]) was structurally indistinguishable from a successful one —
     * the control simply stopped responding with no explanation, the charter's own opening example
     * ("A refusal was computed and thrown away — no message") on a slider instead of the Play button.
     * [BrightnessUpdateOutcome.WriteAccessRequired] is deliberately excluded here: PRIMARY's access-denied
     * case already has a standing, visible explanation (`shouldOfferMainWriteAccess`'s message row plus
     * the Allow button), and the right treatment for the other targets is an unresolved design decision, not
     * assumed here.
     */
    private fun reportBrightnessOutcome(outcome: BrightnessUpdateOutcome) {
        state = outcome.state
        brightnessWriteDeclineMessage(outcome)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    internal fun requestWriteAccess() {
        openWriteAccess()
    }

    internal fun setThemeAwareJoystickLight(enabled: Boolean) {
        val activeController = controller ?: return
        val generation = ++themeGeneration
        themeJob?.cancel()
        themeJob = scope.launch {
            try {
                val updated = privilegedOperationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        activeController.setThemeAwareJoystickLight(enabled).state
                    }
                }
                if (themeGeneration == generation) state = updated
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (themeGeneration == generation) state = activeController.state
            } finally {
                if (themeGeneration == generation) themeJob = null
            }
        }
    }

    /**
     * The Utility route into the Tweaker.
     *
     * Every way in — this row, the physical M button, and a macro bound to TWEAKER — resolves through
     * [tweakerRouteDecision], so none of them can diverge. A paired Tweaker opens directly; an absent
     * Tweaker remains unavailable.
     */
    internal fun openTweakerSurface() {
        val activeController = controller ?: return
        when (
            tweakerRouteDecision(
                tweakerPaired = tweakerRoutePaired(tweakerRow.availability),
            )
        ) {
            // The Tweaker surface opens where the row that asked for it is showing. Defaulting the
            // target here re-homed an open Bottom (SECONDARY) Utility onto the PRIMARY host, which on
            // the Thor with a game on Main has no visible host at all: the Bottom panel vanished and
            // the coordinator sat "open" on a display nobody was looking at until the game ended
            // (measured 2026-09-11).
            TweakerRouteDecision.OPEN -> open(
                UtilitySurface.TWEAKER,
                target = tweakerSurfaceOpenTarget(isOpen, presentationTarget),
            )
            TweakerRouteDecision.UNAVAILABLE ->
                Toast.makeText(context, "TWEAKER UNAVAILABLE ON THIS DEVICE", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAllFilesAccess() {
        allFilesAccessGranted = runCatching { PermissionHelper(context).hasFullStorageAccess() }
            .getOrDefault(false)
    }

    /**
     * The Utility "Files" row's activation -- the only door to [FileBrowserMode.MANAGE].
     *
     * **Deliberately does NOT close this panel on the OPEN branch**, unlike every other route out of
     * Utility here ([openWriteAccess], [openTweakerUsageAccess], [openDrawOverlayAccess] all go through
     * [leaveForExternalActivity]). Those leave for another Activity; this one opens an overlay inside
     * the same app, and `resolveMimirOverlayStack` already ranks `FOLDER_BROWSER` above `BRIGHTNESS`
     * (`top = entries.firstOrNull()`), so publishing the controller is enough: the browser takes input
     * and render, this panel's host leaves composition with its refresh loops, and when the player backs
     * out of the browser `BRIGHTNESS` is top again and Utility is simply there. Closing here would
     * instead drop them onto Home, which is not where they were.
     */
    internal fun openFilesManager() {
        // RC-06: the dock reaches this with Utility closed and never composed, so the mirror may be as old as the last
        // Utility that ran. Decide on the ledger, not on what a past panel saw.
        refreshGameRunning()
        when (filesRouteDecision(hostsInAppSurfaces, allFilesAccessGranted, gameRunning = gameRunning)) {
            FilesRouteDecision.OPEN -> openFileBrowser()
            FilesRouteDecision.REQUEST_ALL_FILES_ACCESS -> requestAllFilesAccess()
            // R2: the row and the dock's seat are withdrawn while a game runs (the row's one answer, above); a press that arrives
            // anyway refuses here, quietly. Never the toast below: its copy is the native overlay's, and inside MainActivity it is false.
            FilesRouteDecision.GAME_RUNNING ->
                android.util.Log.i("MimirUtility", "files route refused: a game is running (R2)")
            // Unreachable through the rail -- the row is withheld from this host entirely
            // (`filesRouteAvailable`). Refusing here as well is what makes that a single auditable
            // decision rather than a render-time `if` that a future caller could route around.
            FilesRouteDecision.UNAVAILABLE ->
                Toast.makeText(context, "OPEN MIMIR TO BROWSE FILES", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * §6.2 : Move/Copy get a voice, on extract's own model --
     * `onExtracted`'s Toast a few lines below in [openFileBrowser]. ONE template for both verbs, so a
     * change of wording is one edit rather than a hunt through two near-identical string literals.
     *
     * A Move rescans the folders touched, so the message need not ask for a manual library rescan.
     * See [kickTransferRescan], which is what makes that true.
     */
    private fun transferAnnouncement(verb: String, label: String, destination: String): String =
        "$verb $label to ${File(destination).name}"

    /** Paths whose roots are owed a rescan, accumulated across a burst of transfers. */
    private val pendingTransferRescanPaths = mutableSetOf<String>()
    private var transferRescanJob: Job? = null

    /**
     * Rescan just the folders touched, debounced.
     *
     * Without this, a Move is worse than invisible. A `GameEntity`'s primary key embeds its path, so a
     * moved ROM's old row survives until the root it LEFT is rescanned -- and `launchLocatorFrom` reads
     * `managedPath` straight off that row with no filesystem check, so `LaunchReadiness.MissingFile`
     * never fires. Pressing Play on the stale tile sends the intent, the emulator shows the error, and
     * Mimir still records `intent_sent` as a successful play: `playCount` goes up and the dead tile
     * climbs "recently played". That is why [sources] is rescanned and not just [destination] -- the
     * destination gains a tile, but only the ORIGIN prunes the ghost.
     *
     * Debounced rather than fired per-transfer because ROM moves can arrive in bursts, and `rescan` is
     * `serializedScan` -- twenty presses would otherwise queue twenty scans that each wait for the last.
     * Accumulate, coalesce, resolve the roots once. A window cancelled while still WAITING keeps its
     * paths, so the next transfer re-arms with them rather than dropping them.
     *
     * The scan itself is [NonCancellable], and that is load-bearing rather than defensive. `rescan` is
     * suspend and takes real time on a real library, so without it this sequence loses a batch outright:
     * move A, the window elapses, the job takes A's paths and CLEARS them, scanning begins -- then move
     * B arrives, cancels the job at its next suspension point, and A's origin is never rescanned and
     * never re-queued. That is the ghost tile above, resurrected by exactly the burst pattern the
     * debounce exists to serve. A started scan now finishes; a new transfer arms a fresh window.
     *
     * Taking the batch and clearing it is one atomic step because this scope is Main-dispatched and
     * neither call suspends between them -- so a transfer landing mid-flight cannot have its paths
     * swallowed by the clear. [FileBrowserStore]'s own scope is `Dispatchers.Main` too (verified, not
     * assumed), which is what puts the sink on this thread in the first place.
     *
     * Scope is the app-core composition's ([rememberAppBrightnessCoordinator]), NOT the Utility
     * overlay's, so closing Utility right after a move does not cancel the pending scan. Process death
     * inside the window still loses it; the manual rescan in Settings → Library remains the floor.
     *
     * **Move only.** This refresh requirement covers moving a ROM into its platform folder. Copy and
     * Extract share the mechanism but not the failure mode -- neither removes a file, so neither can
     * strand a tile pointing at a path that is gone; they merely leave a new file unindexed. Widening
     * this to them is a separate design decision.
     */
    private fun kickTransferRescan(sources: List<String>, destination: String) {
        pendingTransferRescanPaths += sources
        pendingTransferRescanPaths += destination
        transferRescanJob?.cancel()
        transferRescanJob = scope.launch {
            delay(TransferRescanDebounceMs)
            withContext(NonCancellable) {
                val batch = pendingTransferRescanPaths.toSet()
                pendingTransferRescanPaths.clear()
                val library = LibraryInjection.repository(context)
                // Registered roots come in two shapes -- SAF tree URIs and plain paths -- and the file
                // manager only ever speaks the second. LibraryRootMatcher is what bridges them; a naive
                // prefix test here would match nothing and this whole function would be a silent no-op.
                val roots = library.observeSources().first().map { it.rootUri }
                batch.flatMap { LibraryRootMatcher.rootsContaining(it, roots) }
                    .distinct()
                    .forEach { library.rescan(it) }
            }
        }
    }

    private fun openFileBrowser() {
        FileBrowserStore.open(
            context = context,
            authority = authority,
            // The whole safety property of policy §3c. PICK is the add-games picker and can never
            // delete; MANAGE is the file manager proper. Reached only from here, which is why Utility
            // had to exist before anything destructive could be built behind it.
            mode = FileBrowserMode.MANAGE,
            // Genuinely unreachable in MANAGE -- FileBrowserController only offers the commit affordance
            // when `mode == PICK` (FileBrowserController.kt:384), which is that mode's own design intent
            // rather than something suppressed here. Named, not silently defaulted, so the next reader
            // does not go looking for the Utility surface's missing "use this folder" handler.
            onChoose = {},
            // Extract and Verify ARE live in MANAGE, so they get the same honest-minimum feedback the
            // add-games call site already gives them (MimirSettingsCoordinator.launchSourcePicker). A
            // silent extraction is the exact defect those Toasts were written for; inheriting the
            // silent defaults here would have reintroduced it on the surface that uses them most.
            onExtracted = { _, destination, count ->
                val itemWord = if (count == 1) "item" else "items"
                Toast.makeText(
                    context,
                    "Extracted $count $itemWord to ${File(destination).name}",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onExtractFailed = { archivePath, _ ->
                Toast.makeText(
                    context,
                    "Couldn't extract ${File(archivePath).name}",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            // §6.2: Move/Copy are the affordance's whole point and, until this slice, produced no
            // acknowledgement at all -- a completed Copy was indistinguishable from a press that did
            // nothing . Same honest-minimum posture as
            // onExtracted above, MANAGE-only because transfer() cannot fire either sink outside it
            // (`operations ?: return`).
            onMoved = { label, sources, destination, _ ->
                Toast.makeText(context, transferAnnouncement("Moved", label, destination), Toast.LENGTH_SHORT)
                    .show()
                // The half that makes the affordance finish its job -- see [kickTransferRescan] for why
                // the origin matters more than the destination here.
                kickTransferRescan(sources, destination)
            },
            // Rename . A new name is a new identity (title ids are filename-derived), so the
            // library is told to look again at the folder the file sits in, exactly as a move tells it.
            onRenamed = { oldPath, newPath ->
                Toast.makeText(
                    context,
                    "Renamed ${File(oldPath).name} to ${File(newPath).name}",
                    Toast.LENGTH_SHORT,
                ).show()
                kickTransferRescan(listOf(oldPath), File(newPath).parent ?: newPath)
            },
            // No rescan on Copy: the policy specifies the Move case, and Copy's failure mode is a new file the
            // library has not indexed yet, never a tile pointing at a file that is gone. Left as a
            // separate ruling rather than inferred from the neighbouring one.
            onCopied = { label, _, destination, _ ->
                Toast.makeText(context, transferAnnouncement("Copied", label, destination), Toast.LENGTH_SHORT)
                    .show()
            },
            // Delete feedback, 2026-08-14: the Toast names the RECOVERY
            // ROUTE, not just the act. Undo lives on the queue screen, so the message
            // says where to find it. MANAGE-only, because PICK cannot delete at all.
            //
            // No navigation, deliberately: the policy specifies the CLOSE press should route to the queue, and that
            // is the moment that can lose files. This moment only needs a voice.
            onDeleted = { label, count ->
                val itemWord = if (count == 1) "item" else "items"
                Toast.makeText(
                    context,
                    if (count == 1) "Deleted $label — Undo in QUEUE" else "Deleted $count $itemWord — Undo in QUEUE",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onChecksum = { _, digest ->
                Toast.makeText(context, "SHA-1 ${digest.take(12)}…", Toast.LENGTH_LONG).show()
            },
            onChecksumFailed = { path, _ ->
                Toast.makeText(context, "Couldn't read ${File(path).name}", Toast.LENGTH_SHORT).show()
            },
            classifyPlatform = FileBrowserPlatformClassifier.forBrowser,
        )
    }

    /**
     * Raise Android's "All files access" screen. Per-app first, the global list as a fallback --
     * mirroring `MimirOnboardingCoordinator`'s `requestFilesAccess`, because the per-app screen is not
     * present on every OEM build and a `startActivity` for a missing one throws rather than no-ops.
     *
     * Never self-granted: [refreshOnResume] re-reads the real permission when the player returns.
     *
     * **FLAG_ACTIVITY_NEW_TASK is load-bearing, and was missing at first.** `PermissionHelper` is
     * vendored from FileExplorer, where it is launched from an Activity, so its intent carries no
     * flags; [context] here is whatever built the coordinator, and `PrimaryMButtonPanelHost` builds one
     * with the Application context. Starting an Activity from a non-Activity context without this flag
     * throws, both fallbacks would then fail, and the press would degrade to the "unavailable" Toast on
     * a device where the screen exists. Every other settings hop reached from this class already adds
     * it -- [openDrawOverlayAccess], and `MimirBrightnessWriteAccess.intent` (AndroidBrightnessStore.kt:28).
     * Added at the call site rather than inside the vendored helper, whose other callers may legitimately
     * want Activity task semantics. Caught by `UtilityFilesRouteTest`, not by review.
     */
    private fun requestAllFilesAccess() {
        val perApp = PermissionHelper(context).getManageStorageIntent()
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(perApp) }
            .recoverCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            .isSuccess
        if (opened) {
            leaveForExternalActivity()
        } else {
            Toast.makeText(context, "FILE ACCESS UNAVAILABLE ON THIS DEVICE", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun requestPrivilegedAccess() {
        val activeController = controller ?: return
        val posture = (state ?: activeController.state).capabilities.privilegedUtilityPosture
        val expectedStatus = posture.requestedAccessStatus ?: posture.status
        val dismissAfterRequest = false
        launchPrivilegedOperation(
            scope = scope,
            mutex = privilegedOperationMutex,
            request = {
                val started = activeController.requestPrivilegedAccess(expectedStatus)
                PrivilegedUtilityAccessResult(activeController.state, started)
            },
            onComplete = { result ->
                state = result.state
                if (dismissAfterRequest && result.started) leaveForExternalActivity()
                val updatedPosture = result.state.capabilities.privilegedUtilityPosture
                val feedback = reducePrivilegedUtilityAccessFeedback(
                    expectedStatus = expectedStatus,
                    started = result.started,
                    statusAfter = updatedPosture.requestedAccessStatus ?: updatedPosture.status,
                )
                Toast.makeText(context, feedback.message, Toast.LENGTH_SHORT).show()
            },
        )
    }

    /**
     * Settings' Android-app-switching row uses the same certified broker as the M-button Tabs command.
     * The result is surfaced so a touch activation can never fail silently.
     */
    internal fun requestAndroidAppSwitchingAccess(
        expectedStatus: PrivilegedUtilityStatus,
        onComplete: () -> Unit = {},
    ) {
        launchPrivilegedOperation(
            scope = scope,
            mutex = privilegedOperationMutex,
            request = {
                requestAndroidAppSwitchingSetup(
                    CompanionProcessRuntime.services(context).privilegedUtility,
                    expectedStatus = expectedStatus,
                )
            },
            onComplete = { result ->
                Toast.makeText(context, result.feedback.message, Toast.LENGTH_SHORT).show()
                onComplete()
            },
        )
    }

    internal suspend fun refreshPrivilegedPosture(): PrivilegedUtilityStatus? {
        val activeController = controller ?: return null
        if (refreshJob?.isActive == true || readyHandshakeJob?.isActive == true) {
            return state?.capabilities?.privilegedUtilityPosture?.status
        }
        return try {
            val updated = privilegedOperationMutex.withLock {
                withContext(Dispatchers.IO) {
                    val previousStatus = activeController.state.capabilities
                        .privilegedUtilityPosture.status
                    var refreshed = activeController.refreshPrivilegedLiveness()
                    if (
                        previousStatus != PrivilegedUtilityStatus.READY &&
                        refreshed.capabilities.privilegedUtilityPosture.status ==
                        PrivilegedUtilityStatus.READY &&
                        (refreshed.themeAwareJoystickLightRequested ||
                            refreshed.themeAwareJoystickLightRestorePending)
                    ) {
                        refreshed = activeController.reconcileThemeAwareJoystickLight().state
                    }
                    // A crash journal retries against the bridge's live posture even if the
                    // rendered controller state is still carrying its prior failure verdict.
                    activeController.reconcileIdleJoystickLightAfterReady()
                }
            }
            state = updated
            updated.capabilities.privilegedUtilityPosture.status
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            state = activeController.state
            state?.capabilities?.privilegedUtilityPosture?.status
        }
    }

    private fun enqueuePrivilegedFraction(
        activeController: BrightnessController,
        target: BrightnessTarget,
        fraction: Float,
    ) {
        val bounded = BrightnessMath.quantize(target, fraction)
        pendingPrivilegedFractions[target] = bounded
        val generation = (privilegedFractionGenerations[target] ?: 0L) + 1L
        privilegedFractionGenerations[target] = generation
        privilegedFractionJobs.remove(target)?.cancel()
        privilegedFractionJobs[target] = scope.launch {
            try {
                val outcome = privilegedOperationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        activeController.setFraction(target, bounded)
                    }
                }
                if (privilegedFractionGenerations[target] == generation) reportBrightnessOutcome(outcome)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (privilegedFractionGenerations[target] == generation) {
                    state = activeController.state
                }
            } finally {
                if (privilegedFractionGenerations[target] == generation) {
                    pendingPrivilegedFractions.remove(target)
                    privilegedFractionJobs.remove(target)
                }
            }
        }
    }

    private fun enqueuePrivilegedStep(
        activeController: BrightnessController,
        target: BrightnessTarget,
        direction: Int,
    ) {
        val boundedDirection = direction.coerceIn(-1, 1)
        if (boundedDirection == 0) return
        val runs = privilegedStepRuns.getOrPut(target) { ArrayDeque() }
        val tail = runs.lastOrNull()
        if (tail?.direction == boundedDirection) {
            tail.count += 1
        } else {
            runs.addLast(PrivilegedStepRun(boundedDirection, count = 1))
        }
        if (privilegedStepJobs[target]?.isActive == true) return
        privilegedStepJobs[target] = scope.launch {
            try {
                while (true) {
                    val run = privilegedStepRuns[target]?.removeFirstOrNull() ?: break
                    val outcome = privilegedOperationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val first = activeController.step(target, run.direction)
                            if (first !is BrightnessUpdateOutcome.Applied || run.count == 1) {
                                first
                            } else {
                                val firstFraction = first.state.fractionFor(target)
                                    ?: return@withContext first
                                val desired = coalescedPrivilegedStepFraction(
                                    target = target,
                                    firstAppliedFraction = firstFraction,
                                    direction = run.direction,
                                    remainingSteps = run.count - 1,
                                )
                                activeController.setFraction(target, desired)
                            }
                        }
                    }
                    reportBrightnessOutcome(outcome)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                state = activeController.state
            } finally {
                privilegedStepRuns.remove(target)
                privilegedStepJobs.remove(target)
            }
        }
    }

    private fun refreshPrivilegedState(
        activeController: BrightnessController,
        alwaysReconcileTheme: Boolean,
        applyWindowFallbacks: Boolean,
    ) {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val updated = privilegedOperationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        var refreshed = activeController.refresh()
                        val needsThemeReconcile = alwaysReconcileTheme ||
                            refreshed.themeAwareJoystickLightRestorePending ||
                            (refreshed.themeAwareJoystickLightRequested &&
                                !refreshed.themeAwareJoystickLight)
                        if (
                            needsThemeReconcile &&
                            refreshed.capabilities.privilegedUtilityPosture.status ==
                            PrivilegedUtilityStatus.READY
                        ) {
                            refreshed = activeController.reconcileThemeAwareJoystickLight().state
                        }
                        // Resume is also the durable crash-journal recovery lane. This is harmless
                        // without a journal and retries below while vendor access is still being established.
                        refreshed = activeController.reconcileIdleJoystickLightAfterReady()
                        refreshed
                    }
                }
                if (refreshGeneration == generation) {
                    state = updated
                    if (applyWindowFallbacks) activeController.applyWindowFallbacks()
                    if (
                        shouldScheduleUtilityRecoveryHandshake(
                            utilityOpen = isOpen,
                            status = updated.capabilities.privilegedUtilityPosture.status,
                        )
                    ) {
                        scheduleReadyHandshake(activeController)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (refreshGeneration == generation) state = activeController.state
            } finally {
                if (refreshGeneration == generation) refreshJob = null
            }
        }
    }

    /** Bounded resume handshake: UserService binding is async, so one immediate journal restore is not enough. */
    private fun scheduleReadyHandshake(activeController: BrightnessController) {
        if (readyHandshakeJob?.isActive == true) return
        val generation = ++readyHandshakeGeneration
        readyHandshakeJob = scope.launch {
            try {
                repeat(20) {
                    delay(250)
                    if (isOpen) return@launch
                    val updated = privilegedOperationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            activeController.refreshPrivilegedLiveness()
                            activeController.reconcileIdleJoystickLightAfterReady()
                        }
                    }
                    state = updated
                    if (updated.capabilities.privilegedUtilityPosture.status !=
                        PrivilegedUtilityStatus.BINDING
                    ) {
                        return@launch
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                state = activeController.state
            } finally {
                if (readyHandshakeGeneration == generation) readyHandshakeJob = null
            }
        }
    }

    private fun cancelForegroundRefreshes() {
        refreshGeneration++
        refreshJob?.cancel()
        refreshJob = null
        tweakerRefreshGeneration++
        tweakerRefreshJob?.cancel()
        tweakerRefreshJob = null
        cancelReadyHandshake()
    }

    private fun cancelReadyHandshake() {
        readyHandshakeGeneration++
        cancelUtilityRecoveryJob(readyHandshakeJob)
        readyHandshakeJob = null
    }

    private fun openWriteAccess() {
        val opened = runCatching {
            context.startActivity(MimirBrightnessWriteAccess.intent(context))
        }.isSuccess
        if (opened) {
            leaveForExternalActivity()
        } else {
            Toast.makeText(
                context,
                "System-change access is unavailable on this device",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun leaveForExternalActivity() {
        beforeExternalActivity()
        close()
    }
}

/** The visible Utility snapshot owns one press even if an async refresh settles concurrently. */
internal fun renderedTweakerStepOutcome(
    row: TweakerUtilityRowState,
    direction: Int,
): TweakerUtilityStepOutcome = row.stepOutcome(direction)

/**
 * Diagnostic identity of a selection outcome, including the daemon's rejection code. The on-screen
 * copy deliberately collapses most rejection codes into one capped line ("PROFILE CHANGE
 * REJECTED"), which is right for the player and useless for a bug report. Never shown to the player.
 */
internal fun TweakerSystemProfileSelectionResult.diagnosticLabel(): String = when (this) {
    is TweakerSystemProfileSelectionResult.Active -> "Active(${profileId})"
    is TweakerSystemProfileSelectionResult.Stock -> "Stock"
    TweakerSystemProfileSelectionResult.Busy -> "Busy"
    TweakerSystemProfileSelectionResult.Unavailable -> "Unavailable"
    TweakerSystemProfileSelectionResult.UpgradeRequired -> "UpgradeRequired"
    is TweakerSystemProfileSelectionResult.Cancelled -> "Cancelled"
    is TweakerSystemProfileSelectionResult.Rejected -> "Rejected(code=$code)"
    is TweakerSystemProfileSelectionResult.Uncertain -> "Uncertain"
}

internal fun TweakerSystemProfileSelectionResult.systemProfileToastCaps(): String = when (this) {
        is TweakerSystemProfileSelectionResult.Active ->
            "${tweakerProfileDisplayCaps(profileId)} · ACTIVE"
        is TweakerSystemProfileSelectionResult.Stock ->
            "STOCK · TUNING OFF"
        TweakerSystemProfileSelectionResult.Busy -> "TWEAKER IS BUSY · TRY AGAIN"
        TweakerSystemProfileSelectionResult.Unavailable -> "TWEAKER UNAVAILABLE"
        TweakerSystemProfileSelectionResult.UpgradeRequired -> "UPDATE MIMIR TWEAKER"
        is TweakerSystemProfileSelectionResult.Cancelled -> "PROFILE CHANGE CANCELLED"
        is TweakerSystemProfileSelectionResult.Rejected -> when (code) {
            // retired the words "certified"/"qualified" from player copy, but
            // these two codes must not fall through to the generic line either -- that is the exact
            // collapse the comment below records as having cost a full device investigation. They mean
            // the daemon could neither match a signed plan nor derive a self-validating one for this
            // chassis, so the profile is genuinely not runnable here. Say that, in the player's words.
            // OWED: wording remains pending visual review on device.
            // PLAN_NOT_ADMITTED is the current code for the second reason; CALIBRATION_REQUIRED is
            // what an older daemon still sends.
            "PROFILE_NOT_CERTIFIED", "PLAN_NOT_ADMITTED", "CALIBRATION_REQUIRED" ->
                "PROFILE NOT AVAILABLE ON THIS DEVICE"
            // The governor's scaling_min_freq is a QoS floor that tracks power state -- on the Odin 3,
            // 1,996,800 kHz on AC against 1,785,600 on battery (TuningPlanner.kt:266). A profile whose
            // CPU target sits on a battery-only rung is refused while charging, and the daemon is right
            // to refuse it. Collapsing that into "PROFILE CHANGE REJECTED" cost a full device
            // investigation to recover a fact the app already had in hand.
            "CPU_TARGET_BELOW_LIVE_FLOOR" -> "CHARGING RAISES THE CPU FLOOR · TRY ON BATTERY"
            // The daemon's own "I did not answer in time" (its transaction worker was still busy). Unlike every other
            // refusal here it is NOT a verdict: the change it was asked for may still land, so "REJECTED" would be a
            // claim the daemon never made. It reads as the same Busy the bridge itself answers when it holds the
            // session. CPU_TRANSPORT_UNCONFIRMED stays generic on purpose: that one is a refusal, sent before any write.
            "SERVICE_BUSY" -> "TWEAKER IS BUSY · TRY AGAIN"
            // `CPU_TARGET_ABOVE_LIVE_CEILING` had a line here for two days and now has none, because
            // the daemon can no longer emit it. Targets use the operating-point tables without reading
            // the live ceiling: a target off the operating-point table is snapped
            // down onto it, and a ceiling Mimir wrote can no longer be the evidence forbidding its own
            // removal. Copy for a code that cannot arrive would be a promise about a state the product
            // no longer has.
            else -> "PROFILE CHANGE REJECTED"
        }
        is TweakerSystemProfileSelectionResult.Uncertain -> "PROFILE STATUS UNCONFIRMED"
}

internal fun TweakerSystemProfileSelectionResult.systemProfileMessageClass(): MimirMessageClass = when (this) {
    is TweakerSystemProfileSelectionResult.Active,
    is TweakerSystemProfileSelectionResult.Stock,
    -> MimirMessageClass.Confirmation
    is TweakerSystemProfileSelectionResult.Cancelled -> MimirMessageClass.Guidance
    TweakerSystemProfileSelectionResult.Busy,
    TweakerSystemProfileSelectionResult.Unavailable,
    TweakerSystemProfileSelectionResult.UpgradeRequired,
    is TweakerSystemProfileSelectionResult.Rejected,
    is TweakerSystemProfileSelectionResult.Uncertain,
    -> MimirMessageClass.Warning
}

@Composable
internal fun rememberAppBrightnessCoordinator(
    context: Context,
    controller: BrightnessController?,
    authority: FocusAuthority,
): AppBrightnessCoordinator {
    val appContext = context.applicationContext
    val coordinator = remember(controller, authority) {
        CompanionProcessRuntime.brightnessCoordinator(
            context = appContext,
            controller = controller,
            authority = authority,
        )
    }
    // The process-retained model survives Activity recreation, while this exact controller/authority
    // pair owns only this composition's Activity registration. A stale disposal cannot unregister a
    // successor that has already rebound the shared coordinator.
    DisposableEffect(coordinator, controller, authority) {
        onDispose {
            CompanionProcessRuntime.unregisterActivityBrightnessHost(controller, authority)
        }
    }
    return coordinator
}

/** Only these rows cross the privileged vendor boundary; app-local/system rows stay immediate. */
internal fun BrightnessState.requiresPrivilegedUtility(target: BrightnessTarget): Boolean =
    requiresPrivilegedUtilityTarget(target, capabilities.secondarySupport)

/**
 * The player-facing line for a declined brightness/haptics/sound/volume write, or null when nothing needs
 * saying (a successful [BrightnessUpdateOutcome.Applied], or [BrightnessUpdateOutcome.WriteAccessRequired] --
 * PRIMARY's access-denied case already has a standing explanation via `shouldOfferMainWriteAccess`; the
 * right feedback for the other targets is left to the player, not guessed here). Deliberately generic rather
 * than per-target, so it cannot mis-describe a control by mismatching a label defined in a different file.
 */
internal fun brightnessWriteDeclineMessage(outcome: BrightnessUpdateOutcome): String? = when (outcome) {
    is BrightnessUpdateOutcome.WriteFailed -> "CHANGE DID NOT APPLY"
    is BrightnessUpdateOutcome.Unsupported -> "NOT SUPPORTED ON THIS DEVICE"
    is BrightnessUpdateOutcome.Applied, is BrightnessUpdateOutcome.WriteAccessRequired -> null
}

internal fun requiresPrivilegedUtilityTarget(
    target: BrightnessTarget,
    secondarySupport: SecondaryBrightnessSupport,
): Boolean =
    when (target) {
        BrightnessTarget.SECONDARY ->
            secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM
        BrightnessTarget.JOYSTICK_LIGHT,
        BrightnessTarget.JOYSTICK_COLOR,
        -> true
        BrightnessTarget.PRIMARY,
        BrightnessTarget.MIMIR_HAPTIC,
        BrightnessTarget.NAV_SOUND,
        BrightnessTarget.AMBIENT_SOUND,
        BrightnessTarget.MEDIA_VOLUME,
        BrightnessTarget.SCREEN_TIMEOUT,
        BrightnessTarget.EQ_GAIN,
        -> false
    }

internal fun coalescedPrivilegedStepFraction(
    target: BrightnessTarget,
    firstAppliedFraction: Float,
    direction: Int,
    remainingSteps: Int,
): Float {
    var fraction = firstAppliedFraction
    repeat(remainingSteps.coerceAtLeast(0)) {
        fraction = BrightnessMath.step(target, fraction, direction)
    }
    return fraction
}

@Composable
internal fun AppBrightnessCoordinatorHost(
    coordinator: AppBrightnessCoordinator,
    target: AppBrightnessPresentationTarget = AppBrightnessPresentationTarget.PRIMARY,
    secondaryPresentationToken: UUID? = null,
    hostKind: CompanionProcessRuntime.BrightnessHostKind =
        CompanionProcessRuntime.BrightnessHostKind.ACTIVITY,
    retainedForExit: Boolean = false,
    /** Utility is the page returned to (back from the file browser): at rest from its first frame. */
    settledOnMount: Boolean = false,
) {
    if ((!coordinator.isOpen || coordinator.presentationTarget != target) && !retainedForExit) return
    if (target == AppBrightnessPresentationTarget.PRIMARY &&
        !coordinator.isPrimaryHostActive(hostKind) &&
        !retainedForExit
    ) return
    val state = coordinator.state ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    // R2: the Files row follows whether a game is running, read from the session authority and written to the model the row reads.
    // The ledger is READ here, in composition, so a game that starts or ends while Utility is open recomposes this host (a read made
    // inside the effect below is not observed); the effect writes the derived value.
    val sessionSnapshot by MimirSessionAuthority.state.collectAsState()
    val gameRunningNow = filesRouteGameRunning(sessionSnapshot)
    SideEffect { coordinator.gameRunning = gameRunningNow }
    val primaryMButtonControlsAvailable by CompanionProcessRuntime.services(coordinator.context)
        .mButtonTopology.mButtonVisibilityControlsAvailable.collectAsState()
    val mButtonVisibilityPolicy by MButtonSettingsStore.visibilityPolicy.collectAsState()
    val navigationHintVisibility by NavigationHintSettingsStore.visibility.collectAsState()
    val performanceOverlayVisibility by PerformanceOverlaySettingsStore.visibility.collectAsState()
    val performanceOverlayMode by PerformanceOverlaySettingsStore.selectedMode.collectAsState()
    val dismiss: () -> Unit = if (target == AppBrightnessPresentationTarget.SECONDARY) {
        { coordinator.dismissFromSecondaryPresentation() }
    } else {
        coordinator::close
    }
    if (target == AppBrightnessPresentationTarget.SECONDARY && secondaryPresentationToken != null) {
        DisposableEffect(coordinator, secondaryPresentationToken) {
            coordinator.attachSecondaryPresentation(secondaryPresentationToken)
            onDispose { coordinator.detachSecondaryPresentation(secondaryPresentationToken) }
        }
    }
    val cancelMutation: () -> Unit = if (target == AppBrightnessPresentationTarget.SECONDARY) {
        { dismiss() }
    } else {
        { coordinator.requestTweakerProfileCancellation(); Unit }
    }
    AppBrightnessHost(
        state = state,
        authority = coordinator.authority,
        onSetFraction = coordinator::setFraction,
        onSetThemeAwareJoystickLight = coordinator::setThemeAwareJoystickLight,
        onRequestWriteAccess = coordinator::requestWriteAccess,
        onRequestPrivilegedAccess = coordinator::requestPrivilegedAccess,
        onOpenTweaker = coordinator::openTweakerSurface,
        onOpenFiles = coordinator::openFilesManager,
        filesRouteAvailable = coordinator.filesRouteOffered,
        allFilesAccessGranted = coordinator.allFilesAccessGranted,
        tweakerFpsCapRow = coordinator.tweakerFpsCapRow,
        onStepTweakerFpsCap = coordinator::stepTweakerFpsCap,
        primaryMButtonControlsAvailable = primaryMButtonControlsAvailable,
        mButtonVisibilityPolicy = mButtonVisibilityPolicy,
        onStepMButtonVisibilityPolicy = coordinator::stepMButtonVisibilityPolicy,
        navigationHintVisibility = navigationHintVisibility,
        onStepNavigationHintVisibility = coordinator::stepNavigationHintVisibility,
        calmShine = MimirCalmShineSettings.enabled,
        onSetCalmShine = coordinator::setCalmShine,
        tweakerRow = coordinator.tweakerRow,
        tweakerFanGpuTempC = coordinator.tweakerFanGpuTempC,
        tweakerFanCpuTempC = coordinator.tweakerFanCpuTempC,
        tweakerProfileChange = coordinator.tweakerProfileChange,
        tweakerFanControlChange = coordinator.tweakerFanControlChange,
        onStepTweakerProfile = coordinator::stepTweakerProfile,
        tweakerProfilePreviewId = coordinator.tweakerProfilePreviewId,
        tweakerProfileDraftPending = coordinator.tweakerProfileDraftPending,
        tweakerCeilingPreviewLabel = coordinator.tweakerCeilingPreviewLabel,
        tweakerCeilingDraftPending = coordinator.tweakerCeilingDraftPending,
        tweakerCeilingPreviewReleasesHold = coordinator.tweakerCeilingPreviewReleasesHold,
        onApplyTweakerProfile = coordinator::applyTweakerProfileDraft,
        onCancelTweakerProfile = coordinator::cancelTweakerProfileDraft,
        tweakerManualFan = coordinator.tweakerAdvancedCapabilities?.manualFan,
        onStepTweakerFanMode = coordinator::stepTweakerFanMode,
        onStepTweakerFanCurve = coordinator::stepTweakerFanCurve,
        tweakerFanCurvePreviewBias = coordinator.tweakerFanCurvePreviewBias,
        tweakerFanCurveDraftPending = coordinator.tweakerFanCurveDraftPending,
        onPreviewTweakerFanCurve = coordinator::previewTweakerFanCurveBias,
        onApplyTweakerFanCurve = coordinator::applyTweakerFanCurveDraft,
        onCancelTweakerFanCurve = coordinator::cancelTweakerFanCurveDraft,
        performanceOverlayVisibility = performanceOverlayVisibility,
        performanceOverlayMode = performanceOverlayMode,
        performanceOverlayRowValue = performanceOverlayRowValue(
            performanceOverlayVisibility,
            coordinator.tweakerUsageAccessState,
            coordinator.performanceOverlayCanDrawOverlays,
        ),
        onStepPerformanceOverlay = coordinator::stepPerformanceOverlay,
        tweakerUsageAccessState = coordinator.tweakerUsageAccessState,
        onRequestTweakerUsageAccess = coordinator::openTweakerUsageAccess,
        surface = coordinator.surface,
        tweakerMessage = coordinator.tweakerMessage,
        onTweakerMessageDelivered = coordinator::consumeTweakerMessage,
        onDismiss = dismiss,
        onBack = if (target == AppBrightnessPresentationTarget.SECONDARY) {
            coordinator::handleSecondaryBack
        } else {
            coordinator::handleBack
        },
        // Secondary dismissal records the exact operation before requesting cancellation so a confirmed
        // restore can close that Presentation; an uncertain result remains visible. Primary uses the
        // direct idempotent request path.
        onCancelTweakerMutation = cancelMutation,
        backActionLabel = coordinator.backActionLabel,
        focusEnabled = target == AppBrightnessPresentationTarget.PRIMARY,
        enableBackHandler = target == AppBrightnessPresentationTarget.PRIMARY,
        settledOnMount = settledOnMount,
        // The Bottom (SECONDARY) never holds the process-wide pad gate; Main's hosts keep holding it as before.
        holdPad = target == AppBrightnessPresentationTarget.PRIMARY,
    )
    val privilegedStatus = state.capabilities.privilegedUtilityPosture.status
    LaunchedEffect(
        coordinator.isOpen,
        state.capabilities.hasVendorControls,
        privilegedStatus,
        lifecycleOwner,
    ) {
        if (!coordinator.isOpen || !state.capabilities.hasVendorControls) return@LaunchedEffect
        val intervalMillis =
            utilityPrivilegedRefreshIntervalMillis(privilegedStatus) ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (coordinator.isOpen) {
                delay(intervalMillis)
                coordinator.refreshPrivilegedPosture()
            }
        }
    }
    LaunchedEffect(coordinator.isOpen, lifecycleOwner) {
        if (!coordinator.isOpen) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (coordinator.isOpen) {
                delay(UTILITY_TWEAKER_TELEMETRY_REFRESH_MS)
                coordinator.refreshTweakerUtilityState(periodic = true)
            }
        }
    }
}

/**
 * Foreground-only Utility posture cadence.
 *
 * Binding and an explicit in-place permission/retry flow need prompt convergence. READY needs only a
 * sparse health heartbeat; the
 * fixed 500 ms loop previously performed thirty times more READY probes. States whose recovery leaves
 * this Activity (manager, settings, install) reconcile through refreshOnResume and need no heartbeat.
 */
internal fun utilityPrivilegedRefreshIntervalMillis(
    status: PrivilegedUtilityStatus,
): Long? = when (status) {
    PrivilegedUtilityStatus.BINDING -> UtilityBindingRefreshMillis
    PrivilegedUtilityStatus.PERMISSION_REQUIRED,
    PrivilegedUtilityStatus.CONNECTION_FAILED,
    -> UtilityAccessRefreshMillis
    PrivilegedUtilityStatus.READY -> UtilityReadyHeartbeatMillis
    PrivilegedUtilityStatus.UNSUPPORTED_DEVICE,
    PrivilegedUtilityStatus.MANAGER_MISSING,
    PrivilegedUtilityStatus.SERVICE_STOPPED,
    PrivilegedUtilityStatus.API_UNSUPPORTED,
    PrivilegedUtilityStatus.PERMISSION_DENIED,
    PrivilegedUtilityStatus.REMOTE_PERMISSION_MISSING,
    PrivilegedUtilityStatus.READBACK_UNSATISFIABLE,
    -> null
}

// Matches the daemon's own fan-monitor/evidence-sampler cadence (both tick at 1s) so the panel
// never shows a reading staler than one daemon sample under normal conditions. The existing
// tweakerRefreshJob/generation guard in refreshTweakerUtilityState() already prevents this from
// stacking if one round trip runs long -- a slow poll is simply skipped, not queued.
internal const val UTILITY_TWEAKER_TELEMETRY_REFRESH_MS = 1_000L

/**
 * Fan-control temperature retention bound (design spec §4.3). A held GPU/CPU reading survives at
 * most this long past the projection cycle that accepted it; beyond that [boundedRetainedFanTempC]
 * returns null rather than painting a stale number forever if the daemon dies (G-12). Composed with
 * the 3 500 ms `UTILITY_FAN_TELEMETRY_MAX_AGE_MS` freshness gate upstream in `TweakerBridge`, the
 * worst-case displayed staleness is freshness-gate-age + this hold ≈ 7.5 s -- this number is NOT
 * instantaneous, and no caller may treat it as such.
 */
internal const val FAN_TEMPERATURE_RETENTION_HOLD_MS = 4_000L

/**
 * Bounds a held fan-control temperature reading to [holdMs] past [retainedAtElapsedRealtimeMs] --
 * the elapsedRealtime at which the projection cycle *accepted* [retained] as its current value, not
 * the originating sample's own `sampledAtElapsedRealtimeMs`. Stamping at acceptance keeps this hold
 * and the upstream freshness gate additive instead of double-counting the same staleness window.
 *
 * One skipped 1 Hz sample must not blink the digits (a null [retainedAtElapsedRealtimeMs] -- nothing
 * accepted yet -- returns null immediately, same as a null [retained]).
 */
internal fun boundedRetainedFanTempC(
    retained: Int?,
    retainedAtElapsedRealtimeMs: Long?,
    nowElapsedRealtimeMs: Long,
    holdMs: Long = FAN_TEMPERATURE_RETENTION_HOLD_MS,
): Int? {
    if (retained == null) return null
    val at = retainedAtElapsedRealtimeMs ?: return null
    return retained.takeIf { nowElapsedRealtimeMs - at <= holdMs }
}

internal fun shouldScheduleUtilityRecoveryHandshake(
    utilityOpen: Boolean,
    status: PrivilegedUtilityStatus,
): Boolean = !utilityOpen && status == PrivilegedUtilityStatus.BINDING

internal fun cancelUtilityRecoveryJob(job: Job?): Boolean {
    if (job == null) return false
    job.cancel()
    return true
}

internal const val UtilityBindingRefreshMillis = 500L
internal const val UtilityAccessRefreshMillis = 1_000L
internal const val UtilityReadyHeartbeatMillis = 15_000L

/**
 * How long a transfer waits for a follow-up before its roots are rescanned ([kickTransferRescan]).
 *
 * **Unmeasured, and a feel value rather than a derived one** — it trades how soon a moved ROM appears
 * on the shelf against how many scans a burst of moves costs. Long enough that moving several files in
 * a row collapses into one scan, short enough that a single move does not feel like it did nothing.
 * This remains subject to tuning by eye on device; nothing here justifies the exact number.
 */
internal const val TransferRescanDebounceMs = 3_000L

internal fun <T> launchPrivilegedOperation(
    scope: CoroutineScope,
    mutex: Mutex,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    request: suspend () -> T,
    onComplete: (T) -> Unit,
): Job = scope.launch {
    val result = mutex.withLock {
        withContext(dispatcher) {
            request()
        }
    }
    onComplete(result)
}

internal enum class PrivilegedUtilityAccessFeedback(val message: String) {
    READY("Special access is ready."),
    STARTED("Special-access setup started."),
    UPDATED("Special-access status changed. Review the current setup step."),
    UNAVAILABLE("Special-access setup isn’t available on this device."),
}

internal data class PrivilegedUtilityAccessResult(
    val state: BrightnessState,
    val started: Boolean,
)

internal fun reducePrivilegedUtilityAccessFeedback(
    expectedStatus: PrivilegedUtilityStatus,
    started: Boolean,
    statusAfter: PrivilegedUtilityStatus,
): PrivilegedUtilityAccessFeedback = when {
    statusAfter == PrivilegedUtilityStatus.READY -> PrivilegedUtilityAccessFeedback.READY
    started -> PrivilegedUtilityAccessFeedback.STARTED
    statusAfter != expectedStatus -> PrivilegedUtilityAccessFeedback.UPDATED
    else -> PrivilegedUtilityAccessFeedback.UNAVAILABLE
}

internal enum class AndroidAppSwitchingSetupFeedback(val message: String) {
    READY("Android app switching is ready."),
    STARTED("Android app switching setup started."),
    UPDATED("Android app switching status changed. Review the current setup step."),
    UNAVAILABLE("Android app switching setup isn’t available on this device."),
}

internal data class AndroidAppSwitchingSetupResult(
    val feedback: AndroidAppSwitchingSetupFeedback,
) {
    val accepted: Boolean
        get() = feedback == AndroidAppSwitchingSetupFeedback.READY ||
            feedback == AndroidAppSwitchingSetupFeedback.STARTED
}

/**
 * Executes exactly one user-requested setup action, then reads readiness for truthful feedback.
 * It does not grant permissions, start ADB, or elevate privileges on Mimir's behalf.
 */
internal fun requestAndroidAppSwitchingSetup(
    gateway: MButtonSystemRecentsGateway,
    expectedStatus: PrivilegedUtilityStatus,
): AndroidAppSwitchingSetupResult {
    val started = runCatching {
        gateway.requestSystemRecentsAccess(expectedStatus)
    }.getOrDefault(false)
    val statusAfter = runCatching {
        gateway.systemRecentsAccessStatus()
    }.getOrDefault(PrivilegedUtilityStatus.UNSUPPORTED_DEVICE)
    val feedback = when {
        statusAfter == PrivilegedUtilityStatus.READY -> AndroidAppSwitchingSetupFeedback.READY
        started -> AndroidAppSwitchingSetupFeedback.STARTED
        statusAfter != expectedStatus -> AndroidAppSwitchingSetupFeedback.UPDATED
        else -> AndroidAppSwitchingSetupFeedback.UNAVAILABLE
    }
    return AndroidAppSwitchingSetupResult(feedback)
}
