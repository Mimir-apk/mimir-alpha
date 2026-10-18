package com.gruning.mimir.rewrite.core.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** Focus scope pushed while an in-app keyboard is open. */
val MimirKeyboardFocusScope = FocusScope("mimir/keyboard")

/** Stable focus key for a semantic keyboard key ID (matches [com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyId.value]). */
fun mimirKeyboardFocusKey(id: String): FocusKey = FocusKey("keyboard/$id")

/** Where focus lands when a host enters without a landing key; the keyboard passes its own ([enter]'s landOn). */
const val MimirKeyboardInitialKeyId: String = "action/done"

/**
 * Host-side keyboard focus binding. Bridges [FocusAuthority] to [com.gruning.mimir.rewrite.core.ui.MimirKeyboard]
 * without importing core-ui. Call [enter]/[exit] from the keyboard controller lifecycle; wire [decorateKey] at the
 * call site into [com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration].
 */
class MimirKeyboardFocusBinding internal constructor(
    private val authority: FocusAuthority,
    private val scope: FocusScope,
    private val initialKeyId: String,
) {
    private val activators = mutableMapOf<String, () -> Unit>()
    private var entered = false
    internal var lastFocusedKeyId: String? = null

    // L2/R2 shoulder shortcuts (shared keyboard controls): the keyboard's own case-toggle and
    // character-set-cycle mutators, registered by core-ui via MimirKeyboardController.onShoulderActionsChanged
    // (see MimirKeyboardHost.kt). Stored here rather than on the controller itself so dispatchMimirKeyboardInput
    // — which only receives a binding, never a controller — can reach them the same way it already reaches
    // per-key activators.
    private var toggleCaseAction: (() -> Unit)? = null
    private var cycleCharacterSetAction: (() -> Unit)? = null

    // Row geometry: RIGHT from `paste` must land on `spacebar`; RIGHT from a row's last key
    // wraps to that SAME row's first key.
    // Registered by core-ui the same way as the shoulder actions above: a closure that reads the keyboard's
    // CURRENT row layout at invocation time (see MimirKeyboard.kt's `LaunchedEffect(controller)`), because
    // the row structure changes shape when R2 cycles ALPHA/NUMBERS/SYMBOLS. Kept row-local and explicit
    // rather than routed through DefaultFocusAuthority.moveFocus's pixel geometry, which cannot express
    // "next key in row order" for a grid of unequal-width keys (paste/spacebar/modifiers are wider than
    // letter keys).
    private var rowLayoutProvider: (() -> List<List<String>>)? = null

    fun enter() = enter(initialKeyId)

    /** Enters with focus on [landOn] (q for an empty field, Done for one with text). */
    fun enter(landOn: String) {
        if (entered) return
        lastFocusedKeyId = landOn
        authority.rememberFocusForScope(scope, mimirKeyboardFocusKey(landOn))
        entered = true
        authority.pushScope(scope)
        authority.requestFocus(mimirKeyboardFocusKey(landOn))
    }

    fun exit() {
        if (!entered) return
        entered = false
        activators.clear()
        toggleCaseAction = null
        cycleCharacterSetAction = null
        rowLayoutProvider = null
        // discardScope, not a guarded popScope(): a GLOBAL_UTILITY/GLOBAL_QUICK_LAUNCH press can push
        // another scope (e.g. Utility/Brightness) over this one BEFORE the host's DisposableEffect
        // disposes and calls exit() (dispatchMimirInput intercepts the global command ahead of the
        // keyboard's own dispatch -- see MimirAppInputDispatch.kt). A plain popScope() guarded on
        // "is this scope on top" used to skip the pop entirely in that case, leaving this scope on the
        // stack forever -- the covering scope closing later landed focus back in a keyboard whose
        // activators had already been cleared above, and CONFIRM silently did nothing. discardScope
        // removes this scope wherever it sits without disturbing whatever is actually active now.
        authority.discardScope(scope)
    }

    fun registerShoulderActions(toggleCase: () -> Unit, cycleCharacterSet: () -> Unit) {
        toggleCaseAction = toggleCase
        cycleCharacterSetAction = cycleCharacterSet
    }

    /** True if a case toggle was actually wired (the keyboard is mounted and registered it). */
    fun toggleCase(): Boolean {
        val action = toggleCaseAction ?: return false
        action.invoke()
        return true
    }

    /** True if a character-set cycle was actually wired (the keyboard is mounted and registered it). */
    fun cycleCharacterSet(): Boolean {
        val action = cycleCharacterSetAction ?: return false
        action.invoke()
        return true
    }

    /** Registers the keyboard's current row layout — key IDs in row/column order — for LEFT/RIGHT navigation. */
    fun registerRowLayout(rows: () -> List<List<String>>) {
        rowLayoutProvider = rows
    }

    /**
     * Row-relative LEFT/RIGHT neighbor of [focusedId], wrapping within the SAME row (console-keyboard
     * convention). Returns null when no row layout is registered (keyboard not
     * yet mounted) or [focusedId] is not found in any registered row — callers fall back to geometric
     * [FocusAuthority.moveFocus] in either case, never leaving the directional input unhandled outright.
     * A single-key row wraps to itself (still a "move", satisfying Rule 4 — directional input is always
     * consumed).
     */
    internal fun rowNeighbor(focusedId: String, direction: ControllerAction): String? {
        val rows = rowLayoutProvider?.invoke() ?: return null
        val row = rows.firstOrNull { focusedId in it } ?: return null
        if (row.size <= 1) return focusedId
        val index = row.indexOf(focusedId)
        return when (direction) {
            ControllerAction.NAV_RIGHT -> row[(index + 1) % row.size]
            ControllerAction.NAV_LEFT -> row[(index - 1 + row.size) % row.size]
            else -> null
        }
    }

    fun registerActivator(id: String, activate: () -> Unit) {
        activators[id] = activate
    }

    fun activateFocused(): Boolean {
        val focused = authority.state.value.focused?.value?.removePrefix("keyboard/") ?: return false
        val activate = activators[focused] ?: run {
            // A focused keyboard key with no registered activator used to fail completely silently —
            // CONFIRM would be swallowed (dispatchMimirKeyboardInput returns true from the directional
            // branch but false here), navigation kept working (it lives entirely in FocusAuthority, not
            // this map), and nothing typed. That silence is what made this class of defect expensive to
            // diagnose. Every surface wires decorateKey() to register an activator in the same
            // composition pass that registers the key with FocusAuthority (MimirKeyboardFocus.kt's
            // decorateKey), so this branch should be unreachable in steady state; if it fires, something
            // is registering focus targets and activators through two different paths, or reconcileFocus
            // pruned an activator for a key that is still the focused one. runCatching: Log is unmocked
            // on the JVM (unit tests) and must never change behavior there.
            runCatching {
                android.util.Log.w(
                    "MimirKeyboardFocus",
                    "activateFocused: no activator registered for focused key '$focused' " +
                        "(known activators=${activators.keys.sorted()})",
                )
            }
            return false
        }
        lastFocusedKeyId = focused
        activate.invoke()
        return true
    }

    fun reconcileFocus(visibleKeyIds: Set<String>) {
        activators.keys.filter { it !in visibleKeyIds }.forEach(activators::remove)
        val current = authority.state.value
        if (current.scope != scope) return
        val focusedId = current.focused?.value?.removePrefix("keyboard/")
        if (focusedId != null && focusedId in visibleKeyIds) {
            lastFocusedKeyId = focusedId
            return
        }
        val target = lastFocusedKeyId?.takeIf { it in visibleKeyIds }
            ?: initialKeyId.takeIf { it in visibleKeyIds }
            ?: visibleKeyIds.firstOrNull()
        if (target != null) {
            authority.requestFocus(mimirKeyboardFocusKey(target))
            lastFocusedKeyId = target
        }
    }

    @Composable
    fun decorateKey(id: String, activate: () -> Unit): Pair<Modifier, Boolean> {
        registerActivator(id, activate)
        val focus by authority.state.collectAsState()
        val key = mimirKeyboardFocusKey(id)
        val focused = focus.scope == scope && focus.focused == key
        val modifier = Modifier.mimirFocusable(authority = authority, key = key, scope = scope)
        return modifier to focused
    }
}

/** Test-only factory for cross-module unit tests. */
fun createMimirKeyboardFocusBindingForTests(
    authority: FocusAuthority,
    scope: FocusScope = MimirKeyboardFocusScope,
    initialKeyId: String = MimirKeyboardInitialKeyId,
): MimirKeyboardFocusBinding = MimirKeyboardFocusBinding(authority, scope, initialKeyId)

@Composable
fun rememberMimirKeyboardFocusBinding(
    authority: FocusAuthority,
    active: Boolean,
    scope: FocusScope = MimirKeyboardFocusScope,
    initialKeyId: String = MimirKeyboardInitialKeyId,
): MimirKeyboardFocusBinding? {
    val binding = remember(authority, scope, initialKeyId) {
        MimirKeyboardFocusBinding(authority, scope, initialKeyId)
    }
    return binding.takeIf { active }
}

/**
 * Routes controller input while the keyboard scope is active. B and Back both cancel: the host's [onDismiss], the
 * keyboard's "B · Back" legend. Done is A on the check-mark key. Returns true when the action was consumed.
 *
 * Consistent Back behavior: B used to press Done here, so it meant Done in the profile and Tome
 * editors but cancel in the RetroAchievements and artwork sign-in editors. One meaning everywhere now.
 */
fun dispatchMimirKeyboardInput(
    authority: FocusAuthority,
    scope: FocusScope,
    action: ControllerAction,
    binding: MimirKeyboardFocusBinding,
    onDismiss: () -> Unit,
): Boolean {
    if (authority.state.value.scope != scope) return false
    return when {
        // RIGHT/LEFT are row-relative index moves within the keyboard's own row model , not a geometric contest — see `MimirKeyboardFocusBinding.rowNeighbor`. Falls back to
        // the generic geometric `moveFocus` only when no row layout is registered yet or the focused key
        // isn't found in it, so a mid-mount frame degrades to the old (pre-fix) behavior rather than a dead
        // end. Either branch always returns true: Rule 4, directional input is always consumed.
        action == ControllerAction.NAV_LEFT || action == ControllerAction.NAV_RIGHT -> {
            val focusedId = authority.state.value.focused?.value?.removePrefix("keyboard/")
            val rowTarget = focusedId?.let { binding.rowNeighbor(it, action) }
            if (rowTarget != null) {
                authority.requestFocus(mimirKeyboardFocusKey(rowTarget))
            } else {
                authority.moveFocus(action)
            }
            binding.lastFocusedKeyId = authority.state.value.focused
                ?.value
                ?.removePrefix("keyboard/")
            true
        }
        // UP/DOWN retain the geometric mover, which handles rows with differing key counts.
        action.isDirectional -> {
            authority.moveFocus(action)
            binding.lastFocusedKeyId = authority.state.value.focused
                ?.value
                ?.removePrefix("keyboard/")
            true
        }
        action == ControllerAction.CONFIRM -> binding.activateFocused()
        // L2 = caps/shift, R2 = cycle character set forward with wraparound.
        // Both are TRIGGER_LEFT/TRIGGER_RIGHT (core-input/ControllerKeymap.kt's factory map) so this fires
        // for every keyboard host in the app uniformly, not per-surface. False (unhandled) when no keyboard
        // is actually mounted to have registered the action — the caller falls through same as any other
        // unhandled action, never silently eating the trigger.
        action == ControllerAction.TRIGGER_LEFT -> binding.toggleCase()
        action == ControllerAction.TRIGGER_RIGHT -> binding.cycleCharacterSet()
        action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK -> {
            onDismiss()
            true
        }
        else -> false
    }
}
