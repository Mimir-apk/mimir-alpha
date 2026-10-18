package com.gruning.mimir.rewrite.core.input

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Tap activation that plants **no focus node** — the sanctioned replacement for `Modifier.clickable`
 * on any element that also registers with the [FocusAuthority] via [mimirFocusable].
 *
 * ## Why this exists
 *
 * `clickable` and `combinedClickable` both build an `AbstractClickableNode`, which constructs a
 * `FocusableNode` and therefore a real `FocusTargetModifierNode` — a **second** Compose focus target
 * beside the single authority the element already registers with. `tools/mimir_ui_invariants_gate.py`
 * cannot see this: its F1 rules grep only for the literals `.focusable(` / `focusTarget(`, and neither
 * modifier matches, so the drift is silent.
 *
 * **The rule this serves is the narrow one.** The invariant this repo enforces is one authority for
 * *controller navigation*, not the absence of any `FocusableNode` anywhere. A **touch-only** surface that is never controller-navigated may keep using
 * `clickable`. What is a defect is `clickable` *beside* `mimirFocusable` on the same element.
 *
 * ## What it replaces, and the part that is easy to drop
 *
 * This is the three-part pattern the converted surfaces arrived at independently (`HomeLaunchCard.kt`,
 * `GamelistCarousel.kt`, `FileBrowserController.kt`), promoted here so it stops being copy-pasted:
 *
 *  1. [mimirFocusable] — the single authority. **Not** applied here; chain it yourself, so this modifier
 *     stays usable where the focus modifier arrives from somewhere else (`MimirKeyboard`'s key decorator
 *     hands one in from the app layer).
 *  2. A raw pointer gesture for activation — no focus node.
 *  3. **The semantics block, which is not optional.** `clickable` publishes a click ACTION to
 *     accessibility services; a pointer gesture publishes nothing. Worse,
 *     `AbstractClickableNode.getShouldMergeDescendantSemantics` is a hardcoded `return true` in the
 *     shipped `foundation-android:1.7.3` (verified from the artifact's bytecode), so `clickable` also
 *     merged the element's label into one node. Replacing it without `mergeDescendants = true` leaves
 *     the click action on a text-less parent with the label as a separate child — TalkBack lands on a
 *     label with no action. **This is the part a hurried migration drops, and no F1 rule would catch it.**
 *
 * ## `onTap` only — deliberately
 *
 * There is no double-tap parameter and there must not be one. Registering `onDoubleTap` puts the
 * platform double-tap timeout (~300 ms) in front of **every** single tap, which is exactly the defect
 * the Files surface removed its double-tap gesture to fix.
 *
 * ## Focus-on-tap is opt-in
 *
 * Pass [authority] and [key] together when the tap must publish focus *before* acting. That ordering is
 * load-bearing wherever the action resolves its target from the currently-focused key rather than from
 * the tapped element (Home binds `onPlay` to a CONFIRM dispatch that does exactly this): publishing
 * focus first is what makes the tapped element — not whatever the controller last left focused — the
 * thing that acts. [FocusAuthority.requestFocus] is synchronous in [DefaultFocusAuthority] (a `setFocus`
 * write to the state flow, no coroutine, no frame boundary), so the activation on the next line already
 * observes the new focus.
 *
 * Leave both null to activate without touching focus, which is what a surface wants when its focus is
 * driven entirely by the controller and a touch is a pure activation (`MimirKeyboard`'s keys).
 *
 * ## Gesture-node lifetime
 *
 * The gesture node is keyed on `Unit` and reads its targets through [rememberUpdatedState], deliberately
 * NOT keyed on the lambda. `pointerInput`'s keys are a RESTART contract: a changed key disposes the
 * detector and cancels whatever gesture is in flight, so a key that changes while a finger is down eats
 * the tap. This is hardening rather than a bug fix — Compose's strong skipping currently memoizes these
 * lambda literals anyway — but it makes the node's lifetime independent of a compiler setting and of
 * every future capture added to the callers' lambdas.
 *
 * @param label the accessibility action label, read by TalkBack as "double-tap to <label>".
 * @param enabled when false, neither the gesture nor the click action is installed — the element becomes
 *   inert to touch and reports no action, rather than reporting one that does nothing.
 * @param role the semantics role; pass null to leave the element's role unset.
 * @param contentDescription optional label for the merged node. Pass it when the element's own children
 *   do not already carry readable text.
 * @param authority pass together with [key] to publish focus before activating; null for no focus write.
 * @param key pass together with [authority]; null for no focus write.
 * @param interactionSource optional press reporting for a draw-phase response (a scale, a tint) that reads
 *   the source. Null (the default) leaves the gesture exactly as it was. When given, a touch emits
 *   [PressInteraction.Press] on down; [PressInteraction.Release] when the pointer comes up and the tap
 *   activates; and [PressInteraction.Cancel] when the gesture is cancelled, consumed by another handler,
 *   dragged out of bounds, or torn down mid-press (the element is disabled, or leaves composition) — so a
 *   Press is never left dangling. Nothing is drawn here and no focus node is planted; the source is
 *   reported to, never read. A disabled element reports nothing.
 * @param onActivate the action. Runs on tap, and on an accessibility click.
 */
@Composable
fun Modifier.mimirActivatable(
    label: String,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    contentDescription: String? = null,
    authority: FocusAuthority? = null,
    key: FocusKey? = null,
    interactionSource: MutableInteractionSource? = null,
    onActivate: () -> Unit,
): Modifier {
    // Read through `rememberUpdatedState` unconditionally — these are `remember` calls and may not sit
    // behind the `enabled` branch below without breaking Compose's slot table on an enabled/disabled flip.
    val currentActivate by rememberUpdatedState(onActivate)
    val currentAuthority by rememberUpdatedState(authority)
    val currentKey by rememberUpdatedState(key)
    val currentSource by rememberUpdatedState(interactionSource)

    if (!enabled) return this

    // ONE activation path for both the finger and the accessibility service, so the two can never drift
    // into different meanings of "activate" — the same reason the converted surfaces route their art tap
    // and their button through a single helper.
    val activate: () -> Unit = {
        val focusAuthority = currentAuthority
        val focusKey = currentKey
        if (focusAuthority != null && focusKey != null) focusAuthority.requestFocus(focusKey)
        currentActivate()
    }

    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { offset ->
                    // Captured once per press so Release/Cancel always reach the source that saw the Press,
                    // even if the caller swaps sources mid-gesture. Null source: nothing to report.
                    val source = currentSource
                    if (source != null) {
                        val press = PressInteraction.Press(offset)
                        source.tryEmit(press)
                        var released = false
                        try {
                            released = tryAwaitRelease()
                        } finally {
                            // `finally` + non-suspending tryEmit: this also runs when the node is torn down
                            // mid-press (disabled, or the composable leaves), where a suspending emit on the
                            // cancelled coroutine would never land and leave the Press leaked.
                            source.tryEmit(
                                if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                            )
                        }
                    }
                },
                onTap = { activate() },
            )
        }
        .semantics(mergeDescendants = true) {
            role?.let { this.role = it }
            contentDescription?.let { this.contentDescription = it }
            onClick(label = label) {
                activate()
                true
            }
        }
}
