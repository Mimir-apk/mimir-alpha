package com.gruning.mimir.rewrite

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.ControllerKeymap
import com.gruning.mimir.rewrite.core.input.PhysicalControl
import com.gruning.mimir.rewrite.core.input.defaultControllerKeymap
import com.gruning.mimir.rewrite.core.input.physicalControlForKeyCode
import com.gruning.mimir.rewrite.feature.settings.ControllerMappingRow
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutQuickLaunchRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutResetRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutRow
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutUtilityRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutTweakerRowId
import com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val RemappableControllerActions = listOf(
    ControllerAction.NAV_UP,
    ControllerAction.NAV_DOWN,
    ControllerAction.NAV_LEFT,
    ControllerAction.NAV_RIGHT,
    ControllerAction.CONFIRM,
    ControllerAction.CANCEL,
    ControllerAction.SECONDARY,
    ControllerAction.TERTIARY,
    ControllerAction.PAGE_LEFT,
    ControllerAction.PAGE_RIGHT,
    ControllerAction.TRIGGER_LEFT,
    ControllerAction.TRIGGER_RIGHT,
    ControllerAction.SYSTEM_START,
    ControllerAction.SYSTEM_SELECT,
    ControllerAction.SYSTEM_MENU,
    // OR-8: bound to L3 by default, so it sits with the bound buttons, ahead of the unbound global destinations.
    ControllerAction.SHOW_HINTS,
    ControllerAction.GLOBAL_QUICK_LAUNCH,
    ControllerAction.GLOBAL_UTILITY,
    ControllerAction.GLOBAL_TWEAKER,
    ControllerAction.GLOBAL_SETTINGS,
)

internal data class ControllerMappingSnapshot(
    val open: Boolean = false,
    val applied: ControllerKeymap = defaultControllerKeymap(),
    val draft: ControllerKeymap = defaultControllerKeymap(),
    val appliedRaw: ControllerRawBindings = ControllerRawBindings(),
    val draftRaw: ControllerRawBindings = ControllerRawBindings(),
    /** Optional Settings deep-link target; it selects a row but never starts capture by itself. */
    val initialFocusAction: ControllerAction? = null,
    val pendingAction: ControllerAction? = null,
    val exitConfirmationVisible: Boolean = false,
    val status: String? = null,
    val captureGeneration: Long = 0L,
) {
    val isDirty: Boolean
        get() = draft != applied || draftRaw != appliedRaw
}

/**
 * Stable identity for a key outside the fixed console map. This also represents each member of a
 * deliberate chord. Device ids are intentionally absent because Android reallocates them after reconnect.
 */
internal data class ControllerRawKey(
    val descriptorHash: String,
    val vendorId: Int,
    val productId: Int,
    val keyCode: Int,
    val scanCode: Int,
) {
    fun matches(key: ControllerPhysicalKey): Boolean {
        val sameCode = keyCode == key.keyCode && scanCode == key.scanCode
        if (!sameCode) return false
        if (
            descriptorHash == key.descriptorHash &&
            vendorId == key.vendorId &&
            productId == key.productId
        ) {
            return true
        }
        return (vendorId != 0 || productId != 0) &&
            vendorId == key.vendorId &&
            productId == key.productId
    }
}

internal data class ControllerRawBinding(
    val keys: List<ControllerRawKey>,
) {
    init {
        require(keys.size in 1..MaxControllerChordKeys)
        require(keys.distinct().size == keys.size)
    }

    val displayLabel: String
        get() = keys.joinToString(" + ") { key ->
            physicalControlForKeyCode(key.keyCode)?.displayLabel
                ?: controllerShortcutKeyLabel(key.keyCode, key.scanCode)
        }

    fun contains(key: ControllerPhysicalKey): Boolean = keys.any { it.matches(key) }

    fun matchesHeld(held: Collection<ControllerPhysicalKey>): Boolean {
        val unmatched = held.toMutableList()
        return keys.all { expected ->
            val index = unmatched.indexOfFirst(expected::matches)
            if (index < 0) {
                false
            } else {
                unmatched.removeAt(index)
                true
            }
        }
    }
}

internal data class ControllerRawBindings(
    val byAction: Map<ControllerAction, ControllerRawBinding> = emptyMap(),
) {
    fun assign(action: ControllerAction, binding: ControllerRawBinding): ControllerRawBindings {
        val withoutConflicts = byAction.filter { (existingAction, existingBinding) ->
            existingAction != action && existingBinding != binding
        }
        return copy(byAction = withoutConflicts + (action to binding))
    }

    fun remove(action: ControllerAction): ControllerRawBindings =
        copy(byAction = byAction - action)

    fun bindingFor(action: ControllerAction): ControllerRawBinding? = byAction[action]
}

private const val MaxControllerChordKeys = 4

internal sealed interface ControllerMappingApplyResult {
    data object Applied : ControllerMappingApplyResult
    data class MissingRequiredAction(val action: ControllerAction) : ControllerMappingApplyResult
}

/**
 * Global console-control map. Optional extra macro shortcuts remain in [ControllerShortcutStore];
 * this store owns only the controls consumed by Mimir's single InputAuthority.
 */
internal object ControllerMappingStore {
    private const val PreferencesName = "mimir_controller_keymap"
    private const val KeymapKey = "keymap_v1"
    private const val RawBindingsKey = "raw_bindings_v1"
    private const val CodecVersion = "v1"

    private val mutableSnapshot = MutableStateFlow(ControllerMappingSnapshot())
    private var context: Context? = null
    var onApplied: () -> Unit = {}

    val snapshot: StateFlow<ControllerMappingSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        // A stored string that this build cannot read is NOT erased. It used to be, and that turned every
        // decode failure into permanent data loss: the player lost their remap with nothing to restore,
        // and a later build that understood the string would never get to see it. Falling back in memory
        // costs nothing -- the next Apply overwrites it anyway.
        val restored = restoreControllerMapping(
            storedKeymap = preferences.getString(KeymapKey, null),
            storedRaw = preferences.getString(RawBindingsKey, null),
        )
        val applied = restored.keymap
        val appliedRaw = restored.rawBindings
        mutableSnapshot.value = ControllerMappingSnapshot(
            applied = applied,
            draft = applied,
            appliedRaw = appliedRaw,
            draftRaw = appliedRaw,
        )
    }

    fun appliedKeymap(): ControllerKeymap = mutableSnapshot.value.applied
    fun appliedRawBindings(): ControllerRawBindings = mutableSnapshot.value.appliedRaw

    @Synchronized
    fun open(initialFocusAction: ControllerAction? = null) {
        val current = mutableSnapshot.value
        mutableSnapshot.value = current.copy(
            open = true,
            draft = current.applied,
            draftRaw = current.appliedRaw,
            initialFocusAction = initialFocusAction?.takeIf(RemappableControllerActions::contains),
            pendingAction = null,
            exitConfirmationVisible = false,
            status = "Choose an action to remap",
            captureGeneration = current.captureGeneration + 1L,
        )
    }

    /** Returns true only when the mapping was clean and the overlay may close immediately. */
    @Synchronized
    fun requestDismiss(): Boolean {
        val current = mutableSnapshot.value
        if (!current.open) return true
        if (current.isDirty) {
            mutableSnapshot.value = current.copy(
                pendingAction = null,
                exitConfirmationVisible = true,
                captureGeneration = current.captureGeneration + 1L,
            )
            return false
        }
        dismiss()
        return true
    }

    @Synchronized
    fun dismiss() {
        val current = mutableSnapshot.value
        mutableSnapshot.value = current.copy(
            open = false,
            draft = current.applied,
            draftRaw = current.appliedRaw,
            initialFocusAction = null,
            pendingAction = null,
            exitConfirmationVisible = false,
            status = null,
            captureGeneration = current.captureGeneration + 1L,
        )
    }

    @Synchronized
    fun continueEditing() {
        val current = mutableSnapshot.value
        if (!current.open || !current.exitConfirmationVisible) return
        mutableSnapshot.value = current.copy(exitConfirmationVisible = false)
    }

    @Synchronized
    fun beginCapture(action: ControllerAction): Long {
        if (!mutableSnapshot.value.open || action !in RemappableControllerActions) return -1L
        val generation = mutableSnapshot.value.captureGeneration + 1L
        mutableSnapshot.value = mutableSnapshot.value.copy(
            pendingAction = action,
            exitConfirmationVisible = false,
            status = "Press one input, or hold a combination, then release",
            captureGeneration = generation,
        )
        return generation
    }

    @Synchronized
    fun capture(control: PhysicalControl): Boolean {
        val current = mutableSnapshot.value
        val action = current.pendingAction ?: return false
        val previousAction = current.draft[control]
        val updated = assignControllerAction(current.draft, action, control)
        val displaced = previousAction
            ?.takeIf { it != action }
            ?.let(::controllerActionTitle)
        mutableSnapshot.value = current.copy(
            draft = updated,
            draftRaw = current.draftRaw.remove(action),
            pendingAction = null,
            exitConfirmationVisible = false,
            status = buildString {
                append(controllerActionTitle(action))
                append(" mapped to ")
                append(control.displayLabel)
                if (displaced != null) append(" · $displaced is now unbound")
            },
        )
        return true
    }

    @Synchronized
    fun capture(keys: List<ControllerPhysicalKey>): Boolean {
        val current = mutableSnapshot.value
        val action = current.pendingAction ?: return false
        val binding = controllerRawBinding(keys) ?: return false
        if (binding.keys.size == 1) {
            physicalControlForKeyCode(binding.keys.single().keyCode)?.let { control ->
                return capture(control)
            }
        }
        val displaced = current.draftRaw.byAction.entries
            .firstOrNull { (mappedAction, existing) -> mappedAction != action && existing == binding }
            ?.key
            ?.let(::controllerActionTitle)
        val updatedKeymap = current.draft.mapValues { (_, mapped) ->
            mapped?.takeUnless { it == action }
        }
        mutableSnapshot.value = current.copy(
            draft = updatedKeymap,
            draftRaw = current.draftRaw.assign(action, binding),
            pendingAction = null,
            exitConfirmationVisible = false,
            status = buildString {
                append(controllerActionTitle(action))
                append(" mapped to ")
                append(binding.displayLabel)
                if (displaced != null) append(" · $displaced is now unbound")
            },
        )
        return true
    }

    @Synchronized
    fun timeoutCapture(generation: Long): Boolean {
        val current = mutableSnapshot.value
        if (current.pendingAction == null || current.captureGeneration != generation) return false
        mutableSnapshot.value = current.copy(
            pendingAction = null,
            exitConfirmationVisible = false,
            status = "No input received · mapping unchanged",
        )
        return true
    }

    @Synchronized
    fun cancelCapture(): Boolean {
        val current = mutableSnapshot.value
        if (current.pendingAction == null) return false
        mutableSnapshot.value = current.copy(
            pendingAction = null,
            exitConfirmationVisible = false,
            status = "Mapping unchanged",
            captureGeneration = current.captureGeneration + 1L,
        )
        return true
    }

    @Synchronized
    fun resetDraft() {
        if (!mutableSnapshot.value.open) return
        mutableSnapshot.value = mutableSnapshot.value.copy(
            draft = defaultControllerKeymap(),
            draftRaw = ControllerRawBindings(),
            pendingAction = null,
            exitConfirmationVisible = false,
            status = "Factory mapping ready · Apply to save",
            captureGeneration = mutableSnapshot.value.captureGeneration + 1L,
        )
    }

    @Synchronized
    fun apply(): ControllerMappingApplyResult {
        val current = mutableSnapshot.value
        val missing = missingRequiredControllerAction(current.draft, current.draftRaw)
        if (missing != null) {
            mutableSnapshot.value = current.copy(
                pendingAction = null,
                exitConfirmationVisible = false,
                status = "${controllerActionTitle(missing)} must be mapped before Apply",
            )
            return ControllerMappingApplyResult.MissingRequiredAction(missing)
        }
        val normalized = PhysicalControl.entries.associateWith { current.draft[it] }
        context?.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KeymapKey, encodeControllerKeymap(normalized))
            ?.putString(RawBindingsKey, encodeControllerRawBindings(current.draftRaw))
            ?.apply()
        mutableSnapshot.value = current.copy(
            open = false,
            applied = normalized,
            draft = normalized,
            appliedRaw = current.draftRaw,
            draftRaw = current.draftRaw,
            pendingAction = null,
            exitConfirmationVisible = false,
            status = null,
            captureGeneration = current.captureGeneration + 1L,
        )
        onApplied()
        return ControllerMappingApplyResult.Applied
    }

    /** Remove only the optional global destinations while preserving the console-control map. */
    @Synchronized
    fun clearGlobalShortcutBindings(): Boolean {
        val current = mutableSnapshot.value
        val shortcutActions = ControllerShortcutRowActions.values.toSet()
        val updatedKeymap = current.applied.mapValues { (_, action) ->
            action?.takeUnless(shortcutActions::contains)
        }
        val updatedRaw = shortcutActions.fold(current.appliedRaw) { bindings, action ->
            bindings.remove(action)
        }
        if (updatedKeymap == current.applied && updatedRaw == current.appliedRaw) return false
        context?.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KeymapKey, encodeControllerKeymap(updatedKeymap))
            ?.putString(RawBindingsKey, encodeControllerRawBindings(updatedRaw))
            ?.apply()
        mutableSnapshot.value = current.copy(
            applied = updatedKeymap,
            draft = updatedKeymap,
            appliedRaw = updatedRaw,
            draftRaw = updatedRaw,
            pendingAction = null,
            exitConfirmationVisible = false,
            status = null,
            captureGeneration = current.captureGeneration + 1L,
        )
        onApplied()
        return true
    }

    internal data class ControllerMappingRestore(
        val keymap: ControllerKeymap,
        val rawBindings: ControllerRawBindings,
    )

    internal fun restoreControllerMapping(
        storedKeymap: String?,
        storedRaw: String?,
    ): ControllerMappingRestore = ControllerMappingRestore(
        keymap = storedKeymap?.let(::decodeControllerKeymap) ?: defaultControllerKeymap(),
        rawBindings = storedRaw?.let(::decodeControllerRawBindings) ?: ControllerRawBindings(),
    )

    internal fun encodeControllerKeymap(keymap: ControllerKeymap): String =
        buildList {
            add(CodecVersion)
            PhysicalControl.entries.forEach { control ->
                add("${control.name}=${keymap[control]?.name.orEmpty()}")
            }
        }.joinToString("|")

    /**
     * Decode a stored keymap, tolerating a save that simply has no opinion about a control.
     *
     * This used to demand EXACTLY one entry per [PhysicalControl], which made the enum's size part of the
     * on-disk contract without anything saying so. Adding a control — an L3, an R3, a second menu button
     * on a new handheld — would have made every existing save undecodable, and `initialize` then deleted
     * it. One enum line would have factory-reset every player's remap, irrecoverably.
     *
     * A control the save never mentions takes its factory binding. A control the save mentions with an
     * empty action stays explicitly UNBOUND — that distinction is the whole reason the encoder writes
     * empty values, and it survives here.
     *
     * Genuinely unrecognisable input is still rejected: a wrong codec version, an entry without `=`, a
     * duplicate control, an unknown name, an action that is not remappable, or more entries than there
     * are controls. Rejection now falls back to defaults without erasing anything.
     */
    internal fun decodeControllerKeymap(encoded: String): ControllerKeymap? {
        val parts = encoded.split("|")
        if (parts.firstOrNull() != CodecVersion || parts.size > PhysicalControl.entries.size + 1) return null
        val decoded = mutableMapOf<PhysicalControl, ControllerAction?>()
        for (entry in parts.drop(1)) {
            val split = entry.split("=", limit = 2)
            if (split.size != 2) return null
            val control = PhysicalControl.entries.firstOrNull { it.name == split[0] } ?: return null
            if (control in decoded) return null
            val action = split[1].takeIf(String::isNotEmpty)?.let { encodedAction ->
                ControllerAction.entries.firstOrNull { it.name == encodedAction } ?: return null
            }
            if (action != null && action !in RemappableControllerActions) return null
            decoded[control] = action
        }
        val defaults = defaultControllerKeymap()
        return PhysicalControl.entries.associateWith { control ->
            if (control in decoded) decoded[control] else defaults[control]
        }
    }

    internal fun encodeControllerRawBindings(bindings: ControllerRawBindings): String =
        buildList {
            add(CodecVersion)
            bindings.byAction.toSortedMap(compareBy(ControllerAction::name)).forEach { (action, binding) ->
                val keys = binding.keys.joinToString("+") { key ->
                    listOf(
                        key.descriptorHash,
                        key.vendorId,
                        key.productId,
                        key.keyCode,
                        key.scanCode,
                    ).joinToString(",")
                }
                add("${action.name}=$keys")
            }
        }.joinToString("|")

    internal fun decodeControllerRawBindings(encoded: String): ControllerRawBindings? {
        val parts = encoded.split("|")
        if (parts.firstOrNull() != CodecVersion) return null
        var result = ControllerRawBindings()
        for (entry in parts.drop(1)) {
            val split = entry.split("=", limit = 2)
            if (split.size != 2) return null
            val action = ControllerAction.entries.firstOrNull { it.name == split[0] }
                ?.takeIf(RemappableControllerActions::contains)
                ?: return null
            if (result.byAction.containsKey(action)) return null
            val keys = split[1].split("+").map { encodedKey ->
                val fields = encodedKey.split(",")
                if (fields.size != 5) return null
                ControllerRawKey(
                    descriptorHash = fields[0],
                    vendorId = fields[1].toIntOrNull() ?: return null,
                    productId = fields[2].toIntOrNull() ?: return null,
                    keyCode = fields[3].toIntOrNull() ?: return null,
                    scanCode = fields[4].toIntOrNull() ?: return null,
                )
            }
            val binding = runCatching { ControllerRawBinding(keys) }.getOrNull()
                ?.takeIf(::isDispatchableControllerRawBinding)
                ?: return null
            result = result.assign(action, binding)
        }
        return result
    }
}

internal val RequiredReachabilityActions = listOf(
    ControllerAction.NAV_UP,
    ControllerAction.NAV_DOWN,
    ControllerAction.NAV_LEFT,
    ControllerAction.NAV_RIGHT,
    ControllerAction.CONFIRM,
    ControllerAction.CANCEL,
)

internal fun assignControllerAction(
    draft: ControllerKeymap,
    action: ControllerAction,
    control: PhysicalControl,
): ControllerKeymap = draft
    .mapValues { (_, mapped) -> if (mapped == action) null else mapped }
    .toMutableMap()
    .also { it[control] = action }
    .toMap()

internal fun missingRequiredControllerAction(keymap: ControllerKeymap): ControllerAction? =
    missingRequiredControllerAction(keymap, ControllerRawBindings())

internal fun missingRequiredControllerAction(
    keymap: ControllerKeymap,
    rawBindings: ControllerRawBindings,
): ControllerAction? = RequiredReachabilityActions.firstOrNull { required ->
    keymap.values.none { it == required } && rawBindings.bindingFor(required) == null
}

internal fun ControllerMappingSnapshot.toSettingsRows(): List<ControllerMappingRow> =
    RemappableControllerActions.map { action ->
        val control = draft.entries.firstOrNull { (_, mapped) -> mapped == action }?.key
        val raw = draftRaw.bindingFor(action)
        ControllerMappingRow(
            id = controllerMappingActionRowId(action.name),
            actionName = action.name,
            title = controllerActionTitle(action),
            binding = raw?.displayLabel ?: control?.displayLabel ?: MimirCopy.NoBindingAssigned,
            capturing = pendingAction == action,
        )
    }

private val ControllerShortcutRowActions = mapOf(
    ControllerShortcutQuickLaunchRowId to ControllerAction.GLOBAL_QUICK_LAUNCH,
    ControllerShortcutUtilityRowId to ControllerAction.GLOBAL_UTILITY,
    ControllerShortcutTweakerRowId to ControllerAction.GLOBAL_TWEAKER,
)

internal fun controllerShortcutActionForRowId(rowId: String): ControllerAction? =
    ControllerShortcutRowActions[rowId]

internal fun ControllerMappingSnapshot.hasAppliedBinding(action: ControllerAction): Boolean =
    applied.values.any { it == action } || appliedRaw.bindingFor(action) != null

/** Settings shortcut rows project the same authority that dispatches the mapped action. */
internal fun ControllerMappingSnapshot.toShortcutSettingsRows(
    legacyRows: List<ControllerShortcutRow> = emptyList(),
): List<ControllerShortcutRow> {
    val legacyById = legacyRows.associateBy(ControllerShortcutRow::id)
    val rows = ControllerShortcutRowActions.map { (rowId, action) ->
        val raw = appliedRaw.bindingFor(action)
        val control = applied.entries.firstOrNull { (_, mapped) -> mapped == action }?.key
        val legacy = legacyById[rowId]
        val title = when (action) {
            ControllerAction.GLOBAL_QUICK_LAUNCH -> "Quick Launch"
            ControllerAction.GLOBAL_UTILITY -> "Utility"
            ControllerAction.GLOBAL_TWEAKER -> "Tweaker"
            else -> controllerActionTitle(action)
        }
        ControllerShortcutRow(
            id = rowId,
            title = title,
            supporting = when (action) {
                ControllerAction.GLOBAL_QUICK_LAUNCH -> "Open Quick Launch from any Mimir screen."
                ControllerAction.GLOBAL_UTILITY -> "Open Utility from any Mimir screen."
                ControllerAction.GLOBAL_TWEAKER -> "Open Tweaker from any Mimir screen."
                else -> "Open this Mimir tool from any screen."
            },
            value = raw?.displayLabel ?: control?.displayLabel ?: legacy?.value ?: MimirCopy.NoBindingAssigned,
            capturing = pendingAction == action,
        )
    }
    val hasAnyBinding = ControllerShortcutRowActions.values.any(::hasAppliedBinding) ||
        legacyRows.any { it.id == ControllerShortcutResetRowId }
    return rows + if (hasAnyBinding) {
        listOf(
            ControllerShortcutRow(
                id = ControllerShortcutResetRowId,
                title = "Reset controller shortcuts",
                supporting = "Clear optional Mimir tool shortcuts.",
                value = "Reset",
            ),
        )
    } else {
        emptyList()
    }
}

internal fun controllerRawBinding(keys: List<ControllerPhysicalKey>): ControllerRawBinding? {
    val normalized = keys
        .filter { key ->
            key.hasUsableCode && !isForbiddenControllerMappingKey(key.keyCode)
        }
        .map { key ->
            ControllerRawKey(
                descriptorHash = key.descriptorHash,
                vendorId = key.vendorId,
                productId = key.productId,
                keyCode = key.keyCode,
                scanCode = key.scanCode,
            )
        }
        .distinct()
        .take(MaxControllerChordKeys)
    if (normalized.isEmpty()) return null
    return runCatching { ControllerRawBinding(normalized) }
        .getOrNull()
        ?.takeIf(::isDispatchableControllerRawBinding)
}

internal fun isDispatchableControllerRawBinding(binding: ControllerRawBinding): Boolean =
    binding.keys.size in 1..MaxControllerChordKeys &&
        binding.keys.distinct().size == binding.keys.size &&
        binding.keys.all { key ->
            key.descriptorHash.length == 64 &&
                key.descriptorHash.all { it in '0'..'9' || it in 'a'..'f' } &&
                (key.keyCode != KeyEvent.KEYCODE_UNKNOWN || key.scanCode != 0) &&
                !isForbiddenControllerMappingKey(key.keyCode)
        }

/**
 * Android-owned keys must remain Android-owned even while the mapper is listening.
 *
 * The set is hoisted, not inline. This is called for every key DOWN while capture is active, and once
 * per key of every binding during decode and every `controllerRawBinding` build — an inline `setOf(...)`
 * allocated a nine-element LinkedHashSet on each of those.
 */
internal fun isForbiddenControllerMappingKey(keyCode: Int): Boolean =
    keyCode in ForbiddenControllerMappingKeyCodes

private val ForbiddenControllerMappingKeyCodes = setOf(
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_APP_SWITCH,
    KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_SLEEP,
    KeyEvent.KEYCODE_WAKEUP,
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
)

/**
 * Controller
 * Mapping's ALL CAPS action titles were content-authoring drift, not intent — every row this function
 * titles renders through the same `MimirType.SettingsActionTitle` token SettingsDetail.kt uses
 * sentence-case at 20 other sites (ControllerMappingOverlay.kt:248/250), so these titles normalise the
 * same way. `GLOBAL_QUICK_LAUNCH`/`GLOBAL_UTILITY`/`GLOBAL_TWEAKER` reuse the exact wording the sibling
 * Controller Shortcuts row list already uses for the same three actions (`toShortcutSettingsRows` below)
 * rather than introducing a third spelling, consistent with the no-binding sentinel.
 */
internal fun controllerActionTitle(action: ControllerAction): String = when (action) {
    ControllerAction.NAV_UP -> "Move up"
    ControllerAction.NAV_DOWN -> "Move down"
    ControllerAction.NAV_LEFT -> "Move left"
    ControllerAction.NAV_RIGHT -> "Move right"
    ControllerAction.CONFIRM -> "Select / Play"
    ControllerAction.CANCEL -> "Back"
    ControllerAction.SECONDARY -> "Secondary action"
    ControllerAction.TERTIARY -> "Edit / Options"
    ControllerAction.PAGE_LEFT -> "Previous page"
    ControllerAction.PAGE_RIGHT -> "Next page"
    ControllerAction.TRIGGER_LEFT -> "Left trigger"
    ControllerAction.TRIGGER_RIGHT -> "Right trigger"
    ControllerAction.SYSTEM_START -> "Settings"
    ControllerAction.SYSTEM_SELECT -> "Select action"
    ControllerAction.SYSTEM_MENU -> "Mimir menu"
    ControllerAction.SYSTEM_BACK -> "System back"
    ControllerAction.SYSTEM_HOME -> "System home"
    ControllerAction.GLOBAL_QUICK_LAUNCH -> "Quick Launch"
    ControllerAction.GLOBAL_UTILITY -> "Utility"
    ControllerAction.GLOBAL_TWEAKER -> "Tweaker"
    ControllerAction.GLOBAL_SETTINGS -> "Open settings"
    ControllerAction.SHOW_HINTS -> "Show hints"
}

internal fun controllerMappingActionFromName(name: String): ControllerAction? =
    ControllerAction.entries.firstOrNull { it.name == name && it in RemappableControllerActions }

internal fun controllerHintLabels(
    keymap: ControllerKeymap,
    rawBindings: ControllerRawBindings = ControllerRawBindings(),
): MimirControllerHintLabels {
    fun label(action: ControllerAction): String =
        rawBindings.bindingFor(action)?.displayLabel
            ?: keymap.entries.firstOrNull { (_, mapped) -> mapped == action }?.key?.displayLabel.orEmpty()
    fun paired(left: ControllerAction, right: ControllerAction): String {
        val leftLabel = label(left)
        val rightLabel = label(right)
        return if (leftLabel.isBlank() || rightLabel.isBlank()) "" else "$leftLabel/$rightLabel"
    }
    val pageLeft = label(ControllerAction.PAGE_LEFT)
    val pageRight = label(ControllerAction.PAGE_RIGHT)
    return MimirControllerHintLabels(
        byFactoryToken = mapOf(
            "A" to label(ControllerAction.CONFIRM),
            "B" to label(ControllerAction.CANCEL),
            "X" to label(ControllerAction.SECONDARY),
            "Y" to label(ControllerAction.TERTIARY),
            "L" to pageLeft,
            "R" to pageRight,
            "L1" to pageLeft,
            "R1" to pageRight,
            "L/R" to paired(ControllerAction.PAGE_LEFT, ControllerAction.PAGE_RIGHT),
            "L1/R1" to paired(ControllerAction.PAGE_LEFT, ControllerAction.PAGE_RIGHT),
            "L2" to label(ControllerAction.TRIGGER_LEFT),
            "R2" to label(ControllerAction.TRIGGER_RIGHT),
            "L2/R2" to paired(ControllerAction.TRIGGER_LEFT, ControllerAction.TRIGGER_RIGHT),
            "START" to label(ControllerAction.SYSTEM_START),
            "SELECT" to label(ControllerAction.SYSTEM_SELECT),
            "M" to label(ControllerAction.SYSTEM_MENU),
            "L3" to label(ControllerAction.SHOW_HINTS),
        ),
    )
}

private fun controllerMappingActionRowId(actionName: String): String =
    "controls/controller/mapping/action/$actionName"

internal enum class ControllerMappingInputDecision { Continue, Consume }

/**
 * Motion capture has no discrete UP event. Once an axis is accepted, quarantine every following
 * sample until the controller reports neutral so the held stick/HAT/trigger cannot immediately
 * navigate the mapping surface with the newly applied binding.
 */
internal class ControllerMappingMotionInputGate {
    private var awaitingNeutral = false

    fun handle(
        samples: List<Map<Int, Float>>,
        captureActive: Boolean,
        capture: (PhysicalControl) -> Boolean,
    ): ControllerMappingInputDecision {
        if (awaitingNeutral) {
            if (samples.lastOrNull()?.let(::controllerMotionIsAtRest) == true) {
                awaitingNeutral = false
            }
            return ControllerMappingInputDecision.Consume
        }
        if (!captureActive) return ControllerMappingInputDecision.Continue

        val captured = samples.firstNotNullOfOrNull(::physicalControlForMotionAxes)
            ?.let(capture) == true
        if (captured && samples.lastOrNull()?.let(::controllerMotionIsAtRest) != true) {
            awaitingNeutral = true
        }
        return ControllerMappingInputDecision.Consume
    }

    fun reset() {
        awaitingNeutral = false
    }
}

private data class ControllerMappingPress(
    val deviceId: Int,
    val keyCode: Int,
    val scanCode: Int,
)

/**
 * Capture gate ahead of shortcut dispatch and InputAuthority. It consumes the captured DOWN and its
 * trailing UP, so the button can never both bind and activate the focused row.
 */
internal class ControllerMappingInputGate(
    private val clock: () -> Long,
) {
    private val pendingReleases = mutableMapOf<ControllerMappingPress, Long>()
    private val held = linkedMapOf<ControllerMappingPress, ControllerPhysicalKey>()
    private val captured = linkedMapOf<ControllerMappingPress, ControllerPhysicalKey>()

    fun handleKey(
        key: ControllerPhysicalKey?,
        keyCode: Int,
        down: Boolean,
        repeatCount: Int,
        longPress: Boolean,
        captureActive: Boolean,
        capture: (List<ControllerPhysicalKey>) -> Boolean,
        cancel: () -> Boolean,
    ): ControllerMappingInputDecision {
        val now = clock()
        pendingReleases.entries.removeAll { (_, deadline) -> now >= deadline }
        val press = key?.let { ControllerMappingPress(it.deviceId, it.keyCode, it.scanCode) }
        if (!down && press != null && pendingReleases.remove(press) != null) {
            return ControllerMappingInputDecision.Consume
        }
        if (!captureActive) {
            if (!down && press != null && held.remove(press) != null) {
                if (held.isEmpty()) captured.clear()
                return ControllerMappingInputDecision.Consume
            }
            return ControllerMappingInputDecision.Continue
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (down && repeatCount == 0) {
                cancel()
                if (press != null) pendingReleases[press] = now + PendingReleaseTimeoutMs
                held.clear()
                captured.clear()
            }
            return ControllerMappingInputDecision.Consume
        }
        if (key == null) return ControllerMappingInputDecision.Consume
        if (down) {
            if (repeatCount > 0 || longPress || isForbiddenControllerMappingKey(key.keyCode)) {
                return ControllerMappingInputDecision.Consume
            }
            if (press != null && press !in captured && captured.size < MaxControllerChordKeys) {
                held[press] = key
                captured[press] = key
            }
            return ControllerMappingInputDecision.Consume
        }
        if (press != null) held.remove(press)
        if (held.isEmpty() && captured.isNotEmpty()) {
            val completed = captured.values.toList()
            captured.clear()
            capture(completed)
        }
        return ControllerMappingInputDecision.Consume
    }

    fun reset() {
        pendingReleases.clear()
        held.clear()
        captured.clear()
    }

    private companion object {
        const val PendingReleaseTimeoutMs = 2_000L
    }
}

internal sealed interface ControllerRawInputDecision {
    data object Continue : ControllerRawInputDecision
    data object Consume : ControllerRawInputDecision
    data class Dispatch(val action: ControllerAction) : ControllerRawInputDecision
    data class ReplayStandard(val keyCode: Int) : ControllerRawInputDecision
}

/**
 * Runtime gate for explicit raw-key and chord mappings. A standard key that begins a configured chord
 * is held until the chord resolves; if the user releases it alone, its ordinary map is replayed once.
 */
internal class ControllerRawInputGate {
    private val blocked = linkedMapOf<ControllerMappingPress, ControllerPhysicalKey>()
    private val fired = mutableSetOf<ControllerMappingPress>()

    fun handleKey(
        key: ControllerPhysicalKey?,
        down: Boolean,
        repeatCount: Int,
        longPress: Boolean,
        enabled: Boolean,
        bindings: ControllerRawBindings,
    ): ControllerRawInputDecision {
        if (key == null) return ControllerRawInputDecision.Continue
        val press = ControllerMappingPress(key.deviceId, key.keyCode, key.scanCode)
        if (!enabled) {
            return if (!down && blocked.remove(press) != null) {
                fired.remove(press)
                ControllerRawInputDecision.Consume
            } else {
                ControllerRawInputDecision.Continue
            }
        }
        if (!down) {
            val wasBlocked = blocked.remove(press) ?: return ControllerRawInputDecision.Continue
            if (fired.remove(press)) return ControllerRawInputDecision.Consume
            return physicalControlForKeyCode(wasBlocked.keyCode)
                ?.let { ControllerRawInputDecision.ReplayStandard(wasBlocked.keyCode) }
                ?: ControllerRawInputDecision.Consume
        }
        if (press in blocked) return ControllerRawInputDecision.Consume
        if (repeatCount > 0 || longPress) return ControllerRawInputDecision.Continue
        if (bindings.byAction.values.none { it.contains(key) }) {
            return ControllerRawInputDecision.Continue
        }
        blocked[press] = key
        val match = bindings.byAction.entries
            .asSequence()
            .filter { (_, binding) -> binding.matchesHeld(blocked.values) }
            .maxByOrNull { (_, binding) -> binding.keys.size }
            ?: return ControllerRawInputDecision.Consume
        val matchedPresses = match.value.matchedPresses(blocked)
            ?: return ControllerRawInputDecision.Consume
        fired += matchedPresses
        return ControllerRawInputDecision.Dispatch(match.key)
    }

    fun reset() {
        blocked.clear()
        fired.clear()
    }
}

private fun ControllerRawBinding.matchedPresses(
    held: Map<ControllerMappingPress, ControllerPhysicalKey>,
): Set<ControllerMappingPress>? {
    val available = held.entries.toMutableList()
    val result = linkedSetOf<ControllerMappingPress>()
    keys.forEach { expected ->
        val index = available.indexOfFirst { (_, actual) -> expected.matches(actual) }
        if (index < 0) return null
        result += available.removeAt(index).key
    }
    return result
}

/** First activated console control in one axis sample; neutral/noise returns null. */
internal fun physicalControlForMotionAxes(axes: Map<Int, Float>): PhysicalControl? {
    val l2 = maxOf(
        axes[MotionEvent.AXIS_LTRIGGER] ?: 0f,
        axes[MotionEvent.AXIS_BRAKE] ?: 0f,
    )
    val r2 = maxOf(
        axes[MotionEvent.AXIS_RTRIGGER] ?: 0f,
        axes[MotionEvent.AXIS_GAS] ?: 0f,
    )
    if (l2 > MotionCaptureThreshold) return PhysicalControl.L2
    if (r2 > MotionCaptureThreshold) return PhysicalControl.R2
    val x = dominantMotionAxis(
        axes[MotionEvent.AXIS_HAT_X] ?: 0f,
        axes[MotionEvent.AXIS_X] ?: 0f,
    )
    val y = dominantMotionAxis(
        axes[MotionEvent.AXIS_HAT_Y] ?: 0f,
        axes[MotionEvent.AXIS_Y] ?: 0f,
    )
    return when {
        x < -MotionCaptureThreshold -> PhysicalControl.DPAD_LEFT
        x > MotionCaptureThreshold -> PhysicalControl.DPAD_RIGHT
        y < -MotionCaptureThreshold -> PhysicalControl.DPAD_UP
        y > MotionCaptureThreshold -> PhysicalControl.DPAD_DOWN
        else -> null
    }
}

/**
 * Has every axis genuinely returned to rest?
 *
 * ONE implementation for all three motion quarantines — the mapping capture gate and the shortcut
 * capture quarantine both used to carry their own verbatim copy, and both copies had the same two
 * defects, which is what a copy costs.
 *
 * Two-level latch, carried over from `DefaultInputAuthority`, which arms at `InputTuning.axisActivate`
 * (0.55) and re-arms only below `axisRelease` (0.35), triggers at 0.55/0.25. The copies used
 * [MotionCaptureThreshold] in BOTH directions — activate above it, "at rest" at or below it — which is
 * not a latch at all: the two conditions are complementary, so a stick easing back through the
 * activation point read as fully released and a noisy axis resting near it flipped state sample by
 * sample. The player pushing the same stick again then bound a second control they never meant to press.
 *
 * An axis the sample does not mention is NOT treated as centred. Absence is not release — it is no
 * evidence either way, and it must not satisfy the wait.
 *
 * Written as index loops over hoisted arrays because it runs per motion sample for as long as a stick is
 * held: the copies each rebuilt two lists and two lambdas on every call.
 */
internal fun controllerMotionIsAtRest(axes: Map<Int, Float>): Boolean {
    for (axis in MotionRestDirectionalAxes) {
        val value = axes[axis] ?: return false
        if (kotlin.math.abs(value) > MotionCaptureAxisRelease) return false
    }
    for (axis in MotionRestTriggerAxes) {
        val value = axes[axis] ?: return false
        if (value > MotionCaptureTriggerRelease) return false
    }
    return true
}

private val MotionRestDirectionalAxes = intArrayOf(
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
)

private val MotionRestTriggerAxes = intArrayOf(
    MotionEvent.AXIS_LTRIGGER,
    MotionEvent.AXIS_RTRIGGER,
    MotionEvent.AXIS_BRAKE,
    MotionEvent.AXIS_GAS,
)

private fun dominantMotionAxis(first: Float, second: Float): Float =
    if (kotlin.math.abs(first) >= kotlin.math.abs(second)) first else second

/** Activation point. Matches `InputTuning.axisActivate` / `triggerActivate`. */
private const val MotionCaptureThreshold = 0.55f

/** Directional re-arm point. Matches `InputTuning.axisRelease`; must stay below [MotionCaptureThreshold]. */
private const val MotionCaptureAxisRelease = 0.35f

/** Trigger re-arm point. Matches `InputTuning.triggerRelease`; triggers rest lower than sticks do. */
private const val MotionCaptureTriggerRelease = 0.25f
