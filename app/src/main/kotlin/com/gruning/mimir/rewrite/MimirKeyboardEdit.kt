package com.gruning.mimir.rewrite

/**
 * Turning `MimirKeyboard`'s whole-value reports into the edits an `InputConnection` accepts.
 *
 * `MimirKeyboard` is a controlled component: every key press hands back the ENTIRE field value. An
 * `InputConnection` does not take a value — it takes edits, `deleteSurroundingText` and `commitText`.
 * The two are reconciled here, as a pure function, so the reconciliation can be tested without an IME,
 * a device, or a foreign app to type into.
 *
 * **Carried over from the reverted injection surface, which needed the identical shape** (backspaces
 * then text, for a caret with nowhere to seek). Only the verbs on the other side changed: what was
 * `input keyevent 67` and `input text` through a root shell is now two ordinary framework calls.
 */
internal data class MimirKeyboardEdit(
    /** Characters to remove behind the caret before committing. */
    val deleteBefore: Int,
    /** Text to commit at the caret. */
    val commit: String,
) {
    val isEmpty: Boolean get() = deleteBefore == 0 && commit.isEmpty()
}

/**
 * The edit that takes a field reading [previous] to reading [next].
 *
 * Keeps the common prefix and sends only the divergence, so an ordinary keystroke is one character
 * rather than a retype of the line. A deletion becomes [MimirKeyboardEdit.deleteBefore]; a mid-word
 * correction becomes both, and the delete has to come first because the caret is at the end and there
 * is nothing to seek with.
 *
 * [deleteBefore] can never exceed `previous.length`, which is the property that keeps this from eating
 * text the player did not type in this session.
 */
internal fun mimirKeyboardEdit(previous: String, next: String): MimirKeyboardEdit {
    var shared = 0
    val limit = minOf(previous.length, next.length)
    while (shared < limit && previous[shared] == next[shared]) shared++
    return MimirKeyboardEdit(deleteBefore = previous.length - shared, commit = next.substring(shared))
}
