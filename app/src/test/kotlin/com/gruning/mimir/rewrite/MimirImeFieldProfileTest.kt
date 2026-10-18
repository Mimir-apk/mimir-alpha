package com.gruning.mimir.rewrite

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardFieldKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W2f hardening: each class of field another app can open picks the right existing layout, and the enter key
 * honours the field's own action. Pure functions of `inputType` / `imeOptions`, so no IME is needed.
 */
class MimirImeFieldProfileTest {

    private fun profile(inputType: Int) = mimirImeFieldProfile(inputType)

    @Test
    fun plainTextAndNoInputTypeUseTheLetterLayout() {
        assertEquals(MimirImeFieldProfile(MimirKeyboardFieldKind.TEXT, false), profile(InputType.TYPE_CLASS_TEXT))
        assertEquals(MimirImeFieldProfile.Default, profile(InputType.TYPE_NULL))
        assertEquals(
            MimirImeFieldProfile.Default,
            profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
        )
    }

    @Test
    fun numberPhoneAndDateFieldsGetTheNumberLayout() {
        listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
        ).forEach { type ->
            assertEquals("type=$type", MimirImeFieldProfile(MimirKeyboardFieldKind.NUMBER, false), profile(type))
        }
    }

    @Test
    fun everyPasswordVariationIsSecret() {
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ).forEach { type ->
            assertEquals("type=$type", MimirImeFieldProfile(MimirKeyboardFieldKind.TEXT, true), profile(type))
        }
        // A numeric PIN is secret AND opens on the digit grid.
        assertEquals(
            MimirImeFieldProfile(MimirKeyboardFieldKind.NUMBER, true),
            profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        )
    }

    @Test
    fun emailAndUrlFieldsGetTheirOwnLetterLayers() {
        assertEquals(
            MimirKeyboardFieldKind.EMAIL,
            profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).kind,
        )
        assertEquals(
            MimirKeyboardFieldKind.EMAIL,
            profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS).kind,
        )
        assertEquals(
            MimirKeyboardFieldKind.URI,
            profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).kind,
        )
    }

    @Test
    fun aTextVariationNumberIsNotMistakenForAnotherClass() {
        // TYPE_TEXT_VARIATION_PASSWORD (0x80) shares its low bits with variations of other classes; only the
        // text class may read it as a password.
        assertEquals(
            MimirImeFieldProfile(MimirKeyboardFieldKind.NUMBER, false),
            profile(InputType.TYPE_CLASS_PHONE or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        )
    }

    @Test
    fun enterPerformsDoneGoSearchAndSendThenHides() {
        listOf(
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
        ).forEach { action ->
            assertEquals(
                MimirImeEnter.PerformAction(action, hideAfter = true),
                mimirImeEnterBehavior(action, InputType.TYPE_CLASS_TEXT),
            )
        }
    }

    @Test
    fun enterPerformsNextAndPreviousButLeavesTheKeyboardUp() {
        assertEquals(
            MimirImeEnter.PerformAction(EditorInfo.IME_ACTION_NEXT, hideAfter = false),
            mimirImeEnterBehavior(EditorInfo.IME_ACTION_NEXT, InputType.TYPE_CLASS_TEXT),
        )
        assertEquals(
            MimirImeEnter.PerformAction(EditorInfo.IME_ACTION_PREVIOUS, hideAfter = false),
            mimirImeEnterBehavior(EditorInfo.IME_ACTION_PREVIOUS, InputType.TYPE_CLASS_TEXT),
        )
    }

    @Test
    fun aMultiLineFieldWithNoActionGetsALineBreak() {
        val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(MimirImeEnter.InsertNewline, mimirImeEnterBehavior(EditorInfo.IME_ACTION_NONE, multiLine))
        assertEquals(MimirImeEnter.InsertNewline, mimirImeEnterBehavior(EditorInfo.IME_ACTION_UNSPECIFIED, multiLine))
        assertEquals(
            MimirImeEnter.InsertNewline,
            mimirImeEnterBehavior(EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION, multiLine),
        )
    }

    @Test
    fun aSingleLineFieldWithNoActionJustCloses() {
        assertEquals(
            MimirImeEnter.Hide,
            mimirImeEnterBehavior(EditorInfo.IME_ACTION_UNSPECIFIED, InputType.TYPE_CLASS_TEXT),
        )
        assertEquals(
            MimirImeEnter.Hide,
            mimirImeEnterBehavior(EditorInfo.IME_ACTION_NONE, InputType.TYPE_CLASS_NUMBER),
        )
    }
}
