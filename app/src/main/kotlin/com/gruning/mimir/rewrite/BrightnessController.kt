package com.gruning.mimir.rewrite

import kotlin.math.abs

internal const val IdleJoystickLightFraction = 0.01f

/** Idle dim level for a vendor-owned second screen; the privileged panel transaction floors at 5%. */
internal const val IdleSecondaryDimFraction = 0.05f

/**
 * The vendor stick brightness Mimir raises to when it takes the light over and finds the lamp dark.
 *
 * 0.05 is not a taste pick: it is a value read from an Odin device while
 * diagnosing a Nova device (which sat at 0.0). Deliberately low — the point is "visibly lit", not "bright";
 * anything he has set above this floor is left exactly alone.
 */
internal const val ThemeAwareJoystickLightFloor = 0.05f

sealed interface BrightnessUpdateOutcome {
    val state: BrightnessState

    data class Applied(
        override val state: BrightnessState,
        val windowApplied: Boolean,
        val systemApplied: Boolean,
    ) : BrightnessUpdateOutcome

    data class WriteAccessRequired(
        override val state: BrightnessState,
        val windowApplied: Boolean,
        val request: BrightnessPermissionRequest,
    ) : BrightnessUpdateOutcome

    data class WriteFailed(override val state: BrightnessState) : BrightnessUpdateOutcome
    data class Unsupported(override val state: BrightnessState) : BrightnessUpdateOutcome
}

/**
 * The existing Brightness route now backs the broader Utility surface without changing shell navigation IDs.
 * Each global/vendor/app-local control has an independent injected gateway and an honest capability gate.
 */
class BrightnessController(
    private val settings: BrightnessSettingsGateway,
    private val preferences: WindowBrightnessPreferenceStore,
    private val identity: BrightnessDeviceIdentity,
    private val secondaryWindowAvailable: () -> Boolean,
    private val windowSink: WindowBrightnessSink = WindowBrightnessSink.None,
    private val utilityPreferences: UtilityPreferenceStore = UtilityPreferenceStore.None,
    private val haptics: MimirHapticGateway = MimirHapticGateway.None,
    private val navSound: NavSoundGateway = NavSoundGateway.None,
    private val ambientSound: AmbientSoundGateway = AmbientSoundGateway.None,
    private val mediaVolume: MediaVolumeGateway = MediaVolumeGateway.None,
    private val themeColorArgb: () -> Int = { 0xffffffff.toInt() },
    private val privilegedUtility: PrivilegedUtilityGateway = PrivilegedUtilityGateway.None,
    private val secondaryDisplayId: () -> Int? = { null },
    private val onScreenTimeoutChanged: (Int) -> Unit = {},
    private val deferInitialPrivilegedProbe: Boolean = false,
) {
    private var lastLoadedSecondaryWindowAvailable: Boolean? = null
    private var lastLoadedSecondaryDisplayId: Int? = null
    private var lastLoadedRawPrivilegedPosture: PrivilegedUtilityPosture =
        PrivilegedUtilityPosture.Unsupported

    /**
     * Consecutive full-probe failures of the typed vendor readback while the bridge itself reported
     * READY. One miss is treated as retryable; a repeat through a healthy bridge is deterministic,
     * so the posture becomes [PrivilegedUtilityStatus.READBACK_UNSATISFIABLE] instead of offering a
     * retry that cannot succeed. Any probe that passes — or any real posture change — resets it.
     */
    private var consecutiveReadbackContractFailures = 0
    private var idleDimmed = false
    private val idleJoystickStateLock = Any()
    private val idleJoystickIoLock = Any()
    private var idleJoystickGeneration = 0L
    private var idleJoystickDimRequested = false

    var state: BrightnessState = loadState(probePrivileged = !deferInitialPrivilegedProbe)
        private set

    fun refresh(): BrightnessState {
        state = loadState()
        return state
    }

    /** Cheap modal heartbeat: demote on Binder death without re-reading every vendor setting at 2 Hz. */
    fun refreshPrivilegedLiveness(): BrightnessState {
        if (!state.capabilities.hasVendorControls) return state
        val previous = state.capabilities.privilegedUtilityPosture
        val live = privilegedUtility.posture()
        val liveSecondaryWindowAvailable = secondaryWindowAvailable()
        val liveSecondaryDisplayId = secondaryDisplayId()
        val secondaryTopologyChanged =
            state.capabilities.secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM &&
                (lastLoadedSecondaryWindowAvailable != liveSecondaryWindowAvailable ||
                    lastLoadedSecondaryDisplayId != liveSecondaryDisplayId)
        if (live.status == PrivilegedUtilityStatus.READY) {
            if (
                previous.status == PrivilegedUtilityStatus.CONNECTION_FAILED ||
                previous.status == PrivilegedUtilityStatus.REMOTE_PERMISSION_MISSING ||
                previous.status == PrivilegedUtilityStatus.READBACK_UNSATISFIABLE
            ) {
                if (secondaryTopologyChanged) {
                    state = state.copy(
                        capabilities = state.capabilities.copy(
                            secondaryWindowAvailable = liveSecondaryWindowAvailable,
                        ),
                    )
                }
                return state
            }
            if (
                previous.status != PrivilegedUtilityStatus.READY ||
                lastLoadedRawPrivilegedPosture.status != live.status ||
                lastLoadedRawPrivilegedPosture.canWriteVendorSettings != live.canWriteVendorSettings ||
                lastLoadedRawPrivilegedPosture.canControlSecondaryBrightness !=
                    live.canControlSecondaryBrightness ||
                secondaryTopologyChanged
            ) {
                state = loadState()
            }
            return state
        }
        state = state.copy(
            capabilities = state.capabilities.copy(
                privilegedUtilityPosture = live.copy(
                    canWriteVendorSettings = false,
                    canControlSecondaryBrightness = false,
                ),
                secondaryWindowAvailable = liveSecondaryWindowAvailable,
            ),
            themeAwareJoystickLight = false,
        )
        return state
    }

    /** Vendor access is requested only from the dedicated explicit Utility action. */
    fun requestPrivilegedAccess(expectedStatus: PrivilegedUtilityStatus): Boolean {
        val requested = privilegedUtility.requestAccess(expectedStatus)
        state = loadState()
        return requested
    }

    /** Reapply durable window values after Activity/Presentation creation without touching Settings.System. */
    fun applyWindowFallbacks(): WindowBrightnessFallbacks = state.windowFallbacks.also { fallback ->
        if (idleDimmed) {
            windowSink.applyIdleDim(includeSecondary = idleDimIncludesSecondaryWindow())
        } else {
            windowSink.applyPrimary(fallback.primary)
            if (secondaryWindowAvailable()) windowSink.applySecondary(fallback.secondary)
        }
    }

    /** Dims only mounted Mimir windows. Wake restores the exact system/window authority from current state. */
    fun setIdleDimmed(dimmed: Boolean) {
        if (idleDimmed == dimmed) return
        idleDimmed = dimmed
        if (dimmed) {
            windowSink.applyIdleDim(includeSecondary = idleDimIncludesSecondaryWindow())
        } else {
            applyWindowFallbacks()
        }
    }

    /**
     * Dim/restore the PRIMARY panel through the SYSTEM brightness, off-main.
     *
     * The window override cannot do it on this hardware. Measured on the Thor 2026-09-06 across a
     * full wake -> idle -> wake cycle: `panel1-backlight` moved 1562 -> 19 -> 1562 while
     * `panel0-backlight` sat at 2450 throughout, even though
     * `mScreenBrightnessOverrideFromWindowManager` went NaN -> 0.01 -> NaN. The framework had the
     * override; the main panel simply does not follow it, because [updatePrimary] puts the primary on
     * `Settings.System` and then RELEASES the window override (`applyPrimary(null)`). So "Dim displays
     * after" only ever dimmed the second display.
     *
     * The same device the same evening (23:08, on the build carrying this fix) read the other way: at the
     * dim onset panel0 ramped 2450 -> 44 and panel1 sat at 1562 throughout, and the display-4 power
     * controller held the 0.01 override as pending while enforcing the vendor's 0.6. Both readings stand
     * with their clocks; the second screen now dims through [applyIdleSecondarySystemBrightness].
     *
     * Journalled before the write, so a process death while dimmed cannot leave the main screen at 1%:
     * the next dim or the next resume restores it first. Same discipline as the idle joystick light,
     * which is proven on hardware.
     *
     * Runs on [kotlinx.coroutines.Dispatchers.IO] with the other privileged idle work -- `Settings.System`
     * is a binder round trip and must not sit on the frame path.
     */
    fun applyIdlePrimarySystemBrightness(generation: Long): Boolean = synchronized(idleJoystickIoLock) {
        // Resolve the authoritative request from the SAME latest-generation token the joystick uses, so a
        // dim that lost the race to a wake can never commit after it.
        val dimmed = idleJoystickRequest(generation) ?: return@synchronized false
        if (!dimmed) return@synchronized restoreIdlePrimarySystemBrightness()
        // Heal a journal a dead process left behind BEFORE snapshotting a new original, or the dim
        // level would be captured as the value to restore and the screen would stay at 1% for good.
        restoreIdlePrimarySystemBrightness()
        if (!state.capabilities.mainSystemBrightnessActive || !settings.canWrite()) {
            return@synchronized false
        }
        if (!BrightnessMath.isManualBrightnessMode(settings.read(BrightnessSettingsKeys.PRIMARY_MODE))) {
            return@synchronized false
        }
        val key = BrightnessSettingsKeys.PRIMARY
        val original = settings.read(key)?.takeIf { BrightnessMath.systemFraction(it) != null }
            ?: return@synchronized false
        if (!utilityPreferences.beginIdleSystemBrightnessRestore(original)) return@synchronized false
        val dimLevel = BrightnessMath.systemLevel(MimirIdleDimFraction).toString()
        if (settings.write(key, dimLevel)) {
            true
        } else {
            utilityPreferences.clearIdleSystemBrightnessRestore()
            false
        }
    }

    /**
     * Dim/restore the SECOND screen through the vendor-owned panel brightness, off-main.
     *
     * A single-display dimming failure was reported on 2026-09-06 after the main-screen fix. Measured on the Thor at 100 ms across a full
     * wake -> idle -> wake cycle: at the dim onset `panel0-backlight` ramped 2450 -> 44 while
     * `panel1-backlight` sat at 1562 the whole time; at the wake, panel1 flashed 1562 -> 17 -> 1562 over
     * ~1.5 s. Display 4's power controller held Mimir's 0.01 window override as PENDING while enforcing
     * 0.6 "manual" -- `dual_screen_brightness_level=60`, the vendor's own level, which its dual-screen
     * assistant writes through DisplayManager. Writing that key alone moved nothing. So on this build the
     * Presentation's window override is no lever for the second screen. The wake flash is measured; its
     * SOURCE is not established (the Presentation's own override, or display 4 lagging one request behind
     * the global override that MainActivity's window on display 0 still sets), so whether dropping the
     * Presentation override removes it was device-unproven until the install pass of 23:31 the same day:
     * one wake -> idle -> wake cycle on this build read panel1 1562 -> 18 (dimmed) -> 1562 with a clean
     * ramp up and no dip at wake. One cycle; not a proof it never recurs.
     *
     * The lever is the privileged panel transaction the Utility Screen-2 slider already uses (display
     * write + vendor mirror, verified by read-back). It floors at [IdleSecondaryDimFraction]. Journalled
     * before the write and healed on start, exactly like the primary: a per-display brightness write
     * persists, so a process death while dimmed would otherwise leave the second screen at 5%. Gated on
     * the same latest-generation token as the joystick and the primary. Without privileged access the
     * second screen stays undimmed, which is what it did before this change.
     */
    fun applyIdleSecondarySystemBrightness(generation: Long): Boolean = synchronized(idleJoystickIoLock) {
        val dimmed = idleJoystickRequest(generation) ?: return@synchronized false
        if (!dimmed) return@synchronized restoreIdleSecondarySystemBrightness()
        // Heal a journal a dead process left behind BEFORE snapshotting a new original, or the dim level
        // would be captured as the value to restore and the second screen would stay at 5% for good.
        restoreIdleSecondarySystemBrightness()
        val displayId = readySecondarySystemDisplay() ?: return@synchronized false
        val original = privilegedUtility.readDisplayBrightness(displayId)
            ?.takeIf { it.isFinite() && it in 0f..1f }
            ?: return@synchronized false
        // Temporary idle behaviour must never brighten an already-dim panel.
        if (original <= IdleSecondaryDimFraction + 0.0001f) return@synchronized true
        if (!utilityPreferences.beginIdleSecondaryBrightnessRestore(original.toString())) {
            return@synchronized false
        }
        if (idleJoystickRequest(generation) != true) return@synchronized restoreIdleSecondarySystemBrightness()
        val written = privilegedUtility.setSecondaryBrightness(
            displayId,
            IdleSecondaryDimFraction,
            BrightnessMath.vendorPercentLevel(IdleSecondaryDimFraction),
        )
        if (!written || idleJoystickRequest(generation) != true) {
            return@synchronized restoreIdleSecondarySystemBrightness()
        }
        // Round-5 B7: the panel holds the idle dim; the player's level has not changed. No player-state write
        // belongs here -- SECONDARY keeps reporting the journalled original (see loadState).
        true
    }

    /** Give the player's own second-screen brightness back. Safe to call when nothing is owed. */
    private fun restoreIdleSecondarySystemBrightness(): Boolean {
        val owed = utilityPreferences.readIdleSecondaryBrightnessRestore() ?: return false
        val fraction = owed.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
        if (fraction == null) {
            // A journal that cannot be read back is not a debt Mimir can pay; keeping it would park every
            // future dim behind a restore that can never succeed.
            utilityPreferences.clearIdleSecondaryBrightnessRestore()
            return false
        }
        val displayId = readySecondarySystemDisplay() ?: return false
        val restore = BrightnessMath.windowFraction(fraction)
        val written = privilegedUtility.setSecondaryBrightness(
            displayId,
            restore,
            BrightnessMath.vendorPercentLevel(restore),
        )
        if (!written || !utilityPreferences.clearIdleSecondaryBrightnessRestore()) return false
        state = state.copy(secondaryFraction = restore)
        return true
    }

    /** The second screen's display id when its idle dim goes through the vendor panel and privilege is live. */
    private fun readySecondarySystemDisplay(): Int? {
        if (!secondaryIdleDimUsesSystem()) return null
        state = loadState()
        if (!state.capabilities.canAdjustSecondary) return null
        return secondaryDisplayId()
    }

    /**
     * On a vendor-owned second screen the idle dim never touches the Presentation window: the override did
     * nothing there at the dim onset. Whether it was also the wake flash is device-unproven (see
     * [applyIdleSecondarySystemBrightness]).
     */
    private fun secondaryIdleDimUsesSystem(): Boolean =
        state.capabilities.secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM

    private fun idleDimIncludesSecondaryWindow(): Boolean =
        secondaryWindowAvailable() && !secondaryIdleDimUsesSystem()

    /** Give the player's own brightness back. Safe to call when nothing is owed. */
    private fun restoreIdlePrimarySystemBrightness(): Boolean {
        val owed = utilityPreferences.readIdleSystemBrightnessRestore() ?: return false
        if (!settings.canWrite()) return false
        if (!settings.write(BrightnessSettingsKeys.PRIMARY, owed)) return false
        utilityPreferences.clearIdleSystemBrightnessRestore()
        // Round-5 B7: MAIN follows the level just given back, as the secondary restore already does. A primary
        // held by the window fallback keeps showing that fallback: the precedence loadState applies.
        val restored = BrightnessMath.systemFraction(owed)
        if (restored != null && state.primaryWindowFallback == null) {
            state = state.copy(primaryFraction = restored)
        }
        return true
    }

    /** Main-thread, I/O-free ownership handoff for the next privileged idle-light transaction. */
    fun prepareIdleJoystickTransition(dimmed: Boolean): Long = synchronized(idleJoystickStateLock) {
        idleJoystickDimRequested = dimmed
        idleJoystickGeneration = if (idleJoystickGeneration == Long.MAX_VALUE) {
            Long.MIN_VALUE
        } else {
            idleJoystickGeneration + 1L
        }
        idleJoystickGeneration
    }

    /** Runs on Dispatchers.IO. A newer wake/dim request invalidates this token before any stale commit. */
    fun applyIdleJoystickTransition(generation: Long): Boolean = synchronized(idleJoystickIoLock) {
        val dimmed = idleJoystickRequest(generation) ?: return@synchronized false
        if (dimmed) dimJoystickLightForIdle(generation) else restoreIdleJoystickLight(generation)
    }

    /** Called by the existing READY-posture heartbeat, already serialized off-main by its coordinator. */
    fun reconcileIdleJoystickLightAfterReady(): BrightnessState {
        val request = synchronized(idleJoystickStateLock) {
            idleJoystickGeneration to idleJoystickDimRequested
        }
        if (!request.second && utilityPreferences.readIdleJoystickLightRestore() != null) {
            applyIdleJoystickTransition(request.first)
        }
        return state
    }

    private fun idleJoystickRequest(generation: Long): Boolean? = synchronized(idleJoystickStateLock) {
        idleJoystickDimRequested.takeIf { idleJoystickGeneration == generation }
    }

    private fun refreshIdleJoystickCapability(): Boolean {
        state = loadState()
        return state.capabilities.canAdjustJoystickLight
    }

    private fun dimJoystickLightForIdle(generation: Long): Boolean {
        // A process-death journal always restores before a new temporary dim snapshots hardware.
        if (utilityPreferences.readIdleJoystickLightRestore() != null &&
            !restoreIdleJoystickLight(generation, allowDimRequest = true)
        ) {
            return false
        }
        if (idleJoystickRequest(generation) != true || !refreshIdleJoystickCapability()) return false
        val percentKey = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        val enabledKey = BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED
        val originalPercent = privilegedUtility.readVendorSetting(percentKey)
            ?.takeIf { BrightnessMath.joystickFraction(it) != null }
            ?: return false
        val originalEnabled = privilegedUtility.readVendorSetting(enabledKey)
            ?.takeIf { BrightnessMath.joystickEnabled(it) != null }
            ?: return false
        val fraction = BrightnessMath.joystickFraction(originalPercent) ?: return false
        val enabled = BrightnessMath.joystickEnabled(originalEnabled) ?: return false
        // Temporary idle behavior must never enable or brighten an already-off / already-dim light.
        if (!enabled || fraction <= IdleJoystickLightFraction + 0.0001f) return true
        val restore = IdleJoystickLightRestore(originalPercent, originalEnabled)
        if (!utilityPreferences.beginIdleJoystickLightRestore(restore)) return false
        if (idleJoystickRequest(generation) != true) {
            return restoreIdleJoystickLightForCurrentRequest(allowDimRequest = true)
        }
        val dimValue = BrightnessMath.joystickPercentValue(minOf(fraction, IdleJoystickLightFraction))
        val written = privilegedUtility.writeVendorSetting(percentKey, dimValue)
        val percentReadBack = privilegedUtility.readVendorSetting(percentKey)
        val enabledReadBack = privilegedUtility.readVendorSetting(enabledKey)
        val verified = written &&
            BrightnessMath.joystickFraction(percentReadBack)?.let {
                abs(it - IdleJoystickLightFraction) <= 0.001f
            } == true &&
            vendorValueMatches(enabledKey, enabledReadBack, originalEnabled)
        if (!verified || idleJoystickRequest(generation) != true) {
            return restoreIdleJoystickLightForCurrentRequest(allowDimRequest = true)
        }
        state = state.copy(joystickLightFraction = IdleJoystickLightFraction)
        return true
    }

    private fun restoreIdleJoystickLightForCurrentRequest(allowDimRequest: Boolean): Boolean {
        val generation = synchronized(idleJoystickStateLock) { idleJoystickGeneration }
        return restoreIdleJoystickLight(generation, allowDimRequest)
    }

    private fun restoreIdleJoystickLight(
        generation: Long,
        allowDimRequest: Boolean = false,
    ): Boolean {
        val requested = idleJoystickRequest(generation) ?: return false
        if (requested && !allowDimRequest) return false
        val restore = utilityPreferences.readIdleJoystickLightRestore() ?: return true
        if (!refreshIdleJoystickCapability()) return false
        val percentKey = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        val enabledKey = BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED
        val percentWritten = privilegedUtility.writeVendorSetting(percentKey, restore.percent)
        val enabledWritten = percentWritten &&
            privilegedUtility.writeVendorSetting(enabledKey, restore.enabled)
        val percentReadBack = privilegedUtility.readVendorSetting(percentKey)
        val enabledReadBack = privilegedUtility.readVendorSetting(enabledKey)
        val verified = enabledWritten &&
            vendorValueMatches(percentKey, percentReadBack, restore.percent) &&
            vendorValueMatches(enabledKey, enabledReadBack, restore.enabled)
        if (!verified || !utilityPreferences.clearIdleJoystickLightRestore()) return false
        val enabled = BrightnessMath.joystickEnabled(restore.enabled) == true
        val fraction = BrightnessMath.joystickFraction(restore.percent)
        state = state.copy(joystickLightFraction = if (enabled) fraction else 0f)
        return true
    }

    fun step(target: BrightnessTarget, direction: Int): BrightnessUpdateOutcome {
        if (target == BrightnessTarget.MEDIA_VOLUME) return stepMediaVolume(direction)
        if (target == BrightnessTarget.JOYSTICK_LIGHT) {
            return synchronized(idleJoystickIoLock) {
                // A wake can queue an exact restore while Utility is already visible. Restore, read,
                // calculate and write under one hardware transaction so Right never steps from the
                // temporary 1% idle value and overwrites the user's pre-dim brightness.
                if (utilityPreferences.readIdleJoystickLightRestore() != null &&
                    !restoreIdleJoystickLightForCurrentRequest(allowDimRequest = true)
                ) {
                    return@synchronized BrightnessUpdateOutcome.WriteFailed(state)
                }
                val current = refreshLiveFraction(target)
                    ?: return@synchronized refreshAfterPrivilegedFailure()
                updateJoystickLightTransaction(BrightnessMath.step(target, current, direction))
            }
        }
        val current = refreshLiveFraction(target) ?: return if (target.isPrivilegedUtilityTarget()) {
            refreshAfterPrivilegedFailure()
        } else {
            BrightnessUpdateOutcome.Unsupported(state)
        }
        if (target == BrightnessTarget.MIMIR_HAPTIC && !state.capabilities.mimirHapticAmplitudeControl) {
            return setFraction(target, if (direction < 0) 0f else 1f)
        }
        return setFraction(target, BrightnessMath.step(target, current, direction))
    }

    fun setFraction(target: BrightnessTarget, requestedFraction: Float): BrightnessUpdateOutcome {
        if (state.privilegedFailureMessage != null) state = state.copy(privilegedFailureMessage = null)
        if (!state.canAdjustTarget(target)) {
            return BrightnessUpdateOutcome.Unsupported(state)
        }
        val fraction = BrightnessMath.quantize(target, requestedFraction)
        // Privileged hardware can change outside Mimir. A direct set is itself the authority request;
        // never suppress it merely because the last rendered value happens to match.
        val current = if (!target.isPrivilegedUtilityTarget()) {
            refreshLiveFraction(target)
        } else {
            null
        }
        val permissionRelevant = target == BrightnessTarget.PRIMARY
        val systemPostureMatches = when (target) {
            BrightnessTarget.PRIMARY -> state.capabilities.mainSystemBrightnessActive == (
                settings.canWrite() && BrightnessMath.isManualBrightnessMode(
                    settings.read(BrightnessSettingsKeys.PRIMARY_MODE),
                )
            )
            BrightnessTarget.SECONDARY -> true
            BrightnessTarget.SCREEN_TIMEOUT -> true
            else -> true
        }
        val equalityTolerance = if (target == BrightnessTarget.PRIMARY) 0.0021f else 0.001f
        if (
            current != null &&
            abs(current - fraction) < equalityTolerance &&
            systemPostureMatches &&
            (!permissionRelevant || state.capabilities.canWriteSystemSettings == settings.canWrite())
        ) {
            return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
        }
        return when (target) {
            BrightnessTarget.PRIMARY -> updatePrimary(fraction)
            BrightnessTarget.SECONDARY -> updateSecondary(fraction)
            BrightnessTarget.SCREEN_TIMEOUT -> updateScreenTimeout(fraction)
            BrightnessTarget.JOYSTICK_LIGHT -> updateJoystickLight(fraction)
            BrightnessTarget.JOYSTICK_COLOR -> updateJoystickColor(fraction)
            BrightnessTarget.MIMIR_HAPTIC -> updateMimirHaptic(fraction)
            BrightnessTarget.NAV_SOUND -> updateNavSound(fraction)
            BrightnessTarget.AMBIENT_SOUND -> updateAmbientSound(fraction)
            BrightnessTarget.MEDIA_VOLUME -> updateMediaVolume(fraction)
            // Not a Utility control: the equalizer editor owns its own sliders and never asks the controller.
            BrightnessTarget.EQ_GAIN -> BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
        }
    }

    private fun refreshLiveFraction(target: BrightnessTarget): Float? {
        return when (target) {
            BrightnessTarget.PRIMARY -> {
                if (!state.capabilities.mainSystemBrightnessActive) return state.primaryFraction
                val actual = BrightnessMath.systemFraction(settings.read(BrightnessSettingsKeys.PRIMARY))
                    ?: return state.primaryFraction
                state = state.copy(primaryFraction = actual)
                actual
            }
            BrightnessTarget.SECONDARY -> {
                if (state.capabilities.secondarySupport != SecondaryBrightnessSupport.AYN_SYSTEM) {
                    return state.secondaryFraction
                }
                val displayId = secondaryDisplayId() ?: return null
                val actual = privilegedUtility.readDisplayBrightness(displayId)
                    ?.takeIf { it.isFinite() && it in 0f..1f }
                    ?.let(BrightnessMath::windowFraction)
                    ?: return null
                state = state.copy(secondaryFraction = actual)
                actual
            }
            BrightnessTarget.SCREEN_TIMEOUT -> BrightnessMath.screenTimeoutFraction(state.screenTimeoutSeconds)
            BrightnessTarget.JOYSTICK_LIGHT -> {
                if (!state.capabilities.canAdjustJoystickLight) return null
                val percent = BrightnessMath.joystickFraction(
                    privilegedUtility.readVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT),
                ) ?: return null
                val enabled = BrightnessMath.joystickEnabled(
                    privilegedUtility.readVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED),
                ) ?: return null
                val actual = if (enabled) percent else 0f
                state = state.copy(joystickLightFraction = actual)
                actual
            }
            BrightnessTarget.JOYSTICK_COLOR -> {
                if (!state.capabilities.canAdjustJoystickColor) return null
                val actualArgb = JoystickColorCodec.parse(
                    privilegedUtility.readVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR),
                )?.firstOrNull() ?: return null
                val actual = JoystickColorCodec.fractionForArgb(actualArgb)
                state = state.copy(joystickColorFraction = actual, joystickColorArgb = actualArgb)
                actual
            }
            BrightnessTarget.MEDIA_VOLUME -> {
                val actual = mediaVolume.readFraction()?.let(BrightnessMath::utilityFraction) ?: return null
                state = state.copy(mediaVolumeFraction = actual)
                actual
            }
            BrightnessTarget.MIMIR_HAPTIC -> state.mimirHapticFraction
            // Only Mimir writes this preference, so state is the live truth (haptic parity).
            BrightnessTarget.NAV_SOUND -> state.navSoundFraction
            // The active player observes the same preference flow; no hardware readback is involved.
            BrightnessTarget.AMBIENT_SOUND -> state.ambientSoundFraction
            BrightnessTarget.EQ_GAIN -> null
        }
    }

    private fun updateScreenTimeout(fraction: Float): BrightnessUpdateOutcome {
        val seconds = BrightnessMath.screenTimeoutSeconds(fraction)
        utilityPreferences.writeScreenTimeoutSeconds(seconds)
        state = state.copy(screenTimeoutSeconds = seconds)
        onScreenTimeoutChanged(seconds)
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
    }

    fun setThemeAwareJoystickLight(enabled: Boolean): BrightnessUpdateOutcome {
        if (state.privilegedFailureMessage != null) state = state.copy(privilegedFailureMessage = null)
        if (enabled && utilityPreferences.isThemeAwareJoystickLightRestorePending()) {
            if (!state.capabilities.canAdjustJoystickColor) {
                return BrightnessUpdateOutcome.Unsupported(state)
            }
            // A fresh enable request first closes any interrupted transaction, then starts cleanly
            // from the restored manual color below.
            val restored = restorePendingThemeColor()
            if (restored !is BrightnessUpdateOutcome.Applied) return restored
        }
        if (!enabled) {
            if (
                !state.themeAwareJoystickLightRequested &&
                !utilityPreferences.isThemeAwareJoystickLightRestorePending()
            ) {
                return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
            }
            val manual = utilityPreferences.readManualJoystickColor()
            if (manual == null) {
                utilityPreferences.setThemeAwareJoystickLight(false)
                utilityPreferences.setThemeAwareJoystickLightRestorePending(false)
                state = state.copy(
                    themeAwareJoystickLight = false,
                    themeAwareJoystickLightRequested = false,
                    themeAwareJoystickLightRestorePending = false,
                )
                return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
            }
            utilityPreferences.beginThemeAwareJoystickLightRestore(manual)
            restoreJoystickBrightnessAfterThemeLight()
            state = state.copy(
                themeAwareJoystickLight = false,
                themeAwareJoystickLightRequested = false,
                themeAwareJoystickLightRestorePending = true,
            )
            if (!state.capabilities.canAdjustJoystickColor) {
                return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
            }
            return restorePendingThemeColor()
        }

        if (!state.capabilities.canAdjustJoystickColor) return BrightnessUpdateOutcome.Unsupported(state)
        if (state.themeAwareJoystickLightRequested && state.themeAwareJoystickLight) {
            return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
        }
        val original = privilegedUtility.readVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
            ?.takeIf { JoystickColorCodec.parse(it) != null }
            ?: return refreshAfterPrivilegedFailure()
        utilityPreferences.beginThemeAwareJoystickLightRestore(original)
        state = state.copy(themeAwareJoystickLightRestorePending = true)
        raiseJoystickBrightnessForThemeLight()
        val applied = writeJoystickColorArgb(themeColorArgb(), updateManualColor = false)
        if (applied !is BrightnessUpdateOutcome.Applied) {
            // A verified rollback means no recovery obligation remains. An unverifiable rollback
            // keeps the durable pending marker so a later, explicitly reconnected session retries.
            if (
                state.capabilities.privilegedUtilityPosture.status ==
                PrivilegedUtilityStatus.READY
            ) {
                utilityPreferences.setThemeAwareJoystickLightRestorePending(false)
                state = state.copy(themeAwareJoystickLightRestorePending = false)
            }
            return BrightnessUpdateOutcome.WriteFailed(state)
        }
        utilityPreferences.commitThemeAwareJoystickLightEnabled()
        state = state.copy(
            themeAwareJoystickLight = true,
            themeAwareJoystickLightRequested = true,
            themeAwareJoystickLightRestorePending = false,
        )
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = true)
    }

    /** Never prompts in a theme observer; unavailable vendor access makes follow ineffective. */
    fun applyThemeColor(argb: Int): BrightnessUpdateOutcome {
        if (utilityPreferences.isThemeAwareJoystickLightRestorePending()) {
            if (!state.capabilities.canAdjustJoystickColor) {
                state = state.copy(
                    themeAwareJoystickLight = false,
                    themeAwareJoystickLightRequested = false,
                    themeAwareJoystickLightRestorePending = true,
                )
                return BrightnessUpdateOutcome.Unsupported(state)
            }
            return restorePendingThemeColor()
        }
        val requested = utilityPreferences.isThemeAwareJoystickLight()
        if (!requested) {
            if (state.themeAwareJoystickLight || state.themeAwareJoystickLightRequested) {
                state = state.copy(
                    themeAwareJoystickLight = false,
                    themeAwareJoystickLightRequested = false,
                    themeAwareJoystickLightRestorePending = false,
                )
            }
            return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
        }
        if (!state.capabilities.canAdjustJoystickColor) {
            state = state.copy(
                themeAwareJoystickLight = false,
                themeAwareJoystickLightRequested = true,
            )
            return BrightnessUpdateOutcome.Unsupported(state)
        }
        val outcome = writeJoystickColorArgb(argb, updateManualColor = false)
        if (outcome is BrightnessUpdateOutcome.Applied) {
            state = state.copy(
                themeAwareJoystickLight = true,
                themeAwareJoystickLightRequested = true,
            )
            return outcome.copy(state = state)
        }
        return outcome
    }

    /**
     * Nova joystick-light brightness behavior, 2026-08-29.
     *
     * The sticks were `joystick_light_enabled=1,1` with a real colour and
     * `led_light_brightness_percent=0.0` — enabled, coloured, and physically dark. Mimir's theme light
     * only ever wrote COLOUR, so switching it on returned Applied and could not possibly show anything.
     * The technical requirement is that Mimir should own the brightness too.
     *
     * Only ever RAISES, and only from below the floor: a level he has set himself is his, and this must
     * not quietly turn his sticks up. The original is journalled so
     * [restoreJoystickBrightnessAfterThemeLight] can hand it back when the theme light is switched off.
     *
     * Best-effort by design — a failure here must not fail the colour write that is the feature's actual
     * job, and the idle-dim path keeps working either way because it journals independently.
     */
    private fun raiseJoystickBrightnessForThemeLight() {
        val percentKey = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        val current = privilegedUtility.readVendorSetting(percentKey) ?: return
        val fraction = BrightnessMath.joystickFraction(current) ?: return
        if (fraction >= ThemeAwareJoystickLightFloor) return
        if (!utilityPreferences.beginThemeAwareJoystickPercentRestore(current)) return
        val raised = BrightnessMath.joystickPercentValue(ThemeAwareJoystickLightFloor)
        if (!privilegedUtility.writeVendorSetting(percentKey, raised)) {
            utilityPreferences.clearThemeAwareJoystickPercentRestore()
        }
    }

    /** Give back a level this controller raised. A level Mimir never touched is never written. */
    private fun restoreJoystickBrightnessAfterThemeLight() {
        val original = utilityPreferences.readThemeAwareJoystickPercentRestore() ?: return
        val percentKey = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        if (privilegedUtility.writeVendorSetting(percentKey, original)) {
            utilityPreferences.clearThemeAwareJoystickPercentRestore()
        }
    }

    private fun restorePendingThemeColor(): BrightnessUpdateOutcome {
        val manual = utilityPreferences.readManualJoystickColor()
        if (manual == null) {
            utilityPreferences.setThemeAwareJoystickLight(false)
            utilityPreferences.setThemeAwareJoystickLightRestorePending(false)
            state = state.copy(
                themeAwareJoystickLight = false,
                themeAwareJoystickLightRequested = false,
                themeAwareJoystickLightRestorePending = false,
            )
            return BrightnessUpdateOutcome.WriteFailed(state)
        }
        val key = BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR
        val expected = CertifiedVendorValuePolicy.normalize(key, manual)
            ?: return refreshAfterPrivilegedFailure()
        privilegedUtility.writeVendorSetting(key, manual)
        val restored = privilegedUtility.readVendorSetting(key)?.let { readBack ->
            CertifiedVendorValuePolicy.normalize(key, readBack) == expected
        } == true
        if (!restored) return refreshAfterPrivilegedFailure()
        val restoredArgb = JoystickColorCodec.parse(manual)?.firstOrNull()
            ?: return refreshAfterPrivilegedFailure()
        utilityPreferences.setThemeAwareJoystickLight(false)
        utilityPreferences.setThemeAwareJoystickLightRestorePending(false)
        state = state.copy(
            joystickColorFraction = JoystickColorCodec.fractionForArgb(restoredArgb),
            joystickColorArgb = restoredArgb,
            themeAwareJoystickLight = false,
            themeAwareJoystickLightRequested = false,
            themeAwareJoystickLightRestorePending = false,
        )
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = true)
    }

    /** Existing resume hook restores effective follow only when the already-authorized bridge is ready. */
    fun reconcileThemeAwareJoystickLight(): BrightnessUpdateOutcome = applyThemeColor(themeColorArgb())

    private fun updatePrimary(fraction: Float): BrightnessUpdateOutcome {
        val canWrite = settings.canWrite()
        val manualMode = BrightnessMath.isManualBrightnessMode(
            settings.read(BrightnessSettingsKeys.PRIMARY_MODE),
        )
        if (canWrite && manualMode) {
            val key = BrightnessSettingsKeys.PRIMARY
            val original = settings.read(key)
            val value = BrightnessMath.systemLevel(fraction).toString()
            val expected = BrightnessMath.systemFraction(value)
            val written = settings.write(key, value)
            val readBack = BrightnessMath.systemFraction(settings.read(key))
            if (written && expected != null && readBack != null && abs(readBack - expected) <= 0.011f) {
                preferences.clear(BrightnessTarget.PRIMARY)
                windowSink.applyPrimary(null)
                state = state.copy(
                    capabilities = state.capabilities.copy(
                        canWriteSystemSettings = true,
                        mainSystemBrightnessActive = true,
                    ),
                    primaryFraction = fraction,
                    primaryWindowFallback = null,
                )
                return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = true)
            }
            original?.let { settings.write(key, it) }
        }

        preferences.write(BrightnessTarget.PRIMARY, fraction)
        windowSink.applyPrimary(fraction)
        state = state.copy(
            capabilities = state.capabilities.copy(
                canWriteSystemSettings = canWrite,
                mainSystemBrightnessActive = false,
            ),
            primaryFraction = fraction,
            primaryWindowFallback = fraction,
        )
        if (!manualMode) return BrightnessUpdateOutcome.Applied(state, windowApplied = true, systemApplied = false)
        if (!canWrite) return writeAccessRequired(windowApplied = true)
        return BrightnessUpdateOutcome.WriteFailed(state)
    }

    private fun updateSecondary(fraction: Float): BrightnessUpdateOutcome {
        if (!state.capabilities.showsSecondary) return BrightnessUpdateOutcome.Unsupported(state)

        val windowApplied = secondaryWindowAvailable()
        val vendorSystem = state.capabilities.secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM
        if (vendorSystem) {
            if (!state.capabilities.canAdjustSecondary) return BrightnessUpdateOutcome.Unsupported(state)
            // A wake can queue the idle restore while Utility is already visible: pay that debt first so
            // the player's new value cannot be undone by a restore landing after it (the joystick's rule).
            //
            // The restore's own Boolean return is not the signal to gate on: it is also false when there
            // is simply no debt owed (`?: return false` in [restoreIdleSecondarySystemBrightness]), which
            // would reject every ordinary slider request. What matters is whether debt is STILL owed after
            // the attempt -- a failed write leaves the journal in place, and this player's slider value
            // must never be written on top of a debt that can still land later and undo it.
            val idleRestoreDebtRemains = synchronized(idleJoystickIoLock) {
                restoreIdleSecondarySystemBrightness()
                utilityPreferences.readIdleSecondaryBrightnessRestore() != null
            }
            if (idleRestoreDebtRemains) return refreshAfterPrivilegedFailure()
            val displayId = secondaryDisplayId() ?: return BrightnessUpdateOutcome.Unsupported(state)
            val mirrorPercent = BrightnessMath.vendorPercentLevel(fraction)
            val written = privilegedUtility.setSecondaryBrightness(displayId, fraction, mirrorPercent)
            val actual = privilegedUtility.readDisplayBrightness(displayId)
                ?.takeIf { it.isFinite() && it in 0f..1f }
                ?.let(BrightnessMath::windowFraction)
            val mirror = BrightnessMath.vendorPercentFraction(
                privilegedUtility.readVendorSetting(BrightnessSettingsKeys.AYN_SECONDARY),
            )
            val expectedMirror = BrightnessMath.vendorPercentFraction(mirrorPercent.toString())
            if (
                !written ||
                actual == null ||
                abs(actual - fraction) > 0.011f ||
                mirror == null ||
                expectedMirror == null ||
                abs(mirror - expectedMirror) > 0.001f
            ) {
                return refreshAfterPrivilegedFailure()
            }
            preferences.clear(BrightnessTarget.SECONDARY)
            windowSink.applySecondary(null)
            state = state.copy(
                secondaryFraction = actual,
                secondaryWindowFallback = null,
            )
            return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = true)
        }

        commitSecondaryWindow(fraction, windowApplied)
        return BrightnessUpdateOutcome.Applied(state, windowApplied = windowApplied, systemApplied = false)
    }

    private fun commitSecondaryWindow(fraction: Float, windowApplied: Boolean) {
        preferences.write(BrightnessTarget.SECONDARY, fraction)
        if (windowApplied) windowSink.applySecondary(fraction)
        state = state.copy(
            capabilities = state.capabilities.copy(
                canWriteSystemSettings = settings.canWrite(),
                secondaryWindowAvailable = windowApplied,
            ),
            secondaryFraction = fraction,
            secondaryWindowFallback = fraction,
        )
    }

    private fun updateJoystickLight(fraction: Float): BrightnessUpdateOutcome =
        synchronized(idleJoystickIoLock) { updateJoystickLightTransaction(fraction) }

    private fun updateJoystickLightTransaction(fraction: Float): BrightnessUpdateOutcome {
        if (utilityPreferences.readIdleJoystickLightRestore() != null &&
            !restoreIdleJoystickLightForCurrentRequest(allowDimRequest = true)
        ) {
            return BrightnessUpdateOutcome.WriteFailed(state)
        }
        if (!state.capabilities.canAdjustJoystickLight) return BrightnessUpdateOutcome.Unsupported(state)
        val percentKey = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        val enabledKey = BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED
        val originalPercent = privilegedUtility.readVendorSetting(percentKey)
            ?.takeIf { BrightnessMath.joystickFraction(it) != null }
            ?: return refreshAfterPrivilegedFailure()
        val originalEnabled = privilegedUtility.readVendorSetting(enabledKey)
            ?.takeIf { BrightnessMath.joystickEnabled(it) != null }
            ?: return refreshAfterPrivilegedFailure()
        val enabled = fraction > 0f
        val percentValue = BrightnessMath.joystickPercentValue(fraction)
        val enabledValue = BrightnessMath.joystickEnabledValue(originalEnabled, enabled)
        val percentWritten = privilegedUtility.writeVendorSetting(percentKey, percentValue)
        val enabledWritten = percentWritten && privilegedUtility.writeVendorSetting(enabledKey, enabledValue)
        val percentReadBack = privilegedUtility.readVendorSetting(percentKey)
        val enabledReadBack = privilegedUtility.readVendorSetting(enabledKey)
        val verified = enabledWritten &&
            BrightnessMath.joystickFraction(percentReadBack)?.let { abs(it - fraction) <= 0.001f } == true &&
            BrightnessMath.joystickEnabledMatches(enabledReadBack, enabled)
        if (!verified) {
            privilegedUtility.writeVendorSetting(percentKey, originalPercent)
            privilegedUtility.writeVendorSetting(enabledKey, originalEnabled)
            val rollbackPercent = privilegedUtility.readVendorSetting(percentKey)
            val rollbackEnabled = privilegedUtility.readVendorSetting(enabledKey)
            val rollbackVerified = vendorValueMatches(percentKey, rollbackPercent, originalPercent) &&
                vendorValueMatches(enabledKey, rollbackEnabled, originalEnabled)
            return refreshAfterPrivilegedFailure(forceUnverifiableState = !rollbackVerified)
        }
        state = state.copy(joystickLightFraction = if (enabled) fraction else 0f)
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = true)
    }

    private fun updateJoystickColor(fraction: Float): BrightnessUpdateOutcome {
        if (!state.capabilities.canAdjustJoystickColor || state.themeAwareJoystickLightRequested) {
            return BrightnessUpdateOutcome.Unsupported(state)
        }
        return writeJoystickColorArgb(
            JoystickColorCodec.argbForSelection(fraction),
            updateManualColor = true,
        )
    }

    private fun writeJoystickColorArgb(
        argb: Int,
        updateManualColor: Boolean,
    ): BrightnessUpdateOutcome {
        val key = BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR
        val original = privilegedUtility.readVendorSetting(key)
            ?.takeIf { JoystickColorCodec.parse(it) != null }
            ?: return refreshAfterPrivilegedFailure()
        val value = JoystickColorCodec.value(original, argb)
        if (JoystickColorCodec.matches(original, argb)) {
            if (updateManualColor) utilityPreferences.writeManualJoystickColor(value)
            val currentArgb = JoystickColorCodec.parse(original)?.firstOrNull()
                ?: return refreshAfterPrivilegedFailure()
            state = state.copy(
                joystickColorFraction = JoystickColorCodec.fractionForArgb(currentArgb),
                joystickColorArgb = currentArgb,
            )
            return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
        }
        val written = privilegedUtility.writeVendorSetting(key, value)
        val readBack = privilegedUtility.readVendorSetting(key)
        if (!written || !JoystickColorCodec.matches(readBack, argb)) {
            privilegedUtility.writeVendorSetting(key, original)
            val rollbackReadBack = privilegedUtility.readVendorSetting(key)
            return refreshAfterPrivilegedFailure(
                forceUnverifiableState = !vendorValueMatches(key, rollbackReadBack, original),
            )
        }
        if (updateManualColor) utilityPreferences.writeManualJoystickColor(value)
        val actualArgb = JoystickColorCodec.parse(readBack)?.firstOrNull()
            ?: return refreshAfterPrivilegedFailure()
        state = state.copy(
            joystickColorFraction = JoystickColorCodec.fractionForArgb(actualArgb),
            joystickColorArgb = actualArgb,
        )
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = true)
    }

    private fun updateMimirHaptic(fraction: Float): BrightnessUpdateOutcome {
        if (!state.capabilities.mimirHapticsSupported) return BrightnessUpdateOutcome.Unsupported(state)
        val requested = if (state.capabilities.mimirHapticAmplitudeControl) {
            fraction
        } else if (fraction <= 0f) {
            0f
        } else {
            1f
        }
        if (!haptics.writeFraction(requested)) return BrightnessUpdateOutcome.WriteFailed(state)
        val actual = haptics.readFraction() ?: requested
        state = state.copy(mimirHapticFraction = BrightnessMath.utilityFraction(actual))
        haptics.preview()
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
    }

    /** BM03: navigation-cue loudness — a Mimir preference write, previewed audibly (haptic parity). */
    private fun updateNavSound(fraction: Float): BrightnessUpdateOutcome {
        if (!state.capabilities.navSoundSupported) return BrightnessUpdateOutcome.Unsupported(state)
        if (!navSound.writeFraction(fraction)) return BrightnessUpdateOutcome.WriteFailed(state)
        val actual = navSound.readFraction() ?: fraction
        state = state.copy(navSoundFraction = BrightnessMath.utilityFraction(actual))
        navSound.preview()
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
    }

    private fun updateAmbientSound(fraction: Float): BrightnessUpdateOutcome {
        if (!state.capabilities.ambientSoundSupported) return BrightnessUpdateOutcome.Unsupported(state)
        if (!ambientSound.writeFraction(fraction)) return BrightnessUpdateOutcome.WriteFailed(state)
        val actual = ambientSound.readFraction() ?: fraction
        state = state.copy(ambientSoundFraction = BrightnessMath.utilityFraction(actual))
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = false)
    }

    private fun updateMediaVolume(fraction: Float): BrightnessUpdateOutcome {
        if (!state.capabilities.canAdjustMediaVolume) return BrightnessUpdateOutcome.Unsupported(state)
        if (!mediaVolume.writeFraction(fraction)) return BrightnessUpdateOutcome.WriteFailed(state)
        val actual = mediaVolume.readFraction() ?: return BrightnessUpdateOutcome.WriteFailed(state)
        state = state.copy(mediaVolumeFraction = BrightnessMath.utilityFraction(actual))
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = true)
    }

    private fun stepMediaVolume(direction: Int): BrightnessUpdateOutcome {
        if (!state.capabilities.canAdjustMediaVolume) return BrightnessUpdateOutcome.Unsupported(state)
        val actual = mediaVolume.step(direction.coerceIn(-1, 1))
            ?: return BrightnessUpdateOutcome.WriteFailed(state)
        state = state.copy(mediaVolumeFraction = BrightnessMath.utilityFraction(actual))
        return BrightnessUpdateOutcome.Applied(state, windowApplied = false, systemApplied = true)
    }

    private fun writeAccessRequired(windowApplied: Boolean = false): BrightnessUpdateOutcome {
        state = state.copy(
            capabilities = state.capabilities.copy(
                canWriteSystemSettings = false,
                mainSystemBrightnessActive = false,
            ),
        )
        return BrightnessUpdateOutcome.WriteAccessRequired(
            state = state,
            windowApplied = windowApplied,
            request = settings.permissionRequest(),
        )
    }

    private fun loadState(probePrivileged: Boolean = true): BrightnessState {
        val vendor = BrightnessVendorDetector.detect(identity, settings)
        val rawPrivilegedPosture = if (
            vendor.secondarySetting || vendor.joystickLight || vendor.joystickColor
        ) {
            if (probePrivileged) {
                privilegedUtility.posture()
            } else {
                // Production construction happens on the Activity thread. Publish a passive state;
                // AppBrightnessCoordinator performs the first authoritative probe on Dispatchers.IO.
                PrivilegedUtilityPosture(PrivilegedUtilityStatus.BINDING)
            }
        } else {
            PrivilegedUtilityPosture.Unsupported
        }
        lastLoadedRawPrivilegedPosture = rawPrivilegedPosture
        val requiredVendorKeys = buildSet {
            if (vendor.secondarySetting) add(BrightnessSettingsKeys.AYN_SECONDARY)
            if (vendor.joystickLight) {
                add(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT)
                add(BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED)
            }
            if (vendor.joystickColor) add(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
        }
        val privilegedVendorValues = if (
            rawPrivilegedPosture.status == PrivilegedUtilityStatus.READY &&
            rawPrivilegedPosture.canWriteVendorSettings
        ) {
            requiredVendorKeys.associateWith(privilegedUtility::readVendorSetting)
        } else {
            emptyMap()
        }
        val privilegedVendorReadbackReady = requiredVendorKeys.isNotEmpty() &&
            privilegedVendorValues.size == requiredVendorKeys.size &&
            privilegedVendorValues.all { (key, value) -> isValidPrivilegedVendorValue(key, value) }
        consecutiveReadbackContractFailures = if (
            rawPrivilegedPosture.status == PrivilegedUtilityStatus.READY &&
            rawPrivilegedPosture.canWriteVendorSettings &&
            requiredVendorKeys.isNotEmpty()
        ) {
            if (privilegedVendorReadbackReady) 0 else consecutiveReadbackContractFailures + 1
        } else {
            0
        }
        val hasSecondaryWindow = secondaryWindowAvailable()
        val liveSecondaryDisplayId = secondaryDisplayId()
        lastLoadedSecondaryWindowAvailable = hasSecondaryWindow
        lastLoadedSecondaryDisplayId = liveSecondaryDisplayId
        val physicalSecondary = if (
            rawPrivilegedPosture.status == PrivilegedUtilityStatus.READY &&
            rawPrivilegedPosture.canControlSecondaryBrightness &&
            privilegedVendorReadbackReady
        ) {
            liveSecondaryDisplayId
                ?.let(privilegedUtility::readDisplayBrightness)
                ?.takeIf { it.isFinite() && it in 0f..1f }
                ?.let(BrightnessMath::windowFraction)
        } else {
            null
        }
        val privilegedSecondaryMirror = if (vendor.secondarySetting) {
            BrightnessMath.vendorPercentFraction(
                privilegedVendorValues[BrightnessSettingsKeys.AYN_SECONDARY],
            )
        } else {
            null
        }
        val secondaryAuthorityConsistent =
            vendor.secondarySetting &&
                hasSecondaryWindow &&
                physicalSecondary != null &&
                privilegedSecondaryMirror != null &&
                abs(physicalSecondary - privilegedSecondaryMirror) <= 0.011f
        val privilegedPosture = when {
            rawPrivilegedPosture.status != PrivilegedUtilityStatus.READY ->
                rawPrivilegedPosture.copy(
                    canWriteVendorSettings = false,
                    canControlSecondaryBrightness = false,
                )
            !privilegedVendorReadbackReady ->
                rawPrivilegedPosture.copy(
                    // Remote permissions exist, but the vendor state did not satisfy the typed
                    // readback contract. The first miss stays a retryable verification failure;
                    // a repeat through a healthy READY bridge is deterministic, so stop offering
                    // a retry that cannot succeed and say so honestly.
                    status = if (
                        consecutiveReadbackContractFailures >= READBACK_CONTRACT_FAILURE_LIMIT
                    ) {
                        PrivilegedUtilityStatus.READBACK_UNSATISFIABLE
                    } else {
                        PrivilegedUtilityStatus.CONNECTION_FAILED
                    },
                    canWriteVendorSettings = false,
                    canControlSecondaryBrightness = false,
                )
            else -> rawPrivilegedPosture.copy(
                canWriteVendorSettings = true,
                canControlSecondaryBrightness =
                    rawPrivilegedPosture.canControlSecondaryBrightness &&
                        secondaryAuthorityConsistent,
            )
        }
        fun vendorValue(key: String): String? = if (privilegedPosture.canWriteVendorSettings) {
            privilegedVendorValues[key]
        } else {
            settings.read(key)
        }
        val secondarySupport = when {
            vendor.secondarySetting -> SecondaryBrightnessSupport.AYN_SYSTEM
            hasSecondaryWindow -> SecondaryBrightnessSupport.WINDOW_ONLY
            else -> SecondaryBrightnessSupport.UNAVAILABLE
        }
        // Round-5 B7: while the app-idle dim still owes the player's level back, MAIN reports that owed level,
        // not the dim the panel is temporarily holding. The journal is only read here; restore and heal own it.
        val systemPrimary = BrightnessMath.systemFraction(utilityPreferences.readIdleSystemBrightnessRestore())
            ?: BrightnessMath.systemFraction(settings.read(BrightnessSettingsKeys.PRIMARY))
        val storedPrimary = preferences.read(BrightnessTarget.PRIMARY)
        val writeAccess = settings.canWrite()
        val manualBrightnessMode = BrightnessMath.isManualBrightnessMode(
            settings.read(BrightnessSettingsKeys.PRIMARY_MODE),
        )
        val mainSystemAvailable = writeAccess && manualBrightnessMode
        val mainSystemActive = mainSystemAvailable && storedPrimary == null
        val primary = storedPrimary ?: systemPrimary ?: 0.5f
        val storedSecondary = preferences.read(BrightnessTarget.SECONDARY)
        val secondaryWindowFallback = if (secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM) {
            // Pre-P0 builds could persist a Presentation override while showing the Thor vendor row.
            // Retire it so the read-only row and the physical secondary panel cannot diverge.
            if (storedSecondary != null) preferences.clear(BrightnessTarget.SECONDARY)
            null
        } else {
            storedSecondary
        }
        val secondary = if (secondarySupport == SecondaryBrightnessSupport.UNAVAILABLE) {
            null
        } else if (secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM) {
            // Round-5 B7, as for MAIN: an owed idle restore is the player's level, parsed as its restore parses
            // it. Only the REPORTED value takes it: secondaryAuthorityConsistent above keeps comparing the live
            // panel with its live mirror, because the idle restore needs that posture to run at all.
            utilityPreferences.readIdleSecondaryBrightnessRestore()
                ?.toFloatOrNull()
                ?.takeIf { it.isFinite() && it in 0f..1f }
                ?: physicalSecondary
                ?: BrightnessMath.vendorPercentFraction(vendorValue(BrightnessSettingsKeys.AYN_SECONDARY))
                ?: primary
        } else {
            secondaryWindowFallback ?: primary
        }
        val joystick = if (vendor.joystickLight) {
            val percent = BrightnessMath.joystickFraction(
                vendorValue(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT),
            ) ?: 0f
            if (BrightnessMath.joystickEnabled(
                    vendorValue(BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED),
                ) == true
            ) {
                percent
            } else {
                0f
            }
        } else {
            null
        }
        val joystickColorValue = if (vendor.joystickColor) {
            vendorValue(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)
        } else {
            null
        }
        val joystickArgb = if (vendor.joystickColor) {
            JoystickColorCodec.parse(joystickColorValue)?.firstOrNull()
        } else {
            null
        }
        val hapticAvailable = haptics.isAvailable()
        val hapticFraction = if (hapticAvailable) haptics.readFraction() else null
        val navSoundFraction = navSound.readFraction()
        val ambientSoundFraction = ambientSound.readFraction()
        val volumeAvailable = mediaVolume.isAvailable()
        val volumeFraction = if (volumeAvailable) mediaVolume.readFraction() else null
        val colorSupported = vendor.joystickColor && joystickArgb != null
        val themeFollowRequested = utilityPreferences.isThemeAwareJoystickLight()
        val themeRestorePending = utilityPreferences.isThemeAwareJoystickLightRestorePending()
        val effectiveThemeFollowRequest = themeFollowRequested && !themeRestorePending
        val themeFollowEffective = effectiveThemeFollowRequest &&
            colorSupported &&
            privilegedPosture.canWriteVendorSettings &&
            JoystickColorCodec.matches(joystickColorValue, themeColorArgb())
        return BrightnessState(
            capabilities = BrightnessCapabilities(
                canWriteSystemSettings = writeAccess,
                mainSystemBrightnessActive = mainSystemActive,
                secondaryWindowAvailable = hasSecondaryWindow,
                secondarySupport = secondarySupport,
                joystickLightSupported = vendor.joystickLight,
                joystickLightColorSupported = colorSupported,
                mimirHapticsSupported = hapticAvailable && hapticFraction != null,
                mimirHapticAmplitudeControl = hapticAvailable && haptics.hasAmplitudeControl(),
                mediaVolumeSupported = volumeAvailable && volumeFraction != null,
                mediaVolumeFixed = volumeAvailable && mediaVolume.isFixedVolume(),
                mainWriteAccessUseful = manualBrightnessMode,
                privilegedUtilityPosture = privilegedPosture,
                navSoundSupported = navSoundFraction != null,
                ambientSoundSupported = ambientSoundFraction != null,
            ),
            primaryFraction = BrightnessMath.windowFraction(primary),
            primaryWindowFallback = storedPrimary,
            secondaryFraction = secondary?.let(BrightnessMath::windowFraction),
            secondaryWindowFallback = secondaryWindowFallback,
            joystickLightFraction = joystick?.let(BrightnessMath::utilityFraction),
            joystickColorFraction = joystickArgb?.let(JoystickColorCodec::fractionForArgb),
            joystickColorArgb = joystickArgb,
            themeAwareJoystickLight = themeFollowEffective,
            mimirHapticFraction = hapticFraction?.let(BrightnessMath::utilityFraction),
            mediaVolumeFraction = volumeFraction?.let(BrightnessMath::utilityFraction),
            themeAwareJoystickLightRequested = effectiveThemeFollowRequest,
            themeAwareJoystickLightRestorePending = themeRestorePending,
            navSoundFraction = navSoundFraction?.let(BrightnessMath::utilityFraction),
            ambientSoundFraction = ambientSoundFraction?.let(BrightnessMath::utilityFraction),
            screenTimeoutSeconds = BrightnessMath.normalizeScreenTimeoutSeconds(
                utilityPreferences.readScreenTimeoutSeconds(),
            ),
        )
    }

    private fun refreshAfterPrivilegedFailure(
        forceUnverifiableState: Boolean = false,
    ): BrightnessUpdateOutcome {
        val refreshed = loadState()
        val unverifiable = forceUnverifiableState ||
            refreshed.capabilities.privilegedUtilityPosture.status ==
            PrivilegedUtilityStatus.CONNECTION_FAILED ||
            refreshed.capabilities.privilegedUtilityPosture.status ==
            PrivilegedUtilityStatus.READBACK_UNSATISFIABLE
        val capabilities = if (forceUnverifiableState) {
            refreshed.capabilities.copy(
                privilegedUtilityPosture = PrivilegedUtilityPosture(
                    status = PrivilegedUtilityStatus.CONNECTION_FAILED,
                    canWriteVendorSettings = false,
                    canControlSecondaryBrightness = false,
                ),
            )
        } else {
            refreshed.capabilities
        }
        state = refreshed.copy(
            capabilities = capabilities,
            themeAwareJoystickLight = if (forceUnverifiableState) {
                false
            } else {
                refreshed.themeAwareJoystickLight
            },
            privilegedFailureMessage = when {
                capabilities.privilegedUtilityPosture.status ==
                    PrivilegedUtilityStatus.READBACK_UNSATISFIABLE ->
                    "These controls can’t be verified on this device."
                unverifiable ->
                    "Mimir couldn’t confirm the change. Reconnect special access, then reopen Utility."
                else -> "Change was not applied."
            },
        )
        return BrightnessUpdateOutcome.WriteFailed(state)
    }

    private fun isValidPrivilegedVendorValue(key: String, value: String?): Boolean =
        value != null && CertifiedVendorValuePolicy.normalize(key, value) != null

    private fun vendorValueMatches(key: String, actual: String?, expected: String): Boolean {
        val expectedNormalized = CertifiedVendorValuePolicy.normalize(key, expected) ?: return false
        return actual?.let { CertifiedVendorValuePolicy.normalize(key, it) } == expectedNormalized
    }

    private fun BrightnessState.canAdjustTarget(target: BrightnessTarget): Boolean = when (target) {
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
        BrightnessTarget.EQ_GAIN -> false
    }

    private fun BrightnessTarget.isPrivilegedUtilityTarget(): Boolean =
        this == BrightnessTarget.JOYSTICK_LIGHT ||
            this == BrightnessTarget.JOYSTICK_COLOR ||
            (this == BrightnessTarget.SECONDARY &&
                state.capabilities.secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM)

    private companion object {
        /** First contract miss = retryable; the second identical miss through READY = deterministic. */
        const val READBACK_CONTRACT_FAILURE_LIMIT = 2
    }
}
