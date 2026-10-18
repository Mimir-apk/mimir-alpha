package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.isConsolePhysicalKeyCode
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutQuickLaunchRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutResetRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutRow
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutUtilityRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutTweakerRowId
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app destinations that may be assigned to an extra physical controller or handheld macro key. */
internal enum class ControllerShortcutAction(
    val preferenceKey: String,
    val title: String,
) {
    QUICK_LAUNCH("quick_launch", "Quick Launch"),
    UTILITY("utility", "Utility"),
    TWEAKER("tweaker", "Tweaker"),
}

/**
 * Stable key identity. [deviceId] is deliberately transient and is used only to swallow the matching
 * release edge; persisted identity uses descriptor/vendor/product plus the exact key and scan codes.
 */
internal data class ControllerPhysicalKey(
    val deviceId: Int,
    val descriptorHash: String,
    val vendorId: Int,
    val productId: Int,
    val keyCode: Int,
    val scanCode: Int,
) {
    val hasUsableCode: Boolean
        get() = keyCode != KeyEvent.KEYCODE_UNKNOWN || scanCode != 0

    val hasFallbackIdentity: Boolean
        get() = vendorId != 0 || productId != 0
}

internal data class ControllerShortcutBinding(
    val descriptorHash: String,
    val vendorId: Int,
    val productId: Int,
    val keyCode: Int,
    val scanCode: Int,
    val action: ControllerShortcutAction,
) {
    fun exactMatch(key: ControllerPhysicalKey): Boolean =
        descriptorHash == key.descriptorHash &&
            vendorId == key.vendorId &&
            productId == key.productId &&
            keyCode == key.keyCode &&
            scanCode == key.scanCode

    fun familyMatch(key: ControllerPhysicalKey): Boolean =
        key.hasFallbackIdentity &&
            vendorId == key.vendorId &&
            productId == key.productId &&
            keyCode == key.keyCode &&
            scanCode == key.scanCode

    fun rePin(key: ControllerPhysicalKey): ControllerShortcutBinding = copy(
        descriptorHash = key.descriptorHash,
        vendorId = key.vendorId,
        productId = key.productId,
    )
}

internal data class ControllerShortcutResolution(
    val action: ControllerShortcutAction,
    val rePinned: ControllerShortcutBinding? = null,
)

/** Pure one-key/one-action set used by persistence and hostile unit tests. */
internal data class ControllerShortcutBindings(
    val byAction: Map<ControllerShortcutAction, ControllerShortcutBinding> = emptyMap(),
) {
    fun assign(binding: ControllerShortcutBinding): ControllerShortcutBindings {
        val withoutConflicts = byAction.filterValues { existing ->
            existing.action != binding.action &&
                !samePhysicalKey(existing, binding)
        }
        return copy(byAction = withoutConflicts + (binding.action to binding))
    }

    fun remove(action: ControllerShortcutAction): ControllerShortcutBindings =
        copy(byAction = byAction - action)

    fun resolve(key: ControllerPhysicalKey): ControllerShortcutResolution? {
        if (!key.hasUsableCode || isReservedControllerShortcutKey(key.keyCode)) return null
        byAction.values.firstOrNull {
            isDispatchableControllerShortcutBinding(it) && it.exactMatch(key)
        }?.let {
            return ControllerShortcutResolution(it.action)
        }
        byAction.values.firstOrNull {
            isDispatchableControllerShortcutBinding(it) && it.familyMatch(key)
        }?.let {
            return ControllerShortcutResolution(it.action, it.rePin(key))
        }
        return null
    }

    private fun samePhysicalKey(
        first: ControllerShortcutBinding,
        second: ControllerShortcutBinding,
    ): Boolean =
        first.keyCode == second.keyCode &&
            first.scanCode == second.scanCode &&
            (
                (
                    first.descriptorHash == second.descriptorHash &&
                        first.vendorId == second.vendorId &&
                        first.productId == second.productId
                    ) ||
                    (
                        (first.vendorId != 0 || first.productId != 0) &&
                            first.vendorId == second.vendorId &&
                            first.productId == second.productId
                        )
                )
}

internal data class ControllerShortcutSnapshot(
    val bindings: ControllerShortcutBindings = ControllerShortcutBindings(),
    val pendingAction: ControllerShortcutAction? = null,
    val status: String? = null,
    val captureGeneration: Long = 0L,
)

internal data class ControllerShortcutCaptureResult(
    val consumed: Boolean,
    val message: String? = null,
)

internal fun ControllerShortcutSnapshot.toSettingsRows(): List<ControllerShortcutRow> =
    ControllerShortcutAction.entries.map { action ->
        val binding = bindings.byAction[action]
        val pending = pendingAction == action
        ControllerShortcutRow(
            id = when (action) {
                ControllerShortcutAction.QUICK_LAUNCH -> ControllerShortcutQuickLaunchRowId
                ControllerShortcutAction.UTILITY -> ControllerShortcutUtilityRowId
                ControllerShortcutAction.TWEAKER -> ControllerShortcutTweakerRowId
            },
            title = action.title,
            supporting = if (pending) {
                status ?: "Press an extra controller button · B cancels"
            } else {
                when (action) {
                    ControllerShortcutAction.QUICK_LAUNCH ->
                        "Open Quick Launch from any Mimir screen."
                    ControllerShortcutAction.UTILITY ->
                        "Open Utility from any Mimir screen."
                    ControllerShortcutAction.TWEAKER ->
                        "Open Tweaker from any Mimir screen."
                }
            },
            value = when {
                pending -> "Listening"
                binding != null -> controllerShortcutKeyLabel(binding.keyCode, binding.scanCode)
                else -> "Not set"
            },
            capturing = pending,
        )
    } + if (bindings.byAction.isNotEmpty()) {
        listOf(
            ControllerShortcutRow(
                id = ControllerShortcutResetRowId,
                title = "Reset controller shortcuts",
                supporting = status ?: "Clear optional Mimir tool shortcuts.",
                value = "Reset",
            ),
        )
    } else {
        emptyList()
    }

/**
 * Preference-backed shortcut authority. Room and FocusAuthority stay untouched; this owns only two
 * optional, in-app macro destinations and revalidates every stored binding again at dispatch.
 */
internal object ControllerShortcutStore {
    private const val PreferencesName = "mimir_controller_shortcuts"
    private const val BindingPrefix = "binding_"
    private const val RetiredSettingsBindingKey = "${BindingPrefix}settings"
    private const val CodecVersion = "v1"

    private val snapshotMutable = MutableStateFlow(ControllerShortcutSnapshot())
    private var preferences: SharedPreferences? = null

    val snapshot: StateFlow<ControllerShortcutSnapshot> = snapshotMutable.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        preferences = prefs
        var loaded = ControllerShortcutBindings()
        val editor = prefs.edit()
        var pruned = false
        if (prefs.contains(RetiredSettingsBindingKey)) {
            editor.remove(RetiredSettingsBindingKey)
            pruned = true
        }
        ControllerShortcutAction.entries.forEach { action ->
            val key = BindingPrefix + action.preferenceKey
            val binding = prefs.getString(key, null)
                ?.let { decodeControllerShortcutBinding(it, action) }
                ?.takeIf(::isDispatchableControllerShortcutBinding)
            if (binding == null) {
                if (prefs.contains(key)) {
                    editor.remove(key)
                    pruned = true
                }
            } else {
                loaded = loaded.assign(binding)
            }
        }
        if (pruned) editor.apply()
        snapshotMutable.value = snapshotMutable.value.copy(bindings = loaded)
    }

    @Synchronized
    fun beginCapture(action: ControllerShortcutAction): Long {
        val generation = snapshotMutable.value.captureGeneration + 1L
        snapshotMutable.value = snapshotMutable.value.copy(
            pendingAction = action,
            status = "Press an extra controller button · B cancels",
            captureGeneration = generation,
        )
        return generation
    }

    @Synchronized
    fun cancelCapture(message: String = "Controller shortcut capture cancelled"): Boolean {
        if (snapshotMutable.value.pendingAction == null) return false
        snapshotMutable.value = snapshotMutable.value.copy(
            pendingAction = null,
            status = message,
        )
        return true
    }

    @Synchronized
    fun timeoutCapture(generation: Long): Boolean {
        val current = snapshotMutable.value
        if (current.pendingAction == null || current.captureGeneration != generation) return false
        snapshotMutable.value = current.copy(
            pendingAction = null,
            status = "Controller shortcut capture timed out",
        )
        return true
    }

    @Synchronized
    fun capture(context: Context, key: ControllerPhysicalKey): ControllerShortcutCaptureResult {
        initialize(context)
        val action = snapshotMutable.value.pendingAction
            ?: return ControllerShortcutCaptureResult(consumed = false)
        if (isControllerShortcutCancelKey(key.keyCode)) {
            cancelCapture()
            return ControllerShortcutCaptureResult(true, "Controller shortcut capture cancelled")
        }
        if (!key.hasUsableCode) {
            cancelCapture("That controller event has no stable key identity")
            return ControllerShortcutCaptureResult(true, "That controller event can’t be mapped")
        }
        if (isReservedControllerShortcutKey(key.keyCode)) {
            cancelCapture("Use Controller Mapping below for console controls")
            return ControllerShortcutCaptureResult(true, "Use Controller Mapping for that button")
        }

        val binding = ControllerShortcutBinding(
            descriptorHash = key.descriptorHash,
            vendorId = key.vendorId,
            productId = key.productId,
            keyCode = key.keyCode,
            scanCode = key.scanCode,
            action = action,
        )
        val updated = snapshotMutable.value.bindings.assign(binding)
        persistAll(updated)
        val label = controllerShortcutKeyLabel(binding.keyCode, binding.scanCode)
        snapshotMutable.value = snapshotMutable.value.copy(
            bindings = updated,
            pendingAction = null,
            status = "${action.title} mapped to $label",
        )
        return ControllerShortcutCaptureResult(true, "${action.title} mapped")
    }

    @Synchronized
    fun resolve(context: Context, key: ControllerPhysicalKey): ControllerShortcutAction? {
        initialize(context)
        if (!key.hasUsableCode || isReservedControllerShortcutKey(key.keyCode)) {
            pruneNonDispatchable()
            return null
        }
        val resolution = snapshotMutable.value.bindings.resolve(key) ?: return null
        resolution.rePinned?.let { rePinned ->
            val updated = snapshotMutable.value.bindings.assign(rePinned)
            persistAll(updated)
            snapshotMutable.value = snapshotMutable.value.copy(
                bindings = updated,
                status = "${resolution.action.title} re-linked to this controller",
            )
        }
        return resolution.action
    }

    @Synchronized
    fun reset(context: Context) {
        initialize(context)
        preferences?.edit()?.let { editor ->
            ControllerShortcutAction.entries.forEach { action ->
                editor.remove(BindingPrefix + action.preferenceKey)
            }
            editor.remove(RetiredSettingsBindingKey)
            editor.apply()
        }
        snapshotMutable.value = ControllerShortcutSnapshot(
            captureGeneration = snapshotMutable.value.captureGeneration + 1L,
            status = "Controller shortcuts reset",
        )
    }

    /** Retire one legacy shortcut after the same destination is saved in the full mapping authority. */
    @Synchronized
    fun remove(context: Context, action: ControllerShortcutAction) {
        initialize(context)
        val updated = snapshotMutable.value.bindings.remove(action)
        if (updated == snapshotMutable.value.bindings) return
        persistAll(updated)
        snapshotMutable.value = snapshotMutable.value.copy(bindings = updated)
    }

    @Synchronized
    private fun pruneNonDispatchable() {
        val current = snapshotMutable.value.bindings
        val retained = current.byAction.values
            .filter(::isDispatchableControllerShortcutBinding)
            .fold(ControllerShortcutBindings()) { result, binding -> result.assign(binding) }
        if (retained == current) return
        persistAll(retained)
        snapshotMutable.value = snapshotMutable.value.copy(bindings = retained)
    }

    private fun persistAll(bindings: ControllerShortcutBindings) {
        preferences?.edit()?.let { editor ->
            editor.remove(RetiredSettingsBindingKey)
            ControllerShortcutAction.entries.forEach { action ->
                val preferenceKey = BindingPrefix + action.preferenceKey
                val binding = bindings.byAction[action]
                if (binding == null) {
                    editor.remove(preferenceKey)
                } else {
                    editor.putString(preferenceKey, encodeControllerShortcutBinding(binding))
                }
            }
            editor.apply()
        }
    }

    internal fun encodeControllerShortcutBinding(binding: ControllerShortcutBinding): String =
        listOf(
            CodecVersion,
            binding.descriptorHash,
            binding.vendorId,
            binding.productId,
            binding.keyCode,
            binding.scanCode,
        ).joinToString("|")

    internal fun decodeControllerShortcutBinding(
        encoded: String,
        action: ControllerShortcutAction,
    ): ControllerShortcutBinding? {
        val parts = encoded.split("|")
        if (parts.size != 6 || parts[0] != CodecVersion) return null
        val descriptor = parts[1].takeIf { it.length == 64 && it.all(Char::isLowerCaseHexDigit) }
            ?: return null
        return ControllerShortcutBinding(
            descriptorHash = descriptor,
            vendorId = parts[2].toIntOrNull() ?: return null,
            productId = parts[3].toIntOrNull() ?: return null,
            keyCode = parts[4].toIntOrNull() ?: return null,
            scanCode = parts[5].toIntOrNull() ?: return null,
            action = action,
        )
    }
}

internal fun controllerPhysicalKey(event: KeyEvent): ControllerPhysicalKey? {
    if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return null
    if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return null
    val device = event.device ?: InputDevice.getDevice(event.deviceId) ?: return null
    if (device.isVirtual) return null
    val descriptor = device.descriptor?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return ControllerPhysicalKey(
        deviceId = event.deviceId,
        descriptorHash = sha256LowerHex(descriptor),
        vendorId = device.vendorId,
        productId = device.productId,
        keyCode = event.keyCode,
        scanCode = event.scanCode,
    )
}

internal fun isDispatchableControllerShortcutBinding(binding: ControllerShortcutBinding): Boolean =
    binding.descriptorHash.length == 64 &&
        binding.descriptorHash.all(Char::isLowerCaseHexDigit) &&
        (binding.keyCode != KeyEvent.KEYCODE_UNKNOWN || binding.scanCode != 0) &&
        !isReservedControllerShortcutKey(binding.keyCode)

internal fun isControllerShortcutCancelKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B

/** Console and Android system controls stay outside the optional macro-shortcut layer. */
internal fun isReservedControllerShortcutKey(keyCode: Int): Boolean =
    isConsolePhysicalKeyCode(keyCode) || keyCode in ReservedControllerShortcutKeyCodes

private val ReservedControllerShortcutKeyCodes = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP_LEFT,
    KeyEvent.KEYCODE_DPAD_UP_RIGHT,
    KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
    KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_SPACE,
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_APP_SWITCH,
    KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_SLEEP,
    KeyEvent.KEYCODE_WAKEUP,
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_C,
    KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_Z,
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L2,
    KeyEvent.KEYCODE_BUTTON_R2,
    KeyEvent.KEYCODE_BUTTON_THUMBL,
    KeyEvent.KEYCODE_BUTTON_THUMBR,
    KeyEvent.KEYCODE_BUTTON_START,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_BUTTON_MODE,
)

internal sealed interface ControllerShortcutInputDecision {
    data object Continue : ControllerShortcutInputDecision
    data object Consume : ControllerShortcutInputDecision
    data class Dispatch(val action: ControllerShortcutAction) : ControllerShortcutInputDecision
}

private data class ControllerShortcutPress(
    val deviceId: Int,
    val keyCode: Int,
    val scanCode: Int,
)

/**
 * One bounded post-capture barrier shared by key and motion routing. A vendor macro may emit more
 * than the key that completed capture (another key, a HAT edge, or a trigger tail), so quarantine
 * follows every observed key through UP and every observed axis through neutral. A short quiet
 * window admits bundle tails that arrive just after the captured release; the hard deadline keeps a
 * missing UP/neutral event from trapping app input indefinitely.
 */
internal class ControllerShortcutCaptureQuarantine(
    private val clock: () -> Long,
) {
    private val held = mutableSetOf<ControllerShortcutPress>()
    private var armed = false
    private var hardDeadlineMs = 0L
    private var quietDeadlineMs = 0L
    private var captureMotionNonNeutral = false
    private var awaitingMotionNeutral = false

    fun observeCaptureMotion(samples: List<Map<Int, Float>>) {
        samples.lastOrNull()?.let { captureMotionNonNeutral = !controllerMotionIsAtRest(it) }
    }

    fun arm(initialKey: ControllerPhysicalKey?) {
        val now = clock()
        armed = true
        hardDeadlineMs = deadlineAfter(now, HardTimeoutMs)
        quietDeadlineMs = deadlineAfter(now, QuietTailMs)
        awaitingMotionNeutral = captureMotionNonNeutral
        initialKey?.let { held += it.toShortcutPress() }
    }

    fun consumeKey(key: ControllerPhysicalKey?, down: Boolean): Boolean {
        val now = clock()
        if (!isActive(now)) return false
        key?.toShortcutPress()?.let { press ->
            if (down) held += press else held -= press
        }
        quietDeadlineMs = minOf(hardDeadlineMs, deadlineAfter(now, QuietTailMs))
        return true
    }

    fun consumeMotion(samples: List<Map<Int, Float>>): Boolean {
        val now = clock()
        if (!isActive(now)) return false
        samples.lastOrNull()?.let { axes ->
            awaitingMotionNeutral = !controllerMotionIsAtRest(axes)
            quietDeadlineMs = minOf(hardDeadlineMs, deadlineAfter(now, QuietTailMs))
        }
        return true
    }

    fun reset() {
        held.clear()
        armed = false
        hardDeadlineMs = 0L
        quietDeadlineMs = 0L
        captureMotionNonNeutral = false
        awaitingMotionNeutral = false
    }

    private fun isActive(now: Long): Boolean {
        if (!armed || now >= hardDeadlineMs) {
            reset()
            return false
        }
        if (held.isEmpty() && !awaitingMotionNeutral && now > quietDeadlineMs) {
            reset()
            return false
        }
        return true
    }

    private fun ControllerPhysicalKey.toShortcutPress() = ControllerShortcutPress(
        deviceId = deviceId,
        keyCode = keyCode,
        scanCode = scanCode,
    )

    private fun deadlineAfter(now: Long, durationMs: Long): Long =
        if (Long.MAX_VALUE - now < durationMs) Long.MAX_VALUE else now + durationMs

    private companion object {
        const val QuietTailMs = 120L
        const val HardTimeoutMs = 2_000L
    }
}

// The shortcut quarantine used to carry its own verbatim copy of the rest test, with the same two
// defects the mapping gate's copy had: one threshold used in both directions (so no latch at all) and an
// unmentioned axis counted as a centred one. It now shares [controllerMotionIsAtRest], which holds the
// two-level latch and the allocation-free loops.

/**
 * Router-owned capture/dispatch gate. Capture may disappear on the DOWN frame, but the shared
 * quarantine keeps the complete vendor bundle isolated from the Settings surface beneath it.
 */
internal class ControllerShortcutInputGate(
    private val clock: () -> Long,
    private val captureQuarantine: ControllerShortcutCaptureQuarantine =
        ControllerShortcutCaptureQuarantine(clock),
) {
    private val pendingReleases = mutableMapOf<ControllerShortcutPress, Long>()

    fun handle(
        key: ControllerPhysicalKey?,
        down: Boolean,
        repeatCount: Int,
        longPress: Boolean,
        navigationMode: Boolean,
        captureActive: Boolean,
        capture: (ControllerPhysicalKey) -> Boolean,
        resolve: (ControllerPhysicalKey) -> ControllerShortcutAction?,
    ): ControllerShortcutInputDecision {
        val now = clock()
        pendingReleases.entries.removeAll { (_, deadline) -> now >= deadline }
        if (!captureActive && captureQuarantine.consumeKey(key, down)) {
            return ControllerShortcutInputDecision.Consume
        }
        if (captureActive) {
            if (!down || repeatCount > 0 || longPress || key == null) {
                return ControllerShortcutInputDecision.Consume
            }
            if (capture(key)) captureQuarantine.arm(key)
            // Even a rejected/unmappable event belongs to the focusless listening harness.
            return ControllerShortcutInputDecision.Consume
        }
        if (key == null) return ControllerShortcutInputDecision.Continue
        val press = ControllerShortcutPress(key.deviceId, key.keyCode, key.scanCode)
        if (!down) {
            return if (pendingReleases.remove(press) != null) {
                ControllerShortcutInputDecision.Consume
            } else {
                ControllerShortcutInputDecision.Continue
            }
        }
        if (press in pendingReleases) return ControllerShortcutInputDecision.Consume
        if (!navigationMode || repeatCount > 0 || longPress) {
            return ControllerShortcutInputDecision.Continue
        }
        val action = resolve(key) ?: return ControllerShortcutInputDecision.Continue
        pendingReleases[press] = now + PendingReleaseTimeoutMs
        return ControllerShortcutInputDecision.Dispatch(action)
    }

    fun reset() {
        pendingReleases.clear()
        captureQuarantine.reset()
    }

    private companion object {
        const val PendingReleaseTimeoutMs = 2_000L
    }
}

internal fun controllerShortcutKeyLabel(keyCode: Int, scanCode: Int): String {
    val keyLabel = KeyEvent.keyCodeToString(keyCode)
        .removePrefix("KEYCODE_")
        .replace('_', ' ')
    return if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
        "MACRO $scanCode"
    } else if (scanCode != 0) {
        "$keyLabel · $scanCode"
    } else {
        keyLabel
    }
}

private fun sha256LowerHex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun Char.isLowerCaseHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'
