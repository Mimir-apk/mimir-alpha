package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Stable semantic identity for one keyboard key across shift/symbol layout changes. */
@JvmInline
value class MimirKeyboardKeyId(val value: String) {
    init {
        require(value.isNotBlank()) { "MimirKeyboardKeyId must not be blank" }
    }
}

/** Canonical keyboard key IDs — layout-independent so focus can reconcile across shift/symbol toggles. */
object MimirKeyboardKeyIds {
    fun action(raw: String): MimirKeyboardKeyId = MimirKeyboardKeyId("action/$raw")
    fun character(value: String): MimirKeyboardKeyId = MimirKeyboardKeyId("char/${value.lowercase()}")

    val Shift = action("shift")
    val Symbols = action("symbols")
    val Abc = action("abc")
    val Back = action("back")
    val Paste = action("paste")
    val Space = action("space")
    val Done = action("done")
    val Trackpad = action("trackpad")

    /** A secret field's Show/Hide chip. */
    val Reveal = action("reveal")
}

/** Host-supplied focus decoration for one key; [focusModifier] comes from the FocusAuthority host. */
data class MimirKeyboardKeyDecoration(
    val focusModifier: Modifier,
    val focused: Boolean,
)

/**
 * Optional host lifecycle hooks for controller-driven keyboard focus. Decoration is supplied separately
 * via [MimirKeyboard]'s [keyDecorator] parameter so it can stay @Composable without breaking remember stability.
 *
 * @param onShoulderActionsChanged registers the keyboard's OWN case-toggle and character-set-cycle
 *   mutators with the host, mirroring [onKeysChanged]'s "core-ui calls out, core-input stores and later
 *   invokes" shape (see [com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusBinding]). This is how
 *   L2 (caps/shift) and R2 (cycle character set) reach the keyboard's local Compose state from the app's
 *   input-dispatch layer, which runs before/outside the Composable that owns that state.
 * @param onRowLayoutChanged registers a provider of the keyboard's CURRENT row layout (key IDs in
 *   row/column order) with the host, same "core-ui calls out once, core-input stores and reads at
 *   invocation time" shape as [onShoulderActionsChanged] — needed so LEFT/RIGHT can move within a row
 *   instead of falling through to geometric pixel scoring.
 */
class MimirKeyboardController(
    val onEnter: () -> Unit,
    val onExit: () -> Unit,
    val onKeysChanged: (Set<String>) -> Unit = {},
    val onShoulderActionsChanged: (toggleCase: () -> Unit, cycleCharacterSet: () -> Unit) -> Unit = { _, _ -> },
    val onRowLayoutChanged: (rows: () -> List<List<String>>) -> Unit = {},
    /** Enters with focus on the given key ([mimirKeyboardLandingKeyId]); a host without it lands on its default. */
    val onEnterAt: ((keyId: String) -> Unit)? = null,
)

typealias MimirKeyboardKeyDecorator = @Composable (MimirKeyboardKeyId, () -> Unit) -> MimirKeyboardKeyDecoration
