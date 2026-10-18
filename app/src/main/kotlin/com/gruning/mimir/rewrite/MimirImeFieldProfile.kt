package com.gruning.mimir.rewrite

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardFieldKind

/**
 * What [MimirInputMethodService] decides from the field another app opened, as pure functions of
 * [EditorInfo.inputType] / [EditorInfo.imeOptions] so each class of field can be pinned without an IME.
 *
 * W2f (2026-10-03): the keyboard becomes the system default, so it now meets every kind of field and must stop
 * treating them all as plain text.
 */
internal data class MimirImeFieldProfile(
    /** Which of the in-app keyboard's existing layouts the field opens on. */
    val kind: MimirKeyboardFieldKind,
    /**
     * A password-class field. Its text is drawn as dots, is never seeded from the field, and is never logged.
     * Covers PASSWORD, VISIBLE_PASSWORD, WEB_PASSWORD and the numeric PIN variation: a field that asks for any of
     * them wants no preview and no suggestions, and this keyboard has no suggestion strip at all.
     */
    val secret: Boolean,
) {
    companion object {
        val Default = MimirImeFieldProfile(MimirKeyboardFieldKind.TEXT, secret = false)
    }
}

internal fun mimirImeFieldProfile(inputType: Int): MimirImeFieldProfile {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER -> MimirImeFieldProfile(
            MimirKeyboardFieldKind.NUMBER,
            secret = variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME,
        -> MimirImeFieldProfile(MimirKeyboardFieldKind.NUMBER, secret = false)
        InputType.TYPE_CLASS_TEXT -> when (variation) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> MimirImeFieldProfile(MimirKeyboardFieldKind.TEXT, secret = true)
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            -> MimirImeFieldProfile(MimirKeyboardFieldKind.EMAIL, secret = false)
            InputType.TYPE_TEXT_VARIATION_URI -> MimirImeFieldProfile(MimirKeyboardFieldKind.URI, secret = false)
            else -> MimirImeFieldProfile.Default
        }
        else -> MimirImeFieldProfile.Default
    }
}

/** What the keyboard's enter key (the Done key) does for the field. */
internal sealed interface MimirImeEnter {
    /** The field's own IME action; [hideAfter] is false for Next/Previous, which move to another field. */
    data class PerformAction(val action: Int, val hideAfter: Boolean) : MimirImeEnter

    /** A multi-line field with no action of its own: enter is a line break. */
    data object InsertNewline : MimirImeEnter

    /** Nothing to perform; the key just closes the keyboard. */
    data object Hide : MimirImeEnter
}

/**
 * Honours the field's declared action instead of a blind ENTER, which would submit forms the field never asked
 * to submit. Next and Previous leave the keyboard up (the next field opens its own input); Go, Search, Send and
 * Done perform and then close. A multi-line field without an action, or with `IME_FLAG_NO_ENTER_ACTION`, gets a
 * line break.
 */
internal fun mimirImeEnterBehavior(imeOptions: Int, inputType: Int): MimirImeEnter {
    val action = imeOptions and EditorInfo.IME_MASK_ACTION
    val multiLine = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
        inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
        return if (multiLine) MimirImeEnter.InsertNewline else MimirImeEnter.Hide
    }
    return when (action) {
        EditorInfo.IME_ACTION_NEXT,
        EditorInfo.IME_ACTION_PREVIOUS,
        -> MimirImeEnter.PerformAction(action, hideAfter = false)
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_DONE,
        -> MimirImeEnter.PerformAction(action, hideAfter = true)
        else -> if (multiLine) MimirImeEnter.InsertNewline else MimirImeEnter.Hide
    }
}
